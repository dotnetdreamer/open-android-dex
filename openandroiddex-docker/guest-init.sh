#!/bin/sh
# guest-init.sh — this runs as PID 1 INSIDE the virtual machine.
#
# It is packed into a tiny cpio.gz that the app appends to Alpine's stock
# initramfs-virt, and the kernel unpacks both in order, so this /init replaces
# theirs while every binary and module they shipped stays available. That
# concatenation trick is the whole reason the app never has to parse or rebuild
# an initramfs: it only has to WRITE a three-file archive.
#
# Two modes, decided by whether the root disk carries our marker:
#
#   install — first run. Untar Alpine's minirootfs into RAM, use its apk to get
#             e2fsprogs, format the root disk, then install the real system (docker
#             included) straight onto that disk with `apk --root`.
#   boot    — every run after. Mount that disk and switch_root into it.
#
# Progress is reported by printing @@OADX lines to the console, which is the
# app's only window into the VM: DockerService tails console.log for them. Keep
# them cheap and keep them parseable.
#
# POSIX sh only — this is busybox ash from Alpine's initramfs, and the full
# userland does not exist until switch_root.

# Two things have to happen before a single ordinary command works in here,
# and both were learned the hard way on the phone (every applet came back
# "not found" and the script marched all the way to "no-root-disk" having
# silently failed to mkdir, mount and sleep):
#
#  1. PID 1 inherits NOTHING. The kernel hands init an empty environment, so
#     there is no PATH until we set one.
#  2. Alpine's initramfs contains busybox but NOT its applet symlinks — the
#     whole archive is bin/busybox, bin/sh, bin/kmod, sbin/apk, sbin/modprobe
#     and sbin/nlplug-findfs. `busybox --install -s` is what turns that into a
#     userland. Alpine's own init does exactly this on its first two lines.
PATH=/bin:/sbin:/usr/bin:/usr/sbin
export PATH
/bin/busybox mkdir -p /proc /sys /dev /mnt /newroot /usr/bin /usr/sbin
/bin/busybox --install -s

ALPINE_BRANCH=v3.22
# http, not https: the minirootfs ships no CA bundle, and apk verifies every
# index and package against the RSA keys in /etc/apk/keys regardless of
# transport. TLS here would buy nothing and cost a ca-certificates chicken-egg.
APK_REPO=http://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH

# Everything the guest system needs, and nothing else. docker-cli-compose is
# separate from docker in Alpine. e2fsprogs has to be here as well as in the
# installer so a later fsck is possible from inside.
GUEST_PKGS="alpine-base openrc busybox-openrc e2fsprogs docker docker-cli-compose iptables ip6tables"

MARKER=/mnt/.oadx-docker-root

say() { echo "@@OADX $*"; }

# Three resolvers, and the order is not the point — musl queries every listed
# server in PARALLEL and takes the first answer, so a dead one costs nothing.
#
# 10.0.2.3 is slirp's own DNS proxy, and on a Linux host it would be the only
# line here. It does not work on Android: slirp learns the upstream servers by
# reading the host's /etc/resolv.conf, and Android has no such file — DNS lives
# in netd. Measured on the phone: routing and TCP were fine while every apk
# fetch failed with "temporary error (try again later)".
#
# So the public resolvers are the ones that actually answer, and 10.0.2.3 stays
# first for the day this runs somewhere that does have a host resolver list.
write_resolv_conf() {
    {
        echo "nameserver 10.0.2.3"
        echo "nameserver 1.1.1.1"
        echo "nameserver 8.8.8.8"
    } > "$1"
}
# Bumped whenever the inittab below changes. Boot mode rewrites the file when
# it does not carry the current tag, so a fix to the console reaches machines
# that are ALREADY installed — the alternative was telling everyone with a
# working Docker to throw it away over a login prompt.
INITTAB_TAG="# oadx-inittab v2"

write_inittab() { # $1 = the guest root
    cat > "$1/etc/inittab" <<EOF
$INITTAB_TAG
::sysinit:/sbin/openrc sysinit
::sysinit:/sbin/openrc boot
::wait:/sbin/openrc default
# A root shell straight away — no getty, no login prompt. The first build ran
# a getty here and the window's Console tab opened on "oadx-docker login:",
# which is a password nobody was ever given: root's is blank on purpose (see
# below), so the prompt was pure obstruction. The security boundary is the
# unix socket in the app's private storage, not a login on the far side of it.
ttyAMA0::respawn:-/bin/sh
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown
EOF
}

