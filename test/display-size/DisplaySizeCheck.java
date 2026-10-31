package com.ccrstech.openandroiddex.launcher;

/**
 * The Display size arithmetic (DisplaySize.java), on a computer's JVM, for
 * test/display-size-test.mjs: one line per check, "ok <name>" or
 * "FAIL <name> — <detail>".
 */
public final class DisplaySizeCheck {

    private static int failed;

    private static void check(String name, boolean ok, Object detail) {
        if (ok) {
            System.out.println("ok " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " — " + detail);
        }
    }

    public static void main(String[] args) {
        kept();
        roundTrip();
        bounds();
        track();
        steps();
        windows();
        layouts();
        System.out.println("done " + (failed == 0 ? "ok" : failed + " failed"));
        System.exit(failed == 0 ? 0 : 1);
    }

    // ── what a choice is kept as ──

    private static void kept() {
        check("kept: a preset stays a preset", DisplaySize.normalize(1.25f) == 1.25f,
                DisplaySize.normalize(1.25f));
        check("kept: a rounded dpi lands on its preset (161/160 is Default)",
                DisplaySize.normalize(161 / 160f) == 1f, DisplaySize.normalize(161 / 160f));
        check("kept: 134 dpi on a Default of 107 is Large",
                DisplaySize.normalize(134 / 107f) == 1.25f, DisplaySize.normalize(134 / 107f));
        check("kept: the issue's 280 dpi on a 1x screen is 1.75x",
                DisplaySize.normalize(280 / 160f) == 1.75f, DisplaySize.normalize(280 / 160f));
        check("kept: 320 dpi on a 1x screen is 2x",
                DisplaySize.normalize(320 / 160f) == 2f, DisplaySize.normalize(320 / 160f));
        check("kept: 0.8 is not pulled onto Very small", DisplaySize.normalize(0.8f) == 0.8f,
                DisplaySize.normalize(0.8f));
        // the same edge display.rs tests in f64: exactly 0.01 away snaps on both sides
        check("kept: 0.885 snaps to 0.875, as on the PC", DisplaySize.normalize(0.885f) == 0.875f,
                DisplaySize.normalize(0.885f));
        check("kept: 0.886 does not", Math.abs(DisplaySize.normalize(0.886f) - 0.886f) < 1e-6,
                DisplaySize.normalize(0.886f));
        check("kept: four decimals (300 / 213 is 1.4085, as on the PC)",
                Math.abs(DisplaySize.normalize(300 / 213f) - 1.4085f) < 1e-6,
                DisplaySize.normalize(300 / 213f));
        check("kept: bounded above", DisplaySize.normalize(9f) == DisplaySize.MAX_FACTOR,
                DisplaySize.normalize(9f));
        check("kept: bounded below", DisplaySize.normalize(0.1f) == DisplaySize.MIN_FACTOR,
                DisplaySize.normalize(0.1f));
        check("kept: nonsense is the Default",
                DisplaySize.normalize(Float.NaN) == 1f && DisplaySize.normalize(0f) == 1f
                        && DisplaySize.normalize(-2f) == 1f
                        && DisplaySize.normalize(Float.POSITIVE_INFINITY) == 1f, "NaN/0/-2/inf");
        check("kept: preset index of a custom size is -1",
                DisplaySize.presetIndex(1.75f) == -1 && DisplaySize.presetIndex(1f) == 2,
                DisplaySize.presetIndex(1.75f) + "/" + DisplaySize.presetIndex(1f));
        check("kept: the nearest preset is what an old launcher reads",
                DisplaySize.nearestPreset(1.75f) == 4 && DisplaySize.nearestPreset(0.8f) == 0
                        && DisplaySize.nearestPreset(1.1f) == 2,
                DisplaySize.nearestPreset(1.75f) + "/" + DisplaySize.nearestPreset(0.8f));
    }

