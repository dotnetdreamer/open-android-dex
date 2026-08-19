package com.ccrstech.openandroiddex.wmd;

import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * The privileged half of the desktop, as a resident socket server.
 *
 * The launcher runs at uid 10534 and can never hold MANAGE_ACTIVITY_TASKS. This process
 * runs at uid 2000 and does. It holds no policy and draws nothing — it is a mechanism the
 * launcher drives.
 *
 * Transport is loopback TCP rather than a LocalSocket because SELinux policy between
 * untrusted_app and a shell-domain abstract socket is not dependable, whereas
 * app -> 127.0.0.1 is. The cost is that the launcher must declare INTERNET.
 *
 * Line-oriented ASCII so it can be driven by hand with `nc` during diagnosis:
 *
 *   PING                                      -> OK <uid>
 *   LIST <display>                            -> TASK <ix> <id> … (topmost first) / END
 *   STRIP <display> <task> <px>               -> OK          reserve a caption inset
 *   UNSTRIP <display> <task>                  -> OK          release it
 *   MOVE <display> <task> <x> <y>             -> OK <l> <t> <r> <b>
 *   BOUNDS <display> <task> <l> <t> <r> <b>   -> OK          instant, per-frame drag
 *   RESIZE <display> <task> <l> <t> <r> <b> <px> -> OK       animated one-shot + re-strip
 *   FRONT <display> <task>                    -> OK
 *   FOCUSABLE <display> <task> <0|1>          -> OK
 *   ALWAYSONTOP <display> <task> <0|1>        -> OK
 *   CLOSE <task>                              -> OK
 *   CPUSTAT                                   -> OK <busy> <total>  processor jiffies
 *   PROCS <pkg…>                              -> TOTAL <busy> <total> /
 *                                                PROC <pkg> <rssKb> <jiffies> … / END
 *   MOVEDISPLAY <task> <display>              -> OK          take a claimed window back
 *   ARM <ttlSeconds> <settings chain…>        -> OK          refresh the dead-man switch
 *   BYE                                       -> (closes)
 *   ERR <reason>                              on any failure
 *
 * MOVE uses applyTransaction, not a transition: measured 2.49 ms on SM-S938B, and
 * critically it does not enter the transition queue, so it can be issued per drag frame.
 * See doc/custom-titlebar-v2.md §0.
 */
public final class WmDaemon {

    static final int PORT = 7191;

    static final String LAUNCHER = "com.ccrstech.openandroiddex.launcher";
    static final String CAPTION_SERVICE = LAUNCHER + "/" + LAUNCHER + ".CaptionService";

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PORT;
        try {
            ServerSocket server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
            System.out.println("wmd listening on 127.0.0.1:" + port + " uid=" + Wm.uid());
            System.out.flush();
            Thread watchdog = new Thread(WmDaemon::watchdog, "wmd-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            while (true) {
                Socket client = server.accept();
                client.setTcpNoDelay(true);   // a drag is many tiny writes; Nagle would batch them
                new Thread(() -> serve(client), "wmd-client").start();
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void serve(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if ("BYE".equalsIgnoreCase(line)) return;
                try {
                    handle(line.split("\\s+"), out);
                } catch (Throwable t) {
                    out.println("ERR " + t.getMessage());
                }
            }
        } catch (IOException ignored) {
            // client went away mid-command; per-connection state dies with it
        }
    }

