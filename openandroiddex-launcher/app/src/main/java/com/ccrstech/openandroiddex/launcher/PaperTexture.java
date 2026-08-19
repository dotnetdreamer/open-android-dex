package com.ccrstech.openandroiddex.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The grain behind Paper mode.
 *
 * Paper mode is not a palette swap with a warm tint — the thing that makes a
 * screen read as paper is that light lands on fibre instead of glass:
 * highlights diffuse, contrast falls off, and there is structure in what should
 * be a flat fill. So every surface in Paper mode is painted twice: the fill,
 * then a tiled grain over it, clipped to the same rounded rect.
 *
 * The tiles are generated, not shipped. They are small (160px), seamless, and
 * cached per texture for the life of the process, so the cost is one bitmap per
 * texture the user actually picks and a shader lookup per draw.
 *
 * Seamlessness is deliberate work, not luck: per-pixel noise wraps for free,
 * the weave lines are spaced to divide the tile exactly, the press blobs are
 * sampled from a lattice that wraps, and each vellum fibre is drawn nine times
 * (once per neighbouring tile) so the ones crossing an edge come back on the
 * other side. Without that, every surface would show a visible grid.
 */
final class PaperTexture {

    private PaperTexture() {
    }

    /** Texture ids, stored in prefs and offered in Settings. */
    static final String MATTE = "matte";
    static final String WEAVE = "weave";
    static final String PRESS = "press";
    static final String VELLUM = "vellum";

    static final String[] ALL = {MATTE, WEAVE, PRESS, VELLUM};

    private static final int TILE = 160;
    /** Fixed seed: the grain must be the same on every launch and every device. */
    private static final long SEED = 0x9E3779B97F4A7C15L;

    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    static synchronized Bitmap tile(String texture) {
        Bitmap cached = CACHE.get(texture);
        if (cached != null) return cached;
        Bitmap made = generate(texture == null ? MATTE : texture);
        CACHE.put(texture, made);
        return made;
    }

    /**
     * A surface painted in Paper mode: {@code color}, then grain over it, both
     * clipped to a rounded rect of {@code radiusPx}.
     *
     * @param grainAlpha 0–255; 0 gives a plain fill and costs nothing extra.
     */
    static Drawable surface(int color, float radiusPx, int grainAlpha, String texture) {
        return new PaperDrawable(color, radiusPx, grainAlpha, texture);
    }

    // ── tile generation ────────────────────────────────────────────────────

    private static Bitmap generate(String texture) {
        Bitmap bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        Random rnd = new Random(SEED + texture.hashCode());

        // Pixel layers accumulate in one buffer and are composited by hand.
        // Bitmap#setPixels REPLACES, so running two pixel passes over the same
        // bitmap silently threw the first one away — which is what made Cold
        // Press come out as plain noise with its blobs missing.
        int[] pixels = new int[TILE * TILE];
        // Strengths are tuned so the DEFAULT intensity lands around a 3%
        // luminance deviation per pixel — visible as a finish, not as noise —
        // and the top of the dial around 8%. They read as large numbers only
        // because they are multiplied by the intensity alpha before drawing.
        switch (texture) {
            case WEAVE:
                fibreNoise(pixels, rnd, 34);
                break;
            case PRESS:
                press(pixels, rnd);
                fibreNoise(pixels, rnd, 28);
                break;
            case VELLUM:
                fibreNoise(pixels, rnd, 22);
                break;
            case MATTE:
            default:
                fibreNoise(pixels, rnd, 46);
        }
        bmp.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE);

