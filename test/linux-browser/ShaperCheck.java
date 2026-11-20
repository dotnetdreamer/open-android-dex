package com.ccrstech.openandroiddex.launcher;

/**
 * LinuxStreamAudio's Shaper on pure tones, for test/linux-browser-test.mjs: a
 * second of each at the tap's 48 kHz stereo, fed in 20 ms runs as the tap
 * gives them, and how loud it comes out at the smaller rates. A tone the new
 * rate can carry should come through whole; one above half the new rate
 * should be gone, not folded back as another.
 *
 * <p>Prints {@code SHAPE <rate> <channels> <tone Hz> <frames out> <out RMS / in RMS>}.
 */
public final class ShaperCheck {

    public static void main(String[] args) {
        int[][] cases = {
            {16000, 1, 440}, {16000, 1, 3000}, {16000, 1, 12000},
            {24000, 2, 440}, {24000, 2, 5000}, {24000, 2, 15000},
            {48000, 2, 440}, {48000, 1, 440},
        };
        for (int[] c : cases) {
            int rate = c[0];
            int channels = c[1];
            int tone = c[2];
            int frames = 48000;
            byte[] in = new byte[frames * 4];
            double inSq = 0;
            for (int i = 0; i < frames; i++) {
                short v = (short) Math.round(Math.sin(2 * Math.PI * tone * i / 48000.0) * 10000);
                in[i * 4] = in[i * 4 + 2] = (byte) v;
                in[i * 4 + 1] = in[i * 4 + 3] = (byte) (v >> 8);
                inSq += (double) v * v;
            }
            double inRms = Math.sqrt(inSq / frames);
            LinuxStreamAudio.Shaper s = new LinuxStreamAudio.Shaper(rate, channels);
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            int run = 960;
            byte[] piece = new byte[run * 4];
            byte[] out = new byte[s.maxOut(run)];
            for (int f = 0; f < frames; f += run) {
                int n = Math.min(run, frames - f);
                System.arraycopy(in, f * 4, piece, 0, n * 4);
                int got = s.shape(piece, n, out);
                all.write(out, 0, got);
            }
            byte[] o = all.toByteArray();
            int samples = o.length / 2;
            int outFrames = samples / channels;
            // The filter's first 63 taps are its warm-up, from silence: left out.
            double outSq = 0;
            int counted = 0;
            for (int i = 64 * channels; i < samples; i++) {
                short v = (short) ((o[i * 2] & 0xff) | (o[i * 2 + 1] << 8));
                outSq += (double) v * v;
                counted++;
            }
            double ratio = Math.sqrt(outSq / Math.max(1, counted)) / inRms;
            System.out.printf(java.util.Locale.ROOT, "SHAPE %d %d %d %d %.4f%n", rate, channels, tone, outFrames, ratio);
        }
    }
}
