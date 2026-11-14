//! The phone's own screen as a game controller for its apps' windows here
//! (doc/game-mode.md, "The phone as a controller").
//!
//! The controller is the launcher's, on the phone, and so is all it does:
//! what its fingers hold goes to the phone's app server over the phone's own
//! loopback and becomes a gamepad there, for the window in front here —
//! nothing comes to this computer and back, so the cable or Wi‑Fi adds nothing
//! to a press. This computer offers it, and says which window is in front (the
//! windows' `focus` and `blur`, as ever).
//!
//! Offered while a phone has a window here, as the touchpad is (apps/pad.rs):
//! its server is asked (`controllerOn`) as the first of its windows shows its
//! app — or shows it again on another server — and told to stop
//! (`controllerOff`) as the last closes. Its notification in the phone's shade
//! is up while game controls are on in one of that phone's windows — game
//! mode — and down as the last of them turns them off (`notice`). More's "Use
//! phone as game controller", in any of the phone's windows, puts it up at once.

use std::collections::{HashMap, HashSet};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde_json::Value;

use super::host;
use super::link::Surface;

/// How long the phone has to put the controller up: it may start the launcher's process for it.
const OPEN_WAIT: Duration = Duration::from_secs(15);
/// What the phone's windows say as Android takes the controller off the phone's screen, and puts it back.
const HIDDEN_NOTE: &str = "Your phone hides the game controller while Settings or a similar screen is open";
const BACK_NOTE: &str = "The game controller is back on your phone's screen";

/// What is known per phone, by phone.
#[derive(Default)]
struct Phones {
    /// The server the controller is offered on.
    offered: HashMap<String, String>,
    /// The windows with game controls on, and their phone.
    game_on: HashMap<u64, String>,
    /// Whether the phone was last told to have the notification up.
    notice: HashMap<String, bool>,
    /// The phones whose controller Android keeps off their screen now.
    kept_off: HashSet<String>,
}

fn phones() -> &'static Mutex<Phones> {
    static P: OnceLock<Mutex<Phones>> = OnceLock::new();
    P.get_or_init(Default::default)
}

/// A window's More waiting for its phone to put the controller up, by phone: the phone's answer goes here.
fn waiting() -> &'static Mutex<HashMap<String, Sender<Value>>> {
    static W: OnceLock<Mutex<HashMap<String, Sender<Value>>>> = OnceLock::new();
    W.get_or_init(Default::default)
}

impl Phones {
    /// Whether game controls are on in any of `phone`'s windows: game mode.
    fn game_mode(&self, phone: &str) -> bool {
        self.game_on.values().any(|p| p == phone)
    }
}

/// What the phone's server said of its controller (apps/host.rs): how
/// offering or opening it went, or what it did.
pub(crate) fn said(serial: &str, phone: &str, v: &Value) {
    match v["t"].as_str() {
        Some("controllerOn") => answered(serial, phone, v),
        Some("controller") => {
            if !phones().lock().unwrap().offered.contains_key(phone) {
                return;
            }
            match v["k"].as_str() {
                Some("open") => log::info!("apps [{serial}] the phone's game controller is up, for the windows here"),
                Some("closed") => {
                    log::info!("apps [{serial}] the phone's game controller closed");
                    phones().lock().unwrap().kept_off.remove(phone);
                }
                Some("hidden") => hidden(serial, phone, v["on"].as_bool() == Some(true)),
                _ => {}
            }
        }
        _ => {}
    }
}

/// The controller says Android keeps it off the phone's screen, or that it is
/// back: the phone's windows say so, for as long as a note stays.
fn hidden(serial: &str, phone: &str, on: bool) {
    let changed = {
        let mut p = phones().lock().unwrap();
        if on {
            p.kept_off.insert(phone.to_string())
        } else {
            p.kept_off.remove(phone)
        }
    };
    if !changed {
        return;
    }
    log::info!(
        "apps [{serial}] the phone's game controller is {}",
        if on { "kept off its screen: Settings, or a screen like it, is showing" } else { "on its screen again" }
    );
    #[cfg(any(windows, target_os = "macos"))]
    super::note_phone(phone, Some(if on { HIDDEN_NOTE } else { BACK_NOTE }.to_string()), false);
}

