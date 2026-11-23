package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Remote desktop's control: the accessibility service a computer's mouse and
 * keyboard go through ({@link PhoneInput}), and what keeps the phone awake
 * while a computer has it.
 *
 * <p>It reads nothing from the screen and listens to no events: what the
 * computer sees comes from screen sharing, not from here. It dispatches
 * touches, the navigation keys, and text into the focused field, and nothing
 * else.
 *
 * <p><b>Awake while watched.</b> A computer that opens the phone turns its
 * screen on ({@link #wake}) and keeps it on while it is there: a window of
 * this service's own, one pixel, invisible, that asks Android to keep the
 * screen on. When the last computer goes, the window goes and the phone
 * sleeps by its own timeout, as it would have. Asleep, a phone's screen
 * draws nothing to share.
 *
 * <p><b>Asleep and locked.</b> Waking is an activity that turns the screen on
 * and shows over the lock screen for a moment ({@link PhoneWakeActivity}): an
 * app whose accessibility service is on may start one from the background.
 * The lock screen then shows on the computer, and whoever knows the phone's
 * PIN can unlock it from there, as they would by hand.
 *
 * <p>Android keeps this service running while it is switched on, so it also
 * holds the stream open ({@link PhoneStream}) for a computer that comes while
 * nothing is being shared, which is how a computer can ask the phone again.
 */
public class PhoneControlService extends AccessibilityService {

    private static volatile PhoneControlService live;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Object> awake = new HashSet<>();
    private View keepOn;
    private PhoneShots shots;

    /** The running service, or null while it is switched off. */
    static PhoneControlService live() {
        return live;
    }

    /**
     * The slow view, this service's screenshots, for a computer that opens
     * the phone while nothing is shared; null before Android 11, which has no
     * screenshots for a service.
     */
    static PhoneShots shots() {
        PhoneControlService s = live;
        if (s == null || !PhoneShots.possible()) return null;
        synchronized (s) {
            if (s.shots == null) s.shots = new PhoneShots(s);
            return s.shots;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;
        DexLog.step("phone", "control is on");
        PhoneRemote.markControl(this, true);
        PhoneRemoteService.cancelControlLost(this);
        PhoneStream.hold(this, this);
        // The tunnel and the browser door live in :linux, brought back after an
        // update by MY_PACKAGE_REPLACED, which a Galaxy S25 was seen to hold
        // back for minutes once (2026-11-21). Android rebinds this service
        // after an update too, and whichever of the two comes first brings
        // them back; the other finds them up.
        if (PhoneRemote.isEnabled(this)) LinuxService.phoneRemoteChanged(this);
        // Start with the phone: right after a restart nothing is shared yet,
        // and Android asks for screen sharing once per session. The boot
        // left a mark (RemoteResumeReceiver), whenever the user unlocked; the
        // question comes up here, once, as soon as this service is back,
        // which Android lets start the question from the background. Only for
        // a share kept ready (PhoneRemote.asksAfterRestart).
        if (PhoneRemote.takeBootMark(this) && PhoneRemote.asksAfterRestart(this)
                && !PhoneRemoteService.sharing()) {
            main.postDelayed(() -> {
                if (!PhoneRemoteService.sharing() && PhoneRemote.isEnabled(this)) {
                    PhoneRemoteService.askForScreen(this, "the phone started");
                }
            }, 3000);
        }
        PhoneStream.screenChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing: the service listens to no events.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Turned off in Android's settings (an unbind): the user's choice, so
        // no "control is off" later. A force-stop kills the process with no
        // unbind, and leaves the mark for PhoneRemote.controlLost.
        PhoneRemote.markControl(this, false);
        gone();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        gone();
        super.onDestroy();
    }

    private void gone() {
        if (live != this) return;
        live = null;
        main.post(() -> {
            awake.clear();
            dropKeepOn();
        });
        PhoneShots s;
        synchronized (this) {
            s = shots;
            shots = null;
        }
        if (s != null) s.close();
        PhoneStream.release(this);
        PhoneStream.screenChanged();
        DexLog.step("phone", "control is off");
    }

    // ── awake ──

    /** {@code who} (a session) needs the screen on, or no longer does. Any thread. */
    void keepAwake(Object who, boolean on) {
        main.post(() -> {
            if (on) {
                awake.add(who);
            } else {
                awake.remove(who);
            }
            if (awake.isEmpty()) {
                dropKeepOn();
            } else if (keepOn == null) {
                addKeepOn();
            }
        });
    }

    private void addKeepOn() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            View v = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // One transparent pixel, drawn: a window that is never drawn, or
            // drawn at alpha 0, is not on screen as far as keeping it on goes.
            v.setBackgroundColor(0x00000000);
            lp.setTitle("Remote desktop: awake");
            wm.addView(v, lp);
            keepOn = v;
        } catch (Exception e) {
            DexLog.warn("phone", "cannot keep the screen on", e);
        }
    }

    private void dropKeepOn() {
        View v = keepOn;
        keepOn = null;
        if (v == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Exception ignored) {
        }
    }

    // ── the test pad ──

    private View pad;

    /**
     * Whether this install takes the test pad: a file only a debuggable build
     * can be given (run-as), so the Play build never shows one.
     */
    static boolean padAllowed(Context ctx) {
        return new java.io.File(PhoneRemote.dir(ctx), "testpad").exists();
    }

    /**
     * The test pad, for measuring a click to the picture: a full-screen
     * window of this service's own, over everything (the lock screen too),
     * that takes every touch, so a test's taps reach nothing of the user's. A
     * square in the middle turns white or black at each touch down; the test
     * taps it and times how long the changed square takes to come back as
     * video. {@code answer} hears the square's centre and size, in the
     * screen's pixels; {@code touched}, after each touch, how long the finger
     * was down on the phone and how many moves it made (a click must stay a
     * click here, whatever the network did between its press and release).
     * {@code busy}: every touch also draws the whole screen anew, a page of
     * small shapes laid out differently each time (an app opening, a page
     * turned), so that each change is a full screen's worth of picture, not
     * one square's. Any thread.
     */
    void testPad(boolean on, boolean busy, java.util.function.Consumer<int[]> answer,
                 java.util.function.Consumer<long[]> touched) {
        main.post(() -> {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (!on) {
                if (pad != null) {
                    try {
                        wm.removeView(pad);
                    } catch (Exception ignored) {
                    }
                    pad = null;
                }
                return;
            }
            if (pad != null || !padAllowed(this)) return;
            View v = padScrolls(this) ? scrollPad(touched) : new View(this) {
                boolean white;
                int moves;
                int page;
                final android.graphics.Paint paint = new android.graphics.Paint();

                @Override
                protected void onDraw(android.graphics.Canvas c) {
                    c.drawColor(0xFF202020);
                    if (busy) {
                        // Lines of word-sized shapes in many greys, a new layout each touch.
                        java.util.Random r = new java.util.Random(page * 7919L + 17);
                        for (int y = 4; y < getHeight(); y += 14) {
                            for (int x = 4; x < getWidth(); ) {
                                int w = 6 + r.nextInt(30);
                                int g = 40 + r.nextInt(200);
                                paint.setColor(0xFF000000 | (g << 16) | (r.nextInt(256) << 8) | (255 - g));
                                c.drawRect(x, y, Math.min(getWidth(), x + w), y + 9, paint);
                                x += w + 3 + r.nextInt(6);
                            }
                        }
                    }
                    int s = Math.min(getWidth(), getHeight()) / 3;
                    int x = (getWidth() - s) / 2, y = (getHeight() - s) / 2;
                    paint.setColor(white ? 0xFFFFFFFF : 0xFF000000);
                    c.drawRect(x, y, x + s, y + s, paint);
                }

                @Override
                public boolean onTouchEvent(android.view.MotionEvent e) {
                    int action = e.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        white = !white;
                        page++;
                        moves = 0;
                        invalidate();
                    } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                        moves++;
                    } else if ((action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL)
                            && touched != null) {
                        // The finger's time down, as the phone's own touch saw it;
                        // a touch the system cancelled rather than lifted says so.
                        touched.accept(new long[]{e.getEventTime() - e.getDownTime(), moves,
                                action == android.view.MotionEvent.ACTION_CANCEL ? 1 : 0});
                    }
                    return true;
                }
            };
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE);
            lp.setTitle("Remote desktop: test pad");
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            try {
                wm.addView(v, lp);
                pad = v;
                v.post(() -> {
                    int[] at = new int[2];
                    v.getLocationOnScreen(at);
                    int s = Math.min(v.getWidth(), v.getHeight()) / 3;
                    answer.accept(new int[]{at[0] + v.getWidth() / 2, at[1] + v.getHeight() / 2, s});
                });
            } catch (Exception e) {
                DexLog.warn("phone", "no test pad", e);
            }
        });
    }

    /** Whether the test pad is the one for the wheel: its mark says "scroll". */
    static boolean padScrolls(Context ctx) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(new java.io.File(PhoneRemote.dir(ctx), "testpad").toPath());
            return "scroll".equals(new String(b, java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The test pad for the wheel and a touchpad: a page 30 screens down and 3
     * across, opened at its middle, that says in the log (tag OpenDeXPad)
     * where it is after every change and when a finger goes down and up, in
     * the phone's uptime. A test can then tell how far each wheel input
     * scrolled it, and whether it went on by itself after the finger lifted
     * (a fling). A ScrollView around a HorizontalScrollView, as apps nest
     * them: each takes the touches that go its way, with Android's own touch
     * slop and fling.
     */
    private View scrollPad(java.util.function.Consumer<long[]> touched) {
        final String tag = "OpenDeXPad";
        android.graphics.Point size = new android.graphics.Point();
        ((android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(size);
        final int w = Math.max(1, size.x), h = Math.max(1, size.y);
        View page = new View(this) {
            final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onMeasure(int ws, int hs) {
                setMeasuredDimension(w * 3, h * 30);
            }

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                c.drawColor(0xFF1C2230);
                paint.setTextSize(Math.max(24, w / 22f));
                for (int y = 0; y < h * 30; y += 100) {
                    paint.setColor(y % 1000 == 0 ? 0xFFE0E6F0 : 0xFF56607A);
                    c.drawRect(0, y, w * 3, y + (y % 1000 == 0 ? 6 : 2), paint);
                    if (y % 200 == 0) {
                        paint.setColor(0xFFCFD6E0);
                        for (int x = 0; x < w * 3; x += w / 2) c.drawText(x + "," + y, x + 16, y + paint.getTextSize() + 8, paint);
                    }
                }
                for (int x = 0; x < w * 3; x += 100) {
                    paint.setColor(x % w == 0 ? 0xFFF0B040 : 0xFF3A4258);
                    c.drawRect(x, 0, x + (x % w == 0 ? 6 : 2), h * 30, paint);
                }
            }
        };
        android.widget.HorizontalScrollView across = new android.widget.HorizontalScrollView(this) {
            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                android.util.Log.i(tag, "x " + l + " " + SystemClock.uptimeMillis());
            }
        };
        across.addView(page);
        android.widget.ScrollView down = new android.widget.ScrollView(this) {
            int moves;

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                android.util.Log.i(tag, "y " + t + " " + SystemClock.uptimeMillis());
            }

            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    moves = 0;
                    android.util.Log.i(tag, "down " + e.getEventTime() + " " + Math.round(e.getX()) + "," + Math.round(e.getY()));
                } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                    moves++;
                } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    long held = e.getEventTime() - e.getDownTime();
                    android.util.Log.i(tag, (a == android.view.MotionEvent.ACTION_UP ? "up " : "cancel ") + e.getEventTime()
                            + " " + held + "ms " + moves + "mv");
                    if (touched != null) touched.accept(new long[]{held, moves, a == android.view.MotionEvent.ACTION_CANCEL ? 1 : 0});
                }
                return super.dispatchTouchEvent(e);
            }
        };
        down.addView(across);
        down.post(() -> {
            down.scrollTo(0, h * 15);
            across.scrollTo(w, 0);
            android.util.Log.i(tag, "pad " + w + "x" + h + " " + SystemClock.uptimeMillis());
        });
        return down;
    }

    private final Object viewsLock = new Object();
    private boolean allViews;

    /**
     * While the phone is locked, every view of the lock screen, not only the
     * ones its app marks important, and each view's id: a Galaxy's PIN pad
     * says "Enter PIN" and has its OK key in views it does not mark, and
     * {@link PhoneLock} knows the OK key and the pad's own lines by their ids
     * ({@code key_enter_text}, the message areas), which Android leaves out
     * unless asked (FLAG_REPORT_VIEW_IDS). Without the ids, on the S25, the
     * pad came with its digits and Delete, found by what they say, and with
     * no OK key and no words. The computer draws all of them. Back to the
     * usual, smaller set once the phone unlocks. Any thread.
     */
    void seeAllViews(boolean on) {
        synchronized (viewsLock) {
            if (allViews == on) return;
            try {
                android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
                if (info == null) return;
                int both = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                if (on) {
                    info.flags |= both;
                } else {
                    info.flags &= ~both;
                }
                setServiceInfo(info);
                allViews = on;
                // Views read before the change keep their old shape in the
                // service's cache until they change on the screen.
                if (android.os.Build.VERSION.SDK_INT >= 33) clearCache();
            } catch (RuntimeException e) {
                DexLog.warn("phone", "the lock screen's views could not all be read", e);
            }
        }
    }

    /** The screen on, if it is off. Any thread. */
    void wake() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        main.post(() -> {
            try {
                Intent i = new Intent(this, PhoneWakeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                startActivity(i);
                DexLog.step("phone", "turning the screen on for a computer");
            } catch (Exception e) {
                DexLog.warn("phone", "cannot turn the screen on", e);
            }
        });
    }
}
