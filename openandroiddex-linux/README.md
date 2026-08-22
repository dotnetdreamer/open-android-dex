# openandroiddex-linux

Ubuntu 24.04 on the DeX desktop — **no root anywhere**. The distro runs under
[proot](https://github.com/green-green-avk/build-proot-android) (a ptrace
userspace chroot) as the **launcher app's own uid**, in the app's private
storage — no shell uid, no daemon, no PC. Display is XFCE4 through TigerVNC's
Xvnc, rendered by noVNC in a WebView window on the desktop display, with
Firefox and Chromium installed.

This directory holds the two on-device scripts, which
`open-android-dex-tauri/scripts/build-launcher-apk.mjs` copies into the
launcher's assets on every build. proot itself is **committed** to the
launcher's jniLibs for the two ABIs we stage — **arm64-v8a** (real phones) and
**x86_64** (the Android emulator); `proot/` rebuilds those binaries and explains
why we do not use the upstream prebuilts. The Ubuntu base rootfs is downloaded
by the app on first run.

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
   `geometry` and spawn `linux-rt.sh`.
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

`dock.py` also moves that panel to the left edge — vertical, centred, and no
longer than its own contents, which is the dash shape the rest of the look is
modelled on. The numbers are xfce4-panel's own rather than guesses, and the
types matter as much as the values:

| property | type | value | meaning |
| --- | --- | --- | --- |
| `mode` | `uint` | `1` | `XFCE_PANEL_PLUGIN_MODE_VERTICAL`. `0` is horizontal; `2` is *deskbar* — a vertical panel with **horizontal** plugins, which lays the launchers out in rows and is the wrong one. |
| `position` | `string` | `p=7;x=0;y=0` | `p` is a `SnapPosition`: `7` = `SNAP_POSITION_WC`, left edge centred. `5` is plain left, `12` the bottom this replaces. `x`/`y` are ignored for a snapped panel, which recomputes them. |
| `length` | `double` | `1` | A **percent**, range 1–100 — and a `double` since 4.18, where it used to be an int. With `length-adjust` on, the panel then grows to fit its plugins, so `1` means "no bigger than it has to be". |
| `length-adjust` | `bool` | `true` | The grow-to-fit half of the pair above. |
| `size` | `uint` | `44` | Thickness in px, range 16–128. |

Two guards decide whether it happens at all. It is applied **once** per guest,
marked at `/root/.config/openandroiddex/dock-positioned` — dragging the dock
back to the bottom is a reasonable thing to do, and re-imposing the position at
every launch would leave no way to make that stick. And it is skipped entirely
when the layout has only **one** panel: that is not a dock beside a top bar, it
is the desktop's only strip, and moving it is not what anyone asked for. Adding
*launchers* stays per-boot either way, because an app installed later still has
to get one.

**VS Code** comes from Microsoft's own APT repo, the only source of a real
`code` .deb for arm64. Its phase is non-fatal and — unlike the browsers —
deliberately *not* stamped on failure, so a failed install is retried on the
next launch rather than hidden forever. Electron is Chromium, so it gets the
same `--no-sandbox` wrapper, doubly required here: no user namespaces, and VS
Code refuses to start as root without it, which is what everything in this
container is.

Neither browser's sandbox can work here — both want user namespaces — so the
whole `MOZ_DISABLE_*_SANDBOX` family is set, not just the content one, and
`security.sandbox.content.level` is forced to 0 through Firefox's **AutoConfig**
mechanism (a pref cannot come from the environment, and AutoConfig reaches
profiles that do not exist yet). Chromium's permanent *"You are using an
unsupported command-line flag: --no-sandbox"* banner is silenced with the
supported enterprise policy `CommandLineFlagSecurityWarningsEnabled` in
`/etc/chromium/policies/managed/`, rather than with `--test-type`, an
undocumented test switch that quietly changes other behaviour too.

**Chromium is the default browser** when it is installed — it is the one
verified working on a real phone under this container, and Firefox asks far
more of the kernel than proot has to give. One word in `tune_browsers` flips it.

