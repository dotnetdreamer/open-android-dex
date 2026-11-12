//! Keep a copy's record of what it copied, kept in the folder itself
//! (doc/send-to-computer.md, "Keep a copy"): `.open-android-dex.json`, hidden,
//! beside the albums.
//!
//! In the folder, not in this app's settings, so it survives the app being
//! reinstalled, moves with the folder, and a folder renamed by hand is found
//! again — each sub-folder of the chosen place is looked in for a record of
//! this phone. The phone is named in it by a hash of its `ro.serialno`: a
//! phone renamed is still the same phone, and no serial number sits in a
//! folder another program may sync.
//!
//! A picture is known, in order: by its size, time and name; by its size and
//! time under another name (renamed on the phone); by what is in it — the
//! SHA-256 of its bytes (a phone restored, its times changed). Written through
//! a temporary file, flushed to the disk, and a rename; hidden on Windows as
//! well as by its dot. A record that is not JSON (a power cut's empty file)
//! is set aside as `.open-android-dex.json.bad`, never written over; one that
//! is there and cannot be read (a permission) stops Keep a copy for that
//! folder instead of starting it again; one from a newer build is left alone.
//! With no record — deleted, or set aside — what is already in the folder is
//! adopted, not saved a second time (keep.rs, `adopt`).

use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::photos::Photo;

pub const FILE: &str = ".open-android-dex.json";
/// The record's shape this build writes and reads.
pub const VERSION: u32 = 1;

/// One picture copied: where it is in the folder, and what it was on the phone.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Entry {
    /// Its path inside the phone's folder: "Camera/20261106_114611.jpg".
    pub file: String,
    /// Its name on the phone.
    pub name: String,
    pub size: u64,
    /// Its time on the phone, seconds.
    pub modified: i64,
    /// When it was taken, ms.
    pub taken: i64,
    pub sha256: String,
    /// When it was copied, seconds.
    pub at: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Record {
    pub v: u32,
    /// [`phone_key`] of the phone's `ro.serialno`.
    pub phone: String,
    /// What the phone was called when this was last written.
    pub name: String,
    pub items: Vec<Entry>,
}

/// How a picture on the phone stands against the record.
#[derive(Clone, Debug, PartialEq)]
pub enum Known {
    /// Copied: the same size, time and name.
    Same(usize),
    /// Copied, and renamed on the phone since: the same size and time.
    Renamed(usize),
    /// Not by its name or its time: its bytes decide.
    No,
}

impl Record {
    pub fn new(phone: &str, name: &str) -> Record {
        Record { v: VERSION, phone: phone.to_string(), name: name.to_string(), items: Vec::new() }
    }

    /// How `p` stands, walking the record: what [`Index::knows`] answers without walking it.
    #[cfg(test)]
    pub fn knows(&self, p: &Photo) -> Known {
        if let Some(i) = self.items.iter().position(|e| e.size == p.size && e.modified == p.modified && e.name == p.name) {
            return Known::Same(i);
        }
        if let Some(i) = self.items.iter().position(|e| e.size == p.size && e.modified == p.modified) {
            return Known::Renamed(i);
        }
        Known::No
    }

    /// The entry whose bytes hash to `sha256`.
    #[cfg(test)]
    pub fn by_hash(&self, sha256: &str) -> Option<&Entry> {
        self.items.iter().find(|e| e.sha256 == sha256)
    }

    pub fn add(&mut self, e: Entry) {
        self.items.push(e);
    }
}

/// The record looked up by size and time, and by hash, without walking it:
/// a picture per look, for each of thousands. Kept beside the record and told
/// of each entry added.
#[derive(Clone, Debug, Default)]
pub struct Index {
    by_time: HashMap<(u64, i64), Vec<usize>>,
    by_hash: HashMap<String, usize>,
    by_file: std::collections::HashSet<String>,
}

impl Index {
    pub fn of(r: &Record) -> Index {
        let mut ix = Index::default();
        for (i, e) in r.items.iter().enumerate() {
            ix.added(e, i);
        }
        ix
    }

    /// Entry `i`, `e`, now in the record.
    pub fn added(&mut self, e: &Entry, i: usize) {
        self.by_time.entry((e.size, e.modified)).or_default().push(i);
        self.by_hash.entry(e.sha256.clone()).or_insert(i);
        self.by_file.insert(e.file.clone());
    }

    /// Whether an entry already names `file` ("Camera/a.jpg"): a copy of another picture, or an earlier edit of this one.
    pub fn names(&self, file: &str) -> bool {
        self.by_file.contains(file)
    }

