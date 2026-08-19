#!/bin/bash
# build.sh — host side of the proot rebuild. Builds the pinned image (which does
# the whole compile in its final RUN layer) and copies the artifacts straight
# into the launcher's jniLibs, which is where they are committed from.
#
# Run from Git Bash on Windows or any shell on Linux/macOS; needs only Docker.
# The first run downloads ~725 MB of NDK and takes a while; every later run is
# cached unless build-proot.sh or the Dockerfile changed.
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash rewrites anything that looks like a POSIX path before it reaches
# docker.exe, which turns /build/out into C:/Program Files/Git/build/out.
export MSYS_NO_PATHCONV=1

IMAGE=openandroiddex-proot:ndk-r23c-16k
DEST=../../openandroiddex-launcher/app/src/main/jniLibs

docker build -t "$IMAGE" .

CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

for ABI in arm64-v8a x86_64; do
  mkdir -p "$DEST/$ABI"
  for LIB in libproot.so libloader.so libloader32.so; do
    docker cp "$CID:/build/out/$ABI/$LIB" "$DEST/$ABI/$LIB"
    echo "  -> $DEST/$ABI/$LIB"
  done
done

echo "done — bump LINUX_PAYLOAD_VERSION in Linux.java if proot itself changed"
