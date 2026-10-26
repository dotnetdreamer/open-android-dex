//! The phone's notifications as this computer's own (doc/phone-notifications.md).
//!
//! A notification that would have interrupted the phone's owner comes up here
//! as one of this computer's: on Windows a toast under the phone app's own
//! name and icon, on a Mac a banner — the sender's picture, what it says, a
//! reply box where the app takes a reply, the app's own buttons, and a call
//! as a call, with answer and decline. A click opens the app where the person
//! is working: in a window of its own here (the Apps page's), at the
//! conversation; on the phone's desktop if that is running; else on the phone.
//! Read or dismissed on the phone, it goes from here too.
//!
//! What counts as news is the phone's own decision (AppNotifications.java on
//! the phone). What is shown of it is this computer's: the switch, each app's
//! own switch, whether a toast says what the notification says, and two rules
//! of tact — no banner for the app whose window here is the one in front (its
//! toast goes quietly into the notification center), and nothing while the
//! phone's own desktop is in front, which shows its own.
//!
//! - this file: what to show and when, what the person chose
//!   (notifications.json), the keeper — which has every attached phone's app
//!   server listen while the switch is on — and what a click asks of the phone.
//! - `toast`: one toast as plain data, and Windows' XML of it.
//! - `windows`, `macos`: the system's notifications; `unsupported` elsewhere.

mod toast;

#[cfg_attr(windows, path = "windows.rs")]
#[cfg_attr(target_os = "macos", path = "macos.rs")]
#[cfg_attr(not(any(windows, target_os = "macos")), path = "unsupported.rs")]
mod native;

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};

use crate::apps::{self, host, icons};
use toast::{Act, Button, From, Kind, Reply, Style, Toast};

use native::Click;

/// What this computer is called in a sentence: the page's word (lib/host.ts).
const THIS_COMPUTER: &str = if cfg!(target_os = "macos") { "this Mac" } else { "this PC" };
/// How often the keeper looks at the attached phones, when nothing wakes it sooner.
const KEEP_EVERY: Duration = Duration::from_secs(3);
/// A phone whose server would not start, or that would not listen, is tried
/// again after this, not on every look.
const RETRY_AFTER: Duration = Duration::from_secs(30);
/// An ask with no answer in this long is made again: it, or its answer, went
/// with a control connection that dropped and was made again.
const ASK_AGAIN: Duration = Duration::from_secs(10);
/// The most apps remembered; the one heard from longest ago goes.
const APPS_KEPT: usize = 200;
/// How many of an app's own buttons a toast has, beside a reply box and "Turn off".
const BUTTONS: usize = 3;
/// What this app's own toast saying a reply did not go is called, before the notification's id.
const REPLY_FAILED: &str = "reply-";

// ── what the person chose ──

/// Kept in notifications.json, in this app's settings.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// The phone's notifications come up here at all. On until turned off:
    /// it is what a phone plugged into a computer is expected to do, and the
    /// first one introduces itself with a way to turn it off.
    pub on: bool,
    /// A toast says what the notification says; off, only which app has something.
    pub content: bool,
    /// The computer's own notification sound.
    pub sound: bool,
    /// A ringing call comes up as a call: it rings, with answer and decline.
    pub calls: bool,
    /// Dismissing one here dismisses it on the phone too.
    pub clear_on_phone: bool,
    /// The toast that says what this is has been shown.
    pub introduced: bool,
    /// Every app that has notified here, by package.
    pub apps: BTreeMap<String, AppPref>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            on: true,
            content: true,
            sound: true,
            calls: true,
            clear_on_phone: false,
            introduced: false,
            apps: BTreeMap::new(),
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct AppPref {
    pub label: String,
    /// Its notifications are not shown here.
    pub muted: bool,
    /// When it last had news, in ms since the epoch; 0 if it never has (only seen on the phone).
    pub last: i64,
    /// How many times it has.
    pub count: u64,
    /// The phone it last came from: whose icon this computer has for it.
    pub phone: String,
}

// ── what there is now ──

/// One phone, as far as its notifications go.
struct Phone {
    /// What its toasts say it is: its own name, else its model.
    name: String,
    /// The control connection it was last asked on: its server's socket, and
    /// which of that server's connections. The phone listens for that one
    /// alone and stops when it goes, so a new one — a new server, or the same
    /// one reached again — is asked again.
    asked_on: Option<(String, u64)>,
    listening: Listening,
    /// When it was last asked, or last answered: how long an ask has gone
    /// unanswered, or a refusal has stood.
    at: Instant,
    /// What it has now, by key: what a toast is about, and the Apps page's counts.
    active: HashMap<String, Item>,
    /// It is attached now, one way or another.
    attached: bool,
}

impl Phone {
    /// What it said, or what became of it, as of now.
    fn now(&mut self, listening: Listening) {
        self.listening = listening;
        self.at = Instant::now();
    }

    /// Its server asked again: never asked, or asked with no answer for a
    /// while, or a refusal that has stood a while.
    fn ask_again(&self) -> bool {
        match self.listening {
            Listening::Off => true,
            Listening::Asked => self.at.elapsed() >= ASK_AGAIN,
            Listening::Refused(_) => self.at.elapsed() >= RETRY_AFTER,
            Listening::Yes => false,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
enum Listening {
    Off,
    /// Asked, and no answer yet.
    Asked,
    Yes,
    /// The phone will not, or its server would not start: why.
    Refused(String),
}

/// One notification, as the phone describes it.
#[derive(Clone, Debug, Default, PartialEq)]
struct Item {
    key: String,
    pkg: String,
    /// The app's name.
    app: String,
    title: String,
    text: String,
    category: String,
    when: i64,
    test: bool,
    actions: Vec<Action>,
    /// A ringing call's own answer and decline (CallStyle's), when it has them.
    /// With neither, its buttons are the dialer's own, in `actions`.
    answer: bool,
    decline: bool,
}

/// One of the app's own buttons.
#[derive(Clone, Debug, Default, PartialEq)]
struct Action {
    /// Its index in the phone's notification.
    i: i64,
    label: String,
    /// It takes a reply.
    reply: bool,
    /// The app's own word for what goes in its reply box.
    hint: String,
    /// A call's button: the colour its app gave its words (`#rrggbb`), if any.
    tint: String,
}

impl Item {
    fn from(v: &Value) -> Item {
        let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
        Item {
            key: s("key"),
            pkg: s("pkg"),
            app: s("app"),
            title: s("title"),
            text: s("text"),
            category: s("category"),
            when: v["when"].as_i64().unwrap_or(0),
            test: v["test"].as_bool().unwrap_or(false),
            actions: v["actions"]
                .as_array()
                .map(|a| {
                    a.iter()
                        .map(|a| Action {
                            i: a["i"].as_i64().unwrap_or(-1),
                            label: a["label"].as_str().unwrap_or("").to_string(),
                            reply: a["reply"].as_bool().unwrap_or(false),
                            hint: a["hint"].as_str().unwrap_or("").to_string(),
                            tint: a["tint"].as_str().unwrap_or("").to_string(),
                        })
                        .filter(|a| a.i >= 0 && !a.label.is_empty())
                        .collect()
                })
                .unwrap_or_default(),
            answer: v["answer"].as_bool().unwrap_or(false),
            decline: v["decline"].as_bool().unwrap_or(false),
        }
    }
}

/// A toast up now: what it is about, and how to take it down.
#[derive(Clone, Debug)]
struct Shown {
    phone: String,
    kind: Kind,
    /// The notification it is — or, for this app's own, the one it is about, if any.
    item: Item,
    from: From,
    group: String,
    avatar: Option<PathBuf>,
}

struct State {
    settings: Settings,
    phones: HashMap<String, Phone>,
    shown: HashMap<String, Shown>,
}

fn state() -> MutexGuard<'static, State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE
        .get_or_init(|| Mutex::new(State { settings: Settings::default(), phones: HashMap::new(), shown: HashMap::new() }))
        .lock()
        .unwrap()
}

fn phone_mut<'a>(st: &'a mut State, phone: &str) -> &'a mut Phone {
    st.phones.entry(phone.to_string()).or_insert_with(|| Phone {
        name: String::new(),
        asked_on: None,
        listening: Listening::Off,
        at: Instant::now(),
        active: HashMap::new(),
        attached: true,
    })
}

/// Held while what is up changes — this run's record of it (`State::shown`)
/// and the system's own toasts together — and while a switch changes what may
/// be up: a toast going up, one coming down and a switch turned off happen one
/// after another, never through each other, so nothing goes up after the
/// person switched it off, and nothing taken down comes back. Taken before
/// `state()`, never while it is held; never while fetching from a phone.
fn up() -> MutexGuard<'static, ()> {
    static UP: Mutex<()> = Mutex::new(());
    UP.lock().unwrap_or_else(|e| e.into_inner())
}

