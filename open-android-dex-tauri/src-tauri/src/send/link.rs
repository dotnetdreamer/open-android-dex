//! The `send` connection: this computer's standing link to a phone's app
//! server for "Send to computer" and the photos (AppSend.java on the phone).
//!
//! Made through the adb server, as every connection to the server is
//! (apps/adbsock.rs): nothing listens on this computer. Its first message is
//! ours — `send {v, computer, os, share}` — and the phone's hello answers it
//! with what it has of the launcher and its own clock. After that the phone
//! says what was shared (`shared`, `cancelled`) and what its photos did
//! (`photos`, `thumb`), and answers what is asked (`older`, `all`, `status`):
//! each asking carries a number of its own (`ask`), which its answer gives
//! back, so an answer reaches only the one who asked — never a later asker of
//! its kind after a timeout — and the phone makes them on a thread of their
//! own, never behind thumbnails. A link that ends wakes the keeper, which
//! makes it again.

use std::collections::HashMap;
use std::io::Write;
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tauri::AppHandle;

use crate::apps::shared;

/// What the phone said of itself on the link.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Hello {
    /// `ready`, `old` (no share entry, or one this computer no longer speaks to) or `missing`.
    pub launcher: String,
    /// The share entry is enabled.
    pub entry: bool,
    /// Port 7190 is this server's: the launcher's knock reaches it.
    pub port: bool,
    pub sdk: u32,
    /// The phone's clock as it said this, in seconds.
    pub now: i64,
}

impl Hello {
    pub fn parse(v: &Value) -> Hello {
        Hello {
            launcher: v["launcher"].as_str().unwrap_or("missing").to_string(),
            entry: v["entry"].as_bool().unwrap_or(false),
            port: v["port"].as_bool().unwrap_or(false),
            sdk: v["sdk"].as_u64().unwrap_or(0) as u32,
            now: v["now"].as_i64().unwrap_or(0),
        }
    }
}

/// What the phone says on the link, parsed.
#[derive(Clone, Debug, PartialEq)]
pub enum Said {
    Hello(Hello),
    Shared(Value),
    Cancelled(u64),
    /// The photos: `snapshot`, `new`, `gone` or `failed`.
    Photos(Value),
    Thumb(Value),
    /// An answer to something asked (`older`, `all`, `status`, or its failure), by the asking's number.
    Answer(u64, Value),
    Other,
}

pub fn parse(v: &Value) -> Said {
    // An answer is known by the number its asking carried, whatever it says.
    if let Some(n) = v["ask"].as_u64() {
        return Said::Answer(n, v.clone());
    }
    match v["t"].as_str() {
        Some("send") => Said::Hello(Hello::parse(v)),
        Some("shared") => Said::Shared(v.clone()),
        Some("cancelled") => Said::Cancelled(v["share"].as_u64().unwrap_or(0)),
        // A page or a status without its number is nobody's: never the next asker's.
        Some("photos") => match v["kind"].as_str() {
            Some("older" | "all") => Said::Other,
            _ => Said::Photos(v.clone()),
        },
        Some("thumb") => Said::Thumb(v.clone()),
        _ => Said::Other,
    }
}

/// An answer as its asker takes it: what was asked for (`kind`), or why not.
fn answer_of(v: Value, kind: &str) -> Result<Value, String> {
    let said = if v["t"].as_str() == Some("photos") { v["kind"].as_str() } else { v["t"].as_str() };
    match said {
        Some(k) if k == kind => Ok(v),
        Some("failed") => Err("your phone couldn't read its photos".into()),
        _ => Err("your phone didn't answer".into()),
    }
}

pub struct Link {
    pub serial: String,
    pub phone: String,
    /// The server's socket: a new server is a new link.
    pub socket: String,
    out: Mutex<TcpStream>,
    alive: Arc<AtomicBool>,
    hello: Mutex<(Hello, Instant)>,
    /// Whoever waits for an answer, by the number their asking carried.
    waiting: Mutex<HashMap<u64, Sender<Value>>>,
    /// The next asking's number.
    asks: AtomicU64,
    /// The photos asked for on this link: how often, in ms; None, not looked at.
    photos_every: Mutex<Option<u64>>,
    /// When the phone was last asked to look at its launcher again.
    looked: Mutex<Option<Instant>>,
}

