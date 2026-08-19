//! Laptop touchpad gestures for the DeX desktop.
//!
//! A three-finger swipe on a laptop trackpad is not a mouse event and never
//! reaches scrcpy: Windows recognises it in the shell and turns it into Task
//! View or a desktop switch. The only way for the DeX desktop to answer it is
//! to read the pad's raw contacts ourselves, recognise the gesture on this
//! side, and drive the phone with a command.
//!
//! ## The three layers
//!
//! * **Reading** is per host and lives in [`backend`]. Windows subscribes to
//!   the pad's HID digitizer collection with raw input; macOS taps the event
//!   stream and asks each gesture event which fingers are on the trackpad.
//!   Both end up in the same place: a list of contacts in *fractions of pad
//!   width*, which is the one description of a touch that means the same thing
//!   on a 100 mm PC pad and a 160 mm Force Touch trackpad.
//! * **Recognising** is shared, in [`Recogniser`]. Both hosts hand it the same
//!   normalised contacts and get the same [`Gesture`] out, so a swipe commits
//!   after the same physical travel on either machine and there is one
//!   description of what a tap is rather than two that drift.
//! * **Acting** is here too, and is host-independent. A gesture is looked up
//!   in the user's mapping and dispatched as an [`Action`] over whichever of
//!   the device channels is right for it (see [`Sink::act`]).
//!
//! ## What is deliberately NOT here
//!
//! One and two finger input is untouched. The Precision Touchpad driver
//! already turns those into a pointer, a wheel and a right-click, and they
//! already reach scrcpy's window and the phone — reading the same contacts to
//! re-derive them would fight the driver for the cursor and break the thing
//! that works. This module classifies **three or more** contacts and nothing
//! else, which is what makes it purely additive.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use tauri::AppHandle;

use crate::scrcpy::Shared;
use crate::shell::ShellSession;
use crate::wm::WmClient;

#[cfg_attr(windows, path = "windows.rs")]
#[cfg_attr(target_os = "macos", path = "macos.rs")]
#[cfg_attr(not(any(windows, target_os = "macos")), path = "unsupported.rs")]
mod backend;

/// One recognised touchpad gesture.
///
/// Deliberately coarse. A finger count and one of five shapes is the whole
/// vocabulary Samsung DeX and Windows both settled on, and it is the largest
/// set a user can perform reliably without looking at the pad.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Gesture {
    /// How many contacts were down at the moment it fired (3 or more).
    pub fingers: u8,
    pub kind: Kind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Up,
    Down,
    Left,
    Right,
    Tap,
}

impl Gesture {
    /// The settings key this gesture is mapped by, e.g. `gest3up`.
    ///
    /// Also the wire form: the launcher's Settings window pushes
    /// `cfg <slot>.<action>` and the value lands in `stream-config.json`
    /// under exactly this key. Keys must stay dot-free — the request pump
    /// splits `cfg` on the first dot.
    fn slot(&self) -> String {
        let shape = match self.kind {
            Kind::Up => "up",
            Kind::Down => "down",
            Kind::Left => "left",
            Kind::Right => "right",
            Kind::Tap => "tap",
        };
        format!("gest{}{shape}", self.fingers)
    }

    /// This gesture's bit in the claimed-slot mask, or `None` if it is outside
    /// the vocabulary and so can never be claimed.
    fn bit(&self) -> Option<u32> {
        if !(3..=MAX_FINGERS).contains(&self.fingers) {
            return None;
        }
        let shape = match self.kind {
            Kind::Up => 0,
            Kind::Down => 1,
            Kind::Left => 2,
            Kind::Right => 3,
            Kind::Tap => 4,
        };
        Some(1 << ((self.fingers - 3) * 5 + shape))
    }
}

// ── Recognition ───────────────────────────────────────────────────────
//
// Shared by both hosts. Everything below works in "fractions of pad width"
// with the origin at the pad's top-left corner, and it is the backend's job
// to convert into that: a pad's own coordinates are meaningless across
// machines, while a fraction of its width is the same physical gesture
// whatever the hardware.

