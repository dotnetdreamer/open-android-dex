# The GPU for Linux apps

Until now every frame a Linux app drew with OpenGL was drawn by llvmpipe, Mesa's
software renderer, on the phone's CPU. Now it is drawn by the GPU on two kinds of
phone, each with a Mesa Vulkan driver that runs the GPU directly, through the
same kernel device the phone's own driver uses, and Zink putting OpenGL 4.6 on
top of it:

- **Adreno (Snapdragon)**: Mesa's Turnip.
- **Xclipse (Samsung's Exynos 2200 and later)**: Mesa's RADV, as the
  radv-xclipse project ported it to Samsung's kernel driver.

Everywhere else nothing changes.

Status: **running on a Galaxy S25 Ultra (Adreno 830, Android 17) and a Galaxy
S24 (Exynos 2400, Xclipse 940, Android 15)**, in the app's own sessions; see
"Measured" and "What is unproven".

---

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The Adreno driver | `openandroiddex-linux/turnip/` → `assets/linux/gpu/` | Turnip from upstream Mesa, built for KGSL |
| The Xclipse driver | `openandroiddex-linux/radv-xclipse/` → `assets/linux/gpu/` | radv-xclipse's RADV, built for the Linux guest |
| The probe | `openandroiddex-linux/gpu-probe/` → `assets/linux/gpu/` | `oadx-vkprobe`, the same for both |
| Into the guest | `LinuxGpu.stage` (from `LinuxService.startRuntime` / `startWin`) | copying this phone's driver in once per `DRIVER_VERSION` |
| Whether to try | `LinuxGpu.env` | which GPU (`LinuxGpu.driver`), switch on, staged → `LINUX_GPU=turnip` or `xclipse` |
| Whether it works | `linux-rt.sh`, `linux-win.sh` | the probe, and only on its yes, the GL environment |
| What it said | `LinuxGpu.reportVerdict` | the session's `gpu:` line, copied into the app's log |
| The switch | `LauncherActivity.showLinuxMenu` | "Use the GPU" on the Linux tile, `filesDir/linux-gpu` |

```
Linux app ──GL──▶ Ubuntu's Mesa: Zink ──Vulkan──▶ Turnip (ours) ──ioctl──▶ /dev/kgsl-3d0 ──▶ Adreno
                       │                  or ──▶ RADV (ours) ──ioctl──▶ /dev/dri/renderD128 ──▶ Xclipse
                       │
                       └── kopper swapchain ──WSI software path, XPutImage──▶ Xvnc ──▶ viewer
```

## Why Turnip, and not a bridge to Android's driver

The phone's own GPU driver is Qualcomm's, for Android: bionic, OpenGL ES only. A
glibc Linux program cannot load it. The usual way round that in Termux setups is
VirGL: the guest's Mesa serialises its GL calls over a socket to an Android
process that replays them on Android's GLES. It works on any GPU, but every call
crosses a process boundary and GL is emulated on GLES. One published S26 Ultra
setup reaches glmark2 186 that way.

Turnip needs none of that. It is upstream Mesa's Vulkan driver for Adreno, and it
has had a KGSL backend for years, KGSL being the kernel interface Android's
Qualcomm kernels expose and an app may open. Run inside the guest, it drives the
GPU itself. What it cannot do is run on anything but an Adreno, which is why
`LinuxGpu.available()` asks for `/dev/kgsl-3d0` before anything else.

A VirGL server for the other GPUs was built as far as a first compile and set
aside: a second process, a socket and an NDK build, for less speed. Xclipse
phones turned out not to need it (below); Mali and PowerVR phones still would.

## Why ours, and only Turnip

Ubuntu's own Turnip is built for desktop Linux's msm DRM interface only, so it
finds no GPU here. Ours is the same upstream source configured with
`-Dfreedreno-kmds=kgsl`, nothing else in it: no GL, no patches, X11 only. 26.3.0
is pinned because it is the first release with the KGSL sync fixes (mesa!44838)
and it lists the Adreno 830 and 840; the tarball's signature was checked against
Mesa's release-maintainer keys before its sha256 went into the Dockerfile.

GL comes from the guest's **own** Mesa, Ubuntu's 25.2.8, through its Zink. So
the guest's packages are untouched, apt keeps updating them, and the one file we
own is the driver.

## Xclipse: RADV, as radv-xclipse ported it

Samsung's Xclipse GPUs are AMD designs, and Samsung's kernel driver for them,
sgpu, is a fork of AMD's amdgpu that still speaks its interface. On the Galaxy
S24, `/dev/dri/renderD128` answers `DRM_IOCTL_VERSION` as **amdgpu 3.42.0** and
AMD's `AMDGPU_INFO` queries with the GPU (device `0x73a0`, 12 compute units),
from the app's own uid. So Mesa's AMD driver, RADV, can run it the way Turnip
runs an Adreno, given the Xclipse quirks: that is what
[radv-xclipse](https://github.com/JimVulkan/radv-xclipse) does, a community
port (MIT) of Mesa 27.0.0-devel's RADV that lists the Xclipse 920, 530, 940,
540, 550, 950 and 960. Its README says it was developed with heavy use of AI
tools and tested on a Galaxy S22 Ultra and through Samsung's Remote Test Lab,
and that a GPU hang can reboot the phone, which it puts down to the kernel
driver. None happened here.

The port builds only for Android apps (emulators). Ours, in
`openandroiddex-linux/radv-xclipse/`, builds v1.8.0 for the Linux guest:

- **Mesa's X11 parts the port deleted** go back in: `src/x11`, the X11 Vulkan
  headers and what Mesa's loader includes, 15 files from upstream Mesa at the
  port's base, each checked by hash (`upstream-files.sha256`).
- **Two headers the port's build expects for exactly this** (a glibc Linux
  build, "PRoot" in its meson.build) but never published: stand-ins for
  Android's log (to stderr) and properties (none; every switch has an
  environment variable too). `patches/linux-compat-headers.patch`.
