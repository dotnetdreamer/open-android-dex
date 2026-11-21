package com.ccrstech.openandroiddex.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LinuxAppRunner against real processes, for test/linux-apps/runner_check.sh:
 * linux-app.sh under mksh, the proot the app ships (green-green-avk
 * v0.15_release with this repo's patch, built for this machine), kill(2)
 * through /bin/kill, and a real /proc. The census stands in for the cgroup
 * of ":linuxapps": every process of this user, which is what the container
 * runs as for the test and nothing else does. Run as an ordinary user, as the
 * app runs.
 *
 * <p>What each part shows is what it would have caught: every one of these
 * fails against the runner before the fixes (LinuxAppRunner.java as it was
 * staged on 2026-11-20), and passes now.
 */
public class RealRunner {
    static int pass;
    static int fail;

    static void ok(String s) {
        pass++;
        System.out.println("  ok   " + s);
    }

    static void bad(String s, String why) {
        fail++;
        System.out.println("  FAIL " + s + " -- " + why);
    }

    static final File DIR = new File("/tmp/.oadx-apps");
    static final long T0 = System.nanoTime();
    static final int SELF = (int) ProcessHandle.current().pid();
    static final String UID = uidOf(SELF);
    static volatile long slowSpawnMs;
    static int seq;

    static long clock() {
        return (System.nanoTime() - T0) / 1_000_000 + 1_000_000;
    }

    // ── kill(2) and /proc, as LinuxAppsService has them ──

    static final boolean VERBOSE = System.getenv("OADX_TEST_VERBOSE") != null;

    static int run(String... cmd) {
        if (VERBOSE && !"-0".equals(cmd[1])) System.out.println("       " + String.join(" ", cmd));
        try {
            return new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    static final LinuxAppRunner.Killer KILLER = new LinuxAppRunner.Killer() {
        @Override
        public boolean alive(int pid) {
            return run("kill", "-0", String.valueOf(pid)) == 0;
        }

        @Override
        public void quit(int pid) {
            run("kill", "-QUIT", String.valueOf(pid));
        }

        @Override
        public void kill(int pid) {
            run("kill", "-KILL", String.valueOf(pid));
        }

        @Override
        public void killGroup(int pgid) {
            run("kill", "-KILL", "--", "-" + pgid);
        }
    };

    static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static String field(String status, String key) {
        if (status == null) return null;
        for (String l : status.split("\n")) {
            if (l.startsWith(key + ":")) return l.substring(key.length() + 1).trim().split("\\s+")[0];
        }
        return null;
    }

    static String uidOf(int pid) {
        return field(read("/proc/" + pid + "/status"), "Uid");
    }

    /** This JVM's parent, its parent's, and so on: here before it, never its to count. */
    static final java.util.Set<String> ANCESTORS = new java.util.HashSet<>();

    static {
        String p = field(read("/proc/" + SELF + "/status"), "PPid");
        while (p != null && !p.equals("0") && ANCESTORS.add(p)) p = field(read("/proc/" + p + "/status"), "PPid");
    }

    /**
     * The cgroup of ":linuxapps" holds what that process started, and what
     * those started, wherever they were reparented to, and nothing that
     * started it. Here: this user's processes, less this JVM's ancestors --
     * the shell su started it from, which started in the same clock tick, and
     * which a sweep would otherwise take for something an app left behind
     * (it did, the first time).
     */
    static final LinuxAppRunner.Census CENSUS = () -> {
        List<LinuxAppRunner.Proc> out = new ArrayList<>();
        String[] names = new File("/proc").list();
        if (names == null) return null;
        for (String n : names) {
            if (!n.matches("[0-9]+") || n.equals("1") || ANCESTORS.contains(n)) continue;
            String st = read("/proc/" + n + "/status");
            if (st == null || !UID.equals(field(st, "Uid"))) continue;
            // A zombie is dead already (as LinuxAppsService's census has it).
            String state = field(st, "State");
            if ("Z".equals(state) || "X".equals(state)) continue;
            out.add(new LinuxAppRunner.Proc(Integer.parseInt(n),
                    Integer.parseInt(field(st, "PPid")), Integer.parseInt(field(st, "TracerPid"))));
        }
        return out;
    };

    static LinuxAppRunner.Spawner spawner() {
        return (id, display) -> {
            if (slowSpawnMs > 0) {
                try {
                    Thread.sleep(slowSpawnMs); // a slow pb.start()
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "/r/linux-app.sh", id);
            pb.environment().put("LINUX_ROOT", "/r");
            pb.environment().put("LINUX_PROOT", "/opt/proot/proot");
            pb.environment().put("PROOT_LOADER", "/opt/proot/loader");
            pb.environment().put("PROOT_LOADER_32", "/opt/proot/loader32-none");
            pb.environment().put("LINUX_STORAGE", "/nonexistent");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("/tmp/oadx-apps.log")));
            return pb.start();
        };
    }

    // ── the adapter: the one place the runner's API is used to make one ──
    static LinuxAppRunner make() {
        LinuxAppRunner r = new LinuxAppRunner(DIR, spawner(), KILLER, CENSUS, SELF,
                s -> System.out.println("       runner: " + s), RealRunner::clock, System::currentTimeMillis);
        return r;
    }

    static int closeAll(LinuxAppRunner r) {
        int n = r.closeAll();
        r.finish();
        return n;
    }
    // ── end of the adapter ──

    // ── requests, as oadx-run writes them ──

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String request(String display, String name, String command, long ageMs) throws IOException {
        return request(display, name, command, ageMs, SELF);
    }

    /** As oadx-run writes it, its pid last in the id: here this JVM's, alive, unless told another. */
    static String request(String display, String name, String command, long ageMs, int requester) throws IOException {
        String id = (System.currentTimeMillis() * 1000 + (seq++)) + "-" + requester;
        String text = "#!/bin/sh\n# oadx-run 1 display=" + display + " name=" + name + "\n"
                + "rm -f -- \"$0\"\n"
                + "export PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'\n"
                + "umask 0022\ncd -- '/tmp' 2>/dev/null || cd\n"
                + "exec /bin/sh -c " + q(command) + "\n";
        File tmp = new File(DIR, "." + id + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (ageMs > 0 && !tmp.setLastModified(System.currentTimeMillis() - ageMs)) throw new IOException("mtime");
        if (!tmp.renameTo(new File(DIR, id + ".req"))) throw new IOException("rename");
        return id;
    }

    static LinuxAppRunner.App start(LinuxAppRunner r, String display, String name, String command) throws IOException {
        String id = request(display, name, command, 0);
        LinuxAppRunner.App app = r.offer(id + ".req");
        if (app != null) watch(r, app);
        return app;
    }

    static void watch(LinuxAppRunner r, LinuxAppRunner.App app) {
        Thread t = new Thread(() -> r.watch(app), "watch-" + app.id);
        t.setDaemon(true);
        t.start();
    }

    // ── what is running ──

    /** Live (not zombie) processes of ours that are "sleep MARK". */
    static List<String> sleeps(String mark) {
        List<String> out = new ArrayList<>();
        String[] names = new File("/proc").list();
        if (names == null) return out;
        for (String n : names) {
            if (!n.matches("[0-9]+")) continue;
            String cmd = read("/proc/" + n + "/cmdline");
            if (cmd == null || !cmd.equals("sleep\0" + mark + "\0")) continue;
            String st = read("/proc/" + n + "/status");
            if (st == null || "Z".equals(field(st, "State"))) continue;
            String stat = read("/proc/" + n + "/stat");
            String[] f = stat == null ? new String[0] : stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            out.add("pid " + n + " ppid " + field(st, "PPid") + " tracer " + field(st, "TracerPid")
                    + (f.length > 3 ? " pgrp " + f[2] + " session " + f[3] : ""));
        }
        return out;
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            sleep(50);
        }
        return c.getAsBoolean();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Does a sleeps() line say KEY VALUE, as whole words. */
    static boolean has(String line, String key, int value) {
        return (" " + line + " ").contains(" " + key + " " + value + " ");
    }

    static int pidOf(LinuxAppRunner.App app) {
        String s = read(new File(DIR, app.id + ".pid").getPath());
        return s == null ? -1 : Integer.parseInt(s.trim());
    }

    /** Both of a tree app's sleeps up: the setsid'd one in a session of its own. */
    static boolean treeUp(String a, String b) {
        return sleeps(a).size() == 1 && sleeps(b).size() == 1;
    }

    static final String TREE = "setsid sleep %s & sleep %s & wait";

    public static void main(String[] args) throws Exception {
        if (!DIR.isDirectory() && !DIR.mkdirs()) throw new IOException("no " + DIR);
        System.out.println("  (runner as user " + UID + ", pid " + SELF + ")");

        // 1. A session's stop.
        LinuxAppRunner r = make();
        LinuxAppRunner.App a = start(r, ":1.0", "tree", String.format(TREE, "4001", "4002"));
        if (a == null || !waitFor(() -> treeUp("4001", "4002"), 8_000)) {
            bad("an app with a setsid'd child starts under proot", "sleeps " + sleeps("4001") + sleeps("4002"));
        } else {
            String s = sleeps("4001").get(0);
            int proot = pidOf(a);
            boolean apart = has(s, "tracer", proot) && !has(s, "pgrp", proot);
            if (apart) ok("the setsid'd child runs traced by the app's proot, out of its group: " + s);
            else bad("the setsid'd child is out of the app's group", s + ", proot " + proot);
            r.stopDisplay(":1", clock());
            boolean gone = waitFor(() -> sleeps("4001").isEmpty() && sleeps("4002").isEmpty(), 4_000);
            if (gone) ok("a session's stop takes the whole app, the setsid'd child too");
            else bad("a session's stop takes the whole app", "left: " + sleeps("4001") + sleeps("4002"));
        }

        // 2. Android's limit kills the proot itself.
        LinuxAppRunner.App b = start(r, ":1", "tree", String.format(TREE, "4011", "4012"));
        LinuxAppRunner.App c = start(r, ":2", "other", "sleep 4015");
        if (b == null || c == null || !waitFor(() -> treeUp("4011", "4012") && sleeps("4015").size() == 1, 8_000)) {
            bad("two apps start", "sleeps " + sleeps("4011") + sleeps("4012") + sleeps("4015"));
        } else {
            int proot = pidOf(b);
            if (proot <= 1) throw new IllegalStateException("no pid file for " + b.id); // never kill -1
            run("kill", "-KILL", String.valueOf(proot)); // what PhantomProcessRecord.killLocked does
            boolean gone = waitFor(() -> sleeps("4011").isEmpty() && sleeps("4012").isEmpty(), 4_000);
            if (gone) ok("an app whose proot Android killed leaves nothing running untraced, in its group or out of it");
            else bad("an app whose proot Android killed leaves nothing running untraced", "left: " + sleeps("4011") + sleeps("4012"));
            List<String> other = sleeps("4015");
            if (other.size() == 1 && has(other.get(0), "tracer", pidOf(c))) ok("and the other app runs on under its own proot: " + other.get(0));
            else bad("the other app runs on", "" + other);
            r.stopDisplay(":2", clock());
            if (waitFor(() -> sleeps("4015").isEmpty(), 4_000)) ok("which its own session's stop then closes");
            else bad("the other app closes with its session", "" + sleeps("4015"));
        }

        // 3. The service going while an app is starting.
        LinuxAppRunner r3 = make();
        slowSpawnMs = 800;
        String id = request(":1", "slow", String.format(TREE, "4021", "4022"), 0);
        AtomicReference<LinuxAppRunner.App> got = new AtomicReference<>();
        Thread t = new Thread(() -> {
            LinuxAppRunner.App app = r3.offer(id + ".req");
            got.set(app);
            if (app != null) watch(r3, app);
        });
        t.start();
        sleep(300);
        int closed = closeAll(r3);
        t.join(5_000);
        slowSpawnMs = 0;
        sleep(1_500);
        boolean never = waitFor(() -> sleeps("4021").isEmpty() && sleeps("4022").isEmpty(), 4_000);
        if (never) ok("the service going while an app starts leaves nothing running (" + closed + " closed at the time, the one starting closed as it started)");
        else bad("the service going while an app starts leaves nothing running", "left: " + sleeps("4021") + sleeps("4022"));
        String later = request(":1", "after", "sleep 4025", 0);
        if (r3.offer(later + ".req") == null && new File(DIR, later + ".req").exists())
            ok("and once it has gone, a request is left for oadx-run to run where it asked");
        else bad("nothing is claimed once the service has gone", "claimed");
        new File(DIR, later + ".req").delete();

        // 4. A request made before its session's stop, reaching here after it.
        LinuxAppRunner r4 = make();
        r4.stopDisplay(":3", clock());
        String old = request(":3", "stale", "sleep 4031", 2_000);
        LinuxAppRunner.App s = r4.offer(old + ".req");
        sleep(1_500);
        if (s == null && sleeps("4031").isEmpty() && !new File(DIR, old + ".run").exists())
            ok("a request made before its session closed is not started, and oadx-run's wait ends");
        else bad("a stale request is not started", "app " + s + ", sleeps " + sleeps("4031"));

        // 5. A request whose oadx-run is gone: its session died, and a new
        //    instance of the service finds it with no stop on record.
        LinuxAppRunner r5 = make();
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        int dead = (int) gone.pid();
        String orphan = request(":1", "orphan", "sleep 4041", 0, dead);
        LinuxAppRunner.App o = r5.offer(orphan + ".req");
        sleep(1_500);
        if (o == null && sleeps("4041").isEmpty() && new File(DIR, orphan + ".run").exists())
            ok("a request whose oadx-run has gone (pid " + dead + ") is not started, its claim left for the sweep");
        else bad("a request from a dead requester is not started", "app " + o + ", sleeps " + sleeps("4041"));
        new File(DIR, orphan + ".run").delete();

        // Whatever any FAIL above left: this run's own processes only.
        for (String m : new String[]{"4001", "4002", "4011", "4012", "4015", "4021", "4022", "4031", "4041"}) {
            for (String line : sleeps(m)) run("kill", "-KILL", line.split(" ")[1]);
        }
        System.out.println("  (" + pass + " ok, " + fail + " failed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
