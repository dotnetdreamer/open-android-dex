package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.InputMethod;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;

/**
 * A computer's mouse and keyboard, put into the phone through
 * {@link PhoneControlService}.
 *
 * <p><b>A mouse becomes a finger.</b> An app may not inject input events
 * ({@code INJECT_EVENTS} is the system's), so a press is a touch the
 * accessibility service dispatches ({@code dispatchGesture}): a real touch to
 * every app on the phone. The left button is the finger; the right one is
 * Back and the middle one Home, as in scrcpy. The wheel and a touchpad are a
 * finger of their own that stays down while they go and moves as far as a
 * page on the computer would scroll, then holds still and lifts without
 * speed, so the page does not fling on.
 *
 * <p><b>Drags are a queue.</b> A stroke that goes on may only be dispatched
 * once the one before it has completed ({@code continueStroke}), so pointer
 * moves are coalesced and the newest position wins (as the Web viewer's
 * {@link WebInput} does). A finger that is held still is held down: a long
 * press is the phone's own, from the press.
 *
 * <p><b>Typing</b> goes into the focused field. From Android 13 the service
 * is also an input method of its own ({@code flagInputMethodEditor}), so text
 * is committed where the cursor is and Backspace, Delete, the arrows, Tab,
 * Home, End and Ctrl shortcuts are key events in that field, whatever
 * keyboard the phone uses. Before Android 13 the field's text is set whole
 * through its accessibility node, which works in ordinary apps. With no field
 * focused, the arrows and Enter move the phone's focus (Android 13), and
 * Escape is Back.
 *
 * <p>Everything is dispatched on a thread of its own, {@code phone-input}, so
 * a busy main thread never holds a finger down.
 *
 * <p><b>With the phone's helper</b> ({@link PhoneHelper}, which a computer on
 * the cable starts), the finger, the wheel and the keys are real input events
 * instead, put in as the shell (scrcpy's way): Android lets an accessibility
 * service that is not an accessibility tool press nothing it keeps for a
 * person at the phone (permission questions, the installer, sensitive
 * Settings switches), and an input event from the shell is a person's as far
 * as those screens can tell. Each press goes one way from its start to its
 * end, so a finger never changes hands halfway. Without the helper, a press
 * on such a screen is said to the computer ({@link #onProtected}).
 */
final class PhoneInput {

    /** One leg of a drag: the shortest the system dispatches, and no longer than feels attached. */
    private static final int LEG_MIN_MS = 8;
    private static final int LEG_MAX_MS = 100;
    /** A wheel notch sent as RFB's wheel bits: Android's own notch (ViewConfiguration's scroll factor), in dp. */
    private static final float WHEEL_DP = 64f;
    /** One leg of the scrolling finger: as short as the system dispatches, so the page follows within a frame or two. */
    private static final int SCROLL_LEG_MS = 8;
    /**
     * The first leg after the slop: long enough that its move comes a frame
     * after the slop's. An app gets the moves of one frame as one event, and
     * a list takes the whole event that passes its slop as the slop, so a
     * first leg in the same frame was lost (one notch scrolled Settings by
     * 160 of 326 pixels on a Redmi Note 7).
     */
    private static final int SCROLL_FIRST_MS = 24;
    /**
     * How long the scrolling finger stays still before it lifts: past the
     * 40 ms after which Android's velocity tracker (and Chrome's, Flutter's
     * and Compose's) takes a finger for stopped.
     */
    private static final int SCROLL_STILL_MS = 50;
    /** A leg takes this part of what is left, so a notch eases out over a few frames instead of jumping. */
    private static final float SCROLL_EASE = 0.55f;
    /** What is left, up to this (dp), goes in one leg. */
    private static final float SCROLL_ALL_DP = 24f;
    /** A scrolling finger goes down at least this far (dp) from the top and bottom (status bar, navigation) and from the sides (back gestures). */
    private static final float SCROLL_EDGE_DP = 48f;
    private static final float SCROLL_SIDE_DP = 32f;

    private static final int BUTTON_LEFT = 1;
    private static final int BUTTON_MIDDLE = 2;
    private static final int BUTTON_RIGHT = 4;
    private static final int WHEEL_UP = 8;
    private static final int WHEEL_DOWN = 16;
    private static final int WHEEL_LEFT = 32;
    private static final int WHEEL_RIGHT = 64;

    private final Context ctx;
    private final HandlerThread thread;
    private final Handler h;

