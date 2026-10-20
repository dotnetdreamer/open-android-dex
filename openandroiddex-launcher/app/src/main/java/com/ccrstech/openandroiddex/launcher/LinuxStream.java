package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
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
 * computer → phone   text   {"t":"hello","v":1,"start":bool,"w":..,"h":..,"bitrate":..,"fps":..}
 *                    text   {"t":"resize","w":..,"h":..} | {"t":"clip","text":..}
 *                           | {"t":"keyframe"} | {"t":"ping","n":..} | {"t":"bitrate","bitrate":..}
 *                    binary 0x10 buttons u8, x u16, y u16       — the pointer, in desktop pixels
 *                    binary 0x11 down u8, keysym u32            — a key
 *                    binary 0x12                                — one video frame arrived
 * phone → computer   text   {"t":"state","state":..,"message"?}  — running, starting, stopped,
 *                           failed, missing, installing, unfinished
 *                    text   {"t":"video",..} before the first frame of every encoder
 *                    text   {"t":"clip","text":..} | {"t":"stats",..} | {"t":"pong","n":..}
 *                    binary 0x01 flags u8, pts µs u64, H.264 Annex B  — 1 key frame, 2 codec config
 *                    binary 0x02 hotX u16, hotY u16, w u16, h u16, RGBA — the pointer's shape
 * </pre>
 * All numbers big-endian.
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
    static final byte IN_POINTER = 0x10;
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

    /** {@code s} is the session now; the one before it, if any, ends. */
    private void claim(Session s) {
        Session old;
        synchronized (this) {
            old = current;
            current = s;
        }
        if (old != null && old != s) old.closeAll("replaced by a new session");
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

    private static void closeQuietly(LocalSocket s) {
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

    // ── one computer ──

    private final class Session implements LinuxStreamRfb.Handler, LinuxStreamEncoder.Output {
        private final LocalSocket sock;
        private final int peerUid;
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
        private int fps = 60;

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
            this.sock = sock;
            this.peerUid = peerUid;
            this.thread = new Thread(this::run, "linux-stream-session");
            thread.setDaemon(true);
        }

        void begin() {
            thread.start();
        }

        private void run() {
            boolean counted = false;
            try {
                sock.setSoTimeout(15_000);
                LinuxStreamSocket conn = new LinuxStreamSocket(sock.getInputStream(), sock.getOutputStream(), sock);
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
                JSONObject hello = new JSONObject(m.text());
                if (!"hello".equals(hello.optString("t"))) return;
                start = hello.optBoolean("start");
                wantW = even(clamp(hello.optInt("w", 1280), MIN_W, MAX_W));
                wantH = even(clamp(hello.optInt("h", 800), MIN_H, MAX_H));
                bitrate = clamp(hello.optInt("bitrate", 24_000_000), MIN_BITRATE, 150_000_000);
                ceiling = bitrate;
                fps = clamp(hello.optInt("fps", 60), 10, 60);
                acks = hello.optBoolean("acks");
                sock.setSoTimeout(0);
                claim(this);
                DexLog.step("stream", "a computer connected (uid " + peerUid + ", " + wantW + "x" + wantH
                        + ", " + bitrate / 1_000_000 + " Mb/s" + (start ? ", start" : "") + ")");
                host.streaming(true);
                counted = true;
                if (!awaitDesktop()) return;
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
                    DexLog.step("stream", "the computer's session ended");
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
                    state(state, null);
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
                // Xvnc went away: the desktop was logged out of, or stopped.
                if (!closing) {
                    try {
                        state("stopped", null);
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
            LinuxStreamEncoder e = LinuxStreamEncoder.create(w, h, bitrate, fps, rfb.swizzle(), this);
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
                    case "ping": {
                        JSONObject p = new JSONObject();
                        try {
                            p.put("t", "pong").put("n", j.opt("n"));
                        } catch (Exception ignored) {
                        }
                        send(p);
                        break;
                    }
                    default:
                }
            }
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
