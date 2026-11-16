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
| The phone's helper | `openandroiddex-pmos/src/main.c` | the virtual output (KWin) or headless output (Phosh), the encoder, fake or virtual input, the phone's keymap, keeping the phone awake, putting a Phosh phone back, the phone's end of the clipboard, dropped files into Downloads and onto the app under them |
| KWin's script | `openandroiddex-pmos/src/here.js` | apps opened from a screen showing on that screen (KWin), see [Apps open where they are opened](#apps-open-where-they-are-opened) |
| Building it | `openandroiddex-pmos/build.sh`, `cstring.sh`, `Dockerfile` | an x86_64 Alpine with clang and an aarch64 Alpine sysroot; here.js embedded as a C string; the binary goes to `prebuilt/` |
| The computer's end | `src-tauri/src/pmos.rs` | which phone is on the cable, signing in, putting the helper there, the session, the window's WebSocket, a drop's copy (`pmos_put`) |
| The Desktop tab | `src/features/pmos/usePmos.ts`, `DesktopPage.tsx` (`pmosContent`, `PmosAlso`), `PmosSignIn.tsx` | the phone's own flow beside the Android one, and its card on the Android screens |
| The Linux tab | `src/features/pmos/PmosLinuxCard.tsx` | the same phone: sign in, Open desktop |
| The window | `src/features/pmos/PmosDesktop.tsx` | `UsbDesktop` (usb-desktop.md) pointed at pmos.rs, with the clipboard (`clipboardShare.ts`) and drops (`DesktopCards.tsx`'s cards) as the phone's Linux window has them |

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

### The Galaxy S24's cores

On the S24 (Exynos 2400: four 1.96 GHz cores, five at 2.59–2.90 GHz, one at
3.21 GHz) the encoder was the limit, and not for want of speed. Left to the
scheduler, x264 ran on the slow cores. Measured on 2026-11-16 in GStreamer
(Python) on the phone, 1920×1020, moving test patterns, frames a second for
the whole pipeline including the test source:

| | Any core (as before) | Slow cores only | Fast cores only |
| --- | --- | --- | --- |
| x264 ultrafast, 4 sliced threads (as before), moving ball | 18.9 | 12.2 | 47.4 |
| x264 ultrafast, 1 thread, moving ball | | 10.7 | 79.6 (81.5 on the 3.21 GHz core alone) |
| x264 1 thread / 4 sliced threads, moving bars | | | 109.4 / 85.2 |
| x264 1 thread / 4 sliced threads, moving zone plate | | | 65.7 / 26.3 |
| x264 1 thread / 4 sliced threads, noise | | | 56.3 / 31.7 |
| `videoconvert` BGRx → I420, 1 thread / 4 threads, moving ball | 115.0 / 35.9 | | |
| BGRx → NV12 on the CPU / on the GPU (`glcolorconvert` through zink), moving bars | | | 205.8 / 57.8 |

Without the test source's own time, one x264 thread on a fast core encodes
1920×1020 at about 130 frames a second. Sliced threads lose, probably because
each slice keeps its core only partly busy and the clocks don't rise (the
clocks were not watched during the runs). So since
helper 4 the program runs on all but the slowest cores (`prefer_fast_cores`:
those whose top clock is the phone's lowest, when there are cores of more
than one kind), and x264 has one thread. The GPU doesn't encode here: the
Exynos 2400 encodes video in its MFC block, whose driver this postmarketOS
port doesn't load (no MFC device among `/dev/video*`, which are all the
camera's), and converting on the GPU through zink was slower than the CPU.
The helper logs what it sent every ten seconds while the screen moves
(`video: N frames a second over 10 s`).

