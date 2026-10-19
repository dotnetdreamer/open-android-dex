//! A paired phone's Linux desktop, in a window of its own.
//!
//! On the phone, XFCE runs behind Xvnc, and websockify serves the viewer page
//! and the VNC WebSocket on one loopback port (linux-rt.sh). This puts that
//! port on this computer's loopback through the same tunnel `ssh` uses
//! ([`dexremote::pc::Desktop`]) and shows it: the phone's own viewer page
//! (dex.html, with `ctx=pc`) in a frame, inside a page of ours that owns
//! everything around it — reaching the phone, having the desktop started,
//! saying what is happening while that takes a minute, bringing the picture
//! back after the network drops, and fullscreen.
//!
//! The viewer is a frame rather than the window's own page because it comes
//! from the phone's Linux, where anything running as root can rewrite it. The
//! window's own page is given the app's IPC; a frame from another origin is
//! not — no capability names one (see capabilities/phone-desktop.json), and
//! Tauri refuses every command from a remote origin that none does. The most a
//! rewritten page can do is draw inside its rectangle and post messages, which
//! the window checks.
//!
//! Files dropped on the window go onto the guest's Desktop over SFTP, in the
//! same tunnel ([`dexremote::sftp`]); the clipboard is the window page's own
//! business (clipboardShare.ts), through the clipboard plugin.
//!
//! Design record: doc/remote-access.md, "The desktop, in a window".

use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use dexremote::iroh::EndpointId;
use dexremote::pc::{self, Desktop, DialError};
use dexremote::proto::{DesktopSize, Refusal};
use dexremote::sftp::{self, CancellationToken, Progress, Report};
use serde::Serialize;
use tauri::async_runtime::JoinHandle;
use tauri::ipc::Channel;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

/// What a desktop window hears while it is open.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum DesktopEvent {
    /// The phone is starting the desktop, which can take a minute.
    Starting,
    /// The path to the phone: straight there or through a relay, and its
    /// round trip. The window sets the picture's quality from it.
    Path { direct: bool, rtt_ms: u64 },
}

/// How opening the desktop went.
#[derive(Debug, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Opened {
    /// Up: the viewer page, password included, to load in the frame.
    Up { url: String },
    /// Not running, and the window did not ask for it to start.
    Stopped,
    /// Not now, in a sentence. `again`: it may pass by itself — the phone
    /// offline for a moment, Linux being updated — so trying again later is
    /// worth it; otherwise it takes someone at the phone.
    Failed { message: String, again: bool },
}

/// One window's way to its phone: the tunnel, the port the viewer is served
/// on, and where path changes go. It lives as long as the window, across any
/// number of reconnects, so the frame's origin never changes under it.
struct Link {
    desktop: Arc<Desktop>,
    port: u16,
    events: Arc<Mutex<Option<Channel<DesktopEvent>>>>,
    tasks: [JoinHandle<()>; 3],
}

fn links() -> &'static Mutex<HashMap<String, Link>> {
    static LINKS: OnceLock<Mutex<HashMap<String, Link>>> = OnceLock::new();
    LINKS.get_or_init(Default::default)
}

/// One window per phone, named after it.
fn label_for(id: &str) -> String {
    format!("desktop-{}", &id[..id.len().min(16)])
}

