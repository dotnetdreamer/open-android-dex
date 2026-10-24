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

use std::collections::HashMap;
use std::io::Write;
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use super::{adbsock, wire};

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
    /// Where to open: the frame of the window this one takes over from, in the
    /// platform's own screen coordinates (`Window::frame`). None places it as new.
    pub frame: Option<[f64; 4]>,
    /// When the click that asked for the window came: what its first picture is timed from.
    pub clicked: Instant,
}

/// What a window says about itself to the app.
pub enum Event {
    /// The first picture is on screen, this long after the click.
    Live(Duration),
    /// The app could not be shown, and why — said in the window, and to the app.
    Failed(String),
    /// The window is gone.
    Closed,
}

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
    /// The phone says the app has gone: the window goes, telling the phone nothing.
    fn gone(&self);
}

/// One window's link: what both its sides and its threads share.
pub struct Link {
    out: Mutex<Option<Sender<Vec<u8>>>>,
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
    events: Box<dyn Fn(Event) + Send + Sync>,
    /// Hands this computer's clipboard to the phone, if it changed: on coming to the front, and before a paste.
    clipboard: Box<dyn Fn() -> Option<String> + Send + Sync>,
}

impl Link {
    /// A link, and the other end of what it sends: for [`start`].
    pub fn new(
        clicked: Instant,
        events: Box<dyn Fn(Event) + Send + Sync>,
        clipboard: Box<dyn Fn() -> Option<String> + Send + Sync>,
    ) -> (Arc<Link>, Receiver<Vec<u8>>) {
        let (tx, rx) = mpsc::channel::<Vec<u8>>();
        let link = Link {
            out: Mutex::new(Some(tx)),
            asked: Mutex::new(DisplaySize { w: 0, h: 0, dpi: 0 }),
            closing: AtomicBool::new(false),
            live: AtomicBool::new(false),
            clicked,
            clock_offset_us: AtomicI64::new(i64::MIN),
            best_rtt_us: AtomicU64::new(u64::MAX),
            events,
            clipboard,
        };
        (Arc::new(link), rx)
    }

    pub fn send(&self, bytes: Vec<u8>) {
        if let Some(tx) = self.out.lock().unwrap().as_ref() {
            let _ = tx.send(bytes);
        }
    }

    pub fn event(&self, e: Event) {
        (self.events)(e);
    }

    pub fn closing(&self) -> bool {
        self.closing.load(Ordering::SeqCst)
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
        if let Some(text) = (self.clipboard)() {
            self.send(wire::json(&json!({"t": "clip", "text": text})));
        }
    }

    /// The first picture is on screen: said once, timed from the click.
    pub fn shown(&self) {
        if !self.live.swap(true, Ordering::SeqCst) {
            (self.events)(Event::Live(self.clicked.elapsed()));
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
pub struct NetSpec {
    pub serial: String,
    pub socket: String,
    pub pkg: String,
    pub activity: Option<String>,
    pub linux: Option<i32>,
    pub direct: bool,
}

/// Connect to the phone and open the app, on the link's own threads.
pub fn start(link: Arc<Link>, surface: Arc<dyn Surface>, net: NetSpec, out: Receiver<Vec<u8>>) {
    let _ = thread::Builder::new()
        .name(format!("app-socket-{}", net.pkg))
        .spawn(move || run(link, surface, net, out));
}

fn run(link: Arc<Link>, surface: Arc<dyn Surface>, net: NetSpec, out_rx: Receiver<Vec<u8>>) {
    let stream = match adbsock::connect(&net.serial, &net.socket) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("apps: {}: cannot reach the phone: {e}", net.pkg);
            surface.say(Some("Couldn't reach your phone. Is it still connected?".into()));
            link.event(Event::Failed(e));
            return;
        }
    };
    // The writer: everything the window sends, in order, on its own thread.
    let mut writer = match stream.try_clone() {
        Ok(s) => s,
        Err(e) => {
            link.event(Event::Failed(e.to_string()));
            return;
        }
    };
    let want = surface.wanted();
    *link.asked.lock().unwrap() = want;
    let mut open = json!({
        "t": "open",
        "pkg": net.pkg,
        "activity": net.activity.clone().unwrap_or_default(),
        "w": want.w,
        "h": want.h,
        "dpi": want.dpi,
        "fps": 60,
        "onClose": "phone",
        "direct": net.direct,
    });
    if let Some(xid) = net.linux {
        open["linux"] = json!(xid);
    }
    if writer.write_all(&wire::json(&open)).is_err() {
        surface.say(Some("Couldn't reach your phone. Is it still connected?".into()));
        return;
    }
    let ack_tx = link.out.lock().unwrap().clone();
    let w_stream = stream.try_clone().ok();
    let _ = thread::Builder::new().name(format!("app-out-{}", net.pkg)).spawn(move || {
        while let Ok(bytes) = out_rx.recv() {
            if writer.write_all(&bytes).is_err() {
                break;
            }
        }
        // The window closed: the socket goes, and with it the phone's display.
        let _ = writer.flush();
        if let Some(s) = w_stream {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
    });
    // The phone's clock, a few times a minute: each answer says what it read,
    // and the one that came back quickest says it with the least doubt.
    let pings: Arc<Mutex<HashMap<u64, i64>>> = Arc::new(Mutex::new(HashMap::new()));
    {
        let (link, pings) = (link.clone(), pings.clone());
        let _ = thread::Builder::new().name("app-clock".into()).spawn(move || {
            let mut n = 0u64;
            while !link.closing() && link.out.lock().unwrap().is_some() {
                n += 1;
                pings.lock().unwrap().insert(n, now_us());
                link.send(wire::json(&json!({"t": "ping", "n": n})));
                thread::sleep(Duration::from_millis(if n < 5 { 300 } else { 2000 }));
            }
        });
    }
    read_loop(&link, surface.as_ref(), stream, ack_tx, pings);
}

fn read_loop(
    link: &Link,
    surface: &dyn Surface,
    mut stream: TcpStream,
    ack: Option<Sender<Vec<u8>>>,
    pings: Arc<Mutex<HashMap<u64, i64>>>,
) {
    let ack_bytes = wire::ack();
    // Whether the phone paces its frames by these acks; a stream drawn straight
    // into the encoder (on a cable) does not, and an ack a frame is then only
    // a wake-up on both ends. Until the phone says, as a phone that never says.
    let mut paced = true;
    let mut said_stats = Instant::now();
    loop {
        let m = match wire::read(&mut stream) {
            Ok(Some(m)) => m,
            Ok(None) | Err(_) => break,
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
    if !link.closing() {
        // Opening the app again replaces this window (apps::open): no need to close it first.
        surface.say(Some("Your phone stopped answering. Open the app again to bring it back.".into()));
        link.event(Event::Failed("the phone stopped answering".into()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn link() -> Arc<Link> {
        Link::new(Instant::now(), Box::new(|_| {}), Box::new(|| None)).0
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
        let (l, rx) = Link::new(Instant::now(), Box::new(|_| {}), Box::new(|| None));
        let want = DisplaySize::for_area(825, 1831, 2.0);
        assert_eq!(want, DisplaySize { w: 824, h: 1830, dpi: 320 });
        l.ask_size(want);
        l.ask_size(want);
        assert_eq!(rx.try_iter().count(), 1);
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
