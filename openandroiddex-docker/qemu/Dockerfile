# Reproducible QEMU cross-build for the launcher's jniLibs — the engine behind
# the Docker window.
#
# Why a whole VM: this phone's kernel has `# CONFIG_PID_NS is not set` and
# `# CONFIG_USER_NS is not set`, so runc cannot create a container on it even
# with root, and proot (which the Linux window uses) provides no namespaces at
# all. AVF is present but reports "Only protected VMs are supported /
# /dev/kvm does not exist", so the platform's own VM path is closed too. A
# userspace TCG virtual machine is the only way to reach a real dockerd, and
# QEMU is the only mature one.
#
# Everything is pinned, same discipline as ../../openandroiddex-linux/proot:
# the base, the NDK, and every dependency tarball. Nothing from the Debian base
# ends up in the artifacts — every byte we ship comes out of the NDK.
#
# API 28, not the launcher's minSdk 26: glib needs iconv_open, which bionic
# only grew in API 28. Docker.abiSupported() gates the tile to match.
FROM debian:bookworm-20250929-slim AS toolchain

ARG NDK_ZIP=android-ndk-r27c-linux.zip
ARG NDK_SHA1=090e8083a715fdb1a3e402d0763c388abb03fb4e

# gcc/g++ are the BUILD-machine compiler, not the target one: QEMU's meson runs
# code generators (decodetree, the Hexagon semantics parser) that have to
# execute here. Every byte we ship still comes out of the NDK.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip make tar gzip xz-utils bzip2 file patch \
      python3 python3-pip python3-venv pkg-config bison flex git \
      ninja-build perl gcc g++ libc6-dev \
 && rm -rf /var/lib/apt/lists/*

# meson from pip: bookworm ships 1.0, glib 2.82 needs >= 1.4.
RUN pip3 install --break-system-packages 'meson==1.6.1'

RUN curl -fsSLo /tmp/ndk.zip "https://dl.google.com/android/repository/${NDK_ZIP}" \
 && echo "${NDK_SHA1}  /tmp/ndk.zip" | sha1sum -c - \
 && unzip -q /tmp/ndk.zip -d /opt \
 && rm /tmp/ndk.zip

FROM toolchain AS build

COPY patches /build/patches
COPY check-align.py /build/check-align.py
COPY build-qemu.sh /build/build-qemu.sh
RUN chmod +x /build/build-qemu.sh && /build/build-qemu.sh

# Artifacts land in /build/out/<abi>/libqemu.so.
