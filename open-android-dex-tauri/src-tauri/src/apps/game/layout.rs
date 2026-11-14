//! A game's controls, as a file (doc/game-mode.md, "Layouts").
//!
//! One file per game, keyed by the app's package, holding one set of controls
//! for each shape of the picture a game is played in: `wide` (width ≥ height)
//! and `tall`. A control's place is one of nine anchors of the picture and an
//! offset from it in units of the picture's **shorter side**: a game's buttons
//! sit a fixed distance from a corner and scale with the screen's height, so
//! a file follows them from a 16:9 window to a 21:9 one, which plain fractions
//! of width and height would not. The phone's own screen never enters into
//! it — the game runs on the window's display — so a file is the same on any
//! phone and any window size, and can be sent to someone.
//!
//! `local` (on, warned) never leaves this computer: export strips it and
//! import ignores it.

use super::engine::Input;
use serde_json::{json, Map, Value};
use std::path::PathBuf;

pub const FORMAT: &str = "open-android-dex.game-controls";
pub const VERSION: u64 = 1;
/// A file larger than this is no layout.
pub const MAX_BYTES: usize = 64 * 1024;
/// Controls in one shape, at most: well past the ten fingers a phone takes at once.
pub const MAX_CONTROLS: usize = 32;

/// One of the nine points of the picture a control's place is counted from.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Anchor {
    TopLeft,
    Top,
    TopRight,
    Left,
    Center,
    Right,
    BottomLeft,
    Bottom,
    BottomRight,
}

impl Anchor {
    pub const ALL: [Anchor; 9] = [
        Anchor::TopLeft,
        Anchor::Top,
        Anchor::TopRight,
        Anchor::Left,
        Anchor::Center,
        Anchor::Right,
        Anchor::BottomLeft,
        Anchor::Bottom,
        Anchor::BottomRight,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Anchor::TopLeft => "top-left",
            Anchor::Top => "top",
            Anchor::TopRight => "top-right",
            Anchor::Left => "left",
            Anchor::Center => "center",
            Anchor::Right => "right",
            Anchor::BottomLeft => "bottom-left",
            Anchor::Bottom => "bottom",
            Anchor::BottomRight => "bottom-right",
        }
    }

    pub fn named(s: &str) -> Option<Anchor> {
        Anchor::ALL.into_iter().find(|a| a.name() == s)
    }

    /// Where it is, as fractions of the picture's width and height.
    pub fn fraction(self) -> (f32, f32) {
        let i = Anchor::ALL.iter().position(|a| *a == self).unwrap_or(4);
        ((i % 3) as f32 * 0.5, (i / 3) as f32 * 0.5)
    }
}

/// A control's place: an anchor and an offset in units of the picture's shorter side.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct At {
    pub from: Anchor,
    pub dx: f32,
    pub dy: f32,
}

/// Offsets beyond this many shorter sides are clamped (the point is clamped to the picture anyway).
const MAX_OFFSET: f32 = 8.0;

impl At {
    /// The point in a picture `w` × `h`: `(ax·W + dx·S, ay·H + dy·S)`, `S = min(W, H)`, inside the picture.
    pub fn point(&self, w: f32, h: f32) -> (f32, f32) {
        let s = w.min(h);
        let (ax, ay) = self.from.fraction();
        ((ax * w + self.dx * s).clamp(0.0, w), (ay * h + self.dy * s).clamp(0.0, h))
    }

    /// The place of point (`x`, `y`) in a picture `w` × `h`, counted from the
    /// anchor nearest it — what the editor does as a control is dropped
    /// (src/features/game/model.ts mirrors it; this is the rule's test).
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn nearest(x: f32, y: f32, w: f32, h: f32) -> At {
        let s = w.min(h).max(1.0);
        let from = Anchor::ALL
            .into_iter()
            .min_by(|a, b| {
                let d = |k: Anchor| {
                    let (ax, ay) = k.fraction();
                    (x - ax * w).powi(2) + (y - ay * h).powi(2)
                };
                d(*a).total_cmp(&d(*b))
            })
            .unwrap_or(Anchor::Center);
        let (ax, ay) = from.fraction();
        At { from, dx: (x - ax * w) / s, dy: (y - ay * h) / s }
    }
}

/// How a button presses its spot.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Press {
    /// Held for as long as the key is.
    Hold,
    /// One quick tap for each press.
    Tap,
}

