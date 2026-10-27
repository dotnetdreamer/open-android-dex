//! The phone's half of the app windows, kept running: its process, and the
//! one connection that is the phone's as a whole (doc/apps-on-pc.md).
//!
//! One server per phone, started once — the window daemon's dex under
//! `app_process` as the shell, `AppServer` — and told to stop when this app
//! is done with it, every app on its displays going back to the phone. The
//! adb shell that started it closing is not that: the cable may have been
//! pulled with Wi-Fi still there, and the server waits for this computer to
//! come back another way — its control connection made again over Wi-Fi
//! ([`ensure`] adopts it), its windows taken back by their keys (link.rs). It
//! is started as soon as the Apps page is looked at, so the JVM it is (over a
//! second to start on a Galaxy A13) is up before anyone clicks; opening an app
//! after that costs a display, a launch and an encoder.
//!
//! The control connection carries the list of apps, their icons, the
//! clipboard, where the phone's sound plays, the phone's clock, and its
//! notifications while this computer shows them (notifications/mod.rs) —
//! for which a server is started on its own, without the warm-up a window
//! wants ([`ensure_quietly`]), and stopped again when they go off unless the
//! Apps page has used it meanwhile ([`retire`]). The phone listens for the
//! connection that asked, and stops when it goes: a connection made again is
//! a new one ([`Server::connection`]), asked again. Requests on it are one at a time.

use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::process::{Child, ChildStdin, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use super::sound::{self, Output, PhoneSound};
use super::{adbsock, wire};
use crate::adb;

/// Where the dex runs from on the phone: a name of its own, never the window
/// daemon's `wmd.dex`, which a running daemon has open; and replaced by a
/// rename, so a server still running keeps the copy it started with.
const PHONE_DEX: &str = "/data/local/tmp/oadx-apps.dex";
/// What the server gets to come up: its JVM, cold, on a slow phone.
const READY_TIMEOUT: Duration = Duration::from_secs(12);
/// A request on the control connection that gets no answer in this long has none coming.
const ANSWER_TIMEOUT: Duration = Duration::from_secs(20);
/// What opening a Linux app waits to hear: the launcher answers within its main thread's 5 s.
const LINUX_RUN_TIMEOUT: Duration = Duration::from_secs(8);
/// Pinning the phone's sound is one call to its audio service.
const ROUTE_TIMEOUT: Duration = Duration::from_secs(5);

/// What the phone said about itself.
#[derive(Debug, Clone, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Hello {
    pub sdk: u32,
    pub release: String,
    pub model: String,
    pub manufacturer: String,
    /// Whether a window's display can hold the keyboard before it is clicked.
    pub trusted: bool,
    /// Each window keeps its own focus (Android 14): two windows typed into in turn both keep theirs.
    pub own_focus: bool,
}

enum Answer {
    Apps(Vec<Value>),
    Linux(Value),
    LinuxRan(Value),
    Icon(String, Vec<u8>),
    IconsDone,
    Routed(Value),
}

pub struct Server {
    pub socket: String,
    pub hello: Hello,
    /// The phone it runs on, by its `ro.serialno`: whichever way it is reached.
    pub phone: String,
    /// Every adb serial this computer has reached it by: what [`running`] finds it by.
    ways: Mutex<HashSet<String>>,
    child: Mutex<Option<Child>>,
    /// Its adb shell's stdin: "stop" on it is this computer done with the server.
    stdin: Mutex<Option<ChildStdin>>,
    control: Mutex<TcpStream>,
    answers: Mutex<Receiver<Answer>>,
    /// What a control connection made again hands the answers it reads to.
    answer_tx: Mutex<Sender<Answer>>,
    /// One request at a time on the control connection.
    asking: Mutex<()>,
    /// Its control connection is up.
    alive: Arc<AtomicBool>,
    /// Which control connection is the server's: the reader of one replaced
    /// ends quietly. Changed only with `control` held, so what is sent there
    /// and which connection took it agree.
    control_gen: Arc<AtomicU64>,
    /// The phone's text last handed over either way, so neither side gets its own back.
    pub clip_last: Arc<Mutex<String>>,
    /// What the phone last said of its sound outputs: it says so as they come and go.
    sound: Arc<Mutex<Option<PhoneSound>>>,
    /// The Apps page or a window has used it, not only the phone's
    /// notifications: what the phone copies comes to this computer only then,
    /// as it did before notifications started a server of their own.
    for_apps: Arc<AtomicBool>,
}

