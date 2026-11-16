// here.js: a KWin script the phone's helper loads while a computer has a
// screen of the phone's, so an app opened from a screen shows on that screen.
// build.sh embeds it in the helper (here-js.h); the helper writes it to the
// user's cache and has KWin load it (org.kde.kwin.Scripting) when the
// computer's screen is up, and unload it when that screen goes.
//
// Plasma Mobile's app drawer and dock switch to an app's window when it has
// one, wherever that window is (plasma-mobile's activateWindowByStorageId: the
// app's first window). With a computer's screen beside the phone's, an app
// tapped on one screen then came up on the other, and only apps whose window
// didn't match their desktop file (Firefox) opened where they were tapped.
//
// So when KWin activates a window on one screen for something done on the
// other, the window comes to the screen it was asked from:
// - asked from the home screen (the drawer, the dock, a folder): the app's own
//   window on that screen if it has one there, else a new window, if its
//   desktop file has a "new window" action (the helper opens it), else this
//   window, moved;
// - asked any other way (the task switcher, a notification, a link opened in
//   another app): this window, moved.
//
// Where something was done is the screen of the window active just before:
// a click or a touch activates the window under it, the home screen too. The
// helper knows when the computer last pressed a key or button, and it has to
// agree: recently for the computer's screen, not recently for the phone's.
// That keeps KWin's own choices (the next window, after one was minimized or
// closed) and a tap on the phone right after a click on the computer from
// moving anything. Nothing happens unless one of the two screens is the
// computer's; while the helper is gone (no answer) nothing happens at all.
'use strict';

const BUS = 'com.ccrstech.openandroiddex.Pmos';
const PATH = '/com/ccrstech/openandroiddex/Pmos';
// A press on the computer this long ago or less is what asked. A drawer tap
// becomes an activation within half a second; nobody puts the mouse down and
// taps the phone that fast.
const PRESS_MS = 1500;
// A new window asked for comes within this. If it doesn't, the old window is
// moved instead, unless something else has been done since.
const NEW_WINDOW_MS = 8000;
// The window an activation showed (unminimized) within this is minimized
// again when it stays where it is.
const SHOWN_MS = 2000;

let last = workspace.activeWindow; // the window active before the one being activated
let quietUntil = 0; // activations this script causes are not looked at
const shownAt = new Map(); // window id -> when it was last unminimized
const asked = new Map(); // app -> {screen, until, old, focus}: a new window on its way
const timers = new Set(); // single-shot timers, kept until they fire

function call(method, args, reply) {
    const rest = reply ? args.concat([reply]) : args;
    callDBus(BUS, PATH, BUS, method, ...rest);
}

function note(text) {
    call('Note', [text]);
}

function after(ms, fn) {
    const t = new QTimer();
    t.singleShot = true;
    t.interval = ms;
    timers.add(t);
    t.timeout.connect(() => {
        timers.delete(t);
        fn();
    });
    t.start();
}

function idOf(w) {
    return w ? String(w.internalId) : '';
}

function same(a, b) {
    return !!a && !!b && idOf(a) === idOf(b);
}

function gone(w) {
    return !w || w.deleted;
}

function shown(w) {
    return !gone(w) && !w.minimized && !w.hidden;
}

function screenOf(w) {
    return w && w.output ? w.output.name : '';
}

function named(name) {
    for (const s of workspace.screens) {
        if (s.name === name) return s;
    }
    return null;
}

function appOf(w) {
    return (w && w.desktopFileName) || '';
}

// A dialog goes where its main window goes.
function mainOf(w) {
    for (let i = 0; w && w.transient && w.transientFor && i < 16; i++) w = w.transientFor;
    return w;
}

// What a person opens: not the home screen, panels, popups or helper windows.
// (Not moveableAcrossScreens: that is KWin's interactive moving, which Plasma
// Mobile turns off outside docked mode; sending a window to a screen is not.)
function appWindow(w) {
    return !gone(w) && w.normalWindow && !w.skipTaskbar;
}

function focus(w) {
    if (gone(w)) return;
    quietUntil = Date.now() + 300;
    workspace.activeWindow = w;
}

// The window stays where it was; minimized again if this activation showed it.
function keep(w) {
    const t = shownAt.get(idOf(w));
    if (t !== undefined && Date.now() - t < SHOWN_MS && w.minimizable && !w.minimized) w.minimized = true;
}

// KWin 6.8 moves a window whose size stays the same at once (sendToOutput), but
// a configure it sent the app just before (for the activation) still holds the
// old place, and KWin puts the window back there when the app answers it
// (XdgToplevelWindow::handleRoleCommit; only an interactive move updates the
// configures on their way). So for a moment the window is sent again whenever
// it lands back on the screen it came from.
const RESEND_MS = 2000;

