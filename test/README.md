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

Everything else needs a device. `doc/web-viewer.md`, `doc/remote-access.md`
and `doc/usb-desktop.md` list what is still unverified there.
