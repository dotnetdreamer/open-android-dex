#!/system/bin/sh
# linux-app.sh ID -- one Linux app in a proot of its own. POSIX sh for toybox
# /system/bin/sh, like linux-rt.sh, whose rules it follows.
#
# Spawned by the app's LinuxAppsService, which runs in the Android process
# ":linuxapps". That is the whole point of this script: a process belongs to
# the Android process that started it, and Android's child-process limit
# (doc/linux-apps-process.md) closes the least important Android process's
# children first, oldest first. :linuxapps is kept one step less important
# than :linux, where the desktop runs, so when the phone has too many
# processes Android closes apps started from here, and the desktop stays.
#
# ID names the request guest-side oadx-run left in /tmp/.oadx-apps and the
# service claimed by renaming it to ID.run: a shell script that removes itself,
# sets the environment of whatever asked (the desktop session, the window
# session), changes to its folder and execs the app.
#
# One proot per app, not one for all of them: the oldest process of an app is
# its proot, and a proot killed takes everything under it. With one proot for
# every app, the first kill would close them all.
#
# No --kill-on-exit, unlike the sessions: an app's first process may hand over
# to another and exit (a launcher script that starts the real binary in the
# background, VS Code's CLI), and the app must stay. proot ends when the last
# process under it does.
#
# Load-bearing, as in linux-rt.sh: this script makes itself a session leader,
# so its pid is a process-group id, and it execs proot under that same pid.
# The service group-kills ID.pid to close the app, and does so again when proot
# ends, for whatever outlived it (Android's limit kills processes one by one).

ROOT=${LINUX_ROOT:-/data/local/tmp/linux}
ID=$1
case "$ID" in
  ''|*[!0-9A-Za-z._-]*) echo "linux-app.sh: bad id: $ID"; exit 2 ;;
esac
DIR=$ROOT/tmp/.oadx-apps
[ -f "$DIR/$ID.run" ] || { echo "linux-app.sh: no request $ID"; exit 2; }

# `setsid sh "$0"`, not `setsid "$0"`: the script lives in app storage, which
# may not be executed (W^X). See linux-rt.sh.
if [ -z "$LINUX_APP_SETSID" ] && [ -x /system/bin/setsid ]; then
  LINUX_APP_SETSID=1
  export LINUX_APP_SETSID
  exec /system/bin/setsid /system/bin/sh "$0" "$@"
fi

echo $$ > "$DIR/$ID.pid"

mkdir -p "$ROOT/tmp" "$ROOT/shm"

PROOT=${LINUX_PROOT:-$ROOT/proot/root/bin/proot}
[ "$(cat "$ROOT/noseccomp" 2>/dev/null)" = 1 ] && export PROOT_NO_SECCOMP=1
export PROOT_LOADER=${PROOT_LOADER:-$ROOT/proot/root/libexec/proot/loader}
export PROOT_LOADER_32=${PROOT_LOADER_32:-$ROOT/proot/root/libexec/proot/loader32}
export PROOT_TMP_DIR=$ROOT/tmp

# The same storage binds as the sessions, so an app sees the same folders the
# desktop does -- see linux-rt.sh, including why a bad -b source must never
# reach proot. The mount point is made as linux-win.sh makes it.
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

# The request sets the whole environment of whoever asked, so this starts from
# nothing but enough to run it.
#
# Inside, the request is claimed once more before it runs: renamed from ID.run
# to ID.go. oadx-run takes ID.run back if it is still there at its ten
# seconds, and the rename is the one step both can try, so a proot that comes
# up late either wins it or finds it gone and starts nothing -- the app never
# starts twice.
exec "$PROOT" -0 --link2symlink -r "$ROOT/rootfs" \
  -b /dev -b /proc -b /sys -b "$ROOT/tmp:/tmp" -b "$ROOT/shm:/dev/shm" \
  $STORAGE_BIND $SHARED_BIND \
  -w /root /usr/bin/env -i HOME=/root \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /bin/sh -c 'mv -f -- "$1" "$2" 2>/dev/null || exit 75; exec /bin/sh "$2"' \
  oadx-app "/tmp/.oadx-apps/$ID.run" "/tmp/.oadx-apps/$ID.go"
