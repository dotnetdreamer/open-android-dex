//! Where a phone's sound plays while its apps have windows here
//! (doc/apps-on-pc.md, "Where the sound plays"): the desktop's media-output
//! flyout, in every app window's title bar — a Linux app's too, whose sound
//! leaves the phone the way an Android app's does.
//!
//! The same three answers as on the desktop, and the same one setting behind
//! them: this computer only, this computer and the phone, or one of the
//! phone's own outputs — its speaker, a headset, earbuds. The first two are
//! this computer's to carry out (the audio companion, scrcpy.rs); the third is
//! the phone's: its media pinned to that output by the app server, with the
//! shell's permission to route audio, the lever the desktop's flyout pulls
//! through the window daemon. Where the shell may not route audio (Android 11
//! and 12 refuse it), the third answer is the phone as a whole — "Phone only",
//! as the desktop's Settings says it — and the phone plays where it would.

use std::sync::Arc;

use serde_json::{json, Value};

use super::link::Surface;

/// AudioDeviceInfo's types, of the outputs the phone lists.
mod kind {
    pub const SPEAKER: i32 = 2;
    pub const WIRED_HEADSET: i32 = 3;
    pub const WIRED_HEADPHONES: i32 = 4;
    pub const USB_DEVICE: i32 = 11;
    pub const USB_HEADSET: i32 = 22;
}

/// One of the phone's own outputs, as the phone lists it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Output {
    /// Its AudioDeviceInfo type: with the address, what the phone finds it by.
    pub kind: i32,
    pub address: String,
    /// The product's own name ("Galaxy Buds2"), when it has one.
    pub product: String,
    pub builtin: bool,
}

impl Output {
    fn speaker() -> Output {
        Output { kind: kind::SPEAKER, address: String::new(), product: String::new(), builtin: true }
    }

    fn is(&self, (kind, address): &(i32, String)) -> bool {
        // One pair of earbuds answers under two profiles with one address, and
        // the phone lists it once: the address alone says it is the same one.
        (self.kind == *kind && self.address == *address) || (!address.is_empty() && self.address == *address)
    }
}

/// What a phone last said of its sound: its outputs, the one media is pinned
/// to, and the one it plays on now.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PhoneSound {
    pub outputs: Vec<Output>,
    /// Whether the phone lets its media be pinned to one of them at all.
    pub can_pin: bool,
    pub pinned: Option<(i32, String)>,
    pub playing: Option<(i32, String)>,
}

impl PhoneSound {
    /// The phone's `audio` message (AppAudio.state).
    pub fn parse(v: &Value) -> PhoneSound {
        let device = |d: &Value| -> Option<(i32, String)> {
            Some((d["type"].as_i64()? as i32, d["address"].as_str().unwrap_or("").to_string()))
        };
        let outputs = v["outputs"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|o| {
                        Some(Output {
                            kind: o["type"].as_i64()? as i32,
                            address: o["address"].as_str().unwrap_or("").to_string(),
                            product: o["name"].as_str().unwrap_or("").trim().to_string(),
                            builtin: o["builtin"].as_bool().unwrap_or(false),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        PhoneSound {
            outputs,
            can_pin: v["canPin"].as_bool().unwrap_or(false),
            pinned: device(&v["pinned"]),
            playing: device(&v["playing"]),
        }
    }
}

/// The three answers to "where does the sound play", as this computer keeps
/// them: `audio` and `audiodup` (scrcpy.rs, stored_audio_forwarding).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Computer,
    Both,
    Phone,
}

impl Mode {
    pub fn of(forward: bool, duplicate: bool) -> Mode {
        match (forward, duplicate) {
            (false, _) => Mode::Phone,
            (true, true) => Mode::Both,
            (true, false) => Mode::Computer,
        }
    }

    /// The stored pair: (forward, duplicate).
    pub fn keys(self) -> (bool, bool) {
        match self {
            Mode::Computer => (true, false),
            Mode::Both => (true, true),
            Mode::Phone => (false, false),
        }
    }

    /// The launcher's word for it (DexMedia.AUDIO_*).
    pub fn word(self) -> &'static str {
        match self {
            Mode::Computer => "computer",
            Mode::Both => "both",
            Mode::Phone => "phone",
        }
    }
}

/// Where the sound can be sent: what a menu item does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Choice {
    Computer,
    Both,
    /// The phone: pinned to one of its outputs, or (None) where it plays by itself.
    Phone(Option<Output>),
}

/// A line of the menu.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Item {
    Choice { label: String, checked: bool, enabled: bool, choice: Choice },
    /// Between this computer's answers and the phone's outputs.
    Separator,
}