Neither runs unwrapped. `/usr/local/bin/dex-firefox` and `dex-chromium` drop
the sandboxes (both want user namespaces, which a ptrace chroot cannot provide)
and, for Chromium, `/dev/shm` and GL, which Xvnc does not have. The installed
`.desktop` entries are rewritten to call the wrappers, because a `.desktop`
names its binary by absolute path and would never see `/usr/local/bin` on PATH.
XFCE's default-browser lookup goes through **exo's helper table**, not `$BROWSER`
or `update-alternatives`, so a helper of our own is installed and selected in
`helpers.rc`; the other two are set as well, as a belt.

## The look

Stock XFCE on stock Ubuntu is Greybird over Adwaita icons over DejaVu, which on
a phone-sized noVNC canvas reads as a desktop from 2011. `setup_theme` installs
`arc-theme`, `adwaita-icon-theme`, `gtk-update-icon-cache` and `fonts-cantarell`
— all `Architecture: all`, so the arm64 phone and the x86_64 emulator get
identical bytes — and picks **Arc-Dark / Adwaita / Cantarell**, falling back
through Materia-dark and Greybird-dark depending on what actually landed.
Nothing is assumed present: a guest whose apt declined still comes out with a
working desktop, and still gets every setting in the second table below.

Arc is picked over `materia-gtk-theme`, which is equally capable, for one
concrete reason: Arc's xfwm4 decoration assets are **PNG** and Materia's are
**SVG-only**, so Arc draws titlebars with no pixbuf loader in the picture at
all. Materia stays in the detection ladder and is honoured if something
installs it — and `theme.py` treats it as a *default* rather than a choice,
because it was our own pick for one build before Arc replaced it.

The window-manager theme is chosen by a **different** test from the GTK theme —
the presence of `xfwm4/themerc` inside it. xfwm4 silently falls back to its
built-in `Default` when the named theme has no `xfwm4/` directory, and Adwaita
is exactly that case, so asking for the GTK directory and assuming the
decorations follow gives themed widgets under stock titlebars.

**Papirus was tried and rejected**, and the reason is worth keeping. It looks
better than Adwaita and covers far more apps, but it is ~82,000 SVG files:
dpkg unpacks every one through proot's ptrace, and then its postinst builds an
icon cache over the lot. On a phone that is a multi-minute stall on a progress
bar that cannot say why it is stalled — measured on device, sitting at *"setting
up papirus-icon-theme"* long enough to look wedged. 200 MB and that wait is the
wrong trade for an icon set, and Adwaita is what the GNOME desktop this look is
modelled on actually ships anyway.

`gtk-update-icon-cache` is still named on the apt line even though
`adwaita-icon-theme` depends on it, because the failure without it is **silent**:
the icon postinsts test for the binary and, finding nothing, do nothing at all
and report no error. With no cache every GTK process rescans the theme directory
to resolve an icon, and under proot each of those path syscalls is
ptrace-mediated.

### Where the settings go, and why it is /etc/xdg

xfconf reads a channel from the system directories **first** and the user's own
file **last**, and it only ever writes the user's file back. So the channels go
in a system directory, which means they are applied to every guest that has not
chosen otherwise, can never be clobbered — xfconfd's five-second debounced flush
and its flush-on-session-exit both target `$XDG_CONFIG_HOME` only — and are
still overridable, because the moment the user picks a theme in the Appearance
dialog it lands in their file and wins.

That directory is **`/usr/local/etc/xdg/xfce4/xfconf/xfce-perchannel-xml/`**,
prepended to `XDG_CONFIG_DIRS` by `linux-rt.sh` — deliberately **not**
`/etc/xdg`. Three of these channel files are dpkg **conffiles**: `xsettings.xml`
belongs to `xfce4-settings`, `xfce4-power-manager.xml` to
`xfce4-power-manager`, and `xfce4-session.xml` to `xfce4-session`. xfconf merges
per *property* across directories, but two files at the *same path* are not a
merge — the second replaces the first. Writing ours over the packaged
`xfce4-session.xml` would have deleted `/general/FailsafeSessionName` and the
whole `/sessions/Failsafe` client list (xfwm4, xfsettingsd, xfce4-panel, Thunar,
xfdesktop), and a guest with no saved session reaches failsafe *by definition* —
so `xfce4-session` would have found no failsafe to load, put up **"Unable to
load a failsafe session"** and called `exit(EXIT_FAILURE)`. No desktop at all,
on every fresh install. A separate directory keeps every packaged property
intact, layers ours on top per property, and leaves dpkg's conffile untouched so
an upgrade never prompts about it.

