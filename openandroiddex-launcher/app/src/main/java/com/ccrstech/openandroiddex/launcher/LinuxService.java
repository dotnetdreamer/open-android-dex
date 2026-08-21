package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the Linux container's whole lifecycle inside this app: download +
 * verify the rootfs, run the provisioning script, and host the proot / Xvnc /
 * websockify runtime. Everything runs as this app's uid in its own storage —
 * no daemon, no shell, no PC. It is a foreground service so Android does not
 * reap the long-running native process tree while the desktop window sits in
 * the background.
 *
 * Driven by start intents, never bound: PROVISION (idempotent, safe to fire on
 * every launcher start), START w h, STOP. When the app is force-stopped (e.g.
 * the PC ends a DeX session) Android kills every process of this uid, which
 * takes the runtime down with it; the rootfs, living in private storage,
 * survives for next time.
 */
public class LinuxService extends Service {

    static final String ACTION_PROVISION = "com.ccrstech.openandroiddex.launcher.linux.PROVISION";
    static final String ACTION_START = "com.ccrstech.openandroiddex.launcher.linux.START";
    static final String ACTION_STOP = "com.ccrstech.openandroiddex.launcher.linux.STOP";
    /** Wipe the container and build it again from nothing. */
    static final String ACTION_RESET = "com.ccrstech.openandroiddex.launcher.linux.RESET";
    /** Wipe the container and leave it gone, with the storage free. */
    static final String ACTION_UNINSTALL = "com.ccrstech.openandroiddex.launcher.linux.UNINSTALL";
    static final String EXTRA_W = "w";
    static final String EXTRA_H = "h";

    private static final String CHANNEL = "linux";
    private static final int NOTIF_ID = 0x1D;

    /** One provisioning run at a time, across every intent that asks for one. */
    private static final AtomicBoolean provisioning = new AtomicBoolean(false);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Kept only so the runtime process is not GC-reaped mid-life. */
    private Process runtimeProc;
    /**
     * True from the instant we spawn a runtime until we stop it. Guards against
     * the double-start race: two START intents can both see rt.pid absent
     * (the runtime writes it a beat after spawn), and the second Xvnc then
     * fails to bind the port while rt.pid ends up pointing at the loser —
     * leaving a working desktop the window never connects to.
     */
    private volatile boolean runtimeUp;
    /** Newest start we have seen, so idleStop cannot cancel a start in flight. */
    private volatile int lastStartId;

