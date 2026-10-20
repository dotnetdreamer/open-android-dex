//! The phone's Android desktop in a window of this app's own — the desktop's
//! own video path (doc/dex-video.md) — with scrcpy's window the fallback.
//!
//! The session is scrcpy.rs's whichever window shows it: a child process its
//! monitor watches, reconnects and ends, a display the launcher is deployed
//! onto, an enforcer keeping the windows on it in shape. On this path the child
//! is an `adb shell` running the window daemon's dex as the desktop's server
//! (openandroiddex-wmd, Desktop): it makes the display and says so in scrcpy's
//! own words, serves the picture on an abstract socket only adb can reach, and
//! exits when the shell does — the cable pulled, the session stopped. This
//! module is the rest: the server's command line, the window (DexWindow.tsx),
//! and the window's way to the socket — a port on this computer's loopback,
//! held to remote_usb.rs's checks (Host, Origin, the window's token), spliced
//! onto a stream adb's server opens to the phone. Nothing listens anywhere else.

use std::collections::{HashMap, HashSet};
use std::hash::{DefaultHasher, Hash, Hasher};
use std::net::{Ipv4Addr, TcpListener as StdListener};
use std::process::{Child, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

use serde::Serialize;
use tauri::async_runtime::JoinHandle;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, Window, WindowEvent};
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;

use crate::adb;
use crate::remote_usb;
use crate::scrcpy::MirrorOptions;
use crate::transfer;

/// The server's dex on the phone: the window daemon's own build, in a file of
/// its own. It has to be there before the display — which is to say before the
/// launcher's deploy, which pushes wmd.dex — and a running server keeps the copy
/// it started from (the push below lands by rename).
const DEX_PATH: &str = "/data/local/tmp/oadx-desktop.dex";
/// The server: a second entry point in the window daemon's dex.
const SERVER_CLASS: &str = "com.ccrstech.openandroiddex.wmd.Desktop";

/// One desktop's window and its way to the phone, for as long as the session lives.
struct Link {
    label: String,
    serial: String,
    /// The phone, in the window's words.
    name: String,
    wireless: bool,
    /// The socket the current server listens on: a new one each spawn, so a
    /// server left over from before can never answer for this one.
    socket: Arc<Mutex<String>>,
    port: u16,
    token: String,
    task: JoinHandle<()>,
    /// This side is closing the window (the session ended, or moved to
    /// scrcpy's window): its going is not the person ending the desktop.
    closing: Arc<AtomicBool>,
    /// The window asked for scrcpy's window instead.
    fallback: Arc<AtomicBool>,
    /// Whether the window was fullscreen, last it was looked at.
    fullscreen: Arc<AtomicBool>,
}

fn links() -> &'static Mutex<HashMap<String, Link>> {
    static LINKS: OnceLock<Mutex<HashMap<String, Link>>> = OnceLock::new();
    LINKS.get_or_init(Default::default)
}

/// Phones this run of the app has seen this path fail on: they get scrcpy's
/// window until the app starts again, rather than a failed attempt each time.
fn fell_back_on() -> &'static Mutex<HashSet<String>> {
    static FELL: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
    FELL.get_or_init(Default::default)
}

/// The desktop window that has the keyboard, by its label's hash; 0 for none.
/// Read by the touchpad reader for every gesture, so it must never block.
static FOCUSED: AtomicU64 = AtomicU64::new(0);

fn hash_of(label: &str) -> u64 {
    let mut h = DefaultHasher::new();
    label.hash(&mut h);
    h.finish().max(1)
}

/// The window's label for a session: Tauri allows letters, digits and `-/:_`,
/// and a serial can be an mDNS name with spaces and dots in it.
pub(crate) fn label_for(key: &str) -> String {
    format!("dex-{:016x}", hash_of(key))
}

/// The window daemon's dex is in the bundle: this path is possible at all.
pub(crate) fn bundled(app: &AppHandle) -> bool {
    adb::bin_dir(app).is_ok_and(|d| d.join("openandroiddex-wmd.dex").is_file())
}

