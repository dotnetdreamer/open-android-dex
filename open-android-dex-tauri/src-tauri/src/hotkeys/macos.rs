//! macOS backend: an event tap on key-down.
//!
//! The same instrument the touchpad reader uses, and a separate tap rather
//! than a wider mask on that one: gestures only run where a trackpad is being
//! read, while Escape has to work on a Mac with a mouse. Two masked taps cost
//! the system nothing it would not spend on one — events that do not match are
//! never delivered.
//!
//! Needs Accessibility, which is not an extra ask here: leaving fullscreen on
//! macOS goes through the Accessibility API too (`embed/macos.rs`), so a
//! machine that cannot create this tap could not have left fullscreen from the
//! taskbar either.

use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use objc2_app_kit::NSWorkspace;
use objc2_core_foundation::{kCFRunLoopCommonModes, CFMachPort, CFRetained, CFRunLoop};
use objc2_core_graphics::{
    CGEvent, CGEventField, CGEventFlags, CGEventTapLocation, CGEventTapOptions,
    CGEventTapPlacement, CGEventTapProxy, CGEventType,
};
use tauri::AppHandle;

#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    /// Deliberately the variant that raises no dialog — see [`start`].
    fn AXIsProcessTrusted() -> u8;
}

/// The virtual key code for Escape on every Mac keyboard layout — it is a
/// hardware position, not a character, so it does not move with the layout.
const KEY_ESCAPE: i64 = 53;

/// The modifiers that make Escape somebody else's key.
///
/// Command, Control, Option and Shift only: Caps Lock, Fn and the numeric-pad
/// bit ride along on ordinary keystrokes and would veto every press.
const MODIFIERS: CGEventFlags = CGEventFlags(
    CGEventFlags::MaskCommand.0
        | CGEventFlags::MaskControl.0
        | CGEventFlags::MaskAlternate.0
        | CGEventFlags::MaskShift.0,
);

/// The run loop the tap is pumping on, as `(session, pointer)`. `(0, 0)` = none.
///
/// A raw pointer with one retain deliberately leaked, for the reason spelled
/// out in `gestures/macos.rs`: `CFRunLoop` is neither `Send` nor `Sync`, and a
/// run loop dies with its thread, so stopping one from outside is only sound
/// while something is holding it alive. Keyed by session for the reason
/// spelled out on `NEXT_SESSION` in the parent module.
static LOOP: OnceLock<Mutex<(u64, usize)>> = OnceLock::new();

fn tap_loop() -> &'static Mutex<(u64, usize)> {
    LOOP.get_or_init(|| Mutex::new((0, 0)))
}

/// Set when a key-down was swallowed, so its key-up is swallowed too — see the
/// same flag in the Windows backend.
static SWALLOW_UP: AtomicBool = AtomicBool::new(false);

/// The tap's own port, so the callback can switch it back on after the system
/// disables it for being slow.
///
/// Handed to the callback through its `user_info` rather than kept in a
/// `static`, and not by preference: `CFMachPort` is neither `Send` nor `Sync`,
/// so a `static` holding one does not compile. Boxed and reached by pointer is
/// what the trackpad tap does too.
struct TapState {
    tap: Option<CFRetained<CFMachPort>>,
}

