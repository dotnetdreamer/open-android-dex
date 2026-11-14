//! "Send to computer", and the phone's photos that just arrive
//! (doc/send-to-computer.md).
//!
//! On the phone, Share → Send to computer (the launcher's SendActivity) hands
//! what was shared to the phone's app server, which tells this computer: a
//! file lands in Downloads, a link opens in the browser, text goes on the
//! clipboard, and one of this computer's notifications says so. The Photos
//! tab shows the phone's newest photos and screenshots as they are taken, and
//! "Keep a copy" saves each one into a folder here.
//!
//! Everything rides one connection per phone, the `send` link (link.rs), to
//! the same app server the phone's app windows use. Nothing listens on this
//! computer. The keeper below makes the link for every attached phone that
//! wants it — this computer takes shares, or the Photos tab is looking at
//! that phone, or Keep a copy is on for it — starting the server if nothing
//! else has (never over a cable that has not settled: a Samsung phone restarts
//! adbd about 5 s after the plug goes in), and lets the server go when nothing
//! wants it any more.
//!
//! - this file: what the person chose (send.json), the keeper, the commands.
//! - `link`: the `send` connection.
//! - `inbox`: what arrives — files, a link, text — and the notification.
//! - `photos`: each phone's pictures, their thumbnails, an original fetched.
//! - `keep`, `ledger`: Keep a copy, and its record in the folder itself.
//! - `words`: every sentence of it.

pub(crate) mod inbox;
pub(crate) mod keep;
mod ledger;
mod link;
pub(crate) mod photos;
mod pick;
mod words;

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};

use crate::apps::{self, host};

/// How often the keeper looks at the attached phones, when nothing wakes it sooner.
const KEEP_EVERY: Duration = Duration::from_secs(3);
/// A phone whose server would not start is tried again after this.
const RETRY_AFTER: Duration = Duration::from_secs(30);
/// The Photos tab says it is still looking every 30 s; quiet this long, it has gone. Generous:
/// WebKit slows the timers of a window out of sight to about one a minute.
const LOOKING_FOR: Duration = Duration::from_secs(150);
/// How often the phone looks for new pictures: with the page looking, and for Keep a copy alone.
const PHOTOS_EVERY_MS: u64 = 1000;
const PHOTOS_KEEP_EVERY_MS: u64 = 5000;
/// …and with the page's window hidden or minimised: slower, never stopped, so
/// the list stands (older pages and all) and there is no fresh snapshot to
/// make when the window comes back.
const PHOTOS_HIDDEN_EVERY_MS: u64 = 10_000;
/// While a phone's launcher is ready and its share entry still off, how often it is asked to look again.
const LOOK_EVERY: Duration = Duration::from_secs(10);

// ── what the person chose ──

/// Kept in send.json, beside notifications.json. Read field by field
/// ([`parse_settings`]): one this build cannot read is that field's default,
/// never the whole file's.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// "Receive files from my phone": the phone's share entry is on and shares come here.
    pub share: bool,
    /// "Open links automatically": a link sent from the phone opens in the browser.
    pub open_links: bool,
    /// Keep a copy, per phone (by its `ro.serialno`).
    pub keep: BTreeMap<String, keep::Pref>,
    /// The phones whose share entry this computer turned on: with "Take what
    /// my phone sends" off, each is linked once more when it is attached, to
    /// turn it off, and then let go.
    #[serde(skip_serializing_if = "BTreeSet::is_empty")]
    pub entry_on: BTreeSet<String>,
    /// What a newer build wrote that this one does not know: kept as it was.
    #[serde(flatten)]
    pub other: serde_json::Map<String, Value>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { share: true, open_links: true, keep: BTreeMap::new(), entry_on: BTreeSet::new(), other: serde_json::Map::new() }
    }
}

// ── what there is now ──