pub(crate) fn fell_back(serial: &str) -> bool {
    fell_back_on().lock().unwrap().contains(serial)
}

pub(crate) fn remember_fell_back(serial: &str) {
    fell_back_on().lock().unwrap().insert(serial.to_string());
}

/// The desktop should go to scrcpy's window: its own did not open.
pub(crate) fn request_fallback(key: &str) {
    if let Some(l) = links().lock().unwrap().get(key) {
        l.fallback.store(true, Ordering::SeqCst);
    }
}

/// The window asked for scrcpy's: true once, for the monitor to act on.
pub(crate) fn take_fallback(key: &str) -> bool {
    links().lock().unwrap().get(key).is_some_and(|l| l.fallback.swap(false, Ordering::SeqCst))
}

/// The session's own window has the keyboard. Never blocks: a contended map
/// answers "no", which every caller reads as "not the desktop".
pub(crate) fn window_in_front(key: &str) -> bool {
    let front = FOCUSED.load(Ordering::SeqCst);
    front != 0 && front == hash_of(&label_for(key))
}

// ── the server ───────────────────────────────────────────────────────────

/// Start the desktop's server for `opts`: the dex pushed, a new socket, and an
/// `adb shell` whose stdin stays open for as long as the session does — the
/// server ends when it closes.
pub(crate) fn spawn(app: &AppHandle, opts: &MirrorOptions) -> Result<Child, String> {
    let serial = opts.serial.as_str();
    let dex = adb::bin_dir(app)?.join("openandroiddex-wmd.dex");
    if !dex.is_file() {
        return Err("openandroiddex-wmd.dex is not bundled".into());
    }
    let staged = format!("{DEX_PATH}.new");
    adb::run_adb(app, &["-s", serial, "push", &dex.to_string_lossy(), &staged])?;
    adb::run_adb(app, &["-s", serial, "shell", &format!("mv -f {staged} {DEX_PATH}")])?;
    let display = opts.new_display.as_deref().ok_or("no display size")?;
    let socket = format!("openandroiddex-desktop-{}", &remote_usb::new_token()?[..8]);
    ensure_link(app, opts, &socket)?;
    let command = format!("CLASSPATH={DEX_PATH} exec app_process / {SERVER_CLASS} {display} {socket}");
    log::info!("dex [{serial}|desktop] spawning: adb -s {serial} shell {command}");
    let mut cmd = adb::adb_command(app);
    cmd.args(["-s", serial, "shell", &command]);
    cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
    adb::hide_console(&mut cmd);
    cmd.spawn().map_err(|e| format!("failed to start the desktop's server: {e}"))
}

/// The session's link, made on its first spawn and pointed at each new socket.
fn ensure_link(app: &AppHandle, opts: &MirrorOptions, socket: &str) -> Result<(), String> {
    let key = format!("{}|desktop", opts.serial);
    let mut map = links().lock().unwrap();
    if let Some(l) = map.get(&key) {
        *l.socket.lock().unwrap() = socket.to_string();
        return Ok(());
    }
    let listener =
        StdListener::bind((Ipv4Addr::LOCALHOST, 0)).map_err(|e| format!("cannot open a local port for the desktop: {e}"))?;
    listener.set_nonblocking(true).map_err(|e| e.to_string())?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let token = remote_usb::new_token()?;
    let current = Arc::new(Mutex::new(socket.to_string()));
    let dev = if tauri::is_dev() {
        app.config().build.dev_url.as_ref().map(|u| u.origin().ascii_serialization())
    } else {
        None
    };
    let task = tauri::async_runtime::spawn(serve(listener, port, token.clone(), opts.serial.clone(), current.clone(), dev));
    let name = opts.window_title.split(" — ").next().unwrap_or("").trim();
    map.insert(
        key.clone(),
        Link {
            label: label_for(&key),
            serial: opts.serial.clone(),
            name: if name.is_empty() { "your phone".into() } else { name.to_string() },
            wireless: crate::display::is_wireless(&opts.serial),
            socket: current,
            port,
            token,
            task,
            closing: Arc::default(),
            fallback: Arc::default(),
            fullscreen: Arc::default(),
        },
    );
    Ok(())
}

