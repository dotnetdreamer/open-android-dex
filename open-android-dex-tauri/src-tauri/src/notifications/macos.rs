//! The phone's notifications as the Mac's own: banners, and the Notification
//! Center, through UNUserNotificationCenter.
//!
//! A Mac shows every notification under the app that posts it — this one's
//! name and icon — so the phone app's name leads the subtitle ("WhatsApp ·
//! Galaxy A13"), the sender's picture is the banner's thumbnail (else a
//! photo the message carries, else the phone app's own icon), and each phone
//! app's notifications are a thread of their own, stacked together. A Mac
//! declares a notification's buttons up front, by category, rather than with
//! each one: so each set of buttons a phone app uses — its reply box, its own
//! buttons, "Turn off for …" — is a category, made the first time it is seen.
//!
//! The center needs this app's bundle: `tauri dev` runs a bare binary, and
//! there the panel says notifications come from the installed app. It asks
//! the person once whether this app may notify, when notifications are first
//! turned on with a phone attached; a banner is shown while this app is in
//! front too, since its windows are the phone's apps.

use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};

use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::{Bool, NSObject, NSObjectProtocol};
use objc2::{define_class, msg_send, sel, AnyThread};
use objc2_foundation::{ns_string, NSArray, NSBundle, NSError, NSSet, NSString, NSURL};
use objc2_user_notifications::{
    UNAuthorizationOptions, UNAuthorizationStatus, UNMutableNotificationContent, UNNotification, UNNotificationAction,
    UNNotificationActionOptions, UNNotificationAttachment, UNNotificationCategory, UNNotificationCategoryOptions,
    UNNotificationDefaultActionIdentifier, UNNotificationDismissActionIdentifier, UNNotificationInterruptionLevel,
    UNNotificationPresentationOptions, UNNotificationRequest, UNNotificationResponse, UNNotificationSettings,
    UNNotificationSound, UNTextInputNotificationAction, UNTextInputNotificationResponse, UNUserNotificationCenter,
    UNUserNotificationCenterDelegate,
};
use tauri::{AppHandle, Manager};

use super::toast::{Act, From, Kind, Toast};

pub const SUPPORTED: bool = true;

/// A click on a banner: which, what it meant, and what was typed with it.
pub struct Click {
    pub id: String,
    pub act: Act,
    pub text: Option<String>,
}

static CLICKED: OnceLock<fn(Click)> = OnceLock::new();
/// Where a banner's pictures are copied to before the center takes them (it moves what it is given).
static SPOOL: OnceLock<PathBuf> = OnceLock::new();
/// What the center allows, as it last said: its answers come on a queue of its own.
static STATUS: Mutex<(&'static str, String)> = Mutex::new(("ready", String::new()));
/// macOS's question — may this app notify? — is on screen, unanswered. Until
/// it is answered the center says "denied" (seen on macOS 26), and the panel
/// would send the person to System Settings over a question they are being asked.
static ASKING: AtomicBool = AtomicBool::new(false);

/// One set of buttons a phone app's notifications use, as a category.
#[derive(Clone, PartialEq)]
struct Category {
    id: String,
    /// The reply box's act, its button's title and its placeholder; then each button's act and title.
    reply: Option<(String, String, String)>,
    buttons: Vec<(String, String, bool)>,
}

/// Every category made this run: a Mac replaces the whole set whenever one is added.
static CATEGORIES: Mutex<Vec<Category>> = Mutex::new(Vec::new());

/// The newest banner asked for under each id, by a number of its own. One
/// that waits for the center's categories goes up only if it is still the
/// newest when they are in — not after its notification went (`hide`), and
/// not over a newer one put up in its place meanwhile: a banner redrawn
/// without what the notification says must not be covered by the one that
/// said it. Held while a banner is handed to the center or taken from it.
fn latest() -> MutexGuard<'static, HashMap<String, u64>> {
    static LATEST: OnceLock<Mutex<HashMap<String, u64>>> = OnceLock::new();
    LATEST.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner())
}
static ASKED: AtomicU64 = AtomicU64::new(0);

/// This run is the app bundle — the only kind of process the center serves.
fn bundled() -> bool {
    NSBundle::mainBundle().bundleIdentifier().is_some()
}

fn center() -> Option<Retained<UNUserNotificationCenter>> {
    bundled().then(UNUserNotificationCenter::currentNotificationCenter)
}

