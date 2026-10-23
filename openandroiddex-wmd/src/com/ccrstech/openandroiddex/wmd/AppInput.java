package com.ccrstech.openandroiddex.wmd;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
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
 * Keys arrive as Android key codes with their modifiers; typed characters as
 * text, which goes in as the key presses that type them where the virtual
 * keyboard's map has them, and as one ACTION_MULTIPLE event — a string of
 * characters, which text fields take whole — where it does not.
 *
 * <p>Every event is aimed at the window's display by id: an event without one
 * goes to whichever display has focus, which may be the phone's own.
 */
final class AppInput {

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: queue it and return. */
    private static final int INJECT_ASYNC = 0;
    /** The only device id an injected mouse needs: a consistent one. */
    private static final int MOUSE_DEVICE = 0;

    private static Object inputManager;

    private final KeyCharacterMap chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private final int display;

    // the mouse, as Android last heard of it
    private int buttons;
    private long downTime;
    private int lastX;
    private int lastY;

    /** When each held key went down: a key's events carry its own down time. */
    private final Map<Integer, Long> keysDown = new HashMap<>();
    private long refusedSaidAt;

    AppInput(int display) {
        this.display = display;
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

    /** Everything let go: the window lost the keyboard, or the computer went away. */
    synchronized void releaseAll() {
        if (buttons != 0) pointer(lastX, lastY, 0, 0);
        for (Integer code : keysDown.keySet().toArray(new Integer[0])) key(false, code, 0, 0);
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

    /** A key, by Android key code, with Android's meta bits and the computer's own repeat count. */
    synchronized void key(boolean down, int code, int repeat, int meta) {
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
        inject(e);
    }

    /** A key pressed and let go: Back from the window's own button, say. */
    synchronized void tap(int code) {
        key(true, code, 0, 0);
        key(false, code, 0, 0);
    }

    /** Characters typed on the computer, in whatever layout it has. */
    synchronized void text(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            char[] one = s.substring(i, i + n).toCharArray();
            i += n;
            long now = SystemClock.uptimeMillis();
            KeyEvent[] presses = n == 1 ? chars.getEvents(one) : null;
            if (presses != null && presses.length > 0) {
                for (KeyEvent p : presses) {
                    KeyEvent e = KeyEvent.changeTimeRepeat(p, now, 0);
                    e.setSource(InputDevice.SOURCE_KEYBOARD);
                    inject(e);
                }
            } else {
                KeyEvent e = new KeyEvent(now, new String(one), KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
                e.setSource(InputDevice.SOURCE_KEYBOARD);
                inject(e);
            }
        }
    }

    // ── into the input system ──

    private void inject(InputEvent e) {
        try {
            Refl.callSig(e, "setDisplayId", new Class<?>[]{int.class}, display);
            Refl.callSig(manager(), "injectInputEvent", new Class<?>[]{InputEvent.class, int.class}, e, INJECT_ASYNC);
        } catch (Refl.WmError err) {
            long now = SystemClock.uptimeMillis();
            if (now - refusedSaidAt > 10_000) {
                refusedSaidAt = now;
                AppServer.log("input refused on display " + display + " (" + err.getMessage() + ")");
            }
        }
    }
}