/// How far the fingers must travel before a swipe fires, as a fraction of pad
/// width — about 10 mm on a typical 100 mm laptop pad, which is roughly where
/// both Windows and macOS commit their own three-finger swipes.
const SWIPE_FRAC: f32 = 0.10;

/// How still the fingers must be for a lift to count as a tap.
const TAP_SLOP: f32 = 0.03;

/// And how briefly they may rest. Longer is a hold, and a hold must fire
/// nothing — it is how a user pauses part-way through a gesture.
const TAP_MAX: Duration = Duration::from_millis(300);

/// A frame this old is assumed to have been followed by a lift we never saw.
///
/// Contacts normally end with their own lift, so this only matters when the
/// pad sleeps or an event is dropped — but without it a stale three-finger
/// frame makes the NEXT touch look like the middle of a gesture.
const FRAME_STALE: Duration = Duration::from_millis(250);

/// One three-or-more-finger touch, from the moment the third finger landed.
struct Burst {
    /// The most contacts down at once — the count the gesture is named by, so
    /// briefly losing a finger mid-swipe does not demote it.
    fingers: u8,
    start: (f32, f32),
    began: Instant,
    /// One touch fires at most one gesture. Set the moment it does.
    fired: bool,
    /// The fingers went somewhere, even if not far enough to be a swipe. What
    /// stops a sloppy half-swipe from being delivered as a tap when it ends.
    drifted: bool,
}

/// Turns a stream of "these fingers are on the pad right now" into gestures.
///
/// Deliberately ignorant of one and two contacts. The host's own driver turns
/// those into a pointer, a wheel and a right-click that already reach scrcpy
/// and the phone, and re-deriving them here would mean fighting the driver for
/// the cursor. Three is the smallest number this ever reports.
#[derive(Default)]
pub struct Recogniser {
    burst: Option<Burst>,
    last_frame: Option<Instant>,
}

impl Recogniser {
    /// Feed one frame of contacts. Answers a gesture at most once per touch.
    ///
    /// `contacts` is every finger currently down, in fractions of pad width.
    /// An empty slice means the pad is clear, and is what ends a touch.
    pub fn update(&mut self, contacts: &[(f32, f32)]) -> Option<Gesture> {
        let now = Instant::now();
        if self.last_frame.is_some_and(|t| now - t > FRAME_STALE) {
            self.burst = None;
        }
        self.last_frame = Some(now);

        if contacts.len() >= 3 {
            let n = contacts.len() as f32;
            let (sx, sy) = contacts
                .iter()
                .fold((0.0, 0.0), |a, p| (a.0 + p.0, a.1 + p.1));
            return self.moved((sx / n, sy / n), contacts.len() as u8, now);
        }
        // Fewer than three fingers. Anything above zero is a touch still in
        // progress — a swipe often loses a finger before the others lift, and
        // ending the burst there would turn it into a tap.
        if !contacts.is_empty() {
            return None;
        }
        self.lifted(now)
    }

    fn moved(&mut self, centre: (f32, f32), count: u8, now: Instant) -> Option<Gesture> {
        let burst = self.burst.get_or_insert(Burst {
            fingers: 0,
            start: centre,
            began: now,
            fired: false,
            drifted: false,
        });
        burst.fingers = burst.fingers.max(count);
        if burst.fired {
            return None;
        }
        let (dx, dy) = (centre.0 - burst.start.0, centre.1 - burst.start.1);
        let travel = dx.abs().max(dy.abs());
        burst.drifted |= travel > TAP_SLOP;
        if travel < SWIPE_FRAC {
            return None;
        }
        // Fire on crossing the threshold rather than on lift: a swipe that
        // only answers once the fingers come up reads as a slow machine.
        let kind = if dx.abs() > dy.abs() {
            if dx > 0.0 {
                Kind::Right
            } else {
                Kind::Left
            }
        } else if dy > 0.0 {
            Kind::Down
        } else {
            Kind::Up
        };
        burst.fired = true;
        Some(Gesture {
            fingers: burst.fingers,
            kind,
        })
    }

