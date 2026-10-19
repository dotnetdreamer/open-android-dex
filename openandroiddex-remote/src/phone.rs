//! The phone's end: the daemon `LinuxService` runs while Remote access is on.
//!
//! It owns one permanent iroh endpoint — the phone's address, as far as every
//! paired computer is concerned — and puts each SSH stream that arrives on it
//! through to the guest's sshd on loopback. Everything else is the Android side's
//! business, and the line between the two is deliberately plain:
//!
//! * **Files in `--dir`.** `endpoint.key` and the SSH host key are ours (made on
//!   first run, never rewritten). `peers.json` is the *app's*: the list of
//!   paired computers, which only Java writes, so a pairing made over USB by
//!   the launcher process and one made here can never race each other. We
//!   re-read it on every connection and every two seconds, and closing the
//!   live sessions of a computer that has been removed is how "Remove" is
//!   immediate. `status.json` is ours, for the app's window to read.
//! * **Lines on stdin and stdout.** Commands in, events out, one per line —
//!   see [`command_loop`] and the `emit` calls. stdin reaching EOF means the
//!   service that started us is gone, and we go too: an orphaned daemon would
//!   be a door into the phone that nothing on screen knows is open.
//!
//! Who is let in is decided by endpoint id, after the QUIC handshake (which is
//! what proves the id) and before a byte of application data is read. sshd's
//! own public-key check then applies on top: the tunnel is the first lock, not
//! the only one.
//!
//! The desktop is the same door with a different room behind it: a paired
//! computer's streams go to websockify — the noVNC page and the VNC WebSocket
//! on one port — instead of sshd, and Xvnc's password is the lock on top.
//! Starting the desktop is the app's business, asked for with a line; see
//! [`open_desktop`].

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointId, RelayMode, RelayUrl, SecretKey, Watcher};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot, watch, Notify};
use tokio::task::JoinHandle;
use tracing::{debug, info, warn};

use crate::pairing::PairKeys;
use crate::pipe::{splice, Traffic};
use crate::proto::{
    self, CtlRequest, CtlStatus, DesktopHello, DesktopOp, DesktopReply, DesktopSize, PairError, PairRequest,
    PairResponse, Refusal, SshHello, SshReady, ALPN_CTL, ALPN_DESKTOP, ALPN_PAIR, ALPN_SSH, CLOSE_NOT_PAIRED,
    CLOSE_SHUTDOWN, CLOSE_UNPAIRED,
};
use crate::{b64url, sshkey, unix_now};

/// The login name inside the guest. The whole container runs as proot's fake
/// root — the XFCE session, the terminal, apt — so this is the only user there
/// is, and an SSH session lands in the same home as the desktop's terminal.
pub const GUEST_USER: &str = "root";

/// websockify, as linux-rt.sh starts it: the noVNC web root and the VNC
/// WebSocket on one loopback port, which is what the Linux window on the phone
/// loads too.
pub const DESKTOP_WEB_PORT: u16 = 6080;

pub struct Config {
    pub dir: PathBuf,
    /// Where the guest's sshd listens, on 127.0.0.1.
    pub ssh_port: u16,
    /// Where the desktop's websockify listens, on 127.0.0.1.
    pub desktop_port: u16,
    /// The phone's name, shown on the computer.
    pub name: String,
    /// The active network's DNS servers, from Android's LinkProperties. Native
    /// code cannot ask Android for them, so the app passes them in — here at
    /// start, and with every `net` after — and public resolvers stand behind
    /// them. With none, the public ones are all there is, which also works.
    pub dns: Vec<IpAddr>,
    /// A relay of the user's own instead of the public ones.
    pub relay: Option<RelayUrl>,
}

// ── identity ──────────────────────────────────────────────────────────────

/// The phone's two long-lived keys.
pub struct Identity {
    pub key: SecretKey,
    pub host_public: [u8; 32],
}

impl Identity {
    /// The sshd host key as an `ssh-ed25519 …` line — what a pairing hands the
    /// computer to put in its known_hosts.
    pub fn host_key_line(&self) -> String {
        sshkey::public_line(&self.host_public, "openandroiddex")
    }
}

const ENDPOINT_KEY: &str = "endpoint.key";
/// The subdirectory of `--dir` that linux-ssh.sh binds into the guest: the
/// sshd_config the app writes, the host key, and authorized_keys. Only this
/// part is visible from inside Linux. sshd needs its host key in there, but
/// the endpoint key is the phone's identity on the network and stays outside,
/// where nothing running in the guest can read it and pose as the phone.
pub const GUEST_DIR: &str = "ssh";
/// The host key, inside [`GUEST_DIR`].
pub const HOST_KEY: &str = "ssh_host_ed25519_key";

/// Load the phone's identity, making it on first use. Idempotent, and safe to
/// run beside a live daemon: it only ever creates what is missing.
///
/// Two processes can get here at once on a phone's very first start — the
/// window's `init` and the service's `serve` — and without the lock each
/// would make a key of its own, one rename would win, and the loser would go
/// on using a key that is no longer on disk: the computer pins one id while
/// the daemon answers to another. They take turns instead, and the second
/// reads what the first wrote.
pub fn ensure_identity(dir: &Path) -> Result<Identity> {
    std::fs::create_dir_all(dir).with_context(|| format!("cannot create {}", dir.display()))?;
    let lock = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(dir.join("identity.lock"))
        .context("cannot open the identity lock")?;
    lock_exclusive(&lock).context("cannot take the identity lock")?;
    let guest = dir.join(GUEST_DIR);
    real_dir(&guest)?;

    let key_path = dir.join(ENDPOINT_KEY);
    let key = match std::fs::read(&key_path) {
        Ok(bytes) if bytes.len() == 32 => {
            SecretKey::from_bytes(bytes.as_slice().try_into().expect("checked length"))
        }
        Ok(_) => anyhow::bail!("{} is not a key; refusing to replace it", key_path.display()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let key = SecretKey::generate();
            write_safely(&key_path, dir, &key.to_bytes(), true)?;
            info!("made a new endpoint identity {}", key.public().fmt_short());
            key
        }
        Err(e) => return Err(e).context("cannot read the endpoint key"),
    };

    let host_path = guest.join(HOST_KEY);
    // The guest can write here, so a link in place of the key is something it
    // put there, pointing who knows where. Never read through it: start over
    // with a new key, which every paired computer then refuses as changed —
    // the failure SSH is built to make loudly.
    if let Ok(m) = std::fs::symlink_metadata(&host_path) {
        if !m.is_file() {
            warn!("{} is not a plain file; making a new host key", host_path.display());
            if m.is_dir() {
                std::fs::remove_dir_all(&host_path)?;
            } else {
                std::fs::remove_file(&host_path)?;
            }
        }
    }
    let host_public = match std::fs::read_to_string(&host_path) {
        Ok(pem) => {
            let seed = sshkey::parse_private_pem(&pem)
                .with_context(|| format!("{} is not an Ed25519 key", host_path.display()))?;
            *SecretKey::from_bytes(&seed).public().as_bytes()
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let host = SecretKey::generate();
            let public = *host.public().as_bytes();
            let pem = sshkey::private_pem(&host.to_bytes(), &public, "openandroiddex");
            write_safely(&host_path, dir, pem.as_bytes(), true)?;
            info!("made a new SSH host key {}", sshkey::fingerprint(&public));
            public
        }
        Err(e) => return Err(e).context("cannot read the SSH host key"),
    };
    // The .pub is a convenience for anyone looking at the directory; sshd
    // derives the public half from the private file and never reads it.
    let pub_path = guest.join(format!("{HOST_KEY}.pub"));
    if !std::fs::symlink_metadata(&pub_path).is_ok_and(|m| m.is_file()) {
        let line = sshkey::public_line(&host_public, "openandroiddex") + "\n";
        let _ = write_safely(&pub_path, dir, line.as_bytes(), false);
    }

    Ok(Identity { key, host_public })
}

/// Hold an exclusive lock on `f` until it is closed.
///
/// flock(2) directly: std's `File::lock` compiles for Android and then answers
/// `Unsupported` there, which would have kept the daemon from ever starting
/// on a phone — while every test on a computer passed.
#[cfg(unix)]
fn lock_exclusive(f: &std::fs::File) -> std::io::Result<()> {
    use std::os::fd::AsRawFd;
    loop {
        // SAFETY: a valid descriptor this function borrows for the call only.
        if unsafe { libc::flock(f.as_raw_fd(), libc::LOCK_EX) } == 0 {
            return Ok(());
        }
        let e = std::io::Error::last_os_error();
        if e.kind() != std::io::ErrorKind::Interrupted {
            return Err(e);
        }
    }
}

