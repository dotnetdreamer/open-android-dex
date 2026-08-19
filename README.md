<div align="center">

# Open Android DeX

**A real desktop for your Android phone — on Windows and macOS.**

Plug the phone in. A full desktop opens on your monitor: taskbar, app drawer,
resizable windows with real titlebars, home-screen widgets, drag-and-drop from
your PC — plus an Ubuntu desktop and a working Docker engine running on the
phone itself.

Unplug it, and the phone is a plain phone again.

</div>

---

## Features

### The desktop

- **Zero-click launch** — plug in a phone with USB debugging on and the desktop
  starts by itself; the control window minimises out of the way.
- **DeX-style taskbar** — back/home/recents, an Apps toggle, a live open-apps
  row, battery pill, clock with a calendar popup, and a quick-settings tray
  (Wi-Fi, Bluetooth, mobile data, airplane, mute, rotate, location, torch, lock).
- **Real window chrome** — every app window gets a titlebar with close,
  minimise, maximise/restore and snap left/right, plus drag-to-move.
- **Desktop icons and real widgets** — drag an app out of the drawer to place
  it; add genuine Android home-screen widgets with resize handles. A click
  inside a widget opens its app *windowed*, not fullscreen.
- **App drawer** with search and glass blur where the device supports it.
- **Task Manager** — CPU, memory and storage gauges plus per-app usage.
- **In-desktop Settings** — resolution, bitrate, fps, codec and encoder, audio,
  clipboard sync, display density, pointer speed, themes (dark/light/paper),
  glass and rounding, 10 locales, fonts, wallpapers, and a Reduce-quality mode.

### Connectivity

- **Wireless** — move a live USB session onto Wi-Fi and pull the cable. The
  phone is remembered and reconnected automatically next run.
- **Pairing by QR code** (the app generates it) or by the 6-digit code from
  Developer options.
- **Auto-reconnect** when the phone drops, with one automatic degraded retry.
- **Drag and drop** files from your PC onto the window — pushed to
  `/sdcard/Download/`, `.apk` files are installed, with a progress HUD on the phone.

### Web viewer — a browser tab, nothing installed

- **The launcher serves it itself.** Open the Web viewer tile, press Start, and
  the phone shows an address like `http://192.168.1.20:8787`. Type it into a
  browser on any computer on the network and enter the six-digit code. No PC
  app, no extension, no account.
- **WebRTC, and only WebRTC** — peer-to-peer video, control and files. No
  fallback stream, no port forwarding, no tunnel to configure: the transport
  that solves reachability is the transport.
- **Reachable from anywhere, with no inbound route at all.** Point it at your
  own TURN server and at `openandroiddex-signal` — a ~400-line dependency-free
  Node relay in this repo, meant to sit beside an existing coturn — and the
  phone dials *out* and waits in a room. That works on mobile data behind
  carrier-grade NAT, where no port forward can reach. The relay carries
  kilobytes: it never sees the access code and never sees a frame.
- **Or without any of that** — on your own network the phone serves the page
  itself and its own socket is the rendezvous, so the address it shows is all
  you need.
- **Control** — clicks become touches, typing goes to the focused field, and the
  page has Back, Home, Recents, quick settings, screenshot and lock. Can be
  turned off for a view-only session.
- **Files both ways** — browse and download from the phone, and drop files onto
  the page to send them to `/sdcard/Download`, with the same progress card on
  the desktop that a drag from your PC raises. All of it over the data channel,
  so it works in either kind of session.

### Guest environments — on the phone, no root

- **Linux** — Ubuntu 24.04 with XFCE under `proot`, in a resizable DeX window
  via noVNC. Ships Firefox, Chromium, VS Code, git and openssh, and a shared
  folder at `/sdcard/LinuxOnDeX` visible in your file manager.
- **Docker** — a real Docker engine inside a QEMU-TCG Alpine VM. Container and
  image lists over the Engine API, plus a root serial console. `docker pull`,
  `docker run` and `docker compose` all work.

---

## Install