    fn lifted(&mut self, now: Instant) -> Option<Gesture> {
        let burst = self.burst.take()?;
        // A touch that already fired is spent, one that lingered was a hold,
        // and one that wandered was an abandoned swipe. What is left is short,
        // still and three-fingered: a tap.
        if burst.fired || burst.drifted || now - burst.began > TAP_MAX {
            return None;
        }
        Some(Gesture {
            fingers: burst.fingers,
            kind: Kind::Tap,
        })
    }
}

/// What a gesture does. The names are the wire values stored in
/// `stream-config.json` and offered by the launcher's Settings window.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    None,
    /// The taskbar's open-apps popup — this desktop's window switcher.
    OpenApps,
    /// Minimise every window, or bring them all back if they already are.
    ShowDesktop,
    /// Raise the window behind the current one / go back the other way.
    NextWindow,
    PrevWindow,
    /// The launcher's app drawer.
    Drawer,
    /// Maximise the focused window, or restore it.
    MaximizeWindow,
    Home,
    Back,
    Notifications,
}

impl Action {
    fn parse(v: &str) -> Option<Self> {
        Some(match v {
            "none" => Action::None,
            "openapps" => Action::OpenApps,
            "showdesktop" => Action::ShowDesktop,
            "nextwindow" => Action::NextWindow,
            "prevwindow" => Action::PrevWindow,
            "drawer" => Action::Drawer,
            "maximize" => Action::MaximizeWindow,
            "home" => Action::Home,
            "back" => Action::Back,
            "notifications" => Action::Notifications,
            _ => return None,
        })
    }
}

/// The mapping a fresh install starts with.
///
/// It is Samsung DeX's three-finger set, which is also Windows 11's own
/// three-finger set — up shows what is open, down clears to the desktop, left
/// and right walk the open windows. A laptop user's existing muscle memory
/// therefore keeps working, pointed at Android instead of at Windows.
///
/// Four and five finger gestures default to nothing on purpose: we leave them
/// to Windows, so Task View and desktop switching are still there while DeX
/// has the pad. See `backend::suppress` for the other half of that bargain.
fn default_action(gesture: &Gesture) -> Action {
    match (gesture.fingers, gesture.kind) {
        (3, Kind::Up) => Action::OpenApps,
        (3, Kind::Down) => Action::ShowDesktop,
        (3, Kind::Left) => Action::PrevWindow,
        (3, Kind::Right) => Action::NextWindow,
        (3, Kind::Tap) => Action::Back,
        _ => Action::None,
    }
}

/// Whether the user has the feature on at all. Read live, on every gesture.
fn enabled(app: &AppHandle) -> bool {
    crate::scrcpy::config_value(app, "gestures").as_deref() != Some("off")
}

/// Resolve a gesture to its action.
///
/// Read from disk per gesture rather than cached at session start. A gesture
/// is a handful of events per minute against a file of a dozen short strings,
/// and reading it live is what lets the mapping change in the launcher's
/// Settings window and take effect on the next swipe — every other scrcpy
/// setting needs a session restart because it is an argv entry, and this one
/// is not.
fn action_for(app: &AppHandle, gesture: &Gesture) -> Action {
    crate::scrcpy::config_value(app, &gesture.slot())
        .and_then(|v| Action::parse(&v))
        .unwrap_or_else(|| default_action(gesture))
}

