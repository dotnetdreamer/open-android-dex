//! Keep a copy on this computer (doc/send-to-computer.md, "Keep a copy"):
//! each new photo and screenshot saved into a folder here, now and each time
//! the phone connects — from the moment it was switched on, by the phone's
//! own clock, or everything on the phone.
//!
//! There is no cursor to go stale: each time, the phone's list since that
//! moment (`all`) less what the folder's record has (ledger.rs) is what is
//! copied — newest first, one file at a time. A cable pulled half way costs
//! the file in flight its hidden part, and nothing else. Nothing is ever
//! deleted, here or on the phone, and a copy deleted here is not fetched
//! again: it is still in the record.
//!
//! Guards: it stops while this computer's disk has less than 2 GB free, or
//! less than twice the next file; over Wi-Fi, with the phone not charging and
//! under 20 %, the backlog waits — a picture just taken still comes.
//!
//! Without a record (deleted, or a power cut's empty one set aside) nothing is
//! saved twice: a picture whose name is already in its album folder with its
//! size, and its time or its bytes, is adopted as kept rather than saved
//! again as "(2)".
//!
//! It never goes round without a wait: up to date, it waits for the phone to
//! say a picture is new (or half an hour); held, or after a pass that did not
//! finish clean, a while. A picture this computer could not save is skipped
//! for that pass, like one the phone could not read, so the rest still come.
//! Change… and the scope choice reach a pass already running: it stops, and
//! the next starts from the new choice.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::json;
use tauri::{AppHandle, Emitter, Manager};

use super::ledger::{self, Entry, Index, Known, Opened, Record};
use super::link::Link;
use super::photos::Photo;
use super::words::{self, Keeping};
use crate::apps::shared;

/// The disk this computer keeps free, whatever is waiting.
const FLOOR: u64 = 2_000_000_000;
/// Below this, on battery and over Wi-Fi, the backlog waits.
const BATTERY_LOW: i64 = 20;
/// A picture added this long before the run began is backlog, not just taken.
const JUST_TAKEN: i64 = 120;
/// The record is written at most this often while a copy runs (and at its end).
const WRITE_EVERY: Duration = Duration::from_secs(10);
/// Up to date, a run waits for the phone's word that a picture is new — or this long.
const UP_TO_DATE: Duration = Duration::from_secs(30 * 60);
/// How long a run waits after a pass that did not finish clean, or with pictures skipped, before it asks again.
const AGAIN: Duration = Duration::from_secs(60);
/// How long a run held for the disk waits before it looks again.
const HELD: Duration = Duration::from_secs(60);
/// …and for the phone's battery, which changes slowly (a picture just taken still rings the bell).
const HELD_BATTERY: Duration = Duration::from_secs(5 * 60);
/// The free space is asked again after this long, or this many bytes written, in a pass.
const FREE_EVERY: Duration = Duration::from_secs(15);
const FREE_EVERY_BYTES: u64 = 256 * 1024 * 1024;

/// What the person chose, as a count: a pass running stops when it changes (Change…, the scope).
static CHOICE: AtomicU64 = AtomicU64::new(0);

/// What the person chose for one phone, in send.json.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Pref {
    pub on: bool,
    /// The chosen place: Pictures/Open Android DeX unless changed.
    pub root: String,
    /// `new` (from `since` on) or `all`.
    pub scope: String,
    /// The phone's clock when it was switched on: seconds. 0 with `all`.
    pub since: i64,
    /// The phone's folder inside `root`: its name, or a folder renamed by hand and found again.
    pub folder: String,
}

impl Pref {
    /// One phone's choice as send.json has it, field by field: one that cannot be read is its default.
    pub fn parse(v: &serde_json::Value) -> Option<Pref> {
        let o = v.as_object()?;
        let text = |k: &str| o.get(k).and_then(|x| x.as_str()).map(String::from).unwrap_or_default();
        Some(Pref {
            on: o.get("on").and_then(|x| x.as_bool()).unwrap_or(false),
            root: text("root"),
            scope: text("scope"),
            since: o.get("since").and_then(|x| x.as_i64()).unwrap_or(0),
            folder: text("folder"),
        })
    }
}

/// One phone's run, as the page sees it.
#[derive(Clone, Debug)]
struct Run {
    status: Keeping,
    saving: Option<(u64, u64)>,
    /// The run's thread is up.
    running: bool,
    stop: Arc<AtomicBool>,
    bell: Arc<(Mutex<bool>, Condvar)>,
}

fn runs() -> MutexGuard<'static, HashMap<String, Run>> {
    static R: OnceLock<Mutex<HashMap<String, Run>>> = OnceLock::new();
    R.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

/// Each phone's record as last read or written, its index, and its folder: where a kept copy is found.
type Records = HashMap<String, (PathBuf, Record, Index)>;

fn records() -> MutexGuard<'static, Records> {
    static R: OnceLock<Mutex<Records>> = OnceLock::new();
    R.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}

fn ring(bell: &(Mutex<bool>, Condvar)) {
    *bell.0.lock().unwrap() = true;
    bell.1.notify_all();
}

fn wait(bell: &(Mutex<bool>, Condvar), most: Duration) {
    let (rung, cv) = bell;
    let (mut rung, _) = cv.wait_timeout_while(rung.lock().unwrap(), most, |r| !*r).unwrap();
    *rung = false;
}

