//! Game controls: keyboard and mouse for touch-only games, in an Android
//! app's window (doc/game-mode.md).
//!
//! Each Android app's window has a [`Session`]. While its controls are on,
//! the window hands it the keys a control is bound to and every mouse event,
//! and sends the phone no mouse of its own; the session runs the engine
//! (engine.rs) on a thread of its own and sends the fingers it makes on the
//! window's connection (Wire's 0x24), and asks the window to capture the
//! pointer while a look area wants it. The controls themselves are the game's
//! file (layout.rs), set up in the editor (editor.rs).

pub mod editor;
pub mod engine;
#[cfg(debug_assertions)]
pub mod hook;
pub mod keys;
pub mod layout;

use std::collections::{HashMap, HashSet};
use std::hash::Hash;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex, Weak};
use std::time::{Duration, Instant};

use serde_json::json;
use tauri::Manager;

use super::link::Link;
use super::wire;
use engine::{Engine, Event, Finger, Input};
use layout::{Control, Layout, Shape};

/// What a session asks of its native window. Called from the session's
/// threads, never the window's own: each does its part on the window's thread.
pub trait Shell: Send + Sync {
    /// Take the pointer for looking around (hidden, held in the picture, its
    /// movement as deltas), or give it back where it was.
    fn capture(&self, on: bool);
    /// A note in the strip under the title bar (link::Surface::note).
    fn game_note(&self, text: Option<String>, busy: bool);
    /// The window brought to the front.
    fn bring_forward(&self);
    /// Where the picture is on screen: x, y from the main screen's top left,
    /// width, height, in points (a Mac's) or pixels (Windows'); and the
    /// screen's own rectangle the same way. None if it cannot be worked out.
    fn picture_frame(&self) -> Option<Frame>;
    /// The controls came on or went off: a window that keeps the keyboard's
    /// input method from composing over the game while they are on (Windows) does so.
    fn controls(&self, _on: bool) {}
}

/// The picture on screen, and its screen's work area: x, y, width, height.
#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
    pub screen: (f64, f64, f64, f64),
    /// What these are counted in, per point: 1 on a Mac, the screen's DPI over 96 on Windows.
    pub scale: f64,
}

/// The note that asks for the mouse back is said this many times in a run, no more.
const ESC_NOTES: u32 = 3;
static ESC_SAID: AtomicU32 = AtomicU32::new(0);
/// How often the engine's thread looks at the display's size when nothing else wakes it.
const SIZE_LOOK: Duration = Duration::from_millis(100);

enum Msg {
    Event(Event, Instant),
    Stop(Option<Sender<()>>),
}

/// An Android app's window's game controls.
pub struct Session {
    me: Weak<Session>,
    app: tauri::AppHandle,
    pub id: u64,
    pub pkg: String,
    label: Mutex<String>,
    on: AtomicBool,
    captured: AtomicBool,
    /// What the window must hand the session instead of the phone, while on.
    bound: Mutex<HashSet<Input>>,
    /// The keys the window holds as the controls', as it has said — kept while
    /// the controls are off, since their releases still come here (the window
    /// keeps them as the controls' and swallows their repeats): pressed again
    /// in each new engine, which starts with nothing held ([`Engine::press_again`]).
    held: Mutex<HashSet<Input>>,
    window: Mutex<Option<(Arc<Link>, Arc<dyn Shell>)>>,
    running: Mutex<Option<Sender<Msg>>>,
    /// The window has shown its app: the controls may come on by themselves.
    live: AtomicBool,
}

impl Session {
    pub fn new(app: &tauri::AppHandle, id: u64, pkg: &str, label: &str) -> Arc<Session> {
        Arc::new_cyclic(|me| Session {
            me: me.clone(),
            app: app.clone(),
            id,
            pkg: pkg.to_string(),
            label: Mutex::new(label.to_string()),
            on: AtomicBool::new(false),
            captured: AtomicBool::new(false),
            bound: Mutex::new(HashSet::new()),
            held: Mutex::new(HashSet::new()),
            window: Mutex::new(None),
            running: Mutex::new(None),
            live: AtomicBool::new(false),
        })
    }

    /// The native window, once it exists.
    pub fn attach(&self, link: Arc<Link>, shell: Arc<dyn Shell>) {
        *self.window.lock().unwrap() = Some((link, shell));
    }