#[cfg(not(unix))]
fn lock_exclusive(f: &std::fs::File) -> std::io::Result<()> {
    f.lock()
}

/// `path` as a real directory. The guest can swap the directory bound into it
/// for a symlink — an rmdir through proot reaches the real one — and writing
/// through that would put these files wherever it points.
fn real_dir(path: &Path) -> Result<()> {
    if let Ok(m) = std::fs::symlink_metadata(path) {
        if !m.is_dir() {
            warn!("{} is not a directory; putting one back", path.display());
            std::fs::remove_file(path)?;
        }
    }
    std::fs::create_dir_all(path).with_context(|| format!("cannot create {}", path.display()))
}

/// Write a file whole: never half-written, owner-only from the first byte when
/// `secret`, and never through a link.
///
/// The temporary file is made in `tmp_dir`, which the guest cannot see, and
/// with O_EXCL. Made beside `path` it would be in the guest's directory, where
/// code inside Linux could plant a symlink under the temporary name and have
/// this write go through it into any file of the app's — the endpoint key, the
/// list of computers. The rename then replaces whatever sits at `path`, a link
/// included, without following it.
fn write_safely(path: &Path, tmp_dir: &Path, bytes: &[u8], secret: bool) -> Result<()> {
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let tmp = tmp_dir.join(format!(".{name}.{}.tmp", std::process::id()));
    let _ = std::fs::remove_file(&tmp);
    {
        let mut opts = std::fs::OpenOptions::new();
        opts.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(if secret { 0o600 } else { 0o644 });
        }
        #[cfg(not(unix))]
        let _ = secret;
        let mut f = opts.open(&tmp).with_context(|| format!("cannot write {}", tmp.display()))?;
        std::io::Write::write_all(&mut f, bytes)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path).with_context(|| format!("cannot replace {}", path.display()))?;
    Ok(())
}

/// Replace a file atomically, so a reader in another process never sees half.
fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path)
}

// ── the paired computers ──────────────────────────────────────────────────

/// One entry of `peers.json`, as LinuxRemote.java writes it. Only `id` is
/// load-bearing; the name is carried for the status file.
#[derive(Debug, Clone, Deserialize)]
struct PeerEntry {
    id: String,
    #[serde(default)]
    name: String,
}

#[derive(Debug, Default, Deserialize)]
struct PeersFile {
    #[serde(default)]
    peers: Vec<PeerEntry>,
}

/// The parsed list plus what it was parsed from, so a re-read costs one stat.
#[derive(Default)]
struct Peers {
    stamp: Option<(SystemTime, u64, u64)>,
    names: HashMap<EndpointId, String>,
}

/// mtime, length and inode. The app replaces the file by rename, so the inode
/// changes on every write even when a same-length list lands inside the same
/// timestamp tick — which mtime and length alone would call "unchanged".
fn stamp(path: &Path) -> Option<(SystemTime, u64, u64)> {
    let m = std::fs::metadata(path).ok()?;
    #[cfg(unix)]
    let ino = std::os::unix::fs::MetadataExt::ino(&m);
    #[cfg(not(unix))]
    let ino = 0;
    Some((m.modified().unwrap_or(UNIX_EPOCH), m.len(), ino))
}

impl Peers {
    /// Re-read the file if it changed. Returns true when it did.
    ///
    /// A file that cannot be parsed admits NO ONE. Failing open — keeping the
    /// last list, or worse, treating junk as "everyone" — is the one mistake a
    /// door cannot make, and the app rewrites the file whole on every change,
    /// so a torn read heals on the next one.
    fn refresh(&mut self, path: &Path) -> bool {
        let stamp = stamp(path);
        if stamp == self.stamp {
            return false;
        }
        self.stamp = stamp;
        let parsed: PeersFile = match std::fs::read(path) {
            Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
                warn!("peers.json is unreadable ({e}); admitting no one until it is rewritten");
                PeersFile::default()
            }),
            Err(_) => PeersFile::default(),
        };
        self.names = parsed
            .peers
            .into_iter()
            .filter_map(|p| match p.id.parse::<EndpointId>() {
                Ok(id) => Some((id, p.name)),
                Err(_) => {
                    warn!("peers.json: skipping an entry whose id is not a key");
                    None
                }
            })
            .collect();
        info!("{} paired computer(s)", self.names.len());
        true
    }
}

// ── shared state ──────────────────────────────────────────────────────────

/// The guest sshd as LinuxService last described it (`ssh <state>` on stdin).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SshState {
    Up,
    Starting,
    /// Not running, and nothing has been asked of it yet.
    Down,
    /// Asked to start, and it did not.
    Failed,
    /// There is no Linux on the phone.
    Missing,
    /// Linux is being installed or updated; sshd is not in it yet.
    Installing,
}

impl SshState {
    fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "up" => SshState::Up,
            "starting" => SshState::Starting,
            "down" => SshState::Down,
            "failed" => SshState::Failed,
            "missing" => SshState::Missing,
            "installing" => SshState::Installing,
            _ => return None,
        })
    }

    fn as_str(self) -> &'static str {
        match self {
            SshState::Up => "up",
            SshState::Starting => "starting",
            SshState::Down => "down",
            SshState::Failed => "failed",
            SshState::Missing => "missing",
            SshState::Installing => "installing",
        }
    }
}

/// The XFCE desktop as LinuxService last described it (`desktop <state> …`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopState {
    /// Nothing said yet. The app describes it straight after `ready`.
    Unknown,
    /// There is no Linux on the phone.
    Missing,
    /// Linux is being installed, updated or reset.
    Installing,
    /// Linux's setup stopped with an error, and nothing starts until it is retried.
    Unfinished,
    /// Installed, with no desktop session running.
    Stopped,
    /// A session is on its way up.
    Starting,
    /// A session is running; its websockify may still be coming up.
    Running,
    /// The start a computer asked for ended by itself.
    Failed,
}

impl DesktopState {
    fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "missing" => DesktopState::Missing,
            "installing" => DesktopState::Installing,
            "unfinished" => DesktopState::Unfinished,
            "stopped" => DesktopState::Stopped,
            "starting" => DesktopState::Starting,
            "running" => DesktopState::Running,
            "failed" => DesktopState::Failed,
            _ => return None,
        })
    }

    fn as_str(self) -> &'static str {
        match self {
            DesktopState::Unknown => "unknown",
            DesktopState::Missing => "missing",
            DesktopState::Installing => "installing",
            DesktopState::Unfinished => "unfinished",
            DesktopState::Stopped => "stopped",
            DesktopState::Starting => "starting",
            DesktopState::Running => "running",
            DesktopState::Failed => "failed",
        }
    }
}

/// The desktop, and what a computer needs to log in to it.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Desktop {
    state: DesktopState,
    /// Xvnc's password, from the guest's setup; empty until the app has said.
    password: String,
    /// The size the running session was started at, when the app knows it.
    size: Option<DesktopSize>,
}

/// What a computer may ask the desktop to be: a real screen's size, and no
/// more than Xvnc on a phone carries comfortably — every pixel is drawn by the
/// phone's CPU and encoded again for every viewer. The floor is the one the
/// app has always held its own windows to.
const DESKTOP_MIN: DesktopSize = DesktopSize { w: 800, h: 600 };
const DESKTOP_MAX: DesktopSize = DesktopSize { w: 3840, h: 2400 };

fn clamp_size(s: DesktopSize) -> DesktopSize {
    DesktopSize {
        w: s.w.clamp(DESKTOP_MIN.w, DESKTOP_MAX.w),
        h: s.h.clamp(DESKTOP_MIN.h, DESKTOP_MAX.h),
    }
}

/// "1512x982" → a size; anything else, including the app's "-", is none.
fn parse_size(s: &str) -> Option<DesktopSize> {
    let (w, h) = s.split_once('x')?;
    Some(DesktopSize { w: w.parse().ok()?, h: h.parse().ok()? })
}

struct Session {
    peer: EndpointId,
    since: u64,
    conn: Connection,
    traffic: Arc<Traffic>,
}

struct Pairing {
    endpoint: Endpoint,
    task: JoinHandle<()>,
    until: u64,
}

struct Shared {
    dir: PathBuf,
    /// Moves when the app restarts sshd on a different free port (`port <N>`).
    ssh_port: AtomicU16,
    desktop_port: u16,
    desktop: watch::Sender<Desktop>,
    name: String,
    host_key_line: String,
    dns: DnsServers,
    relay: Option<RelayUrl>,
    ep: Endpoint,
    events: Events,
    peers: Mutex<Peers>,
    sessions: Mutex<HashMap<u64, Session>>,
    next_session: AtomicU64,
    ssh: watch::Sender<SshState>,
    pairing: tokio::sync::Mutex<Option<Pairing>>,
    /// The pairing waiting on the app to save it: whose, and how to answer.
    pending: Mutex<Option<(EndpointId, oneshot::Sender<bool>)>>,
    /// The home relay while one is connected.
    online: Mutex<Option<String>>,
    /// Something the status file shows has changed; write it now.
    dirty: Notify,
}

