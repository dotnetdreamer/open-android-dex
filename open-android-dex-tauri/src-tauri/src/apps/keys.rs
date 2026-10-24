//! This computer's keyboard, as Android takes it — for the app windows.
//!
//! The rule is the one the desktop's own window followed (doc/apps-on-pc.md,
//! "Input"): a character the computer's own layout typed goes as that
//! character, so é on a French keyboard is é on the phone whatever layout the
//! phone thinks it has; a key that does something — Enter, the arrows,
//! Backspace — and any key held with Ctrl or the Windows key goes as Android's
//! key code with its modifiers, a letter by the letter the layout types
//! (Windows' virtual-key codes for letters already follow the layout, so
//! Ctrl+A on AZERTY is select-all).
//!
//! Only the mapping is here, as data and pure functions, so it is checked off
//! the phone; the window turns Windows' messages into calls to these. A Mac's
//! keyboard is `mac`, below. Each half is used on its own computer only, and
//! both are tested on any.
#![cfg_attr(not(windows), allow(dead_code))]

/// Android's KeyEvent.META_* bits.
pub mod meta {
    pub const SHIFT: u32 = 0x1 | 0x40; // META_SHIFT_ON | META_SHIFT_LEFT_ON
    pub const ALT: u32 = 0x2 | 0x10; // META_ALT_ON | META_ALT_LEFT_ON
    pub const CTRL: u32 = 0x1000 | 0x2000; // META_CTRL_ON | META_CTRL_LEFT_ON
    pub const META: u32 = 0x10000 | 0x20000; // META_META_ON | META_META_LEFT_ON
    pub const CAPS_LOCK: u32 = 0x100000;
    pub const NUM_LOCK: u32 = 0x200000;
}

/// Android's KeyEvent.KEYCODE_* for the keys the window sends.
pub mod code {
    pub const BACK: u16 = 4;
    pub const FORWARD: u16 = 125;
    pub const ENTER: u16 = 66;
    pub const DEL: u16 = 67;
    pub const ESCAPE: u16 = 111;
}

/// Which modifiers are held, as Windows reports them.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Mods {
    pub shift: bool,
    pub ctrl: bool,
    pub alt: bool,
    /// Right Alt with Ctrl: AltGr, which on Windows IS Ctrl+Alt.
    pub altgr: bool,
    pub win: bool,
    pub caps: bool,
    pub num: bool,
}

impl Mods {
    pub fn meta(self) -> u32 {
        let mut m = 0;
        if self.shift {
            m |= meta::SHIFT;
        }
        // AltGr is how a character is typed, not a shortcut: Android is told neither half.
        if self.ctrl && !self.altgr {
            m |= meta::CTRL;
        }
        if self.alt && !self.altgr {
            m |= meta::ALT;
        }
        if self.win {
            m |= meta::META;
        }
        if self.caps {
            m |= meta::CAPS_LOCK;
        }
        if self.num {
            m |= meta::NUM_LOCK;
        }
        m
    }

    /// Held with a printable key, these make it a shortcut rather than a character.
    pub fn shortcut(self) -> bool {
        (self.ctrl && !self.altgr) || self.win || (self.alt && !self.altgr)
    }
}

