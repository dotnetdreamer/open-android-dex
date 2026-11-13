//! Game controllers on this computer, as gamepads on the phone (doc/game-mode.md,
//! "A controller is the phone's own").
//!
//! The phone's app server makes an Xbox 360 pad on `/dev/uhid` for each
//! controller and ties it to the display of the window that sends it
//! (`AppGamepads.java`). This side reads the controllers and sends each one's
//! whole state, as the pad's 13-byte input report, on the connection of the
//! phone window in front — and nothing while no phone window is.
//!
//! The state and its report build everywhere; reading controllers is the
//! reader's, below, on Windows and Mac only (`gilrs`).

/// The report's button bits, as the Xbox 360 key layout names them (doc/game-mode.md).
pub mod button {
    pub const A: u16 = 0x0001;
    pub const B: u16 = 0x0002;
    pub const X: u16 = 0x0008;
    pub const Y: u16 = 0x0010;
    pub const L1: u16 = 0x0040;
    pub const R1: u16 = 0x0080;
    pub const BACK: u16 = 0x0400;
    pub const START: u16 = 0x0800;
    pub const GUIDE: u16 = 0x1000;
    pub const L3: u16 = 0x2000;
    pub const R3: u16 = 0x4000;
}

/// One controller, as the phone's pad reports it.
#[derive(Clone, Copy, PartialEq, Eq, Default, Debug)]
pub struct PadState {
    pub buttons: u16,
    /// 0 centred, 1 up, then clockwise to 8 up-left.
    pub hat: u8,
    /// Sticks: right and down positive.
    pub lx: i16,
    pub ly: i16,
    pub rx: i16,
    pub ry: i16,
    /// Triggers, 0 to 255.
    pub lt: u8,
    pub rt: u8,
}

impl PadState {
    /// The pad's input report: buttons u16, hat u8, four i16 sticks, two u8 triggers, little-endian.
    pub fn report(&self) -> [u8; 13] {
        let mut r = [0u8; 13];
        r[0..2].copy_from_slice(&(self.buttons & 0x7fff).to_le_bytes());
        r[2] = self.hat & 0x0f;
        r[3..5].copy_from_slice(&self.lx.to_le_bytes());
        r[5..7].copy_from_slice(&self.ly.to_le_bytes());
        r[7..9].copy_from_slice(&self.rx.to_le_bytes());
        r[9..11].copy_from_slice(&self.ry.to_le_bytes());
        r[11] = self.lt;
        r[12] = self.rt;
        r
    }

    /// A report read back (the debug hook's `pad` lines are reports).
    pub fn from_report(r: &[u8; 13]) -> PadState {
        PadState {
            buttons: u16::from_le_bytes([r[0], r[1]]),
            hat: r[2] & 0x0f,
            lx: i16::from_le_bytes([r[3], r[4]]),
            ly: i16::from_le_bytes([r[5], r[6]]),
            rx: i16::from_le_bytes([r[7], r[8]]),
            ry: i16::from_le_bytes([r[9], r[10]]),
            lt: r[11],
            rt: r[12],
        }
    }
}

/// The hat from the four d-pad buttons: opposites cancel.
pub fn hat(up: bool, right: bool, down: bool, left: bool) -> u8 {
    let v = (down as i8) - (up as i8);
    let h = (right as i8) - (left as i8);
    match (v, h) {
        (-1, 0) => 1,
        (-1, 1) => 2,
        (0, 1) => 3,
        (1, 1) => 4,
        (1, 0) => 5,
        (1, -1) => 6,
        (0, -1) => 7,
        (-1, -1) => 8,
        _ => 0,
    }
}

/// A stick axis from -1..1 to the report's i16; `invert` for gilrs' Y, which counts up as positive.
pub fn axis(v: f32, invert: bool) -> i16 {
    let v = if invert { -v } else { v };
    (v.clamp(-1.0, 1.0) * 32767.0).round() as i16
}

