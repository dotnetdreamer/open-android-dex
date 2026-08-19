//! Win32 backend: read a Precision Touchpad's contacts and classify them.
//!
//! ## Why raw HID and not a gesture API
//!
//! Windows has exactly one supported way for an application to be handed a
//! three-finger gesture — `TouchpadGesturesController` — and it only fires for
//! *the foreground process*. The window the user is driving is scrcpy's, which
//! belongs to another process, so that route is closed to us for as long as the
//! desktop is a standalone window. Raw input is the route that is not: with
//! `RIDEV_INPUTSINK` a message-only window in this process receives the pad's
//! HID reports no matter who has focus.
//!
//! The cost of that choice is that raw input *tees* rather than *filters*.
//! Windows keeps recognising its own gestures from the same contacts, so a
//! three-finger swipe would fire ours AND Task View. That is what
//! [`suppress`] is for, and it is the only reason this file touches the
//! registry.
//!
//! ## Three fingers and up, nothing else
//!
//! One and two contacts are left entirely alone. The driver turns those into a
//! pointer, a wheel and a right-click that already reach scrcpy and the phone;
//! re-deriving them here would mean fighting the driver for the cursor. This
//! backend never reports fewer than three.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use tauri::{AppHandle, Manager};
use windows_sys::Win32::Devices::HumanInterfaceDevice::{
    HidP_GetCaps, HidP_GetSpecificButtonCaps, HidP_GetUsageValue, HidP_GetUsages,
    HidP_GetValueCaps, HidP_Input, HIDP_BUTTON_CAPS, HIDP_CAPS, HIDP_STATUS_SUCCESS,
    HIDP_VALUE_CAPS, HID_USAGE_DIGITIZER_TOUCH_PAD, HID_USAGE_PAGE_DIGITIZER,
    PHIDP_PREPARSED_DATA,
};
use windows_sys::Win32::Foundation::{HANDLE, HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::UI::Input::{
    GetRawInputData, GetRawInputDeviceInfoW, GetRawInputDeviceList, RegisterRawInputDevices,
    RAWINPUT, RAWINPUTDEVICE, RAWINPUTDEVICELIST, RAWINPUTHEADER, RIDEV_INPUTSINK, RIDEV_REMOVE,
    RIDI_DEVICEINFO, RIDI_PREPARSEDDATA, RID_DEVICE_INFO, RID_INPUT, RIM_TYPEHID,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetAncestor,
    GetForegroundWindow, GetMessageW, GetWindowLongPtrW, GetWindowThreadProcessId, PostMessageW,
    PostQuitMessage, RegisterClassW, SendMessageTimeoutW, SetWindowLongPtrW, CREATESTRUCTW,
    GA_ROOT, GWLP_USERDATA, HWND_BROADCAST, HWND_MESSAGE, MSG, SMTO_ABORTIFHUNG, WM_CLOSE,
    WM_CREATE, WM_DESTROY, WM_INPUT, WM_SETTINGCHANGE, WNDCLASSW,
};

use super::{Dispatcher, Recogniser};

// ── HID usages we read ────────────────────────────────────────────────
// From the Windows Precision Touchpad HID collection: every compliant pad
// declares these, and their meaning is fixed by the spec.

/// Generic Desktop page — where X and Y live even inside a digitizer.
const PAGE_GENERIC: u16 = 0x01;
const USAGE_X: u16 = 0x30;
const USAGE_Y: u16 = 0x31;
/// Tip Switch: a one-bit button, so it is read with `HidP_GetUsages`.
const USAGE_TIP_SWITCH: u16 = 0x42;
/// Confidence: also a button. The pad's own verdict on whether a contact is a
/// deliberate finger rather than a palm or a resting thumb.
const USAGE_CONFIDENCE: u16 = 0x47;
const USAGE_CONTACT_ID: u16 = 0x51;
/// Report-level; sits in the top-level collection (link collection 0).
const USAGE_CONTACT_COUNT: u16 = 0x54;

/// The registry values Windows reads to decide what ITS three-finger gestures
/// do, and what they must be set to for ours to be the only ones that fire.
///
/// Three fingers only. Four-finger slides and taps are deliberately left
/// alone: DeX's gesture set is a three-finger set, so taking three is enough,
/// and a user who still wants Task View or a desktop switch while the desktop
/// is up can reach both with four.
const SUPPRESSED: [&str; 2] = ["ThreeFingerSlideEnabled", "ThreeFingerTapEnabled"];

const PTP_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\PrecisionTouchPad";

pub fn supported() -> bool {
    true
}

// ── Device discovery ──────────────────────────────────────────────────

/// True when a Precision Touchpad is attached.
///
/// A legacy (non-precision) touchpad reports itself to Windows as a mouse and
/// publishes no digitizer collection at all, so it simply does not appear
/// here — which is the honest answer, since none of this works on one.
pub fn has_touchpad() -> bool {
    !touchpad_handles().is_empty()
}

/// Handles of every attached Precision Touchpad.
fn touchpad_handles() -> Vec<HANDLE> {
    let mut found = Vec::new();
    unsafe {
        let mut count = 0u32;
        let size = std::mem::size_of::<RAWINPUTDEVICELIST>() as u32;
        if GetRawInputDeviceList(std::ptr::null_mut(), &mut count, size) == u32::MAX || count == 0 {
            return found;
        }
        let mut list = vec![RAWINPUTDEVICELIST::default(); count as usize];
        let got = GetRawInputDeviceList(list.as_mut_ptr(), &mut count, size);
        if got == u32::MAX {
            return found;
        }
        for entry in list.iter().take(got as usize) {
            if entry.dwType != RIM_TYPEHID {
                continue;
            }
            let mut info = RID_DEVICE_INFO {
                cbSize: std::mem::size_of::<RID_DEVICE_INFO>() as u32,
                ..Default::default()
            };
            let mut len = info.cbSize;
            if GetRawInputDeviceInfoW(
                entry.hDevice,
                RIDI_DEVICEINFO,
                &mut info as *mut _ as *mut _,
                &mut len,
            ) == u32::MAX
            {
                continue;
            }
            if info.Anonymous.hid.usUsagePage == HID_USAGE_PAGE_DIGITIZER
                && info.Anonymous.hid.usUsage == HID_USAGE_DIGITIZER_TOUCH_PAD
            {
                found.push(entry.hDevice);
            }
        }
    }
    found
}

/// What one pad's report descriptor says, worked out once per device.
///
/// Parsing this per report would mean an allocation and a `HidP_GetValueCaps`
/// sweep for every frame at the pad's report rate.
struct DeviceCaps {
    /// Owned copy — `HidP_*` reads through this pointer on every report.
    preparsed: Vec<u8>,
    /// Link collections that carry one contact each, in descriptor order.
    contacts: Vec<u16>,
    x_min: i32,
    x_span: f32,
    y_min: i32,
    y_span: f32,
    /// Pad height ÷ pad width, so a vertical swipe has to travel the same
    /// physical distance as a horizontal one.
    ///
    /// Taken from the ratio of the two axes' *physical* extents, which needs
    /// no knowledge of the unit they are declared in — only that X and Y use
    /// the same one, which the PTP spec requires. A pad that declares no
    /// physical extents falls back to the shape of a typical laptop pad.
    aspect: f32,
    has_contact_count: bool,
    /// Whether the descriptor declares Confidence at all.
    ///
    /// It has to be asked, not assumed: requiring a usage the pad never sends
    /// would reject every contact, and ignoring one the pad does send lets a
    /// resting palm count as a finger.
    has_confidence: bool,
}

impl DeviceCaps {
    fn ptr(&self) -> PHIDP_PREPARSED_DATA {
        self.preparsed.as_ptr() as PHIDP_PREPARSED_DATA
    }

    /// Normalise a contact to "fractions of pad width", origin top-left.
    fn normalise(&self, x: u32, y: u32) -> (f32, f32) {
        let fx = (x as i32 - self.x_min) as f32 / self.x_span;
        let fy = (y as i32 - self.y_min) as f32 / self.y_span * self.aspect;
        (fx, fy)
    }
}

/// Read a device's preparsed data and pull out everything the parser needs.
fn device_caps(handle: HANDLE) -> Option<DeviceCaps> {
    unsafe {
        let mut size = 0u32;
        if GetRawInputDeviceInfoW(handle, RIDI_PREPARSEDDATA, std::ptr::null_mut(), &mut size)
            == u32::MAX
            || size == 0
        {
            return None;
        }
        let mut preparsed = vec![0u8; size as usize];
        if GetRawInputDeviceInfoW(
            handle,
            RIDI_PREPARSEDDATA,
            preparsed.as_mut_ptr() as *mut _,
            &mut size,
        ) == u32::MAX
        {
            return None;
        }
        let pp = preparsed.as_ptr() as PHIDP_PREPARSED_DATA;

        let mut caps = HIDP_CAPS::default();
        if HidP_GetCaps(pp, &mut caps) != HIDP_STATUS_SUCCESS {
            return None;
        }
        let mut len = caps.NumberInputValueCaps;
        if len == 0 {
            return None;
        }
        let mut value_caps = vec![HIDP_VALUE_CAPS::default(); len as usize];
        if HidP_GetValueCaps(HidP_Input, value_caps.as_mut_ptr(), &mut len, pp)
            != HIDP_STATUS_SUCCESS
        {
            return None;
        }

        // A contact is a link collection that declares BOTH an X and a
        // Contact Identifier. Keying off X alone would also pick up the
        // report-level axes some pads declare for their integrated pointer.
        let mut x_caps: HashMap<u16, &HIDP_VALUE_CAPS> = HashMap::new();
        let mut y_caps: HashMap<u16, &HIDP_VALUE_CAPS> = HashMap::new();
        let mut with_id: Vec<u16> = Vec::new();
        let mut has_contact_count = false;
        for cap in value_caps.iter().take(len as usize) {
            // Ranged usages are not how a pad declares X/Y/Contact ID; taking
            // the union member of the wrong variant would read garbage.
            if cap.IsRange {
                continue;
            }
            let usage = cap.Anonymous.NotRange.Usage;
            match (cap.UsagePage, usage) {
                (PAGE_GENERIC, USAGE_X) => {
                    x_caps.insert(cap.LinkCollection, cap);
                }
                (PAGE_GENERIC, USAGE_Y) => {
                    y_caps.insert(cap.LinkCollection, cap);
                }
                (HID_USAGE_PAGE_DIGITIZER, USAGE_CONTACT_ID) => with_id.push(cap.LinkCollection),
                (HID_USAGE_PAGE_DIGITIZER, USAGE_CONTACT_COUNT) => has_contact_count = true,
                _ => {}
            }
        }
        with_id.sort_unstable();
        with_id.dedup();
        let contacts: Vec<u16> = with_id
            .into_iter()
            .filter(|lc| x_caps.contains_key(lc) && y_caps.contains_key(lc))
            .collect();
        if contacts.is_empty() {
            return None;
        }

        // Every contact collection declares the same axes, so the first one
        // speaks for the pad.
        let x = x_caps[&contacts[0]];
        let y = y_caps[&contacts[0]];
        let x_span = (x.LogicalMax - x.LogicalMin) as f32;
        let y_span = (y.LogicalMax - y.LogicalMin) as f32;
        if x_span <= 0.0 || y_span <= 0.0 {
            return None;
        }
        let x_phys = (x.PhysicalMax - x.PhysicalMin) as f32;
        let y_phys = (y.PhysicalMax - y.PhysicalMin) as f32;
        let aspect = if x_phys > 0.0 && y_phys > 0.0 {
            y_phys / x_phys
        } else {
            0.6
        };

        let has_confidence = declares_button(pp, contacts[0], USAGE_CONFIDENCE);

        Some(DeviceCaps {
            preparsed,
            contacts,
            x_min: x.LogicalMin,
            x_span,
            y_min: y.LogicalMin,
            y_span,
            aspect,
            has_contact_count,
            has_confidence,
        })
    }
}

/// Does a contact collection declare this one-bit usage?
fn declares_button(pp: PHIDP_PREPARSED_DATA, collection: u16, usage: u16) -> bool {
    let mut caps = HIDP_BUTTON_CAPS::default();
    let mut len = 1u16;
    let status = unsafe {
        HidP_GetSpecificButtonCaps(
            HidP_Input,
            HID_USAGE_PAGE_DIGITIZER,
            collection,
            usage,
            &mut caps,
            &mut len,
            pp,
        )
    };
    status == HIDP_STATUS_SUCCESS && len > 0
}

// ── The reader ────────────────────────────────────────────────────────

struct Engine {
    app: AppHandle,
    key: String,
    dispatch: Dispatcher,
    /// Cached as `Option` so a device whose descriptor we could not make sense
    /// of is not re-parsed on every report it sends.
    devices: HashMap<isize, Option<DeviceCaps>>,
    /// Contact id -> normalised position, for the contacts currently down.
    down: HashMap<u16, (f32, f32)>,
    /// Contacts still owed by the frame in progress. See [`apply_report`].
    pending: usize,
    recogniser: Recogniser,
}

impl Engine {
    /// Handle one WM_INPUT.
    fn on_input(&mut self, lparam: LPARAM) {
        let Some((handle, reports, stride)) = read_raw_input(lparam) else {
            return;
        };
        let caps = self
            .devices
            .entry(handle as isize)
            .or_insert_with(|| device_caps(handle));
        let Some(caps) = caps.as_ref() else {
            return;
        };
        for report in reports.chunks_exact(stride) {
            apply_report(caps, report, &mut self.down, &mut self.pending);
        }
        let contacts: Vec<(f32, f32)> = self.down.values().copied().collect();
        let Some(gesture) = self.recogniser.update(&contacts) else {
            return;
        };
        // Nothing mapped to this one, or the feature is off. Windows keeps
        // its own gesture either way — raw input only tees — so unlike macOS
        // there is nothing to hand back here; this just saves waking the
        // worker for a gesture it would discard.
        if !self.dispatch.claims(&gesture) {
            return;
        }
        // The pad is read with RIDEV_INPUTSINK, so these reports keep arriving
        // while the user is in their spreadsheet. Acting on one there would
        // rearrange Android windows from inside another application, so the
        // DeX window has to actually be in front.
        if !desktop_is_foreground(&self.app, &self.key) {
            return;
        }
        // Posted, not performed: this is the thread the pad reports into, and
        // a few hundred milliseconds of adb here would drop the contacts of
        // whatever the user does next.
        self.dispatch.send(gesture);
    }
}

/// Fold one HID report into the set of contacts that are down.
///
/// `pending` is what makes this correct on a pad that cannot fit a whole frame
/// into one report. A Precision Touchpad is allowed — and on a pad with few
/// contact collections, obliged — to split one frame across several reports:
/// the FIRST carries the total contact count for the frame, and every
/// continuation carries a count of **zero**. So a zero is not "all fingers
/// lifted", it is "more of the frame you are already reading", and treating it
/// as a lift wipes the accumulator on every pad that reports this way. A lift
/// needs no sentinel of its own: it arrives as Tip Switch clearing on the
/// contact itself.
///
/// Counting down also disposes of the other zero-count report — the button-only
/// one a pad sends when it is physically clicked — whose contact collections
/// hold nothing worth reading.
fn apply_report(
    caps: &DeviceCaps,
    report: &[u8],
    down: &mut HashMap<u16, (f32, f32)>,
    pending: &mut usize,
) {
    let pp = caps.ptr();
    let len = report.len() as u32;
    let ptr = report.as_ptr();

    if caps.has_contact_count {
        // A new frame overwrites whatever the last one left owing, which is
        // the right answer after a dropped report. Anything else is either a
        // continuation of the frame in progress or, if none is, a report with
        // no contacts in it at all.
        if let Some(n @ 1..) = usage_value(pp, HID_USAGE_PAGE_DIGITIZER, 0, USAGE_CONTACT_COUNT, ptr, len)
        {
            *pending = n as usize;
        }
    } else {
        // No count declared: one report is always one whole frame.
        *pending = caps.contacts.len();
    }
    if *pending == 0 {
        return;
    }

    for &lc in caps.contacts.iter() {
        if *pending == 0 {
            break;
        }
        // A collection past the ones this report filled answers nothing, and
        // must not be counted against the frame.
        let Some(id) = usage_value(pp, HID_USAGE_PAGE_DIGITIZER, lc, USAGE_CONTACT_ID, ptr, len)
        else {
            continue;
        };
        *pending -= 1;
        let id = id as u16;
        let (tip, confident) = contact_flags(pp, lc, ptr, len);
        // Confidence is the pad saying this contact is a deliberate finger and
        // not a palm or a thumb parked at the edge. Believing a low-confidence
        // contact is how an ordinary two-finger scroll becomes a three-finger
        // swipe.
        if !tip || (caps.has_confidence && !confident) {
            down.remove(&id);
            continue;
        }
        let (Some(x), Some(y)) = (
            usage_value(pp, PAGE_GENERIC, lc, USAGE_X, ptr, len),
            usage_value(pp, PAGE_GENERIC, lc, USAGE_Y, ptr, len),
        ) else {
            continue;
        };
        down.insert(id, caps.normalise(x, y));
    }
}

fn usage_value(
    pp: PHIDP_PREPARSED_DATA,
    page: u16,
    collection: u16,
    usage: u16,
    report: *const u8,
    len: u32,
) -> Option<u32> {
    let mut value = 0u32;
    let status = unsafe {
        HidP_GetUsageValue(
            HidP_Input,
            page,
            collection,
            usage,
            &mut value,
            pp,
            report as *const _,
            len,
        )
    };
    (status == HIDP_STATUS_SUCCESS).then_some(value)
}

/// Tip Switch and Confidence are buttons, not values: each is "set" when its
/// usage appears in the collection's active list for this report. Both come out
/// of one call, because the call costs the same either way.
fn contact_flags(
    pp: PHIDP_PREPARSED_DATA,
    collection: u16,
    report: *const u8,
    len: u32,
) -> (bool, bool) {
    let mut usages = [0u16; 8];
    let mut count = usages.len() as u32;
    let status = unsafe {
        HidP_GetUsages(
            HidP_Input,
            HID_USAGE_PAGE_DIGITIZER,
            collection,
            usages.as_mut_ptr(),
            &mut count,
            pp,
            report as *mut _,
            len,
        )
    };
    if status != HIDP_STATUS_SUCCESS {
        return (false, false);
    }
    let active = &usages[..count as usize];
    (
        active.contains(&USAGE_TIP_SWITCH),
        active.contains(&USAGE_CONFIDENCE),
    )
}

/// Pull the HID payload out of a WM_INPUT.
///
/// Answers the device handle, the reports packed back to back, and how long
/// one report is — a single message can carry several, and dropping all but
/// the first loses contacts on a fast swipe.
fn read_raw_input(lparam: LPARAM) -> Option<(HANDLE, Vec<u8>, usize)> {
    unsafe {
        let header = std::mem::size_of::<RAWINPUTHEADER>() as u32;
        let mut size = 0u32;
        if GetRawInputData(
            lparam as _,
            RID_INPUT,
            std::ptr::null_mut(),
            &mut size,
            header,
        ) != 0
            || size == 0
        {
            return None;
        }
        let mut buf = vec![0u8; size as usize];
        let got = GetRawInputData(
            lparam as _,
            RID_INPUT,
            buf.as_mut_ptr() as *mut _,
            &mut size,
            header,
        );
        if got == u32::MAX || (got as usize) < std::mem::size_of::<RAWINPUT>() {
            return None;
        }
        let raw = &*(buf.as_ptr() as *const RAWINPUT);
        if raw.header.dwType != RIM_TYPEHID {
            return None;
        }
        let stride = raw.data.hid.dwSizeHid as usize;
        let count = raw.data.hid.dwCount as usize;
        if stride == 0 || count == 0 {
            return None;
        }
        // `bRawData` is a one-byte placeholder for a variable-length tail, so
        // the reports live past the end of the struct and must be sliced out
        // of the ORIGINAL buffer. Reading them through a copy of `RAWHID` —
        // which is `Copy`, and easy to take one of by accident — would read
        // one byte of payload and then the stack.
        let start = std::mem::offset_of!(RAWINPUT, data)
            + std::mem::offset_of!(windows_sys::Win32::UI::Input::RAWHID, bRawData);
        debug_assert!(start >= std::mem::size_of::<RAWINPUTHEADER>());
        let end = start.checked_add(stride.checked_mul(count)?)?;
        if end > buf.len() {
            return None;
        }
        Some((raw.header.hDevice, buf[start..end].to_vec(), stride))
    }
}

/// Is the desktop's own scrcpy window the one the user is looking at?
fn desktop_is_foreground(app: &AppHandle, key: &str) -> bool {
    let Some(pid) = crate::scrcpy::session_pid(app, key) else {
        return false;
    };
    unsafe {
        let fg = GetForegroundWindow();
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

// ── The message-only window and its thread ────────────────────────────

/// The live reader: which generation it belongs to, and the HWND to ask it to
/// quit. `(0, 0)` = none.
///
/// Process-wide rather than per session on purpose: raw-input registration is
/// keyed by usage page for the whole process, and a second reader would
/// silently take the first one's subscription away.
///
/// The generation is what keeps sessions from killing each other. Each reader
/// gets its own stop-watcher, and a watcher outlives the reader it was started
/// for — so a stale one waking up after the next session has begun would
/// otherwise post its `WM_CLOSE` at a window it never started.
static READER: OnceLock<Mutex<(u64, isize)>> = OnceLock::new();
static NEXT_GEN: AtomicU64 = AtomicU64::new(1);

fn reader() -> &'static Mutex<(u64, isize)> {
    READER.get_or_init(|| Mutex::new((0, 0)))
}

pub fn start(app: AppHandle, key: String, dispatch: Dispatcher, stop: Arc<AtomicBool>) {
    if !has_touchpad() {
        log::info!("gestures: no precision touchpad on this host — not started");
        return;
    }

    let generation = NEXT_GEN.fetch_add(1, Ordering::SeqCst);

    // A watcher rather than a flag the loop checks: `GetMessageW` blocks, so
    // the only way out is a message. Same shape as the logcat killer in diag.
    {
        let stop = stop.clone();
        std::thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(250));
            }
            stop_generation(Some(generation));
        });
    }

    std::thread::spawn(move || {
        let engine = Engine {
            app: app.clone(),
            key,
            dispatch,
            devices: HashMap::new(),
            down: HashMap::new(),
            pending: 0,
            recogniser: Recogniser::default(),
        };
        run(engine, generation);
        restore_host_settings(&app);
    });
}

