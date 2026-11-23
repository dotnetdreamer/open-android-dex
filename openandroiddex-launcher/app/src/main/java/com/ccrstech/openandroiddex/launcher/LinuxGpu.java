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
 * phones with Samsung's Xclipse GPU, and through Android's own driver (VirGL)
 * on every other 64-bit phone. See doc/linux-gpu.md.
 *
 * <p>VirGL, on a Mali, a PowerVR or an Adreno older than the 600 series (no
 * Mesa driver of ours runs those): virglrenderer's server, {@code
 * libvirglserver.so} from openandroiddex-linux/virgl, runs on the Android side
 * as a process of this app, on the phone's own GLES driver, and listens on the
 * guest's /tmp/.virgl_test; Mesa's "virpipe" in the guest sends GL to it. It is
 * checked once per phone build and server version ({@link #virglCheck}), and
 * the guest's own probe (oadx-glprobe) still has the last word.
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
    static final String DRIVER_VERSION = "mesa-26.3.0-3/radv-xclipse-1.8.0-1/virgl-1.3.0-2";

    /**
     * The drivers, by the name the scripts get, and their file in
     * assets/linux/gpu. VirGL has none in the guest: Mesa's own virpipe
     * talks to the server, which is a jniLib ({@link #VIRGL_SERVER}).
     */
    private static final String[][] DRIVERS = {
            {"turnip", "libvulkan_freedreno.so"},
            {"xclipse", "libvulkan_xclipse.so"},
            {"virgl", null},
    };

    /** The VirGL server, in nativeLibraryDir: the one place an app may exec from. */
    static final String VIRGL_SERVER = "libvirglserver.so";
    /** Where the guest's Mesa looks for it (VTEST_SOCKET_NAME's default), under the guest's /tmp. */
    static final String VIRGL_SOCKET = ".virgl_test";

    /** What the switch in Settings can do on this phone; the card says which. */
    enum Offer {
        /** A Mesa driver of ours runs this GPU itself: Turnip on an Adreno, RADV on an Xclipse. */
        DRIVER,
        /** Android's own GLES driver, through VirGL: a Mali, a PowerVR, an older Adreno. */
        VIRGL,
        /** A 64-bit phone where neither works (VirGL refused at its check, or not in this APK). */
        NONE,
        /** Linux itself needs a 64-bit phone. */
        NOT_64_BIT
    }

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
     * Which of our ways this phone's GPU takes, or null for none: an Adreno
     * of the 600 series or later, with the KGSL device that is Qualcomm's
     * alone, takes Turnip; an Xclipse takes RADV; any other GPU (a Mali, a
     * PowerVR, an older Adreno) takes VirGL, unless its check on this phone
     * said no. All three are arm64 builds, as Linux is.
     */
    static String driver(Context ctx) {
        if (!"arm64-v8a".equals(Linux.primaryAbi())) return null;
        if (new File("/dev/kgsl-3d0").exists() && !adrenoBeforeTurnip()) return "turnip";
        if (new File("/dev/dri/renderD128").exists() && isXclipse()) return "xclipse";
        if (virglServer(ctx).exists() && !virglRefused(ctx)) return "virgl";
        return null;
    }

    /** What the switch can do here, for the Settings card. */
    static Offer offer(Context ctx) {
        if (!"arm64-v8a".equals(Linux.primaryAbi())) return Offer.NOT_64_BIT;
        String d = driver(ctx);
        if (d == null) return Offer.NONE;
        return "virgl".equals(d) ? Offer.VIRGL : Offer.DRIVER;
    }

    private static volatile Boolean oldAdreno;

    /**
     * An Adreno older than the 600 series, which Turnip does not drive (the
     * Redmi Note 7's 512): VirGL instead. Read from KGSL's own name for the GPU
     * where an app may read it, else from Android's GL driver; unknown counts
     * as new enough, and Turnip's probe in the guest still has the last word.
     */
    static boolean adrenoBeforeTurnip() {
        Boolean b = oldAdreno;
        if (b != null) return b;
        int n = adrenoNumber(Linux.readFile(new File("/sys/class/kgsl/kgsl-3d0/gpu_model")));
        if (n < 0) n = adrenoNumber(glRenderer());
        b = n > 0 && n < 600;
        oldAdreno = b;
        return b;
    }

    /** "Adreno512v2" (KGSL) or "Adreno (TM) 512" (GL) to 512; -1 when it is no Adreno name. */
    static int adrenoNumber(String name) {
        if (name == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Adreno\\D{0,8}(\\d{3,4})").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Android's GL renderer string, from a 1x1 pbuffer context, or "" if EGL says no. Any thread with no context of its own. */
    static String glRenderer() {
        android.opengl.EGLDisplay d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
        if (d == null || d == android.opengl.EGL14.EGL_NO_DISPLAY) return "";
        int[] v = new int[2];
        if (!android.opengl.EGL14.eglInitialize(d, v, 0, v, 1)) return "";
        android.opengl.EGLContext c = android.opengl.EGL14.EGL_NO_CONTEXT;
        android.opengl.EGLSurface s = android.opengl.EGL14.EGL_NO_SURFACE;
        try {
            int[] want = {android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                    android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                    android.opengl.EGL14.EGL_NONE};
            android.opengl.EGLConfig[] cfg = new android.opengl.EGLConfig[1];
            int[] n = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(d, want, 0, cfg, 0, 1, n, 0) || n[0] < 1) return "";
            c = android.opengl.EGL14.eglCreateContext(d, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT,
                    new int[]{android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE}, 0);
            s = android.opengl.EGL14.eglCreatePbufferSurface(d, cfg[0],
                    new int[]{android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1,
                            android.opengl.EGL14.EGL_NONE}, 0);
            if (c == null || c == android.opengl.EGL14.EGL_NO_CONTEXT || s == null
                    || s == android.opengl.EGL14.EGL_NO_SURFACE || !android.opengl.EGL14.eglMakeCurrent(d, s, s, c)) {
                return "";
            }
            String r = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            return r == null ? "" : r;
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                android.opengl.EGL14.eglMakeCurrent(d, android.opengl.EGL14.EGL_NO_SURFACE,
                        android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                if (s != null && s != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(d, s);
                if (c != null && c != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(d, c);
                android.opengl.EGL14.eglTerminate(d);
            } catch (Throwable ignored) {
                // nothing to give back
            }
        }
    }

    // ── VirGL ──

    static File virglServer(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, VIRGL_SERVER);
    }

    /** The server's check on this phone: "<key>\t<yes|no>\t<renderer or why>". */
    private static File virglCheckFile(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-gpu-virgl");
    }

    /** A new Android build may bring a new GPU driver, and a new APK a new server: either asks again. */
    private static String virglKey() {
        return DRIVER_VERSION + " " + android.os.Build.FINGERPRINT;
    }

    /** The check said no for this phone build and server, so the card says so instead of offering a switch. */
    static boolean virglRefused(Context ctx) {
        String[] p = Linux.readFile(virglCheckFile(ctx)).split("\t", 3);
        return p.length >= 2 && p[0].trim().equals(virglKey()) && "no".equals(p[1]);
    }

    /**
     * Whether this phone's own GL driver can carry VirGL: the server brings
     * its renderer up once and says what it runs on ({@code --probe}, our
     * patch), the way the first guest app would. Asked once per phone build
     * and server, and remembered. io thread: it takes about a second.
     */
    static boolean virglCheck(Context ctx) {
        String[] p = Linux.readFile(virglCheckFile(ctx)).split("\t", 3);
        if (p.length >= 2 && p[0].trim().equals(virglKey())) return "yes".equals(p[1]);
        boolean ok = false;
        String said;
        try {
            Process pr = new ProcessBuilder(virglServer(ctx).getPath(), "--probe",
                    "--use-egl-surfaceless", "--use-gles").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStream in = pr.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) {
                            if (out.length() < 8192) out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {
                    // the process ended
                }
            }, "linux-gpu-virgl-check");
            reader.start();
            boolean ended = pr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!ended) pr.destroyForcibly();
            reader.join(1000);
            String text;
            synchronized (out) {
                text = out.toString();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("probe ok: renderer=\"([^\"]*)\"").matcher(text);
            ok = ended && pr.exitValue() == 0 && m.find();
            said = ok ? m.group(1) : (ended ? "exit " + pr.exitValue() + ": " : "no answer in 15 s: ")
                    + text.trim().replace('\n', ' ').replace('\t', ' ');
        } catch (Exception e) {
            said = String.valueOf(e);
        }
        if (said.length() > 300) said = said.substring(0, 300);
        try {
            writeAtomically(virglCheckFile(ctx), virglKey() + "\t" + (ok ? "yes" : "no") + "\t" + said + "\n");
        } catch (Exception e) {
            DexLog.warn("linux", "gpu: could not save the VirGL check: " + e);
        }
        DexLog.step("linux", "gpu: VirGL check " + (ok ? "passed on " : "failed: ") + said);
        return ok;
    }

    private static Process virglProcess;

    /**
     * The server, running for this app's Linux sessions (the desktop and the
     * one for apps in windows of their own share it). One process for every
     * client ({@code --no-fork --multi-clients}): a process per GL app would
     * count against Android's limit on an app's child processes, which closes
     * Linux when it is reached (ChildProcessLimit). Its GL context exists only
     * while a guest app is connected. A child of this process, so a
     * force-stop takes it too. io thread.
     */
    static synchronized boolean startVirgl(Context ctx) {
        if (virglProcess != null && virglProcess.isAlive()) return true;
        File tmp = new File(Linux.root(ctx), "tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        File socket = new File(tmp, VIRGL_SOCKET);
        //noinspection ResultOfMethodCallIgnored
        socket.delete();
        try {
            ProcessBuilder pb = new ProcessBuilder(virglServer(ctx).getPath(), "--no-fork", "--multi-clients",
                    "--use-egl-surfaceless", "--use-gles", "--socket-path", socket.getPath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(Linux.root(ctx), "virgl.log"));
            virglProcess = pb.start();
        } catch (Exception e) {
            DexLog.warn("linux", "gpu: VirGL server did not start: " + e);
            return false;
        }
        for (int i = 0; i < 30 && !socket.exists() && virglProcess.isAlive(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        boolean up = socket.exists() && virglProcess.isAlive();
        DexLog.step("linux", up ? "gpu: VirGL server listening for the guest" : "gpu: VirGL server not listening");
        return up;
    }

    /** No Linux session left to use it: the server goes, and its socket with it. Any thread. */
    static synchronized void stopVirgl(Context ctx) {
        Process p = virglProcess;
        virglProcess = null;
        if (p == null) return;
        try {
            p.destroyForcibly();
        } catch (Throwable ignored) {
            // gone already
        }
        //noinspection ResultOfMethodCallIgnored
        new File(new File(Linux.root(ctx), "tmp"), VIRGL_SOCKET).delete();
        DexLog.step("linux", "gpu: VirGL server stopped");
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
            String driver = driver(ctx);
            if (driver == null) return;
            // VirGL: the phone's driver checked once, the guest's probe staged,
            // then the server up before the session that will connect to it.
            if ("virgl".equals(driver) && !virglCheck(ctx)) return;
            if (!stage(ctx, driver)) return;
            if ("virgl".equals(driver) && !startVirgl(ctx)) return;
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
            boolean known = false;
            for (String[] d : DRIVERS) {
                if (d[0].equals(driver)) {
                    lib = d[1];
                    known = true;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    if (d[1] != null) new File(dir, d[1]).delete();
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, d[0] + "_icd.json").delete();
                    //noinspection ResultOfMethodCallIgnored
                    new File(icdDir, "oadx-" + d[0] + ".json").delete();
                }
            }
            if (!known) return false;
            if (lib == null) {
                // VirGL: nothing of ours draws in the guest, Mesa's own virpipe
                // does; the probe is what the session needs.
                copyAsset(ctx, "linux/gpu/oadx-glprobe", new File(dir, "oadx-glprobe"), true);
            } else {
                copyAsset(ctx, "linux/gpu/" + lib, new File(dir, lib), false);
                copyAsset(ctx, "linux/gpu/oadx-vkprobe", new File(dir, "oadx-vkprobe"), true);
                // Our own ICD file, not Mesa's: that one names /usr/local/lib,
                // where nothing of ours is.
                String icd = "{\"file_format_version\":\"1.0.1\",\"ICD\":{\"api_version\":\"1.4.0\","
                        + "\"library_path\":\"/" + GUEST_DIR + "/" + lib + "\"}}\n";
                writeAtomically(new File(dir, driver + "_icd.json"), icd);
                writeAtomically(new File(icdDir, "oadx-" + driver + ".json"), icd);
            }
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