/// A trigger from 0..1 to 0..255.
pub fn trigger(v: f32) -> u8 {
    (v.clamp(0.0, 1.0) * 255.0).round() as u8
}

/// A stick this far out of 32767 is being moved, not resting (about a quarter of the way).
const AWAKE_STICK: u16 = 8000;
/// A trigger this far out of 255 is being pulled.
const AWAKE_TRIGGER: u8 = 32;

/// Whether `s` is a person using the controller: a button, the d-pad, a
/// trigger pulled or a stick past the dead zone. A controller that has given
/// none of these since it connected is sent nowhere, so no pad is made on the
/// phone for one that is only plugged in — nor for a device that only looks
/// like one.
pub fn wakes(s: &PadState) -> bool {
    s.buttons != 0
        || s.hat != 0
        || s.lt > AWAKE_TRIGGER
        || s.rt > AWAKE_TRIGGER
        || [s.lx, s.ly, s.rx, s.ry].iter().any(|v| v.unsigned_abs() > AWAKE_STICK)
}

/// Controllers at once: the phone's server makes no more pads than this.
pub const SLOTS: usize = 4;

/// Which controller is in which slot, in the order they connected, and those
/// that connected while every slot was in use, waiting in that order: a slot
/// let go of is the first waiting one's — a fifth controller is used as soon
/// as one of the four goes, not only once it is connected again.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub(crate) struct Seats<T> {
    pub slots: [Option<T>; SLOTS],
    waiting: Vec<T>,
}

#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
impl<T: Copy + PartialEq> Seats<T> {
    pub fn new() -> Seats<T> {
        Seats { slots: [None; SLOTS], waiting: Vec::new() }
    }

    /// Whether `id` has a slot or waits for one.
    pub fn has(&self, id: T) -> bool {
        self.slots.contains(&Some(id)) || self.waiting.contains(&id)
    }

    /// `id` connected: its slot, or None while it waits for one.
    pub fn connect(&mut self, id: T) -> Option<usize> {
        if let Some(i) = self.slots.iter().position(|s| *s == Some(id)) {
            return Some(i);
        }
        if self.waiting.contains(&id) {
            return None;
        }
        match self.slots.iter().position(|s| s.is_none()) {
            Some(i) => {
                self.slots[i] = Some(id);
                Some(i)
            }
            None => {
                self.waiting.push(id);
                None
            }
        }
    }

    /// `id` gone: the slot it let go of, if it had one, and the waiting controller that has it now.
    pub fn disconnect(&mut self, id: T) -> Option<(usize, Option<T>)> {
        self.waiting.retain(|w| *w != id);
        let i = self.slots.iter().position(|s| *s == Some(id))?;
        self.slots[i] = (!self.waiting.is_empty()).then(|| self.waiting.remove(0));
        Some((i, self.slots[i]))
    }
}

// ── the controllers, and who hears them (Windows and Mac) ──

#[cfg(any(windows, target_os = "macos"))]
mod live {
    use super::{PadState, SLOTS};
    use std::collections::HashSet;
    use std::sync::{Condvar, Mutex, OnceLock};
    use std::time::Duration;

    /// The controllers' states now, by slot: the reader's and the debug hook's.
    pub struct Table {
        pub slots: [Option<PadState>; SLOTS],
        pub changed: bool,
    }

