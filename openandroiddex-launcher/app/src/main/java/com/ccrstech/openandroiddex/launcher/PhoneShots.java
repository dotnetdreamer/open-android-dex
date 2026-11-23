package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Display;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * Remote desktop's slow view: the screen as the control service's own
 * screenshots, about three a second, for when nothing is shared
 * ({@link PhoneSource}).
 *
 * <p>This is what still works with nobody at the phone. Screen sharing needs
 * a person to say yes on the phone, every session, and ends whenever the
 * phone locks (Android 15 QPR1 on); a screenshot needs neither, and the lock
 * screen is in it. So a computer that opens a locked phone sees its lock
 * screen here, can unlock it with the PIN the way a hand would, and goes on at
 * this pace until someone at the phone allows screen sharing.
 *
 * <p>Android allows a service one screenshot every 333 ms. Each one is read
 * back from its hardware buffer, drawn smaller for the quality, and drawn into
 * the hardware encoder ({@link LinuxStreamEncoder}, the same one Linux's
 * desktop uses, fed from a texture), so the computer gets the same H.264 on
 * the same wire, three pictures a second instead of sixty. Windows an app
 * marks secure are black, as in screen sharing.
 *
 * <p>Everything here runs on one thread, {@code phone-shots}, which owns the
 * encoder's GL context.
 */
final class PhoneShots implements PhoneSource {

    /** Android's least gap between two screenshots of one service is 333 ms; a little over it. */
    private static final long EVERY_MS = 340;
    /** The frame rate the encoder is told: about the pictures it gets, so each one has its share. */
    private static final int FPS = 5;
    private static final int MIN_BITRATE = 500_000;

    private final AccessibilityService service;
    private final HandlerThread thread;
    private final Handler h;
    private final Executor onThread;

    // The shots thread's.
    private Sink sink;
    private Gate gate;
    private PhoneRemote.Quality quality;
    private int bitrate;
    private boolean keyWhenAsked;
    private LinuxStreamEncoder enc;
    private ByteBuffer pixels;
    /** The last picture, drawn again for a key frame when nothing new comes. */
    private boolean haveLast;
    private boolean taking;
    private boolean posted;
    private long lastShotAt;
    private int failures;
    private boolean released;
    private volatile Encoding encoding;

    PhoneShots(AccessibilityService service) {
        this.service = service;
        this.thread = new HandlerThread("phone-shots");
        thread.start();
        this.h = new Handler(thread.getLooper());
        this.onThread = h::post;
    }

    /** Whether this phone takes screenshots for a service at all: Android 11 on. */
    static boolean possible() {
        return Build.VERSION.SDK_INT >= 30;
    }

    @Override
    public void start(Sink sink, Gate gate, PhoneRemote.Quality q, int bitrate, boolean keyWhenAsked) {
        h.post(() -> {
            if (released) return;
            releaseEncoder();
            this.sink = sink;
            this.gate = gate;
            this.quality = q;
            this.bitrate = bitrate;
            this.keyWhenAsked = keyWhenAsked;
            failures = 0;
            shootSoon(0);
        });
    }

    @Override
    public void stop(Sink of) {
        h.post(() -> {
            if (sink != of) return;
            sink = null;
            gate = null;
            releaseEncoder();
        });
    }

    @Override
    public void setQuality(Sink of, PhoneRemote.Quality q, int bitrate) {
        h.post(() -> {
            if (sink != of) return;
            quality = q;
            this.bitrate = bitrate;
            // The next shot makes an encoder at the new size.
            releaseEncoder();
            shootSoon(0);
        });
    }

    @Override
    public void setBitrate(int bps) {
        h.post(() -> {
            int b = shotsBitrate(bps);
            LinuxStreamEncoder e = enc;
            if (e != null) e.setBitrate(b);
        });
    }

    @Override
    public void requestKeyFrame() {
        h.post(() -> {
            LinuxStreamEncoder e = enc;
            if (e == null) return;
            e.requestKeyFrame();
            // The screen may stand still until the next shot: the last picture again carries it.
            if (haveLast && gate != null && gate.mayDraw()) {
                e.frame();
                gate.drew();
            }
        });
    }

    @Override
    public void kick() {
        // The next shot is on a timer; a session with room again only shortens nothing.
    }

    @Override
    public Encoding encoding() {
        return encoding;
    }

    void close() {
        h.post(() -> {
            released = true;
            sink = null;
            gate = null;
            releaseEncoder();
            thread.quitSafely();
        });
    }

    // ── the shots ──

    private void shootSoon(long delayMs) {
        if (posted) return;
        posted = true;
        h.postDelayed(() -> {
            posted = false;
            shoot();
        }, Math.max(0, delayMs));
    }

