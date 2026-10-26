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

The phone apps' windows (`doc/apps-on-pc.md`) keep their app when their
connection goes — the cable pulled with Wi‑Fi still there — and the phone's
half of that is checked on a phone or an emulator, with the Android SDK and
nothing of the app's running:

```sh
node test/apps-reconnect-test.mjs [serial]
```

It pushes the window daemon's dex (build it first: `openandroiddex-wmd/build.sh`,
or `npm run apk`) and runs the app server as the app does, then speaks the
window wire to it over adb: a window that waits for its computer and takes a
new connection, a second connection taking it from the first, a window closed
after it came back handing its app back, an app opened on the phone while its
window was away making that window "closed" to a computer that comes back, an
unknown window, the server stopping 30 s after its shell went with nobody
back, and "stop" ending it at once. It opens the phone's Settings in windows
of its own, and every one goes back to the phone by the end.

The phone's notifications on the computer (`doc/phone-notifications.md`) have
their phone's half checked the same way:

```sh
node test/notifications-test.mjs [serial]
```

It runs the app server from the dex as the app does and speaks its control
wire: notifications turned on (a snapshot, nothing already there as news, and
the calls ringing), asked again while on (everything again), the shell's own
`cmd notification post` as an app posting — news once, the same words again
nothing, new words at once an update and a moment later news again — a
conversation as its last messages, a sender's picture, a dismissal both ways,
a button on a notification that has gone refused and said, the test
notification, the listener stopping when the connection that asked goes
(hello first on the next, nothing it has not asked for, everything once it
does) and when another connection takes its place (the one replaced refused),
and notifications turned off. On an emulator it also texts its Messages app
(`emu sms send`) and rings its phone (`emu gsm call`), and reads what each
did back from the phone itself rather than from what the server says: a
window opened at a notification by its own intent shows Messages on the
window's display and not the phone's, and Messages has that conversation
read and another one not; a click with the window open, the same in that
window; a reply sent is in the phone's sent messages, to the one who wrote;
"Mark as read" leaves the text read; and a call answered from the computer
puts the phone in the call and goes in its call log as answered, one
declined goes in as declined, one the caller ends as missed — by CallStyle's
own answer and decline, or, on a dialer from before it (AOSP's, on the
Android 12 image), by its own buttons, chosen by their words. It uses the
app's own adb when there is one, so a phone a running app shares is not
dropped by an adb server of another version, and everything it posts is gone
from the phone when it ends; run it on an AVD started with `-read-only` to
leave the AVD's texts and call log as they were.

Everything else needs a device. `doc/web-viewer.md`, `doc/remote-access.md`,
`doc/usb-desktop.md`, `doc/apps-on-pc.md` and `doc/phone-notifications.md`
list what is still unverified there.
