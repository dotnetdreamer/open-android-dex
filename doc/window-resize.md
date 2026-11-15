# Resizing a window by its edges, and the pointer that says so

Issue #5, from a Debian X11 user with a Galaxy S22 Ultra: *the resize hit area at
the window edges is extremely small — I have to position the mouse almost exactly on
the edge — and the cursor does not change to a resize cursor, so there is no visual
indication that the edge can be grabbed.* Both halves were true on every computer and
every phone, and they have different causes.

- **The area** was One UI's: its desktop decor grabs a band about 10 px outside a
  freeform window (custom-titlebar-v2.md §0), and only where the decor runs at all.
- **The cursor** was never going to change. Under scrcpy's default `--mouse=sdk` the
  computer draws its own cursor over the video; the phone's PointerIcons are
  computed and dropped (DexCursors). And scrcpy's client has no way to be told a
  cursor: nothing in its protocol, nothing on its command line.

## Shape

| Piece | Where | What it does |
| --- | --- | --- |
| `ResizeGeometry` | launcher, plain Java | Where the zone is, what a point on it grabs, what of it can be reached, where a drag may take an edge |
| `ResizeEdges` | launcher, CaptionService's | The zone as windows; hover → the pointer's shape; the drag |
| `SIZE`, `SHAPE`, `SHAPEWAIT` | window daemon | One frame of a resize; the pointer's shape, set and waited for |
| `pointer_shape.rs` | the app | Waits for each shape and hands it to the desktop's scrcpy |
| `pointer-shapes.patch` | scrcpy's client | `shape <name>` on stdin → `SDL_SetCursor` |

## The zone

8 dp outside the window, 3 dp inside it, and the first 22 dp of every side from each
corner is that corner (`ResizeGeometry`). Outside, so grabbing an edge costs the app
none of its pixels — Windows' and GNOME's invisible borders are the same idea; a
sliver inside, so a pointer put exactly on the visible edge, the window's own last
pixel, still finds it. At 2x (a desktop fitted to a Retina screen) every size doubles.

It is four windows per desktop window, one per side, accessibility overlays on the
desktop display — the type the touchpad's arrow and a Linux app's menus already use.
Not one display-sized window with a region: that is a display-sized buffer, tens of
megabytes on a 4K desktop, for a transparent picture. Two things make overlays above
every app safe here:

- **They are trusted overlays.** A window whose *frame* lies over another app's
  window blocks that app's touches unless it is trusted
  (custom-titlebar-v2.md §2, `windowOccludesTouchAt` tests the frame). An
  accessibility overlay is trusted, so the app keeps every touch outside our region.
- **Each takes the pointer only where it can be reached**
  (`AttachedSurfaceControl.setTouchableRegion`, API 34, the floor CaptionService
  already has): the strip, minus the windows in front of its own (with their own
  zones, so the front window wins where two zones meet), the taskbar, and every
  window that lies above all apps (`CaptionService.covers`): by z-order, everything
  stacked above the topmost desktop window. Not by type: the taskbar's menus are
  PopupMenus of its window, and accessibility reports them as *application* windows
  with no parent — matched by type, a taskbar icon's menu was not a cover, and the
  bottom edge of the window behind it lay over "Close app" (seen on the S25: a resize
  arrow over the menu, and a click there would have started a resize). And a modal
  window — that very menu — makes accessibility leave out every window below it: with
  the menu open the list held the menu and our edges and nothing else, so there was no
  topmost window to compare layers with. Then everything listed is above it, bar
  entries shaped like a window or the whole display (one only blinked out of the list).

