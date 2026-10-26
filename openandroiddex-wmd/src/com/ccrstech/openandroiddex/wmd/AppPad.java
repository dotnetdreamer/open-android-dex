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

/**
 * The phone's own screen as the touchpad of its apps' windows on the computer
 * (doc/phone-touchpad.md, "The computer's windows"): the server's half.
 *
 * <p>The pad is the launcher's — {@code Touchpad}, in its computer mode, a window
 * over the phone's screen that this process, being no app, could not draw. What
 * its fingers do comes here and goes on to the computer on the control
 * connection, where it moves the computer's pointer over the phone's windows
 * and clicks, scrolls and drags in them — in them only.
 *
 * <p><b>Who gets in.</b> The launcher reaches this process as it reaches the
 * window daemon, over loopback TCP: an app may not connect to the shell's unix
 * socket. So a port of its own, opened only while the computer has asked for
 * the pad, picked by the kernel, taking one connection at a time — from the
 * launcher's uid by the kernel's account ({@link Peers}), with the token this
 * run made and handed it through the launcher's own provider, which only the
 * shell may call. The launcher's uid is also the Linux guest's; all it can do
 * through here is what the computer lets a pad do in the phone's own windows.
 *
 * <p><b>What it changes on the phone.</b> The pad is an overlay, so the
 * launcher must be allowed to draw over other apps: an app-op, given while the
 * computer has asked for the pad and taken back when it stops asking, unless it
 * was already the launcher's (a desktop session gives it too). Notifications are
 * not given: revoking that runtime permission ends the launcher's processes, the
 * phone's Linux with them. Where the launcher may already post one, the pad is
 * offered in the phone's shade; it is always one click away in each window's
 * menu on the computer.
 *
 * <p>The line protocol, launcher to here, one JSON object a line: {@code pad}
 * (the token, first), {@code move} (dx, dy: the computer's points), {@code
 * buttons} (MotionEvent.BUTTON_* held), {@code wheel} (h, v: notches), {@code
 * key} (an Android key code), {@code act} (a gesture's window action), {@code
 * hidden} (on: Android is keeping the pad off the phone's screen, as it does
 * every app's overlay while Settings or a screen like it shows — anywhere,
 * a window of the computer's included), and {@code top}, answered here with
 * the task in front of the phone's own screen, so the pad can get out of the
 * way of a call. Each but {@code top} goes on to the computer as {@code
 * {"t":"pad","k":<what>,…}}, with {@code open} and {@code closed} as the pad
 * connects and goes.
 */
final class AppPad {

    /** The computer, on whichever control connection it has now. */
    interface Sink {
        void send(JSONObject message);
    }

    /** Longer than this, a line is no pad's: the connection is dropped. */
    private static final int LINE_MAX = 4096;
    /** The pad says who it is at once; a connection that does not is let go. */
    private static final int HELLO_MS = 5000;

    private final Sink computer;
    private final SecureRandom random = new SecureRandom();

    // Guarded by this.
    private ServerSocket listening;
    private String token;
    private Socket pad;
    /** The overlay app-op was given here, and is this run's to take back. */
    private boolean overlayGiven;
    /** The launcher's app id, as the package manager says; -1 until asked. */
    private int launcherAppId = -1;

    AppPad(Sink computer) {
        this.computer = computer;
    }

    /**
     * The computer wants the pad: listening, the launcher let draw it, and the
     * launcher told where to find this — to offer it in the phone's shade, or,
     * with {@code open}, to put it up now. The answer says how that went.
     */
    synchronized JSONObject arm(boolean open) throws Exception {
        JSONObject r = new JSONObject().put("t", "padOn").put("open", open);
        if (listening == null || listening.isClosed()) {
            listening = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            byte[] b = new byte[16];
            random.nextBytes(b);
            StringBuilder hex = new StringBuilder();
            for (byte x : b) hex.append(String.format("%02x", x & 0xff));
            token = hex.toString();
            ServerSocket s = listening;
            Thread t = new Thread(() -> acceptLoop(s), "apps-pad");
            t.setDaemon(true);
            t.start();
            AppServer.log("pad: listening on 127.0.0.1:" + s.getLocalPort());
        }
        if (!overlayGiven && !overlayAllowed()) {
            String out = AppTasks.exec(5, "cmd", "appops", "set", WmDaemon.LAUNCHER, "SYSTEM_ALERT_WINDOW", "allow");
            if (overlayAllowed()) {
                overlayGiven = true;
                AppServer.log("pad: the launcher may draw over other apps while the computer wants the pad");
            } else {
                AppServer.log("pad: the launcher was not let draw over other apps (" + out.trim() + ")");
            }
        }
        JSONObject arg = new JSONObject().put("port", listening.getLocalPort()).put("token", token);
        JSONObject said;
        try {
            said = Providers.call(AppServer.LINUX_PROVIDER, open ? "pad.open" : "pad.offer", arg);
        } catch (Exception e) {
            // No launcher, or one from before the pad: the computer installs it if a person asked.
            return r.put("ok", false).put("launcher", false).put("message", String.valueOf(e));
        }
        return r.put("ok", said.optBoolean("ok", false)).put("launcher", true)
                .put("offered", said.optBoolean("offered", false))
                .put("message", said.optString("message"));
    }