    pub fn is_on(&self) -> bool {
        self.on.load(Ordering::SeqCst)
    }

    /// The pointer is captured for a look area.
    pub fn captured(&self) -> bool {
        self.captured.load(Ordering::SeqCst)
    }

    /// The controls are on, and `input` is theirs, not the app's.
    pub fn takes(&self, input: Input) -> bool {
        self.is_on() && self.bound.lock().unwrap().contains(&input)
    }

    /// An event for the engine.
    pub fn event(&self, e: Event) {
        hold(&mut self.held.lock().unwrap(), &e);
        if let Some(tx) = self.running.lock().unwrap().as_ref() {
            let _ = tx.send(Msg::Event(e, Instant::now()));
        }
    }

    pub fn label(&self) -> String {
        self.label.lock().unwrap().clone()
    }

    /// The phone's own name for the app, once it says it.
    pub fn named(&self, name: &str) {
        if !name.is_empty() {
            *self.label.lock().unwrap() = name.to_string();
        }
    }

    fn link(&self) -> Option<Arc<Link>> {
        self.window.lock().unwrap().as_ref().map(|(l, _)| l.clone())
    }

    fn shell(&self) -> Option<Arc<dyn Shell>> {
        self.window.lock().unwrap().as_ref().map(|(_, s)| s.clone())
    }

    fn note(&self, text: &str) {
        if let Some(s) = self.shell() {
            s.game_note(Some(text.to_string()), false);
        }
    }

    pub fn is_mac() -> bool {
        cfg!(target_os = "macos")
    }

    /// Where this computer keeps the games' controls.
    pub fn dir(app: &tauri::AppHandle) -> Option<PathBuf> {
        app.path().app_config_dir().ok().map(|d| d.join("game-controls"))
    }

    /// This game's file, as it is now.
    pub fn layout(&self) -> Option<Layout> {
        layout::load(&Session::dir(&self.app)?, &self.pkg)
    }

    /// The shape of the window's picture now.
    pub fn shape(&self) -> Option<Shape> {
        let asked = self.link()?.asked();
        (asked.w > 0 && asked.h > 0).then(|| Shape::of(asked.w, asked.h))
    }

    /// Whether F8 and ⌘G are the controls' in this window: only once its game
    /// has a control set up, for either shape — or while they are on, so they
    /// still turn them off if the file went meanwhile. Otherwise both keys are
    /// the app's, and the editor is reached from More (☰).
    pub fn shortcut_is_ours(&self) -> bool {
        shortcut_is_ours(self.is_on(), self.layout().as_ref())
    }

    /// More's "Game controls", F8, ⌘G: on and off. With nothing set up for
    /// this game yet, the editor (More only: the keys are the app's then); set
    /// up for the other shape only, a note.
    pub fn toggle(&self) {
        if self.is_on() {
            self.set_on(false, true);
            return;
        }
        let Some(shape) = self.shape() else { return };
        match self.layout() {
            Some(l) if l.warned && l.controls(shape).is_some() => self.set_on(true, true),
            Some(l) if l.warned && l.controls(shape.other()).is_some() => self.note(&other_shape_note(shape.other())),
            _ => self.set_up(),
        }
    }

    /// More's "Set up game controls…": the editor, on a thread of its own.
    pub fn set_up(&self) {
        let Some(me) = self.me.upgrade() else { return };
        let _ = std::thread::Builder::new().name("game-editor".into()).spawn(move || editor::open(me));
    }

    /// The controls on or off; `say` from a person's own asking, which this
    /// game remembers for its next window.
    pub fn set_on(&self, on: bool, say: bool) {
        self.switch(on, say);
        self.game_mode();
    }

    /// Game mode, for the phone's own controller (apps/controller.rs): whether
    /// this window has its controls on now — its phone's notification offers
    /// the controller while one of its windows does.
    fn game_mode(&self) {
        let on = self.is_on();
        super::controller::game_controls(self.id, if on { super::phone_of_window(self.id) } else { None }, on);
    }

