//! Win32 backend: true reparenting.
//!
//! Instead of decoding the stream ourselves, each scrcpy window (spawned
//! borderless) is reparented into the Tauri main window as a native child.
//! The webview draws the window chrome around the rect; these functions
//! find, attach, move and show/hide the native child underneath it.

use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use tauri::{AppHandle, Manager};
use windows_sys::Win32::Foundation::{HWND, LPARAM, POINT, RECT};
use windows_sys::Win32::Graphics::Gdi::{
    ClientToScreen, GetMonitorInfoW, MonitorFromWindow, MONITORINFO, MONITOR_DEFAULTTONEAREST,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    BringWindowToTop, EnumWindows, GetClientRect, GetWindowLongPtrW, GetWindowPlacement,
    GetWindowRect, GetWindowTextW, GetWindowThreadProcessId, IsIconic, IsWindowVisible, IsZoomed,
    SetParent, SetWindowLongPtrW, SetWindowPlacement, SetWindowPos, ShowWindow, GWL_STYLE,
    HWND_TOP, SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_SHOWWINDOW, SW_HIDE, SW_SHOW,
    SW_SHOWMAXIMIZED, WINDOWPLACEMENT, WS_CAPTION, WS_CHILD, WS_POPUP, WS_THICKFRAME,
};

struct FindCtx {
    pid: u32,
    found: HWND,
}

unsafe extern "system" fn enum_cb(hwnd: HWND, lparam: LPARAM) -> i32 {
    let ctx = &mut *(lparam as *mut FindCtx);
    let mut pid = 0u32;
    GetWindowThreadProcessId(hwnd, &mut pid);
    if pid == ctx.pid && IsWindowVisible(hwnd) != 0 {
        let mut buf = [0u16; 4];
        if GetWindowTextW(hwnd, buf.as_mut_ptr(), buf.len() as i32) > 0 {
            ctx.found = hwnd;
            return 0; // stop enumeration
        }
    }
    1
}

/// Find the (titled, visible) top-level window belonging to a process.
fn find_window_for_pid(pid: u32) -> Option<HWND> {
    let mut ctx = FindCtx {
        pid,
        found: std::ptr::null_mut(),
    };
    unsafe {
        EnumWindows(Some(enum_cb), &mut ctx as *mut _ as LPARAM);
    }
    if ctx.found.is_null() {
        None
    } else {
        Some(ctx.found)
    }
}

/// The window to act on: the one already stored for this session, or — for a
/// desktop session, which is a standalone window and was never embedded — the
/// one found by walking the process's top-level windows.
fn window_of(pid: u32, stored: isize) -> Result<HWND, String> {
    if stored != 0 {
        return Ok(stored as HWND);
    }
    find_window_for_pid(pid).ok_or_else(|| "scrcpy window not found".into())
}

/// Reparent the session's window into the main window, retrying while scrcpy
/// is still bringing it up. Answers with the adopted `HWND`.
pub fn attach(
    app: &AppHandle,
    session_key: &str,
    pid: u32,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
) -> Result<isize, String> {
    let main = app
        .get_webview_window("main")
        .ok_or("main window missing")?;
    let parent = main.hwnd().map_err(|e| e.to_string())?.0 as HWND;

    let mut hwnd = None;
    for _ in 0..40 {
        // the SDL window appears once the first frame is decoded
        if let Some(found) = find_window_for_pid(pid) {
            hwnd = Some(found);
            break;
        }
        // bail out early if the process already died (e.g. bad package)
        if super::session(app, session_key).is_err() {
            return Err("session ended before its window appeared".into());
        }
        thread::sleep(Duration::from_millis(250));
    }
    let hwnd = hwnd.ok_or("scrcpy window did not appear within 10s")?;

    unsafe {
        let style = GetWindowLongPtrW(hwnd, GWL_STYLE);
        let style = (style as u32 & !(WS_POPUP | WS_CAPTION | WS_THICKFRAME)) | WS_CHILD;
        SetWindowLongPtrW(hwnd, GWL_STYLE, style as isize);
        SetParent(hwnd, parent);
        SetWindowPos(hwnd, HWND_TOP, x, y, w, h, SWP_SHOWWINDOW);
    }
    Ok(hwnd as isize)
}

