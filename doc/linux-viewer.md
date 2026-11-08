# The Linux viewer

The Linux window is a WebView onto XFCE running under proot, and until now the
page inside it was Ubuntu's own: `vnc_lite.html`, whose entire chrome is a blue
strip carrying the text "Connected to root@localhost" and one button, "Send
CtrlAltDel". On the desktop display that is a second title bar underneath the
one `CaptionService` already draws. On a phone — where this window is the whole
screen and the only pointer is a finger — it is a strip that answers none of the
questions a finger asks: how to right-click, how to get a keyboard, how to see a
1280 px desktop through a 360 px window.

So the page is ours now. It lives in `assets/linux/novnc/`, is staged into the
guest beside Ubuntu's own pages, and gives the window a control bar that
collapses into a floating button, three named interaction methods, a keyboard
that can be raised and dismissed, and zoom and pan.

Status: **written and compiled, not yet run on hardware.** See "What is
unproven"; several entries are the difference between this working and this
being subtly wrong in a way nothing reports.

This record exists for the same reason `web-viewer.md` does: four of the
decisions below look arbitrary until you know what the platform refuses.

---

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| Markup, icons, the probe's three elements | `assets/linux/novnc/dex.html` | the DOM contract |
| Skin | `assets/linux/novnc/dex.css` | tokens, chrome states, the two media queries |
| Connection, coordinates, chrome | `assets/linux/novnc/dex.js` | RFB, status, zoom/pan, the state machine |
| Touch, and the only pointer bridge | `assets/linux/novnc/dex-input.js` | both gesture grammars, synthetic events |
| Keyboard | `assets/linux/novnc/dex-keys.js` | the sink, the padded diff, latches, the key row |
| The guest's size | `assets/linux/novnc/dex-size.js` | how big a desktop a window gets; `LinuxGuestSize` is the launcher's copy |
| Staging | `LinuxService.stageViewer()` | copies every file in that directory into the guest per spawn |
| The window | `LinuxActivity` | the URL, the retry ladder, the health probe |

## Why the page is ours, and why it is served from inside the guest

It must be same-origin with `/core/`. The page imports noVNC's own ES modules —
`RFB`, the keysym tables, `releaseCapture` — and websockify sends no CORS
headers, so a page loaded from anywhere else could not import any of them.
websockify's `--web` is a plain static root, so a file appearing in
`/usr/share/novnc` is served with no configuration at all.

**Staged on every runtime spawn, from `LinuxService.startRuntime()`.** Not from
`provision()`, which early-returns for a guest that is already set up: staging
there would mean bumping `FEATURE_LEVEL` — and re-running the whole setup
script, apt included — every time a line of the page changed. A failed copy is
caught and logged and the container still starts; the stock pages are still on
disk. Because the copy happens once per spawn, the runtime pid is exactly the
version of what is on disk, which is why the URL carries `&v=<rtPid>`:
websockify sends no `Cache-Control`, and without a changing query the WebView
serves yesterday's page out of its heuristic cache.

Ubuntu's `vnc.html` and `vnc_lite.html` are left untouched beside it. There is
deliberately **no automatic fallback to them** when `dex.html` fails: two viewer
contracts against one health probe is a worse failure mode than one error screen
with a Retry on it.

## The contract with the health probe

`LinuxActivity.probeVncHealth()` runs with every status poll for the life of
the window — every 1500 ms, and every 250 ms while the card is up — and is the
only thing that ever takes the "connecting" card down. It reads:

```js
var s = document.getElementById('status') || document.getElementById('noVNC_status');
var c = document.querySelector('#screen canvas') || document.querySelector('canvas');
return (c && c.width>0 && c.height>0 ? 1 : 0) + '|' + (s ? s.textContent : '');
```

and treats a status containing `closed`, `Failed` or `went wrong` as a dead
session worth reloading, on a 40-try budget shared with its load retries. The
grace a dead page gets before that reload is 1.5 s of time, not a count of
polls, so the faster cadence under the card does not shorten it; the first
eight load retries come 250 ms apart and the rest a second apart, as they all
used to.

The faster cadence takes the card down about 1.5 s sooner — 1.7–2.0 s from the
launch instead of 3.3–3.4 s, measured on the S25 — and onto a desktop that is
still arriving: XFCE has its backdrop up by then and its panels about 1.3 s
later, whole at 3.05–3.3 s against 3.3–3.4 s before. So the card no longer
hides the guest drawing itself, and `linux-rt.sh` paints the X root in the
backdrop colour the moment Xvnc answers: before that, the earlier card came
down on the bare root window, which is black, for 0.75 s of every start.

Four rules follow, and **nothing errors when one of them is broken**:

