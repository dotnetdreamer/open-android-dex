mod adb;
mod diag;
mod embed;
mod gestures;
mod hotkeys;
mod projection;
mod scrcpy;
mod shell;
mod transfer;
mod wireless;
mod wm;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let mut builder = tauri::Builder::default();

    #[cfg(desktop)]
    {
        // note: no window-state plugin — the launcher window is a fixed-size
        // status surface; tauri.conf.json is the single source of truth.
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            use tauri::Manager;
            if let Some(win) = app.get_webview_window("main") {
                let _ = win.unminimize();
                let _ = win.set_focus();
            }
        }));
    }

    builder
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_store::Builder::new().build())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_os::init())
        // Every step of a launch is written to disk — see diag.rs for why the
        // plugin's defaults (40 KB, keep one, no adb/scrcpy detail) left a
        // failed connection with nothing to look at.
        .plugin(diag::log_plugin())
        .manage(scrcpy::MirrorState::default())
        .invoke_handler(tauri::generate_handler![
            adb::adb_version,
            adb::adb_devices,
            adb::adb_list_devices,
            adb::adb_list_apps,
            adb::adb_launch_on_display,
            adb::adb_prepare_desktop,
            adb::adb_end_desktop,
            adb::adb_reboot,
            adb::adb_connect,
            adb::adb_start_launcher,
            wireless::wireless_support,
            wireless::wireless_go_wireless,
            wireless::wireless_discover,
            wireless::wireless_pair,
            wireless::wireless_qr_begin,
            wireless::wireless_qr_poll,
            wireless::wireless_qr_cancel,
            wireless::wireless_known,
            wireless::wireless_forget,
            wireless::wireless_reconnect_known,
            wireless::wireless_disconnect,
            projection::projection_support,
            projection::projection_open_settings,
            projection::projection_install_receiver,
            projection::projection_install_command,
            scrcpy::start_mirror,
            scrcpy::stop_mirror,
            scrcpy::focus_session,
            scrcpy::list_mirror_sessions,
            embed::embed_session,
            embed::move_session,
            embed::set_session_visible,
            embed::raise_session,
            diag::diag_collect,
            diag::diag_log_path,
            diag::diag_reveal
        ])
        .setup(|app| {
            diag::log_startup(&app.handle().clone());
            // Before anything else touches the pad: if a previous run was
            // killed mid-session it left the host's own touchpad gestures
            // stood down, and this is the pass that gives them back.
            gestures::restore_host_settings(&app.handle().clone());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while running tauri application")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                log::info!("── application exiting ──");
                scrcpy::kill_all(app);
                // The reader owns a host-wide subscription and, on Windows, a
                // registry change: neither may outlive the process, and the
                // session's stop flag alone would race us to the door.
                gestures::stop_engine();
                gestures::restore_host_settings(app);
                // A keyboard hook must not outlive the process that owns its
                // callback, and on macOS neither must an event tap.
                hotkeys::stop_engine();
            }
        });
}
