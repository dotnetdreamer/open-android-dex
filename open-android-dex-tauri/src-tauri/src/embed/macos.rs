//! macOS backend: application activation, plus the Accessibility API for
//! anything that touches a window's frame.
//!
//! Two APIs, because macOS draws the line between them at permission:
//!
//! * `NSRunningApplication` acts on a whole *application* — activate, hide,
//!   unhide. Any process may do this to any other, no permission involved.
//!   Enough for [`activate`], [`raise`] and [`set_visible`], because a scrcpy
//!   session is one process with one window, so "the app" and "the window" are
//!   the same thing here.
//! * The **Accessibility API** (`AXUIElement`) is the only way to read or write
//!   another process's window frame, and the only way to put it fullscreen.
//!   That requires the user to add this app under System Settings → Privacy &
//!   Security → Accessibility. It cannot be worked around, granted at build
//!   time, or replaced by an entitlement — it is a deliberate user decision, so
//!   the code asks once and reports the answer.
//!
//! There is deliberately no reparenting. macOS has no `SetParent`: a window
//! belongs to the process that created it and cannot be adopted by another.
//! See the module docs in `mod.rs`.

use std::ffi::c_void;

use objc2_app_kit::{NSApplicationActivationOptions, NSRunningApplication};
use objc2_core_foundation::{CFBoolean, CFDictionary, CFRetained, CFString, CFType};
use tauri::AppHandle;

/// Opaque `AXUIElementRef`. Only ever handled through the functions below and
/// released with `CFRelease`, so the layout is never needed.
#[repr(C)]
struct AXUIElement {
    _private: [u8; 0],
}

/// `kAXErrorSuccess`.
const AX_SUCCESS: i32 = 0;

// The Accessibility API is plain C in ApplicationServices, and has been
// unchanged since 10.2. Declaring the handful of entry points used here avoids
// a dependency whose only job would be to declare the same six lines.
//
// `Boolean` is CoreFoundation's `unsigned char`, spelled `u8` rather than
// `bool` here: Rust's `bool` may only ever hold 0 or 1, and promising that of a
// value a C library wrote is how a sound-looking binding becomes undefined
// behaviour the first time something answers 2.
#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    fn AXUIElementCreateApplication(pid: i32) -> *mut AXUIElement;
    fn AXUIElementCopyAttributeValue(
        element: *mut AXUIElement,
        attribute: *const CFString,
        value: *mut *const CFType,
    ) -> i32;
    fn AXUIElementSetAttributeValue(
        element: *mut AXUIElement,
        attribute: *const CFString,
        value: *const CFType,
    ) -> i32;
    fn AXUIElementIsAttributeSettable(
        element: *mut AXUIElement,
        attribute: *const CFString,
        settable: *mut u8,
    ) -> i32;
    fn AXUIElementPerformAction(element: *mut AXUIElement, action: *const CFString) -> i32;
    fn AXValueCreate(the_type: u32, value_ptr: *const c_void) -> *const CFType;
    /// Takes a `CFDictionaryRef`. Untyped here because the only dictionary
    /// ever passed is built with concrete key/value types, and threading those
    /// through an `extern` signature buys nothing.
    fn AXIsProcessTrustedWithOptions(options: *const c_void) -> u8;
    /// The same question without the dialog. Kept separate on purpose — see
    /// [`activate`], which must never raise a prompt of its own.
    fn AXIsProcessTrusted() -> u8;
    static kAXTrustedCheckOptionPrompt: &'static CFString;
}

#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFRelease(cf: *const c_void);
}

/// `kAXValueTypeCGPoint` / `kAXValueTypeCGSize`.
const AX_VALUE_CG_POINT: u32 = 1;
const AX_VALUE_CG_SIZE: u32 = 2;

#[repr(C)]
#[derive(Clone, Copy)]
struct CGPoint {
    x: f64,
    y: f64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct CGSize {
    width: f64,
    height: f64,
}

/// An `AXUIElementRef` that releases itself.
///
/// The Accessibility calls follow Core Foundation's Create/Copy rule — anything
/// with those words in the name is owned by the caller — and the frame commands
/// run on every drag frame, so leaking one per call is not academic.
struct Element(*mut AXUIElement);

impl Element {
    fn app(pid: u32) -> Result<Self, String> {
        let raw = unsafe { AXUIElementCreateApplication(pid as i32) };
        if raw.is_null() {
            return Err(format!("no accessible application for pid {pid}"));
        }
        Ok(Self(raw))
    }