// Windows virtual-key codes (WinUser.h), the ones this needs by name.
const VK_BACK: u32 = 0x08;
const VK_TAB: u32 = 0x09;
const VK_CLEAR: u32 = 0x0C;
const VK_RETURN: u32 = 0x0D;
const VK_SHIFT: u32 = 0x10;
const VK_CONTROL: u32 = 0x11;
const VK_MENU: u32 = 0x12;
const VK_PAUSE: u32 = 0x13;
const VK_CAPITAL: u32 = 0x14;
const VK_ESCAPE: u32 = 0x1B;
const VK_SPACE: u32 = 0x20;
const VK_PRIOR: u32 = 0x21;
const VK_NEXT: u32 = 0x22;
const VK_END: u32 = 0x23;
const VK_HOME: u32 = 0x24;
const VK_LEFT: u32 = 0x25;
const VK_UP: u32 = 0x26;
const VK_RIGHT: u32 = 0x27;
const VK_DOWN: u32 = 0x28;
const VK_SNAPSHOT: u32 = 0x2C;
const VK_INSERT: u32 = 0x2D;
const VK_DELETE: u32 = 0x2E;
const VK_LWIN: u32 = 0x5B;
const VK_RWIN: u32 = 0x5C;
const VK_APPS: u32 = 0x5D;
const VK_NUMPAD0: u32 = 0x60;
const VK_MULTIPLY: u32 = 0x6A;
const VK_ADD: u32 = 0x6B;
const VK_SUBTRACT: u32 = 0x6D;
const VK_DECIMAL: u32 = 0x6E;
const VK_DIVIDE: u32 = 0x6F;
const VK_F1: u32 = 0x70;
const VK_F12: u32 = 0x7B;
const VK_NUMLOCK: u32 = 0x90;
const VK_SCROLL: u32 = 0x91;
const VK_LSHIFT: u32 = 0xA0;
const VK_RSHIFT: u32 = 0xA1;
const VK_LCONTROL: u32 = 0xA2;
const VK_RCONTROL: u32 = 0xA3;
const VK_LMENU: u32 = 0xA4;
const VK_RMENU: u32 = 0xA5;
const VK_BROWSER_BACK: u32 = 0xA6;
const VK_BROWSER_FORWARD: u32 = 0xA7;
const VK_BROWSER_REFRESH: u32 = 0xA8;
const VK_BROWSER_SEARCH: u32 = 0xAA;
const VK_VOLUME_MUTE: u32 = 0xAD;
const VK_VOLUME_DOWN: u32 = 0xAE;
const VK_VOLUME_UP: u32 = 0xAF;
const VK_MEDIA_NEXT_TRACK: u32 = 0xB0;
const VK_MEDIA_PREV_TRACK: u32 = 0xB1;
const VK_MEDIA_STOP: u32 = 0xB2;
const VK_MEDIA_PLAY_PAUSE: u32 = 0xB3;
const VK_OEM_1: u32 = 0xBA;
const VK_OEM_PLUS: u32 = 0xBB;
const VK_OEM_COMMA: u32 = 0xBC;
const VK_OEM_MINUS: u32 = 0xBD;
const VK_OEM_PERIOD: u32 = 0xBE;
const VK_OEM_2: u32 = 0xBF;
const VK_OEM_3: u32 = 0xC0;
const VK_OEM_4: u32 = 0xDB;
const VK_OEM_5: u32 = 0xDC;
const VK_OEM_6: u32 = 0xDD;
const VK_OEM_7: u32 = 0xDE;
const VK_OEM_102: u32 = 0xE2;

/// Android's key code for a Windows key; `extended` is the scan code's
/// extended bit, which is what tells the keypad's Enter from the main one.
pub fn android_code(vk: u32, extended: bool) -> Option<u16> {
    Some(match vk {
        0x41..=0x5A => 29 + (vk - 0x41) as u16, // A..Z → KEYCODE_A..Z
        0x30..=0x39 => 7 + (vk - 0x30) as u16,  // 0..9 → KEYCODE_0..9
        VK_NUMPAD0..=0x69 => 144 + (vk - VK_NUMPAD0) as u16,
        VK_F1..=VK_F12 => 131 + (vk - VK_F1) as u16,
        VK_RETURN => {
            if extended {
                160 // NUMPAD_ENTER
            } else {
                code::ENTER
            }
        }
        VK_BACK => code::DEL,
        VK_DELETE => 112, // FORWARD_DEL
        VK_TAB => 61,
        VK_SPACE => 62,
        VK_ESCAPE => code::ESCAPE,
        VK_LEFT => 21,
        VK_RIGHT => 22,
        VK_UP => 19,
        VK_DOWN => 20,
        VK_HOME => 122, // MOVE_HOME
        VK_END => 123,  // MOVE_END
        VK_PRIOR => 92, // PAGE_UP
        VK_NEXT => 93,  // PAGE_DOWN
        VK_INSERT => 124,
        VK_CLEAR => 28,
        VK_SHIFT | VK_LSHIFT => 59,
        VK_RSHIFT => 60,
        VK_CONTROL | VK_LCONTROL => 113,
        VK_RCONTROL => 114,
        VK_MENU | VK_LMENU => 57,
        VK_RMENU => 58,
        VK_LWIN => 117,
        VK_RWIN => 118,
        VK_APPS => 82, // MENU
        VK_CAPITAL => 115,
        VK_NUMLOCK => 143,
        VK_SCROLL => 116,
        VK_PAUSE => 121,    // BREAK
        VK_SNAPSHOT => 120, // SYSRQ
        VK_MULTIPLY => 155,
        VK_ADD => 157,
        VK_SUBTRACT => 156,
        VK_DECIMAL => 158,
        VK_DIVIDE => 154,
        VK_OEM_1 => 74,      // ; :
        VK_OEM_PLUS => 70,   // = +
        VK_OEM_COMMA => 55,  // ,
        VK_OEM_MINUS => 69,  // -
        VK_OEM_PERIOD => 56, // .
        VK_OEM_2 => 76,      // /
        VK_OEM_3 => 68,      // `
        VK_OEM_4 => 71,      // [
        VK_OEM_5 => 73,      // \
        VK_OEM_6 => 72,      // ]
        VK_OEM_7 => 75,      // '
        VK_OEM_102 => 73,    // the ISO key beside left Shift: a backslash on most layouts
        VK_BROWSER_BACK => code::BACK,
        VK_BROWSER_FORWARD => code::FORWARD,
        VK_BROWSER_REFRESH => 285, // REFRESH
        VK_BROWSER_SEARCH => 84,   // SEARCH
        VK_VOLUME_MUTE => 164,
        VK_VOLUME_DOWN => 25,
        VK_VOLUME_UP => 24,
        VK_MEDIA_NEXT_TRACK => 87,
        VK_MEDIA_PREV_TRACK => 88,
        VK_MEDIA_STOP => 86,
        VK_MEDIA_PLAY_PAUSE => 85,
        _ => return None,
    })
}

