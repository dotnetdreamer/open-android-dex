//! A phone running postmarketOS, on the Desktop tab: a screen of its own on
//! the phone, made by its desktop (KDE Plasma or Phosh), in a window here, the
//! way DeX gives an Android phone's.
//!
//! There is no adb. postmarketOS puts a network on the USB cable instead, on
//! which the phone is always 172.16.42.1, with SSH. Over that SSH this computer
//! signs in once with the phone's own password, leaves a key of its own in its
//! authorized_keys, and from then on needs nothing typed: it puts its helper
//! on the phone (openandroiddex-pmos, built in that folder and embedded here),
//! starts it, and talks to it on its stdin and stdout. On Plasma the helper
//! asks KWin for a virtual output the size of this computer's screen, which
//! Plasma fills with a desktop as it would a monitor plugged in; on Phosh it
//! sizes wlroots' headless output and makes it Phosh's primary screen (see the
//! helper's "Phosh" section). Either way it sends the screen as H.264, and the
//! window sends the mouse and keyboard back.
//!
//! The window is the one the phone's Linux uses over adb (UsbDesktop.tsx),
//! because the helper speaks its messages. This module is the WebSocket it
//! connects to: a port on this computer's loopback that takes only the app's
//! own page with the window's token, checked as remote_usb.rs checks its own
//! (`check_request`), and that turns the socket's messages into the helper's
//! frames and back: `[kind u8][length u32 BE][payload]`, kind 0 JSON text and
//! 1 binary, the socket's two kinds of message.
//!
//! One phone at a time: every postmarketOS phone is 172.16.42.1 on its cable.
//! Which one is plugged in is told by its SSH host key, which pairing pins.
//!
//! The clipboard rides the window's socket as `clip` messages, which the
//! helper puts on the phone's clipboard and takes from it. Files dropped on the
//! window go over a second channel of the same SSH connection, to the helper's
//! `receive`, into the phone's Downloads; then the helper drops them on the app
//! where they were let go of (`pmos_put`).

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use dexremote::sftp::{self, CancellationToken};
use russh::client::{self, Handle};
use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::{HashAlg, PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::ChannelMsg;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::ipc::Channel;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Emitter, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot, watch};

/// The phone's end of postmarketOS's USB network. Every phone is this address
/// on its own cable; the computer's end is given an address beside it.
const ADDRESS: Ipv4Addr = Ipv4Addr::new(172, 16, 42, 1);
const SSH_PORT: u16 = 22;

/// A test over Wi-Fi, with no cable: `OADX_PMOS_ADDRESS` names the phone on
/// this computer's network (`192.168.2.27`, or `redmi-note-7.local`) and is
/// used in place of the cable's address. The phone then counts as there for
/// as long as its SSH answers; everything else is as it is on the cable.
fn wifi_address() -> Option<&'static str> {
    static WIFI: OnceLock<Option<String>> = OnceLock::new();
    WIFI.get_or_init(|| {
        let a = std::env::var("OADX_PMOS_ADDRESS").ok().map(|a| a.trim().to_string()).filter(|a| !a.is_empty());
        if let Some(a) = &a {
            log::info!("pmos: looking for the phone at {a} on Wi-Fi (OADX_PMOS_ADDRESS), not on the USB network");
        }
        a
    })
    .as_deref()
}

/// A test of a desktop other than the phone's own session (a nested KWin on
/// the phone, say): `OADX_PMOS_ENV` names a shell file on the phone that the
/// helper's commands source first, setting its Wayland socket, session bus
/// and folders. What it returns goes in front of each command run there.
fn test_env() -> &'static str {
    static ENV: OnceLock<String> = OnceLock::new();
    ENV.get_or_init(|| {
        let file = std::env::var("OADX_PMOS_ENV").ok().map(|f| f.trim().to_string()).filter(|f| !f.is_empty());
        match file {
            Some(f) if !f.contains('\'') => {
                log::info!("pmos: the helper's commands source {f} on the phone first (OADX_PMOS_ENV)");
                format!(". '{f}'; ")
            }
            _ => String::new(),
        }
    })
}

/// Which network the phone is looked for on, for the log.
fn network() -> &'static str {
    if wifi_address().is_some() {
        "Wi-Fi"
    } else {
        "USB network"
    }
}

/// Where the phone's SSH is: on the cable, or at the Wi-Fi address a test gave.
async fn ssh_address() -> Result<SocketAddr, String> {
    let Some(host) = wifi_address() else { return Ok(SocketAddr::from((ADDRESS, SSH_PORT))) };
    let found: Vec<SocketAddr> = tokio::net::lookup_host((host, SSH_PORT))
        .await
        .map_err(|e| format!("The phone's address {host} didn't resolve ({e})."))?
        .collect();
    found
        .iter()
        .find(|a| a.is_ipv4())
        .or(found.first())
        .copied()
        .ok_or_else(|| format!("The phone's address {host} didn't resolve."))
}

/// The helper, for aarch64 postmarketOS (openandroiddex-pmos/build.sh).
static HELPER: &[u8] = include_bytes!("../../../openandroiddex-pmos/prebuilt/openandroiddex-pmos-aarch64");
/// Where it lives on the phone. Under the user's home: nothing here needs root.
const HELPER_PATH: &str = "$HOME/.local/lib/openandroiddex/openandroiddex-pmos";
/// Older KWin releases give a program virtual outputs and fake input only if
/// its desktop file asks for them by the program's own path; KWin 6.8 gave
/// them to the helper without one. Installed either way.
const DESKTOP_FILE: &str = "$HOME/.local/share/applications/com.ccrstech.openandroiddex.pmos.desktop";

/// The most pixels the phone's screen gets on its long side. Its software
/// encoder takes ~22 ms for a 1080p frame on a Snapdragon 660; past this the
/// screen is made smaller and the window here shows it larger, its layout
/// kept (the density goes down with it), as DeX does past its own cap.
const CAP: u32 = 1920;

/// The settings folder's files: who has been signed in to, and this computer's key.
const PHONES_FILE: &str = "pmos-phones.json";
const KEY_FILE: &str = "pmos-key";

/// To the main window: the session's state, as the Desktop tab shows it.
const EVENT: &str = "pmos:session";

// ── phones signed in to ──────────────────────────────────────────────────

/// A phone this computer has signed in to.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Paired {
    id: String,
    /// The SHA-256 fingerprint of its SSH host key: which phone it is.
    host_key: String,
    user: String,
    /// Its own name for itself: the device tree's model, else its hostname.
    name: String,
    #[serde(default)]
    os: String,
}

fn config_file(app: &AppHandle, name: &str) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join(name))
}

fn paired_all(app: &AppHandle) -> Vec<Paired> {
    config_file(app, PHONES_FILE)
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn paired_save(app: &AppHandle, all: &[Paired]) -> Result<(), String> {
    let path = config_file(app, PHONES_FILE).ok_or("there is no settings folder to remember the phone in")?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let body = serde_json::to_vec_pretty(all).map_err(|e| e.to_string())?;
    std::fs::write(&path, body).map_err(|e| e.to_string())
}

/// A short id for a host key, for the window's label and the page's hash.
fn id_for(host_key: &str) -> String {
    Sha256::digest(host_key.as_bytes())[..6].iter().map(|b| format!("{b:02x}")).collect()
}

fn label_for(id: &str) -> String {
    format!("pmos-{id}")
}

/// This computer's SSH key for its phones, made the first time and kept as
/// an OpenSSH key file, which `ssh -i` takes too.
fn client_seed(app: &AppHandle) -> Result<[u8; 32], String> {
    let path = config_file(app, KEY_FILE).ok_or("there is no settings folder to keep a key in")?;
    if let Ok(text) = std::fs::read_to_string(&path) {
        if let Some(seed) = dexremote::sshkey::parse_private_pem(&text) {
            // ssh -i refuses a key others can read; one written before this
            // was made owner-only is made so now.
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                if std::fs::metadata(&path).is_ok_and(|m| m.permissions().mode() & 0o077 != 0) {
                    let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
                }
            }
            return Ok(seed);
        }
    }
    let mut seed = [0u8; 32];
    if !crate::wireless::fill_random(&mut seed) {
        return Err("the system's random number generator is unavailable".into());
    }
    let public = Ed25519Keypair::from_seed(&seed).public.0;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let mut file = std::fs::OpenOptions::new();
    file.write(true).create(true).truncate(true);
    // Owner-only, as ssh insists for a key. Windows' OpenSSH checks the ACL
    // instead, which a file in the user's profile inherits in a form it takes.
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut file, 0o600);
    std::io::Write::write_all(
        &mut file.open(&path).map_err(|e| format!("could not keep this computer's key: {e}"))?,
        dexremote::sshkey::private_pem(&seed, &public, &key_comment()).as_bytes(),
    )
    .map_err(|e| format!("could not keep this computer's key: {e}"))?;
    Ok(seed)
}

/// What the key is called in the phone's authorized_keys: whose it is. The
/// computer's own name (macOS and Linux export no HOSTNAME to an app).
fn key_comment() -> String {
    let host = std::env::var("COMPUTERNAME").unwrap_or_else(|_| crate::send::computer_name());
    let host: String = host
        .chars()
        .map(|c| if c.is_whitespace() { '-' } else { c })
        .filter(|c| c.is_ascii_alphanumeric() || "-_.".contains(*c))
        .take(40)
        .collect();
    if host.is_empty() {
        "open-android-dex".into()
    } else {
        format!("open-android-dex@{host}")
    }
}

// ── SSH ──────────────────────────────────────────────────────────────────

/// The phone's host key: seen, and checked against the one pairing pinned.
struct Pin {
    want: Option<String>,
    seen: Arc<Mutex<Option<String>>>,
}

impl client::Handler for Pin {
    type Error = russh::Error;

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        let PublicKeyOrCertificate::PublicKey { key, .. } = key else {
            return Ok(false);
        };
        let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
        *self.seen.lock().unwrap() = Some(fingerprint.clone());
        Ok(self.want.as_ref().map_or(true, |w| *w == fingerprint))
    }
}

/// SSH to the phone, its host key `want` if pairing pinned one. Answers the
/// key it showed. Keepalives every two seconds: a cable pulled is noticed in
/// about six, which is what ends the session here.
async fn connect(want: Option<&str>) -> Result<(Handle<Pin>, String), String> {
    let seen = Arc::new(Mutex::new(None));
    let config = Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(2)),
        keepalive_max: 3,
        nodelay: true,
        ..Default::default()
    });
    let address = ssh_address().await?;
    let tcp = tokio::time::timeout(Duration::from_secs(3), TcpStream::connect(address))
        .await
        .map_err(|_| {
            if wifi_address().is_some() { "The phone didn't answer on Wi-Fi." } else { "The phone didn't answer on its USB network." }
                .to_string()
        })?
        .map_err(|e| format!("The phone didn't let this computer in over SSH ({e}). Is SSH turned on there?"))?;
    let _ = tcp.set_nodelay(true);
    let handler = Pin { want: want.map(str::to_string), seen: seen.clone() };
    let ssh = tokio::time::timeout(Duration::from_secs(10), client::connect_stream(config, tcp, handler))
        .await
        .map_err(|_| "The phone's SSH didn't answer.".to_string())?
        .map_err(|e| match e {
            russh::Error::UnknownKey => "This isn't the phone this computer signed in to: its SSH key changed. \
                                         Sign in to it again."
                .to_string(),
            e => format!("SSH to the phone failed: {e}"),
        })?;
    let key = seen.lock().unwrap().clone().unwrap_or_default();
    Ok((ssh, key))
}