    /// How `p` stands against record `r`, which this indexes: copied with the
    /// same size, time and name; copied and renamed since (the same size and
    /// time); or not by either.
    pub fn knows(&self, r: &Record, p: &Photo) -> Known {
        let Some(at) = self.by_time.get(&(p.size, p.modified)) else { return Known::No };
        match at.iter().find(|&&i| r.items[i].name == p.name) {
            Some(&i) => Known::Same(i),
            None => at.first().map_or(Known::No, |&i| Known::Renamed(i)),
        }
    }

    pub fn by_hash<'a>(&self, r: &'a Record, sha256: &str) -> Option<&'a Entry> {
        self.by_hash.get(sha256).map(|&i| &r.items[i])
    }
}

/// Whether a record's `file` is a path inside the folder: relative, every part
/// an ordinary name — no "..", no root, no drive, no share, no stream. The
/// record sits in a folder anyone with access may write to, and its paths are
/// what Open and Show act on.
pub fn inside(file: &str) -> bool {
    !file.is_empty()
        && !file.contains(':')
        && !file.contains('\\')
        && Path::new(file).components().all(|c| matches!(c, Component::Normal(_)))
}

/// A phone, as a record names it: the first 16 hex digits of its serial's SHA-256.
pub fn phone_key(serialno: &str) -> String {
    use sha2::{Digest, Sha256};
    let h = Sha256::digest(format!("open-android-dex keep a copy\n{serialno}").as_bytes());
    h.iter().take(8).map(|b| format!("{b:02x}")).collect()
}

/// What opening a folder's record found.
#[derive(Debug, PartialEq)]
pub enum Opened {
    /// Its record, or a new one where there was none.
    Ready(Record),
    /// A newer build's record: left alone, and nothing is copied here.
    Newer,
    /// Another phone's record.
    Other,
    /// There, and not to be read (a permission, a disk error): nothing is copied here.
    Unreadable(String),
}

/// The record of `folder` for phone `key`, called `name`. One that cannot be
/// read is set aside, and a new one started.
pub fn open(folder: &Path, key: &str, name: &str) -> Opened {
    let path = folder.join(FILE);
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        // Only a record that is not there is no record: one that cannot be read now may be read later.
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Opened::Ready(Record::new(key, name)),
        Err(e) => return Opened::Unreadable(e.to_string()),
    };
    // The version first: a newer shape need not parse as this one.
    let v = serde_json::from_slice::<serde_json::Value>(&bytes).ok();
    if let Some(n) = v.as_ref().and_then(|v| v["v"].as_u64()) {
        if n > VERSION as u64 {
            return Opened::Newer;
        }
    }
    match serde_json::from_slice::<Record>(&bytes) {
        Ok(r) if r.phone == key => Opened::Ready(r),
        Ok(_) => Opened::Other,
        Err(_) => {
            // Under a name nothing has: one set aside before is never written over.
            let mut names = (1..1000).map(|n| folder.join(if n == 1 { format!("{FILE}.bad") } else { format!("{FILE}.{n}.bad") }));
            let Some(bad) = names.find(|p| std::fs::symlink_metadata(p).is_err()) else {
                return Opened::Unreadable("there is no name left to set it aside under".into());
            };
            // Not set aside, it is not started again: a new record would be written over it.
            if let Err(e) = std::fs::rename(&path, &bad) {
                return Opened::Unreadable(e.to_string());
            }
            log::warn!("keep: the record in {} could not be read; set aside as {}", folder.display(), bad.display());
            Opened::Ready(Record::new(key, name))
        }
    }
}

/// The record written whole, or not at all: a temporary file, on the disk
/// before it is renamed over the last one — a power cut leaves one or the
/// other, never an empty record.
pub fn write(folder: &Path, r: &Record) -> std::io::Result<()> {
    use std::io::Write;
    let path = folder.join(FILE);
    let tmp = folder.join(format!("{FILE}.tmp"));
    let json = serde_json::to_vec_pretty(r).map_err(std::io::Error::other)?;
    let mut o = std::fs::OpenOptions::new();
    // Made here, never opened through what is at its name: the folder may be one others can write to,
    // and a link left there would have this write into the file it names.
    o.write(true).create_new(true);
    #[cfg(windows)]
    {
        // Hidden there as well: a dot hides nothing in Explorer.
        use std::os::windows::fs::OpenOptionsExt;
        o.attributes(0x2);
    }
    let mut f = match o.open(&tmp) {
        // An earlier write's, cut short: the name is taken away — never what a link there names — and made again.
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
            std::fs::remove_file(&tmp)?;
            o.open(&tmp)?
        }
        r => r?,
    };
    f.write_all(&json)?;
    f.sync_all()?;
    drop(f);
    std::fs::rename(&tmp, &path)?;
    #[cfg(unix)]
    if let Ok(d) = std::fs::File::open(folder) {
        let _ = d.sync_all();
    }
    Ok(())
}

