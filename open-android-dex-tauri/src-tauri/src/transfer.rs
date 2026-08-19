//! Files dragged from the desktop onto the mirror window.
//!
//! The drop target is scrcpy's own SDL window — it is the desktop, and it
//! already knows how to take a drop: a dropped file is `adb push`ed to
//! [`PUSH_TARGET`], a dropped APK is installed. What scrcpy does *not* do is
//! tell anyone about it. The whole transfer is six lines on its stderr:
//!
//! ```text
//! INFO: Request to push C:\Users\x\holiday.mp4      (at drop time, one per file)
//! INFO: Pushing C:\Users\x\holiday.mp4...           (when its turn comes)
//! INFO: C:\Users\x\holiday.mp4 successfully pushed to /sdcard/Download/
//! ```
//!
//! This module turns those back into a transfer someone can watch: what is in
//! the batch (the "Request to" lines all arrive at drop time, so the total is
//! known before the first byte moves), which file is in flight, and how far
//! along it is — the phone is asked how big the destination file has grown,
//! because `adb push` writes straight to the target path and scrcpy never
//! reports progress itself.
//!
//! The HUD that shows all this lives in the launcher, on the phone: this
//! window is a video stream of a desktop that is over there, so anything drawn
//! here would sit outside it. See `Enforcer::transfer_pass` for the broadcast
//! and `TransferHud.java` for the other end.
//!
//! Paths with spaces are safe on both platforms, by different routes: on
//! Windows scrcpy quotes the local path before handing it to adb
//! (`sc_adb_push`), even though it quotes nothing else; on macOS its posix
//! backend passes argv as an array, so there is no command line to split in the
//! first place.

use std::collections::HashMap;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

/// Where a dropped file lands. This is scrcpy's own default, but it is passed
/// explicitly (`--push-target`) because both ends need to name it: the poll
/// below stats the file there, and the HUD tells the user which folder to look
/// in.
pub const PUSH_TARGET: &str = "/sdcard/Download/";

/// How long after the last file of a batch finished a newly dropped one still
/// counts as part of it.
///
/// A multi-file drop needs no window at all — scrcpy queues every file from
/// the drop before pushing any of them, so the batch is complete before the
/// first byte moves. This only covers the race where a tiny first file lands
/// before the event loop has taken the next file's drop event, which would
/// otherwise split one drop into two HUDs.
const BATCH_SETTLE: Duration = Duration::from_millis(700);

#[derive(Clone, Copy, PartialEq, Eq)]
enum Stage {
    Queued,
    Active,
    Done,
    Failed,
}

struct Item {
    /// The local path exactly as scrcpy printed it. Every later line about
    /// this file names it the same way, so it is the key.
    local: String,
    /// File name alone — what the HUD shows and what the file is called on
    /// the phone.
    name: String,
    /// Local size in bytes; 0 when it could not be measured (a directory, or
    /// a file that vanished), which is the HUD's cue to show no percentage.
    size: u64,
    /// An APK, which scrcpy installs rather than copies.
    install: bool,
    stage: Stage,
}

#[derive(Default)]
struct Batch {
    items: Vec<Item>,
    /// Bytes of the file in flight that have reached the phone.
    bytes: u64,
    /// When the last unfinished item of this batch finished.
    settled: Option<Instant>,
}

impl Batch {
    fn unfinished(&self) -> bool {
        self.items
            .iter()
            .any(|i| matches!(i.stage, Stage::Queued | Stage::Active))
    }

    /// Find the item a log line is about. Exact match first; `ends_with`
    /// covers a line that arrived with something glued to its front —
    /// `adb push` inherits scrcpy's stderr and writes its progress without a
    /// trailing newline, so its last chunk can share a line with the message
    /// that follows it.
    fn find(&mut self, path: &str) -> Option<usize> {
        if let Some(ix) = self.items.iter().position(|i| i.local == path) {
            return Some(ix);
        }
        self.items.iter().position(|i| path.ends_with(&i.local))
    }
}

/// Per session (`<serial>|desktop`), because the log lines that feed this
/// arrive on that session's output reader.
fn batches() -> &'static Mutex<HashMap<String, Batch>> {
    static BATCHES: OnceLock<Mutex<HashMap<String, Batch>>> = OnceLock::new();
    BATCHES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// scrcpy prefixes its own messages with the log level; `adb`, whose output
/// lands on the same pipe, prefixes nothing.
fn marker<'a>(line: &'a str, needle: &str) -> Option<&'a str> {
    line.find(needle).map(|ix| &line[ix + needle.len()..])
}

