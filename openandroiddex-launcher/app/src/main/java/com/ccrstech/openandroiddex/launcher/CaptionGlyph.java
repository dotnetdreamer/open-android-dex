package com.ccrstech.openandroiddex.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The three title-bar marks the computer's window for an app has and the desktop's bars did
 * not: Back, More and Sound (apps/win/frame.rs). Drawn as strokes rather than set as text: ☰
 * and a speaker are not in every phone's fonts, and a missing one is a tofu box in the one
 * place a window says what it can do. The other bar buttons are text (◧ ◨ — ▢ ✕), which
 * every font the desktop has been seen on draws.
 */
final class CaptionGlyph extends Drawable {

    static final int BACK = 0;
    static final int MORE = 1;
    static final int SOUND = 2;

    private final int kind;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();

    /**
     * @param size   its box, in the desktop display's pixels
     * @param stroke its line, in the same pixels
     */
    CaptionGlyph(int kind, int size, float stroke, int color) {
        this.kind = kind;
        this.size = size;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float x0 = b.left + (b.width() - s) / 2f;
        float y0 = b.top + (b.height() - s) / 2f;
        path.reset();
        switch (kind) {
            case BACK:
                // ← : a shaft and a head, the head's arms a quarter of the box each way.
                path.moveTo(x0 + s * 0.84f, y0 + s * 0.5f);
                path.lineTo(x0 + s * 0.16f, y0 + s * 0.5f);
                path.moveTo(x0 + s * 0.44f, y0 + s * 0.22f);
                path.lineTo(x0 + s * 0.16f, y0 + s * 0.5f);
                path.lineTo(x0 + s * 0.44f, y0 + s * 0.78f);
                canvas.drawPath(path, paint);
                break;
            case MORE:
                // ☰
                for (float y : new float[]{0.24f, 0.5f, 0.76f}) {
                    canvas.drawLine(x0 + s * 0.14f, y0 + s * y, x0 + s * 0.86f, y0 + s * y, paint);
                }
                break;
            default:
                // A speaker and two waves. The cone is a closed outline, as the stroke is
                // everywhere else on the bar.
                path.moveTo(x0 + s * 0.10f, y0 + s * 0.38f);
                path.lineTo(x0 + s * 0.28f, y0 + s * 0.38f);
                path.lineTo(x0 + s * 0.50f, y0 + s * 0.18f);
                path.lineTo(x0 + s * 0.50f, y0 + s * 0.82f);
                path.lineTo(x0 + s * 0.28f, y0 + s * 0.62f);
                path.lineTo(x0 + s * 0.10f, y0 + s * 0.62f);
                path.close();
                canvas.drawPath(path, paint);
                arc.set(x0 + s * 0.42f, y0 + s * 0.34f, x0 + s * 0.74f, y0 + s * 0.66f);
                canvas.drawArc(arc, -50f, 100f, false, paint);
                arc.set(x0 + s * 0.40f, y0 + s * 0.16f, x0 + s * 0.94f, y0 + s * 0.84f);
                canvas.drawArc(arc, -50f, 100f, false, paint);
                break;
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
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
