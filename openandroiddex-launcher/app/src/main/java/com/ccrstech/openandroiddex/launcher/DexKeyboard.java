package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The desktop's own on-screen keyboard.
 *
 * <p><b>Why the launcher draws a keyboard instead of raising Android's.</b>
 * The desktop is a virtual display created by the shell, and before Android 13
 * a virtual display cannot be TRUSTED — the flag does not exist. The window
 * manager refuses to put the IME on an untrusted display at all:
 * {@code setShouldShowIme} answers {@code SecurityException: Attempted to set
 * IME flag to an untrusted virtual display}, measured on a Redmi Note 7
 * (Android 10). No uid can talk it round, the daemon's included — so on those
 * phones the system keyboard can only ever appear on the phone's own panel,
 * which is the screen the user is not looking at. A keyboard we draw is the
 * only one that can be here.
 *
 * <p>Where the display IS trusted the system keyboard is the better one — the
 * user's own layout, languages and prediction — so the taskbar button prefers
 * it and only falls back to this. See
 * {@code LauncherActivity#applyOnScreenKeyboard}.
 *
 * <p><b>Why the keys type through accessibility.</b> {@code INJECT_EVENTS} is
 * signature-level, so this APK cannot post a key event. An accessibility
 * service can write straight into the focused editable's node, which is
 * display-agnostic — it is how the web viewer has always typed. The cost is
 * worth stating because it bounds the feature: this types into a text FIELD,
 * through its node. It produces no key events, so a game, a canvas or a
 * terminal emulator reading raw keys gets nothing.
 *
 * <p><b>The window must never take focus.</b> {@code FLAG_NOT_FOCUSABLE} keeps
 * the app's text field the focused window while its keys are being pressed —
 * without it the first press moves focus here and every keystroke afterwards
 * has nowhere to land. Same reason the taskbar overlay carries the flag.
 */
final class DexKeyboard {

    /** One key: what it shows, what it shows shifted, and how wide it sits. */
    private static final class Key {
        final String label;
        final String shifted;
        final int code;
        final float weight;

        Key(String label, String shifted, int code, float weight) {
            this.label = label;
            this.shifted = shifted;
            this.code = code;
            this.weight = weight;
        }
    }

    // Anything >= 0 is a character key and types its own label.
    private static final int CH = 0;
    private static final int BACKSPACE = -1;
    private static final int ENTER = -2;
    private static final int SHIFT = -3;
    private static final int CAPS = -4;
    private static final int TAB = -5;
    private static final int SPACE = -6;
    private static final int LEFT = -7;
    private static final int RIGHT = -8;
    private static final int UP = -9;
    private static final int DOWN = -10;
    private static final int ESC = -12;
    private static final int HOME_KEY = -13;
    private static final int END_KEY = -14;

    /**
     * A PC layout rather than a phone one, because this board is aimed at with
     * a mouse on a 1920-wide desktop: a number row and real modifiers cost
     * nothing here, and a phone's two-page symbol shuffle would be the odd
     * thing on a screen this size.
     */
    private static final Key[][] ROWS = {
            {
                    new Key("`", "~", CH, 1f), new Key("1", "!", CH, 1f),
                    new Key("2", "@", CH, 1f), new Key("3", "#", CH, 1f),
                    new Key("4", "$", CH, 1f), new Key("5", "%", CH, 1f),
                    new Key("6", "^", CH, 1f), new Key("7", "&", CH, 1f),
                    new Key("8", "*", CH, 1f), new Key("9", "(", CH, 1f),
                    new Key("0", ")", CH, 1f), new Key("-", "_", CH, 1f),
                    new Key("=", "+", CH, 1f), new Key("⌫", "⌫", BACKSPACE, 2f),
            },
            {
                    new Key("Tab", "Tab", TAB, 1.5f),
                    new Key("q", "Q", CH, 1f), new Key("w", "W", CH, 1f),
                    new Key("e", "E", CH, 1f), new Key("r", "R", CH, 1f),
                    new Key("t", "T", CH, 1f), new Key("y", "Y", CH, 1f),
                    new Key("u", "U", CH, 1f), new Key("i", "I", CH, 1f),
                    new Key("o", "O", CH, 1f), new Key("p", "P", CH, 1f),
                    new Key("[", "{", CH, 1f), new Key("]", "}", CH, 1f),
                    new Key("\\", "|", CH, 1.5f),
            },
            {
                    new Key("Caps", "Caps", CAPS, 1.8f),
                    new Key("a", "A", CH, 1f), new Key("s", "S", CH, 1f),
                    new Key("d", "D", CH, 1f), new Key("f", "F", CH, 1f),
                    new Key("g", "G", CH, 1f), new Key("h", "H", CH, 1f),
                    new Key("j", "J", CH, 1f), new Key("k", "K", CH, 1f),
                    new Key("l", "L", CH, 1f), new Key(";", ":", CH, 1f),
                    new Key("'", "\"", CH, 1f),
                    new Key("Enter", "Enter", ENTER, 2.2f),
            },
            {
                    new Key("Shift", "Shift", SHIFT, 2.4f),
                    new Key("z", "Z", CH, 1f), new Key("x", "X", CH, 1f),
                    new Key("c", "C", CH, 1f), new Key("v", "V", CH, 1f),
                    new Key("b", "B", CH, 1f), new Key("n", "N", CH, 1f),
                    new Key("m", "M", CH, 1f), new Key(",", "<", CH, 1f),
                    new Key(".", ">", CH, 1f), new Key("/", "?", CH, 1f),
                    new Key("↑", "↑", UP, 1f), new Key("Shift", "Shift", SHIFT, 1.6f),
            },
            {
                    new Key("Esc", "Esc", ESC, 1.4f),
                    new Key("Home", "Home", HOME_KEY, 1.4f),
                    new Key("Space", "Space", SPACE, 6.2f),
                    new Key("End", "End", END_KEY, 1.4f),
                    new Key("←", "←", LEFT, 1f), new Key("↓", "↓", DOWN, 1f),
                    new Key("→", "→", RIGHT, 1f),
            },
    };

    /** Width of the board when it is floating rather than docked, in dp. */
    private static final int FLOAT_DP = 900;
    /** Grip strip along the top: drag handle on the left, window buttons right. */
    private static final int HEADER_DP = 30;

    private final LauncherActivity host;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Every accessibility call the keys make runs here, never on the main
     * thread. {@code performAction} is a blocking binder round trip into the
     * target app with a five-second timeout — on the UI thread one wedged app
     * would freeze the whole shell. One thread rather than a pool, so
     * keystrokes stay in the order they were pressed.
     */
    private HandlerThread keysThread;
    private Handler ipc;

    private View panel;
    private View keysBox;
    private WindowManager.LayoutParams lp;
    private DexTheme theme;

    private boolean shift;
    private boolean caps;

    // ── how the board is sitting. Survives a shell rebuild, which hides and
    // re-shows this same object, so a repaint does not undo the user's layout.
    private boolean docked = true;
    private boolean collapsed;
    /** Floating position, in display px. Meaningless while docked. */
    private int floatX = Integer.MIN_VALUE;
    private int floatY;

    private TextView collapseButton;
    private TextView dockButton;
    /** Every character key, so a shift press can re-letter them in place. */
    private final java.util.List<TextView> charKeys = new java.util.ArrayList<>();
    private final java.util.List<Key> charModels = new java.util.ArrayList<>();
    /** The Shift keys and the Caps key, so they can show they are latched. */
    private final java.util.List<TextView> shiftKeys = new java.util.ArrayList<>();
    private TextView capsKey;

    DexKeyboard(LauncherActivity host) {
        this.host = host;
        this.wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean showing() {
        return panel != null;
    }

    void toggle() {
        if (showing()) hide();
        else show();
    }

    void show() {
        if (showing()) return;
        theme = DexTheme.of(host);
        shift = false;
        caps = false;
        if (keysThread == null) {
            keysThread = new HandlerThread("dex-keys");
            keysThread.start();
            ipc = new Handler(keysThread.getLooper());
        }
        panel = build();
        DexCursors.decorate(panel);
        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE is the whole trick: the app's text field keeps
                // window focus, so the node we type into is still the focused
                // one when the key is released. NOT_TOUCH_MODAL lets everything
                // outside the board go on reaching the desktop underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        applyPlacement();
        try {
            wm.addView(panel, lp);
        } catch (Exception e) {
            DexLog.warn("osk", "keyboard window rejected", e);
            panel = null;
        }
    }

    void hide() {
        if (panel == null) return;
        try {
            wm.removeViewImmediate(panel);
        } catch (Exception ignored) {
        }
        panel = null;
        keysBox = null;
        collapseButton = null;
        dockButton = null;
        capsKey = null;
        charKeys.clear();
        charModels.clear();
        shiftKeys.clear();
    }

    // ── placement: docked full width, or floating where it was dropped ──────

    /**
     * Docked is the default and the useful one: the full width of the display,
     * sitting directly on top of the taskbar, where a desktop keyboard lives
     * and where it can never cover the bar the user needs to reach it again.
     * Floating is what a drag turns it into.
     */
    private void applyPlacement() {
        if (lp == null) return;
        if (docked) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            lp.y = host.dp(LauncherActivity.TASKBAR_DP);
        } else {
            lp.width = Math.min(host.dp(FLOAT_DP), displaySize().x - host.dp(32));
            lp.gravity = Gravity.TOP | Gravity.START;
            if (floatX == Integer.MIN_VALUE) centreFloat();
            clampFloat();
            lp.x = floatX;
            lp.y = floatY;
        }
    }

    private void centreFloat() {
        Point size = displaySize();
        floatX = (size.x - Math.min(host.dp(FLOAT_DP), size.x - host.dp(32))) / 2;
        floatY = Math.max(0, size.y - host.dp(LauncherActivity.TASKBAR_DP) - host.dp(300));
    }

    /** A board dragged off the display cannot be dragged back — keep it reachable. */
    private void clampFloat() {
        Point size = displaySize();
        int w = lp.width > 0 ? lp.width : host.dp(FLOAT_DP);
        int edge = host.dp(80);           // how much may hang off, so the grip stays hittable
        floatX = Math.max(-(w - edge), Math.min(floatX, size.x - edge));
        floatY = Math.max(0, Math.min(floatY, size.y - host.dp(HEADER_DP)));
    }

    private Point displaySize() {
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        if (p.x <= 0 || p.y <= 0) p.set(1920, 1080);
        return p;
    }

    private void relayout() {
        if (panel == null) return;
        applyPlacement();
        try {
            wm.updateViewLayout(panel, lp);
        } catch (Exception ignored) {
        }
    }

    // ── the board ──────────────────────────────────────────────────────────

    private View build() {
        LinearLayout board = new LinearLayout(host);
        board.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(6);
        board.setPadding(pad, 0, pad, pad);
        // Square top corners while docked: it is sitting on the taskbar, and a
        // rounded edge against a straight bar reads as a panel that missed.
        board.setBackground(host.roundedFill(theme.card(), docked ? 0 : 14));
        board.setElevation(host.dp(12));
        board.addView(buildHeader());
        keysBox = buildKeys();
        keysBox.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        board.addView(keysBox);
        DexFonts.applyTo(host, board);
        return board;
    }

    /**
     * The grip. Dragging it anywhere moves the board — and dragging a DOCKED
     * board is what floats it, because "pick it up" is the only gesture anyone
     * tries and it should not have to be armed by a button first.
     */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(host);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(HEADER_DP)));

        TextView grip = new TextView(host);
        grip.setText("⠿  " + host.getString(R.string.lx_keyboard));
        grip.setTextColor(theme.textFaint);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_PX, host.sp(11));
        grip.setGravity(Gravity.CENTER_VERTICAL);
        grip.setPadding(host.dp(6), 0, host.dp(6), 0);
        grip.setContentDescription(host.getString(R.string.lx_kb_move));
        grip.setOnTouchListener(new DragTouch());
        header.addView(grip, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        collapseButton = headerButton(collapsed ? "▴" : "▾",
                collapsed ? R.string.lx_kb_expand : R.string.lx_kb_collapse,
                v -> setCollapsed(!collapsed));
        header.addView(collapseButton);
        dockButton = headerButton(docked ? "⧉" : "⤓",
                docked ? R.string.lx_kb_float : R.string.lx_kb_dock,
                v -> setDocked(!docked));
        header.addView(dockButton);
        header.addView(headerButton("✕", R.string.lx_kb_close,
                v -> host.dismissOnScreenKeyboard()));
        return header;
    }

    private TextView headerButton(String glyph, int description, View.OnClickListener onClick) {
        TextView b = new TextView(host);
        b.setText(glyph);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(theme.textDim);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, host.sp(12));
        b.setBackground(host.tapBackground(0x00000000, theme.hover, 6));
        b.setContentDescription(host.getString(description));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                host.dp(30), host.dp(HEADER_DP - 4));
        blp.leftMargin = host.dp(2);
        b.setLayoutParams(blp);
        return b;
    }

    private View buildKeys() {
        LinearLayout keys = new LinearLayout(host);
        keys.setOrientation(LinearLayout.VERTICAL);
        for (Key[] row : ROWS) keys.addView(buildRow(row));
        return keys;
    }

    private View buildRow(Key[] row) {
        LinearLayout line = new LinearLayout(host);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = host.dp(3);
        line.setLayoutParams(rowLp);
        for (Key k : row) line.addView(buildKey(k));
        return line;
    }

    /**
     * Key height follows the width the board was given, so a full-width board
     * on a 1920 display does not end up a row of long thin slots: the keys keep
     * something close to a keyboard's proportions at any size, within bounds
     * that stay comfortable to hit with a pointer.
     */
    private int keyHeight() {
        int width = docked ? displaySize().x
                : Math.min(host.dp(FLOAT_DP), displaySize().x - host.dp(32));
        int perUnit = width / 15;                 // the widest row is ~15 units
        return Math.max(host.dp(38), Math.min(host.dp(62), Math.round(perUnit * 0.62f)));
    }

    private TextView buildKey(Key k) {
        TextView key = new TextView(host);
        key.setText(k.label);
        key.setGravity(Gravity.CENTER);
        key.setTextColor(k.code == CH ? theme.text : theme.textDim);
        key.setTextSize(TypedValue.COMPLEX_UNIT_PX, host.sp(k.code == CH ? 15 : 12));
        key.setBackground(host.tapBackground(theme.field, theme.hover, 7));
        key.setContentDescription(k.label);
        // Touch rather than click: a key must act on DOWN to feel like a key,
        // and holding one has to repeat — which is the only bearable way to
        // clear a field with backspace.
        key.setOnTouchListener(new RepeatTouch(k));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, keyHeight(), k.weight);
        lp.leftMargin = host.dp(3);
        key.setLayoutParams(lp);
        if (k.code == CH) {
            charKeys.add(key);
            charModels.add(k);
        } else if (k.code == SHIFT) {
            shiftKeys.add(key);
        } else if (k.code == CAPS) {
            capsKey = key;
        }
        return key;
    }

    // ── window controls ────────────────────────────────────────────────────

    private void setCollapsed(boolean value) {
        collapsed = value;
        if (keysBox != null) keysBox.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (collapseButton != null) {
            collapseButton.setText(collapsed ? "▴" : "▾");
            collapseButton.setContentDescription(host.getString(
                    collapsed ? R.string.lx_kb_expand : R.string.lx_kb_collapse));
        }
        relayout();
    }

    private void setDocked(boolean value) {
        if (docked == value) return;
        if (value) {
            docked = true;
        } else {
            // Float it where it already is, so the board does not jump out
            // from under the pointer that asked for it.
            int[] at = new int[2];
            panel.getLocationOnScreen(at);
            floatX = at[0];
            floatY = at[1];
            docked = false;
        }
        rebuild();
    }

    /** Rebuild in place: corner radius and key height both follow the mode. */
    private void rebuild() {
        if (panel == null) return;
        boolean wasCollapsed = collapsed;
        boolean wasShift = shift;
        boolean wasCaps = caps;
        hide();
        collapsed = wasCollapsed;
        show();
        shift = wasShift;
        caps = wasCaps;
        relabel();
    }

    /**
     * Drag the grip to move the board; dragging a docked one floats it first.
     * The threshold keeps a plain click on the strip from nudging it.
     */
    private final class DragTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging) {
                        if (Math.abs(dx) < host.dp(6) && Math.abs(dy) < host.dp(6)) return true;
                        dragging = true;
                        if (docked) {
                            int[] at = new int[2];
                            panel.getLocationOnScreen(at);
                            floatX = at[0];
                            floatY = at[1];
                            docked = false;
                            // Rebuilding mid-drag would drop the gesture, so
                            // the board floats at its current size now and
                            // takes the floating layout on release.
                            lp.width = panel.getWidth();
                            lp.gravity = Gravity.TOP | Gravity.START;
                            if (dockButton != null) {
                                dockButton.setText("⤓");
                                dockButton.setContentDescription(
                                        host.getString(R.string.lx_kb_dock));
                            }
                        }
                        startX = floatX;
                        startY = floatY;
                    }
                    floatX = startX + Math.round(dx);
                    floatY = startY + Math.round(dy);
                    clampFloat();
                    lp.x = floatX;
                    lp.y = floatY;
                    try {
                        wm.updateViewLayout(panel, lp);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * Press, hold to repeat, release.
     */
    private final class RepeatTouch implements View.OnTouchListener {
        private static final int FIRST_MS = 420;
        private static final int NEXT_MS = 45;
        private final Key key;
        private Runnable repeat;

        RepeatTouch(Key key) {
            this.key = key;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    press(key);
                    if (repeats(key.code)) {
                        repeat = new Runnable() {
                            @Override
                            public void run() {
                                press(key);
                                main.postDelayed(this, NEXT_MS);
                            }
                        };
                        main.postDelayed(repeat, FIRST_MS);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (repeat != null) {
                        main.removeCallbacks(repeat);
                        repeat = null;
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Modifiers and Enter must not fire twice from one long press. */
    private static boolean repeats(int code) {
        return code == CH || code == BACKSPACE || code == SPACE
                || code == LEFT || code == RIGHT || code == UP || code == DOWN;
    }

    // ── pressing ───────────────────────────────────────────────────────────

    private void press(Key k) {
        switch (k.code) {
            case CH:
                insert(upper() ? k.shifted : k.label);
                clearShift();
                return;
            case SPACE:
                insert(" ");
                clearShift();
                return;
            case TAB:
                insert("\t");
                return;
            case BACKSPACE:
                backspace();
                return;
            case ENTER:
                enter();
                return;
            case SHIFT:
                shift = !shift;
                relabel();
                return;
            case CAPS:
                caps = !caps;
                relabel();
                return;
            case LEFT:
                move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, false);
                return;
            case RIGHT:
                move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, true);
                return;
            case UP:
                move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, false);
                return;
            case DOWN:
                move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, true);
                return;
            case HOME_KEY:
                caretTo(false);
                return;
            case END_KEY:
                caretTo(true);
                return;
            case ESC:
                global(AccessibilityService.GLOBAL_ACTION_BACK);
                return;
            default:
        }
    }

    private boolean upper() {
        return shift ^ caps;
    }

    private void clearShift() {
        if (!shift) return;
        shift = false;
        relabel();
    }

    /** Re-letter every character key, and light the latched modifiers. */
    private void relabel() {
        if (panel == null) return;
        boolean up = upper();
        for (int i = 0; i < charKeys.size(); i++) {
            charKeys.get(i).setText(up ? charModels.get(i).shifted : charModels.get(i).label);
        }
        for (TextView s : shiftKeys) {
            s.setBackground(host.tapBackground(shift ? theme.accentSoft : theme.field,
                    theme.hover, 7));
            s.setTextColor(shift ? theme.accent : theme.textDim);
        }
        if (capsKey != null) {
            capsKey.setBackground(host.tapBackground(caps ? theme.accentSoft : theme.field,
                    theme.hover, 7));
            capsKey.setTextColor(caps ? theme.accent : theme.textDim);
        }
    }

    // ── typing, through the focused node ───────────────────────────────────

    /**
     * The editable that currently holds input focus, or null.
     *
     * <p>{@code findFocus(FOCUS_INPUT)} rather than the active window's tree.
     * Both are display-agnostic, but "active" follows the finger while a
     * touch-exploration service (TalkBack, Switch Access) is on — pressing a
     * key would then make THIS keyboard the active window and every keystroke
     * would be typed into itself. Input focus is never ours: the window is not
     * focusable. It is also one node instead of a whole prefetched tree.
     *
     * <p>The fallback walk is for provider-backed editors — Compose, WebView —
     * whose host view answers FOCUS_INPUT with its own non-editable node while
     * the real field is a virtual node underneath it.
     */
    private AccessibilityNodeInfo target() {
        AccessibilityService service = CaptionService.live();
        if (service == null) return null;
        AccessibilityNodeInfo node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        String why;
        if (node != null) {
            if (editable(node)) return node;
            why = "input focus is " + node.getClassName() + ", which takes no text";
            recycle(node);
        } else {
            why = "nothing on this display reports input focus";
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            trace(why + "; and no window answered at all");
            return null;
        }
        try {
            AccessibilityNodeInfo found = findFocusedEditable(root);
            if (found == null) {
                trace(why + "; the window is " + root.getPackageName()
                        + " and nothing focused in it takes text");
            }
            return found;
        } finally {
            recycle(root);
        }
    }

    /** One line per distinct reason, not one per keystroke. */
    private void trace(String reason) {
        if (reason.equals(lastReason)) return;
        lastReason = reason;
        DexLog.step("osk", reason);
    }

    private String lastReason;

    /**
     * A field we can write to. {@code isEditable} alone is too narrow: TextView
     * only sets it for an EDITABLE buffer, and provider-backed editors often
     * never set it at all while still answering SET_TEXT perfectly well.
     */
    private static boolean editable(AccessibilityNodeInfo node) {
        if (node.isEditable()) return true;
        return node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    }

    /** Bounded breadth-first hunt for the focused editable. */
    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c != null) queue.add(c);
        }
        int budget = 400;
        AccessibilityNodeInfo found = null;
        while (!queue.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isFocused() && editable(n)) {
                found = n;
                break;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
            recycle(n);
        }
        for (AccessibilityNodeInfo n : queue) recycle(n);
        return found;
    }

    /** No-op from API 33, and throws on a double free below it. */
    private static void recycle(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT >= 33) return;
        try {
            node.recycle();
        } catch (Exception ignored) {
        }
    }

    /**
     * Run {@code work} against the focused field on the keys thread, and say
     * once — not once per keystroke — when there is nothing to type into.
     */
    private void onField(java.util.function.Consumer<AccessibilityNodeInfo> work) {
        if (ipc == null) return;
        ipc.post(() -> {
            AccessibilityNodeInfo node = target();
            if (node == null) return;      // target() has already said why
            int key = node.hashCode();
            if (key != lastField) {
                lastField = key;
                lastReason = null;
                DexLog.step("osk", "typing into " + node.getClassName()
                        + " in " + node.getPackageName());
            }
            try {
                work.accept(node);
            } catch (Exception e) {
                DexLog.warn("osk", "the field refused the edit", e);
            } finally {
                recycle(node);
            }
        });
    }

    private int lastField;

    /**
     * Put {@code s} in at the caret.
     *
     * <p>SET_TEXT replaces the whole field — there is no insert action — so the
     * new value is spliced here and the caret put back afterwards. Appending
     * instead (which is what the web viewer does) would make it impossible to
     * correct a typo anywhere but the end of the line.
     */
    private void insert(String s) {
        onField(node -> {
            if (!splittable(node)) return;
            String base = text(node);
            int[] sel = selection(node, base);
            write(node, base.substring(0, sel[0]) + s + base.substring(sel[1]),
                    sel[0] + s.length());
        });
    }

    /**
     * Backspace: the selection if there is one, otherwise the character before
     * the caret — a whole code point, so deleting an emoji does not leave half
     * a surrogate pair behind and write invalid text back into the field.
     */
    private void backspace() {
        onField(node -> {
            if (!splittable(node)) return;
            String base = text(node);
            int[] sel = selection(node, base);
            int start = sel[0];
            int end = sel[1];
            if (start == end) {
                if (start == 0) return;
                start -= Character.charCount(base.codePointBefore(start));
            }
            write(node, base.substring(0, start) + base.substring(end), start);
        });
    }

    /**
     * Whether this field may be rewritten wholesale.
     *
     * <p>SET_TEXT is a replace, not an insert, so it can only be built on top
     * of text we can read back faithfully — and two kinds of field cannot be.
     * A password reports its bullets, and any field with a display transform
     * (all-caps, a card-number mask) reports the transformed form; splicing
     * either and writing it back would replace the user's real content with
     * the mask. A field at the parcel's text ceiling has already been truncated
     * on the way to us, so rewriting it would cut the rest away.
     */
    private boolean splittable(AccessibilityNodeInfo node) {
        if (node.isPassword()) {
            DexLog.step("osk", "this field hides what it holds — type on the phone instead");
            return false;
        }
        CharSequence raw = node.getText();
        if (raw != null && raw.length() > 20000) {
            DexLog.step("osk", "this field holds too much text to edit from here");
            return false;
        }
        return true;
    }

    /**
     * Enter is the field's own action — search, send, next — where the platform
     * exposes one, and a newline where it does not.
     *
     * <p>ACTION_IME_ENTER is API 30, and below that nothing can ask a field to
     * submit itself. A newline is the honest fallback: in a multi-line field it
     * is exactly right, and a single-line one drops it, which is the same
     * nothing a refused action would have been.
     */
    private void enter() {
        onField(node -> {
            if (Build.VERSION.SDK_INT >= 30 && node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                return;
            }
            // Below API 30 nothing can ask a field to submit itself, so a
            // newline is all that is left: exactly right in a multi-line
            // field, and dropped by a single-line one, which is the same
            // nothing a refused action would have been.
            if (!splittable(node)) return;
            String base = text(node);
            int[] sel = selection(node, base);
            write(node, base.substring(0, sel[0]) + "\n" + base.substring(sel[1]), sel[0] + 1);
        });
    }

    /**
     * Arrows, walked by the platform's own granularity traversal rather than
     * by arithmetic on the text.
     *
     * <p>It is the field that knows where its lines break — a wrapped
     * paragraph has visual lines that no count of newlines can find — and this
     * is also the path that puts the caret somewhere without waking the
     * selection toolbar, which SET_SELECTION does on every call.
     */
    private void move(int granularity, boolean forward) {
        onField(node -> {
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    granularity);
            args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
                    false);
            node.performAction(forward
                            ? AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                            : AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                    args);
        });
    }

    /** Home and End: the ends of the line the caret is on. */
    private void caretTo(boolean end) {
        onField(node -> {
            String base = text(node);
            int[] sel = selection(node, base);
            int at = end ? sel[1] : sel[0];
            int where;
            if (end) {
                int brk = base.indexOf('\n', at);
                where = brk < 0 ? base.length() : brk;
            } else {
                where = base.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
            }
            caret(node, where);
        });
    }

    private void global(int action) {
        AccessibilityService service = CaptionService.live();
        if (service == null) return;
        main.post(() -> {
            try {
                service.performGlobalAction(action);
            } catch (Exception e) {
                DexLog.warn("osk", "global action refused", e);
            }
        });
    }

    /**
     * The field's real text. {@code getText} on an EMPTY field returns the hint
     * on many builds, so typing into one would otherwise begin by appending to
     * the placeholder — which reads as the keyboard inventing words.
     */
    private static String text(AccessibilityNodeInfo node) {
        CharSequence raw = node.getText();
        if (raw == null) return "";
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText()) return "";
        CharSequence hint = Build.VERSION.SDK_INT >= 26 ? node.getHintText() : null;
        if (hint != null && hint.length() > 0 && hint.toString().contentEquals(raw)) return "";
        return raw.toString();
    }

    /** Caret as {start, end}, clamped — the platform reports -1 for "no selection". */
    private static int[] selection(AccessibilityNodeInfo node, String base) {
        int start = node.getTextSelectionStart();
        int end = node.getTextSelectionEnd();
        if (start < 0 || end < 0) {
            start = base.length();
            end = base.length();
        }
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        start = Math.max(0, Math.min(start, base.length()));
        end = Math.max(start, Math.min(end, base.length()));
        return new int[]{start, end};
    }

    /**
     * Replace the field's text and put the caret at {@code at}.
     *
     * <p>The caret is only set when it does not belong at the end: SET_TEXT
     * already parks it there, and every SET_SELECTION pops the field's
     * copy/paste toolbar — once per keystroke would make the bubble flash
     * over the text the whole time someone is typing.
     *
     * <p>A field with a length limit silently truncates the write, which would
     * then leave the caret past the end and the selection refused, so the
     * value is clamped to the limit first.
     */
    private void write(AccessibilityNodeInfo node, String value, int at) {
        int max = node.getMaxTextLength();
        if (max >= 0 && value.length() > max) value = value.substring(0, max);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            DexLog.step("osk", "the focused field would not take the text");
            return;
        }
        if (at >= value.length()) return;
        caret(node, Math.max(0, at));
    }

    /**
     * Put the caret at {@code at}, on a freshly read node: SET_TEXT rebuilds
     * the field, and a stale node answers SET_SELECTION against the length it
     * saw before the write.
     */
    private void caret(AccessibilityNodeInfo written, int at) {
        AccessibilityNodeInfo node = target();
        AccessibilityNodeInfo on = node != null ? node : written;
        try {
            int where = Math.max(0, Math.min(at, text(on).length()));
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, where);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, where);
            on.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        } finally {
            if (node != null) recycle(node);
        }
    }

    /**
     * Put the board back on top.
     *
     * <p>Among windows of one type the newest is the highest, so any panel of
     * ours that opens later — the app drawer above all, which is the surface
     * people most want to type into — lands over the keyboard and buries it.
     * Re-adding the same window is what lifts it back, and it is why the
     * drawer calls this the moment it is up.
     */
    void raise() {
        if (panel == null) return;
        try {
            wm.removeViewImmediate(panel);
            wm.addView(panel, lp);
        } catch (Exception e) {
            DexLog.warn("osk", "could not raise the keyboard", e);
        }
    }

    /** Take the board down and stop its thread. For the activity's onDestroy. */
    void release() {
        hide();
        if (keysThread != null) {
            keysThread.quitSafely();
            keysThread = null;
            ipc = null;
        }
    }
}