/// The desktop's words for the three answers (the launcher's
/// lx_audio_computer, lx_audio_both and lx_audio_phone): one setting, said one way.
pub const COMPUTER_ONLY: &str = "Computer only";
pub const COMPUTER_AND_PHONE: &str = "Computer and phone";
pub const PHONE_ONLY: &str = "Phone only";

/// The menu, for a phone on Android `sdk` (0 if it has not said) that told
/// `phone` of its outputs, with the sound where `mode` puts it.
///
/// The computer's answers have scrcpy's floors: any sound here needs Android
/// 11, the phone playing it as well 13 — below them the item is there, greyed,
/// saying why. The phone's outputs follow, when the phone lets its media be
/// pinned; otherwise, and while it has said nothing yet, the phone is one
/// answer, "Phone only". Which output is checked, while the sound stays on the
/// phone: the pinned one, else where the phone plays now, else the speaker —
/// the one output with no name of its own.
pub fn menu(mode: Mode, sdk: u32, phone: Option<&PhoneSound>) -> Vec<Item> {
    let can_forward = sdk == 0 || sdk >= 30;
    let can_both = sdk == 0 || sdk >= 33;
    // What actually happens: below 13 "both" is the computer alone, below 11 the phone alone.
    let mode = match mode {
        Mode::Both | Mode::Computer if !can_forward => Mode::Phone,
        Mode::Both if !can_both => Mode::Computer,
        m => m,
    };
    let mut items = vec![
        Item::Choice {
            label: if can_forward { COMPUTER_ONLY.into() } else { format!("{COMPUTER_ONLY} (needs Android 11)") },
            checked: mode == Mode::Computer,
            enabled: can_forward,
            choice: Choice::Computer,
        },
        Item::Choice {
            label: if can_both { COMPUTER_AND_PHONE.into() } else { format!("{COMPUTER_AND_PHONE} (needs Android 13)") },
            checked: mode == Mode::Both,
            enabled: can_both,
            choice: Choice::Both,
        },
        Item::Separator,
    ];
    let Some(phone) = phone.filter(|p| p.can_pin) else {
        items.push(Item::Choice { label: PHONE_ONLY.into(), checked: mode == Mode::Phone, enabled: true, choice: Choice::Phone(None) });
        return items;
    };
    let outputs = if phone.outputs.is_empty() { vec![Output::speaker()] } else { phone.outputs.clone() };
    let lit = (mode == Mode::Phone).then(|| {
        let at = |d: &Option<(i32, String)>| d.as_ref().and_then(|d| outputs.iter().position(|o| o.is(d)));
        at(&phone.pinned)
            .or_else(|| at(&phone.playing))
            .or_else(|| outputs.iter().position(|o| o.builtin))
            .unwrap_or(0)
    });
    for (i, out) in outputs.iter().enumerate() {
        items.push(Item::Choice {
            label: name(out),
            checked: lit == Some(i),
            enabled: true,
            choice: Choice::Phone(Some(out.clone())),
        });
    }
    items
}

/// What a person calls an output: its product's own name, or what it is.
/// The desktop's own fallbacks (the launcher's lx_output_*).
pub fn name(out: &Output) -> String {
    if out.builtin || out.kind == kind::SPEAKER {
        return "Phone speaker".into();
    }
    if !out.product.is_empty() {
        return out.product.clone();
    }
    match out.kind {
        kind::WIRED_HEADSET | kind::WIRED_HEADPHONES => "Wired headphones",
        kind::USB_DEVICE | kind::USB_HEADSET => "USB audio",
        _ => "Bluetooth device",
    }
    .into()
}

/// The menu for `serial`'s windows as things stand, asking the phone again
/// for its outputs as it is drawn: the next one is fresher still.
pub fn menu_for(app: &tauri::AppHandle, serial: &str) -> Vec<Item> {
    let (forward, duplicate) = crate::scrcpy::audio_forwarding(app);
    let server = super::host::running(serial);
    let phone = server.as_ref().and_then(|s| {
        s.ask_sound();
        s.sound()
    });
    let sdk = server.as_ref().map_or(0, |s| s.hello.sdk);
    menu(Mode::of(forward, duplicate), sdk, phone.as_ref())
}