No zone on a maximised window (its edges are the display's), on the widget detour's
alert box, or where our windows are drawn under the apps (Samsung, untrusted display,
`overlaysBuried`): there One UI's own stays.

## The drag

Where the shell moves and re-crops a window as its bounds change, the drag is live,
the way AOSP's `FluidResizeTaskPositioner` does it: the daemon's `SIZE` per frame —
`setBounds` and the caption strip's `setAppBounds` in one `applyTransaction`, so the
app relayouts once, not twice — latest wins, then one `RESIZE` transition on release.
The caption bar follows each frame at once (`CaptionService.resizing`), so its buttons
are never cut off by a narrowing window.

Where it does not, a live drag would show nothing at all: `applyTransaction` changes
the bounds, and the picture stays where it was until some transition runs. Measured on
the S25 Ultra on Android 17 (`CP2A.260605.016`): SurfaceFlinger has no `Decor
container` and no `Freeform Outline` on the desktop display — One UI's desktop decor,
which custom-titlebar-v2.md §0 found running there on Android 16, is not — and no task
there ever has a shell caption window. On such a display (`CaptionService.captionless`)
the drag draws an outline where the window is going (`ResizeEdges.Outline`) and the
window takes it on release, in one `RESIZE` transition.

**The title bar had the same problem, and has the same answer.** Its drag is `MOVE`
per frame, `applyTransaction` too, so on that S25 a title-bar drag moved a window's
bounds and not its picture: it looked as if windows could not be moved at all, and
input and picture no longer agreed about where a window was (seen: AnyDesk's bounds
at x −120, drawn at x 734). A title-bar drag there is now an outline as well, moved on
release; displays whose shell follows keep the live drag. Each press also takes the
window's rect from the last poll rather than from the bar's build: a snap, a maximise
or an edge since then had moved it without telling the bar.

A drag never brings a window to the one rect the computer reads as "something just
maximised this": from x ≤ 4 to the display's last column (`Enforcer::fills`). Only a
side edge can get there, so only a side edge is held back; a window that spans the
width already keeps doing so when its top or bottom moves (the computer checks every
pass, so it has read that one as maximised already). Edges stop at the display and
above the taskbar, unless they were past it already, and at the minimum the window
manager will hold the window to when the drag lets go
(`Task#adjustForMinimalTaskDimensions`), so an outline never promises a size the
window won't take. The daemon reads it off the task (`TaskInfo.minWidth`,
`minHeight`, `defaultMinSize`, three more fields on each `TASK` line): the task's own
minimum is its ROOT activity's `<layout android:minWidth>`, in pixels as parsed and
applied as they are on any display; without one, the display's default, in dp. Ours
never goes under that default, and never under the title bar plus 48 dp.

The first version had this wrong (found in review): it read the TOP activity's
manifest and rescaled its pixels by desktop/phone density, so an app asking for 900 px
(300 dp at the phone's density 3) could be dragged to 300 px on a density-1 desktop in
the outline, then land at 900.

## The pointer

Three pointers can be over a desktop, and each learns it differently.

- **The phone's** (Settings → Mouse & cursor → Pointer rendering: Phone,
  `--mouse=uhid`): each strip's `onResolvePointerIcon` answers DexCursors' double
  arrows, in the user's cursor style.
- **The touchpad's** (the phone as touchpad, `DesktopPointer`): its arrow is a
  picture of ours, swapped in place for the double arrow (`DesktopPointer.shape`).
- **The computer's** (the default): the strips tell the window daemon (`SHAPE`), the
  app waits for each change over its forward of 7191 (`SHAPEWAIT`, one thread per
  desktop session), and writes `shape <name>` to the desktop scrcpy's stdin.

A shape is a state, not a queue: a PC that was busy skips the ones in between. It
lasts as long as the launcher's connection, so a launcher that dies over an edge does
not leave the computer's cursor a double arrow. The other side of that: the launcher's
client reconnects on its own (a read that timed out, a daemon restart), and the daemon
has put the arrow back by then, so the strips remember which connection a shape went
over and send it again on the next hover once that has changed — not only when the
shape does (found in review: it stayed the arrow along that edge until the pointer
left it). Going round a corner is a HOVER_EXIT
from one strip and an ENTER on the next, so the arrow comes back only after 60 ms out
of every strip. **And the arrow comes back on every release**: after a press Android
sends hover only to the window the pointer moves onto next, so a pointer that leaves
the edge after a drag never tells the strip — on the S25 the double arrow stayed on the
computer's cursor until the next edge. Still on the edge, the next move says so again.

