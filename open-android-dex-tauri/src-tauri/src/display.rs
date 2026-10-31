//! The desktop's size, density and bit rate, worked out from the screen it
//! opens on.
//!
//! The desktop used to be 1920x1080 at 160 dpi everywhere. On a Retina or 4K
//! screen scrcpy's window then had to stretch it — 1.6x on a 14" MacBook Pro,
//! 2x on a 4K monitor — and everything on it, Android and Linux windows alike,
//! looked soft.
//!
//! scrcpy 5's `--flex-display` resizes the display to follow the window, which
//! sounds like the fix and on a Mac is not: the window's size is taken in
//! POINTS (SDL_EVENT_WINDOW_RESIZED, upstream issue #7096), so on a 2x screen
//! the display gets half the screen's pixels and is drawn at twice their size —
//! softer than before. So the display is made at the screen's own pixel size
//! instead, and scrcpy's window is opened at exactly the size that shows it one
//! pixel for one (`--window-x/y/width/height`). Resizing the window afterwards
//! scales the picture, as it always did; at full size and in fullscreen it is
//! pixel-exact.
//!
//! The density follows the computer's own scaling: one dp is one point of the
//! computer's UI (160 dpi at 1x, 320 at 2x), so an Android window is the size a
//! Mac or Windows one is beside it. A Display size is a multiple of that
//! "Default" — one of the presets in [`SIZE_FACTORS`] or any size the launcher's
//! slider reaches between and past them ([`normalize_factor`]) — and the
//! launcher is told the base on every start (`base_dpi`), because only this
//! side knows the screen.

use std::collections::HashMap;
#[cfg(target_os = "macos")]
use std::sync::atomic::{AtomicU32, Ordering};

use tauri::{AppHandle, Manager};

/// Display sizes are rounded down to a multiple of this. scrcpy rounds a new
/// display down to its encoder's size alignment itself (NewDisplayCapture), and
/// one that came out a few pixels smaller than the window would be scaled by
/// 0.99 rather than shown one for one. 16 is the largest alignment an encoder
/// asks for; most ask for 2.
pub const ALIGN: u32 = 16;

/// The longest side the phone is asked to render. 4K UHD: past it every phone
/// checked would encode a smaller video anyway (a 5K screen's 5120 px), and the
/// phone's GPU would draw pixels nobody sees. A phone whose encoder stops lower
/// is remembered by [`remember_video_cap`].
pub const MAX_LONG_SIDE: u32 = 3840;

/// Below this the screen is not one to fit a desktop to (a work area that came
/// back empty, a projector at 800x600); the old fixed 1080p is used instead.
const MIN_WIDTH: u32 = 960;
const MIN_HEIGHT: u32 = 540;

/// Display size presets as multiples of the Default density — MUST match
/// SettingsActivity.SIZE_FACTORS in the launcher, which offers them.
pub const SIZE_FACTORS: [f64; 5] = [0.75, 0.875, 1.0, 1.25, 1.5];

/// What scrcpy's `--window-*` options are measured in on this computer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Units {
    /// macOS (and Wayland): window coordinates are points, pixels / scale.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))] // built on macOS
    Points,
    /// Windows (and X11): window coordinates are physical pixels.
    #[cfg_attr(target_os = "macos", allow(dead_code))] // built on Windows and Linux
    Pixels,
}

/// Window chrome around scrcpy's content, in physical pixels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Insets {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

/// The screen the desktop's window will open on.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Screen {
    /// The usable area — not the menu bar, the Dock or the taskbar — in
    /// physical pixels and global coordinates.
    pub work_x: i32,
    pub work_y: i32,
    pub work_w: u32,
    pub work_h: u32,
    /// Physical pixels per point: 2 on a Retina screen, 1.5 at 150 %.
    pub scale: f64,
    /// The frame scrcpy's window gets around its content.
    pub frame: Insets,
    pub units: Units,
    /// Whether the window is placed at all. Not where its frame cannot be
    /// known ahead (Linux: the window manager draws it, or the app does).
    pub place: bool,
}

/// scrcpy's window: its CONTENT's top-left and size, in [`Screen::units`].
/// SDL positions and sizes the client area, never the frame around it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WindowRect {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

/// A desktop fitted to a screen.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Geometry {
    /// The display, in pixels.
    pub width: u32,
    pub height: u32,
    /// The Default density: one dp is one point of the computer's own UI.
    pub base_dpi: i32,
    pub window: Option<WindowRect>,
    /// For a display that follows its window (see [`flex_geometry`]): what a
    /// window coordinate is in pixels — the scale the phone is told to multiply
    /// every size scrcpy asks for by. None for a display fitted once.
    pub flex: Option<f64>,
    /// The largest the display gets, in pixels, which is what the bit rate is
    /// chosen for: its own size when fitted once, the screen's usable area
    /// when it follows a window that can be made that big.
    pub largest: (u32, u32),
}

