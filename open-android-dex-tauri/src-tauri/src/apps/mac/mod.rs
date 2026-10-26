//! One phone app in a window of its own on a Mac (doc/apps-on-pc.md, "The
//! window" and "On a Mac").
//!
//! An AppKit window, not a webview, with its own link to the phone (link.rs).
//! Its picture is an AVSampleBufferDisplayLayer handed the phone's H.264 as it
//! arrives (video.rs): macOS decodes it on its media engine and composites the
//! decoded frames as they are, so this process neither decodes, copies nor
//! draws. The link's reader feeds the layer from its own thread; AppKit's
//! main thread, which is busy while a window is dragged or resized, is never
//! in the way of a frame.
//!
//! The window's view takes the mouse, the trackpad's scrolling and the
//! keyboard — text through AppKit's own input, so dead keys and input methods
//! type what they type (keys.rs, `mac`). Its title bar is macOS's, with a Back
//! button beside the window's buttons, since an Android app needs one and a
//! Mac keyboard has none; ⌘[ is Back too, as in Safari, and so is a mouse's
//! side button. Beside Back is More (☰), the window's own menu: full screen.
//! At the bar's other end, a speaker: where the phone's sound plays
//! (sound.rs), as a menu. A Mac pins apps to the Dock, not windows, so the
//! windows are this app's, listed under its Dock icon and in its Window menu.
//! Files dropped on one from Finder go to the phone (drops.rs), and the
//! window's subtitle says how it went.
//!
//! Full screen is macOS's own, in a space of its own — whichever way it is
//! asked for: More, the green button, ⌃⌘F — but with the menu bar kept away,
//! and with it the title bar that would come down from the top. A small bar
//! of the window's own comes down instead when the pointer touches the top of
//! the screen, in the middle, as Remote Desktop's does on Windows; while it is
//! up, a little tab with an arrow there says where it is. Esc leaves.
//!
//! Everything AppKit is touched on the main thread, through the main dispatch
//! queue (which runs during a window's drag and resize as well).

mod video;

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

use dispatch2::{DispatchQueue, DispatchTime};
use objc2::rc::Retained;
use objc2::runtime::{AnyObject, NSObject, NSObjectProtocol, ProtocolObject, Sel};
use objc2::{define_class, msg_send, sel, AnyThread, DefinedClass, MainThreadMarker, MainThreadOnly, Message};
use objc2_app_kit::{
    NSAnimatablePropertyContainer, NSAnimationContext, NSApplication, NSApplicationPresentationOptions,
    NSAutoresizingMaskOptions, NSBackingStoreType, NSButton, NSColor, NSControlStateValueOn, NSDragOperation,
    NSDraggingDestination, NSDraggingInfo, NSEvent, NSEventModifierFlags, NSFont, NSFontWeightMedium,
    NSFontWeightSemibold, NSImage, NSImageSymbolConfiguration, NSLayoutAttribute, NSLineBreakMode, NSMenu,
    NSMenuItem, NSPasteboardTypeFileURL, NSResponder, NSScreen, NSTextAlignment, NSTextField, NSTextInputClient,
    NSTitlebarAccessoryViewController, NSTrackingArea, NSTrackingAreaOptions, NSView, NSVisualEffectBlendingMode,
    NSVisualEffectMaterial, NSVisualEffectState, NSVisualEffectView, NSWindow, NSWindowCollectionBehavior,
    NSWindowDelegate, NSWindowStyleMask, NSWindowTabbingMode,
};
use objc2_foundation::{
    ns_string, NSArray, NSAttributedString, NSAttributedStringKey, NSData, NSInteger, NSNotFound, NSNotification,
    NSPoint, NSRange, NSRangePointer, NSRect, NSSize, NSString, NSUInteger, NSURL,
};
use objc2_quartz_core::{CALayer, CATransaction};
use serde_json::json;

use super::keys::{self, mac as mk};
use super::link::{self, DisplaySize, Event, Host, Link, NetSpec, Refused, Route, Spec, Surface};
use super::sound;
use super::wire;
use video::{Fed, Feed};

/// How long a note stays as the window's subtitle once nothing is happening any more.
const NOTE_MS: u64 = 4500;

/// A window, from outside: what the app holds, from any thread.
pub struct Window {
    pub id: u64,
    pub pkg: String,
    pub label: String,
    pub linux: Option<i32>,
    shared: Arc<Shared>,
}

impl Window {
    /// Bring it to the front, back from the Dock if it was minimised.
    pub fn focus(&self) {
        with_window(self.id, |w, _| {
            if w.isMiniaturized() {
                w.deminiaturize(None);
            }
            w.makeKeyAndOrderFront(None);
            activate(w.mtm());
        });
    }

    /// Close it, as its ✕ does: the app goes back to the phone.
    pub fn close(&self) {
        with_window(self.id, |w, _| w.performClose(None));
    }

    /// The phone says what it shows is gone (a Linux window closed): the window goes too.
    pub fn gone(&self) {
        self.shared.gone();
    }

    /// A new title: a Linux window's, as its app changes it.
    pub fn set_title(&self, title: &str) {
        self.shared.named(title);
    }

    /// The adb serial its connection goes by now.
    pub fn serial(&self) -> String {
        self.shared.link.route().serial
    }

    /// What the phone's server calls it: a notification of its app opens in it by this.
    pub fn key(&self) -> String {
        self.shared.link.key().to_string()
    }

    /// It is the window the person is using: the key window, this app active.
    pub fn is_front(&self) -> bool {
        self.shared.front.load(Ordering::SeqCst)
    }

    /// The way it reaches its phone now: the serial, the server, USB or Wi‑Fi.
    pub fn route(&self) -> Route {
        self.shared.link.route()
    }

    /// Its connection made again over `route` (link::reconnect).
    pub fn reconnect(&self, route: Route, reopen: bool) -> Result<(), Refused> {
        link::reconnect(&self.shared.link, route, reopen)
    }

    /// Its app handed back to the phone over `route`, for a window closed while its connection was gone.
    pub fn hand_back(&self, route: &Route) {
        link::hand_back(&self.shared.link, route);
    }

    /// A short note in its subtitle (Surface::note).
    pub fn note(&self, text: Option<String>, busy: bool) {
        self.shared.note(text, busy);
    }

    /// A line over its picture, which is dimmed under it; None takes it away.
    pub fn say(&self, text: Option<String>) {
        Surface::say(&*self.shared, text);
    }
}

/// The scale of the screen the pointer is on — the one a click just opened something from.
pub fn screen_scale() -> f32 {
    dispatch2::run_on_main(|mtm| screen_under_pointer(mtm).map_or(2.0, |s| s.backingScaleFactor())) as f32
}

/// State the window's main-thread side and the link's threads share.
struct Shared {
    link: Arc<Link>,
    id: u64,
    label: Mutex<String>,
    /// The picture's size in pixels: the view's, at its screen's scale.
    area: Mutex<(u32, u32)>,
    /// Pixels per point, ×1000.
    scale_milli: AtomicU32,
    feed: Mutex<Feed>,
    /// The picture as the phone codes it, and the part of that which is the
    /// app (top left): the rest is the encoder's padding, laid off-window.
    picture: Mutex<((u32, u32), (u32, u32))>,
    /// A Linux window: its ✕ asks the app, and the window goes when the X window does.
    linux: bool,
    /// When the ✕ last asked a Linux app to close its window; a second ✕ soon after closes it here.
    asked_close: Mutex<Option<Instant>>,
    /// When the window last changed size: the phone is asked once it holds still.
    resized_at: Mutex<Instant>,
    stats: Mutex<Stats>,
    /// The phone's name, which the subtitle begins with.
    device: String,
    /// The window's subtitle — the phone, and how the window reaches it — when
    /// no note has its place. Read when a note goes, so a note never puts back
    /// a way the window has left.
    subtitle: Arc<Mutex<String>>,
    /// Counts the notes, so a note's fading never takes the next one with it.
    note_seq: Arc<AtomicU64>,
    /// The key window: the one in front, what the keyboard types into. A
    /// window resigns it when this app stops being the active one, too.
    front: AtomicBool,
}

/// How the picture has been doing, for the log every ten seconds.
struct Stats {
    since: Instant,
    frames: u64,
    /// How old each frame was when macOS was handed it, ms.
    ages: Vec<f64>,
}