    /// The application's frontmost window.
    ///
    /// A scrcpy session is one process showing one window, so asking for the
    /// main window is asking for *the* window. `AXMainWindow` rather than
    /// `AXWindows[0]`: the array's order is not documented to be z-order, and a
    /// session that has opened a file dialog would otherwise be resized by its
    /// dialog.
    fn main_window(pid: u32) -> Result<Self, String> {
        let app = Self::app(pid)?;
        let window = app
            .copy_attribute("AXMainWindow")
            .or_else(|_| app.copy_attribute("AXFocusedWindow"))
            .map_err(|_| {
                "scrcpy has no window yet — it may still be waiting for the first frame"
                    .to_string()
            })?;
        // Reinterpreting the CFTypeRef as an AXUIElementRef: both attributes
        // are documented to answer with one, and CFRelease is correct for
        // either way of being wrong.
        Ok(Self(CFRetained::into_raw(window).as_ptr() as *mut AXUIElement))
    }

    fn copy_attribute(&self, name: &str) -> Result<CFRetained<CFType>, String> {
        let key = CFString::from_str(name);
        let mut out: *const CFType = std::ptr::null();
        let err = unsafe { AXUIElementCopyAttributeValue(self.0, &*key, &mut out) };
        if err != AX_SUCCESS || out.is_null() {
            return Err(format!("{name} unavailable (AXError {err})"));
        }
        // Copy* hands over ownership, so adopt it rather than retaining again.
        Ok(unsafe { CFRetained::from_raw(std::ptr::NonNull::new_unchecked(out as *mut CFType)) })
    }

    fn set_attribute(&self, name: &str, value: &CFType) -> Result<(), String> {
        let key = CFString::from_str(name);
        let err = unsafe { AXUIElementSetAttributeValue(self.0, &*key, value) };
        (err == AX_SUCCESS)
            .then_some(())
            .ok_or_else(|| format!("could not set {name} (AXError {err})"))
    }

    fn perform(&self, action: &str) -> Result<(), String> {
        let key = CFString::from_str(action);
        let err = unsafe { AXUIElementPerformAction(self.0, &*key) };
        (err == AX_SUCCESS)
            .then_some(())
            .ok_or_else(|| format!("could not perform {action} (AXError {err})"))
    }

    fn is_settable(&self, name: &str) -> bool {
        let key = CFString::from_str(name);
        let mut settable: u8 = 0;
        let err = unsafe { AXUIElementIsAttributeSettable(self.0, &*key, &mut settable) };
        err == AX_SUCCESS && settable != 0
    }
}

impl Drop for Element {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { CFRelease(self.0 as *const c_void) };
        }
    }
}

/// Whether this app may drive other applications' windows, asking the user for
/// the right the first time something needs it.
///
/// The prompt is deliberately tied to an action rather than shown at startup:
/// it is the ⛶ button on the phone-side taskbar that needs this, and a
/// permission dialog on first launch — before the user has seen a window, let
/// alone pressed anything — reads as the app overreaching.
///
/// macOS shows the dialog at most once per app; after that this is a cheap
/// no-op that answers false until the checkbox is ticked. It never blocks.
fn accessibility_granted() -> bool {
    let key = unsafe { kAXTrustedCheckOptionPrompt };
    let options =
        CFDictionary::<CFString, CFBoolean>::from_slices(&[key], &[CFBoolean::new(true)]);
    unsafe { AXIsProcessTrustedWithOptions(CFRetained::as_ptr(&options).as_ptr().cast()) != 0 }
}

/// The same question, asked without offering the dialog.
///
/// For paths that have a working answer without the permission and only want to
/// take the better one when it happens to be available. macOS shows the prompt
/// at most once per app, so spending it on a call that did not need it is
/// spending it for good.
fn accessibility_available() -> bool {
    unsafe { AXIsProcessTrusted() != 0 }
}

/// The message shown when the frame commands are refused. Written for the
/// person who pressed the button, not for the log.
const NEEDS_ACCESSIBILITY: &str = "macOS has not given Open Android DeX permission to control \
     other apps' windows. Open System Settings → Privacy & Security → Accessibility, switch \
     Open Android DeX on, then try again.";

fn require_accessibility() -> Result<(), String> {
    if accessibility_granted() {
        return Ok(());
    }
    log::warn!("Accessibility permission not granted — window frame control unavailable");
    Err(NEEDS_ACCESSIBILITY.to_string())
}