async fn login(ssh: &mut Handle<Pin>, user: &str, seed: &[u8; 32]) -> Result<(), String> {
    let key = PrivateKey::from(Ed25519Keypair::from_seed(seed));
    let answer = ssh
        .authenticate_publickey(user, PrivateKeyWithHashAlg::new(Arc::new(key), None))
        .await
        .map_err(|e| format!("SSH to the phone failed: {e}"))?;
    if answer.success() {
        Ok(())
    } else {
        Err("The phone didn't accept this computer's key any more. Sign in to it again.".into())
    }
}

async fn hang_up(ssh: &Handle<Pin>) {
    let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "").await;
}

struct Ran {
    code: Option<u32>,
    out: String,
    err: String,
}

/// One command on the phone, `input` on its stdin, to its end.
async fn run(ssh: &Handle<Pin>, command: &str, input: Option<&[u8]>, limit: Duration) -> Result<Ran, String> {
    let asked = async {
        let mut ch = ssh.channel_open_session().await.map_err(|e| e.to_string())?;
        ch.exec(true, command).await.map_err(|e| e.to_string())?;
        if let Some(bytes) = input {
            ch.data(bytes).await.map_err(|e| e.to_string())?;
        }
        ch.eof().await.map_err(|e| e.to_string())?;
        let (mut out, mut err, mut code) = (Vec::new(), Vec::new(), None);
        while let Some(msg) = ch.wait().await {
            match msg {
                ChannelMsg::Data { data } => out.extend_from_slice(&data),
                ChannelMsg::ExtendedData { data, .. } => err.extend_from_slice(&data),
                ChannelMsg::ExitStatus { exit_status } => code = Some(exit_status),
                _ => {}
            }
        }
        Ok::<_, String>(Ran {
            code,
            out: String::from_utf8_lossy(&out).into_owned(),
            err: String::from_utf8_lossy(&err).into_owned(),
        })
    };
    tokio::time::timeout(limit, asked)
        .await
        .map_err(|_| "The phone took too long to answer.".to_string())?
}

// ── which phone is on the cable ──────────────────────────────────────────

/// Whether this computer has an address on postmarketOS's USB network: the
/// route it would send to the phone from. A UDP socket's connect sends
/// nothing, so this costs the phone nothing, and its sshd logs nothing.
fn link_up() -> bool {
    // On Wi-Fi there is always a route: whether its SSH answers says the rest.
    if wifi_address().is_some() {
        return true;
    }
    let Ok(socket) = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)) else { return false };
    if socket.connect((ADDRESS, 9)).is_err() {
        return false;
    }
    matches!(socket.local_addr(), Ok(SocketAddr::V4(a)) if a.ip().octets()[..3] == ADDRESS.octets()[..3] && *a.ip() != ADDRESS)
}

/// What the cable's phone showed last: its host key, or that it has no SSH.
struct Seen {
    host_key: Option<String>,
    at: Instant,
}

fn seen() -> &'static Mutex<Option<Seen>> {
    static SEEN: OnceLock<Mutex<Option<Seen>>> = OnceLock::new();
    SEEN.get_or_init(Default::default)
}

/// A postmarketOS phone on the cable, as the Desktop tab shows it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PmosPhone {
    /// Empty while its SSH is not answering.
    id: String,
    /// SSH answers on it.
    ssh: bool,
    /// This computer has signed in to it before.
    paired: bool,
    name: Option<String>,
    user: Option<String>,
    os: Option<String>,
}

/// Polled by the Desktop tab. The phone's SSH is asked for its host key once
/// per plug-in; after that this is a routing-table lookup.
#[tauri::command]
pub async fn pmos_status(app: AppHandle) -> Option<PmosPhone> {
    // A test run that names its phones (OADX_ONLY_PHONES) leaves a
    // postmarketOS phone alone unless `pmos` is one of them.
    if !crate::adb::pmos_allowed() {
        return None;
    }
    if !link_up() {
        *seen().lock().unwrap() = None;
        return None;
    }
    // One handshake at a time: polls that meet here take its answer.
    static ASKING: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
    let _asking = ASKING.get_or_init(Default::default).lock().await;
    let cached = seen().lock().unwrap().as_ref().map(|s| (s.host_key.clone(), s.at));
    let host_key = match cached {
        Some((Some(key), _)) => Some(key),
        // No SSH a moment ago: it may still be starting. Asked again every few seconds.
        Some((None, at)) if at.elapsed() < Duration::from_secs(4) => None,
        _ => {
            let key = match connect(None).await {
                Ok((ssh, key)) => {
                    hang_up(&ssh).await;
                    Some(key)
                }
                Err(e) => {
                    log::info!("pmos: a phone is on the {}, and its SSH: {e}", network());
                    None
                }
            };
            if key.is_some() && cached.is_none() {
                log::info!("pmos: a phone is on the {}", network());
            }
            *seen().lock().unwrap() = Some(Seen { host_key: key.clone(), at: Instant::now() });
            key
        }
    };
    let Some(host_key) = host_key else {
        return Some(PmosPhone { id: String::new(), ssh: false, paired: false, name: None, user: None, os: None });
    };
    let paired = paired_all(&app).into_iter().find(|p| p.host_key == host_key);
    Some(PmosPhone {
        id: id_for(&host_key),
        ssh: true,
        paired: paired.is_some(),
        name: paired.as_ref().map(|p| p.name.clone()),
        user: paired.as_ref().map(|p| p.user.clone()),
        os: paired.map(|p| p.os),
    })
}

// ── signing in ───────────────────────────────────────────────────────────

/// What the phone is: its processor, its name for itself, its system.
const ABOUT: &str = "printf 'arch=%s\\n' \"$(uname -m)\"; \
                     printf 'model=%s\\n' \"$(tr -d '\\000' < /proc/device-tree/model 2>/dev/null)\"; \
                     printf 'host=%s\\n' \"$(cat /etc/hostname 2>/dev/null)\"; \
                     printf 'os=%s\\n' \"$(. /etc/os-release 2>/dev/null; echo \"$PRETTY_NAME\")\"";

fn field<'a>(text: &'a str, key: &str) -> &'a str {
    text.lines()
        .find_map(|l| l.strip_prefix(key).and_then(|r| r.strip_prefix('=')))
        .map(str::trim)
        .unwrap_or("")
}

/// Sign in to the phone on the cable with its own username and password, once:
/// this computer's key goes into its authorized_keys, and is tried before
/// anything is remembered. The password is used for this and kept nowhere.
#[tauri::command]
pub async fn pmos_pair(app: AppHandle, user: String, password: String) -> Result<PmosPhone, String> {
    let user = user.trim().to_string();
    if user.is_empty() || password.is_empty() {
        return Err("Enter the username and the password or PIN you use on the phone.".into());
    }
    let (mut ssh, host_key) = connect(None).await?;
    let answer = ssh
        .authenticate_password(user.as_str(), password)
        .await
        .map_err(|e| format!("SSH to the phone failed: {e}"))?;
    if !answer.success() {
        hang_up(&ssh).await;
        return Err("That username and password or PIN didn't work on the phone.".into());
    }
    let about = run(&ssh, ABOUT, None, Duration::from_secs(10)).await?.out;
    let arch = field(&about, "arch");
    if arch != "aarch64" {
        hang_up(&ssh).await;
        return Err(format!(
            "This phone's processor ({}) isn't supported yet: Open Android DeX works with 64-bit ARM phones.",
            if arch.is_empty() { "unknown" } else { arch }
        ));
    }
    let model = field(&about, "model");
    let name = if model.is_empty() { field(&about, "host").to_string() } else { model.to_string() };
    let name = if name.is_empty() { "postmarketOS phone".to_string() } else { name };
    let os = field(&about, "os").to_string();

    let seed = client_seed(&app)?;
    let public = Ed25519Keypair::from_seed(&seed).public.0;
    let line = dexremote::sshkey::public_line(&public, &key_comment());
    let add = format!(
        "umask 077; mkdir -p \"$HOME/.ssh\" && touch \"$HOME/.ssh/authorized_keys\" && \
         {{ grep -qxF '{line}' \"$HOME/.ssh/authorized_keys\" || printf '%s\\n' '{line}' >> \"$HOME/.ssh/authorized_keys\"; }}"
    );
    let added = run(&ssh, &add, None, Duration::from_secs(10)).await?;
    hang_up(&ssh).await;
    if added.code != Some(0) {
        return Err(format!("Couldn't leave this computer's key on the phone: {}", added.err.trim()));
    }
    // The key, tried: an sshd that takes no keys is better found out now than
    // at the next plug-in, with no password to fall back on.
    let (mut again, _) = connect(Some(&host_key)).await?;
    let tried = login(&mut again, &user, &seed).await;
    hang_up(&again).await;
    tried.map_err(|_| "The phone took the password but not this computer's key (does its SSH allow keys?).".to_string())?;

    let id = id_for(&host_key);
    let mut all = paired_all(&app);
    all.retain(|p| p.host_key != host_key);
    all.push(Paired { id: id.clone(), host_key, user: user.clone(), name: name.clone(), os: os.clone() });
    paired_save(&app, &all)?;
    log::info!("pmos: signed in to {name} ({os}) as {user}");
    Ok(PmosPhone { id, ssh: true, paired: true, name: Some(name), user: Some(user), os: Some(os) })
}

/// Forget a phone on this computer. Its authorized_keys keeps the key until
/// someone takes it out there; without the key file here it lets no one in.
#[tauri::command]
pub fn pmos_forget(app: AppHandle, id: String) -> Result<(), String> {
    let mut all = paired_all(&app);
    all.retain(|p| p.id != id);
    paired_save(&app, &all)
}

// ── the session ──────────────────────────────────────────────────────────

/// One message for the window's socket: its opcode, text 1 or binary 2.
type Out = (u8, Vec<u8>);

