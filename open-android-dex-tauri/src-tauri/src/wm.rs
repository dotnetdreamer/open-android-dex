//! PC-side client for the shell-uid window daemon (`openandroiddex-wmd`).
//!
//! The daemon runs at uid 2000 on the device and holds `MANAGE_ACTIVITY_TASKS`, which is
//! what lets anything enumerate, move, re-order or inset another app's task. It speaks a
//! line protocol on loopback TCP 7191; `adb forward` brings that port to the PC so the
//! host can drive windows directly instead of going through the launcher.
//!
//! Why this exists at all: every launcher->PC command today rides the `content query`
//! ContentProvider queue at roughly a second per round, because `content` boots a JVM per
//! invocation. The same work over this socket is a binder call behind a socket write.
//! Measured on SM-S938B: `applyTransaction(setBounds)` moves the real task leash in
//! 2.49 ms, and unlike `startNewTransition` it does not serialize behind other
//! transitions — so it is usable per drag frame.
//!
//! Everything here is best-effort. A missing daemon means `None`/`false`, never an error
//! the UI has to model: the desktop is fully usable without it, minus window chrome and
//! the fast command path.

use std::io::{BufRead, BufReader, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::Mutex;
use std::time::Duration;

/// Device-side port the daemon binds (loopback only — it speaks with shell authority).
pub const DEVICE_PORT: u16 = 7191;
/// Host-side port `adb forward` publishes it on.
pub const HOST_PORT: u16 = 7191;

const CONNECT_TIMEOUT: Duration = Duration::from_millis(500);
const IO_TIMEOUT: Duration = Duration::from_millis(1500);

/// One root task on the desktop display.
#[derive(Debug, Clone, serde::Serialize)]
pub struct Task {
    /// Position in the daemon's LIST, 0 = topmost.
    pub index: i32,
    pub task_id: i32,
    pub display_id: i32,
    /// WindowConfiguration windowing mode; 5 is freeform.
    pub windowing_mode: i32,
    pub visible: bool,
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    /// Top of the app's content. Greater than `top` when a caption strip is reserved.
    pub app_top: i32,
    pub package: String,
    pub activity: String,
}

impl Task {
    pub fn is_freeform(&self) -> bool {
        self.windowing_mode == 5
    }

    /// True once a caption strip has been reserved for this task.
    pub fn has_strip(&self) -> bool {
        self.app_top > self.top
    }
}

/// A pooled connection. Reconnects on demand; a dead daemon is not an error state.
#[derive(Default)]
pub struct WmClient {
    conn: Mutex<Option<Conn>>,
}

struct Conn {
    reader: BufReader<TcpStream>,
    writer: TcpStream,
}

impl WmClient {
    pub fn new() -> Self {
        Self::default()
    }

    /// Display id of the live desktop, or `None`.
    ///
    /// Resolved by the daemon from the task tree rather than by us from `dumpsys`:
    /// dead scrcpy sessions leave virtual displays behind that look identical to the live
    /// one, and the id changes every session.
    pub fn desktop_display(&self) -> Option<i32> {
        let reply = self.request("DESKTOP com.ccrstech.openandroiddex.launcher")?;
        let id: i32 = reply.strip_prefix("OK ")?.trim().parse().ok()?;
        (id >= 0).then_some(id)
    }

    /// Root tasks on `display`, topmost first.
    pub fn list(&self, display: i32) -> Vec<Task> {
        self.list_command(&format!("LIST {display}"))
    }

    /// Move a whole task to `display`.
    ///
    /// Used to take back a desktop window that the phone claimed. Best-effort like
    /// everything else here: an older daemon without the verb answers `ERR`, which reads
    /// as `false` and simply leaves the window where it went.
    pub fn move_to_display(&self, task: i32, display: i32) -> bool {
        self.ok(&format!("MOVEDISPLAY {task} {display}"))
    }

    fn list_command(&self, command: &str) -> Vec<Task> {
        let mut out = Vec::new();
        let mut guard = match self.conn.lock() {
            Ok(g) => g,
            Err(_) => return out,
        };
        if !Self::ensure(&mut guard) {
            return out;
        }
        let conn = guard.as_mut().expect("ensured");
        if writeln!(conn.writer, "{command}").is_err() {
            *guard = None;
            return out;
        }
        let mut saw_end = false;
        loop {
            let mut line = String::new();
            match conn.reader.read_line(&mut line) {
                Ok(0) | Err(_) => break, // EOF: the daemon went away
                Ok(_) => {}
            }
            let line = line.trim_end();
            if line == "END" {
                saw_end = true;
                break;
            }
            if line.starts_with("ERR") {
                break;
            }
            if let Some(task) = parse_task(line) {
                out.push(task);
            }
        }
        if !saw_end {
            *guard = None;
        }
        out
    }

    /// Drain the launcher's pending requests from the daemon.
    ///
    /// `None` means the daemon did not answer — it is not running, or it predates the
    /// verb — and the caller must fall back to the `content query` channel.
    /// `Some(vec![])` means it answered and there is nothing queued, which is the
    /// overwhelmingly common case and the one worth being cheap: this is a write and a
    /// read on an open socket, against a `content query` that boots an app_process VM
    /// (537 ms measured on a Redmi Note 7) every time it is asked.
    ///
    /// Rows are NOT consumed by reading. `req_ack` drops them once they have actually
    /// been executed, so a pump that dies mid-tick delays a press by one poll rather than
    /// eating it — the same discipline the v2 ContentProvider protocol already uses.
    pub fn req_get(&self) -> Option<Vec<(u64, String, String)>> {
        let lines = self.lines_command("REQGET")?;
        let mut out = Vec::new();
        for line in lines {
            // REQ <id> <cmd> <arg…>
            let Some(rest) = line.strip_prefix("REQ ") else {
                continue;
            };
            let mut parts = rest.splitn(3, ' ');
            let (Some(id), Some(cmd)) = (parts.next(), parts.next()) else {
                continue;
            };
            let Ok(id) = id.parse::<u64>() else {
                continue;
            };
            out.push((id, cmd.to_string(), parts.next().unwrap_or("").to_string()));
        }
        Some(out)
    }

    /// Drop every queued request at or below `id`.
    pub fn req_ack(&self, id: u64) -> bool {
        self.ok(&format!("REQACK {id}"))
    }

    /// A multi-line reply, up to `END`. `None` if the daemon never answered, so a caller
    /// can tell "not there" from "there, nothing to say".
    fn lines_command(&self, command: &str) -> Option<Vec<String>> {
        let mut guard = self.conn.lock().ok()?;
        if !Self::ensure(&mut guard) {
            return None;
        }
        let conn = guard.as_mut().expect("ensured");
        if writeln!(conn.writer, "{command}").is_err() {
            *guard = None;
            return None;
        }
        let mut out = Vec::new();
        loop {
            let mut line = String::new();
            match conn.reader.read_line(&mut line) {
                Ok(0) | Err(_) => {
                    // EOF mid-reply: the daemon went away and this answer is a fragment,
                    // so report absence rather than a short list the caller would treat
                    // as "nothing queued".
                    *guard = None;
                    return None;
                }
                Ok(_) => {}
            }
            let line = line.trim_end();
            if line == "END" {
                return Some(out);
            }
            if line.starts_with("ERR") {
                // An older daemon without the verb. Absent, not empty.
                return None;
            }
            out.push(line.to_string());
        }
    }

    /// Reserve `px` at the top of the task for chrome by shrinking the app's bounds.
    ///
    /// Deliberately does NOT publish a captionBar inset source: that additionally wakes
    /// One UI's own decoration into the same band.
    pub fn strip(&self, display: i32, task: i32, px: i32) -> bool {
        self.ok(&format!("STRIP {display} {task} {px}"))
    }

    pub fn unstrip(&self, display: i32, task: i32) -> bool {
        self.ok(&format!("UNSTRIP {display} {task}"))
    }

    /// Move without resizing. The hot path for dragging.
    pub fn move_to(&self, display: i32, task: i32, x: i32, y: i32) -> bool {
        self.ok(&format!("MOVE {display} {task} {x} {y}"))
    }

    pub fn bounds(&self, display: i32, task: i32, l: i32, t: i32, r: i32, b: i32) -> bool {
        self.ok(&format!("BOUNDS {display} {task} {l} {t} {r} {b}"))
    }

    pub fn front(&self, display: i32, task: i32) -> bool {
        self.ok(&format!("FRONT {display} {task}"))
    }

    pub fn send_to_back(&self, display: i32, task: i32) -> bool {
        self.ok(&format!("BACK {display} {task}"))
    }

    /// A real close (`removeTask`), unlike One UI's caption X which only hides the task.
    pub fn close(&self, task: i32) -> bool {
        self.ok(&format!("CLOSE {task}"))
    }

    /// Raise the launcher, without the task API.
    ///
    /// The daemon does it with `am start`, which every Android version has. Its
    /// twin `front` is better — it reorders the existing task instead of
    /// re-delivering an intent — and it is unavailable below Android 12, so this
    /// is the one that runs on the phones that need it most.
    pub fn home(&self) -> bool {
        self.ok("HOME")
    }

    /// Close the front-most app window, launcher excepted, without the task API.
    pub fn close_top(&self) -> bool {
        self.ok("CLOSETOP")
    }

    pub fn ping(&self) -> bool {
        self.ok("PING")
    }

    /// Put the phone's own panel back, if the desktop is what left it dark.
    ///
    /// Unconditional would be wrong: `SCREEN 1` on a phone the user put to sleep with the
    /// power button lights a panel the framework has stopped drawing to. Only the daemon
    /// knows whether the darkness is ours, so it is asked before it is told.
    pub fn restore_screen(&self) -> bool {
        if !matches!(self.request("SCREEN"), Some(r) if r.trim() == "OK 0") {
            return false;
        }
        self.ok("SCREEN 1")
    }

    /// Hand the phone's media route back to its own policy.
    ///
    /// Session-end housekeeping: picking a phone output in the desktop's
    /// media flyout pins the media strategy to that device (the daemon's
    /// AUDIOROUTE SET), and a pin is desktop policy — left behind, it would
    /// quietly overrule the phone's own output switcher ever after. Best
    /// effort like everything here: a daemon too old to know the verb
    /// answers ERR, and there was then no pin to clear either.
    pub fn clear_audio_route(&self) -> bool {
        self.ok("AUDIOROUTE CLEAR")
    }

    /// Arm (and keep alive) the daemon's dead-man switch.
    ///
    /// Every other undo in this project runs from the PC. This one cannot: when the cable
    /// is pulled there is no adb left to run it with, so the daemon does it on the device
    /// instead. It can work the rest out by looking at the phone, but not what the display
    /// settings were BEFORE DeX — only the host kept that — so those ride along as the
    /// `settings` chain to run (`adb::undo_globals_script`).
    ///
    /// Re-sent on a timer, and that repetition IS the heartbeat: the daemon cleans up once
    /// it has heard nothing here for `ttl_secs` AND the desktop display has gone.
    pub fn arm(&self, ttl_secs: u32, undo_globals: &str) -> bool {
        self.ok(&format!("ARM {ttl_secs} {undo_globals}"))
    }

    fn ok(&self, command: &str) -> bool {
        matches!(self.request(command), Some(r) if r.starts_with("OK"))
    }

    /// One request, one reply, with a single reconnect retry.
    ///
    /// The retry is load-bearing, not defensive: a TCP peer that has gone away is not
    /// reported by a failing write — the write succeeds into the socket buffer and the
    /// read returns EOF. Without treating EOF as a dead connection, a daemon restart
    /// wedges the client forever.
    fn request(&self, command: &str) -> Option<String> {
        if let Some(reply) = self.attempt(command) {
            return Some(reply);
        }
        if let Ok(mut guard) = self.conn.lock() {
            *guard = None;
        }
        self.attempt(command)
    }

    fn attempt(&self, command: &str) -> Option<String> {
        let mut guard = self.conn.lock().ok()?;
        if !Self::ensure(&mut guard) {
            return None;
        }
        let conn = guard.as_mut().expect("ensured");
        if writeln!(conn.writer, "{command}").is_err() {
            *guard = None;
            return None;
        }
        let mut line = String::new();
        match conn.reader.read_line(&mut line) {
            Ok(0) | Err(_) => {
                *guard = None;
                None
            }
            Ok(_) => Some(line.trim_end().to_string()),
        }
    }

    fn ensure(guard: &mut Option<Conn>) -> bool {
        if guard.is_some() {
            return true;
        }
        let addr = format!("127.0.0.1:{HOST_PORT}");
        let socket = match addr.parse() {
            Ok(a) => a,
            Err(_) => return false,
        };
        let Ok(stream) = TcpStream::connect_timeout(&socket, CONNECT_TIMEOUT) else {
            return false;
        };
        let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
        let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
        // A drag is many tiny writes; Nagle would batch them into latency.
        let _ = stream.set_nodelay(true);
        let Ok(writer) = stream.try_clone() else {
            let _ = stream.shutdown(Shutdown::Both);
            return false;
        };
        *guard = Some(Conn {
            reader: BufReader::new(stream),
            writer,
        });
        true
    }
}

/// `TASK ix id display mode actType vis l t r b al at ar ab pkg activity` — 17 fields.
///
/// Field order is load-bearing and easy to get wrong by one: index 14 is the app-bounds
/// bottom, index 15 is the package. Reading 14 as the package renders window titles as a
/// y coordinate.
fn parse_task(line: &str) -> Option<Task> {
    let f: Vec<&str> = line.split_whitespace().collect();
    if f.len() < 17 || f[0] != "TASK" {
        return None;
    }
    Some(Task {
        index: f[1].parse().ok()?,
        task_id: f[2].parse().ok()?,
        display_id: f[3].parse().ok()?,
        windowing_mode: f[4].parse().ok()?,
        visible: f[6] == "1",
        left: f[7].parse().ok()?,
        top: f[8].parse().ok()?,
        right: f[9].parse().ok()?,
        bottom: f[10].parse().ok()?,
        app_top: f[12].parse().ok()?,
        package: f[15].to_string(),
        activity: f[16].to_string(),
    })
}


/// The package whose task IS the desktop, rather than a window on it.
const LAUNCHER: &str = "com.ccrstech.openandroiddex.launcher";

/// The activity of that task. The launcher's other windows — its Settings, the
/// task manager, every Linux app's window — are tasks of the same package, each
/// with a taskAffinity of its own, so it is this that tells the desktop apart.
const DESKTOP_ACTIVITY: &str = "com.ccrstech.openandroiddex.launcher.LauncherActivity";

impl Task {
    /// The desktop itself, rather than a window on it.
    fn is_desktop(&self) -> bool {
        self.package == LAUNCHER && self.activity == DESKTOP_ACTIVITY
    }
}

/// Bring the desktop's own launcher back in front of whatever is covering it.
///
/// THE WAY OUT. On a phone that can draw titlebars this is a convenience — the
/// window has a close button and the taskbar is always there beside it. On a
/// phone that cannot, it is the only way back: an app opens fullscreen over the
/// launcher, the taskbar goes with it, and there is nothing left on screen to
/// press.
///
/// It works there because it asks for none of what such a phone lacks. The
/// daemon reorders the task with `MANAGE_ACTIVITY_TASKS` at uid 2000, which
/// needs no trusted display, no freeform mode, no system decorations and no
/// focused window.
#[tauri::command(async)]
pub fn desktop_home() -> Result<bool, String> {
    let wm = WmClient::new();
    // The good path, where the platform has the task API: reorder the launcher's
    // existing task, which keeps its state and skips the intent entirely. The
    // desktop's task, not the first of the launcher's: with its Settings or a
    // Linux app's window open, that window was raised and the desktop stayed
    // behind the app covering it.
    if let Some(display) = wm.desktop_display() {
        if let Some(task) = wm.list(display).into_iter().find(Task::is_desktop) {
            if wm.front(display, task.task_id) {
                return Ok(true);
            }
        }
    }
    // …and the path for everything older. `desktop_display` and `list` both go
    // through getAllRootTaskInfos, which is Android 12 — on a phone below that
    // they answer ERR and we land here, which is exactly where such a phone
    // needs the most help: no trusted display means no freeform, so an app
    // covers the launcher whole and nothing on screen can bring it back.
    Ok(wm.home())
}

/// Close the topmost app window on the desktop.
///
/// The desktop itself is skipped rather than closed: removing its task leaves a
/// black display with nothing left to bring anything back. A desktop with no
/// window on it answers false, which is not an error.
///
/// The launcher's OTHER windows are closed like any window, but by the launcher
/// (`ask_launcher_to_close`), because some of them must be asked: closing the
/// Linux or Docker window ends what runs inside it. Passing them over, as this
/// once did for the whole package, closed the app BEHIND the launcher's Settings
/// instead — and below Android 14, with no caption to press, left nothing on a
/// computer's screen that could close them at all.
#[tauri::command(async)]
pub fn desktop_close_top(app: tauri::AppHandle) -> Result<bool, String> {
    let wm = WmClient::new();
    if let Some(display) = wm.desktop_display() {
        // LIST is topmost-first, so the first task that is not the desktop
        // itself is the window a person would call the one in front.
        if let Some(task) = wm.list(display).into_iter().find(|t| !t.is_desktop()) {
            if task.package == LAUNCHER {
                return Ok(ask_launcher_to_close(&app, &task));
            }
            return Ok(wm.close(task.task_id));
        }
        // The display answered and holds nothing but the desktop: an empty
        // desktop, which is not a failure and must not fall through to a second
        // attempt that would say the same thing more slowly.
        return Ok(false);
    }
    Ok(wm.close_top())
}

/// Have the launcher close one of its own windows the way its caption's ✕
/// does (LauncherActivity's "close"): asked first where that ends something
/// running, else removed.
fn ask_launcher_to_close(app: &tauri::AppHandle, task: &Task) -> bool {
    // A class name of ours, but it came off the phone and goes into a shell.
    if !task.activity.chars().all(|c| c.is_ascii_alphanumeric() || "._$".contains(c)) {
        return false;
    }
    let Some(serial) = forwarded_serial(app) else {
        return false;
    };
    let cmd = format!(
        "am broadcast -a {LAUNCHER}.GESTURE -p {LAUNCHER} --es action close --ei task {} --es activity {}",
        task.task_id, task.activity
    );
    match crate::adb::run_adb_quiet(app, &["-s", &serial, "shell", &cmd]) {
        Ok(_) => true,
        Err(e) => {
            log::warn!("{serial}: could not ask the launcher to close task {}: {e}", task.task_id);
            false
        }
    }
}

/// The phone the daemon client is talking to: the one `adb forward` points
/// [`HOST_PORT`] at. The desktop commands take no serial — they act on the
/// desktop the forward leads to — and what goes by adb has to name it.
fn forwarded_serial(app: &tauri::AppHandle) -> Option<String> {
    serial_forwarding(&crate::adb::run_adb_quiet(app, &["forward", "--list"]).ok()?)
}

/// `adb forward --list`'s "<serial> tcp:7191 tcp:7191", cut at the port rather
/// than at the first space: a phone on wireless debugging can have a space in
/// its serial.
fn serial_forwarding(list: &str) -> Option<String> {
    let host = format!(" tcp:{HOST_PORT} ");
    list.lines()
        .find_map(|l| l.find(&host).map(|at| l[..at].trim().to_string()))
        .filter(|s| !s.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_task_line() {
        let t = parse_task(
            "TASK 0 957 18 5 1 1 549 178 1605 955 549 212 1605 955 \
             com.android.chrome com.google.android.apps.chrome.Main",
        )
        .expect("should parse");
        assert_eq!(t.task_id, 957);
        assert_eq!(t.display_id, 18);
        assert_eq!(t.package, "com.android.chrome");
        assert_eq!((t.left, t.top, t.right, t.bottom), (549, 178, 1605, 955));
        assert_eq!(t.app_top, 212);
        assert!(t.is_freeform());
        assert!(t.has_strip(), "app_top 212 > top 178");
    }

    #[test]
    fn the_desktop_is_the_launchers_own_activity_not_its_other_windows() {
        // LIST on the Galaxy A13, the launcher's Settings open over the desktop.
        let settings = parse_task(
            "TASK 0 2404 22 5 1 1 49 281 934 921 49 281 934 921 \
             com.ccrstech.openandroiddex.launcher com.ccrstech.openandroiddex.launcher.SettingsActivity",
        )
        .unwrap();
        let desktop = parse_task(
            "TASK 1 2403 22 1 1 1 0 0 984 1202 0 0 984 1202 \
             com.ccrstech.openandroiddex.launcher com.ccrstech.openandroiddex.launcher.LauncherActivity",
        )
        .unwrap();
        assert!(!settings.is_desktop());
        assert!(desktop.is_desktop());
        assert_eq!(
            [settings, desktop].into_iter().find(Task::is_desktop).map(|t| t.task_id),
            Some(2403)
        );
    }

    #[test]
    fn finds_the_phone_the_daemon_port_is_forwarded_to() {
        let list = "R58T51N3G2X tcp:27183 localabstract:scrcpy_1234\n\
                    YPKRA6VS754L6XTO tcp:17191 tcp:7191\n\
                    adb-R5CT30-vWgJpq (2)._adb-tls-connect._tcp tcp:7191 tcp:7191\n";
        assert_eq!(
            serial_forwarding(list).as_deref(),
            Some("adb-R5CT30-vWgJpq (2)._adb-tls-connect._tcp")
        );
        assert_eq!(serial_forwarding("R58T51N3G2X tcp:7191 tcp:7191\n").as_deref(), Some("R58T51N3G2X"));
        assert_eq!(serial_forwarding("R58T51N3G2X tcp:17191 tcp:7191\n"), None);
        assert_eq!(serial_forwarding(""), None);
    }

    #[test]
    fn rejects_short_lines() {
        // The 16-field form was the original off-by-one; it must not parse.
        assert!(parse_task("TASK 0 957 18 5 1 1 0 0 1 1 0 0 1 1 com.x").is_none());
        assert!(parse_task("END").is_none());
    }
}
