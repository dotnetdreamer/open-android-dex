// app.js: a KWin script the phone's helper loads for one app shown in a
// window of its own on a computer (`serve --app`), as Android apps are
// (apps-on-pc.md). build.sh embeds it in the helper (app-js.h); the helper
// writes it to the user's cache with a CONFIG of its own in front (the app's
// screen, the app, and the helper's name on the session bus), has KWin run
// it while the app's screen is up, and unloads it before the screen goes. If
// the helper dies, its guard unloads it.
//
// The app's windows go to the app's screen: its main window full screen, so
// the computer's window shows the app alone, and its dialogs over it. An app
// that has a window open already has it brought over; else the helper starts
// the app, and its first window is brought over as it opens. When none of the
// app's windows is left on the screen, the helper hears so and the computer's
// window closes.
//
// When the computer's window closes, the helper runs this once more, with
// CONFIG.back, before the screen goes: the windows on it go to the phone's
// screen, the app's out of full screen, and the app goes on there as a phone
// app. Left to KWin, they would go to a screen of its choosing as the screen
// goes, full screen still: the phone's desktop on the computer, when that is
// open too.
//
// The desktop's own script (here.js) runs beside this one while the phone's
// desktop is open on the computer too: it moves windows only between that
// desktop's screen and the phone's, so the two don't meet.
'use strict';

// A window of the app that comes or is activated this soon after the start is
// the one asked for: a second launch of a single-instance app activates the
// window it has instead of opening one.
const OPEN_MS = 30000;
// A window being moved is looked at this often, until it has stayed where it
// should, as it should, this long, or for at most the last (see place).
const TICK_MS = 200;
const SETTLE_MS = 400;
const KEEP_MS = 8000;
const BACK_KEEP_MS = 3000;

const started = Date.now();
const timers = new Set(); // single-shot timers, kept until they fire
let had = false; // one of the app's windows has been on the screen

function call(method, args) {
    callDBus(CONFIG.bus, CONFIG.path, CONFIG.iface, method, ...args);
}

