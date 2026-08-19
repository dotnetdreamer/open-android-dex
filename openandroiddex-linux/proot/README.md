# proot — 16 KB-aligned rebuild

The launcher ships proot and its two loaders as jniLibs:

    openandroiddex-launcher/app/src/main/jniLibs/<abi>/libproot.so
                                                      libloader.so
                                                      libloader32.so

They are **committed binaries**, not a build-time download. `Linux.java` points
`LINUX_PROOT` / `PROOT_LOADER` / `PROOT_LOADER_32` at them by absolute path in
`nativeLibraryDir` — the one directory an app targeting SDK 35 may exec from
(W^X exempt), which is also why they must be named `lib*.so` and why the
launcher sets `useLegacyPackaging` so the installer extracts them as real files.

This directory rebuilds them. Run it only when proot itself needs to change.

    ./build.sh          # Git Bash on Windows, or any shell with Docker

## Why we don't use the upstream prebuilts

They come from [green-green-avk/build-proot-android][1] (`packages/proot-android-*.tar.gz`),
built with NDK r23c, whose linker default is a **4 KB** max page size. Android
15 introduced devices with 16 KB memory pages, and an ELF whose LOAD segments
are 4 KB aligned cannot be mapped on one. On a debuggable build the platform
also pops an "Android App Compatibility" dialog naming all three files at every
launch.

Patching the shipped binaries is not an option: their segments' file offsets and
virtual addresses are congruent mod 0x1000 but not mod 0x4000, so raising
`p_align` alone would produce an ELF the kernel maps wrong. It has to be a
relink.

## What this build changes — and what it deliberately doesn't

`build-proot.sh` reimplements upstream's `make-proot-for-apk.sh` (commit
`01f83b8`) with the same pins — NDK **r23c** (23.2.8568313), API 21, proot
`v0.15_release`, talloc `2.1.14` — and adds exactly one thing:

    -Wl,-z,max-page-size=16384

on every link. Holding the toolchain identical is the point: it makes "did the
rebuild change proot's behaviour?" answerable with *no, only the segment
alignment moved*. The build fails if any LOAD segment comes out under 16 KB.

Three separate links need the flag and they are reached three different ways —
proot itself through `LDFLAGS`, and each loader through its own
`LOADER_LDFLAGS` / `LOADER_LDFLAGS-m32`, which proot's makefile builds from
scratch and never seeds from `LDFLAGS`.

## Notes

- **`libloader32.so` under `arm64-v8a/` is an ELF32 on purpose.** proot needs a
  32-bit loader to exec 32-bit guest binaries, and it has to sit beside the
  64-bit one in the same native-lib dir. Android's compatibility checker cannot
  parse it there and reports "Unknown error"; that is the checker, not a defect.
  Our Ubuntu 24.04 arm64 rootfs has no 32-bit binaries, so the file could be
  dropped if that ever becomes worth doing.
- **proot itself is page-size agnostic** — it reads `sysconf(_SC_PAGE_SIZE)`
  rather than assuming 4096 (`src/execve/enter.c`, `src/tracee/mem.c`), so the
  alignment was the only blocker on the proot side.
- Only `arm64-v8a` (phones) and `x86_64` (the emulator) are built; those are the
  two ABIs the launcher stages.

[1]: https://github.com/green-green-avk/build-proot-android
