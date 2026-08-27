# Wireless DeX with no adb: the hosted Miracast route

*Design record for `dexcast/` (2026-08). Current. Companion to the reasoning
in `projection.rs`, which it builds on rather than replaces.*

## The ask

A wireless desktop on Windows with **no adb at all** — no cable ever, no
Developer options, nothing installed on the phone. The proposal that started
it: use [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver)
(VDD) somehow.

## What is actually possible

Everything this app normally does rides adb: the launcher, the wmd daemon,
scrcpy's virtual display. None of it can exist without adb. The only
adb-free way a phone can put a *desktop* on a PC is Samsung's own wireless
DeX, which casts over Miracast to any Miracast sink. All of the following
was verified against primary sources in Aug 2026, because each point is
load-bearing:

- **Wireless DeX is alive on current One UI.** Samsung's device list for
  "Wireless DeX with a TV or monitor" includes One UI 7/8-era devices (S25,
  S26, Z Fold7/8, Tab S11…). What One UI 7 killed was the *DeX for PC app*
  route (USB/app), not Miracast. Samsung's own wording: works with Samsung
  TVs "and any other device that supports Miracast screen mirroring".
- **The only Miracast sink a Windows PC can have is Windows' own** Wireless
  Display receiver (`Microsoft.PPIProjection`, `Receiver.exe`). We cannot be
  the sink ourselves — `MiracastReceiver` answers `MiracastNotSupported` to
  Win32 processes (see `projection.rs`). The receiver is launched by
  activation only (`explorer.exe shell:AppsFolder\Microsoft.PPIProjection_
  cw5n1h2txyewy!Microsoft.PPIProjection`; running `Receiver.exe` directly
  crashes with 0xc0000409), and its top-level window belongs to
  `ApplicationFrameHost.exe` (class `ApplicationFrameWindow`) with the app's
  `Windows.UI.Core.CoreWindow` as a child.
- **The receiver's output is capturable.** The old OBS black-screen reports
  were the pre-WGC BitBlt limitation, not DRM; `Windows.Graphics.Capture`
  captures UWP windows and monitors fine. DRM enforcement happens on the
  *phone* (protected apps black themselves out because the receiver offers
  no HDCP path) — the receiver window itself carries no capture protection.
- **Input truth**: the receiver forwards keyboard/mouse/touch to the sender
  over Miracast UIBC natively, but the *sender* decides what to honour.
  Samsung phones reliably honour the keyboard and frequently not the mouse
  (Microsoft Q&A and Samsung community reports, 2021–2025, still true in
  2025 reports on Win11). No hosting design on the PC can fix that; the UI
  must be honest about it. Fallback: "Use your phone as a touchpad".
- **The PC still needs a Miracast-capable Wi-Fi adapter** even over
  infrastructure (MS-MICE only changes the transport after discovery;
  discovery rides Wi-Fi Direct). Ethernet-only desktops cannot receive.
  `projection_support`'s netsh check remains the right gate.
- **Windows 11 24H2/25H2 quirks**: "Wireless Display" is often missing from
  the Optional Features UI (the DISM-by-name install in `projection.rs` is
  the fix), and receiving fails for *standard users* until a firewall allow
  rule exists for `Receiver.exe` — the elevated install script adds one.

## Where VDD fits

Left alone, the receiver takes over a real monitor and nothing about it is
ours. VDD gives the machine a monitor that does not exist; the session parks
the receiver there fullscreen, `Windows.Graphics.Capture` mirrors that
monitor into a normal window of this app, and the user gets Samsung's DeX
inside something they can move, resize and close. Facts that shaped the
module, all verified against the driver's source:

- MIT-licensed, SignPath-signed, and the **driver-only archive is 132 KB**
  (`VirtualDisplayDriver-x86.Driver.Only.zip`). The 71 MB "VDD Control"
  companion app is a tray UI over the driver's pipe — everything it does
  that we need, `dexcast/vdd.rs` does directly.
- Install = import the SignPath cert from the signed catalog into
  `TrustedPublisher`, then nefcon creates the `Root\MttVDD` device node and
  installs the INF. **Driver install requires admin, full stop** — that is
  the one UAC prompt this feature has, and nothing per-session elevates.
- The driver's control pipe `\\.\pipe\MTTVirtualDisplayPipe` is created
  with SDDL `D:(A;;GA;;;WD)` — Everyone, Generic All — so post-install
  control needs no elevation. We use it for `PING`/`PONG`, which doubles as
  the "is VDD installed and running" probe.
- **The driver cannot idle at zero monitors**: `Driver.cpp` clamps a
  monitor count of 0 back to 1 (both in the XML load and therefore after
  every `SETDISPLAYCOUNT`). So "off" is expressed by *detaching* the
  monitor from the desktop (`ChangeDisplaySettingsExW` with zero size,
  `CDS_UPDATEREGISTRY | CDS_NORESET`, then a commit call) — plain user
  APIs, persisted across reboots by Windows' topology store. Attach does
  the reverse at the desktop's right edge and reads back where Windows
  actually put it.
