package com.ccrstech.openandroiddex.launcher;

import java.util.Arrays;

/**
 * The launcher's CaptionMath, run on a computer's JVM: where a desktop window's More menu
 * puts it for Phone size and Tablet size, which of the two it is now, where a title bar's
 * menu opens, and when a press on a menu's button is the one that just closed it. Prints
 * "ok …" / "FAIL …" per check and "done N" at the end, for test/caption-bar-test.mjs.
 */
public final class CaptionMathCheck {

    private static int checks;
    private static int failures;

    /** CaptionMenu's MORE and SOUND, the same numbers: CaptionMenu is Android's and cannot be compiled here. */
    private static final int MORE = 1;
    private static final int SOUND = 2;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "ok " : "FAIL ") + what);
    }

    private static void same(int[] got, int[] want, String what) {
        boolean ok = got != null && got != CaptionMath.MAXIMISE && Arrays.equals(got, want);
        check(ok, what + (ok ? "" : " (got " + describe(got) + ", want " + Arrays.toString(want) + ")"));
    }

    private static boolean allShown(boolean[] shown) {
        return countShown(shown) == shown.length;
    }

    private static int countShown(boolean[] shown) {
        int n = 0;
        for (boolean s : shown) if (s) n++;
        return n;
    }

    /** Whether the controls left shown, and {@code room}, fit in {@code width}. */
    private static boolean fits(int fixed, int[] widths, boolean[] shown, int room, int width) {
        int used = fixed;
        for (int i = 0; i < widths.length; i++) if (!shown[i]) used -= widths[i];
        return used + room <= width;
    }

    private static String describe(int[] r) {
        if (r == null) return "nothing";
        if (r == CaptionMath.MAXIMISE) return "maximise";
        return Arrays.toString(r);
    }

    public static void main(String[] args) {
        // A 1920x1080 desktop at 160 dpi: the taskbar is 52 px, and the maximised rect keeps
        // 4 px of width back (CaptionService#maxRect). The bar is One UI's 40 px.
        final int[] max = {0, 0, 1916, 1028};
        final int bar = 40;
        final int[] window = {300, 100, 900, 700};

        // ── Phone size ──
        int[] phone = CaptionMath.sizeFor(false, window, max, bar, 1f, false);
        same(phone, new int[]{300, 100, 699, 1025},
                "Phone size on 1920x1080: a phone's shape, from where the window's top-left is");
        check(phone != null && phone.length == 4 && phone[3] - phone[1] <= 1028 * 0.9f + 0.5f,
                "…its bar included, no taller than 90 % of the room above the taskbar");
        int[] lower = CaptionMath.sizeFor(false, new int[]{300, 500, 900, 900}, max, bar, 1f, false);
        same(lower, new int[]{300, 103, 699, 1028},
                "a window too low for a phone's height is moved up only as far as it fits");
        int[] right = CaptionMath.sizeFor(false, new int[]{1700, 100, 1900, 500}, max, bar, 1f, false);
        same(right, new int[]{1517, 100, 1916, 1025},
                "one too far right is moved left only as far as it fits");
        int[] fromMax = CaptionMath.sizeFor(false, max, max, bar, 1f, true);
        same(fromMax, new int[]{758, 51, 1157, 976}, "a maximised window comes down in the middle");
        int[] big = CaptionMath.sizeFor(false, window, new int[]{0, 0, 3836, 2108}, bar, 1f, false);
        same(big, new int[]{300, 100, 712, 1055},
                "on a big desktop: exactly a phone's 412 x 915 dp, never larger than asked");
        int[] retina = CaptionMath.sizeFor(false, new int[]{600, 200, 1800, 1400},
                new int[]{0, 0, 3836, 2056}, 80, 2f, false);
        // Within a pixel: the two densities round their halves on their own.
        check(retina != null && retina.length == 4
                        && Math.abs((retina[2] - retina[0]) - 2 * (phone[2] - phone[0])) <= 1
                        && Math.abs((retina[3] - retina[1]) - 2 * (phone[3] - phone[1])) <= 1,
                "a desktop fitted to a Retina screen (2x) gets the same size in dp: "
                        + describe(retina));

        // ── Tablet size ──
        int[] tablet = CaptionMath.sizeFor(true, window, max, bar, 1f, false);
        same(tablet, new int[]{300, 100, 1580, 940},
                "Tablet size on 1920x1080: a tablet's 1280 x 800 dp on its side");
        check(CaptionMath.tabletSized(tablet, bar, 1f), "…and that is a tablet's room to its app");
        check(!CaptionMath.tabletSized(phone, bar, 1f), "a phone's size is not");
        check(CaptionMath.tabletSized(new int[]{0, 0, 600, 640}, bar, 1f)
                        && !CaptionMath.tabletSized(new int[]{0, 0, 600, 639}, bar, 1f),
                "600 dp on the shorter side, the bar not counted, is where a tablet starts");
        int[] small = CaptionMath.sizeFor(true, window, new int[]{0, 0, 1276, 668}, bar, 1f, false);
        check(small == CaptionMath.MAXIMISE,
                "on 1280x720 a fitted tablet's size is under 600 dp but the whole room is a tablet's: maximise ("
                        + describe(small) + ")");
        int[] tiny = CaptionMath.sizeFor(true, window, new int[]{0, 0, 1020, 548}, bar, 1f, false);
        check(tiny != null && tiny != CaptionMath.MAXIMISE && tiny[2] - tiny[0] == 725
                        && tiny[3] - tiny[1] == 453 + bar,
                "on 1024x600 nothing is a tablet's: the most a tablet's shape gets ("
                        + describe(tiny) + ")");
        check(CaptionMath.sizeFor(false, window, new int[]{0, 0, 200, 150}, bar, 1f, false) == null,
                "no room for anything worth giving: nothing is done");

        // ── where a menu opens ──
        final int[] display = {1920, 1080};
        final int taskbar = 52;
        final int margin = 10;
        int[] at = CaptionMath.menuAt(new int[]{300, 100, 334, 140}, 220, 200, margin, display, taskbar);
        check(at[0] + margin == 300 && at[1] + margin == 140,
                "under its button: the card's top-left at the button's bottom-left " + Arrays.toString(at));
        int[] edge = CaptionMath.menuAt(new int[]{1880, 100, 1914, 140}, 220, 200, margin, display, taskbar);
        check(edge[0] + 220 - margin == 1920,
                "a button at the display's right edge: the card ends at the edge " + Arrays.toString(edge));
        int[] low = CaptionMath.menuAt(new int[]{300, 950, 334, 990}, 220, 200, margin, display, taskbar);
        check(low[1] + 200 - margin == 950,
                "no room above the taskbar: the card opens upwards, its bottom at the button's top "
                        + Arrays.toString(low));
        int[] tall = CaptionMath.menuAt(new int[]{300, 100, 334, 140}, 220, 1100, margin, display, taskbar);
        check(tall[1] == -margin, "a card taller than the display starts at its top " + Arrays.toString(tall));
        int[] left = CaptionMath.menuAt(new int[]{-30, 100, 4, 140}, 220, 200, margin, display, taskbar);
        check(left[0] == -margin, "a button half off the left edge: the card starts at the edge "
                + Arrays.toString(left));

        // ── a press on the menu's own button ──
        check(CaptionMath.samePress(5, MORE, 1000, 5, MORE, 1300, 400),
                "the press that closed a menu, heard by its button 300 ms later, does not reopen it");
        check(!CaptionMath.samePress(5, MORE, 1000, 5, SOUND, 1300, 400),
                "the same bar's other button opens its own menu");
        check(!CaptionMath.samePress(5, MORE, 1000, 6, MORE, 1300, 400),
                "another window's More opens");
        check(!CaptionMath.samePress(5, MORE, 1000, 5, MORE, 1400, 400),
                "a press 400 ms after is a press of its own");
        check(!CaptionMath.samePress(5, MORE, 0, 5, MORE, 100, 400),
                "a menu never closed by a press counts for nothing");

        // ── a narrow window's bar ──
        // At 160 dpi under the DeX shell: Back, More and Sound 34 px, the window's own five
        // 38, the icon 16 with 4 + 8 around it. Yielding, in CaptionService#build's order:
        // icon, ◨, ◧, sound, —, More, ▢. Back and ✕ never yield.
        final int[] widths = {28, 38, 38, 34, 38, 34, 38};
        final int fixed = 34 + 34 + 28 + 34 + 5 * 38;     // 320, every control shown
        final int room = 48;
        check(allShown(CaptionMath.shown(fixed, widths, room, 1000)), "a wide window shows them all");
        check(allShown(CaptionMath.shown(fixed, widths, room, 368)),
                "…down to exactly their width and the 48 px to drag by (368)");
        boolean[] one = CaptionMath.shown(fixed, widths, room, 367);
        check(!one[0] && countShown(one) == 6, "a pixel less and the icon goes first, alone");
        boolean[] three = CaptionMath.shown(fixed, widths, room, 300);
        check(!three[0] && !three[1] && !three[2] && three[3] && three[4] && three[5] && three[6],
                "at 300 px the icon and both snaps go; sound, minimise, More, maximise stay");
        check(fits(fixed, widths, CaptionMath.shown(fixed, widths, room, 300), room, 300),
                "…and what stays fits with room to drag by");
        boolean[] none = CaptionMath.shown(fixed, widths, room, 120);
        check(countShown(none) == 0 && fits(fixed, widths, none, room, 120),
                "at 120 px only Back and ✕ are left, still with their 48 px to drag by");
        for (int w = 72; w <= 1000; w++) {
            boolean[] s = CaptionMath.shown(fixed, widths, 0, w);
            if (!fits(fixed, widths, s, 0, w)) {
                check(false, "✕ is never pushed off the bar's end: it is at " + w + " px");
                break;
            }
            if (w == 1000) check(true, "✕ is never pushed off the bar's end, at any width from 72 to 1000 px");
        }
        boolean[] withIcon = CaptionMath.shown(fixed, widths, room, 340);
        boolean[] noIcon = CaptionMath.shown(fixed - 28, new int[]{0, 38, 38, 34, 38, 34, 38}, room, 340);
        check(!withIcon[0] && countShown(withIcon) == 6 && noIcon[1] && noIcon[2] && countShown(noIcon) == 7,
                "at 340 px a bar with an icon drops only the icon, and one with no icon keeps everything");

        System.out.println("done " + checks);
        System.exit(failures == 0 ? 0 : 1);
    }
}
