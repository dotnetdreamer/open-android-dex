package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A touchpad and a pointer, for the desktop drawn on the phone's OWN screen.
 *
 * <p>The desktop shell is usable unplugged — the phone is the display, and
 * there is no PC and no mouse on the other end. What is left is a finger on a
 * shell whose targets were drawn for a pointer: caption buttons, resize edges,
 * the corner of a freeform window. This is the way in. The bottom strip of the
 * screen becomes a touchpad, a cursor floats over everything above it, and the
 * gestures are the ones the Linux viewer's "Mouse" method already teaches —
 * drag to move, tap to click, two fingers to scroll, double-tap and drag to
 * drag. Same grammar, same pointer-speed curve, so the two surfaces do not
 * contradict each other.
 *
 * <p><b>Why the touchpad is a strip and not the whole screen.</b> Clicks are
 * real touches, put back into the display by
 * {@link android.accessibilityservice.AccessibilityService#dispatchGesture}
 * (see {@link WebInput} — this class owns none of that, it only aims it). An
 * injected touch is hit-tested like any other, so a full-screen touchable
 * overlay would catch its own clicks and never let one through. Confining the
 * pad to a strip and the cursor to everything above it makes that impossible
 * by construction rather than by a race with a flag.
 *
 * <p>The cursor window is {@code FLAG_NOT_TOUCHABLE} for the same reason: it
 * is a drawing, and it must never be what a click lands on.
 */
final class DexPointer {

    /** Pointer-speed setting range, and what the slider offers. */
    static final int MIN_SPEED = 1;
    static final int MAX_SPEED = 5;
    /** Touchpad height, as a percentage of the display. */
    static final int MIN_HEIGHT = 18;
    static final int MAX_HEIGHT = 45;

    // ── Pointer-speed curve. Lifted verbatim from dex-input.js so the phone's
    // touchpad and the Linux viewer's "Mouse" method feel like one device: a
    // base gain per speed setting, then acceleration between two finger
    // velocities. PTR_CURVE > 1 keeps slow movement close to linear, which is
    // what precision on a 5px resize edge needs.
    private static final float[] PTR_BASE = {0.8f, 1.2f, 1.6f, 2.2f, 3.0f};
    private static final float PTR_ACCEL = 2.6f;
    private static final float PTR_V0 = 0.35f;   // dp/ms where acceleration starts
    private static final float PTR_V1 = 2.2f;    // dp/ms where it saturates
    private static final float PTR_CURVE = 1.4f;

    /** Android's own long press, matched so a held tap feels native. */
    private static final int LONG_MS = 500;
    /** Past this the finger was travelling, not tapping. */
    private static final int TAP_MS = 320;
    /** Finger travel (dp) that turns a tap into a move. */
    private static final float MOVE_SLOP_DP = 10f;
    /** Second tap of a double-tap must land within this. */
    private static final int DOUBLE_MS = 300;
    /** Finger travel (dp) per wheel notch. */
    private static final float SCROLL_STEP_DP = 22f;

    private final LauncherActivity host;
    private final WindowManager wm;
    private final WebInput input;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View cursorView;
    private View padView;
    private WindowManager.LayoutParams cursorLp;

    /** Cursor position, in raw display pixels. */
    private float cx, cy;
    /** Bottom limit for the cursor: the touchpad's top edge, in display px. */
    private int floorY;

    private int speed;
    private boolean natural;

    // ── live gesture ──
    private static final int NONE = 0, MOVE = 1, SCROLL = 2, DRAG = 3;
    private int mode = NONE;
    private float lastX, lastY;
    private long downAt, lastMoveAt;
    private boolean travelled;
    private float scrollAccX, scrollAccY;
    private long lastTapAt;
    private final Runnable longPress = this::fireLongPress;

    DexPointer(LauncherActivity host) {
        this.host = host;
        this.wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
        this.input = new WebInput(host);
        readPrefs();
    }

    /**
     * Whether a pointer can work at all right now.
     *
     * <p>Two grants, and neither is ours to ask for from here: the overlay
     * permission puts the windows on screen, and the accessibility service is
     * the only thing in this process that can put a touch back into the
     * display. The dock's button says which one is missing rather than
     * toggling into a cursor that cannot click.
     */
    boolean canDrawOverlay() {
        return android.provider.Settings.canDrawOverlays(host);
    }

    boolean canInject() {
        return input.available();
    }

    boolean showing() {
        return padView != null;
    }

    // ── attach / detach ──

    boolean attach() {
        if (padView != null) return true;
        if (!canDrawOverlay()) {
            DexLog.warn("pointer", "no overlay permission — no touchpad");
            return false;
        }
        readPrefs();
        Point size = displaySize();
        try {
            addCursor(size);
            addPad(size);
        } catch (Exception e) {
            DexLog.warn("pointer", "overlay window rejected", e);
            detach();
            return false;
        }
        // Centred in the half of the screen the cursor is allowed into, so the
        // first thing the user does is not hunt for it.
        cx = size.x / 2f;
        cy = Math.max(0, floorY) / 2f;
        placeCursor();
        DexLog.step("pointer", "touchpad on — speed " + speed
                + ", " + (natural ? "natural" : "reverse") + " scrolling");
        return true;
    }

    /**
     * The touchpad's height in px on a display this tall.
     *
     * <p>Static, and the ONLY place the percentage is turned into pixels: the
     * dock floats by exactly this much (see LauncherActivity#dockLift), and a
     * second copy of the arithmetic is a dock that drifts onto the pad the
     * first time the two clamps disagree.
     */
    static int heightPx(Context ctx, int displayHeight) {
        int pct = clampInt(DexPrefs.getInt(ctx, DexPrefs.KEY_PAD_HEIGHT, DexPrefs.DEF_PAD_HEIGHT),
                MIN_HEIGHT, MAX_HEIGHT);
        return Math.round(displayHeight * pct / 100f);
    }

    void detach() {
        main.removeCallbacks(longPress);
        // A drag whose finger never came up: the stroke is open in WebInput,
        // and taking the pad away is not the same as letting go of the button.
        if (mode == DRAG) input.up(nx(), ny());
        mode = NONE;
        if (padView != null) {
            try {
                wm.removeViewImmediate(padView);
            } catch (Exception ignored) {
            }
            padView = null;
        }
        if (cursorView != null) {
            try {
                wm.removeViewImmediate(cursorView);
            } catch (Exception ignored) {
            }
            cursorView = null;
        }
    }

    /** A settings change (speed, direction, height) while the pad is up. */
    void refresh() {
        boolean was = showing();
        detach();
        if (was) attach();
    }

    private void addCursor(Point size) {
        cursorView = new CursorView(host);
        cursorLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // Without NO_LIMITS the window is laid out inside the
                        // safe area and x/y stop meaning display pixels — the
                        // cursor would then sit a status bar below the point
                        // every click is aimed at.
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorLp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(cursorView, cursorLp);
    }

    private void addPad(Point size) {
        int height = heightPx(host, size.y);
        padView = buildPad();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        // Deliberately NOT laid out edge to edge: the strip stops short of the
        // gesture pill, so a swipe up the phone's own navigation still belongs
        // to the phone and not to this.
        wm.addView(padView, lp);
        // The pad's real top edge is what the cursor is clamped to, and only
        // the layout knows it — the window manager insets this window by the
        // system bars, so it is not "display height minus height".
        floorY = size.y - height;
        padView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            if (at[1] > 0 && at[1] != floorY) {
                floorY = at[1];
                cy = Math.min(cy, floorY - 1);
                placeCursor();
            }
        });
    }

    // ── the touchpad ──

    private View buildPad() {
        DexTheme theme = DexTheme.of(host);
        LinearLayout pad = new LinearLayout(host);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        pad.setBackground(theme.surface(theme.bar(), host.dp(18)));
        pad.setElevation(host.dp(10));

        TextView hint = new TextView(host);
        hint.setText(host.getString(R.string.lx_pad_hint));
        hint.setTextColor(theme.textFaint);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, host.sp(11.5f));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(host.dp(24), 0, host.dp(24), 0);
        pad.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pad.setOnTouchListener((v, e) -> {
            onPadTouch(v, e);
            return true;
        });
        DexFonts.applyTo(host, pad);
        return pad;
    }

    private void onPadTouch(View v, MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                downAt = now;
                lastMoveAt = now;
                travelled = false;
                scrollAccX = scrollAccY = 0;
                if (now - lastTapAt <= DOUBLE_MS) {
                    // Double-tap and drag: the second press goes down at the
                    // cursor and stays down, so this drags rather than clicks.
                    mode = DRAG;
                    lastTapAt = 0;
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    input.down(nx(), ny());
                } else {
                    mode = MOVE;
                    main.postDelayed(longPress, LONG_MS);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger is always a scroll, even mid-move: the
                // alternative is a pointer that lurches when the other hand
                // arrives a frame early.
                main.removeCallbacks(longPress);
                if (mode == DRAG) input.up(nx(), ny());
                mode = SCROLL;
                travelled = false;
                downAt = now;
                scrollAccX = scrollAccY = 0;
                lastX = midX(e);
                lastY = midY(e);
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == SCROLL) {
                    if (e.getPointerCount() < 2) break;
                    onScroll(midX(e) - lastX, midY(e) - lastY);
                    lastX = midX(e);
                    lastY = midY(e);
                } else if (mode == MOVE || mode == DRAG) {
                    onMove(e.getX() - lastX, e.getY() - lastY, now);
                    lastX = e.getX();
                    lastY = e.getY();
                }
                lastMoveAt = now;
                break;

            case MotionEvent.ACTION_POINTER_UP:
                // Stay in SCROLL until the last finger leaves. Handing the
                // gesture back to MOVE here would jump the cursor by whatever
                // gap is between the two fingers.
                break;

            case MotionEvent.ACTION_UP:
                main.removeCallbacks(longPress);
                if (mode == DRAG) {
                    input.up(nx(), ny());
                } else if (mode == MOVE && !travelled && now - downAt < TAP_MS) {
                    input.tap(nx(), ny());
                    lastTapAt = now;
                } else if (mode == SCROLL && !travelled && now - downAt < TAP_MS) {
                    // Two-finger tap. Android has no right button; the long
                    // press is what opens a context menu here, and it is what
                    // the Linux viewer maps this to as well.
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    input.longPress(nx(), ny());
                }
                mode = NONE;
                break;

            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                if (mode == DRAG) input.up(nx(), ny());
                mode = NONE;
                break;

            default:
                break;
        }
    }

    /** One finger: the pointer moves, and a live drag follows it. */
    private void onMove(float dx, float dy, long now) {
        float density = host.uiDensity() / 160f;
        float travel = (float) Math.hypot(dx, dy);
        if (!travelled && travel / Math.max(0.01f, density) > MOVE_SLOP_DP) {
            travelled = true;
            main.removeCallbacks(longPress);
        }
        long dt = Math.max(1, now - lastMoveAt);
        float g = gain(travel / Math.max(0.01f, density) / dt);
        Point size = displaySize();
        cx = clamp(cx + dx * g, 0, size.x - 1);
        // floorY, not the display height: below it is the touchpad itself, and
        // a click there would be caught by the pad instead of the shell.
        cy = clamp(cy + dy * g, 0, Math.max(1, floorY) - 1);
        placeCursor();
        if (mode == DRAG) input.move(nx(), ny());
    }

    /**
     * Two fingers: wheel notches at the cursor.
     *
     * <p>One axis at a time — {@link WebInput#scroll} only honours the larger
     * one, and sending both would spend a gesture on the axis that is about to
     * be thrown away.
     */
    private void onScroll(float dx, float dy) {
        float density = host.uiDensity() / 160f;
        float step = SCROLL_STEP_DP * density;
        scrollAccX += dx;
        scrollAccY += dy;
        if (Math.abs(scrollAccY) >= step || Math.abs(scrollAccX) >= step) travelled = true;
        while (Math.abs(scrollAccY) >= step) {
            float notch = scrollAccY > 0 ? 1 : -1;
            scrollAccY -= notch * step;
            // Fingers up means content up means a wheel-down notch, which is
            // the sign WebInput.scroll inverts back out. Reverse flips it.
            input.scroll(nx(), ny(), 0, natural ? -notch : notch);
        }
        while (Math.abs(scrollAccX) >= step) {
            float notch = scrollAccX > 0 ? 1 : -1;
            scrollAccX -= notch * step;
            input.scroll(nx(), ny(), natural ? -notch : notch, 0);
        }
    }

    private void fireLongPress() {
        if (mode != MOVE || travelled) return;
        mode = NONE;
        if (padView != null) padView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        input.longPress(nx(), ny());
    }

    /** Finger velocity in dp/ms to a movement multiplier. */
    private float gain(float v) {
        float t = clamp((v - PTR_V0) / (PTR_V1 - PTR_V0), 0, 1);
        float base = PTR_BASE[clampInt(speed, MIN_SPEED, MAX_SPEED) - 1];
        return base * (1 + (PTR_ACCEL - 1) * (float) Math.pow(t, PTR_CURVE));
    }

    // ── geometry ──

    private void placeCursor() {
        if (cursorView == null || cursorLp == null) return;
        // The hotspot is the arrow's tip, which CursorView draws at its own
        // origin — so the window's corner IS the pointer, and no offset is owed.
        cursorLp.x = Math.round(cx);
        cursorLp.y = Math.round(cy);
        try {
            wm.updateViewLayout(cursorView, cursorLp);
        } catch (Exception ignored) {
        }
    }

    /** Cursor position as the 0…1 fraction {@link WebInput} injects in. */
    private float nx() {
        return cx / Math.max(1, displaySize().x);
    }

    private float ny() {
        return cy / Math.max(1, displaySize().y);
    }

    private Point displaySize() {
        DisplayManager dm = (DisplayManager) host.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        if (d != null) d.getRealSize(p);
        if (p.x <= 0 || p.y <= 0) p.set(1080, 1920);
        return p;
    }

    private static float midX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2f;
    }

    private static float midY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2f;
    }

    private void readPrefs() {
        speed = clampInt(DexPrefs.getInt(host, DexPrefs.KEY_PAD_SPEED, DexPrefs.DEF_PAD_SPEED),
                MIN_SPEED, MAX_SPEED);
        natural = DexPrefs.getBool(host, DexPrefs.KEY_PAD_NATURAL, DexPrefs.DEF_PAD_NATURAL);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * The pointer itself: the same arrow the desktop's own cursor draws, at a
     * fixed size.
     *
     * <p>Not a {@link DexCursors} bitmap. Those are {@code PointerIcon}s, which
     * only exist where a real mouse is driving the display — the case this
     * whole class is for is the one where there is none.
     */
    private static final class CursorView extends View {
        private final Path arrow = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        CursorView(LauncherActivity host) {
            super(host);
            size = host.dp(26);
            float w = size * 0.62f;
            arrow.moveTo(0, 0);
            arrow.lineTo(0, size * 0.80f);
            arrow.lineTo(w * 0.34f, size * 0.60f);
            arrow.lineTo(w * 0.56f, size * 0.98f);
            arrow.lineTo(w * 0.80f, size * 0.88f);
            arrow.lineTo(w * 0.58f, size * 0.51f);
            arrow.lineTo(w, size * 0.47f);
            arrow.close();
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            edge.setColor(0xFF10151F);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, size / 16f));
            edge.setStrokeJoin(Paint.Join.ROUND);
            // A pointer over a white panel is invisible without this, and the
            // shell has both light and dark surfaces under it.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            fill.setShadowLayer(size / 8f, 0, size / 24f, 0x66000000);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(size, size + size / 8);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, edge);
        }
    }
}
