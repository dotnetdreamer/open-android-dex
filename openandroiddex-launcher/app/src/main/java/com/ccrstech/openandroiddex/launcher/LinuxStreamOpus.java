package com.ccrstech.openandroiddex.launcher;

import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Opus from the phone's own encoder, for a browser's Low and Medium sound
 * ({@link LinuxStreamAudio}): Android's software Opus encoder, there from
 * Android 10 on (c2.android.opus.encoder). Before that there is none, and the
 * sound stays PCM.
 *
 * <p>Fed and emptied on the sound's own thread, in step: each run of sound
 * goes in, and whatever packets are ready come out to the browser, one Opus
 * packet each (20 ms). The codec's own header, which a file would keep, is
 * not sent: a browser's decoder needs only the rate and the channels, which
 * the "sound" message says.
 */
final class LinuxStreamOpus implements LinuxStreamAudio.Encoder {

    static final LinuxStreamAudio.OpusMaker MAKER = LinuxStreamOpus::make;

    private static final int RATE = 48000;

    private final MediaCodec codec;
    private final LinuxStreamAudio.Sink out;
    private final int frameBytes;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] packet = new byte[1500];

    private LinuxStreamOpus(MediaCodec codec, int channels, LinuxStreamAudio.Sink out) {
        this.codec = codec;
        this.out = out;
        this.frameBytes = channels * 2;
    }

    /** An encoder for {@code channels} at {@code bitrate}, or null where the phone has none. */
    static LinuxStreamAudio.Encoder make(int channels, int bitrate, LinuxStreamAudio.Sink out) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, channels);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
        if (name == null) return null;
        MediaCodec c = null;
        try {
            c = MediaCodec.createByCodecName(name);
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            DexLog.step("stream", "sound encoded on " + name);
            return new LinuxStreamOpus(c, channels, out);
        } catch (IOException | RuntimeException e) {
            DexLog.warn("stream", "no Opus encoder after all (" + name + "): " + e);
            if (c != null) c.release();
            return null;
        }
    }

    @Override
    public void encode(byte[] pcm, int off, int len, long atUs) throws IOException {
        int done = 0;
        try {
            while (done < len) {
                int i = codec.dequeueInputBuffer(10_000);
                if (i < 0) {
                    // Nothing free to fill: the encoder's output is what frees it.
                    drain();
                    continue;
                }
                ByteBuffer b = codec.getInputBuffer(i);
                b.clear();
                int n = Math.min(b.remaining(), len - done);
                n -= n % frameBytes;
                b.put(pcm, off + done, n);
                long pts = atUs + (long) (done / frameBytes) * 1_000_000L / RATE;
                codec.queueInputBuffer(i, 0, n, pts, 0);
                done += n;
            }
            drain();
        } catch (IllegalStateException e) {
            throw new IOException("the Opus encoder stopped: " + e);
        }
    }

    /** Every packet ready now, to the browser; the codec's own header left out. */
    private void drain() throws IOException {
        while (true) {
            int o = codec.dequeueOutputBuffer(info, 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (o < 0) continue; // the output format, said once
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    ByteBuffer b = codec.getOutputBuffer(o);
                    if (packet.length < info.size) packet = new byte[info.size];
                    b.position(info.offset);
                    b.get(packet, 0, info.size);
                    out.audio(LinuxStreamAudio.OPUS, packet, 0, info.size, info.presentationTimeUs);
                }
            } finally {
                codec.releaseOutputBuffer(o, false);
            }
        }
    }

    @Override
    public void close() {
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        codec.release();
    }
}
