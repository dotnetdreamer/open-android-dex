# Reproducible build of the checks a Linux session runs before it points GL at
# the GPU: oadx-vkprobe for both Vulkan drivers the guest can get, Turnip
# (../turnip) on Adreno phones and RADV (../radv-xclipse) on Exynos phones
# with Xclipse GPUs, and oadx-glprobe for VirGL (../virgl), the way through
# Android's own driver on every other GPU. See doc/linux-gpu.md.
#
# Ubuntu 24.04, the guest's own release, so the probes ask for no newer glibc
# than the guest has. They open their library at run time (the Vulkan loader,
# Mesa's libEGL), so only headers are needed here, and no arm64 library.
FROM ubuntu:noble-20261024.1

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      gcc-aarch64-linux-gnu libc6-dev-arm64-cross libvulkan-dev libegl-dev libx11-dev libgl-dev file \
 && rm -rf /var/lib/apt/lists/*

# -idirafter: the Vulkan, EGL, GLX and Xlib headers from /usr/include, searched
# after the arm64 C library's own, so nothing of the host's libc can be picked up.
COPY oadx-vkprobe.c oadx-glprobe.c /build/
RUN mkdir -p /build/out \
 && for p in oadx-vkprobe oadx-glprobe; do \
      aarch64-linux-gnu-gcc -O2 -Wall -Werror -idirafter /usr/include \
        -o /build/out/$p /build/$p.c -ldl \
      && aarch64-linux-gnu-strip /build/out/$p \
      && file /build/out/$p \
      && aarch64-linux-gnu-objdump -T /build/out/$p | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1 \
      || exit 1; \
    done

# The artifacts land in /build/out/oadx-vkprobe and /build/out/oadx-glprobe.
