//! One name per physical key, for game controls (doc/game-mode.md, "Layouts").
//!
//! The names are the W3C `KeyboardEvent.code` ones, which the editor's webview
//! reads straight from its key events, so a layout says `KeyW` on every
//! computer and means the key in that place: W A S D are the same four keys
//! on an AZERTY keyboard. The native windows know a key by its own number — a
//! Mac's virtual key code (Carbon's kVK_*), a Windows set-1 scan code with its
//! extended bit — and this table turns either into the name.
//!
//! The keys Open Android DeX keeps for itself are named too (the windows need
//! to know them), and refused as a control's key: Esc gives the mouse back,
//! the key under Esc takes it, F8 turns the controls on and off, F11 is full
//! screen, and ⌘ / the Windows key belong to the computer.

/// A key, by its `KeyboardEvent.code` name.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, PartialOrd, Ord)]
pub struct Key(pub &'static str);

/// A Windows scan code with the extended bit, as `0xE0nn`.
const fn ext(scan: u16) -> u16 {
    0xE000 | scan
}

/// (name, a Mac's key code, a Windows scan code). The ISO key between left
/// Shift and Z is `IntlBackslash` on Windows; a Mac reports it as 0x0A, which
/// macOS swaps with the key under Esc on some keyboards — so both of a Mac's
/// codes there are taken as the key under Esc, and that key is kept back.
const KEYS: &[(&str, Option<u16>, Option<u16>)] = &[
    ("KeyA", Some(0x00), Some(0x1E)),
    ("KeyB", Some(0x0B), Some(0x30)),
    ("KeyC", Some(0x08), Some(0x2E)),
    ("KeyD", Some(0x02), Some(0x20)),
    ("KeyE", Some(0x0E), Some(0x12)),
    ("KeyF", Some(0x03), Some(0x21)),
    ("KeyG", Some(0x05), Some(0x22)),
    ("KeyH", Some(0x04), Some(0x23)),
    ("KeyI", Some(0x22), Some(0x17)),
    ("KeyJ", Some(0x26), Some(0x24)),
    ("KeyK", Some(0x28), Some(0x25)),
    ("KeyL", Some(0x25), Some(0x26)),
    ("KeyM", Some(0x2E), Some(0x32)),
    ("KeyN", Some(0x2D), Some(0x31)),
    ("KeyO", Some(0x1F), Some(0x18)),
    ("KeyP", Some(0x23), Some(0x19)),
    ("KeyQ", Some(0x0C), Some(0x10)),
    ("KeyR", Some(0x0F), Some(0x13)),
    ("KeyS", Some(0x01), Some(0x1F)),
    ("KeyT", Some(0x11), Some(0x14)),
    ("KeyU", Some(0x20), Some(0x16)),
    ("KeyV", Some(0x09), Some(0x2F)),
    ("KeyW", Some(0x0D), Some(0x11)),
    ("KeyX", Some(0x07), Some(0x2D)),
    ("KeyY", Some(0x10), Some(0x15)),
    ("KeyZ", Some(0x06), Some(0x2C)),
    ("Digit1", Some(0x12), Some(0x02)),
    ("Digit2", Some(0x13), Some(0x03)),
    ("Digit3", Some(0x14), Some(0x04)),
    ("Digit4", Some(0x15), Some(0x05)),
    ("Digit5", Some(0x17), Some(0x06)),
    ("Digit6", Some(0x16), Some(0x07)),
    ("Digit7", Some(0x1A), Some(0x08)),
    ("Digit8", Some(0x1C), Some(0x09)),
    ("Digit9", Some(0x19), Some(0x0A)),
    ("Digit0", Some(0x1D), Some(0x0B)),
    ("F1", Some(0x7A), Some(0x3B)),
    ("F2", Some(0x78), Some(0x3C)),
    ("F3", Some(0x63), Some(0x3D)),
    ("F4", Some(0x76), Some(0x3E)),
    ("F5", Some(0x60), Some(0x3F)),
    ("F6", Some(0x61), Some(0x40)),
    ("F7", Some(0x62), Some(0x41)),
    ("F8", Some(0x64), Some(0x42)),
    ("F9", Some(0x65), Some(0x43)),
    ("F10", Some(0x6D), Some(0x44)),
    ("F11", Some(0x67), Some(0x57)),
    ("F12", Some(0x6F), Some(0x58)),
    ("Space", Some(0x31), Some(0x39)),
    ("Tab", Some(0x30), Some(0x0F)),
    ("Enter", Some(0x24), Some(0x1C)),
    ("Backspace", Some(0x33), Some(0x0E)),
    ("Escape", Some(0x35), Some(0x01)),
    ("Minus", Some(0x1B), Some(0x0C)),
    ("Equal", Some(0x18), Some(0x0D)),
    ("BracketLeft", Some(0x21), Some(0x1A)),
    ("BracketRight", Some(0x1E), Some(0x1B)),
    ("Backslash", Some(0x2A), Some(0x2B)),
    ("Semicolon", Some(0x29), Some(0x27)),
    ("Quote", Some(0x27), Some(0x28)),
    ("Comma", Some(0x2B), Some(0x33)),
    ("Period", Some(0x2F), Some(0x34)),
    ("Slash", Some(0x2C), Some(0x35)),
    ("IntlBackslash", None, Some(0x56)),
    ("Backquote", Some(0x32), Some(0x29)),
    ("ArrowUp", Some(0x7E), Some(ext(0x48))),
    ("ArrowDown", Some(0x7D), Some(ext(0x50))),
    ("ArrowLeft", Some(0x7B), Some(ext(0x4B))),
    ("ArrowRight", Some(0x7C), Some(ext(0x4D))),
    ("ShiftLeft", Some(0x38), Some(0x2A)),
    ("ShiftRight", Some(0x3C), Some(0x36)),
    ("ControlLeft", Some(0x3B), Some(0x1D)),
    ("ControlRight", Some(0x3E), Some(ext(0x1D))),
    ("AltLeft", Some(0x3A), Some(0x38)),
    ("AltRight", Some(0x3D), Some(ext(0x38))),
    ("MetaLeft", Some(0x37), Some(ext(0x5B))),
    ("MetaRight", Some(0x36), Some(ext(0x5C))),
    ("CapsLock", Some(0x39), Some(0x3A)),
    ("Insert", Some(0x72), Some(ext(0x52))),
    ("Delete", Some(0x75), Some(ext(0x53))),
    ("Home", Some(0x73), Some(ext(0x47))),
    ("End", Some(0x77), Some(ext(0x4F))),
    ("PageUp", Some(0x74), Some(ext(0x49))),
    ("PageDown", Some(0x79), Some(ext(0x51))),
    ("Numpad0", Some(0x52), Some(0x52)),
    ("Numpad1", Some(0x53), Some(0x4F)),
    ("Numpad2", Some(0x54), Some(0x50)),
    ("Numpad3", Some(0x55), Some(0x51)),
    ("Numpad4", Some(0x56), Some(0x4B)),
    ("Numpad5", Some(0x57), Some(0x4C)),
    ("Numpad6", Some(0x58), Some(0x4D)),
    ("Numpad7", Some(0x59), Some(0x47)),
    ("Numpad8", Some(0x5B), Some(0x48)),
    ("Numpad9", Some(0x5C), Some(0x49)),
    ("NumpadDecimal", Some(0x41), Some(0x53)),
    ("NumpadAdd", Some(0x45), Some(0x4E)),
    ("NumpadSubtract", Some(0x4E), Some(0x4A)),
    ("NumpadMultiply", Some(0x43), Some(0x37)),
    ("NumpadDivide", Some(0x4B), Some(ext(0x35))),
    ("NumpadEnter", Some(0x4C), Some(ext(0x1C))),
    ("NumpadEqual", Some(0x51), None),
];

/// The second of a Mac's codes for the key under Esc (the ISO key, swapped with it on some keyboards).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
const MAC_ISO_SECTION: u16 = 0x0A;

/// The keys a control may not have: Open Android DeX's own.
pub const KEPT: &[&str] = &["Escape", "F8", "F11", "Backquote", "MetaLeft", "MetaRight"];

pub const ESCAPE: Key = Key("Escape");
/// The key under Esc: takes the mouse for looking around, and gives it back.
pub const UNDER_ESCAPE: Key = Key("Backquote");

impl Key {
    /// The key called `name`, if there is one.
    pub fn named(name: &str) -> Option<Key> {
        KEYS.iter().find(|(n, _, _)| *n == name).map(|(n, _, _)| Key(n))
    }

    /// Whether a control may have it.
    pub fn bindable(self) -> bool {
        !KEPT.contains(&self.0)
    }

    /// Whether this computer's windows ever hand a control this key: it has a
    /// code here. A Mac has none of its own for the ISO key (it takes it as the
    /// key under Esc) and sends Caps Lock only as a change of state; Windows
    /// has no scan code for the number pad's =. Elsewhere — no game controls — any.
    pub fn here(self) -> bool {
        let Some(&(_, mac, windows)) = KEYS.iter().find(|(n, _, _)| *n == self.0) else { return false };
        if cfg!(target_os = "macos") {
            mac.is_some() && self.0 != "CapsLock"
        } else if cfg!(windows) {
            windows.is_some()
        } else {
            true
        }
    }
}

/// Whether this computer's keyboard has a key under Esc. An Apple JIS keyboard
/// has none — its number row starts at 1 — so an `All the time` look left on
/// that key could never take the mouse, and the editor and the note ask for
/// another key instead. Asked of the keyboard last used (a Mac); elsewhere a
/// JIS keyboard's 半角/全角 is that key, by its place.
pub fn has_key_under_escape() -> bool {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "Carbon", kind = "framework")]
        unsafe extern "C" {
            fn LMGetKbdType() -> u8;
            fn KBGetLayoutType(keyboard_type: i16) -> u32;
        }
        !is_jis(unsafe { KBGetLayoutType(LMGetKbdType() as i16) })
    }
    #[cfg(not(target_os = "macos"))]
    {
        true
    }
}