/// Everything a recognised gesture needs in order to reach the phone.
///
/// Owned by the gesture thread, not borrowed from the enforcer: a gesture must
/// land in the tens of milliseconds, and handing it to the enforcer's 100 ms
/// poll would add up to a poll period of jitter to something the user
/// performed with their hand. The one exception is maximize/restore, which
/// goes through the enforcer's mailbox because the enforcer owns the
/// per-window maximized bookkeeping and would otherwise undo it on the next
/// tick.
pub struct Sink {
    app: AppHandle,
    key: String,
    shell: ShellSession,
    wm: WmClient,
    shared: Arc<Shared>,
    /// The window ring: task ids in a fixed order, and where in it the last
    /// Next/Prev landed. See [`Sink::cycle_window`] for why it is remembered
    /// rather than re-derived from the z-order each time.
    ring: Vec<i32>,
    ring_at: usize,
}

const LAUNCHER: &str = "com.ccrstech.openandroiddex.launcher";

impl Sink {
    /// Fire-and-forget device command. Same subshell shape as the enforcer's
    /// `run_bg`, and for the same reason — see the comment there.
    fn run_bg(&mut self, cmd: &str) -> bool {
        let wrapped = format!("(({cmd}) >/dev/null 2>&1 &)");
        match self.shell.run(&self.app, &wrapped) {
            Ok(_) => true,
            Err(e) => {
                log::warn!("gestures [{}] shell: {e}", self.key);
                false
            }
        }
    }

    /// Tell the launcher to do something only it can do.
    ///
    /// `am broadcast` rather than the window daemon because these are shell
    /// UI — a popup, the drawer, the notification panel — and the daemon
    /// holds no UI at all by design.
    fn tell_launcher(&mut self, what: &str) -> bool {
        self.run_bg(&format!(
            "am broadcast -a {LAUNCHER}.GESTURE -p {LAUNCHER} --es action {what}"
        ))
    }

    /// Raise the next (or previous) window on the desktop.
    ///
    /// "Scroll through apps" in DeX's words, so it has to be a *ring*: three
    /// swipes right through three windows must come back where it started.
    /// That cannot be read off the z-order, because the only primitive we have
    /// is "raise this one" — walking a live topmost-first list forwards just
    /// swaps the top two back and forth forever. So the order is remembered
    /// here and only rebuilt when the set of windows actually changes, which
    /// also means a half-finished walk survives the user pausing.
    ///
    /// Sending the front window to the back would give a true rotation with no
    /// state at all, and is deliberately not used: the launcher is a
    /// fullscreen task, so anything that lands beneath it is occluded, stopped
    /// by the window manager, and reads as closed.
    fn cycle_window(&mut self, display: i32, forward: bool) -> bool {
        let tasks: Vec<i32> = self
            .wm
            .list(display)
            .into_iter()
            .filter(|t| t.visible && t.package != LAUNCHER)
            .map(|t| t.task_id)
            .collect();
        if tasks.len() < 2 {
            return false;
        }
        // Same windows as last time? Keep the ring — and with it the walk.
        // Otherwise start again from the z-order, whose first entry is the
        // window the user is looking at.
        let same = self.ring.len() == tasks.len() && self.ring.iter().all(|id| tasks.contains(id));
        if !same {
            self.ring = tasks;
            self.ring_at = 0;
        }
        let n = self.ring.len();
        self.ring_at = if forward {
            (self.ring_at + 1) % n
        } else {
            (self.ring_at + n - 1) % n
        };
        self.wm.front(display, self.ring[self.ring_at])
    }

    /// Maximize or restore whatever is on top.
    ///
    /// Handed to the enforcer as a package name, exactly as the taskbar's own
    /// maximize button is: that side holds the task ids, the windowed bounds
    /// to come back to and the in-flight transition, and a bare resize issued
    /// from here would be undone on its next pass.
    fn toggle_maximize(&mut self, display: i32) -> bool {
        let Some(top) = self
            .wm
            .list(display)
            .into_iter()
            .find(|t| t.visible && t.package != LAUNCHER)
        else {
            return false;
        };
        self.shared.window_reqs.lock().unwrap().push(top.package);
        true
    }

