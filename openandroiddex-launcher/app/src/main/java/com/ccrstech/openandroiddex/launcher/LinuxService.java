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
    /** Remote access on: the tunnel and the guest's sshd. See {@link RemoteHost}. */
    static final String ACTION_REMOTE_ON = "com.ccrstech.openandroiddex.launcher.linux.REMOTE_ON";
    static final String ACTION_REMOTE_OFF = "com.ccrstech.openandroiddex.launcher.linux.REMOTE_OFF";
    /** Start remote access again if the user left it on — boot, an update, a PC session ending. */
    static final String ACTION_REMOTE_RESUME = "com.ccrstech.openandroiddex.launcher.linux.REMOTE_RESUME";
    /** Open "Add a computer" with the code in {@link #EXTRA_CODE}. */
    static final String ACTION_REMOTE_PAIR = "com.ccrstech.openandroiddex.launcher.linux.REMOTE_PAIR";
    static final String ACTION_REMOTE_PAIR_CANCEL = "com.ccrstech.openandroiddex.launcher.linux.REMOTE_PAIR_CANCEL";
    static final String EXTRA_W = "w";
    static final String EXTRA_H = "h";
    static final String EXTRA_CODE = "code";
    /** On REMOTE_OFF: it came from the notification's Turn off. */
    private static final String EXTRA_FROM_NOTIFICATION = "from_notification";

    private static final String CHANNEL = "linux";
    private static final int NOTIF_ID = 0x1D;

    /** One provisioning run at a time, across every intent that asks for one. */
    private static final AtomicBoolean provisioning = new AtomicBoolean(false);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Remote access, while it is on. Never null after onCreate. */
    private RemoteHost remote;
    /** What the notification said last, so a remote change can repost it. */
    private String foregroundText;
    /** Kept only so the runtime process is not GC-reaped mid-life. */
    private Process runtimeProc;
    /**
     * The runtime this process spawned, for {@link #runtimeAlive}. Static
     * because the runtime belongs to the process, which can outlive an
     * instance of this service.
     */
    private static volatile Process liveRuntime;

    /**
     * A runtime this process started is still running.
     *
     * rt.pid alone cannot say so: linux-rt.sh removes it when the session
     * ends, but a runtime killed along with this process — an app update, a
     * force-stop, the PC's end-of-session clean-up — leaves the file behind,
     * naming a desktop that is gone. Remote access asks this before telling a
     * computer the desktop is up; a runtime always dies with the process that
     * started it, so one this process did not start is not running.
     */
    static boolean runtimeAlive() {
        Process p = liveRuntime;
        return p != null && p.isAlive();
    }
    /**
     * True from the instant we spawn a runtime until we stop it. Guards against
     * the double-start race: two START intents can both see rt.pid absent
     * (the runtime writes it a beat after spawn), and the second Xvnc then
     * fails to bind the port while rt.pid ends up pointing at the loser —
     * leaving a working desktop the window never connects to.
     */
    private volatile boolean runtimeUp;
    /** elapsedRealtime of the last spawn; see idleStop. */
    private volatile long runtimeSpawnedAt;
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

    /**
     * Turn remote access on, and remember that it is on.
     *
     * @return false when Android refused to start the service from where the
     *         caller is. It is still on, and comes up at the next chance —
     *         boot, an update, the desktop or the Linux window opening.
     */
    static boolean remoteOn(Context ctx) {
        LinuxRemote.setEnabled(ctx, true);
        LinuxRemote.clearSshdGaveUp(ctx);
        return send(ctx, ACTION_REMOTE_ON, 0, 0);
    }

    /** Turn remote access off: every session ends, and it stays off. */
    static void remoteOff(Context ctx) {
        LinuxRemote.setEnabled(ctx, false);
        send(ctx, ACTION_REMOTE_OFF, 0, 0);
    }

    /**
     * Bring remote access back if the user left it on, and do nothing at all
     * otherwise. Safe to call from anywhere and as often as you like — boot,
     * an app update, the desktop starting, the Linux window opening — which is
     * why nothing calls ACTION_REMOTE_ON for this.
     *
     * @return false when Android refused to start the service from where we
     *         are (background start restrictions); the caller may then ask
     *         the user to tap something, which is always allowed to.
     */
    static boolean remoteResume(Context ctx) {
        if (!LinuxRemote.isEnabled(ctx) || !LinuxRemote.available(ctx)) return true;
        return send(ctx, ACTION_REMOTE_RESUME, 0, 0);
    }

    /** Open "Add a computer" for {@code code}; turns remote access on if it was off. */
    static void remotePair(Context ctx, String code) {
        LinuxRemote.setEnabled(ctx, true);
        Intent i = new Intent(ctx, LinuxService.class).setAction(ACTION_REMOTE_PAIR)
                .putExtra(EXTRA_CODE, code);
        start(ctx, i);
    }

    static void remotePairCancel(Context ctx) {
        send(ctx, ACTION_REMOTE_PAIR_CANCEL, 0, 0);
    }

    private static boolean send(Context ctx, String action, int w, int h) {
        Intent i = new Intent(ctx, LinuxService.class).setAction(action);
        if (w > 0) i.putExtra(EXTRA_W, w).putExtra(EXTRA_H, h);
        return start(ctx, i);
    }

    private static boolean start(Context ctx, Intent i) {
        try {
            ctx.startForegroundService(i);
            return true;
        } catch (Exception e) {
            // Background-start restrictions can refuse this outright. Losing a
            // Linux command is a bad outcome; taking the desktop shell down
            // with an uncaught exception is a far worse one.
            DexLog.warn("linux", "could not start the service for " + i.getAction(), e);
            return false;
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
        remote = RemoteHost.of(this);
        remote.setListener(new RemoteHost.Listener() {
            @Override
            public void remoteChanged() {
                mainHandler.post(() -> {
                    refreshForeground();
                    // The host stops itself when it finds remote access switched
                    // off behind its back; with it gone this service may have
                    // nothing left to be foreground for.
                    if (!remote.running() && !LinuxRemote.isEnabled(LinuxService.this)) idleStopQueued();
                });
            }

            @Override
            public void startDesktop(int w, int h) {
                try {
                    io.execute(() -> startRuntimeForComputer(w, h));
                } catch (java.util.concurrent.RejectedExecutionException destroyed) {
                    // onDestroy has run; the daemon's wait runs out and the
                    // computer is told the desktop did not start.
                }
            }
        });
        goForeground(getString(R.string.ln_installing));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Repeated on every start: the deadline is armed per startForegroundService
        // call, and a start that arrives after an idleStop dropped us out of the
        // foreground has its own five seconds to satisfy.
        lastStartId = startId;
        // What it said last, not "Installing…" every time: a start that is
        // about nothing the text describes (remote access going off with the
        // desktop up, say) must not leave it claiming an install.
        goForeground(foregroundText != null ? foregroundText : getString(R.string.ln_installing));
        String action = intent != null ? intent.getAction() : null;
        // A null intent is Android restarting us after killing the process —
        // which only happens because the return below said START_STICKY, which
        // it only says while remote access is on. So: bring that back.
        if (intent == null) action = ACTION_REMOTE_RESUME;
        // Not only on the null intent, though: Android re-creates a sticky
        // service with whatever start was pending when it died, and only
        // falls back to null when there was none — closing the Linux window
        // can be that start. So every start that is not the one switching
        // remote access off brings it up when it is on. start() is a no-op
        // while it is running.
        boolean remoteWanted = LinuxRemote.isEnabled(this) && LinuxRemote.available(this);
        if (remoteWanted && !ACTION_REMOTE_OFF.equals(action) && !ACTION_UNINSTALL.equals(action)) {
            remote.start();
            // RemoteResumeReceiver's "paused — tap to resume", if one is up:
            // whatever brought remote access back, it is not paused now.
            try {
                getSystemService(NotificationManager.class).cancel(0x1E);
            } catch (Exception ignored) {
            }
        }
        if (ACTION_REMOTE_ON.equals(action) || ACTION_REMOTE_RESUME.equals(action)) {
            if (remoteWanted) {
                goForeground(getString(R.string.ln_label));
            } else {
                idleStopQueued();
            }
            return stickiness();
        } else if (ACTION_REMOTE_OFF.equals(action)) {
            // Only the notification's Turn off clears the marker here: it has
            // no code of ours in front of it to do that. Every other caller
            // went through remoteOff, which already did — and clearing it
            // again would undo an On that was tapped while this Off queued.
            if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
                LinuxRemote.setEnabled(this, false);
            }
            remote.stop();
            // stop() is asynchronous; idle once it has had its moment.
            mainHandler.postDelayed(this::idleStopQueued, 5000);
            return stickiness();
        } else if (ACTION_REMOTE_PAIR.equals(action)) {
            String code = intent.getStringExtra(EXTRA_CODE);
            if (code != null && LinuxRemote.available(this)) {
                remote.start();
                remote.pair(code);
                goForeground(getString(R.string.ln_label));
            }
            return stickiness();
        } else if (ACTION_REMOTE_PAIR_CANCEL.equals(action)) {
            remote.cancelPair();
            if (!remoteWanted) idleStopQueued();
            return stickiness();
        }
        if (ACTION_START.equals(action)) {
            final int w = intent.getIntExtra(EXTRA_W, 1280);
            final int h = intent.getIntExtra(EXTRA_H, 800);
            io.execute(() -> startRuntime(w, h, true));
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
                // idleStop, not stopSelf: closing the desktop window ends the
                // DESKTOP. Remote access, if it is on, is the reason this
                // service is still wanted, and someone may be logged in over
                // SSH right now.
                idleStop();
            });
        } else if (ACTION_RESET.equals(action)) {
            io.execute(() -> {
                reset();
                provision();
            });
        } else if (ACTION_UNINSTALL.equals(action)) {
            io.execute(() -> {
                uninstall();
                idleStop(); // nothing left to be foreground for
            });
        } else { // PROVISION (default)
            io.execute(this::provision);
        }
        return stickiness();
    }

    /**
     * Sticky only while remote access is on. A container the user is looking
     * at is theirs to restart; one they asked to keep reachable has to come
     * back by itself if Android reclaims the process, or "reachable from
     * anywhere" would quietly stop being true the first time memory ran low.
     */
    private int stickiness() {
        return LinuxRemote.isEnabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        // The host is the process's, not this instance's: stopped (in order,
        // ahead of whatever start a next instance sends it) and told to stop
        // reporting to a service that is gone.
        remote.stop();
        remote.setListener(null);
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
        // Remote access is SSH into this container, so it goes off with it —
        // otherwise a tunnel would keep the phone awake and online to answer
        // "Linux is not installed" for ever. The paired computers are KEPT
        // (files/linux-remote is outside the container): reinstalling and
        // switching remote access back on brings every one of them back.
        LinuxRemote.setEnabled(this, false);
        remote.stop();
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
        // sshd runs in a proot of its own over the same rootfs, and a wipe
        // under a live one is the half-deleted tree this method exists to
        // avoid. Held stopped for the whole wipe, not just stopped: deleting a
        // leftover tree first can take a while, and the live guest still reads
        // as ready until the rename, so sshd would be started again on it.
        // Remote access itself stays on through a reinstall; it reports
        // "installing" until the new guest has an sshd again.
        remote.holdSsh();
        try {
            File dir = Linux.root(this);
            File doomed = new File(dir.getParentFile(), "linux-deleting");
            deleteTree(doomed);
            if (dir.exists() && !dir.renameTo(doomed)) {
                doomed = dir; // rename refused; delete in place rather than not at all
            }
            deleteTree(doomed);
        } finally {
            remote.releaseSsh();
        }
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

    /**
     * A computer opened the desktop through remote access, and it was not
     * running. Never a restart: a session that is up, or was spawned moments
     * ago and has not written its pid yet — the Linux window's own start, a
     * beat ahead — is left alone, and the computer gets it at its size.
     */
    private void startRuntimeForComputer(int w, int h) {
        long sinceSpawn = android.os.SystemClock.elapsedRealtime() - runtimeSpawnedAt;
        if (runtimeUp && sinceSpawn < 15_000) return;
        startRuntime(w, h, false);
    }

    /**
     * @param withSound the guest's speakers come on with it. True for the
     *     Linux window, false for a computer: the phone may be in a bag in
     *     another room, and a video playing on a laptop must not come out of
     *     it. The window opening later brings the sound on then.
     */
    private void startRuntime(int w, int h, boolean withSound) {
        try {
            Linux.Status st = Linux.readStatus(this);
            if (!"ready".equals(st.phase)) return; // nothing to start yet
            if (runtimeUp && st.running) {
                if (withSound) LinuxAudio.start();
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
            // The ceiling is the daemon's too: a computer's screen may ask for
            // a desktop, and every pixel of it is drawn by this phone's CPU.
            writeFile(new File(dir, "geometry"),
                    Math.min(Math.max(w, 800), 3840) + "x" + Math.min(Math.max(h, 600), 2400));
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
            runtimeSpawnedAt = android.os.SystemClock.elapsedRealtime();
            runtimeProc = pb.start();
            liveRuntime = runtimeProc;
            // The guest has no audio device: it plays into a null sink and this
            // drains that sink's monitor into an AudioTrack. Started here rather
            // than when the window opens, because the container is what produces
            // the sound — a session put in the background with "keep running" is
            // still allowed to make noise. It retries while the guest boots.
            if (withSound) LinuxAudio.start();
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
        liveRuntime = null;
        runtimeUp = false;
        DexLog.step("linux", "runtime stopped");
    }

    // ── foreground plumbing ──

    /** Repost the notification with what remote access says now. Main thread. */
    private void refreshForeground() {
        if (foregroundText != null) goForeground(foregroundText);
    }

    private void goForeground(String text) {
        foregroundText = text;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, getString(R.string.ln_label), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            // While remote access is on it is what this notification is FOR —
            // the one sign on the phone that a door is open — so its line wins
            // over the install/runtime text, and the way to close the door is
            // right there on it.
            String remoteLine = remote != null ? remote.summary() : null;
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle(getString(R.string.ln_label))
                    .setContentText(remoteLine != null ? remoteLine : text)
                    .setContentIntent(openWindow())
                    .setOngoing(true);
            if (remoteLine != null) {
                b.addAction(new Notification.Action.Builder(null,
                        getString(R.string.rm_note_settings), openRemote()).build());
                b.addAction(new Notification.Action.Builder(null,
                        getString(R.string.rm_note_turn_off), remoteOffIntent()).build());
            }
            Notification n = b.build();
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

    private PendingIntent openRemote() {
        return PendingIntent.getActivity(this, 1,
                new Intent(this, LinuxRemoteActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * The notification's "Turn off". Straight to the service, which is why the
     * REMOTE_OFF handler clears the enabled marker itself: a PendingIntent has
     * no code of ours in front of it to do that.
     */
    private PendingIntent remoteOffIntent() {
        return PendingIntent.getForegroundService(this, 2,
                new Intent(this, LinuxService.class).setAction(ACTION_REMOTE_OFF)
                        .putExtra(EXTRA_FROM_NOTIFICATION, true),
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
        // A desktop start in flight: runtimeUp goes true before linux-rt.sh has
        // written the rt.pid that readStatus looks for. Only for a moment after
        // the spawn, though — a session that has since ended by itself leaves
        // runtimeUp set, and must not keep the service up for ever. Asked again
        // once the moment has passed: had the start died in it, nothing else
        // would ask.
        long sinceSpawn = android.os.SystemClock.elapsedRealtime() - runtimeSpawnedAt;
        if (runtimeUp && sinceSpawn < 15_000) {
            mainHandler.postDelayed(this::idleStopQueued, 15_000 - sinceSpawn + 500);
            return;
        }
        if (Linux.readStatus(this).running) return;
        // Remote access is a reason to be running all by itself: no window, no
        // desktop session, just a phone that can be reached. The marker, not
        // only running(): it is written before the intent that starts the host
        // is even sent, so a provisioning run finishing in the instant before
        // the host's thread gets going cannot idle the service out from under it.
        if (remote != null && remote.running()) return;
        if (LinuxRemote.isEnabled(this) && LinuxRemote.available(this)) return;
        if (stopSelfResult(lastStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    /**
     * idleStop, queued on the io thread behind whatever it is doing. The
     * remote-access starts decide on the main thread, and an idleStop run
     * there could land between a desktop start's spawn and its rt.pid and stop
     * the service under it; every other caller already runs on io, after its
     * own work.
     */
    private void idleStopQueued() {
        try {
            io.execute(this::idleStop);
        } catch (java.util.concurrent.RejectedExecutionException destroyed) {
            // onDestroy has run; there is nothing left to stop.
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