define_class!(
    // SAFETY: NSObject asks nothing of a subclass; the delegate has no state.
    #[unsafe(super(NSObject))]
    #[name = "OpenAndroidDeXNotificationDelegate"]
    struct Delegate;

    unsafe impl NSObjectProtocol for Delegate {}

    unsafe impl UNUserNotificationCenterDelegate for Delegate {
        /// Shown while this app is the one in front as well: its windows are the phone's apps.
        #[unsafe(method(userNotificationCenter:willPresentNotification:withCompletionHandler:))]
        fn will_present(
            &self,
            _center: &UNUserNotificationCenter,
            _notification: &UNNotification,
            handler: &block2::DynBlock<dyn Fn(UNNotificationPresentationOptions)>,
        ) {
            handler.call((UNNotificationPresentationOptions::Banner
                | UNNotificationPresentationOptions::List
                | UNNotificationPresentationOptions::Sound,));
        }

        #[unsafe(method(userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:))]
        fn did_receive(
            &self,
            _center: &UNUserNotificationCenter,
            response: &UNNotificationResponse,
            handler: &block2::DynBlock<dyn Fn()>,
        ) {
            let id = response.notification().request().identifier().to_string();
            let action = response.actionIdentifier().to_string();
            let text = response.downcast_ref::<UNTextInputNotificationResponse>().map(|r| r.userText().to_string());
            let (open, dismissed) =
                unsafe { (UNNotificationDefaultActionIdentifier.to_string(), UNNotificationDismissActionIdentifier.to_string()) };
            let act = if action == open {
                Some(Act::Open)
            } else if action == dismissed {
                Some(Act::Dismissed)
            } else {
                Act::parse(&action)
            };
            if let (Some(act), Some(clicked)) = (act, CLICKED.get()) {
                clicked(Click { id, act, text });
            }
            handler.call(());
        }
    }
);

pub fn start(app: &AppHandle, clicked: fn(Click)) {
    let _ = CLICKED.set(clicked);
    if let Ok(dir) = app.path().app_cache_dir() {
        let _ = SPOOL.set(dir.join("notification-attachments"));
    }
    let Some(center) = center() else {
        log::info!("notifications: this run is not the app bundle, so the Mac's notifications are out of reach");
        return;
    };
    let delegate: Retained<Delegate> = unsafe { msg_send![Delegate::alloc(), init] };
    center.setDelegate(Some(objc2::runtime::ProtocolObject::from_ref(&*delegate)));
    // The center holds its delegate weakly: this one lives as long as the app.
    std::mem::forget(delegate);
    refresh();
}

/// Ask the center what it allows, for the next `system()`; the panel hears when it changes.
fn refresh() {
    let Some(center) = center() else { return };
    let block = RcBlock::new(|settings: std::ptr::NonNull<UNNotificationSettings>| {
        let settings = unsafe { settings.as_ref() };
        let now = status_of(settings.authorizationStatus(), ASKING.load(Ordering::SeqCst));
        let changed = {
            let mut status = STATUS.lock().unwrap();
            let changed = *status != now;
            *status = now;
            changed
        };
        if changed {
            super::system_changed();
        }
    });
    center.getNotificationSettingsWithCompletionHandler(&block);
}

/// What the center's answer means here: `asking` while its question is on screen.
fn status_of(status: UNAuthorizationStatus, asking: bool) -> (&'static str, String) {
    match status {
        UNAuthorizationStatus::NotDetermined => {
            ("ask", "macOS will ask once whether Open Android DeX may show notifications.".to_string())
        }
        UNAuthorizationStatus::Denied if asking => (
            "ask",
            "macOS is asking whether Open Android DeX may show notifications. Answer it at the top right of the screen."
                .to_string(),
        ),
        UNAuthorizationStatus::Denied => {
            ("off", "Notifications are turned off for Open Android DeX in System Settings.".to_string())
        }
        _ => ("ready", String::new()),
    }
}

pub fn system() -> (&'static str, String) {
    if !bundled() {
        return ("unavailable", "Notifications come from the installed app, not from a development run.".into());
    }
    refresh();
    STATUS.lock().unwrap().clone()
}

/// Whether the Mac shows any of this app's notifications now — or may, once
/// asked. What it says is the center's last answer; the next look has its newest.
pub fn shows_any() -> bool {
    if !bundled() {
        return false;
    }
    refresh();
    STATUS.lock().unwrap().0 != "off"
}

/// The person asked, once: may this app show notifications? Their answer is
/// the system's to keep; after a no, only System Settings changes it.
pub fn ask(_app: &AppHandle) {
    let Some(center) = center() else { return };
    // One question at a time: the one on screen answers for every asker.
    if ASKING.swap(true, Ordering::SeqCst) {
        return;
    }
    let block = RcBlock::new(|granted: Bool, _error: *mut NSError| {
        ASKING.store(false, Ordering::SeqCst);
        log::info!("notifications: macOS says this app {} show notifications", if granted.as_bool() { "may" } else { "may not" });
        refresh();
    });
    center.requestAuthorizationWithOptions_completionHandler(
        UNAuthorizationOptions::Alert | UNAuthorizationOptions::Sound | UNAuthorizationOptions::Badge,
        &block,
    );
}

