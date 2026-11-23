# Linux apps in an Android process of their own

Android 12 and later close the processes that apps start once there are more
than 32 of them on the phone ("Process completed (signal 9)" in Termux and
Andronix). Every Linux process is one. Until now every Linux process belonged to
LinuxOnDroid's `:linux` process, and when Android had to close some, it closed
the desktop itself: proot, Xvnc, XFCE. Now the apps the desktop starts belong to
a second Android process, `:linuxapps`, which Android ranks one step lower, so it
closes an app first and the desktop stays.

This does not switch Android's limit off. Nothing without adb or Developer
options can (see "What else was looked at"). It decides what goes when the
limit is hit.

## Android's rule, from its source

`PhantomProcessList` in the activity manager (AOSP main, checked 2026-11-20):

- **Who counts.** For each app process, Android reads its cgroup
  (`uid_<uid>/pid_<pid>/cgroup.procs`). Every other process in there is a
  "phantom", counted under that app process. A child stays in its parent's
  cgroup for life, however it forks or daemonises.
- **What is killed.** Over `max_phantom_processes` (32) phone-wide, the list is
  sorted by the importance (oom_adj) of the app process each phantom is counted
  under, then by when Android first noticed it, and the extras are killed from
  the least important, oldest end. One process at a time.
- **CPU.** A second rule kills phantoms that use too much CPU, but only under
  app processes in the background (process state HOME or below). Both of ours
  stay in the foreground-service state, so it never applies.
- **Importance.** A service bound with `BIND_NOT_PERCEPTIBLE` by a client at 200
  or better gets 250 (`OomAdjuster.computeServiceHostOomAdjLSP`). `:linux` runs
  a foreground service, so it is at 200 or better, and `:linuxapps` is at 250
  for as long as `:linux` binds it. Level would not do: Android then picks by age
  across both, and the desktop is older than every app.

## Shape

| Piece | File | What it does |
| --- | --- | --- |
| The process | `LinuxAppsService` (`:linuxapps`) | watches `/tmp/.oadx-apps`, starts each request in a proot, closes apps with their session |
| The rules | `LinuxAppRunner` | the claim, the start, watching an app end, the stops; plain Java, unit-tested |
| The link | `LinuxAppsLink` (in `:linux`) | puts the guest half in place, binds with `BIND_NOT_PERCEPTIBLE` while a session runs, rebinds after a crash |
| One app | `openandroiddex-linux/linux-app.sh` | a proot of its own for one request, as a process group the service can kill |
| The hand-over | `guest/oadx-run` | turns a command into a request and waits for it to be claimed |
| The menus | `guest/oadx-apps-menu` | routes the menu, the dock and the desktop icons through `oadx-run` |
| D-Bus | `guest/oadx-apps-dbus.conf` | lets apps in another proot onto the desktop's session bus |

### The hand-over

`oadx-run COMMAND...` writes a small shell script into `/tmp/.oadx-apps`: the
caller's whole environment (`export -p`), its umask and folder, and the command,
each argument single-quoted. Every step is a rename, so exactly one side runs a
request:

1. `oadx-run` writes `ID.req` and waits.
2. The service sees it (`FileObserver`) and claims it: `ID.req` to `ID.run`.
3. `linux-app.sh` starts a proot, and inside it a shell claims it again:
   `ID.run` to `ID.go`, which removes itself and execs the app.

If neither happens within ten seconds, `oadx-run` renames whichever is still
there to `ID.self` and runs it itself, as if it were not there. It hands over
only while `ready` names a live process, so with no `:linuxapps` (Android 11 and
older, an older app) a launch costs one `kill -0`.

Files and renames, no socket: the guest's `/tmp` is on `/data`, where SELinux
refuses the app a filesystem socket, and an abstract one is reachable by other
apps.

### One proot per app

The oldest process of an app is its proot, and proot killed takes the app with
it. With one proot for every app, Android's first kill would close all of them.
No `--kill-on-exit`: VS Code's CLI, `exo-open` and others start the real app and
exit. When a proot ends, the service kills what is left in its process group,
because Android kills one process at a time and a tracee that outlives its proot
runs untranslated.