struct Session {
    id: String,
    name: String,
    label: String,
    port: u16,
    token: String,
    /// The phone's SSH: the helper's channel, and a channel for each drop's files beside it.
    ssh: Arc<Handle<Pin>>,
    /// What the phone's desktop lets the helper do, as it said when the screen was up:
    /// the clipboard (Wayland's data-control), and dropping files on the app under them.
    clipboard: bool,
    carry: bool,
    /// Framed messages for the helper's stdin.
    to_helper: mpsc::UnboundedSender<Vec<u8>>,
    /// The window's socket while one is open, by its number: a new one takes over.
    window: Mutex<Option<(u64, mpsc::UnboundedSender<Out>)>>,
    /// Set once, when the session ends: why, in the Desktop tab's words; empty when asked to.
    end: watch::Sender<Option<String>>,
    /// The helper's output has ended: it has exited, or the link has gone.
    gone: watch::Sender<bool>,
    /// The copy of a drop under way, to stop.
    copy: Mutex<Option<CancellationToken>>,
    /// Waiting for the helper to say whether the app under a drop took it.
    dropped: Mutex<Option<oneshot::Sender<serde_json::Value>>>,
    /// One of the phone's apps in a window of its own (`serve --app`); None, the phone's desktop.
    app: Option<AppOf>,
    /// The helper ended it as it should end ("stopped": an app closed on the phone), not for a failure.
    stopped: AtomicBool,
    /// For what the helper says of an app's window: its title.
    handle: AppHandle,
}

/// What an app's window is (the Apps page's "Open").
#[derive(Clone, Debug)]
struct AppOf {
    /// Its desktop file, without .desktop: what the helper starts.
    id: String,
    /// Its name, which the window is called until the app's own window says.
    name: String,
    /// Which of the screens kept for apps it has: OADX-App-<slot> on the phone, pmos-app-<slot> here.
    slot: u8,
}

impl Session {
    /// Which window this is, for the log: the desktop's, or an app's.
    fn window_name(&self) -> String {
        match &self.app {
            Some(a) => format!("{}'s window", a.name),
            None => "the desktop's window".into(),
        }
    }

    fn finish(&self, why: &str) {
        self.end.send_if_modified(|v| {
            if v.is_none() {
                *v = Some(why.to_string());
                true
            } else {
                false
            }
        });
    }

    fn ended(&self) -> bool {
        self.end.borrow().is_some()
    }

    fn to_window(&self, kind: u8, payload: Vec<u8>) {
        if let Some((_, tx)) = self.window.lock().unwrap().as_ref() {
            let _ = tx.send((if kind == 0 { 1 } else { 2 }, payload));
        }
    }

    fn to_helper(&self, kind: u8, payload: &[u8]) {
        let _ = self.to_helper.send(frame(kind, payload));
    }
}

fn session() -> &'static Mutex<Option<Arc<Session>>> {
    static SESSION: OnceLock<Mutex<Option<Arc<Session>>>> = OnceLock::new();
    SESSION.get_or_init(Default::default)
}

fn current() -> Option<Arc<Session>> {
    session().lock().unwrap().clone().filter(|s| !s.ended())
}

/// The phone's apps in windows of their own, beside the desktop: one session each.
fn app_sessions() -> &'static Mutex<Vec<Arc<Session>>> {
    static APPS: OnceLock<Mutex<Vec<Arc<Session>>>> = OnceLock::new();
    APPS.get_or_init(Default::default)
}

/// The session a window here belongs to: the desktop's, or an app's.
fn by_label(label: &str) -> Option<Arc<Session>> {
    if let Some(s) = current().filter(|s| s.label == label) {
        return Some(s);
    }
    app_sessions().lock().unwrap().iter().find(|s| s.label == label && !s.ended()).cloned()
}

/// A session that has ended is no one's any more.
fn unregister(s: &Arc<Session>) {
    {
        let mut slot = session().lock().unwrap();
        if slot.as_ref().is_some_and(|c| Arc::ptr_eq(c, s)) {
            *slot = None;
        }
    }
    app_sessions().lock().unwrap().retain(|c| !Arc::ptr_eq(c, s));
}

/// A launch under way: one at a time, and the auto-open and a click do not both start one.
static LAUNCHING: AtomicBool = AtomicBool::new(false);

/// The Desktop tab's word for where the session is.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionEvent {
    id: String,
    /// launching, running, ended, failed
    state: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    step: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
    name: String,
}

fn last_event() -> &'static Mutex<Option<SessionEvent>> {
    static LAST: OnceLock<Mutex<Option<SessionEvent>>> = OnceLock::new();
    LAST.get_or_init(Default::default)
}

fn tell(app: &AppHandle, id: &str, name: &str, state: &'static str, step: Option<u8>, message: Option<String>) {
    let event = SessionEvent { id: id.to_string(), state, step, message, name: name.to_string() };
    *last_event().lock().unwrap() = Some(event.clone());
    let _ = app.emit(EVENT, event);
}

/// Where the session is now: for a Desktop tab that has just mounted.
#[tauri::command]
pub fn pmos_session() -> Option<SessionEvent> {
    last_event().lock().unwrap().clone()
}

fn frame(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + payload.len());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// The next whole frame in `buf`, taken out of it.
fn take_frame(buf: &mut Vec<u8>) -> Option<(u8, Vec<u8>)> {
    if buf.len() < 5 {
        return None;
    }
    let len = u32::from_be_bytes([buf[1], buf[2], buf[3], buf[4]]) as usize;
    if buf.len() < 5 + len {
        return None;
    }
    let kind = buf[0];
    let payload = buf[5..5 + len].to_vec();
    buf.drain(..5 + len);
    Some((kind, payload))
}

/// The screen to ask the phone for, and the window to show it in.
struct Fit {
    /// The screen's size in pixels: KWin makes its mode exactly this.
    width: u32,
    height: u32,
    /// The scale Plasma draws it at.
    scale: f64,
    /// The window's content, in logical units here.
    window: (f64, f64),
}

/// The desktop's screen: the window's content area on the screen this app is
/// on, in pixels, one for one as DeX's display is. Past CAP it is made
/// smaller and the window shows it larger, the way DeX's is. Plasma's scale
/// is this screen's, made smaller with it, but never below 1: a desktop drawn
/// smaller than its own pixels and then enlarged here blurs twice.
///
/// After that the screen follows the window (the helper's on_resize), so its
/// first size is the one the helper gives the window it opens in
/// (`screen_for`): a window that opens at the size it was made for then asks
/// the phone for no other.
fn fit(app: &AppHandle) -> Fit {
    let fallback = Fit { width: 1280, height: 720, scale: 1.0, window: (1280.0, 720.0) };
    let Some(screen) = crate::display::screen_for_desktop(app) else { return fallback };
    if crate::display::geometry(&screen, CAP).is_none() {
        return fallback;
    }
    let f = screen.frame;
    let cw = f64::from(screen.work_w.saturating_sub(f.left + f.right).max(1));
    let ch = f64::from(screen.work_h.saturating_sub(f.top + f.bottom).max(1));
    let k = (f64::from(CAP) / cw.max(ch)).min(1.0);
    let scale = (screen.scale * k).clamp(1.0, 4.0);
    let window = ((cw / screen.scale).round(), (ch / screen.scale).round());
    let (width, height) = screen_for(window, scale);
    Fit { width, height, scale, window }
}

/// The screen the helper makes for a window of `window` logical pixels
/// (main.c's on_resize, the same sums): the size as the window says it
/// (UsbDesktop's sizeFor: whole, within 640 × 400 and 3840 × 2400, even), at
/// `scale`, at most CAP on its long side; across in whole cells of eight, as
/// KWin's custom modes are, and down even.
fn screen_for(window: (f64, f64), scale: f64) -> (u32, u32) {
    let said = |v: f64, lo: f64, hi: f64| f64::from((v.round().clamp(lo, hi) as u32) & !1);
    let mut w = said(window.0, 640.0, 3840.0) * scale;
    let mut h = said(window.1, 400.0, 2400.0) * scale;
    let k = f64::from(CAP) / w.max(h);
    if k < 1.0 {
        w *= k;
        h *= k;
    }
    let cells = |v: f64, cell: f64, lo: u32| (((v / cell).round() * cell) as u32).max(lo);
    (cells(w, 8.0, 640), cells(h, 2.0, 400))
}

/// The helper on the phone, if it is not the one embedded here, and the
/// desktop file beside it.
async fn deploy(ssh: &Handle<Pin>) -> Result<(), String> {
    let want: String = Sha256::digest(HELPER).iter().map(|b| format!("{b:02x}")).collect();
    let have = run(ssh, &format!("sha256sum \"{HELPER_PATH}\" 2>/dev/null; echo \"home=$HOME\""), None, Duration::from_secs(10))
        .await?;
    let home = field(&have.out, "home").to_string();
    if !have.out.contains(&want) {
        log::info!("pmos: putting the helper on the phone ({} bytes)", HELPER.len());
        let put = format!(
            "set -e; d=\"$(dirname \"{HELPER_PATH}\")\"; mkdir -p \"$d\"; cat > \"$d/.openandroiddex-pmos.new\"; \
             chmod 755 \"$d/.openandroiddex-pmos.new\"; mv -f \"$d/.openandroiddex-pmos.new\" \"{HELPER_PATH}\""
        );
        let ran = run(ssh, &put, Some(HELPER), Duration::from_secs(60)).await?;
        if ran.code != Some(0) {
            return Err(format!("Couldn't put Open Android DeX's helper on the phone: {}", ran.err.trim()));
        }
    }
    if !home.is_empty() {
        let exec = HELPER_PATH.replace("$HOME", &home);
        let entry = format!(
            "[Desktop Entry]\nType=Application\nName=Open Android DeX\n\
             Comment=Shows this phone's desktop on a computer\nExec={exec}\nNoDisplay=true\n\
             X-KDE-Wayland-Interfaces=zkde_screencast_unstable_v1,org_kde_kwin_fake_input\n"
        );
        // Rewritten only when it changed, and then KDE's application cache
        // rebuilt, in the background and cut loose from this channel.
        let write = format!(
            "f=\"{DESKTOP_FILE}\"; mkdir -p \"$(dirname \"$f\")\"; cat > \"$f.new\" <<'EOF'\n{entry}EOF\n\
             if cmp -s \"$f.new\" \"$f\"; then rm -f \"$f.new\"; else mv -f \"$f.new\" \"$f\"; \
             command -v kbuildsycoca6 >/dev/null && (kbuildsycoca6 </dev/null >/dev/null 2>&1 &); fi; true"
        );
        if let Err(e) = run(ssh, &write, None, Duration::from_secs(10)).await {
            log::info!("pmos: the desktop file: {e}");
        }
    }
    Ok(())
}

