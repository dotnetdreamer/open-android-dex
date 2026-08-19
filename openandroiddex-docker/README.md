# openandroiddex-docker

A real Docker engine on the DeX desktop — **no root anywhere**, and no PC.
Alpine runs inside QEMU as the **launcher app's own uid**, in the app's private
storage, and the launcher talks to `dockerd` over one port QEMU forwards onto
the phone's loopback.

This is a **sibling of the Linux window, not a part of it**. The two share
nothing but that loopback.

## Why a whole virtual machine

The obvious plan — install Docker inside the Ubuntu guest the Linux window
already runs — cannot work, and neither can anything else short of a VM. Three
measurements, all taken on the S25 Ultra (SM-S938B, Android 16):

**1. The kernel has no namespaces to give.** From `/proc/config.gz`:

```
# CONFIG_PID_NS is not set
# CONFIG_USER_NS is not set
# CONFIG_CGROUP_PIDS is not set
# CONFIG_CGROUP_DEVICE is not set
```

`NET_NS`, `UTS_NS`, `OVERLAY_FS`, `VETH`, `NF_NAT` and `BRIDGE` *are* set, but
runc needs PID namespaces at minimum. Confirmed from the other side too:

```
unshare -U → Invalid argument          (USER_NS compiled out)
unshare -p → Operation not permitted
unshare -m → Operation not permitted
```

So `dockerd`/`containerd`/`runc` fail on this kernel **even with root**, and
rootless podman is out for the same reason.

**2. proot was never going to help.** It is a ptrace fake-chroot: it gives a
process a different filesystem view and nothing else — no namespaces, no
cgroups, no real uid 0. Putting Docker "inside Linux" would have been asking
the same kernel the same impossible question from one directory further in.

**3. The platform's own VMs are closed.** AVF is present — the
`com.android.virt` APEX, the `android.software.virtualization_framework`
feature, even Google's Linux Terminal preinstalled (its launcher activity
disabled) — and `/apex/com.android.virt/bin/vm` is group `shell`, so it can be
driven over adb. But it answers:

```
Only protected VMs are supported.
Hypervisor version not set.
/dev/kvm does not exist.
Available OS list: ["microdroid"]
Debug policy: DebugPolicy { log: false, ramdump: false, adb: false }
```

`/dev/gunyah` exists (Qualcomm's hypervisor, mode 0666 but SELinux-denied to
apps) and `/dev/kvm` does not. Non-protected — i.e. arbitrary-kernel — VMs are
unsupported on this device class, so Google's Debian guest cannot boot here
either. Worth remembering for a Pixel: `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE` are both `prot=signature|development`, which makes
them `pm grant`-able over the ADB channel this project already has.

What is left is a virtual machine we bring ourselves, in userspace, with no
acceleration: **QEMU with TCG**. It is the only path to a real `dockerd` on
this phone, and it is slow in exactly the way software CPU emulation is.

## What ships

| | what | where |
|---|---|---|
| `qemu/` | the cross-build for `libqemu.so` (aarch64-softmmu + deps, static, 16 KB aligned) | committed to the launcher's `jniLibs` |
| `guest-init.sh` | the guest's `/init` — installs Alpine on first boot, mounts it on every boot after | APK asset |
| `docker-rt.sh` | launches QEMU on Android | APK asset |
| Alpine 3.22.5 | kernel, initramfs, modloop, minirootfs — pinned by sha256 | downloaded on first run |

`build-launcher-apk.mjs` copies the two scripts into the launcher's assets on
every build; `qemu/build.sh` rebuilds the binaries.

Both ABIs carry a **aarch64** QEMU: one guest architecture means one guest to
build and verify, and with no KVM anywhere an x86_64 host is emulating just as
hard as an arm64 one. The x86_64 artifact exists so the feature can be
exercised on the emulator, not because it is fast there.

## How the guest gets built

Nothing is hosted by this project. Everything comes from Alpine's own CDN,
pinned to a **versioned** directory (`netboot-3.22.5/`, not the rolling
`netboot/` beside it, which is overwritten on every point release and would
expire the checksums without warning).

1. `DockerService` downloads and sha256-verifies `vmlinuz-virt`,
   `initramfs-virt`, `modloop-virt` and the minirootfs — about 39 MB.
2. `Cpio` appends a **three-file cpio.gz** carrying our `/init` to Alpine's
   initramfs. The kernel accepts an initramfs that is several independently
   compressed cpio archives concatenated and unpacks them in order, later
   entries winning — so the app never parses or repacks Alpine's 9 MB archive.
   It writes a few hundred bytes and our `/init` lands on top of theirs with
   every binary, module and `modules.dep` they shipped still in place.
