package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything the app needs to know about its self-hosted Docker engine.
 *
 * The Linux window ({@link Linux}) runs its Ubuntu under proot, which is a
 * ptrace fake-chroot: it hands a process a different filesystem view and
 * nothing else. Docker needs the other things — PID and mount namespaces,
 * cgroup controllers, a real uid 0 — and this device's kernel does not have
 * them to give. Measured on the phone, from {@code /proc/config.gz}:
 *
 * <pre>
 *   # CONFIG_PID_NS is not set
 *   # CONFIG_USER_NS is not set
 *   # CONFIG_CGROUP_PIDS is not set
 *   # CONFIG_CGROUP_DEVICE is not set
 * </pre>
 *
 * runc cannot create a container on that kernel even with root, so no amount
 * of work inside the Linux guest would ever get there. The platform's own
 * virtual machines are closed too: {@code vm info} reports "Only protected VMs
 * are supported / /dev/kvm does not exist / Available OS list: [microdroid]",
 * which is a Qualcomm-Gunyah device-class limit rather than a permission we
 * could ask for. What is left is a virtual machine we bring ourselves, so this
 * feature ships QEMU in the APK's native-lib dir and boots Alpine inside it
 * with TCG — no KVM, no root, no daemon, no PC.
 *
 * That makes Docker a SIBLING of the Linux window, not a tenant of it: the two
 * share nothing but the app's loopback, where {@link #enginePort} publishes
 * dockerd. See {@code openandroiddex-docker/README.md}.
 *
 * This class is the shared vocabulary — where things live, what to download,
 * and how to read the VM's state off disk. {@link DockerService} does the work;
 * {@link DockerActivity} shows it.
 */
final class Docker {

    private Docker() {}

    /**
     * Bumped on any change to the QEMU build, the guest init script, or the
     * pinned Alpine artifacts. A bump throws the VM's disk away and installs a
     * clean one, so it costs the user every container and image they have.
     *
     * The counterpart to {@link Linux#PAYLOAD_VERSION}, and the same warning
     * applies: this is the expensive number. There is deliberately no feature
     * level beside it, because nothing this app installs lives in the guest —
     * everything past first boot is the user's own {@code docker pull}.
     */
    static final int PAYLOAD_VERSION = 1;

    // ── the Alpine pin ────────────────────────────────────────────────────

    /**
     * Pinned to a VERSIONED directory ({@code netboot-3.22.5/}), not the
     * rolling {@code netboot/} one beside it. The rolling path is overwritten
     * in place on every point release, which would make the checksums below
     * expire without warning the day Alpine ships 3.22.6.
     */
    private static final String ALPINE_BASE =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/";
    private static final String ALPINE_NETBOOT = ALPINE_BASE + "netboot-3.22.5/";

    /**
     * The four downloads, as {@code {local name, url, sha256}}.
     *
     * All four are aarch64 whatever the phone is: only one guest architecture
     * is built (see {@code qemu/build-qemu.sh}), because with no KVM anywhere
     * an x86_64 host emulates just as hard as an arm64 one, and one guest
     * means one thing to verify.
     *
     * The kernel is taken as a plain vmlinuz and the modules as the matching
     * modloop squashfs, rather than by installing Alpine's {@code linux-virt}
     * package in the guest. That is deliberate: a package would track Alpine's
     * current kernel and stop matching the vmlinuz pinned here.
     */
    private static final String[][] ARTIFACTS = {
            {"vmlinuz", ALPINE_NETBOOT + "vmlinuz-virt",
                    "f270bfa4324e37f0a28662909b0450c802c8279143f353cbc7fe250cdfb733a8"},
            {"initramfs", ALPINE_NETBOOT + "initramfs-virt",
                    "508de7f561b94aac0b569611574502e4528eb21230318badac9626b7f1791bf4"},
            {"modloop.img", ALPINE_NETBOOT + "modloop-virt",
                    "65a50040ab5129e6c1875353a8d8d91e695eb7f5fc2ba5a36809bd21539ab810"},
            {"minirootfs.tar.gz", ALPINE_BASE + "alpine-minirootfs-3.22.5-aarch64.tar.gz",
                    "3fbc6285032ed46821b511292633d7b2a6306a2e254f590e92bdafff56cf2f70"},
    };

    static String[][] artifacts() {
        return ARTIFACTS;
    }

    // ── VM shape ──────────────────────────────────────────────────────────

    /**
     * The virtual disk, sparse. 32 GiB is a size, not an allocation: the file
     * is created with setLength and /data is f2fs or ext4, so it costs what
     * the guest actually writes. Big enough that nobody has to think about
     * image pulls, and resizing later would mean an offline resize2fs the app
     * has no way to run.
     */
    static final long DISK_BYTES = 32L << 30;