/// Open the desktop of the phone on the cable: or bring its window to the
/// front, if it is open. Returns once the window is.
#[tauri::command]
pub async fn pmos_open(app: AppHandle, id: String) -> Result<(), String> {
    if let Some(s) = current() {
        if s.id == id {
            if let Some(w) = app.get_webview_window(&s.label) {
                let _ = w.unminimize();
                let _ = w.set_focus();
            }
            return Ok(());
        }
        return Err("Another postmarketOS phone's desktop is open. Close it first.".into());
    }
    let phone = paired_all(&app)
        .into_iter()
        .find(|p| p.id == id)
        .ok_or("This computer hasn't signed in to that phone. Plug it in and sign in again.")?;
    if LAUNCHING.swap(true, Ordering::SeqCst) {
        return Ok(());
    }
    tell(&app, &phone.id, &phone.name, "launching", Some(0), None);
    let result = launch(&app, &phone).await;
    LAUNCHING.store(false, Ordering::SeqCst);
    if let Err(e) = &result {
        log::warn!("pmos: the desktop didn't open: {e}");
        tell(&app, &phone.id, &phone.name, "failed", None, Some(e.clone()));
    }
    result
}

async fn launch(app: &AppHandle, phone: &Paired) -> Result<(), String> {
    log::info!("pmos: opening the desktop of {} ({})", phone.name, phone.os);
    let (mut ssh, _) = connect(Some(&phone.host_key)).await?;
    let seed = client_seed(app)?;
    login(&mut ssh, &phone.user, &seed).await?;
    tell(app, &phone.id, &phone.name, "launching", Some(1), None);
    deploy(&ssh).await?;

    let fit = fit(app);
    let command = format!(
        "{}exec \"{HELPER_PATH}\" serve --width {} --height {} --scale {:.4} --cap {CAP}",
        test_env(),
        fit.width,
        fit.height,
        fit.scale
    );
    log::info!("pmos: asking for a {}x{} screen at scale {:.3}", fit.width, fit.height, fit.scale);
    let (read, write, buf, ready) = start_helper(&ssh, command, "the desktop").await?;
    log::info!("pmos: the phone's screen is up: {ready}");
    tell(app, &phone.id, &phone.name, "launching", Some(2), None);

    let s = begin(app, phone, ssh, read, write, buf, &ready, None).await?;
    if let Err(e) = open_window(app, &s, &fit) {
        // Undone here rather than by wind_down, which would call it closed
        // over the failure the Desktop tab is about to be told.
        s.finish("");
        unregister(&s);
        tokio::time::sleep(Duration::from_millis(300)).await;
        hang_up(&s.ssh).await;
        return Err(format!("Couldn't open the desktop's window: {e}"));
    }
    tauri::async_runtime::spawn(wind_down(app.clone(), s.clone()));
    tell(app, &s.id, &s.name, "running", Some(3), None);
    Ok(())
}

type HelperRead = russh::ChannelReadHalf;
type HelperWrite = russh::ChannelWriteHalf<client::Msg>;

/// The helper started with `command`, up to its first word: ready, with what
/// it says of the screen (and what came after it, still to read), or why not.
/// `what` is what it was starting, for those words: "the desktop", an app's name.
async fn start_helper(
    ssh: &Handle<Pin>,
    command: String,
    what: &str,
) -> Result<(HelperRead, HelperWrite, Vec<u8>, serde_json::Value), String> {
    let channel = ssh.channel_open_session().await.map_err(|e| e.to_string())?;
    channel.exec(true, command).await.map_err(|e| e.to_string())?;
    let (mut read, write) = channel.split();
    let mut buf = Vec::new();
    let ready = tokio::time::timeout(Duration::from_secs(30), async {
        let mut why = String::new();
        loop {
            match read.wait().await {
                Some(ChannelMsg::Data { data }) => {
                    buf.extend_from_slice(&data);
                    while let Some((kind, payload)) = take_frame(&mut buf) {
                        if kind != 0 {
                            continue;
                        }
                        let msg: serde_json::Value = serde_json::from_slice(&payload).unwrap_or_default();
                        match msg["t"].as_str() {
                            Some("ready") => return Ok(msg),
                            Some("state") if msg["state"] != "running" && msg["state"] != "starting" => {
                                return Err(msg["message"]
                                    .as_str()
                                    .map(str::to_string)
                                    .unwrap_or_else(|| format!("The phone couldn't start {what}.")))
                            }
                            _ => {}
                        }
                    }
                }
                Some(ChannelMsg::ExtendedData { data, .. }) => {
                    let text = String::from_utf8_lossy(&data);
                    for line in phone_lines(&text) {
                        log::info!("pmos: phone: {line}");
                        why = line.to_string();
                    }
                }
                Some(_) => {}
                None => {
                    return Err(if why.is_empty() {
                        format!("The phone's helper stopped before {what} was up.")
                    } else {
                        format!("The phone couldn't start {what}: {why}")
                    })
                }
            }
        }
    })
    .await
    .map_err(|_| format!("The phone took too long to make {what}'s screen."))??;
    Ok((read, write, buf, ready))
}

/// A session, its helper up: the window's port and its token, the helper's
/// two directions, the heartbeat. The desktop's, or an app's (`app`).
#[allow(clippy::too_many_arguments)]
async fn begin(
    app: &AppHandle,
    phone: &Paired,
    ssh: Handle<Pin>,
    read: HelperRead,
    write: HelperWrite,
    buf: Vec<u8>,
    ready: &serde_json::Value,
    app_of: Option<AppOf>,
) -> Result<Arc<Session>, String> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .map_err(|e| format!("cannot open a local port for the window: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let mut token = [0u8; 16];
    if !crate::wireless::fill_random(&mut token) {
        return Err("the system's random number generator is unavailable".into());
    }
    let (to_helper, helper_rx) = mpsc::unbounded_channel();
    let (end, _) = watch::channel(None);
    let (gone, _) = watch::channel(false);
    let label = match &app_of {
        Some(a) => app_label_for(a.slot),
        None => label_for(&phone.id),
    };
    let s = Arc::new(Session {
        id: phone.id.clone(),
        name: phone.name.clone(),
        label,
        port,
        token: token.iter().map(|b| format!("{b:02x}")).collect(),
        ssh: Arc::new(ssh),
        clipboard: ready["clipboard"].as_bool().unwrap_or(false),
        carry: ready["carry"].as_bool().unwrap_or(false),
        to_helper,
        window: Mutex::new(None),
        end,
        gone,
        copy: Mutex::new(None),
        dropped: Mutex::new(None),
        app: app_of,
        stopped: AtomicBool::new(false),
        handle: app.clone(),
    });
    if s.app.is_some() {
        app_sessions().lock().unwrap().push(s.clone());
    } else {
        *session().lock().unwrap() = Some(s.clone());
    }

    tauri::async_runtime::spawn(write_helper(write, helper_rx, s.clone()));
    tauri::async_runtime::spawn(read_helper(read, buf, s.clone()));
    tauri::async_runtime::spawn(heartbeat(s.clone()));
    let dev = if tauri::is_dev() { app.config().build.dev_url.clone().map(|u| u.origin().ascii_serialization()) } else { None };
    tauri::async_runtime::spawn(serve(listener, s.clone(), dev));
    Ok(s)
}

/// The helper's stderr, line by line, without GStreamer's noise about a
/// hardware encoder it could not open.
fn phone_lines(text: &str) -> impl Iterator<Item = &str> {
    text.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.contains("GStreamer-CRITICAL") && !l.starts_with('('))
        .map(|l| l.strip_prefix("openandroiddex-pmos: ").unwrap_or(l))
}

