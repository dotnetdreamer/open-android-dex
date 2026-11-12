# Reproducible build of the GPU driver Linux gets on Adreno phones: Mesa's own
# Turnip Vulkan driver, built for the KGSL kernel interface that Android's
# Qualcomm kernels expose. The probe that checks it works has its own recipe,
# ../gpu-probe. See doc/linux-gpu.md for how the guest uses them.
#
# Ubuntu 24.04 on purpose: the guest IS Ubuntu 24.04, so building against the
# same release means the same glibc and the same X11 libraries the driver will
# load against (it is built for X11 only; see build-turnip.sh). Cross-compiled
# for arm64 with Ubuntu's own toolchain and arm64 libraries from Ubuntu's
# ports mirror.
FROM ubuntu:noble-20261024.1

ENV DEBIAN_FRONTEND=noninteractive

RUN dpkg --add-architecture arm64 \
 && sed -i 's/^Types: deb$/Types: deb\nArchitectures: amd64/' /etc/apt/sources.list.d/ubuntu.sources \
 && printf '%s\n' \
      'Types: deb' \
      'URIs: http://ports.ubuntu.com/ubuntu-ports/' \
      'Suites: noble noble-updates noble-security' \
      'Components: main universe' \
      'Architectures: arm64' \
      'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' \
      > /etc/apt/sources.list.d/ubuntu-ports.sources \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl xz-utils file binutils \
      crossbuild-essential-arm64 pkgconf ninja-build bison flex \
      python3 python3-venv python3-mako python3-yaml python3-packaging \
      glslang-tools \
      libx11-dev:arm64 libx11-xcb-dev:arm64 libxcb1-dev:arm64 \
      libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 \
      libxcb-shm0-dev:arm64 libxcb-sync-dev:arm64 libxcb-xfixes0-dev:arm64 \
      libxshmfence-dev:arm64 libxrandr-dev:arm64 libxext-dev:arm64 \
      libexpat1-dev:arm64 zlib1g-dev:arm64 \
      libzstd-dev:arm64 libdrm-dev:arm64 \
 && rm -rf /var/lib/apt/lists/*

# Mesa 26.3 wants meson 1.5 or newer; noble ships 1.3. Pinned, in a venv.
RUN python3 -m venv --system-site-packages /opt/meson \
 && /opt/meson/bin/pip install --no-cache-dir meson==1.7.0

# Upstream Mesa, unpatched. 26.3.0 is the first release with the KGSL sync
# fixes (mesa!44838, "fixes some issues in the wild") and it lists the Adreno
# 830 (Galaxy S25) and 840 (S26) among its devices. The checksum was pinned
# after the tarball's GPG signature verified against a release maintainer's key
# in Mesa's docs/release-maintainers-keys.asc. A layer of its own, because the
# mirror is slow and the build script below is what changes.
ARG MESA_V=26.3.0
ARG MESA_SHA256=91d729aa68bb89e4ec857841300bbe922bcb3e52b1f97566ca83930d3b28343a
RUN mkdir -p /build/mesa \
 && curl -fsSL --retry 3 --connect-timeout 30 -o /tmp/mesa.tar.xz \
      "https://archive.mesa3d.org/mesa-${MESA_V}.tar.xz" \
 && echo "${MESA_SHA256}  /tmp/mesa.tar.xz" | sha256sum -c - \
 && tar -xJf /tmp/mesa.tar.xz -C /build/mesa --strip-components=1 \
 && rm /tmp/mesa.tar.xz

COPY build-turnip.sh /build/build-turnip.sh
RUN chmod +x /build/build-turnip.sh && /build/build-turnip.sh

# The artifact lands in /build/out/libvulkan_freedreno.so.
