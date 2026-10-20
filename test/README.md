# Tests

`openandroiddex-signal` is a protocol implementation, which makes it the one
piece of this project worth checking without a phone in your hand. It runs under
plain Node with no dependencies:

```sh
node test/signal-test.js
```

It starts the rendezvous on a spare port and runs a host and two viewers through
the exact message dance the phone and the browser do — including a viewer trying
to forge another viewer's id, a viewer closing its tab, and the phone
reconnecting to reclaim its room.

It is also how the half-open socket bug was found: a socket taken over from an
HTTP upgrade emits `end`, not `close`, so a viewer that shut its tab was never
reported gone and its room entry lived forever.

`openandroiddex-remote` is the other protocol worth checking off a phone, and
`remote-test.js` runs both of its ends against each other — over the real public
relays and address lookup, so it needs the internet, and with Rust and Node:

```sh
node test/remote-test.js
```

It plays LinuxService: runs the phone daemon, writes the computer list the way
the app does when a pairing arrives, and then logs in through the tunnel with
the system's own OpenSSH, on the host key the daemon generated and the
`~/.ssh` files the pairing wrote (under a scratch HOME — the real one is never
touched). Along the way: an unpaired computer turned away with a sentence, the
direct path and the relay-only one, 4 MB intact with half-close, a removal
closing a live session, scp, three sessions at once over the relay alone, the
daemon on its own resolver taking new DNS servers from a `net` and staying
reachable, a reopened code ending at its first deadline by the wall clock, and
a changed host key refused. Then files onto the phone with `dexremote put` —
SFTP inside the tunnel, against the same real sshd: a file and a folder byte
for byte, with their times and executable bit, a link inside the folder left
out and said so, a second and third drop named the way Thunar names copies
and the first one untouched, the relay alone, Ctrl+C taking back the folder it
was in the middle of, a host key other than the paired one refused before a
byte is sent, and every copy's session let go. And back with `dexremote
get`: the guest's script and Send To entry written, the script run as Thunar
runs it, a file and a folder fetched into a scratch Downloads byte for byte
(a large one in ranges), what had gone or was unreadable in Linux counted and
not fatal, "(2)" for a second copy, and the dock's icon sending to the
computer that is looking. Then the desktop, with `dexremote desktop` against
a stand-in websockify: refused with a sentence when there is no Linux, never
started unless asked, a start the app reports failed, the size held to what a
phone can carry, the password handed over, the page and 1 MB of WebSocket
through the tunnel as one session, requests from other sites and other host
names turned away, and Ctrl+C letting the phone go at once. And the desktop as
video, against a stand-in LinuxStream on a Unix socket: the phone saying it
has it, the address printed, the app's page through with the token kept here,
1 MB both ways, other tokens, origins and hosts refused, and a phone without
it refusing in a sentence.

The desktop over adb (`doc/usb-desktop.md`) has its own, with Node 24 and a JDK:

```sh
node test/usb-desktop-test.mjs
node test/usb-desktop-test.mjs --device [serial] [--size 2560x1440]
```

The first runs the window's H.264 handling and key mapping as they are (Node
imports the `.ts` files), and the launcher's own `LinuxStreamRfb` and
`LinuxStreamSocket`, compiled for this JVM, against a stand-in Xvnc in Node:
VncAuth, the encodings asked for, every sampled pixel, the cursor, a resize,
input, the clipboard — TigerVNC's extended one, UTF-8 both ways up to Xvnc's
256 KB, and Latin-1 against a server without it — the probe and the WebSocket. `--device` then runs the
stream classes on a phone under `app_process` as the shell user — nothing is
installed and the launcher is not touched — against a stand-in Xvnc on the
phone's own loopback that changes every pixel of every frame, and a headless
Chrome decodes the hardware-encoded stream with WebCodecs, sends input and a
resize, and prints the frame rate and where the time goes. It cleans up after
itself; the frame rate is checked only on a cable, since wireless debugging's
is the link's.

The Android desktop's own video (`doc/dex-video.md`) has one too, with Node
24, and for `--device` a JDK, the Android SDK and Google Chrome:

```sh
node test/dex-video-test.mjs
node test/dex-video-test.mjs --device [serial] [--size 1280x800]
```

The first runs the window's pure parts as they are: keys and characters as
Android takes them — text in any layout, AltGr and Option, Ctrl shortcuts by
the letter a layout types, a Mac's Cmd as Ctrl and its Cmd+C/X/V as Android's
Copy, Cut and Paste, its line and word moves — the mouse's buttons and wheel,
and a frame's age from the phone's clock. `--device` then runs the window
daemon's own dex on a phone as the computer runs it, `app_process` as the
shell user, on a display of its own (a DeX desktop that is running is not
touched): the display trusted and announced in scrcpy's words, the phone's
Settings opened on it, a headless Chrome decoding the stream with WebCodecs
through the app's `h264.ts`, a still desktop costing nothing, the wheel
scrolling Settings — the frame rate and the latency from the phone's
compositor printed — a resize the display follows, the clipboard both ways
with the phone's own put back afterwards, and the display gone when the
server's stdin closes.

Linux apps as windows of their own (`doc/linux-windows.md`) have their pure
parts checked the same way, with a JDK:

```sh
node test/linux-windows-test.mjs
```

It compiles the launcher's own `X11`, `X11Keys`, `LinuxWm` and `LinuxApps` for
this JVM and runs them: keysyms found in a us layout's columns, Shift and Level
3 where they are needed, a keycode lent to a keysym the layout lacks and never
taken back while held; the session's Xauthority cookie; the guest's xfconf
`xsettings` channel read as XSETTINGS names, the user's file winning, and the
XSETTINGS wire format read back byte for byte; damage rectangles clipped and
merged; and the `.desktop` scanner on a scratch rootfs — what the menu hides
left out, field codes dropped, a terminal app wrapped, `~/.local` overriding,
and icons found through links that name guest paths, and through links proot
wrote with this side's. The window manager against a real Xvnc needs a guest
and is recorded in the design record.

Everything else needs a device. `doc/web-viewer.md`, `doc/remote-access.md`
and `doc/usb-desktop.md` list what is still unverified there.