/// When a look area turns the mouse into a finger.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LookBy {
    /// While its key is held.
    Hold,
    /// While the mouse is taken for it: its key takes it and gives it back —
    /// the key under Esc unless another is chosen (a JIS keyboard has none
    /// there) — and moves it from another such look. Looks that share a key
    /// take it in turn, then give it back (engine.rs, `mouse_keys`).
    Always,
}

#[derive(Clone, PartialEq, Debug)]
pub enum Control {
    /// Four keys moving one finger about a centre; `size` is its radius, in shorter sides.
    Stick { at: At, size: f32, up: Option<Input>, left: Option<Input>, down: Option<Input>, right: Option<Input> },
    /// One spot.
    Button { at: At, key: Option<Input>, press: Press },
    /// An area the mouse drags a finger across; `w`, `h` in shorter sides, `at` its centre.
    Look { at: At, w: f32, h: f32, by: LookBy, key: Option<Input>, speed: f32 },
}

impl Control {
    pub fn at(&self) -> At {
        match self {
            Control::Stick { at, .. } | Control::Button { at, .. } | Control::Look { at, .. } => *at,
        }
    }

    /// Every input it is bound to.
    pub fn inputs(&self) -> Vec<Input> {
        match self {
            Control::Stick { up, left, down, right, .. } => [*up, *left, *down, *right].into_iter().flatten().collect(),
            Control::Button { key, .. } | Control::Look { key, .. } => key.iter().copied().collect(),
        }
    }
}

/// The picture's shape class: the controls kept for it.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum Shape {
    Wide,
    Tall,
}

impl Shape {
    pub fn of(w: u32, h: u32) -> Shape {
        if w >= h {
            Shape::Wide
        } else {
            Shape::Tall
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Shape::Wide => "wide",
            Shape::Tall => "tall",
        }
    }

    pub fn other(self) -> Shape {
        match self {
            Shape::Wide => Shape::Tall,
            Shape::Tall => Shape::Wide,
        }
    }
}

/// One game's file.
#[derive(Clone, PartialEq, Debug, Default)]
pub struct Layout {
    pub pkg: String,
    pub name: String,
    pub wide: Option<Vec<Control>>,
    pub tall: Option<Vec<Control>>,
    /// This computer's: the controls were on when that game's window last had them.
    pub on: bool,
    /// This computer's: the warning was read and accepted for this game.
    pub warned: bool,
}

/// Why a file is not a layout.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Refused {
    NotControls,
    Newer,
}

impl Refused {
    /// The sentence the editor shows.
    pub fn sentence(self) -> &'static str {
        match self {
            Refused::NotControls => "That file isn't a set of game controls.",
            Refused::Newer => "That file was made by a newer version of Open Android DeX.",
        }
    }
}

impl Layout {
    pub fn controls(&self, shape: Shape) -> Option<&Vec<Control>> {
        match shape {
            Shape::Wide => self.wide.as_ref(),
            Shape::Tall => self.tall.as_ref(),
        }
        .filter(|c| !c.is_empty())
    }

    pub fn set_controls(&mut self, shape: Shape, controls: Vec<Control>) {
        let c = Some(controls).filter(|c| !c.is_empty());
        match shape {
            Shape::Wide => self.wide = c,
            Shape::Tall => self.tall = c,
        }
    }

    /// A file's text, read as a layout: refused if it is not one, or is of a
    /// newer format; within it an unknown control is skipped, a key that is
    /// not one (or is kept back) is unbound, and numbers are clamped.
    pub fn parse(text: &str) -> Result<Layout, Refused> {
        if text.len() > MAX_BYTES {
            return Err(Refused::NotControls);
        }
        let v: Value = serde_json::from_str(text).map_err(|_| Refused::NotControls)?;
        Layout::from_value(&v)
    }

    pub fn from_value(v: &Value) -> Result<Layout, Refused> {
        let o = v.as_object().ok_or(Refused::NotControls)?;
        if o.get("format").and_then(Value::as_str) != Some(FORMAT) {
            return Err(Refused::NotControls);
        }
        match o.get("v").and_then(Value::as_u64) {
            Some(VERSION) => {}
            Some(n) if n > VERSION => return Err(Refused::Newer),
            _ => return Err(Refused::NotControls),
        }
        let app = o.get("app").and_then(Value::as_object);
        let text = |k: &str| app.and_then(|a| a.get(k)).and_then(Value::as_str).unwrap_or("").to_string();
        let shapes = o.get("shapes").and_then(Value::as_object).ok_or(Refused::NotControls)?;
        let read = |k: &str| shapes.get(k).map(|s| controls_from(s.get("controls")));
        let local = o.get("local").and_then(Value::as_object);
        let flag = |k: &str| local.and_then(|l| l.get(k)).and_then(Value::as_bool).unwrap_or(false);
        Ok(Layout {
            pkg: text("pkg"),
            name: text("name"),
            wide: read("wide").filter(|c| !c.is_empty()),
            tall: read("tall").filter(|c| !c.is_empty()),
            on: flag("on"),
            warned: flag("warned"),
        })
    }

