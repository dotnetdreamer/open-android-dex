#!/bin/bash
# build-proot.sh — runs INSIDE the Docker image (see Dockerfile). Builds proot
# + its two loaders for the ABIs the launcher ships, 16 KB page aligned.
#
# This is a re-implementation of green-green-avk/build-proot-android's
# `make-proot-for-apk.sh` (commit 01f83b8), trimmed to our two ABIs and with one
# addition: $ALIGN on every link. Android 15+ devices may use 16 KB memory
# pages; an ELF whose LOAD segments are only 4 KB aligned cannot be mapped
# there, and a debuggable build makes the platform pop an "Android App
# Compatibility" dialog about it on every launch. The published prebuilts are
# 4 KB (NDK's pre-r28 default), so the only fix is a relink.
set -euo pipefail
shopt -s nullglob

PROOT_V=${PROOT_V:-0.15_release}
TALLOC_V=${TALLOC_V:-2.1.14}
NDK=${NDK:-/opt/android-ndk-r23c}
# API 21 matches the prebuilts. The launcher's minSdk is 26, so this is only
# ever more permissive than what we actually run on.
API=${API:-21}

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
ALIGN='-Wl,-z,max-page-size=16384'

BUILD=/build/work
OUT=/build/out
mkdir -p "$BUILD" "$OUT"

# <clang arch> <jniLibs ABI>. The 32-bit loader is built from the same
# compiler with -m32 (clang retargets aarch64->arm, x86_64->i386), which is why
# an ELF32 ends up inside arm64-v8a/ — proot needs it to exec 32-bit guests.
ARCHS='aarch64:arm64-v8a x86_64:x86_64'

set_arch() {
  MARCH=$1
  case "$MARCH" in arm*) MARCH_T=arm ;; *) MARCH_T=$MARCH ;; esac
  CC_BIN="$TOOLCHAIN/bin/$MARCH-linux-android$API-clang"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export AS="$TOOLCHAIN/bin/$MARCH_T-linux-android$API-clang"
  export CC="$CC_BIN"
  export CXX="$CC_BIN++"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
  export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
  export LD="$CC_BIN"
  STATIC_ROOT="$BUILD/static-$MARCH/root"
  INSTALL_ROOT="$BUILD/root-$MARCH/root"
}

# ── sources ───────────────────────────────────────────────────────────────
cd "$BUILD"
[ -d "talloc-$TALLOC_V" ] || \
  curl -fsSL "https://download.samba.org/pub/talloc/talloc-$TALLOC_V.tar.gz" | tar -xz
[ -d "proot-$PROOT_V" ] || \
  curl -fsSL "https://github.com/green-green-avk/proot/archive/v$PROOT_V.tar.gz" | tar -xz

# talloc's configure probes pkg-config for the *host* and wedges on the answers
# file if it finds a real one. Upstream's workaround: a pkg-config that always
# fails.
mkdir -p "$BUILD/mock-bin"
printf '#!/bin/sh\n/bin/false\n' > "$BUILD/mock-bin/pkg-config"
chmod +x "$BUILD/mock-bin/pkg-config"

# ── talloc: static, per ABI ───────────────────────────────────────────────
for SPEC in $ARCHS; do
  set_arch "${SPEC%%:*}"
  echo "══════════ talloc $MARCH"
  cd "$BUILD/talloc-$TALLOC_V"
  make distclean >/dev/null 2>&1 || true
  rm -rf bin

  # talloc's cross-compile detection cannot run target binaries, so every probe
  # is answered from this file (verbatim from upstream's make-talloc-static.sh).
  cat <<EOF >cross-answers.txt
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking for HAVE_SECURE_MKSTEMP: OK
Checking getconf large file support flags work: OK
Checking for HAVE_IFACE_IFCONF: FAIL
EOF

  PATH="$BUILD/mock-bin:$PATH" ./configure build \
    --prefix="$INSTALL_ROOT" --disable-rpath --disable-python \
    --cross-compile --cross-answers=cross-answers.txt

  mkdir -p "$STATIC_ROOT/include" "$STATIC_ROOT/lib"
  # proot links talloc statically, so only the objects matter — no .so, no
  # runtime lookup, nothing extra to ship in jniLibs.
  "$AR" rcs "$STATIC_ROOT/lib/libtalloc.a" bin/default/talloc*.o
  cp -f talloc.h "$STATIC_ROOT/include"