/// Whether `folder` itself holds phone `key`'s record.
pub fn holds(folder: &Path, key: &str) -> bool {
    std::fs::read(folder.join(FILE))
        .ok()
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
        .is_some_and(|v| v["phone"].as_str() == Some(key))
}

/// The sub-folder of `root` holding phone `key`'s record: a folder renamed by hand, found again.
pub fn find(root: &Path, key: &str) -> Option<PathBuf> {
    let entries = std::fs::read_dir(root).ok()?;
    for e in entries.flatten() {
        let p = e.path();
        if !p.is_dir() {
            continue;
        }
        let Ok(bytes) = std::fs::read(p.join(FILE)) else { continue };
        let Ok(v) = serde_json::from_slice::<serde_json::Value>(&bytes) else { continue };
        if v["phone"].as_str() == Some(key) {
            return Some(p);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(name: &str, size: u64, modified: i64) -> Photo {
        Photo {
            id: 1,
            name: name.into(),
            album: "Camera".into(),
            path: format!("DCIM/Camera/{name}"),
            taken: modified * 1000,
            added: modified,
            modified,
            size,
            w: 1,
            h: 1,
            mime: "image/jpeg".into(),
        }
    }

    fn entry(name: &str, size: u64, modified: i64, sha: &str) -> Entry {
        Entry { file: format!("Camera/{name}"), name: name.into(), size, modified, taken: 0, sha256: sha.into(), at: 0 }
    }

    fn scratch(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("oadx-ledger-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn the_same_picture_is_not_kept_twice() {
        let mut r = Record::new("k", "Galaxy");
        r.add(entry("a.jpg", 100, 5, "h1"));
        assert_eq!(r.knows(&photo("a.jpg", 100, 5)), Known::Same(0));
        assert_eq!(r.knows(&photo("a.jpg", 101, 5)), Known::No);
    }

    #[test]
    fn a_picture_renamed_on_the_phone_is_the_same_picture() {
        let mut r = Record::new("k", "Galaxy");
        r.add(entry("a.jpg", 100, 5, "h1"));
        assert_eq!(r.knows(&photo("holiday.jpg", 100, 5)), Known::Renamed(0));
    }

    #[test]
    fn a_restored_phone_s_pictures_are_known_by_what_is_in_them() {
        let mut r = Record::new("k", "Galaxy");
        r.add(entry("a.jpg", 100, 5, "abc123"));
        // Restored: a new time, so neither name nor time says it — the hash does.
        assert_eq!(r.knows(&photo("a.jpg", 100, 999)), Known::No);
        assert_eq!(r.by_hash("abc123").map(|e| e.file.as_str()), Some("Camera/a.jpg"));
        assert!(r.by_hash("zzz").is_none());
    }

    #[test]
    fn a_different_picture_with_the_same_name_gets_a_number() {
        let d = scratch("samename");
        std::fs::write(d.join("a.jpg"), b"one").unwrap();
        let r = super::super::inbox::reserve(&d, "a.jpg").unwrap();
        assert_eq!(r.final_path, d.join("a (2).jpg"));
        r.abandon();
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_record_is_found_in_a_folder_renamed_by_hand() {
        let root = scratch("find");
        let folder = root.join("My old phone");
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::create_dir_all(root.join("Somebody else")).unwrap();
        write(&root.join("Somebody else"), &Record::new("other", "Pixel")).unwrap();
        write(&folder, &Record::new("mine", "Galaxy")).unwrap();
        assert_eq!(find(&root, "mine"), Some(folder.clone()));
        assert_eq!(find(&root, "nobody"), None);
        assert_eq!(open(&root.join("Somebody else"), "mine", "Galaxy"), Opened::Other);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_index_knows_what_the_record_knows() {
        let mut r = Record::new("k", "Galaxy");
        r.add(entry("a.jpg", 100, 5, "h1"));
        r.add(entry("b.jpg", 100, 5, "h2"));
        r.add(entry("c.jpg", 7, 9, "h3"));
        let mut ix = Index::of(&r);
        for p in [photo("a.jpg", 100, 5), photo("b.jpg", 100, 5), photo("renamed.jpg", 100, 5), photo("c.jpg", 7, 9), photo("c.jpg", 8, 9), photo("x", 1, 1)] {
            assert_eq!(ix.knows(&r, &p), r.knows(&p), "{}", p.name);
        }
        let e = entry("d.jpg", 3, 3, "h4");
        r.add(e.clone());
        ix.added(&e, 3);
        assert_eq!(ix.knows(&r, &photo("d.jpg", 3, 3)), Known::Same(3));
        assert_eq!(ix.by_hash(&r, "h2").map(|e| e.name.as_str()), Some("b.jpg"));
        assert!(ix.by_hash(&r, "zz").is_none());
    }

    #[test]
    fn a_records_path_stays_inside_its_folder() {
        assert!(inside("Camera/20261106_114611.jpg"));
        assert!(inside("Pictures/a b (2).jpg"));
        for bad in ["", "../../../x.command", "Camera/../../x", "/etc/passwd", "\\\\host\\share\\x.exe", "C:\\x.exe", "C:x.exe",
                    "Camera/x.jpg:stream", "./x.jpg"] {
            assert!(!inside(bad), "{bad}");
        }
    }

    #[test]
    fn a_record_there_and_not_readable_is_not_started_again() {
        let d = scratch("unreadable");
        // A folder where the record should be: there, and not to be read as a file.
        std::fs::create_dir_all(d.join(FILE)).unwrap();
        assert!(matches!(open(&d, "k", "Galaxy"), Opened::Unreadable(_)));
        assert!(d.join(FILE).is_dir(), "left as it was");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_record_that_cannot_be_read_is_set_aside_not_written_over() {
        let d = scratch("bad");
        std::fs::write(d.join(FILE), b"{ not json").unwrap();
        assert_eq!(open(&d, "k", "Galaxy"), Opened::Ready(Record::new("k", "Galaxy")));
        assert_eq!(std::fs::read(d.join(format!("{FILE}.bad"))).unwrap(), b"{ not json");
        assert!(!d.join(FILE).exists());
        // A second one: set aside beside the first, never over it.
        std::fs::write(d.join(FILE), b"").unwrap();
        assert_eq!(open(&d, "k", "Galaxy"), Opened::Ready(Record::new("k", "Galaxy")));
        assert_eq!(std::fs::read(d.join(format!("{FILE}.bad"))).unwrap(), b"{ not json");
        assert_eq!(std::fs::read(d.join(format!("{FILE}.2.bad"))).unwrap(), b"");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    #[cfg(unix)]
    fn a_link_at_the_records_temporary_name_is_never_written_through() {
        let d = scratch("tmplink");
        let elsewhere = d.join("someone's.txt");
        std::fs::write(&elsewhere, b"keep me").unwrap();
        std::os::unix::fs::symlink(&elsewhere, d.join(format!("{FILE}.tmp"))).unwrap();
        let r = Record::new("k", "Galaxy");
        write(&d, &r).unwrap();
        assert_eq!(std::fs::read(&elsewhere).unwrap(), b"keep me");
        assert_eq!(open(&d, "k", "Galaxy"), Opened::Ready(r));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_newer_record_is_left_alone() {
        let d = scratch("newer");
        let newer = br#"{"v": 2, "phone": "k", "shape": "different"}"#;
        std::fs::write(d.join(FILE), newer).unwrap();
        assert_eq!(open(&d, "k", "Galaxy"), Opened::Newer);
        assert_eq!(std::fs::read(d.join(FILE)).unwrap(), newer);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn it_is_written_whole_or_not_at_all() {
        let d = scratch("whole");
        let mut r = Record::new("k", "Galaxy");
        r.add(entry("a.jpg", 1, 2, "h"));
        write(&d, &r).unwrap();
        assert_eq!(open(&d, "k", "Galaxy"), Opened::Ready(r.clone()));
        assert!(!d.join(format!("{FILE}.tmp")).exists());
        // A write that cannot finish leaves the last whole record where it was.
        std::fs::create_dir_all(d.join(format!("{FILE}.tmp"))).unwrap();
        r.add(entry("b.jpg", 1, 3, "h2"));
        assert!(write(&d, &r).is_err());
        let Opened::Ready(back) = open(&d, "k", "Galaxy") else { panic!() };
        assert_eq!(back.items.len(), 1);
        let _ = std::fs::remove_dir_all(&d);
        assert_eq!(phone_key("RFCY90YWS1P").len(), 16);
        assert_ne!(phone_key("RFCY90YWS1P"), phone_key("RFCY10FRKBK"));
    }
}
