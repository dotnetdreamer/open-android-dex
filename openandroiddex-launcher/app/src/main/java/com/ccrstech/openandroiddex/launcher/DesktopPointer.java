package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayDeque;

/**
 * The phone touchpad's pointer on the desktop: where it is, the arrow that shows it, and
 * the mouse it is to the apps there. {@link Touchpad} decides what the fingers
 * mean; this is where that lands.
 *
 * <p><b>A real mouse, not a finger.</b> {@link DexPointer} and the Web viewer put touches
 * into the phone through accessibility, because nothing else is open to an app. The pad
 * only exists while a computer is showing the desktop, and so while the window daemon is
 * up at uid 2000, which holds INJECT_EVENTS: the desktop gets what a mouse sends — hover,
 * a right button, a wheel — through the daemon's MOUSE and WHEEL, which are AppInput, the
 * injector the computer's own app windows use.
 *
 * <p><b>The arrow is ours.</b> Android draws a pointer for an input device it owns, and an
 * injected event goes to the window under it without moving any sprite. So the arrow is a
 * small window of our own on the desktop display — NOT_TOUCHABLE, so it is never what a
 * click lands on — in the style Settings → Mouse &amp; cursor picked
 * ({@link DexCursors#arrow}). It is an accessibility overlay when the caption service is
 * up, which puts it above the taskbar and its menus. Otherwise — and wherever no window of
 * ours can be seen over the apps, One UI on an untrusted display
 * ({@link LauncherActivity#overlaysBuried(Display)}) — the window daemon draws the same
 * arrow as a layer of its own, above everything there, and moves it with each MOUSE.
 *
 * <p><b>Nothing waits on the daemon.</b> What the pad does goes into an outbox that a
 * client thread of its own drains, and a move still waiting there is replaced by the next
 * one: the pointer follows the finger instead of replaying every place it has been, the
 * rule {@link WebInput} drags by. A press, a release and a key are never folded away, so a
 * quick tap is still a click and a drag still starts where the button went down.
 */
final class DesktopPointer implements PadTarget {

    private static final int MOVE = 0;
    private static final int WHEEL = 1;
    private static final int KEY = 2;
    private static final int FOCUS = 3;
    private static final int LEAVE = 4;
    private static final int CURSOR = 5;

    /** One line for the daemon. Positions are in the desktop display's pixels. */
    private static final class Op {
        final int kind;
        int x;
        int y;
        int buttons;
        int code;
        float h;
        float v;
        /** CURSOR: the arrow the daemon is to draw, or null for none. */
        DaemonArrow arrow;
        /**
         * A press or a release. Nothing is merged into one: a move folded into a press
         * would move where the button went down, which is where a drag starts from.
         */
        boolean edge;

        Op(int kind) {
            this.kind = kind;
        }
    }

    /**
     * Where the pointer was left, so the next pad opens with it there rather than in the
     * middle of the screen. Per display: a new session's display is somewhere else.
     */
    private static int lastDisplay = -1;
    private static float lastX;
    private static float lastY;

    private final Context ctx;
    private final int display;
    private final WmClient wm = new WmClient();
    /** Guarded by itself, and so is {@link #draining}. */
    private final ArrayDeque<Op> outbox = new ArrayDeque<>();
    private boolean draining;

    private float x;
    private float y;
    private int width = 1;
    private int height = 1;
    private int densityDpi = 160;
    private int buttons;
    private int speed;
    /** Between show() and hide(): the pad is in front. */
    private boolean shown;

    private View arrow;
    private WindowManager arrowWm;
    private WindowManager.LayoutParams arrowLp;
    private DexCursors.Sprite sprite;
    private boolean placeQueued;
    private final Choreographer.FrameCallback placeOnFrame = frame -> {
        placeQueued = false;
        place();
    };

    /** The arrow the daemon draws, or null when a window of ours shows it (or nothing does). */
    private volatile DaemonArrow daemonArrow;
    /** The client thread's: the connection the daemon last took the arrow on, and where. */
    private int arrowSentOn = -1;
    private int sentX;
    private int sentY;

    /** False from a line the daemon did not answer until the next one it does. */
    private volatile boolean answering = true;

