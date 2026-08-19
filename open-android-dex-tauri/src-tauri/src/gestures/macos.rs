//! macOS backend: a Quartz event tap that reads the trackpad's fingers.
//!
//! ## Why a tap and not a monitor
//!
//! The window the user is driving belongs to scrcpy, so nothing that only
//! works for the frontmost *application* is available to us. Two things are:
//! `NSEvent`'s global monitor, and a Quartz event tap. They see the same
//! events; the difference is that a monitor can only watch, while a tap placed
//! at the session level can also **swallow** an event.
//!
//! That difference is the whole reason for choosing the tap. macOS claims
//! three-finger swipes by default — Mission Control, App Exposé and switching
//! between full-screen spaces — and a gesture that both moved an Android
//! window and threw the user into Mission Control would read as broken. The
//! tap takes the events we act on and leaves every other one alone, with no
//! global setting changed and nothing to put back. That is also why this
//! backend has no equivalent of the Windows one's registry work: the Windows
//! raw-input API can only tee, so there the shell's own gestures have to be
//! stood down instead.
//!
//! ## Where the fingers come from
//!
//! A `CGEvent` carries no public touch data, but `NSEvent` does: every gesture
//! event from a Multi-Touch trackpad answers `touchesMatchingPhase:inView:`
//! with one `NSTouch` per finger, each with a `normalizedPosition` already in
//! the 0…1 range the shared recogniser wants. `+[NSEvent eventWithCGEvent:]`
//! is the bridge, and `NSTouch.deviceSize` gives the trackpad's shape so a
//! vertical swipe has to travel as far as a horizontal one.
//!
//! ## Permission
//!
//! Creating a tap requires this app to be trusted under System Settings →
//! Privacy & Security → Accessibility — the same grant the window commands in
//! `embed/macos.rs` already need. The failure is reported, never silent: a
//! gesture that does nothing with no explanation is the one outcome worse than
//! not having gestures at all.

use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use objc2_app_kit::{NSEvent, NSTouchPhase, NSWorkspace};
use objc2_core_foundation::{kCFRunLoopCommonModes, CFMachPort, CFRetained, CFRunLoop};
use objc2_core_graphics::{
    CGEvent, CGEventTapLocation, CGEventTapOptions, CGEventTapPlacement, CGEventTapProxy,
    CGEventType,
};
use tauri::AppHandle;

use super::{Dispatcher, Recogniser};

/// `NSEventTypeGesture`, and the `CGEventType` it arrives as.
///
/// Not a member of the public `CGEventType` enum — Quartz reserves the value
/// and AppKit is where it is named. Tapping it by number is how every trackpad
/// utility on the platform reads raw fingers, and the number has been stable
/// since 10.5.
const EVENT_TYPE_GESTURE: u32 = 29;

#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    /// The same question `embed/macos.rs` asks, and deliberately the variant
    /// that raises no dialog: a session starting is not the moment to put a
    /// permission prompt in front of someone.
    fn AXIsProcessTrusted() -> u8;
}

pub fn supported() -> bool {
    true
}

/// Whether this host can read the trackpad at all.
///
/// Answered as "are we allowed to", not "is a trackpad plugged in". Nothing
/// public identifies a Multi-Touch trackpad before an event from one arrives,
/// and a Mac without one simply never sends a gesture event — which costs
/// nothing. What the user can actually be asked to fix is the permission, so
/// that is what this reports, and what the Settings window dims on.
pub fn has_touchpad() -> bool {
    unsafe { AXIsProcessTrusted() != 0 }
}

/// The run loop the tap is pumping on, so [`stop`] can end it. 0 = none.
///
/// A raw pointer rather than a `CFRetained`, because `CFRunLoop` is neither
/// `Send` nor `Sync` in these bindings — and one extra retain is deliberately
/// leaked alongside it (see [`run`]). `CFRunLoopStop` is documented as safe to
/// call from another thread, but only if the object is still alive, and a run
/// loop belongs to its thread and dies with it. The leak is what makes the
/// stop sound rather than a race against the reader thread exiting.
static LOOP: OnceLock<Mutex<usize>> = OnceLock::new();

fn tap_loop() -> &'static Mutex<usize> {
    LOOP.get_or_init(|| Mutex::new(0))
}

struct Engine {
    app: AppHandle,
    key: String,
    dispatch: Dispatcher,
    recogniser: Recogniser,
    /// The tap's own port, so the callback can switch it back on after the
    /// system disables it.
    tap: Option<CFRetained<CFMachPort>>,
    /// Set while the current touch is one we claimed, so every event of that
    /// touch is swallowed and not just the one that crossed the threshold.
    /// Without it the tail of a swipe still reaches the Dock and Mission
    /// Control opens a beat after our own action.
    claiming: bool,
}

