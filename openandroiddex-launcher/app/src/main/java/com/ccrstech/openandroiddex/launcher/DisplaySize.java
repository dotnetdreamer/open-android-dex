package com.ccrstech.openandroiddex.launcher;

/**
 * The desktop's Display size as arithmetic: what density a choice stands for,
 * how far the control may go on this display, where a density sits on its
 * slider, and how much room it leaves an app.
 *
 * Plain Java on purpose — nothing from Android — so test/display-size-test.mjs
 * can run every line of it on a computer's JVM.
 *
 * A size is a multiple of the desktop's Default density, never a dpi: the
 * Default is the computer's (one dp per point of its UI, 160 on a 1x monitor,
 * 320 on a Retina laptop) and the same choice has to mean the same size on
 * both. The five presets are fixed multiples; a size between or past them is
 * a multiple too, kept to four decimals, and the dpi it means is worked out
 * again on every display it is applied to.
 */
final class DisplaySize {

    private DisplaySize() {
    }

    /** The five named sizes — MUST match SIZE_FACTORS in the desktop app's display.rs. */
    static final float[] PRESETS = {0.75f, 0.875f, 1f, 1.25f, 1.5f};
    static final int DEFAULT_INDEX = 2;

    /**
     * The densities a desktop may be given at all — MUST match DPI_MIN and
     * DPI_MAX in the desktop app's scrcpy.rs, whose request pump drops
     * anything outside them.
     */
    static final int DPI_MIN = 70;
    static final int DPI_MAX = 960;

    /**
     * A multiple this close to a preset IS that preset: a dpi rounded to a
     * whole number divides back into one only to within 0.5 / Default, and
     * without the snap Default would come back as 1.0026. MUST match SNAP in
     * display.rs, as must the tolerance below, which keeps a float here and a
     * double there agreeing on a multiple exactly 0.01 away.
     */
    static final float SNAP = 0.01f;
    private static final double SNAP_TOLERANCE = 1e-6;

    /** Bounds on any multiple that is kept — MUST match display.rs. */
    static final float MIN_FACTOR = 0.5f;
    static final float MAX_FACTOR = 4f;

    /**
     * The smallest desktop the control lets a density leave, in dp: a phone
     * held sideways. Any denser and the taskbar, the Start menu and the
     * Settings window itself stop fitting — and the way back is inside them.
     */
    static final int MIN_SHORT_DP = 360;
    static final int MIN_LONG_DP = 640;

    /** LauncherActivity.TASKBAR_DP — the shell's 52dp contract with the PC. */
    static final int TASKBAR_DP = 52;
    /** CaptionService.CAPTION_DP: the title bar every desktop window wears. */
    static final int CAPTION_DP = 40;

    /**
     * The narrowest content apps lay out as a tablet: Android's sw600dp and
     * the Medium window size class both start here.
     */
    static final int TABLET_DP = 600;
    /** Material's Expanded width class: room for a navigation rail beside two panes. */
    static final int EXPANDED_DP = 840;

    /** Apps keep to their phone layout: the window is narrower than a small tablet. */
    static final int LAYOUT_PHONE = 0;
    /** Wide enough for a tablet layout by width, too short for one by smallest width. */
    static final int LAYOUT_MIXED = 1;
    /** A tablet's room both ways: every app with a tablet layout uses it. */
    static final int LAYOUT_TABLET = 2;

    // ── what a choice is kept as ──

