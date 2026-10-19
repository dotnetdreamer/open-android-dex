//! Files onto the phone's Linux: SFTP, inside the same tunnel `ssh` uses.
//!
//! The desktop app's window drops files onto the guest's Desktop with this,
//! and `dexremote put` copies them anywhere in its home.
//!
//! **SFTP, and not a verb of the daemon's own,** because of where the writes
//! happen. The phone's sftp-server runs under proot as Linux's own root, so
//! every path is resolved inside the container, the way any program in Linux
//! sees it: a `~/Desktop` the guest turned into a link, or a link planted
//! where a file is about to land, can only ever lead somewhere in Linux. The
//! daemon runs outside proot as the app; writing the same files from there,
//! it would be following the guest's links on the phone's real filesystem,
//! where one pointing into the app's own files would carry a write into the
//! endpoint key or the list of computers. And SFTP needs nothing new on the
//! phone: any phone that takes `ssh` takes this.
//!
//! The SSH client is russh, in process, over the tunnel's byte stream: it logs
//! in with the pairing's key (`~/.ssh/openandroiddex_ed25519`), and the phone's
//! host key must be the one the pairing pinned — anything else is refused
//! before a byte of a file goes out.
//!
//! **Nothing is ever overwritten.** Every file is created with `O_EXCL` and
//! every folder with `mkdir`, both of which fail rather than replace, and a
//! name already taken gets the one Thunar would give the copy: "report (copy
//! 1).pdf", then "(copy 2)". A copy that is cancelled, or fails, takes back
//! what it had made of the item in progress — the file, or the folder and
//! everything in it — and leaves the items already finished where they are.
//!
//! Links and anything else that is not a file or a folder are not copied, and
//! neither are files this computer will not let it read; both are counted and
//! reported rather than skipped in silence.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, UNIX_EPOCH};

use futures_util::stream::{self, StreamExt, TryStreamExt};
use iroh::EndpointId;
use russh::client::{self, Handle};
use russh::keys::{PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh_sftp::client::{Config as SftpConfig, SftpSession};
use russh_sftp::protocol::{FileAttributes, OpenFlags};
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tracing::{debug, info, warn};

use crate::computer::Phone;
use crate::pc::{Client, DialError};
use crate::sshkey;

pub use tokio_util::sync::CancellationToken;

/// Where a drop lands: the guest's Desktop, relative to its home, which XFCE
/// shows the moment a file appears in it.
pub const DESKTOP: &str = "Desktop";

/// A drop of a whole home directory is not what this is for; scp is.
const MAX_ENTRIES: usize = 100_000;

/// Files in flight at once. Each costs three round trips that carry no data —
/// open, set the times, close — and a folder of photos is mostly those over a
/// 30–110 ms path, so they overlap; the bytes of each are pipelined anyway.
const PARALLEL_FILES: usize = 8;

/// Writes in flight per file. Each is as large as the server takes — 255 KiB
/// from OpenSSH's sftp-server, which says so (`limits@openssh.com`) — so the
/// real cap is SSH's own window, 2 MiB; this only has to be more than that.
const WRITES_IN_FLIGHT: usize = 32;

/// How long one SFTP request may take. A relay is shared and rate-limited,
/// and a phone that has just woken is slow to answer the first.
const REQUEST_TIMEOUT_SECS: u64 = 30;

/// What a copy is doing, for a progress bar.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    /// The file being copied now: its name, without the folders above it.
    pub current: String,
    pub files_done: u64,
    pub files_total: u64,
    pub bytes_done: u64,
    pub bytes_total: u64,
}

/// How a copy went.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    /// The folder everything went into, as Linux names it: `/root/Desktop`.
    pub dir: String,
    /// Each dropped item under the name it landed with, in order.
    pub landed: Vec<String>,
    pub files: u64,
    pub bytes: u64,
    /// Links, devices and sockets, which are not copied.
    pub links: u64,
    /// Files and folders this computer would not let the copy read.
    pub unreadable: u64,
    /// Stopped part way. `landed` is what had finished by then; the item in
    /// progress was taken back.
    pub cancelled: bool,
}

