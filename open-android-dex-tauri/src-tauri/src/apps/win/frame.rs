//! An app window's title bar: where its parts are, for the drawing and for the
//! mouse alike, so the two can never disagree about where a button is.
//!
//! Windows 11's own caption's measurements, in its device-independent pixels:
//! 32 tall, 46-wide caption buttons. To the left, the one button a phone app
//! needs that a desktop window has no word for — Back — and More (☰), the
//! window's own menu; then the app's icon and its name, with the phone's after
//! it. Before the caption buttons, the one more the phone's sound needs: where
//! it plays.
//!
//! Full screen, there is no title bar: a smaller bar comes down from the top of
//! the screen, in the middle, while the pointer is up there — Remote Desktop's
//! connection bar's shape. Back and More, the app's name, where the sound
//! plays, minimise, leave full screen, close. While it is up, a small tab with an arrow pointing down
//! stays there, so the person can see where it is.

use windows::Win32::Foundation::RECT;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Hit {
    None,
    Back,
    Min,
    Max,
    Close,
    /// The bar itself: dragging moves the window.
    Caption,
    /// Where the phone's sound plays: a menu.
    Sound,
    More,
    /// The full-screen bar's way out of full screen.
    Windowed,
}

impl Hit {
    pub fn code(self) -> u8 {
        match self {
            Hit::None => 0,
            Hit::Back => 1,
            Hit::Min => 2,
            Hit::Max => 3,
            Hit::Close => 4,
            Hit::Caption => 5,
            Hit::Sound => 6,
            Hit::More => 7,
            Hit::Windowed => 8,
        }
    }

    pub fn from_code(c: u8) -> Hit {
        match c {
            1 => Hit::Back,
            2 => Hit::Min,
            3 => Hit::Max,
            4 => Hit::Close,
            5 => Hit::Caption,
            6 => Hit::Sound,
            7 => Hit::More,
            8 => Hit::Windowed,
            _ => Hit::None,
        }
    }
}

pub struct Bar {
    pub h: i32,
    pub back: RECT,
    pub more: RECT,
    pub icon: RECT,
    pub text_x: i32,
    pub sound: RECT,
    pub min: RECT,
    pub max: RECT,
    pub close: RECT,
}

pub const HEIGHT_DIP: f32 = 32.0;
const BUTTON_DIP: f32 = 46.0;
const BACK_DIP: f32 = 40.0;
const ICON_DIP: f32 = 16.0;

/// The bar's height for a window at `scale` (its DPI over 96).
pub fn height(scale: f32) -> i32 {
    (HEIGHT_DIP * scale).round() as i32
}

pub fn bar(width: i32, scale: f32) -> Bar {
    let h = height(scale);
    let button = (BUTTON_DIP * scale).round() as i32;
    let back_w = (BACK_DIP * scale).round() as i32;
    let icon = (ICON_DIP * scale).round() as i32;
    let close = RECT { left: width - button, top: 0, right: width, bottom: h };
    let max = RECT { left: close.left - button, top: 0, right: close.left, bottom: h };
    let min = RECT { left: max.left - button, top: 0, right: max.left, bottom: h };
    let sound = RECT { left: min.left - back_w, top: 0, right: min.left, bottom: h };
    let back = RECT { left: 0, top: 0, right: back_w, bottom: h };
    let more = RECT { left: back_w, top: 0, right: 2 * back_w, bottom: h };
    let icon_x = more.right + (4.0 * scale).round() as i32;
    let icon_rect = RECT { left: icon_x, top: (h - icon) / 2, right: icon_x + icon, bottom: (h - icon) / 2 + icon };
    Bar { h, back, more, icon: icon_rect, text_x: icon_rect.right + (10.0 * scale).round() as i32, sound, min, max, close }
}

fn inside(r: &RECT, x: i32, y: i32) -> bool {
    x >= r.left && x < r.right && y >= r.top && y < r.bottom
}

/// What is at (`x`, `y`) in the window's client area, if it is the bar.
pub fn hit(width: i32, scale: f32, x: i32, y: i32) -> Hit {
    let b = bar(width, scale);
    if y < 0 || y >= b.h {
        return Hit::None;
    }
    if inside(&b.close, x, y) {
        Hit::Close
    } else if inside(&b.max, x, y) {
        Hit::Max
    } else if inside(&b.min, x, y) {
        Hit::Min
    } else if inside(&b.sound, x, y) {
        Hit::Sound
    } else if inside(&b.back, x, y) {
        Hit::Back
    } else if inside(&b.more, x, y) {
        Hit::More
    } else {
        Hit::Caption
    }
}

/// The bar a full-screen window brings down from the top of the screen.
pub struct TopBar {
    /// Where it is in the window's client area, all the way down.
    pub rect: RECT,
    /// Its tab, while it is up: in the client area too.
    pub handle: RECT,
    /// Its parts, from its own top left corner.
    pub back: RECT,
    pub more: RECT,
    pub label: RECT,
    pub sound: RECT,
    pub min: RECT,
    pub windowed: RECT,
    pub close: RECT,
}

const TOP_BAR_W_DIP: f32 = 440.0;
const TOP_BAR_H_DIP: f32 = 28.0;
const TOP_BUTTON_DIP: f32 = 40.0;
const HANDLE_W_DIP: f32 = 44.0;
const HANDLE_H_DIP: f32 = 14.0;