/// Fit a desktop to `screen`, rendering at most `cap` pixels on its long side.
///
/// The display is the window's content area in physical pixels, so at full
/// size scrcpy shows it one for one. Past `cap` it is scaled down by k and the
/// window still fills the screen, showing it 1/k larger — the density scales
/// by the same k, so the desktop's layout in dp does not depend on the cap.
pub fn geometry(screen: &Screen, cap: u32) -> Option<Geometry> {
    if !(screen.scale.is_finite() && screen.scale > 0.0) {
        return None;
    }
    let f = screen.frame;
    let cw = screen.work_w.checked_sub(f.left + f.right)?;
    let ch = screen.work_h.checked_sub(f.top + f.bottom)?;
    if cw == 0 || ch == 0 {
        return None;
    }
    let k = (f64::from(cap.max(ALIGN)) / f64::from(cw.max(ch))).min(1.0);
    let width = align_down((f64::from(cw) * k) as u32);
    let height = align_down((f64::from(ch) * k) as u32);
    if width < MIN_WIDTH || height < MIN_HEIGHT {
        return None;
    }
    let base_dpi = ((160.0 * screen.scale * k).round() as i32).clamp(80, 960);
    let unit = match screen.units {
        Units::Points => screen.scale,
        Units::Pixels => 1.0,
    };
    let window = screen.place.then(|| {
        let w = f64::from(width) / k;
        let h = f64::from(height) / k;
        // Centred across the work area, and straight under the top of it:
        // the alignment's few spare pixels go below rather than above.
        let x = f64::from(screen.work_x) + f64::from(f.left) + (f64::from(cw) - w).max(0.0) / 2.0;
        let y = f64::from(screen.work_y) + f64::from(f.top);
        WindowRect {
            x: (x / unit).round() as i32,
            y: (y / unit).round() as i32,
            width: (w / unit).round() as u32,
            height: (h / unit).round() as u32,
        }
    });
    Some(Geometry {
        width,
        height,
        base_dpi,
        window,
        flex: None,
        largest: (width, height),
    })
}

fn align_down(v: u32) -> u32 {
    v / ALIGN * ALIGN
}

/// scrcpy keeps a new window this far inside the screen's usable area, in
/// window coordinates on every side combined (DISPLAY_MARGINS, screen.c).
const SCRCPY_MARGINS: f64 = 96.0;

/// The window scrcpy opens for a display that follows its window, `content`
/// in window coordinates, on a main screen whose usable area is `usable`: the
/// display's own size, shrunk to fit inside that area less scrcpy's margins,
/// keeping its shape — get_optimal_size in screen.c, integer for integer. It
/// takes no size of its own beside `--flex-display`, and a window that opens
/// smaller than its display shows it scaled until it is next resized.
pub fn scrcpy_window_for(content: (u32, u32), usable: (u32, u32)) -> (u32, u32) {
    let (cw, ch) = (u64::from(content.0), u64::from(content.1));
    if cw == 0 || ch == 0 {
        return content;
    }
    let margins = SCRCPY_MARGINS as u64;
    let mut w = cw.min(u64::from(usable.0).saturating_sub(margins));
    let mut h = ch.min(u64::from(usable.1).saturating_sub(margins));
    let optimal = h == w * ch / cw || w == h * cw / ch;
    if !optimal {
        if cw * h > ch * w {
            h = ch * w / cw;
        } else {
            w = cw * h / ch;
        }
    }
    (w as u32, h as u32)
}

/// A desktop that follows its window (`--flex-display`), started at the size
/// scrcpy will open its window at on `screen` — the main screen, where SDL
/// centres it — so the first frame already fills it one pixel for one.
///
/// scrcpy sizes that window to the display (taken in window coordinates)
/// shrunk to fit the screen's usable area less 96 on each axis. A display of
/// exactly that area times the screen's scale fits on both axes at once, so
/// the window is that area and no resize is needed to match it. From then on
/// every size the window takes is asked of the phone in pixels — see the
/// scale file in openandroiddex-scrcpy/display-scale.patch, which this
/// returns as `flex`: points to pixels on macOS, 1 where windows are measured
/// in pixels already.
pub fn flex_geometry(screen: &Screen) -> Option<Geometry> {
    if !(screen.scale.is_finite() && screen.scale > 0.0) {
        return None;
    }
    let unit = match screen.units {
        Units::Points => screen.scale,
        Units::Pixels => 1.0,
    };
    let w = (f64::from(screen.work_w) / unit - SCRCPY_MARGINS).floor();
    let h = (f64::from(screen.work_h) / unit - SCRCPY_MARGINS).floor();
    let width = (w * unit).round() as u32;
    let height = (h * unit).round() as u32;
    if width < MIN_WIDTH || height < MIN_HEIGHT {
        return None;
    }
    Some(Geometry {
        width,
        height,
        base_dpi: ((160.0 * screen.scale).round() as i32).clamp(80, 960),
        window: None,
        flex: Some(unit),
        largest: (screen.work_w, screen.work_h),
    })
}