/// Send `serial`'s sound where `choice` says, off the window's thread; the
/// window hears how it went.
///
/// A phone output is pinned first, and only once the phone has taken the pin
/// does this computer stop playing — a pin that failed must not already have
/// silenced the computer and moved the sound somewhere nobody chose (the
/// desktop's routeToPhoneOutput, in the same order). Back to this computer,
/// any pin is let go of, so the phone's half of "computer and phone" plays
/// where the phone would play on its own.
pub fn pick(app: &tauri::AppHandle, serial: &str, choice: Choice, window: Arc<dyn Surface>) {
    let (app, serial) = (app.clone(), serial.to_string());
    std::thread::spawn(move || {
        let server = super::host::running(&serial);
        let (mode, pick) = match &choice {
            Choice::Computer => (Mode::Computer, None),
            Choice::Both => (Mode::Both, None),
            Choice::Phone(out) => (Mode::Phone, out.clone()),
        };
        if let Some(out) = &pick {
            let routed = match &server {
                Some(s) => s.route_audio(Some(out)),
                None => Err("the phone is not connected".into()),
            };
            if let Err(e) = routed {
                log::warn!("apps [{serial}] sound to the phone's {}: {e}", name(out));
                window.note(Some(format!("Couldn't play sound on {}", name(out))), false);
                return;
            }
        } else if mode != Mode::Phone {
            // Only a phone that pins has a pin to let go of.
            if let Some(s) = server.as_ref().filter(|s| s.sound().is_some_and(|p| p.can_pin)) {
                if let Err(e) = s.route_audio(None) {
                    log::info!("apps [{serial}] letting go of the phone's sound output: {e}");
                }
            }
        }
        let (forward, duplicate) = mode.keys();
        crate::scrcpy::set_audio_forwarding(&app, &serial, forward, duplicate);
        if let Some(s) = &server {
            s.tell_launcher_sound(mode, pick.as_ref());
        }
        let said = match (&pick, mode) {
            (Some(out), _) => name(out),
            (None, Mode::Both) => COMPUTER_AND_PHONE.into(),
            (None, Mode::Computer) => COMPUTER_ONLY.into(),
            (None, Mode::Phone) => PHONE_ONLY.into(),
        };
        log::info!("apps [{serial}] sound: {said}");
        window.note(Some(format!("Sound: {said}")), false);
    });
}

/// What the launcher is told, for its own record of the choice (AppsProvider's `media.mode`).
pub fn launcher_message(mode: Mode, pick: Option<&Output>) -> Value {
    let mut m = json!({"t": "audioMode", "mode": mode.word()});
    if let Some(out) = pick {
        m["pick"] = json!({"type": out.kind, "address": out.address, "name": out.product});
    }
    m
}

#[cfg(test)]
mod tests {
    use super::*;

    fn buds() -> Output {
        Output { kind: 8, address: "AA:BB".into(), product: "Galaxy Buds2".into(), builtin: false }
    }

    fn phone() -> PhoneSound {
        PhoneSound { outputs: vec![Output::speaker(), buds()], can_pin: true, pinned: None, playing: None }
    }

