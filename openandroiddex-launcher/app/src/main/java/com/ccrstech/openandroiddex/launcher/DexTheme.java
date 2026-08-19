package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Color;

/**
 * The desktop's palette and surface geometry, derived from {@link DexPrefs}.
 *
 * Everything that paints — taskbar, drawer, desktop grid, window captions,
 * Settings — asks this class for its colours instead of holding literals, so
 * "Dark mode" and the glass sliders are one switch rather than a hunt through
 * four files.
 *
 * Loaded lazily and cached: these are read in tight loops (every tile, every
 * caption) and re-reading SharedPreferences there would be silly.
 * {@link #invalidate()} drops the cache; DexPrefs calls it on every write.
 */
final class DexTheme {

    private static DexTheme cached;

    /** "dark" | "light" | "paper". */
    final String mode;
    /**
     * True when surfaces are dark and ink is light — Paper counts, its olive
     * is dark. Everything that only needs to know "which way round is this"
     * asks here rather than comparing mode strings.
     */
    final boolean dark;
    /** Paper mode: surfaces carry a grain, see {@link PaperTexture}. */
    final boolean paper;
    /**
     * "Reduce quality" is on — see {@link DexPrefs#KEY_PERF}. Everything that
     * costs a frame for looks alone asks here before spending it.
     */
    final boolean perf;
    /** Grain alpha (0–255), already scaled by the intensity setting. */
    final int grainAlpha;
    final String paperTexture;
    final boolean glass;
    /** 0–100, straight from prefs; {@link #blurPx(float)} turns it into pixels. */
    final int blur;
    private final float surfaceAlpha;
    private final float roundFactor;

    // ── palette ──
    /** Fallback backdrop, seen only where the wallpaper does not reach. */
    final int backdrop;
    /** Solid body of a window we own (Settings). */
    final int window;
    /** A card/popup sitting on {@link #window}. */
    final int cardSolid;
    /** The app drawer's full-surface panel. */
    final int panelSolid;
    /** The taskbar strip. */
    final int barSolid;
    /** Window caption strip (always chrome-coloured, never see-through). */
    final int caption;
    final int text;
    final int textDim;
    final int textFaint;
    final int accent;
    final int accentSoft;
    final int divider;
    final int hover;
    final int ripple;
    final int field;
    final int danger;
    final int positive;
    /** Text drawn straight onto the wallpaper — needs its own contrast. */
    final int deskText;

