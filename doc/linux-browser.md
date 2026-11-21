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
| The encoder | `LinuxStreamEncoder.java` | a picture smaller than the desktop, for Low; a browser's key frames only when asked |
| The bitrate | `LinuxStreamRate.java` | how late frames arrive, from the page's arrival times: cut the moment a queue stands |
| The microphone | `LinuxStreamMic.java` | a browser's microphone into a pipe Linux's PulseAudio reads as "Browser microphone" |
| The handshake | `LinuxStreamSocket.java` | `accept`: the WebSocket upgrade without the loopback rules |
| The service | `LinuxService.java` | listening while the switch is on, the notification, starting the desktop as the switch goes on |
| Coming back | `RemoteResumeReceiver.java` | a boot or an update |
| The card | `LinuxSettingsActivity.java` | LinuxOnDroid only; the desktop build has the desktop app |
| The page | `assets/linux/browser/` | `index.html`, `viewer.css`, `viewer.js`, `h264.js`, `keysym.js`, `icon.svg`; `mse.js`, the picture over plain http; `video-worker.js`, the socket, the decoder and the canvas off the page's thread; `touch.js`, fingers; `mic-worklet.js`, the microphone |
| Linux's files | `LinuxFiles.java` | `/fs/`: listing, sending (resumable, and many small files to a request), bringing back (ranges, ZIP), the Trash, tickets; Linux's paths, its links followed as Linux follows them |
| Their pictures | `LinuxFilesThumbs.java` | small pictures of photos (HEIC too), videos, songs' covers and PDFs, with Android's decoders |
| Files, the page | `files.js`, `files.css`, `transfer.js`, `peek.js`, `fileicons.js` | the file manager, the transfers and their panel, Quick look, kinds and icons |
| Check | `test/linux-browser-test.mjs`, `test/linux-browser/` | the server on a JVM and the page in a real browser; `--device`: the same on a phone (`--throttle`: through a slow link, the bitrate's two rules side by side); `RateCheck.java`: the bitrate's rules on simulated links |
| Measure | `test/linux-browser-latency.mjs` | how late frames are drawn, end to end, decoded on the page's thread or in the worker, the page idle or busy |
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
  Closed gently, as every last word is (since 2026-11-20 also to a page
  already watching): whatever the page still sends (its hello, a ping, its
  acknowledgements) is read and dropped until it closes its end, up to a
  second.
  Closed over bytes unread, TCP sends a reset, and a reset can take the last
  word with it: the page then says only that it cannot reach the phone.
  The phone's name is told only to a browser with a token (`/hello` with
  `Authorization: Bearer`): anyone on the network may ask, and the name is
  often its owner's.
- **An address in `Host`, not a name.** A page elsewhere that points its own
  name at the phone (DNS rebinding) gets a 421. Every request that changes
  something, and the WebSocket, must carry this origin's own `Origin`.
- **Nobody holds the door.** Every request has 30 s to come and be answered,
  however slowly it is sent, until its connection is let in (a stream, or
  Linux's files), and one address may hold sixteen connections at once (a
  browser opens six for each scheme, and the page uses both as the plain page
  goes to the secure one: eight left no room). The 30 s are each request's,
  not the connection's: a browser keeps a connection for the page's next file,
  and until 2026-11-20 one kept that long was cut in the middle of a file,
  which Chrome said as `ERR_CONNECTION_ABORTED` and the page lost a module
  of its own to (in four runs of the test in seven, as the plain page went to
  the secure one). A stream that says nothing for 75 s is let go, and the wake
  lock it held with it: the page pings every two seconds, and a hidden tab's
  timers can slow to once a minute.
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
  as one step, so a sign-out is wholly before it or wholly after. Each of the
  store's files is written whole, a temporary file renamed over the old one;
  Windows, where the stand-in tests run, refuses that rename while another
  thread is reading the file, as every request reads the tokens, so a refused
  rename is tried again for up to 50 times 2 ms (until 2026-11-20 it was given
  up at once, and a sign-out that met a request's read left the browser in:
  one round in 72 of the race below). Android renames over a file whoever is
  reading it.

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
of them the moment frames start to queue on the way (`LinuxStreamRate`, below), so
a frame is dropped before one is late. The choice is the browser's,
remembered there.

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

## Six changes, after a look at voxl (2026-11-20)

voxl (github.com/tembo/voxl, its source read that day) streams a Linux
desktop to a browser too: x264 on the CPU with XDamage's hints of what
changed, a bitrate led by delay, decoding in a worker, 10 ms Opus, touch and a
microphone. Where it did better, this now does the same, within limits said
where they are: Opus stays 20 ms a packet, where voxl's is 10 (Android's
encoder makes no other size; PCM, at High, comes 10 ms at a time); an encoder
that refuses "only when asked" makes a key frame every 10 s; touch was tried in
Chrome's touch emulation, not on a tablet; the microphone with Edge's
synthetic one, not a real microphone; and the worker's numbers are a visible
window's (item 5). Its other edge is the hints: MediaCodec takes none, and
Xvnc's incremental updates, of which only the changed rectangles go into the
texture (`glTexSubImage2D`), already spare most of the work they spare.