pub fn stop() {
    stop_generation(None);
}

/// Ask the reader to quit — either whichever one is current (`None`), or only
/// the one a particular session started.
fn stop_generation(only: Option<u64>) {
    let hwnd = {
        let mut guard = match reader().lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        if only.is_some_and(|gen| guard.0 != gen) {
            return; // a later session owns the reader now; not ours to close
        }
        std::mem::replace(&mut *guard, (0, 0)).1
    };
    if hwnd != 0 {
        unsafe {
            PostMessageW(hwnd as HWND, WM_CLOSE, 0, 0);
        }
    }
}

const CLASS_NAME: &[u16] = &[
    b'O' as u16, b'a' as u16, b'd' as u16, b'x' as u16, b'T' as u16, b'o' as u16, b'u' as u16,
    b'c' as u16, b'h' as u16, b'p' as u16, b'a' as u16, b'd' as u16, 0,
];

/// Create the window, subscribe to the pad, pump until told to stop.
fn run(engine: Engine, generation: u64) {
    unsafe {
        let class = WNDCLASSW {
            lpfnWndProc: Some(wnd_proc),
            lpszClassName: CLASS_NAME.as_ptr(),
            ..std::mem::zeroed()
        };
        // A second session re-registers the same class; that is an error we
        // want to ignore rather than a reason not to run.
        RegisterClassW(&class);

        let mut engine = Box::new(engine);
        let hwnd = CreateWindowExW(
            0,
            CLASS_NAME.as_ptr(),
            std::ptr::null(),
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            engine.as_mut() as *mut Engine as *mut _,
        );
        if hwnd.is_null() {
            log::warn!("gestures: could not create the touchpad message window");
            return;
        }

        let device = RAWINPUTDEVICE {
            usUsagePage: HID_USAGE_PAGE_DIGITIZER,
            usUsage: HID_USAGE_DIGITIZER_TOUCH_PAD,
            // INPUTSINK because the window the user is driving belongs to
            // scrcpy: without it the reports stop the moment the desktop is
            // focused, which is the only time they matter.
            dwFlags: RIDEV_INPUTSINK,
            hwndTarget: hwnd,
        };
        if RegisterRawInputDevices(&device, 1, std::mem::size_of::<RAWINPUTDEVICE>() as u32) == 0 {
            log::warn!(
                "gestures: RegisterRawInputDevices failed ({})",
                std::io::Error::last_os_error()
            );
            DestroyWindow(hwnd);
            return;
        }
        if let Ok(mut guard) = reader().lock() {
            *guard = (generation, hwnd as isize);
        }
        log::info!("gestures: reading the touchpad");

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            DispatchMessageW(&msg);
        }

        // RIDEV_REMOVE requires a null target — passing the window we are
        // about to destroy makes the call fail and leaves the process
        // subscribed with nowhere to deliver.
        let remove = RAWINPUTDEVICE {
            usUsagePage: HID_USAGE_PAGE_DIGITIZER,
            usUsage: HID_USAGE_DIGITIZER_TOUCH_PAD,
            dwFlags: RIDEV_REMOVE,
            hwndTarget: std::ptr::null_mut(),
        };
        RegisterRawInputDevices(&remove, 1, std::mem::size_of::<RAWINPUTDEVICE>() as u32);
        if let Ok(mut guard) = reader().lock() {
            if guard.0 == generation {
                *guard = (0, 0);
            }
        }
        log::info!("gestures: stopped reading the touchpad");
    }
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_CREATE => {
            let create = &*(lparam as *const CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams as isize);
            0
        }
        WM_INPUT => {
            let engine = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Engine;
            if !engine.is_null() {
                (*engine).on_input(lparam);
            }
            // Raw input is documented as still needing DefWindowProc for
            // cleanup even once the data has been read.
            DefWindowProcW(hwnd, msg, wparam, lparam)
        }
        WM_CLOSE => {
            DestroyWindow(hwnd);
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

// ── Standing down Windows' own three-finger gestures ──────────────────
//
// The one piece of this feature that reaches outside the app. Raw input tees
// rather than filters, so without it a three-finger swipe up would open our
// window switcher AND Task View, and the desktop would lose focus to the
// shell — the gesture would read as broken.
//
// Treated exactly like the phone's display profile: snapshot first, write
// second, and put back both when the session ends and at the next launch, so
// a crash costs the user a restart of this app rather than a Windows setting
// they have to find and repair by hand.

fn snapshot_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    app.path()
        .app_config_dir()
        .ok()
        .map(|d| d.join("host-touchpad.json"))
}