impl Shared {
    fn peers_path(&self) -> PathBuf {
        self.dir.join("peers.json")
    }

    fn is_paired(&self, id: &EndpointId) -> bool {
        let mut peers = self.peers.lock().unwrap();
        peers.refresh(&self.peers_path());
        peers.names.contains_key(id)
    }

    /// The paired computer a stream is from, or `None` to turn it away.
    ///
    /// Either the connection itself is a paired computer's key, or it is one
    /// of that computer's throwaway endpoints carrying its signature — see
    /// [`proto::Auth`]. The signature is over the connection's own endpoint
    /// id, which the QUIC handshake has already proven, so it cannot be lifted
    /// off one connection and used on another.
    fn computer_for(&self, remote: &EndpointId, auth: Option<&proto::Auth>) -> Option<EndpointId> {
        if self.is_paired(remote) {
            return Some(*remote);
        }
        let auth = auth?;
        let computer: EndpointId = auth.computer.parse().ok()?;
        let sig = data_encoding::HEXLOWER_PERMISSIVE.decode(auth.sig.as_bytes()).ok()?;
        let sig = iroh::Signature::try_from(sig.as_slice()).ok()?;
        computer.verify(&proto::delegation_message(remote), &sig).ok()?;
        self.is_paired(&computer).then_some(computer)
    }

    fn open_session(&self, peer: EndpointId, conn: Connection, traffic: Arc<Traffic>) -> u64 {
        let id = self.next_session.fetch_add(1, Ordering::Relaxed);
        let n = {
            let mut sessions = self.sessions.lock().unwrap();
            sessions.insert(id, Session { peer, since: unix_now(), conn, traffic });
            sessions.len()
        };
        self.events.emit(format!("sessions {n}"));
        self.dirty.notify_one();
        id
    }

    fn close_session(&self, id: u64) {
        let n = {
            let mut sessions = self.sessions.lock().unwrap();
            sessions.remove(&id);
            sessions.len()
        };
        self.events.emit(format!("sessions {n}"));
        self.dirty.notify_one();
    }
}

// ── events ────────────────────────────────────────────────────────────────

/// Lines to the service, in order, each flushed as it goes: LinuxService
/// reads them with a plain readLine, and a buffered event is a wake lock not
/// taken or a pairing that appears to hang.
#[derive(Clone)]
struct Events(mpsc::UnboundedSender<String>);

impl Events {
    fn stdout() -> (Self, JoinHandle<()>) {
        let (tx, mut rx) = mpsc::unbounded_channel::<String>();
        let writer = tokio::spawn(async move {
            let mut out = tokio::io::stdout();
            while let Some(mut line) = rx.recv().await {
                line.push('\n');
                if out.write_all(line.as_bytes()).await.is_err() || out.flush().await.is_err() {
                    break;
                }
            }
        });
        (Events(tx), writer)
    }

    fn emit(&self, line: impl Into<String>) {
        let line = line.into();
        debug!("event: {line}");
        let _ = self.0.send(line);
    }
}

// ── the endpoint ──────────────────────────────────────────────────────────

/// The DNS servers of whatever network the phone is on now, as LinuxService
/// last reported them (at start, then with every `net`).
type DnsServers = Arc<RwLock<Vec<IpAddr>>>;

/// Behind the network's own servers on every network, and alone when it has
/// none. The guest's resolv.conf names the same ones.
const PUBLIC_DNS: [IpAddr; 4] = [
    IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)),
    IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)),
    IpAddr::V6(Ipv6Addr::new(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888)),
    IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1111)),
];

/// iroh's resolver, rebuilt from the phone's current DNS servers whenever it
/// is reset — which iroh does on every major network change, and `net` does
/// when the servers moved.
///
/// A resolver built once at start would go on asking the Wi-Fi router after
/// the phone had walked onto mobile data. Every lookup — the relay's address
/// first, which reconnecting needs — would wait out that dead server before
/// the public tier answered, and on a network that blocks public DNS nothing
/// would answer at all, leaving the phone offline until the daemon restarted.
#[derive(Debug)]
struct PhoneDns {
    servers: DnsServers,
    inner: iroh::dns::DnsResolver,
}

impl PhoneDns {
    fn new(servers: DnsServers) -> Self {
        let mut primary = servers.read().map(|s| s.clone()).unwrap_or_default();
        if primary.is_empty() {
            primary = PUBLIC_DNS.to_vec();
        }
        // Raced, not deferred. A server that silently drops queries — the
        // router of a network the phone has just left — takes six seconds per
        // attempt to be given up on, and iroh's own lookups give up at three,
        // so a tier asked only after the first had failed would never be asked.
        let inner = iroh::dns::DnsResolver::builder()
            .add_nameserver_configs(primary.into_iter().map(iroh::dns::NameserverConfig::udp))
            .fallback_nameserver_configs(PUBLIC_DNS.into_iter().map(iroh::dns::NameserverConfig::udp))
            .with_fallback_mode(iroh::dns::FallbackMode::Eager)
            .build();
        PhoneDns { servers, inner }
    }
}

/// Generous on purpose: iroh puts the caller's own timeout around every lookup,
/// and that is the one that should decide.
const INNER_DNS_TIMEOUT: Duration = Duration::from_secs(30);

type DnsLookup<T> = std::pin::Pin<
    Box<dyn std::future::Future<Output = Result<iroh::dns::BoxIter<T>, iroh::dns::DnsError>> + Send + 'static>,
>;

impl iroh::dns::Resolver for PhoneDns {
    fn lookup_ipv4(&self, host: String) -> DnsLookup<Ipv4Addr> {
        let inner = self.inner.clone();
        Box::pin(async move {
            let found: Vec<Ipv4Addr> = inner
                .lookup_ipv4(host, INNER_DNS_TIMEOUT)
                .await?
                .filter_map(|ip| match ip {
                    IpAddr::V4(v4) => Some(v4),
                    IpAddr::V6(_) => None,
                })
                .collect();
            Ok(Box::new(found.into_iter()) as iroh::dns::BoxIter<Ipv4Addr>)
        })
    }

    fn lookup_ipv6(&self, host: String) -> DnsLookup<Ipv6Addr> {
        let inner = self.inner.clone();
        Box::pin(async move {
            let found: Vec<Ipv6Addr> = inner
                .lookup_ipv6(host, INNER_DNS_TIMEOUT)
                .await?
                .filter_map(|ip| match ip {
                    IpAddr::V6(v6) => Some(v6),
                    IpAddr::V4(_) => None,
                })
                .collect();
            Ok(Box::new(found.into_iter()) as iroh::dns::BoxIter<Ipv6Addr>)
        })
    }

    fn lookup_txt(&self, host: String) -> DnsLookup<iroh::dns::TxtRecordData> {
        let inner = self.inner.clone();
        Box::pin(async move {
            let found: Vec<_> = inner.lookup_txt(host, INNER_DNS_TIMEOUT).await?.collect();
            Ok(Box::new(found.into_iter()) as iroh::dns::BoxIter<iroh::dns::TxtRecordData>)
        })
    }

    fn clear_cache(&self) {
        self.inner.clear_cache();
    }

    fn reset(&self) -> Box<dyn iroh::dns::Resolver> {
        Box::new(PhoneDns::new(self.servers.clone()))
    }
}

/// An iroh endpoint on the public relays and address lookup (the `N0` preset),
/// answering `alpns`, with the phone's DNS servers ahead of the public ones.
async fn bind(key: SecretKey, alpns: &[&[u8]], dns: &DnsServers, relay: Option<&RelayUrl>) -> Result<Endpoint> {
    let mut builder = Endpoint::builder(presets::N0)
        .secret_key(key)
        .alpns(alpns.iter().map(|a| a.to_vec()).collect());
    // On Android a resolver is ALWAYS given, never left to iroh's default. The
    // default reads the system's DNS servers through JNI, and an executable has
    // no JVM: the read is caught and falls back to public resolvers, but Rust's
    // panic hook prints the caught panic first, so every start logged a panic
    // that was not one. Elsewhere — a computer running `serve` for the tests —
    // the system's own configuration is the right default.
    let given = dns.read().is_ok_and(|s| !s.is_empty());
    if cfg!(target_os = "android") || given {
        builder = builder.dns_resolver(iroh::dns::DnsResolver::custom(PhoneDns::new(dns.clone())));
    }
    if let Some(url) = relay {
        builder = builder.relay_mode(RelayMode::custom([url.clone()]));
    }
    if crate::relay_only() {
        builder = builder.clear_ip_transports();
    }
    builder.bind().await.context("cannot bind the endpoint")
}

