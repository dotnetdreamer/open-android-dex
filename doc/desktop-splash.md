# The desktop's splash

Opening the desktop used to show a black window for about four seconds, then
the launcher's icon on navy for under one, then the desktop. The black was not
a slow phone: the computer opens scrcpy's window as soon as the stream starts,
on a display that is still empty, and only starts the launcher on it once
`adb_start_launcher` has installed it, granted it everything and started the
window daemon - measured at 4.5 to 5 seconds a desktop on an S25 Ultra, 10 more
when the launcher has to be reinstalled.

Now the stream carries a splash from its very first frame. Out of the black, a
line of light opens into the laptop of the product mark, its desktop draws
itself, the phone swings in and lands in front of it with a ripple, and the
name appears. It waits with the user for as long as the phone needs, calmly.
Once the launcher has drawn, the laptop's screen in the mark turns into a
window onto the real desktop and the view dives through it, landing on the
desktop one pixel for one. Then the splash is gone from the pipeline
altogether.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| Asking for it | `scrcpy.rs` (`request_desktop_splash`, called from `spawn_scrcpy`) | `/data/local/tmp/oadx-desktop-splash`, written before a desktop's server starts, for desktops only |
| The splash | `openandroiddex-scrcpy/desktop-splash.patch`: `DesktopSplash` | taking the request, the clock, when the desktop may open |
| Drawing it | `DesktopSplashFilter` (same patch) | the shader, the name's texture, watching the display |
| Drawing on a clock | `OpenGLRunner`, `AnimatedOpenGLFilter` (same patch) | frames when the display has none |
| In and out of the pipeline | `NewDisplayCapture.startSplash`, `CaptureControl.RESET_REASON_SPLASH_DONE` | the GL stage while it plays, none after |
| The name | `openandroiddex-scrcpy/desktop-splash-wordmark.png`, `build.mjs` | a picture, built into the server as `DesktopSplashWordmark` |

## Why in the server

Nothing else on the phone is there for the whole wait. The launcher is
force-stopped by the very `am start` that starts it (`adb_start_launcher`: a
running instance keeps its old windowing mode), it is reinstalled whenever its
build differs, and the order of the deploy is not ours to shuffle
(`doc/custom-titlebar-v2.md`, 0.2.3). A splash in the launcher could only begin
after all of it - the four black seconds would stay. The window daemon is
started late in the same deploy, and holds no UI on principle.

The scrcpy server is there before the display exists and outlives everything
done to the launcher, and it already has the stage the splash needs: scrcpy
draws the display through OpenGL whenever it crops or rotates
(`OpenGLRunner`). The splash is a filter in that stage. Being in the stream, it
is the same on Windows, macOS and Linux, needs no window of its own on the
computer and cannot be out of step with what it covers.

## Asking for it

Only a desktop plays it. App windows are displays of their own made by the same
server (`--new-display`, and `--start-app` is a control message, so the server
cannot tell them apart at start). `spawn_scrcpy` writes
`/data/local/tmp/oadx-desktop-splash` before it starts a `<serial>|desktop`
session, the same way it writes `oadx-display-scale`, and the server that makes
the next display takes it in `NewDisplayCapture.init` and deletes it. A file
older than a minute is a desktop that never started, and is ignored. Respawns
after a reconnect go through `spawn_scrcpy` too, so a desktop that comes back
on a new, empty display opens on the splash again.

## When the desktop has drawn

Nothing tells the server, and nothing has to. An empty display is black, and
the starting window Android shows while the launcher starts is one colour with
the icon in the middle. Outside the middle, both are a single colour to the
last bit. The desktop is not: wallpaper, icons, and the taskbar along the
bottom edge. About ten times a second the filter draws the display into a
32x18 framebuffer, reads it back and compares the pixels outside the centre.
Two readings in a row that differ by 8 or more in a channel are the desktop.

The desktop then gets 0.3 s to settle (the taskbar is a window of its own) and
opens, but never before 2.3 s from the first frame, so the mark and its name
are always seen whole. If nothing draws for a minute the display opens as it
is: neither a launcher that never starts nor a desktop the check cannot tell
from a fill can keep the splash up. A minute is well past the slowest start
measured, about 15 s with a reinstall of the launcher.

## The film

All of it is one fragment shader on the splash's clock, in
`DesktopSplashFilter`. The mark is the launcher icon's own drawing
(`scripts/brand.py`), redone as rounded boxes in the icon's 1024 canvas, so it
stays sharp however far the view dives into it.

