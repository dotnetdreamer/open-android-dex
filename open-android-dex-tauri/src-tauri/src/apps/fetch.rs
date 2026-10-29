//! What a phone hands this computer (doc/apps-on-pc.md, "Dragging out" and
//! "The clipboard"): the items of a drag carried out of one of its windows,
//! or what it copied when that is a picture or a file.
//!
//! The phone offers them by number (AppOffers.java) and this computer takes
//! each file on a connection of its own, through adb as a window's is, as a
//! dropped file goes the other way (drops.rs): `{"t":"get","id","i"}`, the
//! phone's answer with the file's name and size, then its bytes, raw, until
//! the connection closes. They land in a folder of this app's own under the
//! computer's temporary folder, one per offer, and go from there to wherever
//! they were dropped or pasted.

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime};

use serde_json::{json, Value};

use super::link::Route;
use super::{adbsock, wire};

/// How long the phone may go quiet in the middle of a file: a big one read
/// from a slow provider, over Wi‑Fi.
const READ_TIMEOUT: Duration = Duration::from_secs(30);
/// A file this quick to come needs no note saying how far it is.
const QUIET_FOR: Duration = Duration::from_millis(400);
/// What is left of other runs' and earlier offers' files is swept once it is this old.
const SWEEP_AFTER: Duration = Duration::from_secs(2 * 24 * 3600);
/// A name's most, in bytes: what every filesystem here takes, with room for " (99)".
const NAME_MAX: usize = 200;

/// One thing the phone offers: a file, by its name, type and size, or text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item {
    pub name: String,
    pub mime: String,
    pub size: Option<u64>,
    pub text: Option<String>,
    /// A file to take, rather than text alone.
    pub file: bool,
}

/// What the phone offers, by the number it took them by.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Offer {
    pub id: u64,
    pub items: Vec<Item>,
}

impl Offer {
    /// The phone's `dragged` or `clipFiles`: None for one that carries nothing.
    pub fn parse(v: &Value) -> Option<Offer> {
        let id = v["id"].as_u64()?;
        let items: Vec<Item> = v["items"]
            .as_array()?
            .iter()
            .map(|i| {
                let file = i["name"].is_string();
                Item {
                    name: i["name"].as_str().unwrap_or("").to_string(),
                    mime: i["mime"].as_str().unwrap_or("").to_string(),
                    size: i["size"].as_i64().and_then(|s| u64::try_from(s).ok()),
                    text: i["text"].as_str().map(str::to_string),
                    file,
                }
            })
            .filter(|i| i.file || i.text.is_some())
            .collect();
        (!items.is_empty()).then_some(Offer { id, items })
    }

    pub fn files(&self) -> impl Iterator<Item = (usize, &Item)> {
        self.items.iter().enumerate().filter(|(_, i)| i.file)
    }
}

/// A note in a window: what a taking says while it goes (link.rs, `Surface::note`).
pub type Note = Arc<dyn Fn(Option<String>, bool) + Send + Sync>;

/// This computer, as the windows' notes call it.
pub fn this_computer() -> &'static str {
    if cfg!(target_os = "macos") {
        "this Mac"
    } else {
        "this PC"
    }
}

/// An offer's files on their way here, one after another, on a thread of
/// their own — taken as soon as they are offered, so that by the time the
/// drag is dropped or the paste made, they are usually here already.
pub struct Taking {
    files: Mutex<Vec<Option<Result<PathBuf, String>>>>,
    landed: Condvar,
}

impl Taking {
    /// Start taking `offer`'s files from the phone over `route`, into a
    /// folder of their own; `note`, if any, says how far they are when that
    /// takes a while.
    pub fn start(route: Route, offer: &Offer, kind: &str, note: Option<Note>) -> Arc<Taking> {
        // Text is no file to take: only what is said of it.
        let slots = offer.items.iter().map(|i| if i.file { None } else { Some(Err(NOT_A_FILE.to_string())) }).collect();
        let taking = Arc::new(Taking { files: Mutex::new(slots), landed: Condvar::new() });
        let (offer, kind, t) = (offer.clone(), kind.to_string(), taking.clone());
        let _ = std::thread::Builder::new().name("apps-take".into()).spawn(move || t.run(route, offer, &kind, note));
        taking
    }

