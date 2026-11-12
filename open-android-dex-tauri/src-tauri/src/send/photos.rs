//! The phone's newest photos and screenshots, for the Photos tab
//! (doc/send-to-computer.md, "The photos are asked for, not announced").
//!
//! The phone's server looks at its media store once a second while the page
//! looks, and says what is new and what has gone (AppPhotos.java); this keeps
//! each phone's list, newest first, and its thumbnails — made on the phone,
//! held here for this run only, in this app's cache, emptied at start and at
//! exit. Nothing of the phone's pictures stays on this computer unless the
//! person saved one, or turned Keep a copy on. An original is fetched when
//! Open, Save or Copy needs it, into this app's own cache beside the
//! thumbnails, and goes with them — or taken from the kept copy, if there is one.

use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};

use super::link::Link;
use crate::apps::shared;

/// How many of the newest the phone is asked for, and a page of "Show older photos".
pub const LIMIT: u32 = 200;
/// A thumbnail asked for and not come in this long is asked for again.
const THUMB_AGAIN: Duration = Duration::from_secs(15);

/// One picture on the phone, as it describes it.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Photo {
    pub id: u64,
    pub name: String,
    pub album: String,
    /// Its folder and name on the phone: "DCIM/Camera/20261106_114611.jpg".
    pub path: String,
    /// When it was taken, in ms.
    pub taken: i64,
    /// When it appeared on the phone, and when it last changed: seconds.
    pub added: i64,
    pub modified: i64,
    pub size: u64,
    pub w: u32,
    pub h: u32,
    pub mime: String,
}

impl Photo {
    pub fn parse(v: &Value) -> Option<Photo> {
        let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
        Some(Photo {
            id: v["id"].as_u64()?,
            name: s("name"),
            album: s("album"),
            path: s("path"),
            taken: v["taken"].as_i64().unwrap_or(0),
            added: v["added"].as_i64().unwrap_or(0),
            modified: v["modified"].as_i64().unwrap_or(0),
            size: v["size"].as_u64().unwrap_or(0),
            w: v["w"].as_u64().unwrap_or(0) as u32,
            h: v["h"].as_u64().unwrap_or(0) as u32,
            mime: s("mime"),
        })
    }

    pub fn parse_all(v: &Value) -> Vec<Photo> {
        v.as_array().map(|a| a.iter().filter_map(Photo::parse).collect()).unwrap_or_default()
    }
}

/// One phone's list.
#[derive(Default)]
struct Feed {
    /// Newest first: by when each appeared on the phone, then its id.
    items: Vec<Photo>,
    more: bool,
    total: (u64, u64),
    /// `loading`, `ready` or `failed`.
    state: String,
    message: String,
    /// What the page has been given: a new one is logged as it reaches it.
    listed: HashSet<u64>,
    /// Thumbnails asked for and not come yet: when.
    asked: HashMap<u64, Instant>,
    /// Where an original went, once it is on this computer (saved), and the
    /// size and time it had then: a picture edited on the phone keeps its id.
    saved: HashMap<u64, (PathBuf, u64, i64)>,
}

/// Where `p` was saved in this run (`saved`, a feed's), while that copy is of `p` as it is now and is still there.
fn saved_copy(saved: &HashMap<u64, (PathBuf, u64, i64)>, p: &Photo) -> Option<PathBuf> {
    saved.get(&p.id).filter(|(at, size, modified)| *size == p.size && *modified == p.modified && at.exists()).map(|(at, ..)| at.clone())
}

fn feeds() -> MutexGuard<'static, HashMap<String, Feed>> {
    static F: OnceLock<Mutex<HashMap<String, Feed>>> = OnceLock::new();
    F.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

/// Newest first, by when it appeared on the phone, then its id.
fn sort(items: &mut [Photo]) {
    items.sort_by_key(|p| std::cmp::Reverse((p.added, p.id)));
}

