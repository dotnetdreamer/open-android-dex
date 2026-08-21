package com.ccrstech.openandroiddex.launcher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The guest's speakers.
 *
 * There is no audio device inside the container. Android's {@code /dev} is
 * bound in, but its audio nodes belong to the media uid and an app may not open
 * them, so ALSA finds nothing at all — which is why the XFCE panel showed a
 * crossed-out speaker and offered a mixer that was not installed. What the
 * guest gets instead is a PulseAudio null sink and a raw PCM tap on its
 * monitor; this class is the other end of that tap. See {@code /etc/pulse/dex.pa}
 * (written by {@code linux-setup.sh}) and the pulseaudio launch in
 * {@code linux-rt.sh}.
 *
 * <p>proot is a ptrace chroot and creates no network namespace, so the guest's
 * {@code 127.0.0.1} is this app's own loopback — the same fact the viewer has
 * always stood on for websockify. The socket carries s16le / 48 kHz / stereo
 * with no header and no handshake: connect, read, write it into an
 * {@link AudioTrack}. Both ends are fixed at the rate AudioTrack takes natively,
 * so nothing resamples anywhere.
 *
 * <p>Lives in the {@code :linux} process with {@link LinuxService}, started and
 * stopped with the runtime, so the audio path cannot outlive the container that
 * feeds it.
 */
final class LinuxAudio {

    private LinuxAudio() {
    }

    /** The guest's {@code module-simple-protocol-tcp} tap, loopback only. */
    static final int PORT = 6081;

    private static final int RATE = 48000;

    /** One read/write, ≈21 ms of audio. */
    private static final int CHUNK = 4096;

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int RETRY_MS = 1000;

    /**
     * How many consecutive failed connects before the pump gives up.
     *
     * It exists because the runtime can end without anyone telling this class:
     * a guest-side logout takes the container down and leaves the service
     * standing, and a pump that retried forever would sit there waking the
     * process once a second for the rest of the session. The counter resets on
     * every successful connect, so a long session that loses its tap gets the
     * full budget back.
     */
    private static final int RETRY_BUDGET = 150;

    /**
     * How long the tap must be pure silence before the track is paused.
     *
     * The monitor of a null sink never stops — an idle desktop still produces
     * 192 kB/s of zeroes — so without this the phone would hold an audio output
     * open for every minute the window is open, playing nothing. Long enough
     * that the gaps inside a piece of audio never reach it.
     */
    private static final long SILENCE_HOLD_MS = 3000;

    /**
     * The most unplayed audio allowed to sit in the socket before it is thrown
     * away, ≈250 ms.
     *
     * The guest is a real-time producer with no flow control of any kind, and
     * the two clocks it runs between — the guest's system timer and the phone's
     * audio clock — are not the same clock. Both drift and a momentary stall
     * show up identically, as bytes piling up in the receive buffer, and every
     * one of those bytes is latency that can never be worked off again. Dropping
     * costs one audible click; carrying it costs a desktop whose sound runs
     * further behind its picture the longer it is used.
     */
    private static final int MAX_LAG = RATE / 4 * 4;

    /** The live pump, or null. Guards against two running at once. */
    private static Pump current;

    /**
     * Start draining the guest's tap, if nothing is draining it already.
     *
     * Safe to call before the container is up: the pump retries the connect
     * while the guest boots, which takes several seconds and is well inside
     * {@link #RETRY_BUDGET}.
     */
    static synchronized void start() {
        if (current != null) return;
        Pump p = new Pump();
        current = p;
        Thread t = new Thread(p, "linux-audio");
        // Daemon: the pump must never be the reason the :linux process stays
        // alive. The service's foreground notification is what does that.
        t.setDaemon(true);
        t.start();
    }

    /**
     * Stop, and unblock the pump wherever it is waiting.
     *
     * Closing the socket from here is the point: the pump spends its life
     * inside a blocking read, which an interrupt does not touch.
     */
    static synchronized void stop() {
        Pump p = current;
        current = null;
        if (p != null) p.cancel();
    }

