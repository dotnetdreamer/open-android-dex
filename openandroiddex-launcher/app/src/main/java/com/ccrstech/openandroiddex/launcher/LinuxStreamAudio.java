package com.ccrstech.openandroiddex.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Linux's sound, for a browser watching the desktop ({@link LinuxStream}).
 *
 * <p>The same tap the phone's own speaker plays ({@link LinuxAudio}): the
 * guest's PulseAudio null sink, its monitor as raw s16le / 48 kHz / stereo on
 * 127.0.0.1:6081 ({@code /etc/pulse/dex.pa}). PulseAudio's simple protocol
 * gives every connection a stream of its own, so this reads beside the phone's
 * pump rather than taking from it; while a browser watches, the phone's pump
 * is held ({@link LinuxAudio#hold}) and only this one reads.
 *
 * <p><b>As the picture is: Low, Medium, High.</b> What it reads is shaped for
 * the browser's quality ({@link Format#forMode}). High is the tap as it is,
 * lossless, 1.5 Mb/s. Medium and Low are Opus where both ends have it (an
 * encoder on the phone, Android 10 on; WebCodecs' AudioDecoder in the page,
 * which browsers give only to https pages): 96 kb/s stereo and 32 kb/s mono.
 * Elsewhere they are PCM made smaller: 24 kHz stereo and 16 kHz mono, low-pass
 * filtered first so that nothing above the new rate's half folds back into
 * what is heard. A change of quality changes the sound at the next read, said
 * first ({@link Sink#format}).
 *
 * <p><b>In 10 ms pieces.</b> Each read takes at most 10 ms of the tap
 * ({@link #CHUNK}), so PCM goes to the browser 10 ms at a time and the page
 * can keep that little in hand. Opus stays in 20 ms packets: Android's
 * encoder makes no other size (C2SoftOpusEnc's frame is fixed at 20 ms).
 *
 * <p>Silence is not sent: an idle desktop's monitor is a steady 192 kB/s of
 * zeroes, so a run of them longer than {@link #SILENCE_HOLD_MS} stops the
 * sending until sound comes back. Gaps shorter than that inside a sound are
 * sent, so that nothing in it moves.
 *
 * <p>Pure Java but for the Opus encoder, which the phone supplies
 * ({@link #opus}): the tests on a computer run this very file.
 *
 * <p><b>Where it reads.</b> Linux's tap by default; Remote desktop's sound
 * reads the phone's own playback instead, through the same shaping, Opus and
 * silence rules ({@link Tap}, {@link PhoneSound}).
 */
final class LinuxStreamAudio {

    /** What the browser is told and sent. */
    interface Sink {
        /** The sound that follows: said before its first run, and again at every change. */
        void format(Format f) throws IOException;

        /** One run: {@code codec} {@link #PCM} (whole sample frames) or {@link #OPUS} (one packet). */
        void audio(int codec, byte[] data, int off, int len, long atUs) throws IOException;

        /** The reading stopped for good, not asked to: {@code why}, for the computer. */
        default void ended(String why) {
        }
    }

    /**
     * Where the sound is read: s16le, 48 kHz, stereo, as a stream that blocks
     * until there is some. Linux's tap is a loopback port that comes back
     * when the desktop does; the phone's own playback is opened once.
     */
    interface Tap {
        /** The sound, from now on. */
        InputStream open() throws IOException;

        /** Wake a read wherever it waits, and let go. Any thread, any number of times. */
        void close();

        /** Gone or never opened: try again a little later? */
        boolean retries();

        /** For the log. */
        String name();
    }

    /** Linux's tap: the guest's PulseAudio monitor on {@link #port}. */
    private static final class LinuxTap implements Tap {
        private volatile Socket sock;

        @Override
        public InputStream open() throws IOException {
            Socket s = new Socket();
            sock = s;
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                return s.getInputStream();
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            Socket s = sock;
            sock = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public boolean retries() {
            return true;
        }

        @Override
        public String name() {
            return "the tap on " + port;
        }
    }

    /** An encoder of whole sample frames at its format's rate and channels, into packets for the sink. */
    interface Encoder {
        void encode(byte[] pcm, int off, int len, long atUs) throws IOException;

        void close();
    }

    /** Makes the Opus encoder: the phone's MediaCodec ({@link LinuxStreamOpus}), or a test's. */
    interface OpusMaker {
        /** Null when there is none. */
        Encoder make(int channels, int bitrate, Sink out);
    }

    static final int PCM = 0;
    static final int OPUS = 1;

    /** The sound for one quality, as the browser can play it. */
    static final class Format {
        final String mode;
        final int codec;
        final int rate;
        final int channels;
        /** Opus's target; PCM's is its rate times its channels times 16. */
        final int bitrate;

        Format(String mode, int codec, int rate, int channels, int bitrate) {
            this.mode = mode;
            this.codec = codec;
            this.rate = rate;
            this.channels = channels;
            this.bitrate = codec == PCM ? rate * channels * 16 : bitrate;
        }

        /**
         * {@code mode} Low, Medium or High, as the picture's; {@code opus} when
         * the page can decode Opus and the phone can make it.
         */
        static Format forMode(String mode, boolean opus) {
            if ("high".equals(mode)) return new Format("high", PCM, RATE, 2, 0);
            if ("low".equals(mode)) return opus ? new Format("low", OPUS, RATE, 1, 32_000) : new Format("low", PCM, 16_000, 1, 0);
            return opus ? new Format("medium", OPUS, RATE, 2, 96_000) : new Format("medium", PCM, 24_000, 2, 0);
        }

        /** The same quality as PCM: what Opus falls back to when no encoder could be made. */
        Format asPcm() {
            return forMode(mode, false);
        }

        String json() {
            return "{\"t\":\"sound\",\"codec\":\"" + (codec == OPUS ? "opus" : "pcm") + "\",\"rate\":" + rate
                    + ",\"channels\":" + channels + ",\"kbps\":" + bitrate / 1000 + ",\"mode\":\"" + mode + "\"}";
        }

        boolean same(Format o) {
            return o != null && o.codec == codec && o.rate == rate && o.channels == channels && o.bitrate == bitrate;
        }

        @Override
        public String toString() {
            return (codec == OPUS ? "Opus " + bitrate / 1000 + " kb/s" : "PCM " + rate / 1000 + " kHz")
                    + (channels == 1 ? " mono" : " stereo");
        }
    }

    /** The phone's Opus encoder; null where there is none (Android 9 and before), and on a computer. */
    static volatile OpusMaker opus;

    /** The desktop's tap, as {@link LinuxAudio#DESKTOP}. A test points it elsewhere. */
    static volatile int port = 6081;

    static final int RATE = 48000;
    /** Bytes in one of the tap's sample frames: two channels of 16 bits. */
    static final int FRAME = 4;
    /** One read: at most 10 ms. The tap hands over what it has, often less. */
    static final int CHUNK = RATE / 100 * FRAME;
    /** Silence longer than this is not sent. */
    static final long SILENCE_HOLD_MS = 500;
    /** As {@link LinuxAudio}'s: the most unplayed backlog kept, ≈250 ms, held against its low point. */
    private static final int MAX_LAG = RATE / 4 * FRAME;
    private static final long LAG_WINDOW_MS = 2000;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    /** A desktop that is up has its tap: a few seconds of misses is a desktop going away. */
    private static final int CONNECT_TRIES = 10;
    private static final int RETRY_MS = 500;

    private final Sink sink;
    private final String who;
    private final Tap tap;
    private final String area;
    private volatile boolean live = true;
    private final Thread thread;
    /** What the browser's quality asks for now; applied at the next read. */
    private volatile Format wanted;

    /** Bytes handed to the sink, for the stream's stats and the tests. */
    volatile long sentBytes;

    /** Linux's sound, from its tap. */
    LinuxStreamAudio(Sink sink, String who, Format format) {
        this(sink, who, format, new LinuxTap(), "stream");
    }

    /** Sound from {@code tap}; {@code area} names it in the log. */
    LinuxStreamAudio(Sink sink, String who, Format format, Tap tap, String area) {
        this.sink = sink;
        this.who = who;
        this.wanted = format;
        this.tap = tap;
        this.area = area;
        this.thread = new Thread(this::run, area + "-stream-audio");
        thread.setDaemon(true);
    }

    LinuxStreamAudio start() {
        thread.start();
        return this;
    }

    /** Another quality: the sound changes at the next read. */
    void setFormat(Format f) {
        wanted = f;
    }

    /** Stop reading, and unblock the read wherever it waits: a closed tap is what does that. */
    void stop() {
        live = false;
        tap.close();
    }

    boolean running() {
        return live && thread.isAlive();
    }

    private void run() {
        int misses = 0;
        String why = null;
        while (live && misses < CONNECT_TRIES) {
            try {
                InputStream in = tap.open();
                if (!live) return;
                misses = 0;
                DexLog.step(area, "sound from " + tap.name() + " for " + who);
                pump(in);
                if (live) DexLog.step(area, "the sound from " + tap.name() + " ended");
                why = "the sound ended";
            } catch (IOException e) {
                if (!live) return;
                misses++;
                why = e.getMessage();
            } finally {
                tap.close();
            }
            if (!live) return;
            if (!tap.retries()) break;
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!live) return;
        DexLog.step(area, "no sound from " + tap.name() + (why != null ? " (" + why + ")" : "") + ": the computer gets none");
        sink.ended(why != null ? why : "no sound");
    }

    // ── the format in use, owned by the pump ──

    private Format current;
    /** The request {@link #current} answers (itself, or Opus it fell back from). */
    private Format applied;
    private Shaper shaper;
    private Encoder encoder;
    private byte[] shaped = new byte[0];

    /** The quality asked for, in place: the browser told first, an encoder made for Opus. */
    private void apply(Format f) throws IOException {
        closeEncoder();
        Format use = f;
        if (f.codec == OPUS) {
            OpusMaker m = opus;
            Encoder e = null;
            try {
                e = m == null ? null : m.make(f.channels, f.bitrate, sink);
            } catch (RuntimeException ex) {
                DexLog.warn("stream", "the Opus encoder would not start: " + ex);
            }
            if (e == null) {
                use = f.asPcm();
            } else {
                encoder = e;
            }
        }
        shaper = new Shaper(use.rate, use.channels);
        current = use;
        sink.format(use);
        DexLog.step("stream", "sound as " + use + " (" + use.mode + ")");
    }

    private void closeEncoder() {
        Encoder e = encoder;
        encoder = null;
        if (e != null) e.close();
    }

    /** Tap to sink, in whole sample frames, silence held back, until one end goes. */
    private void pump(InputStream in) throws IOException {
        byte[] buf = new byte[CHUNK + FRAME];
        int carry = 0; // bytes of a sample frame split by the last read, kept at the front
        long quietSince = -1; // when the current run of zeroes began; -1 while there is sound
        LinuxAudioLag lag = new LinuxAudioLag(MAX_LAG, CHUNK, LAG_WINDOW_MS);
        current = null;
        applied = null;
        try {
            while (live) {
                Format want = wanted;
                if (applied == null || !want.same(applied)) {
                    apply(want);
                    applied = want;
                }
                int skip = lag.skip(in.available(), System.nanoTime() / 1_000_000);
                if (skip > 0) {
                    // Whole sample frames, ending on a frame's boundary: a part
                    // frame kept from the last read goes with them.
                    int drop = skip - skip % FRAME;
                    if (carry > 0) {
                        drop += FRAME - carry;
                        carry = 0;
                    }
                    if (!discard(in, drop)) return;
                    DexLog.step("stream", "sound: dropped " + drop * 1000L / (RATE * FRAME) + " ms of backlog");
                }
                int n = in.read(buf, carry, CHUNK);
                if (n < 0) return;
                long atUs = System.nanoTime() / 1000;
                int have = carry + n;
                int whole = have - have % FRAME;
                if (whole > 0) {
                    long nowMs = atUs / 1000;
                    if (zero(buf, whole)) {
                        if (quietSince < 0) quietSince = nowMs;
                    } else {
                        quietSince = -1;
                    }
                    if (quietSince < 0 || nowMs - quietSince <= SILENCE_HOLD_MS) send(buf, whole, atUs);
                }
                carry = have - whole;
                if (carry > 0) System.arraycopy(buf, whole, buf, 0, carry);
            }
        } finally {
            closeEncoder();
        }
    }

    /** One run of the tap's frames, shaped and, for Opus, encoded. */
    private void send(byte[] tap, int len, long atUs) throws IOException {
        int frames = len / FRAME;
        int need = shaper.maxOut(frames);
        if (shaped.length < need) shaped = new byte[need];
        int out = shaper.shape(tap, frames, shaped);
        if (out == 0) return;
        if (encoder != null) {
            encoder.encode(shaped, 0, out, atUs);
        } else {
            sink.audio(PCM, shaped, 0, out, atUs);
        }
        sentBytes += out;
    }

    /** Exactly {@code n} bytes read and thrown away; false when the tap ended first. */
    private static boolean discard(InputStream in, int n) throws IOException {
        byte[] sink = new byte[Math.min(n, 64 * 1024)];
        while (n > 0) {
            int got = in.read(sink, 0, Math.min(n, sink.length));
            if (got < 0) return false;
            n -= got;
        }
        return true;
    }

    /** PulseAudio's null sink writes exact zeroes, so this is not a threshold. */
    private static boolean zero(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    /**
     * The tap's 48 kHz stereo made into {@code rate} and {@code channels}: two
     * channels averaged into one, then every second or third frame kept after
     * a low-pass filter (a 63-tap windowed sinc at 0.42 of the new rate, so
     * that what the new rate cannot carry does not fold back as noise). The
     * filter's history carries over from one run to the next. At 48 kHz
     * stereo it hands the frames over as they are.
     */
    static final class Shaper {
        static final int TAPS = 63;

        final int channels;
        final int step;
        private final float[] h;
        private final float[][] hist;
        private int pos;
        private int phase;

        Shaper(int rate, int channels) {
            if (RATE % rate != 0) throw new IllegalArgumentException("rate " + rate);
            this.channels = channels;
            this.step = RATE / rate;
            if (step == 1) {
                h = null;
                hist = null;
                return;
            }
            h = new float[TAPS];
            double fc = 0.42 * rate / RATE; // as a share of the tap's own rate
            double sum = 0;
            int mid = TAPS / 2;
            for (int i = 0; i < TAPS; i++) {
                int k = i - mid;
                double sinc = k == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * k) / (Math.PI * k);
                double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1)); // Hamming
                h[i] = (float) (sinc * w);
                sum += h[i];
            }
            for (int i = 0; i < TAPS; i++) h[i] /= (float) sum;
            hist = new float[channels][TAPS];
        }

        /** The most bytes {@link #shape} can make from {@code frames} of the tap's. */
        int maxOut(int frames) {
            return (frames / step + 1) * channels * 2;
        }

        /** {@code frames} of the tap's s16le stereo into {@code out}; returns the bytes written. */
        int shape(byte[] in, int frames, byte[] out) {
            int o = 0;
            for (int f = 0; f < frames; f++) {
                int i = f * FRAME;
                int l = (short) ((in[i] & 0xff) | (in[i + 1] << 8));
                int r = (short) ((in[i + 2] & 0xff) | (in[i + 3] << 8));
                if (step == 1) {
                    if (channels == 2) {
                        out[o++] = in[i];
                        out[o++] = in[i + 1];
                        out[o++] = in[i + 2];
                        out[o++] = in[i + 3];
                    } else {
                        int m = (l + r) >> 1;
                        out[o++] = (byte) m;
                        out[o++] = (byte) (m >> 8);
                    }
                    continue;
                }
                if (channels == 2) {
                    hist[0][pos] = l;
                    hist[1][pos] = r;
                } else {
                    hist[0][pos] = (l + r) * 0.5f;
                }
                pos = (pos + 1) % TAPS;
                if (++phase < step) continue;
                phase = 0;
                for (int c = 0; c < channels; c++) {
                    float acc = 0;
                    float[] x = hist[c];
                    // h is symmetric, so its order against the history does not matter.
                    for (int k = 0, j = pos; k < TAPS; k++) {
                        acc += h[k] * x[j];
                        if (++j == TAPS) j = 0;
                    }
                    int v = Math.round(acc);
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    out[o++] = (byte) v;
                    out[o++] = (byte) (v >> 8);
                }
            }
            return o;
        }
    }
}
