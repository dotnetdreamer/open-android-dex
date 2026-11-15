package com.ccrstech.openandroiddex.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The launcher's ResizeGeometry, run on a computer's JVM: where a window's resize zone
 * is, what it grabs, what of it can be reached, and where a drag may take an edge.
 * Prints "ok …" / "FAIL …" per check and "done N" at the end, for
 * test/window-resize-test.mjs.
 */
public final class ResizeGeometryCheck {

    private static int checks;
    private static int failures;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "ok " : "FAIL ") + what);
    }

    private static final int[] W = {100, 100, 500, 400};

    private static int at(int x, int y) {
        return ResizeGeometry.edgesAt(W, ResizeGeometry.sizes(1f), x, y);
    }

    private static int shapeAt(int x, int y) {
        return ResizeGeometry.shapeFor(at(x, y));
    }

    public static void main(String[] args) {
        final int L = ResizeGeometry.LEFT;
        final int T = ResizeGeometry.TOP;
        final int R = ResizeGeometry.RIGHT;
        final int B = ResizeGeometry.BOTTOM;

        // ── sizes ──
        ResizeGeometry.Sizes one = ResizeGeometry.sizes(1f);
        check(one.outside == 8 && one.inside == 3 && one.corner == 22,
                "at 160 dpi the zone is 8 px outside, 3 inside, corners 22 along each side");
        ResizeGeometry.Sizes two = ResizeGeometry.sizes(2f);
        check(two.outside == 16 && two.inside == 6 && two.corner == 44,
                "on a desktop fitted to a Retina screen (2x) every size doubles");
        check(ResizeGeometry.sizes(0f).outside == 8, "no density read is taken as 1x, not as nothing");

        // ── what a point grabs ──
        check(at(95, 250) == L, "8 px outside the left edge, half way down: the left edge");
        check(at(92, 250) == L && at(91, 250) == 0, "the zone ends 8 px out, not 9");
        check(at(101, 250) == L, "the window's own first pixels are still the edge");
        check(at(103, 250) == 0, "past the 3 px sliver the window is the app's");
        check(at(507, 250) == R && at(508, 250) == 0, "right edge: 8 px out, exclusive");
        check(at(250, 95) == T && shapeAt(250, 95) == ResizeGeometry.SHAPE_NS,
                "above the middle of the top edge: up-down arrow");
        check(at(300, 405) == B && shapeAt(300, 405) == ResizeGeometry.SHAPE_NS,
                "below the middle of the bottom edge: up-down arrow");
        check(shapeAt(95, 250) == ResizeGeometry.SHAPE_EW, "left edge: left-right arrow");
        check(at(95, 95) == (L | T) && shapeAt(95, 95) == ResizeGeometry.SHAPE_NWSE,
                "outside the top-left corner: the corner, the \\ arrow");
        check(at(95, 110) == (L | T), "on the left edge 10 px below the corner: still the corner");
        check(at(95, 125) == L, "on the left edge 25 px below the corner: the edge (corners reach 22)");
        check(at(110, 95) == (L | T), "on the top edge 10 px right of the corner: the corner");
        check(at(505, 105) == (R | T) && shapeAt(505, 105) == ResizeGeometry.SHAPE_NESW,
                "top-right corner: the / arrow");
        check(at(95, 390) == (L | B) && shapeAt(95, 390) == ResizeGeometry.SHAPE_NESW,
                "bottom-left corner: the / arrow");
        check(at(505, 395) == (R | B) && shapeAt(505, 395) == ResizeGeometry.SHAPE_NWSE,
                "bottom-right corner: the \\ arrow");
        check(at(490, 405) == (R | B), "on the bottom edge near the right end: the corner");
        check(at(600, 600) == 0 && at(300, 250) == 0, "far away, or in the middle: nothing");

        int[] narrow = {100, 100, 130, 400};          // narrower than two corners
        ResizeGeometry.Sizes s1 = ResizeGeometry.sizes(1f);
        check(ResizeGeometry.edgesAt(narrow, s1, 110, 95) == (L | T)
                        && ResizeGeometry.edgesAt(narrow, s1, 125, 95) == (R | T),
                "a window narrower than two corners: the nearer corner wins");
        int[] tiny = {100, 100, 104, 104};
        check(ResizeGeometry.edgesAt(tiny, s1, 101, 102) == (L | B)
                        || ResizeGeometry.edgesAt(tiny, s1, 101, 102) == (L | T),
                "a window smaller than two slivers still has one edge per side, never both");

        // ── the bands tile the ring ──
        int[][] bands = ResizeGeometry.bands(W, s1);
        boolean tiled = true;
        for (int y = 80; y < 420 && tiled; y++) {
            for (int x = 80; x < 520 && tiled; x++) {
                int in = 0;
                for (int[] b : bands) {
                    if (x >= b[0] && x < b[2] && y >= b[1] && y < b[3]) in++;
                }
                boolean ring = at(x, y) != 0;
                if (ring ? in != 1 : in != 0) {
                    tiled = false;
                    System.out.println("  at " + x + "," + y + ": ring=" + ring + " bands=" + in);
                }
            }
        }
        check(tiled, "the four strips cover the zone exactly once and nothing else");

        // ── what can be reached ──
        int[] display = {0, 0, 1920, 1080};
        int[] taskbar = {0, 1028, 1920, 1080};
        List<int[]> covers = new ArrayList<>();
        covers.add(taskbar);
        int[] front = {100, 100, 500, 400};
        int[] back = {300, 200, 800, 600};
        List<List<List<int[]>>> reach = ResizeGeometry.reachable(
                Arrays.asList(front, back), new boolean[]{true, true}, display, covers, s1);
        check(reachable(reach, 0, 95, 250), "the front window's left edge can be reached");
        check(!reachable(reach, 1, 297, 300),
                "the back window's left edge, where the front window covers it: not reachable");
        check(reachable(reach, 1, 297, 500),
                "the same edge below the front window: reachable");
        check(!reachable(reach, 1, 297, 405),
                "where the front window's own zone lies over it: the front window wins");
        check(reachable(reach, 1, 805, 300), "the back window's right edge, in the open: reachable");

        int[] low = {100, 600, 500, 1030};
        reach = ResizeGeometry.reachable(Arrays.asList(new int[][]{low}),
                new boolean[]{true}, display, covers, s1);
        check(reachable(reach, 0, 300, 1027) && !reachable(reach, 0, 300, 1029),
                "no grab begins on the taskbar, even over a window's bottom edge");

        int[] corner = {0, 0, 400, 300};
        reach = ResizeGeometry.reachable(Arrays.asList(new int[][]{corner}),
                new boolean[]{true}, display, new ArrayList<>(), s1);
        check(!reachable(reach, 0, -3, 150) && reachable(reach, 0, 1, 150),
                "nothing past the display's edge; the window's own edge pixels still count");

        int[] max = {0, 0, 1916, 1028};
        reach = ResizeGeometry.reachable(Arrays.asList(max, back),
                new boolean[]{false, true}, display, covers, s1);
        check(reach.get(0).isEmpty(), "a maximised window has no zone of its own");
        check(!reachable(reach, 1, 805, 300),
                "…and it still covers the windows behind it");

        List<int[]> cut = ResizeGeometry.subtract(
                new ArrayList<>(Arrays.asList(new int[][]{{0, 0, 100, 100}})),
                new int[]{25, 25, 75, 75});
        int area = 0;
        for (int[] r : cut) area += (r[2] - r[0]) * (r[3] - r[1]);
        check(cut.size() == 4 && area == 100 * 100 - 50 * 50,
                "a hole in the middle of a strip leaves four pieces and the right area");

        // ── a drag ──
        int[] limits = {0, 0, 1920, 1028};
        check(Arrays.equals(ResizeGeometry.resized(W, R, 100, 0, 220, 220, limits),
                new int[]{100, 100, 600, 400}), "right edge dragged 100 px right: 100 px wider");
        check(Arrays.equals(ResizeGeometry.resized(W, L, 300, 0, 220, 220, limits),
                new int[]{280, 100, 500, 400}), "left edge dragged in: stops at the minimum width");
        check(Arrays.equals(ResizeGeometry.resized(W, L, -200, 0, 220, 220, limits),
                new int[]{0, 100, 500, 400}), "left edge dragged past the display: stops at it");
        check(Arrays.equals(ResizeGeometry.resized(W, T, 0, -200, 220, 220, limits),
                new int[]{100, 0, 500, 400}), "top edge dragged up: stops at the top of the display");
        check(Arrays.equals(ResizeGeometry.resized(W, B, 0, 1000, 220, 220, limits),
                new int[]{100, 100, 500, 1028}), "bottom edge: stops at the taskbar");
        check(Arrays.equals(ResizeGeometry.resized(W, L | T, -20, -30, 220, 220, limits),
                new int[]{80, 70, 500, 400}), "a corner moves both of its edges");
        check(Arrays.equals(ResizeGeometry.resized(W, R | B, 50, 60, 220, 220, limits),
                new int[]{100, 100, 550, 460}), "the opposite corner, the other two");
        check(Arrays.equals(ResizeGeometry.resized(W, B, 40, 0, 220, 220, limits), W),
                "a sideways drag on the bottom edge changes nothing");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{0, 0, 1800, 1028}, R, 200, 0,
                        220, 220, limits), new int[]{0, 0, 1918, 1028}),
                "never edge to edge from x 0: the computer would read it as a maximise");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{100, 0, 1920, 1028}, L, -200, 0,
                        220, 220, limits), new int[]{5, 0, 1920, 1028}),
                "…whichever edge was dragged there");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{100, 100, 250, 200}, L, 50, 0,
                        220, 220, limits), new int[]{100, 100, 250, 200}),
                "a window already under the minimum is never made smaller");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{-50, 100, 400, 300}, L, -10, 0,
                        220, 220, limits), new int[]{-50, 100, 400, 300}),
                "an edge already past the display is not pushed further out");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{-50, 100, 400, 300}, R, 10, 0,
                        220, 220, limits), new int[]{-50, 100, 410, 300}),
                "…but the window's other edges still move");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{0, 100, 1920, 600}, B, 0, 200,
                        220, 220, limits), new int[]{0, 100, 1920, 800}),
                "a window that spans the width already keeps it when only its bottom moves");

        // ── the minimum ──
        // An app asking for 900 px (300 dp parsed at the phone's density 3), on a desktop at
        // density 1 with a 220 dp default: the window manager holds it to 900, so the drag
        // stops at 900 — not 300, the size an outline once promised before it landed at 900.
        check(Arrays.equals(ResizeGeometry.minSize(900, 700, 220, 60), new int[]{900, 700}),
                "the task's own minimum is taken in pixels as the platform parsed them");
        check(Arrays.equals(ResizeGeometry.minSize(-1, -1, 220, 60), new int[]{220, 220}),
                "no minimum of its own: the display's default");
        check(Arrays.equals(ResizeGeometry.minSize(100, 100, 220, 60), new int[]{220, 220}),
                "one under the default: the default still holds");
        check(Arrays.equals(ResizeGeometry.minSize(-1, -1, 220, 260), new int[]{220, 260}),
                "never shorter than the title bar and a little of the window");
        check(Arrays.equals(ResizeGeometry.resized(new int[]{0, 0, 1200, 900}, R, -1000, 0,
                        ResizeGeometry.minSize(900, 700, 220, 60)[0], 700, limits),
                        new int[]{0, 0, 900, 900}),
                "a drag stops at that minimum");

        // ── names ──
        check("ew-resize".equals(ResizeGeometry.shapeName(ResizeGeometry.SHAPE_EW))
                        && "ns-resize".equals(ResizeGeometry.shapeName(ResizeGeometry.SHAPE_NS))
                        && "nwse-resize".equals(ResizeGeometry.shapeName(ResizeGeometry.SHAPE_NWSE))
                        && "nesw-resize".equals(ResizeGeometry.shapeName(ResizeGeometry.SHAPE_NESW))
                        && "default".equals(ResizeGeometry.shapeName(ResizeGeometry.SHAPE_NONE)),
                "shapes go out by their CSS names");

        System.out.println("done " + checks);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean reachable(List<List<List<int[]>>> reach, int window, int x, int y) {
        for (List<int[]> band : reach.get(window)) {
            if (ResizeGeometry.contains(band, x, y)) return true;
        }
        return false;
    }
}
