# The desktop's refresh rate, and its FPS readout

Settings › Scrcpy Config (the Stream section) › **Smoothness** holds the desktop at a
refresh rate the phone's panel really runs at (120, 80 or 60 Hz on an S25 Ultra), or
leaves it to the phone (**Automatic**, the default). The desktop's gauge, top right, and
the Task Manager show the frames per second the phone is streaming.

## Why the desktop is not at 120 on its own

The desktop is a virtual display, and a virtual display keeps no time of its own.
SurfaceFlinger paces it, and every app drawing on it, off the phone's built-in panel —
its *pacesetter* (`pacesetterDisplayId` in `dumpsys SurfaceFlinger`). Measured on the
S25 Ultra (SM-S938B, Android 17, Motion smoothness Adaptive), flinging Settings' list on
the desktop and reading the app's frames (`gfxinfo framestats`) and SurfaceFlinger's
cadence for its layer (`--latency`: the desired-present and frame-ready columns; the
actual-present column repeats one timestamp for a virtual display's layers and says
nothing):

| Phone's own screen | Desktop |
| --- | --- |
| Lit (the desktop kept the panel at 120 while anything on it moved) | 120 fps |
| Dark, from the taskbar's Phone screen tile (wmd `SCREEN 0`) | **60 fps** — SurfaceFlinger puts a dark panel at 60 Hz |
| Dark, right after a probe had held the panel at 24 Hz | **24 fps** |

The last row was forced, not met: a throwaway probe pinned the panel at 24 Hz and the
panel was darkened straight after. Whether a panel that idles down to 24 Hz on its own is
ever darkened that way was not seen — with the desktop running it stayed at 120 while
lit. It shows the trap that shapes the design: a dark panel sends no vsync, so
SurfaceFlinger keeps timing it off the last rate it *saw* the panel run at
(`VSyncTracker: mDisplayModePtr` in its dump); a mode set while the panel is dark is
taken (`activeMode`) but never confirmed. Holding 80 Hz in the dark gave 120 fps, the
rate last seen; holding 120 after that 24 Hz gave 24.

## Holding the rate

| Piece | Where | What it does |
| --- | --- | --- |
| `Refresh`, `RefreshRates` | window daemon | Holds the panels' display-mode policy at one mode; the rule for which |
| `HZ` | window daemon | `HZ <rate>` holds, `HZ 0` gives back (`ERR` if it cannot), bare `HZ` reads `OK <held> <rates…>` |
| `Screen.power(false)` | window daemon | Puts a held rate in place and lets it take before the panel goes dark |
| `Enforcer::hold_refresh` | the app (`scrcpy.rs`) | Sends the stored rate with every watchdog arm (~2 s); the request pump applies a change at once |
| `restore_phone` | the app (`adb.rs`) | Gives the rate back on a normal exit, before the daemon is stopped; warns if it could not |
| Smoothness card | launcher, Settings › Stream | Automatic plus the panel's own rates of 60 Hz and up, at the resolution the phone is set to |

The policy is SurfaceFlinger's `setDesiredDisplayModeSpecs`, which wants
`ACCESS_SURFACE_FLINGER` — the shell has it, so the daemon (uid 2000) sets it. One mode,
its rate as floor and ceiling, physical and render, primary and app-request ranges. The
policy is edited on a copy freshly read from SurfaceFlinger rather than built from a
constructor, because its class changes shape between releases: Android 17 added
`displayToken` and `workDuration` and takes an **array** of specs
(`setDesiredDisplayModeSpecs(IBinder, DesiredDisplayModeSpecs[])`); 14–16 take one; 12–13
have four plain floats instead of ranges. All three are handled; only 17 has run.

The rules:

- **Never above the phone's ceiling.** The rate held is the fastest of the panel's modes
  at or below both the rate asked for and the top of the policy the phone last sent —
  where battery saver, heat and Motion smoothness put their limits. So 90 on a panel with
  120/80/60 holds 80, and a ceiling of 60 caps a 120. With **every** mode above the
  ceiling (60/120 Hz modes under a 30 Hz ceiling, say) nothing is held: the phone's own
  policy stands until it sends another, and `HZ` answers 0. (The first version pinned
  the slowest mode there, above the ceiling.)
- **Only the phone's own panels**, both of a foldable's; never a monitor on a cable.
- **Only modes the size the phone is set to** (its policy's default mode), so holding a
  rate never changes the phone's resolution.
- **Nothing is stored.** The phone's DisplayManager owns the policy and sends it again
  whenever its own wishes change, so a 200 ms tick puts ours back, and keeps the policy
  the phone sent as the one to give back. Once the daemon is gone, the phone's next send
  ends the hold with nobody undoing it.
- **Ours is told by what it says** — its default mode and every range pinned at its
  rate — not by reading it back after writing it: a policy the phone sent in between
  would have been taken for ours and written over with an older one on release (the first
  version did that). A policy of the phone's own that pinned the same mode at the same
  rate would be indistinguishable from ours.
- **Set while lit.** Turning the panel off with a rate held re-applies it and waits up to
  200 ms for SurfaceFlinger to see the panel run at it. Changing to another held rate
  while the panel is dark *and this daemon made it dark* lights the panel and darkens it
  again: SurfaceFlinger logged the panel asked on, on 0.22 s later, and asked off 0.40 s
  after that (0.62 s from asking it on to asking it off; one measurement). Giving the
  rate back (Automatic) needs no light and makes none.

Given back by: choosing Automatic (live); the app's normal exit (`restore_phone`); the
watchdog, once the desktop's display has gone with a rate held; the dead-man switch's
cleanup; and, failing all of those, the phone's own next policy. A give-back that fails
says so — `HZ 0` answers `ERR`, `restore_phone` logs a warning — and the daemon keeps the
rate listed, so the watchdog tries again each second while the desktop is gone.

## The FPS readout

`desktop-fps.patch` (scrcpy's server, applied after `desktop-wake.patch`) counts the
frames the encoder hands out, per second, and tells the daemon `FPS <display> <fps>` on
127.0.0.1:7191 from a thread of its own; the launcher's gauge asks `FPS <display>` with
its processor reading (every 2 s) and shows `FPS 120`, the bar full at the panel's top
rate, and the Task Manager shows the same figure as a Frame rate card beside Processor.
The address is 127.0.0.1 by name: the daemon listens on IPv4 only, and Android's
`InetAddress.getLoopbackAddress()` is `::1` — the first build used it, was refused, and
the readout stayed hidden.

Counted at the encoder because every frame the desktop shows passes there, whoever drew
it — an app, a window being dragged, the launcher. A frame MediaCodec repeats by itself
while nothing changes (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`, every 100 ms) is not counted,
told apart by its timestamp being exactly 100 ms after the frame before. The column hides
when the daemon has heard nothing for that display for 3 s (no computer streaming it),
and on the phone's own screen. Only a shell-uid caller may report: the launcher's uid is
the Linux guest's too.

What it is not:

- **The frames the computer shows.** It counts the phone's encoder output before it is
  sent; scrcpy's client can skip frames (below), and nothing here counts the computer's.
- **Every frame.** A real frame that lands exactly 100 ms after the one before — 10 fps
  content on a 60 Hz panel, whose six vsyncs make 100.0000 ms — looks like a repeat and
  is not counted. Not measured.

## Measured

S25 Ultra, Android 17, over Wi-Fi from a MacBook Pro (M1 Pro, built-in 120 Hz
ProMotion screen), desktop 2832×1518, 4 Mbps ("Reduce quality" on), phone screen dark.
Phone side: Settings' list flung on the desktop, the app's frame gaps (`gfxinfo
framestats`) and SurfaceFlinger's cadence for its layer (`--latency`, as above).
Computer side: scrcpy's own counter (`OPENDEX_PRINT_FPS=1`), frames per second, during
~3 s of continuous flings; its seconds are lined up with the flings only roughly (see the
note below).

| Smoothness | App draws | SurfaceFlinger cadence | scrcpy on the Mac |
| --- | --- | --- | --- |
| Automatic | 60 fps (16.67 ms) | 60 | best seconds 61 rendered (one 84, cause not established) |
| 120 Hz | 120 fps (8.33 ms) | 120 | best seconds 124–138 rendered; in another run two seconds of 88 + 33 skipped and 105 + 16 skipped, 121 decoded each |
| 80 Hz, set in the dark (relit) | 80 fps (12.5 ms) | 80 | — |
| 120 Hz, set in the dark (relit) | 120 fps (8.33 ms) | 120 | — |

That shows the Mac receiving close to 120 frames a second in bursts of a few seconds.
Sustained presentation on the Mac was not measured, and why the client skipped 16–33
frames in those seconds — frames arriving together over Wi-Fi, or the Mac's rendering —
was not established.

Over Wi-Fi the user felt lag while things moved. What was measured there: the phone's
send queue to the computer reached 0.36 MB at Automatic and 0.2 MB at 120 Hz while
scrolling (`/proc/net/tcp`, the adb connection's tx_queue), and the phone's Wi-Fi upload
ran at 18–30 Mbit/s against a 4 Mbps target ("Reduce quality"). That points at the link
filling up at either setting, but no lag was measured over Wi-Fi, so the cause of what
the user felt is not established. With the cable in as well, the app used to open the
desktop over the remembered Wi-Fi connection; it now takes the cable when the same phone
is on both (`desktopDevice` in App.tsx), and over the cable the readout held 120 with
the phone's screen dark.

Lag over the cable, measured: a page in Brave on the desktop drew the phone's wall clock
(seconds and milliseconds) every frame, served from the Mac through `adb reverse`;
ScreenCaptureKit recorded the desktop window on the Mac at up to 120 fps, each frame
stamped with the time the Mac composed it, and Vision read the clock off every third
frame; the phone's clock was set against the Mac's over one adb shell (8 ms round trip;
the offset moved 0.3 ms across the runs, and being common to all of them it cancels out
of the comparison). Lag is from the browser's animation callback, before it draws, to
the Mac's frame time — the phone's compositor, the encoder, the cable, the decoder and
the Mac's compositor; not the mouse's way to the phone, nor the Mac's panel scan-out.
Each Automatic figure is one run; each 120 Hz figure, two. Phone screen dark, 10 s per run, 75–93
frames read each:

| Smoothness | Plain clock: median (p10–p90) | Whole window changing every frame |
| --- | --- | --- |
| 120 Hz | 45 ms (39–52); repeated, 48 ms (39–54) | 48 ms (40–55); repeated, 49 ms (41–57) |
| Automatic | 64 ms (53–72) | 67 ms (60–76) |

Holding 120 cut about 17–19 ms off, under load as much as without. Wi-Fi was not
measured this way.

Browsers stay at 60, and not because of the desktop. The pages above drew at about
60 fps at either setting while native views drew at 120. A page timing its own
`requestAnimationFrame` (reporting to the Mac once a second) read exactly 60 a second,
16.6 ms apart, in Brave and in Samsung Internet on the desktop with 120 Hz held — the
desktop's display reporting 60 Hz, as scrcpy makes it, and again with the display made
to report 120 (Android 14's `VirtualDisplayConfig.setRequestedRefreshRate`, a fifth
server patch, tried and reverted) — and in Samsung Internet on the phone's own screen,
lit, at 120 Hz. These browsers keep their animation to 60 on this phone wherever they
are; there is nothing for the desktop to fix. Chrome did not load the page (its first
run, probably) and was not measured.

`OPENDEX_PRINT_FPS=1` adds `--print-fps` to the desktop's scrcpy. On macOS and Linux the
client's stdout is a pipe and fully buffered, so its lines reach the app's log in a lump
when scrcpy exits, not each second; line them up by counting back from the exit.

## Verified, and not yet

Off a phone: `node test/refresh-rate-test.mjs` (16: the rate or the next one down, the
ceiling, endless and missing ceilings, a ceiling below every mode holding nothing — the
60/120 Hz panel under a 30 Hz ceiling included — the size of the phone's default and not
another's, the rates offered); `cargo test` (the `HZ` reply parser, the stored value).

On the S25 Ultra (Android 17), from this Mac over Wi-Fi: the Smoothness card listing
Automatic, 120, 80, 60 Hz; picking 120 there reaching the daemon through the PC in
17 ms and SurfaceFlinger's policy reading `[120, 120]`; the measurements above;
Automatic, 80 and 120 changed while dark, the held rates each relighting the panel
(`screen: panel on/off` in `wmd.log`); the daemon's watchdog giving a rate back when no
desktop was there (a second daemon on a spare port) and, with the current build, when a
real desktop was closed — it relit the panel and gave the rate back within a second,
before the app's own exit path, which then found nothing to do; the policy back to the
phone's own `[10, 120]` afterwards. With the current build: the phone's ceiling lowered
through `settings put system peak_refresh_rate 60` — the hold followed to `[60, 60]` —
and raised again by deleting it — back to `[120, 120]`; the setting is null again, as it
was before the tests.

The FPS readout, same phone, phone screen dark, 10 s of continuous flings, the daemon
asked twice a second: 120 Hz read 108, 132, then 120 for six seconds; Automatic read
56, then 60 throughout; at rest 0 or 1. The series are in the evidence folder
(`fps-readout-series.txt`, copied from the runs' terminal output). Seen in the gauge
(`FPS 120 · CPU 26% · RAM 75% · DISK 20%` mid-fling, archived) and as the Task Manager's
Frame rate card, first, before Processor (`37 fps`, archived). With Settings' list run
against its end the whole chain read about 60 at a held 120 — the app drew at 62 (the
overscroll stretch takes longer than a frame), SurfaceFlinger's cadence 62, the readout
54–57. Short trains of flings read 30–45 while the app drew at 120: the list stops
between flings, and a second that is half still is half the frames.

The gauge's CPU, RAM and DISK figures had never shown: the labels took their column's
default MATCH_PARENT width, a column that wraps its content then sizes itself by the bar
(34dp), and "RAM 70%" broke at the space. The labels wrap their content now, at least as
wide as their widest figure; the view tree read back "CPU 9%", "RAM 70%" and "DISK 20%"
before the fix while the screen showed only the captions.

Not yet:

- **Android 12–16** — the single-spec setter and the flat-float specs are written, never
  run. The Galaxy S24 (Android 15) is the next phone to try.
- **Battery saver and heat themselves** — the ceiling was moved with the peak-refresh
  setting, not by either of them; a ceiling below every mode was checked off the phone
  only.
- **A give-back that fails** — the `ERR` answer, the warning and the retry are written;
  no failure was provoked on a phone.
- **The Task Manager's cards at one height** — the frame rate's caption takes two lines,
  so the cards now share the tallest's height; built and installed, never seen on screen
  (the archived capture is from the build before, its card taller than the rest).
- **Windows and Linux hosts**, **a foldable**. (The cable was run on the S25: frame
  rate and lag above.)
- **Lag over Wi-Fi** — no clock-and-capture run there; what the user felt is not
  explained beyond the queue figures.
- **Hours-long sessions** — the tick and the FPS socket have run for minutes.
- **The app killed outright**, not closed: nothing on the computer gives the rate back,
  and if the daemon goes too before its watchdog's next tick, the policy stays pinned
  until the phone sends one of its own. Seen once, from stopping the dev build: still
  `[120, 120]` afterwards; the phone's next change of policy (here forced by setting and
  deleting `min_refresh_rate`) put its own `[10, 120]` back. How soon that comes by
  itself — a touch on the phone, battery saver, heat — has not been timed.
