//! This computer's clipboard, for the app windows: the phone's copies land in
//! it, and it goes to the phone when an app window comes to the front.

use tauri_plugin_clipboard_manager::ClipboardExt;

pub fn set(app: &tauri::AppHandle, text: &str) {
    if let Err(e) = app.clipboard().write_text(text.to_string()) {
        log::debug!("apps: cannot set this computer's clipboard: {e}");
    }
}

pub fn get(app: &tauri::AppHandle) -> Option<String> {
    app.clipboard().read_text().ok().filter(|t| !t.is_empty())
}
