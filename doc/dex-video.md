# The desktop's own video — smooth over Wi-Fi

The Android desktop used to reach the computer one way: stock scrcpy, its
server on the phone and its window here. scrcpy sends every frame the phone
composes at a fixed bitrate, however far behind the link is. On a cable that
is invisible. Over Wi-Fi the frames that cannot go yet wait in adb's buffers —
a second of them, measured on the Linux stream before it learned better — and
a window being dragged drags that second behind it.

Now the desktop goes the way the Linux desktop's video goes
([usb-desktop.md](usb-desktop.md)): the window daemon's dex makes the display
and encodes it on the phone's hardware encoder, a frame goes into the encoder
only when the encoder and the link have room for it, the bitrate follows what
gets through, and a still desktop costs nothing. The computer shows it in a
window of this app's own, decoded on its GPU (WebCodecs). A slow link costs
frames, never latency.

scrcpy's window stays: the phone's Settings can pick it (Stream → Desktop
window → Classic), the pointer drawn by the phone needs it, and the desktop
goes there by itself when this path cannot start.

This record exists for the reason the others do: several decisions below look
arbitrary until you know what was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The server | `openandroiddex-wmd`: `Desktop.java` | the display, one process per desktop: its flags, its size, its density, keeping it awake, the socket, who gets in |
| The picture | `DesktopVideo.java` | a texture the display draws into; the GL thread; each encoder, drawn into one frame at a time |
| One computer | `DesktopSession.java` | the WebSocket, the pace, the bitrate, the numbers, the computer's input |
| Input | `DesktopInput.java` | the mouse, the wheel, keys and text, injected into the display |
| The clipboard | `DesktopClipboard.java` | the phone's clipboard, read and set from the shell |
| A context | `PhoneContext.java` | what the framework's DisplayManager and ClipboardManager need to run outside an app |
| Shared with Linux | `LinuxStreamSocket`, `LinuxStreamEncoder`, `DexLog` (launcher) | the WebSocket, the encoder's choice and set-up, the log — compiled into the daemon's dex from the launcher's sources (`build.sh`) |
| The session | `src-tauri/src/scrcpy.rs` | the same session either way: the child it watches, reconnects and ends; which window (`own_video_wanted`); falling back |
| The window's way to the phone | `src-tauri/src/dex.rs` | the server's command line; the window; a loopback port held to remote_usb.rs's checks, spliced through adb; drops; the window's events |
| The window | `src/features/dex/DexWindow.tsx` | decoding, drawing one for one, input, the clipboard's computer end, the cover, the pill |
| Pure parts | `dex/androidKeys.ts`, `remote/usb/latency.ts`, `remote/usb/decoder.ts` | keys and the mouse as Android takes them; a frame's age; the decoder both windows use |
| The Wi-Fi | `WifiLow.java` (launcher), `adb.rs` (`wifi_lock`) | the phone's radio out of power save for a wireless session |
| Check | `test/dex-video-test.mjs`, `test/dex-video/` | the pure parts, and the server on a phone |

## The pipe

```
the desktop's display ──SurfaceFlinger──▶ SurfaceTexture (ours) ──one GL draw, when the pace allows──▶
MediaCodec input surface ──H.264──▶ DesktopSession ──@openandroiddex-desktop-<id>──▶ adbd ──adb──▶
adb server ──host:transport + localabstract──▶ dex.rs (127.0.0.1:<port>, checked) ──▶ the window's WebSocket
──▶ VideoDecoder ──▶ canvas, one pixel for one
```

**The session is the same session.** Everything around a desktop — the
monitor that reconnects it, the launcher deployed onto its display, the
enforcer keeping windows in shape, the audio companion, the request pump — is
keyed to a child process the app watches. On this path the child is an `adb
shell` running the server, and the server says what it made in scrcpy's own
words (`New display: WxH/dpi (id=N)`, `Display resized: …`), which the app
already reads. So none of that changed. The shell's stdin stays open for the
session's life; the server ends when it closes — the app quitting, the cable
pulled, adbd restarting (which kills everything adb started anyway) — and its
display goes with it, taking its windows (DESTROY_CONTENT_ON_REMOVAL), as
scrcpy's does.

**Its own process, from the window daemon's dex.** Not the daemon itself:
the launcher talks to the daemon, a fault in a video encoder should not take
the window chrome with it, and the daemon must outlive every display. The dex
is pushed to a file of its own (`/data/local/tmp/oadx-desktop.dex`, by rename,
so a running server keeps its copy) because it has to be there before the
display — and the deploy, which pushes `wmd.dex`, comes after.