impl Link {
    /// The link to `phone`'s server on `socket`, reached over `serial`; `share`, whether this computer takes shares.
    pub fn open(app: &AppHandle, serial: &str, phone: &str, socket: &str, share: bool) -> Result<Arc<Link>, String> {
        let t0 = Instant::now();
        let mut s = shared::connect(serial, socket)?;
        let ask = json!({"t": "send", "v": 1, "computer": super::computer_name(), "os": super::os(), "share": share});
        s.write_all(&shared::json(&ask)).map_err(|e| format!("the phone's app server: {e}"))?;
        let _ = s.set_read_timeout(Some(Duration::from_secs(10)));
        let hello = match shared::read(&mut s) {
            Ok(Some(m)) if m.kind == shared::JSON => serde_json::from_slice::<Value>(&m.data).unwrap_or(Value::Null),
            _ => return Err("the phone's app server did not answer the link".into()),
        };
        if hello["t"].as_str() != Some("send") {
            return Err("the phone's app server is from before Send to computer".into());
        }
        let _ = s.set_read_timeout(None);
        let reader = s.try_clone().map_err(|e| e.to_string())?;
        let link = Arc::new(Link {
            serial: serial.to_string(),
            phone: phone.to_string(),
            socket: socket.to_string(),
            out: Mutex::new(s),
            alive: Arc::new(AtomicBool::new(true)),
            hello: Mutex::new((Hello::parse(&hello), Instant::now())),
            waiting: Mutex::new(HashMap::new()),
            asks: AtomicU64::new(1),
            photos_every: Mutex::new(None),
            looked: Mutex::new(Some(Instant::now())),
        });
        log::info!(
            "send [{serial}] linked to the phone's server in {} ms: launcher {}, entry {}, port {}",
            t0.elapsed().as_millis(),
            link.hello().launcher,
            if link.hello().entry { "on" } else { "off" },
            if link.hello().port { "this server's" } else { "not this server's" }
        );
        let (app, l) = (app.clone(), link.clone());
        let _ = std::thread::Builder::new().name("send-link".into()).spawn(move || l.read(&app, reader));
        Ok(link)
    }

    pub fn alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    pub fn hello(&self) -> Hello {
        self.hello.lock().unwrap().0.clone()
    }

    /// The phone's clock now, by what it said and how long ago: seconds.
    pub fn phone_now(&self) -> i64 {
        let h = self.hello.lock().unwrap();
        h.0.now + h.1.elapsed().as_secs() as i64
    }

    pub fn send(&self, v: &Value) -> Result<(), String> {
        self.out.lock().unwrap().write_all(&shared::json(v)).map_err(|e| format!("the phone's app server: {e}"))
    }

    /// `v` asked, with a number of its own, and the phone's answer to it (of
    /// `kind`), waited for up to `timeout`. An answer that comes later is dropped.
    pub fn ask(&self, v: &Value, kind: &str, timeout: Duration) -> Result<Value, String> {
        let n = self.asks.fetch_add(1, Ordering::SeqCst);
        let mut v = v.clone();
        v["ask"] = json!(n);
        let (tx, rx) = mpsc::channel();
        self.waiting.lock().unwrap().insert(n, tx);
        let answer = self.send(&v).and_then(|()| rx.recv_timeout(timeout).map_err(|_| "your phone didn't answer".to_string()));
        self.waiting.lock().unwrap().remove(&n);
        answer.and_then(|a| answer_of(a, kind))
    }

    /// Whether the phone was last asked to look again at least `every` ago: if so, it counts as asked now.
    pub fn look_due(&self, every: Duration) -> bool {
        let mut at = self.looked.lock().unwrap();
        if at.is_some_and(|t| t.elapsed() < every) {
            return false;
        }
        *at = Some(Instant::now());
        true
    }

    /// The photos looked at every `every` ms, or not at all: said to the phone only when it changes.
    pub fn want_photos(&self, every: Option<u64>) {
        let mut now = self.photos_every.lock().unwrap();
        if *now == every {
            return;
        }
        let ask = match every {
            Some(ms) => json!({"t": "photos", "on": true, "limit": super::photos::LIMIT, "every": ms}),
            None => json!({"t": "photos", "on": false}),
        };
        if self.send(&ask).is_ok() {
            *now = every;
        }
    }

    pub fn photos_every(&self) -> Option<u64> {
        *self.photos_every.lock().unwrap()
    }

    pub fn close(&self) {
        self.alive.store(false, Ordering::SeqCst);
        let _ = self.out.lock().unwrap().shutdown(std::net::Shutdown::Both);
    }

