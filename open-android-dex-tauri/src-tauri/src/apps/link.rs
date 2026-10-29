//! A window's link to the phone, whatever draws the window (doc/apps-on-pc.md,
//! "The window"): its own socket to the phone's app server, the display size
//! it asks for, the phone's clock, and what the phone says about its app.
//! `win` (Windows) and `mac` (macOS) are the windows; this is what they share.
//!
//! Three threads of its own, so nothing waits on anything it does not need to:
//!
//! - the reader (over Wi-Fi, frames are acknowledged the moment they land,
//!   which is what the phone paces itself by), which hands each piece of the
//!   picture to the window as it comes;
//! - the writer (a slow link can make a write wait; the window never does);
//! - the clock (a ping a few times a minute: what says how old a frame is).
//!
//! A window's connection is not the window. When the way to the phone it took
//! goes — the cable pulled, Wi-Fi dropping — the window keeps its last picture,
//! and the app around it makes the connection again another way
//! ([`reconnect`]): to the same window on the phone, if its server is still up,
//! or the app opened in it again on a new one. Each connection has threads of
//! its own, and those of a connection that was replaced end quietly.

use std::collections::HashMap;
use std::io::Write;
use std::net::TcpStream;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, Weak};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use super::{adbsock, fetch, sound, wire};

/// What a window is opened with.
pub struct Spec {
    pub id: u64,
    pub serial: String,
    pub socket: String,
    pub pkg: String,
    pub activity: Option<String>,
    pub label: String,
    /// The phone's name, shown after the app's in the title bar.
    pub device: String,
    pub icon_png: Option<Vec<u8>>,
    /// How Windows starts this app again from a pinned taskbar button.
    #[cfg_attr(not(windows), allow(dead_code))]
    pub relaunch: Option<String>,
    #[cfg_attr(not(windows), allow(dead_code))]
    pub relaunch_icon: Option<String>,
    /// How many windows are already open: each new one is cascaded from the last.
    pub cascade: u32,
    /// One of the phone's Linux windows, by its X id, instead of an Android app.
    pub linux: Option<i32>,
    /// The picture's size to open at, in pixels — a Linux window's own; else a phone's shape.
    pub size: Option<(u32, u32)>,
    /// The phone is on a cable: its display can draw straight into the encoder, unpaced.
    pub direct: bool,
    /// How the window reaches its phone, said in its title bar: "USB" or "Wi‑Fi".
    pub via: &'static str,
    /// When the click that asked for the window came: what its first picture is timed from.
    pub clicked: Instant,
    /// The phone's notification it was opened from (its key): the app opens at
    /// what that is about — the conversation — rather than at its front screen.
    pub notif: Option<String>,
}

/// What a window says about itself to the app.
pub enum Event {
    /// The first picture is on screen, this long after the click.
    Live(Duration),
    /// The app could not be shown, and why — said in the window, and to the app.
    Failed(String),
    /// The window's connection went while it showed its app: the way to the
    /// phone it took has gone. The window keeps its last picture meanwhile.
    Lost,
    /// A picture again, on a connection made again, this long after the last went.
    Back(Duration),
    /// The window is gone.
    Closed,
}

/// How a window reaches its phone: the adb serial its connection goes by,
/// the phone's app server there, and what the title bar says of it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Route {
    pub serial: String,
    pub socket: String,
    /// On a cable: the phone's display draws straight into the encoder, unpaced.
    pub direct: bool,
    /// "USB" or "Wi‑Fi".
    pub via: &'static str,
}

/// Why a connection made again did not take.
#[derive(Debug, PartialEq, Eq)]
pub enum Refused {
    /// The phone's server has no such window: it is not the server the window
    /// had. The app can be opened in the window again.
    Unknown,
    /// The window ended on the phone while this computer was away — its app
    /// was opened on the phone, or this computer came too late. The app is the
    /// phone's: the window goes.
    Gone,
    /// The way there failed, or the app could not be opened.
    Failed(String),
}

/// What a window's title bar says of its phone: the way first — what
/// changes, and what a narrow window must not cut off — then the phone's
/// name: "Wi‑Fi · Galaxy S25".
pub fn way_line(phone: &str, via: &str) -> String {
    if phone.is_empty() {
        via.to_string()
    } else {
        format!("{via} · {phone}")
    }
}

/// How long a connection made again waits for the phone's first word: the
/// app opening again on a slow phone, its display and its launch.
const ANSWER_TIMEOUT: Duration = Duration::from_secs(15);