function send(w, screen) {
    const from = screenOf(w);
    const until = Date.now() + RESEND_MS;
    const back = () => {
        if (!gone(w) && Date.now() < until && screenOf(w) === from) workspace.sendClientToScreen(w, screen);
    };
    w.frameGeometryChanged.connect(back);
    workspace.sendClientToScreen(w, screen);
    after(RESEND_MS + 100, () => {
        if (!gone(w)) w.frameGeometryChanged.disconnect(back);
    });
}

function move(w, screen, why) {
    if (gone(w)) return;
    if (w.minimized) w.minimized = false;
    send(w, screen);
    focus(w);
    note(appOf(w) + ': moved to ' + screen.name + ' (' + why + ')');
}

// The home screen was used: the app is what was asked for, not this window.
function openApp(w, before, screen, canOpen) {
    const app = appOf(w);
    for (const x of workspace.windowList()) {
        if (!same(x, w) && appWindow(x) && appOf(x) === app && screenOf(x) === screen.name) {
            // The focus first: minimizing the active window would have KWin activate another meanwhile.
            focus(x);
            keep(w);
            note(app + ': its window on ' + screen.name + ' instead');
            return;
        }
    }
    if (!canOpen) {
        move(w, screen, 'no second window');
        return;
    }
    // Waited for before asking, so a quick window isn't missed; the screen's
    // own window gets the focus back meanwhile, so KWin opens it there.
    const entry = {screen: screen.name, until: Date.now() + NEW_WINDOW_MS, old: w, focus: before};
    asked.set(app, entry);
    focus(before);
    keep(w);
    call('NewWindow', [app], opened => {
        if (opened || asked.get(app) !== entry) return;
        asked.delete(app);
        move(w, screen, 'the new window would not open');
    });
    after(NEW_WINDOW_MS, () => {
        if (asked.get(app) !== entry) return;
        asked.delete(app);
        // Only if nothing else has been done meanwhile.
        if (same(workspace.activeWindow, before) && !gone(w)) move(w, screen, 'no new window came');
        else note(app + ': no new window came');
    });
}

function decide(w, act, before, from, computer, pressedMs, canOpen, launched) {
    // Something else happened meanwhile: as KWin had it.
    if (gone(w) || gone(before) || !same(workspace.activeWindow, act)) return;
    if (screenOf(w) === from || screenOf(before) !== from) return;
    if (from !== computer && screenOf(w) !== computer) return;
    const recent = pressedMs < PRESS_MS;
    if ((from === computer) !== recent) return;
    const screen = named(from);
    if (!screen) return;
    if (launched) openApp(w, before, screen, canOpen);
    else move(w, screen, 'asked from there');
}

workspace.windowActivated.connect(act => {
    const before = last;
    last = act;
    if (!act || Date.now() < quietUntil) return;
    const w = mainOf(act);
    if (!appWindow(w) || !shown(before)) return;
    const from = screenOf(before);
    if (!from || from === screenOf(w)) return;
    // The home screen (drawer, dock, folders) launches apps; the task switcher
    // and everything else ask for this very window. On the computer's screen
    // the pointer is where the press was, so it must be on the home screen
    // too: a notification popup there doesn't take the focus.
    let launched = before.desktopWindow && !workspace.isEffectActive('mobiletaskswitcher');
    const app = appOf(w);
    call('Look', [app], (computer, pressedMs, canOpen) => {
        if (launched && from === computer) {
            const under = workspace.windowAt(workspace.cursorPos, 1);
            launched = under.length > 0 && same(under[0], before);
        }
        decide(w, act, before, from, computer, pressedMs, canOpen && !!app, launched && !!app);
    });
});

workspace.windowAdded.connect(w => {
    track(w);
    const app = appOf(w);
    const entry = app ? asked.get(app) : undefined;
    if (!entry || !appWindow(w)) return;
    asked.delete(app);
    if (Date.now() > entry.until) return;
    const screen = named(entry.screen);
    if (screen && screenOf(w) !== entry.screen) send(w, screen);
    focus(w);
    note(app + ': a new window on ' + entry.screen);
});

workspace.windowRemoved.connect(w => {
    shownAt.delete(idOf(w));
});

function track(w) {
    if (!w) return;
    const id = idOf(w);
    w.minimizedChanged.connect(() => {
        if (!w.minimized) shownAt.set(id, Date.now());
    });
}

for (const w of workspace.windowList()) track(w);
