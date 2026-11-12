# Reproducible build of oadx-vkprobe, the check a Linux session runs before it
# points GL at the GPU, for both drivers the guest can get: Turnip (../turnip)
# on Adreno phones and RADV (../radv-xclipse) on Exynos phones with Xclipse
# GPUs. See doc/linux-gpu.md.
#
# Ubuntu 24.04, the guest's own release, so the probe asks for no newer glibc
# than the guest has. It opens the Vulkan loader at run time, so only the
# Vulkan headers are needed here, and no arm64 library.
FROM ubuntu:noble-20261024.1

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      gcc-aarch64-linux-gnu libc6-dev-arm64-cross libvulkan-dev file \
 && rm -rf /var/lib/apt/lists/*

# -idirafter: the Vulkan headers from /usr/include, searched after the arm64
# C library's own, so nothing of the host's libc can be picked up.
COPY oadx-vkprobe.c /build/oadx-vkprobe.c
RUN mkdir -p /build/out \
 && aarch64-linux-gnu-gcc -O2 -Wall -Werror -idirafter /usr/include \
      -o /build/out/oadx-vkprobe /build/oadx-vkprobe.c -ldl \
 && aarch64-linux-gnu-strip /build/out/oadx-vkprobe \
 && file /build/out/oadx-vkprobe \
 && aarch64-linux-gnu-objdump -T /build/out/oadx-vkprobe | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1

# The artifact lands in /build/out/oadx-vkprobe.
