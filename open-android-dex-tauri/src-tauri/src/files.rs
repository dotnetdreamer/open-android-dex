//! This computer's filesystem, served to the phone's File transfer window.
//!
//! The window itself is on the phone — it is a freeform window on the DeX
//! desktop beside Settings, Linux, Docker and the Web viewer, because the DeX
//! window on this host is usually fullscreen and a second host window would
//! land on another Space. So the *browser* is over there and the *files* are
//! over here, and something has to carry one to the other.
//!
//! What carries it is a `TcpListener` on loopback that the phone reaches
//! through `adb reverse tcp:7192 tcp:<hostPort>`. Every other option in the
//! tree was measured and is worse:
//!
//! * **The `content query` request queue.** ~990 ms per round, because
//!   `content` boots a JVM per invocation, plus the pump's own 150 ms poll —
//!   about 1.1 s to open a folder. AGENTS.md forbids new traffic on it
//!   outright, and `safe_arg` in scrcpy.rs restricts an argument to
//!   `[A-Za-z0-9._-]`, so a path could not ride it even if the latency were
//!   acceptable. A miss on that queue is *silently consumed*.
//! * **A mailbox verb in `openandroiddex-wmd`.** wmd is fixed by AGENTS.md as
//!   the privileged mechanism with "no UI, no policy", and `handle()` is a
//!   pure per-connection switch with no queue to leave anything in. Teaching
//!   it to relay file bytes would be exactly the policy it is defined not to
//!   hold, and would drag the `build.sh`/`build.cmd`/`desktop.yml` triple with
//!   it.
//! * **`adb push` / `adb pull`.** `run_adb_full` buffers to EOF and hard-kills
//!   at `ADB_TIMEOUT` (25 s), so any file bigger than a phone camera clip dies
//!   mid-copy; and `adb push` writes its progress with no trailing newline, a
//!   hazard `transfer.rs` already had to work around by matching log lines with
//!   `ends_with`. Neither gives byte-accurate progress, and a cancel would have
//!   to be a process kill.
//!
//! The chosen path is the one already measured for the window daemon: a PC↔
//! device round trip over an adb tunnel is **2.57 ms median, 5.30 ms p95**
//! (200 PINGs, doc/custom-titlebar-v2.md). Bytes stream on the same socket, so
//! progress is byte-accurate and a cancel is a `close()`.
//!
//! Shape of the thing, so the direction is not read backwards: **this module is
//! a server and the phone is the client.** `adb reverse` is the mirror image of
//! the `adb forward` that publishes wmd here — same tunnel, opposite direction —
//! and its argument order is `<device> <host>`, the opposite of `forward`'s.
//!
//! Security posture, stated rather than assumed:
//!
//! * The listener binds `127.0.0.1` only, **never `0.0.0.0`**. Nothing on the
//!   local network can reach it; only this machine and, through the reverse
//!   tunnel, the phone.
//! * Every connection must open with `HELLO <token> 1` carrying the 32 hex
//!   characters minted at session start. The token reaches the phone on the
//!   `FILES` broadcast, which is sent with `-p` (unlike `RUNNING` and
//!   `TRANSFER`) precisely because it carries a credential.
//! * The device-side port is a compile-time constant on both ends and is never
//!   sent, so a hostile app on the phone that forges a beacon cannot redirect
//!   us anywhere. What it *can* do is bind 7192 before we do — the same
//!   exposure wmd already accepts on 7191.
//! * There is no chroot. Serving the whole filesystem is the feature; the
//!   host's own permissions are the ACL. What is refused is a path that is not
//!   absolute or that contains `..`, because both make the target depend on
//!   this process's working directory or on symlink resolution, which is how
//!   "browse the computer" turns into "browse whatever the caller can
//!   construct".
//!
//! Nothing here is exposed to the webview: no `#[tauri::command]`, nothing in
//! `generate_handler!`. The React window drives none of this, and that is the
//! point — the UI for it is on the phone.

use std::collections::{HashMap, VecDeque};
use std::fs;
use std::io::{BufRead, BufReader, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::SeqCst};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, UNIX_EPOCH};

use serde_json::{json, Value};
use tauri::{AppHandle, Manager};

/// Device-side port the phone dials. Beside `wm::DEVICE_PORT` (7191) and fixed
/// for the same reason: a port that travels on the wire is a port an attacker
/// can move.
pub const DEVICE_PORT: u16 = 7192;

/// Host-side ports to try, in order. The first is the same number as the
/// device port so a hand-driven `nc` session needs no bookkeeping; the rest
/// exist because two desktops can be connected at once and each gets its own
/// listener.
const HOST_PORT_FIRST: u16 = 7192;
const HOST_PORT_LAST: u16 = 7199;

/// Entries in one `LIST` reply. Beyond this the window says so, because there
/// is no view recycling anywhere in the launcher shell — every row is an
/// inflated view on the main thread.
const LIST_CAP: usize = 5_000;

/// How much of a directory is read before the sort that produces those 5000.
///
/// The cap has to be applied *after* sorting or the 5000 shown would be
/// whatever order the filesystem handed back, which changes between refreshes.
/// Sorting needs the whole listing in memory, so this second, much larger
/// ceiling is what stops one pathological directory from being the thing that
/// ends the process.
const READ_CEILING: usize = 200_000;

/// Caps on one `PLAN` walk. A folder copy is the least-precedented operation
/// in this feature and these are deliberately generous but finite.
const PLAN_FILES_CAP: usize = 20_000;
const PLAN_DIRS_CAP: usize = 2_000;

/// Copy buffer. The same 64 KiB `WebFiles.pump` uses on the phone, so neither
/// end is the one that chose the chunk size.
const BUF: usize = 64 * 1024;

/// Longest request line accepted. A base64url path is 4/3 of its bytes, so
/// this is room for a pair of paths far past any filesystem's own limit — and
/// a ceiling on what a local process can make this thread allocate.
const MAX_LINE: usize = 16 * 1024;

/// A declared `PUT` size past this is not a file, it is a mistake or an
/// attack. Nothing a phone holds comes near it.
const MAX_PUT: u64 = 512 * 1024 * 1024 * 1024;

/// Refuse a `PUT` that would leave the destination volume with less than this.
/// Filling the boot disk is a worse outcome than a failed copy.
const SPACE_MARGIN: u64 = 16 * 1024 * 1024;

/// How long a `PUT`'s free-space answer is reused. See [`disk_space_cached`].
const SPACE_TTL: Duration = Duration::from_millis(500);

/// How often the accept loop wakes to ask whether it still has a reason to
/// exist. Short enough that a closed session frees the port before the user
/// could start another one, long enough to be free.
const ACCEPT_POLL: Duration = Duration::from_millis(200);

/// How often it wakes instead while connections are arriving.
///
/// A copy opens one data socket per file, back to back, and each one sits in
/// the backlog until the loop next wakes — so at [`ACCEPT_POLL`] every file
/// paid up to 200 ms before its first byte. Measured: 208 ms per file for a
/// folder of small files, against 3 ms with this poll, which is the connect
/// and HELLO themselves. The loop drops back to the slow poll [`BUSY_FOR`]
/// after the last accept, so an idle session costs what it always did.
const ACCEPT_POLL_BUSY: Duration = Duration::from_millis(2);
const BUSY_FOR: Duration = Duration::from_secs(2);

/// Socket read/write timeout. **A liveness poll, not a deadline** — a timeout
/// is caught and the operation resumed, so a slow phone never loses a byte to
/// it. Its only job is to give a thread blocked on a peer that is never coming
/// back a chance to notice the session ended.
const IO_POLL: Duration = Duration::from_secs(5);

/// Simultaneous connections. The phone uses one control socket plus one data
/// socket per file in flight; anything past this is a local process that is
/// not the launcher, and it gets the door rather than a thread.
const MAX_CONNS: usize = 8;

/// How long a fresh connection has to produce its `HELLO`, and the read
/// timeout it is given until it does.
///
/// This one IS a deadline, unlike [`IO_POLL`]. Without it a socket that
/// connected and then said nothing sat inside `read_line` for the rest of the
/// session — every timeout was caught and the read resumed — holding one of
/// the only [`MAX_CONNS`] slots. Eight silent sockets from any local process,
/// with no token and no handshake, and the launcher could not get a connection
/// at all. It does not close the exposure the module doc already accepts (a
/// hostile app can bind 7192 before we do, the same as wmd on 7191); it
/// removes the free denial of service that was sitting beside it.
///
/// The budget ends when `HELLO` is accepted. After that a timed-out read goes
/// back to being the liveness poll it was: a control socket is legitimately
/// idle between the user's clicks, and deadlining that would close the pane
/// mid-browse. Generous for what it gates — the phone writes `HELLO` in the
/// same breath as the connect, and gives up on the answer after 4 s
/// (`HostFiles.READ_TIMEOUT_MS`).
const HELLO_DEADLINE: Duration = Duration::from_secs(5);

/// One session's listener, as everything outside this module sees it.
struct Server {
    /// The port `adb reverse` must point at. The phone never learns it.
    host_port: u16,
    /// 32 hex characters, minted per session, memory-only on both ends.
    token: String,
    /// Set when this session is over, by whichever of the accept loop,
    /// [`forget`] or [`shutdown_all`] notices first. Every thread this module
    /// owns holds a clone and checks it.
    done: Arc<AtomicBool>,
}

fn servers() -> &'static Mutex<HashMap<String, Server>> {
    static SERVERS: OnceLock<Mutex<HashMap<String, Server>>> = OnceLock::new();
    SERVERS.get_or_init(|| Mutex::new(HashMap::new()))
}

// ── Lifecycle ───────────────────────────────────────────────────────────

/// Start the file service for one mirror session.
///
/// Returns the host port to hand `adb reverse`, or `None` if a token could not
/// be minted or every port in the range was taken. `None` is not an error the
/// UI models: the beacon is then suppressed, the phone never learns of a host
/// to talk to, and the File transfer window shows "The computer is not
/// answering" — which is the honest thing to show, and the reason the window's
/// tile is gated on `!onPhone()` rather than on this succeeding.
pub fn start(app: AppHandle, key: String, stop: Arc<AtomicBool>) -> Option<u16> {
    // Remembered for `roots`, which runs on a connection thread with no
    // session key to look one up by. Set once and never replaced: every
    // session on this host resolves the same six user folders, and tauri hands
    // out the same handle to all of them anyway.
    let _ = APP.set(app.clone());

    let token = mint_token()?;
    let (listener, host_port) = bind()?;
    if let Err(e) = listener.set_nonblocking(true) {
        log::warn!("file service: the listener would not go non-blocking: {e}");
        return None;
    }

    let done = Arc::new(AtomicBool::new(false));
    servers().lock().unwrap().insert(
        key.clone(),
        Server {
            host_port,
            token: token.clone(),
            done: done.clone(),
        },
    );
    log::info!("file service listening on 127.0.0.1:{host_port} for {key}");

    let registered = key.clone();
    match thread::Builder::new()
        .name("files-accept".into())
        .spawn(move || accept_loop(app, key, token, listener, stop, done))
    {
        Ok(_) => Some(host_port),
        Err(e) => {
            // The listener was moved into a closure that will never run, so it
            // dies here — and the registration has to die with it. A beacon
            // announcing a token for a port nothing answers on is strictly
            // worse than no beacon: the window would connect, hang and time
            // out instead of saying the computer is not there.
            log::warn!("file service: could not start the accept thread: {e}");
            servers().lock().unwrap().remove(&registered);
            None
        }
    }
}