    fn read(self: Arc<Self>, app: &AppHandle, mut stream: TcpStream) {
        while let Ok(Some(m)) = shared::read(&mut stream) {
            if m.kind != shared::JSON {
                continue;
            }
            let Ok(v) = serde_json::from_slice::<Value>(&m.data) else { continue };
            match parse(&v) {
                Said::Hello(h) => {
                    *self.hello.lock().unwrap() = (h.clone(), Instant::now());
                    super::hello_seen(app, &self.phone, &h);
                    super::changed(app);
                }
                Said::Shared(v) => super::inbox::shared(app, &self, &v),
                Said::Cancelled(share) => super::inbox::cancelled(&self.phone, share),
                Said::Photos(v) => super::photos::said(app, &self, &v),
                Said::Thumb(v) => super::photos::thumb(app, &self.phone, &v),
                Said::Answer(n, v) => {
                    if let Some(tx) = self.waiting.lock().unwrap().remove(&n) {
                        let _ = tx.send(v);
                    }
                }
                Said::Other => {}
            }
        }
        let was = self.alive.swap(false, Ordering::SeqCst);
        if was {
            log::info!("send [{}] the link to the phone's server ended", self.serial);
        }
        super::link_ended(app, &self);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_phones_messages_are_parsed() {
        let h = parse(&json!({"t": "send", "ok": true, "v": 1, "sdk": 35, "launcher": "ready", "entry": true, "port": true, "now": 1794436573}));
        assert_eq!(h, Said::Hello(Hello { launcher: "ready".into(), entry: true, port: true, sdk: 35, now: 1794436573 }));
        // A phone with no launcher at all.
        let none = parse(&json!({"t": "send", "ok": true, "sdk": 26, "launcher": "missing", "entry": false, "port": true, "now": 5}));
        assert_eq!(none, Said::Hello(Hello { launcher: "missing".into(), entry: false, port: true, sdk: 26, now: 5 }));
        assert_eq!(Hello::parse(&json!({"t": "send"})).launcher, "missing");
        let shared = json!({"t": "shared", "share": 3, "offer": 7, "kind": "files", "items": [{"name": "a.jpg", "size": 10}]});
        assert_eq!(parse(&shared), Said::Shared(shared.clone()));
        assert_eq!(parse(&json!({"t": "cancelled", "share": 3})), Said::Cancelled(3));
        let snap = json!({"t": "photos", "kind": "snapshot", "items": []});
        assert_eq!(parse(&snap), Said::Photos(snap.clone()));
        let older = json!({"t": "photos", "kind": "older", "items": [], "more": false, "ask": 7});
        assert_eq!(parse(&older), Said::Answer(7, older.clone()));
        let thumb = json!({"t": "thumb", "id": 4, "modified": 9, "jpeg": "AA=="});
        assert_eq!(parse(&thumb), Said::Thumb(thumb.clone()));
        assert!(matches!(parse(&json!({"t": "status", "battery": 50, "ask": 8})), Said::Answer(8, _)));
        assert_eq!(parse(&json!({"t": "what"})), Said::Other);
    }

    #[test]
    fn an_answer_goes_only_to_its_own_asking() {
        // By its number, whatever its kind: a failed page is its asker's, not the Photos tab's.
        let failed = json!({"t": "photos", "kind": "failed", "message": "x", "ask": 3});
        assert_eq!(parse(&failed), Said::Answer(3, failed.clone()));
        // Without one it is nobody's: never handed to the next asker of its kind.
        assert_eq!(parse(&json!({"t": "photos", "kind": "all", "items": []})), Said::Other);
        assert_eq!(parse(&json!({"t": "status", "battery": 50})), Said::Other);
        // The look's own failure, never asked for, is still the Photos tab's.
        let look = json!({"t": "photos", "kind": "failed", "message": "x"});
        assert_eq!(parse(&look), Said::Photos(look.clone()));
        // The asker takes what it asked for, or an error: never a page as a status, never a failure as an empty list.
        assert!(answer_of(json!({"t": "photos", "kind": "all", "items": []}), "all").is_ok());
        assert!(answer_of(json!({"t": "status", "now": 5}), "status").is_ok());
        assert_eq!(answer_of(failed, "all").unwrap_err(), "your phone couldn't read its photos");
        assert!(answer_of(json!({"t": "photos", "kind": "older", "items": []}), "all").is_err());
        assert!(answer_of(json!({"t": "status"}), "older").is_err());
    }
}