// ── serve ─────────────────────────────────────────────────────────────────

/// Run until stdin says `quit` or closes.
pub async fn serve(cfg: Config) -> Result<()> {
    let identity = ensure_identity(&cfg.dir)?;
    let host_key_line = identity.host_key_line();
    let dns: DnsServers = Arc::new(RwLock::new(cfg.dns));
    let ep = bind(identity.key.clone(), &[ALPN_SSH, ALPN_CTL, ALPN_DESKTOP], &dns, cfg.relay.as_ref()).await?;
    let (events, writer) = Events::stdout();
    events.emit(format!("ready {} {}", ep.id(), b64url(&host_key_line)));
    info!("serving as {} (sshd on 127.0.0.1:{})", ep.id(), cfg.ssh_port);

    let (ssh, _) = watch::channel(SshState::Down);
    let (desktop, _) = watch::channel(Desktop { state: DesktopState::Unknown, password: String::new(), size: None });
    let shared = Arc::new(Shared {
        dir: cfg.dir,
        ssh_port: AtomicU16::new(cfg.ssh_port),
        desktop_port: cfg.desktop_port,
        desktop,
        name: cfg.name,
        host_key_line,
        dns,
        relay: cfg.relay,
        ep,
        events: events.clone(),
        peers: Mutex::new(Peers::default()),
        sessions: Mutex::new(HashMap::new()),
        next_session: AtomicU64::new(1),
        ssh,
        pairing: tokio::sync::Mutex::new(None),
        pending: Mutex::new(None),
        online: Mutex::new(None),
        dirty: Notify::new(),
    });
    shared.peers.lock().unwrap().refresh(&shared.peers_path());

    let tasks = [
        tokio::spawn(accept_loop(shared.clone())),
        tokio::spawn(watch_relay(shared.clone())),
        tokio::spawn(watch_peers(shared.clone())),
        tokio::spawn(write_status(shared.clone())),
    ];

    let result = command_loop(shared.clone()).await;

    info!("shutting down");
    for t in &tasks {
        t.abort();
    }
    if let Some(p) = shared.pairing.lock().await.take() {
        p.task.abort();
        p.endpoint.close().await;
    }
    for s in shared.sessions.lock().unwrap().values() {
        s.conn.close(CLOSE_SHUTDOWN.into(), b"remote access is off");
    }
    shared.ep.close().await;
    let _ = std::fs::remove_file(shared.dir.join("status.json"));
    drop(events);
    drop(shared);
    // Bounded: an aborted task can still be holding a clone of the sender at
    // this instant, and the writer only ends when the last one is gone.
    let _ = tokio::time::timeout(Duration::from_secs(2), writer).await;
    result
}

/// Commands from LinuxService, one per line:
///
/// | line | meaning |
/// |---|---|
/// | `pair <CODE> <TTL> [<UNTIL>]` | open a pairing for this code for TTL seconds, ending by UNTIL (unix time) at the latest |
/// | `pair-cancel` | close it early |
/// | `pair-commit <ID>` | the app saved computer ID; tell it yes |
/// | `pair-reject <ID>` | the app did not; tell it no |
/// | `net [DNS,…]` | Android saw the network change (iroh cannot, here), and the new network's DNS servers |
/// | `ssh <STATE>` | the guest sshd is up, starting, down, failed, missing or installing |
/// | `port <N>` | sshd now listens on 127.0.0.1:N |
/// | `desktop <STATE> <PASSWORD> <W>x<H>` | the desktop is running, starting, stopped, failed, unfinished, installing or missing; `-` for a part not known |
/// | `quit` | stop |
async fn command_loop(shared: Arc<Shared>) -> Result<()> {
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Some(line) = lines.next_line().await? {
        let mut words = line.split_whitespace();
        match (words.next(), words.next(), words.next()) {
            (Some("pair"), Some(code), ttl) => {
                let ttl = ttl.and_then(|t| t.parse::<u64>().ok()).unwrap_or(600).clamp(30, 3600);
                // An absolute deadline caps it when given: a code reopened
                // after a restart keeps the one it was first shown with,
                // however long the restart took.
                let mut until = unix_now() + ttl;
                if let Some(cap) = words.next().and_then(|u| u.parse::<u64>().ok()) {
                    until = until.min(cap);
                }
                start_pairing(&shared, code, until).await;
            }
            (Some("pair-cancel"), _, _) => {
                if let Some(p) = shared.pairing.lock().await.take() {
                    p.task.abort();
                    drop_pending(&shared);
                    let id = p.endpoint.id();
                    p.endpoint.close().await;
                    shared.events.emit(format!("pair-closed {id}"));
                    shared.dirty.notify_one();
                }
            }
            (Some(verb @ ("pair-commit" | "pair-reject")), Some(id), _) => {
                let mut pending = shared.pending.lock().unwrap();
                match pending.take() {
                    Some((who, answer)) if who.to_string() == id => {
                        let _ = answer.send(verb == "pair-commit");
                    }
                    other => {
                        warn!("{verb} for {id}, which is not the pending pairing");
                        *pending = other;
                    }
                }
            }
            (Some("net"), servers, _) => {
                debug!("network change from the app");
                // An open code's endpoint has a relay connection and an
                // address of its own, and moves networks with the phone too.
                let pairing = shared.pairing.lock().await.as_ref().map(|p| p.endpoint.clone());
                if set_dns(&shared, servers.unwrap_or("")) {
                    // Before network_change, so the reconnect it sets off
                    // already asks the new network's servers.
                    for ep in std::iter::once(&shared.ep).chain(pairing.as_ref()) {
                        if let Ok(resolver) = ep.dns_resolver() {
                            resolver.reset();
                        }
                    }
                }
                shared.ep.network_change().await;
                if let Some(ep) = pairing {
                    ep.network_change().await;
                }
            }
            (Some("ssh"), Some(state), _) => match SshState::parse(state) {
                Some(state) => {
                    shared.ssh.send_replace(state);
                    shared.dirty.notify_one();
                }
                None => warn!("unknown ssh state {state:?}"),
            },
            (Some("port"), Some(port), _) => match port.parse::<u16>() {
                Ok(port) if port > 0 => shared.ssh_port.store(port, Ordering::Relaxed),
                _ => warn!("bad port {port:?}"),
            },
            (Some("desktop"), Some(state), password) => match DesktopState::parse(state) {
                Some(state) => {
                    let password = password.filter(|p| *p != "-").unwrap_or("").to_string();
                    let size = words.next().and_then(parse_size);
                    shared.desktop.send_if_modified(|d| {
                        let now = Desktop { state, password, size };
                        let changed = *d != now;
                        *d = now;
                        changed
                    });
                }
                None => warn!("unknown desktop state {state:?}"),
            },
            (Some("quit"), _, _) => break,
            (None, _, _) => {}
            _ => warn!("unknown command {line:?}"),
        }
    }
    Ok(())
}

/// Take the servers from a `net` line. True when they changed.
///
/// An empty or unreadable list keeps the old one: Android reports a network
/// lost before it reports the next one gained, and a phone between networks
/// has nothing better to offer than what it had.
fn set_dns(shared: &Shared, list: &str) -> bool {
    let servers: Vec<IpAddr> = list.split(',').filter_map(|s| s.trim().parse().ok()).collect();
    if servers.is_empty() {
        return false;
    }
    let Ok(mut current) = shared.dns.write() else { return false };
    if *current == servers {
        return false;
    }
    info!("DNS servers now {servers:?}");
    *current = servers;
    true
}

// ── incoming connections ──────────────────────────────────────────────────

async fn accept_loop(shared: Arc<Shared>) {
    while let Some(incoming) = shared.ep.accept().await {
        let shared = shared.clone();
        tokio::spawn(async move {
            let conn = match incoming.accept() {
                Ok(accepting) => match accepting.await {
                    Ok(conn) => conn,
                    Err(e) => return debug!("handshake failed: {e}"),
                },
                Err(e) => return debug!("incoming refused: {e}"),
            };
            let kind = match conn.alpn() {
                a if a == ALPN_SSH => Kind::Ssh,
                a if a == ALPN_CTL => Kind::Ctl,
                a if a == ALPN_DESKTOP => Kind::Desktop,
                _ => return conn.close(CLOSE_NOT_PAIRED.into(), b"unknown protocol"),
            };
            serve_conn(shared, conn, kind).await;
        });
    }
}

/// What a connection is for, by its ALPN.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Ssh,
    Ctl,
    Desktop,
}