    DesktopPointer(Context ctx, int display) {
        this.ctx = ctx.getApplicationContext();
        this.display = display;
        readDisplay();
        readSpeed();
        if (lastDisplay == display) {
            x = lastX;
            y = lastY;
        } else {
            x = width / 2f;
            y = height / 2f;
        }
        clampToDisplay();
    }

    int display() {
        return display;
    }

    /** Whether the daemon took the last thing it was sent. */
    @Override
    public boolean answering() {
        return answering;
    }

    @Override
    public void readSpeed() {
        speed = DexPrefs.getInt(ctx, DexPrefs.KEY_PAD_SPEED, DexPrefs.DEF_PAD_SPEED);
    }

    // ── showing ──

    /** The pad is in front: the arrow appears, and what is under it learns it is hovered. */
    @Override
    public void show() {
        shown = true;
        addArrow();
        Op hover = new Op(MOVE);
        hover.x = Math.round(x);
        hover.y = Math.round(y);
        hover.buttons = buttons;
        queue(hover);
    }

    /**
     * The pad has gone from view: the arrow goes with it, and anything it held is let go
     * — a drag left half-done would leave a window stuck to a pointer nobody can see.
     */
    void hide() {
        if (!shown) return;
        shown = false;
        buttons = 0;
        removeArrow();
        queue(new Op(LEAVE));
        lastDisplay = display;
        lastX = x;
        lastY = y;
    }

    /** The pad is closing for good. What is still in the outbox goes first. */
    @Override
    public void release() {
        hide();
        Choreographer.getInstance().removeFrameCallback(placeOnFrame);
        wm.shutdown();
    }

    /** The display changed size or density: stay on it, and at its scale. */
    @Override
    public void displayChanged() {
        int dpi = densityDpi;
        readDisplay();
        clampToDisplay();
        if (dpi != densityDpi) {
            restyle();
        } else {
            schedulePlace();
        }
    }

    /** A pointer setting changed in Settings: draw the arrow again. */
    @Override
    public void restyle() {
        if (arrow == null && daemonArrow == null) return;
        removeArrow();
        addArrow();
    }

    // ── what the pad does ──

    /**
     * The finger moved {@code dxDp}, {@code dyDp} of the phone's dp, at {@code speed} dp/ms.
     * The pointer goes that far in the desktop's dp, times the pad's speed curve — dp on
     * both sides, so a desktop drawn at twice the scale is not twice as slow to cross.
     */
    @Override
    public void moveBy(float dxDp, float dyDp, float speedDpPerMs) {
        float g = DexPointer.gain(speed, speedDpPerMs) * densityDpi / 160f;
        x = clamp(x + dxDp * g, 0, width - 1);
        y = clamp(y + dyDp * g, 0, height - 1);
        schedulePlace();
        Op op = new Op(MOVE);
        op.x = Math.round(x);
        op.y = Math.round(y);
        op.buttons = buttons;
        queue(op);
    }

    /** Hold exactly these MotionEvent.BUTTON_* down where the pointer is; 0 lets go. */
    @Override
    public void press(int next) {
        if (next == buttons) return;
        buttons = next;
        Op op = new Op(MOVE);
        op.x = Math.round(x);
        op.y = Math.round(y);
        op.buttons = next;
        op.edge = true;
        queue(op);
    }

    /** One click of {@code button} where the pointer is. */
    @Override
    public void click(int button) {
        press(buttons | button);
        press(buttons & ~button);
    }

    /** The wheel, in notches: up and right are positive. */
    @Override
    public void scroll(float h, float v) {
        Op op = new Op(WHEEL);
        op.x = Math.round(x);
        op.y = Math.round(y);
        op.h = h;
        op.v = v;
        queue(op);
    }

    /** One key, pressed and let go, for whatever on the desktop has the keyboard. */
    @Override
    public void key(int code) {
        Op op = new Op(KEY);
        op.code = code;
        queue(op);
    }

    /**
     * Give the desktop the keyboard back if the phone's own screen took it — pulling the
     * notification shade down is enough to. Sent as each touch begins, so the computer's
     * keyboard keeps typing where it was; the daemon does nothing when it need not.
     */
    @Override
    public void focusDesktop() {
        queue(new Op(FOCUS));
    }

    // ── to the daemon ──

