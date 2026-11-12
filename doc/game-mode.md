# Game mode: controllers, and keys and a mouse for touch-only games

A phone game in a window on the computer is played with the computer's own
things. A **game controller** plugged into the computer, or paired with it,
is seen by the game as an ordinary controller, as if it were plugged into
the phone: the phone makes a gamepad of its own for it, and the window in
front owns it. For a game that has only buttons on its screen, **keyboard
and mouse controls** put a few fingers on that screen: a stick on W A S D, a
spot pressed while a key is held, an **Aim** — an area the mouse drags a
finger across to look around. They are set up per game, in an editor that shows the game as a
still with the controls on top, and kept in a small file that can be sent to
someone.

It is for people who play a phone game at a desk. The one-line how: the
phone's app server, which already shows each app in its own window
(doc/apps-on-pc.md), makes UHID gamepads tied to the window's display and
injects a multi-touch stream there; the computer reads controllers with
`gilrs` and turns keys and the mouse into fingers with an engine of its own.

This record exists for the reason the others do: several decisions below look
arbitrary until you know what was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The phone's gamepads | `openandroiddex-wmd/…/AppGamepads.java` | `/dev/uhid` devices, their tie to a window's display, who owns them, their going |
| Fingers | `AppInput.java` (`touches`) | one multi-touch stream per window's display; the mouse kept out meanwhile |
| The wire | `Wire.java`, `apps/wire.rs` | `0x24` fingers, `0x25` a controller's state |
| Reading controllers | `apps/gamepad.rs` | `gilrs`, slots, the window in front, `feed` (the hook's way in) |
| The engine | `apps/game/engine.rs` | keys and mouse → fingers; pure, no clock, no threads |
| Layouts | `apps/game/layout.rs` | the format, anchors, validation, the starter, storage |
| Key names | `apps/game/keys.rs` | one name per physical key, from a Mac's key code and from a Windows scan code |
| A window's session | `apps/game/mod.rs` | on/off, the engine's thread, what the native window asks of it (`Shell`) |
| The editor | `apps/game/editor.rs`, `src/features/game/*`, `capabilities/game-controls.json` | the window over the game, its commands, the still |
| The debug hook | `apps/game/hook.rs` (debug builds) | events from a file into the same pipeline |
| A window's still | `apps/link.rs` (`still`, `tap`) | the stream's last configuration; the next key frame on request |
| The native windows | `apps/mac/mod.rs`, `apps/win/mod.rs` | keys and mouse handed to the session, the pointer captured, two menu items |
| The desktop | `scrcpy.rs` | `--gamepad=uhid` and one environment variable |
| The tests | `test/game-test.mjs`, the Rust unit tests | the phone's half over the wire; the engine, the format, the keys |

## A controller is the phone's own

A controller's buttons could have been injected as key events
(`KEYCODE_BUTTON_A`…), as the window injects the keyboard. A game would then
see buttons from no device at all: no `InputDevice` with `SOURCE_GAMEPAD`,
so no controller in its settings, no stick axes, and most engines (Unity's
input system among them) ask for a joystick device before they read anything.

So the phone makes a real one. `/dev/uhid` lets a process describe a HID
device to the kernel and write its input reports; the shell's uid is in the
`uhid` group on every phone tried. The device says it is Microsoft's Xbox 360
pad (bus virtual, vendor `045e`, product `028e`): what scrcpy's gamepads say
too, and a name every Android ships a key layout for
(`Vendor_045e_Product_028e.kl`), so A, B, the shoulders, the sticks and the
triggers come out as Android's own names. `dumpsys input` lists it with
classes `KEYBOARD | GAMEPAD | JOYSTICK` and a controller number.

The descriptor is written in `AppGamepads.java` from the HID usage tables: 15
buttons, a hat, two 16-bit sticks, two 8-bit triggers — one 13-byte report:

| Bytes | What |
| --- | --- |
| 0–1 | buttons: `A 0x0001  B 0x0002  X 0x0008  Y 0x0010  L1 0x0040  R1 0x0080  Back 0x0400  Start 0x0800  Guide 0x1000  L3 0x2000  R3 0x4000` |
| 2 | hat: 0 centred, 1 up, then clockwise to 8 up-left |
| 3–4, 5–6 | left stick x, y (i16; right and down positive) |
| 7–8, 9–10 | right stick x, y |
| 11, 12 | left trigger, right trigger (0–255) |

The planner's probe proved it on Android 17 (one report with everything held
gave every `BTN_` and `ABS_` the layout names, in `getevent -l`); the same
report is `PadState::report`'s test. Bits 0x0004, 0x0020, 0x0100 and 0x0200
are buttons the layout has no name for, and are never set.

The computer sends the whole state, not changes: `0x25`, a slot, the report.
The phone writes a report only when it differs from the last. Rumble, lights
and motion are not carried; what the kernel says back is read and dropped.

On the computer, `gilrs` reads the controllers: Windows.Gaming.Input on
Windows, IOKit HID on a Mac, with hot-plug and SDL's controller database, so
a PlayStation or Switch pad maps as an Xbox one does. The reader is made with
the first phone window, without force feedback (nothing here rumbles, and its
thread has no way to end). **On a Mac** it is kept for the app's life — gilrs's
threads there and its HID manager have no way to end either — reading on and
sending nothing while no phone window is open. **On Windows** it is dropped as
the last phone window closes: its backend stops with it, and while it lives it
polls every 8 ms, which nobody without a phone window open should pay for. Up
to four, in the order they connected; a fifth waits, and takes the slot the
first of the four to go lets go of (`Seats`).

