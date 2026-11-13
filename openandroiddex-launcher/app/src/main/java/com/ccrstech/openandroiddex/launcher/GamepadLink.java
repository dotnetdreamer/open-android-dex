package com.ccrstech.openandroiddex.launcher;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The phone's game controller's way to the computer's half on the phone — its app server
 * (doc/game-mode.md, "The phone as a controller"; the server's AppController). Over
 * loopback TCP, as the touchpad's ({@link HostPointer}), with the token the server handed
 * this app through {@link AppsProvider}: what the fingers hold goes there, and becomes a
 * gamepad of the phone's own for the window in front on the computer. Nothing goes to the
 * computer and back.
 *
 * <p><b>Nothing waits on the server.</b> States go into an outbox a client thread drains.
 * One still waiting is replaced by the next where the two hold the same buttons, d-pad
 * and triggers — the sticks go where they are now, not everywhere they have been — and
 * never where a button went down or up in between: a quick tap is still a press.
 *
 * <p>The server says, as they change, which app the controller plays ({@code target}),
 * whether its input waits for a click in that window on the computer ({@code held}), and
 * that the phone makes no controller ({@code refused}); a connection made again is told
 * them again, and sent what is held now.
 */
final class GamepadLink {

    /** What the server says, on the main thread. */
    interface Listener {
        /** The app the controller plays now; empty for none. */
        void onTarget(String pkg, String label);

        /** Its input waits for a click in the game's window on the computer, or no longer does. */
        void onHeld(boolean on);

        /** The phone makes no game controller, and why. */
        void onRefused(String message);

        /** The server is taking what is sent, or is not answering. */
        void onAnswering(boolean on);
    }

    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 800;
    /** How long the server has to say what is in front of the phone's screen. */
    private static final int TOP_TIMEOUT_MS = 1500;
    /** A connection that failed or went is tried again after this. */
    private static final long RETRY_MS = 1000;

    private final int port;
    private final String token;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("gamepad-link");
    private final Handler io;
    /** States and lines waiting; guarded by itself, and so is {@link #draining}. */
    private final ArrayDeque<Object> outbox = new ArrayDeque<>();
    private boolean draining;
    /** The server's answers to {@code top}. */
    private final LinkedBlockingQueue<JSONObject> tops = new LinkedBlockingQueue<>();

    // The client thread's.
    private Socket socket;
    private OutputStream out;
    private long retryAt;
    /** The last state that went, sent again on a new connection. */
    private GamepadView.State held;
    private volatile boolean released;
    private volatile boolean answering = true;
    /** One try again at a time, without waiting for a press: the controller says when it is back. */
    private final Runnable reconnect = this::connect;

    GamepadLink(int port, String token, Listener listener) {
        this.port = port;
        this.token = token;
        this.listener = listener;
        thread.start();
        io = new Handler(thread.getLooper());
    }

    /** Whether it goes to the server on {@code otherPort} with {@code otherToken}. */
    boolean sameAs(int otherPort, String otherToken) {
        return port == otherPort && token.equals(otherToken);
    }

    /** Connected now, so the first press is not the one that waits for it. */
    void start() {
        io.post(this::connect);
    }

    /** What the controller holds now. */
    void send(GamepadView.State s) {
        queue(s.copy());
    }

    /** Android is keeping the controller off the phone's screen, or has put it back. */
    void hidden(boolean on) {
        try {
            queue(new JSONObject().put("t", "hidden").put("on", on));
        } catch (org.json.JSONException ignored) {
            // a name and a value the library takes
        }
    }

    /** The controller is going: the connection closed, which lets go of all it held. */
    void release() {
        released = true;
        io.post(this::disconnect);
        thread.quitSafely();
    }

    /**
     * What is in front of the phone's own screen — {task, package} — or null if the
     * server could not be asked. Blocks: asked from a thread of the controller's own.
     */
    String[] top() {
        String[][] got = new String[1][];
        tops.clear();
        java.util.concurrent.CountDownLatch sent = new java.util.concurrent.CountDownLatch(1);
        boolean[] ok = new boolean[1];
        io.post(() -> {
            try {
                if (connect()) {
                    out.write(line(new JSONObject().put("t", "top")));
                    out.flush();
                    ok[0] = true;
                }
            } catch (Exception e) {
                disconnect();
            } finally {
                sent.countDown();
            }
        });
        try {
            if (!sent.await(TOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !ok[0]) return null;
            JSONObject j = tops.poll(TOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (j != null) got[0] = new String[]{String.valueOf(j.optInt("task", -1)), j.optString("pkg")};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return got[0];
    }

    // ── to the server ──

    private void queue(Object item) {
        synchronized (outbox) {
            Object last = outbox.peekLast();
            if (item instanceof GamepadView.State && last instanceof GamepadView.State
                    && ((GamepadView.State) last).sameEdges((GamepadView.State) item)) {
                // Still waiting, and nothing pressed or let go since: where the sticks are now.
                outbox.pollLast();
            }
            outbox.add(item);
            if (draining) return;
            draining = true;
        }
        io.post(this::drain);
    }

    /** The client's thread: every line waiting, in order. */
    private void drain() {
        while (true) {
            Object item;
            synchronized (outbox) {
                item = outbox.poll();
                if (item == null) {
                    draining = false;
                    return;
                }
            }
            if (item instanceof GamepadView.State) held = (GamepadView.State) item;
            boolean ok = false;
            if (connect()) {
                try {
                    out.write(line(item instanceof GamepadView.State ? state((GamepadView.State) item) : (JSONObject) item));
                    out.flush();
                    ok = true;
                } catch (Exception e) {
                    disconnect();
                }
            }
            answering(ok);
        }
    }

    /** The client's thread: connected, saying who this is, unless a try has just failed. */
    private boolean connect() {
        if (socket != null) return true;
        if (released) return false;
        long now = System.currentTimeMillis();
        if (now < retryAt) return false;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS);
            OutputStream o = s.getOutputStream();
            o.write(line(new JSONObject().put("t", "gamepad").put("token", token)));
            // What was held when the last connection went: the server let go of it.
            if (held != null && !held.neutral()) o.write(line(state(held)));
            o.flush();
            socket = s;
            out = o;
            Thread reader = new Thread(() -> read(s), "gamepad-link-read");
            reader.setDaemon(true);
            reader.start();
            DexLog.step("gamepad", "connected to the computer's server on port " + port);
            answering(true);
            return true;
        } catch (Exception e) {
            retryAt = now + RETRY_MS;
            try {
                s.close();
            } catch (IOException ignored) {
            }
            answering(false);
            retryLater();
            return false;
        }
    }

    private void disconnect() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
        retryAt = System.currentTimeMillis() + RETRY_MS;
    }

    /** What the server says on {@code s}, until it closes. */
    private void read(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String l;
            while ((l = in.readLine()) != null) {
                JSONObject j;
                try {
                    j = new JSONObject(l);
                } catch (Exception e) {
                    continue;
                }
                switch (j.optString("t")) {
                    case "top":
                        tops.offer(j);
                        break;
                    case "target": {
                        String pkg = j.optString("pkg");
                        String label = j.optString("label");
                        main.post(() -> listener.onTarget(pkg, label));
                        break;
                    }
                    case "held": {
                        boolean on = j.optBoolean("on");
                        main.post(() -> listener.onHeld(on));
                        break;
                    }
                    case "refused": {
                        String message = j.optString("message");
                        main.post(() -> listener.onRefused(message));
                        break;
                    }
                    default:
                }
            }
        } catch (IOException e) {
            // closed: below
        }
        io.post(() -> {
            if (socket != s) return;
            disconnect();
            if (released) return;
            DexLog.step("gamepad", "the computer's server closed the connection");
            answering(false);
            retryLater();
        });
    }

    /** The client's thread: one more try, a moment from now. */
    private void retryLater() {
        if (released) return;
        io.removeCallbacks(reconnect);
        io.postDelayed(reconnect, RETRY_MS);
    }

    private void answering(boolean ok) {
        if (ok == answering) return;
        answering = ok;
        DexLog.step("gamepad", ok ? "the computer's server is taking the controller again"
                : "the computer's server is not answering — the controller reaches nothing");
        main.post(() -> listener.onAnswering(ok));
    }

    private static JSONObject state(GamepadView.State s) throws org.json.JSONException {
        return new JSONObject().put("t", "state").put("b", s.buttons).put("hat", s.hat)
                .put("lx", s.lx).put("ly", s.ly).put("rx", s.rx).put("ry", s.ry)
                .put("lt", s.lt).put("rt", s.rt);
    }

    private static byte[] line(JSONObject j) {
        return (j.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
