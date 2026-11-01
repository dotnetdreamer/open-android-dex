//! Phone or tablet: the size a window's app is given — a phone's or a
//! tablet's — More's way to pick one, and each app's window kept as it closed
//! (doc/apps-on-pc.md, "Phone or tablet").
//!
//! An Android app lays itself out for the room it has. Most that have a
//! tablet layout take it from 600 dp on the shorter side (`sw600dp`); others
//! go by their width, at sizes of their own, and some have only the one. A
//! window's display is the window's own size at one dp to the point
//! (link::density), so the window is the device the app thinks it is on: a
//! phone-shaped window is a phone, and a window made big enough is a tablet's
//! size. A wide one a little short of a tablet's height is made one too
//! (link::layout_density). This module knows sizes, not what an app does
//! with them. Nothing on the phone is changed for any of it — not its own
//! screen, not the desktop's — so there is nothing to put back: each display,
//! and its density, goes with its window.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use tauri::Manager;

use super::link::DisplaySize;

/// The two sizes an app is given in a window: a phone's, or a tablet's from
/// 600 dp on the shorter side.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Layout {
    Phone,
    Tablet,
}

/// A current phone's shape and size, in points: a window's first.
pub const PHONE: (f64, f64) = (412.0, 915.0);
/// A current tablet's, on its side: a Galaxy Tab's or a Pixel Tablet's, at its own density.
pub const TABLET: (f64, f64) = (1280.0, 800.0);
/// The smallest picture a kept window comes back at, in points: about the
/// least a window can be made (`WM_GETMINMAXINFO`, `setContentMinSize`).
const LEAST: (f64, f64) = (240.0, 240.0);

/// The size a picture `w`×`h` points big is to an app, at the density it is
/// given (link::layout_density).
pub fn layout_at(w: f64, h: f64) -> Layout {
    let px = |v: f64| v.max(0.0).round() as u32;
    if DisplaySize::for_app(px(w), px(h), 1.0).tablet() {
        Layout::Tablet
    } else {
        Layout::Phone
    }
}

/// `want` (points) as large as it fits on a screen whose free part is `room`
/// points, under a title bar `bar` points tall: no taller with the bar than 90 %
/// of it, no wider than 95 % — and no larger than asked.
pub fn fit(want: (f64, f64), room: (f64, f64), bar: f64) -> (f64, f64) {
    let k = ((room.1 * 0.9 - bar) / want.1).min(room.0 * 0.95 / want.0).clamp(0.0, 1.0);
    (want.0 * k, want.1 * k)
}

/// `want` (points) as large as it fits in the whole of a screen's free part,
/// `room` points, under a bar `bar` points tall — no larger than asked. A size
/// the person left a window at, which fitted there, is given back as it was.
fn fit_within(want: (f64, f64), room: (f64, f64), bar: f64) -> (f64, f64) {
    let k = ((room.1 - bar) / want.1).min(room.0 / want.0).clamp(0.0, 1.0);
    (want.0 * k, want.1 * k)
}

/// What a window does to give its app a size.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Plan {
    /// Its picture this many points big, where the window is.
    Size(f64, f64),
    /// Maximised: a tablet's shape fitted to this screen is a phone's size.
    Maximize,
    /// No window on this screen is a tablet's size. Full screen is, or not.
    NoRoom { full_screen: bool },
}

/// How a window gives its app `layout`'s size on a screen `screen` points big
/// whose free part (less the taskbar or the Dock) is `room`, under a bar `bar`
/// points tall.
pub fn plan(layout: Layout, room: (f64, f64), screen: (f64, f64), bar: f64) -> Plan {
    let want = match layout {
        Layout::Phone => PHONE,
        Layout::Tablet => TABLET,
    };
    let (w, h) = fit(want, room, bar);
    if layout == Layout::Phone || layout_at(w, h) == Layout::Tablet {
        Plan::Size(w, h)
    } else if layout_at(room.0, room.1 - bar) == Layout::Tablet {
        Plan::Maximize
    } else {
        Plan::NoRoom { full_screen: layout_at(screen.0, screen.1) == Layout::Tablet }
    }
}