#[derive(Default)]
struct State {
    settings: Settings,
    /// Each phone's link, by the phone.
    links: HashMap<String, Arc<link::Link>>,
    /// The Photos tab looks at these phones: since when it last said so.
    /// …and whether its window is hidden.
    looking: HashMap<String, (Instant, bool)>,
    /// Each attached phone: the way to it, and its name.
    here: HashMap<String, (String, String)>,
    /// Every way to a phone seen in this run: the phone, and its name ([`phone_by`]).
    seen: HashMap<String, (String, String)>,
    /// A phone whose server would not start: when, and why.
    refused: HashMap<String, (Instant, String)>,
    /// A phone being set up for sending ("Set it up"): its link asked to look again after.
    setting_up: HashMap<String, Instant>,
}

fn state() -> MutexGuard<'static, State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

static APP: OnceLock<AppHandle> = OnceLock::new();
static CLOSING: AtomicBool = AtomicBool::new(false);

/// This computer's own name, as the phone's card says it ("Sending to IK's MacBook Pro…").
pub(crate) fn computer_name() -> String {
    static NAME: OnceLock<String> = OnceLock::new();
    NAME.get_or_init(dexremote::computer::computer_name).clone()
}

pub(crate) fn os() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

/// The keeper: debug builds may point arrivals elsewhere for the tests.
pub(crate) fn test_override(name: &str) -> Option<String> {
    if cfg!(debug_assertions) {
        std::env::var(name).ok().filter(|v| !v.is_empty())
    } else {
        None
    }
}

// ── this app's run ──

/// Read what the person chose, empty the thumbnails an earlier run left, and start the keeper.
pub fn start(app: &AppHandle) {
    let _ = APP.set(app.clone());
    state().settings = load(app);
    photos::forget_all(app);
    // What a crash left of a file on its way here, removed before anything lands.
    if let Ok(d) = app.path().app_data_dir() {
        let _ = std::fs::create_dir_all(&d);
        inbox::landing_at(d.join("send-landing.json"));
    }
    inbox::load_recent(app);
    // A debug build's tests open the Photos tab without a click (OADX_SEND_TAB=photos).
    if test_override("OADX_SEND_TAB").as_deref() == Some("photos") {
        let app = app.clone();
        std::thread::spawn(move || {
            for _ in 0..4 {
                std::thread::sleep(Duration::from_secs(3));
                let _ = app.emit("send:show", ());
            }
        });
    }
    let app = app.clone();
    let _ = std::thread::Builder::new().name("send-keeper".into()).spawn(move || keep(app));
}

/// This app is exiting: the keeper stops, every link closes, and whatever is
/// on its way here is cut short, its part and its reservation removed.
pub fn stop() {
    CLOSING.store(true, Ordering::SeqCst);
    let links: Vec<Arc<link::Link>> = state().links.drain().map(|(_, l)| l).collect();
    for l in links {
        l.close();
    }
    inbox::stop_all();
    keep::stop_all();
    if let Some(app) = APP.get() {
        photos::forget_all(app);
    }
    wake();
}

fn bell() -> &'static (Mutex<bool>, Condvar) {
    static BELL: OnceLock<(Mutex<bool>, Condvar)> = OnceLock::new();
    BELL.get_or_init(|| (Mutex::new(false), Condvar::new()))
}

/// The keeper looks now, rather than in a few seconds.
pub(crate) fn wake() {
    let (rung, bell) = bell();
    *rung.lock().unwrap() = true;
    bell.notify_all();
}

/// Why a phone's link is wanted: any one of the three.
pub(crate) fn wanted(share: bool, looking: bool, keeping: bool) -> bool {
    share || looking || keeping
}

/// How often the phone looks for new pictures, for what wants them now:
/// `looking`, the page (and whether its window is hidden); None, nothing does.
pub(crate) fn photos_every(looking: Option<bool>, keeping: bool) -> Option<u64> {
    match (looking, keeping) {
        (Some(false), _) => Some(PHOTOS_EVERY_MS),
        (_, true) => Some(PHOTOS_KEEP_EVERY_MS),
        (Some(true), false) => Some(PHOTOS_HIDDEN_EVERY_MS),
        (None, false) => None,
    }
}

/// Whether the attached phones are other ones than before: as sets — two maps
/// with the same phones list them in either order.
fn phones_changed<V>(was: &HashMap<String, V>, now: &HashMap<String, V>) -> bool {
    was.len() != now.len() || now.keys().any(|p| !was.contains_key(p))
}

