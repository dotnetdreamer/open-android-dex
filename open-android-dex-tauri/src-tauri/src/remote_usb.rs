//! A paired phone's Linux desktop over adb — the cable, or wireless debugging
//! — as video from the phone's own encoder.
//!
//! The desktop window's second way to its phone; remote_desktop.rs is the
//! first, through the tunnel, wherever the two are. With the phone on adb the
//! launcher serves the desktop on an abstract socket (LinuxStream.java): Xvnc's
//! raw pixels into the phone's H.264 encoder, out as a WebSocket the window
//! decodes on this computer's GPU. This module puts that socket in front of
//! the window — a port on this computer's loopback that takes one request, the
//! window's own WebSocket, and splices it onto a stream adb's server opens to
//! the phone.
//!
//! **adb's own protocol, not `adb forward`.** A forward is a listening port,
//! and loopback is not private to the app: any web page in any browser here
//! could open a WebSocket to it. This way the only port is ours, and it holds
//! the line remote_desktop.rs's does — the request must name this port in
//! `Host`, come from the app's own page in `Origin`, and carry the window's
//! token in its path.
//!
//! **Which phone is it?** Each attached phone is asked `GET /probe` on its
//! socket, which answers with the id it was paired under: milliseconds,
//! against the second a `content call` costs. Only a phone whose launcher is
//! asleep needs one (`stream`, which wakes the service the socket lives in).
//!
//! Design record: doc/usb-desktop.md.

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Manager, WebviewWindow};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

/// The launcher's socket: LinuxStream.socketName, its package and a suffix.
const SOCKET: &str = "com.ccrstech.openandroiddex.launcher.linux-stream";
/// adb's server, unless the environment moved it (as adb itself honours).
const ADB_PORT: u16 = 5037;
/// No request the window makes comes near this.
const MAX_HEAD: usize = 16 * 1024;

fn adb_server() -> SocketAddr {
    let port = std::env::var("ANDROID_ADB_SERVER_PORT")
        .ok()
        .and_then(|p| p.trim().parse().ok())
        .unwrap_or(ADB_PORT);
    SocketAddr::from((Ipv4Addr::LOCALHOST, port))
}

// ── adb's wire ───────────────────────────────────────────────────────────

async fn adb_connect() -> Result<TcpStream, String> {
    let s = tokio::time::timeout(Duration::from_secs(2), TcpStream::connect(adb_server()))
        .await
        .map_err(|_| "adb's server did not answer".to_string())?
        .map_err(|e| format!("adb's server is not running ({e})"))?;
    let _ = s.set_nodelay(true);
    Ok(s)
}

/// One request to adb's server — four hex digits of length, then the text —
/// and its OKAY.
async fn adb_ask(s: &mut TcpStream, request: &str) -> Result<(), String> {
    s.write_all(format!("{:04x}{request}", request.len()).as_bytes())
        .await
        .map_err(|e| format!("adb: {e}"))?;
    let mut status = [0u8; 4];
    s.read_exact(&mut status).await.map_err(|e| format!("adb: {e}"))?;
    match &status {
        b"OKAY" => Ok(()),
        b"FAIL" => Err(format!("adb: {}", adb_string(s).await.unwrap_or_default())),
        other => Err(format!("adb answered {:?}", String::from_utf8_lossy(other))),
    }
}

