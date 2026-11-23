package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Remote desktop and a locked phone: the PIN pad that screen sharing cannot
 * see, for the computer.
 *
 * <p>Android draws the lock screen's PIN pad, password field and pattern in
 * a secure window: black in screen sharing, in the control service's
 * screenshots, and on a Galaxy even in adb's own screenshot. The lock screen
 * before it (the clock, "Swipe to unlock") shows as it is. So while the pad
 * is up, the computer is told what the pad has and where, read through the
 * control service, as a screen reader reads it: the keys' places, how many
 * digits are in, and what the pad says ("Enter PIN", "Wrong PIN"). The
 * window draws them over the black picture, where a click is a tap on the
 * real key; nothing the person types is read back, only how many.
 *
 * <p>The computer's keyboard works on the pad too. An app cannot type keys
 * into another window, so the number keys, Backspace and Enter press the
 * pad's own keys (a click on the key, as a screen reader's double tap is),
 * which is the same thing a finger does. Typed while the lock screen shows
 * without its pad, the first key brings the pad up (a swipe up, as a person
 * does) and goes in once it is there; typed while the phone sleeps, the
 * screen comes on first.
 *
 * <p>Only the lock screen's own window is touched (System UI's, in front):
 * never while a call or the camera shows over it, and never its Emergency
 * call button. Nothing here unlocks anything: the digits are the person's.
 */
final class PhoneLock {

    /** Nothing to draw: unlocked, or no pad up. */
    static final String NONE = "{\"t\":\"lock\",\"pad\":\"\"}";

    /** Whose window the lock screen is. */
    private static final String SYSTEM_UI = "com.android.systemui";
    /** Nodes looked at, at most, in one read: a lock screen has a few dozen. */
    private static final int MAX_NODES = 600;
    /** Keys typed before the pad was up wait this long for it. */
    private static final long PAD_WAIT_MS = 2500;
    private static final long RETRY_MS = 100;

    private PhoneLock() {
    }

