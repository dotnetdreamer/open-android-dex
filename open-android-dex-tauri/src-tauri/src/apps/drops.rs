//! Files dropped on an app window, or on this app's main window, for the
//! phone (doc/apps-on-pc.md, "Dropping files").
//!
//! Dropped on the app in a window, they go to the app, as a drop from another
//! of the phone's apps would: a chat attaches them, a text field takes a text
//! file's text. Each is sent first into the launcher's cache on the phone
//! (`stage`), then the phone drops them all on the app where the pointer let
//! go (`dropIn`, AppDropIn.java). Where the app takes no drop, they go where
//! a drop goes otherwise; so does each the app took the drop of and hasn't
//! opened a few seconds later, as an app may use only some of a drop.
//!
//! Anywhere else — a window's title bar, the main window or a paste in it, a
//! Linux app's window — and for a folder, which no app takes: what a drop on the desktop
//! does, the file into the phone's Download folder. A folder brings the files
//! in it, in folders of the same names. Nothing is overwritten: a name
//! already there becomes "report (2).pdf", on the phone (AppDrop.java). An
//! APK is installed, wherever it is dropped.
//!
//! Each file is a connection of its own to the phone's app server — through
//! adb, as a window's is, so nothing listens on this computer — and its bytes
//! are streamed on it as they are read here: no copy waits on adb's own
//! command line or its time limit. The phone says how much has landed as it
//! lands, and that is the progress the window shows (link.rs, `Surface::note`).

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};

use super::{adbsock, wire, Note};

/// How much is read and sent at a time.
const CHUNK: usize = 256 * 1024;
/// How long the phone may take to say how a file went once it has all of it:
/// an install is the long one (AppDrop.INSTALL_TIMEOUT_S, and a little).
const RESULT_TIMEOUT: Duration = Duration::from_secs(200);
/// A folder's files at most: a drop of a whole drive is a mistake, not a copy.
const MAX_FILES: usize = 5000;

/// One file a drop sends.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Item {
    pub local: PathBuf,
    /// Its name on the phone; the phone makes it safe, and unique.
    pub name: String,
    /// The folder it goes in, under Download, '/'-separated: "" for a file
    /// dropped on its own, "trip/photos" for one inside a dropped folder.
    pub dir: String,
    pub size: u64,
    /// An APK, installed rather than copied.
    pub install: bool,
}

/// What a drop of `paths` sends: each file, and every file in each folder —
/// links are not followed. And how many things were left out.
pub fn plan(paths: &[PathBuf]) -> (Vec<Item>, usize) {
    let mut items = Vec::new();
    let mut skipped = 0;
    for p in paths {
        let Ok(meta) = std::fs::symlink_metadata(p) else {
            skipped += 1;
            continue;
        };
        let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        if meta.is_file() {
            let install = name.to_lowercase().ends_with(".apk");
            items.push(Item { local: p.clone(), name, dir: String::new(), size: meta.len(), install });
        } else if meta.is_dir() {
            walk(p, &name, &mut items, &mut skipped);
        } else {
            skipped += 1;
        }
    }
    (items, skipped)
}

/// Every file under `dir`, in `rel` and its subfolders on the phone. Never installed: a folder is copied as it is.
fn walk(dir: &Path, rel: &str, items: &mut Vec<Item>, skipped: &mut usize) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        *skipped += 1;
        return;
    };
    let mut entries: Vec<_> = entries.filter_map(Result::ok).collect();
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        if items.len() >= MAX_FILES {
            *skipped += 1;
            return;
        }
        let name = e.file_name().to_string_lossy().into_owned();
        match e.file_type() {
            Ok(t) if t.is_file() => {
                let size = e.metadata().map(|m| m.len()).unwrap_or(0);
                items.push(Item { local: e.path(), name, dir: rel.to_string(), size, install: false });
            }
            Ok(t) if t.is_dir() => walk(&e.path(), &format!("{rel}/{name}"), items, skipped),
            _ => *skipped += 1,
        }
    }
}

