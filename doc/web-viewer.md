# The Web viewer

A browser tab as a second seat at the phone: the launcher streams this phone's
display to it and takes clicks, typing and dropped files back. Everything that
runs on the phone is inside the launcher APK — no PC app, no daemon, no shell
uid. The one optional piece outside it is a relay (`openandroiddex-signal/`) for
the case where nothing can reach the phone at all.

**The transport is WebRTC and only WebRTC.** The first version of this also
streamed H.264 down a WebSocket, with a fragmented-MP4 muxer in the page, a
WebCodecs path, a JPEG fallback, a UPnP port forward and a tunnel-address field.
Every one of those existed to work around the phone being hard to reach — which
is the problem WebRTC solves — so all of it was deleted rather than kept as a
fallback nobody would be able to reason about. `RTCPeerConnection` is not gated
on a secure context (unlike WebCodecs), so this costs nothing even on a plain
`http://192.168.x.x` page.

This record exists for the same reason `custom-titlebar-v2.md` does: three of
the decisions below look arbitrary until you know what was measured or what the
platform refuses, and re-litigating them costs days.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| Settings, secrets, addresses | `Web.java` | prefs keys, the access code, the capture size |
| Page + signalling door | `WebServer.java` | four routes, the lockout, the Host check |
| WebSocket codec | `WebSocketConn.java` | RFC 6455 both ways — signalling only |
| Vocabulary | `WebControl.java` | what a viewer may say on the control channel |
| Control | `WebInput.java` | pointer and typing, through `CaptionService` |
| Files | `WebFiles.java` | listing, upload, the desktop's drop card |
| WebRTC config | `WebRtc.java` | ICE servers, the room, the rendezvous address |
| WebRTC capture | `DisplayCapturer.java` | a `VideoCapturer` over the projection |
| WebRTC peers | `WebRtcHub.java`, `WebRtcPeer.java` | one shared track, one connection per viewer |
| WebRTC files | `WebRtcFiles.java` | transfers on the data channel, with backpressure |
| Rendezvous client | `WebSignal.java` | the phone's outbound socket to the relay |
| Lifecycle | `WebService.java` | the foreground service that holds all of it |
| Consent | `WebConsentActivity.java` | the system capture dialog, in its own task |
| The window | `WebActivity.java` | addresses, code, and every switch |
| The page | `assets/web/` | two session kinds, one transport, input, files |
| The relay | `openandroiddex-signal/` | rooms, `/ice`, and the viewer page |

## What gets streamed, and why it is the phone's display

`MediaProjection` mirrors the display the user consented to. There is no public
way to ask for another one:

- `VirtualDisplayConfig.Builder` has no `setDisplayIdToMirror` outside the
  system (checked against the API 36 stubs — the builder exposes flags,
  categories, brightness and surface, and nothing else).
- Mirroring an arbitrary display through `DisplayManager` needs
  `CAPTURE_VIDEO_OUTPUT`, which is privileged.
- An app-created virtual display is untrusted, so no other app's activity may be
  placed on it — `ActivityStarter` refuses unless the activity opts into
  embedding. So we cannot build our own desktop display either.

Consequence: **with a PC attached, the DeX desktop is on scrcpy's display and
the viewer shows the phone behind it.** Without a PC, the launcher *is* the
phone's display and the viewer shows the desktop shell. The second case is what
this feature is for, and it is the case the UI is written for.

On Android 14+ the consent dialog otherwise offers "a single app" as well as the
whole screen; `MediaProjectionConfig.createConfigForDefaultDisplay()` asks for
the display and skips the picker.

## Control is accessibility, because nothing else is available

`INJECT_EVENTS` is signature-only. Nor does the `wmd` daemon help: the measured
finding in the window-daemon notes is that `input -d <display>` never lands on
the desktop's virtual display. What an app *can* do is
`AccessibilityService#dispatchGesture`, which is a real touch to every app on the
phone, and `performGlobalAction` for the navigation keys. The launcher already
runs `CaptionService`, so the capability was one XML attribute
(`canPerformGestures`) away.

What that costs, stated in the UI as well as here:

- A mouse becomes a finger. No hover, no cursor, no pointer the phone knows about.
- No key codes. Typing goes through `ACTION_SET_TEXT` on the node that holds
  input focus — fine in ordinary apps, nothing at all in a game or a canvas.
  Enter is `ACTION_IME_ENTER`; Back/Home/Recents/Quick settings/Lock/Screenshot
  are global actions.
- Drags are a queue. A continued stroke may only be dispatched once its
  predecessor has completed, so pointer moves are coalesced and the newest
  position wins — which is also the behaviour you want.