    /// The file, with or without this computer's own part.
    pub fn to_value(&self, with_local: bool) -> Value {
        let mut shapes = Map::new();
        for (name, c) in [("wide", &self.wide), ("tall", &self.tall)] {
            if let Some(c) = c.as_ref().filter(|c| !c.is_empty()) {
                shapes.insert(name.into(), json!({ "controls": c.iter().map(control_to).collect::<Vec<_>>() }));
            }
        }
        let mut v = json!({
            "format": FORMAT,
            "v": VERSION,
            "app": { "pkg": self.pkg, "name": self.name },
            "shapes": shapes,
        });
        if with_local {
            v["local"] = json!({ "on": self.on, "warned": self.warned });
        }
        v
    }
}

/// One shape's controls, as the file and the editor write them, read with the file's rules.
pub fn controls_from_value(v: &Value) -> Vec<Control> {
    controls_from(Some(v))
}

/// One shape's controls, as the file and the editor write them.
pub fn controls_to_value(c: &[Control]) -> Value {
    Value::Array(c.iter().map(control_to).collect())
}

fn controls_from(v: Option<&Value>) -> Vec<Control> {
    let mut list: Vec<Control> = v
        .and_then(Value::as_array)
        .map(|list| list.iter().filter_map(control_from).take(MAX_CONTROLS).collect())
        .unwrap_or_default();
    // A key that takes the mouse for an "all the time" look is that look's alone: on any other control the
    // engine would never feel it (engine.rs, mouse_keys), so there it is unbound, as the editor shows it.
    let mouse = super::engine::mouse_keys(&list);
    let off = |k: &mut Option<Input>| {
        if k.is_some_and(|i| mouse.contains(&i)) {
            *k = None;
        }
    };
    for c in &mut list {
        match c {
            Control::Stick { up, left, down, right, .. } => [up, left, down, right].into_iter().for_each(off),
            Control::Button { key, .. } | Control::Look { by: LookBy::Hold, key, .. } => off(key),
            Control::Look { by: LookBy::Always, .. } => {}
        }
    }
    list
}

/// A finite number clamped to `lo..=hi`; None if it is missing or not finite.
fn num(o: &Map<String, Value>, k: &str, lo: f32, hi: f32) -> Option<f32> {
    let n = o.get(k)?.as_f64()? as f32;
    n.is_finite().then(|| n.clamp(lo, hi))
}

/// A control's key: a known name a control may have, and this computer can
/// give it (a file may come from another computer), else unbound.
fn input(o: &Map<String, Value>, k: &str) -> Option<Input> {
    o.get(k).and_then(Value::as_str).and_then(Input::named).filter(|i| i.bindable() && i.here())
}

/// A key held for a while — a stick's or a look area's: not the left button,
/// which is the game's own finger with the mouse free, and not the wheel, whose
/// notch is let go as it comes (the editor says so, src/features/game/model.ts).
fn held_input(o: &Map<String, Value>, k: &str) -> Option<Input> {
    use super::engine::MouseButton;
    input(o, k).filter(|i| !matches!(i, Input::Mouse(MouseButton::Left) | Input::Wheel(_)))
}

pub const STICK_SIZE: (f32, f32) = (0.04, 0.5);
pub const LOOK_SIZE: (f32, f32) = (0.05, 8.0);
pub const SPEED: (f32, f32) = (0.1, 10.0);

