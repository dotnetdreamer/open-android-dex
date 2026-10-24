//! The phone's apps, each in a window of its own on this computer — no
//! desktop around them (doc/apps-on-pc.md).
//!
//! The Apps page lists what the phone's own launcher lists, with real names
//! and icons; a click opens that app in a native window here, sized like a
//! phone, sharp at this screen's scale, its picture decoded by this
//! computer's graphics hardware. On the phone each window is a virtual
//! display of its own, the app started on it and encoded on the phone's
//! hardware encoder: only the app's own pixels are encoded, sent and decoded,
//! and an app that is still costs nothing at all. Closing the window hands the
//! app back to the phone.
//!
//! - `host`: the phone's half, started once per phone and kept running.
//! - `link`: one window's own connection to it, whatever draws the window.
//! - `win`: the window on Windows (Win32, D3D11, Media Foundation).
//! - `mac`: the window on a Mac (AppKit, and a display layer macOS decodes into).
//! - `sound`: where the phone's sound plays, the windows' title-bar menu.
//! - `drops`: files dropped on a window, to the phone.
//! - `icons`, `clipboard`, `keys`, `h264`, `wire`, `adbsock`: what those share.
//!
//! A Linux computer has no window of ours yet: an Android app opens in
//! scrcpy's window there, and the phone's Linux apps are not listed.

mod adbsock;
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
mod clipboard;
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
mod drops;
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
mod h264;
mod host;
mod icons;
mod keys;
mod link;
#[cfg(target_os = "macos")]
mod mac;
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
mod sound;
#[cfg(windows)]
mod win;
mod wire;

/// The window this computer draws a phone app in.
#[cfg(target_os = "macos")]
use mac as native;
#[cfg(windows)]
use win as native;

use std::collections::HashMap;
#[cfg(any(windows, target_os = "macos"))]
use std::path::PathBuf;
#[cfg(any(windows, target_os = "macos"))]
use std::sync::atomic::AtomicBool;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::Emitter;

/// Whether this computer has a window of ours for a phone app, rather than scrcpy's.
const NATIVE_WINDOWS: bool = cfg!(any(windows, target_os = "macos"));

/// One app the phone can open, as the Apps page shows it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneApp {
    pub pkg: String,
    pub label: String,
    /// Came with the phone, rather than installed by its owner.
    pub system: bool,
    /// "android", or "linux" for one of the phone's Linux apps (its `pkg` is "linux:<id>").
    pub kind: &'static str,
    /// A `data:` URL, when this computer already has the icon; else it follows as an `apps:icon` event.
    pub icon: Option<String>,
}

/// A window that is open, for the page's dot under its icon.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OpenWindow {
    pub id: u64,
    pub serial: String,
    pub pkg: String,
    pub label: String,
}

/// What the last list said about each app, for opening one.
#[derive(Clone)]
struct Row {
    label: String,
    activity: String,
    updated: u64,
    /// A Linux app's WM_CLASS, which its windows are known by.
    class: String,
}

/// The cache key of a Linux app's icon: they have no version to key it by.
const LINUX_ICON_VERSION: u64 = 1;

fn rows() -> &'static Mutex<HashMap<String, HashMap<String, Row>>> {
    static R: OnceLock<Mutex<HashMap<String, HashMap<String, Row>>>> = OnceLock::new();
    R.get_or_init(Default::default)
}

#[cfg(any(windows, target_os = "macos"))]
fn windows() -> &'static Mutex<HashMap<u64, Arc<native::Window>>> {
    static W: OnceLock<Mutex<HashMap<u64, Arc<native::Window>>>> = OnceLock::new();
    W.get_or_init(Default::default)
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// Windows on their way to a first picture, per phone. A phone as slow as a
/// Galaxy A13 does one thing at a time well: the list the Apps page asks for
/// as it appears, with a window opening at start, put that window's first
/// picture a second later. So a list waits for them.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn opening() -> &'static (Mutex<HashMap<String, usize>>, Condvar) {
    static O: OnceLock<(Mutex<HashMap<String, usize>>, Condvar)> = OnceLock::new();
    O.get_or_init(Default::default)
}

#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn opening_changed(serial: &str, up: bool) {
    let (m, cv) = opening();
    let mut all = m.lock().unwrap();
    let n = all.entry(serial.to_string()).or_insert(0);
    *n = if up { *n + 1 } else { n.saturating_sub(1) };
    cv.notify_all();
}