/// What the `FILES` broadcast should carry for this session: `(token, os)`.
///
/// `None` means there is nothing to announce — no listener, or the session is
/// gone — and the enforcer must then send no beacon at all. A beacon with a
/// stale token would produce a window that connects and is refused, which
/// looks like a broken feature rather than an absent one.
pub fn beacon(key: &str) -> Option<(String, &'static str)> {
    let map = servers().lock().unwrap();
    map.get(key).map(|s| (s.token.clone(), os_tag()))
}

/// The host port this session's listener bound, for re-issuing the reverse
/// after the device dropped off adb. The reverse and the `adb forward` that
/// carries wmd die in the same event, so they are re-armed together.
pub fn host_port(key: &str) -> Option<u16> {
    servers().lock().unwrap().get(key).map(|s| s.host_port)
}

/// The session is over: stop its listener and every connection on it.
pub fn forget(key: &str) {
    if let Some(server) = servers().lock().unwrap().remove(key) {
        server.done.store(true, SeqCst);
    }
}

/// Every session, at process exit.
///
/// A bound listener must not outlive the process — on Windows a socket left in
/// TIME_WAIT is the next run's failed bind — and the per-session stop flag
/// alone races the door: `RunEvent::Exit` can beat the enforcer's next 200 ms
/// poll. Best-effort by design; the flags are set and the process is then free
/// to go without waiting for threads that are about to be unmapped anyway.
pub fn shutdown_all() {
    let mut map = servers().lock().unwrap();
    for (key, server) in map.drain() {
        log::info!("file service for {key} stopping");
        server.done.store(true, SeqCst);
    }
}

/// 16 bytes of CSPRNG as 32 hex characters.
///
/// `wireless::fill_random` rather than a hash of the clock: this is the only
/// thing between another app on the phone and a read of every file on this
/// computer, and that function is already `#[cfg]`-twinned for Windows
/// (`BCryptGenRandom`) and everything else (`/dev/urandom`) with no dependency
/// to add.
fn mint_token() -> Option<String> {
    let mut raw = [0u8; 16];
    if !crate::wireless::fill_random(&mut raw) {
        log::warn!("file service: the system random number generator is unavailable");
        return None;
    }
    let mut out = String::with_capacity(32);
    for b in raw {
        out.push_str(&format!("{b:02x}"));
    }
    Some(out)
}

/// The first free port in the range, bound to loopback.
///
/// `Ipv4Addr::LOCALHOST`, never `0.0.0.0`: the phone arrives through the
/// reverse tunnel as a connection from this machine, so binding wider would
/// buy nothing and publish the user's filesystem to the coffee shop.
fn bind() -> Option<(TcpListener, u16)> {
    for port in HOST_PORT_FIRST..=HOST_PORT_LAST {
        match TcpListener::bind((Ipv4Addr::LOCALHOST, port)) {
            Ok(listener) => return Some((listener, port)),
            Err(e) if e.kind() == ErrorKind::AddrInUse => continue,
            Err(e) => {
                log::warn!("file service: could not bind 127.0.0.1:{port}: {e}");
                continue;
            }
        }
    }
    log::warn!(
        "file service: ports {HOST_PORT_FIRST}-{HOST_PORT_LAST} are all taken — \
         the File transfer window will show the computer as unreachable"
    );
    None
}

/// Accept until the session ends, then drop the listener.
///
/// Three ways to end, and all three must be watched: the session's own `stop`
/// flag, this module's `done` flag ([`forget`] / [`shutdown_all`]), and the
/// mirror session having disappeared from `MirrorState`. The last is the leak
/// rule every long-lived thread in `scrcpy.rs` obeys — a session that ended
/// without anyone setting a flag still has to end its threads, or a day of
/// connecting and disconnecting leaves a pile of them holding ports.
fn accept_loop(
    app: AppHandle,
    key: String,
    token: String,
    listener: TcpListener,
    stop: Arc<AtomicBool>,
    done: Arc<AtomicBool>,
) {
    let live = Arc::new(AtomicUsize::new(0));
    let mut last_accept: Option<Instant> = None;
    loop {
        if stop.load(SeqCst)
            || done.load(SeqCst)
            || crate::scrcpy::session_display(&app, &key).is_none()
        {
            break;
        }
        match listener.accept() {
            Ok((stream, _)) => {
                if live.load(SeqCst) >= MAX_CONNS {
                    // Not an error worth a reply: the launcher never gets
                    // here, so whoever did is not the launcher.
                    log::warn!("file service: refused a connection past {MAX_CONNS} open");
                    continue;
                }
                last_accept = Some(Instant::now());
                live.fetch_add(1, SeqCst);
                let slot = Slot(live.clone());
                let (token, done) = (token.clone(), done.clone());
                let spawned = thread::Builder::new()
                    .name("files-conn".into())
                    .spawn(move || {
                        let _slot = slot;
                        serve(stream, &token, &done);
                    });
                if spawned.is_err() {
                    log::warn!("file service: could not start a connection thread");
                }
            }
            Err(e) if e.kind() == ErrorKind::WouldBlock => {
                let busy = last_accept.is_some_and(|at| at.elapsed() < BUSY_FOR);
                thread::sleep(if busy { ACCEPT_POLL_BUSY } else { ACCEPT_POLL });
            }
            Err(e) => {
                log::warn!("file service: accept failed: {e}");
                thread::sleep(ACCEPT_POLL);
            }
        }
    }

    // Whoever gets here owns the shutdown: tell the connection threads, then
    // let the listener drop so the port is free before the next session tries
    // to bind it.
    done.store(true, SeqCst);
    let mut map = servers().lock().unwrap();
    // Only if the entry is still ours. A session that ended and restarted
    // under the same key has already replaced it, and removing that one would
    // silence a listener that is very much alive.
    if map
        .get(&key)
        .map(|s| Arc::ptr_eq(&s.done, &done))
        .unwrap_or(false)
    {
        map.remove(&key);
    }
    drop(map);
    log::info!("file service for {key} closed");
}

/// Decrements the live-connection count however the thread ends, panic
/// included. A counter that only counts down on the happy path is a counter
/// that eventually reads `MAX_CONNS` forever.
struct Slot(Arc<AtomicUsize>);

impl Drop for Slot {
    fn drop(&mut self) {
        self.0.fetch_sub(1, SeqCst);
    }
}

// ── The protocol ────────────────────────────────────────────────────────

/// One connection, control or data.
fn serve(stream: TcpStream, token: &str, done: &AtomicBool) {
    if let Err(e) = converse(stream, token, done) {
        // A phone that closed its socket and a session that ended are the two
        // normal ways this returns, and neither is worth a line at warn.
        log::debug!("file service: connection ended: {e}");
    }
}

fn converse(stream: TcpStream, token: &str, done: &AtomicBool) -> std::io::Result<()> {
    converse_within(stream, token, done, HELLO_DEADLINE)
}

/// The body of [`converse`], with the handshake budget as an argument so the
/// test for it does not have to sit out the real one.
fn converse_within(
    stream: TcpStream,
    token: &str,
    done: &AtomicBool,
    hello_budget: Duration,
) -> std::io::Result<()> {
    quiet_media_errors();
    // A socket accepted from a non-blocking listener inherits O_NONBLOCK on
    // the BSDs (macOS included) and does not on Linux. Rust does not normalise
    // it, so without this line every read here would spin on WouldBlock on one
    // host and block on the other.
    stream.set_nonblocking(false)?;
    stream.set_nodelay(true)?;
    // The handshake budget, not the poll, as the read timeout until HELLO is
    // in: the deadline can only be checked between reads, so a timeout longer
    // than the budget would keep a silent socket past it. The two are the same
    // five seconds in production, and this is what stops them drifting apart.
    stream.set_read_timeout(Some(hello_budget))?;
    stream.set_write_timeout(Some(IO_POLL))?;

    let mut out = stream.try_clone()?;
    let mut reader = BufReader::with_capacity(BUF, stream);

    // Handshake first, on EVERY connection. A request before it is not
    // answered at all — the reply would be the interesting half of an
    // unauthenticated read.
    let Some(line) = read_line(&mut reader, done, Some(Instant::now() + hello_budget))? else {
        return Ok(());
    };
    let (verb, args) = split_request(&line);
    if verb != "HELLO" || args.len() < 2 || !same_secret(args[0], token) || args[1] != "1" {
        say(&mut out, "ERR auth", done)?;
        log::warn!("file service: refused a connection whose handshake did not check out");
        return Ok(());
    }
    say(
        &mut out,
        &format!("OK {} {} {}", b64(&host_label()), os_tag(), sep_hex()),
        done,
    )?;
    // Greeted, so the deadline is spent and a timeout goes back to meaning
    // "still nothing, ask again" — this socket may now sit idle for as long as
    // the user leaves the window open without clicking anything.
    reader.get_ref().set_read_timeout(Some(IO_POLL))?;

    loop {
        let Some(line) = read_line(&mut reader, done, None)? else {
            return Ok(());
        };
        if line.is_empty() {
            continue;
        }
        let (verb, args) = split_request(&line);
        match verb.as_str() {
            "BYE" => return Ok(()),
            "ROOTS" => reply(&mut out, done, roots().map(|v| encoded(&v)))?,
            "LIST" => reply(
                &mut out,
                done,
                arg(&args, 0)
                    .and_then(checked)
                    .and_then(|p| list(&p))
                    .map(|v| encoded(&v)),
            )?,
            "PLAN" => reply(
                &mut out,
                done,
                arg(&args, 0)
                    .and_then(checked)
                    .and_then(|p| walk(&p))
                    .map(|v| encoded(&v)),
            )?,
            "MKDIR" => reply(
                &mut out,
                done,
                arg(&args, 0).and_then(checked).and_then(|p| mkdir(&p)),
            )?,
            "FREE" => reply(
                &mut out,
                done,
                arg(&args, 0)
                    .and_then(checked)
                    .and_then(|p| free(&p))
                    .map(|(f, t)| format!("{f} {t}")),
            )?,
            "UNIQUE" => reply(
                &mut out,
                done,
                arg(&args, 0)
                    .and_then(checked)
                    .and_then(|dir| {
                        let name = decoded(arg(&args, 1)?)?;
                        Ok(unique_name(&dir, &name))
                    })
                    .map(|name| b64(&name)),
            )?,
            // The data verbs answer with a body, so the connection is spent
            // either way: the phone opens a fresh socket per file precisely so
            // that a copy in flight never blocks the pane it is browsing.
            "GET" => {
                get(&mut out, done, &args)?;
                return Ok(());
            }
            "PUT" => {
                put(&mut reader, &mut out, done, &args)?;
                return Ok(());
            }
            _ => say(&mut out, "ERR unknown", done)?,
        }
    }
}

/// `VERB arg arg` → `("VERB", ["arg", "arg"])`.
///
/// Splitting on runs of whitespace is only safe because every argument that
/// carries user data is base64url, whose alphabet has no space in it. That is
/// the whole reason for the encoding: names contain spaces, quotes, `$`,
/// newlines and emoji, and none of them may reach this split.
fn split_request(line: &str) -> (String, Vec<&str>) {
    let mut fields = line.split_whitespace();
    let verb = fields.next().unwrap_or("").to_ascii_uppercase();
    (verb, fields.collect())
}

/// Compare a presented token against ours without leaking where they diverge.
///
/// Over loopback this is close to paranoia, but the alternative is a `==` that
/// returns on the first differing byte, and the caller may be another app on
/// the phone with all night to spend.
fn same_secret(presented: &str, token: &str) -> bool {
    let (a, b) = (presented.as_bytes(), token.as_bytes());
    // Accumulated as a `usize`, not a `u8`: the length term is the only part
    // of this that can exceed a byte, and truncating it would make two lengths
    // differing by exactly 256 compare equal on the strength of the byte loop
    // alone.
    let mut diff = a.len() ^ b.len();
    for i in 0..a.len().max(b.len()) {
        diff |= (a.get(i).copied().unwrap_or(0) ^ b.get(i).copied().unwrap_or(0)) as usize;
    }
    diff == 0
}