pub fn start(_app: AppHandle, _key: String, session: u64, stop: Arc<AtomicBool>) {
    // Asked before creating the tap, and this is not belt and braces. An
    // active tap IS an Accessibility request: on an untrusted process
    // CGEventTapCreate returns null AND puts the system's permission dialog up.
    // macOS offers that dialog once. Spending it here — unprompted, at session
    // start, with nothing on screen to explain it — would leave the ⛶ button
    // silently unable to ask for the grant it actually needs.
    if unsafe { AXIsProcessTrusted() } == 0 {
        log::info!(
            "hotkeys: not trusted for Accessibility — Escape will not leave fullscreen until \
             the grant is given (the ⛶ button asks for it)"
        );
        return;
    }
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

/// End the tap's run loop — either whichever is current (`None`), or only the
/// one a particular session started.
fn stop_session(only: Option<u64>) {
    let handle = {
        let mut guard = match tap_loop().lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        if only.is_some_and(|session| guard.0 != session) {
            return; // a later session owns the tap now; not ours to close
        }
        std::mem::replace(&mut *guard, (0, 0)).1
    };
    if handle != 0 {
        unsafe { &*(handle as *const CFRunLoop) }.stop();
    }
}

fn run(session: u64) {
    // Lives as long as the run loop below, which is what the callback's
    // pointer to it depends on.
    let mut state = Box::new(TapState { tap: None });
    let user_info = state.as_mut() as *mut TapState as *mut c_void;

    let Some(tap) = (unsafe {
        CGEvent::tap_create(
            CGEventTapLocation::SessionEventTap,
            CGEventTapPlacement::HeadInsertEventTap,
            // Default, not ListenOnly: the whole point is to be able to take
            // the key away from scrcpy.
            CGEventTapOptions::Default,
            (1u64 << CGEventType::KeyDown.0) | (1u64 << CGEventType::KeyUp.0),
            Some(tap_callback),
            user_info,
        )
    }) else {
        log::warn!(
            "hotkeys: could not create the keyboard event tap — grant Accessibility under \
             System Settings > Privacy & Security to let Escape leave fullscreen"
        );
        return;
    };
    state.tap = Some(tap.clone());

    let Some(source) = CFMachPort::new_run_loop_source(None, Some(&tap), 0) else {
        log::warn!("hotkeys: could not attach the keyboard tap to a run loop");
        return;
    };
    let Some(run_loop) = CFRunLoop::current() else {
        log::warn!("hotkeys: no run loop on the keyboard tap thread");
        return;
    };
    unsafe {
        run_loop.add_source(Some(&source), kCFRunLoopCommonModes);
    }
    CGEvent::tap_enable(&tap, true);
    let handle = CFRetained::as_ptr(&run_loop).as_ptr() as usize;
    if let Ok(mut guard) = tap_loop().lock() {
        *guard = (session, handle);
    }
    std::mem::forget(run_loop.clone());
    log::info!("hotkeys: Escape leaves fullscreen");

    CFRunLoop::run();

    // Detach before anything the callback reads can go away.
    CGEvent::tap_enable(&tap, false);
    unsafe {
        run_loop.remove_source(Some(&source), kCFRunLoopCommonModes);
    }
    tap.invalidate();
    if let Ok(mut guard) = tap_loop().lock() {
        if guard.0 == session {
            *guard = (0, 0);
        }
    }
    super::release_ctx(session);
    SWALLOW_UP.store(false, Ordering::SeqCst);
    log::info!("hotkeys: stopped watching for Escape");
}

unsafe extern "C-unwind" fn tap_callback(
    _proxy: CGEventTapProxy,
    event_type: CGEventType,
    event: NonNull<CGEvent>,
    user_info: *mut c_void,
) -> *mut CGEvent {
    let pass = event.as_ptr();

    if event_type == CGEventType::TapDisabledByTimeout
        || event_type == CGEventType::TapDisabledByUserInput
    {
        // Re-arm rather than give up. A tap the system switched off stays off
        // forever otherwise, and "Escape worked, then quietly stopped" is a
        // very hard thing for a user to report.
        log::warn!("hotkeys: the keyboard tap was disabled by the system; re-enabling");
        let state = user_info as *mut TapState;
        if !state.is_null() {
            if let Some(tap) = unsafe { (*state).tap.as_ref() } {
                CGEvent::tap_enable(tap, true);
            }
        }
        return pass;
    }

    let down = event_type == CGEventType::KeyDown;
    let up = event_type == CGEventType::KeyUp;
    if !down && !up {
        return pass;
    }
    let code = CGEvent::integer_value_field(
        Some(unsafe { event.as_ref() }),
        CGEventField::KeyboardEventKeycode,
    );
    if code != KEY_ESCAPE {
        return pass;
    }
    // Command-Escape and friends belong to the system, and to the user's own
    // shortcuts. Only a bare Escape is ours.
    if CGEvent::flags(Some(unsafe { event.as_ref() })).intersects(MODIFIERS) {
        return pass;
    }
    if down {
        let take = super::on_escape_down();
        SWALLOW_UP.store(take, Ordering::SeqCst);
        if take {
            return std::ptr::null_mut();
        }
    } else if SWALLOW_UP.swap(false, Ordering::SeqCst) {
        return std::ptr::null_mut();
    }
    pass
}

/// Is the desktop's own scrcpy window the one in front?
pub fn desktop_is_foreground(app: &AppHandle, key: &str) -> bool {
    let Some(pid) = super::session_pid(app, key) else {
        return false;
    };
    NSWorkspace::sharedWorkspace()
        .frontmostApplication()
        .is_some_and(|front| front.processIdentifier() == pid as i32)
}
