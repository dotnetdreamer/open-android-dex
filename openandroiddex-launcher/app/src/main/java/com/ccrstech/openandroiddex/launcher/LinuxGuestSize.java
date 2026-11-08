package com.ccrstech.openandroiddex.launcher;

/**
 * How big the Linux desktop is for the window it is shown in: the launcher's
 * copy of the viewer's rule, {@code assets/linux/novnc/dex-size.js}.
 *
 * The viewer asks Xvnc for that size the moment it connects and whenever its
 * window changes shape (doc/linux-viewer.md, "The desktop follows the
 * window"). {@link LinuxActivity} starts the desktop at it, so that the first
 * ask finds it already there rather than laying XFCE out a second time a
 * moment after it started. The two copies agree to the pixel over a sweep of
 * windows — test/linux-guest-size-test.mjs runs both — and nothing in here
 * needs Android, so a plain JVM can run it.
 */
final class LinuxGuestSize {
    private LinuxGuestSize() {
    }

    /** Guest px to the CSS px on the phone, in either orientation. */
    static final double PHONE_SCALE = 2.25;
    /** XFCE's dialogs stop fitting below this; a phone on its side is ~300 CSS px tall. */
    static final int PHONE_SHORT_MIN = 720;
    /** Past this a tablet would draw its desktop smaller than a phone does. */
    static final int PHONE_SHORT_MAX = 1200;
    /**
     * Every pixel is drawn by the phone's CPU and encoded again for each
     * viewer; and LinuxService starts no desktop taller than 2400, so a phone
     * upright never asks for one taller than it started.
     */
    static final int PHONE_LONG_MAX = 2400;

    /** The desktop display: the start's limits (LinuxService), a computer's too. */
    static final int DESKTOP_MIN_W = 800;
    static final int DESKTOP_MIN_H = 600;
    static final int DESKTOP_MAX_W = 3840;
    static final int DESKTOP_MAX_H = 2400;

    /**
     * The viewer's docked bar, CSS px: dex.css's --bar on a phone's window up
     * to 560 wide, on a wider one, and on the desktop display (is-desktop).
     */
    static final double BAR_PHONE_NARROW = 52;
    static final double BAR_PHONE = 56;
    static final double BAR_DESKTOP = 44;
    /** The keys row, pinned: dex.css's --keyrow, and in a window at most 420 CSS px tall. */
    static final double KEYROW = 44;
    static final double KEYROW_SHORT = 40;

    /**
     * The guest's screen for a box the desktop has for good.
     *
     * @param phone the phone's own screen, rather than a window on the desktop display
     * @param w     the box, CSS px
     * @param h     the box, CSS px
     * @param dpr   device px per CSS px
     * @return {width, height}, in the guest's own pixels
     */
    static int[] forBox(boolean phone, double w, double h, double dpr) {
        if (phone) {
            double shortSide = Math.min(w, h);
            double longSide = Math.max(w, h);
            double k = PHONE_SCALE;
            k = Math.max(k, PHONE_SHORT_MIN / shortSide);
            k = Math.min(k, PHONE_SHORT_MAX / shortSide);
            k = Math.min(k, PHONE_LONG_MAX / longSide);
            // The short side first, the long side at the box's own ratio from
            // it: only the long side's rounding is left over, within one guest
            // pixel of the box's shape. The long side's limit, where it bites,
            // holds.
            int s = even(shortSide * k);
            int l = even(s * longSide / shortSide);
            if (l > PHONE_LONG_MAX) {
                l = PHONE_LONG_MAX;
                s = even(l * shortSide / longSide);
            }
            return w <= h ? new int[]{s, l} : new int[]{l, s};
        }
        // Whole device pixels per guest pixel — 1 around 160 dpi, 2 from 280, 3
        // from 440. XFCE draws at 96 dpi whatever the display (its xsettings pin
        // the scale at 1, for VNC's sake), so one for one would make it half the
        // size of everything around it on a desktop fitted to a 2x screen, and
        // a fraction would smear its one-pixel lines across two.
        int per = Math.max(1, (int) Math.floor(dpr + 0.25));
        return new int[]{
                Math.min(Math.max(evenDown(w * dpr / per), DESKTOP_MIN_W), DESKTOP_MAX_W),
                Math.min(Math.max(evenDown(h * dpr / per), DESKTOP_MIN_H), DESKTOP_MAX_H)};
    }

    /**
     * The guest's screen for the viewer in a window of this many device
     * pixels: the box is the window less the docked bar and a pinned keys row,
     * as the page measures it (dex.js, steadyBox).
     *
     * @param barDocked  the bar is docked, rather than folded into the bubble
     * @param keysPinned the keys row is pinned on
     */
    static int[] forWindow(boolean phone, int widthPx, int heightPx, double dpr,
                           boolean barDocked, boolean keysPinned) {
        double w = widthPx / dpr;
        double h = heightPx / dpr;
        double bar = !barDocked ? 0 : !phone ? BAR_DESKTOP : w <= 560 ? BAR_PHONE_NARROW : BAR_PHONE;
        double row = !keysPinned ? 0 : h <= 420 ? KEYROW_SHORT : KEYROW;
        return forBox(phone, w, h - bar - row, dpr);
    }

    /** Even, as every encoder takes (LinuxStream's rule): rounded, for the closest shape. */
    private static int even(double v) {
        return Math.max(2, 2 * (int) Math.round(v / 2));
    }

    /** Even and rounded down, so the desktop still fits at whole device pixels. */
    private static int evenDown(double v) {
        return 2 * (int) Math.floor(v / 2);
    }
}
