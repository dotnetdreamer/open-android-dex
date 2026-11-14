# The build environment for openandroiddex-pmos: an x86_64 Alpine with clang
# and the Wayland scanner, and beside it an aarch64 Alpine sysroot holding the
# libraries the program links against on the phone. postmarketOS is Alpine, so
# a binary linked against Alpine's musl, GLib, GStreamer and libwayland runs
# there as the distribution's own programs do.
#
# Edge, because postmarketOS edge is what the phones this was written on run.
# Only long-stable symbols are used (GStreamer 1.0, GLib 2.x, libwayland 1.x),
# so the binary also runs on a stable postmarketOS, whose libraries are older.
FROM alpine:edge

RUN apk add --no-cache clang lld wayland-dev wayland-protocols plasma-wayland-protocols pkgconf

# gcc is there for its crt files and libgcc, which clang links with for this
# target; nothing in the sysroot is ever run.
RUN mkdir -p /sysroot/etc/apk/keys \
 && cp /usr/share/apk/keys/aarch64/*.pub /sysroot/etc/apk/keys/ \
 && apk add --root /sysroot --arch aarch64 --initdb --no-scripts --no-cache \
      -X https://dl-cdn.alpinelinux.org/alpine/edge/main \
      -X https://dl-cdn.alpinelinux.org/alpine/edge/community \
      musl-dev gcc linux-headers wayland-dev libxkbcommon-dev glib-dev gstreamer-dev gst-plugins-base-dev
