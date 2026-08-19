//! Escape leaves fullscreen.
//!
//! The desktop's fullscreen is ours, not scrcpy's — the session is spawned
//! without `--fullscreen` and the window is resized by `embed/` — so the only
//! things that can leave it are the taskbar's ⛶ button and this. The button is
//! on the phone, which is the wrong place to reach for when the desktop is
//! covering the whole monitor and the pointer is somewhere in Android.
//!
//! ## Why a host key hook at all
//!
//! The key goes to scrcpy's window, in scrcpy's process, and from there
//! straight to Android. Nothing in this app ever sees it. The phone *could*
//! see it — `CaptionService` is an accessibility service and could filter key
//! events — but its only way back here is the request queue, which is a poll
//! away, and a keypress that takes a beat to answer reads as a broken key. So
//! the key is caught on this side, before the window gets it.
//!
//! ## What it costs, and what it does not
//!
//! The hook is installed for the session, but it does almost nothing: the
//! common path is two integer comparisons, because everything that is not
//! Escape-down is passed on before any state is read. The key is only
//! **swallowed** when it was going to do something — fullscreen is on *and*
//! the desktop window is in front — so Escape reaches Android untouched in
//! every other case, including when the desktop is merely windowed. That is
//! the same bargain a browser makes, and it is what stops this from quietly
//! taking a useful key away from Android apps.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tauri::AppHandle;

use crate::scrcpy::Shared;

#[cfg_attr(windows, path = "windows.rs")]
#[cfg_attr(target_os = "macos", path = "macos.rs")]
#[cfg_attr(not(any(windows, target_os = "macos")), path = "unsupported.rs")]
mod backend;

/// What a host's key hook asks before deciding to swallow Escape.
///
/// Deliberately tiny and cheap: on macOS this is consulted from inside an
/// event tap, which the system disables if it dawdles, and on Windows from a
/// low-level keyboard hook, where every keystroke on the machine passes
/// through. Neither may do anything that can block.
#[derive(Clone)]
pub struct Watcher {
    tx: std::sync::mpsc::Sender<()>,
    shared: Arc<Shared>,
    /// One exit in flight at a time. A held Escape repeats about thirty times
    /// a second, and each of those would otherwise queue another round trip
    /// through the window server.
    busy: Arc<AtomicBool>,
}

impl Watcher {
    /// Is the desktop window fullscreen right now? Asked first, because it is
    /// an atomic load and the answer is almost always no.
    fn fullscreen(&self) -> bool {
        self.shared.is_fullscreen()
    }

    /// Ask for fullscreen to be left. Answers whether the key should be
    /// swallowed — which it is even while an earlier exit is still landing,
    /// because letting the repeat through would send a stray Escape to Android
    /// in the middle of leaving.
    fn request_exit(&self) -> bool {
        if self.busy.swap(true, Ordering::SeqCst) {
            return true;
        }
        let _ = self.tx.send(());
        true
    }
}

/// Sessions are numbered so a teardown can only ever tear down its own.
///
/// Every session leaves a stop-watcher behind, and a watcher outlives the hook
/// it was started for. Without this, one waking up after the next session had
/// begun would disarm its replacement — and the symptom would be Escape
/// working until the first reconnect and never again.
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);

/// Start watching for Escape for a desktop session.
pub fn start(app: AppHandle, key: String, shared: Arc<Shared>, stop: Arc<AtomicBool>) {
    stop_engine();
    let (tx, rx) = std::sync::mpsc::channel::<()>();
    let busy = Arc::new(AtomicBool::new(false));
    let watcher = Watcher {
        tx,
        shared: shared.clone(),
        busy: busy.clone(),
    };

    // Leaving fullscreen is several window-server round trips on macOS and a
    // relayout on Windows — far too much for the hook that noticed the key.
    // Same split as the gesture engine, for the same reason.
    {
        let (app, key, stop) = (app.clone(), key.clone(), stop.clone());
        std::thread::spawn(move || loop {
            match rx.recv_timeout(Duration::from_millis(250)) {
                Ok(()) => {
                    match crate::embed::exit_fullscreen(&app, &key) {
                        Ok(on) => shared.record_fullscreen(on),
                        // Nowhere to show this: the key was pressed over a
                        // window belonging to another process, and the button
                        // that mirrors the state is on the phone. On macOS
                        // this is where a missing Accessibility grant
                        // announces itself.
                        Err(e) => log::warn!("hotkeys [{key}] escape: {e}"),
                    }
                    busy.store(false, Ordering::SeqCst);
                }
                // Timing out is how the session's stop flag is noticed during
                // the long quiet stretches between presses.
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => return,
            }
            if stop.load(Ordering::SeqCst) {
                return;
            }
        });
    }

    let session = NEXT_SESSION.fetch_add(1, Ordering::SeqCst);
    if let Ok(mut guard) = ctx().lock() {
        *guard = Some(Ctx {
            session,
            app: app.clone(),
            key: key.clone(),
            watcher,
        });
    }
    backend::start(app, key, session, stop);
}

/// Tear down the running hook, if there is one.
pub fn stop_engine() {
    backend::stop();
}

/// Decide what to do about one Escape-down, from a host's key hook.
///
/// Answers true when the key belongs to us and must not be passed on. The
/// order is the point: the cheap, almost-always-false question is asked first,
/// and the one that costs a system call only when the answer might be yes.
fn take_escape(app: &AppHandle, key: &str, watcher: &Watcher) -> bool {
    if !watcher.fullscreen() {
        return false;
    }
    // Fullscreen but not in front — the user alt-tabbed away and is pressing
    // Escape at something else. Eating it there would be a bug in their editor,
    // not a feature in ours.
    if !backend::desktop_is_foreground(app, key) {
        return false;
    }
    watcher.request_exit()
}

/// Session-key helper shared by both backends, so the two cannot disagree
/// about which window counts as the desktop.
fn session_pid(app: &AppHandle, key: &str) -> Option<u32> {
    crate::scrcpy::session_pid(app, key)
}

/// State the host hooks reach through, because neither a Win32 hook procedure
/// nor an event tap can be handed a closure.
struct Ctx {
    session: u64,
    app: AppHandle,
    key: String,
    watcher: Watcher,
}

fn ctx() -> &'static Mutex<Option<Ctx>> {
    static CTX: std::sync::OnceLock<Mutex<Option<Ctx>>> = std::sync::OnceLock::new();
    CTX.get_or_init(|| Mutex::new(None))
}

/// The whole decision, for a host that has just seen Escape go down.
///
/// Lives here rather than in each backend so there is one answer to "should
/// this key be swallowed" and not two that drift apart.
fn release_ctx(session: u64) {
    if let Ok(mut guard) = ctx().lock() {
        // Only if it is still ours. A hook shutting down late must not clear
        // the state its replacement has already installed.
        if guard.as_ref().is_some_and(|c| c.session == session) {
            *guard = None;
        }
    }
}

fn on_escape_down() -> bool {
    let Ok(guard) = ctx().try_lock() else {
        // Contended only while a session is starting or ending. Passing the
        // key on is the safe answer: a missed Escape is a nuisance, a
        // swallowed one during teardown is a key that vanished.
        return false;
    };
    let Some(c) = guard.as_ref() else {
        return false;
    };
    take_escape(&c.app, &c.key, &c.watcher)
}
