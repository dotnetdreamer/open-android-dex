# Touchpad gestures

Samsung DeX gave a laptop trackpad a three-finger vocabulary: swipe up for what
is open, down for the desktop, left and right to walk the windows. This is how
Open Android DeX answers the same gestures, and — more usefully — why every
piece of it is where it is.

Status: **written and compiled on both hosts, not yet verified on hardware.**
See "What is unproven" at the end; two of the entries decide whether the
feature works out of the box or needs one manual step, and neither could be
settled on the machine it was written on (a desktop PC with no touchpad).

---

## 0. The problem in one paragraph

A three-finger swipe is not a mouse event. The pointer, the wheel and
right-click all reach the phone already, because the host's touchpad driver
turns one and two fingers into exactly those and scrcpy forwards them. Three
fingers never become anything a window can receive: the host's *shell*
recognises them and turns them into Task View or Mission Control. To answer
them ourselves we have to read the pad's contacts directly, decide what they
mean on this side, and drive the phone with a command — and, separately, stop
the host acting on the same fingers.

## 1. Why not the obvious alternatives

**Patch scrcpy.** scrcpy is a stock, SHA256-pinned upstream binary on all three
platforms (`.github/workflows/desktop.yml`). Forking it to add gesture handling
would mean building and shipping it ourselves for Windows and two macOS arches,
forever, to add something that does not belong in a mirroring tool.

**Let Android recognise the gestures.** Android 13+ has a real trackpad stack
(`TouchpadInputMapper` plus the ChromeOS gestures library), and shell (uid 2000)
can open `/dev/uhid`, so a virtual Precision-Touchpad-class HID device on the
phone fed with the PC's raw contacts is buildable. It was rejected for two
reasons. The gestures Android recognises are only *labelled* — their consumer is
Quickstep's privileged spy monitor on the **default** display, which does not
exist on ours — and a UHID touchpad introduces a second, Android-owned,
*relative* pointer that immediately desynchronises from the PC cursor the user
is actually steering. The design would trade a working absolute pointer for
gestures that nothing on our display listens to.

**Windows' `TouchpadGesturesController`.** This is the supported API for exactly
this, it suppresses the shell's own action for free, and its Win32 half ships in
Windows 11 25H2 (`RegisterTouchpadCapableWindow` and friends are exported from
`user32.dll` on build 26200; the WinRT class is present in
`WinMetadata\Windows.UI.winmd`). It is not usable here, for a reason Microsoft
states outright: *"The system will first check for any controllers registered by
the foreground process… Controllers in background processes are ignored."* The
foreground process is `scrcpy.exe`. It would become usable if the desktop were
ever reparented into our own window — `embed/windows.rs` can already do that —
so this is the first thing to revisit if that changes. The class is also
`[Experimental]` and Windows-11-only, which the raw-input path is not.

## 2. Shape

```
        host                            shared                     phone
┌────────────────────────┐   ┌──────────────────────┐   ┌──────────────────┐
│ gestures/windows.rs    │   │ gestures/mod.rs      │   │ wmd daemon       │
│  raw input, HID parse  │──▶│  Recogniser          │──▶│  LIST / FRONT    │
│ gestures/macos.rs      │   │  mapping (on disk)   │   │ launcher         │
│  event tap, NSTouch    │   │  Sink::act           │   │  GESTURE receiver│
└────────────────────────┘   └──────────────────────┘   └──────────────────┘
     contacts, as                a Gesture, then           the shell action
   fractions of pad width          an Action
```

The split is deliberate: the backends know how big a pad is and nothing else,
and the recogniser knows what a swipe is and nothing about hardware. Both hosts
therefore commit a swipe after the same physical travel, and there is one
description of "tap" rather than two that drift.

**Fractions of pad width** is the unit both backends convert into. It is the
only description of a touch that means the same thing on a 100 mm PC pad and a
160 mm Force Touch trackpad, and it needs no HID unit decoding — a pad's
*physical* extents are declared in a unit we would have to parse, but the
**ratio** of its two axes is available without parsing anything, and the ratio
is all that is needed to make a vertical swipe travel as far as a horizontal
one.

**Three fingers and up, never fewer.** One and two contacts are left completely
alone. Re-deriving the pointer and the wheel from the same contacts would mean
fighting the driver for the cursor and breaking what already works. That single
rule is what makes the whole feature additive.