/// HIToolbox's physical layout types, as KBGetLayoutType names them: kKeyboardJIS is 'JIS '.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub(super) fn is_jis(layout_type: u32) -> bool {
    layout_type == u32::from_be_bytes(*b"JIS ")
}

/// The key a Mac's key code names.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn from_mac(kvk: u16) -> Option<Key> {
    if kvk == MAC_ISO_SECTION {
        return Some(UNDER_ESCAPE);
    }
    KEYS.iter().find(|(_, m, _)| *m == Some(kvk)).map(|(n, _, _)| Key(n))
}

/// Whether a key down with ⌘ is ⌘G, game controls' on and off on a Mac: the key
/// that types "g" (`plain`, charactersIgnoringModifiers) — Dvorak's and
/// Colemak's G is elsewhere — and only where this key types no Latin letter at
/// all (Cyrillic, Greek, Hebrew…), the key in G's place, kVK_ANSI_G. The rule
/// the window's other shortcuts follow (apps/keys.rs, `mac::press`).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn mac_is_g(plain: &str, kvk: u16) -> bool {
    const ANSI_G: u16 = 0x05;
    match plain.chars().next() {
        Some(c) if c.is_ascii_alphabetic() => c.eq_ignore_ascii_case(&'g'),
        _ => kvk == ANSI_G,
    }
}

