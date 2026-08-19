# Custom window chrome, second attempt — the layer model was never the only door

> **STATUS: SHIPPED.** Route A works and is running on the reference device: per-window
> title bars drawn by us, inside each app's own window surface, with the app's content
> inset out of the way. Read [§0.2](#02-what-shipped) first — it is the implementation
> record. [§0.1](#01-second-measurement-pass--accessibility-was-blocked-then-unblocked)
> explains the detour that nearly changed the architecture, and is kept because the
> constraint it documents will come back for anyone on a managed device.

A new design record, written after `custom-titlebar.md` and `custom-titlebar-proposal.md`
concluded that device-side per-window chrome is impossible. That conclusion rests on a
premise that is true but incomplete, and the incompleteness is where the whole feature
lives.

**Reference platform** unchanged: SM-S938B (Galaxy S25 Ultra), Android 16 / One UI 8,
non-rooted, USB adb, scrcpy 3.3.4 with `--new-display=1920x1080/160
--no-vd-system-decorations`, launcher APK at uid ~10534, `wmd` daemon at uid 2000.
AOSP citations are `android16-release` unless stated.

---

## 0. Measured on device — 2026-07-31

The gate in [§8](#8-the-gate) has now been **run** against SM-S938B (serial
RFCY10FRKBK) with a live scrcpy display, using the rebuilt `openandroiddex-wmd`
(`Probe`). Four results change the design; three of them contradict what the
source review predicted, so read this before the rest of the document.

**The scrcpy display is id 7**, `virtual:com.android.shell,2000,scrcpy,1`,
1920×1080/160, `type=VIRTUAL`. Live tasks were Chrome (932), Bolt (930), the
launcher (929) and Settings (928).

| # | Question | Measured | Effect on the design |
| --- | --- | --- | --- |
| 1 | Is One UI's desktop shell live on the scrcpy display? | **Yes.** `DesktopDisplayModeController: Display#7 isDesktopFirst=true`, `DesktopModeWindowDecorViewModel DesktopModeStatus=true`, `maxTaskLimit=5`. `FreeformTaskListener` owns tasks 932/930/928/927. Per task, SurfaceFlinger already has `Decor container of Task=N` (child of the Task layer, `z=30000`, `TRUSTED_OVERLAY|SPY`, touchable region inflated 10 px for resize) and `Freeform Outline`. | §6's source-derived prediction that `isDesktopModeSupportedOnDisplay` returns false for `TYPE_VIRTUAL` is **wrong for this build** — Samsung patched it. The decor machinery is running; it simply draws no caption. Task bounds == app bounds on every task, so **no caption strip is reserved by the platform**. |
| 2 | Does plain `applyTransaction(setBounds)` move an organized leash? | **Yes, in 2.49 ms.** `Layer [1682] Task=932 … toDisplayTransform={tx=384 ty=256}` followed the requested bounds. `startNewTransition` also works, at 3.23 ms. | §3.2's headline — "`applyTransaction` will not move a surface on this device" — is **wrong**. The `isOrganized()` early-returns are real, but One UI's `FreeformTaskListener` repositions the surface itself from `onTaskInfoChanged`. **Use `applyTransaction` as the mover.** It is cheaper and, crucially, does not enter the transition queue, so per-frame dragging is not serialized. The old doc's §6 closed-loop pacing (332 ms → 59 ms) is obsolete. |
| 3 | Can we reserve a caption strip with `MANAGE_ACTIVITY_TASKS` alone? | **Yes.** `setAppBounds` + `addInsetsSource(captionBar())` on Bolt (930) gave `bounds=613,33-1087,1061` with `appBounds=613,67-1087,1061` — exactly 34 px reserved, task bounds unchanged, no TaskOrganizer, no accessibility service. | [§3.1](#31-the-one-geometric-constraint-and-its-aosp-sanctioned-answer) is confirmed on hardware. This is the half of Route A that is independently valuable. |
| 4 | Is the launcher stuck in the home bucket? | **No.** All four display-7 tasks report `activityType=1` (STANDARD), including the launcher's task 929. | [§7.1](#7-two-fixes-worth-taking-regardless-of-which-route-wins) does not apply as feared. The taskbar's layer-11 overlay is a choice, not a forced workaround — but moving it to a STANDARD `alwaysOnTop` task is still the fix for `setHideOverlayWindows`. |

Also settled, incidentally:

- **`getAllRootTaskInfosOnDisplay` returns topmost-first.** Index 0 was Chrome, which
  `WMShell` independently reported as `focused=true`, and the order matched
  `dumpsys activity containers` exactly. The old doc's assumption holds.
- **Reachable at uid 2000, all verified live**: `getWindowOrganizerController`,
  `applyTransaction`, `startNewTransition`, `wct.setBounds` / `setAppBounds` /
  `reorder` / `setFocusable` / `setAlwaysOnTop` / `addInsetsSource`,
  `registerTaskStackListener`, and `android.app.TaskStackListener` (so event-driven
  ordering is available, not just polling).
- **Not reachable**: `IWindowManager#setShouldShowSystemDecors` does not exist on this
  build. The §6 "turn on native captions" experiment therefore needs a different knob
  (or the scrcpy flag) and is still open.
- **`com.android.shell` grants** confirmed on device: `MANAGE_ACTIVITY_TASKS`,
  `REMOVE_TASKS`, `INTERNAL_SYSTEM_WINDOW`, `ACCESS_SURFACE_FLINGER`,
  `ADD_TRUSTED_DISPLAY`, `START_ACTIVITIES_FROM_BACKGROUND`, `MANAGE_DISPLAYS`,
  `INJECT_EVENTS`, `WRITE_SECURE_SETTINGS`, and — unexpectedly —
  `EMBED_ANY_APP_IN_UNTRUSTED_MODE` (`GRANTED_BY_ROLE`). `REMOVE_TASKS` being present
  means orphan-chrome reaping has a mechanism.
- **Two `addInsetsSource` overloads** exist, both 7-arg, differing only in the 5th
  parameter (`Insets` vs `Rect`). Bind by parameter *type*, not arity — picking wrong
  throws inside `system_server`.
- **`setAppBounds` outlives its inset source.** When the owner binder dies WM drops the
  captionBar source, but the app-bounds override persists and must be cleared
  explicitly. The daemon owns this cleanup.

`maxTaskLimit=5` is a real constraint worth restating: Route B's one-chrome-task-per-window
would halve the usable window count to 2–3. Route A adds no tasks.

---

## 0.2 What shipped

Route A, implemented. The pieces, and what each one is for:

| Component | Where | Responsibility |
| --- | --- | --- |
| `WmDaemon` + `Wm` + `Refl` | `openandroiddex-wmd/`, uid 2000 | Sole holder of `MANAGE_ACTIVITY_TASKS`. Task enumeration, bounds, z-order, focusability, caption-strip reservation. No UI, no policy. |
| `Probe` | same dex | One-shot diagnostics: `caps`, `list`, `move`, `strip`, `sig`, `stack`. Kept — it is the tool that settles hidden-API shape questions on a new build. |
| `WmClient` | launcher, uid 10534 | Loopback-TCP transport. Socket lifecycle, line protocol, reconnect. |
| `CaptionService` | launcher, uid 10534 | All policy and rendering: which windows get chrome, caption geometry, drag, buttons. |
| `wm.rs` | Tauri host | Same protocol from the PC over `adb forward`. Not yet wired to UI. |

### 0.2.0 Where the surface lives is the whole design

The caption is attached to **One UI's own caption window** for the task
(`attachAccessibilityOverlayToWindow`). Everything good follows from that placement rather
than from any code: our surface is a descendant of the task, so it moves with the window,
clips with it, stacks with it, and disappears behind whatever the window disappears behind —
at the compositor's expense, not ours. No lag, because nothing has to reposition it. No
occlusion logic, because nothing has to compute it.

The other two placements were both built and both measured worse:

| Host | Visible? | Cost |
| --- | --- | --- |
| App's own window | **No** | Below One UI's caption, which is a sibling of the app window in the task's decor container at z=30000. No z inside the app window beats it. |
| Display's a11y overlay | Yes | Above *every* app window, so it must be repositioned by hand (visible drag lag) and cropped by hand against the windows in front. A crop is a rectangle, so an occluder in the *middle* of a bar cannot be expressed — bars end up visibly cut. |
| **Platform caption window** | **Yes** | Only that the host is not always in the accessibility list. |

That last cost is the one that produced "the title bar sometimes doesn't appear", and it is
a retry problem, not a design problem. Measured on a two-window desktop: the caption window
is absent for a good fraction of passes, transiently, and returns on its own.

**Never fall back to the app window when it is missing.** That fallback was the entire bug.
It fails *silently* — the caption reports `visible reason= buffer=…` in SurfaceFlinger while
the user sees One UI's bar — and because the host window blinks in and out, it also rebuilt
the caption on every flip, ping-ponging between the two hosts forever. `pickHost` returns
caption-shaped windows only, and null means "not this pass": keep the bar the task already
has and try again. A task that has no bar yet holds the tick at 60 ms until it gets one;
tasks that already look right do not, or the fast tick would never end.

**The reconcile tick is adaptive** — 60 ms while caption geometry is changing or a task is
still waiting for a host, 400 ms when everything is settled.

**An AccessibilityService only ever sees the displays that existed when it connected.**
This is the single largest cause of "the title bar sometimes doesn't appear", and it is
invisible from the API: on a display created later, `getWindowsOnAllDisplays()` has no entry
at all, so the service enumerates nothing, attaches nothing, and reports no error. Every
scrcpy session *and every reconnect* mints a fresh display id, so a service left running
from the previous session is simply blind to the new desktop. The symptom in the breadcrumb
log is `no a11y windows for display N` while the daemon happily lists tasks on N.

The fix is to bounce the service whenever the display changes, not only on connect —
`Enforcer::restart_caption_service`, called from the same branch that already reacts to a
new display id. `adb_start_launcher` does it too, but a respawn never goes through there.

**The service must be restarted after the desktop display is created**, which
`enable_caption_service` does by clearing the setting and then writing it back. The two
writes have to be *separate adb invocations*: chained in one shell command they land faster
than AccessibilityManagerService tears the service down, so it never disconnects and no
restart happens. Confirm by looking for a fresh `onCreate` in the breadcrumb log — the
absence of one is the whole failure.

**The token depends on the target, and getting it wrong fails silently either way.** For a
window overlay — what we ship — the `InputTransferToken` must belong to a live window; a
fresh `Binder`, or a fresh `InputTransferToken()`, fails with the constructor and the attach
both succeeding and the surface never entering the layer tree. For a display overlay the
reverse holds: a **new** token is required and borrowing a live window's is actively wrong,
because it parents the surface under that window and the reparent onto the display's overlay
layer then leaves it with *no parent at all* — `SurfaceControlViewHost#N z=…` with no
`parentId` and no buffer row. Identical symptom, opposite cause. The two-argument
`attachAccessibilityOverlayToDisplay` returns nothing, so a rejected attach is
indistinguishable from a successful one; check for `parentId` in
`dumpsys SurfaceFlinger --list`.

### 0.2.1 How a title bar gets on screen

1. The daemon reserves the strip: `wct.setAppBounds(token, rect with top += captionPx)`
   applied through `IWindowOrganizerController#applyTransaction`. The task's own bounds are
   untouched; only the app's content area shrinks. **No `addInsetsSource(captionBar())`** —
   publishing one additionally wakes One UI's own caption into the same band.
2. `CaptionService` builds a `SurfaceControlViewHost` for the desktop display and hands its
   `SurfaceControl` to `attachAccessibilityOverlayToWindow(windowId, sc)`.
3. The framework forwards that surface to the **target app's own process**, where
   `AccessibilityInteractionController` runs
   `t.reparent(sc, mViewRootImpl.getSurfaceControl())` and `setTrustedOverlay(sc, true)`.

Step 3 is the whole point: the caption becomes a child layer of the app's window surface,
so it moves, raises, scales and animates with the window for free. The same structural
guarantee AOSP's own caption gets from `.setParent(mTaskSurface)` — and the reason
[§4.4](#44-the-honest-weakness-of-route-b)'s repair loop is not needed.

### 0.2.2 Five bugs that were each silent

Every one of these produced a *working-looking* API call and no visible caption. They are
recorded because each cost real time and none would be guessed from the docs.

| Symptom | Cause | Fix |
| --- | --- | --- |
| Service never starts | `getWindows()` returns the **default display only** | `getWindowsOnAllDisplays()` |
| Attach succeeds, no surface in the layer tree | `SurfaceControlViewHost` was given `new Binder()` as host token; it needs one belonging to a live window | the service adds its own 1×1 `TYPE_ACCESSIBILITY_OVERLAY` window and uses `getRootSurfaceControl().getInputTransferToken()` |
| Surface present, `invisible reason=hidden by parent or layer flag` | nothing ever **shows** it — the a11y attach only reparents and marks trusted; normally the host `SurfaceView` calls show | `Transaction#setVisibility(sc,true)` ourselves (`show()` is `@hide`) |
| Caption invisible on some windows | attached to the *platform's* caption window (same origin/width, strip-tall) whose parent is hidden | require the host window to be at least half the task's height, or prefer the decor window deliberately |
| Window title renders as `896` | `LIST` parser off by one — index 14 is `appBounds.bottom`, the package is 15 | fixed, with a unit test in `wm.rs` |

Two more that were visual rather than silent:

- **Everything ~2.8× too large.** Views were inflated with the *service's* Context, whose
  resources describe the phone panel (~450 dpi), then rendered on the 160 dpi desktop.
  Build them against `createDisplayContext(display)` and size in absolute px.
- **App content offset from its own frame.** The `setAppBounds` override does **not** follow
  a task that moves, and the "is it stripped?" test was `appTop > top`, which stays true
  when stale. Compare the full rect so a moved task re-strips on the next tick.

### 0.2.3 Operational facts that bite

- **Installing the launcher APK clears both `enabled_accessibility_services` and the
  `ACCESS_RESTRICTED_SETTINGS` appop.** `adb_start_launcher` reinstalls unconditionally, so
  every single `npm run tauri dev` used to silently disable the whole feature: the service
  simply never starts, and because nothing errors there is no log line anywhere pointing at
  it. This wasted more time than any actual bug. `adb::enable_caption_service` now re-grants
  both on every launch, *appending* to the services list rather than overwriting it (the
  user's own services — TalkBack, password managers — share that key), and warns if the
  value will not stick, which is the signature of a device-policy allowlist.
- **`am force-stop` clears `enabled_accessibility_services` too**, and the ordering matters:
  `adb_start_launcher` force-stops the launcher to reset its windowing mode, so granting
  before that wipes the grant microseconds later. The appop is *not* cleared by force-stop,
  which makes the wreckage confusing to read — permissions look granted, the service just
  isn't running. Grant **after** the `am start`, never before.

  ```
  settings put secure enabled_accessibility_services …/CaptionService
  settings get …   → com.ccrstech.openandroiddex.launcher/…CaptionService
  am force-stop com.ccrstech.openandroiddex.launcher
  settings get …   → null
  ```
- **The dev binary does not load `resources/bin`.** It loads the copy Tauri stages under
  `src-tauri/target/<profile>/resources/bin`, and `tauri-build` only refreshes that when its
  build script re-runs — which it does not for the APK and dex, because they are absent from
  the `rerun-if-changed` set it emits. Every `npm run tauri dev` therefore reinstalled a
  months-old APK over the freshly sideloaded one, so verified fixes appeared to regress on
  the next run. `build-launcher-apk.mjs` now refreshes any staged copy that exists and warns
  when it cannot (a running dev instance holds the files — the `os error 32` you get if you
  try to `cargo build` while the app is up). When a fix "does not take", compare hashes
  before debugging the code:

  ```powershell
  (Get-FileHash src-tauri\resources\bin\openandroiddex-launcher.apk -Algorithm MD5).Hash
  (Get-FileHash src-tauri\target\debug\resources\bin\openandroiddex-launcher.apk -Algorithm MD5).Hash
  adb shell "md5sum $(adb shell pm path com.ccrstech.openandroiddex.launcher)"
  ```
- **Never hard-code the display id.** It changes per scrcpy session and dead sessions leave
  virtual displays behind. The daemon answers `DESKTOP <pkg>`, preferring the display
  hosting the launcher and falling back to the one with the most visible tasks — the
  fallback matters because installing the APK force-stops the launcher, i.e. destroys the
  very signal used to find the desktop.
- **`setAppBounds` outlives its owner binder.** Clear it explicitly; a crashed host
  otherwise leaves every app short by the strip height.
- **Match the platform caption's height, and only ever grow to it.** One UI's is 40 px here.
  Measuring and assigning (`return h`) is not enough: our own bar is *itself* an
  accessibility window at the same origin and width, so a measurement can land on us and
  latch the height at whatever we last drew. That is how a 34 px bar survived under a 40 px
  platform caption, leaving the 6 px sliver visible in `coveredRegion`. `Math.max(h,
  CAPTION_PX)` makes the height monotone; being a few px too tall costs nothing.

### 0.2.4 Host selection must be stable, not merely correct

Three defects in one code path, each only visible in the compositor:

- **Duplicate bars, and a leak per rebuild.** Captions were keyed by accessibility window
  id, but a caption's identity is its *task*. When the host window id changed the old entry
  was orphaned under the old key, its surface stayed in the layer tree, and the task ended
  up wearing two identical bars with two live touch handlers. Keyed by task id now, with
  the window id stored inside the entry.
- **Nothing released captions for closed windows.** The reconcile loop only ever visits
  live tasks, so a closed task's `SurfaceControlViewHost` was held for the life of the
  service. There is now a sweep for task ids that are no longer live.
- **~30 rebuilds per second.** A task can offer several equally valid host windows, and the
  window list is not returned in a stable order, so "first match wins" flipped between
  passes — and a different host means a full teardown and rebuild. Fixed by keeping the
  window we are already attached to while it still qualifies, and breaking remaining ties
  on lowest window id instead of list order. This one is invisible from the UI: the caption
  looks completely normal while it is being destroyed and recreated 30×/second.

**The host must span the full task width.** Our surface is a child of the host window's
surface and is cropped to it. Hosting on a narrower window yields a bar clipped to that
window even though the buffer is task-wide — measured on two-pane Settings: a 705 px buffer
composited at `397..693`. So an app using activity embedding gets *no* caption rather than a
half-covered one, which is a real limitation, not a bug to fix from inside the service.

### 0.2.5 Known-incomplete

- One UI still draws its own caption; ours covers it. Suppressing it outright is the open
  question ([§6](#6-a-30-minute-experiment-that-could-delete-this-entire-project)).
  - **Launch flash mitigated (2026-08-06).** On app launch the platform caption is on
    screen for the few passes between the window appearing and our caption's host showing
    up in the a11y list — a visible flash of One UI's title bar before ours lands. Cause is
    structural to per-window hosting: the host cannot exist before the platform caption
    does. `CaptionService` now throws an opaque strip (`showCurtain`, a display-level
    `TYPE_ACCESSIBILITY_OVERLAY` the colour of our bar) over the caption area the instant an
    undressed freeform task is seen, and retires it in `ensureCaption` the moment the real
    caption attaches — so the swap is seamless and the platform bar never shows. Gives up
    after `CURTAIN_MAX_MS` (1500 ms) so an app that never yields a host falls back to the
    platform caption rather than a dead strip. This is the in-architecture analog of the
    commercial build, which sidesteps the flash entirely by putting each app on its own
    `--no-vd-system-decorations` display (no platform caption at all) and drawing chrome
    host-side. **Needs on-device verification** (`npm run tauri dev`): confirm no flash on
    launch, no lingering strip after close, and no dark bar on activity-embedding apps.
  - **Window controls: snap + maximise/restore (2026-08-06).** The caption bar gained
    Snap-Left (`◧`) and Snap-Right (`◨`) buttons — the same controls the commercial DeX
    exposes from its (host-side) title-bar dropdown, done as explicit buttons because a
    `PopupWindow` inside a `SurfaceControlViewHost` on a virtual display is unreliable. The
    maximise button (`▢`) is now a toggle: it remembers the window's rect on first
    maximise/snap and restores to exactly that. All three route through a new daemon verb
    **`RESIZE <d> <t> <l> <top> <r> <b> <px>`**, which sets the task bounds *and* re-insets
    the caption strip in a **single `startNewTransition`** — fixing the maximise/restore
    flicker, whose cause was a bare `BOUNDS` (unanimated `applyTransaction`) followed by the
    reconcile re-`STRIP`-ing a frame later (a second relayout). `applyTransaction` stays the
    per-frame drag mover; `RESIZE` is for one-shot user resizes. **Still host-side / TODO:**
    the app-launch fade-in (a PC-side Flutter opacity animation in the commercial; our
    single-streamed-display equivalent is either a platform launch transition via
    `ActivityOptions.makeCustomAnimation` at launch, or a fade-out veil overlay — both want
    on-device iteration).
- Apps using activity embedding (two-pane Settings) get a bar clipped to the pane that hosts
  it, because a surface parented under a window is cropped to that window. Inherent to
  per-window hosting; the display overlay avoided it but cost far more elsewhere.
- Killing the service (e.g. by reinstalling) can leave one orphaned caption surface
  composited over a task until that task's platform caption is recreated.
- Minimise is "send to back" — freeform exposes no real minimise;
  `DesktopTasksController#minimizeTask` lives inside SystemUI.
- Resize by dragging an edge is still One UI's, not ours.
- `wm.rs` compiles and is unit-tested but nothing calls it yet.

---

## 0.1 Second measurement pass — accessibility was blocked, then unblocked

Route A ([§3](#3-route-a-primary--the-caption-is-a-child-of-the-app-s-own-window-surface))
was **built and verified rendering** — a `SurfaceControlViewHost` reparented into Chrome's
own window surface, `visible reason= buffer=…`, 1056×34 at the task origin,
`TRUSTED_OVERLAY | NOT_FOCUSABLE`, live input region. Then it stopped being enableable, for
reasons that had nothing to do with the code.

**The phone was MDM-managed.** Microsoft Intune Company Portal was Profile Owner (user 10)
and `dumpsys device_policy` printed `accessibility services: empty` — an empty
permitted-services allowlist. Proven empirically rather than inferred:

| Write to `enabled_accessibility_services` | Result |
| --- | --- |
| `com.samsung.accessibility/.assistantmenu…` (system) | **sticks** |
| our `CaptionService` (third party) | reverts to `null` at t+0s |
| literal junk `x/y` | reverts to `null` at t+0s |

AccessibilityManagerService filters to permitted+installed and writes the list back, so no
third-party a11y service can ever run here. Not fixable from adb. `CaptionService.java`
stays in the tree as a documented dead end, unreferenced from the manifest.

Escape hatches also measured shut:

- `setprop persist.wm.debug.desktop_*` → **SELinux-denied** to shell.
- `killall com.android.systemui` → **Operation not permitted** (Knox-hardened), so a
  cached flag cannot be re-read without a reboot.
- `settings put global override_desktop_mode_features ALL_DISABLED` is *accepted* and
  persists, but `DesktopModeFlags` latches it at SystemUI start, and it may disable
  freeform along with desktop mode — i.e. the whole product. **Reverted; do not leave it
  set.** Worth one deliberate reboot test.

Two further measurements decide the replacement architecture:

- **`applyTransaction(reorder)` does not reorder.** `setBounds` moves the leash
  (tx 472→473) while SF z-order is unchanged at t+0 / 400 ms / 4 s. Only a transition
  actually restacks — and transitions queue behind WM's own 50 ms-delayed raise. So
  peer-task chrome would show a detached title bar on **every** click-to-focus.
- **`maxTaskLimit=5` is not a blocker** — 9 freeform root tasks drove fine, because this
  display has no "desk" and `DesktopRepository activeTasks` stays empty.

**PC→daemon round trip over `adb forward`: median 2.57 ms, p95 5.30 ms, max 10.5 ms**
(200 PINGs). With the 2.49 ms device-side move that is a ~5 ms command path — inside a
60 Hz frame, so host-driven dragging can be opaque rather than outline.

**That decision was reversed by removing the work profile.** The device was BYOD — Intune
was Profile Owner of user 10, with no Device Owner, so the personal profile was never
managed. Removing the work profile lifted the allowlist; the service enabled on the first
attempt and has stayed bound. Route A is what shipped ([§0.2](#02-what-shipped)).

Keep this section anyway. The finding generalises: **on any managed device, a
work-profile Profile Owner's `setPermittedAccessibilityServices` disables this entire
architecture**, because accessibility services are device-wide and the allowlist applies
across profiles. If Open Android DeX is ever run on a corporate device, per-window chrome
will simply not appear, and the fallback is host-side chrome — bars as Win32/Tauri pixels
over the scrcpy window, driven by the same daemon, with `setAppBounds` still reserving the
strip. That path costs the structural glue (it is a control loop again) but nothing else,
and the two constraints that dominate device-side designs vanish with it:
`InputDispatcher::windowOccludesTouchAt` never sees a frame that does not exist on the
device, and DWM composites above the video so there is no z-interleave problem.

The measured `applyTransaction(reorder)` result above is the reason a peer-task design is
*not* the fallback: it would show a detached title bar on every click-to-focus.

---

## 1. The premise that needs correcting

`custom-titlebar.md` §3.1 proves, correctly, that a `TYPE_APPLICATION_OVERLAY` window
cannot sit between two app windows: `getWindowLayerFromTypeLw` returns policy layer 11
unconditionally, `DisplayAreaPolicyBuilder` puts overlays in a sibling `DisplayArea` of
the one `TaskDisplayArea` that holds every freeform task, and peer subtrees cannot
interleave.

Every word of that is about **windows we add with `WindowManager.addView`**. It says
nothing about the container the tasks themselves live in. And inside that container:

```java
// services/core/java/com/android/server/wm/TaskDisplayArea.java  (assignRootTaskOrdering)
int layer = 0;
layer = adjustRootTaskLayer(t, mTmpHomeChildren,        layer);
layer = adjustRootTaskLayer(t, mTmpNormalChildren,      layer);
        adjustRootTaskLayer(t, mTmpAlwaysOnTopChildren, layer);

// adjustRootTaskLayer
for (child : bucket) child.assignLayer(t, startLayer++);
```

Three buckets — `alwaysOnTop`, `home`, `normal` — and **within a bucket, z is pure
ascending `mChildren` index**. No uid term. No package term. No theme, focusability or
`mBaseLayer` term; `mBaseLayer` is a `WindowState` field, assigned once in the
constructor and read only by `dump()`.

So the corrected statement of the constraint is:

> A **window** we add cannot interleave with app windows.
> A **task** we own interleaves with app tasks for free, because WindowManager sorts
> tasks by nothing except their order in a list we can edit.

Everything below follows from taking that seriously. Two routes do; they differ in
whether the chrome is a *sibling* of the app's surface or a *descendant* of it, and that
single distinction decides the entire UX outcome.

### 1.1 The second correction: `MANAGE_ACTIVITY_TASKS` is a much bigger key than it was used as

`wmd` used `IActivityTaskManager#resizeTask`. The same permission — already verified
granted to uid 2000 — also opens `IWindowOrganizerController`, and **without registering
a `TaskOrganizer`**:

```java
// services/core/java/com/android/server/wm/WindowOrganizerController.java
@Override public void applyTransaction(WindowContainerTransaction t) {
    enforceTaskPermission("applyTransaction()");   // MANAGE_ACTIVITY_TASKS. That is all.
    ...
}
```

Nothing on that path consults `task.mTaskOrganizer`, `isOrganized()`, or caller identity.
The only ownership checks in the file are `enforceTaskFragmentOrganizerPermission` (reached
only for TaskFragment ops) and `mCreatedByOrganizer` gates on ops we do not need
(`removeRootTask`, `setAdjacentRoots`, `setLaunchAdjacentFlagRoot`,
`setReparentLeafTaskIfRelaunch`).

`WindowContainerToken` is a public field on `android.app.TaskInfo`, populated for every
root task by `Task#fillTaskInfo` (`info.token = mRemoteToken.toWindowContainerToken()`),
and `getAllRootTaskInfosOnDisplay(170)` hands them over with a bottom-to-top `position`.

This unlocks, for a non-organizer:

| Op | Effect |
| --- | --- |
| `setBounds(token, rect)` | move/resize any task |
| `reorder(token, onTop)` | z-order any task |
| `setFocusable(token, false)` | make a task never take focus or the resumed slot |
| `setAlwaysOnTop(token, true)` | promote a task to the top bucket |
| `addInsetsSource(token, owner, idx, captionBar(), rect, …)` | **reserve a caption strip inside a task** |
| `setAppBounds(token, rect)` | shrink the app's content area to match |
| `setDragResizing(token, true)` | tell the client to reuse one oversized buffer |

The last three are how AOSP's own desktop caption bar reserves its strip
(`WindowDecoration.WindowDecorationInsets#update`). We can call them today.

---

## 2. The kill shot to design around first

My first formulation of this had the chrome task **above** the app, covering the app's
whole rect, transparent in the middle, with
`InternalInsetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)` so touches in the
interior fell through. **That does not work, and it fails silently in the worst possible
way: the app underneath goes completely dead to touch.**

```cpp
// frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp  (~611)
bool windowOccludesTouchAt(const WindowInfo& windowInfo, ui::LogicalDisplayId displayId,
                           float x, float y, const ui::Transform& displayTransform) {
    if (windowInfo.displayId != displayId) return false;
    const auto frame = displayTransform.transform(windowInfo.frame);
    const auto p = floor(displayTransform.transform(x, y));
    return p.x >= frame.left && p.x < frame.right && p.y >= frame.top && p.y < frame.bottom;
}
```

It tests **`frame`**. It never reads `touchableRegion`. The targeting helper 25 lines
above *does* (`if (!touchableRegion.contains(...)) return false;`). The asymmetry is
deliberate: shrinking `touchableRegion` removes you as a touch **target** and leaves you
as a touch **occluder**. `TOUCHABLE_INSETS_REGION` is precisely the wrong lever.

The rest of the chain, all `android16-release`:

1. `WindowState#getTouchOcclusionMode()` returns `TouchOcclusionMode.BLOCK_UNTRUSTED`
   for an ordinary application window.
2. `InputMonitor#populateInputWindowHandle` copies it verbatim.
3. `canBeObscuredBy()` has five escapes — same token, `NOT_VISIBLE`,
   (`alpha == 0` **and** `NOT_TOUCHABLE`), **same ownerUid**, `TRUSTED_OVERLAY`. Our
   chrome over a third-party app fails all five. (The alpha escape needs the window to be
   untouchable, which a titlebar cannot be. And `alpha` there is `SurfaceControl` alpha —
   a `windowIsTranslucent` theme still reports 1.0.)
4. `computeTouchOcclusionInfo()` sets `hasBlockingOcclusion`, `isTouchTrusted()` returns
   false, and `findTouchedWindowTargetsLocked` simply `continue`s past the app.

The `ACTION_DOWN` is **discarded**. No crash, no toast — logcat prints
`Dropping untrusted touch event due to <our package>/<uid>` and the window reads as
frozen. This is the same mechanism that made screen-dimmer apps break touch on Android
12+, so it is a well-trodden real symptom, not a theoretical one. There is no tunable:
the Android 12-era `block_untrusted_touches` global and `BlockUntrustedTouchesMode` enum
are **gone** in 16; the path is unconditional.

Two consequences that constrain every design below:

- **Chrome must never have a frame that covers its own app's content.** Either put the
  chrome task *below* its app (`computeTouchOcclusionInfo` walks top-first and
  `break`s at the touched window — windows below are never occluders), or keep the
  chrome's frame confined to a strip the app has been inset out of.
- **The chrome window's frame must equal the pixels it draws.** A transparent shadow
  margin overhanging a *lower* window creates a band where that window's taps vanish.
  No decorative slop.

And the "expand the chrome task to the full display during a drag" idea from my first
sketch is a **total input blackout for the whole desktop** for the duration of the drag.
It is deleted.

> There is one escape hatch, kept for later: `layer_state_t::sanitize()` gates
> `eTrustedOverlayChanged` on `ACCESS_SURFACE_FLINGER` only — which uid 2000 holds. The
> daemon *can* mark a handed-over `SurfaceControl` as a trusted overlay, which
> short-circuits `canBeObscuredBy`. It requires passing the `SurfaceControl` over
> **binder** (not the loopback socket), and surface recreation silently drops the flag.
> Last resort, not baseline. Route A below gets the trusted bit for free.

---

## 3. Route A (primary) — the caption is a **child of the app's own window surface**

This is the one to build. It is the only surveyed design that reproduces what AOSP itself
does, and it makes the project's non-negotiable — *bar and window must never visibly
separate* — a property of the scene graph rather than a control loop.

AOSP's desktop caption is not a peer of the window. It is a child of it:

```java
// libs/WindowManager/Shell/.../windowdecor/WindowDecoration.java (updateDecorationContainerSurface)
mDecorationContainerSurface = builder
        .setName("Decor container of Task=" + mTaskInfo.taskId)
        .setContainerLayer()
        .setParent(mTaskSurface)        // ← the entire trick
        .build();
```

and its drag is a raw `SurfaceControl.Transaction` on that same task surface, per frame,
never touching `system_server`:

```java
// DragPositioningCallbackUtility#setPositionOnDrag / VeiledResizeTaskPositioner
t.setPosition(decoration.mTaskSurface, x, y);
t.setFrameTimeline(Choreographer.getInstance().getVsyncId());
t.apply();
// real bounds committed ONCE, on release, via WCT#setBounds in a TRANSIT_CHANGE
```

We cannot get the task leash without registering a global `TaskOrganizer`. But there is a
second way into the app's surface hierarchy, and it runs **inside the target app's own
process**:

```java
// AccessibilityService (API 34+)
public void attachAccessibilityOverlayToWindow(int accessibilityWindowId, SurfaceControl sc)
```

The server does not put this on a display overlay layer. It forwards the `SurfaceControl`
over binder to the target window's `IAccessibilityInteractionConnection`, and
`AccessibilityInteractionController#attachAccessibilityOverlayToWindowUiThread` executes,
in the third-party app's process:

```java
t.reparent(sc, mViewRootImpl.getSurfaceControl()).apply();
```

Our chrome becomes a **child layer of the app's own window surface**. Therefore:

| Property | How it is obtained |
| --- | --- |
| Correct z among all freeform windows | Structural. It is inside the app's window, inside its Task, inside the TaskDisplayArea at policy layer 2. Nothing to maintain. |
| Moves, scales, raises, lowers, animates with the window | Free. It *is* the window. Zero-frame separation by construction; no echo-slaving, no lead compensation, no §6 control loop. |
| Immune to `setHideOverlayWindows` | It is not an overlay window. §3.2 of the old doc stops applying. |
| Immune to untrusted-touch occlusion (§2) | The system applies `t.setTrustedOverlay(sc, true)` before handing the surface off. |
| Real input | `SurfaceControlViewHost` → `WindowlessWindowManager#addToDisplay` → `IWindowSession#grantInputChannel` registers a genuine `InputWindowHandle`, hit-tested by SurfaceFlinger at the layer's actual screen transform. `FLAG_NOT_FOCUSABLE` keeps keyboard focus with the app while touch still lands on the strip. |

### 3.1 The one geometric constraint, and its AOSP-sanctioned answer

`Task#updateSurfaceSize` sets `transaction.setWindowCrop(mSurfaceControl, taskW, taskH)`
on the root task. Our chrome is therefore **clipped to the task bounds** — it cannot
float above the task's top edge the way an overlay bar could.

So do what AOSP does: reserve the strip *inside* the task. From the daemon, in one
transaction:

```java
WindowContainerTransaction wct = new WindowContainerTransaction();
wct.setBounds(taskToken, taskRect);                                  // unchanged, full size
wct.setAppBounds(taskToken, insetRect);                              // top += captionPx
wct.addInsetsSource(taskToken, ownerBinder, 0,
        WindowInsets.Type.captionBar(),          captionRect, null, 0);
wct.addInsetsSource(taskToken, ownerBinder, 0,
        WindowInsets.Type.mandatorySystemGestures(), captionRect, null, 0);
// apply — see §3.2
```

Then park the caption `SurfaceControlViewHost` at `setPosition(sc, 0, 0)` in the reserved
strip. `addLinkedInsetsFrameProvider` `linkToDeath`s the owner binder, so pass one held by
the long-lived daemon, and remove the source when the task closes.

Note the caption strip must be **opaque**: `addInsetsSource(captionBar())` makes
well-behaved apps inset their content, but edge-to-edge apps, games and legacy apps will
still paint under it. Their pixels are simply covered — exactly as in shipping Android
desktop mode.

### 3.2 Which mover to use — *superseded by [§0](#0-measured-on-device--2026-07-31), kept for the reasoning*

> **Measured correction.** The conclusion below — "use `startNewTransition`, because
> `applyTransaction` cannot move an organized surface" — is **wrong on this device**.
> Both movers work: `applyTransaction` 2.49 ms, `startNewTransition` 3.23 ms, both
> confirmed against the SurfaceFlinger leash transform. The `isOrganized()` early-returns
> below are real AOSP code, but One UI's `FreeformTaskListener` repositions the task
> surface itself in response to `onTaskInfoChanged` — the alternative the divergence
> review flagged and the source review discounted.
>
> **Use `applyTransaction` as the mover.** It is cheaper and does not enter the
> transition queue, which is what makes per-frame dragging possible at all. Keep
> `startNewTransition` for changes that should animate (open, close, snap).
>
> The reasoning is retained because the `isOrganized()` wall is one OEM behaviour change
> away from being real again, and because it explains the fallback.

`TaskOrganizerController#getTaskOrganizer()` returns the globally registered organizer
(One UI SystemUI's `ShellTaskOrganizer`), and `Task#canBeOrganized()` is:

```java
if (isRootTask() || mCreatedByOrganizer) return true;   // "All root tasks can be organized"
```

so `Task#updateTaskOrganizerState` assigns that organizer to **every root task on display
170**. Consequently:

```java
// WindowContainer#updateSurfacePositionNonOrganized
// "Avoid fighting with the organizer over Surface position."
if (isOrganized()) return;

// Task#updateSurfaceSize
if (mSurfaceControl == null || isOrganized()) return;
```

A plain `applyTransaction(wct)` carrying `setBounds` therefore updates the app's
`Configuration` and its reported bounds — the app relayouts — **while the leash does not
move and the crop does not change.** The window resizes in place and clips.

The fix, same permission, still no organizer:

```java
IActivityTaskManager atm = IActivityTaskManager.Stub.asInterface(
        ServiceManager.getService("activity_task"));
IWindowOrganizerController woc = atm.getWindowOrganizerController();
woc.startNewTransition(TRANSIT_CHANGE /* 6 */, wct);
```

`Transition#buildFinishTransaction` calls `resetSurfaceTransform` **unconditionally** for
every target —

```java
target.getRelativePosition(tmpPos);
t.setPosition(targetLeash, tmpPos.x, tmpPos.y);
final Rect clipRect = target.getResolvedOverrideBounds();
t.setWindowCrop(targetLeash, clipRect.width(), clipRect.height());
t.setMatrix(targetLeash, 1, 0, 0, 1);
```

— with no `isOrganized()` early return (the `isOrganized()` test nearby is an *additional*
inherit-from-parent restoration, not a skip), and then re-runs
`assignLayers(participantDisplays[i], t)`. So core repositions and re-crops organized
leashes inside the finish transaction. This also explains, retroactively, why the old
`resizeTask` path worked at all: `ATMS#resizeTask` explicitly wraps `task.resize()` in
`new Transition(TRANSIT_CHANGE, …)` + `startCollectOrQueue`.

**Rule for the rebuilt daemon:** `startNewTransition` for anything with a surface
consequence; plain `applyTransaction` only for state that has none (`setFocusable`,
`setAlwaysOnTop`, `setDragResizing`, insets bookkeeping).

**Corollary:** transitions serialize (`startCollectOrQueue` queues behind
`mSyncEngine.hasActiveSync()`), so **you cannot issue one transition per drag frame.**
Route A does not need to — the caption rides the app's surface, so drag is
"veil-or-outline, then one committing transition on release", which is precisely what
`VeiledResizeTaskPositioner` does in shipping Android. Copying the reference
implementation's answer here is not a compromise.

### 3.3 Getting the a11y service enabled

The refutation pass rated this the biggest blocker on Play-policy and user-trust grounds.
**For this project those objections mostly evaporate**: Open Android DeX is not a Play
app. It is a tethered desktop that already installs an APK over adb, already runs a
shell-uid daemon, and already requires a USB connection to do anything at all. Adding to
the boot flow:

```bash
adb shell settings put secure enabled_accessibility_services \
    com.ccrstech.openandroiddex.launcher/.ChromeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

`WRITE_SECURE_SETTINGS` is held by shell, and setting it this way bypasses the Android
13+ restricted-settings dialog that blocks *manual* enablement of sideloaded services.

What genuinely must be tested (§8): whether One UI's accessibility watchdog reverts it,
whether the a11y service can see display 170 at all (scrcpy creates it
`PUBLIC | TRUSTED | OWN_FOCUS | OWN_DISPLAY_GROUP`, and `OWN_FOCUS` interacts with
`AccessibilityWindowManager`'s `topFocusedDisplayId` logic), and — the cheap fatal —
whether a trusted overlay appears in scrcpy's capture or is filtered out. If the chrome
is visible on-device but invisible on the PC, Route A is over; find that out on day one.

### 3.4 Known sharp edges

- **Surface recreation orphans the overlay.** The reparent targets
  `ViewRootImpl.mSurfaceControl` *as of attach time*. WM destroys and recreates the client
  surface on window recreation, activity relaunch and some config changes; the overlay is
  not re-parented automatically. Re-attach idempotently on every
  `AccessibilityEvent.TYPE_WINDOWS_CHANGED` and on every bounds change we author
  (re-attaching to the same window is documented as a no-op; to a different window it
  transfers). Keep `Map<a11yWindowId, SurfaceControlViewHost>`.
- **All chrome lives strictly inside the task rect.** No drop shadows outside it, no
  resize handles beyond it, no drag preview overhanging it. The task crop is hard.
- **Per-window cost**: one `SurfaceControlViewHost` + one `ViewRootImpl` + one input
  channel per managed window. Fine at ~8 windows.
- **If `Task#isOrganized()` is true and One UI's shell also decorates these tasks**, our
  WCT insets ops may be overwritten on the next transition. §8 Block 1 settles whether
  Samsung's desktop shell is live on display 170 at all.

---

## 4. Route B (fallback) — chrome as a peer task, corrected geometry

If Route A dies on a11y enablement or capture filtering, this is the fallback. It is the
design I originally proposed, minus the two independently fatal decisions in §2.

**Shape.** One extra freeform Activity from our APK per managed window — a *chrome task* —
placed **immediately below its app task**, with a frame equal to the title strip and a few
dp of border, and nothing else.

```
z, top → bottom:     app_A          ← user's window
                     chrome_A       ← A's titlebar strip, sits above A's top edge
                     app_B
                     chrome_B
                     launcher (home bucket, always bottom)
```

Why below, not above — three independent reasons that all point the same way:

1. **Touch.** `computeTouchOcclusionInfo` iterates windows top-first and
   `if (windowHandle == otherHandle) break; // All future windows are below us`. A chrome
   task below its app can never occlude it. §2 stops applying to the app it decorates.
2. **Top-resumed.** `ActivityTaskSupervisor#mTopResumedActivity` is system-wide and taken
   from `getTopDisplayFocusedRootTask()`. Chrome above its app permanently steals that
   slot and fires `Activity#onTopResumedActivityChanged(false)` into every managed app —
   the documented CameraX camera-release hook. Apps stay RESUMED (video and audio are
   fine), but **camera apps will drop their preview**, and a YouTube smoke test will not
   catch it. Chrome below dodges it entirely.
3. **No pass-through needed.** The strip sits outside the app's rect, so the app's window
   is not there to intercept it. `TOUCHABLE_INSETS_REGION` — which does not work anyway —
   is not needed. A whole class of hit-testing bugs disappears.

Occlusion is still correct with chrome below: `chrome_A` is below `app_A` (they do not
overlap, so it is invisible), and above `app_B` — so where A's titlebar overlaps B, the
titlebar wins, which is right, because A is above B. Where `app_A` overlaps `chrome_B`,
A covers B's titlebar, which is also right.

### 4.1 The mandatory lines

```java
// on every chrome task creation, in the same WCT that first sets its bounds
wct.setFocusable(chromeToken, false);
```

Without it, if `mFocusedApp` ever becomes the chrome `ActivityRecord`,
`DisplayContent#mFindFocusedWindow` aborts with `mTmpWindow == null` and **display 170
loses keyboard focus entirely**, silently, with no crash and no obvious log.
`FLAG_NOT_FOCUSABLE` on the window does *not* prevent this — `ActivityRecord#isFocusable`
is windowing-mode based (`canReceiveKeys == mWindowingMode != WINDOWING_MODE_PINNED`).
A non-focusable task is also skipped by `resumeFocusedTasksTopActivities`, which is what
keeps the app below RESUMED and in the top-resumed slot. This must be re-applied after
every chrome task recreation and process death; missing one re-application reintroduces
focus death intermittently, which is the worst debugging experience available.

**Never** `setBounds(chromeToken, null)`, never an empty `Rect`, never
`setWindowingMode(..., WINDOWING_MODE_FULLSCREEN)`. `matchParentBounds()` is literally
`getResolvedOverrideBounds().isEmpty()`; flipping it true drives every app below to
`VISIBLE_BEHIND_TRANSLUCENT` (→ all PAUSED) or `INVISIBLE` (→ all STOPPED). Put an
assertion in the daemon rejecting any chrome transaction whose `Rect` `isEmpty()`.

### 4.2 Manifest and launch

```xml
<activity android:name=".ChromeActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:autoRemoveFromRecents="true"
    android:launchMode="standard"
    android:taskAffinity=""
    android:allowTaskReparenting="false"
    android:resizeableActivity="true"
    android:theme="@style/Theme.Chrome"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density|keyboard|keyboardHidden|navigation|uiMode">
    <layout android:minWidth="1dp" android:minHeight="1dp" />
    <!-- no intent-filter; never CATEGORY_HOME / SECONDARY_HOME -->
</activity>
```

- **`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK` on every launch.** Without
  `MULTIPLE_TASK`, every chrome activity sharing an affinity collapses into one task and
  it presents as "only the last window gets chrome".
- **Never `ACTION_MAIN` + exactly one HOME/SECONDARY_HOME category.** `setActivityType`
  would then have to be checked, and if the chrome ever lands in the launcher's home task
  it inherits `ACTIVITY_TYPE_HOME` permanently (`ConfigurationContainer#setActivityType`
  throws `IllegalStateException` on any later change) and is pinned to the bottom bucket
  forever.
- **Launch from the daemon, not the launcher.** `com.android.shell` declares
  `START_ACTIVITIES_FROM_BACKGROUND` (unconditional BAL) and `INTERNAL_SYSTEM_WINDOW`
  (short-circuits `isCallerAllowedToLaunchOnDisplay`). This removes every
  background-activity-launch worry about a paused launcher in one move.
- **`avoidMoveToFront`**: `bundle.putBoolean("android.activity.avoidMoveToFront", true)`
  so the launch itself does not cause an uncontrolled z-jump; do the raise deliberately.
- **Never `ActivityOptions#setTaskAlwaysOnTop`** for chrome. It pins it above *all* app
  tasks and recreates the overlay-layering problem the old doc already killed.

### 4.3 Pairing and reaping

- **Discriminator: launch cookies, not Intent extras.** `Task#fillTaskInfo` defaults to
  `stripExtras=true` and `Intent.cloneFilter()` drops extras, so a per-task extra is
  invisible. `TaskInfo.launchCookies` is always populated and needs no hidden API on the
  launcher side: `Bundle b = opts.toBundle(); b.putBinder("android.activity.launchCookie", cookie);`.
  One field gives both "is this a chrome task" and "which app does it belong to".
- **Reaping.** `ActivityRecord#handleAppDied` can choose `remove = false` ("the activity
  lives on"), so chrome tasks outlive launcher death as restartable ghosts floating over
  live apps. Three defences: `onTaskRemoved(appTaskId)` → `removeTask(pairedChromeTaskId)`;
  a `DeathRecipient` on a launcher-supplied binder that sweeps all known chrome tasks;
  and an idempotent startup reconcile that enumerates
  `getAllRootTaskInfosOnDisplay(170)` and removes any chrome-cookied task with no live
  pairing. Note `ATMS#removeTask` enforces `REMOVE_TASKS`, a *different* permission —
  AOSP's shell manifest declares it, but verify on device (§8 Block 4).
- **Ship a kill switch** that finishes every chrome task and restores plain freeform
  windows. The failure modes here are display-wide; make them recoverable.

### 4.4 The honest weakness of Route B

Route B has one structural defect that Route A does not, and it must not be papered over.

**We do not author the raise.** `WindowManagerService#onPointerDownOutsideFocusLocked`
sets `shouldDelayTouchForFreeform = task.getWindowingMode() == WINDOWING_MODE_FREEFORM`
and posts the raise with `POINTER_DOWN_OUTSIDE_FOCUS_TIMEOUT_MS = 50`, then executes
`handleTapOutsideFocusInsideSelf()`. WM raises the **app task alone**, on its own timer,
and the transition that opens then blocks our repair:

```java
// TransitionController#canAssignLayers — device-global, not per-display
if (wc.asTask() != null && (isPlaying() || isCollecting())) return false;
```

`mCollectingTransition` is a single global field. So a `reorder` issued while *any*
transition is in flight *anywhere on the device* does not take effect until
`buildFinishTransaction` re-runs `assignLayers`. There is also no insert-at-index —
`WindowContainerTransaction#reorder` is top/bottom only — so restoring the invariant means
re-emitting an ordered `reorder(x, toTop=true)` run over the whole stack, O(2N) ops.

Worst case, tapping a partially covered background window: WM raises `app_B` alone at
+50 ms; the newly raised window is on top while its own titlebar is momentarily buried;
our repair is silently deferred for the transition duration; then it snaps. Note it is
**one bar** — `chrome_A` stays immediately below `app_A` throughout — but a snap draws the
eye more than a drift does.

Whether this is a blemish or a dealbreaker is **one measurement**: the freeform
to-front transition duration on display 170. If Samsung's desktop shell is not live there
(§8 Block 1 says it probably is not — see §6) the transition may be near-instant and this
mostly disappears. If it is 250–300 ms, that is 15–18 frames of a detached bar, and
Route B should not ship as per-window chrome. **Measure before building.** This number is
the highest-value unknown in the entire investigation.

Route A has no equivalent exposure, because there is no invariant to repair.

---

## 5. What is now definitively closed

| Approach | Status | Reason (new evidence) |
| --- | --- | --- |
| Nesting the app's task under ours (`WCT#reparent`) | **Do not even prototype** | The permission genuinely allows it — `sanitizeAndApplyHierarchyOpForTask` gates REPARENT only on `task.isRootTask() \|\| parent.mCreatedByOrganizer`. But a Task holding both an `ActivityRecord` child and a `Task` child makes `Task#isLeafTask()` false, and `resumeTopActivityUncheckedLocked` then does an unchecked `(Task) getChildAt(idx--)` over **all** children → `ClassCastException` on the core resume path under the WM global lock → **system_server death, soft reboot**. `Task#addChild` accepts the mix silently. |
| Container root task via `createRootTask` | Closed | Needs no registration, but the created task is `mCreatedByOrganizer=true` and is auto-assigned to the *current* global organizer — Samsung SystemUI. Its lifetime becomes hostage to SystemUI's registration; `setTaskOrganizer(null)` → `removeImmediately()` recurses into every child. A SystemUI restart deletes the user's apps. |
| Cross-app Activity Embedding (`TaskFragmentOrganizer`) | Closed | `createTaskFragment` requires `ownerTask.effectiveUid == caller.mUid`. A TaskFragment can only be created inside a task of your own uid, and the TF organizer's uid is hardcoded to the owner activity's. The `MANAGE_ACTIVITY_TASKS` trust bypass in `isFullyTrustedEmbedding` is unreachable from an app-uid process. |
| `SurfaceControlViewHost` reparented under the TaskDisplayArea | Closed **for interleaving**, useful otherwise | Confirmed: SF derives `WindowInfo::frame`/`transform`/`displayId` from `geomLayerTransform`, so a foreign reparent *is* input-correct — this overturns the old §13.1 rejection. But `adjustRootTaskLayer` assigns tasks contiguous integers, so a foreign sibling of the TDA can only be strictly above or strictly below **all** tasks. Interleaving needs `setRelativeLayer` against a Task leash, and no route hands uid 2000 that leash without a global organizer. |
| `attachAccessibilityOverlayToDisplay` | Closed | Display-level layer `Integer.MAX_VALUE - 2` — topologically identical to `TYPE_APPLICATION_OVERLAY`. Only the **ToWindow** variant is interesting. |
| Device-side compositor (N per-app VirtualDisplays) | Deferred, not closed | Every permission gate clears via the daemon (`com.android.shell` declares `ADD_TRUSTED_DISPLAY`, `INTERNAL_SYSTEM_WINDOW`, `INJECT_EVENTS`), and scrcpy already proves one trusted VD works. But it is a display server, ~4–6 months, and it kills the soft IME (per-VD `LOCAL` renders the keyboard *inside* each window; `FALLBACK` is hardcoded to `DEFAULT_DISPLAY`), cross-window drag & drop, and accessibility. Wrong shape for this problem. |
| Per-app displays composited host-side (commercial DeX topology) | Unchanged — out of scope | §1 of the old doc. |

---

## 6. A 30-minute experiment that could delete this entire project

One UI 8 **removed classic DeX and rebuilt it on AOSP Android 16 Desktop Windowing**.
That reframes the old §3 rejection of native captions.

```java
// libs/WindowManager/Shell/shared/.../DesktopModeStatus.java  isDesktopModeSupportedOnDisplay
if (!canEnterDesktopMode(context)) return false;
if (!enforceDeviceRestrictions()) return true;
if (display.getType() == Display.TYPE_INTERNAL) return canInternalDisplayHostDesktops(context);
if ((display.getType() == Display.TYPE_EXTERNAL || display.getType() == Display.TYPE_OVERLAY)
        && enableDisplayContentModeManagement()) {
    return wm != null && wm.shouldShowSystemDecors(display.getDisplayId());
}
return false;
```

scrcpy's `--new-display` display is `Display.TYPE_VIRTUAL` (5) — neither `INTERNAL` nor
`EXTERNAL` nor `OVERLAY` — so on stock AOSP this falls through to `return false`.

> **Measured correction ([§0](#0-measured-on-device--2026-07-31)).** Samsung *has* patched
> this. On the live device `Display#7 isDesktopFirst=true`, `DesktopModeStatus=true`, and
> `FreeformTaskListener` owns every freeform task on the scrcpy display — **with
> `--no-vd-system-decorations` still set**. The desktop shell is not dormant; it is
> already running, drawing a per-task `Decor container` (`z=30000`, `TRUSTED_OVERLAY`) and
> a `Freeform Outline`, and providing the drag-resize border. It just draws no caption and
> reserves no strip.

**First, a warning — now sharper, not weaker.** Samsung's shell is *already* a co-tenant
on this display. It repositions task surfaces from `onTaskInfoChanged` (which is what
makes the cheap mover work), it enforces `maxTaskLimit=5`, and it owns the resize border.
Any chrome design is cooperating with it whether or not it intends to. Dropping
`--no-vd-system-decorations` would additionally wake the caption and the secondary
home/nav bar; `persist.wm.debug.desktop_veiled_resizing` (default true) would veil every
bounds change. **Treat that flag as load-bearing and document it.**

**Second, the lottery ticket.** The old doc rejected system decorations because
`DisplayContent#isHomeSupported()` ORs the decorations predicate, so decorations
unconditionally imply a secondary home — and the observed result was Samsung's secondary
launcher plus a nav bar. But *which* app becomes the secondary home is a resolver
question, and **our launcher already wins that resolution today on the no-decorations
path** (it declares `CATEGORY_SECONDARY_HOME`). So the real question was never "can we
have captions without a home", it was "can our launcher be the home while decorations are
on, and can the nav bar be suppressed per-display".

If yes, the payoff is native, correct, zero-maintenance per-window captions and the entire
chrome project evaporates. Worth 30 minutes before anything in §8:

```bash
# start scrcpy WITHOUT --no-vd-system-decorations, then:
adb shell dumpsys activity activities | grep -iE "secondary|home|displayId=170"
adb shell cmd package resolve-activity -c android.intent.category.SECONDARY_HOME \
    -a android.intent.action.MAIN
# if Samsung's secondary home wins, try making it lose:
adb shell pm disable-user --user 0 <samsung secondary home component>
# and hunt for per-display nav bar suppression on 170
adb shell dumpsys window displays | grep -A30 "Display: mDisplayId=170"
```

Failure mode is loud and instantly reversible (kill scrcpy). Run it first.

---

## 7. Two fixes worth taking regardless of which route wins

**7.1 The taskbar may be structurally unable to draw above app windows.**
`assignRootTaskOrdering` layers `mTmpHomeChildren` **first — i.e. lowest — unconditionally**.
If `LauncherActivity`'s task on display 170 is `ACTIVITY_TYPE_HOME` (it declares
`CATEGORY_HOME`/`CATEGORY_SECONDARY_HOME`), then any window it owns as part of that task
sits below every app task. The current code sidesteps this by putting the taskbar in a
`TYPE_APPLICATION_OVERLAY` (policy layer 11) — which works, and is exactly what
`Window#setHideOverlayWindows(true)` kills, taking the taskbar down with it
(old doc §3.2, observed with Samsung Settings).

The structural fix is now available: give the taskbar **its own STANDARD task** and
`wct.setAlwaysOnTop(taskbarToken, true)` from the daemon. The `alwaysOnTop` bucket is
layered last (highest), it is an app window rather than an overlay, and
`setHideOverlayWindows` cannot touch it. This is a real, independent improvement to a
current known bug. One `dumpsys activity containers` confirms whether the launcher is in
the home bucket today.

**7.2 Rebuild `wmd` around transitions from day one.** The old doc's §10 conclusion
(990 ms `content query` → 0.35 ms loopback TCP) stands and is still the largest single
latency win available. Build it, but build the mover as
`startNewTransition(TRANSIT_CHANGE, wct)` rather than `resizeTask` — one transaction can
carry N containers, and it skips `resizeTask`'s `canResizeTask()` / `canResizeToBounds()`
rejections. Keep `applyTransaction` for `setFocusable` / `setAlwaysOnTop` /
`setDragResizing` / insets.

Two side effects to guard:

- `reorder` on a root task runs `Task#findEnterPipOnTaskSwitchCandidate` and can **arm
  auto-PiP** on whatever PiP-capable activity is currently top. Raising chrome while
  YouTube is top can pop it into picture-in-picture when it next pauses.
- `reorder` sets `TRANSACT_EFFECTS_LIFECYCLE` → `ensureActivitiesVisible()` +
  `resumeFocusedTasksTopActivities()`. Without `setFocusable(chromeToken, false)` the
  chrome activity gets resumed and the app below paused on every raise.
- Do **not** use `setHidden` for chrome visibility — it uses `FLAG_FORCE_HIDDEN_FOR_TASK_ORG`,
  the same flag Samsung's organizer uses, and `Task#setTaskOrganizer(null)` clears it.
  Use alpha or bounds.
- `WCT#setRelativeBounds` is documented as TaskFragment-only and silently does nothing
  useful on a Task. Do not use it.

---

## 8. The gate

Nothing above should be built before these run. Read-only unless noted, ~25 minutes total,
and any one of them can save six weeks. Run §6 first.

### Block 1 — is One UI's desktop shell live on display 170?

```bash
adb shell dumpsys activity service SystemUIService WMShell
adb shell dumpsys display | grep -B4 -A12 "mDisplayId=170"      # expect: type VIRTUAL
adb shell getprop persist.wm.debug.desktop_mode_enforce_device_restrictions
adb shell getprop persist.wm.debug.desktop_max_task_limit
adb shell getprop persist.wm.debug.desktop_veiled_resizing
adb shell settings get global override_desktop_mode_features
adb shell settings get global force_desktop_mode_on_external_displays
adb shell settings get global enable_freeform_support
adb shell cmd overlay lookup android android:integer/config_maxDesktopWindowingActiveTasks
adb shell cmd overlay lookup android android:bool/config_perDisplayFocusEnabled
```

Look for: any display-170 task in `ShellTaskOrganizer`'s list with a Freeform/Desktop
listener attached (→ Samsung's shell is managing your display and will fight the daemon);
`maxTaskLimit` (halved by Route B's chrome tasks); and
`enforce_device_restrictions=false`, which is the alarm — it makes
`isDesktopModeSupportedOnDisplay` return true early for **every** display including
virtual. `config_perDisplayFocusEnabled` decides whether the top-resumed/camera symptom
in §4 is per-display (benign) or global (real).

### Block 2 — is the launcher stuck in the home bucket? (§7.1)

```bash
adb shell dumpsys activity containers > containers.txt
```

Find the display-170 subtree; read each root task's `activityType`, its child ordering,
and whether any prints `mCreatedByOrganizer=true`.

### Block 3 — does a transition move an organized leash on this device? (§3.2)

Zero code required, because `am task resize` routes through `ATMS#resizeTask`, which wraps
in a `Transition` — making it an exact proxy for `startNewTransition`:

```bash
adb shell dumpsys activity activities | grep -E "taskId=[0-9]+|displayId"
adb shell am task resize <TASK_ID_ON_170> 100 100 1000 700
```

Moves **and** crops correctly → `buildFinishTransaction` → `resetSurfaceTransform` is
repositioning organized leashes as in AOSP, and the daemon's mover is live. Resizes but
does not move, or keeps the old crop → Samsung diverges and both routes need a new
bounds path before any code is written. Time it, and watch for a resize veil — that is
what a drag will look like.

### Block 4 — the raise-transition duration (§4.4)

The number that decides Route B.

```bash
adb shell settings get global transition_animation_scale
adb shell cmd window tracing start
#   ... tap a partially covered background window on display 170 ...
adb shell cmd window tracing stop && adb pull /data/misc/wmtrace/wm_trace.winscope
```

Near-instant → Route B's raise glitch is a blemish. 250–300 ms → Route B must not ship as
per-window chrome; go Route A or go host-side.

### Block 5 — permissions, recents, a11y

```bash
adb shell dumpsys package com.android.shell | grep -E \
  "MANAGE_ACTIVITY_TASKS|REMOVE_TASKS|START_ACTIVITIES_FROM_BACKGROUND|INTERNAL_SYSTEM_WINDOW|ADD_TRUSTED_DISPLAY|ACCESS_SURFACE_FLINGER"
adb shell settings get global hidden_api_policy
adb shell dumpsys activity recents | grep -E "Recent #|realActivity|displayId" | head -40
```

`REMOVE_TASKS` is the one nobody has verified and Route B's orphan reaping needs it.
For recents: launch an app on 170 first — `RecentTasks#isInVisibleRange` keeps an
`excludeFromRecents` task only when `task.isOnHomeDisplay() && isMostRecentTask`, and
Samsung's DeX taskbar is exactly the thing that would patch that gate.

Then, for Route A specifically — the cheap fatal, before any UI code:

```bash
adb shell settings put secure enabled_accessibility_services \
    com.ccrstech.openandroiddex.launcher/.ChromeAccessibilityService
adb shell settings put secure accessibility_enabled 1
# leave it for an hour; then:
adb shell settings get secure enabled_accessibility_services   # did One UI revert it?
```

and attach one `SurfaceControlViewHost` to one window on display 170 and check that it
appears **in the scrcpy capture**, not merely on the phone.

### Decision rule

- §6 gives us native captions → stop, ship that, delete this document.
- Block 3 fails → neither route has a bounds path; fall back to host-side Tauri chrome.
- Block 5 a11y holds and the overlay is visible in capture → **build Route A.**
- a11y fails but Block 4 says the raise transition is fast → build Route B, chrome below,
  minimal frame.
- Both fail → host-side Tauri chrome with outline drag, as the previous review concluded,
  but now with §7.1 and §7.2 taken as independent wins.

---

## 9. Why this is not a rerun of the last attempt

The previous attempt failed on §3.1 and correctly generalised it to "a non-system process
cannot place a window between two app windows". That is true of **windows**. The two
routes here do not add a window between two app windows:

- **Route A** puts the chrome *inside* an app's own window surface, using an API whose
  entire purpose is to let an external process do exactly that, and which the framework
  itself marks as a trusted overlay on the way in.
- **Route B** does not add a window at all. It adds a **task**, into the one container
  where WindowManager sorts purely by a list index — a list index the daemon can already
  edit with a permission it already holds.

The decisive design lesson, and the thing to hold onto if this is ever revisited a third
time:

> **The only chrome that never separates from its window is chrome that is a descendant of
> that window's surface in the SurfaceFlinger scene graph.** AOSP gets it from
> `.setParent(mTaskSurface)`. Any design where chrome is a sibling — a peer task, a peer
> layer, a host-side overlay — is re-litigating synchronisation on every frame, and will
> lose on the frames that matter.

Route A is a descendant. Route B is a sibling with the invariant reduced to its smallest
possible form. That ordering is not a preference; it is the whole result.