    fn switch(&self, on: bool, say: bool) {
        if on {
            // The controls there were go first, whatever comes of these: Done with
            // every control removed leaves none working.
            let was = self.is_on();
            self.stop();
            let (Some(link), Some(shell), Some(shape)) = (self.link(), self.shell(), self.shape()) else { return };
            let Some(controls) = self.layout().and_then(|l| l.controls(shape).cloned()) else {
                if was {
                    self.note("Game controls off.");
                    log::info!("game: {}: controls off, none set up for this shape", self.pkg);
                }
                return;
            };
            let Some(layout) = self.layout() else { return };
            // Whatever the mouse held on the phone is let go: the fingers' stream is the display's now.
            link.send(wire::json(&json!({"t": "leave"})));
            let takes: Vec<String> = engine::mouse_keys(&controls).iter().map(|k| keys::label(k.name(), Session::is_mac())).collect();
            self.start(link, shell, controls, shape);
            if say {
                self.remember(true);
            }
            self.note(&on_note(&takes, keys::has_key_under_escape()));
            log::info!("game: {}: controls on ({} of them, {})", self.pkg, layout.controls(shape).map_or(0, |c| c.len()), shape.name());
        } else {
            let was = self.is_on();
            self.stop();
            if say {
                self.remember(false);
            }
            if was && say {
                self.note("Game controls off.");
                log::info!("game: {}: controls off", self.pkg);
            }
        }
    }

    /// The editor's Done: stored, on, and the game brought forward.
    pub fn apply(&self, mut layout: Layout) -> Result<(), String> {
        let dir = Session::dir(&self.app).ok_or("no place for the controls on this computer")?;
        layout.pkg = self.pkg.clone();
        layout.warned = true;
        layout.on = true;
        self.store(&dir, &layout)?;
        self.set_on(true, false);
        if let Some(s) = self.shell() {
            s.bring_forward();
        }
        Ok(())
    }

    /// The warning was read and accepted for this game: remembered on this computer.
    pub fn warned(&self) -> Result<(), String> {
        let dir = Session::dir(&self.app).ok_or("no place for the controls on this computer")?;
        let mut l = self.layout().unwrap_or_else(|| Layout { pkg: self.pkg.clone(), name: self.label(), ..Layout::default() });
        l.warned = true;
        self.store(&dir, &l)
    }

    /// The game's file written; one there that did not read is kept aside, and the window says so.
    fn store(&self, dir: &std::path::Path, l: &Layout) -> Result<(), String> {
        let stored = layout::store(dir, l)?;
        if let Some(aside) = stored.set_aside {
            let name = aside.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            log::warn!("game: {}: the controls file there did not read; it is kept as {}", self.pkg, aside.display());
            self.note(&set_aside_note(&name));
        }
        Ok(())
    }

    /// `on` kept in the game's file for its next window.
    fn remember(&self, on: bool) {
        let (Some(dir), Some(mut l)) = (Session::dir(&self.app), self.layout()) else { return };
        if l.on != on {
            l.on = on;
            if let Err(e) = self.store(&dir, &l) {
                log::warn!("game: {}: could not keep the controls' state: {e}", self.pkg);
            }
        }
    }

    /// The window lost the keyboard: everything let go, the mouse given back.
    pub fn blur(&self) {
        self.event(Event::Blur);
    }

    /// The window shows its app: the controls come back on if they were on for this game.
    pub fn live(&self) {
        if self.live.swap(true, Ordering::SeqCst) {
            return;
        }
        let (Some(shape), Some(l)) = (self.shape(), self.layout()) else { return };
        if l.on && l.warned && l.controls(shape).is_some() {
            self.set_on(true, false);
        }
    }

    /// The window is gone.
    pub fn closed(&self) {
        self.stop();
        super::controller::game_controls(self.id, None, false);
        editor::closed(&self.app, self.id);
        *self.window.lock().unwrap() = None;
    }

    fn stop(&self) {
        let was = self.on.swap(false, Ordering::SeqCst);
        self.bound.lock().unwrap().clear();
        let Some(tx) = self.running.lock().unwrap().take() else { return };
        let (done_tx, done_rx) = mpsc::channel();
        // Its last fingers lifted and the mouse given back before anything starts again.
        let ended = tx.send(Msg::Stop(Some(done_tx))).is_ok() && done_rx.recv_timeout(Duration::from_millis(300)).is_ok();
        if !ended {
            // Its thread did not answer: the same here, so nothing stays held whatever it is doing.
            log::warn!("game: {}: the controls' thread did not stop in time; their fingers are lifted and the mouse given back here", self.pkg);
            if let Some(link) = self.link() {
                link.send(wire::touch(&[]));
            }
            if let Some(shell) = self.shell() {
                self.captured.store(false, Ordering::SeqCst);
                shell.capture(false);
            }
        }
        if was {
            if let Some(shell) = self.shell() {
                shell.controls(false);
            }
        }
    }

