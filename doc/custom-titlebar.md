# Custom window chrome on a shared virtual display

Architecture and design record for drawing our own titlebars over freeform Android
app windows on the Open Android DeX desktop.

**Status: designed, built, measured, abandoned. All code removed.** Both the
launcher-side implementation and the shell-uid daemon (`openandroiddex-wmd/`) have
been deleted from the tree. This document is the only artefact — it is written to
be sufficient to rebuild the daemon from scratch, because that is still worth doing
for a reason unrelated to window chrome ([§10](#10-what-survived-and-why)).

The feature failed on a platform constraint, not on performance. Latency was
brought from 332 ms to 59 ms and had further headroom. What could not be solved is
[§3.1](#31-window-layering-the-constraint-that-ended-the-feature): a non-system
process cannot place a window between two app windows.

**Reference platform.** Samsung SM-S938B (Galaxy S25 Ultra), Android 16 / One UI 8,
non-rooted, USB adb. Host: Windows 11, Tauri v2, bundled scrcpy 3.3.4.
AOSP citations are `android-16.0.0_r1` / `android16-release` unless stated.

---

## 1. Scope

| | |
| --- | --- |
| **Goal** | Per-window chrome (title, icon, minimise/maximise/close) that we control, over unmodified third-party apps, with drag that moves the app window with the bar. |
| **In scope** | The shared-display topology already in use: one virtual display, real Android freeform tasks, our launcher as the shell. |
| **Out of scope** | One display per app (rejected: encoder-instance ceiling, breaks cross-app drag/drop, deletes the launcher). Root. Custom ROM. Signature permissions. |
| **Non-negotiable** | The bar and its window must never visibly separate. A trailing window reads as a slow machine; detached chrome reads as broken. |

### 1.1 Prior art, and a corrected premise

The feature was scoped on the belief that the commercial *Android Dex* implements
Android-side chrome. It does not. Decompilation of its shipped artefacts:

| Artefact | Finding |
| --- | --- |
| `AndroidDex.apk` manifest | No `SYSTEM_ALERT_WINDOW` declared |
| `AndroidDex.apk` (jadx, full tree) | No overlay / caption / task-bounds code |
| `androiddex.jar` → `AppController.startAppOnDisplay` | `ActivityOptions.setLaunchDisplayId()` + `startActivity()` only |
| `android_dex.exe` symbols | `ScrcpyTexturePlugin`, `Win32Window`, `MoveWindow`, `SetWindowPos`, `DwmSetWindowAttribute` |
| `data/app.so` Dart AOT snapshot | `_TitleBar`, `_MiniTitleBar`, `_MaximizeButton`, `_MinimizeButton`, `_snapLeft`, `_snapRight` — one library, adjacent to `scrcpy_window.dart` |
| Runtime log | `new_display=:r vd_system_decorations=false`, `New display: 1080x2340/454 (id=90)`, `JAR launch: com.android.chrome displayId=90` |

Its topology is **one virtual display per app**, each arriving host-side as a
Flutter texture composited into a Win32 window whose titlebar is a Flutter widget
in the same swapchain. Drag is `MoveWindow`. Zero separation is a *structural
property of host-side composition*, not an optimisation, and does not transfer to
a shared display.

`miri2577/dex-launcher` (Flutter/Android TV; its "windows" contain only its own
widgets) and `mrYouki/YoukiDex-Android-Desktop` (SmartDock fork; delegates to the
system caption; `am task resize` behind `su`) were also examined. Neither
implements chrome over foreign windows.

---

## 2. System context

```
┌── Windows host ─────────────────────────┐        ┌── SM-S938B (Android 16) ──────────────────┐
│                                          │        │                                            │
│  Tauri v2 (Rust)                         │        │  ┌ uid 2000 (shell) ──────────────────┐   │
│    scrcpy.rs ── spawns ─────────────────────adb──────▶ scrcpy-server  (virtual display 170)│   │
│    adb.rs                                │        │  │ openandroiddex-wmd  (app_process)   │   │
│                                          │        │  │   └ IActivityTaskManager binder      │   │
│  scrcpy.exe  ◀── H.264 ──────────────────────────────┘                                     │   │
│    └ SDL window = the whole desktop      │        │                                            │
│                                          │        │  ┌ uid 10534 (launcher APK) ──────────┐   │
│                                          │        │  │ LauncherActivity  (desktop shell)   │   │
│                                          │        │  │ Titlebars ── TCP 127.0.0.1:7191 ────┼───┼──▶ wmd
│                                          │        │  └─────────────────────────────────────┘   │
│                                          │        │  ┌ uid 10xxx (third-party apps) ──────┐   │
│                                          │        │  │ freeform tasks on display 170       │   │
└──────────────────────────────────────────┘        └──┴─────────────────────────────────────┴───┘
```

Display 170 is created by `scrcpy --new-display=1920x1080/160
--no-vd-system-decorations`. Everything the user sees is that one display, encoded
once and streamed as one video.

### 2.1 Why a second process on the device

Moving another app's task requires `MANAGE_ACTIVITY_TASKS`
(`protectionLevel="signature|recents"`). The launcher can never hold it. `uid 2000`
does — `com.android.shell` is platform-signed and declares it — and we already run
shell-privileged code there for scrcpy. The daemon exists solely to be the thing
that *can* call the API, on behalf of the thing that *wants* to.

Verified live on device rather than inferred from the manifest:

```
MANAGE_ACTIVITY_TASKS   granted=true      getAllRootTaskInfos() -> 13 root tasks
ACCESS_SURFACE_FLINGER  granted=true      SurfaceControl.Transaction instantiable
INTERNAL_SYSTEM_WINDOW  granted=true      android.window.TaskOrganizer present
```

A process launched as `CLASSPATH=x.dex app_process /system/bin <Main>` runs
`RuntimeInit` rather than `ZygoteInit`, has no application context, and is
therefore outside the hidden-API blocklist that would otherwise block reflective
access to `@hide` framework APIs.

---

## 3. Constraint analysis

The design is almost entirely dictated by four platform behaviours. Establishing
them was the bulk of the work.

### 3.1 Window layering — the constraint that ended the feature

Every window's z-order key is assigned from its *type*, not its stacking position:

```java
// services/core/java/com/android/server/policy/WindowManagerPolicy.java
default int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow,
                                     boolean roundedCornerOverlay) {
    if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
        return APPLICATION_LAYER;                    // == 2
    }
    switch (type) {
        ...
        case TYPE_APPLICATION_OVERLAY: return 11;    // unconditional
        ...
    }
}
```

```java
// services/core/java/com/android/server/wm/WindowState.java  (constructor)
mBaseLayer = mPolicy.getWindowLayerLw(this) * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
// TYPE_LAYER_MULTIPLIER = 10000, TYPE_LAYER_OFFSET = 1000
```

This predicts exactly what we measured on device:

| Window | Policy layer | `mBaseLayer` | Measured |
| --- | --- | --- | --- |
| App window (`TYPE_APPLICATION`, types 1–99) | 2 | 2·10000+1000 = 21000 | 21000 |
| Our bar (`TYPE_APPLICATION_OVERLAY` = 2038) | 11 | 11·10000+1000 = 111000 | 111000 |

The match confirms One UI 8 uses the stock layer table for these types.

Crucially, layer 11 is **unconditional** — no permission alters it.
`INTERNAL_SYSTEM_WINDOW` only changes `TYPE_SYSTEM_ALERT` (12 vs 9),
`TYPE_SYSTEM_OVERLAY` (23 vs 10) and `TYPE_SYSTEM_ERROR` (27 vs 9).

Layers are not merely sort keys. `DisplayAreaPolicyBuilder` allocates a
`DisplayArea.Tokens[]` indexed by policy layer and classifies each via
`typeOfLayer()` into `LEAF_TYPE_TASK_CONTAINERS(1)` / `LEAF_TYPE_IME_CONTAINERS(2)`
/ `LEAF_TYPE_TOKENS(0)`. Every freeform task lives inside the *one* TaskDisplayArea
at layer 2; every application overlay lives inside a *sibling* container at layer
11. In the SurfaceFlinger hierarchy those are peer subtrees.

> **Therefore: the entire overlay container is above the entire task container.
> There is no z-position at which a bar can sit between two app windows.** One bar
> per window is not expressible. `mSubLayer` cannot help — it orders a child
> against its own parent window only.

Everything in [§7](#7-occlusion-resolution) is a mitigation for this, and the
mitigation is why the feature was dropped.

`TYPE_ACCESSIBILITY_OVERLAY` (layer 31) is worse, not better.

### 3.2 Overlay suppression

`Window#setHideOverlayWindows(boolean)` (Android 12+, gated on
`android.permission.HIDE_OVERLAY_WINDOWS`) causes WindowManager to hide every
`TYPE_APPLICATION_OVERLAY` and system-alert window while the caller is visible, via
`WindowState#setForceHideNonSystemOverlayWindowIfNeeded`. The only opt-out is
`LayoutParams.isSystemApplicationOverlay()`, which requires
`SYSTEM_APPLICATION_OVERLAY` — a signature permission.

Samsung Settings uses it. During bring-up this presented as "the overlay renders
with a correct frame but `isReadyForDisplay()=false`", and it takes down our
taskbar too. **Not overridable. Any app may do this.**

### 3.3 Move vs resize — why a drag is affordable at all

`IActivityTaskManager.resizeTask` is *not* `oneway`:

```aidl
// core/java/android/app/IActivityTaskManager.aidl
interface IActivityTaskManager {
    void resizeTask(int taskId, in Rect bounds, int resizeMode);
}
```

It nonetheless returns almost immediately, because with shell transitions enabled
(the default) `ActivityTaskManagerService.resizeTask` does not resize inline — it
constructs a `Transition(TRANSIT_CHANGE, …)` and hands it to
`TransitionController.startCollectOrQueue`, which **queues** if another sync is
active. The call returning is not the move happening. This distinction is the whole
reason [§6](#6-the-drag-control-loop) exists.

The move/resize split is real and originates in bounds diffing:

```java
// ConfigurationContainer
BOUNDS_CHANGE_NONE = 0, BOUNDS_CHANGE_POSITION = 1, BOUNDS_CHANGE_SIZE = 1 << 1;

// WindowContainer.onRequestedOverrideConfigurationChanged
if (diff == BOUNDS_CHANGE_NONE) return;
if ((diff & BOUNDS_CHANGE_SIZE) != 0) { onResize(); } else { onMovedByResize(); }
```

For a **floating** windowing mode — `WindowConfiguration.isFloating()` is true for
`WINDOWING_MODE_FREEFORM` — `TaskFragment.computeConfigResourceOverrides` skips the
parent-intersection branch, so a pure translation changes no `screenWidthDp`,
`screenHeightDp` or `smallestScreenWidthDp`. **No `Configuration` diff reaches the
app, so no relayout is requested.** A resize does the opposite.

This is why the protocol separates `MOVE`/`DRAG` from `BOUNDS`: sending identical
geometry through the resize path would re-measure the app every frame.

`RESIZE_MODE_SYSTEM = 0`; `RESIZE_MODE_PRESERVE_WINDOW = RESIZE_MODE_USER = 1`
(`android.app.ActivityTaskManager`, `@hide`). `cmd activity task resize` uses
mode 0, as do we.

### 3.4 Bounds clamping

WindowManager refuses to let a freeform task leave the display:

```java
// services/core/java/com/android/server/wm/Task.java — fitWithinBounds()
// MINIMUM_VISIBLE_WIDTH_IN_DP = 48  (WindowState)
overlapPxX = dipToPixel(MINIMUM_VISIBLE_WIDTH_IN_DP, density);
overlapLR  = Math.min(overlapPxX, taskWidth);
```

At 160 dpi (density 1.0) that is 48 px of the task that must remain on screen.

This produced a false bug report during bring-up: drags appeared to track at ~64%
of the commanded distance. Instrumenting the touch handler showed the full gesture
arriving (145 events, `DOWN`→`MOVE`s→`UP`, no `CANCEL`) with correct commanded
positions. A 1152×825 window on a 1920×1080 display simply has almost no travel.
Repeating with a 700×450 window gave exactly 1:1 tracking (commanded +300/+200,
actual +300/+200).

### 3.5 Input dispatch guarantees

An overlay that moves while it owns a gesture was assumed to be dangerous. It is
not. Touch targets are latched at `ACTION_DOWN` and are not re-hit-tested:

- `InputDispatcher::DispatcherTouchState::findTouchedWindowTargets()` only computes
  a new target when `newGesture` is true (`ACTION_DOWN`, `ACTION_SCROLL`,
  `ACTION_HOVER_ENTER`).
- On every `WindowInfos` update, `updateFromWindowInfo()` cancels only for windows
  that were *removed* (`"touched window was removed"`), not for windows that moved
  or resized.
- Mid-gesture re-targeting requires `FLAG_SLIPPERY` (`0x20000000`, `@hide`/`@TestApi`),
  which a normal app cannot set.
- Losing focus does **not** cancel touch — that path uses
  `CANCEL_NON_POINTER_EVENTS` ("focus left window"), keys only. This matters
  because our bar is `FLAG_NOT_FOCUSABLE`.

`getRawX()`/`getRawY()` are display-absolute and unaffected by the window moving,
so a drag can be computed as `grabTaskOrigin + (raw − grabRaw)` — absolute, with no
error accumulation across frames.

---

## 4. Architecture

### 4.1 Components

| Component | Process / uid | Responsibility |
| --- | --- | --- |
| `WmDaemon` | `app_process`, uid 2000 | Sole holder of `IActivityTaskManager`. Task enumeration, bounds mutation, drag pacing. No UI, no policy. |
| `DragBench` | `app_process`, uid 2000 | Synthetic drag client for measurement. Not shipped. |
| `WmClient` | launcher, uid 10534 | Transport. Socket lifecycle, polling, latest-wins coalescing, latency estimation. No UI. |
| `Titlebars` | launcher, uid 10534 | All policy and rendering: bar geometry, occlusion, drag gesture, lead compensation. |

The split is a privilege boundary, not a layering preference. Policy lives entirely
on the launcher side; the daemon is a mechanism with no opinions, which keeps the
privileged surface small and auditable.

### 4.2 Transport selection

| Channel | Cost/op | Verdict |
| --- | --- | --- |
| `adb shell <cmd>` (host-initiated) | 61 ms | Host round trip per command |
| `am task resize` (on device) | 24 ms | Boots an `app_process` VM per invocation |
| `cmd activity task resize` | 11 ms | No VM spawn, still a process |
| `content query` — **our existing request queue** | ~990 ms | `content` is itself a Java shell tool |
| **Loopback TCP → resident binder proxy** | **0.35 ms** | Selected |

A 120 Hz drag frame has an 8.33 ms budget. Every process-per-command path is
disqualified before WindowManager is even reached.

Loopback TCP rather than a `LocalSocket`: SELinux policy between `untrusted_app`
and a shell-domain abstract socket is not dependable, whereas app→loopback TCP is.
Cost: the launcher must declare `android.permission.INTERNET`, without which an app
cannot create *any* socket, including to `127.0.0.1`.

### 4.3 Wire protocol

Line-oriented ASCII, one request per line, one response per line. Chosen so the
daemon can be driven by hand with `nc` during diagnosis — which is how several bugs
were found.

```
PING                             -> OK <uid>
LIST                             -> TASK <id> <display> <mode> <vis> <l> <t> <r> <b> <pkg>
                                    ... (topmost first)
                                    END
GRAB   <taskId>                  -> OK <w> <h>
DRAG   <x> <y>                   -> OK <actualX> <actualY>
DROP                             -> OK
MOVE   <taskId> <x> <y>          -> OK
BOUNDS <taskId> <l> <t> <r> <b>  -> OK
FRONT  <taskId>                  -> OK
CLOSE  <taskId>                  -> OK
BYE                              -> (closes)
ERR <reason>                        on any failure
```

Design notes:

- **`LIST` order is load-bearing.** `getAllRootTaskInfos()` returns tasks
  topmost-first; verified against `dumpsys activity activities`, which matched
  exactly. [§7](#7-occlusion-resolution) depends on it, so it is part of the
  contract, not an accident.
- **`DRAG` echoes the *actual* post-call position, not the requested one.** The
  client renders chrome at the echo. See [§6.3](#63-chrome-is-slaved-to-the-echo).
- **`GRAB`/`DROP` bracket a drag** and cache the task's size, so a `DRAG` frame is
  one binder call with no lookup. State is per-connection: a dropped socket cannot
  strand a drag.
- **`FRONT` probes for an available implementation.** `moveRootTaskToFront` does
  not exist on this build; the daemon falls back through `setFocusedTask(int)` →
  `moveTaskToFront(IApplicationThread, String, int, int, Bundle)`. Hidden-API
  availability varies by build and OEM — probe, never assume.

### 4.4 Threading

**Daemon.** `ServerSocket(7191, 8, InetAddress.getByName("127.0.0.1"))`, thread per
connection, `TCP_NODELAY` (a drag is many tiny writes; Nagle would batch them into
latency). Loopback-only bind is a security requirement: this socket speaks with
shell authority. Per-connection `Grab` state needs no locking. No `Looper` is
prepared — outgoing binder transactions are synchronous on the calling thread, and
the daemon registers no callbacks.

**Client.** One `HandlerThread` for all I/O; results marshalled to the main thread
via `Handler`. Nothing blocking ever runs on the UI thread. Task polling is 250 ms
and **pauses during a drag** so it cannot contend for the socket or the WM lock —
a consequence that had to be handled explicitly in [§7](#7-occlusion-resolution).

### 4.5 Coordinate spaces

One space throughout: **display-absolute pixels on the virtual display.** Task
bounds from `getTaskBounds()`, `MotionEvent.getRawX/Y()`, and
`WindowManager.LayoutParams.x/y` for a `Gravity.TOP|START` overlay all agree, so no
conversion exists anywhere in the drag path — deliberately, since a coordinate
conversion is a place for a sign error to hide.

Density is read from `Display.getRealMetrics()` rather than `Resources`, because a
`wm density` change is not delivered to a paused activity — the launcher sits paused
behind app windows, so `Resources` lags indefinitely.

---

## 5. Bar geometry

A bar occupies the strip immediately **above** its task:

```
bar   = Rect(task.left, task.top − barH, task.left + max(120dp, task.width), task.top)
barH  = 34dp,  min visible width 48dp
```

Each bar is its own overlay window, but the window is a **clipping wrapper**, not
the bar:

```
overlay window   x = visibleSpan.left,  width = visibleSpan.width()   ← clipped
  └ bar content  width = full bar width, leftMargin = fullLeft − x    ← never resized
```

The indirection exists because narrowing the *window* re-lays-out its contents:
right-aligned buttons slide inward and park against the cut edge, so a partly
hidden bar reads as a shrunken one whose controls no longer align with the window
corner. Keeping the content at full width and sliding it under the clip preserves
the invariant that **a control is drawn where the window's corner actually is, or
not at all.**

---

## 6. The drag control loop

This is the substantive part of the design.

### 6.1 Open loop — why the naive version fails

Initial implementation: one `resizeTask` per touch frame at 120 Hz.

```
window behind finger: median 193.8 px, p95 344.9 px   (≈332 ms at 583 px/s)
```

`resizeTask` returns in microseconds ([§3.3](#33-move-vs-resize--why-a-drag-is-affordable-at-all))
but the work is queued into `TransitionController`. Requests were being produced far
faster than WindowManager drained them, so **the error was not a constant lag but an
unbounded backlog** — it grew for as long as the drag continued.

No transport improvement could have helped. The queue was inside `system_server`.

### 6.2 Closed loop — let the consumer set the rate

Each `DRAG` issues the move and then waits for it to land before replying:

```java
awaitCommit(taskId, wantX, wantY, before):
    deadline = now + 28ms
    loop:
        live = getTaskBounds(taskId)
        if live.left == wantX && live.top == wantY  -> return live   // landed
        if live != before                           -> return live   // moved (clamped)
        sleep 250µs
    return live                                                      // gave up
```

Two properties matter:

- **The reply *is* the flow-control signal.** The client coalesces latest-wins, so
  when a frame completes the next one carries the newest pointer position, not a
  stale queued one. At most one move is ever outstanding, so lag is bounded at one
  commit instead of accumulating.
- **It returns on *any* movement, not on equality.** A clamped request
  ([§3.4](#34-bounds-clamping)) never reaches the position asked for; waiting for
  an equality that cannot occur would stall every frame at the display edge.

| | Open loop | Closed loop |
| --- | --- | --- |
| Median error | 193.8 px | **34.1 px** |
| p95 | 344.9 px | **43.9 px** |
| Max | 359.3 px | **48.9 px** |
| At 583 px/s | 332 ms | **59 ms** |

The distribution also tightened (max 49 px against a 34 px median), which is the
real result: the error became *bounded* rather than time-dependent.

### 6.3 Chrome is slaved to the echo

The bar is drawn at the position WindowManager **reports**, never at the pointer.

Drawing at the pointer puts the bar one to two commits ahead of its own window,
which at drag speed is a visible gap. Slaving it to the echo means both trail
together and read as one rigid window. This is the invariant that makes the whole
approach viable, and it is why `DRAG` returns a position at all.

### 6.4 Lead compensation

Closed-loop pacing removes the backlog but leaves a floor: a move aimed at where
the pointer *is* lands one commit later. So the request is aimed at where the
pointer *will be*:

```
target = pointer + clamp(velocity × min(commitLatency, 40ms), ±90px)
velocity  : EMA over raw touch deltas, α = 0.4
commitMs  : EMA of observed DRAG round trips, commitMs = (3·commitMs + rtt) / 4
```

Both the latency and the velocity are measured, not configured — the loop
self-tunes to the device and the current load.

**The safety property is [§6.3](#63-chrome-is-slaved-to-the-echo).** Because the bar
follows the echo and not the prediction, a wrong prediction costs positional
accuracy and *cannot* separate the chrome from its window. That asymmetry is what
makes extrapolation acceptable here at all. The two caps bound the damage from a
noisy velocity sample or a stalled WindowManager.

### 6.5 Coalescing

Latest-wins, never drop:

```java
drag(x, y, echo):
    wantX = x; wantY = y; wantEcho = echo          // overwrite
    if (pending.compareAndSet(false, true)) io.post(pumpDrag)

pumpDrag():                                        // drains until nothing newer
    while (dragging && (wantX,wantY) != lastSent)
        lastSent = (wantX,wantY); request("DRAG …"); post echo to main
    pending.set(false)
```

The first implementation *discarded* a frame when the I/O thread was busy. That is
wrong in a specific and visible way: the final frame of a gesture carries the final
position, so losing it leaves the window short of where the pointer was released.
`ACTION_UP`'s coordinates are replayed for the same reason — they can be beyond the
last `ACTION_MOVE` seen.

### 6.6 Sequence

```
ACTION_DOWN   Titlebars ─ FRONT ─▶ wmd ─▶ setFocusedTask()
                        ─ GRAB  ─▶ wmd ─▶ cache {taskId, w, h}
              raise task in cached z-order; recompute occlusion locally

ACTION_MOVE   target = pointer + lead                        (§6.4)
              ─ DRAG x y ─▶ wmd ─▶ resizeTask()              (§3.3, move-only)
                                   awaitCommit()             (§6.2, paces the loop)
              ◀─ OK ax ay ── actual committed position
              place bar at (ax, ay); recompute occlusion      (§6.3, §7)

ACTION_UP     replay final coordinates, then DROP             (§6.5)
              resume 250 ms polling
```

---

## 7. Occlusion resolution

Given [§3.1](#31-window-layering-the-constraint-that-ended-the-feature), a
background window's bar is *always* painted above a foreground app. It cannot be
z-ordered correctly, so it must be **clipped** to the region nothing in front
covers. Three attempts, each failing differently:

**Attempt 1 — hide past a coverage threshold (30%).** Wrong in kind, not degree: a
threshold *permits* up to 30% of a bar to cut through, and during a drag coverage
sweeps continuously through every value from zero, so the artefact is visible for
most of the gesture.

**Attempt 2 — clip against app window rects.** Missed the common case. Two windows
whose *bars* overlap but whose *windows* do not — routine, because a bar sits just
above its window's top edge. Corrected by treating a window's visual extent as
including its bar: each occluder rect is extended upward by `barH`.

**Attempt 3 — clip by narrowing the window.** Squashed the bar; see [§5](#5-bar-geometry).

Final algorithm, run over the cached task list (topmost first):

```
above = []
for task in snapshot:                       # z-order, front to back
    if task has a bar:
        strip   = bar.rect()
        hidden  = [ intersect(c, strip) for c in above if c overlaps strip vertically ]
        span    = widest maximal sub-interval of strip.x not covered by `hidden`
        if span.width < 48dp: hide bar else: clip bar to span
    extent = task.rect(); extent.top -= barH          # a window includes its chrome
    above.append(extent)
```

A bar is a thin strip, so this is exact 1-D interval subtraction over x — no
rectangle union required. Complexity is O(n²) in visible windows, trivial at
realistic n.

**Occlusion must be recomputed on every drag echo, not only on poll.** Polling
pauses during a drag ([§4.4](#44-threading)), so stationary windows that a dragged
window slides over would otherwise keep grab-time visibility for the whole gesture.
The recomputation needs no daemon call — the other windows are stationary and the
dragged window's live position arrives with the echo — so it is local arithmetic.
The grabbed task is also promoted to the front of the cached z-order on
`ACTION_DOWN`, to match the `FRONT` just issued.

---

## 8. Latency budget

One drag frame, measured on the reference device with four apps open:

| Stage | Cost | Notes |
| --- | --- | --- |
| Touch → launcher handler | ~1 frame | Choreographer-batched |
| `DRAG` socket round trip | 0.7 ms median | includes daemon-side work |
| ├ binder `resizeTask` | ~0.06 ms | queues a `Transition`, does not resize |
| ├ `getTaskBounds` ×N | 0.35 ms each | O(1); `getAllRootTaskInfos` would be O(open apps) |
| └ `awaitCommit` | ~16.7 ms median, 48 ms p95 | **dominant term — this is WindowManager** |
| Echo → `updateViewLayout` → composited | ~1 frame | |
| **Android-side total** | **≈59 ms** | measured commanded-vs-actual |
| scrcpy encode → adb → decode → present | 60–120 ms | not in the above; applies to everything |

Two observations shaped the design:

1. **WindowManager is the bottleneck by an order of magnitude.** Optimising
   transport further is pointless; pacing to WM's throughput is the only lever.
2. The scrcpy round trip is *uniform* — it delays bar and window equally, so it
   costs input latency but cannot cause separation. **Unless** the pointer is not
   in the video: in scrcpy's default SDK mouse mode the cursor is a host GPU cursor
   plane with ~0 latency drawn over video that is 60–120 ms old, so a dragged
   window provably cannot catch the cursor. `--mouse=uhid` moves pointer rendering
   onto the device, putting cursor and window in the same encoded frame. Identified,
   never tested, reverted.

### 8.1 Cost that scaled with open apps

An early `DRAG` implementation read the task's size via `getAllRootTaskInfos()`,
enumerating **every** root task — making the hot path O(open apps). Replacing it
with `getTaskBounds(int)`:

| With 4 apps open | Before | After |
| --- | --- | --- |
| p95 round trip | 14.1 ms | **3.35 ms** |
| Missed frames (2 s @ 120 Hz) | 22 | **4** |

Better under four apps than the previous code managed under one.

---

## 9. Alternatives evaluated

| Approach | Verdict |
| --- | --- |
| **`TaskOrganizer` + leash translation** — what AOSP's own desktop windowing does (`DragPositioningCallbackUtility.setPositionOnDrag` on the task leash per frame, real bounds only on release). | **Rejected.** `registerOrganizer()` is global, not scoped by display or windowing mode, and the controller holds a LIFO `ArrayDeque<ITaskOrganizer>`. Registering *steals every task* from the incumbent — which on this device is Samsung's SystemUI — and once organized, WM stops positioning the task, making us responsible for reimplementing WMShell. Reachable at uid 2000; catastrophic to use. |
| **Per-app virtual displays** (the competitor's topology) | **Out of scope** (§1). Concurrent encoder-instance ceiling, breaks cross-app drag/drop, deletes the launcher and the shared desktop. |
| **Host-side chrome** in the Tauri layer | **Viable, unbuilt.** Correct z-order for free and 0 ms chrome, but app pixels live inside one shared video frame that cannot be repositioned per window, so drag becomes outline-then-commit. |
| **Native Android 15/16 desktop captions** | Not customisable by a third-party launcher. Enabling them also requires system decorations on the display, which reintroduces Samsung's secondary home and nav bar. |
| **Accessibility overlay** | Layer 31 — strictly worse than 11 for interleaving. |

---

## 10. What survived, and why

Nothing survived as code — `openandroiddex-wmd/` was deleted along with the rest.
What survived is the finding, and it is the most valuable thing this work produced:

**The transport is 990 ms → 0.35 ms, and that has nothing to do with titlebars.**

Every launcher→PC command today (`density`, `key`, `qs`, `fullscreen`, `window`)
rides the `content query` ContentProvider queue and pays ~990 ms per round, because
`content` boots a JVM per invocation. A resident shell-uid daemon holding a binder
proxy does the same work in 0.35 ms. That is the largest single latency win
available anywhere in the desktop, it carries no architectural risk, and it is
entirely independent of window chrome.

Rebuilding the daemon for that purpose needs only [§4.3](#43-wire-protocol),
[§4.4](#44-threading) and [§12](#12-appendix--reproducing-the-measurements). It does
**not** need the drag machinery in [§6](#6-the-drag-control-loop): `LIST`, `FRONT`,
`CLOSE` and `BOUNDS` cover the existing command set, and `GRAB`/`DRAG`/`DROP` exist
only for dragging.

Also reverted, and worth revisiting deliberately: `--mouse=uhid`
([§8](#8-latency-budget)). It benefits One UI's own captions too.

---

## 11. If this is revisited

Solve [§3.1](#31-window-layering-the-constraint-that-ended-the-feature) first. Lag
was never what made the result unacceptable — it went 332 ms → 59 ms with headroom
remaining. Z-order was. Any retry must answer "what happens to a background
window's chrome" before any UI code is written. Three answers that do not require
defeating the layer model:

1. **Chrome for the focused window only.** One bar, always frontmost, always
   correct. Loses at-a-glance identification of background windows.
2. **Host-side chrome**, outline-drag, commit on release.
3. **Accept clipped chrome** — what was built. Correct, but a bar missing its own
   buttons because the window corner is covered reads as broken. This is the
   judgement that ended the work.

---

## 12. Appendix — reproducing the measurements

The daemon no longer exists in the tree; these are the commands it was built and
run with, kept so it can be reconstructed from [§4.3](#43-wire-protocol).

```bash
# build (javac + d8; no Gradle, no Android project)
#   javac -cp $SDK/platforms/android-36/android.jar -d build/classes src/**/*.java
#   $SDK/build-tools/36.1.0/d8 --min-api 26 --output build/dex build/classes/**/*.class
bash openandroiddex-wmd/build.sh
adb push openandroiddex-wmd/openandroiddex-wmd.dex /data/local/tmp/wmd.dex

# run as shell (uid 2000)
adb shell CLASSPATH=/data/local/tmp/wmd.dex app_process /system/bin \
    com.ccrstech.openandroiddex.wmd.WmDaemon

# drive by hand
adb shell "printf 'LIST\nBYE\n' | toybox nc 127.0.0.1 7191"

# synthetic drag: <displayId> <hz> <seconds>
adb shell CLASSPATH=/data/local/tmp/wmd.dex app_process /system/bin \
    com.ccrstech.openandroiddex.wmd.DragBench 170 120 2
```

A process started via `adb shell` dies with the adb connection; persisting it
requires reparenting to init (`setsid`/`nohup`).

Verifying the layer constraint directly:

```bash
adb shell dumpsys window windows | grep -E 'mBaseLayer|Sys2038'
#   Sys2038:…LauncherActivity   mBaseLayer=111000
#   app window                  mBaseLayer=21000
```

---

## 13. Peer-Review Synthesis & Verified Technical Consensus

A comprehensive peer review against AOSP (`android-16.0.0_r1` / `android16-release`) source code and live device behavior verified the following final conclusions:

1. **`TYPE_APPLICATION_OVERLAY` is fundamentally unusable for per-window titlebars:**
   - Policy Layer 11 (`mBaseLayer = 111000`) forces overlays above Policy Layer 2 (`mBaseLayer = 21000`). Interleaving overlay bars between task windows is physically impossible in Android's window tree.
   - Security-sensitive apps calling `setHideOverlayWindows(true)` hide all overlay windows unconditionally.

2. **Raw `SurfaceControl` child layers cannot process touch input:**
   - While `uid 2000` with `ACCESS_SURFACE_FLINGER` can create child layers, a bare `SurfaceControl` has no `InputWindowHandle` registered with `InputDispatcher`. Click/touch events cannot be routed to it.
   - Accessing task leashes directly requires `TaskOrganizer`, which registers globally and steals task management from Samsung's SystemUI—introducing unacceptable risks on a daily-driver device.

3. **System decorations introduce unwanted System UI:**
   - Enabling system decorations on display 170 brings back Samsung's secondary home screen and navigation bar, overriding custom launcher UI.

4. **Host-Side Chrome is the sole viable path for per-window titlebars (Future Phase):**
   - Host-side titlebars (Tauri/Win32) solve Z-ordering and overlay suppression 100%.
   - To handle the ~120ms video stream latency gap, host titlebars must use **Wireframe / Outline Dragging** (moving the titlebar and frame preview at 0ms, committing task bounds via `wmd` on release or throttled intervals).

5. **Immediate focus — rebuild `wmd` as a command transport, not window chrome:**
   - Replace the ~990 ms `content query` queue with 0.35 ms loopback TCP for shell commands (`density`, `key`, `fullscreen`, `app start`). Note the daemon has been deleted from the tree; see [§10](#10-what-survived-and-why) for what a rebuild actually needs.

### 13.1 Detail behind conclusion 2 — worth keeping

The reason `ACCESS_SURFACE_FLINGER` does not rescue the `SurfaceControl` approach is
more specific than "you need a `TaskOrganizer`", and the specifics are what make it
final:

- **SurfaceFlinger's model is capability-by-handle, not permission-by-caller.**
  `LayerHandle::getLayer()` (`frameworks/native/services/surfaceflinger/FrontEnd/LayerHandle.cpp`)
  performs no uid or pid check, and `eReparent` (`0x00008000`) does not appear in
  `layer_state_t::sanitize()` at all. Holding a parent's `IBinder` is the entire
  authorisation. The permission is therefore irrelevant in *both* directions: with
  the handle you would not need it, and with it you still cannot obtain the handle.
- **There is no layer-lookup API.** SurfaceFlinger never lets a client enumerate or
  resolve another process's layer. `RunningTaskInfo` carries no `SurfaceControl`,
  and no `IActivityTaskManager` / `IWindowManager` method returns one for a task id.
- **All three routes that do yield a leash are global registrations** that evict the
  incumbent organizer (Samsung SystemUI): `TaskOrganizer` (per-task leashes, LIFO
  `ArrayDeque`, steals everything), `DisplayAreaOrganizer` (gives the TaskDisplayArea
  leash but no per-task handle; `assignChildLayers()` re-asserts z-order every layout
  pass), and `registerTransitionPlayer` (transition-scoped leashes; you become the
  device's animator).
- **Even holding a leash, two things still break it:** a shell `app_process` cannot
  create an input channel — `IWindowManager#openSession()` throws
  `IllegalStateException("Unknown pid=…")` because `ATMS.mProcessMap` has no
  `WindowProcessController` for it — and `WindowContainer#migrateToNewSurfaceControl`
  reparents only `mChildren` before destroying the old surface, taking any foreign
  child layer with it.

### 13.2 The mechanism to use if this is ever rebuilt

Pacing `resizeTask` ([§6.2](#62-closed-loop--let-the-consumer-set-the-rate)) was the
wrong primitive. AOSP's own `VeiledResizeTaskPositioner` /
`DragPositioningCallbackUtility` never resize during a drag. They:

1. `WindowContainerTransaction#reorder(token, onTop, includingParents)` on drag start;
2. per frame, `SurfaceControl.Transaction#setPosition(leash, x, y)` +
   `setFrameTimeline(Choreographer.getInstance().getVsyncId())` + `apply()`;
3. `WindowContainerTransaction#setBounds(token, bounds)` through a `TRANSIT_CHANGE`
   transition on release.

Desync then equals the video round trip alone (~45–70 ms) and the motion is smooth,
because SurfaceFlinger repositions the layer every vsync. It still requires the
`TaskOrganizer` registration above, which is why it was not adopted.

### 13.3 Two corrections to the host-side plan

- **Live sub-rectangle re-compositing does not work.** Cropping a window out of the
  decoded frame and redrawing it at the dragged position fails because occluded
  regions of that window are physically absent from the frame, the vacated region
  has no pixels to fill it, and shadows and anti-aliased corners are pre-blended
  with the old background with no alpha to recover. The workable variant is a
  **frozen ghost**: hide the leash on-device (`Transaction#setAlpha(leash, 0f)`),
  drag a host-side snapshot at 0 ms, reveal and cross-fade on drop.
- **scrcpy cannot be drawn over.** It runs as a separate process rendering to its
  own SDL/Direct3D window and exposes no frames; there is no library
  (Genymobile/scrcpy#3498, closed as not planned). Host-side chrome therefore means
  either embedding its `HWND` and floating borderless chrome windows above it, or
  replacing the client with our own decode path and pinning the server protocol
  version. This is a materially larger change than "render titlebars over the canvas".

### 13.4 The framing worth remembering

Split chrome by whether it must register with app pixels. The taskbar, launcher and
tray have nothing to stay glued to, so they belong **host-side**, where they are
immune to overlay suppression and cost 0 ms. Per-window titlebars must stay welded
to their window, so they belong **device-side**, where bar and window are the same
pixels in the same encoded frame and separation is impossible by construction. This
is the only split in which the failure mode that ended this work cannot occur.