/// The phone app's display: its size in pixels and its density.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DisplaySize {
    pub w: u32,
    pub h: u32,
    pub dpi: u32,
}

impl DisplaySize {
    /// The display for a picture `w`×`h` pixels big at `scale` (pixels per point).
    pub fn for_area(w: u32, h: u32, scale: f32) -> Self {
        DisplaySize { w: w.max(160) & !1, h: h.max(160) & !1, dpi: density(scale) }
    }
}

/// The phone's density for a window at `scale`: one dp to one point of this
/// computer's own interface, as on the desktop that fits its screen.
pub fn density(scale: f32) -> u32 {
    ((160.0 * scale).round() as u32).clamp(72, 960)
}

/// This computer's monotonic clock, in µs from a fixed moment.
pub fn now_us() -> i64 {
    static ORIGIN: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    ORIGIN.get_or_init(Instant::now).elapsed().as_micros() as i64
}

/// The median and 95th percentile of `v`, if it has any.
pub fn percentiles(v: &mut [f64]) -> (Option<f64>, Option<f64>) {
    if v.is_empty() {
        return (None, None);
    }
    v.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let at = |q: f64| v[((v.len() - 1) as f64 * q).round() as usize];
    (Some(at(0.5)), Some(at(0.95)))
}

/// What the link needs of the window it belongs to. Called from the link's
/// threads, never the window's own.
pub trait Surface: Send + Sync {
    /// The app's name, for the log.
    fn label(&self) -> String;
    /// The display the window wants for its size and scale now.
    fn wanted(&self) -> DisplaySize;
    /// A piece of the picture: H.264 Annex B, with the wire's flags.
    fn video(&self, flags: u8, pts: u64, data: Vec<u8>);
    /// The picture has a new size: as coded, and the part that shows.
    fn format(&self, coded: (u32, u32), content: (u32, u32));
    /// The phone's own name for the app, for a window opened knowing only its package.
    fn named(&self, name: &str);
    /// A line to show in the window while it has no picture, or none.
    fn say(&self, text: Option<String>);
    /// How the window reaches its phone now, for its title bar: "USB" or "Wi‑Fi".
    fn way(&self, via: &str);
    /// A short note in the window — where the sound went, how far a dropped
    /// file is — in a strip under the title bar. A `busy` one stays until the
    /// next replaces it; any other goes after a few seconds. None takes it away.
    fn note(&self, text: Option<String>, busy: bool);
    /// The phone says the app has gone: the window goes, telling the phone nothing.
    fn gone(&self);
    /// The app has a drag under way that may leave the window, or no longer
    /// (doc/apps-on-pc.md, "Dragging out"): leaving with it hands it over.
    fn dragging(&self, on: bool);
    /// What a drag handed over carries, for this computer's own drag to take
    /// on; None, it carries nothing this computer can take.
    fn dragged(&self, offer: Option<fetch::Offer>);
}

/// What a window asks of the app around it, from the window's own threads
/// and the link's: the app's policy, which the window only draws.
pub trait Host: Send + Sync {
    /// The window's news: its first picture, a failure, its end.
    fn event(&self, e: Event);
    /// This computer's clipboard for the phone, if it changed since either
    /// side last handed it over: on coming to the front, and before a paste.
    fn clipboard(&self) -> Option<String>;
    /// The title bar's sound menu, as things stand.
    fn sound_menu(&self) -> Vec<sound::Item>;
    /// A choice from that menu; `window` hears how it went.
    fn pick_sound(&self, choice: sound::Choice, window: Arc<dyn Surface>);
    /// Files dropped on the window, for the phone; `window` hears how they go.
    fn dropped(&self, paths: Vec<PathBuf>, window: Arc<dyn Surface>);
    /// More's "Use phone as touchpad": the phone's screen up as the touchpad
    /// of its windows here (apps/pad.rs); `window` hears how it went.
    fn touchpad(&self, window: Arc<dyn Surface>);
    /// More's Copy, sent to the app as its copy key: whatever the phone
    /// copies next comes here as anything it copies does, and `window` says
    /// so — or that nothing was.
    fn copying(&self, window: Arc<dyn Surface>);
}