/// Why a copy did not happen, in words a person can act on.
#[derive(Debug)]
pub enum PutError {
    /// The tunnel to the phone did not open, or its sshd refused.
    Dial(DialError),
    /// The phone answered with a host key other than the paired one.
    HostKey,
    /// The phone's Linux did not take this computer's SSH key.
    Login,
    /// The SSH session opened and SFTP did not start in it.
    NoSftp(String),
    /// The guest's disk cannot take it.
    NoRoom { needed: u64, free: u64 },
    /// More entries than [`MAX_ENTRIES`].
    TooMany,
    /// None of what was dropped is something that can be copied.
    Nothing,
    /// The connection to the phone failed part way.
    Link(String),
    /// Writing on the phone failed: which file, and what the phone said.
    Write { name: String, why: String },
    /// Reading on this computer failed part way through a file.
    Read { name: String, why: String },
}

impl std::fmt::Display for PutError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PutError::Dial(e) => write!(f, "{e}"),
            PutError::HostKey => f.write_str(
                "The phone answered with a different SSH host key from the one it was paired with, \
                 so nothing was copied. Pair it again if you trust it.",
            ),
            PutError::Login => f.write_str(
                "The phone's Linux did not accept this computer's key. Pair it again: on the phone \
                 open Linux → Remote access → Add a computer.",
            ),
            PutError::NoSftp(why) => write!(
                f,
                "The phone's SSH server would not start file transfer ({why}). Update Open Android DeX \
                 on the phone, then try again."
            ),
            PutError::NoRoom { needed, free } => write!(
                f,
                "There is not enough space on the phone: this needs {}, and {} is free.",
                size(*needed),
                size(*free)
            ),
            PutError::TooMany => write!(f, "That is more than {MAX_ENTRIES} files and folders. Use scp or sftp for this one."),
            PutError::Nothing => f.write_str("Nothing in that could be copied: links and devices are not."),
            PutError::Link(why) => write!(f, "The connection to the phone failed: {why}"),
            PutError::Write { name, why } => write!(f, "{name} could not be written on the phone: {why}"),
            PutError::Read { name, why } => write!(f, "{name} could not be read on this computer: {why}"),
        }
    }
}

impl std::error::Error for PutError {}

/// A byte count, the way a file manager says one.
pub fn size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["bytes", "KB", "MB", "GB", "TB"];
    if bytes < 1000 {
        return format!("{bytes} {}", if bytes == 1 { "byte" } else { UNITS[0] });
    }
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1000.0 && unit < UNITS.len() - 1 {
        value /= 1000.0;
        unit += 1;
    }
    if value < 10.0 {
        format!("{value:.1} {}", UNITS[unit])
    } else {
        format!("{value:.0} {}", UNITS[unit])
    }
}

// ── names ─────────────────────────────────────────────────────────────────

/// Where a file name's extension starts, by the rule Thunar's
/// `thunar_util_str_get_extension` uses, so a copy is named the way Thunar
/// would name it: the last dot — but none for a name with no dot, a dot only
/// at the start (".bashrc") or a dot at the very end; a compression suffix
/// takes a short container with it ("backup.tar.gz"); and ".in" takes up to
/// two more parts ("app.desktop.in.in").
fn extension_start(name: &str) -> Option<usize> {
    let b = name.as_bytes();
    let mut dot = name.rfind('.')?;
    if dot == 0 || dot + 1 == b.len() {
        return None;
    }
    // The dot before `before`, never the very first byte.
    let dot_before = |before: usize| -> Option<usize> { name[..before].rfind('.').filter(|&d| d > 0) };
    let ext = &name[dot + 1..];
    const COMPRESSED: [&str; 8] = ["gz", "bz2", "lzma", "lrz", "rpm", "lzo", "xz", "z"];
    if COMPRESSED.iter().any(|c| c.eq_ignore_ascii_case(ext)) {
        if let Some(d2) = dot_before(dot) {
            if (2..=5).contains(&(dot - d2 - 1)) {
                dot = d2;
            }
        }
        return Some(dot);
    }
    if ext.eq_ignore_ascii_case("in") {
        for _ in 0..3 {
            let Some(d2) = dot_before(dot) else { break };
            let part = dot - d2 - 1;
            if part < 2 {
                break;
            }
            let another_in = part == 2 && name[d2 + 1..dot].eq_ignore_ascii_case("in");
            dot = d2;
            if !another_in {
                break;
            }
        }
    }
    Some(dot)
}