Grab the latest [release](https://github.com/dotnetdreamer/Android-Dex/releases/latest).
`adb`, `scrcpy`, the launcher APK and the window daemon are all inside the
download — nothing to install separately.

| Your machine | File |
| --- | --- |
| Windows | `..._x64-setup.exe` (installer) or `..._x64_portable.zip` |
| Mac, Apple Silicon | `..._aarch64.dmg` |
| Mac, Intel | `..._x64.dmg` |

**On the phone:** enable Developer options (`Settings → About phone` → tap
*Build number* seven times), then turn on **USB debugging**. Plug in and accept
the authorisation prompt. That is the whole setup.

**macOS first launch** — the app is ad-hoc signed, not notarized, so macOS
blocks it once:

```bash
xattr -dr com.apple.quarantine "/Applications/Open Android DeX.app"
```

The `-r` matters: the bundled `adb` and `scrcpy` are quarantined separately.
macOS will also ask for **Local Network** (Wi-Fi discovery) and, only for the
taskbar's fullscreen button, **Accessibility**. USB needs neither.

---

## Repository layout

| Path | What it is |
| --- | --- |
| [open-android-dex-tauri/](open-android-dex-tauri/) | The PC app — Tauri v2 (Rust) + React 19 + TypeScript + Vite + Tailwind v4. Owns adb, scrcpy, pairing, the session profile and restore. |
| [openandroiddex-launcher/](openandroiddex-launcher/) | The on-device desktop shell (Android, `com.ccrstech.openandroiddex.launcher`) — everything you see. |
| [openandroiddex-wmd/](openandroiddex-wmd/) | The uid-2000 window daemon. Gradle-less Java compiled straight to one `.dex`; serves a plain-ASCII line protocol on `127.0.0.1:7191`. |
| [openandroiddex-linux/](openandroiddex-linux/) | Ubuntu-under-proot provisioning and runtime scripts, plus the `proot` build. |
| [openandroiddex-docker/](openandroiddex-docker/) | Alpine-on-QEMU VM scripts and the `libqemu.so` build. |
| [openandroiddex-signal/](openandroiddex-signal/) | The WebRTC rendezvous for the web viewer — one dependency-free Node file, meant to run beside your own coturn. Optional; only needed to reach a phone with no inbound route. |
| [test/](test/) | The rendezvous, checked end to end without a phone. Plain Node, no dependencies. |
| [doc/](doc/) | Design records — window-chrome architecture and measurements. |
| [.github/workflows/desktop.yml](.github/workflows/desktop.yml) | The one workflow that versions, builds and releases everything. |

---

## Build it yourself

**Prerequisites:** Node ≥ 20, Rust (stable), JDK 17+, Android SDK (platforms
`android-35` and `android-36`, build-tools with `d8`), with `ANDROID_HOME` and
`JAVA_HOME` set. Windows also needs VS Build Tools with the C++ workload;
macOS needs the Xcode command line tools.

```bash
cd open-android-dex-tauri
npm install
npm run tauri dev
```

`npm run tauri dev` is **the** way to run this. It rebuilds both phone-side
payloads, deploys them, and applies the permissions in the order that matters —
hand-launching scrcpy or sideloading the APK skips all of that.

```bash
npm run tauri build     # Windows → bundle/nsis/ ; macOS → bundle/{macos,dmg}/
npm run apk             # phone-side payloads only (APK + wmd dex + asset sync)
cargo test              # from src-tauri/ — adb, scrcpy and wm parsing tests

# Frontend-only work, no JDK or Android SDK needed:
SKIP_LAUNCHER_APK=1 SKIP_WMD_DEX=1 npm run tauri dev

# Raise the log level (default: debug)
OADX_LOG=trace npm run tauri dev
```

A fresh clone has an empty `src-tauri/resources/bin/`. Unpack
`scrcpy-win64-v3.3.4.zip` (or the matching macOS tarball) into it, flattened,
for a local bundle.

Rebuilding the native prebuilts is rarely needed and requires Docker:

```bash
bash openandroiddex-linux/proot/build.sh    # libproot.so, libloader*.so
bash openandroiddex-docker/qemu/build.sh    # libqemu.so
```

### Releasing

```bash
gh workflow run desktop.yml -f bump=patch      # or -f version=0.4.0, or -f dry_run=true
```

One dispatch bumps the version, builds the phone payloads once on Linux, builds
the desktop app for Windows x64 and both macOS arches, and cuts a single release.

---

## Troubleshooting

The app writes one trace log per run — every adb command, the full scrcpy argv
and output, each UI step and the phone's own logcat, in one timeline:

```
Windows   %LOCALAPPDATA%\com.ccrstech.openandroiddex\logs\open-android-dex.log
macOS     ~/Library/Logs/com.ccrstech.openandroiddex/open-android-dex.log
```

The **Diagnostics** button writes a state dump beside it — host state, device
probes and the run's trace in one attachable file. Attach that to any issue.

---

## Licence

Copyright © 2026

Open Android DeX is free software, released under the **GNU General Public
License v3.0 or later** — see [LICENSE](LICENSE). It is distributed alongside
independent third-party works that keep their own licences (scrcpy, adb, QEMU,
proot, FFmpeg and others); see [COPYRIGHT](COPYRIGHT) and
[THIRD_PARTY_LICENSES.md](open-android-dex-tauri/src-tauri/resources/THIRD_PARTY_LICENSES.md).
