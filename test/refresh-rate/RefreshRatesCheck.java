package com.ccrstech.openandroiddex.wmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The window daemon's HZ rule (RefreshRates), on a computer's JVM, for
 * test/refresh-rate-test.mjs: one line per check, "ok <name>" or "FAIL <name> - <detail>".
 *
 * The modes are the ones a Galaxy S25 Ultra (Android 17) listed on 2026-11-23: seven
 * rates at each of three sizes, the phone set to the smallest (its default mode, 14).
 */
public final class RefreshRatesCheck {

    private static int failed;

    private static void check(String name, Object got, Object want) {
        if (String.valueOf(got).equals(String.valueOf(want))) {
            System.out.println("ok " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " - got " + got + ", want " + want);
        }
    }

    private static final float[] RATES = {120.00001f, 80f, 60.000004f, 48.000004f, 30.000002f, 24.000002f, 10f};
    private static final int[][] SIZES = {{1440, 3120}, {1080, 2340}, {720, 1560}};

    private static List<RefreshRates.Mode> s25() {
        List<RefreshRates.Mode> modes = new ArrayList<>();
        int id = 0;
        for (int[] size : SIZES) {
            for (float rate : RATES) modes.add(new RefreshRates.Mode(id++, size[0], size[1], rate));
        }
        return modes;
    }

    private static Object pick(int sizeOf, float wanted, float ceiling) {
        RefreshRates.Mode m = RefreshRates.pick(s25(), sizeOf, wanted, ceiling);
        return m == null ? "none" : m.id + "@" + Math.round(m.rate);
    }

    public static void main(String[] args) {
        check("120 at the phone's own size", pick(14, 120, 120f), "14@120");
        check("90, which the panel lacks, is the 80 below it", pick(14, 90, 120f), "15@80");
        check("above the panel's top is its top", pick(14, 240, 120f), "14@120");
        check("never above the phone's ceiling (battery saver: 60)", pick(14, 120, 60f), "16@60");
        check("an endless ceiling is no ceiling", pick(14, 120, Float.POSITIVE_INFINITY), "14@120");
        check("no ceiling read (0) is no ceiling", pick(14, 80, 0f), "15@80");
        check("slower than every mode is the slowest", pick(14, 5, 120f), "20@10");
        check("a ceiling below every mode holds nothing (the phone's policy stands)",
                pick(14, 120, 5f), "none");
        List<RefreshRates.Mode> fixed = Arrays.asList(
                new RefreshRates.Mode(0, 1080, 2340, 120f), new RefreshRates.Mode(1, 1080, 2340, 60f));
        RefreshRates.Mode m = RefreshRates.pick(fixed, 0, 120, 30f);
        check("60/120 Hz panel, ceiling 30: nothing held, not 60", m == null ? "none" : m.id + "@" + Math.round(m.rate), "none");
        m = RefreshRates.pick(fixed, 0, 30, 120f);
        check("60/120 Hz panel, 30 asked for under a 120 ceiling: 60", m == null ? "none" : m.id + "@" + Math.round(m.rate), "1@60");
        check("the size of the phone's default, not another's: 1440x3120", pick(0, 120, 120f), "0@120");
        check("the size of the phone's default, not another's: 1080x2340", pick(9, 60, 120f), "9@60");
        check("a default not listed holds nothing", pick(99, 120, 120f), "none");
        check("the rates offered at that size, fastest first",
                RefreshRates.offered(s25(), 14), Arrays.asList(120, 80, 60, 48, 30, 24, 10));
        check("no rates for a size not listed", RefreshRates.offered(s25(), 99), Arrays.asList());
        List<RefreshRates.Mode> twice = s25();
        twice.add(new RefreshRates.Mode(21, 720, 1560, 120f));   // a second 120 at the same size
        check("each rate offered once", RefreshRates.offered(twice, 14), Arrays.asList(120, 80, 60, 48, 30, 24, 10));
        System.out.println("done " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
