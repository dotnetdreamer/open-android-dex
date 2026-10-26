package com.ccrstech.openandroiddex.wmd;

import android.graphics.Rect;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.util.List;

/**
 * The gate from doc/custom-titlebar-v2.md §8, as a runnable.
 *
 * This is the daemon's first commit rather than throwaway scaffolding: `list` stays
 * useful forever as the pairing/debug tool, and `move` is the A/B that decides which
 * mover the daemon uses.
 *
 *   CLASSPATH=/data/local/tmp/wmd.dex app_process /system/bin \
 *       com.ccrstech.openandroiddex.wmd.Probe <cmd> [args]
 */
public final class Probe {

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        try {
            switch (args[0]) {
                case "caps":    caps(); break;
                case "list":    list(intArg(args, 1)); break;
                case "move":    move(args); break;
                case "decors":  decors(args); break;
                case "focusable": focusable(args); break;
                case "stack":   stack(args); break;
                case "sig":     sig(args); break;
                case "strip":   strip(args); break;
                case "fingers": fingers(args); break;
                default: usage();
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void usage() {
        System.out.println("usage:");
        System.out.println("  caps                                    what is reachable at this uid");
        System.out.println("  list <displayId>                        root tasks, in framework order");
        System.out.println("  move <displayId> <taskId> <dx> <dy> <legacy|transition|resize>");
        System.out.println("  decors <displayId> [on|off]             read / set system decorations");
        System.out.println("  focusable <displayId> <taskId> <0|1>");
        System.out.println("  stack <displayId> <lowTaskId> <highTaskId>   one WCT, low then high to top");
        System.out.println("  fingers <n> <x1> <y1> <x2> <y2> <ms> [gap]   n fingers slid together on the phone's screen");
    }

    // ── caps ───────────────────────────────────────────────────────────────

    private static void caps() {
        System.out.println("uid                       = " + Wm.uid());
        report("ActivityTaskManager.getService", () -> Wm.atm() != null);
        report("getWindowOrganizerController", () -> Wm.organizer() != null);
        report("IWindowManager", () -> Wm.windowManager() != null);
        report("WindowContainerTransaction", () -> Wm.wctClass() != null);
        report("WindowContainerToken", () -> Wm.tokenClass() != null);
        report("woc.applyTransaction", () ->
                Refl.hasMethod(Wm.organizer().getClass(), "applyTransaction", Wm.wctClass()));
        report("woc.startNewTransition", Wm::hasStartNewTransition);
        report("wct.setBounds", () ->
                Refl.hasMethod(Wm.wctClass(), "setBounds", Wm.tokenClass(), Rect.class));
        report("wct.setAppBounds", () ->
                Refl.hasMethod(Wm.wctClass(), "setAppBounds", Wm.tokenClass(), Rect.class));
        report("wct.reorder", () ->
                Refl.hasMethod(Wm.wctClass(), "reorder", Wm.tokenClass(), boolean.class));
        report("wct.setFocusable", () ->
                Refl.hasMethod(Wm.wctClass(), "setFocusable", Wm.tokenClass(), boolean.class));
        report("wct.setAlwaysOnTop", () ->
                Refl.hasMethod(Wm.wctClass(), "setAlwaysOnTop", Wm.tokenClass(), boolean.class));
        report("wct.addInsetsSource", () -> {
            for (java.lang.reflect.Method m : Wm.wctClass().getDeclaredMethods()) {
                if (m.getName().equals("addInsetsSource")) return true;
            }
            return false;
        });
        report("wms.setShouldShowSystemDecors", () ->
                Refl.hasMethod(Wm.windowManager().getClass(), "setShouldShowSystemDecors",
                        int.class, boolean.class));
        report("atm.registerTaskStackListener", () -> {
            Class<?> l = Refl.clsOrNull("android.app.ITaskStackListener");
            return l != null && Refl.hasMethod(Wm.atm().getClass(),
                    "registerTaskStackListener", l);
        });
        report("android.app.TaskStackListener", () ->
                Refl.clsOrNull("android.app.TaskStackListener") != null);
    }

    private interface Check {
        boolean run() throws Throwable;
    }

    private static void report(String name, Check c) {
        String verdict;
        try {
            verdict = c.run() ? "yes" : "NO";
        } catch (Throwable t) {
            verdict = "NO (" + t + ")";
        }
        System.out.println(pad(name) + " = " + verdict);
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 32) b.append(' ');
        return b.toString();
    }

    // ── list ───────────────────────────────────────────────────────────────

    private static void list(int displayId) {
        List<Wm.TaskRec> tasks = Wm.tasksOnDisplay(displayId);
        System.out.println("# index taskId display mode actType vis  bounds  appBounds  pkg activity");
        System.out.println("# index 0 is FIRST as returned by getAllRootTaskInfosOnDisplay —"
                + " cross-check against `dumpsys activity containers` before trusting it as top");
        for (int i = 0; i < tasks.size(); i++) {
            Wm.TaskRec t = tasks.get(i);
            System.out.println(t.line(i)
                    + (t.bounds.equals(t.appBounds) ? "   [no caption strip reserved]" : "   [inset]"));
        }
    }

    // ── move: the Block 3 crux experiment ──────────────────────────────────

    private static void move(String[] args) {
        int displayId = intArg(args, 1);
        int taskId = intArg(args, 2);
        int dx = intArg(args, 3);
        int dy = intArg(args, 4);
        String how = args.length > 5 ? args[5] : "transition";

        Wm.TaskRec t = Wm.taskById(displayId, taskId);
        Rect want = new Rect(t.bounds);
        want.offset(dx, dy);
        System.out.println("before  " + t.bounds);
        System.out.println("want    " + want + "   via " + how);

        long t0 = System.nanoTime();
        switch (how) {
            case "legacy": {
                Object wct = Wm.newTransaction();
                Wm.setBounds(wct, t.token, want);
                Wm.applyTransaction(wct);
                break;
            }
            case "transition": {
                Object wct = Wm.newTransaction();
                Wm.setBounds(wct, t.token, want);
                Wm.startNewTransition(wct);
                break;
            }
            case "resize":
                Wm.resizeTask(taskId, want);
                break;
            default:
                System.out.println("ERR unknown mover " + how);
                return;
        }
        long call = System.nanoTime() - t0;
        System.out.printf("call    %.2f ms%n", call / 1e6);

        // Settle, then re-read. A Configuration change lands long before the leash does.
        sleep(400);
        Rect now = Wm.taskById(displayId, taskId).bounds;
        System.out.println("after   " + now);
        System.out.println(now.equals(want)
                ? "BOUNDS OK — now LOOK AT THE SCREEN. If the window did not visibly move,"
                  + " the isOrganized() wall is real and this mover is unusable."
                : "BOUNDS NOT APPLIED (clamped or rejected)");
    }

    // ── decors: does the platform's own caption become available? ──────────

    private static void decors(String[] args) {
        int displayId = intArg(args, 1);
        System.out.println("shouldShowSystemDecors(" + displayId + ") = "
                + Wm.shouldShowSystemDecors(displayId));
        System.out.println("shouldShowIme(" + displayId + ")          = "
                + Wm.shouldShowIme(displayId));
        if (args.length > 2) {
            boolean on = "on".equals(args[2]) || "1".equals(args[2]) || "true".equals(args[2]);
            System.out.println("setting decors -> " + on);
            Wm.setShouldShowSystemDecors(displayId, on);
            sleep(1200);
            System.out.println("now shouldShowSystemDecors = " + Wm.shouldShowSystemDecors(displayId));
            System.out.println("re-listing tasks; watch for appBounds != bounds (= caption reserved):");
            list(displayId);
            System.out.println("ALSO CHECK THE SCREEN for a secondary home / nav bar appearing."
                    + " Revert with: decors " + displayId + " off");
        }
    }

    // ── focusable ──────────────────────────────────────────────────────────

    private static void focusable(String[] args) {
        int displayId = intArg(args, 1);
        int taskId = intArg(args, 2);
        boolean on = intArg(args, 3) != 0;
        Wm.TaskRec t = Wm.taskById(displayId, taskId);
        Object wct = Wm.newTransaction();
        Wm.setFocusable(wct, t.token, on);
        Wm.applyTransaction(wct);
        System.out.println("setFocusable(task " + taskId + ", " + on + ") applied");
    }

    // ── stack: two tasks, one transaction, ordered ─────────────────────────

    private static void stack(String[] args) {
        int displayId = intArg(args, 1);
        int lowTaskId = intArg(args, 2);
        int highTaskId = intArg(args, 3);
        Wm.TaskRec low = Wm.taskById(displayId, lowTaskId);
        Wm.TaskRec high = Wm.taskById(displayId, highTaskId);

        // Hierarchy ops apply in list order, so "low to top" then "high to top"
        // leaves high above low, adjacent, with no observable intermediate state.
        Object wct = Wm.newTransaction();
        Wm.reorder(wct, low.token, true);
        Wm.reorder(wct, high.token, true);
        long t0 = System.nanoTime();
        Wm.startNewTransition(wct);
        System.out.printf("stack applied in %.2f ms%n", (System.nanoTime() - t0) / 1e6);
        sleep(500);
        list(displayId);
    }

    // ── sig: hidden-API signatures vary by build and OEM, so never guess ───

    private static void sig(String[] args) {
        Class<?> c = "wct".equals(args[1]) ? Wm.wctClass()
                : "wms".equals(args[1]) ? Wm.windowManager().getClass()
                : "atm".equals(args[1]) ? Wm.atm().getClass()
                : "woc".equals(args[1]) ? Wm.organizer().getClass()
                : Refl.cls(args[1]);
        String filter = args.length > 2 ? args[2].toLowerCase() : "";
        for (java.lang.reflect.Method m : c.getMethods()) {
            if (!m.getName().toLowerCase().contains(filter)) continue;
            StringBuilder b = new StringBuilder(m.getName()).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) b.append(", ");
                b.append(p[i].getSimpleName());
            }
            System.out.println(b.append(") -> ").append(m.getReturnType().getSimpleName()));
        }
    }

    // ── strip: reserve a caption inset the way AOSP's own decor does ───────

    /**
     * Reserves {@code px} at the top of a task for chrome, exactly as
     * {@code WindowDecoration.WindowDecorationInsets#update} does: shrink the app's
     * bounds and publish a captionBar inset source so well-behaved apps move their
     * content down.
     *
     * The owner binder is link-to-death'd by WM, so this process sleeps to keep the
     * strip alive long enough to observe. In the daemon the owner is a long-lived
     * field and the source is removed when the task closes.
     */
    private static void strip(String[] args) {
        int displayId = intArg(args, 1);
        int taskId = intArg(args, 2);
        int px = intArg(args, 3);

        Wm.TaskRec t = Wm.taskById(displayId, taskId);
        Rect caption = new Rect(t.bounds.left, t.bounds.top, t.bounds.right, t.bounds.top + px);
        Rect app = new Rect(t.bounds);
        app.top += px;
        System.out.println("task    " + t.bounds);
        System.out.println("caption " + caption);
        System.out.println("app     " + app);

        Object wct = Wm.newTransaction();
        if (px > 0) {
            Wm.setAppBounds(wct, t.token, app);
            Wm.addCaptionInset(wct, t.token, caption);
        } else {
            Wm.setAppBounds(wct, t.token, new Rect(t.bounds));
            Wm.removeCaptionInset(wct, t.token);
        }
        Wm.applyTransaction(wct);
        System.out.println("applied; settling…");
        sleep(800);
        list(displayId);
        if (px > 0) {
            System.out.println("holding the inset owner alive for 20s — LOOK AT THE SCREEN:");
            System.out.println("  a blank strip at the top of the window means the app inset itself.");
            sleep(20000);
            Object undo = Wm.newTransaction();
            Wm.setAppBounds(undo, t.token, new Rect(t.bounds));
            Wm.removeCaptionInset(undo, t.token);
            Wm.applyTransaction(undo);
            System.out.println("reverted");
        }
    }

    // ── util ───────────────────────────────────────────────────────────────

    // ── fingers ─────────────────────────────────────────────────────────────

    /**
     * {@code n} fingers on the phone's own screen, {@code gap} px apart side by side, slid
     * together from (x1, y1) to (x2, y2) over {@code ms} — what `input` cannot do, having
     * one finger, and what the phone's touchpad's two-finger scroll and three-finger
     * gestures are checked with (test/pad-test.mjs). Injected as the shell, which holds
     * INJECT_EVENTS, a step every 10 ms.
     */
    private static void fingers(String[] args) {
        int n = Math.max(1, Math.min(5, intArg(args, 1)));
        float x1 = intArg(args, 2), y1 = intArg(args, 3), x2 = intArg(args, 4), y2 = intArg(args, 5);
        int ms = Math.max(50, intArg(args, 6));
        float gap = args.length > 7 ? intArg(args, 7) : 160;
        IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                "getService", new Class<?>[]{String.class}, "input");
        Object im = Refl.callStatic(Refl.cls("android.hardware.input.IInputManager$Stub"),
                "asInterface", new Class<?>[]{IBinder.class}, b);
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        long down = SystemClock.uptimeMillis();
        // Down one at a time, as a hand puts them down.
        for (int k = 1; k <= n; k++) {
            int action = k == 1 ? MotionEvent.ACTION_DOWN
                    : MotionEvent.ACTION_POINTER_DOWN | ((k - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            touch(im, down, action, k, props, x1, y1, gap);
        }
        int steps = Math.max(1, ms / 10);
        for (int s = 1; s <= steps; s++) {
            sleep(10);
            float t = s / (float) steps;
            touch(im, down, MotionEvent.ACTION_MOVE, n, props, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, gap);
        }
        for (int k = n; k >= 1; k--) {
            int action = k == 1 ? MotionEvent.ACTION_UP
                    : MotionEvent.ACTION_POINTER_UP | ((k - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            touch(im, down, action, k, props, x2, y2, gap);
        }
        System.out.println("OK " + n + " fingers");
    }

    /** The first {@code count} fingers at (x, y), side by side: one touchscreen event, on display 0. */
    private static void touch(Object im, long down, int action, int count, MotionEvent.PointerProperties[] props,
                              float x, float y, float gap) {
        MotionEvent.PointerCoords[] at = new MotionEvent.PointerCoords[count];
        MotionEvent.PointerProperties[] which = new MotionEvent.PointerProperties[count];
        for (int i = 0; i < count; i++) {
            at[i] = new MotionEvent.PointerCoords();
            at[i].x = x + (i - (props.length - 1) / 2f) * gap;
            at[i].y = y;
            at[i].pressure = 1f;
            at[i].size = 1f;
            which[i] = props[i];
        }
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, which, at,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        Refl.callSig(e, "setDisplayId", new Class<?>[]{int.class}, 0);
        Refl.callSig(im, "injectInputEvent", new Class<?>[]{InputEvent.class, int.class}, e, 2);
        e.recycle();
    }

    private static int intArg(String[] args, int i) {
        if (args.length <= i) throw new IllegalArgumentException("missing arg " + i);
        return Integer.parseInt(args[i]);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
