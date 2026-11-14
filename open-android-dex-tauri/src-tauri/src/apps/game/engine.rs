//! Keys and a mouse, turned into fingers on a touch-only game (doc/game-mode.md,
//! "The engine").
//!
//! Pure: no threads, no clock of its own, nothing of either computer's. Events
//! come in with the time they happened, as a `Duration` since some origin; the
//! fingers that should be down now come out, and when the engine next needs
//! to be woken (`deadline`). A window's session runs it on a thread of its own
//! and sends `fingers()` to the phone whenever they change.
//!
//! Finger ids: 0 is the pointer's (a left click with the mouse free); control
//! `i` of the layout is `i + 1`.

use super::keys::{Key, ESCAPE, UNDER_ESCAPE};
use super::layout::{Control, LookBy, Press};
use std::collections::HashSet;
use std::time::Duration;

/// A stick's first movement: down at its centre, then to where it points in this many steps…
pub const STICK_STEPS: u32 = 4;
/// …and a change of direction in this many…
pub const STICK_TURN_STEPS: u32 = 2;
/// …this far apart.
pub const STICK_STEP: Duration = Duration::from_millis(8);
/// How far out a stick's finger goes, of its radius.
pub const STICK_REACH: f32 = 0.9;
/// A held button is never let go sooner than this after it went down: a game must see it.
pub const HOLD_MIN: Duration = Duration::from_millis(40);
/// A tap's length.
pub const TAP: Duration = Duration::from_millis(50);
/// A look's finger is lifted when it comes this near the area's edge (of its width or height)…
pub const LOOK_EDGE: f32 = 0.06;
/// …and put down at the centre again this much later.
pub const LOOK_RELIFT: Duration = Duration::from_millis(8);
/// A look's finger with no movement for this long is lifted.
pub const LOOK_IDLE: Duration = Duration::from_millis(150);
/// A look's moves at most this often; the mouse's movement in between adds up.
pub const MOVE_EVERY: Duration = Duration::from_millis(8);
/// Fingers down at once, at most: a phone's touchscreen's, and fewer than the
/// 16 an injected event may carry (AppInput.java, doc/game-mode.md).
pub const MAX_FINGERS: usize = 10;
/// A look area is never narrower or shorter than this, in pixels.
pub const LOOK_MIN: f32 = 16.0;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, PartialOrd, Ord)]
pub enum MouseButton {
    Left,
    Right,
    Middle,
    Back,
    Forward,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, PartialOrd, Ord)]
pub enum WheelWay {
    Up,
    Down,
}

/// What a control is bound to.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, PartialOrd, Ord)]
pub enum Input {
    Key(Key),
    Mouse(MouseButton),
    Wheel(WheelWay),
}

const MOUSE: &[(&str, Input)] = &[
    ("MouseLeft", Input::Mouse(MouseButton::Left)),
    ("MouseRight", Input::Mouse(MouseButton::Right)),
    ("MouseMiddle", Input::Mouse(MouseButton::Middle)),
    ("MouseBack", Input::Mouse(MouseButton::Back)),
    ("MouseForward", Input::Mouse(MouseButton::Forward)),
    ("WheelUp", Input::Wheel(WheelWay::Up)),
    ("WheelDown", Input::Wheel(WheelWay::Down)),
];

impl Input {
    /// A key's `KeyboardEvent.code`, or `MouseLeft` … `WheelDown`.
    pub fn named(name: &str) -> Option<Input> {
        if let Some((_, i)) = MOUSE.iter().find(|(n, _)| *n == name) {
            return Some(*i);
        }
        Key::named(name).map(Input::Key)
    }