    pub fn table() -> &'static (Mutex<Table>, Condvar) {
        static T: OnceLock<(Mutex<Table>, Condvar)> = OnceLock::new();
        T.get_or_init(|| (Mutex::new(Table { slots: [None; SLOTS], changed: false }), Condvar::new()))
    }

    /// What has gone to the phone: per slot, the window and the state it was sent.
    #[derive(Default)]
    pub struct Sent {
        pub slots: [Option<(u64, PadState)>; SLOTS],
        /// A pad was made for the slot on a phone, and has not been told to go.
        pub made: [bool; SLOTS],
        /// The window in front, and which of its connections: a new one starts
        /// with the pads let go on the phone, so what is held is sent on it again.
        pub front: Option<(u64, u64)>,
        /// The window the slot's controller was last used in (`goes_to`).
        pub awake: [Option<u64>; SLOTS],
        /// Windows that have said "Game controller connected." in this run.
        pub noted: HashSet<u64>,
    }

    /// How long a pass waits for news before it looks again: the window in front may change meanwhile.
    pub const LOOK_EVERY: Duration = Duration::from_millis(50);
    /// …and while no phone window is open, when the controllers are read but not sent.
    pub const IDLE_EVERY: Duration = Duration::from_millis(500);
    /// A reader that could not be made, or whose source went, is tried again this much later.
    pub const RETRY_EVERY: Duration = Duration::from_secs(60);
    /// A read that comes back with nothing this soon, this many times running, is a
    /// reader whose source has gone (a Mac's HID manager that would not open).
    pub const DEAD_SOON: Duration = Duration::from_millis(2);
    pub const DEAD_AFTER: u32 = 3;
}

/// A controller's state put in as if the reader had read it; None, it went.
/// The debug hook's way in, and the reader's.
#[cfg(any(windows, target_os = "macos"))]
pub(crate) fn feed(slot: u8, state: Option<PadState>) {
    if slot as usize >= SLOTS {
        return;
    }
    start();
    let (t, bell) = live::table();
    let mut t = t.lock().unwrap();
    t.slots[slot as usize] = state;
    t.changed = true;
    bell.notify_all();
}

#[cfg(not(any(windows, target_os = "macos")))]
pub(crate) fn feed(_slot: u8, _state: Option<PadState>) {}

/// The controllers' thread, once: started with the first phone window.
#[cfg(any(windows, target_os = "macos"))]
pub fn start() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let _ = std::thread::Builder::new().name("apps-gamepad".into()).spawn(run);
    });
}

#[cfg(any(windows, target_os = "macos"))]
fn run() {
    use std::time::Instant;
    let mut sent = live::Sent::default();
    // Made with the first phone window. On a Mac kept for the app's life — gilrs's
    // threads there and its HID manager have no way to end — reading on, not
    // sending, while no phone window is open. On Windows dropped as the last
    // phone window closes: its backend stops with it, and polls every 8 ms while
    // it lives, which nobody without a phone window open should pay for.
    let mut reader: Option<reader::Reader> = None;
    let mut retry_at: Option<Instant> = None;
    let mut said_failed = false;
    let mut said_idle = false;
    let mut dead_reads = 0u32;
    loop {
        let any = super::any_android_window();
        if any && reader.is_none() && retry_at.is_none_or(|t| Instant::now() >= t) {
            match reader::Reader::new() {
                Ok(r) => reader = Some(r),
                Err(e) => {
                    if !said_failed {
                        said_failed = true;
                        log::warn!("game controllers: this computer's cannot be read ({e}); tried again every {} s", live::RETRY_EVERY.as_secs());
                    }
                    retry_at = Some(Instant::now() + live::RETRY_EVERY);
                }
            }
        }
        if any {
            said_idle = false;
        } else {
            // A controller is woken again by a window's use of it.
            sent.awake = [None; SLOTS];
            if cfg!(windows) && reader.is_some() {
                reader = None;
                log::info!("game controllers: no phone window open, no longer read");
            } else if reader.is_some() && !said_idle {
                said_idle = true;
                log::info!("game controllers: no phone window open, not sent");
            }
        }
        let wait = if any { live::LOOK_EVERY } else { live::IDLE_EVERY };
        let started = Instant::now();
        let events = match reader.as_mut() {
            Some(r) => r.read(wait, any),
            None => {
                let (t, bell) = live::table();
                let t = t.lock().unwrap();
                let _ = bell.wait_timeout(t, wait);
                1
            }
        };
        // Never a loop with no wait: a reader whose source has gone answers at once with
        // nothing. A few of those running, and it is made again on the long back-off.
        let spent = started.elapsed();
        if events == 0 && spent < live::DEAD_SOON {
            dead_reads += 1;
            if dead_reads >= live::DEAD_AFTER {
                dead_reads = 0;
                reader = None;
                retry_at = Some(Instant::now() + live::RETRY_EVERY);
                if !said_failed {
                    said_failed = true;
                    log::warn!("game controllers: their source on this computer went; tried again every {} s", live::RETRY_EVERY.as_secs());
                }
            }
        } else {
            dead_reads = 0;
        }
        if events == 0 && spent < wait / 2 {
            let (t, bell) = live::table();
            let t = t.lock().unwrap();
            let _ = bell.wait_timeout(t, wait - spent);
        }
        if any || sent.made.iter().any(|m| *m) {
            pass(&mut sent);
        }
    }
}

