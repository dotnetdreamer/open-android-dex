//! The game controls' editor (doc/game-mode.md, "The editor"): a window of
//! the app's webview, opened over the game's window at its picture's size,
//! with a still of the game in it and the controls on top
//! (src/features/game). A frozen frame is easier to aim at than a moving one,
//! and while the editor is open the game's window is not in front, so nothing
//! presses the game by accident.
//!
//! The editor's commands answer only the editor of a window that is open.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;

use serde_json::{json, Value};
use tauri::{Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

use super::layout::{self, Layout, Shape};
use super::{editor_title, keys, Session};

/// The toolbar and its line under it, above the picture; the line under the picture. Points.
const TOP: f64 = 96.0;
const FOOT: f64 = 46.0;
/// The editor is never narrower than its toolbar.
const MIN_W: f64 = 620.0;
/// A picture larger than this much of its screen (full screen) is shown smaller, at the same shape.
const MOST: f64 = 0.9;
/// How long a still waits for the phone's key frame.
const STILL_WAIT: Duration = Duration::from_secs(2);

struct Open {
    session: Weak<Session>,
    /// Files this editor exported: the only ones it may show in Finder or Explorer.
    exported: HashSet<PathBuf>,
}

fn editors() -> &'static Mutex<HashMap<u64, Open>> {
    static E: OnceLock<Mutex<HashMap<u64, Open>>> = OnceLock::new();
    E.get_or_init(Default::default)
}

fn label(id: u64) -> String {
    format!("game-{id}")
}

/// The editor for `session`'s game, over its window — or brought forward, if it is open.
/// On a thread of its own: a window built from the main thread or a window's deadlocks on Windows.
pub fn open(session: Arc<Session>) {
    let app = session.app.clone();
    let label = label(session.id);
    if let Some(w) = app.get_webview_window(&label) {
        let _ = w.unminimize();
        let _ = w.set_focus();
        return;
    }
    editors().lock().unwrap().insert(session.id, Open { session: Arc::downgrade(&session), exported: HashSet::new() });
    let frame = session.shell().and_then(|s| s.picture_frame());
    let place = frame.map(placed);
    let url = WebviewUrl::App(format!("index.html#game={}", session.id).into());
    let mut b = WebviewWindowBuilder::new(&app, &label, url)
        .title(editor_title(&session.label()))
        .resizable(false)
        .maximizable(false)
        .background_color(tauri::window::Color(9, 11, 16, 255))
        .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny);
    b = match place {
        Some(p) => b.inner_size(p.w, p.h).position(p.x, p.y),
        None => b.inner_size(MIN_W, 720.0).center(),
    };
    match b.build() {
        Ok(w) => {
            // The title bar is above the content: the content is moved to where the picture is.
            if let (Some(p), Ok(inner), Ok(outer), Ok(scale)) = (place, w.inner_position(), w.outer_position(), w.scale_factor()) {
                let want = (p.x * scale, p.y * scale);
                let off = (inner.x as f64 - outer.x as f64, inner.y as f64 - outer.y as f64);
                let _ = w.set_position(tauri::PhysicalPosition::new((want.0 - off.0).round() as i32, (want.1 - off.1).round() as i32));
            }
            log::info!("game: {}: the editor is open{}", session.pkg, if place.is_some() { ", over the game's picture" } else { "" });
        }
        Err(e) => log::warn!("game: {}: the editor did not open: {e}", session.pkg),
    }
}

/// Where the editor's content goes, in logical points: its picture over the game's.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Place {
    x: f64,
    y: f64,
    w: f64,
    h: f64,
}

