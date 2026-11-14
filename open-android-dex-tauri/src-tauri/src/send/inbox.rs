//! What the phone sends, landing here (doc/send-to-computer.md, "What a link
//! is", "Where things land").
//!
//! - **Files** go to Downloads. Never over anything: each final name is
//!   reserved first by creating it exclusively — `name`, `name (2)`, … — the
//!   bytes go to a hidden `.name.part` beside it, made exclusively too (one
//!   already there is somebody else's, and its name is skipped), and the part
//!   is renamed over the reservation while that is still the empty file made
//!   for it; filled meanwhile, it is left alone and the file lands under
//!   another free name. Giving up removes the part, and the reservation only
//!   while it is still that empty file. A file from the
//!   phone is marked as downloaded, as a browser marks one, so the system's
//!   own caution applies to it. Nothing is opened by itself.
//! - **A link** — text that is one `http(s)` URL, or one with a title — opens
//!   in the browser, at most once every three seconds and only while "Open
//!   links right away" is on; otherwise the notification offers it. Words
//!   that came with it (a title, a message) go on the clipboard, the whole
//!   text, and the notification says so. No other scheme is ever opened.
//! - **Text** goes on the clipboard.
//!
//! Any app on the phone can start a share — Android tells a share target
//! nothing reliable about who asked — so what one can make this computer do
//! is capped ([`Caps`]): [`SHARES_PER_MINUTE`] shares a minute from a phone,
//! and [`NOTICES_PER_MINUTE`] notifications a minute. Past the share cap the
//! share is turned away, the phone's card says this computer is busy, and this
//! computer says so once. [`TRANSFERS_AT_ONCE`] files shares come in at once;
//! one more waits its turn, which its card says.
//!
//! Each says so in one of this computer's notifications (notifications/mod.rs,
//! `own_toast`), or the page's own toast where there are none, and is kept in
//! a short list for the Photos tab — files and links across runs
//! (send-recent.json), text for this run only.

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};

use super::link::Link;
use super::words;
use crate::apps::{self, shared};

/// The most arrivals the Photos tab lists.
const RECENT_KEPT: usize = 30;
/// A link opens at most this often by itself: the one thing that acts on its own.
const LINK_PAUSE: Duration = Duration::from_secs(3);
/// What text may carry beside its one link and still be that link: a title.
const TITLE_MAX: usize = 300;
/// A file this quick to come needs no strip saying how far it is.
const QUIET_FOR: Duration = Duration::from_millis(400);
/// How long the phone may go quiet in the middle of a file.
const READ_TIMEOUT: Duration = Duration::from_secs(30);
/// Shares one phone may send in a minute; more are turned away.
pub(crate) const SHARES_PER_MINUTE: usize = 20;
/// Files shares coming in at once, from every phone: each is a thread and an adb
/// stream. One more waits its turn (someone sharing four videos one after
/// another is ordinary use); the per-minute cap is the limit.
pub(crate) const TRANSFERS_AT_ONCE: usize = 3;
/// What a file arriving in Downloads leaves free on this disk, at the least:
/// less than Keep a copy's floor — one file is not a backlog — and enough for
/// the system to carry on.
const ARRIVAL_FLOOR: u64 = 500_000_000;
/// While a file comes, the disk's free space is asked again every this many bytes.
const FREE_EVERY_BYTES: u64 = 64 * 1024 * 1024;
/// This computer's notifications for what arrives, in a minute: past it, what
/// arrives is still listed on the Photos tab, without a notification.
pub(crate) const NOTICES_PER_MINUTE: usize = 10;
const MINUTE: Duration = Duration::from_secs(60);

// ── what a link is ──

/// The one `http`/`https` URL `text` is, if it is one: the whole of it, trimmed,
/// or the only one in it with nothing beside it longer than a title.
pub(crate) fn link_in(text: &str) -> Option<String> {
    let urls: Vec<&str> = text.split_whitespace().map(url_of_word).filter(|w| is_web_url(w)).collect();
    if urls.len() != 1 {
        return None;
    }
    let url = urls[0];
    let rest: String = text.replacen(url, "", 1);
    let rest = rest.trim();
    (rest.chars().count() <= TITLE_MAX).then(|| url.to_string())
}

/// Whether `text`, a link (`url`) with words beside it, says more than the
/// link: a title, a sentence — which go on the clipboard as the link opens,
/// rather than nowhere. Brackets and punctuation around it are no words.
pub(crate) fn words_beside(text: &str, url: &str) -> bool {
    text.replacen(url, "", 1).chars().any(char::is_alphanumeric)
}

/// A word of text as the URL it holds: what wraps it, and what ends its
/// sentence, taken off — a closing bracket or quote only when its opener is
/// not in the URL, so "…/wiki/Rust_(programming_language)" keeps its ")".
pub(crate) fn url_of_word(w: &str) -> &str {
    let mut u = w.trim_start_matches(['<', '(', '[', '{', '"', '\'', '“', '‘', '«']);
    loop {
        let Some(last) = u.chars().last() else { return u };
        let body = &u[..u.len() - last.len_utf8()];
        let drop = match last {
            '.' | ',' | ';' | ':' | '!' | '?' => true,
            ')' => body.matches('(').count() < u.matches(')').count(),
            ']' => body.matches('[').count() < u.matches(']').count(),
            '}' => body.matches('{').count() < u.matches('}').count(),
            '>' => body.matches('<').count() < u.matches('>').count(),
            '"' | '\'' => !body.contains(last),
            '”' => !body.contains('“'),
            '’' => !body.contains('‘'),
            '»' => !body.contains('«'),
            _ => false,
        };
        if !drop {
            return u;
        }
        u = body;
    }
}

fn is_web_url(w: &str) -> bool {
    let lower = w.to_ascii_lowercase();
    let host = lower.strip_prefix("https://").or_else(|| lower.strip_prefix("http://"));
    match host {
        Some(h) => {
            let host = h.split(['/', '?', '#']).next().unwrap_or("");
            !host.is_empty() && !w.chars().any(|c| c.is_control())
        }
        None => false,
    }
}

/// A link opens by itself at most once in [`LINK_PAUSE`].
#[derive(Default)]
pub(crate) struct Pacer {
    last: Option<Instant>,
}

impl Pacer {
    /// Whether a link may open now, at `now`; if so, it is counted.
    pub(crate) fn may_open(&mut self, now: Instant) -> bool {
        if self.last.is_some_and(|t| now.duration_since(t) < LINK_PAUSE) {
            return false;
        }
        self.last = Some(now);
        true
    }
}

fn pacer() -> MutexGuard<'static, Pacer> {
    static P: OnceLock<Mutex<Pacer>> = OnceLock::new();
    P.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

/// At most `most` in any `span`: a count of the last ones' times.
#[derive(Default)]
pub(crate) struct Window {
    times: VecDeque<Instant>,
    /// Turned away and said so: not said again until one is let in.
    told: bool,
}

impl Window {
    /// Whether one more fits at `now`; if so, it is counted.
    pub(crate) fn admit(&mut self, now: Instant, most: usize, span: Duration) -> bool {
        while self.times.front().is_some_and(|t| now.saturating_duration_since(*t) >= span) {
            self.times.pop_front();
        }
        if self.times.len() >= most {
            return false;
        }
        self.times.push_back(now);
        self.told = false;
        true
    }

    /// Whether this is the first turned away since the last let in: said once.
    pub(crate) fn tell_once(&mut self) -> bool {
        !std::mem::replace(&mut self.told, true)
    }
}

/// What any app on the phone can make this computer do, capped (the module's words).
#[derive(Default)]
struct Caps {
    shares: HashMap<String, Window>,
    notices: Window,
}

fn caps() -> MutexGuard<'static, Caps> {
    static C: OnceLock<Mutex<Caps>> = OnceLock::new();
    C.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

/// Files shares coming in now, and the bell one waiting for its turn listens for.
fn slots() -> &'static (Mutex<usize>, Condvar) {
    static S: OnceLock<(Mutex<usize>, Condvar)> = OnceLock::new();
    S.get_or_init(|| (Mutex::new(0), Condvar::new()))
}

/// One of [`TRANSFERS_AT_ONCE`] slots, given back when it ends however it ends.
struct Receiving;

impl Receiving {
    fn try_take() -> Option<Receiving> {
        let mut n = slots().0.lock().unwrap_or_else(|e| e.into_inner());
        (*n < TRANSFERS_AT_ONCE).then(|| {
            *n += 1;
            Receiving
        })
    }

    /// A slot, waited for until one is free — or None, if `flight` ended first
    /// (taken back on the phone, the link gone, this app exiting).
    fn wait(flight: &Flight) -> Option<Receiving> {
        let (lock, bell) = slots();
        let mut n = lock.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if *n < TRANSFERS_AT_ONCE {
                *n += 1;
                return Some(Receiving);
            }
            if flight.ended().is_some() {
                return None;
            }
            n = bell.wait_timeout(n, Duration::from_millis(250)).unwrap_or_else(|e| e.into_inner()).0;
        }
    }
}

impl Drop for Receiving {
    fn drop(&mut self) {
        let (lock, bell) = slots();
        *lock.lock().unwrap_or_else(|e| e.into_inner()) -= 1;
        bell.notify_all();
    }
}

/// A share's [`Flight`] in [`flights`] for as long as its thread runs.
struct InFlight((String, u64));

impl Drop for InFlight {
    fn drop(&mut self) {
        flights().remove(&self.0);
    }
}

// ── where files land ──

/// How many names a finished file is tried under before it is given up: the
/// one reserved, then others, should something be put there meanwhile.
const LAND_TRIES: usize = 3;

