package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Clicks and typing from the browser, put back into the phone.
 *
 * <p><b>Why this is accessibility and not input injection.</b>
 * {@code INJECT_EVENTS} is a signature permission; nothing this APK can do
 * earns it, and the {@code wmd} daemon's uid 2000 does not help either — the
 * measurement in the window-daemon notes is that {@code input -d} on the
 * desktop's virtual display never lands. What an app CAN drive is
 * {@link AccessibilityService#dispatchGesture}, which is a real touch as far as
 * every app on the phone is concerned, and {@code performGlobalAction} for the
 * navigation keys. The launcher already runs an accessibility service for the
 * window captions, so the capability was one XML attribute away.
 *
 * <p>The cost of that choice, stated plainly because the UI has to say it too:
 * a mouse becomes a finger. There is no hover, no cursor of the phone's own,
 * and no arbitrary key codes — typing goes into whatever text field currently
 * holds input focus, through the accessibility node, which works in ordinary
 * apps and does nothing in a game or a canvas that never exposes one.
 *
 * <p><b>Drags are a queue, not a call.</b> A continued stroke may only be
 * dispatched after the previous one has completed, so pointer moves are
 * coalesced: the newest position wins and everything older is discarded. That
 * is also the behaviour you want — a drag should follow the pointer, not
 * replay every place it has been.
 */
final class WebInput {

    /** Tap length. Long enough to register everywhere, short enough not to feel laggy. */
    private static final int TAP_MS = 60;
    /** What the phone considers a long press, plus a margin. */
    private static final int LONG_PRESS_MS = 600;
    /** One leg of a scroll. */
    private static final int SCROLL_MS = 100;
    /** Pixels of travel per wheel notch. */
    private static final float WHEEL_PX = 120f;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Live drag, or null between them. Touched only on the main thread. */
    private GestureDescription.StrokeDescription stroke;
    private boolean busy;
    private boolean hasPending;
    private boolean endPending;
    private float pendingX;
    private float pendingY;
    private float lastX;
    private float lastY;
    private long lastDispatch;

    WebInput(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Whether control is possible at all right now — the caption service has
     * to be running, because it is the only thing here with a way in.
     */
    boolean available() {
        return CaptionService.live() != null;
    }

    // ── pointer ──

    void tap(float nx, float ny) {
        stroke(nx, ny, nx, ny, TAP_MS);
    }

    void longPress(float nx, float ny) {
        stroke(nx, ny, nx, ny, LONG_PRESS_MS);
    }

    void down(float nx, float ny) {
        main.post(() -> {
            cancelDrag();
            Point p = toPixels(nx, ny);
            lastX = p.x;
            lastY = p.y;
            Path path = path(p.x, p.y, p.x, p.y);
            stroke = new GestureDescription.StrokeDescription(path, 0, 16, true);
            lastDispatch = SystemClock.uptimeMillis();
            dispatch(stroke);
        });
    }

    void move(float nx, float ny) {
        main.post(() -> {
            if (stroke == null) return;
            Point p = toPixels(nx, ny);
            pendingX = p.x;
            pendingY = p.y;
            hasPending = true;
            if (!busy) step();
        });
    }

    void up(float nx, float ny) {
        main.post(() -> {
            if (stroke == null) return;
            Point p = toPixels(nx, ny);
            pendingX = p.x;
            pendingY = p.y;
            hasPending = true;
            endPending = true;
            if (!busy) step();
        });
    }

    /**
     * A wheel notch, as a swipe in the opposite direction — scrolling content
     * down means dragging it up. Horizontal wheels are honoured too; a
     * touchpad sends both axes at once and only the larger one is worth a
     * gesture.
     */
    void scroll(float nx, float ny, float dx, float dy) {
        Point from = toPixels(nx, ny);
        float travelX = -dx * WHEEL_PX;
        float travelY = -dy * WHEEL_PX;
        if (Math.abs(travelX) > Math.abs(travelY)) {
            travelY = 0;
        } else {
            travelX = 0;
        }
        Point size = displaySize();
        float toX = clamp(from.x + travelX, 0, size.x - 1);
        float toY = clamp(from.y + travelY, 0, size.y - 1);
        strokePixels(from.x, from.y, toX, toY, SCROLL_MS);
    }

    private void stroke(float nx, float ny, float nx2, float ny2, int durationMs) {
        Point a = toPixels(nx, ny);
        Point b = toPixels(nx2, ny2);
        strokePixels(a.x, a.y, b.x, b.y, durationMs);
    }

    private void strokePixels(float x1, float y1, float x2, float y2, int durationMs) {
        main.post(() -> {
            cancelDrag();
            try {
                dispatch(new GestureDescription.StrokeDescription(
                        path(x1, y1, x2, y2), 0, durationMs));
            } catch (Exception e) {
                DexLog.warn("web", "gesture refused", e);
            }
        });
    }

    /** Main thread. Sends the next leg of a live drag. */
    private void step() {
        if (stroke == null || !hasPending) return;
        hasPending = false;
        boolean last = endPending;
        endPending = false;
        long now = SystemClock.uptimeMillis();
        // The leg lasts as long as the pointer took to get here, so a slow
        // drag stays slow and a flick stays a flick — clamped, because a leg
        // shorter than a frame is not dispatchable and one longer than a
        // fifth of a second feels detached from the mouse.
        int duration = (int) clamp(now - lastDispatch, 16, 200);
        lastDispatch = now;
        Path path = path(lastX, lastY, pendingX, pendingY);
        lastX = pendingX;
        lastY = pendingY;
        try {
            GestureDescription.StrokeDescription next =
                    stroke.continueStroke(path, 0, duration, !last);
            stroke = last ? null : next;
            dispatch(next);
        } catch (Exception e) {
            // A stroke whose predecessor was cancelled cannot be continued.
            stroke = null;
            busy = false;
        }
    }

    private void dispatch(GestureDescription.StrokeDescription s) {
        AccessibilityService service = CaptionService.live();
        if (service == null) return;
        GestureDescription.Builder builder = new GestureDescription.Builder().addStroke(s);
        if (Build.VERSION.SDK_INT >= 30) {
            // The display MediaProjection is mirroring — which is the phone's
            // own. Spelled out rather than left to the default because the same
            // call is what a future desktop-display viewer would need changed.
            builder.setDisplayId(Display.DEFAULT_DISPLAY);
        }
        busy = true;
        boolean sent = service.dispatchGesture(builder.build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        busy = false;
                        step();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        busy = false;
                        stroke = null;
                        hasPending = false;
                        endPending = false;
                    }
                }, main);
        if (!sent) {
            busy = false;
            stroke = null;
        }
    }

    private void cancelDrag() {
        stroke = null;
        hasPending = false;
        endPending = false;
        busy = false;
    }

    // ── keyboard ──

    /**
     * Type into whatever holds input focus.
     *
     * SET_TEXT with the whole field rather than a per-character key event,
     * because an accessibility service has no key events to send: the node is
     * the only handle on the text. Appending to what is already there is what
     * makes typing feel like typing instead of like overwriting.
     */
    void text(String s) {
        if (s == null || s.isEmpty()) return;
        main.post(() -> {
            AccessibilityNodeInfo node = focusedEditable();
            if (node == null) return;
            CharSequence existing = node.getText();
            String base = existing == null ? "" : existing.toString();
            setText(node, base + s);
            node.recycle();
        });
    }

    /** Backspace: one character off the end of the focused field. */
    void backspace() {
        main.post(() -> {
            AccessibilityNodeInfo node = focusedEditable();
            if (node == null) return;
            CharSequence existing = node.getText();
            String base = existing == null ? "" : existing.toString();
            if (!base.isEmpty()) setText(node, base.substring(0, base.length() - 1));
            node.recycle();
        });
    }

    private void setText(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        // Put the caret back at the end; SET_TEXT leaves it wherever the app
        // decides, which on some keyboards-aware fields is the start.
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length());
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length());
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
    }

    private AccessibilityNodeInfo focusedEditable() {
        AccessibilityService service = CaptionService.live();
        if (service == null) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        try {
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) return focus;
            if (focus != null) focus.recycle();
            return null;
        } finally {
            root.recycle();
        }
    }

    /**
     * The keys that are not text. Everything here is a global action, which is
     * the whole set an accessibility service is given — there is no way to send
     * a KEYCODE_* to the foreground app.
     */
    boolean key(String name) {
        AccessibilityService service = CaptionService.live();
        if (service == null) return false;
        int action;
        switch (name) {
            case "back":
                action = AccessibilityService.GLOBAL_ACTION_BACK;
                break;
            case "home":
                action = AccessibilityService.GLOBAL_ACTION_HOME;
                break;
            case "recents":
                action = AccessibilityService.GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quicksettings":
                action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "power":
                action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
                break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return false;
                action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < 30) return false;
                action = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            case "enter":
                return imeEnter();
            case "backspace":
                backspace();
                return true;
            default:
                return false;
        }
        final int a = action;
        main.post(() -> {
            try {
                service.performGlobalAction(a);
            } catch (Exception e) {
                DexLog.warn("web", "global action " + name + " refused", e);
            }
        });
        return true;
    }

    /** Enter = the field's own IME action (search, send, next), where it has one. */
    private boolean imeEnter() {
        if (Build.VERSION.SDK_INT < 30) return false;
        main.post(() -> {
            AccessibilityNodeInfo node = focusedEditable();
            if (node == null) return;
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            node.recycle();
        });
        return true;
    }

    // ── geometry ──

    /**
     * Normalised (0…1) browser coordinates to display pixels.
     *
     * The page works in fractions of the picture rather than pixels precisely
     * so this conversion can use the display's CURRENT size: the stream is
     * scaled, the phone may have rotated since the frame was drawn, and a
     * pixel coordinate computed on the other end would land in the wrong place
     * in both cases.
     */
    private Point toPixels(float nx, float ny) {
        Point size = displaySize();
        return new Point(
                (int) clamp(nx * size.x, 0, size.x - 1),
                (int) clamp(ny * size.y, 0, size.y - 1));
    }

    private Point displaySize() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        if (d != null) d.getRealSize(p);
        if (p.x <= 0 || p.y <= 0) p.set(1080, 1920);
        return p;
    }

    /**
     * AOSP's own click idiom: a path with a single moveTo is empty as far as
     * {@link GestureDescription.StrokeDescription} is concerned, so a tap is a
     * one-pixel line.
     */
    private static Path path(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 == x2 && y1 == y2) {
            path.lineTo(x2 + 1, y2);
        } else {
            path.lineTo(x2, y2);
        }
        return path;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