pub fn set_frame(_pid: u32, stored: isize, x: i32, y: i32, w: i32, h: i32) -> Result<(), String> {
    if stored != 0 {
        unsafe {
            SetWindowPos(
                stored as HWND,
                HWND_TOP,
                x,
                y,
                w,
                h,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            );
        }
    }
    Ok(())
}

pub fn set_visible(_pid: u32, stored: isize, visible: bool) -> Result<(), String> {
    if stored != 0 {
        unsafe {
            ShowWindow(stored as HWND, if visible { SW_SHOW } else { SW_HIDE });
            if visible {
                BringWindowToTop(stored as HWND);
            }
        }
    }
    Ok(())
}

pub fn raise(_pid: u32, stored: isize) -> Result<(), String> {
    if stored != 0 {
        unsafe {
            BringWindowToTop(stored as HWND);
        }
    }
    Ok(())
}

/// Pre-fullscreen frames, keyed by window handle so a toggle can restore
/// exactly what it replaced. Vec because HashMap::new() is not const.
static FS_SAVED: Mutex<Vec<(isize, isize, RECT)>> = Mutex::new(Vec::new());

/// Leave fullscreen if the window is in it, and say nothing if it is not.
///
/// Separate from [`toggle_fullscreen`] because the two have different failure
/// modes and only one of them is safe to drive from a key press: a toggle
/// asked to act on a window that is already windowed puts it fullscreen, which
/// is the opposite of what Escape means. Answers the state afterwards, which
/// is always `false`.
pub fn exit_fullscreen(pid: u32, stored: isize) -> Result<bool, String> {
    let hwnd = window_of(pid, stored)?;
    let mut saved = FS_SAVED.lock().unwrap();
    restore(hwnd, &mut saved);
    Ok(false)
}

/// Put a window back the way [`toggle_fullscreen`] found it. A window with no
/// saved frame was never made fullscreen by us, and is left alone.
fn restore(hwnd: HWND, saved: &mut Vec<(isize, isize, RECT)>) -> bool {
    let hv = hwnd as isize;
    let Some(pos) = saved.iter().position(|(h, _, _)| *h == hv) else {
        return false;
    };
    let (_, style, rect) = saved.remove(pos);
    unsafe {
        SetWindowLongPtrW(hwnd, GWL_STYLE, style);
        SetWindowPos(
            hwnd,
            HWND_TOP,
            rect.left,
            rect.top,
            rect.right - rect.left,
            rect.bottom - rect.top,
            SWP_FRAMECHANGED | SWP_SHOWWINDOW,
        );
    }
    true
}

/// Toggle between borderless fullscreen (covering the monitor the window is
/// on) and the previous windowed frame.
pub fn toggle_fullscreen(pid: u32, stored: isize) -> Result<bool, String> {
    let hwnd = window_of(pid, stored)?;
    let hv = hwnd as isize;
    let mut saved = FS_SAVED.lock().unwrap();
    if restore(hwnd, &mut saved) {
        Ok(false)
    } else {
        unsafe {
            let style = GetWindowLongPtrW(hwnd, GWL_STYLE);
            let mut rect: RECT = std::mem::zeroed();
            if GetWindowRect(hwnd, &mut rect) == 0 {
                return Err("GetWindowRect failed".into());
            }
            let monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            let mut mi: MONITORINFO = std::mem::zeroed();
            mi.cbSize = std::mem::size_of::<MONITORINFO>() as u32;
            if GetMonitorInfoW(monitor, &mut mi) == 0 {
                return Err("GetMonitorInfoW failed".into());
            }
            saved.push((hv, style, rect));
            let fs_style = (style as u32) & !(WS_CAPTION | WS_THICKFRAME);
            SetWindowLongPtrW(hwnd, GWL_STYLE, fs_style as isize);
            let m = mi.rcMonitor;
            SetWindowPos(
                hwnd,
                HWND_TOP,
                m.left,
                m.top,
                m.right - m.left,
                m.bottom - m.top,
                SWP_FRAMECHANGED | SWP_SHOWWINDOW,
            );
        }
        Ok(true)
    }
}

