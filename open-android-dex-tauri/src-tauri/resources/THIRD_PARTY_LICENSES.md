# Third-party software bundled with Open Android DeX

Open Android DeX itself is copyright © 2026 Idrees Khan and is distributed under
the **GNU General Public License, version 3 or later**. The works listed below
are independent projects distributed alongside it — in `resources/bin` on the
host, and inside the launcher APK on the phone. Each keeps its own licence and
its own copyright holders; none of them is a work of this project.

---

## scrcpy 3.3.4

Copyright © Genymobile, Romain Vimont and contributors.
Licensed under the **Apache License, Version 2.0**.
Source: https://github.com/Genymobile/scrcpy

Files, Windows: `scrcpy.exe`, `scrcpy-server`
Files, macOS: `scrcpy`, `scrcpy-server`

The scrcpy release archive also carries the libraries listed under
"Libraries bundled by scrcpy" below, which are **not** Apache-2.0. On macOS the
build is statically linked, so those libraries are inside the `scrcpy` binary
rather than beside it — their licences still apply.

## Android Debug Bridge (adb)

Part of the Android Open Source Project platform tools.
Copyright © The Android Open Source Project.
Licensed under the **Apache License, Version 2.0**.
Source: https://android.googlesource.com/platform/packages/modules/adb/

Files, Windows: `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`
Files, macOS: `adb`

Shipped as part of the scrcpy release above, which bundles adb on both platforms.

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

### FFmpeg

Copyright © the FFmpeg developers.
Licensed under the **GNU Lesser General Public License, version 2.1 or later**
(portions are GPL-2.0-or-later when built with GPL-enabled options).
Source: https://ffmpeg.org/download.html

Files, Windows: `avcodec-61.dll`, `avformat-61.dll`, `avutil-59.dll`,
`swresample-5.dll`

    https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

### SDL2

Copyright © Sam Lantinga and contributors.
Licensed under the **zlib licence**.
Source: https://github.com/libsdl-org/SDL

Files, Windows: `SDL2.dll`

### libusb

Copyright © the libusb contributors.
Licensed under the **GNU Lesser General Public License, version 2.1 or later**.
Source: https://github.com/libusb/libusb

Files, Windows: `libusb-1.0.dll`

### scrcpy application icon

`icon.png` is scrcpy's own application icon, from the scrcpy release archive,
and is used as the mirror window's icon. Copyright © the scrcpy authors.

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