/// [`up`] held: what the functions that change what is up are handed, so none is called without it.
type Up = MutexGuard<'static, ()>;

/// This app is exiting: nothing goes up after its toasts were cleared, no
/// picture is written after its folder went, and the keeper stops.
static CLOSING: AtomicBool = AtomicBool::new(false);

/// What the notifications' own thread is given to do: the phone's word, and
/// a click — both arrive on threads that must not wait (the control
/// connection's reader, the system's) — in the order they came.
enum Work {
    Said { serial: String, phone: String, v: Value },
    Click(Click),
}

fn work(w: Work) {
    static WORK: OnceLock<Mutex<Option<Sender<Work>>>> = OnceLock::new();
    let slot = WORK.get_or_init(|| Mutex::new(None));
    let mut tx = slot.lock().unwrap();
    if tx.is_none() {
        let (send, rx) = mpsc::channel();
        let Some(app) = APP.get().cloned() else { return };
        let _ = std::thread::Builder::new().name("notifications".into()).spawn(move || worker(app, rx));
        *tx = Some(send);
    }
    if let Some(tx) = tx.as_ref() {
        let _ = tx.send(w);
    }
}

static APP: OnceLock<AppHandle> = OnceLock::new();

// ── this app's run ──

/// Read what the person chose, clear what an earlier run left in the
/// notification center (nothing here can act on it now), and start the keeper.
pub fn start(app: &AppHandle) {
    let _ = APP.set(app.clone());
    state().settings = load(app);
    native::start(app, clicked);
    native::clear();
    if let Some(dir) = images_dir(app) {
        let _ = std::fs::remove_dir_all(&dir);
    }
    let app = app.clone();
    let _ = std::thread::Builder::new().name("notifications-keeper".into()).spawn(move || keep(app));
}

/// This app is exiting: its toasts go with it, since nothing could act on
/// them, and so do the pictures they showed — for good: what the phone says
/// meanwhile, or a toast on its way, puts nothing up after this, and the
/// keeper asks no phone for anything more.
pub fn stop() {
    {
        let _up = up();
        CLOSING.store(true, Ordering::SeqCst);
        native::clear();
        if let Some(dir) = APP.get().and_then(images_dir) {
            let _ = std::fs::remove_dir_all(dir);
        }
    }
    wake_keeper();
}

/// The phones' notifications are wanted here: the switch is on, and this
/// computer would show them. Otherwise no phone reads them for it.
fn wanted() -> bool {
    state().settings.on && native::shows_any()
}

/// The phone's word, from its app server's control connection (apps/host.rs).
pub(crate) fn phone_said(serial: &str, phone: &str, v: &Value) {
    work(Work::Said { serial: serial.to_string(), phone: phone.to_string(), v: v.clone() });
}

/// A click on a toast, from the system's thread.
fn clicked(c: Click) {
    work(Work::Click(c));
}

fn worker(app: AppHandle, rx: Receiver<Work>) {
    for w in rx {
        if CLOSING.load(Ordering::SeqCst) {
            break;
        }
        match w {
            Work::Said { serial, phone, v } => said(&app, &serial, &phone, &v),
            Work::Click(c) => click(&app, c),
        }
    }
}

// ── the keeper ──

fn bell() -> &'static (Mutex<bool>, Condvar) {
    static BELL: OnceLock<(Mutex<bool>, Condvar)> = OnceLock::new();
    BELL.get_or_init(|| (Mutex::new(false), Condvar::new()))
}

fn wake_keeper() {
    let (rung, bell) = bell();
    *rung.lock().unwrap() = true;
    bell.notify_all();
}

/// A phone's server reached again, on a new control connection (apps/host.rs):
/// the keeper looks now — the phone's notifications are asked for on it — rather than in a few seconds.
pub(crate) fn look_again() {
    wake_keeper();
}

/// Every attached phone listening while the switch is on, and none after it
/// goes off: each phone's app server started for it if none is running — never
/// over a cable that has not settled, since a Samsung phone's adbd restarts
/// about 5 s after the plug goes in and takes the server with it — and asked
/// again on each new control connection, which the phone listens for alone.
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
        let on = wanted();
        // The page's own look at the phones (every 2.5 s) is usually fresh enough.
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
        let mut changed = false;
        {
            let mut st = state();
            for (phone, p) in st.phones.iter_mut() {
                let attached = here.contains_key(phone);
                changed |= p.attached != attached;
                p.attached = attached;
            }
        }
        for (phone, (serial, name)) in &here {
            changed |= keep_one(&app, phone, serial, name, on);
        }
        if changed {
            emit_state(&app);
        }
    }
}

/// One attached phone: listening if the switch is on, not if it is off. True
/// if what the panel shows of it changed.
fn keep_one(app: &AppHandle, phone: &str, serial: &str, name: &str, on: bool) -> bool {
    if !on {
        return keep_off(app, phone, serial, name);
    }
    let running = host::adopt(app, serial, phone);
    let server = match running {
        Some(s) => s,
        None => {
            let settled = crate::adb::attached_for(serial).is_some_and(|t| t >= apps::CABLE_SETTLE);
            if apps::on_cable(serial) && !settled {
                return false;
            }
            let wait = state()
                .phones
                .get(phone)
                .is_some_and(|p| matches!(p.listening, Listening::Refused(_)) && p.at.elapsed() < RETRY_AFTER);
            if wait || CLOSING.load(Ordering::SeqCst) {
                return false;
            }
            match host::ensure_quietly(app, serial, phone) {
                Ok(s) => s,
                Err(e) => {
                    log::warn!("notifications [{serial}] the phone's app server would not start: {e}");
                    let mut st = state();
                    let p = phone_mut(&mut st, phone);
                    p.name = name.to_string();
                    p.now(Listening::Refused(e));
                    return true;
                }
            }
        }
    };
    // Asked on each new control connection — a new server, the same one
    // reached again, or the switch just turned on; again when an ask has gone
    // unanswered, or a refusal has stood a while.
    let here = (server.socket.clone(), server.connection());
    let (ask, changed) = {
        let mut st = state();
        let p = phone_mut(&mut st, phone);
        let renamed = p.name != name;
        p.name = name.to_string();
        let ask = p.asked_on.as_ref() != Some(&here) || p.ask_again();
        let mut changed = renamed;
        if ask {
            p.asked_on = Some(here.clone());
            // A refusal stays on the panel until the phone says otherwise, rather than
            // turning into "connecting" for the moment each try takes.
            let shown = match &p.listening {
                Listening::Refused(why) => Listening::Refused(why.clone()),
                _ => Listening::Asked,
            };
            changed |= p.listening != shown;
            p.now(shown);
        }
        (ask, changed)
    };
    if ask {
        log::info!("notifications [{serial}] asking the phone for its notifications");
        match server.notifications(true) {
            // It went on a connection made since it was looked at: that one is the one asked.
            Ok(on) if on != here.1 => {
                if let Some(p) = state().phones.get_mut(phone) {
                    p.asked_on = Some((here.0, on));
                }
            }
            Ok(_) => {}
            Err(e) => log::warn!("notifications [{serial}] {e}"),
        }
    }
    changed
}

/// One attached phone while the switch is off: told to stop listening if it
/// was, and marked as not listening only once that is said — or once the
/// connection it listened for has gone, which it stopped with — so a phone
/// out of reach for a moment is told when it is back. A server that ran for
/// the notifications alone stops with them.
fn keep_off(app: &AppHandle, phone: &str, serial: &str, name: &str) -> bool {
    let (asked, cleared) = {
        let mut st = state();
        let p = phone_mut(&mut st, phone);
        p.name = name.to_string();
        let cleared = !p.active.is_empty();
        p.active.clear();
        ((p.listening != Listening::Off).then(|| p.asked_on.clone()), cleared)
    };
    if cleared {
        emit_counts(app, phone);
    }
    let Some(asked_on) = asked else { return cleared };
    let told = match (host::adopt(app, serial, phone), asked_on) {
        (Some(s), Some((socket, on))) if s.socket == socket && s.connection() == on => match s.notifications(false) {
            Ok(_) => {
                log::info!("notifications [{serial}] the phone stops listening: they are off here");
                host::retire(phone);
                true
            }
            Err(e) => {
                log::warn!("notifications [{serial}] {e}");
                false
            }
        },
        // The connection it was asked on has gone — the phone stopped
        // listening with it — or it never was asked, or nothing is left to reach.
        _ => true,
    };
    if told {
        let mut st = state();
        let p = phone_mut(&mut st, phone);
        p.now(Listening::Off);
        p.asked_on = None;
    }
    told || cleared
}

