#!/bin/sh
# Build liboadx-nodump.so for the guest (glibc Linux, not Android) on both ABIs
# the container runs, into the launcher's assets. The results are COMMITTED,
# like proot: an APK build needs no toolchain. Rebuild when nodump.c changes.
#
# The NDK's clang and lld can target plain linux-gnu; -nostdlib keeps glibc out
# of the link entirely (dlsym is resolved at load time from the process), so no
# sysroot is needed. 16 KB-aligned like everything else the launcher ships.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)
out="$here/../../openandroiddex-launcher/app/src/main/assets/linux/sftp"
for pair in arm64-v8a:aarch64-linux-gnu x86_64:x86_64-linux-gnu; do
  abi=${pair%%:*}
  target=${pair#*:}
  mkdir -p "$out/$abi"
  "$BIN/clang" --target="$target" -O2 -fPIC -shared -nostdlib -fuse-ld=lld \
    -Wl,-z,max-page-size=16384 -Wl,--hash-style=both -Wl,-soname,liboadx-nodump.so \
    -o "$out/$abi/liboadx-nodump.so" "$here/nodump.c"
  "$BIN/llvm-strip" "$out/$abi/liboadx-nodump.so"
  echo "$abi -> $out/$abi/liboadx-nodump.so ($(wc -c < "$out/$abi/liboadx-nodump.so" | tr -d ' ') bytes)"
done
