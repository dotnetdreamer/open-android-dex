//! A computer whose notifications are not reached yet — Linux. The phone's
//! own desktop still shows its notifications, in its corner and in its bell;
//! this computer's panel says that here they do not come up on their own.

use tauri::AppHandle;

use super::toast::{Act, From, Toast};

pub const SUPPORTED: bool = false;

/// A click on a toast: never, here.
pub struct Click {
    pub id: String,
    pub act: Act,
    pub text: Option<String>,
}

pub fn start(_app: &AppHandle, _clicked: fn(Click)) {}

pub fn system() -> (&'static str, String) {
    (
        "unavailable",
        "Your phone's notifications come up on Windows and Mac for now. On this computer, the phone's desktop shows them.".into(),
    )
}

pub fn shows_any() -> bool {
    false
}

pub fn ask(_app: &AppHandle) {}

pub fn settings_url() -> Option<&'static str> {
    None
}

pub fn show(_t: &Toast) -> Result<(), String> {
    Err("this computer's notifications are not reached yet".into())
}

pub fn hide(_id: &str, _group: &str, _from: &From) {}

pub fn clear() {}

pub fn allowed(_pkg: &str) -> bool {
    true
}