/// One drop at a time, from every window: a second waits for the first, so
/// two never share the link and both crawl.
fn sending() -> &'static Mutex<()> {
    static S: Mutex<()> = Mutex::new(());
    &S
}

/// A drop let go on the app in a window, rather than on its title bar: the
/// window, by the key the phone's server knows it by, and the point there on
/// the phone's display.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct On {
    pub key: String,
    pub x: u16,
    pub y: u16,
}

/// Send what was dropped to `serial`'s phone, through its app server on
/// `socket`, one file after another: to the app `on` says it was let go on,
/// else to Download. `app` is that app's name, for what is said. `note` hears
/// how it goes — a line to show, whether more of it is coming, and whether
/// anything was lost — for a window's strip under its title bar, the main
/// window's toast, or a paste's.
pub fn send(
    serial: String,
    socket: String,
    paths: Vec<PathBuf>,
    on: Option<On>,
    app: String,
    note: impl Fn(Note) + Send + 'static,
) {
    let _ = thread::Builder::new().name("apps-drop".into()).spawn(move || {
        let (items, skipped) = plan(&paths);
        if items.is_empty() {
            note(Note { text: "Only files and folders can be dropped here".into(), busy: false, failed: true });
            return;
        }
        let _one = sending().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        // A folder is no app's to take: a drop with one goes to Download, all of it.
        let on = on.filter(|_| items.iter().all(|i| i.dir.is_empty()));
        let batch = on.as_ref().map(|_| batch_id());
        let total = items.len();
        let mut landed = Vec::new();
        let mut staged: Vec<(usize, String)> = Vec::new();
        let mut last_error = None;
        for (i, item) in items.iter().enumerate() {
            // An APK is installed, wherever it was dropped.
            let stage = batch.as_deref().filter(|_| !item.install).map(|b| (b, i));
            let to = stage.map(|_| app.as_str());
            let mut shown = -1i64;
            let r = put(&serial, &socket, item, stage, |bytes| {
                let pct = if item.size == 0 { 100 } else { (bytes * 100 / item.size) as i64 };
                if pct != shown {
                    shown = pct;
                    note(Note { text: progress(item, i, total, pct as u32, to), busy: true, failed: false });
                }
            });
            match r {
                Ok(Landed { name, staged: true, .. }) => staged.push((i, name)),
                Ok(Landed { name, path, .. }) => {
                    log::info!("apps [{serial}] dropped {} → {path}", item.local.display());
                    landed.push(name);
                }
                Err(e) => {
                    log::warn!("apps [{serial}] dropped {}: {e}", item.local.display());
                    last_error = Some(e);
                }
            }
        }
        if skipped > 0 {
            log::info!("apps [{serial}] a drop left {skipped} out (links, or more than {MAX_FILES} files)");
        }
        // How it went, as for any drop: `landed`, the names the files got on the phone.
        let done = |landed: &[String], error: Option<&str>| Note {
            text: outcome(&items, landed, error),
            busy: false,
            failed: landed.len() < items.len(),
        };
        let (Some(on), Some(batch)) = (on, batch) else {
            note(done(&landed, last_error.as_deref()));
            return;
        };
        if staged.is_empty() {
            note(done(&landed, last_error.as_deref()));
            return;
        }
        let given = drop_in(&serial, &socket, &on, &batch, &staged);
        let names: Vec<String> = staged.iter().map(|(i, _)| items[*i].name.clone()).collect();
        match &given {
            Ok(Given::Taken { unopened }) if unopened.is_empty() => {
                log::info!("apps [{serial}] {} file(s) dropped into {app} at {},{}", staged.len(), on.x, on.y)
            }
            Ok(Given::Taken { unopened }) => log::info!(
                "apps [{serial}] {} file(s) dropped into {app} at {},{}; {} not opened by it: to Download as well",
                staged.len(),
                on.x,
                on.y,
                unopened.len()
            ),
            Ok(Given::Elsewhere { refused, .. }) => log::info!(
                "apps [{serial}] {} file(s) {} at {},{}: to Download",
                staged.len(),
                if *refused { format!("not taken by {app}") } else { "could not be dropped".into() },
                on.x,
                on.y
            ),
            Err(e) => log::warn!("apps [{serial}] a drop into {app}: {e}"),
        }
        let simple = landed.is_empty() && last_error.is_none() && staged.len() == items.len();
        let said = match given {
            Ok(Given::Taken { unopened }) if simple => {
                // One that could not be put in Download as well is still the app's.
                for e in unopened.iter().filter_map(|r| r.as_ref().err()) {
                    log::warn!("apps [{serial}] a file {app} hasn't opened, to Download as well: {e}");
                }
                let text = taken_outcome(&app, &names, &unopened.into_iter().flatten().collect::<Vec<_>>());
                Note { text, busy: false, failed: false }
            }
            Ok(Given::Elsewhere { refused, landed: there }) if simple && there.iter().all(Result::is_ok) => {
                let there: Vec<String> = there.into_iter().flatten().collect();
                let text = if refused { given_outcome(&app, &names, Some(&there)) } else { outcome(&items, &there, None) };
                Note { text, busy: false, failed: false }
            }
            // With APKs, failures or both: how many are on the phone, as for any drop.
            Ok(Given::Taken { .. }) => {
                landed.extend(names);
                done(&landed, last_error.as_deref())
            }
            Ok(Given::Elsewhere { landed: there, .. }) => {
                for r in there {
                    match r {
                        Ok(name) => landed.push(name),
                        Err(e) => last_error = Some(e),
                    }
                }
                done(&landed, last_error.as_deref())
            }
            Err(e) => done(&landed, Some(e.as_str())),
        };
        note(said);
    });
}

