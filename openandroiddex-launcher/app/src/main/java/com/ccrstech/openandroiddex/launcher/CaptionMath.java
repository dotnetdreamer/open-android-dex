package com.ccrstech.openandroiddex.launcher;

/**
 * The arithmetic of a title bar's More and Sound buttons, kept free of Android so a
 * computer's JVM can check it (test/caption-bar-test.mjs): where More's Phone size and
 * Tablet size put a window, which of the two it is now, and where a bar's menu opens.
 * All in the desktop display's pixels; rects are {left, top, right, bottom}.
 */
final class CaptionMath {

    private CaptionMath() {
    }

    /** A current phone's room, in dp: the computer's window gives an app the same (apps/shape.rs PHONE). */
    static final int PHONE_W_DP = 412;
    static final int PHONE_H_DP = 915;
    /** A tablet's, on its side: a Galaxy Tab's or a Pixel Tablet's (apps/shape.rs TABLET). */
    static final int TABLET_W_DP = 1280;
    static final int TABLET_H_DP = 800;
    /** Where most apps take their tablet layout from: the shorter side, in dp (sw600dp). */
    static final int TABLET_FROM_DP = 600;
    /** Smaller than this on either side, in dp, and a size is not worth giving: nothing is done. */
    static final int LEAST_DP = 160;

    /** {@link #sizeFor}'s answer when a tablet's size is the whole desktop instead: maximise it. */
    static final int[] MAXIMISE = new int[0];

    /**
     * Where Phone size or Tablet size puts a window: its new rect, {@link #MAXIMISE}, or null
     * for nothing to do.
     *
     * <p>The app is given a phone's room or a tablet's, fitted as the computer's window fits
     * them (apps/shape.rs fit): the window, its bar included, no taller than 90 % of the room
     * above the taskbar and no wider than 95 % of it, and never larger than asked. It stays
     * where its top-left is, moved only as far as it has to be to fit; one that was maximised
     * comes down in the middle. A tablet's size that comes out under 600 dp is no tablet's to
     * its app, so where the whole room is one, the window is maximised instead, as the
     * computer's window does; where not even that is, it gets the most there is.
     *
     * @param window    the window's rect now, its bar included
     * @param max       the maximised rect: the room above the taskbar
     * @param bar       the bar's height
     * @param density   the desktop's pixels per dp
     * @param maximised whether the window is on the maximised rect now
     */
    static int[] sizeFor(boolean tablet, int[] window, int[] max, int bar, float density,
                         boolean maximised) {
        int roomW = max[2] - max[0];
        int roomH = max[3] - max[1];
        float wantW = Math.round((tablet ? TABLET_W_DP : PHONE_W_DP) * density);
        float wantH = Math.round((tablet ? TABLET_H_DP : PHONE_H_DP) * density);
        float k = Math.max(0f, Math.min(1f,
                Math.min((roomH * 0.9f - bar) / wantH, roomW * 0.95f / wantW)));
        int w = Math.round(wantW * k);
        int h = Math.round(wantH * k);
        int tabletFrom = Math.round(TABLET_FROM_DP * density);
        if (tablet && Math.min(w, h) < tabletFrom && Math.min(roomW, roomH - bar) >= tabletFrom) {
            return MAXIMISE;
        }
        int least = Math.round(LEAST_DP * density);
        if (w < least || h < least) return null;
        int left;
        int top;
        if (maximised) {
            left = max[0] + (roomW - w) / 2;
            top = max[1] + (roomH - h - bar) / 2;
        } else {
            left = Math.max(max[0], Math.min(window[0], max[2] - w));
            top = Math.max(max[1], Math.min(window[1], max[3] - h - bar));
        }
        return new int[]{left, top, left + w, top + h + bar};
    }

    /** Whether a window's app has a tablet's room now: 600 dp and more on its shorter side. */
    static boolean tabletSized(int[] window, int bar, float density) {
        int w = window[2] - window[0];
        int h = window[3] - window[1] - bar;
        return Math.min(w, h) >= Math.round(TABLET_FROM_DP * density);
    }

    /**
     * Where a bar's menu window goes, {x, y}: its card's top-left at the button's bottom-left,
     * or its card's bottom at the button's top where there is no room below — inside the
     * display, and clear of the taskbar along its bottom. The window is the card with
     * {@code margin} of room for its shadow on every side, which may hang off the display.
     *
     * @param button  the button's rect on the display
     * @param w       the window's width, margins included
     * @param h       the window's height, margins included
     * @param display the display's {width, height}
     * @param bottom  the rows along the display's bottom to keep clear
     */
    static int[] menuAt(int[] button, int w, int h, int margin, int[] display, int bottom) {
        int x = Math.max(-margin, Math.min(button[0] - margin, display[0] - w + margin));
        int y = button[3] - margin;
        if (y + h - margin > display[1] - bottom) {
            int above = button[1] - h + margin;
            y = above >= -margin ? above : Math.max(-margin, display[1] - bottom - h + margin);
        }
        return new int[]{x, y};
    }

    /**
     * Which of a bar's yielding controls stay shown on a window {@code width} wide. With every
     * control shown the bar's controls take {@code fixed}; {@code room} more is kept for the
     * window's name, which is also the handle it is dragged by. The yielding ones, {@code
     * widths} wide (0 for one a bar has not got), make way in order — the first first — until
     * the rest fits. The controls that never yield (Back and ✕) are in {@code fixed} alone.
     */
    static boolean[] shown(int fixed, int[] widths, int room, int width) {
        boolean[] shown = new boolean[widths.length];
        int need = fixed + room;
        for (int i = 0; i < widths.length; i++) {
            shown[i] = need <= width;
            if (!shown[i]) need -= widths[i];
        }
        return shown;
    }

    /**
     * Whether a press on a menu's button is the press that has just closed that same menu,
     * as a press outside it: the menu hears a press go down, the button hears it come up, a
     * moment apart. Only that bar's that button, and only within {@code withinMs}.
     */
    static boolean samePress(int goneTask, int goneKind, long goneAt, int task, int kind,
                             long now, long withinMs) {
        return goneTask == task && goneKind == kind && goneAt > 0 && now - goneAt < withinMs;
    }
}