1. **`#status` is written only from a frozen table of English literals.** Never
   interpolated: the desktop name and a security failure's reason are
   server-supplied text that could contain "Failed" while we are still
   connecting. Never localised: the Java side does a substring match on English.
   No healthy string may contain any of the three tokens — no "Keyboard closed".
2. **There is exactly one `<canvas>` in the document and RFB creates it.** The
   probe's fallback selector takes the first canvas in document order, and a
   bare `<canvas>` defaults to 300×150 — i.e. "painting" — which would lift the
   card over a blank page. Every icon is inline SVG and both rings are `<div>`s
   for this reason alone.
3. **`#status` carries `Loading` in the markup**, before any script runs. The
   probe can and does fire mid-parse.
4. **A clean disconnect writes a dead token too.** Stock `vnc_lite` says
   "Disconnected" for a clean close, which contains none of the three tokens —
   and because `Display` leaves the canvas at its last size, `canvas.width`
   stays non-zero and the probe reports a healthy session forever. The user is
   left looking at a frozen last frame with no error and no Retry.

**The page never reconnects itself.** `LinuxActivity` owns the only retry
ladder; a second loop in here would race `webView.reload()`, burn the shared
budget invisibly, and put two sockets on one Xvnc.

## Every pointer action is a synthetic DOM event

**noVNC has no public method for sending a pointer event — in this version or
any other.** `_sendMouse` is private, and `RFB.messages.pointerEvent` needs the
private socket and the private display. What it does have is a handler bound to
its own canvas which reads exactly `type`, `clientX`, `clientY` and `button`,
and never inspects `isTrusted` or `buttons`. So a `MouseEvent` we construct and
dispatch at that canvas is indistinguishable from a real one by the time it
reaches the wire, and that is how every gesture below leaves the page.

Two details of that path are load-bearing:

- **`bubbles: false`, plus an explicit `releaseCapture()` on every button-up.**
  RFB's `mousedown` calls `setCapture()`, which in this WebView appends a
  full-screen div at `z-index: 10000` to `<body>` and installs `mousemove` /
  `mouseup` proxies on `window`. A *bubbling* synthetic `mouseup` reaches those
  proxies, which clone it and re-dispatch it at the canvas — every drag move and
  every button-up doubled, which collapses a selection in any editor.
  Non-bubbling *alone* leaves that div over the whole UI forever and leaves the
  window proxies installed to double genuine mouse events on the DeX display.
  Only both together are correct. Our own chrome sits at `z-index: 10100`.
- **`releaseCapture` is imported dynamically, with a fallback path and a no-op
  floor.** It is not in `docs/API.md` and its module has moved between versions;
  a static import of the wrong path would kill the page outright.

Keyboard is the opposite story: `sendKey(keysym, code, down)` is public and
complete, and is what every key goes through.

## Three interaction methods, and which one each window opens on

**Direct is the behaviour this window had before any of this existed.** noVNC
handles the input itself: a pointer goes exactly where it is pointed, its own
`GestureHandler` answers touches, and nothing here recognises anything. That is
the right answer whenever there is a real pointer to follow, which is the
desktop display's ordinary case — a mouse on the PC should simply behave like a
mouse. The other two exist for the case Direct cannot serve: a phone, where
there is no pointer at all.

**So the default is per window, not per app: Direct on the desktop display,
Mouse in the phone's window.** Direct on a phone means poking at a desktop of
900-odd pixels across through a ~400 px window with a fingertip that covers a
40 px circle of it — a 1 px window border, a menu item and a scrollbar are all
inside one touch.
Mouse is the only one of the three that can hit any of them: the cursor moves
relative and accelerated, and the click lands at a ring you can see rather than
under the finger hiding it. Touch stays one tap away for the times the guest
really is being used as a touchscreen.

**The page cannot work out which window it is in, so it is told.**
`LinuxActivity` puts `&ctx=phone|desktop` in the URL, decided by display id (its
`onPhone`). Both ways of guessing fail here and in opposite directions:
`pointer: coarse` is what the scrcpy display reports for injected events, and
the CSS viewport is phone-narrow on a 1440p desktop at most display-size
presets. The same parameter now also sets the desktop's tighter chrome band at
boot, which used to wait for the first real mouse event to arrive — an event
that may never come if scrcpy's injections land as touch.

**A method the user picks is remembered per window, under its own key**
(`dexlinux.mode.phone` / `.desktop`). One shared key is the obvious thing and is
wrong: both windows are the same origin, so they are the same `localStorage`,
and a phone session would leave Mouse behind as the next desktop session's
starting point — the exact default this exists to get right. Applying a default
never writes it, so a default stays a default. The old un-suffixed key is read
once as the desktop's, because before the app-list entry every session was one.

**Moving the window between displays re-decides it.** Normally by rebuild — a
display change recreates the activity, so the page reloads with the new `ctx` —
and where the two displays agree on density and the platform sends a plain
configuration change instead, `LinuxActivity` calls `window.dexContext(ctx)` on
the live page, which lands on that window's remembered method or its default.