impl Server {
    pub fn alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    /// Used by the Apps page or a window, not only by the phone's notifications.
    pub fn for_apps(&self) -> bool {
        self.for_apps.load(Ordering::SeqCst)
    }

    /// Which of its control connections is up now: the first is 0, and each
    /// made again (the cable pulled, Wi-Fi still there) is one more.
    pub fn connection(&self) -> u64 {
        self.control_gen.load(Ordering::SeqCst)
    }

    /// The server is reached by `serial` too: a window's connection goes that way.
    pub fn reached_by(&self, serial: &str) {
        self.ways.lock().unwrap().insert(serial.to_string());
    }

    fn reaches(&self, serial: &str) -> bool {
        self.ways.lock().unwrap().contains(serial)
    }

    /// The control connection made again, over `serial`: the server outlived
    /// the way its last one took (the cable pulled) and waits for this computer.
    fn reconnect_control(&self, app: &tauri::AppHandle, serial: &str) -> Result<(), String> {
        let mut control = adbsock::connect(serial, &self.socket)?;
        control
            .write_all(&wire::json(&json!({"t": "control"})))
            .map_err(|e| format!("the phone's app server: {e}"))?;
        let _ = control.set_read_timeout(Some(Duration::from_secs(5)));
        match wire::read(&mut control) {
            Ok(Some(m)) if m.kind == wire::JSON => {}
            _ => return Err("the phone's app server did not say hello".into()),
        }
        let _ = control.set_read_timeout(None);
        let reader = control.try_clone().map_err(|e| e.to_string())?;
        let generation = {
            let mut held = self.control.lock().unwrap();
            *held = control;
            self.control_gen.fetch_add(1, Ordering::SeqCst) + 1
        };
        self.reached_by(serial);
        self.alive.store(true, Ordering::SeqCst);
        spawn_control_reader(
            app.clone(),
            serial.to_string(),
            self.phone.clone(),
            reader,
            self.answer_tx.lock().unwrap().clone(),
            self.alive.clone(),
            self.control_gen.clone(),
            generation,
            self.clip_last.clone(),
            self.sound.clone(),
            self.for_apps.clone(),
        );
        Ok(())
    }

    /// The warm-up a server started for notifications alone skipped: the Apps
    /// page asks for it as it is looked at. The phone runs it once at most.
    pub fn warm(&self) {
        let _ = self.send(&json!({"t": "warm"}));
    }

    /// The phone's notifications to this computer, or no longer. It answers
    /// with `notifs` — whether it may listen — and then a snapshot of what it
    /// has; it listens for the connection this went on, which is said
    /// ([`connection`](Self::connection)'s number), and stops when that goes.
    pub fn notifications(&self, on: bool) -> Result<u64, String> {
        let control = self.control.lock().unwrap();
        (&*control)
            .write_all(&wire::json(&json!({"t": "notifs", "on": on})))
            .map_err(|e| format!("the phone's app server: {e}"))?;
        Ok(self.control_gen.load(Ordering::SeqCst))
    }

    /// Open what notification `key` is about: in the window the phone's server
    /// knows as `window`, or on display `display` (`freeform`, a desktop's),
    /// or on the phone's own screen with neither. The phone says how it went (`notifDone`).
    pub fn notif_open(&self, key: &str, window: Option<&str>, display: Option<i32>, freeform: bool) -> Result<(), String> {
        let mut ask = json!({"t": "notifOpen", "key": key, "freeform": freeform});
        place(&mut ask, window, display);
        self.send(&ask)
    }

    /// One of the notification's own buttons, by its index and what it said;
    /// one that opens something opens it where [`notif_open`](Self::notif_open) would.
    pub fn notif_action(&self, key: &str, i: i64, label: &str, window: Option<&str>, display: Option<i32>) -> Result<(), String> {
        let mut ask = json!({"t": "notifAction", "key": key, "i": i, "label": label, "freeform": display.is_some()});
        place(&mut ask, window, display);
        self.send(&ask)
    }