die() {
    say phase=error pct=0 msg="$1"
    echo "!! $1"
    # Do not reboot into the same failure forever; hold the console so the
    # user (and the log) keep whatever the last error was.
    exec /bin/sh
}

# ── kernel-side plumbing ──────────────────────────────────────────────────
mkdir -p /proc /sys /dev /mnt /newroot
mount -t proc none /proc 2>/dev/null
mount -t sysfs none /sys 2>/dev/null
mount -t devtmpfs none /dev 2>/dev/null

KVER=$(ls /lib/modules 2>/dev/null | head -n 1)
say phase=boot pct=2 msg=kernel-$KVER

# virtio_mmio rather than PCI: QEMU's `virt` machine advertises the mmio
# transports in the device tree, and virtio_mmio.ko is one of the modules
# Alpine ships in the initramfs itself.
for m in virtio_mmio virtio_blk virtio_net squashfs loop overlay; do
    modprobe "$m" 2>/dev/null
done
# Give the block devices a moment to enumerate; there is no udev out here.
i=0
while [ ! -e /dev/vda ] && [ $i -lt 50 ]; do
    i=$((i + 1))
    sleep 0.1
done
if [ ! -e /dev/vda ]; then
    # Worth printing: every reason this happens (a module that did not load, a
    # -drive the host got wrong) looks identical from the marker alone.
    echo "block devices:"
    ls -l /dev/vd* /dev/disk 2>&1
    die no-root-disk
fi

# ── which disk is which ───────────────────────────────────────────────────
# By CONTENT, never by name. On QEMU's `virt` machine the virtio-mmio
# transports are assigned from the top of the address range downward, so the
# kernel enumerates them in the REVERSE of the order the -device arguments were
# written: the first drive on the command line came up as /dev/vdc and the
# modloop as /dev/vda. That cost a run of `mkfs.ext4` aimed at a read-only
# squashfs ("/dev/vda contains a squashfs file system … Operation not
# permitted"), which is exactly the kind of mistake a name-based assumption
# makes silently until it does not.
#
# Magic numbers instead: squashfs is 'hsqs', the minirootfs is a gzip stream,
# and the root disk is whatever is left — zeroes before the install, an ext4
# superblock (which starts at offset 1024, so still zeroes here) after it.
disk_kind() {
    case "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
        68737173*) echo squashfs ;;
        1f8b*)     echo gzip ;;
        *)         echo other ;;
    esac
}

ROOTDEV=""
BASEDEV=""
MODDEV=""
for d in /dev/vd?; do
    [ -b "$d" ] || continue
    case "$(disk_kind "$d")" in
        squashfs) MODDEV=$d ;;
        gzip)     BASEDEV=$d ;;
        *)        ROOTDEV=$d ;;
    esac
done
echo "disks: root=$ROOTDEV base=$BASEDEV modloop=$MODDEV"
[ -n "$ROOTDEV" ] || die no-root-disk
[ -n "$MODDEV" ] || die no-modloop-disk

# ── modules ───────────────────────────────────────────────────────────────
# ext4 is a MODULE in Alpine's virt kernel, and it is not one of the 130 the
# initramfs carries — so without this every mount of the root disk fails with
# "No such device", including the one right after a successful mkfs. So are
# overlay, br_netfilter, veth and the netfilter tables Docker needs later.
#
# The modloop is a squashfs of modules/<kver>/, complete with its modules.dep.
# Binding it over the initramfs's partial /lib/modules/<kver> makes modprobe
# see the full set, and binding it again into the new root before switch_root
# is what keeps them there — an ordinary bind survives the switch, whereas the
# squashfs mount inside the old rootfs would not.
mkdir -p /.modloop
mount -t squashfs -o ro "$MODDEV" /.modloop || die modloop-mount-failed
[ -d "/.modloop/modules/$KVER" ] \
    && mount -o bind "/.modloop/modules/$KVER" "/lib/modules/$KVER"
