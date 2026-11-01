//! Files dropped on an app window, for the phone (doc/apps-on-pc.md,
//! "Dropping files").
//!
//! What a drop on the desktop does: the file goes into the phone's Download
//! folder, and an APK is installed instead. A folder brings the files in it,
//! in folders of the same names. Nothing is overwritten: a name already there
//! becomes "report (2).pdf", on the phone (AppDrop.java).
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
use std::time::Duration;

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

/// Send what was dropped to `serial`'s phone, through its app server on
/// `socket`, one file after another; `note` hears how it goes — a line to
/// show, whether more of it is coming, and whether anything was lost.
pub fn send(serial: String, socket: String, paths: Vec<PathBuf>, note: impl Fn(Note) + Send + 'static) {
    let _ = thread::Builder::new().name("apps-drop".into()).spawn(move || {
        let (items, skipped) = plan(&paths);
        if items.is_empty() {
            note(Note { text: "Only files and folders can be dropped here".into(), busy: false, failed: true });
            return;
        }
        let _one = sending().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let total = items.len();
        let mut landed = Vec::new();
        let mut last_error = None;
        for (i, item) in items.iter().enumerate() {
            let mut shown = -1i64;
            let r = put(&serial, &socket, item, |bytes| {
                let pct = if item.size == 0 { 100 } else { (bytes * 100 / item.size) as i64 };
                if pct != shown {
                    shown = pct;
                    note(Note { text: progress(item, i, total, pct as u32), busy: true, failed: false });
                }
            });
            match r {
                Ok(Landed { name, path }) => {
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
        note(Note {
            text: outcome(&items, &landed, last_error.as_deref()),
            busy: false,
            failed: landed.len() < items.len(),
        });
    });
}

/// Where a file went on the phone: its name there — "report (2).pdf" when
/// one was there already — and its path.
struct Landed {
    name: String,
    path: String,
}

/// What the window says while a drop goes.
fn progress(item: &Item, i: usize, total: usize, pct: u32) -> String {
    if item.install {
        return format!("Installing {}…", short(&item.name));
    }
    if total == 1 {
        format!("Sending {} to your phone… {pct}%", short(&item.name))
    } else {
        format!("Sending {} of {total} to your phone… {pct}%", i + 1)
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

/// One file to the phone: where it landed there, or why not. `got` hears
/// how many bytes the phone has, as it says so.
fn put(serial: &str, socket: &str, item: &Item, mut got: impl FnMut(u64)) -> Result<Landed, String> {
    let mut f = File::open(&item.local).map_err(|e| format!("this computer could not read it ({e})"))?;
    let mut s = adbsock::connect(serial, socket).map_err(|_| "your phone isn't answering".to_string())?;
    let hello = json!({"t": "put", "name": item.name, "dir": item.dir, "size": item.size, "install": item.install});
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
            Ok(Landed { name, path })
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
        assert_eq!(progress(&file("a.mp4", false), 0, 1, 42), "Sending a.mp4 to your phone… 42%");
        assert_eq!(progress(&file("a.mp4", false), 1, 3, 7), "Sending 2 of 3 to your phone… 7%");
        assert_eq!(progress(&file("x.apk", true), 0, 1, 50), "Installing x.apk…");
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