/// The keeper's look, every few seconds: a run for each phone that keeps a copy and is linked.
pub(crate) fn tick(app: &AppHandle) {
    let settings = super::settings();
    for (phone, pref) in &settings.keep {
        if !pref.on {
            continue;
        }
        let Some(link) = super::link_of(phone) else {
            set_status(app, phone, Keeping::Waiting { phone: super::phone_name(phone) }, None);
            continue;
        };
        let start = {
            let mut r = runs();
            let run = r.entry(phone.clone()).or_insert_with(|| Run {
                status: Keeping::Starting,
                saving: None,
                running: false,
                stop: Arc::new(AtomicBool::new(false)),
                bell: Arc::new((Mutex::new(false), Condvar::new())),
            });
            if run.running {
                None
            } else {
                run.running = true;
                run.stop = Arc::new(AtomicBool::new(false));
                Some((run.stop.clone(), run.bell.clone()))
            }
        };
        if let Some((stop, bell)) = start {
            let (app, phone) = (app.clone(), phone.clone());
            let _ = std::thread::Builder::new().name("keep-copy".into()).spawn(move || {
                run(&app, &phone, &link, &stop, &bell);
                if let Some(r) = runs().get_mut(&phone) {
                    r.running = false;
                }
            });
        }
    }
}

/// The link to `phone` went: its run stops, and says it waits.
pub(crate) fn link_down(phone: &str) {
    if let Some(r) = runs().get(phone) {
        r.stop.store(true, Ordering::SeqCst);
        ring(&r.bell);
    }
}

/// What the phone's photos did: a new one wakes the run. A snapshot does not:
/// it comes when the link is made (a run starts then anyway) or when the list
/// is asked for afresh, and a window shown again must not start a pass.
pub(crate) fn photos_said(_app: &AppHandle, link: &Link, kind: &str, _new: &[Photo]) {
    if kind != "new" {
        return;
    }
    if let Some(r) = runs().get(&link.phone) {
        ring(&r.bell);
    }
}

/// This app is exiting: every run stops.
pub(crate) fn stop_all() {
    for r in runs().values() {
        r.stop.store(true, Ordering::SeqCst);
        ring(&r.bell);
    }
}

/// `p`'s kept copy on this computer, if there is one: only ever a file inside the folder.
pub(crate) fn kept_path(phone: &str, p: &Photo) -> Option<PathBuf> {
    let recs = records();
    let (folder, r, ix) = recs.get(phone)?;
    let i = match ix.knows(r, p) {
        Known::Same(i) | Known::Renamed(i) => i,
        Known::No => return None,
    };
    let file = &r.items[i].file;
    if !kept_file_ok(file, &p.name) {
        return None;
    }
    let path = folder.join(file);
    path.is_file().then_some(path)
}

/// Whether a record's `file` may be acted on (Open, Save, Copy, Show) as photo
/// `name`'s copy: inside the folder, and a picture with the photo's own
/// extension — an entry pointing at `Camera/x.command` is never opened.
pub(crate) fn kept_file_ok(file: &str, name: &str) -> bool {
    let ext = |s: &str| Path::new(s).extension().map(|e| e.to_string_lossy().to_ascii_lowercase());
    ledger::inside(file) && ext(file).is_some_and(|e| super::inbox::is_picture_ext(&e) && Some(e.clone()) == ext(name))
}

fn set_status(app: &AppHandle, phone: &str, status: Keeping, saving: Option<(u64, u64)>) {
    let changed = {
        let mut r = runs();
        let run = r.entry(phone.to_string()).or_insert_with(|| Run {
            status: Keeping::Starting,
            saving: None,
            running: false,
            stop: Arc::new(AtomicBool::new(false)),
            bell: Arc::new((Mutex::new(false), Condvar::new())),
        });
        let said = run.status != status;
        if said && !matches!(status, Keeping::Saving { .. }) {
            log::info!("keep [{phone}] {}", words::keeping(&status));
        }
        let changed = said || run.saving != saving;
        run.status = status;
        run.saving = saving;
        changed
    };
    if changed {
        let _ = app.emit("keep:state", json!({"phone": phone}));
    }
}

/// What a run copies: the phone's list less what the record has, newest first.
pub(crate) fn missing(list: &[Photo], record: &Record) -> Vec<Photo> {
    let ix = Index::of(record);
    let mut out: Vec<Photo> = list.iter().filter(|p| ix.knows(record, p) == Known::No).cloned().collect();
    out.sort_by_key(|p| std::cmp::Reverse((p.added, p.id)));
    out
}

/// "From now on": the moment it starts from is the phone's own clock at the switch.
pub(crate) fn since_for(scope: &str, phone_now: i64) -> i64 {
    if scope == "all" {
        0
    } else {
        phone_now
    }
}

/// Whether `p` waits for the battery: backlog only, over Wi-Fi only, not charging and low.
pub(crate) fn waits_for_battery(p: &Photo, run_began: i64, on_cable: bool, battery: i64, charging: bool) -> bool {
    let backlog = p.added < run_began - JUST_TAKEN;
    backlog && !on_cable && !charging && (0..BATTERY_LOW).contains(&battery)
}

/// Whether `folder` is on another disk than this computer's own, where the
/// person's home is — an external drive, a card: the low disk is that one, and
/// the status says so rather than "This Mac".
fn on_another_disk(folder: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let home = std::env::var_os("HOME").and_then(|h| std::fs::metadata(h).ok());
        matches!((std::fs::metadata(folder), home), (Ok(f), Some(h)) if f.dev() != h.dev())
    }
    #[cfg(windows)]
    {
        let drive = |p: &Path| p.components().next().map(|c| c.as_os_str().to_string_lossy().to_uppercase());
        std::env::var_os("USERPROFILE").is_some_and(|h| drive(folder) != drive(Path::new(&h)))
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = folder;
        false
    }
}

/// A Keep folder's root that is not there has gone — a drive unplugged, a
/// folder moved — only if it was chosen with Change…: the default place in
/// Pictures is made again, and one that cannot be made, or none at all, is a
/// folder that cannot be saved in, not one that went.
pub(crate) fn root_gone(root: &str, default: Option<&Path>) -> bool {
    !root.is_empty() && default.is_none_or(|d| d != Path::new(root))
}

/// Whether the disk has room for the next file: above the floor, and twice the file.
pub(crate) fn disk_has_room(free: Option<u64>, next: u64, floor: u64) -> bool {
    match free {
        Some(free) => free >= floor && free >= next.saturating_mul(2),
        None => true,
    }
}

