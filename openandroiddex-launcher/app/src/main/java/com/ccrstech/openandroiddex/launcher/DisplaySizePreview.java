package com.ccrstech.openandroiddex.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The desktop in miniature, at the density the Display size card is
 * considering: the real wallpaper, the taskbar, a few icons, and the window a
 * new app would open in, each drawn at its size in dp at that density.
 *
 * Nothing in it is text — at a sixth of the size text is a smudge — so words
 * are bars and what the eye reads is proportion: how much of the screen the
 * chrome takes, and how much room the window holds. The window lays itself
 * out the way an app would in that room — one column like a phone, list and
 * detail side by side like a tablet, a navigation rail beside both on a big
 * one — so sliding from one size to another shows the layout change the card
 * describes, before anything is applied.
 *
 * The window is a share of the display in pixels (see
 * {@link DisplaySize#newWindow}), so it stays put as the density moves and
 * only what is inside it grows or shrinks — which is exactly what happens on
 * the desktop behind.
 */
final class DisplaySizePreview extends View {

    /** Stand-ins for app icons: five hues that read on every wallpaper. */
    private static final int[] ICONS = {0xFF4d9fff, 0xFFf59e0b, 0xFF34d399, 0xFFf472b6, 0xFFa78bfa};

    private final DexTheme theme;
    private final int dispW;
    private final int dispH;
    private final String launchMode;
    private final String windowSize;
    private final String wallpaperId;
    private final int wallDim;
    private final float density;
    private final int maxHeight;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screen = new RectF();
    private final RectF r = new RectF();
    private final Path clip = new Path();
    /** Re-filled every frame rather than made: a drag redraws this sixty times a second. */
    private final Path windowClip = new Path();

    private Bitmap wallpaper;
    /** The density being drawn — between two while an animation runs. */
    private float dpi;
    private ValueAnimator animator;

    /**
     * @param dispW      the desktop display, in px
     * @param dispH      the desktop display, in px
     * @param maxHeight  the tallest the preview may be, in this view's px —
     *                   a portrait display is drawn narrower rather than taller
     */
    DisplaySizePreview(Context ctx, DexTheme theme, int dispW, int dispH, int dpi,
                       String launchMode, String windowSize, String wallpaperId, int wallDim,
                       int maxHeight) {
        super(ctx);
        this.theme = theme;
        this.dispW = Math.max(1, dispW);
        this.dispH = Math.max(1, dispH);
        this.launchMode = launchMode;
        this.windowSize = windowSize;
        this.wallpaperId = wallpaperId;
        this.wallDim = wallDim;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.maxHeight = maxHeight;
        this.dpi = dpi;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Show another density: animated for a click, straight there for a drag. */
    void setDpi(int target, boolean animate) {
        if (animator != null) animator.cancel();
        if (!animate || !isLaidOut()) {
            dpi = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(dpi, target);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            dpi = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.min(heightCap(), Math.round(w * dispH / (float) dispW));
        setMeasuredDimension(w, h);
    }

    /**
     * What the card needs below the preview, and the section header above it,
     * in dp: the presets, − and +, the slider, what the size leaves, the
     * status. A window shorter than the preview plus this gives up preview
     * first — the controls are what the card is for.
     */
    private static final int CONTROLS_DP = 370;
    private static final int MIN_DP = 90;

    /**
     * The tallest the preview may be now: {@code maxHeight}, or less in a
     * window too short for it and the controls. Asked of the window's height
     * each time, because this window changes size with the density.
     */
    private int heightCap() {
        int windowDp = getResources().getConfiguration().screenHeightDp;
        float capDp = Math.min(maxHeight / density, windowDp - CONTROLS_DP);
        return Math.round(Math.max(MIN_DP, capDp) * density);
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        requestLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float sw = Math.min(w, h * dispW / (float) dispH);
        float sh = sw * dispH / dispW;
        screen.set((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f);
        clip.reset();
        float corner = 10 * density;
        clip.addRoundRect(screen, corner, corner, Path.Direction.CW);
        if (sw >= 1 && sh >= 1) {
            wallpaper = Wallpapers.thumbnail(wallpaperId, Math.round(sw), Math.round(sh), wallDim);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (screen.width() < 1) return;
        float s = screen.width() / dispW;
        // the desktop's dp at the density being shown, in this view's px
        float u = dpi / 160f * s;

        canvas.save();
        canvas.clipPath(clip);
        if (wallpaper != null) {
            canvas.drawBitmap(wallpaper, screen.left, screen.top, null);
        } else {
            canvas.drawColor(theme.backdrop);
        }
        float taskbarTop = screen.bottom - DisplaySize.TASKBAR_DP * u;
        drawIcons(canvas, u, taskbarTop);
        drawWindow(canvas, s, u);
        drawTaskbar(canvas, u, taskbarTop);
        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(theme.divider);
        float corner = 10 * density;
        canvas.drawRoundRect(screen, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** The column of icons the desktop grid starts at the top left. */
    private void drawIcons(Canvas canvas, float u, float limit) {
        float x = screen.left + 18 * u;
        for (int i = 0; i < 4; i++) {
            float y = screen.top + (16 + i * 92) * u;
            if (y + 62 * u > limit) break;
            fill(canvas, ICONS[i], x, y, 44 * u, 44 * u, 12 * u);
            fill(canvas, withAlpha(theme.deskText, 0x99), x + 2 * u, y + 52 * u, 40 * u, 6 * u, 3 * u);
        }
    }

    private void drawWindow(Canvas canvas, float s, float u) {
        int at = Math.round(dpi);
        int[] w = DisplaySize.newWindow(dispW, dispH, at, DisplaySize.px(DisplaySize.TASKBAR_DP, at),
                0, launchMode, windowSize, 0);
        float left = screen.left + w[0] * s;
        float top = screen.top + w[1] * s;
        float width = w[2] * s;
        float height = w[3] * s;
        float corner = 10 * u;

        // a soft shadow, painted rather than cast: shadow layers on shapes need
        // API 28 under hardware rendering, and this launcher starts at 26
        for (int i = 3; i >= 1; i--) {
            float spread = i * Math.max(1f, 2 * u);
            fill(canvas, 0x10000000, left - spread, top - spread + spread / 2,
                    width + 2 * spread, height + 2 * spread, corner + spread);
        }
        fill(canvas, theme.window, left, top, width, height, corner);

        canvas.save();
        r.set(left, top, left + width, top + height);
        windowClip.reset();
        windowClip.addRoundRect(r, corner, corner, Path.Direction.CW);
        canvas.clipPath(windowClip);

        float caption = DisplaySize.CAPTION_DP * u;
        fill(canvas, theme.caption, left, top, width, caption, 0);
        float mid = top + caption / 2;
        dot(canvas, theme.accent, left + 20 * u, mid, 8 * u);
        fill(canvas, withAlpha(theme.textDim, 0x88), left + 36 * u, mid - 4 * u,
                Math.min(96 * u, width * 0.3f), 8 * u, 4 * u);
        for (int i = 0; i < 3; i++) {
            dot(canvas, withAlpha(theme.textDim, 0x77), left + width - (18 + i * 24) * u, mid, 5 * u);
        }

        int[] room = DisplaySize.contentDp(w[2], w[3], at);
        float cx = left;
        float cy = top + caption;
        float cw = width;
        float ch = height - caption;
        if (room[0] >= DisplaySize.EXPANDED_DP) {
            drawRail(canvas, u, cx, cy, ch);
            float rail = 72 * u;
            float list = Math.max(280 * u, (cw - rail) * 0.32f);
            drawList(canvas, u, cx + rail, cy, list, ch, true);
            drawDetail(canvas, u, cx + rail + list, cy, cw - rail - list, ch);
        } else if (room[0] >= DisplaySize.TABLET_DP) {
            float list = Math.max(240 * u, cw * 0.38f);
            drawList(canvas, u, cx, cy, list, ch, true);
            drawDetail(canvas, u, cx + list, cy, cw - list, ch);
        } else {
            float nav = 56 * u;
            drawList(canvas, u, cx, cy, cw, ch - nav, false);
            fill(canvas, theme.cardSolid, cx, cy + ch - nav, cw, nav, 0);
            for (int i = 0; i < 4; i++) {
                dot(canvas, i == 0 ? theme.accent : withAlpha(theme.textDim, 0x66),
                        cx + cw * (i + 0.5f) / 4f, cy + ch - nav / 2, 9 * u);
            }
        }
        canvas.restore();
    }

    /** A navigation rail: what an app shows down its side once it is wide enough. */
    private void drawRail(Canvas canvas, float u, float x, float y, float h) {
        fill(canvas, theme.cardSolid, x, y, 72 * u, h, 0);
        for (int i = 0; i < 4; i++) {
            float cy = y + (36 + i * 64) * u;
            if (cy + 12 * u > y + h) break;
            if (i == 0) fill(canvas, theme.accentSoft, x + 12 * u, cy - 16 * u, 48 * u, 32 * u, 16 * u);
            dot(canvas, i == 0 ? theme.accent : withAlpha(theme.textDim, 0x66), x + 36 * u, cy, 10 * u);
        }
    }

    /** A list of rows — avatar, title, a line under it — as tall as there is room. */
    private void drawList(Canvas canvas, float u, float x, float y, float w, float h,
                          boolean beside) {
        float pad = 12 * u;
        fill(canvas, withAlpha(theme.text, 0xCC), x + pad + 4 * u, y + 16 * u,
                Math.min(120 * u, w * 0.5f), 11 * u, 5 * u);
        float row = 60 * u;
        float top = y + 44 * u;
        for (int i = 0; top + row <= y + h + row * 0.5f && i < 40; i++, top += row) {
            if (beside && i == 0) {
                fill(canvas, theme.accentSoft, x + pad / 2, top + 2 * u, w - pad, row - 4 * u, 12 * u);
            }
            dot(canvas, withAlpha(ICONS[i % ICONS.length], 0xCC), x + pad + 20 * u, top + row / 2, 16 * u);
            float tx = x + pad + 46 * u;
            float avail = Math.max(0, x + w - pad - tx);
            fill(canvas, withAlpha(theme.text, 0xAA), tx, top + row / 2 - 10 * u,
                    avail * 0.62f, 8 * u, 4 * u);
            fill(canvas, withAlpha(theme.textFaint, 0x88), tx, top + row / 2 + 4 * u,
                    avail * 0.4f, 7 * u, 3.5f * u);
        }
        if (beside) {
            paint.setColor(theme.divider);
            canvas.drawRect(x + w - Math.max(1f, u), y, x + w, y + h, paint);
        }
    }

    /** The detail pane beside a list: a heading, a picture, and a paragraph. */
    private void drawDetail(Canvas canvas, float u, float x, float y, float w, float h) {
        float pad = 20 * u;
        float inner = Math.max(0, w - 2 * pad);
        fill(canvas, withAlpha(theme.text, 0xCC), x + pad, y + 18 * u, inner * 0.45f, 14 * u, 7 * u);
        fill(canvas, withAlpha(theme.textFaint, 0x99), x + pad, y + 42 * u, inner * 0.28f, 9 * u, 4.5f * u);
        float media = Math.min(220 * u, h * 0.38f);
        fill(canvas, withAlpha(theme.accent, 0x30), x + pad, y + 66 * u, inner, media, 14 * u);
        float line = y + 66 * u + media + 22 * u;
        float[] widths = {0.94f, 0.9f, 0.96f, 0.62f};
        for (int i = 0; i < widths.length && line + 8 * u < y + h; i++, line += 20 * u) {
            fill(canvas, withAlpha(theme.textDim, 0x66), x + pad, line, inner * widths[i], 8 * u, 4 * u);
        }
    }

    /**
     * The taskbar, in whichever shell is picked. DeX: Back, Home and Recents
     * on the left, the Apps button and what is open in the middle. Windows 11:
     * Start and its icons centred. Both: the tray and the clock on the right.
     */
    private void drawTaskbar(Canvas canvas, float u, float top) {
        float h = screen.bottom - top;
        fill(canvas, theme.bar(), screen.left, top, screen.width(), h, 0);
        paint.setColor(theme.divider);
        canvas.drawRect(screen.left, top, screen.right, top + Math.max(1f, u), paint);

        float mid = top + h / 2;
        float icon = 28 * u;
        float pitch = 40 * u;
        if (theme.win11) {
            int count = 6;
            float start = screen.centerX() - count * pitch / 2 + (pitch - icon) / 2;
            for (int i = 0; i < count; i++) {
                int color = i == 0 ? theme.accent : withAlpha(ICONS[(i - 1) % ICONS.length], 0xDD);
                fill(canvas, color, start + i * pitch, mid - icon / 2, icon, icon, 7 * u);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                dot(canvas, withAlpha(theme.textDim, 0x99), screen.left + (30 + i * 44) * u, mid, 5 * u);
            }
            float apps = 76 * u;
            float left = screen.centerX() - (apps + 2 * pitch) / 2;
            fill(canvas, theme.field, left, mid - 18 * u, apps, 36 * u, 12 * u);
            fill(canvas, withAlpha(theme.text, 0xBB), left + 18 * u, mid - 4 * u, 40 * u, 8 * u, 4 * u);
            for (int i = 0; i < 2; i++) {
                fill(canvas, withAlpha(ICONS[i], 0xDD), left + apps + 12 * u + i * pitch,
                        mid - icon / 2, icon, icon, 8 * u);
            }
        }
        float right = screen.right - 14 * u;
        fill(canvas, withAlpha(theme.text, 0xCC), right - 38 * u, mid - 9 * u, 38 * u, 7 * u, 3.5f * u);
        fill(canvas, withAlpha(theme.textFaint, 0x99), right - 28 * u, mid + 3 * u, 28 * u, 5 * u, 2.5f * u);
        for (int i = 0; i < 3; i++) {
            dot(canvas, withAlpha(theme.textDim, 0x99), right - (58 + i * 22) * u, mid, 5 * u);
        }
        fill(canvas, theme.field, right - 172 * u, mid - 14 * u, 62 * u, 28 * u, 10 * u);
    }

    private void fill(Canvas canvas, int color, float x, float y, float w, float h, float radius) {
        if (w <= 0 || h <= 0) return;
        paint.setColor(color);
        r.set(x, y, x + w, y + h);
        if (radius > 0) {
            canvas.drawRoundRect(r, radius, radius, paint);
        } else {
            canvas.drawRect(r, paint);
        }
    }

    private void dot(Canvas canvas, int color, float cx, float cy, float radius) {
        paint.setColor(color);
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.round((color >>> 24) * alpha / 255f);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
