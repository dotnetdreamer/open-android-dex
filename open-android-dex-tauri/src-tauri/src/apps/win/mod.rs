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
//!
//! The bar's Sound button opens a menu of where the phone's sound plays
//! (sound.rs), Windows' own popup menu; a file dropped from Explorer goes to
//! the phone (drops.rs). How either went is a note in a strip under the bar,
//! for a few seconds.
//!
//! The bar's More (☰) is the window's own menu: full screen, for now (F11 as
//! well). Full screen, the window covers its screen with no frame and no
//! title bar, and a smaller bar comes down from the top of the screen while
//! the pointer is up there, in the middle, drawn into the same surface as the
//! picture — Remote Desktop's connection bar's shape — and while it is up, a
//! small tab with an arrow pointing down shows where it is. Esc leaves.

mod frame;
mod render;

use std::collections::HashSet;
use std::ffi::OsString;
use std::os::windows::ffi::OsStringExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU32, AtomicU64, AtomicU8, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::json;
use windows::core::{w, PCSTR, PCWSTR, PWSTR};
use windows::Win32::Foundation::{HINSTANCE, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows::Win32::Graphics::Dwm::{
    DwmSetWindowAttribute, DWMWA_TRANSITIONS_FORCEDISABLED, DWMWA_USE_IMMERSIVE_DARK_MODE,
    DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DEFAULT, DWMWCP_DONOTROUND, DWMWINDOWATTRIBUTE,
};
use windows::Win32::Graphics::Gdi::{
    ClientToScreen, GetMonitorInfoW, MonitorFromPoint, MonitorFromRect, MonitorFromWindow, ScreenToClient, MONITORINFO,
    MONITOR_DEFAULTTONEAREST,
};
use windows::Win32::System::Com::{CoInitializeEx, COINIT_APARTMENTTHREADED, COINIT_MULTITHREADED};
use windows::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress, LoadLibraryW};
use windows::Win32::System::Registry::{RegGetValueW, HKEY_CURRENT_USER, RRF_RT_REG_DWORD};
use windows::Win32::UI::HiDpi::{
    AdjustWindowRectExForDpi, GetDpiForMonitor, GetDpiForWindow, GetSystemMetricsForDpi, MDT_EFFECTIVE_DPI,
};
use windows::Win32::UI::Input::Ime::{ImmGetCompositionStringW, ImmGetContext, ImmReleaseContext, GCS_RESULTSTR};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, MapVirtualKeyW, ReleaseCapture, SetCapture, TrackMouseEvent, MAPVK_VK_TO_CHAR, TME_LEAVE,
    TME_NONCLIENT, TRACKMOUSEEVENT, VK_CAPITAL, VK_CONTROL, VK_ESCAPE, VK_F11, VK_F4, VK_LWIN, VK_MENU, VK_NUMLOCK,
    VK_RMENU, VK_RWIN, VK_SHIFT, VK_SPACE,
};
use windows::Win32::UI::Shell::PropertiesSystem::{IPropertyStore, SHGetPropertyStoreForWindow};
use windows::Win32::UI::Shell::{DragAcceptFiles, DragFinish, DragQueryFileW, HDROP};
use windows::Win32::UI::WindowsAndMessaging::*;

use super::keys::{self, Mods, Press};
use super::link::{self, DisplaySize, Event, Host, Link, NetSpec, Refused, Route, Spec, Surface};
use super::sound;
use super::wire;
use frame::Hit;

/// A window, from outside.
pub struct Window {
    pub id: u64,
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

    /// The adb serial its connection goes by now.
    pub fn serial(&self) -> String {
        self.shared.link.route().serial
    }

    /// What the phone's server calls it: a notification of its app opens in it by this.
    pub fn key(&self) -> String {
        self.shared.link.key().to_string()
    }

    /// It is the window the person is using: the one Windows has in front.
    pub fn is_front(&self) -> bool {
        unsafe { GetForegroundWindow() == self.shared.hwnd() }
    }

    /// The way it reaches its phone now: the serial, the server, USB or Wi‑Fi.
    pub fn route(&self) -> Route {
        self.shared.link.route()
    }

    /// Its connection made again over `route` (link::reconnect).
    pub fn reconnect(&self, route: Route, reopen: bool) -> Result<(), Refused> {
        link::reconnect(&self.shared.link, route, reopen)
    }

    /// Its app handed back to the phone over `route`, for a window closed while its connection was gone.
    pub fn hand_back(&self, route: &Route) {
        link::hand_back(&self.shared.link, route);
    }