    // The input thread's.
    private int buttons;
    /** The size of screen the computer's coordinates are in: the picture's, from the last video message. */
    private volatile int fromW, fromH;
    private GestureDescription.StrokeDescription stroke;
    private boolean busy;
    private boolean hasPending;
    private boolean endPending;
    private float pendingX, pendingY;
    private float lastX, lastY;
    /**
     * When the position the finger last went to came in, and when the one
     * waiting came in: a leg lasts the time between the two, as the computer
     * moved, not the time between dispatches, which the system's own round
     * trips stretch (a 300 ms drag took 500 to 650 ms that way).
     */
    private long lastLegAt, pendingAt;
    // The scrolling finger (the wheel, a touchpad), the input thread's.
    /** Its last leg, which the next one continues; null while no scrolling finger is down. */
    private GestureDescription.StrokeDescription scroll;
    /** A leg of it is on its way: the next one waits for it. */
    private boolean scrollBusy;
    /** Bumped whenever the finger is given up, so that an older leg's answer is ignored. */
    private int scrollGen;
    /** Where the finger is (whole pixels), and where the next one goes down (the mouse). */
    private float scrollX, scrollY, scrollAtX, scrollAtY;
    /** How far the finger still has to go, in the screen's pixels. */
    private float scrollLeftX, scrollLeftY;
    /** Its last move, which way the last pixel goes. */
    private float scrollLastDx, scrollLastDy;
    /** When it last moved. */
    private long scrollMovedAt;
    /** The next leg is the first after the slop. */
    private boolean scrollFirst;
    /** The screen and the touch slop, read when a finger goes down. */
    private int scrollW, scrollH, scrollSlop;
    private final Runnable scrollLift = this::scrollStep;
    private boolean ctrl, shift, alt, meta;
    private String clip = "";
    /** The left button's finger went down through the helper: its moves and its lift follow it there. */
    private boolean viaHelper;
    /** Told when a press lands on a screen Android keeps from the accessibility service (no helper here). */
    private volatile java.util.function.Consumer<String> protectedTap;

    PhoneInput(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.thread = new HandlerThread("phone-input", android.os.Process.THREAD_PRIORITY_FOREGROUND);
        thread.start();
        this.h = new Handler(thread.getLooper());
        h.post(() -> {
            try {
                // Input's own priority where the phone allows it; foreground where not.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Exception ignored) {
            }
        });
    }

    void release() {
        h.post(() -> {
            endDrag();
            scrollEnd();
            // A finger the helper holds down is lifted (the helper also lifts it if its link goes).
            if (viaHelper) PhoneHelperLink.input("touch u " + lastX + " " + lastY);
            viaHelper = false;
            buttons = 0;
            thread.quitSafely();
        });
    }

    /** Who hears of a press on a screen Android protects from the accessibility service: what the screen is. */
    void onProtected(java.util.function.Consumer<String> c) {
        protectedTap = c;
    }

    /** The picture the computer now sees: its coordinates are this screen's, at this size. */
    void picture(PhoneSource.Encoding e) {
        fromW = e.screenW;
        fromH = e.screenH;
    }

    // ── the mouse ──

    /** RFB's pointer event: the buttons held now, at x, y of the computer's picture of the screen. */
    void pointer(int mask, int x, int y) {
        final long arrived = SystemClock.uptimeMillis();
        h.post(() -> {
            Point p = toScreen(x, y);
            int was = buttons;
            buttons = mask;
            pendingAt = arrived;
            int pressed = mask & ~was;
            boolean left = (mask & BUTTON_LEFT) != 0;
            boolean wasLeft = (was & BUTTON_LEFT) != 0;
            // A click on a phone that is asleep wakes it, and does no more: the
            // person sees what is there before anything is touched.
            if (pressed != 0 && asleep()) {
                wakeForInput();
                return;
            }
            if (left && !wasLeft) {
                // A scrolling finger gives way: the press's own finger cancels it on the phone.
                scrollDrop();
                viaHelper = PhoneHelperLink.input("touch d " + p.x + " " + p.y);
                if (viaHelper) {
                    lastX = p.x;
                    lastY = p.y;
                } else {
                    down(p.x, p.y);
                    String what = protectedAt(p.x, p.y);
                    java.util.function.Consumer<String> tell = protectedTap;
                    if (what != null && tell != null) tell.accept(what);
                }
            } else if (left) {
                if (viaHelper) {
                    if (p.x != lastX || p.y != lastY) PhoneHelperLink.input("touch m " + p.x + " " + p.y);
                    lastX = p.x;
                    lastY = p.y;
                } else {
                    move(p.x, p.y);
                }
            } else if (wasLeft) {
                if (viaHelper) {
                    PhoneHelperLink.input("touch u " + p.x + " " + p.y);
                    viaHelper = false;
                } else {
                    up(p.x, p.y);
                }
            }
            if ((pressed & BUTTON_RIGHT) != 0) nav("back");
            if ((pressed & BUTTON_MIDDLE) != 0) nav("home");
            // A wheel's notches as RFB has them (the page and the app send 0x13
            // instead): Android's own notch each, 64 dp, by the scrolling finger.
            if ((pressed & (WHEEL_UP | WHEEL_DOWN | WHEEL_LEFT | WHEEL_RIGHT)) != 0 && scrollReady()) {
                float notch = WHEEL_DP * ctx.getResources().getDisplayMetrics().density;
                float dx = 0, dy = 0;
                if ((pressed & WHEEL_UP) != 0) dy -= notch;
                if ((pressed & WHEEL_DOWN) != 0) dy += notch;
                if ((pressed & WHEEL_LEFT) != 0) dx -= notch;
                if ((pressed & WHEEL_RIGHT) != 0) dx += notch;
                scrollBy(p.x, p.y, dx, dy);
            }
        });
    }