/// The bit rate a desktop of `width` x `height` is streamed at when the user
/// has not picked one: scrcpy's own 8 Mbps for 1080p, scaled by the pixel
/// count, so a sharper desktop keeps the same quality per pixel when it moves.
///
/// Capped at 32 Mbps over a cable — two 4K-sized frames' worth of headroom on
/// USB 2, and inside every phone encoder's range we know of — and at 16 over
/// Wi-Fi, where scrcpy has no way to slow down for a busy link and a stream
/// that asks for more than it gets is felt as lag. A desktop that is not moving
/// costs a fraction of either: the encoder only spends bits on change.
pub fn auto_bit_rate_mbps(width: u32, height: u32, wireless: bool) -> u32 {
    let pixels = f64::from(width) * f64::from(height);
    let mbps = (8.0 * pixels / (1920.0 * 1080.0)).ceil() as u32;
    mbps.clamp(8, if wireless { 16 } else { 32 })
}

/// Whether adb reaches this phone over the network rather than a cable: an
/// address (`192.168.2.12:38959`) or a paired phone's mDNS service name.
pub fn is_wireless(serial: &str) -> bool {
    serial.contains(':') || serial.contains("adb-tls-connect")
}

/// The fewest and most of the Default a Display size may be kept as — MUST
/// match DisplaySize.MIN_FACTOR/MAX_FACTOR in the launcher, whose slider runs
/// from half the computer's own size to as dense as the display allows.
pub const MIN_FACTOR: f64 = 0.5;
pub const MAX_FACTOR: f64 = 4.0;

/// A multiple this close to a preset is that preset — MUST match
/// DisplaySize.SNAP in the launcher, tolerance and all: a dpi rounded to a
/// whole number divides back into its preset only to within 0.5 / Default, and
/// the tolerance keeps a float there and an f64 here agreeing on a multiple
/// exactly 0.01 away.
const SNAP: f64 = 0.01;
const SNAP_TOLERANCE: f64 = 1e-6;

/// A Display size as it is kept: a preset when it is within [`SNAP`] of one —
/// so a dpi the launcher rounded does not come back as 1.0026 — and otherwise
/// the size the user picked between or past them, bounded and to four
/// decimals, which is closer than any whole dpi can tell apart.
pub fn normalize_factor(factor: f64) -> f64 {
    if !factor.is_finite() || factor <= 0.0 {
        return 1.0;
    }
    if let Some(preset) = SIZE_FACTORS
        .iter()
        .copied()
        .find(|p| (p - factor).abs() <= SNAP + SNAP_TOLERANCE)
    {
        return preset;
    }
    (factor.clamp(MIN_FACTOR, MAX_FACTOR) * 10_000.0).round() / 10_000.0
}

/// The preset nearest to `factor`: what an absolute dpi from before the
/// presets were multiples stood for, when the launcher only offered presets.
pub fn snap_factor(factor: f64) -> f64 {
    if !factor.is_finite() || factor <= 0.0 {
        return 1.0;
    }
    SIZE_FACTORS
        .iter()
        .copied()
        .min_by(|a, b| (a - factor).abs().total_cmp(&(b - factor).abs()))
        .unwrap_or(1.0)
}

/// "Texture (VideoToolbox): 3024x1680" → (3024, 1680) — the size of the video
/// scrcpy decodes, which is smaller than the display only when the phone's
/// encoder could not take the display's size (scrcpy retries smaller by
/// itself: "Applying video encoder constraints", "Retrying with -m…").
pub fn parse_texture_size(line: &str) -> Option<(u32, u32)> {
    let rest = &line[line.find("Texture")?..];
    let (_, size) = rest.split_once(": ")?;
    let (w, h) = size.trim().split_once('x')?;
    Some((w.trim().parse().ok()?, h.trim().parse().ok()?))
}

// ── The screen ─────────────────────────────────────────────────────────────

/// The height of a titled window's title bar on this Mac, in points: measured
/// once on the main thread at start-up ([`measure_window_frame`]), 28 until
/// then — what every macOS since 11 has drawn.
#[cfg(target_os = "macos")]
static TITLE_BAR_PT: AtomicU32 = AtomicU32::new(28);

/// Measure the window frame AppKit will give scrcpy's window. Must run on the
/// main thread, which is where Tauri's setup hook runs.
#[cfg(target_os = "macos")]
pub fn measure_window_frame() {
    use objc2::MainThreadMarker;
    use objc2_app_kit::{NSWindow, NSWindowStyleMask};
    use objc2_foundation::{NSPoint, NSRect, NSSize};
    let Some(mtm) = MainThreadMarker::new() else {
        return;
    };
    let content = NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(800.0, 600.0));
    // SDL's style for a resizable window with a title, which scrcpy's is.
    let style = NSWindowStyleMask::Titled
        | NSWindowStyleMask::Closable
        | NSWindowStyleMask::Miniaturizable
        | NSWindowStyleMask::Resizable;
    let frame = NSWindow::frameRectForContentRect_styleMask(content, style, mtm);
    let bar = (frame.size.height - content.size.height).round();
    if (0.0..100.0).contains(&bar) {
        TITLE_BAR_PT.store(bar as u32, Ordering::Relaxed);
        log::info!("display: window title bars are {bar} pt here");
    }
}

#[cfg(not(target_os = "macos"))]
pub fn measure_window_frame() {}

/// The title bar [`measure_window_frame`] measured, in points.
#[cfg(target_os = "macos")]
pub fn title_bar_pt() -> u32 {
    TITLE_BAR_PT.load(Ordering::Relaxed)
}

