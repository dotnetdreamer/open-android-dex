package com.ccrstech.openandroiddex.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Linux apps in an Android process of their own, ":linuxapps".
 *
 * <p>Android 12 and later close the processes apps start once there are more
 * than 32 on the phone. They pick by the Android process each one belongs to:
 * the least important first, and among equals the oldest first. With every
 * Linux process under ":linux", the oldest were the desktop's own (proot,
 * Xvnc, XFCE), so the desktop went whole: "Process completed (signal 9)".
 *
 * <p>An app the desktop starts now comes here instead (guest-side oadx-run,
 * each in a proot of its own: linux-app.sh). LinuxService binds this with
 * {@code BIND_NOT_PERCEPTIBLE}, which holds this process at importance 250
 * while ":linux" is at 200 or better (it runs a foreground service), so Android
 * closes apps before the desktop, the oldest app first. It is never started,
 * only bound, and never put in the foreground: anything that made it as
 * important as ":linux" would bring back "oldest first" across both.
 *
 * <p>The requests come in as files (see {@link LinuxAppRunner}): the folder is
 * the guest's /tmp/.oadx-apps, and "ready" in it holds this process's pid while
 * it watches, which is how oadx-run knows to hand an app over at all. Files and
 * renames only, no socket: the guest's /tmp is on /data, where SELinux refuses
 * the app a filesystem socket (see linux-rt.sh), and an abstract one could be
 * reached by any app on the phone.
 *
 * <p>Everything here goes with the binding: when LinuxService lets go (both
 * sessions down) or its process dies, Android destroys this service, and
 * {@link #onDestroy} closes every app it started. If this process dies, Android
 * takes its children with it, and the desktop stays.
 */
public class LinuxAppsService extends Service {

    static final String AREA = "linux-apps";
    /** Close the apps on one X display: data {@link #KEY_DISPLAY}, {@link #KEY_BEFORE}. */
    static final int MSG_STOP_DISPLAY = 1;
    static final int MSG_STOP_ALL = 2;
    static final String KEY_DISPLAY = "display";
    /** elapsedRealtime of the stop: apps started after it belong to a newer session. */
    static final String KEY_BEFORE = "before";

    /** A request this old when the service starts no oadx-run waits for any more (it waits ten seconds). */
    static final long STALE_MS = 15_000;

    /** The requests folder, inside the guest's /tmp. */
    static File requests(android.content.Context ctx) {
        return new File(new File(Linux.root(ctx), "tmp"), ".oadx-apps");
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinuxAppRunner runner;
    private FileObserver observer;
    private File dir;
    private volatile boolean cpusetLogged;

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));

    @Override
    public void onCreate() {
        super.onCreate();
        dir = requests(this);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            DexLog.warn(AREA, "no requests folder " + dir + ": apps stay in the desktop's process");
            return;
        }
        try {
            Os.chmod(dir.getAbsolutePath(), 0700);
        } catch (ErrnoException ignored) {
            // the app's own private storage either way
        }
        runner = new LinuxAppRunner(dir, this::spawn, new Killer(), new ProcCensus(),
                android.os.Process.myPid(), s -> DexLog.step(AREA, s),
                android.os.SystemClock::elapsedRealtime, System::currentTimeMillis);
        java.util.List<String> young = runner.sweep(System.currentTimeMillis(), STALE_MS);
        observer = watch(dir);
        observer.startWatching();
        // Only now, with the folder watched: oadx-run hands nothing over until
        // this says so, and a request written from here on is seen.
        writeReady();
        // Written while no one watched (this process was starting): oadx-run is
        // still waiting on them.
        for (String n : young) io.execute(() -> offer(n));
        DexLog.step(AREA, "watching for Linux apps in " + dir);
    }

    @SuppressWarnings("deprecation") // FileObserver(File, int) is API 29; this app starts at 26
    private FileObserver watch(File d) {
        return new FileObserver(d.getAbsolutePath(), FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.endsWith(".req")) return;
                try {
                    io.execute(() -> offer(path));
                } catch (java.util.concurrent.RejectedExecutionException destroyed) {
                    // going away: oadx-run takes the request back and runs it itself
                }
            }
        };
    }

    private void offer(String name) {
        LinuxAppRunner r = runner;
        if (r == null) return;
        LinuxAppRunner.App app = r.offer(name);
        if (app == null) return;
        Thread t = new Thread(() -> r.watch(app), "linux-app-" + app.id);
        t.setDaemon(true);
        t.start();
    }

    private Process spawn(String id, String display) throws java.io.IOException {
        File script = Linux.appScript(this);
        if (!script.isFile()) throw new java.io.IOException("no " + script.getName());
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath(), id);
        pb.directory(Linux.root(this));
        pb.environment().putAll(Linux.scriptEnv(this));
        pb.redirectErrorStream(true);
        // Where each session's apps have always logged: the window session's
        // to the file linux-win.sh used, the desktop's beside it.
        String log = ":2".equals(display) ? "oadx-win-apps.log" : "oadx-apps.log";
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(new File(Linux.root(this), "tmp"), log)));
        Process p = pb.start();
        if (!cpusetLogged) {
            cpusetLogged = true;
            DexLog.step(AREA, "apps start in core group " + firstLine("/proc/self/cpuset"));
        }
        return p;
    }

    private boolean onMessage(Message m) {
        LinuxAppRunner r = runner;
        if (r == null) return true;
        switch (m.what) {
            case MSG_STOP_DISPLAY: {
                Bundle b = m.getData();
                String display = b.getString(KEY_DISPLAY, "");
                long before = b.getLong(KEY_BEFORE, Long.MAX_VALUE);
                io.execute(() -> {
                    int n = r.stopDisplay(display, before);
                    if (n > 0) DexLog.step(AREA, "closed " + n + " app(s) of " + display + " with its session");
                });
                return true;
            }
            case MSG_STOP_ALL:
                io.execute(r::stopAll);
                return true;
            default:
                return false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // First, so oadx-run stops handing apps to a process on its way out.
        clearReady();
        if (observer != null) observer.stopWatching();
        LinuxAppRunner r = runner;
        runner = null;
        if (r != null) {
            // Every app asked to close (proot takes everything it traces), none
            // started from here on, even one in the middle of starting; the
            // force, for whatever is still there, on the runner's own timer.
            int n = r.closeAll();
            if (n > 0) DexLog.step(AREA, "closed " + n + " app(s): Linux is no longer running");
            r.finish();
        }
        io.shutdownNow();
    }

    private void writeReady() {
        File tmp = new File(dir, ".ready.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((android.os.Process.myPid() + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception e) {
            DexLog.warn(AREA, "could not say ready: " + e);
            return;
        }
        if (!tmp.renameTo(new File(dir, "ready"))) DexLog.warn(AREA, "could not say ready");
    }

    /** Only our own: a newer instance may have written it already. */
    private void clearReady() {
        if (dir == null) return;
        File ready = new File(dir, "ready");
        if (firstLine(ready.getAbsolutePath()).equals(String.valueOf(android.os.Process.myPid()))) {
            ready.delete();
        }
    }

    private static String firstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** kill(2) itself: an app may signal its own processes, and needs no /proc for it. */
    static final class Killer implements LinuxAppRunner.Killer {
        @Override
        public boolean alive(int pid) {
            try {
                Os.kill(pid, 0);
                return true;
            } catch (ErrnoException e) {
                // ESRCH: gone. EPERM: the pid is someone else's now, so ours is gone too.
                return false;
            }
        }

        @Override
        public void quit(int pid) {
            // Not blocked in what this process starts: ART blocks SIGQUIT in its
            // own threads (its thread dumps), and the processes it starts get
            // an empty mask (SigBlk in their /proc status, Android 12).
            signal(pid, OsConstants.SIGQUIT);
        }

        @Override
        public void kill(int pid) {
            signal(pid, OsConstants.SIGKILL);
        }

        @Override
        public void killGroup(int pgid) {
            signal(-pgid, OsConstants.SIGKILL);
        }

        private static void signal(int pid, int sig) {
            try {
                Os.kill(pid, sig);
            } catch (ErrnoException ignored) {
                // nothing left there
            }
        }
    }

    /**
     * What Android counts under this process: every process whose cgroup is
     * this one's (uid_N/pid_THIS), as /proc lists them. An app sees its own
     * processes there and nobody else's (measured as an app with
     * targetSdkVersion 36 on Android 12); where /proc will not list even
     * those, this says so once and the runner skips its sweep.
     */
    static final class ProcCensus implements LinuxAppRunner.Census {
        private final String mine = "/pid_" + android.os.Process.myPid();
        private volatile boolean toldNoList;

        @Override
        public java.util.List<LinuxAppRunner.Proc> ours() {
            String[] names = new File("/proc").list();
            if (names == null) {
                if (!toldNoList) {
                    toldNoList = true;
                    DexLog.step(AREA, "/proc does not list this app's processes here: what an app leaves "
                            + "running when Android kills its proot is left to Android");
                }
                return null;
            }
            java.util.List<LinuxAppRunner.Proc> out = new java.util.ArrayList<>();
            for (String n : names) {
                if (n.isEmpty() || n.length() > 10 || !n.chars().allMatch(Character::isDigit)) continue;
                if (!inThisCgroup("/proc/" + n + "/cgroup")) continue;
                int ppid = -1;
                int tracer = -1;
                boolean dead = false;
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/" + n + "/status"))) {
                    String line;
                    // State comes first in the file, then PPid, then TracerPid.
                    while ((line = r.readLine()) != null && (ppid < 0 || tracer < 0)) {
                        if (line.startsWith("State:")) {
                            // "State:\tZ (zombie)"
                            String s = line.substring("State:".length()).trim();
                            dead = !s.isEmpty() && (s.charAt(0) == 'Z' || s.charAt(0) == 'X');
                        }
                        else if (line.startsWith("PPid:")) ppid = number(line);
                        else if (line.startsWith("TracerPid:")) tracer = number(line);
                    }
                } catch (Exception e) {
                    continue; // gone meanwhile
                }
                // A zombie is dead already, waiting for its parent to see it.
                if (dead || ppid < 0 || tracer < 0) continue;
                out.add(new LinuxAppRunner.Proc(Integer.parseInt(n), ppid, tracer));
            }
            return out;
        }

        private boolean inThisCgroup(String path) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // "0::/uid_10932/pid_22080" (v2), "N:cpuacct:/uid_.../pid_..." (v1)
                    if (line.endsWith(mine)) return true;
                }
            } catch (Exception ignored) {
                // gone, or not ours to read
            }
            return false;
        }

        private static int number(String line) {
            try {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            } catch (RuntimeException e) {
                return -1;
            }
        }
    }
}
