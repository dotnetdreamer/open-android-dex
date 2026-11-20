# Linux in a browser: the phone's desktop on any computer's screen

LinuxOnDroid → Settings → **Open in a browser**. The phone then serves its
Linux desktop to any browser on the same network, and a laptop becomes Linux's
screen, keyboard and mouse with nothing installed on it: no app, no extension,
no account.

The picture is the phone's **hardware H.264**, the same stream the desktop app
gets over adb (usb-desktop.md): Xvnc's pixels raw over loopback, one GL draw
into the phone's video encoder, H.264 down a WebSocket, decoded on the
computer's GPU by WebCodecs and drawn the moment it leaves the decoder. No
video element, no jitter buffer, no player. The pointer is the computer's own,
in the shape Xvnc sends, so it moves at the screen's rate whatever the video
does. Linux's sound comes down the same connection and plays in the browser.

What a person does:

1. **Phone:** LinuxOnDroid → ⋮ → Settings → On a computer → *Open in a
   browser*. The card shows two numbered steps: the address
   (`192.168.1.20:7443`) and a six-digit code.
2. **Computer:** type the address. The page offers the secure connection
   first, and the browser warns once that it is not private (the phone signs
   its own); *Advanced*, then *Continue*. A browser that offers no way past
   that warning (a managed laptop's may not) can come back and choose
   *Continue without encryption*.
3. Type the code. Linux fills the window at the window's size. **Low**,
   **Medium** and **High** are at the top.

This record exists for the same reason the others do: several choices below
look arbitrary until you know what was measured, or what a browser refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| Vocabulary | `LinuxBrowser.java` | the switch, the code, the browsers let in (hashed), the state line, the phone's addresses; files in `files/browser/`, read by both processes |
| The certificate | `LinuxBrowserCert.java` | made by the phone, in DER; kept, with its key, until a month before it runs out |
| The door | `LinuxBrowserServer.java` | one port for both schemes, the page, `/pair`, `/forget`, `/stream`, who may ask and for how long |
| A look at the first byte | `LinuxBrowserPeek.java` | `recv(MSG_PEEK)`: TLS or plain HTTP, without taking it |
| The picture | `LinuxStream.java` | `serve` (a browser's session), `quality` (live), telling a browser it was replaced, the browser's sound on and off |
| The sound | `LinuxStreamAudio.java` | the guest's sound tap, read for a browser and shaped for its quality: PCM as it is, smaller PCM (downmixed, low-pass filtered, every second or third frame), or into an encoder; long silence not sent |
| Its Opus | `LinuxStreamOpus.java` | the phone's own Opus encoder (MediaCodec, Android 10 on), for Low and Medium |
| The phone's speaker | `LinuxAudio.java` | `hold`: quiet while a browser watches, the tap let go |
| The encoder | `LinuxStreamEncoder.java` | a picture smaller than the desktop, for Low |
| The handshake | `LinuxStreamSocket.java` | `accept`: the WebSocket upgrade without the loopback rules |
| The service | `LinuxService.java` | listening while the switch is on, the notification, starting the desktop as the switch goes on |
| Coming back | `RemoteResumeReceiver.java` | a boot or an update |
| The card | `LinuxSettingsActivity.java` | LinuxOnDroid only; the desktop build has the desktop app |
| The page | `assets/linux/browser/` | `index.html`, `viewer.css`, `viewer.js`, `h264.js`, `keysym.js`, `icon.svg`; `mse.js`, the picture over plain http |
| Linux's files | `LinuxFiles.java` | `/fs/`: listing, sending (resumable, and many small files to a request), bringing back (ranges, ZIP), the Trash, tickets; Linux's paths, its links followed as Linux follows them |
| Their pictures | `LinuxFilesThumbs.java` | small pictures of photos (HEIC too), videos, songs' covers and PDFs, with Android's decoders |
| Files, the page | `files.js`, `files.css`, `transfer.js`, `peek.js`, `fileicons.js` | the file manager, the transfers and their panel, Quick look, kinds and icons |
| Check | `test/linux-browser-test.mjs`, `test/linux-browser/` | the server on a JVM and the page in a real browser; `--device`: the same on a phone |
| Check, Files | `test/linux-files-test.mjs` | the same server over a stand-in Linux, from Node and in a browser window |

## Why this picture, and not another

Every browser route to a remote desktop that exists was weighed against what
the desktop app already measured on the cable: 45–55 fps at 2560×1440, 12–15 ms
in the encoder (usb-desktop.md, "Verified").

- **noVNC**, the phone's own window: Xvnc compresses every change into JPEG
  and zlib on the phone's CPU, which is also drawing the desktop, and the page
  undoes it in JavaScript. Fine for a terminal; a scrolling page shows it.
- **WebRTC**: a jitter buffer and congestion control built for lossy networks,
  added delay on a LAN; when this project showed the Web viewer's WebRTC in the
  desktop app's window, it was visibly laggier than a raw socket. `vnrit`, the nearest thing
  on GitHub (an X11 desktop to a browser, Android's MediaCodec on Termux), is
  WebRTC through GStreamer, 24 fps and 500 kb/s by default.
- **Media Source Extensions** (H.264 into a `<video>`): plays on plain http,
  but through the media pipeline's buffering, and a decoder that waits to
  reorder frames the stream never reorders.
- **MJPEG** (ScreenStream's local mode): JPEG on the phone's CPU per frame, and
  several times the bandwidth.
- **KasmVNC**: the best of the VNC servers, WebP/JPEG on the CPU, and the
  precedent for named quality presets.

H.264 out of the encoder block, to WebCodecs, is the shortest path a browser
offers, and the one piece of it that did not exist here was a door a browser
can come through.

## Why HTTPS, and the one warning

**WebCodecs is offered to secure contexts only, and a page on a home network
is not one over plain http.** Measured 2026-11-19 in Chrome 157 and Edge 157
(test/linux-browser-test.mjs does it again every run), a page on
`http://192.168.2.34`: `isSecureContext` false, `VideoDecoder` undefined, no
clipboard API, no keyboard lock. The same page over https with a self-signed
certificate, after a person's *Advanced* and *Continue*: `isSecureContext`
true, `VideoDecoder` there, H.264 supported in hardware, `wss://` to the same
address open. (A wiki elsewhere says clicking through grants no secure
context. In Chromium it does.)

It also keeps every key typed and every pixel off the network in the clear,
which matters on a café's Wi-Fi.

**The certificate is the phone's own.** No authority signs for 192.168.x.x,
and the ways around that need a server of ours or a private key published to
everyone. So the browser warns once per address, and the phone's card says so
next to the address. What the certificate is, so that no browser refuses it
outright: X.509 v3, EC P-256, SHA-256; the phone's addresses and `localhost` in
subjectAltName (Safari and Chrome read names only there); serverAuth; not a CA;
397 days, under the 398 Apple allows. Android has no public API that makes a
certificate, so `LinuxBrowserCert` writes the DER itself and verifies it
against its key before keeping it.

It is kept, with its key, until it has a month left, whatever happens to the
phone's addresses. A certificate made again for each new address would bring
the warning back at every address a browser had already said yes to, and teach
people to click through it, which is all a man in the middle needs. An address
it does not name is one more fault in the same warning: measured 2026-11-19 in
Edge 157 with a certificate naming another address, the warning was the same
one (`NET::ERR_CERT_AUTHORITY_INVALID`), *Continue* was offered, and past it
the page was a secure context with H.264 in WebCodecs and `wss://` open. It is
named LinuxOnDroid, not after the phone: anyone on the network can read a
certificate, and a phone's name is often its owner's.

**And plain http, for a browser that will not.** Some browsers offer no way
past the warning at all: a managed laptop's Edge said *You can't visit
192.168.2.25 right now* with no *Advanced* (`NET::ERR_CERT_AUTHORITY_INVALID`,
most likely a policy against clicking through). So the same port also serves
the page over plain http, and the page starts there when an address is typed
without a scheme. It looks for the secure address first: a request to it over
https that comes back means this browser has taken the certificate already,
and the page goes there by itself. One that fails offers a choice, *Continue
securely* first; Back comes to it again if the browser refuses. *Continue
without encryption* is remembered in that browser. What plain http costs:

- **No WebCodecs.** The picture goes through the browser's own video player
  instead (Media Source Extensions, `mse.js`): each frame a fragment of its
  own, appended as it lands, in a timeline with no gaps when the desktop is
  still. A player keeps a little in hand to play smoothly, and that is the
  price: measured 2026-11-20 in headless Edge with its frame-rate limit lifted
  (`--disable-gpu-vsync --disable-frame-rate-limit`, about 58 Hz), one 1280×720
  60 fps H.264 stream, a frame was due on screen a median 53 ms after it
  arrived through the player (its own expected display time), 21 ms through
  WebCodecs (the frame after it was drawn): three of four runs; the fourth
  started with a backlog it took seconds to work off, which is why the player
  now goes to the newest frame as it starts playing. The player keeps about
  three frames: more is worked off by playing faster (1.08× past 80 ms, 1.25×
  past 200 ms), and over 0.6 s it skips to the next key frame, which it asks
  for. Two other ways were measured and not kept. Frames a millisecond long:
  by the player's own account each was shown about a millisecond after it
  landed with the frame-rate limit lifted, but at the 34 Hz headless Edge
  draws at otherwise it showed three frames a second. Chasing the newest
  frame: the player ran dry on every frame and lost a quarter of them.
- **No clipboard API, no keyboard lock.** Paste needs neither (the `paste`
  event). Linux's copies reach the computer by the browser's own copy at the
  next click or key in the page, and the page says so. Ctrl+W and Alt+Tab stay
  the browser's in full screen.
- **Nothing is encrypted**: the desktop, the keys, the token. The bar says *Not
  encrypted*, the More menu offers *Use the secure connection*, and the
  phone's log names such a browser `not encrypted`. The card says what the
  choice costs.

The two are separate doors: a page's requests must come from its own origin,
scheme and all, so the http page cannot pair or stream through TLS, nor the
https page through plain http. A token is a token through either.

**One port, both schemes.** People type `192.168.1.20:7443`, and the browser
asks over plain http. The first byte of every connection is looked at without
being taken: 0x16 opens a TLS handshake, anything else is plain http, and both
are served the same way. Android's `SSLSocketFactory` has no way to be handed a byte already read (Java
8's `createSocket(Socket, InputStream, boolean)` is not on Android; the build
fails on it), so the phone peeks: `recv` with `MSG_PEEK` on a duplicate of the
descriptor, after a `poll` that bounds the wait (`LinuxBrowserPeek`). The card
shows the address without the scheme for that reason: it fits on one line, and
it works typed, the page offering the secure connection first. Copy gives the
full https link.

## Who gets in

- **Only the phone's own networks.** A connection is answered only if it came
  in on an address the phone offers (its Wi-Fi, its hotspot, a cable, a VPN
  with private addresses) and from a private address (10/8, 172.16/12,
  192.168/16, link-local, 100.64/10, IPv6 ULA and link-local). Anything else
  is closed before a byte is read: mobile data, where a carrier's other
  subscribers can share its private range; a public address, which would put
  Linux on the internet; and loopback, which every app on the phone can reach.
- **A code, once, for a token.** Six digits, shown only on the phone, and
  working only while the card shows them and for ten minutes after. A browser
  trades them through `POST /pair` for a 256-bit token it keeps; the phone
  keeps only the token's SHA-256, compared in constant time. A code lets one
  browser in and is used up. Ten wrong codes against it, from anywhere, use it
  up too: a one-in-a-hundred-thousand chance in its whole life. A limit per
  address cannot bound guesses, since an app on the phone or a peer on the
  network can come from as many addresses as it likes; the code's own budget
  can. The card then says so, with *Make a new code*. Five wrong codes from
  one address also lock that address out for 30 s, doubling to ten minutes.
  Pairings are taken one at a time, so many connections at once get no more
  tries than one.
- **The token comes with the WebSocket's request** (`/stream?t=`) and is
  checked before a frame is read: a page without one is told so and closed.
  Closed gently, as every last word is: whatever the page still sends (its
  hello, a ping) is read and dropped until it closes its end, up to a second.
  Closed over bytes unread, TCP sends a reset, and a reset can take the last
  word with it: the page then says only that it cannot reach the phone.
  The phone's name is told only to a browser with a token (`/hello` with
  `Authorization: Bearer`): anyone on the network may ask, and the name is
  often its owner's.
- **An address in `Host`, not a name.** A page elsewhere that points its own
  name at the phone (DNS rebinding) gets a 421. Every request that changes
  something, and the WebSocket, must carry this origin's own `Origin`.
- **Nobody holds the door.** Every connection has 30 s to be let in, however
  slowly it sends, and one address may hold eight at once. A stream that says
  nothing for 75 s is let go, and the wake lock it held with it: the page
  pings every two seconds, and a hidden tab's timers can slow to once a
  minute.
- **The page is the APK's**, never the guest's: root in Linux can rewrite the
  guest's web root, and the page holds the token. It is served with a policy
  that lets it load nothing but its own files (`script-src 'self'`, no inline
  anything, `frame-ancestors 'none'`).
- **Out again:** *Forget this browser* in the page's menu. *Sign them out* on
  the card makes a new code, ends a browser watching now, and closes every
  connection not yet a stream. Turning the switch off closes everything, then
  tells a browser watching. Uninstalling Linux turns it off; the browsers let
  in are kept outside the container, as remote access keeps its computers.
- **No gap to slip through.** A browser being let in at the moment of a
  sign-out or the switch going off is in neither place those look: its token
  is checked and it is no longer one of the connections they close, but it is
  not yet the stream that watches. So the stream asks again as it takes the
  desktop (`LinuxBrowserServer.Gate`), under the lock a sign-out reads the
  watching stream by: whichever comes first, the browser is turned away or
  found and ended. A pairing likewise checks the code and lets the browser in
  as one step, so a sign-out is wholly before it or wholly after.

## Low, Medium and High

| | The picture | Bits a pixel at 60 Hz | Floor–ceiling | At 1920×1080 | The sound |
| --- | --- | --- | --- | --- | --- |
| **Low** | two thirds of the desktop each way, scaled back up smoothly | 0.05 of its pixels | 1.5–8 Mb/s | 2.8 Mb/s | Opus 32 kb/s mono (else PCM 16 kHz mono, 256 kb/s) |
| **Medium** | the whole desktop, one pixel for one | 0.08 | 4–25 Mb/s | 10.0 Mb/s | Opus 96 kb/s stereo (else PCM 24 kHz stereo, 768 kb/s) |
| **High** | the whole desktop, one pixel for one | 0.2 | 10–60 Mb/s | 24.9 Mb/s | PCM 48 kHz stereo as Linux makes it, lossless, 1,536 kb/s |

Medium is the desktop app's own figure for Wi-Fi. High is two and a half
times it, where small text stops softening while it scrolls. Low is less of
everything at once: fewer pixels to encode, to send and to decode, which is
what keeps a slow link from falling behind. They are ceilings for motion: a
still desktop costs nothing, and the phone still lowers the bitrate under any
of them when the link falls behind (`LinuxStream.adapt`), so a frame is
dropped before one is late. The choice is the browser's, remembered there.

**The sound goes with the picture.** Opus where both ends have it: the
phone's own encoder (Android 10 on) and WebCodecs' AudioDecoder in the page,
which browsers give only to https pages. Over plain http, or on an older
phone, Low and Medium are PCM made smaller instead: the two channels averaged
into one for Low, and every second or third frame kept after a low-pass
filter (63 taps, a windowed sinc at 0.42 of the new rate), so that what the
new rate cannot carry is taken out, not folded back as noise: measured, a
12 kHz tone comes out of Low's 16 kHz at a thousandth of itself, a 3 kHz one
whole. High is the tap as it is, at every page: its picture already spends up
to 60 Mb/s, the sound is a small share of it, and nothing is lost or added in
the time an encoder takes. Low's Opus is mono because Low is for the slowest
link; at 32 kb/s, stereo would take bits from each ear for little.

**Switching is live.** The page sends `{"t":"quality"}`: the bitrate lands at
the next frame; a new size makes a new encoder on the pump's thread at its next
update, with a whole picture asked for at once, so a desktop where nothing
moves changes too. No reconnect, no gap in the picture.

## The page

- **The desktop follows the window**, in CSS pixels, half a second after a
  resize settles; asked once, not again for a size the phone held to its
  limits.
- **Keys go as keysyms**, the desktop app's rules (`keysym.js`): typing types
  the computer's layout, shortcuts are the key's place, a Mac's Option types.
- **Paste is the browser's own.** Ctrl+V lets the browser paste, and its
  `paste` event carries the computer's clipboard with no permission asked; the
  text goes to Linux first, then the keys that paste it there. What Linux
  copies lands on the computer's clipboard. A switch on the bar stops both, and
  takes back what the computer gave Linux.
- **Full screen** asks for keyboard lock where there is one (Chrome, Edge):
  Alt+Tab, the Windows key and Ctrl+W go to Linux; holding Esc leaves.
- **The screen stays on** while something moves, as a video player keeps it;
  two minutes after the last frame it may sleep again.
- **The bar** waits above the top edge and comes down when the pointer touches
  it, with the phone's name, the three pictures, frames a second, bitrate and
  round trip, the clipboard, full screen and a menu.
- **Sound.** Linux's sound plays in the browser (Web Audio, on any page,
  http too), in the form the quality gives it (above): the phone says which
  before the first run and at every change, and Opus goes through WebCodecs'
  AudioDecoder. Each run is played a little ahead of the clock, as much in
  hand as the gaps between runs ask for, from 40 to 350 ms; a run that would
  play far behind its picture is dropped instead. A browser lets a
  page start sound only after a click or a key, so a page brought back by a
  reload says *Click the desktop to hear Linux* and asks for none until then.
  The speaker on the bar turns it off, remembered: off is nothing read on the
  phone and nothing sent, which is the performance a person asked for.
- **Leaving asks first.** Closing the tab, reloading or going elsewhere while
  Linux is on screen brings the browser's own *Leave site?*, as a page with
  unsaved work does. Not on a card, and not when the page goes to the secure
  address itself.
- **Every way it ends is said**, over the last picture, dimmed: Linux not
  running (*Start Linux*), opened in another window (*Show it here*), switched
  off on the phone (*Try again*), not yet installed, still installing, not
  finished, failed, signed out on the phone (the code again, at once). A
  connection that drops with no word comes back by itself; a phone that cannot
  be reached is said after three tries for a tab that never saw the desktop,
  eight for one that did, with *Try again* and *Reload the page* (for a
  certificate the browser has forgotten).
- **Only a person starts Linux.** A comeback after a drop, a reload, a tab the
  browser brings back: each shows the desktop if it is running and otherwise
  says it is not, with *Start Linux*. Only the code typed and *Start Linux*
  start it, so a desktop someone logged out of is not booted again behind
  them.
- **The page lets go of a stream it is told is over,** and of the frames still
  in its decoder: otherwise a frame decoded after the word drew the desktop
  back over the card saying so (found by the test under load, then forced on
  every run: CPU slowed 8× and three frames sent right before the word).

## The phone

- **The switch keeps the service up**, in the foreground and sticky, like
  remote access: the notification gives the address, with *Address and code*
  and *Turn off*, and says *Linux is open in a browser* while one watches. A
  boot or an update brings the listener back.
- **The port is 7443, always.** If another app holds it, the card says so in
  place of the steps, rather than moving to a port no bookmark knows: an
  address and a code there would send a person, and the code, to that app.
  Switched off and on again, the card starts afresh.
- **The card shows a code only while it can work**: not while it is used up,
  with no network, or with nothing listening. Showing it is what keeps it
  open.
- **Switching it on starts the desktop**, if Linux is ready and not running, at
  the size it last had, sound off as for any computer. Then rather than at the
  browser's first connection: the browser finds it ready, and on Samsung's
  phones it is born on the big cores. One UI moves an app with no window on
  screen to the small ones about 12 s after its last window goes, a desktop
  started there stays there, and LinuxOnDroid has no WRITE_SECURE_SETTINGS to
  turn that off (remote-access.md, "Samsung's background slowdown").
- **One viewer at a time**, as before: a new one takes the desktop. A browser
  that loses it is told, and waits to be asked; a computer's window is closed
  as it always was. So a browser and the desktop app never take it back and
  forth.
- **Sound goes to the browser.** While a browser watches, the phone's own
  speaker is quiet and lets go of the guest's tap (`LinuxAudio.hold`), whether
  that browser has sound on or not: the person is at the computer. A desktop
  started for a computer or a browser has no phone speaker anyway (a video on
  a laptop must not come out of a phone in a bag); the browser reads the tap
  itself (`LinuxStreamAudio`). Gone, the phone's speaker comes back if it had
  one.
- **Opus is made on the phone**, by Android's own software encoder
  (`c2.android.opus.encoder`, there from Android 10), fed on the sound's own
  thread as each run is read: a packet each 20 ms. A new quality makes a new
  encoder at the next read. An encoder that cannot be made leaves that
  quality as PCM, and the page is told so.

## Files

**Files** on the bar shows Linux's files instead of the desktop's picture: a
file manager in the page, with places down the side (Home, the folders
`~/.config/user-dirs.dirs` names, the phone's shared folder where the build has
it, all of Linux at `/`, the Trash), the path across the top, a list or a grid,
and the keys and the mouse doing what they do in a desktop's file manager.
Files dropped onto it, or whole folders, are copied to Linux; files dropped onto
the desktop's picture land in `~/Desktop`, where Linux's desktop shows them.
`#files` at the end of the address opens Files straight away, and the cards
that say the desktop is not there (stopped, replaced, disconnected) offer Files
instead.

**Off the phone's storage, not through Linux.** The guest's `/` is a folder of
the app's (`files/linux/rootfs`), and proot runs as the app's own user, so the
server's process reads and writes it directly (`LinuxFiles`). Nothing is
encoded and nothing waits for Linux, which need not be running at all: a
person can fetch a file from a Linux that is shut down. Opening Files lets go
of the picture's stream (the phone stops encoding; Linux keeps running), so the
Wi-Fi is the files' alone. Where Linux sees a folder of the phone's (proot's
`-b`), Files sees it there too: `/tmp`, `~/storage`, and the shared folder at
`~/Desktop/LinuxOnDeX`.

**Linux's paths, and only Linux's.** The page names guest paths
(`/root/Documents`); the server turns each into a file of the phone's one
name at a time, following every symbolic link the way Linux would: an absolute
link starts again at the guest's `/`, `..` stops there, forty links at most,
and a link proot made itself (a path of the phone's into the rootfs, which is
what `--link2symlink` writes) reads as the guest path it is. So no link in
Linux reaches outside it, whatever it says. `/proc`, `/sys` and `/dev` are the
phone's own, bound in: they are not listed and cannot be opened. `/`, the
folders right under it, the home folder, the Trash's folders and every bind's
mount point cannot be renamed, moved or deleted from a browser; inside them,
anything can, as in a terminal.

**Who gets in** is who gets the picture: the browser's token, in
`Authorization` on every request. A download the browser makes by itself (an
`<a download>`, a video's `src`, a PDF in a frame, a file dragged out to the
computer's desktop in Chrome and Edge) cannot carry that header, so the page
first asks for a ticket: 24 random bytes, for those files only, for six hours,
and only while that browser stays signed in. A ticket in a downloads list
opens nothing else. Every change comes from the page itself (`Origin`); a sign
out, or the switch going off, closes every transfer under way along with the
tickets. A file served for the page to look at always comes with a sandbox
(`Content-Security-Policy: sandbox`), and text-like files as `text/plain`: an
HTML file of Linux's shows as its text and never runs as this page, which holds
the token. The one exception is a PDF, which the browser's own viewer draws.

**Sending.** A file of 8 MB or more is one `PUT` into a hidden part file beside
where it goes (`.lodpart-` and a hash of its name, size and time), renamed into
place once whole. Cut off (the Wi-Fi, a pause, a sleeping laptop), the page
asks what arrived and sends the rest from there. Smaller files go many to a
request (`POST /fs/batch`): a `Blob` made of record heads and the `File`s
themselves, which refers to them rather than reading them, so a folder of a
thousand photos is a few requests rather than a thousand round trips. A file
already there with the same size and time (to the two seconds a FAT folder
keeps) is not written again: sending something twice is cheap, and a retry
after a cut never makes copies. Three requests run at a time. A name already
taken in the folder is asked about once, for all of them: Replace (folders
merge), Keep both ("photo (2).jpg", picked as the phone would), or Skip. A
file keeps its time. The phone stays awake while anything moves (the same
wake lock as a stream).

**Bringing back.** One file is the browser's own download, with `Range`, so a
download resumes and a video seeks. Several, or a folder, are one ZIP made on
the way: stored, not compressed (most of what people move is compressed
already, and the phone keeps up with the Wi-Fi), with each file's CRC-32 worked
out just before it is sent, so the ZIP's length is known before its first
byte and the browser shows the time left; ZIP64 where a size or an offset
needs it. Chrome and Edge can also save into a folder of the computer's
(`showDirectoryPicker`), folders and all, each file streamed to the disk with
its progress in the page.

**Deleting** moves to Linux's own Trash, laid out as freedesktop.org's spec
says (`~/.local/share/Trash/files`, and an `info/*.trashinfo` with where from
and when), which is where Linux's file manager looks; Undo is a rename back.
What is in a bind (the phone's shared folder) cannot go there (on a phone it is
another filesystem), and the page asks before deleting it for good.

**Seeing.** Quick look (Space) shows a photo, plays a video or a song from the
phone as it streams, opens a PDF in the browser's own viewer, and shows the
first 512 kB of text or code with line numbers. A picture the browser cannot
draw (a HEIC from a phone's camera) is shown through the phone's decoder
instead. The grid's small pictures come from the phone too
(`LinuxFilesThumbs`), two at a time, about the size they are shown.

## Verified, and not yet

Off the phone (`node test/linux-browser-test.mjs`, 204 checks). On
2026-11-19, after an independent review's twelve findings were fixed, the
first 140 passed in two runs in a row; on 2026-11-20, after a second review,
all 144 passed on the final code; with plain http, sound and the question on
leaving, all 186 passed that afternoon; and with the sound following the
quality, all 204 passed on the final code. Of the runs on the way: three
passed whole; three lost only the frame-rate check below, at 39, 30 and 32
a second with the computer busy (as it was lost before these changes too),
and it now takes the middle of three seconds; the first two hung once each
where a plain page is opened again (see Not yet); one lost a check of the
stand-in phone's own (it sent no sound bitrate in its stats, as the phone
does), and one a check whose seconds the new frame-rate count had taken:

- the page's H.264 and key handling under Node, and the launcher's server
  compiled for a JVM with no Android classes in reach;
- the door, from Node: plain http served too, with the page's policy
  (`media-src blob:` its one addition) and the player's script; the
  certificate's names, days and signature, files that are not there, no phone
  name without a token, nothing at all from loopback, a name in Host, another
  site's `Origin` with the right code, a body in chunks;
- the code: refused while the card is not showing it; a wrong code; the
  lockout at the fifth; a right code once (the same digits again are wrong,
  and the phone has a new one); seven wrong codes at once from one address
  (four told wrong, three locked out); thirty, six at a time (nine told wrong,
  the rest told the code is used up, then even the right one refused); a new
  code working;
- holding the door: a ninth silent connection from one address closed
  unread; a request sent a byte every 200 ms closed at its deadline; a
  connection kept open let go the moment the switch goes off, and nothing
  listening until it is on again; another server on the port said as
  `failed port 7443`;
- the stream: another site's page refused even with a token; no token, or one
  the phone never gave, told so and closed with the hello never read; a
  message in 1,100 pieces; a ping of 126 bytes; a stream let in and then
  silent, let go at its time, and the phone then saying nobody is watching;
- plain http, from Node: the plain page pairing through TLS refused, and the
  secure page through the plain door; the right code over plain http, a
  token, the phone's name; another site's and the secure page's streams
  refused at the plain door; `ws://` with the token, the picture, and the
  phone's log saying *not encrypted*;
- the sound's shapes: pure tones through the shaper, 440 Hz and 3 kHz whole
  at Low's 16 kHz and 12 kHz filtered out (0.0009 of itself), 440 Hz and
  5 kHz whole at 24 kHz and 15 kHz filtered out, High's 48 kHz as it is;
- sound, through the launcher's own LinuxStreamAudio reading a stand-in tap
  (a 440 Hz second, a silent second, over and over): a browser that does not
  ask gets none and the tap is not opened; asked in the hello at High, the
  form said first, then PCM in whole sample frames; long silence not sent
  (three quarters of real time, a gap of about half a second each round);
  Medium and Low for a page without Opus, 24 kHz stereo and 16 kHz mono, at
  half and a sixth of High's bytes; for a page that decodes Opus, Opus 96 kb/s
  stereo and 32 kb/s mono, one packet each 20 ms (libopus's packets, through
  the relay's own encoder path, a stand-in only for MediaCodec), the stereo
  encoder closed for the mono one; High still PCM; off, the tap let go and
  nothing more; on again; the browser gone, the tap let go;
- while a browser is being let in (held there, `slowhandoff`, sending a ping
  as the page does): signed out, it is told so as it would take the desktop
  and never watches, and the phone does not say it is watched; switched off,
  the same; and a sign-out right behind a right code, eight times, with the
  store's lock held by a process of its own so that the pairing waits inside
  its code's check and the sign-out waits behind it: the browser let in is
  signed out with the rest;
- then Edge headless on this computer's network address, from the plain page
  offering the secure connection, then the browser's own warning onwards: the
  code; the picture decoded and drawn one pixel for
  one at sixty frames a second; acknowledgements; the pointer's shape; the
  bar; Low, Medium and High live; clicks, buttons, the wheel, keys; F5 kept
  from the browser; the clipboard both ways with every character whole; the
  switch off and on; a resize asked once; a reload back without starting
  Linux; every way it ends, signed out (said at once), switched off and a
  phone a new tab cannot reach (three tries) among them; and what the code
  card says when the phone is not showing the code, when it is used up, and
  when this address is locked out; the sound following Low, Medium and High on
  the bar (Opus decoded by WebCodecs, High's PCM), its form in the bar's
  numbers; a reload asking first (*Stay* keeps the
  desktop, *Leave* reloads); the plain address, once that browser has taken
  the certificate, going to https by itself;
- and a second Edge that has never taken the certificate, over plain http:
  the choice; *Continue without encryption* kept; the code; the picture
  through the browser's video player at its size; *Not encrypted* on the bar
  and *Use the secure connection* in the menu; a click through the picture to
  where it was made; sound playing after a person's action, off (the tap let
  go, nothing more played, kept) and on again; no Opus there, and each
  quality its PCM, 24 kHz stereo, 16 kHz mono, 48 kHz stereo; Linux's copy carried by the
  browser's own copy at the next click; leaving asked first; back on the plain
  address straight to the desktop; *Use the secure connection* leaving without
  a question.

Red, then green, for a race found while writing those checks: a stream that
ends before it has been handed over (the harness ends three in a row the
moment they begin). With the old order the phone kept saying a tab long gone
was watching (`watching … 4 …`, while tab 1 still was); now it names the one
still watching.

Red, then green, for the gaps the second review found (one) and the checks
for it turned up (two). The red runs were the product as it stood before the
fix with the new checks, their harnesses changed only to the old signatures:

- **A sign-out or the switch, while a browser is being let in.** From its
  token's check until LinuxStream took it, a stream was in neither place
  those look. On the old code the browser signed out or switched off there
  streamed anyway (`running`, `video`, frames) and the phone said it was
  watched. Now the stream asks its `Gate` as it takes the desktop.
- **A sign-out between a right code and its token.** The code was checked in
  one locked step and the token added in another. On the old code, eight
  rounds out of eight left the phone letting a browser in after the
  sign-out. Now the two are one step.
- **The last word lost to a reset** (found on the phone, below): a stream
  turned away was closed with the page's ping unread, TCP reset the
  connection, and the page, never reading "off", said it could not reach the
  phone. Every last word now closes gently (`closeAfterWord`).

The Settings card (Robolectric, 13 tests, all passing, in the repository:
`linuxapp/src/test`, run by `./gradlew :linuxapp:testDebugUnitTest`): off by default; on
starting the service and drawing the two steps, with the code's ten minutes
opened; the state line followed: waiting, watching, the port taken (said in
place of the address and the code), another failure, listening again; a
failed start forgotten when switched off and on (red without its fix, then
green); a used-up code, not shown and opening nothing, and *Make a new code*;
a second address; no network; a phone on mobile data with a VPN, its address
offered and named; which interface names are offered (the S25's mobile data,
`rmnet_data0` at 192.0.0.2, never) and which peers may connect (Tailscale's
100.64/10 and its IPv6 range yes, the carrier's 192.0.0.2 no); copying;
signing every browser out, which tells the service; pictures in English,
German, Arabic (right to left), Hindi and Chinese, narrow and wide, with the
port taken and with a code used up (saved with `-PbrowserShots=<folder>`).
The rest of LinuxOnDroid's Robolectric checks (123) pass with this change;
those are another piece of work's, kept outside this one, and run here only
to see that nothing broke. The desktop build still has no card (checked
2026-11-19, not run again since).

### Measured on the S25

2026-11-19, Galaxy S25 Ultra (SM-S938B, Android 17) over Wi-Fi, its screen
off (dozing), Edge 157 headless on a Windows computer on the same network.
`node test/linux-browser-test.mjs --device <serial>`: the launcher's classes as
LinuxOnDroid runs them (LinuxBrowserServer, LinuxBrowserPeek, LinuxStream and
its encoder), started by `app_process` as the shell user, nothing installed;
the desktop test/usb-desktop/StandinXvnc, 1920×1080, every pixel changing
every frame. 36 checks, all passing in each of three runs, the third after the
review's fixes; then 42, after the crash below, with the six that sign out
and switch off from a main thread that, like an app's, dies on any network
use (red on the old code: the harness was killed at the sign-out; green after
the fix); then, on 2026-11-20, 49, with a browser held between its token's
check and LinuxStream while the phone signs out, and again while it switches
off. On the old code both streamed (`streamed: true`; the switch-off one from
a run with the sign-out left out, since a stream that should have ended
leaves the page in no state to try the next). With the `Gate` alone the
switch-off one streamed nothing, but the page said it could not reach the
phone: the "off" had gone down with a reset. With the gentle close, 49 of 49:

- signed out, or switched off, while being let in: turned away as it would
  take the desktop and told so, in the page's own words, the phone process
  alive after each;

- plain http answered with the way to https, by a first byte the phone looked
  at without taking; the page over the phone's own TLS; the certificate the
  phone made, read back with its address, its signature and its fingerprint;
- the browser's warning, *Advanced*, *Continue*, the code, and the phone's
  hardware encoder (`c2.qti.avc.encoder`) on screen;
- each mode's ceiling where it lands, in the phone's log: Low 2.8, Medium
  10.0, High 24.9 Mb/s; Low's new encoder at 1280×720;
- a click and a key at Xvnc, both clipboards, a resize to a new encoder, and a
  second tab taking the desktop with the first told and neither taking it back.

What the link carried, the median of four seconds after six of settling, in
three runs (High first in each; the first two twenty minutes apart, the third
later that evening):

| | Frames a second | Mb/s | Round trip | Encode on the phone | Decode here |
| --- | --- | --- | --- | --- | --- |
| High | 43 · 11 · 30 | 2.8 · 24.5 · 3.8 | 53 · 330 · 531 ms | 13.3 · 30.9 · 13.9 ms | 1.1 · 5.1 · 1.1 ms |
| Medium | 40 · 40 · 46 | 2.1 · 5.8 · 1.6 | 386 · 346 · 260 ms | 29.6 · 15.0 · 14.6 ms | 1.4 · 1.8 · 1.8 ms |
| Low | 46 · 48 · 53 | 2.9 · 3.1 · 3.5 | 120 · 94 · 152 ms | 13.9 · 9.5 · 9.0 ms | 1.0 · 1.2 · 0.9 ms |

Read with care: this is the hardest picture there is, on the slowest link a
browser will meet, a phone whose Wi-Fi sleeps between beacons, and the harness
holds no wake lock (the service does, while anyone watches). The phone cut the
bitrate for a link behind 22, 25 and 25 times in the three runs, so mostly
what flowed was the link's, not the mode's, and the round trip swings with the
Wi-Fi's naps. Where the link did carry High's 24.5 Mb/s, every picture was so
large that the frames came eleven a second: High spends the time on the
picture, Low on the frames. Low kept 46–53 frames a second in every run, the
most of the three modes each time, and had the shortest round trip in two of
the three (High had it in the first). A desktop at rest sends nothing at all,
and a real one changes a small part of its pixels.

**The window was tried longer and not kept.** Holding twice the frames in
flight for a browser (24, 400 ms) was measured against the 12 every computer
has, on the same phone in the same state: 52–55 frames a second against
38–46, but round trips of 240–473 ms against 124–265, and High stalled. The
link is short of bandwidth as well as slow to answer, so more frames only
queue. Latency first: the 12 stays (`LinuxStream.MAX_WINDOW`).

### The app itself, on the S25

2026-11-19, 21:25–21:50: LinuxOnDroid built from this change, installed over
the dev build on the S25 (its Linux kept), and driven as a person would. The
phone's own screen was tapped by adb, each tap only after reading that the
phone was awake, unlocked and showing the expected page. The computer was Edge
157 headless on Windows; the owner also paired Chrome on macOS by themselves,
unprompted, before the test reached that step.

- **The switch:** Settings showed *On a computer* first. Switched on, the
  service started in the foreground, listened on `[wifi 192.168.2.25]`, and
  started the desktop on "6 of 8 cores" at its last size (926×1774). The card
  showed the address, the code and *Waiting for a browser*; the notification
  read "Open 192.168.2.25:7443 in a browser on the same Wi-Fi", with
  *Address and code* and *Turn off*.
- **The browser:** the address typed without https went to https. The
  warning, then *Continue*, gave a secure context with H.264 and the code
  card, the phone's name unsaid. The owner's Mac used the code first, and so
  the test's typing of it was refused, as a used code is. The next code let
  it in: the tab took the phone's name, the desktop resized to the window
  (1920×1080), and the card said *Showing in Edge on Windows · 192.168.2.34*
  with the code made new.
- **Input:** a right-click opened XFCE's desktop menu exactly where it was
  made; a terminal opened, was maximized, and took typed commands.
- **Scrolling text, full screen** (`base64 /dev/urandom`, phone awake): Low 28
  frames a second, 2.8 Mb/s, 33 ms round trip; Medium 25, 12.6 Mb/s, 105 ms;
  High 28, 6.2 Mb/s, 60 ms; encode 6–10 ms, decode under 1 ms. The terminal
  itself redraws about that often.
- **YouTube** (Big Buck Bunny, 60 fps, in Linux's Chromium after its consent
  page): in the page's player, about 40 frames a second drawn by the page in
  every mode, over two runs; in theater mode, Low 42 frames a second at 1.7
  Mb/s, Medium 43 at 6.5, High 39 at 11.8, round trips 20–29 ms, the picture
  clean. These are the frames that reached the browser; what Chromium inside
  Linux itself painted was measured the next night (below). YouTube's own
  full screen (its button, its `f` key) did not take inside Linux's Chromium,
  so a full-screen video was not measured.
- **Sign out and switch off: a crash, found here and fixed.** Both crashed
  `:linux` with `NetworkOnMainThreadException`, and Linux died with it. The
  service ends a browser's stream from its main thread, and closing that
  stream writes the WebSocket's goodbye over TLS, which Android forbids there.
  Neither the JVM tests nor `app_process` has that rule. The page still
  showed the right card each time, because a dropped stream reconnects and
  then hears the truth. Now a stream's closing and its last word go to a
  thread of their own whenever they are asked for on the main thread; those
  threads catch what is thrown in them (an exception left to any thread ends
  the process), and the service catches a RuntimeException thrown while it
  asks for the end. That covers the path that crashed, not every way a
  service can die. Installed again: sign out ended
  the browser's picture at once and the process lived on (same pid); switched
  off, the page said so, the state read `off`, port 7443 was closed, and the
  log had no crash.
- **Afterwards:** the switch off, Linux shut down as before the test, the
  plain (not debuggable) build of this change left installed, the earlier dev
  APK kept to put back.

2026-11-20, 00:46–01:15, the same way, after the second review's fixes: the
debuggable build for the session (to read the code and look inside Linux),
then the plain one, which was left installed and checked on its own.

- **Firefox 160** (headless, Windows, a real Mozilla build): its warning is
  *Be careful. Something doesn't look right.*, then *Advanced*, then
  *Proceed to 192.168.2.25:7443 (Risky)*. A secure context, `VideoDecoder`,
  H.264 High; the code; the real XFCE desktop decoded by Firefox. A
  right-click opened XFCE's menu where it was made; a terminal took
  `echo firefox-typed-$((6*7)) ok` and printed `firefox-typed-42 ok`.
  Scrolling text: Low 27 frames a second at 2.6 Mb/s, Medium 25 at 8.1, High
  25 at 19.6; Firefox decoded in 3.7, 8.2 and 15.3 ms (Edge: under 1 ms),
  headless and so most likely in software. No script errors. Edge paired
  next took the desktop, and Firefox was told *Linux opened somewhere else*.
- **The notification's Turn off**, pressed on the phone's own shade with
  Firefox watching (debuggable build) and again with Edge watching (plain
  build): the page said *Turned off on the phone*, the switch's file went,
  the state read `off`, port 7443 refused connections, and `:linux`, Xvnc and
  xfce4-session kept their pids. The log said *stopped listening*, then
  *ending the session*: closed first, then told.
- **The notification while a browser watched:** found saying *Open … in a
  browser* all through Firefox's session. It was redrawn when the server
  counted the watcher, before LinuxStream had the stream; now it is redrawn
  when the stream starts and ends, and on the plain build it read *Linux is
  open in a browser* (the shade, and `dumpsys notification`).
- **Sign them out**, with Edge watching and Firefox idle: Edge was told at
  once; Firefox, asked to come back, was told it had been signed out (the
  gentle close, on the real app); 0 browsers left, `:linux` the same pid.
- **What Chromium inside Linux paints.** YouTube's *Stats for nerds* over 11
  seconds of the 60 fps video (AV1 1280×720@60, decoded in software in the
  guest): from 545 dropped of 1,924 to 729 of 2,588, so 480 frames painted,
  43.6 a second. The page drew 43 a second over the same seconds. The ceiling
  is Chromium in Linux, not the stream: every frame it painted reached the
  computer.
- **YouTube full screen inside Linux:** found why it does not take. Xfwm4
  grants full screen when asked (a `_NET_WM_STATE` request sent by hand made
  Chromium's window 1920×1080 at 0,0, and back), and a page's
  `requestFullscreen()` resolves, but Chromium leaves full screen at once:
  `fullscreenchange` fires true, then false. A second Chromium on a fresh
  profile stays full screen; given the session's GPU settings (Zink,
  `MESA_LOADER_DRIVER_OVERRIDE=zink`) it leaves at once like the first. With
  `--disable-gpu-compositing`, or with `--use-angle=vulkan
  --enable-features=Vulkan` (its GPU process then holds the Adreno device),
  it stays. Not changed that night: that is how every Linux viewer's Chromium
  draws (linux-setup.sh's `dex-chromium`), and its speed had to be measured
  first. Measured and changed later that day (below).
- **Afterwards:** the switch off, Linux shut down, the phone on its home
  screen, the plain build of this change installed; every file the test put
  in the guest's `/tmp` removed (the session's own `.oadx-*` files kept).

2026-11-20, 11:42–11:54, after Linux's Chromium moved to Vulkan on Adreno
phones (`doc/linux-gpu.md`, "Chromium"), the same way: the debuggable build of
this change with that one in it, Edge headless on the computer. The desktop
first came up without its window manager, broken by the speed test's own Xfwm4
(the trap under "Chromium" there); put right, then:

- **YouTube full screen inside Linux now takes.** The session's Chromium had
  `--use-angle=vulkan` on its command line, and its GPU process the Adreno
  open. YouTube's `f` key made the window full screen: Xfwm4 listed it
  `FULLSCREEN`, 1920×1080 at 0,0. *Stats for nerds* over 18 seconds of the
  60 fps video (AV1 1280×720@60): from 4 dropped of 7,711 to 4 of 8,796, so
  1,085 frames painted, 60 a second. On Zink, in theater mode, it had painted
  43.6.
- **What reached the computer** over those seconds: Medium 25 frames a second
  (1.1 Mb/s, 28 ms round trip, encode 18 ms at 1920×1080), after 35 over the
  10 seconds before; Low 36, rising from 23 to 47 (2.7 Mb/s, 10 ms, encode
  12 ms at 1280×720). The phone's log had the link behind (62% of a second
  spent waiting on it) and the bitrate lowered to 1.3 Mb/s, where Medium had
  carried 12.6 with scrolling text. So Chromium inside Linux is no longer the
  limit for this video; the stream is. Not looked into further.
- **Afterwards** (12:00–12:09): the test's two browsers signed out, the switch
  off, Linux shut down from the app's home screen, the plain build of this
  change installed, the phone on its home screen.

2026-11-20, 14:14–14:27: plain http, sound, and the question on leaving, on
the S25 with its owner's leave; two headless Edges on the computer, each with
a fresh profile; the debuggable build of this change, then the plain one.
Browser access was on, as its owner had left it after using it (on the laptop
where the sound did not come), with one browser remembered; Linux was not
running.

- **The address typed bare:** the plain page, offering the secure connection
  first (no certificate taken yet in that browser). *Continue securely*: the
  browser's warning (`NET::ERR_CERT_AUTHORITY_INVALID`), *Advanced*,
  *Continue*, the code, and Linux started, on WebCodecs.
- **Sound, YouTube in Linux's Chromium** (Big Buck Bunny): over 5 s the page's
  WebSocket carried 444 runs of PCM at 1,547 kb/s, every one with sound in it
  (RMS 2,193 on average, 7,044 at most, read from the frames themselves over
  DevTools), and at most 69 ms between two runs. The page played them, its
  count climbing. The phone's log: *tap 6081 goes to a browser; the phone is
  quiet*, *the browser turned its sound on*, the tap read for that browser.
  The picture meanwhile: 46 frames a second (median), 0.5 Mb/s, 19 ms round
  trip. The phone played nothing: no player of the app's in `dumpsys audio`,
  and none started at all.
- **Sound off:** nothing on the socket for 4 s, the page's Web Audio closed,
  *the browser turned its sound off* in the log. On again: *on*, the tap read
  again. The first look after that saw nothing, because YouTube had stopped
  with its own *Something went wrong. Refresh or try again later*; reloaded
  with F5 through the page: 448 runs at 1,539 kb/s, all with sound.
- **Plain http,** in the second browser, with no certificate taken: the
  choice; *Continue without encryption*; the code; the desktop through the
  browser's video player, *Not encrypted* on the bar and in the phone's log.
  23 frames a second (headless Edge draws about 34 a second, its ceiling and
  not the stream's), each on screen 23 to 53 ms after its bytes arrived (the
  page's own figure), and a round trip of 13 ms in a later sample: 253 and
  184 ms at first, in some state of the phone's link that was not looked into
  (https, measured between, 24 ms). Sound the same: 445 runs at 1,539 kb/s,
  all with sound. The first browser was told *Linux opened somewhere else*.
- **The phone's speaker, held:** with the plain browser watching and YouTube
  paused through it (`k`: 177 runs with sound in the 2 s before, none in the
  3 after), the phone's own Linux window was opened, and the service started
  the speaker's pump. It waited: no player of the app's. The browser then
  left: it asked first (`beforeunload`), its session ended, and the log said
  *tap 6081 plays on the phone again* and *playing the guest tap from 6081*.
  The phone had its sound back: one player, idle, the video being paused.
- **The plain address, in the browser that had taken the certificate:** to
  https by itself and to the desktop, no choice shown.
- **Leaving:** the used plain page asked first. A page with no click or key
  since it loaded did not: Chrome asks only on a page a person has used.
- **Afterwards:** Linux shut down from the app's home screen; the test's two
  browsers taken out of the phone's list (the owner's one kept, from a copy
  taken before); browser access left on, as its owner had it; the plain build
  of this change installed, and the listener came back on 7443 by itself; the
  phone on its home screen, nothing of the app's playing.

2026-11-20, 15:16–15:24: the sound at Low, Medium and High, on the S25 with
its owner's leave again, the same way (the debuggable build of this change,
then the plain one; headless Edge with fresh profiles). YouTube (Big Buck
Bunny) in Linux's Chromium; what crossed the page's WebSocket counted over
DevTools, 5 s each:

| | https page | plain http page |
| --- | --- | --- |
| Medium | Opus 96 kb/s stereo: 251 packets (one each 20 ms), 97 kb/s, 241 bytes each | PCM 24 kHz stereo: 767 to 776 kb/s, every run with sound |
| Low | Opus 32 kb/s mono: 249 packets, 32 kb/s, 81 bytes each | PCM 16 kHz mono: 255 kb/s |
| High | PCM 48 kHz stereo: 1,541 kb/s, every run with sound | PCM 48 kHz stereo: 1,523 to 1,541 kb/s |

- The phone's log at the start: *sound encoded on c2.android.opus.encoder*,
  *sound as Opus 96 kb/s stereo (medium)*. At each quality chosen on the bar:
  *the browser chose low*, a new encoder, *sound as Opus 32 kb/s mono (low)*,
  11 ms after; *the browser chose high*, *sound as PCM 48 kHz stereo (high)*,
  9 ms after.
- The page said each form, and its count of runs played climbed in each;
  over https the Opus went through WebCodecs (its decoder *configured*); over
  plain http there was no Opus to decode, and each quality was its PCM.
- One plain Medium sample had sound in under half its runs (RMS 78); two
  more, right after, had it in all (RMS 1,593 and 985, High's 883): a quiet
  stretch of the film.
- **Afterwards:** YouTube paused through the page (nothing more sent), the
  page left (it asked first), both browsers closed; Linux shut down; the
  owner's one browser kept and the test's taken out; browser access on; the
  plain build of this change installed and listening; the home screen.

Not yet:

- **Phone on mobile data.** Its mobile-data address is never offered or
  answered, by design (on the S25 that is `rmnet_data0` at 192.0.0.2, behind
  the carrier's 464XLAT; checked in the Settings tests). A computer on the
  phone's own hotspot, or a VPN with private addresses such as Tailscale,
  should work: their interfaces and addresses are offered and their peers let
  in (also checked there, and a VPN-only phone's card drawn), but neither was
  tried live. Both need the phone on a cable, so that adb survives its Wi-Fi
  going, and Tailscale on the phone.
- **Before LinuxOnDroid targets API 37:** Android 17 then blocks traffic to and
  from the local network for an app that has not asked for
  `ACCESS_LOCAL_NETWORK`, a runtime permission. Accepting an incoming TCP
  connection is named among what it blocks, so a browser's connection would
  simply time out. At target 36 the app keeps an implicit grant through
  INTERNET (seen on the S25: granted, `REVOKE_WHEN_REQUESTED`), and Android's
  guidance is not to declare or ask for it before targeting 37. The switch
  must then ask for it as it goes on, and say so if refused.
- **Safari:** not on the computer this was built on. It has WebCodecs, and its
  *visit this website* adds the certificate to the keychain, which asks for
  the Mac's password. Firefox's own clipboard was not checked either: headless
  Firefox keeps one of its own, not the computer's. The page keeps text it
  could not hand over and gives it at the next click, as Firefox and Safari
  ask.
- **A full-screen 60 fps video:** Chromium inside Linux now paints all of it,
  and 25 to 36 frames a second of it reached the computer (above), with the
  phone finding the link behind. Why that link fell behind was not looked into.
- **Plain http where it is needed:** a managed laptop that refuses the
  certificate was not at hand (the headless Edge here offers *Continue*).
  Firefox and Safari over plain http are untested.
- **Sound against the picture:** how far apart they are was not measured. The
  tap hands sound over in runs up to about 70 ms apart while something plays,
  and the page keeps as much in hand as those gaps ask, 40 to 350 ms; Opus
  adds its encoder's 20 ms frame and look-ahead to Low and Medium.
- **Opus on an older phone, and in Firefox and Safari:** Android 9 and before
  have no Opus encoder and get the smaller PCM, not tried on such a phone.
  Firefox and Safari's AudioDecoder were not tried.
- **A plain page opened again:** twice, in the first two runs of the computer
  tests, a plain page left for `about:blank` and opened again did not load in
  10 s. Not seen in the seven runs since. By hand, three times, the page
  loaded each time; once it had not yet shown the desktop 3 s later, when the
  watching stopped.
  The test now records the server's connections and its threads if it
  happens again.
- The page's own words are English, as the desktop app is; the phone's card
  and notification are in all nine of the app's languages.
- The notification's address is the one at its last update; the card follows
  a change of network within a second.
- A network with IPv6 alone: the addresses offered are IPv4.

### Files, on a computer (2026-11-20)

`node test/linux-files-test.mjs`: the launcher's own `LinuxBrowserServer` and
`LinuxFiles` on a JVM, over a stand-in Linux (a home with Documents and
Pictures, the phone's shared folder bound at `~/Desktop/LinuxOnDeX`, `/proc`,
`/sys` and `/dev`, and links: one inside, one absolute to `/etc`, one climbing
past `/`, one to a path of the computer's outside it, one as proot writes them,
one to itself), then the page in a visible Edge window. 123 checks, all passed:

- Who gets in: no token, a made-up token, a change without the page's
  `Origin` or from another site's, a name instead of the address, a made-up
  ticket, a ticket for anything but a download, a change sent as a GET: each
  refused
- Paths: `/proc` never listed nor opened, `..` stops at `/`, each link above
  where Linux would take it, the outside one nowhere, the loop refused
- Sending: whole, half then the rest, a connection really cut half way and
  finished, a wrong resume point told where to start, a cancel leaving nothing,
  a taken name refused, kept beside or replaced, forty files and an empty
  folder in one request with two bad paths refused and the rest landing, the
  same batch again doubling nothing
- Bringing back: whole, three kinds of range, past the end, `If-Range` on a
  changed file, an HTML file served as sandboxed text, a ticket's file and no
  other, a ZIP read back by this test and by Windows' own `tar` (libarchive),
  and ZIP64's fields forced and read back by both
- The Trash as Linux's file manager lays it out, Undo, the shared folder's
  file refused and then deleted when asked, a link deleted and not what it
  points to, `/` and the like protected
- A sign-out on the phone cutting a download under way, its ticket and token
  opening nothing after
- In the browser: `#files` opening Files once the code is typed (no stream
  started), files and a whole folder dropped onto the list (an empty folder
  too), the question for a taken name, Upload's picker, F2, Delete with Undo,
  Quick look (an HTML file shown as text and not run), the grid with a
  photo's own small picture, a photo, a video playing and seeking, a download
  and a folder's ZIP saved by the browser, search in a folder and under it,
  the Trash and Put back, a new folder named where it appears, Backspace and
  Enter, a sign-out met by Files, Linux not installed, and a file dropped on
  the desktop's picture landing in `~/Desktop`; nothing thrown by the page

This computer to itself over its own network address, TLS: 64 MB up at
62–148 MB/s and down at 24–39 MB/s across runs (the JVM's TLS, and Windows
scanning a file just written; the phone's TLS is BoringSSL). Not yet run on a
phone: the real rootfs and its links, Android's thumbnails, the speed over
Wi-Fi, the shared folder on a real second filesystem.

## Costs

The APK grows by about 80 KB: the page, and five small classes (the player
over plain http and the sound, 14 KB of it). Switched off, nothing runs.
Switched on and idle, a listening socket and the foreground service remote
access would also hold. Streaming costs what the desktop app's cable route
costs, over Wi-Fi. Sound adds, while something plays in Linux, 32 kb/s at
Low, 96 kb/s at Medium and 1.5 Mb/s at High (over plain http, 256 and 768
kb/s at Low and Medium), and the phone's Opus encoder a little CPU; nothing
while Linux is silent, or the sound is off on the bar: then nothing is read on
the phone either.