/// The frame scrcpy's window gets on a Windows screen at `scale`.
///
/// AdjustWindowRectExForDpi with SDL's style for a resizable titled window
/// (WS_OVERLAPPEDWINDOW). Its left, right and bottom include the invisible
/// resize borders Windows 10 and 11 draw, so a window placed this way shows a
/// few pixels of desktop beside it — the price of its caption landing exactly
/// on the top of the work area.
#[cfg(windows)]
fn window_frame(scale: f64) -> Insets {
    use windows_sys::Win32::Foundation::RECT;
    use windows_sys::Win32::UI::HiDpi::AdjustWindowRectExForDpi;
    use windows_sys::Win32::UI::WindowsAndMessaging::WS_OVERLAPPEDWINDOW;
    let dpi = (96.0 * scale).round() as u32;
    let mut r = RECT {
        left: 0,
        top: 0,
        right: 1000,
        bottom: 1000,
    };
    // SAFETY: r is a valid RECT for the duration of the call.
    let ok = unsafe { AdjustWindowRectExForDpi(&mut r, WS_OVERLAPPEDWINDOW, 0, 0, dpi) };
    if ok == 0 {
        let caption = (31.0 * scale).round() as u32;
        let border = (8.0 * scale).round() as u32;
        return Insets {
            left: border,
            top: caption,
            right: border,
            bottom: border,
        };
    }
    Insets {
        left: (-r.left).max(0) as u32,
        top: (-r.top).max(0) as u32,
        right: (r.right - 1000).max(0) as u32,
        bottom: (r.bottom - 1000).max(0) as u32,
    }
}

/// Whether a desktop here can follow its window. Where SDL measures windows in
/// pixels (Windows) the phone gets their size as it is; on macOS, in points,
/// the patched scrcpy server multiplies it. Linux is left fitted once: X11 and
/// Wayland measure differently and neither has been tried.
pub fn flex_supported() -> bool {
    cfg!(any(target_os = "macos", windows))
}

/// The screen the desktop will open on when it is fitted once: the one the
/// app's own window is on — where the person who clicked "Start" is looking —
/// or the main one.
pub fn screen_for_desktop(app: &AppHandle) -> Option<Screen> {
    let monitor = app
        .get_webview_window("main")
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten())?;
    Some(screen_of(&monitor))
}

/// The main screen — the one SDL centres a window on when it is not told
/// where, which is how a desktop that follows its window opens.
pub fn main_screen(app: &AppHandle) -> Option<Screen> {
    app.primary_monitor().ok().flatten().map(|m| screen_of(&m))
}

fn screen_of(monitor: &tauri::Monitor) -> Screen {
    let work = monitor.work_area();
    let scale = monitor.scale_factor();
    #[cfg(target_os = "macos")]
    let (frame, units, place) = (
        Insets {
            top: (f64::from(TITLE_BAR_PT.load(Ordering::Relaxed)) * scale).round() as u32,
            ..Insets::default()
        },
        Units::Points,
        true,
    );
    #[cfg(windows)]
    let (frame, units, place) = (window_frame(scale), Units::Pixels, true);
    // Neither the frame nor, on Wayland, the position can be known ahead: a
    // title bar's worth is left for whichever draws it, and scrcpy places the
    // window itself.
    #[cfg(not(any(target_os = "macos", windows)))]
    let (frame, units, place) = (
        Insets {
            top: (37.0 * scale).round() as u32,
            ..Insets::default()
        },
        Units::Pixels,
        false,
    );
    Screen {
        work_x: work.position.x,
        work_y: work.position.y,
        work_w: work.size.width,
        work_h: work.size.height,
        scale,
        frame,
        units,
        place,
    }
}

/// The scale of the screen holding the point (`x`, `y`) in global points, or
/// None off every screen: what a desktop following its window is re-scaled to
/// when the window is dragged onto another screen (embed/macos.rs). Windows
/// asks the window itself instead (GetDpiForWindow).
#[cfg(target_os = "macos")]
pub fn scale_at(app: &AppHandle, x: f64, y: f64) -> Option<f64> {
    let monitors = app.available_monitors().ok()?;
    monitors.iter().find_map(|m| {
        let s = m.scale_factor();
        let (mx, my) = (f64::from(m.position().x) / s, f64::from(m.position().y) / s);
        let (mw, mh) = (f64::from(m.size().width) / s, f64::from(m.size().height) / s);
        (x >= mx && x < mx + mw && y >= my && y < my + mh).then_some(s)
    })
}

/// Whether the rect (`x`, `y`, `w`, `h`), in global points, is exactly one
/// screen's whole area — a window in a fullscreen Space.
#[cfg(target_os = "macos")]
pub fn covers_a_screen(app: &AppHandle, x: f64, y: f64, w: f64, h: f64) -> bool {
    let Ok(monitors) = app.available_monitors() else {
        return false;
    };
    monitors.iter().any(|m| {
        let s = m.scale_factor();
        let (mx, my) = (f64::from(m.position().x) / s, f64::from(m.position().y) / s);
        let (mw, mh) = (f64::from(m.size().width) / s, f64::from(m.size().height) / s);
        (x - mx).abs() < 1.0 && (y - my).abs() < 1.0 && (w - mw).abs() < 1.0 && (h - mh).abs() < 1.0
    })
}