/// Never raise a dialog box on this thread.
///
/// `volumes()` uses `GetLogicalDrives` rather than probing `A:\`..`Z:\`
/// precisely so that enumerating an empty card reader touches no media — but
/// the letter is then IN the pane, and the moment the user clicks it `list`
/// calls `fs::metadata`, which opens the path with `CreateFileW`, which is the
/// same "There is no disk in the drive" box by a longer road. `free` and `get`
/// reach it too. The box is raised on the *host's* desktop by a background
/// thread, where the user — who is looking at a phone — cannot see it, and the
/// DeX window is usually fullscreen over it; meanwhile this thread is blocked
/// inside the call until somebody dismisses it.
///
/// `SetThreadErrorMode` rather than `SetErrorMode`: the process-wide one would
/// silently change how every other part of the app fails, and this is exactly
/// the case the thread-scoped variant was added for. The call turns the box
/// into the error return `Fault::io` already knows how to answer with.
#[cfg(windows)]
fn quiet_media_errors() {
    const SEM_FAILCRITICALERRORS: u32 = 0x0001;
    unsafe { SetThreadErrorMode(SEM_FAILCRITICALERRORS, std::ptr::null_mut()) };
}

#[cfg(not(windows))]
fn quiet_media_errors() {}

/// `win` | `mac` | `linux` — the vocabulary the launcher's `HostLink` maps to
/// "This PC" / "This Mac" / "This computer".
fn os_tag() -> &'static str {
    match std::env::consts::OS {
        "windows" => "win",
        "macos" => "mac",
        _ => "linux",
    }
}

/// The host's path separator, as a hex byte, so the phone can join a path the
/// way this filesystem spells one without guessing from the OS name.
fn sep_hex() -> &'static str {
    if cfg!(windows) {
        "5c"
    } else {
        "2f"
    }
}

/// A human name for this computer, for the window's left-hand heading.
///
/// Cached because the fallback forks, and it cannot change while the process
/// lives. It is never empty: an empty base64 field would leave the `HELLO`
/// reply one token short and the phone parsing the OS out of the separator
/// slot, so the OS tag itself is the floor.
fn host_label() -> String {
    static LABEL: OnceLock<String> = OnceLock::new();
    LABEL
        .get_or_init(|| {
            // Windows always exports COMPUTERNAME; a login shell usually sets
            // HOSTNAME but rarely exports it, hence the two fallbacks.
            for var in ["COMPUTERNAME", "HOSTNAME"] {
                if let Ok(v) = std::env::var(var) {
                    let v = v.trim().to_string();
                    if !v.is_empty() {
                        return v;
                    }
                }
            }
            if let Ok(v) = fs::read_to_string("/etc/hostname") {
                let v = v.trim().to_string();
                if !v.is_empty() {
                    return v;
                }
            }
            // macOS keeps it in the dynamic store rather than a file, and this
            // is the one call in the module that forks — once per process.
            let mut cmd = std::process::Command::new("hostname");
            cmd.stdin(std::process::Stdio::null());
            crate::adb::hide_console(&mut cmd);
            if let Ok(out) = cmd.output() {
                let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
                if !v.is_empty() {
                    return v;
                }
            }
            os_tag().to_string()
        })
        .clone()
}

// ── Answers ─────────────────────────────────────────────────────────────

/// A refusal, in the protocol's own vocabulary.
#[derive(Debug)]
struct Fault {
    /// One of `auth`, `proto`, `notfound`, `denied`, `notdir`, `isdir`,
    /// `exists`, `io`, `space`, `toobig`, `unknown`. The phone matches on
    /// these, so they are a closed set and not a place for prose.
    code: &'static str,
    /// Free text for the log on the other end. Base64 on the wire like every
    /// other user-shaped string.
    detail: String,
}

type Answer<T> = Result<T, Fault>;

impl Fault {
    fn new(code: &'static str, detail: impl Into<String>) -> Self {
        Fault {
            code,
            detail: detail.into(),
        }
    }

    fn proto(detail: impl Into<String>) -> Self {
        Fault::new("proto", detail)
    }

    /// An `io::Error` in the protocol's terms.
    ///
    /// `IsADirectory` / `NotADirectory` are deliberately absent: both are
    /// still unstable in std, so every caller that cares checks the metadata
    /// itself rather than reading the kind.
    fn io(what: &str, e: &std::io::Error) -> Self {
        let code = match e.kind() {
            ErrorKind::NotFound => "notfound",
            ErrorKind::PermissionDenied => "denied",
            ErrorKind::AlreadyExists => "exists",
            _ => "io",
        };
        Fault::new(code, format!("{what}: {e}"))
    }

    fn line(&self) -> String {
        if self.detail.is_empty() {
            format!("ERR {}", self.code)
        } else {
            format!("ERR {} {}", self.code, b64(&self.detail))
        }
    }
}

/// One `Answer` on the wire: `OK`, `OK <payload>` or `ERR …`.
fn reply<T: AsRef<str>>(
    out: &mut TcpStream,
    done: &AtomicBool,
    answer: Answer<T>,
) -> std::io::Result<()> {
    match answer {
        Ok(payload) if payload.as_ref().is_empty() => say(out, "OK", done),
        Ok(payload) => say(out, &format!("OK {}", payload.as_ref()), done),
        Err(fault) => say(out, &fault.line(), done),
    }
}

fn arg<'a>(args: &[&'a str], index: usize) -> Answer<&'a str> {
    args.get(index)
        .copied()
        .ok_or_else(|| Fault::proto(format!("missing argument {}", index + 1)))
}

fn encoded(value: &Value) -> String {
    b64(&value.to_string())
}

fn decoded(encoded: &str) -> Answer<String> {
    unb64(encoded).ok_or_else(|| Fault::proto("an argument was not base64url"))
}

/// A path off the wire, decoded and vetted.
///
/// See the module doc for why there is no root to be inside of. The two things
/// refused here are the two that would make the effective target something the
/// caller did not spell out: a relative path (resolved against whatever
/// directory this process happens to be in) and any `..` component.
fn checked(encoded: &str) -> Answer<PathBuf> {
    let raw = decoded(encoded)?;
    if raw.is_empty() {
        return Err(Fault::proto("an empty path"));
    }
    if raw.contains('\0') {
        return Err(Fault::proto("a NUL in a path"));
    }
    let path = PathBuf::from(&raw);
    if !path.is_absolute() {
        return Err(Fault::new("denied", format!("not an absolute path: {raw}")));
    }
    if path.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err(Fault::new("denied", format!("a path with ..: {raw}")));
    }
    Ok(path)
}

// ── ROOTS ───────────────────────────────────────────────────────────────

/// A place the left-hand pane offers as a starting point.
struct Root {
    label: String,
    path: PathBuf,
    /// `drive | volume | home | desktop | documents | downloads | pictures |
    /// videos` — so the phone can pick an icon without parsing the label.
    kind: &'static str,
}

/// Everything the pane starts from: this host's volumes, then the user's own
/// folders.
///
/// `app.path()` rather than the `dirs` crate: tauri already resolves all six
/// of these per platform (`src/path/desktop.rs`), it is already a dependency,
/// and AGENTS.md's cheapest rule to keep is the one about not adding one.
fn roots() -> Answer<Value> {
    let mut all = volumes();
    let Some(app) = app_handle() else {
        // Only reachable if `start` was never called, which cannot happen on
        // a live connection — but a `None` here would otherwise be an unwrap.
        return Ok(Value::Array(rows_to_json(&all)));
    };
    let path = app.path();
    for (kind, resolved) in [
        ("home", path.home_dir()),
        ("desktop", path.desktop_dir()),
        ("downloads", path.download_dir()),
        ("documents", path.document_dir()),
        ("pictures", path.picture_dir()),
        ("videos", path.video_dir()),
    ] {
        let Ok(dir) = resolved else { continue };
        if !dir.is_dir() || all.iter().any(|r| r.path == dir) {
            continue;
        }
        // The home directory's own file name is the login name, which is not
        // what anyone calls that folder. Everything else is already named the
        // way the user's file manager names it.
        let label = if kind == "home" {
            "Home".to_string()
        } else {
            dir.file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_else(|| dir.to_string_lossy().to_string())
        };
        all.push(Root {
            label,
            path: dir,
            kind,
        });
    }
    Ok(Value::Array(rows_to_json(&all)))
}

fn rows_to_json(all: &[Root]) -> Vec<Value> {
    all.iter()
        .filter_map(|r| {
            // A root whose path is not valid UTF-8 could not survive the round
            // trip through a Java String, so it is dropped rather than offered
            // as something that will fail the moment it is clicked.
            let path = r.path.to_str()?;
            Some(json!({ "label": r.label, "path": path, "kind": r.kind }))
        })
        .collect()
}

/// The drives or mounted volumes of this host.
///
/// Three `#[cfg]` bodies of one small function — the in-file idiom
/// `wireless::fill_random` uses — rather than a `mod backend` triplet, which
/// is for a backend with many entry points. Only the volume list differs
/// between hosts; the user-folder tail above is shared, and triplicating it to
/// satisfy a literal reading of "three bodies of `roots`" would be three
/// places to forget the same edit.
#[cfg(windows)]
fn volumes() -> Vec<Root> {
    let mask = unsafe { GetLogicalDrives() };
    let mut out = Vec::new();
    for i in 0..26u32 {
        if mask & (1 << i) == 0 {
            continue;
        }
        let letter = (b'A' + i as u8) as char;
        out.push(Root {
            label: format!("{letter}:"),
            path: PathBuf::from(format!("{letter}:\\")),
            kind: "drive",
        });
    }
    if out.is_empty() {
        // The bitmask failed, which it does not, but a pane with no roots at
        // all is unusable and C: is the one letter worth guessing.
        let c = PathBuf::from("C:\\");
        if c.is_dir() {
            out.push(Root {
                label: "C:".into(),
                path: c,
                kind: "drive",
            });
        }
    }
    out
}

#[cfg(target_os = "macos")]
fn volumes() -> Vec<Root> {
    let mut out = vec![Root {
        label: "/".into(),
        path: PathBuf::from("/"),
        kind: "volume",
    }];
    if let Ok(entries) = fs::read_dir("/Volumes") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with('.') {
                continue;
            }
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            // On the releases before the read-only system volume, /Volumes
            // held a symlink straight back to `/`. Following it would put the
            // boot disk in the list twice under two different names.
            if fs::canonicalize(&path)
                .map(|c| c == Path::new("/"))
                .unwrap_or(false)
            {
                continue;
            }
            out.push(Root {
                label: name,
                path,
                kind: "volume",
            });
        }
    }
    out
}

#[cfg(not(any(windows, target_os = "macos")))]
fn volumes() -> Vec<Root> {
    let mut out = vec![Root {
        label: "/".into(),
        path: PathBuf::from("/"),
        kind: "volume",
    }];
    // The four places the desktop environments of the last decade have put a
    // mounted stick, most specific first. Linux is a release target
    // (`build-linux` in desktop.yml), but this has not yet run on a real
    // Linux desktop.
    let mut parents = Vec::new();
    if let Some(user) = std::env::var("USER")
        .or_else(|_| std::env::var("LOGNAME"))
        .ok()
        .filter(|u| !u.is_empty())
    {
        parents.push(PathBuf::from(format!("/media/{user}")));
        parents.push(PathBuf::from(format!("/run/media/{user}")));
    }
    parents.push(PathBuf::from("/media"));
    parents.push(PathBuf::from("/mnt"));

    for parent in parents {
        let Ok(entries) = fs::read_dir(&parent) else {
            continue;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with('.') {
                continue;
            }
            let path = entry.path();
            if !path.is_dir() || out.iter().any(|r| r.path == path) {
                continue;
            }
            out.push(Root {
                label: name,
                path,
                kind: "volume",
            });
        }
    }
    out
}