`GestureDescription.Builder#setDisplayId` (API 30) is spelled out even though it
is set to the default display: it is the one call a future desktop-display
viewer would need changed.

## "From anywhere" — WebRTC, and the two things it needs

The socket transport only works if the browser can already reach the phone: same
network, a tunnel, or a forwarded port. On mobile data behind carrier-grade NAT
none of those exist and none can be made to. WebRTC is the only thing that
crosses that, and it is what the viewer prefers everywhere — it is also lower
latency than any fallback and it adapts to the link.

**Two separate things have to be true**, and conflating them is the usual way
this gets built wrong:

1. **A relay (TURN)** carries the media when neither end is directly reachable.
   STUN alone is not enough behind symmetric NAT.
2. **A rendezvous** is where an offer and an answer meet. TURN cannot do this —
   it relays between peers that already know each other's relayed addresses,
   which is exactly what has not happened yet.

When the phone is reachable, its own server is the rendezvous: signalling is
`{"t":"rtc"}` messages on the WebSocket that is already open, and nothing extra
runs anywhere. When it is not, the phone dials **out** to a relay and waits
there. `openandroiddex-signal/` is that relay — ~400 lines of dependency-free
Node meant to sit beside an existing coturn. It carries kilobytes, never sees
the access code (checked on the data channel, after the connection exists), and
never sees the room in a request line (it is in the URL fragment, which browsers
do not send).

The dependency is `io.github.webrtc-sdk:android` — the only third-party
dependency in this APK, and the abandoned `org.webrtc:google-webrtc` is not a
substitute (it stopped at M92). `abiFilters` holds it to the two ABIs the APK
already ships, at ~12 MB of native code each.

### How the pieces fit

- **The capture is lazy.** `WebService` owns the projection; `DisplayCapturer`
  hangs a virtual display off it when the first viewer joins and releases it
  when the last one leaves, so a session nobody is watching costs nothing.
- **The phone offers.** It has the media, so there is exactly one offer/answer
  per viewer and the browser needs no renegotiation state machine.
- **Nothing is sent before the code is checked.** The video sender is created
  with its encoding *inactive*, so the track is attached and transmitting
  nothing until authentication succeeds. Per-sender rather than per-track,
  because the track is shared by every peer. Adding the track after auth would
  have meant a second offer — and a leak if it were ever got wrong.
- **A session token counts as proof.** A viewer that came through the HTTP door
  replays its token on the data channel rather than being asked for the code
  twice. Rendezvous viewers have no token and use the code.
- **Files move on the data channel**, chunked at 16 KB and paused against
  `bufferedAmount` in both directions. SCTP will accept far more than the link
  can carry and then die of it. There is no HTTP file API any more: one path
  that works in both session kinds beats two that each work in one.

### One bug worth remembering

`openandroiddex-signal`'s tests caught it: a socket taken over from an HTTP
upgrade in Node is left **half-open** when the far end goes. It emits `end` on
the FIN and never emits `close` until this side is destroyed too. Listening only
for `close` meant a viewer that shut its tab was never reported gone and its room
entry lived forever. `test/signal-test.js` covers the whole room lifecycle for
that reason.

## The door

The server binds every interface by default, because a viewer reachable only
from the phone is not a viewer. Three things hold it shut:

1. **A six-digit code**, exchanged once for a 128-bit session token.
   `SecureRandom`, compared in constant time, shown only in the window — never in
   a link, a log or a URL.
2. **A lockout per client address**, starting at 30 s after five failures and
   doubling to ten minutes. Per address rather than global so one fumbled code
   on a laptop cannot lock the owner out on their tablet.
3. **A `Host` header check.** A LAN server on a phone is the classic
   DNS-rebinding target: a page on the internet points its own hostname at this
   address and then talks to it as same-origin. Only literal addresses,
   `localhost`, and the tunnel hostname the user configured are answered.

Mutating routes additionally require the token in `X-Dex-Token`, which a
cross-origin form cannot set, so a cookie alone never writes. Downloads are
always `Content-Disposition: attachment` — this server hands out whatever is on
the phone's storage, and rendering someone's stored HTML in this origin would
put their file inside the viewer's session. Every path is canonicalised against
the configured root before anything opens it.

There is no TLS. This is `http://` on a local network and the window says so;
a tunnel in front of it supplies TLS, and that is also the recommended way to
reach it from outside.

## Files

