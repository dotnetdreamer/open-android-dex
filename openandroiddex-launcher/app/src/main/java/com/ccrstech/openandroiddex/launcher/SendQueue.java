package com.ccrstech.openandroiddex.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What waits to be sent to the computer (doc/send-to-computer.md, "A knock,
 * then a question"): the shares {@link SendActivity} has taken in, by number,
 * until the computer's half on the phone — the app server, the shell's
 * process — has taken them.
 *
 * <p>This app cannot reach that server's socket (it takes the shell and root
 * only), and does not need to. It <b>knocks</b> on the server's loopback port,
 * {@link #PORT} (AppSend.PORT in the window daemon names the same): one byte
 * that carries nothing. The server then <b>asks</b> here, through
 * {@link AppsProvider} — which answers the shell alone — what there is
 * ({@code share.take}), and opens each file through the provider
 * ({@link #open}): opened by this app, which holds the share's permission,
 * and handed over as an open file. No grant to the shell, no token, no
 * broadcast. The server says how it went ({@code share.opened},
 * {@code share.progress}, {@code share.done}), which the card shows.
 */
final class SendQueue {

    /** Where the app server listens while a computer's link is up. */
    static final int PORT = 7190;
    /** The most text one share takes. */
    static final int TEXT_MAX = 200_000;
    /** The most items one share takes. */
    static final int ITEMS_MAX = 1000;

    /**
     * How long one item may take to open before the server's call is let go
     * of: under the server's own 60 s, so it hears why. The open itself goes on
     * on a worker of this app's — a provider's call cannot be cut — and what it
     * opens in the end is closed.
     */
    static final long OPEN_MS = 50_000;
    /** Opens still running past {@link #OPEN_MS}: with this many, new shares are refused ("stuck"). */
    static final int STUCK_MAX = 3;

    static final byte KNOCK_PRESENT = 'P';
    static final byte KNOCK_SHARE = 'S';
    static final byte KNOCK_CHANGED = 'C';

    /** One file of a share. */
    static final class Item {
        int i;
        String name;
        String mime;
        long size = -1;
        Uri uri;
    }

    /** One share, and how far it is. */
    static final class Share {
        int id;
        /** {@code text} or {@code files}. */
        String kind;
        String text;
        String subject;
        final List<Item> items = new ArrayList<>();
        /** A small JPEG of the first picture, base64, for the computer's notification. */
        String preview;
        // How far, as the server says: all on the main thread's side of the listeners.
        volatile boolean taken;
        /** When it was taken, on {@link SystemClock#uptimeMillis}. */
        volatile long takenAt;
        volatile boolean opened;
        volatile boolean cancelled;
        volatile boolean refused;
        volatile String computer = "";
        volatile int failedToOpen;
        volatile int index;
        /** The computer has as many files coming in as it takes at once: this one waits its turn. */
        volatile boolean waiting;
        volatile long bytes;
        volatile long total = -1;
        volatile JSONObject done;
    }

    interface Listener {
        /** Something about {@code s} changed: on the main thread. */
        void changed(Share s);
    }

    private static final Map<Integer, Share> SHARES = new LinkedHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /**
     * The opens, off the binder thread that asked: a provider that never
     * answers holds one of these, never one of this app's binder threads,
     * which the phone's desktop answers on. More than these at once are refused.
     */
    private static final ThreadPoolExecutor OPENERS = new ThreadPoolExecutor(0, STUCK_MAX + 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "send-open");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicInteger STUCK = new AtomicInteger();
    /**
     * The last share's number. It starts somewhere random so that numbers do
     * not repeat when this process is restarted: the server and the computer
     * know a share by its number, and a share 1 of the last run may still be
     * on their side.
     */
    private static int next = 1 + new java.security.SecureRandom().nextInt(1 << 30);

    private SendQueue() {
    }

    static void listen(Listener l) {
        LISTENERS.add(l);
    }

    static void unlisten(Listener l) {
        LISTENERS.remove(l);
    }

    private static void changed(Share s) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.changed(s);
        });
    }

    /** {@code s} queued: its number. */
    static synchronized int offer(Share s) {
        s.id = ++next;
        SHARES.put(s.id, s);
        // A few at most are ever waiting; the oldest done ones go.
        while (SHARES.size() > 16) {
            Integer oldest = SHARES.keySet().iterator().next();
            Share o = SHARES.get(oldest);
            if (o != null && o.done == null && !o.cancelled && !o.refused) break;
            SHARES.remove(oldest);
        }
        return s.id;
    }

    static synchronized Share get(int id) {
        return SHARES.get(id);
    }

    /** Taken back on the card: the server hears of it at its next question. */
    static void cancel(Share s) {
        s.cancelled = true;
        new Thread(() -> {
            try {
                knock(KNOCK_CHANGED);
            } catch (IOException ignored) {
                // nobody there to tell: nothing was handed over
            }
        }, "send-knock").start();
    }

    /**
     * One byte to the server's port: true if something took it. A refused
     * connection — nobody listening — throws {@link ConnectException} at once.
     */
    static boolean knock(byte what) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 700);
            OutputStream out = s.getOutputStream();
            out.write(what);
            out.flush();
            return true;
        }
    }

    // ── what the server asks (AppsProvider, the shell only) ──

    /**
     * {@code share.take}: every share queued and not yet taken, now taken; and
     * those taken back. While {@link #STUCK_MAX} opens are stuck on a provider
     * that does not answer, a new files share is refused instead ("stuck"),
     * which its card says: another would only wait behind them.
     */
    static JSONObject take(JSONObject in) throws Exception {
        JSONArray shares = new JSONArray();
        JSONArray cancelled = new JSONArray();
        List<Share> took = new ArrayList<>();
        List<Share> refused = new ArrayList<>();
        boolean stuck = STUCK.get() >= STUCK_MAX;
        synchronized (SendQueue.class) {
            for (Share s : SHARES.values()) {
                if (s.cancelled) {
                    if (s.taken && s.done == null) {
                        cancelled.put(s.id);
                        s.done = new JSONObject().put("ok", false).put("message", "cancelled");
                    }
                    continue;
                }
                if (s.taken || s.refused || s.done != null) continue;
                s.computer = in.optString("computer", "");
                if (stuck && "files".equals(s.kind)) {
                    s.done = new JSONObject().put("ok", false).put("message", "stuck");
                    refused.add(s);
                    continue;
                }
                s.taken = true;
                s.takenAt = SystemClock.uptimeMillis();
                took.add(s);
                shares.put(describe(s));
            }
        }
        for (Share s : took) changed(s);
        for (Share s : refused) changed(s);
        if (!refused.isEmpty()) DexLog.warn("send", refused.size() + " share(s) refused: " + STUCK.get() + " opens are stuck");
        DexLog.step("send", "the computer's server took " + took.size() + " share(s), " + cancelled.length() + " taken back");
        return new JSONObject().put("ok", true).put("shares", shares).put("cancelled", cancelled);
    }

    private static JSONObject describe(Share s) throws Exception {
        JSONObject j = new JSONObject().put("id", s.id).put("kind", s.kind);
        if ("text".equals(s.kind)) {
            j.put("text", s.text).put("subject", s.subject == null ? "" : s.subject);
            return j;
        }
        JSONArray items = new JSONArray();
        for (Item it : s.items) {
            items.put(new JSONObject().put("i", it.i).put("name", it.name).put("mime", it.mime == null ? "" : it.mime).put("size", it.size));
        }
        j.put("items", items);
        if (s.preview != null) j.put("preview", s.preview);
        return j;
    }

    /**
     * {@code share.enable}: the share entry on, or back to the manifest's word
     * (off). This app changes its own component; the shell may not.
     */
    static JSONObject enable(Context ctx, boolean on) throws Exception {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.ComponentName entry = new android.content.ComponentName(ctx, SendActivity.class);
        pm.setComponentEnabledSetting(entry, on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP);
        boolean now = pm.getComponentEnabledSetting(entry) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        DexLog.step("send", "the share entry is " + (now ? "on" : "off"));
        return new JSONObject().put("ok", true).put("on", now);
    }

    /** {@code share.refuse}: this computer takes no shares — every queued one is told so. */
    static JSONObject refuse(JSONObject in) throws Exception {
        List<Share> told = new ArrayList<>();
        synchronized (SendQueue.class) {
            for (Share s : SHARES.values()) {
                if (s.taken || s.refused || s.cancelled || s.done != null) continue;
                s.refused = true;
                s.computer = in.optString("computer", "");
                told.add(s);
            }
        }
        for (Share s : told) changed(s);
        DexLog.step("send", "the computer takes no shares: " + told.size() + " refused");
        return new JSONObject().put("ok", true);
    }

    /** {@code share.opened}: everything the share has is open on the server's side, or not to be had. */
    static JSONObject opened(JSONObject in) throws Exception {
        Share s = get(in.optInt("id", -1));
        if (s != null) {
            JSONArray failed = in.optJSONArray("failed");
            s.failedToOpen = failed == null ? 0 : failed.length();
            s.opened = true;
            changed(s);
        }
        return new JSONObject().put("ok", true);
    }

    /** {@code share.wait}: the computer has as many files coming in as it takes at once; this share waits its turn. */
    static JSONObject waiting(JSONObject in) throws Exception {
        Share s = get(in.optInt("id", -1));
        if (s != null && s.done == null) {
            s.waiting = true;
            changed(s);
            DexLog.step("send", "share " + s.id + " waits its turn on the computer");
        }
        return new JSONObject().put("ok", true);
    }

    /** {@code share.progress}: how far a file has gone. */
    static JSONObject progress(JSONObject in) throws Exception {
        Share s = get(in.optInt("id", -1));
        if (s != null) {
            s.waiting = false;
            s.index = in.optInt("i");
            s.bytes = in.optLong("bytes");
            s.total = in.optLong("total", -1);
            changed(s);
        }
        return new JSONObject().put("ok", true);
    }

    /** {@code share.done}: how it went on the computer. */
    static JSONObject done(JSONObject in) throws Exception {
        Share s = get(in.optInt("id", -1));
        if (s != null) {
            if (!in.optString("computer").isEmpty()) s.computer = in.optString("computer");
            s.done = in;
            changed(s);
            DexLog.step("send", "share " + s.id + " done: " + (in.optBoolean("ok") ? in.optString("did") : "not delivered (" + in.optString("message") + ")"));
        }
        return new JSONObject().put("ok", true);
    }

    /**
     * {@code share/<id>/<i>/<name>}, opened as this app — which holds the
     * share's read permission while its card is up — for the server to read.
     */
    static AssetFileDescriptor open(Context ctx, List<String> path) throws FileNotFoundException {
        if (path.size() < 3) throw new FileNotFoundException("no such share");
        Share s;
        int i;
        try {
            s = get(Integer.parseInt(path.get(1)));
            i = Integer.parseInt(path.get(2));
        } catch (NumberFormatException e) {
            throw new FileNotFoundException("no such share");
        }
        if (s == null || s.cancelled || i < 0 || i >= s.items.size()) throw new FileNotFoundException("no such share");
        if (STUCK.get() >= STUCK_MAX) throw new FileNotFoundException("stuck: " + STUCK.get() + " opens have not come back");
        final Share share = s;
        final int item = i;
        final Object lock = new Object();
        final AssetFileDescriptor[] got = {null};
        final FileNotFoundException[] failed = {null};
        final boolean[] state = {false, false}; // done, given up
        try {
            OPENERS.execute(() -> {
                AssetFileDescriptor a = null;
                FileNotFoundException f = null;
                try {
                    a = openNow(ctx, share, item);
                } catch (FileNotFoundException e) {
                    f = e;
                } catch (RuntimeException e) {
                    f = new FileNotFoundException(String.valueOf(e));
                }
                synchronized (lock) {
                    state[0] = true;
                    if (state[1]) {
                        // Given up on: what it opened in the end is closed, and it no longer counts as stuck.
                        if (a != null) {
                            try {
                                a.close();
                            } catch (IOException ignored) {
                            }
                        }
                        STUCK.decrementAndGet();
                        DexLog.step("send", "share " + share.id + " item " + item + " came back after it was given up on");
                    } else {
                        got[0] = a;
                        failed[0] = f;
                    }
                    lock.notifyAll();
                }
            });
        } catch (RejectedExecutionException e) {
            throw new FileNotFoundException("stuck: no opener free");
        }
        synchronized (lock) {
            long until = SystemClock.uptimeMillis() + OPEN_MS;
            while (!state[0]) {
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!state[0]) {
                state[1] = true;
                STUCK.incrementAndGet();
                DexLog.warn("send", "share " + share.id + " item " + item + " not open after " + OPEN_MS / 1000 + " s: given up on");
                throw new FileNotFoundException("not open after " + OPEN_MS / 1000 + " s");
            }
            if (got[0] != null) return got[0];
            throw failed[0] != null ? failed[0] : new FileNotFoundException("nothing at the share's item " + item);
        }
    }

    private static AssetFileDescriptor openNow(Context ctx, Share s, int i) throws FileNotFoundException {
        Uri uri = s.items.get(i).uri;
        ContentResolver cr = ctx.getContentResolver();
        try {
            AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
            if (afd != null) return afd;
        } catch (FileNotFoundException | SecurityException e) {
            // A virtual document, say: it may still have a typed stream.
            String mime = s.items.get(i).mime;
            try {
                AssetFileDescriptor afd = cr.openTypedAssetFileDescriptor(uri, mime == null || mime.isEmpty() ? "*/*" : mime, null);
                if (afd != null) return afd;
            } catch (FileNotFoundException | SecurityException e2) {
                DexLog.warn("send", "share " + s.id + " item " + i + " could not be opened", e2);
            }
            throw new FileNotFoundException(String.valueOf(e.getMessage()));
        }
        throw new FileNotFoundException("nothing at the share's item " + i);
    }
}
