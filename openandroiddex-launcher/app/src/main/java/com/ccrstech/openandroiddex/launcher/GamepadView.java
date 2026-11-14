package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's screen as a game controller ({@link PhoneGamepad}): two sticks, a d-pad,
 * A B X Y, the shoulders and the triggers, Back, Start and the guide button, laid out as
 * an Xbox pad is, and what the fingers on them hold, as the gamepad's report has it
 * (the app server's AppGamepads: buttons, hat, four stick axes, two triggers).
 *
 * <p><b>Laid out landscape.</b> The window asks Android for a landscape screen; where it is
 * given a portrait one anyway, the whole controller is drawn turned a quarter, for a phone
 * held with its top to the left, and each finger is turned back the same way.
 *
 * <p><b>What a finger does.</b> A finger belongs to what it first came down on until it
 * lifts. On a stick, the stick is centred where the finger lands, so a thumb never has to
 * find the middle, and follows a finger that goes past its edge; a quick tap on it that
 * barely moved clicks the stick (L3, R3). On the d-pad, the direction is where the finger
 * is from its middle — eight of them, the four straight ones wider. On A B X Y, the
 * buttons under the finger, as it slides — between two, both. Everything else is held
 * while the finger is down, wherever it slides. A finger on nothing does nothing.
 */
final class GamepadView extends View {

    /** The report's buttons (AppGamepads.DESCRIPTOR, the Xbox 360 key layout). */
    static final int A = 0x0001;
    static final int B = 0x0002;
    static final int X = 0x0008;
    static final int Y = 0x0010;
    static final int LB = 0x0040;
    static final int RB = 0x0080;
    static final int BACK = 0x0400;
    static final int START = 0x0800;
    static final int GUIDE = 0x1000;
    static final int L3 = 0x2000;
    static final int R3 = 0x4000;

    /** What the controller holds now. */
    static final class State {
        int buttons;
        /** 0 centred, 1 up, then clockwise to 8 up-left. */
        int hat;
        /** The sticks, −32768…32767: right and down positive. */
        int lx;
        int ly;
        int rx;
        int ry;
        /** The triggers, 0…255. */
        int lt;
        int rt;

        boolean same(State o) {
            return o != null && buttons == o.buttons && hat == o.hat && lx == o.lx && ly == o.ly
                    && rx == o.rx && ry == o.ry && lt == o.lt && rt == o.rt;
        }

        /** The same buttons, hat and triggers: two such states differ only in where the sticks are. */
        boolean sameEdges(State o) {
            return o != null && buttons == o.buttons && hat == o.hat && lt == o.lt && rt == o.rt;
        }

        boolean neutral() {
            return buttons == 0 && hat == 0 && lx == 0 && ly == 0 && rx == 0 && ry == 0 && lt == 0 && rt == 0;
        }

        State copy() {
            State s = new State();
            s.buttons = buttons;
            s.hat = hat;
            s.lx = lx;
            s.ly = ly;
            s.rx = rx;
            s.ry = ry;
            s.lt = lt;
            s.rt = rt;
            return s;
        }
    }

    interface Listener {
        /** The fingers changed what is held. */
        void onState(State s);

        /** A finger came down: the controller is in use. */
        void onTouched();

        /** ✕. */
        void onClose();
    }

    /** A stick's dead zone, of its reach: a resting thumb holds it still. */
    private static final float DEAD = 0.10f;
    /** A stick tapped this quickly, moved no farther than this of its reach, is clicked. */
    private static final long CLICK_MS = 220;
    private static final float CLICK_REACH = 0.25f;
    /** How long a stick's click holds its button down: a game polls, and must see it. */
    private static final long CLICK_HOLD_MS = 80;

    private static final int BG = 0xFF0B0E14;
    private static final int SURFACE = 0xFF141923;
    private static final int RAISED = 0xFF1B2130;
    private static final int EDGE = 0x24FFFFFF;
    private static final int TEXT = 0xE6FFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int FAINT = 0x5CFFFFFF;
    private static final int GREEN = 0xFF5BC26A;
    private static final int RED = 0xFFE5534B;
    private static final int BLUE = 0xFF4C9BE8;
    private static final int YELLOW = 0xFFF2C14E;

    private final Listener listener;
    private final float density;
    private final int accent;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint status = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    /** A stick: where it rests, how far it reaches, and the finger on it. */
    private final class Stick {
        final int click;
        float homeX;
        float homeY;
        float reach;
        float cx;
        float cy;
        /** −1…1 each, right and down positive. */
        float x;
        float y;
        int finger = -1;
        long downAt;
        float most;

        Stick(int click) {
            this.click = click;
        }

        void down(int id, float u, float v) {
            finger = id;
            downAt = SystemClock.uptimeMillis();
            most = 0;
            // Centred where the thumb lands, kept on the screen.
            cx = clamp(u, reach, width - reach);
            cy = clamp(v, reach, height - reach);
            x = 0;
            y = 0;
        }

        void move(float u, float v) {
            float dx = (u - cx) / reach;
            float dy = (v - cy) / reach;
            float d = (float) Math.hypot(dx, dy);
            if (d > 1f) {
                // Past the edge: the stick follows the thumb, so turning back answers at once.
                cx = u - dx / d * reach;
                cy = v - dy / d * reach;
                dx /= d;
                dy /= d;
                d = 1f;
            }
            most = Math.max(most, d);
            float live = d <= DEAD ? 0f : (d - DEAD) / (1f - DEAD);
            x = d == 0 ? 0 : dx / d * live;
            y = d == 0 ? 0 : dy / d * live;
        }

        /** Lifted: true if it was a click. */
        boolean up() {
            boolean click = SystemClock.uptimeMillis() - downAt <= CLICK_MS && most <= CLICK_REACH;
            finger = -1;
            cx = homeX;
            cy = homeY;
            x = 0;
            y = 0;
            return click;
        }
    }

    /** A round or square button that is held while a finger that came down on it is. */
    private static final class Key {
        final int bit;
        /** 0, or the trigger it is: 1 left, 2 right. */
        final int trigger;
        final String text;
        final boolean round;
        final RectF at = new RectF();
        int fingers;

        Key(int bit, int trigger, String text, boolean round) {
            this.bit = bit;
            this.trigger = trigger;
            this.text = text;
            this.round = round;
        }

        float cx() {
            return at.centerX();
        }

        float cy() {
            return at.centerY();
        }

        float radius() {
            return at.width() / 2f;
        }
    }

    private final Stick left = new Stick(L3);
    private final Stick right = new Stick(R3);
    private final Key[] face = {
            new Key(A, 0, "A", true), new Key(B, 0, "B", true),
            new Key(X, 0, "X", true), new Key(Y, 0, "Y", true)};
    private final int[] faceColors = {GREEN, RED, BLUE, YELLOW};
    private final Key back = new Key(BACK, 0, "", true);
    private final Key guide = new Key(GUIDE, 0, "", true);
    private final Key start = new Key(START, 0, "", true);
    private final Key lb = new Key(LB, 0, "LB", false);
    private final Key rb = new Key(RB, 0, "RB", false);
    private final Key lt = new Key(0, 1, "LT", false);
    private final Key rt = new Key(0, 2, "RT", false);
    private final Key close = new Key(0, 0, "", true);
    private final Key[] held = {back, guide, start, lb, rb, lt, rt};

    private float padX;
    private float padY;
    /** Half the d-pad's width, and of each arm's. */
    private float padReach;
    private float padArm;
    private int hat;
    private int padFinger = -1;
    private float faceX;
    private float faceY;

    /** What each finger came down on: a Stick, a Key, PAD, FACE, or NOTHING. */
    private final SparseArray<Object> fingers = new SparseArray<>();
    /** Where each finger on A B X Y is, and which of them it holds. */
    private final SparseArray<float[]> onFace = new SparseArray<>();
    private static final Object PAD = new Object();
    private static final Object FACE = new Object();
    private static final Object NOTHING = new Object();

    /** A stick's click held until then; 0 while none is. */
    private long l3Until;
    private long r3Until;
    private final Runnable clickUp = this::emit;

    /** The landscape picture, whatever the window is: its width and height. */
    private float width;
    private float height;
    /** The window is portrait: the picture is turned a quarter in it. */
    private boolean turned;

    private CharSequence title = "";
    private CharSequence detail = "";
    private boolean warn;
    private StaticLayout statusLayout;
    private final State last = new State();

    GamepadView(Context ctx, int accent, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.accent = accent;
        this.density = ctx.getResources().getDisplayMetrics().density;
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setColor(DIM);
        status.setTextSize(13 * density);
        setHapticFeedbackEnabled(true);
        setContentDescription(ctx.getString(R.string.gp_title));
    }

    /** What the controller says in its middle: what it plays, and what is in the way, if anything. */
    void setStatus(CharSequence title, CharSequence detail, boolean warn) {
        if (TextUtils.equals(title, this.title) && TextUtils.equals(detail, this.detail) && warn == this.warn) return;
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.warn = warn;
        statusLayout = null;
        invalidate();
    }

    /** Every finger let go, as if lifted: the controller is going, or Android took the touches. */
    void releaseAll() {
        fingers.clear();
        onFace.clear();
        left.up();
        right.up();
        for (Key k : held) k.fingers = 0;
        padFinger = -1;
        hat = 0;
        l3Until = 0;
        r3Until = 0;
        emit();
    }

    // ── laying it out ──

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        turned = h > w;
        width = turned ? h : w;
        height = turned ? w : h;
        layOut();
        releaseAll();
    }

    private void layOut() {
        float W = width;
        float H = height;
        // Sized by the short side, as a thumb's reach is the same on any phone.
        float unit = Math.min(H, W * 0.5f);
        left.reach = unit * 0.15f;
        right.reach = unit * 0.13f;
        left.homeX = left.cx = W * 0.15f;
        left.homeY = left.cy = H * 0.50f;
        right.homeX = right.cx = W * 0.66f;
        right.homeY = right.cy = H * 0.76f;

        padX = W * 0.34f;
        padY = H * 0.76f;
        padReach = unit * 0.14f;
        padArm = padReach * 0.36f;

        faceX = W * 0.85f;
        faceY = H * 0.48f;
        float fr = unit * 0.068f;
        float off = unit * 0.125f;
        place(face[0], faceX, faceY + off, fr);   // A, bottom
        place(face[1], faceX + off, faceY, fr);   // B, right
        place(face[2], faceX - off, faceY, fr);   // X, left
        place(face[3], faceX, faceY - off, fr);   // Y, top

        float small = unit * 0.052f;
        place(back, W * 0.42f, H * 0.42f, small);
        place(guide, W * 0.50f, H * 0.42f, small * 1.2f);
        place(start, W * 0.58f, H * 0.42f, small);
        place(close, W * 0.50f, H * 0.12f, small);

        float top = H * 0.04f;
        float tall = Math.max(H * 0.13f, 40 * density);
        lt.at.set(W * 0.02f, top, W * 0.13f, top + tall);
        lb.at.set(W * 0.145f, top, W * 0.30f, top + tall);
        rb.at.set(W * 0.70f, top, W * 0.855f, top + tall);
        rt.at.set(W * 0.87f, top, W * 0.98f, top + tall);

        statusLayout = null;
        excludeEdges();
        // Where each control is in the window, for a test's fingers (test/controller-test.mjs) and a report.
        DexLog.step("gamepad", "laid out " + Math.round(W) + "x" + Math.round(H) + (turned ? ", turned" : "")
                + ": A " + at(face[0].cx(), face[0].cy()) + " B " + at(face[1].cx(), face[1].cy())
                + " X " + at(face[2].cx(), face[2].cy()) + " Y " + at(face[3].cx(), face[3].cy())
                + " pad " + at(padX, padY) + " reach " + Math.round(padReach)
                + " L " + at(left.homeX, left.homeY) + " R " + at(right.homeX, right.homeY)
                + " stick " + Math.round(left.reach)
                + " back " + at(back.cx(), back.cy()) + " guide " + at(guide.cx(), guide.cy())
                + " start " + at(start.cx(), start.cy()) + " lb " + at(lb.at.centerX(), lb.at.centerY())
                + " rb " + at(rb.at.centerX(), rb.at.centerY()) + " lt " + at(lt.at.centerX(), lt.at.centerY())
                + " rt " + at(rt.at.centerX(), rt.at.centerY()) + " close " + at(close.cx(), close.cy()));
    }

    /** A point of the landscape picture, as "x,y" in the window's own coordinates. */
    private String at(float u, float v) {
        float x = turned ? height - v : u;
        float y = turned ? u : v;
        return Math.round(x) + "," + Math.round(y);
    }

    private static void place(Key k, float cx, float cy, float radius) {
        k.at.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    /**
     * With gesture navigation a slide in from either side is Back: the sticks and A B X Y
     * sit near both. The platform honours 200 dp of this per edge.
     */
    private void excludeEdges() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return;
        float band = Math.min(height, 200 * density);
        float side = 32 * density;
        List<Rect> keep = new ArrayList<>();
        keep.add(toView(0, left.homeY - band / 2, side, left.homeY + band / 2));
        keep.add(toView(width - side, faceY - band / 2, width, faceY + band / 2));
        setSystemGestureExclusionRects(keep);
    }

    /** A rectangle of the landscape picture, in the window's own coordinates. */
    private Rect toView(float l, float t, float rr, float b) {
        if (!turned) return new Rect(Math.round(l), Math.round(t), Math.round(rr), Math.round(b));
        // The picture's (u, v) is the window's (W − v, u), W the window's width (the picture's height).
        return new Rect(Math.round(height - b), Math.round(l), Math.round(height - t), Math.round(rr));
    }

    // ── the fingers ──

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), u(e, i), v(e, i));
                listener.onTouched();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) moved(e.getPointerId(i), u(e, i), v(e, i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                up(e.getPointerId(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                invalidate();
                return true;
            default:
                return true;
        }
        emit();
        invalidate();
        return true;
    }

    private float u(MotionEvent e, int i) {
        return turned ? e.getY(i) : e.getX(i);
    }

    private float v(MotionEvent e, int i) {
        return turned ? height - e.getX(i) : e.getY(i);
    }

    private void down(int id, float u, float v) {
        Object on = hit(u, v);
        fingers.put(id, on);
        if (on instanceof Stick) {
            ((Stick) on).down(id, u, v);
            buzz(false);
        } else if (on == PAD) {
            padFinger = id;
            int was = hat;
            hat = hatAt(u, v);
            if (hat != was) buzz(false);
        } else if (on == FACE) {
            float[] f = {u, v, 0};
            onFace.put(id, f);
            int bits = faceAt(u, v);
            f[2] = bits;
            buzz(false);
        } else if (on == close) {
            close.fingers = 1;
        } else if (on instanceof Key) {
            ((Key) on).fingers++;
            buzz(false);
        }
    }

    private void moved(int id, float u, float v) {
        Object on = fingers.get(id);
        if (on instanceof Stick) {
            Stick s = (Stick) on;
            if (s.finger == id) s.move(u, v);
        } else if (on == PAD && padFinger == id) {
            int was = hat;
            hat = hatAt(u, v);
            if (hat != was && hat != 0) buzz(true);
        } else if (on == FACE) {
            float[] f = onFace.get(id);
            if (f == null) return;
            int was = (int) f[2];
            int bits = faceAt(u, v);
            f[0] = u;
            f[1] = v;
            f[2] = bits;
            if ((bits & ~was) != 0) buzz(true);
        }
    }

    private void up(int id) {
        Object on = fingers.get(id);
        fingers.remove(id);
        if (on instanceof Stick) {
            Stick s = (Stick) on;
            if (s.finger == id && s.up()) {
                // A tap on the stick: its click, held long enough for a game to see.
                long until = SystemClock.uptimeMillis() + CLICK_HOLD_MS;
                if (s == left) l3Until = until;
                else r3Until = until;
                buzz(false);
                removeCallbacks(clickUp);
                postDelayed(clickUp, CLICK_HOLD_MS + 5);
            }
        } else if (on == PAD) {
            if (padFinger == id) {
                padFinger = -1;
                hat = 0;
            }
        } else if (on == FACE) {
            onFace.remove(id);
        } else if (on == close) {
            close.fingers = 0;
            listener.onClose();
        } else if (on instanceof Key) {
            Key k = (Key) on;
            k.fingers = Math.max(0, k.fingers - 1);
        }
    }

    /** What a finger coming down at (u, v) is on. */
    private Object hit(float u, float v) {
        float slop = 8 * density;
        if (near(close, u, v, 1.3f)) return close;
        for (Key k : new Key[]{lt, lb, rb, rt}) {
            if (u >= k.at.left - slop && u <= k.at.right + slop && v >= k.at.top - slop && v <= k.at.bottom + slop) {
                return k;
            }
        }
        for (Key k : new Key[]{back, guide, start}) {
            if (near(k, u, v, 1.4f)) return k;
        }
        for (Key k : face) {
            if (near(k, u, v, 1.45f)) return FACE;
        }
        if (Math.abs(u - padX) <= padReach * 1.3f && Math.abs(v - padY) <= padReach * 1.3f) return PAD;
        if (u < width / 2f && Math.hypot(u - left.homeX, v - left.homeY) <= left.reach * 2.6f && left.finger < 0) {
            return left;
        }
        if (u >= width / 2f && Math.hypot(u - right.homeX, v - right.homeY) <= right.reach * 2.4f && right.finger < 0) {
            return right;
        }
        return NOTHING;
    }

    private static boolean near(Key k, float u, float v, float times) {
        return Math.hypot(u - k.cx(), v - k.cy()) <= k.radius() * times;
    }

    /** A B X Y under (u, v): each within its reach — between two, both. */
    private int faceAt(float u, float v) {
        int bits = 0;
        for (Key k : face) {
            if (near(k, u, v, 1.35f)) bits |= k.bit;
        }
        return bits;
    }

    /** The hat at (u, v): eight ways, the straight four given 60° each and the slanted 30°. */
    private int hatAt(float u, float v) {
        float dx = u - padX;
        float dy = v - padY;
        if (Math.hypot(dx, dy) < padArm * 0.6f) return 0;
        double deg = Math.toDegrees(Math.atan2(-dy, dx)); // 0 right, 90 up
        if (deg < 0) deg += 360;
        if (deg >= 330 || deg < 30) return 3;
        if (deg < 60) return 2;
        if (deg < 120) return 1;
        if (deg < 150) return 8;
        if (deg < 210) return 7;
        if (deg < 240) return 6;
        if (deg < 300) return 5;
        return 4;
    }

    private void buzz(boolean light) {
        performHapticFeedback(light ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** What is held now, to the listener if it changed. */
    private void emit() {
        State s = new State();
        for (int i = 0; i < onFace.size(); i++) s.buttons |= (int) onFace.valueAt(i)[2];
        for (Key k : held) {
            if (k.fingers <= 0) continue;
            s.buttons |= k.bit;
            if (k.trigger == 1) s.lt = 255;
            if (k.trigger == 2) s.rt = 255;
        }
        long now = SystemClock.uptimeMillis();
        if (now < l3Until) s.buttons |= L3;
        if (now < r3Until) s.buttons |= R3;
        s.hat = hat;
        s.lx = axis(left.x);
        s.ly = axis(left.y);
        s.rx = axis(right.x);
        s.ry = axis(right.y);
        if (s.same(last)) return;
        last.buttons = s.buttons;
        last.hat = s.hat;
        last.lx = s.lx;
        last.ly = s.ly;
        last.rx = s.rx;
        last.ry = s.ry;
        last.lt = s.lt;
        last.rt = s.rt;
        listener.onState(s);
        invalidate();
    }

    private static int axis(float f) {
        return Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
    }

    private static float clamp(float x, float lo, float hi) {
        return hi < lo ? (lo + hi) / 2f : Math.max(lo, Math.min(hi, x));
    }

    // ── drawing it ──

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        canvas.save();
        if (turned) {
            canvas.translate(getWidth(), 0);
            canvas.rotate(90);
        }
        drawStick(canvas, left);
        drawStick(canvas, right);
        drawPad(canvas);
        for (int i = 0; i < face.length; i++) drawFace(canvas, face[i], faceColors[i], (last.buttons & face[i].bit) != 0);
        drawSmall(canvas, back, 0);
        drawSmall(canvas, guide, 1);
        drawSmall(canvas, start, 2);
        drawShoulder(canvas, lb, (last.buttons & LB) != 0);
        drawShoulder(canvas, rb, (last.buttons & RB) != 0);
        drawShoulder(canvas, lt, last.lt > 0);
        drawShoulder(canvas, rt, last.rt > 0);
        drawClose(canvas);
        drawStatus(canvas);
        canvas.restore();
    }

    private void drawStick(Canvas c, Stick s) {
        boolean on = s.finger >= 0;
        fill.setColor(SURFACE);
        c.drawCircle(s.cx, s.cy, s.reach, fill);
        stroke.setColor(on ? (accent & 0x00FFFFFF) | 0x99000000 : EDGE);
        stroke.setStrokeWidth(Math.max(1f, density * 1.5f));
        stroke.setStyle(Paint.Style.STROKE);
        c.drawCircle(s.cx, s.cy, s.reach, stroke);
        float kx = s.cx + s.x * s.reach * 0.62f;
        float ky = s.cy + s.y * s.reach * 0.62f;
        boolean clicked = (last.buttons & s.click) != 0;
        fill.setColor(clicked ? accent : on ? (accent & 0x00FFFFFF) | 0x66000000 : RAISED);
        c.drawCircle(kx, ky, s.reach * 0.45f, fill);
        stroke.setColor(EDGE);
        c.drawCircle(kx, ky, s.reach * 0.45f, stroke);
        label.setColor(FAINT);
        label.setTextSize(s.reach * 0.26f);
        c.drawText(s == left ? "L" : "R", kx, ky + label.getTextSize() * 0.36f, label);
    }

    private void drawPad(Canvas c) {
        float a = padArm;
        float reach = padReach;
        float round = a * 0.35f;
        fill.setColor(RAISED);
        r.set(padX - a, padY - reach, padX + a, padY + reach);
        c.drawRoundRect(r, round, round, fill);
        r.set(padX - reach, padY - a, padX + reach, padY + a);
        c.drawRoundRect(r, round, round, fill);
        // The held direction lit: a straight one its arm, a slanted one both of its arms.
        int h = hat;
        boolean up = h == 1 || h == 2 || h == 8;
        boolean rightOn = h == 2 || h == 3 || h == 4;
        boolean down = h == 4 || h == 5 || h == 6;
        boolean leftOn = h == 6 || h == 7 || h == 8;
        fill.setColor((accent & 0x00FFFFFF) | 0xAA000000);
        if (up) {
            r.set(padX - a, padY - reach, padX + a, padY - a);
            c.drawRoundRect(r, round, round, fill);
        }
        if (down) {
            r.set(padX - a, padY + a, padX + a, padY + reach);
            c.drawRoundRect(r, round, round, fill);
        }
        if (leftOn) {
            r.set(padX - reach, padY - a, padX - a, padY + a);
            c.drawRoundRect(r, round, round, fill);
        }
        if (rightOn) {
            r.set(padX + a, padY - a, padX + reach, padY + a);
            c.drawRoundRect(r, round, round, fill);
        }
        // A small arrow at each arm's end.
        fill.setColor(DIM);
        float t = a * 0.45f;
        float in = reach - a * 0.55f;
        triangle(c, padX, padY - in - t * 0.6f, padX - t, padY - in + t * 0.4f, padX + t, padY - in + t * 0.4f);
        triangle(c, padX, padY + in + t * 0.6f, padX - t, padY + in - t * 0.4f, padX + t, padY + in - t * 0.4f);
        triangle(c, padX - in - t * 0.6f, padY, padX - in + t * 0.4f, padY - t, padX - in + t * 0.4f, padY + t);
        triangle(c, padX + in + t * 0.6f, padY, padX + in - t * 0.4f, padY - t, padX + in - t * 0.4f, padY + t);
    }

    private void triangle(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        c.drawPath(path, fill);
    }

    private void drawFace(Canvas c, Key k, int color, boolean on) {
        fill.setColor(on ? (color & 0x00FFFFFF) | 0x80000000 : RAISED);
        c.drawCircle(k.cx(), k.cy(), k.radius(), fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density * 1.5f));
        stroke.setColor((color & 0x00FFFFFF) | (on ? 0xFF000000 : 0x66000000));
        c.drawCircle(k.cx(), k.cy(), k.radius(), stroke);
        label.setColor(on ? 0xFFFFFFFF : color);
        label.setTextSize(k.radius() * 0.9f);
        c.drawText(k.text, k.cx(), k.cy() + label.getTextSize() * 0.36f, label);
    }

    /** Back as two windows, the guide button as a ring, Start as three lines: as the pad has them. */
    private void drawSmall(Canvas c, Key k, int kind) {
        boolean on = (last.buttons & k.bit) != 0;
        fill.setColor(on ? (accent & 0x00FFFFFF) | 0xAA000000 : RAISED);
        c.drawCircle(k.cx(), k.cy(), k.radius(), fill);
        float s = k.radius() * 0.42f;
        float x = k.cx();
        float y = k.cy();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density * 1.4f));
        stroke.setColor(TEXT);
        if (kind == 0) {
            r.set(x - s, y - s * 0.75f, x + s * 0.45f, y + s * 0.35f);
            c.drawRoundRect(r, s * 0.15f, s * 0.15f, stroke);
            r.set(x - s * 0.45f, y - s * 0.35f, x + s, y + s * 0.75f);
            c.drawRoundRect(r, s * 0.15f, s * 0.15f, stroke);
        } else if (kind == 1) {
            c.drawCircle(x, y, s * 0.95f, stroke);
            fill.setColor(TEXT);
            c.drawCircle(x, y, s * 0.3f, fill);
        } else {
            for (int i = -1; i <= 1; i++) c.drawLine(x - s, y + i * s * 0.6f, x + s, y + i * s * 0.6f, stroke);
        }
    }

    private void drawShoulder(Canvas c, Key k, boolean on) {
        float round = k.at.height() * 0.35f;
        fill.setColor(on ? (accent & 0x00FFFFFF) | 0xAA000000 : RAISED);
        c.drawRoundRect(k.at, round, round, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density));
        stroke.setColor(EDGE);
        c.drawRoundRect(k.at, round, round, stroke);
        label.setColor(on ? 0xFFFFFFFF : DIM);
        label.setTextSize(Math.min(k.at.height() * 0.38f, 15 * density));
        c.drawText(k.text, k.at.centerX(), k.at.centerY() + label.getTextSize() * 0.36f, label);
    }

    private void drawClose(Canvas c) {
        fill.setColor(close.fingers > 0 ? 0x33FFFFFF : 0x14FFFFFF);
        c.drawCircle(close.cx(), close.cy(), close.radius(), fill);
        float s = close.radius() * 0.38f;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density * 1.6f));
        stroke.setColor(DIM);
        c.drawLine(close.cx() - s, close.cy() - s, close.cx() + s, close.cy() + s, stroke);
        c.drawLine(close.cx() - s, close.cy() + s, close.cx() + s, close.cy() - s, stroke);
    }

    /** What it plays, and what is in the way: at the top, between the shoulders, under ✕. */
    private void drawStatus(Canvas c) {
        float left = lb.at.right + 12 * density;
        float rightEdge = rb.at.left - 12 * density;
        int w = (int) Math.max(40 * density, rightEdge - left);
        if (statusLayout == null || statusLayout.getWidth() != w) {
            CharSequence text = detail.length() == 0 ? title
                    : title.length() == 0 ? detail : title + "\n" + detail;
            status.setColor(warn ? accent : DIM);
            statusLayout = StaticLayout.Builder.obtain(text, 0, text.length(), status, w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
        }
        float top = close.at.bottom + 6 * density;
        c.save();
        c.translate(left, top);
        statusLayout.draw(c);
        c.restore();
    }
}