**1. Key frames only when asked** (`LinuxStreamEncoder.ONLY_WHEN_ASKED`). A
browser's encoder makes no key frame after its first (MediaFormat's negative
`KEY_I_FRAME_INTERVAL`) unless the page asks: at the start, when its tab is
back in sight, whenever its decoder has to start over. A key frame is many
times a frame's size: one every ten seconds was a hitch every ten seconds on a
link near its limit, and over TCP no frame is ever lost that a key frame would
mend. A computer's window, and the app windows that share the configuration
(`AppVideo`), keep one every 10 s. An encoder that refuses "only when asked"
is configured again with the 10 s, said in the log.

**2. A tab out of sight makes no picture** (`{"t":"picture","on":false}`). The
page says so the moment its tab is hidden: another tab in front, the window
minimised, or covered where the browser tracks that. The phone then asks Xvnc
for no update, gives the encoder nothing and drops what it still had; the
sound goes on. Back, it asks for the whole desktop and a key frame, and sends
nothing until that key frame, asking again every 500 ms if none comes. While
the picture is off it says `{"t":"hb"}` every 20 s, which the page answers at
once: a hidden tab's own timers can slow to one a minute, and the phone lets
go of a browser silent for 75 s.

**3. The bitrate from how late frames arrive** (`LinuxStreamRate`). Each
acknowledgement now carries when its frame arrived, by the page's clock
(`0x12` and a double; `"ackAt":true` in the hello). Against when the phone had
the frame ready, that is its trip one way plus the two clocks' difference,
the same for every frame: the shortest of the last ten seconds is the link's
own, and the shortest of the last 200 ms over it is the queue. The shortest,
not the median, because a queue delays every frame, and a delay that comes
and goes is no queue; the way there, not the round trip, because the phone's
Wi-Fi in power save holds acknowledgements for up to 150 ms (measured on the
S25, 2026-11-19), and the rule before cut the bitrate for that. A queue over
15 ms and a quarter of the link's round trip, there at two decisions in a row
(50 ms apart), cuts the bitrate by 15% (30% at four times that); over twice the
target, to 85% of what the page received in the interval when that is lower,
since a link that is plainly full carries what came through it; then it waits a
round trip and an interval before cutting again. A second and a half with the
queue under half the target, in motion, climbs 8% each half second (4% within
6 s of a cut). Never blind: a frame unacknowledged 300 ms past the link's
round trip, looked at every 100 ms while the pump waits, cuts by 30% (a link
that stops sends no acknowledgements to decide by), and so does a shortest
round trip of a second over 400 ms, which on the network a browser reaches
the phone by is always a queue (one there from the first frame is otherwise
taken for the link's own delay). The old rule (a second in which the pump
waited a fifth of its time on acknowledgements: down 30%; three such seconds
under 2%: up 25%) stays as a backstop, and is still the whole rule for a
computer's window that says only that a frame arrived: the desktop app before
2026-11-21. From then the desktop app says when each frame arrived too
(`ackAt`, `open-android-dex-tauri/src/features/remote/usb/wire.ts`) and its
stream is read the same way, on the cable and Wi-Fi only: a round trip over
400 ms is taken for a queue whatever the reading, which on the internet or a
relay it need not be (doc/usb-desktop.md, "On the local network only"); an
app not yet updated keeps the old rule. A browser far away over a VPN would
meet the same 400 ms; not tried.

Simulated (`test/linux-browser/RateCheck.java`: frames at 60 a second sized by
the bitrate, LinuxStream's window, one bottleneck, Wi-Fi power save holding
acknowledgements 40 to 150 ms about every 400 ms; the computer's clock a second
off and 20 ppm fast), how late frames arrive, at the 95th percentile:

| The link | New rule | Old rule |
| --- | --- | --- |
| a cable, 100 Mb/s | 7.5 ms, no cut | 7.5 ms, no cut |
| Wi-Fi in power save, room to spare | 10.5 ms, no cut, 23 Mb/s | 6.6 ms, 8 cuts for nothing, 7.7 Mb/s |
| slow Wi-Fi, 8 Mb/s | 33 ms | 250 ms |
| slow Wi-Fi in power save | 32 ms, 5.5 Mb/s | 21 ms, 4.2 Mb/s |
| from 40 to 6 Mb/s at once | 34 ms; the first cut 0.33 s after | 659 ms; 1 s after |
| from 6 back to 60 Mb/s | back at the 25 Mb/s asked for within 15 s | 18 Mb/s after 15 s |

A simulation, not a network. On the phone the first version did worse than
this says (below): the computer on Wi-Fi too, its own power save and the two
ends contending held a whole interval of frames now and then, and each time
the bitrate was cut to what had come through, which then was only what the
encoder had made. Through a 6 Mb/s throttle the real Wi-Fi under it was the
bottleneck, and in four rounds turn about the first version kept the round
trip where the old rule did (median 60 to 75 ms against 55 to 61; at 90%, 70
to 99 against 78 to 103) and carried a third less (0.9 to 1.0 Mb/s against 1.3
to 1.7). So the queue must now stand for two decisions in a row, and the cut
to what came through waits for a queue over twice the target; the simulation
gives the same verdicts as before (slow Wi-Fi 33 ms at 95%, the drop's first
cut 0.33 s after). The tuned rule's phone numbers: under "The six changes, on
the S25".

