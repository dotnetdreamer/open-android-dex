//! One phone app in a window of its own on Windows (doc/apps-on-pc.md, "The
//! window").
//!
//! A real Win32 window, not a webview: its own thread and message loop, its
//! own D3D11 device and swap chain (render.rs), and its own link to the phone
//! (link.rs: a socket, with a reader, a writer and a clock of its own). The
//! window adds two threads, so nothing waits on anything it does not need to:
//!
//! - the window's (messages, the mouse and keyboard, the title bar's buttons);
//! - the picture's (decode and present — never stopped by Windows' modal loops
//!   while the window is dragged or resized, which a picture drawn from the
//!   message loop would be).
//!
//! The title bar is ours, drawn into the same surface as the picture so the
//! two can never separate. Windows keeps everything else it gives a window:
//! the resize borders, Snap and its layouts on the maximize button (that
//! button answers HTMAXBUTTON), the shadow and rounded corners, the system
//! menu, Alt+Tab, and a taskbar button of its own with the app's icon — the
//! window has an AppUserModelID per app, so WhatsApp and Gmail are two
//! buttons, either of which can be pinned.

mod frame;
mod render;

use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU32, AtomicU8, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::json;
use windows::core::{w, PCWSTR, PWSTR};
use windows::Win32::Foundation::{HINSTANCE, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows::Win32::Graphics::Dwm::{DwmSetWindowAttribute, DWMWA_USE_IMMERSIVE_DARK_MODE};
use windows::Win32::Graphics::Gdi::{
    GetMonitorInfoW, MonitorFromPoint, MonitorFromRect, ScreenToClient, MONITORINFO, MONITOR_DEFAULTTONEAREST,
};
use windows::Win32::System::Com::{CoInitializeEx, COINIT_APARTMENTTHREADED, COINIT_MULTITHREADED};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Registry::{RegGetValueW, HKEY_CURRENT_USER, RRF_RT_REG_DWORD};
use windows::Win32::UI::HiDpi::{
    AdjustWindowRectExForDpi, GetDpiForMonitor, GetDpiForWindow, GetSystemMetricsForDpi, MDT_EFFECTIVE_DPI,
};
use windows::Win32::UI::Input::Ime::{ImmGetCompositionStringW, ImmGetContext, ImmReleaseContext, GCS_RESULTSTR};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, MapVirtualKeyW, ReleaseCapture, SetCapture, TrackMouseEvent, MAPVK_VK_TO_CHAR, TME_LEAVE,
    TME_NONCLIENT, TRACKMOUSEEVENT, VK_CAPITAL, VK_CONTROL, VK_F4, VK_LWIN, VK_MENU, VK_NUMLOCK, VK_RMENU, VK_RWIN,
    VK_SHIFT, VK_SPACE,
};
use windows::Win32::UI::Shell::PropertiesSystem::{IPropertyStore, SHGetPropertyStoreForWindow};
use windows::Win32::UI::WindowsAndMessaging::*;

use super::keys::{self, Mods, Press};
use super::link::{self, DisplaySize, Event, Link, NetSpec, Spec, Surface};
use super::wire;
use frame::Hit;

/// A window, from outside.
pub struct Window {
    pub id: u64,
    pub serial: String,
    pub pkg: String,
    pub label: String,
    pub linux: Option<i32>,
    shared: Arc<Shared>,
}

impl Window {
    /// Bring it to the front, restored if it was minimised.
    pub fn focus(&self) {
        let hwnd = self.shared.hwnd();
        unsafe {
            if IsIconic(hwnd).as_bool() {
                let _ = ShowWindow(hwnd, SW_RESTORE);
            }
            let _ = SetForegroundWindow(hwnd);
        }
    }

    /// Close it, as its ✕ does: the app goes back to the phone.
    pub fn close(&self) {
        unsafe {
            let _ = PostMessageW(Some(self.shared.hwnd()), WM_CLOSE, WPARAM(0), LPARAM(0));
        }
    }

    /// The phone says what it shows is gone (a Linux window closed): the window goes too.
    pub fn gone(&self) {
        unsafe {
            let _ = PostMessageW(Some(self.shared.hwnd()), WM_APP_GONE, WPARAM(0), LPARAM(0));
        }
    }

    /// A new title: a Linux window's, as its app changes it.
    pub fn set_title(&self, title: &str) {
        self.shared.retitle(title);
    }

    /// Where it is on the screen, in pixels: what a window taking over from it
    /// opens at. None while it is minimised, its rectangle then off-screen.
    pub fn frame(&self) -> Option<[f64; 4]> {
        let hwnd = self.shared.hwnd();
        let mut r = RECT::default();
        unsafe {
            if IsIconic(hwnd).as_bool() {
                return None;
            }
            GetWindowRect(hwnd, &mut r).ok()?;
        }
        Some([r.left as f64, r.top as f64, (r.right - r.left) as f64, (r.bottom - r.top) as f64])
    }
}

/// The scale of the screen the pointer is on — the one a click just opened something from.
pub fn screen_scale() -> f32 {
    unsafe {
        let mut pt = POINT::default();
        let _ = GetCursorPos(&mut pt);
        let monitor = MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST);
        let (mut dx, mut dy) = (96u32, 96u32);
        let _ = GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dx, &mut dy);
        dx.max(96) as f32 / 96.0
    }
}

/// State the window's threads share.
struct Shared {
    link: Arc<Link>,
    hwnd: AtomicIsize,
    client: Mutex<(u32, u32)>,
    /// The window's DPI over 96, ×1000.
    scale_milli: AtomicU32,
    hover: AtomicU8,
    pressed: AtomicU8,
    active: AtomicBool,
    maximized: AtomicBool,
    dark: AtomicBool,
    /// The app's name: what it opened with, then the phone's own word for it.
    label: Mutex<String>,
    device: String,
    message: Mutex<Option<String>>,
    render: Mutex<Option<Sender<RenderMsg>>>,
    /// A Linux window: its ✕ asks the app, and the window goes when the X window does.
    linux: bool,
    /// When the ✕ last asked a Linux app to close its window; a second ✕ soon after closes it here.
    asked_close: Mutex<Option<Instant>>,
}

impl Shared {
    fn hwnd(&self) -> HWND {
        HWND(self.hwnd.load(Ordering::SeqCst) as *mut core::ffi::c_void)
    }

    fn scale(&self) -> f32 {
        self.scale_milli.load(Ordering::SeqCst) as f32 / 1000.0
    }

