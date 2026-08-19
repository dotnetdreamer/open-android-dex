#!/system/bin/sh
# docker-rt.sh — run the Docker virtual machine. POSIX sh for toybox
# /system/bin/sh, same as ../openandroiddex-linux/linux-rt.sh.
#
# Spawned by DockerService. The two load-bearing facts are the same ones that
# script documents, for the same reasons:
#
#  1. It makes itself a SESSION LEADER, so its pid doubles as a process-group
#     id and one `kill -9 -<pid>` takes QEMU down with it. An app uid cannot
#     read other processes' cmdlines, so pattern kills are unreliable here;
#     a group kill needs no /proc at all.
#  2. It removes rt.pid when QEMU exits, because rt.pid is the app's only
#     "is the VM up" signal.
#
# Unlike the Linux window there is no proot and no guest tree on Android's
# filesystem: everything the VM does happens inside root.img, and the only
# things crossing the boundary are the console socket and one forwarded TCP
# port. That is the entire reason this feature exists — see ./README.md.

ROOT=${DOCKER_ROOT:-/data/local/tmp/docker}
QEMU=${DOCKER_QEMU:-$ROOT/libqemu.so}

# Re-exec under setsid once. Guarded by an env flag because setsid refuses to
# run when the caller already leads its group. `setsid sh "$0"`, never
# `setsid "$0"`: this script lives in private storage, which is not executable
# and may not be made so for a target-SDK-35 app, so setsid must be handed the
# INTERPRETER and never the script.
if [ -z "$DOCKER_RT_SETSID" ] && [ -x /system/bin/setsid ]; then
  DOCKER_RT_SETSID=1
  export DOCKER_RT_SETSID
  exec /system/bin/setsid /system/bin/sh "$0" "$@"
fi

# Clear the previous run's verdict BEFORE announcing this one, so the app can
# never read a live rt.pid next to a stale exit marker.
rm -f "$ROOT/rt.exit" "$ROOT/console.sock"
echo $$ > "$ROOT/rt.pid"

MEM=$(cat "$ROOT/mem" 2>/dev/null)
[ -n "$MEM" ] || MEM=2048
CPUS=$(cat "$ROOT/cpus" 2>/dev/null)
[ -n "$CPUS" ] || CPUS=4
PORT=$(cat "$ROOT/port" 2>/dev/null)
[ -n "$PORT" ] || PORT=2375

mkdir -p "$ROOT/tmp"
TMPDIR="$ROOT/tmp"
export TMPDIR

# Console wiring, and the one part worth reading twice. The chardev's own
# logfile captures the guest from the very first kernel line whether or not
# anything is attached, which is what DockerService watches for the @@OADX
# progress markers. The socket on the same chardev is the INTERACTIVE side —
# the terminal in the Docker window connects to it, and may come and go
# without costing us a byte of the log. Doing this with `-serial file:` plus a
# second port would have meant choosing between a complete log and a usable
# terminal.
#
# -display none because no UI backend is compiled in at all (see
# qemu/build-qemu.sh); the VM is headless by construction, not by option.
#
# NOT exec'd: this shell has to outlive QEMU by one step so it can record why
# the VM went away. rt.exit's EXISTENCE is the load-bearing half — the window
# starts a VM whenever none is running, so with no marker a guest-side
# `poweroff` would silently boot a fresh one straight back. The number in it
# only chooses the wording.
run_qemu() { # $1 = console mode: sock | file
  set -- \
    -name oadx-docker \
    -machine virt \
    -cpu cortex-a72 \
    -smp "$CPUS" \
    -m "$MEM" \
    -accel tcg,thread=multi \
    -display none \
    -rtc base=utc \
    -kernel "$ROOT/vmlinuz" \
    -initrd "$ROOT/boot.img" \
    -append "console=ttyAMA0 root=/dev/vda rw panic=10 loglevel=4" \
    -drive "if=none,id=hd0,file=$ROOT/root.img,format=raw,cache=unsafe,discard=unmap" \
    -device virtio-blk-device,drive=hd0 \
    -drive "if=none,id=hd1,file=$ROOT/minirootfs.tar.gz,format=raw,readonly=on" \
    -device virtio-blk-device,drive=hd1 \
    -drive "if=none,id=hd2,file=$ROOT/modloop.img,format=raw,readonly=on" \
    -device virtio-blk-device,drive=hd2 \
    -netdev "user,id=n0,hostfwd=tcp:127.0.0.1:$PORT-:2375" \
    -device virtio-net-device,netdev=n0 \
    -device virtio-rng-device \
    "$@"
  "$QEMU" "$@"
}

# Socket console first, plain log file if the bind is refused.
#
# Binding a unix socket is an SELinux decision, not a filesystem one: under the
# app's own filesDir (app_data_file) it is allowed, but the same command run
# over adb into /data/local/tmp dies with "Failed to bind socket … Permission
# denied" because shell_data_file has no sock_file create. Rather than assume
# every device and every launch context agrees with the one we measured, try
# the good console and accept the read-only one — a VM whose terminal is
# view-only beats no VM at all.
run_qemu -chardev \
    "socket,id=con,path=$ROOT/console.sock,server=on,wait=off,logfile=$ROOT/console.log" \
    -serial chardev:con
RC=$?
if [ "$RC" -ne 0 ] && [ ! -s "$ROOT/console.log" ]; then
  echo "console socket refused — falling back to a log-only console" >&2
  rm -f "$ROOT/console.sock"
  run_qemu -serial "file:$ROOT/console.log"
  RC=$?
fi

echo "$RC" > "$ROOT/rt.exit"
rm -f "$ROOT/rt.pid" "$ROOT/console.sock"
exit "$RC"