/// Whether a device gilrs found is used as a game controller: one SDL's
/// controller database knows (`sdl_mapped`), as SDL's own game controllers
/// are — the only kind on a Mac — or, on Windows, one Windows itself reads as a
/// gamepad (Windows.Gaming.Input's `Gamepad`; gilrs gives those an all-zero
/// id and Windows' own layout). gilrs 0.11 calls every other device
/// "driver-mapped", a 3D mouse included: it never says `MappingSource::None`.
pub fn usable(sdl_mapped: bool, windows_gamepad: bool) -> bool {
    sdl_mapped || windows_gamepad
}

/// Whether a controller's state goes to the phone window in front (`front`,
/// its id): only once the controller has been used — a button, the d-pad, a
/// trigger, a stick past the dead zone (`wakes`) — while that window was in
/// front. `awake` is the window it was last used in. Nothing goes while no
/// phone window is in front, and nothing wakes then.
pub fn goes_to(awake: &mut Option<u64>, front: Option<u64>, state: &PadState) -> bool {
    let Some(f) = front else { return false };
    if *awake == Some(f) {
        return true;
    }
    if wakes(state) {
        *awake = Some(f);
        return true;
    }
    false
}

/// Each controller's state to the phone window in front, where it differs
/// from what that window was sent — once the controller has been used; a
/// controller that went, to every phone.
#[cfg(any(windows, target_os = "macos"))]
fn pass(sent: &mut live::Sent) {
    let slots = {
        let mut t = live::table().0.lock().unwrap();
        t.changed = false;
        t.slots
    };
    let front = super::front_android_window();
    let front_key = front.as_ref().map(|w| (w.id, w.generation()));
    if front_key != sent.front {
        // A window coming to the front, or on a connection made again, is sent
        // what is held now, at once: the one it left let go of it already (its
        // blur), and a new connection starts with the pads let go.
        sent.front = front_key;
        sent.slots = [None; SLOTS];
    }
    for slot in 0..SLOTS {
        match slots[slot] {
            Some(state) => {
                if !goes_to(&mut sent.awake[slot], front.as_ref().map(|w| w.id), &state) {
                    continue;
                }
                let Some(w) = front.as_ref() else { continue };
                if sent.slots[slot] == Some((w.id, state)) {
                    continue;
                }
                w.send(super::wire::gamepad(slot as u8, &state.report()));
                sent.slots[slot] = Some((w.id, state));
                sent.made[slot] = true;
                if sent.noted.insert(w.id) {
                    w.note(Some("Game controller connected.".into()), false);
                }
            }
            None => {
                sent.slots[slot] = None;
                sent.awake[slot] = None;
                if std::mem::take(&mut sent.made[slot]) {
                    for s in super::host::all_running() {
                        s.gamepad_gone(slot as u8);
                    }
                }
            }
        }
    }
}

/// The phone refused a controller (its window said so): the note under its title bar.
pub const REFUSED: &str = "Your phone doesn't let a computer add a game controller.";

/// The phone holds a controller's input until the window is clicked (its window said so).
pub const CLICK_FIRST: &str = "Click in the window to use your game controller here.";