/// One window's link: what both its sides and its threads share.
pub struct Link {
    out: Mutex<Option<Sender<Vec<u8>>>>,
    /// What this window is called to the phone's server, so that a connection
    /// made again finds the same window there.
    key: String,
    /// The way the window's connection takes now.
    route: Mutex<Route>,
    /// Which connection is the window's: the threads of one replaced end quietly.
    generation: AtomicU64,
    /// What the window opened, to open it again on a new server.
    opened: Mutex<Option<NetSpec>>,
    /// The window, for a connection made again from outside it.
    surface: Mutex<Option<Weak<dyn Surface>>>,
    /// When the connection went, until a picture comes again; `lost` says so without the lock.
    lost_at: Mutex<Option<Instant>>,
    lost: AtomicBool,
    /// Connections being made again now: one that ends meanwhile may only be
    /// the one the phone let go of for the new.
    switching: AtomicU64,
    /// The display size last asked of the phone.
    asked: Mutex<DisplaySize>,
    closing: AtomicBool,
    live: AtomicBool,
    clicked: Instant,
    /// The phone's CLOCK_MONOTONIC minus this computer's clock (µs), from the
    /// ping with the shortest round trip: what turns a frame's timestamp into
    /// how old it is when it is shown. i64::MIN until the first answer.
    clock_offset_us: AtomicI64,
    best_rtt_us: AtomicU64,
    host: Arc<dyn Host>,
}

impl Link {
    /// A link, and the other end of what it sends: for [`start`].
    pub fn new(clicked: Instant, host: Arc<dyn Host>) -> (Arc<Link>, Receiver<Vec<u8>>) {
        let (tx, rx) = mpsc::channel::<Vec<u8>>();
        let mut b = [0u8; 12];
        crate::wireless::fill_random(&mut b);
        let link = Link {
            out: Mutex::new(Some(tx)),
            key: b.iter().map(|x| format!("{x:02x}")).collect(),
            route: Mutex::new(Route { serial: String::new(), socket: String::new(), direct: false, via: "" }),
            generation: AtomicU64::new(0),
            opened: Mutex::new(None),
            surface: Mutex::new(None),
            lost_at: Mutex::new(None),
            lost: AtomicBool::new(false),
            switching: AtomicU64::new(0),
            asked: Mutex::new(DisplaySize { w: 0, h: 0, dpi: 0 }),
            closing: AtomicBool::new(false),
            live: AtomicBool::new(false),
            clicked,
            clock_offset_us: AtomicI64::new(i64::MIN),
            best_rtt_us: AtomicU64::new(u64::MAX),
            host,
        };
        (Arc::new(link), rx)
    }

    pub fn send(&self, bytes: Vec<u8>) {
        if let Some(tx) = self.out.lock().unwrap().as_ref() {
            let _ = tx.send(bytes);
        }
    }

    /// What the phone's server calls this window: how a notification of its
    /// app is opened in it, as well as how a new connection finds it.
    pub fn key(&self) -> &str {
        &self.key
    }

    pub fn event(&self, e: Event) {
        self.host.event(e);
    }

    /// The app around the window: its sound menu, a drop.
    pub fn host(&self) -> &Arc<dyn Host> {
        &self.host
    }

    pub fn closing(&self) -> bool {
        self.closing.load(Ordering::SeqCst)
    }

    /// The way the window reaches its phone now.
    pub fn route(&self) -> Route {
        self.route.lock().unwrap().clone()
    }

    /// Its connection went, and no picture has come on another yet: what a
    /// window that only reports its first picture (Windows') checks each one for.
    #[cfg_attr(not(windows), allow(dead_code))]
    pub fn is_lost(&self) -> bool {
        self.lost.load(Ordering::SeqCst)
    }

    /// The window is going. The phone is told what becomes of the app, unless
    /// it is the one that said; the writer sends what it has and closes the
    /// socket, and with it the phone's display. True the first time.
    pub fn close(&self, tell_phone: bool) -> bool {
        if self.closing.swap(true, Ordering::SeqCst) {
            return false;
        }
        if tell_phone {
            self.send(wire::json(&json!({"t": "close", "app": "phone"})));
        }
        self.out.lock().unwrap().take();
        true
    }

    /// The display size last asked of the phone.
    pub fn asked(&self) -> DisplaySize {
        *self.asked.lock().unwrap()
    }

    /// Ask the phone for `want`, if that is not what was asked last.
    pub fn ask_size(&self, want: DisplaySize) {
        {
            let mut asked = self.asked.lock().unwrap();
            if *asked == want {
                return;
            }
            *asked = want;
        }
        self.send(wire::json(&json!({"t": "resize", "w": want.w, "h": want.h, "dpi": want.dpi})));
    }

