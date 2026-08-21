package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the Docker virtual machine's whole lifecycle inside this app: fetch and
 * verify Alpine's kernel, initramfs, modloop and minirootfs, build the boot
 * image, create the virtual disk, and host QEMU. Everything runs as this app's
 * uid in its own storage — no daemon, no shell, no PC, and (unlike the Linux
 * window) nothing of the guest is visible on Android's filesystem at all: the
 * whole system lives inside {@code root.img}.
 *
 * Driven by start intents, never bound: PROVISION (idempotent), START, STOP,
 * RESET. It is a foreground service because QEMU is a long-running native
 * process that Android would otherwise reap while the window sits behind
 * something else.
 *
 * Its own process for the reason spelled out on {@link LinuxService} in the
 * manifest: a foreground service that misses the platform's five-second
 * deadline dies with an uncaught exception, and in the launcher's process that
 * took the whole desktop down with it.
 */
public class DockerService extends Service {

    static final String ACTION_PROVISION = "com.ccrstech.openandroiddex.launcher.docker.PROVISION";
    static final String ACTION_START = "com.ccrstech.openandroiddex.launcher.docker.START";
    static final String ACTION_STOP = "com.ccrstech.openandroiddex.launcher.docker.STOP";
    /** Throw the VM's disk away and install a clean one. Not reversible. */
    static final String ACTION_RESET = "com.ccrstech.openandroiddex.launcher.docker.RESET";

    private static final String CHANNEL = "docker";
    private static final int NOTIF_ID = 0x2D;

    /** One provisioning run at a time, across every intent that asks for one. */
    private static final AtomicBoolean provisioning = new AtomicBoolean(false);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Kept only so the runtime process is not GC-reaped mid-life. */
    private Process runtimeProc;
    /**
     * True from the instant we spawn QEMU until we stop it. Guards the
     * double-start race: two START intents can both see rt.pid absent, because
     * the script writes it a beat after spawn, and the second QEMU then fails
     * to bind the forwarded port while rt.pid points at the loser.
     */
    private volatile boolean runtimeUp;
    private volatile int lastStartId;
    /** Tails console.log while a VM is up; see {@link #watchConsole()}. */
    private volatile Thread consoleWatcher;

    // ── entry points ──