/// Keys that are only ever keys — never "a character the layout typed" —
/// even though Windows makes a WM_CHAR for some of them.
fn always_a_key(vk: u32) -> bool {
    matches!(
        vk,
        VK_RETURN | VK_BACK | VK_TAB | VK_ESCAPE | VK_DELETE | VK_INSERT | VK_CLEAR
    )
}

/// Modifier keys: never sent on their own. Android reads what is held from
/// each event's meta, and a lone Shift it is told of is a Shift some apps
/// then believe is held.
pub fn modifier(vk: u32) -> bool {
    matches!(
        vk,
        VK_SHIFT | VK_LSHIFT | VK_RSHIFT | VK_CONTROL | VK_LCONTROL | VK_RCONTROL | VK_MENU | VK_LMENU | VK_RMENU
            | VK_LWIN | VK_RWIN | VK_CAPITAL | VK_NUMLOCK | VK_SCROLL
    )
}

/// How a key press goes to the phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Press {
    /// As the character Windows is about to type for it (WM_CHAR).
    Text,
    /// As Android's key code, with the modifiers.
    Key(u16),
    /// Not at all.
    Nothing,
}

/// The decision for one key down. `types` is whether the layout makes a
/// character for this key (MapVirtualKey's VK_TO_CHAR is not zero).
pub fn press(vk: u32, extended: bool, types: bool, mods: Mods) -> Press {
    if modifier(vk) {
        return Press::Nothing;
    }
    if types && !always_a_key(vk) && !mods.shortcut() {
        return Press::Text;
    }
    match android_code(vk, extended) {
        Some(c) => Press::Key(c),
        None => Press::Nothing,
    }
}

/// Whether a WM_CHAR is text to send: control characters are the keys that
/// made them (Enter, Tab, Ctrl+letter), already sent as keys.
pub fn printable(c: u32) -> bool {
    c >= 0x20 && c != 0x7F
}