/// The disk this computer keeps free: for Keep a copy, and for what arrives in Downloads.
pub(crate) fn floor() -> u64 {
    super::test_override("OADX_KEEP_FLOOR").and_then(|v| v.parse().ok()).unwrap_or(FLOOR)
}

/// The default place: Pictures/Open Android DeX (a debug build's tests may say elsewhere).
fn default_root(app: &AppHandle) -> Option<PathBuf> {
    if let Some(r) = super::test_override("OADX_KEEP_ROOT") {
        return Some(PathBuf::from(r));
    }
    Some(app.path().picture_dir().ok()?.join("Open Android DeX"))
}

fn now_secs() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// How a picture's copy failed, by who failed.
#[derive(Debug, PartialEq)]
pub(crate) enum Failed {
    /// The phone could not read it: skipped this pass.
    Unreadable,
    /// This disk is full.
    Space,
    /// It is not what the list said any more: the list is asked again.
    Changed,
    /// The phone, or the link to it: the pass stops.
    Phone,
    /// This computer could not save it (a name refused, a folder not to be written): skipped this pass.
    Here,
}

/// What `keep_one`'s error says, by who failed.
pub(crate) fn failed(e: &str) -> Failed {
    match e {
        "unreadable" => Failed::Unreadable,
        "space" => Failed::Space,
        "gone" | "changed" => Failed::Changed,
        "lost" | "stopped" | "Your phone isn't answering." | "Your phone didn't answer." => Failed::Phone,
        _ => Failed::Here,
    }
}

/// The kept copies that are in the folder, each counted once: what "N photos kept" says.
pub(crate) fn kept_count(folder: &Path, record: &Record) -> u64 {
    let files: HashSet<&str> = record.items.iter().map(|e| e.file.as_str()).filter(|f| ledger::inside(f)).collect();
    files.into_iter().filter(|f| folder.join(f).is_file()).count() as u64
}

/// What a run says as it ends: waiting for the phone only if the phone went.
/// Stopped by the switch with the phone still there, it says nothing of a
/// phone to wait for — which a switch turned on again would show for seconds.
pub(crate) fn ended(linked: bool, phone_name: &str) -> Keeping {
    if linked {
        Keeping::Starting
    } else {
        Keeping::Waiting { phone: phone_name.to_string() }
    }
}