/// A name for one drop's files on the phone, as the server takes one: letters, digits and a dash.
fn batch_id() -> String {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    format!("{nanos:x}-{:x}", std::process::id())
}

/// Where a file went on the phone: its name there — "report (2).pdf" when
/// one was there already — and its path; or, `staged`, waiting in the
/// launcher's cache for the app it was dropped on.
struct Landed {
    name: String,
    path: String,
    staged: bool,
}

/// What the window says while a drop goes; `to` is the app the file goes to, if it does.
fn progress(item: &Item, i: usize, total: usize, pct: u32, to: Option<&str>) -> String {
    if item.install {
        return format!("Installing {}…", short(&item.name));
    }
    let to = to.filter(|a| !a.is_empty()).unwrap_or("your phone");
    if total == 1 {
        format!("Sending {} to {to}… {pct}%", short(&item.name))
    } else {
        format!("Sending {} of {total} to {to}… {pct}%", i + 1)
    }
}

/// How a drop on the app went, once its files were staged.
enum Given {
    /// The app took them. Those it had not opened a few seconds later are
    /// in Download as well, `unopened`: each by its name there, or why not.
    Taken { unopened: Vec<Result<String, String>> },
    /// They are in Download instead, each by its name there or why not:
    /// `refused` by the app, or no drop could be made there at all.
    Elsewhere { refused: bool, landed: Vec<Result<String, String>> },
}

/// What the window says when a drop on the app went one way for all of it:
/// `names`, as dropped, taken by `app` — or, with `in_download` (their names
/// there), not taken and in Download.
fn given_outcome(app: &str, names: &[String], in_download: Option<&[String]>) -> String {
    let (app, starting) = if app.is_empty() { ("the app", "The app") } else { (app, app) };
    match (in_download, names) {
        (None, [one]) => format!("Dropped {} into {app}", short(one)),
        (None, _) => format!("Dropped {} files into {app}", names.len()),
        (Some(there), [one]) => {
            let name = short(there.first().unwrap_or(one));
            format!("{starting} didn't take {name}: it's in Downloads on your phone")
        }
        (Some(_), _) => format!("{starting} didn't take them: {} files are in Downloads on your phone", names.len()),
    }
}