impl Shared {
    fn scale(&self) -> f64 {
        self.scale_milli.load(Ordering::SeqCst) as f64 / 1000.0
    }

    fn ask_size(&self) {
        self.link.ask_size(Surface::wanted(self));
    }

    fn shown(&self, pts: u64) {
        if !self.link.is_live() {
            with_window(self.id, |_, v| v.say(None));
        }
        self.link.shown();
        let mut s = self.stats.lock().unwrap();
        s.frames += 1;
        if let Some(age) = self.link.age_ms(pts) {
            s.ages.push(age);
        }
        if s.since.elapsed() >= Duration::from_secs(10) {
            let (median, p95) = link::percentiles(&mut s.ages);
            let ms = |v: Option<f64>| v.map(|m| format!("{m:.1} ms")).unwrap_or_else(|| "?".into());
            log::info!(
                "apps: {}: {} frames in {:.0}s, each handed to macOS {} after the phone composed it (p95 {})",
                self.label.lock().unwrap(),
                s.frames,
                s.since.elapsed().as_secs_f32(),
                ms(median),
                ms(p95),
            );
            *s = Stats { since: Instant::now(), frames: 0, ages: Vec::new() };
        }
    }
}

/// What the link to the phone needs of the window, from the link's threads.
impl Surface for Shared {
    fn label(&self) -> String {
        self.label.lock().unwrap().clone()
    }

    fn wanted(&self) -> DisplaySize {
        let (w, h) = *self.area.lock().unwrap();
        DisplaySize::for_area(w, h, self.scale() as f32)
    }

    fn video(&self, flags: u8, pts: u64, data: Vec<u8>) {
        let fed = self.feed.lock().unwrap().push(flags, &data);
        match fed {
            Ok(Fed::Shown) => self.shown(pts),
            Ok(Fed::Nothing) => {}
            Ok(Fed::WantsKeyFrame) => self.link.send(wire::json(&json!({"t": "keyframe"}))),
            Err(e) => {
                log::warn!("apps: {}: {e}", self.label.lock().unwrap());
                if !self.link.is_live() {
                    self.say(Some("This Mac could not show the app's picture.".into()));
                }
            }
        }
    }

    fn format(&self, coded: (u32, u32), content: (u32, u32)) {
        *self.picture.lock().unwrap() = (coded, content);
        with_window(self.id, |_, v| v.lay_out_picture());
    }

    fn named(&self, name: &str) {
        if name.is_empty() {
            return;
        }
        {
            let mut label = self.label.lock().unwrap();
            if *label == name {
                return;
            }
            *label = name.to_string();
        }
        let name = name.to_string();
        with_window(self.id, move |w, v| {
            let name = NSString::from_str(&name);
            w.setTitle(&name);
            if let Some(bar) = v.ivars().bar.borrow().as_ref() {
                bar.title.setStringValue(&name);
            }
        });
    }

    fn say(&self, text: Option<String>) {
        with_window(self.id, move |_, v| v.say(text.as_deref()));
    }

    /// The subtitle, where the phone's name is: a Mac's title bar is its own,
    /// and the subtitle is the place it gives a window for a line like this.
    fn note(&self, text: Option<String>, busy: bool) {
        let seq = self.note_seq.fetch_add(1, Ordering::SeqCst) + 1;
        let (id, subtitle) = (self.id, self.subtitle.clone());
        let shown = text.unwrap_or_else(|| subtitle.lock().unwrap().clone());
        with_window(id, move |w, _| w.setSubtitle(&NSString::from_str(&shown)));
        if busy {
            return;
        }
        let latest = self.note_seq.clone();
        if let Ok(when) = DispatchTime::try_from(Duration::from_millis(NOTE_MS)) {
            let _ = DispatchQueue::main().after(when, move || {
                if latest.load(Ordering::SeqCst) == seq {
                    let text = subtitle.lock().unwrap().clone();
                    with_window(id, move |w, _| w.setSubtitle(&NSString::from_str(&text)));
                }
            });
        }
    }

    /// A new way to the phone: "Wi‑Fi · Galaxy S25". A note up now gives back this one when it goes.
    fn way(&self, via: &str) {
        let text = link::way_line(&self.device, via);
        *self.subtitle.lock().unwrap() = text.clone();
        with_window(self.id, move |w, _| w.setSubtitle(&NSString::from_str(&text)));
    }

    fn gone(&self) {
        with_window(self.id, |w, v| {
            v.ivars().gone.set(true);
            w.close();
        });
    }
}

// ── the main thread's ──

/// A window's AppKit objects, held on the main thread while it is open.
struct Parts {
    window: Retained<NSWindow>,
    view: Retained<AppView>,
}

thread_local! {
    /// The open windows by id. Only ever touched on the main thread.
    static OPEN: RefCell<HashMap<u64, Parts>> = RefCell::new(HashMap::new());
}

/// Run `f` on the main thread with window `id`'s window and view, if it is still open.
fn with_window(id: u64, f: impl FnOnce(&NSWindow, &AppView) + Send + 'static) {
    DispatchQueue::main().exec_async(move || {
        // Out of the table before `f` runs: closing the window takes it out.
        let parts = OPEN.with(|o| o.borrow().get(&id).map(|p| (p.window.clone(), p.view.clone())));
        if let Some((w, v)) = parts {
            f(&w, &v);
        }
    });
}

fn activate(mtm: MainThreadMarker) {
    #[allow(deprecated)] // activate() is macOS 14's; this one is every version's
    NSApplication::sharedApplication(mtm).activateIgnoringOtherApps(true);
}

fn screen_under_pointer(mtm: MainThreadMarker) -> Option<Retained<NSScreen>> {
    let at = NSEvent::mouseLocation();
    let inside = |r: NSRect| {
        at.x >= r.origin.x && at.x < r.origin.x + r.size.width && at.y >= r.origin.y && at.y < r.origin.y + r.size.height
    };
    NSScreen::screens(mtm).iter().find(|s| inside(s.frame())).or_else(|| NSScreen::mainScreen(mtm))
}

/// What the main thread needs to make the window.
struct Look {
    id: u64,
    label: String,
    device: String,
    via: &'static str,
    icon_png: Option<Vec<u8>>,
    cascade: u32,
    size: Option<(u32, u32)>,
}

/// Open a window for `spec`; it runs until it is closed. The window shows at
/// once, with the app's name, and the picture follows.
pub fn open(spec: Spec, host: Arc<dyn Host>) -> Result<Arc<Window>, String> {
    let (link, out_rx) = Link::new(spec.clicked, host);
    let shared = Arc::new(Shared {
        link,
        id: spec.id,
        label: Mutex::new(spec.label.clone()),
        area: Mutex::new((0, 0)),
        scale_milli: AtomicU32::new(2000),
        feed: Mutex::new(Feed::new()),
        picture: Mutex::new(((0, 0), (0, 0))),
        linux: spec.linux.is_some(),
        asked_close: Mutex::new(None),
        resized_at: Mutex::new(Instant::now()),
        stats: Mutex::new(Stats { since: Instant::now(), frames: 0, ages: Vec::new() }),
        device: spec.device.clone(),
        subtitle: Arc::new(Mutex::new(link::way_line(&spec.device, spec.via))),
        note_seq: Arc::new(AtomicU64::new(0)),
        front: AtomicBool::new(false),
    });
    let look = Look {
        id: spec.id,
        label: spec.label.clone(),
        device: spec.device.clone(),
        via: spec.via,
        icon_png: spec.icon_png.clone(),
        cascade: spec.cascade,
        size: spec.size,
    };
    let (tx, rx) = mpsc::channel::<()>();
    {
        let shared = shared.clone();
        DispatchQueue::main().exec_async(move || {
            let mtm = MainThreadMarker::new().expect("the main queue runs on the main thread");
            make(mtm, &look, &shared);
            let _ = tx.send(());
        });
    }
    rx.recv_timeout(Duration::from_secs(10)).map_err(|_| "the window did not open".to_string())?;
    let net = NetSpec {
        serial: spec.serial.clone(),
        socket: spec.socket.clone(),
        pkg: spec.pkg.clone(),
        activity: spec.activity.clone(),
        linux: spec.linux,
        direct: spec.direct,
        via: spec.via,
        notif: spec.notif.clone(),
    };
    link::start(shared.link.clone(), shared.clone(), net, out_rx);
    Ok(Arc::new(Window {
        id: spec.id,
        pkg: spec.pkg,
        label: spec.label,
        linux: spec.linux,
        shared,
    }))
}

