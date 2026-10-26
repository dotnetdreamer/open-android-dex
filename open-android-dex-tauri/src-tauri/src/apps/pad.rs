//! The phone's own screen as the touchpad of its apps' windows here
//! (doc/phone-touchpad.md, "The computer's windows").
//!
//! The pad is the launcher's, on the phone. What its fingers do comes over the
//! phone's app server's control connection as `pad` messages and lands here:
//! this computer's own pointer moves, and what it presses, scrolls and drags
//! goes to the phone's window under it — to those windows alone
//! (`native::pad`), so a phone never clicks in anything else here.
//!
//! Offered while a phone has a window here: its server is asked (`padOn`) as
//! the first of its windows shows its app — or shows it again on another
//! server — and told to stop (`padOff`) as the last closes. More's "Use phone
//! as touchpad", in any of its windows, puts the pad up on the phone at once.
//!
//! Android keeps every app's overlay, the pad too, off the phone's screen while
//! Settings or a screen like it shows anywhere — as likely as not in a window
//! here. The pad says so (`hidden`, Android 15 and later), and so do the
//! phone's windows, for a moment, and again when the pad is back.

use std::collections::{HashMap, HashSet};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde_json::Value;

use super::host;
use super::link::Surface;

/// What the pad did, for the phone's window under the pointer.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Input {
    /// The pointer moved this far, in this computer's points.
    Move { dx: f64, dy: f64 },
    /// Exactly these buttons held, as MotionEvent.BUTTON_* bits.
    Buttons(u8),
    /// The wheel, in notches: up and right are positive.
    Wheel { h: f64, v: f64 },
    /// Back, for the window the pointer is with.
    Back,
    /// The pad went: whatever it held is let go.
    Gone,
}

/// A three-finger gesture's window action.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Act {
    Next,
    Previous,
    Maximize,
}

/// The farthest one message moves the pointer, in points: a slow link adds
/// moves up, but never to more than a screen or two.
const MOVE_MOST: f64 = 4000.0;
/// The most notches in one message: a flick sends a few, never this many.
const WHEEL_MOST: f64 = 100.0;
/// Android's KEYCODE_BACK: the one key the pad sends.
const BACK: i64 = 4;
/// How long the phone has to put the pad up: it may start the launcher's process for it.
const OPEN_WAIT: Duration = Duration::from_secs(15);
/// What the phone's windows say as Android takes the pad off the phone's screen, and puts it back.
const HIDDEN_NOTE: &str = "Your phone hides the touchpad while Settings or a similar screen is open";
const BACK_NOTE: &str = "The touchpad is back on your phone's screen";

/// Which phones it is offered for, by phone: the server it was offered on.
fn offered() -> &'static Mutex<HashMap<String, String>> {
    static O: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    O.get_or_init(Default::default)
}

/// The phones whose pad Android keeps off their screen now, as their pads said.
fn kept_off() -> &'static Mutex<HashSet<String>> {
    static K: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
    K.get_or_init(Default::default)
}

/// A window's More waiting for its phone to put the pad up, by phone: the phone's answer goes here.
fn waiting() -> &'static Mutex<HashMap<String, Sender<Value>>> {
    static W: OnceLock<Mutex<HashMap<String, Sender<Value>>>> = OnceLock::new();
    W.get_or_init(Default::default)
}

/// One of the pad's messages, as the computer acts on it; None for one it does not.
pub fn input(v: &Value) -> Option<Input> {
    let num = |k: &str| v[k].as_f64().filter(|x| x.is_finite());
    Some(match v["k"].as_str()? {
        "move" => Input::Move {
            dx: num("dx")?.clamp(-MOVE_MOST, MOVE_MOST),
            dy: num("dy")?.clamp(-MOVE_MOST, MOVE_MOST),
        },
        "buttons" => Input::Buttons((v["b"].as_u64()? & 0x7) as u8),
        "wheel" => Input::Wheel {
            h: num("h")?.clamp(-WHEEL_MOST, WHEEL_MOST),
            v: num("v")?.clamp(-WHEEL_MOST, WHEEL_MOST),
        },
        "key" if v["code"].as_i64() == Some(BACK) => Input::Back,
        "closed" => Input::Gone,
        _ => return None,
    })
}

