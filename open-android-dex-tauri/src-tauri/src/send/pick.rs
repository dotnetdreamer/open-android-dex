//! The system's own folder chooser, for Keep a copy's Change…: an
//! NSOpenPanel on a Mac, the shell's IFileOpenDialog on Windows, zenity or
//! kdialog on Linux. Written here rather than taken from tauri-plugin-dialog,
//! which would have moved other packages in Cargo.lock (doc/send-to-computer.md).

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use tauri::AppHandle;

/// A chooser is open: Change… pressed again brings no second one.
static OPEN: AtomicBool = AtomicBool::new(false);

/// A folder the person chose, starting at `start`; None if they cancelled.
pub fn folder(app: &AppHandle, start: Option<&Path>) -> Result<Option<PathBuf>, String> {
    if OPEN.swap(true, Ordering::SeqCst) {
        return Err("The folder chooser is open already.".into());
    }
    let chosen = imp::folder(app, start);
    OPEN.store(false, Ordering::SeqCst);
    chosen
}

#[cfg(target_os = "macos")]
mod imp {
    use super::*;
    use objc2::MainThreadMarker;
    use objc2_app_kit::{NSModalResponseOK, NSOpenPanel};
    use objc2_foundation::{NSString, NSURL};

    pub fn folder(app: &AppHandle, start: Option<&Path>) -> Result<Option<PathBuf>, String> {
        let (tx, rx) = std::sync::mpsc::channel();
        let start = start.map(|p| p.to_string_lossy().into_owned());
        app.run_on_main_thread(move || {
            let Some(mtm) = MainThreadMarker::new() else {
                let _ = tx.send(None);
                return;
            };
            let panel = NSOpenPanel::openPanel(mtm);
            panel.setCanChooseDirectories(true);
            panel.setCanChooseFiles(false);
            panel.setAllowsMultipleSelection(false);
            panel.setCanCreateDirectories(true);
            panel.setPrompt(Some(&NSString::from_str("Choose")));
            panel.setMessage(Some(&NSString::from_str("Choose where to save your phone's photos.")));
            if let Some(s) = &start {
                let url = NSURL::fileURLWithPath(&NSString::from_str(s));
                panel.setDirectoryURL(Some(&url));
            }
            let chosen = if panel.runModal() == NSModalResponseOK {
                panel.URL().and_then(|u| u.path()).map(|p| PathBuf::from(p.to_string()))
            } else {
                None
            };
            let _ = tx.send(chosen);
        })
        .map_err(|e| e.to_string())?;
        rx.recv().map_err(|_| "The folder chooser didn't open.".to_string())
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use windows::core::HSTRING;
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED};
    use windows::Win32::UI::Shell::{
        FileOpenDialog, IFileOpenDialog, IShellItem, SHCreateItemFromParsingName, FOS_FORCEFILESYSTEM, FOS_PICKFOLDERS, SIGDN_FILESYSPATH,
    };

    pub fn folder(app: &AppHandle, start: Option<&Path>) -> Result<Option<PathBuf>, String> {
        use tauri::Manager;
        let start = start.map(|p| p.to_path_buf());
        // Owned by the app's window, so it stays in front of it and the window waits for it.
        let owner = app.get_webview_window("main").and_then(|w| w.hwnd().ok()).map(|h| h.0 as isize);
        // A thread of its own, its own apartment: it blocks only that thread.
        std::thread::spawn(move || unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let r = (|| -> windows::core::Result<Option<PathBuf>> {
                let dialog: IFileOpenDialog = CoCreateInstance(&FileOpenDialog, None, CLSCTX_INPROC_SERVER)?;
                let options = dialog.GetOptions()?;
                dialog.SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM)?;
                dialog.SetTitle(&HSTRING::from("Choose where to save your phone's photos"))?;
                if let Some(s) = &start {
                    if let Ok(item) = SHCreateItemFromParsingName::<_, _, IShellItem>(&HSTRING::from(s.as_os_str()), None) {
                        let _ = dialog.SetFolder(&item);
                    }
                }
                let owner = owner.map(|h| windows::Win32::Foundation::HWND(h as *mut core::ffi::c_void));
                if dialog.Show(owner).is_err() {
                    return Ok(None);
                }
                let item = dialog.GetResult()?;
                let name = item.GetDisplayName(SIGDN_FILESYSPATH)?;
                let path = name.to_string().ok().map(PathBuf::from);
                CoTaskMemFree(Some(name.0 as *const _));
                Ok(path)
            })();
            CoUninitialize();
            r.map_err(|e| e.to_string())
        })
        .join()
        .map_err(|_| "The folder chooser didn't open.".to_string())?
    }
}

#[cfg(not(any(windows, target_os = "macos")))]
mod imp {
    use super::*;

    /// zenity (GNOME and most others), else kdialog (KDE). Not run here: no Linux computer was at hand.
    pub fn folder(_app: &AppHandle, start: Option<&Path>) -> Result<Option<PathBuf>, String> {
        let start = start.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
        let tries: [(&str, Vec<String>); 2] = [
            ("zenity", vec!["--file-selection".into(), "--directory".into(), format!("--filename={start}/")]),
            ("kdialog", vec!["--getexistingdirectory".into(), start.clone()]),
        ];
        for (cmd, args) in tries {
            match std::process::Command::new(cmd).args(&args).output() {
                Ok(out) if out.status.success() => {
                    let p = String::from_utf8_lossy(&out.stdout).trim().to_string();
                    return Ok((!p.is_empty()).then(|| PathBuf::from(p)));
                }
                // One that could not reach the screen: the next is tried. Exit 1 is a cancel, whatever
                // GTK printed beside it (it prints warnings for a dialog that worked).
                Ok(out) if no_display(&out.stderr) => log::warn!("send: {cmd} could not open: {}", String::from_utf8_lossy(&out.stderr).trim()),
                Ok(out) if out.status.code() == Some(1) => return Ok(None),
                Ok(out) => return Err(format!("The folder chooser failed: {}", String::from_utf8_lossy(&out.stderr).trim())),
                // Not there: the next is tried.
                Err(_) => {}
            }
        }
        Err("This computer has no folder chooser that opens (zenity or kdialog).".into())
    }

    fn no_display(stderr: &[u8]) -> bool {
        String::from_utf8_lossy(stderr).to_ascii_lowercase().contains("cannot open display")
    }
}
