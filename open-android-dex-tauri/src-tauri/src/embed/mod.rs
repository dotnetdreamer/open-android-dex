//! Native control of the scrcpy windows this app spawns.
//!
//! Everything the desktop draws lives inside a window belonging to a *different
//! process* — scrcpy's SDL window. Four things are wanted from it: put it
//! somewhere, show or hide it, raise it, and toggle it fullscreen. None of that
//! is expressible in the webview, so each host answers it with its own window
//! API, behind the one contract in [`backend`].
//!
//! The two hosts are not equally capable, and the gap is worth stating plainly
//! because it shaped the design:
//!
//! * **Windows** can *reparent*. `SetParent` makes scrcpy's window a native
//!   child of the Tauri window, so the webview can draw chrome around a rect
//!   and the video sits inside it. That is what [`embed_session`] does.
//! * **macOS cannot**, at all. There is no cross-process reparenting API and
//!   there never has been — a window belongs to the process that made it, and
//!   the window server enforces it. So `embed_session` reports that plainly on
//!   macOS rather than half-working, and the desktop runs as its own top-level
//!   window. That is already how it runs on Windows too: the frontend never
//!   calls the embedding commands (`toggle_fullscreen` and [`activate`] are the
//!   two entry points that carry real traffic), so this costs nothing today.
//!
//! What macOS *can* do splits by permission, which is why the backend
//! distinguishes them:
//!
//! * activate, hide and unhide go through `NSRunningApplication` and need no
//!   permission at all;
//! * moving, resizing and fullscreen go through the Accessibility API, which
//!   the user has to grant in System Settings. The failure is reported, never
//!   silent — a ⛶ button that does nothing with no explanation is the one
//!   outcome worse than not having it.

use std::sync::atomic::Ordering;

use tauri::{AppHandle, Manager};

use crate::scrcpy::MirrorState;

#[cfg_attr(windows, path = "windows.rs")]
#[cfg_attr(target_os = "macos", path = "macos.rs")]
#[cfg_attr(not(any(windows, target_os = "macos")), path = "unsupported.rs")]
mod backend;

/// A session's process id and the native window handle previously stored for
/// it (0 = none). The handle is meaningful only to the backend that produced
/// it: an `HWND` on Windows, always 0 on macOS, where the pid is the whole
/// address of a window.
fn session(app: &AppHandle, key: &str) -> Result<(u32, isize), String> {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    let s = map
        .get(key)
        .ok_or_else(|| format!("no active mirror session: {key}"))?;
    Ok((s.pid(), s.hwnd().load(Ordering::SeqCst)))
}

fn store_handle(app: &AppHandle, key: &str, handle: isize) {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    if let Some(s) = map.get(key) {
        s.hwnd().store(handle, Ordering::SeqCst);
    }
}

/// Reparent a session's scrcpy window into the main window at the given
/// client-area rect (physical pixels).
///
/// Windows only in effect — see the module docs for why macOS answers with an
/// error instead of an approximation.
#[tauri::command]
pub fn embed_session(
    app: AppHandle,
    session_key: String,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
) -> Result<(), String> {
    let (pid, _) = session(&app, &session_key)?;
    // The parent is resolved per backend: only Windows has a use for it, and
    // asking for it here would fail the call on a host that does not.
    let handle = backend::attach(&app, &session_key, pid, x, y, w, h)?;
    store_handle(&app, &session_key, handle);
    Ok(())
}

#[tauri::command]
pub fn move_session(
    app: AppHandle,
    session_key: String,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
) -> Result<(), String> {
    let (pid, handle) = session(&app, &session_key)?;
    backend::set_frame(pid, handle, x, y, w, h)
}

#[tauri::command]
pub fn set_session_visible(
    app: AppHandle,
    session_key: String,
    visible: bool,
) -> Result<(), String> {
    let (pid, handle) = session(&app, &session_key)?;
    backend::set_visible(pid, handle, visible)
}

#[tauri::command]
pub fn raise_session(app: AppHandle, session_key: String) -> Result<(), String> {
    let (pid, handle) = session(&app, &session_key)?;
    backend::raise(pid, handle)
}

/// Toggle a session's scrcpy window between fullscreen and its previous frame,
/// answering with the new state (true = now fullscreen).
///
/// Called by the freeform enforcer when the in-desktop taskbar's ⛶ button is
/// pressed — the phone-side button, not anything in this app's own window.
pub fn toggle_fullscreen(app: &AppHandle, session_key: &str) -> Result<bool, String> {
    let (pid, handle) = session(app, session_key)?;
    backend::toggle_fullscreen(pid, handle)
}

/// Take a session's scrcpy window out of fullscreen, answering with the new
/// state (always `false`).
///
/// The half of [`toggle_fullscreen`] that Escape is allowed to reach. A key
/// press cannot be a toggle: the taskbar's idea of the state can be stale — on
/// macOS the user can leave fullscreen with the green button, and on either
/// host the window can be replaced by an auto-reconnect — and a toggle acting
/// on that stale belief would put the desktop fullscreen instead of taking it
/// out, which is the one thing Escape must never do.
pub fn exit_fullscreen(app: &AppHandle, session_key: &str) -> Result<bool, String> {
    let (pid, handle) = session(app, session_key)?;
    backend::exit_fullscreen(pid, handle)
}

/// Bring a session's scrcpy window to the foreground (taskbar refocus).
///
/// Lives here rather than in `scrcpy.rs` because it is the same question as
/// [`raise_session`] asked of a whole application instead of one window, and
/// both hosts answer it with the same API they answer the rest of this module
/// with.
pub fn activate(app: &AppHandle, session_key: &str) -> Result<(), String> {
    let (pid, _) = session(app, session_key)?;
    backend::activate(pid)
}

/// Where a scrcpy window is and how it is shown: what a desktop that comes back
/// after a reconnect needs to come back the same.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WindowState {
    /// The content's top-left and size, in the units scrcpy's `--window-*`
    /// take (display.rs: points on macOS, pixels on Windows).
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    /// The scale of the screen it is on — pixels per point on macOS, the
    /// window's dpi / 96 on Windows. A desktop that follows its window
    /// re-scales to it when the window is dragged onto another screen.
    pub scale: f64,
    pub maximized: bool,
    pub minimized: bool,
    pub fullscreen: bool,
    /// The frame a maximized window goes back to, in the host's own terms
    /// (Windows: the placement's normal rect). None where there is none.
    pub restore: Option<(i32, i32, i32, i32)>,
}

/// `pid`'s window as it is now, or None when it cannot be told.
pub fn window_state(app: &AppHandle, pid: u32) -> Option<WindowState> {
    backend::window_state(app, pid)
}

/// Maximize `pid`'s window, keeping `restore` (from a [`WindowState`]) as the
/// frame it is restored to. Waits for the window to appear, up to `wait`.
pub fn show_maximized(pid: u32, restore: (i32, i32, i32, i32), wait: std::time::Duration) -> Result<(), String> {
    backend::show_maximized(pid, restore, wait)
}
