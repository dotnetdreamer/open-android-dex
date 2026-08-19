# Escape leaves fullscreen

The desktop's fullscreen is ours, not scrcpy's — the session is spawned without
`--fullscreen` and the window is resized by `embed/` — so until now the only way
out was the taskbar's ⛶ button. That button is on the *phone*, which is exactly
the wrong place to reach for when the desktop is covering the whole monitor.

Code: `src-tauri/src/hotkeys/`. Status: **compiles on both hosts, not verified
on hardware.**

## Why a host key hook

The key goes to scrcpy's window, in scrcpy's process, and from there to Android.
Nothing in this app ever sees it.

The phone *could* see it — `CaptionService` is an accessibility service and
could filter key events with `FLAG_REQUEST_FILTER_KEY_EVENTS` — but its only way
back here is the request queue, which is a poll plus a `content` drain away. A
keypress that answers a beat later reads as a broken key, so the key is caught
on this side, before the window gets it.

On Windows that means `WH_KEYBOARD_LL`. `RegisterHotKey` was the other candidate
and is wrong: it swallows the key for the whole system, so Escape would stop
working in the user's editor the moment the desktop went fullscreen behind it. A
hook can look at who is in front and decide per keystroke. On macOS it is a
`CGEventTap` on key-down — a second one, separate from the trackpad tap, because
gestures only run where there is a trackpad and Escape has to work on a Mac with
a mouse.

That tap is only created once `AXIsProcessTrusted()` says yes. An *active* tap
is itself an Accessibility request: on an untrusted process `CGEventTapCreate`
returns null **and** raises the system permission dialog. macOS offers that
dialog once, and it is reserved for the ⛶ button — spending it unprompted at
session start would leave the button unable to ask for the grant it needs.

## The rules that keep it from being a nuisance

**Exit, never toggle.** `embed::exit_fullscreen` is a new entry point beside
`toggle_fullscreen`, and Escape may only reach the first. Our belief about the
current state can be stale — on macOS the user can leave fullscreen with the
green button, and on either host an auto-reconnect replaces the window — and a
toggle acting on a stale belief would put the desktop *into* fullscreen, which is
the one thing Escape must never do. When the state was wrong, the exit corrects
it: it answers `false`, and that answer is what the taskbar icon is redrawn from.

**Swallowed only when it did something.** The key is taken only if it is a
**bare** Escape, fullscreen is on, *and* the desktop window is in front. Every
other Escape passes through untouched, including when the desktop is merely
windowed. That is the bargain a browser makes, and it is what stops this from
quietly taking a useful key away from Android apps. The matching key-*up* is
swallowed too, so nothing is handed a release with no press.

The modifier test is not politeness. `KBDLLHOOKSTRUCT` carries no modifier
state, so Ctrl+Escape (Start menu), Ctrl+Shift+Escape (Task Manager) and
Alt+Escape all arrive as a plain `VK_ESCAPE` — and a hook returning nonzero is
exactly how those shortcuts get blocked. Taking the Start menu away from a user
whose taskbar is behind a fullscreen desktop would be the worst possible moment
to do it. macOS reads `CGEventFlags` for the same reason, masked to
Command/Control/Option/Shift so Caps Lock and Fn do not veto the key.

**Nothing that can block on the hook.** The whole path a keystroke takes —
the context lock and `scrcpy::session_pid` — is `try_lock`, and a contended
read answers "not our window" and passes the key on. A low-level keyboard hook
runs with the sending thread's input stalled until it returns, and `kill_all`
holds the session map across a process kill; a blocking lock there would put a
hitch in the user's typing at the exact moment the app is shutting down.

**Nothing slow on the hook.** Leaving fullscreen is a relayout on Windows and
several Accessibility round trips on macOS. The hook posts on a channel and a
worker does the work — Windows silently removes a hook whose callback is slow
(`LowLevelHooksTimeout`), and macOS does the same to a tap. A held Escape repeats
about thirty times a second, so a busy flag keeps one exit in flight.

## Costs

The hook is installed for the whole session rather than only while fullscreen,
which trades a little always-on machinery for having no state machine to get
wrong. It is cheap on purpose: every keystroke on the machine reaches the
callback, and everything that is not Escape is passed on after two integer
comparisons, before any state is read.

Both backends key their teardown by a session number, for the reason
`gestures/windows.rs` does: a session's stop-watcher outlives the hook it was
started for, and a stale one waking after the next session began must not disarm
its replacement.

## Not done

There is no setting for it, and no on-screen hint when entering fullscreen —
which is how most apps tell the user the key exists. Either is a small addition
if the behaviour proves surprising.
