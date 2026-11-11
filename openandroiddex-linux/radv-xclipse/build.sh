#!/bin/bash
# build.sh — host side of the RADV Xclipse build. Builds the pinned image (which
# does the whole compile in its final RUN layer) and copies the driver into the
# launcher's assets, beside Turnip, which is where it is committed from.
#
# Run from Git Bash on Windows or any shell on Linux/macOS; needs only Docker.
# The first run downloads the port's source, the libdrm/zlib/expat it builds
# against and the arm64 libraries; every later run is cached unless a file in
# this folder changed.
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash rewrites anything that looks like a POSIX path before it reaches
# docker.exe, which turns /build/out into C:/Program Files/Git/build/out.
export MSYS_NO_PATHCONV=1

IMAGE=openandroiddex-radvx:v1.8.0
DEST=../../openandroiddex-launcher/app/src/main/assets/linux/gpu

docker build -t "$IMAGE" .

CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

mkdir -p "$DEST"
docker cp "$CID:/build/out/libvulkan_xclipse.so" "$DEST/libvulkan_xclipse.so"
echo "  -> $DEST/libvulkan_xclipse.so"
echo "done — bump LinuxGpu.DRIVER_VERSION if the driver changed"
