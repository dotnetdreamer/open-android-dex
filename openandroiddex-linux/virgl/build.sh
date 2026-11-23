#!/bin/bash
# build.sh — host side of the VirGL server build. Builds the pinned image (which
# does the whole compile in its final RUN layer) and copies the artifact straight
# into the launcher's jniLibs, which is where it is committed from.
#
# Run from Git Bash on Windows or any shell on Linux/macOS; needs only Docker.
# The first run downloads ~700 MB of NDK; every later run is cached unless
# build-virgl.sh, patches/ or the Dockerfile changed.
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash rewrites anything that looks like a POSIX path before it reaches
# docker.exe, which turns /build/out into C:/Program Files/Git/build/out.
export MSYS_NO_PATHCONV=1

IMAGE=openandroiddex-virgl:1.3.0-ndk-r30
DEST=../../openandroiddex-launcher/app/src/main/jniLibs

docker build -t "$IMAGE" .

CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

for ABI in arm64-v8a x86_64; do
  mkdir -p "$DEST/$ABI"
  docker cp "$CID:/build/out/$ABI/libvirglserver.so" "$DEST/$ABI/libvirglserver.so"
  echo "  -> $DEST/$ABI/libvirglserver.so"
done