Uploads land in `/sdcard/Download` — the same folder a file dragged onto the
desktop from a PC lands in — and narrate themselves over the same
`ACTION_TRANSFER` broadcast, so a file dropped into the browser tab raises
exactly the card on the desktop that a scrcpy drop does. One drop story, two
ways in. The `seq` extra is deliberately not sent: it exists so the card can
ignore a replay from a restarted PC, and a second counter from here would look
exactly like that restart.

The body of an upload is the file, raw rather than multipart: a browser can send
a `File` as the body of an XHR unchanged, so there is no boundary parsing here
and no copy of the file in the page's memory there. The name rides in the query
string and is stripped of separators before it becomes a path.

The all-files grant decides how much works. With it (the launcher already asks
for it for the Linux shared folder) shared storage can be browsed and written.
Without it, scoped storage denies this uid `open()` under `/sdcard`, so listing
comes back empty and uploads take the MediaStore route instead — no permission,
write-only, Downloads only. The page is told which it is looking at.

## Process placement

`WebService` runs in the **launcher's own process**, unlike `LinuxService` and
`DockerService`, which were split out because a foreground service that misses
the five-second `startForeground` deadline dies with an uncaught exception and
used to take the desktop with it.

The trade goes the other way here: the control path runs through
`CaptionService`, which is the accessibility service in this process, and
nothing else on the phone can dispatch a gesture. Crossing a process boundary
would put an IPC hop on every pointer move — the one message that must not
queue. The deadline is answered instead by entering the foreground before
touching a socket or an encoder, and by two guards:

- `WebService.stop()` no-ops when nothing is running. Starting a service of
  declared type `mediaProjection` with no live projection cannot enter the
  foreground at all on Android 14+.
- If `startForeground` throws anyway, the service shuts down immediately in the
  catch rather than waiting to be killed.

The capture token is not ours to keep: it is granted for one session, cannot be
re-used once stopped, and is revocable from the status bar. So a settings change
rebuilds the encoder in place (`WebStream.reconfigure`) rather than restarting
the projection — the user is never asked to consent twice for turning the
quality down.

## The window that went black

Measured, not theorised, so it does not get rediscovered:

Starting the viewer made the **Web window itself go black on the DeX display**.
The cause is Android 15's sensitive-content protection. While a MediaProjection
is running, a window showing a password field has its whole surface marked
secure — and a secure surface is blanked on every display that is not itself
secure. scrcpy's virtual display is not secure, so the window vanished on the
only screen the user has.

`dumpsys SurfaceFlinger` says it plainly, and says it about exactly one of our
windows:

```
Layer [152248] (Secure) …/.WebActivity…
  input{(TRUSTED_OVERLAY | DROP_INPUT | SENSITIVE_FOR_PRIVACY) …}
  isSecure=true
```

The LauncherActivity layers — same app, same process, same display — are not
secure. The only difference was the TURN password `EditText`. The fix is the
platform's own opt-out, `View.setContentSensitivity(CONTENT_SENSITIVITY_NOT_SENSITIVE)`
(API 35), with our own masking left in place.

**The same thing happens to other apps.** While the viewer is running, any app on
the desktop that shows a password field will black out on the DeX display for as
long as that field is visible. That is the platform protecting the capture, it
applies to every app, and there is nothing this project can do about it short of
making the desktop display secure — which is not ours to create.

Ruled out along the way, so nobody re-walks it: no exception in the launcher
process, TextViews were being constructed, the window reported `HAS_DRAWN` at
the right rect, and the caption service's launch curtain is only 40 px tall.
Note also that `screencap -d <display>` and `uiautomator dump --windows` both
refuse to see the scrcpy display, so SurfaceFlinger's layer dump is the tool
that works here.

## Not verified on a device

Everything here compiles and the muxer is checked under Node. None of it has run
on a phone yet. The parts most likely to need a device pass:

- Whether `dispatchGesture` lands where expected under a scaled stream, and how
  a drag feels through the coalescing queue.
- Whether the AVC encoder on a given device emits B-frames despite
  `KEY_MAX_B_FRAMES`, which would break the MSE path's one-frame-behind timing.
- Whether One UI's accessibility consent re-prompts after `canPerformGestures`
  changed from `false` to `true` on an existing install.
- UPnP against a real router.
- The whole WebRTC path on a device: whether `DisplayCapturer` and `WebStream`
  really can hold two virtual displays off one projection at once, whether the
  inactive-encoding gate behaves as documented on this libwebrtc build, and
  whether `WebSignal`'s hand-written client handshake is accepted by the relay
  (the relay's own half is tested; the phone's is not).
- ICE against a real coturn, including the relay-only mode.
