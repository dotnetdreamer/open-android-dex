package com.ccrstech.openandroiddex.wmd;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's own screen as a game controller for its apps' windows on the
 * computer (doc/game-mode.md, "The phone as a controller"): the server's half.
 *
 * <p>The controller is the launcher's — {@code PhoneGamepad}, a window over the
 * phone's screen with two sticks, a d-pad, A B X Y, the shoulders and the
 * triggers, which this process, being no app, could not draw. Its state comes
 * here over loopback and goes into a gamepad of the phone's own
 * ({@link AppGamepads#PHONE}), aimed at the window in front on the computer —
 * never to the computer and back, so a cable or Wi‑Fi adds nothing to what a
 * press takes. The computer only offers it, and says which window is in front.
 *
 * <p><b>Whose it is.</b> The window the computer last said came to the front
 * ({@code focus}), and it stays that window's while the computer's front is
 * something else of the computer's: the controller is for the phone's games,
 * whatever else is used on the computer meanwhile. The next of the phone's
 * windows to come to the front takes it over, with whatever is held. With no
 * window in front yet, the one window open, if there is only one.
 *
 * <p><b>Who gets in</b>, as for the touchpad ({@link AppPad}): a port of its own,
 * opened only while the computer offers the controller, taking one connection
 * at a time — from the launcher's uid by the kernel's account ({@link Peers}),
 * with the token this run made and handed it through the launcher's own
 * provider, which only the shell may call. All that can be done through here is
 * what a controller does, in the phone's own windows.
 *
 * <p><b>What it changes on the phone</b>, as the touchpad: the launcher let draw
 * over other apps while it is offered, and put in App Standby's active bucket.
 * The notification that opens it is shown only while the computer says one of
 * the phone's windows has its game controls on ({@code notice}); the windows'
 * menus offer it always.
 *
 * <p>The line protocol, launcher to here, one JSON object a line: {@code gamepad}
 * (the token, first), {@code state} (b: the buttons, as the report has them;
 * hat; lx, ly, rx, ry: the sticks; lt, rt: the triggers), {@code hidden} (on:
 * Android keeps the controller off the phone's screen), and {@code top},
 * answered with the task in front of the phone's own screen. Here to the
 * launcher, as things change: {@code target} (pkg, label: the app the
 * controller plays now, empty for none), {@code held} (on: Android 12 and
 * older, its input waits for a click in the window on the computer) and
 * {@code refused} (message: the phone makes no controller). To the computer:
 * {@code {"t":"controller","k":"open" | "closed" | "hidden",…}}.
 */
final class AppController {

    /** The computer, on whichever control connection it has now. */
    interface Sink {
        void send(JSONObject message);
    }

    /** Longer than this, a line is no controller's: the connection is dropped. */
    private static final int LINE_MAX = 4096;
    /** The controller says who it is at once; a connection that does not is let go. */
    private static final int HELLO_MS = 5000;
    /** What the controller is, to the launcher's overlay app-op it needs ({@link LauncherOverlay}). */
    private static final String OVERLAY_NEED = "the game controller";
    /** The buttons a report may hold: the eleven the Xbox 360 key layout names (AppGamepads). */
    static final int BUTTONS = 0x0001 | 0x0002 | 0x0008 | 0x0010 | 0x0040 | 0x0080
            | 0x0400 | 0x0800 | 0x1000 | 0x2000 | 0x4000;
    private static final byte[] NEUTRAL = new byte[AppGamepads.REPORT];

    private final AppServer server;
    private final Sink computer;
    private final SecureRandom random = new SecureRandom();
    /** Lines to the controller, one at a time. */
    private final Object writing = new Object();
    /**
     * Every report, every change of the window in front, one at a time and in
     * order: making a pad waits for Android, and neither the controller's reading
     * nor a window's input thread should wait with it.
     */
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apps-controller");
        t.setDaemon(true);
        return t;
    });

    // Guarded by this.
    private ServerSocket listening;
    private String token;
    private Socket pad;
    private OutputStream out;
    /** The launcher's app id, as the package manager says; -1 until asked. */
    private int launcherAppId = -1;

    // The work thread's.
    /** The last state the controller sent; neutral before. */
    private byte[] last = NEUTRAL;
    /** The window it was last aimed at, and what the controller was told of it. */
    private AppWindow aimed;
    private String toldTarget;
    private boolean toldHeld;
    private boolean toldRefused;

    AppController(AppServer server, Sink computer) {
        this.server = server;
        this.computer = computer;
    }

    /**
     * The computer offers the controller: listening, the launcher let draw it,
     * and told where to find this — and whether the phone's shade offers it
     * ({@code notice}), or, with {@code open}, to put it up now. The answer
     * says how that went.
     */
    synchronized JSONObject arm(boolean open, boolean notice) throws Exception {
        JSONObject r = new JSONObject().put("t", "controllerOn").put("open", open).put("notice", notice);
        if (listening == null || listening.isClosed()) {
            listening = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            byte[] b = new byte[16];
            random.nextBytes(b);
            StringBuilder hex = new StringBuilder();
            for (byte x : b) hex.append(String.format("%02x", x & 0xff));
            token = hex.toString();
            ServerSocket s = listening;
            Thread t = new Thread(() -> acceptLoop(s), "apps-controller-port");
            t.setDaemon(true);
            t.start();
            AppServer.log("controller: listening on 127.0.0.1:" + s.getLocalPort());
        }
        LauncherOverlay.need(OVERLAY_NEED);
        // Not idle, or its connection here is cut off the charger (AppPad says why).
        String standby = AppTasks.exec(5, "cmd", "activity", "set-standby-bucket", WmDaemon.LAUNCHER, "active");
        if (!standby.trim().isEmpty()) {
            AppServer.log("controller: the launcher's standby bucket was not set (" + standby.trim() + ")");
        }
        JSONObject arg = new JSONObject().put("port", listening.getLocalPort()).put("token", token)
                .put("notice", notice);
        JSONObject said;
        try {
            said = Providers.call(AppServer.LINUX_PROVIDER, open ? "gamepad.open" : "gamepad.offer", arg);
        } catch (Exception e) {
            // No launcher, or one from before the controller: the computer installs it if a person asked.
            return r.put("ok", false).put("launcher", false).put("message", String.valueOf(e));
        }
        return r.put("ok", said.optBoolean("ok", false)).put("launcher", true)
                .put("offered", said.optBoolean("offered", false))
                .put("message", said.optString("message"));
    }

    /**
     * The computer no longer offers it — the last of the phone's windows there
     * closed, or it is going: the controller and its notification down, the port
     * closed, its pad gone, and the overlay app-op taken back if it was given
     * here and nothing else needs it.
     */
    synchronized void disarm(String why) {
        boolean desktop = false;
        boolean told = false;
        try {
            JSONObject said = Providers.call(AppServer.LINUX_PROVIDER, "gamepad.close", null);
            desktop = said.optBoolean("desktop", false);
            told = true;
        } catch (Exception e) {
            // no launcher to tell; nothing of its is up
        }
        closeQuietly(listening);
        listening = null;
        closeQuietly(pad);
        pad = null;
        out = null;
        token = null;
        work.execute(this::let);
        LauncherOverlay.release(OVERLAY_NEED, desktop || !told);
        AppServer.log("controller: no longer offered (" + why + ")" + (told ? "" : ", the launcher unreachable"));
    }

    synchronized boolean armed() {
        return listening != null;
    }

    /**
     * The window in front on the computer changed, or one ended: the controller
     * follows, with what it holds, and is told what it plays now.
     */
    void frontChanged() {
        synchronized (this) {
            if (pad == null) return;
        }
        work.execute(this::retarget);
    }

    // ── the controller's connection ──

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                return;
            }
            if (!fromLauncher(c)) {
                closeQuietly(c);
                continue;
            }
            Thread t = new Thread(() -> serve(c), "apps-controller-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Whether the launcher's uid holds the other end — or, where the kernel will not say, let the token decide. */
    private boolean fromLauncher(Socket c) {
        int uid = Peers.uidOf(c.getPort(), c.getLocalPort());
        if (uid == Peers.UNREADABLE) return true;
        int appId;
        synchronized (this) {
            if (launcherAppId < 0) {
                launcherAppId = Peers.appIdFromList(AppTasks.exec(5, "cmd", "package", "list", "packages", "-U",
                        "--user", "0", WmDaemon.LAUNCHER), WmDaemon.LAUNCHER);
            }
            appId = launcherAppId;
        }
        if (Peers.sameApp(uid, appId)) return true;
        AppServer.log("controller: refused a connection from uid " + uid);
        return false;
    }

    private void serve(Socket c) {
        String said;
        BufferedReader in;
        OutputStream o;
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(HELLO_MS);
            in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), LINE_MAX);
            o = c.getOutputStream();
            said = in.readLine();
            c.setSoTimeout(0);
        } catch (IOException e) {
            closeQuietly(c);
            return;
        }
        Socket replaced;
        synchronized (this) {
            JSONObject hello = parse(said);
            if (hello == null || token == null || !"gamepad".equals(hello.optString("t"))
                    || !token.equals(hello.optString("token"))) {
                AppServer.log("controller: a connection without this run's token, refused");
                closeQuietly(c);
                return;
            }
            replaced = pad;
            pad = c;
            out = o;
        }
        closeQuietly(replaced);
        AppServer.log("controller: the phone's controller is up");
        tellComputer("open", null);
        // A new connection is told everything again: what it plays, and how.
        work.execute(() -> {
            toldTarget = null;
            toldHeld = false;
            toldRefused = false;
            retarget();
        });
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > LINE_MAX) break;
                JSONObject j = parse(line);
                if (j == null) continue;
                switch (j.optString("t")) {
                    case "state": {
                        byte[] r = report(j);
                        if (r != null) work.execute(() -> apply(r));
                        break;
                    }
                    case "top":
                        toController(c, AppPad.front());
                        break;
                    case "hidden":
                        tellComputer("hidden", new JSONObject().put("on", j.optBoolean("on")));
                        break;
                    default:
                }
            }
        } catch (Exception e) {
            // the controller closed, or the port did
        } finally {
            boolean ours;
            synchronized (this) {
                ours = pad == c;
                if (ours) {
                    pad = null;
                    out = null;
                }
            }
            closeQuietly(c);
            if (ours) {
                // Closed is a controller unplugged: its pad goes from the phone.
                work.execute(this::let);
                AppServer.log("controller: the phone's controller closed");
                tellComputer("closed", null);
            }
        }
    }

    /**
     * The 13-byte report a {@code state} line says (AppGamepads.DESCRIPTOR):
     * buttons kept to the layout's, every number to its field's range. Null for a
     * line that is not one.
     */
    static byte[] report(JSONObject j) {
        if (!j.has("b")) return null;
        byte[] r = new byte[AppGamepads.REPORT];
        int b = j.optInt("b") & BUTTONS;
        r[0] = (byte) b;
        r[1] = (byte) (b >> 8);
        int hat = j.optInt("hat");
        r[2] = (byte) (hat >= 0 && hat <= 8 ? hat : 0);
        String[] sticks = {"lx", "ly", "rx", "ry"};
        for (int i = 0; i < sticks.length; i++) {
            int v = Math.max(-32768, Math.min(32767, j.optInt(sticks[i])));
            r[3 + 2 * i] = (byte) v;
            r[4 + 2 * i] = (byte) (v >> 8);
        }
        r[11] = (byte) Math.max(0, Math.min(255, j.optInt("lt")));
        r[12] = (byte) Math.max(0, Math.min(255, j.optInt("rt")));
        return r;
    }

    // ── the work thread ──

    /** A state from the controller, into the pad for the window in front. */
    private void apply(byte[] r) {
        last = r;
        AppWindow w = server.controllerTarget();
        if (w != aimed) retarget();
        if (w == null) return;
        w.used();
        String refused = server.gamepads().phone(w, r);
        afterPut(w, refused);
    }

    /** The window in front, looked at again: what is held goes to it, and the controller is told. */
    private void retarget() {
        AppWindow w = server.controllerTarget();
        boolean moved = w != aimed;
        aimed = w;
        String target = w == null ? "" : w.pkg();
        if (!target.equals(toldTarget)) {
            toldTarget = target;
            try {
                JSONObject m = new JSONObject().put("t", "target").put("pkg", target)
                        .put("label", w == null ? "" : server.catalog().label(target));
                toController(m);
            } catch (Exception ignored) {
                // a name and a value the JSON library takes
            }
            AppServer.log("controller: " + (w == null ? "no window to play in"
                    : "plays " + target + " on display " + w.displayId()));
        }
        if (w == null) {
            // Nothing to play: whatever the pad holds is let go where it was.
            server.gamepads().phoneRest();
            return;
        }
        if (moved && !Arrays.equals(last, NEUTRAL)) {
            afterPut(w, server.gamepads().phone(w, last));
        }
    }

    /** After a report went to {@code w}: the phone's refusal, and the hold, said where they changed. */
    private void afterPut(AppWindow w, String refused) {
        if (refused != null) {
            if (!toldRefused) {
                toldRefused = true;
                try {
                    toController(new JSONObject().put("t", "refused").put("message", refused));
                } catch (Exception ignored) {
                }
                w.padRefused(AppGamepads.PHONE, refused);
            }
            return;
        }
        boolean held = server.gamepads().held(AppGamepads.PHONE);
        if (held != toldHeld) {
            toldHeld = held;
            try {
                toController(new JSONObject().put("t", "held").put("on", held));
            } catch (Exception ignored) {
            }
        }
    }

    /** The controller went, or is no longer offered: its pad goes from the phone. */
    private void let() {
        last = NEUTRAL;
        aimed = null;
        toldTarget = null;
        toldHeld = false;
        server.gamepads().phoneGone();
    }

    // ── saying so ──

    private void toController(JSONObject m) {
        Socket c;
        synchronized (this) {
            c = pad;
        }
        if (c != null) toController(c, m);
    }

    /** One line to the controller on {@code c}, if it is still the one connected. */
    private void toController(Socket c, JSONObject m) {
        OutputStream o;
        synchronized (this) {
            if (pad != c || out == null) return;
            o = out;
        }
        // Not under this, which a provider call may hold for seconds.
        synchronized (writing) {
            try {
                o.write((m.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException e) {
                closeQuietly(c);
            }
        }
    }

    /** One thing the controller did, to the computer. */
    private void tellComputer(String what, JSONObject j) {
        try {
            JSONObject m = j == null ? new JSONObject() : new JSONObject(j.toString());
            m.put("t", "controller").put("k", what);
            computer.send(m);
        } catch (Exception ignored) {
            // a name and a value the JSON library takes
        }
    }

    private static JSONObject parse(String line) {
        if (line == null || line.isEmpty()) return null;
        try {
            return new JSONObject(line);
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