impl Engine {
    /// Handle one gesture event. Answers true when we consumed it.
    fn on_event(&mut self, event: &CGEvent) -> bool {
        let contacts = touches(event);
        let gesture = self.recogniser.update(&contacts);
        // A touch that has ended stops being ours, whatever else happens on
        // this event — but the event itself is still swallowed if the touch
        // was ours, so the Dock does not see the tail of a swipe we answered.
        let ending = contacts.is_empty();
        let was_ours = if ending {
            std::mem::take(&mut self.claiming)
        } else {
            self.claiming
        };

        // Claim the touch the moment enough fingers are down and something is
        // mapped for that many, not when our own threshold is crossed. macOS's
        // recogniser has a threshold of its own, and letting the opening half
        // of a swipe reach the Dock is how Mission Control opens underneath a
        // gesture we then also perform.
        let mine = !ending
            && contacts.len() >= 3
            && self.dispatch.claims_any(contacts.len())
            && desktop_is_foreground(&self.app, &self.key);
        if mine {
            self.claiming = true;
        }

        let Some(gesture) = gesture else {
            // A one or two finger event must always pass, or the pointer stops
            // moving. The end of a touch we owned is swallowed so the Dock
            // never sees the tail of a swipe we answered.
            return self.claiming || (was_ours && ending);
        };

        // Recognised, but is it mapped? A four-finger swipe on a machine where
        // only three-finger slots are set, or one the user mapped to Nothing,
        // belongs to macOS.
        if !self.dispatch.claims(&gesture) {
            return self.claiming || (was_ours && ending);
        }
        // The tap sees the whole login session, so these events arrive while
        // the user is in another app. Acting there would rearrange Android
        // windows from inside somebody else's window — and swallowing there
        // would break their trackpad.
        if !desktop_is_foreground(&self.app, &self.key) {
            return false;
        }
        // A tap gesture is only ever recognised on the frame where the last
        // finger comes up, which is the same frame that ends the touch — so
        // this must come AFTER the `ending` bookkeeping above and must not be
        // short-circuited by it.
        self.claiming = !ending;
        // Posted, not performed. A tap callback that blocks on adb is a tap
        // the system switches off for taking too long — and it would stall
        // every other trackpad event in the session while it did.
        self.dispatch.send(gesture);
        true
    }
}

/// Every finger currently on the trackpad, in fractions of pad width.
///
/// `normalizedPosition` is 0…1 on both axes with the origin at the BOTTOM
/// left, which is upside down relative to what the recogniser expects, so y is
/// flipped here — a swipe "up" has to mean up on both hosts. x is already a
/// fraction of width; y is scaled by the pad's aspect so the same physical
/// travel counts the same in either direction.
fn touches(event: &CGEvent) -> Vec<(f32, f32)> {
    let mut out = Vec::new();
    let Some(ns) = NSEvent::eventWithCGEvent(event) else {
        return out;
    };
    for touch in ns
        .touchesMatchingPhase_inView(NSTouchPhase::Touching, None)
        .iter()
    {
        // A palm or a thumb parked on the pad is reported as resting, and
        // counting it would turn a two-finger scroll into a three-finger
        // swipe.
        if touch.isResting() {
            continue;
        }
        let size = touch.deviceSize();
        let aspect = if size.width > 0.0 {
            (size.height / size.width) as f32
        } else {
            0.6
        };
        let p = touch.normalizedPosition();
        out.push((p.x as f32, (1.0 - p.y as f32) * aspect));
    }
    out
}

/// Is the desktop's own scrcpy window the one in front?
///
/// Frontmost *application* rather than key window: a scrcpy session is one
/// process showing one window, which is the assumption `embed/macos.rs` is
/// already built on.
fn desktop_is_foreground(app: &AppHandle, key: &str) -> bool {
    let Some(pid) = crate::scrcpy::session_pid(app, key) else {
        return false;
    };
    NSWorkspace::sharedWorkspace()
        .frontmostApplication()
        .is_some_and(|front| front.processIdentifier() == pid as i32)
}

pub fn start(app: AppHandle, key: String, dispatch: Dispatcher, stop: Arc<AtomicBool>) {
    if unsafe { AXIsProcessTrusted() } == 0 {
        // Said once, plainly, and only in the log: the desktop is entirely
        // usable without gestures, and a dialog at session start would be
        // worse than the missing feature. The Settings window says the same
        // thing where the user is looking for it.
        log::warn!(
            "gestures: this app is not trusted for Accessibility, so the trackpad cannot be \
             read — grant it under System Settings > Privacy & Security > Accessibility"
        );
        return;
    }

    // A watcher rather than a flag the loop checks: `CFRunLoopRun` blocks, and
    // the only way out is from another thread. Same shape as the logcat killer
    // in diag.rs.
    {
        let stop = stop.clone();
        std::thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(250));
            }
            stop_tap();
        });
    }

    std::thread::spawn(move || {
        run(Engine {
            app,
            key,
            dispatch,
            recogniser: Recogniser::default(),
            tap: None,
            claiming: false,
        });
    });
}