Only a **game controller** is used, as SDL's game controllers (scrcpy's) are
the only ones it uses: a device gilrs maps from SDL's controller database —
on a Mac, nothing else — or, on Windows, one Windows itself reads as a
gamepad (Windows.Gaming.Input's `Gamepad`, Xbox-shaped; gilrs gives it an
all-zero id and Windows' layout). Anything else — a 3D mouse, a keyboard that
also says it is a joystick, a pad the database does not know — is logged and
not used: gilrs 0.11 gives every device it does not know a default layout
and calls it "driver-mapped", never `MappingSource::None`, so the database is
the test. A controller's state goes to the phone window in front only once
the controller has been **used** while that window was in front — a button,
the d-pad, a trigger past 32 of 255, a stick past 8,000 of 32,767 (`wakes`,
`goes_to`) — so one that is only plugged in makes no pad on the phone, one
used for a game on the computer makes none in the phone window that comes in
front after, and on a phone without the tie it moves nobody's focus. gilrs's own filters (the d-pad as buttons, jitter, the dead
zone) run in the reader rather than in gilrs, whose loop waits past its
timeout for as long as they drop events: a stick that jitters at rest would
hold the window in front from being sent what is held. A reader that cannot
be made is tried again a minute later, said once; the thread never loops
without a wait, and a reader that answers at once with nothing three times
running — its source gone, as on a Mac whose HID manager would not open — is
dropped and made again on the same minute. gilrs counts a stick's up as
positive, HID as negative: Y is turned over (`axis`). Rejected: GameController
and XInput by hand (two readers written blind, and XInput leaves PlayStation
and Switch pads out), SDL (a C library to build and ship three times), and a
hidden scrcpy to read them (scrcpy reads input only through a window of its
own).

## Whose controller it is

A gamepad's input goes, by default, to the display that has Android's focus —
which may be the phone's own screen in its owner's hand, or another window's.
Android 15 has a way to tie an input device to a display:
`IInputManager.addUniqueIdAssociationByPort(port, displayUniqueId)`, where
the "port" is the device's `phys` and the display is named by its unique id.
The shell may call it (it holds `ASSOCIATE_INPUT_DEVICE_TO_DISPLAY`). Each pad
is made with the `phys` `oadx-pad:<server>:<slot>`, and tied to the display
of the window the computer says owns it.

What the probes found, on the S25 Ultra (Android 17) by the planner, and
again on the Galaxy S24 (Android 15, One UI 7) before anything was built:

- **The tie decides where the input goes.** Files on display 14 and the
  calculator on display 15, Android's focus on 15, the pad tied to 14: B
  closed **Files**, and the calculator stayed. The dispatcher's own line says
  so: `Delivering key to (…): B … d=14`.
- Motion arrives as well: tied to the calculator's display, the stick and
  the hat moved its focus from view to view (`calc_edt_formula →
  calc_tv_result → calc_handle_btn_history → …`).
- A live pad is moved to another display by one more call: no need to make it
  again.
- **A pad tied to a display that does not exist is disabled by Android**
  (`Enabled: false`; the input reader: "should be associated with display …
  but the corresponding viewport cannot be found"). Nothing it does reaches
  any app. The first probe run on Android 17 split a display's unique id on
  its spaces — they look like `virtual:com.android.shell,2000,Open Android
  DeX app,8` — tied the pad to nowhere by mistake, and found this. It is now
  the rule: each pad is born tied to `oadx:nowhere` and only then made, so it
  is never free for a moment; and a window whose display goes leaves its pad
  disabled by Android itself.
- With no tie the pad follows the focused display.
- One UI attaches a `JoystickToTouch Input Mapper` of its own to any gamepad.
  It did nothing in either probe. If a pad ever starts tapping the screen on
  a Samsung phone, that is where to look.

So a report on a window's connection makes the pad if there is none (tied to
nowhere, made, tied to that window's display), moves it there if another
window had it (a neutral report first, so nothing stays held in the window it
leaves), and writes the report. Before the first report goes, the phone waits
until Android has the new pad enabled and — where `InputDevice` says which
display a device is tied to — tied to that window's: a report written sooner
is dropped, or lands where the pad was. That wait was 32–51 ms on the S24; it
gives up after 500 ms (`READY_MS`), as the window's input thread waits with
it. A pad made but not finished — anything thrown after `UHID_CREATE2` — is
destroyed again and the next report tries anew; only a phone that will not
open `/dev/uhid` or make a pad at all is refused for the run. Each pad's
reading thread polls with a timeout, so it ends with its pad.

The window's `blur`, and its connection going, write a neutral report: the
tie stays, and the computer sends nothing more until a window has the front.
A report that comes on a window **between its blur and its next focus** is
dropped: one that crossed the blur on the wire would hold the pad in a window
behind. The computer's side keeps the same order: a window says `focus` to
the phone before it counts as the one in front (`front` on a Mac, set after
the focus goes; on Windows a flag beside the foreground window, which Windows
changes before the window hears of it), so a report never reaches the phone
ahead of its window's focus. The window's end destroys its pads and then
removes their ties (in that order: a pad is never free).
`{"t":"gamepad","slot":n,"on":false}` on the control connection — the
computer's controller unplugged — destroys that slot's; a `gamepad` message
saying anything else does nothing. The server's stop destroys them all.

A server killed outright loses its pads with its file descriptors (the
kernel removes the devices), but its ties would stay in system_server until
the phone restarts. So the server writes `/data/local/tmp/oadx-pads-<server>`
as it makes its first pad — its pid, then each port — deletes it as it
stops, and at start takes back the ties named in any such file whose pid is
no longer an app server (`/proc/<pid>/cmdline`), then the file
(`LauncherOverlay`'s pattern). The test kills one with `kill -9` and watches
the next take its tie back.

**Where the phone will not tie** (no such call for the shell, or a
`SecurityException`; below Android 15 that is expected), the phone remembers
it for the run, logs once, and goes by focus as the keyboard does: before
the pad is made, and at most once a second while its reports come, the
window's display is made the focused one (`AppTasks.focus` of its top task).
On such a phone a touch on the phone's own screen takes the pad there until
the next report a second later. No phone without the tie was at hand: this
route is written, not run.

**Where `/dev/uhid` cannot be opened**, the window is told once —
`{"t":"gamepad","slot":n,"ok":false,"message":…}` — and says under its title
bar: "Your phone doesn't let a computer add a game controller."

## On the desktop

The phone's desktop is scrcpy's window, and scrcpy reads controllers itself:
`--gamepad=uhid` makes a UHID gamepad on the phone for each, tied to the
desktop's display from Android 15. scrcpy's server ends the whole session
when a gamepad's `/dev/uhid` will not open (`UhidManager.open` throws and the
controller stops the server), and makes the pad when a controller appears,
which has nothing to do with when the display is made. So the flag is given
only where it cannot do that (`wants_gamepad`): a session with **a display of
its own** — the desktop, and a scrcpy app window (a Linux computer's phone
apps) — never a phone-screen mirror or the sound's companion; on a phone
whose shell the session's preparation saw open `/dev/uhid` (one more line in
the round trip that already asks the phone its version and whether it takes
injected input: `(: <> /dev/uhid) && echo oadx-uhid-ok`, which opens and
closes it and makes nothing); and never where `stream-config.json` says
`gamepad: off`, which is read for every kind of session. A session whose
output names a uhid failure as it ends (`UhidManager`, or `/dev/uhid` with an
exception) comes back without the flag, and so does every later session on
that phone in this run, whatever its preparation sees (`remember_uhid`: an
open that works is not a gamepad that does); a session that will not start
at all drops it with the other optional options. No Settings row: that would
be the launcher's, with eight translations, and a way out is all it needs to
be for now.

scrcpy asks SDL to read controllers in the background
(`SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS`, sdl_hints.c). A controller used
for a game on the computer would then press A and Back on the phone's desktop
behind it. With `SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS=0` in scrcpy's
environment SDL refuses the hint, scrcpy says `WARN: Could not allow joystick
background events` — the line that proves it — and the controller speaks only
while the desktop's window is in front. That is SDL's documented behaviour;
it was not tried with a controller here.

scrcpy makes a pad for each controller as it starts or as one is plugged in,
whether its window is in front or not. With the desktop and an app's window
both open, the phone has two pads for one controller, each speaking only
while its own window is in front. With `--mouse=uhid` there is no clash:
scrcpy numbers its UHID devices keyboard, mouse, then gamepads.

A Linux computer gets controllers in both places scrcpy is the window: the
desktop, and an Android app's window, each with a display of its own. That is
all of Game mode there.

## Fingers, not a mouse

The computer sends state, as it does for the mouse: `0x24`, a count, then
`id, x, y` for each finger down now. The phone turns the difference into
Android's sequence — the fingers that went first (`POINTER_UP`, or `UP` for
the last), then the new ones (`DOWN` for the first, else `POINTER_DOWN`),
then one `MOVE` with every finger if any that stayed moved — as
`SOURCE_TOUCHSCREEN`, `TOOL_TYPE_FINGER`, pressure 1, in ascending pointer
id, on the window's display, asynchronously. A count of 0 lifts everything.
State rather than events makes a lost message harmless and a reconnection
simple: the next state is the truth.

