package com.ccrstech.openandroiddex.wmd;

import android.content.ComponentName;
import android.hardware.display.VirtualDisplay;
import android.net.LocalSocket;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.ccrstech.openandroiddex.launcher.LinuxStreamEncoder;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/**
 * One app in one window on the computer: a display of its own, sized to the
 * window in the computer screen's pixels, the app started on it, its picture
 * encoded on the phone's hardware encoder — straight from the display on a
 * cable, paced to the link over Wi-Fi — and the window's mouse and keyboard put
 * into it (doc/apps-on-pc.md).
 *
 * <p>The window's socket is this session: the display is made when the window
 * opens and goes when it closes. Closing it hands the app back to the phone —
 * the display is let go without destroying what is on it, and Android moves its
 * tasks to the phone's own screen, behind what is there, as Recents keeps them —
 * unless the window asked for the app to be closed with it. When the app itself
 * goes (its last activity finished), the window is told, and closes.
 *
 * <p>A connection that goes without the window closing — the cable pulled, a
 * Wi-Fi link that dropped — is not the window's end. The app stays on its
 * display and the window waits {@link #AWAY_MS} for the computer to come back,
 * by another way to the phone or the same one, and ask for it by its key
 * ({@link #attach}): the same display and the same app, its picture again from
 * a key frame. Only if the computer does not, or the app is opened on the
 * phone meanwhile, does the window end as it would have, the app handed back.
 *
 * <p>The pace, the bitrate's rule and their numbers are the desktop stream's
 * (doc/apps-on-pc.md, "The pace"; LinuxStream's before it): the newest
 * frame goes into the encoder once the encoder holds fewer than two and the link
 * holds fewer than its round trip in frames, never faster than the window asked
 * for; a second in which a frame waited on the link for a fifth of it drops the
 * bitrate by 30 %, three seconds it kept up raise it by 25 %.
 */
final class AppWindow implements LinuxStreamEncoder.Output, AppVideo.Gate {

    /** The sizes a window may ask for: what an encoder takes, and what a window can be. */
    static final int MIN_W = 160, MIN_H = 160, MAX_W = 4096, MAX_H = 4096;
    /** The encoder may be this many frames behind before the next goes in… */
    private static final int MAX_IN_FLIGHT = 2;
    /** …but never held longer than this, or a lost count would stall the picture. */
    private static final long PACE_MAX_MS = 100;
    /** Frames the link holds: its round trip in frames and the one on the wire, within these. */
    private static final int MIN_WINDOW = 2;
    private static final int MAX_WINDOW = 12;
    private static final int RTT_SAMPLES = 32;
    /** Only a computer that stopped acknowledging altogether is waited out, and logged. */
    private static final long ACK_GIVE_UP_MS = 3000;
    private static final double BEHIND_SLOW = 0.2;
    private static final double BEHIND_FINE = 0.02;
    private static final double BITRATE_DOWN = 0.7;
    private static final double BITRATE_UP = 1.25;
    private static final int MIN_BITRATE = 500_000;
    private static final int MAX_BITRATE = 80_000_000;
    /**
     * How often the display's tasks are looked at — what closes a window whose
     * app has gone: often for a moment after the window opened or was used
     * (an app goes when someone presses Back, or its own close), seldom
     * otherwise. Each look is a binder call that unpacks every task's
     * configuration; at 400 ms all the time it was the largest part of an idle
     * window's cost on a Galaxy A13 (4.9 % of a core against scrcpy's 2.3 %).
     */
    static final long WATCH_MS = 300;
    private static final long WATCH_IDLE_MS = 2000;
    private static final long WATCH_BUSY_FOR_MS = 4000;
    /** A display empty for this long, after it had the app, means the app has gone. */
    private static final long GONE_AFTER_MS = 900;
    /** A launch that has put nothing on the display after this long went somewhere else. */
    private static final long NEVER_CAME_MS = 12_000;
    /** A notification's own intent that has not put its app on the window's display by now went elsewhere, or nowhere. */
    private static final long NOTIFICATION_ARRIVES_MS = 1500;
    /**
     * How long a window whose connection went keeps its app on its display for
     * the computer to come back to: a Samsung phone's adbd is back about 3 s
     * after the cable goes, Wi-Fi debugging a few seconds after that. Longer,
     * and the phone may be in its owner's hand: the app is theirs again.
     */
    static final long AWAY_MS = 30_000;

    private final AppServer server;
    /** The computer's connection now; another replaces it when the computer comes back another way. */
    private volatile LocalSocket sock;
    private volatile Wire wire;
    private final JSONObject open;
    private final int serial;
    /** What the computer calls this window, to come back to it on a new connection; empty, it cannot. */
    final String key;
    volatile boolean closing;
    /** The connection went without the window closing: it waits for the computer to come back. */
    private volatile boolean away;
    /** It ended while away: its app opened on the phone, or the computer did not come back in time. */
    volatile boolean endedAway;