/// A name in a folder, taken before a byte lands, and the hidden part its
/// bytes go to — both made here, exclusively: neither is ever a file that was
/// there before.
#[derive(Debug)]
pub(crate) struct Reserved {
    pub final_path: PathBuf,
    pub part: PathBuf,
    /// The name asked for: another like it is found if this one is filled meanwhile.
    wanted: String,
    /// The reservation, as it was made: it stays empty while it is ours. (The
    /// part is known by what it is as it is closed, every byte in: [`close`].)
    made: Made,
    /// The part, open since it was made: the bytes go into it, never into a path opened again.
    out: File,
}

impl Reserved {
    /// The part, open: what the bytes are written into.
    pub(crate) fn file(&self) -> &File {
        &self.out
    }

    /// The part renamed over the reservation, while that is still the empty
    /// file this app made; filled meanwhile, it is left as it is and the part
    /// lands under another free name. The part is removed only once no name
    /// would take it.
    pub(crate) fn land(self) -> std::io::Result<PathBuf> {
        let Reserved { final_path, part, wanted, made, out } = self;
        // Closed before it is moved: Windows moves no file that is open without sharing its deletion.
        let part_made = close(out);
        let landed = place(&part, final_path, made, &wanted);
        if landed.is_ok() {
            unfinished(&part, false, Leftover::Part);
        } else {
            let_go(&part, part_made, Leftover::Part);
        }
        landed
    }

    /// `from` — a file of this app's own beside the reservation (Keep a
    /// copy's incoming picture) — renamed over it as [`Reserved::land`]
    /// renames the part; this one's own part, never written, removed. Where
    /// it landed; on failure `from` is left where it is, for its owner.
    pub(crate) fn land_from(self, from: &Path) -> std::io::Result<PathBuf> {
        let Reserved { final_path, part, wanted, made, out } = self;
        let part_made = close(out);
        let_go(&part, part_made, Leftover::Part);
        place(from, final_path, made, &wanted)
    }

    /// Given up: the part removed, and the reservation while it is still the
    /// empty file this app made — whatever was put there meanwhile stays.
    pub(crate) fn abandon(self) {
        let Reserved { final_path, part, made, out, .. } = self;
        let part_made = close(out);
        let_go(&part, part_made, Leftover::Part);
        let_go(&final_path, made, Leftover::Reserved);
    }
}

/// A part closed, every byte in: what it is now, to let it go by. Not what it
/// was as it was made — on FAT and exFAT a file is numbered anew by its first
/// byte, and a part known by its empty self would never be ours again.
pub(crate) fn close(f: File) -> Made {
    let made = Made::of(&f);
    drop(f);
    made
}

/// A file this app made, as it was made (a part: as it was closed): the same
/// file later only while its identity still says so — a Unix file's device
/// and inode, a Windows file's volume serial number and file id (none known:
/// only what it is and how long, below). Not a Windows file's creation time,
/// which a file deleted and made again at its name within 15 s keeps.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Made(Option<(u64, u128)>);

impl Made {
    fn of(f: &File) -> Made {
        Made(identity_of(f))
    }

    /// `path` is still that file — a plain file, not a link — and, if `empty`, still empty.
    fn is(self, path: &Path, empty: bool) -> bool {
        std::fs::symlink_metadata(path).is_ok_and(|m| m.is_file() && (!empty || m.len() == 0) && self.0.is_none_or(|id| identity_at(path, &m) == Some(id)))
    }
}

/// An open file's identity.
fn identity_of(f: &File) -> Option<(u64, u128)> {
    #[cfg(windows)]
    {
        windows_id(f)
    }
    #[cfg(not(windows))]
    {
        f.metadata().ok().and_then(|m| identity_at(Path::new(""), &m))
    }
}

/// The identity of the file at `path`, `m` its metadata (not followed through a link).
fn identity_at(path: &Path, m: &std::fs::Metadata) -> Option<(u64, u128)> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let _ = path;
        Some((m.dev(), m.ino() as u128))
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        let _ = m;
        // Its attributes only, never through a link, shared with everyone: nobody's open, write or delete is held up.
        const FILE_READ_ATTRIBUTES: u32 = 0x80;
        const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
        const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
        let f = OpenOptions::new()
            .access_mode(FILE_READ_ATTRIBUTES)
            .share_mode(7)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS)
            .open(path)
            .ok()?;
        windows_id(&f)
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (path, m);
        None
    }
}

/// A Windows file's volume serial number and file id: the 128-bit one
/// (FILE_ID_INFO, ReFS's own), else the 64-bit index (FAT, which has no other).
#[cfg(windows)]
fn windows_id(f: &File) -> Option<(u64, u128)> {
    use std::os::windows::io::AsRawHandle;
    use windows::Win32::Foundation::HANDLE;
    use windows::Win32::Storage::FileSystem::{
        FileIdInfo, GetFileInformationByHandle, GetFileInformationByHandleEx, BY_HANDLE_FILE_INFORMATION, FILE_ID_INFO,
    };
    let h = HANDLE(f.as_raw_handle());
    let mut id = FILE_ID_INFO::default();
    let size = std::mem::size_of::<FILE_ID_INFO>() as u32;
    if unsafe { GetFileInformationByHandleEx(h, FileIdInfo, (&mut id as *mut FILE_ID_INFO).cast(), size) }.is_ok() {
        return Some((id.VolumeSerialNumber, u128::from_le_bytes(id.FileId.Identifier)));
    }
    let mut info = BY_HANDLE_FILE_INFORMATION::default();
    unsafe { GetFileInformationByHandle(h, &mut info) }.ok()?;
    Some((info.dwVolumeSerialNumber as u64, ((info.nFileIndexHigh as u128) << 32) | info.nFileIndexLow as u128))
}

/// A part's hidden mark taken off as it lands under its name: renamed, a file
/// keeps its attributes (Windows; elsewhere the dot was the mark, and goes).
#[cfg(windows)]
fn unhide(path: &Path) {
    use std::os::windows::ffi::OsStrExt;
    use windows::core::PCWSTR;
    use windows::Win32::Storage::FileSystem::{GetFileAttributesW, SetFileAttributesW, FILE_ATTRIBUTE_HIDDEN, FILE_FLAGS_AND_ATTRIBUTES, INVALID_FILE_ATTRIBUTES};
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    unsafe {
        let a = GetFileAttributesW(PCWSTR(wide.as_ptr()));
        if a != INVALID_FILE_ATTRIBUTES && a & FILE_ATTRIBUTE_HIDDEN.0 != 0 {
            let _ = SetFileAttributesW(PCWSTR(wide.as_ptr()), FILE_FLAGS_AND_ATTRIBUTES(a & !FILE_ATTRIBUTE_HIDDEN.0));
        }
    }
}

/// `from` renamed over the reservation `at`, while that is still the empty
/// file this app made (`made`). Otherwise — something put there meanwhile, or
/// a rename that fails — another free name like `wanted` is reserved first and
/// tried, and the one before it let go of (only while it is still ours), up to
/// [`LAND_TRIES`] names. Where it landed.
fn place(from: &Path, mut at: PathBuf, mut made: Made, wanted: &str) -> std::io::Result<PathBuf> {
    let dir = at.parent().map(Path::to_path_buf).unwrap_or_default();
    #[cfg(windows)]
    unhide(from);
    let mut tries = 1;
    loop {
        let why = match rename_over(from, &at, made) {
            Ok(()) => {
                unfinished(&at, false, Leftover::Reserved);
                return Ok(at);
            }
            Err(e) => e,
        };
        // Taken away meanwhile (someone tidying Downloads): nothing of ours is there to let go of.
        let gone = std::fs::symlink_metadata(&at).is_err();
        let again = tries < LAND_TRIES;
        log::warn!("send: {} did not take what arrived ({why}){}", at.display(), if again { "; another name is tried" } else { "" });
        if !again {
            if gone {
                unfinished(&at, false, Leftover::Reserved);
            } else {
                let_go(&at, made, Leftover::Reserved);
            }
            return Err(why);
        }
        // The next name first, so the one let go of is never the one tried again.
        let next = claim_free(&dir, wanted);
        match &next {
            // The same name made anew, as it had been taken away: that is the reservation now. Let go of by
            // the old one's identity — which a new file may be given (NTFS, ext4) — it would be removed.
            Ok((p, _)) if *p == at => {}
            _ if gone => unfinished(&at, false, Leftover::Reserved),
            _ => let_go(&at, made, Leftover::Reserved),
        }
        (at, made) = next?;
        tries += 1;
    }
}

/// How long a rename another program holds up is tried again (Windows).
const RENAME_PATIENCE: Duration = Duration::from_secs(1);

/// `from` renamed over the reservation `at`, while that is still the empty
/// file this app made (`made`) — looked at again before each try. On Windows
/// a rename another program's open handle holds up for a moment (an antivirus
/// or an indexer looking at a new file, Explorer at an open folder) is tried
/// again for about a second before another name is.
fn rename_over(from: &Path, at: &Path, made: Made) -> std::io::Result<()> {
    let until = Instant::now() + RENAME_PATIENCE;
    loop {
        if !made.is(at, true) {
            return Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "it was filled or taken away meanwhile"));
        }
        match std::fs::rename(from, at) {
            Err(e) if held_by_another(&e) && Instant::now() < until => std::thread::sleep(Duration::from_millis(50)),
            r => return r,
        }
    }
}

/// A rename held up by another program's handle, for a moment: Windows'
/// ERROR_ACCESS_DENIED, ERROR_SHARING_VIOLATION, ERROR_LOCK_VIOLATION.
fn held_by_another(e: &std::io::Error) -> bool {
    cfg!(windows) && matches!(e.raw_os_error(), Some(5 | 32 | 33))
}

