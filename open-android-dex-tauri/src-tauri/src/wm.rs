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
    fn rejects_short_lines() {
        // The 16-field form was the original off-by-one; it must not parse.
        assert!(parse_task("TASK 0 957 18 5 1 1 0 0 1 1 0 0 1 1 com.x").is_none());
        assert!(parse_task("END").is_none());
    }
}