/// Whether a left Ctrl key message is AltGr's, not a press of left Ctrl:
/// Windows sends AltGr as left Ctrl and then right Alt (VK_MENU, extended), at
/// one time — as GLFW and SDL tell them apart. `next_*` is the message waiting
/// after it, `time` the left Ctrl's own.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn altgr_ctrl(next_msg: u32, next_vk: u32, next_lparam: i64, next_time: u32, time: u32) -> bool {
    const WM_KEYDOWN: u32 = 0x100;
    const WM_KEYUP: u32 = 0x101;
    const WM_SYSKEYDOWN: u32 = 0x104;
    const WM_SYSKEYUP: u32 = 0x105;
    const VK_MENU: u32 = 0x12;
    matches!(next_msg, WM_KEYDOWN | WM_KEYUP | WM_SYSKEYDOWN | WM_SYSKEYUP) && next_vk == VK_MENU && (next_lparam >> 24) & 1 == 1 && next_time == time
}

/// The key a Windows scan code names; `extended` is the message's bit 24.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn from_windows(scan: u16, extended: bool) -> Option<Key> {
    let code = if extended { ext(scan & 0xFF) } else { scan & 0xFF };
    KEYS.iter().find(|(_, _, w)| *w == Some(code)).map(|(n, _, _)| Key(n))
}