/// A Mac's keyboard (mac/view.rs), by the same rule: what the layout types
/// goes as text, through AppKit's own text input (so dead keys and input
/// methods type what they type); a key that does something, and any key held
/// with ⌘ or Control, goes as a key. ⌘ is Android's Ctrl, as Control is: ⌘C
/// copies. macOS's key codes name places on the keyboard (Carbon's kVK_*, by
/// a US layout's legends), so a shortcut's letter is the one the layout types
/// there: ⌘A on AZERTY is select-all. And the Mac's own ways through text —
/// ⌥ for a word, ⌘ for the line — become Android's.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub mod mac {
    use super::{code, meta};

    /// Which modifiers are held, as an NSEvent's modifierFlags say.
    #[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
    pub struct Mods {
        pub shift: bool,
        pub control: bool,
        pub option: bool,
        pub command: bool,
        pub caps: bool,
    }

    impl Mods {
        /// Android's meta: ⌘ and Control both as its Ctrl, ⌥ as its Alt.
        pub fn meta(self) -> u32 {
            let mut m = 0;
            if self.shift {
                m |= meta::SHIFT;
            }
            if self.control || self.command {
                m |= meta::CTRL;
            }
            if self.option {
                m |= meta::ALT;
            }
            if self.caps {
                m |= meta::CAPS_LOCK;
            }
            m
        }
    }

    // Carbon's virtual key codes (HIToolbox/Events.h), the ones this needs by name.
    const RETURN: u16 = 0x24;
    const TAB: u16 = 0x30;
    const DELETE: u16 = 0x33;
    /// Also what takes a full-screen window out of full screen (mac/mod.rs).
    pub const ESCAPE: u16 = 0x35;
    const RIGHT_COMMAND: u16 = 0x36;
    const COMMAND: u16 = 0x37;
    const SHIFT: u16 = 0x38;
    const CAPS_LOCK: u16 = 0x39;
    const OPTION: u16 = 0x3A;
    const CONTROL: u16 = 0x3B;
    const RIGHT_SHIFT: u16 = 0x3C;
    const RIGHT_OPTION: u16 = 0x3D;
    const RIGHT_CONTROL: u16 = 0x3E;
    const FUNCTION: u16 = 0x3F;
    const VOLUME_UP: u16 = 0x48;
    const VOLUME_DOWN: u16 = 0x49;
    const MUTE: u16 = 0x4A;
    const KEYPAD_ENTER: u16 = 0x4C;
    const HELP: u16 = 0x72;
    const HOME: u16 = 0x73;
    const PAGE_UP: u16 = 0x74;
    const FORWARD_DELETE: u16 = 0x75;
    const END: u16 = 0x77;
    const PAGE_DOWN: u16 = 0x79;
    const LEFT: u16 = 0x7B;
    const RIGHT: u16 = 0x7C;
    const DOWN: u16 = 0x7D;
    const UP: u16 = 0x7E;
    const LEFT_BRACKET: u16 = 0x21;
    const RIGHT_BRACKET: u16 = 0x1E;
    /// F1 to F12, in order: their codes are scattered.
    const F_KEYS: [u16; 12] = [0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F];

    /// Keys only ever sent as keys, whatever else is held: Android's code for each.
    fn action_key(kvk: u16) -> Option<u16> {
        if let Some(i) = F_KEYS.iter().position(|&f| f == kvk) {
            return Some(131 + i as u16); // F1..F12
        }
        Some(match kvk {
            RETURN => code::ENTER,
            KEYPAD_ENTER => 160,
            TAB => 61,
            DELETE => code::DEL,
            FORWARD_DELETE => 112,
            ESCAPE => code::ESCAPE,
            LEFT => 21,
            RIGHT => 22,
            UP => 19,
            DOWN => 20,
            HOME => 122,
            END => 123,
            PAGE_UP => 92,
            PAGE_DOWN => 93,
            HELP => 124, // INSERT: where a Mac keyboard has Help
            VOLUME_UP => 24,
            VOLUME_DOWN => 25,
            MUTE => 164,
            _ => return None,
        })
    }

    /// Modifier keys: never sent on their own, as on Windows.
    pub fn modifier(kvk: u16) -> bool {
        matches!(
            kvk,
            COMMAND | RIGHT_COMMAND | SHIFT | RIGHT_SHIFT | OPTION | RIGHT_OPTION | CONTROL | RIGHT_CONTROL | CAPS_LOCK
                | FUNCTION
        )
    }

    /// Android's code for a character a key types with nothing held.
    fn char_code(c: char) -> Option<u16> {
        let c = c.to_ascii_lowercase();
        Some(match c {
            'a'..='z' => 29 + (c as u8 - b'a') as u16,
            '0'..='9' => 7 + (c as u8 - b'0') as u16,
            ' ' => 62,
            '`' => 68,
            '-' => 69,
            '=' => 70,
            '[' => 71,
            ']' => 72,
            '\\' => 73,
            ';' => 74,
            '\'' => 75,
            '/' => 76,
            ',' => 55,
            '.' => 56,
            _ => return None,
        })
    }

    /// Android's code for a key by its place: a US keyboard's legend there,
    /// for a layout whose own character has no key code (Cyrillic, Greek…).
    fn place_code(kvk: u16) -> Option<u16> {
        const PLACES: &[(u16, char)] = &[
            (0x00, 'a'), (0x0B, 'b'), (0x08, 'c'), (0x02, 'd'), (0x0E, 'e'), (0x03, 'f'), (0x05, 'g'),
            (0x04, 'h'), (0x22, 'i'), (0x26, 'j'), (0x28, 'k'), (0x25, 'l'), (0x2E, 'm'), (0x2D, 'n'),
            (0x1F, 'o'), (0x23, 'p'), (0x0C, 'q'), (0x0F, 'r'), (0x01, 's'), (0x11, 't'), (0x20, 'u'),
            (0x09, 'v'), (0x0D, 'w'), (0x07, 'x'), (0x10, 'y'), (0x06, 'z'), (0x1D, '0'), (0x12, '1'),
            (0x13, '2'), (0x14, '3'), (0x15, '4'), (0x17, '5'), (0x16, '6'), (0x1A, '7'), (0x1C, '8'),
            (0x19, '9'), (0x31, ' '),
        ];
        PLACES.iter().find(|(k, _)| *k == kvk).and_then(|(_, c)| char_code(*c))
    }

    /// How a key down goes to the phone.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum Press {
        /// As the text AppKit's input makes of it (interpretKeyEvents).
        Text,
        /// As Android's key code, with Android's meta.
        Key(u16, u32),
        /// Not at all.
        Nothing,
    }

    /// The decision for one key down. `plain` is what the key types with no
    /// modifiers held (charactersIgnoringModifiers).
    pub fn press(kvk: u16, plain: &str, m: Mods) -> Press {
        if modifier(kvk) {
            return Press::Nothing;
        }
        let shift = if m.shift { meta::SHIFT } else { 0 };
        if let Some(c) = action_key(kvk) {
            return match kvk {
                // ⌘← and ⌘→: the line's start and end; ⌘↑ and ⌘↓: the text's.
                LEFT | RIGHT if m.command => Press::Key(if kvk == LEFT { 122 } else { 123 }, shift),
                UP | DOWN if m.command => Press::Key(if kvk == UP { 122 } else { 123 }, shift | meta::CTRL),
                // ⌥← and ⌥→ go a word; ⌥⌫ takes one.
                LEFT | RIGHT | DELETE | FORWARD_DELETE if m.option => Press::Key(c, shift | meta::CTRL),
                _ => Press::Key(c, m.meta()),
            };
        }
        if !(m.command || m.control) {
            return Press::Text;
        }
        let ch = plain.chars().next();
        let by_char = ch.and_then(char_code);
        // ⌘[ and ⌘] are Back and Forward, as in Safari and Finder.
        if m.command && !m.control && !m.option {
            if ch == Some('[') || (by_char.is_none() && kvk == LEFT_BRACKET) {
                return Press::Key(code::BACK, 0);
            }
            if ch == Some(']') || (by_char.is_none() && kvk == RIGHT_BRACKET) {
                return Press::Key(code::FORWARD, 0);
            }
        }
        match by_char.or_else(|| place_code(kvk)) {
            Some(c) => Press::Key(c, m.meta()),
            None => Press::Nothing,
        }
    }

    /// The shortcuts that stay the Mac's, for this window and this app: quit,
    /// close, minimise, hide, the next window, settings, full screen.
    pub fn stays_on_mac(plain: &str, m: Mods) -> bool {
        if !m.command {
            return false;
        }
        match plain.chars().next().map(|c| c.to_ascii_lowercase()) {
            Some('q' | 'w' | 'm' | 'h' | '`' | ',') => true,
            Some('f') => m.control,
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NONE: Mods = Mods { shift: false, ctrl: false, alt: false, altgr: false, win: false, caps: false, num: false };

    #[test]
    fn a_letter_typed_is_text_and_a_shortcut_is_a_key() {
        assert_eq!(press(0x41, false, true, NONE), Press::Text);
        assert_eq!(press(0x41, false, true, Mods { ctrl: true, ..NONE }), Press::Key(29));
        assert_eq!(press(0x56, false, true, Mods { ctrl: true, shift: true, ..NONE }), Press::Key(50));
        assert_eq!(press(0x41, false, true, Mods { win: true, ..NONE }), Press::Key(29));
    }

    #[test]
    fn altgr_types_a_character() {
        // AltGr+E is € on a German keyboard: Windows says Ctrl+Alt, the layout types it.
        let altgr = Mods { ctrl: true, alt: true, altgr: true, ..NONE };
        assert_eq!(press(0x45, false, true, altgr), Press::Text);
        assert_eq!(altgr.meta(), 0);
    }

    #[test]
    fn keys_that_do_things_are_keys() {
        assert_eq!(press(VK_RETURN, false, true, NONE), Press::Key(66));
        assert_eq!(press(VK_RETURN, true, true, NONE), Press::Key(160));
        assert_eq!(press(VK_BACK, false, true, NONE), Press::Key(67));
        assert_eq!(press(VK_LEFT, true, false, Mods { shift: true, ..NONE }), Press::Key(21));
        assert_eq!(press(VK_F1 + 4, false, false, NONE), Press::Key(135));
        assert_eq!(press(VK_SHIFT, false, false, NONE), Press::Nothing);
    }

    #[test]
    fn meta_bits_are_androids() {
        assert_eq!(Mods { shift: true, ..NONE }.meta(), 0x41);
        assert_eq!(Mods { ctrl: true, ..NONE }.meta(), 0x3000);
        assert_eq!(Mods { ctrl: true, shift: true, caps: true, ..NONE }.meta(), 0x3041 | 0x100000);
    }

    #[test]
    fn a_macs_typing_is_text_and_its_shortcuts_are_keys() {
        use mac::{press, stays_on_mac, Mods as M, Press as P};
        let none = M::default();
        let cmd = M { command: true, ..none };
        // A, then ⌘A by the letter the layout types: AZERTY's A sits where a US Q does.
        assert_eq!(press(0x00, "a", none), P::Text);
        assert_eq!(press(0x00, "a", cmd), P::Key(29, meta::CTRL));
        assert_eq!(press(0x0C, "a", cmd), P::Key(29, meta::CTRL));
        // ⌘⇧Z, and Control+C as Ctrl+C.
        assert_eq!(press(0x06, "z", M { shift: true, ..cmd }), P::Key(54, meta::CTRL | meta::SHIFT));
        assert_eq!(press(0x08, "c", M { control: true, ..none }), P::Key(31, meta::CTRL));
        // A Cyrillic layout's ⌘C: its letter has no key code, its place does.
        assert_eq!(press(0x08, "с", cmd), P::Key(31, meta::CTRL));
        // ⌥ alone types: ⌥E is a dead key, ⌥2 is ™.
        assert_eq!(press(0x0E, "e", M { option: true, ..none }), P::Text);
        assert_eq!(press(0x38, "", none), P::Nothing);
        assert_eq!(press(0x24, "\r", none), P::Key(66, 0));
        assert_eq!(press(0x33, "\u{7f}", none), P::Key(67, 0));
        assert_eq!(press(0x7A, "", none), P::Key(131, 0));
        assert_eq!(press(0x6F, "", none), P::Key(142, 0));
        // ⌘[ is Back.
        assert_eq!(press(0x21, "[", cmd), P::Key(4, 0));
        assert!(stays_on_mac("q", cmd) && stays_on_mac("w", cmd) && !stays_on_mac("c", cmd));
        assert!(!stays_on_mac("q", M { control: true, ..none }));
    }

    #[test]
    fn a_macs_ways_through_text_are_androids() {
        use mac::{press, Mods as M, Press as P};
        let none = M::default();
        let cmd = M { command: true, ..none };
        let opt = M { option: true, ..none };
        assert_eq!(press(0x7B, "", cmd), P::Key(122, 0)); // ⌘← line start
        assert_eq!(press(0x7C, "", M { shift: true, ..cmd }), P::Key(123, meta::SHIFT)); // ⌘⇧→ select to line end
        assert_eq!(press(0x7E, "", cmd), P::Key(122, meta::CTRL)); // ⌘↑ text start
        assert_eq!(press(0x7B, "", opt), P::Key(21, meta::CTRL)); // ⌥← a word back
        assert_eq!(press(0x33, "", opt), P::Key(67, meta::CTRL)); // ⌥⌫ a word gone
        assert_eq!(press(0x7D, "", none), P::Key(20, 0));
    }

    #[test]
    fn control_characters_are_not_text() {
        assert!(!printable(0x0D));
        assert!(!printable(0x01)); // Ctrl+A's WM_CHAR
        assert!(!printable(0x7F));
        assert!(printable('é' as u32));
        assert!(printable(' ' as u32));
    }
}