/// The `AppHandle` the running session started us with.
///
/// Held apart from `SERVERS` because `roots` needs it and a connection thread
/// has no session key to look one up by: every session on this host resolves
/// the same six user folders, so one handle answers for all of them.
fn app_handle() -> Option<AppHandle> {
    APP.get().cloned()
}

static APP: OnceLock<AppHandle> = OnceLock::new();

// ── LIST ────────────────────────────────────────────────────────────────

/// One row of a listing, before it is sorted and encoded.
struct Row {
    name: String,
    path: String,
    dir: bool,
    size: u64,
    /// Milliseconds since the epoch, **not** seconds: the phone's own pane
    /// gets this field from `File.lastModified()`, both panes are drawn by the
    /// same row builder, and a seconds value would render every host file as
    /// 1970 beside phone files dated correctly.
    modified: u64,
}

fn list(dir: &Path) -> Answer<Value> {
    let meta = fs::metadata(dir).map_err(|e| Fault::io("could not stat", &e))?;
    if !meta.is_dir() {
        return Err(Fault::new("notdir", dir.to_string_lossy().to_string()));
    }
    let entries = fs::read_dir(dir).map_err(|e| Fault::io("could not read", &e))?;

    let mut rows = Vec::new();
    let mut truncated = false;
    for entry in entries.flatten() {
        if rows.len() >= READ_CEILING {
            truncated = true;
            break;
        }
        // A name that is not valid UTF-8 cannot round-trip through a Java
        // String, so the copy it was offered for would fail at the moment it
        // was clicked. Omitted rather than offered.
        let Some(name) = entry.file_name().to_str().map(str::to_string) else {
            continue;
        };
        let Some(path) = entry.path().to_str().map(str::to_string) else {
            continue;
        };
        // `.part` is what a copy in flight is writing under — `put` below
        // names its temporary that way, and so does every browser's unfinished
        // download. Skipped for the reason `WebFiles.list` skips it on the
        // phone (WebFiles.java:169-175): the two panes sit side by side, a
        // pane refreshed mid-copy would otherwise list the half-written file
        // beside the finished ones, and the worse half of that is being able
        // to select it and copy a file that is still being written.
        if name.starts_with('.') || name.ends_with(".part") {
            continue;
        }
        // The link's own attributes, not the target's — a hidden symlink to a
        // visible folder is still hidden.
        let Ok(base) = entry.metadata() else { continue };
        if hidden(&base) {
            continue;
        }
        // …but the target's size and type, so a symlinked folder is a folder
        // the user can walk into. A broken link keeps the link's own answer.
        let meta = if base.file_type().is_symlink() {
            fs::metadata(entry.path()).unwrap_or(base)
        } else {
            base
        };
        let is_dir = meta.is_dir();
        rows.push(Row {
            name,
            path,
            dir: is_dir,
            size: if is_dir { 0 } else { meta.len() },
            modified: millis(&meta),
        });
    }

    sort_rows(&mut rows);
    if rows.len() > LIST_CAP {
        rows.truncate(LIST_CAP);
        truncated = true;
    }

    let entries: Vec<Value> = rows
        .iter()
        .map(|r| {
            json!({
                "name": r.name,
                // The FULL host path, on every row. The phone never joins a
                // path with a host separator, so a Windows host and a Linux
                // host need no branch on the other end at all.
                "path": r.path,
                "dir": r.dir,
                "size": r.size,
                "modified": r.modified,
            })
        })
        .collect();

    let parent = match dir.parent().and_then(|p| p.to_str()) {
        Some(p) => Value::String(p.to_string()),
        None => Value::Null,
    };
    Ok(json!({
        "path": dir.to_string_lossy(),
        "parent": parent,
        "truncated": truncated,
        "entries": entries,
    }))
}

/// Folders first, then names case-insensitively — the order a file manager
/// uses, because this is one. Deliberately the same rule `WebFiles.list()`
/// applies on the phone: the two panes sit side by side and disagreeing about
/// where a file belongs in a list would read as a bug in whichever pane the
/// user looked at second.
fn sort_rows(rows: &mut [Row]) {
    rows.sort_by(|a, b| {
        b.dir
            .cmp(&a.dir)
            .then_with(|| cmp_ignore_case(&a.name, &b.name))
    });
}

/// `String.compareToIgnoreCase` in Rust.
///
/// Not `to_lowercase().cmp()`: Java folds one character at a time, upper then
/// lower, while Rust's `to_lowercase` does full Unicode case folding and can
/// change a string's length (`İ` becomes two characters). For the phone pane
/// and this one to agree on an order, the rule has to be Java's. The remaining
/// difference is that Java compares UTF-16 code units and this compares chars,
/// which can only diverge above the BMP — where neither end has a defined
/// order anyone would notice.
fn cmp_ignore_case(a: &str, b: &str) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    let (mut ai, mut bi) = (a.chars(), b.chars());
    loop {
        match (ai.next(), bi.next()) {
            (None, None) => return Ordering::Equal,
            (None, Some(_)) => return Ordering::Less,
            (Some(_), None) => return Ordering::Greater,
            (Some(x), Some(y)) => {
                if x == y {
                    continue;
                }
                let (xu, yu) = (fold_up(x), fold_up(y));
                if xu == yu {
                    continue;
                }
                let (xl, yl) = (fold_down(xu), fold_down(yu));
                if xl != yl {
                    return xl.cmp(&yl);
                }
            }
        }
    }
}

/// Single-character case mapping. `next()` on the iterator rather than
/// collecting: Java's `Character.toUpperCase` is also one char in, one char
/// out, and a multi-character expansion (`ß` → `SS`) is exactly where the two
/// would part company.
fn fold_up(c: char) -> char {
    let mut it = c.to_uppercase();
    match (it.next(), it.next()) {
        (Some(u), None) => u,
        _ => c,
    }
}

fn fold_down(c: char) -> char {
    let mut it = c.to_lowercase();
    match (it.next(), it.next()) {
        (Some(l), None) => l,
        _ => c,
    }
}

/// Windows marks a file hidden with an attribute rather than a leading dot,
/// and `std::os::windows::fs::MetadataExt` hands it over without a Win32 call
/// or a new crate feature.
#[cfg(windows)]
fn hidden(meta: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_HIDDEN: u32 = 0x0000_0002;
    meta.file_attributes() & FILE_ATTRIBUTE_HIDDEN != 0
}

#[cfg(not(windows))]
fn hidden(_meta: &fs::Metadata) -> bool {
    false
}

fn millis(meta: &fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

// ── PLAN ────────────────────────────────────────────────────────────────

/// A whole folder, flattened, for a PC→phone copy.
///
/// The *sending* side always enumerates, so tree recursion exists in exactly
/// one place per direction: here for host→phone, and `File.listFiles()` on the
/// phone for phone→host. The phone creates every `dirs` entry, then opens one
/// data socket per `files` entry.
///
/// Dotfiles, `.part` files and Windows-hidden entries are skipped, the same as
/// `list` above.
/// The argument the other way is real — a folder copy that silently drops
/// files is a poor copy — but the user cannot see inside the folder they
/// picked, so what they believe they are copying is exactly what the pane
/// would have shown them, and `.git` / `.cache` / `.DS_Store` are the whole of
/// what is actually being dropped.
///
/// Symlinks are not followed and are not copied. Following them turns a folder
/// copy into an unbounded walk of the host, and a phone has nowhere to put a
/// link anyway; they are counted in `skipped` so the window can say how many.
/// Anything that is neither a regular file nor a directory — a device node, a
/// socket, a fifo — is counted there too, for the same reason.
fn walk(root: &Path) -> Answer<Value> {
    let meta = fs::metadata(root).map_err(|e| Fault::io("could not stat", &e))?;
    if !meta.is_dir() {
        return Err(Fault::new("notdir", root.to_string_lossy().to_string()));
    }

    let mut dirs: Vec<String> = Vec::new();
    let mut files: Vec<Value> = Vec::new();
    let mut skipped = 0u64;
    let mut truncated = false;

    let mut queue: VecDeque<(PathBuf, String)> = VecDeque::new();
    queue.push_back((root.to_path_buf(), String::new()));
    while let Some((dir, rel)) = queue.pop_front() {
        // A subfolder this user cannot read is not a reason to fail the whole
        // plan: the copy proceeds without it and the window reports what
        // landed. The alternative is one unreadable folder deep in a tree
        // cancelling a copy the user already committed to.
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let Some(name) = entry.file_name().to_str().map(str::to_string) else {
                continue;
            };
            // The same three skips `list` applies, so a folder copy carries
            // exactly what the pane would have shown inside it — including
            // `.part`, which is a file somebody is still writing.
            if name.starts_with('.') || name.ends_with(".part") {
                continue;
            }
            let Ok(meta) = entry.metadata() else { continue };
            if hidden(&meta) {
                continue;
            }
            if meta.file_type().is_symlink() {
                skipped += 1;
                continue;
            }
            // `dirs` entries are ALWAYS '/'-separated, whatever this host
            // spells a path with: the phone appends them to an Android path.
            let child = if rel.is_empty() {
                name.clone()
            } else {
                format!("{rel}/{name}")
            };
            if meta.is_dir() {
                if dirs.len() >= PLAN_DIRS_CAP {
                    truncated = true;
                    continue;
                }
                dirs.push(child.clone());
                queue.push_back((entry.path(), child));
            } else if meta.is_file() {
                if files.len() >= PLAN_FILES_CAP {
                    truncated = true;
                    continue;
                }
                let Some(path) = entry.path().to_str().map(str::to_string) else {
                    continue;
                };
                files.push(json!({ "rel": child, "path": path, "size": meta.len() }));
            } else {
                skipped += 1;
            }
        }
    }

    Ok(json!({
        "root": root.to_string_lossy(),
        "dirs": dirs,
        "files": files,
        "skipped": skipped,
        "truncated": truncated,
    }))
}

// ── MKDIR / FREE / UNIQUE ───────────────────────────────────────────────

/// `mkdir -p` semantics: an existing directory is success, not `exists`.
///
/// A phone→host folder copy sends one `MKDIR` per directory in the tree and
/// makes no attempt to remember which ones it already asked for, because a
/// second window or a retry could have made them in between.
fn mkdir(path: &Path) -> Answer<String> {
    if let Ok(meta) = fs::symlink_metadata(path) {
        if meta.is_dir() {
            return Ok(String::new());
        }
        return Err(Fault::new(
            "exists",
            format!("not a directory: {}", path.to_string_lossy()),
        ));
    }
    fs::create_dir_all(path)
        .map(|_| String::new())
        .map_err(|e| Fault::io("could not create", &e))
}

/// `{free, total}` for the volume holding `path`, in bytes.
fn free(path: &Path) -> Answer<(u64, u64)> {
    let dir = nearest_dir(path)
        .ok_or_else(|| Fault::new("notfound", path.to_string_lossy().to_string()))?;
    disk_space(&dir).ok_or_else(|| {
        Fault::new(
            "io",
            format!("no free-space answer for {}", dir.to_string_lossy()),
        )
    })
}

