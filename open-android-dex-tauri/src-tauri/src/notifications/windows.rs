//! The phone's notifications as Windows' own: toasts, through WinRT's toast
//! notifier.
//!
//! Each phone app is a sender of its own: an identity (AppUserModelID) named
//! after the app, with its icon, kept in the registry under
//! `HKCU\Software\Classes\AppUserModelId` — what Windows reads an unpackaged
//! app's notification name and icon from when no Start-menu shortcut says.
//! It is the identity the app's own windows here have on the taskbar
//! (apps/win), so a WhatsApp toast says "WhatsApp" at its top with
//! WhatsApp's icon, stacks under WhatsApp in the notification center, and
//! Windows' own settings can turn WhatsApp off on its own. This app's own
//! toasts — the introduction, a test — are under its own name.
//!
//! A click comes back in this process (the toast's Activated event): what
//! was typed into its reply box with it. That needs no COM activator,
//! which only a click on a toast whose app is not running would: and these
//! are taken out of the notification center when this app goes (`clear`), so
//! there are none. Windows calls a toast's handlers only while something
//! holds the toast, so each is held until it goes.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use tauri::{AppHandle, Manager};
use windows::core::{IInspectable, Interface, HSTRING};
use windows::Data::Xml::Dom::XmlDocument;
use windows::Foundation::{IPropertyValue, TypedEventHandler};
use windows::UI::Notifications::{
    NotificationSetting, ToastActivatedEventArgs, ToastDismissalReason, ToastDismissedEventArgs, ToastFailedEventArgs,
    ToastNotification, ToastNotificationManager, ToastNotificationPriority, ToastNotifier,
};
use windows::Win32::Foundation::ERROR_SUCCESS;
use windows::Win32::System::Registry::{
    RegCloseKey, RegEnumKeyExW, RegOpenKeyExW, RegSetKeyValueW, HKEY, HKEY_CURRENT_USER, KEY_READ, REG_SZ,
};

use super::toast::{self, Act, From, Kind, Toast};

pub const SUPPORTED: bool = true;

/// This app's own sender: the identity its Start-menu shortcut is made with,
/// so its toasts are this app's in Windows' settings as well.
const OURS: &str = "com.ccrstech.openandroiddex";
/// Where an unpackaged app's notification identities are named.
const IDENTITIES: &str = r"Software\Classes\AppUserModelId";
/// The phone apps' identities begin with this (apps/win, `app_user_model_id`).
const PHONE_APPS: &str = "CCRSTech.OpenAndroidDeX.App.";

/// A click on a toast: which, what it meant, and what was typed with it.
pub struct Click {
    pub id: String,
    pub act: Act,
    pub text: Option<String>,
}

static CLICKED: OnceLock<fn(Click)> = OnceLock::new();

/// Every toast up, by its id: Windows calls a toast's handlers only while it is held.
fn live() -> &'static Mutex<HashMap<String, ToastNotification>> {
    static LIVE: OnceLock<Mutex<HashMap<String, ToastNotification>>> = OnceLock::new();
    LIVE.get_or_init(Default::default)
}

/// The identities named this run, and what they were named: written again only when that changes.
fn named() -> &'static Mutex<HashMap<String, (String, String)>> {
    static NAMED: OnceLock<Mutex<HashMap<String, (String, String)>>> = OnceLock::new();
    NAMED.get_or_init(Default::default)
}

pub fn start(app: &AppHandle, clicked: fn(Click)) {
    let _ = CLICKED.set(clicked);
    // A run with no Start-menu shortcut (the portable build, `tauri dev`) has
    // its name and icon from here; an installed one has both already.
    let icon = our_icon(app);
    name(OURS, "Open Android DeX", icon.as_deref());
}

/// This app's icon as a file, for its own identity: Windows reads it from one.
fn our_icon(app: &AppHandle) -> Option<PathBuf> {
    let path = app.path().app_cache_dir().ok()?.join("notification-icon.png");
    if !path.exists() {
        std::fs::create_dir_all(path.parent()?).ok()?;
        std::fs::write(&path, include_bytes!("../../icons/128x128@2x.png")).ok()?;
    }
    Some(path)
}

/// What Windows' notifications allow this app now.
pub fn system() -> (&'static str, String) {
    match notifier(OURS).and_then(|n| n.Setting()) {
        Ok(NotificationSetting::DisabledForApplication) => {
            ("off", "Windows has notifications turned off for Open Android DeX.".into())
        }
        Ok(NotificationSetting::DisabledForUser) => ("off", "Notifications are turned off in Windows' settings.".into()),
        Ok(NotificationSetting::DisabledByGroupPolicy) => {
            ("unavailable", "Your organisation has turned notifications off on this PC.".into())
        }
        Ok(NotificationSetting::DisabledByManifest) => ("unavailable", "Windows won't show this app's notifications.".into()),
        // Enabled, or Windows would not say: shown, and it says if it refuses one.
        _ => ("ready", String::new()),
    }
}