/// Every stream on a connection is one SSH connection (or one status
/// question, or one desktop request), each authorised on its first frame —
/// see [`Shared::computer_for`]. A connection that has not produced an
/// authorised stream within fifteen seconds is closed: nobody gets to hold one
/// open for free on the strength of a handshake alone.
async fn serve_conn(shared: Arc<Shared>, conn: Connection, kind: Kind) {
    let remote = conn.remote_id();
    let authorised = Arc::new(Notify::new());
    {
        let conn = conn.clone();
        let authorised = authorised.clone();
        tokio::spawn(async move {
            if tokio::time::timeout(Duration::from_secs(15), authorised.notified()).await.is_err() {
                debug!("{}: no authorised stream in time", remote.fmt_short());
                conn.close(CLOSE_NOT_PAIRED.into(), b"not paired");
            }
        });
    }
    let desktop = Arc::new(DesktopLink::default());
    loop {
        let (send, recv) = match conn.accept_bi().await {
            Ok(pair) => pair,
            Err(e) => {
                debug!("{} gone: {e}", remote.fmt_short());
                break;
            }
        };
        let (shared, conn, authorised) = (shared.clone(), conn.clone(), authorised.clone());
        match kind {
            Kind::Ssh => {
                tokio::spawn(ssh_stream(shared, conn, send, recv, authorised));
            }
            Kind::Ctl => {
                tokio::spawn(ctl_stream(shared, conn, send, recv, authorised));
            }
            Kind::Desktop => {
                tokio::spawn(desktop_stream(shared, conn, send, recv, authorised, desktop.clone()));
            }
        }
    }
    desktop.end(&shared);
}

/// A desktop connection is one session, however many streams it opens — a
/// page load alone is dozens — and it lasts exactly as long as the connection:
/// a viewer sitting on an unchanging desktop moves no bytes, and is still
/// someone the phone has to stay awake for.
#[derive(Default)]
struct DesktopLink {
    /// The session, once a stream has been admitted; and whether the
    /// connection is over, so a stream admitted as it closed opens nothing.
    session: Mutex<(Option<u64>, bool)>,
    traffic: Arc<Traffic>,
}

impl DesktopLink {
    fn join(&self, shared: &Shared, peer: EndpointId, conn: &Connection) {
        let mut slot = self.session.lock().unwrap();
        if slot.0.is_none() && !slot.1 {
            slot.0 = Some(shared.open_session(peer, conn.clone(), self.traffic.clone()));
        }
    }

    fn end(&self, shared: &Shared) {
        let mut slot = self.session.lock().unwrap();
        slot.1 = true;
        if let Some(id) = slot.0.take() {
            shared.close_session(id);
        }
    }
}

/// The paired computer behind this stream's first frame, or close the whole
/// connection with "not paired" — which the computer turns into a sentence.
fn admit(shared: &Shared, conn: &Connection, auth: Option<&proto::Auth>, authorised: &Notify) -> Option<EndpointId> {
    match shared.computer_for(&conn.remote_id(), auth) {
        Some(computer) => {
            authorised.notify_one();
            Some(computer)
        }
        None => {
            info!("turned away {}: not a paired computer", conn.remote_id().fmt_short());
            conn.close(CLOSE_NOT_PAIRED.into(), b"not paired");
            None
        }
    }
}

async fn ssh_stream(shared: Arc<Shared>, conn: Connection, mut send: SendStream, mut recv: RecvStream, authorised: Arc<Notify>) {
    let hello = match tokio::time::timeout(Duration::from_secs(15), proto::read_frame::<_, SshHello>(&mut recv)).await {
        Ok(Ok(hello)) => hello,
        _ => return debug!("{}: no hello", conn.remote_id().fmt_short()),
    };
    let Some(peer) = admit(&shared, &conn, hello.auth.as_ref(), &authorised) else {
        return;
    };
    info!("{} connected", peer.fmt_short());
    let tcp = match connect_sshd(&shared).await {
        Ok(tcp) => tcp,
        Err(refusal) => {
            info!("{}: refused, {refusal:?}", peer.fmt_short());
            let _ = proto::write_frame(&mut send, &SshReady { ok: false, refusal: Some(refusal) }).await;
            let _ = send.finish();
            let _ = tokio::time::timeout(Duration::from_secs(5), send.stopped()).await;
            return;
        }
    };
    if proto::write_frame(&mut send, &SshReady { ok: true, refusal: None }).await.is_err() {
        return;
    }
    let traffic = Arc::new(Traffic::default());
    let id = shared.open_session(peer, conn, traffic.clone());
    let (r, w) = tcp.into_split();
    // sshd closes the socket outright when the session is over; see splice.
    if let Err(e) = splice(r, w, send, recv, &traffic, Some(Duration::from_secs(2))).await {
        debug!("{}: session ended with {e}", peer.fmt_short());
    }
    shared.close_session(id);
}

/// A socket to the guest sshd, waiting for it to come up when it is on its way.
///
/// sshd runs while Remote access is on, so this is normally one connect. The
/// waiting is for the moments it is not there: the first connection after the
/// phone booted, a Linux that was still finishing its setup, an sshd that
/// crashed. `need-ssh` asks the app to (re)start it, and the computer's ssh is
/// told the reason in words if it never arrives.
async fn connect_sshd(shared: &Shared) -> Result<TcpStream, Refusal> {
    let deadline = Instant::now() + Duration::from_secs(45);
    let mut asked: Option<Instant> = None;
    loop {
        // Read each time round: the app may move sshd while this waits for it.
        let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, shared.ssh_port.load(Ordering::Relaxed)));
        if let Ok(tcp) = TcpStream::connect(addr).await {
            // Keystrokes, not bulk: an interactive shell is the common case.
            let _ = tcp.set_nodelay(true);
            return Ok(tcp);
        }
        let state = *shared.ssh.borrow();
        match state {
            SshState::Missing => return Err(Refusal::LinuxMissing),
            SshState::Installing => return Err(Refusal::LinuxInstalling),
            SshState::Starting => {}
            // Down, Failed, or Up-but-refusing (it died): all of them want a
            // start, and asking twice is harmless. A Failed that is still
            // there a few seconds after asking is the app's answer to the
            // request rather than the state from before it.
            SshState::Up | SshState::Down | SshState::Failed => match asked {
                None => {
                    shared.events.emit("need-ssh");
                    asked = Some(Instant::now());
                }
                Some(at) if state == SshState::Failed && at.elapsed() > Duration::from_secs(3) => {
                    return Err(Refusal::SshFailed);
                }
                Some(_) => {}
            },
        }
        if Instant::now() >= deadline {
            return Err(Refusal::SshFailed);
        }
        tokio::time::sleep(Duration::from_millis(400)).await;
    }
}

/// One status answer, for the computer's list of phones.
async fn ctl_stream(shared: Arc<Shared>, conn: Connection, mut send: SendStream, mut recv: RecvStream, authorised: Arc<Notify>) {
    let request = match tokio::time::timeout(Duration::from_secs(15), proto::read_frame::<_, CtlRequest>(&mut recv)).await {
        Ok(Ok(request)) => request,
        _ => return,
    };
    if admit(&shared, &conn, request.auth.as_ref(), &authorised).is_none() {
        return;
    }
    let status = CtlStatus {
        name: shared.name.clone(),
        ssh: shared.ssh.borrow().as_str().to_string(),
        sessions: shared.sessions.lock().unwrap().len() as u32,
        version: env!("CARGO_PKG_VERSION").to_string(),
        features: vec![proto::FEATURE_DESKTOP.to_string()],
        desktop: shared.desktop.borrow().state.as_str().to_string(),
    };
    let _ = proto::write_frame(&mut send, &status).await;
    let _ = send.finish();
    let _ = tokio::time::timeout(Duration::from_secs(5), send.stopped()).await;
}

// ── the desktop ───────────────────────────────────────────────────────────

/// How long an `open` that asked for a start waits for the desktop. XFCE under
/// proot takes five to fifteen seconds to come up on a phone, and the first
/// start after an install a good deal longer.
const DESKTOP_START_WAIT: Duration = Duration::from_secs(120);
/// How long an `open` that did not ask for one waits for a desktop that is
/// already on its way up — someone else's start.
const DESKTOP_JOIN_WAIT: Duration = Duration::from_secs(30);

