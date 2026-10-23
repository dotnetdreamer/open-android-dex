package com.ccrstech.openandroiddex.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * One Linux window's pixels on the desktop, and its input: the view inside
 * a {@link LinuxWindowActivity}, or inside the overlay a menu is shown in.
 *
 * <p>{@link LinuxWinGl} draws into its surface. The view says where it is on
 * the display whenever that changes — it is where the X window has to be —
 * and turns what Android delivers into X input at the same coordinates: the
 * mouse's raw position is already a position on the X screen, since the two
 * are the same size and the X window sits exactly under this view.
 *
 * <p>Keys go by meaning, not by key: a character the computer's own layout
 * made is typed as that character, and a shortcut is the key with its
 * modifiers — the rules the desktop window's keysym.ts follows for a browser.
 */
@SuppressLint("ViewConstructor")
final class LinuxWinView extends SurfaceView implements SurfaceHolder.Callback {

    private final LinuxWindows windows;
    final LinuxWm wm;
    final LinuxWm.Win win;
    private final boolean popup;
    final LinuxWinGl.Target target;

    private int buttons;
    private float scrollV;
    private float scrollH;
    /** A dead key waiting for the letter it goes on (KeyCharacterMap's accent), or 0. */
    private int deadAccent;
    private final int[] loc = new int[2];
    private int placedX = Integer.MIN_VALUE;
    private int placedY;
    private int placedW;
    private int placedH;

    /**
     * Where the X window is told this view is, four times a second: a window
     * dragged by its caption moves without a layout pass, and a menu opened
     * from the keyboard must open where the window now is. Clicks do not wait
     * for this — each one checks first ({@link #originFrom}).
     */
    private final Runnable track = new Runnable() {
        @Override
        public void run() {
            place();
            postDelayed(this, 250);
        }
    };

    LinuxWinView(Context ctx, LinuxWindows windows, LinuxWm wm, LinuxWm.Win win, boolean popup) {
        super(ctx);
        this.windows = windows;
        this.wm = wm;
        this.win = win;
        this.popup = popup;
        this.target = new LinuxWinGl.Target(wm, win);
        getHolder().addCallback(this);
        if (popup) {
            // A menu's overlay is see-through around its surface; on top, the
            // surface is drawn over the window rather than through a hole in it.
            setZOrderOnTop(true);
        } else {
            setFocusable(true);
            setFocusableInTouchMode(true);
        }
    }

