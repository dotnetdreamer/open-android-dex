//! The desktop window's own Back, Home and Recent apps, in its title bar.
//!
//! The desktop is scrcpy's window, another process's, and the phone's taskbar is
//! drawn inside it by the launcher. Where the phone draws that taskbar under the
//! apps — a Galaxy A13 on Android 12, whose desktop is an untrusted display — an
//! app that covers the whole desktop, a video made full screen, leaves nothing on
//! the desktop to click: no Back, no Home, no way out but keys nobody knows. These
//! three are this computer's: over scrcpy's title bar, where an app on the phone
//! cannot cover them. Each tells the launcher, as a three-finger gesture does
//! (`am broadcast … GESTURE`), and the launcher runs the taskbar's own code for it:
//! Back to the window in front, Home, Recent apps.
//!
//! At the title bar's left end, where an app window's Back is (apps/win/frame.rs). It
//! covers scrcpy's small icon there, and the window's title is pushed right by a
//! middle dot and spaces of its own (`TITLE_PAD`) so the name starts past the bar
//! rather than under it.
//!
//! A window of this process cannot be a child of scrcpy's — a title bar is not a
//! client area, and a parent in another process ties the two input queues together
//! — so the bar is a top-level window of its own that never takes focus, kept over
//! the title bar and directly above scrcpy's window in the z-order on a 16 ms tick.
//! Full screen there is no title bar, so it waits at the top edge, in the middle,
//! and shows while the pointer is up there.
//!
//! Windows only. On a Mac the desktop is a window of scrcpy's too, and nothing
//! places a window of ours over another process's title bar there.

#[cfg(windows)]
pub use win::watch;

#[cfg(not(windows))]
pub fn watch(
    _app: tauri::AppHandle,
    _key: String,
    _serial: String,
    _pid: u32,
    _stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    _child: std::sync::Arc<std::sync::Mutex<std::process::Child>>,
) {
}

/// The buttons, left to right: what each tells the launcher, and what it is called.
const BUTTONS: [(&str, &str); 3] = [("back", "Back"), ("home", "Home"), ("recents", "Recent apps")];

/// A button's width, in the window's device-independent pixels: the app windows'
/// Back and More (apps/win/frame.rs).
const BUTTON_DIP: f32 = 40.0;
/// Windows 11's own caption buttons, which the bar stands beside: three of them.
const CAPTION_BUTTON_DIP: f32 = 46.0;
/// Between the bar and the minimise button, and above and below it in the title bar.
const GAP_DIP: f32 = 4.0;
/// The least a title bar keeps for scrcpy's icon and the start of its title.
const TITLE_ROOM_DIP: f32 = 160.0;
/// The bar's height when the window is full screen and has no title bar to fit.
const FULL_HEIGHT_DIP: f32 = 30.0;

/// A rect as `(left, top, right, bottom)`, on the screen.
type Rect = (i32, i32, i32, i32);

/// Where the bar goes over a desktop window whose visible frame is `frame` and whose
/// client area starts at `client_top`, or None where there is no room for it: at the
/// title bar's left end, or — `full`, no title bar — at the top edge, in the middle.
fn placement(frame: Rect, client_top: i32, scale: f32, full: bool) -> Option<Rect> {
    let px = |dip: f32| (dip * scale).round() as i32;
    let width = px(BUTTON_DIP) * BUTTONS.len() as i32;
    let gap = px(GAP_DIP);
    if full {
        let mid = (frame.0 + frame.2) / 2;
        let left = mid - width / 2;
        return (frame.2 - frame.0 >= width).then_some((left, frame.1, left + width, frame.1 + px(FULL_HEIGHT_DIP)));
    }
    let caption = client_top - frame.1;
    if caption < px(16.0) {
        return None;
    }
    if frame.2 - frame.0 < gap + width + px(TITLE_ROOM_DIP) + px(CAPTION_BUTTON_DIP) * 3 {
        return None;
    }
    let left = frame.0 + gap;
    Some((left, frame.1 + gap, left + width, client_top - gap))
}

/// What the window's title starts with while the bar is over its left end: a middle dot,
/// which the bar covers, then thirty spaces, which in the caption's font carry the name
/// just past the bar. Windows draws a title without its leading whitespace — spaces,
/// em spaces, a zero-width space and a blank braille cell in front were all dropped,
/// measured on Windows 11 — so the spaces need a visible character before them. Font
/// and bar both grow with the window's scale, so the count holds at any.
const TITLE_PAD: &str = "\u{00B7}                              ";