modprobe ext4 || die no-ext4

# Give the new root its modules, wherever we end up switching into it.
attach_modloop() { # $1 = the about-to-be root
    mkdir -p "$1/.modloop" "$1/lib/modules/$KVER"
    mount -o bind /.modloop "$1/.modloop"
    mount -o bind "/.modloop/modules/$KVER" "$1/lib/modules/$KVER"
}

# ── boot mode ─────────────────────────────────────────────────────────────
# Try the installed system first. A mount failure here is the normal state on
# the very first run, not an error.
if mount -t ext4 -o rw "$ROOTDEV" /mnt 2>/dev/null && [ -f "$MARKER" ]; then
    say phase=boot pct=90 msg=mounting
    # Unmount and mount again rather than `mount --move`: busybox's mount does
    # not take that flag, and the disk is the same either way.
    umount /mnt
    mount -t ext4 -o rw "$ROOTDEV" /newroot || die mount-root-failed
    attach_modloop /newroot
    say phase=boot pct=95 msg=modloop
    # Reconcile the parts of the guest this script owns. Cheap, idempotent, and
    # the only way a fix lands on a machine that is already installed.
    grep -q "$INITTAB_TAG" /newroot/etc/inittab 2>/dev/null || {
        echo "updating inittab to $INITTAB_TAG"
        write_inittab /newroot
    }
    umount /proc 2>/dev/null
    umount /sys 2>/dev/null
    say phase=boot pct=98 msg=switch-root
    exec switch_root /newroot /sbin/init
fi
umount /mnt 2>/dev/null

# ── install mode ──────────────────────────────────────────────────────────
say phase=install pct=5 msg=unpacking-base

# The minirootfs arrives as a whole disk whose contents are literally the
# .tar.gz — no filesystem, nothing to mount. tar reads it as a stream.
mount -t tmpfs -o size=512m none /newroot || die tmpfs-failed
tar -xzf "$BASEDEV" -C /newroot || die minirootfs-unpack-failed

mount -t proc none /newroot/proc 2>/dev/null
mount -t sysfs none /newroot/sys 2>/dev/null
mount -o bind /dev /newroot/dev 2>/dev/null

echo "$APK_REPO/main" > /newroot/etc/apk/repositories
echo "$APK_REPO/community" >> /newroot/etc/apk/repositories
write_resolv_conf /newroot/etc/resolv.conf

# slirp hands out a lease on 10.0.2.15 and routes through 10.0.2.2. Configured
# by hand rather than by udhcpc because there is no networking service out here
# and the addresses are fixed by QEMU's user-mode stack anyway.
ip link set lo up 2>/dev/null
ip link set eth0 up 2>/dev/null
ip addr add 10.0.2.15/24 dev eth0 2>/dev/null
ip route add default via 10.0.2.2 2>/dev/null

say phase=install pct=12 msg=fetching-installer
# Loud on failure. This is the first thing in the whole flow that depends on
# the outside world, so it is where a bad NIC name, a missing virtio_net or a
# firewalled CDN all surface — and with the output swallowed they were
# indistinguishable from each other.
if ! chroot /newroot /sbin/apk update >/newroot/apk-update.log 2>&1; then
    echo "--- ip addr ---"
    ip addr
    echo "--- ip route ---"
    ip route
    echo "--- apk update ---"
    cat /newroot/apk-update.log
    die apk-update-failed
fi
chroot /newroot /sbin/apk add --no-progress e2fsprogs >/dev/null 2>&1 \
    || die e2fsprogs-failed

say phase=install pct=20 msg=formatting-disk
# ^metadata_csum_seed and 64bit off: nothing here needs them and they are the
# two features an older e2fsck refuses to touch, which matters the day someone
# has to repair this disk from a different Alpine.
chroot /newroot /sbin/mkfs.ext4 -F -q -L oadx-docker -O ^64bit "$ROOTDEV" \
    || die mkfs-failed

mkdir -p /newroot/mnt
mount -t ext4 -o rw "$ROOTDEV" /newroot/mnt || die mount-new-root-failed

