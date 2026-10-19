#!/system/bin/sh
# linux-ssh.sh — run the guest's SSH server for remote access. POSIX sh for
# toybox /system/bin/sh, like linux-rt.sh, and built on the same two facts:
#
#  1. It makes itself a SESSION LEADER, so its pid is a process-group id the
#     app can signal without reading /proc, which its uid cannot. Not every
#     process under it stays in that group — sshd setsid()s each login away —
#     so the app sends SIGQUIT first: proot answers it by SIGKILLing every
#     process it traces, logins and whatever they left running included, and
#     exiting. The group SIGKILL after that is for anything left (RemoteHost).
#  2. It removes ssh.pid when proot exits, which is how the app learns sshd is
#     gone (and starts it again, if remote access is still on).
#
# Separate from linux-rt.sh on purpose. The desktop and remote access have lives
# of their own: logging out of XFCE must not cut off someone working over SSH,
# and remote access exists precisely so the guest stays reachable with no window
# open at all. Two proot instances over one rootfs are two shells on one
# machine — nothing in the guest notices, and X clients started over SSH can
# still reach the desktop's display through its abstract socket.
#
# Inside the guest:
#   sshd   127.0.0.1:$LINUX_SSH_PORT — loopback only, so the one way in from
#          outside the phone is the dexremote tunnel, which connects here.
#          Public keys only, against the authorized_keys the app writes from
#          its list of paired computers.
#
# Environment (all from LinuxService):
#   LINUX_ROOT        the container (filesDir/linux)
#   LINUX_REMOTE_SSH  filesDir/linux-remote/ssh: sshd_config, the host key and
#                     authorized_keys, bound onto /etc/oadx-ssh. Outside the
#                     rootfs, so a reinstall keeps the pairings and the host key.
#   LINUX_SSH_PORT    the loopback port, picked by the app
#   LINUX_PROOT, PROOT_LOADER, PROOT_LOADER_32, LINUX_STORAGE, LINUX_SHARED
#                     exactly as for linux-rt.sh

ROOT=${LINUX_ROOT:-/data/local/tmp/linux}
REMOTE=$LINUX_REMOTE_SSH
PORT=${LINUX_SSH_PORT:-7022}

# Same re-exec as linux-rt.sh, for the same reasons: setsid refuses a caller
# that already leads its group, and must be handed the INTERPRETER because this
# script lives in app storage, which can never be exec'd (W^X).
if [ -z "$LINUX_SSH_SETSID" ] && [ -x /system/bin/setsid ]; then
  LINUX_SSH_SETSID=1
  export LINUX_SSH_SETSID
  exec /system/bin/setsid /system/bin/sh "$0" "$@"
fi

rm -f "$ROOT/ssh.exit"
echo $$ > "$ROOT/ssh.pid"

if [ -z "$REMOTE" ] || [ ! -f "$REMOTE/sshd_config" ]; then
  echo "linux-ssh: no sshd_config in '$REMOTE' - the app writes it before every start"
  rm -f "$ROOT/ssh.pid"
  echo 78 > "$ROOT/ssh.exit"
  exit 78
fi
if [ ! -x "$ROOT/rootfs/usr/sbin/sshd" ]; then
  echo "linux-ssh: openssh-server is not installed in the guest yet"
  rm -f "$ROOT/ssh.pid"
  echo 79 > "$ROOT/ssh.exit"
  exit 79
fi

mkdir -p "$ROOT/tmp" "$ROOT/shm"
# Privilege separation chroots its pre-auth child into /run/sshd, and sshd
# refuses to start without it. The mount point for the app's files is made
# here rather than left to proot, for the reason linux-rt.sh pre-creates the
# shared folder's: a binding point proot creates itself is removed by a
# destructor that a group SIGKILL never runs, leaving a mode-000 stub behind.
mkdir -p "$ROOT/rootfs/run/sshd" "$ROOT/rootfs/etc/oadx-ssh"
# Not through a link: the guest can put one at /run/sshd, and chmod follows it
# to wherever it points — outside the rootfs included.
[ -L "$ROOT/rootfs/run/sshd" ] || chmod 755 "$ROOT/rootfs/run/sshd" 2>/dev/null

PROOT=${LINUX_PROOT:-$ROOT/proot/root/bin/proot}
# The seccomp verdict linux-setup.sh probed and cached — the guest has to run
# the same way here as everywhere else.
[ "$(cat "$ROOT/noseccomp" 2>/dev/null)" = 1 ] && export PROOT_NO_SECCOMP=1
export PROOT_LOADER=${PROOT_LOADER:-$ROOT/proot/root/libexec/proot/loader}
export PROOT_LOADER_32=${PROOT_LOADER_32:-$ROOT/proot/root/libexec/proot/loader32}
export PROOT_TMP_DIR=$ROOT/tmp

# The same two storage binds the desktop gets, so a file is in the same place
# whichever way you reached the guest. Guarded the same way, because proot
# treats an unusable -b source as fatal.
STORAGE=${LINUX_STORAGE:-/sdcard}
STORAGE_BIND=
[ -r "$STORAGE" ] && STORAGE_BIND="-b $STORAGE:/root/storage"
SHARED=${LINUX_SHARED:-}
SHARED_BIND=
if [ -n "$SHARED" ] && [ -w "$SHARED" ]; then
  mkdir -p "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
  SHARED_BIND="-b $SHARED:/root/Desktop/LinuxOnDeX"
fi

echo "linux-ssh: sshd on 127.0.0.1:$PORT"

# None of the binds lands under /run/sshd, and that is load-bearing: proot only
# emulates a chroot into a directory with no bind mount inside it, and refuses
# the rest — which would take every login down at its first step.
#
# -D keeps sshd in the foreground (in this process group, where the kill can
# find it), and -e sends its log to stderr, which the app writes to ssh.log.
# The port is on the command line rather than in the config so the app can
# pick a free one without rewriting a file the guest can see.
"$PROOT" -0 --link2symlink --kill-on-exit -r "$ROOT/rootfs" \
  -b /dev -b /proc -b /sys -b "$ROOT/tmp:/tmp" -b "$ROOT/shm:/dev/shm" \
  -b "$REMOTE:/etc/oadx-ssh" \
  $STORAGE_BIND $SHARED_BIND \
  -w /root /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  XDG_CONFIG_DIRS=/usr/local/etc/xdg:/etc/xdg \
  /usr/sbin/sshd -D -e -f /etc/oadx-ssh/sshd_config -o "Port=$PORT" &
PROOT_PID=$!
wait "$PROOT_PID"
RC=$?

rm -f "$ROOT/ssh.pid"
echo "$RC" > "$ROOT/ssh.exit"
echo "linux-ssh: sshd exited $RC"
