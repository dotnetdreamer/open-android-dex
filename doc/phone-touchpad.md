# The phone as the desktop's touchpad

Samsung DeX puts a notification on the phone while it is showing a desktop:
tap it, and the phone's screen becomes a touchpad for that desktop. This is the
same thing for a desktop a computer is showing — one finger points, two scroll,
three run the five gestures Settings → Touchpad maps — and the reasons each
piece is where it is.

Status: **built, and run on hardware** — a Galaxy A13 (Android 12) — see
"Verified, and not yet" at the end. Two of its shapes were measured wrong there
and replaced: the pad as an activity (§2), and the arrow as a window on One UI's
untrusted display (§5).

---

## 0. The problem in one paragraph

The desktop is a display of its own on the phone, shown by the computer. While a
session runs, the phone's own screen is free in the user's hand — on a sofa in
front of a TV, at a desk with a mouse but no touchpad, or just within reach. The
pointer, though, belongs to the desktop's display: nothing on the phone's screen
can move it, click with it or scroll under it, and Android draws no pointer for
input it did not get from a device of its own. So the pad has to do three
things: read fingers on the phone's screen without taking anything away from the
desktop, turn them into a mouse on another display, and draw the pointer there
itself.

## 1. Shape

```
        phone's screen (display 0)                     desktop's display
┌───────────────────────────────────┐   ┌──────────────────────────────────────┐
│ TouchpadNotice  the notification  │   │ the arrow: an overlay, or a layer of │
│ TouchpadActivity  its trampoline  │   │ the daemon's (PadPointer) — see §5   │
│ Touchpad          the pad window  │   │  apps ◀── hover, buttons, wheel, keys │
│ TouchpadGestures  what fingers    │   │            (AppInput, in the daemon)  │
│                   mean            │   └──────────────────────────────────────┘
└───────────────┬───────────────────┘                    ▲
                │ moves, presses, wheel, keys            │
                ▼                                        │
         DesktopPointer ── outbox ── WmClient ──▶ wmd: MOUSE WHEEL LEAVE KEY FOCUS CURSOR
```

| Piece | File | What it owns |
| --- | --- | --- |
| The notification | `TouchpadNotice.java` | up while a computer shows the desktop; opens the pad |
| Its trampoline | `TouchpadActivity.java` | opens the pad and finishes — see §2 |
| The pad | `Touchpad.java` | the window on the phone's screen, its buttons and options, the three-finger actions, getting out of the way |
| The grammar | `TouchpadGestures.java` | fingers → move, click, drag, wheel, gesture |
| The pointer | `DesktopPointer.java` | where it is, the arrow, the outbox to the daemon |
| The mouse | `WmDaemon.java` (`MOUSE` `WHEEL` `LEAVE` `KEY` `FOCUS` `TOP`), `AppInput.java` | injection into the desktop's display, at uid 2000 |
| The daemon's arrow | `PadPointer.java` (`CURSOR`) | the arrow as a SurfaceFlinger layer, where no window of ours can be seen — §5 |
| Entry points | `LauncherActivity.java` | the notification's lifetime; Touchpad in quick settings |
| Settings | `SettingsActivity.java` | Phone as a touchpad (the notification), and the five gestures |

## 2. A window over the phone's screen, not an activity

**The reason is focus.** Below Android 14 only the topmost display has a
focused window (`config_perDisplayFocusEnabled` is false on both test phones),
and touching a focusable window makes its display the topmost. A pad that could
be focused takes the desktop's keyboard on every touch: the computer's keys,
which scrcpy aims at the desktop's display, wait for a focused window that is no
longer there — holding every key behind them — and time out. The daemon's
`AppTasks.focus` records the same thing for the app windows.

