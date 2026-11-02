//! A phone app pinned to this computer's taskbar or Dock, and opened from
//! there again: this app started for it if it is not running, with that app's
//! window and nothing else (doc/apps-on-pc.md, "Pinned apps").
//!
//! A pin names a phone by its `ro.serialno` and an app by its package. Not
//! the way the phone was attached — a Wi-Fi address changes with every
//! connection — and not, for long, where this app is: a portable copy is
//! moved, or replaced by the next version's beside it, and an AppImage is
//! mounted somewhere new every run. Each system pins in its own way, and this
//! app keeps the pins pointing at itself:
//!
//! - Windows pins a window. A phone app's window says how to start it again
//!   (`--phone-app <phone> <pkg>`, apps/win), Windows makes a shortcut of
//!   that — kept with the shortcuts it makes of apps' windows as they show,
//!   not only in the taskbar's own folder — and every run points the ones
//!   that are ours at itself ([`heal`]).
//! - Linux pins a desktop entry, which a dock matches a window to by its app
//!   id. Each phone app opened has one ([`entry`]), named as scrcpy's window
//!   for it is (`SDL_APP_ID`), and every run points them at itself.
//! - A Mac pins applications, never windows. A phone app kept in the Dock from
//!   its window's More menu is a small application of its own
//!   ([`keep_in_dock`]) that hands this app a URL, and LaunchServices finds
//!   this app wherever it is. A URL opens only such an app, and only from the
//!   phone it was kept for.
//!
//! A pin opens its app from the phone the app was last opened from here: a
//! new phone takes each pin over the first time that app is opened from it.
//!
//! A run a pin started is that app's window and nothing else. The main window
//! stays out of sight unless the phone takes a while ([`SHOW_MAIN_AFTER`]) —
//! then it comes up, where the phone's absence is explained — and on Windows
//! and Linux the run ends with the last window it had, as closing the app
//! would. Starting this app by hand, or clicking its own Dock icon, shows the
//! main window, and from then on the run is an ordinary one.

use std::collections::HashMap;
#[cfg(any(target_os = "linux", target_os = "macos"))]
use std::path::Path;
#[cfg(not(target_os = "macos"))]
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};

/// What a pin starts this app with: `--phone-app <phone> <pkg>`.
pub const ARG: &str = "--phone-app";

/// The scheme of the URL a Mac's pinned app opens this app with,
/// `openandroiddex://app/<phone>/<pkg>` — declared in Info.plist.
pub const SCHEME: &str = "openandroiddex";

/// This app's identifier (tauri.conf.json): a pinned app's own names begin with it.
#[cfg_attr(windows, allow(dead_code))]
const OURS: &str = "com.ccrstech.openandroiddex";

/// The window whose showing makes a run an ordinary one.
const MAIN: &str = "main";

/// How long a pinned app may take to open, in a run it started, before the
/// main window comes up to say why: about twice what a phone on the cable
/// takes from a cold start, adb's own start included.
const SHOW_MAIN_AFTER: Duration = Duration::from_secs(4);

/// How long a pin waits for its phone, and how often it looks meanwhile.
const OPEN_FOR: Duration = Duration::from_secs(45);
const OPEN_EVERY: Duration = Duration::from_millis(1500);

/// How often a pin waiting for its phone has the phone's Wi-Fi link made
/// again, where this computer has one to make: nothing else brings it back.
const WIFI_EVERY: Duration = Duration::from_secs(10);

// ── what a pin names ──

/// One phone app, as a pin names it.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Pin {
    /// The phone's `ro.serialno`. An older pin has the adb serial its window
    /// opened over instead: a cable's is the same, and a Wi-Fi one's is read
    /// back where that can be known ([`legacy_phone`]).
    pub phone: String,
    pub pkg: String,
}

impl Pin {
    /// None unless both are what they say they are: a command line or a URL
    /// can carry anything.
    pub fn new(phone: &str, pkg: &str) -> Option<Pin> {
        (is_phone(phone) && is_package(pkg)).then(|| Pin { phone: phone.to_string(), pkg: pkg.to_string() })
    }

    /// The pin a command line asks for: `--phone-app <phone> <pkg>`. Never a
    /// URL: those come from a Mac's Dock apps alone, through LaunchServices,
    /// and open those apps alone (`kept`).
    pub fn from_args(argv: &[String]) -> Option<Pin> {
        let i = argv.iter().position(|a| a == ARG)?;
        Pin::new(argv.get(i + 1)?, argv.get(i + 2)?)
    }

    /// `openandroiddex://app/<phone>/<pkg>`.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    pub fn from_url(url: &str) -> Option<Pin> {
        let (scheme, rest) = url.split_once("://")?;
        if !scheme.eq_ignore_ascii_case(SCHEME) {
            return None;
        }
        let path = rest.strip_prefix("app/")?.split(['?', '#']).next()?.trim_end_matches('/');
        let (phone, pkg) = path.split_once('/')?;
        Pin::new(&unescape(phone)?, &unescape(pkg)?)
    }

    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    pub fn url(&self) -> String {
        format!("{SCHEME}://app/{}/{}", escape(&self.phone), escape(&self.pkg))
    }

    /// What follows the program on a pin's command line.
    #[cfg_attr(not(windows), allow(dead_code))]
    fn args(&self) -> String {
        format!("{ARG} \"{}\" \"{}\"", self.phone, self.pkg)
    }
}

/// A phone as a pin names it: its `ro.serialno` — or, an older pin, an adb
/// serial, a Wi-Fi address or an mDNS name among them.
fn is_phone(s: &str) -> bool {
    !s.is_empty() && s.len() <= 128 && s.chars().all(|c| c.is_ascii_alphanumeric() || "._:-".contains(c))
}