/// One desktop stream: an `open`, answered with how to log in, or a `web`
/// stream put through to websockify. Admitted on its first frame like every
/// other stream; the first one admitted makes the connection a session.
async fn desktop_stream(
    shared: Arc<Shared>,
    conn: Connection,
    mut send: SendStream,
    mut recv: RecvStream,
    authorised: Arc<Notify>,
    link: Arc<DesktopLink>,
) {
    let hello = match tokio::time::timeout(Duration::from_secs(15), proto::read_frame::<_, DesktopHello>(&mut recv)).await {
        Ok(Ok(hello)) => hello,
        _ => return debug!("{}: no desktop hello", conn.remote_id().fmt_short()),
    };
    let Some(peer) = admit(&shared, &conn, hello.auth.as_ref(), &authorised) else {
        return;
    };
    link.join(&shared, peer, &conn);
    match hello.op {
        DesktopOp::Open => {
            let reply = match open_desktop(&shared, hello.start, &mut send).await {
                Ok(desktop) => {
                    info!("{}: the desktop is up for it", peer.fmt_short());
                    DesktopReply { ok: true, password: Some(desktop.password), size: desktop.size, ..Default::default() }
                }
                Err(refusal) => {
                    info!("{}: no desktop, {refusal:?}", peer.fmt_short());
                    DesktopReply::refused(refusal)
                }
            };
            let _ = proto::write_frame(&mut send, &reply).await;
            let _ = send.finish();
            let _ = tokio::time::timeout(Duration::from_secs(5), send.stopped()).await;
        }
        DesktopOp::Ping => {
            let _ = proto::write_frame(&mut send, &DesktopReply { ok: true, ..Default::default() }).await;
            let _ = send.finish();
        }
        DesktopOp::Web => {
            let tcp = match connect_desktop(&shared).await {
                Ok(tcp) => tcp,
                Err(refusal) => {
                    let _ = proto::write_frame(&mut send, &DesktopReply::refused(refusal)).await;
                    let _ = send.finish();
                    let _ = tokio::time::timeout(Duration::from_secs(5), send.stopped()).await;
                    return;
                }
            };
            if proto::write_frame(&mut send, &DesktopReply { ok: true, ..Default::default() }).await.is_err() {
                return;
            }
            let (r, w) = tcp.into_split();
            // websockify closes the socket when it has answered, as sshd does
            // when a session is over; see splice.
            if let Err(e) = splice(r, w, send, recv, &link.traffic, Some(Duration::from_secs(2))).await {
                debug!("{}: desktop stream ended with {e}", peer.fmt_short());
            }
        }
    }
}

/// The desktop, up and answering — asking the app to start it first when the
/// computer said to.
///
/// Starting is the app's: it owns the container, and the Linux window on the
/// phone starts the same session the same way. So this asks with a line,
/// `desktop-start <W> <H>`, and watches what the app says back. Three rules:
///
/// * **A running desktop is never restarted**, whatever size was asked for —
///   it has someone's windows in it. The computer gets it at its own size.
/// * **Only an `open` that says `start` starts anything.** A computer whose
///   viewer lost the connection asks without it, so a desktop the user logged
///   out of is not booted straight back up behind them.
/// * **Up means websockify answers**, not merely that the app has a session:
///   it comes up a few seconds after Xvnc, and a page loaded before it would
///   fail. While the app says `running` the port is tried twice a second.
async fn open_desktop(shared: &Shared, start: Option<DesktopSize>, send: &mut SendStream) -> Result<Desktop, Refusal> {
    let mut changes = shared.desktop.subscribe();
    let deadline = Instant::now() + if start.is_some() { DESKTOP_START_WAIT } else { DESKTOP_JOIN_WAIT };
    let mut asked: Option<Instant> = None;
    let mut told = false;
    loop {
        let now = changes.borrow_and_update().clone();
        if now.state == DesktopState::Running && !now.password.is_empty() && desktop_answers(shared).await {
            return Ok(now);
        }
        match now.state {
            DesktopState::Missing => return Err(Refusal::LinuxMissing),
            DesktopState::Installing => return Err(Refusal::LinuxInstalling),
            DesktopState::Unfinished => return Err(Refusal::LinuxUnfinished),
            // The app answers a start with `starting` straight away, so a
            // Failed still there seconds after asking is the answer to this
            // request rather than what was said before it.
            DesktopState::Failed if asked.is_some_and(|at| at.elapsed() > Duration::from_secs(3)) => {
                return Err(Refusal::DesktopFailed)
            }
            DesktopState::Stopped | DesktopState::Failed if asked.is_none() => match start {
                None if now.state == DesktopState::Failed => return Err(Refusal::DesktopFailed),
                None => return Err(Refusal::DesktopStopped),
                Some(size) => {
                    let size = clamp_size(size);
                    shared.events.emit(format!("desktop-start {} {}", size.w, size.h));
                    asked = Some(Instant::now());
                }
            },
            _ => {}
        }
        // Said once, whoever is doing the starting: the computer's window has
        // something better to show for a minute than a spinner.
        if !told && (asked.is_some() || matches!(now.state, DesktopState::Starting | DesktopState::Running)) {
            told = true;
            proto::write_frame(send, &DesktopReply { starting: true, ..Default::default() })
                .await
                .map_err(|_| Refusal::DesktopFailed)?;
        }
        if Instant::now() >= deadline {
            return Err(Refusal::DesktopFailed);
        }
        let _ = tokio::time::timeout(Duration::from_millis(500), changes.changed()).await;
    }
}

/// websockify is accepting connections.
async fn desktop_answers(shared: &Shared) -> bool {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, shared.desktop_port));
    matches!(tokio::time::timeout(Duration::from_secs(2), TcpStream::connect(addr)).await, Ok(Ok(_)))
}

/// A socket to websockify for one `web` stream. A desktop that is up answers
/// at once; the short wait is for one that is still coming up, or whose
/// websockify is being restarted with it.
async fn connect_desktop(shared: &Shared) -> Result<TcpStream, Refusal> {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, shared.desktop_port));
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(tcp) = TcpStream::connect(addr).await {
            // Pointer moves and keystrokes: an interactive stream, like SSH.
            let _ = tcp.set_nodelay(true);
            return Ok(tcp);
        }
        match shared.desktop.borrow().state {
            DesktopState::Missing => return Err(Refusal::LinuxMissing),
            DesktopState::Installing => return Err(Refusal::LinuxInstalling),
            DesktopState::Running | DesktopState::Starting | DesktopState::Unknown if Instant::now() < deadline => {}
            _ => return Err(Refusal::DesktopStopped),
        }
        tokio::time::sleep(Duration::from_millis(300)).await;
    }
}

// ── background watchers ───────────────────────────────────────────────────

/// `online <relay>` / `offline`, as the home relay comes and goes. Online here
/// means reachable from anywhere: a connected home relay is the meeting point
/// every computer dials first.
async fn watch_relay(shared: Arc<Shared>) {
    let mut watcher = shared.ep.home_relay_status();
    loop {
        let now = watcher
            .get()
            .into_iter()
            .find(|s| s.is_connected())
            .map(|s| s.url().to_string());
        let changed = {
            let mut online = shared.online.lock().unwrap();
            let changed = *online != now;
            *online = now.clone();
            changed
        };
        if changed {
            match &now {
                Some(url) => shared.events.emit(format!("online {url}")),
                None => shared.events.emit("offline"),
            }
            shared.dirty.notify_one();
        }
        if watcher.updated().await.is_err() {
            break;
        }
    }
}

/// "Remove" on the phone has to mean now, not at the next reconnect: a removed
/// computer's open sessions are closed as soon as the file says so.
///
/// Every tick checks every session, whether or not this tick was the one that
/// noticed the file change — `is_paired` refreshes the same list on each new
/// connection, and a watcher that only acted on its own refreshes would miss
/// any removal a connection happened to read first.
async fn watch_peers(shared: Arc<Shared>) {
    let mut tick = tokio::time::interval(Duration::from_secs(2));
    // Connections already closed, so a session still unwinding is not closed
    // (and announced) again on every tick until it is gone.
    let mut closed: std::collections::HashSet<usize> = Default::default();
    loop {
        tick.tick().await;
        let peers: std::collections::HashSet<EndpointId> = {
            let mut peers = shared.peers.lock().unwrap();
            peers.refresh(&shared.peers_path());
            peers.names.keys().copied().collect()
        };
        let mut revoked = Vec::new();
        {
            let sessions = shared.sessions.lock().unwrap();
            closed.retain(|id| sessions.values().any(|s| s.conn.stable_id() == *id));
            for s in sessions.values() {
                if !peers.contains(&s.peer) && closed.insert(s.conn.stable_id()) {
                    s.conn.close(CLOSE_UNPAIRED.into(), b"unpaired");
                    revoked.push(s.peer);
                }
            }
        }
        revoked.sort();
        revoked.dedup();
        for peer in revoked {
            info!("{} was removed; its sessions are closed", peer.fmt_short());
            shared.events.emit(format!("revoked {peer}"));
        }
    }
}

#[derive(Serialize)]
struct StatusFile {
    v: u32,
    id: String,
    pid: u32,
    online: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    relay: Option<String>,
    ssh: &'static str,
    sessions: Vec<SessionStatus>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pairing_until: Option<u64>,
    updated: u64,
}