/// `name`, then "name (2).ext", "name (3).ext" …
fn candidates(name: &str) -> impl Iterator<Item = String> + '_ {
    let wanted = Path::new(name);
    let stem = wanted.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
    let ext = wanted.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    (1..10_000u32).map(move |n| if n == 1 { name.to_string() } else { format!("{stem} ({n}){ext}") })
}

/// `path` made here, empty and exclusively — never a file that was there, nor
/// through a link that was: open, and what it is as made; None if the name is taken.
/// A `hidden` one (a part) is hidden on Windows as well as by its dot.
fn claim(path: &Path, hidden: bool) -> std::io::Result<Option<(File, Made)>> {
    let mut o = OpenOptions::new();
    // Read as well: Windows says what a file is only to a handle that may read its attributes.
    o.read(true).write(true).create_new(true);
    #[cfg(windows)]
    if hidden {
        use std::os::windows::fs::OpenOptionsExt;
        // FILE_ATTRIBUTE_HIDDEN: a dot hides nothing in Explorer.
        o.attributes(0x2);
    }
    #[cfg(not(windows))]
    let _ = hidden;
    match o.open(path) {
        Ok(f) => {
            let made = Made::of(&f);
            Ok(Some((f, made)))
        }
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Ok(None),
        Err(e) => Err(e),
    }
}

/// A free name like `wanted` in `dir`, reserved on its own (a finished file is
/// to be moved there), and listed in flight.
fn claim_free(dir: &Path, wanted: &str) -> std::io::Result<(PathBuf, Made)> {
    for candidate in candidates(wanted) {
        let path = dir.join(&candidate);
        if let Some((_, made)) = claim(&path, false)? {
            unfinished(&path, true, Leftover::Reserved);
            return Ok((path, made));
        }
    }
    Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "no free name"))
}

/// `path` removed while it is still the file this app made — a reservation
/// only while it is still empty — and no longer in flight once it is gone or
/// no longer ours. One that will not go stays listed, for the next start.
fn let_go(path: &Path, made: Made, kind: Leftover) {
    if made.is(path, matches!(kind, Leftover::Reserved)) && std::fs::remove_file(path).is_err() {
        return;
    }
    unfinished(path, false, kind);
}

/// A hidden file of this app's own in `dir` — `.{base}`, or `.{base}-2` … if
/// that is taken — made here exclusively and listed in flight: Keep a copy's
/// picture on its way, before it has a name. Its path, and the file, open;
/// [`close`] it with its bytes in, to remove it by what that says ([`let_go_part`]).
pub(crate) fn part_in(dir: &Path, base: &str) -> std::io::Result<(PathBuf, File)> {
    for n in 1..100u32 {
        let path = dir.join(if n == 1 { format!(".{base}") } else { format!(".{base}-{n}") });
        if let Some((f, _)) = claim(&path, true)? {
            unfinished(&path, true, Leftover::Part);
            return Ok((path, f));
        }
    }
    Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "no free name"))
}

/// A [`part_in`] removed, while it is still the one made; no longer in flight.
pub(crate) fn let_go_part(path: &Path, made: Made) {
    let_go(path, made, Leftover::Part);
}

// ── what a crash leaves ──

/// What this app is in the middle of writing, kept in a small file of its own
/// beside its settings: a part being written, a name reserved and still
/// empty. What is listed there when this app starts is what a crash or a
/// power cut left, and only that is removed — a part, and a reservation that
/// is still empty. Nothing else in Downloads or a Keep folder is looked at.
#[derive(Default)]
struct Landing {
    path: Option<PathBuf>,
    parts: BTreeSet<PathBuf>,
    reserved: BTreeSet<PathBuf>,
}

#[derive(Serialize, Deserialize, Default)]
struct LandingFile {
    #[serde(default)]
    parts: Vec<PathBuf>,
    #[serde(default)]
    reserved: Vec<PathBuf>,
}

#[derive(Clone, Copy)]
pub(crate) enum Leftover {
    /// Removed if it is there.
    Part,
    /// Removed if it is there and still empty.
    Reserved,
}

fn landing() -> MutexGuard<'static, Landing> {
    static L: OnceLock<Mutex<Landing>> = OnceLock::new();
    L.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

/// Where the list is kept, after what an earlier run left in it is removed: at start, before anything lands.
pub(crate) fn landing_at(path: PathBuf) {
    let n = sweep(&path);
    if n > 0 {
        log::info!("send: removed {n} unfinished file(s) an earlier run left");
    }
    landing().path = Some(path);
}

/// What the list at `path` names, removed as [`Leftover`] says; the list too. How many files went.
pub(crate) fn sweep(path: &Path) -> usize {
    let Ok(bytes) = std::fs::read(path) else { return 0 };
    let left: LandingFile = serde_json::from_slice(&bytes).unwrap_or_default();
    let mut n = 0;
    for p in &left.parts {
        if std::fs::remove_file(p).is_ok() {
            n += 1;
        }
    }
    for p in &left.reserved {
        let empty = std::fs::symlink_metadata(p).is_ok_and(|m| m.is_file() && m.len() == 0);
        if empty && std::fs::remove_file(p).is_ok() {
            n += 1;
        }
    }
    let _ = std::fs::remove_file(path);
    n
}

/// `p` in flight (`on`), or no longer.
pub(crate) fn unfinished(p: &Path, on: bool, kind: Leftover) {
    landing().change(&[(p, kind)], on);
}

impl Landing {
    #[cfg(test)]
    fn mark(&mut self, p: &Path, on: bool, kind: Leftover) {
        self.change(&[(p, kind)], on);
    }

    /// `paths` in flight (`on`), or no longer, in one write of the list.
    /// What is no longer in flight is written with the next thing listed, or
    /// by the list's removal once nothing is: a list that still names it names
    /// a file gone, a part landed, or a file that is not empty — so Keep a copy
    /// writes and flushes it twice a picture, not five times. Except an empty
    /// file at a reservation's name (an empty file that arrived, or somebody
    /// else's), which the sweep would take for the reservation: written at once.
    fn change(&mut self, paths: &[(&Path, Leftover)], on: bool) {
        let Some(path) = self.path.clone() else { return };
        let mut changed = false;
        for (p, kind) in paths {
            let set = match kind {
                Leftover::Part => &mut self.parts,
                Leftover::Reserved => &mut self.reserved,
            };
            changed |= if on { set.insert(p.to_path_buf()) } else { set.remove(*p) };
        }
        if !changed {
            return;
        }
        if self.parts.is_empty() && self.reserved.is_empty() {
            let _ = std::fs::remove_file(&path);
            return;
        }
        let empty_at = |p: &Path| std::fs::symlink_metadata(p).is_ok_and(|m| m.len() == 0);
        if !on && !paths.iter().any(|(p, kind)| matches!(kind, Leftover::Reserved) && empty_at(p)) {
            return;
        }
        let file = LandingFile { parts: self.parts.iter().cloned().collect(), reserved: self.reserved.iter().cloned().collect() };
        if let Ok(json) = serde_json::to_vec(&file) {
            // On the disk before the rename: the list is what a power cut is cleaned up by.
            let tmp = path.with_extension("json.tmp");
            let written = File::create(&tmp).and_then(|mut f| {
                f.write_all(&json)?;
                f.sync_all()
            });
            if written.is_ok() {
                let _ = std::fs::rename(&tmp, &path);
            }
        }
    }
}

/// `name` in `dir`, or "name (2).ext" … if that is taken — the name and its
/// hidden part `.name.part` both made here, exclusively. A part already there
/// is somebody else's, whatever it holds: left alone, and that name with it.
pub(crate) fn reserve(dir: &Path, name: &str) -> std::io::Result<Reserved> {
    for candidate in candidates(name) {
        let path = dir.join(&candidate);
        let Some((_, made)) = claim(&path, false)? else { continue };
        let part = dir.join(format!(".{candidate}.part"));
        match claim(&part, true) {
            Ok(Some((out, _))) => {
                landing().change(&[(&path, Leftover::Reserved), (&part, Leftover::Part)], true);
                return Ok(Reserved { final_path: path, part, wanted: name.to_string(), made, out });
            }
            Ok(None) => let_go(&path, made, Leftover::Reserved),
            Err(e) => {
                let_go(&path, made, Leftover::Reserved);
                return Err(e);
            }
        }
    }
    Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "no free name"))
}

/// Whether `need` bytes fit where `free` are left with `floor` still free:
/// said before anything is written.
pub(crate) fn room(need: u64, free: Option<u64>, floor: u64) -> bool {
    match free {
        Some(free) => need.saturating_add(floor) <= free,
        None => true,
    }
}

/// What a share's files say they come to: never more than there is to count.
pub(crate) fn total_size(sizes: impl IntoIterator<Item = Option<u64>>) -> u64 {
    sizes.into_iter().flatten().fold(0u64, |a, b| a.saturating_add(b))
}

