# The desktop's mouse is a mouse

On the desktop, a left-button drag used to work like a finger. In Gallery or My
Files it scrolled the list. Selecting meant press, hold and then the phone's
selection mode, and a file was picked up only after a long press. On a computer,
the same drag draws a selection box or picks the item up at once. The phone's
own touchpad already behaved like a computer, and so did the computer's app
windows (doc/apps-on-pc.md, "Input"). Only the desktop window did not.

Code: `openandroiddex-scrcpy/desktop-mouse.patch` (scrcpy's server, on the phone)
and `openandroiddex-scrcpy/hover-leave.patch` (scrcpy's client, on the
computer). Status: verified on a Galaxy S25 Ultra and a Galaxy A13 from the
Mac; see the end.

## Why it was a finger

Stock scrcpy sends hover and the right and middle buttons as a mouse
(`SOURCE_MOUSE`, `TOOL_TYPE_MOUSE`). It sends a left press, and everything up to
its release, as a finger (`SOURCE_TOUCHSCREEN`, `TOOL_TYPE_FINGER`). That suits a
phone's screen mirrored on a computer, where apps are built for touch. On a
desktop it is the wrong choice. Android already does what a computer does when it
gets a real mouse's left drag:

- Samsung's lists in Gallery and My Files draw a selection box;
- text is selected;
- an item can be dragged straight away.

Hover was never the problem. It already arrived as a mouse, and the hover in
`dumpsys input` (`CursorStatesByDisplay … hoveringPointers=[Pointer(id=0, MOUSE)]`)
was Gallery's. Gallery's thumbnails draw no hover state in a window like this.
Its tabs and My Files do.

## What the server does now

`Controller.injectTouch`, on a display of its own (`--new-display`, which means
the desktop or an app's own display):

- **The computer's pointer is a mouse whatever is held.** Upstream already
  sends this exact sequence for the right button: DOWN and BUTTON_PRESS for the
  first button, BUTTON_PRESS for each one after, MOVE with the buttons held,
  BUTTON_RELEASE for each, and UP with the last.
- **A hover after the UP**, as Android's own `CursorInputMapper` sends for a
  real mouse. Whatever is under the pointer shows its hover again at once, not
  at the next move.
- **No pressure while nothing is held**, as a real mouse reports. Upstream sent
  1.0 on every hover.

Unchanged: a touchscreen's fingers, and the virtual finger scrcpy adds for
Ctrl/Shift-drag pinch and tilt, stay fingers. A mirror of the phone's own screen
(no `--new-display`) keeps upstream's finger for the left button. `--mouse=uhid`
never reaches this code: there the mouse is a HID device, already a real mouse.

The launcher needed nothing. Its own drags never asked where an event came from:

- `ResizeEdges` takes a press with the primary button or none;
- `LinuxWinView` reads the buttons from a mouse's state;
- the right-click handlers check for `BUTTON_SECONDARY` on `ACTION_BUTTON_PRESS`,
  so the BUTTON_PRESS a left click now also makes opens no menu.

The phone's touchpad has always put real mouse events into the desktop through
the window daemon (`AppInput`), and it uses the same views.

## What the client does now

The pointer leaving the window with nothing held sends a `HOVER_EXIT` at the
last hover point. Without it, the last thing hovered stayed lit until the
pointer came back. The window that loses the pointer tells Android, as a
computer's own windows do. The server takes a `HOVER_EXIT` as a mouse's in every
session.

## Not done

- **Ctrl+click and Shift+click are still scrcpy's pinch and tilt.** The client
  turns a left press with Ctrl or Shift held into a two-finger gesture. On a
  desktop, people expect them to add to a selection or extend it.
- **Mouse events carry no modifier state.** scrcpy's touch message has no meta
  field, so an app that reads Shift or Ctrl off the click itself (most of
  Android) does not see them. Apps that count the modifier key events, which
  scrcpy does send, do.

Both belong together, in the client (no virtual finger on a desktop) and the
server (the meta state of the keys held, put on mouse events).

## Verified, and not yet

On the Galaxy S25 Ultra (Android 17, One UI 9) from this Mac, 2026-11-24:

- Before the change, hover reached apps on the desktop. Through the window
  daemon's `MOUSE` verb, the taskbar's Apps button lit up, and Gallery held the
  hover in `dumpsys input` with nothing drawn on its thumbnails. Through scrcpy
  (a mouse move posted to its process), the launcher held the hover. The user saw
  hover in My Files and on Gallery's tabs, and a left drag that scrolled both
  apps.
- After it, the server and the client were built (macOS arm64, the client
  without USB), the server was pushed by `tauri dev`, and the client ran from
  the app's own copy.

On the Galaxy A13 (Android 12, One UI 4.1) from this Mac, 2026-11-24, the real mouse
through the new build:

- A left drag reaches Android as a mouse: `dumpsys input` during a held drag,
  `source=0x00002002` (SOURCE_MOUSE), where it was a finger before.
- One UI 4.1's own Gallery and My Files take that drag as a scroll, from empty
  space and from an item alike: no selection box, no file picked up. That is those
  apps' answer to a mouse outside Samsung DeX; Android gave them the mouse.
- Hover lights My Files' rows. Leaving the window in one move (no motion inside on
  the way) put the lit row out, which is hover-leave.patch. The row under the
  pointer lit the moment a press was let go, which is the server's hover after UP.
- The desktop came up at 2832x1518 (2x, the Mac's Retina screen) with no encoder
  limit stored for the A13.

On the Galaxy S25 Ultra (Android 17, One UI 9) from this Mac, 2026-11-24, the real
mouse through the new build:

- Gallery: a left drag from empty space draws the selection box (6 pictures). The
  title bar's ← then leaves the selection.
- My Files: a drag from beside the rows draws the box (5 rows). A drag that starts
  on a row picks that file up at once: its picture and a count follow the pointer,
  and the places on the left light up as drop targets. Dropped back on its own row,
  nothing moved.
- Hover lights My Files' rows. Leaving the window in one move puts the lit row out.
- After a mouse box selection in My Files, Back closes My Files instead of clearing
  the selection. That is My Files' own handling: a plain `adb shell input keyevent 4`
  does the same, and the title bar's ← sent one key (`key 4 for task 2221: OK`).
  A long-press selection is cleared by Back. Esc does nothing to a mouse selection,
  and a click on another row adds that row to it.

Not yet:

- on the S25: a file dropped into a folder or onto another app, a title bar move, a
  resize edge, an icon from Apps to the desktop;
- the client on Windows and Linux (CI's `scrcpy-client` job builds it), and any
  Windows or Linux host;
- the S24 on Android 15, or a phone whose shell decorates its windows.