fn placed(f: super::Frame) -> Place {
    let s = f.scale.max(0.5);
    let (px, py, pw, ph) = (f.x / s, f.y / s, f.w / s, f.h / s);
    let (sx, sy, sw, sh) = (f.screen.0 / s, f.screen.1 / s, f.screen.2 / s, f.screen.3 / s);
    let w = pw.max(MIN_W);
    let h = ph + TOP + FOOT;
    if pw > MOST * sw || h > MOST * sh {
        // A full-screen game: shown smaller, at its shape, in the middle of its screen.
        let k = (MOST * sw / pw).min((MOST * sh - TOP - FOOT) / ph).max(0.1);
        let (w, h) = ((pw * k).max(MIN_W), ph * k + TOP + FOOT);
        return Place { x: sx + (sw - w) / 2.0, y: sy + (sh - h) / 2.0, w, h };
    }
    let x = (px - (w - pw) / 2.0).clamp(sx, (sx + sw - w).max(sx));
    let y = (py - TOP).clamp(sy, (sy + sh - h).max(sy));
    Place { x, y, w, h }
}

/// The window `id` closed: its editor goes too.
pub fn closed(app: &tauri::AppHandle, id: u64) {
    editors().lock().unwrap().remove(&id);
    if let Some(w) = app.get_webview_window(&label(id)) {
        let _ = w.close();
    }
}

/// The session whose editor `window` is; none for any other window.
fn session_of(window: &WebviewWindow) -> Result<Arc<Session>, String> {
    let id: u64 = window.label().strip_prefix("game-").and_then(|s| s.parse().ok()).ok_or("not a game controls editor")?;
    editors()
        .lock()
        .unwrap()
        .get(&id)
        .and_then(|o| o.session.upgrade())
        .ok_or_else(|| "The game's window has closed.".to_string())
}

/// What the editor shows: the game, its picture's size and shape, its
/// controls for that shape (or a starter), whether the warning was accepted.
#[tauri::command]
pub async fn game_editor_load(window: WebviewWindow) -> Result<Value, String> {
    let s = session_of(&window)?;
    let link = s.link().ok_or("The game's window has closed.")?;
    let size = link.asked();
    let shape = Shape::of(size.w, size.h);
    let file = s.layout();
    let controls = file.as_ref().and_then(|l| l.controls(shape)).map(|c| layout::controls_to_value(c));
    let other = file.as_ref().is_some_and(|l| l.controls(shape.other()).is_some());
    log::info!("game: {}: the editor's page asked for the game ({} picture, {})", s.pkg, shape.name(), if controls.is_some() { "its controls" } else { "the starter" });
    Ok(json!({
        "id": s.id,
        "app": { "pkg": s.pkg, "name": s.label() },
        "shape": shape.name(),
        "picture": { "w": size.w, "h": size.h },
        "dpi": size.dpi,
        "controls": controls,
        "other": other,
        "starter": layout::controls_to_value(&layout::starter(shape, size.w as f32, size.h as f32)),
        "warned": file.as_ref().is_some_and(|l| l.warned),
        "mac": cfg!(target_os = "macos"),
        // An Apple JIS keyboard has no key under Esc: a look area asks for a key instead.
        "underEsc": keys::has_key_under_escape(),
    }))
}

/// The game's picture now: a u32 (big-endian) with the configuration's length,
/// the configuration, then the key frame — H.264 Annex B, for WebCodecs.
#[tauri::command]
pub async fn game_still(window: WebviewWindow) -> Result<tauri::ipc::Response, String> {
    let s = session_of(&window)?;
    let link = s.link().ok_or("The game's window has closed.")?;
    let (config, key) = tauri::async_runtime::spawn_blocking(move || link.still(STILL_WAIT))
        .await
        .map_err(|e| e.to_string())??;
    let mut out = Vec::with_capacity(4 + config.len() + key.len());
    out.extend_from_slice(&(config.len() as u32).to_be_bytes());
    out.extend_from_slice(&config);
    out.extend_from_slice(&key);
    Ok(tauri::ipc::Response::new(out))
}

/// The warning was read and "Set up controls" pressed: remembered for this game.
#[tauri::command]
pub async fn game_warned(window: WebviewWindow) -> Result<(), String> {
    session_of(&window)?.warned()
}

/// The shape the editor's page loaded, as it says it back: the controls on it
/// were placed on that shape's still, whatever shape the game's window is now.
fn shape_named(name: &str) -> Result<Shape, String> {
    match name {
        "wide" => Ok(Shape::Wide),
        "tall" => Ok(Shape::Tall),
        _ => Err("The editor did not say which shape its controls are for.".into()),
    }
}