3. The disk is a **sparse** 32 GiB file (`setLength`, no allocation). Android
   has no `mke2fs` and an app has no loop device, so the guest formats it
   itself on first boot — one ordinary `mkfs.ext4`, as root, inside the VM.
4. First boot: `/init` untars the minirootfs into RAM, uses its `apk` to fetch
   `e2fsprogs`, formats `/dev/vda`, and installs the real system onto that disk
   with `apk --root /mnt --initdb`. Then `switch_root`.
5. Every later boot: `/init` sees its marker on `/dev/vda`, mounts it, mounts
   the modloop squashfs at `/.modloop`, and `switch_root`s. Seconds, not
   minutes.

The kernel modules are taken from the pinned modloop rather than by installing
Alpine's `linux-virt` package, deliberately: a package would track Alpine's
current kernel and stop matching the vmlinuz the app has pinned.

## Three disks, one port, one console

```
vda  root.img            32 GiB sparse ext4, rw   — the whole guest system
vdb  minirootfs.tar.gz   raw, ro                  — read by tar as a stream
vdc  modloop.img         squashfs, ro             — /lib/modules for this kernel
net  user (slirp)        hostfwd 127.0.0.1:<port> → :2375
con  chardev socket + logfile
```

There is no tap device without root, so slirp's userspace NAT is the only way
out, and its `hostfwd` is what puts `dockerd` on the app's loopback.

The console is one chardev doing two jobs. Its **logfile** captures the guest
from the first kernel line whether or not anything is attached — that is what
`DockerService` tails for `@@OADX phase=… pct=… msg=…` progress markers, which
are the only channel that exists while the guest is still installing. Its
**socket** is the interactive side the window's Console tab connects to, and it
may come and go without costing a byte of the log.

## Things measured the hard way

- **PID 1 inherits nothing.** The kernel hands `/init` an empty environment;
  without a `PATH` every applet is "not found" and the script marches all the
  way to `no-root-disk` having silently failed to `mkdir`, `mount` and `sleep`.
- **Alpine's initramfs has busybox but not its applet symlinks.** The whole
  archive is `bin/busybox`, `bin/sh`, `bin/kmod`, `sbin/apk`, `sbin/modprobe`
  and `sbin/nlplug-findfs`. `busybox --install -s` is what turns it into a
  userland — Alpine's own init does that on its first two lines.
- **slirp's DNS proxy does not work on Android.** 10.0.2.3 forwards to whatever
  the host's `/etc/resolv.conf` lists, and Android has no such file — DNS lives
  in netd. Routing and TCP were fine while every `apk` fetch failed with
  "temporary error (try again later)". The guest lists 10.0.2.3, 1.1.1.1 and
  8.8.8.8; musl queries all of them in parallel, so the dead one costs nothing.
- **udhcpc would undo that**, rewriting `/etc/resolv.conf` from the lease. The
  installed system configures eth0 statically — slirp's addresses never change
  anyway.
- **A unix socket cannot be bound under `/data/local/tmp`.** SELinux refuses
  `shell_data_file:sock_file create`, so QEMU exits with "Failed to bind socket
  … Permission denied" when the same command is tried over adb. The app's own
  `filesDir` is `app_data_file`, where it is allowed — but `docker-rt.sh`
  falls back to a plain `-serial file:` if the bind fails anyway, because a VM
  with a read-only console beats no VM.
- `-serial file:` is what to use when reproducing any of this over adb.

## Speed, honestly

TCG means the guest CPU is software. First boot installs Alpine and Docker over
the network and takes minutes; later boots take tens of seconds. Containers run
at a fraction of native. The engine is real, `docker compose` works, and
nothing about that changes the fact that this is a phone emulating a CPU.

Only arm64 images run — the guest is aarch64.

## Security

`dockerd` listens on `tcp://0.0.0.0:2375` **inside the VM**, which slirp
exposes only through one forward bound to the phone's `127.0.0.1`. The port is
random per install and remembered, rather than Docker's usual 2375: the Docker
API has no authentication of its own, so anything that reaches that port is
root in the VM, and 2375 is the number a hostile app would try first. That
narrows the exposure; it does not remove it. The forward is never bound to the
phone's real address.

Root in the VM is not root on the phone. The VM is a QEMU process running as
the launcher's uid, its disk is a file in the app's private storage, and the
guest has no path to Android's filesystem at all.