/// Whether a window opened at `rect` would be on a screen at all: its middle
/// is on one. A remembered window can outlive the monitor it was on.
pub fn on_a_screen(app: &AppHandle, rect: WindowRect) -> bool {
    let Ok(monitors) = app.available_monitors() else {
        return false;
    };
    let cx = f64::from(rect.x) + f64::from(rect.width) / 2.0;
    let cy = f64::from(rect.y) + f64::from(rect.height) / 2.0;
    monitors.iter().any(|m| {
        // Window coordinates are points on macOS, pixels elsewhere (Units).
        let s = if cfg!(target_os = "macos") { m.scale_factor() } else { 1.0 };
        let (mx, my) = (f64::from(m.position().x) / s, f64::from(m.position().y) / s);
        let (mw, mh) = (f64::from(m.size().width) / s, f64::from(m.size().height) / s);
        cx >= mx && cx < mx + mw && cy >= my && cy < my + mh
    })
}

/// The display a desktop that follows its window starts at, on the main
/// screen — or None where it cannot follow (the caller fits it once instead).
pub fn desktop_flex_geometry(app: &AppHandle) -> Option<Geometry> {
    if !flex_supported() {
        return None;
    }
    let screen = main_screen(app)?;
    let g = flex_geometry(&screen);
    log::info!(
        "display: main screen {}x{} at {}x scale → {}",
        screen.work_w,
        screen.work_h,
        screen.scale,
        match &g {
            Some(g) => format!(
                "a display following its window, from {}x{} at {} dpi, sizes x{}",
                g.width,
                g.height,
                g.base_dpi,
                g.flex.unwrap_or(1.0)
            ),
            None => "too small to fit, keeping 1920x1080".into(),
        }
    );
    g
}

/// The desktop for this phone on the screen the app is on, or None when there
/// is no screen to fit (the caller then keeps the fixed 1080p).
pub fn desktop_geometry(app: &AppHandle, serial: &str, codec: Option<&str>) -> Option<Geometry> {
    let screen = screen_for_desktop(app)?;
    let cap = video_cap(app, serial, codec).unwrap_or(MAX_LONG_SIDE).min(MAX_LONG_SIDE);
    let g = geometry(&screen, cap);
    log::info!(
        "display: screen {}x{} at {}x scale (work area at {},{}), cap {cap} → {}",
        screen.work_w,
        screen.work_h,
        screen.scale,
        screen.work_x,
        screen.work_y,
        match &g {
            Some(g) => format!(
                "{}x{} at {} dpi, window {:?}",
                g.width, g.height, g.base_dpi, g.window
            ),
            None => "too small to fit, keeping 1920x1080".into(),
        }
    );
    g
}

// ── What each phone has taught us ────────────────────────────────────────────
//
// Both kept per PHONE, by the identity adb.rs keys its restore records by
// (ro.serialno), not per route: the same phone on the cable and on Wi-Fi is one
// set of preferences and one encoder.

fn store_path(app: &AppHandle, name: &str) -> Option<std::path::PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join(name))
}