/// An Android package: dotted names, each a letter first.
fn is_package(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 255
        && s.split('.').all(|part| {
            let mut chars = part.chars();
            chars.next().is_some_and(|c| c.is_ascii_alphabetic()) && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
}

#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn escape(s: &str) -> String {
    s.bytes()
        .map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") })
        .collect()
}

#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn unescape(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// A command line's arguments as Windows reads a pin's: spaces between
/// them, quotes around them (a pin's own never hold one).
#[cfg_attr(not(windows), allow(dead_code))]
fn split_args(line: &str) -> Vec<String> {
    let mut args = Vec::new();
    let mut arg = String::new();
    let (mut quoted, mut any) = (false, false);
    for c in line.chars() {
        match c {
            '"' => {
                quoted = !quoted;
                any = true;
            }
            c if c.is_whitespace() && !quoted => {
                if any {
                    args.push(std::mem::take(&mut arg));
                    any = false;
                }
            }
            c => {
                arg.push(c);
                any = true;
            }
        }
    }
    if any {
        args.push(arg);
    }
    args
}

/// The file that starts this app, wherever it is this run. An AppImage's own
/// file rather than the mount it runs from, which goes when it exits.
#[cfg(not(target_os = "macos"))]
pub fn launcher() -> Option<PathBuf> {
    #[cfg(target_os = "linux")]
    if let Some(image) = std::env::var_os("APPIMAGE").map(PathBuf::from).filter(|p| p.is_file()) {
        return Some(image);
    }
    std::env::current_exe().ok()
}

/// How Windows starts `pin`'s app again from its taskbar button: what a phone
/// app's window gives the taskbar to pin (apps/win).
#[cfg(windows)]
pub fn relaunch_command(pin: &Pin) -> Option<String> {
    Some(format!("\"{}\" {}", launcher()?.display(), pin.args()))
}

// ── a run a pin started ──

/// A pin started this run, and nothing has shown the main window since.
static HEADLESS: AtomicBool = AtomicBool::new(false);

/// At start, before anything else: the main window, made here rather than from
/// the config (`create: false`) so that it is made shown — as it always was,
/// at once — or, when a pin started this run, made hidden: then the app's
/// window is the run's only one, and the main window waits until it is
/// wanted. Either way, the pins are pointed at this copy of the app.
pub fn start(app: &AppHandle, argv: &[String]) -> tauri::Result<()> {
    let pinned = Pin::from_args(argv);
    if let Some(pin) = &pinned {
        HEADLESS.store(true, Ordering::SeqCst);
        log::info!("pin: this run is for {} from {} — its window, not this app's", pin.pkg, pin.phone);
    }
    if let Some(config) = app.config().app.windows.iter().find(|w| w.label == MAIN) {
        let window = tauri::WebviewWindowBuilder::from_config(app, config)?.visible(pinned.is_none()).build()?;
        // Asked for while it was being made — this app started again by hand
        // as its webview started — when it was not there to show yet.
        if pinned.is_some() && !HEADLESS.load(Ordering::SeqCst) {
            let _ = window.show();
            let _ = window.set_focus();
        }
    }
    refresh(app);
    Ok(())
}

/// The main window, shown and in front: the run is an ordinary one from now on.
pub fn show_main(app: &AppHandle) {
    HEADLESS.store(false, Ordering::SeqCst);
    if let Some(w) = app.get_webview_window(MAIN) {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// This app's own Dock icon, clicked: its main window, if a pin started the
/// run without it. Shown already, the Dock does what it always does.
#[cfg(target_os = "macos")]
pub fn reopened(app: &AppHandle) {
    let hidden = app.get_webview_window(MAIN).is_some_and(|w| !w.is_visible().unwrap_or(true));
    if hidden {
        show_main(app);
    }
}

/// The app is exiting: nothing ends it a second time.
pub fn exiting() {
    HEADLESS.store(false, Ordering::SeqCst);
}

/// A phone app's window has gone — `failed`, if it went because its app could
/// not be shown (scrcpy's, on Linux; a window of this app's says so in
/// itself). The last one of a run a pin started ends the run, as closing the
/// app would, and one that failed brings the main window up instead, where
/// the phone's state is shown. Not while another pinned app is on its way,
/// and not on a Mac, where the app stays in the Dock and its icon there shows
/// the main window.
pub fn window_gone(app: &AppHandle, failed: bool) {
    if cfg!(target_os = "macos") || !HEADLESS.load(Ordering::SeqCst) {
        return;
    }
    let open = !super::apps_windows().is_empty() || !crate::scrcpy::running(app).1.is_empty();
    if open || !pending().lock().unwrap().is_empty() {
        return;
    }
    if failed {
        log::info!("pin: the only window of a run a pin started could not show its app; the main window says why");
        show_main(app);
        return;
    }
    HEADLESS.store(false, Ordering::SeqCst);
    log::info!("pin: the last window of a run a pin started has closed; quitting");
    crate::quit::quit(app);
}

// ── opening a pinned app ──

/// The pins on their way to a window, with what each app is called. One asked
/// for again meanwhile — a second click, or a Mac's URL beside the command
/// line it started this app with — is the same one.
fn pending() -> &'static Mutex<HashMap<Pin, String>> {
    static P: OnceLock<Mutex<HashMap<Pin, String>>> = OnceLock::new();
    P.get_or_init(Default::default)
}

/// A pinned app on its way to a window, for the Apps page: a spinner on its
/// icon, and the app named while the phone it waits for is not here.
#[derive(Debug, Clone, Serialize)]
pub struct Pending {
    pub pkg: String,
    pub label: String,
}

pub fn pending_now() -> Vec<Pending> {
    let mut all: Vec<Pending> =
        pending().lock().unwrap().iter().map(|(p, label)| Pending { pkg: p.pkg.clone(), label: label.clone() }).collect();
    all.sort_by(|a, b| a.label.cmp(&b.label));
    all
}

/// Whether `pkg` from `phone` is on its way to a window by its pin: a click on
/// the Apps page then leaves it to that one, rather than open a second.
pub fn is_pending(phone: &str, pkg: &str) -> bool {
    pending().lock().unwrap().keys().any(|p| p.pkg == pkg && p.phone == phone)
}

fn pending_changed(app: &AppHandle) {
    let _ = app.emit("apps:pending", pending_now());
}

/// Open `pin`'s app as soon as its phone is here — adb may still be starting,
/// a Wi-Fi phone still on its way back — for at most [`OPEN_FOR`]. `clicked`
/// is what its first picture is timed from, and `since` says what it was.
pub fn open(app: &AppHandle, pin: Pin, clicked: Instant, since: &'static str) {
    match pending().lock().unwrap().entry(pin.clone()) {
        std::collections::hash_map::Entry::Occupied(_) => {
            log::info!("pin: {} from {} is on its way already", pin.pkg, pin.phone);
            return;
        }
        std::collections::hash_map::Entry::Vacant(v) => {
            v.insert(pin.pkg.clone());
        }
    }
    let app = app.clone();
    std::thread::spawn(move || {
        // What the page calls it while it waits: its pin's name, read off the main thread.
        let label = label_of(&app, &pin.pkg);
        if let Some(named) = pending().lock().unwrap().get_mut(&pin) {
            *named = label.clone();
        }
        pending_changed(&app);
        let done = Arc::new(AtomicBool::new(false));
        if HEADLESS.load(Ordering::SeqCst) {
            let (app, done) = (app.clone(), done.clone());
            std::thread::spawn(move || {
                std::thread::sleep(SHOW_MAIN_AFTER);
                if !done.load(Ordering::SeqCst) && HEADLESS.load(Ordering::SeqCst) {
                    log::info!("pin: no window yet after {}s; the main window says why", SHOW_MAIN_AFTER.as_secs());
                    show_main(&app);
                }
            });
        }
        let opened = open_when_here(&app, &pin, clicked, since);
        done.store(true, Ordering::SeqCst);
        pending().lock().unwrap().remove(&pin);
        pending_changed(&app);
        if let Err(message) = opened {
            log::warn!("pin: {} from {}: {message}", pin.pkg, pin.phone);
            show_main(&app);
            let _ = app.emit(
                "apps:problem",
                serde_json::json!({"serial": pin.phone, "pkg": pin.pkg, "label": label, "message": message}),
            );
        }
    });
}

fn open_when_here(app: &AppHandle, pin: &Pin, clicked: Instant, since: &'static str) -> Result<(), String> {
    let Some(wanted) = phone_of_pin(app, &pin.phone) else {
        return Err(UNKNOWN_PHONE.into());
    };
    let until = Instant::now() + OPEN_FOR;
    let mut wifi: Option<Instant> = None;
    let mut failed = None;
    loop {
        let devices = crate::adb::recent_devices(app, Duration::from_secs(1));
        // `wanted` names a phone, never a Wi-Fi address: a device attached by
        // that serial is that phone, on its cable.
        let phone = super::phone_of(&devices, &wanted);
        match super::best_serial(&devices, &phone) {
            Some(serial) => match super::open(app, &serial, &pin.pkg, String::new(), clicked, since) {
                Ok(()) => return Ok(()),
                // Its window is open, and could not be brought forward: asking
                // again would not either.
                Err(e) if super::has_window(app, &phone, &pin.pkg) => return Err(e),
                Err(e) => failed = Some(e),
            },
            // Not here yet. Over Wi-Fi, it may be waiting to be asked: adb's
            // own server is new after a restart, and knows no phone.
            None if wifi.is_none_or(|at| at.elapsed() >= WIFI_EVERY) => {
                wifi = Some(Instant::now());
                let (app, phone) = (app.clone(), phone.clone());
                std::thread::spawn(move || {
                    let back = crate::wireless::reconnect_phone(&app, &phone);
                    if !back.is_empty() {
                        log::info!("pin: {phone} is back over Wi-Fi, as {}", back.join(", "));
                    }
                });
            }
            None => {}
        }
        if Instant::now() >= until {
            return Err(failed.unwrap_or_else(|| not_here(&devices)));
        }
        std::thread::sleep(OPEN_EVERY);
    }
}

/// Why a pin cannot open its app: it is an older one, made from a window over
/// Wi-Fi, and names an address no phone this computer remembers was at.
const UNKNOWN_PHONE: &str = "This pin was made by an older version over Wi\u{2011}Fi and does not say which phone it is for. Open the app once from the Apps page: its pin opens it from that phone from then on";

/// The phone a pin names: a phone's own serial is itself, and an older pin's
/// adb serial is read back where that can be known — see [`legacy_phone`].
fn phone_of_pin(app: &AppHandle, token: &str) -> Option<String> {
    legacy_phone(token, |address| {
        crate::wireless::wireless_known(app.clone())
            .unwrap_or_default()
            .into_iter()
            .find(|d| d.address == address)
            .and_then(|d| d.hardware_serial)
    })
}

/// The phone an adb serial was: a cable's serial is the phone's own, and an
/// mDNS name carries it. A Wi-Fi address is only the phone `remembered` there,
/// never one attached at it now — another phone may have that address since,
/// and its app would be opened instead. Otherwise None: the pin cannot say.
fn legacy_phone(token: &str, remembered: impl FnOnce(&str) -> Option<String>) -> Option<String> {
    if super::on_cable(token) {
        return Some(token.to_string());
    }
    if token.contains("._adb-tls-connect.") {
        return Some(super::phone_of(&[], token)).filter(|phone| phone != token);
    }
    remembered(token)
}

/// Why a pinned app did not open when its phone never came.
fn not_here(devices: &[crate::adb::DeviceInfo]) -> String {
    if devices.iter().any(|d| d.state == "unauthorized") {
        "Your phone is asking whether to allow USB debugging. Tap Allow on it, then open the app again".into()
    } else {
        "Your phone isn't connected. Plug it in, or connect it over Wi\u{2011}Fi, then open the app again".into()
    }
}

/// What `pkg` is called before its phone is here to say: as its pin names it
/// (the taskbar shortcut, the desktop entry, the app in the Dock), else as the
/// Apps page last listed it.
pub(crate) fn label_of(app: &AppHandle, pkg: &str) -> String {
    pinned_label(app, pkg)
        .or_else(|| super::rows().lock().unwrap().values().find_map(|t| t.get(pkg).map(|r| r.label.clone())))
        .unwrap_or_else(|| pkg.to_string())
}

// ── keeping the pins pointing here ──

/// Whether this run changes the pins, rather than only saying what it would:
/// not a development build's, whose pins would outlive it, unless asked to.
#[cfg(not(target_os = "macos"))]
fn writes() -> bool {
    !cfg!(debug_assertions) || std::env::var_os("OADX_PINS").is_some()
}

/// Every pin of ours pointed at this copy of the app, off the main thread: a
/// portable copy moved or replaced, the app installed again elsewhere, an
/// AppImage mounted somewhere new.
fn refresh(app: &AppHandle) {
    #[cfg(windows)]
    {
        let app = app.clone();
        std::thread::spawn(move || heal(&app, None));
    }
    #[cfg(target_os = "linux")]
    if writes() {
        let app = app.clone();
        std::thread::spawn(move || refresh_entries(&app));
    }
    // A Mac's pinned apps hand their URL to LaunchServices, which finds this
    // app wherever it is: nothing to point.
    #[cfg(target_os = "macos")]
    let _ = app;
}

/// `pin`'s app has a window here: its pin opens it from this phone from now on.
#[cfg(any(windows, target_os = "macos"))]
pub fn opened(app: &AppHandle, pin: &Pin) {
    let (app, pin) = (app.clone(), pin.clone());
    std::thread::spawn(move || {
        #[cfg(windows)]
        heal(&app, Some(&pin));
        #[cfg(target_os = "macos")]
        follow_shim(&app, &pin);
    });
}

// ── Windows: the taskbar's shortcuts ──

/// Where Windows keeps what is pinned, under the roaming AppData folder: the
/// taskbar's own shortcuts (`TaskBar`), and the ones it makes of an app's
/// windows as they show (`ImplicitAppShortcuts\<hash>\`), which a window
/// pinned from the taskbar is — made once, from the first window of the app,
/// and never changed by a window of another copy of this app after it.
#[cfg(windows)]
const PINNED: &str = r"Microsoft\Internet Explorer\Quick Launch\User Pinned";

/// What each pinned phone app's shortcut is called, by package: what the app is
/// called before its phone is here to say.
#[cfg(windows)]
fn labels() -> &'static Mutex<HashMap<String, String>> {
    static L: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    L.get_or_init(Default::default)
}

/// Whether this run has looked at the taskbar's shortcuts yet: until it has,
/// a pinned app's name is still to be read.
#[cfg(windows)]
fn looked() -> &'static (Mutex<bool>, std::sync::Condvar) {
    static L: OnceLock<(Mutex<bool>, std::sync::Condvar)> = OnceLock::new();
    L.get_or_init(Default::default)
}