#[derive(Serialize)]
struct SessionStatus {
    peer: String,
    name: String,
    since: u64,
    /// The path in use is a direct one (not through a relay).
    direct: bool,
    rtt_ms: u64,
    #[serde(rename = "in")]
    inn: u64,
    out: u64,
}

/// `status.json`, for the window on the phone: rewritten when something it
/// shows changes, and every few seconds regardless so the path and the byte
/// counts stay current.
async fn write_status(shared: Arc<Shared>) {
    let path = shared.dir.join("status.json");
    loop {
        let pairing_until = shared.pairing.lock().await.as_ref().map(|p| p.until);
        let status = {
            let names = shared.peers.lock().unwrap().names.clone();
            let sessions = shared.sessions.lock().unwrap();
            let mut list: Vec<SessionStatus> = sessions
                .values()
                .map(|s| {
                    let (direct, rtt_ms) = selected_path(&s.conn);
                    SessionStatus {
                        peer: s.peer.to_string(),
                        name: names.get(&s.peer).cloned().unwrap_or_default(),
                        since: s.since,
                        direct,
                        rtt_ms,
                        inn: s.traffic.inn.load(Ordering::Relaxed),
                        out: s.traffic.out.load(Ordering::Relaxed),
                    }
                })
                .collect();
            list.sort_by_key(|s| s.since);
            let relay = shared.online.lock().unwrap().clone();
            StatusFile {
                v: 1,
                id: shared.ep.id().to_string(),
                pid: std::process::id(),
                online: relay.is_some(),
                relay,
                ssh: shared.ssh.borrow().as_str(),
                sessions: list,
                pairing_until,
                updated: unix_now(),
            }
        };
        if let Ok(json) = serde_json::to_vec(&status) {
            if let Err(e) = write_atomic(&path, &json) {
                debug!("status.json: {e}");
            }
        }
        let _ = tokio::time::timeout(Duration::from_secs(5), shared.dirty.notified()).await;
    }
}

/// Whether the connection's chosen path is direct, and its round trip.
fn selected_path(conn: &Connection) -> (bool, u64) {
    conn.paths()
        .iter()
        .find(|p| p.is_selected())
        .map(|p| (p.is_ip(), p.rtt().as_millis() as u64))
        .unwrap_or((false, 0))
}

// ── pairing ───────────────────────────────────────────────────────────────

/// Answers every `pair` with exactly one event, in order: `pair-listening` or
/// a `pair-failed` that names no pairing, because none was opened. That is how
/// the app tells the answer to its latest code from an earlier one's.
async fn start_pairing(shared: &Arc<Shared>, code: &str, until: u64) {
    if let Some(old) = shared.pairing.lock().await.take() {
        old.task.abort();
        drop_pending(shared);
        old.endpoint.close().await;
    }
    if until <= unix_now() + 5 {
        // Reopened with nothing left on its clock.
        shared.events.emit("pair-failed expired");
        return;
    }
    let code = code.to_string();
    // Tens of milliseconds of PBKDF2: off the async workers.
    let keys = match tokio::task::spawn_blocking(move || PairKeys::derive(&code)).await {
        Ok(Some(keys)) => keys,
        _ => {
            warn!("pair: that is not a code");
            shared.events.emit("pair-failed invalid");
            return;
        }
    };
    let endpoint = match bind(keys.endpoint.clone(), &[ALPN_PAIR], &shared.dns, shared.relay.as_ref()).await {
        Ok(ep) => ep,
        Err(e) => {
            warn!("pair: {e:#}");
            shared.events.emit("pair-failed error");
            return;
        }
    };
    shared.events.emit(format!("pair-listening {} {until}", endpoint.id()));
    info!("pairing open as {} for {}s", endpoint.id().fmt_short(), until.saturating_sub(unix_now()));

    let task = tokio::spawn(run_pairing(shared.clone(), endpoint.clone(), keys, until));
    *shared.pairing.lock().await = Some(Pairing { endpoint, task, until });
    shared.dirty.notify_one();
}

/// A computer the app was being asked about has lost its pairing — the code
/// was replaced or cancelled. Its answer slot goes, so a late `pair-commit`
/// for it is not taken as consent to anything.
fn drop_pending(shared: &Shared) {
    shared.pending.lock().unwrap().take();
}

enum PairOutcome {
    Paired,
    Refused,
    TooManyBadCodes,
    Expired,
}

/// Accept pairing attempts until one succeeds, the code expires, or enough
/// wrong proofs arrive to say this is not a typo. Then close: a code is good
/// for one computer, once.
async fn run_pairing(shared: Arc<Shared>, endpoint: Endpoint, keys: PairKeys, until: u64) {
    let keys = Arc::new(keys);
    let attempt = async {
        let mut bad = 0;
        while let Some(incoming) = endpoint.accept().await {
            let conn = match incoming.accept() {
                Ok(a) => match a.await {
                    Ok(c) => c,
                    Err(_) => continue,
                },
                Err(_) => continue,
            };
            match pair_one(&shared, &keys, conn, until).await {
                Ok(Some(PairOutcome::Paired)) => return PairOutcome::Paired,
                Ok(Some(PairOutcome::Refused)) => return PairOutcome::Refused,
                Ok(Some(PairOutcome::Expired)) => return PairOutcome::Expired,
                Ok(Some(PairOutcome::TooManyBadCodes)) | Ok(None) => {
                    bad += 1;
                    if bad >= 5 {
                        return PairOutcome::TooManyBadCodes;
                    }
                }
                Err(e) => debug!("pairing attempt failed: {e:#}"),
            }
        }
        PairOutcome::Refused
    };
    // The code's life is counted on the wall clock, which is what the phone's
    // screen counts down — not on tokio's, which stops while the phone sleeps:
    // a 600 s timer there would outlast the countdown by however long the
    // phone slept, and a code the window calls expired would still let
    // whoever saw it in. Checked every two seconds of awake time, so the first
    // moments after a wake end it.
    let expiry = async {
        let mut tick = tokio::time::interval(Duration::from_secs(2));
        loop {
            tick.tick().await;
            if unix_now() >= until {
                break;
            }
        }
    };
    let outcome = tokio::select! {
        outcome = attempt => outcome,
        () = expiry => PairOutcome::Expired,
    };
    // Every event from here on names the pairing it is about. The app may have
    // opened a new code by the time it reads one of these, and an old code's
    // "expired" must not be shown as the new one's.
    let id = endpoint.id();
    match outcome {
        PairOutcome::Paired => {}
        PairOutcome::Refused => shared.events.emit(format!("pair-failed rejected {id}")),
        PairOutcome::TooManyBadCodes => shared.events.emit(format!("pair-failed bad-code {id}")),
        PairOutcome::Expired => shared.events.emit(format!("pair-failed expired {id}")),
    }
    endpoint.close().await;
    // Only clear the slot if it is still ours: a new `pair` replaces it, and
    // must not be cleared by the old one finishing behind it.
    let mut slot = shared.pairing.lock().await;
    if slot.as_ref().map(|p| p.endpoint.id()) == Some(id) {
        *slot = None;
    }
    drop(slot);
    shared.events.emit(format!("pair-closed {id}"));
    shared.dirty.notify_one();
}

