package com.ccrstech.openandroiddex.launcher;

/**
 * Keysyms to keycodes, for XTEST — which presses keycodes, while everything
 * on the desktop's side of the connection knows only characters and named
 * keys.
 *
 * <p>The keyboard map is the server's own (GetKeyboardMapping): Xvnc's XKB
 * keymap as the core protocol sees it, so for a us layout the columns are
 * [base, Shift, group 2, group 2 + Shift, Level 3, Level 3 + Shift, …]. A
 * character found in column 1 needs Shift held, in column 4 the Level 3
 * shift, and that is all a typed character ever needs: the desktop has
 * already applied the computer's own layout to make it.
 *
 * <p>A keysym the map does not have — é on a us layout, any CJK character, an
 * emoji — is given a keycode nobody uses (ChangeKeyboardMapping, the way
 * xdotool and TigerVNC's own server do it), both columns the same so that
 * Shift cannot change it. Those few keycodes are reused, least recently used
 * first.
 *
 * <p>Pure Java; not thread-safe — {@link LinuxWm} holds its lock around it.
 */
final class X11Keys {

    static final int NO_SYMBOL = 0;
    static final int XK_SHIFT_L = 0xffe1;
    static final int XK_SHIFT_R = 0xffe2;
    static final int XK_CONTROL_L = 0xffe3;
    static final int XK_CONTROL_R = 0xffe4;
    static final int XK_ALT_L = 0xffe9;
    static final int XK_ALT_R = 0xffea;
    static final int XK_SUPER_L = 0xffeb;
    static final int XK_SUPER_R = 0xffec;
    static final int XK_ISO_LEVEL3_SHIFT = 0xfe03;

    /** Where a keysym is: its keycode, and the column it sits in there. */
    static final class Place {
        final int keycode;
        final int column;

        Place(int keycode, int column) {
            this.keycode = keycode;
            this.column = column;
        }

        boolean needsShift() {
            return (column & 1) == 1;
        }

        boolean needsLevel3() {
            return column >= 4;
        }
    }

    private final int min;
    private int[][] map;
    /** Keycodes with nothing on them, for keysyms the layout lacks; least recently used first. */
    private final java.util.ArrayDeque<Integer> spare = new java.util.ArrayDeque<>();
    /** keysym → the spare keycode it was given. */
    private final java.util.HashMap<Integer, Integer> lent = new java.util.HashMap<>();

    X11Keys(int minKeycode, int[][] map) {
        this.min = minKeycode;
        setMap(map);
    }

    /** A new map from the server (a MappingNotify): the loans are forgotten with the old one. */
    void setMap(int[][] map) {
        this.map = map;
        spare.clear();
        lent.clear();
        for (int i = 0; i < map.length; i++) {
            boolean empty = true;
            for (int k : map[i]) empty &= k == NO_SYMBOL;
            // Keycodes below 9 are not real keys to every client; leave them.
            if (empty && min + i >= 9) spare.addLast(min + i);
        }
    }

    /**
     * Where {@code keysym} is, preferring the plain column, then Shift, then
     * Level 3 — never group 2, which a single-group layout does not reach
     * without a Mode_switch nobody holds. Null if it is nowhere.
     */
    Place find(int keysym) {
        if (keysym == NO_SYMBOL) return null;
        int[] order = {0, 1, 4, 5};
        for (int col : order) {
            for (int i = 0; i < map.length; i++) {
                if (map[i].length > col && map[i][col] == keysym) return new Place(min + i, col);
            }
        }
        // A keycode with only one keysym on it lists it in column 0 alone;
        // a letter's capital is also found by its lowercase key with Shift.
        int lower = toLower(keysym);
        if (lower != keysym) {
            for (int i = 0; i < map.length; i++) {
                if (map[i].length > 0 && map[i][0] == lower
                        && (map[i].length < 2 || map[i][1] == NO_SYMBOL)) {
                    return new Place(min + i, 1);
                }
            }
        }
        return null;
    }

    /** The keycode of a modifier (or any key that needs no column): column 0 or 1, never shifted. */
    int keycodeOf(int keysym) {
        for (int i = 0; i < map.length; i++) {
            if (map[i].length > 0 && map[i][0] == keysym) return min + i;
        }
        for (int i = 0; i < map.length; i++) {
            for (int k : map[i]) {
                if (k == keysym) return min + i;
            }
        }
        return 0;
    }

    /**
     * A spare keycode lent to {@code keysym}: the caller sends the
     * ChangeKeyboardMapping this returns the arguments for. Null when there
     * is no keycode to lend.
     *
     * @param busy keycodes currently held down, which must not be taken back
     */
    int[] lend(int keysym, java.util.Set<Integer> busy) {
        Integer have = lent.get(keysym);
        if (have != null) {
            spare.remove(have);
            spare.addLast(have);
            return null; // already there
        }
        Integer kc = null;
        for (Integer k : spare) {
            if (!busy.contains(k)) {
                kc = k;
                break;
            }
        }
        if (kc == null) return null;
        spare.remove(kc);
        spare.addLast(kc);
        // Whoever had it loses it.
        Integer previous = null;
        for (java.util.Map.Entry<Integer, Integer> e : lent.entrySet()) {
            if (e.getValue().equals(kc)) previous = e.getKey();
        }
        if (previous != null) lent.remove(previous);
        lent.put(keysym, kc);
        int[] row = map[kc - min];
        for (int c = 0; c < row.length; c++) row[c] = c < 2 ? keysym : NO_SYMBOL;
        return new int[]{kc, keysym};
    }

    /** The keycode lent to {@code keysym}, or 0. */
    int lentTo(int keysym) {
        Integer k = lent.get(keysym);
        return k == null ? 0 : k;
    }

    /** Lower-case keysym for a Latin letter, as Xlib's XConvertCase does for the ranges that matter here. */
    static int toLower(int ks) {
        if (ks >= 'A' && ks <= 'Z') return ks + 32;
        if (ks >= 0xc0 && ks <= 0xde && ks != 0xd7) return ks + 32;
        if ((ks & 0xff000000) == 0x01000000) {
            int cp = ks & 0xffffff;
            int lo = Character.toLowerCase(cp);
            return lo == cp ? ks : unicodeKeysym(lo);
        }
        return ks;
    }

    /**
     * The keysym for a typed character: Latin-1 is itself, everything else
     * the Unicode block (0x01000000 + code point), as every X client
     * understands since X11R6.9.
     */
    static int unicodeKeysym(int codePoint) {
        if (codePoint >= 0x20 && codePoint <= 0x7e) return codePoint;
        if (codePoint >= 0xa0 && codePoint <= 0xff) return codePoint;
        switch (codePoint) {
            case '\n':
            case '\r':
                return 0xff0d; // Return
            case '\t':
                return 0xff09; // Tab
            case 0x08:
                return 0xff08; // BackSpace
            case 0x1b:
                return 0xff1b; // Escape
            case 0x7f:
                return 0xffff; // Delete
            default:
                return 0x01000000 | codePoint;
        }
    }
}