    pub fn name(self) -> &'static str {
        match self {
            Input::Key(k) => k.0,
            other => MOUSE.iter().find(|(_, i)| *i == other).map(|(n, _)| *n).unwrap_or(""),
        }
    }

    /// Whether a control may have it: not one of Open Android DeX's own keys.
    pub fn bindable(self) -> bool {
        match self {
            Input::Key(k) => k.bindable(),
            _ => true,
        }
    }

    /// Whether this computer can ever hand a control it (keys.rs, `Key::here`).
    pub fn here(self) -> bool {
        match self {
            Input::Key(k) => k.here(),
            _ => true,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Event {
    /// A key, a mouse button or a wheel notch (a notch is down then up at once).
    Input { input: Input, down: bool },
    /// The free pointer, in the display's pixels.
    Pointer { x: u16, y: u16 },
    /// The captured pointer's movement, in points.
    Delta { dx: f32, dy: f32 },
    /// The window's display now.
    Size { w: u16, h: u16, dpi: u16 },
    /// The window lost the keyboard.
    Blur,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Finger {
    pub id: u8,
    pub x: u16,
    pub y: u16,
}

type Pt = (f32, f32);

/// A control, placed on the display, and what it is doing.
#[derive(Debug)]
enum Ctl {
    Stick {
        centre: Pt,
        radius: f32,
        keys: [Option<Input>; 4], // up, left, down, right
        held: [bool; 4],
        finger: Option<Pt>,
        /// Where it goes next, and when.
        path: Vec<(Duration, Pt)>,
    },
    Button {
        at: Pt,
        key: Option<Input>,
        press: Press,
        held: bool,
        down_at: Option<Duration>,
        /// When its finger goes up (a tap's end, or a hold's let-go not sooner than HOLD_MIN).
        up_at: Option<Duration>,
    },
    Look {
        centre: Pt,
        half: Pt,
        by: LookBy,
        key: Option<Input>,
        speed: f32,
        /// Its key is held (a `Hold` look).
        held: bool,
        /// Esc gave the mouse back while its key was held: nothing until the key is let go and pressed again.
        freed: bool,
        finger: Option<Pt>,
        moved_at: Duration,
        /// Down at the centre again at this time, after the edge lifted it.
        relift_at: Option<Duration>,
        /// Movement not yet moved, in pixels; moved at most every MOVE_EVERY.
        pending: Pt,
        moved_last: Duration,
    },
}

pub struct Engine {
    controls: Vec<Control>,
    ctls: Vec<Ctl>,
    size: (u16, u16),
    dpi: u16,
    /// The `Always` look that has the mouse, by its place in `ctls`.
    taken: Option<usize>,
    /// The keys that take the mouse for an `Always` look ([`mouse_keys`]).
    mouse_keys: Vec<Input>,
    pointer: Pt,
    /// Finger 0: a left click with the pointer free.
    pointer_down: bool,
}

/// The key an `Always` look takes the mouse with: its own, or the key under Esc.
fn mouse_key(key: Option<Input>) -> Input {
    key.unwrap_or(Input::Key(UNDER_ESCAPE))
}

/// The keys that take the mouse for an `Always` look and give it back, each
/// once, in the looks' order: a look's own, or the key under Esc. Empty
/// without such a look. A key two looks share takes the mouse for the first,
/// moves it to the next at each press, and gives it back after the last.
pub fn mouse_keys(controls: &[Control]) -> Vec<Input> {
    let mut keys: Vec<Input> = Vec::new();
    for c in controls {
        if let Control::Look { by: LookBy::Always, key, .. } = c {
            let k = mouse_key(*key);
            if !keys.contains(&k) {
                keys.push(k);
            }
        }
    }
    keys
}

impl Engine {
    pub fn new(controls: &[Control], size: (u16, u16), dpi: u16) -> Engine {
        let mut e = Engine {
            controls: controls.to_vec(),
            ctls: Vec::new(),
            size,
            dpi: dpi.max(1),
            taken: None,
            mouse_keys: mouse_keys(controls),
            pointer: (0.0, 0.0),
            pointer_down: false,
        };
        e.place();
        e
    }

    /// The controls on the display as it is now; nothing held.
    fn place(&mut self) {
        let (w, h) = (self.size.0 as f32, self.size.1 as f32);
        let s = w.min(h);
        self.ctls = self
            .controls
            .iter()
            .map(|c| match c {
                Control::Stick { at, size, up, left, down, right } => Ctl::Stick {
                    centre: at.point(w, h),
                    radius: size * s,
                    keys: [*up, *left, *down, *right],
                    held: [false; 4],
                    finger: None,
                    path: Vec::new(),
                },
                Control::Button { at, key, press } => {
                    Ctl::Button { at: at.point(w, h), key: *key, press: *press, held: false, down_at: None, up_at: None }
                }
                Control::Look { at, w: aw, h: ah, by, key, speed } => {
                    let (centre, half) = fit(at.point(w, h), (aw * s / 2.0, ah * s / 2.0), w, h);
                    Ctl::Look {
                        centre,
                        half,
                        by: *by,
                        key: *key,
                        speed: *speed,
                        held: false,
                        freed: false,
                        finger: None,
                        moved_at: Duration::ZERO,
                        relift_at: None,
                        pending: (0.0, 0.0),
                        moved_last: Duration::ZERO,
                    }
                }
            })
            .collect();
    }

    /// The pointer should be captured now: an `Always` look has the mouse, or a `Hold` look's key is held.
    pub fn captured(&self) -> bool {
        self.taken.is_some() || self.ctls.iter().any(|c| matches!(c, Ctl::Look { by: LookBy::Hold, held: true, freed: false, .. }))
    }

    /// What the window must not send on as keys: every input a control has, and every key that takes the mouse.
    pub fn bound(&self) -> HashSet<Input> {
        let mut b: HashSet<Input> = self.controls.iter().flat_map(|c| c.inputs()).collect();
        b.extend(self.mouse_keys.iter().copied());
        b
    }

    /// Keys still held from before this engine, or before it let everything
    /// go — the controls off and on again (F8), a new size, the other shape —
    /// pressed again: a held W walks on, rather than waiting to be let go and
    /// pressed again. Only a control's own keys: one that takes the mouse acts
    /// on its press, never on being held, and Esc is no control's.
    pub fn press_again(&mut self, held: &HashSet<Input>, now: Duration) {
        let own: HashSet<Input> = self.controls.iter().flat_map(|c| c.inputs()).collect();
        let mut again: Vec<Input> = held.iter().copied().filter(|i| own.contains(i) && !self.mouse_keys.contains(i) && *i != Input::Key(ESCAPE)).collect();
        again.sort();
        for input in again {
            self.input(input, true, now);
        }
        self.tick(now);
    }

    fn down_count(&self) -> usize {
        self.fingers().len()
    }

    pub fn handle(&mut self, e: Event, now: Duration) {
        match e {
            Event::Input { input, down } => self.input(input, down, now),
            Event::Pointer { x, y } => {
                self.pointer = (x as f32, y as f32);
            }
            Event::Delta { dx, dy } => self.delta(dx, dy, now),
            Event::Size { w, h, dpi } => {
                if (w, h, dpi) != (self.size.0, self.size.1, self.dpi) {
                    self.size = (w, h);
                    self.dpi = dpi.max(1);
                    self.release_all();
                    self.place();
                }
            }
            Event::Blur => self.release_all(),
        }
        self.tick(now);
    }

    /// Everything let go, the mouse given back.
    fn release_all(&mut self) {
        self.taken = None;
        self.pointer_down = false;
        self.place();
    }

    fn input(&mut self, input: Input, down: bool, now: Duration) {
        // Open Android DeX's own: Esc gives the mouse back; an `Always` look's key (the key under Esc
        // unless it has one) takes it for that look, moves it from another, or gives it back.
        if input == Input::Key(ESCAPE) {
            if down {
                self.free();
            }
            return;
        }
        if self.mouse_keys.contains(&input) {
            if down {
                self.take_mouse(input);
            }
            return;
        }
        // A left click with the mouse free is a finger where the pointer is; its
        // let-go lifts that finger. Taken, the left button is a control's, and so
        // is its let-go once its press was — wherever the mouse is by then.
        if input == Input::Mouse(MouseButton::Left) {
            if down && !self.captured() {
                if !self.pointer_down && self.down_count() < MAX_FINGERS {
                    self.pointer_down = true;
                }
                return;
            }
            if !down && self.pointer_down {
                self.pointer_down = false;
                return;
            }
        }
        let room = MAX_FINGERS.saturating_sub(self.down_count());
        let mut made = 0;
        for c in &mut self.ctls {
            match c {
                Ctl::Stick { keys, held, finger, .. } => {
                    for i in 0..4 {
                        if keys[i] == Some(input) {
                            if down && finger.is_none() && made >= room {
                                continue;
                            }
                            if down && finger.is_none() && !held.iter().any(|h| *h) {
                                made += 1;
                            }
                            held[i] = down;
                        }
                    }
                }
                Ctl::Button { key, press, held, down_at, up_at, .. } => {
                    if *key != Some(input) {
                        continue;
                    }
                    if down {
                        if *held || down_at.is_some() {
                            // A repeat, or pressed again while still down: one press.
                            // A hold's let-go waiting on HOLD_MIN waits for the key again.
                            *held = true;
                            if *press == Press::Hold {
                                *up_at = None;
                            }
                            continue;
                        }
                        if made >= room {
                            continue;
                        }
                        made += 1;
                        *held = true;
                        *down_at = Some(now);
                        *up_at = if *press == Press::Tap { Some(now + TAP) } else { None };
                    } else {
                        *held = false;
                        if *press == Press::Hold {
                            if let Some(d) = *down_at {
                                *up_at = Some((d + HOLD_MIN).max(now));
                            }
                        }
                    }
                }
                Ctl::Look { by: LookBy::Hold, key, held, freed, finger, relift_at, .. } => {
                    if *key != Some(input) {
                        continue;
                    }
                    *held = down;
                    if !down {
                        *freed = false;
                        *finger = None;
                        *relift_at = None;
                    }
                }
                Ctl::Look { .. } => {}
            }
        }
        // The sticks, from what their keys hold now.
        for c in &mut self.ctls {
            if let Ctl::Stick { centre, radius, held, finger, path, .. } = c {
                stick(*centre, *radius, held, finger, path, now);
            }
        }
        // A held look taking over from the one that has the mouse, or giving it back: one finger moves.
        self.lift_still_looks();
        if self.captured() {
            self.pointer_down = false;
        }
    }

    /// The one look the mouse moves now: a held `Hold` look — the first, if
    /// more are held — over the `Always` look that has the mouse, which waits
    /// with its finger up until the key is let go. Never two at once.
    fn moving(&self) -> Option<usize> {
        self.ctls.iter().position(|c| matches!(c, Ctl::Look { by: LookBy::Hold, held: true, freed: false, .. })).or(self.taken)
    }

    /// Every look but the one the mouse moves, its finger up.
    fn lift_still_looks(&mut self) {
        let moving = self.moving();
        for (i, c) in self.ctls.iter_mut().enumerate() {
            if let Ctl::Look { finger, relift_at, pending, .. } = c {
                if Some(i) != moving {
                    *finger = None;
                    *relift_at = None;
                    *pending = (0.0, 0.0);
                }
            }
        }
    }

    /// `key`, one that takes the mouse, pressed: the mouse to the first
    /// `Always` look with that key — moved from another look that had it — or,
    /// if one of this key's looks has it, to the next of them; after the last,
    /// given back.
    fn take_mouse(&mut self, key: Input) {
        let looks: Vec<usize> = self
            .ctls
            .iter()
            .enumerate()
            .filter(|(_, c)| matches!(c, Ctl::Look { by: LookBy::Always, key: k, .. } if mouse_key(*k) == key))
            .map(|(i, _)| i)
            .collect();
        let next = match self.taken.and_then(|t| looks.iter().position(|&i| i == t)) {
            Some(p) => looks.get(p + 1).copied(),
            None => looks.first().copied(),
        };
        // After the last, given back — by these looks only: a `Hold` look held meanwhile goes on, as its key says.
        self.taken = next;
        // The look that had it lets its finger go: the mouse moves one finger at a time.
        self.lift_still_looks();
        if next.is_some() {
            self.pointer_down = false;
        }
    }

    /// The mouse given back: an `Always` look's finger let go, and a held look's too until its key is pressed again.
    fn free(&mut self) {
        self.taken = None;
        for c in &mut self.ctls {
            if let Ctl::Look { by, held, freed, finger, relift_at, .. } = c {
                if *by == LookBy::Always || *held {
                    if *held {
                        *freed = true;
                    }
                    *finger = None;
                    *relift_at = None;
                }
            }
        }
    }

    fn delta(&mut self, dx: f32, dy: f32, now: Duration) {
        // Only the one the mouse moves: its movement is one finger's.
        let moving = self.moving();
        let scale = self.dpi as f32 / 160.0;
        let room = MAX_FINGERS.saturating_sub(self.down_count());
        for (i, c) in self.ctls.iter_mut().enumerate() {
            if let Ctl::Look { centre, finger, moved_at, relift_at, pending, speed, moved_last, .. } = c {
                if moving != Some(i) {
                    continue;
                }
                if finger.is_none() && relift_at.is_none() {
                    if room == 0 {
                        continue;
                    }
                    // The first movement: the finger down at the area's centre; the movement follows a tick later,
                    // so the game sees it go down and then move.
                    *finger = Some(*centre);
                    *pending = (0.0, 0.0);
                    *moved_last = now;
                }
                *moved_at = now;
                pending.0 += dx * *speed * scale;
                pending.1 += dy * *speed * scale;
            }
        }
    }

    pub fn tick(&mut self, now: Duration) {
        for c in &mut self.ctls {
            match c {
                Ctl::Stick { finger, path, .. } => {
                    while let Some(&(at, p)) = path.first() {
                        if at > now {
                            break;
                        }
                        *finger = Some(p);
                        path.remove(0);
                    }
                }
                Ctl::Button { down_at, up_at, held, press, .. } => {
                    if let Some(u) = *up_at {
                        if now >= u && (*press == Press::Tap || !*held) {
                            *down_at = None;
                            *up_at = None;
                        }
                    }
                }
                Ctl::Look { centre, half, finger, moved_at, relift_at, pending, moved_last, .. } => {
                    if let Some(r) = *relift_at {
                        if now >= r {
                            *relift_at = None;
                            *finger = Some(*centre);
                            *pending = (0.0, 0.0);
                            *moved_at = now;
                            *moved_last = now;
                        }
                        continue;
                    }
                    let Some(p) = *finger else { continue };
                    if now.saturating_sub(*moved_at) >= LOOK_IDLE {
                        *finger = None;
                        *pending = (0.0, 0.0);
                        continue;
                    }
                    if *pending != (0.0, 0.0) && now.saturating_sub(*moved_last) >= MOVE_EVERY {
                        let q = (p.0 + pending.0, p.1 + pending.1);
                        *pending = (0.0, 0.0);
                        *moved_last = now;
                        let edge = (half.0 * 2.0 * LOOK_EDGE, half.1 * 2.0 * LOOK_EDGE);
                        let inside = (q.0 - centre.0).abs() < half.0 - edge.0 && (q.1 - centre.1).abs() < half.1 - edge.1;
                        if inside {
                            *finger = Some(q);
                        } else {
                            // Near the edge: up, and down at the centre a tick later, as a thumb would.
                            *finger = None;
                            *relift_at = Some(now + LOOK_RELIFT);
                        }
                    }
                }
            }
        }
    }

    /// The fingers that should be down now, ascending id.
    pub fn fingers(&self) -> Vec<Finger> {
        let (w, h) = (self.size.0.max(1) as f32, self.size.1.max(1) as f32);
        let f = |id: usize, p: Pt| Finger { id: id as u8, x: p.0.clamp(0.0, w - 1.0).round() as u16, y: p.1.clamp(0.0, h - 1.0).round() as u16 };
        let mut out = Vec::new();
        if self.pointer_down {
            out.push(f(0, self.pointer));
        }
        for (i, c) in self.ctls.iter().enumerate() {
            let p = match c {
                Ctl::Stick { finger, .. } => *finger,
                Ctl::Button { at, down_at, .. } => down_at.map(|_| *at),
                Ctl::Look { finger, .. } => *finger,
            };
            if let Some(p) = p {
                out.push(f(i + 1, p));
            }
        }
        out.truncate(MAX_FINGERS);
        out
    }

    /// When `tick` is next needed; None while nothing is waiting on time.
    pub fn deadline(&self) -> Option<Duration> {
        let mut d: Option<Duration> = None;
        let mut at = |t: Duration| d = Some(d.map_or(t, |x| x.min(t)));
        for c in &self.ctls {
            match c {
                Ctl::Stick { path, .. } => {
                    if let Some((t, _)) = path.first() {
                        at(*t);
                    }
                }
                Ctl::Button { up_at: Some(u), .. } => at(*u),
                Ctl::Button { .. } => {}
                Ctl::Look { finger, relift_at, moved_at, pending, moved_last, .. } => {
                    if let Some(r) = relift_at {
                        at(*r);
                    } else if finger.is_some() {
                        at(*moved_at + LOOK_IDLE);
                        if *pending != (0.0, 0.0) {
                            at(*moved_last + MOVE_EVERY);
                        }
                    }
                }
            }
        }
        d
    }
}

/// A look area as far as it lies in a picture `w` × `h`: its centre and half
/// size. One larger than the picture, or past its edge, would hold its finger
/// at the picture's edge, never near enough to its own to be lifted.
fn fit(c: Pt, half: Pt, w: f32, h: f32) -> (Pt, Pt) {
    let span = |c: f32, r: f32, len: f32| {
        let len = len.max(0.0);
        let (mut lo, mut hi) = ((c - r).max(0.0), (c + r).min(len));
        let min = LOOK_MIN.min(len);
        if !(hi - lo >= min) {
            lo = (c.clamp(0.0, len) - min / 2.0).clamp(0.0, len - min);
            hi = lo + min;
        }
        ((lo + hi) / 2.0, (hi - lo) / 2.0)
    };
    let (cx, hx) = span(c.0, half.0, w);
    let (cy, hy) = span(c.1, half.1, h);
    ((cx, cy), (hx, hy))
}

/// A stick's finger, from the four keys held: down at the centre and out to
/// where they point in STICK_STEPS, turned in STICK_TURN_STEPS, up when none is held.
fn stick(centre: Pt, radius: f32, held: &[bool; 4], finger: &mut Option<Pt>, path: &mut Vec<(Duration, Pt)>, now: Duration) {
    let [up, left, down, right] = *held;
    if !(up || left || down || right) {
        *finger = None;
        path.clear();
        return;
    }
    let (vx, vy) = ((right as i8 - left as i8) as f32, (down as i8 - up as i8) as f32);
    let len = (vx * vx + vy * vy).sqrt();
    let target = if len == 0.0 {
        centre
    } else {
        (centre.0 + vx / len * STICK_REACH * radius, centre.1 + vy / len * STICK_REACH * radius)
    };
    // Already going there: nothing new.
    let going = path.last().map(|(_, p)| *p).or(*finger);
    if going == Some(target) {
        return;
    }
    let (from, steps) = match *finger {
        None => {
            *finger = Some(centre);
            (centre, STICK_STEPS)
        }
        Some(p) => (p, STICK_TURN_STEPS),
    };
    path.clear();
    for k in 1..=steps {
        let t = k as f32 / steps as f32;
        path.push((now + STICK_STEP * k, (from.0 + (target.0 - from.0) * t, from.1 + (target.1 - from.1) * t)));
    }
}

#[cfg(test)]
mod tests {
    use super::super::layout::{Anchor, At};
    use super::*;

    const fn ms(n: u64) -> Duration {
        Duration::from_millis(n)
    }
    fn key(s: &str) -> Option<Input> {
        Input::named(s)
    }
    fn press(e: &mut Engine, s: &str, down: bool, t: u64) {
        e.handle(Event::Input { input: key(s).unwrap(), down }, ms(t));
    }
    fn at(from: Anchor, dx: f32, dy: f32) -> At {
        At { from, dx, dy }
    }
    /// Runs the engine's own wake-ups up to `t`.
    fn run(e: &mut Engine, t: u64) {
        while let Some(d) = e.deadline() {
            if d > ms(t) {
                break;
            }
            e.tick(d);
        }
        e.tick(ms(t));
    }
    fn wasd() -> Control {
        Control::Stick { at: at(Anchor::BottomLeft, 0.3, -0.3), size: 0.15, up: key("KeyW"), left: key("KeyA"), down: key("KeyS"), right: key("KeyD") }
    }
    fn button(k: &str, p: Press) -> Control {
        Control::Button { at: at(Anchor::BottomRight, -0.2, -0.2), key: key(k), press: p }
    }
    fn look(by: LookBy, k: Option<&str>, speed: f32) -> Control {
        Control::Look { at: at(Anchor::Right, -0.4, 0.0), w: 0.6, h: 0.6, by, key: k.and_then(key), speed }
    }

    #[test]
    fn a_stick_goes_down_at_its_centre_and_out_in_four_steps() {
        let mut e = Engine::new(&[wasd()], (1280, 720), 160);
        let centre = (216, 504); // (0.3·720, 720 − 0.3·720)
        press(&mut e, "KeyW", true, 0);
        assert_eq!(e.fingers(), vec![Finger { id: 1, x: centre.0, y: centre.1 }]);
        let reach: f32 = 0.9 * 0.15 * 720.0; // 97.2
        let mut ys = vec![];
        for t in [8, 16, 24, 32] {
            run(&mut e, t);
            ys.push(e.fingers()[0].y);
        }
        assert_eq!(ys, [(504.0 - reach / 4.0).round() as u16, (504.0 - reach / 2.0).round() as u16, (504.0 - reach * 0.75).round() as u16, (504.0 - reach).round() as u16]);
        assert_eq!(e.deadline(), None);
        // W then W+D: a turn, in two steps.
        press(&mut e, "KeyD", true, 100);
        run(&mut e, 108);
        let mid = e.fingers()[0];
        run(&mut e, 116);
        let end = e.fingers()[0];
        let d = reach / 2f32.sqrt();
        assert_eq!((end.x, end.y), ((216.0 + d).round() as u16, (504.0 - d).round() as u16));
        assert!(mid.x > centre.0 && mid.x < end.x);
        // W+S with D up: the centre.
        press(&mut e, "KeyD", false, 200);
        press(&mut e, "KeyS", true, 200);
        run(&mut e, 220);
        assert_eq!((e.fingers()[0].x, e.fingers()[0].y), centre);
        // All up: lifted.
        press(&mut e, "KeyW", false, 300);
        press(&mut e, "KeyS", false, 300);
        assert!(e.fingers().is_empty());
    }

    #[test]
    fn a_hold_button_lasts_at_least_forty_ms() {
        let mut e = Engine::new(&[button("Space", Press::Hold)], (1280, 720), 160);
        press(&mut e, "Space", true, 0);
        assert_eq!(e.fingers().len(), 1);
        press(&mut e, "Space", false, 5);
        run(&mut e, 39);
        assert_eq!(e.fingers().len(), 1, "still down at 39 ms");
        run(&mut e, 40);
        assert!(e.fingers().is_empty(), "up at 40 ms");
        // Held long: up with the key.
        press(&mut e, "Space", true, 100);
        press(&mut e, "Space", true, 130); // a repeat: one press
        run(&mut e, 500);
        assert_eq!(e.fingers().len(), 1);
        press(&mut e, "Space", false, 600);
        assert!(e.fingers().is_empty());
    }

    #[test]
    fn a_hold_pressed_again_before_forty_ms_waits_for_its_key() {
        let mut e = Engine::new(&[button("Space", Press::Hold)], (1280, 720), 160);
        press(&mut e, "Space", true, 0);
        press(&mut e, "Space", false, 5);
        press(&mut e, "Space", true, 10);
        // Nothing is waiting on time while the key is held: no wake-up in the past, no spinning.
        assert_eq!(e.deadline(), None);
        run(&mut e, 200);
        assert_eq!(e.fingers().len(), 1);
        press(&mut e, "Space", false, 300);
        assert!(e.fingers().is_empty());
    }

    #[test]
    fn a_fire_button_on_the_left_click_is_let_go_after_the_mouse_is_given_back() {
        let fire = Control::Button { at: at(Anchor::BottomRight, -0.2, -0.2), key: key("MouseLeft"), press: Press::Hold };
        let mut e = Engine::new(&[look(LookBy::Hold, Some("MouseRight"), 1.0), fire], (1280, 720), 160);
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(0));
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: true }, ms(10));
        assert_eq!(e.fingers().iter().map(|f| f.id).collect::<Vec<_>>(), [2], "fire down, no finger 0");
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: false }, ms(100));
        assert!(!e.captured());
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: false }, ms(110));
        assert!(e.fingers().is_empty(), "fire let go");
    }

    #[test]
    fn a_tap_is_up_at_fifty_ms_whatever_the_key_does() {
        let mut e = Engine::new(&[button("KeyE", Press::Tap)], (1280, 720), 160);
        press(&mut e, "KeyE", true, 0);
        run(&mut e, 49);
        assert_eq!(e.fingers().len(), 1);
        run(&mut e, 50);
        assert!(e.fingers().is_empty(), "up at 50 ms with the key still held");
        press(&mut e, "KeyE", true, 80); // the key's repeat
        run(&mut e, 300);
        assert!(e.fingers().is_empty(), "a held key taps once");
        press(&mut e, "KeyE", false, 310);
        press(&mut e, "KeyE", true, 320);
        assert_eq!(e.fingers().len(), 1, "the next press taps again");
    }

    #[test]
    fn three_controls_are_three_fingers_and_one_let_go_leaves_the_others() {
        let mut e = Engine::new(&[wasd(), look(LookBy::Hold, Some("MouseRight"), 1.0), button("Space", Press::Hold)], (1280, 720), 160);
        press(&mut e, "KeyW", true, 0);
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(1));
        assert!(e.captured());
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(2));
        press(&mut e, "Space", true, 3);
        run(&mut e, 40);
        let f = e.fingers();
        assert_eq!(f.iter().map(|f| f.id).collect::<Vec<_>>(), [1, 2, 3]);
        press(&mut e, "Space", false, 60);
        run(&mut e, 61);
        let g = e.fingers();
        assert_eq!(g.iter().map(|f| f.id).collect::<Vec<_>>(), [1, 2]);
        assert_eq!(g[0], f[0]);
        assert_eq!(g[1], f[1]);
    }

    #[test]
    fn a_look_area_drags_a_finger_by_the_mouse() {
        let mut e = Engine::new(&[look(LookBy::Hold, Some("MouseRight"), 2.0)], (1280, 720), 320);
        let centre = (1280.0 - 0.4 * 720.0, 360.0); // (992, 360)
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(0));
        assert!(e.fingers().is_empty(), "no finger until the mouse moves");
        e.handle(Event::Delta { dx: 5.0, dy: -2.0 }, ms(10));
        assert_eq!(e.fingers()[0], Finger { id: 1, x: 992, y: 360 });
        e.handle(Event::Delta { dx: 5.0, dy: 0.0 }, ms(12));
        run(&mut e, 18);
        // 10 points across, 2 up, × speed 2 × 320/160: 40 and −8 pixels.
        assert_eq!(e.fingers()[0], Finger { id: 1, x: (centre.0 + 40.0) as u16, y: (centre.1 - 8.0) as u16 });
        // Out to the edge: lifted, and down at the centre a tick later.
        e.handle(Event::Delta { dx: 50.0, dy: 0.0 }, ms(30));
        assert!(e.fingers().is_empty(), "lifted at the edge");
        run(&mut e, 37);
        assert!(e.fingers().is_empty());
        run(&mut e, 38);
        assert_eq!(e.fingers()[0], Finger { id: 1, x: 992, y: 360 });
        // 150 ms of nothing: lifted.
        run(&mut e, 38 + 149);
        assert_eq!(e.fingers().len(), 1);
        run(&mut e, 38 + 150);
        assert!(e.fingers().is_empty());
        // Let go of the key: no more look.
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: false }, ms(300));
        assert!(!e.captured());
        e.handle(Event::Delta { dx: 5.0, dy: 0.0 }, ms(310));
        assert!(e.fingers().is_empty());
    }

    #[test]
    fn the_key_under_esc_takes_the_mouse_and_esc_gives_it_back() {
        let mut e = Engine::new(&[look(LookBy::Always, None, 1.0)], (1280, 720), 160);
        // An `Always` look starts free: a click is a finger.
        assert!(!e.captured());
        assert!(e.bound().contains(&Input::Key(UNDER_ESCAPE)));
        e.handle(Event::Pointer { x: 100, y: 200 }, ms(0));
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: true }, ms(1));
        assert_eq!(e.fingers(), vec![Finger { id: 0, x: 100, y: 200 }]);
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: false }, ms(2));
        assert!(e.fingers().is_empty());
        // The key under Esc takes it.
        e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: true }, ms(10));
        assert!(e.captured() && e.taken == Some(0));
        e.handle(Event::Delta { dx: 2.0, dy: 2.0 }, ms(11));
        assert_eq!(e.fingers().len(), 1);
        // A click while taken makes no finger 0.
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: true }, ms(12));
        assert!(e.fingers().iter().all(|f| f.id != 0));
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: false }, ms(13));
        // Esc gives it back.
        e.handle(Event::Input { input: Input::Key(ESCAPE), down: true }, ms(20));
        assert!(!e.captured());
        assert!(e.fingers().is_empty());
        // The key under Esc again, and again: takes, gives back.
        e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: true }, ms(30));
        assert!(e.captured());
        e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: false }, ms(31));
        e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: true }, ms(40));
        assert!(!e.captured());
        // A click never takes it.
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: true }, ms(50));
        assert!(!e.captured());
        assert_eq!(e.fingers()[0].id, 0);
    }

    #[test]
    fn an_always_look_may_have_its_own_key_for_the_mouse() {
        // A JIS keyboard has no key under Esc: the look's card chose Q.
        let mut e = Engine::new(&[look(LookBy::Always, Some("KeyQ"), 1.0)], (1280, 720), 160);
        assert_eq!(mouse_keys(&e.controls), [key("KeyQ").unwrap()]);
        assert!(e.bound().contains(&key("KeyQ").unwrap()));
        assert!(!e.bound().contains(&Input::Key(UNDER_ESCAPE)), "the key under Esc is the app's again");
        e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: true }, ms(0));
        assert!(!e.captured());
        press(&mut e, "KeyQ", true, 10);
        assert!(e.captured());
        press(&mut e, "KeyQ", false, 11);
        press(&mut e, "KeyQ", true, 20);
        assert!(!e.captured());
        press(&mut e, "KeyQ", true, 30);
        e.handle(Event::Input { input: Input::Key(ESCAPE), down: true }, ms(31));
        assert!(!e.captured(), "Esc gives it back as ever");
        // Without one of its own, the key under Esc.
        assert_eq!(mouse_keys(&[look(LookBy::Always, None, 1.0)]), [Input::Key(UNDER_ESCAPE)]);
        assert!(mouse_keys(&[look(LookBy::Hold, Some("MouseRight"), 1.0)]).is_empty());
    }

    /// Two `Always` looks, each with its own key: in the left and the right half of the picture.
    fn two_looks(first: Option<&str>, second: Option<&str>) -> [Control; 2] {
        let area = |from, k: Option<&str>| Control::Look { at: at(from, 0.0, 0.0), w: 0.5, h: 0.5, by: LookBy::Always, key: k.and_then(key), speed: 1.0 };
        [area(Anchor::Left, first), area(Anchor::Right, second)]
    }

    #[test]
    fn each_always_look_s_key_takes_the_mouse_for_that_look() {
        let mut e = Engine::new(&two_looks(Some("KeyQ"), Some("KeyE")), (1280, 720), 160);
        assert_eq!(mouse_keys(&e.controls), [key("KeyQ").unwrap(), key("KeyE").unwrap()]);
        let b = e.bound();
        assert!(b.contains(&key("KeyQ").unwrap()) && b.contains(&key("KeyE").unwrap()), "neither key is dead");
        assert!(!b.contains(&Input::Key(UNDER_ESCAPE)));
        let ids = |e: &Engine| e.fingers().iter().map(|f| f.id).collect::<Vec<_>>();
        // Q: the first look only.
        press(&mut e, "KeyQ", true, 0);
        press(&mut e, "KeyQ", false, 1);
        assert!(e.captured() && e.taken == Some(0));
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(2));
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(12));
        run(&mut e, 20);
        assert_eq!(ids(&e), [1], "one finger, the first look's");
        // E: the mouse moves to the second; the first's finger goes.
        press(&mut e, "KeyE", true, 30);
        press(&mut e, "KeyE", false, 31);
        assert!(e.captured() && e.taken == Some(1));
        assert!(e.fingers().is_empty(), "the first look let go");
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(32));
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(42));
        run(&mut e, 50);
        assert_eq!(ids(&e), [2], "one finger, the second look's");
        // Q again: back to the first, the second let go.
        press(&mut e, "KeyQ", true, 60);
        assert_eq!(e.taken, Some(0));
        assert!(e.fingers().is_empty());
        press(&mut e, "KeyQ", false, 61);
        // E, then E again: the second, then the mouse given back.
        press(&mut e, "KeyE", true, 70);
        press(&mut e, "KeyE", false, 71);
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(72));
        assert_eq!(ids(&e), [2]);
        press(&mut e, "KeyE", true, 80);
        assert!(!e.captured() && e.taken.is_none());
        assert!(e.fingers().is_empty());
        press(&mut e, "KeyE", false, 81);
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(82));
        assert!(e.fingers().is_empty(), "given back: the mouse moves nobody");
        // Esc gives it back, whichever has it.
        press(&mut e, "KeyE", true, 90);
        assert!(e.captured());
        e.handle(Event::Input { input: Input::Key(ESCAPE), down: true }, ms(91));
        assert!(!e.captured());
        press(&mut e, "KeyQ", true, 100);
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(101));
        e.handle(Event::Input { input: Input::Key(ESCAPE), down: true }, ms(102));
        assert!(!e.captured() && e.fingers().is_empty());
    }

    #[test]
    fn looks_that_share_a_key_take_the_mouse_in_turn_then_give_it_back() {
        // Both left on the key under Esc, as a file or the editor may leave them.
        let mut e = Engine::new(&two_looks(None, None), (1280, 720), 160);
        assert_eq!(mouse_keys(&e.controls), [Input::Key(UNDER_ESCAPE)]);
        assert!(e.bound().contains(&Input::Key(UNDER_ESCAPE)));
        let under = |e: &mut Engine, t| {
            e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: true }, ms(t));
            e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: false }, ms(t + 1));
        };
        under(&mut e, 0);
        assert_eq!(e.taken, Some(0));
        e.handle(Event::Delta { dx: 1.0, dy: 0.0 }, ms(2));
        assert_eq!(e.fingers().iter().map(|f| f.id).collect::<Vec<_>>(), [1]);
        under(&mut e, 10);
        assert_eq!(e.taken, Some(1), "the second, reachable");
        e.handle(Event::Delta { dx: 1.0, dy: 0.0 }, ms(12));
        assert_eq!(e.fingers().iter().map(|f| f.id).collect::<Vec<_>>(), [2]);
        under(&mut e, 20);
        assert!(!e.captured() && e.fingers().is_empty(), "after the last, given back");
        // A look with its own key, and two on the key under Esc: each key goes only through its own.
        let mut e = Engine::new(&[two_looks(None, Some("KeyQ"))[0].clone(), two_looks(None, Some("KeyQ"))[1].clone(), look(LookBy::Always, None, 1.0)], (1280, 720), 160);
        press(&mut e, "KeyQ", true, 0);
        assert_eq!(e.taken, Some(1));
        under(&mut e, 10);
        assert_eq!(e.taken, Some(0));
        under(&mut e, 20);
        assert_eq!(e.taken, Some(2));
        press(&mut e, "KeyQ", false, 25);
        press(&mut e, "KeyQ", true, 30);
        assert_eq!(e.taken, Some(1));
    }

    #[test]
    fn a_look_area_larger_than_the_picture_is_kept_to_it_and_never_stalls() {
        // An imported file: 8 shorter sides wide, on a 1280 × 720 picture.
        let big = Control::Look { at: at(Anchor::Center, 0.0, 0.0), w: 8.0, h: 8.0, by: LookBy::Hold, key: key("MouseRight"), speed: 1.0 };
        let mut e = Engine::new(&[big], (1280, 720), 160);
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(0));
        let (mut at_edge, mut back, mut last_x, mut t) = (0, 0, 0u16, 1);
        for _ in 0..60 {
            e.handle(Event::Delta { dx: 40.0, dy: 0.0 }, ms(t));
            run(&mut e, t + 9);
            if let Some(f) = e.fingers().first() {
                if f.x >= 1279 {
                    at_edge += 1;
                }
                if f.x + 100 < last_x {
                    back += 1;
                }
                last_x = f.x;
            }
            t += 10;
        }
        assert_eq!(at_edge, 0, "the finger sat at the picture's edge");
        assert!(back >= 3, "it was lifted near the edge and put down at the centre again ({back} times)");
        // An area hanging off the picture's edge, and one of no size: inside it, never thinner than LOOK_MIN.
        assert_eq!(fit((1270.0, 360.0), (100.0, 50.0), 1280.0, 720.0), ((1225.0, 360.0), (55.0, 50.0)));
        assert_eq!(fit((640.0, 360.0), (0.0, 0.0), 1280.0, 720.0), ((640.0, 360.0), (LOOK_MIN / 2.0, LOOK_MIN / 2.0)));
        assert_eq!(fit((1280.0, 0.0), (0.0, 0.0), 1280.0, 720.0), ((1280.0 - LOOK_MIN / 2.0, LOOK_MIN / 2.0), (LOOK_MIN / 2.0, LOOK_MIN / 2.0)));
        assert_eq!(fit((0.0, 0.0), (5.0, 5.0), 0.0, 0.0), ((0.0, 0.0), (0.0, 0.0)), "a picture of no size");
    }

    /// From the second review's scratch runs: odd movement and a display of no
    /// size neither panic nor leave a wake-up that never clears.
    #[test]
    fn nan_and_infinite_movement_and_a_display_of_no_size_are_survived() {
        let settles = |e: &mut Engine, until: u64| {
            for _ in 0..10_000 {
                match e.deadline() {
                    Some(d) if d <= ms(until) => e.tick(d),
                    _ => return true,
                }
            }
            false
        };
        let area = Control::Look { at: at(Anchor::Center, 0.0, 0.0), w: 0.5, h: 0.5, by: LookBy::Hold, key: key("MouseRight"), speed: 1.0 };
        let mut e = Engine::new(&[area.clone()], (1280, 720), 160);
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(0));
        for (t, dx) in [(1, f32::NAN), (30, 3.0), (50, f32::INFINITY), (80, f32::NEG_INFINITY)] {
            e.handle(Event::Delta { dx, dy: 0.0 }, ms(t));
            assert!(settles(&mut e, t + 20), "a wake-up that never clears after {dx}");
            assert!(e.fingers().iter().all(|f| f.x < 1280 && f.y < 720));
        }
        let mut z = Engine::new(&[area, wasd(), button("Space", Press::Tap)], (0, 0), 0);
        z.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(0));
        z.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(1));
        press(&mut z, "KeyW", true, 2);
        press(&mut z, "Space", true, 3);
        assert!(settles(&mut z, 300));
        assert!(z.fingers().iter().all(|f| f.x == 0 && f.y == 0));
    }

    #[test]
    fn a_held_look_takes_over_from_the_always_look_one_finger_at_a_time() {
        let always = Control::Look { at: at(Anchor::Left, 0.0, 0.0), w: 0.5, h: 0.5, by: LookBy::Always, key: key("KeyQ"), speed: 1.0 };
        let held = Control::Look { at: at(Anchor::Right, 0.0, 0.0), w: 0.5, h: 0.5, by: LookBy::Hold, key: key("MouseRight"), speed: 1.0 };
        let mut e = Engine::new(&[always, held], (1280, 720), 160);
        let ids = |e: &Engine| e.fingers().iter().map(|f| f.id).collect::<Vec<_>>();
        let moved = |e: &mut Engine, t: u64| e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(t));
        press(&mut e, "KeyQ", true, 0);
        press(&mut e, "KeyQ", false, 1);
        moved(&mut e, 2);
        assert_eq!(ids(&e), [1], "the always look's finger");
        // The right button held: its look moves, and the always look's finger goes up — never two.
        press(&mut e, "MouseRight", true, 10);
        assert!(e.fingers().is_empty());
        moved(&mut e, 11);
        moved(&mut e, 21);
        run(&mut e, 30);
        assert_eq!(ids(&e), [2], "only the held look's");
        // Let go: back to the always look, which still has the mouse.
        press(&mut e, "MouseRight", false, 40);
        assert!(e.captured());
        moved(&mut e, 41);
        assert_eq!(ids(&e), [1]);
        // Held again, then Q gives the always look's mouse back: the held look goes on, not freed as by Esc.
        press(&mut e, "MouseRight", true, 50);
        press(&mut e, "KeyQ", true, 51);
        press(&mut e, "KeyQ", false, 52);
        assert!(e.taken.is_none() && e.captured(), "the held look still has the mouse");
        moved(&mut e, 53);
        assert_eq!(ids(&e), [2]);
        // Esc still frees it until its button is pressed again.
        e.handle(Event::Input { input: Input::Key(ESCAPE), down: true }, ms(60));
        moved(&mut e, 61);
        assert!(e.fingers().is_empty() && !e.captured());
    }

    #[test]
    fn keys_held_from_before_are_pressed_again_on_a_new_engine() {
        let controls = [wasd(), button("Space", Press::Hold), look(LookBy::Always, Some("KeyQ"), 1.0)];
        let held: HashSet<Input> = ["KeyW", "Space", "KeyQ", "KeyZ"].iter().filter_map(|k| key(k)).chain([Input::Key(ESCAPE)]).collect();
        // F8 off and on with W, Space and Q held: the new engine holds W and Space at once.
        let mut e = Engine::new(&controls, (1280, 720), 160);
        e.press_again(&held, ms(0));
        run(&mut e, 100);
        assert_eq!(e.fingers().iter().map(|f| f.id).collect::<Vec<_>>(), [1, 2], "the stick and the button");
        assert!(!e.captured(), "a key that takes the mouse is not taken again by being held");
        // Their releases work as ever.
        press(&mut e, "KeyW", false, 110);
        press(&mut e, "Space", false, 111);
        run(&mut e, 200);
        assert!(e.fingers().is_empty());
        // A new size lets go of everything; what is still held is held again.
        press(&mut e, "KeyW", true, 210);
        e.handle(Event::Size { w: 1920, h: 1080, dpi: 320 }, ms(220));
        assert!(e.fingers().is_empty(), "the size lets go");
        let w: HashSet<Input> = [key("KeyW").unwrap()].into();
        e.press_again(&w, ms(220));
        run(&mut e, 300);
        assert_eq!(e.fingers().iter().map(|f| f.id).collect::<Vec<_>>(), [1]);
    }

    #[test]
    fn esc_frees_a_held_look_until_its_key_is_pressed_again() {
        let mut e = Engine::new(&[look(LookBy::Hold, Some("MouseRight"), 1.0)], (1280, 720), 160);
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(0));
        assert!(e.captured());
        e.handle(Event::Input { input: Input::Key(ESCAPE), down: true }, ms(5));
        assert!(!e.captured());
        e.handle(Event::Delta { dx: 3.0, dy: 0.0 }, ms(6));
        assert!(e.fingers().is_empty());
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: false }, ms(10));
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(20));
        assert!(e.captured());
        // Without an `Always` look the key under Esc is nobody's.
        assert!(!e.bound().contains(&Input::Key(UNDER_ESCAPE)));
    }

    #[test]
    fn a_left_drag_with_the_pointer_free_is_finger_zero_and_never_while_captured() {
        let mut e = Engine::new(&[look(LookBy::Hold, Some("MouseRight"), 1.0)], (1280, 720), 160);
        e.handle(Event::Pointer { x: 50, y: 60 }, ms(0));
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: true }, ms(1));
        e.handle(Event::Pointer { x: 70, y: 90 }, ms(5));
        assert_eq!(e.fingers(), vec![Finger { id: 0, x: 70, y: 90 }]);
        // The look's key while the left is held: the mouse is taken, finger 0 lifted.
        e.handle(Event::Input { input: key("MouseRight").unwrap(), down: true }, ms(10));
        assert!(e.fingers().iter().all(|f| f.id != 0));
        e.handle(Event::Input { input: Input::Mouse(MouseButton::Left), down: true }, ms(11));
        assert!(e.fingers().iter().all(|f| f.id != 0));
    }

    #[test]
    fn blur_lifts_everything_and_frees_the_pointer() {
        let mut e = Engine::new(&[wasd(), look(LookBy::Always, None, 1.0), button("Space", Press::Hold)], (1280, 720), 160);
        press(&mut e, "KeyW", true, 0);
        press(&mut e, "Space", true, 0);
        e.handle(Event::Input { input: Input::Key(UNDER_ESCAPE), down: true }, ms(1));
        e.handle(Event::Delta { dx: 1.0, dy: 1.0 }, ms(2));
        assert_eq!(e.fingers().len(), 3);
        e.handle(Event::Blur, ms(3));
        assert!(e.fingers().is_empty());
        assert!(!e.captured());
        assert_eq!(e.deadline(), None);
        // And the keys held before count for nothing: W's let-go lifts nothing, W again goes down anew.
        press(&mut e, "KeyW", false, 10);
        press(&mut e, "KeyW", true, 20);
        assert_eq!(e.fingers().len(), 1);
    }

    #[test]
    fn a_bottom_right_button_keeps_its_distance_from_the_corner_in_heights() {
        let b = [button("Space", Press::Hold)];
        let mut e = Engine::new(&b, (1280, 720), 160);
        press(&mut e, "Space", true, 0);
        let f = e.fingers()[0];
        let near = ((1280 - f.x) as f32 / 720.0, (720 - f.y) as f32 / 720.0);
        e.handle(Event::Size { w: 2560, h: 1080, dpi: 160 }, ms(10));
        press(&mut e, "Space", true, 20);
        let g = e.fingers()[0];
        let far = ((2560 - g.x) as f32 / 1080.0, (1080 - g.y) as f32 / 1080.0);
        assert!((near.0 - far.0).abs() < 0.002 && (near.1 - far.1).abs() < 0.002, "{near:?} vs {far:?}");
    }

    #[test]
    fn an_eleventh_finger_is_not_made() {
        let names = ["KeyQ", "KeyW", "KeyE", "KeyR", "KeyT", "KeyY", "KeyU", "KeyI", "KeyO", "KeyP", "KeyZ"];
        let controls: Vec<Control> = names.iter().map(|k| button(k, Press::Hold)).collect();
        let mut e = Engine::new(&controls, (1280, 720), 160);
        for (i, k) in names.iter().enumerate() {
            press(&mut e, k, true, i as u64);
        }
        assert_eq!(e.fingers().len(), MAX_FINGERS);
        assert!(e.fingers().iter().all(|f| f.id <= 10));
    }

    #[test]
    fn input_names_read_back() {
        for n in ["KeyW", "Space", "MouseRight", "WheelDown", "ShiftLeft", "MouseLeft"] {
            assert_eq!(Input::named(n).unwrap().name(), n);
        }
        assert!(!Input::named("Escape").unwrap().bindable());
        assert!(Input::named("MouseBack").unwrap().bindable());
    }
}