/// What the phone's look found, applied to the list: what is new (or changed, by its id) and what has gone.
pub(crate) fn apply(items: &mut Vec<Photo>, new: Vec<Photo>, gone: &[u64]) {
    let gone: HashSet<u64> = gone.iter().copied().collect();
    let fresh: HashSet<u64> = new.iter().map(|p| p.id).collect();
    items.retain(|p| !gone.contains(&p.id) && !fresh.contains(&p.id));
    items.extend(new);
    sort(items);
}

/// Of `items`, those in `ids`: how many, and their bytes — what "Everything on the phone" loses when they go.
pub(crate) fn gone_of(items: &[Photo], ids: &[u64]) -> (u64, u64) {
    let ids: HashSet<u64> = ids.iter().copied().collect();
    items.iter().filter(|p| ids.contains(&p.id)).fold((0, 0), |(n, b), p| (n + 1, b + p.size))
}

/// How many pictures count on `phone`, and how many bytes: what "Everything on the phone" would copy.
pub(crate) fn totals(phone: &str) -> (u64, u64) {
    feeds().get(phone).map(|f| f.total).unwrap_or((0, 0))
}

/// A new link to `phone`: its list comes afresh.
pub(crate) fn link_up(app: &AppHandle, phone: &str) {
    {
        let mut f = feeds();
        let feed = f.entry(phone.to_string()).or_default();
        if feed.state != "ready" {
            feed.state = "loading".into();
        }
        feed.asked.clear();
    }
    let _ = app.emit("photos:changed", json!({"phone": phone}));
}

/// The page looks at `phone`: a list never asked for is loading.
pub(crate) fn page_looks(phone: &str) {
    let mut f = feeds();
    let feed = f.entry(phone.to_string()).or_default();
    if feed.state.is_empty() {
        feed.state = "loading".into();
    }
}

/// "Try again" on the page: a list that failed is loading again, until the fresh snapshot it asks for comes.
pub(crate) fn again(phone: &str) {
    let mut f = feeds();
    let feed = f.entry(phone.to_string()).or_default();
    if feed.state == "failed" {
        feed.state = "loading".into();
        feed.message.clear();
    }
}

/// What the phone's look said: `snapshot`, `new`, `gone` or `failed`.
pub(crate) fn said(app: &AppHandle, link: &Link, v: &Value) {
    let kind = v["kind"].as_str().unwrap_or("");
    let new = Photo::parse_all(&v["items"]);
    {
        let mut f = feeds();
        let feed = f.entry(link.phone.clone()).or_default();
        match kind {
            "snapshot" => {
                let mut items = new.clone();
                sort(&mut items);
                feed.items = items;
                feed.more = v["more"].as_bool().unwrap_or(false);
                feed.total = (v["total"]["count"].as_u64().unwrap_or(0), v["total"]["bytes"].as_u64().unwrap_or(0));
                feed.state = "ready".into();
                feed.message.clear();
            }
            "new" => {
                for p in &new {
                    if !feed.items.iter().any(|x| x.id == p.id) {
                        feed.total.0 += 1;
                        feed.total.1 += p.size;
                    }
                }
                apply(&mut feed.items, new.clone(), &[]);
            }
            "gone" => {
                let ids: Vec<u64> = v["ids"].as_array().map(|a| a.iter().filter_map(|i| i.as_u64()).collect()).unwrap_or_default();
                let (n, bytes) = gone_of(&feed.items, &ids);
                apply(&mut feed.items, Vec::new(), &ids);
                feed.total.0 = feed.total.0.saturating_sub(n);
                feed.total.1 = feed.total.1.saturating_sub(bytes);
            }
            "failed" => {
                log::warn!("photos [{}] the phone's media store would not say: {}", link.serial, v["message"].as_str().unwrap_or(""));
                feed.state = "failed".into();
                feed.message = "Your phone didn't answer.".into();
            }
            _ => return,
        }
    }
    if kind == "new" {
        for p in &new {
            log::info!("photos [{}] {} is new on the phone", link.serial, p.id);
        }
    }
    super::keep::photos_said(app, link, kind, &new);
    let _ = app.emit("photos:changed", json!({"phone": link.phone}));
}

