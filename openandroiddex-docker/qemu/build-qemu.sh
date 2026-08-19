#!/bin/bash
# build-qemu.sh — runs INSIDE the Docker image (see Dockerfile). Cross-compiles
# qemu-system-aarch64 and its dependency stack for the two ABIs the launcher
# ships, 16 KB page aligned.
#
# Only aarch64-softmmu is built, for BOTH host ABIs. One guest architecture
# means one guest image to build, host and verify — and since there is no KVM
# on any Android device we can reach (see the Dockerfile header), an x86_64
# host would be emulating just as hard as an arm64 one. The x86_64 artifact
# exists so the feature can be exercised on the emulator, not because it is
# fast there.
#
# Every dependency is linked STATIC, so what lands in jniLibs is a single
# self-contained libqemu.so with no RUNPATH games. proot is the precedent for
# shipping an executable under a lib*.so name: nativeLibraryDir is the only
# directory a targetSdk 29+ app may exec from.
set -euo pipefail

ZLIB_V=${ZLIB_V:-1.3.1}
FFI_V=${FFI_V:-3.4.6}
PCRE2_V=${PCRE2_V:-10.44}
GLIB_V=${GLIB_V:-2.82.5}
GLIB_SERIES=${GLIB_SERIES:-2.82}
PIXMAN_V=${PIXMAN_V:-0.44.2}
SLIRP_V=${SLIRP_V:-4.8.0}
DTC_V=${DTC_V:-1.7.2}
QEMU_V=${QEMU_V:-9.2.3}

NDK=${NDK:-/opt/android-ndk-r27c}
# API 28 is the floor for glib: iconv_open arrived in bionic there.
API=${API:-28}

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
ALIGN='-Wl,-z,max-page-size=16384'

BUILD=/build/work
OUT=/build/out
mkdir -p "$BUILD" "$OUT"

# <clang arch>:<jniLibs ABI>:<meson cpu_family>
ARCHS=${ARCHS:-'aarch64:arm64-v8a:aarch64 x86_64:x86_64:x86_64'}

# ── sources ───────────────────────────────────────────────────────────────
cd "$BUILD"
fetch() { # <url> <dir-that-proves-it-is-here>
  [ -d "$2" ] && return 0
  echo "── fetching $1"
  # Explicit decompressor rather than tar's auto-detect: reading from a pipe it
  # refuses to sniff and dies with "Archive is compressed".
  case "$1" in
    *.tar.xz)  curl -fsSL "$1" | tar -xJ ;;
    *.tar.bz2) curl -fsSL "$1" | tar -xj ;;
    *)         curl -fsSL "$1" | tar -xz ;;
  esac
}
fetch "https://zlib.net/fossils/zlib-$ZLIB_V.tar.gz"                                        "zlib-$ZLIB_V"
fetch "https://github.com/libffi/libffi/releases/download/v$FFI_V/libffi-$FFI_V.tar.gz"      "libffi-$FFI_V"
fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_V/pcre2-$PCRE2_V.tar.gz" "pcre2-$PCRE2_V"
fetch "https://download.gnome.org/sources/glib/$GLIB_SERIES/glib-$GLIB_V.tar.xz"             "glib-$GLIB_V"
fetch "https://www.cairographics.org/releases/pixman-$PIXMAN_V.tar.gz"                       "pixman-$PIXMAN_V"
fetch "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v$SLIRP_V/libslirp-v$SLIRP_V.tar.gz" "libslirp-v$SLIRP_V"
fetch "https://git.kernel.org/pub/scm/utils/dtc/dtc.git/snapshot/dtc-$DTC_V.tar.gz"          "dtc-$DTC_V"
fetch "https://download.qemu.org/qemu-$QEMU_V.tar.xz"                                        "qemu-$QEMU_V"