- Settings live in `C:\VirtualDisplayDriver\vdd_settings.xml` (element
  names from the driver's XML reader: `<monitors><count>`,
  `<resolution><width>/<height>/<refresh_rate>`). Ours pins a single mode,
  1920×1080@60 — wireless DeX tops out at 1080p on non-Samsung-TV sinks, and
  every extra mode is one more thing Windows may pick mid-attach. An
  existing `vdd_settings.xml` is never overwritten (a user who already runs
  VDD configured it on purpose; if the pipe already answers, install is
  skipped entirely and the driver is adopted).
- The payload is **downloaded at the moment the user opts in** (132 KB
  driver + ~2 MB nefcon), pinned by release tag and SHA-256 in
  `payload.rs`, verified with certutil before a byte is trusted. Bumping
  the pin is a reviewable code change. System curl.exe/tar.exe do the
  fetching and unpacking — both ship in System32 since Windows 10 1803.
- **Trust is re-anchored inside the elevation.** The download-time pin does
  not protect the elevated *read*: a user-level process could swap the
  extracted nefcon/INF in the (user-writable) staging dir between staging
  and the admin script. So the elevated script copies the two zips into an
  admin-only `%SystemRoot%\Temp` work dir, re-checks their SHA-256 against
  pins baked into the script text (which comes from the app binary), and
  installs only from what it extracts there — copy → verify → extract →
  run, so the bytes checked are the bytes used. The elevation itself is one
  `Start-Process -Verb RunAs`; the script path travels by environment
  variable (never string-interpolated, so a profile path with a space,
  quote or `$` cannot break or inject it), and a declined UAC prompt is a
  distinct exit code, not a silent success.
- SudoVDA (Apollo's driver) was considered — it has a true per-session
  create/destroy IOCTL API — but it is self-signed and its install would
  put a self-signed cert in the machine's **Root** store. VDD's
  TrustedPublisher-only trust is the better posture, and detach/attach
  makes the IOCTL advantage moot.

## The session, end to end

1. `dexcast_start`: pipe must answer, receiver must be installed.
2. Attach the virtual monitor (right edge of the desktop; read back the
   actual rect).
3. Activate the receiver, find its frame window (class match + CoreWindow
   child pid — `Receiver.exe` is not a unique name, Citrix ships one, so
   pids are confirmed against the SystemApps path), strip caption, park it
   fullscreen on the virtual monitor. Placement is re-asserted once after
   500 ms because ApplicationFrameHost sometimes reasserts geometry.
4. The viewer (`viewer.rs`): one thread owning window + D3D11 + D2D + WGC
   monitor capture. A 15 ms timer drains `TryGetNextFrame`; frames are
   `CopyResource`d into one staging texture wrapped once as a D2D bitmap,
   drawn letterboxed (`geometry.rs`, unit-tested). Cursor capture on.
5. Input is a **portal**: click inside the video → focus moves to the
   receiver, the real cursor teleports to the mapped spot on the virtual
   monitor, `ClipCursor` fences it there; every event thereafter is real
   input delivered by Windows. Esc takes it back via a `WH_KEYBOARD_LL`
   hook (same recipe and reasoning as `hotkeys/windows.rs` — bare Esc only,
   swallow the matching key-up, only when the receiver is foreground).
   Alt-Tab away auto-releases the clip.
6. Teardown (Stop button, viewer window closed, app exit — all idempotent):
   close the receiver with `WM_CLOSE` first (that ends the Miracast session
   properly so the phone shows "disconnected"; kill only if it will not
   die), detach the monitor. `dexcast::shutdown()` runs next to
   `scrcpy::kill_all` in the exit handler because both the receiver and the
   monitor are machine state that survives this process.

## What this is not

It is Samsung's DeX shell, not this app's desktop — no taskbar of ours, no
widgets, no file drop, no window daemon. The panel says so before the steps,
not after. It is the route for "adb cannot be made to work", not a
replacement for the adb desktop. macOS has no version of this and cannot:
no Miracast receiver exists for macOS (Wi-Fi Direct is not exposed to third
parties) and DeX cannot send AirPlay. A macOS virtual display (e.g. the
kext-based EWProxyFramebuffer forks) would be a monitor with nothing to
show on it — the missing piece there is the sink, not the screen.

## Untested on real Windows (first-test checklist)

Everything compiles for `x86_64-pc-windows-msvc` and the portable logic is
unit-tested, but the following want eyes on first run:

- `nefconc --create-device-node`/`--install-driver` exit codes and whether
  the freshly installed driver's monitor arrives attached (assumed yes;
  install parks it immediately).
- Whether style-stripping the `ApplicationFrameWindow` sticks, and whether
  the receiver redraws at 1080p on the virtual monitor.
- `CreateBitmapFromDxgiSurface` over the staging texture on real drivers
  (WARP fallback exists).
- Whether the phone-side "Allow input" toggle appears and what a given One
  UI build honours over UIBC (keyboard expected; mouse device-dependent).
- DPI: viewer coordinates are physical; on a scaled monitor the process's
  DPI awareness (set by tao) should make client rects physical already.