pub fn stop() {
    stop_tap();
}

fn stop_tap() {
    let handle = {
        let mut guard = match tap_loop().lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        std::mem::replace(&mut *guard, 0)
    };
    if handle != 0 {
        // Safe to call from another thread: waking a run loop is the one
        // CFRunLoop operation documented as thread-safe.
        unsafe { &*(handle as *const CFRunLoop) }.stop();
    }
}

/// Windows has a registry change to put back; macOS has nothing, because the
/// tap swallows what it claims instead of switching a system setting off.
pub fn restore_host_settings(_app: &AppHandle) {}

/// Nothing to stand down here — see [`restore_host_settings`].
pub fn set_suppressed(_app: &AppHandle, _want: bool) {}

fn run(engine: Engine) {
    // Boxed and leaked for the lifetime of the tap: the callback holds a raw
    // pointer to it, and the tap outlives this stack frame's locals only in
    // the sense that the run loop below never returns until it is torn down.
    let mut engine = Box::new(engine);
    let user_info = engine.as_mut() as *mut Engine as *mut c_void;

    let Some(tap) = (unsafe {
        CGEvent::tap_create(
            // The session's stream, ahead of everyone — including the Dock,
            // which is what turns a three-finger swipe into Mission Control.
            CGEventTapLocation::SessionEventTap,
            CGEventTapPlacement::HeadInsertEventTap,
            // Default, not ListenOnly: the point of the tap is to be able to
            // take the event away from the Dock.
            CGEventTapOptions::Default,
            1u64 << EVENT_TYPE_GESTURE,
            Some(tap_callback),
            user_info,
        )
    }) else {
        log::warn!("gestures: could not create the trackpad event tap");
        return;
    };
    engine.tap = Some(tap.clone());

    let Some(source) = CFMachPort::new_run_loop_source(None, Some(&tap), 0) else {
        log::warn!("gestures: could not attach the trackpad tap to a run loop");
        return;
    };
    let Some(run_loop) = CFRunLoop::current() else {
        log::warn!("gestures: no run loop on the trackpad thread");
        return;
    };
    unsafe {
        run_loop.add_source(Some(&source), kCFRunLoopCommonModes);
    }
    CGEvent::tap_enable(&tap, true);
    let handle = CFRetained::as_ptr(&run_loop).as_ptr() as usize;
    if let Ok(mut guard) = tap_loop().lock() {
        *guard = handle;
    }
    // One retain, never released — see the comment on LOOP. Without it this
    // object is freed when the thread below finishes, and a `stop` that was
    // already reading the pointer would call into freed memory.
    std::mem::forget(run_loop.clone());
    log::info!("gestures: reading the trackpad");

    CFRunLoop::run();

    // Order matters: the callback holds a raw pointer to `engine`, which is
    // dropped when this function returns. Detaching the source and killing the
    // port first is what guarantees no event can arrive after that.
    CGEvent::tap_enable(&tap, false);
    unsafe {
        run_loop.remove_source(Some(&source), kCFRunLoopCommonModes);
    }
    tap.invalidate();
    if let Ok(mut guard) = tap_loop().lock() {
        if *guard == handle {
            *guard = 0;
        }
    }
    log::info!("gestures: stopped reading the trackpad");
}

unsafe extern "C-unwind" fn tap_callback(
    _proxy: CGEventTapProxy,
    event_type: CGEventType,
    event: NonNull<CGEvent>,
    user_info: *mut c_void,
) -> *mut CGEvent {
    let pass = event.as_ptr();
    let engine = user_info as *mut Engine;
    if engine.is_null() {
        return pass;
    }
    let engine = unsafe { &mut *engine };

    if event_type == CGEventType::TapDisabledByTimeout
        || event_type == CGEventType::TapDisabledByUserInput
    {
        // Re-arm rather than log and give up. A tap the system switched off
        // for being slow stays off forever otherwise, and the symptom — the
        // gestures worked, then quietly stopped — is very hard to report.
        log::warn!("gestures: the trackpad tap was disabled by the system; re-enabling");
        if let Some(tap) = engine.tap.as_ref() {
            CGEvent::tap_enable(tap, true);
        }
        return pass;
    }
    if event_type.0 != EVENT_TYPE_GESTURE {
        return pass;
    }
    // Returning null swallows the event, which is what keeps the Dock from
    // acting on a swipe we have already answered.
    if engine.on_event(unsafe { event.as_ref() }) {
        std::ptr::null_mut()
    } else {
        pass
    }
}
