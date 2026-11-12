#!/bin/bash
# build-turnip.sh — runs INSIDE the Docker image (see Dockerfile). Builds Mesa's
# Turnip Vulkan driver for Adreno GPUs against the KGSL kernel interface. The
# probe the runtime scripts ask before pointing anything at it is ../gpu-probe.
#
# The source is already unpacked in /build/mesa: the Dockerfile fetches and
# checks it in a layer of its own, with the version and checksum pins.
set -euo pipefail

BUILD=/build/work
OUT=/build/out
MESON=/opt/meson/bin/meson
mkdir -p "$BUILD" "$OUT"
cd "$BUILD"
ln -sfn /build/mesa mesa

cat >cross-arm64.txt <<'EOF'
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = ['/usr/lib/aarch64-linux-gnu/pkgconfig', '/usr/share/pkgconfig']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
EOF

# Turnip and nothing else. No GL of any kind: the guest's own Mesa (Ubuntu's)
# provides GL, through Zink, on top of this driver. X11 only: the guest's
# displays are Xvnc, and Mesa 26.3's Wayland support needs newer
# wayland-protocols and wayland-scanner than noble has.
"$MESON" setup build mesa --cross-file cross-arm64.txt \
  --prefix /usr/local --buildtype release -Db_ndebug=true \
  -Dplatforms=x11 \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl \
  -Dgallium-drivers= -Dopengl=false \
  -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dgles1=disabled -Dgles2=disabled \
  -Dglvnd=disabled -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
  -Dlmsensors=disabled -Dvideo-codecs= -Dvulkan-layers= -Dtools= \
  -Dbuild-tests=false -Dspirv-tools=disabled -Ddisplay-info=disabled \
  -Dandroid-libbacktrace=disabled -Dintel-rt=disabled -Dperfetto=false
ninja -C build src/freedreno/vulkan/libvulkan_freedreno.so

aarch64-linux-gnu-strip -o "$OUT/libvulkan_freedreno.so" \
  build/src/freedreno/vulkan/libvulkan_freedreno.so

# ── checks ────────────────────────────────────────────────────────────────
# Nothing newer than noble's glibc, and every library it needs is one the
# guest's own Mesa already pulls in.
for f in "$OUT"/*; do
  echo "══════════ $f"
  file "$f"
  aarch64-linux-gnu-readelf -dW "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/  NEEDED \1/p'
  aarch64-linux-gnu-objdump -T "$f" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1 | sed 's/^/  newest /'
done
ls -l "$OUT"
