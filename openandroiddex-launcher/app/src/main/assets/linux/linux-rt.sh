#!/system/bin/sh
# linux-rt.sh — run the provisioned Ubuntu desktop. POSIX sh for toybox
# /system/bin/sh, like linux-setup.sh.
#
# Spawned by whoever owns the runtime (the app's LinuxService, or a bare
# `sh linux-rt.sh &`). Two facts about this script are load-bearing:
#
#  1. It makes itself a SESSION LEADER, so its pid is also a process-group id
#     and the whole container — proot plus every guest process — dies from one
#     `kill -9 -<pid>`. That matters because the app uid cannot browse /proc:
#     pattern kills (pkill -f) and pid-identity checks both read other
#     processes' cmdlines, so under the app they are unreliable at best. A
#     group kill needs no /proc at all. Everything the session starts must
#     therefore STAY in this group — see the --nofork on dbus-daemon below.
#  2. It removes rt.pid when proot exits. rt.pid is the app's only "is the
#     runtime up" signal, so a leftover file after a guest-side logout is what
#     used to leave the viewer connecting forever to a dead session.
#
# Inside the guest (loopback only, no root anywhere):
#   Xvnc :1     tcp 5901 — VncAuth against /root/.vnc/passwd
#   pulseaudio  tcp 4713 — the control protocol, for pavucontrol and the panel
#               tcp 6081 — the raw s16le/48k/stereo tap the APP reads and plays
#               through AudioTrack. There is no audio device in the guest; see
#               /etc/pulse/dex.pa, written by linux-setup.sh.
#   websockify  tcp 6080 — serves the noVNC web root; the launcher's WebView
#               loads OUR page from it, staged in there by LinuxService:
#               http://127.0.0.1:6080/dex.html?password=<vncpass>&v=<rtpid>
#               Ubuntu's own vnc.html and vnc_lite.html are untouched
#               beside it.
# The XFCE SESSION is what the runtime waits on: when it ends — the user logs
# out, or xfce4-session dies — Xvnc and websockify are taken down with it and
# proot exits. Without that the X server outlived the desktop and reconnecting
# landed on a live-but-empty display: the black screen with nothing on it.

# ROOT / proot from env (app: filesDir + native-lib dir), legacy fallback else.
ROOT=${LINUX_ROOT:-/data/local/tmp/linux}

# Re-exec under setsid once, so $$ below is a session (and process-group)
# leader. Guarded by an env flag because setsid REFUSES to run when the caller
# already leads its group, and skipped entirely if the binary is missing — a
# runtime that cannot be group-killed is still better than one that never
# starts.
# `setsid sh "$0"`, NOT `setsid "$0"`: setsid EXECS its argument, and this
# script lives in the app's private storage, which is neither marked executable
# nor allowed to be (W^X for a target-SDK-35 app). Handing setsid the
# INTERPRETER means nothing ever has to exec the script itself. Measured on
# device: `setsid /path/to/script` fails with "exec: Permission denied".
if [ -z "$LINUX_RT_SETSID" ] && [ -x /system/bin/setsid ]; then
  LINUX_RT_SETSID=1
  export LINUX_RT_SETSID
  exec /system/bin/setsid /system/bin/sh "$0" "$@"
fi

# Clear the previous session's verdict BEFORE announcing this one, so the app
# can never read a live rt.pid next to a stale exit marker.
rm -f "$ROOT/rt.exit"
echo $$ > "$ROOT/rt.pid"

# Window size, written just before the spawn by whoever starts the runtime.
GEO=$(cat "$ROOT/geometry" 2>/dev/null)
[ -n "$GEO" ] || GEO=1280x800

