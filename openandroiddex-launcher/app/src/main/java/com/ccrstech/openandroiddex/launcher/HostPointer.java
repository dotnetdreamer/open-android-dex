package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone touchpad's pointer when it is the computer's (doc/phone-touchpad.md, "The
 * computer's windows"): the phone's apps in windows of their own there, and no desktop on
 * the phone at all.
 *
 * <p><b>The pointer is the computer's own</b>, as it is for those windows whatever moves
 * it. What the pad does goes to the phone's app server — the computer's half on the phone —
 * over loopback TCP, with the token it handed this app through {@link AppsProvider}, and on
 * to the computer, which moves its pointer and clicks, scrolls and drags in the phone's
 * window under it. So nothing is drawn here, and a move goes as how far, not where: the
 * computer knows where its pointer is, and what is under it. How far is the pad's speed
 * curve over the finger's dp, in the computer's points — dp on both sides, as
 * {@link DesktopPointer} has it on the desktop.
 *
 * <p><b>Nothing waits on the computer.</b> What the pad does goes into an outbox that a
 * client thread drains; moves still waiting there add up into one, and so do turns of the
 * wheel, so the pointer goes as far as the finger did however slow the link. A press, a
 * release, a key and an action are never folded into anything.
 */
final class HostPointer implements PadTarget {

    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 800;
    /** How long the server has to say what is in front of the phone's screen. */
    private static final int TOP_TIMEOUT_MS = 1500;
    /** A connection that failed is tried again on the next thing to send, after this. */
    private static final long RETRY_MS = 1000;

    private static final int MOVE = 0;
    private static final int WHEEL = 1;
    private static final int EDGE = 2;

    /** One line for the server. */
    private static final class Op {
        final int kind;
        float a;
        float b;
        /** EDGE: the whole line, as it goes. */
        JSONObject line;

        Op(int kind) {
            this.kind = kind;
        }
    }

    private final Context ctx;
    private final int port;
    private final String token;
    private final HandlerThread thread = new HandlerThread("pad-host");
    private final Handler io;
    /** Guarded by itself, and so is {@link #draining}. */
    private final ArrayDeque<Op> outbox = new ArrayDeque<>();
    private boolean draining;

    // The client thread's.
    private Socket socket;
    private OutputStream out;
    private BufferedReader in;
    private long retryAt;

    private int buttons;
    private int speed;
    /** False from a line that could not be sent until the next one that can. */
    private volatile boolean answering = true;

    HostPointer(Context ctx, int port, String token) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.token = token;
        thread.start();
        io = new Handler(thread.getLooper());
        readSpeed();
    }

    /** Whether it goes to the computer's server on {@code port} with {@code token}. */
    boolean sameAs(int otherPort, String otherToken) {
        return port == otherPort && token.equals(otherToken);
    }

    @Override
    public void show() {
        // Connected now, so the first touch is not the one that waits for it.
        io.post(this::connect);
    }

    @Override
    public void release() {
        if (buttons != 0) press(0);
        io.post(this::disconnect);
        thread.quitSafely();
    }

    @Override
    public void focusDesktop() {
        // The computer's keyboard is the computer's: nothing on the phone holds it.
    }

    @Override
    public boolean answering() {
        return answering;
    }

    @Override
    public void displayChanged() {
    }

    @Override
    public void restyle() {
        // The computer draws its own pointer.
    }

    @Override
    public void readSpeed() {
        speed = DexPrefs.getInt(ctx, DexPrefs.KEY_PAD_SPEED, DexPrefs.DEF_PAD_SPEED);
    }

    @Override
    public void moveBy(float dxDp, float dyDp, float speedDpPerMs) {
        float g = DexPointer.gain(speed, speedDpPerMs);
        Op op = new Op(MOVE);
        op.a = dxDp * g;
        op.b = dyDp * g;
        queue(op);
    }

    @Override
    public void press(int next) {
        if (next == buttons) return;
        buttons = next;
        edge(line("buttons").put("b", next));
    }

    @Override
    public void click(int button) {
        press(buttons | button);
        press(buttons & ~button);
    }

    @Override
    public void scroll(float h, float v) {
        Op op = new Op(WHEEL);
        op.a = h;
        op.b = v;
        queue(op);
    }

    @Override
    public void key(int code) {
        edge(line("key").put("code", code));
    }

    /**
     * One of a gesture's window actions, the computer's to carry out over the phone's
     * windows there: {@code nextwindow}, {@code prevwindow}, {@code maximize}.
     */
    void act(String action) {
        edge(line("act").put("action", action));
    }

    /**
     * Android is keeping the pad off the phone's screen, or has put it back
     * ({@link Touchpad#checkShown}): the computer says so in its windows.
     */
    void hidden(boolean on) {
        edge(line("hidden").put("on", on));
    }

    /**
     * What is in front of the phone's own screen — {task, package} — or null if the server
     * could not be asked. Blocks: the pad asks from a thread of its own.
     */
    String[] top() {
        String[][] got = new String[1][];
        CountDownLatch done = new CountDownLatch(1);
        io.post(() -> {
            try {
                if (!connect()) return;
                out.write(bytes(line("top")));
                out.flush();
                String reply = in.readLine();
                if (reply == null) throw new IOException("the server closed the connection");
                JSONObject j = new JSONObject(reply);
                got[0] = new String[]{String.valueOf(j.optInt("task", -1)), j.optString("pkg")};
            } catch (Exception e) {
                disconnect();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(TOP_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return got[0];
    }

    // ── to the server ──

    private void edge(JSONLine l) {
        Op op = new Op(EDGE);
        op.line = l.json;
        queue(op);
    }

    private void queue(Op op) {
        synchronized (outbox) {
            Op last = outbox.peekLast();
            if (last != null && last.kind == op.kind && op.kind != EDGE) {
                // Still waiting: it goes as far as both, as one line.
                last.a += op.a;
                last.b += op.b;
                return;
            }
            outbox.add(op);
            if (draining) return;
            draining = true;
        }
        io.post(this::drain);
    }

    /** The client's thread: every line waiting, in order. */
    private void drain() {
        while (true) {
            Op op;
            synchronized (outbox) {
                op = outbox.poll();
                if (op == null) {
                    draining = false;
                    return;
                }
            }
            JSONObject l;
            switch (op.kind) {
                case MOVE:
                    l = line("move").put("dx", op.a).put("dy", op.b).json;
                    break;
                case WHEEL:
                    l = line("wheel").put("h", op.a).put("v", op.b).json;
                    break;
                default:
                    l = op.line;
            }
            boolean ok = false;
            if (connect()) {
                try {
                    out.write(bytes(l));
                    out.flush();
                    ok = true;
                } catch (IOException e) {
                    disconnect();
                }
            }
            if (ok != answering) {
                answering = ok;
                DexLog.step("touchpad", ok ? "the computer is taking the pointer again"
                        : "the computer's server is not answering — nothing reaches the computer");
            }
        }
    }

    /** The client's thread: connected, saying who this is, unless a try has just failed. */
    private boolean connect() {
        if (socket != null) return true;
        long now = System.currentTimeMillis();
        if (now < retryAt) return false;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(TOP_TIMEOUT_MS);
            OutputStream o = s.getOutputStream();
            o.write(bytes(line("pad").put("token", token).json));
            o.flush();
            socket = s;
            out = o;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            DexLog.step("touchpad", "connected to the computer's server on port " + port);
            return true;
        } catch (IOException e) {
            retryAt = now + RETRY_MS;
            try {
                s.close();
            } catch (IOException ignored) {
            }
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
        in = null;
        retryAt = System.currentTimeMillis() + RETRY_MS;
    }

    private static JSONLine line(String t) {
        return new JSONLine().put("t", t);
    }

    private static byte[] bytes(JSONObject j) {
        return (j.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(JSONLine l) {
        return bytes(l.json);
    }

    /** A JSON object built in one expression: the library's put throws a checked exception. */
    private static final class JSONLine {
        final JSONObject json = new JSONObject();

        JSONLine put(String name, Object value) {
            try {
                json.put(name, value);
            } catch (org.json.JSONException ignored) {
                // a name and a value the library takes
            }
            return this;
        }

        JSONLine put(String name, float value) {
            return put(name, (Object) (double) value);
        }

        /** Whole, as buttons and key codes are read on the other side — not widened to a float. */
        JSONLine put(String name, int value) {
            return put(name, (Object) value);
        }
    }
}