# ── patches ───────────────────────────────────────────────────────────────
# Applied once, marked with a stamp so a rerun over a warm work volume does not
# try again and fail. Each patch says in its own header why it exists.
PATCHES=${PATCHES:-/build/patches}
if [ -d "$PATCHES" ] && [ ! -e "$BUILD/qemu-$QEMU_V/.patched" ]; then
  for P in "$PATCHES"/*.patch; do
    [ -e "$P" ] || continue
    echo "── patch $(basename "$P")"
    patch -p1 -d "$BUILD/qemu-$QEMU_V" < "$P"
  done
  touch "$BUILD/qemu-$QEMU_V/.patched"
fi

set_arch() {
  MARCH=$1
  ABI=$2
  MCPU=$3
  PREFIX="$BUILD/prefix-$MARCH"
  mkdir -p "$PREFIX"

  export CC="$TOOLCHAIN/bin/$MARCH-linux-android$API-clang"
  export CXX="$TOOLCHAIN/bin/$MARCH-linux-android$API-clang++"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export NM="$TOOLCHAIN/bin/llvm-nm"
  export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
  export LD="$CC"

  export CPPFLAGS="-I$PREFIX/include"
  export CFLAGS="-O2 -fPIC -I$PREFIX/include"
  export LDFLAGS="-L$PREFIX/lib $ALIGN"

  # Point pkg-config at OUR prefix only. Without LIBDIR it would answer with
  # the Debian base's .pc files and we would link the host's glib.
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  unset PKG_CONFIG_SYSROOT_DIR || true

  HOST_TRIPLE="$MARCH-linux-android"

  CROSS_FILE="$BUILD/cross-$MARCH.ini"
  cat > "$CROSS_FILE" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
ranlib = '$RANLIB'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-O2', '-fPIC', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib', '-Wl,-z,max-page-size=16384']

[host_machine]
system = 'android'
cpu_family = '$MCPU'
cpu = '$MCPU'
endian = 'little'
EOF
}

for SPEC in $ARCHS; do
  IFS=: read -r MA AB MC <<< "$SPEC"
  set_arch "$MA" "$AB" "$MC"
  echo "══════════════════════════════════════ $MARCH ($ABI)"

  # ── zlib ────────────────────────────────────────────────────────────────
  echo "── zlib"
  cd "$BUILD/zlib-$ZLIB_V"
  make distclean >/dev/null 2>&1 || true
  ./configure --prefix="$PREFIX" --static
  make -j"$(nproc)"
  make install
  make distclean >/dev/null 2>&1 || true

  # ── libffi ──────────────────────────────────────────────────────────────
  echo "── libffi"
  rm -rf "$BUILD/b-ffi-$MARCH"
  mkdir -p "$BUILD/b-ffi-$MARCH"
  cd "$BUILD/b-ffi-$MARCH"
  "$BUILD/libffi-$FFI_V/configure" --host="$HOST_TRIPLE" --prefix="$PREFIX" \
      --disable-shared --enable-static --disable-docs
  make -j"$(nproc)"
  make install

  # ── pcre2 (glib dependency since 2.74) ──────────────────────────────────
  echo "── pcre2"
  rm -rf "$BUILD/b-pcre2-$MARCH"
  mkdir -p "$BUILD/b-pcre2-$MARCH"
  cd "$BUILD/b-pcre2-$MARCH"
  "$BUILD/pcre2-$PCRE2_V/configure" --host="$HOST_TRIPLE" --prefix="$PREFIX" \
      --disable-shared --enable-static --enable-unicode
  make -j"$(nproc)"
  make install

  # ── glib ────────────────────────────────────────────────────────────────
  # Everything QEMU does not use is off: no tests (they cross-run), no
  # introspection, no libmount/selinux/xattr (none exist for an app uid).
  echo "── glib"
  rm -rf "$BUILD/b-glib-$MARCH"
  meson setup "$BUILD/b-glib-$MARCH" "$BUILD/glib-$GLIB_V" \
      --cross-file "$CROSS_FILE" --prefix="$PREFIX" \
      --default-library=static --buildtype=release \
      -Dtests=false -Dinstalled_tests=false -Dnls=disabled \
      -Dlibmount=disabled -Dselinux=disabled -Dxattr=false \
      -Dintrospection=disabled -Dglib_debug=disabled -Dman-pages=disabled
  meson compile -C "$BUILD/b-glib-$MARCH"
  meson install -C "$BUILD/b-glib-$MARCH"

  # ── pixman ──────────────────────────────────────────────────────────────
  echo "── pixman"
  rm -rf "$BUILD/b-pixman-$MARCH"
  meson setup "$BUILD/b-pixman-$MARCH" "$BUILD/pixman-$PIXMAN_V" \
      --cross-file "$CROSS_FILE" --prefix="$PREFIX" \
      --default-library=static --buildtype=release \
      -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled
  meson compile -C "$BUILD/b-pixman-$MARCH"
  meson install -C "$BUILD/b-pixman-$MARCH"

  # ── libslirp ────────────────────────────────────────────────────────────
  # The whole network story. No tap device is reachable without root, so the
  # VM's only way out is slirp's userspace NAT — and hostfwd is what puts
  # dockerd's socket on the app's own loopback.
  echo "── libslirp"
  rm -rf "$BUILD/b-slirp-$MARCH"
  meson setup "$BUILD/b-slirp-$MARCH" "$BUILD/libslirp-v$SLIRP_V" \
      --cross-file "$CROSS_FILE" --prefix="$PREFIX" \
      --default-library=static --buildtype=release
  meson compile -C "$BUILD/b-slirp-$MARCH"
  meson install -C "$BUILD/b-slirp-$MARCH"

  # ── libfdt ──────────────────────────────────────────────────────────────
  # QEMU's `virt` machine builds a device tree for the guest kernel, so this
  # is not optional. Built here rather than through QEMU's --enable-fdt=internal
  # because that subproject is fetched by meson at configure time, which would
  # put an unpinned download in the middle of a pinned build.
  echo "── libfdt"
  cd "$BUILD/dtc-$DTC_V"
  make clean >/dev/null 2>&1 || true
  make libfdt CC="$CC" AR="$AR" NO_PYTHON=1 NO_YAML=1 NO_VALGRIND=1 \
      CFLAGS="$CFLAGS" -j"$(nproc)"
  mkdir -p "$PREFIX/include" "$PREFIX/lib/pkgconfig"
  cp libfdt/libfdt.a "$PREFIX/lib/"
  cp libfdt/libfdt.h libfdt/libfdt_env.h libfdt/fdt.h "$PREFIX/include/"
  {
    echo "prefix=$PREFIX"
    echo 'libdir=${prefix}/lib'
    echo 'includedir=${prefix}/include'
    echo
    echo 'Name: libfdt'
    echo 'Description: Flat Device Tree manipulation'
    echo "Version: $DTC_V"
    echo 'Libs: -L${libdir} -lfdt'
    echo 'Cflags: -I${includedir}'
  } > "$PREFIX/lib/pkgconfig/libfdt.pc"
  make clean >/dev/null 2>&1 || true

  # ── qemu ────────────────────────────────────────────────────────────────
  # --without-default-features then re-enable by hand: the default set drags in
  # a dozen host libraries that have no Android build, and every one of them is
  # a thing we would have to cross-compile to get a feature the VM never uses.
  echo "── qemu"
  rm -rf "$BUILD/b-qemu-$MARCH"
  mkdir -p "$BUILD/b-qemu-$MARCH"
  cd "$BUILD/b-qemu-$MARCH"
  # AR/RANLIB/STRIP/OBJCOPY/NM come from the environment — configure has no
  # flags for them, only --cc/--cxx. --cpu names the HOST we are building for,
  # which cross-detection cannot guess from an NDK clang.
  "$BUILD/qemu-$QEMU_V/configure" \
      --prefix="$PREFIX" \
      --cross-prefix="" \
      --cc="$CC" --cxx="$CXX" --host-cc=cc \
      --cpu="$MARCH" \
      --target-list=aarch64-softmmu \
      --without-default-features \
      --enable-tcg --enable-slirp --enable-fdt=system --enable-pixman \
      --enable-system --disable-user --disable-tools --disable-guest-agent \
      --disable-docs --disable-install-blobs --disable-containers \
      --audio-drv-list="" \
      --extra-cflags="$CFLAGS" --extra-ldflags="$LDFLAGS" \
      --disable-werror
  make -j"$(nproc)"

  mkdir -p "$OUT/$ABI"
  cp qemu-system-aarch64 "$OUT/$ABI/libqemu.so"
  "$STRIP" "$OUT/$ABI/libqemu.so"

  # 16 KB alignment is a hard requirement on Android 15+ devices with 16 KB
  # pages: an ELF whose LOAD segments are only 4 KB aligned cannot be mapped
  # there at all. Same check the proot build ends with.
  python3 /build/check-align.py "$OUT/$ABI/libqemu.so"
done

echo "done — artifacts in $OUT"
ls -laR "$OUT"
