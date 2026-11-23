#!/bin/bash
# build-virgl.sh — runs INSIDE the Docker image (see Dockerfile). Builds the
# VirGL render server for the two ABIs the launcher ships.
#
# One executable per ABI, named lib*.so so the installer extracts it into the
# native-lib dir, the one place an app targeting SDK 35 may exec from. libepoxy
# and virglrenderer are both built STATIC and linked into it, so it needs
# nothing on the phone but bionic — and the platform's libEGL / libGLESv2,
# which epoxy opens at run time with dlopen, the way any app's GL does.
#
# The only changes to upstream are the patches in patches/: Android having EGL
# but no GBM (0001, 0002), the app's check (0003, --probe), the NDK having no
# AOSP log or cutils headers (0004), and fragment outputs only for the colour
# buffers bound, which an Adreno 512 needed (0005). See doc/linux-gpu.md.
set -euo pipefail

VIRGL_V=1.3.0
# The tag archive GitLab serves for 1.3.0.
VIRGL_SHA256=065bc56e89e6f631f96101cd62eba0748e48eb888b434edc86e89d05395e76f3
EPOXY_V=1.5.10
EPOXY_SHA256=a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15

NDK=${NDK:-/opt/android-ndk-r30}
# The launcher's minSdk. Also the reason vtest's own memfd_create() wrapper
# compiles: bionic only declares memfd_create from API 30, and at 30 the two
# definitions would collide. The wrapper is a raw syscall, so it works on every
# kernel Android 8 shipped with.
API=${API:-26}

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
# <clang arch>:<jniLibs ABI>
ARCHS='aarch64:arm64-v8a x86_64:x86_64'

BUILD=/build/work
OUT=/build/out
mkdir -p "$BUILD" "$OUT"
cd "$BUILD"

# ── sources ───────────────────────────────────────────────────────────────
fetch() { # <url> <sha256> <dir>
  curl -fsSL -o src.tar.gz "$1"
  echo "$2  src.tar.gz" | sha256sum -c -
  rm -rf "$3" && mkdir -p "$3"
  tar -xzf src.tar.gz -C "$3" --strip-components=1
  rm src.tar.gz
}
fetch "https://github.com/anholt/libepoxy/archive/refs/tags/$EPOXY_V.tar.gz" \
  "$EPOXY_SHA256" libepoxy
fetch "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/$VIRGL_V/virglrenderer-$VIRGL_V.tar.gz" \
  "$VIRGL_SHA256" virglrenderer
for p in /build/patches/*.patch; do
  echo "══════════ patch $(basename "$p")"
  patch -d virglrenderer -p1 <"$p"
done

# ── per ABI ───────────────────────────────────────────────────────────────
for SPEC in $ARCHS; do
  MARCH=${SPEC%%:*}
  ABI=${SPEC##*:}
  PREFIX="$BUILD/prefix-$ABI"
  CROSS="$BUILD/cross-$ABI.txt"

  # system = 'android' is what patch 0001 keys off. The page size is r30's
  # default already; it is spelled out so a future NDK cannot quietly drop it.
  cat >"$CROSS" <<EOF
[binaries]
c = '$TOOLCHAIN/bin/$MARCH-linux-android$API-clang'
cpp = '$TOOLCHAIN/bin/$MARCH-linux-android$API-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'

[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384']

[host_machine]
system = 'android'
cpu_family = '$MARCH'
cpu = '$MARCH'
endian = 'little'
EOF

  echo "══════════ libepoxy $ABI"
  rm -rf "epoxy-$ABI" "$PREFIX"
  meson setup "epoxy-$ABI" libepoxy --cross-file "$CROSS" \
    --prefix "$PREFIX" --libdir lib --buildtype release \
    --default-library static \
    -Degl=yes -Dglx=no -Dx11=false -Dtests=false -Ddocs=false
  ninja -C "epoxy-$ABI" install

  echo "══════════ virglrenderer $ABI"
  rm -rf "virgl-$ABI"
  meson setup "virgl-$ABI" virglrenderer --cross-file "$CROSS" \
    --prefix "$PREFIX" --libdir lib --buildtype release \
    --default-library static \
    -Dplatforms=egl -Dvenus=false -Dvideo=false \
    -Dtests=false -Dfuzzer=false -Dvalgrind=false
  ninja -C "virgl-$ABI" vtest/virgl_test_server

  mkdir -p "$OUT/$ABI"
  "$TOOLCHAIN/bin/llvm-strip" -o "$OUT/$ABI/libvirglserver.so" \
    "virgl-$ABI/vtest/virgl_test_server"
done

# ── checks ────────────────────────────────────────────────────────────────
# Every LOAD segment 16 KB aligned (Android 15+ devices may use 16 KB pages),
# and nothing NEEDED beyond what every Android ships: an executable that asked
# for a library the phone lacks would die in the linker before main().
status=0
for f in "$OUT"/*/libvirglserver.so; do
  echo "══════════ $f"
  file "$f"
  readelf -lW "$f" | python3 -c '
import sys
aligns = [int(l.split()[-1], 16) for l in sys.stdin if l.split()[:1] == ["LOAD"]]
print("  LOAD align", " ".join(hex(a) for a in aligns))
sys.exit(0 if aligns and min(aligns) >= 16384 else 1)' \
    || { echo "  LOAD segment under 16 KB"; status=1; }
  needed=$(readelf -dW "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')
  echo "  NEEDED: $needed"
  for n in $needed; do
    case "$n" in
      libc.so|libm.so|libdl.so|liblog.so|libEGL.so|libGLESv2.so|libGLESv3.so|libandroid.so) ;;
      *) echo "  unexpected dependency: $n"; status=1 ;;
    esac
  done
done
exit $status