fn run(app: &AppHandle, phone: &str, link: &Arc<Link>, stop: &AtomicBool, bell: &(Mutex<bool>, Condvar)) {
    let name = super::phone_name(phone);
    let key = ledger::phone_key(phone);
    let mut unreadable: HashSet<u64> = HashSet::new();
    let mut not_saved: HashSet<u64> = HashSet::new();
    loop {
        if stop.load(Ordering::SeqCst) || !link.alive() {
            set_status(app, phone, ended(link.alive(), &name), None);
            return;
        }
        let choice = CHOICE.load(Ordering::SeqCst);
        let Some(mut pref) = super::settings().keep.get(phone).cloned().filter(|p| p.on) else { return };
        let root = PathBuf::from(&pref.root);
        if pref.root.is_empty() || !root.is_dir() {
            // Only one chosen with Change… has gone, and is never made again here: a drive not plugged in
            // would get a folder of its name on this disk. The default place is made; one that cannot be is
            // a folder that cannot be saved in.
            let gone = root_gone(&pref.root, default_root(app).as_deref());
            if gone || pref.root.is_empty() || std::fs::create_dir_all(&root).is_err() {
                set_status(app, phone, if gone { Keeping::FolderGone } else { Keeping::CantWrite }, None);
                wait(bell, HELD);
                continue;
            }
        }
        if cfg!(windows) && inside_onedrive(&root) {
            // Said beside the status, and carried on: the person chose it.
            log::info!("keep [{}] the folder is inside OneDrive", link.serial);
        }
        // The phone's folder: as chosen, or found again where it was renamed by hand.
        let mut folder = root.join(&pref.folder);
        if !folder.join(ledger::FILE).exists() {
            if let Some(found) = ledger::find(&root, &key) {
                pref.folder = found.file_name().unwrap_or_default().to_string_lossy().into_owned();
                folder = found;
                super::set_keep(app, phone, Some(pref.clone()));
            }
        }
        if std::fs::create_dir_all(&folder).is_err() {
            set_status(app, phone, Keeping::CantWrite, None);
            wait(bell, HELD);
            continue;
        }
        let mut record = match ledger::open(&folder, &key, &name) {
            Opened::Ready(r) => r,
            Opened::Newer => {
                set_status(app, phone, Keeping::Newer, None);
                wait(bell, UP_TO_DATE);
                continue;
            }
            Opened::Unreadable(e) => {
                log::warn!("keep [{}] the record in {} can't be read: {e}", link.serial, folder.display());
                set_status(app, phone, Keeping::RecordUnreadable, None);
                wait(bell, HELD);
                continue;
            }
            Opened::Other => {
                // Another phone's folder by that name: this one gets its own beside it — once.
                // If that one is another phone's too, it waits for Change… rather than going round.
                let own = format!("{} ({})", shared::arrival_name(&name), &key[..4]);
                if pref.folder == own {
                    set_status(app, phone, Keeping::OtherPhone, None);
                    wait(bell, AGAIN);
                    continue;
                }
                pref.folder = own;
                super::set_keep(app, phone, Some(pref.clone()));
                continue;
            }
        };
        let mut index = Index::of(&record);
        records().insert(phone.to_string(), (folder.clone(), record.clone(), index.clone()));
        // The list since the moment it starts from, a page at a time.
        let list = match list_since(link, pref.since) {
            Ok(l) => l,
            Err(e) => {
                log::warn!("keep [{}] the phone's list: {e}", link.serial);
                wait(bell, AGAIN);
                continue;
            }
        };
        let todo: Vec<Photo> = missing(&list, &record).into_iter().filter(|p| !unreadable.contains(&p.id) && !not_saved.contains(&p.id)).collect();
        if todo.is_empty() {
            let status = if !not_saved.is_empty() {
                Keeping::NotSaved { n: not_saved.len() as u64 }
            } else if !unreadable.is_empty() {
                Keeping::Unreadable { n: unreadable.len() as u64 }
            } else {
                Keeping::UpToDate { kept: kept_count(&folder, &record) }
            };
            let skipped = !not_saved.is_empty() || !unreadable.is_empty();
            set_status(app, phone, status, None);
            // Up to date: the phone's word that a picture is new rings the bell. Skipped ones are tried again.
            wait(bell, if skipped { AGAIN } else { UP_TO_DATE });
            unreadable.clear();
            not_saved.clear();
            continue;
        }
        let total = todo.len() as u64;
        let began = link.phone_now();
        let on_cable = crate::apps::on_cable(&link.serial);
        let mut battery: Option<(i64, bool, Instant)> = None;
        let mut written = Instant::now();
        let mut held = None;
        let mut clean = true;
        let mut free = crate::files::free_bytes(&folder);
        let (mut free_at, mut since_free) = (Instant::now(), 0u64);
        for (done, p) in todo.iter().enumerate() {
            if stop.load(Ordering::SeqCst) || !link.alive() {
                clean = false;
                break;
            }
            if CHOICE.load(Ordering::SeqCst) != choice {
                log::info!("keep [{}] the choice changed: this pass stops at {} of {total}", link.serial, done + 1);
                clean = false;
                break;
            }
            // The battery first: a backlog held for it costs no disk question per picture.
            if !on_cable {
                if battery.as_ref().is_none_or(|(_, _, at)| at.elapsed() > Duration::from_secs(60)) {
                    battery = status_of(link).map(|(b, c)| (b, c, Instant::now()));
                }
                if let Some((level, charging, _)) = battery {
                    if waits_for_battery(p, began, on_cable, level, charging) {
                        held = Some(Keeping::LowBattery { level });
                        continue;
                    }
                }
            }
            if free_at.elapsed() > FREE_EVERY || since_free > FREE_EVERY_BYTES {
                free = crate::files::free_bytes(&folder);
                (free_at, since_free) = (Instant::now(), 0);
            }
            if !disk_has_room(free, p.size, floor()) {
                held = Some(Keeping::LowDisk { elsewhere: on_another_disk(&folder) });
                break;
            }
            set_status(app, phone, Keeping::Saving { done: done as u64, total }, Some((done as u64, total)));
            match keep_one(link, &folder, &mut record, &mut index, p, stop) {
                Ok(()) => {
                    log::info!("keep [{}] {} of {total}", link.serial, done + 1);
                    free = free.map(|f| f.saturating_sub(p.size));
                    since_free += p.size;
                }
                Err(e) => match failed(&e) {
                    Failed::Unreadable => {
                        unreadable.insert(p.id);
                    }
                    Failed::Space => {
                        held = Some(Keeping::LowDisk { elsewhere: on_another_disk(&folder) });
                        break;
                    }
                    Failed::Changed => clean = false,
                    Failed::Phone => {
                        log::info!("keep [{}] stopped at {} of {total}: {e}", link.serial, done + 1);
                        clean = false;
                        break;
                    }
                    Failed::Here => {
                        // Skipped, as one the phone could not read: the older ones still come.
                        log::warn!("keep [{}] {} couldn't be saved here: {e}", link.serial, p.id);
                        not_saved.insert(p.id);
                    }
                },
            }
            if written.elapsed() >= WRITE_EVERY {
                let _ = ledger::write(&folder, &record);
                records().insert(phone.to_string(), (folder.clone(), record.clone(), index.clone()));
                written = Instant::now();
            }
        }
        if let Err(e) = ledger::write(&folder, &record) {
            log::warn!("keep [{}] the record could not be written: {e}", link.serial);
        }
        records().insert(phone.to_string(), (folder.clone(), record.clone(), index.clone()));
        let _ = app.emit("photos:changed", json!({"phone": phone}));
        if let Some(h) = held {
            let most = if matches!(h, Keeping::LowBattery { .. }) { HELD_BATTERY } else { HELD };
            set_status(app, phone, h, None);
            wait(bell, most);
        } else if !clean && CHOICE.load(Ordering::SeqCst) == choice {
            // Never straight round: a picture rewritten every second, a phone that stopped answering.
            // Said for the wait, so "Saving 3 of 193…" does not stay on through it.
            let status = if link.alive() { Keeping::Again } else { Keeping::Waiting { phone: name.clone() } };
            set_status(app, phone, status, None);
            wait(bell, AGAIN);
        }
    }
}

/// Every picture since `since`, oldest first, a page at a time.
fn list_since(link: &Link, since: i64) -> Result<Vec<Photo>, String> {
    let mut all = Vec::new();
    let mut after: Option<serde_json::Value> = None;
    loop {
        let mut ask = json!({"t": "all", "since": since, "limit": 500});
        if let Some(a) = &after {
            ask["after"] = a.clone();
        }
        let v = link.ask(&ask, "all", Duration::from_secs(30))?;
        all.extend(Photo::parse_all(&v["items"]));
        match v.get("next") {
            Some(n) if n.is_object() => after = Some(n.clone()),
            _ => return Ok(all),
        }
    }
}

fn status_of(link: &Link) -> Option<(i64, bool)> {
    let v = link.ask(&json!({"t": "status"}), "status", Duration::from_secs(10)).ok()?;
    Some((v["battery"].as_i64().unwrap_or(-1), v["charging"].as_bool().unwrap_or(true)))
}

