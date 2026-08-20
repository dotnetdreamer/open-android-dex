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
| Staging | `LinuxService.stageViewer()` | copies the five files into the guest per spawn |
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

`LinuxActivity.probeVncHealth()` runs every 1500 ms for the life of the window
and is the only thing that ever takes the "connecting" card down. It reads:

```js
var s = document.getElementById('status') || document.getElementById('noVNC_status');
var c = document.querySelector('#screen canvas') || document.querySelector('canvas');
return (c && c.width>0 && c.height>0 ? 1 : 0) + '|' + (s ? s.textContent : '');
```

and treats a status containing `closed`, `Failed` or `went wrong` as a dead
session worth reloading, on a 40-try budget shared with its load retries.

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

## Three interaction methods, and why the default is none of ours

**Direct is the default, and it is the behaviour this window had before any of
this existed.** noVNC handles the input itself: a pointer goes exactly where it
is pointed, its own `GestureHandler` answers touches, and nothing here
recognises anything. That is the right answer whenever there is a real pointer
to follow, which is the ordinary case — the desktop on a Windows or macOS
machine, where the mouse should simply behave like a mouse. The other two exist
for the case Direct cannot serve: a phone, where there is no pointer at all.

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
  a 1280-wide desktop through a 360-wide window.
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

`clipViewport`, `dragViewport` and `resizeSession` are all off. `dragViewport`
hijacks button 0 wholesale; `resizeSession` sends the container size in **CSS
pixels**, which on a portrait phone would drag the Linux desktop down to about
360×760 and leave XFCE unusable.

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

## The phone gets a fixed desktop

`requestStart()` used to send the window's own pixels to `Xvnc -geometry`. On
the desktop display that is right and still happens: the viewer scales the
framebuffer to the stage in CSS px, so a framebuffer sized in device pixels
lands 1:1 whatever dpi the display-size preset picked. On the phone's own panel
at density ~2.75 it asked for a ~972×2062 desktop and then squeezed it back into
~354 CSS px of width: XFCE's 10 pt menus end up under a millimetre tall, and no
amount of chrome design rescues that. The phone now gets a flat 1280×800 desktop
that it pans and zooms around, which also means rotating the phone does not
reflow XFCE.

**The two are told apart by display id, not by density.** Density looks like it
would work and does not: the desktop display's density is whatever the
resolution and size presets in Settings computed — 1.0 only at the 1080p preset,
the same trap `custom-titlebar-v2.md` records for the caption. A density test
would have sent a 1280×800 guest to a 2560-wide desktop at three of the five
1440p presets, including the default one.

**This changes `$GEO` for containers that already exist.** The next spawn
resizes their desktop.

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
and its own settings and localisation modules. A page beside it costs five files
and owns its own contract.

**Localising the page.** `assets/web/` is the only precedent and it is English
only; the strings here are in one frozen table so the fix is an object swap, and
the route in is a parameter on the URL `LinuxActivity` already builds. Recorded
as a known hole rather than left silent. `#status` is excluded from it forever —
see the probe contract.

## What is unproven

None of this has run on a device. In rough order of how quietly it would fail:

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
  injected below `PointerChoreographer`. If they arrive as touch, the desktop
  gets phone-sized chrome — functional, wrong-looking.
- **Whether the window insets land right in every orientation.** The page has no
  `env(safe-area-inset-*)` of its own — `LinuxActivity.applyInsets()` pads the
  root with the real ones, because in a WebView `env()` reports the display
  cutout and never the system bars, so it was zero on a phone whose status bar
  was sitting on the control bar. Measured wrong once already; verify in
  landscape and with a cutout.
- **Whether a docked bar over XFCE's own panel is tolerable**, or whether the
  bar should default to collapsed after the first session.

## Costs

Five files, ~102 KB, copied into the guest on every runtime spawn — a few
milliseconds of a start that already takes seconds. Nothing new runs in the
guest, no new port, no new process, and no new permission. The APK grows by
~32 KB rather than the same 102: assets ship DEFLATEd and nothing exempts these
(measured on the debug APK — 104,360 bytes of source compress to 32,489).
