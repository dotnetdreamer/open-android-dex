package com.ccrstech.openandroiddex.wmd;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The computer's mouse and keyboard, put into one app window's display.
 *
 * <p>Injected events, as the desktop's own stream injected them
 * (doc/apps-on-pc.md, "Input"): the pointer the person sees is the computer's
 * own, drawn by the computer at its own rate, and the phone is told where it is
 * and what is pressed. Injection needs INJECT_EVENTS, which uid 2000 holds and
 * no app does.
 *
 * <p>The mouse arrives as RFB's does — where it is and which buttons are down
 * now — and is turned into Android's sequence here, the one a real mouse makes:
 * hovering, then DOWN and BUTTON_PRESS for the first button, BUTTON_PRESS for
 * another, MOVE while any is held, BUTTON_RELEASE for each, UP with the last.
 * Keys arrive as Android key codes with their modifiers, and Ctrl, Alt and
 * Shift are told to the app as keys of their own around them; typed characters
 * as text, which goes in as the key presses that type them where the virtual
 * keyboard's map has them — an accented letter as its accent's dead key and
 * the letter — and is pasted where it does not. Android refuses a string as a
 * key event (ACTION_MULTIPLE) from anyone but an input method.
 *
 * <p>Every event is aimed at the window's display by id: an event without one
 * goes to whichever display has focus, which may be the phone's own.
 */
final class AppInput {

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: queue it and return. */
    private static final int INJECT_ASYNC = 0;
    /** INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH: return once the app has handled it. */
    private static final int INJECT_WAIT_FOR_FINISH = 2;
    /** The only device id an injected mouse needs: a consistent one. */
    private static final int MOUSE_DEVICE = 0;
    /** The accents the virtual keyboard has dead keys for: grave, acute, circumflex, tilde, diaeresis. */
    private static final String DEAD_KEYS = "̀́̂̃̈";

    private static Object inputManager;

    private final KeyCharacterMap chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private final int display;
    /** The phone's clipboard, which lends itself to paste what no key types; null where the shell has none. */
    private final AppClipboard clipboard;

    // the mouse, as Android last heard of it
    private int buttons;
    private long downTime;
    private int lastX;
    private int lastY;
    /** The press carries a drop in (AppWindow.dropIn): it is the drop's to let go, where the drop goes. */
    private boolean carrying;

    /** When each held key went down: a key's events carry its own down time. */
    private final Map<Integer, Long> keysDown = new HashMap<>();
    /** The modifier keys pressed here for the keys held with them, in the order pressed (see {@link #key}). */
    private final List<Integer> modifiersDown = new ArrayList<>();
    private long refusedSaidAt;

    AppInput(int display, AppClipboard clipboard) {
        this.display = display;
        this.clipboard = clipboard;
        manager();
    }