pub fn top_bar(width: i32, scale: f32) -> TopBar {
    let h = (TOP_BAR_H_DIP * scale).round() as i32;
    let w = ((TOP_BAR_W_DIP * scale).round() as i32).min(width);
    let b = (TOP_BUTTON_DIP * scale).round() as i32;
    let left = (width - w) / 2;
    let at = |l: i32| RECT { left: l, top: 0, right: l + b, bottom: h };
    let pad = (8.0 * scale).round() as i32;
    let handle_w = (HANDLE_W_DIP * scale).round() as i32;
    let handle_left = (width - handle_w) / 2;
    TopBar {
        rect: RECT { left, top: 0, right: left + w, bottom: h },
        handle: RECT { left: handle_left, top: 0, right: handle_left + handle_w, bottom: (HANDLE_H_DIP * scale).round() as i32 },
        back: at(0),
        more: at(b),
        label: RECT { left: 2 * b + pad, top: 0, right: w - 4 * b - pad, bottom: h },
        sound: at(w - 4 * b),
        min: at(w - 3 * b),
        windowed: at(w - 2 * b),
        close: at(w - b),
    }
}

/// Whether (`x`, `y`) is on the full-screen bar's tab (while the bar is up).
pub fn on_handle(width: i32, scale: f32, x: i32, y: i32) -> bool {
    inside(&top_bar(width, scale).handle, x, y)
}

/// What is at (`x`, `y`) in a full-screen window's client area, if it is the
/// full-screen bar (when it is down).
pub fn top_hit(width: i32, scale: f32, x: i32, y: i32) -> Hit {
    let t = top_bar(width, scale);
    if !inside(&t.rect, x, y) {
        return Hit::None;
    }
    let x = x - t.rect.left;
    if inside(&t.close, x, y) {
        Hit::Close
    } else if inside(&t.windowed, x, y) {
        Hit::Windowed
    } else if inside(&t.min, x, y) {
        Hit::Min
    } else if inside(&t.sound, x, y) {
        Hit::Sound
    } else if inside(&t.back, x, y) {
        Hit::Back
    } else if inside(&t.more, x, y) {
        Hit::More
    } else {
        Hit::Caption
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_buttons_are_where_windows_puts_its_own() {
        let b = bar(412, 1.0);
        assert_eq!(b.h, 32);
        assert_eq!((b.close.left, b.close.right), (366, 412));
        assert_eq!((b.max.left, b.min.left), (320, 274));
        assert_eq!(hit(412, 1.0, 400, 10), Hit::Close);
        assert_eq!(hit(412, 1.0, 330, 10), Hit::Max);
        assert_eq!(hit(412, 1.0, 280, 10), Hit::Min);
        assert_eq!(hit(412, 1.0, 10, 10), Hit::Back);
        assert_eq!(hit(412, 1.0, 50, 10), Hit::More);
        assert_eq!(hit(412, 1.0, 150, 10), Hit::Caption);
        assert_eq!(hit(412, 1.0, 150, 40), Hit::None);
    }

    #[test]
    fn the_sound_button_is_just_before_minimise() {
        let b = bar(412, 1.0);
        assert_eq!((b.sound.left, b.sound.right), (234, 274));
        assert_eq!(hit(412, 1.0, 250, 10), Hit::Sound);
        assert_eq!(hit(412, 1.0, 233, 10), Hit::Caption);
        assert_eq!(Hit::from_code(Hit::Sound.code()), Hit::Sound);
    }

    #[test]
    fn the_bar_grows_with_the_screens_scale() {
        let b = bar(618, 1.5);
        assert_eq!(b.h, 48);
        assert_eq!(b.close.right - b.close.left, 69);
        assert_eq!((b.more.left, b.more.right), (60, 120));
        assert_eq!(hit(618, 1.5, 610, 47), Hit::Close);
    }

    #[test]
    fn the_full_screen_bar_is_small_and_in_the_middle() {
        let t = top_bar(1920, 1.0);
        assert_eq!((t.rect.left, t.rect.right, t.rect.bottom), (740, 1180, 28));
        assert_eq!(top_hit(1920, 1.0, 750, 10), Hit::Back);
        assert_eq!(top_hit(1920, 1.0, 790, 10), Hit::More);
        assert_eq!(top_hit(1920, 1.0, 960, 10), Hit::Caption);
        assert_eq!(top_hit(1920, 1.0, 1030, 10), Hit::Sound);
        assert_eq!(top_hit(1920, 1.0, 1070, 10), Hit::Min);
        assert_eq!(top_hit(1920, 1.0, 1110, 10), Hit::Windowed);
        assert_eq!(top_hit(1920, 1.0, 1179, 27), Hit::Close);
        assert_eq!(top_hit(1920, 1.0, 1180, 10), Hit::None);
        assert_eq!(top_hit(1920, 1.0, 960, 28), Hit::None);
        assert_eq!(top_hit(1920, 1.0, 100, 0), Hit::None);
        // Its tab, while it is up: small, in the middle, under where the bar comes down.
        assert_eq!((t.handle.left, t.handle.right, t.handle.bottom), (938, 982, 14));
        assert!(on_handle(1920, 1.0, 960, 0) && on_handle(1920, 1.0, 938, 13));
        assert!(!on_handle(1920, 1.0, 937, 5) && !on_handle(1920, 1.0, 960, 14));
        // At 150 %, on a 2560-pixel screen.
        let t = top_bar(2560, 1.5);
        assert_eq!((t.rect.left, t.rect.right, t.rect.bottom), (950, 1610, 42));
        assert_eq!(top_hit(2560, 1.5, 1609, 41), Hit::Close);
    }
}
