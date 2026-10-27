package com.ccrstech.openandroiddex.wmd;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.ccrstech.openandroiddex.launcher.LinuxStreamEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One app window's display as H.264, at the pace the computer's link takes it.
 *
 * <p>The display draws into a texture of ours (a SurfaceTexture), not straight
 * into the encoder, and a frame goes on into the encoder — one GPU draw — only
 * when the encoder and the link have room for it ({@link Gate}). Every frame is
 * still taken off the display as it comes, so the display never waits on us;
 * the ones that cannot go yet are replaced by the next, and the one that goes
 * is always the newest. A slow link costs frames, never latency. The texture
 * also outlives every encoder: a window resized gets a new encoder at the new
 * size while the display keeps drawing into the same texture.
 *
 * <p>This is the Android desktop's own stream as it was built and measured
 * when the desktop had a window of this app's own (12 ms from the phone's
 * compositor to a decoded frame on a cable), one per window
 * (doc/apps-on-pc.md, "The pace").
 *
 * <p>On a cable the pace is not needed — the link never falls behind — and
 * the texture's draw is not free: one GPU copy per frame, and the driver's CPU
 * work around it, which on a Galaxy A13 made an idle window cost the phone
 * twice what scrcpy's does (6.8 % of a core against 3.3 %). There the window
 * asks for {@link #direct}: the display draws straight into the encoder's
 * input surface, as scrcpy's does, and nothing of this class's GL exists.
 *
 * <p>Everything GL — the context, the texture, each encoder's input surface
 * and the draws — lives on this window's own thread.
 * Each encoder's output is drained on a thread of its own and handed to
 * {@link LinuxStreamEncoder.Output}, which may block: that is how a slow link
 * slows the encoder down rather than piling frames up.
 */
final class AppVideo {

    /** Whether the frame waiting may go into the encoder now. */
    interface Gate {
        /**
         * 0: it may, and is counted as gone. Otherwise the milliseconds after
         * which to ask again — sooner if {@link #poke} says something changed.
         */
        long mayDraw();
    }

    /** One encoder, as the computer is told of it. */
    static final class Encoder {
        final String name;
        final boolean hardware;
        /** The coded picture: the display's size padded to the encoder's alignment, or a step smaller. */
        final int width;
        final int height;
        /** The display it was made for. */
        final int srcWidth;
        final int srcHeight;
        /** The display was larger than the encoder takes, and is drawn smaller. */
        final boolean scaled;
        final int bitrate;
        final int fps;
        final boolean lowLatency;

        Encoder(String name, boolean hardware, int width, int height, int srcWidth, int srcHeight,
                boolean scaled, int bitrate, int fps, boolean lowLatency) {
            this.name = name;
            this.hardware = hardware;
            this.width = width;
            this.height = height;
            this.srcWidth = srcWidth;
            this.srcHeight = srcHeight;
            this.scaled = scaled;
            this.bitrate = bitrate;
            this.fps = fps;
            this.lowLatency = lowLatency;
        }
    }

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    /** EGL_RECORDABLE_ANDROID: a config an encoder's input surface takes. */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final HandlerThread thread;
    private final Handler gl;
    /** The display draws into the encoder itself: no texture, no pace (a cable). */
    final boolean direct;

    // ── the GL thread's ──
    private EGLDisplay egl = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    /** What is current while no encoder is: a 1x1 pbuffer, or none where the context needs none. */
    private EGLSurface idle = EGL14.EGL_NO_SURFACE;
    private int texture;
    private int program;
    private int aPos;
    private int aTex;
    private int uMatrix;
    private final FloatBuffer quad;
    private SurfaceTexture frames;
    private Surface sink;
    private final float[] matrix = new float[16];
    private int srcW;
    private int srcH;
    private Codec codec;
    private Gate gate;
    /** A frame has come off the display that the encoder has not had. */
    private boolean pending;
    /** Some frame has come off the display at all: until one has, there is nothing to send. */
    private boolean anyFrame;
    /** When the display composed it, CLOCK_MONOTONIC — also the stream's timestamp for it. */
    private long frameNs;
    private long lastPtsNs;
    private boolean checkQueued;
    /** How long the last frames' swaps took, decayed: an encoder or GPU that pushes back shows here. */
    private volatile double swapMsAvg;
    /** Frames the display composed, for the stats. */
    private volatile long composed;

    private AppVideo(String name, int w, int h, boolean direct) {
        this.direct = direct;
        srcW = w;
        srcH = h;
        float[] v = {
                // x, y, s, t: the whole picture, the texture's own transform doing the rest
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
        thread = new HandlerThread("app-gl-" + name);
        thread.start();
        gl = new Handler(thread.getLooper());
    }

    /** The texture a display of {@code w}x{@code h} draws into, ready. */
    static AppVideo open(String name, int w, int h) throws IOException {
        return open(name, w, h, false);
    }

    /** As {@link #open(String, int, int)}, or, {@code direct}, with nothing between the display and each encoder. */
    static AppVideo open(String name, int w, int h, boolean direct) throws IOException {
        AppVideo v = new AppVideo(name, w, h, direct);
        if (!direct) {
            v.call(() -> {
                v.setUp();
                return null;
            });
        }
        return v;
    }

    /** What the display is given to draw into: the texture's surface, or — direct — the encoder's own, once there is one. */
    Surface sink() {
        if (!direct) return sink;
        Codec c = codec;
        return c == null ? null : c.input;
    }

    /** The display is about to be {@code w}x{@code h}. */
    void resize(int w, int h) throws IOException {
        call(() -> {
            srcW = w;
            srcH = h;
            if (frames != null) frames.setDefaultBufferSize(w, h);
            return null;
        });
    }

    /**
     * An encoder for the display's size now, feeding {@code out} at the pace
     * {@code gate} sets — replacing the one before, if any. The picture already
     * on the display goes in at once, so the computer is not left waiting for
     * something in the app to move.
     */
    Encoder start(int bitrate, int fps, Gate gate, LinuxStreamEncoder.Output out) throws IOException {
        return call(() -> {
            release();
            Codec c = direct ? newDirectCodec(bitrate, fps, out) : newCodec(bitrate, fps, out);
            codec = c;
            this.gate = gate;
            pending = anyFrame;
            tryDraw();
            return c.info;
        });
    }

    /** Everything goes: the encoder, the texture, the thread. Safe to call twice. */
    void close() {
        try {
            call(() -> {
                release();
                gate = null;
                if (frames != null) {
                    frames.release();
                    frames = null;
                }
                if (sink != null) {
                    sink.release();
                    sink = null;
                }
                if (egl != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, idle);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, context);
                    EGL14.eglReleaseThread();
                    egl = EGL14.EGL_NO_DISPLAY;
                }
                return null;
            });
        } catch (IOException ignored) {
            // the thread is gone already
        }
        thread.quitSafely();
    }

    /** Something the gate depends on changed — a frame left the encoder, or arrived at the computer. Any thread. */
    void poke() {
        gl.post(this::tryDraw);
    }

    /** A key frame next, for a decoder that has to start over. Any thread. */
    void requestKeyFrame() {
        Codec c = codec;
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.media.setParameters(b);
        } catch (Exception ignored) {
            // stopping
        }
    }

    /** Encode at {@code bps} from the next frames on — the link's bitrate, adapted. Any thread. */
    void setBitrate(int bps) {
        Codec c = codec;
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
            c.media.setParameters(b);
        } catch (Exception ignored) {
            // stopping
        }
    }

    double swapMs() {
        return Math.round(swapMsAvg * 10) / 10.0;
    }

    long composed() {
        return composed;
    }

    /** Run {@code task} on the GL thread and wait for it. */
    private <T> T call(Callable<T> task) throws IOException {
        Object[] result = new Object[1];
        Throwable[] failed = new Throwable[1];
        CountDownLatch done = new CountDownLatch(1);
        boolean posted = gl.post(() -> {
            try {
                result[0] = task.call();
            } catch (Throwable t) {
                failed[0] = t;
            } finally {
                done.countDown();
            }
        });
        if (!posted) throw new IOException("the video thread has stopped");
        try {
            if (!done.await(10, TimeUnit.SECONDS)) throw new IOException("the video thread did not answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (failed[0] instanceof IOException) throw (IOException) failed[0];
        if (failed[0] != null) throw new IOException(failed[0].toString(), failed[0]);
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }

    // ── GL ──

    private void setUp() {
        egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(egl, version, 0, version, 1)) throw new RuntimeException("eglInitialize");
        // A pbuffer to be current on between encoders, so the display's frames
        // can still be taken off it: where the driver has no recordable config
        // that also makes pbuffers, the context goes current with no surface.
        config = chooseConfig(EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT);
        boolean pbuffer = config != null;
        if (!pbuffer) config = chooseConfig(EGL14.EGL_WINDOW_BIT);
        if (config == null) throw new RuntimeException("no recordable EGL config");
        context = EGL14.eglCreateContext(egl, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
        if (pbuffer) {
            idle = EGL14.eglCreatePbufferSurface(egl, config,
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        }
        makeCurrent(idle);

        String vs = "attribute vec2 aPos; attribute vec4 aTex; uniform mat4 uMatrix; varying vec2 vTex;\n"
                + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = (uMatrix * aTex).xy; }\n";
        // highp: at mediump a 3840-texel row cannot be addressed exactly.
        String fs = "#extension GL_OES_EGL_image_external : require\n"
                + "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                + "varying vec2 vTex; uniform samplerExternalOES uTex;\n"
                + "void main() { gl_FragColor = texture2D(uTex, vTex); }\n";
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        // Linear: at one for one it samples each pixel exactly, and when the
        // display is larger than the encoder takes it is what scales it down.
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        frames = new SurfaceTexture(texture);
        frames.setDefaultBufferSize(srcW, srcH);
        frames.setOnFrameAvailableListener(st -> onFrame(), gl);
        sink = new Surface(frames);
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) throw new RuntimeException("GL error " + err + " setting up the display's texture");
    }

    private EGLConfig chooseConfig(int surfaceType) {
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(egl, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null;
        return configs[0];
    }

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(egl, surface, surface, context)) {
            throw new RuntimeException("eglMakeCurrent: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
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

    // ── frames ──

    /** A frame came off the display: take it, so the display never waits on us, and send it if it may go. */
    private void onFrame() {
        if (frames == null) return;
        try {
            frames.updateTexImage();
        } catch (RuntimeException e) {
            AppServer.log("a frame could not be taken (" + e + ")");
            return;
        }
        frames.getTransformMatrix(matrix);
        long ts = frames.getTimestamp();
        frameNs = ts > 0 ? ts : System.nanoTime();
        composed++;
        anyFrame = true;
        pending = true;
        tryDraw();
    }

    private void tryDraw() {
        Codec c = codec;
        Gate g = gate;
        if (!pending || c == null || g == null) return;
        long wait = g.mayDraw();
        if (wait > 0) {
            if (!checkQueued) {
                checkQueued = true;
                gl.postDelayed(() -> {
                    checkQueued = false;
                    tryDraw();
                }, wait);
            }
            return;
        }
        pending = false;
        draw(c);
    }

    private void draw(Codec c) {
        Encoder e = c.info;
        GLES20.glViewport(0, 0, e.width, e.height);
        if (!e.scaled && (e.width != e.srcWidth || e.height != e.srcHeight)) {
            // The alignment's padding, kept black; the computer crops it off.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(0, e.height - e.srcHeight, e.srcWidth, e.srcHeight);
        }
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        // The encoder wants timestamps that only ever rise; a frame sent again
        // after a new encoder (the picture already on the display) has the
        // same one as before.
        long pts = Math.max(frameNs, lastPtsNs + 1000);
        lastPtsNs = pts;
        EGLExt.eglPresentationTimeANDROID(egl, c.surface, pts);
        long t0 = System.nanoTime();
        EGL14.eglSwapBuffers(egl, c.surface);
        swapMsAvg = swapMsAvg * 0.9 + (System.nanoTime() - t0) / 1e6 * 0.1;
    }

    // ── encoders ──

    /** One MediaCodec and the surface it is drawn into. */
    private final class Codec {
        final MediaCodec media;
        final Surface input;
        final EGLSurface surface;
        final Encoder info;
        final LinuxStreamEncoder.Output out;
        final Thread drain;
        volatile boolean running = true;

        Codec(MediaCodec media, Surface input, EGLSurface surface, Encoder info, LinuxStreamEncoder.Output out) {
            this.media = media;
            this.input = input;
            this.surface = surface;
            this.info = info;
            this.out = out;
            this.drain = new Thread(this::drainLoop, thread.getName() + "-out");
        }

        private void drainLoop() {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            byte[] buf = new byte[256 * 1024];
            try {
                while (running) {
                    // Waits for the next unit rather than asking every few ms: each
                    // ask is a round trip through MediaCodec's own looper, and at
                    // 50 ms they were most of what an idle window cost the phone
                    // (Galaxy A13: the drain and that looper, 3.5 % of a core
                    // between them). release() ends the wait with end-of-stream.
                    int idx = media.dequeueOutputBuffer(bi, 1_000_000);
                    if (idx < 0) continue; // timeout, format or buffers changed: nothing to send
                    try {
                        if (bi.size > 0) {
                            ByteBuffer b = media.getOutputBuffer(idx);
                            if (b != null) {
                                if (buf.length < bi.size) buf = new byte[Math.max(bi.size, buf.length * 2)];
                                b.position(bi.offset);
                                b.limit(bi.offset + bi.size);
                                b.get(buf, 0, bi.size);
                                int flags = 0;
                                if ((bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags |= LinuxStreamEncoder.FLAG_KEY;
                                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags |= LinuxStreamEncoder.FLAG_CONFIG;
                                out.packet(buf, bi.size, bi.presentationTimeUs, flags);
                            }
                        }
                    } finally {
                        media.releaseOutputBuffer(idx, false);
                    }
                    // The encoder has room again: a frame waiting may go.
                    if (!direct) poke();
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            } catch (Throwable t) {
                if (running) out.stopped(t);
            }
        }
    }

    private Codec newCodec(int bitrate, int fps, LinuxStreamEncoder.Output out) throws IOException {
        List<String> tried = new ArrayList<>();
        for (MediaCodecInfo info : LinuxStreamEncoder.candidates()) {
            MediaCodecInfo.VideoCapabilities vc;
            try {
                vc = info.getCapabilitiesForType(MIME).getVideoCapabilities();
            } catch (Exception e) {
                continue;
            }
            int[] size = LinuxStreamEncoder.fit(vc, srcW, srcH, fps);
            if (size == null) {
                tried.add(info.getName() + " (no size)");
                continue;
            }
            MediaCodec media = null;
            Surface input = null;
            EGLSurface surface = EGL14.EGL_NO_SURFACE;
            try {
                media = MediaCodec.createByCodecName(info.getName());
                int attempt = LinuxStreamEncoder.configure(media, info, size[0], size[1], bitrate, fps);
                input = media.createInputSurface();
                surface = EGL14.eglCreateWindowSurface(egl, config, input, new int[]{EGL14.EGL_NONE}, 0);
                if (surface == EGL14.EGL_NO_SURFACE) {
                    throw new RuntimeException("eglCreateWindowSurface: 0x" + Integer.toHexString(EGL14.eglGetError()));
                }
                media.start();
                makeCurrent(surface);
                // Swap whenever asked, never held for a vsync: the encoder is the only consumer.
                EGL14.eglSwapInterval(egl, 0);
                boolean scaled = size[0] < srcW || size[1] < srcH;
                Encoder e = new Encoder(info.getName(), LinuxStreamEncoder.isHardware(info), size[0], size[1],
                        srcW, srcH, scaled, bitrate, fps, attempt == 0);
                Codec c = new Codec(media, input, surface, e, out);
                c.drain.start();
                return c;
            } catch (Exception ex) {
                tried.add(info.getName() + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
                if (surface != EGL14.EGL_NO_SURFACE) {
                    makeCurrent(idle);
                    EGL14.eglDestroySurface(egl, surface);
                }
                if (input != null) input.release();
                if (media != null) {
                    try {
                        media.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new IOException("no H.264 encoder takes " + srcW + "x" + srcH + ": " + tried);
    }

    /** An encoder whose input surface the display draws into itself: no GL at all. */
    private Codec newDirectCodec(int bitrate, int fps, LinuxStreamEncoder.Output out) throws IOException {
        List<String> tried = new ArrayList<>();
        for (MediaCodecInfo info : LinuxStreamEncoder.candidates()) {
            MediaCodecInfo.VideoCapabilities vc;
            try {
                vc = info.getCapabilitiesForType(MIME).getVideoCapabilities();
            } catch (Exception e) {
                continue;
            }
            int[] size = LinuxStreamEncoder.fit(vc, srcW, srcH, fps);
            // The display draws at its own size into whatever surface it has: a
            // coded size other than the display's would scale every frame.
            if (size == null || size[0] < srcW || size[1] < srcH) {
                tried.add(info.getName() + " (no size)");
                continue;
            }
            MediaCodec media = null;
            Surface input = null;
            try {
                media = MediaCodec.createByCodecName(info.getName());
                int attempt = LinuxStreamEncoder.configure(media, info, srcW, srcH, bitrate, fps);
                input = media.createInputSurface();
                media.start();
                Encoder e = new Encoder(info.getName(), LinuxStreamEncoder.isHardware(info), srcW, srcH,
                        srcW, srcH, false, bitrate, fps, attempt == 0);
                Codec c = new Codec(media, input, EGL14.EGL_NO_SURFACE, e, out);
                c.drain.start();
                return c;
            } catch (Exception ex) {
                tried.add(info.getName() + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
                if (input != null) input.release();
                if (media != null) {
                    try {
                        media.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new IOException("no H.264 encoder takes " + srcW + "x" + srcH + " as it is: " + tried);
    }

    /** Let the current encoder go, if there is one. GL thread. */
    private void release() {
        Codec c = codec;
        codec = null;
        if (c == null) return;
        c.running = false;
        try {
            // Wakes the drain's wait at once: it reads end-of-stream and returns.
            c.media.signalEndOfInputStream();
        } catch (Exception ignored) {
        }
        try {
            c.drain.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            c.media.stop();
        } catch (Exception ignored) {
        }
        try {
            c.drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            c.media.release();
        } catch (Exception ignored) {
        }
        if (c.surface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(idle);
            EGL14.eglDestroySurface(egl, c.surface);
        }
        c.input.release();
    }
}