fn answered(serial: &str, phone: &str, v: &Value) {
    let ok = v["ok"].as_bool().unwrap_or(false);
    let open = v["open"].as_bool().unwrap_or(false);
    let notice = v["notice"].as_bool().unwrap_or(false);
    let message = v["message"].as_str().unwrap_or("");
    if ok {
        log::info!(
            "apps [{serial}] the phone's game controller {}{}",
            if open { "is up on the phone" } else { "is offered" },
            match (notice, v["offered"].as_bool() == Some(true)) {
                (true, true) => ", in its notifications too: game mode is on",
                (true, false) => "; game mode is on, but the phone may not show this app's notifications",
                _ => "",
            }
        );
    } else {
        log::info!("apps [{serial}] the phone's game controller could not be {}: {message}", if open { "opened" } else { "offered" });
    }
    if open {
        if let Some(tx) = waiting().lock().unwrap().remove(phone) {
            let _ = tx.send(v.clone());
        }
    }
}

/// A window of `phone` shows its app, over `serial`: the controller offered
/// there, unless it already is on that phone's server.
pub(crate) fn showing(serial: &str, phone: &str) {
    let Some(server) = host::of_phone(phone).or_else(|| host::running(serial)) else { return };
    let notice = {
        let mut p = phones().lock().unwrap();
        if p.offered.get(phone) == Some(&server.socket) {
            return;
        }
        p.offered.insert(phone.to_string(), server.socket.clone());
        let notice = p.game_mode(phone);
        p.notice.insert(phone.to_string(), notice);
        notice
    };
    if let Err(e) = server.controller_on(false, notice) {
        log::warn!("apps [{serial}] the phone's game controller was not offered: {e}");
    }
}

/// The last window of `phone` here has closed: the controller no longer offered.
pub(crate) fn closed(phone: &str) {
    {
        let mut p = phones().lock().unwrap();
        p.game_on.retain(|_, of| of != phone);
        p.notice.remove(phone);
        p.kept_off.remove(phone);
        if p.offered.remove(phone).is_none() {
            return;
        }
    }
    waiting().lock().unwrap().remove(phone);
    if let Some(server) = host::of_phone(phone) {
        let _ = server.controller_off();
    }
}

/// Game controls came on or went off in window `id` of `phone` (apps/game):
/// the phone's notification follows whether any of its windows has them on.
pub(crate) fn game_controls(id: u64, phone: Option<String>, on: bool) {
    let (phone, notice) = {
        let mut p = phones().lock().unwrap();
        let phone = if on {
            match phone {
                Some(phone) => {
                    p.game_on.insert(id, phone.clone());
                    phone
                }
                None => return,
            }
        } else {
            match p.game_on.remove(&id) {
                Some(phone) => phone,
                None => return,
            }
        };
        let notice = p.game_mode(&phone);
        if !p.offered.contains_key(&phone) || p.notice.get(&phone) == Some(&notice) {
            // Not offered yet, and offered with it as it is now; or nothing changes.
            return;
        }
        p.notice.insert(phone.clone(), notice);
        (phone, notice)
    };
    let Some(server) = host::of_phone(&phone) else { return };
    log::info!(
        "apps [{phone}] game mode {}: the phone's game controller notification {}",
        if notice { "on" } else { "off" },
        if notice { "up" } else { "down" }
    );
    if let Err(e) = server.controller_on(false, notice) {
        log::warn!("apps [{phone}] the phone's game controller's notification: {e}");
    }
}