/// The window, on the screen the pointer is on (the one the click was made
/// on), phone-shaped or a Linux window's own size, cascaded from the windows
/// already open.
fn make(mtm: MainThreadMarker, look: &Look, shared: &Arc<Shared>) {
    let screen = screen_under_pointer(mtm);
    let (visible, scale) = screen
        .as_ref()
        .map(|s| (s.visibleFrame(), s.backingScaleFactor()))
        .unwrap_or((NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(1440.0, 900.0)), 2.0));
    // 412 × 915 points: a current phone's own shape and size — or the size a
    // Linux window asked for, which is in pixels.
    let (mut w, mut h) = match look.size {
        Some((pw, ph)) => ((pw.max(160) as f64) / scale, (ph.max(120) as f64) / scale),
        None => (412.0, 915.0),
    };
    let fit = ((visible.size.height * 0.9 - 32.0) / h).min(visible.size.width * 0.95 / w).min(1.0);
    w = (w * fit).round();
    h = (h * fit).round();
    let content = NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(w, h));

    let style = NSWindowStyleMask::Titled
        | NSWindowStyleMask::Closable
        | NSWindowStyleMask::Miniaturizable
        | NSWindowStyleMask::Resizable;
    let window = unsafe {
        NSWindow::initWithContentRect_styleMask_backing_defer(
            NSWindow::alloc(mtm),
            content,
            style,
            NSBackingStoreType::Buffered,
            false,
        )
    };
    // Held in OPEN, and let go of there: AppKit must not release it as well.
    unsafe { window.setReleasedWhenClosed(false) };
    window.setTitle(&NSString::from_str(&look.label));
    window.setSubtitle(&NSString::from_str(&link::way_line(&look.device, look.via)));
    // Each app its own window, never a tab of another's.
    window.setTabbingMode(NSWindowTabbingMode::Disallowed);
    // Minimised, it is the app's own icon in the Dock.
    if let Some(png) = &look.icon_png {
        let icon = NSImage::initWithData(NSImage::alloc(), &NSData::with_bytes(png));
        if let Some(icon) = icon {
            window.setMiniwindowImage(Some(&icon));
        }
    }
    window.setContentMinSize(NSSize::new(240.0, 320.0));
    window.setCollectionBehavior(NSWindowCollectionBehavior::FullScreenPrimary);
    let frame = window.frame();
    let step = 28.0 * (look.cascade % 8) as f64;
    let x = visible.origin.x + (visible.size.width - frame.size.width) / 2.0 + step;
    let y = visible.origin.y + (visible.size.height - frame.size.height) / 2.0 - step / 2.0;
    window.setFrameOrigin(NSPoint::new(x, y.max(visible.origin.y)));

    // The content: the app's view, and a line over it until its picture comes.
    let container = NSView::initWithFrame(NSView::alloc(mtm), content);
    container.setWantsLayer(true);
    let view = AppView::new(mtm, content, shared.clone());
    view.setAutoresizingMask(NSAutoresizingMaskOptions::ViewWidthSizable | NSAutoresizingMaskOptions::ViewHeightSizable);
    container.addSubview(&view);
    // Wrapping: "Your phone stopped answering…" is wider than a phone-shaped window.
    let message = NSTextField::wrappingLabelWithString(
        &NSString::from_str(&format!(
            "Opening on {}…",
            if look.device.is_empty() { "your phone" } else { &look.device }
        )),
        mtm,
    );
    message.setAlignment(NSTextAlignment::Center);
    message.setTextColor(Some(&NSColor::secondaryLabelColor()));
    message.setFrame(NSRect::new(NSPoint::new(16.0, h / 2.0 - 30.0), NSSize::new((w - 32.0).max(40.0), 60.0)));
    message.setAutoresizingMask(
        NSAutoresizingMaskOptions::ViewWidthSizable
            | NSAutoresizingMaskOptions::ViewMinYMargin
            | NSAutoresizingMaskOptions::ViewMaxYMargin,
    );
    container.addSubview(&message);
    *view.ivars().message.borrow_mut() = Some(message);
    // Over everything, out of sight until the window is full screen: the
    // bar's tab, and the bar over it.
    let handle = make_handle(mtm, &view);
    container.addSubview(&handle);
    *view.ivars().handle.borrow_mut() = Some(handle);
    let bar = make_bar(mtm, &view, &look.label);
    container.addSubview(&bar.view);
    *view.ivars().bar.borrow_mut() = Some(bar);
    window.setContentView(Some(&container));
    window.setDelegate(Some(ProtocolObject::from_ref(&*view)));
    window.setAcceptsMouseMovedEvents(true);
    window.makeFirstResponder(Some(&view));
    add_title_buttons(mtm, &window, &view);
    add_sound_button(mtm, &window, &view);

    shared.scale_milli.store((window.backingScaleFactor() * 1000.0) as u32, Ordering::SeqCst);
    view.resized(false);
    window.makeKeyAndOrderFront(None);
    activate(mtm);
    OPEN.with(|o| o.borrow_mut().insert(look.id, Parts { window, view }));
}

/// A borderless button showing one of the system's symbols, which `target` answers.
fn symbol_button(
    mtm: MainThreadMarker,
    symbol: &str,
    name: &str,
    tip: &str,
    action: Sel,
    target: &AnyObject,
) -> Option<Retained<NSButton>> {
    let image = NSImage::imageWithSystemSymbolName_accessibilityDescription(
        &NSString::from_str(symbol),
        Some(&NSString::from_str(name)),
    )?;
    let button = unsafe { NSButton::buttonWithImage_target_action(&image, Some(target), Some(action), mtm) };
    button.setBordered(false);
    button.setToolTip(Some(&NSString::from_str(tip)));
    Some(button)
}

/// Back and More, in the title bar beside the window's own buttons.
fn add_title_buttons(mtm: MainThreadMarker, window: &NSWindow, view: &AppView) {
    let target: &AnyObject = view;
    let holder = NSView::initWithFrame(NSView::alloc(mtm), NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(64.0, 28.0)));
    let buttons = [
        (4.0, "chevron.backward", "Back", "Back (⌘[)", sel!(goBack:)),
        (32.0, "line.3.horizontal", "More", "More", sel!(showMore:)),
    ];
    for (x, symbol, name, tip, action) in buttons {
        if let Some(button) = symbol_button(mtm, symbol, name, tip, action, target) {
            button.setFrame(NSRect::new(NSPoint::new(x, 0.0), NSSize::new(28.0, 28.0)));
            holder.addSubview(&button);
        }
    }
    let accessory = NSTitlebarAccessoryViewController::new(mtm);
    accessory.setView(&holder);
    accessory.setLayoutAttribute(NSLayoutAttribute::Left);
    window.addTitlebarAccessoryViewController(&accessory);
}

/// Where the phone's sound plays, at the title bar's other end: a menu.
fn add_sound_button(mtm: MainThreadMarker, window: &NSWindow, view: &AppView) {
    let Some(image) = NSImage::imageWithSystemSymbolName_accessibilityDescription(
        ns_string!("speaker.wave.2"),
        Some(ns_string!("Sound")),
    ) else {
        return;
    };
    let target: &AnyObject = view;
    let button = unsafe { NSButton::buttonWithImage_target_action(&image, Some(target), Some(sel!(soundMenu:)), mtm) };
    button.setBordered(false);
    button.setToolTip(Some(ns_string!("Where the sound plays")));
    button.setFrame(NSRect::new(NSPoint::new(4.0, 0.0), NSSize::new(28.0, 28.0)));
    let holder = NSView::initWithFrame(NSView::alloc(mtm), NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(36.0, 28.0)));
    holder.addSubview(&button);
    let accessory = NSTitlebarAccessoryViewController::new(mtm);
    accessory.setView(&holder);
    accessory.setLayoutAttribute(NSLayoutAttribute::Right);
    window.addTitlebarAccessoryViewController(&accessory);
}