/// One picture into its album's folder, and into the record: unless its bytes
/// are a copy already kept, or the picture is already in the folder.
fn keep_one(link: &Link, folder: &Path, record: &mut Record, index: &mut Index, p: &Photo, stop: &AtomicBool) -> Result<(), String> {
    let album = if p.album.is_empty() { "Pictures".to_string() } else { shared::arrival_name(&p.album) };
    let dir = folder.join(&album);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let name = shared::arrival_name(&p.name);
    let at = now_secs();
    let entry = |file: String, sha256: String| Entry { file, name: p.name.clone(), size: p.size, modified: p.modified, taken: p.taken, sha256, at };
    // Already here, by its size and the time Keep a copy gives what it saves: no bytes need to come.
    let here = same_name_here(&dir, &name, p.size);
    if let Some(f) = adopt_by_time(&here, &album, index, p.taken) {
        log::info!("keep [{}] {} is in the folder already: adopted", link.serial, p.id);
        let file = format!("{album}/{}", f.file_name().unwrap_or_default().to_string_lossy());
        add(record, index, entry(file, sha_of(f).unwrap_or_default()));
        return Ok(());
    }
    // Its part first, made here exclusively: a folder that cannot be written is found out before the
    // phone is asked for a byte, and nothing already in the folder is opened or written over.
    let (incoming, out) = super::inbox::part_in(&dir, &format!("oadx-incoming-{}", p.id))
        .map_err(|e| if super::photos::is_disk_full(&e) { "space".to_string() } else { e.to_string() })?;
    let fetched = super::photos::fetch(link, p, &out, |_| !stop.load(Ordering::SeqCst));
    // Known by what it is now, every byte in (FAT and exFAT number a file anew by its first byte).
    let made = super::inbox::close(out);
    let gone = |incoming: &Path| super::inbox::let_go_part(incoming, made);
    let sha = match fetched {
        Ok(sha) => sha,
        Err(e) => {
            gone(&incoming);
            return Err(e);
        }
    };
    if let Some(e) = index.by_hash(record, &sha).cloned() {
        // The phone restored, its times changed: the same bytes are here already.
        gone(&incoming);
        add(record, index, entry(e.file, sha));
        return Ok(());
    }
    // In doubt (the same name and size, another time): its bytes say.
    if let Some(f) = here.iter().find(|f| sha_of(f).as_deref() == Some(sha.as_str())) {
        gone(&incoming);
        log::info!("keep [{}] {} is in the folder already, by its bytes: adopted", link.serial, p.id);
        add(record, index, entry(format!("{album}/{}", f.file_name().unwrap_or_default().to_string_lossy()), sha));
        return Ok(());
    }
    let r = match super::inbox::reserve(&dir, &name) {
        Ok(r) => r,
        Err(e) => {
            gone(&incoming);
            return Err(e.to_string());
        }
    };
    // Over the reservation while it is still the empty file made for it, else under another free name.
    let landed = match r.land_from(&incoming) {
        Ok(at) => at,
        Err(e) => {
            gone(&incoming);
            return Err(e.to_string());
        }
    };
    super::inbox::unfinished(&incoming, false, super::inbox::Leftover::Part);
    // When it was taken, so Finder and Explorer sort them right — and so it is known again without a record.
    if let Some(t) = taken_time(p.taken) {
        if let Ok(f) = std::fs::File::options().write(true).open(&landed) {
            let _ = f.set_modified(t);
        }
    }
    let file = format!("{album}/{}", landed.file_name().unwrap_or_default().to_string_lossy());
    add(record, index, entry(file, sha));
    Ok(())
}

fn add(record: &mut Record, index: &mut Index, e: Entry) {
    index.added(&e, record.items.len());
    record.add(e);
}

/// When a picture was taken (ms), as a file's time: none for a time no file can have.
pub(crate) fn taken_time(taken_ms: i64) -> Option<SystemTime> {
    if taken_ms <= 0 {
        return None;
    }
    let t = UNIX_EPOCH.checked_add(Duration::from_millis(taken_ms as u64))?;
    // Past the year 9999 no file system keeps it, and Windows' clock cannot say it.
    (taken_ms < 253_402_300_800_000).then_some(t)
}

/// Files in `dir` named `name`, "name (2)", … that are `size` bytes: what an earlier run may have saved.
pub(crate) fn same_name_here(dir: &Path, name: &str, size: u64) -> Vec<PathBuf> {
    let wanted = dir.join(name);
    let stem = wanted.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let ext = wanted.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    let mut out = Vec::new();
    for n in 1..10_000u32 {
        let path = if n == 1 { wanted.clone() } else { dir.join(format!("{stem} ({n}){ext}")) };
        let Ok(m) = std::fs::symlink_metadata(&path) else { break };
        if m.is_file() && m.len() == size {
            out.push(path);
        }
    }
    out
}

/// Of `here`, the one adopted without a byte from the phone: its time is when
/// the picture was taken, and no entry of the record names it already. One the
/// record names is a copy of something — an earlier edit of this very picture,
/// say, at the same length and time — and only its bytes may say it is this.
pub(crate) fn adopt_by_time<'a>(here: &'a [PathBuf], album: &str, index: &Index, taken_ms: i64) -> Option<&'a PathBuf> {
    here.iter().find(|f| {
        let file = format!("{album}/{}", f.file_name().unwrap_or_default().to_string_lossy());
        !index.names(&file) && taken_matches(f, taken_ms)
    })
}

/// Whether `f`'s time is when the picture was taken, as Keep a copy sets it (to the second: some disks keep no less).
fn taken_matches(f: &Path, taken_ms: i64) -> bool {
    let Some(want) = taken_time(taken_ms) else { return false };
    let Ok(have) = std::fs::metadata(f).and_then(|m| m.modified()) else { return false };
    let secs = |t: SystemTime| t.duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(-1);
    (secs(want) - secs(have)).abs() <= 1
}

fn sha_of(f: &Path) -> Option<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut file = std::fs::File::open(f).ok()?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Some(format!("{:x}", h.finalize()))
}