/// Reading real controllers: gilrs (doc/game-mode.md, "A controller is the
/// phone's own") — Windows.Gaming.Input on Windows, IOKit HID on a Mac.
#[cfg(any(windows, target_os = "macos"))]
mod reader {
    use super::{axis, button, hat, live, trigger, PadState, SLOTS};
    use gilrs::ev::filter::{axis_dpad_to_button, deadzone, Filter, Jitter};
    use gilrs::{Axis, Button, EventType, GamepadId, Gilrs, GilrsBuilder, MappingSource};
    use std::time::{Duration, Instant};

    pub struct Reader {
        gilrs: Gilrs,
        /// Which controller is in which slot: in the order they connected, and
        /// those past the fourth waiting for a slot.
        seats: super::Seats<GamepadId>,
    }

    impl Reader {
        pub fn new() -> Result<Reader, String> {
            let gilrs = GilrsBuilder::new()
                // Nothing here rumbles, and gilrs's force-feedback thread has no way to end.
                .with_force_feedback(false)
                // gilrs's own filters run in `read` instead, without the loop
                // that waits past its timeout while they drop events.
                .with_default_filters(false)
                .set_update_state(false)
                .build()
                .map_err(|e| e.to_string())?;
            let mut r = Reader { gilrs, seats: super::Seats::new() };
            let ids: Vec<GamepadId> = r.gilrs.gamepads().map(|(id, _)| id).collect();
            for id in &ids {
                r.connect(*id);
            }
            log::info!("game controllers: reading them, {} connected", ids.len());
            r.publish();
            Ok(r)
        }

        fn connect(&mut self, id: GamepadId) {
            if self.seats.has(id) {
                return;
            }
            let g = self.gilrs.gamepad(id);
            let name = g.name().to_string();
            // A device with no controller layout — a 3D mouse, a keyboard that
            // also says it is a joystick — is no controller here, as it is none
            // to SDL's game controllers (scrcpy's).
            let sdl = g.mapping_source() == MappingSource::SdlMappings;
            let windows_gamepad = cfg!(windows) && g.uuid() == [0u8; 16];
            if !super::usable(sdl, windows_gamepad) {
                log::info!("game controllers: {name} connected, but it has no controller layout here: it is not used");
                return;
            }
            match self.seats.connect(id) {
                Some(i) => log::info!("game controllers: {name} connected, in slot {i}"),
                None => log::info!("game controllers: {name} connected, but {SLOTS} are in use already: it waits for one to go"),
            }
        }

        fn disconnect(&mut self, id: GamepadId) {
            if let Some((i, next)) = self.seats.disconnect(id) {
                live::table().0.lock().unwrap().slots[i] = None;
                match next {
                    Some(n) => log::info!("game controllers: the one in slot {i} went; {} takes it", self.gilrs.gamepad(n).name()),
                    None => log::info!("game controllers: the one in slot {i} went"),
                }
            }
        }

        /// The events that come within `wait` — the first waited for, the rest
        /// taken as they are, never past its end — then, if `publish`, each
        /// controller's state into the table. How many came.
        pub fn read(&mut self, wait: Duration, publish: bool) -> usize {
            let until = Instant::now() + wait;
            let jitter = Jitter::new();
            let mut n = 0;
            let mut next = self.gilrs.next_event_blocking(Some(wait));
            while let Some(raw) = next {
                n += 1;
                // gilrs's default filters, as it runs them, and its state kept from what they let through.
                let ev = Some(raw)
                    .filter_ev(&axis_dpad_to_button, &mut self.gilrs)
                    .filter_ev(&jitter, &mut self.gilrs)
                    .filter_ev(&deadzone, &mut self.gilrs);
                if let Some(ev) = ev.filter(|e| !e.is_dropped()) {
                    self.gilrs.update(&ev);
                    match ev.event {
                        EventType::Connected => self.connect(ev.id),
                        EventType::Disconnected => self.disconnect(ev.id),
                        _ => {}
                    }
                }
                // A stick that jitters at rest sends on and on: the pass is not held back by it.
                if Instant::now() >= until {
                    break;
                }
                next = self.gilrs.next_event();
            }
            self.gilrs.inc();
            if publish {
                self.publish();
            }
            n
        }