/// The window's link, made on its first connect.
async fn link(label: &str, phone: EndpointId) -> Result<(Arc<Desktop>, u16, Arc<Mutex<Option<Channel<DesktopEvent>>>>), String> {
    let existing = |map: &HashMap<String, Link>| map.get(label).map(|l| (l.desktop.clone(), l.port, l.events.clone()));
    if let Some(found) = existing(&links().lock().unwrap()) {
        return Ok(found);
    }
    let client = crate::remote::client().await?;
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .map_err(|e| format!("cannot open a local port for the desktop: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let desktop = Desktop::new(client, phone);
    let events: Arc<Mutex<Option<Channel<DesktopEvent>>>> = Arc::default();

    let mut map = links().lock().unwrap();
    // A second connect raced this one in (React runs a window's first effect
    // twice in development): keep the first, and let this listener go.
    if let Some(found) = existing(&map) {
        return Ok(found);
    }
    let serve = {
        let desktop = desktop.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(e) = desktop.serve(listener).await {
                log::warn!("remote: the desktop's local port failed: {e}");
            }
        })
    };
    let watch = tauri::async_runtime::spawn(watch_path(desktop.clone(), events.clone()));
    // A phone whose daemon was started again — the app updated, the end of a
    // DeX session — never says the old connection died; this notices.
    let alive = tauri::async_runtime::spawn(desktop.clone().keep_alive());
    map.insert(label.to_string(), Link { desktop: desktop.clone(), port, events: events.clone(), tasks: [serve, watch, alive] });
    Ok((desktop, port, events))
}

/// The path, to the window, when it changes enough to matter: from relayed to
/// direct (hole punching gets there a few seconds after every connect) or by
/// a noticeable step in round trip.
async fn watch_path(desktop: Arc<Desktop>, events: Arc<Mutex<Option<Channel<DesktopEvent>>>>) {
    let mut last: Option<(bool, u64)> = None;
    loop {
        tokio::time::sleep(Duration::from_secs(2)).await;
        let Some((direct, rtt)) = desktop.path() else { continue };
        let moved = match last {
            Some((was_direct, was_rtt)) => was_direct != direct || was_rtt.abs_diff(rtt) >= 10,
            None => true,
        };
        if !moved {
            continue;
        }
        last = Some((direct, rtt));
        if let Some(channel) = events.lock().unwrap().as_ref() {
            let _ = channel.send(DesktopEvent::Path { direct, rtt_ms: rtt });
        }
    }
}

/// The copy each window has in flight, to cancel by — numbered, so a copy
/// that finishes after its window was closed and opened again cannot take the
/// new window's with it.
fn copies() -> &'static Mutex<HashMap<String, (u64, CancellationToken)>> {
    static COPIES: OnceLock<Mutex<HashMap<String, (u64, CancellationToken)>>> = OnceLock::new();
    COPIES.get_or_init(Default::default)
}

/// The window is gone: so is its port, and the phone is told rather than left
/// to time the connection out — it stays awake for as long as it believes
/// someone is looking. A copy still going is stopped, and takes back what it
/// was in the middle of.
pub fn window_closed(label: &str) {
    if let Some((_, copy)) = copies().lock().unwrap().remove(label) {
        copy.cancel();
    }
    let Some(link) = links().lock().unwrap().remove(label) else { return };
    for task in &link.tasks {
        task.abort();
    }
    link.desktop.close();
    log::info!("remote: closed the desktop window {label}");
}

/// The app is exiting.
pub fn close_all() {
    let labels: Vec<String> = links().lock().unwrap().keys().cloned().collect();
    for label in labels {
        window_closed(&label);
    }
}

/// The port a window's viewer is served on, for its navigation rule.
fn port_of(label: &str) -> Option<u16> {
    links().lock().unwrap().get(label).map(|l| l.port)
}

/// Where a desktop window may go: its own page — bundled, or from the dev
/// server in development — and the phone's viewer on its own port. Nowhere
/// else: not a link in the guest, and not the phone page's `dexremote:`, which
/// is LinuxActivity's to catch.
fn may_navigate(label: &str, dev: Option<&Url>, url: &Url) -> bool {
    match url.scheme() {
        // A frame before its page.
        "about" => true,
        // tauri://localhost, or http://tauri.localhost where the webview
        // cannot serve a scheme of its own.
        "tauri" => url.host_str() == Some("localhost"),
        "http" | "https" if url.host_str() == Some("tauri.localhost") => true,
        _ if dev.is_some_and(|dev| dev.origin() == url.origin()) => true,
        "http" => url.host_str() == Some("127.0.0.1") && url.port().is_some() && url.port() == port_of(label),
        _ => false,
    }
}