pub fn settings_url() -> Option<&'static str> {
    Some("x-apple.systempreferences:com.apple.preference.notifications")
}

/// A Mac has no per-app switch for what this app shows: the panel's are the only ones.
pub fn allowed(_pkg: &str) -> bool {
    true
}

pub fn show(t: &Toast) -> Result<(), String> {
    let center = center().ok_or("the Mac's notifications need the app bundle")?;
    let content = UNMutableNotificationContent::new();
    content.setTitle(&NSString::from_str(&t.title));
    let app = match &t.from {
        From::Phone { label, .. } => label.as_str(),
        From::Us => "",
    };
    let subtitle: Vec<&str> = [app, t.attribution.as_str()].into_iter().filter(|s| !s.is_empty()).collect();
    content.setSubtitle(&NSString::from_str(&subtitle.join(" · ")));
    content.setBody(&NSString::from_str(&t.body));
    // Each phone app's notifications stack together.
    let thread = match &t.from {
        From::Phone { pkg, .. } => format!("{}-{pkg}", t.group),
        From::Us => "open-android-dex".into(),
    };
    content.setThreadIdentifier(&NSString::from_str(&thread));
    let mut new_category = false;
    if let Some((category, new)) = category_for(t) {
        content.setCategoryIdentifier(&NSString::from_str(&category));
        new_category = new;
    }
    if !t.silent {
        content.setSound(Some(&UNNotificationSound::defaultSound()));
    }
    // An update to one already there goes into the list without a banner, where macOS has the means (12).
    if !t.popup && content.respondsToSelector(sel!(setInterruptionLevel:)) {
        content.setInterruptionLevel(UNNotificationInterruptionLevel::Passive);
    }
    let icon = match &t.from {
        From::Phone { icon, .. } => icon.clone(),
        From::Us => None,
    };
    if let Some(image) = t.avatar.clone().or_else(|| t.picture.clone()).or(icon) {
        if let Some(attachment) = attachment(&t.id, &image) {
            content.setAttachments(&NSArray::from_retained_slice(&[attachment]));
        }
    }
    let request = UNNotificationRequest::requestWithIdentifier_content_trigger(&NSString::from_str(&t.id), &content, None);
    let id = t.id.clone();
    let done = RcBlock::new(move |error: *mut NSError| {
        if let Some(error) = unsafe { error.as_ref() } {
            log::warn!("notifications: macOS would not show {id}: {}", error.localizedDescription());
        }
    });
    let number = ASKED.fetch_add(1, Ordering::SeqCst);
    let mut newest = latest();
    newest.insert(t.id.clone(), number);
    if new_category {
        drop(newest);
        // The center takes a new set of categories in its own time, and a
        // banner that came first would have none of its buttons: it goes once
        // the center can say what it has, which it says only after that — if
        // it is still the newest under its id then.
        let poster = center.clone();
        let id = t.id.clone();
        let after = RcBlock::new(move |_: std::ptr::NonNull<NSSet<UNNotificationCategory>>| {
            let newest = latest();
            if newest.get(&id) == Some(&number) {
                poster.addNotificationRequest_withCompletionHandler(&request, Some(&done));
            }
        });
        center.getNotificationCategoriesWithCompletionHandler(&after);
    } else {
        center.addNotificationRequest_withCompletionHandler(&request, Some(&done));
    }
    Ok(())
}

/// A picture for a banner. The center moves the file it is given into its
/// own store, so it is given a copy.
fn attachment(id: &str, image: &Path) -> Option<Retained<UNNotificationAttachment>> {
    let spool = SPOOL.get()?;
    std::fs::create_dir_all(spool).ok()?;
    let ext = image.extension().and_then(|e| e.to_str()).unwrap_or("png");
    let copy = spool.join(format!("{id}-{}.{ext}", hash(&image.to_string_lossy())));
    std::fs::copy(image, &copy).ok()?;
    let url = NSURL::fileURLWithPath(&NSString::from_str(&copy.to_string_lossy()));
    match unsafe { UNNotificationAttachment::attachmentWithIdentifier_URL_options_error(ns_string!("picture"), &url, None) } {
        Ok(a) => Some(a),
        Err(e) => {
            log::debug!("notifications: no picture for {id}: {}", e.localizedDescription());
            let _ = std::fs::remove_file(&copy);
            None
        }
    }
}

