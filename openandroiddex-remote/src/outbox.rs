//! Files the other way: from the phone's Linux onto this computer.
//!
//! A drag out of the desktop window cannot work — the drag happens inside X,
//! and VNC carries neither drags nor files — so the guest gets the nearest
//! things it has of its own: an entry for this computer in Thunar's *Send To*,
//! and a *Send to computer* launcher on XFCE's dock to drop files on. Both run
//! one small script in the guest ([`SENDER_SCRIPT`]) that queues what was
//! picked in this computer's outbox there, and the computer fetches its outbox
//! over SFTP while its desktop window is open, into Downloads.
//!
//! **The computer writes all of it**, over SFTP, when its window connects: the
//! script, its Send To entry, and the dock's launcher. So it needs nothing new
//! on the phone, every write lands inside the container (see [`crate::sftp`]
//! for why that matters), and each computer's entry carries its own name.
//!
//! **The outbox holds paths, not copies.** A batch is a folder of small files,
//! each naming one picked item, and the computer reads each item from where it
//! is — as Bluetooth's Send To or a file manager's own copy would. A copy into
//! the outbox first would cost a phone twice the space and twice the time of a
//! large video, for a snapshot nobody asked for.
//!
//! **This computer says it is looking** by rewriting a `.seen` file in its
//! outbox every few seconds; the guest's clock stamps it. The dock's launcher
//! sends to whichever computer's `.seen` is newest — the one showing the
//! desktop now, or the one that showed it last.
//!
//! What arrives here never replaces anything ("report (2).pdf", the app's own
//! convention on this side), is never opened, and is named for this computer's
//! filesystem (Windows' reserved characters and device names included). A
//! fetch that is cut off takes back what it had written of the item in
//! progress and leaves that item in the outbox, to come again whole.

use std::path::{Path, PathBuf};
use std::time::{Duration, UNIX_EPOCH};

use iroh::EndpointId;
use russh_sftp::client::error::Error as SftpError;
use russh_sftp::protocol::{FileAttributes, OpenFlags, StatusCode};
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tracing::{debug, info};

use crate::sftp::{CancellationToken, Guest, PutError};

/// Under the guest's home.
pub const OUTBOX: &str = ".local/share/openandroiddex/outbox";
/// The script, under the guest's home.
pub const SENDER: &str = ".local/share/openandroiddex/oadx-send";
/// Thunar's per-user Send To entries, under the guest's home.
pub const SEND_TO: &str = ".local/share/Thunar/sendto";
/// Where linux-setup.sh keeps the dock's launchers. Its dock.py, which
/// linux-rt.sh runs before every session, adds whatever is in here that the
/// panel does not have yet — at the next start, since a running panel writes
/// its own layout over any edit when it exits.
pub const DOCK_LAUNCHERS: &str = "/usr/local/share/openandroiddex/launchers";