### Why scrcpy's client is patched

Read off SDL 3.4.18, the SDL scrcpy 5.0.1 is built with, for each way of changing
another process's cursor:

- **Windows**: `WM_SETCURSOR` calls `SetCursor(SDL_cursor)` on every mouse move over
  the window. Only replacing the system arrow itself would show, for every program.
- **macOS**: a background process can set the cursor only with a private window-server
  property (`SetsCursorInBackground`). Tried once on this Mac (macOS 26), with the
  pointer over VS Code: `NSCursor.currentSystem` never showed the shape set. Never
  tried with the pointer over scrcpy's own window, so "does not work" is not proven —
  but a private property was not worth building on either way.
- **X11** (read off SDL's source and X11's rules, not run): `XDefineCursor` on
  scrcpy's window works from outside, until SDL defines its own again as the pointer
  enters — and scrcpy tells the phone nothing when the pointer leaves its window, so a
  shape that did not change would never be put back.
- **Wayland**: nothing outside the client can.

Inside the client it is one call that works on all four. `pointer-shapes.patch` adds
`pointer_shape.c`: with `SCRCPY_POINTER_SHAPES=1` in its environment the client reads
stdin, and each `shape <name>` (a CSS cursor name) becomes `SDL_SetCursor` of that
system cursor on the main thread. Without the variable it reads nothing. It says
`Pointer shapes: read from stdin` once listening, and the app writes nothing to a
client that never says so — a stock one, which then keeps its arrow.

That line has to be **flushed**: scrcpy unbuffers its stdout only on Windows, so on
macOS and Linux every INFO line — its version banner too — waits in a pipe buffer for
kilobytes more output. Without the flush the app waited 15 s, decided it had a stock
client, and gave up.

### Building it

`openandroiddex-scrcpy/build-client.sh <target>` builds the client from scrcpy's source
at `build.mjs`'s version and commit, with the patch, by upstream's own release scripts
(`release/build_{linux,windows,macos}.sh`: SDL, FFmpeg, dav1d and libusb from source,
statically linked on Linux and macOS), and refuses a binary without the patch in it.
The desktop workflow's `scrcpy-client` job runs it for each target, on the runners and
with the tools upstream's release.yml uses (`win64` cross-built with MinGW on Linux,
Linux on ubuntu-22.04, macOS Intel on macos-15-intel), cached on everything that
decides the binary. Each platform job lays the one executable over the release it
downloads — adb, the Windows DLLs and the icons stay the checksummed release's — and
checks the patch is in it and that it starts.

A dev run stages a client already built for the computer (`build-launcher-apk.mjs`),
by rename, never by copying over a binary a running scrcpy may have mapped.
`OADX_CLIENT_NO_USB=1` builds without libusb, for a Mac without autotools; it costs
`--otg`, which the app never uses.

**A same-size rebuild ran the old client.** The app spawns scrcpy through a hardlink
named after the product (`branded_exe`), refreshed when the binary changes — and
"changes" was the inode OR the size. Two builds of our client came out byte for byte
the same size (a Mach-O is padded to whole pages), so the link kept the old inode and
the old build ran. On one volume it is the inode now; size only for the copy made
across volumes.

## Traps met on the way