    /**
     * What is stored is dpi / Default and what is applied is Default x that:
     * any dpi the controls can show has to come back as itself, or the
     * launcher and the PC would correct each other on every start.
     */
    private static void roundTrip() {
        int[] bases = {107, 140, 160, 200, 213, 240, 280, 320, 360, 480};
        String broken = null;
        int tried = 0;
        for (int base : bases) {
            for (int dpi = DisplaySize.lowestDpi(base); dpi <= DisplaySize.DPI_MAX; dpi++) {
                if (dpi > base * DisplaySize.MAX_FACTOR) break;
                float f = DisplaySize.factorOf(dpi, base);
                int back = DisplaySize.dpiFor(base, f);
                boolean preset = DisplaySize.presetIndex(f) >= 0;
                tried++;
                if (back != dpi && !preset && broken == null) {
                    broken = "base " + base + ": " + dpi + " kept as " + f + " comes back as " + back;
                }
                // and a preset it snapped to is that preset's own dpi
                if (preset && back != DisplaySize.presetDpis(base)[DisplaySize.presetIndex(f)]
                        && broken == null) {
                    broken = "base " + base + ": " + dpi + " snapped to " + f + " but applies " + back;
                }
            }
        }
        check("round trip: every dpi from half to 4x of ten Defaults (" + tried + ")",
                broken == null, broken);
        check("round trip: the presets at 1x are 120 140 160 200 240",
                java.util.Arrays.equals(DisplaySize.presetDpis(160), new int[]{120, 140, 160, 200, 240}),
                java.util.Arrays.toString(DisplaySize.presetDpis(160)));
        check("round trip: the presets on a Retina screen are 240 280 320 400 480",
                java.util.Arrays.equals(DisplaySize.presetDpis(320), new int[]{240, 280, 320, 400, 480}),
                java.util.Arrays.toString(DisplaySize.presetDpis(320)));
        check("round trip: a dpi is never past the request pump's bounds",
                DisplaySize.dpiFor(960, 1.5f) == DisplaySize.DPI_MAX
                        && DisplaySize.dpiFor(107, 0.5f) == DisplaySize.DPI_MIN,
                DisplaySize.dpiFor(960, 1.5f) + "/" + DisplaySize.dpiFor(107, 0.5f));
    }

    // ── how far the control goes ──

    private static void bounds() {
        check("bounds: half the Default at the bottom (80 at 1x)", DisplaySize.lowestDpi(160) == 80,
                DisplaySize.lowestDpi(160));
        check("bounds: never under the pump's 70", DisplaySize.lowestDpi(107) == 70,
                DisplaySize.lowestDpi(107));
        check("bounds: 1080p tops out at 480 (640 x 360 dp)",
                DisplaySize.highestDpi(160, 1920, 1080) == 480, DisplaySize.highestDpi(160, 1920, 1080));
        check("bounds: a fitted 1x Windows desktop (1920 x 992) at 440",
                DisplaySize.highestDpi(160, 1920, 992) == 440, DisplaySize.highestDpi(160, 1920, 992));
        check("bounds: a portrait screen goes by its long side too (1080 x 1776: 444)",
                DisplaySize.highestDpi(160, 1080, 1776) == 444, DisplaySize.highestDpi(160, 1080, 1776));
        // its long side is the limit here: 3024 px at 756 dpi is 640 dp across
        check("bounds: a Retina laptop (3024 x 1824) at 756",
                DisplaySize.highestDpi(320, 3024, 1824) == 756, DisplaySize.highestDpi(320, 3024, 1824));
        check("bounds: a 4K screen at a Default of 320 stops at the pump's 960",
                DisplaySize.highestDpi(320, 3840, 2160) == 960, DisplaySize.highestDpi(320, 3840, 2160));
        check("bounds: a 4K screen at a Default of 160 stops at 4x, 640",
                DisplaySize.highestDpi(160, 3840, 2160) == 640, DisplaySize.highestDpi(160, 3840, 2160));
        check("bounds: 1080p at a Default of 80 stops at 4x, 320",
                DisplaySize.highestDpi(80, 1920, 1080) == 320, DisplaySize.highestDpi(80, 1920, 1080));
        int dense = DisplaySize.highestDpi(160, 1920, 1080);
        check("bounds: the densest leaves at least 640 x 360 dp",
                DisplaySize.dp(1920, dense) >= 640 && DisplaySize.dp(1080, dense) >= 360,
                DisplaySize.dp(1920, dense) + " x " + DisplaySize.dp(1080, dense));
    }