**A texture between the display and the encoder.** scrcpy hands the display
the encoder's input surface, so every composed frame is encoded. Here the
display draws into a SurfaceTexture, every frame is taken off it as it comes
(so SurfaceFlinger never waits on us), and one GL draw puts the newest into
the encoder when the pace says so. The draw is the GPU's; the CPU touches no
pixel. The texture lives for the whole process; an encoder is made again for
every new size.

## The pace

The Linux stream paces itself by when it asks Xvnc for the next picture:
Xvnc gathers changes meanwhile, so what comes is the newest. A display has
no such question — it composes whenever something on it changes — so the
pace is kept where the picture goes in: a frame that may not go yet is not
drawn, the next one replaces it, and the one that goes is the newest. The
gates are the Linux stream's, with its numbers (usb-desktop.md, "Pacing"):

- **The encoder** may hold at most two frames.
- **The link** may hold as many as fill its round trip, plus the one on the
  wire. The window acknowledges each frame as it arrives (`0x12`); the median
  of the last 32 round trips sizes the window, between 2 and 12 frames.
- **Not faster than the window asked for** (60 by default): a display that
  composes at 90 or 120 Hz would otherwise cost the link half as much again.

A frame waiting on the link for a fifth of a second drops the bitrate by 30 %;
three seconds of motion the link kept up with climb back by 25 %, up to the
ceiling the window asked for — again LinuxStream's rule. The encoder sends the
last picture once more after 100 ms of stillness, so it sharpens, and then
nothing.

## The display

The server makes it as scrcpy does, so a phone that ran scrcpy's desktop runs
this one: PUBLIC, PRESENTATION, OWN_CONTENT_ONLY, SUPPORTS_TOUCH,
ROTATES_WITH_CONTENT, DESTROY_CONTENT_ON_REMOVAL; from Android 13
OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, TOUCH_FEEDBACK_DISABLED; from 14 OWN_FOCUS
and DEVICE_DISPLAY_GROUP; no system decorations — the launcher is the desktop.
TRUSTED from Android 11, where the flag exists (scrcpy waits for 13); a build
that will not let the shell have it is answered with the display without it,
and the app's existing check says what that costs.

Its name is "Open Android DeX". The two things that looked for a desktop
display by scrcpy's name — the launcher's cable-out watch (`UnplugWatch`) and
the app's fallback probe of `dumpsys display` — know both.

It is made through the framework's own `DisplayManager`, with a Context made
for a process that is not an app (`PhoneContext`): the public classes know
their release's binder signatures, which change more often than anything else
the daemon touches. The context names the shell's package, which every
service checks against the caller's uid; on One UI the bare ActivityThread
also needs a ConfigurationController, or looking up a display dies in a null
pointer (found by scrcpy, its issue 4467).

**It follows its window, in the screen's pixels.** The window says its size in
device pixels on connect and on every resize (settled for 400 ms); the phone
resizes the display and makes a new encoder. The density stays — a bigger
window shows more desktop, not a bigger one — until the window lands on a
screen of another scale, when the app moves it with `wm density` as it does for
scrcpy's window (`move_density`), and checks it again each time the window
connects, so a window opened on another screen than the one the display was
made for is put right at once. The display is first made for the screen the
app's window is on (`display::desktop_window_geometry`), held to 3840 pixels on
its long side but not to what scrcpy has learned of the phone's encoder: this
path's encoder takes a display it cannot code a step smaller by itself, and a
display shrunk for it would have shrunk the density too — measured, the first
desktop came up at 95 dpi on a 1x monitor.

**It is kept awake.** It has its own power state and screen timeout, and a
person reading moves nothing; every four seconds the server tells
PowerManager the display is in use (`userActivity` with the display, from
Android 12) — scrcpy's `--keep-active`.

## Input

The pointer is the computer's own: the phone is told where it is and what is
held, so it moves at the screen's rate whatever the video does. The mouse goes
as RFB's does — position and buttons now — and the server turns that into the
sequence a real mouse makes on Android: hovering, DOWN and BUTTON_PRESS for the
first button, MOVE while held, BUTTON_RELEASE, UP with the last. Each event
carries the keyboard's modifiers, so Shift-click extends a selection, and the
desktop's display id, so nothing lands on the phone's own screen.

- Left, right and middle are Android's buttons. A mouse's side buttons are
  Back and Forward, as a real mouse's are on Android. Shift with the right
  button is Back, the binding scrcpy's window had.
- The wheel goes in notches of 64 dp — 64 of the computer's points, the dp
  being a point — so a page scrolls as far as it would here; fractions for a
  touchpad. Without Shift: the browser has already turned Shift+wheel sideways.