/// The name Thunar gives the `n`th copy of `name` when one is already there:
/// "report (copy 1).pdf". A folder's name has no extension, whatever its dots.
pub fn copy_name(name: &str, n: u32, is_dir: bool) -> String {
    match extension_start(name).filter(|_| !is_dir) {
        Some(at) => format!("{} (copy {n}){}", &name[..at], &name[at..]),
        None => format!("{name} (copy {n})"),
    }
}

fn join(dir: &str, name: &str) -> String {
    if dir.ends_with('/') {
        format!("{dir}{name}")
    } else {
        format!("{dir}/{name}")
    }
}

// ── what was dropped ──────────────────────────────────────────────────────

#[derive(Debug)]
struct LocalFile {
    /// Under the item: "photos/a.jpg", always with '/'. Empty for an item
    /// that is itself a file.
    rel: String,
    path: PathBuf,
    size: u64,
    mtime: Option<u32>,
    mode: u32,
}

#[derive(Debug)]
struct LocalDir {
    rel: String,
    mtime: Option<u32>,
}

#[derive(Debug)]
struct Item {
    name: String,
    is_dir: bool,
    /// Parents before children.
    dirs: Vec<LocalDir>,
    files: Vec<LocalFile>,
    /// The item's own times, for a folder.
    mtime: Option<u32>,
}

#[derive(Debug, Default)]
struct Plan {
    items: Vec<Item>,
    files: u64,
    bytes: u64,
    links: u64,
    unreadable: u64,
}

fn mtime_of(m: &std::fs::Metadata) -> Option<u32> {
    let secs = m.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_secs();
    u32::try_from(secs).ok()
}

/// A file's permission bits as they should land: its own, readable and
/// writable by Linux's root whatever they were. Windows has none to give.
fn mode_of(m: &std::fs::Metadata) -> u32 {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        (m.permissions().mode() & 0o777) | 0o600
    }
    #[cfg(not(unix))]
    {
        let _ = m;
        0o644
    }
}

fn name_of(path: &Path) -> Option<String> {
    path.file_name().map(|n| n.to_string_lossy().into_owned())
}

/// Walk what was dropped. A dropped link is followed — someone picked it —
/// but a link found inside a folder is not, which also keeps a link back up
/// the tree from walking forever.
fn plan(paths: &[PathBuf]) -> Result<Plan, PutError> {
    let mut plan = Plan::default();
    let mut entries = 0usize;
    for path in paths {
        let Some(name) = name_of(path) else {
            plan.unreadable += 1;
            continue;
        };
        let meta = match std::fs::metadata(path) {
            Ok(m) => m,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                // A link to nothing, or gone since it was picked up.
                plan.links += 1;
                continue;
            }
            Err(_) => {
                plan.unreadable += 1;
                continue;
            }
        };
        if meta.is_file() {
            plan.files += 1;
            plan.bytes += meta.len();
            plan.items.push(Item {
                name,
                is_dir: false,
                dirs: Vec::new(),
                files: vec![LocalFile { rel: String::new(), path: path.clone(), size: meta.len(), mtime: mtime_of(&meta), mode: mode_of(&meta) }],
                mtime: None,
            });
            entries += 1;
        } else if meta.is_dir() {
            let mut item = Item { name, is_dir: true, dirs: Vec::new(), files: Vec::new(), mtime: mtime_of(&meta) };
            let mut stack = vec![(path.clone(), String::new())];
            while let Some((dir, rel)) = stack.pop() {
                let listing = match std::fs::read_dir(&dir) {
                    Ok(l) => l,
                    Err(_) => {
                        plan.unreadable += 1;
                        continue;
                    }
                };
                let mut children: Vec<_> = listing.filter_map(Result::ok).collect();
                // Stable, so a copy goes through a folder in the order a file
                // manager lists it.
                children.sort_by_key(|c| c.file_name());
                for child in children {
                    entries += 1;
                    if entries > MAX_ENTRIES {
                        return Err(PutError::TooMany);
                    }
                    let child_name = child.file_name().to_string_lossy().into_owned();
                    let child_rel = if rel.is_empty() { child_name } else { format!("{rel}/{child_name}") };
                    let Ok(m) = std::fs::symlink_metadata(child.path()) else {
                        plan.unreadable += 1;
                        continue;
                    };
                    if m.is_dir() {
                        item.dirs.push(LocalDir { rel: child_rel.clone(), mtime: mtime_of(&m) });
                        stack.push((child.path(), child_rel));
                    } else if m.is_file() {
                        plan.files += 1;
                        plan.bytes += m.len();
                        item.files.push(LocalFile { rel: child_rel, path: child.path(), size: m.len(), mtime: mtime_of(&m), mode: mode_of(&m) });
                    } else {
                        plan.links += 1;
                    }
                }
            }
            // The walk is depth-first from a stack; parents must still come
            // before their children when the folders are made.
            item.dirs.sort_by(|a, b| a.rel.matches('/').count().cmp(&b.rel.matches('/').count()).then(a.rel.cmp(&b.rel)));
            plan.items.push(item);
        } else {
            plan.links += 1;
        }
    }
    if plan.items.is_empty() {
        return Err(PutError::Nothing);
    }
    Ok(plan)
}

