//! This computer's clipboard, for the app windows (doc/apps-on-pc.md, "The
//! clipboard"): what a phone copies lands in it, and what is copied here goes
//! to every phone with a window open — as it is copied, and again when one of
//! its windows comes to the front and before a paste there.
//!
//! As it is copied, and not only at a paste, so that each side's own history
//! has what was copied on the other: Windows' (Win+V) and the phone
//! keyboard's both keep what passes through them. What a password manager
//! marks as not for other devices or histories is left here — unless it is
//! pasted into a window, which is a person handing it over.
//!
//! What the phone copies comes here too when it is a picture or a file
//! (mod.rs, `clip_files`): as files, which Finder and Explorer paste, and a
//! picture as the picture as well, on a Mac, for a paste into a document.
//! Nothing goes the other way but text.

use tauri_plugin_clipboard_manager::ClipboardExt;

pub fn set(app: &tauri::AppHandle, text: &str) {
    if let Err(e) = app.clipboard().write_text(text.to_string()) {
        log::debug!("apps: cannot set this computer's clipboard: {e}");
    }
}

pub fn get(app: &tauri::AppHandle) -> Option<String> {
    app.clipboard().read_text().ok().filter(|t| !t.is_empty())
}

/// More text than this is not sent as it is copied: the phone takes its
/// clipboard in one binder call, and a whole document is not a clip. A paste
/// into a window still takes it.
pub const MAX_WATCHED: usize = 256 * 1024;

/// Watch this computer's clipboard for the rest of the run: `changed` hears
/// each change as it happens — the text copied, or None for what is not to
/// be handed over (marked private, too long, or not text at all).
#[cfg(windows)]
pub fn watch(changed: impl Fn(Option<String>) + Send + 'static) {
    use windows::core::w;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::DataExchange::AddClipboardFormatListener;
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DispatchMessageW, GetMessageW, RegisterClassW, HWND_MESSAGE, MSG, WINDOW_EX_STYLE, WINDOW_STYLE,
        WNDCLASSW,
    };

    let _ = std::thread::Builder::new().name("apps-clipboard".into()).spawn(move || unsafe {
        CHANGED.with(|c| *c.borrow_mut() = Some(Box::new(changed)));
        let instance = GetModuleHandleW(None).unwrap_or_default();
        let class = w!("OpenAndroidDeXClipboardWatch");
        let wc = WNDCLASSW { lpfnWndProc: Some(watcher), hInstance: instance.into(), lpszClassName: class, ..Default::default() };
        RegisterClassW(&wc);
        // A window only to be told: message-only, never shown.
        let hwnd: HWND = match CreateWindowExW(
            WINDOW_EX_STYLE(0),
            class,
            None,
            WINDOW_STYLE(0),
            0,
            0,
            0,
            0,
            Some(HWND_MESSAGE),
            None,
            Some(instance.into()),
            None,
        ) {
            Ok(h) => h,
            Err(e) => {
                log::warn!("apps: this computer's clipboard goes to the phone only at a paste ({e})");
                return;
            }
        };
        if let Err(e) = AddClipboardFormatListener(hwnd) {
            log::warn!("apps: this computer's clipboard goes to the phone only at a paste ({e})");
            return;
        }
        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            DispatchMessageW(&msg);
        }
    });
}

#[cfg(windows)]
thread_local! {
    static CHANGED: std::cell::RefCell<Option<Box<dyn Fn(Option<String>)>>> = std::cell::RefCell::new(None);
}

#[cfg(windows)]
unsafe extern "system" fn watcher(
    hwnd: windows::Win32::Foundation::HWND,
    msg: u32,
    wp: windows::Win32::Foundation::WPARAM,
    lp: windows::Win32::Foundation::LPARAM,
) -> windows::Win32::Foundation::LRESULT {
    use windows::Win32::UI::WindowsAndMessaging::{DefWindowProcW, WM_CLIPBOARDUPDATE};
    unsafe {
        if msg == WM_CLIPBOARDUPDATE {
            let text = read(hwnd);
            CHANGED.with(|c| {
                if let Some(f) = c.borrow().as_ref() {
                    f(text);
                }
            });
            return windows::Win32::Foundation::LRESULT(0);
        }
        DefWindowProcW(hwnd, msg, wp, lp)
    }
}

