# Open Android DeX Win — Tauri v2 Rebuild Plan

> Goal: rebuild a Samsung-DeX-style desktop for Android phones on Windows, as a Tauri v2 app.
> Strategy: ship a **basic, working version early** (mirror + settings), then layer the DeX shell and advanced services on top in later phases.
>
> **Note on section 1.** It inventories a *third-party closed-source* Windows
> product, recorded as competitive analysis so the design decisions below have
> something to argue against. It is not prior art for this project and nothing
> here is derived from it — sections 2 onward are the original design.

---

## 1. What the third-party product ships (inventory of its Windows build)

That product is a **Flutter Windows** build. Inventory:

| Group | Files | Purpose |
|---|---|---|
| Shell app | `android_dex.exe`, `flutter_windows.dll`, `data/` (app.so, flutter_assets) | The DeX-like desktop UI |
| Video/render | `avcodec-62.dll`, `avformat-62.dll`, `avutil-60.dll`, `avfilter-11.dll`, `avdevice-62.dll`, `swresample-6.dll`, `swscale-9.dll`, `SDL2.dll`, `SDL2_image.dll`, `SDL2_ttf.dll`, `freetype.dll`, `libpng16.dll`, `zlib1.dll` | FFmpeg decode + render of scrcpy streams **inside** the shell (renders to a Flutter texture) |
| Flutter plugins | `clipboard_watcher_plugin.dll`, `pasteboard_plugin.dll`, `screen_retriever_windows_plugin.dll`, `url_launcher_windows_plugin.dll`, `window_manager_plugin.dll` | Clipboard watch, multi-monitor info, window control |
| ADB toolchain | `Build_copy/adb-windows/`: `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`, `libusb-1.0.dll` | Bundled ADB — no user install needed |
| scrcpy | `Build_copy/adb-windows/`: `scrcpy.exe`, `scrcpy-server`, `SDL3.dll` + own FFmpeg dlls | Stock scrcpy as fallback/runner |
| Custom device servers | `Build_copy/`: `AndroidDex-main-server.jar` (scrcpy fork v4.0), `AndroidDex-audio-server.jar`, `AndroidDex-vd-server.jar`, `AndroidDex-flex-display-server.jar` | Pushed to `/data/local/tmp`, run via `app_process` — per-app virtual displays + per-app audio |
| Companion runtime | `androiddex.jar` | Shell-privileged JAR client (clipboard monitor, wakelock) connecting back over `adb reverse` |
| Companion app | `AndroidDex.apk` (`com.example.androiddex`) | Notification listener, media control, app list + icons, wallpaper, bluetooth state, contacts/call features |
| Assets | wallpapers ×5, Samsung Sharp Sans fonts, dex app icons, help GIFs, `Images/` window-chrome icons | Branding/UI |
| Launcher | `_start.bat` | Menu: normal / admin / `--debugging` variants |
| Log | `android_dex_log.txt` | Structured debug log |

### Runtime flow (from the debug log)
1. Start bundled ADB server → connect device.
2. `adb reverse` network bridge; local servers: JAR bridge **:3698**, APK WebSocket **:3699**, Notification **:3700**, Media **:3701**, scrcpy event server **:3702**.
3. Push scrcpy/audio/virtual-display JARs to the device.
4. Install + launch companion APK; verify permissions (contacts, call log, phone, bluetooth, notification listener, battery optimization).
5. Companion streams state: app list (~154 apps), wallpaper, bluetooth, notifications, media.
6. **Per-app windows**: each app is launched on its own *virtual display* (e.g. 1920×1080/450) by the scrcpy-fork server (`new_display`, `start_app`), one stream + port per window; audio per app via playback capture; frames decoded on the PC and rendered as textures in the shell.

Key insight: the "DeX desktop" = many concurrent scrcpy virtual-display streams, each rendered as a window inside one desktop-shell UI, plus a companion APK for everything scrcpy can't do (notifications, media, app icons).

---

## 2. Our stack

