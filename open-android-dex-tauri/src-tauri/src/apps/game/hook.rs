//! A debug build's way to play without hands (doc/game-mode.md, "Verified"):
//! `OADX_GAME_HOOK=<file>` makes the app follow that file, a line an event,
//! into the same code the system's keys, mouse and controllers reach. Not in
//! release builds.
//!
//! ```text
//! pad <slot> <26 hex>      a controller's state, as gilrs would have given it
//! pad <slot> off
//! toggle                   game controls, in the phone window in front
//! setup                    the editor, for the phone window in front
//! reconnect                its connection made again, the way it goes now
//! controller               More's "Use phone as game controller" there
//! key <Code> down|up
//! button left|right|middle|back|forward down|up
//! point <x> <y>            the free pointer, display pixels
//! move <dx> <dy>           the captured pointer, points
//! wheel up|down
//! ```
//!
//! "In front" is the key window; with `OADX_GAME_HOOK_ANY=1` the newest
//! phone window stands in, so a locked screen does not stop the phone's half
//! being checked.

use std::io::{Read, Seek, SeekFrom};
use std::sync::Arc;
use std::time::Duration;

use super::engine::{Event, Input, MouseButton, WheelWay};
use super::keys::ESCAPE;
use super::Session;
use crate::apps::gamepad::{self, PadState};

/// Follows the file named by `OADX_GAME_HOOK`, if it is set, from its end.
pub fn start() {
    let Ok(path) = std::env::var("OADX_GAME_HOOK") else { return };
    let _ = std::thread::Builder::new().name("game-hook".into()).spawn(move || {
        log::info!("game: following {path} for events (a debug build's hook)");
        let mut at = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        let mut rest = String::new();
        loop {
            std::thread::sleep(Duration::from_millis(40));
            let Ok(mut f) = std::fs::File::open(&path) else { continue };
            let len = f.metadata().map(|m| m.len()).unwrap_or(0);
            if len < at {
                at = 0; // the file was started again
            }
            if len == at || f.seek(SeekFrom::Start(at)).is_err() {
                continue;
            }
            let mut s = String::new();
            if f.read_to_string(&mut s).is_err() {
                continue;
            }
            at += s.len() as u64;
            rest.push_str(&s);
            while let Some(n) = rest.find('\n') {
                let line: String = rest.drain(..=n).collect();
                let line = line.trim();
                if !line.is_empty() {
                    if let Err(e) = run(line) {
                        log::warn!("game: hook: {line}: {e}");
                    } else {
                        log::info!("game: hook: {line}");
                    }
                }
            }
        }
    });
}

fn front() -> Option<Arc<Session>> {
    crate::apps::game_front(std::env::var("OADX_GAME_HOOK_ANY").as_deref() == Ok("1"))
}

fn run(line: &str) -> Result<(), String> {
    let w: Vec<&str> = line.split_whitespace().collect();
    let down = |s: Option<&&str>| match s.copied() {
        Some("down") => Ok(true),
        Some("up") => Ok(false),
        _ => Err("down or up?".to_string()),
    };
    match w.first().copied() {
        Some("pad") => {
            let slot: u8 = w.get(1).and_then(|s| s.parse().ok()).ok_or("which slot?")?;
            match w.get(2).copied() {
                Some("off") => gamepad::feed(slot, None),
                Some(hex) if hex.len() == 26 => {
                    let mut r = [0u8; 13];
                    for (i, b) in r.iter_mut().enumerate() {
                        *b = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).map_err(|e| e.to_string())?;
                    }
                    gamepad::feed(slot, Some(PadState::from_report(&r)));
                }
                _ => return Err("26 hex digits or off".into()),
            }
            Ok(())
        }
        Some("toggle") => {
            front().ok_or("no phone window in front")?.toggle();
            Ok(())
        }
        Some("setup") => {
            front().ok_or("no phone window in front")?.set_up();
            Ok(())
        }
        Some("reconnect") => crate::apps::reconnect_front(std::env::var("OADX_GAME_HOOK_ANY").as_deref() == Ok("1")),
        Some("controller") => crate::apps::controller_front(std::env::var("OADX_GAME_HOOK_ANY").as_deref() == Ok("1")),
        Some("key") => {
            let s = front().ok_or("no phone window in front")?;
            let input = Input::named(w.get(1).ok_or("which key?")?).ok_or("no such key")?;
            let down = down(w.get(2))?;
            if input == Input::Key(ESCAPE) && s.is_on() && s.captured() {
                s.event(Event::Input { input, down });
                return Ok(());
            }
            if !s.takes(input) {
                return Err("not the controls' (they are off, or it has no control): it would go to the app".into());
            }
            s.event(Event::Input { input, down });
            Ok(())
        }
        Some("button") => {
            let s = front().ok_or("no phone window in front")?;
            let b = match w.get(1).copied() {
                Some("left") => MouseButton::Left,
                Some("right") => MouseButton::Right,
                Some("middle") => MouseButton::Middle,
                Some("back") => MouseButton::Back,
                Some("forward") => MouseButton::Forward,
                _ => return Err("which button?".into()),
            };
            let input = Input::Mouse(b);
            if !s.is_on() {
                return Err("the controls are off".into());
            }
            // As the windows do: a left click is always the engine's; another button only if a control has it.
            if b == MouseButton::Left || s.captured() || s.takes(input) {
                s.event(Event::Input { input, down: down(w.get(2))? });
            }
            Ok(())
        }
        Some("point") => {
            let s = front().ok_or("no phone window in front")?;
            let x = w.get(1).and_then(|v| v.parse().ok()).ok_or("x?")?;
            let y = w.get(2).and_then(|v| v.parse().ok()).ok_or("y?")?;
            s.event(Event::Pointer { x, y });
            Ok(())
        }
        Some("move") => {
            let s = front().ok_or("no phone window in front")?;
            let dx = w.get(1).and_then(|v| v.parse().ok()).ok_or("dx?")?;
            let dy = w.get(2).and_then(|v| v.parse().ok()).ok_or("dy?")?;
            s.event(Event::Delta { dx, dy });
            Ok(())
        }
        Some("wheel") => {
            let s = front().ok_or("no phone window in front")?;
            let input = Input::Wheel(match w.get(1).copied() {
                Some("up") => WheelWay::Up,
                Some("down") => WheelWay::Down,
                _ => return Err("up or down?".into()),
            });
            if s.takes(input) {
                s.event(Event::Input { input, down: true });
                s.event(Event::Input { input, down: false });
            }
            Ok(())
        }
        _ => Err("not a line the hook knows".into()),
    }
}