    /**
     * Provision if — and only if — there is anything to provision. The check
     * is off the service on purpose: starting a foreground service is the
     * expensive, deadline-bound part, and reading a few files costs nothing.
     */
    static void provision(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            if (!Docker.needsProvision(app)) {
                DexLog.step("docker", "already provisioned (v" + Docker.PAYLOAD_VERSION + ")");
                return;
            }
            send(app, ACTION_PROVISION);
        }, "docker-provision-check").start();
    }

    static void start(Context ctx) {
        send(ctx, ACTION_START);
    }

    static void stop(Context ctx) {
        send(ctx, ACTION_STOP);
    }

    static void reset(Context ctx) {
        send(ctx, ACTION_RESET);
    }

    private static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, DockerService.class).setAction(action);
        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            // Background-start restrictions can refuse this outright. Losing a
            // Docker command is bad; taking the desktop shell down with an
            // uncaught exception is far worse.
            DexLog.warn("docker", "could not start the service for " + action, e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Enter the foreground the instant this service exists, not on the first
     * onStartCommand: the platform's five-second deadline runs from the
     * caller's startForegroundService(), and onStartCommand is queued behind
     * whatever else the main thread is doing.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        goForeground(getString(R.string.dk_preparing));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        goForeground(getString(R.string.dk_preparing));
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            io.execute(this::startRuntime);
        } else if (ACTION_STOP.equals(action)) {
            io.execute(() -> {
                stopRuntime();
                stopSelf();
            });
        } else if (ACTION_RESET.equals(action)) {
            io.execute(() -> {
                reset();
                provision();
            });
        } else {
            io.execute(this::provision);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ── provisioning ──

    private void provision() {
        if (!Docker.needsProvision(this)) {
            DexLog.step("docker", "already provisioned (v" + Docker.PAYLOAD_VERSION + ")");
            idleStop();
            return;
        }
        if (!Docker.abiSupported()) {
            writeError("unsupported-abi");
            idleStop();
            return;
        }
        if (!Docker.qemuBin(this).exists()) {
            // The APK was built without the jniLibs artifact. Says so instead
            // of failing later with a shell error nobody can read.
            writeError("qemu-missing");
            idleStop();
            return;
        }
        if (!provisioning.compareAndSet(false, true)) {
            DexLog.step("docker", "provisioning already in flight");
            return; // stay foreground; the in-flight run owns the lifecycle
        }
        try {
            File dir = Docker.root(this);
            dir.mkdirs();
            writeState("pushing", 0, "preparing");
            copyAsset("docker/docker-rt.sh", Docker.rtScript(this));

            if (!fetchArtifacts()) {
                writeError("download-failed");
                return;
            }
            writeState("pushing", 90, "building-boot-image");
            buildBootImage();
            writeState("pushing", 95, "creating-disk");
            createDisk();

            Docker.writeFile(new File(dir, "mem"), String.valueOf(Docker.DEFAULT_MEM_MB));
            Docker.writeFile(new File(dir, "cpus"), String.valueOf(Docker.DEFAULT_CPUS));
            Docker.enginePort(this); // chosen once, persisted

            writeState("prepared", 100, "ready-to-boot");
            DexLog.step("docker", "provisioned (v" + Docker.PAYLOAD_VERSION + ")");
        } catch (Exception e) {
            DexLog.step("docker", "provision error: " + e);
            writeError("provision-failed");
        } finally {
            provisioning.set(false);
            idleStop();
        }
    }

    /** Download + verify every pinned Alpine artifact we do not already hold. */
    private boolean fetchArtifacts() {
        String[][] all = Docker.artifacts();
        for (int i = 0; i < all.length; i++) {
            String[] a = all[i];
            File out = Docker.artifact(this, a[0]);
            if (out.exists() && a[2].equalsIgnoreCase(sha256(out))) continue;
            // Each artifact owns a slice of 0..85% so the bar keeps moving
            // across four downloads instead of restarting four times.
            int base = 85 * i / all.length;
            int span = 85 / all.length;
            if (!download(a[1], out, a[2], base, span, a[0])) return false;
        }
        return true;
    }

    private boolean download(String url, File out, String wantSha,
                             int pctBase, int pctSpan, String label) {
        File part = new File(out.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            if (conn.getResponseCode() / 100 != 2) {
                DexLog.step("docker", label + " http " + conn.getResponseCode());
                return false;
            }
            long total = conn.getContentLengthLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long seen = 0;
            int lastPct = -1;
            try (InputStream in = conn.getInputStream();
                 OutputStream fout = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fout.write(buf, 0, n);
                    md.update(buf, 0, n);
                    seen += n;
                    if (total > 0) {
                        int pct = pctBase + (int) (seen * pctSpan / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            writeState("pushing", pct, "downloading-" + label);
                        }
                    }
                }
            }
            if (!wantSha.equalsIgnoreCase(hex(md.digest()))) {
                DexLog.step("docker", label + " sha mismatch");
                part.delete();
                return false;
            }
            out.delete();
            return part.renameTo(out);
        } catch (Exception e) {
            DexLog.step("docker", label + " download error: " + e);
            part.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Alpine's initramfs plus our own /init, concatenated rather than repacked
     * — see {@link Cpio} for why that is legal and why it is the whole reason
     * the app needs no cpio reader.
     */
    private void buildBootImage() throws Exception {
        byte[] init = readAsset("docker/guest-init.sh");
        Cpio.appendSingleFile(Docker.artifact(this, "initramfs"),
                Docker.bootImage(this), "init", init);
        Docker.writeFile(new File(Docker.root(this), "init.sha"), sha256(init));
        DexLog.step("docker", "boot image built (" + Docker.bootImage(this).length() + " bytes)");
    }

    /**
     * Rebuild the boot image when this build's guest script is not the one
     * baked into it. Run before every start, and it costs a hash of 9 KB.
     *
     * This is the counterpart to {@link Linux#FEATURE_LEVEL}, derived rather
     * than declared. {@link Docker#PAYLOAD_VERSION} is the expensive number —
     * bumping it throws away every image and container on the disk — and the
     * first fix to {@code guest-init.sh} after shipping was a login prompt on
     * the console, which is nowhere near worth that. A hash cannot be
     * forgotten either: change the script and the next start carries it.
     *
     * Only the initramfs changes; the disk is untouched. Anything the script
     * must repair inside an existing guest it does in its own boot path.
     */
    private void refreshBootImage() {
        try {
            byte[] init = readAsset("docker/guest-init.sh");
            String want = sha256(init);
            File stamp = new File(Docker.root(this), "init.sha");
            if (Docker.bootImage(this).isFile()
                    && want.equals(Docker.readFile(stamp).trim())) {
                return;
            }
            buildBootImage();
            DexLog.step("docker", "boot image refreshed for a new guest init");
        } catch (Exception e) {
            // A stale boot image still boots. Failing the start over this
            // would turn a cosmetic guest fix into a dead feature.
            DexLog.warn("docker", "could not refresh the boot image", e);
        }
    }

    /**
     * The virtual disk: a sparse file of the declared size, formatted by the
     * guest itself on its first boot.
     *
     * setLength does not write 32 GiB — /data is f2fs or ext4 and the file
     * stays a hole until the guest puts something in it. Formatting is the
     * guest's job because Android has no mke2fs and an app has no loop device;
     * inside the VM it is one ordinary mkfs.ext4 as root.
     */
    private void createDisk() throws Exception {
        File img = Docker.rootImage(this);
        if (img.isFile() && img.length() == Docker.DISK_BYTES) return;
        try (RandomAccessFile raf = new RandomAccessFile(img, "rw")) {
            raf.setLength(Docker.DISK_BYTES);
        }
        DexLog.step("docker", "disk created (" + Docker.DISK_BYTES + " bytes, sparse)");
    }

    /**
     * Delete the VM outright — disk, boot image, downloads, state.
     *
     * The runtime is stopped FIRST: QEMU writing into a file being deleted
     * underneath it leaves a half disk behind, and a half disk still looks
     * like a provisioned one to {@link Docker#needsProvision}.
     */
    private void reset() {
        DexLog.step("docker", "reset — wiping the VM");
        stopRuntime();
        File dir = Docker.root(this);
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File kid : kids) kid.delete();
        }
        dir.mkdirs();
        writeState("pushing", 0, "resetting");
        DexLog.step("docker", "reset — VM removed");
    }

    // ── runtime ──

    private void startRuntime() {
        try {
            if (Docker.needsProvision(this)) return; // nothing to boot yet
            if (runtimeUp && Docker.readStatus(this).running) {
                goForeground(getString(R.string.dk_label));
                return; // already up — do NOT spawn a second QEMU
            }
            // Clear any half-dead or orphaned VM (a previous session's QEMU
            // still holding the forwarded port) so the fresh one owns it.
            stopRuntime();
            // Carry any change to the guest script into the boot image first —
            // see refreshBootImage. Also re-copies the runtime script, which is
            // read from disk at spawn.
            copyAsset("docker/docker-rt.sh", Docker.rtScript(this));
            refreshBootImage();

            File dir = Docker.root(this);
            // Rewritten on every start, not just at provision time, so a change
            // to the VM's shape reaches an install that already exists. The
            // first build asked for 2 GiB and Android's low-memory killer took
            // the whole process mid-install; without this, fixing that number
            // would have meant telling everyone to reset their machine.
            Docker.writeFile(new File(dir, "mem"), String.valueOf(Docker.DEFAULT_MEM_MB));
            Docker.writeFile(new File(dir, "cpus"), String.valueOf(Docker.DEFAULT_CPUS));
            Docker.consoleLog(this).delete(); // one file per run, like the PC trace
            // The state file outlives the VM. Without this, a machine that was
            // up yesterday boots today under the previous run's "ready /
            // engine-up": the window narrates a stage this boot has not reached
            // and starts querying an engine that does not exist yet.
            writeState("boot", 0, "starting");
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", Docker.rtScript(this).getAbsolutePath());
            pb.directory(dir);
            pb.environment().putAll(Docker.scriptEnv(this));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(dir, "rt.log"));
            // NOT waited and NOT setsid'd here: it stays a child of this app so
            // a force-stop (DeX session end) tears the VM down with it; the
            // script setsids ITSELF so the group kill still works.
            runtimeUp = true;
            runtimeProc = pb.start();
            goForeground(getString(R.string.dk_label));
            DexLog.step("docker", "VM started on port " + Docker.enginePort(this));
            watchConsole();
        } catch (Exception e) {
            runtimeUp = false;
            DexLog.step("docker", "VM start error: " + e);
            writeError("start-failed");
        }
    }

    /**
     * Follow the guest's console and turn it into the state the window reads.
     *
     * The guest is not reachable by any other means while it is installing —
     * there is no agent in there and dockerd does not exist yet — so its serial
     * output IS the progress protocol. {@code guest-init.sh} prints
     * {@code @@OADX phase=… pct=… msg=…} lines for exactly this reader.
     *
     * Once those stop, readiness is decided by asking dockerd instead: a guest
     * that reaches its login prompt has not finished booting in any sense the
     * user cares about until the engine answers.
     */
    private void watchConsole() {
        Thread prev = consoleWatcher;
        if (prev != null) prev.interrupt();
        Thread t = new Thread(() -> {
            File log = Docker.consoleLog(this);
            long pos = 0;
            boolean ready = false;
            long lastPing = 0;
            while (!Thread.currentThread().isInterrupted() && runtimeUp) {
                try {
                    if (log.length() < pos) pos = 0; // truncated under us
                    if (log.length() > pos) {
                        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
                            raf.seek(pos);
                            String line;
                            while ((line = raf.readLine()) != null) {
                                int at = line.indexOf("@@OADX ");
                                if (at >= 0) applyGuestMarker(line.substring(at + 7));
                            }
                            pos = raf.getFilePointer();
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (!ready && now - lastPing > 2000) {
                        lastPing = now;
                        if (DockerApi.ping(Docker.enginePort(this))) {
                            ready = true;
                            writeState("ready", 100, "engine-up");
                            DexLog.step("docker", "engine is up");
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    // A console that cannot be read is not a reason to take the
                    // VM down; it only costs progress reporting.
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "docker-console");
        t.setDaemon(true);
        consoleWatcher = t;
        t.start();
    }

    /** {@code phase=install pct=30 msg=installing-alpine} → state.env. */
    private void applyGuestMarker(String rest) {
        String phase = null;
        String msg = "";
        int pct = 0;
        for (String tok : rest.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq);
            String v = tok.substring(eq + 1);
            if ("phase".equals(k)) phase = v;
            else if ("msg".equals(k)) msg = v;
            else if ("pct".equals(k)) {
                try {
                    pct = Integer.parseInt(v);
                } catch (Exception ignored) {
                }
            }
        }
        if (phase != null) writeState(phase, pct, msg);
    }

    /**
     * Take the VM down.
     *
     * The kill that does the work is the PROCESS GROUP one: docker-rt.sh makes
     * itself a session leader, so its pid doubles as a process-group id and
     * {@code kill -9 -<pid>} reaches QEMU in one call. An app uid cannot browse
     * /proc, so pattern kills and pid-identity checks are unreliable here —
     * kill(2) needs no /proc at all. Same mechanism, same reasons, as
     * {@link LinuxService#stopRuntime()}.
     *
     * There is no attempt at a graceful guest shutdown. A crash-consistent
     * ext4 with a journal survives being cut off, which is exactly what the
     * phone does to this app anyway on every DeX session end.
     */
    private void stopRuntime() {
        File dir = Docker.root(this);
        // /system/bin/kill, not the shell builtin: toybox sh's own kill rejects
        // a negative pid outright, so the group kill has to go to the binary.
        String script =
                "P=$(cat " + q(dir) + "/rt.pid 2>/dev/null); "
                        + "if [ -n \"$P\" ]; then "
                        + "/system/bin/kill -9 -\"$P\" 2>/dev/null; "
                        + "/system/bin/kill -9 \"$P\" 2>/dev/null; fi; "
                        + "rm -f " + q(dir) + "/rt.pid " + q(dir) + "/console.sock; true";
        try {
            new ProcessBuilder("/system/bin/sh", "-c", script)
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            DexLog.step("docker", "VM stop error: " + e);
        }
        Process p = runtimeProc;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
        runtimeProc = null;
        runtimeUp = false;
        Thread t = consoleWatcher;
        if (t != null) t.interrupt();
        consoleWatcher = null;
        DexLog.step("docker", "VM stopped");
    }

    // ── foreground plumbing ──

    private void goForeground(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, getString(R.string.dk_label), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle(getString(R.string.dk_label))
                    .setContentText(text)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            DexLog.warn("docker", "could not enter the foreground", e);
        }
    }

    /**
     * Drop foreground + stop when neither provisioning nor a VM is up.
     * Through stopSelfResult, and only dropping the notification once it says
     * the service really is going — a plain stopSelf() ignores starts that
     * arrived while this was deciding.
     */
    private void idleStop() {
        if (provisioning.get()) return;
        if (Docker.readStatus(this).running) return;
        if (stopSelfResult(lastStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    // ── small helpers ──

    private void writeState(String phase, int pct, String msg) {
        Docker.writeFile(new File(Docker.root(this), "state.env"),
                "VERSION=" + Docker.PAYLOAD_VERSION + "\nPHASE=" + phase
                        + "\nPCT=" + pct + "\nMSG=" + msg + "\n");
    }

    private void writeError(String msg) {
        writeState("error", 0, msg);
    }

    private void copyAsset(String name, File dest) throws Exception {
        try (OutputStream out = new FileOutputStream(dest)) {
            out.write(readAsset(name));
        }
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static String q(File f) {
        return "'" + f.getAbsolutePath() + "'";
    }

    private static String sha256(byte[] b) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