/// The last component of a HOST path, for the label in the transfer HUD.
///
/// The separator set is the host's, not both: a backslash is an ordinary
/// character in a macOS filename, so treating it as a separator there would
/// show `holiday\2019.mp4` as `2019.mp4` — a name the user cannot find in
/// their Downloads folder.
fn file_name(path: &str) -> String {
    const SEPARATORS: &[char] = if cfg!(windows) { &['\\', '/'] } else { &['/'] };
    let cut = path.rfind(SEPARATORS).map_or(0, |ix| ix + 1);
    path[cut..].to_string()
}

/// Feed one line of scrcpy's output. Everything that is not about a drop
/// falls through after a handful of substring searches.
pub fn observe(key: &str, line: &str) {
    // Most specific first. These markers are searched for anywhere in the
    // line rather than anchored, so a file called "Installing notes.pdf"
    // would otherwise match the wrong shape of message.
    let (path, install, event) = if let Some((file, _)) =
        line.split_once(" successfully pushed to ")
    {
        (file.trim(), false, Event::Finished(true))
    } else if let Some(rest) = line.strip_suffix(" successfully installed") {
        (rest.trim(), true, Event::Finished(true))
    } else if let Some(rest) = marker(line, "Failed to push ") {
        (
            rest.rsplit_once(" to ").map_or(rest, |(f, _)| f).trim(),
            false,
            Event::Finished(false),
        )
    } else if let Some(rest) = marker(line, "Failed to install ") {
        (rest.trim(), true, Event::Finished(false))
    } else if let Some(rest) = marker(line, "Request to push ") {
        (rest.trim(), false, Event::Queued)
    } else if let Some(rest) = marker(line, "Request to install ") {
        (rest.trim(), true, Event::Queued)
    } else if let Some(rest) = marker(line, "Pushing ") {
        (rest.trim().trim_end_matches("..."), false, Event::Started)
    } else if let Some(rest) = marker(line, "Installing ") {
        (rest.trim().trim_end_matches("..."), true, Event::Started)
    } else {
        return;
    };
    if path.is_empty() {
        return;
    }

    let mut map = batches().lock().unwrap();
    let batch = map.entry(key.to_string()).or_default();
    match event {
        Event::Queued => {
            // A drop that arrives while nothing is pending starts a fresh
            // batch, so the HUD counts this drop rather than the last one too.
            let stale = !batch.unfinished()
                && batch
                    .settled
                    .map(|t| t.elapsed() > BATCH_SETTLE)
                    .unwrap_or(true);
            if stale {
                *batch = Batch::default();
            }
            if batch.find(path).is_some() {
                return;
            }
            let size = std::fs::metadata(Path::new(path))
                .ok()
                .filter(|m| m.is_file())
                .map(|m| m.len())
                .unwrap_or(0);
            batch.items.push(Item {
                local: path.to_string(),
                name: file_name(path),
                size,
                install,
                stage: Stage::Queued,
            });
            log::info!(
                "transfer [{key}] queued {} ({} bytes){}",
                file_name(path),
                size,
                if install { ", as an install" } else { "" }
            );
        }
        Event::Started => {
            // Belt and braces: scrcpy always logs the request first, but a
            // "Pushing" for a file we never saw queued still gets a row
            // rather than a silent transfer.
            if batch.find(path).is_none() {
                let size = std::fs::metadata(Path::new(path))
                    .ok()
                    .filter(|m| m.is_file())
                    .map(|m| m.len())
                    .unwrap_or(0);
                batch.items.push(Item {
                    local: path.to_string(),
                    name: file_name(path),
                    size,
                    install,
                    stage: Stage::Queued,
                });
            }
            if let Some(ix) = batch.find(path) {
                batch.items[ix].stage = Stage::Active;
                batch.bytes = 0;
            }
        }
        Event::Finished(ok) => {
            if let Some(ix) = batch.find(path) {
                batch.items[ix].stage = if ok { Stage::Done } else { Stage::Failed };
                batch.bytes = 0;
                if !batch.unfinished() {
                    batch.settled = Some(Instant::now());
                }
                log::info!(
                    "transfer [{key}] {} {}",
                    batch.items[ix].name,
                    if ok { "landed" } else { "FAILED" }
                );
            }
        }
    }
}

enum Event {
    Queued,
    Started,
    Finished(bool),
}