    /**
     * A multiple as it is kept: a preset when it is within {@link #SNAP} of
     * one, otherwise bounded and rounded to four decimals.
     */
    static float normalize(float factor) {
        if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f) return 1f;
        for (float preset : PRESETS) {
            if (Math.abs((double) factor - preset) <= SNAP + SNAP_TOLERANCE) return preset;
        }
        float bounded = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
        return Math.round(bounded * 10000f) / 10000f;
    }

    /** The multiple a density stands for on a desktop whose Default is {@code base}. */
    static float factorOf(int dpi, int base) {
        return normalize(dpi / (float) Math.max(1, base));
    }

    /**
     * The density a multiple stands for — MUST match apply_stored_density in
     * the desktop app's scrcpy.rs, or the two would take turns correcting each
     * other on every start.
     */
    static int dpiFor(int base, float factor) {
        return clamp(Math.round(base * normalize(factor)), DPI_MIN, DPI_MAX);
    }

    /** Which preset a multiple is, or -1 for a size of the user's own. */
    static int presetIndex(float factor) {
        float f = normalize(factor);
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == f) return i;
        }
        return -1;
    }

    /** The preset nearest a multiple — what display_size_index still holds. */
    static int nearestPreset(float factor) {
        int best = 0;
        for (int i = 1; i < PRESETS.length; i++) {
            if (Math.abs(PRESETS[i] - factor) < Math.abs(PRESETS[best] - factor)) best = i;
        }
        return best;
    }

    /** The five presets as densities on a desktop whose Default is {@code base}. */
    static int[] presetDpis(int base) {
        int[] out = new int[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) out[i] = dpiFor(base, PRESETS[i]);
        return out;
    }

    // ── how far the control goes ──

    /** The least dense it offers: everything at half the computer's own size. */
    static int lowestDpi(int base) {
        return Math.max(DPI_MIN, Math.round(base * MIN_FACTOR));
    }

    /**
     * The densest it offers on a {@code w} x {@code h} display whose Default
     * is {@code base}: what still leaves a phone held sideways, and never
     * past {@link #MAX_FACTOR} times the Default — the most a choice is kept
     * as, so anything denser would be applied as that.
     */
    static int highestDpi(int base, int w, int h) {
        int byShort = (int) Math.floor(160.0 * Math.min(w, h) / MIN_SHORT_DP);
        int byLong = (int) Math.floor(160.0 * Math.max(w, h) / MIN_LONG_DP);
        int byFactor = (int) Math.floor(Math.max(1, base) * (double) MAX_FACTOR);
        return clamp(Math.min(Math.min(byShort, byLong), byFactor), DPI_MIN, DPI_MAX);
    }

    /**
     * The slider: densities on a logarithmic track, because a size is felt as
     * a ratio — 120 to 160 is the same step as 240 to 320 — and a linear one
     * would pile the five presets into its first third.
     *
     * It always reaches every preset and the size in force, wherever the
     * display's own bounds fall: a desktop that follows its window can be made
     * smaller than the size chosen on a bigger one, and the control has to be
     * able to show where it is before it can move it. So on a small display
     * at a high Default, Large and Very large can leave less than the
     * 640 x 360 dp {@link #highestDpi} keeps to: they were there before the
     * slider was, and stay one click away.
     */
    static final class Track {

        static final int STEPS = 1000;
        /** How near a preset the thumb has to come, in steps, to settle on it. */
        static final int DETENT = 14;

        final int base;
        final int lo;
        final int hi;
        final int[] presets;

        Track(int base, int w, int h, int current) {
            this.base = Math.max(1, base);
            presets = presetDpis(this.base);
            int low = Math.min(lowestDpi(this.base), Math.min(presets[0], current));
            int high = Math.max(highestDpi(this.base, w, h),
                    Math.max(presets[presets.length - 1], current));
            lo = clamp(low, DPI_MIN, DPI_MAX - 1);
            hi = clamp(high, lo + 1, DPI_MAX);
        }

        /** Where a density sits on the track, 0 to {@link #STEPS}. */
        int position(int dpi) {
            double t = Math.log(clamp(dpi, lo, hi) / (double) lo) / Math.log(hi / (double) lo);
            return clamp((int) Math.round(t * STEPS), 0, STEPS);
        }

        /**
         * The density under the thumb: a preset when it is inside one's
         * detent, the end itself at either end, otherwise the nearest 5 dpi —
         * and in every case the density a choice there would really be
         * applied at.
         */
        int dpiAt(int position) {
            for (int preset : presets) {
                if (Math.abs(position - position(preset)) <= DETENT) return preset;
            }
            // 756 is the top of a Retina laptop's track, not 755
            if (position <= 0) return resolve(lo);
            if (position >= STEPS) return resolve(hi);
            double dpi = lo * Math.pow(hi / (double) lo, clamp(position, 0, STEPS) / (double) STEPS);
            return resolve(clamp((int) (Math.round(dpi / 5.0) * 5), lo, hi));
        }

        /**
         * One press of − or +: the next multiple of ten that way, landing on
         * any preset on the way rather than stepping over it. Never a press
         * that changes nothing, unless the track has run out.
         */
        int step(int dpi, int direction) {
            int from = dpi;
            for (int guard = 0; guard < 8; guard++) {
                int next = direction > 0 ? (from / 10 + 1) * 10 : ((from + 9) / 10 - 1) * 10;
                for (int preset : presets) {
                    if (direction > 0 && preset > from && preset < next) next = preset;
                    if (direction < 0 && preset < from && preset > next) next = preset;
                }
                next = clamp(next, lo, hi);
                int resolved = resolve(next);
                if (resolved != dpi) return resolved;
                if (next == from) return dpi;
                from = next;
            }
            return dpi;
        }

        /** What a density would be stored as, and so applied at. */
        int resolve(int dpi) {
            return dpiFor(base, factorOf(dpi, base));
        }

        boolean isPreset(int dpi) {
            for (int preset : presets) {
                if (preset == dpi) return true;
            }
            return false;
        }
    }

    // ── what it leaves an app ──

    /** dp to px at a density, rounded the way LauncherActivity.dp rounds. */
    static int px(float dp, int dpi) {
        return Math.round(dp * dpi / 160f);
    }

    /** px to dp at a density. */
    static int dp(int px, int dpi) {
        return Math.round(px * 160f / Math.max(1, dpi));
    }

    /**
     * Where a new window goes when the window memory has nothing for its app:
     * {x, y, width, height} in px, from the "App launch mode" and "Default
     * window size" settings. LauncherActivity.nextWindowBounds is this with its
     * own cascade counter as {@code slot}; the Display size card asks it at
     * the density it is showing, to draw the window an app would really get.
     *
     * The size is a share of the display in pixels, so it is the same window
     * at every density — what changes is how many dp it holds, which is what
     * decides an app's layout.
     *
     * Maximized deliberately stops 2dp short of the display's edges: the PC
     * reads a window that fills the display exactly as One UI's own maximize
     * and undoes it (scrcpy.rs, Enforcer::fills).
     *
     * @param reserve px at the bottom the shell's chrome holds (the taskbar)
     * @param top     px at the top the system holds (0 on a desktop display)
     */
    static int[] newWindow(int w, int h, int dpi, int reserve, int top,
                           String mode, String size, int slot) {
        float scale;
        if ("compact".equals(size)) {
            scale = 0.8f;
        } else if ("large".equals(size)) {
            scale = 1.28f;
        } else {
            scale = 1f;
        }
        int ww = Math.min(w - px(16, dpi), Math.round(w * 0.55f * scale));
        int wh = Math.min(h - reserve - px(16, dpi), Math.round(h * 0.72f * scale));
        int x;
        int y;
        if ("maximized".equals(mode)) {
            x = px(2, dpi);
            y = top + px(2, dpi);
            ww = w - px(4, dpi);
            wh = h - reserve - y - px(2, dpi);
        } else if ("center".equals(mode)) {
            x = (w - ww) / 2;
            y = Math.max(px(8, dpi), (h - reserve - wh) / 2);
        } else {
            int step = px(30, dpi);
            x = px(64, dpi) + (slot % 5) * step;
            y = px(36, dpi) + (slot % 5) * step;
        }
        return new int[]{x, y, ww, wh};
    }

    /** The room an app gets in a {@code w} x {@code h} px window, in dp: {width, height} below its title bar. */
    static int[] contentDp(int w, int h, int dpi) {
        return new int[]{dp(w, dpi), Math.max(0, dp(h, dpi) - CAPTION_DP)};
    }

    /**
     * Which layout apps choose for content this size. Width alone decides it
     * for apps built on window size classes; smallest width — the sw600dp
     * resources older apps ship — needs the height as well. Where the two
     * disagree is {@link #LAYOUT_MIXED}, and saying so is the honest answer.
     */
    static int layout(int widthDp, int heightDp) {
        if (widthDp < TABLET_DP) return LAYOUT_PHONE;
        return Math.min(widthDp, heightDp) >= TABLET_DP ? LAYOUT_TABLET : LAYOUT_MIXED;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
