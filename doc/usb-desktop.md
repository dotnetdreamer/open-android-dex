# The desktop over adb — the phone's Linux at a local desktop's speed

*Phone Linux → Desktop* opens the phone's XFCE in a window on the computer
([remote-access.md](remote-access.md), "The desktop, in a window"). Through the
tunnel that is the phone's own viewer page — noVNC — and it is as fast as VNC
gets: Xvnc squeezes every change into JPEG and zlib on the phone's CPU, and the
page undoes it in JavaScript. Fine for a terminal and a menu; a scrolling page
or a moving window shows it.

With the phone on **adb** — the cable, or wireless debugging — the same window
gets the desktop another way: Xvnc's pixels raw over the phone's loopback, into
the phone's **hardware video encoder**, out as H.264 that the computer's GPU
decodes. The pointer is the computer's own, wearing the shape Xvnc sends, so it
moves at the screen's rate whatever the video does. The window chooses by
itself, and moves between the two as the cable comes and goes.

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
| Which way | `src/features/remote/DesktopRouter.tsx` | adb or the tunnel, and moving between them; and what is the window's either way — the clipboard, drops, what Linux sends |
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
shell (2000, which is adbd), root, or this app's own uid (which the remote-access
daemon is, for the day this rides the tunnel). It is RemoteProvider's rule:
anyone with adb already owns the phone, and nobody else gets a byte.

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

The bitrate is set by the window from the desktop's pixels: 0.18 bits a pixel at
60 Hz on a cable (10–100 Mb/s; 40 at 2560×1440), 0.07 on wireless debugging
(4–30). They are ceilings for motion; a desktop is mostly still, and still costs
nothing — Xvnc sends no update when nothing changed.

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

## Coming and going

The window opens over adb when the phone is on it, the tunnel otherwise. A
stream that ends without the phone saying why — the cable pulled — is looked for
on adb once more (a restarted service comes straight back the same way) and
otherwise goes through the tunnel. The desktop lives on the phone, so nothing in
it is lost either way. While on the tunnel the window probes adb every three
seconds, and moves back when the phone is there.

What the window owns rather than the route goes along unchanged: a copy in
progress, the cards in the corner, the clipboard's switch. Only the guest's
copy of this computer's clipboard has to be given again — Xvnc holds a
client's clipboard only while that client stays — and the new connection gets
it.

A DeX session on the same cable is another matter: pulling the cable ends it,
and a minute later the window daemon's dead-man switch undoes the session on the
phone, force-stopping the launcher — the Linux desktop with it. That switch now
sends the same "bring remote access back" broadcast the PC sends after its own
force-stop (`WmDaemon.cleanup`); before, a phone unplugged mid-session stayed
unreachable over SSH and the desktop window until something else restarted the
app.

## Verified, and not yet

The window in headless Chrome, off the phone, Tauri's IPC mocked and the adb
end a stand-in LinuxStream (41 checks, the same run as remote-access.md's):
the route chosen and the stream asked for at the cable's rate, the clipboard
and its switch over the stream, a drop on the video, the cable pulled in the
middle of a copy and the copy finishing on the tunnel, the clipboard given
again on the new connection, and the move back to adb within three seconds.

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
title naming the route.

Not yet:

- keys from a real keyboard (the mapping is tested; the window was looked at,
  not typed into), F11, and the cursor's shape in WKWebView;
- pulling the cable mid-session and putting it back — the router's move to the
  tunnel and back, and the title following it;
- Windows (WebView2) and Linux (WebKitGTK) — the latter may have no WebCodecs and
  would use the tunnel;
- the extended clipboard against the real Xvnc (1.13.1 on the phone): the
  stand-ins are written from TigerVNC's behaviour, not run against it;
- the same video through the tunnel when the phone is not on adb — the daemon
  would splice it to the same socket, as it splices websockify.