**A third window is on a computer: `&ctx=pc`.** Through remote access, the same
page is opened from a laptop (doc/remote-access.md, "The desktop, in a
window"), framed by the desktop app or in a browser tab. There it is Direct and
nothing else — a mouse and a keyboard of its own; a touchscreen laptop's finger
is a pointer too — so the method, the on-screen keyboard and the Remote access
button go, and a fullscreen button comes. Zoom goes too: there the picture's box
is the whole stage and the guest's screen is resized to it (noVNC's
`resizeSession`, here only), so the desktop is always one pixel per pixel and
Ctrl+scroll belongs to the guest. F11 is taken in the capture phase and
passed up to the window rather than to the guest, and the status words the
health probe reads are posted to the window as well, because on a computer the
window around the page is what reconnects.

The clipboard crosses there too, and the window around the page is what
decides when (remote-access.md, "The clipboard"): the page posts noVNC's
`clipboard` events up, with every CRLF made a LF — noVNC 1.3 does only the
first — and hands what the window posts down to `clipboardPasteFrom`. A paste
waits until noVNC has heard which clipboard Xvnc speaks
(`_clipboardServerCapabilitiesFormats`, private, like the 1.3.0 internals
listed under "What is unproven"): sent sooner, it goes the Latin-1 way and
mangles everything outside Latin-1. The bar gains a clipboard switch, hidden
until the window says which way it is — so it never appears in a plain browser
tab — and struck through while sharing is off. The window keeps the setting;
the button only asks it to flip.

noVNC's own vocabulary is fixed — one-finger drag is a left-drag, two fingers is
scroll, and pinch holds Ctrl and asks the *remote app* to zoom. It is one mode,
it has no viewer pan, and it cannot be reshaped into two, which is why Touch and
Mouse replace it rather than extend it. They do that by intercepting
`touchstart/move/end/cancel` in the **capture phase on `#stage`**, which beats
AT_TARGET on the canvas no matter who registered first — and that matters,
because RFB attaches its listeners synchronously inside its own constructor, so
there is no registration race we could win. In Direct the interceptor returns
before it stops anything, so the events reach noVNC untouched.

**Only `touch*` is intercepted, in any method.** Every mouse event from the
scrcpy display reaches the canvas exactly as it did before this page existed.
That is the whole reason for capture-on-an-ancestor rather than an opaque
overlay, which would steal real mouse events, and rather than
`rfb._gestures.detach()`, which is a private field, also starves RFB's own focus
handling, and could not be undone when the user picks Direct again.

**Touch** — the device is a remote touchscreen:

| Gesture | Recognised as | Sent |
| --- | --- | --- |
| Tap | released before the long press, travel ≤ 12 px | left click where you touched |
| Long tap | held 500 ms, released without moving | right click |
| Long press and drag | held 500 ms, then moved | left button down, drag, up |
| One finger drag | moved before 500 ms | scroll wheel |
| Two fingers | centroid moves, separation steady | pan the view — nothing on the wire |
| Pinch | separation changes by 24 px | zoom the view — nothing on the wire |

**Mouse** — the device is a trackpad. A virtual cursor lives in framebuffer
coordinates and every click lands at *it*, not at the finger:

| Gesture | Recognised as | Sent |
| --- | --- | --- |
| Drag | one finger past 12 px | the cursor moves, accelerated |
| Tap / long tap | as above | left / right click at the cursor |
| Double-tap and drag | second press within 300 ms, then movement | left button down, drag, up |
| Two fingers | centroid moves | scroll wheel at the cursor |
| Pinch | as above | zoom the view |

Three decisions inside that:

- **The pointer gain is divided by the live scale.** Trackpad feel lives in
  screen space: 10 px of finger moves the pointer 10 px *on screen* at every
  zoom level. The cursor clamps at the framebuffer edge, never at the viewport.
- **Edge auto-pan is mandatory, not a nicety.** Mouse mode has no pan gesture —
  two fingers is scroll — so without it there is no way to reach the far side of
  a desktop zoomed in past the window.
- **Right-click fires on lift, not at the 500 ms mark.** It is the only way
  long-tap-right-click and long-press-drag-select can share one finger, and both
  are wanted. The ring that fades in at 500 ms is what makes the two outcomes
  legible before the finger leaves.

Deliberately absent: **no three-finger gesture** (in X, middle click pastes the
primary selection into whatever has focus — the most destructive thing available
by accident, and a resting palm fires it; middle click is an arm-the-next-tap
chip in the sheet instead), and **no double-tap-to-zoom in either mode** (it
would eat every double-click). Taps are never debounced: two quick taps are two
quick clicks and XFCE's own timer decides what they mean.

## Size the picture, never scale it