    /**
     * Guest RAM in MiB, held for the VM's whole life.
     *
     * 1 GiB, not the 2 that seems obvious on an 11 GiB phone. Measured while
     * installing: {@code free -m} showed 192 MB genuinely free with everything
     * else in cache, and a QEMU whose RSS climbs toward 2 GB is the fattest
     * thing on the device — Android's low-memory killer took the whole :docker
     * process at 88%, right at the heaviest moment of the package install, and
     * the window sat on "starting" because the kill left rt.pid behind.
     * dockerd itself needs about 150 MB.
     */
    static final int DEFAULT_MEM_MB = 1024;

    /**
     * Guest vCPUs. Four rather than all eight: TCG runs one host thread per
     * vCPU and they contend on the translation-block lock, so past a handful
     * more vCPUs make the guest slower, not faster — and the phone still has a
     * desktop shell to draw.
     */
    static final int DEFAULT_CPUS = 4;

    // ── locations (all app-owned) ─────────────────────────────────────────

    /** Root of the whole feature in the app's private storage. */
    static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "docker");
    }

    /** The extracted native libraries — the one dir an app may exec from. */
    static File qemuBin(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libqemu.so");
    }

    static File rtScript(Context ctx) {
        return new File(root(ctx), "docker-rt.sh");
    }

    /** Alpine's stock initramfs with our own /init appended. See {@link Cpio}. */
    static File bootImage(Context ctx) {
        return new File(root(ctx), "boot.img");
    }

    /** The VM's only writable disk: its whole root filesystem. */
    static File rootImage(Context ctx) {
        return new File(root(ctx), "root.img");
    }

    static File artifact(Context ctx, String name) {
        return new File(root(ctx), name);
    }

    /** Everything the guest has ever printed to its serial console. */
    static File consoleLog(Context ctx) {
        return new File(root(ctx), "console.log");
    }

    /** The live console, for the terminal in the window. */
    static File consoleSocket(Context ctx) {
        return new File(root(ctx), "console.sock");
    }

    /**
     * The ports docker-rt.sh forwards out of the VM onto the phone.
     *
     * Read from the copy of the script that is actually on disk rather than
     * hard-coded here, because that copy is what the running machine was
     * started from: a build that changed the list does not make the VM someone
     * booted yesterday agree with it, and a link offered for a port nothing
     * forwards is worse than no link.
     */
    static java.util.List<Integer> forwardedPorts(Context ctx) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^FWD_PORTS=\"([^\"]*)\"")
                .matcher(readFile(rtScript(ctx)));
        if (!m.find()) return out;
        for (String p : m.group(1).trim().split("\\s+")) {
            try {
                out.add(Integer.parseInt(p));
            } catch (Exception ignored) {
                // a stray token in the list is not worth failing a window over
            }
        }
        return out;
    }

    static File pidFile(Context ctx) {
        return new File(root(ctx), "rt.pid");
    }

    static File exitFile(Context ctx) {
        return new File(root(ctx), "rt.exit");
    }

    // ── the engine port ───────────────────────────────────────────────────

    /**
     * The loopback port QEMU forwards to dockerd, chosen once and remembered.
     *
     * Random rather than Docker's usual 2375, for two reasons. It cannot
     * collide with anything else on the phone, and 2375 is the number a
     * hostile app would try first — the Docker API has no authentication of
     * its own, so anything that reaches this port is root in the VM. That is
     * a real exposure and the random port only narrows it; it is why the
     * forward is bound to 127.0.0.1 and never to the phone's real address.
     */
    static int enginePort(Context ctx) {
        int chosen = enginePortIfSet(ctx);
        if (chosen != 0) return chosen;
        File dir = root(ctx);
        // The directory is not a given: a first run has not made it yet and a
        // reset has just deleted it, and a write into a missing directory is
        // how a port gets "chosen" into thin air.
        dir.mkdirs();
        int port = 20000 + new java.security.SecureRandom().nextInt(30000);
        writeFile(new File(dir, "port"), String.valueOf(port));
        return port;
    }

    /**
     * The port that HAS been chosen, or 0 when there is not one yet.
     *
     * For readers — the window above all. {@link #enginePort} mints a port as
     * a side effect of being asked, which is right for the service that is
     * about to boot QEMU with it and wrong for everyone else. The window asked
     * once in onCreate, provisioning then deleted the whole directory and
     * chose a different number for the VM it started, and the window spent the
     * rest of its life polling a port nothing was listening on while the
     * engine sat there answering on another.
     */
    static int enginePortIfSet(Context ctx) {
        try {
            int p = Integer.parseInt(readFile(new File(root(ctx), "port")).trim());
            if (p > 1024 && p < 65536) return p;
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ── state ─────────────────────────────────────────────────────────────

    /**
     * What the window renders. {@code phase} is one of pushing / install /
     * boot / ready / error, and comes from two places: the app writes it while
     * it is downloading and preparing, and the guest's own init takes over
     * once the VM is running (see {@code guest-init.sh}'s @@OADX lines).
     */
    static final class Status {
        int version;
        String phase = "";
        int pct;
        String msg = "";
        boolean running;
        /**
         * rt.pid names a process that is not there any more — the VM was
         * KILLED rather than stopped.
         *
         * Worth a field of its own because it is the difference between two
         * screens that look identical from the state file: docker-rt.sh
         * removes rt.pid on its way out, so a file left behind next to a dead
         * process means nobody got to run that line. On this phone that is
         * almost always Android's low-memory killer taking the :docker
         * process, and saying so beats a progress bar frozen at 88%.
         */
        boolean died;
    }

    /** The VM's pid, or 0 if there is no rt.pid to read. */
    static int pid(Context ctx) {
        try {
            return Integer.parseInt(readFile(pidFile(ctx)).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Is that pid still a live process?
     *
     * An app cannot LIST /proc, but it can stat one entry belonging to its own
     * uid, which is all this needs. Anything unexpected counts as alive: a
     * false "it died" would stop a perfectly good VM being shown.
     */
    private static boolean alive(int pid) {
        if (pid <= 0) return false;
        try {
            return new File("/proc/" + pid).exists();
        } catch (Exception e) {
            return true;
        }
    }

    static Status readStatus(Context ctx) {
        Status st = new Status();
        for (String line : readFile(new File(root(ctx), "state.env")).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "VERSION":
                    st.version = parseInt(v);
                    break;
                case "PHASE":
                    st.phase = v;
                    break;
                case "PCT":
                    st.pct = parseInt(v);
                    break;
                case "MSG":
                    st.msg = v;
                    break;
                default:
                    break;
            }
        }
        int pid = pid(ctx);
        st.running = alive(pid);
        st.died = pid > 0 && !st.running;
        return st;
    }

    /**
     * Is there anything to do before the window can show a VM?
     *
     * Read off plain files so the caller can ask on any thread without paying
     * for a service start — same reasoning as {@link Linux}: starting a
     * foreground service is the expensive and deadline-bound part.
     */
    static boolean needsProvision(Context ctx) {
        if (readStatus(ctx).version != PAYLOAD_VERSION) return true;
        // Files, not the phase. The phase after the first boot belongs to the
        // GUEST (install/boot/ready come off its console), and a VM that is
        // mid-install is not a VM that needs provisioning again — asking about
        // the artifacts is the only question this method should be answering.
        if (!bootImage(ctx).isFile()) return true;
        if (!rootImage(ctx).isFile() || rootImage(ctx).length() != DISK_BYTES) return true;
        if (!rtScript(ctx).isFile()) return true;
        for (String[] a : ARTIFACTS) {
            if (!artifact(ctx, a[0]).isFile()) return true;
        }
        return false;
    }

    // ── support ───────────────────────────────────────────────────────────

    /**
     * Can this device run the VM at all?
     *
     * API 28 is the floor because QEMU is linked against a bionic that has
     * iconv_open, which arrived there; see the qemu Dockerfile. The ABI check
     * is about which libqemu.so the APK carries, not about the guest — the
     * guest is aarch64 either way.
     */
    static boolean abiSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        String abi = primaryAbi();
        return "arm64-v8a".equals(abi) || "x86_64".equals(abi);
    }

    /**
     * True on the ABI where the guest is emulated rather than merely
     * virtualised-in-software at the same architecture.
     *
     * There is no KVM anywhere, so arm64 is TCG too — but arm64-on-arm64 TCG
     * still lets the translator keep the guest's own instruction semantics,
     * while x86_64 is emulating a foreign ISA on top of that. The window says
     * so rather than letting someone conclude the feature is broken.
     */
    static boolean isForeignArch() {
        return !"arm64-v8a".equals(primaryAbi());
    }

    private static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis[0] : "";
    }

    // ── the environment docker-rt.sh runs under ───────────────────────────

    static Map<String, String> scriptEnv(Context ctx) {
        Map<String, String> env = new HashMap<>();
        env.put("DOCKER_ROOT", root(ctx).getAbsolutePath());
        env.put("DOCKER_QEMU", qemuBin(ctx).getAbsolutePath());
        return env;
    }

    // ── small helpers, shared with the service ────────────────────────────

    static String readFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 13];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Atomic file write (tmp + rename) so a reader never sees half of it. */
    static void writeFile(File f, String content) {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
