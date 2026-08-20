package com.ccrstech.openandroiddex.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The desktop backdrops offered in Settings → Wallpaper.
 *
 * They are painted, not shipped: a handful of gradients and soft blooms drawn
 * into a bitmap. That keeps the APK free of megabytes of photography (it is
 * reinstalled over adb on every connect), makes every wallpaper resolution- and
 * aspect-independent, and lets the same code render both the desktop and the
 * little previews in the picker.
 *
 * The bitmap is rendered once per size and upscaled — these are smooth
 * gradients, so a ~720px render is indistinguishable from a native-resolution
 * one and costs a fraction of the memory on a 4K desktop.
 */
final class Wallpapers {

    private Wallpapers() {
    }

    /** id, display name, and the palette the painter works from. */
    static final class Spec {
        final String id;
        final String name;
        /** Base vertical gradient, top → bottom. */
        final int[] base;
        /** Soft blooms: {colour, cx%, cy%, radius% of the diagonal}. */
        final int[][] blooms;
        /**
         * True when the wallpaper is pale enough that white icon labels stop
         * being readable on it — the desktop asks for this and flips its ink.
         */
        final boolean light;
        /**
         * Colour at the rim, fading to nothing towards the middle; 0 for none.
         *
         * Blooms only ever ADD light, and a surface that is lit everywhere
         * reads as a flat fill. Darkening the edges is what makes a sheet look
         * like an object under a lamp rather than a gradient.
         */
        final int vignette;

        Spec(String id, String name, int[] base, int[][] blooms) {
            this(id, name, base, blooms, false, 0);
        }

        Spec(String id, String name, int[] base, int[][] blooms, boolean light) {
            this(id, name, base, blooms, light, 0);
        }

        Spec(String id, String name, int[] base, int[][] blooms, boolean light, int vignette) {
            this.id = id;
            this.name = name;
            this.base = base;
            this.blooms = blooms;
            this.light = light;
            this.vignette = vignette;
        }
    }

    static final Spec[] ALL = {
            new Spec("midnight", "Midnight", new int[]{0xFF0d1526, 0xFF0b0d10, 0xFF101a14},
                    new int[][]{
                            {0x664d9fff, 18, 22, 62},
                            {0x3322d3ee, 82, 12, 48},
                            {0x338b5cf6, 62, 88, 55},
                    }),
            new Spec("aurora", "Aurora", new int[]{0xFF071a1c, 0xFF04262a, 0xFF061014},
                    new int[][]{
                            {0x8834d399, 22, 78, 60},
                            {0x5538bdf8, 74, 26, 58},
                            {0x33a78bfa, 46, 6, 44},
                    }),
            new Spec("ember", "Ember", new int[]{0xFF1a0b12, 0xFF2a0f10, 0xFF12070a},
                    new int[][]{
                            {0x88f97316, 78, 76, 58},
                            {0x66e11d48, 24, 34, 62},
                            {0x33fbbf24, 58, 96, 40},
                    }),
            new Spec("mist", "Mist", new int[]{0xFF141a24, 0xFF1b2330, 0xFF0f141c},
                    new int[][]{
                            {0x5594a3b8, 30, 20, 70},
                            {0x44c4b5fd, 82, 62, 52},
                            {0x2238bdf8, 12, 92, 46},
                    }),
            new Spec("dune", "Dune", new int[]{0xFF1c1409, 0xFF2b1f10, 0xFF130e08},
                    new int[][]{
                            {0x77f59e0b, 26, 84, 62},
                            {0x55fb923c, 84, 30, 52},
                            {0x33fde68a, 54, 4, 40},
                    }),
            // the one pale backdrop, so light mode has a desktop that matches it
            new Spec("linen", "Linen", new int[]{0xFFf3f5fa, 0xFFe8ecf5, 0xFFf6f3ee},
                    new int[][]{
                            {0x55bfd4ff, 24, 22, 62},
                            {0x44ffd6c2, 80, 74, 56},
                            {0x33d9c9ff, 62, 6, 44},
                    }, true),
            // The pair the Windows 11 shell opens on: a lit bloom over deep
            // blue for its dark mode, and the same shape washed out for its
            // light one. Painted like every other backdrop here — the point is
            // a desktop that reads as Windows at a glance, not a copy of
            // Microsoft's photograph.
            new Spec("bloom", "Bloom", new int[]{0xFF06224e, 0xFF091a3d, 0xFF040c22},
                    new int[][]{
                            {0x9932a4ff, 50, 47, 44},   // the lit heart of the flower
                            {0x6600cfff, 29, 68, 52},   // cool petal, lower left
                            {0x559a6cff, 73, 29, 50},   // violet petal, upper right
                            {0x33ff9ad5, 63, 76, 34},   // warm rim where the two meet
                    }, false, 0x66040a18),
            new Spec("bloomlight", "Bloom Light", new int[]{0xFFdfeafb, 0xFFc9dcf7, 0xFFedf3fc},
                    new int[][]{
                            {0x88ffffff, 50, 45, 42},
                            {0x557fb8ff, 27, 70, 54},
                            {0x44c4b0ff, 75, 26, 50},
                            {0x33ffd0e8, 65, 79, 34},
                    }, true),
            // the two that pair with Paper mode: its own olive, and the
            // parchment it is named after
            new Spec("moss", "Moss", new int[]{0xFF2f3519, 0xFF3c4322, 0xFF262b14},
                    new int[][]{
                            {0x668a9a4e, 22, 26, 64},
                            {0x44d9cf8a, 82, 70, 54},
                            {0x2a6f7f3f, 54, 96, 46},
                    }),
            new Spec("parchment", "Parchment", new int[]{0xFFf3e9cf, 0xFFe8dcbb, 0xFFf6efdd},
                    new int[][]{
                            {0x55d8c48f, 26, 24, 62},
                            {0x44c9c98f, 80, 72, 54},
                            {0x33efe0b4, 58, 4, 44},
                    }, true),
            // A sheet of handmade stock under a desk lamp: warm cream, the
            // light falling from the upper left, and the corners dropping into
            // sepia. Made for Paper mode — with the grain over it, the vignette
            // is what turns a gradient into something that looks like it has
            // a thickness.
            new Spec("deckle", "Deckle", new int[]{0xFFeaddbe, 0xFFd3c193, 0xFFc2ab7c},
                    new int[][]{
                            {0x59fff6dd, 22, 16, 62},   // the lamp, upper left
                            {0x4ed8b878, 82, 78, 60},   // warm bounce, lower right
                            {0x38a08f5e, 8, 96, 46},    // the shaded corner
                    }, true, 0x8c4e4224),
    };