    private static void handle(String[] a, PrintWriter out) {
        switch (a[0].toUpperCase()) {
            case "PING":
                out.println("OK " + Wm.uid());
                return;

            case "DESKTOP": {
                // Which display is the desktop? Answered from the task tree, not from
                // accessibility: dead scrcpy sessions leave their virtual displays
                // behind, and only the live one is running our launcher.
                out.println("OK " + Wm.displayHosting(a.length > 1 ? a[1] : LAUNCHER));
                return;
            }

            case "ARM": {
                // "ARM <ttlSeconds> <settings chain…>". The rest of the line is a shell
                // command, spaces and all, so it is re-joined rather than read as one arg.
                //
                // This doubles as the heartbeat: the PC re-sends it every few seconds, and
                // it is the ONLY thing that refreshes the timer. The launcher talks to this
                // socket too, and treating its traffic as proof of life would keep the
                // switch armed forever after the cable was pulled.
                ttlMs = Long.parseLong(a[1]) * 1000L;
                StringBuilder sb = new StringBuilder();
                for (int ix = 2; ix < a.length; ix++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(a[ix]);
                }
                undoGlobals = sb.toString();
                lastArmMs = System.currentTimeMillis();
                out.println("OK");
                return;
            }

            case "LIST": {
                List<Wm.TaskRec> tasks = Wm.tasksOnDisplay(i(a, 1));
                for (int ix = 0; ix < tasks.size(); ix++) out.println(tasks.get(ix).line(ix));
                out.println("END");
                return;
            }

            case "STRIP": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                int px = i(a, 3);
                Rect caption = new Rect(t.bounds.left, t.bounds.top, t.bounds.right,
                        t.bounds.top + px);
                Rect app = new Rect(t.bounds);
                app.top += px;
                Object wct = Wm.newTransaction();
                Wm.setAppBounds(wct, t.token, app);
                // Publishing a captionBar inset source is what makes One UI's
                // DesktopModeWindowDecoration draw ITS caption into the strip — inside the
                // task's Decor container at z=30000, which is above the app window and
                // therefore above ours. Reserving the space via app bounds alone gets the
                // layout we want without waking the platform's decoration.
                // Pass "inset" as a 5th arg to opt back in for comparison.
                if (a.length > 4 && "inset".equals(a[4])) {
                    Wm.addCaptionInset(wct, t.token, caption);
                }
                Wm.applyTransaction(wct);
                out.println("OK " + caption.left + " " + caption.top + " "
                        + caption.right + " " + caption.bottom);
                return;
            }

            case "UNSTRIP": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                // The app-bounds override outlives the inset source, so clear it
                // explicitly — owner-binder death alone does not restore it.
                Wm.setAppBounds(wct, t.token, new Rect(t.bounds));
                Wm.removeCaptionInset(wct, t.token);
                Wm.applyTransaction(wct);
                out.println("OK");
                return;
            }