### What goes through it

- **The Applications menu, Thunar's Open With, `xdg-open`**: these look a
  desktop file up by name along `XDG_DATA_DIRS`. `linux-rt.sh` puts
  `/usr/local/share/oadx-apps` first, and `oadx-apps-menu` writes a copy of every
  app's entry there with its `Exec` lines behind `oadx-run` (and
  `DBusActivatable=false`, or the bus would start it). The packages' files are
  untouched.
- **The user's own entries** in `~/.local/share/applications`, which come
  before every copy (a menu editor's overrides, an app's own launcher): routed
  in place, their mode kept.
- **"The web browser", "the terminal", the file manager, the mail reader**:
  `exo-open --launch`, which hands the lookup to xfce4-settings'
  `xfce4-mime-helper`. It finds its helpers (`xfce4/helpers/NAME.desktop`)
  along `XDG_DATA_DIRS` too, so they get routed copies the same way, every
  command of `X-XFCE-Commands` and `X-XFCE-CommandsWithParameter` behind
  `oadx-run` (but one whose program is not there, so that XFCE still moves on
  to the next in the list); the user's own (`~/.local/share/xfce4/helpers`, made in
  Preferred Applications) are routed in place. This is also how an entry with
  `Terminal=true` runs from XFCE's menus: libxfce4ui and the whisker menu open
  it with `exo-open --launch TerminalEmulator` (both 4.18, checked), so its
  terminal is routed and the command with it. And a browser another app asks
  for gets a proot of its own instead of running inside that app's.
- **The dock**: xfce4-panel keeps a copy of each launcher in
  `~/.config/xfce4/panel/launcher-N`. They are routed in place, before the panel
  starts (it writes them back out when it exits).
- **The desktop icons**: routed in place with their mode kept, since xfdesktop
  runs only an executable one. That is all xfdesktop 4.18 checks, the guest's
  (Ubuntu 24.04), GVfs or not: neither it nor libxfce4ui 4.18 has a launcher
  checksum (no such strings in either; Thunar 4.18 has its own "Untrusted
  application launcher" for launchers it opens, by the same executable bit).
  From 4.19 on, with GVfs metadata, xfdesktop also checks a checksum, and a
  rewritten icon would ask to be trusted again: there, and only there, they are
  left alone. The version is dpkg's.
- **Links** among any of these are never written through: what one points at
  may be a package's own file. They stay as they are, unrouted.
- **The window session**: `linux-win.sh` runs each `run` command through
  `oadx-run`.
- **apt**: a `DPkg::Post-Invoke` hook reruns the menu half, so a new app is
  routed as soon as it is installed.

Left in `:linux`: XFCE's autostart, services the session bus starts (Thunar's
own window, when xfdesktop started it over D-Bus, is one), links,
`Terminal=true` entries opened by something other than XFCE's menus (GLib
picks a terminal of its own for `gio open`), and anything started from a
script that is not a menu launch. An app that a routed app starts directly,
not through a helper, runs in that app's proot: under `:linuxapps`, closed
with it.

### Closing apps with their session