/// The full-screen bar: how wide and tall the part of it that shows is, and
/// how far its rounded top hangs above the screen, out of sight.
const BAR_W: f64 = 380.0;
const BAR_H: f64 = 30.0;
const BAR_ROUND: f64 = 9.0;
/// How near the top of the screen the pointer must come to bring the bar down, in points.
const TOP_EDGE: f64 = 2.0;
/// How far off the bar the pointer may stray before the bar goes back up, in points.
const BAR_NEAR: f64 = 24.0;
/// How long the bar stays once the pointer has left it…
const BAR_STAYS: Duration = Duration::from_millis(600);
/// …and when the window has just gone full screen, to show where it is.
const BAR_ON_ARRIVAL: Duration = Duration::from_secs(2);

/// The tab at the top of a full-screen window while its bar is up: how wide
/// and tall the part of it that shows is (its rounded top is above the screen too).
const HANDLE_W: f64 = 44.0;
const HANDLE_H: f64 = 14.0;
const HANDLE_ROUND: f64 = 7.0;

/// The bar a full-screen window brings down from the top of the screen, as
/// Remote Desktop's does: small and in the middle, with Back and More, the
/// app's name, where the sound plays, leaving full screen, and closing.
struct Bar {
    view: Retained<NSVisualEffectView>,
    title: Retained<NSTextField>,
}

/// What the full-screen bar and its tab are made of: the title bar's
/// material over the app's picture, which shows through, blurred; rounded,
/// in the middle at the top whatever the window's size, and out of sight
/// until the window is full screen.
fn top_piece(mtm: MainThreadMarker, size: NSSize, round: f64) -> Retained<NSVisualEffectView> {
    let piece = NSVisualEffectView::initWithFrame(NSVisualEffectView::alloc(mtm), NSRect::new(NSPoint::new(0.0, 0.0), size));
    piece.setMaterial(NSVisualEffectMaterial::Titlebar);
    piece.setBlendingMode(NSVisualEffectBlendingMode::WithinWindow);
    piece.setState(NSVisualEffectState::Active);
    piece.setWantsLayer(true);
    if let Some(layer) = piece.layer() {
        layer.setCornerRadius(round);
        layer.setMasksToBounds(true);
    }
    piece.setAutoresizingMask(
        NSAutoresizingMaskOptions::ViewMinXMargin
            | NSAutoresizingMaskOptions::ViewMaxXMargin
            | NSAutoresizingMaskOptions::ViewMinYMargin,
    );
    piece.setHidden(true);
    piece
}

/// The tab: an arrow pointing down, at the top of the screen, where the bar is.
fn make_handle(mtm: MainThreadMarker, view: &AppView) -> Retained<NSVisualEffectView> {
    let handle = top_piece(mtm, NSSize::new(HANDLE_W, HANDLE_H + HANDLE_ROUND), HANDLE_ROUND);
    let target: &AnyObject = view;
    if let Some(button) = symbol_button(mtm, "chevron.down", "Show the bar", "Show the bar", sel!(showBar:), target) {
        let small = NSImageSymbolConfiguration::configurationWithPointSize_weight(9.0, unsafe { NSFontWeightSemibold });
        if let Some(image) = button.image().and_then(|i| i.imageWithSymbolConfiguration(&small)) {
            button.setImage(Some(&image));
        }
        button.setFrame(NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(HANDLE_W, HANDLE_H)));
        handle.addSubview(&button);
    }
    handle
}

fn make_bar(mtm: MainThreadMarker, view: &AppView, label: &str) -> Bar {
    let bar = top_piece(mtm, NSSize::new(BAR_W, BAR_H + BAR_ROUND), BAR_ROUND);
    let target: &AnyObject = view;
    let buttons = [
        (4.0, "chevron.backward", "Back", "Back (⌘[)", sel!(goBack:)),
        (32.0, "line.3.horizontal", "More", "More", sel!(showMore:)),
        (BAR_W - 88.0, "speaker.wave.2", "Sound", "Where the sound plays", sel!(soundMenu:)),
        (BAR_W - 60.0, "arrow.down.right.and.arrow.up.left", "Exit full screen", "Exit full screen (Esc)", sel!(switchFullScreen:)),
        (BAR_W - 32.0, "xmark", "Close", "Close", sel!(closeWindow:)),
    ];
    for (x, symbol, name, tip, action) in buttons {
        if let Some(button) = symbol_button(mtm, symbol, name, tip, action, target) {
            button.setFrame(NSRect::new(NSPoint::new(x, (BAR_H - 24.0) / 2.0), NSSize::new(28.0, 24.0)));
            bar.addSubview(&button);
        }
    }
    let title = NSTextField::labelWithString(&NSString::from_str(label), mtm);
    title.setAlignment(NSTextAlignment::Center);
    title.setLineBreakMode(NSLineBreakMode::ByTruncatingTail);
    title.setFont(Some(&NSFont::systemFontOfSize_weight(12.0, unsafe { NSFontWeightMedium })));
    // In the middle: as far from the bar's ends as the three buttons at its right reach.
    title.setFrame(NSRect::new(NSPoint::new(92.0, (BAR_H - 16.0) / 2.0), NSSize::new(BAR_W - 184.0, 16.0)));
    bar.addSubview(&title);
    Bar { view: bar, title }
}

/// Android's MotionEvent.BUTTON_* bits.
const BUTTON_PRIMARY: u8 = 1;
const BUTTON_SECONDARY: u8 = 2;
const BUTTON_TERTIARY: u8 = 4;

/// A trackpad scrolls in points; a mouse wheel's notch is about this many.
const POINTS_PER_NOTCH: f64 = 10.0;

/// The view's own state. Main thread only.
struct Ui {
    shared: Arc<Shared>,
    /// Mouse buttons held in the picture, as MotionEvent.BUTTON_* bits.
    buttons: Cell<u8>,
    /// Control+click is a Mac's right click: the primary button went as the secondary.
    control_click: Cell<bool>,
    /// Keys sent to the phone as keys, by macOS's code, with the code each went
    /// as: their release goes too.
    keys_down: RefCell<HashMap<u16, u16>>,
    /// An input method's text, while it is being composed.
    marked: RefCell<String>,
    /// Where the pointer last went down, in the view: where an input method's
    /// candidates show (the app's caret is on the phone).
    caret: Cell<NSPoint>,
    /// The picture, inside a layer that crops off the phone's padding.
    clip: Retained<CALayer>,
    picture: Retained<objc2_av_foundation::AVSampleBufferDisplayLayer>,
    message: RefCell<Option<Retained<NSTextField>>>,
    tracking: RefCell<Option<Retained<NSTrackingArea>>>,
    /// The sound menu as last shown: its items' tags index it.
    sound: RefCell<Vec<sound::Item>>,
    /// Full screen, from the moment the window starts to go there.
    full_screen: Cell<bool>,
    /// The bar a full-screen window brings down from the top, and whether it
    /// is down; and the tab that shows where it is while it is up.
    bar: RefCell<Option<Bar>>,
    handle: RefCell<Option<Retained<NSVisualEffectView>>>,
    bar_down: Cell<bool>,
    /// The bar is due to go back up; a new `bar_hide` calls that off.
    bar_leaving: Cell<bool>,
    bar_hide: Cell<u64>,
    /// More's menu or the sound menu is open: the bar stays until it closes.
    menu_open: Cell<bool>,
    /// Esc took the window out of full screen: its repeats go nowhere either.
    esc_held: Cell<bool>,
    /// Closing because the phone said the app had gone: the phone is not told.
    gone: Cell<bool>,
    closed: Cell<bool>,
}