        // Stroke layers go through a Canvas, which composites for us.
        Canvas canvas = new Canvas(bmp);
        if (WEAVE.equals(texture)) weave(canvas, rnd);
        if (VELLUM.equals(texture)) vellum(canvas, rnd);
        return bmp;
    }

    /** Source-over of one straight-alpha colour onto the accumulating buffer. */
    private static void blend(int[] dst, int i, int a, int r, int g, int b) {
        if (a <= 0) return;
        int prev = dst[i];
        int da = prev >>> 24;
        if (da == 0) {
            dst[i] = Color.argb(a, r, g, b);
            return;
        }
        int keep = da * (255 - a) / 255;
        int outA = a + keep;
        if (outA == 0) {
            dst[i] = 0;
            return;
        }
        int outR = (r * a + ((prev >> 16) & 0xFF) * keep) / outA;
        int outG = (g * a + ((prev >> 8) & 0xFF) * keep) / outA;
        int outB = (b * a + (prev & 0xFF) * keep) / outA;
        dst[i] = Color.argb(outA, Math.min(255, outR), Math.min(255, outG), Math.min(255, outB));
    }

    /**
     * Per-pixel speckle — the base of every texture. Wraps by construction,
     * because no pixel depends on its neighbours.
     *
     * Light and dark in equal measure: paper fibre catches light on one side
     * and shadows on the other, and a one-sided noise would read as haze.
     */
    private static void fibreNoise(int[] pixels, Random rnd, int strength) {
        for (int i = 0; i < pixels.length; i++) {
            // triangular distribution: mostly near zero, occasional grain
            int n = (rnd.nextInt(strength + 1) + rnd.nextInt(strength + 1)) / 2;
            if (rnd.nextBoolean()) {
                blend(pixels, i, n, 255, 252, 240);
            } else {
                blend(pixels, i, n, 24, 20, 10);
            }
        }
    }

    /**
     * Woven cloth. Threads alternate lit and shadowed at half the pitch rather
     * than being drawn as pairs of ruled lines — a light line with a dark one
     * three pixels away reads as engineering paper, which is exactly what this
     * looked like on the first pass.
     *
     * A full-span line wraps at any offset, so the per-thread jitter that keeps
     * it from looking machine-drawn is free.
     */
    private static void weave(Canvas canvas, Random rnd) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int step = 8;                               // 160 / 8 = 20 threads each way
        for (int i = 0; i * step < TILE; i++) {
            float x = i * step + rnd.nextFloat() - 0.5f;
            paint.setStrokeWidth(0.9f + rnd.nextFloat() * 0.5f);
            paint.setColor(Color.argb(10 + rnd.nextInt(8), 255, 250, 235));
            canvas.drawLine(x, 0, x, TILE, paint);
            paint.setColor(Color.argb(7 + rnd.nextInt(6), 20, 16, 8));
            canvas.drawLine(x + step / 2f, 0, x + step / 2f, TILE, paint);
        }
        for (int i = 0; i * step < TILE; i++) {
            float y = i * step + rnd.nextFloat() - 0.5f;
            paint.setStrokeWidth(0.9f + rnd.nextFloat() * 0.5f);
            paint.setColor(Color.argb(9 + rnd.nextInt(8), 255, 250, 235));
            canvas.drawLine(0, y, TILE, y, paint);
            paint.setColor(Color.argb(6 + rnd.nextInt(6), 20, 16, 8));
            canvas.drawLine(0, y + step / 2f, TILE, y + step / 2f, paint);
        }
    }

    /**
     * Cold-press mottle: soft blobs from a wrapping value-noise lattice, the
     * coarse dimpled surface of watercolour paper.
     */
    private static void press(int[] pixels, Random rnd) {
        // Two octaves: a broad undulation for the sheet, finer dimples on top,
        // weighted towards the fine one. Cold-press paper is dimpled at about
        // a millimetre — weight it the other way and it reads as cloud cover.
        // The amplitude is deliberately close to the matte grain's: this is a
        // different SHAPE of texture, not a louder one.
        float[] coarse = lattice(rnd, 14);
        float[] fine = lattice(rnd, 40);
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                float v = sample(coarse, 14, x * 14 / (float) TILE, y * 14 / (float) TILE);
                float f = sample(fine, 40, x * 40 / (float) TILE, y * 40 / (float) TILE);
                // centre on zero so the dimples go both lighter and darker
                float signed = (v - 0.5f) * 2f * 0.4f + (f - 0.5f) * 2f * 0.6f;
                int a = Math.min(255, Math.round(Math.abs(signed) * 34f));
                if (signed >= 0) {
                    blend(pixels, y * TILE + x, a, 255, 250, 236);
                } else {
                    blend(pixels, y * TILE + x, a, 26, 22, 12);
                }
            }
        }
    }

    private static float[] lattice(Random rnd, int n) {
        float[] out = new float[n * n];
        for (int i = 0; i < out.length; i++) {
            out[i] = rnd.nextFloat();
        }
        return out;
    }

    /** Bilinear sample of a lattice that wraps at its edges. */
    private static float sample(float[] lattice, int n, float x, float y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        // smoothstep, so the blobs have soft shoulders instead of facets
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        int xa = Math.floorMod(x0, n), xb = Math.floorMod(x0 + 1, n);
        int ya = Math.floorMod(y0, n), yb = Math.floorMod(y0 + 1, n);
        float top = lattice[ya * n + xa] + (lattice[ya * n + xb] - lattice[ya * n + xa]) * fx;
        float bottom = lattice[yb * n + xa] + (lattice[yb * n + xb] - lattice[yb * n + xa]) * fx;
        return top + (bottom - top) * fy;
    }

    /**
     * Fibres suspended in a translucent sheet: many, short and faint.
     *
     * The first pass used 44 long bright strokes and read as scratches on a
     * lens — a fibre you can trace with your eye is a defect, a fibre you can
     * only see as texture is vellum.
     */
    private static void vellum(Canvas canvas, Random rnd) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 110; i++) {
            float x = rnd.nextFloat() * TILE;
            float y = rnd.nextFloat() * TILE;
            double angle = rnd.nextDouble() * Math.PI;
            float len = 3 + rnd.nextFloat() * 11;
            float dx = (float) Math.cos(angle) * len;
            float dy = (float) Math.sin(angle) * len;
            paint.setStrokeWidth(0.5f + rnd.nextFloat() * 0.6f);
            paint.setColor(rnd.nextInt(3) == 0
                    ? Color.argb(13 + rnd.nextInt(10), 26, 22, 12)
                    : Color.argb(15 + rnd.nextInt(13), 255, 251, 238));
            // nine copies: a fibre crossing an edge has to reappear opposite
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    canvas.drawLine(x + ox * TILE, y + oy * TILE,
                            x + dx + ox * TILE, y + dy + oy * TILE, paint);
                }
            }
        }
    }

    // ── the drawable ───────────────────────────────────────────────────────

    /** Fill, then grain, both clipped to the same rounded rect. */
    private static final class PaperDrawable extends Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final int baseAlpha;

        PaperDrawable(int color, float radiusPx, int grainAlpha, String texture) {
            this.radius = Math.max(0f, radiusPx);
            this.baseAlpha = Math.max(0, Math.min(255, grainAlpha));
            fill.setColor(color);
            grain.setAlpha(baseAlpha);
            grain.setShader(new BitmapShader(tile(texture),
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            rect.set(b);
            canvas.drawRoundRect(rect, radius, radius, fill);
            if (baseAlpha > 0) {
                canvas.drawRoundRect(rect, radius, radius, grain);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            grain.setAlpha(baseAlpha * alpha / 255);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