**Pointer ids.** The computer's ids are its own (a control's index + 1, up to
33); Android's are not: its dispatcher refuses a motion event with a pointer
id above 31 or more than 16 pointers, and every event carries every finger,
so one finger past either would have every event refused while the phone's
map moved on. Measured on the S24 (a probe injecting on a window's display):
id 31 taken, id 32 refused, 16 pointers taken, the 17th refused — and
`injectInputEvent` says false when it refuses, even asynchronously. So the
phone gives each finger the **lowest pointer id free**, as a touchscreen's
driver does (scrcpy's `PointersState` too), takes at most 16 from one message,
and the computer never holds more than 10 (`MAX_FINGERS`). An event Android
refuses all the same is said in the server's log ("touch refused on display
…", at most every 10 s), followed by a `CANCEL` of what Android has down —
the finger whose lift was refused included, even the last; a finger whose
going down was refused left out — and the phone forgets its fingers: its map
and Android's agree that nothing is down, and the computer's next state
starts anew. (No refusal could be made to happen on the S24 once ids and
counts are kept to Android's: this path is read, not run.) The test sends twenty fingers with
ids 40 to 59: 16 held at most, all let go, the next finger a new gesture's
first.

**A connection made again** — cable ↔ Wi‑Fi, or the same way back after a
stall — starts game controls clean at both ends. The phone, on the window's
own thread as it turns to the new connection and before it reads anything on
it, lets go of what game controls held there — their fingers, and the
window's controllers' pads — whether or not it saw the last one end: the
computer may attach again while the phone still holds the old socket, and a
lift written into a dead connection would otherwise leave the finger down. An
ordinary mouse press or a key held stays as it is, so a drag in a phone or a
Linux window goes on across a move between Wi‑Fi and the cable as it always
did (the phone lets go of everything only when it sees the computer go). The
computer sends its whole state on the new one: the session's thread sees the
window's connection change (`Link::generation`, one more for each) and sends
the fingers held — none included — within 100 ms; the controllers' pass
treats the window as new and sends each controller's state within 50 ms. The
test attaches a second connection to a window whose first holds a finger and
the hat, and sees both let go, the first closed, and the second's state
taken; and attaches a third while the second holds a mouse press, which
stays down until the third lets it go.

The probe found, on Android 17 and again on the S24: two injected fingers
hold on a window's display (`DOWN`, `MOVE`, `POINTER_DOWN`, `MOVE`, `MOVE`,
`POINTER_UP`, `UP` each accepted in wait-for-finish mode); and a mouse hover
injected between their moves is **refused**, the fingers going on. Every
injected pointer event is given device −1 whatever is passed, so an injected
mouse and injected fingers are one device to the dispatcher. Older Android
keeps one pointer stream per display, and a second source can end the first.
Either way the answer is the same: while the controls are on, the computer
sends no mouse at all, and the phone keeps the two apart as well — any finger
message lets go of a mouse button held (the session sends one, with no
fingers, as the controls come on) and the first finger ends a hover; the mouse's `pointer`,
`scroll` and `leave` do nothing while a finger is down; `leave` sends a hover
exit only if a hover was sent; `releaseAll` (the window's blur, the
connection going) lifts the fingers.

One UI logs each touch it dispatches on a user build — `InputDispatcher:
Delivering touch to (pid): action: 0x105 … d=17` — but not moves: the test
reads the order from those lines.

## The engine

`apps/game/engine.rs` is pure: events in with the time they happened, the
fingers that should be down now out, and when it next needs waking. A
window's session runs it on a thread of its own. Finger 0 is the pointer's (a
left click with the mouse free); control *i* is finger *i*+1. The rules, with
their constants at the top of the file:

- **Stick.** The first direction: a finger down at the stick's centre, then
  out to 0.9 of its radius in 4 steps 8 ms apart. A change of direction: 2
  steps. Opposite keys cancel (W+S is the centre). All four up: lifted where
  it is.
- **Button, Hold.** Down with the key, up with the key, never sooner than
  40 ms after down — a game polls, and a shorter touch may not be seen.
- **Button, Tap.** Down with the key, up 50 ms later whatever the key does; a
  held key taps once.
- **Aim (`look`).** Engaged while its key is held (`While holding a key`) or while the
  mouse is taken (`All the time`). The first movement puts a finger down at
  the area's centre; the movement itself follows a tick later, so the game
  sees a touch begin and then move. Each movement moves it by `delta × speed
  × dpi/160` pixels — a point of mouse is a dp of finger at speed 1. Within
  6 % of the area's edge the finger is lifted and put down at the centre one
  tick later, as a thumb would; 150 ms without movement lifts it; disengaged,
  it lifts. Its moves go at most every 8 ms, the mouse's movement in between
  added up. An area is kept to the part of it inside the picture, and never
  thinner than 16 pixels (`fit`): one larger than the picture, which a file
  may hold, held its finger at the picture's edge, never near enough to its
  own edge to be lifted.
- **The mouse taken.** A `While holding a key` look takes it while its key is
  held. An `All the time` look starts free — turning the controls on, the
  window opening with them on, the window coming to the front: none of these
  takes the mouse — and only its key takes it, and gives it back when pressed
  again: the key under Esc, unless its card chose another (an Apple JIS
  keyboard has `1` under Esc and no key that sends that code: there, found by
  `KBGetLayoutType`, the card asks for a key as the look is set to `All the
  time`, says why, and the note as the controls come on says to choose one —
  never to press a key that is not there). Esc always
  gives it back (a held look's too, until its key is pressed again). A left
  click with the mouse free is always a finger, so the game's own menus work;
  a click never takes the mouse.
- **One look moves at a time.** A `While holding a key` look held while an
  `All the time` look has the mouse takes the movement over: the other's
  finger goes up, and comes back once the key is let go. The press that gives
  an `All the time` look's mouse back gives back only that: a look held
  meanwhile goes on (Esc frees it, as ever).
- **Several `All the time` looks.** Each key takes the mouse for its own look
  — from another that had it, whose finger is lifted — and gives it back when
  pressed again; the mouse's movement moves the finger of the look that has
  it, and no other. Looks that **share a key** — both left on the key under
  Esc, or a file that gives two the same — take it **in turn**: the first
  press the first of them, each press after that the next, and after the
  last the mouse is given back (`mouse_keys`, `take_mouse`). That is the
  only rule under which every Aim the editor shows can be reached and
  no key is dead whatever a file says: one look per key would leave a second
  look on the default with no way in, and "unbound" is no way out, since an
  `All the time` look with no key has the key under Esc. Until the outside
  audit the first such look's key was the only one: a second look's own key
  did nothing, and every such look moved its finger at once.
- **Blur** lifts every finger and gives the mouse back. **A new size**
  resolves the positions again; wide ↔ tall swaps to the game's other set of
  controls, or turns them off with a note. A key still held through either,
  or through F8 off and on, is pressed again in the engine that follows
  (`press_again`, from the keys the session was told are held): a held W
  walks on, where it used to do nothing until let go and pressed again.
- At most 10 fingers; an eleventh press is ignored.

Not there, on purpose: swipes and macros (a second grammar; when someone
asks), a d-pad distinct from a stick (a stick over a d-pad works), tilt, and
key hints drawn over the live game (native drawing in two windows; the editor
shows where everything is).

## A window's controls

While they are on, a key a control has is that control's and nothing else —
held, it is one press — and every other key goes to the app as before; to
type in a game's chat, they are turned off first (F8). The keys are
**physical**: a control has `KeyW`, the key in that place, so W A S D are the
same four keys on an AZERTY keyboard. The native windows know a key by its
own number — a Mac's virtual key code, a Windows scan code with its extended
bit — and `game/keys.rs` names it.

Open Android DeX keeps some keys for itself, and no control may have them:
**F8** turns the controls on and off (⌘G too on a Mac), **Esc** gives the
mouse back (that Esc does not reach the game; otherwise Esc is what it was:
out of full screen, else Back), the **key under Esc** takes the mouse for an
`All the time` look that has no key of its own, **F11** is full screen, and
**⌘** and the **Windows key** are the computer's. **F8 and ⌘G are the
controls' only in a window whose app has controls set up** — a file with at
least one control, for either shape — or while they are on there, so they
still turn them off if the file went meanwhile. Everywhere else, Linux apps'
windows included, both keys go to the app as they always did (⌘G as Ctrl+G),
and the editor is reached from More (☰) only; More shows the key beside "Game
controls" only where it is the controls'. ⌘G is the key that types "g" —
Dvorak's and Colemak's G, wherever it is — and only on a layout whose key in
G's place types no Latin letter at all (Cyrillic, Greek, Hebrew) the key in
that place (kVK_ANSI_G): the rule the window's other shortcuts follow (`mac_is_g`). A
Mac sends Shift, Control and Option to a view only as a change of the
modifiers (`flagsChanged:`); which one moved, and whether it is down, are the
event's key code and the modifiers' device bits. ⌘ is never a game key, and
neither is Caps Lock on a Mac (it comes as a change of state, never as a
press and a release: the editor refuses it). On Windows a bound Alt is taken
from `WM_SYSKEYDOWN`; Alt+F4 stays Windows', whatever the controls have, and
so does Alt+Space unless Space is a control's.

**On Windows a key the controls take is never also typed.** The message loop
translates every key into characters before the window sees it, so it does
not translate one the controls take (`takes`, by scan code): no `WM_CHAR` —
key repeats included — no `WM_SYSCHAR` (so Alt+Space with both bound opens no
window menu), no dead key left waiting to change the next letter typed. The
window also swallows any character that follows such a key. `WM_UNICHAR` is
not made by translating keys and is left alone. While the controls are on the
window's input method is put aside (`ImmAssociateContext` with none, given
back as they go off): a Japanese keyboard's key under Esc is 半角/全角, which
would otherwise start a composition over the game.

The mouse, while they are on: movement is the pointer's place (free) or how
far it moved (taken); a left click free is a finger where the pointer is;
the other buttons, the side buttons and the wheel do their control if one
has them, else nothing. On Windows the side buttons take the capture as the
others do, so their release comes to the window wherever the pointer is by
then, and a capture another takes lets go of every button held for the
controls — left, right, middle and the side buttons (`WM_CAPTURECHANGED`;
until the outside audit only the left and the side buttons, so a right or
middle button's control stayed pressed and its later release was dropped). A
drag is never carried out of the window, and files dropped on the picture go
to Downloads. The phone as touchpad is the computer's pointer: a tap on it is
a finger where the pointer is.

**Taking the mouse**, on a Mac: only for the window that has the keyboard, in
the app that is active — a right press reaches a window behind without making
it key, and there Esc could not reach it; asked anywhere else, the controls
let go instead. The pointer is put in the middle of the picture,
`CGAssociateMouseAndMouseCursorPosition(false)` holds it still while the
mouse moves, `NSCursor::hide`, and the movement comes as `NSEvent`'s deltas.
Given back exactly once (hiding is counted): the pointer put back where it
was, then joined to the mouse again, then shown — the warp first, as SDL and
GLFW do, since the other order leaves the pointer still for a moment. On
Windows, likewise only for the window in front; `SetCapture`, `ClipCursor`
to the picture, `ShowCursor(false)`, and after each move the distance from
the picture's middle (divided by the scale, so a point is a point) and the
pointer put back there, the move that makes ignored. Raw input would be finer
on Windows; it is one registration for the whole process, and cannot be tried
here. **Every way it comes back**: Esc; the look's key again; the window
losing the keyboard (⌘Tab, Alt+Tab, the Windows key, a click elsewhere,
minimising); this app no longer active (a Mac); the screen locking or the
computer going to sleep; the window's connection going ("Reconnecting…": the
controls let go of everything); the window closing; on Windows another taking
the capture (a menu); the controls' thread not stopping in time (the session
lifts the fingers and gives the mouse back itself). Either system gives the
pointer back when the process dies.

**When a key's release never comes.** A control's finger is let go by its
key's release, the window losing the keyboard, or the controls going off; a
release can go elsewhere. Three cases, and what covers each:

| Case | Mac | Windows |
| --- | --- | --- |
| The screen locks (⌃⌘Q, Win+L) with a key held; or the computer sleeps | the session's notifications (`com.apple.screenIsLocked`, `NSWorkspaceWillSleepNotification`, screens sleeping, the session or this app going inactive) are the window losing the keyboard: everything let go on the phone, controllers' pads included, the mouse given back; unlocked or awake and still key, the window is in front again — but never while the screen is locked (a flag from the lock until the unlock): after a wake the window is key and the app active behind the lock screen | `WM_WTSSESSION_CHANGE` (`WTS_SESSION_LOCK`, registered per window) and `WM_POWERBROADCAST` (`PBT_APMSUSPEND`) the same, a flag from the lock until the unlock likewise; after the unlock, or a wake with the screen not locked, the window is in front again once Windows has it as the foreground window — looked at every 250 ms for five seconds, as Windows may hand it over a moment later and send no `WM_ACTIVATE` |
| The window's own menu (☰, the speaker) open, a key let go meanwhile | as the menu closes, each held key is checked against the keyboard's own state and let go if it is up; one the state never showed down is let go too | the same, after `TrackPopupMenuEx` returns |
| Both Shift keys down, one let go (Windows sends no key-up until the last) | — | the key state of that side: `VK_LSHIFT`, `VK_RSHIFT` (Control and Alt the same), never the shared `VK_SHIFT` |

Behind all three, a few times a second (every 250 ms) while a control's key is
held, the window asks the system whether it is still down —
`CGEventSourceKeyState(kCGEventSourceStateHIDSystemState)` on a Mac,
`GetAsyncKeyState` of the key's own side on Windows — and lets go of one that
is up (`game::released`). A key is let go this way only once the system has
said it is down since it was pressed, so a system that cannot say lets
nothing go. Windows keeps one state per virtual key, and several keys share
one — the arrows and Insert, Delete, Home, End, Page Up, Page Down with the
number pad's keys (theirs with Num Lock off), Enter with the number pad's
Enter — so those are never asked about (`windows_state_vk`): holding ← and
tapping the number pad's 4 would let go of a stick. Only their own release, a
blur, or a menu closing lets them go. The timer starts with the first key
held, not again with each: started over, it would never fire while keys keep
coming. Controllers need none of this: gilrs reads the hardware, and the
phone's pads are let go with the window's blur.

On: with no controls set up for this game, More's "Game controls" opens the
editor; set up for the other shape only, a note says to make the window that
shape or set them up again. Done with every control removed turns the old
ones off. The notes, each fading: "Game controls on. Press F8 to turn them
off." (⌘G on a Mac), or with an `All the time` Aim "Game controls on. Press
the key under Esc to aim." — the Aim's own key named instead, if it
has one ("Press Q to aim."), and with several each key once, in their
order ("Press Q or E to aim."); "Game controls off."; "Press Esc to get
your mouse back." the first three times in a run the mouse is taken. A game
whose controls were on when its window closed has them on again with its next
window's first picture.

A game that turns landscape inside a tall window is letterboxed: a window's
display never rotates (`AppServer.createDisplay`). Its controls are wherever
the editor's still showed them. The fix is the person's — More › Tablet size,
or full screen. The phone's `task` message says the app's orientation, which
could drive that one day.

## The editor

Three places were weighed:

| Where | For | Against |
| --- | --- | --- |
| Drawn natively over the live picture | in place | two UIs by hand: AppKit views on a Mac, Direct2D with its own hit-testing on Windows, where nothing can be run from here (the Windows title bar alone is 1,700 lines of renderer) |
| A transparent webview glued over the native window | in place, one UI | transparency needs `macOSPrivateApi`, window parenting on two systems, focus and full-screen cases; a glued window that lags reads as broken |
| **A webview window over a still** | one UI in React with the app's own kit, previewable without a phone, the same on Windows; a frozen frame is easier to aim at than a moving one; while it is open the game's window is not in front, so nothing presses the game by accident | not literally inside the game's window |

The third won. `game::Session::set_up` opens it on a thread of its own (a
window built from the main thread or a window's deadlocks on Windows), titled
"Controls for <App>", placed so its picture lies over the game's (a Mac
counts up from the bottom of the main screen: turned over), never narrower
than its toolbar, a full-screen game's shown smaller at the same shape. Its
picture is a **still**: the link keeps the stream's last configuration (SPS
and PPS), asks the phone for a key frame (`{"t":"keyframe"}`, which existed),
and hands both to the page, which decodes them with WebCodecs as the USB
desktop's stream does and draws the app's part on a canvas. A webview with no
decoder, or a phone that sends nothing in 2 s, gets the line "Can't show the
game here. Place the controls by eye, then test them in the game."