/// A length-prefixed answer: four hex digits, then that many bytes.
async fn adb_string(s: &mut TcpStream) -> Result<String, String> {
    let mut len = [0u8; 4];
    s.read_exact(&mut len).await.map_err(|e| e.to_string())?;
    let len = std::str::from_utf8(&len)
        .ok()
        .and_then(|l| usize::from_str_radix(l, 16).ok())
        .ok_or("adb sent no length")?;
    let mut body = vec![0u8; len];
    s.read_exact(&mut body).await.map_err(|e| e.to_string())?;
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// A phone adb can talk to now.
#[derive(Debug, Clone, PartialEq)]
struct Device {
    serial: String,
    /// Wireless debugging rather than the cable: less bandwidth, more latency.
    wireless: bool,
}

/// `host:devices` — serial, a tab, the state, one per line. The tab is what
/// keeps a wireless phone's mDNS name, spaces and all, in one piece.
fn parse_devices(list: &str) -> Vec<Device> {
    list.lines()
        .filter_map(|line| {
            let (serial, state) = line.rsplit_once('\t')?;
            (state.trim() == "device" && !serial.is_empty()).then(|| Device {
                serial: serial.to_string(),
                wireless: serial.contains(':') || serial.contains("adb-tls-connect"),
            })
        })
        .collect()
}

async fn attached() -> Result<Vec<Device>, String> {
    let mut s = adb_connect().await?;
    adb_ask(&mut s, "host:devices").await?;
    Ok(parse_devices(&adb_string(&mut s).await?))
}

/// A byte stream to the launcher's socket on `serial`, straight through adb's
/// server — nothing listening anywhere on this computer.
async fn open_socket(serial: &str) -> Result<TcpStream, String> {
    let mut s = adb_connect().await?;
    adb_ask(&mut s, &format!("host:transport:{serial}")).await?;
    adb_ask(&mut s, &format!("localabstract:{SOCKET}")).await?;
    Ok(s)
}

// ── which phone ──────────────────────────────────────────────────────────

/// What the socket says it is: the id the phone was paired under.
#[derive(Debug, Clone, Deserialize)]
struct Probe {
    #[serde(default)]
    id: String,
}

async fn probe(serial: &str) -> Result<Probe, String> {
    let asked = async {
        let mut s = open_socket(serial).await?;
        s.write_all(b"GET /probe HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
            .await
            .map_err(|e| e.to_string())?;
        let mut got = Vec::new();
        (&mut s).take(64 * 1024).read_to_end(&mut got).await.map_err(|e| e.to_string())?;
        let text = String::from_utf8_lossy(&got);
        let (head, body) = text.split_once("\r\n\r\n").ok_or("the phone's socket said nothing")?;
        if !head.starts_with("HTTP/1.1 200") {
            return Err(format!("the phone answered {}", head.lines().next().unwrap_or("")));
        }
        serde_json::from_str::<Probe>(body).map_err(|e| e.to_string())
    };
    tokio::time::timeout(Duration::from_secs(4), asked)
        .await
        .map_err(|_| "the phone's socket did not answer".to_string())?
}

/// Each serial's phone, as last probed: a second phone on the same computer
/// is not woken up again every time a window looks for the first.
fn seen() -> &'static Mutex<HashMap<String, String>> {
    static SEEN: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    SEEN.get_or_init(Default::default)
}

/// `content call … --method stream`: wake the launcher's Linux service, where
/// the socket lives. Blocking — the content tool boots a VM per call.
fn wake_blocking(app: &AppHandle, serial: &str) -> Result<dexremote::usb::PhoneAnswer, String> {
    let mut args: Vec<String> = vec!["-s".into(), serial.into(), "shell".into()];
    args.extend(dexremote::usb::shell_args("stream", &serde_json::json!({})));
    let refs: Vec<&str> = args.iter().map(String::as_str).collect();
    let out = crate::adb::run_adb_timeout(app, &refs, Duration::from_secs(20))?;
    dexremote::usb::parse_answer(&out)
}

/// The attached phone paired as `id`, the cable ahead of Wi-Fi. With `wake`,
/// a phone whose socket does not answer is woken and asked again.
async fn find(app: &AppHandle, id: &str, wake: bool) -> Option<Device> {
    let mut devices = attached().await.ok()?;
    devices.sort_by_key(|d| d.wireless);
    let other = |serial: &str| seen().lock().unwrap().get(serial).is_some_and(|s| s != id);
    let mut asleep = Vec::new();
    for d in &devices {
        if other(&d.serial) {
            continue;
        }
        match probe(&d.serial).await {
            Ok(p) => {
                seen().lock().unwrap().insert(d.serial.clone(), p.id.clone());
                if p.id == id {
                    return Some(d.clone());
                }
            }
            Err(_) => asleep.push(d.clone()),
        }
    }
    if !wake {
        return None;
    }
    for d in asleep {
        let (app2, serial) = (app.clone(), d.serial.clone());
        let answer = tauri::async_runtime::spawn_blocking(move || wake_blocking(&app2, &serial)).await;
        match answer {
            Ok(Ok(a)) if a.ok && a.started != Some(false) => {}
            Ok(Ok(a)) => {
                log::info!("remote: {} cannot stream its desktop (linux {}, {:?})", d.serial, a.linux, a.error);
                continue;
            }
            _ => continue,
        }
        // The service takes a moment to come up and listen.
        for _ in 0..16 {
            tokio::time::sleep(Duration::from_millis(250)).await;
            if let Ok(p) = probe(&d.serial).await {
                seen().lock().unwrap().insert(d.serial.clone(), p.id.clone());
                if p.id == id {
                    return Some(d);
                }
                break;
            }
        }
    }
    None
}

// ── the window's port ────────────────────────────────────────────────────

/// One window's port: its token, and the phone it is currently put through
/// to — updated when the phone moves between the cable and Wi-Fi.
struct Link {
    port: u16,
    token: String,
    phone: Arc<Mutex<Device>>,
    task: JoinHandle<()>,
}

/// Each window's live streams over adb: its title is the adb route's while
/// there is one (the tunnel's watch stands aside, remote_desktop.rs).
fn live() -> &'static Mutex<HashMap<String, usize>> {
    static LIVE: OnceLock<Mutex<HashMap<String, usize>>> = OnceLock::new();
    LIVE.get_or_init(Default::default)
}