**4. Sound in 10 ms pieces, and less of it in hand.** The phone reads PCM 10 ms
at a time (`LinuxStreamAudio.CHUNK`, 20 before); Opus stays 20 ms, the only
size Android's encoder makes (C2SoftOpusEnc's frame is fixed). The page keeps
what the gaps between runs say it needs, from 15 ms (40 before) to 350, and
forgets an old gap by half every two seconds (every eight before). Two
mistakes went with it: a gap after silence counted (the phone sends half a
second of silence and then nothing until there is sound again, and that pause,
when just under the half second that marks a stall, held the sound at its
350 ms most from then on: a sound out of step with the picture after any
pause), and a hidden tab's bursts, now forgotten when the tab is back.

**5. The picture in a worker** (`video-worker.js`). The stream's socket, the
decoder and the canvas (`transferControlToOffscreen`) live in a worker; the
rest of the page sees a `WorkerSocket` with a WebSocket's shape, and gets
everything but the frames as it came. Acknowledgements, sent from the worker,
carry the time a frame really arrived, whatever the page's own thread is doing
(from there, a busy page would look like a slow link). A browser that cannot
draw in a worker, or `lod.decode` set to `main`, decodes on the page's thread
as before. Measured on this computer (`test/linux-browser-latency.mjs`: Edge
in a window, the launcher's server on a JVM, 1280×720 at 60 frames a second
with noise in every frame, paced as the phone paces, each frame stamped with
this computer's clock when sent; two rounds of 10 s each way, interleaved),
how long after it was sent each frame was drawn:

| | Median | 95th percentile |
| --- | --- | --- |
| the page's thread, idle | 2 to 9 ms | 3 to 10 ms |
| the worker, idle | 3 to 10 ms | 6 to 10 ms |
| the page's thread, busy 60% (a 30 ms task each 50 ms) | 17 to 28 ms | 32 to 43 ms |
| the worker, the page busy 60% | 6 to 10 ms | 7 to 10 ms |

Again on the final code (late on 2026-11-20, two rounds): the page's thread
idle 1.8 and 4.8 ms (3.4 and 5.9 at 95%), the worker idle 1.9 and 1.9 (3.0
and 2.9); the page's thread busy 8.3 and 18.5 ms (28 and 35), the worker with
the page busy 1.9 and 5.1 (2.6 and 6.0).

These are a visible window's, the way a person sees the page. Headless (its
own clock for drawing), the two runs disagreed. An audit's run (2026-11-21)
had the worker's median the lower with the page busy (7 to 9 ms against 13 to
19) but its 95th percentile the higher (47 to 133 ms against 32 to 36). This
computer's the same morning had the worker the lower in both: with the page
busy, 3.2 and 8.0 ms at the median against 8.8 and 17.4, and 5.5 and 11.8 at
95% against 27.9 and 80.1; idle, 3.2 and 3.3 against 3.3 and 7.7. So the tail
is claimed for a visible Edge on this computer only; other browsers and
machines are untried.

Measured too, and not kept: acknowledging a frame once decoded rather than as
it arrives, in either place. It was no sooner on screen (this computer
decodes a frame in a millisecond or two), so the acknowledgement stays at the
arrival.

**6. Touch, a keyboard, and a microphone.** On a touch screen (`touch.js`),
LinuxOnDroid's own window's two ways (`novnc/dex-input.js`, whose numbers it
keeps): **Touch**, a tap clicks where it lands, a drag scrolls, a long press
is a right-click or, moving, a drag with the button held, two fingers pinch
and move the picture; **Trackpad**, the finger moves a pointer drawn in
Linux's own shape with a ring, faster as it goes faster, a tap clicks where
the pointer is, a double tap held is a drag, two fingers tap for a right-click
and drag to scroll, a pointer pushed against a zoomed picture's edge moves it.
Touch on a tablet's screen, Trackpad on a phone's, until a person picks; a
handle at the top brings the bar down; **Fit** undoes a pinch. The zoom is the
page's own (a transform of the canvas and the player), the desktop's size
unchanged. The keyboard button focuses a field out of sight that the screen's
keyboard types into: what it types goes to Linux as keys, worked out from how
the field's text changed (noVNC's way, since a screen keyboard composes and
corrects), and a real keyboard on the field is Linux's as it is on the
picture; a strip on top of the screen's keyboard has Esc, Tab, Ctrl and Alt
(held for the next key), Super and the arrows.