A control is dragged; as it is let go the nearest of nine anchors is chosen
for it, and never shown. A click on it opens a small card beside it: a
stick's four keys and size, a button's key and Hold or Tap, an Aim's
"While holding a key" or "All the time", its key — for "All the time" the key
that takes the mouse, "Key under Esc" until another is chosen — its speed; a
chip clicked waits for "a key or mouse button…" (the wheel too); a key
another control has moves to this one. Two "All the time" areas on one key —
which the default does by itself, as a key moved off one leaves it on the key
under Esc — are allowed, as the engine takes them in turn, and said: the card
says "Shared with another Aim. Each press of Q moves to the next one.", and
the area's label how many presses reach it ("Press Q
twice", "The key under Esc, twice"). A key the game would never feel is
refused in a sentence under the card: "Caps Lock can't be held down on a Mac.
Pick another key."; "Left click already taps the game. Pick another key or
button." (a stick's or an Aim's); "The wheel can't be held down. Pick a key
or mouse button." (the same two); "Open Android DeX uses this key. Pick
another.". An Aim's corner handle never
makes it larger than the picture, nor smaller than the smallest a file may
hold, even at the picture's edge. The first time the editor opens for a game,
before anything else, it says:

> **Check the game's rules first** — Some online games don't allow a
> keyboard and mouse. Your account could be banned. *Cancel* / *Set up
> controls*