/// What the window says when `app` took a drop of `names` and, by the time
/// the phone looked, had not opened `unopened` (their names in Download,
/// where they went as well): an app may take a drop and use only some of it.
fn taken_outcome(app: &str, names: &[String], unopened: &[String]) -> String {
    if unopened.is_empty() {
        return given_outcome(app, names, None);
    }
    let (app, starting) = if app.is_empty() { ("the app", "The app") } else { (app, app) };
    let (all, there) = (names.len(), unopened.len());
    match unopened {
        [name] if all == 1 => format!("{starting} hasn't opened {}: it's in Downloads on your phone", short(name)),
        _ if there >= all => format!("{starting} hasn't opened them: {there} files are in Downloads on your phone"),
        [name] => format!("Dropped {} of {all} files into {app}; {} is in Downloads on your phone", all - 1, short(name)),
        _ => format!("Dropped {} of {all} files into {app}; the other {there} are in Downloads on your phone", all - there),
    }
}

/// The staged files of `batch` dropped on the app in `on`'s window: how it went.
fn drop_in(serial: &str, socket: &str, on: &On, batch: &str, files: &[(usize, String)]) -> Result<Given, String> {
    let mut s = adbsock::connect(serial, socket).map_err(|_| "your phone isn't answering".to_string())?;
    let files: Vec<Value> = files.iter().map(|(i, name)| json!({"i": i, "name": name})).collect();
    let ask = json!({"t": "dropIn", "window": on.key, "batch": batch, "x": on.x, "y": on.y, "files": files});
    s.write_all(&wire::json(&ask)).map_err(|e| e.to_string())?;
    // The drop is a second, and a few more while the phone sees which files the
    // app opens; what it did not take or open is copied to Download before the answer.
    let _ = s.set_read_timeout(Some(RESULT_TIMEOUT));
    loop {
        let m = match wire::read(&mut s) {
            Ok(Some(m)) => m,
            _ => return Err("your phone stopped answering".into()),
        };
        if m.kind != wire::JSON {
            continue;
        }
        let Ok(v) = serde_json::from_slice::<Value>(&m.data) else { continue };
        if v["t"].as_str() != Some("dropIn") {
            continue;
        }
        if v["ok"].as_bool() != Some(true) {
            return Err(v["message"].as_str().unwrap_or("the phone said no").to_string());
        }
        // In Download: all of them when the app did not take them, those it hasn't opened when it did.
        let landed = v["files"]
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|f| {
                        if f["ok"].as_bool() == Some(true) {
                            Ok(f["name"].as_str().unwrap_or("").to_string())
                        } else {
                            Err(f["message"].as_str().unwrap_or("it couldn't be saved").to_string())
                        }
                    })
                    .collect()
            })
            .unwrap_or_default();
        if v["taken"].as_bool() == Some(true) {
            return Ok(Given::Taken { unopened: landed });
        }
        return Ok(Given::Elsewhere { refused: v["why"].as_str() == Some("refused"), landed });
    }
}

/// What the window says once a drop is done: `landed` holds the names the
/// files got on the phone, which a file dropped on its own is called by.
fn outcome(items: &[Item], landed: &[String], error: Option<&str>) -> String {
    let total = items.len();
    let installs = items.iter().filter(|i| i.install).count();
    if total == 1 {
        let item = &items[0];
        let name = short(landed.first().unwrap_or(&item.name));
        return match (landed.len(), item.install) {
            (1, true) => format!("{name} is installed"),
            (1, false) => format!("{name} is in Downloads on your phone"),
            (_, true) => format!("{name} couldn't be installed: {}", install_failure(error.unwrap_or(""))),
            (_, false) => format!("{name} couldn't be sent: {}", error.unwrap_or("")),
        };
    }
    let landed = landed.len();
    match (landed == total, installs) {
        (true, n) if n == total => format!("{total} apps are installed"),
        (true, 0) => format!("{total} files are in Downloads on your phone"),
        (true, _) => format!("All {total} are on your phone"),
        (false, _) if landed == 0 => format!("None of the {total} could be sent: {}", error.unwrap_or("")),
        (false, _) => format!("{landed} of {total} are on your phone; the rest couldn't be sent"),
    }
}

