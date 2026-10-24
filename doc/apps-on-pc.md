# The phone's apps in windows of their own

The computer used to show a phone one way: its whole desktop, in scrcpy's
window. Now the app's **Apps** page lists what the phone's own launcher lists,
with the real names and icons, and a click opens that one app in a native
window on the computer: sized like a phone, sharp at the screen's scale, with
the system's own window controls, its picture decoded by the computer's
graphics hardware. No desktop is set up on the phone at all. Closing the
window hands the app back to the phone, where it was. The phone's Linux apps
are on the same page and open the same way.

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
| The window, Windows | `apps/win/mod.rs`, `apps/win/frame.rs`, `apps/keys.rs` | a Win32 window, its title bar, the mouse and keyboard as Android takes them |
| The picture, Windows | `apps/win/render.rs` | decoding, drawing, presenting |
| The window, Mac | `apps/mac/mod.rs`, `apps/mac/video.rs`, `apps/h264.rs`, `apps/keys.rs` (`mac`) | an AppKit window, its input, the display layer macOS decodes into |
| Linux apps | launcher `AppsProvider.java`, `LinuxWindows.java`, `LinuxWindowActivity.java` | the guest's apps and icons, starting one, its X window on an app window's display |
| Sound | `scrcpy.rs` (the audio companion) | the phone's sound here while any of its apps has a window |
| Where the sound plays | `apps/sound.rs`, `AppAudio.java`, `Audio.java`, launcher `AppsProvider.java` (`media.mode`) | the title bar's menu: this computer, both, or one of the phone's own outputs |
| Dropped files | `apps/drops.rs`, `AppDrop.java` | a file dropped on a window: into the phone's Download folder, an APK installed |
| Probe | `src-tauri/examples/app_window.rs` | one window, without the rest of the app (Windows) |