End to end, in a nested KWin on the S24 (`kwin_wayland --virtual` drawing
through zink like the session's, the helper's screen 1920×1020, a window
showing a detailed web page dragged across it by a KWin script every 16 ms,
20 s each, frames counted at the computer's end):

| Helper | Frames a second (lowest to highest second) |
| --- | --- |
| before (4 sliced threads, any core) | 32.8 (3–38) |
| fast cores, 1 thread | 30.4 (3–38) |
| the same with `--convert gl` (KWin's frames as DMA-BUF, converted on the GPU) | 29.1 (4–37) |

All three stop at about 37: in that nested KWin the frames themselves come no
faster, so this test can't show the encoder's gain, and the GPU path brought
none there. Not measured yet: the real session's frame rate with the new
helper (the person was using the phone).

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
- Not here yet: sound, the Android flow's Wi-Fi, files from the phone to this
  computer. A hardware encoder (Venus) would lift the frame rate; on this
  phone its driver needs work first. The clipboard and files dropped on the
  window came with helper 5: see
  [The clipboard and dropped files](#the-clipboard-and-dropped-files).

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
- Docked mode was first turned on by hand for this test, and stayed on: after
  Disconnect, the phone's own screen had its apps as windows with title bars
  too. Since helper 3, the helper turns docked mode on for the session and puts
  back what the phone had: see [Plasma Mobile's docked mode](#plasma-mobiles-docked-mode).

### Plasma Mobile's docked mode

Plasma Mobile puts every app full screen without a title bar, on all its
screens, unless its Docked Mode quick setting is on (`convergenceModeEnabled`
in `plasmamobilerc`'s `[General]`). The shell and KWin follow that file live
when it is written with `kwriteconfig6 --notify`. So once the stream is up the
helper writes it on, and once the computer's screen has gone it writes back
what the phone had. The windows on the computer's screen come to the phone's
screen when it goes, and turn back into phone apps there. Docked mode is one
setting for every screen: while a computer is connected, the phone's own
screen has windows too.

The setting alone is not all of docked mode. Plasma Mobile's own switch
(`MobileShellSettings::setConvergenceModeEnabled`) also runs
`plasma-mobile-envmanager --apply-settings`, which writes KWin's rules for the
mode into `~/.config/plasma-mobile/kwinrc` (in docked mode windows can be
dragged, open centred instead of maximized and have the title bar's buttons;
outside it none of that) and has KWin reload them. The helper wrote only the
setting until 2026-11-16, so on the S24 docked mode was on but KWin kept the
phone's rules: no window could be dragged on the computer's screen
(`InteractiveWindowMoveEnabled=false` while `convergenceModeEnabled=true`).
Now it runs the envmanager after each change, with the session's environment
and `QT_QPA_PLATFORM=offscreen` as `startplasmamobile` does, and also when it
finds docked mode on already. Checked in a nested KWin set up like Plasma
Mobile's: the session coming turned KWin's `interactiveWindowMoveEnabled` on,
its going turned it off and placement back to maximizing. On the S24 the
envmanager run by hand during a session made the windows on the computer's
screen movable (`moveable=true`).

What the phone had is kept in `~/.cache/openandroiddex/plasma-docked-before`
until it is put back. So the guard puts it back when the helper dies, and the
autostart entry's `rest` does at the next login after a restart. A second
session doesn't take the first one's "on" for the phone's own. A phone that
had docked mode on already is left alone.

Checked on the S24 on 2026-11-15 (helper 3, through the app):
- The window opening turned docked mode on ("the phone had it off").
- Disconnect put it back off, and the kept file went.
- A kill -9 of the helper: the guard put it back off within the same second.
  The window then reconnected by itself, which turned it on again, as it
  should while the window is open.
- `rest` with a kept "off" and no session, run by hand: it wrote it back and
  removed the file.

The restart case (`rest` from the autostart at login) was not tried.

More on the S24 the same evening, through the app (helper 3):
- The window resized through Windows (`SetWindowPos`, no input sent). At
  1280 × 800 the screen became 1264 × 760 in 716 ms, and the window had its
  picture 847 ms after it asked. At 1000 × 700 it became 984 × 660 in
  716 ms, with the picture after 830 ms. Plasma laid the home screen out anew
  each time.
- This computer's real mouse and keyboard (`SendInput`, after checking the
  PC had been idle for 5 minutes): a click on the home screen's handle opened
  the app drawer, and "term" typed into its search left Terminal. No other
  input came in between.
- A taskbar for the computer's screen: a desktop panel (`org.kde.panel`
  from plasma-desktop, with the task list, tray and clock) can be added to
  that screen alone through plasmashell's scripting. The task list showed the
  phone's windows and minimized and restored them. Its app menus (Kickoff,
  Kicker) never opened. The first Disconnect after it was added ended
  plasmashell: the phone was left without its home screen and bars until the
  shell was started again. Earlier disconnects without the panel never did
  that. So the panel was taken off again, and Plasma Mobile has no taskbar on
  the computer's screen.

### Apps open where they are opened

Plasma Mobile's app drawer and dock switch to an app's window when the app
has one, on whichever screen that window is
(`WindowUtil::activateWindowByStorageId`: the app's first window, matched by
its app id plus `.desktop`). With the computer's screen beside the phone's,
Brave, Calls and VLC tapped on the computer's screen came up on the phone's
(found on the S24 on 2026-11-16). Firefox opened on the computer's screen only
because its window's id (`firefox-esr`) doesn't match its desktop file
(`org.mozilla.firefox-esr`), so the drawer started it again and KWin placed the
new window on the screen in use.

So while the computer's screen is up, KWin runs a script of ours, `here.js`
(the helper writes it to `~/.cache/openandroiddex/kwin-here.js` and loads it
through `org.kde.kwin.Scripting`; it is unloaded before the screen closes, and
by the guard's `rest` if the helper dies). When KWin activates a window on one
screen for something done on the other, the window comes to the screen it was
asked from:

- From the home screen (the drawer, the dock, a folder): the app's own window
  on that screen if it has one there; else a new window, if the app's desktop
  file has a new-window action (`new-window`, `NewWindow`, `new-empty-window`;
  not for `SingleMainWindow` apps); else that window, moved. Brave and
  Chromium get a new window, as Firefox did; Calls and VLC, which have no such
  action, are moved.
- Any other way (the task switcher, a notification, a link opened from an app
  on that screen): that window, moved.
- An app whose new window doesn't come within 8 seconds has its old window
  moved, unless something else was done meanwhile.

It works in both directions: an app tapped on the phone whose window is on the
computer's screen comes to the phone the same way.

"Where something was done" needs two signs, because KWin's script API can't
see input: `windowActivated` comes after KWin has already made the window's
screen the active one (`Workspace::setActiveWindow`), and a touch moves no
pointer. The first is the window active just before: a click or a touch
activates the window under it, the home screen too (checked: the home screen
is a desktop window that takes the focus, and the drawer opens inside it, not
as a window of its own). The second is the helper's: it asks how long ago the
computer last pressed a key or a mouse button. The two have to agree, a press
within 1.5 s for the computer's screen and none for the phone's, or nothing
happens. That keeps KWin's own choices (the next window after one was
minimized or closed, whose "window before" is the minimized one, also
excluded) and a tap on the phone right after a click on the computer from
moving anything. On the computer's screen, "from the home screen" also needs
the pointer to be over the home screen: a notification popup takes no focus,
so the home screen can be the window before it.

The helper answers on the session bus as `com.ccrstech.openandroiddex.Pmos`
(`Look`, `NewWindow`, `Note`), to KWin's own connection only. A new window is
the desktop file's action: over D-Bus (`org.freedesktop.Application.
ActivateAction`) for a `DBusActivatable` app, else its `Exec` run with the
session's environment, in a process session of its own, with nothing on the
helper's stdout (the computer's stream; GLib's own launcher would hand it to
the app). The script's decisions go to the helper's log as `here:` lines.

What cost time to learn (KWin 6.8.3):

- **A window that keeps its size jumps back.** `sendToOutput` moves it at once,
  but a configure KWin sent the app just before (for the activation) still
  holds the old place, and when the app answers it KWin puts the window back
  there (`XdgToplevelWindow::handleRoleCommit`; only an interactive move
  updates configures on their way). Five of six same-size moves bounced in
  testing. The script sends the window again whenever it lands back on its
  old screen within two seconds; all six then stayed. The first moves tried,
  of maximized windows (which change size between the two screens), stayed
  without that.
- **`moveableAcrossScreens` is false outside docked mode**: it follows KWin's
  `InteractiveWindowMoveEnabled`, which Plasma Mobile's envmanager turns off
  in mobile mode. Sending a window to a screen doesn't need it, so the script
  doesn't ask.
- **`Scripting.start()` applies every script setting**, as the settings page
  does, so the helper runs its own script by its object, `/Scripting/ScriptN`.
  KWin numbers scripts by how many it holds, so after an unload the number can
  be one in use; the helper looks at `/Scripting`'s children first and uses
  `start()` only then.
- **Two shells.** The helper takes the session from the first `plasmashell` it
  finds, and a nested test session's shell started over SSH did as well as the
  real one (another session's test hit this on the S24). It now prefers a
  process whose logind session has a seat. On the S24 the real `plasmashell`
  had been restarted with `--replace` and belonged to no session, and SSH
  logins there get none: kded6, ksmserver and KWin were in the seated session
  `c3`, so the session comes from kded6.

Checked on 2026-11-16 on the S24, in a private nested session (`kwin_wayland
--virtual` on its own bus and settings, Plasma Mobile's real shell in it on
both screens, the test helper making the computer's screen, clicks through the
helper's own fake input, three test apps; nothing of the phone's session was
used or shown):

| Case | Result |
| --- | --- |
| Multi-window app open on the phone, its icon clicked on the computer's screen | its new-window action ran; a new window on the computer's screen, active; the phone kept its own |
| Single-window app (no action) | moved to the computer's screen, active |
| App with a window on both screens, clicked again there | its window there came back from minimized; nothing moved or opened |
| `DBusActivatable` app | `ActivateAction("new-window")`; a new window on the computer's screen |
| Phone home screen, app only on the computer's screen | moved to the phone; with a window on both, the phone's (the other minimized again) |
| A click on the computer, then a touch on the phone 277 ms later | nothing moved: the two signs disagreed |
| A window on the computer's screen asking for a phone window | moved (not a new window): not from the home screen |
| Home screen active, pointer over another window | moved (not a new window) |
| A new-window action that opens nothing | the old window moved after 8 s |
| A window minimized on the computer's screen | KWin activated the next window; nothing moved |
| Helper stopped / killed (`kill -9`) | the script unloaded / the guard's `rest` unloaded it |

From a click on a drawer icon to the script asking the helper: 95–150 ms.
Not tested: the phone's real session with Brave, Calls and VLC (the person
was using the phone), a real touch on the phone's screen (stood in for by
activating its home screen), the task switcher, a notification popup. Phosh
was not looked at: the script is KWin's.

### The on-screen keyboard after a session

On the S24 on 2026-11-16, after sessions on the computer's screen, the phone's
on-screen keyboard (plasma-keyboard, KWin's input method) came up as a strip
about 160 px wide in the middle of the 393 px screen, showing only "q w e r",
and stayed that way: nothing on the phone reset it. A new keyboard process
was right again. plasma-keyboard sizes its window and keys from its window's
screen (`Screen.width`, `Screen.height`; the keys are 30% of the screen's
height, the panel at most three times that wide, a phone layout under
500 px) and tells KWin its keys' rectangle; KWin takes that rectangle as the
keyboard's size and centres it on the active screen
(`InputPanelV1Window::resetPosition`). In a nested KWin with plasma-keyboard,
it kept the size of the screen it was first laid out for when KWin showed it
on the other (a phone-sized keyboard on a 1080 px screen), but the strip
itself was not reproduced, so how the phone's got that way is not known.

So when the computer's screen has gone, the helper ends the keyboard process
with SIGKILL, and KWin starts a new one at once, laid out for the phone: KWin
starts its input method again whenever it crashes, and gives up only after
five crashes each within 20 s of the last (`InputMethod::startInputMethod`,
`m_inputMethodCrashTimer`). The process is found as KWin's child with the
variable KWin gives its input method alone (`MALIIT_ENABLE_ANIMATIONS`), so
any keyboard KWin runs is restarted the same way. SIGTERM won't do:
plasma-keyboard ends cleanly then, and KWin leaves a clean end be (seen on the
S24). A first version cleared and restored `[Wayland] InputMethod` in kwinrc
instead; when the setting came only from a system default, `kwriteconfig6
--delete` left `InputMethod[$d]`, which hid the default, and KWin had no
keyboard at all. This way touches no setting. The guard restarts the keyboard
if serve died, unless serve said it had (a `k` on the guard's pipe).

Checked on 2026-11-16 in a nested KWin with plasma-keyboard on the S24: serve
ending (keyboard 27439 → 27539, one restart, the guard didn't add one); serve
killed (`kill -9`; the guard's `rest` unloaded the KWin script and the
keyboard went 30951 → 31118); `[Wayland]` in the private kwinrc unchanged
both times. Not done during a session: the keyboard moving between the two
screens mid-session was not reproduced, and the real session's keyboard has
not yet been restarted this way.

## The clipboard and dropped files

Since helper 5 the window shares the clipboard with the phone, and files
dropped on it go to the phone, as the phone's Linux window does both
(usb-desktop.md, "The window"): the same rules and the same switch on the pill
(`clipboardShare.ts`, the phone being the guest), and the same cards in the
corner (`DesktopCards.tsx`).

### The clipboard

The window's `clip` messages carry text both ways, as for the phone's Linux;
the helper is the phone's end. A Wayland program sees the clipboard only while
it has the keyboard, which the helper never has, so it uses data-control, the
protocol clipboard managers use: the seat's clipboard at any time, and a way
to set it. KWin 6.8 offers the standard one (`ext_data_control_manager_v1`),
wlroots its older `zwlr_data_control_manager_v1` as well; the helper takes
whichever there is, and says so in its `ready` (`"clipboard":true`). A phone
without either shows no switch on the pill.

- **Text only**, in the types toolkits ask for (`text/plain;charset=utf-8`,
  `UTF8_STRING`, `text/plain`, `TEXT`, `STRING`). A picture or files on the
  phone's clipboard stay there.
- **No echo.** The computer's text carries a type of the helper's own beside
  those (`application/x-openandroiddex-pmos`), so when the phone says its
  clipboard is now that, it isn't sent back.
- **Only what changes, only to a window.** The phone's text goes once a
  window is open and when it changes: a window that opens hands the
  computer's clipboard over (when it is in front), and the phone's older one
  mustn't overwrite it.
- **Off means nothing leaves the phone.** The window tells the helper where
  its switch is (`{"t":"clipshare","on":…}`) once it shows the picture and
  whenever the switch moves, and until it says on, the helper neither reads
  nor sends what is copied on the phone. Off also takes the computer's text
  off the phone's clipboard, if it is still there; something copied on the
  phone since is left alone. (Until a review on 2026-11-16 found it, the
  phone's text still crossed to this computer with the switch off, and only
  the window threw it away. The phone's Linux, LinuxStream, ignores the word
  and still sends its clipboard; the window drops it there as before.)
- **Only into a window in front.** What the phone copies goes onto this
  computer's clipboard 150 ms later (a selection being dragged out announces
  itself many times), and only if the window is still in front then;
  otherwise it waits for the window to come back, as anything copied while
  it is elsewhere does (`clipboardShare.ts`). Before the same review, a window
  sent to the back within those 150 ms still wrote it, over whatever was
  copied in the app now in front; the fix serves the Linux window too.
- **Sizes.** The window gives the phone up to 1 MiB at once (the helper takes
  a message of up to 4 MiB, and JSON's escapes can double a text); the helper
  sends up to 4 MiB, the window's limit for what comes in.
- The computer's text goes with the helper: the phone's clipboard empties if
  it still held it when the session ended, as Xvnc lets go of a client's.

**KWin's serial rule.** KWin 6.8 gives a data-control selection the next
serial (`display->nextSerial()`) and cancels an app's later copy whose serial
is older (`SeatInterfacePrivate::updateSelection`, `serial <
currentSelectionSerial`). An app copies with the serial of the input that
asked for it (GTK: its latest key, button or touch), so Ctrl+C or a menu click
after the computer's text arrived is newer, and takes the clipboard. In the
nested test, where no input had reached the test app, its copies after the
computer's text carried serial 0 and were all cancelled; with a key sent to it
through the helper first, every one took.

### Files dropped on the window

A drop goes into the phone's Downloads (XDG_DOWNLOAD_DIR), whatever it lands
on, through the helper's `receive`: pmos.rs starts it on a second channel of
the session's own SSH connection, so there is no second login, nothing
listening on the phone, and no SFTP server needed (the S24's dropbear has
none). pmos.rs walks the drop as for the phone's Linux (dexremote's
`sftp::plan`: folders whole, links and unreadable files counted and left) and
sends it in frames (main.c, "Files from the computer"). On the phone:

- **Nothing is replaced.** An item whose name is taken is called
  "name (1).ext", as a browser names a second download ("backup (1).tar.gz",
  "trip (1)"); every file and folder is made with `O_EXCL` or `mkdir`, nothing
  is followed through a link, and paths are checked a part at a time (a `..`
  is refused).
- Times and permission bits come along (owner read and write always).
- **All of an item or none.** One that doesn't finish (Cancel on the card,
  the window or the session closing, a write failing) is taken back whole;
  items that had landed stay.
- Not enough room on the phone is refused before anything is sent.

### Into the app under the drop

Then the helper drops the landed files on the app where they were let go of,
as a file manager would: into a browser's upload, a chat, a terminal (which
types the path). Wayland lets a program start a drag only from a button
pressed on a surface of its own. So the helper puts eight pixels of its own
over everything at that point (a layer-shell surface, overlay layer), moves
its pointer onto them (KWin's fake input; on wlroots, the virtual pointer),
presses the button there, starts a drag carrying the files' `text/uri-list`,
makes its pixels let the pointer through (an empty input region), moves the
pointer a pixel and back so the compositor looks for what is under it, waits
for the app there to accept (up to about a second: a browser asks its page
first), and lets go. Then the pointer goes back where the computer had it.
The card says which happened: "Dropped into the app on …" with "A copy is in
Downloads there", or "Copied to Downloads on …" when nothing there takes
files (the home screen, a window that doesn't). The helper says whether it can
(`"carry":true` in its `ready`); without it a drop only lands in Downloads.

The drop's place is Tauri's: the position of the webview's drop (physical
pixels of the page; `ScreenToClient` on Windows) over the device pixel ratio,
mapped onto the desktop the way the pointer is (`DesktopHost.point`, which the
view hands over while it shows the desktop).

### Speed

The S24's SSH server is dropbear, which gives each channel a receive window of
24 KB (its default `-W`). Over the cable one channel carried about 10 MB/s
whatever this computer did: a file of 100,000,000 bytes took 10.1 s through the
app (the app's log: copying at 16:46:08.646, landed at 16:46:18.747). More
channels of the same connection at once carry more (from this computer with
paramiko, `cat > /dev/null` on the phone, 2026-11-16):

| Channels at once | MiB/s |
| --- | --- |
| 1 | 8.7 |
| 2 | 22.6 |
| 4 | 30.8 |
| 8 | 38.5 |

So a file of 4 MiB (4,194,304 bytes) or more goes in four shares at once,
over four more channels of the session's one SSH connection, each running
`receive --part`, opened at the first such file of a drop and kept for the
rest of it. The main channel makes the file first and says so (`G`, which
answers "opened"), and only then do the shares start: the channels are
separate and nothing orders them, so a share could otherwise reach the phone
before the file it writes into exists. (A review found that gap on
2026-11-16; forced in a test, the share failed with "No such file or
directory". A single file is made when it is named, so only big files inside
a dropped folder were exposed.) Each share writes where it goes (`pwrite`),
then the main channel says the bytes are all there and the helper checks the
size. Through the app, the same 100,000,000 bytes took 5.0 s (copying at
17:01:37.510, landed at 17:01:42.477, before the two small items of the same
drop). Four rather than eight: OpenSSH, which other pmOS phones run, allows
ten sessions on a connection by default (`MaxSessions`), and its 2 MB window
makes one channel fast there anyway.

### Checked

On the S24 on 2026-11-16, in a private nested session as for here.js (its own
KWin, bus and settings, Plasma Mobile's shell, a GTK 4 test app that copies,
pastes and takes dropped files). First with a harness speaking for the
computer:

| Case | Result |
| --- | --- |
| The app copies (em dash, CJK, emoji, a newline) | the same 42 bytes to the computer |
| The computer's text (quotes, backslashes, a tab, ü, an emoji) | the app pasted the same 61 bytes; nothing sent back |
| The switch off (empty text) | the phone's clipboard empty; with the app's newer copy there, left alone |
| No window, then a window again | nothing sent while none, nothing unasked when it came |
| The window's switch: not said yet, on, off, on; a new window not saying yet, then on | sent while on only: 3 of the 6 copies |
| 752,810 bytes both ways | byte for byte |
| Two files, one with a Unicode name, dropped on the app | both URIs taken, 53 ms |
| A folder dropped on the app | taken, 73 ms |
| Dropped on the home screen | "nothing there takes files", after 1.3 s |
| Dropped with a button held on the computer | not done |
| After a drop | the pointer back where the computer had it |
| `receive`: names taken, `.tar.gz`, a folder, Unicode, an empty file | "hello (1).txt", "backup (1).tar.gz", "trip (1)"; contents, times and modes kept |
| `receive`: cancelled, cut off, a `..` | taken back whole, nothing left; refused |
| `receive --part`: 24,129,593 bytes in four shares | byte for byte; a share outside Downloads refused; cancelled mid-way, taken back |
| A share opening its file before the main `receive` made it (forced) | the share fails, the item is taken back; with `G` and "opened" first, 9,437,961 bytes in a folder in four shares, byte for byte |

The window's clipboard rules run off the phone in `test/usb-desktop-test.mjs`
(`clipboardShare.ts`): what the guest copies lands with the window in front;
sent to the back within the 150 ms, it waits, and lands when the window comes
back; a newer copy on this computer wins over it; and the phone's words and
1 MiB limit beside Linux's 256 KiB.

Then through the app: a separate development copy with a test identity, its
helper pointed at the nested session (`OADX_PMOS_ENV`), the drops emitted into
the window as Tauri's own drop events (paths and place). Two files dropped on
the test app went into Downloads byte for byte and reached the app at the very
point (desktop 500, 350, which was 300, 172 in its window, below its title
bar); a folder, a Unicode name and a taken name dropped on the home screen
landed as "Copied to Downloads", "notes (1).txt" among them; Cancel on the card
during 100,000,000 bytes on one channel said "Copy stopped" and left nothing;
with the four shares the same file arrived in 5.0 s with the bar moving. Cancel
during the four shares first left their four `receive --part` waiting on the
phone until the session ended (the channels were dropped, not closed); fixed,
the same cancel at 40 % left none. Disconnect ended the helper, the script and
the nested keyboard, leaving the real session's alone. The person at this
computer used that window too: a PNG they dropped on its home screen arrived
(1,082,942 bytes, a PNG of 1179 × 2556, its date kept; its size and header
were checked, no checksum against the original), and bringing the window to
the front put their clipboard on the phone (the helper logged 2,497 bytes).
These runs were before the two fixes above (the switch, the share order); the
fixes were checked with the harness and the test file, not through the app.

### Not tested

- Phosh: data-control and the layer shell are wlroots' too, but the S24 runs
  Plasma.
- The phone's own session, and its real apps (a browser's upload, a chat):
  only the GTK 4 test app took drops.
- The phone's text landing on this computer's clipboard: the helper sent it,
  but this computer's foreground was a Windows prompt that kept the window
  from coming to the front for the test, and the window holds the phone's
  text until it is in front.
- A drag from Explorer with this computer's mouse, apart from the person's
  one drop.
- The window sending its switch (`clipshare`) through the app: the helper's
  side ran with the harness sending the word; the window's side was
  type-checked only.

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

`OADX_PMOS_ENV=<file on the phone>` makes each of the helper's commands
source that shell file first: a test pointing the helper at another desktop on
the phone than its own session, such as a nested KWin (its `WAYLAND_DISPLAY`,
bus and folders). The checks of the clipboard and drops above ran that way.

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