async fn serve(listener: StdListener, port: u16, token: String, serial: String, socket: Arc<Mutex<String>>, dev: Option<String>) {
    let listener = match tokio::net::TcpListener::from_std(listener) {
        Ok(l) => l,
        Err(e) => return log::warn!("dex: the window's port failed: {e}"),
    };
    loop {
        let Ok((tcp, _)) = listener.accept().await else { return };
        let (token, serial, socket, dev) = (token.clone(), serial.clone(), socket.clone(), dev.clone());
        tauri::async_runtime::spawn(async move {
            forward(tcp, port, &token, &serial, &socket, dev.as_deref()).await;
        });
    }
}

/// The window's WebSocket, checked, and on to the server's socket through adb.
async fn forward(mut tcp: TcpStream, port: u16, token: &str, serial: &str, socket: &Mutex<String>, dev: Option<&str>) {
    let _ = tcp.set_nodelay(true);
    let Some(received) = remote_usb::read_head(&mut tcp).await else { return };
    if let Err(why) = remote_usb::check_request(&received, port, token, dev) {
        log::info!("dex: refused a request to the desktop's video port ({why})");
        let _ = tcp
            .write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .await;
        return;
    }
    let name = socket.lock().unwrap().clone();
    let mut phone = match open(serial, &name).await {
        Ok(p) => p,
        Err(e) => {
            log::info!("dex: the desktop's socket on {serial}: {e}");
            let _ = tcp
                .write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .await;
            return;
        }
    };
    // The token is this port's; the server serves its stream at /stream.
    if phone.write_all(&remote_usb::with_path(&received, "/stream")).await.is_err() {
        return;
    }
    // Deep buffers: the video is megabits a second through here, the input a
    // few bytes at a time, and neither should wait for the other.
    let _ = tokio::io::copy_bidirectional_with_sizes(&mut tcp, &mut phone, 64 * 1024, 256 * 1024).await;
}

/// A byte stream to the server's socket, straight through adb's server.
async fn open(serial: &str, socket: &str) -> Result<TcpStream, String> {
    let mut s = remote_usb::adb_connect().await?;
    remote_usb::adb_ask(&mut s, &format!("host:transport:{serial}")).await?;
    remote_usb::adb_ask(&mut s, &format!("localabstract:{socket}")).await?;
    Ok(s)
}

// ── the window ───────────────────────────────────────────────────────────

/// `key` as it goes in the window's address: everything but letters and digits escaped.
fn escaped(key: &str) -> String {
    key.bytes()
        .map(|b| if b.is_ascii_alphanumeric() { (b as char).to_string() } else { format!("%{b:02X}") })
        .collect()
}

/// Where the window may go: its own page — bundled, or the dev server in
/// development — and nowhere else.
fn may_navigate(dev: Option<&Url>, url: &Url) -> bool {
    match url.scheme() {
        "about" => true,
        "tauri" => url.host_str() == Some("localhost"),
        "http" | "https" if url.host_str() == Some("tauri.localhost") => true,
        _ => dev.is_some_and(|dev| dev.origin() == url.origin()),
    }
}