**An activity was built first, and measured wrong.** On a Galaxy A13 (Android
12) the pad was an activity whose task the daemon then made one that cannot take
focus (`WindowContainerTransaction.setFocusable(false)`, the line
custom-titlebar-v2.md §4.1 calls mandatory for a chrome task) and whose window
was `FLAG_NOT_FOCUSABLE`. The launch itself had already made it the phone
display's focused app, and nothing gives that back: `dumpsys window` showed
display 0 with the pad as `mFocusedApp`, no `mCurrentFocus` anywhere, and
`mTopFocusedDisplayId=0`. That is the state InputDispatcher waits in: a Back
from the phone's navigation bar is aimed at display 0, there is a focused app
and no focused window, so it waits — and every touch on the pad waits behind
it, because a touch only cuts the queue when it lands on a *different* app.
After five seconds Android calls the pad not responding. It also explains why the
daemon's `FOCUS` did nothing in that state: the task manager skipped display 0
(nothing on it was both focusable and visible) and reported the desktop as
focused, while the window manager — which is what keys follow — kept display 0
on top because it had a focused app.

**An overlay never enters that bookkeeping.** `TYPE_APPLICATION_OVERLAY` with
`FLAG_NOT_FOCUSABLE` cannot be focused, and a touch on it moves no display to
the top (`WindowState#canReceiveKeys` is false, so `onPointerDownOutsideFocus`
returns early). The phone's focused app stays the phone's own, and the desktop
keeps its keyboard. It is the shape the dock's own touchpad already has
(`DexPointer`), under the overlay permission the computer grants for the session.

**The notification still opens an activity** — a notification whose intent is a
broadcast leaves the shade down over the pad it just opened — but one that only
calls `Touchpad.open` and finishes (`TouchpadActivity`, on the Detour theme like
the other trampolines). Finishing is what hands the phone's display back: the
app the user had in front is resumed, and is its focused app again. Quick
settings on the desktop opens the pad with no activity at all.

**What the overlay costs, and what is done about it.** An overlay is not in the
phone's stack of screens, so Home, a call being answered or an app opened from
the shade would come up *underneath* it. The pad asks the daemon twice a second
what is in front of the phone's display (`TOP 0`, read-only like `LIST`, and
answering on Android 11 too) and closes itself the moment that changes; our own
tasks are not counted, so the trampoline passing through does not close it. Home
and Recents on a phone already at its home screen change nothing in front, so
they are also caught from `ACTION_CLOSE_SYSTEM_DIALOGS` (reasons `homekey` and
`recentapps` only — the shade sends others, and opens over the pad on purpose).

The phone's own Back is the app underneath's, which is still the phone's
foreground app. Below Android 14, with the desktop holding the keyboard, a Back
aimed at the phone's display waits like any other key there — the pre-14
multi-display model, not something the pad adds — and the pad's next touch, on a
different window, cuts it loose. ✕ is the pad's way out.

## 3. A real mouse, through the daemon

`WebInput` and the dock's touchpad put taps into the phone through
`AccessibilityService#dispatchGesture`, because nothing else is open to an app,
and say plainly what that costs: a mouse becomes a finger — no hover, no right
button, a wheel turned into swipes that fling. The pad exists only while a
computer is showing the desktop, and so only while the window daemon is up at
uid 2000, which holds `INJECT_EVENTS`. So the pad is a real mouse: the daemon's
`MOUSE` is `AppInput.pointer`, the injector the computer's own app windows use,
which makes the sequence a mouse makes (hover; DOWN and BUTTON_PRESS; MOVE while
held; BUTTON_RELEASE and UP), aimed at the desktop's display by id. A right
click is `BUTTON_SECONDARY`, which is what opens context menus — the desktop's
own included. `WHEEL` is `ACTION_SCROLL` in fractions of a notch.

The old note that "`input -d` never lands on the desktop's display" (web-viewer.md)
does not describe injection in-process: AppInput has put every app window's
input into displays of its own since apps-on-pc shipped, and the desktop's
display takes it the same way.

**Nothing waits on the daemon.** The pad's moves go into an outbox drained on a
client thread of its own, one line answered before the next. A move still waiting
is replaced by the next one — the pointer follows the finger instead of replaying
every place it has been, `WebInput`'s rule for drags — and consecutive wheel
lines add up. A press, a release and a key are never folded into anything: a
quick tap is still a click, and a drag still starts where the button went down.

## 4. The grammar

The Linux viewer's Mouse method (`dex-input.js`), constant for constant where
the two share one, so the phone teaches one vocabulary:

| Fingers | Does | Constants |
| --- | --- | --- |
| One, slid | moves the pointer, once past a tap's slop; the slop itself never moves it | `MOVE_SLOP` 12 dp |
| One, tapped | clicks; never held back to wait for a second tap | — |
| Two quick taps | two clicks; the app decides they are a double-click | `DOUBLE_MS` 300, `DOUBLE_SLOP` 40 dp |
| Double-tap, then slide | holds the button: select, or drag a window by its bar | — |
| One, held still | arms a right-click (a ring and a tick on the phone); lifting clicks it, sliding turns it into a drag | `LONG_MS` 500, `LONG_SLOP` 14 dp |
| Two, slid | scrolls at the pointer; a flick keeps going and slows | 40 dp per notch; fling decay 325 ms |
| Two, tapped | right-click | within 300 ms, unmoved |
| Two, pinched | nothing — see below | `PINCH_SLOP` 24 dp |
| Three (or more), slid | one gesture per touch, fired as the centre crosses ~10 mm | 60 dp |
| Three, tapped | the tap gesture | still within 20 dp, 300 ms |

Pointer speed is `DexPointer`'s curve (itself the viewer's), applied in dp on
both sides — the finger's dp on the phone, the desktop's dp on the desktop — so a
desktop drawn at twice the scale is not twice as slow to cross. Speed and scroll
direction are the dock touchpad's two settings (`pad_speed`, `pad_natural`), set
from the pad's ⚙.

Pinching does nothing: Android has no mouse event that zooms. Ctrl with a wheel
zooms a page in Chrome and almost nothing else; a real two-finger pinch would need
touchscreen events injected beside a mouse's, which is a different piece of work.

## 5. The pointer

Android draws a pointer for an input device it owns; an injected event goes to
the window under it and moves no sprite. So the arrow is ours: `DexCursors`'
arrow in the style, colour and size Settings → Mouse & cursor picked (System,
which has no picture of its own here, is drawn as DeX), at the desktop's
density. Where it is drawn comes down to two things measured on the A13.