// ── what the phone says ──

fn said(app: &AppHandle, serial: &str, phone: &str, v: &Value) {
    match v["t"].as_str() {
        Some("notifs") => listening(app, phone, v),
        Some("notifDone") => done(phone, v),
        // Not wanted here, and the phone still telling — a server reached again
        // that was listening when they went off: none of it is kept, and it stops.
        Some("notif") if !wanted() => {
            if let Some(s) = host::of_phone(phone) {
                let _ = s.notifications(false);
            }
        }
        Some("notif") => match v["kind"].as_str() {
            Some("snapshot") => snapshot(app, serial, phone, v),
            Some("posted") => posted(app, serial, phone, v),
            Some("removed") => removed(app, phone, v["key"].as_str().unwrap_or("")),
            Some("call") => call(app, serial, phone, &v["call"]),
            Some("callEnded") => {
                let key = v["key"].as_str().unwrap_or("");
                end_calls(app, phone, |k| k == key);
            }
            _ => {}
        },
        _ => {}
    }
}

/// Whether the phone listens, now that it was asked.
fn listening(app: &AppHandle, phone: &str, v: &Value) {
    let on = v["on"].as_bool().unwrap_or(false);
    let ok = v["ok"].as_bool().unwrap_or(false);
    let message = v["message"].as_str().unwrap_or("").to_string();
    phone_mut(&mut state(), phone).now(match (on, ok) {
        (false, _) => Listening::Off,
        (true, true) => Listening::Yes,
        (true, false) => Listening::Refused(message.clone()),
    });
    match (on, ok) {
        (true, true) => listens(app, phone),
        (true, false) => log::warn!("notifications [{phone}] the phone will not listen: {message}"),
        _ => {}
    }
    emit_state(app);
}

/// The phone listens: what waited on the first phone to, goes ahead.
fn listens(app: &AppHandle, phone: &str) {
    log::info!("notifications [{phone}] the phone is listening");
    // The first moment there is something to show, a Mac asks whether this app may.
    if native::system().0 == "ask" {
        native::ask(app);
    }
    introduce_once(app);
}

/// The first phone to listen, introduced — once this computer can show it.
fn introduce_once(app: &AppHandle) {
    if native::system().0 != "ready" {
        return;
    }
    let phone = {
        let mut st = state();
        if st.settings.introduced || !st.settings.on {
            return;
        }
        let Some(phone) = st.phones.iter().find(|(_, p)| p.attached && p.listening == Listening::Yes).map(|(id, _)| id.clone())
        else {
            return;
        };
        st.settings.introduced = true;
        phone
    };
    save(app);
    introduction(&phone);
}

/// What the system allows changed (a Mac's answer to its question): the
/// panel says so, and the introduction waits no longer.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn system_changed() {
    if let Some(app) = APP.get() {
        introduce_once(app);
        emit_state(app);
    }
}

/// Everything the phone has now, none of it news: what this computer shows is
/// made to agree — a toast whose notification has gone meanwhile goes too.
/// It is word that the phone listens, too, if its answer to being asked went
/// missing.
fn snapshot(app: &AppHandle, serial: &str, phone: &str, v: &Value) {
    let items: Vec<Item> = v["items"].as_array().map(|a| a.iter().map(Item::from).collect()).unwrap_or_default();
    let calls: Vec<Value> = v["calls"].as_array().cloned().unwrap_or_default();
    let keys: HashSet<String> = items.iter().map(|i| i.key.clone()).collect();
    let confirmed = {
        let mut st = state();
        let p = phone_mut(&mut st, phone);
        p.active = items.iter().map(|i| (i.key.clone(), i.clone())).collect();
        let confirmed = p.listening != Listening::Yes;
        if confirmed {
            p.now(Listening::Yes);
        }
        for item in items.iter().filter(|i| !i.test) {
            note_app(&mut st.settings, item, phone, false);
        }
        confirmed
    };
    if confirmed {
        listens(app, phone);
        emit_state(app);
    }
    {
        let up = up();
        down(app, &up, |_, s| s.phone == phone && s.kind == Kind::Message && !keys.contains(&s.item.key));
    }
    // A call that stopped ringing meanwhile goes; one ringing as the phone
    // started listening rings here as one that just began.
    let ringing: HashSet<String> = calls.iter().filter_map(|c| c["key"].as_str()).map(str::to_string).collect();
    end_calls(app, phone, |k| !ringing.contains(k));
    for c in &calls {
        let up = c["key"].as_str().is_some_and(|k| state().shown.contains_key(&toast::id_for(phone, k)));
        if !up {
            call(app, serial, phone, c);
        }
    }
    save(app);
    emit_counts(app, phone);
    let _ = app.emit("notifications:apps", ());
}

/// A notification posted or changed: a toast if it is news and nothing here
/// says otherwise; an update to the toast already up if it is not.
fn posted(app: &AppHandle, serial: &str, phone: &str, v: &Value) {
    let item = Item::from(v);
    if item.key.is_empty() {
        return;
    }
    let alert = v["alert"].as_bool().unwrap_or(false);
    let id = toast::id_for(phone, &item.key);
    let (settings, up, listed) = {
        let mut st = state();
        phone_mut(&mut st, phone).active.insert(item.key.clone(), item.clone());
        let listed = !item.test && note_app(&mut st.settings, &item, phone, alert);
        (st.settings.clone(), st.shown.contains_key(&id), listed)
    };
    emit_counts(app, phone);
    if listed {
        save(app);
        let _ = app.emit("notifications:apps", ());
    }
    if item.test {
        if alert {
            test_arrived(phone, &item);
            arrived(app, &item);
        }
        return;
    }
    if !alert {
        // Not news: the toast already up, if there is one, says what the notification says now.
        if up && settings.content {
            show_item(app, serial, phone, &item, v, Show::Update);
        }
        return;
    }
    if let Some(why) = withheld(app, phone, &item, &settings) {
        log::debug!("notifications [{phone}] {} not shown: {why}", item.pkg);
        return;
    }
    // Its app's window here is the one in front: no banner over what the
    // person is reading — but kept. An app says nothing of the conversation on
    // its screen (Messages posts none), so what it posts now is another's.
    let how = if apps::front_app().is_some_and(|(p, pkg)| p == phone && pkg == item.pkg) {
        log::debug!("notifications [{phone}] {} quietly: its app's window is the one in front", item.pkg);
        Show::Quietly
    } else {
        Show::Alert
    };
    if show_item(app, serial, phone, &item, v, how) {
        arrived(app, &item);
    }
}

/// One came up: the bell in this app's title bar rings once.
fn arrived(app: &AppHandle, item: &Item) {
    let _ = app.emit("notifications:arrived", json!({"pkg": item.pkg}));
}

/// Why a notification that is news is not shown here, if it is not.
fn withheld(app: &AppHandle, phone: &str, item: &Item, settings: &Settings) -> Option<&'static str> {
    if !settings.on {
        return Some("notifications are off");
    }
    if muted(settings, &item.pkg) {
        return Some("its app is turned off here");
    }
    if !native::allowed(&item.pkg) {
        return Some("its app is turned off in the system's settings");
    }
    if desktop_in_front(app, phone) {
        return Some("the phone's desktop is in front, and shows its own");
    }
    None
}

/// Gone from the phone — read there, dismissed, answered: gone from here too.
fn removed(app: &AppHandle, phone: &str, key: &str) {
    let id = toast::id_for(phone, key);
    {
        let up = up();
        let (shown, item) = {
            let mut st = state();
            let item = st.phones.get_mut(phone).and_then(|p| p.active.remove(key));
            (st.shown.remove(&id), item)
        };
        match (shown, item) {
            (Some(s), _) => take_down(app, &up, &id, &s),
            // Not up as far as this run knows — clicked, so forgotten, while the
            // system kept it — but whatever there is of it is taken down all the same.
            (None, Some(item)) => {
                let from = From::Phone { pkg: item.pkg, label: item.app, icon: None };
                native::hide(&id, &toast::group_for(phone), &from);
                forget_images(app, &id);
            }
            (None, None) => {}
        }
    }
    emit_counts(app, phone);
}

