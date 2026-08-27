//! Windows' Wireless Display receiver, treated as a component we place.
//!
//! The receiver is a UWP app (package `Microsoft.PPIProjection`, process
//! `Receiver.exe`), which dictates all three quirks this file works around:
//!
//! * it cannot be started by running its exe — that dies with 0xc0000409
//!   before main; it must be *activated*, and `explorer.exe
//!   shell:AppsFolder\<AUMID>` is the activation route that needs no COM;
//! * its top-level window belongs to a different process,
//!   `ApplicationFrameHost.exe` (class `ApplicationFrameWindow`) — the app's
//!   own `Windows.UI.Core.CoreWindow` sits inside as a child, and matching
//!   *that* child's pid is the only reliable way to tell the receiver's frame
//!   from every other UWP window on the machine;
//! * `Receiver.exe` is not a unique name (Citrix ships one), so pids are
//!   confirmed against the SystemApps path, not the file name.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use windows::core::BOOL;
use windows::Win32::Foundation::{CloseHandle, HWND, LPARAM, RECT};
use windows::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
    TH32CS_SNAPPROCESS,
};
use windows::Win32::System::Threading::{
    OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
    PROCESS_QUERY_LIMITED_INFORMATION,
};
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, FindWindowExW, GetClassNameW, GetWindowLongPtrW, GetWindowRect,
    GetWindowThreadProcessId, IsWindow, PostMessageW, SetWindowLongPtrW, SetWindowPos,
    ShowWindow, GWL_STYLE, HWND_TOP, SWP_FRAMECHANGED, SWP_SHOWWINDOW, SW_RESTORE, WM_CLOSE,
    WS_CAPTION, WS_THICKFRAME,
};

use super::vdd::MonitorRect;

/// App activation id for the receiver, constant across Windows 10 and 11 —
/// the Win11 rename to "Wireless Display" changed the label, not the package.
const RECEIVER_AUMID: &str =
    "shell:AppsFolder\\Microsoft.PPIProjection_cw5n1h2txyewy!Microsoft.PPIProjection";

/// Where the FoD payload lands. Present ⇔ the capability is installed, which
/// is the only admin-free way to ask (`Get-WindowsCapability` elevates).
fn receiver_exe() -> PathBuf {
    let root = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    PathBuf::from(root)
        .join("SystemApps")
        .join("Microsoft.PPIProjection_cw5n1h2txyewy")
        .join("Receiver.exe")
}

pub fn installed() -> bool {
    receiver_exe().exists()
}

/// The pid of a running receiver, confirmed by image path.
fn receiver_pid() -> Option<u32> {
    let want = receiver_exe().to_string_lossy().to_ascii_lowercase();
    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0).ok()?;
        let mut entry = PROCESSENTRY32W {
            dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32,
            ..Default::default()
        };
        let mut found = None;
        if Process32FirstW(snap, &mut entry).is_ok() {
            loop {
                let name = super::vdd::wide_str(&entry.szExeFile);
                if name.eq_ignore_ascii_case("Receiver.exe") {
                    if let Ok(proc) =
                        OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, entry.th32ProcessID)
                    {
                        let mut buf = [0u16; 512];
                        let mut len = buf.len() as u32;
                        let ok = QueryFullProcessImageNameW(
                            proc,
                            PROCESS_NAME_WIN32,
                            windows::core::PWSTR(buf.as_mut_ptr()),
                            &mut len,
                        )
                        .is_ok();
                        let _ = CloseHandle(proc);
                        if ok {
                            let path =
                                String::from_utf16_lossy(&buf[..len as usize]).to_ascii_lowercase();
                            if path == want {
                                found = Some(entry.th32ProcessID);
                                break;
                            }
                        }
                    }
                }
                if Process32NextW(snap, &mut entry).is_err() {
                    break;
                }
            }
        }
        let _ = CloseHandle(snap);
        found
    }
}

struct FindFrame {
    receiver_pid: u32,
    found: HWND,
}

extern "system" fn enum_frames(hwnd: HWND, lparam: LPARAM) -> BOOL {
    unsafe {
        let ctx = &mut *(lparam.0 as *mut FindFrame);
        let mut class = [0u16; 64];
        let n = GetClassNameW(hwnd, &mut class);
        if n > 0 && String::from_utf16_lossy(&class[..n as usize]) == "ApplicationFrameWindow" {
            // The frame is ApplicationFrameHost's; the app is the CoreWindow
            // child. Match the child's owner.
            let core = FindWindowExW(
                Some(hwnd),
                None,
                windows::core::w!("Windows.UI.Core.CoreWindow"),
                None,
            );
            if let Ok(core) = core {
                let mut pid = 0u32;
                GetWindowThreadProcessId(core, Some(&mut pid));
                if pid == ctx.receiver_pid {
                    ctx.found = hwnd;
                    return BOOL(0);
                }
            }
        }
        BOOL(1)
    }
}

