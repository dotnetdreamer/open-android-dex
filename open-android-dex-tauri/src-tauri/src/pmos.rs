//! A phone running postmarketOS, on the Desktop tab: a screen of its own on
//! the phone, made by its desktop (KDE Plasma), in a window here, the way DeX
//! gives an Android phone's.
//!
//! There is no adb. postmarketOS puts a network on the USB cable instead, on
//! which the phone is always 172.16.42.1, with SSH. Over that SSH this computer
//! signs in once with the phone's own password, leaves a key of its own in its
//! authorized_keys, and from then on needs nothing typed: it puts its helper
//! on the phone (openandroiddex-pmos, built in that folder and embedded here),
//! starts it, and talks to it on its stdin and stdout. The helper asks KWin for
//! a virtual output the size of this computer's screen, which Plasma fills
//! with a desktop as it would a monitor plugged in, and sends it as H.264; the
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

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use russh::client::{self, Handle};
use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::{HashAlg, PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::ChannelMsg;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Emitter, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, watch};

/// The phone's end of postmarketOS's USB network. Every phone is this address
/// on its own cable; the computer's end is given an address beside it.
const ADDRESS: Ipv4Addr = Ipv4Addr::new(172, 16, 42, 1);
const SSH_PORT: u16 = 22;

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
    std::fs::write(&path, dexremote::sshkey::private_pem(&seed, &public, &key_comment()))
        .map_err(|e| format!("could not keep this computer's key: {e}"))?;
    Ok(seed)
}

/// What the key is called in the phone's authorized_keys: whose it is.
fn key_comment() -> String {
    let host = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_default();
    let host: String = host.chars().filter(|c| c.is_ascii_alphanumeric() || "-_.".contains(*c)).take(40).collect();
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
    let tcp = tokio::time::timeout(Duration::from_secs(3), TcpStream::connect(SocketAddr::from((ADDRESS, SSH_PORT))))
        .await
        .map_err(|_| "The phone didn't answer on its USB network.".to_string())?
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
                    log::info!("pmos: a phone is on the USB network, and its SSH: {e}");
                    None
                }
            };
            if key.is_some() && cached.is_none() {
                log::info!("pmos: a phone is on the USB network");
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
        return Err("Enter the username and password you use on the phone.".into());
    }
    let (mut ssh, host_key) = connect(None).await?;
    let answer = ssh
        .authenticate_password(user.as_str(), password)
        .await
        .map_err(|e| format!("SSH to the phone failed: {e}"))?;
    if !answer.success() {
        hang_up(&ssh).await;
        return Err("That username or password didn't work on the phone.".into());
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
    /// Framed messages for the helper's stdin.
    to_helper: mpsc::UnboundedSender<Vec<u8>>,
    /// The window's socket while one is open, by its number: a new one takes over.
    window: Mutex<Option<(u64, mpsc::UnboundedSender<Out>)>>,
    /// Set once, when the session ends: why, in the Desktop tab's words; empty when asked to.
    end: watch::Sender<Option<String>>,
}

impl Session {
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
fn fit(app: &AppHandle) -> Fit {
    let fallback = Fit { width: 1280, height: 720, scale: 1.0, window: (1280.0, 720.0) };
    let Some(screen) = crate::display::screen_for_desktop(app) else { return fallback };
    let Some(g) = crate::display::geometry(&screen, CAP) else { return fallback };
    let f = screen.frame;
    let cw = f64::from(screen.work_w.saturating_sub(f.left + f.right).max(1));
    let ch = f64::from(screen.work_h.saturating_sub(f.top + f.bottom).max(1));
    let k = (f64::from(CAP) / cw.max(ch)).min(1.0);
    Fit {
        width: g.width,
        height: g.height,
        scale: (screen.scale * k).clamp(1.0, 4.0),
        window: ((cw / screen.scale).round(), (ch / screen.scale).round()),
    }
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
        "exec \"{HELPER_PATH}\" serve --width {} --height {} --scale {:.4}",
        fit.width, fit.height, fit.scale
    );
    log::info!("pmos: asking for a {}x{} screen at scale {:.3}", fit.width, fit.height, fit.scale);
    let channel = ssh.channel_open_session().await.map_err(|e| e.to_string())?;
    channel.exec(true, command).await.map_err(|e| e.to_string())?;
    let (mut read, write) = channel.split();

