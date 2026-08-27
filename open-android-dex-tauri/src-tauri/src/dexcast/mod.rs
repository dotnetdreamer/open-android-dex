//! Wireless DeX with no adb anywhere — the Miracast route, hosted.
//!
//! `projection.rs` established the hard boundary: this app cannot *be* a
//! Miracast sink (`MiracastReceiver` refuses Win32 processes), so the only
//! receiver a phone can cast DeX to is Windows' own Wireless Display app.
//! What that module could not do was give the user anything better than a
//! signpost — the receiver takes over a monitor and none of it looks or feels
//! like part of this app.
//!
//! This module closes that gap with a virtual monitor:
//!
//! 1. A one-time, admin-approved setup installs
//!    [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver)
//!    (MIT, SignPath-signed, 132 KB) — downloaded at that moment, never
//!    bundled — giving the PC a monitor that does not exist.
//! 2. A session *attaches* that monitor to the desktop, launches the Wireless
//!    Display receiver, and parks it there fullscreen, out of sight.
//! 3. `Windows.Graphics.Capture` mirrors the virtual monitor into a normal
//!    window of ours — so Samsung's DeX desktop arrives inside something the
//!    user can move, resize and close like everything else here.
//!
//! Two truths shape the edges of this design and are worth stating up front:
//!
//! * **The driver cannot idle at zero monitors.** `Driver.cpp` clamps a
//!   monitor count of 0 back to 1, so "off" is expressed by *detaching* the
//!   monitor from the desktop (`ChangeDisplaySettingsExW`, no admin), not by
//!   asking the driver for none. The driver's control pipe is ACL'd to
//!   Everyone, so nothing after the install ever elevates.
//! * **Input is the phone's decision, not ours.** The receiver forwards
//!   keyboard/mouse to the sender over Miracast UIBC, and Samsung's sender
//!   reliably honours the keyboard but frequently not the mouse (a much
//!   reported One UI limitation). The viewer's input portal forwards what the
//!   OS will carry; the UI is honest that the pointer may have to be the
//!   phone-as-touchpad.
//!
//! macOS has no counterpart on purpose: there is no Miracast receiver for
//! macOS and cannot be one (projection.rs, first paragraph), and a virtual
//! monitor on the Mac would be a monitor with nothing to show on it. The
//! wireless-adb routes in `wireless.rs` remain the Mac answer.

use serde::Serialize;

// Compiled on every host: the pinned payload, the generated scripts and the
// portal's letterbox math are pure data and arithmetic, and keeping them
// portable is what lets their tests run on the machine this is written on.
mod geometry;
mod payload;

#[cfg(windows)]
mod receiver;
#[cfg(windows)]
mod vdd;
#[cfg(windows)]
mod viewer;

#[cfg(not(windows))]
const WINDOWS_ONLY: &str = "Wireless DeX without adb rides Windows' Miracast receiver, which has \
     no macOS counterpart — use the QR or pairing-code route instead.";

/// Where the feature stands on this machine, for the panel to draw from.
///
/// Each field is an independent observation rather than one enum: setup can be
/// half-done in several ways (receiver present but driver missing, driver
/// installed by another tool, payload downloaded but never installed), and the
/// panel wants to point at the specific missing piece, not say "not ready".
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DexcastStatus {
    /// This build can host the route at all (i.e. it is Windows).
    pub supported: bool,
    /// Windows' Wireless Display receiver app is installed.
    pub receiver_installed: bool,
    /// The Virtual Display Driver answers on its control pipe.
    pub driver_ready: bool,
    /// The pinned driver + nefcon payload sits staged in app data.
    pub staged: bool,
    /// A session is live right now.
    pub running: bool,
    /// Human-readable notes collected along the way (which pieces answered,
    /// which did not) — surfaced by the panel under a disclosure, and the
    /// first thing worth reading when a status looks wrong.
    pub detail: String,
}

// ── Commands ────────────────────────────────────────────────────────────

/// Observe. Never changes anything, safe to poll.
#[cfg(windows)]
#[tauri::command(async)]
pub fn dexcast_status(app: tauri::AppHandle) -> Result<DexcastStatus, String> {
    let receiver_installed = receiver::installed();
    let driver_ready = vdd::pipe_ping();
    let staged = vdd::staged(&app);
    let running = viewer::is_running();
    let detail = format!(
        "receiver: {} · driver pipe: {} · payload: {}",
        if receiver_installed { "installed" } else { "missing" },
        if driver_ready { "answering" } else { "silent" },
        if staged { "staged" } else { "not downloaded" },
    );
    Ok(DexcastStatus {
        supported: true,
        receiver_installed,
        driver_ready,
        staged,
        running,
        detail,
    })
}

/// Fetch and verify the driver payload. No admin, no system change: the two
/// pinned archives land in app data and are checked against their SHA-256
/// before a byte of them is trusted. Called when the user opts in — this is
/// the "download at the moment the user decides" half of the deal.
#[cfg(windows)]
#[tauri::command(async)]
pub fn dexcast_prepare(app: tauri::AppHandle) -> Result<String, String> {
    vdd::prepare(&app)
}