**A window of ours can be buried.** One UI, on a virtual display that is not
trusted — every scrcpy display below Android 13 — parents the display's whole
app layer (`DefaultTaskDisplayArea`) to a `RootWrapper` above the `Root` that
every overlay leaf lives in. The arrow as an accessibility overlay was added and
reported drawn (`ty=2032` on the desktop's display), and was never composited:
the desktop covers it. `LauncherActivity.overlaysBuried` already knew this for
the taskbar.

**Whatever is on the click's own point must be trusted.** The hot spot is
exactly where each click lands, and a surface over it from another uid that is
not a trusted overlay is "occlusion" to Android 12's tapjacking guard. With the
guard on, InputDispatcher logged `Untrusted touch due to occlusion by /2000`
and dropped the click. The session turns the guard off (`TOUCH_GLOBAL` in
adb.rs), but only below Android 16, where the setting is gone and the guard is
unconditional. An application overlay drawn at full alpha is occlusion by the
same rule, so the arrow is never one.

So the arrow comes from the first of these that takes it:

1. the caption service's window context (`CaptionService.overlayContext`): an
   accessibility overlay — trusted, above the taskbar and every menu, needing
   no permission — Android 14 and later;
2. the caption service's own display context. Below 14 a window context cannot
   hold an accessibility overlay — "token null is not valid", measured on the
   A13 — while an accessibility service's display context is made with the
   service's overlay token for that display;
3. the window daemon (`PadPointer`, `CURSOR`) — and first, where
   `overlaysBuried` says no window of ours would be seen.

A window is `NOT_TOUCHABLE`, so no click lands on it, and `LAYOUT_NO_LIMITS`,
so near the right and bottom edges its tip is still where the clicks go; it is
moved once per frame at most.

**The daemon's arrow** is a layer at the root of SurfaceFlinger, on the
desktop's layer stack: where the system draws a real mouse's pointer, above the
`RootWrapper` and everything in it. A root layer is composited only for a caller
holding `ACCESS_SURFACE_FLINGER`, which the shell has and an app never will, and
the same authority makes it a trusted overlay (Android 12 and later). The
launcher draws the bitmap and sends it once, a base64 PNG with its hot spot
(`CURSOR`); each `MOUSE` then moves the layer in the same step as the hover it
injects, so nothing on the phone places it frame by frame, and `LEAVE` hides it.
The layer belongs to the connection that sent it: when that socket closes — the
pad closed, the launcher died — the arrow goes too, rather than stay frozen over
the desktop. `WmClient` counts its connections, and the pointer sends the arrow
again on a new one.

The arrow shows while the pad is up and goes with it; `LEAVE` lets go of
anything held and ends the hover. Where it was left is remembered per display,
so reopening the pad does not send it back to the middle.

## 6. Three fingers

The five slots Settings → Touchpad maps for a laptop's touchpad
(`gest3up` … `gest3tap`), read from the phone's own copy of that mapping — one
table for both pads, which is why the gesture cards in Settings are never dimmed
now. Each action goes down the channel that owns it, as the laptop's do
(`Sink::act`): the shell's panels as a `GESTURE` broadcast to the launcher, Back
as a key through the daemon, maximise to the computer as a `window` request,
exactly as the taskbar's app menu sends it.

Home is the exception, and so is the pad's own ○: both are the taskbar's ○
(`goHome`, through the same broadcast), not `KEYCODE_HOME`. On the A13 that key
did nothing on the desktop — a display with no home of its own — and One UI
still told every app the phone's Home key had been pressed, the pad among them,
which put itself away. The laptop's Home gesture still sends the key.

Next and previous window walk a remembered ring, for the reason
`Sink::cycle_window` gives. Unlike the laptop's, our own windows — Settings,
Linux — are in it; only the desktop itself is not. Both need `LIST`, which is
Android 12's; on 11 they do nothing.

`notifcentre` ("Notifications") was offered by Settings long before the desktop
app's `Action::parse` knew it, so choosing it for a laptop gesture did the
default instead. Both pads know it now.

## 7. Focus, once more

The pad never takes the keyboard, and gives it back to the desktop when
something else on the phone did — pulling the shade down is enough:

- `FOCUS <desktop>` as the pad opens, again 700 ms later (the notification's
  trampoline takes the top back as it finishes), and as every touch begins. The
  daemon does nothing when the desktop already has the keyboard, the usual case,
  and otherwise focuses the desktop's top window (`setFocusedTask`, as the app
  windows do).
- `KEY` hands the keyboard over first, then presses the key — in-process, where
  `KEYBACK` and the laptop's gestures fork `input` and wait for a VM to boot.

So the computer's keyboard keeps typing where it was, and a click from the pad
lands in a window that can take keys. Measured on the A13: with the phone's own
screen holding the keyboard (the shade pulled down by touch,
`mTopFocusedDisplayId=0`), one slide on the pad gave it back to the desktop, and
the pad's Back, pressed straight away, closed the window in front there.

## 8. The notification

Up while the desktop is on a display of its own *and* a computer is behind it —
the first heartbeat from the computer (`ACTION_RUNNING`) is the moment — on
Android 11 and later. That excludes the desktop drawn on the phone's own screen
(which has the dock's strip) and a desktop on a monitor with no computer (no
daemon to carry the pointer). Ongoing, low importance, local only. Down with the
desktop: the end-of-session force-stop clears it, a pulled cable takes the
display and the activity that put it up, and the desktop takes it down itself
otherwise (`touchpadOffered`). From Android 14 a user may swipe it away; that
stands until the next desktop.

From Android 13 a notification needs `POST_NOTIFICATIONS`. The computer grants
it for the session (`grant_post_notifications` in adb.rs) by the rule notification
access follows: an answer the user already gave stands either way — granted is
theirs, and refused (`USER_SET` or `USER_FIXED` on a permission that is not
granted) is theirs too — and `restore_phone` revokes it only when this app was
what granted it, recorded in the per-phone restore map. Without it the
notification is simply not shown, and quick settings' Touchpad still opens the pad.

Settings → Touchpad → Phone as a touchpad turns the notification off. The pad
itself stays one tap away in quick settings.

## 9. Who may move the pointer

