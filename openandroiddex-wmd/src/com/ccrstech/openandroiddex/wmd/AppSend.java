package com.ccrstech.openandroiddex.wmd;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Send to computer" and the phone's newest photos (doc/send-to-computer.md):
 * the phone's half of the computer's standing link for them.
 *
 * <p>A connection whose first message is {@code send} is that link, one per
 * computer: a later one takes the place of an earlier one, which is closed.
 * While it is up the server listens on {@code 127.0.0.1:}{@link #PORT} — the
 * launcher's share card (SendActivity, SendQueue) <b>knocks</b> there with one
 * byte that carries nothing, and the server <b>asks the launcher</b> what it
 * has through the launcher's provider ({@code share.take}): binder says who is
 * calling, and the provider answers only the shell. Files come back from the
 * launcher already open — it holds the share's permission, the shell holds
 * none — and are offered by number ({@link AppOffers}); the computer takes
 * each on a {@code get} connection, as it takes a dragged-out file, and says
 * how it went ({@code sendDone}), which the launcher's card shows.
 *
 * <p>The same link carries the phone's pictures ({@link AppPhotos}), which need
 * nothing installed at all.
 *
 * <p>What the phone has of the launcher is read from the package manager, in
 * this process: nothing is woken to find out. The share entry is declared
 * disabled; the launcher is asked to enable it when a computer that takes
 * shares links, and to set it back to the manifest's word when one that does
 * not links — the shell may not change an ordinary app's components itself.
 */
final class AppSend {

    /** Where the launcher knocks (SendQueue.PORT names the same). */
    static final int PORT = 7190;
    private static final String APPS = AppServer.LINUX_PROVIDER;
    private static final ComponentName ENTRY = new ComponentName(WmDaemon.LAUNCHER, WmDaemon.LAUNCHER + ".SendActivity");
    /** The entry's meta-data: the version of what it speaks to this server. */
    private static final String META = "com.ccrstech.openandroiddex.send";
    /** Every version of the entry's protocol this server speaks. */
    private static final int[] SPEAKS = {1};
    /** How often the launcher's card is told how far a file has gone. */
    private static final long PROGRESS_EVERY_MS = 250;
    /** How long after the last share the launcher's provider is let go of, when nothing else uses it. */
    private static final long RELEASE_AFTER_S = 30;
    /** How long a knock's one byte may take to come: the launcher writes it as soon as it is connected. */
    private static final int KNOCK_READ_MS = 300;
    /** How often the port is tried again while something else on the phone has it. */
    private static final long PORT_AGAIN_S = 10;
    /**
     * How long one of a share's files may take to open (a cloud file is
     * fetched first). One that takes longer counts as unreadable, and so do the
     * share's files after it: they are most likely from the same place. The
     * launcher gives up on its side first (SendQueue.OPEN_MS, 50 s), so its
     * binder thread is free again, and says why; this is for a launcher that
     * does not answer at all.
     */
    private static final long OPEN_ITEM_MS = 60_000;
    /** Shares whose files are opened at once; more wait, up to {@link #SHARES_WAITING}, and past that are refused. */
    private static final int SHARES_OPENING = 3;
    private static final int SHARES_WAITING = 16;

    /** One computer's link. */
    final class Link {
        final LocalSocket sock;
        final Wire wire;
        volatile boolean share;
        volatile String computer = "";
        volatile String os = "";
        private AppPhotos photos;

        Link(LocalSocket sock, Wire wire) {
            this.sock = sock;
            this.wire = wire;
        }

        synchronized AppPhotos photos() {
            if (photos == null) photos = new AppPhotos(wire);
            return photos;
        }

        void close() {
            AppPhotos p;
            synchronized (this) {
                p = photos;
            }
            if (p != null) p.stop();
            // Shut down first: closing alone does not wake a thread blocked reading a LocalSocket.
            try {
                sock.shutdownInput();
            } catch (IOException ignored) {
            }
            try {
                sock.shutdownOutput();
            } catch (IOException ignored) {
            }
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }

        void say(JSONObject j) {
            try {
                wire.json(j);
            } catch (IOException ignored) {
                // the computer went: its reader's end says so
            }
        }
    }

    /** A share handed to a computer and not yet said to be done. */
    private static final class Pending {
        final int id;
        final Link link;
        final int offer;

        Pending(int id, Link link, int offer) {
            this.id = id;
            this.link = link;
            this.offer = offer;
        }
    }

    private final AppServer server;
    private final AppOffers offers;
    private volatile Link link;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    /** The launcher asked, one call after another and off every connection's thread. */
    private final ExecutorService asking = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "send-ask");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "send-later");
        t.setDaemon(true);
        return t;
    });
    /**
     * A knock's byte, read off the accepting thread: a connection that sends
     * nothing holds one of these for {@link #KNOCK_READ_MS}, never the port.
     * More waiting than the queue holds are closed unread.
     */
    private final ThreadPoolExecutor readers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "send-knock");
                t.setDaemon(true);
                return t;
            });
    /** A share's files opened, each share on a thread of its own: one slow provider holds up only its own share. */
    private final ThreadPoolExecutor opening = new ThreadPoolExecutor(SHARES_OPENING, SHARES_OPENING, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(SHARES_WAITING), r -> {
                Thread t = new Thread(r, "send-share");
                t.setDaemon(true);
                return t;
            });
    /**
     * One open each, so a share's thread can stop waiting for it: as many as
     * shares open at once, and one more. A call that never comes back holds
     * one; with all of them held, an item is refused at once.
     */
    private final ThreadPoolExecutor opens = new ThreadPoolExecutor(0, SHARES_OPENING + 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "send-open");
                t.setDaemon(true);
                return t;
            });
    /** A take asked for and not yet begun: however many knocks come, one take waits at most. */
    private final AtomicBoolean takeWaits = new AtomicBoolean();
    /** Shares whose files are being opened. */
    private final Set<Integer> beingOpened = ConcurrentHashMap.newKeySet();
    /** Shares taken back while their files were being opened. */
    private final Set<Integer> takenBack = ConcurrentHashMap.newKeySet();
    private ServerSocket port;
    /** Whether a later try at the port is set. */
    private boolean portAgain;
    /** The provider's release, set for later. */
    private java.util.concurrent.ScheduledFuture<?> release;
    private volatile boolean stopping;

    AppSend(AppServer server, AppOffers offers) {
        this.server = server;
        this.offers = offers;
        opening.allowCoreThreadTimeOut(true);
    }

    // ── the link ──

    void serve(LocalSocket s, Wire wire, JSONObject hello) {
        Thread.currentThread().setName("send-link");
        Link l = new Link(s, wire);
        Link old;
        synchronized (this) {
            old = link;
            link = l;
        }
        if (old != null) {
            AppServer.log("send: a new link takes the place of the last");
            old.close();
        }
        try {
            setUp(l, hello);
            boolean listening = listen();
            wire.json(helloOf(listening));
            AppServer.log("send: linked to " + (l.computer.isEmpty() ? "a computer" : l.computer)
                    + (l.share ? ", which takes shares" : ", which takes no shares")
                    + (listening ? "" : "; port " + PORT + " is not this server's"));
            while (!stopping) {
                Wire.Msg m = wire.read();
                if (m == null) break;
                if (m.type != Wire.JSON) continue;
                JSONObject j = new JSONObject(m.text());
                switch (j.optString("t")) {
                    case "send":
                        setUp(l, j);
                        wire.json(helloOf(listen()));
                        break;
                    case "look":
                        setUp(l, null);
                        wire.json(helloOf(listen()));
                        break;
                    case "sendDone":
                        done(j);
                        break;
                    case "sendWait":
                        waits(j.optInt("share", -1));
                        break;
                    case "photos":
                    case "older":
                    case "all":
                    case "thumbs":
                    case "status":
                        l.photos().handle(j);
                        break;
                    default:
                }
            }
        } catch (Exception e) {
            if (!stopping && link == l) AppServer.log("send: " + e);
        } finally {
            boolean last;
            synchronized (this) {
                last = link == l;
                if (last) link = null;
            }
            if (last) unlisten();
            l.close();
            lost(l);
        }
    }

    /** What the computer said of itself — or, with null, nothing new — and the share entry set to match. */
    private void setUp(Link l, JSONObject said) {
        if (said != null) {
            l.share = said.optBoolean("share", true);
            l.computer = said.optString("computer", l.computer);
            l.os = said.optString("os", l.os);
        }
        String launcher = launcher();
        boolean on = entryOn();
        if (l.share && "ready".equals(launcher) && !on) entry(true);
        else if (!l.share && on) entry(false);
    }

    private JSONObject helloOf(boolean listening) throws org.json.JSONException {
        return new JSONObject().put("t", "send").put("ok", true).put("v", 1).put("sdk", Build.VERSION.SDK_INT)
                .put("launcher", launcher()).put("entry", entryOn()).put("port", listening)
                .put("now", System.currentTimeMillis() / 1000);
    }

    /** The server is stopping: the port closed, the link with it. */
    void stopping() {
        stopping = true;
        Link l = link;
        if (l != null) l.close();
        unlisten();
    }

    // ── what the phone has of the launcher ──

    /** {@code ready}, {@code old} (the launcher without the entry, or one this server no longer speaks to) or {@code missing}. */
    static String launcher() {
        PackageManager pm = PhoneContext.get().getPackageManager();
        try {
            // MATCH_DISABLED_COMPONENTS: the entry is disabled until a computer enables it.
            ActivityInfo a = pm.getActivityInfo(ENTRY, PackageManager.GET_META_DATA | 0x200);
            int v = a.metaData == null ? 0 : a.metaData.getInt(META, 0);
            for (int s : SPEAKS) if (s == v) return "ready";
            return "old";
        } catch (PackageManager.NameNotFoundException e) {
            try {
                pm.getPackageInfo(WmDaemon.LAUNCHER, 0);
                return "old";
            } catch (PackageManager.NameNotFoundException e2) {
                return "missing";
            }
        } catch (RuntimeException e) {
            return "missing";
        }
    }

    private static boolean entryOn() {
        try {
            return PhoneContext.get().getPackageManager().getComponentEnabledSetting(ENTRY)
                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The share entry on, or back to what the manifest says (off) — by the
     * launcher itself ({@code share.enable}): the shell may not change a
     * component of an ordinary app ("Shell cannot change component state",
     * Android 15), but an app may change its own. Once per change; the
     * provider is let go of again later ({@link #releaseLater}).
     */
    private void entry(boolean on) {
        try {
            JSONObject r = Providers.call(APPS, "share.enable", new JSONObject().put("on", on));
            if (!r.optBoolean("ok")) AppServer.log("send: the launcher would not set its share entry (" + r.optString("message") + ")");
        } catch (Exception e) {
            AppServer.log("send: the launcher would not set its share entry (" + e + ")");
        }
        AppServer.log("send: the share entry is " + (entryOn() ? "on" : "off") + " now");
        releaseLater();
    }

    // ── the knock ──

    /**
     * Listening on the port, if this server can have it. If something else
     * on the phone has it — another computer's server, or any app — the hello
     * says so ({@code port: false}), the computer shows it, and the port is
     * tried again every {@link #PORT_AGAIN_S} s while the link is up; the
     * computer hears a new hello when it is had.
     */
    private synchronized boolean listen() {
        if (port != null && !port.isClosed()) return true;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
            port = s;
            Thread t = new Thread(() -> knocks(s), "send-port");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException e) {
            if (!portAgain && !stopping) {
                portAgain = true;
                AppServer.log("send: port " + PORT + " is not this server's (" + e.getMessage() + "); trying again every " + PORT_AGAIN_S + " s");
                later.schedule(this::portAgain, PORT_AGAIN_S, TimeUnit.SECONDS);
            }
            return false;
        }
    }

    private void portAgain() {
        synchronized (this) {
            portAgain = false;
        }
        Link l = link;
        if (l == null || stopping) return;
        if (!listen()) return;
        AppServer.log("send: port " + PORT + " is this server's now");
        try {
            l.say(helloOf(true));
        } catch (org.json.JSONException ignored) {
        }
    }

    private synchronized void unlisten() {
        if (port == null) return;
        try {
            port.close();
        } catch (IOException ignored) {
        }
        port = null;
    }

    /**
     * The port, which anyone on the phone may reach: the knock carries
     * nothing, so what it costs is bounded instead — a byte read off this
     * thread, and one take waiting at most however many knocks come.
     */
    private void knocks(ServerSocket s) {
        while (!s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                return;
            }
            try {
                readers.execute(() -> knock(c));
            } catch (RejectedExecutionException e) {
                shut(c);
            }
        }
    }

    private void knock(Socket c) {
        int b = -1;
        try {
            c.setSoTimeout(KNOCK_READ_MS);
            InputStream in = c.getInputStream();
            b = in.read();
        } catch (IOException ignored) {
        } finally {
            shut(c);
        }
        // 'P': anyone there — answered by the connection itself. 'S': something waits. 'C': something changed.
        if (b == 'S' || b == 'C') wantTake();
    }

    private static void shut(Socket c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /** A take, unless one is already waiting to begin: that one will see whatever this knock was for. */
    private void wantTake() {
        if (!takeWaits.compareAndSet(false, true)) return;
        try {
            asking.execute(() -> {
                takeWaits.set(false);
                take();
            });
        } catch (RejectedExecutionException e) {
            takeWaits.set(false);
        }
    }

    // ── a share ──

    /** What the launcher has: taken, opened, offered, told. */
    private void take() {
        Link l = link;
        if (l == null) return;
        JSONObject r;
        try {
            if (!l.share) {
                Providers.call(APPS, "share.refuse", new JSONObject().put("computer", l.computer).put("why", "off"));
                // The provider the call held is let go of later, as after a take.
                releaseLater();
                return;
            }
            r = Providers.call(APPS, "share.take", new JSONObject().put("computer", l.computer).put("os", l.os).put("v", 1));
        } catch (Exception e) {
            AppServer.log("send: the launcher would not say what it has (" + e + ")");
            releaseLater();
            return;
        }
        JSONArray cancelled = r.optJSONArray("cancelled");
        for (int i = 0; cancelled != null && i < cancelled.length(); i++) cancel(cancelled.optInt(i, -1));
        JSONArray shares = r.optJSONArray("shares");
        for (int i = 0; shares != null && i < shares.length(); i++) {
            JSONObject sh = shares.optJSONObject(i);
            if (sh == null) continue;
            int id = sh.optInt("id", -1);
            if ("text".equals(sh.optString("kind"))) {
                try {
                    hand(l, sh);
                } catch (Exception e) {
                    AppServer.log("send: share " + id + ": " + e);
                }
                continue;
            }
            // Files: opened on a thread of their own, so a slow provider holds up no other share,
            // no Cancel and no card's "done" — all of which come through this thread.
            beingOpened.add(id);
            try {
                opening.execute(() -> {
                    try {
                        hand(l, sh);
                    } catch (Exception e) {
                        AppServer.log("send: share " + id + ": " + e);
                    } finally {
                        beingOpened.remove(id);
                        takenBack.remove(id);
                        releaseLater();
                    }
                });
            } catch (RejectedExecutionException e) {
                beingOpened.remove(id);
                AppServer.log("send: share " + id + " refused: " + SHARES_OPENING + " shares are opening and " + SHARES_WAITING + " wait");
                try {
                    tellLauncher(id, new JSONObject().put("ok", false).put("did", "").put("landed", new JSONArray())
                            .put("failed", 0).put("message", "stuck"), l);
                } catch (org.json.JSONException ignored) {
                }
            }
        }
        releaseLater();
    }

    private void hand(Link l, JSONObject sh) throws Exception {
        int id = sh.optInt("id", -1);
        if ("text".equals(sh.optString("kind"))) {
            pending.put(id, new Pending(id, l, 0));
            l.say(new JSONObject().put("t", "shared").put("share", id).put("offer", 0).put("kind", "text")
                    .put("text", sh.optString("text")).put("subject", sh.optString("subject")));
            AppServer.log("send: share " + id + " (text, " + sh.optString("text").length() + " characters) handed to the computer");
            return;
        }
        JSONArray items = sh.optJSONArray("items");
        int n = items == null ? 0 : items.length();
        List<AppOffers.Item> offered = new ArrayList<>();
        JSONArray failed = new JSONArray();
        long t0 = SystemClock.uptimeMillis();
        boolean slow = false;
        for (int i = 0; i < n; i++) {
            if (takenBack.contains(id) || link != l) break;
            JSONObject it = items.getJSONObject(i);
            int index = it.optInt("i", i);
            if (slow) {
                failed.put(index);
                continue;
            }
            String name = it.optString("name", "file");
            Uri uri = Uri.parse("content://" + APPS + "/share/" + id + "/" + index + "/" + Uri.encode(name));
            try {
                Content.Opened o;
                try {
                    o = openWithin(uri);
                } catch (TimeoutException e) {
                    AppServer.log("send: share " + id + " item " + index + " was not open after " + OPEN_ITEM_MS / 1000
                            + " s; it and the rest count as unreadable");
                    slow = true;
                    failed.put(index);
                    continue;
                }
                AppOffers.Item item = new AppOffers.Item();
                item.uri = uri.toString();
                item.name = name;
                item.mime = it.optString("mime", "");
                long size = it.optLong("size", -1);
                item.size = size >= 0 ? size : o.length;
                item.opened = o;
                item.handedOver = true;
                item.progress = progress(id, offered.size(), n);
                offered.add(item);
            } catch (IOException | RuntimeException e) {
                AppServer.log("send: share " + id + " item " + index + " could not be opened (" + e + ")");
                failed.put(index);
            }
        }
        if (takenBack.contains(id) || link != l) {
            // Taken back while it opened (the launcher's card already says so), or the link went.
            for (AppOffers.Item it : offered) {
                Content.Opened o = it.takeOpened();
                if (o != null) o.close();
            }
            boolean back = takenBack.contains(id);
            if (!back) {
                tellLauncher(id, new JSONObject().put("ok", false).put("did", "").put("landed", new JSONArray())
                        .put("failed", 0).put("message", "lost"), l);
            }
            AppServer.log("send: share " + id + (back ? " taken back" : " lost with its link") + " while it opened");
            return;
        }
        // The card may go now: everything it shared is open here, or not to be had.
        try {
            Providers.call(APPS, "share.opened", new JSONObject().put("id", id).put("failed", failed));
        } catch (Exception e) {
            AppServer.log("send: share.opened: " + e);
        }
        if (offered.isEmpty()) {
            tellLauncher(id, new JSONObject().put("ok", false).put("did", "").put("landed", new JSONArray())
                    .put("failed", n).put("message", "unreadable"), l);
            return;
        }
        // A share's offer is the share's: dropped as it ends (done, taken back, lost), never closed for its age.
        int offer = offers.putHeld(offered);
        JSONObject say = new JSONObject().put("t", "shared").put("share", id).put("offer", offer).put("kind", "files")
                .put("items", AppOffers.describe(offered)).put("unread", failed.length()).put("total", n);
        String preview = sh.optString("preview", "");
        if (!preview.isEmpty()) say.put("preview", preview);
        // Under the lock Cancel takes: either the computer hears "shared" and then "cancelled", or
        // the share was taken back first and it hears neither.
        synchronized (pending) {
            if (takenBack.contains(id) || link != l) {
                offers.drop(offer);
                AppServer.log("send: share " + id + " taken back as it was handed over");
                return;
            }
            pending.put(id, new Pending(id, l, offer));
            l.say(say);
        }
        AppServer.log("send: share " + id + " (" + offered.size() + " of " + n + " files open in "
                + (SystemClock.uptimeMillis() - t0) + " ms) handed to the computer");
    }

    /** One open handed between its thread and the share's: what it opened, or that the share gave up on it. */
    private static final class Handoff {
        Content.Opened opened;
        boolean gaveUp;
    }

    /**
     * {@code uri} opened within {@link #OPEN_ITEM_MS}. A launcher that never
     * answers keeps its binder call — that cannot be cut — on one of
     * {@link #opens}, which closes whatever it opens in the end.
     */
    private Content.Opened openWithin(Uri uri) throws IOException, TimeoutException {
        Handoff h = new Handoff();
        Future<?> f;
        try {
            f = opens.submit(() -> {
                Content.Opened o = Content.open(uri);
                synchronized (h) {
                    if (h.gaveUp) o.close();
                    else h.opened = o;
                }
                return null;
            });
        } catch (RejectedExecutionException e) {
            throw new IOException("every opener is held by an open that has not come back");
        }
        try {
            f.get(OPEN_ITEM_MS, TimeUnit.MILLISECONDS);
            synchronized (h) {
                return h.opened;
            }
        } catch (TimeoutException e) {
            synchronized (h) {
                h.gaveUp = true;
                if (h.opened != null) h.opened.close();
            }
            throw e;
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException(c);
        } catch (InterruptedException e) {
            synchronized (h) {
                h.gaveUp = true;
                if (h.opened != null) h.opened.close();
            }
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** How far item {@code i} of {@code n} has gone, to the launcher's card a few times a second. */
    private AppOffers.Progress progress(int id, int i, int n) {
        long[] last = {0};
        return (bytes, total) -> {
            long now = SystemClock.uptimeMillis();
            if (now - last[0] < PROGRESS_EVERY_MS && bytes != total) return;
            last[0] = now;
            try {
                Providers.call(APPS, "share.progress", new JSONObject().put("id", id).put("i", i).put("n", n)
                        .put("bytes", bytes).put("total", total));
            } catch (Exception ignored) {
                // the card is the launcher's: it may have gone
            }
        };
    }

    /** How the computer said it went. */
    private void done(JSONObject j) {
        int id = j.optInt("share", -1);
        Pending p = pending.remove(id);
        if (p == null) return;
        if (p.offer > 0) offers.drop(p.offer);
        try {
            JSONObject said = new JSONObject().put("ok", j.optBoolean("ok")).put("did", j.optString("did"))
                    .put("landed", j.optJSONArray("landed") == null ? new JSONArray() : j.optJSONArray("landed"))
                    .put("failed", j.optInt("failed")).put("message", j.optString("message"));
            asking.execute(() -> tellLauncher(id, said, p.link));
        } catch (org.json.JSONException ignored) {
        }
        AppServer.log("send: share " + id + " done on the computer: " + j.optString("did") + (j.optBoolean("ok") ? "" : " (" + j.optString("message") + ")"));
        releaseLater();
    }

    /** The computer has as many files coming in as it takes at once: the card says this one waits its turn. */
    private void waits(int id) {
        if (!pending.containsKey(id)) return;
        asking.execute(() -> {
            try {
                Providers.call(APPS, "share.wait", new JSONObject().put("id", id));
            } catch (Exception e) {
                AppServer.log("send: share.wait: " + e);
            }
        });
    }

    /** The launcher's card took it back before the computer had it all. */
    private void cancel(int id) {
        Pending p;
        synchronized (pending) {
            if (beingOpened.contains(id)) takenBack.add(id);
            p = pending.remove(id);
        }
        if (p == null) return;
        if (p.offer > 0) offers.drop(p.offer);
        try {
            p.link.say(new JSONObject().put("t", "cancelled").put("share", id));
        } catch (org.json.JSONException ignored) {
        }
        AppServer.log("send: share " + id + " taken back on the phone");
    }

    /** A link that ended with shares on it: lost, which the launcher's card says. */
    private void lost(Link l) {
        for (Pending p : new ArrayList<>(pending.values())) {
            if (p.link != l || pending.remove(p.id) == null) continue;
            if (p.offer > 0) offers.drop(p.offer);
            try {
                JSONObject said = new JSONObject().put("ok", false).put("did", "").put("landed", new JSONArray())
                        .put("failed", 0).put("message", "lost");
                asking.execute(() -> tellLauncher(p.id, said, l));
            } catch (org.json.JSONException ignored) {
            }
            AppServer.log("send: share " + p.id + " lost with its link");
        }
    }

    private void tellLauncher(int id, JSONObject said, Link l) {
        try {
            Providers.call(APPS, "share.done", said.put("id", id).put("computer", l.computer));
        } catch (Exception e) {
            AppServer.log("send: share.done: " + e);
        }
    }

    /**
     * The launcher's provider let go of a while after the last share, unless
     * a window or the touchpad still uses it: a held provider keeps the
     * launcher's process from being reclaimed.
     */
    private synchronized void releaseLater() {
        // One at a time: each take would otherwise leave one more waiting here.
        if (release != null) release.cancel(false);
        release = later.schedule(() -> {
            if (pending.isEmpty() && server.idle()) {
                asking.execute(() -> {
                    if (pending.isEmpty() && server.idle()) Providers.release(APPS);
                });
            }
        }, RELEASE_AFTER_S, TimeUnit.SECONDS);
    }
}