    /**
     * The computer no longer wants the pad — its last window here closed, or it
     * is going: the pad and its offer down, the port closed, and the overlay
     * app-op taken back if it was given here and no desktop of the phone's still
     * needs it.
     */
    synchronized void disarm(String why) {
        boolean desktop = false;
        boolean told = false;
        try {
            JSONObject said = Providers.call(AppServer.LINUX_PROVIDER, "pad.close", null);
            desktop = said.optBoolean("desktop", false);
            told = true;
        } catch (Exception e) {
            // no launcher to tell; nothing of its is up
        }
        closeQuietly(listening);
        listening = null;
        closeQuietly(pad);
        pad = null;
        token = null;
        if (overlayGiven && !desktop) {
            AppTasks.exec(5, "cmd", "appops", "set", WmDaemon.LAUNCHER, "SYSTEM_ALERT_WINDOW", "default");
            overlayGiven = false;
        }
        AppServer.log("pad: no longer offered (" + why + ")" + (told ? "" : ", the launcher unreachable"));
    }

    synchronized boolean armed() {
        return listening != null;
    }

    private static boolean overlayAllowed() {
        String out = AppTasks.exec(5, "cmd", "appops", "get", WmDaemon.LAUNCHER, "SYSTEM_ALERT_WINDOW");
        return out.contains("SYSTEM_ALERT_WINDOW: allow");
    }

    // ── the pad's connection ──

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
            Thread t = new Thread(() -> serve(c), "apps-pad-conn");
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
        AppServer.log("pad: refused a connection from uid " + uid);
        return false;
    }

    private void serve(Socket c) {
        String said;
        BufferedReader in;
        OutputStream out;
        try {
            c.setSoTimeout(HELLO_MS);
            in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), LINE_MAX);
            out = c.getOutputStream();
            said = in.readLine();
            c.setSoTimeout(0);
        } catch (IOException e) {
            closeQuietly(c);
            return;
        }
        Socket replaced;
        synchronized (this) {
            JSONObject hello = parse(said);
            if (hello == null || token == null || !"pad".equals(hello.optString("t"))
                    || !token.equals(hello.optString("token"))) {
                AppServer.log("pad: a connection without this run's token, refused");
                closeQuietly(c);
                return;
            }
            replaced = pad;
            pad = c;
        }
        closeQuietly(replaced);
        tell("open", null);
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > LINE_MAX) break;
                JSONObject j = parse(line);
                if (j == null) continue;
                String what = j.optString("t");
                switch (what) {
                    case "top":
                        top(out);
                        break;
                    case "move":
                    case "buttons":
                    case "wheel":
                    case "key":
                    case "act":
                    case "hidden":
                        tell(what, j);
                        break;
                    default:
                }
            }
        } catch (IOException e) {
            // the pad closed, or the port did
        } finally {
            boolean ours;
            synchronized (this) {
                ours = pad == c;
                if (ours) pad = null;
            }
            closeQuietly(c);
            if (ours) tell("closed", null);
        }
    }

    /** What is in front of the phone's own screen, for the pad to get out of the way of. */
    private static void top(OutputStream out) throws IOException {
        JSONObject r = new JSONObject();
        try {
            r.put("t", "top").put("task", -1).put("pkg", "");
            for (AppTasks.Task t : AppTasks.onDisplay(0)) {
                if (!t.visible) continue;
                r.put("task", t.id).put("pkg", t.pkg);
                break;
            }
        } catch (Exception e) {
            // the platform would not say: none
        }
        out.write((r.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** One thing the pad did, to the computer. */
    private void tell(String what, JSONObject j) {
        try {
            JSONObject m = j == null ? new JSONObject() : new JSONObject(j.toString());
            m.remove("token");
            m.put("t", "pad").put("k", what);
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