fn control_from(v: &Value) -> Option<Control> {
    let o = v.as_object()?;
    let at = o.get("at").and_then(Value::as_object)?;
    let at = At {
        from: Anchor::named(at.get("from").and_then(Value::as_str)?)?,
        dx: num(at, "dx", -MAX_OFFSET, MAX_OFFSET)?,
        dy: num(at, "dy", -MAX_OFFSET, MAX_OFFSET)?,
    };
    Some(match o.get("type").and_then(Value::as_str)? {
        "stick" => Control::Stick {
            at,
            size: num(o, "size", STICK_SIZE.0, STICK_SIZE.1).unwrap_or(0.14),
            up: held_input(o, "up"),
            left: held_input(o, "left"),
            down: held_input(o, "down"),
            right: held_input(o, "right"),
        },
        "button" => Control::Button {
            at,
            key: input(o, "key"),
            press: if o.get("press").and_then(Value::as_str) == Some("tap") { Press::Tap } else { Press::Hold },
        },
        "look" => Control::Look {
            at,
            w: num(o, "w", LOOK_SIZE.0, LOOK_SIZE.1).unwrap_or(0.7),
            h: num(o, "h", LOOK_SIZE.0, LOOK_SIZE.1).unwrap_or(0.8),
            by: if o.get("by").and_then(Value::as_str) == Some("always") { LookBy::Always } else { LookBy::Hold },
            // For "all the time", the key that takes the mouse and gives it back; none, the key under Esc.
            key: held_input(o, "key"),
            speed: num(o, "speed", SPEED.0, SPEED.1).unwrap_or(1.0),
        },
        _ => return None,
    })
}

/// Four decimals: a file a person may read, and nothing a pixel would notice.
fn round(n: f32) -> f64 {
    (n as f64 * 10_000.0).round() / 10_000.0
}

fn key_value(i: &Option<Input>) -> Value {
    i.map(|i| Value::from(i.name())).unwrap_or(Value::Null)
}

fn control_to(c: &Control) -> Value {
    let at = c.at();
    let at = json!({ "from": at.from.name(), "dx": round(at.dx), "dy": round(at.dy) });
    match c {
        Control::Stick { size, up, left, down, right, .. } => json!({
            "type": "stick", "at": at, "size": round(*size),
            "up": key_value(up), "left": key_value(left), "down": key_value(down), "right": key_value(right),
        }),
        Control::Button { key, press, .. } => json!({
            "type": "button", "at": at, "key": key_value(key),
            "press": if *press == Press::Tap { "tap" } else { "hold" },
        }),
        Control::Look { w, h, by, key, speed, .. } => json!({
            "type": "look", "at": at, "w": round(*w), "h": round(*h),
            "by": if *by == LookBy::Always { "always" } else { "hold" },
            "key": key_value(key), "speed": round(*speed),
        }),
    }
}

/// The controls a game starts with when it has none for this shape: a stick
/// on W A S D, and on a wide picture a look area on the right while the
/// right button is held — the mouse is never taken from someone who has not
/// asked — with Space and E beside it; on a tall one the stick and Space.
pub fn starter(shape: Shape, w: f32, h: f32) -> Vec<Control> {
    let key = |s: &str| Input::named(s);
    let stick = |dx, dy| Control::Stick {
        at: At { from: Anchor::BottomLeft, dx, dy },
        size: 0.14,
        up: key("KeyW"),
        left: key("KeyA"),
        down: key("KeyS"),
        right: key("KeyD"),
    };
    match shape {
        Shape::Wide => {
            let s = w.min(h).max(1.0);
            let area = 0.45 * w / s;
            vec![
                stick(0.30, -0.32),
                Control::Look {
                    at: At { from: Anchor::Right, dx: -area / 2.0, dy: -0.12 },
                    w: area - 0.04,
                    h: 0.6 * h / s,
                    by: LookBy::Hold,
                    key: key("MouseRight"),
                    speed: 1.0,
                },
                Control::Button { at: At { from: Anchor::BottomRight, dx: -0.18, dy: -0.22 }, key: key("Space"), press: Press::Hold },
                Control::Button { at: At { from: Anchor::BottomRight, dx: -0.36, dy: -0.34 }, key: key("KeyE"), press: Press::Tap },
            ]
        }
        Shape::Tall => vec![
            stick(0.25, -0.30),
            Control::Button { at: At { from: Anchor::BottomRight, dx: -0.20, dy: -0.25 }, key: key("Space"), press: Press::Hold },
        ],
    }
}

// ── on this computer ──

/// Names Windows keeps for its devices, whatever follows them after a dot.
const DEVICE_NAMES: &[&str] = &[
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2",
    "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
];

/// A package name, as a file name may carry it: Android's own characters
/// only, and never a name Windows keeps for a device (`con.x.json` is no file there).
pub fn safe_pkg(pkg: &str) -> bool {
    let first = pkg.split('.').next().unwrap_or("").to_ascii_lowercase();
    !pkg.is_empty()
        && pkg.len() <= 255
        && pkg.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_')
        && !pkg.starts_with('.')
        && !DEVICE_NAMES.contains(&first.as_str())
}

