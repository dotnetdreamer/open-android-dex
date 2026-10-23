//! The phone's half of the app windows, kept running: its process, and the
//! one connection that is the phone's as a whole (doc/apps-on-pc.md).
//!
//! One server per phone, started once — the window daemon's dex under
//! `app_process` as the shell, `AppServer` — and held by the `adb shell` that
//! started it: the server ends when that shell's stdin closes, so this app
//! ending ends it, and every app on its displays goes back to the phone. It is
//! started as soon as the Apps page is looked at, so the JVM it is (over a
//! second to start on a Galaxy A13) is up before anyone clicks; opening an app
//! after that costs a display, a launch and an encoder.
//!
//! The control connection carries the list of apps, their icons, the
//! clipboard and the phone's clock. Requests on it are one at a time.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::process::{Child, ChildStdin, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

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
}

pub struct Server {
    pub socket: String,
    pub hello: Hello,
    child: Mutex<Option<Child>>,
    /// Held: the server lives exactly as long as this does.
    stdin: Mutex<Option<ChildStdin>>,
    control: Mutex<TcpStream>,
    answers: Mutex<Receiver<Answer>>,
    /// One request at a time on the control connection.
    asking: Mutex<()>,
    alive: Arc<AtomicBool>,
    /// The phone's text last handed over either way, so neither side gets its own back.
    pub clip_last: Arc<Mutex<String>>,
}

impl Server {
    pub fn alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
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
                Ok(Answer::Apps(_)) | Ok(Answer::Linux(_)) | Ok(Answer::LinuxRan(_)) => continue,
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
        self.alive.store(false, Ordering::SeqCst);
        // Closing its stdin is what ends it; the kill is for an adb that hangs.
        self.stdin.lock().unwrap().take();
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

fn servers() -> &'static Mutex<HashMap<String, Arc<Server>>> {
    static S: OnceLock<Mutex<HashMap<String, Arc<Server>>>> = OnceLock::new();
    S.get_or_init(Default::default)
}

/// One start at a time per phone: the page asking twice must not start two.
fn starting() -> &'static Mutex<()> {
    static S: OnceLock<Mutex<()>> = OnceLock::new();
    S.get_or_init(Default::default)
}

/// The phone's server, started if it is not running — and, started now,
/// warmed up for the windows to come.
pub fn ensure(app: &tauri::AppHandle, serial: &str) -> Result<Arc<Server>, String> {
    ensure_with(app, serial, true)
}

/// The phone's server for a window that is waiting now: started without the
/// warm-up if it is not running, since the window pays for that itself.
pub fn ensure_for_window(app: &tauri::AppHandle, serial: &str) -> Result<Arc<Server>, String> {
    ensure_with(app, serial, false)
}

fn ensure_with(app: &tauri::AppHandle, serial: &str, warm: bool) -> Result<Arc<Server>, String> {
    if let Some(s) = running(serial) {
        return Ok(s);
    }
    let _one = starting().lock().unwrap();
    if let Some(s) = running(serial) {
        return Ok(s);
    }
    let s = Arc::new(start(app, serial, warm)?);
    servers().lock().unwrap().insert(serial.to_string(), s.clone());
    Ok(s)
}

/// The phone's server if it is up.
pub fn running(serial: &str) -> Option<Arc<Server>> {
    let mut map = servers().lock().unwrap();
    match map.get(serial) {
        Some(s) if s.alive() => Some(s.clone()),
        Some(_) => {
            map.remove(serial);
            None
        }
        None => None,
    }
}

/// Every phone's server stops: this app is exiting.
pub fn stop_all() {
    let all: Vec<Arc<Server>> = servers().lock().unwrap().drain().map(|(_, s)| s).collect();
    for s in all {
        s.stop();
    }
}

fn random_name() -> String {
    let mut b = [0u8; 8];
    crate::wireless::fill_random(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn start(app: &tauri::AppHandle, serial: &str, warm: bool) -> Result<Server, String> {
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
    cmd.args([
        "-s",
        serial,
        "shell",
        &format!(
            "CLASSPATH={PHONE_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.AppServer {socket}{}",
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
                alive.store(false, Ordering::SeqCst);
                log::info!("apps [{serial}] the phone's app server has ended");
                if let Some(tx) = ready.take() {
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
    let reader = control.try_clone().map_err(|e| e.to_string())?;
    spawn_control_reader(app.clone(), serial.to_string(), reader, answer_tx, alive.clone(), clip_last.clone());
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
        child: Mutex::new(Some(child)),
        stdin: Mutex::new(stdin),
        control: Mutex::new(control),
        answers: Mutex::new(answer_rx),
        asking: Mutex::new(()),
        alive,
        clip_last,
    })
}

/// What the phone sends on its own: answers go to whoever asked, the
/// clipboard straight to this computer's.
fn spawn_control_reader(
    app: tauri::AppHandle,
    serial: String,
    mut stream: TcpStream,
    answers: Sender<Answer>,
    alive: Arc<AtomicBool>,
    clip_last: Arc<Mutex<String>>,
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
                        Some("linux") => super::linux_event(&app, &serial, &v),
                        Some("clip") => {
                            let text = v["text"].as_str().unwrap_or("").to_string();
                            if text.is_empty() {
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
        alive.store(false, Ordering::SeqCst);
        log::info!("apps [{serial}] the control connection closed");
    });
}