The **microphone** (https only: browsers give `getUserMedia` to secure pages):
off at every start, on by its button, the browser asking first, echo
cancelling asked for. `mic-worklet.js` makes 10 ms of s16le at 48 kHz, one
channel, on the browser's audio thread (`0x13`); the phone writes it, never
waiting, into `/tmp/.oadx-mic` (`LinuxStreamMic`), a pipe held to one page
(4 KB, 42 ms) so that a microphone nobody in Linux records leaves at most that
much behind it. In Linux, `module-pipe-source` reads the pipe as "Browser
microphone", the default source, loaded beside `dex.pa` by `linux-rt.sh` at
every start (`/tmp/.oadx-desk.pa`, `.nofail`), so a Linux set up before this
gets it at its next start. A Linux without the pipe is said on the page, in
amber: *Linux cannot hear the microphone yet. Restart Linux on the phone*.
SIGPIPE on a pipe whose reader went is an error, not the end of `:linux`: ART
blocks it in every thread it runs (`Runtime::BlockSignals`). An end card, a
sign-out, Files and leaving the page all turn the microphone off, and the
browser stops recording; the desktop again does not start it by itself. A
press while the browser still asks waits for that answer, and an answer that
comes after the microphone was turned off (Files opened meanwhile) records
nothing. Before 2026-11-21 Files and a sign-out left the browser recording,
and the desktop's next stream sent the microphone to Linux again unasked; two
presses before the answer asked for two microphones, and off stopped one.

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
- holding the door: a seventeenth silent connection from one address closed
  unread; a request sent a byte every 200 ms closed at its deadline; one
  connection kept for eight files, one every half second, answered for each
  past that deadline (with the deadline the connection's, as before
  2026-11-20: three, then cut at 1.5 s); a
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
  the relay's own encoder path, a stand-in only for MediaCodec; for 20 ms of
  exact zeroes Opus's own frame of silence, as a real encoder makes, since
  2026-11-20: a tone's packet there, right before the phone holds silence
  back, made the pause after it a gap of the link's, and the sound in hand
  350 ms whenever the pause came under half a second, a check failing in
  about half the runs), the stereo
  encoder closed for the mono one; High still PCM; off, the tap let go and
  nothing more; on again; the browser gone, the tap let go;
- while a browser is being let in (held there, `slowhandoff`, sending a ping
  as the page does): signed out, it is told so as it would take the desktop
  and never watches, and the phone does not say it is watched; switched off,
  the same; and a sign-out right behind a right code, eight times, with the
  store's lock held by a process of its own so that the pairing waits inside
  its code's check and the sign-out waits behind it: the browser let in is
  signed out with the rest; and 200 sign-outs, each with a browser just let
  in, while two threads read the list of browsers as requests do
  (`test/linux-browser/StoreCheck.java`): every browser out and a new code
  each time (with the rename given up at its first refusal, as before
  2026-11-20: 8, 8 and 15 sign-outs in 200 left the browser in);
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
  phone. A stream turned away now closes gently
  (`LinuxStreamSocket.closeAfterWord`).
- **The same, to a page watching** (found 2026-11-20, the S25): a session
  told it was replaced, signed out, switched off, or that Linux had stopped
  was closed the moment the word was written, while the page's
  acknowledgements and pings kept coming. Linux closes a socket with bytes
  unread by sending a reset, and a reset throws away what the phone had not
  sent yet and what the browser had not read: in three runs of the phone's
  test in a row, the first tab was never told it was replaced, came back by
  itself, and took the desktop from the second. Now the session says the
  word and the close frame and sends nothing more, and its own input loop
  reads and drops what the page still sends until the page closes its end
  (which it does on reading the word), at most a second
  (`LinuxStream.Session.closeAfterWord`). Red, then green, on the S25, with a
  stream not reading for 0.7 s and sending pointer moves all the while when
  a second one takes the desktop: on the old code its socket was reset 0.4 s
  in, and "replaced" never came; with the fix the word came, and the phone's
  test passed 58 of 58. The stand-in on this computer ends a stream the same
  way now: Windows resets a socket closed over unread bytes too.

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

### The six changes, on a computer (2026-11-20)

`node test/linux-browser-test.mjs`, with these added: the bitrate's two rules on
the simulated links (the table above), the hello's `ackAt`, acknowledgements
carrying this computer's time, the phone's word while a hidden tab has no
picture answered in under 150 ms (twice, so a ping by chance cannot pass it), a
tab behind another telling the phone, no frame sent or drawn for 2.5 s while
the sound went on, the tab back from a key frame in under half a second, the
sound in hand, the picture decoded and drawn in the worker, and touch, the
screen's keyboard and the microphone: a tap, a long press with its ring, a
drag that scrolls, a pinch to about twice and a tap landing on the zoomed
desktop's point, Fit, Trackpad's pointer drawn and moved, a tap at the pointer
and not the finger, two fingers for a right-click and for scrolling, typed
text, a delete, Ctrl held from the strip, Esc, Enter from a real keyboard on
the field, the strip hiding; Edge's fake microphone sending 100 messages a
second of 960 bytes with its tone in them, the phone's "unavailable" turning
the button amber with its words, and off sending nothing more. Results:
249 of 249 in each of the last three runs (10 to 12), after five things the
runs before them turned up, each made to fail first and then pass:

- **A connection kept for the next file was cut in the middle of one.** The
  30 s to be let in were the connection's, from when it came, not each
  request's. In four runs of seven the plain page going to the secure one
  lost a module of the page this way (`ERR_CONNECTION_ABORTED`). Red: three
  files of eight on one kept connection, cut at a deadline of 1.5 s. Now each
  request has its own.
- **The sound in hand stuck at 350 ms after a hidden tab came back**, in
  three runs of five: the stand-in's Opus, a tone's packet even for silence
  (above, "the sound").