/// The closest existing directory at or above `path`.
///
/// `FREE` is asked about the folder a pane is showing, but also about a
/// destination file that does not exist yet — the same volume either way.
fn nearest_dir(path: &Path) -> Option<PathBuf> {
    let mut cursor = path;
    loop {
        if let Ok(meta) = fs::metadata(cursor) {
            return if meta.is_dir() {
                Some(cursor.to_path_buf())
            } else {
                cursor.parent().map(Path::to_path_buf)
            };
        }
        cursor = cursor.parent()?;
    }
}

/// Free space, per host.
///
/// std has no answer to this at all, and the plan's "no new dependency" rule
/// is the one worth keeping, so each host gets the smallest thing that is
/// actually correct:
///
/// * Windows: `GetDiskFreeSpaceExW`, declared here. `windows-sys` would need
///   `Win32_Storage_FileSystem` added to its feature list and the heavier
///   `windows` crate is reserved for dexcast, so this follows the precedent
///   already set for the Accessibility API in `embed/macos.rs` — a handful of
///   stable C entry points are declared in the module that uses them. The
///   signature has not changed since Windows 95.
/// * Everywhere else: `df -Pk`, which is POSIX and identical on macOS and
///   Linux. The alternative was hand-declaring `statvfs`, whose *struct* is
///   laid out differently on every one of those platforms — a portability bug
///   waiting for a host nobody here can compile for.
///
/// A dead network mount makes this block, exactly as `fs::metadata` on the
/// same path already would.
#[cfg(windows)]
fn disk_space(dir: &Path) -> Option<(u64, u64)> {
    use std::os::windows::ffi::OsStrExt;
    let wide: Vec<u16> = dir
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let (mut available, mut total, mut free_total) = (0u64, 0u64, 0u64);
    let ok =
        unsafe { GetDiskFreeSpaceExW(wide.as_ptr(), &mut available, &mut total, &mut free_total) };
    // FreeBytesAvailableToCaller, not TotalNumberOfFreeBytes: on a volume with
    // quotas the second is a number this user cannot actually write into.
    (ok != 0).then_some((available, total))
}

#[cfg(not(windows))]
fn disk_space(dir: &Path) -> Option<(u64, u64)> {
    let out = std::process::Command::new("df")
        .arg("-Pk")
        .arg(dir)
        .env("LC_ALL", "C")
        .stdin(std::process::Stdio::null())
        .output()
        .ok()?;
    parse_df(&String::from_utf8_lossy(&out.stdout))
}

/// The free-space answer `PUT` uses, remembered for half a second.
///
/// A folder copy is one `PUT` per file and the cap is 20,000 of them, and on
/// every host but Windows the answer above costs a `df` fork — a whole process
/// spawned, per file, on the connection thread, for a number that cannot move
/// by more than the copy itself between two files. One entry is enough because
/// a batch copies into one folder.
///
/// What this trades away is exactness, and [`SPACE_MARGIN`] is why that is
/// affordable: a copy that outruns a stale answer runs out of margin rather
/// than out of disk, and the write then fails with `io` instead of `space`,
/// which is the same file refused with a less specific reason. The filesystem
/// remains the thing that actually stops the disk filling; this check is the
/// courtesy that says so before the bytes move.
///
/// The `FREE` verb deliberately does not come through here. The pane's footer
/// is asked once per navigation and can afford to be exact.
fn disk_space_cached(dir: &Path) -> Option<(u64, u64)> {
    static LAST: OnceLock<Mutex<Option<SpaceSeen>>> = OnceLock::new();
    let cell = LAST.get_or_init(|| Mutex::new(None));
    if let Ok(seen) = cell.lock() {
        if let Some(last) = seen.as_ref() {
            if last.dir.as_path() == dir && last.taken.elapsed() < SPACE_TTL {
                return Some(last.answer);
            }
        }
    }
    let answer = disk_space(dir)?;
    if let Ok(mut seen) = cell.lock() {
        *seen = Some(SpaceSeen {
            dir: dir.to_path_buf(),
            taken: Instant::now(),
            answer,
        });
    }
    Some(answer)
}

/// The one remembered answer. A named struct rather than the tuple it
/// replaces, which is one field past what clippy will read.
struct SpaceSeen {
    dir: PathBuf,
    taken: Instant,
    answer: (u64, u64),
}

/// The numbers out of `df -Pk`, without trusting the column positions.
///
/// A device name or a mount point can contain a space, which would shift every
/// index; what cannot shift is the shape of the middle of the row — three
/// counts followed by a percentage. `-P` is what guarantees the row is one
/// line rather than two.
#[cfg(not(windows))]
fn parse_df(text: &str) -> Option<(u64, u64)> {
    for line in text.lines().skip(1) {
        let fields: Vec<&str> = line.split_whitespace().collect();
        for i in 0..fields.len().saturating_sub(3) {
            let (Ok(total), Ok(_used), Ok(available)) = (
                fields[i].parse::<u64>(),
                fields[i + 1].parse::<u64>(),
                fields[i + 2].parse::<u64>(),
            ) else {
                continue;
            };
            if !fields[i + 3].ends_with('%') {
                continue;
            }
            return Some((available.saturating_mul(1024), total.saturating_mul(1024)));
        }
    }
    None
}

/// A free name for `name` in `dir` — "report.pdf" beside an existing one
/// becomes "report (2).pdf".
///
/// Character for character what `WebFiles.unique` does on the phone, including
/// the ceiling at 999 and the `lastIndexOf('.') > 0` rule that leaves
/// ".bashrc" as all stem and no extension. Both ends do the same thing so a
/// copy in either direction produces the same name, and neither ever silently
/// overwrites.
fn unique_name(dir: &Path, name: &str) -> String {
    let safe = safe_name(name);
    if !dir.join(&safe).exists() {
        return safe;
    }
    let (stem, ext) = split_ext(&safe);
    for i in 2..1000 {
        let candidate = format!("{stem} ({i}){ext}");
        if !dir.join(&candidate).exists() {
            return candidate;
        }
    }
    safe
}

fn split_ext(name: &str) -> (&str, &str) {
    match name.rfind('.') {
        Some(dot) if dot > 0 => (&name[..dot], &name[dot..]),
        _ => (name, ""),
    }
}