#[cfg(windows)]
fn pinned_label(_app: &AppHandle, pkg: &str) -> Option<String> {
    // Read as this run starts, off the main thread: a moment's wait for it, at most.
    let (done, bell) = looked();
    let _ = bell.wait_timeout_while(done.lock().unwrap(), Duration::from_secs(1), |done| !*done);
    labels().lock().unwrap().get(pkg).cloned()
}

/// Each taskbar shortcut of ours — one with a phone app's AppUserModelID —
/// pointed at this copy of the app; at the phone its app was last opened from
/// (`opened`), else the phone an older shortcut's adb serial was; and at an
/// icon that is still there. Squirrel and Chrome keep their pins working
/// through updates the same way.
#[cfg(windows)]
fn heal(app: &AppHandle, opened: Option<&Pin>) {
    // One at a time: a run's first look and windows opening together would
    // otherwise rewrite the same shortcut at once.
    static ONE: Mutex<()> = Mutex::new(());
    let _one = ONE.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    heal_links(app, opened);
    let (done, bell) = looked();
    *done.lock().unwrap() = true;
    bell.notify_all();
}

#[cfg(windows)]
fn heal_links(app: &AppHandle, opened: Option<&Pin>) {
    use windows::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED};
    let Some(exe) = launcher() else { return };
    let Some(pinned) = std::env::var_os("APPDATA").map(|d| PathBuf::from(d).join(PINNED)) else { return };
    let entries = |dir: PathBuf| -> Vec<PathBuf> {
        std::fs::read_dir(dir).map(|r| r.filter_map(|e| Some(e.ok()?.path())).collect()).unwrap_or_default()
    };
    let mut dirs = vec![pinned.join("TaskBar")];
    dirs.extend(entries(pinned.join("ImplicitAppShortcuts")).into_iter().filter(|p| p.is_dir()));
    let links: Vec<PathBuf> = dirs
        .into_iter()
        .flat_map(entries)
        .filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("lnk")))
        .collect();
    if links.is_empty() {
        return;
    }
    let write = writes();
    let phone_for = |pin: &Pin| match opened {
        Some(o) if o.pkg == pin.pkg => o.phone.clone(),
        // An older pin whose phone cannot be known is left as it is: its
        // click says why, and opening its app once re-points it.
        _ => phone_of_pin(app, &pin.phone).unwrap_or_else(|| pin.phone.clone()),
    };
    let icon_for = |phone: &str, pin: &Pin| {
        super::icons::newest(app, phone, &pin.pkg)
            .and_then(|(updated, png)| super::icons::ico_file(app, phone, &pin.pkg, updated, &png))
    };
    let com = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) }.is_ok();
    for link in &links {
        match unsafe { heal_link(link, &exe, write, &phone_for, &icon_for) } {
            Ok(Some(healed)) => {
                labels().lock().unwrap().insert(healed.pkg, healed.name.clone());
                if healed.changes.is_empty() {
                    continue;
                }
                let changes = healed.changes.join(", ");
                if write {
                    log::info!("pin: \"{}\" on the taskbar opens with {changes} now", healed.name);
                } else {
                    log::debug!(
                        "pin: \"{}\" on the taskbar would open with {changes} — not changed by a development build (OADX_PINS=1 lets it)",
                        healed.name
                    );
                }
            }
            Ok(None) => {}
            Err(e) => log::debug!("pin: {}: {e}", link.display()),
        }
    }
    if com {
        unsafe { CoUninitialize() };
    }
}

/// One of the taskbar's shortcuts that is ours, looked at: the app it opens,
/// the name the taskbar shows, and what was changed in it (`write`) or would be.
#[cfg(windows)]
struct Healed {
    pkg: String,
    name: String,
    changes: Vec<String>,
}