    private static void track() {
        DisplaySize.Track t = new DisplaySize.Track(160, 1920, 1080, 160);
        check("track: a 1x 1080p desktop runs 80 to 480", t.lo == 80 && t.hi == 480, t.lo + ".." + t.hi);
        check("track: its ends are its ends", t.position(80) == 0 && t.position(480) == 1000,
                t.position(80) + "/" + t.position(480));
        boolean onPresets = true;
        for (int preset : t.presets) {
            if (t.dpiAt(t.position(preset)) != preset) onPresets = false;
        }
        check("track: each preset's position gives the preset back", onPresets, "");
        int p200 = t.position(200);
        check("track: a thumb inside a preset's detent settles on it",
                t.dpiAt(p200 + DisplaySize.Track.DETENT) == 200
                        && t.dpiAt(p200 - DisplaySize.Track.DETENT) == 200,
                t.dpiAt(p200 + DisplaySize.Track.DETENT));
        check("track: just outside it, it does not",
                t.dpiAt(p200 + DisplaySize.Track.DETENT + 8) != 200,
                t.dpiAt(p200 + DisplaySize.Track.DETENT + 8));
        // logarithmic: 120 to 160 is the same distance as 240 to 320
        int a = t.position(160) - t.position(120);
        int b = t.position(320) - t.position(240);
        check("track: equal ratios are equal distances", Math.abs(a - b) <= 1, a + " vs " + b);
        check("track: Default sits left of the middle, not crammed at the edge",
                t.position(160) > 300 && t.position(160) < 500, t.position(160));
        int last = 0;
        boolean monotone = true;
        boolean clean = true;
        for (int p = 0; p <= DisplaySize.Track.STEPS; p++) {
            int dpi = t.dpiAt(p);
            if (dpi < last) monotone = false;
            if (dpi % 5 != 0 && !t.isPreset(dpi)) clean = false;
            if (t.resolve(dpi) != dpi) clean = false;
            last = dpi;
        }
        check("track: never goes backwards along the slider", monotone, "");
        check("track: shows only what it would apply, to 5 dpi or a preset", clean, "");
        check("track: the issue's 240, 280 and 320 are all on it",
                hits(t, 240) && hits(t, 280) && hits(t, 320), "");
        DisplaySize.Track shrunk = new DisplaySize.Track(160, 1000, 668, 480);
        check("track: a desktop made smaller than its size still shows that size",
                shrunk.hi >= 480 && shrunk.position(480) == 1000, shrunk.lo + ".." + shrunk.hi);
        DisplaySize.Track tiny = new DisplaySize.Track(107, 1280, 720, 107);
        check("track: every preset is reachable however small the display",
                tiny.lo <= tiny.presets[0] && tiny.hi >= tiny.presets[4],
                tiny.lo + ".." + tiny.hi + " vs " + java.util.Arrays.toString(tiny.presets));
        deadZones();
    }

    /**
     * No stretch of any track applies something other than what is under
     * the thumb. A choice is kept as a multiple of at most MAX_FACTOR, so a
     * track running past that multiple would show sizes it cannot keep: on a
     * 4K screen at a Default of 160 the thumb went on to 960 dpi while every
     * size past 640 was applied as 640, and jumped back on release.
     */
    private static void deadZones() {
        int[] bases = {80, 107, 120, 160, 200, 213, 240, 280, 320, 333, 400, 480, 640, 960};
        int[][] screens = {{1280, 720}, {1920, 1080}, {1920, 992}, {2560, 1440}, {2880, 1620},
                {3024, 1824}, {3840, 2160}, {5120, 2880}, {7680, 4320}, {1080, 1776}, {800, 600}};
        String gap = null;
        String top = null;
        int tracks = 0;
        for (int base : bases) {
            for (int[] s : screens) {
                int[] currents = {DisplaySize.lowestDpi(base), base,
                        DisplaySize.dpiFor(base, 1.5f), DisplaySize.dpiFor(base, 2.75f),
                        DisplaySize.dpiFor(base, DisplaySize.MAX_FACTOR)};
                for (int current : currents) {
                    DisplaySize.Track t = new DisplaySize.Track(base, s[0], s[1], current);
                    tracks++;
                    // a dpi near a preset snaps onto it, by up to SNAP of the Default
                    int allowance = (int) Math.ceil(DisplaySize.SNAP * base + 1e-6) + 1;
                    for (int dpi = t.lo; dpi <= t.hi && gap == null; dpi++) {
                        int applied = t.resolve(dpi);
                        if (Math.abs(applied - dpi) > allowance) {
                            gap = s[0] + "x" + s[1] + " at a Default of " + base + ", track "
                                    + t.lo + ".." + t.hi + ": " + dpi + " is applied as " + applied;
                        }
                    }
                    if (top == null && (t.resolve(t.hi) != t.hi
                            || t.dpiAt(DisplaySize.Track.STEPS) != t.hi
                            || t.resolve(t.lo) != t.lo || t.dpiAt(0) != t.lo)) {
                        top = s[0] + "x" + s[1] + " at " + base + ": track " + t.lo + ".." + t.hi
                                + " applies " + t.resolve(t.lo) + ".." + t.resolve(t.hi)
                                + ", the thumb at its ends shows " + t.dpiAt(0) + ".."
                                + t.dpiAt(DisplaySize.Track.STEPS);
                    }
                }
            }
        }
        check("track: no stretch applies another size, on " + tracks + " tracks", gap == null, gap);
        check("track: both ends of every track are under the thumb and applied as themselves",
                top == null, top);
    }