/// A window as it closed, kept for its app: the next opens the same way.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Shape {
    /// Its picture, in points: as it was before it was maximised or made full screen.
    pub w: f64,
    pub h: f64,
    /// It was maximised.
    #[serde(default)]
    pub max: bool,
}

/// How a window opens on a screen whose free part is `room` points, under a
/// bar `bar` points tall: its picture in points, and whether it is maximised.
/// As `kept` closed, where that fits on this screen, else as large as fits; a
/// phone's shape, fitted as a new window is, the first time. One that was a
/// tablet's size and would be a phone's here is maximised instead, where that
/// makes it a tablet's; where it does not, it opens as large as fits.
pub fn opening(kept: Option<Shape>, room: (f64, f64), bar: f64) -> (f64, f64, bool) {
    let Some(k) = kept.filter(|k| k.w.is_finite() && k.h.is_finite()) else {
        let (w, h) = fit(PHONE, room, bar);
        return (w, h, false);
    };
    let (w, h) = fit_within((k.w.max(LEAST.0), k.h.max(LEAST.1)), room, bar);
    let shrunk = layout_at(k.w, k.h) == Layout::Tablet && layout_at(w, h) == Layout::Phone;
    let max = k.max || (shrunk && layout_at(room.0, room.1 - bar) == Layout::Tablet);
    (w, h, max)
}

fn store_path(app: &tauri::AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join("app-windows.json"))
}