/// A thumbnail from the phone: kept for this run, and to the page.
pub(crate) fn thumb(app: &AppHandle, phone: &str, v: &Value) {
    let Some(id) = v["id"].as_u64() else { return };
    feeds().entry(phone.to_string()).or_default().asked.remove(&id);
    if v["failed"].as_bool() == Some(true) {
        return;
    }
    let modified = v["modified"].as_i64().unwrap_or(0);
    let Some(bytes) = v["jpeg"].as_str().and_then(decode_base64) else { return };
    if let Some(dir) = thumbs_dir(app, phone) {
        if std::fs::create_dir_all(&dir).is_ok() {
            let _ = std::fs::write(dir.join(thumb_name(id, modified)), &bytes);
        }
    }
    let _ = app.emit("photos:thumb", json!({"phone": phone, "id": id, "url": data_url(&bytes)}));
}

/// What a thumbnail is called: by what it is of, so a picture edited on the phone is a new one.
pub(crate) fn thumb_name(id: u64, modified: i64) -> String {
    format!("{id}-{modified}.jpg")
}

fn thumbs_dir(app: &AppHandle, phone: &str) -> Option<PathBuf> {
    let safe: String = phone.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    Some(app.path().app_cache_dir().ok()?.join("send").join("thumbs").join(safe))
}

/// Every thumbnail and preview of the phone's pictures this app holds, gone: at start and at exit.
pub(crate) fn forget_all(app: &AppHandle) {
    if let Ok(d) = app.path().app_cache_dir() {
        let _ = std::fs::remove_dir_all(d.join("send"));
    }
}

fn data_url(jpeg: &[u8]) -> String {
    format!("data:image/jpeg;base64,{}", encode_base64(jpeg))
}