/// The clipboard's text, unless it is marked private, too long, or not text.
#[cfg(windows)]
unsafe fn read(owner: windows::Win32::Foundation::HWND) -> Option<String> {
    use windows::Win32::System::DataExchange::{CloseClipboard, OpenClipboard};
    unsafe {
        // One at a time: whoever just wrote it may hold it a moment longer.
        let mut open = false;
        for _ in 0..10 {
            if OpenClipboard(Some(owner)).is_ok() {
                open = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(15));
        }
        if !open {
            return None;
        }
        let text = if private() { None } else { text() };
        let _ = CloseClipboard();
        text.filter(|t| !t.is_empty() && t.len() <= MAX_WATCHED)
    }
}

/// Marked by whoever copied it as not for monitors, histories or other
/// devices — what password managers do, and what Windows' own clipboard
/// history and its sync to other devices obey. A format's presence, or a
/// DWORD of 0 in it.
#[cfg(windows)]
unsafe fn private() -> bool {
    use windows::core::w;
    use windows::Win32::Foundation::HGLOBAL;
    use windows::Win32::System::DataExchange::{GetClipboardData, IsClipboardFormatAvailable, RegisterClipboardFormatW};
    use windows::Win32::System::Memory::{GlobalLock, GlobalUnlock};
    unsafe {
        if IsClipboardFormatAvailable(RegisterClipboardFormatW(w!("ExcludeClipboardContentFromMonitorProcessing"))).is_ok() {
            return true;
        }
        for name in [w!("CanIncludeInClipboardHistory"), w!("CanUploadToCloudClipboard")] {
            let format = RegisterClipboardFormatW(name);
            if IsClipboardFormatAvailable(format).is_err() {
                continue;
            }
            let Ok(h) = GetClipboardData(format) else { continue };
            let g = HGLOBAL(h.0);
            let p = GlobalLock(g) as *const u32;
            if p.is_null() {
                continue;
            }
            let allowed = p.read_unaligned() != 0;
            let _ = GlobalUnlock(g);
            if !allowed {
                return true;
            }
        }
        false
    }
}

#[cfg(windows)]
unsafe fn text() -> Option<String> {
    use windows::Win32::Foundation::HGLOBAL;
    use windows::Win32::System::DataExchange::{GetClipboardData, IsClipboardFormatAvailable};
    use windows::Win32::System::Memory::{GlobalLock, GlobalSize, GlobalUnlock};
    /// WinUser.h's, which the windows crate files under its Ole module.
    const CF_UNICODETEXT: u32 = 13;
    unsafe {
        IsClipboardFormatAvailable(CF_UNICODETEXT).ok()?;
        let g = HGLOBAL(GetClipboardData(CF_UNICODETEXT).ok()?.0);
        let p = GlobalLock(g) as *const u16;
        if p.is_null() {
            return None;
        }
        let units = std::slice::from_raw_parts(p, GlobalSize(g) / 2);
        let len = units.iter().position(|&c| c == 0).unwrap_or(units.len());
        let text = String::from_utf16_lossy(&units[..len]);
        let _ = GlobalUnlock(g);
        Some(text)
    }
}

/// The Mac's clipboard's count of changes when files from the phone were put
/// there: that change is the phone's own, not one to hand back to it.
#[cfg(target_os = "macos")]
static OWN_CHANGE: std::sync::atomic::AtomicIsize = std::sync::atomic::AtomicIsize::new(-1);