    fn act(&mut self, action: Action, display: i32) {
        let ok = match action {
            Action::None => return,
            Action::OpenApps => self.tell_launcher("openapps"),
            Action::Drawer => self.tell_launcher("drawer"),
            Action::ShowDesktop => self.tell_launcher("showdesktop"),
            Action::Notifications => self.tell_launcher("notifications"),
            Action::NextWindow => self.cycle_window(display, true),
            Action::PrevWindow => self.cycle_window(display, false),
            Action::MaximizeWindow => self.toggle_maximize(display),
            // Injected on the desktop display so the app focused THERE gets
            // it, not whatever the phone's own screen is showing.
            Action::Home => self.run_bg(&format!("input -d {display} keyevent KEYCODE_HOME")),
            Action::Back => self.run_bg(&format!("input -d {display} keyevent KEYCODE_BACK")),
        };
        if !ok {
            log::warn!("gestures [{}] {action:?} did not land", self.key);
        }
    }
}

/// Where a backend posts the gestures it recognises.
///
/// A channel, and not a function call, for a reason that is not tidiness. A
/// gesture's action costs an adb round trip — up to a few hundred milliseconds
/// for a keyevent, which boots a VM on the phone — and both readers are on a
/// thread that must not block: on Windows a stalled reader drops contacts
/// mid-swipe, and on macOS a tap callback that takes too long is *switched off
/// by the system*. So the reader's whole job on the hot path is a foreground
/// check and a send.
#[derive(Clone)]
pub struct Dispatcher {
    tx: std::sync::mpsc::Sender<Gesture>,
    /// Which gestures currently resolve to a real action, one bit per slot.
    ///
    /// The mapping itself lives on disk and the master switch can change at
    /// any moment, and neither is a thing to go and read from a reader — but
    /// macOS has to decide *synchronously*, before it returns from the tap,
    /// whether to swallow the event. Swallowing one we are not going to act on
    /// would silently delete the user's own four-finger gestures, and keep
    /// deleting three-finger ones after they switched the feature off. So the
    /// worker keeps this in step and the reader reads it with one atomic load.
    claimed: Arc<AtomicU32>,
}

impl Dispatcher {
    /// Never blocks, and never fails in a way worth reporting: a closed
    /// channel means the session ended between recognising and posting.
    fn send(&self, gesture: Gesture) {
        let _ = self.tx.send(gesture);
    }

    /// Will this gesture actually do something? Safe to ask from a reader.
    fn claims(&self, gesture: &Gesture) -> bool {
        gesture
            .bit()
            .is_some_and(|bit| self.claimed.load(Ordering::Relaxed) & bit != 0)
    }

    /// Is *anything* mapped for this many fingers?
    ///
    /// Asked on the frame a finger lands, before any gesture exists, because
    /// macOS has to decide whether to swallow from the very first event: its
    /// own recogniser has its own threshold, and waiting until ours is crossed
    /// hands the Dock the opening half of every swipe.
    ///
    /// macOS-only by nature, not by oversight: raw input on Windows only tees,
    /// so there is nothing there to take an event away from.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    fn claims_any(&self, fingers: usize) -> bool {
        let Ok(fingers) = u8::try_from(fingers) else {
            return false;
        };
        let mask = [Kind::Up, Kind::Down, Kind::Left, Kind::Right, Kind::Tap]
            .into_iter()
            .filter_map(|kind| Gesture { fingers, kind }.bit())
            .fold(0, |a, b| a | b);
        mask != 0 && self.claimed.load(Ordering::Relaxed) & mask != 0
    }
}

/// Recompute the claimed-slot mask from what is on disk right now.
fn claimed_mask(app: &AppHandle) -> u32 {
    if !enabled(app) {
        return 0;
    }
    let mut mask = 0;
    for fingers in 3..=MAX_FINGERS {
        for kind in [Kind::Up, Kind::Down, Kind::Left, Kind::Right, Kind::Tap] {
            let gesture = Gesture { fingers, kind };
            if action_for(app, &gesture) != Action::None {
                mask |= gesture.bit().unwrap_or(0);
            }
        }
    }
    mask
}