async fn write_helper(
    write: russh::ChannelWriteHalf<client::Msg>,
    mut rx: mpsc::UnboundedReceiver<Vec<u8>>,
    s: Arc<Session>,
) {
    let mut end = s.end.subscribe();
    loop {
        tokio::select! {
            msg = rx.recv() => {
                let Some(bytes) = msg else { break };
                if write.data(&bytes[..]).await.is_err() {
                    s.finish("The phone stopped answering.");
                    break;
                }
            }
            _ = end.changed() => break,
        }
    }
    // Asked to end: the helper takes its screen down. The channel stays open
    // for its last words (wind_down hangs up once they are said).
    let _ = write.data(&frame(0, br#"{"t":"stop"}"#)[..]).await;
    let _ = write.eof().await;
}

async fn read_helper(mut read: russh::ChannelReadHalf, mut buf: Vec<u8>, s: Arc<Session>) {
    let mut why = String::new();
    let flush = |buf: &mut Vec<u8>, why: &mut String| {
        while let Some((kind, payload)) = take_frame(buf) {
            if kind == 0 && payload.starts_with(br#"{"t":"state""#) {
                let msg: serde_json::Value = serde_json::from_slice(&payload).unwrap_or_default();
                if msg["state"] == "stopped" || msg["state"] == "failed" {
                    *why = msg["message"].as_str().unwrap_or_default().to_string();
                }
                if msg["state"] == "stopped" {
                    s.stopped.store(true, Ordering::SeqCst);
                }
            }
            // The answer to a drop is pmos_put's, not the window's.
            if kind == 0 && payload.starts_with(br#"{"t":"dropped""#) {
                if let Some(waiting) = s.dropped.lock().unwrap().take() {
                    let _ = waiting.send(serde_json::from_slice(&payload).unwrap_or_default());
                }
                continue;
            }
            // An app's window is called what the app's own window in front is.
            if kind == 0 && payload.starts_with(br#"{"t":"title""#) {
                let msg: serde_json::Value = serde_json::from_slice(&payload).unwrap_or_default();
                retitle(&s, msg["title"].as_str().unwrap_or_default());
                continue;
            }
            s.to_window(kind, payload);
        }
    };
    flush(&mut buf, &mut why);
    loop {
        match read.wait().await {
            Some(ChannelMsg::Data { data }) => {
                buf.extend_from_slice(&data);
                flush(&mut buf, &mut why);
            }
            Some(ChannelMsg::ExtendedData { data, .. }) => {
                for line in phone_lines(&String::from_utf8_lossy(&data)) {
                    log::info!("pmos: phone: {line}");
                }
            }
            Some(ChannelMsg::ExitStatus { exit_status }) => log::info!("pmos: the phone's helper ended ({exit_status})"),
            Some(_) => {}
            None => break,
        }
    }
    s.gone.send_replace(true);
    s.finish(if why.is_empty() { "The phone was unplugged, or stopped answering." } else { &why });
}

/// The helper closes the screen after fifteen seconds without a word from here.
async fn heartbeat(s: Arc<Session>) {
    let mut end = s.end.subscribe();
    loop {
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(2)) => s.to_helper(0, br#"{"t":"hb"}"#),
            _ = end.changed() => return,
        }
    }
}

/// Once the session ends, however it does: the phone let go of, the window
/// closed, and the Desktop tab told why.
async fn wind_down(app: AppHandle, s: Arc<Session>) {
    let mut end = s.end.subscribe();
    while end.borrow().is_none() {
        if end.changed().await.is_err() {
            break;
        }
    }
    let why = end.borrow().clone().unwrap_or_default();
    // A copy under way stops: what it was in the middle of is taken back on the phone.
    if let Some(copy) = s.copy.lock().unwrap().as_ref() {
        copy.cancel();
    }
    // The helper's stop, written by write_helper, and then its end: before it
    // closes the screen it puts the screen's first mode back and takes its
    // custom modes away (kscreen-doctor, a second or so), and says so. A
    // hang-up before that loses its word; with the link gone it is at once.
    let mut gone = s.gone.subscribe();
    let _ = tokio::time::timeout(Duration::from_secs(4), gone.wait_for(|g| *g)).await;
    hang_up(&s.ssh).await;
    unregister(&s);
    s.window.lock().unwrap().take();
    if let Some(w) = app.get_webview_window(&s.label) {
        let _ = w.close();
    }
    if let Some(a) = &s.app {
        // An app's window: the Apps page hears it went, and why when it wasn't asked to.
        log::info!("pmos: {} on {} ended{}", a.name, s.name, if why.is_empty() { String::new() } else { format!(": {why}") });
        // Closed on the phone, or here: nothing to say. Anything else is why it went.
        let message = if s.stopped.load(Ordering::SeqCst) { String::new() } else { why };
        apps_changed(&app, Some(AppEnded { phone: s.id.clone(), app: a.id.clone(), name: a.name.clone(), message }));
        return;
    }
    log::info!("pmos: the desktop of {} ended{}", s.name, if why.is_empty() { String::new() } else { format!(": {why}") });
    tell(&app, &s.id, &s.name, "ended", None, (!why.is_empty()).then_some(why));
}

/// Close the desktop: the Desktop tab's Disconnect.
#[tauri::command]
pub fn pmos_close() {
    if let Some(s) = current() {
        s.finish("");
    }
}

/// A window of a phone's (its desktop's, one of its apps') has gone: so has its session.
pub fn window_closed(label: &str) {
    if let Some(s) = by_label(label) {
        s.finish("");
    }
}

/// The app is quitting.
pub fn close_all() {
    if let Some(s) = current() {
        s.finish("");
    }
    for s in app_sessions().lock().unwrap().iter() {
        s.finish("");
    }
}

// ── the window ───────────────────────────────────────────────────────────

fn open_window(app: &AppHandle, s: &Session, fit: &Fit) -> Result<(), String> {
    if let Some(w) = app.get_webview_window(&s.label) {
        let _ = w.close();
    }
    let dev = if tauri::is_dev() { app.config().build.dev_url.clone() } else { None };
    WebviewWindowBuilder::new(app, &s.label, WebviewUrl::App(format!("index.html#pmos={}", s.id).into()))
        .title(format!("{} — postmarketOS · {}", s.name, if wifi_address().is_some() { "Wi-Fi" } else { "USB" }))
        .inner_size(fit.window.0.max(640.0), fit.window.1.max(400.0))
        .min_inner_size(640.0, 400.0)
        .center()
        .background_color(tauri::window::Color(9, 11, 16, 255))
        .incognito(true)
        .on_navigation(move |url| may_navigate(dev.as_ref(), url))
        .on_new_window(|_, _| NewWindowResponse::Deny)
        .build()
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// The app's own page, and nothing else.
fn may_navigate(dev: Option<&Url>, url: &Url) -> bool {
    match url.scheme() {
        "tauri" => url.host_str() == Some("localhost"),
        "http" | "https" if url.host_str() == Some("tauri.localhost") => true,
        _ => dev.is_some_and(|dev| dev.origin() == url.origin()),
    }
}

/// What the window opens.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PmosLink {
    url: String,
    name: String,
    /// The phone can share its clipboard: the window shows the switch for it.
    clipboard: bool,
    /// Files dropped on the window can be dropped on the app under them, not only saved in Downloads.
    carry: bool,
    /// The app's name, for an app's window; none for the desktop's.
    app: Option<String>,
}

/// From the phone's desktop window: the WebSocket to open. A window gets its
/// own phone's and no other.
#[tauri::command]
pub fn pmos_window_link(window: WebviewWindow, id: String) -> Result<PmosLink, String> {
    let s = by_label(window.label()).ok_or("This window's connection to the phone has closed.")?;
    if s.id != id {
        return Err("this window belongs to another phone".into());
    }
    Ok(PmosLink {
        url: format!("ws://127.0.0.1:{}/stream/{}", s.port, s.token),
        name: s.name.clone(),
        clipboard: s.clipboard,
        carry: s.carry,
        app: s.app.as_ref().map(|a| a.name.clone()),
    })
}

// ── the phone's apps, each in a window of its own ───────────────────────

// The Apps page lists a postmarketOS phone's apps beside an Android phone's,
// and opens each in a window of its own, as it does an Android app or a Linux
// app of an Android phone's (apps-on-pc.md). On the phone that is the
// helper's `serve --app` (main.c, "One app's window"): a screen of its own,
// made and streamed as the desktop's is, the size of the window here, with the
// app's windows on it full screen; closing the window here puts the app back
// on the phone's own screen. The window is the desktop's (PmosDesktop.tsx,
// UsbDesktop.tsx), and so are its clipboard and its drops; it is called what
// the app's window in front is called.

/// The apps' screens: OADX-App-<n> on the phone. Not "OpenAndroidDeX…": the
/// desktop's helper finds its own screen by that name.
const APP_SLOTS: u8 = 8;

fn app_label_for(slot: u8) -> String {
    format!("pmos-app-{slot}")
}

/// One login at a time for the Apps page, listing or opening: each costs the
/// phone's sshd a PAM run, and a pile of them once soft-locked a phone (see
/// pmos-desktop.md).
fn apps_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(Default::default)
}

fn paired(app: &AppHandle, id: &str) -> Result<Paired, String> {
    paired_all(app)
        .into_iter()
        .find(|p| p.id == id)
        .ok_or_else(|| "This computer hasn't signed in to that phone. Plug it in and sign in again.".to_string())
}

/// Signed in to the phone with this computer's key, and the helper there.
async fn reach(app: &AppHandle, phone: &Paired) -> Result<Handle<Pin>, String> {
    let (mut ssh, _) = connect(Some(&phone.host_key)).await?;
    let seed = client_seed(app)?;
    login(&mut ssh, &phone.user, &seed).await?;
    deploy(&ssh).await?;
    Ok(ssh)
}

/// One of the phone's apps, as its launcher lists it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PmosApp {
    /// Its desktop file, without .desktop.
    id: String,
    name: String,
    /// Its icon as a data URL, SVG or PNG; none when the phone has none for it.
    icon: Option<String>,
}

