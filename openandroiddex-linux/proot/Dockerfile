# Reproducible 16 KB-aligned proot build for the launcher's jniLibs.
#
# Everything is pinned: Debian base, the exact NDK green-green-avk used for the
# published prebuilts (r23c = 23.2.8568313), the proot source tag and the talloc
# release. The ONLY intended difference from those prebuilts is the linker's
# max-page-size — see build-proot.sh. Keeping the toolchain identical is the
# point: it makes "did the rebuild change proot's behaviour?" answerable with
# "no, only the segment alignment moved".
#
# The base is bullseye, not something current, for one reason: talloc 2.1.14
# bundles a Python 2 waf (`except Utils.WafError, e:`), and bullseye is the last
# Debian carrying python2. Moving to a talloc new enough to build under python3
# would relink proot against a different allocator — exactly the kind of change
# this build exists to avoid. Nothing from the base ends up in the artifacts;
# every byte we ship comes out of the NDK.
FROM debian:bullseye-20251229-slim

ARG NDK_ZIP=android-ndk-r23c-linux.zip
ARG NDK_SHA1=e5053c126a47e84726d9f7173a04686a71f9a67a

# python-is-python2: talloc's ./configure execs bare `python` to start waf.
# python3 is for the alignment check at the end of build-proot.sh.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip make tar gzip xz-utils file patch \
      python2 python-is-python2 python3 binutils gawk \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSLo /tmp/ndk.zip "https://dl.google.com/android/repository/${NDK_ZIP}" \
 && echo "${NDK_SHA1}  /tmp/ndk.zip" | sha1sum -c - \
 && unzip -q /tmp/ndk.zip -d /opt \
 && rm /tmp/ndk.zip

COPY build-proot.sh /build/build-proot.sh
RUN chmod +x /build/build-proot.sh && /build/build-proot.sh

# Artifacts land in /build/out/<abi>/{libproot.so,libloader.so,libloader32.so}.