fn running_app(pid: u32) -> Result<objc2::rc::Retained<NSRunningApplication>, String> {
    NSRunningApplication::runningApplicationWithProcessIdentifier(pid as i32)
        // A session whose scrcpy has already exited: the caller is a button
        // press racing the process going away, not a bug.
        .ok_or_else(|| format!("no running application with pid {pid}"))
}

/// macOS has no cross-process reparenting, so there is nothing to attach to.
///
/// Reported rather than approximated: a caller that asked for a child window
/// and silently got a floating one would draw its chrome around empty space and
/// leave the video somewhere else on screen.
pub fn attach(
    _app: &AppHandle,
    _session_key: &str,
    _pid: u32,
    _x: i32,
    _y: i32,
    _w: i32,
    _h: i32,
) -> Result<isize, String> {
    Err("macOS cannot reparent another process's window into this one — \
         the desktop runs as its own window here"
        .into())
}

/// Move and resize the window. Top-left origin in screen points, matching the
/// Accessibility API's own coordinate space (which, unlike the rest of Cocoa,
/// puts the origin at the top-left of the main display).
pub fn set_frame(pid: u32, _stored: isize, x: i32, y: i32, w: i32, h: i32) -> Result<(), String> {
    require_accessibility()?;
    let window = Element::main_window(pid)?;

    let point = CGPoint {
        x: x as f64,
        y: y as f64,
    };
    let position = unsafe { AXValueCreate(AX_VALUE_CG_POINT, &point as *const _ as *const c_void) };
    if position.is_null() {
        return Err("could not build an AXValue for the position".into());
    }
    let position = unsafe {
        CFRetained::from_raw(std::ptr::NonNull::new_unchecked(position as *mut CFType))
    };

    let size = CGSize {
        width: w as f64,
        height: h as f64,
    };
    let size_value = unsafe { AXValueCreate(AX_VALUE_CG_SIZE, &size as *const _ as *const c_void) };
    if size_value.is_null() {
        return Err("could not build an AXValue for the size".into());
    }
    let size_value = unsafe {
        CFRetained::from_raw(std::ptr::NonNull::new_unchecked(size_value as *mut CFType))
    };

    // Position first: resizing a window that is partly off-screen can be
    // clamped by the window server, and moving it back afterwards would then
    // leave the wrong size behind.
    window.set_attribute("AXPosition", &position)?;
    window.set_attribute("AXSize", &size_value)
}

/// Hide or unhide the session.
///
/// Application-level (`NSRunningApplication`), which needs no permission —
/// and for a one-window process is indistinguishable from hiding the window.
pub fn set_visible(pid: u32, _stored: isize, visible: bool) -> Result<(), String> {
    let app = running_app(pid)?;
    let ok = if visible {
        let shown = app.unhide();
        // Unhiding does not raise, and a caller asking for a window to be
        // visible means on top of the others. Through `activate` rather than
        // `activateWithOptions` directly, so this gets the same two-tier
        // treatment and cannot quietly rot into the no-op described there.
        let _ = activate(pid);
        shown
    } else {
        app.hide()
    };
    if ok {
        Ok(())
    } else {
        Err(format!(
            "macOS refused to {} pid {pid}",
            if visible { "unhide" } else { "hide" }
        ))
    }
}

pub fn raise(pid: u32, _stored: isize) -> Result<(), String> {
    activate(pid)
}

/// Toggle the window between fullscreen and its previous frame.
///
/// This is AppKit's own fullscreen — the green-button one, which moves the
/// window to its own Space — rather than the borderless-covers-the-monitor
/// trick the Windows backend performs. On macOS that trick is the wrong answer:
/// a window resized over the menu bar stays *under* it, so the result would be
/// a window with its top edge hidden rather than a fullscreen one.
///
/// No saved-frame bookkeeping is needed to match the Windows backend's restore,
/// because AppKit restores the pre-fullscreen frame itself.
/// Leave fullscreen if the window is in it, and say nothing if it is not.
///
/// Separate from [`toggle_fullscreen`] because only one of the two is safe to
/// drive from a key press: a toggle asked to act on a window that is already
/// windowed puts it fullscreen, which is the opposite of what Escape means.
///
/// Deliberately does NOT delegate to the toggle after checking the state.
/// Doing so would decide the direction all over again, from a second reading
/// several cross-process round trips later — and AppKit's fullscreen
/// transition is animated over about half a second, so a held Escape would
/// have one repeat read `true`, hand over, have the toggle read `false` once
/// the animation landed, and put the desktop straight back into fullscreen.
/// The direction is fixed here, once: off.
pub fn exit_fullscreen(pid: u32, _stored: isize) -> Result<bool, String> {
    require_accessibility()?;
    let window = Element::main_window(pid)?;
    if !is_fullscreen(&window) {
        return Ok(false);
    }
    if !window.is_settable("AXFullScreen") {
        return Err("this scrcpy window cannot leave fullscreen".into());
    }
    window.set_attribute("AXFullScreen", &CFBoolean::new(false))?;
    // Unlike the toggle there is nothing to read back for: the answer is the
    // same whether the transition has landed yet or not, and re-reading mid
    // animation is exactly the mistake described above.
    Ok(false)
}

