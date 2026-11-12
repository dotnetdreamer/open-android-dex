# Third-party software bundled with Open Android DeX

Open Android DeX itself is copyright © 2026 Idrees Khan and is distributed under
the **GNU General Public License, version 3 or later**. The works listed below
are independent projects distributed alongside it — in `resources/bin` on the
host, and inside the launcher APK on the phone. Each keeps its own licence and
its own copyright holders; none of them is a work of this project.

---

## scrcpy 5.0.1

Copyright © Genymobile, Romain Vimont and contributors.
Licensed under the **Apache License, Version 2.0**.
Source: https://github.com/Genymobile/scrcpy

Files, Windows: `scrcpy.exe`, `scrcpy-server`, `LICENSE.txt`
Files, macOS: `scrcpy`, `scrcpy-server`, `LICENSE`
Files, Linux: `scrcpy`, `scrcpy-server`, `LICENSE`

`scrcpy-server` is not the release's own file: it is built from scrcpy's
source at that release with one change by this project,
`openandroiddex-scrcpy/display-scale.patch` (a resizable display follows its
window in the computer screen's pixels). The files it changes say so.

The scrcpy release archive also carries the libraries listed under
"Libraries bundled by scrcpy" below, which are **not** Apache-2.0. On macOS and
Linux the build is statically linked, so those libraries are inside the `scrcpy`
binary rather than beside it — their licences still apply, and apply to the
binary that contains them.

## Android Debug Bridge (adb)

Part of the Android Open Source Project platform tools.
Copyright © The Android Open Source Project.
Licensed under the **Apache License, Version 2.0**.
Source: https://android.googlesource.com/platform/packages/modules/adb/

Files, Windows: `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`
Files, macOS: `adb`
Files, Linux: `adb`

Shipped as part of the scrcpy release above, which bundles adb on all three
platforms.

### Apache License 2.0

scrcpy and adb are distributed under the Apache License, Version 2.0. You may
obtain a copy of the licence at:

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied.

---

## Libraries bundled by scrcpy

These ship inside the scrcpy release archive. They are listed separately because
they are **not** covered by scrcpy's Apache-2.0 licence.

Only the Windows build ships them as separate files. The macOS and Linux builds
are statically linked, so on those two the code below is *inside* the `scrcpy`
binary — which changes where it is, not whether these licences apply.

### FFmpeg 9.0.2

Copyright © the FFmpeg developers.
Licensed under the **GNU Lesser General Public License, version 2.1 or later**
(portions are GPL-2.0-or-later when built with GPL-enabled options).
Source: https://ffmpeg.org/download.html

Files, Windows: `avcodec-63.dll`, `avformat-63.dll`, `avutil-61.dll`,
`swresample-7.dll`

    https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

### dav1d 1.5.4

Copyright © VideoLAN and dav1d authors.
Licensed under the **BSD 2-Clause licence**.
Source: https://code.videolan.org/videolan/dav1d

The AV1 decoder. It has no file of its own: on Windows it is compiled into
`avcodec-63.dll`, and on macOS and Linux into the `scrcpy` binary.

### SDL3 3.4.18

Copyright © Sam Lantinga and contributors.
Licensed under the **zlib licence**.
Source: https://github.com/libsdl-org/SDL

Files, Windows: `SDL3.dll`

### libusb 1.0.30

Copyright © the libusb contributors.
Licensed under the **GNU Lesser General Public License, version 2.1 or later**.
Source: https://github.com/libusb/libusb

Files, Windows: `libusb-1.0.dll`

### scrcpy images

`scrcpy.png` and `disconnected.png` in `resources/bin` are scrcpy's own
application icon and its "phone disconnected" picture, from the scrcpy release
archive. A copy of `disconnected.png` is also in `resources/icon`, where the
desktop window shows it when the phone disconnects (the `scrcpy.png` beside it
there is this project's own logo, under the file name scrcpy loads).
Copyright © the scrcpy authors; Apache License, Version 2.0.

---

## Compiled into the app (Windows and macOS)

### gilrs 0.11.2 and gilrs-core 0.6.8

Copyright © the gilrs project contributors.
Licensed under the **Apache License, Version 2.0** or the **MIT licence**, at
your option.
Source: https://gitlab.com/gilrs-project/gilrs

Reads game controllers for the phone apps' windows (doc/game-mode.md). It has
no file of its own: it is compiled into the app's executable on Windows and
macOS; a Linux build does not include it.

### SDL_GameControllerDB

Copyright © 1997-2025 Sam Lantinga and the SDL_GameControllerDB contributors.
Licensed under the **zlib licence**.
Source: https://github.com/mdqinc/SDL_GameControllerDB

The controller mappings gilrs carries, compiled into the app with it, so
PlayStation and Switch controllers map as Xbox ones do.

---

## Bundled inside the launcher APK

These are extracted and executed **on the phone**, never on the host.

### PRoot 0.15

Copyright © STMicroelectronics and contributors.
Licensed under the **GNU General Public License, version 2 or later**.
Source: https://github.com/green-green-avk/proot, tag `v0.15_release` (a fork
of Termux's https://github.com/termux/proot, itself a fork of
https://github.com/proot-me/proot), built the way
https://github.com/green-green-avk/build-proot-android builds it.

Files: `lib/<abi>/libproot.so`, `libloader.so`, `libloader32.so`

Built with one change by this project,
`openandroiddex-linux/proot/patches/untag-tracee-addresses.patch`: before
proot reads a program's memory, it drops the tag Android puts in the top byte
of heap pointers. Without it proot could not start anything on some kernels
(Linux 6.1 on a Galaxy S24, for one). The build recipe is in
`openandroiddex-linux/proot/`.

    https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

#### talloc 2.1.14

Copyright © Andrew Tridgell and Stefan Metzmacher.
Licensed under the **GNU Lesser General Public License, version 3 or later**.
Source: https://download.samba.org/pub/talloc/talloc-2.1.14.tar.gz

The memory allocator PRoot uses, linked into `libproot.so` from unmodified
source.

    https://www.gnu.org/licenses/lgpl-3.0.html

### QEMU 9.2.3

Copyright © Fabrice Bellard and the QEMU contributors.
Licensed under the **GNU General Public License, version 2** (QEMU is
GPL-2.0-only; individual components carry other compatible licences, listed in
the upstream `LICENSE` file).
Source: https://www.qemu.org/download/#source

Files: `lib/<abi>/libqemu.so` — the TCG system emulator that runs the Docker
guest VM. Built from unmodified upstream sources with the NDK toolchain; the
build recipe is in `openandroiddex-docker/qemu/`.

    https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

### Mesa 26.3.0: Turnip, the Vulkan driver for Adreno GPUs

Copyright © the Mesa contributors; each source file names its own copyright
holders.
Licensed under the **MIT licence** (text below). The few files compiled into
the driver under other licences are listed after it.
Source: https://archive.mesa3d.org/mesa-26.3.0.tar.xz

Files: `assets/linux/gpu/libvulkan_freedreno.so`. Built from unmodified
upstream source for the KGSL kernel interface Android's Qualcomm kernels
expose; the recipe, with the tarball's checksum, is in
`openandroiddex-linux/turnip/`. On a phone with an Adreno GPU it is copied
into the Linux guest, where Linux apps load it.

`assets/linux/gpu/oadx-vkprobe` beside it is not Mesa: it is this project's
own program (GPL-3.0-or-later), from `openandroiddex-linux/gpu-probe/oadx-vkprobe.c`.

    Permission is hereby granted, free of charge, to any person obtaining a
    copy of this software and associated documentation files (the "Software"),
    to deal in the Software without restriction, including without limitation
    the rights to use, copy, modify, merge, publish, distribute, sublicense,
    and/or sell copies of the Software, and to permit persons to whom the
    Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in
    all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
    FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
    DEALINGS IN THE SOFTWARE.

Files in the driver under other licences:

- **xxHash** (`src/util/xxhash.h`): Copyright © 2012-2023 Yann Collet.
  BSD 2-Clause licence, text below.
- **Berkeley SoftFloat 3e** (`src/util/softfloat.c`, functions modified by
  Mesa): Copyright © 2011–2018 The Regents of the University of California.
  BSD 3-Clause licence, text below.
- **`src/loader/loader_dri_helper.c`**: an X11-style permission notice with
  no copyright line, text below.
- **C11 threads** (`src/c11/impl/threads_posix.c`): Copyright © 2012 yohhoy.
  Boost Software License 1.0.
- **BLAKE3 1.8.2** (`src/util/blake3/`): CC0-1.0, or Apache-2.0, or Apache-2.0
  with LLVM exceptions.
- Headers: the Khronos Vulkan and SPIR-V headers (Apache-2.0 or MIT; MIT),
  Android's `vk_android_native_buffer.h` and the OpenGL ES platform headers
  (Apache-2.0), `GLES3/gl3ext.h` (SGI Free Software License B 2.0), and the
  KGSL kernel interface header `msm_kgsl.h` (GPL-2.0 WITH Linux-syscall-note:
  a program that uses the kernel's interface through it is not covered by
  the GPL).

xxHash:

    Copyright (C) 2012-2023 Yann Collet

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are
    met:

       * Redistributions of source code must retain the above copyright
         notice, this list of conditions and the following disclaimer.
       * Redistributions in binary form must reproduce the above
         copyright notice, this list of conditions and the following disclaimer
         in the documentation and/or other materials provided with the
         distribution.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
    "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
    LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
    A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
    OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
    SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
    LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
    DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
    THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
    (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Berkeley SoftFloat Release 3e:

    Copyright 2011, 2012, 2013, 2014, 2015, 2016, 2017, 2018 The Regents of the
    University of California.  All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

     1. Redistributions of source code must retain the above copyright notice,
        this list of conditions, and the following disclaimer.

     2. Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions, and the following disclaimer in the
        documentation and/or other materials provided with the distribution.

     3. Neither the name of the University nor the names of its contributors
        may be used to endorse or promote products derived from this software
        without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS", AND ANY
    EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, ARE
    DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
    DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
    (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
    LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
    ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
    (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
    THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

`src/loader/loader_dri_helper.c`:

    Permission to use, copy, modify, distribute, and sell this software and its
    documentation for any purpose is hereby granted without fee, provided that
    the above copyright notice appear in all copies and that both that copyright
    notice and this permission notice appear in supporting documentation, and
    that the name of the copyright holders not be used in advertising or
    publicity pertaining to distribution of the software without specific,
    written prior permission.  The copyright holders make no representations
    about the suitability of this software for any purpose.  It is provided "as
    is" without express or implied warranty.

    THE COPYRIGHT HOLDERS DISCLAIM ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
    INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS, IN NO
    EVENT SHALL THE COPYRIGHT HOLDERS BE LIABLE FOR ANY SPECIAL, INDIRECT OR
    CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
    DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
    TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE
    OF THIS SOFTWARE.

### radv-xclipse 1.8.0: Mesa's RADV for Samsung Xclipse GPUs

Copyright © 2026 JimVulkan (the Xclipse changes) and the Mesa contributors;
each source file names its own copyright holders.
Licensed under the **MIT licence** (the text in the Mesa section above).
Source: https://github.com/JimVulkan/radv-xclipse, tag `v1.8.0` (commit
`269238e8538c971cbe5195ed3e0013dea59f3bf5`), a port of Mesa 27.0.0-devel's
RADV Vulkan driver to Samsung's Xclipse GPUs.

Files: `assets/linux/gpu/libvulkan_xclipse.so`. The port only builds for
Android apps; this one is built for the Linux guest by the recipe in
`openandroiddex-linux/radv-xclipse/`, which also puts back 15 files from
upstream Mesa that the port removed (listed in `upstream-files.sha256`; MIT,
or Apache-2.0 or MIT) and applies two patches by this project (MIT):
`patches/linux-compat-headers.patch` and
`patches/quiet-drm-version-notice.patch`. On an Exynos phone with an Xclipse
GPU it is copied into the Linux guest, where Linux apps load it.

Files in the driver under other licences are the ones listed in the Mesa
section above (xxHash, Berkeley SoftFloat 3e, `loader_dri_helper.c`, C11
threads, BLAKE3 and the headers), whose texts are there, and:

- `src/vulkan/wsi/wsi_common_display.h`: Copyright © 2017 Keith Packard, under
  the same X11-style permission notice as `loader_dri_helper.c`.
- `include/drm-uapi/dma-buf.h`, a Linux kernel interface header: GPL-2.0 WITH
  Linux-syscall-note, as `msm_kgsl.h` above.

Linked into the driver, each from the source the port's build fetches:

- **libdrm 2.4.133**: Copyright © Advanced Micro Devices, Inc., Precision
  Insight, Inc., VA Linux Systems, Inc. and the libdrm contributors. MIT
  licence. Source: https://dri.freedesktop.org/libdrm/libdrm-2.4.133.tar.xz,
  with the port's two patches (`subprojects/packagefiles/` in its repository).
- **Expat 2.5.0**: Copyright © 1998-2000 Thai Open Source Software Center Ltd
  and Clark Cooper, © 2001-2022 Expat maintainers. MIT licence. Source:
  https://github.com/libexpat/libexpat/releases/download/R_2_5_0/expat-2.5.0.tar.xz
- **zlib 1.3.1**: Copyright © 1995-2024 Jean-loup Gailly and Mark Adler.
  zlib licence, text below. Source: http://zlib.net/fossils/zlib-1.3.1.tar.gz

zlib:

    This software is provided 'as-is', without any express or implied
    warranty.  In no event will the authors be held liable for any damages
    arising from the use of this software.

    Permission is granted to anyone to use this software for any purpose,
    including commercial applications, and to alter it and redistribute it
    freely, subject to the following restrictions:

    1. The origin of this software must not be misrepresented; you must not
       claim that you wrote the original software. If you use this software
       in a product, an acknowledgment in the product documentation would be
       appreciated but is not required.
    2. Altered source versions must be plainly marked as such, and must not be
       misrepresented as being the original software.
    3. This notice may not be removed or altered from any source distribution.

### Ubuntu Base 24.04 (arm64)

Copyright © Canonical Ltd. and others. Ubuntu is a trademark of Canonical Ltd.
The image is an aggregate of Ubuntu packages, each under its own licence — the
individual notices ship inside the image at `/usr/share/doc/<package>/copyright`.
Source: https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/

Extracted on the phone as the Linux guest's root filesystem.

---

## Downloaded at runtime, not bundled

### Alpine Linux (branch v3.22)

Copyright © the Alpine Linux developers. Alpine Linux is a trademark of the
Alpine Linux Development Team.
The minirootfs and the packages fetched by `apk` are each under their own
licence; notices ship inside the image.
Source: https://alpinelinux.org/downloads/

Fetched on first run of the Docker guest and installed into the VM disk on the
phone. Not present in any release artifact.

### Docker Engine and guest packages

`docker`, `containerd`, `runc` and everything else `apk` installs into the guest
are downloaded from Alpine's repositories at first run, under their own licences
(Docker Engine: Apache-2.0). Not redistributed by this project.

---

## Source availability

For the GPL- and LGPL-licensed works above, the corresponding source is
available from each project's upstream URL listed in its section. The build
recipes are in this repository:

- `libproot.so`: `openandroiddex-linux/proot/`. It builds PRoot's upstream
  source with the one patch in `openandroiddex-linux/proot/patches/`, and
  talloc's unmodified.
- `libqemu.so`: `openandroiddex-docker/qemu/`, from unmodified upstream
  source.
- `libvulkan_freedreno.so`: `openandroiddex-linux/turnip/`, from unmodified
  upstream source.
- `libvulkan_xclipse.so`: `openandroiddex-linux/radv-xclipse/`, from the
  port's source with the 15 upstream Mesa files and the two patches it lists,
  and libdrm, Expat and zlib as the port's build fetches them.
