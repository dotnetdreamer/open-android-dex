#!/system/bin/sh
# linux-win.sh — the guest's second X display, where Linux apps open as
# windows of their own on the desktop instead of inside XFCE. POSIX sh for
# toybox /system/bin/sh, like linux-rt.sh, whose rules it follows.
#
# Spawned by the app's LinuxService, which keeps this script's stdin and
# writes one line per command:
#   run <base64 of a shell command>   start an app on :2
#   quit                              end the session
# stdin closing (the app process went away) ends it too.
#
# What runs inside the guest (loopback only, no root anywhere):
#   Xvnc :2     tcp 5902 for a VNC viewer, if anyone ever wants to look —
#               VncAuth against /root/.vnc/passwd, the desktop's password.
#               Its X socket is the abstract @/tmp/.X11-unix/X2, and that is
#               what the launcher connects to: it is this display's window
#               manager (LinuxWm), and the only thing that ever draws its
#               windows. -auth makes every client show the cookie the app
#               wrote, since an abstract socket is reachable by any app on
#               the phone.
#   dbus        a session bus of its own, unix:abstract=dex-win-bus — NOT the
#               XFCE desktop's: apps that keep one instance (a terminal, the
#               file manager, Firefox) hand a second launch to the first one
#               over the bus, and one on :1 would open the window on :1.
#   pulseaudio  only when the app asks (LINUX_WIN_SOUND=1): the desktop on
#               :1 is not running, so nothing else is giving these apps sound.
#               The same daemon and the same ports linux-rt.sh uses, so the
#               app's LinuxAudio reads it the same way.
#
# Two facts load-bearing in linux-rt.sh hold here too:
#  1. This script makes itself a session leader, so its pid is a process-group
#     id and `kill -9 -<pid>` takes the whole container down — proot and every
#     app started here, since they are started without setsid and never leave
#     the group.
#  2. It removes win.pid when proot exits; win.pid is what the app reads as
#     "the window session is up".

ROOT=${LINUX_ROOT:-/data/local/tmp/linux}

# `setsid sh "$0"`, not `setsid "$0"`: the script lives in app storage, which
# may not be executed (W^X). See linux-rt.sh.
if [ -z "$LINUX_WIN_SETSID" ] && [ -x /system/bin/setsid ]; then
  LINUX_WIN_SETSID=1
  export LINUX_WIN_SETSID
  exec /system/bin/setsid /system/bin/sh "$0" "$@"
fi

echo $$ > "$ROOT/win.pid"

# The X screen is the desktop display, pixel for pixel: the window manager
# keeps every X window exactly where its Android window is, so the two share
# one set of screen coordinates. Written by the app just before the spawn.
GEO=$(cat "$ROOT/win-geometry" 2>/dev/null)
[ -n "$GEO" ] || GEO=1920x1080
DPI=$(cat "$ROOT/win-dpi" 2>/dev/null)
[ -n "$DPI" ] || DPI=96

mkdir -p "$ROOT/tmp" "$ROOT/shm"

PROOT=${LINUX_PROOT:-$ROOT/proot/root/bin/proot}
[ "$(cat "$ROOT/noseccomp" 2>/dev/null)" = 1 ] && export PROOT_NO_SECCOMP=1
export PROOT_LOADER=${PROOT_LOADER:-$ROOT/proot/root/libexec/proot/loader}
export PROOT_LOADER_32=${PROOT_LOADER_32:-$ROOT/proot/root/libexec/proot/loader32}
export PROOT_TMP_DIR=$ROOT/tmp

# The same two storage binds as the desktop, and for the same reasons — see
# linux-rt.sh, including why a bad -b source must never reach proot.
STORAGE=${LINUX_STORAGE:-/sdcard}
STORAGE_BIND=
[ -r "$STORAGE" ] && STORAGE_BIND="-b $STORAGE:/root/storage"
SHARED=${LINUX_SHARED:-}
SHARED_BIND=
if [ -n "$SHARED" ]; then
  mkdir -p "$SHARED" 2>/dev/null
  if [ -w "$SHARED" ]; then
    mkdir -p "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
    chmod 755 "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
    SHARED_BIND="-b $SHARED:/root/Desktop/LinuxOnDeX"
  fi
fi

SOUND=${LINUX_WIN_SOUND:-0}