            case "MOVE": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Rect want = new Rect(t.bounds);
                want.offsetTo(i(a, 3), i(a, 4));
                Object wct = Wm.newTransaction();
                Wm.setBounds(wct, t.token, want);
                Wm.applyTransaction(wct);
                out.println("OK " + want.left + " " + want.top + " " + want.right + " " + want.bottom);
                return;
            }

            case "BOUNDS": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Rect want = new Rect(i(a, 3), i(a, 4), i(a, 5), i(a, 6));
                if (want.isEmpty()) {
                    // A cleared/empty bounds makes matchParentBounds() true, which drives
                    // every task below to VISIBLE_BEHIND_TRANSLUCENT or INVISIBLE — i.e.
                    // pauses or stops every other app on the display. Never allow it.
                    out.println("ERR empty bounds rejected");
                    return;
                }
                Object wct = Wm.newTransaction();
                Wm.setBounds(wct, t.token, want);
                Wm.applyTransaction(wct);
                out.println("OK");
                return;
            }

            case "RESIZE": {
                // One-shot geometry: maximise / restore / snap. Two things make the plain
                // BOUNDS path flicker for these, and this case fixes both:
                //
                //  1. It sets the task bounds AND re-insets the caption strip (app bounds) in
                //     ONE transaction, so the app never relayouts twice — BOUNDS then a
                //     separate STRIP a reconcile pass later is what leaves the content jumping
                //     under a momentarily-stale strip.
                //  2. It moves the pixels through a transition, so the platform animates the
                //     resize, instead of applyTransaction's unanimated jump. (applyTransaction
                //     stays the right tool for per-frame drag, where a transition cannot be
                //     issued every frame — see MOVE.)
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Rect want = new Rect(i(a, 3), i(a, 4), i(a, 5), i(a, 6));
                if (want.isEmpty()) {
                    out.println("ERR empty bounds rejected");
                    return;
                }
                int px = a.length > 7 ? i(a, 7) : 0;
                Rect app = new Rect(want);
                if (px > 0) app.top += px;
                Object wct = Wm.newTransaction();
                Wm.setBounds(wct, t.token, want);
                Wm.setAppBounds(wct, t.token, app);
                if (Wm.hasStartNewTransition()) {
                    Wm.startNewTransition(wct);
                } else {
                    Wm.applyTransaction(wct);
                }
                out.println("OK");
                return;
            }

            case "FRONT": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                Wm.reorder(wct, t.token, true);
                Wm.startNewTransition(wct);   // a raise should animate
                out.println("OK");
                return;
            }

            case "BACK": {
                // Just a restack. NOT a minimise: the fullscreen launcher occludes whatever
                // lands at the bottom, so the window manager stops the task and it looks
                // closed. Use HIDE for minimise.
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                Wm.reorder(wct, t.token, false);
                Wm.startNewTransition(wct);
                out.println("OK");
                return;
            }

            case "HIDE": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                Wm.setHidden(wct, t.token, true);
                Wm.startNewTransition(wct);   // minimising should animate
                out.println("OK");
                return;
            }

            case "SHOW": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                Wm.setHidden(wct, t.token, false);
                Wm.reorder(wct, t.token, true);    // restore to the front, like a real WM
                Wm.startNewTransition(wct);
                out.println("OK");
                return;
            }

            case "FOCUSABLE": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                Wm.setFocusable(wct, t.token, i(a, 3) != 0);
                Wm.applyTransaction(wct);
                out.println("OK");
                return;
            }

            case "ALWAYSONTOP": {
                Wm.TaskRec t = Wm.taskById(i(a, 1), i(a, 2));
                Object wct = Wm.newTransaction();
                Wm.setAlwaysOnTop(wct, t.token, i(a, 3) != 0);
                Wm.applyTransaction(wct);
                out.println("OK");
                return;
            }

            case "CLOSE":
                Wm.removeTask(i(a, 1));
                out.println("OK");
                return;

            // Raw processor jiffies for the taskbar's performance gauge, as
            // "OK <busy> <total>". It lives here for one reason: /proc/stat is
            // labelled proc_stat, which untrusted_app has not been allowed to
            // read since Android 9, while this daemon runs as shell (uid 2000)
            // and can. The arithmetic stays on the app side — a percentage is a
            // difference between two readings, and the daemon is stateless.
            case "CPUSTAT": {
                long[] cpu = readCpuJiffies();
                if (cpu == null) {
                    out.println("ERR no-proc-stat");
                    return;
                }
                out.println("OK " + cpu[0] + " " + cpu[1]);
                return;
            }

            // "PROCS <pkg…>" -> TOTAL <busy> <total> / PROC <pkg> <rssKb> <jiffies> … / END
            //
            // Per-app cost, for the Task Manager's list. Only this side can
            // answer it: an app has not been able to enumerate other processes
            // since Android 8, while this daemon is shell (uid 2000), a member
            // of the readproc group, and can read /proc/<pid> for anyone.
            //
            // Bounded by the caller's package list rather than dumping all
            // ~1000 processes: the reply stays small and the scan does one
            // cheap cmdline read per pid.
            //
            // MEASURED on SM-S938B as shell: cmdline, statm and stat all read
            // fine; smaps_rollup does NOT (permission denied), so this reports
            // RESIDENT memory and cannot report PSS. RSS counts shared pages —
            // the zygote's, mostly — in full for every process, so these
            // figures over-count and must never be presented as a share of
            // system memory.
            case "PROCS": {
                long[] cpu = readCpuJiffies();
                out.println("TOTAL " + (cpu == null ? "0 0" : cpu[0] + " " + cpu[1]));
                java.util.Set<String> wanted = new java.util.HashSet<>();
                for (int ix = 1; ix < a.length; ix++) wanted.add(a[ix]);
                if (wanted.isEmpty()) {
                    out.println("END");
                    return;
                }
                java.util.Map<String, long[]> totals = new java.util.HashMap<>();
                String[] pids = new java.io.File("/proc").list();
                if (pids != null) {
                    for (String pid : pids) {
                        if (pid.isEmpty() || pid.charAt(0) < '0' || pid.charAt(0) > '9') continue;
                        String pkg = processName(pid);
                        // An app's extra processes are named "pkg:something";
                        // they are the same app and their cost belongs to it.
                        if (pkg == null) continue;
                        int colon = pkg.indexOf(':');
                        if (colon > 0) pkg = pkg.substring(0, colon);
                        if (!wanted.contains(pkg)) continue;
                        long[] acc = totals.get(pkg);
                        if (acc == null) totals.put(pkg, acc = new long[2]);
                        acc[0] += rssKb(pid);
                        acc[1] += procJiffies(pid);
                    }
                }
                for (java.util.Map.Entry<String, long[]> e : totals.entrySet()) {
                    out.println("PROC " + e.getKey() + " " + e.getValue()[0]
                            + " " + e.getValue()[1]);
                }
                out.println("END");
                return;
            }

            case "MOVEDISPLAY": {
                // Take a task back. Deliberately addressed by task id and NOT routed
                // through taskById: the whole point is that the task is no longer on the
                // display it belongs to, so a display-scoped lookup would fail.
                Wm.moveTaskToDisplay(i(a, 1), i(a, 2));
                out.println("OK");
                return;
            }

            default:
                out.println("ERR unknown " + a[0]);
        }
    }

    // ── /proc readers ─────────────────────────────────────────────────────
    // All of these are here rather than in the launcher for one reason: the
    // launcher is untrusted_app, which has not been allowed to read /proc/stat
    // since Android 9 and cannot see other processes' /proc at all. This runs
    // as shell (uid 2000), in the readproc group.

    /** {busy, total} jiffies from /proc/stat, or null if it cannot be read. */
    private static long[] readCpuJiffies() {
        String line = null;
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/stat"))) {
            line = r.readLine();
        } catch (Throwable ignored) {
        }
        if (line == null || !line.startsWith("cpu ")) return null;
        long total = 0;
        long idle = 0;
        String[] f = line.trim().split("\\s+");
        for (int ix = 1; ix < f.length; ix++) {
            long v;
            try {
                v = Long.parseLong(f[ix]);
            } catch (NumberFormatException e) {
                continue;
            }
            total += v;
            // idle + iowait: waiting on storage is not the CPU working
            if (ix == 4 || ix == 5) idle += v;
        }
        return new long[]{total - idle, total};
    }

    /** An Android process is named for its package; "" and null mean not one. */
    private static String processName(String pid) {
        byte[] buf = readSmall("/proc/" + pid + "/cmdline");
        if (buf == null) return null;
        int end = 0;
        while (end < buf.length && buf[end] != 0) end++;
        if (end == 0) return null;
        String name = new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        // Native processes are paths ("/system/bin/…"); apps are package names.
        if (name.indexOf('/') >= 0 || name.indexOf('.') < 0) return null;
        return name;
    }

    /** Resident set size in KiB. NOT PSS — smaps_rollup is denied to shell. */
    private static long rssKb(String pid) {
        byte[] buf = readSmall("/proc/" + pid + "/statm");
        if (buf == null) return 0;
        String[] f = new String(buf, java.nio.charset.StandardCharsets.UTF_8).trim().split("\\s+");
        if (f.length < 2) return 0;
        try {
            // field 2 is resident pages; 4 KiB pages on every device we target
            return Long.parseLong(f[1]) * 4;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** utime + stime for a process, in jiffies. */
    private static long procJiffies(String pid) {
        byte[] buf = readSmall("/proc/" + pid + "/stat");
        if (buf == null) return 0;
        String line = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        // The comm field is parenthesised and may contain spaces, so fields are
        // counted from the LAST ')' rather than from the start of the line.
        int close = line.lastIndexOf(')');
        if (close < 0) return 0;
        String[] f = line.substring(close + 1).trim().split("\\s+");
        // after comm and state: utime is field 11, stime 12 (0-based here)
        if (f.length < 13) return 0;
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] readSmall(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return null;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (Throwable t) {
            return null; // process died mid-scan, or not ours to read
        }
    }

    private static int i(String[] a, int ix) {
        if (a.length <= ix) throw new IllegalArgumentException("missing arg " + ix);
        return Integer.parseInt(a[ix]);
    }

    // ── dead-man switch ────────────────────────────────────────────────────────────
    //
    // Ending a session normally is the PC's job: it kills scrcpy and undoes, over adb,
    // everything the launch turned on. Pull the cable and none of that can run — the
    // desktop vanishes but the phone keeps freeform windowing, a relaxed hidden-API
    // policy, our accessibility service, a granted overlay app-op, and this process.
    //
    // Nothing else on the phone can clean that up. The launcher is an ordinary app uid:
    // it cannot write secure settings, cannot reset an app-op and cannot kill this
    // process. This one runs at uid 2000 — the same authority the PC's `adb shell` had —
    // and `setsid` means it outlives the cable. So it does the undo itself.

    /** Last ARM from the PC. Only ARM refreshes it; see the verb for why. */
    private static volatile long lastArmMs = 0L;
    /** How long silence has to last before the PC counts as gone. */
    private static volatile long ttlMs = 0L;
    /** The one part of the undo only the PC can know. Null until armed. */
    private static volatile String undoGlobals = null;

    private static void watchdog() {
        while (true) {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                return;
            }
            String globals = undoGlobals;
            if (globals == null) continue;                                  // never armed
            long silent = System.currentTimeMillis() - lastArmMs;
            if (silent < ttlMs) continue;                                   // PC still there

            // Silence alone is not enough. The PC also goes quiet for a few seconds
            // whenever it cycles the session itself — the Settings window's "restart
            // desktop" — and cleaning up under a desktop that is coming right back would
            // be worse than not cleaning up at all. The display having gone too is what
            // separates "restarting" from "gone".
            int display;
            try {
                display = Wm.displayHosting(LAUNCHER);
            } catch (Throwable t) {
                // A framework hiccup is not evidence of anything. Wait for a clean read.
                System.out.println("watchdog: cannot read the task tree (" + t + ") — holding");
                continue;
            }
            if (display > 0) continue;

            System.out.println("watchdog: no PC for " + (silent / 1000)
                    + "s and no desktop display — undoing the session");
            cleanup(globals);
            System.out.println("watchdog: done, exiting");
            System.out.flush();
            System.exit(0);
        }
    }

    /**
     * The same undo the PC would have run, minus the parts that only make sense with a
     * cable attached. Ordered so this process is the last thing standing.
     */
    private static void cleanup(String globals) {
        sh(globals);

        // Read the accessibility list NOW rather than taking it from the ARM: a session
        // can be hours long, and anything the user switched on in the meantime — a
        // screen reader, a password manager — is in this same list. Writing back a
        // reading from session start would silently turn it off.
        String rest = withoutCaptionService(sh("settings get secure enabled_accessibility_services"));
        sh(rest.isEmpty()
                ? "settings delete secure enabled_accessibility_services; "
                        + "settings put secure accessibility_enabled 0"
                : "settings put secure enabled_accessibility_services '" + rest + "'");

        // App-ops and the widget-bind whitelist the PC granted over adb without
        // the user ever seeing a prompt. Widgets already on the desktop stay
        // bound; this only closes the door on silent NEW binds. No-op (an error
        // string, swallowed) before Android 12, which has no `cmd appwidget`.
        sh("appops set " + LAUNCHER + " SYSTEM_ALERT_WINDOW default; "
                + "appops set " + LAUNCHER + " ACCESS_RESTRICTED_SETTINGS default; "
                + "cmd appwidget revokebind --package " + LAUNCHER + " --user 0");

        sh("am force-stop " + LAUNCHER);
    }

    /**
     * Drop our caption service from a colon-separated accessibility list, leaving every
     * other entry untouched. Mirrors {@code without_caption_service} in adb.rs — the PC
     * does this when it can reach the phone, this side when it cannot.
     */
    static String withoutCaptionService(String list) {
        if (list == null) return "";
        String s = list.trim();
        // `settings get` prints the literal string "null" for an unset key.
        if (s.isEmpty() || "null".equals(s)) return "";
        StringBuilder out = new StringBuilder();
        for (String part : s.split(":")) {
            String p = part.trim();
            if (p.isEmpty() || CAPTION_SERVICE.equals(p)) continue;
            if (out.length() > 0) out.append(':');
            out.append(p);
        }
        return out.toString();
    }

    /** Run a shell command with this process's own authority, stdout back. */
    private static String sh(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return "";
        try {
            // Absolute: this process is exec'd by app_process, not by a login shell, and
            // is not owed a usable PATH.
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r =
                         new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString().trim();
        } catch (Throwable t) {
            System.out.println("watchdog: `" + cmd + "` failed: " + t);
            return "";
        }
    }
}