/// One window counted as opening, from before its phone's server is even up
/// (a list asked for meanwhile waits behind it) until its window says how it
/// went — or until this is dropped, if it never got that far.
#[cfg(any(windows, target_os = "macos"))]
struct Opening {
    serial: String,
    armed: bool,
}

#[cfg(any(windows, target_os = "macos"))]
impl Opening {
    fn new(serial: &str) -> Self {
        opening_changed(serial, true);
        Opening { serial: serial.to_string(), armed: true }
    }
}

#[cfg(any(windows, target_os = "macos"))]
impl Drop for Opening {
    fn drop(&mut self) {
        if self.armed {
            opening_changed(&self.serial, false);
        }
    }
}

/// Wait, up to `most`, for `serial`'s windows to have their first pictures.
fn wait_for_opening(serial: &str, most: Duration) {
    let (m, cv) = opening();
    let until = Instant::now() + most;
    let mut all = m.lock().unwrap();
    while all.get(serial).copied().unwrap_or(0) > 0 {
        let left = until.saturating_duration_since(Instant::now());
        if left.is_zero() {
            break;
        }
        all = cv.wait_timeout(all, left).unwrap().0;
    }
}

/// When this run of the app started: what a window opened at start is timed from.
fn process_start() -> Instant {
    static T: OnceLock<Instant> = OnceLock::new();
    *T.get_or_init(Instant::now)
}

/// Note the moment this run started — called first thing, before Tauri is up.
pub fn note_start() {
    let _ = process_start();
}

/// A phone on the cable, by its serial: adb names a Wi-Fi one by its address
/// (`ip:port`) or its mDNS service. On a cable the link never falls behind, so
/// its windows' pictures skip the pacing a Wi-Fi link needs, and its GPU copy.
pub fn on_cable(serial: &str) -> bool {
    !serial.contains(':') && !serial.contains("._adb-tls-connect.")
}

/// The phone's app server, started now if it is not running — what the Apps
/// page asks for as soon as it is looked at, so the first click finds it up.
#[tauri::command(async)]
pub fn apps_prepare(app: tauri::AppHandle, serial: String) -> Result<host::Hello, String> {
    Ok(host::ensure(&app, &serial)?.hello.clone())
}

/// The phone's apps, in its own launcher's names, A to Z. Icons this
/// computer already has come with them; the rest follow as `apps:icon`.
#[tauri::command(async)]
pub fn apps_list(app: tauri::AppHandle, serial: String) -> Result<Vec<PhoneApp>, String> {
    let server = host::ensure(&app, &serial)?;
    wait_for_opening(&serial, Duration::from_secs(4));
    let list = server.apps()?;
    let mut out = Vec::new();
    let mut table = HashMap::new();
    let mut missing = Vec::new();
    for a in &list {
        let pkg = a["pkg"].as_str().unwrap_or("").to_string();
        if pkg.is_empty() {
            continue;
        }
        let row = Row {
            label: a["label"].as_str().unwrap_or(&pkg).to_string(),
            activity: a["activity"].as_str().unwrap_or("").to_string(),
            updated: a["updated"].as_u64().unwrap_or(0),
            class: String::new(),
        };
        let icon = icons::cached(&app, &serial, &pkg, row.updated);
        if icon.is_none() {
            missing.push(pkg.clone());
        }
        out.push(PhoneApp {
            pkg: pkg.clone(),
            label: row.label.clone(),
            system: a["system"].as_bool().unwrap_or(false),
            kind: "android",
            icon: icon.map(|png| icons::data_url(&png)),
        });
        table.insert(pkg, row);
    }
    // The phone's Linux apps, beside its Android ones, when it has Linux — and
    // this computer has a window of ours to show them in: scrcpy's cannot.
    let mut missing_linux = Vec::new();
    match if NATIVE_WINDOWS { server.linux_apps() } else { Err("no app windows on this computer".into()) } {
        Ok(v) if v["installed"].as_bool() == Some(true) => {
            for a in v["list"].as_array().cloned().unwrap_or_default() {
                let id = a["id"].as_str().unwrap_or("").to_string();
                if id.is_empty() {
                    continue;
                }
                let pkg = format!("linux:{id}");
                let row = Row {
                    label: a["name"].as_str().unwrap_or(&id).to_string(),
                    activity: String::new(),
                    updated: LINUX_ICON_VERSION,
                    class: a["cls"].as_str().unwrap_or("").to_string(),
                };
                let icon = icons::cached(&app, &serial, &pkg, LINUX_ICON_VERSION);
                if icon.is_none() {
                    missing_linux.push(id.clone());
                }
                out.push(PhoneApp {
                    pkg: pkg.clone(),
                    label: row.label.clone(),
                    system: false,
                    kind: "linux",
                    icon: icon.map(|png| icons::data_url(&png)),
                });
                table.insert(pkg, row);
            }
        }
        Ok(_) => {}
        Err(e) => log::info!("apps [{serial}] no Linux apps: {e}"),
    }
    out.sort_by_key(|a| a.label.to_lowercase());
    rows().lock().unwrap().insert(serial.clone(), table.clone());
    if !missing.is_empty() || !missing_linux.is_empty() {
        let (app, serial) = (app.clone(), serial.clone());
        std::thread::spawn(move || {
            let t0 = std::time::Instant::now();
            let n = missing.len() + missing_linux.len();
            let mut got = |pkg: String, png: Vec<u8>| {
                let updated = table.get(&pkg).map(|r| r.updated).unwrap_or(0);
                icons::store(&app, &serial, &pkg, updated, &png);
                let _ = app.emit(
                    "apps:icon",
                    serde_json::json!({"serial": serial, "pkg": pkg, "icon": icons::data_url(&png)}),
                );
            };
            let r = server
                .icons(&missing, icons::PX, &mut got)
                .and_then(|()| server.linux_icons(&missing_linux, icons::PX, &mut got));
            match r {
                Ok(()) => log::info!("apps [{serial}] {n} icons in {}ms", t0.elapsed().as_millis()),
                Err(e) => log::warn!("apps [{serial}] icons: {e}"),
            }
        });
    }
    Ok(out)
}

