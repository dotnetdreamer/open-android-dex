//! Hosts with no touchpad backend.
//!
//! Windows and macOS both have a way to read a multi-touch pad from a process
//! that is not the foreground one; nothing here is written for Linux yet, and
//! answering "no" is better than a half-implementation that reads one pad
//! vendor's device and silently misses everyone else's.
//!
//! Everything above this file is host-independent, so a Linux backend is a
//! matter of filling in these five functions with an evdev reader — the
//! recogniser, the mapping and the dispatch are already there.

use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use tauri::AppHandle;

use super::Dispatcher;

pub fn supported() -> bool {
    false
}

pub fn has_touchpad() -> bool {
    false
}

pub fn start(_app: AppHandle, _key: String, _dispatch: Dispatcher, _stop: Arc<AtomicBool>) {}

pub fn stop() {}

pub fn restore_host_settings(_app: &AppHandle) {}

/// Nothing to stand down here — see [`restore_host_settings`].
pub fn set_suppressed(_app: &AppHandle, _want: bool) {}
