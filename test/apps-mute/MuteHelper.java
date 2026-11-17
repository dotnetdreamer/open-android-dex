package com.ccrstech.openandroiddex.wmd;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's side of test/apps-mute-test.mjs, run under app_process as the
 * shell, as the app server is — in the window daemon's package, for its
 * PhoneContext and AppAudio:
 *
 * <pre>
 *   state          the music stream's mute and volume, and who sees a capture of the phone's output
 *   capture MS     the phone's output captured for MS ms, as scrcpy's {@code output} source captures
 *                  it (an AudioRecord on REMOTE_SUBMIX), saying what can see it meanwhile
 *   capture-mute 0 the same, with a quiet tone played into it and the stream muted while it runs:
 *                  the capture's loudest sample steady before, 100 ms at a time after, and steady after
 *   unmute, mute   the music stream unmuted, or muted: the runner's putting it back as it was
 * </pre>
 */
public final class MuteHelper {

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        AudioManager am = (AudioManager) PhoneContext.get().getSystemService(Context.AUDIO_SERVICE);
        String what = args.length > 0 ? args[0] : "state";
        try {
            switch (what) {
                case "capture":
                    capture(am, Long.parseLong(args[1]), false);
                    break;
                case "capture-mute":
                    capture(am, Long.parseLong(args[1]), true);
                    break;
                case "unmute":
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                    state(am, "after-unmute");
                    break;
                case "mute":
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                    state(am, "after-mute");
                    break;
                default:
                    state(am, "now");
            }
        } catch (Throwable t) {
            System.out.println("ERROR " + t);
        }
        System.out.flush();
        System.exit(0);
    }

    /** One line: {@code STATE <tag> muted=… volume=… submix=… listed=…}. */
    private static void state(AudioManager am, String tag) {
        System.out.println("STATE " + tag
                + " muted=" + am.isStreamMute(AudioManager.STREAM_MUSIC)
                + " volume=" + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                + " submix=" + AppAudio.submixActive()
                + " listed=" + listed(am));
        System.out.flush();
    }

    /** The sources of the recordings AudioManager lists, comma-separated, or "-". */
    private static String listed(AudioManager am) {
        List<String> sources = new ArrayList<>();
        try {
            for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
                sources.add(String.valueOf(c.getClientAudioSource()));
            }
        } catch (Throwable t) {
            return "error";
        }
        return sources.isEmpty() ? "-" : String.join(",", sources);
    }

    /**
     * As scrcpy's AudioDirectCapture records its {@code output} source; with
     * {@code mute}, the music stream muted while it runs (and unmuted before
     * it stops), and the captured sound's loudest sample said before and after.
     */
    private static void capture(AudioManager am, long ms, boolean mute) {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        AudioRecord.Builder b = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= 31) b.setContext(PhoneContext.get());
        b.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
        b.setAudioFormat(format);
        int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min > 0) b.setBufferSizeInBytes(8 * min);
        AudioRecord record = b.build();
        state(am, "before-capture");
        long t0 = SystemClock.uptimeMillis();
        record.startRecording();
        System.out.println("CAPTURE started " + (SystemClock.uptimeMillis() - t0) + " ms");
        state(am, "capturing");
        byte[] buf = new byte[Math.max(min, 4096)];
        // A quiet tone through media, only once media goes to the capture: it is captured, not heard.
        Tone tone = mute && Boolean.TRUE.equals(AppAudio.submixActive()) ? Tone.start() : null;
        if (!mute) {
            System.out.println("PEAK unmuted " + peak(record, buf, ms) + " (no tone)");
        } else {
            // Steady states, not what is still on its way: what the tone gave before the mute
            // reached the capture is read and thrown away first.
            peak(record, buf, 600);
            System.out.println("PEAK steady-unmuted " + peak(record, buf, 600) + (tone == null ? " (no tone)" : " (tone)"));
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            state(am, "capturing-muted");
            // How the capture goes after the mute, 100 ms at a time.
            StringBuilder after = new StringBuilder();
            for (int i = 0; i < 20; i++) after.append(i == 0 ? "" : " ").append(peak(record, buf, 100));
            System.out.println("PEAKS after-mute-100ms " + after);
            System.out.println("PEAK steady-muted " + peak(record, buf, 800) + (tone == null ? " (no tone)" : " (tone)"));
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            peak(record, buf, 600);
            System.out.println("PEAK steady-unmuted-again " + peak(record, buf, 600) + (tone == null ? " (no tone)" : " (tone)"));
        }
        if (tone != null) tone.stop();
        SystemClock.sleep(200);
        state(am, "capturing-end");
        record.stop();
        record.release();
        System.out.println("CAPTURE stopped");
        SystemClock.sleep(150);
        state(am, "after-capture");
    }

    /** A 1 kHz tone at an eighth of full scale, through media, until stopped. */
    private static final class Tone {
        private final android.media.AudioTrack track;
        private volatile boolean on = true;

        private Tone(android.media.AudioTrack track) {
            this.track = track;
        }

        static Tone start() {
            int rate = 48000;
            android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build();
            Tone tone = new Tone(t);
            short[] wave = new short[rate / 10];
            for (int i = 0; i < wave.length; i++) wave[i] = (short) (4096 * Math.sin(2 * Math.PI * 1000 * i / rate));
            t.play();
            Thread w = new Thread(() -> {
                while (tone.on) t.write(wave, 0, wave.length);
            }, "tone");
            w.setDaemon(true);
            w.start();
            return tone;
        }

        void stop() {
            on = false;
            SystemClock.sleep(150);
            track.pause();
            track.flush();
            track.release();
        }
    }

    /** The loudest 16-bit sample read in {@code ms}: 0 for silence (or nothing playing). */
    private static int peak(AudioRecord record, byte[] buf, long ms) {
        int peak = 0;
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            int n = record.read(buf, 0, buf.length);
            if (n < 0) break;
            for (int i = 0; i + 1 < n; i += 2) {
                int s = Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)));
                if (s > peak) peak = s;
            }
        }
        return peak;
    }
}
