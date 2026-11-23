# The GPU for Linux apps

Until now every frame a Linux app drew with OpenGL was drawn by llvmpipe, Mesa's
software renderer, on the phone's CPU. Now it is drawn by the GPU on two kinds of
phone, each with a Mesa Vulkan driver that runs the GPU directly, through the
same kernel device the phone's own driver uses, and Zink putting OpenGL 4.6 on
top of it:

- **Adreno (Snapdragon), 600 series and later**: Mesa's Turnip.
- **Xclipse (Samsung's Exynos 2200 and later)**: Mesa's RADV, as the
  radv-xclipse project ported it to Samsung's kernel driver.

On every other 64-bit phone (a Mali, a PowerVR, an Adreno older than the 600
series) the guest's GL goes to the phone's own GLES driver instead, through
VirGL ("VirGL: any other GPU"). A 32-bit phone runs no Linux at all, and its
settings say so.

Status: **running on a Galaxy S25 Ultra (Adreno 830, Android 17) and a Galaxy
S24 (Exynos 2400, Xclipse 940, Android 15)**, in the app's own sessions, and
**VirGL on a Redmi Note 7 (Adreno 512, Android 10)**; see "Measured" and "What
is unproven".

---

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The Adreno driver | `openandroiddex-linux/turnip/` → `assets/linux/gpu/` | Turnip from upstream Mesa, built for KGSL |
| The Xclipse driver | `openandroiddex-linux/radv-xclipse/` → `assets/linux/gpu/` | radv-xclipse's RADV, built for the Linux guest |
| The VirGL server | `openandroiddex-linux/virgl/` → `jniLibs/<abi>/libvirglserver.so` | virglrenderer's vtest server, built with the NDK, on the phone's GLES |
| The probes | `openandroiddex-linux/gpu-probe/` → `assets/linux/gpu/` | `oadx-vkprobe` for both Vulkan drivers, `oadx-glprobe` for VirGL |
| Into the guest | `LinuxGpu.stage` (from `LinuxService.startRuntime` / `startWin`) | copying this phone's driver (or VirGL's probe) in once per `DRIVER_VERSION` |
| Whether to try | `LinuxGpu.env` | which GPU (`LinuxGpu.driver`), switch on, staged → `LINUX_GPU=turnip`, `xclipse` or `virgl`; for VirGL, its check on this phone (`virglCheck`) and the server up (`startVirgl`) |
| Whether it works | `linux-rt.sh`, `linux-win.sh` | the probe, and only on its yes, the GL environment |
| The server's life | `LinuxGpu.startVirgl` / `stopVirgl`, from `LinuxService` | up before a session that will use it, down when neither session is left |
| How Chromium draws | `linux-rt.sh`, `linux-win.sh` → the guest's `/etc/chromium.d/oadx-gpu` | on an Adreno whose probe said yes, ANGLE on Vulkan straight on Turnip ("Chromium", under "Measured") |
| What it said | `LinuxGpu.reportVerdict` | the session's `gpu:` line, copied into the app's log |
| The switch | `LinuxSettingsActivity` | "Use the graphics chip" under "Graphics for Linux apps" on the Linux settings page: the desktop's Linux tile menu opens it, and so does LinuxOnDroid's home screen menu; `filesDir/linux-gpu`. The line under it says which way the phone takes (`LinuxGpu.offer`) |