    /**
     * One connection's worth of state, so a pump that has been cancelled can
     * never be confused with the one that replaced it. A static flag would let
     * a stale thread — still blocked in a read when the next runtime started —
     * come back to life and fight the new pump for the socket.
     */
    private static final class Pump implements Runnable {

        private volatile boolean live = true;
        private volatile Socket sock;

        void cancel() {
            live = false;
            close(sock);
        }

        @Override
        public void run() {
            int misses = 0;
            boolean announced = false;
            while (live && misses < RETRY_BUDGET) {
                Socket s = null;
                try {
                    s = new Socket();
                    sock = s;
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);
                    misses = 0;
                    announced = false;
                    DexLog.step("linux", "audio: playing the guest tap from " + PORT);
                    drain(s.getInputStream());
                    DexLog.step("linux", "audio: tap closed");
                } catch (Exception e) {
                    // Once per outage, not once per second: a container that is
                    // still booting refuses this connect every time round.
                    if (live && !announced) {
                        announced = true;
                        DexLog.step("linux", "audio: waiting for the guest tap (" + e + ")");
                    }
                } finally {
                    close(s);
                    sock = null;
                }
                if (!live) return;
                misses++;
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (live) {
                DexLog.step("linux", "audio: no tap after " + (RETRY_BUDGET * RETRY_MS / 1000)
                        + "s — giving up until the runtime restarts");
            }
        }

        /** Socket to speaker, until one end goes away. */
        private void drain(InputStream in) throws Exception {
            AudioTrack track = build();
            try {
                byte[] buf = new byte[CHUNK];
                boolean playing = false;
                long quietSince = 0;
                while (live) {
                    int lag = in.available();
                    if (lag > MAX_LAG) {
                        long dropped = in.skip(lag - CHUNK);
                        DexLog.step("linux", "audio: dropped " + (dropped * 1000 / (RATE * 4))
                                + "ms of backlog");
                    }
                    int n = in.read(buf);
                    if (n <= 0) return;               // the guest went away
                    if (!silent(buf, n)) {
                        quietSince = 0;
                        if (!playing) {
                            track.play();
                            playing = true;
                        }
                    } else if (playing) {
                        long now = SystemClock.uptimeMillis();
                        if (quietSince == 0) {
                            quietSince = now;
                        } else if (now - quietSince > SILENCE_HOLD_MS) {
                            // flush(), so the pause does not leave the tail of
                            // the last sound queued to play on the next one.
                            track.pause();
                            track.flush();
                            playing = false;
                            quietSince = 0;
                        }
                    }
                    // Silence while paused is simply discarded — reading it is
                    // what keeps the socket from backing up, and the read is
                    // paced by the guest, which produces in real time.
                    for (int off = 0; playing && off < n; ) {
                        int w = track.write(buf, off, n - off);
                        if (w < 0) throw new java.io.IOException("AudioTrack write " + w);
                        off += w;
                    }
                }
            } finally {
                try {
                    track.pause();
                    track.flush();
                } catch (Throwable ignored) {
                }
                track.release();
            }
        }
    }

    private static AudioTrack build() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        // Four chunks is the floor rather than the target: too small and the
        // track underruns on every scheduling hiccup, too large and every sound
        // in the guest starts late by the whole buffer.
        int size = Math.max(min > 0 ? min : 0, CHUNK * 4);

        AudioAttributes.Builder attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                // UNKNOWN is the honest answer, not a gap: the guest mixes a
                // video, a notification and a terminal bell into one stream, so
                // any specific content type would be a guess applied to all of
                // them.
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // How a DeX session hears any of this: the PC captures the phone's
            // playback (`--audio-source=playback --audio-dup`), and an app that
            // opts out of playback capture is simply silent over there. The
            // default already allows it; saying so keeps a later policy change
            // elsewhere in the app from taking the desktop's sound away.
            attrs.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
        }

        // No audio focus is requested, deliberately. The tap runs for the whole
        // life of the window and is silent most of it, so asking for focus would
        // pause the user's music the moment a Linux window opened.
        return new AudioTrack.Builder()
                .setAudioAttributes(attrs.build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** PulseAudio's null sink writes exact zeroes, so this is not a threshold. */
    private static boolean silent(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