/// Bring the process's window to the foreground.
///
/// PowerShell rather than Win32: `SetForegroundWindow` is subject to the
/// foreground lock, which silently refuses a call from a process the user did
/// not just interact with — and the caller here is a button press that happened
/// on the *phone*. `AppActivate` goes through the shell, which is allowed to.
pub fn activate(pid: u32) -> Result<(), String> {
    let mut cmd = std::process::Command::new("powershell");
    cmd.args([
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        &format!("(New-Object -ComObject WScript.Shell).AppActivate({pid})"),
    ]);
    crate::adb::hide_console(&mut cmd);
    cmd.output().map_err(|e| format!("focus failed: {e}"))?;
    Ok(())
}

/// The window's client area on the screen, its placement, and whether it is
/// maximized, minimized or made fullscreen by [`toggle_fullscreen`]. SDL takes
/// `--window-*` as the client area in pixels, which is what this answers.
///
/// The scale is the window's own dpi, as Windows scales it on the monitor it
/// is on, over 96. Per window rather than per monitor so a window straddling
/// two is answered the way Windows itself decided.
pub fn window_state(_app: &AppHandle, pid: u32) -> Option<super::WindowState> {
    use windows_sys::Win32::UI::HiDpi::GetDpiForWindow;
    let hwnd = find_window_for_pid(pid)?;
    // SAFETY: a handle EnumWindows just returned, and structs of our own; a
    // window gone since fails the calls, which is answered with None.
    unsafe {
        let mut placement: WINDOWPLACEMENT = std::mem::zeroed();
        placement.length = std::mem::size_of::<WINDOWPLACEMENT>() as u32;
        let mut client: RECT = std::mem::zeroed();
        let mut origin = POINT { x: 0, y: 0 };
        if GetWindowPlacement(hwnd, &mut placement) == 0
            || GetClientRect(hwnd, &mut client) == 0
            || ClientToScreen(hwnd, &mut origin) == 0
        {
            return None;
        }
        let dpi = GetDpiForWindow(hwnd);
        let normal = placement.rcNormalPosition;
        let fullscreen = FS_SAVED.lock().unwrap().iter().any(|(h, _, _)| *h == hwnd as isize);
        Some(super::WindowState {
            x: origin.x,
            y: origin.y,
            width: (client.right - client.left).max(0) as u32,
            height: (client.bottom - client.top).max(0) as u32,
            scale: if dpi > 0 { f64::from(dpi) / 96.0 } else { 1.0 },
            maximized: IsZoomed(hwnd) != 0,
            minimized: IsIconic(hwnd) != 0,
            fullscreen,
            restore: Some((normal.left, normal.top, normal.right, normal.bottom)),
        })
    }
}

/// Maximize the process's window once it appears, with `restore` — a
/// placement's normal rect, as [`window_state`] read it — as the frame the
/// restore button goes back to. One `SetWindowPlacement`, so the window never
/// shows at the restore size on the way.
pub fn show_maximized(pid: u32, restore: (i32, i32, i32, i32), wait: Duration) -> Result<(), String> {
    let hwnd = wait_for_window(pid, wait).ok_or("scrcpy window did not appear")?;
    let (left, top, right, bottom) = restore;
    // SAFETY: a live handle and a placement of our own.
    unsafe {
        let mut placement: WINDOWPLACEMENT = std::mem::zeroed();
        placement.length = std::mem::size_of::<WINDOWPLACEMENT>() as u32;
        if GetWindowPlacement(hwnd, &mut placement) == 0 {
            return Err("GetWindowPlacement failed".into());
        }
        placement.showCmd = SW_SHOWMAXIMIZED as u32;
        placement.rcNormalPosition = RECT { left, top, right, bottom };
        if SetWindowPlacement(hwnd, &placement) == 0 {
            return Err("SetWindowPlacement failed".into());
        }
    }
    Ok(())
}

/// The process's window, polled for until it appears or `wait` runs out —
/// scrcpy only opens it once the first frame has arrived.
fn wait_for_window(pid: u32, wait: Duration) -> Option<HWND> {
    let deadline = std::time::Instant::now() + wait;
    loop {
        if let Some(hwnd) = find_window_for_pid(pid) {
            return Some(hwnd);
        }
        if std::time::Instant::now() >= deadline {
            return None;
        }
        thread::sleep(Duration::from_millis(100));
    }
}
