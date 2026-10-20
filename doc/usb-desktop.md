# The desktop over adb — the phone's Linux at a local desktop's speed

*Linux → Open desktop* opens the phone's XFCE in a window on the computer
([remote-access.md](remote-access.md), "The desktop, in a window"). Through the
tunnel that is the phone's own viewer page — noVNC — and it is as fast as VNC
gets: Xvnc squeezes every change into JPEG and zlib on the phone's CPU, and the
page undoes it in JavaScript. Fine for a terminal and a menu; a scrolling page
or a moving window shows it.

With the phone on **adb** — the cable, or wireless debugging — the same window
gets the desktop another way: Xvnc's pixels raw over the phone's loopback, into
the phone's **hardware video encoder**, out as H.264 that the computer's GPU
decodes. The pointer is the computer's own, wearing the shape Xvnc sends, so it
moves at the screen's rate whatever the video does. Off adb the same video
comes **through the tunnel**, at a bitrate the route can carry, and the viewer
page is left for a phone whose app has no video. The window chooses by itself,
and moves between them as the cable comes and goes.

This record exists for the same reason the others do: several numbers below
look arbitrary until you know what was measured.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The server | `LinuxStream.java` (in `:linux`, started with `LinuxService`) | the abstract socket, who gets in, one session at a time, the desktop's state and start, pacing |
| Xvnc's pixels | `LinuxStreamRfb.java` | a VNC client for loopback: VncAuth (its own DES), raw rectangles, the cursor's shape, resizes, input, the clipboard (UTF-8) |
| The encoder | `LinuxStreamEncoder.java` | a GL texture the size of the desktop, drawn into MediaCodec's input surface; low-latency H.264 |
| The socket | `LinuxStreamSocket.java` | the server end of a WebSocket, and `GET /probe` |
| Waking it | `RemoteProvider.java`, method `stream` | one `content call` that starts the service the socket lives in |
| The computer's end | `src-tauri/src/remote_usb.rs` | adb's own protocol, which phone is which, the window's port and its checks |
| Through the tunnel | `openandroiddex-remote` (`stream` op in `phone.rs`, `/stream/<token>` in `pc.rs`), `remote_desktop_stream` | the daemon put through to the same socket; the window's port, the same checks |
| Which way | `src/features/remote/DesktopRouter.tsx` | adb, the tunnel's video or its viewer page, and moving between them; and what is the window's either way — the clipboard, drops, what Linux sends |
| The window | `src/features/remote/usb/UsbDesktop.tsx` | decoding, the cursor, input, the size, the clipboard's guest end, the status pill |
| Pure parts | `usb/h264.ts`, `usb/keysym.ts` | Annex B → WebCodecs; keys → keysyms |
| Check | `test/usb-desktop-test.mjs`, `test/usb-desktop/` | everything off the phone, and the stream classes on one |

## The pipe

```
Xvnc :1 (in proot) ──raw RFB, loopback──▶ LinuxStreamRfb ──glTexSubImage2D──▶ texture
texture ──one draw per update──▶ MediaCodec input surface ──H.264──▶ LinuxStreamSocket
  ──@<package>.linux-stream──▶ adbd ──adb──▶ adb server ──host:transport + localabstract──▶
remote_usb.rs (127.0.0.1:<port>, checked) ──▶ the window's WebSocket ──▶ VideoDecoder ──▶ canvas
```

**Raw, because on loopback everything that makes VNC efficient is cost.** The
bytes never leave the phone; what was expensive was Xvnc's JPEG and zlib on a
CPU that is also drawing the desktop. Raw is a copy on Xvnc's side and a
texture upload on ours. The pixel format is Xvnc's own (B,G,R,X at depth 24),
so it converts nothing; the channel order is undone in the shader (`swizzle`).
CopyRect is not asked for: on loopback a moved window as raw is cheaper than a
texture-to-texture copy GLES 2 does not have.

**The GPU does the colour conversion, the encoder block the rest.** Each
complete update is one draw of the texture into the encoder's surface — the
same pipe scrcpy uses for the phone's screen, fed from a texture instead of the
compositor. Nothing per pixel runs on the CPU but Xvnc's copy and the kernel's.