    /// A short note in the strip under its title bar (Surface::note).
    pub fn note(&self, text: Option<String>, busy: bool) {
        Surface::note(&*self.shared, text, busy);
    }

    /// A line in the middle of the window while it has no picture; None takes it away.
    pub fn say(&self, text: Option<String>) {
        Shared::say(&self.shared, text);
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
    /// The phone's name, and the title bar's "USB · Galaxy S25" made of it and the way there now.
    phone_name: String,
    device: Mutex<String>,
    message: Mutex<Option<String>>,
    render: Mutex<Option<Sender<RenderMsg>>>,
    /// A Linux window: its ✕ asks the app, and the window goes when the X window does.
    linux: bool,
    /// When the ✕ last asked a Linux app to close its window; a second ✕ soon after closes it here.
    asked_close: Mutex<Option<Instant>>,
    /// Said under the bar for a while: where the sound went, how a drop goes.
    note: Mutex<Option<String>>,
    /// Counts the notes, so a note's fading never takes the next one with it.
    note_seq: AtomicU64,
    /// Full screen: no frame and no title bar, the picture the whole screen.
    /// Set before the frame changes, which asks the window about it.
    full_screen: AtomicBool,
    /// While full screen: the window as it was, to go back to.
    windowed: Mutex<Option<Windowed>>,
    /// The full-screen bar: how far down it is (0 to 100), whether it is
    /// coming down or going up, and whether it is due to go up (HIDE_TIMER).
    top_bar: AtomicU8,
    top_bar_down: AtomicBool,
    top_bar_leaving: AtomicBool,
    /// More's menu or the sound menu is open: the full-screen bar stays until it closes.
    menu_open: AtomicBool,
}

/// A full-screen window as it was before: its style, where it was, and
/// whether it was maximised (it is restored first, and maximised again after).
struct Windowed {
    style: isize,
    rect: RECT,
    maximized: bool,
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

    /// The title bar's height: none, full screen.
    fn bar_height(&self) -> i32 {
        if self.full_screen.load(Ordering::SeqCst) {
            0
        } else {
            frame::height(self.scale())
        }
    }