/// The most contacts a gesture is ever named by. Precision Touchpads report up
/// to five, and nothing in the vocabulary uses more.
const MAX_FINGERS: u8 = 5;

/// Start reading the touchpad for a desktop session.
///
/// Idempotent in the sense that matters: the previous engine, if any, is torn
/// down first. Raw-input registration is per process and per usage page, not
/// per window, so two live readers would mean the second silently steals the
/// first's subscription and the first goes deaf.
pub fn start(
    app: AppHandle,
    serial: String,
    key: String,
    shared: Arc<Shared>,
    stop: Arc<AtomicBool>,
) {
    stop_engine();
    if !backend::supported() {
        return;
    }
    let mut sink = Sink {
        app: app.clone(),
        key: key.clone(),
        shell: ShellSession::new(serial),
        wm: WmClient::new(),
        shared,
        ring: Vec::new(),
        ring_at: 0,
    };
    let (tx, rx) = std::sync::mpsc::channel::<Gesture>();
    let claimed = Arc::new(AtomicU32::new(claimed_mask(&app)));

    // The thread that actually talks to the phone. Everything slow lives here
    // — reading the mapping off disk, the daemon socket, the adb shell.
    {
        let (app, key, stop) = (app.clone(), key.clone(), stop.clone());
        let claimed = claimed.clone();
        std::thread::spawn(move || {
            loop {
                match rx.recv_timeout(Duration::from_millis(250)) {
                    Ok(gesture) => act(&app, &key, &mut sink, gesture),
                    // Timing out is how the session's stop flag is noticed
                    // during the long quiet stretches between gestures.
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                }
                if stop.load(Ordering::SeqCst) {
                    break;
                }
                // Follow the master switch and the mapping rather than
                // sampling them once at session start: turning gestures off is
                // also the user asking for their computer's own three-finger
                // gestures back, and making them wait for the next session to
                // get them would be the wrong answer to that.
                let on = enabled(&app);
                claimed.store(claimed_mask(&app), Ordering::Relaxed);
                backend::set_suppressed(&app, on);
            }
            backend::set_suppressed(&app, false);
        });
    }

    backend::start(app, key, Dispatcher { tx, claimed }, stop);
}

/// True when this host can read a precision touchpad AND one is attached.
///
/// Answered for the launcher's Settings window, which dims its gesture rows
/// rather than hiding them — a desktop PC with a mouse should be told the
/// section is inert, not left wondering why nothing happens.
pub fn host_has_touchpad() -> bool {
    backend::has_touchpad()
}

/// Tear down the running engine, if there is one.
pub fn stop_engine() {
    backend::stop();
}

/// Put back any Windows touchpad settings a previous run left changed.
///
/// Called at startup, before anything else touches the pad. The session-end
/// path restores them too; this is the one that catches a crash, a power cut
/// or a kill from Task Manager, and it is why the snapshot is a file on disk
/// rather than a value in memory.
pub fn restore_host_settings(app: &AppHandle) {
    backend::restore_host_settings(app);
}

