package com.ccrstech.openandroiddex.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a desktop window can be grabbed to resize it, and what a drag there does: the
 * arithmetic of {@link ResizeEdges}, with no Android in it, so it is checked on a
 * computer's JVM (test/window-resize-test.mjs).
 *
 * <p>Every rect is {@code {left, top, right, bottom}} in the desktop display's pixels,
 * right and bottom exclusive, as android.graphics.Rect has them.
 *
 * <p><b>The grab zone straddles the edge.</b> Most of it lies OUTSIDE the window, the way
 * Windows' and GNOME's invisible borders do, so that grabbing an edge costs the app none of
 * its own pixels; a sliver lies inside, so that a pointer put exactly on the visible edge
 * — the window's own last pixel — still finds it. Corners reach {@link #CORNER_DP} along
 * both sides, so a diagonal resize does not need the corner itself.
 */
final class ResizeGeometry {

    private ResizeGeometry() {
    }

    /** Outside the window, in dp. One UI's own reaches about 10 px at 160 dpi. */
    static final float OUTSIDE_DP = 8f;
    /** Inside the window, in dp: the visible edge, and a little of the window over it. */
    static final float INSIDE_DP = 3f;
    /** How far along each side, from the corner, a grab is a corner grab. */
    static final float CORNER_DP = 22f;
    /**
     * The smallest a window is made by a drag when the platform says nothing smaller:
     * AOSP's default_minimal_size_resizable_task, which the window manager would enforce
     * anyway — by moving the edge that was NOT dragged, so a window shrunk from the left
     * would start walking right.
     */
    static final float MIN_SIZE_DP = 220f;

    // ── edges ──

    static final int LEFT = 1;
    static final int TOP = 2;
    static final int RIGHT = 4;
    static final int BOTTOM = 8;

    // ── what the pointer should say ──

    static final int SHAPE_NONE = 0;
    static final int SHAPE_EW = 1;
    static final int SHAPE_NS = 2;
    static final int SHAPE_NWSE = 3;
    static final int SHAPE_NESW = 4;

    /** The same shapes by the names the window daemon and the computer's cursor use. */
    static String shapeName(int shape) {
        switch (shape) {
            case SHAPE_EW: return "ew-resize";
            case SHAPE_NS: return "ns-resize";
            case SHAPE_NWSE: return "nwse-resize";
            case SHAPE_NESW: return "nesw-resize";
            default: return "default";
        }
    }

    /** Grab sizes at one density, in px. */
    static final class Sizes {
        final int outside;
        final int inside;
        final int corner;

        Sizes(int outside, int inside, int corner) {
            this.outside = outside;
            this.inside = inside;
            this.corner = corner;
        }
    }

    /** {@code density} is the desktop display's, as a multiple of 160 dpi. */
    static Sizes sizes(float density) {
        float d = density > 0f ? density : 1f;
        return new Sizes(Math.max(2, Math.round(OUTSIDE_DP * d)),
                Math.max(1, Math.round(INSIDE_DP * d)),
                Math.max(4, Math.round(CORNER_DP * d)));
    }

    // ── one window ──

    /** Which edges a point grabs on window {@code w}; 0 when it is not on one. */
    static int edgesAt(int[] w, Sizes s, int x, int y) {
        if (x < w[0] - s.outside || x >= w[2] + s.outside
                || y < w[1] - s.outside || y >= w[3] + s.outside) {
            return 0;
        }
        if (x >= w[0] + s.inside && x < w[2] - s.inside
                && y >= w[1] + s.inside && y < w[3] - s.inside) {
            return 0;                                  // the window itself
        }
        boolean left = x < w[0] + s.inside;
        boolean right = x >= w[2] - s.inside;
        boolean top = y < w[1] + s.inside;
        boolean bottom = y >= w[3] - s.inside;
        // A window narrower or shorter than two slivers: the nearer side wins.
        if (left && right) {
            if (x - w[0] < w[2] - x) right = false; else left = false;
        }
        if (top && bottom) {
            if (y - w[1] < w[3] - y) bottom = false; else top = false;
        }
        // Along a side, near either end of it: the corner.
        if ((left || right) && !top && !bottom) {
            top = y < w[1] + s.corner;
            bottom = y >= w[3] - s.corner;
            if (top && bottom) {
                if (y - w[1] < w[3] - y) bottom = false; else top = false;
            }
        } else if ((top || bottom) && !left && !right) {
            left = x < w[0] + s.corner;
            right = x >= w[2] - s.corner;
            if (left && right) {
                if (x - w[0] < w[2] - x) right = false; else left = false;
            }
        }
        return (left ? LEFT : 0) | (top ? TOP : 0) | (right ? RIGHT : 0) | (bottom ? BOTTOM : 0);
    }