    private void down(float x, float y) {
        endDrag();
        lastX = x;
        lastY = y;
        lastLegAt = pendingAt;
        stroke = new GestureDescription.StrokeDescription(path(x, y, x, y), 0, LEG_MIN_MS, true);
        dispatch(stroke);
    }

    private void move(float x, float y) {
        if (stroke == null) return;
        pendingX = x;
        pendingY = y;
        hasPending = true;
        if (!busy) step();
    }

    private void up(float x, float y) {
        if (stroke == null) return;
        pendingX = x;
        pendingY = y;
        hasPending = true;
        endPending = true;
        if (!busy) step();
    }

    /** The next leg of a live drag. */
    private void step() {
        if (stroke == null || !hasPending) return;
        hasPending = false;
        boolean last = endPending;
        endPending = false;
        // The leg lasts as long as the mouse took to get here (pendingAt), so
        // a slow drag stays slow and a flick stays a flick. A finger lifted
        // where it already is (a click, the end of a drag that stopped) lifts
        // at once: held on for the leg's length, a click would be that much
        // nearer a long press.
        boolean still = pendingX == lastX && pendingY == lastY;
        // A move to where the finger already is (a pen's pressure, a second
        // button, a jitter smaller than a pixel) gets no leg. A continued leg
        // that does not move makes no events: Android answers it as cancelled
        // and leaves the finger down, onCancelled then dropped the stroke, and
        // the release never went (on a Redmi Note 7 the finger stayed down
        // until the next touch, until 2026-11-22). The release itself, still
        // or not, goes as before; the finger's place and time stay as they are.
        if (still && !last) return;
        int ms = last && still ? LEG_MIN_MS : (int) Math.max(LEG_MIN_MS, Math.min(LEG_MAX_MS, pendingAt - lastLegAt));
        lastLegAt = pendingAt;
        Path p = path(lastX, lastY, pendingX, pendingY);
        lastX = pendingX;
        lastY = pendingY;
        try {
            GestureDescription.StrokeDescription next = stroke.continueStroke(p, 0, ms, !last);
            stroke = last ? null : next;
            dispatch(next);
        } catch (Exception e) {
            // A stroke whose predecessor was cancelled cannot be continued.
            stroke = null;
            busy = false;
        }
    }

    // ── the wheel and a touchpad: a finger that scrolls ──

    /**
     * The wheel or a touchpad ({@code 0x13}): the page at x, y of the
     * computer's picture scrolls by dx, dy of the picture's screen pixels, as
     * a browser's wheel deltas say it (a positive dy shows what is further
     * down). The computer sends what a page there would scroll, its own
     * momentum included (a touchpad's), so the phone adds none.
     */
    void scroll(int x, int y, int dx, int dy) {
        h.post(() -> {
            if (dx == 0 && dy == 0) return;
            if (PhoneHelperLink.linked() && scroll == null && !scrollBusy && stroke == null && !busy && !viaHelper) {
                // Through the helper: a mouse's wheel, as Android's own lists and pages take it
                // (AXIS_VSCROLL is up, a browser's dy is down; a unit is the scroll factor's pixels).
                if (asleep()) {
                    wakeForInput();
                    return;
                }
                Point size = screenSize();
                int fw = fromW > 0 ? fromW : size.x, fh = fromH > 0 ? fromH : size.y;
                float factor = Math.max(1f, android.view.ViewConfiguration.get(ctx).getScaledVerticalScrollFactor());
                float sx = x * (float) size.x / fw, sy = y * (float) size.y / fh;
                float hs = dx * (float) size.x / fw / factor, vs = -dy * (float) size.y / fh / factor;
                if (PhoneHelperLink.input("scroll " + sx + " " + sy + " " + hs + " " + vs)) return;
            }
            if (!scrollReady()) return;
            int fw = fromW > 0 ? fromW : scrollW, fh = fromH > 0 ? fromH : scrollH;
            scrollBy(x * (float) scrollW / fw, y * (float) scrollH / fh, dx * (float) scrollW / fw, dy * (float) scrollH / fh);
        });
    }