mkdir -p "$ROOT/tmp"
# Android has NO /dev/shm (measured: `ls /dev/shm` -> No such file or
# directory), and we bind Android's /dev into the guest, so the rootfs's own
# empty /dev/shm is shadowed and POSIX shared memory simply does not exist in
# there. Firefox survives that only while it can use memfd_create, which it
# cannot under proot ("read-only dup failed; not using memfd") -- so it falls
# back to shm_open, finds nothing, and the content process dies on launch.
# A writable directory bound over /dev/shm is the whole fix; proot resolves the
# longer bind first, so this wins over -b /dev (verified with our own proot).
mkdir -p "$ROOT/shm"

PROOT=${LINUX_PROOT:-$ROOT/proot/root/bin/proot}
# Match the seccomp mode linux-setup.sh probed and cached: "1" = pure ptrace
# (Samsung/OEM, execve EPERM otherwise), empty/absent = hardware seccomp (the
# emulator + mainline; forcing ptrace there makes the guest hit ENOSYS).
[ "$(cat "$ROOT/noseccomp" 2>/dev/null)" = 1 ] && export PROOT_NO_SECCOMP=1
export PROOT_LOADER=${PROOT_LOADER:-$ROOT/proot/root/libexec/proot/loader}
export PROOT_LOADER_32=${PROOT_LOADER_32:-$ROOT/proot/root/libexec/proot/loader32}
export PROOT_TMP_DIR=$ROOT/tmp

# A shared-storage bind for file exchange (runtime only; setup has no business
# there). Best-effort: only bind it when the path is actually readable — under
# an app uid /sdcard is scoped-storage-gated and may be absent. Override with
# LINUX_STORAGE (e.g. the app's own getExternalFilesDir()).
STORAGE=${LINUX_STORAGE:-/sdcard}
STORAGE_BIND=
[ -r "$STORAGE" ] && STORAGE_BIND="-b $STORAGE:/root/storage"

# The one folder both sides can reach. $STORAGE above lives under
# /sdcard/Android/data, which no Android 11+ file manager will browse, so it has
# only ever been a diagnostics drop. This one is /sdcard/LinuxOnDeX, at the top
# of internal storage where a user actually looks: My Files, Files, a share
# sheet and USB/MTP all land on it without digging.
#
# The app pays for that placement with MANAGE_EXTERNAL_STORAGE -- a TOP-LEVEL
# name on shared storage cannot be created without it. So an unset or unusable
# $SHARED here usually means the user has not granted it; the app decides that,
# and simply does not pass the variable when it cannot use the folder.
#
# Bound straight into ~/Desktop, so it is the first thing on the guest desktop.
#
# Best-effort, and the guard is load-bearing rather than tidy: proot treats an
# unusable -b source as a FATAL startup error, not a skipped bind, so a bad path
# here would stop the container starting instead of merely costing it a folder.
# -w and not -d: a directory that exists but refuses writes is exactly the case
# that would take the session down.
#
# The MOUNTPOINT is pre-created rather than left to proot. proot registers a
# talloc destructor to remove a binding point it had to create itself, and an
# unclean exit -- --kill-on-exit, or the app's process-group SIGKILL -- never
# runs it, leaving a mode-000 stub in ~/Desktop that renders as an unreadable
# folder. Creating it here means proot hits EEXIST and registers nothing.
#
# $SHARED_BIND is expanded UNQUOTED on the proot argv so it splits into two
# items, so the path must be whitespace-free. It is: a fixed name at the root
# of internal storage.
SHARED=${LINUX_SHARED:-}
SHARED_BIND=
if [ -n "$SHARED" ]; then
  mkdir -p "$SHARED" 2>/dev/null
  if [ -w "$SHARED" ]; then
    mkdir -p "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
    chmod 755 "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
    SHARED_BIND="-b $SHARED:/root/Desktop/LinuxOnDeX"
    echo "shared folder: $SHARED -> /root/Desktop/LinuxOnDeX"
  else
    echo "shared folder: could not create or write $SHARED - bind skipped"
  fi
fi