fn keep(app: AppHandle) {
    loop {
        {
            let (rung, bell) = bell();
            let (mut rung, _) = bell.wait_timeout_while(rung.lock().unwrap(), KEEP_EVERY, |r| !*r).unwrap();
            *rung = false;
        }
        if CLOSING.load(Ordering::SeqCst) {
            return;
        }
        let devices = crate::adb::recent_devices(&app, Duration::from_secs(3));
        let mut here: HashMap<String, (String, String)> = HashMap::new();
        for d in devices.iter().filter(|d| d.state == "device") {
            let phone = apps::phone_id(d);
            if here.contains_key(&phone) {
                continue;
            }
            if let Some(serial) = apps::best_serial(&devices, &phone) {
                let name = devices.iter().find(|x| x.serial == serial).map(apps::device_name).unwrap_or_default();
                here.insert(phone, (serial, name));
            }
        }
        let gone: Vec<Arc<link::Link>> = {
            let mut st = state();
            for d in devices.iter().filter(|d| d.state == "device") {
                st.seen.insert(d.serial.clone(), (apps::phone_id(d), apps::device_name(d)));
            }
            let changed = phones_changed(&st.here, &here);
            st.here = here.clone();
            let gone_phones: Vec<String> = st.links.keys().filter(|p| !here.contains_key(*p)).cloned().collect();
            let gone = gone_phones.iter().filter_map(|p| st.links.remove(p)).collect();
            drop(st);
            if changed {
                changed_now(&app);
            }
            gone
        };
        for l in gone {
            l.close();
        }
        for (phone, (serial, name)) in &here {
            keep_one(&app, phone, serial, name);
        }
        keep::tick(&app);
    }
}

/// One attached phone: its link up if anything wants it, and down otherwise.
fn keep_one(app: &AppHandle, phone: &str, serial: &str, name: &str) {
    let (share, looking, keeping, switch_off) = {
        let mut st = state();
        st.looking.retain(|_, (at, _)| at.elapsed() < LOOKING_FOR);
        let s = &st.settings;
        (s.share, st.looking.get(phone).map(|(_, hidden)| *hidden), s.keep.get(phone).is_some_and(|k| k.on), !s.share && s.entry_on.contains(phone))
    };
    let _ = name;
    // Share switched off while this phone was away, its entry still on: linked once more, to turn it off.
    if !wanted(share, looking.is_some(), keeping) && !switch_off {
        let l = state().links.remove(phone);
        if let Some(l) = l {
            log::info!("send [{serial}] the link to the phone's server is no longer wanted");
            l.close();
            changed_now(app);
        }
        host::release_send(phone);
        return;
    }
    let server = match host::adopt(app, serial, phone) {
        Some(s) => s,
        None => {
            let settled = crate::adb::attached_for(serial).is_some_and(|t| t >= apps::CABLE_SETTLE);
            if apps::on_cable(serial) && !settled {
                return;
            }
            if state().refused.get(phone).is_some_and(|(at, _)| at.elapsed() < RETRY_AFTER) || CLOSING.load(Ordering::SeqCst) {
                return;
            }
            match host::ensure_quietly(app, serial, phone) {
                Ok(s) => s,
                Err(e) => {
                    log::warn!("send [{serial}] the phone's app server would not start: {e}");
                    // A phone gone for a moment (the cable's link made again) is tried at the next
                    // look, not held off: only a server that would not start is.
                    if !vanished(&e) {
                        state().refused.insert(phone.to_string(), (Instant::now(), e));
                        changed_now(app);
                    }
                    return;
                }
            }
        }
    };
    server.hold_for_send(true);
    state().refused.remove(phone);
    let current = state().links.get(phone).filter(|l| l.alive() && l.socket == server.socket).cloned();
    let l = match current {
        Some(l) => l,
        None => match link::Link::open(app, serial, phone, &server.socket, share) {
            Ok(l) => {
                let old = state().links.insert(phone.to_string(), l.clone());
                if let Some(old) = old {
                    old.close();
                }
                hello_seen(app, phone, &l.hello());
                photos::link_up(app, phone);
                changed_now(app);
                l
            }
            Err(e) => {
                log::warn!("send [{serial}] no link to the phone's server: {e}");
                return;
            }
        },
    };
    // Set up again after "Set it up", or after any install of this build's
    // launcher (a desktop's start): the phone looks at its launcher once more
    // (one package-manager question there), and enables the share entry if it
    // can. A ready launcher whose entry is still off is asked again now and
    // then; a missing or old one, never — only an install changes that.
    let h = l.hello();
    let unsettled = share && h.launcher == "ready" && !h.entry;
    let installed = state().setting_up.remove(phone).is_some();
    if installed || (unsettled && l.look_due(LOOK_EVERY)) {
        let _ = l.send(&json!({"t": "look"}));
    }
    l.want_photos(photos_every(looking, keeping));
}