"Set up controls" is remembered for that game on this computer, and is the
only way a layout can come to exist for it (import lives inside the editor),
so nobody turns controls on for a game without having read it. Done stores
them, turns them on and brings the game forward; closing the window any
other way changes nothing. Done and Export store them under the shape the
editor opened with — the shape of the still they were placed on — whatever
shape the game's window is by then, and keep the other shape's; Import
takes a file's set for that same shape (until the outside audit it took the
game window's shape at that moment, so a window turned meanwhile had the
other set placed on this still and stored under this shape). The editor's
commands answer only the editor of a window that is open, and Show opens only
a file that editor exported. Its window's capability is the one call of the
window's own its page makes, closing it (`core:window:allow-close`); the rest
are the app's own commands. Import reads no file larger than 64 KB, and the
names a file carries are shown at most 60 characters long, control
characters taken out.

## Layouts

`<config>/game-controls/<package>.json`, one file per game, written beside
itself and moved over. Inside, one set of controls per shape of the picture:
`wide` (width ≥ height) and `tall`.

A control's place is an **anchor and an offset**: one of nine points of the
picture, and an offset in units of the picture's **shorter side**. A game's
buttons sit a fixed distance from a corner and scale with the screen's
height, so a file follows them from a 16:9 window to a 21:9 one; plain
fractions of width and height would not. The phone's own screen never enters
into it — the game runs on the window's display — so a file is the same for
any window size and any phone.