/// Whether AppKit currently has this window in a fullscreen Space.
fn is_fullscreen(window: &Element) -> bool {
    window
        .copy_attribute("AXFullScreen")
        .ok()
        .and_then(|v| v.downcast_ref::<CFBoolean>().map(CFBoolean::value))
        .unwrap_or(false)
}

pub fn toggle_fullscreen(pid: u32, _stored: isize) -> Result<bool, String> {
    require_accessibility()?;
    let window = Element::main_window(pid)?;

    // Read before writing: this has to be a toggle, and the taskbar's idea of
    // the current state can be stale (the user may have used the green button).
    let current = is_fullscreen(&window);

    // Settable only for a window AppKit marked FullScreenPrimary, which SDL
    // does for a resizable window — and scrcpy only makes its window resizable
    // when there is video. An audio-only session legitimately lands here.
    if !window.is_settable("AXFullScreen") {
        return Err("this scrcpy window cannot be put fullscreen".into());
    }
    let wanted = !current;
    window.set_attribute("AXFullScreen", &CFBoolean::new(wanted))?;

    // Read back rather than answer with what was asked for. The setter returns
    // as soon as the request is accepted, while the Spaces transition is
    // animated and lands about half a second later — and it can still be
    // declined in between. The answer goes straight to the taskbar's ⛶ icon,
    // so a wrong one leaves the button lying about the state until the next
    // press.
    let settled = window
        .copy_attribute("AXFullScreen")
        .ok()
        .and_then(|v| v.downcast_ref::<CFBoolean>().map(CFBoolean::value))
        .unwrap_or(wanted);
    Ok(settled)
}

/// Bring the session's window to the foreground.
///
/// Two tiers, because neither is sufficient alone.
///
/// `NSRunningApplication` needs no permission, and is enough for the ordinary
/// case: the "Show desktop" button lives in this app's own window, so this app
/// is frontmost when it runs. But macOS 14 made activation *cooperative* — a
/// process that is not itself frontmost can no longer pull another app forward,
/// and `ActivateIgnoringOtherApps`, the flag that used to override exactly
/// that, is documented from Sonoma on as having no effect. So this tier
/// silently does nothing whenever the request did not originate in our own
/// window.
///
/// The Accessibility API is not bound by that rule. It is deliberately not
/// *requested* here — no dialog on a path that usually works without one, and
/// macOS only offers the prompt once — but when the user has already granted it
/// for the ⛶ button, it is used, and it is the tier that actually works from
/// the background.
pub fn activate(pid: u32) -> Result<(), String> {
    let app = running_app(pid)?;

    // ActivateAllWindows only. Passing the deprecated ignore-others flag
    // alongside it would just mislead whoever reads this next: the system
    // ignores it.
    let sent = app.activateWithOptions(NSApplicationActivationOptions::ActivateAllWindows);

    if accessibility_available() {
        if let Ok(element) = Element::app(pid) {
            if element
                .set_attribute("AXFrontmost", &CFBoolean::new(true))
                .is_ok()
            {
                // Frontmost raises the application; AXRaise raises the window
                // within it. Best-effort: a session whose window has not
                // appeared yet is already served by the line above.
                if let Ok(window) = Element::main_window(pid) {
                    let _ = window.perform("AXRaise");
                }
                return Ok(());
            }
        }
    }

    if sent {
        // Not a guarantee. `activateWithOptions` reports that the request was
        // sent, not that the window server honoured it.
        log::debug!(
            "activated pid {pid} via NSRunningApplication — if it did not come forward, \
             macOS needs Accessibility permission to raise an app from the background \
             (System Settings → Privacy & Security → Accessibility)"
        );
        Ok(())
    } else {
        Err(format!(
            "macOS would not bring pid {pid} forward. Since macOS 14 a background app \
             cannot raise another app without Accessibility permission — grant it under \
             System Settings → Privacy & Security → Accessibility."
        ))
    }
}