/// A name from the phone is not a path.
///
/// The mirror of `WebFiles.safeName`, plus the things only this side has to
/// care about. Android happily names a file `12:30 notes.txt` and Windows
/// cannot hold one, so the reserved characters become underscores rather than
/// the copy failing with an io error the user cannot act on. The device names
/// matter more than they look: without the check, a phone file called `NUL`
/// would be "copied" straight into the null device and reported as landed.
fn safe_name(name: &str) -> String {
    let flattened = name.replace('\\', "/");
    let tail = flattened.rsplit('/').next().unwrap_or("");
    let mut cleaned: String = tail.chars().filter(|c| !c.is_control()).collect();

    if cfg!(windows) {
        cleaned = cleaned
            .chars()
            .map(|c| if "<>:\"|?*".contains(c) { '_' } else { c })
            .collect();
        // Windows silently drops these from the end of a name, so a file
        // written as "notes." is then not the file anyone asked to open.
        cleaned = cleaned.trim_end_matches(['.', ' ']).to_string();
        let stem = split_ext(&cleaned).0.to_ascii_uppercase();
        const DEVICES: [&str; 22] = [
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
            "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        ];
        if DEVICES.contains(&stem.as_str()) {
            cleaned = format!("_{cleaned}");
        }
    }

    let cleaned = cleaned.trim();
    if cleaned.is_empty() || cleaned == "." || cleaned == ".." {
        return "upload".to_string();
    }
    // Keep the END of an over-long name, as the phone does: the extension is
    // the half a file manager cannot do without.
    let count = cleaned.chars().count();
    if count > 180 {
        cleaned.chars().skip(count - 180).collect()
    } else {
        cleaned.to_string()
    }
}

// ── GET / PUT ───────────────────────────────────────────────────────────

/// `GET <b64path>` → `OK <size>` and then exactly that many bytes.
fn get(out: &mut TcpStream, done: &AtomicBool, args: &[&str]) -> std::io::Result<()> {
    let opened = (|| -> Answer<(fs::File, u64)> {
        let path = checked(arg(args, 0)?)?;
        let meta = fs::metadata(&path).map_err(|e| Fault::io("could not stat", &e))?;
        if meta.is_dir() {
            return Err(Fault::new("isdir", path.to_string_lossy().to_string()));
        }
        if !meta.is_file() {
            return Err(Fault::new(
                "denied",
                format!("not a regular file: {}", path.to_string_lossy()),
            ));
        }
        let file = fs::File::open(&path).map_err(|e| Fault::io("could not open", &e))?;
        Ok((file, meta.len()))
    })();

    let (file, size) = match opened {
        Ok(v) => v,
        Err(fault) => return say(out, &fault.line(), done),
    };
    say(out, &format!("OK {size}"), done)?;

    // Exactly `size` bytes, no more and no fewer than the file can give. A
    // file that shrinks under a copy sends short and the phone's read fails,
    // which is the honest outcome — the alternative is padding a file with
    // zeroes so the byte count matches.
    let mut reader = BufReader::with_capacity(BUF, file);
    let mut buf = vec![0u8; BUF];
    let mut left = size;
    while left > 0 {
        if done.load(SeqCst) {
            return Err(ended());
        }
        let want = left.min(BUF as u64) as usize;
        match reader.read(&mut buf[..want]) {
            Ok(0) => break,
            Ok(n) => {
                write_polled(out, &buf[..n], done)?;
                left -= n as u64;
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    out.flush()
}

/// `PUT <b64path> <size>` → `OK`, the body, then `OK <b64landedName>`.
///
/// The body is written to `<target>.part` and renamed at the end, and the
/// `.part` is deleted on every failure path including an early EOF. That is
/// the same invariant `WebFiles.receive` and `WebRtcFiles.finishUpload` hold
/// on the phone, for the same reason: a connection that dies half way must
/// leave an obviously unfinished file, never a plausible truncated one. The
/// phone's own pane hides `.part` names while a copy is running.
///
/// The landed name is recomputed here rather than trusted from the request.
/// The phone asks `UNIQUE` before it starts, but between that answer and this
/// write anything at all can have appeared at the path — so the name that
/// comes back is the name on disk, and the phone reports that one.
fn put(
    reader: &mut BufReader<TcpStream>,
    out: &mut TcpStream,
    done: &AtomicBool,
    args: &[&str],
) -> std::io::Result<()> {
    let prepared = (|| -> Answer<(PathBuf, PathBuf, fs::File, u64)> {
        let target = checked(arg(args, 0)?)?;
        let size: u64 = arg(args, 1)?
            .parse()
            .map_err(|_| Fault::proto("the size is not a number"))?;
        if size > MAX_PUT {
            return Err(Fault::new("toobig", format!("{size} bytes")));
        }
        let parent = target
            .parent()
            .ok_or_else(|| Fault::new("notfound", "the destination has no parent"))?;
        let meta = fs::metadata(parent).map_err(|e| Fault::io("could not stat", &e))?;
        if !meta.is_dir() {
            return Err(Fault::new("notdir", parent.to_string_lossy().to_string()));
        }
        if let Some((available, _)) = disk_space_cached(parent) {
            if available < size.saturating_add(SPACE_MARGIN) {
                return Err(Fault::new("space", format!("{available} bytes free")));
            }
        }
        let wanted = target
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("upload");
        let landed = parent.join(unique_name(parent, wanted));
        let mut part = landed.clone().into_os_string();
        part.push(".part");
        let part = PathBuf::from(part);
        // Opened before the OK rather than after it. Once the phone hears OK
        // it streams the body, so a refusal sent after that is a line nobody
        // reads: this end closes with the body still arriving, the peer sees a
        // reset, and "denied" reaches the phone as "connection reset". A
        // write-protected folder is the common way to get here.
        let file = fs::File::create(&part).map_err(|e| Fault::io("could not write", &e))?;
        Ok((landed, part, file, size))
    })();

    let (landed, part, file, size) = match prepared {
        Ok(v) => v,
        Err(fault) => return say(out, &fault.line(), done),
    };
    if let Err(e) = say(out, "OK", done) {
        drop(file);
        let _ = fs::remove_file(&part);
        return Err(e);
    }

    let written = (|| -> std::io::Result<u64> {
        let mut sink = std::io::BufWriter::with_capacity(BUF, file);
        let n = pump(reader, &mut sink, size, done)?;
        sink.flush()?;
        Ok(n)
    })();
    match written {
        Ok(n) if n == size => {}
        Ok(n) => {
            let _ = fs::remove_file(&part);
            let fault = Fault::new("io", format!("the phone sent {n} of {size} bytes"));
            return say(out, &fault.line(), done);
        }
        Err(e) => {
            let _ = fs::remove_file(&part);
            return say(out, &Fault::io("could not write", &e).line(), done);
        }
    }
    if let Err(e) = fs::rename(&part, &landed) {
        let _ = fs::remove_file(&part);
        return say(out, &Fault::io("could not rename", &e).line(), done);
    }

    let name = landed
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or_default()
        .to_string();
    say(out, &format!("OK {}", b64(&name)), done)
}

// ── Socket plumbing ─────────────────────────────────────────────────────

/// Why a transfer stopped when nothing on the wire went wrong: the desktop
/// session ended under it. Distinct from an EOF so the log says which.
fn ended() -> std::io::Error {
    std::io::Error::other("the session ended")
}

/// One protocol line out, newline included.
fn say(out: &mut TcpStream, line: &str, done: &AtomicBool) -> std::io::Result<()> {
    write_polled(out, line.as_bytes(), done)?;
    write_polled(out, b"\n", done)?;
    out.flush()
}

/// Write everything, treating the socket timeout as a poll rather than a
/// failure.
///
/// `write_all` is not usable here: a `LIST` reply for a large folder is
/// hundreds of kilobytes, far past the socket buffer, so a phone that pauses
/// between reads would otherwise turn a full listing into an io error.
fn write_polled(out: &mut TcpStream, mut data: &[u8], done: &AtomicBool) -> std::io::Result<()> {
    while !data.is_empty() {
        if done.load(SeqCst) {
            return Err(ended());
        }
        match out.write(data) {
            Ok(0) => {
                return Err(std::io::Error::new(
                    ErrorKind::WriteZero,
                    "the phone stopped reading",
                ))
            }
            Ok(n) => data = &data[n..],
            Err(e) if timed_out(&e) => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// `size` bytes off the socket and into `sink`.
///
/// Returns what actually arrived, which the caller compares against what was
/// promised — a short count is the phone going away mid-file, and it is the
/// caller that must then delete the `.part`.
fn pump(
    reader: &mut BufReader<TcpStream>,
    sink: &mut impl Write,
    size: u64,
    done: &AtomicBool,
) -> std::io::Result<u64> {
    let mut buf = vec![0u8; BUF];
    let mut got = 0u64;
    while got < size {
        if done.load(SeqCst) {
            return Err(ended());
        }
        let want = (size - got).min(BUF as u64) as usize;
        match reader.read(&mut buf[..want]) {
            Ok(0) => break,
            Ok(n) => {
                sink.write_all(&buf[..n])?;
                got += n as u64;
            }
            Err(e) if timed_out(&e) => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(got)
}

/// One request line, or `None` when the peer has gone.
///
/// A control connection sits idle between clicks, so a timeout here is
/// expected and is not the end of anything — it is the only chance this thread
/// gets to notice the session ended. `MAX_LINE` is the ceiling on what a local
/// process can make it allocate.
///
/// `by` is the exception, and the handshake is the only caller that passes
/// one: a line that never arrives before it is fatal rather than polled, so a
/// connection that says nothing cannot hold a [`MAX_CONNS`] slot for the whole
/// session. It bites on a trickle too — bytes arriving keep resetting the
/// socket's own timeout, and the deadline is absolute.
fn read_line(
    reader: &mut BufReader<TcpStream>,
    done: &AtomicBool,
    by: Option<Instant>,
) -> std::io::Result<Option<String>> {
    let mut buf: Vec<u8> = Vec::new();
    loop {
        if done.load(SeqCst) {
            return Ok(None);
        }
        if by.is_some_and(|deadline| Instant::now() >= deadline) {
            return Err(std::io::Error::new(
                ErrorKind::TimedOut,
                "a connection that never said HELLO",
            ));
        }
        let budget = MAX_LINE.saturating_sub(buf.len());
        if budget == 0 {
            return Err(std::io::Error::new(
                ErrorKind::InvalidData,
                "a request line past the ceiling",
            ));
        }
        // One `fill_buf` per pass rather than `read_until`, which is what
        // makes the deadline above worth anything: `read_until` loops over
        // `recv` *inside* one call, so a peer dribbling a byte per poll and
        // never a newline would keep it in there — every read succeeding,
        // control never coming back — and the deadline would go unchecked for
        // as long as the bytes lasted. Coming back every read is the point.
        let chunk = match reader.fill_buf() {
            Ok(c) => c,
            // Bytes read before the timeout are already in `buf`, so retrying
            // resumes the line rather than restarting it.
            Err(e) if timed_out(&e) => continue,
            Err(e) => return Err(e),
        };
        // EOF. A partial line before it is a truncated request, and the only
        // sane reading of a truncated request is that the peer left.
        if chunk.is_empty() {
            return Ok(None);
        }
        // Never past the newline: a `PUT` body arrives in this same buffer,
        // glued to the request line, and one byte over-read here truncates
        // every file on the wire.
        let slice = &chunk[..chunk.len().min(budget)];
        let take = match slice.iter().position(|&b| b == b'\n') {
            Some(i) => i + 1,
            None => slice.len(),
        };
        buf.extend_from_slice(&slice[..take]);
        reader.consume(take);
        if buf.last() != Some(&b'\n') {
            // Either there is more line to come, or the budget ran out with
            // no newline in sight; the next pass through decides which.
            continue;
        }
        while matches!(buf.last(), Some(b'\n' | b'\r')) {
            buf.pop();
        }
        return match String::from_utf8(buf) {
            Ok(line) => Ok(Some(line)),
            Err(_) => Err(std::io::Error::new(
                ErrorKind::InvalidData,
                "a request line that was not UTF-8",
            )),
        };
    }
}

/// A socket timeout, on either host.
///
/// Windows reports `SO_RCVTIMEO` as `WSAETIMEDOUT` and the unixes report
/// `EAGAIN`, and Rust maps those to two different kinds. Treating only one of
/// them as a poll would leave the other host's connections dying every five
/// seconds.
fn timed_out(e: &std::io::Error) -> bool {
    matches!(
        e.kind(),
        ErrorKind::WouldBlock | ErrorKind::TimedOut | ErrorKind::Interrupted
    )
}

// ── base64url, without padding ──────────────────────────────────────────

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// The url-safe alphabet with the padding suppressed — `Base64.URL_SAFE |
/// NO_WRAP | NO_PADDING` on the other end.
///
/// `transfer::b64` is the standard alphabet with padding, for `am broadcast`
/// arguments. This one cannot reuse it: `+` and `/` are fine in a shell
/// argument and fatal in a whitespace-split protocol line, and `=` is fine
/// there too but makes a token that has to be quoted in every hand-typed `nc`
/// session. Encoding every path, name and error detail is what lets the parser
/// stay `split_whitespace` and still carry spaces, quotes, newlines and emoji.
fn b64(input: &str) -> String {
    b64_bytes(input.as_bytes())
}

fn b64_bytes(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(ALPHABET[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            out.push(ALPHABET[(n >> 6) as usize & 63] as char);
        }
        if chunk.len() > 2 {
            out.push(ALPHABET[n as usize & 63] as char);
        }
    }
    out
}

/// `None` for anything that is not this alphabet or is not UTF-8 underneath.
///
/// Trailing `=` is tolerated and ignored. Nothing this codebase writes emits
/// it, but a hand-driven `nc` session pasting from a padding encoder is
/// exactly the diagnosis path the line protocol exists for.
fn unb64(input: &str) -> Option<String> {
    String::from_utf8(unb64_bytes(input)?).ok()
}

fn unb64_bytes(input: &str) -> Option<Vec<u8>> {
    let trimmed = input.trim_end_matches('=');
    let mut out = Vec::with_capacity(trimmed.len() / 4 * 3);
    let mut acc: u32 = 0;
    let mut bits = 0u32;
    for c in trimmed.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'-' => 62,
            b'_' => 63,
            _ => return None,
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    // A lone trailing character carries six bits, which is not a byte and not
    // something any encoder produces.
    if bits >= 6 {
        return None;
    }
    Some(out)
}

// Three kernel32 entry points, declared rather than pulled in. (A `///` here
// would be an unused doc comment: rustc attaches nothing to an extern block.)
//
// None has changed shape since the 1990s. `windows-sys` would need
// `Win32_Storage_FileSystem` and `Win32_System_WindowsProgramming` added to
// its feature list in Cargo.toml, and the heavier `windows` crate — which
// already carries the first — is deliberately confined to dexcast. Declaring
// two stable C entry points in the module that uses them is the same trade
// `embed/macos.rs` makes for the six ApplicationServices calls it needs, and
// it keeps this whole feature at zero Cargo.toml churn.
#[cfg(windows)]
#[link(name = "kernel32")]
extern "system" {
    /// The bitmask of drive letters that exist, bit 0 = `A:`.
    ///
    /// Used instead of probing `A:\`..`Z:\` with `is_dir()`: `fs::metadata` on
    /// Windows opens the path with `CreateFileW`, and doing that to an empty
    /// card reader or optical drive is how a background thread raises a modal
    /// "There is no disk in the drive" box on the user's desktop. The bitmask
    /// touches no media at all, and an empty slot listed as a drive is exactly
    /// what Explorer itself shows.
    fn GetLogicalDrives() -> u32;

    /// Free and total bytes for the volume holding a directory. std has no
    /// equivalent on any host.
    fn GetDiskFreeSpaceExW(
        directory: *const u16,
        free_to_caller: *mut u64,
        total: *mut u64,
        free_total: *mut u64,
    ) -> i32;

    /// Per-thread error mode. Windows 7 and later; the process-wide
    /// `SetErrorMode` it replaces is the one thing we must not touch. Passing
    /// a null `old_mode` says we do not intend to put it back — the thread
    /// exists to serve one connection and then ends.
    fn SetThreadErrorMode(new_mode: u32, old_mode: *mut u32) -> i32;
}

// ── Tests ───────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// A path shaped the way THIS host spells one.
    ///
    /// The separator is the whole point of these fixtures: `checked` refuses a
    /// path that is not absolute, and "absolute" means something different on
    /// Windows — a hard-coded `/tmp/…` would stop exercising the accept branch
    /// there and a hard-coded `C:\…` would stop exercising it here.
    fn host_path(rest: &str) -> String {
        if cfg!(windows) {
            format!("C:\\tmp\\{rest}")
        } else {
            format!("/tmp/{rest}")
        }
    }

    /// A scratch directory of this test's own. Tests run in parallel, so
    /// sharing one would make `unique_name` answer about another test's files.
    fn scratch(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("oadx-files-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("a scratch directory");
        dir
    }

    fn row(name: &str, dir: bool) -> Row {
        Row {
            name: name.to_string(),
            path: host_path(name),
            dir,
            size: 0,
            modified: 0,
        }
    }

    #[test]
    fn base64url_round_trips_names_a_file_manager_actually_sees() {
        for original in [
            "",
            "a",
            "ab",
            "notes.txt",
            "Übergröße — Sicherungskopie (2).tar.gz",
            "C:\\Users\\ik\\a folder\\don't \"quote\" me $PATH|.txt",
            "写真 📸.jpg",
            "line\nbreak",
        ] {
            let encoded = b64(original);
            assert_eq!(
                unb64(&encoded).as_deref(),
                Some(original),
                "round trip of {original:?}"
            );
            assert!(
                !encoded.contains(['+', '/', '=']),
                "{encoded:?} is not url-safe and unpadded"
            );
        }
    }

    #[test]
    fn base64url_uses_minus_and_underscore_for_62_and_63() {
        // The two bytes chosen so both of the characters that differ from the
        // standard alphabet appear, and so the length is 2 mod 3 — the case
        // that would otherwise be padded.
        assert_eq!(b64_bytes(&[0xff, 0xef]), "_-8");
        assert_eq!(unb64_bytes("_-8"), Some(vec![0xff, 0xef]));
        assert_eq!(unb64_bytes("_-8="), Some(vec![0xff, 0xef]));
        // Not in the alphabet, and a lone six-bit tail.
        assert_eq!(unb64_bytes("a+b"), None);
        assert_eq!(unb64_bytes("Q"), None);
    }

    #[test]
    fn parses_a_request_line() {
        let line = format!("LIST {}", b64(&host_path("photos")));
        let (verb, args) = split_request(&line);
        assert_eq!(verb, "LIST");
        assert_eq!(args.len(), 1);
        assert_eq!(
            unb64(args[0]).as_deref(),
            Some(host_path("photos").as_str())
        );

        // Case-insensitive verb, extra whitespace, and a two-argument form.
        let (verb, args) = split_request("  unique   QUJD    ZGVm  ");
        assert_eq!(verb, "UNIQUE");
        assert_eq!(args, ["QUJD", "ZGVm"]);

        let (verb, args) = split_request("");
        assert_eq!(verb, "");
        assert!(args.is_empty());
    }

    #[test]
    fn a_path_must_be_absolute_and_free_of_dot_dot() {
        assert!(checked(&b64(&host_path("notes.txt"))).is_ok());

        let escape = checked(&b64(&host_path("photos/../../etc/shadow")))
            .expect_err("a .. path must be refused");
        assert_eq!(escape.code, "denied");

        let relative = checked(&b64("photos/holiday.jpg")).expect_err("relative must be refused");
        assert_eq!(relative.code, "denied");

        assert_eq!(checked(&b64("")).unwrap_err().code, "proto");
        assert_eq!(checked("not base64!").unwrap_err().code, "proto");
    }

    #[test]
    fn directories_come_first_then_names_case_insensitively() {
        let mut rows = vec![
            row("zebra.txt", false),
            row("Apple", true),
            row("beta.txt", false),
            row("apple.txt", false),
            row("zulu", true),
            row("Beta.txt", false),
        ];
        sort_rows(&mut rows);
        let names: Vec<&str> = rows.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(
            names,
            [
                "Apple",
                "zulu",
                "apple.txt",
                "beta.txt",
                "Beta.txt",
                "zebra.txt"
            ]
        );
    }

    #[test]
    fn case_folding_matches_javas_rule() {
        use std::cmp::Ordering;
        assert_eq!(cmp_ignore_case("apple", "APPLE"), Ordering::Equal);
        assert_eq!(cmp_ignore_case("Apple", "banana"), Ordering::Less);
        assert_eq!(cmp_ignore_case("b", "Apple"), Ordering::Greater);
        // A prefix sorts before the longer name, as `n1 - n2` does in Java.
        assert_eq!(cmp_ignore_case("note", "notes"), Ordering::Less);
        // The character whose case mapping is two characters long. Java's
        // `Character.toUpperCase('ß')` gives back 'ß' rather than "SS",
        // because it maps one char to one char — so Java compares U+00DF
        // against 's' and answers Greater. Folding the whole string with
        // Rust's `to_lowercase()` would expand it to "ss", compare equal for
        // one more character and answer Less, and the two panes would then
        // disagree about where the file sits in the list.
        assert_eq!(cmp_ignore_case("straße", "STRASSE"), Ordering::Greater);
    }

    #[test]
    fn unique_name_keeps_both_copies_the_way_the_phone_does() {
        let dir = scratch("unique");
        assert_eq!(unique_name(&dir, "report.pdf"), "report.pdf");

        fs::write(dir.join("report.pdf"), b"x").unwrap();
        assert_eq!(unique_name(&dir, "report.pdf"), "report (2).pdf");

        fs::write(dir.join("report (2).pdf"), b"x").unwrap();
        assert_eq!(unique_name(&dir, "report.pdf"), "report (3).pdf");

        // A leading dot is not an extension: `lastIndexOf('.') > 0` on both
        // ends, so the suffix goes on the end of the whole name.
        fs::write(dir.join(".bashrc"), b"x").unwrap();
        assert_eq!(unique_name(&dir, ".bashrc"), ".bashrc (2)");

        // A name arriving as a path keeps only its last component.
        assert_eq!(unique_name(&dir, "../../etc/passwd"), "passwd");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn safe_name_strips_everything_that_is_not_a_name() {
        assert_eq!(safe_name("holiday.jpg"), "holiday.jpg");
        assert_eq!(safe_name("a/b/c.txt"), "c.txt");
        assert_eq!(safe_name("a\\b\\c.txt"), "c.txt");
        assert_eq!(safe_name(".."), "upload");
        assert_eq!(safe_name(""), "upload");
        assert_eq!(safe_name("we\u{7}ird\u{0}.txt"), "weird.txt");
        assert_eq!(safe_name("x".repeat(400).as_str()).chars().count(), 180);

        // Windows-only rewrites, asserted only where they apply — the same
        // `cfg!` the function branches on, so neither host runs the other's
        // expectations.
        if cfg!(windows) {
            assert_eq!(safe_name("12:30 notes.txt"), "12_30 notes.txt");
            assert_eq!(safe_name("notes."), "notes");
            assert_eq!(safe_name("NUL"), "_NUL");
            assert_eq!(safe_name("nul.txt"), "_nul.txt");
        } else {
            assert_eq!(safe_name("12:30 notes.txt"), "12:30 notes.txt");
            assert_eq!(safe_name("NUL"), "NUL");
        }
    }

    #[test]
    fn a_listing_carries_full_paths_sorted_and_without_dotfiles() {
        let dir = scratch("list");
        fs::create_dir(dir.join("zulu")).unwrap();
        fs::create_dir(dir.join("Alpha")).unwrap();
        fs::write(dir.join("beta.txt"), b"hello").unwrap();
        fs::write(dir.join(".hidden"), b"x").unwrap();
        // A copy in flight, from this module's own `put` or from a browser.
        // Both panes hide these, so neither can be selected and copied while
        // it is still being written.
        fs::write(dir.join("movie.mp4.part"), b"half").unwrap();

        let listing = list(&dir).expect("a listing");
        let entries = listing["entries"].as_array().unwrap();
        let names: Vec<&str> = entries
            .iter()
            .map(|e| e["name"].as_str().unwrap())
            .collect();
        assert_eq!(
            names,
            ["Alpha", "zulu", "beta.txt"],
            "the dotfile and the .part are absent"
        );

        assert_eq!(listing["truncated"], json!(false));
        assert_eq!(entries[0]["dir"], json!(true));
        assert_eq!(entries[2]["size"], json!(5));
        // Every row carries the FULL host path, so the phone never joins one.
        assert_eq!(
            entries[2]["path"].as_str().unwrap(),
            dir.join("beta.txt").to_str().unwrap()
        );
        assert_eq!(
            listing["parent"].as_str().unwrap(),
            dir.parent().unwrap().to_str().unwrap()
        );

        assert_eq!(list(&dir.join("beta.txt")).unwrap_err().code, "notdir");
        assert_eq!(list(&dir.join("nope")).unwrap_err().code, "notfound");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_plan_is_flat_slash_separated_and_does_not_follow_links() {
        let dir = scratch("plan");
        fs::create_dir_all(dir.join("photos/raw")).unwrap();
        fs::write(dir.join("photos/a.jpg"), b"12345").unwrap();
        fs::write(dir.join("photos/raw/b.dng"), b"67").unwrap();
        fs::write(dir.join("top.txt"), b"x").unwrap();
        fs::write(dir.join(".hidden"), b"x").unwrap();
        fs::write(dir.join("photos/c.jpg.part"), b"half").unwrap();

        // The link is only made where making one is free. On Windows it needs
        // Developer Mode or SeCreateSymbolicLinkPrivilege, and a test that is
        // sometimes a privilege test instead of the test it claims to be is
        // worse than one that says plainly what it covers on which host.
        #[cfg(unix)]
        let expected_skips = {
            std::os::unix::fs::symlink(dir.join("top.txt"), dir.join("link.txt")).unwrap();
            1
        };
        #[cfg(not(unix))]
        let expected_skips = 0;

        let plan = walk(&dir).expect("a plan");
        let mut dirs: Vec<&str> = plan["dirs"]
            .as_array()
            .unwrap()
            .iter()
            .map(|d| d.as_str().unwrap())
            .collect();
        dirs.sort_unstable();
        assert_eq!(dirs, ["photos", "photos/raw"]);

        let mut files: Vec<&str> = plan["files"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| f["rel"].as_str().unwrap())
            .collect();
        files.sort_unstable();
        assert_eq!(files, ["photos/a.jpg", "photos/raw/b.dng", "top.txt"]);

        assert_eq!(plan["skipped"], json!(expected_skips));
        assert_eq!(plan["truncated"], json!(false));
        // Every file also carries its full host path, for the data socket.
        let a = plan["files"]
            .as_array()
            .unwrap()
            .iter()
            .find(|f| f["rel"] == json!("photos/a.jpg"))
            .unwrap();
        assert_eq!(
            a["path"].as_str().unwrap(),
            dir.join("photos").join("a.jpg").to_str().unwrap()
        );
        assert_eq!(a["size"], json!(5));

        assert_eq!(walk(&dir.join("top.txt")).unwrap_err().code, "notdir");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn mkdir_is_mkdir_p_and_refuses_to_shadow_a_file() {
        let dir = scratch("mkdir");
        assert!(mkdir(&dir.join("a/b/c")).is_ok());
        assert!(dir.join("a/b/c").is_dir());
        // Twice is success, because a folder copy asks per directory and does
        // not remember what it already asked for.
        assert!(mkdir(&dir.join("a/b/c")).is_ok());

        fs::write(dir.join("a/file"), b"x").unwrap();
        assert_eq!(mkdir(&dir.join("a/file")).unwrap_err().code, "exists");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_token_is_thirty_two_hex_characters() {
        let token = mint_token().expect("the system CSPRNG");
        assert_eq!(token.len(), 32);
        assert!(token.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(token, mint_token().unwrap(), "two mints must differ");
    }

    #[test]
    fn a_secret_compares_equal_only_to_itself() {
        assert!(same_secret("0123456789abcdef", "0123456789abcdef"));
        assert!(!same_secret("0123456789abcdef", "0123456789abcdee"));
        assert!(!same_secret("0123456789abcde", "0123456789abcdef"));
        assert!(!same_secret("", "0123456789abcdef"));
    }

    #[test]
    fn the_host_describes_itself_consistently() {
        assert_eq!(
            os_tag(),
            if cfg!(windows) {
                "win"
            } else if cfg!(target_os = "macos") {
                "mac"
            } else {
                "linux"
            }
        );
        assert_eq!(sep_hex(), if cfg!(windows) { "5c" } else { "2f" });
        // Never empty: the HELLO reply is positional and an empty label would
        // shift the OS into the separator's slot.
        assert!(!host_label().is_empty());
        assert!(unb64(&b64(&host_label())).is_some());
    }

    #[test]
    fn free_space_answers_for_a_directory_that_exists() {
        let dir = scratch("free");
        let (available, total) = free(&dir).expect("this volume has a size");
        assert!(total > 0, "a mounted volume has a total size");
        assert!(available <= total);

        // A destination file that does not exist yet is asked about by way of
        // the folder it would land in.
        let (a2, t2) = free(&dir.join("not-written-yet.bin")).expect("the parent answers");
        assert_eq!(t2, total);
        assert!(a2 <= t2);

        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(not(windows))]
    #[test]
    fn df_is_parsed_from_the_shape_of_the_row_not_its_columns() {
        let macos = "Filesystem 1024-blocks      Used Available Capacity  Mounted on\n\
                     /dev/disk3s5   971350180 812345678 158000000    84%    /System/Volumes/Data\n";
        assert_eq!(
            parse_df(macos),
            Some((158_000_000 * 1024, 971_350_180 * 1024))
        );

        // A device name with a space in it shifts every column and must not
        // shift the answer.
        let spaced = "Filesystem 1024-blocks Used Available Capacity Mounted on\n\
                      //user@nas/My Share 100 40 60 40% /Volumes/My Share\n";
        assert_eq!(parse_df(spaced), Some((60 * 1024, 100 * 1024)));

        assert_eq!(parse_df("Filesystem 1024-blocks\n"), None);
        assert_eq!(parse_df(""), None);
    }

    fn client(port: u16) -> (TcpStream, BufReader<TcpStream>) {
        let sock = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).expect("a test connection");
        let back = BufReader::new(sock.try_clone().expect("a second handle"));
        (sock, back)
    }

    fn reply_line(back: &mut BufReader<TcpStream>) -> String {
        let mut line = String::new();
        back.read_line(&mut line).expect("a reply line");
        line.trim_end_matches(['\r', '\n']).to_string()
    }

    /// The four conversations the phone actually has, over real sockets.
    ///
    /// The pieces above are all testable on their own; the framing is not, and
    /// it is the half that fails silently. `read_line` reads through the very
    /// `BufReader` a `PUT` body then comes out of, so a line reader that
    /// over-read by one byte would pass every other test in this file and
    /// truncate every file on the wire — which is why the `PUT` here sends its
    /// request line and its body in ONE write, guaranteeing both are sitting
    /// in that buffer when the line is parsed.
    #[test]
    fn the_whole_conversation_survives_one_socket() {
        let dir = scratch("wire");
        fs::write(dir.join("notes.txt"), b"hello").unwrap();
        let token = "0123456789abcdef0123456789abcdef";

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("a loopback port");
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            let done = AtomicBool::new(false);
            for _ in 0..4 {
                let (stream, _) = listener.accept().expect("a connection");
                converse(stream, token, &done).expect("a clean conversation");
            }
        });

        // A wrong token is refused, and nothing after it is answered at all.
        let (mut send, mut back) = client(port);
        writeln!(send, "HELLO deadbeef 1").unwrap();
        assert_eq!(reply_line(&mut back), "ERR auth");

        // The control connection: greet, list, leave.
        let (mut send, mut back) = client(port);
        writeln!(send, "HELLO {token} 1").unwrap();
        let hello: Vec<String> = reply_line(&mut back)
            .split(' ')
            .map(str::to_string)
            .collect();
        assert_eq!(hello[0], "OK");
        assert_eq!(unb64(&hello[1]).as_deref(), Some(host_label().as_str()));
        assert_eq!(hello[2], os_tag());
        assert_eq!(hello[3], sep_hex());

        writeln!(send, "LIST {}", b64(dir.to_str().unwrap())).unwrap();
        let listing = reply_line(&mut back);
        let json = unb64(listing.strip_prefix("OK ").expect("an OK with a payload"))
            .expect("base64url json");
        assert!(json.contains("notes.txt"), "{json}");
        writeln!(send, "BYE").unwrap();

        // A data connection: PUT, with the body glued to the request line.
        let landed = dir.join("landed.bin");
        let (mut send, mut back) = client(port);
        writeln!(send, "HELLO {token} 1").unwrap();
        assert!(reply_line(&mut back).starts_with("OK "));
        let mut frame = format!("PUT {} 5\n", b64(landed.to_str().unwrap())).into_bytes();
        frame.extend_from_slice(b"world");
        send.write_all(&frame).unwrap();
        assert_eq!(
            reply_line(&mut back),
            "OK",
            "the host is ready for the body"
        );
        let named = reply_line(&mut back);
        assert_eq!(
            unb64(named.strip_prefix("OK ").expect("the landed name")).as_deref(),
            Some("landed.bin")
        );
        assert_eq!(fs::read(&landed).unwrap(), b"world");
        assert!(
            !dir.join("landed.bin.part").exists(),
            "the .part is renamed, never left behind"
        );

        // …and one to read it back, which is where a size that disagreed with
        // the body would show up.
        let (mut send, mut back) = client(port);
        writeln!(send, "HELLO {token} 1").unwrap();
        assert!(reply_line(&mut back).starts_with("OK "));
        writeln!(send, "GET {}", b64(landed.to_str().unwrap())).unwrap();
        assert_eq!(reply_line(&mut back), "OK 5");
        let mut body = Vec::new();
        back.read_to_end(&mut body).unwrap();
        assert_eq!(body, b"world");

        server.join().expect("the server thread");
        let _ = fs::remove_dir_all(&dir);
    }

    /// A PUT this host cannot write is refused before the phone sends a byte.
    ///
    /// After `OK` the phone is streaming, and a refusal written then is lost:
    /// this end closes with the body still arriving and the peer sees a reset
    /// instead of the code. Measured before the `.part` was opened ahead of the
    /// `OK`: an 8 MB PUT into a read-only folder reached the phone as
    /// "Connection reset by peer". Unix only — a read-only folder is a mode bit
    /// here and nothing at all on Windows.
    #[cfg(unix)]
    #[test]
    fn a_put_that_cannot_be_written_is_refused_before_the_body() {
        use std::os::unix::fs::PermissionsExt;
        let dir = scratch("readonly");
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o555)).unwrap();
        let writable_anyway = fs::write(dir.join("probe"), b"").is_ok();
        if writable_anyway {
            // Root ignores mode bits, so there is no read-only folder to test.
            fs::set_permissions(&dir, fs::Permissions::from_mode(0o755)).unwrap();
            let _ = fs::remove_dir_all(&dir);
            return;
        }

        let token = "0123456789abcdef0123456789abcdef";
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("a loopback port");
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            let done = AtomicBool::new(false);
            let (stream, _) = listener.accept().expect("a connection");
            converse(stream, token, &done)
        });

        let (mut send, mut back) = client(port);
        writeln!(send, "HELLO {token} 1").unwrap();
        assert!(reply_line(&mut back).starts_with("OK "));
        // The request line alone: the answer has to come before any body.
        let target = dir.join("big.bin");
        writeln!(send, "PUT {} {}", b64(target.to_str().unwrap()), 64u64 << 20).unwrap();
        let answer = reply_line(&mut back);
        let _ = server.join();

        fs::set_permissions(&dir, fs::Permissions::from_mode(0o755)).unwrap();
        let left: Vec<_> = fs::read_dir(&dir).unwrap().flatten().collect();
        let _ = fs::remove_dir_all(&dir);
        assert!(answer.starts_with("ERR denied"), "refused up front: {answer}");
        assert!(left.is_empty(), "nothing written, not even a .part");
    }

    /// A connection that never greets us is dropped, not held.
    ///
    /// There are only `MAX_CONNS` slots, and before the handshake had a
    /// deadline a socket that connected and then said nothing sat inside
    /// `read_line` for the rest of the session — every timeout caught, the
    /// read resumed. Eight of those, from any local process and without ever
    /// presenting a token, and the launcher could not reach this host at all.
    /// The budget is passed in rather than waited out: a test that spends the
    /// real five seconds asleep is a test people learn to skip.
    #[test]
    fn an_ungreeted_connection_is_dropped_rather_than_held() {
        let budget = Duration::from_millis(200);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("a loopback port");
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            let done = AtomicBool::new(false);
            let (stream, _) = listener.accept().expect("a connection");
            let started = Instant::now();
            let outcome =
                converse_within(stream, "0123456789abcdef0123456789abcdef", &done, budget);
            (started.elapsed(), outcome.is_err())
        });

        // Connect, and then say nothing at all — the whole of the attack.
        let (_send, mut back) = client(port);
        // Bounded so a regression fails here instead of hanging the suite: the
        // host closing its end is what ends this read, and without the
        // deadline it never closes.
        back.get_ref()
            .set_read_timeout(Some(IO_POLL))
            .expect("a client-side timeout");
        let mut answered = Vec::new();
        back.read_to_end(&mut answered)
            .expect("the host to close the socket on its own");
        assert!(
            answered.is_empty(),
            "a connection that never authenticated is told nothing"
        );

        let (waited, gave_up) = server.join().expect("the server thread");
        assert!(gave_up, "the handshake ends as an error, not a clean close");
        assert!(
            waited < IO_POLL,
            "dropped on its own budget rather than some later poll: {waited:?}"
        );
    }

    /// …and a connection that trickles is dropped on the same budget.
    ///
    /// The deadline is only worth the socket timeout beside it if control
    /// actually comes back to be checked. A sender that dribbles one byte per
    /// poll and never a newline keeps every `recv` returning data, so a reader
    /// that loops *inside* one call — as `read_until` does — never returns to
    /// the top of the loop and the deadline is never consulted. That is the
    /// same free slot-holding as saying nothing, for one byte every few
    /// seconds, so it has to end on the same budget.
    #[test]
    fn a_trickle_that_never_ends_a_line_is_dropped_on_the_same_budget() {
        let budget = Duration::from_millis(200);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("a loopback port");
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            let done = AtomicBool::new(false);
            let (stream, _) = listener.accept().expect("a connection");
            let started = Instant::now();
            let outcome =
                converse_within(stream, "0123456789abcdef0123456789abcdef", &done, budget);
            (started.elapsed(), outcome.is_err())
        });

        // One byte at a time, comfortably inside the budget, never a newline —
        // and never a HELLO. Stops of its own accord so the test cannot hang
        // if the host declines to close.
        let (mut send, mut back) = client(port);
        let trickler = thread::spawn(move || {
            for _ in 0..40 {
                if send.write_all(b"H").is_err() || send.flush().is_err() {
                    return;
                }
                thread::sleep(budget / 4);
            }
        });

        back.get_ref()
            .set_read_timeout(Some(IO_POLL))
            .expect("a client-side timeout");
        let mut answered = Vec::new();
        back.read_to_end(&mut answered)
            .expect("the host to close the socket on its own");
        assert!(
            answered.is_empty(),
            "a connection that never authenticated is told nothing"
        );

        let (waited, gave_up) = server.join().expect("the server thread");
        let _ = trickler.join();
        assert!(gave_up, "the handshake ends as an error, not a clean close");
        // Against the budget, not against `IO_POLL`: a host that only gave up
        // once the trickler ran out of bytes would still beat five seconds,
        // and that is precisely the bug.
        assert!(
            waited < budget * 3,
            "the trickle ends on the handshake budget, not on the byte supply: {waited:?}"
        );
    }
}