| Time | What happens |
| --- | --- |
| 0 - 1.1 s | black lifts to a navy room lit from the centre |
| 0.1 - 0.55 s | a point of light stretches into a line, the laptop screen's width |
| 0.5 - 1.4 s | the line opens into the screen, white-hot, cooling to its blue |
| 0.8 - 1.25 s | the bezel closes around it, the deck slides in |
| 1.0 - 1.6 s | the window pops in, its tile grows, its lines write, the taskbar dots pop |
| 1.0 - 1.6 s | the phone swings in, lands, its screen lights from the top |
| 1.5 - 2.5 s | two ripples leave the phone: connected |
| 1.45 - 2.2 s | the name wipes in, a sheen crosses the mark, then the name |
| 2.6 s on | waiting: the room breathes, a light runs along a line under the name, the sheen returns |
| reveal 0 - 0.3 | the laptop's screen fades to the live desktop; the name and the phone leave |
| reveal 0.1 - 1 | the view dives into the screen; the screen widens to the display and lands on it |

The final frame of the reveal is the display, sampled one for one with nothing
of the splash left in it, so the hand-over is not a cut.

## Back to the display

When the reveal ends the filter asks for a capture reset
(`RESET_REASON_SPLASH_DONE`). The encoder restarts, `NewDisplayCapture.start`
sees the splash is over and gives the display the encoder's surface directly,
exactly as if there had never been a splash: no GL stage, no extra copy, for
the rest of the session.

While it plays, `OpenGLRunner` draws the filter every 16 ms whether or not the
display has a new frame (an idle display has none), and stamps those frames
with the clock rather than the display's timestamps, which the synthesized
frames do not have.

## The name

The name is a picture, `openandroiddex-scrcpy/desktop-splash-wordmark.png`,
1024x128: "Open Android " in Roboto (Apache-2.0) at weight 300, white at 94%,
then "DeX" at 500 in the screen's gradient, #86C8FF to #4D9FFF; text size =
height / 1.45, baseline at 1.05 x the size, 2% letter spacing, centred.
`build.mjs` puts it in the server as base64 (`DesktopSplashWordmark`, generated
like `BuildConfig`); it is drawn mipmapped, 4% of the display's height.

It is not set in type on the phone because it cannot be. The server is not an
app, and since Android 12 a process's `Typeface` is filled in by an app's own
start-up. Measured on the S25 Ultra (Android 17, One UI): every typeface is
"not fully initialized"; `Typeface.loadPreinstalledSystemFontMap()` throws a
NullPointerException inside Samsung's FlipFont `setSystemFontMap`; a typeface
built from a file in `/system/fonts` falls back to the same uninitialized
default; and drawing text with no typeface aborts the process in native code
(`src == nullptr && gDefaultTypeface == nullptr`). The picture avoids all of it.

## When it cannot be drawn

Every way the splash can fail ends the splash, not the desktop: a GPU that will
not build its shader or give a framebuffer to read back from, a name that does
not decode, an EGL that will not start. The display is then drawn as it is,
which is what it was before. A desktop whose first picture already goes
through scrcpy's own transform (a crop, an angle or a locked orientation; the
app asks for none) does not play it either, and it never begins later, at a
reset.

## Measured

On the S25 Ultra (Adreno 830, Android 17), drawing the filter offscreen at
1920x1110: about 3 ms a frame, 10 ms at worst, the shader built and the name
decoded.

Then over two real desktop starts through `npm run tauri dev` on the same
phone (Windows, a 1824x1044 desktop at 200 dpi, 2026-11-02 19:46 and 19:47):

| | First start | Second start |
| --- | --- | --- |
| Launcher reinstalled during the wait | yes, 4.0 s | no |
| Desktop seen by the probe, on the splash's clock | 7.96 s | 3.68 s |
| Desktop open, from the start of the session | about 11.0 s | about 6.4 s |

The first start was recorded from the screen ten times a second. The window's
first frame, about 1.8 s after the session started, was already the splash's
line of light. The reveal landed on the desktop itself (wallpaper, taskbar,
icons), not on the launcher's starting window, and the reset at its end showed
nothing of its own: no black frame, the picture steady from the last frame of
the reveal on. The second start is known from the log only. Not yet measured:
any GPU other than this one, and the macOS and Linux apps (the phone's side is
the same for all three).