    /// A point in a picture `area` pixels big as a point on the phone's display.
    pub fn to_display(&self, x: f64, y: f64, area: (u32, u32)) -> (u16, u16) {
        let d = self.asked();
        let (aw, ah) = (area.0.max(1) as f64, area.1.max(1) as f64);
        let fx = (x.max(0.0) * d.w as f64 / aw).min(d.w.saturating_sub(1) as f64);
        let fy = (y.max(0.0) * d.h as f64 / ah).min(d.h.saturating_sub(1) as f64);
        (fx as u16, fy as u16)
    }

    /// This computer's clipboard to the phone, over this window's own socket —
    /// so it lands before the paste that follows it on the same socket.
    pub fn hand_clipboard_over(&self) {
        if let Some(text) = self.host.clipboard() {
            self.send(wire::json(&json!({"t": "clip", "text": text})));
        }
    }

    /// A picture is on screen: the first is said once, timed from the click;
    /// the first after the connection went, timed from its going.
    pub fn shown(&self) {
        if !self.live.swap(true, Ordering::SeqCst) {
            self.host.event(Event::Live(self.clicked.elapsed()));
        }
        if self.lost.swap(false, Ordering::SeqCst) {
            if let Some(at) = self.lost_at.lock().unwrap().take() {
                self.host.event(Event::Back(at.elapsed()));
            }
        }
    }

    pub fn is_live(&self) -> bool {
        self.live.load(Ordering::SeqCst)
    }

    /// How old a frame the phone stamped `pts` (its clock, µs) is now, in ms —
    /// once the phone's clock is known.
    pub fn age_ms(&self, pts: u64) -> Option<f64> {
        self.age_ms_at(pts, now_us())
    }

    /// How old a frame stamped `pts` was at `at` (this computer's clock, µs), in ms.
    pub fn age_ms_at(&self, pts: u64, at: i64) -> Option<f64> {
        let offset = self.clock_offset_us.load(Ordering::SeqCst);
        if offset == i64::MIN {
            return None;
        }
        let age = (at + offset - pts as i64) as f64 / 1000.0;
        (0.0..2000.0).contains(&age).then_some(age)
    }
}

/// Where the link goes, and what it opens there.
#[derive(Clone)]
pub struct NetSpec {
    pub serial: String,
    pub socket: String,
    pub pkg: String,
    pub activity: Option<String>,
    pub linux: Option<i32>,
    pub direct: bool,
    /// "USB" or "Wi‑Fi": how the window reaches the phone, for its title bar.
    pub via: &'static str,
    /// The notification the window was opened from, for its first opening only.
    pub notif: Option<String>,
}

/// Connect to the phone and open the app, on the link's own threads.
pub fn start(link: Arc<Link>, surface: Arc<dyn Surface>, net: NetSpec, out: Receiver<Vec<u8>>) {
    *link.route.lock().unwrap() =
        Route { serial: net.serial.clone(), socket: net.socket.clone(), direct: net.direct, via: net.via };
    // Opened again on a new server, the app opens as it is: its notification was the first opening's.
    *link.opened.lock().unwrap() = Some(NetSpec { notif: None, ..net.clone() });
    *link.surface.lock().unwrap() = Some(Arc::downgrade(&surface));
    let _ = thread::Builder::new()
        .name(format!("app-socket-{}", net.pkg))
        .spawn(move || run(link, surface, net, out));
}

/// What opens the app in a window on the phone — this one's, by its key.
fn open_message(net: &NetSpec, want: DisplaySize, direct: bool, key: &str) -> Value {
    let mut open = json!({
        "t": "open",
        "pkg": net.pkg,
        "activity": net.activity.clone().unwrap_or_default(),
        "w": want.w,
        "h": want.h,
        "dpi": want.dpi,
        "fps": 60,
        "onClose": "phone",
        "direct": direct,
        "key": key,
    });
    if let Some(xid) = net.linux {
        open["linux"] = json!(xid);
    }
    if let Some(key) = &net.notif {
        open["notif"] = json!(key);
    }
    open
}

