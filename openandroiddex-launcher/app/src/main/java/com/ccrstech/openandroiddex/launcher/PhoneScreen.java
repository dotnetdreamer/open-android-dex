package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
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
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone's screen as H.264, for {@link PhoneStream}.
 *
 * <p>MediaProjection's virtual display mirrors the screen into a
 * {@link SurfaceTexture}, and a picture goes on to the encoder only when the
 * session has room for it ({@link Gate#mayDraw}): one GL draw from that
 * texture into the encoder's input surface. That draw is the whole point of
 * the texture. A virtual display wired straight to the encoder (what screen
 * recorders do) encodes every frame the screen makes, 60 or 120 a second, and
 * a link that cannot carry them has only two ways out: queue them, which is
 * lag, or drop encoded frames, which breaks every frame after until a key
 * frame. Here a link that falls behind gets fewer pictures, each one whole and
 * each one the newest, the same rule {@link LinuxStream} keeps by asking Xvnc
 * for an update only when the window has room.
 *
 * <p>Every buffer the display hands over is taken off it at once
 * ({@link SurfaceTexture#updateTexImage}), drawn or not, so the mirror never
 * waits on this side. A screen that does not change makes no frames at all,
 * and the encoder sends its last picture again a few times
 * ({@code KEY_REPEAT_PREVIOUS_FRAME_AFTER}) so it sharpens.
 *
 * <p><b>One virtual display per projection.</b> From Android 14 a
 * projection's consent covers one {@code createVirtualDisplay}; a second
 * throws. So the display is made once and kept: resized when the screen turns
 * or the quality changes, and given no surface while nobody watches, so the
 * mirror costs nothing then. A new encoder, on the other hand, is cheap and
 * made for every new size: an encoder cannot change size.
 *
 * <p>Everything GL, the texture and the display happen on one thread,
 * {@code phone-gl}. The encoded units are drained on a thread of their own and
 * handed to {@link Sink}, which may block: a slow link slows the drain, not
 * the screen.
 */
final class PhoneScreen implements PhoneSource {

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    /** A frame may go this much early: vsync is not exactly the frame rate. */
    private static final long EARLY_NS = 2_000_000;

    private final Context ctx;
    private final MediaProjection projection;
    private final HandlerThread thread;
    private final Handler gl;

    // GL thread only, from here down to the encoder.
    private EGLDisplay egl = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface idle = EGL14.EGL_NO_SURFACE;
    private EGLSurface target = EGL14.EGL_NO_SURFACE;
    private int program;
    private int aPos;
    private int aTex;
    private int uTexMatrix;
    private int oesTexture;
    private final float[] texMatrix = new float[16];
    private FloatBuffer quad;
    private SurfaceTexture texture;
    private Surface textureSurface;
    private VirtualDisplay display;
    private DisplayManager.DisplayListener displayListener;
    private boolean mirroring;

    private int screenW;
    private int screenH;
    private int rotation;
    private int densityDpi;

    /** Buffers the display has handed over and the GL thread has not yet taken. */
    private int pending;
    /** The texture holds a picture the encoder has not had. */
    private boolean fresh;
    /** A draw is posted for when the frame rate allows the next one. */
    private boolean timed;
    private long lastDrawNs;
    private boolean released;

    // The stream: set on the GL thread, read on others.
    private volatile Sink sink;
    private volatile Gate gate;
    private PhoneRemote.Quality quality;
    private int bitrate;
    private boolean keyWhenAsked;
    private volatile Encoding encoding;

    // The encoder.
    private volatile MediaCodec codec;
    private Surface codecInput;
    private Thread drain;

    private PhoneScreen(Context ctx, MediaProjection projection) {
        this.ctx = ctx.getApplicationContext();
        this.projection = projection;
        this.thread = new HandlerThread("phone-gl");
        thread.start();
        this.gl = new Handler(thread.getLooper());
    }

    /**
     * The screen, mirrored into a texture, no picture made yet. The
     * projection's callback must already be registered (Android 14 requires
     * it before the display is made). Blocks until the display exists.
     */
    static PhoneScreen open(Context ctx, MediaProjection projection) throws IOException {
        PhoneScreen s = new PhoneScreen(ctx, projection);
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failed = new Throwable[1];
        s.gl.post(() -> {
            try {
                s.setUp();
            } catch (Throwable t) {
                failed[0] = t;
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) failed[0] = new IOException("the display took too long");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed[0] = e;
        }
        if (failed[0] != null) {
            s.close();
            throw new IOException("cannot mirror the screen: " + failed[0], failed[0]);
        }
        return s;
    }

    // ── what the session asks for ──

    /**
     * Start making pictures for a session: a new encoder at {@code q}'s size
     * for the screen as it is turned now, the display drawing again. Its
     * first unit follows {@link Sink#video}.
     */
    @Override
    public void start(Sink sink, Gate gate, PhoneRemote.Quality q, int bitrate, boolean keyWhenAsked) {
        gl.post(() -> {
            if (released) return;
            this.sink = sink;
            this.gate = gate;
            this.quality = q;
            this.bitrate = bitrate;
            this.keyWhenAsked = keyWhenAsked;
            rebuild();
        });
    }

    /** No more pictures: the encoder goes, and the display stops drawing. The projection stays. */
    @Override
    public void stop(Sink of) {
        gl.post(() -> {
            if (sink != of) return;
            sink = null;
            gate = null;
            releaseEncoder();
            mirror(false);
        });
    }

    /** Another quality for the session that has the screen: a new encoder at its size. */
    @Override
    public void setQuality(Sink of, PhoneRemote.Quality q, int bitrate) {
        gl.post(() -> {
            if (sink != of || released) return;
            quality = q;
            this.bitrate = bitrate;
            rebuild();
        });
    }

    /** The link's bitrate, from the next frames on. Any thread. */
    @Override
    public void setBitrate(int bps) {
        MediaCodec c = codec;
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
            c.setParameters(b);
        } catch (Exception ignored) {
            // stopping
        }
    }

    /**
     * A key frame next, for a decoder that has to start over. A screen that
     * is standing still makes no frames to carry it, so the picture the
     * texture holds goes again too. Any thread.
     */
    @Override
    public void requestKeyFrame() {
        MediaCodec c = codec;
        if (c != null) {
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                c.setParameters(b);
            } catch (Exception ignored) {
            }
        }
        gl.post(() -> {
            fresh = true;
            tick();
        });
    }

    /** The session has room again (an acknowledgement came): a picture that waited may go. Any thread. */
    @Override
    public void kick() {
        gl.post(this::tick);
    }

    @Override
    public Encoding encoding() {
        return encoding;
    }

    /** Everything goes: the encoder, the display, GL. The projection is the caller's to stop. */
    void close() {
        gl.post(() -> {
            released = true;
            sink = null;
            gate = null;
            releaseEncoder();
            if (displayListener != null) {
                ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
                displayListener = null;
            }
            if (display != null) {
                display.release();
                display = null;
            }
            if (textureSurface != null) textureSurface.release();
            if (texture != null) texture.release();
            textureSurface = null;
            texture = null;
            if (egl != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, idle);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, eglContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(egl);
            }
            egl = EGL14.EGL_NO_DISPLAY;
            thread.quitSafely();
        });
    }

    // ── set up ──

    private void setUp() {
        try {
            // The compositor's own priority; refused quietly where it is not allowed.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (Exception ignored) {
        }
        readScreen();
        egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(egl, version, 0, version, 1)) throw new IllegalStateException("eglInitialize");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(egl, attribs, 0, configs, 0, 1, n, 0) || n[0] < 1) {
            throw new IllegalStateException("no recordable EGL config");
        }
        eglConfig = configs[0];
        eglContext = EGL14.eglCreateContext(egl, eglConfig, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext");
        idle = EGL14.eglCreatePbufferSurface(egl, eglConfig, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(egl, idle, idle, eglContext)) throw new IllegalStateException("eglMakeCurrent");

        String vs = "attribute vec4 aPos;\n"
                + "attribute vec4 aTex;\n"
                + "uniform mat4 uTexMatrix;\n"
                + "varying vec2 vTex;\n"
                + "void main() { gl_Position = aPos; vTex = (uTexMatrix * aTex).xy; }\n";
        String fs = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "varying vec2 vTex;\n"
                + "uniform samplerExternalOES sTex;\n"
                + "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        float[] v = {
                // x, y, s, t
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        oesTexture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(oesTexture);
        int[] size = contentSize(PhoneRemote.Quality.of(PhoneRemote.MEDIUM));
        texture.setDefaultBufferSize(size[0], size[1]);
        texture.setOnFrameAvailableListener(st -> {
            pending++;
            tick();
        }, gl);
        textureSurface = new Surface(texture);

        // Made once, for the projection's whole life; drawing only while
        // someone watches (mirror()).
        display = projection.createVirtualDisplay("Remote desktop", size[0], size[1], densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, gl);
        if (display == null) throw new IllegalStateException("no virtual display");

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int id) {
            }

            @Override
            public void onDisplayRemoved(int id) {
            }

            @Override
            public void onDisplayChanged(int id) {
                if (id != Display.DEFAULT_DISPLAY || released) return;
                int w = screenW, h = screenH, r = rotation;
                readScreen();
                if (w != screenW || h != screenH || r != rotation) {
                    DexLog.step("phone", "the screen is now " + screenW + "x" + screenH + " (turned " + rotation * 90 + ")");
                    if (sink != null) rebuild();
                }
            }
        };
        dm.registerDisplayListener(displayListener, gl);
    }

    private void readScreen() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        DisplayMetrics m = new DisplayMetrics();
        if (d != null) {
            d.getRealSize(p);
            d.getRealMetrics(m);
            rotation = d.getRotation();
        }
        if (p.x <= 0 || p.y <= 0) p.set(1080, 2340);
        screenW = p.x;
        screenH = p.y;
        densityDpi = m.densityDpi > 0 ? m.densityDpi : 420;
    }

    /** The screen at {@code q}'s size (its quality and the window's Picture size), both sides even. */
    private int[] contentSize(PhoneRemote.Quality q) {
        return q.pictureFor(screenW, screenH);
    }

    // ── the encoder ──

    /** A new encoder for the session, at its quality and the screen as it is now. GL thread. */
    private void rebuild() {
        Sink out = sink;
        if (out == null || released) return;
        releaseEncoder();
        try {
            Encoding e = newEncoder(quality, bitrate);
            encoding = e;
            // The texture's buffers at the picture's size, then the display:
            // the other order hands over one frame at the old size.
            texture.setDefaultBufferSize(e.w, e.h);
            display.resize(e.w, e.h, Math.max(1, densityDpi * e.w / Math.max(1, e.screenW)));
            out.video(e);
            mirror(true);
            // The first picture goes as soon as the display draws one. A screen
            // standing still may not draw again for a while: whatever the
            // texture holds goes now, and the next one replaces it.
            fresh = true;
            tick();
        } catch (Exception ex) {
            DexLog.warn("phone", "no encoder", ex);
            out.stopped(ex);
        }
    }

    private Encoding newEncoder(PhoneRemote.Quality q, int bps) throws IOException {
        int[] want = contentSize(q);
        int keyEvery = keyWhenAsked ? LinuxStreamEncoder.ONLY_WHEN_ASKED : LinuxStreamEncoder.KEY_FRAME_EVERY_S;
        List<String> tried = new ArrayList<>();
        for (MediaCodecInfo info : LinuxStreamEncoder.candidates()) {
            MediaCodecInfo.VideoCapabilities vc;
            try {
                vc = info.getCapabilitiesForType(MIME).getVideoCapabilities();
            } catch (Exception e) {
                continue;
            }
            int[] coded = LinuxStreamEncoder.fit(vc, want[0], want[1], q.fps);
            if (coded == null) {
                tried.add(info.getName() + " (no size)");
                continue;
            }
            int w = want[0], h = want[1];
            if (coded[0] < w || coded[1] < h) {
                // The encoder will not take the picture: the largest one, at
                // the screen's shape, inside the size it will take.
                double s = Math.min(coded[0] / (double) w, coded[1] / (double) h);
                w = Math.max(2, (int) (w * s) & ~1);
                h = Math.max(2, (int) (h * s) & ~1);
            }
            MediaCodec c = null;
            try {
                c = MediaCodec.createByCodecName(info.getName());
                int attempt = LinuxStreamEncoder.configure(c, info, coded[0], coded[1], bps, q.fps, keyEvery);
                Surface input = c.createInputSurface();
                c.start();
                EGLSurface s = EGL14.eglCreateWindowSurface(egl, eglConfig, input, new int[]{EGL14.EGL_NONE}, 0);
                if (s == EGL14.EGL_NO_SURFACE) {
                    input.release();
                    throw new IllegalStateException("eglCreateWindowSurface");
                }
                codec = c;
                codecInput = input;
                target = s;
                EGL14.eglMakeCurrent(egl, target, target, eglContext);
                Encoding e = new Encoding();
                e.source = "screen";
                e.codecName = info.getName();
                e.hardware = LinuxStreamEncoder.isHardware(info);
                e.encW = coded[0];
                e.encH = coded[1];
                e.w = w;
                e.h = h;
                e.screenW = screenW;
                e.screenH = screenH;
                e.rotation = rotation;
                e.bitrate = bps;
                e.fps = q.fps;
                e.configAttempt = attempt;
                startDrain(c);
                DexLog.step("phone", "encoding the " + screenW + "x" + screenH + " screen as " + w + "x" + h
                        + (coded[0] != w || coded[1] != h ? " in " + coded[0] + "x" + coded[1] : "")
                        + " on " + e.codecName + (e.hardware ? "" : " (software)") + " at "
                        + Math.round(bps / 1e5) / 10.0 + " Mb/s, " + q.id
                        + (attempt == 0 ? ", low latency" : ", plain configuration"));
                return e;
            } catch (Exception ex) {
                tried.add(info.getName() + " (" + ex.getClass().getSimpleName() + ")");
                if (c != null) {
                    try {
                        c.release();
                    } catch (Exception ignored) {
                    }
                }
                codec = null;
            }
        }
        throw new IOException("no H.264 encoder takes " + want[0] + "x" + want[1] + ": " + tried);
    }

    private void startDrain(MediaCodec c) {
        Thread t = new Thread(() -> drainLoop(c), "phone-out");
        t.setPriority(Thread.MAX_PRIORITY);
        drain = t;
        t.start();
    }

    private void drainLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[256 * 1024];
        try {
            while (codec == c) {
                int idx = c.dequeueOutputBuffer(info, 50_000);
                if (idx < 0) continue;
                try {
                    if (info.size > 0) {
                        ByteBuffer b = c.getOutputBuffer(idx);
                        Sink out = sink;
                        if (b != null && out != null) {
                            if (buf.length < info.size) buf = new byte[Math.max(info.size, buf.length * 2)];
                            b.position(info.offset);
                            b.limit(info.offset + info.size);
                            b.get(buf, 0, info.size);
                            int flags = 0;
                            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags |= LinuxStreamEncoder.FLAG_KEY;
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags |= LinuxStreamEncoder.FLAG_CONFIG;
                            out.packet(buf, info.size, info.presentationTimeUs, flags);
                        }
                    }
                } finally {
                    c.releaseOutputBuffer(idx, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Throwable t) {
            Sink out = sink;
            if (codec == c && out != null) out.stopped(t);
        }
    }

    /** GL thread. Safe to call with no encoder. */
    private void releaseEncoder() {
        MediaCodec c = codec;
        codec = null;
        encoding = null;
        if (target != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(egl, idle, idle, eglContext);
            EGL14.eglDestroySurface(egl, target);
            target = EGL14.EGL_NO_SURFACE;
        }
        if (c != null) {
            try {
                c.signalEndOfInputStream();
            } catch (Exception ignored) {
            }
            Thread t = drain;
            if (t != null) {
                try {
                    t.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                c.stop();
            } catch (Exception ignored) {
            }
            try {
                c.release();
            } catch (Exception ignored) {
            }
        }
        drain = null;
        if (codecInput != null) codecInput.release();
        codecInput = null;
    }

    // ── pictures ──

    /** The display draws into the texture, or nothing. GL thread. */
    private void mirror(boolean on) {
        if (display == null || on == mirroring) return;
        display.setSurface(on ? textureSurface : null);
        mirroring = on;
    }

    /**
     * Take whatever the display handed over, and send the newest picture on
     * if the frame rate and the session both allow. GL thread.
     */
    private void tick() {
        if (released || texture == null) return;
        if (pending > 0) {
            try {
                while (pending > 0) {
                    texture.updateTexImage();
                    pending--;
                }
            } catch (Exception e) {
                pending = 0;
                return;
            }
            fresh = true;
        }
        Gate g = gate;
        if (!fresh || target == EGL14.EGL_NO_SURFACE || g == null) return;
        long now = System.nanoTime();
        int fps = quality != null ? quality.fps : 60;
        long due = lastDrawNs + 1_000_000_000L / fps - EARLY_NS;
        if (now < due) {
            if (!timed) {
                timed = true;
                gl.postDelayed(() -> {
                    timed = false;
                    tick();
                }, Math.max(1, (due - now) / 1_000_000));
            }
            return;
        }
        if (!g.mayDraw()) return;
        draw(now);
        fresh = false;
        lastDrawNs = now;
        g.drew();
    }

    private void draw(long now) {
        Encoding e = encoding;
        if (e == null) return;
        texture.getTransformMatrix(texMatrix);
        GLES20.glViewport(0, 0, e.encW, e.encH);
        if (e.encW != e.w || e.encH != e.h) {
            // The alignment's padding, kept black; the computer crops it off.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(0, e.encH - e.h, e.w, e.h);
        }
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(egl, target, now);
        EGL14.eglSwapBuffers(egl, target);
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
            throw new IllegalStateException("shader: " + log);
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
        if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