`XDG_CONFIG_DIRS` is set on the **proot** env in `linux-rt.sh`, not on the env
that runs `startxfce4` — which looks like the obvious place and is the wrong
one. `xfconfd` is D-Bus *activated*, so it is spawned by `dbus-daemon` and
inherits `dbus-daemon`'s environment rather than the session command's, and
`dbus-daemon` is started from inside `$SESSION`. The proot env is the only one
both of them share.

That path is **`xfce4/xfconf/xfce-perchannel-xml`**, built by xfconf as
`CONFIG_DIR_STEM` (`"xfce4/xfconf/"`) joined with `XFCONF_BACKEND_PERCHANNEL_XML_TYPE_ID`
(`"xfce-perchannel-xml"`). It used to be written here as
`.config/xfconf/xfconf-perchannel-xml` — wrong on both counts, the missing
`xfce4/` level and `xfconf-` for `xfce-` — so the desktop-icon channel and every
panel edit `dock.py` made landed in a directory xfconf has never read. That is
why the dock launchers did not appear no matter when the script ran, and fixing
it is part of feature level 14.

`theme.py` covers the one case a system default cannot. A guest provisioned
before this phase has already run a session, and xfwm4 writes its **entire**
defaults block into the user's channel on first start — so that file holds a
`Default` theme and `use_compositing=true`, and the user file beats a system
default forever. It rewrites exactly those properties, in the user's own file,
**once**, and a marker at `/root/.config/openandroiddex/theme-migrated` is what
enforces the once.

The marker, not the value test, is what protects a deliberate choice. The test —
"only rewrite a value that is still one a distro wrote" — reads as though it
would do that on every pass, and for the *string* properties it does. For a
**boolean** it cannot: a bool has two values, ours is one, so the other is by
construction "a default", and a user who re-enabled compositing in Window
Manager Tweaks would have had it switched off again at every launch, forever,
with no supported way to make it stick. The same holds for the small enums
(`HintStyle`, `RGBA`, `vblank_mode`) whose overwrite set covers every value we
do not want. Running once is all a migration was ever meant to do; afterwards
the user owns these settings.

Like `dock.py` it runs from `linux-rt.sh` a moment before the session, for the
same reason: xfconfd caches a channel in memory on first read and **never**
re-reads it, so an edit made under a live session is not merely late — the next
thing that dirties the channel rewrites the whole file from the stale in-memory
tree.

The phase is deliberately **unstamped** and guarded on artifacts rather than a
`.stamp-theme`: a `FEATURE_LEVEL` bump keeps every existing stamp, so a stamped
phase would reach fresh installs only and no guest already out there would ever
be themed.

### The settings that are not about looks

The same channels hold the switches that decide what this display costs, and
with no GPU behind Xvnc every effect XFCE enables by default is rasterised by
llvmpipe on the phone's CPU, re-encoded by TigerVNC and pushed through
websockify into a WebView. None of them buy anything at this end.