/// Files the phone copied, on the Mac's clipboard: each as a file, which
/// Finder pastes — and a single picture as the picture too, in the same
/// item, which a document, a message or a mail takes instead.
#[cfg(target_os = "macos")]
pub fn set_files(paths: &[std::path::PathBuf]) -> Result<(), String> {
    use objc2::rc::Retained;
    use objc2::runtime::ProtocolObject;
    use objc2_app_kit::{NSPasteboard, NSPasteboardItem, NSPasteboardTypeFileURL, NSPasteboardWriting};
    use objc2_foundation::{NSArray, NSData, NSString, NSURL};

    objc2::rc::autoreleasepool(|_| {
        let board = NSPasteboard::generalPasteboard();
        let mut items: Vec<Retained<ProtocolObject<dyn NSPasteboardWriting>>> = Vec::new();
        for p in paths {
            let url = NSURL::fileURLWithPath(&NSString::from_str(&p.to_string_lossy()));
            let item = NSPasteboardItem::new();
            let Some(text) = url.absoluteString() else { continue };
            item.setString_forType(&text, unsafe { NSPasteboardTypeFileURL });
            if let (1, Some(uti)) = (paths.len(), picture_type(p)) {
                if let Ok(bytes) = std::fs::read(p) {
                    item.setData_forType(&NSData::with_bytes(&bytes), &NSString::from_str(uti));
                }
            }
            items.push(ProtocolObject::from_retained(item));
        }
        if items.is_empty() {
            return Err("no files".into());
        }
        board.clearContents();
        if !board.writeObjects(&NSArray::from_retained_slice(&items)) {
            return Err("the pasteboard refused them".into());
        }
        OWN_CHANGE.store(board.changeCount(), std::sync::atomic::Ordering::SeqCst);
        Ok(())
    })
}

/// A picture's type, by its extension, for those a Mac's apps paste as pictures.
#[cfg(target_os = "macos")]
fn picture_type(p: &std::path::Path) -> Option<&'static str> {
    let ext = p.extension()?.to_string_lossy().to_ascii_lowercase();
    Some(match ext.as_str() {
        "jpg" | "jpeg" => "public.jpeg",
        "png" => "public.png",
        "heic" | "heif" => "public.heic",
        "gif" => "com.compuserve.gif",
        "tif" | "tiff" => "public.tiff",
        "webp" => "org.webmproject.webp",
        _ => return None,
    })
}

/// Files the phone copied, on Windows' clipboard: the shell's own data
/// object for them — what Explorer itself puts there for a copy, so a paste
/// in Explorer, or into any app that takes files, is the files. Flushed, so
/// the clipboard holds them itself and no thread of ours must answer for it.
#[cfg(windows)]
pub fn set_files(paths: &[std::path::PathBuf]) -> Result<(), String> {
    use windows::Win32::System::Ole::{OleFlushClipboard, OleInitialize, OleSetClipboard, OleUninitialize};
    let paths = paths.to_vec();
    // OLE's own thread: the clipboard's calls want an apartment of their own.
    std::thread::spawn(move || unsafe {
        OleInitialize(None).map_err(|e| e.to_string())?;
        let set = shell_data_object(&paths).and_then(|data| {
            when_free(|| OleSetClipboard(&data))?;
            when_free(|| OleFlushClipboard())
        });
        OleUninitialize();
        set.map_err(|e| e.to_string())
    })
    .join()
    .unwrap_or_else(|_| Err("the clipboard's thread failed".into()))
}

/// `f` on the clipboard, again for up to half a second while another app has
/// it open — one reading what changed there, as every clipboard watcher does
/// (CLIPBRD_E_CANT_OPEN, seen on Windows 11 with the phone's copied picture).
/// Meanwhile what is asked of this thread is answered: a watcher that opened
/// the clipboard between the set and the flush waits on OLE's window here for
/// the files themselves (WM_RENDERFORMAT), and keeps the clipboard open until
/// it has them. Left unanswered, a clipboard manager failed a third of the copies.
#[cfg(windows)]
fn when_free(f: impl Fn() -> windows::core::Result<()>) -> windows::core::Result<()> {
    use windows::Win32::Foundation::CLIPBRD_E_CANT_OPEN;
    use windows::Win32::UI::WindowsAndMessaging::{
        MsgWaitForMultipleObjects, PeekMessageW, MSG, PM_NOREMOVE, PM_QS_SENDMESSAGE, QS_SENDMESSAGE,
    };
    let until = std::time::Instant::now() + std::time::Duration::from_millis(500);
    loop {
        match f() {
            Err(e) if e.code() == CLIPBRD_E_CANT_OPEN && std::time::Instant::now() < until => unsafe {
                // 25 ms, or less if something is sent here: a peek delivers it, and takes nothing off the queue.
                MsgWaitForMultipleObjects(None, false, 25, QS_SENDMESSAGE);
                let _ = PeekMessageW(&mut MSG::default(), None, 0, 0, PM_NOREMOVE | PM_QS_SENDMESSAGE);
            },
            done => return done,
        }
    }
}