done

# ── proot + loaders, per ABI ──────────────────────────────────────────────
for SPEC in $ARCHS; do
  set_arch "${SPEC%%:*}"
  ABI="${SPEC##*:}"
  echo "══════════ proot $MARCH -> $ABI"
  cd "$BUILD/proot-$PROOT_V/src"
  make distclean >/dev/null 2>&1 || true

  # The loader paths the launcher exports as PROOT_LOADER / PROOT_LOADER_32
  # (Linux.java). Everything in jniLibs must be named lib*.so or the installer
  # will not extract it.
  export PROOT_UNBUNDLE_LOADER='.'
  export PROOT_UNBUNDLE_LOADER_NAME='libloader.so'
  export PROOT_UNBUNDLE_LOADER_NAME_32='libloader32.so'
  export CFLAGS="-I$STATIC_ROOT/include -Werror=implicit-function-declaration"
  export LDFLAGS="-L$STATIC_ROOT/lib $ALIGN"

  # $ALIGN goes on three separate links and they are reached three different
  # ways: proot itself via LDFLAGS, and each loader via its own LOADER_LDFLAGS*
  # (the makefile builds those from scratch, ignoring LDFLAGS). Appending it to
  # CC as well is the belt to that pair of braces — it costs an "unused
  # argument" warning per compile and guarantees no link escapes.
  env "LOADER_LDFLAGS=$ALIGN" "LOADER_LDFLAGS-m32=$ALIGN" \
      CC="$CC_BIN $ALIGN" LD="$CC_BIN $ALIGN" \
      make V=1 "PREFIX=$INSTALL_ROOT" install

  mkdir -p "$OUT/$ABI"
  ( cd "$INSTALL_ROOT/bin" && "$STRIP" proot libloader.so libloader32.so )
  cp -f "$INSTALL_ROOT/bin/proot"          "$OUT/$ABI/libproot.so"
  cp -f "$INSTALL_ROOT/bin/libloader.so"   "$OUT/$ABI/libloader.so"
  cp -f "$INSTALL_ROOT/bin/libloader32.so" "$OUT/$ABI/libloader32.so"
done

# ── the whole reason this file exists: verify, do not assume ──────────────
python3 - "$OUT" <<'PY'
import struct, sys, os, glob
bad = []
for f in sorted(glob.glob(os.path.join(sys.argv[1], '*', '*.so'))):
    d = open(f, 'rb').read()
    if d[4] == 2:
        phoff, = struct.unpack_from('<Q', d, 0x20)
        ent, num = struct.unpack_from('<HH', d, 0x36)
        segs = [struct.unpack_from('<QQQQQQ', d, phoff + i*ent + 8)
                for i in range(num)
                if struct.unpack_from('<I', d, phoff + i*ent)[0] == 1]
        aligns = [s[5] for s in segs]
    else:
        phoff, = struct.unpack_from('<I', d, 0x1c)
        ent, num = struct.unpack_from('<HH', d, 0x2a)
        segs = [struct.unpack_from('<8I', d, phoff + i*ent) for i in range(num)]
        aligns = [s[7] for s in segs if s[0] == 1]
    rel = os.path.relpath(f, sys.argv[1])
    print('%-32s LOAD align %s' % (rel, ' '.join(hex(a) for a in aligns)))
    if any(a < 0x4000 for a in aligns):
        bad.append(rel)
if bad:
    sys.exit('NOT 16 KB aligned: ' + ', '.join(bad))
print('all LOAD segments >= 16 KB aligned')
PY
