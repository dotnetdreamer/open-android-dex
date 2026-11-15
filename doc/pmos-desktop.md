# A postmarketOS phone's desktop, on the Desktop tab

Plug in a phone running postmarketOS with Plasma Mobile or Phosh and the
Desktop tab opens its desktop in a window, the way it opens DeX for an Android
phone. The desktop is a screen of the phone's own, not a mirror of its display:
on Plasma, KWin makes a virtual output the size of this computer's screen, and
Plasma fills it as it would a monitor plugged in (wallpaper, dock, app drawer,
windows with title bars in convergence mode); on Phosh, wlroots' headless output
is sized for this computer and Phosh's shell moves onto it (see
[Phosh](#phosh)). The window here shows it and sends it the mouse and keyboard.
The Linux tab has the same phone as a card at its top.

This record exists because several of the choices below look arbitrary until
you know what was measured, on a Redmi Note 7 (Snapdragon 660, postmarketOS
edge, Plasma 6.8, KWin 6.8.2) on 2026-11-13.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The phone's helper | `openandroiddex-pmos/src/main.c` | the virtual output (KWin) or headless output (Phosh), the encoder, fake or virtual input, the phone's keymap, keeping the phone awake, putting a Phosh phone back |
| Building it | `openandroiddex-pmos/build.sh`, `Dockerfile` | an x86_64 Alpine with clang and an aarch64 Alpine sysroot; the binary goes to `prebuilt/` |
| The computer's end | `src-tauri/src/pmos.rs` | which phone is on the cable, signing in, putting the helper there, the session, the window's WebSocket |
| The Desktop tab | `src/features/pmos/usePmos.ts`, `DesktopPage.tsx` (`pmosContent`, `PmosAlso`), `PmosSignIn.tsx` | the phone's own flow beside the Android one, and its card on the Android screens |
| The Linux tab | `src/features/pmos/PmosLinuxCard.tsx` | the same phone: sign in, Open desktop |
| The window | `src/features/pmos/PmosDesktop.tsx` | `UsbDesktop` (usb-desktop.md) pointed at pmos.rs |

## The pipe

```
KWin virtual output ──PipeWire (BGRA, shared memory)──▶ pipewiresrc
  ──▶ videoconvert (I420) ──▶ x264enc (zerolatency, ultrafast) ──▶ h264parse ──▶ appsink
  ──stdout, framed──▶ sshd ──SSH over the USB network──▶ pmos.rs
  ──127.0.0.1:<port>, checked──▶ the window's WebSocket ──▶ VideoDecoder ──▶ canvas
```

Input goes back the same way, as the messages `UsbDesktop` already sends the
phone's Linux: RFB pointer masks and X keysyms. The helper speaks those
messages, so the window needed no new protocol, only `smooth` (below).

**SSH, and its exec channel, not a port.** postmarketOS puts a network on the
USB cable (the phone is always 172.16.42.1) and runs sshd on it. That sshd has
`AllowTcpForwarding no`, so there is no tunnel to open, and a port of the
helper's own would be one more thing listening on the phone. The helper's
stdin and stdout are the whole conversation, each message framed as
`[kind u8][length u32 BE][payload]`, kind
0 JSON text and 1 binary: the WebSocket's two kinds, which pmos.rs turns into
each other. Nothing listens on the phone, and on this computer only the
window's loopback port does, under remote_usb.rs's checks (`Host`, `Origin`,
the window's token).

**Signing in once.** The first plug-in asks for the username and password the
owner uses on the phone. pmos.rs signs in with them, writes this computer's
ed25519 key into `~/.ssh/authorized_keys` (the key file is `pmos-key` in the
app's settings folder, an OpenSSH key `ssh -i` takes too), logs in again with
the key to prove it works, and pins the phone's host key. The password is not
kept or logged. After that a plug-in opens the desktop with nothing typed,
once per plug-in, never while an Android desktop is opening or open.

**Beside Android phones.** An Android phone that adb can use, on a cable or on
Wi-Fi, keeps the Desktop tab's page as it always has. The postmarketOS phone
is then a card on that phone's screens ("is ready", the running desktop, "was
closed"): signing in, opening its desktop, showing it and closing it all
happen on the card, and its desktop can be open beside the Android phone's,
each in its own window. Without such a card a phone was unreachable on a
computer with any Android phone connected, which the first tests, run with no
Android phones, did not show. The Linux tab's card signs in too.

**Which phone.** Polling costs the phone nothing: a UDP socket "connected" to
172.16.42.1 says whether this computer has a route there (an address on the
cable's network), which sends no packet. Only when that appears is the
phone's SSH asked for its host key, once, which says which phone it is.

## The helper

C, against the phone's own libraries (libwayland-client, GStreamer, GLib,
libxkbcommon), 53 KB, built in Docker and embedded in the app with
`include_bytes!`; pmos.rs puts it in `~/.local/lib/openandroiddex/` when its
SHA-256 differs from the one there. Nothing needs root.

- **KWin's virtual output**: `zkde_screencast_unstable_v1.stream_virtual_output`.
  KWin 6.8 makes the output's mode exactly the width and height asked for, in
  pixels, at 60 Hz (`DrmVirtualOutput`), whatever the protocol's "logical"
  says. It then gives the output the scale it remembers for that name
  (`kwinoutputconfig.json`), the first computer's for every computer after,
  so the helper sets the scale it was asked for with `kscreen-doctor` when it
  differs, and KWin remembers that instead. Checked both ways (1.0 → 1.25 and
  back).
- **Who may use it.** Older KWin releases hand the virtual output and fake
  input only to a program whose desktop file names them by its own path
  (`X-KDE-Wayland-Interfaces`); pmos.rs installs one. KWin 6.8 offered both to
  the helper before that file existed.
- **The keyboard is the phone's.** Fake input version 6 can type a keysym
  itself (`keyboard_keysym`), but KWin applies the Shift it adds one event
  late: "Hello, DeX!" came out "hEllo, dEx!". The helper instead reads the
  seat's keymap, finds the key and level for each keysym on the phone's
  layout, and presses Shift or AltGr around it itself, as Xvnc does for the
  phone's Linux: the window's keysyms were written for exactly that. A key
  held on the computer repeats there, and each repeat is a fresh press here,
  modifiers and all. A character the phone's layout cannot type at all falls
  back to KWin's keysym typing.
- **The pointer is this computer's.** The stream has no cursor in it
  (`pointer hidden`), so the pointer moves at this screen's rate. Plasma's own
  cursor shapes (a text caret, a resize arrow) are not shown; the Android
  desktop's window is the same.
- **The wheel.** The window sends two wheel clicks for each notch of this
  computer's wheel, and the helper gives KWin half a notch for each click (an
  axis value of 7.5, where libinput gives 15 for a real notch), meaning one
  notch here to be one notch there. Measured on 2026-11-14: three notches
  scrolled QMLKonsole 13 lines, about 4 a notch, where Windows scrolls 3. Not
  compared with a mouse plugged into the phone.
- **Pacing.** Frames go into the encoder only while a window is watching and
  fewer than four are unacknowledged, as LinuxStream.java paces the Linux
  desktop. KWin sends a frame only when the screen changes, so the last one is
  sent again every 250 ms while it is still, for a key frame asked for on a
  still desktop and for the encoder's second, sharper pass.
- **Awake while it is used.** The helper holds the session's
  `org.freedesktop.ScreenSaver` and `org.freedesktop.PowerManagement.Inhibit`
  inhibitions, so the phone does not dim, lock or sleep under someone typing on
  the computer; they go with its D-Bus connection however it ends.
- **The computer gone.** No word from the computer for fifteen seconds and the
  helper closes the screen: a cable pulled takes the USB network with it, and
  sshd may not notice for hours. pmos.rs sends a heartbeat every two seconds,
  and its SSH keepalive (every two seconds, three missed) ends the session on
  this side.

## Numbers

Encoders on the Redmi Note 7 (kernel 7.0.14-sdm660):

| | |
| --- | --- |
| The Venus hardware encoder (`/dev/video1`) | will not start: "Encoder on device /dev/video1 has no supported input format", as root too |
| x264 ultrafast/zerolatency, 1920×1080 test pattern, 4 threads | 22 ms a frame |
| the same, 8 threads | 18 ms a frame |
| videoconvert BGRx → I420, 1920×1080, 4 threads | 12.5 ms a frame |

Frame rate while a window is dragged around the desktop, measured at the
window:

| Screen | Conversion | Frames a second |
| --- | --- | --- |
| 2544×1336 (this computer's screen, uncapped) | CPU | 13–15 (another workload was running on the phone) |
| 1920×1008 | CPU | 17–20 |
| 1920×1008 | GPU: DMA-BUF from KWin, `glcolorconvert`, `gldownload` | 11–13 |

So the screen is capped at 1920 pixels on its long side (`CAP`), the window
shows it larger (`smooth`: drawn with filtering, not pixelated), and Plasma's
scale is this screen's times the shrink but never below 1, since a desktop
drawn smaller than its own pixels and then enlarged blurs twice. The GPU path
is there (`--convert gl`) but not used: the download on this Adreno costs more
than KWin's own readback plus `videoconvert`.

From the app seeing the phone on the cable to the first frame in the window:
about one second, including putting a new helper on the phone. A first frame
after the window says hello: 45–115 ms.

## Things to know

- The first time a computer's screen appears, KScreen offers its "new display"
  picker over it, with "Only use external screen" selected (choosing that would
  switch the phone's own screen off until the session ends). It was up five
  seconds into the first session. By then KWin had remembered the setup, and
  fourteen seconds into the next session there was no picker.
- Windows opened on the computer's screen leave it when the session ends; in
  the next session, KWin had put them back on it.
- After a restart the phone is locked, and the window opens on its lock
  screen. It unlocks from there: on 2026-11-14 someone at this computer
  swiped it up in the window, three seconds after the window's input began,
  and the phone logged a successful unlock within the next sixteen (the app's
  log and the phone's journal line up). logind's `LockedHint` said "no" while
  it was locked, so it can't tell whether the phone is locked; the session's
  `org.freedesktop.ScreenSaver.GetActive` answered correctly when tried the
  same day.
- Not here yet: the clipboard, files dropped on the window, sound, the Android
  flow's Wi-Fi. A hardware encoder (Venus) would lift the frame rate; on this
  phone its driver needs work first.

## Following the window

The screen follows the window as DeX's does: the window says its size in
its own logical pixels (`resize`, which `UsbDesktop` already sends the
phone's Linux, and in `hello`), and the helper makes the screen that many
logical pixels at the session's scale, at most `--cap` pixels on its long
side (pmos.rs passes `CAP`, 1920). Plasma gets more room as the window
grows, not a bigger picture of the same room. Widths are whole multiples of
8 (KWin's custom modes are CVT modes, which round to that), heights even.
pmos.rs makes the first screen with the same sums, so a window that opens at
the size it was made for asks for nothing.

How, in two halves, both needed:

1. The mode, for Plasma. KWin gives a virtual output a custom mode as it
   would a monitor: `kscreen-doctor
   output.Virtual-OpenAndroidDeX.addCustomMode.W.H.60000.full`, then
   `mode.WxH@60`, on the helper's sizer thread (each run is a Qt start on the
   phone, 350 to 720 ms in all). Plasma lays the screen out at the new size,
   as for a monitor's new resolution.
2. The stream, for the picture. KWin 6.8 does not resize a virtual output's
   stream with its mode. Such an output can be resized
   (`DrmVirtualOutput::canResize`), so its stream offers any size from
   200 × 200 to 10000 × 10000, and KWin sizes the output by what the stream
   is agreed at, never the stream by the output
   (`ScreenCastStream::onStreamParamChanged`, `OutputScreenCastSource::resize`).
   So once the mode is in, the helper builds its pipeline again with the new
   size on a `capsfilter` after `pipewiresrc`; KWin finds the output already
   that size and leaves the mode alone. `x264enc` starts with a key frame and
   `on_sample` sends the window a new `video` message first, as the phone's
   Linux does after a resize.

Each half was tried alone on 2026-11-14 first. The mode alone (138e0cb as
written) changed Plasma's layout, but the stream stayed at its first size
with the new screen squeezed into it: 1488×858 into 1488×848, black bars in
the window. The stream alone made KWin resize the output, but Plasma never
laid the new size out (a 1496×862 layout cropped to 1100×700, 15 s later).
The stream was first renegotiated in place: the new size's first frame then
waited 11 s for the screen's next change. Taking the pipeline to READY and
back brought the first frame and then nothing for 7 s, whatever the screen
did. A new pipeline, as at the start, streams at once.

KWin keeps custom modes, and the mode in use, in `kwinoutputconfig.json`
under the output's name, and hands them to the next screen of that name. So
the helper puts the first mode back and removes its custom modes before the
screen closes, and tidies leftovers from a session that died as it starts.
pmos.rs waits up to four seconds for the helper to end after asking it to
stop, so that it is heard doing so.

What a kill does, checked on 2026-11-14: `kill -9` of the helper while the
screen was 1000×640 left `kwinoutputconfig.json` holding the 1000×640 custom
mode, as the mode in use and in `customModes`. The next screen of that name
came up at 1000×640, not the 1496×862 asked for: the helper's scale check,
which then divided the size asked for by the screen's width, read 1.496 and
waited out its three seconds. It now reads the mode in use. The start-up
tidy put 1496×862 back and removed the custom mode before the first frame
(`size: 1496x862 in 426 ms`); the first `video` line said 1496x862, Plasma
laid the screen out at that size, and `kwinoutputconfig.json` was clean.

### Results

On 2026-11-14, a Redmi Note 7 (postmarketOS edge, KWin 6.8.2, kscreen
6.8.3, Plasma Mobile's home screen), unlocked, reached over Wi-Fi with the
test switch below (its cable had been taken out). The app ran in development
on Windows 11, on a 1512 × 949 screen at 100 %. The window was resized
through Windows (`SetWindowPos`); F11 was sent into the window's page.

| Window | Screen | Mode | First frame of it | Window shows it |
| --- | --- | --- | --- | --- |
| 1100 × 700 | 1104 × 700 | 677 ms | 851 ms | 1.30 s |
| 1400 × 600 | 1400 × 600 | 640 ms | 828 ms | 1.26 s |
| 800 × 860 | 800 × 860 | 717 ms | 899 ms | 1.42 s |
| 1496 × 862, the first size | 1496 × 862 | 352 ms | 560 ms | 1.01 s |
| F11, 1512 × 949 | 1512 × 948 | 682 ms | 914 ms | 1.56 s |
| F11 again, 1496 × 862 | 1496 × 862 | 362 ms | 605 ms | 1.15 s |

"Mode" is the sizer's `size: WxH in N ms`; "first frame" is from the
helper hearing of the size (`video: now WxH, N ms after the window asked`);
"window shows it" is from Windows resizing the window (for F11, from the
key) to the window's own `remote: desktop video` line, and includes the
window's 400 ms wait for a resize to settle. At every size Plasma's home screen was laid out anew, with
no black bars but one row at each edge where the width or height had to be
rounded (1100 → 1104, 949 → 948). After each size, a swipe up opened the
app drawer, a click put the cursor in its search field (at the clicked
character, once), and a word typed into it found the app: Settings,
Terminal, Camera, Clock, Weather, Recorder. That input was injected into
the window's own handlers over the DevTools protocol, not a real mouse or
keyboard. At 1200 × 760, `kscreen-doctor -o` listed one custom mode beside
the first; closing the window logged `size: back to 1496x862` half a second
later and left `kwinoutputconfig.json` with no custom modes. The table's
first row is from the final helper; the others from the build before its
scale check was fixed, which does not touch resizing.

Not tested: a window border dragged with a mouse (a stream of sizes; the
window waits 400 ms and the sizer takes the latest), a scale other than 1,
the GPU conversion path (`--convert gl`) with the `capsfilter`, resizing over
the cable rather than Wi-Fi, Plasma's desktop shell rather than Plasma
Mobile's, any other phone or KWin version. The lock screen lays itself out
again late, or not at all while the screen grows; once unlocked, Plasma
follows.

### On a Galaxy S24

On 2026-11-15, a Galaxy S24 (Exynos 2400, postmarketOS edge, KWin 6.8.3,
Plasma Mobile 6.8.3 in docked mode) on the cable, through the app in
development and a new identity: sign-in, then the window opened at
1496 × 862 with KScreen's picker, which went by itself. From the window:
the app drawer, apps started, typing in a terminal, Alt+F4, Meta+arrows
(halves side by side), Meta+PageDown, and KWin's title bar buttons on a KDE
app (minimize, maximize, restore, close). A minimized window came back from
its icon in the drawer. The phone's power key turned its own screen off and on
with the window open; the session locked, and the PIN typed into the window
unlocked it. Disconnect left the phone with its own screen only.

- Plasma Mobile has no taskbar on the computer's screen: the strip at the
  bottom is the home screen's dock of favourites. Running apps show a dot
  in the drawer.
- GNOME (GTK) apps draw their own title bars, with the buttons GNOME's
  `button-layout` lists. postmarketOS' Phosh setup leaves it at `'appmenu:'`
  (no buttons). On the S24, an autostart per session sets
  `'appmenu:minimize,maximize,close'` in Plasma and puts `'appmenu:'` back in
  Phosh.
- The S24 needed work on the phone, outside this repository, before KWin
  showed anything on its own screen (a KWin shim for its display's buffer
  formats). The window's pill had a bug the S24 found; see usb-desktop.md.

## Phosh

Phosh's compositor, phoc, is wlroots, and has no virtual outputs to ask for.
What it has, written into phoc itself, is wlroots' headless backend: a session
started with `WLR_BACKENDS=drm,libinput,headless` has an output `HEADLESS-1`
beside the phone's panel, that no screen shows. The helper sizes that output
for this computer, moves Phosh's shell onto it, copies it, and drives it.

**Turning it on, per user, no root.** `phosh-session` sources the user's
`~/.phoshdebug` before it starts phoc. The first time the helper meets Phosh
without a headless output it appends a marked block there that adds `headless`
to `WLR_BACKENDS`, and an autostart entry
(`~/.config/autostart/com.ccrstech.openandroiddex.pmos-rest.desktop`) that runs
`openandroiddex-pmos rest` at each login, which turns the output off until a
computer asks for it. The window then says to log out on the phone once. The
helper adds the block again only if its marked line is gone, and rewrites the
autostart entry whenever it differs (it names the helper's own path).

**The shell.** Phosh draws its bar, app grid, lock screen and notifications on
its primary screen only, and the wallpaper on the others. So the helper makes
the headless output primary through Phosh's DisplayConfig (Mutter's D-Bus API,
which Phosh implements), and gives the panel the primary back at the end.
Things that cost time:

- Only a *persistent* config (method 2) moves Phosh's primary; a temporary one
  is accepted and the primary flag ignored. Phosh keeps it in memory and saves
  no file, so nothing of it survives a login.
- Phosh refuses any change while its lock screen is up ("Can't configure
  outputs when locked"). The helper waits up to 25 s for the phone to be
  unlocked, then fails with "The phone is locked. Unlock it…". GNOME's
  `ScreenSaver.GetActive` said false on Phosh's lock screen; logind's
  `LockedHint` for the user's seat0 session was right, so that is asked first.
- Turned on and made primary in one config, the new screen's wallpaper came out
  zoomed into a portrait crop: Phosh sizes the primary screen's wallpaper from
  its "usable area", not yet the new screen's. Turned on beside the panel,
  sized, and then made primary in a second config, it is drawn right.

**phoc dies on a re-enabled headless output, unless.** wlroots 0.19's
output management asserts (`head_send_state: found`) when a head with a custom
mode is turned on while a client is bound that has no mode object for it, which
is every client that bound while it was off, as long as another client (Phosh)
still holds one from before. phoc aborts and the session ends: it happened
twice on the S24 before the cause was found, in a private phoc with Phosh
reproducing it on demand. So the helper lets go of output management, has
Phosh turn the screen on (DisplayConfig, leaving it out of nothing), waits for
its `wl_output`, and binds output management again before sizing it. Any other
program bound to output management while the screen is off and turned on would
still bring phoc down (none runs in a stock Phosh session; not tried with Phosh
Mobile Settings open).

**Putting the phone back, even if the helper dies.** A primary screen nobody
can see would leave the phone's own panel with nothing but its wallpaper. The
helper puts the panel back and turns the output off as it ends (Phosh's
DisplayConfig, which turns off what it is not given; wlr-output-management if
Phosh refuses), and starts a guard of its own (`openandroiddex-pmos guard`, a
fresh exec in its own session holding the read end of a pipe) that runs `rest`
when the pipe closes, however the helper ended. Its log is
`~/.cache/openandroiddex/guard.log`. Checked: `kill -9` of the helper put the
panel back within the second; the app closed, restarted by `tauri dev`, or
Disconnect all left the phone as it was.

**The picture and input.** wlr-screencopy into a `wl_shm` buffer of the
helper's (XBGR8888 on the S24, `RGBx` to GStreamer), copied row by row into an
`appsrc`, then the same encoder as KWin's. A frame is copied only when the
screen changes (`copy_with_damage`), and the last one goes again every 250 ms
while it is still. A new size rebuilds the pipeline, as for KWin. Input: a
`zwlr_virtual_pointer_v1` tied to the output (absolute moves in the frame's
pixels), and a `zwp_virtual_keyboard_v1` given the seat's own keymap, with
modifiers sent by the helper (wlroots does not update them for a virtual
keyboard). Two of the window's wheel clicks make one notch, wlroots counting
whole notches.

**The pointer.** A headless output has no cursor plane, so phoc draws its
pointer into the picture whatever screencopy is told. The helper sends the
window a cursor of no size, which hides this computer's pointer over the
picture, and the one seen is phoc's: it follows the app's shapes (a text caret
over a terminal), and moves at the picture's rate rather than this screen's.

**Windows.** Apps opened from the computer's screen open there, floating with
title bars. When the session ends phoc moves them to the panel, and they stay
there when a computer opens the desktop again: Phosh's overview on the
computer's screen lists them, and choosing one raises it on the phone.

### Results

On 2026-11-15, the Galaxy S24 (postmarketOS edge, Phosh and phoc 0.58.0,
GStreamer 1.28.7, x264), on its cable (172.16.42.1) and before that through
`OADX_PMOS_ADDRESS`, the app in development on Windows 11 under a test
identity. Input went into the window's own handlers over the DevTools protocol
(`Input.dispatchMouseEvent`/`dispatchKeyEvent`), not a real mouse or keyboard;
the phone was logged in and unlocked with its PIN through a uinput keyboard on
the phone.

- Plug-in with the phone signed in to: found at app start, and the desktop
  opened by itself. With the phone at its greeter (nobody logged in) that
  failed with "Couldn't reach the phone's desktop (Wayland wayland-0 in
  /run/user/10000)"; the helper now says "Nobody is logged in on the phone
  yet…" instead (not re-run). Logged in, "Try again" to the first frame in the
  window: 1.5 s.
- The phone locked: the helper said it was waiting; the PIN's Enter came 10 s
  later, the screen was on 0.8 s after it, and its first frame in the window
  1.1 s after that.
- Through the window: a click on the home bar opened the app grid, typing
  "console" in its search and Enter opened Console on the computer's screen,
  `echo Hello from the PC` typed and ran (capitals and all), and three wheel
  notches scrolled its output nine lines. Calculator, Text Editor ("Hello,
  DeX!") the same way through the test driver.
- The window resized through Windows (`SetWindowPos`) to 1100 × 700: the screen
  was 1088 × 660 39 ms after the window asked, its first frame 246 ms after, and
  Phosh laid it out anew.
- A still screen: 4 frames a second (the 250 ms resend); the app grid opening,
  13; 4–6 ms round trip on the cable.

Not tested: a real mouse or keyboard on the window, a scale other than 1, any
other Phosh phone or Phosh version, Phosh's own docked mode, Plasma again after
this change (its code path is the same, but was not re-run on the Redmi), and
a cable pulled.

## Testing over Wi-Fi

`OADX_PMOS_ADDRESS=<address>` (an IP or a name such as `redmi-note-7.local`)
in the app's environment looks for the phone there instead of on the cable,
for a computer the phone is not plugged into; the phone counts as there
while its SSH answers. It is a test switch, not the Wi-Fi the Android flow
has: there is no discovery, and signing in over Wi-Fi trusts the network for
the first host key. postmarketOS's sshd listens on Wi-Fi too, and the phone
announces `_ssh._tcp` over mDNS under its hostname.

On 2026-11-14 the app on a Mac (macOS 26, M1 Pro, Retina 3024×1964) used it
with the same Redmi on Wi-Fi: found 0.15–0.6 s after the app started; signed
in (typed by a person); the desktop opened by itself after each app restart,
1.5–1.7 s from the app starting to the first frame; a helper from the
killed app ended on the phone. The key file had been written readable by
everyone (0644), which `ssh -i` refuses, and its comment carried no computer
name on macOS (no `HOSTNAME` in an app's environment): both fixed.