/// Open `pkg` in a window of its own — or bring its window forward if it is open already.
#[tauri::command(async)]
pub fn apps_open(app: tauri::AppHandle, serial: String, pkg: String, device: Option<String>) -> Result<(), String> {
    open(&app, &serial, &pkg, device.unwrap_or_default(), Instant::now(), "the click")
}

/// `since` says what `clicked` was, for the log: the click, or this run starting.
#[cfg(any(windows, target_os = "macos"))]
fn open(
    app: &tauri::AppHandle,
    serial: &str,
    pkg: &str,
    device: String,
    clicked: Instant,
    since: &'static str,
) -> Result<(), String> {
    if let Some(id) = pkg.strip_prefix("linux:") {
        return open_linux(app, serial, id);
    }
    if let Some(w) = windows().lock().unwrap().values().find(|w| w.serial == serial && w.pkg == pkg) {
        w.focus();
        return Ok(());
    }
    let opening = Opening::new(serial);
    let server = host::ensure_for_window(app, serial)?;
    let known = rows().lock().unwrap().get(serial).and_then(|t| t.get(pkg).cloned());
    let (row, mut icon) = match known {
        Some(r) => {
            let icon = icons::cached(app, serial, pkg, r.updated);
            (r, icon)
        }
        // Opened before the page listed anything (a pinned taskbar button, at
        // start): not worth waiting for the whole list. The phone names the app
        // as its window opens, and its icon here is the last one this computer
        // was given; the list follows for the page.
        None => {
            let (app2, serial2) = (app.clone(), serial.to_string());
            std::thread::spawn(move || {
                if let Err(e) = apps_list(app2, serial2.clone()) {
                    log::info!("apps [{serial2}] listing after an app opened at start: {e}");
                }
            });
            let (updated, icon) = match icons::newest(app, serial, pkg) {
                Some((updated, png)) => (updated, Some(png)),
                None => (0, None),
            };
            (Row { label: pkg.to_string(), activity: String::new(), updated, class: String::new() }, icon)
        }
    };
    if icon.is_none() {
        let _ = server.icons(&[pkg.to_string()], icons::PX, |_, png| icon = Some(png));
        if let Some(png) = &icon {
            icons::store(app, serial, pkg, row.updated, png);
        }
    }
    #[cfg(windows)]
    let (relaunch, relaunch_icon) = (
        std::env::current_exe()
            .ok()
            .map(|exe| format!("\"{}\" --phone-app \"{serial}\" \"{pkg}\"", exe.display())),
        icon.as_ref()
            .and_then(|png| icons::ico_file(app, serial, pkg, row.updated, png))
            .map(|p| format!("{},0", p.display())),
    );
    // A Mac pins an app, not a window: there is no relaunch to give.
    #[cfg(not(windows))]
    let (relaunch, relaunch_icon) = (None, None);
    let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
    let cascade = windows().lock().unwrap().len() as u32;
    let spec = link::Spec {
        id,
        serial: serial.to_string(),
        socket: server.socket.clone(),
        pkg: pkg.to_string(),
        activity: (!row.activity.is_empty()).then(|| row.activity.clone()),
        label: row.label.clone(),
        device,
        icon_png: icon,
        relaunch,
        relaunch_icon,
        cascade,
        linux: None,
        size: None,
        direct: on_cable(serial),
        clicked,
    };
    open_window(app, serial, pkg, &row.label, spec, since, opening)
}