function note(text) {
    call('Note', [CONFIG.app + ': ' + text]);
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

function gone(w) {
    return !w || w.deleted;
}

function screenOf(w) {
    return w && w.output ? w.output.name : '';
}

function ourScreen() {
    for (const s of workspace.screens) {
        if (s.name === CONFIG.output) return s;
    }
    return null;
}

// The phone's own: the screen that isn't a computer's (KWin names those Virtual-…).
function phoneScreen() {
    for (const s of workspace.screens) {
        if (!String(s.name).startsWith('Virtual-')) return s;
    }
    return null;
}

function onScreen(w) {
    return !gone(w) && screenOf(w) === CONFIG.output;
}

function lower(v) {
    return String(v || '').toLowerCase();
}

// The app's: its desktop file as KWin names a window's, else its window class
// or name, which some apps don't make the same (Firefox's is firefox-esr, its
// desktop file org.mozilla.firefox-esr).
function ours(w) {
    if (gone(w)) return false;
    const names = [lower(w.desktopFileName), lower(w.resourceClass), lower(w.resourceName)];
    if (names.includes(lower(CONFIG.app))) return true;
    for (const c of CONFIG.classes) {
        if (c && names.includes(lower(c))) return true;
    }
    return false;
}

// A dialog belongs where its main window is.
function mainOf(w) {
    for (let i = 0; w && w.transient && w.transientFor && i < 16; i++) w = w.transientFor;
    return w;
}

// What a person opens: not the home screen, panels, popups or helper windows.
function appWindow(w) {
    return !gone(w) && w.normalWindow && !w.skipTaskbar;
}

// Windows being placed, by id: {stop, screen}.
const placing = new Map();

// The window to `screen`, full screen (full true) or not (false), or as it is
// (null), looked at again every TICK_MS until it has stayed so for SETTLE_MS:
// then done(true); after `keepMs` without, done(false).
//
// Asking once isn't enough. KWin moves a window to another screen only when
// the app answers the configure that says so, and meanwhile the window is
// where it was: KWin 6.8 even puts it back where an older configure had it
// (see here.js's send), and other scripts act on where it still is. Plasma
// Mobile's convergentwindows, outside docked mode, makes a window as big as
// its screen of the moment whenever it is added or activated, or goes in or
// out of full screen or maximized: on the phone's screen, half way through a
// move to this one, which undid the move and had the window full screen on
// the phone. Once a window is full screen there, convergentwindows leaves it.
function place(w, screen, full, keepMs, done) {
    const id = String(w.internalId);
    const old = placing.get(id);
    if (old) old.stop();
    const start = Date.now();
    let since = 0; // since when it has been as it should
    const timer = new QTimer();
    timer.interval = TICK_MS;
    const stop = () => {
        timer.stop();
        if (placing.get(id) === entry) placing.delete(id);
    };
    const entry = {stop, screen: screen.name};
    const look = () => {
        if (gone(w)) {
            stop();
            done(false);
            return;
        }
        const there = screenOf(w) === screen.name;
        if (there && (full === null || w.fullScreen === full)) {
            if (!since) since = Date.now();
            if (Date.now() - since >= SETTLE_MS) {
                stop();
                done(true);
            }
            return;
        }
        since = 0;
        if (Date.now() - start > keepMs) {
            stop();
            done(false);
            return;
        }
        if (full === false && w.fullScreen) {
            // Out of full screen first, where it is: moved while still full
            // screen, the app's answer would have it back on this screen.
            w.fullScreen = false;
        } else {
            if (!there) workspace.sendClientToScreen(w, screen);
            if (full === true && !w.fullScreen) w.fullScreen = true;
        }
    };
    placing.set(id, entry);
    timer.timeout.connect(look);
    timer.start();
    look();
}

// Being placed on this screen, so as good as on it.
function coming(w) {
    const p = placing.get(String(w.internalId));
    return !!p && p.screen === CONFIG.output;
}

const titled = new Set(); // windows whose title the computer's window follows

function bring(w, why) {
    const screen = ourScreen();
    if (!screen || gone(w)) return;
    if (w.minimized) w.minimized = false;
    // Activated before it moves, so what activating does (convergentwindows) is done first.
    workspace.activeWindow = w;
    // The main window full screen, no title bar: the computer's window is the app's.
    const full = !w.transient;
    place(w, screen, full, KEEP_MS, ok => {
        if (ok) return;
        if (!gone(w)) note('its window would not stay on the screen (it is on ' + (screenOf(w) || 'none') + ')');
        after(300, check);
    });
    if (full) {
        had = true;
        call('Shown', [w.caption || '']);
        if (!titled.has(w.internalId)) {
            titled.add(w.internalId);
            w.captionChanged.connect(() => {
                if (onScreen(w) && workspace.activeWindow === w) call('Title', [w.caption || '']);
            });
        }
    }
    note(why);
}

function count() {
    let n = 0;
    for (const w of workspace.windowList()) {
        if (appWindow(w) && ours(w) && (onScreen(w) || coming(w))) n++;
    }
    return n;
}

// None of the app's windows left on the screen: closed, or taken elsewhere.
function check() {
    if (had && count() === 0) call('Empty', []);
}

function watch(w) {
    w.outputChanged.connect(() => after(300, check));
}

function start() {
    workspace.windowAdded.connect(w => {
        if (gone(w)) return;
        const main = mainOf(w);
        if (w.transient && main !== w && ours(main) && onScreen(main)) {
            bring(w, 'a dialog of its window');
        } else if (appWindow(w) && ours(w) && (!had || Date.now() - started < OPEN_MS || onScreen(w))) {
            watch(w);
            bring(w, 'a window opened');
        }
    });

    workspace.windowRemoved.connect(() => after(300, check));

    workspace.windowActivated.connect(w => {
        if (gone(w)) return;
        // The app's window in front on the screen names the computer's window.
        const main = mainOf(w);
        if (onScreen(main) && ours(main)) {
            call('Title', [main.caption || '']);
            return;
        }
        // The launch asked a single-instance app for its window: the one it activates.
        if (!had && Date.now() - started <= OPEN_MS && appWindow(w) && ours(w)) {
            watch(w);
            bring(w, 'its window, as the app asked');
        }
    });

    // At the start: a window the app has open already, the one in use first.
    let found = null;
    const active = workspace.activeWindow;
    if (appWindow(active) && ours(active)) {
        found = active;
    } else {
        for (const w of workspace.windowList()) {
            if (appWindow(w) && ours(w)) found = w;
        }
    }
    if (found) {
        watch(found);
        bring(found, 'its window, brought over');
    }
    // A string: numbers reach the bus as doubles.
    call('Started', [found ? '1' : '0']);
}

// What goes to the phone's screen: the windows a person opened there and
// their dialogs; not popups, or the shell's own (the screen's home screen and
// panels go with the screen).
function goesBack(w) {
    const main = mainOf(w);
    return onScreen(w) && !w.popupWindow && !gone(main) && (main.normalWindow || main.dialog) && !main.skipTaskbar;
}

// Sized for the computer's screen, it may not fit the phone's: as big as that lets it.
function fit(w, screen) {
    const g = w.frameGeometry;
    const s = screen.geometry;
    if ((g.width > s.width + 1 || g.height > s.height + 1) && w.maximizable && typeof w.setMaximize === 'function') {
        w.setMaximize(true, true);
        return true;
    }
    return false;
}

function handBack() {
    const phone = phoneScreen();
    const going = phone ? workspace.windowList().filter(goesBack) : [];
    const t0 = Date.now();
    let left = going.length;
    let there = 0;
    let fitted = 0;
    // Once each has stayed there (or given up): the screen can go.
    const said = () => {
        let text = there + ' of ' + going.length + ' on ' + (phone ? phone.name : 'no phone screen');
        if (fitted) text += ', ' + fitted + ' made to fit';
        call('Back', [text + ', in ' + (Date.now() - t0) + ' ms']);
    };
    if (!left) {
        said();
        return;
    }
    for (const w of going) {
        // Full screen was bring's doing: the phone shows its apps its own way.
        const full = ours(w) && !w.transient ? false : null;
        place(w, phone, full, BACK_KEEP_MS, ok => {
            if (ok) {
                there++;
                if (fit(w, phone)) fitted++;
            }
            if (--left === 0) said();
        });
    }
}

if (CONFIG.back) handBack();
else start();