    /**
     * Whether a scroll may go now. Not while the left button's finger is down
     * or on its way up (a new finger would cancel it: a click lost); a phone
     * asleep is woken, and nothing more. The screen and the touch slop are
     * read here when no scrolling finger is down. Input thread.
     */
    private boolean scrollReady() {
        if (stroke != null || busy) return false;
        if (scroll == null && !scrollBusy) {
            if (asleep()) {
                wakeForInput();
                return false;
            }
            Point size = screenSize();
            scrollW = size.x;
            scrollH = size.y;
            scrollSlop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        }
        return true;
    }

    /** The page at sx, sy of the screen scrolls by dx, dy of the screen's pixels. Input thread. */
    private void scrollBy(float sx, float sy, float dx, float dy) {
        scrollAtX = sx;
        scrollAtY = sy;
        // The page moves up to show what is below: the finger goes up.
        scrollLeftX -= dx;
        scrollLeftY -= dy;
        scrollStep();
    }

    /**
     * The scrolling finger's next leg, or its lift once it has been still
     * for long enough. Each leg is a gesture of its own that continues the
     * last ({@code continueStroke}), and the next goes when it is done, with
     * all that came in meanwhile: the finger keeps up however fast the
     * wheel spins, and stays down while it does. Input thread.
     */
    private void scrollStep() {
        h.removeCallbacks(scrollLift);
        if (scrollBusy) return;
        boolean more = Math.abs(scrollLeftX) >= 1 || Math.abs(scrollLeftY) >= 1;
        if (scroll == null) {
            if (more) scrollDown();
            return;
        }
        if (more && scrollMove()) return;
        // Nothing to move by, or no room on the screen for it: the finger
        // lifts once it has been still for long enough (and a new one goes
        // down for what is left).
        long wait = scrollMovedAt + SCROLL_STILL_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            h.postDelayed(scrollLift, wait);
            return;
        }
        scrollUp();
    }

    /**
     * A finger goes down where the mouse is and moves just past the touch
     * slop, in a leg of its own. A list whose items take touches (most lists)
     * starts to scroll on the move that passes the slop and does not scroll
     * by that move, so the distance comes in the legs after it. Until
     * 2026-11-22 each notch was a swipe of its own that Android 10 delivers
     * as one move (it samples an accessibility gesture every 100 ms), and
     * such a list took all of it as its slop: on a Redmi Note 7 the wheel
     * scrolled neither the test pad's page nor Settings at all.
     */
    private void scrollDown() {
        float d = ctx.getResources().getDisplayMetrics().density;
        float side = SCROLL_SIDE_DP * d, edge = SCROLL_EDGE_DP * d;
        int x = Math.round(clamp(scrollAtX, Math.min(side, scrollW / 2f), Math.max(scrollW - 1 - side, scrollW / 2f)));
        int y = Math.round(clamp(scrollAtY, Math.min(edge, scrollH / 2f), Math.max(scrollH - 1 - edge, scrollH / 2f)));
        int slop = scrollSlop + 1;
        int sx = 0, sy = 0;
        if (Math.abs(scrollLeftY) >= Math.abs(scrollLeftX)) {
            sy = scrollLeftY < 0 ? -slop : slop;
        } else {
            sx = scrollLeftX < 0 ? -slop : slop;
        }
        GestureDescription.StrokeDescription first;
        try {
            first = new GestureDescription.StrokeDescription(path(x, y, x + sx, y + sy), 0, SCROLL_LEG_MS, true);
        } catch (Exception e) {
            DexLog.warn("phone", "scroll refused", e);
            scrollDrop();
            return;
        }
        scroll = first;
        scrollX = x + sx;
        scrollY = y + sy;
        scrollLastDx = sx;
        scrollLastDy = sy;
        scrollFirst = true;
        scrollSend(first);
    }

    /**
     * The next leg: part of what is left (all of it when it is small), to
     * whole pixels, kept on the screen. A leg that would not move the
     * finger by a whole pixel is never sent: Android answers it as
     * cancelled although the finger stays down. False when there is no
     * room left to move in.
     */
    private boolean scrollMove() {
        float lx = scrollLeftX, ly = scrollLeftY;
        float len = (float) Math.hypot(lx, ly);
        float all = SCROLL_ALL_DP * ctx.getResources().getDisplayMetrics().density;
        float take = len <= all ? len : Math.max(all, len * SCROLL_EASE);
        float nx = Math.round(clamp(scrollX + lx * take / len, 2, scrollW - 3));
        float ny = Math.round(clamp(scrollY + ly * take / len, 2, scrollH - 3));
        if (nx == scrollX && ny == scrollY) return false;
        GestureDescription.StrokeDescription next;
        try {
            next = scroll.continueStroke(path(scrollX, scrollY, nx, ny), 0, scrollFirst ? SCROLL_FIRST_MS : SCROLL_LEG_MS, true);
        } catch (Exception e) {
            DexLog.warn("phone", "scroll refused", e);
            scrollDrop();
            return true;
        }
        scrollFirst = false;
        scrollLeftX -= nx - scrollX;
        scrollLeftY -= ny - scrollY;
        scrollLastDx = nx - scrollX;
        scrollLastDy = ny - scrollY;
        scrollX = nx;
        scrollY = ny;
        scroll = next;
        scrollSend(next);
        return true;
    }

    /**
     * The finger lifts after one last pixel the way it went. That move comes
     * after the finger was still for longer than a velocity tracker waits, so
     * the tracker starts again from it alone and sees no speed: the page
     * stops where the finger does and does not fling on. A lift that only
     * waited (Android 10 does not count the lift itself) could still fling.
     */
    private void scrollUp() {
        int ex = 0, ey = 0;
        if (Math.abs(scrollLastDy) >= Math.abs(scrollLastDx)) {
            ey = scrollLastDy < 0 ? -1 : 1;
        } else {
            ex = scrollLastDx < 0 ? -1 : 1;
        }
        float nx = scrollX + ex, ny = scrollY + ey;
        if (nx < 2 || nx > scrollW - 3 || ny < 2 || ny > scrollH - 3) {
            nx = scrollX - ex;
            ny = scrollY - ey;
        }
        GestureDescription.StrokeDescription last;
        try {
            last = scroll.continueStroke(path(scrollX, scrollY, nx, ny), 0, SCROLL_LEG_MS, false);
        } catch (Exception e) {
            scrollDrop();
            return;
        }
        scroll = null;
        scrollSend(last);
    }

    /** One leg, with an answer of its own: the left button's state is never touched by it. */
    private void scrollSend(GestureDescription.StrokeDescription s) {
        AccessibilityService service = PhoneControlService.live();
        if (service == null) {
            scrollDrop();
            return;
        }
        final int gen = scrollGen;
        GestureDescription.Builder b = new GestureDescription.Builder().addStroke(s);
        if (Build.VERSION.SDK_INT >= 30) b.setDisplayId(Display.DEFAULT_DISPLAY);
        scrollBusy = true;
        boolean sent;
        try {
            sent = service.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    if (gen != scrollGen) return;
                    scrollBusy = false;
                    scrollMovedAt = SystemClock.uptimeMillis();
                    scrollStep();
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    // Android took the finger away: a touch on the phone itself, or a click's own finger.
                    if (gen == scrollGen) scrollDrop();
                }
            }, h);
        } catch (Exception e) {
            DexLog.warn("phone", "scroll refused", e);
            sent = false;
        }
        if (!sent) scrollDrop();
    }

    /** The scrolling finger is given up: what was left to scroll goes, and a leg still on its way is ignored. Input thread. */
    private void scrollDrop() {
        scrollGen++;
        scroll = null;
        scrollBusy = false;
        scrollLeftX = 0;
        scrollLeftY = 0;
        h.removeCallbacks(scrollLift);
    }

    /** The session ends: a scrolling finger still down lifts now (a leg may continue one still on its way). */
    private void scrollEnd() {
        if (scroll != null) scrollUp();
        scrollDrop();
    }

    private void dispatch(GestureDescription.StrokeDescription s) {
        busy = true;
        if (!dispatchThen(s, () -> {
            busy = false;
            step();
        })) {
            busy = false;
            stroke = null;
        }
    }

    /** @return false when the service is not there to take it */
    private boolean dispatchThen(GestureDescription.StrokeDescription s, Runnable done) {
        AccessibilityService service = PhoneControlService.live();
        if (service == null) return false;
        GestureDescription.Builder b = new GestureDescription.Builder().addStroke(s);
        if (Build.VERSION.SDK_INT >= 30) b.setDisplayId(Display.DEFAULT_DISPLAY);
        try {
            return service.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    if (done != null) done.run();
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    busy = false;
                    stroke = null;
                    hasPending = false;
                    endPending = false;
                }
            }, h);
        } catch (Exception e) {
            DexLog.warn("phone", "gesture refused", e);
            return false;
        }
    }

    /**
     * A finger still down is lifted where it is: when the computer goes, and
     * before a new press. The buttons are the computer's to say and are left
     * as they are: a press calls this after it has recorded its own button,
     * and clearing it here made every release look like nothing had been
     * pressed, so the finger stayed down until the next click lifted it
     * (every click a long press, every drag a run of new touches, until
     * 2026-11-21).
     */
    private void endDrag() {
        if (stroke != null) {
            try {
                GestureDescription.StrokeDescription last = stroke.continueStroke(path(lastX, lastY, lastX, lastY), 0, LEG_MIN_MS, false);
                stroke = null;
                dispatchThen(last, null);
            } catch (Exception ignored) {
                stroke = null;
            }
        }
        hasPending = false;
        endPending = false;
        busy = false;
    }

    /** The screen is off (the phone asleep, or locked with its screen off). */
    private boolean asleep() {
        android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isInteractive();
    }

    private void wakeForInput() {
        PhoneControlService c = PhoneControlService.live();
        if (c != null) c.wake();
    }

    // ── the keys ──

    /** The navigation keys and the screen: global actions, which is all an accessibility service is given. */
    void nav(String name) {
        AccessibilityService service = PhoneControlService.live();
        if (service == null) return;
        int action;
        switch (name) {
            case "back": action = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": action = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": action = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quicksettings": action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return;
                action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < 28) return;
                action = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            default:
                return;
        }
        h.post(() -> {
            try {
                service.performGlobalAction(action);
            } catch (Exception e) {
                DexLog.warn("phone", "global action " + name + " refused", e);
            }
        });
    }

    /** The computer's clipboard: the phone's too, and what Ctrl+V types. */
    void clip(String text) {
        if (text == null) return;
        h.post(() -> {
            clip = text;
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Remote desktop", text));
            } catch (Exception ignored) {
                // Some phones keep a background app off the clipboard; Ctrl+V still types it.
            }
        });
    }

    /** Text, typed or pasted, at the cursor of the focused field. */
    void text(String s) {
        if (s == null || s.isEmpty()) return;
        h.post(() -> {
            if (!(PhoneHelperLink.linked() && typeViaHelper(s))) commit(s);
        });
    }

    /** X11's key event: a keysym, pressed or let go. */
    void key(int keysym, boolean down) {
        h.post(() -> {
            switch (keysym) {
                case 0xffe1: case 0xffe2: shift = down; return;
                case 0xffe3: case 0xffe4: ctrl = down; return;
                case 0xffe9: case 0xffea: case 0xfe03: alt = down; return;
                case 0xffeb: case 0xffec: case 0xffe7: case 0xffe8: meta = down; return;
                default:
            }
            if (!down) return;
            // A locked phone: the number keys, Backspace and Enter press the
            // lock screen's own pad (PhoneLock), which no app can type into;
            // the pad is brought up, and the screen woken, first if need be.
            if (!ctrl && !meta && !alt) {
                String padKey = PhoneLock.keyFor(keysym);
                if (padKey != null && PhoneLock.press(ctx, padKey, (ms, r) -> h.postDelayed(r, ms))) return;
            }
            if (asleep()) {
                wakeForInput();
                return;
            }
            int code = keyCode(keysym);
            if (code == KeyEvent.KEYCODE_ESCAPE) {
                nav("back");
                return;
            }
            // The helper here: the key as a keyboard's, which every field and screen takes.
            if (PhoneHelperLink.linked() && keyViaHelper(keysym, code)) return;
            if (ctrl || meta) {
                // Shortcuts: Ctrl+V types the computer's clipboard; the rest
                // are the field's own (select all, copy, cut, undo).
                int letter = letterOf(keysym);
                if (letter == 'v') {
                    commit(clip);
                    return;
                }
                if (letter != 0) {
                    keyEvent(KeyEvent.KEYCODE_A + (letter - 'a'), KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
                    return;
                }
            }
            if (code != 0) {
                special(code);
                return;
            }
            String text = textOf(keysym);
            if (text != null && !alt) commit(text);
        });
    }

    // ── keys through the helper ──

    /** The modifiers held now, as KeyEvent's meta state. */
    private int metaNow() {
        int m = 0;
        if (shift) m |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (ctrl) m |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (alt) m |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if (meta) m |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        return m;
    }

    /**
     * A key as a keyboard's key events, through the helper. False if the helper
     * cannot type it (a character the phone's virtual keyboard map has no keys
     * for, Ctrl+V's text with one): then the service's own way.
     */
    private boolean keyViaHelper(int keysym, int code) {
        if (ctrl || meta) {
            int letter = letterOf(keysym);
            // Ctrl+V types the computer's clipboard, as the service's way does.
            if (letter == 'v') return typeViaHelper(clip);
            if (letter != 0) return press(KeyEvent.KEYCODE_A + (letter - 'a'), metaNow());
        }
        if (code != 0) return press(code, metaNow());
        String text = textOf(keysym);
        if (text == null || alt) return false;
        return typeViaHelper(text);
    }

    private boolean press(int code, int metaState) {
        return PhoneHelperLink.input("key d " + code + " " + metaState)
                && PhoneHelperLink.input("key u " + code + " " + metaState);
    }

    /** Text as the key events Android's virtual keyboard map gives for it; false if it has none for some of it. */
    private boolean typeViaHelper(String s) {
        if (s == null || s.isEmpty()) return true;
        KeyEvent[] events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(s.toCharArray());
        if (events == null) return false;
        for (KeyEvent e : events) {
            if (!PhoneHelperLink.input("key " + (e.getAction() == KeyEvent.ACTION_DOWN ? "d" : "u") + " "
                    + e.getKeyCode() + " " + e.getMetaState())) return false;
        }
        return true;
    }

    // ── screens Android keeps from the service ──

    /**
     * What the screen under (x, y) is, if it is one Android keeps from an
     * accessibility service that is not an accessibility tool: from Android
     * 14, a view marked sensitive (or one that filters touches when obscured)
     * drops such a service's gestures and leaves its node out of the tree.
     * Known by the window's app: Android's permission questions, its
     * installer, and the system's own questions (screen sharing, USB
     * debugging). Null for anything else, and before Android 14.
     */
    private String protectedAt(int x, int y) {
        if (Build.VERSION.SDK_INT < 34) return null;
        PhoneControlService s = PhoneControlService.live();
        if (s == null) return null;
        try {
            android.view.accessibility.AccessibilityWindowInfo top = null;
            android.graphics.Rect r = new android.graphics.Rect();
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                w.getBoundsInScreen(r);
                if (!r.contains(x, y)) continue;
                if (top == null || w.getLayer() > top.getLayer()) top = w;
            }
            if (top == null) return null;
            AccessibilityNodeInfo root = top.getRoot();
            CharSequence pkg = root == null ? null : root.getPackageName();
            String kind = protectedKind(pkg == null ? null : pkg.toString(), top.getType());
            if (kind != null) return kind;
            // An app's window the service is shown nothing of: Android keeps the whole of it from
            // a service that is not a tool (the permission question on Android 17, seen on an S25).
            if (root == null && top.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                CharSequence title = top.getTitle();
                return title != null && title.toString().toLowerCase(java.util.Locale.ROOT).contains("permission")
                        ? "permission" : "system";
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "permission", "install" or "system" for an app whose screens Android protects, else null. Package-private for the tests. */
    static String protectedKind(String pkg, int windowType) {
        if (pkg == null) return null;
        switch (pkg) {
            case "com.google.android.permissioncontroller":
            case "com.android.permissioncontroller":
                return "permission";
            case "com.google.android.packageinstaller":
            case "com.android.packageinstaller":
                return "install";
            case "com.android.systemui":
                // The system's own questions are activities of SystemUI's; its bars and its shade are not.
                return windowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ? "system" : null;
            default:
                return null;
        }
    }

    private void special(int code) {
        InputMethod.AccessibilityInputConnection ic = connection();
        if (ic == null) {
            // No field: the arrows and Enter move the phone's own focus.
            navigateWithoutField(code);
            return;
        }
        if (code == KeyEvent.KEYCODE_ENTER && !shift) {
            EditorInfo info = editorInfo();
            if (info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
                int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action);
                    return;
                }
            }
        }
        keyEvent(code, shift ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON : 0);
    }

    private void navigateWithoutField(int code) {
        if (Build.VERSION.SDK_INT < 33) {
            if (code == KeyEvent.KEYCODE_DEL) backspaceThroughNode();
            if (code == KeyEvent.KEYCODE_ENTER) imeEnterThroughNode();
            return;
        }
        AccessibilityService service = PhoneControlService.live();
        if (service == null) return;
        int action;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: action = AccessibilityService.GLOBAL_ACTION_DPAD_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: action = AccessibilityService.GLOBAL_ACTION_DPAD_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: action = AccessibilityService.GLOBAL_ACTION_DPAD_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: action = AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT; break;
            case KeyEvent.KEYCODE_ENTER: action = AccessibilityService.GLOBAL_ACTION_DPAD_CENTER; break;
            default: return;
        }
        service.performGlobalAction(action);
    }

    private void commit(String s) {
        if (s == null || s.isEmpty()) return;
        InputMethod.AccessibilityInputConnection ic = connection();
        if (ic != null) {
            ic.commitText(s, 1, null);
            return;
        }
        typeThroughNode(s);
    }

    private void keyEvent(int code, int metaState) {
        InputMethod.AccessibilityInputConnection ic = connection();
        if (ic == null) {
            navigateWithoutField(code);
            return;
        }
        long now = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, metaState));
        ic.sendKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, metaState));
    }

    /** The field the cursor is in, through the service's own input method (Android 13), or null. */
    private InputMethod.AccessibilityInputConnection connection() {
        if (Build.VERSION.SDK_INT < 33) return null;
        PhoneControlService service = PhoneControlService.live();
        if (service == null) return null;
        InputMethod im = service.getInputMethod();
        if (im == null || !im.getCurrentInputStarted()) return null;
        return im.getCurrentInputConnection();
    }

    private EditorInfo editorInfo() {
        if (Build.VERSION.SDK_INT < 33) return null;
        PhoneControlService service = PhoneControlService.live();
        InputMethod im = service == null ? null : service.getInputMethod();
        return im == null ? null : im.getCurrentInputEditorInfo();
    }

    // ── before Android 13: the focused field's node ──

    private void typeThroughNode(String s) {
        AccessibilityNodeInfo node = focusedEditable();
        if (node == null) return;
        CharSequence had = node.getText();
        String text = had == null ? "" : had.toString();
        int start = Math.max(0, Math.min(text.length(), node.getTextSelectionStart()));
        int end = Math.max(start, Math.min(text.length(), node.getTextSelectionEnd()));
        if (node.getTextSelectionStart() < 0) start = end = text.length();
        setText(node, text.substring(0, start) + s + text.substring(end), start + s.length());
    }

    private void backspaceThroughNode() {
        AccessibilityNodeInfo node = focusedEditable();
        if (node == null) return;
        CharSequence had = node.getText();
        String text = had == null ? "" : had.toString();
        int start = node.getTextSelectionStart() < 0 ? text.length() : Math.min(text.length(), node.getTextSelectionStart());
        int end = node.getTextSelectionEnd() < 0 ? text.length() : Math.min(text.length(), node.getTextSelectionEnd());
        if (start == end && start > 0) start = text.offsetByCodePoints(start, -1);
        setText(node, text.substring(0, start) + text.substring(end), start);
    }

    private void imeEnterThroughNode() {
        if (Build.VERSION.SDK_INT < 30) return;
        AccessibilityNodeInfo node = focusedEditable();
        if (node != null) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    private void setText(AccessibilityNodeInfo node, String value, int caret) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
    }

    private AccessibilityNodeInfo focusedEditable() {
        AccessibilityService service = PhoneControlService.live();
        if (service == null) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return focus != null && focus.isEditable() ? focus : null;
    }

    // ── keysyms ──

    /** The key codes that are not text; 0 for text. */
    static int keyCode(int keysym) {
        switch (keysym) {
            case 0xff08: return KeyEvent.KEYCODE_DEL;
            case 0xffff: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 0xff0d: case 0xff8d: return KeyEvent.KEYCODE_ENTER;
            case 0xff09: case 0xfe20: return KeyEvent.KEYCODE_TAB;
            case 0xff1b: return KeyEvent.KEYCODE_ESCAPE;
            case 0xff51: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 0xff52: return KeyEvent.KEYCODE_DPAD_UP;
            case 0xff53: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 0xff54: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 0xff50: return KeyEvent.KEYCODE_MOVE_HOME;
            case 0xff57: return KeyEvent.KEYCODE_MOVE_END;
            case 0xff55: return KeyEvent.KEYCODE_PAGE_UP;
            case 0xff56: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 0xff63: return KeyEvent.KEYCODE_INSERT;
            default: return 0;
        }
    }

    /** The text a keysym types, or null: Latin-1 as itself, the rest as X11's Unicode keysyms. */
    static String textOf(int keysym) {
        int cp;
        if ((keysym >= 0x20 && keysym <= 0x7e) || (keysym >= 0xa0 && keysym <= 0xff)) {
            cp = keysym;
        } else if ((keysym & 0xff000000) == 0x01000000) {
            cp = keysym & 0x00ffffff;
        } else if (keysym >= 0xffb0 && keysym <= 0xffb9) {
            cp = '0' + (keysym - 0xffb0); // the keypad's digits
        } else if (keysym == 0xffaa || keysym == 0xffab || keysym == 0xffad || keysym == 0xffae || keysym == 0xffaf) {
            cp = "*+?-./".charAt(keysym - 0xffaa);
        } else {
            return null;
        }
        if (cp <= 0 || !Character.isValidCodePoint(cp) || Character.isISOControl(cp)) return null;
        return new String(Character.toChars(cp));
    }

    /** The letter a keysym is, lower case, for a shortcut; 0 for none. */
    private static int letterOf(int keysym) {
        if (keysym >= 'a' && keysym <= 'z') return keysym;
        if (keysym >= 'A' && keysym <= 'Z') return keysym + ('a' - 'A');
        return 0;
    }

    // ── geometry ──

    /** The computer's picture of the screen, to the screen as it is now. */
    private Point toScreen(int x, int y) {
        Point size = screenSize();
        int fw = fromW > 0 ? fromW : size.x;
        int fh = fromH > 0 ? fromH : size.y;
        return new Point(
                (int) clamp(x * (float) size.x / fw, 0, size.x - 1),
                (int) clamp(y * (float) size.y / fh, 0, size.y - 1));
    }

    private Point screenSize() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        if (d != null) d.getRealSize(p);
        if (p.x <= 0 || p.y <= 0) p.set(1080, 2340);
        return p;
    }

    /** A tap is a one-pixel line: a path with a lone moveTo is empty to a stroke. */
    /**
     * A stroke's way from one point to the next. A finger that stays put is
     * a line of no length, which the system takes as a touch held at that
     * point (GestureDescription treats a zero-length path as a tap). It must
     * end exactly where it says: the next leg of the same finger has to start
     * there, or the system refuses it ("points mismatch") and the finger
     * stays down, which made every click a long press until 2026-11-21 (this
     * once nudged a still point one pixel to the right).
     */
    private static Path path(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return path;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