/// `<config>/game-controls/<package>.json`.
pub fn path(dir: &std::path::Path, pkg: &str) -> Option<PathBuf> {
    safe_pkg(pkg).then(|| dir.join(format!("{pkg}.json")))
}

/// The game's file: none, or what it holds. Err if there is one that does not
/// read — broken, too large, a newer format's, or not to be opened.
pub fn read(dir: &std::path::Path, pkg: &str) -> Result<Option<Layout>, String> {
    let Some(path) = path(dir, pkg) else { return Ok(None) };
    let size = match std::fs::metadata(&path) {
        Ok(m) => m.len(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.to_string()),
    };
    if size > MAX_BYTES as u64 {
        return Err(format!("{size} bytes, more than a set of controls"));
    }
    let text = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
    let mut l = Layout::parse(&text).map_err(|r| r.sentence().to_string())?;
    l.pkg = pkg.to_string();
    Ok(Some(l))
}

/// The game's file, if there is one that reads.
pub fn load(dir: &std::path::Path, pkg: &str) -> Option<Layout> {
    read(dir, pkg).ok().flatten()
}

/// What [`store`] did.
#[derive(Debug)]
pub struct Stored {
    #[cfg_attr(not(test), allow(dead_code))]
    pub path: PathBuf,
    /// The file there before did not read: it was kept under this name, never written over.
    pub set_aside: Option<PathBuf>,
}

