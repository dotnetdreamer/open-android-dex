package com.ccrstech.openandroiddex.launcher;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;

/**
 * What fingers on the phone's screen mean when the screen is the desktop's touchpad
 * ({@link Touchpad}).
 *
 * <p>The grammar is the Linux viewer's Mouse method (dex-input.js), constant for constant
 * where the two share one, so the phone does not teach two vocabularies:
 * <ul>
 *   <li>one finger moves the pointer, once it has gone further than a tap would;</li>
 *   <li>a tap clicks. Taps are never held back to see if a second follows: two quick
 *       taps are two quick clicks, and the app decides they were a double-click;</li>
 *   <li>double-tap and slide holds the button down — to select text, or to drag a
 *       window by its bar;</li>
 *   <li>hold a finger still and the release is a right-click. Slide instead, and the
 *       hold becomes a drag;</li>
 *   <li>two fingers scroll at the pointer, and tapping with two is a right-click;</li>
 *   <li>three fingers swipe or tap: the five gestures Settings → Touchpad maps for the
 *       laptop's touchpad as well (gestures/mod.rs), and recognised the way it does —
 *       on the centre of the fingers, fired the moment the threshold is crossed rather
 *       than on lift, once per touch.</li>
 * </ul>
 *
 * <p>Pinching does nothing. Android has no mouse event that zooms: a wheel with Ctrl held
 * zooms a page in Chrome and almost nothing else, and two fingers spreading apart must at
 * least not scroll by accident.
 *
 * <p>Everything here is in the pad's own dp, and on the main thread.
 */
final class TouchpadGestures {

    interface Listener {
        /** A finger landed on an empty pad: whatever comes next begins now. */
        void onTouchStart();

        /** Move the pointer this far, in the phone's dp, the finger going {@code speed} dp/ms. */
        void onPoint(float dxDp, float dyDp, float speed);

        /** Exactly these MotionEvent.BUTTON_* are now held down; 0 is let go. */
        void onButtons(int buttons);

        /** One click of {@code button}. */
        void onClick(int button);

        /** The wheel, in notches: positive {@code v} is up, positive {@code h} right. */
        void onWheel(float h, float v);

        /** A three-finger gesture, by its Settings slot: "gest3up" … "gest3tap". */
        void onGesture(String slot);

        /** A held finger has made the next release a right-click, or no longer does. */
        void onArmed(boolean armed);
    }

    // ── the Linux viewer's (dex-input.js) ──
    private static final int LONG_MS = 500;
    private static final float LONG_SLOP_DP = 14f;
    private static final float MOVE_SLOP_DP = 12f;
    /** noVNC decides at 50; 60 kills the misfire. */
    private static final int TWO_DECIDE_MS = 60;
    private static final float PINCH_SLOP_DP = 24f;
    private static final float PAN_SLOP_DP = 12f;
    private static final int DOUBLE_MS = 300;
    private static final float DOUBLE_SLOP_DP = 40f;

    // ── this pad's ──
    /** A two-finger tap: lifted within this of the second finger landing, unmoved. */
    private static final int TWO_TAP_MS = 300;
    /**
     * Finger travel per wheel notch. An app scrolls 64 dp or so for a notch, so a page
     * moves about half again as far as the fingers did — the viewer's SCROLL_GAIN is 1.25.
     */
    private static final float NOTCH_DP = 40f;
    /**
     * Three fingers: how far their centre travels before a swipe fires. About 10 mm,
     * which is where the laptop's 10% of a pad's width commits (SWIPE_FRAC).
     */
    private static final float SWIPE_DP = 60f;
    /** …and how still, and how brief, a three-finger tap is: the laptop's 3% and 300 ms. */
    private static final float SWIPE_TAP_SLOP_DP = 20f;
    private static final int SWIPE_TAP_MS = 300;
    /** Two fingers still going this fast (dp/ms) as they lift keep the page going. */
    private static final float FLING_MIN = 0.25f;
    /** …slowing to 1/e every this many ms, the platform's own fling decay … */
    private static final float FLING_TAU_MS = 325f;
    /** …until they are going slower than this. */
    private static final float FLING_STOP = 0.02f;
    /** Fingers that stopped this long before lifting have no fling left in them. */
    private static final int FLING_STALE_MS = 50;