/// The game's file with `controls` for `shape` — the one the editor loaded — the other shape's kept.
fn with_controls(s: &Session, controls: &Value, shape: Shape) -> Layout {
    let mut l = s.layout().unwrap_or_else(|| Layout { pkg: s.pkg.clone(), name: s.label(), ..Layout::default() });
    l.pkg = s.pkg.clone();
    l.name = s.label();
    l.set_controls(shape, layout::controls_from_value(controls));
    l
}

/// Done: stored under the shape the editor loaded, the controls on, the game in front, the editor closed.
#[tauri::command]
pub async fn game_save(window: WebviewWindow, controls: Value, shape: String) -> Result<(), String> {
    let s = session_of(&window)?;
    let l = with_controls(&s, &controls, shape_named(&shape)?);
    s.apply(l)?;
    let _ = window.close();
    Ok(())
}

/// Export: the file without this computer's part, in Downloads as "<App> controls.json". Its path.
#[tauri::command]
pub async fn game_export(window: WebviewWindow, controls: Value, shape: String) -> Result<String, String> {
    let s = session_of(&window)?;
    let l = with_controls(&s, &controls, shape_named(&shape)?);
    let dir = super::super::fetch::downloads().ok_or("This computer has no Downloads folder.")?;
    let name = super::super::fetch::safe_name(&format!("{} controls.json", s.label()));
    let path = super::super::fetch::free_name(&dir.join(name));
    let text = serde_json::to_string_pretty(&l.to_value(false)).map_err(|e| e.to_string())?;
    // Made here: never written into a file put at that name since it was found free.
    let written = std::fs::OpenOptions::new().write(true).create_new(true).open(&path).and_then(|mut f| {
        use std::io::Write;
        f.write_all(text.as_bytes())
    });
    written.map_err(|e| format!("It couldn't be saved: {e}"))?;
    if let Some(o) = editors().lock().unwrap().get_mut(&s.id) {
        o.exported.insert(path.clone());
    }
    log::info!("game: {}: controls exported to {}", s.pkg, path.display());
    Ok(path.to_string_lossy().into_owned())
}

/// Import: a file's text read as a game's controls — the controls for the
/// shape the editor loaded, as Done and Export store them, whatever shape the
/// game's window is by then; and whose game they were made for — or the
/// sentence why not.
#[tauri::command]
pub async fn game_import(window: WebviewWindow, text: String, shape: String) -> Result<Value, String> {
    let s = session_of(&window)?;
    let shape = shape_named(&shape)?;
    let l = Layout::parse(&text).map_err(|r| r.sentence().to_string())?;
    let controls = imported(&l, shape)?;
    log::info!("game: {}: controls imported for a {} picture", s.pkg, shape.name());
    // The names are shown in a dialog: a file's may be anything.
    Ok(json!({ "app": { "pkg": shown(&l.pkg, 255), "name": shown(&l.name, 60) }, "controls": layout::controls_to_value(&controls) }))
}

/// A file's controls for `shape`, the editor's — or the sentence why there are none.
fn imported(l: &Layout, shape: Shape) -> Result<Vec<layout::Control>, String> {
    match (l.controls(shape), l.controls(shape.other())) {
        (Some(c), _) => Ok(c.clone()),
        (None, Some(_)) => Err(format!("That file has controls for a {} window only.", shape.other().name())),
        (None, None) => Err(layout::Refused::NotControls.sentence().to_string()),
    }
}

/// `s` as a dialog may show it: no control characters, at most `most` characters, an ellipsis if cut.
fn shown(s: &str, most: usize) -> String {
    let clean: String = s.chars().filter(|c| !c.is_control()).collect();
    if clean.chars().count() <= most {
        return clean;
    }
    let mut cut: String = clean.chars().take(most.saturating_sub(1)).collect();
    cut.push('…');
    cut
}