/// A folder OneDrive syncs: it uploads what is saved there.
fn inside_onedrive(p: &Path) -> bool {
    ["OneDrive", "OneDriveConsumer", "OneDriveCommercial"].iter().any(|v| {
        std::env::var_os(v).is_some_and(|od| !od.is_empty() && p.starts_with(PathBuf::from(od)))
    })
}

// ── what the page asks ──

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct KeepView {
    pub on: bool,
    pub scope: String,
    /// The folder, as a path.
    pub folder: String,
    /// …and as the card shows it: "Pictures › Open Android DeX › Galaxy S24".
    pub shown: String,
    pub status: String,
    /// Said beside the status, never instead of it: the folder is inside OneDrive.
    pub notice: Option<String>,
    pub saving: Option<(u64, u64)>,
    /// "193 photos, 612 MB".
    pub everything: String,
    pub folder_gone: bool,
    pub one_drive: bool,
    pub this_computer: &'static str,
}

fn pref_or_default(app: &AppHandle, phone: &str, name: &str) -> Pref {
    super::settings().keep.get(phone).cloned().unwrap_or_else(|| Pref {
        on: false,
        root: default_root(app).map(|p| p.to_string_lossy().into_owned()).unwrap_or_default(),
        scope: "new".into(),
        since: 0,
        folder: shared::arrival_name(name),
    })
}

fn shown(app: &AppHandle, path: &Path) -> String {
    let home = app.path().home_dir().ok();
    let rel = home.as_ref().and_then(|h| path.strip_prefix(h).ok()).unwrap_or(path);
    rel.components().map(|c| c.as_os_str().to_string_lossy().into_owned()).filter(|c| c != "/" && !c.is_empty()).collect::<Vec<_>>().join(" › ")
}

fn view(app: &AppHandle, phone: &str, name: &str) -> KeepView {
    let pref = pref_or_default(app, phone, name);
    let folder = PathBuf::from(&pref.root).join(&pref.folder);
    let (status, saving) = runs()
        .get(phone)
        .map(|r| (r.status.clone(), r.saving))
        .unwrap_or((if super::link_of(phone).is_some() { Keeping::Starting } else { Keeping::Waiting { phone: name.to_string() } }, None));
    let folder_gone = pref.on && !Path::new(&pref.root).is_dir() && root_gone(&pref.root, default_root(app).as_deref());
    let one_drive = cfg!(windows) && inside_onedrive(&folder);
    let photos = super::photos::totals(phone);
    KeepView {
        on: pref.on,
        scope: if pref.scope == "all" { "all".into() } else { "new".into() },
        folder: folder.to_string_lossy().into_owned(),
        shown: shown(app, &folder),
        status: if folder_gone { words::keeping(&Keeping::FolderGone) } else { words::keeping(&status) },
        notice: (one_drive && pref.on).then(|| words::keeping(&Keeping::OneDrive)),
        saving,
        everything: words::everything(photos.0, photos.1),
        folder_gone,
        one_drive,
        this_computer: words::this_computer(),
    }
}

#[tauri::command(async)]
pub fn keep_state(app: AppHandle, serial: String) -> KeepView {
    let (phone, name) = super::phone_of(&app, &serial);
    view(&app, &phone, &name)
}

/// The switch, or the choice between "New photos from now on" and "Everything on the phone".
#[tauri::command(async)]
pub fn keep_set(app: AppHandle, serial: String, on: Option<bool>, scope: Option<String>) -> Result<KeepView, String> {
    let (phone, name) = super::phone_of(&app, &serial);
    let mut pref = pref_or_default(&app, &phone, &name);
    let was = pref.clone();
    if let Some(s) = scope {
        pref.scope = if s == "all" { "all".into() } else { "new".into() };
    }
    if let Some(o) = on {
        pref.on = o;
    }
    // The moment it starts from, by the phone's own clock: asked when switched on or moved to "from now on".
    if pref.on && (!was.on || pref.scope != was.scope) {
        let phone_now = match super::link_of(&phone) {
            Some(l) => status_now(&l).unwrap_or_else(|| l.phone_now()),
            None if pref.scope == "new" => return Err("Connect your phone first.".into()),
            None => 0,
        };
        pref.since = since_for(&pref.scope, phone_now);
    }
    if pref.on && !pref.root.is_empty() {
        let _ = std::fs::create_dir_all(&pref.root);
    }
    log::info!("keep [{serial}] {} ({}), since {}", if pref.on { "on" } else { "off" }, pref.scope, pref.since);
    let (on, changed) = (pref.on, pref.scope != was.scope || pref.since != was.since);
    // Stored first, then counted, then told — as Change… does: a run woken by the bell reads the new choice.
    super::set_keep(&app, &phone, Some(pref));
    if changed {
        CHOICE.fetch_add(1, Ordering::SeqCst);
    }
    if let Some(r) = runs().get(&phone) {
        if !on {
            r.stop.store(true, Ordering::SeqCst);
        }
        ring(&r.bell);
    }
    // Switched on with the phone there: a run starts now, not at the keeper's next look.
    if on && !was.on && super::link_of(&phone).is_some() {
        set_status(&app, &phone, ended(true, &name), None);
        tick(&app);
    }
    let _ = app.emit("keep:state", json!({"phone": phone}));
    Ok(view(&app, &phone, &name))
}

fn status_now(l: &Link) -> Option<i64> {
    l.ask(&json!({"t": "status"}), "status", Duration::from_secs(5)).ok()?["now"].as_i64()
}