    private AppVideo video;
    private VirtualDisplay display;
    volatile int displayId = -1;
    private AppInput input;
    private String pkg;
    private ComponentName component;
    /** The X window this window shows, for one of the phone's Linux apps; 0 for an Android app. */
    private int xid;
    private volatile int width;
    private volatile int height;
    private volatile int dpi;
    /** Whether the phone made the display trusted: said again to a computer that comes back. */
    private volatile boolean trusted;
    /** What the window wants done with the app when it closes: "phone" hands it back, "close" ends it. */
    private volatile String onClose = "phone";
    /** On a cable: the display draws straight into the encoder (AppVideo#direct). */
    private boolean direct;

    // the open request
    private volatile int bitrate;
    private volatile int ceiling;
    private int fps = 60;

    // pacing: the GL thread's draws, the drain's sends, the input thread's acknowledgements
    private final Object pace = new Object();
    private int inFlight;
    private long lastDrawAt;
    private long sent;
    private long acked;
    private final ArrayDeque<Long> sentAt = new ArrayDeque<>();
    private final long[] rtts = new long[RTT_SAMPLES];
    private int rttCount;
    private long rttMedianNs = -1;
    private long minRttNs = Long.MAX_VALUE;
    private boolean ackStallLogged;
    private long linkBlockedSince;
    private long linkWaitMs;
    private int keptUp;

    // stats, drain thread
    private long statsSince = SystemClock.elapsedRealtime();
    private int statsFrames;
    private long statsBytes;
    private long statsCaptureUs;
    private long firstFrameAt;

    // the task watcher's
    private final long openedAt = SystemClock.uptimeMillis();
    private boolean hadTask;
    private long emptySince;
    private int topTask = -1;
    private int lastOrientation = Integer.MIN_VALUE;
    private int lastColor;
    private String lastTop = "";
    /** The keyboard was asked to come here; a key waits for no focus that will never come. */
    private volatile boolean wantsKeys;
    /** When the computer last sent input; the watcher looks closely for a while after it. */
    private volatile long inputAt = SystemClock.uptimeMillis();
    private long watchedAt;

    AppWindow(AppServer server, LocalSocket sock, Wire wire, JSONObject open, int serial) {
        this.server = server;
        this.sock = sock;
        this.wire = wire;
        this.open = open;
        this.serial = serial;
        this.key = open.optString("key", "");
    }

    // ── the window's life ──

    /**
     * The window, on the thread of the connection that opened it: its display
     * and app, then the computer's input on each connection it has in turn,
     * until it closes — or a connection goes and the computer does not come back.
     */
    void run() {
        try {
            begin();
            while (true) {
                Wire w = wire;
                try {
                    inputLoop(w);
                } catch (IOException e) {
                    // the connection went: as its end, below
                }
                if (closing) break;
                if (wire != w) continue; // another connection has it already
                if (!waitForComputer(w)) break;
            }
        } catch (Exception e) {
            if (!closing) {
                AppServer.log("window " + serial + " (" + pkg + "): " + e);
                state("failed", message(e));
            }
        } finally {
            end();
        }
    }

