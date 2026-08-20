package com.ccrstech.openandroiddex.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The one shape the Windows 11 shell cannot borrow from a font: the Start
 * mark.
 *
 * Everything else in that shell is drawn from {@link DexTheme}'s palette and
 * the glyphs the desktop already uses — a taskbar is a row of rounded fills and
 * a Start menu is a card, and neither needs a bitmap. The four-pane mark does:
 * no Android device ships a font carrying it, an image asset would have to be
 * re-exported per density and per theme, and the shape is four rounded
 * rectangles.
 *
 * Deliberately not Microsoft's artwork — no logotype, no brand gradient. This
 * is our own drawing of a four-pane window, painted in whatever colour the
 * caller's theme is using, in the same spirit as the painted wallpapers: the
 * desktop should READ as Windows without carrying anyone else's assets.
 */
final class Win11 {

    private Win11() {
    }

    /** The Start mark in one colour, sized to whatever bounds it is given. */
    static Drawable logo(int color) {
        return new Logo(color);
    }

    private static final class Logo extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Logo(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            // The gutter between the panes is what makes the mark read as four
            // windows rather than one grid, and it is proportional so the shape
            // survives every density the desktop can be driven at.
            float gapX = b.width() * 0.13f;
            float gapY = b.height() * 0.13f;
            float paneW = (b.width() - gapX) / 2f;
            float paneH = (b.height() - gapY) / 2f;
            float radius = Math.max(1f, Math.min(paneW, paneH) * 0.12f);
            for (int i = 0; i < 4; i++) {
                float left = b.left + (i % 2) * (paneW + gapX);
                float top = b.top + (i / 2) * (paneH + gapY);
                canvas.drawRoundRect(left, top, left + paneW, top + paneH,
                        radius, radius, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