/// A launcher was just installed over `serial`: its phone's link looks at it
/// again — the phone, by its `ro.serialno`, whichever way (cable, Wi‑Fi) its link goes.
pub(crate) fn launcher_installed(serial: &str) {
    let Some(app) = APP.get() else { return };
    let (phone, _) = phone_of(app, serial);
    state().setting_up.insert(phone, Instant::now());
    wake();
}

/// What a hello said of the share entry: a phone whose entry this computer
/// turned on is remembered, and forgotten once it says the entry is off.
pub(crate) fn hello_seen(app: &AppHandle, phone: &str, h: &link::Hello) {
    let changed = {
        let mut st = state();
        let share = st.settings.share;
        let set = &mut st.settings.entry_on;
        if h.entry && share {
            set.insert(phone.to_string())
        } else if !h.entry {
            set.remove(phone)
        } else {
            false
        }
    };
    if changed {
        save(app);
        wake();
    }
}

/// What adb says when the phone is not there at that moment, rather than refusing.
pub(crate) fn vanished(e: &str) -> bool {
    e.contains("not found") || e.contains("offline") || e.contains("no devices")
}

/// A link ended: the keeper makes it again, and what was on its way says it was lost.
pub(crate) fn link_ended(app: &AppHandle, l: &link::Link) {
    {
        let mut st = state();
        if st.links.get(&l.phone).is_some_and(|x| std::ptr::eq(Arc::as_ptr(x), l)) {
            st.links.remove(&l.phone);
        }
    }
    inbox::link_lost(app, &l.phone);
    keep::link_down(&l.phone);
    changed_now(app);
    if !CLOSING.load(Ordering::SeqCst) {
        wake();
    }
}

/// `phone`'s server would not start, the last time it was tried.
pub(crate) fn refused_of(phone: &str) -> bool {
    state().refused.contains_key(phone)
}

/// `phone`'s link, if it is up.
pub(crate) fn link_of(phone: &str) -> Option<Arc<link::Link>> {
    state().links.get(phone).filter(|l| l.alive()).cloned()
}

/// The phone the page's `serial` is: its `ro.serialno`, and its name.
pub(crate) fn phone_of(app: &AppHandle, serial: &str) -> (String, String) {
    let devices = crate::adb::recent_devices(app, Duration::from_secs(3));
    phone_by(&devices, &mut state().seen, serial)
}

/// The phone `serial` is a way to, and its name, from the list — remembered in
/// `seen` — or, a way gone from it a moment ago (a phone on Wi‑Fi dropping as
/// its Keep a copy switch is pressed), the phone it was: never a phone of its
/// own under its `ip:port`. A way never seen is its own, as before.
pub(crate) fn phone_by(devices: &[crate::adb::DeviceInfo], seen: &mut HashMap<String, (String, String)>, serial: &str) -> (String, String) {
    if let Some(d) = devices.iter().find(|d| d.serial == serial) {
        let p = (apps::phone_id(d), apps::device_name(d));
        seen.insert(serial.to_string(), p.clone());
        return p;
    }
    seen.get(serial).cloned().unwrap_or_else(|| (serial.to_string(), "your phone".to_string()))
}