    private static boolean hits(DisplaySize.Track t, int dpi) {
        for (int p = 0; p <= DisplaySize.Track.STEPS; p++) {
            if (t.dpiAt(p) == dpi) return true;
        }
        return false;
    }

    private static void steps() {
        DisplaySize.Track t = new DisplaySize.Track(160, 1920, 1080, 160);
        check("steps: + from Default is 170", t.step(160, 1) == 170, t.step(160, 1));
        check("steps: − from Default is 150", t.step(160, -1) == 150, t.step(160, -1));
        check("steps: a preset on the way is landed on, not stepped over (135 + is 140)",
                t.step(135, 1) == 140, t.step(135, 1));
        check("steps: from a custom 175, + is 180", t.step(175, 1) == 180, t.step(175, 1));
        check("steps: stops at the top", t.step(480, 1) == 480, t.step(480, 1));
        check("steps: stops at the bottom", t.step(80, -1) == 80, t.step(80, -1));
        // walk the whole track both ways: every press moves, every preset is visited
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int dpi = t.lo;
        int presses = 0;
        boolean moved = true;
        while (dpi < t.hi && presses < 200) {
            int next = t.step(dpi, 1);
            if (next <= dpi) moved = false;
            seen.add(next);
            dpi = next;
            presses++;
        }
        boolean allPresets = true;
        for (int preset : t.presets) allPresets &= seen.contains(preset) || preset == t.lo;
        check("steps: + all the way up moves every press and visits every preset",
                moved && allPresets && dpi == t.hi, presses + " presses, at " + dpi);
        // a Default whose presets are not multiples of ten, and a 1.5x that rounds up
        DisplaySize.Track odd = new DisplaySize.Track(107, 1280, 720, 161);
        check("steps: − from a preset that 160 would snap back onto still moves",
                odd.step(161, -1) == 150, odd.step(161, -1));
        check("steps: Default 213: − from 213 is 210, + is 220",
                new DisplaySize.Track(213, 2560, 1440, 213).step(213, -1) == 210
                        && new DisplaySize.Track(213, 2560, 1440, 213).step(213, 1) == 220, "");
    }

    // ── what it leaves an app ──

    /**
     * LauncherActivity.nextWindowBounds as it was before its arithmetic moved
     * into DisplaySize.newWindow, transcribed with dp() at the given density.
     */
    private static int[] oldBounds(int sx, int sy, int dpi, int reserve, int topInset,
                                   String mode, String sizeKey, int cascade) {
        float scale;
        switch (sizeKey) {
            case "compact":
                scale = 0.8f;
                break;
            case "large":
                scale = 1.28f;
                break;
            default:
                scale = 1f;
        }
        int w = Math.min(sx - dp(16, dpi), Math.round(sx * 0.55f * scale));
        int h = Math.min(sy - reserve - dp(16, dpi), Math.round(sy * 0.72f * scale));
        int x;
        int y;
        if ("maximized".equals(mode)) {
            x = dp(2, dpi);
            y = topInset + dp(2, dpi);
            w = sx - dp(4, dpi);
            h = sy - reserve - y - dp(2, dpi);
        } else if ("center".equals(mode)) {
            x = (sx - w) / 2;
            y = Math.max(dp(8, dpi), (sy - reserve - h) / 2);
        } else {
            int step = dp(30, dpi);
            x = dp(64, dpi) + (cascade % 5) * step;
            y = dp(36, dpi) + (cascade % 5) * step;
        }
        return new int[]{x, y, w, h};
    }