```
Linux app ──GL──▶ Ubuntu's Mesa: Zink ──Vulkan──▶ Turnip (ours) ──ioctl──▶ /dev/kgsl-3d0 ──▶ Adreno
                       │                  or ──▶ RADV (ours) ──ioctl──▶ /dev/dri/renderD128 ──▶ Xclipse
                       │
                       └── kopper swapchain ──WSI software path, XPutImage──▶ Xvnc ──▶ viewer

Chromium, on an Adreno ──ANGLE──Vulkan──▶ Turnip (ours), no Zink in between
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
aside on 2026-11-10: a second process, a socket and an NDK build, for less
speed. Xclipse phones turned out not to need it (below). Mali and PowerVR
phones, and Adreno phones older than Turnip's, did: on 2026-11-22 the user
asked for the GPU on those too, and it was finished ("VirGL: any other GPU").

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

## VirGL: any other GPU

A Mali (most MediaTek phones, some Exynos and Tensor ones), a PowerVR (many
budget MediaTek and Unisoc phones) and an Adreno older than the 600 series
have no Mesa driver of ours. For them the guest's GL goes to the phone's own
GLES driver through VirGL, the way Termux setups do it, all of it ours:

```
Linux app ──GLX──▶ Ubuntu's Mesa: virpipe (GALLIUM_DRIVER) ──vtest, /tmp/.virgl_test──▶
   libvirglserver.so (an Android process of this app) ──GLES──▶ the phone's own driver ──▶ GPU
   each finished frame read back over the socket ──XPutImage──▶ Xvnc ──▶ viewer