/// The window's desktop is coming over adb right now.
pub(crate) fn streaming(label: &str) -> bool {
    live().lock().unwrap().get(label).is_some_and(|n| *n > 0)
}

/// What a window's title calls adb: the cable, or wireless debugging.
fn route_for(device: &Device) -> &'static str {
    if device.wireless {
        "Wi-Fi"
    } else {
        "USB"
    }
}

fn links() -> &'static Mutex<HashMap<String, Link>> {
    static LINKS: OnceLock<Mutex<HashMap<String, Link>>> = OnceLock::new();
    LINKS.get_or_init(Default::default)
}

fn new_token() -> Result<String, String> {
    let mut b = [0u8; 16];
    if !crate::wireless::fill_random(&mut b) {
        return Err("the system random number generator is unavailable".into());
    }
    Ok(b.iter().map(|x| format!("{x:02x}")).collect())
}

/// What the window's port needs to set its title.
#[derive(Clone)]
struct Titles {
    app: AppHandle,
    label: String,
    name: String,
}

async fn link(label: &str, device: &Device, dev: Option<String>, titles: Titles) -> Result<(u16, String), String> {
    let existing = |map: &HashMap<String, Link>| {
        map.get(label).map(|l| {
            *l.phone.lock().unwrap() = device.clone();
            (l.port, l.token.clone())
        })
    };
    if let Some(found) = existing(&links().lock().unwrap()) {
        return Ok(found);
    }
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .map_err(|e| format!("cannot open a local port for the desktop: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let token = new_token()?;
    let mut map = links().lock().unwrap();
    // A second call raced this one in (React runs a window's first effect twice
    // in development): keep the first, and let this listener go.
    if let Some(found) = existing(&map) {
        return Ok(found);
    }
    let shared = Arc::new(Mutex::new(device.clone()));
    let task = tauri::async_runtime::spawn(serve(listener, port, token.clone(), shared.clone(), dev, titles));
    map.insert(label.to_string(), Link { port, token: token.clone(), phone: shared, task });
    Ok((port, token))
}

async fn serve(
    listener: TcpListener,
    port: u16,
    token: String,
    phone: Arc<Mutex<Device>>,
    dev: Option<String>,
    titles: Titles,
) {
    loop {
        let Ok((tcp, _)) = listener.accept().await else { return };
        let (token, phone, dev, titles) = (token.clone(), phone.clone(), dev.clone(), titles.clone());
        tauri::async_runtime::spawn(async move {
            let device = phone.lock().unwrap().clone();
            forward(tcp, port, &token, &device, dev.as_deref(), &titles).await;
        });
    }
}

/// The title while a stream is live, and back to the plain one after the
/// last ends — which the tunnel's watch refines, if the window goes there.
fn retitle(titles: &Titles, route: Option<&str>) {
    if let Some(window) = titles.app.get_webview_window(&titles.label) {
        let _ = window.set_title(&crate::remote_desktop::title_for(&titles.name, route));
    }
}

async fn forward(mut tcp: TcpStream, port: u16, token: &str, device: &Device, dev: Option<&str>, titles: &Titles) {
    let serial = device.serial.as_str();
    let _ = tcp.set_nodelay(true);
    let Some(received) = read_head(&mut tcp).await else { return };
    if let Err(why) = check_request(&received, port, token, dev) {
        log::info!("remote: refused a request to the desktop's video port ({why})");
        let _ = tcp
            .write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .await;
        return;
    }
    let mut phone = match open_socket(serial).await {
        Ok(p) => p,
        Err(e) => {
            log::info!("remote: the desktop's video socket on {serial}: {e}");
            let _ = tcp
                .write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .await;
            return;
        }
    };
    // On with the token taken out of the path: it is this port's, and the
    // phone serves its stream at /stream.
    if phone.write_all(&with_path(&received, "/stream")).await.is_err() {
        return;
    }
    *live().lock().unwrap().entry(titles.label.clone()).or_default() += 1;
    retitle(titles, Some(route_for(device)));
    // Deep buffers: the video is megabits a second through here, the input
    // a few bytes at a time, and neither should wait for the other.
    let _ = tokio::io::copy_bidirectional_with_sizes(&mut tcp, &mut phone, 64 * 1024, 256 * 1024).await;
    let left = {
        let mut map = live().lock().unwrap();
        let n = map.entry(titles.label.clone()).or_default();
        *n = n.saturating_sub(1);
        *n
    };
    if left == 0 {
        retitle(titles, None);
    }
}

async fn read_head(tcp: &mut TcpStream) -> Option<Vec<u8>> {
    let mut received = Vec::with_capacity(1024);
    let mut chunk = [0u8; 4096];
    let read = async {
        loop {
            let n = tcp.read(&mut chunk).await.ok()?;
            if n == 0 {
                return None;
            }
            received.extend_from_slice(&chunk[..n]);
            if head_end(&received).is_some() {
                return Some(());
            }
            if received.len() > MAX_HEAD {
                return None;
            }
        }
    };
    tokio::time::timeout(Duration::from_secs(10), read).await.ok()??;
    Some(received)
}

fn head_end(received: &[u8]) -> Option<usize> {
    received.windows(4).position(|w| w == b"\r\n\r\n").map(|i| i + 4)
}

/// The desktop app's own page: bundled (tauri://localhost on macOS and Linux,
/// http://tauri.localhost on Windows) or, in development, the dev server.
fn app_origin(origin: &str, dev: Option<&str>) -> bool {
    let o = origin.to_ascii_lowercase();
    matches!(o.as_str(), "tauri://localhost" | "http://tauri.localhost" | "https://tauri.localhost")
        || dev.is_some_and(|d| d.eq_ignore_ascii_case(origin))
}

/// Is this the window's own WebSocket? `GET /stream/<token>` addressed to
/// this port by name, from the app's own page, asking to upgrade.
///
/// A page from another site names itself in `Origin`, a DNS-rebinding one
/// carries its own name in `Host`, and neither knows the token; the app's
/// page is the only one that gets the URL.
fn check_request(received: &[u8], port: u16, token: &str, dev: Option<&str>) -> Result<(), &'static str> {
    let end = head_end(received).ok_or("no request")?;
    let head = std::str::from_utf8(&received[..end]).map_err(|_| "not text")?;
    let mut lines = head.split("\r\n");
    let first = lines.next().unwrap_or("");
    let mut parts = first.split(' ');
    let (method, path, version) = (parts.next(), parts.next(), parts.next());
    if method != Some("GET") || !version.is_some_and(|v| v.starts_with("HTTP/1.")) {
        return Err("not a GET");
    }
    if path != Some(&format!("/stream/{token}")) {
        return Err("not the window's path");
    }
    let ours = [format!("127.0.0.1:{port}"), format!("localhost:{port}")];
    let (mut host, mut origin, mut upgrade) = (None, None, None);
    for line in lines {
        let Some((name, value)) = line.split_once(':') else { continue };
        let slot = if name.eq_ignore_ascii_case("host") {
            &mut host
        } else if name.eq_ignore_ascii_case("origin") {
            &mut origin
        } else if name.eq_ignore_ascii_case("upgrade") {
            &mut upgrade
        } else {
            continue;
        };
        if slot.replace(value.trim()).is_some() {
            return Err("a header twice");
        }
    }
    let host = host.ok_or("no Host")?;
    if !ours.iter().any(|o| o.eq_ignore_ascii_case(host)) {
        return Err("another host");
    }
    // A browser always says where a WebSocket comes from.
    if !origin.is_some_and(|o| app_origin(o, dev)) {
        return Err("another origin");
    }
    if !upgrade.is_some_and(|u| u.eq_ignore_ascii_case("websocket")) {
        return Err("not a WebSocket");
    }
    Ok(())
}

/// `received` with its request line's path replaced.
fn with_path(received: &[u8], path: &str) -> Vec<u8> {
    let Some(line_end) = received.windows(2).position(|w| w == b"\r\n") else {
        return received.to_vec();
    };
    let line = String::from_utf8_lossy(&received[..line_end]);
    let mut parts: Vec<&str> = line.split(' ').collect();
    if parts.len() >= 2 {
        parts[1] = path;
    }
    let mut out = parts.join(" ").into_bytes();
    out.extend_from_slice(&received[line_end..]);
    out
}

/// The window is gone: so is its port.
pub fn window_closed(label: &str) {
    if let Some(link) = links().lock().unwrap().remove(label) {
        link.task.abort();
    }
    live().lock().unwrap().remove(label);
}

// ── the window's command ─────────────────────────────────────────────────

/// How a window reaches its phone's desktop over adb.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsbDesktop {
    /// The WebSocket to open: this computer's loopback, the window's token.
    pub url: String,
    /// Wireless debugging rather than the cable.
    pub wireless: bool,
}

