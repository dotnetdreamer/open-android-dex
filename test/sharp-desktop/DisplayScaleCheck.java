package com.genymobile.scrcpy.video;

/**
 * The scale the patched scrcpy server multiplies a flex display's sizes by
 * (openandroiddex-scrcpy/display-scale.patch), on a computer's JVM: one line
 * per check, "ok <name>" or "FAIL <name> — <detail>".
 */
public final class DisplayScaleCheck {

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
        check("scale: what the computer writes for a Retina screen", DisplayScale.parse("2.0\n") == 2f,
                DisplayScale.parse("2.0\n"));
        check("scale: …and for 1x, written as the app prints it", DisplayScale.parse("1") == 1f, DisplayScale.parse("1"));
        check("scale: a fractional one", DisplayScale.parse("1.5") == 1.5f, DisplayScale.parse("1.5"));
        check("scale: no file, an empty one, or junk is stock behaviour",
                DisplayScale.parse(null) == 1f && DisplayScale.parse("") == 1f && DisplayScale.parse("two") == 1f, "-");
        check("scale: nothing absurd gets through",
                DisplayScale.parse("0") == 1f && DisplayScale.parse("-2") == 1f && DisplayScale.parse("100") == 1f
                        && DisplayScale.parse("NaN") == 1f, "-");
        check("scale: a 1416x853-point window is 2832x1706 pixels at 2x",
                DisplayScale.toPixels(1416, 2f) == 2832 && DisplayScale.toPixels(853, 2f) == 1706, "-");
        check("scale: a 1000-point window at 1.5x", DisplayScale.toPixels(1000, 1.5f) == 1500,
                DisplayScale.toPixels(1000, 1.5f));
        check("scale: back to window coordinates, as the display's first size is",
                DisplayScale.toWindow(2832, 2f) == 1416 && DisplayScale.toWindow(1706, 2f) == 853, "-");
        check("scale: never a zero side", DisplayScale.toPixels(0, 2f) == 1 && DisplayScale.toWindow(1, 4f) == 1, "-");
        check("scale: the file is where the computer writes it",
                "/data/local/tmp/oadx-display-scale".equals(DisplayScale.FILE), DisplayScale.FILE);
        System.out.println("done " + failed);
    }
}
