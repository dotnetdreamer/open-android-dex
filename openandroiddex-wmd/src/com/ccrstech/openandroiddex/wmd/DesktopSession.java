package com.ccrstech.openandroiddex.wmd;

import android.net.LocalSocket;
import android.os.SystemClock;

import com.ccrstech.openandroiddex.launcher.LinuxStreamEncoder;
import com.ccrstech.openandroiddex.launcher.LinuxStreamSocket;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * One computer watching the desktop: the WebSocket its window opened, the pace
 * its link takes frames at, its mouse and keyboard.
 *
 * <p>The wire, after a WebSocket upgrade on {@code /stream} (LinuxStreamSocket);
 * numbers big-endian:
 * <pre>
 * computer → phone   text   {"t":"hello","v":1,"w":..,"h":..,"bitrate":..,"fps":..,"acks":true}
 *                    text   {"t":"resize","w":..,"h":..} | {"t":"clip","text":..} | {"t":"keyframe"}
 *                           | {"t":"ping","n":..} | {"t":"bitrate","bitrate":..}
 *                    binary 0x12                                         — one video frame arrived
 *                    binary 0x20 buttons u8, x u16, y u16, meta u32      — the mouse, display pixels
 *                    binary 0x21 x u16, y u16, h f32, v f32, meta u32    — the wheel, in notches
 *                    binary 0x22 down u8, repeat u8, code u16, meta u32  — a key: Android's code and meta
 *                    binary 0x23 UTF-8                                    — characters typed
 * phone → computer   text   {"t":"video",..} before the first frame of every encoder
 *                    text   {"t":"clip","text":..} | {"t":"stats",..} | {"t":"pong","n":..,"at":..}
 *                           | {"t":"state","state":"failed","message":..}
 *                    binary 0x01 flags u8, pts µs u64, H.264 Annex B    — 1 key frame, 2 codec config
 * </pre>
 * The video, its acknowledgement and the pacing are the Linux desktop's
 * (LinuxStream), so the window decodes both desktops with the same code. The
 * pts is when the phone's compositor made the frame, on the clock "at" reads
 * — CLOCK_MONOTONIC, in microseconds — which is how the window measures the
 * whole way from that moment to its own screen.
 *
 * <p>The numbers below are LinuxStream's, and measured there (doc/usb-desktop.md,
 * "Pacing"); what differs is the source. Xvnc sends a picture when asked, so
 * the Linux stream paces itself by when it asks. A display composes whenever
 * something on it changes, and every frame is taken off it as it comes
 * (DesktopVideo); this decides which of them go on into the encoder
 * ({@link #mayDraw}) — the newest one, once the encoder and the link have room.
 */
final class DesktopSession implements LinuxStreamEncoder.Output, DesktopVideo.Gate {

    static final byte MSG_VIDEO = 0x01;
    static final byte IN_ACK = 0x12;
    static final byte IN_POINTER = 0x20;
    static final byte IN_SCROLL = 0x21;
    static final byte IN_KEY = 0x22;
    static final byte IN_TEXT = 0x23;

    /** The sizes a window may ask for: what an encoder takes, and what a window can be. */
    static final int MIN_W = 320, MIN_H = 240, MAX_W = 4096, MAX_H = 4096;
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
    /** Adaptive bitrate — LinuxStream.adapt, the same rule and the same numbers. */
    private static final double BEHIND_SLOW = 0.2;
    private static final double BEHIND_FINE = 0.02;
    private static final double BITRATE_DOWN = 0.7;
    private static final double BITRATE_UP = 1.25;
    private static final int MIN_BITRATE = 500_000;
    private static final int MAX_BITRATE = 150_000_000;

    private final Desktop desktop;
    private final LocalSocket sock;
    private final int peerUid;
    private final Thread thread;
    private volatile LinuxStreamSocket ws;
    volatile boolean closing;

    // the hello
    private volatile int bitrate = 16_000_000;
    /** What the computer asked for, for its route: the most {@link #adapt} gives back. */
    private volatile int ceiling = 16_000_000;
    private int fps = 60;
    private boolean acks;

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
    /** Since when a frame has been waiting on the link; 0 while none is. */
    private long linkBlockedSince;
    /** Milliseconds of the stats second a frame spent waiting on the link. */
    private long linkWaitMs;
    /** Seconds of motion in a row that the link kept up with; drain thread. */
    private int keptUp;

    // stats, drain thread
    private long statsSince = SystemClock.elapsedRealtime();
    private int statsFrames;
    private long statsBytes;
    private long statsCaptureUs;
    private long statsSendUs;

    DesktopSession(Desktop desktop, LocalSocket sock, int peerUid) {
        this.desktop = desktop;
        this.sock = sock;
        this.peerUid = peerUid;
        this.thread = new Thread(this::run, "desktop-session");
        thread.setDaemon(true);
    }

    void begin() {
        thread.start();
    }

    private void run() {
        boolean claimed = false;
        try {
            sock.setSoTimeout(15_000);
            LinuxStreamSocket conn = new LinuxStreamSocket(sock.getInputStream(), sock.getOutputStream(), sock);
            LinuxStreamSocket.Request req = conn.readRequest();
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
            int w = even(clamp(hello.optInt("w", desktop.width()), MIN_W, MAX_W));
            int h = even(clamp(hello.optInt("h", desktop.height()), MIN_H, MAX_H));
            bitrate = clamp(hello.optInt("bitrate", bitrate), MIN_BITRATE, MAX_BITRATE);
            ceiling = bitrate;
            fps = clamp(hello.optInt("fps", 60), 10, 120);
            acks = hello.optBoolean("acks");
            sock.setSoTimeout(0);
            desktop.claim(this);
            claimed = true;
            Desktop.log("a computer connected (uid " + peerUid + ", " + w + "x" + h + ", "
                    + bitrate / 1_000_000 + " Mb/s at " + fps + " fps)");
            desktop.resize(w, h);
            newEncoder();
            inputLoop();
        } catch (Exception e) {
            if (!closing) Desktop.log("session: " + e);
        } finally {
            closeAll(null);
            if (claimed && desktop.ended(this)) Desktop.log("the computer's session ended");
        }
    }

    private void newEncoder() throws IOException {
        synchronized (pace) {
            // A frame the old encoder still had is not coming out of the new one.
            inFlight = 0;
        }
        DesktopVideo.Encoder e = desktop.video.start(bitrate, fps, this, this);
        Desktop.log("encoding " + e.srcWidth + "x" + e.srcHeight + " as " + e.width + "x" + e.height
                + " on " + e.name + (e.hardware ? "" : " (software)") + " at " + e.bitrate / 1_000_000 + " Mb/s"
                + (e.lowLatency ? ", low latency" : ", plain configuration"));
        JSONObject v = new JSONObject();
        try {
            v.put("t", "video").put("codec", "avc").put("encoder", e.name).put("hw", e.hardware)
                    .put("w", e.width).put("h", e.height)
                    .put("cw", e.scaled ? e.width : e.srcWidth).put("ch", e.scaled ? e.height : e.srcHeight)
                    .put("dw", e.srcWidth).put("dh", e.srcHeight)
                    .put("bitrate", e.bitrate).put("fps", e.fps).put("lowLatency", e.lowLatency)
                    .put("display", desktop.displayId).put("dpi", desktop.dpi());
        } catch (Exception ignored) {
        }
        send(v);
    }

    // ── the pace: which frames go into the encoder (GL thread) ──

    @Override
    public long mayDraw() {
        long now = SystemClock.uptimeMillis();
        synchronized (pace) {
            if (closing) return PACE_MAX_MS;
            // Not faster than the window asked for: a display that composes at
            // 90 or 120 Hz would otherwise cost the link half as much again.
            long gap = 1000L / fps - 2;
            if (now - lastDrawAt < gap) return lastDrawAt + gap - now;
            if (inFlight >= MAX_IN_FLIGHT) {
                long left = lastDrawAt + PACE_MAX_MS - now;
                if (left > 0) return left;
            }
            if (acks && sent - acked >= window()) {
                if (linkBlockedSince == 0) linkBlockedSince = now;
                long left = linkBlockedSince + ACK_GIVE_UP_MS - now;
                if (left > 0) return left;
                if (!ackStallLogged) {
                    ackStallLogged = true;
                    Desktop.log("no acknowledgement for " + ACK_GIVE_UP_MS + " ms; going on without");
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

    @Override
    public void packet(byte[] data, int len, long ptsUs, int flags) throws IOException {
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
        }
        statsFrames++;
        statsBytes += len;
        // From the compositor's frame to the encoder's output: what the phone adds.
        statsCaptureUs += Math.max(0, outUs - ptsUs);
        statsSendUs += doneUs - outUs;
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
            long behind;
            long rtt;
            long best;
            int win;
            synchronized (pace) {
                behind = sent - acked;
                rtt = rttMedianNs < 0 ? -1 : rttMedianNs / 1000;
                best = minRttNs == Long.MAX_VALUE ? -1 : minRttNs / 1000;
                win = window();
            }
            j.put("t", "stats").put("fps", Math.round(statsFrames / secs))
                    .put("kbps", Math.round(statsBytes * 8 / 1000.0 / secs))
                    .put("encodeMs", Math.round(statsCaptureUs / 1000.0 / n * 10) / 10.0)
                    .put("sendMs", Math.round(statsSendUs / 1000.0 / n * 10) / 10.0)
                    .put("unacked", behind)
                    .put("rttMs", rtt < 0 ? -1 : Math.round(rtt / 100.0) / 10.0)
                    .put("bestRttMs", best < 0 ? -1 : Math.round(best / 100.0) / 10.0)
                    .put("window", win)
                    .put("targetKbps", bitrate / 1000)
                    .put("swapMs", desktop.video.swapMs());
        } catch (Exception ignored) {
        }
        statsSince = now;
        statsFrames = 0;
        statsBytes = 0;
        statsCaptureUs = 0;
        statsSendUs = 0;
        try {
            send(j);
        } catch (IOException ignored) {
            // the next frame's send says so
        }
    }

    /**
     * LinuxStream.adapt: a second in which a frame waited on the link for a
     * fifth of it drops the bitrate by 30 %; three seconds of motion it kept up
     * with climb back by 25 %, up to what the computer asked for.
     */
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
        desktop.video.setBitrate(bps);
        Desktop.log(why + ": " + Math.round(bps / 1e5) / 10.0 + " Mb/s");
    }

    @Override
    public void stopped(Throwable why) {
        if (closing) return;
        Desktop.log("the encoder stopped: " + why);
        try {
            state("failed", "The phone's video encoder stopped.");
        } catch (IOException ignored) {
        }
        closeAll(null);
    }

    // ── the computer's input ──

    private void inputLoop() throws IOException {
        DesktopInput in = desktop.input;
        while (!closing) {
            LinuxStreamSocket.Message m = ws.read();
            if (m == null) return;
            if (m.opcode == LinuxStreamSocket.OP_BINARY) {
                byte[] d = m.data;
                if (d.length == 0) continue;
                switch (d[0]) {
                    case IN_ACK:
                        acked();
                        break;
                    case IN_POINTER:
                        if (d.length >= 10) in.pointer(u16(d, 2), u16(d, 4), d[1] & 0xff, i32(d, 6));
                        break;
                    case IN_SCROLL:
                        if (d.length >= 17) {
                            in.scroll(u16(d, 1), u16(d, 3), Float.intBitsToFloat(i32(d, 5)),
                                    Float.intBitsToFloat(i32(d, 9)), i32(d, 13));
                        }
                        break;
                    case IN_KEY:
                        if (d.length >= 9) in.key(d[1] != 0, u16(d, 3), d[2] & 0xff, i32(d, 5));
                        break;
                    case IN_TEXT:
                        if (d.length > 1) in.text(new String(d, 1, d.length - 1, StandardCharsets.UTF_8));
                        break;
                    default:
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
                case "resize": {
                    int w = even(clamp(j.optInt("w"), MIN_W, MAX_W));
                    int h = even(clamp(j.optInt("h"), MIN_H, MAX_H));
                    if (desktop.resize(w, h)) newEncoder();
                    break;
                }
                case "clip":
                    if (desktop.clipboard != null) desktop.clipboard.set(j.optString("text"));
                    break;
                case "keyframe":
                    desktop.video.requestKeyFrame();
                    break;
                case "bitrate": {
                    // The route changed: what it carries is the new ceiling,
                    // and the link has its say from there.
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
                    send(p);
                    break;
                }
                default:
            }
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
        desktop.video.poke();
    }

    /** What the phone copied, for the window — it decides whether it lands. */
    void clip(String text) {
        JSONObject j = new JSONObject();
        try {
            j.put("t", "clip").put("text", text);
            send(j);
        } catch (Exception ignored) {
        }
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
        if (why != null) Desktop.log("ending the session: " + why);
        LinuxStreamSocket s = ws;
        if (s != null) s.close();
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }

    private static int u16(byte[] d, int at) {
        return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff);
    }

    private static int i32(byte[] d, int at) {
        return ((d[at] & 0xff) << 24) | ((d[at + 1] & 0xff) << 16) | ((d[at + 2] & 0xff) << 8) | (d[at + 3] & 0xff);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Sizes are even: every encoder takes those. */
    private static int even(int v) {
        return v & ~1;
    }
}