    /// A reply typed here, into the notification's reply button `i`.
    pub fn notif_reply(&self, key: &str, i: i64, label: &str, text: &str) -> Result<(), String> {
        self.send(&json!({"t": "notifReply", "key": key, "i": i, "label": label, "text": text}))
    }

    /// Dismissed here, and so on the phone.
    pub fn notif_dismiss(&self, key: &str) -> Result<(), String> {
        self.send(&json!({"t": "notifDismiss", "key": key}))
    }

    /// A ringing call, answered or declined from here by CallStyle's own intents.
    pub fn notif_call(&self, key: &str, answer: bool) -> Result<(), String> {
        self.send(&json!({"t": "notifCall", "key": key, "answer": answer}))
    }

    /// One of a ringing call's own buttons — a dialer from before CallStyle's
    /// — by its index, if it still says `label`: fired on the phone, where a call is taken.
    pub fn notif_call_button(&self, key: &str, i: i64, label: &str) -> Result<(), String> {
        self.send(&json!({"t": "notifCall", "key": key, "i": i, "label": label}))
    }

    /// A notification from the phone, for a person to see one arrive.
    pub fn notif_test(&self, title: &str, text: &str) -> Result<(), String> {
        self.send(&json!({"t": "notifTest", "title": title, "text": text}))
    }

    /// The phone's screen as the touchpad of its windows here (apps/pad.rs):
    /// offered on the phone, or with `open`, up on it now. The phone answers
    /// `padOn` — how it went — and then sends what the fingers do (`pad`).
    pub fn pad_on(&self, open: bool) -> Result<(), String> {
        self.send(&json!({"t": "padOn", "open": open}))
    }

    /// The pad no longer offered: down on the phone, and its port closed.
    pub fn pad_off(&self) -> Result<(), String> {
        self.send(&json!({"t": "padOff"}))
    }

    /// This computer's clipboard for the phone's next paste, on the phone's own
    /// connection, unless it is what either side last handed over. True if it went.
    pub fn give_clipboard(&self, text: &str) -> bool {
        {
            let mut last = self.clip_last.lock().unwrap();
            if *last == text {
                return false;
            }
            *last = text.to_string();
        }
        self.send(&json!({"t": "clip", "text": text})).is_ok()
    }

    /// This computer's clipboard no longer holds what was last handed over
    /// either way: whatever either side copies next is new.
    pub fn forget_clipboard(&self) {
        self.clip_last.lock().unwrap().clear();
    }

    /// What the phone last said of where its sound can play.
    pub fn sound(&self) -> Option<PhoneSound> {
        self.sound.lock().unwrap().clone()
    }

    /// Ask the phone to say it again; what it says replaces the last, when it comes.
    pub fn ask_sound(&self) {
        let _ = self.send(&json!({"t": "audio"}));
    }

    /// Pin the phone's media to `to` — or, with None, let the phone choose again.
    pub fn route_audio(&self, to: Option<&Output>) -> Result<(), String> {
        let _one = self.asking.lock().unwrap();
        let ask = match to {
            Some(o) => json!({"t": "audioRoute", "type": o.kind, "address": o.address}),
            None => json!({"t": "audioRoute", "clear": true}),
        };
        self.send(&ask)?;
        let answers = self.answers.lock().unwrap();
        let until = Instant::now() + ROUTE_TIMEOUT;
        loop {
            match answers.recv_timeout(until.saturating_duration_since(Instant::now())) {
                Ok(Answer::Routed(v)) if v["ok"].as_bool() == Some(true) => return Ok(()),
                Ok(Answer::Routed(v)) => {
                    return Err(v["message"].as_str().filter(|m| !m.is_empty()).unwrap_or("the phone refused").to_string())
                }
                Ok(_) => continue,
                Err(_) => return Err("the phone did not answer".into()),
            }
        }
    }

    /// Tell the phone's launcher where this computer sent the sound, for its
    /// own record of the choice: the desktop's flyout then shows the same.
    pub fn tell_launcher_sound(&self, mode: sound::Mode, pick: Option<&Output>) {
        let _ = self.send(&sound::launcher_message(mode, pick));
    }