```json
{
  "format": "open-android-dex.game-controls",
  "v": 1,
  "app": { "pkg": "com.example.game", "name": "Example Game" },
  "shapes": {
    "wide": {
      "controls": [
        { "type": "stick",  "at": { "from": "bottom-left",  "dx": 0.30,  "dy": -0.32 }, "size": 0.14,
          "up": "KeyW", "left": "KeyA", "down": "KeyS", "right": "KeyD" },
        { "type": "button", "at": { "from": "bottom-right", "dx": -0.18, "dy": -0.22 }, "key": "Space", "press": "hold" },
        { "type": "look",   "at": { "from": "right", "dx": -0.40, "dy": -0.12 }, "w": 0.76, "h": 0.6,
          "by": "hold", "key": "MouseRight", "speed": 1.0 }
      ]
    }
  },
  "local": { "on": true, "warned": true }
}
```

`from` is one of `top-left top top-right left center right bottom-left bottom
bottom-right`; the point is `(ax·W + dx·S, ay·H + dy·S)`, `S = min(W, H)`,
inside the picture. `size`, `w`, `h` are in units of `S`. Keys are the W3C
`KeyboardEvent.code` names, which the editor's webview reads straight from
its key events, plus `MouseLeft MouseRight MouseMiddle MouseBack MouseForward
WheelUp WheelDown`; a missing or `null` key is unbound, and so is a kept one,
and so is the left button or the wheel on a stick or an Aim (an Aim's
`key` with `"by": "always"` is the key that takes the mouse; `null`,
the key under Esc). Reading refuses a `v` above 1 ("That file was made by a
newer version of Open Android DeX.") and anything that is not one ("That
file isn't a set of game controls."); skips a control of an unknown type or
place; clamps numbers; and takes at most 32 controls and 64 KB. A file may
come from another computer: a key this one never hands a control is unbound
(`Key::here` — on a Mac the ISO key, which it takes as the key under Esc, and
Caps Lock, which it sends as a change of state; on Windows the number pad's
=), and so is a key an `All the time` Aim takes the mouse with, on any other
control, which would never feel it. The editor shows both as unbound. A package
whose first part is a name Windows keeps for a device (`con`, `nul`, `com1`…)
has no file.

A game's file that is there but does not read — a newer version's, broken,
too large — is **never written over**: before anything is stored for that
game it is moved aside as `<package>.unreadable.json` (`-2`, `-3`… after
that), and the game's window says "Couldn't read this game's old controls.
They were kept as “…”.".

`local` (on, warned) never leaves this computer: **Export** writes the file
without it into Downloads as "<App> controls.json", and **Import** ignores it.
Sharing is sending the file. An import made for another game asks first: "Use
these controls here? These were made for <Other>. The buttons in <App> may be
in other places."

A game with none for its shape starts from a **starter**: on a wide picture a
stick on W A S D at the bottom left, an Aim over the right 45 % held by
the right button — the mouse never vanishes on someone who has not asked for
it — and Space and E at the bottom right; on a tall one the stick and Space.

## A clip

Not built. The plan had a clip of the window's picture as its last and
droppable milestone, and it was dropped: everything else came first.

What it would be: each window's link already receives the app's picture as
H.264 with the phone's own timestamps, and writing that into an MP4 (`ftyp`,
a streamed `mdat`, a `moov` with one `avc1` track, `avcC` from the
configuration) costs the phone nothing. **Sound** is the hard half: the
phone's sound never passes through this app — scrcpy's companion receives
and plays it — and recording that companion separately would give a second
file whose clock is its own, and a gap in what the person hears each time
recording starts and stops. A clip whose sound drifts is worse than one that
says it has none. Sound would take the app server capturing the phone's
playback itself, as scrcpy's server does (an `AudioRecord` on the playback
mix, AAC from the phone's encoder), stamped on the same clock as the picture,
sent on the window's connection and written beside the video, and a decision
on how that shares the phone's playback capture with the companion on each
Android version. A feature of its own.

## The wire

On a window's connection, computer → phone:

| Type | Body | Meaning |
| --- | --- | --- |
| `0x24` | count u8, then count × (id u8, x u16, y u16) | exactly these fingers are down, here, in display pixels |
| `0x25` | slot u8, 13-byte report | the controller in this slot is in this state; the window that says so owns it |

JSON:

| Message | Way | On | Meaning |
| --- | --- | --- | --- |
| `{"t":"gamepad","slot":n,"on":false}` | computer → phone | control | that controller is gone |
| `{"t":"gamepad","slot":n,"ok":false,"message":…}` | phone → computer | the window's | the phone could not make it |

Both binary types count as someone using the window for the task watcher. An
older dex ignores both, so a new computer with an old server is merely
without them; the dex is pushed with every server start anyway.

## Verified, and not yet

**The probes, before anything was built.** The planner's on the S25 Ultra
(Android 17, One UI 9), 2026-11-11; the same probe again on the Galaxy S24
(SM-S921B, Android 15, One UI 7, on its cable) the same night, with a finger
step added and the calculator's display given the focus by a tap: the shell
opens `/dev/uhid`; the pad is an Xbox 360 pad to Android; the tie call exists
and is the shell's; tied to Files' display while the calculator's had the
focus, B closed Files and the calculator stayed; the stick and hat moved the
calculator's focus; a pad tied to a display that has gone is disabled; two
injected fingers hold, and a hover among them is refused.