- **A sign-out that left a browser in**, one round in 72 of the race of a
  sign-out and a pairing: the store's file not replaced while being read, on
  Windows (above, "Who gets in"; `StoreCheck.java`: 8, 8 and 15 sign-outs in
  200 before, none in eight runs after).
- **The first tab not told that the desktop opened in the second**, once in
  ten runs; on the phone, three runs in a row: the session's last word lost
  to a reset (above, "The same, to a page watching").
- **A tap's check read too soon**, once: the move to the finger before the
  press has the buttons up too, and was taken for the lift.

The runs before (1 to 9): 192 of 219, 218 of 219, 216 of 244, 241 of 245, 243
of 247, 244 of 247, 243 of 247, and 246 of 248 twice (run 8 the red one
above). Their failures: the five above; the page's sound in hand held at
350 ms by a gap after silence (fixed, above, "Sound in 10 ms pieces"); the
test's own timing, since changed (the keyboard's steps waiting for each
other, a hidden tab measured once the frames on their way have landed, the
sound measured against the gaps it had and not against 40 ms, a tab brought
in front again after another one closed); a section's later checks failing
with its first; and once, in run 3, the bar's first showing, not seen again
in the nine runs after.

The guest side, in a container (Ubuntu 24.04, PulseAudio 16.1, `dex.pa` and
`.oadx-desk.pa` exactly as the scripts write them, started as `linux-rt.sh`
starts it): "Browser microphone" the default source, the pipe a FIFO of
mode 0666 held to 4096 bytes by the writer; a tone written as LinuxStreamMic
writes it heard by `parec` 0.4 to 0.8 ms after it was written, five times in
five; again after the writer closed and opened the pipe; three seconds of tone
with nobody recording: 3,840 bytes kept and 284,160 dropped, and the next
recorder heard 9 ms of what was kept.

