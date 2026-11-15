#!/usr/bin/env bash
# Build scrcpy's client — the program that runs on the computer — from scrcpy's own
# source at the release the app bundles, with pointer-shapes.patch: the desktop
# window's cursor takes the shape the phone asks for, a double arrow over a window's
# resize edge (doc/window-resize.md, issue #5).
#
#   openandroiddex-scrcpy/build-client.sh <target>
#       <target>: linux-x86_64 | win64 | macos-aarch64 | macos-x86_64
#   ->  openandroiddex-scrcpy/build/client/<target>/scrcpy[.exe]
#
# Upstream's own release scripts do the work (release/build_<os>.sh: SDL, FFmpeg,
# dav1d and libusb from source, statically linked on Linux and macOS), so what ships
# is built the way scrcpy's releases are, and the tools they need are the ones
# upstream's .github/workflows/release.yml installs — see the desktop workflow's
# scrcpy-client job. win64 is cross-built on Linux, as upstream does it.
#
# Only the client executable is taken. The rest of the release — adb, the Windows
# DLLs, the icons — stays the downloaded one whose checksums the desktop workflow
# checks; the DLLs are built from the same scripts at the same versions, so the
# executable links against exactly those.
#
# The version and commit are build.mjs's, which builds the server: the client refuses
# a server of any other version, so the two can never be built from different
# releases.
#
# OADX_CLIENT_NO_USB=1 (macOS only) leaves libusb out, whose build needs autotools a
# Mac without Homebrew does not have. It costs scrcpy's --otg mode, which the app never
# uses; for local testing, never for a release.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-}"

VERSION="$(sed -n "s/^const SCRCPY_VERSION = '\(.*\)'.*/\1/p" "$HERE/build.mjs")"
COMMIT="$(sed -n "s/^const SCRCPY_COMMIT = '\(.*\)'.*/\1/p" "$HERE/build.mjs")"
REPO="https://github.com/Genymobile/scrcpy.git"
if [ -z "$VERSION" ] || [ -z "$COMMIT" ]; then
  echo "[scrcpy-client] cannot read SCRCPY_VERSION / SCRCPY_COMMIT from build.mjs" >&2
  exit 1
fi

case "$TARGET" in
  linux-x86_64) EXE=scrcpy ;;
  win64) EXE=scrcpy.exe ;;
  macos-aarch64 | macos-x86_64) EXE=scrcpy ;;
  *)
    echo "usage: $0 linux-x86_64 | win64 | macos-aarch64 | macos-x86_64" >&2
    exit 1
    ;;
esac

# Its own clone, not build.mjs's: that one is reset with `git clean` before every
# server build, and both trees are patched differently.
SRC="$HERE/build/client/scrcpy-$VERSION"
OUT="$HERE/build/client/$TARGET"

head_of() { git -C "$1" rev-parse HEAD 2>/dev/null || true; }

mkdir -p "$HERE/build/client"
if [ "$(head_of "$SRC")" != "$COMMIT" ]; then
  rm -rf "$SRC"
  echo "[1/3] git clone scrcpy v$VERSION"
  git clone --quiet --depth 1 --branch "v$VERSION" "$REPO" "$SRC"
  if [ "$(head_of "$SRC")" != "$COMMIT" ]; then
    echo "[scrcpy-client] v$VERSION is $(head_of "$SRC"), not $COMMIT — refusing to build it" >&2
    exit 1
  fi
else
  echo "[1/3] scrcpy v$VERSION already in $SRC"
fi

echo "[2/3] apply pointer-shapes.patch"
# Back to the release's own files; app/deps/work and release/work are ignored by
# scrcpy's .gitignore, so the dependencies already built survive this.
git -C "$SRC" checkout --quiet -- .
git -C "$SRC" clean --quiet -fd
git -C "$SRC" apply "$HERE/pointer-shapes.patch"

echo "[3/3] build the client for $TARGET"
cd "$SRC"
case "$TARGET" in
  linux-x86_64)
    release/build_linux.sh x86_64
    BUILT="release/work/build-linux-x86_64/dist/$EXE"
    ;;
  win64)
    release/build_windows.sh 64
    BUILT="release/work/build-win64/dist/$EXE"
    ;;
  macos-aarch64 | macos-x86_64)
    ARCH="${TARGET#macos-}"
    if [ "${OADX_CLIENT_NO_USB:-}" = "1" ]; then
      # release/build_macos.sh without its libusb, and with -Dusb=false to match.
      app/deps/sdl.sh macos native static
      app/deps/dav1d.sh macos native static
      app/deps/ffmpeg.sh macos native static
      DEPS="$PWD/app/deps/work/install/macos-native-static"
      DIR="release/work/build-macos-$ARCH-nousb"
      rm -rf "$DIR"
      (
        unset PKG_CONFIG_PATH
        export PKG_CONFIG_LIBDIR="$DEPS/lib/pkgconfig"
        meson setup "$DIR" -Dc_args="-I$DEPS/include" -Dc_link_args="-L$DEPS/lib" \
          --buildtype=release --strip -Db_lto=true -Dcompile_server=false \
          -Dportable=true -Dstatic=true -Dusb=false
        ninja -C "$DIR"
      )
      BUILT="$DIR/app/$EXE"
    else
      release/build_macos.sh "$ARCH"
      BUILT="release/work/build-macos-$ARCH/dist/$EXE"
    fi
    ;;
esac

# The patch is the whole point of this build: a binary without it must not leave here
# looking like a success.
if ! grep -aqF "Pointer shapes: read from stdin" "$BUILT"; then
  echo "[scrcpy-client] $BUILT does not carry pointer-shapes.patch" >&2
  exit 1
fi
mkdir -p "$OUT"
cp -f "$BUILT" "$OUT/$EXE"
chmod +x "$OUT/$EXE"
echo "done -> $OUT/$EXE"