define_class!(
    // SAFETY: NSView asks nothing of a subclass that this breaks; AppView has no Drop.
    #[unsafe(super(NSView, NSResponder, NSObject))]
    #[thread_kind = MainThreadOnly]
    #[name = "OpenAndroidDeXPhoneAppView"]
    #[ivars = Ui]
    struct AppView;

    impl AppView {
        #[unsafe(method(acceptsFirstResponder))]
        fn accepts_first_responder(&self) -> bool {
            true
        }

        /// A click on a window behind others both brings it forward and
        /// reaches the app, as on Windows.
        #[unsafe(method(acceptsFirstMouse:))]
        fn accepts_first_mouse(&self, _event: Option<&NSEvent>) -> bool {
            true
        }

        #[unsafe(method(setFrameSize:))]
        fn set_frame_size(&self, size: NSSize) {
            let _: () = unsafe { msg_send![super(self), setFrameSize: size] };
            // Until it is in its window, the window is still being made.
            self.resized(self.window().is_some());
        }

        #[unsafe(method(viewDidChangeBackingProperties))]
        fn backing_changed(&self) {
            if let Some(w) = self.window() {
                self.ivars().shared.scale_milli.store((w.backingScaleFactor() * 1000.0) as u32, Ordering::SeqCst);
            }
            self.resized(true);
        }

        #[unsafe(method(updateTrackingAreas))]
        fn update_tracking_areas(&self) {
            if let Some(old) = self.ivars().tracking.borrow_mut().take() {
                self.removeTrackingArea(&old);
            }
            // Hover reaches the app whether or not the window is in front, as on Windows.
            let options = NSTrackingAreaOptions::MouseEnteredAndExited
                | NSTrackingAreaOptions::MouseMoved
                | NSTrackingAreaOptions::ActiveAlways
                | NSTrackingAreaOptions::InVisibleRect;
            let owner: &AnyObject = self;
            let area = unsafe {
                NSTrackingArea::initWithRect_options_owner_userInfo(
                    NSTrackingArea::alloc(),
                    self.bounds(),
                    options,
                    Some(owner),
                    None,
                )
            };
            self.addTrackingArea(&area);
            *self.ivars().tracking.borrow_mut() = Some(area);
            let _: () = unsafe { msg_send![super(self), updateTrackingAreas] };
        }

        #[unsafe(method(mouseDown:))]
        fn mouse_down(&self, event: &NSEvent) {
            let control = event.modifierFlags().contains(NSEventModifierFlags::Control);
            self.ivars().control_click.set(control);
            self.press(event, if control { BUTTON_SECONDARY } else { BUTTON_PRIMARY }, true);
        }

        #[unsafe(method(mouseUp:))]
        fn mouse_up(&self, event: &NSEvent) {
            let bit = if self.ivars().control_click.replace(false) { BUTTON_SECONDARY } else { BUTTON_PRIMARY };
            self.press(event, bit, false);
        }

        #[unsafe(method(rightMouseDown:))]
        fn right_mouse_down(&self, event: &NSEvent) {
            self.press(event, BUTTON_SECONDARY, true);
        }

        #[unsafe(method(rightMouseUp:))]
        fn right_mouse_up(&self, event: &NSEvent) {
            self.press(event, BUTTON_SECONDARY, false);
        }

        #[unsafe(method(otherMouseDown:))]
        fn other_mouse_down(&self, event: &NSEvent) {
            self.other(event, true);
        }

        #[unsafe(method(otherMouseUp:))]
        fn other_mouse_up(&self, event: &NSEvent) {
            self.other(event, false);
        }

        #[unsafe(method(mouseDragged:))]
        fn mouse_dragged(&self, event: &NSEvent) {
            self.pointer(event);
        }

        #[unsafe(method(rightMouseDragged:))]
        fn right_mouse_dragged(&self, event: &NSEvent) {
            self.pointer(event);
        }

        #[unsafe(method(otherMouseDragged:))]
        fn other_mouse_dragged(&self, event: &NSEvent) {
            self.pointer(event);
        }

        #[unsafe(method(mouseMoved:))]
        fn mouse_moved(&self, event: &NSEvent) {
            if self.ivars().buttons.get() == 0 {
                let on_bar = self.track(self.convertPoint_fromView(event.locationInWindow(), None));
                if !on_bar {
                    self.pointer(event);
                }
            }
        }

        #[unsafe(method(mouseExited:))]
        fn mouse_exited(&self, event: &NSEvent) {
            // Out over the top of the screen (into the strip beside a camera
            // notch, or onto a screen above) is as good as touching it.
            self.track(self.convertPoint_fromView(event.locationInWindow(), None));
            if self.ivars().buttons.get() == 0 {
                self.ivars().shared.link.send(wire::json(&json!({"t": "leave"})));
            }
        }

        #[unsafe(method(scrollWheel:))]
        fn scroll_wheel(&self, event: &NSEvent) {
            // A trackpad's and a Magic Mouse's are in points, a wheel's in
            // notches; the content-follows-fingers direction is the Mac's own.
            let (mut h, mut v) = (-event.scrollingDeltaX(), event.scrollingDeltaY());
            if event.hasPreciseScrollingDeltas() {
                h /= POINTS_PER_NOTCH;
                v /= POINTS_PER_NOTCH;
            }
            if h == 0.0 && v == 0.0 {
                return;
            }
            let (dx, dy) = self.on_display(event);
            let meta = mods(event).meta();
            self.ivars().shared.link.send(wire::scroll(dx, dy, h as f32, v as f32, meta));
        }

        #[unsafe(method(keyDown:))]
        fn key_down(&self, event: &NSEvent) {
            let kvk = event.keyCode();
            let ui = self.ivars();
            // Esc takes a full-screen window out of full screen, and does not
            // reach the app; nor do its repeats while it is held.
            if kvk == mk::ESCAPE {
                if event.isARepeat() && ui.esc_held.get() {
                    return;
                }
                let m = mods(event);
                let alone = !(m.shift || m.control || m.option || m.command);
                ui.esc_held.set(ui.full_screen.get() && alone);
                if ui.esc_held.get() {
                    if let Some(w) = self.window() {
                        w.toggleFullScreen(None);
                    }
                    return;
                }
            }
            let plain = event.charactersIgnoringModifiers().map(|s| s.to_string()).unwrap_or_default();
            match mk::press(kvk, &plain, mods(event)) {
                mk::Press::Key(code, meta) => {
                    self.key(code, meta, event.isARepeat());
                    self.ivars().keys_down.borrow_mut().insert(kvk, code);
                }
                mk::Press::Text => self.interpretKeyEvents(&NSArray::from_slice(&[event])),
                mk::Press::Nothing => {}
            }
        }

        #[unsafe(method(keyUp:))]
        fn key_up(&self, event: &NSEvent) {
            if event.keyCode() == mk::ESCAPE {
                self.ivars().esc_held.set(false);
            }
            let code = self.ivars().keys_down.borrow_mut().remove(&event.keyCode());
            if let Some(code) = code {
                self.ivars().shared.link.send(wire::key(false, 0, code, mods(event).meta()));
            }
        }

        /// ⌘ with a key comes here before it is a key down, and the menus'
        /// shortcuts would take it next: the phone's, unless it is one that
        /// stays the Mac's. AppKit does not always send a view the key-up of a
        /// ⌘ shortcut, so it goes as a press and a release at once.
        #[unsafe(method(performKeyEquivalent:))]
        fn perform_key_equivalent(&self, event: &NSEvent) -> bool {
            self.shortcut(event)
        }

        /// The title bar's Back, and the full-screen bar's.
        #[unsafe(method(goBack:))]
        fn go_back(&self, _sender: Option<&AnyObject>) {
            let link = &self.ivars().shared.link;
            link.send(wire::key(true, 0, keys::code::BACK, 0));
            link.send(wire::key(false, 0, keys::code::BACK, 0));
        }

        /// The title bar's speaker: where the phone's sound plays.
        #[unsafe(method(soundMenu:))]
        fn sound_menu(&self, sender: Option<&AnyObject>) {
            self.show_sound_menu(sender.and_then(|s| s.downcast_ref::<NSView>()));
        }

        /// A choice from that menu, by its item's tag.
        #[unsafe(method(pickSound:))]
        fn pick_sound(&self, sender: Option<&AnyObject>) {
            let Some(item) = sender.and_then(|s| s.downcast_ref::<NSMenuItem>()) else { return };
            let picked = self.ivars().sound.borrow().get(item.tag() as usize).cloned();
            if let Some(sound::Item::Choice { choice, enabled: true, .. }) = picked {
                let shared = self.ivars().shared.clone();
                shared.link.host().pick_sound(choice, shared.clone());
            }
        }

        /// More, in the title bar or the full-screen bar: its menu, under it.
        #[unsafe(method(showMore:))]
        fn show_more(&self, sender: Option<&AnyObject>) {
            if let Some(button) = sender.and_then(|s| s.downcast_ref::<NSView>()) {
                self.more(button);
            }
        }

        /// More's Full screen, and the full-screen bar's way out of it. Not
        /// `toggleFullScreen:`, which would take the menu bar's ⌃⌘F from the window.
        #[unsafe(method(switchFullScreen:))]
        fn switch_full_screen(&self, _sender: Option<&AnyObject>) {
            if let Some(w) = self.window() {
                w.toggleFullScreen(None);
            }
        }

        /// The full-screen bar's tab, clicked: the bar comes down.
        #[unsafe(method(showBar:))]
        fn show_bar(&self, _sender: Option<&AnyObject>) {
            self.bar_down(true);
        }

        /// The full-screen bar's ✕, as the title bar's.
        #[unsafe(method(closeWindow:))]
        fn close_window(&self, _sender: Option<&AnyObject>) {
            if let Some(w) = self.window() {
                w.performClose(None);
            }
        }
    }

    unsafe impl NSObjectProtocol for AppView {}

    /// Files from Finder, dropped on the window: to the phone.
    unsafe impl NSDraggingDestination for AppView {
        #[unsafe(method(draggingEntered:))]
        fn dragging_entered(&self, sender: &ProtocolObject<dyn NSDraggingInfo>) -> NSDragOperation {
            if dropped_paths(sender).is_empty() {
                NSDragOperation::None
            } else {
                NSDragOperation::Copy
            }
        }

        #[unsafe(method(performDragOperation:))]
        fn perform_drag_operation(&self, sender: &ProtocolObject<dyn NSDraggingInfo>) -> bool {
            let paths = dropped_paths(sender);
            let taken = !paths.is_empty();
            if taken {
                let shared = self.ivars().shared.clone();
                shared.link.host().dropped(paths, shared.clone());
            }
            taken
        }
    }

    /// AppKit's text input: what the layout or an input method types reaches
    /// the phone as text. The composing happens here, the text goes when it is
    /// done; the phone's app has the caret, so there is no text to show here.
    unsafe impl NSTextInputClient for AppView {
        #[unsafe(method(insertText:replacementRange:))]
        fn insert_text(&self, string: &AnyObject, _replacement: NSRange) {
            self.ivars().marked.borrow_mut().clear();
            self.type_text(&text_of(string));
        }

        /// The keys that do things went to the phone as keys already.
        #[unsafe(method(doCommandBySelector:))]
        fn do_command_by_selector(&self, _command: Sel) {}

        #[unsafe(method(setMarkedText:selectedRange:replacementRange:))]
        fn set_marked_text(&self, string: &AnyObject, _selected: NSRange, _replacement: NSRange) {
            *self.ivars().marked.borrow_mut() = text_of(string);
        }

        /// An input method's text is done as it stands: it is typed.
        #[unsafe(method(unmarkText))]
        fn unmark_text(&self) {
            let text = std::mem::take(&mut *self.ivars().marked.borrow_mut());
            self.type_text(&text);
        }

        #[unsafe(method(selectedRange))]
        fn selected_range(&self) -> NSRange {
            NSRange::new(NSNotFound as NSUInteger, 0)
        }

        #[unsafe(method(markedRange))]
        fn marked_range(&self) -> NSRange {
            let marked = self.ivars().marked.borrow();
            if marked.is_empty() {
                NSRange::new(NSNotFound as NSUInteger, 0)
            } else {
                NSRange::new(0, marked.encode_utf16().count())
            }
        }

        #[unsafe(method(hasMarkedText))]
        fn has_marked_text(&self) -> bool {
            !self.ivars().marked.borrow().is_empty()
        }

        #[unsafe(method_id(attributedSubstringForProposedRange:actualRange:))]
        fn attributed_substring(&self, _range: NSRange, _actual: NSRangePointer) -> Option<Retained<NSAttributedString>> {
            None
        }

        #[unsafe(method_id(validAttributesForMarkedText))]
        fn valid_attributes_for_marked_text(&self) -> Retained<NSArray<NSAttributedStringKey>> {
            NSArray::new()
        }

        /// Where an input method puts its candidates: by the last click, the
        /// nearest this computer knows to the phone's caret.
        #[unsafe(method(firstRectForCharacterRange:actualRange:))]
        fn first_rect(&self, _range: NSRange, _actual: NSRangePointer) -> NSRect {
            let at = self.ivars().caret.get();
            let in_window = self.convertRect_toView(NSRect::new(at, NSSize::new(1.0, 20.0)), None);
            match self.window() {
                Some(w) => w.convertRectToScreen(in_window),
                None => in_window,
            }
        }

        #[unsafe(method(characterIndexForPoint:))]
        fn character_index_for_point(&self, _point: NSPoint) -> NSUInteger {
            NSNotFound as NSUInteger
        }
    }

    unsafe impl NSWindowDelegate for AppView {
        #[unsafe(method(windowShouldClose:))]
        fn window_should_close(&self, _sender: &NSWindow) -> bool {
            self.may_close()
        }

        #[unsafe(method(windowWillClose:))]
        fn window_will_close(&self, _notification: &NSNotification) {
            self.closed();
        }

        #[unsafe(method(windowDidBecomeKey:))]
        fn window_did_become_key(&self, _notification: &NSNotification) {
            self.ivars().shared.front.store(true, Ordering::SeqCst);
            let link = &self.ivars().shared.link;
            link.hand_clipboard_over();
            link.send(wire::json(&json!({"t": "focus"})));
        }

        #[unsafe(method(windowDidResignKey:))]
        fn window_did_resign_key(&self, _notification: &NSNotification) {
            self.ivars().shared.front.store(false, Ordering::SeqCst);
            let link = &self.ivars().shared.link;
            for (_, code) in self.ivars().keys_down.borrow_mut().drain() {
                link.send(wire::key(false, 0, code, 0));
            }
            link.send(wire::json(&json!({"t": "blur"})));
        }

        #[unsafe(method(windowDidEndLiveResize:))]
        fn window_did_end_live_resize(&self, _notification: &NSNotification) {
            self.ivars().shared.ask_size();
        }

        /// macOS's full screen, with the menu bar kept away — and the title bar,
        /// which would come down with it: the window's own bar comes down instead.
        #[unsafe(method(window:willUseFullScreenPresentationOptions:))]
        fn full_screen_options(
            &self,
            _window: &NSWindow,
            _proposed: NSApplicationPresentationOptions,
        ) -> NSApplicationPresentationOptions {
            NSApplicationPresentationOptions::FullScreen
                | NSApplicationPresentationOptions::HideDock
                | NSApplicationPresentationOptions::HideMenuBar
        }

        #[unsafe(method(windowWillEnterFullScreen:))]
        fn window_will_enter_full_screen(&self, _notification: &NSNotification) {
            self.ivars().full_screen.set(true);
        }

        #[unsafe(method(windowDidEnterFullScreen:))]
        fn window_did_enter_full_screen(&self, _notification: &NSNotification) {
            self.place_handle();
            if let Some(handle) = self.ivars().handle.borrow().as_ref() {
                handle.setHidden(false);
            }
            // Down for a moment, so the person sees where it is.
            self.bar_down(true);
            self.bar_up_soon(BAR_ON_ARRIVAL);
        }

        #[unsafe(method(windowWillExitFullScreen:))]
        fn window_will_exit_full_screen(&self, _notification: &NSNotification) {
            self.out_of_full_screen();
        }

        #[unsafe(method(windowDidFailToEnterFullScreen:))]
        fn window_did_fail_to_enter_full_screen(&self, _window: &NSWindow) {
            self.out_of_full_screen();
        }
    }
);