/// Open the session's window, filling the screen the app's own window is on —
/// where the person who started the desktop is looking. The display follows
/// the window from there, so any size it is given is shown one for one.
pub(crate) fn open_window(app: &AppHandle, key: &str, opts: &MirrorOptions) -> Result<(), String> {
    let label = label_for(key);
    if let Some(w) = app.get_webview_window(&label) {
        let _ = w.set_focus();
        return Ok(());
    }
    let dev = if tauri::is_dev() { app.config().build.dev_url.clone() } else { None };
    let title = match opts.window_title.trim() {
        "" => "Open Android DeX",
        t => t,
    };
    let mut b = WebviewWindowBuilder::new(app, &label, WebviewUrl::App(format!("index.html#dex={}", escaped(key)).into()))
        .title(title)
        .background_color(tauri::window::Color(0, 0, 0, 255))
        .min_inner_size(640.0, 400.0)
        .accept_first_mouse(true)
        .always_on_top(opts.always_on_top)
        .fullscreen(opts.fullscreen)
        .on_navigation(move |url| may_navigate(dev.as_ref(), url))
        .on_new_window(|_, _| NewWindowResponse::Deny);
    let monitor = app
        .get_webview_window("main")
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten());
    b = match monitor {
        Some(m) => {
            let s = m.scale_factor();
            let area = m.work_area();
            let (x, y) = (f64::from(area.position.x) / s, f64::from(area.position.y) / s);
            let (w, h) = (f64::from(area.size.width) / s, f64::from(area.size.height) / s);
            // Restored, it is most of that screen; it opens maximised.
            b.position(x + w * 0.05, y + h * 0.05).inner_size(w * 0.9, h * 0.9).maximized(true)
        }
        None => b.inner_size(1280.0, 800.0).center(),
    };
    b.build().map_err(|e| e.to_string())?;
    log::info!("dex [{key}] opened the desktop's window");
    Ok(())
}

/// The session is over, or moving to scrcpy's window: the window goes, and so
/// does its port. Not the person closing it, so nothing more is stopped.
pub(crate) fn close_window(app: &AppHandle, key: &str) {
    let link = links().lock().unwrap().remove(key);
    let label = match link {
        Some(l) => {
            l.closing.store(true, Ordering::SeqCst);
            l.task.abort();
            l.label
        }
        None => label_for(key),
    };
    if let Some(w) = app.get_webview_window(&label) {
        let _ = w.destroy();
    }
}

/// "Show desktop": the window to the front. False when the session has none.
pub(crate) fn focus(app: &AppHandle, key: &str) -> bool {
    let Some(w) = app.get_webview_window(&label_for(key)) else { return false };
    let _ = w.unminimize();
    let _ = w.show();
    let _ = w.set_focus();
    true
}

/// The taskbar's ⛶, for a desktop in this window: its new state, or None when
/// the session's window is scrcpy's.
pub(crate) fn toggle_fullscreen(app: &AppHandle, key: &str) -> Option<Result<bool, String>> {
    let w = app.get_webview_window(&label_for(key))?;
    Some((|| {
        let on = !w.is_fullscreen().map_err(|e| e.to_string())?;
        w.set_fullscreen(on).map_err(|e| e.to_string())?;
        Ok(on)
    })())
}

/// What happens to a desktop window, from lib.rs's window handler.
pub fn on_window_event(window: &Window, event: &WindowEvent) {
    let label = window.label();
    if !label.starts_with("dex-") {
        return;
    }
    let app = window.app_handle().clone();
    let key = || {
        links()
            .lock()
            .unwrap()
            .iter()
            .find(|(_, l)| l.label == label)
            .map(|(k, l)| (k.clone(), l.closing.load(Ordering::SeqCst), l.fullscreen.clone()))
    };
    match event {
        WindowEvent::Focused(on) => {
            let me = hash_of(label);
            if *on {
                FOCUSED.store(me, Ordering::SeqCst);
            } else {
                let _ = FOCUSED.compare_exchange(me, 0, Ordering::SeqCst, Ordering::SeqCst);
            }
        }
        WindowEvent::Destroyed => {
            let _ = FOCUSED.compare_exchange(hash_of(label), 0, Ordering::SeqCst, Ordering::SeqCst);
            // Closed by the person: that is the desktop ending, as closing
            // scrcpy's window always was. Off this thread — stopping waits.
            if let Some((key, false, _)) = key() {
                log::info!("dex [{key}] the desktop's window was closed — ending the desktop");
                if let Some(l) = links().lock().unwrap().remove(&key) {
                    l.task.abort();
                }
                thread::spawn(move || {
                    let _ = crate::scrcpy::stop_mirror(app, key);
                });
            }
        }
        WindowEvent::Resized(_) => {
            // Fullscreen by the window's own button or F11: the taskbar's ⛶
            // says what the window is.
            if let (Some((key, _, was)), Ok(now)) = (key(), window.is_fullscreen()) {
                if was.swap(now, Ordering::SeqCst) != now {
                    crate::scrcpy::record_desktop_fullscreen(&key, now);
                }
            }
        }
        WindowEvent::ScaleFactorChanged { scale_factor, .. } => {
            if let Some((key, _, _)) = key() {
                let scale = *scale_factor;
                thread::spawn(move || crate::scrcpy::desktop_scale_changed(&app, &key, scale));
            }
        }
        _ => {}
    }
}

