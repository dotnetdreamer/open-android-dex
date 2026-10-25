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
    /// The phone's `ro.serialno`: the page's phone may be the same one another way.
    pub phone: String,
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

// ── which way to the phone ──

/// How long the cable must have stayed in before windows move onto it: a
/// Samsung phone restarts adbd about 5 s after it is plugged in (its MTP
/// switch), ending everything adb started.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
const CABLE_SETTLE: Duration = Duration::from_secs(10);

/// How long a window whose connection went keeps looking for its phone — the
/// cable pulled with Wi-Fi still there, a Samsung phone's adbd restarting.
/// Later, the phone may be in its owner's hand, and the app is theirs until
/// they open it here again. The phone's server waits as long (AppWindow.AWAY_MS).
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
const RECONNECT_FOR: Duration = Duration::from_secs(30);

/// How often the keeper looks at the open windows, and how often while one
/// waits for its phone.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
const KEEP_EVERY: Duration = Duration::from_secs(2);
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
const KEEP_LOST_EVERY: Duration = Duration::from_millis(700);

/// How often a window waiting for its phone has the phone's Wi-Fi link made
/// again: nothing else brings one back once a Samsung phone's adbd restarted.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
const WIFI_AGAIN_EVERY: Duration = Duration::from_secs(5);

/// What a window's title bar says about how it reaches its phone.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn via(serial: &str) -> &'static str {
    if on_cable(serial) {
        "USB"
    } else {
        "Wi\u{2011}Fi"
    }
}

/// One phone, whichever way it is attached: its `ro.serialno`.
fn phone_id(d: &crate::adb::DeviceInfo) -> String {
    d.hardware_serial.clone().unwrap_or_else(|| d.serial.clone())
}

/// The phone `serial` is a way to. One the list does not have — a pinned
/// button's, its phone not attached that way now — is read from its name: a
/// cable's serial is the phone's own, and an mDNS name carries it
/// ("adb-<serial>-<suffix>._adb-tls-connect._tcp").
fn phone_of(devices: &[crate::adb::DeviceInfo], serial: &str) -> String {
    if let Some(d) = devices.iter().find(|d| d.serial == serial) {
        return phone_id(d);
    }
    serial
        .strip_prefix("adb-")
        .and_then(|rest| rest.split("._adb-tls-connect.").next())
        .and_then(|name| name.rsplit_once('-').map(|(serial, _)| serial.to_string()))
        .unwrap_or_else(|| serial.to_string())
}

/// The way to open `phone`'s windows over: the cable once it has settled —
/// faster, and drawn straight into the encoder — else Wi-Fi, else a cable not
/// settled yet. None when the phone is not there at all.
fn best_serial(devices: &[crate::adb::DeviceInfo], phone: &str) -> Option<String> {
    best_serial_by(devices, phone, |serial| crate::adb::attached_for(serial).is_some_and(|t| t >= CABLE_SETTLE))
}

/// [`best_serial`], `settled` saying whether a cable has been in long enough.
fn best_serial_by(devices: &[crate::adb::DeviceInfo], phone: &str, settled: impl Fn(&str) -> bool) -> Option<String> {
    let ready: Vec<&crate::adb::DeviceInfo> =
        devices.iter().filter(|d| d.state == "device" && phone_id(d) == phone).collect();
    ready
        .iter()
        .find(|d| on_cable(&d.serial) && settled(&d.serial))
        .or_else(|| ready.iter().find(|d| !on_cable(&d.serial)))
        .or_else(|| ready.first())
        .map(|d| d.serial.clone())
}

/// Which way to reach the phone `serial` names, now.
struct Way {
    serial: String,
    phone: String,
    /// What the phone is called, for a title bar whose caller did not say.
    name: String,
}

fn way_to(app: &tauri::AppHandle, serial: &str) -> Way {
    let devices = crate::adb::recent_devices(app, Duration::from_secs(3));
    let phone = phone_of(&devices, serial);
    let serial = best_serial(&devices, &phone).unwrap_or_else(|| serial.to_string());
    let name = devices
        .iter()
        .find(|d| d.serial == serial)
        .and_then(|d| d.name.clone().or_else(|| (!d.model.is_empty()).then(|| d.model.clone())))
        .unwrap_or_default();
    Way { serial, phone, name }
}