/// The shell's own data object for `paths` — files as Explorer hands them
/// over, to a drop or a paste: the file list, the shell's own list, and the rest.
/// They are one offer's files, so in one folder: any elsewhere are left out.
#[cfg(windows)]
pub(super) unsafe fn shell_data_object(
    paths: &[std::path::PathBuf],
) -> windows::core::Result<windows::Win32::System::Com::IDataObject> {
    use windows::core::HSTRING;
    use windows::Win32::System::Com::IDataObject;
    use windows::Win32::UI::Shell::Common::ITEMIDLIST;
    use windows::Win32::UI::Shell::{ILFindLastID, ILFree, SHCreateDataObject, SHParseDisplayName};
    let Some(dir) = paths.first().and_then(|p| p.parent()) else {
        return Err(windows::core::Error::empty());
    };
    unsafe {
        // The folder, and each file as a child of it: given whole paths and no
        // folder, the shell's data object offers nothing at all — no file list,
        // so Explorer refuses the drop and a paste pastes nothing.
        let mut folder: *mut ITEMIDLIST = std::ptr::null_mut();
        SHParseDisplayName(&HSTRING::from(dir.as_os_str()), None, &mut folder, 0, None)?;
        let mut pidls: Vec<*const ITEMIDLIST> = Vec::new();
        let mut failed = None;
        for p in paths.iter().filter(|p| p.parent() == Some(dir)) {
            let mut pidl: *mut ITEMIDLIST = std::ptr::null_mut();
            match SHParseDisplayName(&HSTRING::from(p.as_os_str()), None, &mut pidl, 0, None) {
                Ok(()) => pidls.push(pidl),
                Err(e) => failed = Some(e),
            }
        }
        // Each child is the last part of its whole path, held in it until the data object has copied it.
        let children: Vec<*const ITEMIDLIST> = pidls.iter().map(|&p| ILFindLastID(p) as *const ITEMIDLIST).collect();
        let made = if children.is_empty() {
            Err(failed.unwrap_or_else(windows::core::Error::empty))
        } else {
            SHCreateDataObject::<_, IDataObject>(Some(folder), Some(&children), None::<&IDataObject>)
        };
        for p in pidls {
            ILFree(Some(p));
        }
        ILFree(Some(folder));
        made
    }
}

/// A Mac says nothing when its clipboard changes; its count of changes is
/// asked twice a second instead, which costs nothing it would notice.
#[cfg(target_os = "macos")]
pub fn watch(changed: impl Fn(Option<String>) + Send + 'static) {
    use objc2_app_kit::{NSPasteboard, NSPasteboardTypeString};

    let _ = std::thread::Builder::new().name("apps-clipboard".into()).spawn(move || {
        let mut seen = objc2::rc::autoreleasepool(|_| NSPasteboard::generalPasteboard().changeCount());
        loop {
            std::thread::sleep(std::time::Duration::from_millis(500));
            // Some(the change), whose text is None when it is not to be handed over.
            let change = objc2::rc::autoreleasepool(|_| {
                let board = NSPasteboard::generalPasteboard();
                let now = board.changeCount();
                if now == seen {
                    return None;
                }
                seen = now;
                // Files the phone copied, put there by this app: nothing to hand back.
                if now == OWN_CHANGE.load(std::sync::atomic::Ordering::SeqCst) {
                    return None;
                }
                // What 1Password and its kind mark concealed or passing
                // (nspasteboard.org): not for other devices, nor histories.
                let private = board.types().is_some_and(|types| {
                    types.iter().any(|t| {
                        let t = t.to_string();
                        t == "org.nspasteboard.ConcealedType" || t == "org.nspasteboard.TransientType"
                    })
                });
                if private {
                    return Some(None);
                }
                Some(board.stringForType(unsafe { NSPasteboardTypeString }).map(|s| s.to_string()))
            });
            if let Some(text) = change {
                changed(text.filter(|t| !t.is_empty() && t.len() <= MAX_WATCHED));
            }
        }
    });
}

/// A Linux computer's app windows are scrcpy's, which carries the clipboard itself.
#[cfg(not(any(windows, target_os = "macos")))]
pub fn watch(_changed: impl Fn(Option<String>) + Send + 'static) {}