**The phone's half**, `node test/game-test.mjs RFCY90YWS1P`, on the S24:
**28 passed, 0 failed**, on 2026-11-11 and again on the final sources on
2026-11-12 (two runs fell one short, both the test's own fault: its
expectation of the order two fingers are lifted in, and a clock read to the
whole second, which let an earlier tap into a later check; both fixed). What it checks is in
test/README.md; the numbers it printed: the pad ready 32–36 ms after the
first report; the three fingers `0x0 0x105 0x205 0x106 0x6 0x1`; 7 held and 8
tapped `0x0 0x105 0x106 0x1`, and uiautomator read the formula as
"Calculator input field 87"; a mouse move among fingers `0x0 0x1` and no
refusal; the hat moving the calculator's focus `calc_edt_formula →
calc_handle_btn_history`; the killed server's tie taken back by the next
("took back 1 tie(s)"). The phone was left as it was found: no pad, no tie,
no display of ours, no forward, the windows' tasks out of its recent apps.

**In the app on a Mac** (macOS 26, `tauri dev`, the debug build's hook), with
the S24 on its cable, 2026-11-12. The Mac's screen was locked for the whole
run, so this is everything after the system's keys and mouse: the hook's
lines go into the session as the window's handlers would, and a phone window
stands in for the one in front. The calculator in its window (display 34,
664×1476 at 320 dpi) with a file of controls put in place by hand — Hold
buttons on its 7 (J) and 8 (K), a stick, an Aim: turned on, "controls
on (4 of them, tall)"; J down, K down, K up, J up as One UI's `0x0 0x105
0x106 0x1` on display 34, and the formula "87"; J held as they were turned
off, its finger lifted (`0x0 0x1`); the right button taking the mouse and
giving it back ("the mouse is taken for looking around" / "given back");
eight mouse moves 25 ms apart as one touch, thirty as three (the area's edge
lifting it twice). A controller's state, as gilrs would hand it over: a pad
made and tied to display 34 within 51 ms, the hat moving the calculator's
focus, A opening its converter, B coming back, B again closing it — the
window gone, "game controller 0 gone", the reader stopped with no phone window
open (since the second review it reads on and sends nothing), nothing of ours
left on the phone. With two windows, the pad moved to
the newer as it came in front. The desktop's scrcpy started with
`--gamepad=uhid` and said `WARN: Could not allow joystick background events`;
the reader said "0 connected". The editor opened as "Controls for Clock",
smaller than its picture on that small screen; a locked screen draws no web
content, so what was in it was not seen there.

**The computer's half without a phone**: 255 Rust tests (this feature's: the
engine 13, layouts 8, key names 3, the report, hat and axes 3, the wire 1,
the editor's placement 1, the notes 1, scrcpy's options 1); the page's
TypeScript (`tsc --noEmit`); the whole app type-checked for Windows from the
Mac (stand-in C tools; nothing linked). The editor was looked at in a browser
with its commands faked, a still made by the browser's own H.264 encoder
going through the same decoding: the warning, each card, a key chip waiting,
a key given, the key under Esc refused, a phone-shaped game, nothing yet, no
picture, a drag re-anchored, Done.

**After a second review** (its findings fixed, 2026-11-12, the S24 on its
cable, not run over Wi‑Fi). Android's limits measured on the S24 by a probe
on a window's display: pointer id 31 taken, 32 refused; 16 pointers taken,
the 17th refused; `injectInputEvent` false for each refusal. The phone's half
**37 passed, 0 failed** (one run before fell on the test's own count of the
twenty fingers — it missed `0x5` and `0x6`, a pointer index of 0 — fixed): the
twenty fingers `0x0 0x105 … 0xf05 0x6 0x6 0x6 0x5 0x0 0x105 0x206 ×13 0x6 0x1
0x1` (16 at most, the three that went replaced by the lowest ids free; fingers
over two of the calculator's windows reach each as a stream of its own),
nothing refused, the next finger `0x0 0x1`; the second connection: "attached",
the hat centred and the finger up (`0x0 0x1`), the first closed, the second's
hat and tap taken; a report after the blur dropped (the hat stayed centred);
the pad ready in 45 ms. In the app (the hook, the screen locked as before),
the calculator on display 60: J held, K tapped, `0x0 0x105 0x106 0x1`, the
formula "87"; J held and the window's connection made again over the cable
(the hook's `reconnect`): `0x0 0x1 0x0 0x1` — the phone letting go as it took
the new connection ("the computer is back, on a cable"), the computer sending
J's finger again, J's release — and two neutral reports made no pad, the hat
made one (ready in 35 ms), still held after the connection was made again,
centred on release. The editor's page, with its capability down to
`core:window:allow-close`, asked for its game ("the editor's page asked for
the game (tall picture, its controls)"). The desktop's session preparation
printed `oadx-uhid-ok`, said "game controllers on this desktop: yes", and
gave `--gamepad=uhid` to the desktop's scrcpy only, not to the sound's. The
app ended by a real Quit; the phone was left with no server, display or pad,
Wireless debugging off, the test's calculator task out of recents. 271 Rust
tests, `tsc` clean, the Windows check clean; the editor preview showed "All
the time" with "Key under Esc", Q chosen for it ("Press Q to aim with the mouse…",
the area's label "Press Q"), the key under Esc pressed for it going back to
the default, a stick refused the left button in a sentence, and Done sending
`shape: "wide"`.

**After the review's second look** (2026-11-12, the S24 on its cable): the
phone's half **38 passed, 0 failed**, the new case a mouse press held across
a connection made again (`0x0` while the third connection attached, `0x0 0x1`
only after its own release); in the app the calculator on display 71: "87"
with `0x0 0x105 0x106 0x1`, J held across the hook's `reconnect` `0x0 0x1 0x0
0x1`, two neutral controller reports no pad, the hat one; the session's line
now "game controllers in this session: yes". 275 Rust tests (the shared
virtual keys, ⌘G's rule, a controller going only to the window it was used
in, the shortcut while on, a uhid failure kept for the run, a scrcpy app
window with controllers); `tsc` and the Windows check clean. Ended by a real
Quit; the phone left as found.

**After an outside audit** (2026-11-12), which came after the two reviews and
found four more, each fixed:

1. A second `All the time` look's key did nothing: only the first such look's
   key took the mouse, and one flag drove every such look, so all of them
   moved their fingers at once. Now each look has its own (above, "Several
   `All the time` looks"), every such key is the controls' (`bound`), and the
   note names each.
2. On Windows a capture another took (`WM_CAPTURECHANGED`) let go only of the
   left button's control before forgetting the buttons, so a right or middle
   button's control stayed pressed and its release was dropped. Now every
   held button is let go first.
3. Import took the game window's shape at that moment; it takes the editor's,
   as Done and Export do.
4. In Send to computer, the reservation of a name in Downloads
   (doc/send-to-computer.md, "Verified, and not yet").

Run for them: 336 Rust tests — the engine's two looks on Q and E (Q takes for
the first only, E moves it to the second, E again gives it back, Esc gives it
back, one finger moving), two looks sharing the key under Esc taken in turn,
the notes naming each key, a file's two looks both reached, Import's shape —
`tsc`, the Windows check, `npm run apk`. **Not run**: none of it in the app
or on the phone; the editor's new sentence and labels not looked at in the
preview; the Windows capture fix is read and type-checked only, as everything
on Windows is.

On the phone after the audit's fixes: the game test **38 passed, 0 failed**,
and a combined run 18 of 18. **After a final review** (2026-11-12): a file's
keys this computer never gives, and a key an `All the time` look takes the
mouse with on another control, are unbound as it is read (above, "Layouts");
unit-tested, in 345 Rust tests with `tsc` and the Windows check clean; not
run in the app.

**The known issues, fixed** (2026-11-12, after the final review: what "Not
yet" listed as left on purpose). Unit-tested on the Mac, in 358 Rust tests
with `tsc` and the Windows check clean; none run in the app, on the phone or
on Windows:

1. Two looks at once: a held `While holding a key` look now takes the
   movement from the `All the time` look that has the mouse, whose finger
   goes up until the key is let go (`moving`); the press that gives that
   look's mouse back no longer frees the held one (above, "One look moves at
   a time").
2. A key held across F8 off and on, a new size or the other shape: the
   session keeps the keys the window says are held, and each new engine
   presses them again (`press_again`) — the windows' own code is unchanged,
   so it holds on a Mac and on Windows alike.
3. An Apple JIS keyboard (`KBGetLayoutType`): the Aim's card asks for
   a key and says why, and the note never says to press the key under Esc.
4. A fifth controller waits and takes the first slot let go of (`Seats`).
5. A Mac whose screen was locked before the app started knows it from the
   session's own word (`CGSSessionScreenIsLocked`) as the first window opens.
6. A uhid failure is kept per phone (`ro.serialno`, `adb::restore_key`), not
   per adb serial: the same phone over Wi‑Fi is not offered `--gamepad=uhid`
   again in that run.
7. The pointer and a moving window: on a Mac a taken pointer is put back in
   the middle of the picture as the window moves or changes size; on Windows
   it is kept to the part of the picture on the window's screen (`on_screen`),
   so a middle past the screen's edge no longer gives a move that never ends.
8. Windows' AltGr: the left Ctrl it sends, followed at the same time by
   right Alt (`altgr_ctrl`, as GLFW and SDL tell them apart), no longer fires
   a control; and the window's ✕ gives a taken mouse back itself, not through
   Windows deactivating the window as it goes. Both type-checked only.

**On a real Mac, the S24 and two real games** (2026-11-12 afternoon, the
Mac's screen unlocked; keys posted to the app, mouse events at the HID level,
the app made active first). An earlier try had the key under Esc take
nothing: the calculator's window was not the key window of the active app
then (another app was in front; keys posted to the app still reach its window,
which is why J, K, F8 and ⌘G worked). With the app active it took the mouse
at once, after F8 and ⌘G too. The capture refused there is now a line in the
log ("the mouse is not taken: this window is not the one in use").

- Looking around: the key under Esc took the mouse — the pointer hidden and
  held in the middle of the picture through 40 HID moves — and gave it back
  where it was on Esc and on a third press; taken 85–90 ms after the key,
  given back 64–90 ms after Esc or the button's release. One UI logs no move
  (`0x2`) at all, so the finger was watched with the phone's Pointer location:
  its trail started at the first look's centre and moved by the mouse (dX 36,
  dY 26 for 12 moves of 3, 2); pressed again, the trail started at the second
  look's centre; the right button's `While holding a key` look, from its own
  centre, given back at the press point on release.
- Ludo Wala, offline against two bots: the editor opened over the live board
  (WKWebView decoded the still); Space on the dice cup rolled the dice in the
  game, one touch each; F8 off and Space reached the game as a key, no touch;
  F8 on again. A controller made in 75 ms and its keys reached the game's
  window, which did nothing with them.
- Kanche, its practice yard: a stick (W A S D) on its aim pad turned the view;
  a `While holding a key` look on the right button over its pull slider pulled
  and flicked — the toss made ("You won the toss"), then a shot ("No marbles
  knocked out"). F8 off gave D back to the game as a key. A controller made in
  49 ms: B opened the game's own "Leave the match?", A and START changed its
  camera; the sticks and triggers did nothing there.
- Cable ↔ Wi‑Fi with controls on: with the S24 on both, the cable's transport
  dropped (`adb reconnect`), the calculator's window came back over Wi‑Fi on a
  new display 842 ms later with its controls still on (J: one touch there).
  The phone's half over the S24's Wi‑Fi serial, before the merge: 37 passed,
  0 failed (one check not made: uiautomator read no formula). On Wi‑Fi alone (a second adb server that leaves USB alone): the window, its
  controls (four K presses, four touches), the desktop, and since a fix its
  sound — the sound's scrcpy had lost the race with the desktop's for a port
  and was never tried again; it now waits for the desktop's display.
- After the merge with Send's fixes, the S24 on its cable: the phone's half
  **38 passed, 0 failed**; the combined run in the app 18 of 18 on its second
  try (the first put J's 7 before "87": the calculator's cursor after the
  share's card, the touch itself went); 359 Rust tests, `tsc` and the Windows
  check clean.

Not yet:

- **A real controller.** None was at hand (none attached to the Mac on
  2026-11-12: no joystick or gamepad among its HID devices, nothing connected
  over Bluetooth): gilrs reading one on a Mac and on Windows, and PlayStation
  or Switch pads mapping as Xbox ones, are not verified. Everything after the reader — the table, the window in front, the
  wire, the phone's pad — is, through the hook. So is "Game controller
  connected." only as far as the code that says it.
- **The desktop's controller.** scrcpy's `--gamepad=uhid` and the SDL
  variable were seen on its command line and in its log; a controller pressing
  anything on the phone's desktop, and staying quiet while another window is in
  front, were not tried.
- **Windows: type-checked only** (the whole app, from the Mac). Someone at a
  Windows PC should try first: F8 on and off; W A S D and a held key; the
  right button taking the mouse (hidden, kept in the picture, coming back on
  Esc, Alt+Tab, the Windows key, a click on another window, F11); the wheel on
  a control; Alt bound to a control while Alt+F4 still closes; the menu's two
  items; and the editor opening over the window, at the right place on a
  screen scaled to 150 %. Raw input for looking around is left for then.
  Nothing here can run it: the Mac's one Windows VM (Parallels) no longer
  starts, its activation expired, and CI builds only when started by hand and
  runs no tests on any system.
- **A Mac's real keyboard and mouse**, the rest: `flagsChanged:` for a bound
  Shift, and a taken mouse given back on ⌘Tab and on the window's ✕, were not
  seen; the mouse's events were posted at the HID level, not a hand on a mouse.
- **The editor in the app**, the rest: it opened over two games' live
  pictures (WKWebView decoded the still); the drag, Done turning the controls
  on and bringing the game forward, Export into Downloads and Show, Import of
  that file and of a broken one, were looked at only in the browser preview.
- **The tie refused** (below Android 15): the focus route is written, not run.
  No phone without the tie was at hand. Nor a phone that refuses `/dev/uhid`.
- **A game that turns landscape inside a tall window**, and an online game:
  the two played above are tall and were played offline.
- **When a key's release never comes**, none of the three cases was seen with
  a real key: the Mac's `CGEventSourceKeyState` answering for a held key
  (if it never says a key is down, nothing is let go by it, and the lock,
  sleep and menu paths still are), its session notifications, Windows'
  `GetAsyncKeyState`, `WM_WTSSESSION_CHANGE` and `WM_POWERBROADCAST`.
- **Every Windows statement in this record is read, not run**: the side
  buttons' capture, a key's character never typed, Alt+Space, the input
  method put aside, the screen lock and sleep, the foreground flag.
- **A Mac's Input Monitoring prompt**: where a keyboard or a receiver also
  describes itself as a game device, gilrs opening it in this app — and SDL in
  scrcpy's — may raise macOS's "receive keystrokes" prompt by surprise. Not
  seen; no such device was at hand.
- **The controls' thread** wakes ten times a second per window with controls
  on, in front or not, to see the picture's size: cheap, and no fault; not
  changed.
- **Windows' remaining**: the editor is misplaced where two screens have
  different scales. Its sum is Tauri's per-screen conversion, which only a
  Windows PC with two such screens can check; not changed.
- **A Linux computer**: not built for here; its windows are scrcpy's.