// ── the session ───────────────────────────────────────────────────────────

/// The pairing's pin on the phone's host key.
struct Pinned {
    host: [u8; 32],
}

impl client::Handler for Pinned {
    type Error = russh::Error;

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        let presented = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.key_data().ed25519().map(|k| k.0),
            // The pairing pinned a key; a certificate is something else.
            PublicKeyOrCertificate::Certificate(_) => None,
        };
        Ok(presented == Some(self.host))
    }
}

/// An SFTP session in the phone's Linux, as the paired computer.
pub struct Guest {
    conn: iroh::endpoint::Connection,
    ssh: Handle<Pinned>,
    sftp: SftpSession,
}

impl Guest {
    /// Through the tunnel to the guest's sshd, logged in with the pairing's
    /// key `seed`, and SFTP started.
    pub async fn open(client: &Client, phone: &Phone, seed: &[u8; 32]) -> Result<Self, PutError> {
        let id: EndpointId = phone.id.parse().map_err(|_| PutError::Dial(DialError::Other("not a phone id".into())))?;
        let host = sshkey::parse_public_line(&phone.host_key).ok_or(PutError::HostKey)?;
        let (conn, send, recv) = client.open_ssh(id).await.map_err(PutError::Dial)?;
        let config = Arc::new(client::Config {
            // The phone may be on a relay and asleep between packets; the
            // tunnel itself notices a phone that has gone.
            keepalive_interval: Some(Duration::from_secs(20)),
            ..Default::default()
        });
        let stream = tokio::io::join(recv, send);
        let mut ssh = match client::connect_stream(config, stream, Pinned { host }).await {
            Ok(ssh) => ssh,
            Err(russh::Error::UnknownKey) => {
                conn.close(0u32.into(), b"host key");
                return Err(PutError::HostKey);
            }
            Err(e) => {
                conn.close(0u32.into(), b"ssh");
                return Err(PutError::Link(e.to_string()));
            }
        };
        let key = PrivateKey::from(russh::keys::ssh_key::private::Ed25519Keypair::from_seed(seed));
        let auth = ssh
            .authenticate_publickey(&phone.user, PrivateKeyWithHashAlg::new(Arc::new(key), None))
            .await
            .map_err(|e| PutError::Link(e.to_string()))?;
        if !auth.success() {
            conn.close(0u32.into(), b"login");
            return Err(PutError::Login);
        }
        let channel = ssh.channel_open_session().await.map_err(|e| PutError::NoSftp(e.to_string()))?;
        channel.request_subsystem(true, "sftp").await.map_err(|e| PutError::NoSftp(e.to_string()))?;
        let config = SftpConfig {
            max_concurrent_writes: WRITES_IN_FLIGHT,
            request_timeout_secs: REQUEST_TIMEOUT_SECS,
            ..Default::default()
        };
        // An sftp-server that dies at its first request — the one proot
        // breaks without the shim — closes the channel here.
        let sftp = SftpSession::new_with_config(channel.into_stream(), config)
            .await
            .map_err(|e| PutError::NoSftp(e.to_string()))?;
        Ok(Guest { conn, ssh, sftp })
    }