/// Logical size for a new desktop window: most of the screen the app's main
/// window is on, so the desktop is not shrunk much before F11 makes it exact.
fn window_size(app: &AppHandle) -> (f64, f64) {
    let monitor = app
        .get_webview_window("main")
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten());
    let Some(monitor) = monitor else { return (1280.0, 800.0) };
    let scale = monitor.scale_factor();
    let area = monitor.work_area().size;
    let (w, h) = (f64::from(area.width) / scale, f64::from(area.height) / scale);
    ((w * 0.85).round().max(800.0), (h * 0.85).round().max(520.0))
}

/// Open a phone's desktop window, or bring it to the front if it is open.
/// Async because a window built on the main thread from a synchronous command
/// deadlocks on Windows.
#[tauri::command]
pub async fn remote_open_desktop(app: AppHandle, id: String) -> Result<(), String> {
    let phone = crate::remote::find(&id)?;
    let label = label_for(&phone.id);
    if let Some(window) = app.get_webview_window(&label) {
        let _ = window.unminimize();
        let _ = window.set_focus();
        return Ok(());
    }
    let (w, h) = window_size(&app);
    let rule = label.clone();
    let dev = if tauri::is_dev() { app.config().build.dev_url.clone() } else { None };
    WebviewWindowBuilder::new(&app, &label, WebviewUrl::App(format!("index.html#desktop={}", phone.id).into()))
        .title(format!("{} — Linux", phone.name))
        .inner_size(w, h)
        .min_inner_size(640.0, 420.0)
        .center()
        .background_color(tauri::window::Color(9, 11, 16, 255))
        // Nothing kept between windows: each one serves the viewer on a new
        // port, and a cached copy of a page the phone has since updated is the
        // one thing worth less than none.
        .incognito(true)
        .on_navigation(move |url| may_navigate(&rule, dev.as_ref(), url))
        .on_new_window(|_, _| NewWindowResponse::Deny)
        .build()
        .map_err(|e| e.to_string())?;
    log::info!("remote: opened a desktop window for {}", phone.name);
    Ok(())
}

/// From a desktop window: reach the phone, and have its desktop up — started
/// at `width`×`height` (the screen's logical size) when `start` and it is not
/// running. Without `start` a stopped desktop stays stopped: that is how a
/// window coming back after a drop asks, so that a desktop someone logged out
/// of is not booted up again behind them.
#[tauri::command]
pub async fn remote_desktop_connect(
    window: WebviewWindow,
    id: String,
    width: u32,
    height: u32,
    start: bool,
    events: Channel<DesktopEvent>,
) -> Result<Opened, String> {
    // A window reaches its own phone and no other.
    if window.label() != label_for(&id) {
        return Err("this window belongs to another phone".into());
    }
    crate::remote::find(&id)?;
    let phone: EndpointId = id.parse().map_err(|_| "not a phone id".to_string())?;
    let (desktop, port, channel) = link(window.label(), phone).await?;
    *channel.lock().unwrap() = Some(events.clone());
    let size = start.then_some(DesktopSize { w: width, h: height });
    let result = desktop
        .open(size, move || {
            let _ = events.send(DesktopEvent::Starting);
        })
        .await;
    Ok(match result {
        Ok(login) => {
            log::info!("remote: the desktop is up ({})", match login.size {
                Some(s) => format!("{}x{}", s.w, s.h),
                None => "size unknown".into(),
            });
            Opened::Up { url: pc::desktop_url(port, &login) }
        }
        Err(DialError::Refused(Refusal::DesktopStopped)) => Opened::Stopped,
        Err(e) => {
            log::info!("remote: no desktop: {e}");
            let again = matches!(
                e,
                DialError::Unreachable | DialError::Other(_) | DialError::Refused(Refusal::LinuxInstalling)
            );
            Opened::Failed { message: e.to_string(), again }
        }
    })
}

