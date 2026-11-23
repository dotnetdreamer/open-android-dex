package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Linux apps {@link LinuxAppsService} runs, each in a proot of its own
 * (linux-app.sh), and the bookkeeping that closes them again. Plain Java with
 * the process start, kill(2) and /proc handed in, so the rules can be tested
 * off a phone; the service is the Android half.
 *
 * <p>Why apps run there at all: Android's child-process limit closes the
 * processes of the least important Android process first, and the oldest of
 * those first (doc/linux-apps-process.md). The desktop is in ":linux"; these
 * are in ":linuxapps", one step less important, so the apps go before it.
 *
 * <p>The hand-over, start to finish, every step a rename, so that exactly one
 * side ever runs a request:
 * <ol>
 * <li>Guest-side oadx-run writes {@code ID.req} into the requests folder and
 *     waits for it to go.</li>
 * <li>{@link #offer} claims it by renaming it to {@code ID.run}. A request
 *     oadx-run took back first (it waits ten seconds) is not there to rename,
 *     and nothing happens.</li>
 * <li>linux-app.sh starts a proot, and inside it a shell claims {@code ID.run}
 *     by renaming it to {@code ID.go}, and runs that. When the proot never
 *     gets that far, oadx-run takes {@code ID.run} back at its ten seconds and
 *     runs the app itself: late, but never lost and never twice.</li>
 * </ol>
 *
 * <p>Closing an app is a SIGQUIT to its proot, not a kill. proot ignores
 * SIGTERM, and on SIGQUIT it SIGKILLs every process it traces, whatever
 * session or group each one is in, and exits (src/tracee/event.c,
 * kill_all_tracees; RemoteHost stops sshd the same way). A SIGKILL to the
 * group would miss everything an app moved out of it with setsid -- VS Code's
 * whole window, a terminal's shells -- and with proot gone, and no
 * PTRACE_O_EXITKILL, those run on untraced, outside the container's paths.
 * The group SIGKILL follows only if proot has not finished within
 * {@link #graceMs}.
 *
 * <p>When Android's limit kills a proot itself, proot cannot do that, so
 * once an app has ended, what of it is left running untraced is found in
 * this Android process's cgroup and killed ({@link #reap}).
 */
final class LinuxAppRunner {

    /** Starts linux-app.sh for one claimed request. */
    interface Spawner {
        Process spawn(String id, String display) throws IOException;
    }

    /** kill(2), for one process, for a process group, and for "is it still there". */
    interface Killer {
        boolean alive(int pid);

        /** SIGQUIT. To proot: SIGKILL everything you trace, and exit. */
        void quit(int pid);

        /** SIGKILL to one process. */
        void kill(int pid);

        /** SIGKILL to a process group. */
        void killGroup(int pgid);
    }

    /** One process of this Android process's cgroup, as /proc tells it. */
    static final class Proc {
        final int pid;
        final int ppid;
        /** TracerPid: the proot tracing it, 0 for none. */
        final int tracer;

        Proc(int pid, int ppid, int tracer) {
            this.pid = pid;
            this.ppid = ppid;
            this.tracer = tracer;
        }
    }

    /**
     * Every process Android counts under this Android process (its cgroup),
     * this process included or not; null where /proc will not say.
     */
    interface Census {
        List<Proc> ours();
    }

    /** Where a line of the log goes. */
    interface Log {
        void line(String s);
    }

    /** Request ids as oadx-run makes them: date +%s%N (or +%s), a dash, its pid. */
    static final Pattern ID = Pattern.compile("[0-9]{1,32}-[0-9]{1,10}");
    private static final Pattern HEADER = Pattern.compile(
            "^# oadx-run 1 display=([A-Za-z0-9:._-]*) name=([A-Za-z0-9:._+-]*)$");

    /** How often a watcher looks again for an app whose first process has handed over. */
    static final long POLL_MS = 500;

    /**
     * After the SIGQUIT, a second one: a process forked in the instant of the
     * first is traced but not yet in proot's list, and proot, not exiting while
     * it lives, would run it on. The second one finds it listed.
     */
    long quitAgainMs = 300;
    /** And then the group SIGKILL, for a proot that has not finished by now. */
    long graceMs = 2_000;

    static final class App {
        final String id;
        final String display;
        final String name;
        final Process proc;
        /**
         * android.os.SystemClock.elapsedRealtime() when oadx-run asked: the
         * claim time less the request's age. Comparable across processes, so
         * a session's stop (stamped in ":linux") can tell its own apps.
         */
        final long requestedAt;
        /** The process group, from ID.pid; -1 until linux-app.sh has written it. */
        volatile int pgid = -1;
        volatile boolean stopped;
        volatile boolean ended;

        App(String id, String display, String name, Process proc, long requestedAt) {
            this.id = id;
            this.display = display;
            this.name = name;
            this.proc = proc;
            this.requestedAt = requestedAt;
        }
    }

    private final File dir;
    private final Spawner spawner;
    private final Killer killer;
    private final Census census;
    private final int self;
    private final Log log;
    private final LongSupplier clock;
    private final LongSupplier wall;
    private final Map<String, App> running = new ConcurrentHashMap<>();
    /** Per X display, the last stop's time: a request made before it is that session's. */
    private final Map<String, Long> stops = new ConcurrentHashMap<>();
    /** Set by {@link #closeAll}: the service is going, and starts nothing more. Guarded by this. */
    private boolean closed;
    private final ScheduledThreadPoolExecutor timer;

    /**
     * @param self  the pid of the Android process this runs in
     * @param clock android.os.SystemClock.elapsedRealtime
     * @param wall  System.currentTimeMillis, the clock of the requests' file times
     */
    LinuxAppRunner(File dir, Spawner spawner, Killer killer, Census census, int self,
                   Log log, LongSupplier clock, LongSupplier wall) {
        this.dir = dir;
        this.spawner = spawner;
        this.killer = killer;
        this.census = census;
        this.self = self;
        this.log = log;
        this.clock = clock;
        this.wall = wall;
        timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "linux-apps-timer");
            t.setDaemon(true);
            return t;
        });
        // Its one thread goes when there is nothing to wait for.
        timer.setKeepAliveTime(5, TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
    }

    /**
     * A file appeared in the requests folder. Starts it if it is a request
     * and this side wins it; returns the app started, or null. Safe to call
     * twice for one file (the folder watch can report it twice): the second
     * rename finds nothing.
     *
     * <p>Two requests are not started: any once {@link #closeAll} has run
     * (oadx-run takes it back and runs it where it was asked for, as with no
     * ":linuxapps"), and one asked for by a session that has been stopped
     * since -- a click just before a logout, reaching here just after it.
     * Should either happen while the app is being started, it is closed again
     * the moment it is, never left running.
     */
    App offer(String name) {
        if (name == null || !name.endsWith(".req")) return null;
        String id = name.substring(0, name.length() - ".req".length());
        if (!ID.matcher(id).matches()) return null;
        synchronized (this) {
            if (closed) return null;
        }
        File req = new File(dir, name);
        File run = new File(dir, id + ".run");
        if (!req.renameTo(run)) return null; // taken back by oadx-run, or claimed already
        long requestedAt = requestedAt(run);
        String[] h = header(run);
        String display = h[0];
        String label = h[1];
        if (stoppedSince(display, requestedAt)) {
            // Gone with .run, oadx-run stops waiting and starts nothing.
            run.delete();
            log.line("did not start " + label + " (" + id + "): its session on " + display + " was closed after it asked");
            return null;
        }
        // oadx-run waits, alive, for as long as its request can be claimed;
        // gone, so is the session it was in (killed with it). Such a request
        // is a leftover a new instance of this service finds in the folder,
        // with no stop of that session on record: never started. ID.run
        // stays: with no oadx-run to take it back the sweep removes it, and
        // should this be wrong about it, oadx-run takes it back at its ten
        // seconds and runs it where it was asked for -- late, never lost.
        int requester = requesterOf(id);
        if (requester > 1 && !killer.alive(requester)) {
            log.line("did not start " + label + " (" + id + "): whoever asked for it has gone");
            return null;
        }
        Process p;
        try {
            p = spawner.spawn(id, display);
        } catch (IOException | RuntimeException e) {
            // ID.run stays: oadx-run takes it back at its ten seconds and runs
            // the app where it was asked for.
            log.line("could not start " + label + ": " + e);
            return null;
        }
        App app = new App(id, display, label, p, requestedAt);
        boolean late;
        synchronized (this) {
            // The same lock as closeAll's and stopDisplay's: either they saw
            // this app, or this sees them.
            late = closed || stoppedSince(display, requestedAt);
            running.put(id, app);
        }
        log.line("started " + label + " (" + id + ")" + (display.isEmpty() ? "" : " on " + display));
        if (late) {
            log.line("closing " + label + " (" + id + ") at once: its session closed while it started");
            stop(app);
        }
        return app;
    }

    /** The pid in a request's id (oadx-run's own, after the dash); -1 when it does not read. */
    static int requesterOf(String id) {
        int dash = id.lastIndexOf('-');
        try {
            return Integer.parseInt(id.substring(dash + 1));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The claim time, less how long the request waited (its file's age, never below none). */
    private long requestedAt(File request) {
        long age = wall.getAsLong() - request.lastModified();
        return clock.getAsLong() - Math.max(0, age);
    }

    private boolean stoppedSince(String display, long requestedAt) {
        Long s = stops.get(display);
        return s != null && requestedAt <= s;
    }

    /**
     * Wait for an app to end, then take what outlived its proot, and forget
     * it. Blocking: the service runs it on a thread of the app's own.
     *
     * <p>Ended means the whole process group is gone, not only the process
     * the service started: should setsid ever fork (it does for a caller that
     * already leads a group), the child that leads the group lives on.
     */
    void watch(App app) {
        int code;
        try {
            code = app.proc.waitFor();
            int pgid = groupOf(app);
            while (pgid > 0 && killer.alive(pgid)) Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        app.ended = true;
        // Android's limit kills processes one at a time: a proot killed leaves
        // what ran under it detached, in a Linux that no longer translates a
        // single path. What is left in its group goes with it, and what left
        // the group (setsid) is found by reap.
        int pgid = groupOf(app);
        if (pgid > 0) killer.killGroup(pgid);
        running.remove(app.id);
        new File(dir, app.id + ".pid").delete();
        log.line((app.stopped ? "closed " : "ended ") + app.name + " (" + app.id + "), exit " + code);
        reap();
    }

    /**
     * Close every app on this X display that was asked for at or before
     * {@code before}, and start none that was. Returns how many it closed: one
     * already on its way out (an earlier stop, its watcher not done yet) is
     * not counted again.
     */
    int stopDisplay(String display, long before) {
        List<App> apps;
        synchronized (this) {
            stops.merge(display, before, Math::max);
            apps = new ArrayList<>(running.values());
        }
        int n = 0;
        for (App app : apps) {
            if (app.display.equals(display) && app.requestedAt <= before && stop(app)) n++;
        }
        return n;
    }

    /** Close every app, and go on taking new ones; counted as {@link #stopDisplay} counts. */
    int stopAll() {
        int n = 0;
        for (App app : running()) {
            if (stop(app)) n++;
        }
        return n;
    }

    /**
     * Close every app and start no more: the service is going. Counted as
     * {@link #stopDisplay} counts. {@link #finish} is the rest of it.
     */
    int closeAll() {
        List<App> apps;
        synchronized (this) {
            closed = true;
            apps = new ArrayList<>(running.values());
        }
        int n = 0;
        for (App app : apps) {
            if (stop(app)) n++;
        }
        return n;
    }

    /**
     * After {@link #closeAll}, once proot has had its moment: whatever of
     * the apps is still there goes by force, then whatever left them, as
     * {@link #reap} finds it. On the timer, since the service's own threads go
     * with it.
     */
    void finish() {
        schedule(() -> {
            List<Integer> leaders = new ArrayList<>();
            for (App app : running()) {
                int g = groupOf(app);
                if (g > 0) {
                    killer.killGroup(g);
                    leaders.add(g);
                }
                destroy(app.proc);
            }
            // proot detaches what it traced as it exits, not as the kill is
            // sent; reap needs them untraced to see them. Half a second at most.
            for (int g : leaders) {
                for (int i = 0; i < 25 && killer.alive(g); i++) sleep(20);
            }
            reap();
        }, graceMs + POLL_MS);
    }

    List<App> running() {
        return new ArrayList<>(running.values());
    }

    /** False when it was stopped already. */
    private boolean stop(App app) {
        synchronized (app) {
            if (app.stopped) return false;
            app.stopped = true;
        }
        quit(app);
        schedule(() -> {
            if (!app.ended) quit(app);
        }, quitAgainMs);
        schedule(() -> {
            if (app.ended) return;
            int g = groupOf(app);
            if (g > 0) killer.killGroup(g);
            destroy(app.proc);
        }, graceMs);
        return true;
    }

    private void quit(App app) {
        int pgid = groupOf(app);
        if (pgid > 0) {
            // linux-app.sh's pid, which execs proot: proot itself.
            killer.quit(pgid);
        } else {
            // Before linux-app.sh has written its pid there is no proot to
            // ask, and killing the shell itself is the whole stop.
            destroy(app.proc);
        }
    }

    private static void destroy(Process p) {
        try {
            p.destroyForcibly();
        } catch (Throwable ignored) {
            // gone already
        }
    }

    /** On the timer; at once if the timer will take nothing more. */
    private void schedule(Runnable r, long delayMs) {
        try {
            timer.schedule(r, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            r.run();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Kill what is left running untraced in this Android process's cgroup
     * ({@link #orphans}): what an app left behind when Android's limit killed
     * its proot, and what an app moved out of its group before that. Returns
     * how many. Nothing where /proc will not say.
     */
    int reap() {
        int total = 0;
        // Again while a look finds something: one of them may have forked
        // between the look and its kill. Three looks at most.
        for (int round = 0; round < 3; round++) {
            List<Proc> ours = census.ours();
            if (ours == null) return total;
            // Read now, not only as stops read them: an app just started is
            // known by its pid file before anything has asked for it.
            Set<Integer> known = new HashSet<>();
            for (App a : running.values()) {
                int g = groupOf(a);
                if (g > 0) known.add(g);
            }
            List<Integer> left = orphans(ours, self, known);
            if (left.isEmpty()) break;
            for (int pid : left) killer.kill(pid);
            log.line("took " + left.size() + " process(es) an app left running without its proot: " + left);
            total += left.size();
        }
        return total;
    }

    /**
     * Which of this Android process's processes run on with no proot over
     * them. Every process an app runs is traced by its proot; untraced in
     * here are only:
     * <ul>
     * <li>this process itself;</li>
     * <li>what it started -- linux-app.sh, which becomes proot (its parent is
     *     this process, and so is a known app's pid);</li>
     * <li>a proot's first process, before it asks to be traced (its parent is
     *     one of those); and</li>
     * <li>what was traced by a proot that is gone. That is the lot this
     *     returns.</li>
     * </ul>
     */
    static List<Integer> orphans(List<Proc> ours, int self, Set<Integer> known) {
        Map<Integer, Integer> parent = new HashMap<>();
        for (Proc p : ours) parent.put(p.pid, p.ppid);
        List<Integer> out = new ArrayList<>();
        for (Proc p : ours) {
            if (p.pid == self || p.pid <= 1 || p.ppid == self) continue;
            if (p.tracer != 0) continue;
            if (known.contains(p.pid) || known.contains(p.ppid)) continue;
            Integer grand = parent.get(p.ppid);
            if (grand != null && grand == self) continue;
            out.add(p.pid);
        }
        return out;
    }

    /** ID.pid, as linux-app.sh writes it just before it execs proot. */
    private int groupOf(App app) {
        if (app.pgid > 0) return app.pgid;
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir, app.id + ".pid")))) {
            String line = r.readLine();
            if (line != null && line.trim().matches("[0-9]{1,10}")) {
                int pid = Integer.parseInt(line.trim());
                if (pid > 1) app.pgid = pid;
            }
        } catch (IOException | NumberFormatException ignored) {
            // not written yet
        }
        return app.pgid;
    }

    /** {display, name} from a request's second line; {"", "an app"} when it does not read. */
    static String[] header(File request) {
        try (BufferedReader r = new BufferedReader(new FileReader(request))) {
            r.readLine(); // #!/bin/sh
            String line = r.readLine();
            if (line != null) {
                Matcher m = HEADER.matcher(line);
                if (m.matches()) {
                    String name = m.group(2).isEmpty() ? "an app" : m.group(2);
                    return new String[]{display(m.group(1)), name};
                }
            }
        } catch (IOException ignored) {
            // the defaults
        }
        return new String[]{"", "an app"};
    }

    /**
     * An X display without its screen number: ":1.0" is ":1". The sessions
     * export ":1" and ":2", but GTK hands its children the display with the
     * screen on it, so every app XFCE starts says ":1.0" (seen on the S25),
     * and a session's stop, which names ":1", must reach them all.
     */
    static String display(String d) {
        int colon = d.lastIndexOf(':');
        if (colon < 0) return d;
        int dot = d.indexOf('.', colon + 1);
        return dot > colon + 1 ? d.substring(0, dot) : d;
    }

    /**
     * Leftovers from a session that went without cleaning up: requests no
     * oadx-run waits for any more, claims nothing ran, pid files of apps no
     * one watches. Only ones older than {@code olderThanMs}: a request a few
     * seconds old is someone's click, and oadx-run is still waiting on it.
     * Returns the requests young enough to start, oldest first.
     */
    List<String> sweep(long nowWallMs, long olderThanMs) {
        List<String> young = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return young;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            String n = f.getName();
            boolean ours = n.endsWith(".req") || n.endsWith(".run") || n.endsWith(".go")
                    || n.endsWith(".pid") || n.endsWith(".self")
                    || (n.startsWith(".") && n.endsWith(".tmp"));
            if (!ours) continue;
            if (nowWallMs - f.lastModified() > olderThanMs) {
                f.delete();
            } else if (n.endsWith(".req")) {
                young.add(n);
            }
        }
        return young;
    }
}