    static Spec spec(String id) {
        for (Spec s : ALL) {
            if (s.id.equals(id)) return s;
        }
        return ALL[0];
    }

    /**
     * Ink for text drawn straight onto the desktop (icon labels, the empty
     * hint). Follows the wallpaper rather than the theme: the wallpaper is
     * what is actually behind those pixels. A heavy darkness overlay turns a
     * pale wallpaper dark again, so it counts too.
     */
    static boolean lightInk(String id, int dimPercent) {
        return !spec(id).light || dimPercent >= 45;
    }

    /** Longest edge of the rendered bitmap; everything above is upscaled. */
    private static final int RENDER_MAX = 720;

    static Drawable drawable(String id, int dimPercent) {
        return drawable(id, dimPercent, 0, PaperTexture.MATTE);
    }

    /**
     * The desktop backdrop, optionally grained.
     *
     * Paper mode puts the grain on the wallpaper as well as on the panels,
     * because the desktop is a surface too — grain that stopped at the edge of
     * the taskbar would read as a texture applied to widgets rather than a
     * matte finish over the whole thing.
     */
    static Drawable drawable(String id, int dimPercent, int grainAlpha, String texture) {
        return new WallpaperDrawable(spec(id), Math.max(0, Math.min(80, dimPercent)),
                Math.max(0, Math.min(255, grainAlpha)), texture);
    }

    /** A fixed-size render, for the picker's thumbnails. */
    static Bitmap thumbnail(String id, int w, int h, int dimPercent) {
        Bitmap bmp = render(spec(id), Math.max(1, w), Math.max(1, h));
        if (dimPercent > 0) {
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.argb(Math.round(255 * dimPercent / 100f), 0, 0, 0));
        }
        return bmp;
    }

    private static Bitmap render(Spec spec, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setShader(new LinearGradient(0, 0, w * 0.35f, h, spec.base,
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        float diagonal = (float) Math.hypot(w, h);
        for (int[] bloom : spec.blooms) {
            float cx = w * bloom[1] / 100f;
            float cy = h * bloom[2] / 100f;
            float radius = Math.max(1f, diagonal * bloom[3] / 100f);
            // transparent at the rim so the blooms melt into each other rather
            // than stacking up as visible discs
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[]{bloom[0], bloom[0] & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
        }

        if (spec.vignette != 0) {
            // Centred on the lit corner rather than the middle, so the fall-off
            // runs with the light instead of ringing the sheet symmetrically.
            //
            // The radius has to be about 0.62 of the diagonal for the far
            // corner to actually REACH full strength: at a radius near the
            // diagonal itself the corners sit barely a third of the way along
            // the ramp and the vignette is invisible.
            float cx = w * 0.38f;
            float cy = h * 0.34f;
            float radius = diagonal * 0.62f;
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[]{spec.vignette & 0x00FFFFFF, spec.vignette & 0x00FFFFFF,
                            spec.vignette},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
        return bmp;
    }

    /**
     * Renders {@link #render} at (a capped fraction of) its bounds and scales
     * it up, re-rendering only when the bounds actually change shape — a
     * display-size change, not every draw pass.
     */
    private static final class WallpaperDrawable extends Drawable {

        private final Spec spec;
        private final int dim;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        /** Grain painted over the wallpaper in Paper mode; 0 elsewhere. */
        private final Paint grain;
        private Bitmap bitmap;
        private int builtW, builtH;

        WallpaperDrawable(Spec spec, int dim, int grainAlpha, String texture) {
            this.spec = spec;
            this.dim = dim;
            if (grainAlpha > 0) {
                grain = new Paint(Paint.FILTER_BITMAP_FLAG);
                grain.setAlpha(grainAlpha);
                grain.setShader(new android.graphics.BitmapShader(PaperTexture.tile(texture),
                        Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            } else {
                grain = null;
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            if (bitmap == null || builtW != b.width() || builtH != b.height()) {
                builtW = b.width();
                builtH = b.height();
                float scale = Math.min(1f, RENDER_MAX / (float) Math.max(builtW, builtH));
                bitmap = render(spec, Math.max(1, Math.round(builtW * scale)),
                        Math.max(1, Math.round(builtH * scale)));
            }
            canvas.drawBitmap(bitmap, null, b, paint);
            if (dim > 0) {
                canvas.drawColor(Color.argb(Math.round(255 * dim / 100f), 0, 0, 0));
            }
            if (grain != null) {
                canvas.drawRect(b, grain);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
