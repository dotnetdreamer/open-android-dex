package com.ccrstech.openandroiddex.launcher;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The desktop, as H.264 out of the phone's hardware encoder, for
 * {@link LinuxStream}.
 *
 * <p>Xvnc's rectangles go into one GL texture the size of the desktop, and
 * each finished update is one draw of that texture into the encoder's input
 * surface: the GPU does the RGB-to-YUV, the encoder block does the rest, and
 * the CPU — already busy drawing the desktop for Xvnc — does neither. This is
 * the same pipe scrcpy uses for the phone's own screen, fed from a texture
 * instead of the compositor.
 *
 * <p>Tuned for latency over everything: no B-frames, one frame in flight in
 * the encoder ({@code KEY_LATENCY}), realtime priority, parameter sets on
 * every key frame so a decoder can join at any one, and a key frame only when
 * asked for. A desktop that stops changing is sent once more after a moment
 * ({@code KEY_REPEAT_PREVIOUS_FRAME_AFTER}) so the last picture sharpens, and
 * then not at all.
 *
 * <p>Created, drawn into and released on one thread — the one that owns the
 * GL context. The encoded output is drained on a thread of its own and handed
 * to {@link Output}, which may block: that is how a slow link slows the
 * encoder down rather than piling frames up.
 *
 * <p>The choice of encoder and its configuration ({@link #candidates},
 * {@link #fit}, {@link #configure}) are public: the app windows on the computer
 * (openandroiddex-wmd, AppVideo) feed a hardware encoder the same way, from a
 * virtual display instead of a texture of Xvnc's pixels, and the window
 * daemon's dex compiles this file for them.
 */
public final class LinuxStreamEncoder {

    public static final int FLAG_KEY = 1;
    public static final int FLAG_CONFIG = 2;

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    /** EGL_RECORDABLE_ANDROID: a config the encoder's surface will take. */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    /** Seconds between key frames nobody asked for — the stream asks when it needs one. */
    private static final int KEY_FRAME_EVERY_S = 10;
    /** How long a desktop must stand still before its last picture goes again, sharper. */
    private static final long REPEAT_AFTER_US = 100_000;

    /** Where the encoded units go, on the drain thread. */
    public interface Output {
        void packet(byte[] data, int len, long ptsUs, int flags) throws IOException;

        /** The encoder stopped by itself. */
        void stopped(Throwable why);
    }

    final String codecName;
    final boolean hardware;
    /** The coded picture. */
    final int encWidth;
    final int encHeight;
    /** The desktop. */
    final int fbWidth;
    final int fbHeight;
    /** The desktop was larger than the encoder takes, and is drawn smaller. */
    final boolean scaled;
    final int bitrate;
    final int fps;

    private final MediaCodec codec;
    private final Surface input;
    private final Output out;
    private final Thread drain;
    private volatile boolean running = true;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private int aPos;
    private int aTex;
    private final FloatBuffer quad;
    /** How long the last frames' swaps took, decayed — a GPU or encoder that pushes back shows here. */
    private volatile double swapMsAvg;
    /** Which configuration the encoder took: 0 everything, 1 the plain one. */
    int configAttempt;

    private LinuxStreamEncoder(MediaCodec codec, String name, boolean hardware, int encW, int encH,
                               int fbW, int fbH, boolean scaled, int bitrate, int fps, Output out) {
        this.codec = codec;
        this.codecName = name;
        this.hardware = hardware;
        this.encWidth = encW;
        this.encHeight = encH;
        this.fbWidth = fbW;
        this.fbHeight = fbH;
        this.scaled = scaled;
        this.bitrate = bitrate;
        this.fps = fps;
        this.out = out;
        this.input = codec.createInputSurface();
        float[] v = {
                // x, y, s, t — the texture's first row is the desktop's top
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
        drain = new Thread(this::drainLoop, "linux-stream-out");
    }

    /**
     * An encoder for a {@code fbW}×{@code fbH} desktop, on the calling thread's
     * new GL context. {@code swizzle} is how the uploaded bytes read back as
     * red, green, blue (see {@link LinuxStreamRfb#swizzle}).
     */
    static LinuxStreamEncoder create(int fbW, int fbH, int bitrate, int fps, String swizzle, Output out)
            throws IOException {
        List<String> tried = new ArrayList<>();
        for (MediaCodecInfo info : candidates()) {
            MediaCodecInfo.VideoCapabilities vc;
            try {
                vc = info.getCapabilitiesForType(MIME).getVideoCapabilities();
            } catch (Exception e) {
                continue;
            }
            int[] size = fit(vc, fbW, fbH, fps);
            if (size == null) {
                tried.add(info.getName() + " (no size)");
                continue;
            }
            boolean scaled = size[0] < fbW || size[1] < fbH;
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createByCodecName(info.getName());
                int attempt = configure(codec, info, size[0], size[1], bitrate, fps);
                LinuxStreamEncoder enc = new LinuxStreamEncoder(codec, info.getName(), isHardware(info),
                        size[0], size[1], fbW, fbH, scaled, bitrate, fps, out);
                enc.configAttempt = attempt;
                try {
                    enc.setUpGl(swizzle);
                } catch (RuntimeException e) {
                    enc.release();
                    throw new IOException("GL: " + e.getMessage(), e);
                }
                try {
                    codec.start();
                } catch (RuntimeException e) {
                    // released with its GL context; the next encoder gets a fresh one
                    enc.release();
                    codec = null;
                    throw e;
                }
                enc.drain.start();
                return enc;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                tried.add(info.getName() + " (" + e.getClass().getSimpleName() + ")");
                if (codec != null) {
                    try {
                        codec.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new IOException("no H.264 encoder takes " + fbW + "x" + fbH + ": " + tried);
    }

    /** Encoders for H.264 that take a surface, hardware first. */
    public static List<MediaCodecInfo> candidates() {
        List<MediaCodecInfo> hw = new ArrayList<>(), sw = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean avc = false;
            for (String t : info.getSupportedTypes()) avc |= MIME.equalsIgnoreCase(t);
            if (!avc) continue;
            boolean surface = false;
            try {
                for (int c : info.getCapabilitiesForType(MIME).colorFormats) {
                    surface |= c == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                }
            } catch (Exception e) {
                continue;
            }
            if (!surface) continue;
            (isHardware(info) ? hw : sw).add(info);
        }
        hw.addAll(sw);
        return hw;
    }

    public static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String n = info.getName().toLowerCase(java.util.Locale.ROOT);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."));
    }

    /**
     * The coded size for a desktop of {@code w}×{@code h}: its own, padded up
     * to the encoder's alignment, if the encoder takes that at {@code fps};
     * otherwise the largest step down that it does. Null if none.
     */
    public static int[] fit(MediaCodecInfo.VideoCapabilities vc, int w, int h, int fps) {
        int wa = Math.max(2, vc.getWidthAlignment());
        int ha = Math.max(2, vc.getHeightAlignment());
        double[] steps = {1.0, 0.875, 0.75, 2.0 / 3.0, 0.5};
        for (int pass = 0; pass < 2; pass++) {
            for (double s : steps) {
                int ew = s == 1.0 ? alignUp(w, wa) : alignDown((int) (w * s), wa);
                int eh = s == 1.0 ? alignUp(h, ha) : alignDown((int) (h * s), ha);
                if (ew <= 0 || eh <= 0) continue;
                boolean ok = pass == 0 ? vc.areSizeAndRateSupported(ew, eh, fps) : vc.isSizeSupported(ew, eh);
                if (ok) return new int[]{ew, eh};
            }
        }
        return null;
    }

    private static int alignUp(int v, int a) {
        return (v + a - 1) / a * a;
    }

    private static int alignDown(int v, int a) {
        return v / a * a;
    }

    /** @return which attempt took: 0 with every low-latency key, 1 the plain one */
    public static int configure(MediaCodec codec, MediaCodecInfo info, int w, int h, int bitrate, int fps) {
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
        // Everything that makes it faster first; if the encoder will not take
        // the lot, again with only what every encoder takes.
        for (int attempt = 0; attempt < 2; attempt++) {
            MediaFormat f = MediaFormat.createVideoFormat(MIME, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_EVERY_S);
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US);
            if (attempt == 0) {
                MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
                if (ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                }
                if (supportsProfile(caps, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)) {
                    f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                    f.setInteger(MediaFormat.KEY_LEVEL, levelFor(w, h, fps));
                }
                f.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
                if (Build.VERSION.SDK_INT >= 29) {
                    f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
                }
                if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1);
                String n = info.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.contains("qti") || n.contains("qcom")) {
                    // Qualcomm's own low-latency switch; unknown keys are ignored elsewhere.
                    f.setInteger("vendor.qti-ext-enc-low-latency.enable", 1);
                }
            }
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                return attempt;
            } catch (RuntimeException e) {
                if (attempt == 1) throw e;
                DexLog.warn("stream", info.getName() + " refused the low-latency configuration: " + e);
                codec.reset();
            }
        }
        return 1;
    }

    private static boolean supportsProfile(MediaCodecInfo.CodecCapabilities caps, int profile) {
        if (caps.profileLevels == null) return false;
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
            if (pl.profile == profile) return true;
        }
        return false;
    }

    /** The lowest H.264 level that holds this many macroblocks, per frame and per second. */
    public static int levelFor(int w, int h, int fps) {
        long mbs = (long) ((w + 15) / 16) * ((h + 15) / 16);
        long rate = mbs * fps;
        if (mbs <= 8192 && rate <= 245760) return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
        if (mbs <= 8704 && rate <= 522240) return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
        if (mbs <= 22080 && rate <= 983040) return MediaCodecInfo.CodecProfileLevel.AVCLevel51;
        if (mbs <= 36864 && rate <= 2073600) return MediaCodecInfo.CodecProfileLevel.AVCLevel52;
        return MediaCodecInfo.CodecProfileLevel.AVCLevel62;
    }

    // ── GL ──

    private void setUpGl(String swizzle) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new RuntimeException("eglInitialize");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new RuntimeException("no recordable EGL config");
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
        surface = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[]{EGL14.EGL_NONE}, 0);
        if (surface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface");
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new RuntimeException("eglMakeCurrent");
        // Swap whenever asked, never held for a vsync: the encoder is the only consumer.
        EGL14.eglSwapInterval(display, 0);

        String vs = "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;\n"
                + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }\n";
        // highp: at mediump a 3840-texel row cannot be addressed exactly.
        String fs = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                + "varying vec2 vTex; uniform sampler2D uTex;\n"
                + "void main() { gl_FragColor = vec4(texture2D(uTex, vTex)." + swizzle + ", 1.0); }\n";
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        int filter = scaled ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fbWidth, fbHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) throw new RuntimeException("GL error " + err + " making a " + fbWidth + "x" + fbHeight + " texture");
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("shader: " + log);
        }
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("program: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    /** Raw pixels into the desktop's texture. GL thread. */
    void upload(int x, int y, int w, int h, ByteBuffer pixels) {
        if (w <= 0 || h <= 0 || x + w > fbWidth || y + h > fbHeight) return;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, w, h,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
    }

    /** One frame of whatever the texture holds now, to the encoder. GL thread. */
    void frame() {
        GLES20.glViewport(0, 0, encWidth, encHeight);
        if (!scaled && (encWidth != fbWidth || encHeight != fbHeight)) {
            // The alignment's padding, kept black; the computer crops it off.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(0, encHeight - fbHeight, fbWidth, fbHeight);
        }
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        long t0 = System.nanoTime();
        EGLExt.eglPresentationTimeANDROID(display, surface, t0);
        EGL14.eglSwapBuffers(display, surface);
        swapMsAvg = swapMsAvg * 0.9 + (System.nanoTime() - t0) / 1e6 * 0.1;
    }

    double swapMs() {
        return Math.round(swapMsAvg * 10) / 10.0;
    }

    /** Encode at {@code bps} from the next frames on — the link's bitrate, adapted. Any thread. */
    void setBitrate(int bps) {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
            codec.setParameters(b);
        } catch (Exception ignored) {
            // stopping
        }
    }

    /** A key frame next, for a decoder that has to start over. Any thread. */
    void requestKeyFrame() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {
            // stopping
        }
    }

    // ── output ──

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[256 * 1024];
        try {
            while (running) {
                int idx = codec.dequeueOutputBuffer(info, 50_000);
                if (idx < 0) continue; // timeout, format or buffers changed: nothing to send
                try {
                    if (info.size > 0) {
                        ByteBuffer b = codec.getOutputBuffer(idx);
                        if (b != null) {
                            if (buf.length < info.size) buf = new byte[Math.max(info.size, buf.length * 2)];
                            b.position(info.offset);
                            b.limit(info.offset + info.size);
                            b.get(buf, 0, info.size);
                            int flags = 0;
                            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags |= FLAG_KEY;
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags |= FLAG_CONFIG;
                            out.packet(buf, info.size, info.presentationTimeUs, flags);
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(idx, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Throwable t) {
            if (running) out.stopped(t);
        }
    }

    /** Stop and let go of everything. On the GL thread; safe to call twice. */
    void release() {
        running = false;
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            drain.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            display = EGL14.EGL_NO_DISPLAY;
            surface = EGL14.EGL_NO_SURFACE;
            context = EGL14.EGL_NO_CONTEXT;
        }
        input.release();
    }
}
