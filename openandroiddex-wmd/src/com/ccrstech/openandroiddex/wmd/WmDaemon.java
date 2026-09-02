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
 *   SCREEN [0|1]                              -> OK [<0|1>]  the phone's own panel
 *   CPUSTAT                                   -> OK <busy> <total>  processor jiffies
 *   PROCS <pkg…>                              -> TOTAL <busy> <total> /
 *                                                PROC <pkg> <rssKb> <jiffies> … / END
 *   MOVEDISPLAY <task> <display>              -> OK          take a claimed window back
 *   AUDIOROUTE SET <type> <address|->         -> OK          pin media to a phone output
 *   AUDIOROUTE CLEAR                          -> OK          back to the phone's own policy
 *   ARM <ttlSeconds> <settings chain…>        -> OK          refresh the dead-man switch
 *   REQPUT <id> <cmd> <arg…>                  -> OK          launcher raises a PC request
 *   REQGET                                    -> REQ <id> <cmd> <arg…> … / END
 *   REQACK <id>                               -> OK          drop rows with id <= N
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

    /** How long RECLAIM waits for an activity that is still starting. */
    static final long RECLAIM_WAIT_MS = 2000L;

    /**
     * Launcher -> PC requests, waiting for the PC to drain them.
     *
     * The launcher cannot close another app's task or flip a system toggle; the PC can,
     * over adb. That channel used to be a ContentProvider the PC read with
     * `content query`, which costs a whole app_process VM per read — measured at 537 ms
     * on a Redmi Note 7 against 73 ms for a bare adb shell round trip. Polling it at
     * 150 ms meant booting a JVM roughly every 0.7 s for the entire session, which is
     * most of what made an idle desktop warm, and it put 150 ms of poll wait plus 537 ms
     * of query in front of every taskbar press.
     *
     * Same rows, same ack discipline, over the socket that is already open. See
     * RequestProvider, which still carries the payload when this daemon is not up.
     *
     * Bounded because the PC can go away (cable out, app killed) while the launcher keeps
     * queueing: past the cap the OLDEST row is dropped, so a stale backlog can never
     * stop the newest press from being seen.
     */
    private static final java.util.ArrayDeque<String[]> REQUESTS = new java.util.ArrayDeque<>();
    private static final int REQUESTS_MAX = 256;

    /** A component name and nothing else: the LAUNCH verb's only input check. */
    static final java.util.regex.Pattern COMPONENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+");

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

            case "LAUNCH": {
                // "LAUNCH <displayId> <pkg/cls>" — put an activity on a display the
                // launcher itself is not allowed to put one on.
                //
                // An UNTRUSTED virtual display refuses a launch from an app uid:
                // ActivityTaskManager logs "Permission Denial: … with launchDisplayId=N"
                // and the tap dies there, whatever the ActivityOptions say. Only a
                // system-level caller may do it, which is exactly what this process is.
                // Every virtual display before Android 11 is untrusted — there was no
                // such thing as a trusted one — and a manufacturer may withhold it
                // after. On a phone whose display IS trusted the launcher never asks.
                //
                // `am` rather than a reflected startActivityAsUser: that signature
                // gained and lost parameters across the releases this daemon runs on,
                // and the cost of a fork — once, on a click a human made and is already
                // waiting on — buys immunity from all of it. Nothing else in here can
                // afford this and nothing else does it.
                int display = i(a, 1);
                String comp = a.length > 2 ? a[2] : "";
                // This socket is reachable by any app on the phone, so the component is
                // held to the character set of a real one before it goes near a shell.
                // No quote can get through, so the quoting below cannot be escaped out of.
                if (!COMPONENT.matcher(comp).matches()) {
                    out.println("ERR not a component: " + comp);
                    return;
                }
                String reply = sh("am start --display " + display + " -n '" + comp + "'");
                // `am` reports a refusal on stdout and still exits 0, so the text is the
                // only answer there is.
                boolean failed = reply.contains("Error") || reply.contains("Exception");
                out.println(failed ? "ERR " + reply.replace('\n', ' ') : "OK");
                return;
            }

            case "HOME": {
                // "HOME [displayId]" — raise the desktop's own launcher over whatever is
                // covering it. THE WAY OUT, and the reason it exists twice:
                //
                // FRONT is the right verb and cannot be used on an old phone. It reorders
                // through getAllRootTaskInfosOnDisplay (Android 12) and a
                // WindowContainerTransaction (Android 11), neither of which exists on 10 —
                // and 10 is exactly where this matters, because a phone that cannot make a
                // trusted display gets no freeform, so every app opens fullscreen over the
                // launcher and takes the taskbar with it. There is then nothing left on
                // screen to press.
                //
                // `am start` on the launcher does the same job with an API that has been
                // there since the beginning. It is a no-op on a launcher already in front.
                int d = a.length > 1 ? i(a, 1) : displayFromDump(LAUNCHER);
                if (d < 0) {
                    out.println("ERR no desktop display");
                    return;
                }
                String r = sh("am start --display " + d
                        + " -n " + LAUNCHER + "/" + LAUNCHER + ".LauncherActivity");
                out.println(r.contains("Error") || r.contains("Exception")
                        ? "ERR " + r.replace('\n', ' ') : "OK " + d);
                return;
            }

            case "CLOSETOP": {
                // "CLOSETOP [displayId]" — close the front-most app window, launcher
                // excepted: it is the desktop itself, and removing it leaves a black
                // display with no way to bring anything back.
                //
                // removeTask is old enough to work everywhere (verified answering OK on
                // Android 10); only FINDING the task needed the 12+ API, and the dump
                // gives the same answer on every version.
                int d = a.length > 1 ? i(a, 1) : displayFromDump(LAUNCHER);
                if (d < 0) {
                    out.println("ERR no desktop display");
                    return;
                }
                int task = topTaskFromDump(d, LAUNCHER);
                if (task < 0) {
                    out.println("OK none");     // an empty desktop is not a failure
                    return;
                }
                Wm.removeTask(task);
                out.println("OK " + task);
                return;
            }

            case "RECLAIM": {
                // "RECLAIM <displayId> <pkg/cls>" — take a window the phone kept and put
                // it on the desktop.
                //
                // Our own screens cannot be started onto an untrusted display by anyone.
                // The launcher may not name the display (an app uid is refused), and
                // unnamed the platform puts the activity on the DEFAULT display instead
                // of the caller's. This process may not start them either — they are not
                // exported, and "not exported from uid 10225" applies to uid 2000 like
                // anyone else.
                //
                // So the activity is allowed to open wherever it lands, and then MOVED,
                // which needs only MANAGE_ACTIVITY_TASKS and no cooperation from the
                // activity at all. It is the same reclaim the PC already does for windows
                // the phone steals back mid-session.
                //
                // POLLED, because the caller asks the instant it starts the activity and
                // the task does not exist yet — a few hundred milliseconds of nothing is
                // the normal case, not a failure.
                int want = i(a, 1);
                String comp = a.length > 2 ? a[2] : "";
                if (want < 0 || !COMPONENT.matcher(comp).matches()) {
                    out.println("ERR bad arguments");
                    return;
                }
                long deadline = System.currentTimeMillis() + RECLAIM_WAIT_MS;
                int[] found = null;
                while (found == null && System.currentTimeMillis() < deadline) {
                    found = strayTask(comp, want);
                    if (found == null) sleep(120);
                }
                if (found == null) {
                    out.println("OK none");     // never appeared, or already where it belongs
                    return;
                }
                // moveStackToDisplay (below Android 12) wants the STACK, its replacement
                // wants the task. On the releases where they overlap the two ids are the
                // same number; where they are not, this is the difference between moving
                // the window and moving nothing.
                Wm.moveTaskToDisplay(Wm.hasRootTaskMove() ? found[0] : found[1], want);
                out.println("OK " + found[0]);
                return;
            }

            case "KEYBACK": {
                // "KEYBACK <displayId>" — the Back KEY (the verb BACK is taken: it sends a
                // task behind the others). Aimed at a display rather than at, aimed at a display rather than at
                // whatever the phone thinks is focused.
                //
                // An app cannot do this: INJECT_EVENTS is a system permission and the
                // launcher does not have it. This process does, which is the only reason
                // the desktop's own Back button can exist at all.
                int d = i(a, 1);
                if (d < 0) {
                    out.println("ERR no display");
                    return;
                }
                String r = sh("input -d " + d + " keyevent 4 2>&1");
                out.println(r.contains("Exception") ? "ERR " + r.replace('\n', ' ') : "OK");
                return;
            }

            case "REQPUT": {
                // "REQPUT <id> <cmd> <arg…>". The id is the LAUNCHER'S, not ours, and
                // that is the whole point: the same request is also queued in
                // RequestProvider, so the PC sees each press on both channels and its
                // existing "skip anything at or below the watermark" rule is what stops
                // it running twice. Two id spaces here would mean every taskbar press
                // executing twice — once per channel.
                //
                // The arg is the rest of the line, spaces and all (a package name never
                // has one, a config value can), so it is re-joined rather than read as a
                // single token. Same reason as ARM.
                if (a.length < 3) {
                    out.println("ERR no command");
                    return;
                }
                StringBuilder arg = new StringBuilder();
                for (int i = 3; i < a.length; i++) {
                    if (arg.length() > 0) arg.append(' ');
                    arg.append(a[i]);
                }
                try {
                    Long.parseLong(a[1]);
                } catch (Exception e) {
                    out.println("ERR bad id");
                    return;
                }
                synchronized (REQUESTS) {
                    REQUESTS.add(new String[]{a[1], a[2], arg.toString()});
                    while (REQUESTS.size() > REQUESTS_MAX) REQUESTS.poll();
                }
                out.println("OK");
                return;
            }

            case "REQGET": {
                // Read WITHOUT clearing, exactly as the v2 ContentProvider does: the PC
                // acks only once a request has actually been executed, so a PC that dies
                // between reading and acting delays the press by one poll instead of
                // eating it.
                synchronized (REQUESTS) {
                    for (String[] r : REQUESTS) {
                        out.println("REQ " + r[0] + " " + r[1] + " " + r[2]);
                    }
                }
                out.println("END");
                return;
            }

            case "REQACK": {
                long upto;
                try {
                    upto = Long.parseLong(a[1]);
                } catch (Exception e) {
                    out.println("ERR bad id");
                    return;
                }
                synchronized (REQUESTS) {
                    for (java.util.Iterator<String[]> it = REQUESTS.iterator(); it.hasNext(); ) {
                        if (Long.parseLong(it.next()[0]) <= upto) it.remove();
                    }
                }
                out.println("OK");
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

            // The phone's own panel, which is not the desktop's display: the desktop
            // has a power state of its own and keeps running while this is dark. No
            // argument reads back what we last set, since nothing else reports it — the
            // framework still believes a panel we blanked this way is lit.
            case "SCREEN": {
                if (a.length < 2) {
                    out.println("OK " + (Screen.isOff() ? "0" : "1"));
                    return;
                }
                Screen.power(!"0".equals(a[1]));
                out.println("OK");
                return;
            }

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

            // "AUDIOROUTE SET <type> <address|->" / "AUDIOROUTE CLEAR" — pin the
            // phone's MEDIA to one of its own outputs, or hand the choice back.
            // The one verb here that is not about windows: it lives on this
            // socket because selecting a route is MODIFY_AUDIO_ROUTING, held by
            // this uid and by no app. See Audio for the mechanism; any failure
            // (an OEM that moved the API, a refused permission) comes back as
            // ERR and the launcher opens the platform's own picker instead.
            case "AUDIOROUTE": {
                if (a.length < 2) {
                    out.println("ERR audioroute needs SET or CLEAR");
                    return;
                }
                switch (a[1].toUpperCase()) {
                    case "SET":
                        Audio.route(i(a, 2), a.length > 3 && !"-".equals(a[3]) ? a[3] : "");
                        out.println("OK");
                        return;
                    case "CLEAR":
                        Audio.clear();
                        out.println("OK");
                        return;
                    default:
                        out.println("ERR unknown audioroute " + a[1]);
                        return;
                }
            }

            case "MOVEDISPLAY": {
                // Take a task back. Deliberately addressed by task id and NOT routed
                // through taskById: the whole point is that the task is no longer on the
                // display it belongs to, so a display-scoped lookup would fail.
                //
                // TRANSLATED FIRST on anything below Android 12, where the call behind
                // this is moveStackToDisplay and wants the STACK. A task id handed to it
                // is not rejected — it is a different window's stack, or nobody's, so the
                // move silently does nothing or moves the wrong thing. Caught on a Redmi
                // on Android 10, where "reclaim this window" moved the desktop's own
                // launcher onto the phone.
                int target = i(a, 1);
                if (!Wm.hasRootTaskMove()) {
                    int stack = stackForTask(target);
                    if (stack >= 0) target = stack;
                }
                Wm.moveTaskToDisplay(target, i(a, 2));
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
            // A panel we blanked must not outlive the desktop that asked for it, and
            // this tick is the only thing that can notice: a shutdown hook was tried and
            // does not fire — ART leaves SIGTERM to the kernel, so `pkill` takes this
            // process with the panel still dark. Ahead of the ARM guard because a session
            // that never armed can still have blanked the phone, and the task-tree read
            // costs nothing while the panel is lit.
            if (Screen.isOff()) {
                boolean desktopGone = false;
                try {
                    desktopGone = Wm.displayHosting(LAUNCHER) <= 0;
                } catch (Throwable t) {
                    // A framework hiccup is not evidence that anything went away.
                }
                if (desktopGone) {
                    System.out.println("watchdog: desktop gone with the panel dark — restoring it");
                    Screen.restore();
                }
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
        // Before anything else: the undo below ends in force-stopping the launcher, and a
        // dark phone with no desktop left to turn it back on is the worst state to leave.
        Screen.restore();

        // The media-route pin is desktop policy set with this process's authority
        // (AUDIOROUTE SET), and this process is about to exit — left behind it would
        // quietly overrule the phone's own output switcher. The PC clears it on a
        // normal exit (restore_phone); this is the cable-pulled twin. Best effort:
        // with nothing pinned the framework answers an error, which is the same
        // outcome spelled differently.
        try {
            Audio.clear();
        } catch (Throwable t) {
            System.out.println("watchdog: no media-output pin to clear (" + t.getMessage() + ")");
        }

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



    /**
     * The stack a task belongs to, or -1 — the id moveStackToDisplay wants when the
     * platform is too old to take a task id.
     */
    private static int stackForTask(int taskId) {
        for (String raw : sh("dumpsys activity activities").split("\n")) {
            String line = raw.trim();
            if (!isTaskLine(line) || intAfter(line, " #") != taskId) continue;
            return intAfter(line, "StackId=");
        }
        return -1;
    }

    /**
     * A task running {@code component} on some display other than {@code want}.
     *
     * Returns {task id, stack id} or null. Read from the dump rather than the task API
     * for the reason spelled out on displayFromDump: the API is Android 12, and the
     * phones that need any of this are older.
     *
     * Matched on the ACTIVITY, never on the package: the launcher's own task carries the
     * same package name, and a package match would cheerfully move the desktop itself
     * onto the desktop.
     */
    private static int[] strayTask(String component, int want) {
        int display = -1;
        int task = -1;
        int stack = -1;
        for (String raw : sh("dumpsys activity activities").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Display #")) {
                display = intAfter(line, "Display #");
                task = -1;
                continue;
            }
            if (isTaskLine(line) && line.startsWith("* ")) {
                task = intAfter(line, " #");
                stack = intAfter(line, "StackId=");
                continue;
            }
            // The activity lives on the Hist line under its task, so the ids above are
            // still the ones this line belongs to.
            if (task >= 0 && display != want && display >= 0
                    && line.contains("ActivityRecord{") && matches(line, component)) {
                return new int[]{task, stack >= 0 ? stack : task};
            }
        }
        return null;
    }

    /** Uninterruptible enough for a poll; a spurious wake just polls again. */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    /**
     * Which display is hosting {@code pkg}, read from `dumpsys activity activities`.
     *
     * The task-API twin of this (Wm.displayHosting) needs getAllRootTaskInfos, which
     * arrived in Android 12. The dump has said the same thing since long before that
     * and says it on every version, which is the whole point: this path is only ever
     * taken by phones too old for the other one.
     */
    private static int displayFromDump(String pkg) {
        int display = -1;
        for (String raw : sh("dumpsys activity activities").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Display #")) {
                display = intAfter(line, "Display #");
            } else if (display >= 0 && isTaskLine(line) && line.contains(pkg)) {
                return display;
            }
        }
        return -1;
    }

    /**
     * Task id of the front-most task on {@code displayId} that is not {@code skipPkg},
     * or -1. The dump prints each display's tasks top to bottom, so the first match is
     * the window a person would call the one in front.
     */
    private static int topTaskFromDump(int displayId, String skipPkg) {
        boolean here = false;
        for (String raw : sh("dumpsys activity activities").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Display #")) {
                here = intAfter(line, "Display #") == displayId;
                continue;
            }
            if (!here || !line.startsWith("* ") || !isTaskLine(line)) continue;
            if (line.contains(skipPkg)) continue;
            return intAfter(line, " #");
        }
        return -1;
    }

    /**
     * Whether an ActivityRecord line belongs to the app we are reclaiming.
     *
     * BY PACKAGE, not by the exact activity: an app rarely lands on the activity that
     * was asked for. Chrome answers a launch with its first-run screen, and plenty of
     * apps answer with a trampoline — the task is the app's either way, and the task is
     * what moves.
     *
     * OUR OWN package is the exception and must match in full. The desktop's launcher
     * carries the same package as its Settings and Task Manager, and a package match
     * would move the desktop itself onto the desktop, which ends with an empty display.
     */
    private static boolean matches(String line, String component) {
        int slash = component.indexOf('/');
        String pkg = slash > 0 ? component.substring(0, slash) : component;
        if (LAUNCHER.equals(pkg)) return line.contains(component);
        return line.contains(pkg + "/");
    }

    /** Both spellings: Android 10 prints TaskRecord{…}, later versions Task{…}. */
    private static boolean isTaskLine(String line) {
        return line.contains("TaskRecord{") || line.contains("Task{");
    }

    /** The run of digits following {@code marker}, or -1. */
    private static int intAfter(String line, String marker) {
        int at = line.indexOf(marker);
        if (at < 0) return -1;
        int i = at + marker.length();
        int start = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        try {
            return i > start ? Integer.parseInt(line.substring(start, i)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
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