| Concern | Choice |
|---|---|
| Shell | **Tauri v2** (Rust backend, WebView2 frontend) |
| Frontend | React + TypeScript + Vite + Tailwind (glassmorphism via CSS variables) |
| State/settings | `tauri-plugin-store` (JSON on disk), applied live via CSS vars |
| Bundled binaries | `adb.exe` (+ dlls) and `scrcpy.exe` + `scrcpy-server` as Tauri **sidecars/resources** |
| Video (basic) | Spawn stock `scrcpy.exe` → its own window (zero decode work for us) |
| Video (advanced) | Rust talks to `scrcpy-server` sockets directly → relays H.264 to webview → **WebCodecs** decode → canvas render (embedded windows, no FFmpeg-in-Rust needed) |
| Installer | Tauri bundler → **NSIS** single .exe installer, double-click → run |
| Logging | `tauri-plugin-log` + `--debugging` flag → `android_dex_log.txt`-style file |

License note: scrcpy and ADB are Apache-2.0 — bundling is fine with attribution.

---

## 3. Phases

### Phase 0 — Scaffold & foundation (½–1 day)
- `create-tauri-app` (v2) + React/TS/Vite/Tailwind.
- Project layout: `src/` (features folders), `src-tauri/` (Rust modules: `adb`, `scrcpy`, `settings`, `log`).
- Bundle sidecars: `adb.exe` + `AdbWinApi.dll`/`AdbWinUsbApi.dll`, `scrcpy.exe` + `scrcpy-server` + its dlls (resources dir).
- Plugins: store, log, single-instance, window-state, shell/process.
- Dark glass UI tokens (CSS variables: `--blur`, `--surface-alpha`, `--radius`) + app frame (custom titlebar like the screenshot).
- **Done when:** app builds, opens a dark glass window, sidecar `adb version` returns output shown in UI.

### Phase 1 — Device manager (ADB layer) (1–2 days)
- Rust `adb` module: start/kill server, `adb track-devices` (live device add/remove events → frontend via Tauri events), `getprop` for brand/model/Android version, battery level.
- USB first; wireless connect screen (`adb pair` / `adb connect ip:port`) as a simple form.
- UI: device card (model, state, connection type), connect/disconnect, "waiting for device" state.
- **Done when:** plugging/unplugging the phone updates the UI live with correct model info.

### Phase 2 — Mirroring MVP (basic version target) (1–2 days)
- Rust `scrcpy` module: build CLI args from settings, spawn `scrcpy.exe` (external window), track PID, detect exit, auto-reconnect option.
- Options wired: resolution/max-size, bitrate, max FPS, audio on/off, stay-awake, turn-screen-off, always-on-top, fullscreen, window title.
- Big "Start Mirroring" button on the device card.
- **Done when:** one click mirrors the phone reliably; closing either side cleans up.

### Phase 3 — Settings app (matches the screenshot) (2–3 days)
- Sidebar nav: **Display & UI** (dark mode, glass effects: blur intensity / surface transparency / item rounding sliders, display size), **Language & Font**, **Wallpaper** (bundled wallpapers + custom file, darkness overlay), **Scrcpy Config** (app mode, resolution, performance presets), **Clipboard Manager** (enable, history size) — plus settings search.
- All values persisted via store plugin; glass sliders update CSS variables live.
- i18n scaffold (en + your second language) with a simple JSON dictionary.
- **Done when:** UI is a close visual match to the reference screenshot and every control persists across restarts.

> ✅ End of Phase 3 = shippable **basic version**: bundled ADB, one-click mirroring, polished settings. Installer work (Phase 7) can be pulled forward here if you want to distribute early.

### Phase 4 — DeX desktop shell (3–5 days)
- Full-window desktop: wallpaper, taskbar (running apps, clock, battery/bt status), app drawer.
- App list: `pm list packages -3` + labels via `dumpsys`/`cmd package` (basic: default icon; icons/labels done properly in Phase 6 via companion APK).
- Launch app in its own window: spawn one scrcpy process per app with `--new-display=1920x1080` `--start-app=<package>` (scrcpy ≥ 3.x) — windows are still native scrcpy windows in this phase, positioned/tracked by the shell.
- Multi-window manager: track open app sessions, close/refocus from taskbar.
- **Done when:** you can open 2–3 phone apps side-by-side from the app drawer, each on its own virtual display.