/// A call ringing: up as a call, while the person has calls on and its app
/// is not turned off here — and not while the phone's desktop is in front,
/// whose own banner has it.
fn call(app: &AppHandle, serial: &str, phone: &str, c: &Value) {
    let item = Item::from(c);
    if item.key.is_empty() {
        return;
    }
    // Its app on the panel's list, with a switch of its own — turned off
    // there, a dialer rings here no more — and not only once it posts a missed call.
    if note_app(&mut state().settings, &item, phone, true) {
        save(app);
        let _ = app.emit("notifications:apps", ());
    }
    if !rings(&state().settings, &item.pkg) {
        return;
    }
    if desktop_in_front(app, phone) {
        log::debug!("notifications [{phone}] a call: the desktop's own banner has it");
        return;
    }
    let id = toast::id_for(phone, &item.key);
    let from = from_phone(app, serial, phone, &item);
    let name = phone_name(phone);
    let up = put_up(|settings| {
        // Still wanted as it goes up: calls may have been switched off while it was on its way.
        if !rings(settings, &item.pkg) {
            return None;
        }
        let avatar = if settings.content { save_image(app, &id, "a", &c["avatar"]) } else { None };
        let t = call_toast(&item, settings, id.clone(), &name, toast::group_for(phone), from, avatar.clone());
        let shown = Shown { phone: phone.to_string(), kind: Kind::Call, item: item.clone(), from: t.from.clone(), group: t.group.clone(), avatar };
        Some((t, shown))
    });
    if up {
        arrived(app, &item);
    }
}

/// A call from `pkg` rings here: notifications on, calls on, and its app not turned off here.
fn rings(settings: &Settings, pkg: &str) -> bool {
    settings.on && settings.calls && !muted(settings, pkg)
}

/// An app turned off on the panel, or by "Turn off for …".
fn muted(settings: &Settings, pkg: &str) -> bool {
    settings.apps.get(pkg).is_some_and(|a| a.muted)
}

/// A ringing call as a toast: who is calling — or, with what they say off,
/// only which app — and what answers it ([`call_buttons`]).
fn call_toast(
    item: &Item,
    settings: &Settings,
    id: String,
    phone_name: &str,
    group: String,
    from: From,
    avatar: Option<PathBuf>,
) -> Toast {
    let caller = if settings.content && !item.title.is_empty() { toast::tidy_title(&item.title) } else { item.app.clone() };
    Toast {
        id,
        group,
        from,
        kind: Kind::Call,
        title: caller,
        body: if settings.content && !item.text.is_empty() { toast::tidy_body(&item.text) } else { "Incoming call".into() },
        attribution: phone_name.to_string(),
        when: item.when,
        avatar: avatar.filter(|_| settings.content),
        picture: None,
        reply: None,
        buttons: call_buttons(item),
        silent: !settings.sound,
        popup: true,
    }
}

/// What answers a ringing call. CallStyle's own answer and decline, green and
/// red. A dialer from before CallStyle has only its buttons, in an order of
/// its own — AOSP's puts Decline first — so they are shown as it made them,
/// in its words and its colours, each firing the button it is: which of them
/// connects the call is the dialer's to say, never guessed here.
fn call_buttons(item: &Item) -> Vec<Button> {
    if item.answer || item.decline {
        let mut out = Vec::new();
        if item.answer {
            out.push(Button { label: "Answer".into(), act: Act::Answer.code(), style: Style::Go, menu: false });
        }
        if item.decline {
            out.push(Button { label: "Decline".into(), act: Act::Decline.code(), style: Style::Stop, menu: false });
        }
        return out;
    }
    item.actions
        .iter()
        .filter(|a| !a.reply)
        .take(BUTTONS)
        .map(|a| Button { label: a.label.clone(), act: Act::Button(a.i).code(), style: toast::style_of(&a.tint), menu: false })
        .collect()
}

/// A phone's ringing calls whose key `ended` says, down.
fn end_calls(app: &AppHandle, phone: &str, ended: impl Fn(&str) -> bool) {
    let up = up();
    down(app, &up, |_, s| s.phone == phone && s.kind == Kind::Call && ended(&s.item.key));
}

/// How what a toast asked of the phone went. A reply that did not go must
/// not look as if it had.
fn done(phone: &str, v: &Value) {
    let what = v["what"].as_str().unwrap_or("");
    let key = v["key"].as_str().unwrap_or("");
    if v["ok"].as_bool().unwrap_or(false) {
        log::debug!("notifications [{phone}] {what}: done");
        return;
    }
    let message = v["message"].as_str().unwrap_or("").to_string();
    log::warn!("notifications [{phone}] {what} {key}: {message}");
    let (item, content) = {
        let st = state();
        (st.phones.get(phone).and_then(|p| p.active.get(key).cloned()), st.settings.content)
    };
    match what {
        "notifReply" => {
            let to = item.as_ref().map(|i| i.title.clone()).filter(|t| content && !t.is_empty());
            let app_name = item.as_ref().map(|i| i.app.clone()).unwrap_or_else(|| "the app".into());
            note(
                &format!("{REPLY_FAILED}{}", toast::id_for(phone, key)),
                Some(phone),
                &match to {
                    Some(to) => format!("Your reply to {to} didn't send"),
                    None => "Your reply didn't send".into(),
                },
                &format!("Open {app_name} on your phone to send it."),
                Vec::new(),
            );
        }
        "notifTest" => {
            // The phone could not post one: what one looks like, from here.
            let name = phone_name(phone);
            note(
                "test",
                Some(phone),
                &format!("{name}'s notifications show up here"),
                "Your phone couldn't send a test just now, but its notifications come up like this one.",
                Vec::new(),
            );
        }
        _ => {}
    }
}

// ── the toasts ──

/// How a phone app's notification goes up.
#[derive(Clone, Copy, PartialEq)]
enum Show {
    /// News: it pops up.
    Alert,
    /// News, while its app's window here is the one in front: into the
    /// notification center without popping up.
    Quietly,
    /// Not news: the toast already up says what the notification says now, quietly.
    Update,
}

/// A phone app's notification, up: a new toast, or the one already up for it,
/// changed — made as it goes up, and only if it is wanted then: notifications
/// on, its app not turned off, and for a change, its toast still up. True if it went up.
fn show_item(app: &AppHandle, serial: &str, phone: &str, item: &Item, v: &Value, how: Show) -> bool {
    let id = toast::id_for(phone, &item.key);
    // Whose it is, with its app's icon — fetched from the phone the first time,
    // which can take a while: before what is up is held, never while.
    let from = from_phone(app, serial, phone, item);
    let name = phone_name(phone);
    let popup = how == Show::Alert;
    put_up(|settings| {
        let before = state().shown.get(&id).map(|s| s.avatar.clone());
        if !settings.on || muted(settings, &item.pkg) || (how == Show::Update && before.is_none()) {
            return None;
        }
        let (avatar, picture) = if settings.content {
            (save_image(app, &id, "a", &v["avatar"]).or(before.flatten()), save_image(app, &id, "p", &v["picture"]))
        } else {
            (None, None)
        };
        let t = message_toast(item, settings, id.clone(), &name, toast::group_for(phone), from, avatar.clone(), picture, popup);
        let shown = Shown { phone: phone.to_string(), kind: Kind::Message, item: item.clone(), from: t.from.clone(), group: t.group.clone(), avatar };
        Some((t, shown))
    })
}

/// A phone app's notification as a toast: what it says, its sender's
/// picture, a reply box where the app takes a reply, its first few buttons,
/// and "Turn off for …" last. With what they say off, only which app has
/// something, and "Turn off for …": an app's own buttons can carry what the
/// notification says ("Copy 123456"), and a reply box answers someone it would not name.
#[allow(clippy::too_many_arguments)]
fn message_toast(
    item: &Item,
    settings: &Settings,
    id: String,
    phone_name: &str,
    group: String,
    from: From,
    avatar: Option<PathBuf>,
    picture: Option<PathBuf>,
    popup: bool,
) -> Toast {
    let app_name = if item.app.is_empty() { item.pkg.clone() } else { item.app.clone() };
    let (title, body) = if settings.content {
        (toast::tidy_title(&item.title), toast::tidy_body(&item.text))
    } else {
        let what = if item.category == "msg" { "New message" } else { "New notification" };
        (app_name.clone(), what.to_string())
    };
    let reply = item.actions.iter().find(|a| a.reply).filter(|_| settings.content).map(|a| Reply {
        act: Act::Reply(a.i).code(),
        placeholder: if a.hint.is_empty() { a.label.clone() } else { a.hint.clone() },
        label: a.label.clone(),
    });
    let mut buttons: Vec<Button> = item
        .actions
        .iter()
        .filter(|a| !a.reply && settings.content)
        .take(BUTTONS)
        .map(|a| Button { label: a.label.clone(), act: Act::Button(a.i).code(), style: Style::Plain, menu: false })
        .collect();
    buttons.push(Button { label: format!("Turn off for {app_name}"), act: Act::Mute.code(), style: Style::Plain, menu: true });
    Toast {
        id,
        group,
        from,
        kind: Kind::Message,
        title,
        body,
        attribution: phone_name.to_string(),
        when: item.when,
        avatar: avatar.filter(|_| settings.content),
        picture: picture.filter(|_| settings.content),
        reply,
        buttons,
        silent: !settings.sound,
        popup,
    }
}