/// The shortcut at `path`, if it is a phone app's, pointed at `exe`, at the
/// phone `phone_for` says, and at the icon `icon_for` makes if its own has
/// gone. None for a shortcut that is not ours.
#[cfg(windows)]
unsafe fn heal_link(
    path: &std::path::Path,
    exe: &std::path::Path,
    write: bool,
    phone_for: &dyn Fn(&Pin) -> String,
    icon_for: &dyn Fn(&str, &Pin) -> Option<PathBuf>,
) -> windows::core::Result<Option<Healed>> {
    use windows::core::{Interface, HSTRING, PCWSTR};
    use windows::Win32::System::Com::{CoCreateInstance, IPersistFile, CLSCTX_INPROC_SERVER, STGM_READ, STGM_READWRITE};
    use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
    use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};

    let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
    let file: IPersistFile = link.cast()?;
    file.Load(&HSTRING::from(path), if write { STGM_READWRITE } else { STGM_READ })?;
    let store: IPropertyStore = link.cast()?;
    let ours = super::app_user_model_id("");
    if !app_id_of(&store).is_some_and(|id| id.starts_with(&ours)) {
        return Ok(None);
    }
    let mut args = vec![0u16; 2048];
    link.GetArguments(&mut args)?;
    let Some(pin) = Pin::from_args(&split_args(&wide(&args))) else { return Ok(None) };
    let name = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();

    let mut changes = Vec::new();
    if !target_of(&link).is_some_and(|t| same_file(&t, exe)) {
        changes.push(format!("this copy of the app ({})", exe.display()));
        if write {
            link.SetPath(&HSTRING::from(exe))?;
            if let Some(dir) = exe.parent() {
                link.SetWorkingDirectory(&HSTRING::from(dir))?;
            }
        }
    }
    let phone = phone_for(&pin);
    if phone != pin.phone {
        changes.push(format!("the phone {phone}"));
        if write {
            let to = Pin { phone: phone.clone(), pkg: pin.pkg.clone() };
            link.SetArguments(&HSTRING::from(to.args()))?;
        }
    }
    let mut icon = vec![0u16; 1024];
    let mut index = 0;
    let _ = link.GetIconLocation(&mut icon, &mut index);
    let icon = wide(&icon);
    if icon.is_empty() || !std::path::Path::new(&icon).is_file() {
        if let Some(ico) = icon_for(&phone, &pin) {
            changes.push("its icon".to_string());
            if write {
                link.SetIconLocation(&HSTRING::from(ico.as_path()), 0)?;
            }
        }
    }
    if write && !changes.is_empty() {
        file.Save(PCWSTR::null(), true)?;
    }
    Ok(Some(Healed { pkg: pin.pkg, name, changes }))
}

/// A shortcut's AppUserModelID, which Windows copies from the window it was pinned from.
#[cfg(windows)]
unsafe fn app_id_of(store: &windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore) -> Option<String> {
    use windows::Win32::Storage::EnhancedStorage::PKEY_AppUserModel_ID;
    use windows::Win32::System::Com::StructuredStorage::PropVariantClear;
    use windows::Win32::System::Variant::VT_LPWSTR;
    let mut value = store.GetValue(&PKEY_AppUserModel_ID).ok()?;
    let id = {
        let inner = &value.Anonymous.Anonymous;
        let text = inner.Anonymous.pwszVal;
        (inner.vt == VT_LPWSTR && !text.is_null()).then(|| text.to_string().ok()).flatten()
    };
    let _ = PropVariantClear(&mut value);
    id
}

/// The file a shortcut starts.
#[cfg(windows)]
unsafe fn target_of(link: &windows::Win32::UI::Shell::IShellLinkW) -> Option<PathBuf> {
    use windows::Win32::System::Com::CoTaskMemFree;
    use windows::Win32::UI::Shell::{SHGetPathFromIDListEx, GPFIDL_DEFAULT};
    let items = link.GetIDList().ok()?;
    if items.is_null() {
        return None;
    }
    let mut path = vec![0u16; 1024];
    let found = SHGetPathFromIDListEx(items, &mut path, GPFIDL_DEFAULT).as_bool();
    CoTaskMemFree(Some(items as *const core::ffi::c_void));
    found.then(|| PathBuf::from(wide(&path)))
}

#[cfg(windows)]
fn wide(text: &[u16]) -> String {
    let len = text.iter().position(|&c| c == 0).unwrap_or(text.len());
    String::from_utf16_lossy(&text[..len])
}

#[cfg(windows)]
fn same_file(a: &std::path::Path, b: &std::path::Path) -> bool {
    let plain = |p: &std::path::Path| p.to_string_lossy().trim_start_matches(r"\\?\").replace('/', "\\").to_lowercase();
    plain(a) == plain(b)
}

// ── Linux: a desktop entry per phone app ──

/// What a phone app's scrcpy window is known by on a Linux desktop — its app id
/// on Wayland, its WM_CLASS on X11 — and the name of its desktop entry, which is
/// how a dock matches the two. One per app, as a Windows taskbar button is.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn app_id(pkg: &str) -> String {
    format!("{OURS}.app.{pkg}")
}

/// What scrcpy is told for a phone app's window on Linux: its app id, and the
/// directory its icon is in (`SCRCPY_ICON_DIR`) if this computer has the app's.
#[cfg(target_os = "linux")]
pub struct Entry {
    pub app_id: String,
    pub icon_dir: Option<PathBuf>,
}

/// `pin`'s app about to open in scrcpy's window: its desktop entry written, so
/// a dock shows the window as the app, with its name and icon, and can pin it.
#[cfg(target_os = "linux")]
pub fn entry(app: &AppHandle, pin: &Pin, label: &str) -> Entry {
    let id = app_id(&pin.pkg);
    let icon_dir = icon_dir(app, pin);
    let icon = icon_dir.as_ref().map(|d| d.join("scrcpy.png"));
    // Not by a development build, whose entry would outlive it: its window has
    // the app id all the same, which an installed copy's entry matches.
    if !writes() {
        log::debug!("pin: {}'s desktop entry not written by a development build (OADX_PINS=1 lets it)", pin.pkg);
    } else if let Err(e) = write_entry(app, &id, pin, label, icon.as_deref()) {
        log::warn!("pin: {}'s desktop entry: {e}", pin.pkg);
    }
    Entry { app_id: id, icon_dir }
}

#[cfg(target_os = "linux")]
fn entries_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().data_dir().map_err(|e| e.to_string())?.join("applications");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

