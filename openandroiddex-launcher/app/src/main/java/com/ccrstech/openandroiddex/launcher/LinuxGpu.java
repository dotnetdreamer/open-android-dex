package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The phone's GPU for Linux apps, on Adreno (Snapdragon) phones and on Exynos
 * phones with Samsung's Xclipse GPU. See doc/linux-gpu.md.
 *
 * <p>Linux draws GL on the GPU through two pieces of Mesa: a Vulkan driver
 * for the phone's GPU, which talks to it through the same kernel device the
 * phone's own driver uses, and Zink, GL on top of Vulkan, which the guest's
 * Ubuntu Mesa already has. The Vulkan driver is ours, in the APK's assets and
 * copied into the guest here, because Ubuntu's are built for desktop Linux:
 * <ul>
 * <li>{@code turnip}: Mesa's Turnip for Adreno, built for Android's KGSL
 * interface (/dev/kgsl-3d0) in openandroiddex-linux/turnip.</li>
 * <li>{@code xclipse}: Mesa's RADV as the radv-xclipse project ported it to
 * Xclipse, whose kernel driver (Samsung's sgpu) speaks AMD's interface on
 * /dev/dri/renderD128; built for Linux in openandroiddex-linux/radv-xclipse.</li>
 * </ul>
 *
 * <p>No process of ours runs for it, and nothing is decided out here but
 * "this phone has one of those GPUs and its driver is in the guest": the
 * scripts get {@code LINUX_GPU=<driver>}, and inside the guest a probe that
 * really opens the GPU says whether to point GL at it. That order matters
 * because Mesa does not fall back to the CPU by itself: an app told to use
 * Zink with no working GPU under it gets no GL at all, which is worse than
 * slow GL.
 */
final class LinuxGpu {

    private LinuxGpu() {
    }

    /**
     * What is in the guest, so an update that brings a new driver replaces
     * the old one and every other start copies nothing. Bump it whenever a
     * build.sh in openandroiddex-linux/turnip, radv-xclipse or gpu-probe puts
     * new files in assets/linux/gpu.
     */
    static final String DRIVER_VERSION = "mesa-26.3.0-3/radv-xclipse-1.8.0-1";

    /** The drivers, by the name the scripts get, and their file in assets/linux/gpu. */
    private static final String[][] DRIVERS = {
            {"turnip", "libvulkan_freedreno.so"},
            {"xclipse", "libvulkan_xclipse.so"},
    };

    /** Inside the guest, all ours: nothing Ubuntu ships lives under /usr/local/lib/openandroiddex. */
    private static final String GUEST_DIR = "usr/local/lib/openandroiddex/gpu";
    /** Where any Vulkan app in the guest finds the driver without being told: oadx-<driver>.json. */
    private static final String GUEST_ICD_DIR = "usr/local/share/vulkan/icd.d";

    // ── the switch ──

    /**
     * A file, for the reason every Linux setting is one: the launcher writes
     * it and the {@code :linux} process reads it, and cross-process
     * SharedPreferences are not reliable. Absent means on.
     */
    static File switchFile(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-gpu");
    }

    static boolean enabled(Context ctx) {
        return !"off".equals(Linux.readFile(switchFile(ctx)).trim());
    }

    static void setEnabled(Context ctx, boolean on) {
        File f = switchFile(ctx);
        if (on) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("off".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            DexLog.warn("linux", "gpu: could not save the switch: " + e);
        }
    }

    /**
     * Which of our drivers this phone's GPU takes, or null for none: an
     * Adreno, with the KGSL device that is Qualcomm's alone, takes Turnip; an
     * Xclipse takes RADV. Mali and PowerVR phones, and the Exynos ones with a
     * Mali, stay on the CPU exactly as before. Both drivers are arm64 builds.
     */
    static String driver() {
        if (!"arm64-v8a".equals(Linux.primaryAbi())) return null;
        if (new File("/dev/kgsl-3d0").exists()) return "turnip";
        if (new File("/dev/dri/renderD128").exists() && isXclipse()) return "xclipse";
        return null;
    }

    static boolean available() {
        return driver() != null;
    }

    /**
     * Samsung's sgpu kernel driver runs every Xclipse, and every phone with
     * one ships Samsung's libdrm for it beside its Vulkan driver; a Mali
     * Exynos has neither. Either sign will do, since sysfs may be closed to an
     * app. The probe in the guest still has the last word.
     */
    private static boolean isXclipse() {
        try {
            File driver = new File("/sys/class/drm/renderD128/device/driver").getCanonicalFile();
            if ("sgpu".equals(driver.getName())) return true;
        } catch (Exception ignored) {
            // sysfs closed to us: the library is the other sign
        }
        return new File("/vendor/lib64/libdrm_sgpu.so").exists();
    }

    // ── into the runtime ──

    /**
     * Put {@code LINUX_GPU=<driver>} into a runtime script's environment when
     * the GPU may be tried, copying the driver into the guest first if this
     * build's is not there yet. The copy is the only slow part, and it only
     * happens once per driver version. io thread. Never throws; any failure
     * leaves the env alone and the guest on the CPU.
     */
    static void env(Context ctx, Map<String, String> env) {
        try {
            if (!enabled(ctx)) return;
            String driver = driver();
            if (driver == null || !stage(ctx, driver)) return;
            env.put("LINUX_GPU", driver);
        } catch (Throwable t) {
            DexLog.warn("linux", "gpu: not used: " + t);
        }
    }

    /**
     * Copy the guest's verdict, "gpu: Adreno (TM) 830v1" or "gpu: not used --
     * ...", from a session log into the app's own log, which is where a bug
     * report finds it: the session log is in private storage. Only for a start
     * that was given {@code LINUX_GPU}. A thread of its own that gives up after
     * half a minute; the probe answers in the first second or two of a start,
     * and nothing waits on this.
     */
    static void reportVerdict(Map<String, String> env, File sessionLog) {
        if (env.get("LINUX_GPU") == null) return;
        Thread t = new Thread(() -> {
            long until = android.os.SystemClock.elapsedRealtime() + 30_000;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                for (String line : Linux.readFile(sessionLog).split("\n")) {
                    if (line.startsWith("gpu: ")) {
                        DexLog.step("linux", line.trim());
                        return;
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            DexLog.warn("linux", "gpu: the session said nothing about the GPU in 30 s");
        }, "linux-gpu-verdict");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The driver, the probe and the ICD files in the guest, at this build's
     * version. The stamp names the driver too, so a guest that finds itself
     * on a phone with the other kind of GPU (a backup brought over) gets the
     * right one, and the other's files go.
     */
    private static synchronized boolean stage(Context ctx, String driver) {
        File rootfs = new File(Linux.root(ctx), "rootfs");
        if (!rootfs.isDirectory()) return false;
        File dir = new File(rootfs, GUEST_DIR);
        File stamp = new File(dir, "version");
        String want = DRIVER_VERSION + " " + driver;
        if (want.equals(Linux.readFile(stamp).trim())) return true;
        try {
            mkdirsOpen(rootfs, dir);
            File icdDir = new File(rootfs, GUEST_ICD_DIR);
            mkdirsOpen(rootfs, icdDir);
            String lib = null;
            for (String[] d : DRIVERS) {
                if (d[0].equals(driver)) {
                    lib = d[1];
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, d[1]).delete();
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, d[0] + "_icd.json").delete();
                    //noinspection ResultOfMethodCallIgnored
                    new File(icdDir, "oadx-" + d[0] + ".json").delete();
                }
            }
            if (lib == null) return false;
            copyAsset(ctx, "linux/gpu/" + lib, new File(dir, lib), false);
            copyAsset(ctx, "linux/gpu/oadx-vkprobe", new File(dir, "oadx-vkprobe"), true);
            // Our own ICD file, not Mesa's: that one names /usr/local/lib,
            // where nothing of ours is.
            String icd = "{\"file_format_version\":\"1.0.1\",\"ICD\":{\"api_version\":\"1.4.0\","
                    + "\"library_path\":\"/" + GUEST_DIR + "/" + lib + "\"}}\n";
            writeAtomically(new File(dir, driver + "_icd.json"), icd);
            writeAtomically(new File(icdDir, "oadx-" + driver + ".json"), icd);
            // Last, so a copy cut short is redone at the next start.
            writeAtomically(stamp, want + "\n");
            DexLog.step("linux", "gpu: " + driver + " driver " + DRIVER_VERSION + " staged into the guest");
            return true;
        } catch (Exception e) {
            DexLog.warn("linux", "gpu: could not stage the driver: " + e);
            return false;
        }
    }

    /**
     * Make a directory, and every one between it and the rootfs, open to all,
     * as /usr/local is in any Linux. This app's umask makes new directories
     * owner-only, which a user other than root in the guest could not enter.
     */
    private static void mkdirsOpen(File rootfs, File dir) throws java.io.IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new java.io.IOException("cannot create " + dir);
        for (File d = dir; d != null && !d.equals(rootfs); d = d.getParentFile()) {
            //noinspection ResultOfMethodCallIgnored
            d.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            d.setExecutable(true, false);
        }
    }

    private static void copyAsset(Context ctx, String asset, File to, boolean executable) throws Exception {
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = ctx.getAssets().open(asset);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        // Read for everyone, as a library in /usr/local/lib is anywhere; the
        // probe is run by the guest's shell, under proot's loader.
        //noinspection ResultOfMethodCallIgnored
        tmp.setReadable(true, false);
        if (executable) {
            //noinspection ResultOfMethodCallIgnored
            tmp.setExecutable(true, false);
        }
        if (!tmp.renameTo(to)) throw new java.io.IOException("cannot replace " + to);
    }

    private static void writeAtomically(File f, String content) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.setReadable(true, false);
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot replace " + f);
    }
}