    private static synchronized Object manager() {
        if (inputManager == null) {
            IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                    "getService", new Class<?>[]{String.class}, "input");
            if (b == null) throw new Refl.WmError("no input service");
            inputManager = Refl.callStatic(Refl.cls("android.hardware.input.IInputManager$Stub"),
                    "asInterface", new Class<?>[]{IBinder.class}, b);
        }
        return inputManager;
    }

    // ── the mouse ──

    /**
     * The pointer is at ({@code x}, {@code y}) in the display's pixels with
     * {@code want} (MotionEvent.BUTTON_*) held, and {@code meta} the keyboard's
     * modifiers — Shift-click extends a selection only if the click says Shift.
     */
    synchronized void pointer(int x, int y, int want, int meta) {
        long now = SystemClock.uptimeMillis();
        boolean moved = x != lastX || y != lastY;
        lastX = x;
        lastY = y;
        if (want == buttons) {
            if (buttons == 0) {
                motion(MotionEvent.ACTION_HOVER_MOVE, now, now, x, y, 0, 0, meta);
            } else if (moved) {
                motion(MotionEvent.ACTION_MOVE, downTime, now, x, y, buttons, 0, meta);
            }
            return;
        }
        if (moved && buttons != 0) motion(MotionEvent.ACTION_MOVE, downTime, now, x, y, buttons, 0, meta);
        // Presses first, lowest button first, then releases — a change of two
        // at once is rare, and this order never lets the count reach zero by
        // accident in the middle of it.
        for (int bit = 1; bit <= 0x10; bit <<= 1) {
            if ((want & bit) == 0 || (buttons & bit) != 0) continue;
            if (buttons == 0) {
                downTime = now;
                motion(MotionEvent.ACTION_DOWN, downTime, now, x, y, buttons | bit, 0, meta);
            }
            buttons |= bit;
            motion(MotionEvent.ACTION_BUTTON_PRESS, downTime, now, x, y, buttons, bit, meta);
        }
        for (int bit = 1; bit <= 0x10; bit <<= 1) {
            if ((want & bit) != 0 || (buttons & bit) == 0) continue;
            buttons &= ~bit;
            motion(MotionEvent.ACTION_BUTTON_RELEASE, downTime, now, x, y, buttons, bit, meta);
            if (buttons == 0) {
                motion(MotionEvent.ACTION_UP, downTime, now, x, y, 0, 0, meta);
                // Back to hovering, so whatever is under the pointer knows it is.
                motion(MotionEvent.ACTION_HOVER_MOVE, now, now, x, y, 0, 0, meta);
            }
        }
    }

    /**
     * The press under way abandoned, as if it had never been: what it went
     * down on sees it cancelled, and is not clicked. A press meant for the
     * launcher's corner that went to the app, or one a drop in left held
     * (AppWindow.dropIn).
     */
    synchronized void cancel() {
        if (buttons == 0) return;
        long now = SystemClock.uptimeMillis();
        buttons = 0;
        motion(MotionEvent.ACTION_CANCEL, downTime, now, lastX, lastY, 0, 0, 0);
    }

    /**
     * A drop in is carried from now on (AppWindow.dropIn): its press goes from
     * the display's corner to where the computer let go, and is let go there.
     * Nothing else lets go of it meanwhile — the window losing the keyboard on
     * the computer would drop it short, and the carry's next step press the app.
     */
    synchronized void carry() {
        carrying = true;
    }

    /** The drop in is done. A press it left held, which only a carry that stopped short does, is cancelled, never let go on the app. */
    synchronized void carried() {
        carrying = false;
        cancel();
    }

    /** The pointer left the window: whatever was under it stops showing it hovered. */
    synchronized void left() {
        if (buttons != 0) return;
        long now = SystemClock.uptimeMillis();
        motion(MotionEvent.ACTION_HOVER_EXIT, now, now, lastX, lastY, 0, 0, 0);
    }

    /** The wheel, in notches (fractions for a touchpad): {@code v} up is positive, {@code h} right. */
    synchronized void scroll(int x, int y, float h, float v, int meta) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerCoords c = coords(x, y, 0f);
        c.setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        c.setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{mouse()}, new MotionEvent.PointerCoords[]{c},
                meta, buttons, 1f, 1f, MOUSE_DEVICE, 0, InputDevice.SOURCE_MOUSE, 0);
        inject(e);
        e.recycle();
    }

    /** Everything let go: the window lost the keyboard, or the computer went away. All but a press carrying a drop in ({@link #carry}). */
    synchronized void releaseAll() {
        if (buttons != 0 && !carrying) pointer(lastX, lastY, 0, 0);
        // A key's let-go takes the modifiers pressed here with it: those are not let go twice.
        for (Integer code : keysDown.keySet().toArray(new Integer[0])) {
            if (keysDown.containsKey(code)) key(false, code, 0, 0);
        }
    }

    private void motion(int action, long down, long now, int x, int y, int state, int actionButton, int meta) {
        boolean pressed = state != 0 && action != MotionEvent.ACTION_UP;
        MotionEvent e = MotionEvent.obtain(down, now, action, 1,
                new MotionEvent.PointerProperties[]{mouse()},
                new MotionEvent.PointerCoords[]{coords(x, y, pressed ? 1f : 0f)},
                meta, state, 1f, 1f, MOUSE_DEVICE, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) Refl.callSig(e, "setActionButton", new Class<?>[]{int.class}, actionButton);
        inject(e);
        e.recycle();
    }

    private static MotionEvent.PointerProperties mouse() {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        return p;
    }

    private static MotionEvent.PointerCoords coords(int x, int y, float pressure) {
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = pressure;
        c.size = 1f;
        return c;
    }

    // ── the keyboard ──

    /** A modifier an app is told of as a key of its own: its meta bits, and its two keys. */
    private static final class Modifier {
        final int on;
        final int left;
        final int right;
        final int leftKey;
        final int rightKey;

        Modifier(int on, int left, int right, int leftKey, int rightKey) {
            this.on = on;
            this.left = left;
            this.right = right;
            this.leftKey = leftKey;
            this.rightKey = rightKey;
        }

        /** The key a meta says is held: the right-hand one only where it says that one alone. */
        int key(int meta) {
            return (meta & right) != 0 && (meta & left) == 0 ? rightKey : leftKey;
        }

        /** Its bits in the meta of events while {@code key} is held. */
        int bits(int key) {
            return on | (key == rightKey ? right : left);
        }
    }

    /**
     * Ctrl, Alt and Shift, in the order a shortcut holds them. Not the Meta
     * key: the phone acts on that one itself (pressed and let go alone, it
     * opens the app drawer or the assistant), and the computer keeps almost
     * every shortcut with its own Windows key. It stays in the meta only.
     */
    private static final Modifier[] MODIFIERS = {
            new Modifier(KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON,
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT),
            new Modifier(KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON,
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT),
            new Modifier(KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_RIGHT_ON,
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT),
    };
    private static final int MODIFIER_BITS = KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_SHIFT_MASK;

    /**
     * A key, by Android key code, with Android's meta bits and the computer's
     * own repeat count.
     *
     * <p>Ctrl, Alt and Shift in the meta go to the app as keys of their own
     * too, as a keyboard's do: Ctrl+A is Ctrl down, A down, A up, Ctrl up.
     * Most of Android reads what is held from the meta alone (a text field's
     * select-all does), but an app that keeps its own count of the keys held
     * does not: a remote desktop passing keys on to a computer, as
     * TeamViewer's does, sent that computer an A alone. They are pressed with
     * the key they came with, never on their own (the computer sends no
     * modifier keys), so Alt let go alone never opens the menus at the far end
     * of such an app, nor does Shift alone switch an input method's language.
     * They are let go before a key that no longer has them, and with the last
     * key held with them.
     */
    synchronized void key(boolean down, int code, int repeat, int meta) {
        // The person's own paste: it pastes what they copied, never text lent
        // to an earlier paste of characters — and is handled before the next
        // such paste can lend the clipboard again.
        boolean paste = code == KeyEvent.KEYCODE_PASTE
                || (code == KeyEvent.KEYCODE_V && (meta & KeyEvent.META_CTRL_ON) != 0);
        if (paste && down && clipboard != null) clipboard.giveBackNow();
        if (KeyEvent.isModifierKey(code)) {
            // A modifier key itself: one of ours let go by releaseAll, or one a
            // computer sent as a key (none does), which goes as it came.
            modifiersDown.remove((Integer) code);
            send(down, code, repeat, meta, INJECT_ASYNC);
            return;
        }
        releaseModifiers(meta, false);
        if (down) pressModifiers(meta);
        send(down, code, repeat, meta, paste ? INJECT_WAIT_FOR_FINISH : INJECT_ASYNC);
        if (!down && !keyHeld()) releaseModifiers(meta, true);
    }

    /** Each modifier {@code meta} holds that the app has not been told is held: its key, down. */
    private void pressModifiers(int meta) {
        for (Modifier m : MODIFIERS) {
            if ((meta & m.on) == 0 || keysDown.containsKey(m.leftKey) || keysDown.containsKey(m.rightKey)) continue;
            int code = m.key(meta);
            modifiersDown.add(code);
            send(true, code, 0, (meta & ~MODIFIER_BITS) | heldBits(0) | m.bits(code), INJECT_ASYNC);
        }
    }

    /** The modifiers pressed here that {@code meta} no longer holds, or all of them: their keys, up, the last pressed first. */
    private void releaseModifiers(int meta, boolean all) {
        for (int i = modifiersDown.size() - 1; i >= 0; i--) {
            int code = modifiersDown.get(i);
            Modifier m = modifier(code);
            if (!all && (meta & m.on) != 0) continue;
            modifiersDown.remove(i);
            send(false, code, 0, (meta & ~MODIFIER_BITS) | heldBits(code), INJECT_ASYNC);
        }
    }

    private static Modifier modifier(int code) {
        for (Modifier m : MODIFIERS) {
            if (code == m.leftKey || code == m.rightKey) return m;
        }
        throw new IllegalArgumentException("not a modifier: " + code);
    }

    /** The meta bits of the modifier keys held now, all but {@code except}. */
    private int heldBits(int except) {
        int bits = 0;
        for (Modifier m : MODIFIERS) {
            if (m.leftKey != except && keysDown.containsKey(m.leftKey)) bits |= m.bits(m.leftKey);
            if (m.rightKey != except && keysDown.containsKey(m.rightKey)) bits |= m.bits(m.rightKey);
        }
        return bits;
    }

    /** Whether a key that is no modifier is held. */
    private boolean keyHeld() {
        for (int code : keysDown.keySet()) {
            if (!KeyEvent.isModifierKey(code)) return true;
        }
        return false;
    }

    /** One key's event: each of a key's events carries the time it went down. */
    private void send(boolean down, int code, int repeat, int meta, int mode) {
        long now = SystemClock.uptimeMillis();
        long downAt;
        if (down) {
            Long held = keysDown.get(code);
            downAt = held != null && repeat > 0 ? held : now;
            keysDown.put(code, downAt);
        } else {
            Long held = keysDown.remove(code);
            downAt = held != null ? held : now;
        }
        KeyEvent e = new KeyEvent(downAt, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code,
                down ? repeat : 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        inject(e, mode);
    }

    /** A key pressed and let go: Back from the window's own button, say. */
    synchronized void tap(int code) {
        key(true, code, 0, 0);
        key(false, code, 0, 0);
    }

    /**
     * Characters typed on the computer, in whatever layout it has: each as the
     * keys that type it on the virtual keyboard, and what no key types —
     * another script, an emoji, € — pasted, a run of them at a time.
     */
    synchronized void text(String s) {
        // Characters bring their own Shift, the virtual keyboard's, and none is typed with Ctrl or Alt held.
        releaseModifiers(0, true);
        StringBuilder untyped = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int n = Character.charCount(s.codePointAt(i));
            String one = s.substring(i, i + n);
            i += n;
            KeyEvent[] presses = presses(one);
            if (presses == null) {
                untyped.append(one);
                continue;
            }
            if (untyped.length() > 0) {
                paste(untyped.toString());
                untyped.setLength(0);
            }
            long now = SystemClock.uptimeMillis();
            for (KeyEvent p : presses) {
                KeyEvent e = KeyEvent.changeTimeRepeat(p, now, 0);
                e.setSource(InputDevice.SOURCE_KEYBOARD);
                inject(e);
            }
        }
        if (untyped.length() > 0) paste(untyped.toString());
    }

    /**
     * The key presses that type {@code one}, or null if none do: its own key,
     * or for a letter with one of {@link #DEAD_KEYS} the accent's dead key and
     * then the bare letter — é is the acute's key and E, as Option-E then E is
     * on a Mac.
     */
    private KeyEvent[] presses(String one) {
        KeyEvent[] direct = one.length() == 1 ? chars.getEvents(one.toCharArray()) : null;
        if (direct != null && direct.length > 0) return direct;
        String parts = Normalizer.normalize(one, Normalizer.Form.NFD);
        if (parts.length() != 2 || DEAD_KEYS.indexOf(parts.charAt(1)) < 0) return null;
        KeyEvent[] dead = chars.getEvents(new char[]{parts.charAt(1), parts.charAt(0)});
        return dead != null && dead.length > 0 ? dead : null;
    }

    /** Characters no key types, pasted: the phone's clipboard lends itself to one paste (AppClipboard.lend). */
    private void paste(String text) {
        if (clipboard == null) {
            AppServer.log("no clipboard here: " + text.codePointCount(0, text.length())
                    + " typed characters no key types were dropped");
            return;
        }
        clipboard.lend(text, () -> {
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                inject(new KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_PASTE, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), INJECT_WAIT_FOR_FINISH);
            }
        });
    }

    // ── into the input system ──

    private void inject(InputEvent e) {
        inject(e, INJECT_ASYNC);
    }

    private void inject(InputEvent e, int mode) {
        try {
            Refl.callSig(e, "setDisplayId", new Class<?>[]{int.class}, display);
            Refl.callSig(manager(), "injectInputEvent", new Class<?>[]{InputEvent.class, int.class}, e, mode);
        } catch (Refl.WmError err) {
            long now = SystemClock.uptimeMillis();
            if (now - refusedSaidAt > 10_000) {
                refusedSaidAt = now;
                AppServer.log("input refused on display " + display + " (" + err.getMessage() + ")");
            }
        }
    }
}