/// Carry out one recognised gesture. Runs on the worker thread, never on a
/// reader's.
///
/// Everything that can say "not now" says it here rather than in the reader,
/// so the host-specific half stays a pure classifier.
fn act(app: &AppHandle, key: &str, sink: &mut Sink, gesture: Gesture) {
    if !enabled(app) {
        return;
    }
    let action = action_for(app, &gesture);
    if action == Action::None {
        return;
    }
    // The display id changes on every reconnect, so it is resolved per
    // gesture and never cached.
    let Some(display) = crate::scrcpy::session_display_id(app, key) else {
        return;
    };
    if display < 0 {
        return;
    }
    log::info!("gestures [{key}] {gesture:?} -> {action:?} on display {display}");
    sink.act(action, display);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_slot_name_is_the_settings_key() {
        assert_eq!(
            Gesture {
                fingers: 3,
                kind: Kind::Up
            }
            .slot(),
            "gest3up"
        );
        assert_eq!(
            Gesture {
                fingers: 4,
                kind: Kind::Tap
            }
            .slot(),
            "gest4tap"
        );
    }

    #[test]
    fn slots_contain_no_dot_so_the_cfg_split_survives() {
        for fingers in 3..=5u8 {
            for kind in [Kind::Up, Kind::Down, Kind::Left, Kind::Right, Kind::Tap] {
                assert!(!Gesture { fingers, kind }.slot().contains('.'));
            }
        }
    }

    #[test]
    fn the_dex_three_finger_set_is_the_default() {
        let g = |kind| Gesture { fingers: 3, kind };
        assert_eq!(default_action(&g(Kind::Up)), Action::OpenApps);
        assert_eq!(default_action(&g(Kind::Down)), Action::ShowDesktop);
        assert_eq!(default_action(&g(Kind::Left)), Action::PrevWindow);
        assert_eq!(default_action(&g(Kind::Right)), Action::NextWindow);
        assert_eq!(default_action(&g(Kind::Tap)), Action::Back);
    }

    #[test]
    fn four_fingers_are_left_to_the_host_by_default() {
        for kind in [Kind::Up, Kind::Down, Kind::Left, Kind::Right, Kind::Tap] {
            assert_eq!(default_action(&Gesture { fingers: 4, kind }), Action::None);
        }
    }

    /// Three fingers in a row, offset from the origin by `d`.
    fn three(d: (f32, f32)) -> Vec<(f32, f32)> {
        [(0.40, 0.50), (0.50, 0.50), (0.60, 0.50)]
            .iter()
            .map(|p| (p.0 + d.0, p.1 + d.1))
            .collect()
    }

    /// Walk the fingers to `d` in small steps, as a real pad reports them.
    fn swipe_to(r: &mut Recogniser, d: (f32, f32)) -> Vec<Gesture> {
        let mut out = Vec::new();
        r.update(&three((0.0, 0.0)));
        for step in 1..=10 {
            let f = step as f32 / 10.0;
            if let Some(g) = r.update(&three((d.0 * f, d.1 * f))) {
                out.push(g);
            }
        }
        if let Some(g) = r.update(&[]) {
            out.push(g);
        }
        out
    }

    #[test]
    fn a_three_finger_swipe_reports_its_direction() {
        for (delta, kind) in [
            ((0.0, -0.30), Kind::Up),
            ((0.0, 0.30), Kind::Down),
            ((-0.30, 0.0), Kind::Left),
            ((0.30, 0.0), Kind::Right),
        ] {
            let mut r = Recogniser::default();
            assert_eq!(
                swipe_to(&mut r, delta),
                vec![Gesture { fingers: 3, kind }],
                "{delta:?} should read as {kind:?}"
            );
        }
    }

    #[test]
    fn one_touch_fires_exactly_one_gesture() {
        let mut r = Recogniser::default();
        // A long swipe crosses the threshold on nearly every frame; only the
        // first crossing may be delivered.
        assert_eq!(swipe_to(&mut r, (0.60, 0.0)).len(), 1);
    }

    #[test]
    fn one_and_two_fingers_are_never_ours() {
        let mut r = Recogniser::default();
        for step in 0..20 {
            let d = step as f32 / 20.0;
            assert!(r.update(&[(0.1 + d, 0.5)]).is_none());
            assert!(r.update(&[(0.1 + d, 0.5), (0.2 + d, 0.5)]).is_none());
        }
        assert!(r.update(&[]).is_none());
    }

    #[test]
    fn a_still_three_finger_touch_is_a_tap() {
        let mut r = Recogniser::default();
        assert!(r.update(&three((0.0, 0.0))).is_none());
        assert!(r.update(&three((0.004, 0.002))).is_none());
        assert_eq!(
            r.update(&[]),
            Some(Gesture {
                fingers: 3,
                kind: Kind::Tap
            })
        );
    }

    #[test]
    fn an_abandoned_swipe_is_not_a_tap() {
        let mut r = Recogniser::default();
        r.update(&three((0.0, 0.0)));
        // Past the tap slop, short of the swipe threshold: the user changed
        // their mind, and nothing at all should happen.
        r.update(&three((0.06, 0.0)));
        r.update(&three((0.06, 0.0)));
        assert_eq!(r.update(&[]), None);
    }

    #[test]
    fn a_finger_lifting_early_does_not_re_fire_the_swipe_as_a_tap() {
        let mut r = Recogniser::default();
        r.update(&three((0.0, 0.0)));
        assert!(r.update(&three((0.0, -0.30))).is_some());
        // Fingers rarely leave the pad together. The two that are still down
        // are the tail of a swipe that has already been answered — not the
        // start of anything, and certainly not a tap.
        assert_eq!(r.update(&[(0.40, 0.20), (0.50, 0.20)]), None);
        assert_eq!(r.update(&[]), None);
    }

    #[test]
    fn a_touch_that_never_reached_three_fingers_is_nothing() {
        let mut r = Recogniser::default();
        r.update(&[(0.40, 0.50), (0.50, 0.50)]);
        assert_eq!(r.update(&[]), None);
    }

    #[test]
    fn a_long_rest_on_three_fingers_is_a_hold_not_a_tap() {
        let mut r = Recogniser::default();
        r.update(&three((0.0, 0.0)));
        std::thread::sleep(TAP_MAX + Duration::from_millis(20));
        assert_eq!(r.update(&[]), None);
    }

    #[test]
    fn four_fingers_are_reported_as_four() {
        let mut r = Recogniser::default();
        let hand = |d: f32| {
            vec![
                (0.30 + d, 0.50),
                (0.40 + d, 0.50),
                (0.50 + d, 0.50),
                (0.60 + d, 0.50),
            ]
        };
        r.update(&hand(0.0));
        let fired: Vec<_> = (1..=10)
            .filter_map(|s| r.update(&hand(s as f32 / 10.0 * 0.3)))
            .collect();
        assert_eq!(
            fired,
            vec![Gesture {
                fingers: 4,
                kind: Kind::Right
            }]
        );
    }

    #[test]
    fn every_gesture_in_the_vocabulary_gets_its_own_bit() {
        let mut seen = std::collections::HashSet::new();
        for fingers in 3..=MAX_FINGERS {
            for kind in [Kind::Up, Kind::Down, Kind::Left, Kind::Right, Kind::Tap] {
                let bit = Gesture { fingers, kind }.bit().expect("in vocabulary");
                assert!(bit != 0 && bit.count_ones() == 1, "{fingers}/{kind:?}");
                assert!(seen.insert(bit), "{fingers}/{kind:?} collides");
            }
        }
        // The mask has to fit the u32 the reader loads it from.
        assert_eq!(seen.len(), 15);
    }

    #[test]
    fn a_gesture_outside_the_vocabulary_can_never_be_claimed() {
        // Six fingers is not a gesture we offer. It must not alias one that
        // is, because on macOS a claimed bit means "swallow this event".
        for fingers in [0u8, 1, 2, 6, 10, 255] {
            assert_eq!(
                Gesture {
                    fingers,
                    kind: Kind::Up
                }
                .bit(),
                None
            );
        }
    }

    #[test]
    fn every_action_round_trips_through_its_wire_name() {
        let names = [
            "none",
            "openapps",
            "showdesktop",
            "nextwindow",
            "prevwindow",
            "drawer",
            "maximize",
            "home",
            "back",
            "notifications",
        ];
        for name in names {
            assert!(Action::parse(name).is_some(), "{name} did not parse");
        }
        assert_eq!(Action::parse("nonsense"), None);
    }
}
