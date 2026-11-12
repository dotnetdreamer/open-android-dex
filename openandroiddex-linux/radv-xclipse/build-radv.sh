#!/bin/bash
# build-radv.sh — runs INSIDE the Docker image (see Dockerfile). Builds RADV
# with the radv-xclipse port's Xclipse support, for the Linux guest (glibc,
# X11) rather than for Android apps, which is all the port itself builds.
#
# The source is already in /build/radvx: the Dockerfile fetches it by commit
# in a layer of its own.
set -euo pipefail
shopt -s nullglob

BUILD=/build/work
OUT=/build/out
MESON=/opt/meson/bin/meson
mkdir -p "$BUILD" "$OUT"
cd "$BUILD"

# Ours, in name order, onto the fresh tree: what a Linux build of the port
# needs and the port does not do itself. See each patch.
if [ ! -f /build/radvx/.oadx-patched ]; then
  for P in /build/patches/*.patch; do
    echo "══════════ patch $(basename "$P")"
    patch -d /build/radvx -p1 --forward < "$P"
  done
  touch /build/radvx/.oadx-patched
fi

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

# RADV and nothing else, X11 only, as Turnip: the guest's own Mesa (Ubuntu's)
# provides GL through Zink on top. The dependency choices are the port's own
# (its build.sh): libdrm, zlib and expat from its wraps, linked in statically,
# so the libdrm carries the port's Xclipse patches; armv8.2-a, which every
# Exynos with an Xclipse GPU has.
#
# --prefix /usr although nothing is installed: it is where the static libdrm
# looks for amdgpu.ids, which the guest has from Ubuntu's libdrm-common (it
# names AMD's own cards; the Xclipse ids are not in it). Under /usr/local the
# lookup failed and said so on the stderr of every GL app.
"$MESON" setup build /build/radvx --cross-file cross-arm64.txt \
  --prefix /usr --buildtype release -Db_ndebug=true \
  -Dplatforms=x11 \
  -Dvulkan-drivers=amd -Dgallium-drivers= -Dopengl=false \
  -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dgles1=disabled -Dgles2=disabled \
  -Dglvnd=disabled -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
  -Dlmsensors=disabled -Dzstd=disabled -Dvideo-codecs= -Dvulkan-layers= -Dtools= \
  -Dbuild-tests=false -Dspirv-tools=disabled -Ddisplay-info=disabled \
  -Dandroid-libbacktrace=disabled -Dintel-rt=disabled -Dperfetto=false \
  -Dallow-fallback-for=libdrm --force-fallback-for=expat,libdrm,zlib \
  -Dlibdrm:default_library=static -Dexpat:default_library=static -Dzlib:default_library=static \
  -Dc_args=-march=armv8.2-a -Dcpp_args=-march=armv8.2-a
ninja -C build src/amd/vulkan/libvulkan_radeon.so

aarch64-linux-gnu-strip -o "$OUT/libvulkan_xclipse.so" build/src/amd/vulkan/libvulkan_radeon.so

# ── checks ────────────────────────────────────────────────────────────────
# Nothing newer than noble's glibc, no Android library, and every library it
# needs is one the guest already has.
for f in "$OUT"/*; do
  echo "══════════ $f"
  file "$f"
  aarch64-linux-gnu-readelf -dW "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/  NEEDED \1/p'
  aarch64-linux-gnu-objdump -T "$f" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1 | sed 's/^/  newest /'
  if aarch64-linux-gnu-objdump -T "$f" | grep -E '__android_log|__system_property'; then
    echo "  an Android symbol is still referenced" >&2
    exit 1
  fi
done
ls -l "$OUT"