- **Two injectors, one device.** scrcpy's mouse and the daemon's (`AppInput`, the
  touchpad's) both inject as device −1. A drag injected from the daemon while a person
  used the real mouse left InputDispatcher holding a press that never ended
  (`CursorStatesByDisplay … touchingPointers=[MOUSE]` on the title bar): from then on
  hover reached no strip and the person's presses were refused as inconsistent. A new
  display clears it. Never inject while someone is using the desktop.
- **`tauri dev`'s rebuild re-copies `resources/` in place.** For a while afterwards
  every exec of the staged `adb` died at once with SIGKILL.

## Protocol

```
SIZE <display> <task> <l> <t> <r> <b> <px>   -> OK                 bounds + strip, no transition
SHAPE <display> <name>                        -> OK <seq>           launcher or PC
SHAPEWAIT <display> <seq> <ms>                -> OK <seq> <name>    PC only; ≤ 1200 ms
```

Shape names: `default`, `ew-resize`, `ns-resize`, `nwse-resize`, `nesw-resize` — and
`move`, `pointer`, `text`, `crosshair`, `not-allowed`, `wait`, `progress`, which the
client knows and nothing sends yet.

## Verified, and not yet

Off a phone: `node test/window-resize-test.mjs` (65: the zone, its sizes at 1x and
2x, corners, the strips tiling the ring exactly once, what windows in front, the
taskbar and the display's edge take away, every clamp of a drag, no side edge reaching
the maximise rect, the minimum taken as the window manager takes it; and `Shapes` — a
wait that ends on a change, one that runs out, the cap on a wait, per display, back to
the arrow when its connection goes). These run `ResizeGeometry` and `Shapes` only:
the overlays, the gestures, what counts as a menu, reading the minimum off a task and
SDL's cursors are Android's and the client's, and run only on the phone. `cargo test`
(`pointer_shape::` 2, `wm::` the SHAPEWAIT parser); `cargo check`; the launcher and the
daemon built; the patched client built on this Mac (arm64, without libusb) by
`build-client.sh`, and the patch checked to apply to v5.0.1.

On the S25 Ultra (Android 17, One UI, `CP2A.260605.016`) from this Mac with the
patched client, the person's own mouse: the edges and corners take hover and the
computer's cursor turns into the right double arrow (seen by them); resizes by
edges and corners, in outline mode, which this display chose itself (no shell
caption); the shape handed through `SHAPE` → `SHAPEWAIT` → scrcpy's stdin in the log.
With the display-9 build — before the two fixes to what counts as in front, which
changed nothing else: a corner resize (`resized task 1324 -> 264,118,1669,1082`) and
the shape back to `default` 19 ms after it; a title-bar drag moved Brave by (+270, +68)
at the same size, and a capture of the desktop showed it drawn exactly at its new
bounds, no outline left. With the display-11 build, the last one on the phone, only
hover and the menu were run — no resize. The taskbar menu over a window's bottom edge
(their report), run through the daemon's mouse with nobody else on it: with the menu open at
(1491,1086)–(1892,1419) the bottom strip's touchable region came back as
`[887,1296][1491,1318] | [1892,1296][2324,1318]` in `dumpsys input`, hovering the menu
where the strip had been left the shape `default`, and the same strip outside the menu
still said `ns-resize`. On the Android 16 emulator: the desktop came up with the
patched client before the flush was added; nothing about edges was run there.

Not yet:

- **The live drag** — never run on a shell that re-crops as bounds change (One UI with
  its decor, as on Android 16; AOSP desktop mode). Only outline mode has run.
- **Windows and Linux** — the client is built only by CI there, which has not run;
  the app's half is the same code on all three, compiled only on macOS.
- **Wayland, X11** — not run. On Wayland the client is native SDL, the case the patch
  exists for.
- **The touchpad's arrow** turning into a double arrow, and the phone-drawn pointer
  (`--mouse=uhid`) — not run.
- A drag ending at `fills`' rect, a window with its own `minWidth`, a Linux app's
  window, two windows overlapping with the back one's edge under the front one — the
  rules are checked off the phone, not on it.
- **The fixes from review** — the minimum read off the task (three more `TASK`
  fields), and a shape sent again after the daemon connection changes (the daemon
  puts the arrow back when a connection ends; the hover that follows a reconnect
  sends it again) — compiled and checked off the phone (`cargo test` 384 with a
  20-field line). On the S25 so far only this: the new daemon's `LIST 12` came back
  with 20 fields, `-1 -1 220` for the desktop's own task — Android 17 has the
  `TaskInfo` fields, no minimum of its own, the 220 dp default. A drag against a
  minimum and the resend after a reconnect have not been run.