- Characters typed go as text, in whatever layout the keyboard has: the server
  types them with the virtual keyboard's own key presses, or hands over the
  ones it has no keys for (é, 日本, an emoji) as one ACTION_MULTIPLE string,
  which text fields take whole. Keys that do things — arrows, Enter,
  Backspace — and any key with a shortcut modifier go as Android key codes and
  meta, a letter by the letter the layout types (Ctrl+A on AZERTY is select
  all). The computer's key repeat is the phone's.
- A Mac is spoken to as Mac users type: Cmd is Ctrl; Cmd+C, X and V are
  Android's Copy, Cut and Paste keys, which a Linux window turns into Ctrl or
  Ctrl+Shift by app (a terminal's is Ctrl+Shift+C); Cmd and Option with the
  arrows and Backspace move and delete by line and word; Cmd+Q, H, M, Tab, `
  and Space stay the Mac's. A paste waits for this computer's clipboard to
  reach the phone first.
- F11 is the window's fullscreen; Escape leaves fullscreen and goes no further
  ([escape-leaves-fullscreen.md](escape-leaves-fullscreen.md)), and is Android's
  otherwise.
- Touchpad gestures work as they did: the gesture engine's "is the desktop in
  front" asks whether this window has the keyboard (`dex::window_in_front`,
  never blocking), and scrcpy's pid otherwise.

## The clipboard

The window's rules are the Linux window's (`clipboardShare.ts`): this
computer's clipboard goes to the phone when the window comes to the front, the
phone's comes here while the window is in front, and a copy made on the phone
while it is elsewhere waits, and loses to a newer one here. The switch is the
phone's own Settings → Clipboard, which scrcpy's window obeyed too.

On the phone the server reads and sets the clipboard through the framework's
ClipboardManager, as the shell: an app may read it only while it has focus
(Android 10 and later), the shell holds READ_CLIPBOARD_IN_BACKGROUND. What the
computer gave is not sent back as a copy. Up to 512 KB of UTF-8 at once.

## Files dropped on the window

Into the phone's Downloads, and an `.apk` installed — what scrcpy's window
does with a drop, by `adb push` and `adb install -r`, one drop at a time. Each
step is told to `transfer.rs` in scrcpy's own words, so the phone's transfer
card follows it exactly as it followed scrcpy's.

## The window

`DexWindow.tsx`, a window of this app's labelled `dex-<hash of the session>`,
its own page only (capability `dex-window.json`). It opens maximised on the
screen the app's window is on, says what it is waiting for until the picture
comes, and waits through a reconnect: the cable pulled and plugged back, the
phone's adbd restarting after a plug-in. Closing it ends the desktop, as
closing scrcpy's always did. "Show desktop" brings it forward, and the
taskbar's ⛶ makes it fullscreen; its own fullscreen is reported back so the ⛶
says what it is.

The pill — at the top edge, as the Linux window's — says the route (USB,
Wi-Fi), the size, frames shown a second, the bitrate, and how old the frames
are when they show (below).

## Who gets in

**On the phone** the socket is abstract and named afresh for every server
(`openandroiddex-desktop-<random>`), and each connection's peer is checked
(SO_PEERCRED): the shell, which adbd is, and root. Not the launcher, unlike
the Linux stream: its uid is also the Linux guest's, where anything the user
installs runs, and this socket takes a mouse and a keyboard.

**On the computer** nothing listens but the window's own port on loopback,
with remote_usb.rs's checks: `Host` must name the port, `Origin` must be the
app's own page, and the path must carry the window's random token. The port
then speaks adb's own protocol to the phone — no `adb forward`, which would be
a port any page in any browser here could reach.

## Falling back to scrcpy

The desktop goes to scrcpy's window when:

- the phone's Settings say Classic (`video`), or the phone draws the pointer
  (uhid, scrcpy's alone), or `OPENDEX_VIDEO=scrcpy` is set;
- the window daemon's dex is not in the bundle;
- the server ends before it has made a display — the same desktop, in
  scrcpy's window, and the phone remembered for as long as the app runs, so a
  reconnect does not try again first;
- the window cannot decode (no WebCodecs in this webview), or the person asks
  from its "stopped" cover ("Use the classic window").

The session does not end for it: the monitor starts scrcpy in place of the
server, a new display comes, and the launcher is put back on it as after any
reconnect.

## The Wi-Fi

In power save the phone's radio sleeps between beacons, and a frame or an
acknowledgement waits for it: round trips over wireless debugging went from 8
ms to 150 and back from one moment to the next, at any load. The launcher
holds a low-latency Wi-Fi lock while it runs a desktop the app reaches over
wireless debugging (`am start … --ez wifi_lock`, said on every start), and the
Linux stream's service while a session over Wi-Fi or the tunnel is watched —
the window says its route in the hello (`tier`), and a session through the
tunnel is the app's own uid. Android 10 to 13 also get the high-performance
lock, which holds in the background as well; from 14 a high-performance
request becomes a low-latency one, which holds while the app is in front, as
a desktop's launcher is (`WifiLow`).

## Measuring it

Every change to this path is measured by one number: how old a frame is when
the window shows it. The phone stamps each frame with its own clock
(CLOCK_MONOTONIC) — the Android desktop when its compositor made the frame,
the Linux desktop when the picture went into the encoder — and every pong says
what that clock reads (`at`); half way through the ping's round trip is the
best guess of when it read it, and the ping with the shortest round trip is the
guess with the least room for error (`latency.ts`). What it leaves out is the
phone's own before the stamp — an app drawing its frame — and this screen's
after the draw.

Both windows show it in their pill, and the Android desktop's window writes a
line to the app's log every half minute: size, frames shown, bitrate, latency,
round trip, route — but not for a half minute it spent hidden. The webview
runs a hidden window's page late, its frames with it: measured, a window left
behind another app read 46–50 ms where it had read 19 in front, with the same
round trip. scrcpy's window cannot be measured that way; its frame
count (`--print-fps`) is summed into one log line every half minute instead,
so the two can be read against each other.

## Verified, and not yet

Off a phone: `node test/dex-video-test.mjs` (40: keys, text and the Mac's
shortcuts, the mouse's buttons and wheel, a frame's age), `cargo test` (120;
`dex::` 4 — labels Tauri takes for mDNS serials, the session's key through the
window's address, the window's navigation, which window is in front), `tsc`.

On a phone (`--device`, Galaxy S25 Ultra, Android 16, the cable, 2026-10-21):
the server under `app_process` as the shell, on a display of its own: trusted,
own display group, always unlocked, own focus, owned by the shell, announced
in scrcpy's words; `c2.qti.avc.encoder` in hardware with the low-latency
configuration; Settings drawn and decoded by Chrome's WebCodecs through the
app's `h264.ts` at the display's size; a still desktop sending nothing; the
wheel scrolling Settings at 65 fps, the latency from the phone's compositor to
a decoded frame 12 ms typical and 9 at best, round trip 1–2 ms; a resize the
display followed ("Display resized", `wm size` agreeing) with a new encoder;
the clipboard both ways, an em dash, CJK, Hangul and emoji whole, not echoed
back; the server's stdin closing taking the display and Settings with it.

Over Wi-Fi (`--device` on the wireless debugging transport, the same phone at
1920x1080, two pairs of runs alternating the launcher's lock): Settings
scrolling at 42–49 fps; 22–27 ms typical from the phone's compositor to a
decoded frame, 14 at best; round trips 6–10 ms at best and 17–31 typical.
With the lock held — `dumpsys wifi` showing it held and the radio in mode 4,
low latency — the typical latency was 3–4 ms lower in both pairs, and in the
first, where the radio without it had been dozing, the link's median round
trip halved (16 ms against 31) and its window went from three frames to two.
The phone's lock follows the route: the same launcher let it go when told the
cable.

The real thing (the app from `tauri dev`, the same phone on the cable, a 1x
2560x1440 monitor): the desktop in its own window, maximised, the display
following it to 2560x1284 at 160 dpi, the launcher deployed onto it as onto
scrcpy's; an idle desktop at about 6 fps (its clock and gauges) at 19–20 ms of
latency and a 2–3 ms round trip. The window's first desktop came up at 95 dpi
— scrcpy's learned encoder cap had shrunk the display it was made for — which
is why this path is held to 3840 only and the density is checked on connect.

Not yet:

- the real window over wireless debugging ("Go wireless"): the numbers above
  were the server's own, measured by the test page; scrcpy's window on the same
  link (its `--print-fps` tally) beside them; a link slow enough to make the
  bitrate fall;
- typing, shortcuts, the clipboard and drops in the real window, by a person;
- the cable pulled and put back under a live window; adbd's restart after a
  plug-in, which kills the server too;
- moving the window to the Retina screen (the density following);
- the fallback to scrcpy on a phone where this path fails, and a webview
  without WebCodecs (WebKitGTK);
- Android 10–12 (no display group, the trusted flag tried from 11), Windows'
  WebView2 and Linux hosts;
- a portrait-only app fullscreen on the desktop (ROTATES_WITH_CONTENT, kept
  from scrcpy's flags; a rotated display is not handled here);
- whether reading the clipboard as the shell shows Android 12's "pasted from
  your clipboard" toast;
- a hidden window: the phone keeps encoding every change for it, as for
  scrcpy's (its acknowledgements come late, so the pace slows and the bitrate
  falls by itself); pausing the stream while hidden is not done.
