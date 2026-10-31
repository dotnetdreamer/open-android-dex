# A sharp desktop, the computer's own size

The desktop used to be 1920x1080 at 160 dpi on every computer. On a Retina
laptop or a 4K monitor scrcpy's window stretched it 1.6 to 2 times, and
everything on it — Android windows, Linux windows, the taskbar — looked soft.

Now the desktop is the size of its window, in the screen's own pixels, and
follows the window as it is resized, maximised, made fullscreen or dragged to
another screen: one phone pixel for each screen pixel, whatever the window's
size. Its density follows the computer's own scaling: one dp is one point of
the Mac's or Windows' UI, so an Android window is the size a native one is
beside it, on a Retina laptop (320 dpi) and on a 1x monitor (160) alike. The
stream's bit rate grows with the pixels.

Settings → Scrcpy Config shows it as two new defaults, "Match the computer's
screen" and a bit rate of "Auto". A resolution or a rate picked there still
wins, as before.

The window daemon's socket stopped answering strangers in the same change; it
is the second half of this record.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The fit | `src-tauri/src/display.rs` | the screen, the display's size and Default density, the bit rate, what each phone has taught us |
| Applying it | `scrcpy.rs` (`apply_stored_config`, `apply_stored_density`, `build_args`) | the command line of every desktop spawn and respawn |
| Following the window | `openandroiddex-scrcpy` (`display-scale.patch`, `build.mjs`) | scrcpy's server, rebuilt: a window's size asked for in pixels; no density left forced on its displays |
| …and the screen | `scrcpy.rs` (`watch_window_scale`), `embed::window_scale` | the scale of the screen the window is on; the density with it |
| …and the window manager | `scrcpy.rs` (`parse_display_resized`, the enforcer's `refit`) | the display's live size; maximised windows kept maximised |
| Telling the launcher | `adb.rs` (`adb_start_launcher`), `scrcpy.rs` (`watch_window_scale`) | `--ei base_dpi` on every launcher start, and a `DEFAULT_DENSITY` broadcast before a density that follows the window to another screen |
| Display size | `DisplaySize` (the arithmetic), `SettingsActivity.buildDisplaySizeCard`, `DisplaySizePreview`, `DexPrefs.sizeFactor`, `LauncherActivity.reapplyDensity` | a multiple of the Default, preset or the user's own; the card and its preview; the two new rows |
| Title bars | `CaptionService` (`px`, `trackDensity`) | bars in the display's own pixels |
| Linux | `LinuxWm.atDpi`/`dpi`/`displaySize`, `LinuxWindows.desktopChanged`, `LinuxActivity.pixelsPerGuestPixel` | GTK's scale in a window, the X screen following the display; XFCE's framebuffer |
| Who gets in | `openandroiddex-wmd`: `Peers.java`, `WmDaemon.callerOf`, `PC_ONLY`; `UnplugWatch.ARMED_KEYS` | the caller's uid; what each caller may ask |
| Check | `cargo test display::`, `test/sharp-desktop-test.mjs`, `test/linux-windows-test.mjs`, `test/display-size-test.mjs` | the arithmetic and the parsing, off a phone |

## Following the window

scrcpy 5's `--flex-display` resizes the virtual display to follow the window.
Stock, on a Mac, it is softer than the stretched 1080p it would replace: the
client takes the window's size from `SDL_EVENT_WINDOW_RESIZED`, which SDL3
reports in *points* on macOS (and Wayland), so on a 2x screen the display gets
half the screen's pixels and `--render-fit=unscaled` draws each one twice as
big. Upstream knows ([#7096](https://github.com/Genymobile/scrcpy/issues/7096),
October 2026): the logical size is deliberate, and a
`--display-scale=logical|physical` option "would make sense". It is not in
5.0.1, the newest release.

So the part of scrcpy that runs on the phone is rebuilt with a small patch
(`openandroiddex-scrcpy/display-scale.patch`, built by `build.mjs` from scrcpy's
own source at the bundled release, by `npm run apk` locally and in CI's
launcher job): every size the client asks for is multiplied by a scale the
computer writes to `/data/local/tmp/oadx-display-scale` — 2 on a Retina screen,
1 elsewhere and always on Windows, where SDL already measures in pixels. The
client stays the stock release, started with `--flex-display` and
`--render-fit=letterbox`: the frame is then the window's size in pixels, and
letterboxing draws it into the window one for one. Mouse positions map through
the drawn rectangle, so they need nothing. A frame the server marks as a
client resize never makes the client resize its window, which is what keeps the
doubled size from fighting the window. Without the file, or with the release's
own server, it is stock flex — and the log says so ("does not follow the
screen's scale").

- **Opening.** SDL centres the window on the main screen and fits it to that
  screen's usable area less 96 on each axis. The first display is exactly that
  area times the scale (`display::flex_geometry`), so the window opens one for
  one with no resize; macOS may then place it on another screen, which the
  next point covers.
- **Resizing.** The client asks for the new size in points; the phone makes it
  in pixels, says "Display resized: WxH/dpi", and the PC's window manager reads
  that line — and `wm size -d` every 2.5 s, for a server that does not say —
  and re-fits maximised windows to the new display instead of reading them as
  resized by hand.
- **Another screen.** A window dragged from a 1x monitor to a Retina screen
  asks for nothing: its size in points is unchanged. `watch_window_scale` asks
  the window server once a second which screen the window is on
  (`CGWindowListCopyWindowInfo`, no permission needed; `GetDpiForWindow` on
  Windows), writes the new scale for the phone, which applies it to the last
  size asked for, and moves the density with it (`wm density`, Default × the
  phone's Display size) so the UI stays the size it was. Just before that it
  tells the launcher the new Default (`DEFAULT_DENSITY`, `--ei base_dpi`; the
  launcher takes it only from a sender holding `WRITE_SECURE_SETTINGS`, which
  the shell has). The launcher used to divide the new density by its Display
  size instead, which gave a Default too small whenever the density was held
  at the 960 dpi limit: 3x on a screen whose Default is 480 is held at 960, and
  960 / 3 is 320. The broadcast also updates the intent the desktop was
  started with, which a re-creation (a language picked, a reset) reads the
  Default from again.

A resolution picked in Settings is a fixed display, as before, and Linux hosts
keep the display fitted once (below): X11 and Wayland measure windows
differently and neither has been tried.

## The fit, where the window is not followed

On Linux the display is fitted once, and on macOS and Windows this is the
fallback should a following display be impossible (a screen too small for
one). `display::screen_for_desktop` takes the screen the app's own window is on —
where the person who clicked Start is looking — or the main one, and its work
area from Tauri: no menu bar, no Dock or taskbar, in physical pixels.

Off that comes the frame scrcpy's window will get, because SDL positions and
sizes the window's *content*:

- **macOS**: the title bar, measured once at start-up on the main thread with
  `NSWindow frameRectForContentRect:styleMask:` (SDL's style for a resizable
  titled window). It is 32 points on macOS 26, not the 28 every macOS since 11
  drew, which is why it is measured.
- **Windows**: `AdjustWindowRectExForDpi(WS_OVERLAPPEDWINDOW)` at the screen's
  dpi. Its left, right and bottom include Windows 10/11's invisible resize
  borders, so a few pixels of desktop show beside the window.
- **Linux**: 37 logical pixels left for whatever draws the title bar, and the
  window is not placed at all — the frame is the window manager's or the
  app's, and Wayland lets nobody choose a position.

The display is the content area in pixels, rounded down to 16: scrcpy rounds a
new display down to its encoder's alignment itself, and one that came out a few
pixels smaller than the window would be scaled by 0.99 instead of shown one for
one. Past 3840 pixels on the long side (`MAX_LONG_SIDE`, 4K UHD) — or past what
this phone's encoder has been seen to manage — it is scaled down by k and the
window, still filling the screen, shows it 1/k larger; the density scales by
the same k, so the desktop's layout in dp does not depend on the cap.

The Default density is `160 x scale x k`: 160 on a 1x monitor, 320 on a Retina
screen, 240 at Windows' 150 %.

scrcpy is then given `--window-x/y/width/height` — points on a Mac, pixels on
Windows — so the content sits directly under the top of the work area, centred
across it. Left to itself scrcpy fits the window to the screen less a 96-point
margin, about 0.9x, which is soft again. In fullscreen the display is
width-limited and still one for one, with thin bars above and below.

A screen too small to fit (under 960x540) keeps the fixed 1080p. So does the
degraded retry after a session that never got a display: whatever it strips,
it also goes back to 1920x1080 at the same Display size preset, which every
phone has run.

## Display size

The five sizes (Very small to Very large) were absolute dpis, which only meant
the same thing at the resolution they were picked at — 160 was Default at
1080p. They are multiples now, `SIZE_FACTORS` {0.75, 0.875, 1, 1.25, 1.5} on
both sides, of whatever the session's Default is. Since
[issue #6](https://github.com/dotnetdreamer/open-android-dex/issues/6) a size
can also be any multiple between and past them, picked to the dpi: the five
topped out at 1.5x, 240 dpi on a 1x screen, and the issue asked for 280 and 320.

- The computer remembers each phone's multiple, by `ro.serialno` rather than
  by adb route, in `display-size.json`, and bakes `Default x multiple` into
  every `--new-display`. A Display size request from the launcher is turned
  back into a multiple of the session's own Default before it is remembered
  (`display::normalize_factor`): a preset when it is within 0.01 of one — a
  whole dpi divides back into its preset only to within 0.5 / Default — and
  otherwise the multiple itself, to four decimals, between 0.5 and 4. Until
  #6 it was snapped to the nearest preset, which is why a density set any other
  way never survived a reconnect.
- The launcher keeps the same multiple (`display_size_factor`, by the same
  rule in `DisplaySize.normalize`) with the nearest preset's index beside it
  (`display_size_index`) for a launcher from before. It learns the Default from
  the `base_dpi` extra the computer starts it with, and from the computer again
  when the window goes to a screen of another scale. Only the computer can know
  it — it comes from a screen the phone has never seen.
- Every whole dpi the controls can show comes back as itself through
  `dpi / Default` and `Default x multiple` on both sides (checked for ten
  Defaults from half to 4x), or the launcher's `reapplyDensity` and the
  computer would correct each other on every start.
- An old absolute dpi, on either side, is moved over once as the preset it
  stood for at the resolution then in force (1080p unless one was picked):
  160 → Default.
- A resolution picked in Settings keeps the old 1080-dp-tall Default
  (`default_density`, `SettingsActivity.defaultDpi`).

### The card

Settings → Display & UI opens on it, because it is the first thing a desktop on
a new screen needs and the issue came from someone who had not found the five
presets at the bottom of the section. It is also on the desktop's right-click
menu ("Display size…", where Windows keeps Display settings; Settings opens on
the card and outlines it for a moment), and Settings' search finds it under
density, dpi, scale and zoom.

- **A preview of the desktop** (`DisplaySizePreview`), drawn rather than
  captured: the user's wallpaper, theme and taskbar style, the icon column and
  the window a new app would open in, each at its size in dp at the density
  under consideration. The window is the one `nextWindowBounds` would give —
  its arithmetic now lives in `DisplaySize.newWindow`, which both call, and
  the test compares it with the old inline code in 35,840 cases. It is a share
  of the display in pixels, so it stays put while what is inside it grows or
  shrinks. What is inside is a sketch of a typical app, not a real one, laid
  out the way such an app would be in that room: one column under 600 dp,
  list and detail from 600, a navigation rail beside both from 840.
- **The five presets** as pills, one click each, applied at once.
- **A slider** from half the Default to the densest that still leaves the
  display 640 x 360 dp — a phone held sideways; any denser and the taskbar, the
  Start menu and Settings itself stop fitting, and the way back is inside them
  — and never past 4x the Default, the most a multiple is kept as. Without
  that cap a 4K screen at a Default of 160 had a track to 960 dpi on which
  everything past 640 was applied as 640, the thumb jumping back on release.
  It is logarithmic, because 120 → 160 is felt as the same step as 240 → 320
  and a linear track put the presets in its first third, with a detent and a
  dot at each preset. It only ever shows a value it would apply (5 dpi apart,
  a preset, or either end exactly), applies on release, and always reaches
  every preset and the size in force, which a desktop that follows its window
  can leave outside the display's own bounds. So the 640 x 360 dp is the
  slider's own limit, not a promise: on a small display at a high Default,
  Large and Very large can leave less (1280 x 720 at a Default of 320: Very
  large is 480 dpi, about 427 x 240 dp). They were there before the slider
  was, and stay one click away.
- **− and +**, ten dpi a press, landing on a preset on the way rather than
  stepping over it; held down — by a finger, the mouse, or Enter or Space —
  they repeat until let go. A hold is the button's pressed state, which the
  view also drops on losing focus or the window and when the pointer slides
  off; an accessibility long press holds nothing down and is one step. They
  apply 900 ms after the last press, as the slider's arrow keys do: every
  apply is a `wm density` and a rebuild of every activity on the display, this
  window included.
- **What it leaves**: "Looks like W × H", the desktop in dp as macOS words it,
  and the layout apps choose in a new window there (`DisplaySize.layout`):
  *Tablet layout* when its content is at least 600 dp both ways, *Phone layout*
  under 600 wide, and *Mixed layouts* between, where apps built on window size
  classes (by width) and apps shipping `sw600dp` resources (by smallest width)
  disagree. It is an estimate from those two breakpoints: each app picks its
  own layout, so the sentences under it say "most apps" and "some apps", and
  the note below the card says a few apps only have a phone layout. The issue
  expected 240–320 dpi to bring tablet layouts at 1080p; a lower density is
  what gives apps the room for them, and the card shows which way each goes.
- **Status**: "Applying display size…" until the density lands. Landing
  re-creates the window, and that is the confirmation: carried across in the
  saved state, it shows as "✓ Applied". Eight seconds without it, the card says
  the desktop picks it up at its next start, which it does: the request stays
  in the queue and `reapplyDensity` asks again.
- **The window keeps its size in dp**, as a computer's own windows do when its
  scaling changes. A freeform task keeps its pixels through a density change,
  so at twice the density Settings was half its size in dp and the card it was
  changed from no longer fitted. Before the first change of a run the window's
  size in dp is noted (and whether it is maximised); after each one lands,
  `CaptionService.placeTask` puts it back at that size about its centre,
  inside the maximised rect, through the daemon's one-shot `RESIZE` — or on the
  maximised rect, which moved with the taskbar. A size the display clamped is
  not noted as the user's: the note is only taken afresh when the window's size
  differs from where the card last put it. A dimension that all but filled the
  display (Settings opened at a large size) is noted as the 940 x 640 dp it
  opens at. In a window still too short for everything, the preview gives up
  height first and the controls are scrolled back into view.
- `launchSettings` now centres the window in the room above the taskbar, which
  at a large size is a good part of the display.
- On the phone's own screen, where Settings opens when the launcher is the
  phone's home, the card is a link to Android's own Display size instead:
  there is no desktop display there to describe.

### When the desktop ends

The phone's own screen (display 0) is never given a density: every
`wm density` here is `-d` the desktop's virtual display, and the reset below
refuses display 0, where it would undo a size the owner set themselves.

The desktop's display goes with the session, but on Android 14 and earlier its
density may not. A density forced on a virtual display is kept in
`/data/system/display_settings.xml`, across a reboot too, under the display's
identity, `virtual:com.android.shell,2000,scrcpy,0`, and forced again on the
next display that gets that identity: the next desktop, a phone app's window,
anyone's scrcpy. Android 15 forgets it with the display
(`DisplayWindowSettingsProvider` keeps virtual displays out of the file from
`android15-release`; 11 to 14 do not). The Display size and a window moved to
a screen of another scale are both `wm density -d`, so:

- **A desktop that ends on purpose** (Exit DeX on the phone or here, the app
  closing, a restart for new stream settings, a switch to Wi-Fi) has its
  density taken off by the computer before scrcpy closes the display
  (`release_display_density` in `stop_mirror` and `kill_all`): one shell line
  that resets a forced density on Android 14 and earlier and leaves 15 and
  later to Android, rather than re-laying out a desktop about to close. It
  must come first: a display already gone cannot be reset.
- **The server clears it too** (`clearForcedDensity`, display-scale.patch):
  before it releases its display, which runs when it notices its connection
  close, as a desktop in use does at once; and on each display it makes,
  before it reports it, whatever an earlier display left there. So a cable
  pulled, or a server killed before it could tidy up, is undone by the next
  display the app makes on that phone at the latest. Until then it is still
  there, and a stock scrcpy display made in between (it takes adb, so a
  computer) would get it. The phone itself cannot clear it after the cable is
  out: the reset needs the display, and the display has gone.

## Bit rate

When none was picked: scrcpy's own 8 Mbps for 1080p, scaled by the most pixels
the display will have — the screen's usable area, for one that follows its
window: 22 Mbps on a 14" MacBook — and held between 8 and 32 over a
cable and at most 16 over Wi-Fi, where scrcpy cannot slow down for a busy link
and asking for more than arrives is felt as lag. A still desktop costs a
fraction of either; the encoder only spends on change. "Reduce quality" still
caps everything at 4.

## The encoder's limit

scrcpy 5 tries a display's full size first and, if the phone's encoder fails,
applies its declared limits and retries smaller ("Retrying with -m…"). The
session works, but every start pays a failed attempt and the phone's GPU draws
pixels the stream throws away. The client's `Texture: WxH` line is the size it
decodes; one smaller than the display is remembered per phone and codec
(`video-caps.json`), and later desktops render no larger. The S25 Ultra's
encoder takes 8192x8192, so it has never needed this.

## What a denser desktop changed elsewhere

**Title bars.** `CaptionService` was written when the desktop ran at 160 dpi,
with `px(dp) = dp` and a 40 px caption. At 320 dpi its bar was half a title
bar with One UI's own caption showing underneath — and One UI's, 80 px there,
was rejected as "taller than any caption" and never measured. Every size in it
is in dp now, converted at the desktop display's density, which is followed
every pass; on a change the bars and measured heights are dropped and rebuilt.
The same bars were already slightly short at the Large presets.

**Linux windows.** The window session's X DPI is the desktop's (96 at 160 dpi,
192 at 320). Font DPI alone at 192 is big text in small widgets, so past 1.75x
`LinuxWm.atDpi` also publishes `Gdk/WindowScalingFactor` and `Gdk/UnscaledDPI`
the way GNOME does for a HiDPI screen: GTK draws at a whole multiple, Chromium,
Electron and Firefox take the same scale. The guest's own xsettings pin the
factor at 1 for the VNC desktop; these windows are composited one X pixel per
Android pixel, so the scale is theirs.

**XFCE in DeX.** Its framebuffer was the window's device pixels, which at
320 dpi makes XFCE (pinned at 96 dpi for VNC's sake) half the size of
everything around it. It now gets whole device pixels per X pixel: 2 from
280 dpi, 3 from 440.

## Who may talk to the window daemon

wmd listens on loopback TCP 7191 because SELinux does not dependably let an app
reach a shell-domain Unix socket. Loopback TCP carries no SO_PEERCRED, so until
now any app with INTERNET could connect and drive it with shell authority —
including `ARM`, whose argument is a shell command the watchdog runs as uid 2000
once the computer goes quiet, and `REQPUT`, whose rows the computer executes.

The kernel knows who connected. `/proc/net/tcp6` and `/proc/net/tcp` list each
socket with the uid that made it, shell may read them (apps may not, since
Android 10), and the caller's end is the line whose local port is the peer's
port and whose remote port is 7191 — both tables, because a Java client is a
dual-stack socket and adbd's forward is a plain IPv4 one. Only a socket a
process still holds counts: a client that connected, wrote and closed before
the read is an orphan printed with uid 0 and inode 0, which read naively is
root.

- uid 2000 or 0 — the computer through `adb forward`, or adb shell — may use
  every verb.
- The launcher, matched by app id (`cmd package list packages -U --user 0`,
  re-asked at most every 2 s for a caller it does not know, since a reinstall
  can move it), may use every verb but `ARM`, `REQGET` and `REQACK`, which only
  the computer sends. Its uid is the Linux guest's too, where anything the user
  installs runs.
- Anyone else is answered `ERR not allowed` and hung up on, with one log line
  per uid per 10 s.
- Tables that cannot be read at all leave the daemon as open as it was, and say
  so once in `wmd.log`; no phone checked denies them to shell.

What this does not close: an app that binds 127.0.0.1:7191 *before* the daemon
does gets every connection the daemon would have had. The launcher's cable-out
undo (`UnplugWatch`) used to write any `settings put global|secure` line such a
daemon handed it, with the WRITE_SECURE_SETTINGS the computer grants for the
session; it now writes only the eight global keys the computer ever arms
(`ARMED_KEYS`, which must track `DESKTOP_GLOBALS`, `TOUCH_GLOBAL` and
`PERF_GLOBALS` in `adb.rs`). The computer still drains requests from whatever
answers on 7191; checking that the listener is uid 2000 before `adb forward`
is the follow-up.

## Verified, and not yet

Off a phone: `cargo test` (116; `display::` 15 — the following display opening
one for one on a 1x and a Retina main screen and on Windows at 150 %, a fitted
Retina laptop, a 1x monitor, a 5K screen capped, the learned cap, Linux,
screens too small, alignment, bit rates, Wi-Fi routes, presets, the texture
line — and the server's "Display resized" and `wm size` lines);
`node test/sharp-desktop-test.mjs` (29: the daemon's callers against
`/proc/net` lines read off the phone, and the patch's `DisplayScale` lifted out
of the patch file); `node test/linux-windows-test.mjs` (58, five for `atDpi`);
`tsc`; the whole app type-checked for Windows (`AdjustWindowRectExForDpi`,
`GetDpiForWindow` included); the patched server built by `build.mjs` and by
upstream's own `build_without_gradle.sh`, byte for byte the same size.

On the phone (Galaxy S25 Ultra, Android 16, the desktop app from `tauri dev`,
on a 14" MacBook Pro with a 2560x1440 1x monitor as its main screen, macOS
26.6.1, 2026-10-20):

- On the 1x monitor: a 2560x1280 desktop at 160 dpi; scrcpy's window frame
  2560x1312 at y=30, content exactly the work area below the menu bar; the
  taskbar's text crisp at one pixel for one.
- On the Retina screen (the app's window moved there, the desktop restarted
  from Settings): 3024x1824 at 320 dpi, the window 1512x944 points — content
  3024x1824 pixels, one for one — and Retina-sharp in a 2x capture. macOS put
  it 5 points lower than asked, under the notch; the size is unaffected.
- The old 160 dpi carried over as Default; the launcher started with
  `base_dpi 160`, then 320; Settings shows "Match the computer's screen",
  bit rate "Auto" and "Default screen" selected.
- The daemon: adb shell answered as before; the launcher let in by app id
  (10601); an ordinary app (a throwaway probe, uid 10602) answered
  `ERR not allowed` and hung up on, logged, and its `ARM` left the armed undo
  untouched.
- Linux windows at 320 dpi: the session at 3024x1824 and 192 dpi, Thunar drawn
  by GTK at 2x; its title bar 40 px with One UI's caption showing below before
  the CaptionService fix, 80 px and covering it after. Calculator's the same.

The desktop following its window (2026-10-21, the patched server staged by
`npm run apk`):

- The server announced itself ("Flex display: 1.0x the window's size") and made
  2464x1220 at 160 dpi for the 1x main screen; macOS put the window on the
  Retina screen, the watcher saw it within a second ("the window is on a 2x
  screen now"), and the phone re-made the display at 3024x1834 at 320 dpi — the
  window's content in pixels; a 2x capture one for one. With the release's own
  server the same session had warned and stayed at 1512x916, in points.
- Resized to 1000x700 points: "Display resized: 2000x1336/320", the taskbar and
  the tray laid out across the new width, the UI the same size, the capture
  2000 pixels wide.
- Dragged onto the 1x monitor: "Display scale: 1.0x", "Display resized:
  1000x668/160".
- Calculator maximised at (0,0)-(1796,916) on a 1800x968 desktop; the window
  grown to 2160x1112 (macOS kept it on the screen): "re-fitted to the display",
  (0,0)-(2156,1060), still maximised.

Display size, between and past the presets (2026-10-31; the desktop app from
`tauri dev` on Windows at 100 %, a read-only Pixel 8a emulator on Android 16,
a 1824x936 desktop whose Default is 160):

- Off a phone: `node test/display-size-test.mjs` (57: what a size is kept as,
  every dpi from half to 4x of ten Defaults back as itself, the slider's bounds,
  detents and steps, the new-window rect against the old `nextWindowBounds` in
  35,840 cases, the layouts); `cargo test` (196, `display::` 19 with the custom
  multiples and the same round trip).
- The desktop's right-click menu shows "Display size…"; it opens Settings on
  the card, outlined for a moment. Search finds the section by "dpi".
- Large: `wm density` 200, "display size 1.25x Default"; the next session's
  display made at 1824x936/200. Three presses of +: one request, 230,
  remembered as 1.4375 (it used to be snapped to 1.5). A drag: 330, remembered
  as 2.0625, and the next session made at /330 with no correcting request from
  the launcher. Holding +: 160 to 230, one request. The top of the track there:
  415 (640 x 360 dp is 416; the track is in steps of 5).
- The preview turned from list and detail with a rail (160) to without (200)
  to one column (330); the verdict went Tablet → Mixed → Phone with them. Dark,
  Light and Windows 11 Light all drawn in their own palette and taskbar.
- Settings at 940 x 640 px at 160 → Very large: [207,0][1617,858] (940 dp
  across, as tall as the room above the taskbar allows) → 415: the whole
  maximised rect, the controls scrolled into view → Back to Default:
  940 x 640 px again. Opened fresh at 335 it is [91,41][1732,785], 90 % of the
  room each way; taken to Default it came back 796 x 408 until a dimension the
  display had clamped was noted as the size it opens at, and with that,
  [441,93][1381,733]: 940 x 640.
- Factory reset of "Display size and language" alone: a request for 160,
  remembered as 1x; the card reopened at Default, not at the 170 picked before.
  (That scope resets the desktop's language as well; on an English emulator
  left on Automatic it changed nothing visible.)

After a review of the above found three bugs (the same day, same setup, the
fixed launcher and desktop app; input as before, `adb shell input -d` on the
desktop display):

- Off a phone: `display-size-test.mjs` 61, with a check that walks every dpi
  of 770 tracks (14 Defaults, 11 screens, 5 sizes in force). Before the fix it
  failed: 1080p at a Default of 80 ran to 480 and applied everything past 320
  as 320, and 4K at 160 likewise past 640; the top of a Retina track showed
  755 for 756. `cargo test` 197.
- Enter held for 1.5 s on + (focus moved there with Tab): 160 to 250 while
  held, then nothing more, and one request, 250. The old repeat stopped only
  on a touch's release, so a key or an accessibility long press ran it to the
  top of the track. A touch held on −: 250 to 160, one request.
- The end of the track: 416, applied as 416. Two presses of Right on the
  slider: one request, 235, 900 ms after the second.
- Windows scaling 100 % → 125 %: "told the launcher the Default is 200 dpi",
  the launcher's "the desktop's Default density is 200 dpi" logged before
  that line returned, then `wm density` 250 (Large, 1.25x); the card showed
  250 with Large lit; no request from the launcher. A language picked and put
  back re-created the desktop twice: no request either (it read 200 from its
  start intent, which the broadcast updates; without that it would have read
  the 160 it was started with and asked for 200, from the code, not run).
  Back to 100 %: told 160, density 200.
- Not run: a Default held at 960, which this PC cannot reach (its scaling
  stops at 175 %, and the clamp needs a screen more than 2.1 times denser than
  the one the size was picked on). With the Default no longer worked out from
  the density, the clamp has nothing left to get wrong.

The density left behind (the same day; a Galaxy A13 on Android 12 by USB, and
the emulator):

- On the A13, bare scrcpy displays with no window, not a desktop: one forced
  to 200 and closed, and the next display with the same identity, asked for
  160, came up at "Override density: 200". With the fixed server: "Forced
  density 200 left by an earlier display cleared: 160 dpi again", logged
  before "New display", and 160. The computer's reset line on a display
  carrying the 200: "reset", then "none"; the next display made by the old
  server, which clears nothing, came up at 160.
- Also on the A13: the fixed server's clear on release did not run. Killing
  the test client hung its server up before it noticed the connection close;
  that display drew nothing, so the server had nothing to send to find out
  sooner. The next display came up at 200, which is why the computer resets
  first and every new display is cleared.
- On the emulator, a real desktop at 235 with Large forced (200), Exit DeX
  from the taskbar: the reset line ran on display 3 before the session
  stopped and answered "forgotten" (Android 16), and the server's own release
  clear ran: "Forced density 200 on this display cleared: 235 dpi again".

Not yet: the card on the phone's own screen (Android's Display size link; the
shell may not start Settings there and the launcher would have to leave the
desktop), an accessibility long press on − or +, "Not applied yet" (needs a
computer that stops draining the queue), a desktop on a real phone, Android 12
with captions off (the window keeps its pixels as before), a real desktop on
Android 11 to 14 ending by Exit or by a real cable pull, and a real cable pull
anywhere.

Not yet:

- a Windows or Linux computer: only type-checked, so the frame insets, SDL's
  pixel coordinates there and Wayland's placement are unproven;
- the learned encoder cap on a phone that needs it (the A13);
- the launcher's side of `PC_ONLY` on a phone — no debuggable build to run as
  its uid — and the daemon on Android 10–12;
- fullscreen and the green button's zoom with a following display (the same
  resize path, not watched happen); a window straddling two screens (the one
  holding its middle wins);
- Linux windows growing their X screen as the desktop grows mid-session
  (`desktopChanged` → RANDR), and GTK picking up a new scale when the window
  changes screen while a Linux app is open;
- a desktop that follows its window on Windows: SDL there sizes in pixels and
  the scale stays 1, but the density following `GetDpiForWindow` is untried;
- XFCE in DeX at 320 dpi, Qt apps in Linux windows (they read `Xft.dpi` from X
  resources, not XSETTINGS);
- the higher bit rates over Wi-Fi.