// ── the window's commands ────────────────────────────────────────────────

/// How a desktop window reaches its phone.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DexLink {
    /// The WebSocket to open: this computer's loopback, the window's token.
    pub url: String,
    pub name: String,
    /// As the window's pill says it.
    pub route: &'static str,
    pub wireless: bool,
    /// A bit rate picked in the phone's Settings, or "Reduce quality"'s cap,
    /// in bits a second; None for the window's own, by size and route.
    pub bitrate: Option<u32>,
    pub fps: u32,
    /// Settings → Clipboard on the phone.
    pub clipboard: bool,
}

/// From a desktop window: its way to the phone, once the session's server is
/// up. An error while it is not — the phone reconnecting — and the window asks
/// again.
#[tauri::command]
pub fn dex_link(app: AppHandle, window: WebviewWindow, key: String) -> Result<DexLink, String> {
    let (url, name, wireless) = {
        let map = links().lock().unwrap();
        let link = map.get(&key).ok_or("this desktop has ended")?;
        if window.label() != link.label {
            return Err("this window belongs to another desktop".into());
        }
        (format!("ws://127.0.0.1:{}/stream/{}", link.port, link.token), link.name.clone(), link.wireless)
    };
    if !crate::scrcpy::session_has_display(&app, &key) {
        return Err("the desktop is starting".into());
    }
    // The window may be on another screen than the one the display was made
    // for — opened there, or moved while the phone was away: the density goes
    // by the screen the window is on now, as it does when it moves.
    if let Ok(scale) = window.scale_factor() {
        let (app, key) = (app.clone(), key.clone());
        thread::spawn(move || crate::scrcpy::desktop_scale_changed(&app, &key, scale));
    }
    Ok(DexLink {
        url,
        name,
        route: if wireless { "Wi-Fi" } else { "USB" },
        wireless,
        bitrate: crate::scrcpy::picked_bitrate_bps(&app),
        fps: crate::scrcpy::stored_fps(&app),
        clipboard: crate::scrcpy::clipboard_shared(&app),
    })
}

/// One drop at a time per desktop: files go onto the phone in turn.
fn drop_turn(key: &str) -> Arc<Mutex<()>> {
    static TURNS: OnceLock<Mutex<HashMap<String, Arc<Mutex<()>>>>> = OnceLock::new();
    TURNS.get_or_init(Default::default).lock().unwrap().entry(key.to_string()).or_default().clone()
}