/// Marked as downloaded, as a browser marks a file: the system's own caution
/// applies to it as to anything else that came from outside. Never the executable bit.
pub(crate) fn mark_downloaded(path: &Path) {
    #[cfg(target_os = "macos")]
    {
        use std::os::unix::ffi::OsStrExt;
        extern "C" {
            fn setxattr(
                path: *const std::os::raw::c_char,
                name: *const std::os::raw::c_char,
                value: *const std::os::raw::c_void,
                size: usize,
                position: u32,
                options: std::os::raw::c_int,
            ) -> std::os::raw::c_int;
        }
        let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let value = format!("0081;{secs:08x};Open Android DeX;");
        if let Ok(p) = std::ffi::CString::new(path.as_os_str().as_bytes()) {
            let name = c"com.apple.quarantine";
            let r = unsafe { setxattr(p.as_ptr(), name.as_ptr(), value.as_ptr().cast(), value.len(), 0, 0) };
            if r != 0 {
                log::warn!("send: {} could not be marked as downloaded: {}", path.display(), std::io::Error::last_os_error());
            }
        }
    }
    #[cfg(windows)]
    {
        // The stream of the name the file has on disk: Windows drops a trailing dot or space from a
        // file's name, but not from the middle of "name.:Zone.Identifier", which would be another file.
        let real = path.with_file_name(shared::windows_keeps(&path.file_name().unwrap_or_default().to_string_lossy()));
        let mut stream = real.as_os_str().to_owned();
        stream.push(":Zone.Identifier");
        if let Err(e) = std::fs::write(PathBuf::from(stream), b"[ZoneTransfer]\r\nZoneId=3\r\n") {
            log::warn!("send: {} could not be marked as downloaded: {e}", real.display());
        }
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    let _ = path;
}

/// Where arrivals land: Downloads (a debug build's tests may say elsewhere).
fn destination(app: &AppHandle) -> Option<PathBuf> {
    if let Some(d) = super::test_override("OADX_SEND_DIR") {
        return Some(PathBuf::from(d));
    }
    app.path().download_dir().ok().filter(|d| d.is_dir()).or_else(shared::downloads)
}

// ── what arrived: the short list ──

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Recent {
    pub id: u64,
    /// When, in ms since the epoch.
    pub at: i64,
    pub phone: String,
    pub phone_name: String,
    /// `receiving`, `files`, `failed`, `link` or `text`.
    pub kind: String,
    pub title: String,
    /// Where files landed.
    #[serde(default)]
    pub paths: Vec<String>,
    #[serde(default)]
    pub url: Option<String>,
    /// Text: for the page, never written to disk (only files and links are, [`save_recent`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// How far, while it comes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pct: Option<u64>,
    /// The share it is, while it comes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub share: Option<u64>,
}

fn recent() -> MutexGuard<'static, Vec<Recent>> {
    static R: OnceLock<Mutex<Vec<Recent>>> = OnceLock::new();
    R.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// The first id of a run: past every id kept, and past every earlier run's —
/// the time in ms, so a notification left from yesterday never names today's file.
pub(crate) fn first_id(top_kept: u64, now_ms: i64) -> u64 {
    (top_kept + 1).max(u64::try_from(now_ms).unwrap_or(0))
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn recent_path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_data_dir().ok().map(|d| d.join("send-recent.json"))
}

pub(crate) fn load_recent(app: &AppHandle) {
    let list: Vec<Recent> = recent_path(app)
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let top = list.iter().map(|r| r.id).max().unwrap_or(0);
    NEXT_ID.store(first_id(top, now_ms()), Ordering::SeqCst);
    *recent() = list.into_iter().filter(|r| r.kind == "files" || r.kind == "link").collect();
}

fn save_recent(app: &AppHandle) {
    let kept: Vec<Recent> = recent().iter().filter(|r| r.kind == "files" || r.kind == "link").cloned().collect();
    let Some(path) = recent_path(app) else { return };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_vec_pretty(&kept) {
        let tmp = path.with_extension("json.tmp");
        if std::fs::write(&tmp, json).is_ok() {
            let _ = std::fs::rename(&tmp, &path);
        }
    }
}

/// `r` new or changed in the list, the page told.
fn remember(app: &AppHandle, r: Recent, system: Option<bool>) {
    {
        let mut list = recent();
        list.retain(|x| x.id != r.id);
        list.insert(0, r.clone());
        list.truncate(RECENT_KEPT);
    }
    if r.kind != "receiving" {
        save_recent(app);
    }
    let _ = app.emit("send:arrived", json!({"item": r, "system": system}));
}

/// The arrivals, newest first.
#[tauri::command]
pub fn send_recent() -> Vec<Recent> {
    recent().clone()
}

/// The list emptied.
#[tauri::command(async)]
pub fn send_clear(app: AppHandle) {
    recent().retain(|r| r.kind == "receiving");
    save_recent(&app);
    let _ = app.emit("send:arrived", json!({"cleared": true}));
}

/// An arrival's Show, Open, Copy link — by its id, never a path from the page — or ✕ while it comes.
#[tauri::command(async)]
pub fn send_act(app: AppHandle, id: u64, act: String) -> Result<(), String> {
    let r = recent().iter().find(|r| r.id == id).cloned().ok_or("That is no longer in the list.")?;
    act_on(&app, &r, &act)
}

fn act_on(app: &AppHandle, r: &Recent, act: &str) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    let first = r.paths.first().map(PathBuf::from);
    match act {
        "show" => {
            let p = first.filter(|p| p.exists()).ok_or("It isn't there any more.")?;
            tauri_plugin_opener::reveal_item_in_dir(&p).map_err(|e| e.to_string())
        }
        "open" => {
            if let Some(url) = &r.url {
                return app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string());
            }
            let p = first.filter(|p| p.exists()).ok_or("It isn't there any more.")?;
            app.opener().open_path(p.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
        }
        "copy" => {
            let text = r.url.clone().or_else(|| r.text.clone()).ok_or("There is nothing to copy.")?;
            if apps::from_phone_clipboard(app, &r.phone, &text) {
                Ok(())
            } else {
                Err(format!("It couldn't be put on {}'s clipboard.", words::this_computer()))
            }
        }
        "stop" => {
            let share = r.share.ok_or("It has arrived already.")?;
            if let Some(f) = flights().get(&(r.phone.clone(), share)).cloned() {
                f.end(End::Stopped);
            }
            Ok(())
        }
        "forget" => {
            recent().retain(|x| x.id != r.id);
            save_recent(app);
            let _ = app.emit("send:arrived", json!({"cleared": false}));
            Ok(())
        }
        _ => Err(format!("no such action: {act}")),
    }
}

/// A click on one of these notifications: its body, or one of its buttons.
pub(crate) fn toast_clicked(app: &AppHandle, id: &str, button: Option<i64>) {
    let Some(n) = id.strip_prefix("send-").and_then(|n| n.parse::<u64>().ok()) else { return };
    let Some(r) = recent().iter().find(|r| r.id == n).cloned() else {
        super::show_window(app);
        return;
    };
    let picture = r.paths.len() == 1 && r.paths[0].rsplit('.').next().is_some_and(is_picture_ext);
    let act = match (button, r.kind.as_str()) {
        (Some(0), _) => "show",
        (Some(1), _) => "open",
        (Some(2), _) => "copy",
        (None, "files") if picture => "open",
        (None, "files") => "show",
        (None, "link") => "open",
        _ => {
            super::show_window(app);
            return;
        }
    };
    if let Err(e) = act_on(app, &r, act) {
        log::warn!("send: {act} from a notification: {e}");
    }
}

pub(crate) fn is_picture_ext(e: &str) -> bool {
    matches!(e.to_ascii_lowercase().as_str(), "jpg" | "jpeg" | "png" | "gif" | "heic" | "heif" | "webp" | "bmp" | "avif" | "dng" | "tif" | "tiff")
}

/// One of this computer's notifications, else the page's own toast — or, past
/// [`NOTICES_PER_MINUTE`], neither: it is listed on the Photos tab.
fn tell(app: &AppHandle, r: Recent, said: &words::Said, picture: Option<PathBuf>, buttons: Vec<(String, i64)>) {
    if !caps().notices.admit(Instant::now(), NOTICES_PER_MINUTE, MINUTE) {
        log::info!("send [{}] past {NOTICES_PER_MINUTE} notifications a minute: listed only", r.phone);
        remember(app, r, None);
        return;
    }
    let id = format!("send-{}", r.id);
    let system = crate::notifications::own_toast(&id, &r.phone, &said.title, &said.body, picture, buttons);
    remember(app, r, Some(system));
}

/// A share turned away for a cap: the phone's card says this computer is busy, and this computer says so once.
fn turn_away(app: &AppHandle, link: &Link, share: u64, total: usize, why: words::Busy) {
    log::warn!("send [{}] share {share} turned away: {why:?}", link.serial);
    done(link, share, false, "", &[], total, "busy");
    let once = match why {
        words::Busy::Shares => caps().shares.entry(link.phone.clone()).or_default().tell_once(),
    };
    if once {
        let said = words::busy(why, &super::phone_name(&link.phone));
        let id = format!("send-busy-{}", NEXT_ID.fetch_add(1, Ordering::SeqCst));
        let system = crate::notifications::own_toast(&id, &link.phone, &said.title, &said.body, None, Vec::new());
        if !system {
            let _ = app.emit("send:arrived", json!({"item": {"kind": "failed", "title": said.title}, "system": false}));
        }
    }
}

// ── a share, landing ──

/// How a file on its way ended, if it did not land.
#[derive(Clone, Copy, Debug, PartialEq)]
enum End {
    /// ✕ on this computer.
    Stopped,
    /// Taken back on the phone.
    Cancelled,
    /// The link went.
    Lost,
}

/// A share on its way: what may end it.
struct Flight {
    ended: Mutex<Option<End>>,
    stream: Mutex<Option<TcpStream>>,
}