```

- **The server** (`openandroiddex-linux/virgl/`, a Docker build like the
  others): virglrenderer 1.3.0's vtest server and libepoxy 1.5.10, both MIT,
  linked static with NDK r30 for API 26, every load segment 16 KB aligned, and
  needing nothing on the phone but bionic: epoxy opens the platform's libEGL
  and libGLESv2 at run time. A jniLib, `libvirglserver.so`, because the
  native-library directory is the one place an app may exec from. Five
  patches:
  - 0001, 0002: Android has EGL but no GBM, so EGL on the default display;
  - 0003: `--probe`, the renderer brought up once and its name printed, for
    the app's check;
  - 0004: the bundled Mesa util's Android branch wants AOSP's `<log/log.h>`
    and `<cutils/properties.h>`, which the NDK has not: bionic's own property
    call, and messages to stderr, which the app keeps as the server's log;
  - 0005: a shader that writes `gl_FragColor` writes every colour buffer, and
    for a GLES host virglrenderer declared an output for each of
    `max_draw_buffers`; an Adreno 512's compiler refused every such shader
    ("Output fsout_c1 location or component exceeds max allowed", so glmark2's
    first scene killed the connection). Now only the buffers bound, at least
    one.
- **Which phones** (`LinuxGpu.driver`, `offer`): an Adreno of the 600 series
  or later keeps Turnip, an Xclipse keeps RADV, every other 64-bit phone gets
  VirGL. The Adreno's series is read from KGSL's `gpu_model` where an app may
  read it, else from Android's GL renderer string (`LinuxGpu.glRenderer`, a
  1x1 pbuffer context, once per process).
- **The app's check** (`LinuxGpu.virglCheck`): the server's `--probe` in the
  app's own process, once per phone build and server (`linux-gpu-virgl`,
  keyed by `DRIVER_VERSION` and `Build.FINGERPRINT`). A no is remembered, and
  the settings then say the chip does not work with Linux.
- **The server's life** (`startVirgl`, `stopVirgl`): one process for the
  desktop and the session for apps in windows, `--no-fork --multi-clients`,
  because a process per GL app would count against Android's limit on an
  app's child processes, which closes Linux when it is reached
  (`ChildProcessLimit`). Its GL context exists only while
  a guest app is connected. It goes when neither session is left, with the
  service, or with the app's process.
- **The guest's probe** (`oadx-glprobe`, `openandroiddex-linux/gpu-probe/`):
  a GL context through GLX on the session's own display, which is what its
  apps will use, and a yes only for a VirGL renderer (llvmpipe would mean the
  variable was not honoured); EGL's surfaceless platform only with no display
  or no libGL (through VirGL it offered no config at all). A guest with no
  Mesa yet (no GL app installed) is "not checked" and still gets the variable:
  the server was checked on the Android side, and an app installed later
  draws through it. The session's `gpu:` line names the renderer and the way
  it was asked.
- **The environment**: `GALLIUM_DRIVER=virpipe` alone, exported after the
  probe's yes. Xvnc has no DRI3, so Mesa's GLX takes its software path,
  `virpipe` is that path's driver, and the software path is the one that
  hands each frame to the window, unlike Zink under it (the trap above).
  Chromium's wrapper keeps `--disable-gpu` under VirGL: it only lets the GPU
  in for Zink.

### Measured: Redmi Note 7

Snapdragon 660, Adreno 512, Android 10 (MIUI 12.5), 2026-11-22, glmark2
2023.01 at 800x600, the guest's proot, on USB power, thermal status 0 before
and after (battery 34-35 °C). A private Xvnc with no viewer, the processor
first, then through VirGL with the server as the app starts it:

| | llvmpipe | VirGL | |
| --- | ---: | ---: | ---: |
| terrain | 3 FPS | 22 FPS | 7.3x |
| refract | 7 FPS | 36 FPS | 5.1x |
| desktop blur | 22 FPS | 57 FPS | 2.6x |
| jellyfish | 53 FPS | 71 FPS | 1.3x |
| phong shading | 66 FPS | 73 FPS | 1.1x |
| texture, mipmap | 136 FPS | 93 FPS | 0.68x |
| build, VBO | 113 FPS | 86 FPS | 0.76x |
| the seven scenes' score | 56 | 61 | |
| glmark2's default run, score | 74 | 71 | |

The heavy scenes, where the processor is no use at all (3 and 7 frames a
second), become usable; the light ones stop near 90 frames a second, the cost
of reading each frame back over the socket and copying it into Xvnc, where
this phone's eight cores draw them faster themselves. Both stay above a
screen's 60. GL is 2.1 in the compatibility context glmark2 asks for; a core
context's version was not measured.

On the real desktop, with the app's viewer on the phone's screen: jellyfish
33 FPS on the processor, 47 through VirGL. Through the whole chain as a user
gets it (Start Linux on the home screen): the app's check said yes on the
Adreno (TM) 512, the server listened before the session started, the
session's probe said `gpu: VirGL, virgl (Adreno (TM) 512), GL 2.1 Mesa 25.2.8
(GLX)`, the session's own environment carried `GALLIUM_DRIVER=virpipe`, and
glmark2 started with exactly that environment drew through VirGL at 46 FPS.
Screenshots of the phone's screen showed the jellyfish drawn right, and
moving. Shut down from the home screen, the server went with the session.

Found on the way, on the same phone: Linux setup itself stopped at once on
Android 10 ("can't create temporary file /data/local/shm….tmp", FAILED:
configure), the phone's shell keeping here-documents in a folder an app may
not write. The scripts now get `TMPDIR`, the app's cache (`Linux.scriptEnv`).

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
left alone: no "Use the GPU" in the desktop's menu (today the Linux settings
page shows it greyed, with the reason), nothing copied into the guest, no
probe, no `gpu:` line, and none of the 34 processes of the two sessions with a
GL variable. Linux had never run on it before that: it first needed the proot
fix in `openandroiddex-linux/proot/README.md`.

### Chromium

Since 2026-11-20, Linux's Chromium on an Adreno draws through ANGLE on Vulkan,
straight on Turnip, instead of GL through Zink. Ubuntu's `/usr/bin/chromium`
empties `CHROMIUM_FLAGS` and then reads every file in `/etc/chromium.d`, so a
flag meant for every launch has to be there; the session's own environment
never reaches it. `linux-rt.sh` and `linux-win.sh` write
`/etc/chromium.d/oadx-gpu` at every start, so a guest built by an older setup
gets it too. At each launch it adds `--use-angle=vulkan
--enable-features=Vulkan` when that launch's environment has `OADX_GPU=turnip`
and the probe's `MESA_LOADER_DRIVER_OVERRIDE=zink`. Anywhere else it adds
nothing: `dex-chromium` still decides `--disable-gpu`, and an Xclipse phone
keeps Zink (not measured there).

Why: on Zink, Chromium leaves full screen the moment it enters it. A page's
`requestFullscreen()` resolves, `fullscreenchange` fires true and then false,
and YouTube's full screen (its button, its `f` key) never takes
(`doc/linux-browser.md`). Xfwm4 is not the cause: asked by hand, it makes
Chromium's window full screen. With `--disable-gpu-compositing`, or on Vulkan,
full screen stays.

Measured on the S25 Ultra on 2026-11-20, before the change: a test page in
Linux's Chromium, a 1600x900 window on a private 1920x1080 Xvnc with Xfwm4
(compositing off), the app's desktop session running beside it, the phone
awake, unlocked and on AC power. One fresh Chromium on a fresh profile per
run, in this order: Zink, Vulkan, no GPU, Vulkan, Zink, Vulkan. Frames a
second are the page's own (`requestAnimationFrame` over 6 s, after 1 s of
warm-up); CPU is Chromium's whole process tree, in cores. The video is a
generated test pattern with noise, VP9 at 8 Mb/s, decoded in software.

| | GL through Zink (before) | ANGLE on Vulkan (now) | no GPU |
| --- | ---: | ---: | ---: |
| CSS animations, 320 boxes | 42–43 | 60 | 34 |
| WebGL, a full-window shader | 43–47 | 37–38 | none |
| canvas 2D, 1,500 shapes and 100 texts a frame | 48 | 60 | 60 |
| scrolling 400 styled cards | 41–45 | 60 | 60 |
| scrolling plain text | 47–48 | 60 | 60 |
| 1080p60 VP9 video, frames dropped (the player's count) | 29–30% | 0 | 19% |
| the same, frames shown a second (`requestVideoFrameCallback`) | 40–41 | 44–46 | 43 |
| a page's full screen, 2.5 s after a click | left at once | stays, 1920x1080 | stays |
| CPU, scrolling plain text | 0.32–0.49 | 0.42–0.44 | 0.75 |
| CPU, CSS animations | 0.75–1.06 | 1.16–1.26 | 1.18 |

Vulkan reaches the 60 frames a second the CPU path gets, on less CPU than it
(0.42–0.44 cores against 0.75 scrolling text), and keeps what the GPU gave CSS.
It spends more CPU than Zink, for more frames: about 0.02 core-seconds a frame
on the CSS animations either way. WebGL is slower: 37.6 against 44.9 frames a
second, the runs' averages, 16% less. The ceiling near 45 that the runs of
2026-11-13 put down to the copy (see "What is next") came with Zink: with the
same session variables, Chromium on Vulkan paints 60. Each run's renderer says
which path drew: `ANGLE (Mesa, zink Vulkan 1.4(Adreno (TM) 830v1
(MESA_TURNIP)), OpenGL 4.6)` against `ANGLE (Qualcomm, Vulkan 1.4.363 (Adreno
(TM) 830v1 (0x44050001)), turnip Mesa driver)`. Vulkan's GPU process held
`/dev/kgsl-3d0` open twice, Zink's once.

Then in the app's own session, with the change installed, watched in a
browser on a computer (`doc/linux-browser.md`): the session's Chromium had
`--use-angle=vulkan` on its command line and its GPU process the Adreno open.
YouTube's `f` key made its window full screen (Xfwm4: `FULLSCREEN`, 1920x1080
at 0,0), and *Stats for nerds* counted 1,085 frames of the 60 fps video (AV1,
1280x720) in 18 s, none dropped: 60 a second. On Zink, in theater mode, it had
painted 43.6 a second of the same video.

A trap for whoever measures this again: an Xfwm4 of your own on a second
Xvnc, started without the session's `XDG_CONFIG_DIRS=/usr/local/etc/xdg:/etc/xdg`,
starts an xfconfd that saves XFCE's stock window manager settings into
`/root`'s own (Ubuntu's theme, compositing on). theme.py, which puts those
right, runs once per guest, so nothing undid it: the desktop's own Xfwm4 then
crashed as it started (`malloc(): corrupted top size`), and the panel and the
desktop, finding no window manager, did not come up either, until the settings
were put back by theme.py's rule. Give that Xfwm4 the session's
`XDG_CONFIG_DIRS`, or a `HOME` of its own.

## Costs

The drivers are in the APK's assets, in both the launcher and LinuxOnDroid:
Turnip 15 MB (3.5 MB compressed), RADV 20 MB (6.0 MB compressed). A phone gets
only its own copied into the guest, once per `DRIVER_VERSION`. The probe is one
short process per session start.

VirGL adds 0.92 MB to the APK, compressed: the server for arm64 (1.66 MB,
0.47 MB compressed) and x86_64 (1.33 MB, 0.44 MB), and the GL probe (67 KB,
5 KB). A Play install of an arm64 phone gets the arm64 server alone. Running,
it is one more child process of the app while a Linux session is up, with a
GL context only while a guest app draws.

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
- **VirGL on a Mali or a PowerVR.** None was at hand: VirGL ran on an Adreno
  512 (the Redmi Note 7, the whole chain) and, its check alone from adb's
  shell, on the S25's Adreno 830. Vendor GLES drivers differ, and the Adreno
  512's refused a shader until patch 0005: on any other GPU the app's check
  and the guest's probe are what stand between a refusing driver and the
  user's apps, and a shader refused after both have said yes ends that app's
  GL connection, not the session.
- **VirGL in the session for apps in windows (`:2`), Chromium on VirGL, a
  core context's GL version, and long sessions.** Only the desktop's session
  was run; Chromium keeps `--disable-gpu` there.

## What is next

- The copy's ceiling: MIT-SHM by memfd (the way `X11.java` already reads
  windows) instead of XPutImage would take a copy off every frame.
- Chromium uses the GPU since feature level 19: its wrapper (`dex-chromium`,
  written by `browser_wrappers` in linux-setup.sh) leaves out `--disable-gpu`
  when the session's probe passed. Measured on the S24 at 1600x900, two runs
  each (2026-11-12): CSS animations 12–13 → 38 fps, WebGL none → 45–50 fps,
  scrolling (~48 fps) and canvas 2D unchanged, the same CPU. ANGLE picked Zink
  on RADV by itself.
  On the S25 Ultra (Adreno 830, Turnip), through the wrapper, desktop on 6 of
  8 cores, one run off and two on (2026-11-13): CSS animations 17 → 43–44 fps,
  WebGL none → 44–50 fps, scrolling the same (47–50 fps) on about half the CPU
  (0.96 → 0.55 cores). But canvas 2D 58 → 45–47 fps, a plain page's scroll
  (Wikipedia) 60 → 47 fps, 1080p60 VP9 video 11% → 27% of frames dropped,
  and pages load about 0.3 s later. The GPU path topped out near 45–50 fps,
  and this CPU gets past that without it. Kept on all the same, the user's
  call: the CPU it frees is worth more than the frames it costs. That ceiling
  was put down to the copy above; it was Zink's: since 2026-11-20 Chromium on
  an Adreno draws on Vulkan and reaches 60 ("Chromium", under "Measured").
  The window session (`:2`) draws on the GPU too (the same wrapper; its GPU
  process loads Turnip). Eight minutes of CSS and WebGL held steady with no
  crash. Firefox (software WebRender) and VS Code (`--disable-gpu`) still start
  and run; they are unchanged and unmeasured.
- Chromium on Vulkan in the window session (`:2`): linux-win.sh writes the
  same file and gives its apps the same variables, but Chromium there has only
  been run on Zink. On an Xclipse, Chromium on Vulkan (RADV) is unmeasured.
- A display path with DRI3 would let finished frames skip the CPU entirely.