    fn run(&self, route: Route, offer: Offer, kind: &str, note: Option<Note>) {
        let dir = match folder(kind, offer.id) {
            Ok(d) => d,
            Err(e) => {
                let why = format!("{} has no room for it ({e})", this_computer());
                for (i, _) in offer.files() {
                    self.land(i, Err(why.clone()));
                }
                return;
            }
        };
        let total = offer.files().count();
        let started = Instant::now();
        let mut noted = false;
        for (n, (i, item)) in offer.files().enumerate() {
            let mut shown = -1i64;
            let got = get(&route, offer.id, i, &dir, |bytes, size| {
                let pct = match size {
                    Some(0) | None => -1,
                    Some(s) => (bytes.min(s) * 100 / s) as i64,
                };
                if pct != shown && started.elapsed() >= QUIET_FOR {
                    shown = pct;
                    if let Some(note) = &note {
                        noted = true;
                        note(Some(progress(&item.name, n, total, pct)), true);
                    }
                }
            });
            if let Err(e) = &got {
                log::warn!("apps [{}] taking {} from the phone: {e}", route.serial, item.name);
            }
            self.land(i, got);
        }
        if noted {
            if let Some(note) = &note {
                note(None, false);
            }
        }
    }

    fn land(&self, i: usize, r: Result<PathBuf, String>) {
        self.files.lock().unwrap()[i] = Some(r);
        self.landed.notify_all();
    }

    /// Item `i`'s file here, once it has come — or why it did not — waiting up to `timeout`.
    pub fn wait(&self, i: usize, timeout: Duration) -> Result<PathBuf, String> {
        let until = Instant::now() + timeout;
        let mut files = self.files.lock().unwrap();
        loop {
            match files.get(i) {
                None => return Err("there is no such file".into()),
                Some(Some(r)) => return r.clone(),
                Some(None) => {}
            }
            let left = until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err("your phone is taking too long".into());
            }
            files = self.landed.wait_timeout(files, left).unwrap().0;
        }
    }
}

/// What a text item's slot holds: there was no file to take.
const NOT_A_FILE: &str = "not a file";

/// What a window says while files come from the phone.
fn progress(name: &str, n: usize, total: usize, pct: i64) -> String {
    let pct = if pct >= 0 { format!(" {pct}%") } else { String::new() };
    if total == 1 {
        format!("Copying {} from your phone…{pct}", short(name))
    } else {
        format!("Copying {} of {total} from your phone…{pct}", n + 1)
    }
}

/// A name short enough to leave room for the rest of a note: its start and its end.
pub fn short(name: &str) -> String {
    const MOST: usize = 36;
    let chars: Vec<char> = name.chars().collect();
    if chars.len() <= MOST {
        return name.to_string();
    }
    let head: String = chars[..20].iter().collect();
    let tail: String = chars[chars.len() - 14..].iter().collect();
    format!("{head}…{tail}")
}