    fn send(&self, bytes: Vec<u8>) {
        self.link.send(bytes);
    }

    fn repaint(&self) {
        if let Some(tx) = self.render.lock().unwrap().as_ref() {
            let _ = tx.send(RenderMsg::Repaint);
        }
    }

    fn say(&self, text: Option<String>) {
        *self.message.lock().unwrap() = text;
        self.repaint();
    }

    /// The window's name, in its title bar and on its taskbar button.
    fn retitle(&self, name: &str) {
        if name.is_empty() {
            return;
        }
        {
            let mut label = self.label.lock().unwrap();
            if *label == name {
                return;
            }
            *label = name.to_string();
        }
        let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
        unsafe {
            let _ = SetWindowTextW(self.hwnd(), PCWSTR(wide.as_ptr()));
        }
        self.repaint();
    }

    /// The part of the window the phone's display fills: the client area under the bar.
    fn area(&self) -> (u32, u32) {
        let (w, h) = *self.client.lock().unwrap();
        let bar = frame::height(self.scale()) as u32;
        (w, h.saturating_sub(bar))
    }

    /// What the display should be for the window as it is now.
    fn wanted(&self) -> DisplaySize {
        let (w, h) = self.area();
        DisplaySize::for_area(w, h, self.scale())
    }

    /// Ask the phone for the display the window wants, if it is not that already.
    fn ask_size(&self) {
        self.link.ask_size(self.wanted());
    }

    /// A point in the window's client area as a point on the phone's display.
    fn to_display(&self, x: i32, y: i32) -> (u16, u16) {
        let bar = frame::height(self.scale());
        self.link.to_display(x as f64, (y - bar) as f64, self.area())
    }
}

/// What the link to the phone needs of the window, from the link's threads.
impl Surface for Shared {
    fn label(&self) -> String {
        self.label.lock().unwrap().clone()
    }

    fn wanted(&self) -> DisplaySize {
        Shared::wanted(self)
    }

    fn video(&self, flags: u8, pts: u64, data: Vec<u8>) {
        if let Some(tx) = self.render.lock().unwrap().as_ref() {
            let _ = tx.send(RenderMsg::Video(flags, pts, data));
        }
    }

    fn format(&self, coded: (u32, u32), content: (u32, u32)) {
        if let Some(tx) = self.render.lock().unwrap().as_ref() {
            let _ = tx.send(RenderMsg::Format(coded, content));
        }
    }

    fn named(&self, name: &str) {
        self.retitle(name);
    }

    fn say(&self, text: Option<String>) {
        Shared::say(self, text);
    }

    fn gone(&self) {
        unsafe {
            let _ = PostMessageW(Some(self.hwnd()), WM_APP_GONE, WPARAM(0), LPARAM(0));
        }
    }
}

enum RenderMsg {
    Video(u8, u64, Vec<u8>),
    Format((u32, u32), (u32, u32)),
    Repaint,
    Quit,
}

const CLASS: PCWSTR = w!("OpenAndroidDeXAppWindow");
/// WinUser.h's, which the windows crate files under its Controls module.
const WM_MOUSELEAVE: u32 = 0x02A3;
/// The phone said the app has gone; the window follows.
const WM_APP_GONE: u32 = WM_APP + 1;
const RESIZE_TIMER: usize = 1;
/// How long a resize must hold before the phone is asked for it: the phone
/// makes a new encoder for each size, and a drag passes through dozens.
const RESIZE_SETTLE_MS: u32 = 120;

/// Open a window for `spec`; it runs until it is closed. The window shows
/// at once, with the app's icon and name, and the picture follows.
pub fn open(
    spec: Spec,
    events: Box<dyn Fn(Event) + Send + Sync>,
    clipboard: Box<dyn Fn() -> Option<String> + Send + Sync>,
) -> Result<Arc<Window>, String> {
    let (tx, rx) = mpsc::channel::<Result<Arc<Window>, String>>();
    let name = format!("app-window-{}", spec.pkg);
    thread::Builder::new()
        .name(name)
        .spawn(move || ui_thread(spec, events, clipboard, tx))
        .map_err(|e| e.to_string())?;
    rx.recv_timeout(Duration::from_secs(10))
        .map_err(|_| "the window did not open".to_string())?
}

struct Geometry {
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    scale: f32,
    client: (u32, u32),
}

/// A phone-shaped window on the screen the pointer is on — the one the click
/// that opened it was made on — at that screen's scale, cascaded from the
/// windows already open.
fn geometry(cascade: u32, size: Option<(u32, u32)>) -> Geometry {
    unsafe {
        let mut pt = POINT::default();
        let _ = GetCursorPos(&mut pt);
        let monitor = MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST);
        let mut info = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        let _ = GetMonitorInfoW(monitor, &mut info);
        let work = info.rcWork;
        let (mut dx, mut dy) = (96u32, 96u32);
        let _ = GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dx, &mut dy);
        let dpi = dx.max(96);
        let scale = dpi as f32 / 96.0;
        let bar = frame::height(scale);
        // 412 × 915 dp: a current phone's own shape and size — or the size a
        // Linux window asked for, which is already in pixels.
        let (mut vw, mut vh) = match size {
            Some((w, h)) => (w.max(160) as f32, h.max(120) as f32),
            None => (412.0 * scale, 915.0 * scale),
        };
        let max_h = (work.bottom - work.top) as f32 * 0.9 - bar as f32;
        let max_w = (work.right - work.left) as f32 * 0.95;
        if vh > max_h {
            let k = max_h / vh;
            vw *= k;
            vh *= k;
        }
        if vw > max_w {
            let k = max_w / vw;
            vw *= k;
            vh *= k;
        }
        let client_w = (vw.round() as i32) & !1;
        let client_h = ((vh.round() as i32) & !1) + bar;
        // The borders Windows will add around the client (our bar replaces its caption and top edge).
        let mut r = RECT { left: 0, top: 0, right: client_w, bottom: client_h };
        let _ = AdjustWindowRectExForDpi(&mut r, WS_OVERLAPPEDWINDOW, false, WS_EX_APPWINDOW, dpi);
        let side = -r.left;
        let bottom = r.bottom - client_h;
        let w = client_w + 2 * side;
        let h = client_h + bottom;
        let step = ((28.0 * scale) as i32) * (cascade % 8) as i32;
        let x = work.left + ((work.right - work.left) - w) / 2 + step;
        let y = work.top + ((work.bottom - work.top) - h) / 2 + step / 2;
        Geometry { x, y: y.max(work.top), w, h, scale, client: (client_w as u32, client_h as u32) }
    }
}