    /// Every app with a launcher entry, as the phone lists them.
    pub fn apps(&self) -> Result<Vec<Value>, String> {
        let _one = self.asking.lock().unwrap();
        self.send(&json!({"t": "apps"}))?;
        let answers = self.answers.lock().unwrap();
        let until = Instant::now() + ANSWER_TIMEOUT;
        loop {
            match answers.recv_timeout(until.saturating_duration_since(Instant::now())) {
                Ok(Answer::Apps(list)) => return Ok(list),
                Ok(_) => continue,
                Err(_) => return Err("the phone did not list its apps".into()),
            }
        }
    }

    /// The phone's Linux apps, by way of the launcher: `{supported, installed, list}`.
    pub fn linux_apps(&self) -> Result<Value, String> {
        let _one = self.asking.lock().unwrap();
        self.send(&json!({"t": "linuxApps"}))?;
        let answers = self.answers.lock().unwrap();
        let until = Instant::now() + ANSWER_TIMEOUT;
        loop {
            match answers.recv_timeout(until.saturating_duration_since(Instant::now())) {
                Ok(Answer::Linux(v)) => return Ok(v),
                Ok(_) => continue,
                Err(_) => return Err("the phone did not list its Linux apps".into()),
            }
        }
    }

    /// Open a Linux app; its windows arrive as `linux` events, each opened
    /// here. The phone says if it cannot — its own desktop has Linux's windows.
    pub fn linux_run(&self, id: &str, w: u32, h: u32, dpi: u32) -> Result<(), String> {
        let _one = self.asking.lock().unwrap();
        self.send(&json!({"t": "linuxRun", "id": id, "w": w, "h": h, "dpi": dpi}))?;
        let answers = self.answers.lock().unwrap();
        let until = Instant::now() + LINUX_RUN_TIMEOUT;
        loop {
            match answers.recv_timeout(until.saturating_duration_since(Instant::now())) {
                Ok(Answer::LinuxRan(v)) if v["ok"].as_bool() == Some(false) => {
                    let message = v["message"].as_str().filter(|m| !m.is_empty());
                    return Err(message.unwrap_or("The phone could not open it.").to_string());
                }
                Ok(Answer::LinuxRan(_)) => return Ok(()),
                Ok(_) => continue,
                // Nothing said in time: its window may still come.
                Err(_) => return Ok(()),
            }
        }
    }

    /// Linux apps' icons, as `icons` gives Android's: named "linux:<id>".
    pub fn linux_icons(&self, ids: &[String], px: u32, got: impl FnMut(String, Vec<u8>)) -> Result<(), String> {
        self.icons_by("linuxIcons", "ids", ids, px, got)
    }

    /// The icons of `pkgs`, `px` square, as PNGs — each handed to `got` as it comes.
    pub fn icons(&self, pkgs: &[String], px: u32, got: impl FnMut(String, Vec<u8>)) -> Result<(), String> {
        self.icons_by("icons", "pkgs", pkgs, px, got)
    }

    fn icons_by(&self, kind: &str, key: &str, pkgs: &[String], px: u32, mut got: impl FnMut(String, Vec<u8>)) -> Result<(), String> {
        if pkgs.is_empty() {
            return Ok(());
        }
        let _one = self.asking.lock().unwrap();
        self.send(&json!({"t": kind, key: pkgs, "px": px}))?;
        let answers = self.answers.lock().unwrap();
        let until = Instant::now() + ANSWER_TIMEOUT;
        loop {
            match answers.recv_timeout(until.saturating_duration_since(Instant::now())) {
                Ok(Answer::Icon(pkg, png)) => got(pkg, png),
                Ok(Answer::IconsDone) => return Ok(()),
                Ok(Answer::Apps(_)) | Ok(Answer::Linux(_)) | Ok(Answer::LinuxRan(_)) | Ok(Answer::Routed(_)) => continue,
                Err(_) => return Err("the phone did not send its icons".into()),
            }
        }
    }