    private void queue(Op op) {
        synchronized (outbox) {
            Op last = outbox.peekLast();
            if (last != null && last.kind == op.kind && !last.edge && !op.edge) {
                // Still waiting, so it can be brought up to date instead of followed.
                if (op.kind == MOVE && last.buttons == op.buttons) {
                    last.x = op.x;
                    last.y = op.y;
                    return;
                }
                if (op.kind == WHEEL) {
                    last.x = op.x;
                    last.y = op.y;
                    last.h += op.h;
                    last.v += op.v;
                    return;
                }
                if (op.kind == FOCUS) return;
            }
            outbox.add(op);
            if (draining) return;
            draining = true;
        }
        wm.post(this::drain);
    }

    /** The client's thread. One line at a time, each answered before the next goes. */
    private void drain() {
        while (true) {
            Op op;
            synchronized (outbox) {
                op = outbox.poll();
                if (op == null) {
                    draining = false;
                    return;
                }
            }
            if (op.kind == CURSOR) {
                if (op.arrow == null) {
                    wm.cursorGone(display);
                } else {
                    sendArrow(op.arrow, op.x, op.y);
                }
                continue;
            }
            boolean ok;
            switch (op.kind) {
                case MOVE:
                    ok = wm.mouse(display, op.x, op.y, op.buttons);
                    sentX = op.x;
                    sentY = op.y;
                    break;
                case WHEEL:
                    ok = wm.wheel(display, op.x, op.y, op.h, op.v);
                    break;
                case KEY:
                    ok = wm.key(display, op.code);
                    break;
                case FOCUS:
                    ok = wm.focus(display);
                    break;
                default:
                    ok = wm.leave(display);
                    break;
            }
            DaemonArrow drawn = daemonArrow;
            if (ok && drawn != null && arrowSentOn != wm.connections()) {
                // The daemon drew the arrow for a connection that has since gone, and it
                // went with it: this line came on a new one.
                sendArrow(drawn, sentX, sentY);
            }
            if (ok != answering) {
                answering = ok;
                DexLog.step("touchpad", ok ? "the window daemon is taking the pointer again"
                        : "the window daemon did not take the pointer — nothing reaches the desktop");
            }
        }
    }

    /** The client's thread: have the daemon draw {@code a} with its hot spot at x, y. */
    private void sendArrow(DaemonArrow a, int atX, int atY) {
        boolean ok = wm.cursor(display, atX, atY, a.hotX, a.hotY, a.png);
        // Taken or not, this connection has been asked: a daemon that refuses it is not
        // asked again on every line.
        arrowSentOn = wm.connections();
        sentX = atX;
        sentY = atY;
        if (!ok) {
            DexLog.warn("touchpad", "the window daemon would not draw the pointer on display "
                    + display + " — it moves and clicks, but cannot be seen");
        }
    }

    // ── the arrow ──

    /**
     * The arrow, from the first of three places that will take it.
     *
     * <ol>
     *   <li>The caption service's window context ({@link CaptionService#overlayContext}):
     *       an accessibility overlay, above the taskbar and every menu, with no permission
     *       needed — where a Linux app's menus go. Android 14 and later.</li>
     *   <li>The service's own display context. Below 14 a window context cannot hold an
     *       accessibility overlay — measured on a Galaxy A13, Android 12: "token null is
     *       not valid" — while an accessibility service's display context is made with
     *       the service's overlay token for that display
     *       (AccessibilityService#createDisplayContext), which is what such a window
     *       needs.</li>
     *   <li>The window daemon ({@link #drawByDaemon}) — first, where no window of ours is
     *       seen over the apps at all.</li>
     * </ol>
     *
     * Never an ordinary overlay. It sits on the very point each click aims at, and an
     * application overlay there is what Android 12's tapjacking guard calls occlusion: it
     * drops the click (adb.rs, TOUCH_GLOBAL, which the session can switch off only below
     * Android 16). Accessibility overlays and the daemon's layer are trusted, and are not.
     */
    private void addArrow() {
        if (arrow != null || daemonArrow != null) return;
        sprite = DexCursors.arrow(ctx, densityDpi);
        if (sprite == null) return;
        Display d = displayObject();
        if (d == null) return;
        if (LauncherActivity.overlaysBuried(d)) {
            DexLog.step("touchpad", "windows draw under the apps on display " + display
                    + " (Samsung, untrusted) — the window daemon draws the pointer");
            drawByDaemon();
            return;
        }
        int a11y = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        if (addArrow(CaptionService.overlayContext(display), a11y)) return;
        CaptionService service = CaptionService.live();
        if (service != null && addArrow(service.createDisplayContext(d), a11y)) return;
        DexLog.step("touchpad", "no accessibility overlay for the pointer on display "
                + display + " — the window daemon draws it instead");
        drawByDaemon();
    }