/// A gesture's window action, as the computer carries it out; None for one it does not.
pub fn act(v: &Value) -> Option<Act> {
    match (v["k"].as_str()?, v["action"].as_str()?) {
        ("act", "nextwindow") => Some(Act::Next),
        ("act", "prevwindow") => Some(Act::Previous),
        ("act", "maximize") => Some(Act::Maximize),
        _ => None,
    }
}

/// What the phone's server said of its pad (apps/host.rs): what the fingers
/// did, or how asking for the pad went.
pub(crate) fn said(serial: &str, phone: &str, v: &Value) {
    match v["t"].as_str() {
        Some("padOn") => answered(serial, phone, v),
        Some("pad") => {
            // Only while it is offered: a server reached again that had a pad
            // up for a computer that has since withdrawn it is not acted on.
            if !offered().lock().unwrap().contains_key(phone) {
                return;
            }
            match v["k"].as_str() {
                Some("open") => log::info!("apps [{serial}] the phone's touchpad is up, for the windows here"),
                Some("closed") => {
                    log::info!("apps [{serial}] the phone's touchpad closed");
                    kept_off().lock().unwrap().remove(phone);
                }
                Some("hidden") => hidden(serial, phone, v["on"].as_bool() == Some(true)),
                _ => {}
            }
            #[cfg(any(windows, target_os = "macos"))]
            {
                if let Some(i) = input(v) {
                    super::native::pad(i);
                } else if let Some(a) = act(v) {
                    super::window_act(a);
                }
            }
        }
        _ => {}
    }
}

/// The pad says Android keeps it off the phone's screen, or that it is back:
/// the phone's windows say so, for as long as any note stays — not for as
/// long as Settings is open, over the top of every one of them.
fn hidden(serial: &str, phone: &str, on: bool) {
    let changed = {
        let mut off = kept_off().lock().unwrap();
        if on {
            off.insert(phone.to_string())
        } else {
            off.remove(phone)
        }
    };
    if !changed {
        return;
    }
    log::info!(
        "apps [{serial}] the phone's touchpad is {}",
        if on { "kept off its screen: Settings, or a screen like it, is showing" } else { "on its screen again" }
    );
    #[cfg(any(windows, target_os = "macos"))]
    super::note_phone(phone, Some(if on { HIDDEN_NOTE } else { BACK_NOTE }.to_string()), false);
}

fn answered(serial: &str, phone: &str, v: &Value) {
    let ok = v["ok"].as_bool().unwrap_or(false);
    let open = v["open"].as_bool().unwrap_or(false);
    let message = v["message"].as_str().unwrap_or("");
    if ok {
        log::info!(
            "apps [{serial}] the phone's touchpad {}{}",
            if open { "is up on the phone" } else { "is offered" },
            if v["offered"].as_bool() == Some(true) { ", in its notifications too" } else { "" }
        );
    } else {
        log::info!("apps [{serial}] the phone's touchpad could not be {}: {message}", if open { "opened" } else { "offered" });
    }
    if open {
        if let Some(tx) = waiting().lock().unwrap().remove(phone) {
            let _ = tx.send(v.clone());
        }
    }
}

/// A window of `phone` shows its app, over `serial`: the pad offered there,
/// unless it already is on that phone's server.
pub(crate) fn showing(serial: &str, phone: &str) {
    let Some(server) = host::of_phone(phone).or_else(|| host::running(serial)) else { return };
    {
        let mut on = offered().lock().unwrap();
        if on.get(phone) == Some(&server.socket) {
            return;
        }
        on.insert(phone.to_string(), server.socket.clone());
    }
    if let Err(e) = server.pad_on(false) {
        log::warn!("apps [{serial}] the phone's touchpad was not offered: {e}");
    }
}

/// The last window of `phone` here has closed: the pad no longer offered.
pub(crate) fn closed(phone: &str) {
    if offered().lock().unwrap().remove(phone).is_none() {
        return;
    }
    waiting().lock().unwrap().remove(phone);
    kept_off().lock().unwrap().remove(phone);
    if let Some(server) = host::of_phone(phone) {
        let _ = server.pad_off();
    }
    #[cfg(any(windows, target_os = "macos"))]
    super::native::pad(Input::Gone);
}