/// A window for `spec`, kept in the list until it closes, with the app's sound following it here
/// once it shows.
#[cfg(any(windows, target_os = "macos"))]
fn open_window(
    app: &tauri::AppHandle,
    serial: &str,
    pkg: &str,
    label: &str,
    spec: link::Spec,
    since: &'static str,
    mut opening: Opening,
) -> Result<(), String> {
    let id = spec.id;
    let host = Arc::new(WindowHost {
        app: app.clone(),
        id,
        serial: serial.to_string(),
        socket: spec.socket.clone(),
        pkg: pkg.to_string(),
        label: label.to_string(),
        since,
        settled: AtomicBool::new(false),
    });
    watch_clipboard();
    let window = native::open(spec, host)?;
    // The window says when it is no longer opening: its first event.
    opening.armed = false;
    windows().lock().unwrap().insert(id, window);
    emit_windows(app);
    Ok(())
}

/// What a window of this app asks of it: its news, the clipboard, its sound
/// menu, a drop.
#[cfg(any(windows, target_os = "macos"))]
struct WindowHost {
    app: tauri::AppHandle,
    id: u64,
    serial: String,
    /// The phone's app server, which a drop is a connection to.
    socket: String,
    pkg: String,
    label: String,
    since: &'static str,
    /// Its first event says the window is no longer opening.
    settled: AtomicBool,
}

#[cfg(any(windows, target_os = "macos"))]
impl link::Host for WindowHost {
    fn event(&self, e: link::Event) {
        let (app, serial, pkg) = (&self.app, &self.serial, &self.pkg);
        if !self.settled.swap(true, Ordering::SeqCst) {
            opening_changed(serial, false);
        }
        match e {
            link::Event::Live(after) => {
                log::info!("apps [{serial}] {pkg}: on screen {}ms after {}", after.as_millis(), self.since);
                let _ = app.emit("apps:live", serde_json::json!({"serial": serial, "pkg": pkg, "ms": after.as_millis() as u64}));
                // The app's sound comes here with it, as the desktop's does —
                // once its picture has: the sound's own server is a second
                // JVM starting on the phone, which slowed the window it came with.
                crate::scrcpy::apps_audio(app, serial, true);
            }
            link::Event::Failed(message) => {
                log::warn!("apps [{serial}] {pkg}: {message}");
                let _ = app.emit(
                    "apps:problem",
                    serde_json::json!({"serial": serial, "pkg": pkg, "label": self.label, "message": message}),
                );
            }
            link::Event::Closed => {
                let last = {
                    let mut all = windows().lock().unwrap();
                    all.remove(&self.id);
                    !all.values().any(|w| w.serial == *serial)
                };
                if last {
                    crate::scrcpy::apps_audio(app, serial, false);
                }
                emit_windows(app);
            }
        }
    }

    fn clipboard(&self) -> Option<String> {
        let text = clipboard::get(&self.app)?;
        let server = host::running(&self.serial)?;
        let mut last = server.clip_last.lock().unwrap();
        if *last == text {
            return None;
        }
        *last = text.clone();
        Some(text)
    }

    fn sound_menu(&self) -> Vec<sound::Item> {
        sound::menu_for(&self.app, &self.serial)
    }

    fn pick_sound(&self, choice: sound::Choice, window: Arc<dyn link::Surface>) {
        sound::pick(&self.app, &self.serial, choice, window);
    }

