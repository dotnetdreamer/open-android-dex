# Reproducible build of the GPU driver Linux gets on Exynos phones (Samsung
# Xclipse GPUs): Mesa's RADV Vulkan driver as ported to Xclipse by the
# radv-xclipse project, built for the Linux guest instead of for Android apps.
# See doc/linux-gpu.md for how the guest uses it.
#
# Ubuntu 24.04 on purpose, as for Turnip next door: the guest IS Ubuntu 24.04,
# so this is its glibc and its X11 libraries. Cross-compiled for arm64 with
# Ubuntu's own toolchain and arm64 libraries from Ubuntu's ports mirror.
FROM ubuntu:noble-20261024.1 AS base

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
      ca-certificates curl git xz-utils file binutils patch \
      crossbuild-essential-arm64 pkgconf ninja-build bison flex \
      python3 python3-venv python3-mako python3-yaml python3-packaging \
      glslang-tools \
      libx11-dev:arm64 libx11-xcb-dev:arm64 libxcb1-dev:arm64 \
      libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 \
      libxcb-shm0-dev:arm64 libxcb-sync-dev:arm64 libxcb-xfixes0-dev:arm64 \
      libxshmfence-dev:arm64 libxrandr-dev:arm64 libxext-dev:arm64 \
 && rm -rf /var/lib/apt/lists/*

# The port wants meson 1.4 or newer; noble ships 1.3. Pinned, in a venv.
RUN python3 -m venv --system-site-packages /opt/meson \
 && /opt/meson/bin/pip install --no-cache-dir meson==1.7.0

# radv-xclipse v1.8.0 (Mesa 27.0.0-devel with the Xclipse changes, MIT),
# pinned by its commit, which a moved tag or a regenerated archive cannot
# change. Its own wraps fetch libdrm 2.4.133 (with its two Xclipse patches),
# zlib and expat at configure time, each checked against the hash in its wrap.
ARG RADVX_REPO=https://github.com/JimVulkan/radv-xclipse.git
ARG RADVX_COMMIT=269238e8538c971cbe5195ed3e0013dea59f3bf5
RUN git init -q /build/radvx \
 && git -C /build/radvx fetch -q --depth 1 "$RADVX_REPO" "$RADVX_COMMIT" \
 && git -C /build/radvx checkout -q FETCH_HEAD \
 && test "$(git -C /build/radvx rev-parse HEAD)" = "$RADVX_COMMIT" \
 && rm -rf /build/radvx/.git

# The port builds for Android only and deleted the parts of Mesa an X11 build
# needs: src/x11, the X11 Vulkan headers and the headers Mesa's loader includes.
# upstream-files.sha256 lists every file put back, from upstream Mesa's main at
# the port's base: its vulkan_core.h is byte for byte the port's, and the
# src/x11 files are the same a week either side. Each is checked by hash.
ARG MESA_UPSTREAM_COMMIT=c823a83e8f373a5c36c2b9ed1d7dc98a4f3360ee
COPY upstream-files.sha256 /build/upstream-files.sha256
RUN cd /build/radvx \
 && while read -r _sum path; do \
      mkdir -p "$(dirname "$path")" \
      && curl -fsSL --retry 3 --connect-timeout 30 -o "$path" \
           "https://gitlab.freedesktop.org/mesa/mesa/-/raw/${MESA_UPSTREAM_COMMIT}/$path" || exit 1; \
    done < /build/upstream-files.sha256 \
 && sha256sum -c /build/upstream-files.sha256

FROM base AS build
COPY patches /build/patches
COPY build-radv.sh /build/build-radv.sh
RUN chmod +x /build/build-radv.sh && /build/build-radv.sh

# The artifact lands in /build/out/libvulkan_xclipse.so.