/// A file's name short enough to leave room for the rest of the sentence:
/// its start and its end, the extension kept.
fn short(name: &str) -> String {
    const MOST: usize = 36;
    let chars: Vec<char> = name.chars().collect();
    if chars.len() <= MOST {
        return name.to_string();
    }
    let head: String = chars[..20].iter().collect();
    let tail: String = chars[chars.len() - 14..].iter().collect();
    format!("{head}…{tail}")
}

/// What the phone's installer said, as a person would say it. Its own words
/// when they are not one of the usual reasons.
fn install_failure(said: &str) -> String {
    let code = said
        .split(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
        .find(|w| w.starts_with("INSTALL_"))
        .unwrap_or("");
    match code {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" => "another copy of it is installed, signed by someone else".into(),
        "INSTALL_FAILED_VERSION_DOWNGRADE" => "a newer version is installed".into(),
        "INSTALL_FAILED_OLDER_SDK" => "it needs a newer Android".into(),
        "INSTALL_FAILED_NO_MATCHING_ABIS" => "it isn't made for this phone's processor".into(),
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" => "the phone is out of space".into(),
        "INSTALL_FAILED_USER_RESTRICTED" => "the phone doesn't allow installs from a computer (on Xiaomi: Developer options → Install via USB)".into(),
        "INSTALL_FAILED_TEST_ONLY" => "it's a test build, which the phone won't install this way".into(),
        c if c.starts_with("INSTALL_PARSE_FAILED") || c == "INSTALL_FAILED_INVALID_APK" => "it isn't a valid app".into(),
        // What Android 12's installer throws for a file that is no APK at all.
        _ if said.contains("Failed to parse") => "it isn't a valid app".into(),
        _ if said.is_empty() => "the phone didn't say why".into(),
        _ => said.to_string(),
    }
}

/// One file to the phone: where it landed there, or why not. With `stage`
/// (its drop's batch, and its place in it), into the launcher's cache for the
/// app it was dropped on — a launcher too old for that puts it in Download,
/// and says where. `got` hears how many bytes the phone has, as it says so.
fn put(serial: &str, socket: &str, item: &Item, stage: Option<(&str, usize)>, mut got: impl FnMut(u64)) -> Result<Landed, String> {
    let mut f = File::open(&item.local).map_err(|e| format!("this computer could not read it ({e})"))?;
    let mut s = adbsock::connect(serial, socket).map_err(|_| "your phone isn't answering".to_string())?;
    let mut hello = json!({"t": "put", "name": item.name, "dir": item.dir, "size": item.size, "install": item.install});
    if let Some((batch, i)) = stage {
        hello["stage"] = json!(batch);
        hello["i"] = json!(i);
    }
    s.write_all(&wire::json(&hello)).map_err(|e| e.to_string())?;
    // The phone's side, read as it comes: progress, then how it went.
    let (tx, rx) = mpsc::channel::<Value>();
    let mut reader = s.try_clone().map_err(|e| e.to_string())?;
    thread::spawn(move || {
        while let Ok(Some(m)) = wire::read(&mut reader) {
            if m.kind != wire::JSON {
                continue;
            }
            let Ok(v) = serde_json::from_slice::<Value>(&m.data) else { continue };
            if tx.send(v).is_err() {
                break;
            }
        }
    });
    // A result before the last byte is the phone giving up (no room, no folder):
    // its reason, not the broken connection that follows it, is what is said.
    let answer = |v: &Value| -> Option<Result<Landed, String>> {
        if v["t"].as_str() != Some("put") {
            return None;
        }
        Some(if v["ok"].as_bool() == Some(true) {
            let name = v["name"].as_str().filter(|n| !n.is_empty()).unwrap_or(&item.name).to_string();
            let path = v["path"].as_str().unwrap_or(&name).to_string();
            Ok(Landed { name, path, staged: v["staged"].as_bool() == Some(true) })
        } else {
            Err(v["message"].as_str().unwrap_or("the phone said no").to_string())
        })
    };
    let mut buf = vec![0u8; CHUNK];
    let mut sent = 0u64;
    while sent < item.size {
        let want = CHUNK.min((item.size - sent) as usize);
        let n = match f.read(&mut buf[..want]) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) => return Err(format!("this computer could not read it ({e})")),
        };
        if s.write_all(&buf[..n]).is_err() {
            // The phone hung up: what it said before it did, if anything.
            return match rx.recv_timeout(Duration::from_secs(2)).ok().and_then(|v| answer(&v)) {
                Some(r) => r,
                None => Err("your phone stopped answering".into()),
            };
        }
        sent += n as u64;
        for v in rx.try_iter() {
            if let Some(r) = answer(&v) {
                return r;
            }
            if let Some(b) = v["bytes"].as_u64() {
                got(b);
            }
        }
    }
    if sent < item.size {
        // Shorter than it was when it was dropped: the phone, waiting for the
        // rest, gets the end of the connection instead, and throws its part away.
        let _ = s.shutdown(std::net::Shutdown::Both);
        return Err("it changed while it was being sent".into());
    }
    loop {
        match rx.recv_timeout(RESULT_TIMEOUT) {
            Ok(v) => {
                if let Some(r) = answer(&v) {
                    return r;
                }
                if let Some(b) = v["bytes"].as_u64() {
                    got(b);
                }
            }
            Err(_) => return Err("your phone stopped answering".into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("oadx-drop-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn a_folder_brings_its_files_in_folders_of_the_same_names() {
        let d = scratch("plan");
        std::fs::create_dir_all(d.join("trip").join("raw")).unwrap();
        std::fs::write(d.join("trip").join("b.jpg"), b"bb").unwrap();
        std::fs::write(d.join("trip").join("raw").join("a.dng"), b"a").unwrap();
        std::fs::write(d.join("app.APK"), b"apk").unwrap();
        let (items, skipped) = plan(&[d.join("trip"), d.join("app.APK"), d.join("missing.txt")]);
        let got: Vec<(String, String, u64, bool)> =
            items.iter().map(|i| (i.dir.clone(), i.name.clone(), i.size, i.install)).collect();
        assert_eq!(
            got,
            [
                ("trip".into(), "b.jpg".into(), 2, false),
                ("trip/raw".into(), "a.dng".into(), 1, false),
                ("".into(), "app.APK".into(), 3, true),
            ]
        );
        assert_eq!(skipped, 1);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn an_apk_in_a_folder_is_copied_not_installed() {
        let d = scratch("apk-in-folder");
        std::fs::create_dir_all(d.join("apps")).unwrap();
        std::fs::write(d.join("apps").join("x.apk"), b"x").unwrap();
        let (items, _) = plan(&[d.join("apps")]);
        assert!(!items[0].install);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_title_bar_says_how_it_went() {
        let file = |name: &str, install: bool| Item { local: PathBuf::new(), name: name.into(), dir: String::new(), size: 1, install };
        assert_eq!(progress(&file("a.mp4", false), 0, 1, 42, None), "Sending a.mp4 to your phone… 42%");
        assert_eq!(progress(&file("a.mp4", false), 1, 3, 7, None), "Sending 2 of 3 to your phone… 7%");
        assert_eq!(progress(&file("x.apk", true), 0, 1, 50, None), "Installing x.apk…");
        // Dropped on the app: to the app, by its name.
        assert_eq!(progress(&file("a.mp4", false), 0, 1, 42, Some("WhatsApp")), "Sending a.mp4 to WhatsApp… 42%");
        assert_eq!(progress(&file("x.apk", true), 0, 2, 9, Some("WhatsApp")), "Installing x.apk…");
        let on = |names: &[&str]| names.iter().map(|n| n.to_string()).collect::<Vec<_>>();
        assert_eq!(outcome(&[file("a.mp4", false)], &on(&["a.mp4"]), None), "a.mp4 is in Downloads on your phone");
        // Its name on the phone, when another was there already.
        assert_eq!(outcome(&[file("a.mp4", false)], &on(&["a (2).mp4"]), None), "a (2).mp4 is in Downloads on your phone");
        assert_eq!(outcome(&[file("x.apk", true)], &on(&["x.apk"]), None), "x.apk is installed");
        assert_eq!(
            outcome(&[file("x.apk", true)], &[], Some("Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected]")),
            "x.apk couldn't be installed: a newer version is installed"
        );
        assert_eq!(outcome(&[file("a", false), file("b", false)], &on(&["a", "b"]), None), "2 files are in Downloads on your phone");
        assert_eq!(
            outcome(&[file("a", false), file("b", false), file("c", false)], &on(&["a", "b"]), Some("no room")),
            "2 of 3 are on your phone; the rest couldn't be sent"
        );
    }

    #[test]
    fn a_drop_on_the_app_says_where_it_went() {
        let names = |n: &[&str]| n.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(given_outcome("WhatsApp", &names(&["a.jpg"]), None), "Dropped a.jpg into WhatsApp");
        assert_eq!(given_outcome("WhatsApp", &names(&["a.jpg", "b.pdf"]), None), "Dropped 2 files into WhatsApp");
        // Not taken: in Download, by the name it got there.
        assert_eq!(
            given_outcome("Settings", &names(&["a.jpg"]), Some(&names(&["a (2).jpg"]))),
            "Settings didn't take a (2).jpg: it's in Downloads on your phone"
        );
        assert_eq!(
            given_outcome("Settings", &names(&["a.jpg", "b.pdf"]), Some(&names(&["a.jpg", "b.pdf"]))),
            "Settings didn't take them: 2 files are in Downloads on your phone"
        );
        assert_eq!(given_outcome("", &names(&["a.jpg"]), None), "Dropped a.jpg into the app");
        assert_eq!(given_outcome("", &names(&["a.jpg"]), Some(&names(&["a.jpg"]))), "The app didn't take a.jpg: it's in Downloads on your phone");
    }

    #[test]
    fn a_drop_the_app_used_part_of_says_where_the_rest_went() {
        let names = |n: &[&str]| n.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        // Every file opened: as any drop it took.
        assert_eq!(taken_outcome("WhatsApp", &names(&["a.jpg", "b.pdf"]), &[]), "Dropped 2 files into WhatsApp");
        // The photos opened, the PDF not: in Download, by the name it got there.
        assert_eq!(
            taken_outcome("WhatsApp", &names(&["a.jpg", "b.jpg", "c.pdf"]), &names(&["c (2).pdf"])),
            "Dropped 2 of 3 files into WhatsApp; c (2).pdf is in Downloads on your phone"
        );
        assert_eq!(
            taken_outcome("WhatsApp", &names(&["a.jpg", "b.pdf", "c.zip"]), &names(&["b.pdf", "c.zip"])),
            "Dropped 1 of 3 files into WhatsApp; the other 2 are in Downloads on your phone"
        );
        // A text field takes any drop, and reads only text.
        assert_eq!(
            taken_outcome("Settings", &names(&["logo.png"]), &names(&["logo.png"])),
            "Settings hasn't opened logo.png: it's in Downloads on your phone"
        );
        assert_eq!(
            taken_outcome("", &names(&["a.png", "b.png"]), &names(&["a.png", "b.png"])),
            "The app hasn't opened them: 2 files are in Downloads on your phone"
        );
    }

    #[test]
    fn a_long_name_keeps_its_start_and_its_end() {
        assert_eq!(short("holiday.mp4"), "holiday.mp4");
        assert_eq!(short("IMG_20231014_123456_HDR_edited_final_v2.jpg"), "IMG_20231014_123456_…d_final_v2.jpg");
    }

    #[test]
    fn an_installers_own_words_are_kept_when_they_are_not_the_usual() {
        assert_eq!(install_failure("Failure [INSTALL_PARSE_FAILED_NOT_APK: not an apk]"), "it isn't a valid app");
        assert_eq!(
            install_failure("Failed to parse APK file: /data/local/tmp/oadx-drop-1.apk: Failed to parse /data/local/tmp/oadx-drop-1.apk"),
            "it isn't a valid app"
        );
        assert_eq!(install_failure("Failure [SOMETHING_NEW]"), "Failure [SOMETHING_NEW]");
        assert_eq!(install_failure(""), "the phone didn't say why");
    }
}