/// From a desktop window: is its phone on adb, with a launcher that streams?
/// `Some` with the WebSocket to open if so; `None` sends the window through
/// the tunnel instead. `wake` lets a sleeping launcher be woken first, which
/// costs a second; the window's watch for the cable coming back does not.
#[tauri::command]
pub async fn remote_desktop_usb(
    app: AppHandle,
    window: WebviewWindow,
    id: String,
    wake: bool,
) -> Result<Option<UsbDesktop>, String> {
    // A window reaches its own phone and no other.
    if window.label() != crate::remote_desktop::label_for(&id) {
        return Err("this window belongs to another phone".into());
    }
    let paired = crate::remote::find(&id)?;
    let Some(device) = find(&app, &id, wake).await else {
        return Ok(None);
    };
    let dev = if tauri::is_dev() {
        app.config().build.dev_url.as_ref().map(|u| u.origin().ascii_serialization())
    } else {
        None
    };
    let titles = Titles { app: app.clone(), label: window.label().to_string(), name: paired.name };
    let (port, token) = link(window.label(), &device, dev, titles).await?;
    log::info!(
        "remote: the desktop goes over adb ({}, {})",
        device.serial,
        if device.wireless { "wireless debugging" } else { "the cable" }
    );
    Ok(Some(UsbDesktop { url: format!("ws://127.0.0.1:{port}/stream/{token}"), wireless: device.wireless }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(lines: &[&str]) -> Vec<u8> {
        let mut text = lines.join("\r\n");
        text.push_str("\r\n\r\n");
        text.into_bytes()
    }

    #[test]
    fn devices_keep_their_whole_serial() {
        let list = "R5CT30ABCDE\tdevice\nadb-RFCY10FRKBK-JGcERD (2)._adb-tls-connect._tcp\tdevice\n\
                    192.168.1.9:5555\toffline\nemulator-5554\tunauthorized\n";
        let got = parse_devices(list);
        assert_eq!(
            got,
            vec![
                Device { serial: "R5CT30ABCDE".into(), wireless: false },
                Device { serial: "adb-RFCY10FRKBK-JGcERD (2)._adb-tls-connect._tcp".into(), wireless: true },
            ]
        );
    }

    #[test]
    fn only_the_windows_own_socket_goes_through() {
        let ok = request(&[
            "GET /stream/abc HTTP/1.1",
            "Host: 127.0.0.1:7000",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Origin: tauri://localhost",
        ]);
        assert_eq!(check_request(&ok, 7000, "abc", None), Ok(()));
        let windows = request(&["GET /stream/abc HTTP/1.1", "Host: localhost:7000", "Upgrade: websocket", "Origin: http://tauri.localhost"]);
        assert_eq!(check_request(&windows, 7000, "abc", None), Ok(()));

        let dev = request(&["GET /stream/abc HTTP/1.1", "Host: 127.0.0.1:7000", "Upgrade: websocket", "Origin: http://localhost:1420"]);
        assert!(check_request(&dev, 7000, "abc", None).is_err(), "the dev server only in development");
        assert_eq!(check_request(&dev, 7000, "abc", Some("http://localhost:1420")), Ok(()));

        let site = request(&["GET /stream/abc HTTP/1.1", "Host: 127.0.0.1:7000", "Upgrade: websocket", "Origin: https://example.com"]);
        assert!(check_request(&site, 7000, "abc", None).is_err(), "a page from elsewhere");
        let no_origin = request(&["GET /stream/abc HTTP/1.1", "Host: 127.0.0.1:7000", "Upgrade: websocket"]);
        assert!(check_request(&no_origin, 7000, "abc", None).is_err());
        let rebound = request(&["GET /stream/abc HTTP/1.1", "Host: example.com:7000", "Upgrade: websocket", "Origin: tauri://localhost"]);
        assert!(check_request(&rebound, 7000, "abc", None).is_err(), "DNS rebinding");
        let token = request(&["GET /stream/xyz HTTP/1.1", "Host: 127.0.0.1:7000", "Upgrade: websocket", "Origin: tauri://localhost"]);
        assert!(check_request(&token, 7000, "abc", None).is_err(), "another token");
        let plain = request(&["GET /stream/abc HTTP/1.1", "Host: 127.0.0.1:7000", "Origin: tauri://localhost"]);
        assert!(check_request(&plain, 7000, "abc", None).is_err(), "not an upgrade");
        let twice = request(&[
            "GET /stream/abc HTTP/1.1",
            "Host: 127.0.0.1:7000",
            "Host: example.com",
            "Upgrade: websocket",
            "Origin: tauri://localhost",
        ]);
        assert!(check_request(&twice, 7000, "abc", None).is_err());
    }

    #[test]
    fn the_token_stays_on_this_computer() {
        let head = request(&["GET /stream/abc HTTP/1.1", "Host: 127.0.0.1:7000"]);
        let out = String::from_utf8(with_path(&head, "/stream")).unwrap();
        assert!(out.starts_with("GET /stream HTTP/1.1\r\nHost: 127.0.0.1:7000\r\n\r\n"));
        assert!(!out.contains("abc"));
    }

    /// adb's server, played by a socket: the requests arrive in its framing,
    /// and a FAIL comes back as the message it carries.
    #[tokio::test]
    async fn speaks_adbs_framing() {
        let server = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let addr = server.local_addr().unwrap();
        let fake = tokio::spawn(async move {
            let (mut s, _) = server.accept().await.unwrap();
            let mut got = [0u8; 16];
            s.read_exact(&mut got).await.unwrap();
            assert_eq!(&got, b"000chost:devices");
            s.write_all(b"OKAY0010serial-1\tdevice\n").await.unwrap();
            let mut got = [0u8; 20];
            s.read_exact(&mut got).await.unwrap();
            assert_eq!(&got, b"0010host:transport:x");
            s.write_all(b"FAIL0006closed").await.unwrap();
        });
        let mut c = TcpStream::connect(addr).await.unwrap();
        adb_ask(&mut c, "host:devices").await.unwrap();
        assert_eq!(parse_devices(&adb_string(&mut c).await.unwrap())[0].serial, "serial-1");
        assert_eq!(adb_ask(&mut c, "host:transport:x").await, Err("adb: closed".into()));
        fake.await.unwrap();
    }
}