/// Whether Windows shows any notification now. Turned off for this app
/// alone, it still shows the phone apps', which are senders of their own;
/// turned off for everything, or by the organisation, it shows none.
pub fn shows_any() -> bool {
    !matches!(
        notifier(OURS).and_then(|n| n.Setting()),
        Ok(NotificationSetting::DisabledForUser | NotificationSetting::DisabledByGroupPolicy)
    )
}

/// Nothing to ask on Windows: notifications are allowed until turned off.
pub fn ask(_app: &AppHandle) {}

pub fn settings_url() -> Option<&'static str> {
    Some("ms-settings:notifications")
}

/// Whether Windows' settings let `pkg`'s notifications through: each phone
/// app can be turned off there on its own, like any app.
pub fn allowed(pkg: &str) -> bool {
    !matches!(
        notifier(&crate::apps::app_user_model_id(pkg)).and_then(|n| n.Setting()),
        Ok(NotificationSetting::DisabledForApplication | NotificationSetting::DisabledForUser)
    )
}

fn notifier(id: &str) -> windows::core::Result<ToastNotifier> {
    ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(id))
}

/// The identity a toast goes out under, named (and its icon set) if that changed.
fn identity(from: &From) -> String {
    match from {
        From::Us => OURS.to_string(),
        From::Phone { pkg, label, icon } => {
            let id = crate::apps::app_user_model_id(pkg);
            name(&id, label, icon.as_deref());
            id
        }
    }
}

pub fn show(t: &Toast) -> Result<(), String> {
    let id = identity(&t.from);
    let toast = build(t).map_err(|e| format!("the toast: {e}"))?;
    notifier(&id).and_then(|n| n.Show(&toast)).map_err(|e| format!("Windows refused it: {e}"))?;
    live().lock().unwrap().insert(t.id.clone(), toast);
    Ok(())
}

fn build(t: &Toast) -> windows::core::Result<ToastNotification> {
    let doc = XmlDocument::new()?;
    doc.LoadXml(&HSTRING::from(toast::windows_xml(t)))?;
    let toast = ToastNotification::CreateToastNotification(&doc)?;
    toast.SetTag(&HSTRING::from(&t.id))?;
    toast.SetGroup(&HSTRING::from(&t.group))?;
    // What the phone said yesterday is not news after a restart.
    toast.SetExpiresOnReboot(true)?;
    if !t.popup {
        toast.SetSuppressPopup(true)?;
    }
    if t.kind == Kind::Call {
        toast.SetPriority(ToastNotificationPriority::High)?;
    }

    let id = t.id.clone();
    toast.Activated(&TypedEventHandler::<ToastNotification, IInspectable>::new(move |sender, args| {
        let Some(args) = args.as_ref() else { return Ok(()) };
        let args: ToastActivatedEventArgs = args.cast()?;
        let code = args.Arguments()?.to_string();
        let text = args
            .UserInput()
            .ok()
            .and_then(|input| input.Lookup(&HSTRING::from("reply")).ok())
            .and_then(|v| v.cast::<IPropertyValue>().ok())
            .and_then(|v| v.GetString().ok())
            .map(|s| s.to_string());
        let_go(&id, sender.as_ref());
        match Act::parse(&code) {
            Some(act) => deliver(Click { id: id.clone(), act, text }),
            None => log::warn!("notifications: a click Windows says was \"{code}\""),
        }
        Ok(())
    }))?;

    let id = t.id.clone();
    toast.Dismissed(&TypedEventHandler::<ToastNotification, ToastDismissedEventArgs>::new(move |sender, args| {
        let Some(args) = args.as_ref() else { return Ok(()) };
        match args.Reason()? {
            // Closed by the person: theirs to say what that means (clear it on the phone too?).
            ToastDismissalReason::UserCanceled => {
                if let_go(&id, sender.as_ref()) {
                    deliver(Click { id: id.clone(), act: Act::Dismissed, text: None });
                }
            }
            // Taken down from here, or replaced by a newer one under the same id.
            ToastDismissalReason::ApplicationHidden => {
                let_go(&id, sender.as_ref());
            }
            // Gone quietly into the notification center, where a click still reaches it: held.
            _ => {}
        }
        Ok(())
    }))?;

    let id = t.id.clone();
    toast.Failed(&TypedEventHandler::<ToastNotification, ToastFailedEventArgs>::new(move |sender, args| {
        if let Some(args) = args.as_ref() {
            log::warn!("notifications: Windows could not show {id}: {:?}", args.ErrorCode());
        }
        let_go(&id, sender.as_ref());
        Ok(())
    }))?;
    Ok(toast)
}