/// A window where another was (`Window::frame`, the one it takes over from):
/// the same rectangle, at the scale of the screen it is on.
fn geometry_at(frame: [f64; 4]) -> Geometry {
    unsafe {
        let r = RECT {
            left: frame[0] as i32,
            top: frame[1] as i32,
            right: (frame[0] + frame[2]) as i32,
            bottom: (frame[1] + frame[3]) as i32,
        };
        let monitor = MonitorFromRect(&r, MONITOR_DEFAULTTONEAREST);
        let (mut dx, mut dy) = (96u32, 96u32);
        let _ = GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dx, &mut dy);
        let dpi = dx.max(96);
        // The borders Windows adds around a client, as geometry() reckons them —
        // here taken off the whole window rather than added to a client.
        let mut b = RECT { left: 0, top: 0, right: 100, bottom: 100 };
        let _ = AdjustWindowRectExForDpi(&mut b, WS_OVERLAPPEDWINDOW, false, WS_EX_APPWINDOW, dpi);
        let side = -b.left;
        let bottom = b.bottom - 100;
        let (w, h) = (r.right - r.left, r.bottom - r.top);
        let client = (((w - 2 * side).max(160) as u32) & !1, ((h - bottom).max(160) as u32) & !1);
        Geometry { x: r.left, y: r.top, w, h, scale: dpi as f32 / 96.0, client }
    }
}

fn dark_mode() -> bool {
    unsafe {
        let mut value = 1u32;
        let mut size = 4u32;
        let ok = RegGetValueW(
            HKEY_CURRENT_USER,
            w!("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
            w!("AppsUseLightTheme"),
            RRF_RT_REG_DWORD,
            None,
            Some(&mut value as *mut u32 as *mut _),
            Some(&mut size),
        );
        ok.is_ok() && value == 0
    }
}

fn register_class() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| unsafe {
        let instance = GetModuleHandleW(None).unwrap_or_default();
        let wc = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: CS_DBLCLKS,
            lpfnWndProc: Some(wndproc),
            hInstance: HINSTANCE(instance.0),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            lpszClassName: CLASS,
            ..Default::default()
        };
        RegisterClassExW(&wc);
    });
}

/// The window's own thread: makes it, then runs its message loop until it is destroyed.
fn ui_thread(
    spec: Spec,
    events: Box<dyn Fn(Event) + Send + Sync>,
    clipboard: Box<dyn Fn() -> Option<String> + Send + Sync>,
    opened: Sender<Result<Arc<Window>, String>>,
) {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
    register_class();
    let geo = match spec.frame {
        Some(frame) => geometry_at(frame),
        None => geometry(spec.cascade, spec.size),
    };
    let (link, out_rx) = Link::new(spec.clicked, events, clipboard);
    let shared = Arc::new(Shared {
        link,
        hwnd: AtomicIsize::new(0),
        client: Mutex::new(geo.client),
        scale_milli: AtomicU32::new((geo.scale * 1000.0) as u32),
        hover: AtomicU8::new(0),
        pressed: AtomicU8::new(0),
        active: AtomicBool::new(true),
        maximized: AtomicBool::new(false),
        dark: AtomicBool::new(dark_mode()),
        label: Mutex::new(spec.label.clone()),
        // The phone, and how this window reaches it: "Galaxy S25 · USB".
        device: if spec.device.is_empty() { spec.via.to_string() } else { format!("{} · {}", spec.device, spec.via) },
        message: Mutex::new(Some(format!("Opening on {}…", if spec.device.is_empty() { "your phone" } else { &spec.device }))),
        render: Mutex::new(None),
        linux: spec.linux.is_some(),
        asked_close: Mutex::new(None),
    });
    let state = Box::new(Ui {
        shared: shared.clone(),
        buttons: 0,
        tracking: false,
        nc_tracking: false,
        keys_down: HashSet::new(),
        swallow_char: false,
        high_surrogate: None,
        render_thread: None,
        props: None,
    });
    let state_ptr = Box::into_raw(state);
    let title: Vec<u16> = spec.label.encode_utf16().chain(std::iter::once(0)).collect();
    let hwnd = unsafe {
        CreateWindowExW(
            WS_EX_APPWINDOW | WS_EX_NOREDIRECTIONBITMAP,
            CLASS,
            PCWSTR(title.as_ptr()),
            WS_OVERLAPPEDWINDOW,
            geo.x,
            geo.y,
            geo.w,
            geo.h,
            None,
            None,
            None,
            Some(state_ptr as *const core::ffi::c_void),
        )
    };
    let hwnd = match hwnd {
        Ok(h) => h,
        Err(e) => {
            unsafe { drop(Box::from_raw(state_ptr)) };
            let _ = opened.send(Err(format!("the window could not be made: {e}")));
            return;
        }
    };
    shared.hwnd.store(hwnd.0 as isize, Ordering::SeqCst);
    unsafe {
        let dark: i32 = shared.dark.load(Ordering::SeqCst) as i32;
        let _ = DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &dark as *const i32 as *const _, 4);
        // The client area becomes the whole window less its side and bottom borders (WM_NCCALCSIZE).
        let _ = SetWindowPos(hwnd, None, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        *shared.client.lock().unwrap() = ((rc.right - rc.left) as u32, (rc.bottom - rc.top) as u32);
        let scale = GetDpiForWindow(hwnd) as f32 / 96.0;
        shared.scale_milli.store((scale * 1000.0) as u32, Ordering::SeqCst);
        if let Some(png) = &spec.icon_png {
            set_icons(hwnd, png);
        }
        (*state_ptr).props = taskbar_identity(hwnd, &spec);
    }

    // the picture
    let (render_tx, render_rx) = mpsc::channel::<RenderMsg>();
    *shared.render.lock().unwrap() = Some(render_tx);
    {
        let shared = shared.clone();
        let icon = spec.icon_png.clone();
        let t = thread::Builder::new()
            .name(format!("app-picture-{}", spec.pkg))
            .spawn(move || render_thread(shared, render_rx, icon));
        unsafe { (*state_ptr).render_thread = t.ok() };
    }

    // the phone
    let net = NetSpec {
        serial: spec.serial.clone(),
        socket: spec.socket.clone(),
        pkg: spec.pkg.clone(),
        activity: spec.activity.clone(),
        linux: spec.linux,
        direct: spec.direct,
    };
    link::start(shared.link.clone(), shared.clone(), net, out_rx);

    unsafe {
        let _ = ShowWindow(hwnd, SW_SHOWNORMAL);
        let _ = SetForegroundWindow(hwnd);
    }
    let window = Arc::new(Window {
        id: spec.id,
        serial: spec.serial.clone(),
        pkg: spec.pkg.clone(),
        label: spec.label.clone(),
        linux: spec.linux,
        shared: shared.clone(),
    });
    let _ = opened.send(Ok(window));

    unsafe {
        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    shared.link.event(Event::Closed);
}

/// A QueryPerformanceCounter time as this computer's clock has it (link::now_us).
fn qpc_to_us(qpc: i64) -> i64 {
    use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
    let (mut now, mut freq) = (0i64, 0i64);
    unsafe {
        let _ = QueryPerformanceCounter(&mut now);
        let _ = QueryPerformanceFrequency(&mut freq);
    }
    let here = link::now_us();
    here - ((now - qpc) as i128 * 1_000_000 / freq.max(1) as i128) as i64
}

/// The window's icon, big and small, from the app's own.
fn set_icons(hwnd: HWND, png: &[u8]) {
    unsafe {
        for (which, px) in [(ICON_BIG, 0), (ICON_SMALL, 0)] {
            if let Ok(icon) = CreateIconFromResourceEx(png, true, 0x0003_0000, px, px, LR_DEFAULTCOLOR) {
                SendMessageW(hwnd, WM_SETICON, Some(WPARAM(which as usize)), Some(LPARAM(icon.0 as isize)));
            }
        }
    }
}

/// A taskbar button of its own for the app, and what pinning it starts again.
fn taskbar_identity(hwnd: HWND, spec: &Spec) -> Option<IPropertyStore> {
    use windows::Win32::Storage::EnhancedStorage::{
        PKEY_AppUserModel_ID, PKEY_AppUserModel_RelaunchCommand, PKEY_AppUserModel_RelaunchDisplayNameResource,
        PKEY_AppUserModel_RelaunchIconResource,
    };
    unsafe {
        let store: IPropertyStore = SHGetPropertyStoreForWindow(hwnd).ok()?;
        let id = app_user_model_id(&spec.pkg);
        store.SetValue(&PKEY_AppUserModel_ID, &lpwstr(&id)).ok()?;
        if let Some(cmd) = &spec.relaunch {
            let _ = store.SetValue(&PKEY_AppUserModel_RelaunchCommand, &lpwstr(cmd));
            let _ = store.SetValue(&PKEY_AppUserModel_RelaunchDisplayNameResource, &lpwstr(&spec.label));
            if let Some(icon) = &spec.relaunch_icon {
                let _ = store.SetValue(&PKEY_AppUserModel_RelaunchIconResource, &lpwstr(icon));
            }
        }
        let _ = store.Commit();
        Some(store)
    }
}

/// Windows asks that a window's AppUserModel properties be cleared before it goes.
fn clear_identity(store: &IPropertyStore) {
    use windows::Win32::Storage::EnhancedStorage::{
        PKEY_AppUserModel_ID, PKEY_AppUserModel_RelaunchCommand, PKEY_AppUserModel_RelaunchDisplayNameResource,
        PKEY_AppUserModel_RelaunchIconResource,
    };
    use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
    unsafe {
        let empty = PROPVARIANT::default();
        for key in [
            &PKEY_AppUserModel_ID,
            &PKEY_AppUserModel_RelaunchCommand,
            &PKEY_AppUserModel_RelaunchDisplayNameResource,
            &PKEY_AppUserModel_RelaunchIconResource,
        ] {
            let _ = store.SetValue(key, &empty);
        }
        let _ = store.Commit();
    }
}

/// One phone app's id on the taskbar: the same app from any phone is one button.
pub fn app_user_model_id(pkg: &str) -> String {
    let clean: String = pkg.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '.').collect();
    let id = format!("CCRSTech.OpenAndroidDeX.App.{clean}");
    id.chars().take(128).collect()
}