    /**
     * The arrow as a layer of the window daemon's own, at the root of the display and so
     * above everything on it: there each MOUSE moves it, in the same step as the hover, and
     * nothing here has to place it. The daemon keeps it for as long as this client's
     * connection lasts (WmClient#connections).
     */
    private void drawByDaemon() {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        sprite.bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        DaemonArrow a = new DaemonArrow(
                android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP),
                Math.round(sprite.hotX), Math.round(sprite.hotY));
        daemonArrow = a;
        Op op = new Op(CURSOR);
        op.arrow = a;
        op.x = Math.round(x);
        op.y = Math.round(y);
        op.edge = true;
        queue(op);
    }

    private boolean addArrow(Context window, int type) {
        if (window == null) return false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                sprite.bitmap.getWidth(), sprite.bitmap.getHeight(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // Without NO_LIMITS the window is kept on screen, and near the
                        // right or bottom edge the arrow's tip would stop being where the
                        // clicks go.
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        // A window animation on a pointer would fade it in on every show, and some
        // builds animate a relayout too.
        lp.windowAnimations = 0;
        lp.setTitle("Touchpad pointer");
        lp.x = Math.round(x - sprite.hotX);
        lp.y = Math.round(y - sprite.hotY);
        View v = new ArrowView(window, sprite.bitmap);
        WindowManager manager = window.getSystemService(WindowManager.class);
        try {
            manager.addView(v, lp);
        } catch (RuntimeException e) {
            DexLog.step("touchpad", "the pointer's window was refused as type " + type
                    + " (" + e.getMessage() + ") — trying the next way");
            return false;
        }
        arrow = v;
        arrowWm = manager;
        arrowLp = lp;
        return true;
    }

    private void removeArrow() {
        if (daemonArrow != null) {
            daemonArrow = null;
            Op gone = new Op(CURSOR);
            gone.edge = true;
            queue(gone);
        }
        if (arrow == null) return;
        try {
            arrowWm.removeViewImmediate(arrow);
        } catch (RuntimeException ignored) {
        }
        arrow = null;
        arrowWm = null;
        arrowLp = null;
    }

    /** Once per frame at most: the finger reports more often than the display draws. */
    private void schedulePlace() {
        if (arrow == null || placeQueued) return;
        placeQueued = true;
        Choreographer.getInstance().postFrameCallback(placeOnFrame);
    }

    private void place() {
        if (arrow == null || sprite == null) return;
        arrowLp.x = Math.round(x - sprite.hotX);
        arrowLp.y = Math.round(y - sprite.hotY);
        try {
            arrowWm.updateViewLayout(arrow, arrowLp);
        } catch (RuntimeException ignored) {
            // the display went away under it; the pad is about to close
        }
    }

    // ── the display ──

    private Display displayObject() {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        return dm == null ? null : dm.getDisplay(display);
    }

    private void readDisplay() {
        Display d = displayObject();
        if (d == null) return;
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        if (m.widthPixels > 0 && m.heightPixels > 0) {
            width = m.widthPixels;
            height = m.heightPixels;
        }
        if (m.densityDpi > 0) densityDpi = m.densityDpi;
    }

    private void clampToDisplay() {
        x = clamp(x, 0, width - 1);
        y = clamp(y, 0, height - 1);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** The arrow as the daemon is sent it: a PNG, in base64, and its hot spot. */
    private static final class DaemonArrow {
        final String png;
        final int hotX;
        final int hotY;

        DaemonArrow(String png, int hotX, int hotY) {
            this.png = png;
            this.hotX = hotX;
            this.hotY = hotY;
        }
    }

    /** The arrow: one bitmap, drawn at the window's corner. */
    private static final class ArrowView extends View {
        private final Bitmap bitmap;

        ArrowView(Context ctx, Bitmap bitmap) {
            super(ctx);
            this.bitmap = bitmap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(bitmap.getWidth(), bitmap.getHeight());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(bitmap, 0, 0, null);
        }
    }
}