**A CSS `transform: scale()` anywhere between the canvas and the viewport
silently breaks click accuracy, and nothing reports it.** noVNC maps a click
with `getBoundingClientRect()`, which a transform changes, and then divides by a
scale it keeps itself, which a transform does not. Every click lands off by the
factor and drifts further from the transform origin.

So `#screen` is **sized** in pixels to zoom it and **translated** to pan it — a
translation moves the box and the pixels by exactly the same amount. Its aspect
ratio is the framebuffer's, so noVNC's own `autoscale()` resolves to precisely
the zoom we asked for and its coordinate maths stays exact at every level, using
nothing but documented API.

The scale used for our own coordinate conversions is **re-measured from noVNC's
rendered box on every call**, never read back from the zoom variable: noVNC
rescales from a `ResizeObserver`, so its number lands a frame after ours, and
deriving from its box is the only mapping that cannot drift mid-animation.

`clipViewport` and `dragViewport` are off, and `resizeSession` is on only on a
computer. `dragViewport` hijacks button 0 wholesale; `resizeSession` sends the
container size in **CSS pixels**, which on a portrait phone would drag the
Linux desktop down to about 360×760 and leave XFCE unusable — so the phone's
window and the desktop display's ask for sizes of their own (see "The desktop
follows the window").

## The keyboard, and why it is a text diff

There are two paths and never both. On the desktop display a physical keyboard
types into the canvas and noVNC's own `Keyboard` handles it, scancodes and all;
that path is better than anything written here and is untouched. On a phone a
hidden textarea holds focus and every key is synthesised.

Focusing a real, on-screen-but-invisible field inside the user's own gesture is
the only thing that raises the IME, and `blur` is the only honest signal that it
went away. `display: none` would make the field unfocusable, so it is parked
off-screen instead.

**Why a diff rather than `keydown`:** with a composing IME, Chromium reports
`keydown` as `keyCode 229` / `key "Unidentified"` for essentially every
character key, and with an *empty* field it often emits no `beforeinput` for
Backspace at all, because there is nothing to delete. So the field is kept
padded with underscores, the caret is pinned at the end, and typing is read as a
prefix difference — which is the shape noVNC's own `vnc.html` uses, with a
working reference sitting on the same box. Composition events suppress the diff
while an accent or a CJK candidate is being built.

**Modifiers latch rather than being held.** One tap arms, two taps lock, and the
modifier is pressed around the key and released again — a locked modifier left
physically down would turn every later click into a Ctrl-click on the guest,
with nothing on screen to show it. The exception is the auto-repeating arrows,
which hold their modifiers for as long as the key is down, because otherwise
Shift+Arrow selects nothing. Latches clear on blur, on disconnect and on a mode
change.

**Esc is in the key row for a desktop reason, not a phone one.** Per
`escape-leaves-fullscreen.md`, a bare Escape is swallowed by the host's
low-level hook in both directions while the DeX desktop is fullscreen and in
front — so on a fullscreen desktop there is otherwise no way to send Escape to
the guest at all. The page itself never consumes Escape and never calls
`showModal()` or `requestFullscreen()`, both of which would: `LinuxActivity`
deliberately hands Escape to the guest the moment the WebView exists.

## The desktop follows the window

Turn the phone and XFCE is laid out again for the other orientation, edge to
edge; drag a window on the desktop display wider and the desktop gets the
width. The page asks Xvnc for a screen of the window's shape whenever the
window's box changes (`followWindow` in `dex.js`); Xvnc resizes and tells every
viewer, and noVNC's canvas follows. Nothing reloads and nothing reconnects:
`LinuxActivity` declares orientation and size in its `configChanges`, so a
rotation is a resize of the same page.

Before this the phone had a fixed 1280×800, letterboxed into either
orientation — most of a portrait screen black, and a third of a landscape one —
and the desktop display kept whatever size the start gave it.

**How big** is `dex-size.js`. `LinuxGuestSize` is the launcher's copy, which
`requestStart()` starts the desktop at, so that the page's first ask finds it
already there instead of laying XFCE out a second time a moment after it
started. For that the launcher needs the box as the page will measure it, bar
and keys row included, and both of those live in the page's localStorage: so
the window reads them off the live page with each health probe
(`rememberChrome`, kept in `files/linux-viewer-chrome`) and a start goes by the
last reading — the page's own defaults until there is one. And `LinuxService`
no longer floors a start at 800×600, which moved a phone 320 CSS px wide from
the 720 across it asks for; its floor is 320×200 now, a bound on nonsense, and
`test/linux-guest-size-test.mjs` checks that neither its floor nor its ceiling
moves any size a window asks for. What can still differ is the WebView's own
measure of its box, a fraction of a CSS pixel off the launcher's arithmetic,
which can now and then round to the next even size: the page then asks, and
XFCE is laid out once more, two pixels wider or taller.