/// What the HUD should be showing, or `None` when this session has never had
/// a drop.
pub struct Snapshot {
    /// Every file of the batch has finished, one way or the other.
    pub done: bool,
    /// The file in flight, or the last one to finish.
    pub name: String,
    /// 1-based position of `name` in the batch.
    pub index: u32,
    pub total: u32,
    pub ok: u32,
    pub failed: u32,
    /// The batch is an APK install rather than a copy.
    pub install: bool,
    /// 0-100, or -1 when the size is unknown (a folder) and the HUD should
    /// show an indeterminate bar.
    pub pct: i32,
    /// Device path of the file in flight, for the size poll. Empty when
    /// nothing is in flight or the file is being installed rather than copied.
    pub active_dest: String,
    /// Names that landed, for the media scan that makes them visible to the
    /// phone's file manager.
    pub landed: Vec<String>,
}

pub fn snapshot(key: &str) -> Option<Snapshot> {
    let map = batches().lock().unwrap();
    let batch = map.get(key)?;
    if batch.items.is_empty() {
        return None;
    }
    let active = batch.items.iter().position(|i| i.stage == Stage::Active);
    // Once everything has finished the HUD keeps naming the last file, not
    // the first: "holiday.mp4 copied" is the sentence a one-file drop wants.
    let focus = active.unwrap_or(batch.items.len() - 1);
    let item = &batch.items[focus];
    let done = !batch.unfinished();
    let pct = if done {
        100
    } else if active.is_none() || item.size == 0 {
        -1
    } else {
        // Never 100 before the success line: the last block is written and
        // then the file is still being closed, and a bar that sits at 100%
        // while the HUD says "copying" reads as stuck.
        ((batch.bytes.min(item.size) * 100 / item.size) as i32).min(99)
    };
    Some(Snapshot {
        done,
        name: item.name.clone(),
        index: focus as u32 + 1,
        total: batch.items.len() as u32,
        ok: batch
            .items
            .iter()
            .filter(|i| i.stage == Stage::Done)
            .count() as u32,
        failed: batch
            .items
            .iter()
            .filter(|i| i.stage == Stage::Failed)
            .count() as u32,
        install: item.install,
        pct,
        active_dest: match active {
            Some(_) if !item.install => format!("{PUSH_TARGET}{}", item.name),
            _ => String::new(),
        },
        landed: batch
            .items
            .iter()
            .filter(|i| i.stage == Stage::Done && !i.install)
            .map(|i| i.name.clone())
            .collect(),
    })
}

/// Record how much of the file in flight has reached the phone.
pub fn note_bytes(key: &str, bytes: u64) {
    if let Some(batch) = batches().lock().unwrap().get_mut(key) {
        batch.bytes = bytes;
    }
}

/// The session is over — its drops are history.
pub fn forget(key: &str) {
    batches().lock().unwrap().remove(key);
}