/// One file from the phone into `dir`, under a name safe here and free
/// there: where it landed. `progress` hears the bytes so far and how many
/// there are, when the phone knows.
pub fn get(route: &Route, id: u64, i: usize, dir: &Path, mut progress: impl FnMut(u64, Option<u64>)) -> Result<PathBuf, String> {
    let mut s = adbsock::connect(&route.serial, &route.socket).map_err(|_| "your phone isn't answering".to_string())?;
    s.write_all(&wire::json(&json!({"t": "get", "id": id, "i": i}))).map_err(|e| e.to_string())?;
    let _ = s.set_read_timeout(Some(READ_TIMEOUT));
    let answer = match wire::read(&mut s) {
        Ok(Some(m)) if m.kind == wire::JSON => serde_json::from_slice::<Value>(&m.data).unwrap_or(Value::Null),
        _ => return Err("your phone didn't answer".into()),
    };
    if answer["ok"].as_bool() != Some(true) {
        return Err(answer["message"].as_str().unwrap_or("your phone couldn't send it").to_string());
    }
    let size = answer["size"].as_i64().and_then(|s| u64::try_from(s).ok());
    let path = free_name(&dir.join(safe_name(answer["name"].as_str().unwrap_or("file"))));
    let mut part_name = path.file_name().unwrap_or_default().to_os_string();
    part_name.push(".part");
    let part = path.with_file_name(part_name);
    let mut out = File::create(&part).map_err(|e| format!("{} couldn't save it ({e})", this_computer()))?;
    let mut buf = vec![0u8; 256 * 1024];
    let mut got = 0u64;
    let copied = loop {
        match s.read(&mut buf) {
            Ok(0) => break Ok(()),
            Ok(n) => {
                if let Err(e) = out.write_all(&buf[..n]) {
                    break Err(format!("{} couldn't save it ({e})", this_computer()));
                }
                got += n as u64;
                progress(got, size);
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => break Err("your phone stopped sending it".into()),
        }
    };
    drop(out);
    let whole = copied.and_then(|()| match size {
        Some(s) if got < s => Err("your phone stopped sending it".into()),
        _ => Ok(()),
    });
    if let Err(e) = whole {
        let _ = std::fs::remove_file(&part);
        return Err(e);
    }
    std::fs::rename(&part, &path).map_err(|e| {
        let _ = std::fs::remove_file(&part);
        format!("{} couldn't save it ({e})", this_computer())
    })?;
    Ok(path)
}

/// A folder of its own for one offer's files, under this computer's
/// temporary folder; what is left of old ones is swept the first time.
pub fn folder(kind: &str, id: u64) -> std::io::Result<PathBuf> {
    static SWEPT: std::sync::Once = std::sync::Once::new();
    static SEQ: AtomicU64 = AtomicU64::new(0);
    let root = std::env::temp_dir().join("Open Android DeX");
    SWEPT.call_once(|| sweep(&root));
    let dir = root.join(format!("{kind}-{}-{id}-{}", std::process::id(), SEQ.fetch_add(1, Ordering::SeqCst)));
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

fn sweep(root: &Path) {
    let Ok(entries) = std::fs::read_dir(root) else { return };
    let now = SystemTime::now();
    for e in entries.flatten() {
        let old = e
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|m| now.duration_since(m).ok())
            .is_some_and(|age| age > SWEEP_AFTER);
        if old {
            let _ = std::fs::remove_dir_all(e.path());
        }
    }
}

/// `wanted`, or "name (2).ext" beside it if that is taken.
pub fn free_name(wanted: &Path) -> PathBuf {
    if !wanted.exists() {
        return wanted.to_path_buf();
    }
    let stem = wanted.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let ext = wanted.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    for n in 2..1000 {
        let candidate = wanted.with_file_name(format!("{stem} ({n}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    wanted.with_file_name(format!("{stem} ({}){ext}", std::process::id()))
}

/// A name from the phone, as a file name here: no folders, nothing this
/// computer's filesystem refuses, not empty, not too long — the extension kept.
pub fn safe_name(name: &str) -> String {
    let last = name.rsplit(['/', '\\']).next().unwrap_or("");
    let mut cleaned: String = last
        .chars()
        .map(|c| if c.is_control() || "<>:\"|?*".contains(c) { '_' } else { c })
        .collect();
    // Windows drops trailing dots and spaces, and a file may not be named for a device.
    cleaned = cleaned.trim().trim_end_matches('.').trim().to_string();
    let stem_upper = cleaned.split('.').next().unwrap_or("").to_ascii_uppercase();
    let device = matches!(stem_upper.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem_upper.starts_with("COM") || stem_upper.starts_with("LPT"))
            && stem_upper.len() == 4
            && stem_upper.as_bytes()[3].is_ascii_digit());
    if device {
        cleaned = format!("_{cleaned}");
    }
    if cleaned.is_empty() || cleaned.starts_with('.') && cleaned.chars().all(|c| c == '.') {
        return "file".into();
    }
    if cleaned.len() <= NAME_MAX {
        return cleaned;
    }
    let dot = cleaned.rfind('.').filter(|&d| d > 0 && cleaned.len() - d <= 16);
    let ext = dot.map(|d| cleaned[d..].to_string()).unwrap_or_default();
    let mut stem = cleaned[..cleaned.len() - ext.len()].to_string();
    while stem.len() + ext.len() > NAME_MAX {
        stem.pop();
    }
    format!("{stem}{ext}")
}

/// This computer's Downloads folder, where a drag let go of before it could
/// become this computer's own goes instead.
pub fn downloads() -> Option<PathBuf> {
    #[cfg(windows)]
    let home = std::env::var_os("USERPROFILE");
    #[cfg(not(windows))]
    let home = std::env::var_os("HOME");
    let d = PathBuf::from(home?).join("Downloads");
    d.is_dir().then_some(d)
}

/// The files of `taking` moved into Downloads: the note that says where they went, or why not.
pub fn into_downloads(taking: &Taking, offer: &Offer) -> String {
    let Some(downloads) = downloads() else {
        return format!("There is no Downloads folder on {} to put it in", this_computer());
    };
    let mut moved = Vec::new();
    let mut failed = None;
    for (i, item) in offer.files() {
        match taking.wait(i, Duration::from_secs(600)) {
            Ok(from) => {
                let to = free_name(&downloads.join(from.file_name().unwrap_or_default()));
                match std::fs::rename(&from, &to).or_else(|_| std::fs::copy(&from, &to).map(|_| ())) {
                    Ok(()) => moved.push(to),
                    Err(e) => failed = Some(format!("{} couldn't be saved ({e})", short(&item.name))),
                }
            }
            Err(e) => failed = Some(format!("{} couldn't be copied: {e}", short(&item.name))),
        }
    }
    match (moved.len(), failed) {
        (0, Some(why)) => why,
        (0, None) => "There was nothing to save".into(),
        (1, None) => format!(
            "{} is in your Downloads folder",
            short(&moved[0].file_name().unwrap_or_default().to_string_lossy())
        ),
        (n, None) => format!("{n} files are in your Downloads folder"),
        (n, Some(_)) => format!("{n} files are in your Downloads folder; the rest couldn't be copied"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_offer_is_its_files_and_its_text() {
        let v = json!({"t": "dragged", "id": 7, "items": [
            {"name": "IMG_1.jpg", "mime": "image/jpeg", "size": 2048},
            {"text": "https://example.com"},
            {"name": "notes.txt", "mime": "text/plain", "size": -1, "text": "hi"},
            {}
        ]});
        let o = Offer::parse(&v).unwrap();
        assert_eq!(o.id, 7);
        assert_eq!(o.items.len(), 3);
        assert_eq!((o.items[0].file, o.items[0].size), (true, Some(2048)));
        assert_eq!((o.items[1].file, o.items[1].text.as_deref()), (false, Some("https://example.com")));
        assert_eq!((o.items[2].file, o.items[2].size), (true, None));
        assert_eq!(o.files().map(|(i, _)| i).collect::<Vec<_>>(), [0, 2]);
        assert_eq!(Offer::parse(&json!({"t": "dragged", "none": true})), None);
        assert_eq!(Offer::parse(&json!({"t": "dragged", "id": 1, "items": [{}]})), None);
    }

    #[test]
    fn a_phones_name_is_a_safe_name_here() {
        assert_eq!(safe_name("IMG_20261029.jpg"), "IMG_20261029.jpg");
        assert_eq!(safe_name("../../etc/passwd"), "passwd");
        assert_eq!(safe_name("a\\b/c:d?.png"), "c_d_.png");
        assert_eq!(safe_name("con.txt"), "_con.txt");
        assert_eq!(safe_name("COM1"), "_COM1");
        assert_eq!(safe_name("report. "), "report");
        assert_eq!(safe_name(""), "file");
        assert_eq!(safe_name(".."), "file");
        let long = format!("{}.jpeg", "x".repeat(400));
        let s = safe_name(&long);
        assert!(s.len() <= NAME_MAX && s.ends_with(".jpeg"), "{s}");
    }

    #[test]
    fn a_name_taken_gets_a_number() {
        let d = std::env::temp_dir().join(format!("oadx-fetch-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        assert_eq!(free_name(&d.join("a.jpg")), d.join("a.jpg"));
        std::fs::write(d.join("a.jpg"), b"x").unwrap();
        assert_eq!(free_name(&d.join("a.jpg")), d.join("a (2).jpg"));
        std::fs::write(d.join("a (2).jpg"), b"x").unwrap();
        assert_eq!(free_name(&d.join("a.jpg")), d.join("a (3).jpg"));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_note_says_how_far() {
        assert_eq!(progress("a.mp4", 0, 1, 42), "Copying a.mp4 from your phone… 42%");
        assert_eq!(progress("a.mp4", 1, 3, 7), "Copying 2 of 3 from your phone… 7%");
        assert_eq!(progress("a.mp4", 0, 1, -1), "Copying a.mp4 from your phone…");
    }
}
