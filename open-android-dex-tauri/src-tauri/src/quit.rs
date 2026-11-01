//! Closing the main window quits the app, and everything it has open goes
//! with it: every phone's desktop, every phone app's window here, every Linux
//! desktop. Its close button is one click, beside Minimize, and a person who
//! hit it by accident with a desktop and two apps open lost all three at once
//! (2026-10-31). So with something open the close waits for a word first: the
//! window asks (ClosePrompt.tsx), and the app quits only if told to.
//!
//! The question is the page's to draw, but this side holds the close. A page
//! that cannot answer, its webview hung, must not leave a window that will
//! never close: a question the page has not said it is showing within
//! [`SEEN_WITHIN`] is taken as unasked, and the app quits as it always did.
//! Every close asked for while the question is up asks again, so a page that
//! hangs after showing it still lets the next press of ✕ through.
//!
//! What is open is read off the main thread: the session map is held across
//! slow work elsewhere, and the main thread is the window's.

use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};

/// The window whose close is the app's.
const MAIN: &str = "main";

/// The event that puts the question up, with what would end.
const ASKED: &str = "app:close-asked";

/// How long the page has to say the question is up before the app quits
/// anyway. A webview answers an event in milliseconds; one that has not in
/// this long is not going to.
const SEEN_WITHIN: Duration = Duration::from_secs(3);

/// What quitting now would end.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Open {
    /// Phones whose desktop is up or coming up, by adb serial: the page knows their names.
    pub desktops: Vec<String>,
    /// Phone apps in windows of their own here, by name.
    pub apps: Vec<String>,
    /// Linux desktops in windows of their own.
    pub linux_desktops: usize,
}

impl Open {
    fn is_empty(&self) -> bool {
        self.desktops.is_empty() && self.apps.is_empty() && self.linux_desktops == 0
    }

    /// For the log.
    fn describe(&self) -> String {
        let mut parts = Vec::new();
        if !self.desktops.is_empty() {
            parts.push(format!("DeX on {}", self.desktops.join(", ")));
        }
        if !self.apps.is_empty() {
            parts.push(format!("{} app window(s): {}", self.apps.len(), self.apps.join(", ")));
        }
        if self.linux_desktops > 0 {
            parts.push(format!("{} Linux desktop(s)", self.linux_desktops));
        }
        parts.join("; ")
    }
}

/// Where the question stands.
#[derive(Debug, Default)]
struct Ask {
    /// Which asking this is, so a wait only acts on its own.
    round: u64,
    /// Asked, and not answered yet.
    open: bool,
    /// The page has said the question is up.
    seen: bool,
    /// Quitting: nothing holds a close any more.
    quitting: bool,
}

impl Ask {
    /// Asked (again): this asking's round, unseen until the page says.
    fn begin(&mut self) -> u64 {
        self.round += 1;
        self.open = true;
        self.seen = false;
        self.round
    }

    fn seen(&mut self) {
        if self.open {
            self.seen = true;
        }
    }

    /// Answered, either way: no wait left running acts.
    fn answered(&mut self) {
        self.open = false;
        self.seen = false;
        self.round += 1;
    }

    /// `round` is still the asking, and the page never said it was showing it.
    fn unseen(&self, round: u64) -> bool {
        self.open && !self.seen && self.round == round
    }
}

static ASK: Mutex<Ask> = Mutex::new(Ask { round: 0, open: false, seen: false, quitting: false });

fn ask() -> std::sync::MutexGuard<'static, Ask> {
    ASK.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// A window was asked to close. The main window's close is held, and what is
/// open decides it: nothing, and the app quits as before; something, and the
/// window asks.
pub fn close_requested(window: &tauri::Window, api: &tauri::CloseRequestApi) {
    if window.label() != MAIN || ask().quitting {
        return;
    }
    api.prevent_close();
    let app = window.app_handle().clone();
    thread::spawn(move || ask_or_quit(&app));
}