    /** LauncherActivity.dp. */
    private static int dp(float v, int uiDensity) {
        return Math.round(v * uiDensity / 160f);
    }

    private static void windows() {
        int[] standard = DisplaySize.newWindow(1920, 1080, 160, 52, 0, "cascade", "standard", 0);
        check("windows: a standard window on 1080p is 1056 x 778 at (64, 36)",
                java.util.Arrays.equals(standard, new int[]{64, 36, 1056, 778}),
                java.util.Arrays.toString(standard));
        int[] max = DisplaySize.newWindow(1920, 1080, 160, 52, 0, "maximized", "standard", 0);
        check("windows: maximized stops 2 dp short of every edge",
                java.util.Arrays.equals(max, new int[]{2, 2, 1916, 1024}), java.util.Arrays.toString(max));
        int[] dense = DisplaySize.newWindow(1920, 1080, 320, 104, 0, "cascade", "standard", 0);
        check("windows: the same window in px at twice the density",
                dense[2] == 1056 && dense[3] == 778, java.util.Arrays.toString(dense));

        String[] modes = {"cascade", "center", "maximized", "bogus"};
        String[] sizes = {"compact", "standard", "large", "bogus"};
        int[][] screens = {{1920, 1080}, {1920, 992}, {1080, 1776}, {3024, 1824}, {1280, 720},
                {984, 1776}, {1220, 2712}, {2560, 1376}};
        int[] dpis = {80, 120, 140, 160, 200, 240, 280, 320, 440, 480};
        int compared = 0;
        String differs = null;
        for (int[] s : screens) {
            for (int dpi : dpis) {
                for (int reserveDp : new int[]{52, 56}) {
                    int reserve = dp(reserveDp, dpi);
                    for (int top : new int[]{0, 63}) {
                        for (String mode : modes) {
                            for (String size : sizes) {
                                for (int slot = 0; slot < 7; slot++) {
                                    int[] a = oldBounds(s[0], s[1], dpi, reserve, top, mode, size, slot);
                                    int[] b = DisplaySize.newWindow(s[0], s[1], dpi, reserve, top,
                                            mode, size, slot);
                                    compared++;
                                    if (!java.util.Arrays.equals(a, b) && differs == null) {
                                        differs = s[0] + "x" + s[1] + "@" + dpi + " " + mode + "/" + size
                                                + " slot " + slot + ": " + java.util.Arrays.toString(a)
                                                + " vs " + java.util.Arrays.toString(b);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        check("windows: identical to the old nextWindowBounds in " + compared + " cases",
                differs == null, differs);
    }

    private static void layouts() {
        int[] room = DisplaySize.contentDp(1056, 778, 160);
        check("layouts: a standard window at 1x gives 1056 x 738 below its title bar",
                room[0] == 1056 && room[1] == 738, room[0] + " x " + room[1]);
        check("layouts: ... which is a tablet's room",
                DisplaySize.layout(room[0], room[1]) == DisplaySize.LAYOUT_TABLET, "");
        int[] at240 = DisplaySize.contentDp(1056, 778, 240);
        check("layouts: at 240 dpi the same window is 704 x 479: wide but short",
                at240[0] == 704 && at240[1] == 479
                        && DisplaySize.layout(at240[0], at240[1]) == DisplaySize.LAYOUT_MIXED,
                at240[0] + " x " + at240[1]);
        int[] at320 = DisplaySize.contentDp(1056, 778, 320);
        check("layouts: at 320 dpi it is a phone's width",
                DisplaySize.layout(at320[0], at320[1]) == DisplaySize.LAYOUT_PHONE,
                at320[0] + " x " + at320[1]);
        check("layouts: 600 both ways is the first tablet",
                DisplaySize.layout(600, 600) == DisplaySize.LAYOUT_TABLET
                        && DisplaySize.layout(599, 900) == DisplaySize.LAYOUT_PHONE
                        && DisplaySize.layout(900, 599) == DisplaySize.LAYOUT_MIXED, "");
        check("layouts: a window shorter than its title bar has no room, not less than none",
                DisplaySize.contentDp(400, 30, 160)[1] == 0, DisplaySize.contentDp(400, 30, 160)[1]);
    }
}