#[cfg(target_os = "linux")]
fn write_entry(app: &AppHandle, id: &str, pin: &Pin, label: &str, icon: Option<&Path>) -> Result<(), String> {
    let exe = launcher().ok_or("no path to this app")?;
    let path = entries_dir(app)?.join(format!("{id}.desktop"));
    let had = std::fs::read_to_string(&path).ok();
    let field = |key: &str| had.as_deref().and_then(|t| desktop::field(t, key));
    // Opened before its phone named it (a pin, at start): the name it had.
    let label = field("Name").filter(|_| label == pin.pkg).unwrap_or_else(|| label.to_string());
    let icon = icon.map(Path::to_path_buf).or_else(|| field("Icon").map(PathBuf::from).filter(|p| p.is_file()));
    let text = desktop::entry(id, &label, &exe, pin, icon.as_deref());
    if had.as_deref() != Some(text.as_str()) {
        std::fs::write(&path, text).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// The app's icon where scrcpy reads a window's icon from: a directory holding
/// `scrcpy.png`, and `disconnected.png` for a phone that dropped.
#[cfg(target_os = "linux")]
fn icon_dir(app: &AppHandle, pin: &Pin) -> Option<PathBuf> {
    let (_, png) = super::icons::newest(app, &pin.phone, &pin.pkg)?;
    let dir = app.path().app_data_dir().ok()?.join("pinned").join(&pin.pkg);
    std::fs::create_dir_all(&dir).ok()?;
    let icon = dir.join("scrcpy.png");
    if std::fs::read(&icon).ok().as_deref() != Some(png.as_slice()) {
        std::fs::write(&icon, &png).ok()?;
    }
    let gone = dir.join("disconnected.png");
    if !gone.is_file() {
        if let Ok(ours) = crate::adb::resources_dir(app) {
            let _ = std::fs::copy(ours.join("icon").join("disconnected.png"), &gone);
        }
    }
    Some(dir)
}

#[cfg(target_os = "linux")]
fn pinned_label(app: &AppHandle, pkg: &str) -> Option<String> {
    let text = std::fs::read_to_string(entries_dir(app).ok()?.join(format!("{}.desktop", app_id(pkg)))).ok()?;
    desktop::field(&text, "Name").filter(|name| name != pkg)
}

/// Each phone app's desktop entry starting this copy of the app.
#[cfg(target_os = "linux")]
fn refresh_entries(app: &AppHandle) {
    let (Some(exe), Ok(dir)) = (launcher(), entries_dir(app)) else { return };
    let Ok(read) = std::fs::read_dir(&dir) else { return };
    let ours = app_id("");
    for path in read.filter_map(|e| Some(e.ok()?.path())) {
        let named = path.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with(&ours) && n.ends_with(".desktop"));
        let Some(text) = named.then(|| std::fs::read_to_string(&path).ok()).flatten() else { continue };
        let field = |key: &str| desktop::field(&text, key);
        let Some(pin) = field(desktop::PHONE).zip(field(desktop::PACKAGE)).and_then(|(phone, pkg)| Pin::new(&phone, &pkg)) else {
            continue;
        };
        let label = field("Name").unwrap_or_else(|| pin.pkg.clone());
        let fresh = desktop::entry(&app_id(&pin.pkg), &label, &exe, &pin, field("Icon").map(PathBuf::from).as_deref());
        if fresh != text && std::fs::write(&path, fresh).is_ok() {
            log::info!("pin: {label}'s desktop entry starts {} now", exe.display());
        }
    }
}

/// Desktop entries as the freedesktop.org specification writes them.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
mod desktop {
    use super::{Pin, ARG};
    use std::path::Path;

    /// The keys an entry of ours names its phone app by.
    pub const PHONE: &str = "X-OpenAndroidDeX-Phone";
    pub const PACKAGE: &str = "X-OpenAndroidDeX-Package";

    /// `pin`'s entry: `id` is the window's app id, which a dock matches it by
    /// (`StartupWMClass` on X11, the file's name on Wayland). Out of the app
    /// menus — it is the window's, and the Apps page lists what the phone has —
    /// but a dock pins it all the same.
    pub fn entry(id: &str, label: &str, exe: &Path, pin: &Pin, icon: Option<&Path>) -> String {
        let exe = exe.to_string_lossy();
        let exec = [&*exe, ARG, pin.phone.as_str(), pin.pkg.as_str()].map(exec_arg).join(" ");
        let mut text = String::from("[Desktop Entry]\nType=Application\nVersion=1.0\n");
        text += &format!("Name={}\n", value(label));
        text += &format!("Comment={}\n", value(&format!("{label} from your phone, in a window of its own")));
        text += &format!("Exec={}\n", value(&exec));
        if let Some(icon) = icon {
            text += &format!("Icon={}\n", value(&icon.to_string_lossy()));
        }
        text += &format!("StartupWMClass={id}\nStartupNotify=false\nTerminal=false\nNoDisplay=true\n");
        text += &format!("{PHONE}={}\n{PACKAGE}={}\n", pin.phone, pin.pkg);
        text
    }

    /// One argument of an `Exec` line: quoted if it holds a character the
    /// specification reserves, and every `%` doubled — a lone one is a field code.
    fn exec_arg(arg: &str) -> String {
        const RESERVED: &str = " \t\n\"'\\><~|&;$*?#()`";
        let arg = arg.replace('%', "%%");
        if !arg.is_empty() && !arg.chars().any(|c| RESERVED.contains(c)) {
            return arg;
        }
        let mut quoted = String::from("\"");
        for c in arg.chars() {
            if matches!(c, '"' | '`' | '$' | '\\') {
                quoted.push('\\');
            }
            quoted.push(c);
        }
        quoted.push('"');
        quoted
    }

    /// A value, escaped as every string in an entry is — before, for `Exec`,
    /// its own quoting is read.
    fn value(s: &str) -> String {
        s.replace('\\', "\\\\").replace('\n', "\\n").replace('\t', "\\t").replace('\r', "\\r")
    }

    /// `key`'s value in the entry's own group.
    pub fn field(text: &str, key: &str) -> Option<String> {
        let mut ours = false;
        for line in text.lines().map(str::trim_start) {
            if line.starts_with('[') {
                ours = line.trim_end() == "[Desktop Entry]";
            } else if let Some((k, v)) = line.split_once('=').filter(|_| ours) {
                if k.trim_end() == key {
                    return Some(unescape(v.trim_start()));
                }
            }
        }
        None
    }

    fn unescape(v: &str) -> String {
        let mut out = String::with_capacity(v.len());
        let mut chars = v.chars();
        while let Some(c) = chars.next() {
            if c != '\\' {
                out.push(c);
                continue;
            }
            match chars.next() {
                Some('s') => out.push(' '),
                Some('n') => out.push('\n'),
                Some('t') => out.push('\t'),
                Some('r') => out.push('\r'),
                Some('\\') => out.push('\\'),
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => out.push('\\'),
            }
        }
        out
    }
}

// ── a Mac: a small application per phone app kept in the Dock ──

/// Where the phone apps kept in the Dock are, under the home folder: the
/// person's own Applications, where Spotlight finds them too.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
const SHIMS: &str = "Applications/Open Android DeX Apps";

/// One phone app's folder there, by package: so its Dock tile still finds it
/// when the app is renamed. Inside, the app itself, named as it was then.
#[cfg(target_os = "macos")]
fn shim_home(app: &AppHandle, pkg: &str) -> Option<std::path::PathBuf> {
    Some(app.path().home_dir().ok()?.join(SHIMS).join(pkg))
}

/// The application kept in the Dock for `pkg`, if there is one.
#[cfg(target_os = "macos")]
fn shim_of(app: &AppHandle, pkg: &str) -> Option<std::path::PathBuf> {
    std::fs::read_dir(shim_home(app, pkg)?)
        .ok()?
        .filter_map(|e| Some(e.ok()?.path()))
        .find(|p| p.extension().is_some_and(|x| x == "app"))
}

#[cfg(target_os = "macos")]
fn pinned_label(app: &AppHandle, pkg: &str) -> Option<String> {
    Some(shim_of(app, pkg)?.file_stem()?.to_string_lossy().into_owned())
}

/// The app kept in the Dock for `pkg`, as it was kept — its phone, read from
/// its own executable. All a URL may open: the scheme is there for these apps,
/// and a web page could name any app and any phone in one.
#[cfg(target_os = "macos")]
pub fn kept(app: &AppHandle, pkg: &str) -> Option<Pin> {
    let exe = shim_of(app, pkg)?.join("Contents").join("MacOS").join(dock::EXE);
    dock::script_pin(&std::fs::read_to_string(exe).ok()?).filter(|p| p.pkg == pkg)
}

/// The app's icon, if the phone gave none at a Dock's size: this app's own.
#[cfg(target_os = "macos")]
const OUR_ICNS: &[u8] = include_bytes!("../../icons/icon.icns");

/// More's "Keep in Dock": `pin`'s app as an application of its own in the
/// Dock, which opens it here from there — this app started for it if it is
/// not running. `icon` is the app's at a Dock's size, when the phone gave it.
/// What to tell the person, either way.
#[cfg(target_os = "macos")]
pub fn keep_in_dock(app: &AppHandle, pin: &Pin, label: &str, icon: Option<&[u8]>) -> Result<String, String> {
    use std::process::Command;
    let shim = match shim_of(app, &pin.pkg) {
        Some(shim) => shim,
        None => shim_home(app, &pin.pkg).ok_or("no home folder")?.join(format!("{}.app", dock::file_name(label, &pin.pkg))),
    };
    write_shim(&shim, pin, label, icon).map_err(|e| format!("{label} couldn't be put in the Dock: {e}"))?;
    let path = shim.to_string_lossy().into_owned();
    let tiles = Command::new("/usr/bin/defaults")
        .args(["read", "com.apple.dock", "persistent-apps"])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    if dock::mentions(&tiles, &dock::bundle_id(&pin.pkg), &path) {
        return Ok(format!("{label} is in the Dock"));
    }
    let added = Command::new("/usr/bin/defaults")
        .args(["write", "com.apple.dock", "persistent-apps", "-array-add", &dock::tile(&path)])
        .status();
    if !added.is_ok_and(|s| s.success()) {
        let _ = Command::new("/usr/bin/open").arg("-R").arg(&shim).status();
        return Err(format!("Drag {label} from this Finder window to the Dock to keep it there"));
    }
    // The Dock reads its tiles as it starts: started again, it has the new one.
    let _ = Command::new("/usr/bin/killall").arg("Dock").status();
    log::info!("pin: {} from {} kept in the Dock, as {path}", pin.pkg, pin.phone);
    Ok(format!("{label} is in the Dock. Click it there to open it, even with this app closed"))
}

#[cfg(target_os = "macos")]
fn write_shim(shim: &Path, pin: &Pin, label: &str, icon: Option<&[u8]>) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let contents = shim.join("Contents");
    std::fs::create_dir_all(contents.join("MacOS"))?;
    std::fs::create_dir_all(contents.join("Resources"))?;
    std::fs::write(contents.join("Info.plist"), dock::plist(label, &pin.pkg))?;
    let exe = contents.join("MacOS").join(dock::EXE);
    std::fs::write(&exe, dock::script(pin))?;
    std::fs::set_permissions(&exe, std::fs::Permissions::from_mode(0o755))?;
    let icns = icon.and_then(dock::icns).unwrap_or_else(|| OUR_ICNS.to_vec());
    std::fs::write(contents.join("Resources").join("AppIcon.icns"), icns)
}