/// The virtual key to ask Windows' key state about for a key held by its scan
/// code: a modifier's own side (VK_LSHIFT, VK_RSHIFT…), which Windows keeps
/// apart where its messages do not — both Shifts down, one let go, sends no
/// key-up — else the message's own `vk`. None for a key whose virtual key
/// another physical key shares, since Windows keeps one state per virtual key:
/// the arrows and Insert, Delete, Home, End, Page Up and Page Down with the
/// number pad's keys (which are those with Num Lock off), and Enter with the
/// number pad's Enter. Holding ← and tapping the number pad's 4 would
/// otherwise let go of a stick.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn windows_state_vk(scan: u16, extended: bool, vk: u32) -> Option<u32> {
    match (scan & 0xFF, extended) {
        (0x2A, _) => Some(0xA0),     // VK_LSHIFT
        (0x36, _) => Some(0xA1),     // VK_RSHIFT
        (0x1D, false) => Some(0xA2), // VK_LCONTROL
        (0x1D, true) => Some(0xA3),  // VK_RCONTROL
        (0x38, false) => Some(0xA4), // VK_LMENU
        (0x38, true) => Some(0xA5),  // VK_RMENU
        // Enter and the number pad's Enter: VK_RETURN both.
        (0x1C, _) => None,
        // The number pad's 7 8 9, 4 5 6, 1 2 3, 0 and . — and, extended, the
        // arrows and the six keys above them, which share their virtual keys.
        (0x47..=0x49 | 0x4B..=0x4D | 0x4F..=0x53, _) => None,
        _ => Some(vk),
    }
}