A stop of the desktop or a logout tells the service to close the apps of that
X display (`:1`; the window session's is `:2`). GTK hands its children the
display with the screen number (`:1.0`), so the service drops it. The window
session is watched for ending by itself, as the desktop is, and its apps are
closed then too. When no session is left, `:linux` unbinds, Android destroys
the service, and it closes every app. If `:linux` dies, the same happens.

**How an app is closed.** A SIGQUIT to its proot, not a kill. proot ignores
SIGTERM, and on SIGQUIT it SIGKILLs every process it traces, whatever session
or group each is in, and exits (`src/tracee/event.c`, `kill_all_tracees`; the
same code in the app's v0.15 as upstream). A group SIGKILL, which is what this
did at first, misses whatever an app moved out of its group with `setsid`:
VS Code's CLI starts its window that way, a terminal its shells. With proot
gone and no `PTRACE_O_EXITKILL`, those run on untraced, outside the
container's paths. The SIGQUIT is sent again after 0.3 s (a process forked in
the instant of the first is traced but not yet on proot's list), and only if
proot has not finished 2 s later does the group get the SIGKILL. The processes
the app starts get SIGQUIT neither blocked nor ignored, though ART blocks it in
its own threads (`SigBlk` of a spawned shell, Android 12).

**When Android kills a proot** itself, which is its first kill of an app,
proot can do none of that, and what it traced runs on. What is still in its
group goes with the group kill the service makes when the proot ends; what
left the group is found in `/proc`: every process in `:linuxapps`'s cgroup
(`uid_N/pid_THIS`) that is traced by nothing, and is neither the service, nor
something it started (linux-app.sh, now proot), nor a proot's first process
before it asks to be traced. It looks again while a look finds something (one
may have forked in between), three times at most, and a zombie is dead already.
An app may list its own processes in `/proc` and read their `status` and
`cgroup` (measured as an app with targetSdkVersion 36 on Android 12, and by
the service itself on the S25's Android 17); the cgroup's own member list it
may not (`EACCES`). Where `/proc` lists nothing, the service says so once and
skips this. What it cannot see either way: a process that made itself
undumpable (`PR_SET_DUMPABLE` 0, as ssh-agent does), which `/proc` hides even
from its own user. That one is left to Android's next trim, under
`:linuxapps`.

**Starting and closing at once.** The service, once it is going, claims no
more requests: `oadx-run` takes one back at its ten seconds and runs it where
it was asked for, as with no `:linuxapps`. An app it was starting at that
moment is closed the moment it has started (it once registered after the
clean-up and ran on). A request that was asked for before its session's stop
and reaches the service after it, is not started: its file's age, taken from
the claim time, says when it was asked for, and the stop says when the session
was closed. The session's stop is sent twice, before its kill and after it, so
an app asked for in between is caught too. Nor is a request started whose
`oadx-run` has gone (its pid is the last part of the request's name, and it
waits, alive, for as long as the request can be claimed): that is a leftover
from a session that died, found by a new instance of the service, which knows
of no stop.

**When `:linuxapps` dies**, Android takes its apps with it and the desktop
stays. Android did not restart the bound service on its own after a crash on
the S25, so `LinuxAppsLink` binds again after a second, doubling the wait each
time within a minute. None at all if Android has brought the service back
meanwhile, and the new binding is made before the dead one goes: unbinding a
service that is up destroys it, and every app it runs with it, and Android's
own restart of it is due a second after the death too, just when this one is.
But while such a restart is due, Android holds the service back and a binding
waits with it (`ActiveServices.bringUpServiceInnerLocked`: "If waiting for a
restart, then do nothing"), longer after each crash. So if it is still not up
three seconds later, the link lets go of its binding, which cancels that wait,
and binds again, which brings it up at once. Once per death, and not while
the service says it is up (`ready` names a live process): its connection is
then on its way, and letting go would destroy it. A binding Android declares
dead is replaced on the same wait as a death, and each session starts the
wait over.

### D-Bus across proots

proot fakes a socket peer's user (`SO_PEERCRED`) only for the processes it
traces itself (`extension/fake_id0/getsockopt.c`). To the desktop's session
bus, which runs as fake root, an app in another proot is the app's real Android
user, and the bus refused it. Log Out from the menu failed with "The connection
is closed".

GLib clients authenticate with `EXTERNAL` and no user named, so they are taken
as the socket's user. libdbus clients say root, which the socket does not back,
and fall back to the next mechanism. So `oadx-apps-dbus.conf`
(`/etc/dbus-1/session.d/oadx-apps.conf`) allows `DBUS_COOKIE_SHA1` beside
`EXTERNAL` (the cookie is in `/root/.dbus-keyrings`, which only this app can
read) and lets in the user `oadx-android`. That user's line in `/etc/passwd`
carries this install's uid, because the bus resolves policy users by name and
ignores a bare number ("Unknown username"). Other apps on the phone are other
users and can do neither.

## Costs

- `:linuxapps` while a session runs: 20.2 MB PSS on the S25 (RSS 101 MB, most of
  it shared), beside 22.4 MB for `:linux`.
- One more process per app (its proot), which counts toward the 32.
- A launch goes through `oadx-run` and a proot start: no delay was noticeable
  on the S25, and none was timed.
- Android 12 and later only; before that nothing is put in place.

## Tested

**Off a phone**: `node test/linux-apps-test.mjs`, 163 checks in two Docker
containers (ubuntu:24.04, the guest's own; eclipse-temurin:17-jdk), and
`./gradlew :app:testDebugUnitTest`: `LinuxAppRunnerTest` 29, `LinuxAppsLinkTest`
6, `LinuxAppsLinkBindTest` 9 (Robolectric: the binding through deaths, Android's
own restarts and the force).

- arguments, environment, folder and umask byte for byte, with quotes,
  newlines, dollars and empty arguments;
- the fallback with no service, a dead `ready`, a stuck service and a proot
  that never comes;
- 80 launches with the claim racing `oadx-run`'s deadline: every one ran once;
- the menu rules case by case, idempotence, 400 entries, three runs at once,
  links never written through, the user's own entries and helpers, the GVfs
  rule by xfdesktop version, the helper copies, and XFCE 4.18's own lookup
  (`xfce4-mime-helper` on a throwaway X) taking the routed browser and
  terminal;
- `linux-app.sh` under mksh with a stand-in proot: session, group, pid, command
  line, and the inner claim losing;
- the D-Bus policy against a real `dbus-daemon`: plainly (the app's user refused
  without it, let in with it, another user kept out), and in the phone's
  shape, bus and clients each in a proot of their own as one real user, with the
  app's proot built for the computer (`test/linux-apps/build_proot.sh`):
  libdbus refused without `DBUS_COOKIE_SHA1` and let in with it, the cookie
  file proving which way; GLib let in; another user out both ways;
- `LinuxAppRunner` itself against real processes and that proot
  (`runner_check.sh`, `RealRunner.java`): a session's stop takes a setsid'd
  child; an app whose proot is SIGKILLed leaves nothing running untraced, and
  the app beside it runs on; the service going while an app starts leaves
  nothing running; a request from a closed session, and one whose `oadx-run`
  has gone, is not started. The runner as it was before the 2026-11-21 audit
  fails 5 of the first 8 (the setsid'd child left running untraced, parent
  pid 1, after both kinds of ending; the app started during the shutdown left
  running; a request still claimed after it; the stale request started); the
  runner now passes all 9.

**On a Galaxy A13 (Android 12)**, with a throwaway two-process app shaped like
`:linux` and `:linuxapps`, the "desktop" noticed by Android before the "apps"
started:

| Binding | Importance | Android killed |
| --- | --- | --- |
| no flag | 200 and 200 | the 8 oldest "desktop" processes |
| `BIND_NOT_PERCEPTIBLE` | 200 and 250 | the 8 oldest "app" processes; no "desktop" one |

When both groups were first noticed in the same sweep, Android killed the apps
in both cases, because it notices the higher-numbered process's children first.
That is why the trials above were staged.

**On a Galaxy S25 Ultra (Android 17)**, LinuxOnDroid with a real Ubuntu
desktop: app A (30 sleeping processes) and app B (3), launched from inside the
session the way the menu does, then Android's limit switched on:

- **Before** (the build without this): about 60 processes, all under `:linux`.
  Android killed 29, the whole desktop among them (proot, Xvnc, xfce4-session, the
  panel, xfwm4, xfdesktop, D-Bus, PulseAudio, Thunar). The app said "Android
  probably closed Linux".
- **After**: 23 under `:linux`, 37 under `:linuxapps`. Android killed 28, every
  one under `:linuxapps`: app A's proot, its shell and 26 of its processes. The
  service closed the rest of A. App B and all 23 desktop processes were
  untouched, and the desktop stayed on screen. On the final build, after
  `:linuxapps` had been crashed and rebound, the same test killed 29, again all
  under `:linuxapps`.
- Importance 200 and 250 with the app on screen and in the background.
- Real taps: the dock's terminal, an Applications menu entry, and Log Out each
  ran under `:linuxapps`.
- Endings: Shut down, logout from XFCE's menu, and a crash of `:linuxapps`
  (apps gone, desktop kept, rebound in 1.1 s, the next launch routed).

**On the S25 again, 2026-11-21**, after the audit, the build with SIGQUIT,
the sweep, the start race and the helpers (before the review fixes listed
below), started and stopped by LinuxService's own intents, nothing on the
screen, the app's limit setting untouched:

- An app with two setsid'd children (sessions of their own, traced by its
  proot) and a plain one: Shut down closed all three ("closed 1 app(s) of :1
  with its session", proot's exit 0).
- An app's proot SIGKILLed the way Android's trim kills (one pid): its group
  went with the service's group kill, and the sweep took exactly its four
  setsid'd children ("took 4 process(es) an app left running without its
  proot"); the app beside it ran on. So `/proc` reads work for the app on
  Android 17 too.
- Chromium from the menu, a throwaway profile at `chrome://gpu`: all 11 of its
  processes under `:linuxapps`, the GPU process with `--use-angle=vulkan`,
  Turnip (`libvulkan_freedreno.so`) loaded and the Adreno (`kgsl-3d0`) open.
- Sound from a routed app, into a silent test sink of the desktop's PulseAudio
  (`PULSE_SINK`, through the hand-over): its monitor read the tone at its full
  level (peak 12000, about 3.7 s of it). Nothing reached the speaker.
- `exo-open --launch TerminalEmulator` from the desktop: the terminal and its
  command in a proot of their own under `:linuxapps`.
- `:linuxapps` SIGKILLed (as a low-memory kill would): every app went, the
  desktop stayed, the service was back 1.05 s later, the next launches routed
  there, and Shut down then closed them all, nothing left.

The review of the result (an independent read of the code) brought these: a
request whose `oadx-run` has gone is not started; the stop is sent again after
the kill; the sweep looks again; a dead binding waits as a death does, the wait
starts over per session, and no force while the service says it is up; a failed
start lets the binding go; `oadx-apps-menu`'s runs at once keep their own
lists, and a helper's command whose program is not there is left for XFCE to
pass over. They are tested off a phone, and the final build with them was run
on the S25 again, headless, the phone locked: a real `oadx-run` launch passes
the new check; the setsid'd app closed whole at Shut down, counted once; the
sweep took the four setsid'd children of a killed proot in one look; exo's
terminal ran routed; `:linuxapps` killed came back 1.04 s later and took the
next launch. Chromium and sound were not run again on it.

**Not tested**:
- The desktop launcher's own build and its window session on a phone, and its
  new watch for the session ending by itself (compiled, read, never run). No
  phone here has Linux in the desktop launcher.
- Android's own trim on the final build (2026-11-20's runs had it; on
  2026-11-21 the phone's owner was using it, so the limit was not switched on,
  and the kill was made by hand instead).
- Firefox and VS Code from a routed app; the desktop and dock Chromium icons,
  which would have opened the user's own browser profile.
- The screen off, and a kill of `:linux` with apps running.
- Android 13 to 16, and phones other than Samsung's.

## What else was looked at

- **Switching the limit off**: needs `WRITE_SECURE_SETTINGS` or
  `device_config`, which only adb's shell user has, or the Developer options
  toggle.
- **Running Linux under an app zygote**, the one parent the scan skips: its
  children are isolated processes, with no files, no network and no permissions.
- **Fewer processes**: worth doing beside this (the desktop alone is 23 of the
  32), and the only thing that helps when the desktop itself is over the limit.