The new verbs are open to the launcher's uid, and the launcher's uid is also the
Linux guest's, where anything the user installs runs (see `PC_ONLY`). So for that
uid every input verb is held to the desktop's own display — the one the launcher's
`LauncherActivity` is on, looked up through `getTasks` at most every two seconds
and never display 0, where the lock screen and every permission dialog live.
`WHEEL` refuses `NaN`, infinities and absurd sizes, and `KEY` refuses codes
Android does not have. `CURSOR` is held to the same display and to a picture no
bigger than a pointer — 256 px either way, its size read before it is decoded —
because a layer above everything on the desktop must not become a convincing
dialog. `TOP` is read-only, like `LIST`. The PC's own uid may aim anywhere, as
with every other verb.

## 10. Verified, and not yet

On a Galaxy A13 (Android 12, One UI 4), USB, 2026-10-25, against a real
desktop seen in the computer's window. Fingers were injected on the phone's
screen by a uid-2000 touch injector (`input` cannot do several fingers), and the
arrow's position read from SurfaceFlinger:

- **The notification** comes up with the computer's first heartbeat, ongoing and
  low importance, with the touchpad icon. Tapping it opens the pad; so does
  Touchpad in the desktop's quick settings. Settings → Phone as a touchpad takes
  it down and puts it back (an open pad stays open). Exit DeX takes it down.
- **The pad** lays out between the system bars and leaves the phone's own app
  focused. ✕ closes it; an app brought in front on the phone closes it ("the
  phone brought com.android.settings to the front"); so does One UI's
  `homekey` broadcast (seen when the pad's old Home sent it — below).
- **The arrow**, as a window, was buried (§5); as the daemon's layer it shows
  over the desktop and over app windows, in the computer's window. It goes when
  the pad does, and when the connection that drew it closes.
- **The gestures**: a slide moves the pointer (300 phone px → 148 desktop px at
  the default speed) and hovering lights what is under it; a tap clicks (the
  taskbar's Apps, the stopwatch's Start); two fingers tapped, and one held then
  lifted, right-click (the desktop's Add widget… menu); one held then slid drags
  a window by its handle, and a double-tap then slide drags a list as far as
  the finger goes, where a plain slide only moves the pointer over it; two
  fingers scroll in the natural direction, and a flick keeps the page moving
  after the fingers lift; three swiped up ran `openapps`.
- **Focus**, as §7 says: a slide gives the keyboard back, and Back reaches the
  desktop's front window with no not-responding.
- **The guard** (§5): an untrusted arrow layer made InputDispatcher drop a click
  at its own hot spot; marked trusted, the same click went through. Both with
  `block_untrusted_touches` set to block for the test, then put back.
- **Home** as `KEYCODE_HOME` did nothing on the desktop and closed the pad; it is
  the taskbar's ○ now, and the pad stays. On this phone the taskbar's ○ itself
  does not bring the desktop in front of a freeform window either — the same
  `goHome`, with or without the pad.
- `CURSOR` refuses a picture wider than 256 px, and a line that is not one.
- Below Android 13 the permission check finds nothing to grant.

One thing to know when trying it: a click on One UI's window handle opens the
window's Quick options, so a double-tap there opens those before the slide —
hold, then slide, to move a window by its handle.

Not yet:

1. **A trusted display** (Android 13+, the S25 Ultra), where the arrow is an
   accessibility overlay (§5's first two ways) rather than the daemon's layer.
   Neither test phone is 13 or later; nor has the daemon's layer run on one.
2. **One UI on Android 11**, which `overlaysBuried` also sends to the daemon.
3. **`ACTION_CLOSE_SYSTEM_DIALOGS` on Android 14 and later** — whether it still
   reaches a receiver that is not exported.
4. **Android 13+ `POST_NOTIFICATIONS`** — the grant, the revoke, and whether a
   shell `pm revoke` leaves flags the next session would read as a refusal.
5. **An answered call** and Recents closing the pad; only Home and an app
   started on the phone were tried.
6. **Latency** beyond "it follows the finger": the arrow moves in the daemon in
   the same step as the hover it shows, and reaches the computer in the video.