Windows and Mac have windows of this app's own. On a Linux computer a click
opens an Android app in scrcpy's per-app window (`--new-display
--start-app`), and the phone's Linux apps are not listed there: scrcpy can
only start Android packages.

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
is up before anyone clicks), under `app_process` as the shell, from the window
daemon's dex pushed to `/data/local/tmp/oadx-apps.dex` by rename, so a running
server keeps the copy it started with. It is held by the `adb shell` that
started it and ends when that shell's stdin closes: the app quitting, the
cable pulled, adbd restarting. Every app on its displays then goes back to the
phone, behind what it shows. The shell ignores SIGHUP before it becomes the
server (`trap '' HUP`): adbd hangs up a dropped link's processes, and the
signal ended the server on the spot, its apps landing on top of the phone's
screen; ignoring it, the server sees its stdin close, as when the app quits.
That cannot help where adbd itself restarts on a Samsung phone — init then
kills everything in adbd's cgroup — so there the apps still land on top, and
the keeper (below, "Cable or Wi‑Fi") puts them back in their windows if the
phone is back within 30 s; not yet tried on a Samsung phone. Stopping waits for
the windows to hand their apps back (up to three seconds), not a fixed 300 ms. On an A13 it is ready 740–780 ms after its process starts
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
and each window's title bar says which way it went: "Galaxy S25 · USB" or
"· Wi‑Fi", in the subtitle on a Mac, after the phone's name in the bar on
Windows.

While an app window is open, a keeper looks every two seconds:

- A window on Wi‑Fi moves onto the cable once the cable has been in for 10 s.
  A Samsung phone restarts adbd about 5 s after it is plugged in (its MTP
  switch), ending everything adb started; sooner would move onto a link about
  to drop.
- A window whose phone stopped answering, after it had shown its app, opens
  again — the fastest way there is — if the phone is back within 30 s: an adbd
  restart, a cable pulled with Wi‑Fi still there. Later, the phone may be in
  its owner's hand, and its app stays theirs until they open it here again.

Either way the new window opens where the old one was, as big; the old one
closes once its app has moved to the new window's display, or at once if it
had stopped answering. Opening an app whose window is on Wi‑Fi while the cable
is in, or whose phone stopped answering, does the same. A window that loses its
phone says so itself; the page only reports a window that never showed its app.

On the Android 16 emulator, `emulator-5554` as the cable and `adb connect
127.0.0.1:5555` as Wi‑Fi, a window asked for over Wi‑Fi opened over the cable;
`adb -s emulator-5554 reconnect` brought it back over Wi‑Fi 1.4 s after the
drop, its picture 0.74 s later, and onto the cable 10.5 s after the cable
returned, its picture 1.5 s later — in the same place each time.

## The wire

One connection per window, one more for what is the phone's as a whole (the
list, the icons, the clipboard, its sound, the clock), and one per dropped
file. Every message is a type byte, a big-endian u32 length and that many
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
what closing does), `resize`, `focus`/`blur`/`leave`, `clip`, `keyframe`,
`bitrate`, `close`, `ping` one way; `video` (the encoder, its size, the part
of it that is the display, `paced`), `display` (the app's name), `state`
(`closed`, `failed`), `stats` once a second, `pong` the other. The control
connection's: `apps`, `icons`, `linuxApps`, `linuxIcons`, `linuxRun` (answered
by `linuxRan`), `clip` both ways; `audio` — the computer asking, and the phone
saying where its sound can go, after its hello, as outputs come and go, and
after a pin; `audioRoute` (answered by `audioRouted`) and `audioMode`, the
computer's.

A dropped file's connection is the one exception to the framing: its first
message is `put` (the name, the size, the folder it goes in, whether it is an
APK to install), and exactly that many bytes follow it, raw. The phone answers
`got` as they land and `put` once, with how it went.

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
has an AppUserModelID of its own and a relaunch command, `--phone-app <serial>
<package>`: WhatsApp and Gmail are two taskbar buttons, either can be pinned,
and a pinned one starts this app with just that window — no desktop.

A window opens at a current phone's shape, 412 × 915 dp, fitted to 90 % of the
screen's height, at that screen's scale; the phone's display gets the window's
pixels and 160 dpi times the scale, so text is the size it would be on the
phone and one pixel is one pixel. Resizing the window resizes the display (a
new encoder at the new size; the app lays itself out again).

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
app's, under its Dock icon and in its Window menu.

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

More (☰), beside Back in the title bar, is the window's own menu; for now it
holds one choice, Full screen. Full screen, the app's picture is the whole
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

A window that takes over from a full-screen one ("Cable or Wi‑Fi") goes full
screen as well, from where the old one was before it went full screen.

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

Text only. An image copied here would have to become a file in the phone's
shared storage — its gallery — to be a clip there; files go by dropping them
instead. A Linux app's window shares the phone's clipboard through the
launcher, which hands it to the X selection while one of its windows has the
focus (`doc/linux-windows.md`).

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

How a pick went is said for a few seconds under the title bar (a Mac: the
window's subtitle) — "Sound: Computer only" — and the reason it failed, if it
did, goes to the log.

## Dropping files

Files dropped on a window — from Explorer, or from Finder — go to the phone as
a drop on the desktop does: into its Download folder, and an APK installed
instead. A folder brings its files, in folders of the same names (links not
followed, at most 5000 files). Nothing is overwritten and nothing is asked: a
name already there becomes "report (2).pdf", as wherever else files arrive on
this phone (`WebFiles.uniqueIn`), and the window says the name it got. Names
are made safe for shared storage, which may be FAT underneath, and none can
climb out of Download.

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
lands, and that is the progress the window shows under its title bar (a Mac:
its subtitle): "Sending 2 of 4 to your phone… 34%", then "4 files are in
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

Not yet:

- **Full screen on Windows**: compiled, and its layout's tests run, but not
  yet run on a Windows computer. Nowhere yet: a window taking over from a
  full-screen one (it should go full screen too).
- **On a Mac**: an input method's composition with candidates (Pinyin, say —
  only a dead key's marked text has run), the Dock icon a minimised window
  shows, and an Intel Mac, run.
- **The Mac's speaker, drop and clipboard**, which came after that run: the
  menu under its title-bar button, a drop from Finder (a file reference made
  a path), the subtitle's notes, the pasteboard watched.
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