/// Up, and remembered as up if the system took it. `make` makes it from what
/// the person has chosen as it goes up — a switch flipped while it was on its
/// way counts — or says it is not wanted now (None); nothing goes up once this
/// app is closing. True if it went up.
fn put_up(make: impl FnOnce(&Settings) -> Option<(Toast, Shown)>) -> bool {
    let _up = up();
    if CLOSING.load(Ordering::SeqCst) {
        return false;
    }
    let settings = state().settings.clone();
    let Some((t, shown)) = make(&settings) else { return false };
    match native::show(&t) {
        Ok(()) => {
            state().shown.insert(t.id.clone(), shown);
            true
        }
        Err(e) => {
            let what = if shown.item.pkg.is_empty() { &t.id } else { &shown.item.pkg };
            log::warn!("notifications: {what} could not be shown: {e}");
            false
        }
    }
}

/// One toast down — off the screen and out of the notification center — and
/// its pictures gone; already out of this run's record.
fn take_down(app: &AppHandle, _: &Up, id: &str, s: &Shown) {
    native::hide(id, &s.group, &s.from);
    forget_images(app, id);
}

/// Every toast up that `which` picks, down: out of this run's record and off
/// the screen together.
fn down(app: &AppHandle, up: &Up, which: impl Fn(&str, &Shown) -> bool) {
    let gone: Vec<(String, Shown)> = {
        let mut st = state();
        let ids: Vec<String> = st.shown.iter().filter(|(id, s)| which(id, s)).map(|(id, _)| id.clone()).collect();
        ids.into_iter().filter_map(|id| st.shown.remove(&id).map(|s| (id, s))).collect()
    };
    for (id, s) in gone {
        take_down(app, up, &id, &s);
    }
}

/// This app's own toast — the introduction, a test, a reply that did not go.
fn note(id: &str, phone: Option<&str>, title: &str, body: &str, buttons: Vec<Button>) {
    let attribution = phone.map(phone_name).unwrap_or_default();
    put_up(|settings| {
        let t = Toast {
            id: id.to_string(),
            group: "this-app".into(),
            from: From::Us,
            kind: Kind::Note,
            title: title.to_string(),
            body: body.to_string(),
            attribution,
            when: now_ms(),
            avatar: None,
            picture: None,
            reply: None,
            buttons,
            silent: !settings.sound,
            popup: true,
        };
        let shown = Shown {
            phone: phone.unwrap_or("").to_string(),
            kind: Kind::Note,
            item: Item::default(),
            from: From::Us,
            group: t.group.clone(),
            avatar: None,
        };
        Some((t, shown))
    });
}

/// The first phone to listen, introduced: what this is, and the way to turn it off.
fn introduction(phone: &str) {
    let name = phone_name(phone);
    note(
        "introduction",
        Some(phone),
        "Your phone's notifications show up here now",
        &format!("When {name} gets one, it comes up on {THIS_COMPUTER} too. Reply, open the app or answer a call from here."),
        vec![
            Button { label: "Settings".into(), act: Act::Settings.code(), style: Style::Plain, menu: false },
            Button { label: "Turn off".into(), act: Act::TurnOff.code(), style: Style::Plain, menu: false },
        ],
    );
}

/// The phone's test notification, here: this app's own toast, saying it worked.
fn test_arrived(phone: &str, item: &Item) {
    let name = phone_name(phone);
    let id = toast::id_for(phone, &item.key);
    let off = if cfg!(target_os = "macos") { "or turn its app off under Options" } else { "or right-click it to turn its app off" };
    note(
        &id,
        None,
        &format!("Notifications from {name} show up here"),
        &format!("This one came from your phone just now. Click one to open it, reply in its box, {off}."),
        Vec::new(),
    );
    if let Some(s) = state().shown.get_mut(&id) {
        s.phone = phone.to_string();
        s.item = item.clone();
    }
}

/// Whose a phone app's toast is: the app, with its icon — on Windows, read
/// from a file. One the Apps page has not listed is fetched once from the phone.
fn from_phone(app: &AppHandle, serial: &str, phone: &str, item: &Item) -> From {
    let label = if item.app.is_empty() { item.pkg.clone() } else { item.app.clone() };
    let icon = icons::newest_file(app, phone, &item.pkg).or_else(|| {
        let server = host::of_phone(phone).or_else(|| host::running(serial))?;
        let mut png = None;
        let _ = server.icons(std::slice::from_ref(&item.pkg), icons::PX, |_, p| png = Some(p));
        icons::store(app, phone, &item.pkg, 0, &png?);
        icons::newest_file(app, phone, &item.pkg)
    });
    From::Phone { pkg: item.pkg.clone(), label, icon }
}

// ── a click ──

fn click(app: &AppHandle, c: Click) {
    // The system takes a toast away with a click on it: forgotten here too —
    // except a call's, kept until the phone says the call ended, which takes
    // down whatever is left of it then.
    let shown = {
        let _up = up();
        let shown = {
            let mut st = state();
            match st.shown.get(&c.id) {
                Some(s) if s.kind == Kind::Call => Some(s.clone()),
                _ => st.shown.remove(&c.id),
            }
        };
        forget_images(app, &c.id);
        shown
    };
    match c.act {
        Act::TurnOff => {
            set_on(app, false);
            return;
        }
        Act::Settings => {
            show_panel(app);
            return;
        }
        Act::Unmute => {
            if let Some(pkg) = c.id.strip_prefix("muted-") {
                set_muted(app, pkg, false);
            }
            return;
        }
        _ => {}
    }
    let Some(s) = shown else {
        log::debug!("notifications: a click on a toast this run did not put up ({})", c.id);
        return;
    };
    if s.kind == Kind::Note {
        // This app's own: its body opens this app's window.
        if c.act == Act::Open {
            show_panel(app);
        }
        return;
    }
    let server = host::of_phone(&s.phone);
    match c.act {
        Act::Open => {
            let (app, s) = (app.clone(), s.clone());
            // Opening a window takes a while: not on the thread every other toast waits on.
            std::thread::spawn(move || open(&app, &s, server));
        }
        Act::Mute => {
            let pkg = &s.item.pkg;
            set_muted(app, pkg, true);
            let label = state().settings.apps.get(pkg).map(|a| a.label.clone()).unwrap_or_else(|| pkg.clone());
            note(
                &format!("muted-{pkg}"),
                Some(&s.phone),
                &format!("{label} won't show up on {THIS_COMPUTER}"),
                "Its notifications stay on your phone. Turn it back on from the bell in Open Android DeX.",
                vec![Button { label: "Undo".into(), act: Act::Unmute.code(), style: Style::Plain, menu: false }],
            );
        }
        Act::Dismissed => {
            if s.kind == Kind::Message && state().settings.clear_on_phone {
                if let Some(server) = server {
                    let _ = server.notif_dismiss(&s.item.key);
                }
            }
        }
        _ => {
            let Some(server) = server else {
                unreachable(&s.phone);
                return;
            };
            let key = &s.item.key;
            let label = |i: i64| s.item.actions.iter().find(|a| a.i == i).map(|a| a.label.clone()).unwrap_or_default();
            let sent = match c.act {
                // A dialer's own button, on its ringing call: on the phone, where a call is taken.
                Act::Button(i) if s.kind == Kind::Call => server.notif_call_button(key, i, &label(i)),
                Act::Button(i) => {
                    let window = apps::window_key(&s.phone, &s.item.pkg);
                    let display = if window.is_none() { desktop_of(app, &s.phone).map(|(_, d)| d) } else { None };
                    server.notif_action(key, i, &label(i), window.as_deref(), display)
                }
                Act::Reply(i) => {
                    let text = c.text.unwrap_or_default();
                    if text.trim().is_empty() {
                        return;
                    }
                    server.notif_reply(key, i, &label(i), text.trim())
                }
                Act::Answer => server.notif_call(key, true),
                Act::Decline => server.notif_call(key, false),
                _ => Ok(()),
            };
            if let Err(e) = sent {
                log::warn!("notifications [{}] {e}", s.phone);
            }
        }
    }
}

