package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The Linux desktop as hardware-encoded video, for a computer on the cable.
 *
 * <p>The desktop window on a computer used to get the desktop the way the
 * phone's own window does: noVNC, through websockify, with Xvnc compressing
 * every change into JPEG and zlib on the phone's CPU and the page undoing it
 * in JavaScript. That is a fine way to cross a network and a poor way to fill
 * a 60 Hz screen. With the phone on adb there is a better one: read Xvnc's
 * pixels raw over loopback ({@link LinuxStreamRfb}), encode them on the
 * phone's video encoder ({@link LinuxStreamEncoder}), and let the computer's
 * GPU decode them. The pointer is drawn by the computer itself, from the
 * shape Xvnc sends, so it never waits for a frame.
 *
 * <p><b>Who gets in.</b> An abstract socket, {@code <package>.linux-stream},
 * that adb can reach ({@code adb forward … localabstract:…}) and so can every
 * app on the phone — so each connection's peer is checked: the shell (2000,
 * which is adb), root, or this app's own uid (the remote-access daemon, which
 * could carry it over the tunnel). Anyone with adb already owns the phone;
 * nobody else gets a byte. The same rule as {@link RemoteProvider}'s.
 *
 * <p><b>One session at a time.</b> A new session replaces the old: it is a
 * window that came back after a cable was pulled and put back, and the old
 * session is a dead socket that has not noticed yet. A probe replaces
 * nothing.
 *
 * <p><b>Which phone is this?</b> {@code GET /probe} answers
 * {@code {"v":1,"id":..,"name":..,"state":..}} and closes — the remote-access
 * id the computer paired with, so a computer with two phones on adb finds the
 * one a window belongs to in a few milliseconds, without the second-long
 * {@code content call} that {@link RemoteProvider} costs.
 *
 * <p>The wire, after a WebSocket upgrade on {@code /stream} ({@link LinuxStreamSocket}):
 * <pre>
 * computer → phone   text   {"t":"hello","v":1,"start":bool,"w":..,"h":..,"bitrate":..,"fps":..,
 *                            "audio"?:bool,"codecs"?:["opus","pcm"],"mode"?:"low"|"medium"|"high"}
 *                    text   {"t":"resize","w":..,"h":..} | {"t":"clip","text":..}
 *                           | {"t":"keyframe"} | {"t":"ping","n":..} | {"t":"bitrate","bitrate":..}
 *                           | {"t":"audio","on":bool,"codecs"?:[..]}  — a browser's sound, on or off
 *                    binary 0x10 buttons u8, x u16, y u16       — the pointer, in desktop pixels
 *                    binary 0x11 down u8, keysym u32            — a key
 *                    binary 0x12                                — one video frame arrived
 * phone → computer   text   {"t":"state","state":..,"message"?}  — running, starting, stopped,
 *                           failed, missing, installing, unfinished
 *                    text   {"t":"video",..} before the first frame of every encoder
 *                    text   {"t":"clip","text":..} | {"t":"stats",..} | {"t":"pong","n":..}
 *                    binary 0x01 flags u8, pts µs u64, H.264 Annex B  — 1 key frame, 2 codec config
 *                    binary 0x02 hotX u16, hotY u16, w u16, h u16, RGBA — the pointer's shape
 *                    text   {"t":"sound","codec":"opus"|"pcm","rate":..,"channels":..,"kbps":..,"mode":..}
 *                           — the sound that follows, before its first run and at every change
 *                    binary 0x03 codec u8, read at µs u64, data — a browser's sound: codec 0 is
 *                           PCM s16le at the said rate and channels, 1 is one Opus packet
 * </pre>
 * All numbers big-endian but the sound's samples, which are as the guest makes them.
 *
 * <p><b>Sound, for a browser.</b> A computer on the cable hears the phone's own
 * playback (the desktop app captures it), so only a browser asks for sound
 * ({@code "audio":true}). While a browser watches, the phone's speaker gives
 * the desktop's sound up ({@link LinuxAudio#hold}), whether that browser
 * wants it or not: the person is at the computer. Sound off is nothing read
 * and nothing sent ({@link LinuxStreamAudio}).
 *
 * <p><b>Over the tunnel too.</b> The remote-access daemon puts a computer's
 * stream through to this socket when the phone is not on adb ({@code desktop/1}'s
 * {@code stream} op), so the link may be a relay on the other side of the
 * world. The bitrate the computer asks for is a ceiling for its route; the
 * session takes it down when the link cannot carry it and back up when it can
 * ({@link Session#adapt}).
 *
 * <p>Design record: doc/usb-desktop.md.
 */
final class LinuxStream {

    /** What the stream needs from the service it lives in. */
    interface Host {
        /** As a computer would find the desktop: running, starting, stopped, failed, missing, installing, unfinished. */
        String desktopState();

        /** A computer asked for it and it is not running: start it at w×h. Never a restart. */
        void startDesktop(int w, int h);

        /** Xvnc's password, or "-" while there is none. */
        String vncPassword();

        /**
         * Said with "stopped" when there is more to say than that: Android
         * killed the desktop (ChildProcessLimit). Null for a logout or a stop.
         * Waits up to {@code waitMs} for the runtime's verdict.
         */
        String stoppedWhy(long waitMs);

        /**
         * A session began or ended: the service must not idle out from under
         * one, and the CPU must not sleep under it — a phone in a bag with its
         * screen off is still drawing the desktop for someone.
         */
        void streaming(boolean on);

        /** The id this phone has for remote access, or "" before it has one. */
        String phoneId();

        String phoneName();
    }

    /** Xvnc's own port (linux-rt.sh), not websockify's 6080. A test points it elsewhere. */
    static volatile int rfbPort = 5901;

    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;

    static final byte MSG_VIDEO = 0x01;
    static final byte MSG_CURSOR = 0x02;
    static final byte MSG_AUDIO = 0x03;
    static final byte IN_POINTER = 0x10;

    static {
        // A browser's Low and Medium sound is Opus where the phone can make it.
        LinuxStreamAudio.opus = LinuxStreamOpus.MAKER;
    }
    static final byte IN_KEY = 0x11;
    static final byte IN_ACK = 0x12;

    /** The size a start or resize is held to: what a phone can draw, and what the window allows. */
    private static final int MIN_W = 640, MIN_H = 400, MAX_W = 3840, MAX_H = 2400;
    /** How long a computer waits for a desktop it asked to start, which the daemon also allows. */
    private static final long START_WAIT_MS = 130_000;
    /** How long Xvnc may take to answer once the runtime says it is up. */
    private static final long XVNC_WAIT_MS = 30_000;
    /** The encoder may be this many frames behind before Xvnc is asked for the next update. */
    private static final int MAX_IN_FLIGHT = 2;
    /** ...but never held longer than this, or a lost count would stall the picture. */
    private static final long PACE_MAX_MS = 100;
    /**
     * Frames sent that the computer has not yet said arrived: as many as fill
     * the link's round trip, and no more. A write returns once adb has the
     * bytes, not once the computer does, and on wireless debugging adb will
     * hold a second of video in its buffers — measured: round trips of 130 ms
     * with nothing pacing it, and of a second once a timeout let frames past a
     * fixed window. So the window is the round trip in frames, plus the one on
     * the wire: two on a cable (~2 ms). The round trip is the recent median,
     * not the best: a phone's Wi-Fi in power save answers in 8 ms one moment
     * and 150 the next (measured on wireless debugging, at any load), and a
     * window sized for the 8 starved the picture to 20 fps at the 150 without
     * making anything sooner. The ceiling, 200 ms of frames, is what is left to
     * stop: a link slower than the video, where only frames can give.
     */
    private static final int MIN_WINDOW = 2;
    /**
     * Browsers too. Tried at 24 for a browser on a Galaxy S25 Ultra whose
     * screen was off (Wi-Fi in power save, which no app may stop from the
     * background since Android 14), 2026-11-19, A/B on the same phone: more
     * frames (52-55 fps against 38-46) for twice the round trip (240-473 ms
     * against 124-265) and a stall at High. Latency first, so not kept.
     */
    private static final int MAX_WINDOW = 12;
    /** Round trips the window is the median of. */
    private static final int RTT_SAMPLES = 32;
    /** Only a computer that stopped acknowledging altogether is waited out, and logged. */
    private static final long ACK_GIVE_UP_MS = 3000;
    /**
     * Adaptive bitrate: a second in which the pump waited this share of its
     * time on acknowledgements is a link taking frames slower than they come;
     * the bitrate drops by {@link #BITRATE_DOWN}. Three seconds of motion that
     * kept within {@link #BEHIND_FINE} of it climb by {@link #BITRATE_UP}, up
     * to what the computer asked for. Never below {@link #MIN_BITRATE}.
     */
    private static final double BEHIND_SLOW = 0.2;
    private static final double BEHIND_FINE = 0.02;
    private static final double BITRATE_DOWN = 0.7;
    private static final double BITRATE_UP = 1.25;
    private static final int MIN_BITRATE = 500_000;

    static String socketName(Context ctx) {
        return ctx.getPackageName() + ".linux-stream";
    }

    private static LinuxStream instance;

    /** Listen, once per process. Safe to call again. */
    static void start(Context ctx, Host host) {
        start(socketName(ctx), host);
    }

    /** Listen on {@code @name}: the app's own name, or a test's. */
    static synchronized void start(String name, Host host) {
        if (instance != null) return;
        try {
            instance = new LinuxStream(name, host);
            DexLog.step("stream", "listening on @" + name);
        } catch (IOException e) {
            DexLog.warn("stream", "cannot listen on @" + name + ": " + e);
        }
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.shutdown();
        instance = null;
    }

    /** A computer is watching. */
    static synchronized boolean active() {
        return instance != null && instance.current() != null;
    }

    /**
     * A browser's session ({@link LinuxBrowserServer}): its WebSocket already
     * upgraded, and its hello read there, since the token that let it in rides
     * in the hello. From here it is a session like any computer's, with two
     * differences: it may ask for a picture smaller than the desktop, and it is
     * told when a newer one takes the desktop from it, so that two windows do
     * not take it back and forth. {@code gate} is asked as it takes the
     * desktop ({@link #claim}); {@code ended} runs once it is over.
     *
     * @return false when the stream is not up, which it is whenever the
     *         service is
     */
    static boolean serve(LinuxStreamSocket ws, JSONObject hello, java.io.Closeable sock, String who,
                         LinuxBrowserServer.Gate gate, Runnable ended) {
        LinuxStream s;
        synchronized (LinuxStream.class) {
            s = instance;
        }
        if (s == null || s.stopped) return false;
        s.new Session(ws, hello, sock, who, gate, ended).begin();
        return true;
    }

    /** A browser is watching the desktop now. */
    static boolean browserWatching() {
        LinuxStream s;
        synchronized (LinuxStream.class) {
            s = instance;
        }
        if (s == null) return false;
        Session cur = s.current();
        return cur != null && cur.browser;
    }

    /**
     * A browser watching now loses its picture, and is told why first so that
     * its page says so rather than coming back: {@code "off"} (the switch went
     * off) or {@code "unauthorized"} (every browser was signed out). A
     * computer's session stays. A browser let in but not yet watching is not
     * here to end: its gate, asked as it takes the desktop, already says no.
     */
    static void endBrowser(String why) {
        LinuxStream s;
        synchronized (LinuxStream.class) {
            s = instance;
        }
        if (s == null) return;
        Session cur;
        synchronized (s) {
            cur = s.current;
        }
        if (cur != null && cur.browser) s.tellThenClose(cur, why, "browsers were " + why + " on the phone");
    }

    /** The process's main thread, where Android allows no network (StrictMode's NetworkOnMainThreadException). */
    static boolean onMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && main.isCurrentThread();
    }

    /**
     * {@code work} on a thread of its own, which nothing can throw out of: on
     * Android an exception left to any thread ends the process, and with it
     * Linux, which runs in this one.
     */
    static void offMain(String name, Runnable work) {
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                DexLog.warn("stream", name + ": " + e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Say {@code state} to a session, then end it. On a thread of its own and
     * given a moment: a frame stuck on a dead link holds the socket's lock,
     * and the close is what frees it.
     */
    private void tellThenClose(Session was, String state, String why) {
        if (onMainThread()) {
            // The service calls here from its main thread (the switch going
            // off, a sign-out), which must neither wait on a link nor write to
            // one: the whole notice goes to a thread of its own.
            offMain("linux-stream-notice", () -> tellThenClose(was, state, why));
            return;
        }
        Thread note = new Thread(() -> {
            try {
                was.state(state, null);
            } catch (IOException | RuntimeException ignored) {
                // gone, or going: the close below is what matters
            }
        }, "linux-stream-notice");
        note.setDaemon(true);
        note.start();
        try {
            note.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        was.closeAll(why);
    }

    private final Host host;
    private final String name;
    private final LocalServerSocket server;
    private final Thread acceptor;
    private volatile boolean stopped;
    private Session current;

    private LinuxStream(String name, Host host) throws IOException {
        this.host = host;
        this.name = name;
        this.server = new LocalServerSocket(name);
        this.acceptor = new Thread(this::acceptLoop, "linux-stream");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private synchronized Session current() {
        return current != null && !current.closing ? current : null;
    }

    private void acceptLoop() {
        while (!stopped) {
            LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!stopped) DexLog.warn("stream", "accept: " + e);
                return;
            }
            if (stopped) {
                closeQuietly(s);
                return;
            }
            int uid;
            try {
                Credentials c = s.getPeerCredentials();
                uid = c.getUid();
            } catch (IOException e) {
                closeQuietly(s);
                continue;
            }
            if (uid != SHELL_UID && uid != ROOT_UID && uid != Process.myUid()) {
                DexLog.warn("stream", "refused a connection from uid " + uid);
                closeQuietly(s);
                continue;
            }
            new Session(s, uid).begin();
        }
    }

    /**
     * {@code s} is the session now, and the one before it, if any, ends;
     * unless {@code s} may no longer have the desktop. A browser's gate is
     * asked here, under the lock {@link #endBrowser} reads the session by: a
     * sign-out or the switch going off either came before this and is seen,
     * or comes after and finds {@code s} to end. So is the service stopping.
     *
     * @return null when {@code s} is the session; else what its page is told
     *         ("" for nothing)
     */
    private String claim(Session s) {
        Session old;
        synchronized (this) {
            if (stopped) return "";
            String no = s.gate != null ? s.gate.refusal() : null;
            if (no != null) return no;
            old = current;
            current = s;
        }
        if (old == null || old == s) return null;
        if (old.browser) {
            // A browser comes back by itself when its stream drops, and so does
            // the desktop app's window: told nothing, the two would take the
            // desktop from each other for ever. Told, the browser's page says
            // the desktop opened somewhere else and waits to be asked.
            tellThenClose(old, "replaced", "replaced by a new session");
            return null;
        }
        old.closeAll("replaced by a new session");
        return null;
    }

    private void shutdown() {
        stopped = true;
        // Closing a socket does not wake a thread blocked in accept() on it —
        // Linux leaves that thread waiting on a descriptor that is gone — but a
        // connection does, and only while the socket still listens. So the
        // acceptor is woken first, sees `stopped`, and returns; then the close.
        try (LocalSocket poke = new LocalSocket()) {
            poke.connect(new LocalSocketAddress(name));
        } catch (IOException ignored) {
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Session s;
        synchronized (this) {
            s = current;
            current = null;
        }
        if (s != null) s.closeAll("the service stopped");
    }

    private synchronized void ended(Session s) {
        if (current == s) current = null;
    }

    private static void closeQuietly(java.io.Closeable s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Desktop sizes are even: every encoder takes those. */
    private static int even(int v) {
        return v & ~1;
    }

    /** A browser's picture scale, held to what the encoder steps through: half the desktop to all of it. */
    static double scaleOf(double asked) {
        if (Double.isNaN(asked)) return 1.0;
        return Math.max(0.5, Math.min(1.0, asked));
    }

    // ── one computer ──

    private final class Session implements LinuxStreamRfb.Handler, LinuxStreamEncoder.Output {
        /** A computer's, over adb or the tunnel; null for a browser's. */
        private final LocalSocket local;
        /** What closes the connection, whichever kind it is. */
        private final java.io.Closeable sock;
        /** "uid 2000", or the browser and its address. */
        private final String who;
        /** A browser's session (LinuxBrowserServer): upgraded and let in before it got here. */
        final boolean browser;
        /** A browser's hello, read where its token was checked. */
        private final JSONObject givenHello;
        /** A browser's: whether it may still have the desktop, asked as it takes it. Null for a computer's. */
        final LinuxBrowserServer.Gate gate;
        private final Runnable onEnded;
        private final Thread thread;
        private Thread pump;
        private volatile LinuxStreamSocket ws;
        private volatile LinuxStreamRfb rfb;
        /** Owned by the pump thread; read elsewhere only to ask for a key frame. */
        private volatile LinuxStreamEncoder enc;
        volatile boolean closing;

        // the hello
        private boolean start;
        private int wantW = 1280, wantH = 800;
        /** What the encoder is asked for now: {@link #ceiling} or less, as the link allows. */
        private volatile int bitrate = 24_000_000;
        /** What the computer asked for, for its route: the most {@link #adapt} gives back. */
        private volatile int ceiling = 24_000_000;
        private volatile int fps = 60;
        /**
         * The most of the desktop's size the picture may be: 1 for every
         * computer, less for a browser's Low quality. A smaller picture is less
         * to encode, send and decode; the browser scales it back up.
         */
        private volatile double scale = 1.0;
        /** A browser chose another quality: the pump makes the encoder again at its next update. */
        private volatile boolean rebuild;

        // a browser's sound
        /** The browser wants Linux's sound now. */
        private volatile boolean soundWanted;
        /** The browser's quality, Low, Medium or High: the sound follows it. */
        private volatile String mode = "medium";
        /** The page can decode Opus (WebCodecs' AudioDecoder: an https page). */
        private volatile boolean pageOpus;
        /** The desktop is up, so its sound tap is there to read. */
        private volatile boolean soundReady;
        /** Reading the tap for this browser; null while sound is off. Under {@link #soundLock}. */
        private LinuxStreamAudio sound;
        private final Object soundLock = new Object();
        /** The phone's speaker was given up for this browser and must be given back. */
        private boolean speakerHeld;
        /** Sound bytes sent, all told; and as the last stats saw it (drain thread). */
        private final java.util.concurrent.atomic.AtomicLong soundSent = new java.util.concurrent.atomic.AtomicLong();
        private long soundSeen;

        // pump-thread state
        private boolean needFull;
        private boolean skipFrame;
        /**
         * Where an update's time goes, summed over the stats second and read
         * by the drain thread when it reports: waiting for Xvnc, reading its
         * pixels, uploading them, drawing into the encoder.
         */
        private long requestedAt;
        private long firstRectAt;
        private long uploadNs;
        private volatile long sumWaitNs;
        private volatile long sumReadNs;
        private volatile long sumUploadNs;
        private volatile long sumFrameNs;
        private volatile int sumUpdates;
        private volatile long sumPixels;

        // pacing, shared by the pump, the drain and the input reader
        private final Object pace = new Object();
        private int inFlight;
        private long sent;
        private long acked;
        /** When each frame not yet acknowledged was sent (System.nanoTime), oldest first. */
        private final java.util.ArrayDeque<Long> sentAt = new java.util.ArrayDeque<>();
        private final long[] rtts = new long[RTT_SAMPLES];
        private int rttCount;
        private long rttMedianNs = -1;
        private long minRttNs = Long.MAX_VALUE;
        /** The computer acknowledges frames (every one of ours does; an older one may not). */
        private boolean acks;
        private boolean ackStallLogged;
        /** Time the pump spent waiting on acknowledgements this stats second. Under {@link #pace}. */
        private long linkWaitNs;
        /** Seconds of motion in a row that the link kept up with; drain thread. */
        private int keptUp;

        // stats, drain thread
        private long statsSince = SystemClock.elapsedRealtime();
        private int statsFrames;
        private long statsBytes;
        private long statsEncodeUs;
        private long statsSendUs;

        Session(LocalSocket sock, int peerUid) {
            this.local = sock;
            this.sock = sock;
            this.who = "uid " + peerUid;
            this.browser = false;
            this.givenHello = null;
            this.gate = null;
            this.onEnded = null;
            this.thread = new Thread(this::run, "linux-stream-session");
            thread.setDaemon(true);
        }

        Session(LinuxStreamSocket ws, JSONObject hello, java.io.Closeable sock, String who,
                LinuxBrowserServer.Gate gate, Runnable ended) {
            this.local = null;
            this.sock = sock;
            this.ws = ws;
            this.who = who;
            this.browser = true;
            this.givenHello = hello;
            this.gate = gate;
            this.onEnded = ended;
            this.thread = new Thread(this::run, "linux-stream-browser");
            thread.setDaemon(true);
        }

        void begin() {
            thread.start();
        }

        private void run() {
            boolean counted = false;
            try {
                JSONObject hello;
                if (local != null) {
                    local.setSoTimeout(15_000);
                    LinuxStreamSocket conn = new LinuxStreamSocket(local.getInputStream(), local.getOutputStream(), local);
                    LinuxStreamSocket.Request req = conn.readRequest();
                    if ("/probe".equals(req.path)) {
                        JSONObject p = new JSONObject();
                        p.put("v", 1).put("id", host.phoneId()).put("name", host.phoneName())
                                .put("state", host.desktopState());
                        conn.respond(200, "OK", "application/json", p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        return;
                    }
                    if (!"/stream".equals(req.path)) {
                        conn.respond(404, "Not Found", "text/plain", new byte[0]);
                        return;
                    }
                    conn.upgrade(req);
                    ws = conn;
                    LinuxStreamSocket.Message m = ws.read();
                    if (m == null || m.opcode != LinuxStreamSocket.OP_TEXT) return;
                    hello = new JSONObject(m.text());
                } else {
                    hello = givenHello;
                }
                if (!"hello".equals(hello.optString("t"))) return;
                start = hello.optBoolean("start");
                wantW = even(clamp(hello.optInt("w", 1280), MIN_W, MAX_W));
                wantH = even(clamp(hello.optInt("h", 800), MIN_H, MAX_H));
                bitrate = clamp(hello.optInt("bitrate", 24_000_000), MIN_BITRATE, 150_000_000);
                ceiling = bitrate;
                fps = clamp(hello.optInt("fps", 60), 10, 60);
                acks = hello.optBoolean("acks");
                // Only a browser asks for a smaller picture; a computer's window
                // is shown one pixel for one.
                if (browser) scale = scaleOf(hello.optDouble("scale", 1.0));
                if (local != null) local.setSoTimeout(0);
                String refused = claim(this);
                if (refused != null) {
                    // Signed out, or switched off, while it was being let in.
                    DexLog.step("stream", "turned away " + who + (refused.isEmpty() ? "" : ": " + refused));
                    if (!refused.isEmpty()) {
                        state(refused, null);
                        // Nothing reads it yet, and its page pings: the word
                        // must not go down with a reset.
                        LinuxStreamSocket w = ws;
                        if (w != null) w.closeAfterWord(LinuxBrowserServer.LAST_WORD_MS);
                    }
                    return;
                }
                DexLog.step("stream", (browser ? "a browser connected (" : "a computer connected (") + who + ", "
                        + wantW + "x" + wantH + ", " + bitrate / 1_000_000 + " Mb/s"
                        + (scale < 1 ? ", " + Math.round(scale * 100) + "% size" : "")
                        + (hello.has("mode") ? ", " + hello.optString("mode") : "")
                        + (start ? ", start" : "") + ")");
                host.streaming(true);
                counted = true;
                if (browser) {
                    // The person is at the computer: the phone's speaker goes
                    // quiet for as long as this browser watches.
                    LinuxAudio.hold(LinuxAudio.DESKTOP, true);
                    speakerHeld = true;
                    soundWanted = hello.optBoolean("audio");
                    mode = hello.optString("mode", "medium");
                    pageOpus = opusIn(hello);
                }
                if (!awaitDesktop()) return;
                soundReady = true;
                if (soundWanted) sound(true);
                rfb = connectXvnc();
                if (rfb == null) return;
                pump = new Thread(this::pumpLoop, "linux-stream-gl");
                pump.setDaemon(true);
                pump.start();
                inputLoop();
            } catch (Exception e) {
                if (!closing) DexLog.warn("stream", "session: " + e);
            } finally {
                closeAll(null);
                sound(false);
                if (speakerHeld) {
                    speakerHeld = false;
                    LinuxAudio.hold(LinuxAudio.DESKTOP, false);
                }
                Thread p = pump;
                if (p != null) {
                    try {
                        p.join(3000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                ended(this);
                if (counted) {
                    host.streaming(false);
                    DexLog.step("stream", browser ? "the browser's session ended" : "the computer's session ended");
                }
                if (onEnded != null) {
                    try {
                        onEnded.run();
                    } catch (RuntimeException e) {
                        DexLog.warn("stream", "after the browser's session: " + e);
                    }
                }
            }
        }

        /**
         * Until the desktop is up, saying how it is going — or say why it will
         * not be and give up. A desktop that is not running is started only
         * when the window asked ({@code start}): a window coming back after a
         * drop does not boot a desktop someone logged out of behind them.
         */
        private boolean awaitDesktop() throws IOException, InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + START_WAIT_MS;
            long askedAt = 0;
            String said = "";
            long saidAt = 0;
            while (!closing) {
                String state = host.desktopState();
                long now = SystemClock.elapsedRealtime();
                if ("running".equals(state)) {
                    state(state, null);
                    return true;
                }
                if (("stopped".equals(state) || "failed".equals(state)) && askedAt == 0 && start) {
                    host.startDesktop(wantW, wantH);
                    askedAt = now;
                    state = "starting";
                } else if ("stopped".equals(state) && askedAt > 0 && now - askedAt < 10_000) {
                    // The start is on its way through the service; it has not
                    // registered yet.
                    state = "starting";
                }
                if (!"starting".equals(state)) {
                    // Long settled by now, so no wait for the why.
                    state(state, "stopped".equals(state) ? host.stoppedWhy(0) : null);
                    return false;
                }
                // Said again every couple of seconds: it is also how a window
                // that went away during a long start is noticed.
                if (!state.equals(said) || now - saidAt > 2000) {
                    state(state, null);
                    said = state;
                    saidAt = now;
                }
                if (now > deadline) {
                    state("failed", "The desktop did not start in time.");
                    return false;
                }
                Thread.sleep(400);
            }
            return false;
        }

        private LinuxStreamRfb connectXvnc() throws IOException, InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + XVNC_WAIT_MS;
            IOException last = null;
            while (!closing && SystemClock.elapsedRealtime() < deadline) {
                String pass = host.vncPassword();
                try {
                    LinuxStreamRfb r = LinuxStreamRfb.connect(rfbPort, pass, 2000);
                    DexLog.step("stream", "reading Xvnc at " + r.width + "x" + r.height + " (" + r.swizzle() + ")");
                    return r;
                } catch (LinuxStreamRfb.AuthFailed e) {
                    state("failed", "The desktop did not accept its password. Open Linux on the phone once, then try again.");
                    return null;
                } catch (IOException e) {
                    last = e;
                    Thread.sleep(300);
                }
            }
            if (!closing) {
                DexLog.warn("stream", "Xvnc did not answer: " + last);
                state("failed", "The desktop is up but its display did not answer.");
            }
            return null;
        }

        // ── the pump: Xvnc → texture → encoder ──

        private void pumpLoop() {
            LinuxStreamRfb r = rfb;
            try {
                newEncoder(r.width, r.height);
                while (!closing) r.readMessage(this);
            } catch (EOFException e) {
                // Xvnc went away: the desktop was logged out of, or stopped,
                // or killed by Android, which the window is told in words.
                if (!closing) {
                    try {
                        state("stopped", host.stoppedWhy(3000));
                    } catch (IOException ignored) {
                    }
                }
            } catch (Exception e) {
                if (!closing) {
                    DexLog.warn("stream", "pump: " + e);
                    try {
                        state("failed", "The picture stopped: " + e.getMessage());
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                LinuxStreamEncoder e = enc;
                enc = null;
                if (e != null) e.release();
                closeAll(null);
            }
        }

        private void newEncoder(int w, int h) throws IOException {
            LinuxStreamEncoder old = enc;
            enc = null;
            if (old != null) old.release();
            synchronized (pace) {
                inFlight = 0;
                // A frame the old encoder had sent and the computer had not yet
                // acknowledged is still on its way; acks for it still come.
            }
            LinuxStreamEncoder e = LinuxStreamEncoder.create(w, h, bitrate, fps, scale, rfb.swizzle(), this);
            enc = e;
            DexLog.step("stream", "encoding " + w + "x" + h + " as " + e.encWidth + "x" + e.encHeight
                    + " on " + e.codecName + (e.hardware ? "" : " (software)") + " at " + e.bitrate / 1_000_000 + " Mb/s"
                    + (e.configAttempt == 0 ? ", low latency" : ", plain configuration"));
            JSONObject v = new JSONObject();
            try {
                v.put("t", "video").put("codec", "avc").put("encoder", e.codecName).put("hw", e.hardware)
                        .put("w", e.encWidth).put("h", e.encHeight)
                        .put("cw", e.scaled ? e.encWidth : w).put("ch", e.scaled ? e.encHeight : h)
                        .put("dw", w).put("dh", h)
                        .put("bitrate", e.bitrate).put("fps", e.fps).put("lowLatency", e.configAttempt == 0);
            } catch (Exception ignored) {
            }
            send(v);
        }

        @Override
        public void onResize(int w, int h) throws IOException {
            LinuxStreamEncoder e = enc;
            if (e != null && e.fbWidth == w && e.fbHeight == h) return;
            newEncoder(w, h);
            // The texture is new and empty: nothing goes out until a whole
            // picture has been put in it.
            needFull = true;
            skipFrame = true;
        }

        @Override
        public void onRect(int x, int y, int w, int h, ByteBuffer pixels) {
            long t = System.nanoTime();
            if (firstRectAt == 0) firstRectAt = t;
            LinuxStreamEncoder e = enc;
            if (e != null) e.upload(x, y, w, h, pixels);
            uploadNs += System.nanoTime() - t;
            sumPixels += (long) w * h;
        }

        @Override
        public void onUpdateEnd() throws IOException {
            long t0 = System.nanoTime();
            LinuxStreamEncoder e = enc;
            if (rebuild && e != null) {
                // Another quality: a new encoder at the new size, here on the
                // thread that owns the GL context. Its texture starts empty, so
                // this update goes nowhere and the next is the whole desktop,
                // the first frame of the new encoder and a key frame.
                rebuild = false;
                newEncoder(e.fbWidth, e.fbHeight);
                e = enc;
                needFull = true;
                skipFrame = true;
            }
            if (e != null && !skipFrame) {
                synchronized (pace) {
                    inFlight++;
                }
                e.frame();
            }
            skipFrame = false;
            long t1 = System.nanoTime();
            if (requestedAt != 0 && firstRectAt != 0) {
                sumWaitNs += firstRectAt - requestedAt;
                sumReadNs += Math.max(0, t0 - firstRectAt - uploadNs);
                sumUploadNs += uploadNs;
                sumFrameNs += t1 - t0;
                sumUpdates++;
            }
            firstRectAt = 0;
            uploadNs = 0;
            // Not ahead of the encoder, nor of the link: a request answered
            // while frames queue up behind either would only be a picture
            // that is late. Xvnc keeps gathering changes meanwhile, so the
            // next update is the newest picture, not the next one in line.
            synchronized (pace) {
                long now = SystemClock.elapsedRealtime();
                long encoderUntil = now + PACE_MAX_MS;
                long linkUntil = now + ACK_GIVE_UP_MS;
                while (!closing) {
                    now = SystemClock.elapsedRealtime();
                    long left;
                    boolean link = false;
                    if (inFlight > MAX_IN_FLIGHT - 1) {
                        left = encoderUntil - now;
                    } else if (acks && sent - acked >= window()) {
                        left = linkUntil - now;
                        link = true;
                        if (left <= 0 && !ackStallLogged) {
                            ackStallLogged = true;
                            DexLog.warn("stream", "no acknowledgement for " + ACK_GIVE_UP_MS + " ms; going on without");
                        }
                    } else {
                        break;
                    }
                    if (left <= 0) break;
                    long waitFrom = System.nanoTime();
                    try {
                        pace.wait(left);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (link) linkWaitNs += System.nanoTime() - waitFrom;
                }
            }
            LinuxStreamRfb r = rfb;
            requestedAt = System.nanoTime();
            if (r != null) r.requestUpdate(!needFull);
            needFull = false;
        }

        @Override
        public void onCursor(int hotX, int hotY, int w, int h, byte[] rgba) throws IOException {
            if (w > 256 || h > 256) {
                w = 0;
                h = 0;
                rgba = new byte[0];
            }
            ByteBuffer head = ByteBuffer.allocate(9);
            head.put(MSG_CURSOR).putShort((short) hotX).putShort((short) hotY).putShort((short) w).putShort((short) h);
            LinuxStreamSocket s = ws;
            if (s != null) s.sendBinary(head.array(), rgba, rgba.length);
        }

        @Override
        public void onClipboard(String text) throws IOException {
            JSONObject j = new JSONObject();
            try {
                j.put("t", "clip").put("text", text);
            } catch (Exception ignored) {
            }
            send(j);
        }

        // ── the drain: encoder → computer ──

        @Override
        public void packet(byte[] data, int len, long ptsUs, int flags) throws IOException {
            // pts is when the frame went into the encoder (eglPresentationTime
            // takes System.nanoTime), so this is the encoder's own time.
            long outUs = System.nanoTime() / 1000;
            ByteBuffer head = ByteBuffer.allocate(10);
            head.put(MSG_VIDEO).put((byte) flags).putLong(ptsUs);
            LinuxStreamSocket s = ws;
            if (s == null) throw new IOException("closed");
            s.sendBinary(head.array(), data, len);
            if ((flags & LinuxStreamEncoder.FLAG_CONFIG) != 0) return;
            long doneUs = System.nanoTime() / 1000;
            synchronized (pace) {
                if (inFlight > 0) inFlight--;
                sent++;
                if (acks) sentAt.addLast(System.nanoTime());
                pace.notifyAll();
            }
            long now = SystemClock.elapsedRealtime();
            statsFrames++;
            statsBytes += len;
            statsEncodeUs += Math.max(0, outUs - ptsUs);
            statsSendUs += doneUs - outUs;
            if (now - statsSince >= 1000) {
                adapt(now - statsSince, statsFrames);
                JSONObject j = new JSONObject();
                try {
                    double secs = (now - statsSince) / 1000.0;
                    int n = Math.max(1, statsFrames);
                    long behind;
                    long rtt;
                    int win;
                    long best;
                    synchronized (pace) {
                        behind = sent - acked;
                        rtt = rttMedianNs < 0 ? -1 : rttMedianNs / 1000;
                        best = minRttNs == Long.MAX_VALUE ? -1 : minRttNs / 1000;
                        win = window();
                    }
                    j.put("t", "stats").put("fps", Math.round(statsFrames / secs))
                            .put("kbps", Math.round(statsBytes * 8 / 1000.0 / secs))
                            .put("encodeMs", Math.round(statsEncodeUs / 1000.0 / n * 10) / 10.0)
                            .put("sendMs", Math.round(statsSendUs / 1000.0 / n * 10) / 10.0)
                            .put("unacked", behind)
                            .put("rttMs", rtt < 0 ? -1 : Math.round(rtt / 100.0) / 10.0)
                            .put("bestRttMs", best < 0 ? -1 : Math.round(best / 100.0) / 10.0)
                            .put("window", win)
                            .put("targetKbps", bitrate / 1000)
                            .put("swapMs", enc == null ? 0 : enc.swapMs());
                    if (browser) j.put("soundKbps", soundKbps(secs));
                    int u = sumUpdates;
                    if (u > 0) {
                        j.put("waitMs", Math.round(sumWaitNs / 1e5 / u) / 10.0)
                                .put("readMs", Math.round(sumReadNs / 1e5 / u) / 10.0)
                                .put("uploadMs", Math.round(sumUploadNs / 1e5 / u) / 10.0)
                                .put("frameMs", Math.round(sumFrameNs / 1e5 / u) / 10.0)
                                .put("mpixPerUpdate", Math.round(sumPixels / 1e4 / u) / 100.0);
                    }
                    sumWaitNs = 0;
                    sumReadNs = 0;
                    sumUploadNs = 0;
                    sumFrameNs = 0;
                    sumUpdates = 0;
                    sumPixels = 0;
                } catch (Exception ignored) {
                }
                send(j);
                statsSince = now;
                statsFrames = 0;
                statsBytes = 0;
                statsEncodeUs = 0;
                statsSendUs = 0;
            }
        }

        /**
         * The bitrate, held to what the link carries — the drain thread's, once
         * a second. A cable never comes near; Wi-Fi in power save, mobile data
         * and a relay can, and there a frame that waits is a frame that is late.
         */
        private void adapt(long periodMs, int frames) {
            long waited;
            synchronized (pace) {
                waited = linkWaitNs;
                linkWaitNs = 0;
            }
            double behind = waited / 1e6 / Math.max(1, periodMs);
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
            LinuxStreamEncoder e = enc;
            if (e != null) e.setBitrate(bps);
            DexLog.step("stream", why + ": " + Math.round(bps / 1e5) / 10.0 + " Mb/s");
        }

        @Override
        public void stopped(Throwable why) {
            if (closing) return;
            DexLog.warn("stream", "the encoder stopped: " + why);
            try {
                state("failed", "The phone's video encoder stopped.");
            } catch (IOException ignored) {
            }
            closeAll(null);
        }

        // ── the computer's input ──

        private void inputLoop() throws IOException {
            while (!closing) {
                LinuxStreamSocket.Message m = ws.read();
                if (m == null) return;
                LinuxStreamRfb r = rfb;
                if (r == null) return;
                if (m.opcode == LinuxStreamSocket.OP_BINARY) {
                    byte[] d = m.data;
                    if (d.length >= 6 && d[0] == IN_POINTER) {
                        r.pointer(u16(d, 2), u16(d, 4), d[1] & 0xff);
                    } else if (d.length >= 6 && d[0] == IN_KEY) {
                        int keysym = ((d[2] & 0xff) << 24) | ((d[3] & 0xff) << 16) | ((d[4] & 0xff) << 8) | (d[5] & 0xff);
                        r.key(keysym, d[1] != 0);
                    } else if (d.length >= 1 && d[0] == IN_ACK) {
                        synchronized (pace) {
                            acked++;
                            Long at = sentAt.pollFirst();
                            if (at != null) {
                                long rtt = System.nanoTime() - at;
                                minRttNs = Math.min(minRttNs, rtt);
                                rtts[rttCount++ % RTT_SAMPLES] = rtt;
                                if (rttCount % 4 == 0 || rttCount < RTT_SAMPLES) {
                                    long[] sorted = java.util.Arrays.copyOf(rtts, Math.min(rttCount, RTT_SAMPLES));
                                    java.util.Arrays.sort(sorted);
                                    rttMedianNs = sorted[sorted.length / 2];
                                }
                            }
                            pace.notifyAll();
                        }
                    }
                    continue;
                }
                JSONObject j;
                try {
                    j = new JSONObject(m.text());
                } catch (Exception e) {
                    continue;
                }
                switch (j.optString("t")) {
                    case "resize":
                        r.setDesktopSize(even(clamp(j.optInt("w"), MIN_W, MAX_W)),
                                even(clamp(j.optInt("h"), MIN_H, MAX_H)));
                        break;
                    case "clip":
                        r.clipboard(j.optString("text"));
                        break;
                    case "keyframe": {
                        LinuxStreamEncoder e = enc;
                        if (e != null) e.requestKeyFrame();
                        break;
                    }
                    case "bitrate": {
                        // The route changed — a relay that became a direct
                        // path, say: what it can carry is the new ceiling, and
                        // the link has its say from there.
                        int asked = clamp(j.optInt("bitrate", ceiling), MIN_BITRATE, 150_000_000);
                        ceiling = asked;
                        keptUp = 0;
                        setBitrate(asked, "the computer's route changed");
                        break;
                    }
                    case "quality": {
                        // A browser's Low, Medium or High, while it watches. The
                        // bitrate lands at the next frame; a new size or frame
                        // rate needs a new encoder, which the pump makes at its
                        // next update, and a whole picture is asked for now so
                        // that there is one on a desktop where nothing moves.
                        if (!browser) break;
                        int asked = clamp(j.optInt("bitrate", ceiling), MIN_BITRATE, 150_000_000);
                        double s = scaleOf(j.optDouble("scale", scale));
                        int f = clamp(j.optInt("fps", fps), 10, 60);
                        ceiling = asked;
                        keptUp = 0;
                        setBitrate(asked, "the browser chose " + j.optString("mode", "a quality"));
                        if (s != scale || f != fps) {
                            scale = s;
                            fps = f;
                            rebuild = true;
                            r.requestUpdate(false);
                        }
                        // The sound follows the picture's quality, at its next read.
                        String asMode = j.optString("mode", mode);
                        if (!asMode.equals(mode)) {
                            mode = asMode;
                            LinuxStreamAudio a;
                            synchronized (soundLock) {
                                a = sound;
                            }
                            if (a != null) a.setFormat(soundFormat());
                        }
                        break;
                    }
                    case "ping": {
                        JSONObject p = new JSONObject();
                        try {
                            p.put("t", "pong").put("n", j.opt("n"));
                        } catch (Exception ignored) {
                        }
                        send(p);
                        break;
                    }
                    case "audio":
                        // A browser's sound switch: off is nothing read and nothing sent.
                        if (!browser) break;
                        soundWanted = j.optBoolean("on");
                        if (j.has("codecs")) pageOpus = opusIn(j);
                        DexLog.step("stream", "the browser turned its sound " + (soundWanted ? "on" : "off"));
                        sound(soundWanted && soundReady);
                        break;
                    default:
                }
            }
        }

        /** The page said it can decode Opus ({@code "codecs":["opus","pcm"]}). */
        private boolean opusIn(JSONObject j) {
            org.json.JSONArray c = j.optJSONArray("codecs");
            if (c == null) return false;
            for (int i = 0; i < c.length(); i++) {
                if ("opus".equals(c.optString(i))) return true;
            }
            return false;
        }

        /** The sound for the picture's quality now, as both ends can make and play it. */
        private LinuxStreamAudio.Format soundFormat() {
            return LinuxStreamAudio.Format.forMode(mode, pageOpus && LinuxStreamAudio.opus != null);
        }

        /** Read the desktop's sound for this browser, or stop. Any thread, any number of times. */
        private void sound(boolean on) {
            synchronized (soundLock) {
                if (on && !closing) {
                    if (sound == null || !sound.running()) sound = new LinuxStreamAudio(soundSink, who, soundFormat()).start();
                } else if (sound != null) {
                    sound.stop();
                    sound = null;
                }
            }
        }

        /** Where the sound goes: its form said first, then each run. */
        private final LinuxStreamAudio.Sink soundSink = new LinuxStreamAudio.Sink() {
            @Override
            public void format(LinuxStreamAudio.Format f) throws IOException {
                LinuxStreamSocket s = ws;
                if (s == null || closing) throw new IOException("closed");
                s.sendText(f.json());
            }

            @Override
            public void audio(int codec, byte[] data, int off, int len, long atUs) throws IOException {
                sendSound(codec, off == 0 ? data : java.util.Arrays.copyOfRange(data, off, off + len), len, atUs);
            }
        };

        /** One run of sound: 0x03, its codec (0 PCM, 1 Opus), when it was read, its bytes. */
        private void sendSound(int codec, byte[] data, int len, long atUs) throws IOException {
            ByteBuffer head = ByteBuffer.allocate(10);
            head.put(MSG_AUDIO).put((byte) codec).putLong(atUs);
            LinuxStreamSocket s = ws;
            if (s == null || closing) throw new IOException("closed");
            s.sendBinary(head.array(), data, len);
            soundSent.addAndGet(len);
        }

        /** Sound sent since the last stats, in kb/s over {@code secs}. Drain thread. */
        private long soundKbps(double secs) {
            long total = soundSent.get();
            long d = total - soundSeen;
            soundSeen = total;
            return Math.round(d * 8 / 1000.0 / secs);
        }

        /** Frames the link holds: its round trip in frames, and the one on the wire. Under {@link #pace}. */
        private int window() {
            if (rttMedianNs < 0) return MIN_WINDOW + 1;
            long frameNs = 1_000_000_000L / fps;
            long frames = (rttMedianNs + frameNs - 1) / frameNs + 1;
            return (int) Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, frames));
        }

        private int u16(byte[] d, int at) {
            return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff);
        }

        private void state(String state, String message) throws IOException {
            JSONObject j = new JSONObject();
            try {
                j.put("t", "state").put("state", state);
                if (message != null) j.put("message", message);
            } catch (Exception ignored) {
            }
            send(j);
        }

        private void send(JSONObject j) throws IOException {
            LinuxStreamSocket s = ws;
            if (s == null) throw new IOException("closed");
            s.sendText(j.toString());
        }

        /** End it, from any thread, any number of times: every blocked read and write wakes. */
        void closeAll(String why) {
            if (closing) return;
            closing = true;
            if (why != null) DexLog.step("stream", "ending the session: " + why);
            if (onMainThread()) {
                // A browser's stream says goodbye over TLS, and Android allows
                // no network on the main thread: NetworkOnMainThreadException
                // took :linux down, Linux with it, when the switch went off on
                // the S25. The service's onDestroy closes sessions from there
                // too.
                offMain("linux-stream-close", this::release);
                return;
            }
            release();
        }

        private void release() {
            LinuxStreamSocket s = ws;
            if (s != null) s.close();
            LinuxStreamRfb r = rfb;
            if (r != null) r.close();
            closeQuietly(sock);
            synchronized (pace) {
                pace.notifyAll();
            }
        }
    }
}