fn load<T: serde::de::DeserializeOwned + Default>(app: &AppHandle, name: &str) -> T {
    store_path(app, name)
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save<T: serde::Serialize>(app: &AppHandle, name: &str, value: &T) {
    let Some(path) = store_path(app, name) else {
        return;
    };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_string(value) {
        let _ = std::fs::write(path, json);
    }
}

/// The phone's Display size, as a multiple of the Default density: a preset
/// or a size of the user's own.
const SIZE_FILE: &str = "display-size.json";

/// What came before it: an absolute dpi per adb route, which meant a preset
/// only at the resolution it was picked at (160 is Default at 1080p).
const LEGACY_DENSITY_FILE: &str = "display-density.json";

/// This phone's Display size, 1.0 (Default) when it never picked one.
///
/// A phone that only has a dpi from before is moved over once: that dpi was
/// a preset of the 1080-dp-tall Default at the resolution then in force
/// (`legacy_res`), and the multiple it stood for is what is kept.
pub fn size_factor(app: &AppHandle, serial: &str, legacy_res: (i32, i32)) -> f64 {
    let id = crate::adb::restore_key(app, serial);
    let mut factors: HashMap<String, f64> = load(app, SIZE_FILE);
    if let Some(f) = factors.get(&id) {
        return normalize_factor(*f);
    }
    let legacy: HashMap<String, i32> = load(app, LEGACY_DENSITY_FILE);
    let Some(dpi) = legacy.get(serial).or_else(|| legacy.get(&id)).copied() else {
        return 1.0;
    };
    let default = (160.0 * f64::from(legacy_res.0.min(legacy_res.1)) / 1080.0).round();
    let f = snap_factor(f64::from(dpi) / default.max(1.0));
    log::info!("display: {id} had {dpi} dpi at {}x{} — kept as {f}x Default", legacy_res.0, legacy_res.1);
    factors.insert(id, f);
    save(app, SIZE_FILE, &factors);
    f
}

/// Remember the Display size the launcher just applied, as a multiple of
/// `base_dpi` — a preset or any size between and past them, so a desktop set to
/// 280 dpi on a 1x screen comes back at 280 rather than at the nearest preset.
pub fn remember_size_factor(app: &AppHandle, serial: &str, dpi: i32, base_dpi: i32) -> f64 {
    let f = normalize_factor(f64::from(dpi) / f64::from(base_dpi.max(1)));
    let id = crate::adb::restore_key(app, serial);
    let mut factors: HashMap<String, f64> = load(app, SIZE_FILE);
    if factors.get(&id) != Some(&f) {
        factors.insert(id, f);
        save(app, SIZE_FILE, &factors);
    }
    f
}

/// The longest video side each phone's encoder turned out to manage, per codec.
const VIDEO_CAP_FILE: &str = "video-caps.json";

fn video_cap_key(id: &str, codec: Option<&str>) -> String {
    format!("{id}|{}", codec.unwrap_or("h264"))
}

/// The longest side this phone's encoder has been seen to produce, when that
/// was less than the display asked of it.
pub fn video_cap(app: &AppHandle, serial: &str, codec: Option<&str>) -> Option<u32> {
    let caps: HashMap<String, u32> = load(app, VIDEO_CAP_FILE);
    caps.get(&video_cap_key(&crate::adb::restore_key(app, serial), codec)).copied()
}

/// The phone encoded a smaller video than its display: render no larger than
/// that next time. scrcpy found the size by failing at the bigger one first,
/// which costs a moment at every start and a GPU drawing pixels the stream
/// throws away — this is what stops both after the first time.
pub fn remember_video_cap(app: &AppHandle, serial: &str, codec: Option<&str>, long_side: u32) {
    if long_side < MIN_WIDTH {
        return; // not an encoder limit anybody could build a desktop on
    }
    let key = video_cap_key(&crate::adb::restore_key(app, serial), codec);
    let mut caps: HashMap<String, u32> = load(app, VIDEO_CAP_FILE);
    if caps.get(&key).is_some_and(|c| *c <= long_side) {
        return;
    }
    log::warn!("display: {key} encodes at most {long_side} px on the long side — later desktops render no larger");
    caps.insert(key, long_side);
    save(app, VIDEO_CAP_FILE, &caps);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mac(work: (i32, i32, u32, u32), scale: f64) -> Screen {
        Screen {
            work_x: work.0,
            work_y: work.1,
            work_w: work.2,
            work_h: work.3,
            scale,
            frame: Insets {
                top: (28.0 * scale) as u32,
                ..Insets::default()
            },
            units: Units::Points,
            place: true,
        }
    }

    #[test]
    fn scrcpy_opens_a_display_that_fits_at_its_own_size() {
        // The Galaxy A13's desktop, windowed on this PC's portrait screen
        // (work area 1080x1872, so scrcpy allows 984x1776).
        assert_eq!(scrcpy_window_for((804, 1542), (1080, 1872)), (804, 1542));
        assert_eq!(scrcpy_window_for((984, 1776), (1080, 1872)), (984, 1776));
    }

    #[test]
    fn scrcpy_shrinks_a_bigger_display_keeping_its_shape() {
        // Dragged out to the screen's height: 1776 tall is the most it opens at.
        let (w, h) = scrcpy_window_for((804, 1900), (1080, 1872));
        assert_eq!(h, 1776);
        assert_eq!(w, 804 * 1776 / 1900);
        // Too wide instead: the width is what gives.
        let (w, h) = scrcpy_window_for((1060, 600), (1080, 1872));
        assert_eq!((w, h), (984, 600 * 984 / 1060));
        // A maximized window's display, which is reopened scaled and then
        // maximized again.
        assert_eq!(scrcpy_window_for((1080, 1848), (1080, 1872)), (984, 1848 * 984 / 1080));
    }

    #[test]
    fn a_retina_laptop_gets_its_own_pixels_shown_one_for_one() {
        // 14" MacBook Pro: 1512x982 pt, menu bar 37 pt, Dock 70 pt at the bottom.
        let g = geometry(&mac((0, 74, 3024, 1750), 2.0), MAX_LONG_SIDE).unwrap();
        assert_eq!((g.width, g.height), (3024, 1680));
        assert_eq!(g.base_dpi, 320);
        let w = g.window.unwrap();
        // 3024 px over 1512 pt: one pixel for one at 2x.
        assert_eq!((w.width, w.height), (1512, 840));
        // Right under the title bar, which sits right under the menu bar.
        assert_eq!((w.x, w.y), (0, 37 + 28));
    }

    #[test]
    fn a_1x_monitor_keeps_160_dpi_and_fills_its_work_area() {
        // The 2560x1440 main screen this was written on: menu bar 25 pt.
        let g = geometry(&mac((0, 25, 2560, 1415), 1.0), MAX_LONG_SIDE).unwrap();
        assert_eq!((g.width, g.height), (2560, 1376));
        assert_eq!(g.base_dpi, 160);
        let w = g.window.unwrap();
        assert_eq!((w.x, w.y, w.width, w.height), (0, 25 + 28, 2560, 1376));
    }

    #[test]
    fn a_5k_screen_is_capped_and_shown_larger_at_the_same_layout() {
        // 5120x2880 px (2560x1440 pt) at 2x; the phone renders 3840 across.
        let g = geometry(&mac((0, 50, 5120, 2830), 2.0), MAX_LONG_SIDE).unwrap();
        assert_eq!(g.width, 3840);
        assert!(g.height % ALIGN == 0 && g.height <= (2830 - 56) * 3 / 4);
        // k = 0.75: 240 dpi keeps one dp at one point.
        assert_eq!(g.base_dpi, 240);
        let w = g.window.unwrap();
        assert_eq!(w.width, 2560);
        // dp across = window points across
        assert_eq!(g.width * 160 / g.base_dpi as u32, w.width);
    }

    #[test]
    fn windows_at_150_percent_places_the_client_area_in_pixels() {
        let s = Screen {
            work_x: 0,
            work_y: 0,
            work_w: 3840,
            work_h: 2088, // 4K less a 72 px taskbar
            scale: 1.5,
            frame: Insets {
                left: 11,
                top: 45,
                right: 11,
                bottom: 11,
            },
            units: Units::Pixels,
            place: true,
        };
        let g = geometry(&s, MAX_LONG_SIDE).unwrap();
        assert_eq!((g.width, g.height), (3808, 2032));
        assert_eq!(g.base_dpi, 240);
        let w = g.window.unwrap();
        assert_eq!((w.width, w.height), (3808, 2032));
        assert_eq!(w.y, 45);
        assert_eq!(w.x, 11 + (3818 - 3808) / 2);
    }

    #[test]
    fn a_learned_encoder_limit_shrinks_the_display_not_the_layout() {
        let full = geometry(&mac((0, 74, 3024, 1750), 2.0), MAX_LONG_SIDE).unwrap();
        let capped = geometry(&mac((0, 74, 3024, 1750), 2.0), 1920).unwrap();
        assert_eq!(capped.width, 1920);
        // the same window, the same desktop in dp
        assert_eq!(capped.window.unwrap().width, full.window.unwrap().width);
        let dp = |g: &Geometry| (f64::from(g.width) * 160.0 / f64::from(g.base_dpi)).round();
        assert!((dp(&capped) - dp(&full)).abs() <= 2.0, "{} vs {}", dp(&capped), dp(&full));
    }

    #[test]
    fn linux_sizes_the_display_but_leaves_the_window_to_scrcpy() {
        let s = Screen {
            place: false,
            units: Units::Pixels,
            ..mac((0, 32, 1920, 1048), 1.0)
        };
        let g = geometry(&s, MAX_LONG_SIDE).unwrap();
        assert!(g.window.is_none());
        assert_eq!(g.base_dpi, 160);
    }

    #[test]
    fn a_screen_too_small_to_fit_keeps_the_old_fixed_desktop() {
        assert!(geometry(&mac((0, 25, 800, 575), 1.0), MAX_LONG_SIDE).is_none());
        assert!(geometry(&mac((0, 0, 0, 0), 2.0), MAX_LONG_SIDE).is_none());
        let broken = Screen {
            scale: f64::NAN,
            ..mac((0, 25, 2560, 1415), 1.0)
        };
        assert!(geometry(&broken, MAX_LONG_SIDE).is_none());
    }

    #[test]
    fn every_size_is_a_multiple_of_the_alignment() {
        for (w, h, s) in [(2880, 1694, 2.0), (1366, 728, 1.0), (2560, 1550, 1.25), (3456, 2160, 2.0)] {
            let g = geometry(&mac((0, 0, w, h), s), MAX_LONG_SIDE).unwrap();
            assert_eq!(g.width % ALIGN, 0);
            assert_eq!(g.height % ALIGN, 0);
        }
    }

    #[test]
    fn a_flex_desktop_opens_at_the_window_scrcpy_will_make_one_for_one() {
        // The 2560x1440 main screen: usable 2560x1316 points at 1x.
        let g = flex_geometry(&mac((0, 30, 2560, 1316), 1.0)).unwrap();
        assert_eq!((g.width, g.height), (2560 - 96, 1316 - 96));
        assert_eq!((g.base_dpi, g.flex, g.window), (160, Some(1.0), None));
        assert_eq!(g.largest, (2560, 1316));
        // A Retina main screen: 1512x949 points usable at 2x, so the display
        // is the window's (1512-96)x(949-96) points in pixels, at 320 dpi.
        let g = flex_geometry(&mac((0, 66, 3024, 1898), 2.0)).unwrap();
        assert_eq!((g.width, g.height), (2832, 1706));
        assert_eq!((g.base_dpi, g.flex), (320, Some(2.0)));
        // scrcpy fits that display, taken as points, into the same area less
        // 96: both axes land exactly, so the window needs no resize.
        let (w_pt, h_pt) = (f64::from(g.width) / 2.0, f64::from(g.height) / 2.0);
        assert_eq!((w_pt, h_pt), (1512.0 - 96.0, 949.0 - 96.0));
    }

    #[test]
    fn a_flex_desktop_on_windows_is_sized_in_pixels_and_told_scale_one() {
        let s = Screen {
            work_x: 0,
            work_y: 0,
            work_w: 3840,
            work_h: 2088,
            scale: 1.5,
            frame: Insets::default(),
            units: Units::Pixels,
            place: true,
        };
        let g = flex_geometry(&s).unwrap();
        assert_eq!((g.width, g.height), (3840 - 96, 2088 - 96));
        assert_eq!((g.base_dpi, g.flex), (240, Some(1.0)));
    }

    #[test]
    fn a_flex_desktop_needs_a_screen_worth_following() {
        assert!(flex_geometry(&mac((0, 25, 1024, 600), 1.0)).is_none());
        let broken = Screen {
            scale: 0.0,
            ..mac((0, 25, 2560, 1415), 1.0)
        };
        assert!(flex_geometry(&broken).is_none());
    }

    #[test]
    fn bit_rate_follows_the_pixels_and_stops_at_the_link_cap() {
        assert_eq!(auto_bit_rate_mbps(1920, 1080, false), 8);
        assert_eq!(auto_bit_rate_mbps(1280, 720, false), 8);
        assert_eq!(auto_bit_rate_mbps(3024, 1680, false), 20);
        assert_eq!(auto_bit_rate_mbps(2560, 1376, false), 14);
        assert_eq!(auto_bit_rate_mbps(3840, 2160, false), 32);
        assert_eq!(auto_bit_rate_mbps(3840, 2160, true), 16);
        assert_eq!(auto_bit_rate_mbps(3024, 1680, true), 16);
    }

    #[test]
    fn wireless_routes_are_told_from_cables() {
        assert!(is_wireless("192.168.2.12:38959"));
        assert!(is_wireless("adb-RFCY10FRKBK-JGcERD._adb-tls-connect._tcp"));
        assert!(!is_wireless("RFCY10FRKBK"));
    }

    #[test]
    fn legacy_dpis_snap_to_the_presets() {
        assert_eq!(snap_factor(1.0026), 1.0);
        assert_eq!(snap_factor(200.0 / 160.0), 1.25);
        assert_eq!(snap_factor(0.8), 0.75);
        assert_eq!(snap_factor(0.82), 0.875);
        assert_eq!(snap_factor(9.0), 1.5);
        assert_eq!(snap_factor(f64::NAN), 1.0);
        assert_eq!(snap_factor(0.0), 1.0);
    }

    #[test]
    fn a_size_of_the_users_own_is_kept_as_it_was_picked() {
        // the issue's own numbers, on a 1x screen whose Default is 160
        assert_eq!(normalize_factor(280.0 / 160.0), 1.75);
        assert_eq!(normalize_factor(320.0 / 160.0), 2.0);
        assert_eq!(normalize_factor(0.8), 0.8);
        assert_eq!(normalize_factor(0.82), 0.82);
        // a rounded dpi still lands on its preset
        assert_eq!(normalize_factor(1.0026), 1.0);
        assert_eq!(normalize_factor(200.0 / 160.0), 1.25);
        assert_eq!(normalize_factor(134.0 / 107.0), 1.25);
        // 0.01 away exactly snaps here as it does in the launcher's float
        assert_eq!(normalize_factor(0.885), 0.875);
        assert_eq!(normalize_factor(0.886), 0.886);
        // bounded, and never nonsense
        assert_eq!(normalize_factor(9.0), MAX_FACTOR);
        assert_eq!(normalize_factor(0.1), MIN_FACTOR);
        assert_eq!(normalize_factor(f64::NAN), 1.0);
        assert_eq!(normalize_factor(0.0), 1.0);
        assert_eq!(normalize_factor(-2.0), 1.0);
        // four decimals: 300 dpi on a Default of 213 survives the round trip
        let f = normalize_factor(300.0 / 213.0);
        assert_eq!(f, 1.4085);
        assert_eq!((213.0 * f).round() as i32, 300);
    }

    #[test]
    fn every_whole_dpi_comes_back_from_its_multiple() {
        // What is remembered is dpi / Default; what the next desktop is made at
        // is Default x that. Any dpi the launcher can ask for has to survive it,
        // or the launcher and this side would correct each other on every start.
        for base in [107, 140, 160, 200, 213, 240, 280, 320, 360, 480] {
            let lo = ((f64::from(base) * MIN_FACTOR).round() as i32).max(70);
            for dpi in lo..=960 {
                let f = normalize_factor(f64::from(dpi) / f64::from(base));
                if f == MAX_FACTOR && f64::from(dpi) > f64::from(base) * MAX_FACTOR {
                    continue;
                }
                let back = (f64::from(base) * f).round() as i32;
                let snapped = SIZE_FACTORS.contains(&f);
                assert!(
                    back == dpi || snapped,
                    "base {base}: {dpi} dpi kept as {f} comes back as {back}"
                );
            }
        }
    }

    #[test]
    fn the_decoded_size_is_read_from_every_interop() {
        assert_eq!(parse_texture_size("INFO: Texture (VideoToolbox): 1920x1080"), Some((1920, 1080)));
        assert_eq!(parse_texture_size("INFO: Texture: 3024x1680"), Some((3024, 1680)));
        assert_eq!(parse_texture_size("INFO: Texture (VA-API): 2560x1376"), Some((2560, 1376)));
        assert_eq!(parse_texture_size("INFO: Renderer: metal"), None);
        assert_eq!(parse_texture_size("INFO: Texture (x): wide"), None);
    }
}