fn encode_base64(b: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(b.len().div_ceil(3) * 4);
    for chunk in b.chunks(3) {
        let n = (chunk[0] as u32) << 16 | (*chunk.get(1).unwrap_or(&0) as u32) << 8 | *chunk.get(2).unwrap_or(&0) as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

/// Standard base64, as the phone's `Base64.NO_WRAP` writes it.
pub(crate) fn decode_base64(s: &str) -> Option<Vec<u8>> {
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

// ── the day headings ──

const DAYS: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const MONTHS: [&str; 12] =
    ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/// The heading a picture that appeared at `at` (seconds) sits under, seen at
/// `now`, `offset` seconds east of UTC: Today, Yesterday, else "Monday 9
/// November" — with the year when it is not this one.
pub(crate) fn day_heading(at: i64, now: i64, offset: i32) -> String {
    let date = |t: i64| {
        let o = time::UtcOffset::from_whole_seconds(offset).unwrap_or(time::UtcOffset::UTC);
        time::OffsetDateTime::from_unix_timestamp(t).map(|d| d.to_offset(o).date()).ok()
    };
    let (Some(d), Some(today)) = (date(at), date(now)) else { return String::new() };
    if d == today {
        return "Today".into();
    }
    if today.previous_day() == Some(d) {
        return "Yesterday".into();
    }
    let day = DAYS[d.weekday().number_days_from_monday() as usize];
    let month = MONTHS[d.month() as usize - 1];
    if d.year() == today.year() {
        format!("{day} {} {month}", d.day())
    } else {
        format!("{day} {} {month} {}", d.day(), d.year())
    }
}

// ── what the page asks ──

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoView {
    #[serde(flatten)]
    pub photo: Photo,
    pub day: String,
    /// It is on this computer already: kept, or saved — "Show in Finder" shows it.
    pub here: bool,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotosView {
    /// `none` (no link yet), `loading`, `ready` or `failed`.
    pub state: String,
    pub message: String,
    pub items: Vec<PhotoView>,
    pub more: bool,
    pub count: u64,
    pub bytes: u64,
    /// "193 photos, 612 MB".
    pub everything: String,
}

fn view(app: &AppHandle, phone: &str, serial: &str, tz: i32) -> PhotosView {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let linked = super::link_of(phone).is_some();
    let mut f = feeds();
    let feed = f.entry(phone.to_string()).or_default();
    let refused = !linked && super::refused_of(phone);
    let state = if refused && feed.state != "ready" {
        "failed".to_string()
    } else if feed.state.is_empty() || (!linked && feed.state != "ready") {
        "loading".to_string()
    } else {
        feed.state.clone()
    };
    let mut reached = Vec::new();
    let items: Vec<PhotoView> = feed
        .items
        .iter()
        .map(|p| {
            if feed.listed.insert(p.id) && state == "ready" {
                reached.push(p.id);
            }
            let here = saved_copy(&feed.saved, p).is_some() || super::keep::kept_path(phone, p).is_some();
            PhotoView { photo: p.clone(), day: day_heading(p.added, now, tz), here }
        })
        .collect();
    let view = PhotosView {
        state,
        message: feed.message.clone(),
        items,
        more: feed.more,
        count: feed.total.0,
        bytes: feed.total.1,
        everything: super::words::everything(feed.total.0, feed.total.1),
    };
    drop(f);
    // The first list is the page's whole picture; after it, each one new to it is logged as it reaches it.
    if reached.len() <= 20 {
        for id in reached {
            log::info!("photos [{serial}] {id} reached the page");
        }
    }
    let _ = app;
    view
}

/// `serial`'s phone's photos, as the page shows them; `tz`, the page's offset from UTC in minutes east.
#[tauri::command(async)]
pub fn photos_list(app: AppHandle, serial: String, tz: Option<i32>) -> PhotosView {
    let (phone, _) = super::phone_of(&app, &serial);
    view(&app, &phone, &serial, tz.unwrap_or(0) * 60)
}

/// "Show older photos": the next page, below the oldest the page has.
#[tauri::command(async)]
pub fn photos_older(app: AppHandle, serial: String, tz: Option<i32>) -> Result<PhotosView, String> {
    let (phone, _) = super::phone_of(&app, &serial);
    let l = super::link_of(&phone).ok_or("Your phone isn't connected.")?;
    let oldest = feeds().get(&phone).and_then(|f| f.items.last().map(|p| (p.added, p.id)));
    let ask = match oldest {
        Some((added, id)) => json!({"t": "older", "before": {"added": added, "id": id}, "limit": LIMIT}),
        None => json!({"t": "older", "limit": LIMIT}),
    };
    let v = l.ask(&ask, "older", Duration::from_secs(20))?;
    {
        let mut f = feeds();
        let feed = f.entry(phone.clone()).or_default();
        let older = Photo::parse_all(&v["items"]);
        apply(&mut feed.items, older, &[]);
        feed.more = v["more"].as_bool().unwrap_or(false);
    }
    Ok(view(&app, &phone, &serial, tz.unwrap_or(0) * 60))
}

/// Thumbnails for `ids`: those this computer has, now; the rest asked of the phone, and sent as they come (`photos:thumb`).
#[tauri::command(async)]
pub fn photos_thumb(app: AppHandle, serial: String, ids: Vec<u64>) -> HashMap<String, String> {
    let (phone, _) = super::phone_of(&app, &serial);
    let dir = thumbs_dir(&app, &phone);
    let mut have = HashMap::new();
    let mut ask = Vec::new();
    {
        let mut f = feeds();
        let feed = f.entry(phone.clone()).or_default();
        for id in ids {
            let Some(p) = feed.items.iter().find(|p| p.id == id) else { continue };
            let file = dir.as_ref().map(|d| d.join(thumb_name(id, p.modified)));
            if let Some(bytes) = file.and_then(|f| std::fs::read(f).ok()) {
                have.insert(id.to_string(), data_url(&bytes));
                continue;
            }
            if feed.asked.get(&id).is_some_and(|at| at.elapsed() < THUMB_AGAIN) {
                continue;
            }
            feed.asked.insert(id, Instant::now());
            ask.push(id);
        }
    }
    if !ask.is_empty() {
        if let Some(l) = super::link_of(&phone) {
            let _ = l.send(&json!({"t": "thumbs", "ids": ask}));
        }
    }
    have
}

/// Open, Save to Downloads, Copy, Show in Finder — for one photo, by its id.
#[tauri::command(async)]
pub fn photos_act(app: AppHandle, serial: String, id: u64, act: String) -> Result<String, String> {
    use tauri_plugin_opener::OpenerExt;
    let (phone, _) = super::phone_of(&app, &serial);
    let photo = feeds().get(&phone).and_then(|f| f.items.iter().find(|p| p.id == id).cloned()).ok_or("That photo isn't in the list any more.")?;
    match act.as_str() {
        "show" => {
            let here = feeds().get(&phone).and_then(|f| saved_copy(&f.saved, &photo))
                .or_else(|| super::keep::kept_path(&phone, &photo))
                .ok_or(format!("It isn't on {} yet.", super::words::this_computer()))?;
            tauri_plugin_opener::reveal_item_in_dir(&here).map_err(|e| e.to_string())?;
            Ok(String::new())
        }
        "open" => {
            let (path, _) = original(&app, &phone, &photo)?;
            app.opener().open_path(path.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())?;
            Ok(String::new())
        }
        "save" => {
            let (from, fetched) = original(&app, &phone, &photo)?;
            let dir = app.path().download_dir().ok().filter(|d| d.is_dir()).or_else(shared::downloads).ok_or("There is no Downloads folder here.")?;
            let r = super::inbox::reserve(&dir, &picture_name(&photo)).map_err(|e| e.to_string())?;
            // Into the part the reservation made, never a path opened again.
            let copied = std::fs::File::open(&from).and_then(|mut f| {
                let mut out = r.file();
                std::io::copy(&mut f, &mut out)
            });
            // One fetched for this alone is done with once copied.
            if fetched {
                let _ = std::fs::remove_file(&from);
            }
            if let Err(e) = copied {
                r.abandon();
                return Err(format!("It couldn't be saved ({e})."));
            }
            let to = r.land().map_err(|e| e.to_string())?;
            super::inbox::mark_downloaded(&to);
            let name = to.file_name().unwrap_or_default().to_string_lossy().into_owned();
            feeds().entry(phone.clone()).or_default().saved.insert(id, (to, photo.size, photo.modified));
            let _ = app.emit("photos:changed", json!({"phone": phone}));
            Ok(format!("Saved {} to Downloads", shared::short(&name)))
        }
        "copy" => {
            #[cfg(any(windows, target_os = "macos"))]
            {
                let (path, _) = original(&app, &phone, &photo)?;
                shared::set_files(&[path])?;
                Ok("Copied. Paste it anywhere.".into())
            }
            #[cfg(not(any(windows, target_os = "macos")))]
            Err("Can't copy photos on this computer.".into())
        }
        _ => Err(format!("no such action: {act}")),
    }
}

/// The original of `p`, on this computer: the kept copy, or fetched now into
/// this app's own cache ([`originals_in`]) — then true, a file of this app's.
fn original(app: &AppHandle, phone: &str, p: &Photo) -> Result<(PathBuf, bool), String> {
    if let Some(kept) = super::keep::kept_path(phone, p) {
        return Ok((kept, false));
    }
    if let Some(saved) = feeds().get(phone).and_then(|f| saved_copy(&f.saved, p)) {
        return Ok((saved, false));
    }
    let l = super::link_of(phone).ok_or("Your phone isn't connected.")?;
    let cache = app.path().app_cache_dir().map_err(|e| e.to_string())?;
    let dir = originals_in(&cache);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    // Reserved as an arrival is, so nothing already there is opened or written over.
    let r = super::inbox::reserve(&dir, &picture_name(p)).map_err(|e| e.to_string())?;
    match fetch(&l, p, r.file(), |_| true) {
        Ok(_) => r.land().map(|path| (path, true)).map_err(|e| e.to_string()),
        Err(e) => {
            r.abandon();
            Err(e)
        }
    }
}

/// A folder of its own for one original fetched for Open, Save or Copy, in
/// this app's own cache — never the shared temporary folder — under `send`, so
/// it goes with the thumbnails, at exit and at the next start ([`forget_all`]).
/// Open's viewer and Copy's paste have it for as long as this app runs.
pub(crate) fn originals_in(cache: &std::path::Path) -> PathBuf {
    static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    cache.join("send").join("originals").join(SEQ.fetch_add(1, std::sync::atomic::Ordering::SeqCst).to_string())
}

/// `p`'s bytes into `out` — a file its caller made for them — checked against
/// what the phone said of it, and flushed to the disk; `going` hears the bytes
/// so far and may stop it (false). The bytes' SHA-256, for Keep a copy's record.
pub(crate) fn fetch(l: &Link, p: &Photo, out: &std::fs::File, mut going: impl FnMut(u64) -> bool) -> Result<String, String> {
    let mut out = out;
    use sha2::{Digest, Sha256};
    let mut s = shared::connect(&l.serial, &l.socket).map_err(|_| "Your phone isn't answering.".to_string())?;
    s.write_all(&shared::json(&json!({"t": "photo", "id": p.id, "size": p.size, "modified": p.modified, "offset": 0})))
        .map_err(|e| e.to_string())?;
    let _ = s.set_read_timeout(Some(Duration::from_secs(30)));
    let answer = match shared::read(&mut s) {
        Ok(Some(m)) if m.kind == shared::JSON => serde_json::from_slice::<Value>(&m.data).unwrap_or(Value::Null),
        _ => return Err("Your phone didn't answer.".into()),
    };
    if answer["ok"].as_bool() != Some(true) {
        if answer["why"].as_str() == Some("changed") || answer["why"].as_str() == Some("gone") {
            // The list is asked for again: what the page shows is not what the phone has.
            let every = l.photos_every();
            l.want_photos(None);
            l.want_photos(every);
        }
        return Err(match answer["why"].as_str() {
            Some("gone") => "gone".into(),
            Some("changed") => "changed".into(),
            _ => "unreadable".into(),
        });
    }
    let mut hash = Sha256::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut got = 0u64;
    let result: Result<(), String> = loop {
        match s.read(&mut buf) {
            Ok(0) => break Ok(()),
            Ok(n) => {
                if let Err(e) = out.write_all(&buf[..n]) {
                    break Err(if is_disk_full(&e) { "space".into() } else { e.to_string() });
                }
                hash.update(&buf[..n]);
                got += n as u64;
                if !going(got) {
                    break Err("stopped".into());
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => break Err("lost".into()),
        }
    };
    // On the disk before it is renamed: a copy the record names is never an empty file after a power cut.
    // Shorter than the phone said, its link still up: the file is (the media store's size was old), so it
    // counts as unreadable — Keep a copy skips it for the pass rather than stopping at it every time.
    result
        .and_then(|()| if got < p.size { Err(if l.alive() { "unreadable".into() } else { "lost".into() }) } else { Ok(()) })
        .and_then(|()| out.sync_all().map_err(|e| if is_disk_full(&e) { "space".into() } else { e.to_string() }))?;
    Ok(format!("{:x}", hash.finalize()))
}

/// What a photo is called on this computer: its name made safe, ending in a
/// picture's extension — its own, or one for its type — so that Open hands
/// the system a picture, whatever the phone's list called it.
pub(crate) fn picture_name(p: &Photo) -> String {
    let name = shared::arrival_name(&p.name);
    let ext = std::path::Path::new(&name).extension().map(|e| e.to_string_lossy().into_owned()).unwrap_or_default();
    if super::inbox::is_picture_ext(&ext) {
        return name;
    }
    let of_type = match p.mime.as_str() {
        "image/png" => "png",
        "image/gif" => "gif",
        "image/webp" => "webp",
        "image/heic" => "heic",
        "image/heif" => "heif",
        "image/bmp" => "bmp",
        "image/avif" => "avif",
        _ => "jpg",
    };
    format!("{name}.{of_type}")
}

/// A write that failed for want of room: ENOSPC, or Windows' ERROR_DISK_FULL and ERROR_HANDLE_DISK_FULL.
pub(crate) fn is_disk_full(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::StorageFull || matches!(e.raw_os_error(), Some(28) if cfg!(unix)) || matches!(e.raw_os_error(), Some(112 | 39) if cfg!(windows))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(id: u64, added: i64, name: &str) -> Photo {
        Photo {
            id,
            name: name.into(),
            album: "Camera".into(),
            path: format!("DCIM/Camera/{name}"),
            taken: added * 1000,
            added,
            modified: added,
            size: 10,
            w: 4,
            h: 3,
            mime: "image/jpeg".into(),
        }
    }

    #[test]
    fn an_original_is_fetched_into_this_app_s_cache_and_goes_with_the_thumbnails() {
        let cache = std::path::Path::new("/Users/x/Library/Caches/app");
        let (a, b) = (originals_in(cache), originals_in(cache));
        // Under `send`, which forget_all empties at exit and at the next start; a folder each, so a name is its own.
        assert!(a.starts_with(cache.join("send")) && b.starts_with(cache.join("send")), "{a:?}");
        assert_ne!(a, b);
    }

    #[test]
    fn try_again_makes_a_failed_list_load_again_and_nothing_else() {
        let phone = "oadx-test-try-again";
        {
            let mut f = feeds();
            let feed = f.entry(phone.to_string()).or_default();
            feed.state = "failed".into();
            feed.message = "Your phone didn't answer.".into();
        }
        again(phone);
        {
            let f = feeds();
            assert_eq!((f[phone].state.as_str(), f[phone].message.as_str()), ("loading", ""));
        }
        feeds().get_mut(phone).unwrap().state = "ready".into();
        again(phone);
        let state = feeds()[phone].state.clone();
        assert_eq!(state, "ready", "a list that is there stays");
        feeds().remove(phone);
    }

    #[test]
    fn a_copy_saved_before_an_edit_on_the_phone_is_not_the_picture_now() {
        let d = std::env::temp_dir().join(format!("oadx-photos-saved-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let at = d.join("shot.png");
        std::fs::write(&at, b"before the crop").unwrap();
        let shot = photo(7, 100, "shot.png");
        let mut feed = Feed::default();
        feed.saved.insert(shot.id, (at.clone(), shot.size, shot.modified));
        assert_eq!(saved_copy(&feed.saved, &shot), Some(at.clone()));
        // Cropped on the phone: the same id, sent as new again with its new size and time.
        let cropped = Photo { size: 7, modified: 160, ..shot.clone() };
        assert_eq!(saved_copy(&feed.saved, &cropped), None, "fetched afresh, not the uncropped copy");
        assert_eq!(saved_copy(&feed.saved, &Photo { modified: 160, ..shot.clone() }), None);
        // The copy gone from Downloads: fetched afresh too.
        std::fs::remove_file(&at).unwrap();
        assert_eq!(saved_copy(&feed.saved, &shot), None);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn new_and_gone_are_the_difference_of_two_looks() {
        let mut items = vec![photo(1, 100, "a.jpg"), photo(2, 200, "b.jpg"), photo(3, 300, "c.jpg")];
        sort(&mut items);
        assert_eq!(items.iter().map(|p| p.id).collect::<Vec<_>>(), [3, 2, 1]);
        // A new one, one gone, one renamed on the phone (the same id, sent as new again).
        apply(&mut items, vec![photo(4, 400, "d.jpg"), photo(2, 200, "renamed.jpg")], &[1]);
        assert_eq!(items.iter().map(|p| p.id).collect::<Vec<_>>(), [4, 3, 2]);
        assert_eq!(items[2].name, "renamed.jpg");
        // Same second: the larger id is the newer.
        apply(&mut items, vec![photo(5, 400, "e.jpg")], &[]);
        assert_eq!(items.iter().map(|p| p.id).collect::<Vec<_>>(), [5, 4, 3, 2]);
        let parsed = Photo::parse_all(&json!([{"id": 9, "name": "x.jpg", "added": 1, "size": 5}, {"no": "id"}]));
        assert_eq!(parsed.len(), 1);
        assert_eq!((parsed[0].id, parsed[0].size), (9, 5));
    }

    #[test]
    fn a_photo_is_always_opened_as_a_picture() {
        let mut p = photo(1, 1, "IMG_1.JPG");
        assert_eq!(picture_name(&p), "IMG_1.JPG");
        p.name = "x.command".into();
        assert_eq!(picture_name(&p), "x.command.jpg");
        p.mime = "image/png".into();
        p.name = "shot".into();
        assert_eq!(picture_name(&p), "shot.png");
        p.name = ".hidden.heic".into();
        assert_eq!(picture_name(&p), "hidden.heic");
    }

    #[test]
    fn what_goes_takes_its_bytes_with_it() {
        let mut items = vec![photo(1, 100, "a.jpg"), photo(2, 200, "b.jpg"), photo(3, 300, "c.jpg")];
        items[0].size = 1000;
        items[2].size = 5;
        assert_eq!(gone_of(&items, &[1, 3, 99]), (2, 1005));
        assert_eq!(gone_of(&items, &[]), (0, 0));
    }

    #[test]
    fn a_thumbnail_is_named_by_what_it_is_of() {
        assert_eq!(thumb_name(4938, 1794152371), "4938-1794152371.jpg");
        assert_ne!(thumb_name(4938, 1794152371), thumb_name(4938, 1794152372));
    }

    #[test]
    fn the_day_headings() {
        // Wednesday 11 November 2026, 23:00 UTC.
        let now = 1_794_438_000;
        assert_eq!(day_heading(now - 60, now, 0), "Today");
        assert_eq!(day_heading(now - 86_400, now, 0), "Yesterday");
        assert_eq!(day_heading(now - 2 * 86_400, now, 0), "Monday 9 November");
        assert_eq!(day_heading(now - 400 * 86_400, now, 0), "Tuesday 7 October 2025");
        // An hour east of UTC it is already Thursday: what was "today" in UTC is yesterday there.
        assert_eq!(day_heading(now - 60, now, 3600), "Yesterday");
        assert_eq!(day_heading(now, now, 3600), "Today");
        assert_eq!(day_heading(now - 3 * 86_400, now, 3600), "Monday 9 November");
    }

    #[test]
    fn base64_both_ways() {
        for s in [&b""[..], b"a", b"ab", b"abc", b"\xff\xd8\xff\xe0 jpeg"] {
            if s.is_empty() {
                continue;
            }
            assert_eq!(decode_base64(&encode_base64(s)).unwrap(), s);
        }
        assert_eq!(encode_base64(b"abc"), "YWJj");
        assert_eq!(encode_base64(b"ab"), "YWI=");
    }
}