/// Every app's window as it last closed, by package. A file that cannot be
/// read is none: windows open as phones until one closes and writes it again.
fn load(app: &tauri::AppHandle) -> HashMap<String, Shape> {
    store_path(app)
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

/// How `pkg`'s window last closed, if one has.
pub fn kept(app: &tauri::AppHandle, pkg: &str) -> Option<Shape> {
    load(app).remove(pkg)
}

/// `pkg`'s window closed as `shape`: its next opens the same way. Written to
/// a file beside the last and moved over it, so a write cut short leaves the
/// last one whole.
pub fn keep(app: &tauri::AppHandle, pkg: &str, shape: Shape) {
    static SAVING: Mutex<()> = Mutex::new(());
    let _one = SAVING.lock().unwrap();
    let shape = Shape { w: shape.w.round(), h: shape.h.round(), max: shape.max };
    let mut all = load(app);
    if all.get(pkg) == Some(&shape) {
        return;
    }
    all.insert(pkg.to_string(), shape);
    let Some(path) = store_path(app) else { return };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_vec_pretty(&all) {
        let tmp = path.with_extension("json.tmp");
        if std::fs::write(&tmp, json).is_ok() && std::fs::rename(&tmp, &path).is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A Windows 11 screen of `w`×`h` pixels at `scale`, in points: the whole
    /// of it, and the work area above its 48-point taskbar. The bar is 32.
    fn screen_at(w: f64, h: f64, scale: f64) -> (f64, f64) {
        (w / scale, h / scale)
    }
    fn work_at(w: f64, h: f64, scale: f64) -> (f64, f64) {
        (w / scale, h / scale - 48.0)
    }
    /// 1080p, the commonest, at `scale`.
    fn work(scale: f64) -> (f64, f64) {
        work_at(1920.0, 1080.0, scale)
    }
    fn screen(scale: f64) -> (f64, f64) {
        screen_at(1920.0, 1080.0, scale)
    }
    const BAR: f64 = 32.0;

    #[test]
    fn a_window_is_the_device_its_app_thinks_it_is_on() {
        assert_eq!(layout_at(412.0, 915.0), Layout::Phone);
        assert_eq!(layout_at(1280.0, 800.0), Layout::Tablet);
        // Snapped to a half at 100 %: a tablet stood up.
        assert_eq!(layout_at(960.0, 1000.0), Layout::Tablet);
        // A quarter at 100 %: a phone on its side.
        assert_eq!(layout_at(960.0, 484.0), Layout::Phone);
        // Maximised at 175 %: short of 600 points, a tablet by the lean.
        assert_eq!(layout_at(1097.0, 537.0), Layout::Tablet);
    }

    #[test]
    fn a_phone_is_fitted_as_it_always_was() {
        // 1440p at 100 %: room for a phone as it is.
        assert_eq!(fit(PHONE, work_at(2560.0, 1440.0, 1.0), BAR), PHONE);
        // 1080p: 90 % of the work area less the bar, a phone's shape.
        for scale in [1.0, 1.5] {
            let (w, h) = fit(PHONE, work(scale), BAR);
            assert!((h - (work(scale).1 * 0.9 - BAR)).abs() < 1e-9);
            assert!((w / h - PHONE.0 / PHONE.1).abs() < 1e-9);
            assert_eq!(plan(Layout::Phone, work(scale), screen(scale), BAR), Plan::Size(w, h));
        }
    }

    #[test]
    fn a_tablet_is_a_tablets_shape_where_it_fits_and_maximised_where_not() {
        // 100 % and 125 %: the shape fits as a tablet.
        assert_eq!(plan(Layout::Tablet, work(1.0), screen(1.0), BAR), Plan::Size(1280.0, 800.0));
        assert!(matches!(plan(Layout::Tablet, work(1.25), screen(1.25), BAR), Plan::Size(_, h) if h >= 600.0));
        // 150 %: 573 points tall, a tablet by the lean, still a window.
        assert!(matches!(plan(Layout::Tablet, work(1.5), screen(1.5), BAR), Plan::Size(_, h) if h < 600.0));
        // 175 %: fitted, a phone on its side; maximised, a tablet.
        assert_eq!(plan(Layout::Tablet, work(1.75), screen(1.75), BAR), Plan::Maximize);
        // 200 %: no window is; full screen, 540 points tall, is.
        assert_eq!(plan(Layout::Tablet, work(2.0), screen(2.0), BAR), Plan::NoRoom { full_screen: true });
        // 250 %: not even full screen.
        assert_eq!(plan(Layout::Tablet, work(2.5), screen(2.5), BAR), Plan::NoRoom { full_screen: false });
    }

    #[test]
    fn a_window_opens_as_it_closed() {
        // The first time: a phone.
        assert_eq!(opening(None, work_at(2560.0, 1440.0, 1.0), BAR), (412.0, 915.0, false));
        // As it closed, where it fits.
        let tab = Shape { w: 1280.0, h: 800.0, max: false };
        assert_eq!(opening(Some(tab), work(1.0), BAR), (1280.0, 800.0, false));
        assert_eq!(opening(Some(Shape { max: true, ..tab }), work(1.0), BAR), (1280.0, 800.0, true));
        // Snapped to half the screen, as tall as it: back as it was, not shrunk
        // to the 90 % a new window is fitted to.
        let half = Shape { w: 960.0, h: 1000.0, max: false };
        assert_eq!(opening(Some(half), work(1.0), BAR), (960.0, 1000.0, false));
        // A tablet on a smaller screen, as large as fits: at 175 % as tall as
        // the screen, a tablet's size still by the lean, not maximised.
        let (w, h, max) = opening(Some(tab), work(1.75), BAR);
        assert!((h - (work(1.75).1 - BAR)).abs() < 1e-9);
        assert_eq!((layout_at(w, h), max), (Layout::Tablet, false));
        // A tablet stood up, which as large as fits there is a phone's size:
        // maximised, where that is a tablet's.
        let (w, h, max) = opening(Some(Shape { w: 800.0, h: 1100.0, max: false }), work(1.75), BAR);
        assert_eq!((layout_at(w, h), max), (Layout::Phone, true));
        // At 200 % only full screen is a tablet's size: as large as fits, a phone's.
        let (w, h, max) = opening(Some(tab), work(2.0), BAR);
        assert_eq!((layout_at(w, h), max), (Layout::Phone, false));
        // A phone made smaller stays a phone, never maximised.
        assert!(!opening(Some(Shape { w: 360.0, h: 700.0, max: false }), work(1.75), BAR).2);
        // Nonsense in the file is no smaller than a window can be.
        let (w, h, _) = opening(Some(Shape { w: 1.0, h: -5.0, max: false }), work(1.0), BAR);
        assert_eq!((w, h), LEAST);
    }

    #[test]
    fn a_kept_window_reads_back_without_what_it_did_not_say() {
        let read: HashMap<String, Shape> = serde_json::from_str(r#"{"com.whatsapp": {"w": 900, "h": 640}}"#).unwrap();
        assert_eq!(read["com.whatsapp"], Shape { w: 900.0, h: 640.0, max: false });
    }
}