    /**
     * The connection {@code gone} went without the window closing. The app
     * stays on its display, nothing is encoded for nobody, and the window
     * waits for the computer to come back on a new connection. False if it
     * does not in {@link #AWAY_MS}, or the window ends meanwhile.
     */
    private boolean waitForComputer(Wire gone) {
        // A computer that gave no key cannot ask for the window again.
        if (key.isEmpty()) return false;
        synchronized (pace) {
            inFlight = 0;
            sent = acked = 0;
            sentAt.clear();
            linkBlockedSince = 0;
        }
        long until;
        synchronized (this) {
            if (wire != gone || closing) return !closing;
            away = true;
            until = SystemClock.uptimeMillis() + AWAY_MS;
        }
        if (input != null) input.releaseAll();
        AppServer.log("window " + serial + " (" + pkg + "): the computer's connection went; the app stays on its display for "
                + AWAY_MS / 1000 + " s, for the computer to come back");
        synchronized (this) {
            while (wire == gone && !closing) {
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (wire != gone && !closing) return true;
            if (!closing) {
                endedAway = true;
                AppServer.log("window " + serial + " (" + pkg + "): the computer did not come back");
            }
        }
        return false;
    }

    /**
     * The computer again, on {@code s} — over another way to the phone, or the
     * same one back: this window's display and app, its picture from a key
     * frame on (the one already on the display goes at once). The connection
     * it replaces, if it is somehow still open, is closed. The link's numbers
     * are found again: a cable and Wi-Fi carry differently. False if the window
     * is ending.
     */
    boolean attach(LocalSocket s, Wire w, JSONObject req) throws Exception {
        LocalSocket old;
        synchronized (this) {
            if (closing || endedAway) return false;
            old = sock;
            sock = s;
            wire = w;
            away = false;
            notifyAll();
        }
        // Said on the new connection before the old one closes: the computer
        // knows the window moved before it sees its last connection end.
        w.json(new JSONObject().put("t", "attached").put("display", displayId));
        if (old != s) {
            // Shut, not only closed: a close does not wake the window's own
            // thread, blocked reading it, and that thread reads the new one next.
            shutdownQuietly(old);
            closeQuietly(old);
        }
        inputAt = SystemClock.uptimeMillis();
        // The next key goes to this window: the computer's keyboard is this window's again.
        wantsKeys = true;
        synchronized (pace) {
            inFlight = 0;
            sent = acked = 0;
            sentAt.clear();
            rttCount = 0;
            rttMedianNs = -1;
            minRttNs = Long.MAX_VALUE;
            linkBlockedSince = 0;
            linkWaitMs = 0;
            ackStallLogged = false;
            keptUp = 0;
        }
        sayDisplay();
        int nw = even(clamp(req.optInt("w", width), MIN_W, MAX_W));
        int nh = even(clamp(req.optInt("h", height), MIN_H, MAX_H));
        int nd = clamp(req.optInt("dpi", dpi), 72, 960);
        boolean wantDirect = req.optBoolean("direct", direct);
        synchronized (this) {
            // On a cable the display draws straight into the encoder; over Wi-Fi through the texture, paced.
            AppVideo old2 = null;
            if (wantDirect != direct) {
                old2 = video;
                video = AppVideo.open(String.valueOf(serial), nw, nh, wantDirect);
                direct = wantDirect;
                if (!direct) display.setSurface(video.sink());
            } else if (nw != width || nh != height) {
                video.resize(nw, nh);
            }
            if (nw != width || nh != height || nd != dpi) display.resize(nw, nh, nd);
            width = nw;
            height = nh;
            dpi = nd;
            bitrate = ceiling = clamp(req.optInt("bitrate", defaultBitrate(nw, nh)), MIN_BITRATE, MAX_BITRATE);
            newEncoder();
            if (old2 != null) old2.close();
        }
        AppServer.log("window " + serial + " (" + pkg + "): the computer is back, " + (direct ? "on a cable" : "paced")
                + ", " + width + "x" + height);
        return true;
    }

    private void begin() throws Exception {
        xid = open.optInt("linux", 0);
        if (xid != 0) {
            pkg = "linux:0x" + Integer.toHexString(xid);
        } else {
            pkg = open.optString("pkg");
            if (pkg.isEmpty()) throw new IOException("no app named");
            component = server.catalog().component(pkg, open.optString("activity"));
            if (component == null) throw new IOException(pkg + " has nothing to open");
        }
        width = even(clamp(open.optInt("w", 412), MIN_W, MAX_W));
        height = even(clamp(open.optInt("h", 892), MIN_H, MAX_H));
        dpi = clamp(open.optInt("dpi", 160), 72, 960);
        fps = clamp(open.optInt("fps", 60), 10, 120);
        bitrate = clamp(open.optInt("bitrate", defaultBitrate(width, height)), MIN_BITRATE, MAX_BITRATE);
        ceiling = bitrate;
        if ("close".equals(open.optString("onClose"))) onClose = "close";
        direct = open.optBoolean("direct", false);

        long t0 = SystemClock.uptimeMillis();
        video = AppVideo.open(String.valueOf(serial), width, height, direct);
        boolean[] madeTrusted = new boolean[1];
        display = server.createDisplay(width, height, dpi, video.sink(), madeTrusted);
        trusted = madeTrusted[0];
        displayId = display.getDisplay().getDisplayId();
        input = new AppInput(displayId, server.clipboard());
        server.hideIme(displayId);
        long t1 = SystemClock.uptimeMillis();
        // The app first: it takes far longer to draw than the encoder takes to make.
        // Opened from one of its notifications, by that notification's own
        // intent: the conversation it is about, not the app's front screen.
        String notif = open.optString("notif", "");
        AppNotifications notifications = xid == 0 && !notif.isEmpty() ? server.notifications() : null;
        String how;
        // Straight onto the window's display only where Android takes the app's
        // own intent there from the shell: a trusted display — an untrusted one
        // (Android 12's shell can make no other) takes no activity another app
        // asks onto it — on Android 16, whose "allow always" lends the intent the
        // shell's leave to start an activity from the background. Before it,
        // with the app nowhere on screen, the start is blocked (13, and 15 with
        // "allowed") — and 15 still moves a task the app has to the display,
        // empty of the conversation, which would pass for it having arrived.
        boolean straight = trusted && Build.VERSION.SDK_INT >= 36;
        if (notifications != null && straight && notifications.launch(notif, pkg, displayId)
                && arrives(displayId, pkg, NOTIFICATION_ARRIVES_MS)) {
            how = "its notification";
        } else {
            // Else the app is started in the window as any app is, and then its
            // notification's intent fired with no display: on screen now, the app
            // may start an activity, and that one joins its task — in the window.
            // (An app that kept the conversation in its task on the phone's own
            // screen is brought over with it, the notification gone with the tap.)
            how = xid != 0 ? AppTasks.startLinuxWindow(xid, displayId) : AppTasks.start(component, displayId);
            if (notifications != null && notifications.after(notif)) how += ", then its notification";
        }
        long t2 = SystemClock.uptimeMillis();
        sayDisplay();
        newEncoder();
        long t3 = SystemClock.uptimeMillis();
        AppServer.log("window " + serial + ": " + (xid != 0 ? pkg : component.flattenToShortString()) + " on display " + displayId
                + " (" + width + "x" + height + "/" + dpi + (trusted ? ", trusted" : ", untrusted")
                + (direct ? ", straight into the encoder" : ", paced")
                + "): display " + (t1 - t0) + " ms, launch " + (t2 - t1) + " ms by " + how
                + ", encoder " + (t3 - t2) + " ms");
        server.watch(this);
    }

    /**
     * {@code pkg}'s own task on the window's display within {@code ms} — not
     * just anything: Android 13 puts a launcher's secondary home on a new
     * display, which an intent that never started would be taken for.
     */
    private static boolean arrives(int displayId, String pkg, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        do {
            try {
                for (AppTasks.Task t : AppTasks.onDisplay(displayId)) {
                    if (pkg.equals(t.pkg)) return true;
                }
            } catch (Throwable t) {
                return true; // the platform will not say: the watcher decides, as for any window
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < until);
        return false;
    }

    /** The window's display, and the app's name, to the computer. */
    private void sayDisplay() throws IOException {
        JSONObject d = new JSONObject();
        try {
            d.put("t", "display").put("id", displayId).put("w", width).put("h", height).put("dpi", dpi)
                    .put("trusted", trusted);
            if (xid == 0) d.put("label", server.catalog().label(pkg));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        wire.json(d);
    }

    private void end() {
        closeAll(null);
        server.forget(this);
        if (input != null) input.releaseAll();
        if (display != null && xid != 0) {
            // A Linux window's task belongs on no phone screen: it goes, and its
            // app is asked to close the window — which it may keep, for unsaved
            // work, and then the computer is told it is back.
            try {
                for (AppTasks.Task t : AppTasks.onDisplay(displayId)) AppTasks.remove(t.id);
            } catch (Throwable t) {
                AppServer.log("window " + serial + ": could not end the Linux window's task (" + t + ")");
            }
            release();
        } else if (display != null) {
            if ("close".equals(onClose)) {
                try {
                    for (AppTasks.Task t : AppTasks.onDisplay(displayId)) AppTasks.remove(t.id);
                } catch (Throwable t) {
                    AppServer.log("window " + serial + ": could not close the app (" + t + ")");
                }
                release();
            } else {
                handBack();
            }
        }
        if (video != null) video.close();
        if (endedAway) server.endedAway(key);
        AppServer.log("window " + serial + " (" + pkg + ") closed"
                + (xid != 0 ? ", its app asked to close it" : "close".equals(onClose) ? ", the app with it" : ", the app back on the phone"));
    }

    private void release() {
        try {
            display.release();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Let the display go with the app still on it, and put the app back on the
     * phone — behind what the phone shows. A display let go without
     * DESTROY_CONTENT_ON_REMOVAL moves its tasks to the phone's own screen ON
     * TOP (seen on a Galaxy A13, Android 12: the Clock opened over Chrome), so
     * each one is sent to the back as soon as it lands, and if that is refused
     * what the phone showed before is brought back over it.
     */
    private void handBack() {
        List<Object> ours = new java.util.ArrayList<>();
        java.util.Set<Integer> ourIds = new java.util.HashSet<>();
        int phoneTop = -1;
        try {
            for (Wm.TaskRec t : Wm.tasksOnDisplay(displayId)) {
                ours.add(t.token);
                ourIds.add(t.taskId);
            }
            for (Wm.TaskRec t : Wm.tasksOnDisplay(0)) {
                if (t.visible) {
                    phoneTop = t.taskId;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // Android 11 and older: no root tasks to ask about; the app lands where Android puts it
        }
        release();
        if (ours.isEmpty()) return;
        // The tasks move when WindowManager gets to the display's removal, after
        // release() has returned: sent back before they land, they land on top
        // anyway (one close in twelve on the Android 16 emulator).
        waitUntilOnPhone(ourIds, 2000);
        try {
            Object wct = Wm.newTransaction();
            for (Object token : ours) Wm.reorder(wct, token, false);
            Wm.applyTransaction(wct);
        } catch (Throwable t) {
            AppServer.log("window " + serial + ": could not send the app behind (" + t + ")");
        }
        if (phoneTop < 0) return;
        try {
            List<Wm.TaskRec> now = Wm.tasksOnDisplay(0);
            if (!now.isEmpty() && now.get(0).taskId != phoneTop && ourIds.contains(now.get(0).taskId)) {
                AppTasks.focus(phoneTop);
            }
        } catch (Throwable t) {
            AppServer.log("window " + serial + ": could not put the phone's screen back (" + t + ")");
        }
    }

    /** Until one of {@code ids} is on the phone's own screen, or {@code ms} have passed. */
    private static void waitUntilOnPhone(java.util.Set<Integer> ids, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            try {
                for (Wm.TaskRec t : Wm.tasksOnDisplay(0)) {
                    if (ids.contains(t.taskId)) return;
                }
            } catch (Throwable t) {
                return;
            }
            SystemClock.sleep(15);
        }
    }

    private void newEncoder() throws IOException {
        synchronized (pace) {
            // A frame the old encoder still had is not coming out of the new one.
            inFlight = 0;
        }
        AppVideo.Encoder e;
        try {
            e = video.start(bitrate, fps, this, this);
        } catch (IOException refused) {
            if (!direct) throw refused;
            // No encoder takes the display's own size as it is: through the texture after all.
            AppServer.log("window " + serial + ": " + refused.getMessage() + " — pacing it instead");
            video.close();
            direct = false;
            video = AppVideo.open(String.valueOf(serial), width, height, false);
            if (display != null) display.setSurface(video.sink());
            e = video.start(bitrate, fps, this, this);
        }
        if (direct && display != null) display.setSurface(video.sink());
        JSONObject v = new JSONObject();
        try {
            v.put("t", "video").put("codec", "avc").put("encoder", e.name).put("hw", e.hardware)
                    .put("w", e.width).put("h", e.height)
                    .put("cw", e.scaled ? e.width : e.srcWidth).put("ch", e.scaled ? e.height : e.srcHeight)
                    .put("dw", e.srcWidth).put("dh", e.srcHeight)
                    .put("bitrate", e.bitrate).put("fps", e.fps).put("lowLatency", e.lowLatency)
                    .put("display", displayId).put("dpi", dpi)
                    // Paced frames wait for the computer's acks; a direct stream has no pace to keep.
                    .put("paced", !direct);
        } catch (Exception ignored) {
        }
        wire.json(v);
    }

    /** The window is {@code w}x{@code h} pixels at {@code newDpi} now, and so is the display. */
    private synchronized void resize(int w, int h, int newDpi) throws IOException {
        if (w == width && h == height && newDpi == dpi) return;
        boolean sized = w != width || h != height;
        if (sized) video.resize(w, h);
        display.resize(w, h, newDpi);
        width = w;
        height = h;
        dpi = newDpi;
        if (sized) newEncoder();
    }

    // ── the task watcher (the server's timer thread) ──

    /** Look at what is on the display now. False once the window should go. */
    boolean watchTick() {
        if (closing || displayId < 0) return false;
        long since = SystemClock.uptimeMillis();
        long every = !hadTask || since - inputAt < WATCH_BUSY_FOR_MS || emptySince != 0 ? WATCH_MS : WATCH_IDLE_MS;
        if (since - watchedAt < every) return true;
        watchedAt = since;
        List<AppTasks.Task> tasks;
        try {
            tasks = AppTasks.onDisplay(displayId);
        } catch (Throwable t) {
            return true; // asked again next tick
        }
        long now = SystemClock.uptimeMillis();
        if (!tasks.isEmpty()) {
            hadTask = true;
            emptySince = 0;
            AppTasks.Task top = tasks.get(0);
            topTask = top.id;
            if (top.orientation != lastOrientation || top.color != lastColor || !top.pkg.equals(lastTop)) {
                lastOrientation = top.orientation;
                lastColor = top.color;
                lastTop = top.pkg;
                JSONObject j = new JSONObject();
                try {
                    j.put("t", "task").put("task", top.id).put("pkg", top.pkg)
                            .put("orientation", top.orientation).put("color", top.color);
                    if (top.label != null) j.put("label", top.label);
                    wire.json(j);
                } catch (Exception ignored) {
                }
            }
            return true;
        }
        if (hadTask) {
            if (emptySince == 0) {
                emptySince = now;
            } else if (now - emptySince >= GONE_AFTER_MS) {
                // While away, the app leaving is its owner opening it on the phone:
                // a computer that comes back must not take it from their hand.
                if (away) endedAway = true;
                AppServer.log("window " + serial + ": " + pkg + (away ? " was opened on the phone while the computer was away" : " closed itself"));
                state("closed", null);
                closeAll(null);
                return false;
            }
        } else if (now - openedAt >= NEVER_CAME_MS) {
            AppServer.log("window " + serial + ": " + pkg + " never came onto its display");
            state("failed", "This app wouldn't open in a window — it may only run on the phone's own screen.");
            closeAll(null);
            return false;
        }
        return true;
    }

    int displayId() {
        return displayId;
    }

    /** Its display is trusted: another app's intent may put an activity on it. */
    boolean trusted() {
        return trusted;
    }

    /** A connection of the computer's is this window's now: it holds the server. */
    boolean held() {
        return !away && !closing;
    }

    // ── the pace: which frames go into the encoder (GL thread) ──

    @Override
    public long mayDraw() {
        long now = SystemClock.uptimeMillis();
        synchronized (pace) {
            // Nobody to send a frame to: none is drawn until the computer is back.
            if (closing || away) return PACE_MAX_MS;
            // Not faster than the window asked for: a display that composes at
            // 90 or 120 Hz would otherwise cost the link half as much again.
            long gap = 1000L / fps - 2;
            if (now - lastDrawAt < gap) return lastDrawAt + gap - now;
            if (inFlight >= MAX_IN_FLIGHT) {
                long left = lastDrawAt + PACE_MAX_MS - now;
                if (left > 0) return left;
            }
            if (sent - acked >= window()) {
                if (linkBlockedSince == 0) linkBlockedSince = now;
                long left = linkBlockedSince + ACK_GIVE_UP_MS - now;
                if (left > 0) return left;
                if (!ackStallLogged) {
                    ackStallLogged = true;
                    AppServer.log("window " + serial + ": no acknowledgement for " + ACK_GIVE_UP_MS + " ms; going on without");
                }
            }
            if (linkBlockedSince != 0) {
                linkWaitMs += now - linkBlockedSince;
                linkBlockedSince = 0;
            }
            inFlight++;
            lastDrawAt = now;
            return 0;
        }
    }

    /** Frames the link holds: its round trip in frames, and the one on the wire. Under {@link #pace}. */
    private int window() {
        if (rttMedianNs < 0) return MIN_WINDOW + 1;
        long frameNs = 1_000_000_000L / fps;
        long frames = (rttMedianNs + frameNs - 1) / frameNs + 1;
        return (int) Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, frames));
    }

    // ── the drain: encoder → computer ──

    private final byte[] head = new byte[9];

    @Override
    public void packet(byte[] data, int len, long ptsUs, int flags) throws IOException {
        long outUs = System.nanoTime() / 1000;
        Wire w = wire;
        boolean gone = away;
        if (!gone) {
            ByteBuffer.wrap(head).put((byte) flags).putLong(ptsUs);
            try {
                w.send(Wire.VIDEO, head, 9, data, len);
            } catch (IOException e) {
                // The connection went: the window's own thread finds it gone and
                // waits for the computer to come back. Not the encoder's end.
                gone = true;
                if (w == wire) shutdownQuietly(sock);
            }
        }
        if (gone) {
            if (!direct) {
                synchronized (pace) {
                    if (inFlight > 0) inFlight--;
                }
            }
            return;
        }
        if ((flags & LinuxStreamEncoder.FLAG_CONFIG) != 0) return;
        if (!direct) {
            synchronized (pace) {
                if (inFlight > 0) inFlight--;
                sent++;
                sentAt.addLast(System.nanoTime());
            }
        }
        if (firstFrameAt == 0) {
            firstFrameAt = SystemClock.uptimeMillis();
            AppServer.log("window " + serial + ": first frame " + (firstFrameAt - openedAt) + " ms after the window asked");
        }
        statsFrames++;
        statsBytes += len;
        // From the compositor's frame to the encoder's output: what the phone adds.
        statsCaptureUs += Math.max(0, outUs - ptsUs);
        long now = SystemClock.elapsedRealtime();
        if (now - statsSince >= 1000) report(now);
    }

    /** Once a second, from the drain: the numbers, and the bitrate held to what the link carries. */
    private void report(long now) {
        long period = now - statsSince;
        adapt(period, statsFrames);
        JSONObject j = new JSONObject();
        try {
            double secs = period / 1000.0;
            int n = Math.max(1, statsFrames);
            long rtt;
            long best;
            synchronized (pace) {
                rtt = rttMedianNs < 0 ? -1 : rttMedianNs / 1000;
                best = minRttNs == Long.MAX_VALUE ? -1 : minRttNs / 1000;
            }
            j.put("t", "stats").put("fps", Math.round(statsFrames / secs))
                    .put("kbps", Math.round(statsBytes * 8 / 1000.0 / secs))
                    .put("encodeMs", Math.round(statsCaptureUs / 1000.0 / n * 10) / 10.0)
                    .put("rttMs", rtt < 0 ? -1 : Math.round(rtt / 100.0) / 10.0)
                    .put("bestRttMs", best < 0 ? -1 : Math.round(best / 100.0) / 10.0)
                    .put("targetKbps", bitrate / 1000)
                    .put("swapMs", video.swapMs());
        } catch (Exception ignored) {
        }
        statsSince = now;
        statsFrames = 0;
        statsBytes = 0;
        statsCaptureUs = 0;
        try {
            wire.json(j);
        } catch (IOException ignored) {
            // the next frame's send says so
        }
    }

    private void adapt(long periodMs, int frames) {
        long waited;
        synchronized (pace) {
            if (linkBlockedSince != 0) {
                long now = SystemClock.uptimeMillis();
                linkWaitMs += now - linkBlockedSince;
                linkBlockedSince = now;
            }
            waited = linkWaitMs;
            linkWaitMs = 0;
        }
        double behind = (double) waited / Math.max(1, periodMs);
        int now = bitrate;
        if (behind > BEHIND_SLOW) {
            keptUp = 0;
            int next = Math.max(MIN_BITRATE, (int) (now * BITRATE_DOWN));
            if (next < now) setBitrate(next, "the link is behind (" + Math.round(behind * 100) + "% waiting)");
        } else if (behind < BEHIND_FINE && frames >= fps / 4 && now < ceiling) {
            if (++keptUp >= 3) {
                keptUp = 0;
                setBitrate(Math.min(ceiling, (int) (now * BITRATE_UP)), "the link keeps up");
            }
        }
    }

    private void setBitrate(int bps, String why) {
        bitrate = bps;
        video.setBitrate(bps);
        AppServer.log("window " + serial + ": " + why + ": " + Math.round(bps / 1e5) / 10.0 + " Mb/s");
    }

    @Override
    public void stopped(Throwable why) {
        if (closing) return;
        AppServer.log("window " + serial + ": the encoder stopped: " + why);
        state("failed", "The phone's video encoder stopped.");
        closeAll(null);
    }

    // ── the window's input ──

    /** The computer's input on {@code from}, until it ends or the window closes. */
    private void inputLoop(Wire from) throws IOException {
        while (!closing) {
            Wire.Msg m = from.read();
            if (m == null) return;
            byte[] d = m.data;
            // Someone using the window, not the window's own bookkeeping: an ack,
            // or the clock's ping every 2 s, which kept the watcher busy for good.
            if (m.type == Wire.POINTER || m.type == Wire.SCROLL || m.type == Wire.KEY || m.type == Wire.TEXT) {
                inputAt = SystemClock.uptimeMillis();
            }
            switch (m.type) {
                case Wire.ACK:
                    acked();
                    break;
                case Wire.POINTER:
                    if (d.length >= 9) input.pointer(Wire.u16(d, 1), Wire.u16(d, 3), d[0] & 0xff, Wire.i32(d, 5));
                    break;
                case Wire.SCROLL:
                    if (d.length >= 16) {
                        input.scroll(Wire.u16(d, 0), Wire.u16(d, 2), Float.intBitsToFloat(Wire.i32(d, 4)),
                                Float.intBitsToFloat(Wire.i32(d, 8)), Wire.i32(d, 12));
                    }
                    break;
                case Wire.KEY:
                    if (d.length >= 8) key(d[0] != 0, Wire.u16(d, 2), d[1] & 0xff, Wire.i32(d, 4));
                    break;
                case Wire.TEXT:
                    if (d.length > 0) {
                        keyboardHere();
                        input.text(new String(d, StandardCharsets.UTF_8));
                    }
                    break;
                case Wire.JSON: {
                    JSONObject j;
                    try {
                        j = new JSONObject(m.text());
                    } catch (Exception e) {
                        break;
                    }
                    command(j);
                    break;
                }
                default:
            }
        }
    }

    private void key(boolean down, int code, int repeat, int meta) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_FORWARD) {
            input.key(down, code, repeat, meta);
            return;
        }
        if (down) keyboardHere();
        input.key(down, code, repeat, meta);
    }

    /**
     * Make sure the display the keys are aimed at is the one with the focused
     * window. Below Android 14 only one display has one, the one last touched —
     * and a click on this window's title bar touches nothing on the phone.
     */
    private void keyboardHere() {
        if (!wantsKeys) return;
        wantsKeys = false;
        int t = topTask;
        if (t < 0 || AppTasks.focusedDisplay() == displayId) return;
        try {
            AppTasks.focus(t);
        } catch (Throwable e) {
            AppServer.log("window " + serial + ": cannot give it the keyboard (" + e + ")");
        }
    }

    private void command(JSONObject j) throws IOException {
        String t = j.optString("t");
        if (!"ping".equals(t)) inputAt = SystemClock.uptimeMillis();
        switch (t) {
            case "resize": {
                int w = even(clamp(j.optInt("w", width), MIN_W, MAX_W));
                int h = even(clamp(j.optInt("h", height), MIN_H, MAX_H));
                int newDpi = clamp(j.optInt("dpi", dpi), 72, 960);
                resize(w, h, newDpi);
                break;
            }
            case "focus":
                // The window came to the front on the computer: the next key is for it.
                wantsKeys = true;
                break;
            case "blur":
                input.releaseAll();
                input.left();
                break;
            case "leave":
                // The pointer left the window: nothing under it is hovered any more.
                input.left();
                break;
            case "clip":
                // The computer's clipboard, on this window's own socket so it lands
                // before the paste that follows it.
                server.setClipboard(j.optString("text"));
                break;
            case "keyframe":
                video.requestKeyFrame();
                break;
            case "close":
                if (xid != 0 && "ask".equals(j.optString("app"))) {
                    // A Linux window's ✕: its app is asked, and may ask about unsaved
                    // work first; the window goes when the X window does.
                    server.linuxClose(xid);
                    break;
                }
                // The window is closing: what becomes of the app. The socket's end follows.
                onClose = "close".equals(j.optString("app")) ? "close" : "phone";
                closeAll(null);
                break;
            case "bitrate": {
                int asked = clamp(j.optInt("bitrate", ceiling), MIN_BITRATE, MAX_BITRATE);
                ceiling = asked;
                keptUp = 0;
                setBitrate(asked, "the computer's route changed");
                break;
            }
            case "ping": {
                JSONObject p = new JSONObject();
                try {
                    p.put("t", "pong").put("n", j.opt("n")).put("at", System.nanoTime() / 1000);
                } catch (Exception ignored) {
                }
                wire.json(p);
                break;
            }
            default:
        }
    }

    private void acked() {
        synchronized (pace) {
            acked++;
            Long at = sentAt.pollFirst();
            if (at != null) {
                long rtt = System.nanoTime() - at;
                minRttNs = Math.min(minRttNs, rtt);
                rtts[rttCount++ % RTT_SAMPLES] = rtt;
                if (rttCount % 4 == 0 || rttCount < RTT_SAMPLES) {
                    long[] sorted = Arrays.copyOf(rtts, Math.min(rttCount, RTT_SAMPLES));
                    Arrays.sort(sorted);
                    rttMedianNs = sorted[sorted.length / 2];
                }
            }
        }
        if (!direct) video.poke();
    }

    private void state(String state, String message) {
        JSONObject j = new JSONObject();
        try {
            j.put("t", "state").put("state", state);
            if (message != null) j.put("message", message);
            wire.json(j);
        } catch (Exception ignored) {
        }
    }

    /** End it, from any thread, any number of times: every blocked read and write wakes. */
    void closeAll(String why) {
        if (closing) return;
        closing = true;
        if (why != null) AppServer.log("window " + serial + ": " + why);
        LocalSocket s = sock;
        shutdownQuietly(s);
        closeQuietly(s);
        synchronized (this) {
            notifyAll();
        }
    }

    /** Both ways: a thread reading it wakes, and the other end sees it end. */
    private static void shutdownQuietly(LocalSocket s) {
        try {
            s.shutdownInput();
        } catch (IOException ignored) {
        }
        try {
            s.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.toString() : m;
    }

    /** Scrcpy's 8 Mb/s at 1080p, scaled with the pixels, within reason. */
    private static int defaultBitrate(int w, int h) {
        double mp = w * (double) h / (1920 * 1080);
        return (int) Math.max(2_000_000, Math.min(24_000_000, 8_000_000 * Math.max(0.35, mp)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Sizes are even: every encoder takes those. */
    private static int even(int v) {
        return v & ~1;
    }
}