/// The game's file written beside itself and moved over, so a crash never
/// leaves half a file. One there that does not read is moved out of the way
/// first, under a name of its own, and never written over: it may be a newer
/// version's, or hand-made.
pub fn store(dir: &std::path::Path, layout: &Layout) -> Result<Stored, String> {
    let path = path(dir, &layout.pkg).ok_or("not a package name")?;
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let mut set_aside = None;
    if read(dir, &layout.pkg).is_err() {
        let aside = (1..1000)
            .map(|n| dir.join(if n == 1 { format!("{}.unreadable.json", layout.pkg) } else { format!("{}.unreadable-{n}.json", layout.pkg) }))
            .find(|p| !p.exists())
            .ok_or("too many controls files that do not read")?;
        std::fs::rename(&path, &aside).map_err(|e| format!("the controls file there does not read, and could not be kept aside: {e}"))?;
        set_aside = Some(aside);
    }
    let text = serde_json::to_string_pretty(&layout.to_value(true)).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, text).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        e.to_string()
    })?;
    Ok(Stored { path, set_aside })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_nine_anchors() {
        let (w, h) = (1280.0, 720.0);
        let p = |a| At { from: a, dx: 0.0, dy: 0.0 }.point(w, h);
        assert_eq!(p(Anchor::TopLeft), (0.0, 0.0));
        assert_eq!(p(Anchor::Top), (640.0, 0.0));
        assert_eq!(p(Anchor::TopRight), (1280.0, 0.0));
        assert_eq!(p(Anchor::Left), (0.0, 360.0));
        assert_eq!(p(Anchor::Center), (640.0, 360.0));
        assert_eq!(p(Anchor::Right), (1280.0, 360.0));
        assert_eq!(p(Anchor::BottomLeft), (0.0, 720.0));
        assert_eq!(p(Anchor::Bottom), (640.0, 720.0));
        assert_eq!(p(Anchor::BottomRight), (1280.0, 720.0));
        // An offset is in shorter sides, and the point stays in the picture.
        assert_eq!(At { from: Anchor::BottomRight, dx: -0.5, dy: -0.25 }.point(w, h), (920.0, 540.0));
        assert_eq!(At { from: Anchor::TopLeft, dx: -1.0, dy: 3.0 }.point(w, h), (0.0, 720.0));
        for a in Anchor::ALL {
            assert_eq!(Anchor::named(a.name()), Some(a));
        }
    }

    #[test]
    fn a_point_snapped_to_its_nearest_anchor_comes_back_to_the_same_pixel() {
        let (w, h) = (2400.0, 1080.0);
        for (x, y) in [(10.0, 15.0), (1200.0, 540.0), (2300.0, 1000.0), (700.0, 900.0), (1500.0, 100.0), (2399.0, 1.0)] {
            let at = At::nearest(x, y, w, h);
            let (px, py) = at.point(w, h);
            assert!((px - x).abs() < 0.01 && (py - y).abs() < 0.01, "{x},{y} → {at:?} → {px},{py}");
        }
        assert_eq!(At::nearest(2300.0, 1000.0, w, h).from, Anchor::BottomRight);
        assert_eq!(At::nearest(1250.0, 520.0, w, h).from, Anchor::Center);
        assert_eq!(At::nearest(100.0, 600.0, w, h).from, Anchor::Left);
    }

    fn sample() -> Layout {
        Layout {
            pkg: "com.example.game".into(),
            name: "Example Game".into(),
            wide: Some(starter(Shape::Wide, 1280.0, 720.0)),
            tall: None,
            on: true,
            warned: true,
        }
    }

    #[test]
    fn a_layout_reads_back_as_it_was_written() {
        let l = sample();
        let text = serde_json::to_string(&l.to_value(true)).unwrap();
        let back = Layout::parse(&text).unwrap();
        assert_eq!(back.pkg, l.pkg);
        assert_eq!(back.name, l.name);
        assert!(back.on && back.warned);
        let (a, b) = (l.wide.unwrap(), back.wide.unwrap());
        assert_eq!(a.len(), b.len());
        for (x, y) in a.iter().zip(&b) {
            let (p, q) = (x.at().point(1280.0, 720.0), y.at().point(1280.0, 720.0));
            assert!((p.0 - q.0).abs() < 0.1 && (p.1 - q.1).abs() < 0.1);
            assert_eq!(x.inputs(), y.inputs());
        }
        assert!(back.tall.is_none());
    }

    #[test]
    fn an_export_has_no_local_part_and_an_import_ignores_one() {
        let v = sample().to_value(false);
        assert!(v.get("local").is_none());
        assert_eq!(v["format"], FORMAT);
        assert_eq!(v["shapes"]["wide"]["controls"][0]["type"], "stick");
        let back = Layout::from_value(&v).unwrap();
        assert!(!back.on && !back.warned);
    }

    #[test]
    fn a_newer_format_and_rubbish_are_refused() {
        let mut v = sample().to_value(false);
        v["v"] = json!(2);
        assert_eq!(Layout::from_value(&v), Err(Refused::Newer));
        assert_eq!(Layout::parse("not json"), Err(Refused::NotControls));
        assert_eq!(Layout::parse("{}"), Err(Refused::NotControls));
        assert_eq!(Layout::parse(r#"{"format":"something.else","v":1,"shapes":{}}"#), Err(Refused::NotControls));
        assert_eq!(Layout::parse(r#"{"format":"open-android-dex.game-controls","shapes":{}}"#), Err(Refused::NotControls));
        assert_eq!(Layout::parse(&" ".repeat(MAX_BYTES + 1)), Err(Refused::NotControls));
        assert_eq!(Refused::Newer.sentence(), "That file was made by a newer version of Open Android DeX.");
    }

    #[test]
    fn an_unknown_control_is_skipped_numbers_are_clamped_and_kept_keys_unbound() {
        let text = r#"{"format":"open-android-dex.game-controls","v":1,"app":{"pkg":"a.b","name":"A"},
          "shapes":{"wide":{"controls":[
            {"type":"swipe","at":{"from":"center","dx":0,"dy":0}},
            {"type":"button","at":{"from":"middle","dx":0,"dy":0},"key":"KeyQ"},
            {"type":"button","at":{"from":"center","dx":99,"dy":-99},"key":"Escape","press":"tap"},
            {"type":"stick","at":{"from":"left","dx":0.2,"dy":0},"size":5,"up":"KeyW","left":"NoSuchKey","down":null},
            {"type":"look","at":{"from":"right","dx":-0.3,"dy":0},"w":0,"h":100,"by":"always","speed":-3}
          ]}}}"#;
        let l = Layout::parse(text).unwrap();
        let c = l.wide.unwrap();
        assert_eq!(c.len(), 3, "the swipe and the unknown anchor skipped");
        assert_eq!(c[0], Control::Button { at: At { from: Anchor::Center, dx: 8.0, dy: -8.0 }, key: None, press: Press::Tap });
        match &c[1] {
            Control::Stick { size, up, left, down, right, .. } => {
                assert_eq!(*size, STICK_SIZE.1);
                assert_eq!(*up, Input::named("KeyW"));
                assert_eq!((*left, *down, *right), (None, None, None));
            }
            other => panic!("{other:?}"),
        }
        match &c[2] {
            Control::Look { w, h, by, speed, key, .. } => {
                assert_eq!((*w, *h, *by, *speed, *key), (LOOK_SIZE.0, LOOK_SIZE.1, LookBy::Always, SPEED.0, None));
            }
            other => panic!("{other:?}"),
        }
        let many = format!(
            r#"{{"format":"open-android-dex.game-controls","v":1,"shapes":{{"tall":{{"controls":[{}]}}}}}}"#,
            vec![r#"{"type":"button","at":{"from":"center","dx":0,"dy":0},"key":"KeyQ"}"#; 40].join(",")
        );
        assert_eq!(Layout::parse(&many).unwrap().tall.unwrap().len(), MAX_CONTROLS);
        let nan = r#"{"format":"open-android-dex.game-controls","v":1,"shapes":{"wide":{"controls":[
            {"type":"button","at":{"from":"center","dx":"x","dy":0},"key":"KeyQ"}]}}}"#;
        assert!(Layout::parse(nan).unwrap().wide.is_none());
    }

    #[test]
    fn a_stick_or_look_on_the_left_button_or_the_wheel_is_unbound() {
        let text = r#"{"format":"open-android-dex.game-controls","v":1,"shapes":{"wide":{"controls":[
            {"type":"stick","at":{"from":"left","dx":0.2,"dy":0},"up":"MouseLeft","left":"WheelUp","down":"KeyS","right":"MouseBack"},
            {"type":"look","at":{"from":"right","dx":-0.3,"dy":0},"by":"hold","key":"MouseLeft"},
            {"type":"look","at":{"from":"right","dx":-0.3,"dy":0},"by":"always","key":"WheelDown"},
            {"type":"look","at":{"from":"right","dx":-0.3,"dy":0},"by":"always","key":"KeyQ"},
            {"type":"button","at":{"from":"center","dx":0,"dy":0},"key":"WheelUp"}
          ]}}}"#;
        let c = Layout::parse(text).unwrap().wide.unwrap();
        assert_eq!(c[0].inputs().iter().map(|i| i.name()).collect::<Vec<_>>(), ["KeyS", "MouseBack"]);
        assert!(c[1].inputs().is_empty() && c[2].inputs().is_empty());
        assert_eq!(c[3].inputs(), [Input::named("KeyQ").unwrap()], "the key that takes the mouse");
        assert_eq!(c[4].inputs(), [Input::named("WheelUp").unwrap()], "a button may have a notch");
        // Two looks "all the time", the wheel's left on the key under Esc: both read, and both reached.
        assert_eq!(super::super::engine::mouse_keys(&c), [Input::Key(super::super::keys::UNDER_ESCAPE), Input::named("KeyQ").unwrap()]);
    }

    #[test]
    fn a_file_s_keys_this_computer_never_gives_or_a_look_takes_the_mouse_with_are_unbound() {
        let text = r#"{"format":"open-android-dex.game-controls","v":1,"shapes":{"wide":{"controls":[
            {"type":"look","at":{"from":"right","dx":-0.3,"dy":0},"by":"always","key":"KeyQ"},
            {"type":"button","at":{"from":"center","dx":0,"dy":0},"key":"KeyQ"},
            {"type":"stick","at":{"from":"left","dx":0.2,"dy":0},"up":"KeyQ","left":"KeyA","down":"KeyS","right":"KeyD"},
            {"type":"look","at":{"from":"right","dx":-0.3,"dy":0},"by":"hold","key":"KeyQ"},
            {"type":"look","at":{"from":"left","dx":0.3,"dy":0},"by":"always","key":"KeyQ"},
            {"type":"button","at":{"from":"top","dx":0,"dy":0.1},"key":"CapsLock"},
            {"type":"button","at":{"from":"top","dx":0.1,"dy":0.1},"key":"IntlBackslash"},
            {"type":"button","at":{"from":"top","dx":0.2,"dy":0.1},"key":"NumpadEqual"}
          ]}}}"#;
        let c = Layout::parse(text).unwrap().wide.unwrap();
        let names = |c: &Control| c.inputs().iter().map(|i| i.name()).collect::<Vec<_>>();
        // Q takes the mouse for the looks "all the time" — both of them, in turn — and is nobody else's.
        assert_eq!((names(&c[0]), names(&c[4])), (vec!["KeyQ"], vec!["KeyQ"]));
        assert!(names(&c[1]).is_empty(), "the button on Q would never be pressed");
        assert_eq!(names(&c[2]), ["KeyA", "KeyS", "KeyD"]);
        assert!(names(&c[3]).is_empty());
        // Keys this computer never hands a control.
        let (caps, iso, num_eq) = (names(&c[5]), names(&c[6]), names(&c[7]));
        if cfg!(target_os = "macos") {
            assert!(caps.is_empty() && iso.is_empty(), "a Mac sends Caps Lock as a change of state and the ISO key as the key under Esc");
            assert_eq!(num_eq, ["NumpadEqual"]);
        } else if cfg!(windows) {
            assert_eq!((caps, iso), (vec!["CapsLock"], vec!["IntlBackslash"]));
            assert!(num_eq.is_empty(), "Windows has no scan code for the number pad's =");
        }
    }

    #[test]
    fn the_starter_for_wide_and_for_tall() {
        let wide = starter(Shape::Wide, 1600.0, 900.0);
        assert_eq!(wide.len(), 4);
        assert!(matches!(wide[0], Control::Stick { .. }));
        match &wide[1] {
            Control::Look { at, w, by, key, .. } => {
                assert_eq!(*by, LookBy::Hold);
                assert_eq!(*key, Input::named("MouseRight"));
                // The right 45 % of the picture, or near it.
                let (cx, _) = at.point(1600.0, 900.0);
                assert!((cx - 1600.0 * 0.775).abs() < 1.0, "{cx}");
                assert!((*w * 900.0 - 0.45 * 1600.0).abs() < 40.0);
            }
            other => panic!("{other:?}"),
        }
        let keys: Vec<_> = wide.iter().flat_map(|c| c.inputs()).map(|i| i.name()).collect();
        assert_eq!(keys, ["KeyW", "KeyA", "KeyS", "KeyD", "MouseRight", "Space", "KeyE"]);
        let tall = starter(Shape::Tall, 720.0, 1280.0);
        assert_eq!(tall.len(), 2);
        assert!(matches!(tall[1], Control::Button { press: Press::Hold, .. }));
        assert_eq!(Shape::of(1280, 720), Shape::Wide);
        assert_eq!(Shape::of(720, 720), Shape::Wide);
        assert_eq!(Shape::of(720, 1280), Shape::Tall);
    }

    #[test]
    fn stored_beside_itself_and_read_back() {
        let dir = std::env::temp_dir().join(format!("oadx-game-layout-test-{}", std::process::id()));
        let l = sample();
        let stored = store(&dir, &l).unwrap();
        assert!(stored.path.ends_with("com.example.game.json"));
        assert!(stored.set_aside.is_none());
        assert!(!dir.join("com.example.game.json.tmp").exists());
        let back = load(&dir, "com.example.game").unwrap();
        assert!(back.on && back.warned && back.wide.is_some());
        assert!(load(&dir, "com.example.other").is_none());
        assert!(matches!(read(&dir, "com.example.other"), Ok(None)));
        assert!(path_is_refused());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_that_does_not_read_is_kept_aside_never_written_over() {
        let dir = std::env::temp_dir().join(format!("oadx-game-unreadable-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("com.example.game.json");
        // A newer version's file, then a broken one, then one too large.
        let mut newer = sample().to_value(true);
        newer["v"] = json!(2);
        let texts = [serde_json::to_string(&newer).unwrap(), "{ not json".to_string(), " ".repeat(MAX_BYTES + 1)];
        for (i, text) in texts.iter().enumerate() {
            std::fs::write(&file, text).unwrap();
            assert!(read(&dir, "com.example.game").is_err());
            assert!(load(&dir, "com.example.game").is_none());
            let stored = store(&dir, &sample()).unwrap();
            let aside = stored.set_aside.expect("set aside");
            let want = if i == 0 { "com.example.game.unreadable.json".to_string() } else { format!("com.example.game.unreadable-{}.json", i + 1) };
            assert!(aside.ends_with(&want), "{aside:?}");
            assert_eq!(&std::fs::read_to_string(&aside).unwrap(), text, "kept as it was");
            assert!(load(&dir, "com.example.game").is_some_and(|l| l.wide.is_some()));
        }
        // A file that reads is written over as before.
        assert!(store(&dir, &sample()).unwrap().set_aside.is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn path_is_refused() -> bool {
        let d = std::path::Path::new("/tmp");
        path(d, "../etc/passwd").is_none() && path(d, "a/b").is_none() && path(d, "").is_none() && path(d, "com.x").is_some()
    }

    #[test]
    fn no_windows_device_name_is_a_file_name() {
        for pkg in ["con.x", "CON", "nul.game", "Com1.app", "lpt9.x.y", "aux"] {
            assert!(!safe_pkg(pkg), "{pkg}");
        }
        for pkg in ["com.example.game", "console.app", "com10.x", "nullify.game", "a.con"] {
            assert!(safe_pkg(pkg), "{pkg}");
        }
    }
}