impl AppView {
    fn new(mtm: MainThreadMarker, frame: NSRect, shared: Arc<Shared>) -> Retained<Self> {
        let picture = video::new_layer();
        let clip = CALayer::new();
        clip.setMasksToBounds(true);
        clip.addSublayer(&picture);
        shared.feed.lock().unwrap().set_layer(Some(video::Layer(picture.clone())));
        let this = Self::alloc(mtm).set_ivars(Ui {
            shared,
            buttons: Cell::new(0),
            control_click: Cell::new(false),
            keys_down: RefCell::new(HashMap::new()),
            marked: RefCell::new(String::new()),
            caret: Cell::new(NSPoint::new(0.0, 0.0)),
            clip,
            picture,
            message: RefCell::new(None),
            tracking: RefCell::new(None),
            sound: RefCell::new(Vec::new()),
            full_screen: Cell::new(false),
            bar: RefCell::new(None),
            handle: RefCell::new(None),
            bar_down: Cell::new(false),
            bar_leaving: Cell::new(false),
            bar_hide: Cell::new(0),
            menu_open: Cell::new(false),
            esc_held: Cell::new(false),
            gone: Cell::new(false),
            closed: Cell::new(false),
        });
        let view: Retained<Self> = unsafe { msg_send![super(this), initWithFrame: frame] };
        view.setWantsLayer(true);
        if let Some(layer) = view.layer() {
            view.ivars().clip.setFrame(layer.bounds());
            layer.addSublayer(&view.ivars().clip);
        }
        view.registerForDraggedTypes(&NSArray::from_slice(&[unsafe { NSPasteboardTypeFileURL }]));
        view
    }

