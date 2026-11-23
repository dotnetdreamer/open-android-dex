package com.ccrstech.openandroiddex.launcher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * A measuring aid for Remote desktop's sound, as the test pad is for its
 * picture ({@link PhoneControlService#padAllowed}: a mark a test leaves in
 * the app's files, there only while a test runs): a tone the phone plays as
 * media, so a test hears the phone's sound without opening anyone's music
 * app. {@code {"t":"testtone","hz":..,"ms":..}} on the stream.
 */
final class PhoneTestTone {

    private static final int RATE = 48000;
    /** The longest tone: ten seconds, under 2 MB of samples. */
    static final int MAX_MS = 10_000;

    private PhoneTestTone() {
    }

    static void play(int hz, int ms) {
        final int f = Math.max(50, Math.min(8000, hz));
        final int len = Math.max(50, Math.min(MAX_MS, ms));
        Thread t = new Thread(() -> {
            int frames = RATE * len / 1000;
            short[] pcm = new short[frames * 2];
            int ramp = RATE / 200; // 5 ms in and out: no click
            for (int i = 0; i < frames; i++) {
                double env = Math.min(1.0, Math.min(i, frames - 1 - i) / (double) ramp);
                short v = (short) Math.round(Math.sin(2 * Math.PI * f * i / RATE) * 0.35 * 32767 * env);
                pcm[2 * i] = v;
                pcm[2 * i + 1] = v;
            }
            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .build();
                track.write(pcm, 0, pcm.length);
                track.play();
                DexLog.step("phone", "test tone: " + f + " Hz for " + len + " ms");
                Thread.sleep(len + 150);
            } catch (Exception e) {
                DexLog.warn("phone", "no test tone", e);
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    track.release();
                }
            }
        }, "phone-test-tone");
        t.setDaemon(true);
        t.start();
    }
}
