#!/bin/bash
# build.sh — host side of the probe build: builds the image (the compile is its
# last RUN layer) and copies the probe into the launcher's assets beside the
# drivers, which is where it is committed from.
#
# Run from Git Bash on Windows or any shell on Linux/macOS; needs only Docker.
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash rewrites anything that looks like a POSIX path before it reaches
# docker.exe, which turns /build/out into C:/Program Files/Git/build/out.
export MSYS_NO_PATHCONV=1

IMAGE=openandroiddex-gpu-probe:noble
DEST=../../openandroiddex-launcher/app/src/main/assets/linux/gpu

docker build -t "$IMAGE" .

CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

mkdir -p "$DEST"
docker cp "$CID:/build/out/oadx-vkprobe" "$DEST/oadx-vkprobe"
echo "  -> $DEST/oadx-vkprobe"
echo "done — bump LinuxGpu.DRIVER_VERSION if the probe changed"
