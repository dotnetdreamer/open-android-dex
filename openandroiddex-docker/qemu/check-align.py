#!/usr/bin/env python3
"""Fail the build unless every PT_LOAD segment is at least 16 KB aligned.

Android 15+ devices may use 16 KB memory pages, and an ELF whose LOAD segments
are only 4 KB aligned cannot be mapped on one at all. The linker flag that
fixes it (-Wl,-z,max-page-size=16384) is easy to lose in a refactor and the
failure only shows up on a subset of phones, so it is checked here instead.

Same guarantee the proot build ends with; see ../../openandroiddex-linux/proot.
"""
import struct
import sys

PT_LOAD = 1


def main(path):
    with open(path, "rb") as f:
        d = f.read()
    if d[:4] != b"\x7fELF":
        sys.exit("%s: not an ELF" % path)
    if d[4] != 2:
        sys.exit("%s: not ELF64" % path)

    (phoff,) = struct.unpack_from("<Q", d, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", d, 0x36)

    bad = []
    for i in range(phnum):
        o = phoff + i * phentsize
        (p_type,) = struct.unpack_from("<I", d, o)
        if p_type != PT_LOAD:
            continue
        (p_align,) = struct.unpack_from("<Q", d, o + 48)
        if p_align < 16384:
            bad.append(p_align)

    if bad:
        sys.exit("%s: PT_LOAD alignment too small: %r" % (path, bad))
    print("  16 KB aligned OK")


if __name__ == "__main__":
    main(sys.argv[1])