    // ── the surface ──

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        target.surface = holder.getSurface();
        LinuxWinGl.get().attach(target);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        LinuxWinGl.get().resized(target, width, height);
        place();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        LinuxWinGl.get().detach(target);
        target.surface = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        windows.register(this);
        if (!popup) post(track);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(track);
        windows.unregister(this);
        super.onDetachedFromWindow();
    }

    /** Tell the window manager where this view is now, if that changed. */
    void place() {
        if (popup || !isAttachedToWindow()) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        getLocationOnScreen(loc);
        placeAt(loc[0], loc[1], w, h);
    }

    private void placeAt(int x, int y, int w, int h) {
        if (x == placedX && y == placedY && w == placedW && h == placedH) return;
        placedX = x;
        placedY = y;
        placedW = w;
        placedH = h;
        windows.placed(win, x, y, w, h);
    }

    /**
     * The view's place on the screen as this event has it — its raw position
     * less its position in the view — moving the X window there first if it
     * is not there yet, so the click lands where it was aimed.
     */
    private void originFrom(MotionEvent e) {
        if (popup) return;
        int x = Math.round(e.getRawX() - e.getX());
        int y = Math.round(e.getRawY() - e.getY());
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) placeAt(x, y, w, h);
    }

    // ── the pointer ──

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        originFrom(e);
        int action = e.getActionMasked();
        int next = buttonsOf(e, action);
        int x = Math.round(e.getRawX());
        int y = Math.round(e.getRawY());
        if (action == MotionEvent.ACTION_DOWN && !popup && !hasFocus()) requestFocus();
        windows.pointerInWindow(this);
        wm.pointer(popup ? null : win, x, y, next);
        buttons = next;
        return true;
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
            originFrom(e);
            wm.pointer(popup ? null : win, Math.round(e.getRawX()), Math.round(e.getRawY()), buttons);
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(e);
        originFrom(e);
        int x = Math.round(e.getRawX());
        int y = Math.round(e.getRawY());
        // Android's up is positive, X's up is button 4: the steps are negated.
        scrollV += e.getAxisValue(MotionEvent.AXIS_VSCROLL);
        scrollH += e.getAxisValue(MotionEvent.AXIS_HSCROLL);
        int v = (int) scrollV;
        int h = (int) scrollH;
        scrollV -= v;
        scrollH -= h;
        if (v != 0) wm.wheel(popup ? null : win, x, y, -v, false);
        if (h != 0) wm.wheel(popup ? null : win, x, y, h, true);
        return true;
    }

    /** X's three buttons as bits — left, middle, right — from what Android says is down. */
    private static int buttonsOf(MotionEvent e, int action) {
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE;
        int tool = e.getToolType(0);
        if (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_STYLUS) {
            // A finger is the left button for as long as it is down.
            return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || !down ? 0 : 1;
        }
        int state = e.getButtonState();
        int b = 0;
        if ((state & MotionEvent.BUTTON_PRIMARY) != 0) b |= 1;
        if ((state & MotionEvent.BUTTON_TERTIARY) != 0) b |= 2;
        if ((state & MotionEvent.BUTTON_SECONDARY) != 0) b |= 4;
        // An injector that reports a press with no button means the left one.
        if (b == 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) b = 1;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) b = 0;
        return b;
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent e, int pointerIndex) {
        PointerIcon icon = windows.pointerIcon();
        return icon != null ? icon : super.onResolvePointerIcon(e, pointerIndex);
    }

    // ── keys ──

    /** One key event for the window's app; true when it is the app's, which is nearly every key. */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false; // the phone's
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
                // They stay here: their effect is already in the characters.
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true; // nothing to go back from in a desktop window
            case KeyEvent.KEYCODE_PASTE:
            case KeyEvent.KEYCODE_COPY:
            case KeyEvent.KEYCODE_CUT:
                // What scrcpy sends for a computer's Cmd+V, Cmd+C and Cmd+X.
                // X has no keys every app takes for them, so they become what
                // the app takes: Ctrl+V, C and X, and in a terminal — where
                // Ctrl+C is an interrupt — Ctrl+Shift+V, C and X. A paste first
                // hands Linux the clipboard scrcpy has just set.
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    int letter = code == KeyEvent.KEYCODE_PASTE ? 'v' : code == KeyEvent.KEYCODE_COPY ? 'c' : 'x';
                    if (code == KeyEvent.KEYCODE_PASTE) windows.clipboardToLinux(wm, getContext());
                    boolean terminal = isTerminal(win);
                    wm.key(X11Keys.XK_CONTROL_L, true, false);
                    if (terminal) wm.key(X11Keys.XK_SHIFT_L, true, false);
                    wm.key(letter, true, false);
                    wm.key(letter, false, false);
                    if (terminal) wm.key(X11Keys.XK_SHIFT_L, false, false);
                    wm.key(X11Keys.XK_CONTROL_L, false, false);
                }
                return true;
            default:
        }
        int action = e.getAction();
        if (action == KeyEvent.ACTION_MULTIPLE && code == KeyEvent.KEYCODE_UNKNOWN) {
            type(e.getCharacters());
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return true;
        boolean down = action == KeyEvent.ACTION_DOWN;
        int named = namedKeysym(code);
        if (named != 0) {
            wm.key(named, down, false);
            return true;
        }
        int meta = e.getMetaState();
        boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
        boolean sup = (meta & KeyEvent.META_META_ON) != 0;
        boolean leftAlt = (meta & KeyEvent.META_ALT_LEFT_ON) != 0;
        int ch = e.getUnicodeChar(meta);
        if (ctrl || sup || (leftAlt && ch == e.getUnicodeChar(meta & ~KeyEvent.META_ALT_MASK))) {
            // A shortcut: the key itself, unshifted, under the modifiers held —
            // Ctrl+C is C whatever the layout put on that key.
            int base = e.getKeyCharacterMap().get(code, 0);
            if (base > 0) wm.key(X11Keys.unicodeKeysym(Character.toLowerCase(base)), down, false);
            return true;
        }
        if (!down) return true; // a typed character went down and up at once
        if ((ch & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            deadAccent = ch & KeyCharacterMap.COMBINING_ACCENT_MASK;
            return true;
        }
        if (ch == 0) return true;
        if (deadAccent != 0) {
            int composed = KeyCharacterMap.getDeadChar(deadAccent, ch);
            if (composed != 0) {
                ch = composed;
            } else {
                typeCodePoint(deadAccent);
            }
            deadAccent = 0;
        }
        typeCodePoint(ch);
        return true;
    }

    /** A terminal, by its window class: Ctrl+C is an interrupt there, and copying is Ctrl+Shift+C. */
    static boolean isTerminal(LinuxWm.Win w) {
        String c = (w.wmClass + " " + w.wmInstance).toLowerCase(java.util.Locale.ROOT);
        return c.contains("term") || c.contains("konsole") || c.contains("kitty") || c.contains("alacritty")
                || c.contains("terminator") || c.contains("tilix");
    }

    private void type(CharSequence text) {
        if (text == null) return;
        text.toString().codePoints().forEach(this::typeCodePoint);
    }

    private void typeCodePoint(int cp) {
        int ks = X11Keys.unicodeKeysym(cp);
        boolean named = ks >= 0xff00 && ks <= 0xffff;
        wm.key(ks, true, !named);
        if (named) wm.key(ks, false, false);
    }

    /** Keys that are not characters, by Android key code; 0 for one that types something. */
    static int namedKeysym(int code) {
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) {
            return 0xffbe + (code - KeyEvent.KEYCODE_F1);
        }
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0xff8d;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_DEL: return 0xff08;          // Backspace
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;  // Delete
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_INSERT: return 0xff63;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_SYSRQ: return 0xff61;        // Print
            case KeyEvent.KEYCODE_BREAK: return 0xff13;        // Pause
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 0xff14;
            case KeyEvent.KEYCODE_MENU: return 0xff67;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return X11Keys.XK_SHIFT_L;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return X11Keys.XK_SHIFT_R;
            case KeyEvent.KEYCODE_CTRL_LEFT: return X11Keys.XK_CONTROL_L;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return X11Keys.XK_CONTROL_R;
            case KeyEvent.KEYCODE_ALT_LEFT: return X11Keys.XK_ALT_L;
            // AltGr on most layouts: typed characters let it go around them.
            case KeyEvent.KEYCODE_ALT_RIGHT: return X11Keys.XK_ALT_R;
            case KeyEvent.KEYCODE_META_LEFT: return X11Keys.XK_SUPER_L;
            case KeyEvent.KEYCODE_META_RIGHT: return X11Keys.XK_SUPER_R;
            default: return 0;
        }
    }

    // ── an on-screen keyboard, or any input method ──

    @Override
    public boolean onCheckIsTextEditor() {
        return !popup;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        if (popup) return null;
        // TYPE_NULL: a keyboard that can should send keys, not compose text.
        out.inputType = InputType.TYPE_NULL;
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                type(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < before; i++) typeCodePoint(0x08);
                for (int i = 0; i < after; i++) typeCodePoint(0x7f);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return key(event);
            }
        };
    }
}