    pub fn sftp(&self) -> &SftpSession {
        &self.sftp
    }

    /// The guest's home, where the login lands: `/root` on a phone.
    pub async fn home(&self) -> Result<String, PutError> {
        self.sftp.canonicalize(".").await.map_err(|e| PutError::Link(e.to_string()))
    }

    /// Hang up, saying so: the phone counts the connection as a session.
    pub async fn close(self) {
        let _ = self.sftp.close().await;
        let _ = self.ssh.disconnect(russh::Disconnect::ByApplication, "", "").await;
        self.conn.close(0u32.into(), b"done");
    }

    /// `to`, made if it is not there, as an absolute path in the guest.
    ///
    /// Asked with a stat, not a realpath: OpenSSH's realpath is OpenBSD's,
    /// which answers for a path whose last part does not exist yet.
    async fn folder(&self, to: &str) -> Result<String, PutError> {
        match self.sftp.metadata(to).await {
            Ok(m) if m.file_type().is_dir() => {}
            Ok(_) => return Err(write_error(to, "it is there, and it is not a folder")),
            Err(_) => self.sftp.create_dir(to).await.map_err(|e| write_error(to, e))?,
        }
        self.sftp.canonicalize(to).await.map_err(|e| write_error(to, e))
    }

    async fn exists(&self, path: &str) -> bool {
        self.sftp.symlink_metadata(path).await.is_ok()
    }
}

// ── the copy ──────────────────────────────────────────────────────────────

/// What a copy has done so far, shared by the files in flight: what the
/// progress bar shows.
#[derive(Default)]
struct Counters {
    files: AtomicU64,
    bytes: AtomicU64,
    current: Mutex<String>,
}

/// What one item has landed, and left out, for the report.
#[derive(Default)]
struct Tally {
    files: AtomicU64,
    bytes: AtomicU64,
    unreadable: AtomicU64,
}

/// What one item has made on the phone, to take back if it does not finish.
#[derive(Default)]
struct Made {
    files: Vec<String>,
    dirs: Vec<String>,
}

fn attrs(mode: Option<u32>, mtime: Option<u32>) -> FileAttributes {
    let mut a = FileAttributes::empty();
    a.permissions = mode;
    if let Some(t) = mtime {
        a.atime = Some(t);
        a.mtime = Some(t);
    }
    a
}

fn write_error(name: &str, e: impl std::fmt::Display) -> PutError {
    PutError::Write { name: name.to_string(), why: e.to_string() }
}

/// Copy `paths` into `to` in the phone's Linux — [`DESKTOP`] for a drop —
/// never over anything already there. `progress` is called every tenth of a
/// second while it runs. Cancelling takes back the item in progress and
/// returns what had landed, with [`Report::cancelled`] set.
pub async fn put(
    client: &Client,
    phone: &Phone,
    seed: &[u8; 32],
    paths: &[PathBuf],
    to: &str,
    mut progress: impl FnMut(&Progress) + Send,
    cancel: &CancellationToken,
) -> Result<Report, PutError> {
    let owned = paths.to_vec();
    let plan = tokio::task::spawn_blocking(move || plan(&owned))
        .await
        .map_err(|e| PutError::Read { name: "what was dropped".into(), why: e.to_string() })??;
    let guest = tokio::select! {
        guest = Guest::open(client, phone, seed) => guest?,
        _ = cancel.cancelled() => return Ok(Report { cancelled: true, ..Default::default() }),
    };
    let result = copy(&guest, plan, to, &mut progress, cancel).await;
    guest.close().await;
    result
}