/// The app kept in the Dock for `pin`'s app opens it from `pin`'s phone from now on.
#[cfg(target_os = "macos")]
fn follow_shim(app: &AppHandle, pin: &Pin) {
    let Some(shim) = shim_of(app, &pin.pkg) else { return };
    let exe = shim.join("Contents").join("MacOS").join(dock::EXE);
    let fresh = dock::script(pin);
    if std::fs::read_to_string(&exe).ok().as_deref() != Some(fresh.as_str()) && std::fs::write(&exe, fresh).is_ok() {
        log::info!("pin: {} in the Dock opens from {} now", pin.pkg, pin.phone);
    }
}

/// What a phone app kept in the Dock is made of.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
mod dock {
    use super::{Pin, ARG, OURS};

    /// The application's executable: a script that hands its app to this one.
    pub const EXE: &str = "launch";

    /// Its bundle identifier, of the characters one allows.
    pub fn bundle_id(pkg: &str) -> String {
        format!("{OURS}.app.{}", pkg.replace('_', "-"))
    }

    /// Its file's name: the app's, less what a Mac file name cannot hold.
    pub fn file_name(label: &str, pkg: &str) -> String {
        let name: String = label.chars().filter(|c| !c.is_control() && !matches!(c, '/' | ':')).take(80).collect();
        let name = name.trim().trim_start_matches('.').trim();
        if name.is_empty() {
            pkg.to_string()
        } else {
            name.to_string()
        }
    }

    pub fn plist(label: &str, pkg: &str) -> String {
        let pairs = [
            ("CFBundleDevelopmentRegion", "en".to_string()),
            ("CFBundleDisplayName", xml(label)),
            ("CFBundleExecutable", EXE.to_string()),
            ("CFBundleIconFile", "AppIcon".to_string()),
            ("CFBundleIdentifier", bundle_id(pkg)),
            ("CFBundleInfoDictionaryVersion", "6.0".to_string()),
            ("CFBundleName", xml(label)),
            ("CFBundlePackageType", "APPL".to_string()),
            ("CFBundleShortVersionString", "1.0".to_string()),
            ("CFBundleVersion", "1".to_string()),
            ("LSMinimumSystemVersion", "11.0".to_string()),
            ("OADXPackage", xml(pkg)),
        ];
        let mut text = String::from(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
             <!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n\
             <plist version=\"1.0\">\n<dict>\n",
        );
        for (key, value) in pairs {
            text += &format!("\t<key>{key}</key>\n\t<string>{value}</string>\n");
        }
        text += "\t<key>NSHighResolutionCapable</key>\n\t<true/>\n</dict>\n</plist>\n";
        text
    }

    /// The executable. The URL reaches this app if it is running — it is
    /// declared in this app's Info.plist — and the arguments start it for the
    /// app if it is not; started so, it hears both, and opens the app once.
    pub fn script(pin: &Pin) -> String {
        format!(
            "#!/bin/sh\n\
             # A phone app kept in the Dock by Open Android DeX, which opens it in a\n\
             # window of its own (apps/pin.rs in its source).\n\
             /usr/bin/open -b {OURS} '{url}' --args {ARG} '{phone}' '{pkg}' && exit 0\n\
             /usr/bin/osascript -e 'display alert \"Open Android DeX is not on this Mac\" message \"Install it again to open your phone apps from the Dock.\"' >/dev/null 2>&1\n\
             exit 1\n",
            url = pin.url(),
            phone = pin.phone,
            pkg = pin.pkg,
        )
    }

    /// The pin a [`script`] opens: what follows its `--phone-app`, quoted.
    pub fn script_pin(script: &str) -> Option<Pin> {
        let (_, rest) = script.split_once(&format!("{ARG} '"))?;
        let (phone, rest) = rest.split_once("' '")?;
        let (pkg, _) = rest.split_once('\'')?;
        Pin::new(phone, pkg)
    }

    /// A Dock tile for the application at `path`, as `defaults` adds one.
    pub fn tile(path: &str) -> String {
        format!(
            "<dict><key>tile-data</key><dict><key>file-data</key><dict>\
             <key>_CFURLString</key><string>{}</string><key>_CFURLStringType</key><integer>0</integer>\
             </dict></dict><key>tile-type</key><string>file-tile</string></dict>",
            xml(path)
        )
    }

    /// Whether the Dock's tiles, as `defaults read` prints them, hold the
    /// application: by its identifier once the Dock has read it, by its path
    /// — either way the Dock writes one — before.
    pub fn mentions(tiles: &str, bundle_id: &str, path: &str) -> bool {
        tiles.contains(&format!("\"{bundle_id}\""))
            || tiles.contains(&format!("= {bundle_id};"))
            || tiles.contains(path)
            || tiles.contains(&file_url(path))
    }