- **A notice the kernel's age prints**: "DRM version is 3.42.0 (< 3.59);
  continuing." went to the stderr of every GL app; now only with the port's
  `RADV_XCLIPSE_LOG`. `patches/quiet-drm-version-notice.patch`.
- `--prefix /usr`, so the static libdrm finds the guest's `amdgpu.ids` instead
  of saying it cannot (the Xclipse ids are not in it; the name stays "AMD
  Radeon Graphics").

The port had already done the hard part for us: its `wsi_common_x11.c` presents
without DRI3 (`x11_sw_fallback`), which is exactly an Xvnc. The environment is
the Adreno one, unchanged.

`LinuxGpu.driver` calls a phone an Xclipse one when it has
`/dev/dri/renderD128` and either sysfs names its kernel driver `sgpu` or
`/vendor/lib64/libdrm_sgpu.so` exists; a Mali Exynos has neither. The probe has
the last word, as for Turnip.

## The environment, and the trap beside it

Xvnc has no DRI3, the extension through which an app hands the X server a GPU
buffer, and cannot get one here: TigerVNC's DRI3 needs a DRM render node, and an
Adreno phone gives apps KGSL instead. Without it Mesa refuses hardware GL by
default. What works, exported only after the probe says yes:

- `MESA_LOADER_DRIVER_OVERRIDE=zink` asks GLX (and EGL) for Zink.
- `LIBGL_KOPPER_DRI2=1` is what lets GLX accept that without DRI3
  (`glxext.c`: no multibuffer and no `LIBGL_KOPPER_DRI2` is "DRI3 not
  available"). Zink then presents through kopper, its own Vulkan swapchain.
- `MESA_VK_WSI_DEBUG=sw` makes that swapchain copy each finished frame into the
  window with XPutImage instead of asking for DRI3.

**Not `GALLIUM_DRIVER=zink`.** It looks equivalent and glxinfo agrees, but it
puts Zink under the *software* GL path, which never hands a frame to the window:
measured, glmark2 at 1,637 FPS in a black window. Every benchmark run without a
viewer looked fine; the first screenshot did not. Check a frame, not a number.

EGL takes the same variables: `eglinfo -p x11` reports Zink on the Adreno for
both GL and GLES. GTK3 apps that draw no GL pay nothing: an xfce4-terminal is
32.7 MB against 32.4 MB on the CPU, and never opens the GPU device.

## Why a probe, and why in the guest

Mesa does not fall back. With Zink asked for and no working Vulkan device under
it, an app gets no GL at all ("glx: failed to create drisw screen", then a
BadValue), measured with the driver pointed at nothing. So the variables are only
exported after `oadx-vkprobe` has said yes: it opens the Vulkan loader, finds a
device driven by Turnip or RADV (through our driver's ICD alone) and creates a
logical device on it, the first point where the kernel is asked for a GPU
context. With no driver it exits 3 (`vkCreateInstance -9`) and the session
keeps llvmpipe.

It runs at every start, beside the Xvnc start, so it costs the session next to
nothing. It is exported after Xvnc and before the session bus: the apps, and the
ones the bus activates, get the GPU; the X server's own GLX does not. The verdict
lands in `rt.log` / `win.log` as a `gpu:` line and, through `reportVerdict`, in
the app's log, which is where a bug report finds it.

## Measured

Galaxy S25 Ultra (Snapdragon 8 Elite, Adreno 830), Android 17, the guest's proot,
glmark2 2023.01, frames presented. CPU first, on USB power, thermal status 0.

| | llvmpipe (before) | Zink on Turnip |
| --- | ---: | ---: |
| glmark2, default 800x600, private Xvnc | 345 | 459 |
| 1920x1080 full screen, 7-scene subset | 75 | 121 |
| terrain, 1080p | 6 FPS | 109 FPS |
| refract, 1080p | 16 FPS | 101 FPS |
| desktop blur, 1080p | 38 FPS | 118 FPS |
| jellyfish, 800x600, the real desktop with the viewer on | 144 FPS | 732 FPS |

The shape matters more than the score. Heavy scenes gain most (terrain 18.8x,
refract 14.5x, blur 4x at 800x600). Every GPU scene tops out at the cost of the
copy: about 500 FPS at 800x600 and 130 at 1080p. So trivially light scenes that
llvmpipe draws at 600-800 FPS come out a little slower (0.6-0.8x), which no app
showing 60 frames a second will notice. The copy goes over the X socket:
`MESA_VK_WSI_DEBUG=sw,noshm` changes nothing (123 against 123 FPS), because
System V shared memory does not exist under proot on Android.

### Linux apps as windows

The same phone on 2026-11-11, glmark2 opened from the desktop's app drawer as
a window of its own (800x600), the same seven scenes. Both runs were in one
window session: the CPU run is the same app started with the three variables
removed (`env -u`), which is the environment the session gives apps when the
probe says no or the switch is off.

| | llvmpipe | Zink on Turnip |
| --- | ---: | ---: |
| terrain | 10 FPS | 382 FPS |
| refract | 25 FPS | 339 FPS |
| jellyfish | 127 FPS | 1,009 FPS |
| desktop blur | 85 FPS | 507 FPS |
| phong shading | 224 FPS | 1,043 FPS |
| texture, mipmap | 468 FPS | 1,123 FPS |
| build, VBO | 356 FPS | 1,099 FPS |
| glmark2 score | 184 | 785 |

Here every scene is faster on the GPU, the light ones too (2.4x to 38x). These
rows are not comparable with the private-Xvnc ones above: in a window session
the launcher also reads and draws every frame of the window on the same
phone. Screenshots of the desktop display showed the scenes in the window
during both runs; the GPU run's process had the three variables, `DISPLAY=:2`
and `/dev/kgsl-3d0` open, and the CPU run's had none of them.

The same phone again that evening, kernel 6.6.127, on the build that made the
probe and the scripts serve both drivers (and the patched proot): the app
re-staged Turnip for the new `DRIVER_VERSION`, both sessions' probes said
`gpu: Adreno (TM) 830v1`, and jellyfish ran at 868 FPS on the Linux desktop and
693 FPS as a window of its own with the desktop session also running, both seen
on screen.

### Galaxy S24: Exynos 2400, Xclipse 940

Android 15, kernel 6.1, 2026-11-11, glmark2 2023.01 at 800x600, frames
presented, on USB power, thermal status 0 before and after. The app staged the
driver itself; both sessions' probes said `gpu: AMD Radeon Graphics (RADV
MAGELLAN CARL)`, and the app's log copied it.

The seven scenes on a private Xvnc, no viewer:

| | llvmpipe | Zink on RADV |
| --- | ---: | ---: |
| terrain | 6 FPS | 182 FPS |
| refract | 14 FPS | 376 FPS |
| jellyfish | 77 FPS | 508 FPS |
| desktop blur | 46 FPS | 441 FPS |
| phong shading | 103 FPS | 480 FPS |
| texture, mipmap | 208 FPS | 519 FPS |
| build, VBO | 85 FPS | 494 FPS |
| glmark2 score | 76 | 427 |

Jellyfish in the app's own sessions, with exactly the environment each gives
its apps (copied from its `xfce4-session` or `xfce4-terminal`; the CPU runs
the same with the three variables taken out):

| | llvmpipe | Zink on RADV |
| --- | ---: | ---: |
| on the Linux desktop, viewer on | 56 FPS | 439 FPS |
| as a window of its own (desktop session running too) | 56 FPS | 320 FPS |

Every scene is faster, the light ones too (2.5x to 30x), where on the S25 the
lightest came out a little slower; the GPU tops out near the same copy
ceiling, about 500 FPS. Screenshots
showed the jellyfish in both places, on the Linux desktop and as a window of
the phone's desktop; the GPU runs had the three variables and
`/dev/dri/renderD128` open, the CPU runs neither. No GPU hang and no reboot in
any of it.

Before it had this driver, the S24 was the check that a phone without one is
left alone: no "Use the GPU" in the menu, nothing copied into the guest, no
probe, no `gpu:` line, and none of the 34 processes of the two sessions with a
GL variable. Linux had never run on it before that: it first needed the proot
fix in `openandroiddex-linux/proot/README.md`.

## Costs

The drivers are in the APK's assets, in both the launcher and LinuxOnDroid:
Turnip 15 MB (3.5 MB compressed), RADV 20 MB (6.0 MB compressed). A phone gets
only its own copied into the guest, once per `DRIVER_VERSION`. The probe is one
short process per session start.

## What is unproven

- **Adreno 6xx and 7xx phones**, and Samsung's Snapdragon tablets. Turnip
  supports them upstream; none was at hand.
- **Xclipse GPUs other than the S24's 940**: the 920 (S22), 530, 540, 550,
  950 and 960. The port lists them all; it says ray tracing hangs the 950 and
  960. Only the S24, on Android 15, was at hand.
- **Long sessions on the S24, and its reboot-on-hang risk.** The port says a
  GPU hang can reboot the phone; none was seen in a few minutes of glmark2.
- **Apps beyond glmark2, vkcube and glxinfo/eglinfo.** The switch in the Linux
  tile's menu is the way out for an app that draws wrongly.
- **Linux apps shown on the computer.** Those run in the same window session
  and get the same variables; GL in one was only run on the phone's desktop.

## What is next

- The copy's ceiling: MIT-SHM by memfd (the way `X11.java` already reads
  windows) instead of XPutImage would take a copy off every frame.
- Chromium uses the GPU since feature level 19: its wrapper (`dex-chromium`,
  written by `browser_wrappers` in linux-setup.sh) leaves out `--disable-gpu`
  when the session's probe passed. Measured on the S24 at 1600x900, two runs
  each (2026-11-12): CSS animations 12–13 → 38 fps, WebGL none → 45–50 fps,
  scrolling (~48 fps) and canvas 2D unchanged, the same CPU. ANGLE picked Zink
  on RADV by itself. Not run: the S25 (Turnip), the window session (`:2`),
  real sites and video. Firefox (software WebRender) and VS Code
  (`--disable-gpu`) are unchanged and unmeasured.
- A display path with DRI3 would let finished frames skip the CPU entirely.