# The guest session, as one string so the quoting stays in one place.
#
# A stale X lock from a killed session would stop Xvnc starting again, hence
# the rm. The guest's /tmp lives on /data, where SELinux denies FILESYSTEM unix
# sockets while ABSTRACT ones bind fine (measured on device) — so dbus-daemon
# is given an abstract address by hand instead of going through dbus-launch
# (whose /tmp/dbus-XXXX bind dies with EACCES and takes the session with it),
# and X clients reach Xvnc through the abstract @/tmp/.X11-unix/X1 that Xorg
# always binds alongside the (failing) file socket.
#
# --nofork on dbus-daemon is not cosmetic: a daemonising dbus calls setsid and
# leaves this script's process group, which is exactly the escape hatch the
# group kill cannot follow.
#
# The MOZ_DISABLE_*_SANDBOX family: every one of Firefox's child processes
# sandboxes itself with user namespaces and seccomp, neither of which survives
# a ptrace chroot, and disabling only the CONTENT one leaves the rest to crash.
# MOZ_CRASHREPORTER_DISABLE keeps a crash from stacking a modal reporter window
# on top of the desktop. The env reaches the browsers because every menu launch
# inherits xfce4-session's environment, which is this one.
SESSION='rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p /tmp/runtime-root && chmod 700 /tmp/runtime-root
Xvnc :1 -geometry GEOMETRY -depth 24 -rfbport 5901 -localhost \
  -rfbauth /root/.vnc/passwd -SecurityTypes VncAuth &
XVNC=$!
sleep 2
dbus-daemon --session --address=unix:abstract=dex-session-bus --nofork &
DBUS=$!
sleep 1
# The dock, applied while NO panel is running. xfce4-panel writes its config out
# when it EXITS, so an edit made during a live session is discarded on the way
# out -- which is why the launchers added at provision time never appeared.
# Doing it here, a moment before the session starts, is what makes them stick.
# Idempotent: it adds only what is not already on the panel.
[ -f /usr/local/share/openandroiddex/dock.py ] && \
  python3 /usr/local/share/openandroiddex/dock.py >/dev/null 2>&1
# The theme, for the same reason and in the same window. Our defaults live in
# /etc/xdg and cannot be clobbered, but a guest provisioned before that existed
# already has the whole xfwm4 defaults block written into its OWN channel, and
# the user file is merged after the system one -- so a stale Default theme and
# use_compositing=true would win forever. This rewrites those, and only those:
# a setting the user actually chose is left alone. It has to happen with no
# xfconfd alive, because xfconfd caches a channel on first read and never
# re-reads it, so an edit under a live session is erased wholesale the next
# time anything dirties the channel. No-op on a guest that has no user file.
[ -f /usr/local/share/openandroiddex/theme.py ] && \
  python3 /usr/local/share/openandroiddex/theme.py >/dev/null 2>&1
# Sound, when the guest has been through a setup pass that installs it -- an
# older container simply has no audio, exactly as before. There is no audio
# device in here: the desktop plays into a null sink and the APP reads that
# sink monitor off 127.0.0.1:6081. See /etc/pulse/dex.pa.
#
# NOT daemonised, which is the dbus-daemon rule again rather than a style
# choice: pulseaudio -D calls setsid and leaves this script process group,
# which is the one escape hatch the group kill cannot follow. Its log goes to
# stderr and so into rt.log with everything else.
#
# The runtime dir is cleared first because /tmp SURVIVES a session: pulseaudio
# writes a pid file in there, and a session that was killed rather than logged
# out leaves that file behind pointing at a pid Android has since handed to
# somebody else -- at which point the next daemon refuses to start, saying one
# is already running. Nothing in that directory is worth keeping: with no unix
# socket and no cookie in this configuration, it holds only the pid file.
PULSE=
if [ -x /usr/bin/pulseaudio ] && [ -f /etc/pulse/dex.pa ]; then
  rm -rf /tmp/runtime-root/pulse
  XDG_RUNTIME_DIR=/tmp/runtime-root pulseaudio -n --file=/etc/pulse/dex.pa \
    --exit-idle-time=-1 --disallow-exit=1 --disable-shm=1 &
  PULSE=$!
  sleep 1