    fn file_url(path: &str) -> String {
        let escaped: String = path
            .bytes()
            .map(|b| if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") })
            .collect();
        format!("file://{escaped}")
    }

    fn xml(s: &str) -> String {
        s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
    }

    /// An .icns of the one PNG, under each type its size is — all a Mac reads
    /// from one now. None for a size no type is: macOS would not draw it.
    pub fn icns(png: &[u8]) -> Option<Vec<u8>> {
        let types: &[&[u8; 4]] = match png_side(png)? {
            16 => &[b"icp4"],
            32 => &[b"icp5", b"ic11"],
            64 => &[b"icp6", b"ic12"],
            128 => &[b"ic07"],
            256 => &[b"ic08", b"ic13"],
            512 => &[b"ic09", b"ic14"],
            1024 => &[b"ic10"],
            _ => return None,
        };
        let len = 8 + types.len() * (8 + png.len());
        let mut out = Vec::with_capacity(len);
        out.extend_from_slice(b"icns");
        out.extend_from_slice(&(len as u32).to_be_bytes());
        for kind in types {
            out.extend_from_slice(*kind);
            out.extend_from_slice(&((8 + png.len()) as u32).to_be_bytes());
            out.extend_from_slice(png);
        }
        Some(out)
    }

    /// A square PNG's side, from its header.
    fn png_side(png: &[u8]) -> Option<u32> {
        if png.len() < 24 || !png.starts_with(b"\x89PNG\r\n\x1a\n") || &png[12..16] != b"IHDR" {
            return None;
        }
        let w = u32::from_be_bytes(png[16..20].try_into().ok()?);
        let h = u32::from_be_bytes(png[20..24].try_into().ok()?);
        (w == h).then_some(w)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn argv(args: &[&str]) -> Vec<String> {
        args.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn a_pin_is_read_off_its_command_line() {
        let pin = Pin::from_args(&argv(&["C:\\x\\oadx.exe", "--phone-app", "R58T51N3G2X", "com.whatsapp"])).unwrap();
        assert_eq!(pin, Pin { phone: "R58T51N3G2X".into(), pkg: "com.whatsapp".into() });
        // Older pins: the adb serial the window opened over, Wi-Fi ones included.
        for phone in ["192.168.1.20:37159", "adb-R58T51N3G2X-vWgJpq._adb-tls-connect._tcp", "emulator-5554"] {
            assert_eq!(Pin::from_args(&argv(&["oadx", "--phone-app", phone, "com.whatsapp"])).unwrap().phone, phone);
        }
        assert_eq!(Pin::from_args(&argv(&["oadx"])), None);
        assert_eq!(Pin::from_args(&argv(&["oadx", "--phone-app", "R58T51N3G2X"])), None);
    }

    #[test]
    fn a_pin_carries_nothing_but_a_phone_and_a_package() {
        assert!(Pin::new("R58T51N3G2X", "com.whatsapp").is_some());
        assert!(Pin::new("R58T51N3G2X", "com.google.android.apps.maps").is_some());
        assert!(Pin::new("R58T51N3G2X", "com.my_app.v2").is_some());
        assert!(Pin::new("", "com.whatsapp").is_none());
        assert!(Pin::new("R58T51N3G2X", "").is_none());
        assert!(Pin::new("R58T51N3G2X", "linux:firefox").is_none(), "a Linux app's window is not pinned");
        assert!(Pin::new("R58T51N3G2X", "com..whatsapp").is_none());
        assert!(Pin::new("R58T51N3G2X", "com.2fa").is_none());
        assert!(Pin::new("R58T51N3G2X\" --evil", "com.whatsapp").is_none());
        assert!(Pin::new("R58T51N3G2X", "com.whatsapp' && rm -rf ~ '").is_none());
        assert!(Pin::new(&"R".repeat(129), "com.whatsapp").is_none());
    }

    #[test]
    fn a_pin_goes_through_its_url_and_back() {
        let pin = Pin::new("192.168.1.20:37159", "com.my_app").unwrap();
        let url = pin.url();
        assert_eq!(url, "openandroiddex://app/192.168.1.20%3A37159/com.my_app");
        assert_eq!(Pin::from_url(&url), Some(pin.clone()));
        assert_eq!(Pin::from_url("OpenAndroidDeX://app/R58T51N3G2X/com.whatsapp/"), Pin::new("R58T51N3G2X", "com.whatsapp"));
        assert_eq!(Pin::from_url("openandroiddex://app/R58T51N3G2X/com.whatsapp?x=1#y"), Pin::new("R58T51N3G2X", "com.whatsapp"));
        assert_eq!(Pin::from_args(&argv(&["oadx", &url])), None, "a URL is never taken from a command line");
        assert_eq!(Pin::from_args(&argv(&["oadx", "--phone-app", "192.168.1.20:37159", "com.my_app"])), Some(pin));
        assert_eq!(Pin::from_url("https://app/R58T51N3G2X/com.whatsapp"), None);
        assert_eq!(Pin::from_url("openandroiddex://desktop/R58T51N3G2X"), None);
        assert_eq!(Pin::from_url("openandroiddex://app/R58T51N3G2X/com.whats%22app"), None);
        assert_eq!(Pin::from_url("openandroiddex://app/R58T51N3G2X/com.whatsapp%"), None);
    }

    /// An older pin's adb serial names its phone where that can be known — and
    /// a Wi-Fi address no phone is remembered at names none, rather than
    /// whichever phone is attached at it now.
    #[test]
    fn an_older_pins_serial_names_its_phone_only_where_that_can_be_known() {
        let nobody = |_: &str| None;
        assert_eq!(legacy_phone("R58T51N3G2X", nobody).as_deref(), Some("R58T51N3G2X"));
        assert_eq!(legacy_phone("adb-R58T51N3G2X-vWgJpq._adb-tls-connect._tcp", nobody).as_deref(), Some("R58T51N3G2X"));
        assert_eq!(legacy_phone("adb-._adb-tls-connect._tcp", nobody), None);
        let remembered = |address: &str| (address == "192.168.1.20:37159").then(|| "R58T51N3G2X".to_string());
        assert_eq!(legacy_phone("192.168.1.20:37159", remembered).as_deref(), Some("R58T51N3G2X"));
        assert_eq!(legacy_phone("192.168.1.20:5555", remembered), None);
        assert_eq!(legacy_phone("192.168.1.20:5555", nobody), None);
    }

    /// A Dock app opens its app from the phone it was kept for, read back from
    /// its own script: what a URL asking for it is held to.
    #[test]
    fn a_dock_apps_script_says_which_phone_it_was_kept_for() {
        let pin = Pin::new("R58T51N3G2X", "com.whatsapp").unwrap();
        assert_eq!(dock::script_pin(&dock::script(&pin)), Some(pin));
        assert_eq!(dock::script_pin("#!/bin/sh\nexit 0\n"), None);
        assert_eq!(dock::script_pin("--phone-app 'R58T51N3G2X\" x' 'com.whatsapp'"), None);
    }

    #[test]
    fn a_windows_command_line_is_split_as_windows_does_ours() {
        let pin = Pin::new("R58T51N3G2X", "com.whatsapp").unwrap();
        assert_eq!(split_args(&pin.args()), argv(&["--phone-app", "R58T51N3G2X", "com.whatsapp"]));
        assert_eq!(
            split_args("\"C:\\Program Files\\Open Android DeX\\oadx.exe\"  --phone-app R58 \"com.whatsapp\""),
            argv(&["C:\\Program Files\\Open Android DeX\\oadx.exe", "--phone-app", "R58", "com.whatsapp"])
        );
        assert_eq!(split_args("a \"\" b"), argv(&["a", "", "b"]));
        assert!(split_args("   ").is_empty());
    }

    #[test]
    fn a_desktop_entry_names_its_window_and_how_to_start_it() {
        let pin = Pin::new("R58T51N3G2X", "com.whatsapp").unwrap();
        let id = app_id(&pin.pkg);
        assert_eq!(id, "com.ccrstech.openandroiddex.app.com.whatsapp");
        let text = desktop::entry(&id, "WhatsApp", Path::new("/opt/oadx/open-android-dex"), &pin, Some(Path::new("/home/a/icon.png")));
        assert!(text.starts_with("[Desktop Entry]\n"));
        assert!(text.contains("\nExec=/opt/oadx/open-android-dex --phone-app R58T51N3G2X com.whatsapp\n"));
        assert!(text.contains("\nStartupWMClass=com.ccrstech.openandroiddex.app.com.whatsapp\n"));
        assert!(text.contains("\nIcon=/home/a/icon.png\n"));
        assert!(text.contains("\nNoDisplay=true\n"));
        assert_eq!(desktop::field(&text, "Name").as_deref(), Some("WhatsApp"));
        assert_eq!(desktop::field(&text, desktop::PHONE).as_deref(), Some("R58T51N3G2X"));
        assert_eq!(desktop::field(&text, desktop::PACKAGE).as_deref(), Some("com.whatsapp"));
        assert!(!desktop::entry(&id, "WhatsApp", Path::new("/x"), &pin, None).contains("Icon="));
    }

    /// An AppImage kept where its owner keeps it: spaces, a `$`, a `%`.
    #[test]
    fn an_exec_line_quotes_what_the_specification_reserves() {
        let pin = Pin::new("R58T51N3G2X", "com.whatsapp").unwrap();
        let exe = Path::new("/home/a/My Apps/Open $DeX 100%.AppImage");
        let text = desktop::entry("x", "WhatsApp", exe, &pin, None);
        let exec = text.lines().find_map(|l| l.strip_prefix("Exec=")).unwrap();
        // The string escape doubles the backslash that quotes `$` in the argument.
        assert_eq!(exec, "\"/home/a/My Apps/Open \\\\$DeX 100%%.AppImage\" --phone-app R58T51N3G2X com.whatsapp");
        // Read back as a string, it is the quoted argument the specification asks for.
        assert_eq!(
            desktop::field(&text, "Exec").unwrap(),
            "\"/home/a/My Apps/Open \\$DeX 100%%.AppImage\" --phone-app R58T51N3G2X com.whatsapp"
        );
    }

    #[test]
    fn a_desktop_entry_field_is_read_from_its_own_group_and_unescaped() {
        let text = "[Desktop Entry]\nName=Notes\\sand\\\\more\nIcon = /a.png\n[Desktop Action new]\nName=New note\n";
        assert_eq!(desktop::field(text, "Name").as_deref(), Some("Notes and\\more"));
        assert_eq!(desktop::field(text, "Icon").as_deref(), Some("/a.png"));
        assert_eq!(desktop::field(text, "Exec"), None);
        assert_eq!(desktop::field("[Other]\nName=x\n", "Name"), None);
    }

    #[test]
    fn a_dock_app_is_named_and_started_for_its_phone_app() {
        let pin = Pin::new("R58T51N3G2X", "com.my_app").unwrap();
        assert_eq!(dock::bundle_id(&pin.pkg), "com.ccrstech.openandroiddex.app.com.my-app");
        assert_eq!(dock::file_name("AT&T: Mail/Voice", &pin.pkg), "AT&T MailVoice");
        assert_eq!(dock::file_name(" .hidden ", &pin.pkg), "hidden");
        assert_eq!(dock::file_name("\n", &pin.pkg), "com.my_app");
        let plist = dock::plist("AT&T <Mail>", &pin.pkg);
        assert!(plist.contains("<key>CFBundleName</key>\n\t<string>AT&amp;T &lt;Mail&gt;</string>"));
        assert!(plist.contains("<key>CFBundleIdentifier</key>\n\t<string>com.ccrstech.openandroiddex.app.com.my-app</string>"));
        assert!(plist.contains("<key>CFBundleExecutable</key>\n\t<string>launch</string>"));
        let script = dock::script(&pin);
        assert!(script.starts_with("#!/bin/sh\n"));
        assert!(script.contains(
            "/usr/bin/open -b com.ccrstech.openandroiddex 'openandroiddex://app/R58T51N3G2X/com.my_app' --args --phone-app 'R58T51N3G2X' 'com.my_app' && exit 0\n"
        ));
    }

    #[test]
    fn a_dock_tile_is_found_however_the_dock_wrote_it() {
        let path = "/Users/a/Applications/Open Android DeX Apps/com.whatsapp/WhatsApp.app";
        let id = dock::bundle_id("com.whatsapp");
        assert!(dock::tile(path).contains("<string>/Users/a/Applications/Open Android DeX Apps/com.whatsapp/WhatsApp.app</string>"));
        assert!(dock::mentions(&format!("\"_CFURLString\" = \"{path}\";"), &id, path));
        assert!(dock::mentions(
            "\"_CFURLString\" = \"file:///Users/a/Applications/Open%20Android%20DeX%20Apps/com.whatsapp/WhatsApp.app/\";",
            &id,
            path
        ));
        assert!(dock::mentions("\"bundle-identifier\" = \"com.ccrstech.openandroiddex.app.com.whatsapp\";", &id, path));
        assert!(dock::mentions("\"bundle-identifier\" = com.ccrstech.openandroiddex.app.com.whatsapp;", &id, path));
        // WhatsApp Business is another app.
        assert!(!dock::mentions(
            "\"bundle-identifier\" = \"com.ccrstech.openandroiddex.app.com.whatsapp.w4b\";\n\
             \"_CFURLString\" = \"file:///Users/a/Applications/Open%20Android%20DeX%20Apps/com.whatsapp.w4b/WhatsApp%20Business.app/\";",
            &id,
            path
        ));
    }

    /// A pin made by an older copy of the app, from a window over Wi-Fi: that
    /// copy's folder has gone, as a portable one replaced by the next version's
    /// does, and its icon with it. Healed, the pin starts this copy, for the
    /// phone the address was, with an icon that is there. A shortcut that is
    /// not a phone app's is left alone.
    #[cfg(windows)]
    #[test]
    fn a_taskbar_pin_is_pointed_at_this_copy_its_phone_and_an_icon() {
        use windows::core::{Interface, HSTRING};
        use windows::Win32::Storage::EnhancedStorage::PKEY_AppUserModel_ID;
        use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, IPersistFile, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, STGM_READ};
        use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
        use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};

        let dir = std::env::temp_dir().join(format!("oadx-pin-{}", std::process::id()));
        let old = dir.join("Open.Android.DeX_0.3.29_x64_portable");
        let old_exe = old.join("Open Android DeX.exe");
        let new_exe = dir.join("Open.Android.DeX_0.3.31_x64_portable").join("Open Android DeX.exe");
        let ico = dir.join("com.whatsapp@1.ico");
        for file in [&old_exe, &new_exe, &ico] {
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, b"").unwrap();
        }
        let ours = dir.join("WhatsApp.lnk");
        let theirs = dir.join("Something else.lnk");
        unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let make = |file: &Path, id: Option<String>| {
                let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER).unwrap();
                link.SetPath(&HSTRING::from(old_exe.as_path())).unwrap();
                link.SetArguments(&HSTRING::from("--phone-app \"192.168.1.20:37159\" \"com.whatsapp\"")).unwrap();
                link.SetIconLocation(&HSTRING::from(old.join("com.whatsapp@0.ico").as_path()), 0).unwrap();
                if let Some(id) = id {
                    let store: IPropertyStore = link.cast().unwrap();
                    store.SetValue(&PKEY_AppUserModel_ID, &lpwstr(&id)).unwrap();
                    store.Commit().unwrap();
                }
                link.cast::<IPersistFile>().unwrap().Save(&HSTRING::from(file), true).unwrap();
            };
            make(&ours, Some(super::super::app_user_model_id("com.whatsapp")));
            make(&theirs, None);
        }
        std::fs::remove_dir_all(&old).unwrap();

        let phone_for = |pin: &Pin| if pin.phone == "192.168.1.20:37159" { "R58T51N3G2X".to_string() } else { pin.phone.clone() };
        let icon_for = |_: &str, _: &Pin| Some(ico.clone());
        unsafe {
            let healed = heal_link(&ours, &new_exe, true, &phone_for, &icon_for).unwrap().unwrap();
            assert_eq!((healed.pkg.as_str(), healed.name.as_str()), ("com.whatsapp", "WhatsApp"));
            assert_eq!(healed.changes.len(), 3, "{:?}", healed.changes);
            assert!(heal_link(&theirs, &new_exe, true, &phone_for, &icon_for).unwrap().is_none());

            // Read back as Windows reads it when the pin is clicked.
            let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER).unwrap();
            link.cast::<IPersistFile>().unwrap().Load(&HSTRING::from(ours.as_path()), STGM_READ).unwrap();
            assert!(same_file(&target_of(&link).unwrap(), &new_exe));
            let mut args = vec![0u16; 512];
            link.GetArguments(&mut args).unwrap();
            assert_eq!(wide(&args), "--phone-app \"R58T51N3G2X\" \"com.whatsapp\"");
            let (mut icon, mut index) = (vec![0u16; 512], 0);
            link.GetIconLocation(&mut icon, &mut index).unwrap();
            assert!(same_file(Path::new(&wide(&icon)), &ico));

            // Healed once, there is nothing left to change.
            assert!(heal_link(&ours, &new_exe, true, &phone_for, &icon_for).unwrap().unwrap().changes.is_empty());
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A VT_LPWSTR PROPVARIANT, as apps/win sets a window's AppUserModelID with.
    #[cfg(windows)]
    fn lpwstr(s: &str) -> windows::Win32::System::Com::StructuredStorage::PROPVARIANT {
        use windows::core::PWSTR;
        use windows::Win32::System::Com::CoTaskMemAlloc;
        use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
        use windows::Win32::System::Variant::VT_LPWSTR;
        let wide: Vec<u16> = s.encode_utf16().chain(std::iter::once(0)).collect();
        unsafe {
            let mem = CoTaskMemAlloc(wide.len() * 2) as *mut u16;
            std::ptr::copy_nonoverlapping(wide.as_ptr(), mem, wide.len());
            let mut pv = PROPVARIANT::default();
            let inner = &mut pv.Anonymous.Anonymous;
            inner.vt = VT_LPWSTR;
            inner.Anonymous.pwszVal = PWSTR(mem);
            pv
        }
    }

    fn png(side: u32) -> Vec<u8> {
        let mut png = b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR".to_vec();
        png.extend_from_slice(&side.to_be_bytes());
        png.extend_from_slice(&side.to_be_bytes());
        png.extend_from_slice(&[8, 6, 0, 0, 0]);
        png
    }

    #[test]
    fn an_icns_holds_the_png_under_each_type_its_size_is() {
        let icon = png(256);
        let icns = dock::icns(&icon).unwrap();
        assert_eq!(&icns[..4], b"icns");
        assert_eq!(u32::from_be_bytes(icns[4..8].try_into().unwrap()) as usize, icns.len());
        assert_eq!(&icns[8..12], b"ic08");
        assert_eq!(u32::from_be_bytes(icns[12..16].try_into().unwrap()) as usize, 8 + icon.len());
        assert_eq!(&icns[16..16 + icon.len()], &icon[..]);
        assert_eq!(&icns[16 + icon.len()..20 + icon.len()], b"ic13");
        assert!(dock::icns(&png(96)).is_none(), "no type is 96 pixels");
        assert!(dock::icns(b"GIF89a").is_none());
    }
}