/// Files dropped on a desktop window, onto the Desktop of that phone's Linux:
/// over SFTP in the tunnel, logged in with the pairing's key, never over
/// anything already there (see dexremote::sftp). `events` hears the progress
/// ten times a second. One copy per window at a time — the window queues the
/// drops that arrive during one.
#[tauri::command]
pub async fn remote_desktop_put(
    window: WebviewWindow,
    id: String,
    paths: Vec<String>,
    events: Channel<Progress>,
) -> Result<Report, String> {
    let label = window.label().to_string();
    if label != label_for(&id) {
        return Err("this window belongs to another phone".into());
    }
    let phone = crate::remote::find(&id)?;
    let seed = crate::remote::computer()?.ssh_key_seed().map_err(|e| e.to_string())?;
    let client = crate::remote::client().await?;
    let cancel = CancellationToken::new();
    let number = {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
        let mut copies = copies().lock().unwrap();
        if copies.contains_key(&label) {
            return Err("a copy is already running in this window".into());
        }
        let number = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        copies.insert(label.clone(), (number, cancel.clone()));
        number
    };
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).collect();
    log::info!("remote: copying {} dropped item(s) to the desktop on {}", paths.len(), phone.name);
    let result = sftp::put(&client, &phone, &seed, &paths, sftp::DESKTOP, |p| {
        let _ = events.send(p.clone());
    }, &cancel)
    .await;
    {
        let mut copies = copies().lock().unwrap();
        if copies.get(&label).is_some_and(|(n, _)| *n == number) {
            copies.remove(&label);
        }
    }
    match result {
        Ok(report) => {
            log::info!(
                "remote: {} {} file(s), {} bytes, to {}{}",
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
            Ok(report)
        }
        Err(e) => {
            log::warn!("remote: the copy failed: {e}");
            Err(e.to_string())
        }
    }
}

/// Stop the copy a window has in flight. What it was in the middle of is
/// taken back; what had finished stays.
#[tauri::command]
pub fn remote_desktop_put_cancel(window: WebviewWindow) {
    if let Some((_, copy)) = copies().lock().unwrap().get(window.label()) {
        copy.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_desktop_window_goes_to_its_own_page_and_its_own_viewer_only() {
        let label = "desktop-test";
        let go = |url: &str| may_navigate(label, None, &url.parse().unwrap());
        assert!(go("tauri://localhost/index.html#desktop=ab"));
        assert!(go("http://tauri.localhost/index.html"));
        assert!(go("about:blank"));
        // No link yet, so no viewer port is anyone's.
        assert!(!go("http://127.0.0.1:6080/dex.html"));
        assert!(!go("dexremote:open"));
        assert!(!go("https://example.com/"));
        assert!(!go("file:///etc/passwd"));
        assert!(!go("http://localhost:1420/index.html"), "the dev server only in development");
        let dev: Url = "http://localhost:1420".parse().unwrap();
        assert!(may_navigate(label, Some(&dev), &"http://localhost:1420/index.html#desktop=ab".parse().unwrap()));
    }

    #[test]
    fn events_read_as_the_window_expects_them() {
        let path = serde_json::to_string(&DesktopEvent::Path { direct: true, rtt_ms: 23 }).unwrap();
        assert_eq!(path, r#"{"kind":"path","direct":true,"rttMs":23}"#);
        let failed = serde_json::to_string(&Opened::Failed { message: "x".into(), again: false }).unwrap();
        assert_eq!(failed, r#"{"kind":"failed","message":"x","again":false}"#);
        let progress = serde_json::to_value(Progress { current: "a.jpg".into(), files_done: 1, ..Default::default() }).unwrap();
        assert_eq!(progress["filesDone"], 1);
        assert_eq!(progress["current"], "a.jpg");
        let report = serde_json::to_value(Report { landed: vec!["a (copy 1).jpg".into()], ..Default::default() }).unwrap();
        for key in ["dir", "landed", "files", "bytes", "links", "unreadable", "cancelled"] {
            assert!(report.get(key).is_some(), "the window reads {key}");
        }
    }
}
