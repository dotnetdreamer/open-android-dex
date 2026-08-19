//! Win32 backend: a low-level keyboard hook.
//!
//! `WH_KEYBOARD_LL` is the only thing that can take a key away from a window
//! belonging to another process, which scrcpy's is. `RegisterHotKey` was the
//! other candidate and is wrong here: it swallows the key for the whole
//! system, so Escape would stop working in the user's editor the moment the
//! desktop went fullscreen behind it. A hook can look at who is in front and
//! decide per keystroke.
//!
//! The callback runs on the thread that installed the hook, and only while
//! that thread is pumping messages — hence a thread of our own with a message
//! loop, exactly as the touchpad reader has. Windows silently removes a hook
//! whose callback is slow (`LowLevelHooksTimeout`), so the callback does
//! nothing but compare two integers on the way to passing the key on.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use tauri::AppHandle;
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, VK_CONTROL, VK_ESCAPE, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
};
use windows_sys::Win32::System::Threading::GetCurrentThreadId;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, DispatchMessageW, GetAncestor, GetForegroundWindow, GetMessageW,
    GetWindowThreadProcessId, PostThreadMessageW, SetWindowsHookExW, UnhookWindowsHookEx, GA_ROOT,
    HC_ACTION, KBDLLHOOKSTRUCT, MSG, WH_KEYBOARD_LL, WM_KEYDOWN, WM_KEYUP, WM_QUIT, WM_SYSKEYDOWN,
    WM_SYSKEYUP,
};

/// The thread pumping the hook, as `(session, thread id)`. `(0, 0)` = none.
///
/// Keyed by session for the reason spelled out on `NEXT_SESSION` in the parent
/// module: a stop-watcher outlives the hook it was started for, and a stale one
/// must not tear down its replacement.
static HOOK: OnceLock<Mutex<(u64, u32)>> = OnceLock::new();

fn hook() -> &'static Mutex<(u64, u32)> {
    HOOK.get_or_init(|| Mutex::new((0, 0)))
}

/// Set when a key-down was swallowed, so its key-up is swallowed too.
///
/// Without it Android is handed an Escape release with no press, which is the
/// kind of half-event that makes an app's key handling behave oddly for
/// reasons nobody can reproduce.
static SWALLOW_UP: AtomicBool = AtomicBool::new(false);

pub fn start(_app: AppHandle, _key: String, session: u64, stop: Arc<AtomicBool>) {
    {
        let stop = stop.clone();
        std::thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(250));
            }
            stop_session(Some(session));
        });
    }

    std::thread::spawn(move || run(session));
}

pub fn stop() {
    stop_session(None);
}

/// End the hook thread — either whichever is current (`None`), or only the one
/// a particular session started.
fn stop_session(only: Option<u64>) {
    let tid = {
        let mut guard = match hook().lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        if only.is_some_and(|session| guard.0 != session) {
            return; // a later session owns the hook now; not ours to close
        }
        std::mem::replace(&mut *guard, (0, 0)).1
    };
    if tid != 0 {
        // A hook has no window, so the quit goes to the thread itself. Posting
        // WM_QUIT is what makes its GetMessageW return zero.
        unsafe {
            PostThreadMessageW(tid, WM_QUIT, 0, 0);
        }
    }
}

fn run(session: u64) {
    unsafe {
        // Null module handle: for a low-level hook the callback is not
        // injected into other processes, it is called back on this thread, so
        // there is no DLL for Windows to load.
        let handle = SetWindowsHookExW(WH_KEYBOARD_LL, Some(hook_proc), std::ptr::null_mut(), 0);
        if handle.is_null() {
            log::warn!(
                "hotkeys: could not install the keyboard hook ({}) — Escape will not leave \
                 fullscreen",
                std::io::Error::last_os_error()
            );
            return;
        }
        if let Ok(mut guard) = hook().lock() {
            *guard = (session, GetCurrentThreadId());
        }
        log::info!("hotkeys: Escape leaves fullscreen");

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            DispatchMessageW(&msg);
        }

        UnhookWindowsHookEx(handle);
        if let Ok(mut guard) = hook().lock() {
            if guard.0 == session {
                *guard = (0, 0);
            }
        }
        super::release_ctx(session);
        SWALLOW_UP.store(false, Ordering::SeqCst);
        log::info!("hotkeys: stopped watching for Escape");
    }
}

unsafe extern "system" fn hook_proc(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    // Documented contract: anything below HC_ACTION must be passed on
    // untouched and not inspected.
    if code != HC_ACTION as i32 {
        return CallNextHookEx(std::ptr::null_mut(), code, wparam, lparam);
    }
    let message = wparam as u32;
    let down = message == WM_KEYDOWN || message == WM_SYSKEYDOWN;
    let up = message == WM_KEYUP || message == WM_SYSKEYUP;
    // The cheap test first, and it is the one that answers for every keystroke
    // on the machine that is not Escape.
    if (down || up) && (*(lparam as *const KBDLLHOOKSTRUCT)).vkCode == VK_ESCAPE as u32 {
        if down {
            let take = !modified() && super::on_escape_down();
            SWALLOW_UP.store(take, Ordering::SeqCst);
            if take {
                return 1; // swallowed: the key never reaches scrcpy
            }
        } else if SWALLOW_UP.swap(false, Ordering::SeqCst) {
            return 1;
        }
    }
    CallNextHookEx(std::ptr::null_mut(), code, wparam, lparam)
}

/// Is a modifier held? Then this Escape is not ours.
///
/// Ctrl+Escape opens the Start menu, Ctrl+Shift+Escape opens Task Manager and
/// Alt+Escape cycles windows — all three arrive here as a plain `VK_ESCAPE`
/// (`KBDLLHOOKSTRUCT` carries no modifier state), and a hook returning nonzero
/// is precisely how those shortcuts get blocked. Swallowing them would take the
/// Start menu away from a user whose taskbar is behind a fullscreen desktop,
/// which is exactly when they need it.
///
/// `GetAsyncKeyState` rather than `GetKeyState`: the latter reports the state
/// of the *calling thread's* input queue, and a hook thread has no synchronised
/// queue of its own. Only reached on an Escape, so its cost is paid a handful
/// of times a session and never in the general keystroke path.
unsafe fn modified() -> bool {
    [VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN]
        .into_iter()
        .any(|vk| GetAsyncKeyState(vk as i32) as u16 & 0x8000 != 0)
}

/// Is the desktop's own scrcpy window the one the user is looking at?
pub fn desktop_is_foreground(app: &AppHandle, key: &str) -> bool {
    let Some(pid) = super::session_pid(app, key) else {
        return false;
    };
    unsafe {
        let fg: HWND = GetForegroundWindow();
        if fg.is_null() {
            return false;
        }
        // The root ancestor, not the focused window: this keeps working if the
        // desktop is ever hosted as a child of one of our own windows.
        let root = GetAncestor(fg, GA_ROOT);
        let root = if root.is_null() { fg } else { root };
        let mut owner = 0u32;
        GetWindowThreadProcessId(root, &mut owner);
        owner == pid
    }
}
