package com.ccrstech.openandroiddex.launcher;

import android.app.KeyguardManager;
import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.media.projection.MediaProjection;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Remote desktop's stream: this phone's screen to a computer, and the
 * computer's mouse and keyboard back.
 *
 * <p><b>Who gets in.</b> An abstract socket, {@code <package>.phone-stream},
 * which every app on the phone can reach, so each connection's peer is
 * checked: this app's own uid (the remote-access daemon, which carries the
 * stream through the tunnel from a computer that paired with this phone),
 * the shell (2000, which is adb, so a computer on the cable) and root. Anyone
 * with adb already owns the phone; nobody else gets a byte. The daemon lets
 * through only the computers the phone paired with ({@code peers.json}).
 *
 * <p><b>The wire</b> is {@link LinuxStream}'s, after a WebSocket upgrade on
 * {@code /phone} (or {@code /stream}), so the desktop app's window, its
 * decoder and the bitrate rule that reads arrival times all work unchanged.
 * What differs is what the picture is and what input means:
 * <pre>
 * computer → phone   text   {"t":"hello","v":1,"w":..,"h":..,"bitrate":..,"fps":..,"acks":bool,"ackAt":bool,
 *                            "quality"?:"low"|"medium"|"high","scale"?:0.25..1,
 *                            "audio"?:bool,"phone"?:bool,"codecs"?:["opus","pcm"],"panel"?:bool}
 *                    text   {"t":"panel","on":bool}  — the phone's own screen lit or dark, the phone running
 *                              on for the computer (false: as scrcpy's screen off; {@link PhoneHelperLink})
 *                    text   {"t":"audio","on"?:bool,"phone"?:bool,"codecs"?:[..]}  — the phone's sound:
 *                              on, sent here; phone, still played on the phone ({@link PhoneSound})
 *                           | {"t":"bye"}  — the window is going (its tab or window closed): the
 *                              session ends now, and Remote desktop stays on for the next one
 *                    text   {"t":"quality","mode":..} | {"t":"keyframe"} | {"t":"ping","n":..}
 *                           | {"t":"size","scale":0.25..1}  — Picture size: this part of the picture the
 *                              quality makes (1: all of it, 0.5: half its width and height)
 *                           | {"t":"bitrate","bitrate":..,"ackAt"?:bool} | {"t":"picture","on":bool}
 *                           | {"t":"text","text":..}  — typed or pasted text, into the focused field
 *                           | {"t":"key","key":"back"|"home"|"recents"|"notifications"|"quicksettings"
 *                              |"power"|"lock"|"screenshot"|"wake"}
 *                           | {"t":"clip","text":..}  — the computer's clipboard, for the phone's
 *                           | {"t":"share"}  — ask the phone for screen sharing again
 *                    binary 0x10 buttons u8, x u16, y u16  — the mouse, in the phone's screen pixels:
 *                           bit 0 is a finger, bit 1 Home, bit 2 Back, bits 3-6 a wheel's notches (64 dp each)
 *                    binary 0x13 x u16, y u16, dx i16, dy i16  — the wheel or a touchpad: the page at x, y
 *                           scrolls by dx, dy of the phone's screen pixels, as a browser's wheel deltas say it
 *                           (positive dy shows what is further down). One message for each wheel event, with
 *                           as much as a page on the computer would scroll: bits 3-6 can only count notches, a
 *                           press and a release each, and a touchpad's small, fast steps would be dozens of
 *                           messages a notch ({@link PhoneInput#scroll})
 *                    binary 0x11 down u8, keysym u32       — a key, as X11 names it
 *                    binary 0x12 [arrived f64]             — one frame arrived (LinuxStreamRate)
 * phone → computer   text   {"t":"state","state":"running"|"waiting"|"stopped"|"off"|"replaced"|"failed",
 *                            "message"?:..}
 *                    text   {"t":"video",..,"cw":..,"ch":..,"dw":screenW,"dh":screenH,"scale":..,"kind":"phone"}
 *                            before every encoder (cw×ch: the picture, scale: the Picture size asked for)
 *                    text   {"t":"phone","control":bool,"screenOn":bool,"locked":bool,"quality":..,
 *                            "panel":"none"|"on"|"off","panelWanted":bool,"panelStart"?:..}  — panel: the
 *                              phone's own screen ("none": no helper runs; panelStart, the adb command
 *                              that starts one); panelWanted: this window's wish, which the phone's
 *                              power button takes back
 *                    text   {"t":"stats",..} | {"t":"pong","n":..}
 *                    text   {"t":"audio","state":"off"|"on"|"permission"|"sharing"|"android"|"failed",
 *                            "phone":bool,"quiet":bool}  — the sound's state, when it changes
 *                    text   {"t":"sound",..}  — the sound that follows, as {@link LinuxStream}'s
 *                    text   {"t":"lock","pad":""|"pin"|"password"|"pattern",..}  — the lock screen's
 *                              keys and words, which screen sharing shows black ({@link PhoneLock})
 *                    binary 0x01 flags u8, pts µs u64, H.264 Annex B
 *                    binary 0x03 codec u8, read at µs u64, data  — the phone's sound, as Linux's
 * </pre>
 *
 * <p><b>Sound costs nothing until asked for.</b> Sound on the phone alone
 * and Sound off read nothing and send nothing; only a window that plays the
 * sound gets it, Opus at the picture's quality ({@link LinuxStreamAudio}).
 *
 * <p><b>A locked phone.</b> Android hides the PIN pad from screen sharing
 * (on a Galaxy, even from adb's screenshot), so while it is up the phone
 * says where its keys are and what it says, and the window draws them over
 * the black picture; a click there is a tap on the real key, and the
 * computer's number keys, Backspace and Enter press them
 * ({@link PhoneLock}). A click or a key on a phone that is asleep wakes it.
 *
 * <p><b>One computer at a time.</b> A new session takes the screen from the
 * old one, which is told so and closed: it is the same person's window come
 * back after the link dropped, or another of their computers.
 *
 * <p>Runs in the app's own process while Remote desktop is on and something
 * holds it: the control service ({@link PhoneControlService}), which Android
 * keeps running while it is switched on, or the screen sharing
 * ({@link PhoneRemoteService}). Input is dispatched in this same process, so
 * a pointer move never crosses a process to get to the screen.
 */
final class PhoneStream {

    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;

    static final byte MSG_VIDEO = 0x01;
    static final byte MSG_AUDIO = 0x03;
    static final byte IN_POINTER = 0x10;
    static final byte IN_KEY = 0x11;
    static final byte IN_ACK = 0x12;
    static final byte IN_SCROLL = 0x13;

    static {
        // The phone's sound is Opus where the phone can make it, as Linux's is.
        LinuxStreamAudio.opus = LinuxStreamOpus.MAKER;
    }

    /** How often a locked phone's lock screen is read for the computer ({@link PhoneLock}). */
    private static final long LOCK_LOOK_MS = 300;
    /** Lock watches running: a replaced session's and its successor's can overlap for a moment. */
    private static final java.util.concurrent.atomic.AtomicInteger LOCK_WATCHES =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * A computer heard nothing from for this long is gone: a tab that
     * vanished without a word (a crash, a lid shut, the network gone) holds
     * no session, its sound, the phone kept quiet or its screen kept on any
     * longer than this. A window sends a byte every 50 ms in front and every
     * 2 s hidden; a hidden tab's timers may slow to one a minute.
     */
    private static final int SILENT_MS = 90_000;

    /** Frames in the encoder at once: one being made, one more ready to go. */
    private static final int MAX_IN_ENCODER = 2;
    private static final int MIN_WINDOW = 2;
    /** As {@link LinuxStream}'s, and for the same reason: latency first. */
    private static final int MAX_WINDOW = 12;
    private static final int RTT_SAMPLES = 32;
    private static final long ACK_GIVE_UP_MS = 3000;
    private static final long KEY_AGAIN_MS = 500;
    private static final int MIN_BITRATE = 500_000;
    private static final double BEHIND_SLOW = 0.2;
    private static final double BEHIND_FINE = 0.02;
    private static final double BITRATE_DOWN = 0.7;
    private static final double BITRATE_UP = 1.25;
    /** How often the phone's own state (control, screen, lock) is looked at for the computer. */
    private static final long PHONE_STATE_MS = 1000;

    private static PhoneStream instance;
    private static final Set<Object> holders = new HashSet<>();

    private final Context ctx;
    private final LocalServerSocket server;
    private final Thread acceptor;
    private volatile boolean stopped;
    private Session current;

    // ── who runs it ──

    /** {@code who} needs the stream: started if it is not running and Remote desktop is on. */
    static synchronized void hold(Context ctx, Object who) {
        holders.add(who);
        if (instance != null || !PhoneRemote.isEnabled(ctx)) return;
        try {
            instance = new PhoneStream(ctx.getApplicationContext());
            DexLog.step("phone", "listening on @" + PhoneRemote.socketName(ctx));
        } catch (IOException e) {
            DexLog.warn("phone", "cannot listen", e);
        }
    }

    /** {@code who} is done with it; the last one out stops it. */
    static synchronized void release(Object who) {
        holders.remove(who);
        if (holders.isEmpty()) stopNow("nothing holds it");
    }

    /** Remote desktop was switched off: the stream goes whoever holds it. */
    static synchronized void switchedOff() {
        stopNow("switched off");
    }

    /** Remote desktop was switched on: start for whoever already holds it. */
    static synchronized void switchedOn(Context ctx) {
        if (instance == null && !holders.isEmpty()) {
            Object any = holders.iterator().next();
            holders.remove(any);
            hold(ctx, any);
        }
    }

    private static void stopNow(String why) {
        PhoneStream s = instance;
        instance = null;
        if (s != null) s.shutdown(why);
    }

    /** Who is watching now, or null. */
    static String watcher() {
        PhoneStream s;
        synchronized (PhoneStream.class) {
            s = instance;
        }
        if (s == null) return null;
        Session c = s.current();
        return c != null && c.live ? c.who : null;
    }

    /** Screen sharing started or ended: the session watching, if any, is told. Any thread. */
    static void screenChanged() {
        PhoneStream s;
        synchronized (PhoneStream.class) {
            s = instance;
        }
        if (s == null) return;
        Session c = s.current();
        if (c != null) c.screenChanged();
    }

    /**
     * This app is about to show one of Android's own questions (a permission's)
     * while a computer watches: from Android 14 a service that is not an
     * accessibility tool cannot press its buttons, nor even see its window on
     * Android 17 (an S25), so the computer is told why its press will do
     * nothing, unless the phone's helper is here to press for it.
     */
    static void askingAndroid(String what) {
        if (android.os.Build.VERSION.SDK_INT < 34 || PhoneHelperLink.linked()) return;
        PhoneStream s;
        synchronized (PhoneStream.class) {
            s = instance;
        }
        if (s == null) return;
        Session c = s.current();
        if (c == null || !c.live) return;
        try {
            c.send(new JSONObject().put("t", "protected").put("what", what));
        } catch (Exception ignored) {
        }
    }

    /** The phone's own screen went dark or lit, or its helper came or went (PhoneHelperLink): the session watching is told. */
    static void panelChanged() {
        PhoneStream s;
        synchronized (PhoneStream.class) {
            s = instance;
        }
        if (s == null) return;
        Session c = s.current();
        if (c != null && c.live) c.tellPhone(true);
    }

    /** The sound's permission was given or refused on the phone: the session watching hears it, or is told why not. */
    static void soundAllowedChanged() {
        PhoneStream s;
        synchronized (PhoneStream.class) {
            s = instance;
        }
        if (s == null) return;
        Session c = s.current();
        if (c != null) c.soundChanged();
    }

    // ── the socket ──

    private PhoneStream(Context ctx) throws IOException {
        this.ctx = ctx;
        // A session that ended with the app's process may have left the phone quiet.
        PhoneSound.undoLeftOver(ctx);
        // On a Xiaomi phone, MIUI's Autostart, which can go off again by itself.
        PhoneRemoteService.checkXiaomiAutostart(ctx);
        PhoneHelperLink.onChange(PhoneStream::panelChanged);
        // The helper started by the app itself (no computer's adb): its progress, for the window.
        PhoneHelperStart.onChange(PhoneStream::panelChanged);
        this.server = new LocalServerSocket(PhoneRemote.socketName(ctx));
        this.acceptor = new Thread(this::acceptLoop, "phone-stream");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private synchronized Session current() {
        return current;
    }

    private void acceptLoop() {
        while (!stopped) {
            LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!stopped) DexLog.warn("phone", "accept: " + e);
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
                DexLog.warn("phone", "refused a connection from uid " + uid);
                closeQuietly(s);
                continue;
            }
            new Session(s, uid).begin();
        }
    }

    /** {@code s} has the screen now; the one before it, if any, is told and ends. */
    private boolean claim(Session s) {
        Session old;
        synchronized (this) {
            if (stopped) return false;
            old = current;
            current = s;
        }
        if (old != null && old != s) old.endWith("replaced", "Another computer opened this phone.");
        return true;
    }

    /** @return whether {@code s} had the screen until now */
    private synchronized boolean ended(Session s) {
        if (current != s) return false;
        current = null;
        return true;
    }

    private void shutdown(String why) {
        Session c;
        synchronized (this) {
            stopped = true;
            c = current;
            current = null;
        }
        if (c != null) {
            if (PhoneRemote.isEnabled(ctx)) {
                // Still on: screen sharing ended, and with control off there
                // is no slow view to carry on with.
                c.endWith("stopped", "Screen sharing ended on the phone. Turn on control in its Settings to keep a slow view when that happens.");
            } else {
                c.endWith("off", "Remote desktop was turned off on the phone.");
            }
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        DexLog.step("phone", "stopped listening (" + why + ")");
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── one computer ──

    private final class Session implements PhoneSource.Sink, PhoneSource.Gate {
        private final LocalSocket sock;
        private final Thread thread;
        /** For the log and the Settings page: who it is, as far as the phone can tell. */
        volatile String who;
        private volatile LinuxStreamSocket ws;
        private volatile boolean closing;
        /** Has the screen (claimed), whether or not a picture flows yet. */
        volatile boolean live;

        private final PhoneInput input = new PhoneInput(ctx);
        private PhoneRemote.Quality quality;
        /** The window's Picture size: the part of the quality's picture the picture is (1: all of it). */
        private volatile double scale = 1;
        private int fps = 60;
        private volatile int bitrate;
        private int ceiling;
        private boolean acks;
        private volatile LinuxStreamRate rate;
        private volatile PhoneSource source;
        /**
         * What the source now carrying the picture was handed as its sink and
         * gate (start, stop and setQuality take the same one). A source the
         * session has moved off may still finish work it had begun, on its
         * own thread: the slow view's screenshot that comes back after screen
         * sharing took over, an encoder's last units. That source's feed is
         * no longer the session's, and goes nowhere. Until 2026-11-22 the
         * slow view's late screenshot made an encoder and said "slow view"
         * after screen sharing's own message, and the computer stayed on the
         * slow view (the first computer after the app started, on the S25).
         */
        private volatile Feed feed;

        private final class Feed implements PhoneSource.Sink, PhoneSource.Gate {
            private boolean current() {
                return feed == this;
            }

            @Override
            public void video(PhoneSource.Encoding e) {
                if (current()) Session.this.video(e);
            }

            @Override
            public void packet(byte[] data, int len, long ptsUs, int flags) throws IOException {
                if (current()) Session.this.packet(data, len, ptsUs, flags);
            }

            @Override
            public void stopped(Throwable why) {
                if (current()) Session.this.stopped(why);
            }

            @Override
            public boolean mayDraw() {
                return current() && Session.this.mayDraw();
            }

            @Override
            public void drew() {
                if (current()) Session.this.drew();
            }
        }
        private volatile boolean pictureOff;
        private volatile boolean waitKey;
        private long keyAskedAt;

        // The window: guarded by pace.
        private final Object pace = new Object();
        private int inEncoder;
        private long sent;
        private long acked;
        private final ArrayDeque<long[]> sentAt = new ArrayDeque<>();
        private final long[] rtts = new long[RTT_SAMPLES];
        private int rttCount;
        private long rttMedianNs = -1;
        private long minRttNs = Long.MAX_VALUE;
        private long blockedSince;
        private long linkWaitNs;
        private long lastAckAt;

        // The second's numbers, on the drain thread.
        private long statsSince = SystemClock.elapsedRealtime();
        private int statsFrames;
        private long statsBytes;
        private long statsEncodeUs;
        private long statsSendUs;
        private int framesLastSecond;
        private int keptUp;
        private int oldCuts;

        private String phoneSaid = "";
        private long phoneLookedAt;
        /** This session put the test pad up: it comes down with the session. */
        private volatile boolean padOn;

        // ── the phone's sound ({@link PhoneSound}) ──
        /** The window plays the phone's sound: it is read and sent. */
        private volatile boolean soundWanted;
        /** The phone plays its own sound too (the default); false keeps it quiet. */
        private volatile boolean soundOnPhone = true;
        /** The window can decode Opus (WebCodecs' AudioDecoder). */
        private volatile boolean pageOpus;
        private final Object soundLock = new Object();
        /** Reading the phone's sound for this window; null while it is not. Under soundLock. */
        private LinuxStreamAudio sound;
        /** The projection {@link #sound} reads through. Under soundLock. */
        private Object soundFrom;
        /** Why a reading ended by itself, and the projection it read: not started again on that one. Under soundLock. */
        private String soundFailed;
        private Object soundFailedOn;
        /** The sound's state as the window was last told it. Under soundLock. */
        private String soundSaid = "";
        /** The permission was asked for on this session's behalf already. Under soundLock. */
        private boolean soundAsked;
        private final java.util.concurrent.atomic.AtomicLong soundSent = new java.util.concurrent.atomic.AtomicLong();
        private long soundSeen;

        // ── the lock screen ({@link PhoneLock}) ──
        private volatile Thread lockWatch;
        /** What the window was last told of the lock screen. On lockWatch's thread. */
        private String lockSaid = "";

        private final int uid;
        /** The line said what it wanted (its request came). */
        private volatile boolean asked;
        /** The screen was this session's: "opened the screen" was said. */
        private volatile boolean opened;

        Session(LocalSocket sock, int uid) {
            this.sock = sock;
            this.uid = uid;
            this.who = uid == Process.myUid() ? "a computer, through remote access"
                    : uid == SHELL_UID ? "a computer on the cable" : "uid " + uid;
            this.thread = new Thread(this::run, "phone-stream-session");
            thread.setDaemon(true);
        }

        void begin() {
            thread.start();
        }

        private void run() {
            try {
                sock.setSoTimeout(15_000);
                LinuxStreamSocket conn = new LinuxStreamSocket(sock.getInputStream(), sock.getOutputStream(), sock);
                LinuxStreamSocket.Request req = conn.readRequest();
                asked = true;
                if ("/probe".equals(req.path)) {
                    JSONObject p = new JSONObject();
                    p.put("v", 1).put("kind", "phone").put("name", LinuxRemote.phoneName(ctx))
                            .put("sharing", PhoneRemoteService.sharing())
                            .put("control", PhoneControlService.live() != null);
                    conn.respond(200, "OK", "application/json", p.toString().getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (!"/phone".equals(req.path) && !"/stream".equals(req.path)) {
                    conn.respond(404, "Not Found", "text/plain", new byte[0]);
                    return;
                }
                // Which way it came, for the log and the Settings page: said
                // by the browser door or the tunnel (both this app's uid),
                // which drop whatever x-dex-* a browser sent itself.
                String via = uid == Process.myUid() ? req.headers.get("x-dex-via") : null;
                String peer = req.headers.get("x-dex-peer");
                if ("wifi".equals(via)) {
                    who = "a browser on the same Wi-Fi";
                } else if ("tunnel".equals(via)) {
                    who = "a browser, through the Cloudflare tunnel";
                } else if ("remote".equals(via) && peer != null && !peer.trim().isEmpty()) {
                    String name = peer.replaceAll("[\\p{Cntrl}]", " ").trim();
                    who = (name.length() > 60 ? name.substring(0, 60) : name) + ", through remote access";
                }
                conn.upgrade(req);
                ws = conn;
                LinuxStreamSocket.Message m = ws.read();
                if (m == null || m.opcode != LinuxStreamSocket.OP_TEXT) return;
                JSONObject hello = new JSONObject(m.text());
                if (!"hello".equals(hello.optString("t"))) return;
                sock.setSoTimeout(SILENT_MS);
                // The window's choice: it sees the picture and the link. A
                // window that says none gets Medium.
                quality = PhoneRemote.Quality.of(hello.optString("quality", hello.optString("mode", "")));
                scale = PhoneRemote.sizeScale(hello.optDouble("scale", 1));
                fps = clamp(hello.optInt("fps", quality.fps), 10, 60);
                ceiling = clamp(hello.optInt("bitrate", quality.maxBitrate), MIN_BITRATE, 150_000_000);
                acks = hello.optBoolean("acks");
                // The sound's two switches: a window that says nothing hears
                // nothing, and leaves the phone playing as it was.
                soundWanted = hello.optBoolean("audio");
                soundOnPhone = hello.optBoolean("phone", true);
                pageOpus = opusIn(hello);
                // The phone's own screen: lit unless the window says otherwise ("panel":false, PhoneHelperLink).
                boolean panelDark = hello.has("panel") && !hello.optBoolean("panel", true);
                if (!claim(this)) return;
                live = true;
                opened = true;
                DexLog.step("phone", who + " opened the screen, " + quality.id + (scale < 1 ? ", at " + Math.round(scale * 100) + "% of its size" : ""));
                PhoneRemoteService.watching(ctx, who);
                if (acks && hello.optBoolean("ackAt")) {
                    rate = new LinuxStreamRate(ceiling, ceiling, MIN_BITRATE);
                }
                // A window that only watches may leave the phone as it is
                // ("wake":false): asleep, its screen draws nothing new.
                PhoneControlService control = PhoneControlService.live();
                if (control != null && hello.optBoolean("wake", true)) {
                    control.keepAwake(this, true);
                    control.wake();
                }
                PhoneHelperLink.session(ctx, this, true, panelDark);
                // No helper yet: the app starts it itself where the phone was set up for that
                // (PhoneHelperStart; nothing happens on a phone that was not). A window that
                // came back wanting the screen dark counts as asking.
                if (!PhoneHelperLink.linked()) PhoneHelperStart.ensure(ctx, panelDark);
                // A press Android keeps from the service (no helper here): the window says why nothing happened.
                input.onProtected(what -> {
                    try {
                        send(new JSONObject().put("t", "protected").put("what", what));
                    } catch (Exception ignored) {
                    }
                });
                tellPhone(true);
                // Control was on and Android turned control off (the app was
                // force-stopped): the person at the phone is asked to turn it back on.
                // On in Android's settings but never connected (seen after an
                // update on Android 10): asked to turn it off and on.
                if (PhoneControlService.live() == null && PhoneRemote.controlLost(ctx)) {
                    PhoneRemoteService.postControlLost(ctx);
                } else if (PhoneRemote.controlUnbound(ctx)) {
                    PhoneRemoteService.postControlUnbound(ctx);
                }
                screenChanged();
                soundChanged();
                watchLock();
                inputLoop();
            } catch (Exception e) {
                // A line that closes before it asks anything is no computer:
                // the door drops one it opened for a browser signed out at
                // that moment, before a byte goes. Not worth a line, and its
                // "who" would only be this app's own uid's guess.
                if (!closing && (asked || !(e instanceof java.io.EOFException))) {
                    DexLog.step("phone", who + ": " + e.getMessage());
                }
            } finally {
                finish();
            }
        }

        /**
         * Screen sharing or the control service came or went: the picture
         * comes from the best source there is now (screen sharing, else the
         * slow view), or the computer is told it waits. Synchronized: the
         * session's thread, a projection's callback and the control service
         * can all call it at once.
         */
        synchronized void screenChanged() {
            if (closing || !live) return;
            // Unattended access: screen sharing needs no one at the phone, so
            // a computer gets full speed rather than the slow view. Android
            // asks nobody; the slow view carries the picture until it starts.
            if (PhoneRemoteService.screen() == null && PhoneRemote.isEnabled(ctx) && PhoneRemote.unattended(ctx)) {
                PhoneRemoteService.askForScreen(ctx, "unattended access, for " + who);
            }
            PhoneSource now = PhoneRemoteService.screen();
            if (now == null) now = PhoneControlService.shots();
            PhoneSource was = source;
            if (now == was) {
                if (now == null) waiting();
                return;
            }
            // The old source is not heard from here on, whatever it still finishes.
            Feed old = feed;
            Feed f = now == null ? null : new Feed();
            feed = f;
            source = now;
            if (was != null) was.stop(old);
            if (now == null) {
                waiting();
                soundChanged();
                return;
            }
            synchronized (pace) {
                inEncoder = 0;
            }
            try {
                state("running", null);
            } catch (IOException e) {
                return;
            }
            DexLog.step("phone", who + " gets " + (now instanceof PhoneScreen ? "screen sharing" : "the slow view"));
            now.start(f, f, quality.scaled(scale), startBitrate(now), false);
            // The sound comes with screen sharing and goes with it.
            soundChanged();
        }

        // ── the phone's sound ──

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
            return LinuxStreamAudio.Format.forMode(quality.id, pageOpus && LinuxStreamAudio.opus != null);
        }

        /**
         * Where the phone's sound goes, now: read and sent or not, and the
         * phone's own speaker quiet or not, from the window's two switches
         * and what the phone can do. Any thread, any number of times: after
         * the hello, an ask, a change of screen sharing, a permission given.
         */
        void soundChanged() {
            boolean open = live && !closing;
            PhoneSound.quiet(ctx, this, open && !soundOnPhone);
            MediaProjection mp = PhoneRemoteService.projection();
            String state;
            boolean askNow = false;
            synchronized (soundLock) {
                if (!open || !soundWanted) {
                    state = "off";
                } else if (!PhoneSound.supported()) {
                    state = "android";
                } else if (!PhoneSound.allowed(ctx)) {
                    state = "permission";
                    askNow = !soundAsked;
                    soundAsked = true;
                } else if (mp == null) {
                    state = "sharing";
                } else if (soundFailed != null && soundFailedOn == mp) {
                    state = "failed";
                } else {
                    state = "on";
                }
                if ("on".equals(state)) {
                    if (sound == null || soundFrom != mp) {
                        if (sound != null) sound.stop();
                        final MediaProjection from = mp;
                        sound = new LinuxStreamAudio(new SoundSink(from), who, soundFormat(), PhoneSound.capture(mp), "phone").start();
                        soundFrom = mp;
                    }
                } else if (sound != null) {
                    sound.stop();
                    sound = null;
                    soundFrom = null;
                }
            }
            if (askNow) PhoneSound.ask(ctx);
            // Allowed since (in Settings, or Android's own app settings): the question is no longer one.
            if (!"permission".equals(state) && PhoneSound.allowed(ctx)) PhoneSound.cancelAsk(ctx);
            tellSound(state);
        }

        /** The window told of the sound's state when it changes: what it hears, and whether the phone is quiet. */
        private void tellSound(String state) {
            boolean quiet = PhoneSound.quietNow() && !soundOnPhone;
            String said = state + "/" + soundOnPhone + "/" + quiet;
            synchronized (soundLock) {
                if (said.equals(soundSaid)) return;
                soundSaid = said;
            }
            DexLog.step("phone", "sound for " + who + ": " + state + (soundOnPhone ? ", the phone plays too" : quiet ? ", the phone quiet" : ""));
            try {
                send(new JSONObject().put("t", "audio").put("state", state).put("phone", soundOnPhone).put("quiet", quiet));
            } catch (Exception ignored) {
            }
        }

        private void stopSound() {
            synchronized (soundLock) {
                if (sound != null) sound.stop();
                sound = null;
                soundFrom = null;
            }
            PhoneSound.quiet(ctx, this, false);
        }

        /** Where one reading's sound goes: its form first, then each run, as Linux's sound goes. */
        private final class SoundSink implements LinuxStreamAudio.Sink {
            private final MediaProjection from;

            SoundSink(MediaProjection from) {
                this.from = from;
            }

            @Override
            public void format(LinuxStreamAudio.Format f) throws IOException {
                LinuxStreamSocket s = ws;
                if (s == null || closing) throw new IOException("closed");
                s.sendText(f.json());
            }

            @Override
            public void audio(int codec, byte[] data, int off, int len, long atUs) throws IOException {
                byte[] body = off == 0 ? data : java.util.Arrays.copyOfRange(data, off, off + len);
                ByteBuffer head = ByteBuffer.allocate(10);
                head.put(MSG_AUDIO).put((byte) codec).putLong(atUs);
                LinuxStreamSocket s = ws;
                if (s == null || closing) throw new IOException("closed");
                s.sendBinary(head.array(), body, len);
                soundSent.addAndGet(len);
            }

            @Override
            public void ended(String why) {
                synchronized (soundLock) {
                    if (soundFrom != from) return;
                    soundFailed = why;
                    soundFailedOn = from;
                    sound = null;
                    soundFrom = null;
                }
                DexLog.warn("phone", "the phone's sound stopped for " + who + ": " + why);
                soundChanged();
            }
        }

        // ── the lock screen ──

        /**
         * While the session lasts, the lock screen looked at a few times a
         * second while the phone is locked (nothing is read while it is not):
         * the window is told where the hidden PIN pad's keys are and what it
         * says, and when the phone locks or unlocks.
         */
        private void watchLock() {
            Thread t = new Thread(() -> {
                LOCK_WATCHES.incrementAndGet();
                try {
                    KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
                    while (!closing && live) {
                        try {
                            boolean locked = km != null && km.isKeyguardLocked();
                            if (!locked) {
                                PhoneControlService c = PhoneControlService.live();
                                if (c != null) c.seeAllViews(false);
                            }
                            String json = locked ? PhoneLock.read(ctx) : PhoneLock.NONE;
                            if (!json.equals(lockSaid)) {
                                lockSaid = json;
                                sendRaw(json);
                            }
                            tellPhone(false);
                            Thread.sleep(LOCK_LOOK_MS);
                        } catch (InterruptedException e) {
                            return;
                        } catch (Exception e) {
                            if (closing) return;
                            try {
                                Thread.sleep(LOCK_LOOK_MS);
                            } catch (InterruptedException ie) {
                                return;
                            }
                        }
                    }
                } finally {
                    // The last watch to stop puts control back to its usual,
                    // smaller view of the screen. A session that ends while
                    // the phone is locked never sees the unlock that does it
                    // above, and the wider view stayed on after it (seen on
                    // the S25 as fetchFlags=384 after the computer left).
                    if (LOCK_WATCHES.decrementAndGet() == 0) {
                        PhoneControlService c = PhoneControlService.live();
                        if (c != null) c.seeAllViews(false);
                    }
                }
            }, "phone-lock-watch");
            t.setDaemon(true);
            lockWatch = t;
            t.start();
        }

        /**
         * Nothing to show: no screen sharing and no control service. The phone
         * is asked, on its screen, for someone there to say yes. With the
         * control service on there is always the slow view, and the computer
         * asks for screen sharing itself ({@code {"t":"share"}}), when the
         * person at it wants full speed.
         */
        private void waiting() {
            try {
                state("waiting", PhoneRemote.isEnabled(ctx)
                        ? "Waiting for screen sharing to be allowed on the phone."
                        : "Remote desktop is turned off on the phone.");
            } catch (IOException ignored) {
                return;
            }
            if (PhoneRemote.isEnabled(ctx)) PhoneRemoteService.askForScreen(ctx, "a computer is waiting");
        }

        private int startBitrate(PhoneSource s) {
            PhoneSource.Encoding e = s.encoding();
            int w = e != null ? e.w : 1080, h = e != null ? e.h : 2340;
            int b = Math.min(ceiling, quality.bitrateFor(w, h));
            bitrate = Math.max(MIN_BITRATE, b);
            LinuxStreamRate rt = rate;
            if (rt != null) rt.set(bitrate, Math.min(ceiling, quality.maxBitrate), System.nanoTime());
            return bitrate;
        }

        // ── the picture ──

        @Override
        public void video(PhoneSource.Encoding e) {
            synchronized (pace) {
                inEncoder = 0;
            }
            // The encoder decides the first bitrate from the picture's size.
            int b = Math.max(MIN_BITRATE, Math.min(ceiling, quality.bitrateFor(e.w, e.h)));
            if (b != e.bitrate) {
                bitrate = b;
                PhoneSource s = source;
                if (s != null) s.setBitrate(b);
            }
            LinuxStreamRate rt = rate;
            if (rt != null) rt.set(bitrate, Math.min(ceiling, quality.maxBitrate), System.nanoTime());
            input.picture(e);
            try {
                JSONObject v = new JSONObject();
                v.put("t", "video").put("kind", "phone").put("source", e.source)
                        .put("codec", "avc").put("encoder", e.codecName)
                        .put("hw", e.hardware).put("w", e.encW).put("h", e.encH).put("cw", e.w).put("ch", e.h)
                        .put("dw", e.screenW).put("dh", e.screenH).put("rotation", e.rotation)
                        .put("bitrate", bitrate).put("fps", e.fps).put("quality", quality.id)
                        .put("scale", scale).put("lowLatency", e.configAttempt == 0);
                send(v);
            } catch (Exception ignored) {
            }
        }

        @Override
        public boolean mayDraw() {
            if (closing || pictureOff) return false;
            long now = System.nanoTime();
            synchronized (pace) {
                boolean room = inEncoder < MAX_IN_ENCODER && (!acks || sent - acked < window());
                if (room) {
                    if (blockedSince != 0) {
                        linkWaitNs += now - blockedSince;
                        blockedSince = 0;
                    }
                    return true;
                }
                if (blockedSince == 0) blockedSince = now;
                if (acks && sent > acked) {
                    long[] oldest = sentAt.peekFirst();
                    LinuxStreamRate rt = rate;
                    if (rt != null && oldest != null) {
                        int next = rt.held(now, oldest[0]);
                        if (next > 0) setBitrateLater(next, "a frame is held");
                    }
                    // A computer that stopped acknowledging altogether is
                    // waited out: what it has is counted as arrived.
                    if (lastAckAt != 0 && (now - lastAckAt) / 1_000_000 > ACK_GIVE_UP_MS) {
                        DexLog.warn("phone", who + " stopped acknowledging frames; carrying on");
                        acked = sent;
                        sentAt.clear();
                        lastAckAt = now;
                        return true;
                    }
                }
                return false;
            }
        }

        @Override
        public void drew() {
            synchronized (pace) {
                inEncoder++;
            }
        }

        /** Set from a thread that must not wait: the GL thread. */
        private void setBitrateLater(int bps, String why) {
            bitrate = bps;
            PhoneSource s = source;
            if (s != null) s.setBitrate(bps);
            DexLog.step("phone", why + ": " + Math.round(bps / 1e5) / 10.0 + " Mb/s");
        }

        @Override
        public void packet(byte[] data, int len, long ptsUs, int flags) throws IOException {
            long readyNs = System.nanoTime();
            long outUs = readyNs / 1000;
            boolean config = (flags & LinuxStreamEncoder.FLAG_CONFIG) != 0;
            if (!config && (pictureOff || waitKey)) {
                if (!pictureOff && (flags & LinuxStreamEncoder.FLAG_KEY) != 0) {
                    waitKey = false;
                } else {
                    synchronized (pace) {
                        if (inEncoder > 0) inEncoder--;
                    }
                    if (!pictureOff && readyNs / 1_000_000 - keyAskedAt >= KEY_AGAIN_MS) {
                        keyAskedAt = readyNs / 1_000_000;
                        PhoneSource s = source;
                        if (s != null) s.requestKeyFrame();
                    }
                    return;
                }
            }
            ByteBuffer head = ByteBuffer.allocate(10);
            head.put(MSG_VIDEO).put((byte) flags).putLong(ptsUs);
            LinuxStreamSocket s = ws;
            if (s == null || closing) throw new IOException("closed");
            s.sendBinary(head.array(), data, len);
            if (config) return;
            long doneUs = System.nanoTime() / 1000;
            synchronized (pace) {
                if (inEncoder > 0) inEncoder--;
                sent++;
                if (acks) sentAt.addLast(new long[]{readyNs, len});
                if (lastAckAt == 0) lastAckAt = readyNs;
            }
            PhoneSource sc = source;
            if (sc != null) sc.kick();
            long now = SystemClock.elapsedRealtime();
            statsFrames++;
            statsBytes += len;
            statsEncodeUs += Math.max(0, outUs - ptsUs);
            statsSendUs += doneUs - outUs;
            if (now - statsSince >= 1000) {
                framesLastSecond = statsFrames;
                adapt(now - statsSince, statsFrames);
                stats(now);
            }
        }

        private void stats(long now) {
            JSONObject j = new JSONObject();
            try {
                double secs = (now - statsSince) / 1000.0;
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
                        .put("encodeMs", Math.round(statsEncodeUs / 1000.0 / n * 10) / 10.0)
                        .put("sendMs", Math.round(statsSendUs / 1000.0 / n * 10) / 10.0)
                        .put("unacked", behind)
                        .put("rttMs", rtt < 0 ? -1 : Math.round(rtt / 100.0) / 10.0)
                        .put("bestRttMs", best < 0 ? -1 : Math.round(best / 100.0) / 10.0)
                        .put("window", win)
                        .put("targetKbps", bitrate / 1000)
                        .put("quality", quality.id);
                long soundTotal = soundSent.get();
                j.put("soundKbps", Math.round((soundTotal - soundSeen) * 8 / 1000.0 / secs));
                soundSeen = soundTotal;
                LinuxStreamRate rt = rate;
                if (rt != null) {
                    long q = rt.queueNs();
                    j.put("queueMs", q < 0 ? -1 : Math.round(q / 1e5) / 10.0)
                            .put("cuts", rt.cutsByQueue + rt.cutsByFull)
                            .put("cutsQueue", rt.cutsByQueue).put("cutsFull", rt.cutsByFull)
                            .put("cutsHeld", rt.cutsByHeld);
                }
                j.put("cutsOld", oldCuts);
                send(j);
            } catch (Exception ignored) {
            }
            statsSince = now;
            statsFrames = 0;
            statsBytes = 0;
            statsEncodeUs = 0;
            statsSendUs = 0;
        }

        /** The bitrate held to what the link carries, once a second: LinuxStream's rule. */
        private void adapt(long periodMs, int frames) {
            long waited;
            synchronized (pace) {
                waited = linkWaitNs;
                linkWaitNs = 0;
            }
            double behind = waited / 1e6 / Math.max(1, periodMs);
            LinuxStreamRate rt = rate;
            if (rt != null) {
                int next = rt.full(System.nanoTime(), behind);
                if (next > 0) setBitrate(next, "the link is full (" + Math.round(behind * 100) + "% waiting)");
                return;
            }
            int now = bitrate;
            int top = Math.min(ceiling, quality.maxBitrate);
            if (behind > BEHIND_SLOW) {
                keptUp = 0;
                int next = Math.max(MIN_BITRATE, (int) (now * BITRATE_DOWN));
                if (next < now) {
                    oldCuts++;
                    setBitrate(next, "the link is behind (" + Math.round(behind * 100) + "% waiting)");
                }
            } else if (behind < BEHIND_FINE && frames >= fps / 4 && now < top) {
                if (++keptUp >= 3) {
                    keptUp = 0;
                    setBitrate(Math.min(top, (int) (now * BITRATE_UP)), "the link keeps up");
                }
            }
        }

        private void setBitrate(int bps, String why) {
            bitrate = bps;
            PhoneSource s = source;
            if (s != null) s.setBitrate(bps);
            DexLog.step("phone", why + ": " + Math.round(bps / 1e5) / 10.0 + " Mb/s");
        }

        @Override
        public void stopped(Throwable why) {
            if (closing) return;
            DexLog.warn("phone", "the encoder stopped: " + why);
            endWith("failed", "The phone's video encoder stopped.");
        }

        private int window() {
            if (rttMedianNs < 0) return MIN_WINDOW + 1;
            long frameNs = 1_000_000_000L / fps;
            long frames = (rttMedianNs + frameNs - 1) / frameNs + 1;
            return (int) Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, frames));
        }

        // ── the computer's input ──

        private void inputLoop() throws IOException {
            long heard = SystemClock.elapsedRealtime();
            while (true) {
                LinuxStreamSocket.Message m;
                try {
                    m = ws.read();
                } catch (IOException e) {
                    // The socket's own timeout: nothing at all for SILENT_MS.
                    if (!closing && SystemClock.elapsedRealtime() - heard >= SILENT_MS - 1000) {
                        DexLog.step("phone", "nothing from " + who + " for " + SILENT_MS / 1000 + " s: its session ends");
                        return;
                    }
                    throw e;
                }
                if (m == null) return;
                heard = SystemClock.elapsedRealtime();
                // Ending: read and dropped until the far end closes, or the deadline does.
                if (closing) continue;
                if (m.opcode == LinuxStreamSocket.OP_BINARY) {
                    byte[] d = m.data;
                    if (d.length >= 6 && d[0] == IN_POINTER) {
                        input.pointer(d[1] & 0xff, u16(d, 2), u16(d, 4));
                    } else if (d.length >= 9 && d[0] == IN_SCROLL) {
                        input.scroll(u16(d, 1), u16(d, 3), (short) u16(d, 5), (short) u16(d, 7));
                    } else if (d.length >= 6 && d[0] == IN_KEY) {
                        int keysym = ((d[2] & 0xff) << 24) | ((d[3] & 0xff) << 16) | ((d[4] & 0xff) << 8) | (d[5] & 0xff);
                        input.key(keysym, d[1] != 0);
                    } else if (d.length >= 1 && d[0] == IN_ACK) {
                        ack(d);
                    }
                } else {
                    JSONObject j;
                    try {
                        j = new JSONObject(m.text());
                    } catch (Exception e) {
                        continue;
                    }
                    command(j);
                    if (bye) {
                        DexLog.step("phone", who + " said goodbye");
                        return;
                    }
                }
                tellPhone(false);
            }
        }

        /** The window said it is going ({@code {"t":"bye"}}). */
        private boolean bye;

        private void ack(byte[] d) {
            long now = System.nanoTime();
            long[] f;
            synchronized (pace) {
                acked++;
                lastAckAt = now;
                f = sentAt.pollFirst();
                if (f != null) {
                    long rtt = now - f[0];
                    minRttNs = Math.min(minRttNs, rtt);
                    rtts[rttCount++ % RTT_SAMPLES] = rtt;
                    if (rttCount % 4 == 0 || rttCount < RTT_SAMPLES) {
                        long[] sorted = java.util.Arrays.copyOf(rtts, Math.min(rttCount, RTT_SAMPLES));
                        java.util.Arrays.sort(sorted);
                        rttMedianNs = sorted[sorted.length / 2];
                    }
                }
            }
            PhoneSource s = source;
            if (s != null) s.kick();
            LinuxStreamRate rt = rate;
            if (rt != null && f != null && d.length >= 9) {
                double arrivedMs = ByteBuffer.wrap(d, 1, 8).getDouble();
                if (arrivedMs > 0 && !Double.isInfinite(arrivedMs) && !Double.isNaN(arrivedMs)) {
                    rt.ack(now, f[0], (long) (arrivedMs * 1e6), (int) f[1]);
                    int next = rt.decide(now, framesLastSecond >= fps / 4);
                    if (next > 0) {
                        long q = rt.queueNs();
                        setBitrate(next, next < bitrate
                                ? "the link is behind (a queue of " + Math.round(q / 1e6) + " ms)"
                                : "the link keeps up");
                    }
                }
            }
        }

        private void command(JSONObject j) throws IOException {
            switch (j.optString("t")) {
                case "keyframe": {
                    PhoneSource s = source;
                    if (s != null) s.requestKeyFrame();
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
                case "picture": {
                    boolean on = j.optBoolean("on", true);
                    if (on == !pictureOff) break;
                    pictureOff = !on;
                    if (on) {
                        waitKey = true;
                        PhoneSource s = source;
                        if (s != null) s.requestKeyFrame();
                    }
                    break;
                }
                case "quality": {
                    PhoneRemote.Quality q = PhoneRemote.Quality.of(j.optString("mode", quality.id));
                    if (q.id.equals(quality.id)) break;
                    quality = q;
                    fps = q.fps;
                    DexLog.step("phone", who + " chose " + q.id);
                    PhoneSource s = source;
                    Feed f = feed;
                    if (s != null && f != null) s.setQuality(f, q.scaled(scale), startBitrate(s));
                    // The sound follows the quality, at its next read.
                    synchronized (soundLock) {
                        if (sound != null) sound.setFormat(soundFormat());
                    }
                    tellPhone(true);
                    break;
                }
                case "audio": {
                    // The window's sound switches: heard there or not, and on the phone or not.
                    if (j.has("on")) soundWanted = j.optBoolean("on");
                    if (j.has("phone")) soundOnPhone = j.optBoolean("phone", true);
                    if (j.has("codecs")) pageOpus = opusIn(j);
                    DexLog.step("phone", who + " chose sound " + (soundWanted ? "here" : "not here")
                            + (soundOnPhone ? " and on the phone" : ", the phone quiet"));
                    synchronized (soundLock) {
                        // A new choice tries again what failed before.
                        soundFailed = null;
                        soundFailedOn = null;
                        if (sound != null) sound.setFormat(soundFormat());
                    }
                    soundChanged();
                    break;
                }
                case "bye":
                    // The window is going: the session ends now, not when the
                    // link notices (a relay can take many seconds to).
                    bye = true;
                    break;
                case "size": {
                    // The window's Picture size: a smaller picture, the quality's
                    // bits per pixel on it (a new encoder, as for a quality).
                    double part = PhoneRemote.sizeScale(j.optDouble("scale", 1));
                    if (part == scale) break;
                    scale = part;
                    DexLog.step("phone", who + " chose " + Math.round(part * 100) + "% of the screen's size");
                    PhoneSource s = source;
                    Feed f = feed;
                    if (s != null && f != null) s.setQuality(f, quality.scaled(part), startBitrate(s));
                    break;
                }
                case "bitrate": {
                    int asked = clamp(j.optInt("bitrate", ceiling), MIN_BITRATE, 150_000_000);
                    ceiling = asked;
                    keptUp = 0;
                    if (j.has("ackAt")) {
                        if (!j.optBoolean("ackAt")) {
                            rate = null;
                        } else if (rate == null && acks) {
                            rate = new LinuxStreamRate(asked, asked, MIN_BITRATE);
                        }
                    }
                    int top = Math.min(ceiling, quality.maxBitrate);
                    LinuxStreamRate rt = rate;
                    if (rt != null) rt.set(Math.min(bitrate, top), top, System.nanoTime());
                    if (bitrate > top) setBitrate(top, "the computer's route changed");
                    break;
                }
                case "text":
                    input.text(j.optString("text"));
                    break;
                case "key": {
                    String k = j.optString("key");
                    if ("wake".equals(k)) {
                        PhoneControlService c = PhoneControlService.live();
                        if (c != null) c.wake();
                    } else {
                        // The computer locks the phone: the sleep is its own, not a person's press.
                        if ("lock".equals(k)) PhoneHelperLink.sleepComing();
                        input.nav(k);
                    }
                    break;
                }
                case "panel": {
                    // The phone's own screen dark or lit, the phone running on for the computer.
                    boolean dark = !j.optBoolean("on", true);
                    DexLog.step("phone", who + " turned the phone's own screen " + (dark ? "off" : "on"));
                    PhoneHelperLink.session(ctx, this, live, dark);
                    // Asked for with no helper here: started by the app, if the phone was set up for it.
                    if (dark && !PhoneHelperLink.linked()) PhoneHelperStart.ensure(ctx, true);
                    tellPhone(true);
                    break;
                }
                case "clip":
                    input.clip(j.optString("text"));
                    break;
                case "share":
                    if (PhoneRemoteService.screen() == null) {
                        PhoneRemoteService.askForScreen(ctx, "the computer asked");
                    }
                    break;
                case "testtone":
                    // A measuring aid, as the test pad is: a tone the phone plays as media.
                    if (PhoneControlService.padAllowed(ctx)) PhoneTestTone.play(j.optInt("hz", 440), j.optInt("ms", 1000));
                    break;
                case "testpad": {
                    // A debuggable build's measuring aid (PhoneControlService#testPad).
                    PhoneControlService c = PhoneControlService.live();
                    if (c == null || !PhoneControlService.padAllowed(ctx)) break;
                    boolean on = j.optBoolean("on");
                    if (on) padOn = true;
                    c.testPad(on, j.optBoolean("busy"), at -> {
                        try {
                            send(new JSONObject().put("t", "testpad").put("x", at[0]).put("y", at[1]).put("size", at[2]));
                        } catch (Exception ignored) {
                        }
                    }, held -> {
                        try {
                            send(new JSONObject().put("t", "padtouch").put("heldMs", held[0]).put("moves", held[1])
                                    .put("cancelled", held.length > 2 && held[2] != 0));
                        } catch (Exception ignored) {
                        }
                    });
                    break;
                }
                default:
            }
        }

        private final Object phoneLook = new Object();

        /** The phone's own state for the computer's window: sent when it changes. The input's thread and the lock watch's. */
        private void tellPhone(boolean now) {
            synchronized (phoneLook) {
                tellPhoneNow(now);
            }
        }

        private void tellPhoneNow(boolean now) {
            long t = SystemClock.elapsedRealtime();
            if (!now && t - phoneLookedAt < PHONE_STATE_MS) return;
            phoneLookedAt = t;
            boolean control = PhoneControlService.live() != null;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();
            boolean locked = km != null && km.isKeyguardLocked();
            // The phone's own screen (PhoneHelperLink): "none" without the helper, "on" or "off";
            // and whether this window still wants it off (the phone's power button takes that back).
            String panel = PhoneHelperLink.state();
            boolean panelWanted = PhoneHelperLink.wants(this);
            // With no helper: what stands between this phone and one it starts itself
            // (PhoneHelperStart: setup, starting, Developer options off, …), and why a start failed.
            String how = "none".equals(panel) ? PhoneHelperStart.state(ctx) : "";
            String howWhy = PhoneHelperStart.FAILED.equals(how) ? PhoneHelperStart.why() : "";
            String said = control + "/" + screenOn + "/" + locked + "/" + quality.id + "/" + panel + "/" + panelWanted
                    + "/" + how + "/" + howWhy;
            if (said.equals(phoneSaid)) return;
            phoneSaid = said;
            try {
                JSONObject j = new JSONObject().put("t", "phone").put("control", control).put("screenOn", screenOn)
                        .put("locked", locked).put("quality", quality.id)
                        .put("name", LinuxRemote.phoneName(ctx))
                        .put("panel", panel).put("panelWanted", panelWanted);
                if ("none".equals(panel)) {
                    j.put("panelStart", PhoneHelperLink.startCommand(ctx));
                    j.put("panelHow", how).put("panelWhy", howWhy).put("app", ctx.getString(R.string.ln_label));
                }
                send(j);
            } catch (Exception ignored) {
            }
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
            sendRaw(j.toString());
        }

        private void sendRaw(String text) throws IOException {
            LinuxStreamSocket s = ws;
            if (s == null || closing) throw new IOException("closed");
            s.sendText(text);
        }

        /**
         * A last word, then the end, as {@link LinuxStream}'s sessions end:
         * the word and the close frame together on a thread of their own, the
         * session's thread reading on (and dropping) until the far end closes
         * its end, which a window does the moment it reads the word, and a
         * deadline that closes the socket anyway. Closed with the far end's
         * acknowledgements still unread, the socket answers with a reset, and
         * a reset can take the word with it. Any thread; the caller never
         * waits on this session's link: it may be the session taking over.
         */
        void endWith(String state, String message) {
            if (closing) return;
            closing = true;
            stopPicture();
            stopSound();
            Thread lw = lockWatch;
            if (lw != null) lw.interrupt();
            String word;
            try {
                JSONObject j = new JSONObject().put("t", "state").put("state", state);
                if (message != null) j.put("message", message);
                word = j.toString();
            } catch (Exception e) {
                word = null;
            }
            DexLog.step("phone", "ending " + who + "'s session: " + state);
            Thread deadline = new Thread(() -> {
                try {
                    Thread.sleep(LinuxBrowserServer.LAST_WORD_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                closeAll();
            }, "phone-stream-last-word");
            deadline.setDaemon(true);
            deadline.start();
            LinuxStreamSocket s = ws;
            final String last = word;
            Thread bye = new Thread(() -> {
                if (s == null || !s.sayLast(last, 300)) closeAll();
            }, "phone-stream-goodbye");
            bye.setDaemon(true);
            bye.start();
        }

        /** End it now, from any thread, any number of times: every blocked read and write wakes. */
        private void closeAll() {
            if (!released.compareAndSet(false, true)) return;
            closing = true;
            LinuxStreamSocket s = ws;
            if (s != null) s.close();
            closeQuietly(sock);
        }

        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();

        private void stopPicture() {
            PhoneSource s = source;
            Feed f = feed;
            feed = null;
            source = null;
            if (s != null) s.stop(f);
        }

        private void finish() {
            closing = true;
            live = false;
            stopPicture();
            stopSound();
            Thread lw = lockWatch;
            if (lw != null) lw.interrupt();
            input.release();
            // The phone's own screen lit again, unless another computer's session wants it dark.
            PhoneHelperLink.session(ctx, this, false, false);
            PhoneControlService control = PhoneControlService.live();
            if (control != null) control.keepAwake(this, false);
            if (padOn && control != null) control.testPad(false, false, at -> {
            }, null);
            // Only the session that had the screen says nobody has it now: a
            // replaced one would say so over the one that replaced it.
            if (ended(this)) PhoneRemoteService.watching(ctx, null);
            closeAll();
            // Only a session that opened the screen closes it: a probe, a
            // refused hello, or a line dropped before its request never had it.
            if (opened) DexLog.step("phone", who + " closed the screen");
        }
    }
}
