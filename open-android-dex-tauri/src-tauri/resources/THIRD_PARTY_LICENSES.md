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

## Bundled inside the launcher APK

These are extracted and executed **on the phone**, never on the host.

### PRoot

Copyright © STMicroelectronics and contributors.
Licensed under the **GNU General Public License, version 2**.
Android build: https://github.com/green-green-avk/build-proot-android
Upstream source: https://github.com/proot-me/proot

Files: `lib/<abi>/libproot.so`, `libloader.so`, `libloader32.so`

    https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

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
recipes used to produce `libproot.so` and `libqemu.so` are in
`openandroiddex-linux/proot/` and `openandroiddex-docker/qemu/` in this
repository; both build unmodified upstream sources.