    fn dropped(&self, paths: Vec<PathBuf>, window: Arc<dyn link::Surface>) {
        drops::send(self.serial.clone(), self.socket.clone(), paths, window);
    }
}

/// What is copied on this computer, to every phone with a window here, as it
/// is copied — so the phone's own clipboard history has it, not only the
/// next paste (doc/apps-on-pc.md, "The clipboard"). Watched from the first
/// window on, for the rest of the run: with no window open nothing is sent.
///
/// Something copied that does not go (a password, an image) still changes
/// what this computer holds: each phone forgets the text last handed over
/// either way, so the same text copied on the phone after it is taken again.
#[cfg(any(windows, target_os = "macos"))]
fn watch_clipboard() {
    static WATCHING: std::sync::Once = std::sync::Once::new();
    WATCHING.call_once(|| {
        clipboard::watch(|text| {
            let mut serials: Vec<String> = windows().lock().unwrap().values().map(|w| w.serial.clone()).collect();
            serials.sort();
            serials.dedup();
            for serial in serials {
                let Some(server) = host::running(&serial) else { continue };
                match &text {
                    Some(text) => {
                        if server.give_clipboard(text) {
                            log::debug!("apps [{serial}] this computer's clipboard went to the phone ({} chars)", text.chars().count());
                        }
                    }
                    None => server.forget_clipboard(),
                }
            }
        });
    });
}

/// Elsewhere, scrcpy's own window does it: a display of its own for the app,
/// which follows the window. The same phone and the same app, without this
/// computer's GPU path — and without the phone's Linux apps, which are not
/// Android packages scrcpy could start (they are not listed here).
#[cfg(not(any(windows, target_os = "macos")))]
fn open(
    app: &tauri::AppHandle,
    serial: &str,
    pkg: &str,
    _device: String,
    _clicked: Instant,
    _since: &'static str,
) -> Result<(), String> {
    if pkg.starts_with("linux:") {
        return Err("Linux apps open in windows of their own on Windows and Mac. Here, open Linux's desktop from the Linux tab.".into());
    }
    let row = rows().lock().unwrap().get(serial).and_then(|t| t.get(pkg).cloned());
    let label = row.map(|r| r.label).unwrap_or_else(|| pkg.to_string());
    let options: crate::scrcpy::MirrorOptions = serde_json::from_value(serde_json::json!({
        "serial": serial,
        "appPackage": pkg,
        "newDisplay": "412x915/160",
        "vdNoDecorations": true,
        "windowTitle": label,
        "audio": false,
        "keepActive": true,
    }))
    .map_err(|e| e.to_string())?;
    crate::scrcpy::start_mirror(app.clone(), options).map(|_| ())
}

#[cfg(any(windows, target_os = "macos"))]
fn open_windows() -> Vec<OpenWindow> {
    windows()
        .lock()
        .unwrap()
        .values()
        .map(|w| OpenWindow { id: w.id, serial: w.serial.clone(), pkg: w.pkg.clone(), label: w.label.clone() })
        .collect()
}

#[cfg(not(any(windows, target_os = "macos")))]
fn open_windows() -> Vec<OpenWindow> {
    Vec::new()
}

fn emit_windows(app: &tauri::AppHandle) {
    let _ = app.emit("apps:windows", open_windows());
}

/// The app windows that are open.
#[tauri::command]
pub fn apps_windows() -> Vec<OpenWindow> {
    open_windows()
}

/// Close a phone's app windows (every phone's, without one), each app back on its phone.
#[tauri::command]
pub fn apps_close(serial: Option<String>) {
    #[cfg(any(windows, target_os = "macos"))]
    {
        let all: Vec<Arc<native::Window>> = windows().lock().unwrap().values().cloned().collect();
        for w in all {
            if serial.as_deref().map_or(true, |s| s == w.serial) {
                w.close();
            }
        }
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    let _ = serial;
}

/// This app is exiting: every window closes, and every phone's server stops —
/// which puts every app back on its phone.
pub fn shutdown() {
    apps_close(None);
    host::stop_all();
}

/// An app asked for on the command line: `--phone-app <serial> <pkg>`, which
/// is what a phone app pinned to the taskbar runs.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StartupApp {
    pub serial: String,
    pub pkg: String,
}