fn run(link: Arc<Link>, surface: Arc<dyn Surface>, net: NetSpec, out_rx: Receiver<Vec<u8>>) {
    let mut stream = match adbsock::connect(&net.serial, &net.socket) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("apps: {}: cannot reach the phone: {e}", net.pkg);
            surface.say(Some("Couldn't reach your phone. Is it still connected?".into()));
            link.event(Event::Failed(e));
            return;
        }
    };
    let want = surface.wanted();
    *link.asked.lock().unwrap() = want;
    if stream.write_all(&wire::json(&open_message(&net, want, net.direct, &link.key))).is_err() {
        surface.say(Some("Couldn't reach your phone. Is it still connected?".into()));
        link.event(Event::Failed("the phone did not take the window".into()));
        return;
    }
    serve(link, surface, stream, out_rx, 0, None);
}

/// Make the window's connection again, over `route`: to the same window on
/// the phone (its server still up), or — `reopen` — the app opened in it
/// again, on a new server. Waits for the phone's first word, so the caller
/// knows which it got; on success the window's picture follows on threads of
/// this connection's own, and the title bar says the new way.
pub fn reconnect(link: &Arc<Link>, route: Route, reopen: bool) -> Result<(), Refused> {
    link.switching.fetch_add(1, Ordering::SeqCst);
    let made = connect_again(link, route, reopen);
    link.switching.fetch_sub(1, Ordering::SeqCst);
    made
}

fn connect_again(link: &Arc<Link>, route: Route, reopen: bool) -> Result<(), Refused> {
    let surface = link
        .surface
        .lock()
        .unwrap()
        .as_ref()
        .and_then(Weak::upgrade)
        .ok_or_else(|| Refused::Failed("the window has closed".into()))?;
    let net = link.opened.lock().unwrap().clone().ok_or_else(|| Refused::Failed("the window has not opened yet".into()))?;
    if link.closing() {
        return Err(Refused::Failed("the window is closing".into()));
    }
    let mut stream = adbsock::connect(&route.serial, &route.socket).map_err(Refused::Failed)?;
    let want = surface.wanted();
    let ask = if reopen {
        open_message(&net, want, route.direct, &link.key)
    } else {
        json!({"t": "attach", "key": link.key, "w": want.w, "h": want.h, "dpi": want.dpi, "direct": route.direct})
    };
    stream.write_all(&wire::json(&ask)).map_err(|e| Refused::Failed(format!("the phone: {e}")))?;
    let _ = stream.set_read_timeout(Some(ANSWER_TIMEOUT));
    let first = match wire::read(&mut stream) {
        Ok(Some(m)) => m,
        Ok(None) => return Err(Refused::Failed("the phone closed the connection".into())),
        Err(e) => return Err(Refused::Failed(format!("the phone did not answer: {e}"))),
    };
    let _ = stream.set_read_timeout(None);
    answer_of(&first)?;
    // The window's connection is this one now; the last one's writer ends with
    // its channel. A window closing meanwhile closes this one too.
    let (generation, rx) = {
        let mut out = link.out.lock().unwrap();
        if link.closing() {
            return Err(Refused::Failed("the window is closing".into()));
        }
        *link.asked.lock().unwrap() = want;
        *link.route.lock().unwrap() = route.clone();
        // The new way's own round trip, not the last one's.
        link.best_rtt_us.store(u64::MAX, Ordering::SeqCst);
        let generation = link.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let (tx, rx) = mpsc::channel::<Vec<u8>>();
        *out = Some(tx);
        (generation, rx)
    };
    surface.way(route.via);
    let (link, name) = (link.clone(), format!("app-socket-{}", net.pkg));
    thread::Builder::new()
        .name(name)
        .spawn(move || serve(link, surface, stream, rx, generation, Some(first)))
        .map(|_| ())
        .map_err(|e| Refused::Failed(e.to_string()))
}

/// What the phone's first word on a connection made again says: it took the
/// window ("attached", or the display an app opened on), or why not.
fn answer_of(first: &wire::Msg) -> Result<(), Refused> {
    if first.kind != wire::JSON {
        return Ok(());
    }
    let v: Value = serde_json::from_slice(&first.data).unwrap_or(Value::Null);
    if v["t"] != "state" {
        return Ok(());
    }
    Err(match v["state"].as_str() {
        Some("closed") => Refused::Gone,
        Some("unknown") => Refused::Unknown,
        _ => Refused::Failed(v["message"].as_str().unwrap_or("the app could not be opened").to_string()),
    })
}

