# The phone's apps in windows of their own

The computer used to show a phone one way: its whole desktop, in scrcpy's
window. Now the app's **Apps** page lists what the phone's own launcher lists,
with the real names and icons, and a click opens that one app in a native
window on the computer: sized like a phone, sharp at the screen's scale, with
the system's own window controls, its picture decoded by the computer's
graphics hardware. No desktop is set up on the phone at all. Closing the
window closes the app on the phone too, once the person says so (a Mac's
hands it back to the phone, where it was). The phone's Linux apps are on the
same page and open the same way.

It is lighter than the desktop because only the app's own pixels are
composed, encoded, sent and decoded, and an app that is still costs nothing.
It is faster to open than scrcpy's per-app windows because the phone's half is
one process that stays up: a click costs a display, a launch and an encoder,
never a JVM.

This record exists for the reason the others do: several decisions below look
arbitrary until you know what was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The page | `src/features/apps/AppsPanel.tsx`, `App.tsx` | the list, the icons, a click, "Open my apps here when my phone connects" |
| The phone's server | `openandroiddex-wmd/…/AppServer.java` | one process per phone: the socket, the control connection, the warm-up, keeping the displays awake |
| What the phone has | `AppCatalog.java`, `PhoneContext.java`, `Providers.java` | the launcher's apps and their icons, as the shell; the Linux apps, from the launcher |
| A window, phone side | `AppWindow.java`, `AppVideo.java`, `AppTasks.java`, `AppInput.java` | a display, the app started on it, its encoder, its input, the watcher that notices the app has gone |
| The clipboard | `AppClipboard.java`, `apps/clipboard.rs` | one for the phone, both ways, as things are copied |
| The wire | `Wire.java`, `apps/wire.rs` | the framing, both ends |
| The phone's half, from here | `apps/host.rs`, `apps/adbsock.rs` | starting the server, the control connection, reaching a socket on the phone |
| A window's link | `apps/link.rs` | one window's own socket to the server, its reader, writer and clock; what both windows share |
| Phone or tablet | `apps/shape.rs`, `link.rs` (`layout_density`) | the size an app is given by its window's shape, More's Phone size and Tablet size, each app's window kept as it closed |
| The window, Windows | `apps/win/mod.rs`, `apps/win/frame.rs`, `apps/keys.rs` | a Win32 window, its title bar, the mouse and keyboard as Android takes them |
| The picture, Windows | `apps/win/render.rs` | decoding, drawing, presenting |
| The window, Mac | `apps/mac/mod.rs`, `apps/mac/video.rs`, `apps/h264.rs`, `apps/keys.rs` (`mac`) | an AppKit window, its input, the display layer macOS decodes into |
| Linux apps | launcher `AppsProvider.java`, `LinuxWindows.java`, `LinuxWindowActivity.java` | the guest's apps and icons, starting one, its X window on an app window's display |
| Sound | `scrcpy.rs` (the audio companion) | the phone's sound here while any of its apps has a window |
| Where the sound plays | `apps/sound.rs`, `AppAudio.java`, `Audio.java`, launcher `AppsProvider.java` (`media.mode`) | the title bar's menu: this computer, both, or one of the phone's own outputs |
| Dropped files | `apps/drops.rs`, `AppDrop.java`, `AppDropIn.java`, launcher `DropsProvider.java`, `PcDrags.java`, `App.tsx` | a file dropped on a window's app: given to that app, as a drop from another app; on its title bar or the main window: into the phone's Download folder; an APK installed |
| Dragging out | `AppDrags.java`, `LauncherOverlay.java`, launcher `PcDrags.java`, `apps/mac`, `apps/win` | a drag the app lets go of, carried out of its window: dropped on the launcher's pixel, then this computer's own drag |
| What the phone hands over | `AppOffers.java`, `Content.java`, `apps/fetch.rs` | a drag's files and a copied picture's, read as the shell and taken here on connections of their own |
| The phone's notifications | `notifications/`, `AppNotifications.java` | as this computer's own; a window opened at one (doc/phone-notifications.md) |
| The phone as their touchpad | `apps/pad.rs`, `AppPad.java`, launcher `Touchpad.java`, `HostPointer.java` | the phone's screen moving this computer's pointer over these windows (doc/phone-touchpad.md, §10) |
| Pinned apps | `apps/pin.rs`, `lib.rs`, `Info.plist` | an app pinned to the taskbar or the Dock, opened from there again — this app started for it, wherever it now is ("Pinned apps") |
| Probe | `src-tauri/examples/app_window.rs` | one window, without the rest of the app (Windows) |