    /** The pointer a grab of these edges shows. */
    static int shapeFor(int edges) {
        boolean h = (edges & (LEFT | RIGHT)) != 0;
        boolean v = (edges & (TOP | BOTTOM)) != 0;
        if (h && v) {
            return ((edges & LEFT) != 0) == ((edges & TOP) != 0) ? SHAPE_NWSE : SHAPE_NESW;
        }
        if (h) return SHAPE_EW;
        if (v) return SHAPE_NS;
        return SHAPE_NONE;
    }

    static final int BAND_TOP = 0;
    static final int BAND_BOTTOM = 1;
    static final int BAND_LEFT = 2;
    static final int BAND_RIGHT = 3;

    /**
     * The four strips the grab zone is made of, by BAND_*: top and bottom run the full
     * width, corners included; left and right run between them. They do not overlap, so
     * each point of the ring belongs to exactly one — one small window each, see
     * {@link ResizeEdges}. A side too short to have a middle comes back empty.
     */
    static int[][] bands(int[] w, Sizes s) {
        int o = s.outside;
        int i = s.inside;
        int midTop = w[1] + i;
        int midBottom = w[3] - i;
        if (midBottom < midTop) midBottom = midTop;
        return new int[][]{
                {w[0] - o, w[1] - o, w[2] + o, midTop},
                {w[0] - o, midBottom, w[2] + o, w[3] + o},
                {w[0] - o, midTop, w[0] + i, midBottom},
                {w[2] - i, midTop, w[2] + o, midBottom},
        };
    }

    // ── what can be reached ──

    /**
     * The part of each window's grab zone the pointer can reach, by window, by band.
     *
     * @param windows   topmost first: {@code {left, top, right, bottom}}
     * @param resizable per window, whether it has a grab zone of its own; one that does not
     *                  (a maximised window) still covers the windows behind it
     * @param display   the display's own rect: nothing beyond it can be pointed at
     * @param covers    what is above every window — the taskbar, its menus, a keyboard —
     *                  where no grab may begin
     * @return per window, per band, the reachable rects; empty for a window without one
     */
    static List<List<List<int[]>>> reachable(List<int[]> windows, boolean[] resizable,
                                             int[] display, List<int[]> covers, Sizes s) {
        List<List<List<int[]>>> out = new ArrayList<>();
        List<int[]> above = new ArrayList<>(covers);
        for (int n = 0; n < windows.size(); n++) {
            int[] w = windows.get(n);
            List<List<int[]>> perBand = new ArrayList<>();
            if (resizable[n]) {
                for (int[] band : bands(w, s)) {
                    List<int[]> parts = new ArrayList<>();
                    int[] clipped = intersect(band, display);
                    if (clipped != null) parts.add(clipped);
                    for (int[] cover : above) parts = subtract(parts, cover);
                    perBand.add(parts);
                }
            }
            out.add(perBand);
            // What this window takes from those behind it: itself, and — if it has one —
            // its own grab zone, which wins where two windows' zones meet.
            above.add(resizable[n] ? inflate(w, s.outside) : w.clone());
        }
        return out;
    }

    // ── a drag ──

    /**
     * The smallest a drag may make a window, {@code {width, height}}: never less than the
     * window manager will hold it to when the drag lets go
     * (Task#adjustForMinimalTaskDimensions), so an outline never promises a size the
     * window will not take.
     *
     * <p>{@code taskMinW}/{@code taskMinH} are the task's own minimum, -1 for none: pixels
     * as the platform parsed them from its ROOT activity's manifest, which it applies as
     * they are on every display — so never rescaled for the desktop's density here either.
     * {@code defaultPx} is the platform's minimum for a resizable window on this display,
     * kept as the floor even where an app asks for less; {@code floorH} is the title bar
     * and a little of the window.
     */
    static int[] minSize(int taskMinW, int taskMinH, int defaultPx, int floorH) {
        int w = Math.max(defaultPx, taskMinW);
        int h = Math.max(Math.max(defaultPx, taskMinH), floorH);
        return new int[]{w, h};
    }