async fn copy(
    guest: &Guest,
    plan: Plan,
    to: &str,
    progress: &mut (impl FnMut(&Progress) + Send),
    cancel: &CancellationToken,
) -> Result<Report, PutError> {
    let dir = guest.folder(to).await?;
    if let Ok(Some(fs)) = guest.sftp.fs_info(dir.as_str()).await {
        let free = fs.blocks_avail.saturating_mul(fs.fragment_size);
        if free > 0 && plan.bytes > free {
            return Err(PutError::NoRoom { needed: plan.bytes, free });
        }
    }
    let mut report = Report { dir: dir.clone(), links: plan.links, unreadable: plan.unreadable, ..Default::default() };
    let counters = Counters::default();
    let snapshot = |c: &Counters| Progress {
        current: c.current.lock().unwrap().clone(),
        files_done: c.files.load(Ordering::Relaxed),
        files_total: plan.files,
        bytes_done: c.bytes.load(Ordering::Relaxed),
        bytes_total: plan.bytes,
    };
    progress(&snapshot(&counters));
    let mut tick = tokio::time::interval(Duration::from_millis(100));
    for item in &plan.items {
        let made = Mutex::new(Made::default());
        let tally = Tally::default();
        // Boxed, so that dropping it below really ends it: every handle it
        // holds closes before the removals that follow reach the server.
        let mut work = Box::pin(copy_item(guest, &dir, item, &counters, &tally, &made));
        let outcome = loop {
            tokio::select! {
                r = &mut work => break Some(r),
                _ = tick.tick() => progress(&snapshot(&counters)),
                _ = cancel.cancelled() => break None,
            }
        };
        drop(work);
        report.unreadable += tally.unreadable.load(Ordering::Relaxed);
        match outcome {
            Some(Ok(landed)) => {
                if let Some(landed) = landed {
                    info!("put: {} landed as {landed}", item.name);
                    report.landed.push(landed);
                }
                report.files += tally.files.load(Ordering::Relaxed);
                report.bytes += tally.bytes.load(Ordering::Relaxed);
            }
            Some(Err(e)) => {
                warn!("put: {}: {e}", item.name);
                undo(guest, &made.into_inner().unwrap()).await;
                return Err(e);
            }
            None => {
                info!("put: cancelled during {}", item.name);
                undo(guest, &made.into_inner().unwrap()).await;
                report.cancelled = true;
                return Ok(report);
            }
        }
    }
    progress(&snapshot(&counters));
    Ok(report)
}

/// The item's own name in `dir`, or Thunar's next "(copy n)" for it, made on
/// the phone: a folder, or a file open for writing. Only the server can say
/// whether a name is free without a race, so it is asked by creating —
/// O_EXCL and mkdir both refuse a name that is taken, a dangling link's
/// included.
async fn claim(guest: &Guest, dir: &str, item: &Item, mode: u32) -> Result<(String, String, Option<RemoteFile>), PutError> {
    for n in 0..1000u32 {
        let candidate = if n == 0 { item.name.clone() } else { copy_name(&item.name, n, item.is_dir) };
        let path = join(dir, &candidate);
        let attempt = if item.is_dir {
            guest.sftp.create_dir(path.as_str()).await.map(|()| None)
        } else {
            guest.sftp.open_with_flags_and_attributes(path.as_str(), CREATE_NEW, attrs(Some(mode), None)).await.map(Some)
        };
        match attempt {
            Ok(file) => return Ok((candidate, path, file)),
            Err(e) if guest.exists(&path).await => debug!("put: {candidate} is taken ({e})"),
            Err(e) => return Err(write_error(&item.name, e)),
        }
    }
    Err(write_error(&item.name, "every name for a copy of it is taken"))
}

type RemoteFile = russh_sftp::client::fs::File;

const CREATE_NEW: OpenFlags = OpenFlags::CREATE.union(OpenFlags::EXCLUDE).union(OpenFlags::WRITE);

/// A file the copy may read, or `None` — counted, and the bar moved past it —
/// for one this computer will not open.
async fn open_local(f: &LocalFile, counters: &Counters, tally: &Tally) -> Option<tokio::fs::File> {
    match tokio::fs::File::open(&f.path).await {
        Ok(local) => Some(local),
        Err(e) => {
            debug!("put: cannot read {}: {e}", f.path.display());
            tally.unreadable.fetch_add(1, Ordering::Relaxed);
            counters.files.fetch_add(1, Ordering::Relaxed);
            counters.bytes.fetch_add(f.size, Ordering::Relaxed);
            None
        }
    }
}