Not yet: on a real tablet's browser, touch and the screen's keyboard (here
with Chrome's touch emulation only); Firefox and Safari for the worker (both
have OffscreenCanvas; Safari's WebCodecs in a worker untried) and the
microphone.

### The six changes, on the S25 (2026-11-20)

Galaxy S25 Ultra (SM-S938B, Android 17), awake and charging, over Wi-Fi to
this computer, also on Wi-Fi (576 Mb/s). Two ways in:

**LinuxOnDroid itself** (`node test/linux-browser-app.mjs <serial>`, the
debuggable build of this code; a browser of the test's own let in with the
code read by run-as, no tap; Linux started from the page; in Linux, through a
proot of the test's own, a terminal running `top`, a tone from `pacat
--latency-msec=20`, a recording of the microphone with `parec`). 15 of 15:
Linux on screen in a visible Edge window, decoded in the worker; the phone's
encoder line `1296x646 as 1296x646 on c2.qti.avc.encoder at 4 Mb/s, low
latency, key frames when asked`; 15 s of `top`, 169 frames drawn and no key
frame unasked; the tab behind another: the phone's log saying so, its stats
standing still, nothing drawn while `top` ran; the tab back, the first frame
167 ms after, from a key frame (77 ms in the run before); Linux's tone heard
as Opus 96 kb/s, 32 ms in hand, the longest recent gap 23 ms, none run dry;
"Browser microphone" Linux's default source, the phone saying the
microphone reaches Linux, and `parec` recording 144,000 samples in 3 s, the
browser's tone in them (the loudest 10 ms at 14,890); the test's browser
signed out, the phone's browsers byte for byte as before. The run before lost
five checks to the test's own proot path (the libraries are a folder deeper,
`lib/arm64`), and left Linux running, which was logged out of by hand after.
The phone was then given the normal (not debuggable) build of the same code.

**The harness** (`--device`, nothing installed, the 1920×1080 stand-in with
every pixel changing every frame), in the eight runs from 2 to 9:
- key frames: none unasked in 15 s of motion, the encoder's line saying "key
  frames when asked", in every run;
- a hidden tab: the phone's log saying it stopped the picture in every run,
  and the picture back from a key frame in 156 to 368 ms; three runs (3, 5
  and 7) failed the check while frames sent before the tab was hidden were
  still arriving over a congested link (the phone's own figures: 0.8 s and
  4.3 s queued, in runs 5 and 7), which the check, waiting a fixed 1.5 s,
  took for frames sent while hidden. It now waits until nothing has been
  drawn for a second (in run 9 the last frame on its way came 404 ms after
  the tab was hidden), and then nothing may come for 3 s;
- the microphone: 88 to 98 kB/s (48 kHz mono is 96) with the tone, into a
  plain file standing in for the pipe (the shell may not make a pipe in
  /data/local/tmp: SELinux, EACCES), and "Linux cannot hear" said in amber
  for a pipe that is not there.

The bitrate's rules, side by side through a proxy here (`--throttle`, four
rounds turn about, ten seconds each after eight of settling, at High), round
trip from a frame ready to its acknowledgement:

| The link | New rule: median, 90% | Old rule: median, 90% |
| --- | --- | --- |
| 1.5 Mb/s, the final rule | 28 and 27 ms; 40 and 129 ms; 51–57 fps | 376 and 673 ms; 3.8 and 2.9 s; 40–42 fps |
| 1.5 Mb/s, the final rule, a second run | 107 and 72 ms; 2.5 s and 212 ms; 25–46 fps | 230 ms and 1.3 s; 3.9 and 2.8 s; 36–38 fps |
| the Wi-Fi as it is, the final rule | 64 and 94 ms; 102 and 141 ms | 145 and 69 ms; 413 and 85 ms |
| the Wi-Fi as it is, a second run | 137 and 48 ms; 234 and 94 ms; 4.9 and 2.6 Mb/s | 48 and 85 ms; 57 and 114 ms; 1.7 and 1.3 Mb/s |
| 6 Mb/s, the first version | 75 and 60 ms; 99 and 70 ms; 0.9–1.0 Mb/s | 55 and 61 ms; 103 and 78 ms; 1.3–1.7 Mb/s |

The first version's run is what it was tuned from (above); a run in between,
on Wi-Fi so bad that both rules' round trips were 3.7 s at the 90th
percentile, showed the two blind spots now closed (a stream stuck at its
ceiling with a second of frames in the phone's socket; a queue from the first
frame read as the link's own). The Wi-Fi as it is was the bottleneck under the
6 Mb/s throttle, and swings from round to round. What the numbers are: the
phone's own round trip from a frame ready to go to its acknowledgement, the
median of each second's, then the median (and 90th percentile) of ten such
seconds for one round; and "the rounds' average" is the average of two rounds'
medians. Through 1.5 Mb/s the new rule wins both runs on that average (28
against 525 ms at the median in the first, 89 against 757 in the second). It
had a bad round of its own, the second run's first: it began with a queue of
433 ms, which it then cut down, and that round's 90th percentile was 2.5 s. On the Wi-Fi as it
is the two trade places: the new rule was the faster on average in the first
run (79 against 107 ms at the median, carrying 2.7 and 1.2 Mb/s where the old
carried 5.3 and 1.5) and the slower in the second (92 against 66 ms at the
median, 164 against 86 at 90%, carrying 4.9 and 2.6 Mb/s where the old
carried 1.7 and 1.3). On that link neither rule is the faster one yet.

Runs on the final code (late on 2026-11-20): the phone's test 58 of 58 in
run 8, after the last word was made to wait for the page (above, "The same,
to a page watching"), and 58 of 58 in run 9, with the hidden tab's check
waiting for the frames on their way. Run 7, the same checks on the code before that, failed the
new check as the old code should, and failed the hidden tab's on the link of
that moment: the phone's own figures showed 4.3 s of frames queued, sent
before the tab was hidden, since the encoder made 1.9 Mb/s of the stand-in's
every-pixel-changing picture at a target of 0.5 (frames already on their way
are drawn when they come). Run 6, also before the fix, 61 of 62: the Wi-Fi
comparison in the table. Runs 3 to 5, earlier the same evening, each lost
"the first is told the desktop opened somewhere else" and what followed from
it; that was the reset, found and fixed after them.

LinuxOnDroid itself once more on the final code (its debuggable build, SHA-256
f8174c3e…; the plain one, a1fc1e2b…, left on the phone after): 16 of 16.
Linux on screen,
decoded in the worker; `1296x680 as 1296x680 on c2.qti.avc.encoder at 4 Mb/s,
low latency, key frames when asked`; 15 s of `top`, 171 frames and no key
frame unasked; the phone reporting the queue it reads; the tab hidden and
back from a key frame 176 ms after; Linux's sound as Opus 96 kb/s, 35 ms in
hand (the longest recent gap 25 ms), once run dry; "Browser microphone" the
default source, and `parec` recording 143,520 samples in 3 s, the loudest
10 ms at 15,906; nothing thrown by the page; the test's browser signed out,
the phone's list of browsers as before, byte for byte; Linux logged out of
again.

### An audit's findings, fixed (2026-11-21)

An audit of all the above (another model, reading the code and these
records) found two bugs and a gap, and asked for narrower claims, made above
(the voxl comparison, what the bitrate's numbers are, the worker's numbers).
Each fix was made to fail first, on a copy of the code without it
(`OADX_PAGE_DIR`, `OADX_LAUNCHER_DIR`: the repo's own page went on being
served meanwhile), then to pass.

- **The microphone recording on.** Files and a sign-out left the browser
  recording, and the desktop's next stream sent the microphone to Linux again
  unasked; two presses before the browser's answer asked for two microphones,
  and off stopped one (above, item 6). New checks wrap `getUserMedia` and
  count every microphone it gave the page and whether it still records: two
  presses, Files with it on, Desktop after, a press and then Files before the
  answer, a sign-out on the phone with it on, and Forget this browser with it
  on. On the page without the fix all six failed (runs 13 and 14; in run 13 the
  press-then-Files one only because the check before had left the old page's
  microphone on, so each check now starts with it off).
- **The phone's wait for the page, with two gaps.** The deadline for the
  close was set only after the close frame had been written, and on a page
  that stopped reading (its send buffer full) that write waits for as long as
  the link stays up: nothing then ended the session, and the caller, which can
  be the session taking the desktop over, waited with it. Now the deadline
  comes first and the close frame goes on a thread of its own. And a session
  whose own thread still waited for Linux to start (or for Xvnc) closed the
  moment it saw the word, the page's sends unread: a reset, the word with it.
  Now that thread reads and drops until the page closes too, or the deadline.
  The check: a stream replaced while Linux is still starting
  (`desktop starting` in the harness), its page not reading for 700 ms and
  sending all along. Red on the S25 (run 10): the page got "starting" and then
  a reset 781 ms in, no word.
- **A close that waits on the link.** `LinuxStreamSocket.close` wrote its
  goodbye whenever nobody else was writing, and with the send buffer full
  waited there, and with it whoever ended the session: a page silent for a
  minute, the service stopping. Now a goodbye still unwritten after 500 ms is
  given up and the connection reset under it. `CloseCheck.java`, run on the
  phone's own sockets: red, still waiting after 5 s (run 10).
- **A sign-out to a page not reading** had been tried only with a page that
  read; it is the same ending as being replaced, and now has a check of its
  own. It passed on the code before the fixes too (run 10), as it should: the
  input loop already read on after a watching session's last word.

The runs, in order. On this computer: run 13, the page without the microphone
fix, 248 of 255 (the six microphone checks, one of them for the wrong reason,
above; and the sound's check below); run 14, the same page, each microphone
check starting from off, 246 of 255: the six microphone checks, and three
others while I ran the desktop app's test and a type check on this computer
at the same time (17 to 26 frames a second drawn, 339 of 360 frames
acknowledged, 61 ms of sound in hand), not a fault of the page; runs 15 and 16,
the fixed code and nothing else running, 255 of 255 each. Files, 123 of 123.
On the S25, through the harness: run 10, the phone's classes without the two
fixes, 63 of 66 (the close still waiting after 5 s and the check after it,
and the word lost while Linux was starting; the phone dozing, on battery);
run 11, the fixed code, 64 of 66, every check of the fixes passing (the close
back in 504 ms, its writer woken; the word while Linux was starting; the
sign-out), and the two comparisons of the desktop app's stream failing (below);
run 12, 65 of 65 (the close back in 502 ms), the desktop app's stream on a
smaller picture, one comparison of the two not decided (below). For runs 11 and
12 the phone was kept awake with a wake key every 8 s (screen on, never a tap,
the phone locked or on its home screen); in run 10 its Wi-Fi was in power save,
and the browser's High carried 0.7 Mb/s with a round trip of 840 ms.

One check was changed for being wrong, not for failing the code: "the sound in
hand comes back down when the tab is back" asked for less than while it was
hidden, and a tab the browser had not slowed (29 ms hidden, 30 back, run 13)
has nothing to come down from. Now: under 60 ms back, and lower than hidden
only if it had grown.

**The desktop app's stream**, measured in the same runs (the phone's classes
under app_process, a client acknowledging every frame with the app's own
`wire.ts`, over wireless debugging, the only way the S25 is on adb): the app's
new hello gets the new rule and an older app's keeps the old, in every run;
and then the stream sat at the 0.5 Mb/s floor under both rules, the link
carrying about what the stand-in's picture makes even there, so the two could
not be told apart on it. Run 11's two failed checks were that: the rules'
round trips moving with the link from round to round. For run 12 the
comparison went to a 640×400 picture, whose floor the link might carry; it
did not, and a comparison held at the floor in every second measured is now
said and not counted (the "through 1.5 Mb/s" one in run 12). The other, with
a second or so above the floor, passed within its margin (the new rule 119 ms
at the median against 108, 337 at 90% against 314), which says little either
way. Which cut held the bitrate at the floor these runs cannot say (a
second audit, below). Numbers: doc/usb-desktop.md, "The encoder".

LinuxOnDroid itself on the fixed code (`test/linux-browser-app.mjs`, its
debuggable build, SHA-256 b1fb1074…; the plain one, 738b4320…, left on the
phone after, serving the same page byte for byte): 16 of 16. Linux started
from the page, decoded in the worker, `1298x648 as 1298x648 on
c2.qti.avc.encoder at 4 Mb/s, low latency, key frames when asked`; 15 s of
`top`, 172 frames and no key frame unasked; the queue reported; the tab hidden
and back from a key frame 494 ms after; Linux's sound as Opus 96 kb/s, 39 ms in
hand, twice run dry; "Browser microphone" Linux's default source, `parec`
recording 149,280 samples in 3 s of Edge's synthetic microphone, the loudest
10 ms at 15,337; nothing thrown by the page; the test's browser signed out, the
phone's list of browsers as before, byte for byte; Linux logged out of again.

### A second audit (2026-11-21, late morning)

The same auditor checked the fixes above against the raw logs and the code,
found the counts as written, and four things more:

- **The microphone, two ways out still.** Forget let go of the stream at once
  but stopped the browser recording only once the phone had answered the
  forget, seconds on a slow link; and Files or a sign-out while the sound path
  was still being made (the browser had answered, the worklet not yet come)
  left that microphone recording until the path was made, since a stop could
  not see it before then. Now Forget stops it at the press, and the
  microphone is where a stop sees it from the browser's answer on. Checks:
  Forget with the phone 2 s slow to answer, nothing recording 300 ms after the
  press; Files with the worklet held back 1.5 s, nothing recording 250 ms
  after. Red on the page without the two fixes (run 17): both. Green: runs
  18 to 20.
- **The takeover still waiting on the old session.** The session taking the
  desktop over still waited up to 300 ms for the old one's word, and then
  closed its microphone itself, whose word to its page goes out under its own
  lock: on a link that had stopped, up to the old session's deadline. Now
  the caller does no I/O at all. The old session's word and close frame go
  together, under one hold of its socket's lock (300 ms to find it free, else
  that session closes at once), on its own thread, and its sound and
  microphone after; the phone says how long a takeover took ("took the
  desktop from … in N ms"). New checks on the phone: a stream whose link has
  stopped (its page not reading, acknowledging all the same, so that the
  phone's buffers fill and its writes wait) replaced by another, which must
  take the desktop in under 100 ms while the stalled one closes soon after;
  and a page that reads the word and the close and never closes its end,
  which the phone's own deadline must end a second after its last word.
- **The desktop app's floor, its cause not shown.** Right: the full link's
  cut is held back when a fresh reading of the queue is small, so it cannot
  have been "whatever the queue", as written here before. Corrected
  (doc/usb-desktop.md), and the stats now count each kind of cut apart
  (`cutsQueue`, `cutsFull`, `cutsHeld`, and `cutsOld` for the old rule), which
  the comparison prints for every round.
- **The local network assumed.** The desktop app sent arrival times on the
  internet and relays too, and the rule takes a round trip over 400 ms for a
  queue. Now on the cable and Wi-Fi only, and a route that leaves the local
  network under a live stream, or comes back, says so; the phone turns the
  new rule off, or on again. Checks: `wire.ts` on this computer (a relay's
  hello asks for no times: red on the morning's `wire.ts`, green now), and on
  the phone a stream whose route leaves and comes back.

The runs. On this computer: run 17 (the page without the two microphone
fixes) 253 of 256, the two new checks failing as they should and one other,
the fake microphone's tone measured at 259 against 300, which the page's
change cannot move; runs 18 to 20, the fixed code, 253, 256 and 256 of 256.
Run 18's three: the plain page going to the secure one stuck at Connecting,
its stream open and no frame drawn; then a check after it that needed the
picture; and a new tab asking for a key frame every 500 ms with none drawn,
the first tab never told. No error from the stand-in; not seen in the two
runs after, nor in runs 15 and 16; not explained. Files, 123 of 123; the
desktop app's own test, 82 of 82, then 83 with the rule for wireless
debugging below.

On the S25, which left the Wi-Fi between 10:05 and 10:40 and was back at
12:02: run 13, the
phone's classes with the old takeover and no route switch, 64 of 69. Three of
its five failures were the test's own: the phone's log read as nothing. On
Windows each of logcat's lines ends in CRLF and the reader split on LF only,
so no line matched; the same reader had given nothing for every earlier
failure's "the phone:" detail too. Fixed (split on CRLF). The route switch
failed as it should. And the desktop app's comparison over wireless debugging
failed: the old rule's round trips 18 and 15 ms at the median, the new one's
281 and 325. Run 14, the same code, the reader fixed, 67 of 69: the takeover
took 302 ms, and the route switch failed again, as they should; the stalled
stream closed 1,035 ms after its last word began and the page that never
closed was ended at 1,014 ms, which pass on the old code too (the deadline was
there already). Run 15, the fixed code, 68 of 69: the takeover 1 ms, the
stalled stream closed at 1,072 ms, the page that never closed ended at
1,019 ms, the route switch done both ways; the one failure the desktop app's
comparison through 1.5 Mb/s (the new rule 184 ms at the median against 153,
321 at 90% against 345). For all three the phone was kept awake with the wake
key, as before.

So over wireless debugging the desktop app now keeps the old rule (`wire.ts`,
`readsTimes`): in the six runs that compared the two there (10 to 15), the
new rule's median round trip was the longer in five. The cuts now counted
apart say why it sat at the floor: most were the held frame's (a frame not
acknowledged 300 ms past the link's own round trip), then the full link's,
and the queue's fewest (run 15, the new rule's four rounds: held 3, 2, 5 and
4; full 4, 2, 1 and 2; queue 0, 3, 0 and 1). The two comparisons over
wireless debugging are measured from now on, not counted; the suite was not
run on the phone again after that change, which touched only the test's
reporting and the desktop app. LinuxOnDroid itself on this code (its
debuggable build, e2a85e1b…; the plain one, abb4d634…, left on the phone,
serving the same page byte for byte): 16 of 16.

### Files, on a computer (2026-11-20)

`node test/linux-files-test.mjs`: the launcher's own `LinuxBrowserServer` and
`LinuxFiles` on a JVM, over a stand-in Linux (a home with Documents and
Pictures, the phone's shared folder bound at `~/Desktop/LinuxOnDeX`, `/proc`,
`/sys` and `/dev`, and links: one inside, one absolute to `/etc`, one climbing
past `/`, one to a path of the computer's outside it, one as proot writes them,
one to itself), then the page in a visible Edge window. 123 checks, all passed
(and again late on 2026-11-20, on the server with each request's own
deadline and the store's rename tried again):

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