**Shared, always.** The phone's own Linux window may be watching the same
desktop; an exclusive client would throw it off. A resize from the computer
resizes the guest, and the phone letterboxes it, as through the tunnel.

What was weighed instead:

- **scrcpy on a virtual display with the Linux window on it.** The video would
  be hardware-encoded, but the picture on that display is still noVNC in a
  WebView — JPEG and JavaScript, now on the phone — and the window would be a
  scrcpy window with none of this one's sizing or chrome.
- **A different X server** (Xvfb with a mapped framebuffer, or an X server that
  draws into an Android surface). Faster again in principle, but the phone's
  own Linux window and the tunnel both stand on Xvnc and noVNC.

## The pointer is the computer's

Xvnc sends the cursor as a shape (the Cursor pseudo-encoding) and leaves it out
of the picture. The window turns it into a CSS cursor, so the pointer is this
computer's own, at its own rate — the single biggest reason it feels like a
local desktop rather than a remote one. A cursor larger than 256 px, or none, is
no cursor.

## Who gets in

**On the phone,** the socket is abstract — reachable by `adb` and by every app
on the phone alike — so each connection's peer is checked with SO_PEERCRED: the
shell (2000, which is adbd), root, or this app's own uid, which the
remote-access daemon is: it carries the stream through the tunnel. It is
RemoteProvider's rule: anyone with adb already owns the phone, and nobody else
gets a byte.