/// A VT_LPWSTR PROPVARIANT, which the AppUserModel keys require (a BSTR is refused).
fn lpwstr(s: &str) -> windows::Win32::System::Com::StructuredStorage::PROPVARIANT {
    use windows::Win32::System::Com::CoTaskMemAlloc;
    use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
    use windows::Win32::System::Variant::VT_LPWSTR;
    let wide: Vec<u16> = s.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        let mem = CoTaskMemAlloc(wide.len() * 2) as *mut u16;
        if mem.is_null() {
            return PROPVARIANT::default();
        }
        std::ptr::copy_nonoverlapping(wide.as_ptr(), mem, wide.len());
        let mut pv = PROPVARIANT::default();
        let inner = &mut pv.Anonymous.Anonymous;
        inner.vt = VT_LPWSTR;
        inner.Anonymous.pwszVal = PWSTR(mem);
        pv
    }
}

// ── the window's messages ──

struct Ui {
    shared: Arc<Shared>,
    /// Mouse buttons held in the picture, as MotionEvent.BUTTON_* bits.
    buttons: u8,
    tracking: bool,
    nc_tracking: bool,
    /// Keys sent to the phone as keys, so their release goes too.
    keys_down: HashSet<u32>,
    /// The key just sent as a key also makes a WM_CHAR, which is not text.
    swallow_char: bool,
    high_surrogate: Option<u16>,
    render_thread: Option<thread::JoinHandle<()>>,
    props: Option<IPropertyStore>,
}

fn lo(l: LPARAM) -> i32 {
    (l.0 & 0xFFFF) as i16 as i32
}

fn hi(l: LPARAM) -> i32 {
    ((l.0 >> 16) & 0xFFFF) as i16 as i32
}