    /// The sound menu, under the button that asked for it: where the phone's
    /// sound plays. Picking from it is the app's to carry out, off the main
    /// thread; the window's subtitle says how it went.
    fn show_sound_menu(&self, anchor: Option<&NSView>) {
        let mtm = self.mtm();
        let items = self.ivars().shared.link.host().sound_menu();
        if items.is_empty() {
            return;
        }
        let menu = NSMenu::initWithTitle(NSMenu::alloc(mtm), ns_string!(""));
        // Each item says whether it can be picked; AppKit is not to decide by its target.
        menu.setAutoenablesItems(false);
        let target: &AnyObject = self;
        for (i, item) in items.iter().enumerate() {
            match item {
                sound::Item::Separator => menu.addItem(&NSMenuItem::separatorItem(mtm)),
                sound::Item::Choice { label, checked, enabled, .. } => {
                    let m = unsafe {
                        NSMenuItem::initWithTitle_action_keyEquivalent(
                            NSMenuItem::alloc(mtm),
                            &NSString::from_str(label),
                            Some(sel!(pickSound:)),
                            ns_string!(""),
                        )
                    };
                    unsafe { m.setTarget(Some(target)) };
                    m.setTag(i as NSInteger);
                    if *checked {
                        m.setState(NSControlStateValueOn);
                    }
                    m.setEnabled(*enabled);
                    menu.addItem(&m);
                }
            }
        }
        *self.ivars().sound.borrow_mut() = items;
        let anchor: Retained<NSView> = match anchor {
            Some(v) => v.retain(),
            None => (*self).retain().into_super(),
        };
        self.pop_up(&menu, &anchor);
    }

    /// `menu` under `button`: its top left at the button's bottom left,
    /// whichever way the button counts. The full-screen bar stays while it is
    /// open, and goes back up after if the pointer has left it meanwhile.
    fn pop_up(&self, menu: &NSMenu, button: &NSView) {
        let ui = self.ivars();
        let b = button.bounds();
        let y = if button.isFlipped() { b.size.height + 4.0 } else { -4.0 };
        ui.menu_open.set(true);
        menu.popUpMenuPositioningItem_atLocation_inView(None, NSPoint::new(0.0, y), Some(button));
        ui.menu_open.set(false);
        if let Some(w) = self.window() {
            self.track(self.convertPoint_fromView(w.mouseLocationOutsideOfEventStream(), None));
        }
    }

    /// The view changed size or scale: the picture follows at once, and the
    /// phone is asked for the new size once the window holds still — the
    /// phone makes a new encoder for each, and a drag passes through dozens.
    fn resized(&self, settle: bool) {
        let shared = self.ivars().shared.clone();
        let size = self.bounds().size;
        let scale = shared.scale();
        *shared.area.lock().unwrap() = ((size.width * scale).round() as u32, (size.height * scale).round() as u32);
        self.lay_out_picture();
        self.place_handle();
        if !settle {
            return;
        }
        *shared.resized_at.lock().unwrap() = Instant::now();
        if let Ok(when) = DispatchTime::try_from(Duration::from_millis(130)) {
            let _ = DispatchQueue::main().after(when, move || {
                if shared.resized_at.lock().unwrap().elapsed() >= Duration::from_millis(120) {
                    shared.ask_size();
                }
            });
        }
    }

    /// The app's part of the picture, as large as fits in the view, centred —
    /// the view's own shape once the phone has caught up with a resize. The
    /// phone pads what it codes to its encoder's alignment, at the right and
    /// the bottom; the clip is the app's part, and cuts the padding off.
    fn lay_out_picture(&self) {
        let ui = self.ivars();
        let bounds = self.bounds();
        let (view_w, view_h) = (bounds.size.width, bounds.size.height);
        let ((coded_w, coded_h), (app_w, app_h)) = *ui.shared.picture.lock().unwrap();
        let (clip, picture) = if app_w == 0 || app_h == 0 || coded_w < app_w || coded_h < app_h {
            (bounds, NSRect::new(NSPoint::new(0.0, 0.0), bounds.size))
        } else {
            let s = (view_w / app_w as f64).min(view_h / app_h as f64);
            let (w, h) = (app_w as f64 * s, app_h as f64 * s);
            let clip = NSRect::new(NSPoint::new((view_w - w) / 2.0, (view_h - h) / 2.0), NSSize::new(w, h));
            // Layers count from the bottom: the app's corner is the top left.
            let (pw, ph) = (coded_w as f64 * s, coded_h as f64 * s);
            (clip, NSRect::new(NSPoint::new(0.0, h - ph), NSSize::new(pw, ph)))
        };
        CATransaction::begin();
        CATransaction::setDisableActions(true);
        ui.clip.setFrame(clip);
        ui.picture.setFrame(picture);
        CATransaction::commit();
    }

    fn say(&self, text: Option<&str>) {
        if let Some(m) = self.ivars().message.borrow().as_ref() {
            match text {
                Some(t) => {
                    m.setStringValue(&NSString::from_str(t));
                    m.setHidden(false);
                }
                None => m.setHidden(true),
            }
        }
        // A picture that stopped is dimmed under what is said about it: the
        // line is unreadable over a light app's last frame otherwise.
        CATransaction::begin();
        CATransaction::setDisableActions(true);
        self.ivars().picture.setOpacity(if text.is_some() { 0.15 } else { 1.0 });
        CATransaction::commit();
    }

    /// More's menu, under the button that opened it: full screen, for now,
    /// with the Mac's own keys for it beside it.
    fn more(&self, button: &NSView) {
        let ui = self.ivars();
        let mtm = self.mtm();
        let (title, key, held) = if ui.full_screen.get() {
            ("Exit full screen", "\u{1b}", NSEventModifierFlags::empty())
        } else {
            ("Full screen", "f", NSEventModifierFlags::Control | NSEventModifierFlags::Command)
        };
        let item = unsafe {
            NSMenuItem::initWithTitle_action_keyEquivalent(
                NSMenuItem::alloc(mtm),
                &NSString::from_str(title),
                Some(sel!(switchFullScreen:)),
                &NSString::from_str(key),
            )
        };
        item.setKeyEquivalentModifierMask(held);
        let target: &AnyObject = self;
        unsafe { item.setTarget(Some(target)) };
        let menu = NSMenu::new(mtm);
        menu.addItem(&item);
        self.pop_up(&menu, button);
    }

    /// The pointer at `p` in a full-screen window: at the top of the screen
    /// it brings the bar down, and off the bar for a moment it sends it back
    /// up. True if it is on the bar, which the app then does not see.
    fn track(&self, p: NSPoint) -> bool {
        let ui = self.ivars();
        if !ui.full_screen.get() {
            return false;
        }
        let size = self.bounds().size;
        let middle = size.width / 2.0;
        let on_handle = (p.x - middle).abs() < HANDLE_W / 2.0 && p.y >= size.height - HANDLE_H;
        if on_handle || p.y >= size.height - TOP_EDGE {
            self.bar_down(true);
        }
        if !ui.bar_down.get() {
            return false;
        }
        let left = ((size.width - BAR_W) / 2.0).round();
        let on = |margin: f64| p.x >= left - margin && p.x < left + BAR_W + margin && p.y >= size.height - BAR_H - margin;
        if on(BAR_NEAR) {
            self.keep_bar();
        } else {
            self.bar_up_soon(BAR_STAYS);
        }
        on(0.0)
    }

    /// The bar slides down, or back up out of sight.
    fn bar_down(&self, down: bool) {
        let ui = self.ivars();
        self.keep_bar();
        if ui.bar_down.replace(down) == down {
            return;
        }
        let Some(bar) = ui.bar.borrow().as_ref().map(|b| b.view.clone()) else { return };
        let size = self.bounds().size;
        let x = ((size.width - BAR_W) / 2.0).round();
        let up = NSPoint::new(x, size.height + 2.0);
        if down {
            bar.setFrameOrigin(up);
            bar.setHidden(false);
        }
        NSAnimationContext::beginGrouping();
        NSAnimationContext::currentContext().setDuration(0.15);
        // Its rounded top above the screen: only its lower corners show.
        bar.animator().setFrameOrigin(if down { NSPoint::new(x, size.height - BAR_H) } else { up });
        NSAnimationContext::endGrouping();
    }