/// The category for a toast's buttons — made, and the whole set given to the
/// center again, the first time a set is seen (then true). None for a toast with none.
fn category_for(t: &Toast) -> Option<(String, bool)> {
    if t.reply.is_none() && t.buttons.is_empty() {
        return None;
    }
    let reply = t.reply.as_ref().map(|r| (r.act.clone(), r.label.clone(), r.placeholder.clone()));
    let buttons: Vec<(String, String, bool)> =
        t.buttons.iter().map(|b| (b.act.clone(), b.label.clone(), b.menu && t.kind == Kind::Message)).collect();
    let mut h = DefaultHasher::new();
    (&reply, &buttons).hash(&mut h);
    let id = format!("c{:016x}", h.finish());
    let mut all = CATEGORIES.lock().unwrap();
    if all.iter().any(|c| c.id == id) {
        return Some((id, false));
    }
    all.push(Category { id: id.clone(), reply, buttons });
    if let Some(center) = center() {
        let made: Vec<Retained<UNNotificationCategory>> = all.iter().map(make_category).collect();
        center.setNotificationCategories(&NSSet::from_retained_slice(&made));
    }
    Some((id, true))
}

fn make_category(c: &Category) -> Retained<UNNotificationCategory> {
    let mut actions: Vec<Retained<UNNotificationAction>> = Vec::new();
    if let Some((act, title, placeholder)) = &c.reply {
        let reply = UNTextInputNotificationAction::actionWithIdentifier_title_options_textInputButtonTitle_textInputPlaceholder(
            &NSString::from_str(act),
            &NSString::from_str(title),
            UNNotificationActionOptions::empty(),
            ns_string!("Send"),
            &NSString::from_str(placeholder),
        );
        actions.push(Retained::into_super(reply));
    }
    for (act, title, off) in &c.buttons {
        // "Turn off for …" in red, as a Mac marks what takes something away.
        let options = if *off { UNNotificationActionOptions::Destructive } else { UNNotificationActionOptions::empty() };
        actions.push(UNNotificationAction::actionWithIdentifier_title_options(&NSString::from_str(act), &NSString::from_str(title), options));
    }
    UNNotificationCategory::categoryWithIdentifier_actions_intentIdentifiers_options(
        &NSString::from_str(&c.id),
        &NSArray::from_retained_slice(&actions),
        &NSArray::new(),
        // Its closing is heard too: the person may want it gone from the phone as well.
        UNNotificationCategoryOptions::CustomDismissAction,
    )
}

/// A banner down: shown, or still on its way — a request the center has not
/// delivered yet, or one waiting for its categories, which now never goes.
pub fn hide(id: &str, _group: &str, _from: &From) {
    let mut newest = latest();
    newest.remove(id);
    if let Some(center) = center() {
        let ids = NSArray::from_retained_slice(&[NSString::from_str(id)]);
        center.removePendingNotificationRequestsWithIdentifiers(&ids);
        center.removeDeliveredNotificationsWithIdentifiers(&ids);
    }
    drop(newest);
    forget_copies(id);
}

/// Everything this app has in the Notification Center, gone — and on its way there.
pub fn clear() {
    let mut newest = latest();
    newest.clear();
    if let Some(center) = center() {
        center.removeAllPendingNotificationRequests();
        center.removeAllDeliveredNotifications();
    }
    drop(newest);
    if let Some(spool) = SPOOL.get() {
        let _ = std::fs::remove_dir_all(spool);
    }
}

fn forget_copies(id: &str) {
    let Some(spool) = SPOOL.get() else { return };
    let Ok(entries) = std::fs::read_dir(spool) else { return };
    let prefix = format!("{id}-");
    for e in entries.flatten() {
        if e.file_name().to_string_lossy().starts_with(&prefix) {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

fn hash(s: &str) -> String {
    let mut h = DefaultHasher::new();
    s.hash(&mut h);
    format!("{:08x}", h.finish() as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_question_on_screen_is_not_a_no() {
        // macOS says "denied" while its question is unanswered: the panel says
        // it is asking, rather than sending the person to System Settings.
        assert_eq!(status_of(UNAuthorizationStatus::Denied, true).0, "ask");
        assert!(status_of(UNAuthorizationStatus::Denied, true).1.contains("is asking"));
        assert_eq!(status_of(UNAuthorizationStatus::Denied, false).0, "off");
        assert_eq!(status_of(UNAuthorizationStatus::NotDetermined, false).0, "ask");
        assert_eq!(status_of(UNAuthorizationStatus::Authorized, true).0, "ready");
        assert_eq!(status_of(UNAuthorizationStatus::Provisional, false).0, "ready");
    }
}