| window | the guest's screen | for example |
| --- | --- | --- |
| the phone's own screen | 2.25 guest px to the CSS px, in either orientation; the short side 720 to 1200, the long side at most 2400 | a 411-dp phone: 924×1760 upright; on its side 2042×720 with the bar docked, 1930×798 with it folded away |
| a window on the desktop display | the window's device pixels, in whole device pixels per guest pixel (1 around 160 dpi, 2 from 280, 3 from 440); 800×600 to 3840×2400 | a 1080p desktop at 1×: 1920×1036, the bar taken off |
| a computer's window (`ctx=pc`) | noVNC's own `resizeSession`, unchanged: the box in CSS px | remote-access.md |

Why those numbers. The phone's window in device pixels is the wrong answer: at
density ~2.75 it is ~972×2062 of them, and the viewer would squeeze a desktop
that size back into ~354 CSS px of width — XFCE's 10 pt menus under a
millimetre tall, which no amount of chrome design rescues. A fixed scale in CSS
px keeps everything in XFCE the same size when the phone turns, the way the
phone's own apps turn — wherever none of the limits below decides. The 720
floor is for XFCE's dialogs, which stop fitting below it, and a phone on its
side under the docked bar is only about 300 CSS px tall: there the floor
decides, and XFCE is drawn about 5 % smaller than upright (2042 guest px across
859 CSS px, against 924 across 411). With the bar folded away the phone on its
side is at 2.25 too. Either way the text is larger than the old 1280×800 drew
it — a little on its side, a good deal upright. The 1200 ceiling keeps a tablet
or an opened-out Fold from drawing its desktop smaller than a phone does; the
2400 is the CPU's, which draws every pixel and encodes it again for each
viewer, and as tall as `LinuxService` starts a desktop. The short side is made
an exact even number and the long side follows it at the box's own ratio, so
the desktop is the box's shape to within one of its own pixels: under half a
CSS pixel of letterbox in any window a phone has. `test/linux-guest-size-test.mjs`
checks that, and that the two copies agree to the pixel over some 300,000 boxes
and windows.

On the desktop display a desktop sized this way fits its window at exactly
whole device pixels per guest pixel, and `layout()` takes that scale rather
than the fit a hair larger: a scale of 1.001 resamples every line of text. That
holds for a window at least 800×600 guest pixels' worth; a smaller one gets
the 800×600 floor, scaled down to fit it, and is not sharp. Before, the start
sized the guest to the whole window, the bar's 44 px included, and the fit drew
it ~4 % smaller than that.

**The two are told apart by display id, not by density.** Density looks like it
would work and does not: the desktop display's density is whatever the
resolution and size presets in Settings computed — 1.0 only at the 1080p preset,
the same trap `custom-titlebar-v2.md` records for the caption. A density test
would have sent a phone-shaped guest to a 2560-wide desktop at three of the five
1440p presets, including the default one.

**The box** is the stage less the docked bar and a keys row the user pinned
(`steadyBox`). Not less the on-screen keyboard, nor the keys row it brings up:
those come and go, and a desktop laid out again every time the keyboard opened
would be worse than one fitted above it meanwhile. A bar folded into the bubble
gives the desktop its height back, as it does on a computer.

**The keyboard never resizes the desktop**, whichever of two things it does to
the page. Where it covers the page, the box is the same with it up or down,
and a turn made while typing lands at once. Where it resizes the page, nothing
measured while it is up is the window's own size — rotated, the box changes
width and is still the keyboard's height short. The sign is in the two seconds
after the keyboard is raised: the box against the one from just before it
was, the same width and less height (`kbResizes`, believed for good once
seen). Only then, and only against that box — the first version took any step
to the same width and less height while the keyboard was up, and a turn makes
one on its way round (see "Measured on the S25"). From then on the desktop
keeps its size while the keyboard is up, and for a second after it goes, which
is how long such a keyboard takes to give the page its height back; a turn
made while typing lands then, once. The S25's keyboards cover the page.

**When.** 150 ms after the box last changed on the phone, where a rotation is
one change; noVNC's own 500 ms on the desktop display, where a window being
dragged is many. And once at the first frame, so that a page connecting to a
desktop started for another window, or sized since by another viewer, asks for
its own. Never *because* the desktop changed size: that is another viewer's
ask — a computer through remote access, or on the cable — and answering it
would be a tug of war between two screens. The last request to reach Xvnc
wins. That is not always the window that changed last: each waits its own
time, so a computer's window resized a moment before the phone turns asks
after it, and keeps the desktop.

A desktop of a new size opens at that size's own zoom (`zoom.<w>x<h>`) — fit,
until someone zooms it — and centred. The phone upright and on its side are two
sizes, so each keeps its own; but it is the size that is remembered, not the
orientation, and folding the bar away makes a third.