| Setting | Why |
| --- | --- |
| `xfwm4/use_compositing=false` | xfwm4's compositor is XRender and never gets acceleration here — xfwm4 blacklists the software renderers itself. Also removes the shadow band around every window, which is a soft gradient and so the worst thing this stack can ask TigerVNC to compress. |
| `xfwm4/box_move`, `box_resize` | Outline drags: four thin lines per frame instead of a full repaint and re-encode of the window and everything it uncovers. The cheapest remaining win, and the one the user feels, because dragging is what they do most. |
| `Gtk/EnableAnimations=false` | The largest GTK-side win, and **not** in xfce4-settings' shipped defaults, so it has to be created. |
| `Net/CursorBlink=false` | The largest **idle** win: a blinking caret in any focused entry wakes the encoder, the websocket and the WebView twice a second forever — on a phone that is battery, not lag. |
| `Xft/RGBA=none` | A transport decision, not a taste one. Subpixel AA paints coloured fringes on every glyph edge, which pushes a text rect past the distinct-colour threshold TigerVNC uses to keep a rect lossless, so the whole line goes out as JPEG. |
| `Gdk/WindowScalingFactor=1` | Pinned. Scaling to 2 makes every app render four times the pixels for content the WebView then scales back down. When text is too small raise `Gtk/FontName`, never this. |
| `xfce4-desktop` backdrop, solid | A photographic wallpaper is the most expensive single thing on this display: the first paint JPEG-encodes a full screen of high-entropy image, and every window move re-encodes the strip it uncovered. A solid colour is one uniform block. Not a gradient, for the same reason. |
| `xfce4-screensaver` off, `dpms-enabled=false` | Blanking sends a full-screen update to blank and another to wake, saves nothing — there is no panel behind this framebuffer — and on a WebView that has already slept the wake half sometimes does not arrive, which is the black-screen-after-idle failure. |
| `xfce4-session/SaveOnExit=false` | Serialises every client's state through proot's ptrace-mediated file I/O at exactly the moment `linux-rt.sh` is tearing the container down, then re-launches all of it next start. |

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
- **pkill self-match**: the bracket trick only covers the pattern's own text.
  Any *other* plain occurrence of a target word in the same command line is a
  self-kill. Also: `websockify` is an apt **package name** — the belt must
  anchor on `--web` or it SIGKILLs apt mid-install and wedges dpkg.

## state.env

`/data/local/tmp/linux/state.env`, `KEY=VALUE` lines, always written
atomically (`state.env.tmp` + `mv`):

    VERSION=<int>  PHASE=<phase>  PCT=<0-100>  MSG=<no-whitespace, dash-separated>

`MSG` may also carry `<verb>:<name>` — `setting-up:libgtk-3-0t64` — when the
tail half is a name the script did not invent. `LinuxActivity.msgWords`
de-dashes only the verb, so the package keeps the dashes that belong to it. The
desktop phase publishes this every 5 s from apt's own transcript (`apt_msg`);
every other phase sends a plain dash-separated token and reads unchanged.
    phases: pushing extracting configuring apt-update installing-desktop ready error

## Ports (guest, loopback only)

| port | what |
|---|---|
| 5901 | Xvnc `:1` (VncAuth, password in `$ROOT/vncpass`) |
| 6080 | websockify + noVNC http — `http://127.0.0.1:6080/dex.html?password=<vncpass>&v=<rtpid>`, our viewer page (`doc/linux-viewer.md`), staged into the web root beside Ubuntu's own `vnc.html` and `vnc_lite.html` |
| 6081 | the PulseAudio tap — raw s16le/48k/stereo, read by `LinuxAudio` in the app (`doc/linux-audio.md`) |
| 4713 | the PulseAudio control protocol, for `pavucontrol`, the panel plugin and anything linking libpulse |

## Sound

There is no audio device in the container — Android's `/dev` is bound in, but
its audio nodes belong to the media uid — so the guest gets a PulseAudio
**null sink** and the app drains that sink's **monitor** over loopback TCP into
an `AudioTrack`. `setup_audio` installs pulseaudio, pavucontrol and the panel
plugin and writes `/etc/pulse/dex.pa`; `linux-rt.sh` starts the daemon with
`-n --file=` that, in its own process group, and kills it with the session.
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

Worth knowing: the setup child is **not** `setsid`'d, unlike `linux-rt.sh`. That
is what makes an install killable by an app restart at all, and it is a
lifecycle decision, not an accident — recovery is handled above rather than by
making the install outlive its process.

## Script rules

Both scripts must stay POSIX-sh compatible with toybox `/system/bin/sh` (no
bashisms) and keep LF line endings — `.gitattributes` here enforces that. For
which version number to bump when you change one, see **Two version numbers**
above.
