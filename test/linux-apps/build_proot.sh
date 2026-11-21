#!/bin/sh
# The proot the app ships, built for this machine into /opt/proot (proot and
# its loader): green-green-avk/proot v0.15_release with
# openandroiddex-linux/proot/patches, as openandroiddex-linux/proot builds it
# for Android, but with this distro's gcc and talloc and no 32-bit loader. For
# runner_check.sh and dbus_check.sh: what proot does to a process it traces --
# its signals, the user it fakes, the credentials it sends over a socket -- is
# the same code on both. Needs gcc, libc6-dev, make, libtalloc-dev, patch, curl.
set -u
REPO=${REPO:-/work}
[ -x /opt/proot/proot ] && [ -f /opt/proot/loader ] && exit 0
mkdir -p /opt/src /opt/proot
cd /opt/src || exit 3
rm -rf proot-0.15_release
curl -fsSL https://github.com/green-green-avk/proot/archive/v0.15_release.tar.gz | tar -xz || { echo "  FAIL proot source download"; exit 3; }
for p in "$REPO"/openandroiddex-linux/proot/patches/*.patch; do
  patch -d proot-0.15_release -p1 --forward < "$p" >/dev/null || { echo "  FAIL patch $p"; exit 3; }
done
# Its compat.h defines SYS_SECCOMP as a number when included before signal.h,
# which a newer glibc's enum of the same name then fails on (Ubuntu 26.04's,
# not 24.04's, which has none); there, named as itself, it is "defined", and
# the enum stays the enum. The -Wno-errors are for a
# newer gcc's stricter defaults on old C, and sys/ioctl.h is where glibc keeps
# the TCSETS numbers its tcsetsf2tcsets extension uses (bionic has them in
# termios.h). The loader first, on its own: it has assembly, which no C header
# may be forced into. Unbundled, as the app ships it (libloader.so), and found
# through PROOT_LOADER.
SECCOMP=
# Asked the way proot is built, with _GNU_SOURCE, which is what declares it.
printf '#include <signal.h>\nint x = SYS_SECCOMP;\n' | gcc -D_GNU_SOURCE -x c -c - -o /dev/null 2>/dev/null &&
  SECCOMP=-DSYS_SECCOMP=SYS_SECCOMP
{ make -C proot-0.15_release/src loader/loader HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=. &&
  make -C proot-0.15_release/src proot HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=. \
    EXT_CPPFLAGS="-D_FILE_OFFSET_BITS=64 -std=gnu17 $SECCOMP -include sys/ioctl.h -Wno-error=incompatible-pointer-types -Wno-error=implicit-function-declaration -Wno-error=int-conversion"
} >/tmp/proot-build.log 2>&1 ||
  { tail -15 /tmp/proot-build.log; echo "  FAIL proot build"; exit 3; }
cp proot-0.15_release/src/proot /opt/proot/proot
cp proot-0.15_release/src/loader/loader /opt/proot/loader
chmod 755 /opt/proot/proot /opt/proot/loader
echo "  ok   proot built from green-green-avk/proot v0.15_release and this repo's patches"