**The message.** noVNC 1.3 asks for a size only through `resizeSession`, and
only for its own box in CSS px. So the page sends the same SetDesktopSize
itself: `RFB.messages.setDesktopSize` is public; the socket it writes to
(`_sock`), the screen it names (`_screenID`, `_screenFlags`) and the flag that
says Xvnc can resize (`_supportsSetDesktopSize`) are not — the same kind of
reach `guestTakesUtf8` makes, checked the same way. Without them the desktop
keeps the size it started at, letterboxed, as before. One oddity is
load-bearing and harmless: noVNC 1.3 keeps the screen id as a byte view
(`rQshiftBytes`), which the message turns into 0, so every ask names a screen
Xvnc does not know. TigerVNC 1.13 takes that for a new layout — it lets its
output go and gives it to the new screen — and resizes all the same; it is how
`resizeSession` has always worked against it.

**The dock** does not follow on its own. `dock.py` stands it on the left at a
length that is a percent of the screen height, worked out once for the height
it saw, and at another height the same percent leaves a gap under the top bar
or runs the dock up into it, over the Applications button. `dockfit.py`,
written into the guest by `linux-rt.sh` at every start, waits on the X server
for the root window's ConfigureNotify and sets the length again through
xfconfd (openandroiddex-linux/README.md, "And it follows the screen") — only a
length it set itself, or before it has set one, a length dock.py could have
made: one someone chose is theirs. XFCE does
the rest itself: xfwm4 fits maximised windows to the new screen and keeps the
others on it, xfdesktop lays its icons out on the new work area, and
xfsettingsd — which compares outputs by id — sees the same one and opens no
display dialog. (Read in their sources, xfwm4 4.18 and xfce4-settings 4.18.4;
see "What is unproven".)

**This changes `$GEO` for containers that already exist.** The next spawn on the
phone starts at the window's shape instead of 1280×800.

### Measured on the S25

2026-11-08, Galaxy S25 Ultra (SM-S938B, Android 17), FHD+ at density 420 — a
devicePixelRatio of 2.625, so a 411.43 CSS px wide window — with the dev build
made debuggable (`ORG_GRADLE_PROJECT_debuggableLauncher=true`) so that the
page could be read through the WebView's DevTools and the guest through
`run-as`. Linux in the phone's own window, opened from the app list; the phone
turned by `settings put system user_rotation`.

| | the box, CSS px | the desktop | checked |
| --- | --- | --- | --- |
| upright, bar docked | 411.43 × 788 | 926×1774 | the start's `START 926x1774` and the first frame the same — laid out once |
| on its side, bar docked | 854.86 × 310.48 | 1982×720 | `xrandr`: `current 1982 x 720` |
| upright, bar folded | 411.43 × 840 | 926×1890 | |
| on its side, bar folded | 854.86 × 366.48 | 1922×824 | started cold at it: `START 1922x824`, the first frame 1922×824, nothing after for 6 s |

A turn reached the desktop 141 to 186 ms after the page saw the window change,
in five turns — the 150 ms wait and Xvnc's answer. Before the window settles,
the old desktop is drawn fitted into the new one for those 150 ms. The page's
DevTools showed the picture's box against the window's: 0.01 × 0.26 CSS px
short upright and 0.05 × 0.01 on its side, with the fit taken from the
stage's fractional size. Taken from `clientWidth`/`clientHeight` — rounded —
as it first was, it was 1.5 CSS px short on its side, and that is fixed now
(see `layout()`).