    private DexTheme(Context ctx) {
        mode = DexPrefs.theme(ctx);
        paper = DexPrefs.THEME_PAPER.equals(mode);
        dark = paper || !DexPrefs.THEME_LIGHT.equals(mode);
        paperTexture = DexPrefs.getString(ctx, DexPrefs.KEY_PAPER_TEXTURE,
                DexPrefs.DEF_PAPER_TEXTURE);
        perf = DexPrefs.getBool(ctx, DexPrefs.KEY_PERF, DexPrefs.DEF_PERF);
        // Capped well below opaque: grain is a finish, and past about a third
        // it stops reading as paper and starts reading as dirt on the screen.
        //
        // Reduce quality takes it to zero: the grain is a tiled BitmapShader
        // over every surface in the shell, which is the most expensive thing
        // Paper mode does per painted rect.
        grainAlpha = paper && !perf
                ? Math.round(DexPrefs.pct(DexPrefs.getInt(ctx, DexPrefs.KEY_GRAIN,
                DexPrefs.DEF_GRAIN)) / 100f * 235f)
                : 0;
        // The one place Reduce quality has to reach: blur and transparency are
        // both derived from `glass` below, so forcing it off here turns off the
        // cross-window blur (a SurfaceFlinger pass per frame, on a display that
        // is being video-encoded) AND makes every surface opaque, which is what
        // takes the wallpaper out of the blend behind the taskbar and the cards.
        glass = !perf && DexPrefs.getBool(ctx, DexPrefs.KEY_GLASS, DexPrefs.DEF_GLASS);
        blur = glass ? DexPrefs.pct(DexPrefs.getInt(ctx, DexPrefs.KEY_BLUR, DexPrefs.DEF_BLUR)) : 0;
        int transparency = glass
                ? DexPrefs.pct(DexPrefs.getInt(ctx, DexPrefs.KEY_TRANSPARENCY, DexPrefs.DEF_TRANSPARENCY))
                : 0;
        // 0% = solid, 100% = the most see-through a surface may get and still
        // hold legible text over a bright wallpaper.
        surfaceAlpha = 1f - transparency / 100f * 0.62f;
        int rounding = DexPrefs.pct(DexPrefs.getInt(ctx, DexPrefs.KEY_ROUNDING, DexPrefs.DEF_ROUNDING));
        // 50% is the design's own radii; 0 squares everything off, 100 doubles it.
        roundFactor = rounding / 50f;

        if (paper) {
            // Warm olive under cream ink — the palette a matte, low-contrast
            // surface wants. Nothing here is pure white or pure black: paper
            // has neither, and the point of the mode is the contrast falls off.
            backdrop = 0xFF2b3018;
            window = 0xFF3a4023;
            cardSolid = 0xFF525a34;
            panelSolid = 0xFF454b2c;
            barSolid = 0xFF3a4023;
            caption = 0xFF33381e;
            text = 0xFFf2ecd8;
            textDim = 0xFFd5cfb1;
            textFaint = 0xFFa6a484;
            accent = 0xFFefe4b8;
            accentSoft = 0x2aefe4b8;
            divider = 0x22f2ecd8;
            hover = 0x18f2ecd8;
            ripple = 0x55f2ecd8;
            field = 0x1cf2ecd8;
            danger = 0xFFe08363;
            positive = 0xFFb6c98f;
            deskText = 0xFFf2ecd8;
        } else if (dark) {
            backdrop = 0xFF0b0d10;
            window = 0xFF0d1117;
            cardSolid = 0xFF151b26;
            panelSolid = 0xFF0b0d10;
            barSolid = 0xFF0a0c10;
            caption = 0xFF0a0c10;
            text = 0xFFe7ecf3;
            textDim = 0xFFc4ccd8;
            textFaint = 0xFF7a8699;
            accent = 0xFF4d9fff;
            accentSoft = 0x1F4d9fff;
            divider = 0x1AFFFFFF;
            hover = 0x14FFFFFF;
            ripple = 0x66FFFFFF;
            field = 0x14FFFFFF;
            danger = 0xFFff5c5c;
            positive = 0xFF4ade80;
            deskText = 0xFFe7ecf3;
        } else {
            backdrop = 0xFFeef1f6;
            window = 0xFFf6f8fb;
            cardSolid = 0xFFffffff;
            panelSolid = 0xFFf3f5f9;
            barSolid = 0xFFf7f9fc;
            caption = 0xFFe8ecf3;
            text = 0xFF101828;
            textDim = 0xFF475467;
            textFaint = 0xFF667085;
            accent = 0xFF1668dc;
            accentSoft = 0x1F1668dc;
            divider = 0x1A101828;
            hover = 0x14101828;
            ripple = 0x33101828;
            field = 0x0F101828;
            danger = 0xFFd92d20;
            positive = 0xFF15803d;
            deskText = 0xFF101828;
        }
    }

    static DexTheme of(Context ctx) {
        DexTheme t = cached;
        if (t == null) {
            t = new DexTheme(ctx.getApplicationContext());
            cached = t;
        }
        return t;
    }

    static void invalidate() {
        cached = null;
    }

    // ── surfaces ──

    /** Apply the transparency slider to one of the solid colours above. */
    private int glassy(int solid) {
        int a = Math.round(Color.alpha(solid) * surfaceAlpha);
        return (Math.max(0, Math.min(255, a)) << 24) | (solid & 0x00FFFFFF);
    }

    int windowBg() {
        return window;                    // the Settings body stays solid: it is the backdrop
    }

    int card() {
        return glassy(cardSolid);
    }

    int panel() {
        // the drawer covers the whole desktop — keep a floor under it so the
        // icon grid behind never fights the app list for attention
        int a = Math.round(Color.alpha(panelSolid) * Math.max(surfaceAlpha, 0.72f));
        return (a << 24) | (panelSolid & 0x00FFFFFF);
    }

    int bar() {
        return glassy(barSolid);
    }

    /**
     * A painted surface: a rounded fill in every theme, and in Paper mode the
     * same rect with grain over it.
     *
     * Every background in the shell goes through here rather than setting a
     * colour directly — that is what makes Paper mode one switch instead of a
     * hunt through the taskbar, the drawer, the cards and the captions.
     */
    android.graphics.drawable.Drawable surface(int color, float radiusPx) {
        if (paper && grainAlpha > 0) {
            return PaperTexture.surface(color, radiusPx, grainAlpha, paperTexture);
        }
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Radius in dp, after the rounding slider. */
    float radius(float baseDp) {
        return baseDp * roundFactor;
    }

    /** Blur-behind radius in px for a window, or 0 when blur is off. */
    int blurPx(float density) {
        if (blur <= 0) return 0;
        return Math.round(blur / 100f * 48f * density / 160f);
    }
}