### Phase 5 — Embedded rendering (the big lift) (1–2 weeks, isolated)
- Replace external scrcpy windows with in-shell windows:
  - Rust connects to `scrcpy-server` sockets over `adb forward` (video + control).
  - Relay H.264 NALUs to the webview over a local WebSocket; decode with **WebCodecs** (`VideoDecoder`, h264 supported in WebView2); render to `<canvas>` per app window.
  - Input: forward mouse/keyboard events from each canvas → scrcpy control protocol (Rust encodes control messages).
- Draggable/resizable in-shell windows with the glass chrome from `Images/` equivalents.
- **Done when:** an app window renders inside the Tauri shell at ≥30fps with working touch/keyboard.

### Phase 6 — Companion services (optional, post-basic) (1–2 weeks)
- Clipboard sync PC↔phone (scrcpy control socket covers the basic case; watcher on PC side).
- Companion APK (our own, minimal): app list **with icons**, notification listener → PC toast panel, media control, wallpaper sync — over `adb reverse` WebSocket like the reference (ports 3698–3702 pattern).
- Per-app audio via `--audio-source=playback` + `--audio-dup` (scrcpy ≥ 2.6) instead of a custom audio server, as the basic approach.
- **Done when:** app drawer shows real icons and notifications appear on the PC.

### Phase 7 — Packaging & distribution (1–2 days)
- NSIS installer via Tauri bundler; all sidecars/resources included; icon, product name, version.
- First-run check: WinUSB/OEM driver hint if no device seen.
- Optional: `tauri-plugin-updater` for auto-updates; `--debugging` flag → file log.
- **Done when:** a fresh Windows machine can double-click the installer and mirror a phone with nothing else installed.

---

## 4. Risks & notes
- **WebCodecs H.264 in WebView2**: supported, but verify early in Phase 5 with a spike before committing (fallback: FFmpeg decode in Rust → raw frames over shared texture, heavier).
- **Blur performance**: CSS `backdrop-filter` on many layers can be expensive in WebView2 — keep glass to top-level surfaces, expose the blur-intensity slider (as the reference does) so users can tune it.
- **Multiple scrcpy instances**: each needs its own port/scid — the Rust module must allocate ports (like the reference's 12345, 14001…).
- **Icons/labels without an APK**: genuinely awkward via adb alone; that's why the reference ships a companion APK. We defer it (Phase 6) instead of blocking the basic version.
- **Admin start**: reference offers "Administrator Start" — we only need it for global shortcuts over elevated windows; keep normal start as default.

## 5. Suggested repo layout
```
open-android-dex-tauri/
├─ src/                      # React frontend
│  ├─ features/{devices,mirror,settings,shell}/
│  └─ lib/{store,i18n,glass}/
├─ src-tauri/
│  ├─ src/{adb,scrcpy,ports,log}.rs
│  ├─ resources/adb-windows/     # adb.exe + dlls
│  ├─ resources/scrcpy/          # scrcpy.exe, scrcpy-server, dlls
│  └─ tauri.conf.json
└─ assets/ (wallpapers, icons, fonts)
```

---

**Status (2026-07-29): Phases 0–4, 6 (no-APK parts) and 7 implemented. The desktop
(Phase 4/5) uses a different architecture than originally planned: ONE scrcpy
`--new-display` session per device creates a secondary display on which Android runs its
own home screen + nav bar (Samsung-DeX style); apps are sent to it with
`am start --display <id>` — no per-app streams, no embedded rendering, no companion APK.
Requires Android 10+ (secondary home works best on 13+/Samsung). Phase 5's WebCodecs
in-shell rendering remains future work; a Win32 SetParent embed module exists in
`src-tauri/src/embed.rs` but is currently unused by the UI.**