/// A phone's name, as the notifications and status lines say it.
pub(crate) fn phone_name(phone: &str) -> String {
    state().here.get(phone).map(|(_, n)| n.clone()).filter(|n| !n.is_empty()).unwrap_or_else(|| "your phone".into())
}

/// Something the page shows changed (a link, a hello): it asks again.
pub(crate) fn changed(app: &AppHandle) {
    changed_now(app);
}

fn changed_now(app: &AppHandle) {
    let _ = app.emit("send:state", ());
}

// ── what the page asks ──

/// What the Photos tab shows of "Send to computer" for one phone.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SendView {
    pub share: bool,
    pub open_links: bool,
    /// The phone's server is linked.
    pub linked: bool,
    /// `ready`, `old`, `missing`, or "" while not linked.
    pub launcher: String,
    pub entry: bool,
    /// The launcher's knock reaches this phone's server: port 7190 is its, not another app's.
    pub port: bool,
    /// Its server would not start, and why.
    pub refused: Option<String>,
    pub phone_name: String,
    pub computer: String,
    pub this_computer: &'static str,
    pub show_in_folder: &'static str,
}

#[tauri::command(async)]
pub fn send_state(app: AppHandle, serial: String) -> SendView {
    let (phone, name) = phone_of(&app, &serial);
    let l = link_of(&phone);
    let st = state();
    let hello = l.as_ref().map(|l| l.hello());
    SendView {
        share: st.settings.share,
        open_links: st.settings.open_links,
        linked: l.is_some(),
        launcher: hello.as_ref().map(|h| h.launcher.clone()).unwrap_or_default(),
        entry: hello.as_ref().is_some_and(|h| h.entry),
        port: hello.as_ref().is_some_and(|h| h.port),
        refused: st.refused.get(&phone).map(|(_, e)| e.clone()),
        phone_name: name,
        computer: computer_name(),
        this_computer: words::this_computer(),
        show_in_folder: words::show_in_folder(),
    }
}

/// "Receive files from my phone" and "Open links automatically", changed.
#[tauri::command(async)]
pub fn send_set(app: AppHandle, share: Option<bool>, open_links: Option<bool>) {
    let links: Vec<Arc<link::Link>> = {
        let mut st = state();
        if let Some(o) = open_links {
            st.settings.open_links = o;
        }
        let share_changed = share.is_some_and(|s| s != st.settings.share);
        if let Some(s) = share {
            st.settings.share = s;
        }
        if share_changed {
            st.links.values().cloned().collect()
        } else {
            Vec::new()
        }
    };
    let share = state().settings.share;
    // Said again on each link: the phone sets its share entry to match.
    for l in links {
        let _ = l.send(&json!({"t": "send", "v": 1, "computer": computer_name(), "os": os(), "share": share}));
    }
    save(&app);
    wake();
    changed_now(&app);
}

/// "Set it up" / "Update it": this build's launcher put on the phone, for its
/// share entry — the touchpad's install, with its own words — and the phone
/// asked to look again.
#[tauri::command(async)]
pub fn send_setup(app: AppHandle, serial: String) -> Result<(), String> {
    let (phone, _) = phone_of(&app, &serial);
    let devices = crate::adb::recent_devices(&app, Duration::from_secs(3));
    let way = apps::best_serial(&devices, &phone).unwrap_or(serial);
    crate::adb::ensure_launcher_for_send(&app, &way)?;
    state().setting_up.insert(phone.clone(), Instant::now());
    if let Some(l) = link_of(&phone) {
        let _ = l.send(&json!({"t": "look"}));
        state().setting_up.remove(&phone);
    }
    wake();
    Ok(())
}

