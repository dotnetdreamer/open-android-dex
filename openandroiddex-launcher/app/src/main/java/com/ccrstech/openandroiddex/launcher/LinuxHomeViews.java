package com.ccrstech.openandroiddex.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The custom views LinuxOnDroid's home screen ({@link LinuxHomeActivity}) is
 * drawn with. Small on purpose: everything here only paints, and every fact it
 * paints is handed to it by the activity.
 */
final class LinuxHomeViews {

    private LinuxHomeViews() {
    }

    /** The prompt, the caret and the progress marks: the app icon's amber. */
    static final int AMBER = 0xFFFFC131;

    // ── a page's top bar ──

    /**
     * The top of a page that opens from the home screen, Settings and Remote
     * access: Back, then the page's name. One builder, so the pages look and
     * work alike.
     *
     * Back only where the page needs one of its own: in LinuxOnDroid, and on
     * the phone's own screen. A window on a desktop display has Back on its own
     * bar (CaptionService), so there the page shows only its name.
     *
     * @param color the arrow's and the name's colour
     * @param dark  a dark page, for the colour of the press ripple
     */
    static View pageBar(Activity a, String name, int color, boolean dark) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(a, 56));
        if (Linux.hasHomeScreen(a) || onPhoneDisplay(a)) {
            ImageView back = new ImageView(a);
            Drawable arrow = a.getDrawable(R.drawable.hm_ic_back).mutate();
            arrow.setTint(color);
            back.setImageDrawable(arrow);
            back.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable rest = new GradientDrawable();
            rest.setColor(0x00000000);
            rest.setCornerRadius(dp(a, 22));
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(dp(a, 22));
            // A white ripple vanishes on a light desktop's page; a dark one shows.
            back.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(dark ? 0x24FFFFFF : 0x1F000000), rest, mask));
            back.setContentDescription(a.getString(R.string.lx_back));
            back.setFocusable(true);
            back.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            back.setOnClickListener(v -> a.finish());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44));
            // The arrow's stroke sits where the page's text starts.
            backLp.setMarginStart(-dp(a, 10));
            bar.addView(back, backLp);
        }
        TextView title = new TextView(a);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(color);
        title.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.DEFAULT, 700, false) : Typeface.DEFAULT_BOLD);
        // The language's direction, as on the home screen.
        DexLocale.followLayout(title);
        title.setLetterSpacing(-0.01f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(dp(a, 6));
        bar.addView(title, nameLp);
        DexFonts.applyTo(a, bar);
        return bar;
    }

    /** On the phone's own screen, not a desktop's: a window there has no bar of ours. */
    static boolean onPhoneDisplay(Activity a) {
        try {
            Display d = Build.VERSION.SDK_INT >= 30 ? a.getDisplay()
                    : a.getWindowManager().getDefaultDisplay();
            return d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY;
        } catch (Exception e) {
            return true;
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    // ── the terminal ──

    /**
     * The little terminal at the top of the hero. It says what the hero says,
     * the way Linux would: an apt run while Linux installs, {@code startxfce4}
     * waiting for its Enter while it is ready, the session's uptime while it
     * runs. The Start button is that Enter.
     *
     * Typed out once when the state changes, then still, apart from a blinking
     * caret where Linux is waiting on the user. Never typed on a rebuild that is
     * only a rotation, and never at all with animations off (see
     * {@link #show}). Hidden from accessibility services: it is decoration, and
     * every fact in it is also in the hero's text, which they do read.
     *
     * Always three rows high whatever it holds, so a change of state does not
     * move everything under it.
     */
    static final class Terminal extends View {

        /** A command, typed after the prompt. */
        static final int CMD = 0;
        /** What came back. */
        static final int OUT = 1;
        /** An error, apt's "E:" kind. */
        static final int ERR = 2;
        /** Something that went well. */
        static final int GOOD = 3;

        /** A line the type is fitted to, and stays fitted to. */
        static final int FIT_FIXED = 0;
        /**
         * A value that moves while it is shown (the install's step, the
         * uptime): the type shrinks for it when it outgrows the line, and does
         * not grow back while this showing lasts, so it never jumps about.
         */
        static final int FIT_MOVING = 1;
        /** Made to fill the line by its maker (apt's progress bar, by cellsAfter): left out. */
        static final int FIT_FILLS = 2;

        static final class Line {
            final int kind;
            String text;
            final int fit;

            Line(int kind, String text) {
                this(kind, text, FIT_FIXED);
            }

            Line(int kind, String text, int fit) {
                this.kind = kind;
                this.text = text == null ? "" : text;
                this.fit = fit;
            }
        }

        private static final int ROWS = 3;
        private static final long CHAR_MS = 32;
        private static final long CMD_PAUSE_MS = 280;
        private static final long OUT_PAUSE_MS = 150;
        private static final long BLINK_MS = 530;

        private static final int TEXT = 0xFFE9EEF5;
        private static final int OUTPUT = 0xFF8F9BAE;
        private static final int ERROR = 0xFFFF8A80;
        private static final int OKAY = 0xFF6EE7A0;

        private final float dp;
        /** The type's size, and the smallest the fit may take it to (12.5 and 10 sp). */
        private final float baseSize;
        private final float minSize;
        /** The size the moving lines have needed so far in this showing; it only goes down. */
        private float movingSize = Float.MAX_VALUE;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mono = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        private final List<Line> lines = new ArrayList<>();
        private String title = "";
        /** Blink a caret after the last line once it is all there. */
        private boolean caret;
        /** uptimeMillis the typing began; 0 = everything shown at once. */
        private long typeStart;
        /** How long the typing of {@link #lines} takes, end to end. */
        private long typeLength;
        /** The screen is in front: worth animating. */
        private boolean running;
        private boolean blinkOn = true;

        private final Runnable blink = new Runnable() {
            @Override
            public void run() {
                blinkOn = !blinkOn;
                invalidate();
                if (running && caret) postDelayed(this, BLINK_MS);
            }
        };

        Terminal(Context ctx) {
            super(ctx);
            dp = ctx.getResources().getDisplayMetrics().density;
            float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f,
                    ctx.getResources().getDisplayMetrics());
            mono.setTypeface(Typeface.MONOSPACE);
            baseSize = 12.5f * sp;
            minSize = 10f * sp;
            mono.setTextSize(baseSize);
            label.setTypeface(Typeface.MONOSPACE);
            label.setTextSize(11f * sp);
            label.setColor(0x80FFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, dp));
            edge.setColor(0x1AFFFFFF);
            // Code reads left to right in every locale.
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** The window title in the bar, as a terminal shows user@host. */
        void setTitle(String t) {
            title = t == null ? "" : t;
            invalidate();
        }

        /**
         * New contents. {@code animate} types them out; otherwise they are
         * simply there, which is what a rotation and Reduce motion get.
         */
        void show(List<Line> next, boolean withCaret, boolean animate) {
            movingSize = Float.MAX_VALUE;
            lines.clear();
            for (int i = 0; i < next.size() && i < ROWS; i++) lines.add(next.get(i));
            caret = withCaret;
            long t = 0;
            for (Line ln : lines) {
                t += ln.kind == CMD ? CMD_PAUSE_MS + ln.text.length() * CHAR_MS : OUT_PAUSE_MS;
            }
            typeLength = t;
            typeStart = animate ? SystemClock.uptimeMillis() : 0;
            blinkOn = true;
            removeCallbacks(blink);
            invalidate();
            if (!animate) scheduleBlink(0);
        }

        /** One line's text, changed where it stands: a live value, not news. */
        void setText(int index, String text) {
            if (index < 0 || index >= lines.size()) return;
            String t = text == null ? "" : text;
            if (t.equals(lines.get(index).text)) return;
            lines.get(index).text = t;
            invalidate();
        }

        /** Animate only while the screen is in front. */
        void setRunning(boolean on) {
            running = on;
            removeCallbacks(blink);
            if (!on) return;
            blinkOn = true;
            invalidate();
            scheduleBlink(0);
        }

        private boolean typed() {
            return typeStart == 0 || SystemClock.uptimeMillis() - typeStart >= typeLength;
        }

        private void scheduleBlink(long after) {
            if (running && caret) postDelayed(blink, BLINK_MS + after);
        }

        private float barHeight() {
            return 30 * dp;
        }

        /** From the base size, not the fitted one: the box keeps its height whatever it holds. */
        private float lineHeight() {
            return baseSize * 1.62f;
        }

        /**
         * The type at the largest size, up to the base, at which every line
         * fits the width, caret included: a sentence in a longer language, or a
         * narrow phone, gets smaller type rather than a line cut off half way.
         * A moving line only ever takes it down (see {@link #FIT_MOVING}).
         * Below {@link #minSize} it stops, and what still does not fit is cut
         * with "…".
         */
        private void fitText(int width) {
            mono.setTextSize(baseSize);
            float avail = width - 2 * 16 * dp;
            if (avail <= 0) return;
            float size = baseSize;
            for (Line ln : lines) {
                if (ln.fit == FIT_FILLS) continue;
                float w = mono.measureText((ln.kind == CMD ? "$ " + ln.text : ln.text) + "  ");
                if (w <= avail) continue;
                float need = baseSize * avail / w;
                if (ln.fit == FIT_MOVING) {
                    movingSize = Math.min(movingSize, need);
                } else {
                    size = Math.min(size, need);
                }
            }
            mono.setTextSize(Math.max(minSize, Math.min(size, movingSize)));
        }

        /**
         * Does this line read right to left? By its first letter with a
         * direction, as the system decides a paragraph's.
         */
        private static boolean rightToLeft(String s) {
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                byte d = Character.getDirectionality(cp);
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
                if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
                i += Character.charCount(cp);
            }
            return false;
        }

        private float pad() {
            return 12 * dp;
        }

        /**
         * How many '#' cells fit on a line after {@code around}, which is
         * measured rather than counted: the progress line's bar, beside a
         * label in the user's language. A guess until the first layout.
         */
        int cellsAfter(String around) {
            fitText(getWidth());
            float cell = mono.measureText("#");
            if (getWidth() <= 0 || cell <= 0) return 32 - around.length();
            float w = getWidth() - 2 * 16 * dp - mono.measureText(around);
            return (int) (w / cell);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = Math.round(barHeight() + 2 * pad() + ROWS * lineHeight());
            setMeasuredDimension(w, resolveSize(h, heightSpec));
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(blink);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = 16 * dp;
            float half = edge.getStrokeWidth() / 2f;
            box.set(half, half, w - half, h - half);
            fill.setColor(0xB30A0E18);
            c.drawRoundRect(box, r, r, fill);
            c.drawRoundRect(box, r, r, edge);

            // The bar: three dots and the title, as any terminal window has.
            float bar = barHeight();
            float cy = bar / 2f;
            fill.setColor(0x38FFFFFF);
            for (int i = 0; i < 3; i++) c.drawCircle(16 * dp + i * 12 * dp, cy, 3.5f * dp, fill);
            if (!title.isEmpty()) {
                String t = TextUtils.ellipsize(title, label, w - 2 * 64 * dp,
                        TextUtils.TruncateAt.END).toString();
                c.drawText(t, (w - label.measureText(t)) / 2f,
                        cy - (label.ascent() + label.descent()) / 2f, label);
            }
            fill.setColor(0x12FFFFFF);
            c.drawRect(half, bar, w - half, bar + Math.max(1f, dp), fill);

            // The rows, as far as the typing has got.
            fitText((int) w);
            long now = SystemClock.uptimeMillis();
            long elapsed = typeStart == 0 ? Long.MAX_VALUE : now - typeStart;
            float x0 = 16 * dp;
            float avail = w - 2 * x0;
            float lh = lineHeight();
            float y = bar + pad() + (lh - (mono.ascent() + mono.descent())) / 2f;
            float caretX = x0;
            float caretY = y;
            boolean caretBefore = false;
            boolean typing = false;
            long t = 0;
            for (Line ln : lines) {
                float x = x0;
                if (ln.kind == CMD) {
                    t += CMD_PAUSE_MS;
                    if (elapsed < t) break;
                    mono.setColor(AMBER);
                    c.drawText("$", x, y, mono);
                    x += mono.measureText("$ ");
                    int n = ln.text.length();
                    int shown = (int) Math.min(n, (elapsed - t) / CHAR_MS);
                    t += n * CHAR_MS;
                    String vis = fit(ln.text.substring(0, shown), avail - (x - x0));
                    mono.setColor(TEXT);
                    c.drawText(vis, x, y, mono);
                    caretX = x + mono.measureText(vis);
                    caretY = y;
                    caretBefore = false;
                    if (shown < n) {
                        typing = true;
                        break;
                    }
                } else {
                    t += OUT_PAUSE_MS;
                    if (elapsed < t) break;
                    mono.setColor(ln.kind == ERR ? ERROR : ln.kind == GOOD ? OKAY : OUTPUT);
                    String vis = fit(ln.text, avail);
                    float tw = mono.measureText(vis);
                    // What Linux says in Arabic reads from the right: set against
                    // the right edge, as a terminal that knows bidi sets it, and
                    // ending, caret and all, on the left.
                    boolean rtl = rightToLeft(vis);
                    float tx = rtl ? x0 + avail - tw : x;
                    c.drawText(vis, tx, y, mono);
                    caretX = rtl ? tx : tx + tw;
                    caretY = y;
                    caretBefore = rtl;
                }
                y += lh;
            }

            boolean done = elapsed >= typeLength;
            // Solid while it types, blinking where Linux waits on the user.
            if (typing || (done && caret && blinkOn)) {
                float cw = mono.measureText("0") * 0.62f;
                float left = caretBefore ? caretX - 2 * dp - cw : caretX + 2 * dp;
                fill.setColor(AMBER);
                c.drawRect(left, caretY + mono.ascent() * 0.86f, left + cw,
                        caretY + mono.descent() * 0.55f, fill);
            }
            if (!done) {
                if (running) {
                    postInvalidateOnAnimation();
                } else {
                    // In the background mid-type: just be done when we are back.
                    typeStart = 0;
                }
            } else if (typeStart != 0) {
                // Typed out: the caret takes over, on its own clock.
                typeStart = 0;
                removeCallbacks(blink);
                scheduleBlink(0);
            }
        }

        private String fit(String s, float width) {
            if (mono.measureText(s) <= width) return s;
            return TextUtils.ellipsize(s, mono, width, TextUtils.TruncateAt.END).toString();
        }
    }

    // ── the hero's light ──

    /**
     * One soft light behind the hero: a radial fall-off from {@code color} to
     * nothing, centred at a fraction of the view. Two of them, drifting slowly
     * against each other, are the hero's whole background.
     *
     * A view of its own rather than a shader in the hero's background: the
     * activity moves it with translation alone, which the RenderThread does on
     * an unchanged display list. A shader whose centre moved would be the hero
     * redrawn every frame for as long as the screen is open.
     */
    static final class Glow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final float fx;
        private final float fy;
        private final float fr;

        /**
         * @param fx centre, as a fraction of the width
         * @param fy centre, as a fraction of the height
         * @param fr radius, as a fraction of the width
         */
        Glow(Context ctx, int color, float fx, float fy, float fr) {
            super(ctx);
            this.color = color;
            this.fx = fx;
            this.fy = fy;
            this.fr = fr;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /**
         * No size of its own: only what it is told exactly. A light is laid
         * over the hero and must never be what sizes it, and a View's default
         * answer to "at most this tall" is all of it. That made the hero as tall
         * as the screen whenever the page was shorter than the screen.
         */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(
                    MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                            ? MeasureSpec.getSize(widthSpec) : 0,
                    MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                            ? MeasureSpec.getSize(heightSpec) : 0);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float radius = Math.max(1f, fr * w);
            paint.setShader(new RadialGradient(fx * w, fy * h, radius,
                    new int[]{color, color & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    // ── the status dot ──

    /**
     * The coloured dot in the hero's status pill. Steady for a state that is
     * settled; a ring breathing out of it for one where something is going on
     * (Linux running, or installing), so the difference reads at a glance.
     */
    static final class Dot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private int color = 0xFF7A8699;
        private boolean pulse;
        private float phase;
        private ValueAnimator anim;

        Dot(Context ctx) {
            super(ctx);
            dp = ctx.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int c, boolean pulsing) {
            color = c;
            pulse = pulsing;
            if (!pulsing) stop();
            invalidate();
        }

        /** Breathe, if this is a dot that does: only while the screen is in front. */
        void setRunning(boolean on) {
            if (!on || !pulse) {
                stop();
                return;
            }
            if (anim != null) return;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        private void stop() {
            if (anim != null) anim.cancel();
            anim = null;
            phase = 0f;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float core = 3.5f * dp;
            if (pulse && anim != null) {
                float ring = core + phase * 5f * dp;
                paint.setColor(color);
                paint.setAlpha(Math.round(110 * (1f - phase)));
                c.drawCircle(cx, cy, ring, paint);
            }
            paint.setColor(color);
            c.drawCircle(cx, cy, core, paint);
        }
    }

    // ── layout ──

    /**
     * A vertical column no wider than {@code maxWidth}: on a tablet, a foldable
     * or a window on a desktop, the page keeps the measure it was designed at
     * and sits in the middle, instead of stretching one line of text across a
     * whole screen.
     */
    static final class Column extends LinearLayout {
        private int maxWidth;

        Column(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
        }

        void setMaxWidth(int px) {
            if (px == maxWidth) return;
            maxWidth = px;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getSize(widthSpec);
            if (maxWidth > 0 && size > maxWidth) {
                widthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /**
     * Children left to right, wrapping onto a new row when the next would not
     * fit: the chips listing what is inside Linux. Mirrored for a right-to-left
     * layout.
     */
    static final class Flow extends ViewGroup {
        private final int hGap;
        private final int vGap;

        Flow(Context ctx, int hGapPx, int vGapPx) {
            super(ctx);
            hGap = hGapPx;
            vGap = vGapPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int childSpec = MeasureSpec.makeMeasureSpec(Math.max(0, maxW), MeasureSpec.AT_MOST);
            int x = 0;
            int rowH = 0;
            int total = 0;
            int widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    total += rowH + vGap;
                    x = 0;
                    rowH = 0;
                }
                x += cw + hGap;
                widest = Math.max(widest, x - hGap);
                rowH = Math.max(rowH, ch);
            }
            total += rowH;
            int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                    ? MeasureSpec.getSize(widthSpec)
                    : widest + getPaddingLeft() + getPaddingRight();
            setMeasuredDimension(w, resolveSize(total + getPaddingTop() + getPaddingBottom(),
                    heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int x = 0;
            int y = getPaddingTop();
            int rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    y += rowH + vGap;
                    x = 0;
                    rowH = 0;
                }
                int left = rtl ? (r - l) - getPaddingRight() - x - cw : getPaddingLeft() + x;
                child.layout(left, y, left + cw, y + ch);
                x += cw + hGap;
                rowH = Math.max(rowH, ch);
            }
        }
    }
}