/// More's "Use phone as game controller", in a window of `phone`: the
/// controller up on the phone now — this build's launcher put there first,
/// if the phone has none that can. `window` says how it went.
pub(crate) fn open_now(app: &tauri::AppHandle, serial: &str, phone: &str, window: Arc<dyn Surface>) {
    let (app, serial, phone) = (app.clone(), serial.to_string(), phone.to_string());
    std::thread::spawn(move || {
        let Some(server) = host::of_phone(&phone).or_else(|| host::running(&serial)) else {
            window.note(Some("The phone isn't connected".into()), false);
            return;
        };
        let notice = {
            let mut p = phones().lock().unwrap();
            p.offered.insert(phone.clone(), server.socket.clone());
            let notice = p.game_mode(&phone);
            p.notice.insert(phone.clone(), notice);
            notice
        };
        window.note(Some("Opening the game controller on your phone…".into()), true);
        let mut said = ask_open(&server, &phone, notice);
        // A phone without this app's launcher, or with one from before the
        // controller, is given this build of it — unless its Linux is running,
        // which an install would end — and asked again once it has it.
        if said.as_ref().is_ok_and(|r| r["ok"].as_bool() != Some(true) && r["message"].as_str() != Some("timed out")) {
            window.note(Some("Setting up the game controller on your phone…".into()), true);
            said = crate::adb::ensure_launcher_for_controller(&app, &serial).and_then(|()| ask_open(&server, &phone, notice));
        }
        let note = match said {
            Ok(r) if r["ok"].as_bool() == Some(true) => "The game controller is on your phone's screen".to_string(),
            Ok(r) => {
                log::warn!("apps [{serial}] the phone's game controller: {}", r["message"].as_str().unwrap_or("refused"));
                "The game controller couldn't be opened on your phone".to_string()
            }
            Err(e) => {
                log::warn!("apps [{serial}] the phone's game controller: {e}");
                e
            }
        };
        window.note(Some(note), false);
    });
}

/// The controller asked to open on the phone, and its answer, or "timed out".
fn ask_open(server: &host::Server, phone: &str, notice: bool) -> Result<Value, String> {
    let (tx, rx) = mpsc::channel();
    waiting().lock().unwrap().insert(phone.to_string(), tx);
    server.controller_on(true, notice)?;
    let answer = rx.recv_timeout(OPEN_WAIT);
    waiting().lock().unwrap().remove(phone);
    Ok(answer.unwrap_or_else(|_| serde_json::json!({"ok": false, "message": "timed out"})))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One test at a time: they share the module's state.
    fn fresh() -> std::sync::MutexGuard<'static, ()> {
        static ONE: Mutex<()> = Mutex::new(());
        let guard = ONE.lock().unwrap_or_else(|e| e.into_inner());
        *phones().lock().unwrap() = Phones::default();
        guard
    }

    #[test]
    fn game_mode_is_any_window_of_the_phone_with_controls_on() {
        let _one = fresh();
        // Not offered on any server: remembered, nothing sent.
        game_controls(1, Some("phone-a".into()), true);
        game_controls(2, Some("phone-a".into()), true);
        game_controls(3, Some("phone-b".into()), true);
        {
            let p = phones().lock().unwrap();
            assert!(p.game_mode("phone-a"));
            assert!(p.game_mode("phone-b"));
            assert!(!p.game_mode("phone-c"));
        }
        // One of phone-a's two off: still game mode there.
        game_controls(1, None, false);
        assert!(phones().lock().unwrap().game_mode("phone-a"));
        game_controls(2, None, false);
        assert!(!phones().lock().unwrap().game_mode("phone-a"));
        // A window never on, said off, changes nothing.
        game_controls(9, None, false);
        assert!(phones().lock().unwrap().game_mode("phone-b"));
        // On with no phone known: nothing.
        game_controls(4, None, true);
        assert!(!phones().lock().unwrap().game_on.contains_key(&4));
    }

    #[test]
    fn the_last_window_closing_forgets_the_phone() {
        let _one = fresh();
        game_controls(1, Some("phone-a".into()), true);
        game_controls(2, Some("phone-b".into()), true);
        phones().lock().unwrap().offered.insert("phone-a".into(), "oadx-apps-x".into());
        closed("phone-a");
        let p = phones().lock().unwrap();
        assert!(!p.game_mode("phone-a"));
        assert!(!p.offered.contains_key("phone-a"));
        assert!(p.game_mode("phone-b"));
    }
}
