#!/usr/bin/env bash
# build.sh — the phone's half of the desktop on a postmarketOS phone, built in
# Docker for aarch64 and written to prebuilt/, which the desktop app embeds it
# from (src-tauri/src/pmos.rs). It is committed there, like libdexremote.so is
# in the launcher: building it needs Docker, and building the app should not.
#
#   ./build.sh
#
# The image (Dockerfile) is built once and kept as openandroiddex-pmos-build.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=openandroiddex-pmos-build
docker build -q -t "$IMAGE" . >/dev/null

# Git Bash hands Docker /c/... paths it cannot mount; pwd -W is C:/...
HOST_DIR=$(pwd -W 2>/dev/null || pwd)

MSYS_NO_PATHCONV=1 docker run --rm -v "$HOST_DIR:/src" -w /src "$IMAGE" sh -euc '
  gen=/tmp/gen
  mkdir -p "$gen" prebuilt
  for xml in \
    /usr/share/plasma-wayland-protocols/zkde-screencast-unstable-v1.xml \
    /usr/share/plasma-wayland-protocols/fake-input.xml \
    /usr/share/wayland-protocols/unstable/xdg-output/xdg-output-unstable-v1.xml; do
    base=$(basename "$xml" .xml)
    wayland-scanner client-header "$xml" "$gen/$base-client-protocol.h"
    wayland-scanner private-code "$xml" "$gen/$base-protocol.c"
  done
  export PKG_CONFIG_SYSROOT_DIR=/sysroot
  export PKG_CONFIG_LIBDIR=/sysroot/usr/lib/pkgconfig:/sysroot/usr/share/pkgconfig
  pkgs="wayland-client xkbcommon gio-unix-2.0 gstreamer-1.0 gstreamer-app-1.0 gstreamer-video-1.0"
  clang --target=aarch64-alpine-linux-musl --sysroot=/sysroot -fuse-ld=lld \
    -O2 -Wall -Wextra -Wno-unused-parameter -I"$gen" $(pkg-config --cflags $pkgs) \
    -o prebuilt/openandroiddex-pmos-aarch64 src/main.c "$gen"/*.c \
    -Wl,--as-needed $(pkg-config --libs $pkgs) -s
'
ls -l prebuilt/openandroiddex-pmos-aarch64