/// An exported file, shown in Finder or Explorer.
#[tauri::command]
pub async fn game_reveal(window: WebviewWindow, path: String) -> Result<(), String> {
    let s = session_of(&window)?;
    let path = PathBuf::from(path);
    let ours = editors().lock().unwrap().get(&s.id).is_some_and(|o| o.exported.contains(&path));
    if !ours {
        return Err("Only a file this editor saved can be shown.".into());
    }
    tauri_plugin_opener::reveal_item_in_dir(&path).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(x: f64, y: f64, w: f64, h: f64) -> super::super::Frame {
        super::super::Frame { x, y, w, h, screen: (0.0, 25.0, 1920.0, 1055.0), scale: 1.0 }
    }

    #[test]
    fn the_editor_s_picture_lies_over_the_game_s() {
        let p = placed(frame(500.0, 300.0, 800.0, 450.0));
        assert_eq!(p, Place { x: 500.0, y: 300.0 - TOP, w: 800.0, h: 450.0 + TOP + FOOT });
        // A phone's shape: the editor wider than the picture, the picture in its middle.
        let p = placed(frame(700.0, 100.0, 400.0, 800.0));
        assert_eq!((p.w, p.x), (MIN_W, 700.0 - (MIN_W - 400.0) / 2.0));
        // A full-screen game: smaller, at its shape, in the middle of the screen.
        let p = placed(frame(0.0, 0.0, 1920.0, 1080.0));
        assert!(p.w < 1920.0 && p.h <= 1055.0 * MOST + 1.0);
        let k = (p.h - TOP - FOOT) / 1080.0;
        assert!((p.w - 1920.0 * k).abs() < 1.0);
        // Pixels on Windows: logical points out.
        let f = super::super::Frame { x: 1000.0, y: 600.0, w: 1600.0, h: 900.0, screen: (0.0, 0.0, 3840.0, 2100.0), scale: 2.0 };
        assert_eq!(placed(f), Place { x: 500.0, y: 300.0 - TOP, w: 800.0, h: 450.0 + TOP + FOOT });
    }

    #[test]
    fn done_and_export_say_the_shape_the_editor_loaded() {
        assert_eq!(shape_named("wide"), Ok(Shape::Wide));
        assert_eq!(shape_named("tall"), Ok(Shape::Tall));
        assert!(shape_named("").is_err() && shape_named("Wide").is_err());
        // Placed on a tall still, stored as tall, whatever the window is now; the wide set kept.
        let mut l = Layout { wide: Some(layout::starter(Shape::Wide, 1280.0, 720.0)), ..Layout::default() };
        let tall = layout::controls_to_value(&layout::starter(Shape::Tall, 720.0, 1280.0));
        l.set_controls(shape_named("tall").unwrap(), layout::controls_from_value(&tall));
        assert_eq!(l.wide.as_ref().map(Vec::len), Some(4));
        assert_eq!(l.tall.as_ref().map(Vec::len), Some(2));
    }

    #[test]
    fn import_takes_the_shape_the_editor_loaded() {
        // A file with both shapes: the editor's set, whichever shape the game's window has turned to.
        let both = Layout {
            wide: Some(layout::starter(Shape::Wide, 1280.0, 720.0)),
            tall: Some(layout::starter(Shape::Tall, 720.0, 1280.0)),
            ..Layout::default()
        };
        assert_eq!(imported(&both, shape_named("wide").unwrap()).unwrap().len(), 4);
        assert_eq!(imported(&both, shape_named("tall").unwrap()).unwrap().len(), 2);
        // Only the other shape's: said, nothing taken.
        let wide_only = Layout { wide: both.wide.clone(), ..Layout::default() };
        assert_eq!(imported(&wide_only, Shape::Tall).unwrap_err(), "That file has controls for a wide window only.");
        assert_eq!(imported(&Layout::default(), Shape::Wide).unwrap_err(), "That file isn't a set of game controls.");
    }

    #[test]
    fn a_file_s_names_are_kept_short_for_the_dialog() {
        assert_eq!(shown("Calculator", 60), "Calculator");
        assert_eq!(shown("a\u{0}b\nc", 60), "abc");
        let long = "x".repeat(500);
        let s = shown(&long, 60);
        assert_eq!(s.chars().count(), 60);
        assert!(s.ends_with('…'));
    }
}