/// Stand Windows' own three-finger gestures down, or give them back.
///
/// Driven from the worker's tick rather than from session start, so the master
/// switch in Settings takes effect the moment it is flipped. Idempotent in both
/// directions: the presence of the snapshot file IS the current state.
pub fn set_suppressed(app: &AppHandle, want: bool) {
    let held = snapshot_path(app).is_some_and(|p| p.exists());
    if want && !held {
        suppress(app);
    } else if !want && held {
        restore_host_settings(app);
    }
}

/// Take Windows' three-finger gestures out of the way, remembering what they
/// were. A no-op if a snapshot already exists — that one is the truth, and
/// overwriting it with the zeroes we ourselves wrote would strand the user's
/// real settings.
fn suppress(app: &AppHandle) {
    let Some(path) = snapshot_path(app) else {
        return;
    };
    if path.exists() {
        return;
    }
    let snapshot: HashMap<String, Option<u32>> = SUPPRESSED
        .iter()
        .map(|name| (name.to_string(), read_dword(name)))
        .collect();
    if snapshot.values().all(|v| *v == Some(0)) {
        // Already the way we want them, by the user's own choice. Writing a
        // snapshot here would mean restoring — and thereby *enabling* —
        // gestures they had turned off themselves.
        return;
    }

    // The snapshot goes to disk BEFORE anything is written, and a failure to
    // write it is a reason not to touch the registry at all. The other order
    // has one outcome we must never produce: the user's three-finger gestures
    // zeroed with no record of what they were, which is not something they can
    // put back without knowing the values by heart.
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let Ok(json) = serde_json::to_string(&snapshot) else {
        return;
    };
    if let Err(e) = std::fs::write(&path, json) {
        log::warn!("gestures: could not record the host's touchpad settings ({e}) — leaving them alone");
        return;
    }

    for name in SUPPRESSED {
        write_dword(name, 0);
    }
    announce();
    log::info!("gestures: Windows' own three-finger gestures stood down for this session");
}