/// The guest's half. POSIX sh — Ubuntu's /bin/sh is dash — and nothing past
/// coreutils, because nothing else can be counted on in there (no
/// notify-send, no gdbus, no notification daemon at all).
pub const SENDER_SCRIPT: &str = r#"#!/bin/sh
# oadx-send: Thunar's "Send To" a computer, and the dock's "Send to computer",
# in the Linux of an Open Android DeX phone. Written by that computer's desktop
# app whenever its window to this desktop connects; changes here are lost then.
#
#   oadx-send <computer> FILE...   queue FILE... for that computer
#   oadx-send --open FILE...       for the computer showing this desktop now,
#                                  or else the one that showed it last
#
# Nothing is copied here. Each queued item is the path of something picked,
# and the computer reads it from where it is, while its window is open, into
# its Downloads.
outbox="$HOME/.local/share/openandroiddex/outbox"
target=$1
[ $# -gt 0 ] && shift
if [ "$target" = --open ]; then
  target=
  for seen in "$outbox"/*/.seen; do
    [ -f "$seen" ] || continue
    if [ -z "$target" ] || [ "$seen" -nt "$outbox/$target/.seen" ]; then
      target=$(basename "$(dirname "$seen")")
    fi
  done
fi
case "$target" in
  "" | *[!0-9a-f]*) echo "oadx-send: no computer to send to" >&2; exit 2 ;;
esac
[ $# -gt 0 ] || exit 0
batch=$(date +%s)-$$
staging="$outbox/$target/.incoming-$batch"
mkdir -p "$staging" || exit 1
n=0
for item in "$@"; do
  case "$item" in
    /*) ;;
    *) item="$PWD/$item" ;;
  esac
  n=$((n + 1))
  printf '%s' "$item" > "$staging/$(printf %04d "$n")" || exit 1
done
# Whole, or not at all: the computer only ever reads a batch once it is here.
mv "$staging" "$outbox/$target/$batch"
"#;

/// This computer, as the guest names it.
#[derive(Debug, Clone)]
pub struct Me {
    /// The outbox's name for it: the first 16 hex digits of its id.
    pub key: String,
    /// What its Send To entry says.
    pub name: String,
}

impl Me {
    pub fn new(computer: &EndpointId, name: &str) -> Self {
        let id = computer.to_string();
        Me { key: id[..16.min(id.len())].to_string(), name: name.to_string() }
    }
}

/// One batch from the guest, as it landed here.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Received {
    /// Where it landed: this computer's Downloads, unless asked otherwise.
    pub dir: String,
    /// Each item under the name it landed with.
    pub landed: Vec<String>,
    /// The first of them, whole, for "Show".
    pub first: Option<String>,
    pub files: u64,
    pub bytes: u64,
    /// Links and devices met inside a folder, which are left out.
    pub links: u64,
    /// Sent, and gone or unreadable in Linux by the time it was fetched.
    pub missing: u64,
    /// Could not be written here: what, and why.
    pub failed: Vec<String>,
}

impl Received {
    fn is_empty(&self) -> bool {
        self.landed.is_empty() && self.missing == 0 && self.failed.is_empty()
    }
}

fn guest_path(home: &str, rel: &str) -> String {
    if rel.starts_with('/') {
        rel.to_string()
    } else {
        format!("{}/{rel}", home.trim_end_matches('/'))
    }
}

fn link_error(e: impl std::fmt::Display) -> PutError {
    PutError::Link(e.to_string())
}

fn is_status(e: &SftpError, codes: &[StatusCode]) -> bool {
    matches!(e, SftpError::Status(s) if codes.contains(&s.status_code))
}

/// A path made, every part of it that was missing: `mkdir -p`.
async fn make_dirs(guest: &Guest, path: &str) -> Result<(), PutError> {
    let mut at = String::new();
    for part in path.split('/').filter(|p| !p.is_empty()) {
        at.push('/');
        at.push_str(part);
        if guest.sftp().metadata(at.as_str()).await.is_ok() {
            continue;
        }
        if let Err(e) = guest.sftp().create_dir(at.as_str()).await {
            // Raced by another computer making the same folder.
            if guest.sftp().metadata(at.as_str()).await.is_err() {
                return Err(link_error(format!("cannot make {at}: {e}")));
            }
        }
    }
    Ok(())
}

/// One of our files in the guest, replaced whole: the old one removed, and the
/// new one created with O_EXCL, so a link the guest left in its place is
/// replaced rather than written through.
async fn replace(guest: &Guest, path: &str, body: &str, mode: u32) -> Result<(), PutError> {
    let _ = guest.sftp().remove_file(path).await;
    let mut attrs = FileAttributes::empty();
    attrs.permissions = Some(mode);
    let mut f = guest
        .sftp()
        .open_with_flags_and_attributes(path, OpenFlags::CREATE | OpenFlags::EXCLUDE | OpenFlags::WRITE, attrs)
        .await
        .map_err(|e| link_error(format!("cannot write {path}: {e}")))?;
    f.write_all(body.as_bytes()).await.map_err(link_error)?;
    f.shutdown().await.map_err(link_error)?;
    Ok(())
}

/// A string as a desktop entry's value: one line, backslashes escaped.
fn entry_value(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect::<String>()
        .replace('\\', "\\\\")
        .trim()
        .to_string()
}

/// A path as one argument of a desktop entry's Exec, quoted when it has to be.
fn exec_arg(path: &str) -> String {
    if path.chars().all(|c| c.is_ascii_alphanumeric() || "/._-+".contains(c)) {
        return path.to_string();
    }
    let mut quoted = String::from("\"");
    for c in path.chars() {
        if "\"`$\\".contains(c) {
            quoted.push('\\');
        }
        quoted.push(c);
    }
    quoted.push('"');
    quoted
}

/// This computer's Send To entry, as Thunar reads it.
pub fn send_to_entry(home: &str, me: &Me) -> String {
    format!(
        "[Desktop Entry]\nType=Application\nVersion=1.0\nName={}\nIcon=computer\nExec={} {} %F\n",
        entry_value(&me.name),
        exec_arg(&guest_path(home, SENDER)),
        me.key
    )
}

/// The dock's launcher: files dropped on it go to the computer showing the
/// desktop, or the one that showed it last.
pub fn dock_entry(home: &str) -> String {
    format!(
        "[Desktop Entry]\nVersion=1.0\nType=Application\nName=Send to computer\n\
         Comment=Drop files here to send them to the computer showing this desktop\n\
         Icon=computer\nExec={} --open %F\nTerminal=false\nStartupNotify=false\n",
        exec_arg(&guest_path(home, SENDER))
    )
}

/// Put everything the guest needs in place, the way it is now: the script,
/// this computer's Send To entry, its outbox, and — where the guest has
/// dock.py's folder — the dock's launcher. Rewritten on every connect, so an
/// update of the app reaches the guest without one of the phone.
pub async fn install(guest: &Guest, home: &str, me: &Me, dock: bool) -> Result<(), PutError> {
    let sender = guest_path(home, SENDER);
    make_dirs(guest, sender.rsplit_once('/').map(|(d, _)| d).unwrap_or(home)).await?;
    replace(guest, &sender, SENDER_SCRIPT, 0o755).await?;
    make_dirs(guest, &guest_path(home, &format!("{OUTBOX}/{}", me.key))).await?;
    let send_to = guest_path(home, SEND_TO);
    make_dirs(guest, &send_to).await?;
    replace(guest, &format!("{send_to}/openandroiddex-{}.desktop", me.key), &send_to_entry(home, me), 0o644).await?;
    if dock && guest.sftp().metadata(DOCK_LAUNCHERS).await.is_ok_and(|m| m.file_type().is_dir()) {
        replace(guest, &format!("{DOCK_LAUNCHERS}/oadx-send.desktop"), &dock_entry(home), 0o755).await?;
    }
    heartbeat(guest, home, me).await
}

/// Say this computer is looking: `.seen`, rewritten, so the guest's own clock
/// stamps it and no two clocks are ever compared.
pub async fn heartbeat(guest: &Guest, home: &str, me: &Me) -> Result<(), PutError> {
    let path = guest_path(home, &format!("{OUTBOX}/{}/.seen", me.key));
    replace(guest, &path, &me.name, 0o644).await
}

/// A name from the guest, made safe for this computer: the last part of a
/// path only, no control characters, and on Windows none of its reserved
/// characters or device names. The mirror of the desktop app's own rule for
/// files coming off the phone (files.rs, `safe_name`).
pub fn local_name(name: &str) -> String {
    let flattened = name.replace('\\', "/");
    let tail = flattened.rsplit('/').next().unwrap_or("");
    let mut cleaned: String = tail.chars().filter(|c| !c.is_control()).collect();
    if cfg!(windows) {
        cleaned = cleaned.chars().map(|c| if "<>:\"|?*".contains(c) { '_' } else { c }).collect();
        cleaned = cleaned.trim_end_matches(['.', ' ']).to_string();
        let stem = split_ext(&cleaned).0.to_ascii_uppercase();
        const DEVICES: [&str; 22] = [
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        ];
        if DEVICES.contains(&stem.as_str()) {
            cleaned = format!("_{cleaned}");
        }
    }
    let cleaned = cleaned.trim();
    if cleaned.is_empty() || cleaned == "." || cleaned == ".." {
        return "from Linux".to_string();
    }
    let count = cleaned.chars().count();
    if count > 180 {
        cleaned.chars().skip(count - 180).collect()
    } else {
        cleaned.to_string()
    }
}

fn split_ext(name: &str) -> (&str, &str) {
    match name.rfind('.') {
        Some(dot) if dot > 0 => (&name[..dot], &name[dot..]),
        _ => (name, ""),
    }
}

/// The nth name for `name` here: itself, then "report (2).pdf" — the app's
/// own convention on this side, the same as its File transfer window's.
fn nth_name(name: &str, n: u32) -> String {
    if n <= 1 {
        return name.to_string();
    }
    let (stem, ext) = split_ext(name);
    format!("{stem} ({n}){ext}")
}

/// A file made here under a free name, by creating it: `create_new` refuses a
/// name that is taken, so nothing in Downloads is ever replaced.
fn create_local_file(dir: &Path, name: &str) -> std::io::Result<(PathBuf, std::fs::File)> {
    for n in 1..1000 {
        let path = dir.join(nth_name(name, n));
        match std::fs::OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(f) => return Ok((path, f)),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "every name for it is taken"))
}

fn create_local_dir(dir: &Path, name: &str) -> std::io::Result<PathBuf> {
    for n in 1..1000 {
        let path = dir.join(nth_name(name, n));
        match std::fs::create_dir(&path) {
            Ok(()) => return Ok(path),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "every name for it is taken"))
}

/// Why one item did not land.
enum Missed {
    /// Gone from Linux, or unreadable there: say so, and drop it.
    Gone,
    /// Could not be written here: say so, and drop it.
    Here(String),
    /// The connection: keep it, to come again whole.
    Link(PutError),
}

/// Every batch waiting for this computer, fetched into `into`. `fetching`
/// hears each item's name as it starts. A cancel, or the connection going,
/// leaves the item in progress in the outbox and takes back what had been
/// written of it here.
pub async fn fetch(
    guest: &Guest,
    home: &str,
    me: &Me,
    into: &Path,
    mut fetching: impl FnMut(&str) + Send,
    cancel: &CancellationToken,
) -> Result<Vec<Received>, PutError> {
    let outbox = guest_path(home, &format!("{OUTBOX}/{}", me.key));
    let listing = match guest.sftp().read_dir(outbox.as_str()).await {
        Ok(listing) => listing,
        Err(e) if is_status(&e, &[StatusCode::NoSuchFile]) => return Ok(Vec::new()),
        Err(e) => return Err(link_error(e)),
    };
    let mut batches: Vec<String> = listing
        .filter(|e| !e.file_name().starts_with('.') && e.file_type().is_dir())
        .map(|e| e.file_name())
        .collect();
    batches.sort();
    let mut done = Vec::new();
    for batch in batches {
        let batch_dir = format!("{outbox}/{batch}");
        let mut items: Vec<String> = match guest.sftp().read_dir(batch_dir.as_str()).await {
            Ok(listing) => listing
                .filter(|e| !e.file_name().starts_with('.') && e.file_type().is_file())
                .map(|e| e.file_name())
                .collect(),
            Err(e) => return Err(link_error(e)),
        };
        items.sort();
        let mut got = Received { dir: into.display().to_string(), ..Default::default() };
        for item in items {
            let item_path = format!("{batch_dir}/{item}");
            let picked = match guest.sftp().read(item_path.as_str()).await {
                Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
                Err(e) => return Err(link_error(e)),
            };
            if cancel.is_cancelled() {
                break;
            }
            fetching(&local_name(&picked));
            match fetch_item(guest, &picked, into, &mut got, cancel).await {
                Ok(()) => {}
                Err(Missed::Gone) => got.missing += 1,
                Err(Missed::Here(why)) => got.failed.push(why),
                Err(Missed::Link(e)) => {
                    // What it had written is gone already (fetch_item takes
                    // back its own); the item stays for next time.
                    if !got.is_empty() {
                        done.push(got);
                    }
                    return if done.is_empty() { Err(e) } else { Ok(done) };
                }
            }
            if let Err(e) = guest.sftp().remove_file(item_path.as_str()).await {
                debug!("outbox: cannot clear {item_path}: {e}");
            }
        }
        let _ = guest.sftp().remove_dir(batch_dir.as_str()).await;
        if !got.is_empty() {
            info!("outbox: {} item(s) into {}", got.landed.len(), got.dir);
            done.push(got);
        }
    }
    Ok(done)
}

/// One picked item: a file, or a folder and everything in it. On anything
/// but success, whatever it had written here is removed again.
async fn fetch_item(guest: &Guest, picked: &str, into: &Path, got: &mut Received, cancel: &CancellationToken) -> Result<(), Missed> {
    let name = local_name(picked);
    // The picked item is followed if it is a link — someone picked it — and
    // links inside a folder are not.
    let attrs = match guest.sftp().metadata(picked).await {
        Ok(a) => a,
        Err(e) if is_status(&e, &[StatusCode::NoSuchFile, StatusCode::PermissionDenied]) => return Err(Missed::Gone),
        Err(e) => return Err(Missed::Link(link_error(e))),
    };
    let kind = attrs.file_type();
    if kind.is_file() {
        let (path, file) = create_local_file(into, &name).map_err(|e| Missed::Here(format!("{name}: {e}")))?;
        let landed = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        match fetch_file(guest, picked, file, &attrs, cancel).await {
            Ok(bytes) => {
                got.files += 1;
                got.bytes += bytes;
                got.first.get_or_insert_with(|| path.display().to_string());
                got.landed.push(landed);
                Ok(())
            }
            Err(e) => {
                let _ = std::fs::remove_file(&path);
                Err(e)
            }
        }
    } else if kind.is_dir() {
        let root = create_local_dir(into, &name).map_err(|e| Missed::Here(format!("{name}: {e}")))?;
        let mut tally = Received::default();
        match fetch_tree(guest, picked, &root, &mut tally, cancel).await {
            Ok(()) => {
                got.files += tally.files;
                got.bytes += tally.bytes;
                got.links += tally.links;
                got.missing += tally.missing;
                got.failed.extend(tally.failed);
                got.first.get_or_insert_with(|| root.display().to_string());
                got.landed.push(root.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
                Ok(())
            }
            Err(e) => {
                let _ = std::fs::remove_dir_all(&root);
                Err(e)
            }
        }
    } else {
        Err(Missed::Gone)
    }
}

/// A folder's contents, depth first. Files that vanish or cannot be read
/// part way are counted; the connection going ends it.
async fn fetch_tree(guest: &Guest, remote: &str, local: &Path, tally: &mut Received, cancel: &CancellationToken) -> Result<(), Missed> {
    let mut stack = vec![(remote.to_string(), local.to_path_buf())];
    while let Some((dir, here)) = stack.pop() {
        let listing = match guest.sftp().read_dir(dir.as_str()).await {
            Ok(l) => l,
            Err(e) if is_status(&e, &[StatusCode::NoSuchFile, StatusCode::PermissionDenied]) => {
                tally.missing += 1;
                continue;
            }
            Err(e) => return Err(Missed::Link(link_error(e))),
        };
        for entry in listing {
            let name = entry.file_name();
            if name == "." || name == ".." {
                continue;
            }
            let child = format!("{}/{name}", dir.trim_end_matches('/'));
            let kind = entry.file_type();
            if kind.is_dir() {
                let made = here.join(local_name(&name));
                if let Err(e) = std::fs::create_dir(&made) {
                    tally.failed.push(format!("{name}: {e}"));
                    continue;
                }
                stack.push((child, made));
            } else if kind.is_file() {
                let file = match std::fs::OpenOptions::new().write(true).create_new(true).open(here.join(local_name(&name))) {
                    Ok(f) => f,
                    Err(e) => {
                        tally.failed.push(format!("{name}: {e}"));
                        continue;
                    }
                };
                match fetch_file(guest, &child, file, &entry.metadata(), cancel).await {
                    Ok(bytes) => {
                        tally.files += 1;
                        tally.bytes += bytes;
                    }
                    Err(Missed::Gone) => tally.missing += 1,
                    Err(Missed::Here(why)) => tally.failed.push(why),
                    Err(link) => return Err(link),
                }
            } else {
                tally.links += 1;
            }
        }
    }
    Ok(())
}

/// One file's bytes into `file`, and its time. Returns the byte count.
async fn fetch_file(
    guest: &Guest,
    remote: &str,
    mut file: std::fs::File,
    attrs: &FileAttributes,
    cancel: &CancellationToken,
) -> Result<u64, Missed> {
    let mut source = match guest.sftp().open(remote).await {
        Ok(f) => f,
        Err(e) if is_status(&e, &[StatusCode::NoSuchFile, StatusCode::PermissionDenied]) => return Err(Missed::Gone),
        Err(e) => return Err(Missed::Link(link_error(e))),
    };
    let mut buf = vec![0u8; 256 * 1024];
    let mut total = 0u64;
    loop {
        // Between chunks, so that what was written of it is taken back by
        // whoever started it.
        if cancel.is_cancelled() {
            return Err(Missed::Link(link_error("cancelled")));
        }
        let n = source.read(&mut buf).await.map_err(|e| Missed::Link(link_error(e)))?;
        if n == 0 {
            break;
        }
        std::io::Write::write_all(&mut file, &buf[..n]).map_err(|e| Missed::Here(format!("{remote}: {e}")))?;
        total += n as u64;
    }
    if let Some(t) = attrs.mtime {
        let _ = file.set_modified(UNIX_EPOCH + Duration::from_secs(u64::from(t)));
    }
    let _ = source.shutdown().await;
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_from_linux_are_made_safe_here() {
        assert_eq!(local_name("/root/Desktop/report.pdf"), "report.pdf");
        assert_eq!(local_name("a\nb.txt"), "ab.txt");
        assert_eq!(local_name("/"), "from Linux");
        assert_eq!(local_name(".."), "from Linux");
        if cfg!(windows) {
            assert_eq!(local_name("12:30 notes?.txt"), "12_30 notes_.txt");
            assert_eq!(local_name("nul.txt"), "_nul.txt");
            assert_eq!(local_name("trailing. "), "trailing");
        }
    }

    #[test]
    fn copies_here_are_named_the_way_the_app_names_them() {
        assert_eq!(nth_name("report.pdf", 1), "report.pdf");
        assert_eq!(nth_name("report.pdf", 2), "report (2).pdf");
        assert_eq!(nth_name(".bashrc", 3), ".bashrc (3)");
        let dir = std::env::temp_dir().join(format!("dexremote-outbox-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let (a, _) = create_local_file(&dir, "x.txt").unwrap();
        let (b, _) = create_local_file(&dir, "x.txt").unwrap();
        assert_eq!(a.file_name().unwrap(), "x.txt");
        assert_eq!(b.file_name().unwrap(), "x (2).txt");
        assert_eq!(create_local_dir(&dir, "x.txt").unwrap().file_name().unwrap(), "x (3).txt");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_send_to_entry_names_this_computer_and_runs_the_script() {
        let me = Me { key: "0123456789abcdef".into(), name: "Idrees's Mac\\Book\nPro".into() };
        let entry = send_to_entry("/root", &me);
        assert!(entry.contains("\nName=Idrees's Mac\\\\Book Pro\n"), "{entry}");
        assert!(entry.contains("\nExec=/root/.local/share/openandroiddex/oadx-send 0123456789abcdef %F\n"), "{entry}");
        let odd = send_to_entry("/home/a b", &me);
        assert!(odd.contains("Exec=\"/home/a b/.local/share/openandroiddex/oadx-send\" 0123456789abcdef %F"), "{odd}");
        assert!(dock_entry("/root").contains("Exec=/root/.local/share/openandroiddex/oadx-send --open %F"));
    }

    #[test]
    fn the_script_is_plain_sh() {
        assert!(SENDER_SCRIPT.starts_with("#!/bin/sh\n"));
        // No bashisms dash would refuse.
        for bashism in ["[[", "function ", "$'", "local -", "<<<"] {
            assert!(!SENDER_SCRIPT.contains(bashism), "{bashism}");
        }
        if std::path::Path::new("/bin/sh").exists() {
            let dir = std::env::temp_dir().join(format!("dexremote-script-{}", std::process::id()));
            std::fs::create_dir_all(&dir).unwrap();
            let file = dir.join("oadx-send");
            std::fs::write(&file, SENDER_SCRIPT).unwrap();
            let checked = std::process::Command::new("/bin/sh").arg("-n").arg(&file).status().unwrap();
            assert!(checked.success(), "sh -n");
            let _ = std::fs::remove_dir_all(&dir);
        }
    }
}