    /// Whatever was to send the bar back up, does not.
    fn keep_bar(&self) {
        let ui = self.ivars();
        ui.bar_hide.set(ui.bar_hide.get() + 1);
        ui.bar_leaving.set(false);
    }

    /// The bar goes back up `after` this long, unless that is called off first.
    fn bar_up_soon(&self, after: Duration) {
        let ui = self.ivars();
        if !ui.bar_down.get() || ui.bar_leaving.replace(true) {
            return;
        }
        let (id, hide) = (ui.shared.id, ui.bar_hide.get());
        let Ok(when) = DispatchTime::try_from(after) else { return };
        let _ = DispatchQueue::main().after(when, move || {
            let Some(view) = OPEN.with(|o| o.borrow().get(&id).map(|p| p.view.clone())) else { return };
            let ui = view.ivars();
            if ui.bar_hide.get() != hide {
                return;
            }
            ui.bar_leaving.set(false);
            // Not from under an open menu: the bar is looked at again once it closes.
            if !ui.menu_open.get() {
                view.bar_down(false);
            }
        });
    }

    /// The window is leaving full screen, or did not get there: the bar and its tab go at once.
    fn out_of_full_screen(&self) {
        let ui = self.ivars();
        ui.full_screen.set(false);
        self.keep_bar();
        ui.bar_down.set(false);
        if let Some(bar) = ui.bar.borrow().as_ref() {
            bar.view.setHidden(true);
        }
        if let Some(handle) = ui.handle.borrow().as_ref() {
            handle.setHidden(true);
        }
    }

    /// The bar's tab, in the middle of the top, its rounded top above it.
    fn place_handle(&self) {
        if let Some(handle) = self.ivars().handle.borrow().as_ref() {
            let size = self.bounds().size;
            handle.setFrameOrigin(NSPoint::new(((size.width - HANDLE_W) / 2.0).round(), size.height - HANDLE_H));
        }
    }

    /// ⌘ with a key comes here before it is a key down, and the menus'
    /// shortcuts would take it next: the phone's, unless it is one that stays
    /// the Mac's. AppKit does not always send a view the key-up of a ⌘
    /// shortcut, so it goes as a press and a release at once.
    fn shortcut(&self, event: &NSEvent) -> bool {
        let Some(window) = self.window() else { return false };
        let mine = window.firstResponder().is_some_and(|r| std::ptr::eq(&*r, self.as_ref() as &NSResponder));
        let m = mods(event);
        if !window.isKeyWindow() || !mine || !m.command {
            return false;
        }
        let plain = event.charactersIgnoringModifiers().map(|s| s.to_string()).unwrap_or_default();
        if mk::stays_on_mac(&plain, m) {
            return false;
        }
        match mk::press(event.keyCode(), &plain, m) {
            mk::Press::Key(code, meta) => {
                self.key(code, meta, event.isARepeat());
                self.ivars().shared.link.send(wire::key(false, 0, code, meta));
                true
            }
            _ => false,
        }
    }

    /// The ✕. A Linux window's asks the app, which may want to save first; the
    /// window goes when its X window does. A second ✕ soon after closes it
    /// here, for an app that does not answer.
    fn may_close(&self) -> bool {
        let shared = &self.ivars().shared;
        if shared.linux && !self.ivars().gone.get() {
            let mut asked = shared.asked_close.lock().unwrap();
            let again = asked.is_some_and(|at| at.elapsed() < Duration::from_secs(6));
            if !again {
                *asked = Some(Instant::now());
                shared.link.send(wire::json(&json!({"t": "close", "app": "ask"})));
                return false;
            }
        }
        true
    }

    /// The window is closing: its link goes, and so does everything it held.
    fn closed(&self) {
        let ui = self.ivars();
        if ui.closed.replace(true) {
            return;
        }
        let shared = &ui.shared;
        shared.link.close(!ui.gone.get());
        // The layer is let go of here, on the main thread, not by the link's reader.
        drop(shared.feed.lock().unwrap().set_layer(None));
        if let Some(w) = self.window() {
            w.setDelegate(None);
        }
        let id = shared.id;
        // Let go of the window once AppKit is done closing it.
        DispatchQueue::main().exec_async(move || {
            OPEN.with(|o| o.borrow_mut().remove(&id));
        });
        shared.link.event(Event::Closed);
    }

    /// Where an event is, on the phone's display.
    fn on_display(&self, event: &NSEvent) -> (u16, u16) {
        let p = self.convertPoint_fromView(event.locationInWindow(), None);
        let size = self.bounds().size;
        let shared = &self.ivars().shared;
        let s = shared.scale();
        // A view counts from the bottom; the phone from the top.
        let area = *shared.area.lock().unwrap();
        shared.link.to_display(p.x * s, (size.height - p.y) * s, area)
    }

    fn pointer(&self, event: &NSEvent) {
        let (x, y) = self.on_display(event);
        let link = &self.ivars().shared.link;
        link.send(wire::pointer(self.ivars().buttons.get(), x, y, mods(event).meta()));
    }

    fn press(&self, event: &NSEvent, bit: u8, down: bool) {
        let ui = self.ivars();
        if down {
            let p = self.convertPoint_fromView(event.locationInWindow(), None);
            ui.caret.set(p);
            ui.buttons.set(ui.buttons.get() | bit);
        } else {
            if ui.buttons.get() & bit == 0 {
                return;
            }
            ui.buttons.set(ui.buttons.get() & !bit);
        }
        self.pointer(event);
    }

    /// A mouse's third button is its middle one; its side buttons are Back and
    /// Forward, as a real mouse's are on Android.
    fn other(&self, event: &NSEvent, down: bool) {
        match event.buttonNumber() {
            2 => self.press(event, BUTTON_TERTIARY, down),
            3 => self.ivars().shared.link.send(wire::key(down, 0, keys::code::BACK, 0)),
            4 => self.ivars().shared.link.send(wire::key(down, 0, keys::code::FORWARD, 0)),
            _ => {}
        }
    }

    fn key(&self, code: u16, meta: u32, repeat: bool) {
        let link = &self.ivars().shared.link;
        if code == 50 && meta & keys::meta::CTRL != 0 && !repeat {
            // ⌘V: what this Mac copied reaches the phone first.
            link.hand_clipboard_over();
        }
        link.send(wire::key(true, repeat as u8, code, meta));
    }

    fn type_text(&self, text: &str) {
        // Not control characters, nor the private ones AppKit names keys by
        // (F13, Clear…), which no layout types.
        let text: String = text.chars().filter(|c| !c.is_control() && !('\u{F700}'..='\u{F8FF}').contains(c)).collect();
        if !text.is_empty() {
            self.ivars().shared.link.send(wire::text(&text));
        }
    }
}

/// The files a drag carries, as paths. Finder hands over references to them
/// (file:///.file/id=…), which are made paths again here.
fn dropped_paths(sender: &ProtocolObject<dyn NSDraggingInfo>) -> Vec<PathBuf> {
    let board = sender.draggingPasteboard();
    let Some(items) = board.pasteboardItems() else { return Vec::new() };
    items
        .iter()
        .filter_map(|item| {
            let url = item.stringForType(unsafe { NSPasteboardTypeFileURL })?;
            let path = NSURL::URLWithString(&url)?.filePathURL()?.path()?;
            Some(PathBuf::from(path.to_string()))
        })
        .collect()
}

/// What AppKit's text input hands over: an NSString or an NSAttributedString.
fn text_of(string: &AnyObject) -> String {
    if let Some(s) = string.downcast_ref::<NSAttributedString>() {
        return s.string().to_string();
    }
    string.downcast_ref::<NSString>().map(|s| s.to_string()).unwrap_or_default()
}

fn mods(event: &NSEvent) -> mk::Mods {
    let f = event.modifierFlags();
    mk::Mods {
        shift: f.contains(NSEventModifierFlags::Shift),
        control: f.contains(NSEventModifierFlags::Control),
        option: f.contains(NSEventModifierFlags::Option),
        command: f.contains(NSEventModifierFlags::Command),
        caps: f.contains(NSEventModifierFlags::CapsLock),
    }
}