    fn start(&self, link: Arc<Link>, shell: Arc<dyn Shell>, controls: Vec<Control>, shape: Shape) {
        let (tx, rx) = mpsc::channel();
        let asked = link.asked();
        let engine = Engine::new(&controls, (asked.w as u16, asked.h as u16), asked.dpi as u16);
        *self.bound.lock().unwrap() = engine.bound();
        self.on.store(true, Ordering::SeqCst);
        *self.running.lock().unwrap() = Some(tx);
        shell.controls(true);
        let Some(me) = self.me.upgrade() else { return };
        let _ = std::thread::Builder::new()
            .name(format!("game-{}", self.id))
            .spawn(move || me.run(engine, link, shell, shape, rx));
    }

    /// The keys held as the controls' now.
    fn held(&self) -> HashSet<Input> {
        self.held.lock().unwrap().clone()
    }

    /// The engine's thread: events in, fingers out, the pointer taken and given back.
    fn run(&self, mut engine: Engine, link: Arc<Link>, shell: Arc<dyn Shell>, mut shape: Shape, rx: Receiver<Msg>) {
        let origin = Instant::now();
        // A key held across F8 off and on is the new engine's at once.
        engine.press_again(&self.held(), origin.elapsed());
        let mut sent: Vec<Finger> = Vec::new();
        let mut captured = false;
        let mut size = link.asked();
        let mut done: Option<Sender<()>> = None;
        // A new connection starts with nothing held on the phone: what is held is sent on it again, nothing included.
        let mut generation = link.generation();
        // Nothing held as the controls come on, said once: a mouse button held then is let go on the phone.
        let mut again = true;
        loop {
            let now = origin.elapsed();
            // Never less than a millisecond: a wake-up the engine has not cleared must not spin the thread.
            let wait = engine.deadline().map_or(SIZE_LOOK, |d| d.saturating_sub(now).clamp(Duration::from_millis(1), SIZE_LOOK));
            match rx.recv_timeout(wait) {
                Ok(Msg::Event(e, at)) => engine.handle(e, at.saturating_duration_since(origin)),
                Ok(Msg::Stop(ack)) => {
                    done = ack;
                    break;
                }
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => break,
            }
            let now = origin.elapsed();
            engine.tick(now);
            let asked = link.asked();
            if asked != size && asked.w > 0 && asked.h > 0 {
                size = asked;
                let now_shape = Shape::of(asked.w, asked.h);
                if now_shape == shape {
                    // Everything let go for the new size; what is still held, held again.
                    engine.handle(Event::Size { w: asked.w as u16, h: asked.h as u16, dpi: asked.dpi as u16 }, now);
                    engine.press_again(&self.held(), now);
                } else {
                    // Wide ↔ tall: the game's other set of controls, or off with a note.
                    match self.layout().and_then(|l| l.controls(now_shape).cloned()) {
                        Some(controls) => {
                            engine = Engine::new(&controls, (asked.w as u16, asked.h as u16), asked.dpi as u16);
                            *self.bound.lock().unwrap() = engine.bound();
                            engine.press_again(&self.held(), now);
                            shape = now_shape;
                        }
                        None => {
                            self.on.store(false, Ordering::SeqCst);
                            self.bound.lock().unwrap().clear();
                            self.running.lock().unwrap().take();
                            shell.controls(false);
                            shell.game_note(Some(other_shape_note(shape)), false);
                            super::controller::game_controls(self.id, None, false);
                            break;
                        }
                    }
                }
            }
            let g = link.generation();
            if g != generation {
                generation = g;
                again = true;
            }
            let f = engine.fingers();
            if touch_due(&f, &sent, std::mem::take(&mut again)) {
                link.send(wire::touch(&f));
                sent = f;
            }
            let c = engine.captured();
            if c != captured {
                captured = c;
                self.captured.store(c, Ordering::SeqCst);
                shell.capture(c);
                if c && ESC_SAID.fetch_add(1, Ordering::SeqCst) < ESC_NOTES {
                    shell.game_note(Some("Press Esc to get your mouse back.".into()), false);
                }
            }
        }
        if !sent.is_empty() {
            link.send(wire::touch(&[]));
        }
        if captured {
            self.captured.store(false, Ordering::SeqCst);
            shell.capture(false);
        }
        if let Some(d) = done {
            let _ = d.send(());
        }
    }
}

