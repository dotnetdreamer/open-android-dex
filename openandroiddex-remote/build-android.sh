#!/bin/bash
# build-android.sh — the phone half of remote access, for the two ABIs the
# launcher ships Linux on, copied straight into the launcher's jniLibs as
# libdexremote.so — which is where they are committed from, like proot.
#
#   ./build-android.sh
#
# Needs Rust with the two Android targets and an NDK:
#
#   rustup target add aarch64-linux-android x86_64-linux-android
#   sdkmanager "ndk;29.0.14206865"
#
# Why an Android target and not a static musl binary, which would need no NDK
# at all: iroh supports Android as a platform, and the Android build is the one
# that knows it is on Android. A Linux build running there would try to watch
# the network over netlink, which Android denies to apps, and retry that every
# thirty seconds for ever. The Android build does not try, and leaves network
# changes to the app — which is what LinuxService's `net` command is for.
#
# The binary is an executable named like a library for the same reason
# libproot.so is: nativeLibraryDir is the one place an app targeting SDK 35 may
# exec from, and the installer only extracts files called lib*.so into it.
set -euo pipefail
cd "$(dirname "$0")"

# API 26 is the launcher's minSdk. The NDK is the one pinned below unless
# ANDROID_NDK_HOME says otherwise; any r28+ is fine (r28 made 16 KB pages the
# default), and the flag below makes older ones produce the same layout.
NDK_VERSION=29.0.14206865
API=26
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}
[ -d "$NDK" ] || { echo "no NDK at $NDK — sdkmanager \"ndk;$NDK_VERSION\" or set ANDROID_NDK_HOME" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;   # the NDK's macOS build is universal
  Linux) HOST=linux-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
  *) echo "unsupported build host $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/$HOST/bin
DEST=../openandroiddex-launcher/app/src/main/jniLibs

for SPEC in aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64; do
  TARGET=${SPEC%%:*}
  ABI=${SPEC##*:}
  UPPER=$(echo "$TARGET" | tr 'a-z-' 'A-Z_')
  LOWER=$(echo "$TARGET" | tr '-' '_')
  echo "══════════ $TARGET -> $ABI"
  # ring compiles C and assembly through the cc crate, which reads CC_<target>;
  # rustc links through the same clang so the two agree on the sysroot.
  # No default features: `sftp` is the computer's way of copying files onto a
  # phone, and the phone never dials anyone.
  env "CC_$LOWER=$TOOLCHAIN/$TARGET$API-clang" \
      "AR_$LOWER=$TOOLCHAIN/llvm-ar" \
      "CARGO_TARGET_${UPPER}_LINKER=$TOOLCHAIN/$TARGET$API-clang" \
      "CARGO_TARGET_${UPPER}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
      cargo build --release --locked --no-default-features --target "$TARGET"
  mkdir -p "$DEST/$ABI"
  cp -f "target/$TARGET/release/dexremote" "$DEST/$ABI/libdexremote.so"
  echo "  -> $DEST/$ABI/libdexremote.so ($(wc -c < "$DEST/$ABI/libdexremote.so" | tr -d ' ') bytes)"
done

# Verify, do not assume — the same check openandroiddex-linux/proot/build.sh
# runs: an ELF whose LOAD segments are only 4 KB aligned cannot be mapped on a
# 16 KB-page device.
python3 - "$DEST" <<'PY'
import struct, sys, os
bad = []
for abi in ("arm64-v8a", "x86_64"):
    f = os.path.join(sys.argv[1], abi, "libdexremote.so")
    d = open(f, "rb").read()
    assert d[:4] == b"\x7fELF" and d[4] == 2, f + " is not a 64-bit ELF"
    phoff, = struct.unpack_from("<Q", d, 0x20)
    ent, num = struct.unpack_from("<HH", d, 0x36)
    aligns = [struct.unpack_from("<Q", d, phoff + i * ent + 0x30)[0]
              for i in range(num)
              if struct.unpack_from("<I", d, phoff + i * ent)[0] == 1]
    print("%-28s LOAD align %s" % (abi + "/libdexremote.so", " ".join(hex(a) for a in aligns)))
    if any(a < 0x4000 for a in aligns):
        bad.append(abi)
if bad:
    sys.exit("NOT 16 KB aligned: " + ", ".join(bad))
print("all LOAD segments >= 16 KB aligned")
PY
