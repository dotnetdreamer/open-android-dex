# openandroiddex-linux

Ubuntu 24.04 on the DeX desktop — **no root anywhere**. The distro runs under
[proot](https://github.com/green-green-avk/build-proot-android) (a ptrace
userspace chroot) as the **launcher app's own uid**, in the app's private
storage — no shell uid, no daemon, no PC. Display is XFCE4 through TigerVNC's
Xvnc, rendered by noVNC in a WebView window on the desktop display, with
Firefox and Chromium installed.

This directory holds the four on-device scripts — setup, the desktop runtime,
`linux-ssh.sh`, remote access's sshd (see `doc/remote-access.md`), and
`linux-win.sh`, the second X display Linux apps open on as windows of their
own (see `doc/linux-windows.md`) — which
`open-android-dex-tauri/scripts/build-launcher-apk.mjs` copies into the
launcher's assets on every build. proot itself is **committed** to the
launcher's jniLibs for the two ABIs we stage — **arm64-v8a** (real phones) and
**x86_64** (the Android emulator); `proot/` rebuilds those binaries and explains
why we do not use the upstream prebuilts. `sftp-shim/` builds the small library
remote access preloads into the guest's sftp-server, without which SFTP — scp,
sftp, WinSCP — cannot run under proot (its header says why); the two builds are
committed under the launcher's `assets/linux/sftp/`. The Ubuntu base rootfs is
downloaded by the app on first run.

Verified end-to-end on an x86_64 Android 16 emulator (2026-08-18): full XFCE
desktop, terminal, input, VncAuth. Not yet re-verified on the arm64 phone.

## Flow

1. `LinuxService.provision` runs on every launcher start and whenever the Linux
   window opens. It is idempotent: it downloads and sha256-verifies the Ubuntu
   base tarball if it is missing, then runs `linux-setup.sh <version> <features>`.
2. `linux-setup.sh` phases: extract rootfs → configure → `apt-get update` →
   install XFCE4 + TigerVNC + noVNC → `vncpass` + xstartup → **git** →
   **Node.js** → **GIMP** → **browsers** → shared folder → VS Code →
   **IntelliJ IDEA** → dock → ready. The optional apps (Firefox, Chromium,
   VS Code, git, Node.js, GIMP, IntelliJ) run only when the app chooser's tick
   list — handed over as `LINUX_APPS`, echoed back into `apps.done` on
   success — asks for them. Each phase drops a `.stamp-*` file,
   so re-runs resume; `setup.pid`
   guards against double-runs. `repair_dpkg` runs before the install so an
   interrupted unpack never wedges the rootfs permanently — `dpkg --configure -a`
   alone, which is all this used to do, does not, and the missing rungs are what
   turned one killed run into a permanent **Retry loop**. See **A wedged desktop
   phase** below.
3. The Linux tile launches `LinuxActivity`, which polls `Linux.readStatus`
   (plain files, no daemon) and, once ready, has `LinuxService.start` write
   `geometry`, copy `linux-rt.sh` fresh from the APK — as it does the viewer
   page, and `startWin` does `linux-win.sh` — and spawn it. Before, only a
   provision copied it, so a guest already set up kept the script it was built
   with until a `FEATURE_LEVEL` bump re-ran the whole setup for everyone.
4. `linux-rt.sh` re-execs itself under `setsid`, writes its own pid to
   `rt.pid`, and runs proot as a child. Stopping is one
   `/system/bin/kill -9 -<rt.pid>` — see **Process-group teardown**.

## Reinstall vs Uninstall

Both live on the Linux tile's right-click menu, and they are not variants of
each other. **Reinstall** wipes and provisions again — the repair for a
container that is broken. **Uninstall** wipes and stops there, for someone who
wants the ~1.5 GB back.

The difference is entirely in what does *not* follow the wipe, and one thing
has to be remembered for it to survive at all: `LinuxService.provision` runs on
every launcher start, so an uninstall would be undone — and everything it freed
downloaded again — about three seconds into the next desktop session. So
uninstall writes `files/linux-uninstalled`, a **sibling** of the container
rather than a file in it (the wipe would take it with the tree otherwise), and
`Linux.needsProvision` returns false while it exists. `LinuxService.provision`
re-checks it on the service side too, because the caller decides on its own
thread and then sends an intent — an uninstall landing in that gap would be
reversed by a decision taken before it.

Opening Linux clears the marker, and that is deliberately the *only* thing that
does: an explicit request for Linux is the way back in, while the desktop's own
provision-on-launch must never count as one. An open Linux window closes itself
when it sees the marker, since nothing it polls for could arrive.

The **shared folder is never touched** by either. It sits at the top of external
storage precisely so it belongs to the user rather than to the container.

## Two version numbers

`Linux.PAYLOAD_VERSION` and `Linux.FEATURE_LEVEL` (both passed to
`linux-setup.sh`, as `$1` and `$2`) answer different questions and must never be
conflated:

| | bump means | cost |
|---|---|---|
| `PAYLOAD_VERSION` (`$1`) | the guest must be rebuilt from scratch | wipes the stamps, re-extracts the rootfs — **a container reset**, everything the user installed is gone |
| `FEATURE_LEVEL` (`$2`) | this build installs something new *inside* the guest | re-runs the setup script; the stamps carry it straight to the new phase |

`LinuxService.provision` treats a guest as done only when the version matches,
the phase is `ready`, **and** its `FEATURES` is at least this build's. Adding
the browsers to a working Ubuntu was a feature bump for exactly that reason —
doing it as a version bump would have cost every existing user their container.

## Session lifetime and shutdown

The XFCE session **is** the runtime's lifetime. `linux-rt.sh` starts Xvnc,
dbus, the session and websockify, then `wait`s on `xfce4-session`; when that
ends — a guest-side logout, or a crash — Xvnc and websockify are killed behind
it, proot exits, and `rt.pid` is removed. Before this, websockify was the
foreground process and outlived the desktop, so a logout left a live X server
with nothing on it and reconnecting showed a **black screen** that no amount of
retrying could fix.

When the session ends, the script writes its exit status to `rt.exit`. The
file's **existence** is the load-bearing part: the viewer starts a runtime
whenever none is running, so with no marker a logout silently booted a fresh
desktop the instant the old one went away — which is not what "log out" means.
The number in it only chooses the wording, and it comes from `xfce4-session`
straight through proot: **0 = the user logged out**, anything else = the session
fell over. That status is the only honest way to tell those apart; duration is
not, and an earlier "under 15 seconds means it crashed" rule called a prompt
second logout a crash. A kill from the app side never reaches that line (the
whole process group dies at once), so it leaves no marker and the next open
starts clean.

Ways the container goes down, all of which end in the same group kill:

- the window's caption ✕ — routed to `LinuxActivity` as `ACTION_CLOSE_WINDOW`
  rather than removing the task, so it can **ask first** (a close ends a session
  the user may have work inside; minimising is the way to keep it running)
- `LinuxActivity.onDestroy`, unless the activity is only being recreated for a
  configuration change
- **Log Out** inside XFCE
- the PC force-stopping the launcher at session end

XFCE's own **Shut Down** and **Restart** buttons stay greyed out and always
will: `xfce4-session` asks `org.freedesktop.login1` over the *system* bus
whether it may, and a proot container has neither systemd nor a system bus.
There is nothing to shut down but the session, which is what Log Out does.

## Process-group teardown

`linux-rt.sh` makes itself a session leader so its pid doubles as a
process-group id, and the container is killed with a single
`/system/bin/kill -9 -<rt.pid>` that reaches proot and every guest process at
once. That indirection exists because the app uid cannot browse `/proc`: the
old identity check (`grep rootfs /proc/$P/cmdline`) could never pass and simply
skipped the kill, and the `pkill -f` belts behind it match on cmdlines they
equally cannot read — which is why closing the window left the container
running. `kill(2)` needs no `/proc` at all.

Three measured details hold it together:

- **`setsid sh "$0"`, never `setsid "$0"`.** setsid EXECS its argument, and the
  script lives in app storage, which is neither executable nor allowed to be
  (W^X). Handing setsid the interpreter means nothing execs the script.
  Measured: `setsid /path/to/script` → `exec: Permission denied`.
- **`/system/bin/kill`, never the shell builtin.** toybox sh's own `kill`
  rejects a negative pid: `arguments must be jobs or process IDs`.
- **Nothing may daemonise out of the group.** `dbus-daemon` therefore runs
  `--nofork`; a forking dbus calls `setsid` and escapes the one kill that
  matters.

## Browsers

Ubuntu 24.04's `firefox` and `chromium` packages are **snap transitional
stubs** — they install a shim that shells out to snapd, which cannot exist in a
proot container. That stub is the whole reason XFCE answered *"Failed to
execute default web browser"*: there was no browser behind the name. Both now
come from real .deb sources:

| | source | arches |
|---|---|---|
| Firefox | Mozilla's own APT repo, pinned above the Ubuntu archive so the snap stub can never win the name back | amd64, arm64 |
| Chromium | the [xtradeb](https://launchpad.net/~xtradeb/+archive/ubuntu/apps) PPA — the maintained noble chromium .deb | amd64, arm64 |

Chromium is deliberately **non-fatal**: it comes from a third-party PPA, and a
guest with Firefox in it is a working guest.

**Android has no `/dev/shm`** (measured: `ls /dev/shm` → no such file), and we
bind Android's `/dev` in, which shadows the rootfs's own empty one — so POSIX
shared memory did not exist in the guest at all. Chromium papers over that with
`--disable-dev-shm-usage`; **Firefox crashed on launch**, because it falls back
to `shm_open` once `memfd_create` is unavailable under proot, and there was
nothing to fall back to. `linux-rt.sh` now binds `$ROOT/shm` over `/dev/shm`;
proot resolves the longer bind first, so it wins over `-b /dev`.

## Finding the apps

Installing an app is not the same as being able to find it. Firefox, Chromium
and VS Code each get a `.desktop` entry we own in `/usr/local/share/applications`
— ours rather than the packages', because they point at the `dex-*` wrappers
(an apt upgrade rewrites a packaged one straight back to the unwrapped binary)
and because we can decline to create the entry for an app whose install failed,
instead of leaving a dead icon behind. Each entry is also copied to
`/root/Desktop` with the executable bit set, which is what xfdesktop reads as
"trusted".

The dock is **panel-2** on a stock Ubuntu XFCE — the bottom strip that already
holds the terminal/file-manager/browser launchers. `panel-1` is the top bar
with the menu and the clock, and putting app launchers up there next to the
clock is not what anyone means by the dock, so `dock.py` targets whichever panel
already contains launcher plugins and inserts beside them. It edits the config
rather than authoring one: inventing panel positions, sizes and plugin sets is
how you hand someone a broken desktop. It needs full `python3` — **not**
`python3-minimal`, which ships without `shutil` *or* `ElementTree`, so the edit
failed silently (measured).

`xfce4-panel` writes its config out when it **exits**, so an edit made while a
session is live is not merely late — it is *discarded* on the way out, which is
why launchers added at provision time never appeared at all. `linux-rt.sh`
therefore runs `dock.py` again a moment before starting XFCE, when no panel is
holding the config. It takes no arguments: it adds whatever is in the launcher
directory, so an app that only installs later (VS Code retrying) is picked up
by the next session with nothing to keep in step.

The launcher templates live in `/usr/local/share/openandroiddex/launchers`,
**not** `/usr/local/share/applications` — that path is on `XDG_DATA_DIRS`, so
entries there join the Applications menu, and since ours carry the same `Name`
as the packages' own the Internet menu showed "Firefox" and "Chromium" twice.
The packaged `.desktop` files, whose `Exec` we rewrite to the wrappers, are what
the menu should show.

The dock phase is deliberately **unstamped**, because the app it would have
added may only have installed on this pass; everything it does is idempotent.

### The dock sits on the LEFT

`dock.py` also moves that panel to the left edge — vertical, **full height**,
with the desktop icons pushed clear of it, which is the Ubuntu arrangement. The
numbers are xfce4-panel's own rather than guesses, and the types matter as much
as the values:

| property | type | value | meaning |
| --- | --- | --- | --- |
| `mode` | `uint` | `1` | `XFCE_PANEL_PLUGIN_MODE_VERTICAL`. `0` is horizontal; `2` is *deskbar* — a vertical panel with **horizontal** plugins, which lays the launchers out in rows and is the wrong one. |
| `position` | `string` | `p=8;x=0;y=0` | `p` is a `SnapPosition`: `8` = `SNAP_POSITION_SW`, the **bottom** of the left edge, so the panel grows upward and stops short of the top bar. `5` (`W`, the whole left edge) is the fallback when there is no top bar to keep clear of. |
| `length` | `double` | computed | A **percent** of the edge, 1–100, a `double` since 4.18. Screen height less the top bar's height, so the two edges meet exactly — no overlap, no gap. |
| `length-adjust` | `bool` | `false` | The length is deliberate; nothing should grow it. |
| `size` | `uint` | `48` | Thickness in px, range 16–128. The panel's own default, and about what Ubuntu's launcher measures. |
| `autohide-behavior` | `uint` | `0` | `AUTOHIDE_BEHAVIOR_NEVER`. **This is the one that makes the icons move** — see below. |
| `enable-struts` | `bool` | `true` | A panel reserves its space by publishing `_NET_WM_STRUT_PARTIAL`, and xfdesktop lays icons out inside the work area that leaves. Necessary, not sufficient. |

**Autohide is why the icons sat under the dock**, and it cost two wrong
guesses to find. `panel_window_screen_struts_edge` opens with

```c
if (window->autohide_behavior != AUTOHIDE_BEHAVIOR_NEVER
    || ! window->struts_enabled)
  return STRUTS_EDGE_NONE;
```

so a panel that autohides publishes **no strut at all** — before the function
ever looks at the edge, the length, or `enable-struts`. Ubuntu ships this panel
with autohide on, which is also why it *looked* fine: with no window open there
is nothing to hide from, so it stayed visible while reserving nothing, and
xfdesktop went on laying icons from `x=0` with the dock drawn straight over
them. Setting the position, the length and `enable-struts` changed the result
not at all until `autohide-behavior` went in beside them.

**A shorter panel still reserves the whole edge**, which is what lets the dock
stop below the top bar without giving anything up. xfwm4's `workspaceUpdateArea`
does

```c
screen_info->margins[STRUTS_LEFT] =
    MAX(screen_info->margins[STRUTS_LEFT], c->struts[STRUTS_LEFT]);
```

and the strut's start/end y reach that line only through `strutsToRectangles`,
whose rectangle is used for exactly one thing: testing that the strut
intersects the primary monitor at all. The margin itself is the full strut
value. So a partial-height left panel takes the same 48px off the work area as
a full-height one. (An earlier note here claimed the opposite — that a partial
strut could not shrink a rectangular work area. That was wrong; autohide was
always the only blocker.)

**The top-left corner needs the dock to yield.** Struts do not arbitrate
between panels — only between a panel and ordinary windows — so a full-height
dock and a full-width top bar simply both own the corner, and the later-mapped
one wins, which is how the Applications button ended up underneath. The dock
stopping at the top bar's bottom edge is the fix, and the reason its length is
computed from the geometry rather than fixed.

Nothing has to be reset for the icons to move: xfdesktop filters
`PropertyNotify` for `_NET_WORKAREA` on the root window and re-runs its grid
resize, so they reflow as soon as the strut lands.

Two guards decide whether it happens at all. It is applied **once** per guest,
marked at `/root/.config/openandroiddex/dock-positioned` — dragging the dock
back to the bottom is a reasonable thing to do, and re-imposing the position at
every launch would leave no way to make that stick. That marker holds a **layout
number**, not merely existing, for the same reason `FEATURE_LEVEL` does:
layouts 1 and 2 were the short centred strip and the strutless full-height bar,
and a bare "already done" marker would have left every guest that got one of
them stuck there forever. And the move is skipped entirely
when the layout has only **one** panel: that is not a dock beside a top bar, it
is the desktop's only strip. Adding *launchers* stays per-boot either way,
because an app installed later still has to get one.

## What a start and an install cost

Measured on the S25 Ultra (SM-S938B, Android 17, kernel 6.6, proot in seccomp
mode), 2026-11-07, in scratch guests under `/data/local/tmp`: the real scripts,
our `libproot.so`, the pinned rootfs and the same mirror, run as the shell user
so that nothing of the app was touched — and then, for what only the app can
show, in the app itself (**In the app**, below).

**Starting the desktop.** `linux-rt.sh` used to start Xvnc, sleep 2 s, start
the bus, sleep 1 s, run `dock.py` and then `theme.py`, start the sound server,
sleep 1 s, and only then XFCE — four seconds of fixed sleeps whether the phone
needed them or not, with nothing checking that they had been enough. Now the
bus, the sound server and both config edits start beside Xvnc, and XFCE waits
on each by asking it — `xset q` for the display, the bus's own
`--print-address`, `wait` for the edits, `pactl info` for the sound — the way
`linux-win.sh` already waited for its own. `theme.py` is a one-shot migration,
and once its marker exists it is no longer started just to exit. Seconds from
the spawn of `linux-rt.sh`, the old script and the new alternating on each
guest after a warm-up start:

| guest built by | script | runs | websockify listening | XFCE's panels and desktop managed |
|---|---|---|---|---|
| the stock setup | before | 4 | 4.46–4.51 | 5.17–5.21 |
| | after | 4 | 0.56–0.58 | 1.26–1.29 |
| this setup | before | 2 | 4.34–4.36 | 5.14–5.17 |
| | after | 2 | 0.52–0.53 | 1.30–1.31 |

"Managed" is xfwm4's `_NET_CLIENT_LIST` reaching its final count, read with
`xprop` from a second proot. That is the script's share of a start and nothing
more. Between the tile and a picture in the window there is also the app's
side — the service spawning the script, `LinuxActivity`'s status poll, the page
load and its retries, noVNC's connect and first paint, and the health probe
that takes the card down — and the two builds in **In the app** differ in none
of it, so there the window gains less than the script does, and nothing like
the same ratio. That side has a change of its own in this tree: while the card
is up, the window polls and probes every 250 ms rather than every 1.5 s, and
its first eight page loads come 250 ms apart rather than 1 s.
`doc/linux-viewer.md` has its measurement on the S25: the card down at
1.7–2.0 s from the launch instead of 3.3–3.4 s. `LinuxService` copies the
script from the APK at every spawn now (**Flow**, step 3), so the change
reaches every existing guest without a `FEATURE_LEVEL` bump.

**In the app.** LinuxOnDroid's debug build — not debuggable, as it ships —
installed beside the launcher on the same phone, twice: once as this tree, once
with git HEAD's `linux-rt.sh` and `dex.css` in its assets, nothing else
different. Measured from outside, as any shell may: the screen, `/proc`.

A start is cold — the app force-stopped, 5 s, then the launcher icon's own
intent — and timed to the moment two points of the window, clear of the
pointer ring, turn XFCE's desktop colour, which happens only once the card is
down on a painted desktop. The screen was read every 0.26 s, so every time is
late by up to that much, the same for both:

| build | starts | launch to the desktop on screen |
|---|---|---|
| before | 4 | 6.24–6.42 s |
| after | 7 | 3.18–3.34 s |

The window shows its card at about half a second in both. That it gains about
3.1 s rather than the script's 3.9 s is most likely the app's own polls — a
status poll every 1.5 s, a page load retried every 1 s, the 1.5 s health probe
— landing every start on their own ticks; that part is inferred, not measured. The first start after each install is left out: the
install had restarted the app already, and one such run began with its window
up.

An idle desktop — a cold start left alone for 20 s, then 60 s of CPU ticks from
`/proc/<pid>/stat` (100 to a CPU-second), two windows per build:

| build | WebView renderer | the app's own process | the rest of the app's uid |
|---|---|---|---|
| before (the status dot pulsing forever) | 1,797 | 1,937 and 2,263 | 15–16 |
| after (three pulses) | 7 and 8 | 6 and 8 | 9–11 |

About 41 CPU-seconds a minute against a quarter of one: two-thirds of a core
spent redrawing a dot on a desktop where nothing moved, the renderer painting
it and the app process compositing it. The renderer is the sandboxed process
that appeared with the launch; in one of the two "before" windows it could not
be told apart, hence one number. The two builds' only difference that outlives
the start is `dex.css`.

The panel's red microphone, in both builds, is xfce4-pulseaudio-plugin's
recording indicator: the app's own sound tap records the null sink's monitor.
Nothing records a microphone.

**Installing.** Stock against this build with no optional apps
(`LINUX_APPS=none`): a bare desktop. The default selection — Firefox, Chromium,
VS Code and git — was not measured. The same packages leave it, so the saving
in packages and megabytes should carry over, but it is a smaller share of a
much longer install. Same phone and mirror; the download times are the
network's. Transaction times are apt's own, from `/var/log/apt/history.log`.
In the app, the same bare desktop took 203 s of setup script, 216 s from the
Install tap — with no in-app run of the old setup to set beside it:

| | before (1 run) | after (2 runs) |
|---|---|---|
| the whole setup | 235 s | 154 s, 154 s |
| the desktop's apt transaction | 123 s, 314 packages | 49 s and 63 s, 235 packages |
| the desktop's download | 161 MB | 126 MB |
| the first `apt-get update` | 37.4 MB | 28.8 MB |
| the container on disk (`du`, apt's `.deb` cache included) | 1,442 MB | 1,126 MB |

Where it comes from, in `linux-setup.sh`:

- **noVNC and websockify as files** (`novnc_files`): their packages pulled in
  70 others — nodejs, numpy with LAPACK and gfortran, OpenStack's oslo, babel's
  locale data — 139 MB and 5,208 paths for 1.2 MB of web root and a few Python
  modules. Checked on a guest this setup built: noVNC's own `vnc_lite.html`,
  in a desktop browser through `adb forward`, logged in with VncAuth, drew the
  desktop, opened the Applications menu on a click and ran a typed `uname -m`
  in a terminal; the sound server and the 6081 tap the app plays from were up
  beside it. Our own viewer page was not part of that — the app stages it.
- **Their updates are the app's, not apt's.** The files belong to no package,
  so `apt upgrade` passes them by; `NOVNC_SYNC` is the policy (see
  `novnc_files`), and both of its paths were run on device. A bump re-took the
  archive's versions on a guest that already had a desktop — 5 s, and the
  record in `/usr/local/share/openandroiddex/novnc-files` rewritten — and a
  refresh with no way to download logged its failure and kept the files and
  the record it had, for the next pass to try again.
- **Two icon themes off the disk**: `ubuntu-mono` and `humanity-icon-theme`
  stay installed for `adwaita-icon-theme`'s sake, but a dpkg `path-exclude`
  keeps their 11,390 files — over a quarter of every path in the guest — from
  being unpacked. Nothing selects them. Fresh desktops only.
- **No `Translation-en`** (`Acquire::Languages "none"`): Ubuntu's Packages
  indexes already carry every description.
- **Up to two fewer apt runs per setup pass**: no second `apt-get update` in
  the SSH step when the same run fetched the lists minutes earlier, and no
  `apt-get install python3` when dpkg's status file already has it (`pkg_ok`).

**Measured and not taken: `force-unsafe-io`.** dpkg without an fsync per file
is the usual container speed-up, but the desktop's `apt-get install`, from a
warm `.deb` cache, took 127 s with it against 116 s and 135 s for two stock
runs — inside the noise. `/data` on this phone is f2fs mounted
`fsync_mode=nobarrier`, so an fsync is cheap already, and the option gives up
dpkg's crash safety for nothing measurable.

**Found and not fixed: fchmodat2.** Linux 6.6 added `fchmodat2`, and glibc 2.39
sends every `fchmodat(…, AT_SYMLINK_NOFOLLOW)` through it. Our proot predates
the syscall, so it reaches the kernel with the guest's path untranslated:
`os.chmod(p, m, follow_symlinks=False)` fails with ENOENT on any path in the
guest, and GNU tar unpacking over a directory that already exists exits 2
("Cannot change mode … No such file or directory"). Unpacking into a fresh
directory is unaffected, which is why `extract_tarball` works and why
`novnc_files` moves its trees into place instead of unpacking over `/`.
Rebuilding proot will not fix it on its own: `proot/build-proot.sh` relinks
`v0.15_release` unchanged apart from the 16 KB alignment. It needs proot's
source patched first — `fchmodat2` added to the syscalls whose path it
translates, beside `fchmodat` — or a proot that already has that, and a
patched proot is a change of behaviour, so it needs the device checks a
straight relink did not.

## Device-measured constraints (do not regress)

- **Hardlinks**: our uid may not `link()` on /data (SELinux). The rootfs
  extraction runs through `proot --link2symlink`; plain tar dies on
  perl/gunzip.
- **Filesystem unix sockets on /data: denied; abstract sockets: allowed.**
  Hence dbus-daemon is started by hand on `unix:abstract=dex-session-bus`
  (dbus-launch's `/tmp/dbus-XXXX` bind dies with EACCES and takes the session
  with it), and X clients reach Xvnc through the abstract `@/tmp/.X11-unix/X1`
  Xorg always binds alongside the (failing) file socket.
- **toybox `pkill -s` is a silent no-op** — nothing may rely on session-id
  kills. Kill by pid file + argv patterns instead.
- **toybox `pkill -9 -f` / `pkill -KILL -f` KILLS THE CALLER** (signal-flag
  misparse). The only safe spellings are `pkill -f` (SIGTERM) and
  `pkill -l KILL -f`.
- **proot never handles SIGTERM** (sits in waitpid), and a SIGKILLed tracer
  cannot reap tracees — hence pid kill *plus* per-guest belt pkills.
- **`tar` over an existing directory fails on a 6.6 kernel** — fchmodat2, see
  **What a start and an install cost**. Unpack into a fresh directory and move
  the tree into place, as `extract_tarball` and `novnc_files` do.
- **pkill self-match**: the bracket trick only covers the pattern's own text.
  Any *other* plain occurrence of a target word in the same command line is a
  self-kill. Also: `websockify` is an apt **package name** — the belt must
  anchor on `--web` or it SIGKILLs apt mid-install and wedges dpkg.

## state.env

`/data/local/tmp/linux/state.env`, `KEY=VALUE` lines, always written
atomically (`state.env.tmp` + `mv`):

    VERSION=<int>  PHASE=<phase>  PCT=<0-100>  MSG=<no-whitespace, dash-separated>
    STEP=<step, or empty>  STEPPCT=<0-100, or -1>

`MSG` may also carry `<verb>:<name>` — `setting-up:libgtk-3-0t64` — when the
tail half is a name the script did not invent. `LinuxActivity.msgWords`
de-dashes only the verb, so the package keeps the dashes that belong to it. The
desktop phase publishes this every 5 s from apt's own transcript (`apt_msg`);
the optional steps publish every 5 s too (`step_begin`, see **Skip, Restart and
a line that moves**); every other phase sends a plain dash-separated token and
reads unchanged.

`STEP` names the step a run is on — a chooser app id, or `desktop` — and
`STEPPCT` how far through it is. After a failure `STEP` still names the step
that failed, which is how the error screen knows to offer Skip.
    phases: pushing extracting configuring apt-update installing-desktop ready error

## Ports (guest, loopback only)

| port | what |
|---|---|
| 5901 | Xvnc `:1` (VncAuth, password in `$ROOT/vncpass`) |
| 6080 | websockify + noVNC http — `http://127.0.0.1:6080/dex.html?password=<vncpass>&v=<rtpid>`, our viewer page (`doc/linux-viewer.md`), staged into the web root beside Ubuntu's own `vnc.html` and `vnc_lite.html` |
| 6081 | the PulseAudio tap — raw s16le/48k/stereo, read by `LinuxAudio` in the app (`doc/linux-audio.md`) |
| 4713 | the PulseAudio control protocol, for `pavucontrol`, the panel plugin and anything linking libpulse |
| 7022 | sshd, while remote access is on — from `linux-ssh.sh`, in a proot of its own. Keys only; reached from outside the phone only through the dexremote tunnel. Another free port when 7022 is taken |
| 5902 | Xvnc `:2`, the window display (`linux-win.sh`), for a VNC viewer if anyone wants to see it bare — VncAuth, the desktop's password. Its X socket is the abstract `@/tmp/.X11-unix/X2`, where the launcher is the window manager; every X client needs the session's cookie (`/tmp/.oadx-win.xauth`) |
| 6082 | the window session's PulseAudio tap, read by `LinuxAudio` like 6081 — a daemon of its own (`/tmp/.oadx-win.pa`: `dex.pa` with each port one higher), so neither session's sound depends on the other |
| 4714 | the window session's PulseAudio control protocol; `PULSE_SERVER` for every app opened as a window |

## Sound

There is no audio device in the container — Android's `/dev` is bound in, but
its audio nodes belong to the media uid — so the guest gets a PulseAudio
**null sink** and the app drains that sink's **monitor** over loopback TCP into
an `AudioTrack`. `setup_audio` installs pulseaudio, pavucontrol and the panel
plugin and writes `/etc/pulse/dex.pa`; `linux-rt.sh` starts the daemon with
`-n --file=` that, in its own process group, and kills it with the session.
`linux-win.sh` runs a second daemon from the same file, each port one higher,
for apps opened as windows of their own (`doc/linux-windows.md`).
Design record: `doc/linux-audio.md`.

Two consequences worth knowing before changing any of it. The control protocol
is on **TCP**, not a unix socket, for the same SELinux reason dbus takes an
abstract address — and because of that `/etc/pulse/client.conf.d/dex.conf` must
keep `autospawn = no`, or a client that cannot find a socket starts a second
daemon off the stock `default.pa`. And `module-suspend-on-idle` must stay
unloaded: a suspended null sink stops its monitor, which is the whole audio
path.

## Diagnosing a failed phase

`note()` writes each milestone to **logcat** as well as to `setup.log`, and
`guest_or_note` reports the tail of a failed guest command the same way. The PC
folds `logcat -s OpenDeX` into its session trace, while `setup.log` lives in
private storage a non-debuggable build cannot read — so without this, "VS Code
did not install" was unanswerable from the outside. `LinuxService` also logs
`firefox=… chromium=… code=…` after every setup run.

The desktop install is the one phase `guest_or_note` cannot wrap — capturing a
300-package transcript into a shell variable would also take the live stream out
of `setup.log`, which is what anyone watching an install is tailing. It streams,
and `apt_note` lifts apt's own `E:` / `dpkg: error` lines back out of the log
into logcat when it fails. Before that, the biggest and most interruption-prone
phase was the only one that explained nothing.

`share_log` **chmods its copy 0644**. `setup.log` is created by `LinuxService`'s
`ProcessBuilder` redirect under an app umask of 077, so it is 0600 and `cp`
carries that straight over: `adb pull` answered *"Permission denied"*, and the
one escape hatch that exists so diagnosis does not need a debuggable build did
not actually work.

## A wedged desktop phase

Measured on an S25 (Android 16, arm64, 2026-08-19). The symptom is **"Linux
setup failed / installing desktop"** that returns within ~3.5 s of every Retry —
far too fast to be the ~1.5 GB the phase actually downloads, which is the tell
that apt refused at the dependency check *before* fetching anything.

What it is **not**: the same package set, the same `libproot.so`, the same
pinned rootfs image and the same `sources.list` installed all 311 packages
cleanly into a scratch guest on the same phone at the same moment
(`REAL_INSTALL_RC=0`). Network, mirror, disk, package set, seccomp mode and
arm64 itself are all exonerated by that.

What it is: `runSetup` spawns the script as a plain `ProcessBuilder` child of
the `:linux` process and blocks in `waitFor`, so when Android replaces that
process the in-flight install dies with it. The observed casualty was
**`perl-base`, left half-installed** (`iH`):

    dpkg: error processing package perl-base (--configure):
     package is in a very bad inconsistent state; you should
     reinstall it before attempting configuration

`Need to get 0 B/158 MB of archives` in the same transcript places the kill
precisely: every `.deb` had already been fetched, so it landed during **unpack**,
not download. That is why the retries were so fast — apt had nothing to fetch
and refused at the dependency check.

Neither `dpkg --configure -a` nor `apt-get --fix-broken install` clears it: both
only ever CONFIGURE, and this package's *unpack* is what never finished. Only
re-unpacking does, which is `repair_dpkg`'s third rung — plain install first,
`--reinstall` behind it. Both branches are verified on device; see the function's
comment for why that order is the load-bearing part.

A guest already wedged this way heals on the next run, because a guest in
`error` never takes the "already provisioned" early-out and so always re-copies
the script.

Worth knowing: the setup run **is** a session of its own now, like
`linux-rt.sh` (the same `exec /system/bin/setsid /system/bin/sh "$0"` re-exec),
so that Skip and Restart can stop one run — script, proot and every guest
process — with one group kill. That changes nothing about dying *with* the app:
a force-stop takes the app's processes whatever session they are in, which is
how `linux-rt.sh` goes down with the desktop too. Recovery is still handled
above rather than by making the install outlive its process — and the install
now picks itself up straight after the kill; see **Picking up after the
desktop ends**.

## Skip, Restart and a line that moves

Measured on the S25 (2026-11-02): the browsers step — Firefox, then the 86 MB
Chromium from the xtradeb PPA — takes about 8 minutes on a phone, and it used to
publish `install-browsers` at 93 % once and nothing after. A line that never
moves reads as a hang, so people left the desktop on it — which killed the
step (below), which the next desktop began again. Three things answer that:

- **A live line.** `step_begin <step> <pct> <verb:name> [file size]` starts a
  ticker that re-publishes `state.env` every 5 s. For apt it reads apt's own
  machine-readable progress: `apt_in` adds `-o APT::Status-Fd=3` and points fd 3
  at `/tmp/.oadx-apt-status` (`dlstatus:<n>:<pct>:…` while downloading,
  `pmstatus:<pkg>:<pct>:…` while dpkg works) — apt-get prints no progress of its
  own when stdout is not a terminal, and most steps capture their output
  anyway. For a tarball (`fetch_tarball`, VS Code and IntelliJ) it is the
  partial file's size against the Content-Length. The window shows it as
  `downloading chromium · 45%`. Percentages are `bytes / (size / 100)`, never
  `bytes * 100 / size`: `/system/bin/sh` is mksh, whose arithmetic is 32-bit.
- **Skip** (the chooser's apps only). `LinuxService.skipStep` takes the app off
  the tick list — exactly as if the chooser had been answered without it, so
  the chooser is also how it comes back — and sends `RERUN`. The run in flight
  is stopped with one group kill (`stopSetup`: `kill -9 -<setup.pid>`) and a
  fresh one starts; the stamps carry it straight back. The error screen puts
  Skip beside Retry when the step that failed is one of the chooser's apps,
  which in practice means a browser: `install_browsers` is the only optional
  step whose failure ends the run. Git, Node.js, GIMP, VS Code and IntelliJ
  note a failure in the log and carry on — VS Code retrying on later
  launches, three times a feature level — so they never reach that screen.
- **Restart** (those, and `desktop`). `LinuxService.restartStep` writes the
  step's id to `step.redo` and sends `RERUN`. The next run reads and deletes it,
  and `redo_clean` drops the step's unfinished downloads (apt's `partial/`, the
  step's tarball) before the step runs again.

A run cut short anywhere inside dpkg — by Skip, Restart or the app being killed
— leaves the next apt-get refusing to work ("dpkg was interrupted"), and only
the desktop phase used to repair for itself. `repair_if_dirty` now runs before
the first optional step of every run: two host-side reads (a non-empty
`/var/lib/dpkg/updates`, or a package in a half state in `status`) and
`repair_dpkg` only when one is true. A package half-unpacked for an app that is
no longer wanted is removed instead of finished, or Skip would install the app
anyway. `drop_unwanted` then clears what a no-longer-wanted app left behind —
its repo (only while the app is not installed: an installed one keeps its
updates), its half-downloaded tarball, a browser `.deb` that never unpacked.

Tarballs resume (`curl -C -`) instead of starting over, but only onto the same
build: the URLs are "latest" redirects, so the build a partial file came from
is recorded beside it (`<file>.url`, its size in `<file>.size`) and a different
answer starts clean. Nothing else throws a partial file away: a probe that
fails resumes onto the recorded build, and a download that fails — a timeout,
a dropped connection, under 1 KB/s for two minutes — keeps every byte for the
next attempt. Only curl 33 (no ranges) or HTTP 416 (a range past the end)
starts the file over.

Tarballs unpack into `/opt/.<tree>-new` and are renamed into place only once
tar has finished (`extract_tarball`). Unpacked straight into place, a run
killed half way left a half tree whose launcher could already be there, and
the "installed?" guards (`vscode_settled`, `setup_intellij`) ask the launcher
alone: the half tree passed for an install, and Restart could not undo it.
`drop_half_tree` takes out such a tree left by the old way — one with its
tarball still beside it, since every extract that ends deletes the tarball —
before Restart's clean-up or the guards ever see it.

Checked (2026-11-02, S25): the helpers themselves, on the phone's own shell —
`node test/linux-setup-test.mjs`, which says what it covers — and a dev build
on the phone: the new launcher installed over the old one in place with the
guest kept, and its first GIMP install found the fault below (fixed, with a
check that fails without the fix). Not yet run with the fix, against a real
guest: apt's status-fd lines under proot (the format is apt's documented one;
the ticker falls back to the step's own word when it reads nothing), Skip and
Restart pressed in the window, a real interrupted unpack being repaired, and
the pickup after leaving the desktop, a reboot and an update.

The ticker is stopped with SIGKILL (`/system/bin/kill -9`). With the script a
session leader, a catchable signal to it made mksh run the script's own TERM
trap once the ticker's sleep ended: "FAILED: interrupted" five seconds into
every run, in its first step. Measured: `kill` and `/system/bin/kill` both did
it under setsid, neither did without it, and `kill -9` never did.

## Picking up after the desktop ends

Leaving the desktop ends every process of the launcher: the desktop app's
`restore_phone` revokes POST_NOTIFICATIONS (Android kills an app whose runtime
permission is revoked — `am_kill … permissions revoked` in the events log) and
then force-stops it, to release display focus. That took the install with it
every time, mid-step. Now `RemoteResumeReceiver`, which the desktop app
broadcasts to right after the force-stop (and which also runs after a reboot
and an app update), checks `Linux.installInterrupted` — `state.env` still
naming a phase on the way, since nothing killed by SIGKILL gets to write
anything else — and starts provisioning again then and there, in the
background. Starting a foreground service from a broadcast is allowed for a
battery-exempt app, so `restore_phone` takes its own exemption back only after
the broadcast; `am broadcast` returns once the receiver has run. From then on
the run holds a partial wake lock (`LinuxService.setupWake`, three hours at
most), so it goes on with the screen off: Doze disables partial wake locks only
for apps below foreground-service state. Not yet measured on a device —
screen off, on battery, after leaving the desktop — and worth doing before
anything here is called finished.

## Script rules

Both scripts must stay POSIX-sh compatible with toybox `/system/bin/sh` (no
bashisms) and keep LF line endings — `.gitattributes` here enforces that. For
which version number to bump when you change one, see **Two version numbers**
above.
