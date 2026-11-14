# A postmarketOS phone's desktop, on the Desktop tab

Plug in a phone running postmarketOS with Plasma Mobile and the Desktop tab
opens its desktop in a window, the way it opens DeX for an Android phone. The
desktop is a screen of the phone's own, not a mirror of its display: KWin makes
a virtual output the size of this computer's screen, Plasma fills it as it
would a monitor plugged in (wallpaper, dock, app drawer, windows with title
bars in convergence mode), and the window here shows it and sends it the mouse
and keyboard. The Linux tab has the same phone as a card at its top.

This record exists because several of the choices below look arbitrary until
you know what was measured, on a Redmi Note 7 (Snapdragon 660, postmarketOS
edge, Plasma 6.8, KWin 6.8.2) on 2026-11-13.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The phone's helper | `openandroiddex-pmos/src/main.c` | the virtual output, the encoder, fake input, the phone's keymap, keeping the phone awake |
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
- The window does not resize the phone's screen: it is the size of this
  computer's screen, and the window scales it. KWin's virtual outputs take
  custom modes, so following the window is possible later.
- Not here yet: the clipboard, files dropped on the window, sound, the Android
  flow's Wi-Fi. A hardware encoder (Venus) would lift the frame rate; on this
  phone its driver needs work first.