# The guest side, as one single-quoted string. NO APOSTROPHES ANYWHERE IN IT,
# prose included — see the same warning in linux-rt.sh.
#
# Every app gets the desktop session environment, plus:
#   LANG C.UTF-8 — glibc has it built in, and without a UTF-8 locale a
#     terminal shows an accented letter, a dash or any CJK as nothing at all;
#   GDK_BACKEND x11, QT_QPA_PLATFORM xcb, MOZ_ENABLE_WAYLAND 0 — there is only
#     X here;
#   NO_AT_BRIDGE 1 — no accessibility bus to wait for.
# Apps start in the background with stdin from /dev/null: the session reads
# its commands from stdin, and an app holding it would swallow the next one.
#
# xset r off: keys come from Android, which repeats a held key by itself;
# X repeating it as well would type everything twice.
SESSION='rm -f /tmp/.X2-lock /tmp/.X11-unix/X2
mkdir -p /tmp/runtime-root && chmod 700 /tmp/runtime-root
Xvnc :2 -geometry GEOMETRY -depth 24 -dpi DPIVALUE -rfbport 5902 -localhost \
  -rfbauth /root/.vnc/passwd -SecurityTypes VncAuth \
  -auth /tmp/.oadx-win.xauth -nolisten tcp &
XVNC=$!
export DISPLAY=:2 XAUTHORITY=/tmp/.oadx-win.xauth
n=0
until xset q >/dev/null 2>&1; do
  n=$((n + 1))
  [ $n -gt 150 ] && break
  sleep 0.2
done
xset r off 2>/dev/null
dbus-daemon --session --address=unix:abstract=dex-win-bus --nofork &
DBUS=$!
PULSE=
if [ SOUNDVALUE = 1 ] && [ -x /usr/bin/pulseaudio ] && [ -f /etc/pulse/dex.pa ]; then
  rm -rf /tmp/runtime-root/pulse
  XDG_RUNTIME_DIR=/tmp/runtime-root pulseaudio -n --file=/etc/pulse/dex.pa \
    --exit-idle-time=-1 --disallow-exit=1 --disable-shm=1 &
  PULSE=$!
fi
export DBUS_SESSION_BUS_ADDRESS=unix:abstract=dex-win-bus \
  XDG_RUNTIME_DIR=/tmp/runtime-root XDG_SESSION_TYPE=x11 \
  XDG_CURRENT_DESKTOP=XFCE LANG=C.UTF-8 \
  PULSE_SERVER=tcp:127.0.0.1:4713 \
  GDK_BACKEND=x11 QT_QPA_PLATFORM=xcb NO_AT_BRIDGE=1 \
  MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
  MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 \
  MOZ_DISABLE_UTILITY_SANDBOX=1 MOZ_DISABLE_GPU_SANDBOX=1 \
  MOZ_CRASHREPORTER_DISABLE=1 MOZ_ENABLE_WAYLAND=0
echo "window session up on :2"
while read -r verb arg; do
  case "$verb" in
    run)
      cmd=$(printf %s "$arg" | base64 -d 2>/dev/null)
      [ -n "$cmd" ] || continue
      echo "run: $cmd"
      sh -c "$cmd" </dev/null >>/tmp/oadx-win-apps.log 2>&1 &
      ;;
    quit)
      break
      ;;
  esac
done
kill -9 $DBUS $XVNC $PULSE 2>/dev/null
exit 0'

SESSION=$(echo "$SESSION" | sed -e "s/GEOMETRY/$GEO/" -e "s/DPIVALUE/$DPI/" -e "s/SOUNDVALUE/$SOUND/")

# Not exec'd, for the same reason as linux-rt.sh: this script outlives proot
# by exactly long enough to clear win.pid. --kill-on-exit takes every app
# with the session when it ends.
#
# stdin goes in through fd 3: a shell without job control gives a background
# job /dev/null for stdin, and the session would read "end of input" before
# its first command.
exec 3<&0
"$PROOT" -0 --link2symlink --kill-on-exit -r "$ROOT/rootfs" \
  -b /dev -b /proc -b /sys -b "$ROOT/tmp:/tmp" -b "$ROOT/shm:/dev/shm" \
  $STORAGE_BIND $SHARED_BIND \
  -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  XDG_CONFIG_DIRS=/usr/local/etc/xdg:/etc/xdg \
  /bin/sh -c "$SESSION" <&3 &
PROOT_PID=$!
exec 3<&-
wait "$PROOT_PID"

rm -f "$ROOT/win.pid"