fn ask_or_quit(app: &AppHandle) {
    let open = open_now(app);
    if open.is_empty() {
        log::info!("close: nothing open to lose; quitting");
        quit(app);
        return;
    }
    let round = ask().begin();
    // In front, wherever it was: minimized, hidden behind a phone app's
    // window, or closed from the taskbar without being looked at.
    if let Some(w) = app.get_webview_window(MAIN) {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
    log::info!("close: asking first ({})", open.describe());
    if let Err(e) = app.emit_to(MAIN, ASKED, &open) {
        log::warn!("close: could not ask the window ({e}); quitting");
        quit(app);
        return;
    }
    thread::sleep(SEEN_WITHIN);
    if ask().unseen(round) {
        log::warn!("close: the window did not show the question in {}s; quitting anyway", SEEN_WITHIN.as_secs());
        quit(app);
    }
}

/// Everything that quitting now would end.
fn open_now(app: &AppHandle) -> Open {
    let (desktops, mut apps) = crate::scrcpy::running(app);
    apps.extend(crate::apps::apps_windows().into_iter().map(|w| w.label));
    let linux_desktops = app.webview_windows().keys().filter(|label| label.starts_with("desktop-")).count();
    Open { desktops, apps, linux_desktops }
}

fn quit(app: &AppHandle) {
    ask().quitting = true;
    app.exit(0);
}

/// The page has the question up.
#[tauri::command]
pub fn app_close_seen() {
    ask().seen();
}

/// The person's answer: quit, or keep everything open.
#[tauri::command]
pub fn app_close_answer(app: AppHandle, close: bool) {
    ask().answered();
    if close {
        log::info!("close: quitting, as asked");
        quit(&app);
    } else {
        log::info!("close: kept open, as asked");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nothing_open_is_nothing_to_ask_about() {
        assert!(Open::default().is_empty());
        assert!(!Open { desktops: vec!["R58T51N3G2X".into()], ..Default::default() }.is_empty());
        assert!(!Open { apps: vec!["Clock".into()], ..Default::default() }.is_empty());
        assert!(!Open { linux_desktops: 1, ..Default::default() }.is_empty());
    }

    #[test]
    fn the_log_says_what_would_end() {
        let open = Open {
            desktops: vec!["R58T51N3G2X".into()],
            apps: vec!["Clock".into(), "Samsung Internet".into()],
            linux_desktops: 1,
        };
        assert_eq!(open.describe(), "DeX on R58T51N3G2X; 2 app window(s): Clock, Samsung Internet; 1 Linux desktop(s)");
    }

    /// A question the page never showed quits; one it showed waits for the answer.
    #[test]
    fn only_an_unseen_question_quits_by_itself() {
        let mut a = Ask::default();
        let r = a.begin();
        assert!(a.unseen(r));
        a.seen();
        assert!(!a.unseen(r));
        a.answered();
        assert!(!a.unseen(r));
    }

    /// A wait from an earlier asking never acts on a later one.
    #[test]
    fn an_old_wait_leaves_a_new_question_alone() {
        let mut a = Ask::default();
        let first = a.begin();
        a.seen();
        a.answered(); // kept open
        let second = a.begin(); // ✕ again, a moment later
        assert!(!a.unseen(first), "the first wait ends with its own question");
        assert!(a.unseen(second));
    }

    /// ✕ pressed again while the question is up asks again, so a page that
    /// hung after showing it is caught by the new wait.
    #[test]
    fn asking_again_needs_the_page_again() {
        let mut a = Ask::default();
        let first = a.begin();
        a.seen();
        let second = a.begin();
        assert!(!a.unseen(first));
        assert!(a.unseen(second), "a page that no longer answers lets the close through");
        a.seen();
        assert!(!a.unseen(second));
    }

    #[test]
    fn a_seen_with_nothing_asked_changes_nothing() {
        let mut a = Ask::default();
        a.seen();
        assert!(!a.seen);
    }
}