Windows and Mac have windows of this app's own. On a Linux computer a click
opens an Android app in scrcpy's per-app window (`--new-display
--start-app`), and the phone's Linux apps are not listed there: scrcpy can
only start Android packages. That window is known to the desktop as the app,
so a dock shows it by the app's name and icon and can pin it ("Pinned apps").

## Why not scrcpy's per-app windows

scrcpy 3 can already start an app on a display of its own. Measured on a
Galaxy A13 over USB, the same app at the same size, scrcpy's window and this
one taking turns:

| | scrcpy, a window per app | this |
| --- | --- | --- |
| A click on the Apps page to the first picture presented | 1.6–2.0 s, every time | 243–318 ms, about 260 typically |
| A pinned taskbar button, nothing running yet, to the first picture presented | the same 1.6–2.0 s | 1.72–1.77 s |
| Phone memory of the servers, one window (PSS) | 76.9 MB: its server and its clean-up process, a JVM each | 56.9 MB: one server, shared by every window |
| Phone CPU, everything the window involves, the Clock's stopwatch at 15 fps | 49.8–50.5 % of a core | 50.5–52.6 % |
| From the phone composing a frame to Windows presenting it | not measurable | 7–9 ms median, 95 % within 10–15 ms |
| …and to the refresh it goes out on the screen at | not measurable | 12–28 ms median, 95 % within 36–43 ms |
| Computer CPU time per frame, in runs at the same clock state | 3.7–3.8 M time-stamp cycles | 5.6–5.8 M |

What each row is:

- **A click** is timed from the moment the page's request reaches this app to
  the first frame the window presents. scrcpy's is its process starting to
  its first frame: a new server each time, so there is no warm case.
- **A pinned button** is timed from this app's process starting, in a debug
  build: about 0.4 s of it is the app itself starting, before it asks for the
  phone's server; then 0.95–1 s for the server, of which its JVM is 0.75–0.8
  s; then 330–350 ms for the window. scrcpy pays a JVM too, so the two are
  close; this one opens every later app in about 260 ms, and scrcpy's in 1.6–2.0 s again.
- **Memory** is PSS: pages shared between processes are split between them,
  not counted in each. Counted as RSS, the same runs come to 144 MB and 88.5
  MB, which overstates what scrcpy's two processes really add. The app, the
  codec and the compositor are the same on both sides and not counted.
- **Phone CPU** is every process the window involves: the servers, the media
  codec, SurfaceFlinger, system_server and the app. The codec (6.9–7.3 %),
  SurfaceFlinger (18.0–18.2 %) and the app (18.5–18.8 %) cost the same on both
  sides. This server costs 3.9–4.0 % to scrcpy's 3.5–3.6 %. system_server was
  at scrcpy's 2.5–2.7 % in three runs of seven and at 4.5–5.0 % in four, which
  scrcpy's never was; it is not the task watcher (slowed to a minute, the same
  happened). Not yet explained.
- **Latency** is in "Measuring it". This computer's screen is composed at
  55.5 Hz, every 18 ms, which is most of the difference between the two rows.
- **Computer CPU** is in "The picture" and "Measuring it": the time-stamp
  counter runs at a fixed rate, so these are CPU time, and both windows came
  out lower (1.6–1.9 M) in runs where the processor ran faster.

Each scrcpy window is a server of its own, so each pays for a JVM, a display
manager and a codec list. Here they are paid once.

## The phone's server

One per phone, started as soon as the Apps page is first looked at (so its JVM
is up before anyone clicks) — or, while the phone's notifications come to this
computer, as soon as the phone is attached, without the warm-up below, which
the Apps page then asks for (`warm`; doc/phone-notifications.md) — under
`app_process` as the shell, from the window
daemon's dex pushed to `/data/local/tmp/oadx-apps.dex` by rename, so a running
server keeps the copy it started with. It stops when this app says so —
"stop" on the `adb shell`'s stdin, or on its control connection, as the app
quits (after which none starts, not even one the notifications' keeper was
starting as it did), or as notifications are turned off if they were all it ran for — and
every app on its displays goes back to the phone, behind what it shows. What
the phone copies comes to this computer's clipboard once the Apps page or a
window has used the server, not while it runs for notifications alone. That shell closing is not the app gone: the cable may have been pulled
with Wi‑Fi still there. So the server is held by its shell, by a control
connection or by any window's, and stops only when none of them has held it for
30 s (`AppWindow.AWAY_MS`) — this app crashing, or the phone gone for good. The
shell ignores SIGHUP before it becomes the server (`trap '' HUP`): adbd hangs
up a dropped link's processes, which ended the server on the spot. Nothing
helps where adbd itself restarts on a Samsung phone — init then kills
everything in adbd's cgroup — and there the apps land on top of the phone's
screen until their windows open them again ("Cable or Wi‑Fi"). Stopping waits
for the windows to hand their apps back (up to three seconds), not a fixed 300
ms. Once its shell has gone, the server's log comes over the control
connection instead. On an A13 it is ready 740–780 ms after its process starts
(it logs how long), and the push and rename before that take about 110 ms.

**Warm-up.** Everything the first window would otherwise pay for is paid while
nobody waits: the GPU driver (`eglInitialize`), the codec list, one encoder
made and let go, the display and package managers. Cold, an A13's first
window spent 534 ms on its display and 404 ms on its encoder; warm, its first
frame comes 222–243 ms after the window asks.

But a server started *for* a window — a pinned taskbar button, nothing running
yet — is started with `--no-warm-up`: the window pays those costs anyway, and
the warm-up beside it only slowed it. On that path everything else waits for
the window too. The page's list of apps waits (up to four seconds) while a
window of that phone is opening, and the phone's sound starts once the
picture is there: its companion is a second JVM starting on the phone. Each
of the three had made the first frame later; together they took the pinned
start from 3.7 s to 1.72–1.77 s, the window's own part from 1.37 s to 330–350
ms.

**The list and the icons** come from the package manager in this process,
through `PhoneContext` — `cmd package` gives no labels and no icons. Two things
the framework assumes of an app process had to be made true: an `Application`
on the bare ActivityThread (an `AdaptiveIconDrawable` needs one), and the
shell's own ContentResolver (Samsung's live icons, the calendar's date, ask
for it and refuse "android"). The A13's 26 apps list in 13–18 ms warm; an icon
is 25–40 ms and comes once, then only when the app is updated.

## A window's display

A virtual display per window, owned by the shell:

- `PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH` always.
- `TRUSTED` from Android 11 where the shell may make one. Android 12's shell
  lacks `ADD_TRUSTED_DISPLAY`, so on an A13 the display is untrusted: it gets
  the keyboard only once its task is focused (`setFocusedTask` when the window
  is clicked), and Android 12 shows the phone's own soft keyboard on the
  phone's screen when a text field takes focus — the IME policy that hides it
  needs a trusted display. (The first Back after that closes the phone's
  keyboard, as Back does on a phone.)
- `OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED` from 13,
  `OWN_FOCUS | DEVICE_DISPLAY_GROUP` from 14: a window keeps its own focus, and
  two windows typed into in turn both keep theirs.
- Never `DESTROY_CONTENT_ON_REMOVAL`: when the window closes, Android moves
  the app's task to the phone's screen, behind what is there, as Recents keeps
  it. Then the task that was on top of the phone before is put back on top
  (`handBack`), so closing a window never changes what the phone shows. The
  task moves when WindowManager gets to the display's removal, after
  `release()` has returned, so the server waits for it to land before sending
  it back: sent back sooner, it landed on top in one close of twelve on the
  Android 16 emulator, and when the app was killed; in none of 27 since.

**The launch** is in this process, `IActivityTaskManager.startActivity` with
the display and fullscreen windowing in the ActivityOptions: 39–62 ms. An app
whose task is already on the phone (a window closed earlier) is moved onto the
new display, not started twice.

**When the app goes** — Back on its last screen, its own finish — the window
closes too. Nothing tells a shell process that; the server looks at the
display's tasks, every 300 ms for four seconds after the window opened or was
used, every two seconds otherwise. An empty display for 900 ms after it had
the app means it has gone. Each look is a binder call that unpacks every
task's configuration; at a steady 400 ms it was the largest part of an idle
window's cost. Only the person's own input counts as use: the clock's ping
every two seconds once did, which kept the watcher fast for good.

**Closing it.** Handed back, an app the person has finished with stays open
on the phone, and the only way to close it from here was Back, over and over,
to its last screen. So on Windows the person's ✕ — the title bar's, the
full-screen bar's, Alt+F4, the taskbar's Close window — closes the app on the
phone too, and asks first, in Windows' own message box ("Close Clock?"): OK,
and `close` says `"app": "close"`, and the server removes the tasks on the
window's display before it lets the display go, as swiping the app out of
Recents does. It is not a force-stop, which would hold back the app's
notifications until it is opened again. The rest is as after a swipe, and
Android's and the app's to decide: whether its process ends, a service
declared to stop with its task (`stopWithTask`) stopping, the app told
(`onTaskRemoved`) and free to stop what it was doing — its music, say — or
carry on. Its tasks on other displays are not touched. Cancel keeps both.

A window closed while its connection is gone has the phone told over the
first way back to it, tried as the keeper tries, every 0.7 s, until the phone
has been told or has stopped waiting (`RECONNECT_FOR`, as the server's
`AWAY_MS`). One whose phone stopped answering asks nothing: the phone has the
app back already. This app quitting asks nothing and hands every app back, as
a Mac's ✕ does. The question is a modal loop on the window's thread, as its
menus are, and nothing destroys the window under it: the phone saying the
app went, or this app quitting, takes the question down and has its way,
whatever the answer.

**Awake.** A display in its own power group (13+) has its own screen timeout,
and a person reading a page moves nothing, so the server tells each display it
is in use every four seconds (`userActivity`).

## The pace

On a cable the display draws straight into the encoder's input surface, as
scrcpy's does, and every frame the phone composes is encoded (`direct` in the
window's `open`). The link never falls behind there, and anything between the
two costs: a GL copy per frame put an idle window's server at 6.8 % of a phone
core, twice scrcpy's.

Over Wi-Fi it is the Android desktop's stream (built when the desktop had a
window of this app's own, commit 8f666e8, and LinuxStream's before it): the
display draws into a SurfaceTexture, and one GL draw puts the newest frame into
the encoder when

- the encoder holds fewer than two frames,
- the link holds fewer than its round trip in frames, plus the one on the wire
  — the computer acknowledges each frame as it lands (`0x10`), the median of
  the last 32 round trips sizes it, between 2 and 12 frames,
- and it is not faster than the window asked for (60).

A second in which a frame waited on the link for a fifth of it drops the
bitrate by 30 %; three seconds it kept up raise it by 25 %, to the ceiling the
window asked for. A slow link costs frames, never latency. A display size no
encoder takes as it is goes this way too, scaled by the draw.

**The drain** waits for the encoder's output a second at a time rather than
asking every 50 ms: each ask is a round trip through MediaCodec's own looper,
and those were most of an idle window's cost. Letting an encoder go signals
end-of-stream, which ends the wait at once. A direct stream is not
acknowledged at all (`paced: false` in the `video` message): an ack a frame is
a wake-up on both ends for nothing.

Together these took the server's own share of a 10 fps window on an A13 from
6.8 % of a core to 3.6 %, against scrcpy's server at 3.3–3.45 % for the same
app at the same size. Everything a window involves is in the table above.

## Cable or Wi‑Fi

A phone attached both ways — wireless debugging on, and the cable in — is one
phone here: the device list matches the two by `ro.serialno`. Its windows go
over the cable, which is faster and draws straight into the encoder (above),
and each window's title bar says which way it goes: "USB · Galaxy S25" or
"Wi‑Fi · …", in the subtitle on a Mac, after the app's name in the bar on
Windows — the way first, so that a narrow window cuts off the phone's name
rather than it. When the way changes, so does that, in the same window.

**A window is not its connection.** When the way a window's connection took
goes — the cable pulled, Wi‑Fi dropping — the window keeps its place, its size,
full screen, and its last picture, says "Reconnecting to Galaxy S25…" under its
title, and the connection is made again the fastest way there is now. Nothing
is closed and nothing opens: the window was never replaced by another.

- **The same window on the phone.** A window whose connection goes without
  closing it keeps its app on its display for 30 s (`AWAY_MS`), encoding nothing
  for nobody, and a new connection asks for it by its key (`attach`): the same
  display, the same app, its picture again from a key frame — the one on the
  display goes at once — paced or straight into the encoder as the new way
  wants. The server's own control connection is made again the same way, so
  the clipboard and the sound menu carry on.
- **Opened again in the same window.** Where the server has gone — a Samsung
  phone's adbd, restarting as the cable goes, takes it with it — a new one is
  started the way there is, and the app opened in the window again; its task
  moves back from the phone's screen, where Android put it when the old
  server's display went.
- **Opened on the phone meanwhile**, the app is its owner's: the phone's server
  says the window closed, and it goes, here too. Asked for again on the Apps
  page, it is opened in the window again.
- **Nowhere to go.** A window with no way to its phone says "Waiting for Galaxy
  S25 — plug it back in, or keep it on the same Wi‑Fi" and keeps looking (the
  keeper, every 0.7 s while a window waits). This app also makes the phone's
  Wi‑Fi link again itself, every 5 s, if it knows how (a phone set up for
  Wi‑Fi here, by `ro.serialno`, by its mDNS name where it pairs; or one made
  reachable beside its cable this run, below): after a Samsung phone's adbd
  restarted, nothing else does. After 30 s — 60 s while the
  phone is there but not usable yet, a cable settling — the window says "Your
  phone stopped answering. Open the app again to bring it back"; the phone's
  server has handed the app back by then. Opening it again brings it back in
  the same window.

**Wi‑Fi beside the cable.** A window can only move to a way to its phone that
exists, and a phone on the cable alone had none: pulling the cable left its
windows waiting for it, "stuck", unless the phone had been set up for Wi‑Fi
here first. So while a phone's windows go over a cable that has settled, the
keeper makes the phone reachable over Wi‑Fi as well (`wireless::ready_wifi`):
the phone's own Wireless debugging (Android 11 and later), switched on from the
shell (`settings put global adb_wifi_enabled 1`), and `adb connect` to its
port. The cable is pulled, and the window moves over a link that is already
up — if one could be made: what it takes is below, and a window with none
waits for the cable as before.

- **No pairing — for a computer trusted for good.** adbd checks a computer
  coming in over Wi‑Fi against the keys it keeps (the TLS handshake's
  certificate, against `adb_keys`), so a computer allowed on the cable with
  "Always allow from this computer" is let in at once. One allowed for the
  cable's while only is not: adb answers "failed to connect" either way, so
  the log names both causes. Seen on a Galaxy A13 (Android 12) and a Redmi 9A
  (Android 11), neither paired with this computer.
- **A cable in for 10 s.** No try is made over a cable plugged in while this
  app runs until it has settled (`CABLE_SETTLE`, for a Samsung phone's adbd
  restart about 5 s after the plug goes in, which moves Wireless debugging's
  port), then 0.7–1.3 s to make the link: a cable pulled sooner leaves its
  windows waiting for it. A phone already plugged in as the app starts counts
  as settled.
- **Not `adb tcpip`**, which the Desktop tab's "go wireless" uses: that
  restarts adbd, and on a Samsung phone everything adbd started goes with it —
  every window's server. Switching Wireless debugging on restarted nothing on
  the Galaxy A13 (its cable's transport id stayed the same); other makers'
  phones are not known to.
- **A Xiaomi phone's shell may not switch it on**: the Redmi 9A refuses the
  write (`SecurityException`: `WRITE_SECURE_SETTINGS`, which MIUI gives the
  shell only with "USB debugging (Security settings)" on). Its windows say "To
  carry on over Wi‑Fi when you unplug, turn on Wireless debugging on your
  phone" for a minute, and the link is made once its owner has.
- **The phone asks first**, the first time on a network: "Allow wireless
  debugging on this network?", the switch put back off until its owner answers.
  The windows on the cable say "Tap Allow on your phone, so this window carries
  on over Wi‑Fi when you unplug" for a minute, the phone is looked at every 2 s
  for the answer, and "Ready to carry on over Wi‑Fi when you unplug" once it
  was allowed. Ticking "Always allow on this network" makes it the last time.
  The switch is turned on once a run: turned off on the phone, or not allowed,
  it stays off. Allowed only after the cable went, the phone is found by its
  mDNS name while its windows wait for it.
- **Its port** is read off the phone's socket table: a listener of the shell's
  (adbd's) uid bound to every address, in `/proc/net/tcp6` — the touchpad's,
  also the shell's, is on loopback. Else from mDNS, where the phone announces
  `adb-<ro.serialno>-<suffix>`. Neither alone is enough: a Galaxy A13 does not
  let the shell read `service.adb.tls.port`, and a Redmi 9A does not announce
  itself.
- **For this run only.** Never remembered with the phones set up for Wi‑Fi: a
  remembered phone is taken over Wi‑Fi at the next start, its cable in.
  Wireless debugging this app switched on goes off again as it exits, and the
  links it made are let go; one its owner had on stays on. A link to a port
  the phone has since left (adbd restarted) is let go as the next is made, or
  as soon as the phone is up over Wi‑Fi another way: left offline in adb's
  list, the Desktop page would read it as a phone that left the network.
- **The exit** (`wireless::let_wifi_go`) first stops any new try and waits up
  to 4 s for those under way, which check every half second and record
  nothing once it has begun — a link one makes after, it lets go itself; a
  switch it is about to turn on is recorded before it is turned. Then it
  switches off over a device list made then, not the last poll's (the cable
  may have been pulled a moment before), trying the cable first, then each
  way over Wi‑Fi. An `adb connect` under way is not cut short: one that
  outlasts the 4 s may leave a link adb lists offline after the app has gone.
- After a Samsung phone's adbd restarts as the cable goes, Wireless debugging
  listens on a new port; a waiting window has the link made again by the
  phone's mDNS name.

Android 10 and older have no Wireless debugging: their windows wait for the
cable, unless the phone was set up for Wi‑Fi on the Desktop tab.

Measured on Windows 11 with the Redmi 9A, Wireless debugging already on and
the Clock in a window: the link over Wi‑Fi up 0.7–0.8 s after the window first
showed the app, on a port read off the socket table; the cable dropped by this
computer (`adb reconnect`), the same window on the phone over Wi‑Fi, its
picture back 453–667 ms after the connection went; back onto the cable 10.3 s
after it returned. adbd restarted (`adb usb`): the Redmi's server went with it
too, adb's own mDNS connection found the new port, and the app was opened
again in the same window over Wi‑Fi 7.5 s after — its task moved back onto
the window's display, which keeps what Android keeps across a display change,
not everything — the old link let go (`wireless::drop_stale`), and the window
back on the cable once it settled. On the Galaxy A13, the phone asked 0.4–1.3
s after the window showed Calendar, and the window said so for its minute.

A window on Wi‑Fi moves onto the cable once the cable has been in for 10 s. A
Samsung phone restarts adbd about 5 s after it is plugged in (its MTP switch),
ending everything adb started, so no server is started over a cable that has
not settled; one already running is only reached that way, at once. A move is
a thread of its own, so one slow phone holds up no other window.

Over mobile data there is no way for an Android app's window: a window is a
display that only the shell may make, and the shell comes with debugging —
the cable, or Wireless debugging, which the phone keeps only on Wi‑Fi. (The
phone's Linux desktop goes on over mobile data, through the tunnel; its
picture is the launcher's, not the shell's.)

Measured on the Android 16 emulator, `emulator-5554` as the cable and `adb
connect 127.0.0.1:5555` as Wi‑Fi, Settings in a window:

- the cable pulled (`adb -s emulator-5554 reconnect`): the same window over
  Wi‑Fi, its picture back 114–197 ms after the cable went (629 ms on a first
  run); back onto the cable 10.2 s after it returned, the same window;
- the server killed (`kill -9`, a Samsung adbd restart's effect) with the cable
  settled: a new server in 0.94 s, the app opened in the same window again,
  its picture back 1.56 s after;
- the cable pulled, then 3 s later the server killed and Wi‑Fi gone for 3 s,
  the cable back unsettled: no server started over it; the app opened again
  over Wi‑Fi when that came back (picture 4.65 s after the loss, most of it
  the 3 s without Wi‑Fi), onto the cable once it had settled;
- with no Wi‑Fi at all, the cable dropped and back at once: the same server,
  orphaned, reached over the unsettled cable — picture back 253 ms after.

The phone's half, off the app, through a test client speaking the wire
(`test/apps-reconnect-test.mjs`, 17 checks): a window waits and takes a new connection, a second one takes it from
the first, which is shut; a window closed after it came back hands its app
back; an app opened on the phone while away makes the window "closed" to a
computer that comes back, an unknown key "unknown"; with its shell gone and no
connection the server stops after 30 s and hands its app back, and "stop" ends
it at once.

## The wire

One connection per window, one more for what is the phone's as a whole (the
list, the icons, the clipboard, its sound, the clock), and one per dropped
file, or per file this computer takes from the phone. Every message is a type byte, a big-endian u32 length and that many
bytes:

| Type | Way | Body |
| --- | --- | --- |
| `0x01` | both | JSON, UTF-8 |
| `0x02` | phone → computer | flags u8 (1 key frame, 2 codec config), pts µs u64, H.264 Annex B |
| `0x03` | phone → computer | package length u8, package, PNG — an icon |
| `0x10` | computer → phone | a frame arrived (paced streams only) |
| `0x20` | computer → phone | buttons u8, x u16, y u16, meta u32 — the mouse, in display pixels |
| `0x21` | computer → phone | x u16, y u16, h f32, v f32, meta u32 — the wheel, in notches |
| `0x22` | computer → phone | down u8, repeat u8, code u16, meta u32 — a key, Android's code and meta |
| `0x23` | computer → phone | UTF-8 — characters typed |

A window's JSON: `open` (package or Linux window, size, dpi, fps, `direct`,
what closing does, the window's `key`, and the notification it opens at,
`notif`), `resize`, `focus`/`blur`/`leave`,
`clip`, `keyframe`, `bitrate`, `close`, `ping`, `dragOut` one way; `video` (the encoder,
its size, the part of it that is the display, `paced`), `display` (the app's
name), `state` (`closed`, `failed`), `stats` once a second, `pong`, `drag`
(`on`, and the types a drag carries) and `dragged` (what a drag handed over
carries, by number, or `none`) the other ("Dragging out").
A window's connection made again starts with `attach` (its key, size, dpi,
`direct`) instead of `open`, answered by `attached` — then `display` and
`video` as for an open — or by `state`: `closed` (it ended while the computer
was away), `unknown` (another server: open it again). The control
connection's: `apps`, `icons`, `linuxApps`, `linuxIcons`, `linuxRun` (answered
by `linuxRan`), `clip` both ways; `audio` — the computer asking, and the phone
saying where its sound can go, after its hello, as outputs come and go, and
after a pin; `audioRoute` (answered by `audioRouted`) and `audioMode`, the
computer's; `stop` and `warm`, the computer's, and `log`, the phone's once its
shell has gone; `clipFiles`, the phone's, a picture or file it copied, by
number ("The clipboard"); the phone's notifications — `notifs`, `notif`, `notifOpen`
and the rest, in doc/phone-notifications.md, "The wire"; and the phone as these
windows' touchpad — `padOn`, `padOff` and `pad`, in doc/phone-touchpad.md, §10.

A dropped file's connection is the one exception to the framing: its first
message is `put` (the name, the size, the folder it goes in, whether it is an
APK to install), and exactly that many bytes follow it, raw. The phone answers
`got` as they land and `put` once, with how it went. A file dropped on the app
in a window says `stage` (its drop's batch) and `i` too, and is answered
`staged` instead of with a path; once the last is there, one more connection
asks for the drop: `dropIn` (the window's key, the batch, the point, and the
staged files by `i` and name), answered once by `dropIn` with `taken` (and
`files`, each one the app has not opened, as `put` answers for one in
Download), or `taken` false, `why` (`refused`, `cannot`) and each file so
("Dropping files"). A file this computer
takes from the phone is the same the other way: `get` (the offer's number and
the item's), the phone's `get` with its name, type and size (-1 unknown), and
its bytes, raw, until the connection closes.

Each window's connection is its own adb stream, side by side on the one USB or
Wi-Fi link, so one app's key frame never waits behind another's.

## Who gets in

The server listens on an abstract socket named afresh for every run, and asks
the kernel who each connection is (SO_PEERCRED): the shell, which adbd is, or
root. Not the launcher, whose uid is also the Linux guest's: this socket takes
a mouse and a keyboard into any app on the phone.

The computer reaches it through the adb server's own protocol —
`host:transport:<serial>`, then `localabstract:<name>` — on a connection this
process opens, so nothing listens on the computer. An `adb forward` would be a
loopback port any program here, or any page in any browser, could reach and
type into the phone with.

## The window

Whatever draws it, a window's link to the phone is `link.rs`: its own socket,
and three threads — the reader, which hands each piece of the picture to the
window as it lands (and over Wi-Fi acknowledges it); the writer (a slow link
can make a write wait; the window never does); the clock, a ping a few times a
minute. The link asks the phone for the display the window wants, maps a
point in the window to a pixel on that display, and hands the clipboard over.

**On Windows** it is a real Win32 window, not a webview, with two threads of
its own: the window's messages, and the picture (never stopped by Windows'
modal loops while the window is dragged or resized, which a picture drawn
from the message loop would be).

The title bar is ours — Back, More ("Full screen"), the app's icon, "App ·
phone", where the sound plays, minimise, maximise, close — and Windows keeps
the rest of what it gives a window:
`WM_NCCALCSIZE` keeps the side and bottom resize borders, the maximise button
answers `HTMAXBUTTON` so Windows 11's Snap layouts open on it, and the shadow,
rounded corners, system menu and Alt+Tab are the system's. Each app's window
has an AppUserModelID of its own and a relaunch command, `--phone-app <phone>
<package>`: WhatsApp and Gmail are two taskbar buttons, either can be pinned,
and a pinned one starts this app with just that window — no desktop ("Pinned
apps").

An app's first window opens at a current phone's shape, 412 × 915 dp, fitted
to 90 % of the screen's height, at that screen's scale; later ones as the last
one closed ("Phone or tablet"). The phone's display gets the window's pixels
and 160 dpi times the scale, so text is the size it would be on the phone and
one pixel is one pixel. Resizing the window resizes the display (a new encoder
at the new size; the app lays itself out again).

Opening one waits 10 s for the window to be made, and a pinned app's start
asks again every 1.5 s for 45 s. A window is made only if it is still wanted:
whichever comes first, its thread (the main thread, on a Mac) starting on it or
the open giving up, wins — one made after would be no one's, and the open
asked for again beside it (seen on a Mac swapping hard, 2026-10-29: four windows
on "Opening…" behind the one that opened).

## Pinned apps

A person pins an app to the taskbar or the Dock so that a click there opens
it, whatever else is running: after a restart, with this app closed. That
click has to open the app's window from its phone, with this app started for
it if need be, and nothing else of this app — the main window has nothing to
add to it.

**What a pin names.** The phone by its `ro.serialno` and the app by its
package: `--phone-app <phone> <package>`. Not the adb serial the window
happened to open over, as it was until 0.3.30: a Wi‑Fi one is an address
whose port Android rolls every time wireless debugging is switched on, so a
pin made from a window over Wi‑Fi opened nothing once the phone came back.
Pins made so are read back — an mDNS name carries the serial, and a Wi‑Fi
address is looked up among the phones this computer remembers. One whose
address no remembered phone was at opens nothing, as another phone may have
that address now: its click says to open the app once from the Apps page,
which points the pin at the phone it opened from. And not, for
long, where this app is: the portable build is a folder that is moved, or
replaced by the next version's beside it, an AppImage is mounted somewhere new
every run, and a Mac app runs from wherever it was unpacked.

Each system pins in its own way, and this app keeps the pins it can reach
pointing at itself:

- **Windows** pins a window. A pin is a shortcut of the window's relaunch
  command, with its AppUserModelID in it, under `%APPDATA%\Microsoft\Internet
  Explorer\Quick Launch\User Pinned`: in `TaskBar`, or — as a window pinned
  from its taskbar button is on Windows 11 — in `ImplicitAppShortcuts\<hash>\`,
  where Windows keeps a shortcut of each app's windows, made from the first of
  them it saw and never changed by a later window, even one of another copy of
  this app. Every run looks in both (`heal`) and points each shortcut with a
  phone app's AppUserModelID at its own executable, and the folder it starts
  in; gives one whose icon has gone the icon this computer has; and reads an
  older one's adb serial back to its phone. Squirrel and Chrome keep their
  pins working through updates the same way. A portable copy moved or
  replaced, its pins work again the first time the new copy runs. A window
  opened by a pin, at start, is opened before its phone has named the app;
  what a pin of it would be called follows the name when it comes.
- **Linux** pins a desktop entry, and a dock matches a window to one by its
  app id — its X11 class, its Wayland app id. scrcpy's window for a phone app
  is started with `SDL_APP_ID=com.ccrstech.openandroiddex.app.<package>` (SDL
  3's one name for both), and the entry of that name is written as the window
  opens, in `~/.local/share/applications`: the app's name and icon — scrcpy's
  window icon too, through `SCRCPY_ICON_DIR` — and how to start it. That is the
  AppImage's own file (`$APPIMAGE`), not the mount it runs from, which is gone
  when it exits; every run writes the entries again for wherever it is. They
  stay out of the app menus (`NoDisplay`), and a dock pins them all the same.
  Linux gives one app no way to bring another's window forward
  (`embed::activate`), so an app asked for again while its window is open is
  not opened twice: the person is told where it is.
- **A Mac** pins applications, never windows, and these windows are this
  app's, under its Dock icon. More's "Keep in Dock" makes the app an
  application of its own, in `~/Applications/Open Android DeX Apps/<package>/`,
  with the app's icon from the phone at a Dock's size (256 px, an `.icns`), and
  adds it to the Dock as `dockutil` does: a tile in the Dock's preferences, and
  the Dock started again to read it. Its executable is a script, `open -b
  com.ccrstech.openandroiddex 'openandroiddex://app/<phone>/<package>' --args
  --phone-app <phone> <package>`. LaunchServices finds this app wherever it
  is; the URL reaches it if it is running, and the arguments start it for the
  app if it is not — started so, it hears both, and opens the app once. The
  scheme is declared in `Info.plist`, and opens only the apps kept in the
  Dock, each from the phone its own script names, which is read back from it:
  a web page could name any app, and any phone, in a URL.

**A pin follows its app's phone.** It opens the app from the phone the app
was last opened from here: the shortcut's arguments, the entry's command and
the Dock app's script are written again when a window opens from another. A
new phone takes each pin over the first time that app is opened from it, and
a pin never opens its app on a phone it was not opened from, which may be
someone else's. Until its phone names the app, a window opened by a pin is
called what the pin calls it — the shortcut's name, the entry's, the Dock
app's.

**The click.** With this app running, the second start hands its command line
to the running one (the single-instance plugin; on a Mac, the URL) and goes.
Started by the pin, this app makes its main window hidden — it is made in
`setup` rather than from the config (`create: false`), so that an ordinary
start makes it shown, at once, as it always did — and opens the app as soon as
its phone is there. adb's server may be starting after a restart, and a phone
this computer reaches over Wi‑Fi is connected again — by its remembered
address, or where it announces itself — every 10 s, as nothing else would.
The app's window is then all there is to see. With no window after 4 s the
main window comes up, on the Apps page, which names the app that is waiting
("Connect your phone to open WhatsApp") and puts a spinner on its icon; after
45 s the page says why the app could not open — the phone asking to allow USB
debugging, or not there. One app asked for twice while it opens — a second
click, a Mac's URL beside the command line it started this app with, its
tile on the Apps page — opens once.

A pinned button clicked while its app's window is open only brings the window
forward: this app never hears of it. So a window waiting for its phone, or
given up on it ("Click here to bring the app back"), takes being brought
forward, or clicked, as its app being opened again (Windows).

**The run ends with its window.** A run a pin started is its app's: when the
last of its windows closes, the run quits, as closing the app would — on
Windows and Linux, where nothing else of it would be left to see. A window
that could not show its app (scrcpy's, on Linux; this app's own say so
themselves) brings the main window up instead. Starting this app by hand
meanwhile shows the main window — even while its webview is still starting,
which is when the request had been lost before the window was made in `setup`
— and from then on the run is an ordinary one. On a Mac the app stays in the
Dock, and its icon there shows the main window.

Not done, and why:

- **A launcher of our own that never moves** — a URL protocol registered for
  the user, the pin starting `explorer.exe` with the URL, or a small
  executable copied somewhere fixed. Either works for a copy that has moved
  only once the new copy has run, as re-pointing does; either puts a second
  process in front of every click; and a protocol is one any web page can ask
  for.
- **Pinning for the person.** Windows gives an unpackaged app no way to pin
  itself, by design. A dock's own "Pin to Dash" or "Pin to Task Manager" is
  the person's to use.
- **A Dock icon per window on a Mac.** The window would have to be another
  process's, drawn there over a remote layer, as Chrome's app shims are.
- **Development builds** change no pin — one pointed at a debug build would
  outlive it — unless `OADX_PINS=1`; they log what they would have changed.
  On Linux they write no desktop entry, and their windows match an installed
  copy's all the same.

## Phone or tablet

An Android app lays itself out for the room it has. Most apps that have a
tablet layout take it from 600 dp on the display's shorter side, Android's
`sw600dp`. It is not every app's line: some go by their width, at sizes of
their own (Material's window size classes change at 600 and at 840 dp wide),
and some have only the one layout. It is also the size from which Android 16
sets aside a fixed orientation in apps built for it. A window's display is the
window's own size at one dp to the point ("The window"), so the window is the
device the app thinks it is on: a phone-shaped window is a phone's size, and
a window 600 × 600 points or more is a tablet's, which an app with a tablet
layout lays itself out for again as the window is resized across. That was so
before anything below. Everything here is about the size an app is given; what
the app does with it is the app's, and nothing here looks.

**Nothing on the phone changes for it.** The question that started this was
whether the phone's density should be lowered while app windows are open, so
that apps take their tablet layouts, and put back when the last window closes
or the desktop starts. It need not be, and must not be. The phone's own screen
(display 0) is never given a density by anything here, and the desktop's
display has its own (doc/sharp-desktop.md, "Display size"). Each window's
display is given its density as it is made and with each resize
(`VirtualDisplay.resize(w, h, dpi)`; never `wm density`), so there is nothing
to put back: the display goes with its window, and its density with it.

**A wide window leans to a tablet's size.** Under 600 points tall, a window
wider than it is tall was a phone on its side to its app: a portrait-only app
letterboxed in the middle, the rest stretched sideways. On a laptop's short
screen even a maximised window is: 1080p at 175 % leaves 537 points under
Windows 11's 48-point taskbar and the 32-point bar. So an Android app's window
wider than it is tall and under 600 points tall is given the density that
makes it 600 dp tall (`link::layout_density`), where that is no lower than the
desktop's Small display size, 0.875 of one dp to the point. The check is made
on the whole dpi the display would be given, rounded down, so the window's
text is never more than an eighth smaller than in a window a phone's shape.
That makes it from about 525 points tall, a little more at some scales: at a
custom 104 dpi on Windows, 568 pixels tall would be 151 dpi against 173, past
the eighth, and is left at 173. A window taller than it is wide is left a
phone's size until it is 600 points wide: that is a phone's shape. A Linux
window's display is never leant.

| Maximised, Windows 11 at 1920 × 1080 | The picture, points | The display | The size, to the app |
| --- | --- | --- | --- |
| 100 % | 1920 × 1000 | 1920 × 1000 dp, 160 dpi | a tablet's |
| 125 % | 1536 × 784 | 1536 × 784 dp, 200 dpi | a tablet's |
| 150 % | 1280 × 640 | 1280 × 640 dp, 240 dpi | a tablet's |
| 175 % | 1097 × 537 | 1229 × 602 dp, 250 dpi rather than 280 | a tablet's, leant |
| 200 % | 960 × 460 | 960 × 460 dp, 320 dpi | a phone's; full screen, 960 × 540 points, is a tablet's, leant |

**More picks one.** More (☰) has Phone size and Tablet size, a radio pair on
the one the window is now: a tablet's from 600 dp on its shorter side, a
phone's under. It says what size the app is given, not which layout the app
shows — hence sizes, not layouts: an app with no tablet layout is not promised
one. Picking the other makes the window that shape, centred where it was and
kept on its screen. Full screen, the window leaves first, and is then made
that shape whatever size it comes back as (on a Mac, once macOS says it is out
of full screen; a full screen that fails to end drops the request):

- Phone size: a current phone's shape, 412 × 915 points, fitted to the screen
  as a first window is. A maximised window is restored first.
- Tablet size: a current tablet's, on its side, 1280 × 800 points (a Galaxy
  Tab's or a Pixel Tablet's at its own density), fitted the same way. Where
  that fitted shape is a phone's size (1080p at 175 %: 768 × 480 points), the
  window is maximised instead. Where even that is a phone's, the window is
  left as it is and a note says that the screen has no room for a tablet's
  size, or has it only in full screen.

A note under the bar says what was done, and that the app's next window opens
so: "Tablet size. Gmail opens like this next time" — on the same screen, and
unless the window is resized again before it closes. Nothing else changes for
the app: it is resized as a window being dragged bigger is.

**Each app's window opens as its last closed.** Closing a window keeps its
size by app, in `app-windows.json` beside the app's other settings: its
picture in points as it was before it was maximised, minimised or made full
screen, and whether it was maximised. A window snapped to half the screen is
kept the size it was snapped to, and comes back that size, not snapped. The
app's next window opens at that size where it fits on the screen it opens on,
and else as large as fits there — not the 90 % a first window is fitted to,
which would shrink a window as tall as the screen each time it opened — kept
on the screen however it is cascaded, and maximised if it was. One that was a
tablet's size and would be a phone's on this screen (a tablet stood up, on a
laptop's short screen) is maximised instead, where that makes it a tablet's;
where it does not (1080p at 200 %), it opens as large as fits, a phone's size.
A file that cannot be read is no sizes at all, and is written over by the next
window to close; it is written beside itself and moved over, so a write cut
short leaves the last one whole. A Linux window keeps nothing: its size is its
app's.

## On a Mac

The same link and the same phone; an AppKit window (`mac/mod.rs`) whose
picture is an `AVSampleBufferDisplayLayer` (`mac/video.rs`). That layer is
macOS's own way to show a stream: it decodes on the Mac's media engine
(VideoToolbox) and the window server composites the decoded surfaces as they
are, so this process neither decodes, copies nor draws a frame. Every frame is
marked to be shown the moment it is decoded (`DisplayImmediately`), not at its
timestamp. The layer wants H.264 its own way (`h264.rs`): the parameter sets
as a format description, and each frame's NAL units after their lengths
instead of start codes. A new stream (a resize) is a new format description
and waits for its key frame; a layer whose decoder fails is flushed and the
phone asked for a key frame.

The link's reader feeds the layer from its own thread — enqueuing is what the
layer means to be done from a media queue — so the main thread, busy while a
window is dragged, is never in a frame's way. Everything else AppKit is done
on the main thread, through the main dispatch queue, which runs during a
drag and a live resize too. The phone pads its picture to its encoder's
alignment at the right and the bottom; the layer sits in a clip the size of
the app's part, which cuts the padding off, letterboxed for the moment a
resize takes to reach the phone, as on Windows.

The title bar is macOS's own, with Back and More beside the window's buttons
(a title bar accessory): an Android app needs a Back, and a Mac keyboard has
no key for it. ⌘[ is Back as well, as in Safari and Finder, and so is a mouse's side
button. The app's name is the title and the phone's the subtitle; minimised,
the window is the app's own icon in the Dock. A Mac pins applications to the
Dock, not windows, so there is no relaunch command: the windows are this
app's, under its Dock icon and in its Window menu. More's "Keep in Dock" gives
the app an application of its own there instead ("Pinned apps").

The keyboard (`keys.rs`, `mac`): text goes through AppKit's own input
(`NSTextInputClient`), so dead keys and input methods type what they type,
and what they finish goes to the phone as text. ⌘ is Android's Ctrl, as
Control is, so ⌘C copies and ⌘V pastes (the Mac's clipboard reaching the phone
first); a shortcut's letter is the one the layout types, by
`charactersIgnoringModifiers`, and by its place on the keyboard where the
layout's letter has no key code. The Mac's own ways through text become
Android's: ⌥← a word, ⌘← the line's start, ⌘↑ the text's, ⌥⌫ a word gone.
⌘Q, ⌘W, ⌘M, ⌘H, ⌘\`, ⌘, and ⌃⌘F stay the Mac's. The trackpad scrolls in points,
ten to a notch, momentum included; Control+click is a right click.

This was written on a Windows computer and checked there by compiling it
against the macOS bindings (`cargo check --target aarch64-apple-darwin`, with
a stand-in C compiler for the build scripts, since `check` never links) and by
the unit tests of the parts that are pure functions (the key mapping, the
H.264 framing). It has not run on a Mac yet ("Verified, and not yet").

## Full screen

More (☰), beside Back in the title bar, is the window's own menu: Copy and
Paste ("The clipboard"), Full screen, and the phone as the touchpad. Full screen, the app's picture is the whole
screen and there is no title bar: the phone's display takes the screen's size,
as it takes any window's, and the app lays itself out for it. When the pointer
touches the top of the screen a small bar comes down in the middle, as Remote
Desktop's connection bar does — Back and More, the app's name, where the sound
plays, leaving full screen, closing (and minimising, on Windows). It goes back up 0.6 s after the
pointer has left it, and comes down for two seconds as the window goes full
screen, so the person sees that it is there. While it is up, a small tab with
an arrow pointing down stays at the top, in the middle, where it comes down;
the pointer on the tab brings it down too. Esc leaves full screen, and while
it does, neither it nor its repeats reach the app — Esc is Back to most
Android apps, which is not what someone leaving full screen means.

**On Windows** the window loses its caption and frame and covers its screen —
the monitor's rectangle, not the work area: the taskbar steps aside for a
window that covers a screen — with DWM's rounded corners and its animations
off. A maximised window is restored first and maximised again after, so
Windows still knows it as the window it was. The bar is drawn the way the
title bar is, once per change into a texture of its own that is copied into
the back buffer after the picture, so it cannot come apart from the picture
either; it slides by copying more of its rows each tick. F11 goes in and out,
as in Windows' own apps, and More's menu says so.

**On a Mac** it is macOS's own full screen, in a space of its own, whichever
way it is asked for: More, the green button, ⌃⌘F. The window asks for the menu
bar to be hidden rather than to come down when the pointer reaches the top
(`window:willUseFullScreenPresentationOptions:`), and the title bar macOS
would bring down with it stays away too; the window's own bar, a visual effect
view over the picture whose rounded top is above the screen, comes down
instead.

A full-screen window whose way to the phone changes stays full screen: it is
the same window ("Cable or Wi‑Fi").

## Input

The pointer is the computer's own: the phone is told where it is and what is
held, so it moves at the screen's rate whatever the video does. The mouse goes
as position and buttons, and the server makes the sequence a real mouse makes
on Android: hover, DOWN and BUTTON_PRESS for the first button, MOVE while
held, BUTTON_RELEASE, UP with the last, each with the keyboard's modifiers and
the window's display id, so nothing lands on the phone's own screen. The side
buttons are Back and Forward. The wheel goes in notches.

A character the computer's layout typed goes as that character, so é on a
French keyboard is é on the phone whatever layout the phone thinks it has: as
the keys that type it on the virtual keyboard, an accented letter as its
accent's dead key and then the letter (grave, acute, circumflex, tilde,
diaeresis: é is the acute's key, then E). What no key types — another script,
an emoji, € — is pasted, a run at a time: the phone's clipboard holds it for the
paste and is given back what it held a second later, neither change goes up
here, and the person's own paste gives it back at once. Android takes a string
as one key event (ACTION_MULTIPLE) from an input method only; from the shell it
refuses it ("invalid action code 2"), which is how the first version lost every
such character. A key that does something — Enter, the arrows, Backspace — and
any key held with Ctrl or the Windows key goes as Android's key code with its
modifiers, a letter by the letter the layout types (Ctrl+A on AZERTY is select
all). The computer's key repeat is the phone's.

Ctrl, Alt and Shift reach the app as keys of their own as well, around the key
they came with, as a keyboard's do: Ctrl+A is Ctrl down, A down, A up, Ctrl
up (`AppInput.key`). Android reads what is held from each event's meta, so a
text field's select-all worked without them, but not everything does. A text
field's Shift+arrow reads Shift from Shift's own key, and selected nothing; an
app that keeps its own count of the keys held — a remote desktop such as
TeamViewer, passing keys on to a computer — saw an A alone. The phone makes
these keys from the meta, and the computer sends no modifier keys, so none is
ever pressed alone: Alt let go on its own would open the menus at the far end
of such an app, and Shift alone switch an input method's language. They go up
before a key that no longer has them, and with the last key held with them;
typed text brings its own Shift, the virtual keyboard's. The Windows key stays
in the meta only: the phone acts on it alone (the app drawer, the assistant),
and the computer keeps almost every Windows-key shortcut for itself.

## The clipboard

The clipboard follows the person, both ways, as things are copied: what is
copied here goes to every phone with a window open the moment it is copied
(`apps/clipboard.rs`: `WM_CLIPBOARDUPDATE` on Windows; on a Mac, which says
nothing, the pasteboard's change count, asked twice a second), and again when
a window comes to the front and before a paste in it; what the phone copies
comes back at once. Neither side is handed its own text back, and an empty
clipboard here never empties the phone's. The shell holds
`READ_CLIPBOARD_IN_BACKGROUND`, the permission that exists for exactly this.

"Its own text" is judged by time as well as by text. What the phone sets
itself — the computer's text, typing's loan and its giving back — it takes as
its own for two seconds after setting it: every notification of it, since One
UI tells a listener of one set twice (on a Galaxy A13 the second went back up
as a copy until the two seconds covered both). After that the same text is a
copy like any other. And when
something is copied here that does not go (below), each phone forgets the
text last handed over: otherwise "A" copied here, then a password, then "A"
copied again in a phone app would leave the password on this computer, both
sides sure "A" was there already.

As it is copied, and not only at a paste, because both sides keep a history,
and each should have what was copied on the other: copy three things here and
the phone keyboard's clipboard has all three; copy on the phone and it is in
Windows' (Win+V). Handed over only when a window came forward or before a
paste, as it once was, the phone got the last thing copied and never the ones
before it.

What a password manager marks as not for histories or other devices stays
here — on Windows `ExcludeClipboardContentFromMonitorProcessing`, or
`CanIncludeInClipboardHistory` or `CanUploadToCloudClipboard` set to 0, the
marks Windows' own history and sync obey; on a Mac
`org.nspasteboard.ConcealedType` or `TransientType` — unless it is pasted into
a window, which is a person handing it over. Text over 256 KB waits for a
paste too: the phone takes its clipboard in one binder call.

From this computer, text only. An image copied here would have to become a
file in the phone's shared storage — its gallery — to be a clip there; files
go by dropping them instead. A Linux app's window shares the phone's clipboard through the
launcher, which hands it to the X selection while one of its windows has the
focus (`doc/linux-windows.md`).

From the phone, pictures and files too. A clip of content URIs — a gallery's
"Copy to clipboard", the share sheet's Copy, the Files app's own copy — is
offered to this computer (`clipFiles`) as a drag's items are ("Dragging out"):
the clipboard grants a clip's URIs to whichever package reads it, and the
server reads it as the shell's, so the shell may read them. They are taken here at once,
up to 512 MB in all — a film copied there is not a clip; dragging it out takes
it — and put on this computer's clipboard as files: Finder's and Explorer's
paste. On a Mac a single picture goes as the picture as well, in the same
pasteboard item, which a document, a message or a mail takes instead; on
Windows it is the shell's own data object for the files, what Explorer's copy
puts there, flushed, so no thread of this app's has to answer for it after.
The Mac's change of its clipboard is marked as this app's own, so it is not
handed back. Nothing goes the other way but text.

**More's Copy and Paste** are the app's own Ctrl+C and Ctrl+V, as ⌘C and ⌘V
already send them — for whoever does not know the keys, and to say what
happened: "Copied “…”", a picture's progress and then "Copied photo.jpg —
paste it anywhere on this Mac", or, when the phone copied nothing within 1.5 s,
"Nothing was copied. Select something first, or use the app's Share › Copy."
Many galleries copy nothing with the key; their Share › Copy, or their own
"Copy to clipboard", does.

## The picture

```
the app's display ──▶ MediaCodec (H.264, the phone's hardware) ──▶ adbd ──▶ adb server ──▶ this window's socket
──▶ Media Foundation's H.264 decoder (DXVA, on the window's D3D11 device) ──▶ one pixel-shader draw ──▶ flip-model swap chain
```

That is Windows; a Mac's is the display layer above.

**The decoder** is Media Foundation's own, given the window's device, so it
decodes in hardware into a texture that never leaves the GPU, in low-latency
mode (a frame in, that frame out; the phone sends no B-frames). Its surfaces
are asked for with `D3D11_BIND_SHADER_RESOURCE` as well as the decoder's own
binding, so a shader can sample them. Software decoding is the fallback.

**One draw** turns NV12 into the window's colours: the two planes sampled as
textures, BT.601 or BT.709, limited or full range as the stream says,
scaled only for the moment a resize takes to reach the phone and come back.
The D3D11 video processor does the same in one call, and stays for a decoder
whose surfaces cannot be sampled — but on an Intel UHD that call cost the CPU
1.6–2.8 M cycles a frame, and a draw costs a few thousand.

**The swap chain** is flip-discard, three buffers, at most one frame queued
(the latency waitable object is waited on before drawing). Three, so the next
back buffer is free even while the compositor holds the last two; after every
Present the back buffer's view is discarded (`DiscardView`), since every pixel
is drawn again. Binding the back buffer cost 0.9–2 M cycles a frame without
those two, 0.004 M with them.

**The title bar** is part of the same back buffer, put there after the
picture, so the window and its bar are one surface and can never separate.
Direct2D draws it only when it changes — a hover, the window's focus, its
width, the scale — into a texture of its own, and each frame copies that on
the GPU (0.01 M cycles). Its text formats are made once per scale; they were
once made every frame at 100 %.

**Where a frame's CPU goes now** (Intel UHD, Windows 11, an A13's stopwatch at
15 fps, 404 × 896, the probe on its own): the decode call 0.11 M cycles on the
picture's thread, the draw 0.02 M, Present 0.4–1 M, and Microsoft's decoder's
own worker threads about half of the 5.2–5.9 M the process spends in all.
scrcpy, which decodes in software and uploads, spends 3.7–3.8 M at that size
and 1.75 M at 1600 × 960, where so little changes per frame that software
decoding is cheap. The decoder's worker-thread count (one, two, the default)
changed nothing measurable, and waiting on its output with a GPU fence made it
worse. Whether another decoder front end would be cheaper — FFmpeg's D3D11VA,
through the avcodec that already ships with scrcpy — is untried.

**The screen** is the last step and the longest: Windows composes a window's
frame at its next refresh and sends it out at the one after, so a frame
presented 7–9 ms after the phone made it is on the screen 12–28 ms after
(median) on this computer's 55.5 Hz screen. Skipping the compositor needs
presenting to a hardware overlay plane (Windows' composition swap chains);
not tried.

## Measuring it

**Start** is timed by the app: each window logs "on screen N ms after the
click", from the moment the page's request arrived (or "after this run
started", for a pinned button: from the process starting) to its first frame
presented. The phone's server logs what its own part took: "ready N ms after
the process started", and each window's display, launch, encoder and first
frame.

**Latency** is two numbers, from the moment the phone's compositor made a
frame: when this window presented it, and when Windows put it on the screen.
The phone stamps each frame with its own clock (CLOCK_MONOTONIC); every `pong`
says what that clock read, half way through the ping's round trip is the best
guess of when, and the ping with the shortest round trip is the guess with the
least doubt. The screen's time comes from the swap chain's frame statistics:
the present count of the frame Windows last showed and the refresh it went
out at (`SyncQPCTime`, less a refresh period for each refresh since), matched
to the frames presented. Each window logs, every ten seconds, the frames it
showed, the decode time, and both ages' median and 95th percentile; the
phone's side logs its frame rate, bitrate and encode time. A Mac's window logs
when each frame was handed to macOS instead: the layer does not say when it
was shown.

**CPU** on the computer is `QueryProcessCycleTime`, which counts the
processor's time-stamp counter. That counter runs at a fixed rate, so this is
CPU time at a fine grain, not work: it still moves with the processor's clock,
and scrcpy's own window came out at 1.6 M and at 3.8 M per frame in two runs.
So runs are interleaved, scrcpy's and this one's in turn, and compared in like
states. Content matters as much: the Clock app's main screens draw 0–10 frames
a second depending on the tab, so its stopwatch (a steady 15) is the workload
the numbers above come from. On the phone, jiffies of every process a window
involves (`/proc/<pid>/stat`: the servers, `media.codec`, `surfaceflinger`,
`system_server`, the app), per thread (`/proc/<pid>/task/*/stat`) to see
which part, and the servers' memory as PSS (`/proc/<pid>/smaps_rollup`).

**The probe**, `cargo run --release --example app_window -- <serial> <package>
[label]`, opens one window with everything a real one has, without the rest of
the app: it pushes the dex from `resources/bin` and starts the server itself.
Like `cargo test`, its executable needs a Common Controls v6 manifest beside it
(`app_window.exe.manifest`), which tauri-build only writes for the app itself.
Close it with its ✕ (or `WM_CLOSE`), not by killing it: that is what ends its
server on the phone.

## Linux apps

The Linux apps the launcher lists — everything in XFCE's Applications menu —
are on the same page with a Linux mark, read from the launcher's
`AppsProvider` (`linux.apps`, `linux.icon`): shell only (DUMP), because the
guest lives in the launcher's storage, not the server's. The server calls it
in-process (`getContentProviderExternal`), about 20 ms.

Opening one: `linux.run` starts it on the launcher's window display (`:2`),
first putting the launcher on Android's temporary allow-list for 30 s, since a
foreground service may not be started from the background otherwise. The X
window it makes is reported over `linux.events`, a long poll, and the computer
opens a window for it like any other, except that what goes on its display is
the launcher's `LinuxWindowActivity` for that X window, started there by the
shell (it is exported to holders of DUMP). Its menus and popups are drawn
inside the window.

**One session, one place.** Linux's windows live in one window session on the
phone, and it is either the phone's own desktop's or a computer's. While the
desktop has it, `linux.run` refuses and says why ("Linux is showing its windows
on your phone's desktop right now…"), and the page shows that, rather than the
app quietly opening on the phone. (It used to open there.)

**Closing.** An Android app has somewhere to go back to — its task, on the
phone — and a Linux window has not: its ✕ asks the app to close it
(`linux.close`, as a Linux desktop's close button does), the app may ask about
unsaved work first, and the window goes when the X window does. A second ✕
within six seconds closes the window here, for an app that does not answer.

## Sound

While any app of a phone has a window here, the phone's sound comes here too,
through the same audio companion the desktop uses (scrcpy with no video), if
the person chose to hear it here and the phone is Android 11 or later. It
starts once the first window's picture is there — the companion is a JVM of
its own on the phone, and starting it beside a window slowed the window — and
stops with the last window. On exit the windows close first, and the
companion is stopped without the desktop's undo: that undo force-stops the
launcher and sends the phone to its home screen, from whatever its owner had
open on it.

### Where it plays

Every window has a speaker in its title bar — beside minimise on Windows, at
the bar's right end on a Mac — and it opens the desktop's media-output flyout
as a menu (`apps/sound.rs`): the same answers, in the same words, and the same
one setting behind them, the `audio` and `audiodup` this computer keeps for
the desktop.

- **Computer only** — the companion moves the phone's sound here.
- **Computer and phone** — it copies it here and the phone plays too: Android
  13 and later, all scrcpy can do; greyed below it, saying so.
- **The phone's own outputs** — its speaker, wired headphones, a USB card,
  earbuds — as the phone lists them (`AppAudio`, the launcher's
  `DexMedia.outputs` again, the names the products' own): the companion stops
  and the phone's media is pinned to the one picked, the window daemon's
  `AUDIOROUTE` lever (`Audio.java`), pulled by the app server, which is the
  shell too.

A Linux app's window has the same speaker and the same answers: its sound
leaves the phone through the launcher's own `AudioTrack` (`LinuxAudio`, media
usage), which the companion captures and a pin routes as it does any app's.

**The pin first.** A phone output is pinned before this computer stops
playing: a pin that failed must not already have silenced the computer and
sent the sound somewhere nobody chose — the order the desktop's
`routeToPhoneOutput` keeps. Back to this computer, the pin is let go of, so
the phone's half of "computer and phone" plays where the phone would on its own.

**Phones that will not pin.** Pinning is `MODIFY_AUDIO_ROUTING`, and the shell
does not hold it everywhere: a Galaxy A13 on Android 12 and a Redmi 9A on 11
refuse it, and the desktop's flyout falls back to the platform's picker there
for the same reason. The phone says so (`canPin`), and the menu's third answer
is then the phone as a whole — "Phone only", as the desktop's Settings words it
— which plays where the phone sends it by itself: earbuds while they are
connected, else the speaker.

**What is checked** is what happens: the stored answer, "computer and phone"
shown as "computer only" on a phone below 13; on the phone, the output media
is pinned to, else where the phone says it plays now (Android 12 and later),
else the speaker.

**One setting.** A choice is stored as the launcher's `cfg` rows store it,
carried out for every phone with a window here (its companion cycled, as a
choice made on the desktop is), and told to the launcher (`AppsProvider`'s
`media.mode`), so the desktop's flyout and Settings show it the next time
they open. A launcher too old for the method keeps its own record of the
choice; that is all it costs.

How a pick went is said for a few seconds in a strip under the title bar —
"Sound: Computer only" — and the reason it failed, if it did, goes to the log.
On a Mac that strip is the window's own, as on Windows: the subtitle, where it
used to go, has room for a word or two in a window the shape of a phone, and
none at all in full screen.

## Dropping files

Files dropped on the app in a window — from Explorer, or from Finder — go to
that app, as a drop from another of the phone's apps would: a chat attaches
them, a text field takes a text file's text. Dropped on a window's title bar
(Windows), or on this app's main window, they go where a drop on the desktop
goes: into the phone's Download folder. So do files dropped on an app that
takes none at that point, and the window says so ("Settings didn't take
report.pdf: it's in Downloads on your phone"). An APK is installed wherever it
is dropped, and a folder, which no app takes, goes to Download with its files.

In Download a folder brings its files, in folders of the same names (links not
followed, at most 5000 files). Nothing is overwritten and nothing is asked: a
name already there becomes "report (2).pdf", as wherever else files arrive on
this phone (`WebFiles.uniqueIn`), and the window says the name it got. Names
are made safe for shared storage, which may be FAT underneath, and none can
climb out of Download.

**Into the app.** The shell can leave no file anywhere an app may read, and
the server, which is no app, can start no drag ("Dragging out"). So each file
goes first into the launcher's cache: the server opens it for writing through
the launcher's provider, as `content write` would (`stage`, AppDrop). Once the
last is there, the window drops them all (`dropIn`, AppDropIn): the
launcher's corner of the display is armed with them, the server presses on
it, and the press starts a drag of them there. It is a global drag, and the
right to read the files goes to whichever app takes it; the launcher's
`DropsProvider` serves them, and nobody else may read them. The server carries
the press to where the computer's pointer let go and lets go there. The app
under that point takes the drop as it takes any, or does not, and how the drag
ended says which. Taken, the files stay in the launcher's cache for a day, as
the app may read them again while it sends them; not taken, they are copied
to Download, and go. On a Galaxy A13 the drop took 0.4 to 1.4 s, from asking
to the answer.

Taken is said of the whole drop, not of each file: a chat may take a drop's
photos and leave the PDF with them, and a text field takes any drop and reads
only text. So the launcher keeps which files an app has opened (each open
goes through `DropsProvider`), and once a drop is taken the server waits up
to 5 s for the app to open them all (`drop.opened`). Each it has not is
copied to Download as well, and stays the app's to read, as an app that reads
nothing until it sends may still do. The window says so: "Dropped 2 of 3
files into WhatsApp; report.pdf is in Downloads on your phone". An app that
reads all it takes, as one showing what it was given does, answers no later
than its last read.

**The corner pressed.** It is an overlay of the launcher's, one pixel, put up
for the press (PcDrags `arm`); the server lets the launcher draw over other
apps while windows are open, as it does for Android 17's drag pixel
(`LauncherOverlay`). The drag pixel itself, a presentation below Android 17,
was not enough: on a Galaxy A13 an app's window sat above it, and the press
went to the app. Measured on 2026-11-01, on the A13 (One UI 4, Android 12):
while the app on a window's display has a focused text field, One UI lifts
that display's whole task area out of the window hierarchy's root and over it
(a `RootWrapper` layer in SurfaceFlinger), so every app there is above every
other window there — the overlay, the drag pixel, and accessibility overlays
too (PadPointer, `overlaysBuried`). A drop on such an app finds no corner to
press and goes to Download, and a drag out of one would miss the pixel the
same way (the same layering; not run). With no text field focused (Clock, Files) the
order is Android's own, and the pixel is on top; letting the overlay be the
keyboard's target (`FLAG_ALT_FOCUSABLE_IM`) changed nothing. The launcher
answers only once the overlay has drawn, and the server presses 400 ms after:
150 ms missed on the A13. An app that hides overlays (Files, Settings) hides
that one too, so the pixel is pressed next. A press
neither took went to the app's own corner, where its Back arrow often is: it
is cancelled, not let go, and the files go to Download. So do drops on
Android 11 and older, which have no pixel, and on a Linux app's window, whose
app is X's. While the drag is carried, the computer's own pointer goes
nowhere, and the press is the drag's alone (`AppInput.carry`): the window
losing the keyboard on the computer, or its connection going, lets go of
every other key and button but not that one, which would drop the files
short of where they were let go, and the carry's next step press the app.

A text field asks a provider which types it can give before it takes a
dropped file as its text (`getStreamTypes`); a provider that names none (the
default) had its drop taken and nothing put in.

**The main window.** A drop on this app's own window goes to the phone the
page in front shows: the Desktop page's (its desktop's, else the one it
offers to open), the Apps page's, or the Linux page's on the cable. With two
phones connected, never the one out of sight. Into Download, an APK
installed, its notes as the window's toast. While files are dragged over it,
the window says where they will go.

Not the desktop's way, scrcpy's `adb push`: a process per file, adb's own
command line, `run_adb`'s 25-second limit, and no progress a window could
show. Each file is a connection of its own to the app server (`put`,
`AppDrop.java`), through adb as a window's is, so nothing listens on this
computer; its bytes are streamed as they are read here, written to a `.part`
renamed once the last is there, and the `.part` goes on any failure — a drop
cut short leaves nothing behind. Quitting mid-drop is one of those: the
server stops each drop as it stops its windows, and waits for its `.part` to
go. An install it was still waiting on when it had to end leaves its APK in
`/data/local/tmp`, swept by the next server as it starts. The phone says how much has landed as it
lands, and that is the progress the window shows under its title bar:
"Sending 2 of 4 to your phone… 34%", then "4 files are in
Downloads on your phone" for a few seconds. One drop at a time, from every
window: a second waits for the first rather than sharing the link with it.

An APK is written to the shell's `/data/local/tmp` and installed with `pm
install` (`cmd package`: a binder call, not a JVM), as `adb install` would,
then deleted. When the installer refuses, its reason is worded for a person:
"a newer version is installed", "the phone doesn't allow installs from a
computer" (Xiaomi's Install via USB). From Android 11 the phone's shared
storage tells the media store of a new file itself, as it closes; before it,
the file is scanned, so the gallery and the file pickers see it either way.

A Linux app's window takes a drop the same way, into the phone's Download
folder, not Linux's home: the guest sees it as `/root/storage/Download` when
the launcher may read shared storage (`linux-rt.sh`'s bind, which the window
session shares).

A 9 MB file took 516 ms over a Galaxy A13's USB (17 MB/s) and 904 ms over a
Redmi 9A's (10 MB/s).

## Dragging out

A drag that starts in a phone app's window can end on this computer: a
picture out of the Files app onto the desktop, into a mail, a folder. On the
phone the drag is Android's own, started as the app starts it — Files on a
mouse drag, most apps on a long press — and as the pointer leaves the window
with it, the phone ends it on a window of its own and says what it carried,
and this computer's own drag takes over from there, under the pointer, with
the button still held.

**Hearing a drag.** Android tells every window on a display when a drag an
app let go of — a global one (`DRAG_FLAG_GLOBAL`) — starts and ends there, and
gives what it carries, with the right to read it, to the window it is dropped
on. A drag an app keeps to itself, reordering a list, is not told and does not
leave. So each Android app window's display has a window of the launcher's
(`PcDrags`): one pixel at its top left, fully transparent and taking no touch —
Android lets every touch through a window like that — until a drag starts,
when it takes them (the drag has the pointer then anyway). It is the
launcher's because the server is no app: from Android 14 the window manager
refuses a session to a process the activity manager did not start ("Unknown
pid=… uid=2000", seen on the Android 16 emulator). It is a presentation
window, which any app may put on a public presentation display — every
window's display is one — so it needs no overlay permission, and Android does
not hide it while Settings shows, as it hides overlays (an overlay is the
fallback where a presentation is refused). The server asks for it as the window opens
(`drag.watch`), and hears what the drags do on one call that waits until
something happens (`drag.events`), as Linux's windows are heard; the window
tells this computer (`drag`, on and off).

**Android 17.** There a presentation hides every other app's activities on its
display whenever the app showing it has none of its own on screen: with no
desktop running, the window's own app was never shown, and its window here
waited on "Opening on …" for good (a Galaxy S25 Ultra's logs, 2026-10-31,
and the Android 17 emulator; with a desktop running it was shown). So from
Android 17 the pixel is an overlay, which hides nothing beneath it. The server
lets the launcher draw over other apps while windows are open, and gives that
back with the last one unless a desktop keeps it (`LauncherOverlay`, shared
with the phone's touchpad; who gave it is kept on the phone, so a server that
dies with it given still has it taken back). A launcher from before this,
which would put a presentation there, is asked for no pixel at all
(`drag.caps`): its windows open, and nothing is dragged out of them until the
launcher is updated. An overlay has its own cost: Android hides every app's
overlays while an app that wants none is on screen (the Files app, Settings, a
prompt such as "Allow wireless debugging"), so on Android 17 a drag out of
those is not heard. A pixel put up only at the hand-over would not hear it
either: a window that comes while a drag is under way is not told of it there
(tried).

**Handing it over.** When the pointer leaves the picture with the primary
button held while the phone says a drag is under way, this computer asks for
it (`dragOut`). The server moves the phone's pointer onto the pixel and lets go
there: the drag is dropped on the launcher's window, which takes its read
permission through the platform's own content listener (Android 12 and later:
`setOnReceiveContentListener` — no hidden call), grants each content URI to the
shell for fifteen minutes, and says what it carried: each item's name, type
and size, as its provider says them, and any text. The server offers them by
number (`dragged`), and the press is this computer's from then: the phone
ignores its pointer until the button is let go. A drop that landed elsewhere,
or none within 3 s, is `dragged` with `none`, and the press goes on in the
app. The pixel takes a drop 150 ms after the drag started at the earliest —
its relayout has to land — and the server waits out the rest if the pointer
left sooner.

**On this computer.** Every file starts on its way here as soon as the phone
says what the drag carries, into a folder of this app's under the temporary
folder (`apps/fetch.rs`), so a drop seldom waits for it; one that takes longer
than 0.4 s says how far it is under the title bar. A Mac's own drag starts at
the pointer's next move — inside a drag of the mouse, where AppKit begins
one — each file promised (`NSFilePromiseProvider`), as Photos drags
pictures: Finder, Mail, Messages and the rest ask for a file as it is dropped,
on a queue of this app's, and it is written there; text goes as text. On Windows the files come
first, then Windows' own drag carries them on (`SHDoDragDrop`, with the
shell's data object for them, as Explorer drags files): Windows' drag wants
files that exist, and the note says how far they are meanwhile. Let go before
either could start, they go to Downloads, and the window says so.

Each file comes on a connection of its own (`get`), as a dropped one goes the
other way: the phone reads it as the shell, its provider reached as an
external one — as `content read` reaches it — and held for that read only.

**What does not leave.** An app that starts no global drag: many galleries
start one only in split screen or on a tablet, and a long press there
selects instead. For those, the app's Share → Copy (or its own "Copy to
clipboard"), and More's Copy, bring a picture over through the clipboard
("The clipboard"). Android 11 and older: the launcher's listener needs 12. A
phone whose launcher is from before this: nothing is heard, and nothing is
carried out — until the desktop brings the launcher up to date: the next
window to open asks again, and the open ones with it. The phone's touchpad
does not: it puts this build's launcher there only where the phone's cannot
open the pad, so one that has the pad but is from before this stays as it
is. A Linux app's window: its drags are X's. Full screen: the pointer cannot
leave the screen.

Measured on the Android 16 emulator (`test/apps-drag-test.mjs`, 13 checks):
Files' own drag of a picture heard as it started (`image/png`), handed over
117 ms after `dragOut`, its name, type and size said, and the file taken
byte for byte; a press with no drag under way hands nothing over; the window
takes the mouse again after.

## Verified, and not yet

On a Galaxy A13 (Android 12, USB), from the app itself and from the probe, on
Windows 11:

- the list and icons; opening, a second window beside the first, reopening an
  app handed back earlier; the start times in the table, three of each;
- the mouse, the wheel, typing (after a click, the display being untrusted);
  resizing; Back, in the title bar;
- pasting both ways as a person does it: Ctrl+V in Settings' search field put
  what this computer had copied there, and Ctrl+A, Ctrl+C there put the field's
  text on this computer's clipboard (input posted to the window itself, with
  Ctrl set in its thread's key state: no other window saw a key);
- an app finishing closes its window; ✕ hands the app back and leaves the
  phone's screen as it was; exit leaving no server, display or forward on the
  phone;
- the command a pinned taskbar button runs (`--phone-app`), started the way
  Windows starts it — not a real pin and click on the taskbar;
- the sound companion.

On the Android 15 emulator: the trusted display's flags (`OWN_FOCUS`
included), `linux.apps` in-process, and the shell starting
`LinuxWindowActivity` on a window's display.

On a Mac (Apple silicon, macOS 26.6) with the Android 16 emulator (arm64,
the software encoder), from the app itself:

- it builds and runs, its tests pass, and it compiles for Intel Macs
  (`x86_64-apple-darwin`);
- opening from the command line (`--phone-app`): 2.0–2.6 s after the run
  started, 0.3–0.7 s into the running app; a second window beside the first;
  an app finishing closes its window; reopening an app handed back earlier;
  over the cable and paced, over the emulator's second, network transport;
- the picture, captured: sharp and filling the window; a resize through the
  window's frame (the phone's display follows, the app lays itself out again);
  the title bar's Back; ✕ handing the app back, the phone's screen as it was;
- the phone's H.264 through the same CoreMedia calls into VideoToolbox, by a
  separate tool: 390 frames across a resize, none failed;
- the phone's copies reaching the Mac's clipboard; a dropped link (`adb
  reconnect device`): the window dims its last picture under a line saying
  so, the apps go back behind, and opening the app again replaces the window,
  its sound with it;
- real input, through the HID tap and AppKit's own text input: a click (the
  whole press/release sequence on the window's display), a right click as
  the secondary button, the wheel and the trackpad, typing `abc`, ⌥E then E as
  é (the marked text, then the dead key on the phone), 你好 (pasted), ⌘A ⌘C
  putting the field's text on the Mac's clipboard, ⌘V putting the Mac's text
  in the field, ⌘[ as Back, ⌘M to the Dock and back; the Apps page's tile
  pressed (through Accessibility, its own click handler) opening the app;
- full screen (Settings, on a 2560 × 1440 screen): More's Full screen and
  ⌃⌘F, the app laid out for the screen, no menu bar or title bar coming down;
  the bar down at the top edge and on its tab, back up 0.6 s after the
  pointer left, down for its first two seconds; its More ("Exit full
  screen"), its way out, its Back and its ✕ (the app back on the phone); Esc
  out of full screen and not reaching the app — and, in a window, still
  reaching it (`KEYCODE_ESCAPE` on the window's display).

On the same emulator, through a test client speaking the wire: clicks, Back,
the wheel, typing (é É ü ñ à ê ç ß € 你好 😀 Жук, the clipboard given back and
nothing sent up), Ctrl+V of the computer's text, resize, the paced stream.

The sound menu, the clipboard as it is copied, and drops (2026-10-24):

- the phone's half on a Galaxy A13 (Android 12) and a Redmi 9A (Android 11),
  both on USB, by a harness speaking the wire: the `audio` state after hello,
  `canPin` false and a pin refused for want of `MODIFY_AUDIO_ROUTING`, the
  connection going on after it; the clipboard both ways between processes,
  nothing handed back; `put` — a file whole (by checksum), "(2)" beside one
  already there, a hostile name and folder kept inside Download, a drop cut
  short leaving nothing, an APK installed and a file that is not one refused,
  nothing left in `/data/local/tmp`, the media store knowing the files;
- in the app on Windows 11 with the A13: the speaker's menu in Windows' dark
  theme, the Android 13 answer greyed, "Phone only" stopping the companion
  and "Computer only" starting it again in about 330 ms, the setting stored,
  the note under the bar; a copy here reaching the phone with the window
  behind others, three in a row being three items in the Samsung keyboard's
  clipboard, a copy on the phone on top of Win+V's history, and one marked
  `ExcludeClipboardContentFromMonitorProcessing` staying here;
- a drop through a window's own `WM_DROPFILES` handler, posted in-process as
  Explorer's arrives (the probe's window): a file, a 6 MB file and a folder
  with one inside it, an APK, the same file again as "(2)", each with its note.

Dragging out (2026-10-29, the Android 16 emulator; `test/apps-drag-test.mjs`
above): in the Mac app, a picture dragged out of Files was handed over and
taken byte for byte into the app's folder; the launcher brought up to date by
More's "Use phone as touchpad" (an old one on the phone, as a read-only
emulator has), and the window open already asking again with the next, the
launcher catching drags on both displays.

Dragging out on Windows 11 (2026-10-29 and 30), in the app:

- with a Galaxy A13 (Android 12): a picture dragged out of My Files into an
  Explorer folder, byte for byte (a long press: a plain drag scrolls its
  list); Gallery's "Copy to clipboard", tapped from adb, then Explorer's own
  Paste, byte for byte; More's Copy and Paste with text in Settings' search;
  a copy of `test/apps-drag-test.mjs` naming DocumentsUI's `FilesActivity`
  (neither phone lists Files to open), 13/13 in seven runs in a row,
  hand-over 33–52 ms, with nothing else listed in Download (it presses the
  first item there);
- with a Redmi 9A (Android 11): a picture's clip, set from the shell (MIUI
  refuses the shell's input), pasted into Explorer, byte for byte, the app
  server staying up.

Android 17's pixel (2026-10-31, the Pixel 9 Pro Fold emulator, read-only;
this change's launcher and dex in the released v0.3.27 portable, whose
computer's side it leaves as it was), with no desktop running:

- v0.3.27 first: Files opened in a window stayed on "Opening on …" (its
  activity never asked to be shown, `visibleRequested=false`), and showed 4.6 s
  after the launcher was force-stopped, its pixel going with it — 63 s after
  it was asked for. With this change, Files showed in 3.1 s, its pixel an
  overlay (`dumpsys window`), the launcher let draw over others while it was
  open and given back (`default`) when it closed;
- `test/apps-drag-test.mjs`, which now opens Files by its activity and checks
  for its picture: 8/8 there (its Android 17 branch), and 14/14 on the Galaxy
  A13 (Android 12) with this dex and v0.3.27's launcher;
- a picture dragged out of Chrome (a page served from this computer), handed
  over and taken byte for byte, 761 of 761 bytes; the Files app's drag not
  heard, as said;
- `test/pad-test.mjs`: 27/28; the one failure, the pad not force-hidden by
  `dumpsys`'s account while Settings shows (the pad itself says it is hidden),
  is the same with v0.3.27's dex.

Shortcut keys and drops into the app (2026-10-31; the server by
`test/apps-keys-test.mjs` and `test/apps-drop-in-test.mjs`, speaking the wire
as the computer does; for drops, this launcher put on each phone by the
desktop):

- the keys on a Galaxy A13 (Android 12) and the Android 16 emulator: Shift+Left
  selected nothing in Settings' search with the server from before this, and
  selects the last word with this one; Ctrl+A and Ctrl+C copy the field's text
  with both. The Android 15 emulator ran this one only. On a debuggable Android 15
  emulator the order itself, as its input dispatcher dispatched it: Ctrl down,
  A down, A up, Ctrl up; Ctrl let go first going up first; Ctrl+Alt+A
  unwinding the other way round; typed text after them with only its own
  Shift; a window losing the keyboard with Ctrl+Shift+Left held letting go of
  all three, once each (11/11);
- a text file dropped on Settings' search field on the A13, from the drag
  pixel (before the overlay): the field took it and its text was the file's,
  read through the drop's permission (Ctrl+A, Ctrl+C), in 0.37–0.61 s, three
  runs (two earlier ones took it and put nothing in, before `getStreamTypes`);
  a drop where nothing takes it refused and its file in Download byte for
  byte;
- with the overlay, the final build: the Android 17 and 15 emulators 6/6 each
  (a drop nothing takes refused, in Download byte for byte, the app not
  clicked on the way, the launcher's drawing over others given back with the
  window); on the A13, two drops on Chrome dragged from the overlay at the
  first press and were refused there (1.0–1.4 s). The same drops before the
  overlay, and on Files and Settings' search, missed the pixel: the press was
  cancelled, the app not clicked, the files in Download. The existing drag-out
  test failed the same way that day on the A13 (the drop missed the pixel),
  where it passed 14/14 before;
- the computer's half: `drops.rs`'s sentences by its tests, and both computers
  compiled (Windows built and run; the Mac type-checked for Apple silicon).

A drop an app uses part of, and a drop carried while the window loses the
keyboard (2026-11-01; `test/apps-drop-in-test.mjs` on a read-only Android 13
emulator, this launcher put on it by the desktop):

- Settings' search there is Compose too: 6/6, the field's cases not tried;
- an activity of nothing but a plain text field instead (a scratch build, put
  on that emulator only, as no app on the image has a text field that is a
  View), named to the test: 16/16 in three runs, each drop pressed on the
  overlay. A text file dropped on the field was taken and opened, its text
  the field's, nothing put in Download (1.3–3.1 s). A text file and a picture
  dropped together were taken; the picture, never opened, was in Download as
  well, byte for byte, and the text file not, its text the field's (7.0–9.1 s,
  the server waiting its 5 s for the picture). A text file dropped while
  `blur` came every 20 ms, 45 to 140 of them during the drop, was taken where
  it was let go, its text the field's;
- the server from before these two, on the same emulator and app: 12/16. The
  picture was taken with the text file and nothing said of it, in Download or
  not; and under the blurs the press was let go before its drag had started,
  so no drag was made, the file went to Download and the field stayed empty;
- the main window choosing its phone by the page in front: compiled
  (TypeScript), not run.

On main after the merge, on the Galaxy A13 (2026-11-01; this launcher put on
it by the desktop; the wire tests with nothing of the computer's app running,
the rest in the app itself, driven over its page):

- `test/apps-keys-test.mjs`: 5/5 (Settings' search, Ctrl+A, Ctrl+C, Shift+Left);
- `test/apps-drag-test.mjs`: 14/14 (a picture dragged out of Files);
- `test/apps-drop-in-test.mjs` with the scratch app's text field: 7/16. Every
  drop found no corner to press — the field was focused, so One UI had lifted
  the apps over the launcher's windows ("The corner pressed") — and went to
  Download byte for byte, the app not clicked, no input refused;
- the close prompt: with nothing open, ✕ closed the app at once; with Clock
  in a window, it asked ("This closes with it: Clock"), Cancel kept both
  running past the 3 s the app waits for an answer, and Close ended both;
- Paste in the main window's right-click menu, a file on this computer's
  clipboard: in the A13's Download byte for byte, the toast saying so.

Phone or tablet (2026-11-01, on a Windows 11 computer, no phone attached). A
review the same day found four things, all real, all since changed: on a Mac
a size picked in full screen was looked at again once out, and skipped where
the window came back that size already, with no note; the menu was said to
show the app's layout, which it cannot know (its items were Phone layout and
Tablet layout then); a kept window was fitted to 90 % of its own screen again,
shrinking one as tall as the screen; and the rounding down of the dpi could
take the lean past the eighth (12.7 % at a custom 104 dpi). After the changes:

- the arithmetic, by the tests of `shape.rs` and of `link.rs`'s display size,
  the files as they are built on their own (the app's own test binary does
  not start on that computer, `STATUS_ENTRYPOINT_NOT_FOUND`, builds from before
  the change too): the lean at 175 %, its stop at 525 points, the eighth held
  to the dpi at every Windows scale from 96 to 288 dpi and every even height
  from 200 to 2398 pixels, a window taller than wide left a phone's size, the
  phone's shape fitted as before, Tablet size fitted, maximised or without
  room from 100 % to 250 %, a window opening as it closed (one snapped to
  half the screen as it was), and the file kept, written over when it was
  broken, never left half written;
- both computers compiled, no warnings in what changed: Windows built and
  linted, the Mac type-checked and linted for Apple silicon (`--all-targets`,
  a stand-in C compiler for the build scripts).

Pinned apps (2026-11-02, on a Windows 11 computer, no phone attached):

- a taskbar shortcut made for real (`IShellLinkW`), as an older pin is — an
  older portable folder's executable, a phone's Wi‑Fi address, an icon in that
  folder, a phone app's AppUserModelID — and the folder then deleted. Healed,
  it starts this copy from its folder, for the phone the address was, with an
  icon that is there, read back as Windows reads it when the pin is clicked;
  healed again, nothing changes; another program's shortcut is left alone. The
  test binary started with Common Controls 6 from a manifest beside it, as it
  does not start otherwise (`STATUS_ENTRYPOINT_NOT_FOUND`, above);
- the app itself, a debug build, its main window watched by its class: started
  plainly, made shown (1.3 s after the start); started as a pin with a phone
  that never came, made hidden, and shown 4.9 s after it was
  made; started by hand while a pin's run waited, the second start handing
  over and going, and the main window shown 0.4 s after — and, started so
  while its webview was still starting, shown as it finished, where before
  the window was made in `setup` the request was lost;
- what a pin reads and writes, by the tests of `pin.rs`: the command line and
  the URL, and what they refuse (no package, a quote, a Linux app); a Windows
  command line split as Windows does; a desktop entry, its `Exec` with a path
  holding spaces, `$` and `%`, read back; the Dock app's `Info.plist`, script
  and tile, the tile found however the Dock wrote it, and its `.icns`;
- Windows built and linted, and all 225 of the app's tests run; the Mac (Apple
  silicon) and Linux (x86_64) type-checked and linted, `--all-targets`, with
  stand-in C tools — and for Linux a stand-in `pkg-config` that has every
  library. No warnings in what changed, on any of the three.

A review the same day, by a second model with the phones and the files around
the code stood in for, found four things, all real, all since changed: an
older pin's Wi‑Fi address that no remembered phone was at was matched against
the phones attached now, so another phone given that address would have been
opened; a Mac's URL was held to the Dock's apps by package alone, so one could
name another phone for an app kept there, and opening it pointed that Dock app
at the other phone; a Linux development build wrote its own path into an
app's desktop entry, breaking the pin once the build had gone; and on Linux an
app already open was brought forward by a call that always fails there, and
the click reported as done. After the changes: the first two by the tests of
`pin.rs` (an older pin's serial read back, a Dock app's script read back);
all 227 tests run on Windows; the three computers built or type-checked, and
linted, again, with no warnings in what changed. The two Linux changes were
type-checked, not run.

On the Galaxy S25 Ultra (Android 17, on the cable and over Wi‑Fi), a release
build of this in a copy of v0.3.30's portable folder (its phone half is this
one's), Windows 11, 2026-11-02 — the pin made, and clicked, by the person:

- started as a pin starts it: Calculator on screen 2.2–2.6 s after the run
  began, the main window never shown, the window's relaunch command naming the
  phone's own serial and this copy;
- the person's pin was an implicit app shortcut, made by v0.3.30's Calculator
  window and pointing at v0.3.30's folder — this copy's windows changed nothing
  in it, and its click started v0.3.30. That is why `heal` reads
  `ImplicitAppShortcuts` now: the first run of this copy after the change
  pointed the pin at it, and its click started this copy (2.6 s to the
  picture); a copy replacing another, as a portable one is, with the pin
  following;
- what the pin is called: the package, from a window opened at start, until
  the phone's name for the app took its place ("Calculator");
- a click with the cable out and Wi‑Fi on: the app over Wi‑Fi, 2.4 s; with
  the phone away: the main window after 4 s, and the app 1.5 s after the cable
  went in; the cable pulled from a window: on over Wi‑Fi, nothing lost;
- a pinned run quitting as its window closed, four times;
- v0.3.30, clicked with the phone away, opened the app twice once it came —
  from its page and from the pin — and the second never reached its display;
  the page now leaves an app to its pin while that waits;
- UI Automation could not press a taskbar button (no Invoke on Windows 11's
  taskbar, and injected clicks were not taken), so the person's own hands did.

Not yet:

- **A window that gave up, clicked**: the phone never went for the 30 s that
  takes — Wi‑Fi kept it — so "Click here to bring the app back" has not run.
  Nor has the Apps page's deferring to a waiting pin, nor a click after a
  restart of the computer itself (stopping adb's server stood in for one in
  the plan, and did not run either).
- **Linux and the Mac**, at all: not a GNOME or a KDE dock, on X11 or Wayland,
  showing scrcpy's window as the app and pinning it; not More's "Keep in
  Dock", nor a click on its tile, nor `RunEvent::Opened`. Both have only been
  type-checked: their parts that need no system are among the tests above.

- **Phone or tablet with a phone**: the menu's pair and its notes, a window
  opening at its kept size and maximised, and a density changing within one
  screen's scale on a live window's display — the server takes a density
  with every resize, but only a move to a screen of another scale had sent a
  new one before. Which apps change their layout where: none was looked at.
  Nor on a Mac at all, its full screen's way out included.

- **A drop from Explorer onto an app window, and onto the main window**: the
  computer's half of both — the drop point, the title bar's way to Download,
  the main window's own drop and its toast — compiled, and the phone's half
  tested by the wire, but not one real drop made yet. Nor on a Mac.
- **A real app taking a drop from the overlay**: only the scratch app's plain
  text field has. Chrome's address bar refused one, and the apps here that
  took drops hide overlays or are Compose; a chat app's attaching a file
  (WhatsApp) not tried, none being on the test phones. So neither is how long
  a chat takes to open what it attaches, which the 5 s wait for it assumes.
- **A drop into an app with a focused text field on One UI 4** (a chat with
  its message box open): it goes to Download ("The corner pressed"). A way
  out, not tried: start the drag from a translucent activity of the
  launcher's on that display, which is in the task area itself and so above
  the app — at the cost of the app pausing for the second the drop takes.
- **The main window with two phones connected**: the drop following the page
  in front is compiled, not run.
- **TeamViewer itself**, whose missing Ctrl this was for: not on the test
  phones. The keys it now gets are a keyboard's, as checked above.
- **The drag pixel on the A13**: why an app's window rides above it on some
  days, and whether dragging out should move to an overlay as dropping in did
  (an overlay is hidden over Files and Settings, which the pixel is not).

- **This change on a phone with Android 17**: a Galaxy S25 Ultra's logs with
  v0.3.27 were read, not this change run there; nor yet on One UI's own apps
  (My Files, Gallery), which may or may not hide overlays as Files does.
- **A drag out landing on this computer**: on a Mac, the drop on Finder
  (this Mac's drag now begins at the next move of the mouse — the first try,
  from an older event, never landed); a second try stopped on an emulator too
  slow with the Mac swapping (windows taking 2 minutes, Files black). Nor yet
  run on a Mac: More's Copy and Paste, and a picture copied on the phone
  reaching its clipboard. On Windows: More's Copy and Paste on the Redmi 9A,
  whose MIUI refuses the shell's input while its "USB debugging (Security
  settings)" is off; a folder dragged out (it ends in
  "couldn't be copied"); and why the A13 once gave a drop to the app's own
  window instead of the catcher (a folder's drag; not seen since).
- **A cable pulled from a Samsung phone**, the case this was made for: the
  emulator's transports neither restart adbd nor drop Wi‑Fi on their own. Its
  steps were each shown there ("Cable or Wi‑Fi"), not the phone's timing. Nor
  a cable really pulled at all: Windows' adb cannot `detach` a phone, so the
  cable was dropped by this computer (`adb reconnect`), and came back.
- **Wi‑Fi beside the cable, after "Allow"**: a phone switched on by this app,
  asked, allowed, and then unplugged — its owner's tap, which a test may not
  give for them. Shown: the switch on and the phone asking (Galaxy A13), and
  everything after "Allow" on a phone that had it on already (Redmi 9A).
- **The exit undoing it on a phone**: waiting for a try under way, and
  switching off over Wi‑Fi when the cable went just before — unit-tested
  (`wind_up`, `ways_to_switch_off`), not run, for want of a phone this app
  had switched on (above). Run: the exit letting go of a link, and switching
  off a phone that never got as far as "Allow".
- **A phone refusing the switch**: the Redmi 9A's refusal was seen, from the
  shell; its windows' note, and its owner switching it on after, were not run.
- **What it takes, besides**: a phone allowed for the cable's while only, or a
  network that keeps phone and computer apart, has not been tried.
- **A window's way changing on a Mac's screen**, with the new note and title:
  type-checked, not yet run there. On Windows it was run with the Redmi 9A
  (2026-10-27): "Now over Wi‑Fi", "Wi‑Fi · Redmi…", and back.
- **Full screen on Windows**: compiled, and its layout's tests run, but not
  yet run on a Windows computer. Nowhere yet: a window taking over from a
  full-screen one (it should go full screen too).
- **On a Mac**: an input method's composition with candidates (Pinyin, say —
  only a dead key's marked text has run), the Dock icon a minimised window
  shows, and an Intel Mac, run.
- **The Mac's speaker, drop and clipboard**, which came after that run: the
  menu under its title-bar button, a drop from Finder (a file reference made
  a path), the notes under the title bar, the pasteboard watched.
- **A pin on a phone that allows one**: the outputs listed, earbuds picked and
  the sound back from them — no phone here lets the shell route audio.
- **The launcher told** (`media.mode`): both phones here run a launcher from
  before `AppsProvider`, and the desktop is what installs a newer one.
- A drop from Explorer itself, the system's half of `WM_DROPFILES`, and onto
  an app run as administrator (the message filter is let through, untried).
- The same text copied again in a phone app after something that does not go
  was copied here: nothing on these phones' shells can set a clip that is
  already there, as an app does.
- A Linux app's window: its speaker, the clipboard through the launcher, a
  drop on it — the test phones are 32-bit (below).
- A drop over Wi-Fi, measured.
- A Linux app end to end on a real phone: the test phones are 32-bit, and the
  emulators cannot install the guest (proot's `apt` fails with ENOSYS on
  x86_64). That includes the refusal while the phone's desktop has the session.
- Wi-Fi measured end to end. The paced path is the one measured on a cable
  before direct existed; the link's side of it is the desktop stream's.
- Why system_server is sometimes 2 points busier with this window than with
  scrcpy's.
- Android 12's soft keyboard on the phone's screen when a field focuses: the
  policy that hides it needs a trusted display, which the shell cannot make
  there.
- Windows of this app's own on a Linux computer (it gets scrcpy's).
- The compositor's refresh of latency: presenting to a hardware overlay.