fn mods() -> Mods {
    unsafe {
        let down = |vk: u16| (GetKeyState(vk as i32) as u16 & 0x8000) != 0;
        let toggled = |vk: u16| (GetKeyState(vk as i32) & 1) != 0;
        let ctrl = down(VK_CONTROL.0);
        let alt = down(VK_MENU.0);
        Mods {
            shift: down(VK_SHIFT.0),
            ctrl,
            alt,
            altgr: ctrl && down(VK_RMENU.0),
            win: down(VK_LWIN.0) || down(VK_RWIN.0),
            caps: toggled(VK_CAPITAL.0),
            num: toggled(VK_NUMLOCK.0),
        }
    }
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    unsafe {
        if msg == WM_NCCREATE {
            let cs = &*(lp.0 as *const CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, cs.lpCreateParams as isize);
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
        let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Ui;
        if ptr.is_null() {
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
        if msg == WM_NCDESTROY {
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
            drop(Box::from_raw(ptr));
            PostQuitMessage(0);
            return LRESULT(0);
        }
        (*ptr).handle(hwnd, msg, wp, lp)
    }
}

impl Ui {
    fn bar_height(&self) -> i32 {
        frame::height(self.shared.scale())
    }

    fn width(&self) -> i32 {
        self.shared.client.lock().unwrap().0 as i32
    }

    fn set_hover(&self, h: Hit) {
        if self.shared.hover.swap(h.code(), Ordering::SeqCst) != h.code() {
            self.shared.repaint();
        }
    }

    fn set_pressed(&self, h: Hit) {
        if self.shared.pressed.swap(h.code(), Ordering::SeqCst) != h.code() {
            self.shared.repaint();
        }
    }

    fn pointer(&self, x: i32, y: i32) {
        let (dx, dy) = self.shared.to_display(x, y);
        self.shared.send(wire::pointer(self.buttons, dx, dy, mods().meta()));
    }

    fn key_tap(&self, code: u16) {
        self.shared.send(wire::key(true, 0, code, 0));
        self.shared.send(wire::key(false, 0, code, 0));
    }

    /// This computer's clipboard to the phone, ahead of what is typed next.
    fn hand_clipboard_over(&self) {
        self.shared.link.hand_clipboard_over();
    }

    fn close(&mut self, hwnd: HWND, tell_phone: bool) {
        if !self.shared.link.close(tell_phone) {
            return;
        }
        if let Some(tx) = self.shared.render.lock().unwrap().take() {
            let _ = tx.send(RenderMsg::Quit);
        }
        if let Some(t) = self.render_thread.take() {
            let until = Instant::now() + Duration::from_millis(800);
            while !t.is_finished() && Instant::now() < until {
                thread::sleep(Duration::from_millis(5));
            }
        }
        if let Some(store) = self.props.take() {
            clear_identity(&store);
        }
        unsafe {
            let _ = DestroyWindow(hwnd);
        }
    }

    fn button_bit(msg: u32) -> u8 {
        match msg {
            WM_LBUTTONDOWN | WM_LBUTTONUP | WM_LBUTTONDBLCLK => 1, // BUTTON_PRIMARY
            WM_RBUTTONDOWN | WM_RBUTTONUP | WM_RBUTTONDBLCLK => 2, // BUTTON_SECONDARY
            WM_MBUTTONDOWN | WM_MBUTTONUP | WM_MBUTTONDBLCLK => 4, // BUTTON_TERTIARY
            _ => 0,
        }
    }

    unsafe fn handle(&mut self, hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
        unsafe {
            match msg {
                WM_NCCALCSIZE if wp.0 != 0 => {
                    // Windows' frame, less its caption and top edge: the bar is ours, the
                    // side and bottom borders (and so resizing from them) stay Windows'.
                    let params = &mut *(lp.0 as *mut NCCALCSIZE_PARAMS);
                    let top = params.rgrc[0].top;
                    let r = DefWindowProcW(hwnd, msg, wp, lp);
                    params.rgrc[0].top = top;
                    if IsZoomed(hwnd).as_bool() {
                        // A maximised window's frame hangs off the screen: keep the bar on it.
                        let dpi = GetDpiForWindow(hwnd);
                        params.rgrc[0].top +=
                            GetSystemMetricsForDpi(SM_CYFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
                    }
                    r
                }
                WM_NCHITTEST => {
                    let r = DefWindowProcW(hwnd, msg, wp, lp);
                    if r.0 as u32 != HTCLIENT {
                        return r;
                    }
                    let mut pt = POINT { x: lo(lp), y: hi(lp) };
                    let _ = ScreenToClient(hwnd, &mut pt);
                    let dpi = GetDpiForWindow(hwnd);
                    let edge = GetSystemMetricsForDpi(SM_CYFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
                    let width = self.width();
                    if !IsZoomed(hwnd).as_bool() && pt.y < edge {
                        return LRESULT(if pt.x < edge * 2 {
                            HTTOPLEFT
                        } else if pt.x >= width - edge * 2 {
                            HTTOPRIGHT
                        } else {
                            HTTOP
                        } as isize);
                    }
                    LRESULT(match frame::hit(width, self.shared.scale(), pt.x, pt.y) {
                        Hit::Close => HTCLOSE,
                        Hit::Max => HTMAXBUTTON,
                        Hit::Min => HTMINBUTTON,
                        Hit::Caption => HTCAPTION,
                        Hit::Back | Hit::None => HTCLIENT,
                    } as isize)
                }
                // The caption buttons are non-client parts Windows would draw itself.
                WM_NCMOUSEMOVE => {
                    let h = match wp.0 as u32 {
                        HTCLOSE => Hit::Close,
                        HTMAXBUTTON => Hit::Max,
                        HTMINBUTTON => Hit::Min,
                        _ => Hit::None,
                    };
                    self.set_hover(h);
                    if !self.nc_tracking {
                        let mut t = TRACKMOUSEEVENT {
                            cbSize: std::mem::size_of::<TRACKMOUSEEVENT>() as u32,
                            dwFlags: TME_LEAVE | TME_NONCLIENT,
                            hwndTrack: hwnd,
                            dwHoverTime: 0,
                        };
                        let _ = TrackMouseEvent(&mut t);
                        self.nc_tracking = true;
                    }
                    if h == Hit::None {
                        DefWindowProcW(hwnd, msg, wp, lp)
                    } else {
                        LRESULT(0)
                    }
                }
                WM_NCMOUSELEAVE => {
                    self.nc_tracking = false;
                    self.set_hover(Hit::None);
                    self.set_pressed(Hit::None);
                    LRESULT(0)
                }
                WM_NCLBUTTONDOWN | WM_NCLBUTTONDBLCLK => match wp.0 as u32 {
                    HTCLOSE => {
                        self.set_pressed(Hit::Close);
                        LRESULT(0)
                    }
                    HTMAXBUTTON => {
                        self.set_pressed(Hit::Max);
                        LRESULT(0)
                    }
                    HTMINBUTTON => {
                        self.set_pressed(Hit::Min);
                        LRESULT(0)
                    }
                    _ => DefWindowProcW(hwnd, msg, wp, lp),
                },
                WM_NCLBUTTONUP => {
                    let pressed = Hit::from_code(self.shared.pressed.load(Ordering::SeqCst));
                    self.set_pressed(Hit::None);
                    match (wp.0 as u32, pressed) {
                        (HTCLOSE, Hit::Close) => {
                            let _ = PostMessageW(Some(hwnd), WM_CLOSE, WPARAM(0), LPARAM(0));
                            LRESULT(0)
                        }
                        (HTMAXBUTTON, Hit::Max) => {
                            let _ = ShowWindow(hwnd, if IsZoomed(hwnd).as_bool() { SW_RESTORE } else { SW_MAXIMIZE });
                            LRESULT(0)
                        }
                        (HTMINBUTTON, Hit::Min) => {
                            let _ = ShowWindow(hwnd, SW_MINIMIZE);
                            LRESULT(0)
                        }
                        (HTCLOSE | HTMAXBUTTON | HTMINBUTTON, _) => LRESULT(0),
                        _ => DefWindowProcW(hwnd, msg, wp, lp),
                    }
                }
                WM_MOUSEMOVE => {
                    let (x, y) = (lo(lp), hi(lp));
                    if !self.tracking {
                        let mut t = TRACKMOUSEEVENT {
                            cbSize: std::mem::size_of::<TRACKMOUSEEVENT>() as u32,
                            dwFlags: TME_LEAVE,
                            hwndTrack: hwnd,
                            dwHoverTime: 0,
                        };
                        let _ = TrackMouseEvent(&mut t);
                        self.tracking = true;
                    }
                    let in_bar = y < self.bar_height();
                    self.set_hover(if in_bar && frame::hit(self.width(), self.shared.scale(), x, y) == Hit::Back {
                        Hit::Back
                    } else {
                        Hit::None
                    });
                    if self.buttons != 0 || !in_bar {
                        self.pointer(x, y);
                    }
                    LRESULT(0)
                }
                WM_MOUSELEAVE => {
                    self.tracking = false;
                    self.set_hover(Hit::None);
                    if self.buttons == 0 {
                        self.shared.send(wire::json(&json!({"t": "leave"})));
                    }
                    LRESULT(0)
                }
                WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN | WM_LBUTTONDBLCLK | WM_RBUTTONDBLCLK
                | WM_MBUTTONDBLCLK => {
                    let (x, y) = (lo(lp), hi(lp));
                    if self.buttons == 0 && y < self.bar_height() {
                        if msg == WM_LBUTTONDOWN || msg == WM_LBUTTONDBLCLK {
                            if frame::hit(self.width(), self.shared.scale(), x, y) == Hit::Back {
                                self.set_pressed(Hit::Back);
                                SetCapture(hwnd);
                            }
                        }
                        return LRESULT(0);
                    }
                    if self.buttons == 0 {
                        SetCapture(hwnd);
                    }
                    self.buttons |= Self::button_bit(msg);
                    self.pointer(x, y);
                    LRESULT(0)
                }
                WM_LBUTTONUP | WM_RBUTTONUP | WM_MBUTTONUP => {
                    let (x, y) = (lo(lp), hi(lp));
                    if msg == WM_LBUTTONUP && Hit::from_code(self.shared.pressed.load(Ordering::SeqCst)) == Hit::Back {
                        self.set_pressed(Hit::None);
                        let _ = ReleaseCapture();
                        if frame::hit(self.width(), self.shared.scale(), x, y) == Hit::Back {
                            self.key_tap(keys::code::BACK);
                        }
                        return LRESULT(0);
                    }
                    let bit = Self::button_bit(msg);
                    if self.buttons & bit == 0 {
                        return LRESULT(0);
                    }
                    self.buttons &= !bit;
                    self.pointer(x, y);
                    if self.buttons == 0 {
                        let _ = ReleaseCapture();
                    }
                    LRESULT(0)
                }
                WM_CAPTURECHANGED => {
                    if self.buttons != 0 {
                        self.buttons = 0;
                        let mut pt = POINT::default();
                        let _ = GetCursorPos(&mut pt);
                        let _ = ScreenToClient(hwnd, &mut pt);
                        self.pointer(pt.x, pt.y);
                    }
                    self.set_pressed(Hit::None);
                    LRESULT(0)
                }
                WM_XBUTTONDOWN | WM_XBUTTONUP => {
                    // A mouse's side buttons are Back and Forward, as a real mouse's are on Android.
                    let code = if (wp.0 >> 16) & 0xFFFF == 1 { keys::code::BACK } else { keys::code::FORWARD };
                    self.shared.send(wire::key(msg == WM_XBUTTONDOWN, 0, code, 0));
                    LRESULT(1)
                }
                WM_MOUSEWHEEL | WM_MOUSEHWHEEL => {
                    let mut pt = POINT { x: lo(lp), y: hi(lp) };
                    let _ = ScreenToClient(hwnd, &mut pt);
                    if pt.y >= self.bar_height() {
                        let delta = ((wp.0 >> 16) & 0xFFFF) as i16 as f32 / 120.0;
                        let (dx, dy) = self.shared.to_display(pt.x, pt.y);
                        let (h, v) = if msg == WM_MOUSEWHEEL { (0.0, delta) } else { (delta, 0.0) };
                        self.shared.send(wire::scroll(dx, dy, h, v, mods().meta()));
                    }
                    LRESULT(0)
                }
                WM_KEYDOWN | WM_SYSKEYDOWN => {
                    let vk = wp.0 as u32;
                    let m = mods();
                    // Windows' own: Alt+F4 closes, Alt+Space is the window's menu.
                    if msg == WM_SYSKEYDOWN && (vk == VK_F4.0 as u32 || (vk == VK_SPACE.0 as u32 && !m.ctrl)) {
                        return DefWindowProcW(hwnd, msg, wp, lp);
                    }
                    let extended = (lp.0 >> 24) & 1 == 1;
                    let repeat = if (lp.0 >> 30) & 1 == 1 { 1 } else { 0 };
                    let types = MapVirtualKeyW(vk, MAPVK_VK_TO_CHAR) != 0;
                    self.swallow_char = false;
                    match keys::press(vk, extended, types, m) {
                        Press::Text => {}
                        Press::Key(code) => {
                            if code == 50 && m.ctrl && repeat == 0 {
                                // Ctrl+V: what this computer copied reaches the phone first.
                                self.hand_clipboard_over();
                            }
                            self.shared.send(wire::key(true, repeat, code, m.meta()));
                            self.keys_down.insert(vk);
                            self.swallow_char = types;
                        }
                        Press::Nothing => {}
                    }
                    LRESULT(0)
                }
                WM_KEYUP | WM_SYSKEYUP => {
                    let vk = wp.0 as u32;
                    if self.keys_down.remove(&vk) {
                        let extended = (lp.0 >> 24) & 1 == 1;
                        if let Some(code) = keys::android_code(vk, extended) {
                            self.shared.send(wire::key(false, 0, code, mods().meta()));
                        }
                    }
                    if msg == WM_SYSKEYUP && vk == VK_F4.0 as u32 {
                        return DefWindowProcW(hwnd, msg, wp, lp);
                    }
                    LRESULT(0)
                }
                WM_CHAR => {
                    let c = wp.0 as u32;
                    if self.swallow_char {
                        return LRESULT(0);
                    }
                    if (0xD800..0xDC00).contains(&c) {
                        self.high_surrogate = Some(c as u16);
                        return LRESULT(0);
                    }
                    let text = if (0xDC00..0xE000).contains(&c) {
                        match self.high_surrogate.take() {
                            Some(hs) => String::from_utf16_lossy(&[hs, c as u16]),
                            None => return LRESULT(0),
                        }
                    } else if keys::printable(c) {
                        char::from_u32(c).map(String::from).unwrap_or_default()
                    } else {
                        return LRESULT(0);
                    };
                    if !text.is_empty() {
                        self.shared.send(wire::text(&text));
                    }
                    LRESULT(0)
                }
                // Alt with a key was sent as a shortcut; its character is not text.
                WM_SYSCHAR | WM_DEADCHAR | WM_SYSDEADCHAR => LRESULT(0),
                WM_IME_COMPOSITION => {
                    if (lp.0 as u32) & GCS_RESULTSTR.0 != 0 {
                        let himc = ImmGetContext(hwnd);
                        let bytes = ImmGetCompositionStringW(himc, GCS_RESULTSTR, None, 0);
                        if bytes > 0 {
                            let mut buf = vec![0u16; bytes as usize / 2];
                            ImmGetCompositionStringW(himc, GCS_RESULTSTR, Some(buf.as_mut_ptr() as *mut _), bytes as u32);
                            let _ = ImmReleaseContext(hwnd, himc);
                            let text = String::from_utf16_lossy(&buf);
                            if !text.is_empty() {
                                self.shared.send(wire::text(&text));
                            }
                            return LRESULT(0);
                        }
                        let _ = ImmReleaseContext(hwnd, himc);
                    }
                    DefWindowProcW(hwnd, msg, wp, lp)
                }
                WM_SYSCOMMAND => {
                    // Alt pressed and let go on its own would put the window in menu mode — there is no menu.
                    if (wp.0 & 0xFFF0) as u32 == SC_KEYMENU && lp.0 == 0 {
                        return LRESULT(0);
                    }
                    DefWindowProcW(hwnd, msg, wp, lp)
                }
                WM_ACTIVATE => {
                    let active = (wp.0 & 0xFFFF) as u32 != WA_INACTIVE;
                    self.shared.active.store(active, Ordering::SeqCst);
                    self.shared.repaint();
                    if active {
                        self.hand_clipboard_over();
                        self.shared.send(wire::json(&json!({"t": "focus"})));
                    } else {
                        for vk in self.keys_down.drain() {
                            if let Some(code) = keys::android_code(vk, false) {
                                self.shared.send(wire::key(false, 0, code, 0));
                            }
                        }
                        self.shared.send(wire::json(&json!({"t": "blur"})));
                    }
                    DefWindowProcW(hwnd, msg, wp, lp)
                }
                WM_SIZE => {
                    let (w, h) = ((lp.0 & 0xFFFF) as u32, ((lp.0 >> 16) & 0xFFFF) as u32);
                    if wp.0 as u32 != SIZE_MINIMIZED && w > 0 && h > 0 {
                        *self.shared.client.lock().unwrap() = (w, h);
                        self.shared.maximized.store(wp.0 as u32 == SIZE_MAXIMIZED, Ordering::SeqCst);
                        self.shared.repaint();
                        let _ = SetTimer(Some(hwnd), RESIZE_TIMER, RESIZE_SETTLE_MS, None);
                    }
                    LRESULT(0)
                }
                WM_TIMER if wp.0 == RESIZE_TIMER => {
                    let _ = KillTimer(Some(hwnd), RESIZE_TIMER);
                    self.shared.ask_size();
                    LRESULT(0)
                }
                WM_EXITSIZEMOVE => {
                    let _ = KillTimer(Some(hwnd), RESIZE_TIMER);
                    self.shared.ask_size();
                    LRESULT(0)
                }
                WM_DPICHANGED => {
                    let dpi = (wp.0 & 0xFFFF) as u32;
                    self.shared.scale_milli.store((dpi as f32 / 96.0 * 1000.0) as u32, Ordering::SeqCst);
                    let r = &*(lp.0 as *const RECT);
                    let _ = SetWindowPos(hwnd, None, r.left, r.top, r.right - r.left, r.bottom - r.top,
                        SWP_NOZORDER | SWP_NOACTIVATE);
                    self.shared.ask_size();
                    LRESULT(0)
                }
                WM_SETTINGCHANGE => {
                    let dark = dark_mode();
                    if self.shared.dark.swap(dark, Ordering::SeqCst) != dark {
                        let v: i32 = dark as i32;
                        let _ = DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &v as *const i32 as *const _, 4);
                        self.shared.repaint();
                    }
                    DefWindowProcW(hwnd, msg, wp, lp)
                }
                WM_GETMINMAXINFO => {
                    let info = &mut *(lp.0 as *mut MINMAXINFO);
                    let s = self.shared.scale();
                    info.ptMinTrackSize = POINT { x: (240.0 * s) as i32, y: (320.0 * s) as i32 };
                    LRESULT(0)
                }
                WM_PAINT => {
                    let mut ps = windows::Win32::Graphics::Gdi::PAINTSTRUCT::default();
                    let _ = windows::Win32::Graphics::Gdi::BeginPaint(hwnd, &mut ps);
                    let _ = windows::Win32::Graphics::Gdi::EndPaint(hwnd, &ps);
                    self.shared.repaint();
                    LRESULT(0)
                }
                WM_ERASEBKGND => LRESULT(1),
                WM_CLOSE => {
                    if self.shared.linux {
                        // Asked of the app, which may want to save first; the window
                        // goes when its X window does. A second ✕ soon after closes it
                        // here, for an app that does not answer.
                        let mut asked = self.shared.asked_close.lock().unwrap();
                        let again = asked.is_some_and(|at| at.elapsed() < Duration::from_secs(6));
                        if !again {
                            *asked = Some(Instant::now());
                            drop(asked);
                            self.shared.send(wire::json(&json!({"t": "close", "app": "ask"})));
                            return LRESULT(0);
                        }
                    }
                    self.close(hwnd, true);
                    LRESULT(0)
                }
                WM_APP_GONE => {
                    self.close(hwnd, false);
                    LRESULT(0)
                }
                _ => DefWindowProcW(hwnd, msg, wp, lp),
            }
        }
    }
}

// ── the picture's thread ──

fn render_thread(shared: Arc<Shared>, rx: Receiver<RenderMsg>, icon: Option<Vec<u8>>) {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
    let (w, h) = *shared.client.lock().unwrap();
    let mut r = match render::Renderer::new(shared.hwnd(), w, h, icon.as_deref()) {
        Ok(r) => r,
        Err(e) => {
            log::error!("apps: {}: no picture on this computer's GPU: {e}", shared.label.lock().unwrap());
            shared.link.event(Event::Failed(format!("This computer's graphics could not show the app: {e}")));
            return;
        }
    };
    let mut dirty = true;
    let mut frames = 0u64;
    let mut stats_at = Instant::now();
    let mut decode_us = 0u128;
    // The newest picture's timestamp, until it has been shown once.
    let mut unshown_pts: Option<u64> = None;
    // How old each picture was when it was presented, ms.
    let mut ages: Vec<f64> = Vec::new();
    // Pictures presented and not yet seen on the screen: DXGI's count for each, and its timestamp.
    let mut on_the_way: std::collections::VecDeque<(u32, u64)> = std::collections::VecDeque::new();
    // How old each was when Windows put it on the screen — the refresh it went out at — ms.
    let mut on_screen: Vec<f64> = Vec::new();
    loop {
        // Everything that has come, before drawing once: a backlog is decoded
        // and only its newest picture shown.
        let first = if dirty { rx.recv_timeout(Duration::from_millis(0)) } else { rx.recv_timeout(Duration::from_millis(250)) };
        let mut next = match first {
            Ok(m) => Some(m),
            Err(RecvTimeoutError::Timeout) => None,
            Err(RecvTimeoutError::Disconnected) => break,
        };
        let mut quit = false;
        while let Some(m) = next.take() {
            match m {
                RenderMsg::Video(flags, pts, data) => {
                    let t0 = Instant::now();
                    if r.decode(&data, pts, flags & wire::VIDEO_CONFIG != 0) {
                        dirty = true;
                        frames += 1;
                        unshown_pts = Some(pts);
                    }
                    decode_us += t0.elapsed().as_micros();
                }
                RenderMsg::Format(coded, content) => {
                    r.video_format(coded, content);
                    if !r.has_decoder() {
                        shared.say(Some("This computer could not decode the app's picture.".into()));
                    }
                }
                RenderMsg::Repaint => dirty = true,
                RenderMsg::Quit => quit = true,
            }
            next = rx.try_recv().ok();
        }
        if quit {
            break;
        }
        if dirty {
            let (w, h) = *shared.client.lock().unwrap();
            if let Err(e) = r.resize(w, h) {
                log::warn!("apps: resizing the picture: {e}");
            }
            let _ = r.set_scale(shared.scale());
            r.wait_ready(20);
            let message = shared.message.lock().unwrap().clone();
            let label = shared.label.lock().unwrap().clone();
            let look = render::Look {
                label: &label,
                device: &shared.device,
                active: shared.active.load(Ordering::SeqCst),
                maximized: shared.maximized.load(Ordering::SeqCst),
                hover: Hit::from_code(shared.hover.load(Ordering::SeqCst)),
                pressed: Hit::from_code(shared.pressed.load(Ordering::SeqCst)),
                dark: shared.dark.load(Ordering::SeqCst),
                message: if r.has_picture() { None } else { message.as_deref() },
            };
            let drawn = r.draw(&look);
            if let Err(e) = &drawn {
                log::warn!("apps: drawing: {e}");
            }
            dirty = false;
            if let Some(pts) = unshown_pts.take() {
                if drawn.is_ok() && r.has_picture() {
                    // Presented: the first one is the window's "on screen", timed from the click.
                    if !shared.link.is_live() {
                        shared.message.lock().unwrap().take();
                        shared.link.shown();
                    }
                    if let Some(age) = shared.link.age_ms(pts) {
                        ages.push(age);
                    }
                    if let Some(n) = r.present_count() {
                        on_the_way.push_back((n, pts));
                        if on_the_way.len() > 32 {
                            on_the_way.pop_front();
                        }
                    }
                }
            }
        }
        // Which of them Windows has put on the screen since, and when.
        if let Some(&(oldest, _)) = on_the_way.front() {
            if let Some((count, at)) = r.on_screen().filter(|&(c, _)| c >= oldest) {
                let at_us = qpc_to_us(at);
                while let Some(&(n, pts)) = on_the_way.front() {
                    if n > count {
                        break;
                    }
                    on_the_way.pop_front();
                    if n == count {
                        if let Some(age) = shared.link.age_ms_at(pts, at_us) {
                            on_screen.push(age);
                        }
                    }
                }
            }
        }
        if stats_at.elapsed() >= Duration::from_secs(10) && frames > 0 {
            let (median, p95) = link::percentiles(&mut ages);
            let (seen, seen95) = link::percentiles(&mut on_screen);
            let ms = |v: Option<f64>| v.map(|m| format!("{m:.1} ms")).unwrap_or_else(|| "?".into());
            log::info!(
                "apps: {}: {} frames in {:.0}s, decode {:.2} ms each; after the phone composed them, presented {} (p95 {}), on the screen {} (p95 {})",
                shared.label.lock().unwrap(),
                frames,
                stats_at.elapsed().as_secs_f32(),
                decode_us as f64 / frames as f64 / 1000.0,
                ms(median),
                ms(p95),
                ms(seen),
                ms(seen95),
            );
            frames = 0;
            decode_us = 0;
            ages.clear();
            on_screen.clear();
            stats_at = Instant::now();
        }
    }
}