    private static final int IDLE = 0;
    /** One finger, not yet a tap, a move, a hold or a drag. */
    private static final int DOWN1 = 1;
    /** One finger held still: the release right-clicks, a slide drags. */
    private static final int ARMED = 2;
    private static final int MOVE = 3;
    /** One finger, with the button held. */
    private static final int DRAG = 4;
    /** Two fingers, not yet a tap, a scroll or a pinch. */
    private static final int TWO = 5;
    private static final int SCROLL = 6;
    private static final int PINCH = 7;
    private static final int THREE = 8;
    /** What is left of a touch that has done its thing: it starts nothing more. */
    private static final int DEAD = 9;

    private final Listener out;
    /** px per dp on the pad's display. */
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hold = this::onHold;
    private boolean natural = DexPrefs.DEF_PAD_NATURAL;

    private int state = IDLE;
    // one finger
    private int primary = -1;
    private float p0x;
    private float p0y;
    private float lastX;
    private float lastY;
    private long lastAt;
    /** The finger's speed in dp/ms, smoothed as the viewer smooths it. */
    private float vel;
    /** This press is the second of a double tap. */
    private boolean dbl;
    private long lastTapAt;
    private float lastTapX;
    private float lastTapY;
    // two fingers and more
    private long groupAt;
    private float c0x;
    private float c0y;
    private float spread0;
    private float cLastX;
    private float cLastY;
    private long cLastAt;
    private float flingVx;
    private float flingVy;
    private boolean fired;
    private boolean drifted;
    // the fling
    private boolean flinging;
    private long flingAt;
    private final Choreographer.FrameCallback flingFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!flinging) return;
            long now = frameTimeNanos / 1_000_000L;
            if (flingAt == 0) {
                flingAt = now;
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            float dt = Math.max(1, now - flingAt);
            flingAt = now;
            float decay = (float) Math.exp(-dt / FLING_TAU_MS);
            flingVx *= decay;
            flingVy *= decay;
            if (Math.hypot(flingVx, flingVy) < FLING_STOP) {
                flinging = false;
                return;
            }
            wheel(flingVx * dt, flingVy * dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    TouchpadGestures(Listener out, float density) {
        this.out = out;
        this.density = Math.max(0.1f, density);
    }

    /** Natural: the page follows the fingers. Reverse: a wheel's way round. */
    void setNatural(boolean natural) {
        this.natural = natural;
    }

    /** One event from the pad's surface. */
    void onTouch(MotionEvent e) {
        long now = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down(e, now);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(e, now);
                break;
            case MotionEvent.ACTION_MOVE:
                move(e, now);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(e, now);
                break;
            case MotionEvent.ACTION_UP:
                end(now, false);
                break;
            case MotionEvent.ACTION_CANCEL:
                end(now, true);
                break;
            default:
                break;
        }
    }

    /**
     * The pad is going away mid-touch — a call, Home — and gets no lift to end on.
     * Nothing it was holding may stay held, and nothing it was about to do happens.
     */
    void cancel() {
        stopFling();
        end(0, true);
    }

    // ── one finger ──

    private void down(MotionEvent e, long now) {
        stopFling();
        out.onTouchStart();
        primary = e.getPointerId(0);
        p0x = lastX = e.getX(0);
        p0y = lastY = e.getY(0);
        lastAt = now;
        vel = 0;
        dbl = lastTapAt > 0 && now - lastTapAt < DOUBLE_MS
                && dist(p0x, p0y, lastTapX, lastTapY) < DOUBLE_SLOP_DP * density;
        state = DOWN1;
        main.removeCallbacks(hold);
        main.postDelayed(hold, LONG_MS);
    }

    private void onHold() {
        if (state != DOWN1) return;
        if (dist(lastX, lastY, p0x, p0y) > LONG_SLOP_DP * density) return;
        state = ARMED;
        out.onArmed(true);
    }

    private void move(MotionEvent e, long now) {
        switch (state) {
            case DOWN1:
            case ARMED: {
                int i = e.findPointerIndex(primary);
                if (i < 0) return;
                float x = e.getX(i);
                float y = e.getY(i);
                float slop = (state == ARMED ? LONG_SLOP_DP : MOVE_SLOP_DP) * density;
                if (dist(x, y, p0x, p0y) <= slop) {
                    track(x, y, now);
                    return;
                }
                main.removeCallbacks(hold);
                if (state == ARMED) {
                    // Held, then moved: the hold becomes a drag — "long press and
                    // drag to select".
                    out.onArmed(false);
                    state = DRAG;
                    out.onButtons(MotionEvent.BUTTON_PRIMARY);
                } else if (dbl) {
                    // The second press of a double tap, now moving: hold the button.
                    dbl = false;
                    lastTapAt = 0;
                    state = DRAG;
                    out.onButtons(MotionEvent.BUTTON_PRIMARY);
                } else {
                    state = MOVE;
                }
                // The slop itself never moves the pointer, or every tap would nudge it.
                track(x, y, now);
                return;
            }
            case MOVE:
            case DRAG: {
                int i = e.findPointerIndex(primary);
                if (i < 0) return;
                float x = e.getX(i);
                float y = e.getY(i);
                float dx = (x - lastX) / density;
                float dy = (y - lastY) / density;
                long dt = Math.max(1, now - lastAt);
                vel = 0.6f * vel + 0.4f * ((float) Math.hypot(dx, dy) / dt);
                track(x, y, now);
                if (dx != 0 || dy != 0) out.onPoint(dx, dy, vel);
                return;
            }
            case TWO: {
                if (e.getPointerCount() < 2 || now - groupAt < TWO_DECIDE_MS) return;
                float cx = centreX(e, -1);
                float cy = centreY(e, -1);
                if (Math.abs(spread(e) - spread0) >= PINCH_SLOP_DP * density) {
                    state = PINCH;
                } else if (dist(cx, cy, c0x, c0y) >= PAN_SLOP_DP * density) {
                    // Measured from where the decision was made, as the viewer's pan is.
                    state = SCROLL;
                    cLastX = cx;
                    cLastY = cy;
                    cLastAt = now;
                }
                return;
            }
            case SCROLL: {
                float cx = centreX(e, -1);
                float cy = centreY(e, -1);
                float dx = (cx - cLastX) / density;
                float dy = (cy - cLastY) / density;
                long dt = Math.max(1, now - cLastAt);
                flingVx = 0.6f * flingVx + 0.4f * (dx / dt);
                flingVy = 0.6f * flingVy + 0.4f * (dy / dt);
                cLastX = cx;
                cLastY = cy;
                cLastAt = now;
                wheel(dx, dy);
                return;
            }
            case THREE: {
                float dx = (centreX(e, -1) - c0x) / density;
                float dy = (centreY(e, -1) - c0y) / density;
                float travel = Math.max(Math.abs(dx), Math.abs(dy));
                if (travel > SWIPE_TAP_SLOP_DP) drifted = true;
                if (fired || travel < SWIPE_DP) return;
                fired = true;
                String kind = Math.abs(dx) > Math.abs(dy)
                        ? (dx > 0 ? "right" : "left")
                        : (dy > 0 ? "down" : "up");
                out.onGesture("gest3" + kind);
                return;
            }
            default:
                return;
        }
    }

    private void track(float x, float y, long now) {
        lastX = x;
        lastY = y;
        lastAt = now;
    }

    // ── more fingers ──

    private void pointerDown(MotionEvent e, long now) {
        int n = e.getPointerCount();
        // A second finger during a drag is a palm, not a gesture: the drag holds a
        // button down and must not be dropped by it. The viewer reads it the same way.
        if (state == DRAG && n == 2) return;
        main.removeCallbacks(hold);
        if (n >= 3) {
            if (state == THREE) {
                // Another finger: the centre jumps by it, and the swipe must not.
                int joined = e.getActionIndex();
                c0x += centreX(e, -1) - centreX(e, joined);
                c0y += centreY(e, -1) - centreY(e, joined);
                return;
            }
            if (state == ARMED) out.onArmed(false);
            if (state == DRAG) out.onButtons(0);
            state = THREE;
            groupAt = now;
            c0x = centreX(e, -1);
            c0y = centreY(e, -1);
            fired = false;
            drifted = false;
            return;
        }
        if (state == DOWN1 || state == ARMED || state == MOVE) {
            if (state == ARMED) out.onArmed(false);
            state = TWO;
            groupAt = now;
            c0x = centreX(e, -1);
            c0y = centreY(e, -1);
            spread0 = spread(e);
            flingVx = 0;
            flingVy = 0;
        }
    }

    private void pointerUp(MotionEvent e, long now) {
        int gone = e.getActionIndex();
        switch (state) {
            case TWO:
                // The first of two fingers lifting ends a two-finger touch; still
                // undecided, and quick, it was a tap.
                if (now - groupAt <= TWO_TAP_MS) out.onClick(MotionEvent.BUTTON_SECONDARY);
                state = DEAD;
                return;
            case SCROLL:
                startFling(now);
                state = DEAD;
                return;
            case PINCH:
                state = DEAD;
                return;
            case THREE:
                // Fingers rarely leave together, so a swipe goes on with the ones that
                // are left, its origin moved by however far the centre jumps without
                // this one.
                c0x += centreX(e, gone) - centreX(e, -1);
                c0y += centreY(e, gone) - centreY(e, -1);
                return;
            case DRAG:
                // The palm a drag ignored, lifting — or the dragging finger, with the palm
                // left down: the drag carries on under the one that is left.
                if (e.getPointerId(gone) == primary && e.getPointerCount() > 1) {
                    int keep = gone == 0 ? 1 : 0;
                    primary = e.getPointerId(keep);
                    track(e.getX(keep), e.getY(keep), now);
                }
                return;
            default:
                return;
        }
    }

    // ── the end ──

    private void end(long now, boolean cancelled) {
        main.removeCallbacks(hold);
        switch (state) {
            case DOWN1:
                // Still undecided at the lift: no further than a tap would go, and up
                // before the hold armed.
                if (!cancelled) {
                    out.onClick(MotionEvent.BUTTON_PRIMARY);
                    lastTapAt = now;
                    lastTapX = p0x;
                    lastTapY = p0y;
                }
                break;
            case ARMED:
                out.onArmed(false);
                if (!cancelled) out.onClick(MotionEvent.BUTTON_SECONDARY);
                break;
            case DRAG:
                out.onButtons(0);
                break;
            case SCROLL:
                if (!cancelled) startFling(now);
                break;
            case THREE:
                if (!cancelled && !fired && !drifted && now - groupAt <= SWIPE_TAP_MS) {
                    out.onGesture("gest3tap");
                }
                break;
            default:
                break;
        }
        state = IDLE;
        primary = -1;
    }

    // ── the wheel ──

    /**
     * Fingers moved {@code dxDp}, {@code dyDp}: the wheel, in notches. Natural: the page
     * follows the fingers — fingers going down pull it down, which is scrolling up,
     * AXIS_VSCROLL's positive; going right brings in what is to the left, AXIS_HSCROLL's
     * negative. Reverse turns both round, as a wheel does.
     */
    private void wheel(float dxDp, float dyDp) {
        if (dxDp == 0 && dyDp == 0) return;
        float sign = natural ? 1f : -1f;
        out.onWheel(-dxDp / NOTCH_DP * sign, dyDp / NOTCH_DP * sign);
    }

    private void startFling(long now) {
        if (now - cLastAt > FLING_STALE_MS) return;
        if (Math.hypot(flingVx, flingVy) < FLING_MIN) return;
        flinging = true;
        flingAt = 0;
        Choreographer.getInstance().postFrameCallback(flingFrame);
    }

    private void stopFling() {
        if (!flinging) return;
        flinging = false;
        Choreographer.getInstance().removeFrameCallback(flingFrame);
    }

    // ── geometry ──

    /** The fingers' centre, leaving out pointer index {@code skip} (-1: none). */
    private static float centreX(MotionEvent e, int skip) {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sum += e.getX(i);
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private static float centreY(MotionEvent e, int skip) {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sum += e.getY(i);
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** How far apart the first two fingers are. */
    private static float spread(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return dist(e.getX(0), e.getY(0), e.getX(1), e.getY(1));
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