        /// The slots this reader has, as the phone's pad would report them. A
        /// slot it does not have is left alone: the debug hook's, if anyone's.
        fn publish(&self) {
            let mut t = live::table().0.lock().unwrap();
            for (i, id) in self.seats.slots.iter().enumerate() {
                if let Some(id) = id {
                    t.slots[i] = Some(self.state(*id));
                }
            }
        }

        /// A controller unplugged while nothing read them must not come back as a ghost:
        /// its last state goes with the reader.
        fn forget(&self) {
            let mut t = live::table().0.lock().unwrap();
            for (i, id) in self.seats.slots.iter().enumerate() {
                if id.is_some() {
                    t.slots[i] = None;
                }
            }
        }

        fn state(&self, id: GamepadId) -> PadState {
            let g = self.gilrs.gamepad(id);
            let mut buttons = 0;
            for (b, bit) in [
                (Button::South, button::A),
                (Button::East, button::B),
                (Button::West, button::X),
                (Button::North, button::Y),
                (Button::LeftTrigger, button::L1),
                (Button::RightTrigger, button::R1),
                (Button::Select, button::BACK),
                (Button::Start, button::START),
                (Button::Mode, button::GUIDE),
                (Button::LeftThumb, button::L3),
                (Button::RightThumb, button::R3),
            ] {
                if g.is_pressed(b) {
                    buttons |= bit;
                }
            }
            let pull = |b: Button| g.button_data(b).map_or(0.0, |d| d.value());
            PadState {
                buttons,
                hat: hat(
                    g.is_pressed(Button::DPadUp),
                    g.is_pressed(Button::DPadRight),
                    g.is_pressed(Button::DPadDown),
                    g.is_pressed(Button::DPadLeft),
                ),
                // gilrs counts up as positive; the phone's pad, as HID does, down.
                lx: axis(g.value(Axis::LeftStickX), false),
                ly: axis(g.value(Axis::LeftStickY), true),
                rx: axis(g.value(Axis::RightStickX), false),
                ry: axis(g.value(Axis::RightStickY), true),
                lt: trigger(pull(Button::LeftTrigger2)),
                rt: trigger(pull(Button::RightTrigger2)),
            }
        }
    }

