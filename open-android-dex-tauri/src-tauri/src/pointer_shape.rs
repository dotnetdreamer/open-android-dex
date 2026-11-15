//! The pointer's shape over the desktop, on this computer's cursor (issue #5,
//! doc/window-resize.md).
//!
//! Under scrcpy's default `--mouse=sdk` this computer draws its own cursor over the
//! desktop's video, so the shapes the phone gives its windows' edges — a double arrow
//! over a resize edge — never show: the phone computes them and nothing draws them. The
//! launcher's resize edges (ResizeEdges) say which shape is wanted to the window daemon
//! (SHAPE), and this waits for each new one (SHAPEWAIT, over the forward of 7191) and
//! hands it to the desktop's scrcpy, whose window owns the cursor.
//!
//! scrcpy's own client cannot be told a cursor; ours can. openandroiddex-scrcpy's
//! `pointer-shapes.patch` makes it read `shape <name>` lines from stdin when
//! `SCRCPY_POINTER_SHAPES=1` is in its environment, and set the window's cursor to the
//! system cursor of that name with SDL — the same call on Windows, macOS, X11 and
//! Wayland, which no cursor trick from outside that process can say. It says so on its
//! first lines ([`LISTENING`]); a stock client never does, and is left alone.
//!
//! Not when the PHONE draws the pointer (`--mouse=uhid`): there the shape is in the video
//! already, drawn by Android from the same edges' PointerIcon, and this computer's cursor
//! is hidden inside scrcpy's window.

use std::io::Write;
use std::process::{Child, ChildStdin};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// What the patched client prints once it reads stdin (pointer_shape.c).
pub const LISTENING: &str = "Pointer shapes: read from stdin";

/// The environment variable that turns the patched client's reader on.
pub const ENV: &str = "SCRCPY_POINTER_SHAPES";

/// How long one SHAPEWAIT may hold the line: under wm.rs's read timeout (1.5 s).
const WAIT_MS: u32 = 1000;

/// How long a client gets to say it is listening before it is taken for a stock one.
const LISTEN_GRACE: Duration = Duration::from_secs(15);

/// The shapes the patched client knows (pointer_shape.c), and so the only ones sent.
const NAMES: &[&str] = &[
    "default",
    "ew-resize",
    "ns-resize",
    "nwse-resize",
    "nesw-resize",
    "move",
    "pointer",
    "text",
    "crosshair",
    "not-allowed",
    "wait",
    "progress",
];

fn known(name: &str) -> bool {
    NAMES.contains(&name)
}

/// The line the client reads for `name`.
fn line_for(name: &str) -> String {
    format!("shape {name}\n")
}

/// Follow the phone's pointer shapes for one desktop attempt, until it ends.
///
/// `listening` is set by the output reader when the client prints [`LISTENING`];
/// `stdin` is the client's. One thread; it ends with the attempt's process, when a stop
/// is asked for, or — with a client that never said it listens — after
/// [`LISTEN_GRACE`].
pub fn watch(
    key: String,
    display_id: Arc<AtomicI32>,
    stop: Arc<AtomicBool>,
    child: Arc<Mutex<Child>>,
    stdin: ChildStdin,
    listening: Arc<AtomicBool>,
) {
    let spawned = thread::Builder::new()
        .name("pointer-shapes".into())
        .spawn(move || run(&key, &display_id, &stop, &child, stdin, &listening));
    if let Err(e) = spawned {
        log::warn!("pointer shapes: no thread: {e}");
    }
}

fn run(
    key: &str,
    display_id: &AtomicI32,
    stop: &AtomicBool,
    child: &Mutex<Child>,
    mut stdin: ChildStdin,
    listening: &AtomicBool,
) {
    let wm = crate::wm::WmClient::new();
    // From the display, not from the spawn: the client starts listening as its window
    // opens, which on a first start can be many seconds after it was spawned.
    let mut displayed: Option<Instant> = None;
    let mut seen: i64 = -1;
    let mut shown = String::from("default");
    let mut daemon_said = true;
    loop {
        if stop.load(Ordering::SeqCst) || exited(child) {
            return;
        }
        let display = display_id.load(Ordering::SeqCst);
        if display < 0 {
            thread::sleep(Duration::from_millis(200));
            continue;
        }
        let since = *displayed.get_or_insert_with(Instant::now);
        if !listening.load(Ordering::SeqCst) {
            if since.elapsed() > LISTEN_GRACE {
                log::info!(
                    "pointer shapes [{key}]: this scrcpy does not take them (a stock client, \
                     not openandroiddex-scrcpy's) — the cursor stays an arrow over window edges"
                );
                return;
            }
            thread::sleep(Duration::from_millis(200));
            continue;
        }
        let Some((seq, name)) = wm.shape_wait(display, seen, WAIT_MS) else {
            // No daemon, or one older than SHAPE: the edges are not there either.
            if daemon_said {
                log::info!("pointer shapes [{key}]: the window daemon does not answer SHAPEWAIT yet");
                daemon_said = false;
            }
            thread::sleep(Duration::from_millis(1000));
            continue;
        };
        if !daemon_said {
            log::info!("pointer shapes [{key}]: following the phone's pointer shapes");
            daemon_said = true;
        }
        if seq == seen {
            continue; // the wait ran out: nothing new
        }
        seen = seq;
        if !known(&name) || name == shown {
            continue;
        }
        if let Err(e) = stdin.write_all(line_for(&name).as_bytes()).and_then(|_| stdin.flush()) {
            // The client has gone, or closed its end: nothing more to tell it.
            log::info!("pointer shapes [{key}]: scrcpy stopped listening ({e})");
            return;
        }
        log::debug!("pointer shapes [{key}]: {name}");
        shown = name;
    }
}

fn exited(child: &Mutex<Child>) -> bool {
    // try_wait caches the status, so the monitor polling the same child still sees it.
    matches!(child.lock().map(|mut c| c.try_wait()), Ok(Ok(Some(_))))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sends_only_what_the_patched_client_knows() {
        for name in ["default", "ew-resize", "ns-resize", "nwse-resize", "nesw-resize"] {
            assert!(known(name), "{name}");
        }
        assert!(!known("rm"));
        assert!(!known(""));
        assert_eq!(line_for("ew-resize"), "shape ew-resize\n");
    }

    #[test]
    fn listens_for_the_line_the_patch_prints() {
        // pointer_shape.c: LOGI("Pointer shapes: read from stdin"), which SDL prints as
        // "INFO: Pointer shapes: read from stdin" — the output reader looks for it inside.
        let printed = "INFO: Pointer shapes: read from stdin";
        assert!(printed.contains(LISTENING));
        let patch = include_str!("../../../openandroiddex-scrcpy/pointer-shapes.patch");
        assert!(patch.contains(LISTENING), "the patch no longer prints what this waits for");
        assert!(patch.contains(ENV), "the patch no longer reads {ENV}");
        for name in NAMES {
            assert!(patch.contains(&format!("{{\"{name}\", SDL_SYSTEM_CURSOR_")), "{name}");
        }
    }
}