/// The phone's app server, started now if it is not running — what the Apps
/// page asks for as soon as it is looked at, so the first click finds it up.
/// Over the cable when the phone is on one, as its windows will be.
#[tauri::command(async)]
pub fn apps_prepare(app: tauri::AppHandle, serial: String) -> Result<host::Hello, String> {
    let way = way_to(&app, &serial);
    Ok(host::ensure(&app, &way.serial, &way.phone)?.hello.clone())
}

/// The phone's apps, in its own launcher's names, A to Z. Icons this
/// computer already has come with them; the rest follow as `apps:icon`, for
/// the `serial` the page asked with.
#[tauri::command(async)]
pub fn apps_list(app: tauri::AppHandle, serial: String) -> Result<Vec<PhoneApp>, String> {
    let way = way_to(&app, &serial);
    let server = host::ensure(&app, &way.serial, &way.phone)?;
    wait_for_opening(&way.serial, Duration::from_secs(4));
    let phone = way.phone;
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
        let icon = icons::cached(&app, &phone, &pkg, row.updated);
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
                let icon = icons::cached(&app, &phone, &pkg, LINUX_ICON_VERSION);
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
    rows().lock().unwrap().insert(phone.clone(), table.clone());
    if !missing.is_empty() || !missing_linux.is_empty() {
        let (app, serial) = (app.clone(), serial.clone());
        std::thread::spawn(move || {
            let t0 = std::time::Instant::now();
            let n = missing.len() + missing_linux.len();
            let mut got = |pkg: String, png: Vec<u8>| {
                let updated = table.get(&pkg).map(|r| r.updated).unwrap_or(0);
                icons::store(&app, &phone, &pkg, updated, &png);
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
    let way = way_to(app, serial);
    let device = if device.is_empty() { way.name.clone() } else { device };
    // The app's window on this phone, whichever way it was opened.
    let phones: HashMap<u64, String> = kept().lock().unwrap().iter().map(|(id, k)| (*id, k.phone.clone())).collect();
    let existing = windows()
        .lock()
        .unwrap()
        .values()
        .find(|w| w.pkg == pkg && w.linux.is_none() && phones.get(&w.id) == Some(&way.phone))
        .cloned();
    if let Some(w) = existing {
        w.focus();
        // Waiting for its phone, or given up on it: asked for now, it is made
        // again from now — and its app taken back from the phone if need be.
        let stalled = match kept().lock().unwrap().get_mut(&w.id) {
            Some(k) if k.lost.is_some() || k.failed.is_some() => {
                k.lost = Some(Instant::now());
                k.failed = None;
                k.said_waiting = false;
                k.asked = true;
                true
            }
            _ => false,
        };
        if stalled {
            w.say(None);
            note(&w, Some(format!("Reconnecting to {}…", phone_name(&device))), true);
            wake_keeper();
        }
        return Ok(());
    }
    open_new(app, &way, pkg, device, clicked, since)
}

/// What a window's notes call its phone.
#[cfg(any(windows, target_os = "macos"))]
fn phone_name(device: &str) -> &str {
    if device.is_empty() {
        "your phone"
    } else {
        device
    }
}

/// `pkg` in a new window, over `way`.
#[cfg(any(windows, target_os = "macos"))]
fn open_new(
    app: &tauri::AppHandle,
    way: &Way,
    pkg: &str,
    device: String,
    clicked: Instant,
    since: &'static str,
) -> Result<(), String> {
    let serial = way.serial.as_str();
    let phone = way.phone.as_str();
    let opening = Opening::new(serial);
    let server = host::ensure_for_window(app, serial, phone)?;
    let known = rows().lock().unwrap().get(phone).and_then(|t| t.get(pkg).cloned());
    let (row, mut icon) = match known {
        Some(r) => {
            let icon = icons::cached(app, phone, pkg, r.updated);
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
            let (updated, icon) = match icons::newest(app, phone, pkg) {
                Some((updated, png)) => (updated, Some(png)),
                None => (0, None),
            };
            (Row { label: pkg.to_string(), activity: String::new(), updated, class: String::new() }, icon)
        }
    };
    if icon.is_none() {
        let _ = server.icons(&[pkg.to_string()], icons::PX, |_, png| icon = Some(png));
        if let Some(png) = &icon {
            icons::store(app, phone, pkg, row.updated, png);
        }
    }
    #[cfg(windows)]
    let (relaunch, relaunch_icon) = (
        std::env::current_exe()
            .ok()
            .map(|exe| format!("\"{}\" --phone-app \"{serial}\" \"{pkg}\"", exe.display())),
        icon.as_ref()
            .and_then(|png| icons::ico_file(app, phone, pkg, row.updated, png))
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
        via: via(serial),
        clicked,
    };
    open_window(app, serial, phone, pkg, &row.label, spec, since, opening)
}

/// What the keeper (`keep`) knows of each window.
#[derive(Clone)]
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
struct Kept {
    /// The phone it shows, whichever way that is attached.
    phone: String,
    /// What its title bar calls the phone.
    device: String,
    /// It has shown its app.
    live: bool,
    /// Its app could not be shown, or its phone did not come back in time.
    failed: Option<Instant>,
    /// When its connection went, while no other has its picture yet: the
    /// keeper makes one again, the fastest way there is.
    lost: Option<Instant>,
    /// It has said it waits for its phone, which no way reaches yet.
    said_waiting: bool,
    /// The person opened it again while it was lost or given up: its app is
    /// opened in it again even if the phone had it back.
    asked: bool,
    /// The keeper is moving it to another way to its phone now.
    busy: bool,
}

#[cfg(any(windows, target_os = "macos"))]
fn kept() -> &'static Mutex<HashMap<u64, Kept>> {
    static K: OnceLock<Mutex<HashMap<u64, Kept>>> = OnceLock::new();
    K.get_or_init(Default::default)
}

/// What wakes the keeper before its next look: a window's connection going, or opened again.
#[cfg(any(windows, target_os = "macos"))]
fn keeper_bell() -> &'static (Mutex<bool>, Condvar) {
    static B: OnceLock<(Mutex<bool>, Condvar)> = OnceLock::new();
    B.get_or_init(|| (Mutex::new(false), Condvar::new()))
}

#[cfg(any(windows, target_os = "macos"))]
fn wake_keeper() {
    let (rung, bell) = keeper_bell();
    *rung.lock().unwrap() = true;
    bell.notify_all();
}

/// While an app's window is open, every KEEP_EVERY — and every
/// KEEP_LOST_EVERY while one waits for its phone: a window whose connection
/// went is moved onto the fastest way to its phone there is now, the same
/// window, for RECONNECT_FOR; one on Wi-Fi moves onto the cable once the
/// cable has settled. Each move is a thread of its own, so one slow phone
/// holds up no other window, and none is ever replaced by another.
#[cfg(any(windows, target_os = "macos"))]
fn keep(app: tauri::AppHandle) {
    // When each phone's Wi-Fi link was last asked to be made again.
    let mut wifi_asked: HashMap<String, Instant> = HashMap::new();
    let mut waiting = false;
    loop {
        {
            let (rung, bell) = keeper_bell();
            let most = if waiting { KEEP_LOST_EVERY } else { KEEP_EVERY };
            let (mut rung, _) = bell.wait_timeout_while(rung.lock().unwrap(), most, |r| !*r).unwrap();
            *rung = false;
        }
        let open: Vec<(Arc<native::Window>, Kept)> = {
            let kept = kept().lock().unwrap().clone();
            windows()
                .lock()
                .unwrap()
                .values()
                .filter_map(|w| kept.get(&w.id).map(|k| (w.clone(), k.clone())))
                .collect()
        };
        waiting = open.iter().any(|(_, k)| k.lost.is_some());
        if open.is_empty() {
            continue;
        }
        // A fresh look: it is also what says how long the cable has been in.
        let Ok(devices) = crate::adb::adb_list_devices(app.clone()) else { continue };
        for (w, k) in open {
            if k.busy {
                continue;
            }
            if let Some(since) = k.lost {
                // A phone that is coming back — a cable settling — is waited
                // for a while longer than one that is nowhere.
                let way = best_serial(&devices, &k.phone);
                let most = if way.is_some() { RECONNECT_FOR * 2 } else { RECONNECT_FOR };
                if since.elapsed() >= most {
                    give_up(&w);
                    continue;
                }
                match way {
                    Some(serial) => {
                        let why = if k.asked { Why::Asked } else { Why::Lost };
                        start_moving(&app, &w, &k, serial, why);
                    }
                    None => {
                        if !k.said_waiting {
                            if let Some(k) = kept().lock().unwrap().get_mut(&w.id) {
                                k.said_waiting = true;
                            }
                            let name = phone_name(&k.device);
                            note(&w, Some(format!("Waiting for {name} — plug it back in, or keep it on the same Wi\u{2011}Fi")), true);
                        }
                        if wifi_asked.get(&k.phone).is_none_or(|at| at.elapsed() >= WIFI_AGAIN_EVERY) {
                            wifi_asked.insert(k.phone.clone(), Instant::now());
                            let (app, phone) = (app.clone(), k.phone.clone());
                            std::thread::spawn(move || crate::wireless::reconnect_phone(&app, &phone));
                        }
                    }
                }
                continue;
            }
            if k.failed.is_some() || !k.live {
                continue;
            }
            if let Some(best) = best_serial(&devices, &k.phone) {
                if on_cable(&best) && !on_cable(&w.serial()) {
                    start_moving(&app, &w, &k, best, Why::Cable);
                }
            }
        }
    }
}

/// Why a window moves to another way to its phone.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
enum Why {
    /// Its connection went.
    Lost,
    /// It is on Wi-Fi, and the cable is in and has settled.
    Cable,
    /// It was opened again on the Apps page, waiting or given up.
    Asked,
}

/// `w` moving to the way `serial` takes to its phone, on a thread of its own.
#[cfg(any(windows, target_os = "macos"))]
fn start_moving(app: &tauri::AppHandle, w: &Arc<native::Window>, k: &Kept, serial: String, why: Why) {
    match kept().lock().unwrap().get_mut(&w.id) {
        Some(k) if !k.busy => k.busy = true,
        _ => return,
    }
    let (app, w, phone, name) = (app.clone(), w.clone(), k.phone.clone(), k.device.clone());
    let _ = std::thread::Builder::new().name("apps-move".into()).spawn(move || {
        match move_window(&app, &w, &phone, &serial, why) {
            Ok(()) => {}
            // Waited for, not failed: the next look tries again.
            Err(e) if e == SETTLING => log::debug!("apps [{serial}] {}: {e}", w.pkg),
            Err(e) => {
                log::info!("apps [{serial}] {}: not over {} yet: {e}", w.pkg, via(&serial));
                // Still where it was, or still waiting: its note says which.
                match why {
                    Why::Cable => note(&w, None, false),
                    _ => note(&w, Some(format!("Reconnecting to {}…", phone_name(&name))), true),
                }
            }
        }
        if let Some(k) = kept().lock().unwrap().get_mut(&w.id) {
            k.busy = false;
        }
    });
}

/// `w` onto the way to its phone that `serial` takes: the same window on the
/// phone if its server is still up — the cable pulled, Wi-Fi still there —
/// else its app opened in it again on a new server, as when a Samsung
/// phone's adbd, restarting as the cable goes, takes the server with it.
/// Either way the same window here, where it was, as it was.
#[cfg(any(windows, target_os = "macos"))]
fn move_window(app: &tauri::AppHandle, w: &Arc<native::Window>, phone: &str, serial: &str, why: Why) -> Result<(), String> {
    let from = w.route();
    let to = via(serial);
    // A server started anew over a cable that has not settled would go with a
    // Samsung phone's adbd, restarting about 5 s after the plug went in; one
    // already running is only reached that way.
    let settled = crate::adb::attached_for(serial).is_some_and(|t| t >= CABLE_SETTLE);
    if on_cable(serial) && !settled && host::adopt(app, serial, phone).is_none() {
        return Err(SETTLING.into());
    }
    note(w, Some(format!("Switching to {to}…")), true);
    let server = host::ensure_for_window(app, serial, phone)?;
    let route = link::Route { serial: serial.to_string(), socket: server.socket.clone(), direct: on_cable(serial), via: to };
    let same = server.socket == from.socket;
    if !same && why == Why::Cable {
        // A window that is fine where it is moves only to the same window.
        return Err("the phone's app server on the cable is not the window's".into());
    }
    let mut done = if same { w.reconnect(route.clone(), false) } else { Err(link::Refused::Unknown) };
    // The window's server has gone, or has no such window: its app opened in
    // it again — unless the phone took the app back meanwhile (opened on the
    // phone), which only the person asking for it here again overrides.
    let again = w.linux.is_none()
        && match &done {
            Err(link::Refused::Unknown) => why != Why::Cable,
            Err(link::Refused::Gone) => why == Why::Asked,
            _ => false,
        };
    if again {
        done = w.reconnect(route, true);
    }
    match done {
        Ok(()) => {
            // Moved: the keeper waits for nothing more, whatever its picture's first frame takes.
            if let Some(k) = kept().lock().unwrap().get_mut(&w.id) {
                k.lost = None;
                k.asked = false;
                k.said_waiting = false;
            }
            // Reached this way: the phone as a whole (the clipboard, the sound) too.
            if !server.alive() {
                let _ = host::ensure_for_window(app, serial, phone);
            }
            log::info!(
                "apps [{serial}] {}: over {to} now, {} (was over {})",
                w.pkg,
                if again { "opened again" } else { "the same window on the phone" },
                from.via
            );
            // The sound's companion of the way it left, let go if no window takes it.
            if from.serial != serial && !windows().lock().unwrap().values().any(|x| x.serial() == from.serial) {
                crate::scrcpy::apps_audio(app, &from.serial, false);
            }
            note(w, Some(format!("Now over {to}")), false);
            emit_windows(app);
            Ok(())
        }
        Err(link::Refused::Gone) => {
            log::info!("apps [{serial}] {}: opened on the phone while this computer was away; its window goes", w.pkg);
            w.gone();
            Ok(())
        }
        Err(link::Refused::Unknown) if w.linux.is_some() => {
            // A Linux window is its X window on the phone's server: with that
            // server gone there is nothing here to open again.
            give_up(w);
            Ok(())
        }
        Err(link::Refused::Unknown) => Err("the phone's app server no longer has this window".into()),
        Err(link::Refused::Failed(e)) => Err(e),
    }
}

/// What a move that waits for a cable to settle says: waited for, not failed.
#[cfg(any(windows, target_os = "macos"))]
const SETTLING: &str = "the cable has not settled yet";

/// A window's note — said once: a waiting note the window shows already is
/// not put up again by the next look, which would make its fading start over.
#[cfg(any(windows, target_os = "macos"))]
fn note(w: &native::Window, text: Option<String>, busy: bool) {
    let mut said = said().lock().unwrap();
    match (&text, busy) {
        (Some(t), true) if said.get(&w.id) == Some(t) => return,
        (Some(t), true) => {
            said.insert(w.id, t.clone());
        }
        _ => {
            said.remove(&w.id);
        }
    }
    drop(said);
    w.note(text, busy);
}

/// Each window's waiting note, as last put up.
#[cfg(any(windows, target_os = "macos"))]
fn said() -> &'static Mutex<HashMap<u64, String>> {
    static SAID: OnceLock<Mutex<HashMap<u64, String>>> = OnceLock::new();
    SAID.get_or_init(Default::default)
}

/// A window whose phone did not come back in RECONNECT_FOR says so; its app
/// is the phone's again (its server hands it back) until it is opened here.
#[cfg(any(windows, target_os = "macos"))]
fn give_up(w: &native::Window) {
    if let Some(k) = kept().lock().unwrap().get_mut(&w.id) {
        k.lost = None;
        k.asked = false;
        k.failed = Some(Instant::now());
    }
    log::info!("apps [{}] {}: its phone did not come back", w.serial(), w.pkg);
    let text = "Your phone stopped answering. Open the app again to bring it back.";
    note(w, Some(text.into()), true);
    w.say(Some(text.into()));
}

/// A window for `spec`, kept in the list until it closes, with the app's sound following it here
/// once it shows.
#[cfg(any(windows, target_os = "macos"))]
#[allow(clippy::too_many_arguments)]
fn open_window(
    app: &tauri::AppHandle,
    serial: &str,
    phone: &str,
    pkg: &str,
    label: &str,
    spec: link::Spec,
    since: &'static str,
    mut opening: Opening,
) -> Result<(), String> {
    let id = spec.id;
    let device = spec.device.clone();
    let host = Arc::new(WindowHost {
        app: app.clone(),
        id,
        opened_over: serial.to_string(),
        device: device.clone(),
        pkg: pkg.to_string(),
        label: label.to_string(),
        since,
        settled: AtomicBool::new(false),
        live: AtomicBool::new(false),
    });
    watch_clipboard();
    kept().lock().unwrap().insert(
        id,
        Kept {
            phone: phone.to_string(),
            device,
            live: false,
            failed: None,
            lost: None,
            said_waiting: false,
            asked: false,
            busy: false,
        },
    );
    let window = match native::open(spec, host) {
        Ok(w) => w,
        Err(e) => {
            kept().lock().unwrap().remove(&id);
            return Err(e);
        }
    };
    // The window says when it is no longer opening: its first event.
    opening.armed = false;
    windows().lock().unwrap().insert(id, window);
    emit_windows(app);
    static KEEPER: std::sync::Once = std::sync::Once::new();
    let app = app.clone();
    KEEPER.call_once(move || {
        let _ = std::thread::Builder::new().name("apps-keeper".into()).spawn(move || keep(app));
    });
    Ok(())
}

/// What a window of this app asks of it: its news, the clipboard, its sound
/// menu, a drop. The way the window reaches its phone is the window's own,
/// and changes: it is asked of the window each time.
#[cfg(any(windows, target_os = "macos"))]
struct WindowHost {
    app: tauri::AppHandle,
    id: u64,
    /// The serial it opened over: what the page waits on while it opens.
    opened_over: String,
    /// What its title bar calls the phone.
    device: String,
    pkg: String,
    label: String,
    since: &'static str,
    /// Its first event says the window is no longer opening.
    settled: AtomicBool,
    /// It has shown its app.
    live: AtomicBool,
}

#[cfg(any(windows, target_os = "macos"))]
impl WindowHost {
    /// The window, while it is open.
    fn window(&self) -> Option<Arc<native::Window>> {
        windows().lock().unwrap().get(&self.id).cloned()
    }

    /// The way it reaches its phone now: its serial and the server's socket.
    fn route(&self) -> Option<link::Route> {
        self.window().map(|w| w.route())
    }

    fn serial(&self) -> String {
        self.route().map(|r| r.serial).unwrap_or_else(|| self.opened_over.clone())
    }
}

#[cfg(any(windows, target_os = "macos"))]
impl link::Host for WindowHost {
    fn event(&self, e: link::Event) {
        let (app, pkg) = (&self.app, &self.pkg);
        if !self.settled.swap(true, Ordering::SeqCst) {
            opening_changed(&self.opened_over, false);
        }
        let serial = self.serial();
        match e {
            link::Event::Live(after) => {
                self.live.store(true, Ordering::SeqCst);
                if let Some(k) = kept().lock().unwrap().get_mut(&self.id) {
                    k.live = true;
                    // Opened again from the page, it is back.
                    k.lost = None;
                    k.failed = None;
                    k.asked = false;
                }
                log::info!("apps [{serial}] {pkg}: on screen {}ms after {}", after.as_millis(), self.since);
                let _ = app.emit("apps:live", serde_json::json!({"serial": serial, "pkg": pkg, "ms": after.as_millis() as u64}));
                // The app's sound comes here with it, as the desktop's does —
                // once its picture has: the sound's own server is a second
                // JVM starting on the phone, which slowed the window it came with.
                crate::scrcpy::apps_audio(app, &serial, true);
            }
            link::Event::Failed(message) => {
                log::warn!("apps [{serial}] {pkg}: {message}");
                if let Some(k) = kept().lock().unwrap().get_mut(&self.id) {
                    k.failed = Some(Instant::now());
                    k.lost = None;
                }
                // A window that was showing its app says so itself: the page
                // only hears of one that never got its app.
                if !self.live.load(Ordering::SeqCst) {
                    let _ = app.emit(
                        "apps:problem",
                        serde_json::json!({"serial": serial, "pkg": pkg, "label": self.label, "message": message}),
                    );
                }
            }
            link::Event::Lost => {
                if let Some(k) = kept().lock().unwrap().get_mut(&self.id) {
                    if k.lost.is_none() {
                        k.lost = Some(Instant::now());
                        k.said_waiting = false;
                    }
                }
                if let Some(w) = self.window() {
                    note(&w, Some(format!("Reconnecting to {}…", phone_name(&self.device))), true);
                }
                wake_keeper();
            }
            link::Event::Back(after) => {
                if let Some(k) = kept().lock().unwrap().get_mut(&self.id) {
                    k.lost = None;
                    k.failed = None;
                    k.asked = false;
                    k.said_waiting = false;
                }
                let route = self.route();
                let to = route.as_ref().map_or("", |r| r.via);
                log::info!("apps [{serial}] {pkg}: its picture is back over {to}, {}ms after the last connection went", after.as_millis());
                if let Some(w) = self.window() {
                    w.say(None);
                }
                // The sound follows the window to the way it takes now.
                crate::scrcpy::apps_audio(app, &serial, true);
            }
            link::Event::Closed => {
                let was = kept().lock().unwrap().remove(&self.id);
                said().lock().unwrap().remove(&self.id);
                let (window, last) = {
                    let mut all = windows().lock().unwrap();
                    let window = all.remove(&self.id);
                    (window, !all.values().any(|w| w.serial() == serial))
                };
                if last {
                    crate::scrcpy::apps_audio(app, &serial, false);
                }
                // Closed while its connection was gone: its app waits for this
                // computer on the phone's server, and goes back to the phone
                // now — over whichever way reaches the phone.
                if let (Some(k), Some(w)) = (was, window) {
                    if k.lost.is_some() {
                        let app = app.clone();
                        std::thread::spawn(move || {
                            let devices = crate::adb::recent_devices(&app, Duration::from_secs(1));
                            if let Some(serial) = best_serial(&devices, &k.phone) {
                                let route = link::Route { serial, ..w.route() };
                                w.hand_back(&route);
                            }
                        });
                    }
                }
                emit_windows(app);
            }
        }
    }

    fn clipboard(&self) -> Option<String> {
        let text = clipboard::get(&self.app)?;
        let server = host::running(&self.serial())?;
        let mut last = server.clip_last.lock().unwrap();
        if *last == text {
            return None;
        }
        *last = text.clone();
        Some(text)
    }

    fn sound_menu(&self) -> Vec<sound::Item> {
        sound::menu_for(&self.app, &self.serial())
    }

    fn pick_sound(&self, choice: sound::Choice, window: Arc<dyn link::Surface>) {
        sound::pick(&self.app, &self.serial(), choice, window);
    }

    fn dropped(&self, paths: Vec<PathBuf>, window: Arc<dyn link::Surface>) {
        let Some(route) = self.route() else { return };
        drops::send(route.serial, route.socket, paths, window);
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
            let mut serials: Vec<String> = windows().lock().unwrap().values().map(|w| w.serial()).collect();
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
    let way = way_to(app, serial);
    let row = rows().lock().unwrap().get(&way.phone).and_then(|t| t.get(pkg).cloned());
    let label = row.map(|r| r.label).unwrap_or_else(|| pkg.to_string());
    let options: crate::scrcpy::MirrorOptions = serde_json::from_value(serde_json::json!({
        "serial": way.serial,
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
    let phones: HashMap<u64, String> = kept().lock().unwrap().iter().map(|(id, k)| (*id, k.phone.clone())).collect();
    windows()
        .lock()
        .unwrap()
        .values()
        .map(|w| OpenWindow {
            id: w.id,
            serial: w.serial(),
            phone: phones.get(&w.id).cloned().unwrap_or_else(|| w.serial()),
            pkg: w.pkg.clone(),
            label: w.label.clone(),
        })
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
            if serial.as_deref().map_or(true, |s| s == w.serial()) {
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
        via: via(serial),
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
            // The probe has no keeper: a window whose connection went stays as it is.
            link::Event::Lost => log::warn!("probe: the connection went"),
            link::Event::Back(after) => log::info!("probe: back {}ms after the connection went", after.as_millis()),
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
    // Its windows come from the server that runs it: over the cable when there is one.
    let way = way_to(app, serial);
    let serial = way.serial.as_str();
    let server = host::ensure(app, serial, &way.phone)?;
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
        let socket = host::running(serial).map(|s| s.socket.clone()).unwrap_or_default();
        let mine = |w: &&Arc<native::Window>| w.linux == Some(xid) && w.route().socket == socket;
        match v["kind"].as_str() {
            Some("open") if xid != 0 => {
                let (app, serial, v) = (app.clone(), serial.to_string(), v.clone());
                let mapped = Instant::now();
                std::thread::spawn(move || open_linux_window(&app, &serial, xid, &title, &v, mapped));
            }
            Some("title") => {
                if let Some(w) = windows().lock().unwrap().values().find(mine) {
                    w.set_title(&title);
                }
            }
            Some("closed") => {
                if let Some(w) = windows().lock().unwrap().values().find(mine) {
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
    let Some(server) = host::running(serial) else { return };
    if let Some(w) = windows().lock().unwrap().values().find(|w| w.linux == Some(xid) && w.route().socket == server.socket) {
        w.focus();
        return;
    }
    let devices = crate::adb::recent_devices(app, Duration::from_secs(3));
    let phone = phone_of(&devices, serial);
    let device = devices
        .iter()
        .find(|d| d.serial == serial)
        .and_then(|d| d.name.clone().or_else(|| (!d.model.is_empty()).then(|| d.model.clone())))
        .unwrap_or_default();
    // Its app, by window class: the icon on the window and its taskbar button.
    let class = v["cls"].as_str().unwrap_or("").to_lowercase();
    let instance = v["instance"].as_str().unwrap_or("").to_lowercase();
    let found = rows().lock().unwrap().get(&phone).and_then(|t| {
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
    let icon = icons::cached(app, &phone, &app_pkg, LINUX_ICON_VERSION);
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
        device,
        icon_png: icon,
        relaunch: None,
        relaunch_icon: None,
        cascade,
        linux: Some(xid),
        size: (w > 0 && h > 0).then_some((w, h)),
        direct: on_cable(serial),
        via: via(serial),
        clicked: mapped,
    };
    if let Err(e) = open_window(app, serial, &phone, &app_pkg, &label, spec, "Linux mapped it", Opening::new(serial)) {
        log::warn!("apps [{serial}] Linux window 0x{xid:x}: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::adb::DeviceInfo;

    fn device(serial: &str, state: &str, hardware: &str) -> DeviceInfo {
        DeviceInfo {
            serial: serial.into(),
            state: state.into(),
            model: "SM-S938B".into(),
            product: String::new(),
            connection: if on_cable(serial) { "usb" } else { "wifi" }.into(),
            brand: None,
            android_version: None,
            hardware_serial: Some(hardware.into()),
            name: None,
        }
    }

    #[test]
    fn a_phone_is_known_whichever_way_it_is_attached() {
        let list = [device("192.168.1.20:5555", "device", "R5CT30ABC")];
        assert_eq!(phone_of(&list, "192.168.1.20:5555"), "R5CT30ABC");
        // Not in the list: a cable's serial is the phone's own, an mDNS name carries it.
        assert_eq!(phone_of(&list, "R5CT30ABC"), "R5CT30ABC");
        assert_eq!(phone_of(&[], "adb-R5CT30ABC-vWgJpq._adb-tls-connect._tcp"), "R5CT30ABC");
        assert_eq!(phone_of(&[], "adb-R5CT30ABC-vWgJpq (2)._adb-tls-connect._tcp"), "R5CT30ABC");
        assert_eq!(phone_of(&[], "10.0.0.7:41233"), "10.0.0.7:41233");
    }

    #[test]
    fn the_cable_is_taken_once_it_has_settled() {
        let both = [
            device("192.168.1.20:5555", "device", "R5CT30ABC"),
            device("R5CT30ABC", "device", "R5CT30ABC"),
            // Another phone, on the cable: not this one's way.
            device("ZY22OTHER", "device", "ZY22OTHER"),
        ];
        assert_eq!(best_serial_by(&both, "R5CT30ABC", |_| true).as_deref(), Some("R5CT30ABC"));
        // Just plugged in: Wi-Fi until the cable has stayed in.
        assert_eq!(best_serial_by(&both, "R5CT30ABC", |_| false).as_deref(), Some("192.168.1.20:5555"));
        // A cable that has not settled is still better than nothing.
        let cable = [device("R5CT30ABC", "device", "R5CT30ABC")];
        assert_eq!(best_serial_by(&cable, "R5CT30ABC", |_| false).as_deref(), Some("R5CT30ABC"));
        // Not ready (unauthorized, offline), or not there: no way.
        let asking = [device("R5CT30ABC", "unauthorized", "R5CT30ABC")];
        assert_eq!(best_serial_by(&asking, "R5CT30ABC", |_| true), None);
        assert_eq!(best_serial_by(&both, "NOTHERE", |_| true), None);
    }

    #[test]
    fn the_title_bar_says_how() {
        assert_eq!(via("R5CT30ABC"), "USB");
        assert_eq!(via("emulator-5554"), "USB");
        assert_eq!(via("192.168.1.20:5555"), "Wi\u{2011}Fi");
        assert_eq!(via("adb-R5CT30ABC-vWgJpq._adb-tls-connect._tcp"), "Wi\u{2011}Fi");
    }
}
