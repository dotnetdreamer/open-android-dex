//! Hosts with no way to take a key from another process's window.
//!
//! Nothing is lost that was not already missing: the taskbar's ⛶ button is
//! also inert on a host where `embed/` cannot resize the scrcpy window, so
//! there is no fullscreen here for Escape to leave. F11 is not taken either,
//! which here is the better outcome: it reaches scrcpy, whose own F11
//! fullscreen (4.0+) is the only one this host has.

use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use tauri::AppHandle;

/// No hook can be installed here, so `hotkeys::start` returns before it builds
/// the machinery an installed hook would feed — see the guard in `mod.rs`.
pub fn supported() -> bool {
    false
}

pub fn start(_app: AppHandle, _key: String, _session: u64, _stop: Arc<AtomicBool>) {}

pub fn stop() {}

pub fn desktop_is_foreground(_app: &AppHandle, _key: &str) -> bool {
    false
}