/// More's "Use phone as touchpad", in a window of `phone`: the pad up on the
/// phone now — this build's launcher put there first, if the phone has none
/// that can. `window` says how it went.
pub(crate) fn open_now(app: &tauri::AppHandle, serial: &str, phone: &str, window: Arc<dyn Surface>) {
    let (app, serial, phone) = (app.clone(), serial.to_string(), phone.to_string());
    std::thread::spawn(move || {
        let Some(server) = host::of_phone(&phone).or_else(|| host::running(&serial)) else {
            window.note(Some("The phone isn't connected".into()), false);
            return;
        };
        offered().lock().unwrap().insert(phone.clone(), server.socket.clone());
        window.note(Some("Opening the touchpad on your phone…".into()), true);
        let mut said = ask_open(&server, &phone);
        // A phone without this app's launcher, or with one from before the
        // pad, is given this build of it — unless its Linux is running, which
        // an install would end — and asked again once it has it.
        if said.as_ref().is_ok_and(|r| r["ok"].as_bool() != Some(true) && r["message"].as_str() != Some("timed out")) {
            window.note(Some("Setting up the touchpad on your phone…".into()), true);
            said = crate::adb::ensure_launcher(&app, &serial).and_then(|()| ask_open(&server, &phone));
        }
        let note = match said {
            Ok(r) if r["ok"].as_bool() == Some(true) => "The touchpad is on your phone's screen".to_string(),
            Ok(r) => {
                log::warn!("apps [{serial}] the phone's touchpad: {}", r["message"].as_str().unwrap_or("refused"));
                "The touchpad couldn't be opened on your phone".to_string()
            }
            Err(e) => {
                log::warn!("apps [{serial}] the phone's touchpad: {e}");
                e
            }
        };
        window.note(Some(note), false);
    });
}

/// The pad asked to open on the phone, and its answer, or "timed out".
fn ask_open(server: &host::Server, phone: &str) -> Result<Value, String> {
    let (tx, rx) = mpsc::channel();
    waiting().lock().unwrap().insert(phone.to_string(), tx);
    server.pad_on(true)?;
    let answer = rx.recv_timeout(OPEN_WAIT);
    waiting().lock().unwrap().remove(phone);
    Ok(answer.unwrap_or_else(|_| serde_json::json!({"ok": false, "message": "timed out"})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn the_pads_messages_are_read_as_what_they_did() {
        assert_eq!(input(&json!({"t": "pad", "k": "move", "dx": 3.5, "dy": -2})), Some(Input::Move { dx: 3.5, dy: -2.0 }));
        assert_eq!(input(&json!({"k": "buttons", "b": 1})), Some(Input::Buttons(1)));
        assert_eq!(input(&json!({"k": "wheel", "h": 0, "v": 1.25})), Some(Input::Wheel { h: 0.0, v: 1.25 }));
        assert_eq!(input(&json!({"k": "key", "code": 4})), Some(Input::Back));
        assert_eq!(input(&json!({"k": "closed"})), Some(Input::Gone));
        assert_eq!(act(&json!({"k": "act", "action": "nextwindow"})), Some(Act::Next));
        assert_eq!(act(&json!({"k": "act", "action": "maximize"})), Some(Act::Maximize));
    }

    #[test]
    fn nothing_but_what_a_pad_does_is_taken() {
        // Back is the one key: no other code reaches a window from the pad.
        assert_eq!(input(&json!({"k": "key", "code": 66})), None);
        // Buttons beyond the three a mouse has are not pressed.
        assert_eq!(input(&json!({"k": "buttons", "b": 0xff})), Some(Input::Buttons(7)));
        // A move or a wheel that is not a number, or is an absurd one, goes no farther than the bounds.
        assert_eq!(input(&json!({"k": "move", "dx": "x", "dy": 1})), None);
        assert_eq!(input(&json!({"k": "move", "dx": 1e12, "dy": -1e12})), Some(Input::Move { dx: MOVE_MOST, dy: -MOVE_MOST }));
        assert_eq!(input(&json!({"k": "wheel", "h": 0, "v": 1e9})), Some(Input::Wheel { h: 0.0, v: WHEEL_MOST }));
        assert_eq!(act(&json!({"k": "act", "action": "home"})), None);
        assert_eq!(input(&json!({"k": "open"})), None);
    }
}
