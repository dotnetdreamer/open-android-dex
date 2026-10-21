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
| Following the window | `openandroiddex-scrcpy` (`display-scale.patch`, `build.mjs`) | scrcpy's server, rebuilt: a window's size asked for in pixels |
| …and the screen | `scrcpy.rs` (`watch_window_scale`), `embed::window_scale` | the scale of the screen the window is on; the density with it |
| …and the window manager | `scrcpy.rs` (`parse_display_resized`, the enforcer's `refit`) | the display's live size; maximised windows kept maximised |
| Telling the launcher | `adb.rs` (`adb_start_launcher`) | `--ei base_dpi` on every launcher start |
| The presets | `SettingsActivity`, `DexPrefs.sizeIndex`, `LauncherActivity.reapplyDensity` | Display size as a multiple of the Default; the two new rows |
| Title bars | `CaptionService` (`px`, `trackDensity`) | bars in the display's own pixels |
| Linux | `LinuxWm.atDpi`/`dpi`/`displaySize`, `LinuxWindows.desktopChanged`, `LinuxActivity.pixelsPerGuestPixel` | GTK's scale in a window, the X screen following the display; XFCE's framebuffer |
| Who gets in | `openandroiddex-wmd`: `Peers.java`, `WmDaemon.callerOf`, `PC_ONLY`; `UnplugWatch.ARMED_KEYS` | the caller's uid; what each caller may ask |
| Check | `cargo test display::`, `test/sharp-desktop-test.mjs`, `test/linux-windows-test.mjs` | the arithmetic and the parsing, off a phone |

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
  phone's preset) so the UI stays the size it was. The launcher re-derives its
  Default from the new density (`followBaseDpi`).

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

## The Display size presets

The five sizes (Very small to Very large) were absolute dpis, which only meant
the same thing at the resolution they were picked at — 160 was Default at
1080p. They are multiples now, `SIZE_FACTORS` {0.75, 0.875, 1, 1.25, 1.5} on
both sides, of whatever the session's Default is.

- The computer remembers each phone's multiple, by `ro.serialno` rather than
  by adb route, in `display-size.json`, and bakes `Default x multiple` into
  every `--new-display`. A Display size request from the launcher is turned
  back into a multiple of the session's own Default before it is remembered.
- The launcher keeps an index (`display_size_index`) and learns the Default
  from the `base_dpi` extra the computer starts it with. Only the computer can
  know it — it comes from a screen the phone has never seen.
- An old absolute dpi, on either side, is moved over once as the multiple it
  stood for at the resolution then in force (1080p unless one was picked):
  160 → Default.
- A resolution picked in Settings keeps the old 1080-dp-tall Default
  (`default_density`, `SettingsActivity.defaultDpi`).

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