/// Install the staged driver — the one step that must elevate, and the UAC
/// prompt is the user's approval for it. Blocks until the driver answers on
/// its pipe (or plainly does not), then parks the new monitor off the desktop
/// so setup ends with nothing visibly changed.
#[cfg(windows)]
#[tauri::command(async)]
pub fn dexcast_install(app: tauri::AppHandle) -> Result<String, String> {
    if viewer::is_running() {
        // Installing (even the adopt path) can touch the monitor and the
        // driver a live session is standing on. Make the caller stop first.
        return Err("Stop the running wireless-DeX session before changing the driver.".into());
    }
    vdd::install(&app)
}

/// Remove the driver and its device node (elevates, like install).
#[cfg(windows)]
#[tauri::command(async)]
pub fn dexcast_uninstall(app: tauri::AppHandle) -> Result<(), String> {
    // A live session holds the monitor attached and the receiver running;
    // removing the driver underneath it would strand both.
    viewer::stop();
    receiver::close();
    vdd::uninstall(&app)
}

/// Start a session: monitor on, receiver parked on it, viewer up.
///
/// Everything here is undone by [`dexcast_stop`] and again by [`shutdown`] on
/// app exit — the pieces this leaves behind (an extra monitor, a fullscreen
/// receiver) are exactly the kind of debris a crash-quit must not strand, the
/// same reasoning as the gestures module's restore pass.
#[cfg(windows)]
#[tauri::command(async)]
pub fn dexcast_start(app: tauri::AppHandle) -> Result<(), String> {
    if viewer::is_running() {
        // Second click on the button, or a stale panel: hand the existing
        // session's window forward rather than double-starting.
        viewer::raise();
        return Ok(());
    }
    if !vdd::pipe_ping() {
        return Err("The virtual display driver is not answering — run the setup step first.".into());
    }
    if !receiver::installed() {
        return Err(
            "Windows' Wireless Display receiver is not installed — use the install button in \
             the steps above."
                .into(),
        );
    }

    let monitor = vdd::attach_monitor()?;
    log::info!(
        "dexcast: virtual monitor attached at {},{} {}x{}",
        monitor.x, monitor.y, monitor.w, monitor.h
    );

    // The receiver is launched only after the monitor exists, so its window
    // has somewhere to go that is not the user's screen.
    let hwnd = match receiver::launch_and_park(&monitor) {
        Ok(h) => h,
        Err(e) => {
            // launch_and_park can fail *after* activating the receiver (it
            // came up but never showed its window in time), leaving the
            // process running with a dead Miracast session the phone would
            // sit waiting on. Close it, then give the monitor back — a
            // half-started session is worse than none.
            receiver::close();
            let _ = vdd::detach_monitor();
            return Err(e);
        }
    };

    if let Err(e) = viewer::start(&app, monitor, hwnd) {
        // A viewer that timed out rather than failed may still be mid-start;
        // stop() reaps it before the receiver is pulled out from under it.
        viewer::stop();
        receiver::close();
        let _ = vdd::detach_monitor();
        return Err(e);
    }
    Ok(())
}

/// End the session and put the machine back: viewer closed, receiver gone,
/// monitor detached. Safe to call when nothing runs.
#[cfg(windows)]
#[tauri::command(async)]
pub fn dexcast_stop() -> Result<(), String> {
    viewer::stop();
    receiver::close();
    vdd::detach_monitor()
}

// ── Not Windows ─────────────────────────────────────────────────────────

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn dexcast_status(_app: tauri::AppHandle) -> Result<DexcastStatus, String> {
    Ok(DexcastStatus {
        supported: false,
        receiver_installed: false,
        driver_ready: false,
        staged: false,
        running: false,
        detail: WINDOWS_ONLY.to_string(),
    })
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn dexcast_prepare(_app: tauri::AppHandle) -> Result<String, String> {
    Err(WINDOWS_ONLY.to_string())
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn dexcast_install(_app: tauri::AppHandle) -> Result<String, String> {
    Err(WINDOWS_ONLY.to_string())
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn dexcast_uninstall(_app: tauri::AppHandle) -> Result<(), String> {
    Err(WINDOWS_ONLY.to_string())
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn dexcast_start(_app: tauri::AppHandle) -> Result<(), String> {
    Err(WINDOWS_ONLY.to_string())
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn dexcast_stop() -> Result<(), String> {
    Err(WINDOWS_ONLY.to_string())
}

/// Exit-time teardown, called from the run loop next to `scrcpy::kill_all`.
///
/// The receiver and the extra monitor belong to the *machine*, not to this
/// process — neither goes away when we die, and a desktop that keeps an
/// invisible monitor is the kind of haunting users reasonably blame us for.
///
/// Gated on our actually having a session's state up: with no session, the
/// only VDD monitor or running receiver on the box belongs to the *user*
/// (they run VDD or the Wireless Display app themselves), and tearing that
/// down on our way out would be the very haunting this is meant to prevent.
pub fn shutdown() {
    #[cfg(windows)]
    {
        if !viewer::is_running() && !vdd::we_own_monitor() {
            return;
        }
        viewer::stop();
        receiver::close();
        if let Err(e) = vdd::detach_monitor() {
            log::warn!("dexcast: monitor detach on exit failed: {e}");
        }
    }
}