/// Whether the fingers go to the phone now: they changed, or the connection
/// is a new one (`again`), which the phone starts with nothing held on — so
/// they go on it even when there are none.
fn touch_due(now: &[Finger], sent: &[Finger], again: bool) -> bool {
    again || now != sent
}

/// The keys held, as an event the window hands over says: a key's press and
/// let-go, and nothing once the window has lost the keyboard (`Blur`). Not a
/// mouse button: with the controls off, its let-go goes to the phone, not here.
fn hold(held: &mut HashSet<Input>, e: &Event) {
    match *e {
        Event::Input { input: input @ Input::Key(_), down: true } => {
            held.insert(input);
        }
        Event::Input { input: input @ Input::Key(_), down: false } => {
            held.remove(&input);
        }
        Event::Blur => held.clear(),
        _ => {}
    }
}

/// F8 and ⌘G are the controls' while they are `on`, and where the game has a control set up, for either shape.
fn shortcut_is_ours(on: bool, l: Option<&Layout>) -> bool {
    on || l.is_some_and(|l| l.controls(Shape::Wide).is_some() || l.controls(Shape::Tall).is_some())
}

/// The keys of `held` whose release never came: the system's own key state
/// says they are up, having said they were down since they were pressed. A key
/// it has never been seen down by is left alone — a system that cannot say
/// lets nothing go. `seen` is kept per key; the released ones are taken out.
///
/// What the windows run a few times a second while a control's key is held
/// (doc/game-mode.md, "When a key's release never comes").
pub fn released<K: Copy + Eq + Hash>(held: &mut HashMap<K, bool>, down: impl Fn(K) -> Option<bool>) -> Vec<K> {
    let mut up = Vec::new();
    for (k, seen) in held.iter_mut() {
        match down(*k) {
            Some(true) => *seen = true,
            Some(false) if *seen => up.push(*k),
            _ => {}
        }
    }
    for k in &up {
        held.remove(k);
    }
    up
}

/// The part of `picture` (left, top, right, bottom) on `screen`: where a taken
/// pointer is kept and put back in the middle of (Windows). A picture partly
/// off its screen keeps it to the part that is on; one wholly off, to the
/// screen. Never a middle the pointer cannot be put on.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn on_screen(picture: (i32, i32, i32, i32), screen: (i32, i32, i32, i32)) -> (i32, i32, i32, i32) {
    let (l, t) = (picture.0.max(screen.0), picture.1.max(screen.1));
    let (r, b) = (picture.2.min(screen.2), picture.3.min(screen.3));
    if l < r && t < b {
        (l, t, r, b)
    } else {
        screen
    }
}

/// How often the windows ask the system about the controls' held keys.
pub const RECONCILE_EVERY: Duration = Duration::from_millis(250);

/// The note as the controls come on: the keys that take the mouse, if an Aim
/// is "all the time" — each named once ("Press Q or E to aim.").
/// On a keyboard with no key under Esc (`under_esc` false, an Apple JIS one), a
/// look left on that key is said to need another, never to press a key that is
/// not there.
fn on_note(takes: &[String], under_esc: bool) -> String {
    let missing = keys::label(keys::UNDER_ESCAPE.0, Session::is_mac());
    if !under_esc && takes.contains(&missing) {
        let here: Vec<String> = takes.iter().filter(|k| **k != missing).cloned().collect();
        let rest = if here.is_empty() { String::new() } else { format!(" {}", on_note(&here, true).trim_start_matches("Game controls on. ")) };
        return format!("Game controls on.{rest} This keyboard has no key under Esc. Pick a key for Aim in More › Set up game controls.");
    }
    if let Some((last, rest)) = takes.split_last() {
        let keys = if rest.is_empty() { last.clone() } else { format!("{} or {last}", rest.join(", ")) };
        format!("Game controls on. Press {keys} to aim.")
    } else if Session::is_mac() {
        "Game controls on. Press ⌘G to turn them off.".into()
    } else {
        "Game controls on. Press F8 to turn them off.".into()
    }
}