    fn send(&self, v: &Value) -> Result<(), String> {
        self.control
            .lock()
            .unwrap()
            .write_all(&wire::json(v))
            .map_err(|e| format!("the phone's app server: {e}"))
    }

    fn stop(&self) {
        // Said both ways it may still be heard: its stdin, and the control
        // connection — which is the one left after the cable that started it went.
        let _ = self.send(&json!({"t": "stop"}));
        self.alive.store(false, Ordering::SeqCst);
        if let Some(mut stdin) = self.stdin.lock().unwrap().take() {
            let _ = stdin.write_all(b"stop\n");
            let _ = stdin.flush();
        }
        if let Some(mut c) = self.child.lock().unwrap().take() {
            thread::spawn(move || {
                let until = Instant::now() + Duration::from_secs(2);
                while Instant::now() < until {
                    if let Ok(Some(_)) = c.try_wait() {
                        return;
                    }
                    thread::sleep(Duration::from_millis(50));
                }
                let _ = c.kill();
                let _ = c.wait();
            });
        }
        let _ = self.control.lock().unwrap().shutdown(std::net::Shutdown::Both);
    }
}

/// Where something a notification opens goes: a window's key, else a display.
fn place(ask: &mut Value, window: Option<&str>, display: Option<i32>) {
    if let Some(w) = window {
        ask["window"] = json!(w);
    } else if let Some(d) = display {
        ask["display"] = json!(d);
    }
}

/// Each phone's server, by the phone.
fn servers() -> &'static Mutex<HashMap<String, Arc<Server>>> {
    static S: OnceLock<Mutex<HashMap<String, Arc<Server>>>> = OnceLock::new();
    S.get_or_init(Default::default)
}

/// One start at a time per phone: the page asking twice must not start two.
fn starting() -> &'static Mutex<()> {
    static S: OnceLock<Mutex<()>> = OnceLock::new();
    S.get_or_init(Default::default)
}

/// This app is exiting and every server has been stopped ([`stop_all`]): none
/// starts after that — the notifications' keeper may be in the middle of
/// starting one as the app goes, and it would outlive it on the phone.
static STOPPED: AtomicBool = AtomicBool::new(false);

/// `phone`'s server, reached over `serial`: the one running — adopted over
/// `serial` if it lost its control connection with the way that took (the
/// cable pulled) — else one started now, and warmed up for the windows to come.
pub fn ensure(app: &tauri::AppHandle, serial: &str, phone: &str) -> Result<Arc<Server>, String> {
    ensure_with(app, serial, phone, true, true)
}

/// [`ensure`] for a window that is waiting now: a server started for it
/// skips the warm-up, since the window pays for that itself.
pub fn ensure_for_window(app: &tauri::AppHandle, serial: &str, phone: &str) -> Result<Arc<Server>, String> {
    ensure_with(app, serial, phone, false, true)
}

/// [`ensure`] for the phone's notifications alone: no warm-up, which on a slow
/// phone would sit beside a desktop that may be starting; the Apps page asks
/// for it when it is looked at ([`Server::warm`]).
pub fn ensure_quietly(app: &tauri::AppHandle, serial: &str, phone: &str) -> Result<Arc<Server>, String> {
    ensure_with(app, serial, phone, false, false)
}

fn ensure_with(app: &tauri::AppHandle, serial: &str, phone: &str, warm: bool, for_apps: bool) -> Result<Arc<Server>, String> {
    let s = reach_or_start(app, serial, phone, warm)?;
    if for_apps {
        s.for_apps.store(true, Ordering::SeqCst);
    }
    Ok(s)
}

fn reach_or_start(app: &tauri::AppHandle, serial: &str, phone: &str, warm: bool) -> Result<Arc<Server>, String> {
    if let Some(s) = adopt(app, serial, phone) {
        return Ok(s);
    }
    let _one = starting().lock().unwrap();
    // Started for another window meanwhile.
    if let Some(s) = servers().lock().unwrap().get(phone).filter(|s| s.alive()).cloned() {
        s.reached_by(serial);
        return Ok(s);
    }
    if STOPPED.load(Ordering::SeqCst) {
        return Err("Open Android DeX is closing.".into());
    }
    let s = Arc::new(start(app, serial, phone, warm)?);
    {
        // Kept — unless every server was stopped while this one started.
        let mut servers = servers().lock().unwrap();
        if !STOPPED.load(Ordering::SeqCst) {
            servers.insert(phone.to_string(), s.clone());
            return Ok(s);
        }
    }
    s.stop();
    Err("Open Android DeX is closing.".into())
}