/// Hand the window's app back to the phone, over `route`, for a window closed
/// while its connection was gone: the phone's server still has it waiting.
pub fn hand_back(link: &Link, route: &Route) {
    let Ok(mut stream) = adbsock::connect(&route.serial, &route.socket) else { return };
    let want = link.asked();
    let ask = json!({"t": "attach", "key": link.key, "w": want.w, "h": want.h, "dpi": want.dpi, "direct": route.direct});
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    if stream.write_all(&wire::json(&ask)).is_err() {
        return;
    }
    if let Ok(Some(first)) = wire::read(&mut stream) {
        if answer_of(&first).is_ok() {
            let _ = stream.write_all(&wire::json(&json!({"t": "close", "app": "phone"})));
            let _ = stream.flush();
        }
    }
}

/// One of the window's connections: what the window sends, in order, on a
/// writer of its own; the phone's clock; and what the phone says, on this
/// thread. `first` is a word of the phone's read already.
fn serve(
    link: Arc<Link>,
    surface: Arc<dyn Surface>,
    stream: TcpStream,
    out_rx: Receiver<Vec<u8>>,
    generation: u64,
    first: Option<wire::Msg>,
) {
    let mut writer = match stream.try_clone() {
        Ok(s) => s,
        Err(e) => {
            link.event(Event::Failed(e.to_string()));
            return;
        }
    };
    let ack_tx = link.out.lock().unwrap().clone();
    let w_stream = stream.try_clone().ok();
    let _ = thread::Builder::new().name("app-out".into()).spawn(move || {
        while let Ok(bytes) = out_rx.recv() {
            if writer.write_all(&bytes).is_err() {
                break;
            }
        }
        // The window closed, or this connection was replaced: the socket goes.
        let _ = writer.flush();
        if let Some(s) = w_stream {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
    });
    // The phone's clock, a few times a minute: each answer says what it read,
    // and the one that came back quickest says it with the least doubt.
    let pings: Arc<Mutex<HashMap<u64, i64>>> = Arc::new(Mutex::new(HashMap::new()));
    let done = Arc::new(AtomicBool::new(false));
    {
        let (link, pings, done) = (link.clone(), pings.clone(), done.clone());
        let _ = thread::Builder::new().name("app-clock".into()).spawn(move || {
            let mut n = 0u64;
            while !link.closing() && !done.load(Ordering::SeqCst) && link.generation.load(Ordering::SeqCst) == generation {
                n += 1;
                pings.lock().unwrap().insert(n, now_us());
                link.send(wire::json(&json!({"t": "ping", "n": n})));
                thread::sleep(Duration::from_millis(if n < 5 { 300 } else { 2000 }));
            }
        });
    }
    read_loop(&link, surface.as_ref(), stream, ack_tx, pings, generation, first);
    done.store(true, Ordering::SeqCst);
}

fn read_loop(
    link: &Link,
    surface: &dyn Surface,
    mut stream: TcpStream,
    ack: Option<Sender<Vec<u8>>>,
    pings: Arc<Mutex<HashMap<u64, i64>>>,
    generation: u64,
    mut first: Option<wire::Msg>,
) {
    let ack_bytes = wire::ack();
    // Whether the phone paces its frames by these acks; a stream drawn straight
    // into the encoder (on a cable) does not, and an ack a frame is then only
    // a wake-up on both ends. Until the phone says, as a phone that never says.
    let mut paced = true;
    let mut said_stats = Instant::now();
    loop {
        let m = match first.take() {
            Some(m) => m,
            None => match wire::read(&mut stream) {
                Ok(Some(m)) => m,
                Ok(None) | Err(_) => break,
            },
        };
        match m.kind {
            wire::VIDEO => {
                let Some((flags, pts, data)) = wire::video(&m.data) else { continue };
                if paced && flags & wire::VIDEO_CONFIG == 0 {
                    // Acknowledged on arrival: the phone paces itself by the link, not by this decoder.
                    if let Some(a) = &ack {
                        let _ = a.send(ack_bytes.clone());
                    }
                }
                surface.video(flags, pts, data.to_vec());
            }
            wire::JSON => {
                let Ok(v) = serde_json::from_slice::<Value>(&m.data) else { continue };
                match v["t"].as_str() {
                    Some("pong") => {
                        let arrived = now_us();
                        let sent = v["n"].as_u64().and_then(|n| pings.lock().unwrap().remove(&n));
                        if let (Some(sent), Some(at)) = (sent, v["at"].as_i64()) {
                            let rtt = (arrived - sent).max(0) as u64;
                            if rtt <= link.best_rtt_us.load(Ordering::SeqCst) {
                                link.best_rtt_us.store(rtt, Ordering::SeqCst);
                                // The phone read its clock about half way through the round trip.
                                link.clock_offset_us.store(at - (sent + rtt as i64 / 2), Ordering::SeqCst);
                            }
                        }
                    }
                    Some("display") => {
                        if let Some(name) = v["label"].as_str().filter(|n| !n.is_empty()) {
                            surface.named(name);
                        }
                    }
                    Some("drag") => surface.dragging(v["on"].as_bool() == Some(true)),
                    Some("dragged") => surface.dragged(fetch::Offer::parse(&v)),
                    Some("video") => {
                        let coded = (v["w"].as_u64().unwrap_or(0) as u32, v["h"].as_u64().unwrap_or(0) as u32);
                        let content = (v["cw"].as_u64().unwrap_or(0) as u32, v["ch"].as_u64().unwrap_or(0) as u32);
                        paced = v["paced"].as_bool() != Some(false);
                        log::info!(
                            "apps: {}: {}x{} from {}{}, {} kb/s",
                            surface.label(),
                            content.0,
                            content.1,
                            v["encoder"].as_str().unwrap_or("?"),
                            if v["hw"].as_bool() == Some(true) { "" } else { " (software)" },
                            v["bitrate"].as_u64().unwrap_or(0) / 1000
                        );
                        surface.format(coded, content);
                        // The phone has caught up with a resize: ask for the next if the window moved on.
                        link.ask_size(surface.wanted());
                    }
                    Some("state") => {
                        let message = v["message"].as_str().map(str::to_string);
                        match v["state"].as_str() {
                            Some("closed") => {
                                surface.gone();
                                return;
                            }
                            Some("failed") => {
                                let text = message.unwrap_or_else(|| "The app could not be opened.".into());
                                surface.say(Some(text.clone()));
                                link.event(Event::Failed(text));
                            }
                            _ => {}
                        }
                    }
                    Some("stats") => {
                        if said_stats.elapsed() >= Duration::from_secs(10) {
                            said_stats = Instant::now();
                            // The phone times its frames' acks; without acks, the clock's quickest ping.
                            let rtt = match v["rttMs"].as_f64() {
                                Some(ms) if ms >= 0.0 => format!("{ms}"),
                                _ => match link.best_rtt_us.load(Ordering::SeqCst) {
                                    u64::MAX => "?".into(),
                                    us => format!("{:.1}", us as f64 / 1000.0),
                                },
                            };
                            log::info!(
                                "apps: {}: phone sends {} fps, {} kb/s, {} ms in the encoder, link round trip {} ms",
                                surface.label(),
                                v["fps"],
                                v["kbps"],
                                v["encodeMs"],
                                rtt
                            );
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    }
    // The phone lets go of a connection as another takes the window: until
    // that one is made, this end may only be that.
    let until = Instant::now() + ANSWER_TIMEOUT;
    while link.switching.load(Ordering::SeqCst) > 0 && Instant::now() < until {
        thread::sleep(Duration::from_millis(20));
    }
    if link.closing() || link.generation.load(Ordering::SeqCst) != generation {
        // Closed, or another connection has the window now.
        return;
    }
    if link.is_live() {
        // The way to the phone this connection took has gone. The window keeps
        // its last picture; the app around it makes the connection again.
        link.lost_at.lock().unwrap().get_or_insert_with(Instant::now);
        link.lost.store(true, Ordering::SeqCst);
        log::info!("apps: {}: the connection over {} went", surface.label(), link.route().via);
        link.event(Event::Lost);
    } else {
        surface.say(Some("Your phone stopped answering. Open the app again to bring it back.".into()));
        link.event(Event::Failed("the phone stopped answering".into()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct NoHost;

    impl Host for NoHost {
        fn event(&self, _e: Event) {}
        fn clipboard(&self) -> Option<String> {
            None
        }
        fn sound_menu(&self) -> Vec<sound::Item> {
            Vec::new()
        }
        fn pick_sound(&self, _choice: sound::Choice, _window: Arc<dyn Surface>) {}
        fn dropped(&self, _paths: Vec<PathBuf>, _window: Arc<dyn Surface>) {}
        fn touchpad(&self, _window: Arc<dyn Surface>) {}
        fn copying(&self, _window: Arc<dyn Surface>) {}
    }

    fn link() -> Arc<Link> {
        Link::new(Instant::now(), Arc::new(NoHost)).0
    }

    #[test]
    fn a_point_maps_to_the_display_and_stays_on_it() {
        let l = link();
        l.ask_size(DisplaySize { w: 1080, h: 2400, dpi: 320 });
        // A window showing it at half size.
        assert_eq!(l.to_display(270.0, 600.0, (540, 1200)), (540, 1200));
        assert_eq!(l.to_display(-5.0, -5.0, (540, 1200)), (0, 0));
        assert_eq!(l.to_display(9999.0, 9999.0, (540, 1200)), (1079, 2399));
    }

    #[test]
    fn a_size_is_asked_once() {
        let (l, rx) = Link::new(Instant::now(), Arc::new(NoHost));
        let want = DisplaySize::for_area(825, 1831, 2.0);
        assert_eq!(want, DisplaySize { w: 824, h: 1830, dpi: 320 });
        l.ask_size(want);
        l.ask_size(want);
        assert_eq!(rx.try_iter().count(), 1);
    }

    #[test]
    fn the_phones_first_word_says_whether_it_took_the_window() {
        let word = |v: Value| wire::Msg { kind: wire::JSON, data: v.to_string().into_bytes() };
        assert_eq!(answer_of(&word(json!({"t": "attached", "display": 7}))), Ok(()));
        assert_eq!(answer_of(&word(json!({"t": "display", "id": 7}))), Ok(()));
        assert_eq!(answer_of(&word(json!({"t": "state", "state": "closed"}))), Err(Refused::Gone));
        assert_eq!(answer_of(&word(json!({"t": "state", "state": "unknown"}))), Err(Refused::Unknown));
        assert_eq!(
            answer_of(&word(json!({"t": "state", "state": "failed", "message": "no"}))),
            Err(Refused::Failed("no".into()))
        );
        // A picture first is a window taken back too.
        assert_eq!(answer_of(&wire::Msg { kind: wire::VIDEO, data: vec![0; 9] }), Ok(()));
    }

    #[test]
    fn a_window_asks_for_its_app_by_its_key() {
        let net = NetSpec {
            serial: "s".into(),
            socket: "k".into(),
            pkg: "com.android.settings".into(),
            activity: None,
            linux: None,
            direct: true,
            via: "USB",
            notif: None,
        };
        let open = open_message(&net, DisplaySize { w: 400, h: 800, dpi: 320 }, false, "abc");
        assert_eq!((open["t"].as_str(), open["key"].as_str(), open["direct"].as_bool()), (Some("open"), Some("abc"), Some(false)));
        assert!(open.get("linux").is_none());
        assert!(open.get("notif").is_none());
        // Two windows are never the same window to the phone.
        assert_ne!(link().key, link().key);
    }

    #[test]
    fn a_window_opened_from_a_notification_opens_at_it() {
        let net = NetSpec {
            serial: "s".into(),
            socket: "k".into(),
            pkg: "com.google.android.apps.messaging".into(),
            activity: None,
            linux: None,
            direct: true,
            via: "USB",
            notif: Some("0|com.google.android.apps.messaging|2|tag|10145".into()),
        };
        let open = open_message(&net, DisplaySize { w: 400, h: 800, dpi: 320 }, true, "abc");
        assert_eq!(open["notif"].as_str(), Some("0|com.google.android.apps.messaging|2|tag|10145"));
    }

    #[test]
    fn the_way_comes_before_the_phones_name() {
        assert_eq!(way_line("Galaxy S25 Ultra", "Wi\u{2011}Fi"), "Wi\u{2011}Fi · Galaxy S25 Ultra");
        assert_eq!(way_line("", "USB"), "USB");
    }

    #[test]
    fn a_frames_age_needs_the_phones_clock() {
        let l = link();
        assert_eq!(l.age_ms_at(1_000, 5_000), None);
        // The phone's clock is 1 s ahead of this one.
        l.clock_offset_us.store(1_000_000, Ordering::SeqCst);
        assert_eq!(l.age_ms_at(1_000_000 + 4_000 - 12_500, 4_000), Some(12.5));
        // A frame from the future, or from long ago, is a clock that slipped.
        assert_eq!(l.age_ms_at(1_000_000 + 9_000, 4_000), None);
    }
}