    /**
     * Provision if — and only if — there is anything to provision.
     *
     * The check is here rather than inside the service because starting a
     * foreground service is the expensive, dangerous part. The desktop kicks
     * this on every launch, and on a busy first launch the main thread did not
     * get round to onStartCommand inside the platform's five-second window; the
     * framework then threw ForegroundServiceDidNotStartInTimeException, which
     * force-finished LinuxActivity AND LauncherActivity — the taskbar vanishing
     * and the display going black on the first Linux open, with the desktop
     * needing a relaunch. Reading four small files off the main thread costs
     * nothing and skips the service entirely in the common case.
     */
    static void provision(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            // Linux.needsProvision answers false while the chooser has not been
            // through, which is what keeps the desktop's provision-on-launch
            // from starting a 1.5 GB download behind a question nobody has been
            // shown. Said out loud here rather than folded into the line below,
            // because "already provisioned" would be a lie about a phone with
            // no container on it at all.
            if (Linux.needsAppChoice(app)) {
                DexLog.step("linux", "waiting for the app choice before installing");
                return;
            }
            if (!Linux.needsProvision(app)) {
                DexLog.step("linux", "already provisioned (v" + Linux.PAYLOAD_VERSION
                        + " f" + Linux.FEATURE_LEVEL + ") — " + Linux.installedApps(app));
                return;
            }
            send(app, ACTION_PROVISION, 0, 0);
        }, "linux-provision-check").start();
    }

    static void start(Context ctx, int w, int h) {
        send(ctx, ACTION_START, w, h);
    }

    static void stop(Context ctx) {
        send(ctx, ACTION_STOP, 0, 0);
    }

    /** Throw the container away and install a clean one. Not reversible. */
    static void reset(Context ctx) {
        send(ctx, ACTION_RESET, 0, 0);
    }

    /** Throw the container away and leave the storage free. Not reversible. */
    static void uninstall(Context ctx) {
        send(ctx, ACTION_UNINSTALL, 0, 0);
    }

    private static void send(Context ctx, String action, int w, int h) {
        Intent i = new Intent(ctx, LinuxService.class).setAction(action);
        if (w > 0) i.putExtra(EXTRA_W, w).putExtra(EXTRA_H, h);
        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            // Background-start restrictions can refuse this outright. Losing a
            // Linux command is a bad outcome; taking the desktop shell down
            // with an uncaught exception is a far worse one.
            DexLog.warn("linux", "could not start the service for " + action, e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Enter the foreground the instant this service exists, not merely on the
     * first onStartCommand. The platform's five-second deadline runs from the
     * caller's startForegroundService(), and onStartCommand is queued behind
     * whatever else the main thread is doing — during the desktop's own launch
     * that was enough to miss it and take the whole shell down with a
     * ForegroundServiceDidNotStartInTimeException.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        goForeground(getString(R.string.ln_installing));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Repeated on every start: the deadline is armed per startForegroundService
        // call, and a start that arrives after an idleStop dropped us out of the
        // foreground has its own five seconds to satisfy.
        lastStartId = startId;
        goForeground(getString(R.string.ln_installing));
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            final int w = intent.getIntExtra(EXTRA_W, 1280);
            final int h = intent.getIntExtra(EXTRA_H, 800);
            io.execute(() -> startRuntime(w, h));
        } else if (ACTION_STOP.equals(action)) {
            io.execute(() -> {
                stopRuntime();
                // Hand the session's output to MediaStore before this process
                // goes away. Android's Files index and the whole USB/MTP object
                // list come from that database, not from the filesystem, so
                // anything the guest wrote is invisible over a cable until it is
                // scanned. Linux.scanShared blocks precisely because an async
                // scan started here would be cut off: :linux has no other
                // component and is reaped the moment stopSelf lands.
                Linux.scanShared(this);
                stopSelf();
            });
        } else if (ACTION_RESET.equals(action)) {
            io.execute(() -> {
                reset();
                provision();
            });
        } else if (ACTION_UNINSTALL.equals(action)) {
            io.execute(() -> {
                uninstall();
                stopSelf(); // nothing left to be foreground for
            });
        } else { // PROVISION (default)
            io.execute(this::provision);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        // The pump is a daemon thread, so the process usually takes it with it.
        // This is for the case where the service goes and the process does not.
        LinuxAudio.stop();
    }

    // ── provisioning ──

    private void provision() {
        // Re-checked HERE and not only in the static provision(): that one
        // decides on its own thread and then sends an intent, so an uninstall
        // landing in between would be undone by a decision taken before it.
        // The window is small and the cost of losing it is a 1.5 GB download
        // the user just asked to be rid of.
        if (Linux.isUninstalled(this)) {
            DexLog.step("linux", "uninstalled — not provisioning");
            idleStop();
            return;
        }
        // The chooser has to be answered before anything is built. Checked
        // here as well as in the static provision(), for the same reason the
        // uninstall marker is: that one decides on its own thread and then
        // sends an intent, and RESET reaches this method without going through
        // it at all — a reinstall asked for before the first container exists
        // must land on the chooser, not on a download of everything.
        if (Linux.needsAppChoice(this)) {
            DexLog.step("linux", "waiting for the app choice before installing");
            idleStop();
            return;
        }
        // ONE test, and it is Linux.needsProvision's — the same one the caller
        // used to decide to send this intent. A second, hand-rolled copy of it
        // lived here and had drifted: it knew about the payload version and the
        // feature level but not about the VS Code stamp, so an unsettled VS
        // Code brought the intent all the way here and was then answered with
        // "already provisioned". Its documented retry-on-the-next-launch never
        // ran once. The app selection would have gone the same way.
        if (!Linux.needsProvision(this)) {
            DexLog.step("linux", "already provisioned (v" + Linux.PAYLOAD_VERSION
                    + " f" + Linux.FEATURE_LEVEL + ") — " + Linux.installedApps(this));
            idleStop();
            return;
        }
        if (!Linux.abiSupported()) {
            writeError("unsupported-abi");
            idleStop();
            return;
        }
        if (!provisioning.compareAndSet(false, true)) {
            DexLog.step("linux", "provisioning already in flight");
            return; // stay foreground; the in-flight run owns the lifecycle
        }
        try {
            File dir = Linux.root(this);
            dir.mkdirs();
            writeState("pushing", 0, "preparing");
            copyAsset("linux/linux-setup.sh", Linux.setupScript(this));
            copyAsset("linux/linux-rt.sh", Linux.rtScript(this));
            if (!ensureRootfs()) {
                writeError("download-failed");
                return;
            }
            runSetup(); // blocks here until the script exits
        } catch (Exception e) {
            DexLog.step("linux", "provision error: " + e);
            writeError("provision-failed");
        } finally {
            provisioning.set(false);
            idleStop();
        }
    }

    /**
     * Delete the container outright: the rootfs, every phase stamp, the state
     * file and the downloaded tarball. The caller follows this with a
     * provision, so the next thing the user sees is a fresh install running.
     */
    private void reset() {
        DexLog.step("linux", "reset — wiping the container");
        wipe();
        Linux.root(this).mkdirs();
        writeState("pushing", 0, "resetting");
        DexLog.step("linux", "reset — container removed");
    }

    /**
     * The same wipe, and then nothing.
     *
     * Everything that separates this from {@link #reset} is what does NOT
     * happen after it: no mkdirs, no state file, no provision behind it. That
     * is the whole point — reset is for a container that is broken, uninstall
     * is for someone who wants the storage back, and finishing an uninstall by
     * downloading 1.5 GB again would answer the wrong one.
     *
     * With no state.env, readStatus reports phase "none", which the UI already
     * renders as "nothing here" — so the tile needs no new state of its own.
     * The marker is what stops provision-on-launch putting it all back.
     *
     * The shared folder is deliberately untouched. It sits at the top of
     * external storage precisely so it belongs to the user rather than to the
     * container; deleting someone's documents is not what "uninstall" means.
     */
    private void uninstall() {
        DexLog.step("linux", "uninstall — wiping the container");
        wipe();
        Linux.setUninstalled(this, true);
        DexLog.step("linux", "uninstall — container removed, provisioning off");
    }

    /**
     * Stop the runtime and delete the container tree.
     *
     * The runtime is stopped FIRST and the directory is renamed out of the way
     * before it is walked: a live guest writing into a tree being deleted
     * underneath it leaves a half-rootfs behind, and a half-rootfs looks
     * provisioned to the setup script's stamps.
     */
    private void wipe() {
        stopRuntime();
        File dir = Linux.root(this);
        File doomed = new File(dir.getParentFile(), "linux-deleting");
        deleteTree(doomed);
        if (dir.exists() && !dir.renameTo(doomed)) {
            doomed = dir; // rename refused; delete in place rather than not at all
        }
        deleteTree(doomed);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        // Iterative: a rootfs is deep enough that recursion is a real risk, and
        // symlinks must be unlinked, never followed — the guest is full of them
        // and one pointing at /data would take the app's own storage with it.
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(f);
        java.util.ArrayDeque<File> dirs = new java.util.ArrayDeque<>();
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] kids = isLink(cur) ? null : cur.listFiles();
            if (kids == null) {
                cur.delete();
                continue;
            }
            dirs.push(cur);
            for (File kid : kids) stack.push(kid);
        }
        while (!dirs.isEmpty()) dirs.pop().delete();
    }

    private static boolean isLink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (Exception e) {
            return true; // cannot tell — treat as a link and never descend
        }
    }

    /** Download + verify the rootfs tarball unless a good copy is already here. */
    private boolean ensureRootfs() {
        File out = Linux.rootfsTarball(this);
        String want = Linux.rootfsSha256();
        if (out.exists() && want != null && want.equalsIgnoreCase(sha256(out))) {
            return true; // already have it, verified
        }
        String url = Linux.rootfsUrl();
        if (url == null || want == null) return false;
        writeState("pushing", 0, "downloading-ubuntu");
        File part = new File(out.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            if (conn.getResponseCode() / 100 != 2) {
                DexLog.step("linux", "rootfs http " + conn.getResponseCode());
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
                        int pct = (int) (seen * 25 / total); // download spans 0..25%
                        if (pct != lastPct) {
                            lastPct = pct;
                            writeState("pushing", pct, "downloading-ubuntu");
                        }
                    }
                }
            }
            String got = hex(md.digest());
            if (!want.equalsIgnoreCase(got)) {
                DexLog.step("linux", "rootfs sha mismatch");
                part.delete();
                return false;
            }
            out.delete();
            return part.renameTo(out);
        } catch (Exception e) {
            DexLog.step("linux", "rootfs download error: " + e);
            part.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Run linux-setup.sh to completion, its output tee'd to setup.log. */
    private void runSetup() throws Exception {
        File dir = Linux.root(this);
        ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/sh",
                Linux.setupScript(this).getAbsolutePath(),
                String.valueOf(Linux.PAYLOAD_VERSION),
                String.valueOf(Linux.FEATURE_LEVEL));
        pb.directory(dir);
        pb.environment().putAll(Linux.scriptEnv(this));
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(dir, "setup.log"));
        DexLog.step("linux", "running setup (v" + Linux.PAYLOAD_VERSION
                + " f" + Linux.FEATURE_LEVEL + ")");
        Process p = pb.start();
        int rc = p.waitFor();
        DexLog.step("linux", "setup exited " + rc + " — " + Linux.installedApps(this));
    }

    // ── runtime ──

    private void startRuntime(int w, int h) {
        try {
            Linux.Status st = Linux.readStatus(this);
            if (!"ready".equals(st.phase)) return; // nothing to start yet
            if (runtimeUp && st.running) {
                goForeground(getString(R.string.ln_label));
                return; // already up — do NOT spawn a second one
            }
            // Clear any half-dead or orphaned runtime (a previous session's
            // Xvnc/websockify still holding the ports) so the fresh one owns
            // them cleanly. Safe: it only touches this app's own processes.
            stopRuntime();
            File dir = Linux.root(this);
            // Our viewer page, refreshed into the guest on every real spawn.
            // Here rather than in provision(), which early-returns for a guest
            // that is already set up: staging there would mean bumping
            // FEATURE_LEVEL — and re-running the whole setup script — every
            // time a line of the page changed.
            try {
                stageViewer();
            } catch (Exception e) {
                // The stock noVNC pages are still on disk and still work. A
                // page that failed to copy is a viewer that 404s, which the
                // window turns into its normal error-with-Retry; it is never a
                // reason to abandon the container.
                DexLog.step("linux", "viewer stage failed: " + e);
            }
            writeFile(new File(dir, "geometry"), Math.max(w, 800) + "x" + Math.max(h, 600));
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", Linux.rtScript(this).getAbsolutePath());
            pb.directory(dir);
            pb.environment().putAll(Linux.scriptEnv(this));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(dir, "rt.log"));
            // NOT waited and NOT setsid'd: it stays a child of this app so a
            // force-stop (DeX session end) tears the whole container down; the
            // service staying foreground is what keeps it alive until then.
            runtimeUp = true;
            runtimeProc = pb.start();
            // The guest has no audio device: it plays into a null sink and this
            // drains that sink's monitor into an AudioTrack. Started here rather
            // than when the window opens, because the container is what produces
            // the sound — a session put in the background with "keep running" is
            // still allowed to make noise. It retries while the guest boots.
            LinuxAudio.start();
            goForeground(getString(R.string.ln_label));
            DexLog.step("linux", "runtime started " + w + "x" + h);
        } catch (Exception e) {
            runtimeUp = false;
            DexLog.step("linux", "runtime start error: " + e);
        }
    }

    /**
     * Take the whole container down — proot and every guest process with it.
     *
     * The kill that actually does the work is the PROCESS GROUP one:
     * linux-rt.sh makes itself a session leader, so its pid doubles as a
     * process-group id and {@code kill -9 -<pid>} reaches the tracer and every
     * tracee in a single call. That indirection exists because none of the
     * obvious mechanisms survive the app uid: an app cannot browse /proc, so
     * the old identity check ({@code grep rootfs /proc/$P/cmdline}) could never
     * pass and simply skipped the kill, and the {@code pkill -f} belts behind it
     * match on cmdlines they equally cannot read. kill(2) needs no /proc at all.
     *
     * The belts are kept anyway, for anything that daemonised out of the group
     * on an older payload, and Process#destroyForcibly covers the case where
     * setsid was unavailable and the script is still our direct child. TOYBOX
     * LAW: `pkill -9 -f` kills the CALLER — only `pkill -l KILL -f` is safe,
     * and the bracket keeps each pattern from matching this command line.
     */
    private void stopRuntime() {
        // Before the kill, so the pump's socket dies with the guest that feeds
        // it rather than a second later, on a read of a container that is gone.
        LinuxAudio.stop();
        File dir = Linux.root(this);
        // /system/bin/kill, not the builtin: toybox sh's own kill rejects a
        // negative pid outright ("arguments must be jobs or process IDs"), so
        // the group kill has to go to the real binary. Measured on device.
        String script =
                "P=$(cat " + q(dir) + "/rt.pid 2>/dev/null); "
                        + "if [ -n \"$P\" ]; then "
                        + "/system/bin/kill -9 -\"$P\" 2>/dev/null; "
                        + "/system/bin/kill -9 \"$P\" 2>/dev/null; fi; "
                        + "rm -f " + q(dir) + "/rt.pid " + q(dir) + "/rt.exit; "
                        + "pkill -l KILL -f '[X]vnc :1' 2>/dev/null; "
                        + "pkill -l KILL -f '[w]ebsockify --web' 2>/dev/null; "
                        + "pkill -l KILL -f '[p]ulseaudio -n' 2>/dev/null; "
                        + "pkill -l KILL -f '[d]ex-session-bus' 2>/dev/null; "
                        + "pkill -l KILL -f '[x]fce' 2>/dev/null; true";
        try {
            new ProcessBuilder("/system/bin/sh", "-c", script)
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            DexLog.step("linux", "runtime stop error: " + e);
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
        DexLog.step("linux", "runtime stopped");
    }

    // ── foreground plumbing ──

    private void goForeground(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, getString(R.string.ln_label), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle(getString(R.string.ln_label))
                    .setContentText(text)
                    .setContentIntent(openWindow())
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            DexLog.warn("linux", "could not enter the foreground", e);
        }
    }

    /**
     * Tapping the ongoing notification opens the Linux window.
     *
     * It matters on the phone, where this notification is the one thing on
     * screen that says a container is running and the only handle on a session
     * put aside with "keep running" — and it costs nothing on the desktop,
     * where there is usually no notification to tap: the PC never grants
     * POST_NOTIFICATIONS and the app asks for it only in the phone's window
     * (see LinuxActivity.askForNotifications).
     *
     * Through {@link LinuxAppActivity} rather than straight at the window: that
     * is the same entry the app icon uses, so the "already open on the desktop"
     * answer is given once, in one place. IMMUTABLE because nothing may rewrite
     * this intent, and required from Android 12 in any case.
     */
    private PendingIntent openWindow() {
        return PendingIntent.getActivity(this, 0,
                new Intent(this, LinuxAppActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Drop foreground + stop when neither provisioning nor a runtime is up.
     *
     * Through stopSelfResult, and only dropping the notification once it says
     * the service really is going: a plain stopSelf() ignores starts that
     * arrived while this was deciding, so it could leave the service alive but
     * OUT of the foreground with a start still counting down against it — the
     * exact contract breach that kills the process.
     */
    private void idleStop() {
        if (provisioning.get()) return;
        if (Linux.readStatus(this).running) return;
        if (stopSelfResult(lastStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    // ── small helpers ──

    private void writeState(String phase, int pct, String msg) {
        writeFile(new File(Linux.root(this), "state.env"),
                "VERSION=" + Linux.PAYLOAD_VERSION
                        + "\nFEATURES=" + Linux.FEATURE_LEVEL + "\nPHASE=" + phase
                        + "\nPCT=" + pct + "\nMSG=" + msg + "\n");
    }

    private void writeError(String msg) {
        writeState("error", 0, msg);
    }

    /** Atomic file write (tmp + rename) so a reader never sees half of it. */
    private static void writeFile(File f, String content) {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }

    /**
     * Put the viewer page into the guest's noVNC web root, beside Ubuntu's own
     * pages rather than over them.
     *
     * It has to be served from in there: the page imports noVNC's ES modules
     * from /core/, websockify sends no CORS headers, and same origin is the
     * only way that resolves. websockify's --web is a plain static root, so a
     * file appearing in the directory is served with no configuration at all.
     *
     * Copied on every spawn, so an APK update ships a new page without
     * reprovisioning — and the runtime pid the window puts in the URL is then
     * exactly the version of what is on disk.
     */
    private void stageViewer() throws Exception {
        File rootfs = new File(Linux.root(this), "rootfs");
        if (!rootfs.isDirectory()) return;            // nothing provisioned yet
        File dst = new File(rootfs, "usr/share/novnc");
        dst.mkdirs();                                 // copyAsset does not
        String[] names = getAssets().list("linux/novnc");
        if (names == null) return;
        for (String n : names) copyAsset("linux/novnc/" + n, new File(dst, n));
        DexLog.step("linux", "viewer staged (" + names.length + " files)");
    }

    private void copyAsset(String name, File dest) throws Exception {
        try (InputStream in = getAssets().open(name);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String q(File f) {
        return "'" + f.getAbsolutePath() + "'";
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