/// `phone`'s server reached over `serial`, if there is one to reach: up, or
/// only without its control connection since the way that took went (the
/// cable pulled) — the same server, which keeps every window it has. Never
/// one started anew.
pub fn adopt(app: &tauri::AppHandle, serial: &str, phone: &str) -> Option<Arc<Server>> {
    let _one = starting().lock().unwrap();
    let s = servers().lock().unwrap().get(phone).cloned()?;
    if s.alive() {
        s.reached_by(serial);
        return Some(s);
    }
    match s.reconnect_control(app, serial) {
        Ok(()) => {
            log::info!("apps [{serial}] the phone's app server is reached this way now");
            // A new connection, which the phone's notifications are asked for on, now.
            crate::notifications::look_again();
            Some(s)
        }
        Err(e) => {
            log::info!("apps [{serial}] the phone's app server has gone ({e})");
            servers().lock().unwrap().remove(phone);
            None
        }
    }
}

/// The server that `serial` reaches, if it is up.
pub fn running(serial: &str) -> Option<Arc<Server>> {
    servers().lock().unwrap().values().find(|s| s.alive() && s.reaches(serial)).cloned()
}

/// `phone`'s server, if it is up: whichever way it is reached.
pub fn of_phone(phone: &str) -> Option<Arc<Server>> {
    servers().lock().unwrap().get(phone).filter(|s| s.alive()).cloned()
}

/// `phone`'s server stops if the phone's notifications were all it was for:
/// they are off now, and it would otherwise run until this app exits.
pub fn retire(phone: &str) {
    let _one = starting().lock().unwrap();
    let retired = {
        let mut servers = servers().lock().unwrap();
        match servers.get(phone) {
            Some(s) if !s.for_apps() => servers.remove(phone),
            _ => None,
        }
    };
    if let Some(s) = retired {
        log::info!("apps [{phone}] the phone's app server stops: notifications were all it was running for");
        s.stop();
    }
}

/// Every phone's server stops, and none starts after: this app is exiting.
pub fn stop_all() {
    let all: Vec<Arc<Server>> = {
        let mut servers = servers().lock().unwrap();
        STOPPED.store(true, Ordering::SeqCst);
        servers.drain().map(|(_, s)| s).collect()
    };
    for s in all {
        s.stop();
    }
}

