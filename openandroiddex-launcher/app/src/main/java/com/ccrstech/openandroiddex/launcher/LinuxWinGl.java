package com.ccrstech.openandroiddex.launcher;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws every Linux window, and every menu over one, from the pixels
 * {@link LinuxWm#fetch} reads out of the X server.
 *
 * <p>One thread, one GL context, one texture per window: a window's
 * rectangles go into its texture as they changed (glTexSubImage2D, nothing
 * else), and the texture is drawn into the window's surface one pixel to one
 * pixel, its top-left at the surface's — never scaled, so text is exactly as
 * the app drew it. A surface that is briefly larger than the window (an
 * Android resize the app has not caught up with) shows the window's colour
 * beside it, not a stretched picture.
 *
 * <p>The bytes are X's — B, G, R, unused — and go up as they are; the shader
 * reads them back as red, green and blue, as {@link LinuxStreamEncoder} does
 * for Xvnc's.
 */
final class LinuxWinGl {

    /** One surface to draw one window into: a Linux window's, or a menu's. */
    static final class Target {
        final LinuxWm wm;
        final LinuxWm.Win win;
        Surface surface;
        int surfaceWidth;
        int surfaceHeight;
        /** Shown where the surface reaches past the window; ARGB. */
        int background = 0xff000000;
        // GL thread only
        EGLSurface egl = EGL14.EGL_NO_SURFACE;
        int texture;
        int texWidth;
        int texHeight;
        boolean drawn;
        /** The surface changed: draw again even with no new pixels. */
        volatile boolean redraw = true;

        Target(LinuxWm wm, LinuxWm.Win win) {
            this.wm = wm;
            this.win = win;
        }
    }

    private static LinuxWinGl instance;

    static synchronized LinuxWinGl get() {
        if (instance == null) instance = new LinuxWinGl();
        return instance;
    }

    private final Object lock = new Object();
    private final List<Target> targets = new ArrayList<>();
    /** Targets whose EGL surface must go before their Surface does. Under {@link #lock}. */
    private final List<Target> leaving = new ArrayList<>();
    private boolean wake;
    private final Thread thread;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface idle = EGL14.EGL_NO_SURFACE;
    private int program;
    private int aPos;
    private int aTex;
    private int uScale;
    private final FloatBuffer quad;

    private LinuxWinGl() {
        float[] v = {
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
        thread = new Thread(this::loop, "linux-win-gl");
        thread.setDaemon(true);
        thread.start();
    }

    /** Start drawing into {@code t.surface}. Any thread. */
    void attach(Target t) {
        synchronized (lock) {
            if (!targets.contains(t)) targets.add(t);
            t.redraw = true;
            // A new surface starts with a new texture, which only the whole window fills.
            t.win.invalidate();
            wake = true;
            lock.notifyAll();
        }
    }

    /** The surface changed size. */
    void resized(Target t, int w, int h) {
        synchronized (lock) {
            t.surfaceWidth = w;
            t.surfaceHeight = h;
            t.redraw = true;
            wake = true;
            lock.notifyAll();
        }
    }

    /**
     * Stop drawing into it, and return only once nothing here holds its
     * surface — what SurfaceHolder asks of surfaceDestroyed.
     */
    void detach(Target t) {
        synchronized (lock) {
            if (!targets.remove(t)) return;
            leaving.add(t);
            wake = true;
            lock.notifyAll();
            long until = System.currentTimeMillis() + 1000;
            while (leaving.contains(t)) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** New pixels for some window. Any thread; never blocks. */
    void wake() {
        synchronized (lock) {
            wake = true;
            lock.notifyAll();
        }
    }

    private void loop() {
        try {
            setUp();
        } catch (RuntimeException e) {
            DexLog.warn("linuxwin", "GL: " + e.getMessage());
            return;
        }
        List<Target> work = new ArrayList<>();
        while (true) {
            synchronized (lock) {
                while (!wake && leaving.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                wake = false;
                for (Target t : leaving) release(t, true);
                leaving.clear();
                lock.notifyAll();
                work.clear();
                work.addAll(targets);
            }
            long frameStart = System.nanoTime();
            boolean more = false;
            for (Target t : work) {
                try {
                    more |= draw(t);
                } catch (RuntimeException e) {
                    DexLog.warn("linuxwin", "draw " + t.win + ": " + e);
                }
            }
            if (more) {
                // Damage arrived while drawing: go again, but no faster than
                // the display can show it.
                long spent = (System.nanoTime() - frameStart) / 1_000_000;
                if (spent < 8) {
                    try {
                        Thread.sleep(8 - spent);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                wake();
            }
        }
    }

    private void setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new RuntimeException("eglInitialize");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new RuntimeException("no EGL config");
        }
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
        // Something to be current on between windows, and to make textures with.
        idle = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(display, idle, idle, context);
        String vs = "attribute vec2 aPos; attribute vec2 aTex; uniform vec2 uScale; varying vec2 vTex;\n"
                + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex * uScale; }\n";
        String fs = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                + "varying vec2 vTex; uniform sampler2D uTex;\n"
                + "void main() { gl_FragColor = vec4(texture2D(uTex, vTex).bgr, 1.0); }\n";
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uScale = GLES20.glGetUniformLocation(program, "uScale");
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
    }

    /** @return true when the window had more damage than this frame took. */
    private boolean draw(Target t) {
        LinuxWm.Win w = t.win;
        if (w.closed || t.surface == null || !t.surface.isValid()) return false;
        boolean dirty = w.dirty();
        if (!dirty && !t.redraw) return false;
        if (t.egl == EGL14.EGL_NO_SURFACE) {
            t.egl = EGL14.eglCreateWindowSurface(display, config, t.surface, new int[]{EGL14.EGL_NONE}, 0);
            if (t.egl == EGL14.EGL_NO_SURFACE) return false;
        }
        if (!EGL14.eglMakeCurrent(display, t.egl, t.egl, context)) {
            release(t, false);
            return false;
        }
        if (dirty) {
            int[] size = t.wm.fetch(w, new LinuxWm.Pixels() {
                @Override
                public void begin(int width, int height, boolean whole) {
                    // A texture made afresh holds nothing, and only a fetch of
                    // the whole window fills it: if this one is only the parts
                    // that changed, the next reads it all.
                    if (ensureTexture(t, width, height) && !whole) w.invalidate();
                }

                @Override
                public void rect(int x, int y, int rw, int rh, ByteBuffer pixels) {
                    if (x + rw > t.texWidth || y + rh > t.texHeight) return;
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.texture);
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, rw, rh,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                }
            });
            if (size == null && !t.drawn) return w.dirty();
        }
        t.redraw = false;
        if (t.texture == 0) return w.dirty();
        int sw = t.surfaceWidth > 0 ? t.surfaceWidth : t.texWidth;
        int sh = t.surfaceHeight > 0 ? t.surfaceHeight : t.texHeight;
        GLES20.glViewport(0, 0, sw, sh);
        if (sw > t.texWidth || sh > t.texHeight) {
            int c = t.background;
            GLES20.glClearColor(((c >> 16) & 0xff) / 255f, ((c >> 8) & 0xff) / 255f, (c & 0xff) / 255f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        // One pixel to one pixel, top-left to top-left: GL's origin is the
        // bottom-left, so the window's rows end at the surface's top.
        GLES20.glViewport(0, sh - t.texHeight, t.texWidth, t.texHeight);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(uScale, 1f, 1f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.texture);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (!EGL14.eglSwapBuffers(display, t.egl)) {
            int err = EGL14.eglGetError();
            if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) release(t, false);
        }
        t.drawn = true;
        return w.dirty();
    }

    /** A texture the window's size. True when it was made afresh, and so holds nothing yet. */
    private boolean ensureTexture(Target t, int width, int height) {
        if (t.texture != 0 && t.texWidth == width && t.texHeight == height) return false;
        if (t.texture == 0) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            t.texture = tex[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        t.texWidth = width;
        t.texHeight = height;
        return true;
    }

    /**
     * Let go of the target's EGL surface — its Surface is going, or broke —
     * and, when it is leaving for good, its texture.
     */
    private void release(Target t, boolean freeTexture) {
        EGL14.eglMakeCurrent(display, idle, idle, context);
        if (t.egl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, t.egl);
            t.egl = EGL14.EGL_NO_SURFACE;
        }
        if (freeTexture && t.texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{t.texture}, 0);
            t.texture = 0;
            t.texWidth = 0;
            t.texHeight = 0;
        }
        t.drawn = false;
        t.redraw = true;
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
}
