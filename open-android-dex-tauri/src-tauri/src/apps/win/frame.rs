//! An app window's title bar: where its parts are, for the drawing and for the
//! mouse alike, so the two can never disagree about where a button is.
//!
//! Windows 11's own caption's measurements, in its device-independent pixels:
//! 32 tall, 46-wide caption buttons. To the left, the one button a phone app
//! needs that a desktop window has no word for — Back — then the app's icon and
//! its name, with the phone's after it. Before the caption buttons, the one
//! more the phone's sound needs: where it plays.

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
            _ => Hit::None,
        }
    }
}

pub struct Bar {
    pub h: i32,
    pub back: RECT,
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
    let icon_x = back_w + (4.0 * scale).round() as i32;
    let icon_rect = RECT { left: icon_x, top: (h - icon) / 2, right: icon_x + icon, bottom: (h - icon) / 2 + icon };
    Bar { h, back, icon: icon_rect, text_x: icon_rect.right + (10.0 * scale).round() as i32, sound, min, max, close }
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
        assert_eq!(hit(618, 1.5, 610, 47), Hit::Close);
    }
}