/// Put back whatever [`suppress`] found, and forget the snapshot.
///
/// A value that was absent before is DELETED rather than set to its default:
/// Windows treats absent as "the user never chose", and leaving a 1 behind
/// would pin a preference they never expressed.
pub fn restore_host_settings(app: &AppHandle) {
    let Some(path) = snapshot_path(app) else {
        return;
    };
    let Ok(text) = std::fs::read_to_string(&path) else {
        return;
    };
    if let Ok(snapshot) = serde_json::from_str::<HashMap<String, Option<u32>>>(&text) {
        for (name, value) in snapshot {
            match value {
                Some(v) => {
                    write_dword(&name, v);
                }
                None => delete_value(&name),
            }
        }
        announce();
        log::info!("gestures: Windows' own touchpad gestures restored");
    }
    let _ = std::fs::remove_file(&path);
}

/// Tell the shell its touchpad settings moved.
///
/// Best effort, and said plainly because it matters to the user: Windows does
/// not document these values as live-reloadable, and on a machine where the
/// gesture engine has them cached the change lands at the next sign-in. That
/// is the one case where the desktop's gestures and Windows' own both fire
/// until the user signs out once.
fn announce() {
    const SETTING: &[u16] = &[
        b'P' as u16, b'r' as u16, b'e' as u16, b'c' as u16, b'i' as u16, b's' as u16, b'i' as u16,
        b'o' as u16, b'n' as u16, b'T' as u16, b'o' as u16, b'u' as u16, b'c' as u16, b'h' as u16,
        b'P' as u16, b'a' as u16, b'd' as u16, 0,
    ];
    unsafe {
        let mut result = 0usize;
        SendMessageTimeoutW(
            HWND_BROADCAST,
            WM_SETTINGCHANGE,
            0,
            SETTING.as_ptr() as LPARAM,
            SMTO_ABORTIFHUNG,
            200,
            &mut result,
        );
    }
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Open (creating if needed) the per-user Precision Touchpad key.
fn open_ptp_key(write: bool) -> Option<windows_sys::Win32::System::Registry::HKEY> {
    use windows_sys::Win32::System::Registry::{
        RegCreateKeyExW, HKEY_CURRENT_USER, KEY_QUERY_VALUE, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE,
    };
    let path = wide(PTP_KEY);
    let access = if write {
        KEY_QUERY_VALUE | KEY_SET_VALUE
    } else {
        KEY_QUERY_VALUE
    };
    let mut key = std::ptr::null_mut();
    let rc = unsafe {
        RegCreateKeyExW(
            HKEY_CURRENT_USER,
            path.as_ptr(),
            0,
            std::ptr::null(),
            REG_OPTION_NON_VOLATILE,
            access,
            std::ptr::null(),
            &mut key,
            std::ptr::null_mut(),
        )
    };
    (rc == 0).then_some(key)
}

fn read_dword(name: &str) -> Option<u32> {
    use windows_sys::Win32::System::Registry::{RegCloseKey, RegQueryValueExW};
    let key = open_ptp_key(false)?;
    let value = wide(name);
    let mut data = 0u32;
    let mut size = std::mem::size_of::<u32>() as u32;
    let mut kind = 0u32;
    let rc = unsafe {
        RegQueryValueExW(
            key,
            value.as_ptr(),
            std::ptr::null(),
            &mut kind,
            &mut data as *mut u32 as *mut u8,
            &mut size,
        )
    };
    unsafe { RegCloseKey(key) };
    (rc == 0).then_some(data)
}

fn write_dword(name: &str, value: u32) -> bool {
    use windows_sys::Win32::System::Registry::{RegCloseKey, RegSetValueExW, REG_DWORD};
    let Some(key) = open_ptp_key(true) else {
        return false;
    };
    let name = wide(name);
    let rc = unsafe {
        RegSetValueExW(
            key,
            name.as_ptr(),
            0,
            REG_DWORD,
            &value as *const u32 as *const u8,
            std::mem::size_of::<u32>() as u32,
        )
    };
    unsafe { RegCloseKey(key) };
    rc == 0
}

fn delete_value(name: &str) {
    use windows_sys::Win32::System::Registry::{RegCloseKey, RegDeleteValueW};
    let Some(key) = open_ptp_key(true) else {
        return;
    };
    let name = wide(name);
    unsafe {
        RegDeleteValueW(key, name.as_ptr());
        RegCloseKey(key);
    }
}