/// One dropped item: a file, or a folder with everything in it. Returns the
/// name it landed under, or `None` for a file that could not be read at all.
async fn copy_item(
    guest: &Guest,
    dir: &str,
    item: &Item,
    counters: &Counters,
    tally: &Tally,
    made: &Mutex<Made>,
) -> Result<Option<String>, PutError> {
    if !item.is_dir {
        let f = &item.files[0];
        let Some(local) = open_local(f, counters, tally).await else { return Ok(None) };
        let (landed, path, remote) = claim(guest, dir, item, f.mode).await?;
        made.lock().unwrap().files.push(path);
        upload(f, &landed, local, remote.expect("a file was claimed"), counters, tally).await?;
        return Ok(Some(landed));
    }

    let (landed, root, _) = claim(guest, dir, item, 0o755).await?;
    made.lock().unwrap().dirs.push(root.clone());
    for d in &item.dirs {
        let path = join(&root, &d.rel);
        guest.sftp.create_dir(path.as_str()).await.map_err(|e| write_error(&format!("{landed}/{}", d.rel), e))?;
        made.lock().unwrap().dirs.push(path);
    }
    let (root_ref, landed_ref) = (&root, &landed);
    stream::iter(item.files.iter().map(Ok::<&LocalFile, PutError>))
        .try_for_each_concurrent(PARALLEL_FILES, |f| async move {
            let Some(local) = open_local(f, counters, tally).await else { return Ok(()) };
            let path = join(root_ref, &f.rel);
            let display = format!("{landed_ref}/{}", f.rel);
            let remote = guest
                .sftp
                .open_with_flags_and_attributes(path.as_str(), CREATE_NEW, attrs(Some(f.mode), None))
                .await
                .map_err(|e| write_error(&display, e))?;
            made.lock().unwrap().files.push(path);
            upload(f, &display, local, remote, counters, tally).await
        })
        .await?;
    // Last, deepest first: writing into a folder moves its time.
    for d in item.dirs.iter().rev() {
        let _ = guest.sftp.set_metadata(join(&root, &d.rel), attrs(None, d.mtime)).await;
    }
    let _ = guest.sftp.set_metadata(root.as_str(), attrs(None, item.mtime)).await;
    Ok(Some(landed))
}

/// One file's bytes into the remote file made for it, then its time.
async fn upload(
    f: &LocalFile,
    display: &str,
    mut local: tokio::fs::File,
    mut remote: RemoteFile,
    counters: &Counters,
    tally: &Tally,
) -> Result<(), PutError> {
    *counters.current.lock().unwrap() =
        Path::new(display).file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let read_error = |e: std::io::Error| PutError::Read { name: display.to_string(), why: e.to_string() };
    let mut buf = vec![0u8; 256 * 1024];
    let mut sent = 0u64;
    loop {
        let n = local.read(&mut buf).await.map_err(read_error)?;
        if n == 0 {
            break;
        }
        remote.write_all(&buf[..n]).await.map_err(|e| write_error(display, e))?;
        sent += n as u64;
        counters.bytes.fetch_add(n as u64, Ordering::Relaxed);
    }
    // Behind the writes, which the server takes in order: the time it sets
    // is the last thing done to the file.
    if f.mtime.is_some() {
        remote.set_metadata(attrs(None, f.mtime)).await.map_err(|e| write_error(display, e))?;
    }
    remote.shutdown().await.map_err(|e| write_error(display, e))?;
    counters.files.fetch_add(1, Ordering::Relaxed);
    tally.files.fetch_add(1, Ordering::Relaxed);
    tally.bytes.fetch_add(sent, Ordering::Relaxed);
    Ok(())
}

