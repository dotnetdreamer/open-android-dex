// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // A window that paints nothing is the default Linux experience otherwise.
    //
    // The webview here is WebKitGTK, and its DMABUF renderer is the fast path
    // for handing rendered frames to the compositor. On a large slice of real
    // Linux machines — the proprietary NVIDIA driver most reliably, and several
    // Wayland compositors under Xwayland — that path silently produces empty
    // buffers: the window opens at the right size, with the right background,
    // and no UI is ever drawn on it. There is no error, on stderr or anywhere
    // else, which is what makes it worth pre-empting rather than diagnosing.
    //
    // Turning it off falls back to the ordinary rendering path. That costs
    // nothing here that could be noticed: this webview draws a small control
    // panel that is idle most of the time, and the part of this product where
    // frame pacing actually matters is the phone's screen, which is scrcpy's
    // own window and not ours.
    //
    // It has to be here rather than in `run()`: the variable is read when
    // WebKit initialises, which the first webview does, so setting it after
    // GTK is already up is setting it too late. `set_var` is a safe fn on this
    // crate's edition (2021) and this is single-threaded — nothing else has
    // started yet.
    #[cfg(target_os = "linux")]
    std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");

    open_android_dex_tauri_lib::run()
}