## 3. Reading the pad

### Windows — raw input

`RegisterRawInputDevices` for `(HID_USAGE_PAGE_DIGITIZER 0x0D,
HID_USAGE_DIGITIZER_TOUCH_PAD 0x05)` with `RIDEV_INPUTSINK`, targeting a
message-only window on a thread of our own.

- **INPUTSINK is mandatory**, not an optimisation: without it the reports stop
  the moment the DeX window is focused, which is the only time they matter.
- **A thread of our own**, not the Tauri window's `WndProc`: parsing HID on the
  UI thread would compete with tao's own subclass chain, and `GetMessageW`
  blocks. Teardown is a 250 ms watcher that posts `WM_CLOSE`, the same shape as
  the logcat killer in `diag.rs`.
- **Registration is per process and per usage page**, last call wins, so the
  reader is a process-wide singleton and `start` tears down any previous one.
  `RIDEV_REMOVE` requires a **null** target — passing the window being destroyed
  makes the call fail and leaves the process subscribed with nowhere to deliver.
- Each reader carries a **generation**, and its stop-watcher may only close that
  generation. A watcher outlives the reader it was started for, so a stale one
  waking up after the next session began would otherwise post `WM_CLOSE` at a
  window it never started.
- The reports live *past* the end of `RAWINPUT` (`bRawData` is a one-byte
  placeholder), and `RAWHID` is `Copy` — reading them through a copy of it gets
  one byte of payload and then the stack. They are sliced out of the original
  buffer by `offset_of!`.
- One `WM_INPUT` can carry several reports (`dwCount`); dropping all but the
  first loses contacts on a fast swipe.
- **A `Contact Count` of zero is not "all fingers lifted".** A pad is allowed —
  and on one with few contact collections, obliged — to split a frame across
  several reports, where the first carries the frame's total and every
  continuation carries zero. Reading zero as a lift wipes the accumulator on
  every pad that reports that way, so the count is a *budget*: set on the first
  report, counted down per contact, and the walk stops when it runs out. That
  also disposes of the button-only report a pad sends on a physical click,
  whose contact collections hold nothing. A lift needs no sentinel — it arrives
  as Tip Switch clearing on the contact itself.
- **Confidence is honoured** where the descriptor declares it (asked once with
  `HidP_GetSpecificButtonCaps`, because requiring a usage a pad never sends
  would reject every contact). It is the pad's own verdict that a contact is a
  finger and not a palm, and without it a thumb parked at the edge turns an
  ordinary two-finger scroll into a three-finger swipe.
- A **legacy (non-precision) touchpad** reports itself to Windows as a mouse and
  publishes no digitizer collection at all. Registration succeeds and no
  `WM_INPUT` ever arrives; `has_touchpad()` reports that honestly and the
  Settings section dims.

### macOS — an event tap

A `CGEventTapCreate` at `kCGSessionEventTap` for `NSEventTypeGesture` (29), with
`kCGEventTapOptionDefault` so it can **swallow**. `+[NSEvent eventWithCGEvent:]`
bridges to AppKit, and `touchesMatchingPhase:inView:` answers with one `NSTouch`
per finger, already normalised.

- **A tap rather than `NSEvent`'s global monitor** for one reason: a monitor can
  only watch. macOS claims three-finger swipes by default (Mission Control, App
  Exposé, spaces), and swallowing the events we act on is what keeps them from
  firing alongside ours — with no global setting changed and nothing to put
  back. That is why this backend has no equivalent of Windows' registry work.
- A **whole touch** is claimed, not one event: without that the tail of a swipe
  still reaches the Dock and Mission Control opens a beat after our action.
- **Only what we act on is swallowed.** The reader cannot read the mapping off
  disk — it has to answer synchronously — so the worker keeps a bitmask of the
  slots that currently resolve to a real action and the tap reads it with one
  atomic load. Swallowing a gesture we are not going to perform would silently
  delete the user's own four-finger gestures, and keep deleting three-finger
  ones after they switched the feature off.
- The frame that ends a touch is the same frame a **tap** is recognised on, so
  the end-of-touch bookkeeping must not short-circuit the dispatch. Getting that
  order wrong makes three-finger tap silently dead on macOS and nowhere else.