fn find_frame_window(pid: u32) -> Option<HWND> {
    let mut ctx = FindFrame {
        receiver_pid: pid,
        found: HWND::default(),
    };
    unsafe {
        // Stopping the enumeration early makes EnumWindows report failure;
        // the out-param is the answer either way.
        let _ = EnumWindows(Some(enum_frames), LPARAM(&mut ctx as *mut _ as isize));
    }
    if ctx.found.is_invalid() {
        None
    } else {
        Some(ctx.found)
    }
}

/// Launch (or adopt) the receiver and park it fullscreen on the monitor.
/// Answers the frame window handle for the viewer's portal to focus later.
pub fn launch_and_park(monitor: &MonitorRect) -> Result<isize, String> {
    if receiver_pid().is_none() {
        log::info!("dexcast: activating the Wireless Display receiver");
        let mut cmd = Command::new("explorer.exe");
        cmd.arg(RECEIVER_AUMID)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        crate::adb::hide_console(&mut cmd);
        // explorer hands the activation off and exits at once; its status
        // says nothing about whether the app came up — the wait below does.
        cmd.spawn().map_err(|e| format!("could not launch the receiver: {e}"))?;
    }

    // Process first, then window: the frame appears a beat after the pid,
    // and both a cold app start and a warm one pass through here.
    let deadline = Instant::now() + Duration::from_secs(15);
    let (pid, frame) = loop {
        if let Some(pid) = receiver_pid() {
            if let Some(frame) = find_frame_window(pid) {
                break (pid, frame);
            }
        }
        if Instant::now() >= deadline {
            return Err(
                "The Wireless Display receiver did not open a window within 15 seconds. \
                 Try opening it once from the Start menu — a first run can be slow."
                    .into(),
            );
        }
        std::thread::sleep(Duration::from_millis(300));
    };
    log::info!("dexcast: receiver pid {pid}, frame {:?}", frame.0);

    unsafe {
        // A minimised window keeps its restored geometry through SetWindowPos
        // and stays tiny; restore first.
        let _ = ShowWindow(frame, SW_RESTORE);
        // Borderless-on-the-monitor, same recipe as embed's fullscreen: the
        // captured monitor should hold only the receiver's content, not a
        // caption bar that would arrive inside the DeX picture.
        let style = GetWindowLongPtrW(frame, GWL_STYLE);
        let fs_style = (style as u32) & !(WS_CAPTION.0 | WS_THICKFRAME.0);
        SetWindowLongPtrW(frame, GWL_STYLE, fs_style as isize);
        SetWindowPos(
            frame,
            Some(HWND_TOP),
            monitor.x,
            monitor.y,
            monitor.w,
            monitor.h,
            SWP_FRAMECHANGED | SWP_SHOWWINDOW,
        )
        .map_err(|e| format!("could not move the receiver onto the virtual monitor: {e}"))?;

        // ApplicationFrameHost sometimes reasserts geometry a moment later
        // (it proxies state to the inner CoreWindow); check once and repeat
        // the placement if it moved — second time sticks in practice.
        std::thread::sleep(Duration::from_millis(500));
        let mut rect = RECT::default();
        if GetWindowRect(frame, &mut rect).is_ok()
            && (rect.left != monitor.x || rect.top != monitor.y)
        {
            let _ = SetWindowPos(
                frame,
                Some(HWND_TOP),
                monitor.x,
                monitor.y,
                monitor.w,
                monitor.h,
                SWP_FRAMECHANGED | SWP_SHOWWINDOW,
            );
        }
    }
    Ok(frame.0 as isize)
}

/// Close the receiver, gracefully first.
///
/// WM_CLOSE lets the app end the Miracast session properly, which is what
/// makes the phone show its "disconnected" state instead of a frozen frame
/// and a timeout. The kill is only for a receiver that stopped listening.
pub fn close() {
    let Some(pid) = receiver_pid() else { return };
    if let Some(frame) = find_frame_window(pid) {
        unsafe {
            let _ = PostMessageW(Some(frame), WM_CLOSE, Default::default(), Default::default());
        }
        let deadline = Instant::now() + Duration::from_secs(3);
        while Instant::now() < deadline {
            if receiver_pid().is_none() {
                return;
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        // The frame may survive as a ghost; only force things if the process
        // is genuinely still there.
        if let Some(frame) = find_frame_window(pid) {
            if unsafe { IsWindow(Some(frame)) }.as_bool() {
                log::warn!("dexcast: receiver ignored WM_CLOSE, killing pid {pid}");
            }
        }
    }
    let mut cmd = Command::new("taskkill");
    cmd.args(["/PID", &pid.to_string(), "/F"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    crate::adb::hide_console(&mut cmd);
    let _ = cmd.status();
}