**On the computer,** adb's own protocol, not `adb forward`. A forward is a
listening port, and loopback is not private to the app — any page in any browser
here could open a WebSocket to it. remote_usb.rs asks adb's server for
`host:transport:<serial>` then `localabstract:<name>` and gets a byte stream with
nothing listening anywhere. The window's own port holds remote_desktop.rs's line
and one more: `Host` must name the port, `Origin` must be the app's own page
(tauri://localhost, http://tauri.localhost, or the dev server in development),
and the path must carry the window's random token, which only its page is given.

## Which phone is it

Each attached phone is asked `GET /probe` on the socket, which answers with the
id it was paired under: a few milliseconds, against the second a `content call`
costs (it boots a VM). The answer is remembered per serial, so a second phone on
the same computer is not asked again every time. Only a phone whose socket does
not answer is woken — `content call … stream` starts `LinuxService` — and asked
again; a probe never replaces a running session.

## Pacing: a slow link costs frames, never latency

Two gates stand before each request for the next update, and Xvnc gathers
changes while they hold, so the next update is the newest picture, not the next
one in line:

- **The encoder** may hold at most two frames.
- **The link** may hold as many frames as fill its round trip, plus the one on
  the wire. The window acknowledges every frame as it arrives (`0x12`); the
  phone measures each frame's round trip and keeps the median of the last 32.

A write returns once adbd has the bytes, not once the computer does, and on
wireless debugging adb will hold a second of video in its buffers. Measured on
the S25 Ultra over wireless debugging, 2560×1440 with every pixel changing:

| Pacing | Frame rate | Round trip |
| --- | --- | --- |
| encoder only | 55 fps | 129 ms typical |
| three frames, with a 500 ms escape | 15 fps | ~1 s — the escape let frames past and the queue grew |
| round trip in frames, from the *best* round trip | 20 fps | best 8 ms, typical 100: the window was sized for the 8 |
| from the *median*, at most 12 | 45–53 fps | ~100 ms typical, and it is the link's: the phone's Wi-Fi in power save |

On a cable the round trip is a millisecond or two, so the window is two frames
and the picture is never behind by more than that. The ceiling of twelve (200 ms
at 60 Hz) is what is left to stop: a link slower than the video.

## The encoder

H.264 High, VBR, no B-frames, `KEY_LATENCY` 1, realtime priority, Qualcomm's
own low-latency switch where it is a Qualcomm encoder, parameter sets on every
key frame so a decoder can join at any of them, a key frame every ten seconds
and whenever the window asks (after a decoder error), and the last picture once
more after 100 ms of stillness so it sharpens. If the encoder will not take all
of that, it is configured again with only what every encoder takes, and the log
says so. The coded size is the desktop's, padded to the encoder's alignment
(the window crops the padding off); a desktop larger than the encoder takes at
60 fps is drawn smaller and the window scales it back.

The bitrate is set by the window from the desktop's pixels and the route, at
60 Hz:

| Route | Bits a pixel | Floor–ceiling | At 2560×1440 |
| --- | --- | --- | --- |
| the cable | 0.18 | 10–100 Mb/s | 40 Mb/s |
| Wi-Fi: wireless debugging, or the tunnel on the same network | 0.07 | 4–30 | 15.5 |
| the internet, straight (mobile data, someone else's Wi-Fi) | 0.03 | 2–12 | 6.6 |
| a relay | 0.012 | 1–4 | 2.7 |

They are ceilings for motion; a desktop is mostly still, and still costs
nothing — Xvnc sends no update when nothing changed. When the route changes
under a live stream — hole punching gets past the relay a few seconds after a
connect — the window sends the new ceiling (`{"t":"bitrate"}`), and the encoder
takes it from its next frame (`PARAMETER_KEY_VIDEO_BITRATE`).

**Below the ceiling, the link decides.** Once a second the phone looks at how
much of that second the pump spent waiting for acknowledgements. Over a fifth
is a link taking frames slower than they come: the bitrate drops by 30 %. Three
seconds of motion with almost no waiting climb back by 25 %, up to the
ceiling, never below 0.5 Mb/s. Fewer bits per frame is how a slow link keeps
its frame rate; the pacing above already keeps it from piling up latency.

## The window

WebCodecs, in the AVC form: the stream's Annex B is split into units, the
parameter sets go into an avcC description, the codec string comes from the SPS
(`h264.ts`). WebCodecs takes Annex B in some engines and not others; this form
it takes in all. `optimizeForLatency`, and each frame is drawn as it comes out
of the decoder. WKWebView was checked for both of the app's origins
(tauri://localhost and the dev server's http://localhost): a secure context,
`VideoDecoder`, H.264 and HEVC. A webview without WebCodecs uses the tunnel.

The canvas is the desktop's size and the desktop is the window's, in logical
pixels — one pixel per pixel, drawn `pixelated` on a Retina screen rather than
smoothed. A resize asks for `SetDesktopSize` half a second after it settles; the
phone makes a new encoder at the new size.

Keys go as keysyms (`keysym.ts`): the character the keyboard makes here when
typing (any layout, AltGr, dead keys); the key's place with Ctrl or Cmd held, so
a shortcut is the same on any layout; a Mac's Option types its character with
Alt let go around it; Caps Lock and Num Lock stay here, their effect already in
the characters. Held keys and buttons are let go when the window loses focus,
and on a Mac every key goes up with Cmd, which reports no keyup for them. F11 is
the window's fullscreen.

The clipboard is the window's, the same one the tunnel's page uses
(`clipboardShare.ts`, remote-access.md's "The clipboard"): this computer's to
Linux when the window comes to the front, Linux's to this computer while it is
there, and the switch for it — on the status pill here, as on the viewer's bar
through the tunnel — remembered per phone. The stream only carries the text
(`clip`, both ways) and the phone hands it to Xvnc.

**On the phone it is TigerVNC's extended clipboard**, the one noVNC speaks
through the tunnel, so every character crosses: standard cut text is Latin-1,
where an em dash, 日本語 or an emoji becomes `?`. `LinuxStreamRfb` asks for it
among the encodings (`0xC0A1E5CE`); Xvnc answers with its capabilities ahead
of its first picture, and gets this client's back — text only, and a size of
0, so none of it comes unasked. Then, as Xvnc does it:

- *Computer → Linux*: Xvnc is only told there is text (notify). It asks for
  it (request) when a program in the guest pastes, which may be minutes
  later, so the phone keeps the computer's latest text to hand over then
  (provide). Empty text — the switch turned off — offers nothing, and Xvnc
  lets go of what this connection gave the guest.
- *Linux → computer*: Xvnc says the guest has new text, and the phone asks
  for it at once, as TigerVNC's own viewer does; the window decides whether
  it lands (clipboardShare.ts).
- The text is UTF-8 with CRLF line ends and a NUL, deflated, behind its
  length — counted that way against Xvnc's MaxCutText of 256 KB, past which
  Xvnc drops it unread. The window holds such a text back and says so; the
  phone does not offer one either. What comes back is handed on up to 4 MB.
- A server that sends no capabilities before its first picture has no
  extended clipboard, and gets standard cut text, Latin-1.

Files dropped on the window, and what Linux sends this computer, are the
window's too (`DesktopRouter.tsx`): they go through the tunnel whichever way
the picture comes — SFTP into the guest's Desktop, and from its outbox into
Downloads — so a copy under way carries on when the cable is pulled.

**The pill's numbers** are the picture's size, the frames shown a second, the
bitrate, and how old a frame is when it shows: the phone answers each ping
with its own clock (`at`), the clock every frame's pts is on, and the window
reads each frame's age against it — the same readout as the Android desktop's
own window ([dex-video.md](dex-video.md), "Measuring it"). A phone whose pongs
carry no clock gets the round trip instead, as before.

**The window says its route in the hello** (`tier`: cable, wifi, remote,
relay), and for a route that is not the cable the phone keeps its Wi-Fi out of
power save while the session lasts (`WifiLow`, dex-video.md, "The Wi-Fi"); a
session through the tunnel, which is the app's own uid, counts as one without
being told.

**The title says how the window gets there**: `<phone> — Linux · USB` on the
cable, `· Wi-Fi` on wireless debugging, and through the tunnel `· Wi-Fi` on the
same network, `· Remote` across the internet (mobile data, someone else's Wi-Fi)
and `· Remote (relay)` through a relay. Direct alone cannot tell the tunnel's last
two apart, so the path's address does (`pc::on_local_network`): an RFC 1918,
link-local or ULA address, or — IPv6 having no NAT to give it away — the same /64
as this computer's, is the local network. While a stream over adb is live,
remote_usb.rs owns the title and the tunnel's watch stands aside; when the last
one ends the title goes back to plain, and the tunnel's watch, if the window has
gone there, fills it in within two seconds.

## Through the tunnel

Off adb, the remote-access daemon carries the same stream: `desktop/1` has a
`stream` op beside `web`, and the daemon — this app's uid, which LinuxStream
lets in — puts it through to `@<package>.linux-stream` (`--stream-socket`,
which RemoteHost passes; a test gives a path, since a Mac has no abstract
sockets). On the computer it arrives on the window's own tunnel port, the one
the viewer page is served on, at `/stream/<token>`, under the rules the port
over adb holds: `Host` naming the port, `Origin` the app's own page — not the
viewer's frame, which has this port's origin and is the guest's to rewrite —
and the window's token, which goes no further than this computer. The phone
is handed `/stream`, the same request as over adb, so LinuxStream cannot tell
the two apart and does not need to.

Whether a phone has it is asked with the ping the window sends anyway: a
phone with the video answers `{"ok":true,"stream":true}`, an older one plain
`ok`, and gets the viewer page. A webview without WebCodecs gets the viewer
page too.

```
LinuxStream ──@<package>.linux-stream──▶ dexremote serve (app uid) ──QUIC, desktop/1 `stream`──▶
pc::Desktop (127.0.0.1:<tunnel port>/stream/<token>, checked) ──▶ the window's WebSocket
```

**The title and the pill say the route**: `· USB` and *USB* on the cable;
`· Wi-Fi` and *Wireless debugging* on adb over Wi-Fi; through the tunnel
`· Wi-Fi`, `· Remote` or `· Remote (relay)`, and the pill the same words —
following the path as it changes, as the bitrate does.

## Coming and going

The window opens over adb when the phone is on it; otherwise as video through
the tunnel; otherwise with the viewer page. A stream that ends without the
phone saying why — the cable pulled — is looked for on adb once more (a
restarted service comes straight back the same way), then as video through the
tunnel, then the viewer page. The desktop lives on the phone, so nothing in it
is lost either way. While off adb the window probes adb every three seconds,
and moves back when the phone is there; on the viewer page because the phone
was out of reach when its video was asked for, it asks again every twelve. A
phone that says it has no video is not asked again while the window is open.
Three streams in a row that end within ten seconds of starting are something
the window cannot fix: it stays on the viewer page for half a minute.

What the window owns rather than the route goes along unchanged: a copy in
progress, the cards in the corner, the clipboard's switch. Only the guest's
copy of this computer's clipboard has to be given again — Xvnc holds a
client's clipboard only while that client stays — and the new connection gets
it.

A DeX session on the same cable is another matter: pulling the cable ends it,
and about ten seconds later the phone undoes the session by itself — its
settings and the caption service. Two things there can do it:

- **The window daemon's dead-man switch** (`WmDaemon.cleanup`) does all of it,
  the grants included. The PC re-arms it every two seconds
  (`WATCHDOG_TTL_SECS`, ten seconds; it was a minute) and it looks every second.
- **The launcher** (`UnplugWatch`), for when the daemon is dead. A restarting
  adbd kills the daemon — init kills adbd's whole cgroup, `setsid` or not — and
  the S25 Ultra restarts adbd three seconds after every unplug, before the
  switch trips. So the launcher keeps a copy of the switch (the daemon's
  `ARMED`) and, holding WRITE_SECURE_SETTINGS for the session (granted at
  deploy, revoked by `restore_phone` and by the daemon's own undo), writes the
  settings back itself, then turns the caption service off. The app-ops, the
  widget whitelist and the pointer speed need the shell's uid and wait for the
  PC.

Neither undoes anything while the phone sits on a computer's USB port: the
cable is still in or already back, and the PC is about to set the desktop up
again. The launcher's Linux keeps running through all of it, so a desktop
window carries on over Wi-Fi, and a phone plugged back in gets DeX set up
again around it (`desktop:relaunch` with `resume`: no reinstall, no
force-stop), not just a new display over a profile that is no longer there.
Only with no Linux running does the daemon force-stop the launcher, and then
it sends the same "bring remote access back" broadcast the PC sends after its
own force-stop; before, a phone unplugged mid-session stayed unreachable over
SSH and the desktop window until something else restarted the app.

## Verified, and not yet

The window in headless Chrome, off the phone, Tauri's IPC mocked and the far
end a stand-in LinuxStream (51 checks, the same run as remote-access.md's):
the route chosen and the stream asked for at the cable's rate, the clipboard
and its switch over the stream, a drop on the video; the cable pulled in the
middle of a copy and the window on the tunnel's video at a relay's rate, its
pill saying so, the copy finishing there, the clipboard given again on the new
connection, the path going direct and the phone told Wi-Fi's ceiling; a phone
out of reach when its video is asked for, the viewer page meanwhile and the
video again once it answers; a phone without video on the viewer page; and
the move back to adb within three seconds.

Through the tunnel (`node test/remote-test.js`, 9 more checks, over the real
relays): a phone with video says so in its ping and `dexremote desktop
--video-origin` prints the address; another token, the viewer's own frame,
another site and another host refused, none of them reaching the phone; the
app's page through to LinuxStream's socket, handed `/stream` with the page's
`Origin`, 1 MB both ways intact; and a phone without it refusing the op in a
sentence, and a computer asking first told the app is too old.

Off the phone (`node test/usb-desktop-test.mjs`, 63 checks): the window's H.264
handling and key mapping; the launcher's own `LinuxStreamRfb` against a stand-in
Xvnc — VncAuth against OpenSSL's DES, a wrong password refused, shared, raw and
the four pseudo-encodings asked for and nothing else, Xvnc's format kept, every
sampled pixel right, the cursor's shape and mask, a resize and the pictures
after it, pointer (held to the desktop), keys; the extended clipboard as
TigerVNC 1.13 speaks it — the capabilities both ways, each text announced and
handed over only when asked, CRLF and Latin-1, an em dash, CJK and an emoji
whole, CRLF line ends and the NUL as Xvnc counts them, 200 KB in one piece,
300 KB never offered, the last text kept for a paste later on, a take-back
that offers nothing, and from the guest the same kinds and 200 KB; and against
a stand-in without it, Latin-1 both ways; its
`LinuxStreamSocket` — the probe, a 300 KB message both ways, RFC 6455's accept,
another origin and another host refused. `cargo test`: adb's framing against a
stand-in server, serials with spaces, the window port's checks.

On a phone (`--device`, Galaxy S25 Ultra over wireless debugging, 2026-10-19):
the stream classes under `app_process` as the shell user, nothing installed,
against a stand-in Xvnc on the phone's own loopback changing every pixel of
every frame: `c2.qti.avc.encoder` in hardware with the low-latency configuration
taken; 2560×1440 at 45–55 fps and 1920×1080 at 44–53 fps decoded by Chrome's
WebCodecs through the window's own `h264.ts`; ~12–15 ms from the encoder's input
to its output; pointer, keys, clipboard both ways and a resize to a new encoder,
all through adb.

On the cable (`--device`, Galaxy A13 SM-A135U, Exynos 850, 2026-10-20): a round
trip of 1.8 ms at best and 3 typical, so the window held two frames;
`OMX.Exynos.AVC.Encoder` in hardware, low latency taken; 1920×1080 changing every
pixel at 23 fps, and the time per update says where the rest goes — 32 ms
waiting for the stand-in's 8 MB of raw pixels on the phone's slow cores, 4.5
uploading them, 5.4 drawing into the encoder. A real desktop sends what changed;
on a phone like this one it is XFCE's own drawing that sets the pace.

The real thing (2026-10-20, the desktop app from `tauri dev`, the launcher it
deploys): on the A13 with no Linux, the socket asleep answers adb's `FAIL
closed`, and the provider's `stream` says `{"ok":false,"linux":"missing"}` and
wakes nothing. On the Galaxy S25 Ultra over the cable: the socket in `:linux`
answering adbd (uid 2000) — so SELinux lets adbd reach an app's abstract socket
— with the id the computer paired with; a session with nothing to start saying
`stopped`; then the window itself: *Desktop* chose adb ("the desktop goes over
adb (RFCY10FRKBK, the cable)"), Xvnc read at 2176×1086 in its own B,G,R,X,
`c2.qti.avc.encoder` with the low-latency configuration at 25 Mb/s, WKWebView
decoding it, the desktop one pixel per pixel and sharp (the user's own
screenshot), a resize to 1352×1086 on a new encoder five seconds later, and the
title naming the route. Typing from the Mac's keyboard, F11 and the cursor's
shape: checked by the user in the same window.

Pulling the cable, S25 Ultra, 2026-10-20 (over Wi-Fi, and over mobile data
with the phone's Wi-Fi off): the window was on the tunnel's video within a
second of the cable going — at a relay's rate while the path was unknown and
Wi-Fi's 4 Mb/s two seconds later, or "Remote" at 2 Mb/s over mobile data — and
back on adb about ten seconds after the cable was back, once the phone had
settled. adbd restarted three seconds after every unplug and took the window
daemon with it; the launcher undid the session ten seconds after the desktop
went ("8 settings put back, 0 left for the computer"), and a cable back within
ten seconds undid nothing. Plugged in, Samsung's MTP service switches the port
to file transfer about five seconds later, which restarts adbd again — right
through a deploy, or just after one: DeX came back on the display scrcpy made
next ("the desktop came back on display N") in 1.2–1.4 s each time, and a
deploy cut off part way waited for the phone instead of failing. The
launcher's Linux kept its processes through all of it, and a launch onto a
phone already running this build's Linux (the app restarting, say) now resumes
around it rather than reinstalling the launcher, which used to end that Linux.

Not yet:

- frames per second and latency on the real desktop, the cable against Wi-Fi:
  the window's bar shows both (pointer at the top edge), not yet read off it;
- Windows (WebView2) and Linux (WebKitGTK) — the latter may have no WebCodecs and
  would use the tunnel;
- the extended clipboard against the real Xvnc (1.13.1 on the phone): the
  stand-ins are written from TigerVNC's behaviour, not run against it;
- the video through a relay, and a path going direct mid-stream: Wi-Fi and
  mobile data are checked on the phone (above); a relay's bandwidth and the
  bitrate adapting to it only off the phone, over the real relays, with a
  stand-in LinuxStream on a Unix socket;
- the window daemon's own undo on a phone whose adbd outlives the cable (the
  S25's never did), and the A13 with this launcher.