pub fn app_in_args(argv: &[String]) -> Option<StartupApp> {
    let i = argv.iter().position(|a| a == "--phone-app")?;
    Some(StartupApp { serial: argv.get(i + 1)?.clone(), pkg: argv.get(i + 2)?.clone() })
}

fn startup() -> &'static Mutex<Option<StartupApp>> {
    static S: OnceLock<Mutex<Option<StartupApp>>> = OnceLock::new();
    S.get_or_init(Default::default)
}

/// The app this run was started to open, if it was: the window asks before it
/// does anything else, and opens no desktop by itself when there is one.
#[tauri::command]
pub fn apps_startup() -> Option<StartupApp> {
    startup().lock().unwrap().clone()
}

/// This run was started for one phone app: open it as soon as its phone is
/// there — adb may still be starting, a Wi-Fi phone still on its way back.
pub fn open_at_startup(app: &tauri::AppHandle, argv: &[String]) {
    let Some(want) = app_in_args(argv) else { return };
    *startup().lock().unwrap() = Some(want.clone());
    open_when_ready(app, want, process_start(), "this run started");
}

/// `--phone-app` handed to the running app by a second start (a pinned
/// taskbar button clicked while the app is open). True if it was that.
pub fn open_from_args(app: &tauri::AppHandle, argv: &[String]) -> bool {
    let Some(want) = app_in_args(argv) else { return false };
    open_when_ready(app, want, Instant::now(), "the taskbar click");
    true
}

fn open_when_ready(app: &tauri::AppHandle, want: StartupApp, clicked: Instant, since: &'static str) {
    let app = app.clone();
    std::thread::spawn(move || {
        let StartupApp { serial, pkg } = want;
        let until = std::time::Instant::now() + std::time::Duration::from_secs(45);
        let mut last = String::new();
        while std::time::Instant::now() < until {
            match open(&app, &serial, &pkg, String::new(), clicked, since) {
                Ok(()) => return,
                Err(e) => last = e,
            }
            std::thread::sleep(std::time::Duration::from_millis(1500));
        }
        log::warn!("apps [{serial}] {pkg} from the taskbar: {last}");
        let _ = app.emit(
            "apps:problem",
            serde_json::json!({"serial": serial, "pkg": pkg, "label": pkg, "message": last}),
        );
    });
}

/// One app window with nothing else of the app around it — for trying the
/// window on a phone by hand (examples/app_window.rs), against a server the
/// caller started. Returns when the window closes.
#[cfg(windows)]
pub fn probe(serial: &str, socket: &str, pkg: &str, label: &str, icon_png: Option<Vec<u8>>) -> Result<(), String> {
    let (tx, rx) = std::sync::mpsc::channel::<()>();
    let host = Arc::new(ProbeHost { serial: serial.to_string(), socket: socket.to_string(), closed: Mutex::new(tx) });
    let spec = link::Spec {
        id: 0,
        serial: serial.to_string(),
        socket: socket.to_string(),
        pkg: pkg.to_string(),
        activity: None,
        label: label.to_string(),
        device: "probe".into(),
        icon_png,
        relaunch: None,
        relaunch_icon: None,
        cascade: 0,
        linux: None,
        size: None,
        direct: on_cable(serial),
        clicked: Instant::now(),
    };
    let _window = win::open(spec, host)?;
    let _ = rx.recv();
    Ok(())
}

/// The probe's window has no app around it: no clipboard and no sound menu
/// (both are the app's settings), but a drop reaches the phone as it would.
#[cfg(windows)]
struct ProbeHost {
    serial: String,
    socket: String,
    closed: Mutex<std::sync::mpsc::Sender<()>>,
}

#[cfg(windows)]
impl link::Host for ProbeHost {
    fn event(&self, e: link::Event) {
        match e {
            link::Event::Live(after) => log::info!("probe: on screen {}ms after opening", after.as_millis()),
            link::Event::Failed(m) => log::warn!("probe: {m}"),
            link::Event::Closed => {
                let _ = self.closed.lock().unwrap().send(());
            }
        }
    }

    fn clipboard(&self) -> Option<String> {
        None
    }

    fn sound_menu(&self) -> Vec<sound::Item> {
        Vec::new()
    }

    fn pick_sound(&self, _choice: sound::Choice, _window: Arc<dyn link::Surface>) {}

    fn dropped(&self, paths: Vec<PathBuf>, window: Arc<dyn link::Surface>) {
        drops::send(self.serial.clone(), self.socket.clone(), paths, window);
    }
}