    private void shoot() {
        if (released || sink == null || taking) return;
        long now = SystemClock.uptimeMillis();
        long due = lastShotAt + EVERY_MS;
        if (now < due) {
            shootSoon(due - now);
            return;
        }
        Gate g = gate;
        if (g == null) return;
        if (!g.mayDraw()) {
            // The link is behind: the shot waits, and then is the newest.
            shootSoon(30);
            return;
        }
        taking = true;
        lastShotAt = now;
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, onThread, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    taking = false;
                    HardwareBuffer hb = r.getHardwareBuffer();
                    try {
                        got(hb, r);
                        failures = 0;
                    } catch (Exception e) {
                        DexLog.warn("phone", "a screenshot could not be sent", e);
                    } finally {
                        hb.close();
                    }
                    shootSoon(EVERY_MS);
                }

                @Override
                public void onFailure(int errorCode) {
                    taking = false;
                    // Too soon is Android's clock against ours: try just after.
                    // Anything else (a display that is off) is tried again
                    // more slowly, and said once.
                    if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && failures++ == 0) {
                        DexLog.step("phone", "no screenshot (error " + errorCode + ")");
                    }
                    shootSoon(errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? 60 : 1000);
                }
            });
        } catch (Exception e) {
            taking = false;
            DexLog.warn("phone", "screenshots refused", e);
            shootSoon(2000);
        }
    }

    private void got(HardwareBuffer hb, AccessibilityService.ScreenshotResult r) throws Exception {
        Sink out = sink;
        Gate g = gate;
        if (out == null || g == null) return;
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
        if (hw == null) return;
        int screenW = hw.getWidth(), screenH = hw.getHeight();
        Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        if (soft == null) return;
        int[] size = contentSize(screenW, screenH, quality);
        if (soft.getWidth() != size[0] || soft.getHeight() != size[1]) {
            Bitmap scaled = Bitmap.createScaledBitmap(soft, size[0], size[1], true);
            if (scaled != soft) soft.recycle();
            soft = scaled;
        }
        if (enc == null || enc.fbWidth != size[0] || enc.fbHeight != size[1]) {
            newEncoder(out, size[0], size[1], screenW, screenH);
        }
        int need = size[0] * size[1] * 4;
        if (pixels == null || pixels.capacity() < need) pixels = ByteBuffer.allocateDirect(need);
        pixels.clear();
        soft.copyPixelsToBuffer(pixels);
        pixels.position(0);
        soft.recycle();
        enc.upload(0, 0, size[0], size[1], pixels);
        enc.frame();
        haveLast = true;
        g.drew();
    }

    private void newEncoder(Sink out, int w, int h, int screenW, int screenH) throws Exception {
        releaseEncoder();
        int keyEvery = keyWhenAsked ? LinuxStreamEncoder.ONLY_WHEN_ASKED : LinuxStreamEncoder.KEY_FRAME_EVERY_S;
        int b = shotsBitrate(bitrate);
        // Only this source's sink, and only while it is the one: an encoder's
        // last units may drain after the session has moved on.
        LinuxStreamEncoder.Output guarded = new LinuxStreamEncoder.Output() {
            @Override
            public void packet(byte[] data, int len, long ptsUs, int flags) throws java.io.IOException {
                if (sink == out) out.packet(data, len, ptsUs, flags);
            }

            @Override
            public void stopped(Throwable why) {
                if (sink == out) out.stopped(why);
            }
        };
        LinuxStreamEncoder e = LinuxStreamEncoder.create(w, h, b, FPS, 1.0, keyEvery, "rgb", guarded);
        enc = e;
        Encoding x = new Encoding();
        x.source = "shots";
        x.codecName = e.codecName;
        x.hardware = e.hardware;
        x.encW = e.encWidth;
        x.encH = e.encHeight;
        x.w = e.scaled ? e.encWidth : w;
        x.h = e.scaled ? e.encHeight : h;
        x.screenW = screenW;
        x.screenH = screenH;
        x.bitrate = b;
        x.fps = (int) Math.round(1000.0 / EVERY_MS);
        x.configAttempt = e.configAttempt;
        encoding = x;
        DexLog.step("phone", "the slow view: " + screenW + "x" + screenH + " screenshots as " + w + "x" + h
                + " on " + e.codecName + " at " + Math.round(b / 1e5) / 10.0 + " Mb/s");
        out.video(x);
    }

    private void releaseEncoder() {
        LinuxStreamEncoder e = enc;
        enc = null;
        encoding = null;
        haveLast = false;
        if (e != null) e.release();
    }

    /** A sixtieth-of-a-second bitrate, spread over a few pictures a second. */
    private int shotsBitrate(int sessionBps) {
        return Math.max(MIN_BITRATE, sessionBps / 4);
    }

    /** The screenshot at the quality's size (and the window's Picture size), both sides even. */
    private static int[] contentSize(int screenW, int screenH, PhoneRemote.Quality q) {
        if (q == null) return new int[]{Math.max(2, screenW & ~1), Math.max(2, screenH & ~1)};
        return q.pictureFor(screenW, screenH);
    }
}