/// Open what a toast is about, where the person is working: on the phone's
/// desktop if it is running (and that in front); else in a window of its own
/// here, at the conversation; else on the phone, as a tap there would.
fn open(app: &AppHandle, s: &Shown, server: Option<Arc<host::Server>>) {
    let Some(server) = server else {
        unreachable(&s.phone);
        return;
    };
    let key = &s.item.key;
    if let Some((session, display)) = desktop_of(app, &s.phone) {
        let _ = server.notif_open(key, None, Some(display), true);
        let _ = crate::scrcpy::focus_session(app.clone(), session);
        return;
    }
    #[cfg(any(windows, target_os = "macos"))]
    {
        let pkg = &s.item.pkg;
        let devices = crate::adb::recent_devices(app, Duration::from_secs(3));
        if let Some(serial) = apps::best_serial(&devices, &s.phone) {
            match apps::open_at_notification(app, &serial, pkg, key) {
                Ok(Some(window)) => {
                    let _ = server.notif_open(key, Some(&window), None, false);
                    return;
                }
                // A new window, which opens at it.
                Ok(None) => return,
                Err(e) => log::warn!("notifications [{}] {pkg} could not open here ({e}): on the phone instead", s.phone),
            }
        }
    }
    let _ = server.notif_open(key, None, None, false);
}

/// A click that needs the phone, while it is not attached.
fn unreachable(phone: &str) {
    let name = phone_name(phone);
    note(
        &format!("away-{}", toast::group_for(phone)),
        Some(phone),
        &format!("{name} isn't connected"),
        "Plug it back in, or keep it on the same Wi\u{2011}Fi, and try again.",
        Vec::new(),
    );
}

/// The phone's desktop, if one is running for it: its session and its display.
fn desktop_of(app: &AppHandle, phone: &str) -> Option<(String, i32)> {
    let devices = crate::adb::recent_devices(app, Duration::from_secs(5));
    devices.iter().filter(|d| apps::phone_id(d) == phone).find_map(|d| {
        let session = format!("{}|desktop", d.serial);
        crate::scrcpy::session_display_id(app, &session).map(|display| (session, display))
    })
}

fn desktop_in_front(app: &AppHandle, phone: &str) -> bool {
    desktop_of(app, phone).is_some_and(|(session, _)| crate::hotkeys::desktop_in_front(app, &session))
}

/// This app's window, its notification settings open.
fn show_panel(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
    let _ = app.emit("notifications:panel", ());
}

// ── what the person chose, changed ──

fn set_on(app: &AppHandle, on: bool) {
    {
        let up = up();
        state().settings.on = on;
        if !on {
            // Off: nothing here can act on what is up any more.
            down(app, &up, |_, s| s.kind != Kind::Note);
        }
    }
    if on {
        native::ask(app);
    }
    save(app);
    wake_keeper();
    emit_state(app);
}

/// What they say, switched off: every toast up says only which app has
/// something now — redrawn where it is, not shown again, except a call still
/// ringing — and the pictures it had go; a note naming whom a reply was for
/// goes. At once, where the switch was flipped, not behind what the phone
/// has said meanwhile: with what is up held, nothing comes or goes through it.
fn redraw(app: &AppHandle, up: &Up) {
    let (settings, shown) = {
        let st = state();
        (st.settings.clone(), st.shown.iter().map(|(id, s)| (id.clone(), s.clone())).collect::<Vec<_>>())
    };
    for (id, s) in shown {
        let t = match s.kind {
            Kind::Message => {
                message_toast(&s.item, &settings, id.clone(), &phone_name(&s.phone), s.group.clone(), s.from.clone(), None, None, false)
            }
            Kind::Call => call_toast(&s.item, &settings, id.clone(), &phone_name(&s.phone), s.group.clone(), s.from.clone(), None),
            Kind::Note => {
                if id.starts_with(REPLY_FAILED) && state().shown.remove(&id).is_some() {
                    take_down(app, up, &id, &s);
                }
                continue;
            }
        };
        forget_images(app, &id);
        match native::show(&t) {
            Ok(()) => {
                if let Some(s) = state().shown.get_mut(&id) {
                    s.avatar = None;
                }
            }
            Err(e) => log::warn!("notifications: {} could not be redrawn: {e}", s.item.pkg),
        }
    }
}

fn set_muted(app: &AppHandle, pkg: &str, muted: bool) {
    {
        let up = up();
        state().settings.apps.entry(pkg.to_string()).or_default().muted = muted;
        if muted {
            down(app, &up, |_, s| s.kind != Kind::Note && s.item.pkg == pkg);
        }
    }
    save(app);
    let _ = app.emit("notifications:apps", ());
}

/// An app heard from: its name, the phone, and — when it had news — when and
/// how often. True if the list of apps changed.
fn note_app(settings: &mut Settings, item: &Item, phone: &str, news: bool) -> bool {
    if item.pkg.is_empty() {
        return false;
    }
    let new = !settings.apps.contains_key(&item.pkg);
    let a = settings.apps.entry(item.pkg.clone()).or_default();
    let renamed = !item.app.is_empty() && a.label != item.app;
    if renamed {
        a.label = item.app.clone();
    }
    a.phone = phone.to_string();
    if news {
        a.last = now_ms();
        a.count += 1;
    }
    if settings.apps.len() > APPS_KEPT {
        let oldest = settings.apps.iter().filter(|(p, _)| *p != &item.pkg).min_by_key(|(_, a)| (a.muted, a.last)).map(|(p, _)| p.clone());
        if let Some(p) = oldest {
            settings.apps.remove(&p);
        }
    }
    new || renamed || news
}

fn settings_path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join("notifications.json"))
}