/// The apps of a phone this computer has signed in to, for the Apps page: what
/// its own launcher lists, with their icons (the helper's `apps`).
#[tauri::command]
pub async fn pmos_apps(app: AppHandle, id: String) -> Result<Vec<PmosApp>, String> {
    let phone = paired(&app, &id)?;
    let _one = apps_lock().lock().await;
    let ssh = reach(&app, &phone).await?;
    let ran = run(&ssh, &format!("{}exec \"{HELPER_PATH}\" apps", test_env()), None, Duration::from_secs(30)).await;
    hang_up(&ssh).await;
    let ran = ran?;
    for line in phone_lines(&ran.err) {
        log::info!("pmos: phone: {line}");
    }
    let v: serde_json::Value =
        serde_json::from_str(&ran.out).map_err(|e| format!("The phone's list of apps didn't come through: {e}"))?;
    let mut apps: Vec<PmosApp> = v["apps"]
        .as_array()
        .map(|all| {
            all.iter()
                .filter_map(|a| {
                    let id = a["id"].as_str()?.to_string();
                    let kind = if a["type"] == "svg" { "svg+xml" } else { "png" };
                    Some(PmosApp {
                        name: a["name"].as_str().unwrap_or(&id).to_string(),
                        icon: a["icon"].as_str().map(|b64| format!("data:image/{kind};base64,{b64}")),
                        id,
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    apps.sort_by_key(|a| a.name.to_lowercase());
    log::info!("pmos: {} apps on {}", apps.len(), phone.name);
    Ok(apps)
}

/// An app's window on the Apps page: which phone's, which app.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OpenApp {
    phone: String,
    app: String,
}

/// An app's window that went, and why when it wasn't asked to (the app closed
/// on the phone, or never opened).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AppEnded {
    phone: String,
    app: String,
    name: String,
    message: String,
}

fn open_apps() -> Vec<OpenApp> {
    app_sessions()
        .lock()
        .unwrap()
        .iter()
        .filter(|s| !s.ended())
        .filter_map(|s| s.app.as_ref().map(|a| OpenApp { phone: s.id.clone(), app: a.id.clone() }))
        .collect()
}

fn apps_changed(app: &AppHandle, ended: Option<AppEnded>) {
    let _ = app.emit("pmos:apps", serde_json::json!({ "open": open_apps(), "ended": ended }));
}

/// The phone's apps in windows of their own now, for an Apps page just opened.
#[tauri::command]
pub fn pmos_app_windows() -> Vec<OpenApp> {
    open_apps()
}

/// Apps being opened, by phone and app: a second click waits for the first.
fn opening() -> &'static Mutex<std::collections::HashSet<(String, String)>> {
    static OPENING: OnceLock<Mutex<std::collections::HashSet<(String, String)>>> = OnceLock::new();
    OPENING.get_or_init(Default::default)
}

/// Open one of the phone's apps in a window of its own, or bring its window to
/// the front if it has one. `icon` is the app's icon as a PNG, for the
/// window's own (the Apps page draws it, SVG or not). Returns once the window is.
#[tauri::command]
pub async fn pmos_open_app(
    app: AppHandle,
    id: String,
    app_id: String,
    name: String,
    icon: Option<Vec<u8>>,
) -> Result<(), String> {
    // As the helper takes it: a desktop file's name, nothing that could be a path or a word for the shell.
    if app_id.is_empty()
        || app_id.starts_with('.')
        || app_id.len() > 200
        || !app_id.chars().all(|c| c.is_ascii_alphanumeric() || ".-_".contains(c))
    {
        return Err("That isn't one of the phone's apps.".into());
    }
    let open = app_sessions()
        .lock()
        .unwrap()
        .iter()
        .find(|s| s.id == id && !s.ended() && s.app.as_ref().is_some_and(|a| a.id == app_id))
        .cloned();
    if let Some(s) = open {
        if let Some(w) = app.get_webview_window(&s.label) {
            let _ = w.unminimize();
            let _ = w.set_focus();
        }
        return Ok(());
    }
    let phone = paired(&app, &id)?;
    if !opening().lock().unwrap().insert((id.clone(), app_id.clone())) {
        return Ok(());
    }
    let result = open_app(&app, &phone, &app_id, &name, icon.as_deref()).await;
    opening().lock().unwrap().remove(&(id.clone(), app_id.clone()));
    match &result {
        Ok(()) => apps_changed(&app, None),
        Err(e) => log::warn!("pmos: {name} didn't open: {e}"),
    }
    result
}

async fn open_app(app: &AppHandle, phone: &Paired, app_id: &str, name: &str, icon: Option<&[u8]>) -> Result<(), String> {
    // One opening at a time: so two don't take the same screen, and the phone's sshd one login at a time.
    let _one = apps_lock().lock().await;
    let slot = (1..=APP_SLOTS)
        .find(|n| !app_sessions().lock().unwrap().iter().any(|s| s.app.as_ref().is_some_and(|a| a.slot == *n)))
        .ok_or("Eight of the phone's apps are open on this computer already. Close one first.")?;
    log::info!("pmos: opening {name} ({app_id}) from {} in a window of its own", phone.name);
    let ssh = reach(app, phone).await?;
    let fit = app_fit(app);
    let command = format!(
        "{}exec \"{HELPER_PATH}\" serve --app {app_id} --name OADX-App-{slot} --width {} --height {} --scale {:.4} --cap {CAP}",
        test_env(),
        fit.width,
        fit.height,
        fit.scale
    );
    let (read, write, buf, ready) = start_helper(&ssh, command, name).await?;
    log::info!("pmos: {name}'s screen is up: {ready}");
    let of = AppOf { id: app_id.to_string(), name: name.to_string(), slot };
    let s = begin(app, phone, ssh, read, write, buf, &ready, Some(of)).await?;
    if let Err(e) = open_app_window(app, &s, &fit, icon) {
        s.finish("");
        unregister(&s);
        tokio::time::sleep(Duration::from_millis(300)).await;
        hang_up(&s.ssh).await;
        return Err(format!("Couldn't open {name}'s window: {e}"));
    }
    tauri::async_runtime::spawn(wind_down(app.clone(), s));
    Ok(())
}

/// An app's screen: 1024 × 720 of this screen's points, as a Linux app's
/// window opens (apps/mod.rs's open_linux), in pixels at this screen's scale.
/// It follows the window from there, as the desktop's does.
fn app_fit(app: &AppHandle) -> Fit {
    let scale = crate::display::screen_for_desktop(app).map(|s| s.scale).unwrap_or(1.0).clamp(1.0, 4.0);
    let window = (1024.0, 720.0);
    let (width, height) = screen_for(window, scale);
    Fit { width, height, scale, window }
}

fn open_app_window(app: &AppHandle, s: &Session, fit: &Fit, icon: Option<&[u8]>) -> Result<(), String> {
    let a = s.app.as_ref().ok_or("not an app's window")?;
    if let Some(w) = app.get_webview_window(&s.label) {
        let _ = w.close();
    }
    let dev = if tauri::is_dev() { app.config().build.dev_url.clone() } else { None };
    let w = WebviewWindowBuilder::new(app, &s.label, WebviewUrl::App(format!("index.html#pmos={}", s.id).into()))
        .title(app_title(&a.name, &s.name))
        .inner_size(fit.window.0, fit.window.1)
        .min_inner_size(640.0, 400.0)
        .center()
        .background_color(tauri::window::Color(9, 11, 16, 255))
        .incognito(true)
        .on_navigation(move |url| may_navigate(dev.as_ref(), url))
        .on_new_window(|_, _| NewWindowResponse::Deny)
        .build()
        .map_err(|e| e.to_string())?;
    if let Some(png) = icon {
        set_window_icon(&w, png);
    }
    Ok(())
}

/// "Calculator — Galaxy S24": the app's, then the phone's, as an Android app's window is called.
fn app_title(title: &str, phone: &str) -> String {
    format!("{title} — {phone}")
}

/// What the app's window in front is called now, for its window here; its name when it says nothing.
fn retitle(s: &Session, title: &str) {
    let Some(a) = &s.app else { return };
    if let Some(w) = s.handle.get_webview_window(&s.label) {
        let _ = w.set_title(&app_title(if title.trim().is_empty() { &a.name } else { title.trim() }, &s.name));
    }
}

/// The window's icon, big and small, from the app's own (a PNG), as an Android app's window has (apps/win's set_icons).
#[cfg(windows)]
fn set_window_icon(w: &WebviewWindow, png: &[u8]) {
    use windows::Win32::Foundation::{HWND, LPARAM, WPARAM};
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateIconFromResourceEx, SendMessageW, ICON_BIG, ICON_SMALL, LR_DEFAULTCOLOR, WM_SETICON,
    };
    let Ok(raw) = w.hwnd() else { return };
    let hwnd = HWND(raw.0 as _);
    unsafe {
        for which in [ICON_BIG, ICON_SMALL] {
            if let Ok(icon) = CreateIconFromResourceEx(png, true, 0x0003_0000, 0, 0, LR_DEFAULTCOLOR) {
                SendMessageW(hwnd, WM_SETICON, Some(WPARAM(which as usize)), Some(LPARAM(icon.0 as isize)));
            }
        }
    }
}

#[cfg(not(windows))]
fn set_window_icon(_w: &WebviewWindow, _png: &[u8]) {}

// ── files dropped on the window ──────────────────────────────────────────

/// How a drop went: the copy into the phone's Downloads, in the report the
/// Linux desktop's window reads (dexremote::sftp::Report), and whether the app
/// it was dropped on took it.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PmosPut {
    #[serde(flatten)]
    report: sftp::Report,
    /// Taken by the app under the drop; false when nothing there takes files;
    /// null when it wasn't tried (nothing landed, or no place to drop it).
    taken: Option<bool>,
}

/// One frame's worth of a file's bytes: about what SSH's window lets through at once.
const CHUNK: usize = 256 * 1024;

/// Files dropped on the phone's desktop window: into its Downloads, through
/// the helper's `receive` on a channel of the session's own SSH connection
/// (no second login, nothing listening, no SFTP server needed on the phone),
/// then, given `at` (the drop's place in the picture's pixels), dropped on the
/// app under it by the helper (main.c, "Files dropped on the window").
/// `events` hears the copy's progress ten times a second. One copy per window
/// at a time: the window queues the drops that come during one.
#[tauri::command]
pub async fn pmos_put(
    window: WebviewWindow,
    id: String,
    paths: Vec<String>,
    at: Option<[u32; 2]>,
    events: Channel<sftp::Progress>,
) -> Result<PmosPut, String> {
    let s = by_label(window.label()).ok_or("This window's connection to the phone has closed.")?;
    if s.id != id {
        return Err("this window belongs to another phone".into());
    }
    let cancel = CancellationToken::new();
    {
        let mut copy = s.copy.lock().unwrap();
        if copy.is_some() {
            return Err("a copy is already running in this window".into());
        }
        *copy = Some(cancel.clone());
    }
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).collect();
    log::info!("pmos: copying {} dropped item(s) to {}", paths.len(), s.name);
    let result = put(&s, &paths, &events, &cancel).await;
    s.copy.lock().unwrap().take();
    let (report, uris) = result.inspect_err(|e| log::warn!("pmos: the copy failed: {e}"))?;
    log::info!(
        "pmos: {} {} file(s), {} bytes, to {}{}",
        if report.cancelled { "stopped after" } else { "copied" },
        report.files,
        report.bytes,
        report.dir,
        if report.links + report.unreadable > 0 {
            format!(" ({} links, {} unreadable, left out)", report.links, report.unreadable)
        } else {
            String::new()
        }
    );
    let taken = match at {
        Some([x, y]) if s.carry && !uris.is_empty() && !report.cancelled && !s.ended() => drop_on_app(&s, x, y, &uris).await,
        _ => None,
    };
    Ok(PmosPut { report, taken })
}

/// Stop the copy a window has under way. What it was in the middle of is
/// taken back on the phone; what had landed stays.
#[tauri::command]
pub fn pmos_put_cancel(window: WebviewWindow) {
    if let Some(s) = by_label(window.label()) {
        if let Some(copy) = s.copy.lock().unwrap().as_ref() {
            copy.cancel();
        }
    }
}

/// A file's 'F' frame for `receive` (main.c): its size, its time, its permissions, its path in the item.
fn file_head(f: &sftp::LocalFile) -> Vec<u8> {
    let mut head = Vec::with_capacity(20 + f.rel.len());
    head.extend_from_slice(&f.size.to_be_bytes());
    head.extend_from_slice(&i64::from(f.mtime.unwrap_or(0)).to_be_bytes());
    head.extend_from_slice(&f.mode.to_be_bytes());
    head.extend_from_slice(f.rel.as_bytes());
    head
}

/// Files of this size (4 MiB, 4,194,304 bytes) or more go over several channels
/// of the session's one SSH connection at once. The phone's SSH server
/// (dropbear) gives each channel a window of 24 KB: on the Galaxy S24's cable
/// one channel carried about 8.7 MiB/s, two 22.6, four 30.8, eight 38.5
/// (2026-11-16). Four, opened at the first big file of a drop and kept for the
/// rest of it.
const PART_MIN: u64 = 4 << 20;
const PARTS: u64 = 4;

/// A channel to the helper's `receive` (or `receive --part`): frames out, its JSON words back.
struct Receiver {
    write: russh::ChannelWriteHalf<client::Msg>,
    answers: mpsc::UnboundedReceiver<serde_json::Value>,
}

impl Receiver {
    async fn open(s: &Session, part: bool) -> Result<Self, String> {
        let channel = s.ssh.channel_open_session().await.map_err(|e| format!("The phone didn't take the files: {e}"))?;
        let command = format!("{}exec \"{HELPER_PATH}\" receive{}", test_env(), if part { " --part" } else { "" });
        channel.exec(true, command).await.map_err(|e| format!("The phone didn't take the files: {e}"))?;
        let (mut read, write) = channel.split();
        let (tx, answers) = mpsc::unbounded_channel();
        tauri::async_runtime::spawn(async move {
            let mut buf = Vec::new();
            while let Some(msg) = read.wait().await {
                match msg {
                    ChannelMsg::Data { data } => {
                        buf.extend_from_slice(&data);
                        while let Some((kind, payload)) = take_frame(&mut buf) {
                            if kind == 0 {
                                let _ = tx.send(serde_json::from_slice(&payload).unwrap_or_default());
                            }
                        }
                    }
                    ChannelMsg::ExtendedData { data, .. } => {
                        for line in phone_lines(&String::from_utf8_lossy(&data)) {
                            log::info!("pmos: phone: {line}");
                        }
                    }
                    _ => {}
                }
            }
        });
        Ok(Receiver { write, answers })
    }

    /// One whole frame. Never cut short: the phone would read the rest as another.
    async fn send(&self, kind: u8, payload: &[u8]) -> Result<(), String> {
        self.write.data(&frame(kind, payload)[..]).await.map_err(|_| "The phone stopped taking the files.".to_string())
    }

    async fn next(&mut self, limit: Duration) -> Result<serde_json::Value, String> {
        match tokio::time::timeout(limit, self.answers.recv()).await {
            Ok(Some(m)) if m["t"] == "error" => {
                Err(m["message"].as_str().unwrap_or("The phone couldn't save the files.").to_string())
            }
            Ok(Some(m)) => Ok(m),
            Ok(None) => Err("The phone stopped taking the files.".into()),
            Err(_) => Err("The phone took too long to answer.".into()),
        }
    }