fn random_name() -> String {
    let mut b = [0u8; 8];
    crate::wireless::fill_random(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn start(app: &tauri::AppHandle, serial: &str, phone: &str, warm: bool) -> Result<Server, String> {
    let t0 = Instant::now();
    let dex = adb::bin_dir(app)?.join("openandroiddex-wmd.dex");
    if !dex.exists() {
        return Err("This build is missing the part of the app that runs on the phone (openandroiddex-wmd.dex).".into());
    }
    let tmp = format!("{PHONE_DEX}.new");
    adb::run_adb(app, &["-s", serial, "push", &dex.to_string_lossy(), &tmp])?;
    adb::run_adb(app, &["-s", serial, "shell", &format!("mv -f {tmp} {PHONE_DEX}")])?;

    let socket = format!("openandroiddex-apps-{}", random_name());
    let mut cmd = adb::adb_command(app);
    // SIGHUP ignored: when the link drops (the cable pulled, adbd restarting)
    // adbd hangs the server up, which would end it on the spot and leave its
    // apps on top of the phone's screen. Ignoring it, the server sees its
    // stdin close instead, and waits for this computer to come back another
    // way; only if it does not are the apps handed back behind what the phone
    // shows.
    cmd.args([
        "-s",
        serial,
        "shell",
        &format!(
            "trap '' HUP; CLASSPATH={PHONE_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.AppServer {socket}{}",
            if warm { "" } else { " --no-warm-up" }
        ),
    ])
    .stdin(Stdio::piped())
    .stdout(Stdio::piped())
    .stderr(Stdio::piped());
    let mut child = cmd.spawn().map_err(|e| format!("failed to run adb: {e}"))?;
    adb::end_with_this_app(&child);
    let stdin = child.stdin.take();
    let alive = Arc::new(AtomicBool::new(true));

    let (ready_tx, ready_rx) = mpsc::channel::<Result<(), String>>();
    if let Some(out) = child.stdout.take() {
        let (serial, alive) = (serial.to_string(), alive.clone());
        thread::Builder::new()
            .name("apps-server-out".into())
            .spawn(move || {
                let mut ready = Some(ready_tx);
                for line in BufReader::new(out).lines() {
                    let Ok(line) = line else { break };
                    let line = line.trim_end();
                    if line.is_empty() {
                        continue;
                    }
                    log::info!("apps [{serial}] {line}");
                    if line.contains("apps ready on") {
                        if let Some(tx) = ready.take() {
                            let _ = tx.send(Ok(()));
                        }
                    } else if line.starts_with("ERROR") {
                        if let Some(tx) = ready.take() {
                            let _ = tx.send(Err(line.trim_start_matches("ERROR:").trim().to_string()));
                        }
                    }
                }
                // The way this shell took has gone — the server's own end, or only the
                // cable's: its control connection says which.
                log::info!("apps [{serial}] the adb shell that started the phone's app server has ended");
                if let Some(tx) = ready.take() {
                    alive.store(false, Ordering::SeqCst);
                    let _ = tx.send(Err("the phone's app server ended before it was ready".into()));
                }
            })
            .map_err(|e| e.to_string())?;
    }
    if let Some(err) = child.stderr.take() {
        let serial = serial.to_string();
        thread::spawn(move || {
            for line in BufReader::new(err).lines().map_while(Result::ok) {
                if !line.trim().is_empty() {
                    log::warn!("apps [{serial}] {}", line.trim_end());
                }
            }
        });
    }
    let started = match ready_rx.recv_timeout(READY_TIMEOUT) {
        Ok(r) => r,
        Err(_) => Err(format!("the phone's app server did not start in {}s", READY_TIMEOUT.as_secs())),
    };
    if let Err(e) = started {
        let _ = child.kill();
        return Err(e);
    }

    let mut control = adbsock::connect(serial, &socket)?;
    control
        .write_all(&wire::json(&json!({"t": "control"})))
        .map_err(|e| format!("the phone's app server: {e}"))?;
    let _ = control.set_read_timeout(Some(Duration::from_secs(10)));
    let first = wire::read(&mut control).map_err(|e| format!("the phone's app server: {e}"))?;
    let _ = control.set_read_timeout(None);
    let hello: Value = match first {
        Some(m) if m.kind == wire::JSON => serde_json::from_slice(&m.data).unwrap_or(Value::Null),
        _ => return Err("the phone's app server did not say hello".into()),
    };
    let hello = Hello {
        sdk: hello["sdk"].as_u64().unwrap_or(0) as u32,
        release: hello["release"].as_str().unwrap_or("").to_string(),
        model: hello["model"].as_str().unwrap_or("").to_string(),
        manufacturer: hello["manufacturer"].as_str().unwrap_or("").to_string(),
        trusted: hello["trusted"].as_bool().unwrap_or(false),
        own_focus: hello["ownFocus"].as_bool().unwrap_or(false),
    };

    let (answer_tx, answer_rx) = mpsc::channel::<Answer>();
    let clip_last = Arc::new(Mutex::new(String::new()));
    let sound = Arc::new(Mutex::new(None));
    let control_gen = Arc::new(AtomicU64::new(0));
    let for_apps = Arc::new(AtomicBool::new(false));
    let reader = control.try_clone().map_err(|e| e.to_string())?;
    spawn_control_reader(
        app.clone(),
        serial.to_string(),
        phone.to_string(),
        reader,
        answer_tx.clone(),
        alive.clone(),
        control_gen.clone(),
        0,
        clip_last.clone(),
        sound.clone(),
        for_apps.clone(),
    );
    log::info!(
        "apps [{serial}] app server up in {}ms: Android {} (API {}), {} {}{}",
        t0.elapsed().as_millis(),
        hello.release,
        hello.sdk,
        hello.manufacturer,
        hello.model,
        if hello.trusted { "" } else { ", displays untrusted" }
    );
    Ok(Server {
        socket,
        hello,
        phone: phone.to_string(),
        ways: Mutex::new(HashSet::from([serial.to_string()])),
        child: Mutex::new(Some(child)),
        stdin: Mutex::new(stdin),
        control: Mutex::new(control),
        answers: Mutex::new(answer_rx),
        answer_tx: Mutex::new(answer_tx),
        asking: Mutex::new(()),
        alive,
        control_gen,
        clip_last,
        sound,
        for_apps,
    })
}

/// What the phone sends on its own: answers go to whoever asked, the
/// clipboard straight to this computer's (once the server is the Apps
/// page's, `for_apps`), word of its sound to the menus, its notifications to
/// this computer's own (notifications/mod.rs).
/// The server is no longer reached once this ends, unless a control
/// connection made again (generation after `generation`) has it.
#[allow(clippy::too_many_arguments)]
fn spawn_control_reader(
    app: tauri::AppHandle,
    serial: String,
    phone: String,
    mut stream: TcpStream,
    answers: Sender<Answer>,
    alive: Arc<AtomicBool>,
    control_gen: Arc<AtomicU64>,
    generation: u64,
    clip_last: Arc<Mutex<String>>,
    sound: Arc<Mutex<Option<PhoneSound>>>,
    for_apps: Arc<AtomicBool>,
) {
    let _ = thread::Builder::new().name("apps-control".into()).spawn(move || {
        loop {
            let m = match wire::read(&mut stream) {
                Ok(Some(m)) => m,
                _ => break,
            };
            match m.kind {
                wire::JSON => {
                    let Ok(v) = serde_json::from_slice::<Value>(&m.data) else { continue };
                    match v["t"].as_str() {
                        Some("apps") => {
                            let list = v["list"].as_array().cloned().unwrap_or_default();
                            let _ = answers.send(Answer::Apps(list));
                        }
                        Some("iconsDone") => {
                            let _ = answers.send(Answer::IconsDone);
                        }
                        Some("linuxApps") => {
                            let _ = answers.send(Answer::Linux(v));
                        }
                        Some("linuxRan") => {
                            let _ = answers.send(Answer::LinuxRan(v));
                        }
                        Some("audio") => {
                            *sound.lock().unwrap() = Some(PhoneSound::parse(&v));
                        }
                        Some("audioRouted") => {
                            let _ = answers.send(Answer::Routed(v));
                        }
                        Some("linux") => super::linux_event(&app, &serial, &v),
                        Some("notifs") | Some("notif") | Some("notifDone") => {
                            crate::notifications::phone_said(&serial, &phone, &v);
                        }
                        Some("pad") | Some("padOn") => super::pad::said(&serial, &phone, &v),
                        // The server's own log, once the shell it printed to has gone with its cable.
                        Some("log") => log::info!("apps [{serial}] {}", v["line"].as_str().unwrap_or("")),
                        Some("clip") => {
                            let text = v["text"].as_str().unwrap_or("").to_string();
                            if text.is_empty() || !for_apps.load(Ordering::SeqCst) {
                                continue;
                            }
                            let fresh = {
                                let mut last = clip_last.lock().unwrap();
                                let fresh = *last != text;
                                *last = text.clone();
                                fresh
                            };
                            if fresh {
                                super::clipboard::set(&app, &text);
                            }
                        }
                        _ => {}
                    }
                }
                wire::ICON => {
                    let d = &m.data;
                    if d.is_empty() {
                        continue;
                    }
                    let n = d[0] as usize;
                    if d.len() < 1 + n {
                        continue;
                    }
                    let pkg = String::from_utf8_lossy(&d[1..1 + n]).into_owned();
                    let _ = answers.send(Answer::Icon(pkg, d[1 + n..].to_vec()));
                }
                _ => {}
            }
        }
        if control_gen.load(Ordering::SeqCst) == generation {
            alive.store(false, Ordering::SeqCst);
            log::info!("apps [{serial}] the control connection closed");
        }
    });
}