# apk --root needs the target to have keys and repositories of its own before
# --initdb; it does not inherit the ones we are running from.
mkdir -p /newroot/mnt/etc/apk/keys
cp /newroot/etc/apk/keys/* /newroot/mnt/etc/apk/keys/ 2>/dev/null
cp /newroot/etc/apk/repositories /newroot/mnt/etc/apk/repositories

say phase=install pct=30 msg=installing-alpine
# shellcheck disable=SC2086
chroot /newroot /sbin/apk add --root /mnt --initdb --no-progress $GUEST_PKGS \
    >/newroot/apk.log 2>&1 || {
    tail -20 /newroot/apk.log
    die guest-packages-failed
}

say phase=install pct=70 msg=configuring
R=/newroot/mnt

# Mount points only — the modules themselves stay on the modloop and are bound
# in at every boot by attach_modloop. Copying them onto the disk would work but
# duplicates ~60 MB, and installing Alpine's linux-virt package instead would
# be worse than either: it tracks Alpine's CURRENT kernel and would stop
# matching the vmlinuz the app has pinned.
mkdir -p "$R/.modloop" "$R/lib/modules/$KVER"

# Static, not dhcp. Two reasons, and the second one is the one that bites:
# QEMU's user-mode stack always hands out the same three addresses, so there is
# nothing to discover — and udhcpc REWRITES /etc/resolv.conf from the lease,
# which would replace the resolvers written just below with slirp's 10.0.2.3
# alone, the one that cannot answer on Android.
cat > "$R/etc/network/interfaces" <<EOF
auto lo
iface lo inet loopback

auto eth0
iface eth0 inet static
    address 10.0.2.15
    netmask 255.255.255.0
    gateway 10.0.2.2
EOF

write_resolv_conf "$R/etc/resolv.conf"
echo "oadx-docker" > "$R/etc/hostname"

# dockerd on TCP as well as its unix socket: the TCP side is the only one the
# Android app can reach, through QEMU's hostfwd onto the phone's loopback.
# 0.0.0.0 is not as open as it looks — slirp gives this VM no route from
# anywhere except the emulator's own port forward.
cat > "$R/etc/conf.d/docker" <<EOF
DOCKER_OPTS="-H unix:///var/run/docker.sock -H tcp://0.0.0.0:2375"
EOF

# A root shell on the serial console. This is the terminal the Docker window
# shows, so it is a feature, not a debugging leftover.
write_inittab "$R"
echo "ttyAMA0" >> "$R/etc/securetty"

# No password on root. The console is reachable only through a unix socket in
# this app's private storage, and dockerd on the same VM already grants
# root-equivalent power to anything that can reach its port — a password here
# would be theatre that only locks the user out of their own terminal.
sed -i 's|^root:[^:]*:|root::|' "$R/etc/shadow" 2>/dev/null

chroot "$R" /sbin/rc-update add devfs sysinit >/dev/null 2>&1
chroot "$R" /sbin/rc-update add procfs sysinit >/dev/null 2>&1
chroot "$R" /sbin/rc-update add sysfs sysinit >/dev/null 2>&1
chroot "$R" /sbin/rc-update add cgroups sysinit >/dev/null 2>&1
chroot "$R" /sbin/rc-update add networking boot >/dev/null 2>&1
chroot "$R" /sbin/rc-update add hostname boot >/dev/null 2>&1
chroot "$R" /sbin/rc-update add docker default >/dev/null 2>&1

# Written last, and only once everything above succeeded: this file is what
# tells the next boot to skip install mode entirely. A half-installed disk
# without it gets formatted again rather than booted into.
date > "$R/.oadx-docker-root"
sync

say phase=install pct=88 msg=starting

umount /newroot/proc 2>/dev/null
umount /newroot/sys 2>/dev/null
umount /newroot/dev 2>/dev/null

# Get the new root out of the doomed tmpfs before switching into it. Unmount
# and mount again rather than `mount --move`, which busybox does not take.
mkdir -p /newroot2
umount "$R" || die unmount-new-root-failed
mount -t ext4 -o rw "$ROOTDEV" /newroot2 || die remount-new-root-failed
attach_modloop /newroot2

umount /proc 2>/dev/null
umount /sys 2>/dev/null
say phase=boot pct=98 msg=switch-root
exec switch_root /newroot2 /sbin/init