fn load(app: &AppHandle) -> Settings {
    settings_path(app)
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
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

// ── pictures ──

fn images_dir(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_cache_dir().ok().map(|d| d.join("notifications"))
}

/// A picture the phone sent (base64), as a file a toast can show — named by
/// what is in it, since Windows keeps a picture by its path and would show a
/// new one under an old name as the old.
fn save_image(app: &AppHandle, id: &str, kind: &str, b64: &Value) -> Option<PathBuf> {
    let bytes = decode_base64(b64.as_str()?)?;
    let ext = if bytes.starts_with(b"\x89PNG") { "png" } else { "jpg" };
    let dir = images_dir(app)?;
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join(format!("{id}-{kind}-{:08x}.{ext}", crc(&bytes)));
    if !path.exists() {
        std::fs::write(&path, &bytes).ok()?;
    }
    Some(path)
}

fn forget_images(app: &AppHandle, id: &str) {
    let Some(dir) = images_dir(app) else { return };
    let Ok(entries) = std::fs::read_dir(&dir) else { return };
    let prefix = format!("{id}-");
    for e in entries.flatten() {
        if e.file_name().to_string_lossy().starts_with(&prefix) {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

/// A cheap checksum, to name a picture by what is in it.
fn crc(bytes: &[u8]) -> u32 {
    bytes.iter().fold(0x811c_9dc5u32, |h, b| (h ^ *b as u32).wrapping_mul(0x0100_0193))
}

/// Standard base64, as the phone's `Base64.NO_WRAP` writes it.
fn decode_base64(s: &str) -> Option<Vec<u8>> {
    fn val(c: u8) -> Option<u32> {
        Some(match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return None,
        } as u32)
    }
    let clean: Vec<u8> = s.bytes().filter(|c| !c.is_ascii_whitespace() && *c != b'=').collect();
    if clean.is_empty() {
        return None;
    }
    let mut out = Vec::with_capacity(clean.len() * 3 / 4);
    for chunk in clean.chunks(4) {
        let mut n = 0u32;
        for (i, c) in chunk.iter().enumerate() {
            n |= val(*c)? << (18 - 6 * i);
        }
        let bytes = [(n >> 16) as u8, (n >> 8) as u8, n as u8];
        out.extend_from_slice(&bytes[..chunk.len().saturating_sub(1)]);
    }
    Some(out)
}

// ── the page ──

/// What the bell in the title bar shows (src/features/notifications).
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct View {
    /// This computer can show them at all: Windows and macOS.
    pub supported: bool,
    pub on: bool,
    pub content: bool,
    pub sound: bool,
    pub calls: bool,
    pub clear_on_phone: bool,
    /// What this computer's own notifications allow: "ready", "ask" (a Mac
    /// that has not asked yet), "off" (turned off for this app in the
    /// system's settings), "unavailable" — and why, for the last two.
    pub system: &'static str,
    pub system_message: String,
    pub phones: Vec<PhoneView>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneView {
    pub phone: String,
    pub name: String,
    /// "starting", "on" or "refused".
    pub state: &'static str,
    pub message: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppView {
    pub pkg: String,
    pub label: String,
    pub icon: Option<String>,
    pub muted: bool,
    /// Turned off for it in the system's own settings (Windows: per app).
    pub blocked: bool,
    pub last: i64,
    pub count: u64,
    /// How many of its notifications are on the attached phones now.
    pub waiting: u32,
}

fn view() -> View {
    let (system, system_message) = native::system();
    let st = state();
    let mut phones: Vec<PhoneView> = st
        .phones
        .iter()
        .filter(|(_, p)| p.attached && p.listening != Listening::Off)
        .map(|(phone, p)| {
            let (state, message) = match &p.listening {
                Listening::Yes => ("on", String::new()),
                Listening::Refused(m) => ("refused", m.clone()),
                _ => ("starting", String::new()),
            };
            PhoneView { phone: phone.clone(), name: p.name.clone(), state, message }
        })
        .collect();
    phones.sort_by(|a, b| a.name.cmp(&b.name));
    View {
        supported: native::SUPPORTED,
        on: st.settings.on,
        content: st.settings.content,
        sound: st.settings.sound,
        calls: st.settings.calls,
        clear_on_phone: st.settings.clear_on_phone,
        system,
        system_message,
        phones,
    }
}

fn emit_state(app: &AppHandle) {
    let _ = app.emit("notifications:state", view());
}

/// How many notifications each app has on `phone` now: the Apps page's dots.
fn counts(phone: &str) -> HashMap<String, u32> {
    let mut out = HashMap::new();
    if let Some(p) = state().phones.get(phone) {
        for item in p.active.values().filter(|i| !i.test) {
            *out.entry(item.pkg.clone()).or_insert(0) += 1;
        }
    }
    out
}

fn emit_counts(app: &AppHandle, phone: &str) {
    let _ = app.emit("notifications:counts", json!({"phone": phone, "counts": counts(phone)}));
}

/// The bell's state: the switches, what the system allows, the phones.
#[tauri::command]
pub fn notifications_state() -> View {
    view()
}

/// Every app that has notified here, or has notifications on an attached
/// phone: most recent first, each with its icon and how many are waiting.
#[tauri::command(async)]
pub fn notifications_apps(app: AppHandle) -> Vec<AppView> {
    let (apps, waiting) = {
        let st = state();
        let mut waiting: HashMap<String, u32> = HashMap::new();
        for p in st.phones.values().filter(|p| p.attached) {
            for item in p.active.values().filter(|i| !i.test) {
                *waiting.entry(item.pkg.clone()).or_insert(0) += 1;
            }
        }
        (st.settings.apps.clone(), waiting)
    };
    let mut missing: HashMap<String, Vec<String>> = HashMap::new();
    let mut out: Vec<AppView> = apps
        .into_iter()
        .map(|(pkg, a)| {
            let icon = icons::newest(&app, &a.phone, &pkg).map(|(_, png)| icons::data_url(&png));
            if icon.is_none() && !a.phone.is_empty() {
                missing.entry(a.phone.clone()).or_default().push(pkg.clone());
            }
            AppView {
                icon,
                blocked: !native::allowed(&pkg),
                label: if a.label.is_empty() { pkg.clone() } else { a.label },
                waiting: waiting.get(&pkg).copied().unwrap_or(0),
                pkg,
                muted: a.muted,
                last: a.last,
                count: a.count,
            }
        })
        .collect();
    out.sort_by(|a, b| b.last.cmp(&a.last).then_with(|| a.label.to_lowercase().cmp(&b.label.to_lowercase())));
    fetch_icons(&app, missing);
    out
}

/// The icons the list has none of yet — an app seen only in a phone's shade,
/// never on the Apps page — from its phone, once a run each; then the list again.
fn fetch_icons(app: &AppHandle, missing: HashMap<String, Vec<String>>) {
    static ASKED: Mutex<Option<HashSet<String>>> = Mutex::new(None);
    let want: Vec<(String, Vec<String>)> = {
        let mut asked = ASKED.lock().unwrap();
        let asked = asked.get_or_insert_with(HashSet::new);
        missing
            .into_iter()
            .map(|(phone, pkgs)| {
                let new: Vec<String> = pkgs.into_iter().filter(|p| asked.insert(format!("{phone}\n{p}"))).collect();
                (phone, new)
            })
            .filter(|(_, pkgs)| !pkgs.is_empty())
            .collect()
    };
    if want.is_empty() {
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || {
        let mut got = false;
        for (phone, pkgs) in want {
            let Some(server) = host::of_phone(&phone) else { continue };
            let _ = server.icons(&pkgs, icons::PX, |pkg, png| {
                icons::store(&app, &phone, &pkg, 0, &png);
                got = true;
            });
        }
        if got {
            let _ = app.emit("notifications:apps", ());
        }
    });
}

/// A switch flipped on the panel; the others as they were.
#[tauri::command]
pub fn notifications_set(
    app: AppHandle,
    on: Option<bool>,
    content: Option<bool>,
    sound: Option<bool>,
    calls: Option<bool>,
    clear_on_phone: Option<bool>,
) -> View {
    if let Some(on) = on {
        if on != state().settings.on {
            log::info!("notifications: turned {} here", if on { "on" } else { "off" });
            set_on(&app, on);
        }
    }
    {
        let up = up();
        let (hidden, no_calls) = {
            let mut st = state();
            let hidden = content == Some(false) && st.settings.content;
            let no_calls = calls == Some(false) && st.settings.calls;
            if let Some(v) = content {
                st.settings.content = v;
            }
            if let Some(v) = sound {
                st.settings.sound = v;
            }
            if let Some(v) = calls {
                st.settings.calls = v;
            }
            if let Some(v) = clear_on_phone {
                st.settings.clear_on_phone = v;
            }
            (hidden, no_calls)
        };
        if no_calls {
            log::info!("notifications: calls no longer ring here");
            down(&app, &up, |_, s| s.kind == Kind::Call);
        }
        if hidden {
            log::info!("notifications: what they say is no longer shown here");
            redraw(&app, &up);
        }
    }
    save(&app);
    emit_state(&app);
    view()
}

/// One app's own switch.
#[tauri::command]
pub fn notifications_mute(app: AppHandle, pkg: String, muted: bool) {
    log::info!("notifications: {pkg} turned {} here", if muted { "off" } else { "on" });
    set_muted(&app, &pkg, muted);
}

/// A notification from each listening phone, so the person sees one arrive.
/// Which phones were asked, for the panel to say.
#[tauri::command(async)]
pub fn notifications_test() -> Result<Vec<String>, String> {
    if !state().settings.on {
        return Err("Turn notifications on first.".into());
    }
    let phones: Vec<(String, String)> = state()
        .phones
        .iter()
        .filter(|(_, p)| p.attached && p.listening == Listening::Yes)
        .map(|(id, p)| (id.clone(), p.name.clone()))
        .collect();
    if phones.is_empty() {
        return Err("No phone is connected yet.".into());
    }
    let mut asked = Vec::new();
    for (phone, name) in phones {
        if let Some(server) = host::of_phone(&phone) {
            if server.notif_test("Open Android DeX", &format!("Checking that this phone's notifications reach {THIS_COMPUTER}.")).is_ok() {
                asked.push(name);
            }
        }
    }
    if asked.is_empty() {
        return Err("Your phone didn't answer. Try again in a moment.".into());
    }
    Ok(asked)
}

/// What stands in the way, out of the way: the system's question asked (a
/// Mac that has not asked yet), else its notification settings opened.
#[tauri::command]
pub fn notifications_fix(app: AppHandle) {
    match native::system().0 {
        "ask" => native::ask(&app),
        _ => {
            if let Some(url) = native::settings_url() {
                use tauri_plugin_opener::OpenerExt;
                if let Err(e) = app.opener().open_url(url, None::<&str>) {
                    log::warn!("notifications: could not open the system's settings: {e}");
                }
            }
        }
    }
}

/// How many notifications each app has on `phone` now.
#[tauri::command]
pub fn notifications_counts(phone: String) -> HashMap<String, u32> {
    counts(&phone)
}

fn phone_name(phone: &str) -> String {
    state().phones.get(phone).map(|p| p.name.clone()).filter(|n| !n.is_empty()).unwrap_or_else(|| "Your phone".into())
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn whatsapp() -> Item {
        Item {
            key: "0|com.whatsapp|1|null|10234".into(),
            pkg: "com.whatsapp".into(),
            app: "WhatsApp".into(),
            title: "Mom".into(),
            text: "Are you coming?".into(),
            category: "msg".into(),
            when: 1,
            test: false,
            actions: vec![
                Action { i: 0, label: "Mark as read".into(), ..Action::default() },
                Action { i: 1, label: "Reply".into(), reply: true, hint: "Message".into(), ..Action::default() },
            ],
            ..Item::default()
        }
    }

    /// A call ringing on a dialer from before CallStyle, as AOSP's posts it: Decline first, coloured.
    fn older_dialers_call() -> Item {
        Item {
            key: "0|com.android.dialer|1|null|10010".into(),
            pkg: "com.android.dialer".into(),
            app: "Phone".into(),
            title: "Mom".into(),
            text: "Incoming call".into(),
            category: "call".into(),
            actions: vec![
                Action { i: 0, label: "Decline".into(), tint: "#a52714".into(), ..Action::default() },
                Action { i: 1, label: "Answer".into(), tint: "#097138".into(), ..Action::default() },
            ],
            ..Item::default()
        }
    }

    fn toast_for(item: &Item, settings: &Settings) -> Toast {
        let id = toast::id_for("A13", &item.key);
        message_toast(item, settings, id, "Galaxy A13", toast::group_for("A13"), From::Us, Some("a.png".into()), None, true)
    }

    #[test]
    fn a_message_comes_up_with_its_reply_box_its_buttons_and_a_way_to_turn_it_off() {
        let t = toast_for(&whatsapp(), &Settings::default());
        assert_eq!((t.title.as_str(), t.body.as_str(), t.attribution.as_str()), ("Mom", "Are you coming?", "Galaxy A13"));
        let reply = t.reply.expect("a reply box");
        assert_eq!((reply.act.as_str(), reply.placeholder.as_str()), ("r1", "Message"));
        assert_eq!(t.buttons[0].act, "b0");
        let off = t.buttons.last().unwrap();
        assert!(off.menu && off.act == "mute" && off.label == "Turn off for WhatsApp");
        assert!(t.avatar.is_some() && !t.silent);
    }

    #[test]
    fn with_what_they_say_off_a_toast_says_only_which_app() {
        let settings = Settings { content: false, sound: false, ..Settings::default() };
        let mut item = whatsapp();
        item.actions.push(Action { i: 2, label: "Copy \"123456\"".into(), ..Action::default() });
        let t = toast_for(&item, &settings);
        assert_eq!((t.title.as_str(), t.body.as_str()), ("WhatsApp", "New message"));
        assert!(t.reply.is_none() && t.avatar.is_none() && t.silent);
        // None of the app's own buttons, which can say what the message does: only the way to turn it off.
        let labels: Vec<&str> = t.buttons.iter().map(|b| b.label.as_str()).collect();
        assert_eq!(labels, ["Turn off for WhatsApp"]);
        let call = call_toast(&item, &settings, "c".into(), "Galaxy A13", "g".into(), From::Us, Some("a.png".into()));
        assert_eq!((call.title.as_str(), call.body.as_str()), ("WhatsApp", "Incoming call"));
        assert!(call.avatar.is_none());
    }

    #[test]
    fn a_call_by_callstyle_has_its_own_answer_and_decline() {
        let mut item = older_dialers_call();
        item.actions.clear();
        (item.answer, item.decline) = (true, true);
        let t = call_toast(&item, &Settings::default(), "c".into(), "Pixel", "g".into(), From::Us, None);
        let buttons: Vec<(&str, &str, Style)> = t.buttons.iter().map(|b| (b.label.as_str(), b.act.as_str(), b.style)).collect();
        assert_eq!(buttons, [("Answer", "answer", Style::Go), ("Decline", "decline", Style::Stop)]);
        // Only what the call has: a decline with no answer is never made into one.
        item.answer = false;
        let only: Vec<String> = call_buttons(&item).into_iter().map(|b| b.act).collect();
        assert_eq!(only, ["decline"]);
    }

    #[test]
    fn an_older_dialers_call_has_its_own_buttons_as_it_made_them() {
        // AOSP's dialer before CallStyle puts Decline first: each button fires
        // itself, in its own words and colour — none is taken for "answer" by its place.
        let t = call_toast(&older_dialers_call(), &Settings::default(), "c".into(), "Redmi 9A", "g".into(), From::Us, None);
        let buttons: Vec<(&str, &str, Style)> = t.buttons.iter().map(|b| (b.label.as_str(), b.act.as_str(), b.style)).collect();
        assert_eq!(buttons, [("Decline", "b0", Style::Stop), ("Answer", "b1", Style::Go)]);
        // With what they say off, a call is still answered from here: its buttons are the call's.
        let quiet = call_toast(&older_dialers_call(), &Settings { content: false, ..Settings::default() }, "c".into(), "R", "g".into(), From::Us, None);
        assert_eq!((quiet.title.as_str(), quiet.buttons.len()), ("Phone", 2));
        // A dialer that coloured nothing has plain buttons, still its own.
        let mut plain = older_dialers_call();
        plain.actions.iter_mut().for_each(|a| a.tint.clear());
        assert!(call_buttons(&plain).iter().all(|b| b.style == Style::Plain));
    }

    #[test]
    fn a_call_is_read_with_what_answers_it() {
        let v = json!({"key": "k", "pkg": "p", "answer": true, "decline": true});
        let item = Item::from(&v);
        assert!(item.answer && item.decline && item.actions.is_empty());
        let v = json!({"key": "k", "pkg": "p", "actions": [{"i": 0, "label": "Decline", "tint": "#a52714"}]});
        let item = Item::from(&v);
        assert!(!item.answer && !item.decline);
        assert_eq!(item.actions[0].tint, "#a52714");
    }

    #[test]
    fn a_phone_is_asked_again_only_once_its_answer_is_overdue() {
        let ago = |s: u64| Instant::now().checked_sub(Duration::from_secs(s)).unwrap();
        let phone = |listening, at| Phone {
            name: String::new(),
            asked_on: Some(("s".into(), 0)),
            listening,
            at,
            active: HashMap::new(),
            attached: true,
        };
        assert!(phone(Listening::Off, ago(0)).ask_again());
        assert!(!phone(Listening::Yes, ago(60)).ask_again());
        assert!(!phone(Listening::Asked, ago(3)).ask_again(), "its answer may still be on its way");
        assert!(phone(Listening::Asked, ago(11)).ask_again(), "the ask, or its answer, went missing");
        assert!(!phone(Listening::Refused("no".into()), ago(3)).ask_again(), "not on every look");
        assert!(phone(Listening::Refused("no".into()), ago(31)).ask_again());
    }

    #[test]
    fn the_phones_description_is_read_whole() {
        let v = json!({
            "t": "notif", "kind": "posted", "key": "k", "pkg": "p", "app": "App", "title": "T", "text": "x",
            "when": 5, "category": "msg", "test": true,
            "actions": [{"i": 2, "label": "Reply", "reply": true, "hint": "Say"}, {"i": 3, "label": ""}, {"label": "no index"}]
        });
        let item = Item::from(&v);
        assert_eq!((item.key.as_str(), item.when, item.test), ("k", 5, true));
        assert_eq!(item.actions, vec![Action { i: 2, label: "Reply".into(), reply: true, hint: "Say".into(), tint: String::new() }]);
    }

    #[test]
    fn apps_are_remembered_with_when_they_had_news() {
        let mut s = Settings::default();
        assert!(note_app(&mut s, &whatsapp(), "A13", false), "a new app is a change to the list");
        assert_eq!((s.apps["com.whatsapp"].count, s.apps["com.whatsapp"].last), (0, 0));
        assert!(!note_app(&mut s, &whatsapp(), "A13", false), "seen again, nothing changed");
        assert!(note_app(&mut s, &whatsapp(), "A13", true));
        assert_eq!(s.apps["com.whatsapp"].count, 1);
        assert!(s.apps["com.whatsapp"].last > 0);
        // A setting kept from before this version reads as the defaults for what it lacks.
        let old: Settings = serde_json::from_str(r#"{"on": false}"#).unwrap();
        assert!(!old.on && old.content && old.calls && !old.clear_on_phone);
    }

    #[test]
    fn base64_from_the_phone_decodes() {
        assert_eq!(decode_base64("TWFu").unwrap(), b"Man");
        assert_eq!(decode_base64("TWE=").unwrap(), b"Ma");
        assert_eq!(decode_base64("TQ==").unwrap(), b"M");
        assert_eq!(decode_base64("//79/A==").unwrap(), vec![0xFF, 0xFE, 0xFD, 0xFC]);
        assert!(decode_base64("").is_none());
        assert!(decode_base64("@@@@").is_none());
    }
}