    fn checked(items: &[Item]) -> Vec<String> {
        items
            .iter()
            .filter_map(|i| match i {
                Item::Choice { label, checked: true, .. } => Some(label.clone()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn the_phones_message_reads_back() {
        let v = serde_json::json!({
            "t": "audio",
            "outputs": [
                {"type": 2, "address": "", "name": "", "builtin": true},
                {"type": 8, "address": "AA:BB", "name": " Galaxy Buds2 ", "builtin": false},
            ],
            "canPin": true,
            "pinned": {"type": 8, "address": "AA:BB"},
            "playing": null,
        });
        let s = PhoneSound::parse(&v);
        assert_eq!(s.outputs, vec![Output::speaker(), buds()]);
        assert!(s.can_pin);
        assert_eq!(s.pinned, Some((8, "AA:BB".into())));
        assert_eq!(s.playing, None);
        // A phone that says nothing of pins cannot be assumed to take one.
        assert!(!PhoneSound::parse(&serde_json::json!({"t": "audio", "outputs": []})).can_pin);
    }

    #[test]
    fn the_menu_is_the_desktops() {
        let items = menu(Mode::Computer, 34, Some(&phone()));
        let labels: Vec<String> = items
            .iter()
            .map(|i| match i {
                Item::Choice { label, .. } => label.clone(),
                Item::Separator => "—".into(),
            })
            .collect();
        assert_eq!(labels, ["Computer only", "Computer and phone", "—", "Phone speaker", "Galaxy Buds2"]);
        assert_eq!(checked(&items), ["Computer only"]);
    }

    #[test]
    fn on_the_phone_the_pin_is_checked_then_where_it_plays_then_the_speaker() {
        let mut p = phone();
        assert_eq!(checked(&menu(Mode::Phone, 34, Some(&p))), ["Phone speaker"]);
        p.playing = Some((8, "AA:BB".into()));
        assert_eq!(checked(&menu(Mode::Phone, 34, Some(&p))), ["Galaxy Buds2"]);
        p.pinned = Some((2, String::new()));
        assert_eq!(checked(&menu(Mode::Phone, 34, Some(&p))), ["Phone speaker"]);
        // The same earbuds under their other profile are the same earbuds.
        p.pinned = Some((26, "AA:BB".into()));
        assert_eq!(checked(&menu(Mode::Phone, 34, Some(&p))), ["Galaxy Buds2"]);
    }

    #[test]
    fn an_older_phone_says_what_it_cannot_do() {
        let items = menu(Mode::Both, 31, Some(&phone()));
        // Android 12 cannot play it in both places: it comes here alone, and says so.
        assert_eq!(checked(&items), ["Computer only"]);
        assert!(items.contains(&Item::Choice {
            label: "Computer and phone (needs Android 13)".into(),
            checked: false,
            enabled: false,
            choice: Choice::Both,
        }));
        // Android 10 sends no sound here at all: it plays on the phone.
        assert_eq!(checked(&menu(Mode::Computer, 29, Some(&phone()))), ["Phone speaker"]);
    }

    #[test]
    fn a_phone_that_cannot_pin_is_one_answer() {
        // Android 11 and 12 refuse the shell the pin: the phone plays where it would.
        let mut p = phone();
        p.can_pin = false;
        let items = menu(Mode::Phone, 31, Some(&p));
        assert_eq!(items.len(), 4);
        assert_eq!(checked(&items), ["Phone only"]);
        assert_eq!(
            items[3],
            Item::Choice { label: "Phone only".into(), checked: true, enabled: true, choice: Choice::Phone(None) }
        );
        // …and so is one that has said nothing yet.
        assert_eq!(checked(&menu(Mode::Phone, 0, None)), ["Phone only"]);
        assert_eq!(checked(&menu(Mode::Computer, 0, None)), ["Computer only"]);
    }

    #[test]
    fn a_phone_that_lists_nothing_still_has_a_speaker() {
        let p = PhoneSound { outputs: Vec::new(), can_pin: true, pinned: None, playing: None };
        assert_eq!(checked(&menu(Mode::Phone, 34, Some(&p))), ["Phone speaker"]);
    }

    #[test]
    fn an_output_without_a_name_is_called_what_it_is() {
        let wired = Output { kind: 4, address: String::new(), product: String::new(), builtin: false };
        assert_eq!(name(&wired), "Wired headphones");
        assert_eq!(name(&Output { kind: 22, ..wired.clone() }), "USB audio");
        assert_eq!(name(&Output { kind: 8, ..wired }), "Bluetooth device");
    }

    #[test]
    fn the_launcher_hears_the_choice_in_its_own_words() {
        assert_eq!(launcher_message(Mode::Both, None), serde_json::json!({"t": "audioMode", "mode": "both"}));
        assert_eq!(
            launcher_message(Mode::Phone, Some(&buds())),
            serde_json::json!({"t": "audioMode", "mode": "phone", "pick": {"type": 8, "address": "AA:BB", "name": "Galaxy Buds2"}})
        );
        assert_eq!(Mode::of(true, true), Mode::Both);
        assert_eq!(Mode::of(false, true), Mode::Phone);
        assert_eq!(Mode::Computer.keys(), (true, false));
    }
}
