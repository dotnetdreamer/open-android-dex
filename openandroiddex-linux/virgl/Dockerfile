# Reproducible build of the VirGL render server the launcher ships as a jniLib
# (libvirglserver.so): the GPU for Linux apps on a Mali, a PowerVR or an Adreno
# older than the 600 series. See doc/linux-gpu.md, "VirGL: any other GPU".
#
# Everything is pinned: the Debian base, the NDK, and both sources (virglrenderer
# and libepoxy, by sha256 in build-virgl.sh). Nothing from the base ends up in
# the artifact; every byte we ship comes out of the NDK and those two sources.
FROM debian:trixie-20261104-slim

# r30, the NDK whose linker already defaults to 16 KB pages. The checksum is
# Google's own, from dl.google.com/android/repository/repository2-3.xml.
ARG NDK_ZIP=android-ndk-r30-linux.zip
ARG NDK_SHA1=5107f898313790e449e87eee2183d9a20602dee9

# python3-yaml: virglrenderer generates its format tables from a YAML file at
# build time. binutils is for the readelf checks at the end of the build.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip patch file binutils \
      meson ninja-build pkgconf python3 python3-yaml \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSLo /tmp/ndk.zip "https://dl.google.com/android/repository/${NDK_ZIP}" \
 && echo "${NDK_SHA1}  /tmp/ndk.zip" | sha1sum -c - \
 && unzip -q /tmp/ndk.zip -d /opt \
 && rm /tmp/ndk.zip

COPY patches /build/patches
COPY build-virgl.sh /build/build-virgl.sh
RUN chmod +x /build/build-virgl.sh && /build/build-virgl.sh

# Artifacts land in /build/out/<abi>/libvirglserver.so.