    static boolean locked(Context ctx) {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /** What a keysym is on a PIN pad: "0" to "9", "del", "ok", or null. */
    static String keyFor(int keysym) {
        if (keysym >= '0' && keysym <= '9') return String.valueOf((char) keysym);
        if (keysym >= 0xffb0 && keysym <= 0xffb9) return String.valueOf((char) ('0' + keysym - 0xffb0));
        if (keysym == 0xff08 || keysym == 0xffff) return "del";
        if (keysym == 0xff0d || keysym == 0xff8d) return "ok";
        return null;
    }

    // ── what the pad has ──

    /** The pad's parts, as found in the lock screen's window. */
    static final class Pad {
        /** "pin", "password", "pattern", or "" for the lock screen without a pad. */
        String kind = "";
        final List<AccessibilityNodeInfo> digits = new ArrayList<>();
        final List<String> digitNames = new ArrayList<>();
        AccessibilityNodeInfo delete;
        AccessibilityNodeInfo ok;
        AccessibilityNodeInfo field;
        AccessibilityNodeInfo pattern;
        final List<AccessibilityNodeInfo> words = new ArrayList<>();

        AccessibilityNodeInfo digit(String d) {
            int i = digitNames.indexOf(d);
            return i < 0 ? null : digits.get(i);
        }
    }

    /** The lock screen's window, in front: null when it is not (unlocked, or a call over it). */
    private static AccessibilityNodeInfo lockWindow(AccessibilityService s) {
        AccessibilityNodeInfo root;
        try {
            root = s.getRootInActiveWindow();
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        return pkg != null && SYSTEM_UI.contentEquals(pkg) ? root : null;
    }

    static Pad scan(AccessibilityNodeInfo root) {
        Pad p = new Pad();
        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
        todo.add(root);
        int seen = 0;
        while (!todo.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = todo.poll();
            if (n == null) continue;
            if (n.isVisibleToUser()) look(p, n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        if (p.digits.size() >= 10) {
            p.kind = "pin";
        } else if (p.pattern != null) {
            p.kind = "pattern";
        } else if (p.field != null) {
            p.kind = "password";
        }
        return p;
    }

    private static void look(Pad p, AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        String idEnd = id == null ? "" : id.substring(id.indexOf('/') + 1).toLowerCase(Locale.ROOT);
        CharSequence descCs = n.getContentDescription();
        CharSequence textCs = n.getText();
        String desc = descCs == null ? "" : descCs.toString().trim();
        String text = textCs == null ? "" : textCs.toString().trim();
        CharSequence cls = n.getClassName();
        String className = cls == null ? "" : cls.toString();
        if (idEnd.contains("emergency")) return;
        if (n.isClickable()) {
            String digit = desc.matches("[0-9]") ? desc : text.matches("[0-9]") && desc.isEmpty() ? text : null;
            if (digit != null && !p.digitNames.contains(digit)) {
                p.digitNames.add(digit);
                p.digits.add(n);
                return;
            }
            if (idEnd.equals("delete_button") || idEnd.endsWith("delete_button")
                    || desc.equalsIgnoreCase("delete") || desc.equalsIgnoreCase("backspace")) {
                if (p.delete == null) p.delete = n;
                return;
            }
        }
        // OK: a Galaxy's is a label that takes clicks only once enough digits are in.
        if (idEnd.equals("key_enter") || idEnd.startsWith("key_enter")) {
            if (p.ok == null || (n.isClickable() && !p.ok.isClickable())) p.ok = n;
            return;
        }
        if (n.isEditable() && n.isPassword()) {
            if (p.field == null) p.field = n;
            return;
        }
        if (className.endsWith("LockPatternView") || idEnd.equals("lockpatternview") || idEnd.equals("lock_pattern_view")) {
            if (p.pattern == null) p.pattern = n;
            return;
        }
        // What the pad says: its own message lines ("Enter PIN", "Wrong PIN",
        // "Try again in 30 seconds"), not every label on the lock screen.
        if (!text.isEmpty() && (idEnd.contains("message") || idEnd.contains("help_text") || idEnd.contains("bouncer"))) {
            p.words.add(n);
        }
    }

    /**
     * The lock screen as the window draws it, as one {@code "lock"} message:
     * the pad's kind, the screen's size, and each key, the field and the
     * pad's words where they are on the screen. While the phone is locked
     * and a computer watches, a few times a second. Not on the main thread:
     * each look waits on System UI.
     */
    static String read(Context ctx) {
        PhoneControlService s = PhoneControlService.live();
        if (s == null) return "{\"t\":\"lock\",\"pad\":\"\",\"locked\":true,\"control\":false}";
        s.seeAllViews(true);
        AccessibilityNodeInfo root = lockWindow(s);
        if (root == null) return "{\"t\":\"lock\",\"pad\":\"\",\"locked\":true}";
        Pad p = scan(root);
        try {
            Point size = screen(ctx);
            JSONObject j = new JSONObject().put("t", "lock").put("pad", p.kind).put("locked", true)
                    .put("w", size.x).put("h", size.y);
            if (p.kind.isEmpty()) return j.toString();
            JSONArray keys = new JSONArray();
            for (int i = 0; i < p.digits.size(); i++) keys.put(place(p.digits.get(i)).put("k", p.digitNames.get(i)));
            if (p.delete != null) keys.put(place(p.delete).put("k", "del"));
            if (p.ok != null) keys.put(place(p.ok).put("k", "ok").put("on", p.ok.isEnabled() && p.ok.isClickable()));
            j.put("keys", keys);
            if (p.field != null) {
                CharSequence t = p.field.getText();
                // How many, never what: a password field gives its length as dots, or nothing.
                int n = t == null ? 0 : t.length();
                j.put("field", place(p.field).put("n", n));
            }
            if (p.pattern != null) j.put("pattern", place(p.pattern));
            JSONArray words = new JSONArray();
            for (AccessibilityNodeInfo w : p.words) {
                CharSequence t = w.getText();
                if (t != null && t.length() > 0) words.put(place(w).put("s", t.toString()));
            }
            j.put("words", words);
            return j.toString();
        } catch (Exception e) {
            return "{\"t\":\"lock\",\"pad\":\"\",\"locked\":true}";
        }
    }

    private static JSONObject place(AccessibilityNodeInfo n) throws org.json.JSONException {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return new JSONObject().put("x", r.left).put("y", r.top).put("w", r.width()).put("h", r.height());
    }

    private static Point screen(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        if (d != null) d.getRealSize(p);
        if (p.x <= 0 || p.y <= 0) p.set(1080, 2340);
        return p;
    }

    // ── its keys, from the computer's keyboard ──

    /** Keys typed for the pad that wait for it to come up, and since when. Input thread only. */
    private static final ArrayDeque<String> waiting = new ArrayDeque<>();
    private static long waitingSince;
    private static boolean swiped;

    /**
     * A key typed at the computer while the phone is locked: true when it is
     * the pad's (pressed now, or once the pad is up). {@code later} runs
     * work after a delay on the input's thread, which this is called on.
     */
    static boolean press(Context ctx, String key, Later later) {
        PhoneControlService s = PhoneControlService.live();
        if (s == null || key == null || !locked(ctx)) return false;
        if (!waiting.isEmpty()) {
            // The pad is on its way: in the order typed.
            waiting.add(key);
            return true;
        }
        boolean digit = key.length() == 1;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            // Asleep: a digit wakes the phone and goes in once its pad is up;
            // Backspace and Enter only wake it (the caller's way).
            if (!digit) return false;
            begin(key);
            s.wake();
            later.after(RETRY_MS * 3, () -> pump(ctx, later));
            return true;
        }
        AccessibilityNodeInfo root = lockWindow(s);
        // Something over the lock screen (a call, the camera): not the pad's keys.
        if (root == null) return false;
        Pad p = scan(root);
        // A PIN pad up, or the lock screen without one and a digit typed (which brings it up).
        if ("pin".equals(p.kind) || (p.kind.isEmpty() && digit)) {
            begin(key);
            pump(ctx, later);
            return true;
        }
        // A password or a pattern: the keys go the usual way, into the focused field.
        return false;
    }

    private static void begin(String key) {
        waitingSince = System.currentTimeMillis();
        swiped = false;
        waiting.add(key);
    }

    interface Later {
        void after(long ms, Runnable r);
    }

    /** The keys that wait, pressed as soon as the pad is there: the screen woken and the pad brought up first. */
    private static void pump(Context ctx, Later later) {
        while (!waiting.isEmpty()) {
            PhoneControlService s = PhoneControlService.live();
            if (s == null || !locked(ctx) || System.currentTimeMillis() - waitingSince > PAD_WAIT_MS) {
                if (!waiting.isEmpty()) DexLog.step("phone", "the lock screen's pad did not come up: " + waiting.size() + " key(s) dropped");
                waiting.clear();
                return;
            }
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                s.wake();
                later.after(RETRY_MS * 3, () -> pump(ctx, later));
                return;
            }
            AccessibilityNodeInfo root = lockWindow(s);
            Pad p = root == null ? null : scan(root);
            if (p == null) {
                // Something else is in front (a call, the camera over the lock screen): not ours to type into.
                DexLog.step("phone", "not the lock screen in front: " + waiting.size() + " key(s) dropped");
                waiting.clear();
                return;
            }
            if (!"pin".equals(p.kind)) {
                if (p.kind.isEmpty() && !swiped) {
                    swiped = true;
                    swipeUp(s, screen(ctx));
                }
                if (!p.kind.isEmpty()) {
                    // A password or a pattern: the keys are not a PIN pad's;
                    // typed text goes the usual way, into the focused field.
                    waiting.clear();
                    return;
                }
                later.after(RETRY_MS, () -> pump(ctx, later));
                return;
            }
            String k = waiting.poll();
            AccessibilityNodeInfo node = "del".equals(k) ? p.delete : "ok".equals(k) ? p.ok : p.digit(k);
            if (node == null || ("ok".equals(k) && !(node.isEnabled() && node.isClickable()))) continue;
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }

    /** "Swipe to unlock": the lock screen's own gesture, from below the middle upwards. */
    private static void swipeUp(AccessibilityService s, Point size) {
        Path path = new Path();
        path.moveTo(size.x / 2f, size.y * 0.75f);
        path.lineTo(size.x / 2f, size.y * 0.35f);
        try {
            s.dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 200)).build(), null, null);
            DexLog.step("phone", "a key from the computer brings up the lock screen's pad");
        } catch (RuntimeException e) {
            DexLog.warn("phone", "cannot bring up the lock screen's pad", e);
        }
    }
}