/// The Photos tab looks at `serial`'s photos, or no longer: the link wanted
/// for it, the phone asked to look once a second — every 10 s while the
/// page's window is `hidden`, which changes only how often.
#[tauri::command(async)]
pub fn photos_watch(app: AppHandle, serial: String, on: bool, hidden: Option<bool>, again: Option<bool>) {
    let (phone, _) = phone_of(&app, &serial);
    let link = {
        let mut st = state();
        if on {
            st.looking.insert(phone.clone(), (Instant::now(), hidden.unwrap_or(false)));
        } else {
            st.looking.remove(&phone);
        }
        // "Try again": a server that would not start is tried at the next look, not after its 30 s.
        if again == Some(true) {
            st.refused.remove(&phone);
        }
        st.links.get(&phone).cloned()
    };
    if on {
        photos::page_looks(&phone);
    }
    if again == Some(true) {
        // …and a list that failed is asked for afresh: the look stopped here, so the keeper's next
        // `want_photos` starts it again, and the phone answers with a whole new snapshot.
        photos::again(&phone);
        if let Some(l) = link {
            l.want_photos(None);
        }
    }
    wake();
}

/// A click on one of "Send to computer"'s notifications (notifications/mod.rs): its body, or button `button`.
pub(crate) fn toast_clicked(app: &AppHandle, id: &str, button: Option<i64>) {
    inbox::toast_clicked(app, id, button);
}

/// This app's window, in front, at the Photos tab — where what arrived is listed.
pub(crate) fn show_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
    let _ = app.emit("send:show", ());
}

// ── send.json ──

fn settings_path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join("send.json"))
}

pub(crate) fn settings() -> Settings {
    state().settings.clone()
}

pub(crate) fn set_keep(app: &AppHandle, phone: &str, pref: Option<keep::Pref>) {
    {
        let mut st = state();
        match pref {
            Some(p) => {
                st.settings.keep.insert(phone.to_string(), p);
            }
            None => {
                st.settings.keep.remove(phone);
            }
        }
    }
    save(app);
    wake();
}

fn load(app: &AppHandle) -> Settings {
    settings_path(app).and_then(|p| std::fs::read(p).ok()).map(|b| parse_settings(&b)).unwrap_or_default()
}

/// send.json as written, field by field: one that cannot be read is its own
/// default, and the rest stand — a bad "keep" never turns shares back on.
pub(crate) fn parse_settings(bytes: &[u8]) -> Settings {
    let mut s = Settings::default();
    let Ok(Value::Object(map)) = serde_json::from_slice::<Value>(bytes) else { return s };
    for (k, v) in map {
        match k.as_str() {
            "share" => s.share = v.as_bool().unwrap_or(s.share),
            "openLinks" => s.open_links = v.as_bool().unwrap_or(s.open_links),
            "keep" => {
                for (phone, pref) in v.as_object().into_iter().flatten() {
                    if let Some(p) = keep::Pref::parse(pref) {
                        s.keep.insert(phone.clone(), p);
                    }
                }
            }
            "entryOn" => s.entry_on = v.as_array().into_iter().flatten().filter_map(|x| x.as_str().map(String::from)).collect(),
            _ => {
                s.other.insert(k, v);
            }
        }
    }
    s
}