    /**
     * The window after its {@code edges} have been dragged by (dx, dy) from {@code start}.
     *
     * <p>Only the dragged edges move. Each stops where the window would get smaller than
     * {@code minW} x {@code minH} — or than it already is, for a window that started
     * smaller than that — and where it would leave {@code limits} (the display above the
     * taskbar), unless it was already beyond them when the drag began.
     *
     * <p>A drag never brings a window to one rect: from the left edge (x ≤ 4) to the
     * display's last column. The computer's desktop session reads exactly that as
     * "something just maximised this window" (scrcpy.rs, Enforcer::fills) and answers by
     * maximising it itself; a drag that happens to end there must not set that off. Only
     * a side edge can get there, so only a side edge is held back: a window that spans
     * the width already keeps doing so when its top or bottom moves, and the computer has
     * read it as maximised already (it checks every pass, not on a change).
     */
    static int[] resized(int[] start, int edges, int dx, int dy, int minW, int minH,
                         int[] limits) {
        int[] r = start.clone();
        int width = start[2] - start[0];
        int height = start[3] - start[1];
        int mw = Math.min(minW, width);
        int mh = Math.min(minH, height);
        if ((edges & LEFT) != 0) {
            int lo = Math.min(limits[0], start[0]);
            r[0] = clamp(start[0] + dx, lo, start[2] - mw);
        } else if ((edges & RIGHT) != 0) {
            int hi = Math.max(limits[2], start[2]);
            r[2] = clamp(start[2] + dx, start[0] + mw, hi);
        }
        if ((edges & TOP) != 0) {
            int lo = Math.min(limits[1], start[1]);
            r[1] = clamp(start[1] + dy, lo, start[3] - mh);
        } else if ((edges & BOTTOM) != 0) {
            int hi = Math.max(limits[3], start[3]);
            r[3] = clamp(start[3] + dy, start[1] + mh, hi);
        }
        int lastColumn = limits[2] - 1;
        if (r[0] <= 4 && r[2] >= lastColumn) {
            if ((edges & RIGHT) != 0) {
                r[2] = lastColumn - 1;
            } else if ((edges & LEFT) != 0) {
                r[0] = 5;
            }
        }
        return r;
    }

    // ── rect helpers ──

    static int[] inflate(int[] r, int by) {
        return new int[]{r[0] - by, r[1] - by, r[2] + by, r[3] + by};
    }

    /** The overlap of two rects, or null when they do not overlap. */
    static int[] intersect(int[] a, int[] b) {
        int l = Math.max(a[0], b[0]);
        int t = Math.max(a[1], b[1]);
        int r = Math.min(a[2], b[2]);
        int bt = Math.min(a[3], b[3]);
        return l < r && t < bt ? new int[]{l, t, r, bt} : null;
    }

    /** {@code rects} with {@code cut} taken out of each: up to four pieces per rect. */
    static List<int[]> subtract(List<int[]> rects, int[] cut) {
        List<int[]> out = new ArrayList<>();
        for (int[] a : rects) {
            int[] x = intersect(a, cut);
            if (x == null) {
                out.add(a);
                continue;
            }
            if (a[1] < x[1]) out.add(new int[]{a[0], a[1], a[2], x[1]});      // above
            if (x[3] < a[3]) out.add(new int[]{a[0], x[3], a[2], a[3]});      // below
            if (a[0] < x[0]) out.add(new int[]{a[0], x[1], x[0], x[3]});      // left
            if (x[2] < a[2]) out.add(new int[]{x[2], x[1], a[2], x[3]});      // right
        }
        return out;
    }

    static boolean contains(List<int[]> rects, int x, int y) {
        for (int[] r : rects) {
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true;
        }
        return false;
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