/// Change…: the system's folder chooser; the phone's folder goes inside what is chosen.
#[tauri::command(async)]
pub fn keep_choose(app: AppHandle, serial: String) -> Result<KeepView, String> {
    let (phone, name) = super::phone_of(&app, &serial);
    let mut pref = pref_or_default(&app, &phone, &name);
    let start = PathBuf::from(&pref.root);
    let Some(chosen) = super::pick::folder(&app, start.is_dir().then_some(start.as_path()))? else {
        return Ok(view(&app, &phone, &name));
    };
    let (root, folder) = chosen_place(&chosen, &ledger::phone_key(&phone), &name);
    pref.root = root.to_string_lossy().into_owned();
    pref.folder = folder;
    records().remove(&phone);
    // Set first, then told: the pass that stops reads the new folder when it starts again.
    super::set_keep(&app, &phone, Some(pref));
    CHOICE.fetch_add(1, Ordering::SeqCst);
    if let Some(r) = runs().get(&phone) {
        ring(&r.bell);
    }
    let _ = app.emit("keep:state", json!({"phone": phone}));
    Ok(view(&app, &phone, &name))
}

/// Where Change… puts phone `key`'s folder, chosen `chosen`: the root and the
/// folder's name in it. Its own folder chosen (it holds this phone's record:
/// moved to another disk, say, and chosen there) is carried on with — its
/// parent the root — never copied into again inside itself; else the folder
/// inside the chosen one holding this phone's record, or one named for it.
pub(crate) fn chosen_place(chosen: &Path, key: &str, name: &str) -> (PathBuf, String) {
    if ledger::holds(chosen, key) {
        if let (Some(parent), Some(own)) = (chosen.parent(), chosen.file_name()) {
            return (parent.to_path_buf(), own.to_string_lossy().into_owned());
        }
    }
    let folder = ledger::find(chosen, key)
        .and_then(|f| f.file_name().map(|n| n.to_string_lossy().into_owned()))
        .unwrap_or_else(|| shared::arrival_name(name));
    (chosen.to_path_buf(), folder)
}

