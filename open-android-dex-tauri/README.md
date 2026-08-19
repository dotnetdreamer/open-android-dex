# Open Android DeX (Tauri v2)

Samsung-DeX-style desktop for Android phones, on **Windows and macOS**. See
`../open-android-dex-tauri-plan.md` for the full phase plan.

## Stack

- **Tauri v2** (Rust backend) + React 19 + TypeScript + Vite + Tailwind v4
- Bundled **adb** + **scrcpy** in `src-tauri/resources/bin/` (git-ignored — see
  that directory's note in `.gitignore` for which archive to unpack there)
- Plugins: store (settings), log, single-instance, clipboard, os

## Develop

```
npm install
npm run tauri dev
```

Requires Node ≥ 20, Rust (stable), and — because `npm run apk` rebuilds the two
phone-side payloads on every run — a JDK 17+ and the Android SDK (platform
`android-36`, build-tools with `d8`). Set `ANDROID_HOME` if the SDK is not in
the default location for your platform; `JAVA_HOME` if you have no Android
Studio. Set `SKIP_LAUNCHER_APK=1 SKIP_WMD_DEX=1` to work on the frontend
without either.

Per platform:

- **Windows** — VS Build Tools with the C++ workload.
- **macOS** — Xcode command line tools.

## Build

```
npm run tauri build
```

- **Windows** → NSIS installer under `src-tauri/target/release/bundle/nsis/`.
- **macOS** → `.app` and `.dmg` under `src-tauri/target/release/bundle/`.

## Platform differences

The phone-side half — launcher, taskbar, window daemon, captions, file drop —
is identical on both hosts; it runs on the phone. Everything below is about the
desktop side.

| | Windows | macOS |
|---|---|---|
| Window chrome | custom titlebar, our own caption buttons | custom titlebar, native traffic lights (`titleBarStyle: Overlay`) |
| Taskbar ⛶ fullscreen | borderless, covers the monitor | native Spaces fullscreen — **needs Accessibility permission** |
| "Show desktop" | `AppActivate` via the shell | `NSRunningApplication`, no permission needed |
| Wi-Fi discovery | firewall rule | **Local Network permission** (macOS 15+), declared in `Info.plist` |
| Miracast ("Project to PC") | supported | not offered — macOS has no receiver a phone can cast DeX to |
| Embedding scrcpy's window | `SetParent` reparenting (built, unused) | impossible; macOS has no cross-process reparenting |

### macOS configuration files

- `src-tauri/tauri.macos.conf.json` — merged over `tauri.conf.json` for macOS
  builds only (Tauri v2 does this by file name). It sets the bundle targets to
  `app`/`dmg`, and repeats the whole `app.windows[0]` object because the merge
  **replaces arrays wholesale** rather than merging them.
- `src-tauri/Info.plist` — merged into the generated one. It carries the Local
  Network usage string and the Bonjour service types adb browses; without them
  macOS denies discovery silently.

### macOS permissions

Neither is required to connect over USB and run the desktop.

- **Local Network** — Wi-Fi connect routes. Prompted on first use.
- **Accessibility** — only the taskbar's ⛶ fullscreen button, which resizes a
  window belonging to `scrcpy`. Prompted the first time it is pressed. Because
  release builds are signed ad-hoc rather than with a developer certificate,
  macOS ties the grant to that exact build — after an update you may have to
  toggle it off and on again.

## Glass design tokens

The UI reads CSS variables set in `src/index.css` — the Phase 3 settings app
drives these live:

| Variable | Purpose |
|---|---|
| `--glass-blur` | Blur intensity |
| `--surface-alpha` | Surface transparency |
| `--item-radius` | Item rounding |
| `--accent` | Accent color |

## Phase status

- [x] Phase 0 — scaffold, glass shell, bundled adb/scrcpy resources, `adb version`/`devices` commands
- [ ] Phase 1 — device manager (live tracking, wireless connect)
- [ ] Phase 2 — mirroring MVP (scrcpy launcher)
- [ ] Phase 3 — settings app (screenshot parity)
- [ ] Phase 4 — DeX desktop shell (per-app virtual displays)
- [ ] Phase 5 — embedded rendering (WebCodecs)
- [ ] Phase 6 — companion services
- [ ] Phase 7 — packaging