/// A key as a note under the title bar names it: a letter in capitals, else a
/// short name — as the editor's chips do (src/features/game/model.ts, keyLabel).
pub fn label(name: &str, mac: bool) -> String {
    if name == UNDER_ESCAPE.0 {
        return "the key under Esc".into();
    }
    if let Some(l) = name.strip_prefix("Key").filter(|l| l.len() == 1) {
        return l.into();
    }
    if let Some(d) = name.strip_prefix("Digit") {
        return d.into();
    }
    if let Some(d) = name.strip_prefix("Numpad").filter(|d| d.len() == 1) {
        return format!("Num {d}");
    }
    let side = if name.ends_with("Right") && !name.starts_with("Arrow") { "right " } else { "" };
    if name.starts_with("Shift") {
        return format!("{side}Shift");
    }
    if name.starts_with("Control") {
        return format!("{side}{}", if mac { "Control" } else { "Ctrl" });
    }
    if name.starts_with("Alt") {
        return format!("{side}{}", if mac { "Option" } else { "Alt" });
    }
    let named = [
        ("Minus", "-"),
        ("Equal", "="),
        ("BracketLeft", "["),
        ("BracketRight", "]"),
        ("Backslash", "\\"),
        ("Semicolon", ";"),
        ("Quote", "'"),
        ("Comma", ","),
        ("Period", "."),
        ("Slash", "/"),
        ("IntlBackslash", "the ISO key"),
        ("ArrowUp", "↑"),
        ("ArrowDown", "↓"),
        ("ArrowLeft", "←"),
        ("ArrowRight", "→"),
        ("CapsLock", "Caps Lock"),
        ("PageUp", "Page Up"),
        ("PageDown", "Page Down"),
        ("MouseRight", "the right button"),
        ("MouseMiddle", "the middle button"),
        ("MouseBack", "the back button"),
        ("MouseForward", "the forward button"),
    ];
    named.iter().find(|(n, _)| *n == name).map_or_else(|| name.to_string(), |(_, l)| l.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn altgr_s_left_ctrl_is_nobody_s_press() {
        let ralt = 1i64 << 24 | 0x38 << 16; // extended, scan 0x38
        // AltGr: right Alt down (WM_SYSKEYDOWN or WM_KEYDOWN) at the left Ctrl's own time.
        assert!(altgr_ctrl(0x104, 0x12, ralt, 5000, 5000));
        assert!(altgr_ctrl(0x100, 0x12, ralt, 5000, 5000));
        // And its let-go: the left Ctrl up comes before right Alt's.
        assert!(altgr_ctrl(0x105, 0x12, ralt, 7000, 7000));
        // A real left Ctrl: nothing after it, left Alt after it, or right Alt a moment later.
        assert!(!altgr_ctrl(0x0200, 0, 0, 5000, 5000));
        assert!(!altgr_ctrl(0x104, 0x12, 0x38 << 16, 5000, 5000));
        assert!(!altgr_ctrl(0x104, 0x12, ralt, 5016, 5000));
        assert!(!altgr_ctrl(0x100, 0x41, ralt, 5000, 5000));
    }

    #[test]
    fn every_name_once_and_no_code_twice() {
        let names: HashSet<_> = KEYS.iter().map(|k| k.0).collect();
        assert_eq!(names.len(), KEYS.len());
        let mac: Vec<_> = KEYS.iter().filter_map(|k| k.1).collect();
        assert_eq!(mac.iter().collect::<HashSet<_>>().len(), mac.len());
        assert!(!mac.contains(&MAC_ISO_SECTION));
        let win: Vec<_> = KEYS.iter().filter_map(|k| k.2).collect();
        assert_eq!(win.iter().collect::<HashSet<_>>().len(), win.len());
    }

    #[test]
    fn spot_checks() {
        assert_eq!(from_mac(0x0D), Some(Key("KeyW")));
        assert_eq!(from_windows(0x11, false), Some(Key("KeyW")));
        assert_eq!(from_mac(0x7E), Some(Key("ArrowUp")));
        assert_eq!(from_windows(0x48, true), Some(Key("ArrowUp")));
        assert_eq!(from_windows(0x48, false), Some(Key("Numpad8")));
        assert_eq!(from_mac(0x3C), Some(Key("ShiftRight")));
        assert_eq!(from_windows(0x36, false), Some(Key("ShiftRight")));
        assert_eq!(from_windows(0x1D, true), Some(Key("ControlRight")));
        assert_eq!(from_windows(0x1C, true), Some(Key("NumpadEnter")));
        assert_eq!(from_mac(0x31), Some(Key("Space")));
        assert_eq!(from_mac(0x00), Some(Key("KeyA")));
        assert_eq!(from_windows(0x1E, false), Some(Key("KeyA")));
        assert_eq!(from_mac(0x32), Some(UNDER_ESCAPE));
        assert_eq!(from_mac(0x0A), Some(UNDER_ESCAPE));
        assert_eq!(from_windows(0x29, false), Some(UNDER_ESCAPE));
        assert_eq!(from_mac(0x35), Some(ESCAPE));
        assert_eq!(from_windows(0x01, false), Some(ESCAPE));
        assert_eq!(from_mac(0x99), None);
    }

    #[test]
    fn windows_key_state_is_asked_about_each_modifier_s_own_side() {
        // Both Shifts down, one let go: Windows' messages keep no count, its key state does.
        assert_eq!(windows_state_vk(0x2A, false, 0x10), Some(0xA0));
        assert_eq!(windows_state_vk(0x36, false, 0x10), Some(0xA1));
        assert_eq!(windows_state_vk(0x1D, false, 0x11), Some(0xA2));
        assert_eq!(windows_state_vk(0x1D, true, 0x11), Some(0xA3));
        assert_eq!(windows_state_vk(0x38, false, 0x12), Some(0xA4));
        assert_eq!(windows_state_vk(0x38, true, 0x12), Some(0xA5));
        assert_eq!(windows_state_vk(0x11, false, 0x57), Some(0x57), "W is W");
    }

    /// Every key of the table whose virtual key another key shares is never
    /// asked about; every other key is, by its own virtual key.
    #[test]
    fn no_key_is_asked_about_by_a_virtual_key_another_shares() {
        let shared = [
            "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Insert", "Delete", "Home", "End", "PageUp", "PageDown", "Enter",
            "NumpadEnter", "Numpad0", "Numpad1", "Numpad2", "Numpad3", "Numpad4", "Numpad5", "Numpad6", "Numpad7", "Numpad8",
            "Numpad9", "NumpadDecimal",
        ];
        for (name, _, win) in KEYS {
            let Some(code) = win else { continue };
            let (scan, extended) = (code & 0xFF, (code & 0xE000) != 0);
            let asked = windows_state_vk(scan, extended, 0x1234);
            if shared.contains(name) {
                assert_eq!(asked, None, "{name}");
            } else if !["ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight"].contains(name) {
                assert_eq!(asked, Some(0x1234), "{name}");
            }
        }
        // The number pad's other keys have virtual keys of their own.
        for name in ["NumpadAdd", "NumpadSubtract", "NumpadMultiply", "NumpadDivide"] {
            let code = KEYS.iter().find(|k| k.0 == name).and_then(|k| k.2).unwrap();
            assert!(windows_state_vk(code & 0xFF, (code & 0xE000) != 0, 0x6B).is_some(), "{name}");
        }
    }

    #[test]
    fn cmd_g_is_the_key_that_types_g_or_g_s_place_where_no_latin_letter_is() {
        // QWERTY and AZERTY: G's place types g.
        assert!(mac_is_g("g", 0x05));
        assert!(mac_is_g("G", 0x05));
        // Dvorak: G's place types i, and g is typed by QWERTY's U key (0x20).
        assert!(!mac_is_g("i", 0x05));
        assert!(mac_is_g("g", 0x20));
        // Colemak: G's place types d; g is QWERTY's T key (0x11).
        assert!(!mac_is_g("d", 0x05));
        assert!(mac_is_g("g", 0x11));
        // Russian, Greek, Hebrew: no Latin letter there, so G's place.
        assert!(mac_is_g("п", 0x05));
        assert!(mac_is_g("γ", 0x05));
        assert!(mac_is_g("ע", 0x05));
        assert!(!mac_is_g("п", 0x20), "another key with no Latin letter is not G");
        assert!(!mac_is_g("", 0x06));
    }

    #[test]
    fn a_note_names_a_key_as_a_chip_does() {
        assert_eq!(label("Backquote", true), "the key under Esc");
        assert_eq!(label("KeyQ", false), "Q");
        assert_eq!(label("Digit1", true), "1");
        assert_eq!(label("ShiftRight", true), "right Shift");
        assert_eq!(label("AltLeft", true), "Option");
        assert_eq!(label("AltLeft", false), "Alt");
        assert_eq!(label("MouseRight", false), "the right button");
        assert_eq!(label("F5", false), "F5");
        assert_eq!(label("Numpad7", false), "Num 7");
    }

    #[test]
    fn the_kept_keys_refuse() {
        for name in KEPT {
            let k = Key::named(name).expect(name);
            assert!(!k.bindable(), "{name}");
        }
        assert!(Key::named("KeyW").unwrap().bindable());
        assert!(Key::named("ShiftLeft").unwrap().bindable());
        assert_eq!(Key::named("NotAKey"), None);
    }
}