- The tap is **re-armed** on `kCGEventTapDisabledByTimeout`. A tap the system
  switches off for being slow stays off forever otherwise, and "the gestures
  worked, then quietly stopped" is a very hard bug to report.
- Needs **Accessibility**, the same grant `embed/macos.rs` already asks for. The
  refusal is logged and shown as a dimmed Settings section, never silent.
- `NSTouch.isResting()` filters a palm or a parked thumb, which would otherwise
  turn a two-finger scroll into a three-finger swipe.

### Both

A gesture is only dispatched when the DeX window is actually in front
(`GetForegroundWindow` → `GA_ROOT` → pid on Windows,
`NSWorkspace.frontmostApplication` on macOS). Both readers see the whole
session, so without the check a three-finger swipe in a spreadsheet would
rearrange Android windows. On macOS the check also gates the *swallow*: taking
an event from another app would break their trackpad.

## 4. Recognising

| | |
|---|---|
| Swipe threshold | 0.10 of pad width (≈10 mm on a 100 mm pad) |
| Tap slop / max hold | 0.03 of pad width, 300 ms |
| Stale frame | 250 ms |

A swipe **fires on crossing the threshold, not on lift** — a gesture that only
answers once the fingers come up reads as a slow machine. One touch fires at
most one gesture; the rest of the touch is latched. A lift with no fire, no
drift and no lingering is a tap. Fewer than three contacts does **not** end a
touch — fingers rarely leave the pad together, and ending it there would turn
the tail of every swipe into a tap.

## 5. Acting

Three channels, chosen per action by what actually owns the state:

| Action | Channel | Why |
|---|---|---|
| Next / previous window | `wm.rs` → daemon `LIST` + `FRONT` | ~2.5 ms; no shell involvement at all |
| Maximise / restore | the enforcer's `window_reqs` mailbox | the enforcer owns per-window maximized state and windowed bounds, and would undo a bare resize on its next tick |
| Open apps, apps screen, quick settings, show desktop | `am broadcast …GESTURE` | shell UI, which the daemon holds none of by design |
| Home, Back | `input -d <display> keyevent` | no daemon verb exists; this is the same path the taskbar's own Back button uses |

**Nothing slow happens on a reader thread.** A recognised gesture is posted on a
channel to a worker that owns the `WmClient` and the `ShellSession`; the
reader's whole job on the hot path is a foreground check and a send. This is not
tidiness. A keyevent costs a few hundred milliseconds because it boots a VM on
the phone, and on Windows a stalled reader drops the contacts of whatever the
user does next — while on macOS a tap callback that takes too long is **switched
off by the system**, silently, for the rest of the session.

The worker acts immediately rather than handing gestures to the enforcer's
100 ms poll, which would add up to a poll period of jitter to something the user
performed with their hand.

**Window cycling is a ring**, and the order is remembered rather than read off
the z-order each time. The only primitive available is "raise this one", and
walking a live topmost-first list forwards just swaps the top two back and forth
forever. Sending the front window to the *back* would give a true rotation with
no state at all, and is deliberately not used: the launcher is a fullscreen
task, so anything beneath it is occluded, stopped by the window manager, and
reads as closed.

**Show desktop restores bottom-up.** `wm.show` un-hides *and* raises, so
replaying the stack from the deepest window puts every one back where it was;
walking the minimised map in its own order — it is a `HashMap` — hands back a
desktop shuffled into an arbitrary order with the wrong window focused. Which
way the toggle goes is decided by what is on screen, not by whether anything
happens to be minimised: a user who minimised one window by hand and then asks
for the desktop means "clear the other two".

**Show desktop lives in `CaptionService`**, not on the PC. Both halves of the
toggle are there — the live window list and the record of what has already been
hidden — and, critically, a window hidden without being recorded in that map
vanishes from the taskbar with nothing able to restore it. "Show desktop" would
then mean "close everything".

## 6. Standing down the host's own gestures

macOS needs nothing: the tap swallows what it claims.

Windows raw input **tees rather than filters**, and there is no raw-input flag
that suppresses the gesture engine (`RIDEV_NOLEGACY` is documented as mouse and
keyboard only). So while a session is live the two DWORDs Windows reads —
`ThreeFingerSlideEnabled` and `ThreeFingerTapEnabled`, under
`HKCU\Software\Microsoft\Windows\CurrentVersion\PrecisionTouchPad` — are set to
zero, and put back when it ends.