/// `title` with the bar's room in front of it, or None when it already has it.
fn padded(title: &str) -> Option<String> {
    if title.starts_with(TITLE_PAD) {
        return None;
    }
    Some(format!("{TITLE_PAD}{}", title.trim_start_matches([' ', '\u{2003}', '\u{00B7}'])))
}

/// Which button is at `x` across a bar `width` wide, if any.
fn button_at(x: i32, width: i32) -> Option<usize> {
    if x < 0 || x >= width || width <= 0 {
        return None;
    }
    Some(((x as i64 * BUTTONS.len() as i64) / width as i64) as usize)
}

#[cfg(windows)]
mod win {
    use std::process::Child;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::{Duration, Instant};

    use tauri::AppHandle;
    use windows::core::{w, BOOL, PCWSTR};
    use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
    use windows::Win32::Graphics::Dwm::{DwmGetWindowAttribute, DWMWA_EXTENDED_FRAME_BOUNDS};
    use windows::Win32::Graphics::Gdi::{
        BeginPaint, BitBlt, ClientToScreen, CreateCompatibleBitmap, CreateCompatibleDC, CreateFontW,
        CreateRoundRectRgn, CreateSolidBrush, DeleteDC, DeleteObject, DrawTextW, EndPaint, FillRect,
        InvalidateRect, SelectObject, SetBkMode, SetTextColor, SetWindowRgn, CLEARTYPE_QUALITY,
        CLIP_DEFAULT_PRECIS, DEFAULT_CHARSET, DT_CENTER, DT_SINGLELINE, DT_VCENTER, FW_NORMAL,
        OUT_DEFAULT_PRECIS, PAINTSTRUCT, SRCCOPY, TRANSPARENT,
    };
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::System::Registry::{RegGetValueW, HKEY_CURRENT_USER, RRF_RT_REG_DWORD};
    use windows::Win32::UI::HiDpi::GetDpiForWindow;
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        ReleaseCapture, SetCapture, TrackMouseEvent, TME_LEAVE, TRACKMOUSEEVENT,
    };
    use windows::Win32::UI::WindowsAndMessaging::*;

    use super::{button_at, padded, placement, Rect, BUTTONS};

    /// How often the title is checked for the bar's room: the app sets it again when the
    /// phone is renamed.
    const TITLE_EVERY: Duration = Duration::from_secs(2);

    /// Not among the windows crate's WindowsAndMessaging constants (apps/win does the same).
    /// Left out, a `WM_MOUSELEAVE =>` arm is a binding that matches every message.
    const WM_MOUSELEAVE: u32 = 0x02A3;
    const CLASS: PCWSTR = w!("OpenAndroidDeXDesktopBar");
    const TICK: usize = 1;
    const TICK_MS: u32 = 16;
    /// How long a full-screen bar stays once the pointer has left it.
    const LINGER: Duration = Duration::from_millis(900);
    /// How long the desktop's window has to appear: a first start installs the
    /// launcher before the display shows.
    const WINDOW_WAIT: Duration = Duration::from_secs(90);

    /// Segoe MDL2 Assets, on Windows 10 and 11 alike: Back, Home, Task View.
    const GLYPHS: [u16; 3] = [0xE72B, 0xE80F, 0xE7C4];

    struct Bar {
        app: AppHandle,
        key: String,
        serial: String,
        scrcpy: HWND,
        stop: Arc<AtomicBool>,
        child: Arc<Mutex<Child>>,
        hover: Option<usize>,
        pressed: Option<usize>,
        tracking: bool,
        /// Where it is, as last placed; None while hidden.
        at: Option<Rect>,
        scale: f32,
        dark: bool,
        /// Full screen: when the pointer was last at the top edge or on the bar.
        wanted_at: Option<Instant>,
        /// When the title was last checked for the bar's room.
        titled_at: Option<Instant>,
    }

    /// Put the bar over desktop session `key`'s window — scrcpy's, process `pid` — for as
    /// long as the session lasts.
    pub fn watch(
        app: AppHandle,
        key: String,
        serial: String,
        pid: u32,
        stop: Arc<AtomicBool>,
        child: Arc<Mutex<Child>>,
    ) {
        let spawned = thread::Builder::new()
            .name("deskbar".into())
            .spawn(move || run(app, key, serial, pid, stop, child));
        if let Err(e) = spawned {
            log::warn!("desktop bar: no thread: {e}");
        }
    }

    fn exited(child: &Mutex<Child>) -> bool {
        child.lock().map(|mut c| !matches!(c.try_wait(), Ok(None))).unwrap_or(true)
    }

    fn run(app: AppHandle, key: String, serial: String, pid: u32, stop: Arc<AtomicBool>, child: Arc<Mutex<Child>>) {
        let since = Instant::now();
        let scrcpy = loop {
            if stop.load(Ordering::SeqCst) || exited(&child) {
                return;
            }
            if let Some(h) = window_of(pid) {
                break h;
            }
            if since.elapsed() > WINDOW_WAIT {
                log::info!("desktop bar [{key}]: the desktop's window never appeared");
                return;
            }
            thread::sleep(Duration::from_millis(200));
        };
        register_class();
        let bar = Box::new(Bar {
            app,
            key: key.clone(),
            serial,
            scrcpy,
            stop,
            child,
            hover: None,
            pressed: None,
            tracking: false,
            at: None,
            scale: 1.0,
            dark: dark_mode(),
            wanted_at: None,
            titled_at: None,
        });
        let ptr = Box::into_raw(bar);
        // SAFETY: a class registered above; the state pointer is handed to the window
        // through CREATESTRUCT and taken back in WM_NCDESTROY.
        unsafe {
            let instance = GetModuleHandleW(None).unwrap_or_default();
            let hwnd = CreateWindowExW(
                WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                CLASS,
                w!("Phone keys"),
                WS_POPUP,
                0,
                0,
                0,
                0,
                None,
                None,
                Some(instance.into()),
                Some(ptr as *const core::ffi::c_void),
            );
            let Ok(hwnd) = hwnd else {
                drop(Box::from_raw(ptr));
                log::warn!("desktop bar [{key}]: the window could not be made");
                return;
            };
            log::info!("desktop bar [{key}]: Back, Home and Recent apps over the desktop's title bar");
            let _ = SetTimer(Some(hwnd), TICK, TICK_MS, None);
            let mut msg = MSG::default();
            while GetMessageW(&mut msg, None, 0, 0).as_bool() {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        log::info!("desktop bar [{key}]: gone with the desktop's window");
    }

    fn register_class() {
        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| unsafe {
            let instance = GetModuleHandleW(None).unwrap_or_default();
            let wc = WNDCLASSEXW {
                cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
                lpfnWndProc: Some(wndproc),
                hInstance: instance.into(),
                hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
                lpszClassName: CLASS,
                ..Default::default()
            };
            RegisterClassExW(&wc);
        });
    }

    struct Find {
        pid: u32,
        found: Option<HWND>,
    }

    unsafe extern "system" fn find_cb(hwnd: HWND, lp: LPARAM) -> BOOL {
        let find = &mut *(lp.0 as *mut Find);
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == find.pid && IsWindowVisible(hwnd).as_bool() {
            let mut title = [0u16; 4];
            if GetWindowTextW(hwnd, &mut title) > 0 {
                find.found = Some(hwnd);
                return BOOL(0);
            }
        }
        BOOL(1)
    }

    /// The desktop's window: scrcpy's visible, titled top-level window.
    fn window_of(pid: u32) -> Option<HWND> {
        let mut find = Find { pid, found: None };
        // SAFETY: the callback reads only the Find it is handed, for this call.
        unsafe {
            let _ = EnumWindows(Some(find_cb), LPARAM(&mut find as *mut Find as isize));
        }
        find.found
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

    fn rgb(r: u8, g: u8, b: u8) -> COLORREF {
        COLORREF(r as u32 | (g as u32) << 8 | (b as u32) << 16)
    }

    unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
        if msg == WM_NCCREATE {
            let cs = &*(lp.0 as *const CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, cs.lpCreateParams as isize);
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
        let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Bar;
        if ptr.is_null() {
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
        if msg == WM_NCDESTROY {
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
            drop(Box::from_raw(ptr));
            PostQuitMessage(0);
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
        let bar = &mut *ptr;
        match msg {
            // Pressed without being activated: the keyboard stays with the desktop.
            WM_MOUSEACTIVATE => LRESULT(MA_NOACTIVATE as isize),
            WM_TIMER => {
                bar.tick(hwnd);
                LRESULT(0)
            }
            WM_MOUSEMOVE => {
                if !bar.tracking {
                    let mut t = TRACKMOUSEEVENT {
                        cbSize: std::mem::size_of::<TRACKMOUSEEVENT>() as u32,
                        dwFlags: TME_LEAVE,
                        hwndTrack: hwnd,
                        dwHoverTime: 0,
                    };
                    let _ = TrackMouseEvent(&mut t);
                    bar.tracking = true;
                }
                let hit = bar.hit(lp);
                if hit != bar.hover {
                    bar.hover = hit;
                    let _ = InvalidateRect(Some(hwnd), None, false);
                }
                LRESULT(0)
            }
            WM_MOUSELEAVE => {
                bar.tracking = false;
                if bar.hover.take().is_some() {
                    let _ = InvalidateRect(Some(hwnd), None, false);
                }
                LRESULT(0)
            }
            WM_LBUTTONDOWN => {
                bar.pressed = bar.hit(lp);
                SetCapture(hwnd);
                let _ = InvalidateRect(Some(hwnd), None, false);
                LRESULT(0)
            }
            WM_LBUTTONUP => {
                let _ = ReleaseCapture();
                let hit = bar.hit(lp);
                if let (Some(down), Some(up)) = (bar.pressed.take(), hit) {
                    if down == up {
                        bar.press(up);
                    }
                }
                let _ = InvalidateRect(Some(hwnd), None, false);
                LRESULT(0)
            }
            WM_PAINT => {
                bar.paint(hwnd);
                LRESULT(0)
            }
            WM_ERASEBKGND => LRESULT(1),
            _ => DefWindowProcW(hwnd, msg, wp, lp),
        }
    }

    impl Bar {
        fn hit(&self, lp: LPARAM) -> Option<usize> {
            let x = (lp.0 & 0xFFFF) as i16 as i32;
            let (l, _, r, _) = self.at?;
            button_at(x, r - l)
        }

        /// One button: the launcher runs the taskbar's own code for it.
        fn press(&self, which: usize) {
            let (what, name) = BUTTONS[which];
            let app = self.app.clone();
            let serial = self.serial.clone();
            let key = self.key.clone();
            log::info!("desktop bar [{key}]: {name}");
            thread::spawn(move || {
                let cmd = format!(
                    "am broadcast -a {launcher}.GESTURE -p {launcher} --es action {what}",
                    launcher = crate::adb::LAUNCHER_PACKAGE
                );
                if let Err(e) = crate::adb::run_adb(&app, &["-s", &serial, "shell", &cmd]) {
                    log::warn!("desktop bar [{key}]: {name} did not reach the phone: {e}");
                }
            });
        }

        /// Keep the bar where it belongs, or take it away with the desktop's window.
        unsafe fn tick(&mut self, hwnd: HWND) {
            if self.stop.load(Ordering::SeqCst) || exited(&self.child) || !IsWindow(Some(self.scrcpy)).as_bool() {
                let _ = DestroyWindow(hwnd);
                return;
            }
            let want = self.wanted();
            match want {
                None => {
                    if self.at.take().is_some() {
                        let _ = ShowWindow(hwnd, SW_HIDE);
                    }
                }
                Some(r) => {
                    if self.wanted_at.is_none() {
                        self.keep_title();     // over the title bar, not full screen
                    }
                    let moved = self.at != Some(r);
                    // Directly above the desktop's window, wherever that is in the z-order:
                    // above it, never above what is above it.
                    let above = GetWindow(self.scrcpy, GW_HWNDPREV).ok();
                    let in_place = above == Some(hwnd);
                    if moved || !in_place {
                        let after = match above {
                            Some(a) if a != hwnd && GetWindowLongPtrW(a, GWL_EXSTYLE) & WS_EX_TOPMOST.0 as isize == 0 => Some(a),
                            _ => Some(HWND_TOP),
                        };
                        let flags = SWP_NOACTIVATE | SWP_SHOWWINDOW | if moved { SET_WINDOW_POS_FLAGS(0) } else { SWP_NOMOVE | SWP_NOSIZE };
                        let _ = SetWindowPos(hwnd, after, r.0, r.1, r.2 - r.0, r.3 - r.1, flags);
                    }
                    if moved {
                        let (w, h) = (r.2 - r.0, r.3 - r.1);
                        let radius = (8.0 * self.scale).round() as i32;
                        let rgn = CreateRoundRectRgn(0, 0, w + 1, h + 1, radius, radius);
                        // The window owns the region from here.
                        SetWindowRgn(hwnd, Some(rgn), true);
                        self.at = Some(r);
                        let _ = InvalidateRect(Some(hwnd), None, false);
                    }
                }
            }
        }

        /// Keep the bar's room at the front of the window's title, so its name starts past
        /// the bar rather than under it.
        unsafe fn keep_title(&mut self) {
            if self.titled_at.is_some_and(|at| at.elapsed() < TITLE_EVERY) {
                return;
            }
            self.titled_at = Some(Instant::now());
            let mut buf = [0u16; 512];
            let n = GetWindowTextW(self.scrcpy, &mut buf);
            if n <= 0 {
                return;
            }
            let title = String::from_utf16_lossy(&buf[..n as usize]);
            if let Some(room) = padded(&title) {
                let wide: Vec<u16> = room.encode_utf16().chain(std::iter::once(0)).collect();
                let _ = SetWindowTextW(self.scrcpy, PCWSTR(wide.as_ptr()));
            }
        }

        /// Where the bar should be now, or None for hidden.
        unsafe fn wanted(&mut self) -> Option<Rect> {
            let s = self.scrcpy;
            if IsIconic(s).as_bool() || !IsWindowVisible(s).as_bool() {
                return None;
            }
            let mut frame = RECT::default();
            if DwmGetWindowAttribute(
                s,
                DWMWA_EXTENDED_FRAME_BOUNDS,
                &mut frame as *mut RECT as *mut core::ffi::c_void,
                std::mem::size_of::<RECT>() as u32,
            )
            .is_err()
                && GetWindowRect(s, &mut frame).is_err()
            {
                return None;
            }
            let mut origin = POINT { x: 0, y: 0 };
            let _ = ClientToScreen(s, &mut origin);
            let dpi = GetDpiForWindow(s);
            self.scale = if dpi > 0 { dpi as f32 / 96.0 } else { 1.0 };
            let full = GetWindowLongPtrW(s, GWL_STYLE) & WS_CAPTION.0 as isize == 0;
            let r = placement((frame.left, frame.top, frame.right, frame.bottom), origin.y, self.scale, full)?;
            if !full {
                self.wanted_at = None;
                return Some(r);
            }
            // Full screen: shown while the pointer is at the top edge or on the bar, and
            // for a moment after, so a pointer moving onto it does not lose it on the way.
            let mut pt = POINT::default();
            let _ = GetCursorPos(&mut pt);
            let edge = (8.0 * self.scale).round() as i32;
            let at_edge = pt.y >= frame.top && pt.y < frame.top + edge && pt.x >= frame.left && pt.x < frame.right;
            let on_bar = self.at.is_some_and(|(l, t, rr, b)| pt.x >= l && pt.x < rr && pt.y >= t && pt.y < b);
            if at_edge || on_bar {
                self.wanted_at = Some(Instant::now());
            }
            self.wanted_at.filter(|at| at.elapsed() < LINGER).map(|_| r)
        }

        unsafe fn paint(&self, hwnd: HWND) {
            let mut ps = PAINTSTRUCT::default();
            let hdc = BeginPaint(hwnd, &mut ps);
            let Some((l, t, r, b)) = self.at else {
                let _ = EndPaint(hwnd, &ps);
                return;
            };
            let (w, h) = (r - l, b - t);
            let mem = CreateCompatibleDC(Some(hdc));
            let bmp = CreateCompatibleBitmap(hdc, w, h);
            let old = SelectObject(mem, bmp.into());
            let (bg, lit, down, ink) = if self.dark {
                (rgb(0x33, 0x33, 0x33), rgb(0x48, 0x48, 0x48), rgb(0x5A, 0x5A, 0x5A), rgb(0xF2, 0xF2, 0xF2))
            } else {
                (rgb(0xE4, 0xE4, 0xE4), rgb(0xD2, 0xD2, 0xD2), rgb(0xC0, 0xC0, 0xC0), rgb(0x1A, 0x1A, 0x1A))
            };
            let all = RECT { left: 0, top: 0, right: w, bottom: h };
            let brush = CreateSolidBrush(bg);
            FillRect(mem, &all, brush);
            let _ = DeleteObject(brush.into());
            let size = -((12.0 * self.scale).round() as i32);
            let font = CreateFontW(
                size, 0, 0, 0, FW_NORMAL.0 as i32, 0, 0, 0, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, 0, w!("Segoe MDL2 Assets"),
            );
            let old_font = SelectObject(mem, font.into());
            SetBkMode(mem, TRANSPARENT);
            SetTextColor(mem, ink);
            for i in 0..BUTTONS.len() {
                let x0 = (w as i64 * i as i64 / BUTTONS.len() as i64) as i32;
                let x1 = (w as i64 * (i as i64 + 1) / BUTTONS.len() as i64) as i32;
                let mut cell = RECT { left: x0, top: 0, right: x1, bottom: h };
                let fill = if self.pressed == Some(i) {
                    Some(down)
                } else if self.hover == Some(i) {
                    Some(lit)
                } else {
                    None
                };
                if let Some(c) = fill {
                    let b = CreateSolidBrush(c);
                    FillRect(mem, &cell, b);
                    let _ = DeleteObject(b.into());
                }
                let mut glyph = [GLYPHS[i]];
                DrawTextW(mem, &mut glyph, &mut cell, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            }
            SelectObject(mem, old_font);
            let _ = DeleteObject(font.into());
            let _ = BitBlt(hdc, 0, 0, w, h, Some(mem), 0, 0, SRCCOPY);
            SelectObject(mem, old);
            let _ = DeleteObject(bmp.into());
            let _ = DeleteDC(mem);
            let _ = EndPaint(hwnd, &ps);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 1440 px wide desktop window at 100 %: its visible frame from 100 to 1540, a
    /// 31 px title bar.
    const FRAME: Rect = (100, 50, 1540, 900);

    #[test]
    fn sits_at_the_title_bars_left_end() {
        let r = placement(FRAME, 81, 1.0, false).expect("room");
        // 4 px in from the frame's left edge; 3 buttons of 40.
        assert_eq!(r.0, 104);
        assert_eq!(r.2 - r.0, 120);
        // Inside the title bar, 4 px from its top and from the client area.
        assert_eq!((r.1, r.3), (54, 77));
    }

    #[test]
    fn grows_with_the_window_scale() {
        let r = placement(FRAME, 50 + 62, 2.0, false).expect("room");
        assert_eq!(r.2 - r.0, 240);
        assert_eq!(r.0, 100 + 8);
    }

    #[test]
    fn the_title_makes_room_once() {
        let once = padded("Galaxy A13 — Open Android DeX").expect("padded");
        assert!(once.starts_with(TITLE_PAD) && once.ends_with("Galaxy A13 — Open Android DeX"));
        assert_eq!(padded(&once), None);
        // A title set again without it, or with spaces of its own, gets exactly one pad.
        assert_eq!(padded("  Galaxy A13").as_deref(), Some(format!("{TITLE_PAD}Galaxy A13").as_str()));
    }

    #[test]
    fn a_window_too_narrow_for_it_and_its_title_has_none() {
        // 160 for the title, 120 for the bar, 142 for the gap and the caption buttons.
        assert!(placement((0, 0, 421, 600), 31, 1.0, false).is_none());
        assert!(placement((0, 0, 422, 600), 31, 1.0, false).is_some());
    }

    #[test]
    fn full_screen_it_waits_at_the_top_in_the_middle() {
        let r = placement((0, 0, 1920, 1080), 0, 1.0, true).expect("room");
        assert_eq!(r, (900, 0, 1020, 30));
    }

    #[test]
    fn no_title_bar_and_not_full_screen_is_no_place() {
        assert!(placement(FRAME, 50, 1.0, false).is_none());
    }

    #[test]
    fn each_third_is_a_button() {
        assert_eq!(button_at(0, 120), Some(0));
        assert_eq!(button_at(39, 120), Some(0));
        assert_eq!(button_at(40, 120), Some(1));
        assert_eq!(button_at(119, 120), Some(2));
        assert_eq!(button_at(120, 120), None);
        assert_eq!(button_at(-1, 120), None);
    }
}
