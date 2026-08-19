#!/bin/bash
# build.sh — host side of the QEMU rebuild. Builds the pinned image (which does
# the whole cross-compile in its final RUN layer) and copies the artifacts
# straight into the launcher's jniLibs, which is where they are committed from.
#
# Run from Git Bash on Windows or any shell on Linux/macOS; needs only Docker.
# The first run downloads ~660 MB of NDK plus the source tarballs and takes a
# while; every later run is cached unless build-qemu.sh, a patch or the
# Dockerfile changed.
#
# Same shape and the same reasons as ../../openandroiddex-linux/proot/build.sh.
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash rewrites anything that looks like a POSIX path before it reaches
# docker.exe, which turns /build/out into C:/Program Files/Git/build/out.
export MSYS_NO_PATHCONV=1

IMAGE=openandroiddex-qemu:ndk-r27c-9.2.3
DEST=../../openandroiddex-launcher/app/src/main/jniLibs

docker build -t "$IMAGE" .

CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

for ABI in arm64-v8a x86_64; do
  mkdir -p "$DEST/$ABI"
  docker cp "$CID:/build/out/$ABI/libqemu.so" "$DEST/$ABI/libqemu.so"
  echo "  -> $DEST/$ABI/libqemu.so"
done

echo "done — bump Docker.PAYLOAD_VERSION in Docker.java if the VM must be rebuilt"