- **Three fingers only.** Four-finger slides and taps are left to Windows, so a
  user still has Task View and desktop switching while the desktop has three.
  That is also why the default mapping stops at three fingers.
- Snapshotted to `host-touchpad.json` in the app config dir **before** the
  write, and a snapshot that fails to reach disk is a reason not to write at
  all. The other order has one outcome that must never be produced: the values
  zeroed with no record of what they were, which the user cannot repair without
  knowing them by heart. Restored at session end, at application exit, **and at
  the next launch** — the last of those covers a crash or a kill, and is the
  same discipline `adb::restore_phone` applies to the phone.
- Driven from the worker's tick, not from session start, so the master switch in
  Settings gives the host's gestures back the moment it is turned off. Pads
  whose values are *already* zero are left alone entirely — writing a snapshot
  there would mean "restoring" gestures the user had disabled themselves.
- A value that was **absent** is deleted rather than set to a default: Windows
  treats absent as "the user never chose", and leaving a `1` behind would pin a
  preference they never expressed.
- `TOUCHPAD_PARAMETERS` / `SPI_SETTOUCHPADPARAMETERS` (Windows 11 24H2+) is the
  supported way to change touchpad settings live, and it carries **no** three-
  or four-finger fields. The registry is the only route there is.

## 7. Settings

A `Touchpad` section in the launcher's Settings window, next to Mouse & Cursor.
Five cards, one per gesture, each a list of actions.

It rides the existing `cfg` channel (`DexPrefs` `stream_` prefix → `pcConfig` →
`RequestProvider` → the PC's request pump → `stream-config.json`) but is the one
setting in that window with **no restart footer**: every other PC-side setting
is a scrcpy argument and only lands on a fresh session, whereas the gesture
engine reads its mapping off disk on each gesture. A few file reads a minute
buys a remapped swipe that works on the next swipe.

Whether the host has a readable touchpad rides along on the PC's existing
running-apps broadcast as `--ez tp`. The section dims rather than hides when it
does not — the same dim-and-explain the pointer-speed card uses, because a
section that empties itself reads as broken.

`GESTURE_VALUES` in `SettingsActivity.java` and `Action::parse` in
`gestures/mod.rs` are two halves of one table, as are `DexPrefs.DEF_GESTURE_*`
and `default_action()`. Changing one alone is a silent no-op.

The keys sit in the **Streaming** reset scope, next to `KEY_MOUSE_MODE` and for
the same non-obvious reason: `runFactoryReset` turns *any* `stream_` key into a
blanket `cfg reset.all` on the PC. A key left out of every scope would be wiped
on the PC by an unrelated reset while the phone kept its copy, and the two sides
would disagree with nothing left to reconcile them.

## 8. What is unproven

1. **Whether writing `ThreeFingerSlideEnabled` takes effect without a sign-out.**
   Sources disagree and Microsoft documents nothing; one report has a direct
   registry write changing touchpad behaviour not at all. If it needs a sign-out
   on a given machine, both our gesture and Windows' fire until the user signs
   out once — which is what the Settings note says. *Settles by: writing the
   value on a real laptop and swiping.*
2. **Whether a session-level event tap actually stops the Dock acting.** Every
   comparable macOS trackpad utility works this way, but the alternative (asking
   the user to switch the gestures off in System Settings) is a different
   product. *Settles by: running it on a Mac and swiping.*
3. **HID parsing against a real pad.** The report walk is written from the PTP
   specification, not from a captured descriptor. Serial-mode pads and
   multi-report frames are handled, but untested.
4. **`input -d <display> keyevent` latency for Back.** It boots `app_process`,
   so it is the slowest thing here. If it reads as laggy, a `KEY` verb on the
   daemon is the fix.
5. **Two-finger pinch still does nothing.** Windows sets `MK_CONTROL` in
   `WM_MOUSEWHEEL`'s `wParam` rather than injecting a Ctrl keydown, and SDL2
   reads only the wheel delta — so scrcpy injects a plain scroll with
   `metaState = 0` and no Android app sees a zoom. Out of scope here; it is a
   scrcpy-side problem, not a gesture-recognition one.