/// Take back what an unfinished item made: its files, then its folders,
/// deepest first. Best effort — a phone that has gone keeps what it has.
async fn undo(guest: &Guest, made: &Made) {
    stream::iter(made.files.iter())
        .for_each_concurrent(PARALLEL_FILES, |path| async move {
            if let Err(e) = guest.sftp.remove_file(path.as_str()).await {
                debug!("put: could not take back {path}: {e}");
            }
        })
        .await;
    for dir in made.dirs.iter().rev() {
        if let Err(e) = guest.sftp.remove_dir(dir.as_str()).await {
            debug!("put: could not take back {dir}: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copies_are_named_the_way_thunar_names_them() {
        assert_eq!(copy_name("report.pdf", 1, false), "report (copy 1).pdf");
        assert_eq!(copy_name("report.pdf", 2, false), "report (copy 2).pdf");
        assert_eq!(copy_name("Makefile", 1, false), "Makefile (copy 1)");
        assert_eq!(copy_name(".bashrc", 1, false), ".bashrc (copy 1)", "a leading dot is not an extension");
        assert_eq!(copy_name("name.", 1, false), "name. (copy 1)", "nor is a trailing one");
        assert_eq!(copy_name("backup.tar.gz", 1, false), "backup (copy 1).tar.gz");
        assert_eq!(copy_name("photos.TAR.XZ", 3, false), "photos (copy 3).TAR.XZ");
        // The container part has to be two to five characters.
        assert_eq!(copy_name("x.verylong.gz", 1, false), "x.verylong (copy 1).gz");
        assert_eq!(copy_name("a.b.gz", 1, false), "a.b (copy 1).gz");
        assert_eq!(copy_name(".tar.gz", 1, false), ".tar (copy 1).gz", "never the first dot");
        assert_eq!(copy_name("app.desktop.in", 1, false), "app (copy 1).desktop.in");
        assert_eq!(copy_name("app.desktop.in.in", 1, false), "app (copy 1).desktop.in.in");
        assert_eq!(copy_name("x.in", 1, false), "x (copy 1).in");
        assert_eq!(copy_name("photos.2024", 1, true), "photos.2024 (copy 1)", "a folder has no extension");
        assert_eq!(copy_name("Ünïcødé.tär", 1, false), "Ünïcødé (copy 1).tär");
    }

    #[test]
    fn sizes_read_like_a_file_managers() {
        assert_eq!(size(1), "1 byte");
        assert_eq!(size(999), "999 bytes");
        assert_eq!(size(1_500), "1.5 KB");
        assert_eq!(size(23_400_000), "23 MB");
        assert_eq!(size(4_000_000_000), "4.0 GB");
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dexremote-sftp-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_drop_is_walked_folders_first_and_links_are_counted_not_followed() {
        let root = scratch("plan");
        std::fs::create_dir_all(root.join("trip/photos/raw")).unwrap();
        std::fs::write(root.join("trip/readme.md"), b"hello").unwrap();
        std::fs::write(root.join("trip/photos/a.jpg"), vec![1u8; 300]).unwrap();
        std::fs::write(root.join("trip/photos/raw/b.dng"), vec![2u8; 700]).unwrap();
        std::fs::write(root.join("notes.txt"), b"notes").unwrap();
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(root.join("trip"), root.join("trip/photos/loop")).unwrap();
            std::os::unix::fs::symlink(root.join("notes.txt"), root.join("picked-link")).unwrap();
            std::os::unix::fs::symlink(root.join("nowhere"), root.join("dangling")).unwrap();
        }
        let mut paths = vec![root.join("trip"), root.join("notes.txt")];
        if cfg!(unix) {
            paths.push(root.join("picked-link"));
            paths.push(root.join("dangling"));
        }
        let plan = plan(&paths).unwrap();
        let trip = &plan.items[0];
        assert!(trip.is_dir);
        assert_eq!(trip.dirs.iter().map(|d| d.rel.as_str()).collect::<Vec<_>>(), ["photos", "photos/raw"]);
        let mut files: Vec<_> = trip.files.iter().map(|f| f.rel.as_str()).collect();
        files.sort();
        assert_eq!(files, ["photos/a.jpg", "photos/raw/b.dng", "readme.md"]);
        assert_eq!(plan.items[1].name, "notes.txt");
        if cfg!(unix) {
            assert_eq!(plan.items.len(), 3, "a dropped link is copied as what it points to");
            assert_eq!(plan.items[2].name, "picked-link");
            assert_eq!(plan.links, 2, "the link inside the folder, and the one to nothing");
            assert_eq!(plan.files, 5);
            assert_eq!(plan.bytes, 5 + 300 + 700 + 5 + 5);
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_drop_of_nothing_copyable_says_so() {
        let root = scratch("nothing");
        assert!(matches!(plan(&[root.join("missing")]), Err(PutError::Nothing)));
        let _ = std::fs::remove_dir_all(&root);
    }
}