fn save(app: &AppHandle) {
    static SAVING: Mutex<()> = Mutex::new(());
    let _one = SAVING.lock().unwrap();
    let settings = state().settings.clone();
    let Some(path) = settings_path(app) else { return };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_vec_pretty(&settings) {
        let tmp = path.with_extension("json.tmp");
        if std::fs::write(&tmp, json).is_ok() {
            let _ = std::fs::rename(&tmp, &path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_way_that_drops_is_still_the_phone_it_was() {
        let wifi = crate::adb::DeviceInfo {
            serial: "192.168.1.5:40123".into(),
            state: "device".into(),
            model: "SM-S921B".into(),
            product: "e1s".into(),
            connection: "wifi".into(),
            brand: Some("samsung".into()),
            android_version: Some("15".into()),
            hardware_serial: Some("RFCY90YWS1P".into()),
            name: Some("Galaxy S24".into()),
        };
        let mut seen = HashMap::new();
        let phone = ("RFCY90YWS1P".to_string(), "Galaxy S24".to_string());
        assert_eq!(phone_by(std::slice::from_ref(&wifi), &mut seen, &wifi.serial), phone);
        // Gone from the list a moment later, as a switch is pressed: still that phone, never its ip:port.
        assert_eq!(phone_by(&[], &mut seen, &wifi.serial), phone);
        // A way never seen is its own, as before.
        assert_eq!(phone_by(&[], &mut seen, "10.0.0.9:5555"), ("10.0.0.9:5555".to_string(), "your phone".to_string()));
    }

    #[test]
    fn the_defaults_take_shares_and_open_links() {
        let s = Settings::default();
        assert!(s.share && s.open_links && s.keep.is_empty());
        assert_eq!(parse_settings(b"not json"), Settings::default());
        assert_eq!(parse_settings(b"{}"), Settings::default());
    }

    #[test]
    fn settings_are_read_and_written_with_an_unknown_field_kept_out_of_the_way() {
        let s = parse_settings(br#"{"share": false, "openLinks": true, "fromTheFuture": {"x": 1},
            "keep": {"R5C": {"on": true, "root": "/tmp/k", "scope": "all", "since": 0, "folder": "Galaxy"}}}"#);
        assert!(!s.share && s.open_links);
        assert!(s.keep["R5C"].on);
        assert_eq!(s.keep["R5C"].scope, "all");
        assert_eq!(s.other["fromTheFuture"], json!({"x": 1}));
        let back: Value = serde_json::from_slice(&serde_json::to_vec(&s).unwrap()).unwrap();
        assert_eq!(back["fromTheFuture"], json!({"x": 1}));
        assert_eq!(back["share"], json!(false));
        assert_eq!(parse_settings(&serde_json::to_vec(&s).unwrap()), s);
    }

    #[test]
    fn one_field_that_cannot_be_read_is_that_fields_default_only() {
        let s = parse_settings(br#"{"share": false, "openLinks": "yes", "entryOn": ["R5C", 7],
            "keep": {"A": {"on": true, "root": 5, "scope": "all", "since": "x", "folder": "Galaxy"}, "B": 3}}"#);
        assert!(!s.share, "share kept though openLinks is not a bool");
        assert!(s.open_links);
        assert_eq!(s.entry_on.iter().collect::<Vec<_>>(), ["R5C"]);
        let a = &s.keep["A"];
        assert!(a.on && a.scope == "all" && a.folder == "Galaxy" && a.root.is_empty() && a.since == 0);
        assert!(!s.keep.contains_key("B"));
        assert!(parse_settings(b"[1, 2]").share);
        let back = parse_settings(&serde_json::to_vec(&s).unwrap());
        assert_eq!(back, s);
    }

    #[test]
    fn the_phones_changed_only_when_they_are_other_ones() {
        let map = |phones: &[&str]| phones.iter().map(|p| (p.to_string(), ("serial".to_string(), "name".to_string()))).collect::<HashMap<_, _>>();
        // The same two phones, in maps made one after another: never "changed", whatever order each lists them in.
        for _ in 0..200 {
            assert!(!phones_changed(&map(&["A", "B"]), &map(&["B", "A"])));
        }
        assert!(phones_changed(&map(&["A"]), &map(&["A", "B"])));
        assert!(phones_changed(&map(&["A", "B"]), &map(&["A"])));
        assert!(phones_changed(&map(&["A"]), &map(&["B"])));
        assert!(!phones_changed(&map(&[]), &map(&[])));
    }

    #[test]
    fn wanted_is_any_of_three_reasons() {
        assert!(!wanted(false, false, false));
        assert!(wanted(true, false, false));
        assert!(wanted(false, true, false));
        assert!(wanted(false, false, true));
        assert_eq!(photos_every(Some(false), true), Some(PHOTOS_EVERY_MS));
        assert_eq!(photos_every(Some(false), false), Some(PHOTOS_EVERY_MS));
        assert_eq!(photos_every(None, true), Some(PHOTOS_KEEP_EVERY_MS));
        assert_eq!(photos_every(None, false), None);
        // A hidden window slows the look, never stops it: Keep a copy's pace if it is on.
        assert_eq!(photos_every(Some(true), false), Some(PHOTOS_HIDDEN_EVERY_MS));
        assert_eq!(photos_every(Some(true), true), Some(PHOTOS_KEEP_EVERY_MS));
    }
}