    /// The part of the window the phone's display fills: the client area under the bar.
    fn area(&self) -> (u32, u32) {
        let (w, h) = *self.client.lock().unwrap();
        (w, h.saturating_sub(self.bar_height() as u32))
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
        self.link.to_display(x as f64, (y - self.bar_height()) as f64, self.area())
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

    /// A new way to the phone: "Wi‑Fi · Galaxy S25" in the title bar.
    fn way(&self, via: &str) {
        *self.device.lock().unwrap() = link::way_line(&self.phone_name, via);
        self.repaint();
    }

    fn note(&self, text: Option<String>, busy: bool) {
        let seq = self.note_seq.fetch_add(1, Ordering::SeqCst) + 1;
        *self.note.lock().unwrap() = text;
        self.repaint();
        // Its fading is the window's own thread's timer.
        unsafe {
            let _ = PostMessageW(Some(self.hwnd()), WM_APP_NOTE, WPARAM(busy as usize), LPARAM(seq as isize));
        }
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
/// A note was put up (wparam: busy, lparam: its number): the window times its fading.
const WM_APP_NOTE: u32 = WM_APP + 2;
const RESIZE_TIMER: usize = 1;
const NOTE_TIMER: usize = 2;
/// How long a resize must hold before the phone is asked for it: the phone
/// makes a new encoder for each size, and a drag passes through dozens.
const RESIZE_SETTLE_MS: u32 = 120;
/// How long a note stays once nothing is happening any more.
const NOTE_MS: u32 = 4500;
/// WinUser.h's: the global data a file drop carries, which the windows crate has no name for.
const WM_COPYGLOBALDATA: u32 = 0x0049;
/// The full-screen bar slides a step each tick, and goes up when this one fires.
const SLIDE_TIMER: usize = 3;
const HIDE_TIMER: usize = 4;
const SLIDE_MS: u32 = 15;
const SLIDE_STEP: u8 = 20;
/// How near the top of the screen the pointer must come to bring the bar
/// down, in pixels; how far off it the pointer may stray before it goes back
/// up, in device-independent pixels; how long it stays once the pointer has
/// left it, and when the window has just gone full screen, to show where it is.
const TOP_EDGE_PX: i32 = 2;
const BAR_NEAR_DIP: f32 = 24.0;
const BAR_STAYS_MS: u32 = 600;
const BAR_ON_ARRIVAL_MS: u32 = 2000;
/// More's menu.
const MENU_FULL_SCREEN: usize = 1;

/// Open a window for `spec`; it runs until it is closed. The window shows
/// at once, with the app's icon and name, and the picture follows.
pub fn open(spec: Spec, host: Arc<dyn Host>) -> Result<Arc<Window>, String> {
    let (tx, rx) = mpsc::channel::<Result<Arc<Window>, String>>();
    let name = format!("app-window-{}", spec.pkg);
    thread::Builder::new()
        .name(name)
        .spawn(move || ui_thread(spec, host, tx))
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

/// The window's popup menus dark when the system is. Windows has no public
/// switch for it: uxtheme's AllowDarkModeForWindow and FlushMenuThemes are
/// exported by ordinal only (133 and 136, the same since Windows 10 1809),
/// and the process-wide half is already done — tao allows dark menus for the
/// app's own windows. Where either is missing the menu is simply light.
fn dark_menus(hwnd: HWND, dark: bool) {
    type Allow = unsafe extern "system" fn(HWND, bool) -> bool;
    type Flush = unsafe extern "system" fn();
    static UXTHEME: std::sync::OnceLock<(Option<Allow>, Option<Flush>)> = std::sync::OnceLock::new();
    let (allow, flush) = *UXTHEME.get_or_init(|| unsafe {
        let Ok(uxtheme) = LoadLibraryW(w!("uxtheme.dll")) else { return (None, None) };
        let allow = GetProcAddress(uxtheme, PCSTR(133 as *const u8)).map(|f| std::mem::transmute::<_, Allow>(f));
        let flush = GetProcAddress(uxtheme, PCSTR(136 as *const u8)).map(|f| std::mem::transmute::<_, Flush>(f));
        (allow, flush)
    });
    unsafe {
        if let Some(allow) = allow {
            allow(hwnd, dark);
        }
        if let Some(flush) = flush {
            flush();
        }
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
fn ui_thread(spec: Spec, host: Arc<dyn Host>, opened: Sender<Result<Arc<Window>, String>>) {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
    register_class();
    let geo = geometry(spec.cascade, spec.size);
    let (link, out_rx) = Link::new(spec.clicked, host);
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
        // How this window reaches the phone, and the phone: "USB · Galaxy S25".
        phone_name: spec.device.clone(),
        device: Mutex::new(link::way_line(&spec.device, spec.via)),
        message: Mutex::new(Some(format!("Opening on {}…", if spec.device.is_empty() { "your phone" } else { &spec.device }))),
        render: Mutex::new(None),
        linux: spec.linux.is_some(),
        asked_close: Mutex::new(None),
        note: Mutex::new(None),
        note_seq: AtomicU64::new(0),
        full_screen: AtomicBool::new(false),
        windowed: Mutex::new(None),
        top_bar: AtomicU8::new(0),
        top_bar_down: AtomicBool::new(false),
        top_bar_leaving: AtomicBool::new(false),
        menu_open: AtomicBool::new(false),
    });
    let state = Box::new(Ui {
        shared: shared.clone(),
        buttons: 0,
        tracking: false,
        nc_tracking: false,
        keys_down: HashSet::new(),
        swallow_char: false,
        high_surrogate: None,
        last_y: 0,
        esc_held: false,
        render_thread: None,
        props: None,
        note_fades: 0,
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
        // Files from Explorer go to the phone. Let through even to an app run
        // as administrator, which Windows would otherwise keep Explorer's drop from.
        DragAcceptFiles(hwnd, true);
        for m in [WM_DROPFILES, WM_COPYDATA, WM_COPYGLOBALDATA] {
            let _ = ChangeWindowMessageFilterEx(hwnd, m, MSGFLT_ALLOW, None);
        }
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
        via: spec.via,
        notif: spec.notif.clone(),
    };
    link::start(shared.link.clone(), shared.clone(), net, out_rx);

    unsafe {
        let _ = ShowWindow(hwnd, SW_SHOWNORMAL);
        let _ = SetForegroundWindow(hwnd);
    }
    let window = Arc::new(Window {
        id: spec.id,
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
    /// Where the pointer last was, down the client area: leaving a full-screen
    /// window over its top (onto a screen above) brings the bar down.
    last_y: i32,
    /// Esc took the window out of full screen: its repeats go nowhere either.
    esc_held: bool,
    render_thread: Option<thread::JoinHandle<()>>,
    props: Option<IPropertyStore>,
    /// The note the fade timer is running for, by its number.
    note_fades: u64,
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

/// One of DWM's window attributes that is a number.
fn set_dwm(hwnd: HWND, attribute: DWMWINDOWATTRIBUTE, value: i32) {
    unsafe {
        let _ = DwmSetWindowAttribute(hwnd, attribute, &value as *const i32 as *const _, 4);
    }
}

/// The window over the whole of a screen: the one `near` (a rectangle Windows
/// suggests) is mostly on, else the one the window is on.
fn fill_screen(hwnd: HWND, near: Option<&RECT>) {
    unsafe {
        let monitor = match near {
            Some(r) => MonitorFromRect(r, MONITOR_DEFAULTTONEAREST),
            None => MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST),
        };
        let mut info = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        if !GetMonitorInfoW(monitor, &mut info).as_bool() {
            return;
        }
        let m = info.rcMonitor;
        let _ = SetWindowPos(hwnd, Some(HWND_TOP), m.left, m.top, m.right - m.left, m.bottom - m.top,
            SWP_FRAMECHANGED | SWP_NOOWNERZORDER);
    }
}

impl Ui {
    fn bar_height(&self) -> i32 {
        self.shared.bar_height()
    }

    fn full_screen(&self) -> bool {
        self.shared.full_screen.load(Ordering::SeqCst)
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

    /// The Sound button's menu: where the phone's sound plays, Windows' own
    /// popup menu, under the button. Picking from it is the app's to carry
    /// out, off this thread; a note says how it went.
    fn sound_menu(&self, hwnd: HWND) {
        let host = self.shared.link.host().clone();
        let items = host.sound_menu();
        if items.is_empty() {
            return;
        }
        unsafe {
            let Ok(menu) = CreatePopupMenu() else { return };
            for (i, item) in items.iter().enumerate() {
                match item {
                    sound::Item::Separator => {
                        let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
                    }
                    sound::Item::Choice { label, checked, enabled, .. } => {
                        let mut flags = MF_STRING;
                        if *checked {
                            flags |= MF_CHECKED;
                        }
                        if !*enabled {
                            flags |= MF_GRAYED;
                        }
                        // The menu keeps a copy of the text.
                        let wide: Vec<u16> = label.encode_utf16().chain(std::iter::once(0)).collect();
                        let _ = AppendMenuW(menu, flags, i + 1, PCWSTR(wide.as_ptr()));
                    }
                }
            }
            dark_menus(hwnd, self.shared.dark.load(Ordering::SeqCst));
            // Under the button, right edges in line, as Windows' own title-bar menus open.
            let b = self.button_rect(Hit::Sound);
            let mut at = POINT { x: b.right, y: b.bottom };
            let _ = ClientToScreen(hwnd, &mut at);
            let flags = TPM_RETURNCMD | TPM_RIGHTALIGN | TPM_TOPALIGN | TPM_NONOTIFY;
            self.shared.menu_open.store(true, Ordering::SeqCst);
            let picked = TrackPopupMenuEx(menu, flags.0, at.x, at.y, hwnd, None).0 as usize;
            self.shared.menu_open.store(false, Ordering::SeqCst);
            let _ = DestroyMenu(menu);
            self.after_menu(hwnd);
            // The menu had the mouse: whatever was under it then is not hovered now.
            self.set_hover(Hit::None);
            if let Some(sound::Item::Choice { choice, enabled: true, .. }) = picked.checked_sub(1).and_then(|i| items.get(i)) {
                host.pick_sound(choice.clone(), self.shared.clone());
            }
        }
    }

    /// Paths dropped from Explorer, for the phone.
    fn dropped(&self, drop: HDROP) {
        let mut paths = Vec::new();
        unsafe {
            let count = DragQueryFileW(drop, u32::MAX, None);
            for i in 0..count {
                let len = DragQueryFileW(drop, i, None) as usize;
                let mut buf = vec![0u16; len + 1];
                let got = DragQueryFileW(drop, i, Some(&mut buf)) as usize;
                paths.push(PathBuf::from(OsString::from_wide(&buf[..got.min(len)])));
            }
            DragFinish(drop);
        }
        if !paths.is_empty() {
            self.shared.link.host().dropped(paths, self.shared.clone());
        }
    }

    fn toggle_full_screen(&mut self, hwnd: HWND) {
        if self.full_screen() {
            self.leave_full_screen(hwnd);
        } else {
            self.enter_full_screen(hwnd);
        }
    }

    /// The whole of the screen the window is on, with no frame and no title
    /// bar. A maximised window is restored first, so that Windows still knows
    /// it as the window it was, and maximised again after.
    fn enter_full_screen(&mut self, hwnd: HWND) {
        if self.full_screen() {
            return;
        }
        unsafe {
            // No animation through the states on the way.
            set_dwm(hwnd, DWMWA_TRANSITIONS_FORCEDISABLED, 1);
            let maximized = IsZoomed(hwnd).as_bool();
            if maximized {
                let _ = ShowWindow(hwnd, SW_RESTORE);
            }
            let mut rect = RECT::default();
            let _ = GetWindowRect(hwnd, &mut rect);
            let style = GetWindowLongPtrW(hwnd, GWL_STYLE);
            *self.shared.windowed.lock().unwrap() = Some(Windowed { style, rect, maximized });
            self.shared.full_screen.store(true, Ordering::SeqCst);
            let _ = SetWindowLongPtrW(hwnd, GWL_STYLE, style & !((WS_CAPTION | WS_THICKFRAME).0 as isize));
            set_dwm(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND.0);
            fill_screen(hwnd, None);
        }
        // What the pointer was over in the title bar is not in the new bar.
        self.set_hover(Hit::None);
        // Down for a moment, so the person sees where it is.
        self.top_bar_down(hwnd);
        self.top_bar_up_soon(hwnd, BAR_ON_ARRIVAL_MS);
    }

    /// Back to the window it was, where it was.
    fn leave_full_screen(&mut self, hwnd: HWND) {
        let Some(w) = self.shared.windowed.lock().unwrap().take() else { return };
        self.top_bar_up(hwnd, false);
        self.shared.full_screen.store(false, Ordering::SeqCst);
        unsafe {
            let _ = SetWindowLongPtrW(hwnd, GWL_STYLE, w.style);
            set_dwm(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DEFAULT.0);
            let r = w.rect;
            let _ = SetWindowPos(hwnd, None, r.left, r.top, r.right - r.left, r.bottom - r.top,
                SWP_FRAMECHANGED | SWP_NOZORDER | SWP_NOOWNERZORDER);
            if w.maximized {
                let _ = ShowWindow(hwnd, SW_MAXIMIZE);
            }
            set_dwm(hwnd, DWMWA_TRANSITIONS_FORCEDISABLED, 0);
        }
    }

    /// Whatever was to send the full-screen bar back up, does not.
    fn keep_top_bar(&self, hwnd: HWND) {
        unsafe {
            let _ = KillTimer(Some(hwnd), HIDE_TIMER);
        }
        self.shared.top_bar_leaving.store(false, Ordering::SeqCst);
    }

    /// The full-screen bar slides down, and stays.
    fn top_bar_down(&self, hwnd: HWND) {
        self.keep_top_bar(hwnd);
        if !self.shared.top_bar_down.swap(true, Ordering::SeqCst) {
            unsafe {
                let _ = SetTimer(Some(hwnd), SLIDE_TIMER, SLIDE_MS, None);
            }
        }
    }

    /// The full-screen bar goes back up `ms` from now, unless that is called off first.
    fn top_bar_up_soon(&self, hwnd: HWND, ms: u32) {
        let s = &self.shared;
        if !s.top_bar_down.load(Ordering::SeqCst)
            || s.menu_open.load(Ordering::SeqCst)
            || s.top_bar_leaving.swap(true, Ordering::SeqCst)
        {
            return;
        }
        unsafe {
            let _ = SetTimer(Some(hwnd), HIDE_TIMER, ms, None);
        }
    }

    /// The full-screen bar goes up: sliding, or at once.
    fn top_bar_up(&self, hwnd: HWND, slide: bool) {
        self.keep_top_bar(hwnd);
        self.shared.top_bar_down.store(false, Ordering::SeqCst);
        self.set_hover(Hit::None);
        unsafe {
            if slide {
                let _ = SetTimer(Some(hwnd), SLIDE_TIMER, SLIDE_MS, None);
            } else {
                let _ = KillTimer(Some(hwnd), SLIDE_TIMER);
                self.shared.top_bar.store(0, Ordering::SeqCst);
                self.shared.repaint();
            }
        }
    }

    /// The pointer at (`x`, `y`) in a full-screen window: at the top of the
    /// screen it brings the bar down, and off the bar for a moment it sends it
    /// back up. What of the bar it is on, if anything: the app does not see it there.
    fn track_top(&self, hwnd: HWND, x: i32, y: i32) -> Hit {
        let (width, scale) = (self.width(), self.shared.scale());
        if y < TOP_EDGE_PX || frame::on_handle(width, scale, x, y) {
            self.top_bar_down(hwnd);
        }
        if !self.shared.top_bar_down.load(Ordering::SeqCst) {
            return Hit::None;
        }
        let t = frame::top_bar(width, scale).rect;
        let near = (BAR_NEAR_DIP * scale) as i32;
        if x >= t.left - near && x < t.right + near && y < t.bottom + near {
            self.keep_top_bar(hwnd);
        } else {
            self.top_bar_up_soon(hwnd, BAR_STAYS_MS);
        }
        frame::top_hit(width, scale, x, y)
    }

    /// What of a bar is at (`x`, `y`): the title bar, or the full-screen bar
    /// while it is down (and its tab while it is up, which is the bar's too).
    fn bar_hit(&self, x: i32, y: i32) -> Hit {
        let (width, scale) = (self.width(), self.shared.scale());
        if !self.full_screen() {
            frame::hit(width, scale, x, y)
        } else if self.shared.top_bar_down.load(Ordering::SeqCst) {
            frame::top_hit(width, scale, x, y)
        } else if frame::on_handle(width, scale, x, y) {
            Hit::Caption
        } else {
            Hit::None
        }
    }

    /// The buttons this window works itself, in its client area: the title
    /// bar's Back, More and Sound (the rest are caption buttons, which Windows
    /// works), and all of the full-screen bar's.
    fn ours(&self, hit: Hit) -> bool {
        match hit {
            Hit::Back | Hit::More | Hit::Sound => true,
            Hit::Min | Hit::Windowed | Hit::Close => self.full_screen(),
            _ => false,
        }
    }

    /// One of those buttons, clicked.
    fn button(&mut self, hwnd: HWND, hit: Hit) {
        unsafe {
            match hit {
                Hit::Back => self.key_tap(keys::code::BACK),
                Hit::More => self.more_menu(hwnd),
                Hit::Sound => self.sound_menu(hwnd),
                Hit::Min => {
                    let _ = ShowWindow(hwnd, SW_MINIMIZE);
                }
                Hit::Windowed => self.leave_full_screen(hwnd),
                Hit::Close => {
                    let _ = PostMessageW(Some(hwnd), WM_CLOSE, WPARAM(0), LPARAM(0));
                }
                _ => {}
            }
        }
    }

    /// Where a bar's button is in the client area: the full-screen bar's while
    /// the window is full screen, else the title bar's.
    fn button_rect(&self, hit: Hit) -> RECT {
        let (width, scale) = (self.width(), self.shared.scale());
        if self.full_screen() {
            let t = frame::top_bar(width, scale);
            let r = match hit {
                Hit::Back => t.back,
                Hit::More => t.more,
                Hit::Sound => t.sound,
                Hit::Min => t.min,
                Hit::Windowed => t.windowed,
                _ => t.close,
            };
            RECT { left: t.rect.left + r.left, right: t.rect.left + r.right, ..r }
        } else {
            let b = frame::bar(width, scale);
            match hit {
                Hit::Back => b.back,
                Hit::More => b.more,
                Hit::Sound => b.sound,
                Hit::Min => b.min,
                Hit::Max => b.max,
                _ => b.close,
            }
        }
    }

    /// A menu has closed: full screen, the bar goes back up if the pointer
    /// left it while the menu was open.
    fn after_menu(&self, hwnd: HWND) {
        if self.full_screen() {
            let mut pt = POINT::default();
            unsafe {
                let _ = GetCursorPos(&mut pt);
                let _ = ScreenToClient(hwnd, &mut pt);
            }
            self.track_top(hwnd, pt.x, pt.y);
        }
    }

    /// More's menu, under its button: full screen, for now, with its key beside it.
    fn more_menu(&mut self, hwnd: HWND) {
        let full = self.full_screen();
        let button = self.button_rect(Hit::More);
        unsafe {
            let Ok(menu) = CreatePopupMenu() else { return };
            let text = if full { w!("Exit full screen\tEsc") } else { w!("Full screen\tF11") };
            let _ = AppendMenuW(menu, MF_STRING, MENU_FULL_SCREEN, text);
            dark_menus(hwnd, self.shared.dark.load(Ordering::SeqCst));
            // Under the button, and never over it.
            let mut corners = [POINT { x: button.left, y: button.top }, POINT { x: button.right, y: button.bottom }];
            for p in &mut corners {
                let _ = ClientToScreen(hwnd, p);
            }
            let clear = TPMPARAMS {
                cbSize: std::mem::size_of::<TPMPARAMS>() as u32,
                rcExclude: RECT { left: corners[0].x, top: corners[0].y, right: corners[1].x, bottom: corners[1].y },
            };
            self.shared.menu_open.store(true, Ordering::SeqCst);
            let flags = TPM_RETURNCMD | TPM_LEFTALIGN | TPM_TOPALIGN | TPM_VERTICAL;
            let chosen = TrackPopupMenuEx(menu, flags.0, corners[0].x, corners[1].y, hwnd, Some(&clear));
            self.shared.menu_open.store(false, Ordering::SeqCst);
            let _ = DestroyMenu(menu);
            // The menu had the mouse: whatever was under it then is not hovered now.
            self.set_hover(Hit::None);
            if chosen.0 as usize == MENU_FULL_SCREEN {
                self.toggle_full_screen(hwnd);
            } else {
                self.after_menu(hwnd);
            }
        }
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
                    if self.full_screen() {
                        // No frame at all: the client area is the whole window.
                        return LRESULT(0);
                    }
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
                    if self.full_screen() {
                        return LRESULT(HTCLIENT as isize);
                    }
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
                        Hit::Back | Hit::Sound | Hit::More | Hit::Windowed | Hit::None => HTCLIENT,
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
                    self.last_y = y;
                    if self.full_screen() {
                        let on = if self.buttons == 0 { self.track_top(hwnd, x, y) } else { Hit::None };
                        self.set_hover(if self.ours(on) { on } else { Hit::None });
                        if on == Hit::None {
                            self.pointer(x, y);
                        }
                        return LRESULT(0);
                    }
                    let in_bar = y < self.bar_height();
                    let on = if in_bar { frame::hit(self.width(), self.shared.scale(), x, y) } else { Hit::None };
                    self.set_hover(if self.ours(on) { on } else { Hit::None });
                    if self.buttons != 0 || !in_bar {
                        self.pointer(x, y);
                    }
                    LRESULT(0)
                }
                WM_MOUSELEAVE => {
                    self.tracking = false;
                    self.set_hover(Hit::None);
                    if self.full_screen() {
                        // Out over the top of the screen, onto one above, is as good as touching it.
                        if self.last_y < TOP_EDGE_PX * 4 {
                            self.top_bar_down(hwnd);
                        } else {
                            self.top_bar_up_soon(hwnd, BAR_STAYS_MS);
                        }
                    }
                    if self.buttons == 0 {
                        self.shared.send(wire::json(&json!({"t": "leave"})));
                    }
                    LRESULT(0)
                }
                WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN | WM_LBUTTONDBLCLK | WM_RBUTTONDBLCLK
                | WM_MBUTTONDBLCLK => {
                    let (x, y) = (lo(lp), hi(lp));
                    // On a bar (the title bar, or the full-screen bar while it is
                    // down), a press is the bar's, never the app's.
                    let on = if self.buttons == 0 { self.bar_hit(x, y) } else { Hit::None };
                    if on != Hit::None {
                        if (msg == WM_LBUTTONDOWN || msg == WM_LBUTTONDBLCLK) && self.ours(on) {
                            self.set_pressed(on);
                            SetCapture(hwnd);
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
                    let pressed = Hit::from_code(self.shared.pressed.load(Ordering::SeqCst));
                    if msg == WM_LBUTTONUP && self.ours(pressed) {
                        self.set_pressed(Hit::None);
                        let _ = ReleaseCapture();
                        if self.bar_hit(x, y) == pressed {
                            self.button(hwnd, pressed);
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
                    if pt.y >= self.bar_height() && self.bar_hit(pt.x, pt.y) == Hit::None {
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
                    // Esc takes a full-screen window out of full screen, and F11 in
                    // and out, as in Windows' own apps; neither reaches the app, nor
                    // do their repeats while they are held.
                    let alone = msg == WM_KEYDOWN && !(m.ctrl || m.alt || m.shift || m.win);
                    if vk == VK_ESCAPE.0 as u32 {
                        if repeat == 1 && self.esc_held {
                            return LRESULT(0);
                        }
                        self.esc_held = alone && self.full_screen();
                        if self.esc_held {
                            self.leave_full_screen(hwnd);
                            return LRESULT(0);
                        }
                    }
                    if vk == VK_F11.0 as u32 && alone {
                        if repeat == 0 {
                            self.toggle_full_screen(hwnd);
                        }
                        return LRESULT(0);
                    }
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
                    if vk == VK_ESCAPE.0 as u32 {
                        self.esc_held = false;
                    }
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
                        if self.full_screen() {
                            self.top_bar_up(hwnd, false);
                        }
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
                WM_APP_NOTE => {
                    // A busy note stays until the next; any other fades.
                    let _ = KillTimer(Some(hwnd), NOTE_TIMER);
                    if wp.0 == 0 {
                        self.note_fades = lp.0 as u64;
                        let _ = SetTimer(Some(hwnd), NOTE_TIMER, NOTE_MS, None);
                    }
                    LRESULT(0)
                }
                WM_TIMER if wp.0 == NOTE_TIMER => {
                    let _ = KillTimer(Some(hwnd), NOTE_TIMER);
                    if self.shared.note_seq.load(Ordering::SeqCst) == self.note_fades {
                        self.shared.note.lock().unwrap().take();
                        self.shared.repaint();
                    }
                    LRESULT(0)
                }
                WM_DROPFILES => {
                    self.dropped(HDROP(wp.0 as *mut core::ffi::c_void));
                    LRESULT(0)
                }
                WM_TIMER if wp.0 == SLIDE_TIMER => {
                    let s = &self.shared;
                    let now = s.top_bar.load(Ordering::SeqCst);
                    let next = if s.top_bar_down.load(Ordering::SeqCst) {
                        now.saturating_add(SLIDE_STEP).min(100)
                    } else {
                        now.saturating_sub(SLIDE_STEP)
                    };
                    s.top_bar.store(next, Ordering::SeqCst);
                    s.repaint();
                    if next == 0 || next == 100 {
                        let _ = KillTimer(Some(hwnd), SLIDE_TIMER);
                    }
                    LRESULT(0)
                }
                WM_TIMER if wp.0 == HIDE_TIMER => {
                    let _ = KillTimer(Some(hwnd), HIDE_TIMER);
                    self.shared.top_bar_leaving.store(false, Ordering::SeqCst);
                    // Not from under an open menu: the bar is looked at again once it closes.
                    if !self.shared.menu_open.load(Ordering::SeqCst) {
                        self.top_bar_up(hwnd, true);
                    }
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
                    if self.full_screen() {
                        // Onto another screen: the whole of that one.
                        fill_screen(hwnd, Some(r));
                    } else {
                        let _ = SetWindowPos(hwnd, None, r.left, r.top, r.right - r.left, r.bottom - r.top,
                            SWP_NOZORDER | SWP_NOACTIVATE);
                    }
                    self.shared.ask_size();
                    LRESULT(0)
                }
                WM_DISPLAYCHANGE => {
                    // The screen's resolution changed under a full-screen window: all of it still.
                    if self.full_screen() {
                        fill_screen(hwnd, None);
                    }
                    DefWindowProcW(hwnd, msg, wp, lp)
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
                    // Wide enough for the bar's buttons — Back, More, the icon, Sound,
                    // minimise, maximise, close — side by side, if nothing else.
                    info.ptMinTrackSize = POINT { x: (300.0 * s) as i32, y: (320.0 * s) as i32 };
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
            let note = shared.note.lock().unwrap().clone();
            let device = shared.device.lock().unwrap().clone();
            let look = render::Look {
                label: &label,
                device: &device,
                active: shared.active.load(Ordering::SeqCst),
                maximized: shared.maximized.load(Ordering::SeqCst),
                hover: Hit::from_code(shared.hover.load(Ordering::SeqCst)),
                pressed: Hit::from_code(shared.pressed.load(Ordering::SeqCst)),
                dark: shared.dark.load(Ordering::SeqCst),
                message: if r.has_picture() { None } else { message.as_deref() },
                note: note.as_deref(),
                full_screen: shared.full_screen.load(Ordering::SeqCst),
                top_bar: shared.top_bar.load(Ordering::SeqCst) as f32 / 100.0,
            };
            let drawn = r.draw(&look);
            if let Err(e) = &drawn {
                log::warn!("apps: drawing: {e}");
            }
            dirty = false;
            if let Some(pts) = unshown_pts.take() {
                if drawn.is_ok() && r.has_picture() {
                    // Presented: the first one is the window's "on screen", timed from
                    // the click; the first after its connection went, its return.
                    if !shared.link.is_live() || shared.link.is_lost() {
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