/// The folder, shown in Finder or Explorer.
#[tauri::command(async)]
pub fn keep_show(app: AppHandle, serial: String) -> Result<(), String> {
    let (phone, name) = super::phone_of(&app, &serial);
    let pref = pref_or_default(&app, &phone, &name);
    let folder = PathBuf::from(&pref.root).join(&pref.folder);
    let target = if folder.is_dir() { folder } else { PathBuf::from(&pref.root) };
    if !target.exists() {
        return Err("Can't find this folder.".into());
    }
    use tauri_plugin_opener::OpenerExt;
    app.opener().open_path(target.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_run_the_switch_stopped_never_says_it_waits_for_the_phone() {
        // Off and on again with the phone there: no "Waiting for…" between.
        assert_eq!(ended(true, "Galaxy S24"), Keeping::Starting);
        assert!(!words::keeping(&ended(true, "Galaxy S24")).contains("Waiting"));
        // The phone gone: that is what it waits for.
        assert_eq!(ended(false, "Galaxy S24"), Keeping::Waiting { phone: "Galaxy S24".into() });
    }

    fn photo(id: u64, name: &str, size: u64, added: i64) -> Photo {
        Photo {
            id,
            name: name.into(),
            album: "Camera".into(),
            path: format!("DCIM/Camera/{name}"),
            taken: added * 1000,
            added,
            modified: added,
            size,
            w: 1,
            h: 1,
            mime: "image/jpeg".into(),
        }
    }

    #[test]
    fn only_a_chosen_folder_has_gone() {
        let default = Path::new("/Users/someone/Pictures/Open Android DeX");
        assert!(root_gone("/Volumes/SD/Photos", Some(default)), "chosen with Change…, now not there");
        assert!(!root_gone("/Users/someone/Pictures/Open Android DeX", Some(default)), "the default place: made, or can't be saved in");
        assert!(!root_gone("", Some(default)), "no folder at all: never one that went");
        assert!(root_gone("/Volumes/SD/Photos", None));
        assert_eq!(words::keeping(&Keeping::CantWrite), "Can't save to this folder. Choose another one.");
    }

    #[test]
    fn change_onto_the_phones_own_folder_carries_on_with_it() {
        let root = std::env::temp_dir().join(format!("oadx-keep-chosen-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let own = root.join("Galaxy S24");
        std::fs::create_dir_all(own.join("Camera")).unwrap();
        ledger::write(&own, &Record::new("mine", "Galaxy S24")).unwrap();
        // Moved to another disk and chosen there: carried on with, not started again inside itself.
        assert_eq!(chosen_place(&own, "mine", "Galaxy S24"), (root.clone(), "Galaxy S24".to_string()));
        // Its parent chosen: found inside it, as before — renamed by hand, too.
        std::fs::rename(&own, root.join("Old phone")).unwrap();
        assert_eq!(chosen_place(&root, "mine", "Galaxy S24"), (root.clone(), "Old phone".to_string()));
        // Another phone's folder chosen: this one's goes inside it, as any other folder.
        let theirs = root.join("Old phone");
        assert_eq!(chosen_place(&theirs, "someone else", "Pixel"), (theirs.clone(), "Pixel".to_string()));
        // An empty folder: this phone's folder inside it.
        let empty = root.join("Empty");
        std::fs::create_dir_all(&empty).unwrap();
        assert_eq!(chosen_place(&empty, "mine", "Galaxy S24"), (empty.clone(), "Galaxy S24".to_string()));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn what_is_missing_is_the_list_less_the_record() {
        let mut r = Record::new("k", "Galaxy");
        r.add(Entry { file: "Camera/a.jpg".into(), name: "a.jpg".into(), size: 10, modified: 100, taken: 0, sha256: "x".into(), at: 0 });
        let list = vec![photo(1, "a.jpg", 10, 100), photo(2, "b.jpg", 20, 200), photo(3, "c.jpg", 30, 300), photo(4, "renamed.jpg", 10, 100)];
        let m = missing(&list, &r);
        // Newest first; a.jpg kept, and its renamed self known too.
        assert_eq!(m.iter().map(|p| p.id).collect::<Vec<_>>(), [3, 2]);
    }

    #[test]
    fn from_now_on_starts_at_the_phones_own_clock() {
        assert_eq!(since_for("new", 1_794_436_573), 1_794_436_573);
        assert_eq!(since_for("all", 1_794_436_573), 0);
    }

    #[test]
    fn the_backlog_waits_on_a_low_battery_over_wifi_and_a_new_picture_does_not() {
        let began = 10_000;
        let old = photo(1, "old.jpg", 1, began - 3600);
        let new = photo(2, "new.jpg", 1, began);
        assert!(waits_for_battery(&old, began, false, 15, false));
        assert!(!waits_for_battery(&new, began, false, 15, false));
        // Charging, on the cable, or above the line: it carries on.
        assert!(!waits_for_battery(&old, began, false, 15, true));
        assert!(!waits_for_battery(&old, began, true, 15, false));
        assert!(!waits_for_battery(&old, began, false, 20, false));
        // A phone that would not say is not held.
        assert!(!waits_for_battery(&old, began, false, -1, false));
    }

    #[test]
    fn a_failure_here_skips_the_picture_and_one_there_stops_the_pass() {
        assert_eq!(failed("unreadable"), Failed::Unreadable);
        assert_eq!(failed("space"), Failed::Space);
        assert_eq!(failed("changed"), Failed::Changed);
        assert_eq!(failed("gone"), Failed::Changed);
        assert_eq!(failed("lost"), Failed::Phone);
        assert_eq!(failed("Your phone isn't answering."), Failed::Phone);
        // Anything else is this computer's: a name refused, a folder read-only.
        assert_eq!(failed("Read-only file system (os error 30)"), Failed::Here);
        assert_eq!(failed("Permission denied (os error 13)"), Failed::Here);
    }

    #[test]
    fn a_file_the_record_names_is_adopted_only_by_its_bytes() {
        let d = std::env::temp_dir().join(format!("oadx-keep-named-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let taken = 1_794_436_573_000i64;
        let f = d.join("a.jpg");
        std::fs::write(&f, b"0123456789").unwrap();
        std::fs::File::options().write(true).open(&f).unwrap().set_modified(taken_time(taken).unwrap()).unwrap();
        let here = vec![f.clone()];
        // No record names it (the record was lost): adopted by its time.
        assert_eq!(adopt_by_time(&here, "Camera", &Index::default(), taken), Some(&f));
        // The record names it: an earlier copy, of this picture before an edit at the same length and time.
        let mut r = Record::new("k", "Galaxy");
        r.add(Entry { file: "Camera/a.jpg".into(), name: "a.jpg".into(), size: 10, modified: 1, taken, sha256: "old".into(), at: 0 });
        assert_eq!(adopt_by_time(&here, "Camera", &Index::of(&r), taken), None);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn only_a_picture_with_the_photos_own_extension_is_acted_on() {
        assert!(kept_file_ok("Camera/a.jpg", "a.jpg"));
        assert!(kept_file_ok("Camera/a (2).JPG", "a.jpg"));
        assert!(kept_file_ok("Screenshots/s.png", "s.png"));
        assert!(!kept_file_ok("Camera/x.command", "x.jpg"));
        assert!(!kept_file_ok("Camera/x.command", "x.command"));
        assert!(!kept_file_ok("Camera/a.png", "a.jpg"));
        assert!(!kept_file_ok("../a.jpg", "a.jpg"));
        assert!(!kept_file_ok("Camera/noext", "noext"));
    }

    #[test]
    fn an_absurd_date_is_no_time_and_no_panic() {
        assert!(taken_time(0).is_none());
        assert!(taken_time(-5).is_none());
        assert!(taken_time(i64::MAX).is_none());
        assert!(taken_time(1_794_436_573_000).is_some());
    }

    #[test]
    fn a_picture_already_in_the_folder_is_found_by_its_name_and_size() {
        let d = std::env::temp_dir().join(format!("oadx-keep-adopt-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("a.jpg"), b"other picture").unwrap();
        std::fs::write(d.join("a (2).jpg"), b"0123456789").unwrap();
        std::fs::write(d.join("a (3).jpg"), b"9876543210").unwrap();
        std::fs::write(d.join("b.jpg"), b"x").unwrap();
        let found = same_name_here(&d, "a.jpg", 10);
        assert_eq!(found, vec![d.join("a (2).jpg"), d.join("a (3).jpg")]);
        assert!(same_name_here(&d, "c.jpg", 1).is_empty());
        // Its time, as Keep a copy set it, adopts it without a byte from the phone; another time does not.
        let taken = 1_794_436_573_000i64;
        let f = std::fs::File::options().write(true).open(d.join("a (3).jpg")).unwrap();
        f.set_modified(taken_time(taken).unwrap()).unwrap();
        drop(f);
        assert!(taken_matches(&d.join("a (3).jpg"), taken));
        assert!(!taken_matches(&d.join("a (2).jpg"), taken));
        // In doubt, its bytes say.
        use sha2::{Digest, Sha256};
        assert_eq!(sha_of(&d.join("a (2).jpg")).unwrap(), format!("{:x}", Sha256::digest(b"0123456789")));
        // Counted once each, and only what is there.
        let mut r = Record::new("k", "Galaxy");
        let e = |file: &str| Entry { file: file.into(), name: "a.jpg".into(), size: 10, modified: 1, taken: 0, sha256: String::new(), at: 0 };
        r.add(e("a (2).jpg"));
        r.add(e("a (2).jpg"));
        r.add(e("a (3).jpg"));
        r.add(e("deleted by hand.jpg"));
        r.add(e("../escape.jpg"));
        assert_eq!(kept_count(&d, &r), 2);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_disk_guard_counts_the_next_file() {
        let gb = 1_000_000_000u64;
        assert!(disk_has_room(Some(3 * gb), 100, 2 * gb));
        assert!(!disk_has_room(Some(gb), 100, 2 * gb));
        // Above the floor, but not twice the next file.
        assert!(!disk_has_room(Some(3 * gb), 2 * gb, 2 * gb));
        assert!(disk_has_room(Some(5 * gb), 2 * gb, 2 * gb));
        assert!(disk_has_room(None, 100, 2 * gb));
    }
}
