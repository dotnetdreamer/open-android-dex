//! Fallback backend for hosts with no window control of their own (Linux).
//!
//! Every entry point answers with the same explanation rather than pretending
//! to have worked. The app is otherwise fully usable: the desktop is a
//! standalone scrcpy window, and only the taskbar's ⛶ and "Show desktop"
//! buttons depend on anything here.

use tauri::AppHandle;

const UNSUPPORTED: &str = "controlling the scrcpy window is not implemented on this platform";

pub fn attach(
    _app: &AppHandle,
    _session_key: &str,
    _pid: u32,
    _x: i32,
    _y: i32,
    _w: i32,
    _h: i32,
) -> Result<isize, String> {
    Err(UNSUPPORTED.into())
}

pub fn set_frame(_pid: u32, _stored: isize, _x: i32, _y: i32, _w: i32, _h: i32) -> Result<(), String> {
    Err(UNSUPPORTED.into())
}

pub fn set_visible(_pid: u32, _stored: isize, _visible: bool) -> Result<(), String> {
    Err(UNSUPPORTED.into())
}

pub fn raise(_pid: u32, _stored: isize) -> Result<(), String> {
    Err(UNSUPPORTED.into())
}

pub fn toggle_fullscreen(_pid: u32, _stored: isize) -> Result<bool, String> {
    Err(UNSUPPORTED.into())
}

pub fn exit_fullscreen(_pid: u32, _stored: isize) -> Result<bool, String> {
    Err(UNSUPPORTED.into())
}

pub fn activate(_pid: u32) -> Result<(), String> {
    Err(UNSUPPORTED.into())
}