    // Up to its first word: ready, or why not.
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
                                return Err(msg["message"].as_str().unwrap_or("The phone couldn't start the desktop.").to_string())
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
                        "The phone's helper stopped before the desktop was up.".to_string()
                    } else {
                        format!("The phone couldn't start the desktop: {why}")
                    })
                }
            }
        }
    })
    .await
    .map_err(|_| "The phone took too long to make the desktop's screen.".to_string())??;
    log::info!("pmos: the phone's screen is up: {ready}");
    tell(app, &phone.id, &phone.name, "launching", Some(2), None);

    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .map_err(|e| format!("cannot open a local port for the desktop: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let mut token = [0u8; 16];
    if !crate::wireless::fill_random(&mut token) {
        return Err("the system's random number generator is unavailable".into());
    }
    let (to_helper, helper_rx) = mpsc::unbounded_channel();
    let (end, _) = watch::channel(None);
    let s = Arc::new(Session {
        id: phone.id.clone(),
        name: phone.name.clone(),
        label: label_for(&phone.id),
        port,
        token: token.iter().map(|b| format!("{b:02x}")).collect(),
        to_helper,
        window: Mutex::new(None),
        end,
    });
    *session().lock().unwrap() = Some(s.clone());

    tauri::async_runtime::spawn(write_helper(write, helper_rx, s.clone()));
    tauri::async_runtime::spawn(read_helper(read, buf, s.clone()));
    tauri::async_runtime::spawn(heartbeat(s.clone()));
    let dev = if tauri::is_dev() { app.config().build.dev_url.clone().map(|u| u.origin().ascii_serialization()) } else { None };
    tauri::async_runtime::spawn(serve(listener, s.clone(), dev));

    if let Err(e) = open_window(app, &s, &fit) {
        // Undone here rather than by wind_down, which would call it closed
        // over the failure the Desktop tab is about to be told.
        s.finish("");
        *session().lock().unwrap() = None;
        tokio::time::sleep(Duration::from_millis(300)).await;
        hang_up(&ssh).await;
        return Err(format!("Couldn't open the desktop's window: {e}"));
    }
    tauri::async_runtime::spawn(wind_down(app.clone(), s.clone(), ssh));
    tell(app, &s.id, &s.name, "running", Some(3), None);
    Ok(())
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
    // Asked to end: the helper takes its screen down, and the channel goes.
    let _ = write.data(&frame(0, br#"{"t":"stop"}"#)[..]).await;
    let _ = write.eof().await;
    let _ = write.close().await;
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
async fn wind_down(app: AppHandle, s: Arc<Session>, ssh: Handle<Pin>) {
    let mut end = s.end.subscribe();
    while end.borrow().is_none() {
        if end.changed().await.is_err() {
            break;
        }
    }
    let why = end.borrow().clone().unwrap_or_default();
    // The helper's stop, written by write_helper, a moment to land.
    tokio::time::sleep(Duration::from_millis(300)).await;
    hang_up(&ssh).await;
    {
        let mut slot = session().lock().unwrap();
        if slot.as_ref().is_some_and(|c| Arc::ptr_eq(c, &s)) {
            *slot = None;
        }
    }
    s.window.lock().unwrap().take();
    if let Some(w) = app.get_webview_window(&s.label) {
        let _ = w.close();
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

/// The phone's desktop window has gone: so has its session.
pub fn window_closed(label: &str) {
    if let Some(s) = current().filter(|s| s.label == label) {
        s.finish("");
    }
}

/// The app is quitting.
pub fn close_all() {
    if let Some(s) = current() {
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
        .title(format!("{} — postmarketOS · USB", s.name))
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
}

/// From the phone's desktop window: the WebSocket to open. A window gets its
/// own phone's and no other.
#[tauri::command]
pub fn pmos_window_link(window: WebviewWindow, id: String) -> Result<PmosLink, String> {
    let s = current().ok_or("The phone's desktop has closed.")?;
    if window.label() != s.label || s.id != id {
        return Err("this window belongs to another phone".into());
    }
    Ok(PmosLink { url: format!("ws://127.0.0.1:{}/stream/{}", s.port, s.token), name: s.name.clone() })
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
    log::info!("pmos: the desktop's window connected");

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
        log::info!("pmos: the desktop's window disconnected");
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
    fn noise_is_left_out_of_the_log() {
        let text = "\n(openandroiddex-pmos:1): GStreamer-CRITICAL **: x\nopenandroiddex-pmos: video: x264enc\n";
        assert_eq!(phone_lines(text).collect::<Vec<_>>(), vec!["video: x264enc"]);
    }
}