The dock, read off the X server (the panels' own windows): on its side the top
bar is 1982×27 at 0,0 and the dock 49×694 at 0,26; upright the bar is 926×27
and the dock 49×1747 at 0,27. `dockfit.py` recorded 96.39 and 98.53, and
xfconfd wrote 96.39 to the panel's file. The dock meets the bar both ways —
upright exactly, on its side over the bar's last row: dock.py's formula takes
the bar as `size` × `nrows`, 26, and its window is 27. That pixel is dock.py's,
and `dockfit.py` keeps its formula deliberately (it is how it recognises a
length dock.py made).

The keyboard: the S25's covers the page. Raised, the layout viewport stayed
840 tall and the visual viewport fell to 477; the desktop kept its size,
fitted above the keyboard, and closing it changed nothing either. A turn with
the keyboard up found the first guard's mistake: on the way round, the page
passes through a box taller than the one it lands on — 854.86 × 406.67 for
18 ms, the keyboard hidden — and the step down to 366.48, the same width and
less height while the keyboard was up, was read as the keyboard resizing the
page. The desktop then stayed at the passing box's size, 1924×914, until the
keyboard closed and a second had gone by. The guard now learns only in the
two seconds after the keyboard is raised, and only against the box from just
before it was: a turn changes the width, so it can never pass for that. After
the fix, both ways round with the keyboard up reached the right desktop at
once (186 and 183 ms), and closing the keyboard changed nothing.

Not run on the phone: a dock length set by hand (`xfconf-query` cannot reach
the session's bus from a second proot — "connection is closed" — and Panel
Preferences was not driven; `test/linux-dockfit-test.mjs` covers the rule),
the keys row pinned, the desktop display's window resized, two viewers at
once, and a keyboard that resizes the page, which this phone does not have.
The two fixes were run by copying the new `dex.js` into the guest's web root
and reloading the page; the launcher on the phone was built before them.

## Reaching it without a desktop

The phone branch above was reachable only by accident until `LinuxAppActivity`:
the way into this window was the desktop's tile, and the tile lives on the
scrcpy display. Nothing under it ever needed that display — `LinuxService`
downloads the rootfs, runs the install and hosts the runtime under this app's
own uid — so the feature was a launcher away from standing on its own. It is a
second `MAIN`/`LAUNCHER` activity in the same APK: installing the launcher now
puts **two** icons in the phone's app list, "Open Android DeX" and
"LinuxOnDroid", and
the second one opens the container with no PC, no cable and no session.

**A trampoline, not an `<activity-alias>`,** which is the shorter way to put a
second icon in a drawer. An alias is its own component to the window manager:
the task reports the ALIAS in `RunningTaskInfo.topActivity`, which is what wmd
hands `CaptionService`, and four places compare that name to
`LinuxActivity.class.getName()` to decide what a window is —
`isDesktopTask` (an unrecognised name means "this IS the desktop", so the window
would silently lose its caption), `onClose` (the ✕ would remove the task instead
of asking first), the taskbar's own-window tiles and the Task Manager. It is not
hypothetical: the desktop's tile pulls this very window across from the phone's
display, so an alias-launched window ends up on the desktop wearing the wrong
name. The trampoline keeps one identity and costs one class.

**One window, and therefore one runtime.** `LinuxActivity` is `singleTask` on a
task affinity of its own, so the app icon, the tile and the notification all
raise the same window rather than stacking viewers — and the service refuses to
spawn a second `Xvnc` while one is up (`runtimeUp`). The one case the framework
would get wrong is the icon being tapped while the window is open **on the
desktop**: a start from the phone's task moves that task to the phone's display,
which would take a live session off the screen the user is actually looking at.
So the icon asks `LinuxActivity.isOnDesktopDisplay()` first and says where the
window is instead. That question is asked of the WINDOW, not of the session: a
desktop can be running with this window open on the phone, and there the right
answer is to raise it.

**The phone's window opens as a trackpad.** `&ctx=phone` in the viewer URL makes
Mouse the default there and Direct the default on the desktop display — see
"Three interaction methods" above for why, and for what happens to a method the
user picks instead.

**It asks for one permission, and only here.** `POST_NOTIFICATIONS`, which
nothing in this app had ever asked anyone for: the PC grants what a DeX session
needs over adb, and a system dialog raised for the desktop's window would open
on the *phone's* screen, behind the user. On the phone the foreground service's
notification is the container's only visible presence — that a session is
running, the install's progress, and the way back to a window put aside with
"keep running" — so `LinuxActivity` asks as that window opens, once, and does
not look at the answer: without the grant the platform hides the notification,
it does not refuse the service.

**Back had to grow a question.** On the phone it is the only way out — there is
no caption and so no ✕ — and `finish()` runs `onDestroy`, which stops the
container. Back now opens the same dialog the ✕ does, on both displays, and on
the phone that dialog offers **Keep running** (`moveTaskToBack`, which keeps the
activity and so keeps the container) in place of Cancel, stacked rather than in a
row because two labels side by side overflow the 300 dp panel in German.

## Why not the obvious alternatives

**Native Android chrome over the WebView.** The bar would be easy; everything
behind it would not. Interaction modes, zoom, pan and the keyboard all need to
reach inside the page anyway, so the controls would sit on one side of a
`evaluateJavascript` boundary and their effects on the other — and the whole
thing would still not exist for the desktop-display case, where the same window
is driven by a mouse.

**A `@JavascriptInterface` bridge for the keyboard.** `InputMethodManager` is
the only *deterministic* way to dismiss the IME, and `blur()` is merely reliable.
It would also be the first `addJavascriptInterface` in this APK, exposed to
every page websockify serves, and it needs a `shouldOverrideUrlLoading` origin
lock before it would be safe. Deferred until `blur()` is measured to be
insufficient.

**Patching Ubuntu's `vnc.html`.** It is a package file: an `apt` upgrade
replaces it, and the full noVNC UI is a monolith bound to its own element ids
and its own settings and localisation modules. A page beside it costs six files
and owns its own contract.

**Localising the page.** `assets/web/` is the only precedent and it is English
only; the strings here are in one frozen table so the fix is an object swap, and
the route in is a parameter on the URL `LinuxActivity` already builds. Recorded
as a known hole rather than left silent. `#status` is excluded from it forever —
see the probe contract.

## What is unproven

None of this has run on a device. In rough order of how quietly it would fail:

- **The rest of "The desktop follows the window", beyond what "Measured on
  the S25" ran.** *Tests:* pin the keys row and close and reopen Linux — the
  log's `START <w>x<h>` and the first frame must be the same; set the dock's
  length by hand in Panel Preferences and turn the phone — it must stay as set;
  resize the Linux window on the desktop display — the desktop follows half a
  second after the drag, sharp; and, on a phone whose keyboard resizes the page
  rather than covering it, turn it with the keyboard up — the desktop keeps
  its size until the keyboard closes, then follows once, a second later.

- **Whether hover still reaches the canvas on the DeX display.** The chrome
  layer is `pointer-events: none` with only its controls opting back in, so it
  should — but XFCE tooltips, menu tracking and drop targets all depend on it,
  and this is the one regression that would not announce itself. *Test:* hover a
  panel item and wait for the tooltip; press and slide through the Applications
  menu.
- **Whether the guest's `core/rfb.js` is upstream 1.3.0 unpatched.** Every claim
  about `setCapture`'s behaviour and the private field names rests on it; the
  public API claims hold either way. *Test:* `diff` it against upstream `v1.3.0`
  and `grep -n "export function releaseCapture" /usr/share/novnc/core/util/*.js`.
- **Whether the IME resizes the window, pans it, or simply covers it.** One
  formula covers resize and pan; it cannot cover the third. *Test:* raise the
  keyboard and read `window.innerHeight` against `visualViewport.height` and
  `.offsetTop`.
- **Whether `sink.blur()` dismisses One UI's keyboard.** If it does not, the
  deferred JS bridge becomes required rather than optional.
- **Whether the padded diff is needed at all**, or whether `beforeinput` alone
  would do. IME-dependent. *Test:* type and backspace with GBoard and with
  Samsung Keyboard, and check that an accent and an emoji arrive intact.
- **Whether the public sizing path makes a live pinch feel a frame behind.**
  `USE_PRIVATE_SCALE` was considered and not taken; if it janks, `_display.scale`
  is the same arithmetic without the observer round-trip.
- **Whether `pointerType === 'mouse'` arrives on the scrcpy display.** Events are
  injected below `PointerChoreographer`. It no longer decides the chrome — the
  URL's `ctx` does that at boot — so if they arrive as touch the only thing left
  to notice is that a mouse plugged into the *phone* will not tighten the
  chrome until it moves.
- **Whether the window insets land right in every orientation.** The page has no
  `env(safe-area-inset-*)` of its own — `LinuxActivity.applyInsets()` pads the
  root with the real ones, because in a WebView `env()` reports the display
  cutout and never the system bars, so it was zero on a phone whose status bar
  was sitting on the control bar. Measured wrong once already; verify in
  landscape and with a cutout.
- **Whether a docked bar over XFCE's own panel is tolerable**, or whether the
  bar should default to collapsed after the first session.
- **Whether the desktop's tile still reuses the window after the app icon
  opened it.** A `singleTask` start matches an existing task by affinity, so it
  should move that task to the desktop display rather than build a second
  viewer; the relaunch that follows must report `isChangingConfigurations()` or
  `onDestroy` would stop a container the user only moved. *Test:* open Linux
  from the phone's app list, connect a session, click the tile, and check the
  guest kept running (`rt.pid` unchanged in the log).
- **Whether each window really opens on its own method after both have been
  used.** The storage is per context and the default is never written, so the
  order sessions happen in should not matter. *Test:* open on the phone (Mouse),
  switch it to Touch, close it; open on the desktop — it must be Direct; reopen
  on the phone — it must be Touch. Then check `localStorage` holds
  `dexlinux.mode.phone` and no `dexlinux.mode.desktop`.
- **Whether `ic_linux` reads at 48 dp.** The mark is a terminal prompt drawn as
  three strokes, and thin strokes under a launcher's mask are how icons turn
  into smudges. *Test:* look at it in the app list, in the themed-icon mode, and
  in the recents card.

## Costs

Six files, ~142 KB, copied into the guest on every runtime spawn — a few
milliseconds of a start that already takes seconds. The page itself runs
nothing new in the guest: no port, no process, no permission. The APK grows by
much less than that: assets ship DEFLATEd and nothing exempts these (measured
on the debug APK when they were five files — 104,360 bytes of source
compressed to 32,489).

Following the window adds one process to the guest, `dockfit.py`: a python3
blocked on the X server, which takes no CPU until the screen changes size and
then runs `xfconf-query` twice. Its memory has not been measured. A resize
itself costs a full repaint of the desktop, once, as any window resize on a
computer does.