    impl Drop for Reader {
        fn drop(&mut self) {
            self.forget();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fifth_controller_takes_the_slot_one_of_the_four_lets_go() {
        let mut s: Seats<u32> = Seats::new();
        assert_eq!((1..=4).map(|id| s.connect(id)).collect::<Vec<_>>(), [Some(0), Some(1), Some(2), Some(3)]);
        assert_eq!(s.connect(5), None, "a fifth waits");
        assert_eq!(s.connect(6), None, "and a sixth behind it");
        assert_eq!(s.connect(2), Some(1), "one connected again keeps its slot");
        assert!(s.has(5) && s.has(6));
        // The second goes: the fifth has its slot, the sixth still waits.
        assert_eq!(s.disconnect(2), Some((1, Some(5))));
        assert_eq!(s.slots, [Some(1), Some(5), Some(3), Some(4)]);
        // One waiting that goes was never in a slot.
        assert_eq!(s.disconnect(6), None);
        assert!(!s.has(6));
        // With nobody waiting, a slot let go of is free for the next.
        assert_eq!(s.disconnect(3), Some((2, None)));
        assert_eq!(s.connect(7), Some(2));
        assert_eq!(s.disconnect(9), None);
    }

    #[test]
    fn the_report_is_the_one_the_probe_proved() {
        let s = PadState {
            buttons: button::A
                | button::B
                | button::X
                | button::Y
                | button::L1
                | button::R1
                | button::BACK
                | button::START
                | button::GUIDE
                | button::L3
                | button::R3,
            hat: 3,
            lx: 1000,
            ly: -2000,
            rx: 3000,
            ry: -4000,
            lt: 64,
            rt: 200,
        };
        assert_eq!(s.report(), [0xdb, 0x7c, 0x03, 0xe8, 0x03, 0x30, 0xf8, 0xb8, 0x0b, 0x60, 0xf0, 0x40, 0xc8]);
        assert_eq!(PadState::from_report(&s.report()), s);
        assert_eq!(PadState::default().report(), [0u8; 13]);
    }

    #[test]
    fn the_hat_from_the_d_pad() {
        assert_eq!(hat(false, false, false, false), 0);
        assert_eq!(hat(true, false, false, false), 1);
        assert_eq!(hat(true, true, false, false), 2);
        assert_eq!(hat(false, true, false, false), 3);
        assert_eq!(hat(false, true, true, false), 4);
        assert_eq!(hat(false, false, true, false), 5);
        assert_eq!(hat(false, false, true, true), 6);
        assert_eq!(hat(false, false, false, true), 7);
        assert_eq!(hat(true, false, false, true), 8);
        assert_eq!(hat(true, false, true, false), 0, "opposites cancel");
        assert_eq!(hat(true, true, true, false), 3);
    }

    #[test]
    fn a_controller_wakes_on_real_input_only() {
        let rest = PadState::default();
        assert!(!wakes(&rest), "only plugged in");
        // A resting stick's drift, a trigger barely touched: still asleep.
        assert!(!wakes(&PadState { lx: 3000, ry: -7999, lt: 20, ..rest }));
        assert!(wakes(&PadState { buttons: button::A, ..rest }));
        assert!(wakes(&PadState { hat: 5, ..rest }));
        assert!(wakes(&PadState { rt: 33, ..rest }));
        assert!(wakes(&PadState { ly: -8001, ..rest }));
        assert!(wakes(&PadState { rx: i16::MIN, ..rest }), "i16::MIN has no positive twin");
    }

    #[test]
    fn a_controller_goes_only_to_the_window_in_front_it_was_used_in() {
        let rest = PadState::default();
        let pressed = PadState { buttons: button::A, ..rest };
        let mut awake = None;
        // No phone window in front: nothing goes, and a press there wakes nothing.
        assert!(!goes_to(&mut awake, None, &pressed));
        assert_eq!(awake, None);
        // Window 1 in front, the controller resting: nothing.
        assert!(!goes_to(&mut awake, Some(1), &rest));
        // Used there: it goes, and goes on at rest.
        assert!(goes_to(&mut awake, Some(1), &pressed));
        assert!(goes_to(&mut awake, Some(1), &rest));
        // Window 2 comes in front: nothing until it is used there.
        assert!(!goes_to(&mut awake, Some(2), &rest));
        assert_eq!(awake, Some(1));
        assert!(goes_to(&mut awake, Some(2), &PadState { lx: 20000, ..rest }));
        assert_eq!(awake, Some(2));
    }

    #[test]
    fn a_device_is_a_controller_only_with_a_layout() {
        assert!(usable(true, false), "in SDL's database");
        assert!(usable(false, true), "a Windows gamepad");
        assert!(!usable(false, false), "a 3D mouse: gilrs's default layout only");
    }

    #[test]
    fn a_gilrs_y_of_one_is_up_on_the_phone() {
        assert_eq!(axis(1.0, true), -32767);
        assert_eq!(axis(-1.0, true), 32767);
        assert_eq!(axis(0.5, false), 16384);
        assert_eq!(axis(3.0, false), 32767);
        assert_eq!(trigger(1.0), 255);
        assert_eq!(trigger(0.25), 64);
        assert_eq!(trigger(-1.0), 0);
    }
}