/// A toast no longer held — if it is still the one held under its id: a
/// newer toast for the same notification (the next message in a
/// conversation) replaces it, and the old one's last word must not let the
/// new one go, or a click on the new one would never come back. True if it was.
fn let_go(id: &str, toast: Option<&ToastNotification>) -> bool {
    let Some(toast) = toast else { return false };
    let mut live = live().lock().unwrap();
    if live.get(id).is_some_and(|held| same(held, toast)) {
        live.remove(id);
        return true;
    }
    false
}

/// The same toast: COM's own test, the two IUnknowns compared.
fn same(a: &ToastNotification, b: &ToastNotification) -> bool {
    match (a.cast::<windows::core::IUnknown>(), b.cast::<windows::core::IUnknown>()) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

fn deliver(c: Click) {
    if let Some(clicked) = CLICKED.get() {
        clicked(c);
    }
}

/// A toast down: off the screen if it is up, and out of the notification center.
pub fn hide(id: &str, group: &str, from: &From) {
    let aumid = match from {
        From::Us => OURS.to_string(),
        From::Phone { pkg, .. } => crate::apps::app_user_model_id(pkg),
    };
    // Not held while Windows takes it down: its Dismissed handler takes the same lock.
    let held = live().lock().unwrap().remove(id);
    if let Some(toast) = held {
        let _ = notifier(&aumid).and_then(|n| n.Hide(&toast));
    }
    if let Ok(history) = ToastNotificationManager::History() {
        let _ = history.RemoveGroupedTagWithId(&HSTRING::from(id), &HSTRING::from(group), &HSTRING::from(aumid));
    }
}

/// Everything this app has in the notification center, gone: at start (what
/// an earlier run left, which nothing can act on now) and at exit.
pub fn clear() {
    live().lock().unwrap().clear();
    let Ok(history) = ToastNotificationManager::History() else { return };
    let _ = history.ClearWithId(&HSTRING::from(OURS));
    for id in phone_identities() {
        let _ = history.ClearWithId(&HSTRING::from(id));
    }
}

/// The phone apps' identities named so far, this run or an earlier one.
fn phone_identities() -> Vec<String> {
    let mut out = Vec::new();
    unsafe {
        let mut key = HKEY::default();
        if RegOpenKeyExW(HKEY_CURRENT_USER, &HSTRING::from(IDENTITIES), None, KEY_READ, &mut key) != ERROR_SUCCESS {
            return out;
        }
        let mut name = [0u16; 256];
        for i in 0.. {
            let mut len = name.len() as u32;
            let r = RegEnumKeyExW(key, i, Some(windows::core::PWSTR(name.as_mut_ptr())), &mut len, None, None, None, None);
            if r != ERROR_SUCCESS {
                break;
            }
            let id = String::from_utf16_lossy(&name[..len as usize]);
            if id.starts_with(PHONE_APPS) {
                out.push(id);
            }
        }
        let _ = RegCloseKey(key);
    }
    out
}

/// An identity's name and icon, where Windows reads an unpackaged app's from.
fn name(id: &str, label: &str, icon: Option<&Path>) {
    let icon = icon.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
    {
        let named = named().lock().unwrap();
        if named.get(id).is_some_and(|(l, i)| l == label && *i == icon) {
            return;
        }
    }
    let key = format!(r"{IDENTITIES}\{id}");
    let ok = set_string(&key, "DisplayName", label) && (icon.is_empty() || set_string(&key, "IconUri", &icon));
    if !ok {
        log::warn!("notifications: could not name {id} for Windows");
        return;
    }
    named().lock().unwrap().insert(id.to_string(), (label.to_string(), icon));
}

/// One string value of a key under HKEY_CURRENT_USER — the key made, if it is not there.
fn set_string(key: &str, name: &str, value: &str) -> bool {
    let wide: Vec<u16> = value.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        RegSetKeyValueW(
            HKEY_CURRENT_USER,
            &HSTRING::from(key),
            &HSTRING::from(name),
            REG_SZ.0,
            Some(wide.as_ptr() as *const core::ffi::c_void),
            (wide.len() * 2) as u32,
        ) == ERROR_SUCCESS
    }
}
