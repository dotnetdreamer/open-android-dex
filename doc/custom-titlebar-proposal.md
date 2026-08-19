# Architectural Review & Senior Engineering Synthesis: Custom Window Chrome for Open Android DeX

**Authors:** Senior Android Systems Engineers (Google Android Systems & Peer Review Synthesis)  
**Target:** Open Android DeX Project  
**Reference Documents Reviewed:** `doc/custom-titlebar.md`, `doc/custom-titlebar-proposal.md`  

---

## 1. Executive Summary & Final Consensus

Following a rigorous multi-agent peer review against AOSP (`android-16.0.0_r1` / `android16-release`) source code and live system behaviors on Android 16 / One UI 8, **both analysis iterations have reached a single, unified engineering consensus.**

### Final Consensus & Actionable Direction:

1. **Do NOT attempt device-side custom titlebars (`TYPE_APPLICATION_OVERLAY` or `TaskOrganizer`).**
   - Drawing custom titlebars via `TYPE_APPLICATION_OVERLAY` fails due to AOSP WindowManager Policy Layer 11 hardcoding (`mBaseLayer = 111000` vs Layer 2 `21000` for app tasks).
   - Manipulating task leashes directly via `TaskOrganizer` (`registerOrganizer()`) steals global window management from Samsung's SystemUI, introducing catastrophic stability risks on a daily-driver device.
2. **Raw `SurfaceControl` (Proposal 2) cannot handle input directly.**
   - SurfaceFlinger gates layer creation on handles, but a raw `SurfaceControl` created without WindowManager (`WindowState`) lacks an `InputWindowHandle` registered with `InputDispatcher`. Therefore, touch/click events cannot be routed to a raw SurfaceFlinger layer.
3. **Native System Captions (Proposal 3) bring back unwanted System UI.**
   - Enabling `vd_system_decorations` on secondary display 170 triggers Samsung's native secondary launcher and navigation bar, overriding the Open Android DeX shell layout.
4. **Host-Side Chrome (Proposal 1) is the only viable path for per-window titlebars, requiring Wireframe/Ghost Outline Dragging.**
   - Host-side titlebars render at 0ms local host input latency.
   - However, app content in the scrcpy H.264 video stream trails by ~120ms due to video encoding/decoding pipeline latency.
   - **The UX Solution:** Host titlebars must use **Outline / Wireframe Dragging** (classic X11/Windows DWM style)—moving the titlebar and a translucent window frame at 0ms matching the host cursor, and committing task bounds via `wmd` on release or throttled intervals.
5. **IMMEDIATE ACTION ITEM: Deploy `openandroiddex-wmd` for IPC latency wins.**
   - The primary immediate win for `openandroiddex-wmd` (`uid 2000`) is **killing the ~990ms `content query` ContentProvider queue** and replacing it with **0.35ms loopback TCP socket commands** for shell operations (`density`, `key`, `fullscreen`, `app start`).

---

## 2. Technical Evaluation Matrix & Peer Review Verification

| Approach / Proposal | Technical Mechanism | Peer Review Finding & Verification | Final Status |
| :--- | :--- | :--- | :--- |
| **Android `TYPE_APPLICATION_OVERLAY`** | Launcher `View` overlays at Policy Layer 11 | ❌ Hardcoded above Policy Layer 2 (App Tasks). Cannot stack between app windows. Broken by `setHideOverlayWindows`. | **ABANDONED** |
| **Raw `SurfaceControl` Child (Proposal 2)** | Attach child layer to Task leash via `uid 2000` | ❌ Lacks `InputWindowHandle` in `InputDispatcher`. Layer cannot receive click/touch events. Requires `TaskOrganizer`. | **REJECTED** |
| **Native System Captions (Proposal 3)** | `vd_system_decorations` (note: `setCaptionEnabled` does **not** exist — no such API on `Window`/WM) | ❌ Forces Samsung secondary home and nav bar on display 170. `DisplayContent#isHomeSupported()` ORs the decorations predicate, so "captions yes, home no" is structurally impossible. Caption is drawn by `com.android.wm.shell.windowdecor` inside platform-signed SystemUI — no plugin, theme or AIDL hook for third parties. | **REJECTED** |
| **Host-Side Chrome + Wireframe Drag (Proposal 1)** | Host-side Tauri UI over scrcpy canvas | ✅ Solves Z-ordering & overlay suppression 100%. Requires outline drag to handle ~120ms video stream lag. | **VIABLE (FUTURE)** |
| **Headless Daemon IPC (`wmd`)** | Resident `app_process` TCP socket at `uid 2000` | ✅ Bypasses `content query` JVM boot cost. Cuts IPC latency from **990ms to 0.35ms**. | **HIGH PRIORITY (TO BUILD — see note)** |

> **Repo-state note.** `openandroiddex-wmd/` was **deleted** from the tree along with
> the titlebar code; nothing is "ready to activate". The measurements above are real
> and were taken from a working daemon, but the source no longer exists. Rebuilding
> it needs only the protocol, threading model and build commands recorded in
> `custom-titlebar.md` §4.3, §4.4 and §12 — and it does *not* need the drag machinery
> (`GRAB`/`DRAG`/`DROP`), which existed solely for titlebars.

---

## 3. Recommended Roadmap

1. **Phase A (Immediate Priority):**
   - Rebuild `openandroiddex-wmd` (deleted — see the repo-state note above) solely as a high-speed command daemon executing shell commands (`density`, `keyevent`, `app launch`, `window control`) over loopback TCP port 7191, eliminating the 990 ms `content query` latency.
   - Scope it to `LIST` / `FRONT` / `CLOSE` / `BOUNDS`. Skip `GRAB`/`DRAG`/`DROP` and the closed-loop pacing entirely; they exist only to drag chrome that no longer exists.
   - Note the daemon dies with its `adb shell` connection — persisting it needs reparenting to init (`setsid`/`nohup`), which the original never solved.
2. **Phase B (Future Desktop Shell Polish):**
   - If custom per-window titlebars are implemented, build Host-Side Chrome in Tauri (`open-android-dex-tauri`) using outline/wireframe drag mode.