/// The note for a controls file that did not read and was kept aside as `name`.
fn set_aside_note(name: &str) -> String {
    format!("Couldn't read this game's old controls. They were kept as “{name}”.")
}

/// The note for controls set up for `made` only.
fn other_shape_note(made: Shape) -> String {
    let (a, b) = match made {
        Shape::Wide => ("wide", "wide"),
        Shape::Tall => ("tall", "tall"),
    };
    format!("These controls are for a {a} window. Make this window {b}, or set them up again.")
}

/// The game's name as the editor's title says it.
pub fn editor_title(label: &str) -> String {
    format!("Controls for {label}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_notes_say_what_the_plan_says() {
        assert_eq!(other_shape_note(Shape::Wide), "These controls are for a wide window. Make this window wide, or set them up again.");
        assert_eq!(other_shape_note(Shape::Tall), "These controls are for a tall window. Make this window tall, or set them up again.");
        let named = |keys: &[&str]| keys.iter().map(|k| k.to_string()).collect::<Vec<_>>();
        assert_eq!(on_note(&named(&["the key under Esc"]), true), "Game controls on. Press the key under Esc to aim.");
        assert_eq!(on_note(&named(&["Q"]), true), "Game controls on. Press Q to aim.");
        assert_eq!(on_note(&named(&["Q", "E"]), true), "Game controls on. Press Q or E to aim.");
        assert_eq!(on_note(&named(&["Q", "E", "the key under Esc"]), true), "Game controls on. Press Q, E or the key under Esc to aim.");
        assert!(on_note(&[], true).ends_with("to turn them off."));
        // An Apple JIS keyboard: never "press" a key that is not there.
        let none = "This keyboard has no key under Esc. Pick a key for Aim in More › Set up game controls.";
        assert_eq!(on_note(&named(&["the key under Esc"]), false), format!("Game controls on. {none}"));
        assert_eq!(on_note(&named(&["Q", "the key under Esc"]), false), format!("Game controls on. Press Q to aim. {none}"));
        assert_eq!(on_note(&named(&["Q"]), false), "Game controls on. Press Q to aim.");
        assert!(keys::is_jis(u32::from_be_bytes(*b"JIS ")) && !keys::is_jis(u32::from_be_bytes(*b"ANSI")) && !keys::is_jis(u32::from_be_bytes(*b"ISO ")));
        assert_eq!(set_aside_note("a.b.unreadable.json"), "Couldn't read this game's old controls. They were kept as “a.b.unreadable.json”.");
        assert_eq!(editor_title("Calculator"), "Controls for Calculator");
    }

    #[test]
    fn a_taken_pointer_is_kept_to_the_picture_s_part_on_its_screen() {
        let screen = (0, 0, 1920, 1080);
        assert_eq!(on_screen((100, 100, 900, 600), screen), (100, 100, 900, 600), "all on: the picture");
        // Half past the right edge: its middle would be off the screen, a move that never ends.
        let (l, t, r, b) = on_screen((1500, 100, 2300, 600), screen);
        assert_eq!((l, t, r, b), (1500, 100, 1920, 600));
        assert!((l + r) / 2 < 1920);
        // Wholly off (another screen gone): the screen itself.
        assert_eq!(on_screen((2000, 0, 2400, 300), screen), screen);
        // A screen left of the main one, at negative coordinates.
        assert_eq!(on_screen((-1000, 0, -200, 500), (-1280, 0, 0, 1024)), (-1000, 0, -200, 500));
    }

    #[test]
    fn the_keys_held_are_kept_as_the_window_says_them() {
        let w = Input::named("KeyW").unwrap();
        let mut held = HashSet::new();
        hold(&mut held, &Event::Input { input: w, down: true });
        hold(&mut held, &Event::Input { input: Input::Mouse(engine::MouseButton::Right), down: true });
        assert_eq!(held, [w].into(), "a key, never a mouse button");
        hold(&mut held, &Event::Delta { dx: 1.0, dy: 0.0 });
        assert_eq!(held.len(), 1);
        hold(&mut held, &Event::Input { input: w, down: false });
        assert!(held.is_empty());
        hold(&mut held, &Event::Input { input: w, down: true });
        hold(&mut held, &Event::Blur);
        assert!(held.is_empty(), "the window's keyboard gone: nothing held");
    }

    #[test]
    fn a_new_connection_is_sent_what_is_held_even_nothing() {
        let w = Finger { id: 1, x: 100, y: 200 };
        // As ever: only a change goes.
        assert!(!touch_due(&[w], &[w], false));
        assert!(touch_due(&[], &[w], false));
        // A new connection: the same fingers again, and "none" again — the lift that went into the old one.
        assert!(touch_due(&[w], &[w], true));
        assert!(touch_due(&[], &[], true));
        assert!(!touch_due(&[], &[], false));
    }

    #[test]
    fn the_key_that_takes_the_mouse_is_the_look_s_own_or_the_key_under_esc() {
        use layout::{Anchor, At, LookBy};
        use engine::mouse_keys;
        let look = |by, key: Option<&str>| Control::Look { at: At { from: Anchor::Center, dx: 0.0, dy: 0.0 }, w: 0.5, h: 0.5, by, key: key.and_then(Input::named), speed: 1.0 };
        assert_eq!(mouse_keys(&[look(LookBy::Always, None)]), [Input::Key(keys::UNDER_ESCAPE)]);
        assert_eq!(mouse_keys(&[look(LookBy::Always, Some("KeyQ"))]), [Input::named("KeyQ").unwrap()]);
        assert!(mouse_keys(&[look(LookBy::Hold, Some("MouseRight"))]).is_empty());
        assert_eq!(keys::label(mouse_keys(&[look(LookBy::Always, Some("KeyQ"))])[0].name(), true), "Q");
        // Two looks, two keys: both named, in their order; two on one key: it is named once.
        let both = [look(LookBy::Always, Some("KeyQ")), look(LookBy::Hold, Some("MouseRight")), look(LookBy::Always, Some("KeyE"))];
        assert_eq!(mouse_keys(&both), [Input::named("KeyQ").unwrap(), Input::named("KeyE").unwrap()]);
        assert_eq!(mouse_keys(&[look(LookBy::Always, None), look(LookBy::Always, None)]), [Input::Key(keys::UNDER_ESCAPE)]);
    }

    #[test]
    fn f8_and_cmd_g_are_the_controls_only_once_one_is_set_up() {
        let starter = |shape| layout::starter(shape, 1280.0, 720.0);
        assert!(!shortcut_is_ours(false, None), "no file: the app's");
        assert!(!shortcut_is_ours(false, Some(&Layout { warned: true, ..Layout::default() })), "only the warning read: the app's");
        assert!(shortcut_is_ours(false, Some(&Layout { wide: Some(starter(Shape::Wide)), ..Layout::default() })));
        assert!(shortcut_is_ours(false, Some(&Layout { tall: Some(starter(Shape::Tall)), ..Layout::default() })), "either shape");
        assert!(!shortcut_is_ours(false, Some(&Layout { wide: Some(vec![]), ..Layout::default() })), "every control removed");
        // On, and the file gone (or unreadable) meanwhile: the keys still turn them off.
        assert!(shortcut_is_ours(true, None));
    }

    #[test]
    fn a_held_key_is_let_go_once_the_system_says_it_is_up() {
        let mut held: HashMap<u16, bool> = HashMap::new();
        held.insert(0x0D, false); // W
        held.insert(0x38, false); // left Shift
        // Both down: nothing let go, both seen.
        assert!(released(&mut held, |_| Some(true)).is_empty());
        assert_eq!(held.len(), 2);
        // W up (its release went to the lock screen, or to a menu): let go; Shift still down.
        assert_eq!(released(&mut held, |k| Some(k != 0x0D)), [0x0D]);
        assert_eq!(held.keys().copied().collect::<Vec<_>>(), [0x38]);
        // A key the system has never said is down is never let go by it…
        let mut fresh: HashMap<u16, bool> = HashMap::from([(0x31, false)]);
        assert!(released(&mut fresh, |_| Some(false)).is_empty());
        assert!(fresh.contains_key(&0x31));
        // …and a system that cannot say lets nothing go.
        assert!(released(&mut held, |_| None).is_empty());
        assert_eq!(held.len(), 1);
    }
}