// ── the phone's Linux apps ──

/// A Linux app's window opens when the app maps it, so opening the app only
/// asks the phone to run it — at the size and density a window here would
/// have, which is what its X screen starts at.
#[cfg(any(windows, target_os = "macos"))]
fn open_linux(app: &tauri::AppHandle, serial: &str, id: &str) -> Result<(), String> {
    let server = host::ensure(app, serial)?;
    let scale = native::screen_scale();
    let (w, h) = ((1024.0 * scale) as u32, (720.0 * scale) as u32);
    let dpi = (96.0 * scale).round() as u32;
    log::info!("apps [{serial}] running Linux's {id} at {w}x{h}, {dpi} dpi");
    server.linux_run(id, w, h, dpi)
}

/// What a Linux app's windows did, from the phone: a window to open here, a
/// title, one gone.
pub(crate) fn linux_event(app: &tauri::AppHandle, serial: &str, v: &serde_json::Value) {
    #[cfg(any(windows, target_os = "macos"))]
    {
        let xid = v["xid"].as_i64().unwrap_or(0) as i32;
        let title = v["title"].as_str().unwrap_or("").to_string();
        match v["kind"].as_str() {
            Some("open") if xid != 0 => {
                let (app, serial, v) = (app.clone(), serial.to_string(), v.clone());
                let mapped = Instant::now();
                std::thread::spawn(move || open_linux_window(&app, &serial, xid, &title, &v, mapped));
            }
            Some("title") => {
                if let Some(w) = windows().lock().unwrap().values().find(|w| w.serial == serial && w.linux == Some(xid)) {
                    w.set_title(&title);
                }
            }
            Some("closed") => {
                if let Some(w) = windows().lock().unwrap().values().find(|w| w.serial == serial && w.linux == Some(xid)) {
                    w.gone();
                }
            }
            _ => {}
        }
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    let _ = (app, serial, v);
}

#[cfg(any(windows, target_os = "macos"))]
fn open_linux_window(app: &tauri::AppHandle, serial: &str, xid: i32, title: &str, v: &serde_json::Value, mapped: Instant) {
    if let Some(w) = windows().lock().unwrap().values().find(|w| w.serial == serial && w.linux == Some(xid)) {
        w.focus();
        return;
    }
    let Some(server) = host::running(serial) else { return };
    // Its app, by window class: the icon on the window and its taskbar button.
    let class = v["cls"].as_str().unwrap_or("").to_lowercase();
    let instance = v["instance"].as_str().unwrap_or("").to_lowercase();
    let found = rows().lock().unwrap().get(serial).and_then(|t| {
        t.iter()
            .filter(|(pkg, _)| pkg.starts_with("linux:"))
            .find(|(pkg, r)| {
                let id = pkg.trim_start_matches("linux:").to_lowercase();
                let c = r.class.to_lowercase();
                (!c.is_empty() && (c == class || c == instance)) || id == instance || id == class
            })
            .map(|(pkg, r)| (pkg.clone(), r.clone()))
    });
    let (app_pkg, app_label) = match &found {
        Some((pkg, r)) => (pkg.clone(), r.label.clone()),
        None => (format!("linux:{}", if instance.is_empty() { "app" } else { &instance }), "Linux".to_string()),
    };
    let icon = icons::cached(app, serial, &app_pkg, LINUX_ICON_VERSION);
    let label = if title.is_empty() { app_label.clone() } else { title.to_string() };
    let w = v["w"].as_u64().unwrap_or(0) as u32;
    let h = v["h"].as_u64().unwrap_or(0) as u32;
    let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
    let cascade = windows().lock().unwrap().len() as u32;
    let spec = link::Spec {
        id,
        serial: serial.to_string(),
        socket: server.socket.clone(),
        pkg: app_pkg.clone(),
        activity: None,
        label: label.clone(),
        device: String::new(),
        icon_png: icon,
        relaunch: None,
        relaunch_icon: None,
        cascade,
        linux: Some(xid),
        size: (w > 0 && h > 0).then_some((w, h)),
        direct: on_cable(serial),
        clicked: mapped,
    };
    if let Err(e) = open_window(app, serial, &app_pkg, &label, spec, "Linux mapped it", Opening::new(serial)) {
        log::warn!("apps [{serial}] Linux window 0x{xid:x}: {e}");
    }
}