impl Flight {
    fn end(&self, why: End) {
        let mut e = self.ended.lock().unwrap();
        if e.is_none() {
            *e = Some(why);
        }
        if let Some(s) = self.stream.lock().unwrap().as_ref() {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
    }

    fn ended(&self) -> Option<End> {
        *self.ended.lock().unwrap()
    }
}

/// What is on its way, by the phone and its share's number.
type Flights = HashMap<(String, u64), Arc<Flight>>;

fn flights() -> MutexGuard<'static, Flights> {
    static F: OnceLock<Mutex<Flights>> = OnceLock::new();
    F.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

/// The phone took a share back: whatever of it is on its way here goes, and nothing is said.
pub(crate) fn cancelled(phone: &str, share: u64) {
    if let Some(f) = flights().get(&(phone.to_string(), share)).cloned() {
        f.end(End::Cancelled);
    }
}

/// The link to `phone` went: what was on its way did not arrive.
pub(crate) fn link_lost(_app: &AppHandle, phone: &str) {
    let ours: Vec<Arc<Flight>> = flights().iter().filter(|((p, _), _)| p == phone).map(|(_, f)| f.clone()).collect();
    for f in ours {
        f.end(End::Lost);
    }
}

/// This app is exiting: everything on its way stops, its part and reservation removed.
pub(crate) fn stop_all() {
    let all: Vec<Arc<Flight>> = flights().values().cloned().collect();
    for f in &all {
        f.end(End::Stopped);
    }
    let until = Instant::now() + Duration::from_secs(2);
    while !flights().is_empty() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// Something was shared on the phone.
pub(crate) fn shared(app: &AppHandle, link: &Arc<Link>, v: &Value) {
    let share = v["share"].as_u64().unwrap_or(0);
    let total = v["total"].as_u64().map(|t| t as usize).unwrap_or_else(|| v["items"].as_array().map_or(1, |a| a.len()));
    if !caps().shares.entry(link.phone.clone()).or_default().admit(Instant::now(), SHARES_PER_MINUTE, MINUTE) {
        turn_away(app, link, share, total, words::Busy::Shares);
        return;
    }
    match v["kind"].as_str() {
        Some("text") => text(app, link, share, v["text"].as_str().unwrap_or("")),
        Some("files") => {
            // In flight from now: Cancel, a link gone and this app exiting reach a share that waits its turn too.
            let flight = Arc::new(Flight { ended: Mutex::new(None), stream: Mutex::new(None) });
            let key = (link.phone.clone(), share);
            flights().insert(key.clone(), flight.clone());
            let (app, link, v) = (app.clone(), link.clone(), v.clone());
            let _ = std::thread::Builder::new().name("send-receive".into()).spawn(move || {
                let _gone = InFlight(key);
                let one = match Receiving::try_take() {
                    Some(one) => one,
                    None => {
                        log::info!("send [{}] share {share} waits: {TRANSFERS_AT_ONCE} are coming in", link.serial);
                        let _ = link.send(&json!({"t": "sendWait", "share": share}));
                        match Receiving::wait(&flight) {
                            Some(one) => one,
                            None => {
                                log::info!("send [{}] share {share} ended while it waited: {:?}", link.serial, flight.ended());
                                return;
                            }
                        }
                    }
                };
                let _one = one;
                receive(&app, &link, share, &v, &flight)
            });
        }
        _ => {}
    }
}

fn done(link: &Link, share: u64, ok: bool, did: &str, landed: &[String], failed: usize, message: &str) {
    let _ = link.send(&json!({"t": "sendDone", "share": share, "ok": ok, "did": did, "landed": landed, "failed": failed, "message": message}));
}

fn text(app: &AppHandle, link: &Link, share: u64, text: &str) {
    use tauri_plugin_opener::OpenerExt;
    let phone_name = super::phone_name(&link.phone);
    let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
    if let Some(url) = link_in(text) {
        let open = super::settings().open_links && pacer().may_open(Instant::now());
        let opened = open && app.opener().open_url(&url, None::<&str>).is_ok();
        let did = if opened { "opened" } else { "offered" };
        // The words that came with it — a message, a title — on the clipboard, all of it, link and all.
        let copied = words_beside(text, &url) && apps::from_phone_clipboard(app, &link.phone, text);
        log::info!("send [{}] a link from the phone: {did}{}", link.serial, if copied { ", its text on the clipboard" } else { "" });
        done(link, share, true, did, &[], 0, "");
        let said = words::link(&url, opened, copied, &phone_name);
        let r = Recent {
            id,
            at: now_ms(),
            phone: link.phone.clone(),
            phone_name: phone_name.clone(),
            kind: "link".into(),
            title: said.title.clone(),
            paths: Vec::new(),
            url: Some(url),
            text: None,
            pct: None,
            share: None,
        };
        let buttons = if opened {
            vec![("Copy link".to_string(), 2)]
        } else {
            vec![("Open".to_string(), 1), ("Copy link".to_string(), 2)]
        };
        tell(app, r, &said, None, buttons);
        return;
    }
    if !apps::from_phone_clipboard(app, &link.phone, text) {
        log::warn!("send [{}] text from the phone: the clipboard would not take it", link.serial);
        done(link, share, false, "clipboard", &[], 1, "clipboard");
        let said = words::not_copied(&phone_name);
        let r = failed_recent(id, link, &phone_name, &said);
        tell(app, r, &said, None, Vec::new());
        return;
    }
    log::info!("send [{}] text from the phone: {} characters on the clipboard", link.serial, text.chars().count());
    done(link, share, true, "clipboard", &[], 0, "");
    let said = words::text(text, &phone_name);
    let r = Recent {
        id,
        at: now_ms(),
        phone: link.phone.clone(),
        phone_name,
        kind: "text".into(),
        title: said.title.clone(),
        paths: Vec::new(),
        url: None,
        text: Some(text.to_string()),
        pct: None,
        share: None,
    };
    tell(app, r, &said, None, Vec::new());
}

/// One item of a share, as the phone describes it.
struct Incoming {
    name: String,
    size: Option<u64>,
}

fn receive(app: &AppHandle, link: &Arc<Link>, share: u64, v: &Value, flight: &Arc<Flight>) {
    let t0 = Instant::now();
    let offer = v["offer"].as_u64().unwrap_or(0);
    let items: Vec<Incoming> = v["items"]
        .as_array()
        .map(|a| {
            a.iter()
                .map(|i| Incoming {
                    name: shared::arrival_name(i["name"].as_str().unwrap_or("file")),
                    size: i["size"].as_i64().and_then(|s| u64::try_from(s).ok()),
                })
                .collect()
        })
        .unwrap_or_default();
    let unread = v["unread"].as_u64().unwrap_or(0) as usize;
    let total = v["total"].as_u64().map(|t| t as usize).unwrap_or(items.len() + unread).max(items.len());
    let first = items.first().map(|i| i.name.clone()).unwrap_or_else(|| "file".into());
    let phone_name = super::phone_name(&link.phone);
    let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
    let fail = |message: &str| {
        log::warn!("send [{}] share {share} did not arrive: {message}", link.serial);
        done(link, share, false, "saved", &[], total, message);
        let said = words::none(&first, total, message);
        let r = failed_recent(id, link, &phone_name, &said);
        tell(app, r, &said, None, Vec::new());
    };
    let Some(dir) = destination(app) else {
        fail("folder");
        return;
    };
    // The sizes the share says are a hint (a pipe may say 0, or too little): they are checked against the
    // floor first, and the disk again as the bytes come.
    let need = total_size(items.iter().map(|i| i.size));
    let floor = arrival_floor();
    if !room(need, crate::files::free_bytes(&dir), floor) {
        fail("space");
        return;
    }
    let mut landed: Vec<PathBuf> = Vec::new();
    let mut why = String::new();
    let mut shown = false;
    let mut bytes_in = 0u64;
    for (i, item) in items.iter().enumerate() {
        if flight.ended().is_some() {
            break;
        }
        let r = match reserve(&dir, &item.name) {
            Ok(r) => r,
            Err(e) => {
                log::warn!("send [{}] {} could not be saved: {e}", link.serial, item.name);
                why = if e.kind() == std::io::ErrorKind::StorageFull { "space".into() } else { String::new() };
                continue;
            }
        };
        let mut last_pct = None;
        let got = take(link, offer, i, &r, flight, floor, |bytes| {
            if t0.elapsed() < QUIET_FOR {
                return;
            }
            let pct = item.size.filter(|s| *s > 0).map(|s| bytes.min(s) * 100 / s);
            if shown && pct == last_pct {
                return;
            }
            last_pct = pct;
            shown = true;
            let text = words::receiving(&item.name, i, items.len(), pct);
            remember(
                app,
                Recent {
                    id,
                    at: now_ms(),
                    phone: link.phone.clone(),
                    phone_name: phone_name.clone(),
                    kind: "receiving".into(),
                    title: text,
                    paths: Vec::new(),
                    url: None,
                    text: None,
                    pct,
                    share: Some(share),
                },
                None,
            );
        });
        let result = match got {
            Ok(n) => r.land().map(|p| (n, p)).map_err(|_| String::new()),
            Err(e) => {
                r.abandon();
                Err(e)
            }
        };
        match result {
            Ok((n, path)) => {
                mark_downloaded(&path);
                bytes_in += n;
                log::info!(
                    "send [{}] {} landed in Downloads ({n} bytes in {} ms)",
                    link.serial,
                    path.file_name().unwrap_or_default().to_string_lossy(),
                    t0.elapsed().as_millis()
                );
                landed.push(path);
            }
            Err(e) => {
                if why.is_empty() || flight.ended().is_some() {
                    why = e;
                }
            }
        }
    }
    flights().remove(&(link.phone.clone(), share));
    let names: Vec<String> = landed.iter().map(|p| p.file_name().unwrap_or_default().to_string_lossy().into_owned()).collect();
    match flight.ended() {
        Some(End::Cancelled) => {
            log::info!("send [{}] share {share} taken back on the phone; {} of it had landed", link.serial, landed.len());
            if landed.is_empty() {
                recent().retain(|r| r.id != id);
                let _ = app.emit("send:arrived", json!({"cleared": false}));
                return;
            }
        }
        Some(End::Stopped) => {
            log::info!("send [{}] share {share} stopped here", link.serial);
            done(link, share, false, "saved", &names, total - landed.len(), "stopped");
            if landed.is_empty() {
                recent().retain(|r| r.id != id);
                let _ = app.emit("send:arrived", json!({"cleared": false}));
                return;
            }
        }
        Some(End::Lost) => why = "lost".into(),
        None => {
            done(link, share, !landed.is_empty(), "saved", &names, total - landed.len(), if landed.is_empty() { &why } else { "" });
        }
    }
    if landed.is_empty() {
        let said = words::none(&first, total, &why);
        log::warn!("send [{}] share {share} did not arrive: {}", link.serial, said.body);
        let r = failed_recent(id, link, &phone_name, &said);
        tell(app, r, &said, None, Vec::new());
        return;
    }
    log::info!("send [{}] share {share}: {} of {total} files, {bytes_in} bytes in {} ms", link.serial, landed.len(), t0.elapsed().as_millis());
    let said = words::files(&names[0], landed.len(), total);
    let one_picture = landed.len() == 1 && names[0].rsplit('.').next().is_some_and(is_picture_ext);
    let picture = if one_picture { v["preview"].as_str().and_then(|b64| preview_file(app, id, b64)) } else { None };
    let mut buttons = vec![(words::show_in_folder().to_string(), 0)];
    if landed.len() == 1 && total == 1 {
        buttons.push(("Open".to_string(), 1));
    }
    let r = Recent {
        id,
        at: now_ms(),
        phone: link.phone.clone(),
        phone_name,
        kind: "files".into(),
        title: words::arrived(&names[0], landed.len()),
        paths: landed.iter().map(|p| p.to_string_lossy().into_owned()).collect(),
        url: None,
        text: None,
        pct: None,
        share: None,
    };
    tell(app, r, &said, picture, buttons);
}

fn failed_recent(id: u64, link: &Link, phone_name: &str, said: &words::Said) -> Recent {
    Recent {
        id,
        at: now_ms(),
        phone: link.phone.clone(),
        phone_name: phone_name.to_string(),
        kind: "failed".into(),
        title: said.title.clone(),
        paths: Vec::new(),
        url: None,
        text: None,
        pct: None,
        share: None,
    }
}

/// The phone's preview of a picture (512 px), as a file a notification shows.
fn preview_file(app: &AppHandle, id: u64, b64: &str) -> Option<PathBuf> {
    let bytes = super::photos::decode_base64(b64)?;
    let dir = app.path().app_cache_dir().ok()?.join("send").join("previews");
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join(format!("{id}.jpg"));
    std::fs::write(&path, bytes).ok()?;
    Some(path)
}

/// Why a copy stopped short.
#[derive(Debug)]
pub(crate) enum Cut {
    /// This disk would have gone under the floor.
    Floor,
    Read(std::io::Error),
    Write(std::io::Error),
}

/// `from` into `to`, asking `free` about the disk every [`FREE_EVERY_BYTES`]:
/// it stops when what is left would go under `floor` — whatever the file
/// said it is, which is a hint (a pipe may say 0, a file may still grow), and
/// however long a source goes on. How many came.
pub(crate) fn copy_watching(
    from: &mut impl Read,
    to: &mut impl Write,
    mut free: impl FnMut() -> Option<u64>,
    floor: u64,
    mut progress: impl FnMut(u64),
) -> Result<u64, Cut> {
    let mut buf = vec![0u8; 256 * 1024];
    let mut got = 0u64;
    let mut since = 0u64;
    loop {
        match from.read(&mut buf) {
            Ok(0) => return Ok(got),
            Ok(n) => {
                since += n as u64;
                if since >= FREE_EVERY_BYTES {
                    since = 0;
                    if free().is_some_and(|f| f.saturating_sub(n as u64) < floor) {
                        return Err(Cut::Floor);
                    }
                }
                to.write_all(&buf[..n]).map_err(Cut::Write)?;
                got += n as u64;
                progress(got);
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(Cut::Read(e)),
        }
    }
}

/// What an arrival leaves free (a debug build's tests may say otherwise: `OADX_SEND_FLOOR`).
fn arrival_floor() -> u64 {
    super::test_override("OADX_SEND_FLOOR").and_then(|v| v.parse().ok()).unwrap_or(ARRIVAL_FLOOR)
}

/// Item `i` of offer `offer` into `r`'s part, the file it made: its bytes, as
/// `get` gives them (AppOffers.java), as long as this disk keeps `floor` free.
/// Fewer bytes than the phone says the file itself has (`exact`) is a cut; a
/// size the sending app only said is a hint either way.
fn take(link: &Link, offer: u64, i: usize, r: &Reserved, flight: &Flight, floor: u64, progress: impl FnMut(u64)) -> Result<u64, String> {
    let mut s = shared::connect(&link.serial, &link.socket).map_err(|_| "lost".to_string())?;
    *flight.stream.lock().unwrap() = s.try_clone().ok();
    if flight.ended().is_some() {
        return Err("stopped".into());
    }
    s.write_all(&shared::json(&json!({"t": "get", "id": offer, "i": i}))).map_err(|_| "lost".to_string())?;
    let _ = s.set_read_timeout(Some(READ_TIMEOUT));
    let answer = match shared::read(&mut s) {
        Ok(Some(m)) if m.kind == shared::JSON => serde_json::from_slice::<Value>(&m.data).unwrap_or(Value::Null),
        _ => return Err(ended_or(flight, "lost")),
    };
    if answer["ok"].as_bool() != Some(true) {
        return Err("unreadable".into());
    }
    let size = answer["size"].as_i64().and_then(|s| u64::try_from(s).ok());
    // A phone from before `exact` gave only a file's own length.
    let exact = answer["exact"].as_bool().unwrap_or(true);
    let mut out = r.file();
    let dir = r.part.parent().unwrap_or(&r.part).to_path_buf();
    // Pieces, then the end mark: a stream that stops before it is a file cut short, whatever its size said.
    let mut s = crate::apps::shared::Pieces::new(s);
    let got = match copy_watching(&mut s, &mut out, || crate::files::free_bytes(&dir), floor, progress) {
        Ok(n) => n,
        Err(Cut::Floor) => {
            log::warn!("send [{}] item {i}: this disk would go under {floor} bytes free: stopped", link.serial);
            return Err("space".into());
        }
        Err(Cut::Write(e)) => {
            return Err(if super::photos::is_disk_full(&e) {
                "space".into()
            } else {
                String::new()
            })
        }
        Err(Cut::Read(e)) => {
            log::debug!("send [{}] item {i}: the phone's stream ended: {e}", link.serial);
            return Err(ended_or(flight, "lost"));
        }
    };
    if out.sync_all().is_err() {
        return Err(String::new());
    }
    if let Some(e) = flight.ended() {
        return Err(end_word(e).into());
    }
    if exact && size.is_some_and(|s| got < s) {
        return Err(ended_or(flight, "lost"));
    }
    Ok(got)
}

fn ended_or(flight: &Flight, otherwise: &str) -> String {
    flight.ended().map(|e| end_word(e).to_string()).unwrap_or_else(|| otherwise.to_string())
}

fn end_word(e: End) -> &'static str {
    match e {
        End::Stopped => "stopped",
        End::Cancelled => "cancelled",
        End::Lost => "lost",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("oadx-send-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn a_link_is_one_http_url() {
        assert_eq!(link_in("https://example.com/a?b=1").as_deref(), Some("https://example.com/a?b=1"));
        assert_eq!(link_in("  http://example.com  ").as_deref(), Some("http://example.com"));
        assert_eq!(link_in("A page title\nhttps://example.com/page").as_deref(), Some("https://example.com/page"));
        assert_eq!(link_in("Look: <https://example.com/x>").as_deref(), Some("https://example.com/x"));
        assert_eq!(link_in("https://a.example https://b.example"), None);
        // Words beside it go on the clipboard as it opens; what only wraps it is no words.
        assert!(words_beside("Meet here at 5 https://maps.example/x", "https://maps.example/x"));
        assert!(words_beside("A page title\nhttps://example.com/page", "https://example.com/page"));
        assert!(!words_beside("  https://example.com  ", "https://example.com"));
        assert!(!words_beside("<https://example.com/x>.", "https://example.com/x"));
        assert!(!words_beside("(https://example.com/x)", "https://example.com/x"));
        assert_eq!(link_in("javascript:alert(1)"), None);
        assert_eq!(link_in("file:///etc/passwd"), None);
        assert_eq!(link_in("tel:+441234"), None);
        assert_eq!(link_in("myapp://open"), None);
        assert_eq!(link_in("https://"), None);
        assert_eq!(link_in("just some words"), None);
        let long = format!("{} https://example.com", "w".repeat(301));
        assert_eq!(link_in(&long), None);
        let title = format!("{} https://example.com", "w".repeat(299));
        assert_eq!(link_in(&title).as_deref(), Some("https://example.com"));
    }

    #[test]
    fn a_link_keeps_its_brackets_and_loses_its_full_stop() {
        let rust = "https://en.wikipedia.org/wiki/Rust_(programming_language)";
        assert_eq!(link_in(rust).as_deref(), Some(rust));
        assert_eq!(link_in(&format!("({rust})")).as_deref(), Some(rust));
        assert_eq!(link_in(&format!("See {rust}.")).as_deref(), Some(rust));
        assert_eq!(link_in(&format!("“{rust}”")).as_deref(), Some(rust));
        assert_eq!(link_in("See https://example.com/a, and more").as_deref(), Some("https://example.com/a"));
        assert_eq!(link_in("https://example.com/what?!").as_deref(), Some("https://example.com/what"));
        assert_eq!(link_in("'https://example.com/x'").as_deref(), Some("https://example.com/x"));
        assert_eq!(link_in("\"https://example.com/q\"").as_deref(), Some("https://example.com/q"));
        assert_eq!(link_in("[https://example.com/list]").as_deref(), Some("https://example.com/list"));
        assert_eq!(link_in("https://example.com/a_(b)_c).").as_deref(), Some("https://example.com/a_(b)_c"));
        assert_eq!(url_of_word("https://example.com/it's"), "https://example.com/it's");
        assert_eq!(url_of_word("https://example.com/x?a=1;"), "https://example.com/x?a=1");
        assert_eq!(url_of_word("...."), "");
    }

    #[test]
    fn caps_count_a_minute_and_say_so_once() {
        let mut w = Window::default();
        let t = Instant::now();
        for i in 0..SHARES_PER_MINUTE {
            assert!(w.admit(t + Duration::from_millis(i as u64), SHARES_PER_MINUTE, MINUTE), "{i}");
        }
        assert!(!w.admit(t + Duration::from_secs(1), SHARES_PER_MINUTE, MINUTE));
        assert!(w.tell_once());
        assert!(!w.admit(t + Duration::from_secs(30), SHARES_PER_MINUTE, MINUTE));
        assert!(!w.tell_once(), "said once, not again");
        // A minute after the first, one more fits; and the next time it is full, it is said again.
        assert!(w.admit(t + MINUTE, SHARES_PER_MINUTE, MINUTE));
        assert!(!w.admit(t + MINUTE, SHARES_PER_MINUTE, MINUTE));
        assert!(w.tell_once());
        // Three at once, given back however each ends; a fourth waits its turn, or its end.
        let held: Vec<Receiving> = (0..TRANSFERS_AT_ONCE).filter_map(|_| Receiving::try_take()).collect();
        assert_eq!(held.len(), TRANSFERS_AT_ONCE);
        assert!(Receiving::try_take().is_none());
        let ended = Flight { ended: Mutex::new(Some(End::Cancelled)), stream: Mutex::new(None) };
        assert!(Receiving::wait(&ended).is_none(), "taken back while it waited");
        let waiting = std::thread::spawn(|| {
            let f = Flight { ended: Mutex::new(None), stream: Mutex::new(None) };
            let t = Instant::now();
            Receiving::wait(&f).map(|r| (r, t.elapsed()))
        });
        std::thread::sleep(Duration::from_millis(300));
        drop(held);
        let (fourth, waited) = waiting.join().unwrap().expect("a slot once one was given back");
        assert!(waited >= Duration::from_millis(250), "{waited:?}");
        drop(fourth);
        assert!(Receiving::try_take().is_some());
    }

    #[test]
    fn sizes_add_up_without_wrapping() {
        assert_eq!(total_size([Some(1), None, Some(2)]), 3);
        assert_eq!(total_size([Some(u64::MAX), Some(5)]), u64::MAX);
        assert!(!room(total_size([Some(u64::MAX), Some(u64::MAX)]), Some(1 << 40), 0));
    }

    #[test]
    fn ids_never_come_round_again() {
        let now = 1_794_436_573_000i64;
        assert_eq!(first_id(0, now), now as u64);
        // A list kept from a run with a clock ahead: past that, still.
        assert_eq!(first_id(now as u64 + 50, now), now as u64 + 51);
        assert_eq!(first_id(7, -1), 8);
    }

    #[test]
    fn a_copy_takes_what_comes_and_stops_at_the_floor() {
        use std::cell::Cell;
        let mb = 1024 * 1024u64;
        // Whatever the file said, all of it comes while the disk has room; the disk is asked every 64 MB.
        let asked = Cell::new(0);
        let n = copy_watching(
            &mut std::io::repeat(7).take(200 * mb),
            &mut std::io::sink(),
            || {
                asked.set(asked.get() + 1);
                Some(100_000 * mb)
            },
            500_000_000,
            |_| {},
        );
        assert_eq!(n.unwrap(), 200 * mb);
        assert_eq!(asked.get(), 3);
        // A source that goes on and on: stopped once the disk would go under the floor (checked every 64 MB).
        let wrote = Cell::new(0u64);
        let r = copy_watching(
            &mut std::io::repeat(1),
            &mut std::io::sink(),
            || Some(1_000_000_000u64.saturating_sub(wrote.get())),
            500_000_000,
            |got| wrote.set(got),
        );
        assert!(matches!(r, Err(Cut::Floor)));
        assert!(wrote.get() > 430_000_000 && wrote.get() < 570_000_000, "{}", wrote.get());
        // Nothing to say about the disk: nothing stops it but the end.
        let small = vec![1u8; 1000];
        assert_eq!(copy_watching(&mut &small[..], &mut std::io::sink(), || None, 1 << 40, |_| {}).unwrap(), 1000);
    }

    #[test]
    fn what_a_crash_left_is_removed_and_nothing_else() {
        let d = scratch("landing");
        let list = d.join("send-landing.json");
        // Its own list, not the app's: other tests reserve names at the same time.
        let mut l = Landing { path: Some(list.clone()), ..Default::default() };
        let (a, a_part) = (d.join("a.jpg"), d.join(".a.jpg.part"));
        std::fs::write(&a, b"").unwrap();
        std::fs::write(&a_part, b"half").unwrap();
        l.mark(&a, true, Leftover::Reserved);
        l.mark(&a_part, true, Leftover::Part);
        // b.jpg landed: no longer listed.
        let (b, b_part) = (d.join("b.jpg"), d.join(".b.jpg.part"));
        l.mark(&b, true, Leftover::Reserved);
        l.mark(&b_part, true, Leftover::Part);
        std::fs::write(&b, b"whole").unwrap();
        l.mark(&b_part, false, Leftover::Part);
        l.mark(&b, false, Leftover::Reserved);
        // c.jpg's reservation filled by something by then: not removed.
        let c = d.join("c.jpg");
        std::fs::write(&c, b"filled").unwrap();
        l.mark(&c, true, Leftover::Reserved);
        // Someone's own empty file and own hidden part, never listed: left alone.
        std::fs::write(d.join("theirs.txt"), b"").unwrap();
        std::fs::write(d.join(".theirs.txt.part"), b"x").unwrap();
        assert!(list.exists());
        // The app "crashes" here. The next start sweeps what is listed.
        assert_eq!(sweep(&list), 2);
        assert!(!a.exists() && !a_part.exists());
        assert_eq!(std::fs::read(&b).unwrap(), b"whole");
        assert_eq!(std::fs::read(&c).unwrap(), b"filled");
        assert!(d.join("theirs.txt").exists() && d.join(".theirs.txt.part").exists());
        assert!(!list.exists());
        assert_eq!(sweep(&list), 0);
        // Nothing in flight: no list at all.
        let mut l = Landing { path: Some(list.clone()), ..Default::default() };
        l.mark(&a, true, Leftover::Part);
        l.mark(&a, false, Leftover::Part);
        assert!(!list.exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_list_is_written_for_what_comes_and_lazily_for_what_is_done() {
        let d = scratch("lazy");
        let list = d.join("send-landing.json");
        let listed = || serde_json::from_slice::<LandingFile>(&std::fs::read(&list).unwrap()).unwrap();
        let mut l = Landing { path: Some(list.clone()), ..Default::default() };
        // A name and its part, listed in one write.
        let (name, part) = (d.join("a.jpg"), d.join(".a.jpg.part"));
        l.change(&[(&name, Leftover::Reserved), (&part, Leftover::Part)], true);
        assert_eq!((listed().reserved, listed().parts), (vec![name.clone()], vec![part.clone()]));
        // Another in flight; the first lands (no longer empty there): not written for that alone…
        let other = d.join(".b.jpg.part");
        l.mark(&other, true, Leftover::Part);
        std::fs::write(&name, b"landed").unwrap();
        l.mark(&part, false, Leftover::Part);
        l.mark(&name, false, Leftover::Reserved);
        assert_eq!(listed().reserved, vec![name.clone()], "still on the disk, and harmless: it is not empty");
        // …but with the next thing listed.
        let next = d.join(".c.jpg.part");
        l.mark(&next, true, Leftover::Part);
        assert!(listed().reserved.is_empty());
        assert_eq!(listed().parts, vec![other.clone(), next.clone()]);
        // An empty file at a reservation's name (an empty file that arrived) is written off at once.
        let empty = d.join("empty.txt");
        l.mark(&empty, true, Leftover::Reserved);
        std::fs::write(&empty, b"").unwrap();
        l.mark(&empty, false, Leftover::Reserved);
        assert!(listed().reserved.is_empty());
        // Nothing left in flight: the list goes at once.
        l.mark(&other, false, Leftover::Part);
        l.mark(&next, false, Leftover::Part);
        assert!(!list.exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn links_open_at_most_once_in_three_seconds() {
        let mut p = Pacer::default();
        let t = Instant::now();
        assert!(p.may_open(t));
        assert!(!p.may_open(t + Duration::from_millis(500)));
        assert!(!p.may_open(t + Duration::from_millis(2999)));
        assert!(p.may_open(t + Duration::from_millis(3000)));
        assert!(!p.may_open(t + Duration::from_millis(3100)));
    }

    #[test]
    fn a_name_is_reserved_before_a_byte_lands() {
        let d = scratch("reserve");
        let a = reserve(&d, "a.jpg").unwrap();
        let b = reserve(&d, "a.jpg").unwrap();
        assert_eq!(a.final_path, d.join("a.jpg"));
        assert_eq!(b.final_path, d.join("a (2).jpg"));
        assert_eq!(a.part, d.join(".a.jpg.part"));
        assert_eq!(b.part, d.join(".a (2).jpg.part"));
        // Another program's file of that name is never written over.
        std::fs::write(d.join("c.txt"), b"theirs").unwrap();
        let c = reserve(&d, "c.txt").unwrap();
        assert_eq!(c.final_path, d.join("c (2).txt"));
        // The part is made with the name, and the bytes go into the file made.
        assert_eq!(std::fs::read(&b.part).unwrap(), b"");
        b.file().write_all(b"second").unwrap();
        a.file().write_all(b"first").unwrap();
        assert_eq!(b.land().unwrap(), d.join("a (2).jpg"));
        assert_eq!(a.land().unwrap(), d.join("a.jpg"));
        assert_eq!(std::fs::read(d.join("a.jpg")).unwrap(), b"first");
        assert_eq!(std::fs::read(d.join("a (2).jpg")).unwrap(), b"second");
        assert_eq!(std::fs::read(d.join("c.txt")).unwrap(), b"theirs");
        assert!(!d.join(".a.jpg.part").exists());
        c.abandon();
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_failed_arrival_leaves_nothing() {
        let d = scratch("fail");
        let r = reserve(&d, "big.mp4").unwrap();
        r.file().write_all(b"half").unwrap();
        r.abandon();
        assert_eq!(std::fs::read_dir(&d).unwrap().count(), 0);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_part_already_there_is_left_alone_and_its_name_skipped() {
        let d = scratch("theirpart");
        std::fs::write(d.join(".photo.jpg.part"), b"someone else's half").unwrap();
        let r = reserve(&d, "photo.jpg").unwrap();
        assert_eq!(r.final_path, d.join("photo (2).jpg"));
        assert_eq!(r.part, d.join(".photo (2).jpg.part"));
        assert!(!d.join("photo.jpg").exists(), "the name given up is not left reserved");
        r.file().write_all(b"arrived").unwrap();
        assert_eq!(r.land().unwrap(), d.join("photo (2).jpg"));
        assert_eq!(std::fs::read(d.join("photo (2).jpg")).unwrap(), b"arrived");
        assert_eq!(std::fs::read(d.join(".photo.jpg.part")).unwrap(), b"someone else's half");
        assert!(!d.join(".photo (2).jpg.part").exists());
        // Never through a link left at a part's name either.
        #[cfg(unix)]
        {
            let target = d.join("target.txt");
            std::fs::write(&target, b"keep me").unwrap();
            std::os::unix::fs::symlink(&target, d.join(".x.txt.part")).unwrap();
            let r = reserve(&d, "x.txt").unwrap();
            assert_eq!(r.final_path, d.join("x (2).txt"));
            r.abandon();
            assert_eq!(std::fs::read(&target).unwrap(), b"keep me");
        }
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_reservation_filled_meanwhile_is_kept_and_the_arrival_lands_beside_it() {
        let d = scratch("filled");
        let r = reserve(&d, "a.jpg").unwrap();
        r.file().write_all(b"ours").unwrap();
        // Something writes into the empty file while the bytes come.
        std::fs::write(d.join("a.jpg"), b"someone's").unwrap();
        assert_eq!(r.land().unwrap(), d.join("a (2).jpg"));
        assert_eq!(std::fs::read(d.join("a.jpg")).unwrap(), b"someone's");
        assert_eq!(std::fs::read(d.join("a (2).jpg")).unwrap(), b"ours");
        assert!(!d.join(".a.jpg.part").exists());
        // Swapped for another empty file of the same name: not the one made, so not written over either.
        #[cfg(unix)]
        {
            let r = reserve(&d, "b.jpg").unwrap();
            r.file().write_all(b"ours too").unwrap();
            // Moved aside rather than removed: its inode is not free to be given to the new one.
            std::fs::rename(d.join("b.jpg"), d.join("b-moved.jpg")).unwrap();
            std::fs::write(d.join("b.jpg"), b"").unwrap();
            assert_eq!(r.land().unwrap(), d.join("b (2).jpg"));
            assert_eq!(std::fs::read(d.join("b.jpg")).unwrap(), b"");
            assert_eq!(std::fs::read(d.join("b (2).jpg")).unwrap(), b"ours too");
        }
        // Keep a copy's way: its own file moved over the reservation, the reservation's unused part gone.
        let (incoming, f) = part_in(&d, "oadx-incoming-7").unwrap();
        let (again, g) = part_in(&d, "oadx-incoming-7").unwrap();
        assert_eq!((incoming.clone(), again.clone()), (d.join(".oadx-incoming-7"), d.join(".oadx-incoming-7-2")));
        let_go_part(&again, close(g));
        assert!(!again.exists());
        (&f).write_all(b"picture").unwrap();
        let made = close(f);
        let r = reserve(&d, "c.jpg").unwrap();
        std::fs::write(d.join("c.jpg"), b"theirs").unwrap();
        assert_eq!(r.land_from(&incoming).unwrap(), d.join("c (2).jpg"));
        assert_eq!(std::fs::read(d.join("c.jpg")).unwrap(), b"theirs");
        assert_eq!(std::fs::read(d.join("c (2).jpg")).unwrap(), b"picture");
        assert!(!d.join(".c.jpg.part").exists() && !incoming.exists());
        let_go_part(&incoming, made);
        let _ = std::fs::remove_dir_all(&d);
    }

    /// Wherever the scratch folder is: run with TMPDIR on a FAT or exFAT
    /// volume, where a file is numbered anew by its first byte, it is the rule
    /// that what this app wrote is still its own to let go.
    #[test]
    fn what_was_written_is_still_ours_to_let_go() {
        let d = scratch("written");
        // An arrival given up half way.
        let r = reserve(&d, "big.mp4").unwrap();
        r.file().write_all(b"half of it").unwrap();
        r.abandon();
        // Keep a copy's picture cut short, and one adopted by its bytes: let go.
        for base in ["oadx-incoming-8", "oadx-incoming-9"] {
            let (incoming, f) = part_in(&d, base).unwrap();
            (&f).write_all(b"a picture, or half of one").unwrap();
            let made = close(f);
            let_go_part(&incoming, made);
        }
        assert_eq!(std::fs::read_dir(&d).unwrap().map(|e| e.unwrap().file_name()).collect::<Vec<_>>(), Vec::<std::ffi::OsString>::new());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_reservation_taken_away_meanwhile_is_made_again_and_the_arrival_keeps_its_name() {
        let d = scratch("takenaway");
        for (name, known) in [("video.mp4", false), ("clip.mp4", true)] {
            let mut r = reserve(&d, name).unwrap();
            if !known {
                // Known by no identity: as a file made anew at that name may be given the old one's (NTFS
                // within 15 s of the old one's end, ext4 and xfs at once), and so passes for it.
                r.made = Made(None);
            }
            r.file().write_all(b"the whole video").unwrap();
            // Someone deletes the empty file they see in Downloads while the bytes come.
            std::fs::remove_file(d.join(name)).unwrap();
            assert_eq!(r.land().unwrap(), d.join(name), "{name}");
            assert_eq!(std::fs::read(d.join(name)).unwrap(), b"the whole video");
            assert!(!d.join(format!(".{name}.part")).exists());
        }
        assert_eq!(std::fs::read_dir(&d).unwrap().count(), 2);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn giving_up_leaves_what_was_put_at_the_reserved_name() {
        let d = scratch("giveup");
        let r = reserve(&d, "report.pdf").unwrap();
        r.file().write_all(b"half").unwrap();
        std::fs::write(d.join("report.pdf"), b"someone's report").unwrap();
        r.abandon();
        assert_eq!(std::fs::read(d.join("report.pdf")).unwrap(), b"someone's report");
        assert!(!d.join(".report.pdf.part").exists());
        assert_eq!(std::fs::read_dir(&d).unwrap().count(), 1);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn too_little_room_is_said_before_anything_is_written() {
        assert!(room(10, Some(10), 0));
        assert!(!room(11, Some(10), 0));
        assert!(room(1 << 40, None, 0));
        assert!(room(0, Some(0), 0));
        // An arrival leaves 500 MB: 10 GB onto 10.2 GB free is refused, onto 10.6 GB it fits.
        let gb = 1_000_000_000u64;
        assert!(!room(10 * gb, Some(10 * gb + gb / 5), ARRIVAL_FLOOR));
        assert!(room(10 * gb, Some(10 * gb + gb * 6 / 10), ARRIVAL_FLOOR));
    }

    #[test]
    fn a_marked_file_is_still_its_bytes() {
        let d = scratch("mark");
        let p = d.join("x.jpg");
        std::fs::write(&p, b"jpeg").unwrap();
        mark_downloaded(&p);
        assert_eq!(std::fs::read(&p).unwrap(), b"jpeg");
        #[cfg(target_os = "macos")]
        {
            let out = std::process::Command::new("xattr").args(["-p", "com.apple.quarantine"]).arg(&p).output().unwrap();
            assert!(String::from_utf8_lossy(&out.stdout).contains("Open Android DeX"));
        }
        let _ = std::fs::remove_dir_all(&d);
    }
}