/// Base64 for the strings that reach the phone as `am broadcast` arguments.
///
/// File names are user data: quotes, apostrophes, `$`, spaces and non-ASCII
/// all occur, and the broadcast is assembled into a shell command line.
/// Encoding sidesteps every layer of quoting between here and the launcher.
pub fn b64(input: &str) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes = input.as_bytes();
    let mut out = String::with_capacity((bytes.len() + 2) / 3 * 4);
    for chunk in bytes.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(ALPHABET[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

/// Single-quote a string for the device shell (a file name goes into `stat`).
pub fn sh_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The state is global (the output readers are threads), so tests must
    /// not share a session key — they run in parallel.
    fn snap(key: &str) -> Snapshot {
        snapshot(key).expect("a batch")
    }

    /// A local path shaped the way THIS platform's scrcpy prints one.
    ///
    /// The separator is the whole point of these fixtures: `file_name` splits
    /// on the host's separator only, so a hard-coded `C:\tmp\…` would stop
    /// exercising the split at all on macOS — every assertion below would pass
    /// against a name that was never shortened.
    fn host_path(name: &str) -> String {
        if cfg!(windows) {
            format!("C:\\tmp\\{name}")
        } else {
            format!("/tmp/{name}")
        }
    }

    #[test]
    fn a_drop_becomes_a_batch() {
        let key = "batch|desktop";
        observe(key, &format!("INFO: Request to push {}", host_path("a b.zip")));
        observe(key, &format!("INFO: Request to push {}", host_path("c.txt")));
        let s = snap(key);
        assert_eq!((s.total, s.done), (2, false));

        observe(key, &format!("INFO: Pushing {}...", host_path("a b.zip")));
        let s = snap(key);
        assert_eq!(s.name, "a b.zip");
        assert_eq!(s.index, 1);
        assert_eq!(s.active_dest, "/sdcard/Download/a b.zip");

        observe(
            key,
            &format!(
                "INFO: {} successfully pushed to /sdcard/Download/",
                host_path("a b.zip")
            ),
        );
        observe(key, &format!("INFO: Pushing {}...", host_path("c.txt")));
        observe(
            key,
            &format!(
                "INFO: {} successfully pushed to /sdcard/Download/",
                host_path("c.txt")
            ),
        );
        let s = snap(key);
        assert!(s.done);
        assert_eq!((s.ok, s.failed), (2, 0));
        assert_eq!(s.landed, vec!["a b.zip", "c.txt"]);
        forget(key);
    }

    /// A backslash is an ordinary character in a macOS filename. Splitting on
    /// it there would hand the HUD — and the size poll that stats
    /// `/sdcard/Download/<name>` — a name the phone does not have.
    #[cfg(not(windows))]
    #[test]
    fn a_backslash_is_part_of_the_name_on_unix() {
        let key = "backslash|desktop";
        observe(key, "INFO: Request to push /tmp/holiday\\2019.mp4");
        observe(key, "INFO: Pushing /tmp/holiday\\2019.mp4...");
        let s = snap(key);
        assert_eq!(s.name, "holiday\\2019.mp4");
        assert_eq!(s.active_dest, "/sdcard/Download/holiday\\2019.mp4");
        forget(key);
    }

    #[test]
    fn a_failure_is_counted_not_lost() {
        let key = "fail|desktop";
        observe(key, &format!("INFO: Request to push {}", host_path("x.bin")));
        observe(key, &format!("INFO: Pushing {}...", host_path("x.bin")));
        observe(
            key,
            &format!(
                "ERROR: Failed to push {} to /sdcard/Download/",
                host_path("x.bin")
            ),
        );
        let s = snap(key);
        assert!(s.done);
        assert_eq!((s.ok, s.failed), (0, 1));
        forget(key);
    }

    #[test]
    fn adb_progress_glued_to_the_front_still_matches() {
        let key = "glue|desktop";
        observe(key, &format!("INFO: Request to push {}", host_path("big.iso")));
        observe(key, &format!("INFO: Pushing {}...", host_path("big.iso")));
        observe(
            key,
            &format!(
                "[ 99%] /sdcard/Download/big.isoINFO: {} successfully pushed to /sdcard/Download/",
                host_path("big.iso")
            ),
        );
        assert!(snap(key).done);
        forget(key);
    }

    #[test]
    fn an_apk_is_an_install() {
        let key = "apk|desktop";
        observe(
            key,
            &format!("INFO: Request to install {}", host_path("app.apk")),
        );
        observe(key, &format!("INFO: Installing {}...", host_path("app.apk")));
        let s = snap(key);
        assert!(s.install);
        assert!(
            s.active_dest.is_empty(),
            "an install has no destination file"
        );
        observe(
            key,
            &format!("INFO: {} successfully installed", host_path("app.apk")),
        );
        let s = snap(key);
        assert!(s.done && s.ok == 1);
        assert!(s.landed.is_empty(), "an install is not a file to scan");
        forget(key);
    }

    /// A name that looks like one of the other messages must not be parsed as
    /// one — every marker is searched for anywhere in the line.
    #[test]
    fn a_file_named_after_a_message_is_still_just_a_file() {
        let key = "names|desktop";
        let path = host_path("Installing Guide.pdf");
        observe(key, &format!("INFO: Request to push {path}"));
        observe(key, &format!("INFO: Pushing {path}..."));
        let s = snap(key);
        assert_eq!(s.name, "Installing Guide.pdf");
        assert_eq!(s.total, 1, "one file, not one file plus a phantom install");
        assert!(!s.install);
        observe(
            key,
            &format!("INFO: {path} successfully pushed to /sdcard/Download/"),
        );
        let s = snap(key);
        assert!(s.done);
        assert_eq!((s.ok, s.total), (1, 1));
        forget(key);
    }

    #[test]
    fn progress_needs_a_size_and_never_reaches_100_early() {
        let key = "pct|desktop";
        observe(
            key,
            &format!("INFO: Pushing {}...", host_path("nonexistent.bin")),
        );
        assert_eq!(snap(key).pct, -1, "no size, no percentage");
        forget(key);
    }

    #[test]
    fn base64_matches_the_reference() {
        assert_eq!(b64("a"), "YQ==");
        assert_eq!(b64("ab"), "YWI=");
        assert_eq!(b64("abc"), "YWJj");
        assert_eq!(b64("holiday's ☂.mp4"), "aG9saWRheSdzIOKYgi5tcDQ=");
    }
}