fi
env DISPLAY=:1 DBUS_SESSION_BUS_ADDRESS=unix:abstract=dex-session-bus \
  XDG_RUNTIME_DIR=/tmp/runtime-root XDG_SESSION_TYPE=x11 \
  PULSE_SERVER=tcp:127.0.0.1:4713 \
  MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
  MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 \
  MOZ_DISABLE_UTILITY_SANDBOX=1 MOZ_DISABLE_GPU_SANDBOX=1 \
  MOZ_CRASHREPORTER_DISABLE=1 MOZ_ENABLE_WAYLAND=0 \
  startxfce4 >/root/.vnc/session.log 2>&1 &
XFCE=$!
/usr/bin/websockify --web=/usr/share/novnc 127.0.0.1:6080 127.0.0.1:5901 &
WS=$!
# THE session lifetime. Everything else is torn down behind it, so a logout
# ends the container instead of leaving an empty X server behind. Its exit
# status is carried all the way out: xfce4-session exits 0 when the user logged
# out and non-zero when it fell over, and that is the only honest way to tell
# those apart. Duration is NOT — someone can log out five seconds after logging
# in, and reading that as a crash is exactly what this used to do.
wait $XFCE
RC=$?
kill -9 $WS $DBUS $XVNC $PULSE 2>/dev/null
exit $RC'

# $GEO is host-side; substitute it rather than letting the guest shell see it.
SESSION=$(echo "$SESSION" | sed "s/GEOMETRY/$GEO/")

# NOT exec'd: proot runs as a child so this script outlives it by exactly long
# enough to clear rt.pid, which is how the app learns the session is over.
#
# XDG_CONFIG_DIRS is what puts our theme defaults in front of the distro's.
# linux-setup.sh writes them to /usr/local/etc/xdg rather than /etc/xdg,
# because three of those channel files are dpkg conffiles owned by
# xfce4-settings, xfce4-power-manager and xfce4-session, and overwriting the
# last of those deletes the failsafe session list and leaves xfce4-session with
# nothing to start. Two directories, merged per property, collide with nothing.
#
# It goes HERE, on the proot env, and NOT on the env that runs startxfce4 --
# which looks like the obvious place and is the wrong one. xfconfd is D-Bus
# ACTIVATED, so it is spawned by dbus-daemon and inherits dbus-daemon's
# environment, not the session command's. dbus-daemon is started from inside
# $SESSION, so the only environment both of them share is this one.
"$PROOT" -0 --link2symlink --kill-on-exit -r "$ROOT/rootfs" \
  -b /dev -b /proc -b /sys -b "$ROOT/tmp:/tmp" -b "$ROOT/shm:/dev/shm" \
  $STORAGE_BIND $SHARED_BIND \
  -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color DEBIAN_FRONTEND=noninteractive \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  XDG_CONFIG_DIRS=/usr/local/etc/xdg:/etc/xdg \
  /bin/sh -c "$SESSION" &
PROOT_PID=$!
wait "$PROOT_PID"
RC=$?

# rt.pid is the app's "runtime is up" signal — clearing it here is what tells
# the viewer a guest-side logout actually ended the session.
rm -f "$ROOT/rt.pid"

# The session's verdict; proot passes the guest's status straight through.
#
# Its EXISTENCE is the load-bearing part. The viewer starts a runtime whenever
# none is running, so with no marker a logout silently booted a fresh desktop
# the instant the old one went away — which is not what "log out" means. The
# number in it only chooses the wording: 0 = the user logged out, anything else
# = the session fell over. A kill from the app side never reaches this line
# (the whole process group dies at once), and that is right: no marker, so the
# next open starts clean.
echo "$RC" > "$ROOT/rt.exit"