    /// The next word that is `t`, past any others.
    async fn wait_for(&mut self, t: &str, limit: Duration) -> Result<serde_json::Value, String> {
        loop {
            let m = self.next(limit).await?;
            if m["t"] == t {
                return Ok(m);
            }
        }
    }

    /// An error the phone has already said, without waiting for one.
    fn failed(&mut self) -> Option<String> {
        while let Ok(m) = self.answers.try_recv() {
            if m["t"] == "error" {
                return Some(m["message"].as_str().unwrap_or("The phone couldn't save the files.").to_string());
            }
        }
        None
    }

    /// Done with it: "that was all", then the channel's end.
    async fn close(mut self) {
        if self.send(b'Q', &[]).await.is_ok() {
            let _ = self.wait_for("bye", Duration::from_secs(5)).await;
        }
        let _ = self.write.eof().await;
    }
}

fn read_error(name: &str, e: impl std::fmt::Display) -> String {
    sftp::PutError::Read { name: name.to_string(), why: e.to_string() }.to_string()
}

/// One share of a big file, `len` bytes from `start`, over a `receive --part`
/// channel into `remote` (its path under the phone's Downloads). Hands the
/// channel back with how it went.
#[allow(clippy::too_many_arguments)]
async fn send_share(
    mut chan: Receiver,
    local: PathBuf,
    name: String,
    remote: String,
    start: u64,
    len: u64,
    done: Arc<AtomicU64>,
    stop: CancellationToken,
) -> (Receiver, Result<(), String>) {
    let result = async {
        let mut file = tokio::fs::File::open(&local).await.map_err(|e| read_error(&name, e))?;
        tokio::io::AsyncSeekExt::seek(&mut file, std::io::SeekFrom::Start(start)).await.map_err(|e| read_error(&name, e))?;
        chan.send(b'O', remote.as_bytes()).await?;
        let mut reader = file.take(len);
        let mut payload = vec![0u8; 8 + CHUNK];
        let mut at = start;
        while at < start + len {
            let n = reader.read(&mut payload[8..]).await.map_err(|e| read_error(&name, e))?;
            if n == 0 {
                return Err(read_error(&name, "it got shorter while it was being copied"));
            }
            payload[..8].copy_from_slice(&at.to_be_bytes());
            chan.send(b'P', &payload[..8 + n]).await?;
            at += n as u64;
            done.fetch_add(n as u64, Ordering::Relaxed);
            if stop.is_cancelled() {
                return Err(String::new());
            }
            if let Some(why) = chan.failed() {
                return Err(why);
            }
        }
        chan.send(b'C', &[]).await?;
        chan.wait_for("done", Duration::from_secs(60)).await.map(|_| ())
    }
    .await;
    (chan, result)
}

/// The copy itself: what landed, and each landed item's file:// URI on the phone.
async fn put(
    s: &Session,
    paths: &[PathBuf],
    events: &Channel<sftp::Progress>,
    cancel: &CancellationToken,
) -> Result<(sftp::Report, Vec<String>), String> {
    let owned = paths.to_vec();
    let plan = tokio::task::spawn_blocking(move || sftp::plan(&owned))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
    let mut main = Receiver::open(s, false).await?;
    let ready = main.next(Duration::from_secs(15)).await?;
    let dir = ready["dir"].as_str().unwrap_or("Downloads").to_string();
    let free = ready["free"].as_u64().unwrap_or(0);
    if free > 0 && plan.bytes > free {
        main.close().await;
        return Err(sftp::PutError::NoRoom { needed: plan.bytes, free }.to_string());
    }
    let mut report = sftp::Report { dir, links: plan.links, unreadable: plan.unreadable, ..Default::default() };
    let mut uris = Vec::new();
    let mut progress = sftp::Progress { files_total: plan.files, bytes_total: plan.bytes, ..Default::default() };
    let mut told = Instant::now();
    let _ = events.send(progress.clone());
    // The channels for big files' shares, once there is one.
    let mut pool: Vec<Receiver> = Vec::new();

    let outcome: Result<(), String> = async {
        for item in &plan.items {
            // A single file this computer won't open is counted and left, as before it was ever claimed there.
            let mut first = None;
            if !item.is_dir {
                match tokio::fs::File::open(&item.files[0].path).await {
                    Ok(f) => first = Some(f),
                    Err(_) => {
                        report.unreadable += 1;
                        progress.files_done += 1;
                        progress.bytes_done += item.files[0].size;
                        continue;
                    }
                }
            }
            let mut head = vec![u8::from(item.is_dir)];
            head.extend_from_slice(item.name.as_bytes());
            main.send(b'I', &head).await?;
            // Its name there, for the shares of a big file in it.
            let claimed = if item.files.iter().any(|f| f.size >= PART_MIN) {
                let m = main.wait_for("claimed", Duration::from_secs(30)).await?;
                m["name"].as_str().unwrap_or_default().to_string()
            } else {
                String::new()
            };
            for d in &item.dirs {
                main.send(b'D', d.rel.as_bytes()).await?;
            }
            let (mut files, mut bytes) = (0u64, 0u64);
            for f in &item.files {
                let local = match first.take() {
                    Some(opened) => opened,
                    None => match tokio::fs::File::open(&f.path).await {
                        Ok(opened) => opened,
                        Err(_) => {
                            report.unreadable += 1;
                            progress.files_done += 1;
                            progress.bytes_done += f.size;
                            continue;
                        }
                    },
                };
                progress.current = f.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                let in_shares = f.size >= PART_MIN && !claimed.is_empty();
                // A file that goes in shares is made first ('G' answers "opened"): the shares'
                // channels are separate from this one, and nothing orders them, so a share could
                // otherwise reach the phone before the file it writes into exists.
                main.send(if in_shares { b'G' } else { b'F' }, &file_head(f)).await?;
                if in_shares {
                    main.wait_for("opened", Duration::from_secs(30)).await?;
                    // Its bytes in shares, over the pool's channels at once; then "they are there".
                    drop(local);
                    while (pool.len() as u64) < PARTS {
                        let mut part = Receiver::open(s, true).await?;
                        part.wait_for("ready", Duration::from_secs(15)).await?;
                        pool.push(part);
                    }
                    let remote = if item.is_dir { format!("{claimed}/{}", f.rel) } else { claimed.clone() };
                    let done = Arc::new(AtomicU64::new(0));
                    let stop = cancel.child_token();
                    let share = f.size.div_ceil(PARTS);
                    let mut shares = tokio::task::JoinSet::new();
                    for (i, chan) in pool.drain(..).enumerate() {
                        let start = i as u64 * share;
                        let len = share.min(f.size.saturating_sub(start));
                        let name = progress.current.clone();
                        shares.spawn(send_share(chan, f.path.clone(), name, remote.clone(), start, len, done.clone(), stop.clone()));
                    }
                    let before = progress.bytes_done;
                    let mut failure: Option<String> = None;
                    let mut tick = tokio::time::interval(Duration::from_millis(100));
                    loop {
                        tokio::select! {
                            joined = shares.join_next() => match joined {
                                None => break,
                                Some(Ok((chan, result))) => {
                                    pool.push(chan);
                                    if let Err(why) = result {
                                        stop.cancel();
                                        failure.get_or_insert(why);
                                    }
                                }
                                Some(Err(e)) => {
                                    stop.cancel();
                                    failure.get_or_insert(e.to_string());
                                }
                            },
                            _ = tick.tick() => {
                                progress.bytes_done = before + done.load(Ordering::Relaxed);
                                let _ = events.send(progress.clone());
                            }
                        }
                    }
                    progress.bytes_done = before + done.load(Ordering::Relaxed);
                    // Stopped or failed: the pool's channels are still closed below (only whole frames
                    // ever went on them), so no `receive --part` is left waiting on the phone.
                    if let Some(why) = failure {
                        return Err(why);
                    }
                    if cancel.is_cancelled() {
                        return Err(String::new());
                    }
                    main.send(b'S', &[]).await?;
                    files += 1;
                    bytes += f.size;
                    progress.files_done += 1;
                    continue;
                }
                // Exactly the size announced: a file that changes under the copy is caught, not half sent.
                let mut reader = local.take(f.size);
                let mut sent = 0u64;
                let mut chunk = vec![0u8; CHUNK];
                while sent < f.size {
                    let n = tokio::select! {
                        n = reader.read(&mut chunk) => n.map_err(|e| read_error(&progress.current, e))?,
                        _ = cancel.cancelled() => return Err(String::new()),
                    };
                    if n == 0 {
                        return Err(read_error(&progress.current, "it got shorter while it was being copied"));
                    }
                    // Whole frames only: a write cut short would leave the phone reading half of one.
                    main.send(b'B', &chunk[..n]).await?;
                    if cancel.is_cancelled() {
                        return Err(String::new());
                    }
                    sent += n as u64;
                    progress.bytes_done += n as u64;
                    if told.elapsed() >= Duration::from_millis(100) {
                        told = Instant::now();
                        let _ = events.send(progress.clone());
                        if let Some(why) = main.failed() {
                            return Err(why);
                        }
                    }
                }
                files += 1;
                bytes += sent;
                progress.files_done += 1;
            }
            main.send(b'E', &i64::from(item.mtime.unwrap_or(0)).to_be_bytes()).await?;
            let landed = main.wait_for("landed", Duration::from_secs(30)).await?;
            report.landed.push(landed["name"].as_str().unwrap_or(&item.name).to_string());
            if let Some(uri) = landed["uri"].as_str().filter(|u| !u.is_empty()) {
                uris.push(uri.to_string());
            }
            report.files += files;
            report.bytes += bytes;
            let _ = events.send(progress.clone());
            if cancel.is_cancelled() {
                return Err(String::new());
            }
        }
        Ok(())
    }
    .await;

    for part in pool {
        part.close().await;
    }
    if let Err(why) = outcome {
        // Cancelled (no words), or failed: the item under way is taken back on the phone.
        if main.send(b'X', &[]).await.is_ok() {
            let _ = main.wait_for("undone", Duration::from_secs(5)).await;
        }
        main.close().await;
        if !why.is_empty() {
            return Err(why);
        }
        report.cancelled = true;
        return Ok((report, uris));
    }
    main.close().await;
    Ok((report, uris))
}

