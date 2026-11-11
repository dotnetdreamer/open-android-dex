#!/bin/bash
# build.sh — host side of the Turnip build. Builds the pinned image (which does
# the whole compile in its final RUN layer) and copies the driver straight into
# the launcher's assets, which is where they are committed from.
#
# Run from Git Bash on Windows or any shell on Linux/macOS; needs only Docker.
# The first run downloads ~90 MB of Mesa source plus the arm64 libraries; every
# later run is cached unless build-turnip.sh or the Dockerfile changed.
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash rewrites anything that looks like a POSIX path before it reaches
# docker.exe, which turns /build/out into C:/Program Files/Git/build/out.
export MSYS_NO_PATHCONV=1

IMAGE=openandroiddex-turnip:mesa-26.3.0
DEST=../../openandroiddex-launcher/app/src/main/assets/linux/gpu

docker build -t "$IMAGE" .

CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

mkdir -p "$DEST"
for f in libvulkan_freedreno.so; do
  docker cp "$CID:/build/out/$f" "$DEST/$f"
  echo "  -> $DEST/$f"
done
echo "done — bump LinuxGpu.DRIVER_VERSION if the driver changed"
