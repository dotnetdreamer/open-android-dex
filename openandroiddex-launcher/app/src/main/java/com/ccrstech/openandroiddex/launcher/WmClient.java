package com.ccrstech.openandroiddex.launcher;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Transport to the shell-uid daemon (openandroiddex-wmd) over loopback TCP.
 *
 * Pure mechanism: socket lifecycle and line I/O, nothing else. All policy lives in the
 * caller. Every call blocks, so nothing here may run on the UI thread — use
 * {@link #post} for that.
 *
 * Requires android.permission.INTERNET: without it an app cannot create any socket,
 * including one to 127.0.0.1.
 */
final class WmClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 7191;
    private static final int CONNECT_TIMEOUT_MS = 400;
    private static final int READ_TIMEOUT_MS = 1500;

    /** One root task as reported by the daemon's LIST, topmost first. */
    static final class Task {
        int index;
        int taskId;
        int displayId;
        int windowingMode;
        boolean visible;
        int left, top, right, bottom;
        int appLeft, appTop, appRight, appBottom;
        String pkg = "";
        String activity = "";

        boolean isFreeform() {
            return windowingMode == 5;
        }

        /**
         * True when the reserved strip is exactly {@code px} tall AND still aligned to the
         * task.
         *
         * Checking only {@code appTop > top} is not enough: the app-bounds override is set
         * once and does NOT follow the task when it moves, so a dragged window keeps a
         * stale override and its content ends up offset from its own frame. Comparing the
         * full rect makes a moved task look un-stripped, so the reconcile re-applies it.
         */
        boolean hasStrip(int px) {
            return appLeft == left && appRight == right && appBottom == bottom
                    && appTop == top + px;
        }
    }

    private final HandlerThread thread = new HandlerThread("wm-client");
    private final Handler io;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    WmClient() {
        thread.start();
        io = new Handler(thread.getLooper());
    }

    void post(Runnable r) {
        io.post(r);
    }

    void postDelayed(Runnable r, long delayMs) {
        io.postDelayed(r, delayMs);
    }

    void shutdown() {
        io.post(this::close);
        thread.quitSafely();
    }

    /** @return true if the daemon answered PING. Cheap enough to call on a poll. */
    boolean isUp() {
        String reply = request("PING");
        return reply != null && reply.startsWith("OK");
    }

    /**
     * Display id of the live desktop, or -1.
     *
     * Asked of the daemon rather than derived from accessibility windows: a freshly
     * created virtual display is not reliably enumerated there, and dead scrcpy sessions
     * leave virtual displays behind that look identical to the live one.
     */
    int desktopDisplay() {
        String reply = request("DESKTOP " + "com.ccrstech.openandroiddex.launcher");
        if (reply == null || !reply.startsWith("OK ")) return -1;
        try {
            return Integer.parseInt(reply.substring(3).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The phone's own panel, on or off. False if the daemon could not be reached.
     *
     * Not the desktop's display: scrcpy gives that one a power state of its own, so it
     * keeps running at full speed while the panel is dark. The call belongs on the other
     * end of this socket because SurfaceControl takes a power mode only from the shell's
     * uid, which this app will never have — see the daemon's Screen.
     */
    boolean screen(boolean on) {
        return ok(request("SCREEN " + (on ? 1 : 0)));
    }

    /**
     * Root tasks on {@code displayId}, topmost first — or null if the daemon could not be
     * reached or the reply was truncated.
     *
     * Null and empty mean very different things and callers must not conflate them: empty
     * is "the display really has no tasks", null is "no information this time". A dropped
     * socket read as "nothing is open" would tear down every caption and un-pin every
     * minimised window, on a poll that runs several times a second.
     */
    List<Task> list(int displayId) {
        List<Task> tasks = new ArrayList<>();
        synchronized (this) {
            if (!connect()) return null;
            try {
                out.println("LIST " + displayId);
                String line;
                boolean sawEnd = false;
                while ((line = in.readLine()) != null) {
                    if ("END".equals(line)) { sawEnd = true; break; }
                    if (line.startsWith("ERR")) break;
                    Task t = parse(line);
                    if (t != null) tasks.add(t);
                }
                if (!sawEnd) {
                    close();              // EOF mid-list: the daemon went away
                    return null;
                }
            } catch (Exception e) {
                close();
                return null;
            }
        }
        return tasks;
    }

    /**
     * <pre>
     * idx  0     1  2  3       4    5       6   7 8 9 10  11 12 13 14  15  16
     *      TASK  ix id display mode actType vis l t r b   al at ar ab  pkg activity
     * </pre>
     * 17 tokens. Getting this off by one is silent and looks like nonsense data —
     * the window title renders as the app-bounds bottom coordinate.
     */
    private static Task parse(String line) {
        String[] p = line.split("\\s+");
        if (p.length < 17 || !"TASK".equals(p[0])) return null;
        try {
            Task t = new Task();
            t.index = Integer.parseInt(p[1]);
            t.taskId = Integer.parseInt(p[2]);
            t.displayId = Integer.parseInt(p[3]);
            t.windowingMode = Integer.parseInt(p[4]);
            t.visible = "1".equals(p[6]);
            t.left = Integer.parseInt(p[7]);
            t.top = Integer.parseInt(p[8]);
            t.right = Integer.parseInt(p[9]);
            t.bottom = Integer.parseInt(p[10]);
            t.appLeft = Integer.parseInt(p[11]);
            t.appTop = Integer.parseInt(p[12]);
            t.appRight = Integer.parseInt(p[13]);
            t.appBottom = Integer.parseInt(p[14]);
            t.pkg = p[15];
            t.activity = p[16];
            return t;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean strip(int displayId, int taskId, int px) {
        return ok(request("STRIP " + displayId + " " + taskId + " " + px));
    }

    boolean unstrip(int displayId, int taskId) {
        return ok(request("UNSTRIP " + displayId + " " + taskId));
    }

    boolean move(int displayId, int taskId, int x, int y) {
        return ok(request("MOVE " + displayId + " " + taskId + " " + x + " " + y));
    }

    boolean bounds(int displayId, int taskId, int l, int t, int r, int b) {
        return ok(request("BOUNDS " + displayId + " " + taskId + " "
                + l + " " + t + " " + r + " " + b));
    }

    /**
     * One-shot maximise / restore / snap: set the task rect AND re-inset the caption strip
     * ({@code px} tall) in a single animated transition. Smoother than {@link #bounds} for a
     * user-driven resize, which BOUNDS leaves to jump and then re-strips a pass later.
     */
    boolean resize(int displayId, int taskId, int l, int t, int r, int b, int px) {
        return ok(request("RESIZE " + displayId + " " + taskId + " "
                + l + " " + t + " " + r + " " + b + " " + px));
    }

    boolean front(int displayId, int taskId) {
        return ok(request("FRONT " + displayId + " " + taskId));
    }

    /** Restack to the bottom. Not a minimise — see {@link #hide}. */
    boolean sendToBack(int displayId, int taskId) {
        return ok(request("BACK " + displayId + " " + taskId));
    }

    /**
     * Minimise: the task stays attached to the display, just not visible, so it can be
     * restored. Distinct from BACK, which only restacks — the fullscreen launcher then
     * occludes the task and the window manager stops it, which looks like a close.
     */
    boolean hide(int displayId, int taskId) {
        return ok(request("HIDE " + displayId + " " + taskId));
    }

    /** Un-minimise and raise. */
    boolean show(int displayId, int taskId) {
        return ok(request("SHOW " + displayId + " " + taskId));
    }

    boolean close(int taskId) {
        return ok(request("CLOSE " + taskId));
    }

    /**
     * Processor jiffies as {busy, total}, or null when the daemon is not there.
     *
     * Asked of the daemon rather than read directly because /proc/stat is
     * labelled proc_stat, which untrusted_app has not been allowed to read
     * since Android 9 — the daemon is shell (uid 2000) and can. Blocking
     * socket I/O: never call this from the main thread.
     */
    long[] cpuStat() {
        String reply = request("CPUSTAT");
        if (!ok(reply)) return null;
        String[] parts = reply.trim().split("\s+");
        if (parts.length < 3) return null;
        try {
            return new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One app's cost: resident bytes and cumulative processor jiffies. */
    static final class ProcCost {
        long rssBytes;
        long jiffies;
    }

    /**
     * Per-app cost for the packages named, plus the system's total jiffies
     * under the key {@link #TOTAL_KEY} so a percentage can be worked out
     * against the SAME scan. Null when the daemon is not there.
     *
     * Asked of the daemon because an app cannot enumerate other processes —
     * getRunningAppProcesses has returned only the caller's own since Android
     * 8. Blocking socket I/O: never call this from the main thread.
     */
    static final String TOTAL_KEY = " total";

    java.util.Map<String, ProcCost> procs(java.util.Collection<String> packages) {
        if (packages == null || packages.isEmpty()) return null;
        StringBuilder cmd = new StringBuilder("PROCS");
        for (String p : packages) cmd.append(' ').append(p);
        java.util.Map<String, ProcCost> costs = new java.util.HashMap<>();
        synchronized (this) {
            if (!connect()) return null;
            try {
                out.println(cmd.toString());
                String line;
                boolean sawEnd = false;
                while ((line = in.readLine()) != null) {
                    if ("END".equals(line)) { sawEnd = true; break; }
                    if (line.startsWith("ERR")) break;
                    String[] f = line.trim().split("\\s+");
                    try {
                        if (f.length >= 3 && "TOTAL".equals(f[0])) {
                            ProcCost total = new ProcCost();
                            total.jiffies = Long.parseLong(f[2]); // total, not busy
                            costs.put(TOTAL_KEY, total);
                        } else if (f.length >= 4 && "PROC".equals(f[0])) {
                            ProcCost c = new ProcCost();
                            c.rssBytes = Long.parseLong(f[2]) * 1024L;
                            c.jiffies = Long.parseLong(f[3]);
                            costs.put(f[1], c);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!sawEnd) {
                    close();              // EOF mid-list: the daemon went away
                    return null;
                }
            } catch (Exception e) {
                close();
                return null;
            }
        }
        return costs;
    }

    private static boolean ok(String reply) {
        return reply != null && reply.startsWith("OK");
    }

    /**
     * One request, one reply, with a single reconnect retry.
     *
     * The retry is not belt-and-braces: Socket#isConnected() stays true for the life of
     * the object once it has ever connected, so a socket whose peer has gone away still
     * looks healthy. Writes then succeed into the void and readLine() returns null at
     * EOF rather than throwing — so without treating null as a dead connection, a daemon
     * restart wedges the client permanently.
     */
    private synchronized String request(String command) {
        String reply = attempt(command);
        if (reply != null) return reply;
        close();
        return attempt(command);
    }

    private String attempt(String command) {
        if (!connect()) return null;
        try {
            out.println(command);
            if (out.checkError()) {
                close();
                return null;
            }
            String reply = in.readLine();
            if (reply == null) close();     // EOF: peer is gone
            return reply;
        } catch (Exception e) {
            close();
            return null;
        }
    }

    /** Lazily (re)connects. A dead daemon just means every call returns null. */
    private boolean connect() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return true;
        close();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out = new PrintWriter(s.getOutputStream(), true);
            if (!everConnected) {
                everConnected = true;
                DexLog.step("wmd", "connected to the window daemon on " + HOST + ":" + PORT);
            }
            return true;
        } catch (Exception e) {
            close();
            // Once per outage, not once per call: a missing daemon means no
            // window chrome at all, and that is worth one line — but this runs
            // on every window operation.
            if (everConnected || !reportedFailure) {
                reportedFailure = true;
                everConnected = false;
                DexLog.warn("wmd", "window daemon unreachable on " + HOST + ":" + PORT
                        + " — no titlebars, no window control (" + e + ")");
            }
            return false;
        }
    }

    /** Whether the last connect attempt got through, so outages are logged once. */
    private boolean everConnected;
    private boolean reportedFailure;

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    static boolean onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