/// The landed files, dropped by the helper where the window had them dropped.
async fn drop_on_app(s: &Session, x: u32, y: u32, uris: &[String]) -> Option<bool> {
    let (tx, rx) = oneshot::channel();
    *s.dropped.lock().unwrap() = Some(tx);
    let list: String = uris.iter().map(|u| format!("{u}\r\n")).collect();
    let ask = serde_json::json!({ "t": "drop", "x": x, "y": y, "uris": list });
    s.to_helper(0, ask.to_string().as_bytes());
    match tokio::time::timeout(Duration::from_secs(8), rx).await {
        Ok(Ok(answer)) => {
            let taken = answer["taken"].as_bool();
            match answer["why"].as_str() {
                Some(why) if taken != Some(true) => log::info!("pmos: the drop stayed in Downloads: {why}"),
                _ => log::info!("pmos: the app under the drop took it"),
            }
            taken
        }
        _ => {
            s.dropped.lock().unwrap().take();
            log::info!("pmos: the phone didn't say whether the app under the drop took it");
            None
        }
    }
}

/// The window's WebSocket: one at a time, a new one taking over from the old.
async fn serve(listener: TcpListener, s: Arc<Session>, dev: Option<String>) {
    let mut end = s.end.subscribe();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let Ok((tcp, _)) = accepted else { return };
                tauri::async_runtime::spawn(socket(tcp, s.clone(), dev.clone()));
            }
            _ = end.changed() => return,
        }
    }
}

static SOCKETS: AtomicU64 = AtomicU64::new(1);

async fn socket(mut tcp: TcpStream, s: Arc<Session>, dev: Option<String>) {
    let _ = tcp.set_nodelay(true);
    let Some(head) = crate::remote_usb::read_head(&mut tcp).await else { return };
    let key = match crate::remote_usb::check_request(&head, s.port, &s.token, dev.as_deref()) {
        Ok(()) => header(&head, "sec-websocket-key"),
        Err(why) => {
            log::info!("pmos: refused a request to the desktop's port ({why})");
            None
        }
    };
    let Some(key) = key else {
        let _ = tcp.write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await;
        return;
    };
    let accept = websocket_accept(&key);
    let answer = format!(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"
    );
    if tcp.write_all(answer.as_bytes()).await.is_err() {
        return;
    }
    // Anything the page sent after its request is the start of its first frame.
    let end = crate::remote_usb::head_end(&head).unwrap_or(head.len());
    let leftover = head[end..].to_vec();
    let (mut rd, mut wr) = tcp.into_split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Out>();
    let number = SOCKETS.fetch_add(1, Ordering::Relaxed);
    *s.window.lock().unwrap() = Some((number, tx.clone()));
    log::info!("pmos: {} connected", s.window_name());

    let writer = tauri::async_runtime::spawn(async move {
        while let Some((opcode, payload)) = rx.recv().await {
            if wr.write_all(&ws_frame(opcode, &payload)).await.is_err() {
                return;
            }
        }
        let _ = wr.write_all(&ws_frame(0x8, &[])).await;
        let _ = wr.shutdown().await;
    });
    let mut pending = leftover;
    let mut message: Vec<u8> = Vec::new();
    let mut message_op = 0u8;
    'read: loop {
        let frame = match read_ws_frame(&mut rd, &mut pending).await {
            Ok(f) => f,
            Err(_) => break,
        };
        let (fin, opcode, payload) = frame;
        match opcode {
            0x8 => break 'read,
            0x9 => {
                let _ = tx.send((0xA, payload));
            }
            0xA => {}
            0x0 | 0x1 | 0x2 => {
                if opcode != 0 {
                    message_op = opcode;
                    message.clear();
                }
                message.extend_from_slice(&payload);
                if message.len() > 4 << 20 {
                    break 'read;
                }
                if fin {
                    s.to_helper(if message_op == 1 { 0 } else { 1 }, &message);
                    message.clear();
                }
            }
            _ => break 'read,
        }
    }
    // Still the window's socket: the helper stops sending until the next one.
    let mine = {
        let mut w = s.window.lock().unwrap();
        if w.as_ref().is_some_and(|(n, _)| *n == number) {
            w.take();
            true
        } else {
            false
        }
    };
    if mine {
        s.to_helper(0, br#"{"t":"bye"}"#);
        log::info!("pmos: {} disconnected", s.window_name());
    }
    drop(tx);
    let _ = writer.await;
}

fn header(head: &[u8], name: &str) -> Option<String> {
    let text = std::str::from_utf8(head).ok()?;
    text.split("\r\n").skip(1).find_map(|line| {
        let (n, v) = line.split_once(':')?;
        n.trim().eq_ignore_ascii_case(name).then(|| v.trim().to_string())
    })
}

/// RFC 6455's answer to the page's key.
fn websocket_accept(key: &str) -> String {
    use base64::Engine;
    let mut sha = sha1_smol::Sha1::new();
    sha.update(key.as_bytes());
    sha.update(b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
    base64::engine::general_purpose::STANDARD.encode(sha.digest().bytes())
}

/// A server's frame: never masked.
fn ws_frame(opcode: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 10);
    out.push(0x80 | opcode);
    match payload.len() {
        n if n < 126 => out.push(n as u8),
        n if n < 65536 => {
            out.push(126);
            out.extend_from_slice(&(n as u16).to_be_bytes());
        }
        n => {
            out.push(127);
            out.extend_from_slice(&(n as u64).to_be_bytes());
        }
    }
    out.extend_from_slice(payload);
    out
}

/// One frame from the page, unmasked: (fin, opcode, payload).
async fn read_ws_frame(rd: &mut tokio::net::tcp::OwnedReadHalf, pending: &mut Vec<u8>) -> Result<(bool, u8, Vec<u8>), ()> {
    async fn need(rd: &mut tokio::net::tcp::OwnedReadHalf, pending: &mut Vec<u8>, n: usize) -> Result<(), ()> {
        let mut chunk = [0u8; 16 * 1024];
        while pending.len() < n {
            let got = rd.read(&mut chunk).await.map_err(|_| ())?;
            if got == 0 {
                return Err(());
            }
            pending.extend_from_slice(&chunk[..got]);
        }
        Ok(())
    }
    need(rd, pending, 2).await?;
    let (b0, b1) = (pending[0], pending[1]);
    let masked = b1 & 0x80 != 0;
    let mut at = 2;
    let len = match b1 & 0x7f {
        126 => {
            need(rd, pending, 4).await?;
            at = 4;
            u16::from_be_bytes([pending[2], pending[3]]) as usize
        }
        127 => {
            need(rd, pending, 10).await?;
            at = 10;
            u64::from_be_bytes(pending[2..10].try_into().unwrap()) as usize
        }
        n => n as usize,
    };
    if len > 4 << 20 {
        return Err(());
    }
    let mask_at = at;
    if masked {
        at += 4;
    }
    need(rd, pending, at + len).await?;
    let mut payload = pending[at..at + len].to_vec();
    if masked {
        let mask = [pending[mask_at], pending[mask_at + 1], pending[mask_at + 2], pending[mask_at + 3]];
        for (i, b) in payload.iter_mut().enumerate() {
            *b ^= mask[i % 4];
        }
    }
    pending.drain(..at + len);
    Ok((b0 & 0x80 != 0, b0 & 0x0f, payload))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_round_trip() {
        let mut buf = frame(0, br#"{"t":"hb"}"#);
        buf.extend(frame(1, &[0x12]));
        buf.extend_from_slice(&[1, 0, 0]);
        assert_eq!(take_frame(&mut buf), Some((0, br#"{"t":"hb"}"#.to_vec())));
        assert_eq!(take_frame(&mut buf), Some((1, vec![0x12])));
        assert_eq!(take_frame(&mut buf), None);
        assert_eq!(buf, vec![1, 0, 0]);
    }

    #[test]
    fn websocket_accept_is_rfc_6455s() {
        // The example in RFC 6455, section 1.3.
        assert_eq!(websocket_accept("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
    }

    #[test]
    fn server_frames_carry_their_length() {
        assert_eq!(ws_frame(1, b"hi"), vec![0x81, 2, b'h', b'i']);
        let big = ws_frame(2, &[0u8; 300]);
        assert_eq!(&big[..4], &[0x82, 126, 1, 44]);
        assert_eq!(big.len(), 304);
    }

    #[test]
    fn about_fields() {
        let about = "arch=aarch64\nmodel=Xiaomi Redmi Note 7\nhost=redmi-note-7\nos=postmarketOS edge\n";
        assert_eq!(field(about, "arch"), "aarch64");
        assert_eq!(field(about, "model"), "Xiaomi Redmi Note 7");
        assert_eq!(field(about, "os"), "postmarketOS edge");
        assert_eq!(field(about, "missing"), "");
    }

    #[test]
    fn key_comment_is_safe_inside_single_quotes() {
        // pmos_pair puts the key's line in single quotes in a shell command on
        // the phone; a computer called "IK's M1" must not end them.
        let c = key_comment();
        assert!(c.starts_with("open-android-dex"), "{c}");
        assert!(c.chars().all(|ch| ch.is_ascii_alphanumeric() || "-_.@".contains(ch)), "{c}");
    }

    #[test]
    fn noise_is_left_out_of_the_log() {
        let text = "\n(openandroiddex-pmos:1): GStreamer-CRITICAL **: x\nopenandroiddex-pmos: video: x264enc\n";
        assert_eq!(phone_lines(text).collect::<Vec<_>>(), vec!["video: x264enc"]);
    }

    #[test]
    fn a_files_header_is_what_receive_reads() {
        // main.c's rx_frame: [u64 size][i64 mtime][u32 mode][path], big-endian.
        let f = sftp::LocalFile {
            rel: "photos/ünï.jpg".into(),
            path: PathBuf::from("x"),
            size: 0x0102_0304_0506,
            mtime: Some(1_700_000_000),
            mode: 0o644,
        };
        let h = file_head(&f);
        assert_eq!(&h[..8], &[0, 0, 1, 2, 3, 4, 5, 6]);
        assert_eq!(&h[8..16], &1_700_000_000i64.to_be_bytes());
        assert_eq!(&h[16..20], &[0, 0, 1, 0xa4]);
        assert_eq!(&h[20..], "photos/ünï.jpg".as_bytes());
        // A file with no time: 0, which the phone leaves as the time it was written.
        assert_eq!(&file_head(&sftp::LocalFile { mtime: None, ..f })[8..16], &[0; 8]);
    }

    #[test]
    fn first_screen_is_the_windows() {
        // A 1512 × 949 screen at 100 %: its window's content, one for one.
        assert_eq!(screen_for((1496.0, 862.0), 1.0), (1496, 862));
        // An odd size, as the window would say it: even.
        assert_eq!(screen_for((1497.0, 863.0), 1.0), (1496, 862));
        // Across, whole cells of eight, as KWin's custom modes are.
        assert_eq!(screen_for((1500.0, 862.0), 1.0), (1504, 862));
        // Past CAP on its long side: made smaller, its shape kept, even.
        assert_eq!(screen_for((2560.0, 1400.0), 1.0), (1920, 1050));
        // Plasma's scale makes it that many more pixels.
        assert_eq!(screen_for((1280.0, 720.0), 1.25), (1600, 900));
    }
}