/// One computer's attempt. `Ok(None)` is a wrong code: answered, and counted.
async fn pair_one(shared: &Shared, keys: &PairKeys, conn: Connection, until: u64) -> Result<Option<PairOutcome>> {
    let (mut send, mut recv) = tokio::time::timeout(Duration::from_secs(20), conn.accept_bi())
        .await
        .context("no stream")??;
    let req: PairRequest = proto::read_frame(&mut recv).await?;

    // The computer is whoever signed for this endpoint, when it says so — and
    // the endpoint itself otherwise. Either way it is an identity the QUIC
    // handshake or a signature over that handshake's key has proven, never
    // one the request merely names.
    let remote = conn.remote_id();
    let computer = match &req.auth {
        Some(auth) => match auth.computer.parse::<EndpointId>().ok().filter(|c| {
            data_encoding::HEXLOWER_PERMISSIVE
                .decode(auth.sig.as_bytes())
                .ok()
                .and_then(|sig| iroh::Signature::try_from(sig.as_slice()).ok())
                .is_some_and(|sig| c.verify(&proto::delegation_message(&remote), &sig).is_ok())
        }) {
            Some(c) => c,
            None => {
                proto::write_frame(&mut send, &PairResponse::refused(PairError::Invalid)).await?;
                let _ = send.finish();
                return Ok(None);
            }
        },
        None => remote,
    };

    let expected = keys.computer_proof(&computer, &req.name, &req.os, &req.ssh_key);
    if !PairKeys::verify(&expected, &req.proof) {
        info!("pairing from {}: wrong code", computer.fmt_short());
        proto::write_frame(&mut send, &PairResponse::refused(PairError::BadCode)).await?;
        let _ = send.finish();
        let _ = tokio::time::timeout(Duration::from_secs(3), send.stopped()).await;
        return Ok(None);
    }
    let name = clean_name(&req.name);
    let os = match req.os.as_str() {
        "windows" | "macos" | "linux" => req.os.as_str(),
        _ => "other",
    };
    if name.is_empty() || sshkey::parse_public_line(&req.ssh_key).is_none() {
        proto::write_frame(&mut send, &PairResponse::refused(PairError::Invalid)).await?;
        let _ = send.finish();
        return Ok(None);
    }

    // The right code, but its time is up by the wall clock — the phone may
    // have slept through the end of it with this connection already open.
    if unix_now() >= until {
        proto::write_frame(&mut send, &PairResponse::refused(PairError::Expired)).await?;
        let _ = send.finish();
        let _ = tokio::time::timeout(Duration::from_secs(3), send.stopped()).await;
        return Ok(Some(PairOutcome::Expired));
    }

    // The app writes peers.json and authorized_keys, then says so. Until it
    // does, this computer is not paired, whatever it proved. The request names
    // its pairing, so the app can refuse one whose code is no longer on
    // screen — replaced or cancelled while this computer was typing.
    let (answer, decided) = oneshot::channel();
    *shared.pending.lock().unwrap() = Some((computer, answer));
    shared.events.emit(format!(
        "pair-request {computer} {} {os} {} {}",
        b64url(&name),
        b64url(req.ssh_key.trim()),
        keys.endpoint_id()
    ));
    let saved = matches!(tokio::time::timeout(Duration::from_secs(20), decided).await, Ok(Ok(true)));
    shared.pending.lock().unwrap().take();
    if !saved {
        proto::write_frame(&mut send, &PairResponse::refused(PairError::Rejected)).await?;
        let _ = send.finish();
        let _ = tokio::time::timeout(Duration::from_secs(3), send.stopped()).await;
        return Ok(Some(PairOutcome::Refused));
    }

    let phone = shared.ep.id();
    let response = PairResponse {
        ok: true,
        error: None,
        id: Some(phone.to_string()),
        name: Some(shared.name.clone()),
        user: Some(GUEST_USER.to_string()),
        host_key: Some(shared.host_key_line.clone()),
        proof: Some(data_encoding::HEXLOWER.encode(&keys.phone_proof(&phone, &computer, &shared.host_key_line))),
    };
    proto::write_frame(&mut send, &response).await?;
    let _ = send.finish();
    // Said as soon as the answer is on its way, not after it is acknowledged:
    // the wait below can run into the code's expiry, and a computer that has
    // its answer and is saved on the phone was paired, whatever the clock says.
    info!("paired with {} ({name})", computer.fmt_short());
    shared.events.emit(format!("paired {computer} {}", keys.endpoint_id()));
    let _ = tokio::time::timeout(Duration::from_secs(5), send.stopped()).await;
    Ok(Some(PairOutcome::Paired))
}

/// A computer's name as the phone will show it: one line, printable, short.
fn clean_name(name: &str) -> String {
    let cleaned: String = name.chars().filter(|c| !c.is_control()).take(64).collect();
    cleaned.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_is_made_once_and_kept() {
        let dir = std::env::temp_dir().join(format!("dexremote-id-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let a = ensure_identity(&dir).unwrap();
        let b = ensure_identity(&dir).unwrap();
        assert_eq!(a.key.public(), b.key.public());
        assert_eq!(a.host_public, b.host_public);
        // sshd reads the private host key, so it has to be one sshd accepts.
        let pem = std::fs::read_to_string(dir.join(GUEST_DIR).join(HOST_KEY)).unwrap();
        assert!(sshkey::parse_private_pem(&pem).is_some());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(dir.join(GUEST_DIR).join(HOST_KEY)).unwrap().permissions().mode();
            assert_eq!(mode & 0o077, 0, "sshd refuses a host key others can read");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_broken_peers_file_admits_no_one() {
        let dir = std::env::temp_dir().join(format!("dexremote-peers-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("peers.json");
        let id = SecretKey::from_bytes(&[3; 32]).public();
        std::fs::write(&path, format!(r#"{{"v":1,"peers":[{{"id":"{id}","name":"PC"}}]}}"#)).unwrap();
        let mut peers = Peers::default();
        assert!(peers.refresh(&path));
        assert!(peers.names.contains_key(&id));
        assert!(!peers.refresh(&path), "an unchanged file is not re-read");
        // Replaced the way the app replaces it: a new file renamed over.
        let tmp = dir.join("peers.tmp");
        std::fs::write(&tmp, b"{ not json").unwrap();
        std::fs::rename(&tmp, &path).unwrap();
        assert!(peers.refresh(&path));
        assert!(peers.names.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn desktop_sizes_are_read_and_kept_to_what_a_phone_can_carry() {
        assert_eq!(parse_size("1512x982"), Some(DesktopSize { w: 1512, h: 982 }));
        assert_eq!(parse_size("-"), None);
        assert_eq!(parse_size("1512x"), None);
        assert_eq!(clamp_size(DesktopSize { w: 1512, h: 982 }), DesktopSize { w: 1512, h: 982 });
        assert_eq!(clamp_size(DesktopSize { w: 99_999, h: 99_999 }), DESKTOP_MAX);
        assert_eq!(clamp_size(DesktopSize { w: 0, h: 0 }), DESKTOP_MIN);
        assert_eq!(DesktopState::parse("running").map(DesktopState::as_str), Some("running"));
        assert_eq!(DesktopState::parse("unknown"), None, "unknown is ours to say, never the app's");
    }

    #[test]
    fn names_are_one_printable_line() {
        assert_eq!(clean_name("  DESKTOP-1\n\r\t "), "DESKTOP-1");
        assert_eq!(clean_name(&"x".repeat(200)).len(), 64);
    }

    #[test]
    fn two_first_starts_at_once_agree_on_one_identity() {
        let dir = std::env::temp_dir().join(format!("dexremote-race-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let threads: Vec<_> = (0..8)
            .map(|_| {
                let dir = dir.clone();
                std::thread::spawn(move || {
                    let id = ensure_identity(&dir).unwrap();
                    (id.key.public(), id.host_public)
                })
            })
            .collect();
        let seen: Vec<_> = threads.into_iter().map(|t| t.join().unwrap()).collect();
        assert!(seen.windows(2).all(|w| w[0] == w[1]), "every caller got the same keys");
        let on_disk = ensure_identity(&dir).unwrap();
        assert_eq!(on_disk.key.public(), seen[0].0, "and they are the ones on disk");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// What code inside the guest can do to the directory bound into it — and
    /// none of it may reach a file outside that directory.
    #[cfg(unix)]
    #[test]
    fn links_the_guest_plants_are_never_followed() {
        use std::os::unix::fs::symlink;
        let root = std::env::temp_dir().join(format!("dexremote-links-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let dir = root.join("state");
        let victim = root.join("victim");
        std::fs::create_dir_all(dir.join(GUEST_DIR)).unwrap();
        std::fs::write(&victim, b"precious").unwrap();

        // The host key and its .pub replaced by links to someone else's file.
        symlink(&victim, dir.join(GUEST_DIR).join(HOST_KEY)).unwrap();
        symlink(&victim, dir.join(GUEST_DIR).join(format!("{HOST_KEY}.pub"))).unwrap();
        let id = ensure_identity(&dir).unwrap();
        assert_eq!(std::fs::read(&victim).unwrap(), b"precious");
        let host = std::fs::symlink_metadata(dir.join(GUEST_DIR).join(HOST_KEY)).unwrap();
        assert!(host.is_file(), "a real key file took the link's place");
        let pem = std::fs::read_to_string(dir.join(GUEST_DIR).join(HOST_KEY)).unwrap();
        let seed = sshkey::parse_private_pem(&pem).unwrap();
        assert_eq!(*SecretKey::from_bytes(&seed).public().as_bytes(), id.host_public);

        // The whole directory swapped for a link to another one.
        std::fs::remove_dir_all(dir.join(GUEST_DIR)).unwrap();
        let elsewhere = root.join("elsewhere");
        std::fs::create_dir_all(&elsewhere).unwrap();
        symlink(&elsewhere, dir.join(GUEST_DIR)).unwrap();
        ensure_identity(&dir).unwrap();
        assert!(std::fs::symlink_metadata(dir.join(GUEST_DIR)).unwrap().is_dir());
        assert_eq!(std::fs::read_dir(&elsewhere).unwrap().count(), 0, "nothing was written through it");
        let _ = std::fs::remove_dir_all(&root);
    }
}