/// Files dropped on a desktop window: into the phone's Downloads, an app
/// (.apk) installed — what scrcpy's window does with a drop. Said to
/// transfer.rs in scrcpy's own words, so the phone's transfer card follows it
/// exactly as it follows scrcpy's.
#[tauri::command]
pub fn dex_drop(app: AppHandle, window: WebviewWindow, key: String, paths: Vec<String>) -> Result<(), String> {
    let serial = {
        let map = links().lock().unwrap();
        let link = map.get(&key).ok_or("this desktop has ended")?;
        if window.label() != link.label {
            return Err("this window belongs to another desktop".into());
        }
        link.serial.clone()
    };
    let apk = |p: &str| p.to_ascii_lowercase().ends_with(".apk");
    for p in &paths {
        transfer::observe(&key, &format!("Request to {} {p}", if apk(p) { "install" } else { "push" }));
    }
    log::info!("dex [{key}] {} item(s) dropped on the window", paths.len());
    thread::spawn(move || {
        let turn = drop_turn(&key);
        let _turn = turn.lock().unwrap();
        for p in paths {
            // An hour: a large video over wireless debugging is minutes, and
            // the transfer card shows every one of them.
            let long = Duration::from_secs(3600);
            if apk(&p) {
                transfer::observe(&key, &format!("Installing {p}..."));
                let done = adb::run_adb_timeout(&app, &["-s", &serial, "install", "-r", &p], long);
                transfer::observe(&key, &if done.is_ok() { format!("{p} successfully installed") } else { format!("Failed to install {p}") });
            } else {
                transfer::observe(&key, &format!("Pushing {p}..."));
                let done = adb::run_adb_timeout(&app, &["-s", &serial, "push", &p, transfer::PUSH_TARGET], long);
                transfer::observe(
                    &key,
                    &if done.is_ok() {
                        format!("{p} successfully pushed to {}", transfer::PUSH_TARGET)
                    } else {
                        format!("Failed to push {p} to {}", transfer::PUSH_TARGET)
                    },
                );
            }
        }
    });
    Ok(())
}

/// From a desktop window that cannot show the desktop — no WebCodecs in this
/// webview, or the person asking: the same desktop in scrcpy's window. The
/// server is ended and the monitor starts scrcpy in its place.
#[tauri::command]
pub fn dex_fallback(app: AppHandle, window: WebviewWindow, key: String, reason: String) -> Result<(), String> {
    {
        let map = links().lock().unwrap();
        let link = map.get(&key).ok_or("this desktop has ended")?;
        if window.label() != link.label {
            return Err("this window belongs to another desktop".into());
        }
        link.fallback.store(true, Ordering::SeqCst);
    }
    log::warn!("dex [{key}] the desktop goes to scrcpy's window: {reason}");
    crate::scrcpy::kill_child(&app, &key);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_session_key_makes_a_label_tauri_takes() {
        let l = label_for("adb-RFCY10FRKBK-JGcERD (2)._adb-tls-connect._tcp|desktop");
        assert!(l.starts_with("dex-"));
        assert!(l.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'));
        assert_eq!(l, label_for("adb-RFCY10FRKBK-JGcERD (2)._adb-tls-connect._tcp|desktop"), "the same every time");
        assert_ne!(l, label_for("RFCY10FRKBK|desktop"));
    }

    #[test]
    fn the_key_survives_the_windows_address() {
        assert_eq!(escaped("R5CT|desktop"), "R5CT%7Cdesktop");
        assert_eq!(escaped("adb-x (2)._adb"), "adb%2Dx%20%282%29%2E%5Fadb");
    }

    #[test]
    fn the_window_goes_to_its_own_page_only() {
        let go = |u: &str| may_navigate(None, &u.parse().unwrap());
        assert!(go("tauri://localhost/index.html#dex=x"));
        assert!(go("http://tauri.localhost/index.html"));
        assert!(!go("https://example.com/"));
        assert!(!go("http://127.0.0.1:6080/"));
        assert!(!go("http://localhost:1420/index.html"), "the dev server only in development");
        let dev: Url = "http://localhost:1420".parse().unwrap();
        assert!(may_navigate(Some(&dev), &"http://localhost:1420/index.html#dex=x".parse().unwrap()));
    }

    #[test]
    fn a_window_in_front_is_its_own_sessions() {
        let key = "TEST-FRONT|desktop";
        assert!(!window_in_front(key));
        FOCUSED.store(hash_of(&label_for(key)), Ordering::SeqCst);
        assert!(window_in_front(key));
        assert!(!window_in_front("OTHER|desktop"));
        FOCUSED.store(0, Ordering::SeqCst);
        assert!(!window_in_front(key));
    }
}
