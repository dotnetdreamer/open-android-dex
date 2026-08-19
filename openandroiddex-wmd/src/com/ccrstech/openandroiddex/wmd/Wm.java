package com.ccrstech.openandroiddex.wmd;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * The privileged half of the desktop: everything that needs {@code uid 2000}.
 *
 * Two movers exist deliberately.
 *
 * {@link #applyTransaction} is the cheap one, but on this device it does NOT move a
 * surface. One UI's SystemUI registers a global {@code ShellTaskOrganizer}, and
 * {@code Task#canBeOrganized()} returns true for every root task, so every task on the
 * scrcpy display is {@code isOrganized()}. Both
 * {@code WindowContainer#updateSurfacePositionNonOrganized} and
 * {@code Task#updateSurfaceSize} early-return on that ("Avoid fighting with the
 * organizer over Surface position"), so a plain {@code setBounds} updates the app's
 * Configuration — it relayouts and reports new bounds — while the leash stays put.
 *
 * {@link #startNewTransition} is the one that actually moves pixels:
 * {@code Transition#buildFinishTransaction} calls {@code resetSurfaceTransform}
 * unconditionally for every target, doing {@code setPosition} + {@code setWindowCrop}
 * on the leash, and then re-runs {@code assignLayers}. Same {@code enforceTaskPermission}
 * gate, still no organizer registration. This is also why the old {@code resizeTask}
 * path worked at all — it wraps {@code task.resize()} in a Transition itself.
 *
 * Keep state-only changes (focusable, always-on-top, drag-resizing) on
 * {@code applyTransaction}; they have no surface consequence and do not deserve a
 * transition.
 */
final class Wm {

    /** WindowManager.TRANSIT_CHANGE */
    static final int TRANSIT_CHANGE = 6;

    private static Object atm;                 // IActivityTaskManager
    private static Object woc;                 // IWindowOrganizerController
    private static Object wms;                 // IWindowManager
    private static Class<?> wctCls;            // android.window.WindowContainerTransaction
    private static Class<?> tokenCls;          // android.window.WindowContainerToken

    private Wm() {
    }

    // ── service handles ────────────────────────────────────────────────────

    static Object atm() {
        if (atm == null) {
            atm = Refl.callStatic(Refl.cls("android.app.ActivityTaskManager"),
                    "getService", new Class<?>[]{});
        }
        return atm;
    }

    static Object organizer() {
        if (woc == null) woc = Refl.call(atm(), "getWindowOrganizerController");
        return woc;
    }

    static Object windowManager() {
        if (wms == null) {
            IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                    "getService", new Class<?>[]{String.class}, "window");
            if (b == null) throw new Refl.WmError("no window service");
            wms = Refl.callStatic(Refl.cls("android.view.IWindowManager$Stub"),
                    "asInterface", new Class<?>[]{IBinder.class}, b);
        }
        return wms;
    }

    static Class<?> wctClass() {
        if (wctCls == null) wctCls = Refl.cls("android.window.WindowContainerTransaction");
        return wctCls;
    }

    static Class<?> tokenClass() {
        if (tokenCls == null) tokenCls = Refl.cls("android.window.WindowContainerToken");
        return tokenCls;
    }

    // ── task enumeration ───────────────────────────────────────────────────

    /** One root task, flattened out of RunningTaskInfo so nothing else needs reflection. */
    static final class TaskRec {
        int taskId;
        int displayId;
        int windowingMode;
        int activityType;
        boolean visible;
        Rect bounds = new Rect();
        Rect appBounds = new Rect();
        String pkg = "?";
        String activity = "?";
        Object token;          // WindowContainerToken
        Object info;           // the raw RunningTaskInfo

        String line(int index) {
            return "TASK " + index + " " + taskId + " " + displayId + " " + windowingMode
                    + " " + activityType + " " + (visible ? 1 : 0)
                    + " " + bounds.left + " " + bounds.top + " " + bounds.right + " " + bounds.bottom
                    + " " + appBounds.left + " " + appBounds.top + " " + appBounds.right + " " + appBounds.bottom
                    + " " + pkg + " " + activity;
        }
    }

    /**
     * Root tasks on one display, in the order the framework returns them.
     *
     * `getAllRootTaskInfosOnDisplay` is documented nowhere; the ordering is verified
     * against `dumpsys activity containers` by {@code Probe order}. Do not assume it
     * without running that — the whole z-order design depends on which end is the top.
     */
    static List<TaskRec> tasksOnDisplay(int displayId) {
        Object raw = Refl.callSig(atm(), "getAllRootTaskInfosOnDisplay",
                new Class<?>[]{int.class}, displayId);
        List<?> infos = (List<?>) raw;
        List<TaskRec> out = new ArrayList<>();
        for (Object info : infos) out.add(read(info));
        return out;
    }

    /**
     * Every root task on every display. Used to locate the desktop without depending on
     * accessibility window enumeration, which does not reliably report a freshly created
     * virtual display and cannot distinguish a live scrcpy session from the corpses that
     * previous sessions leave behind.
     */
    static List<TaskRec> allTasks() {
        List<?> infos = (List<?>) Refl.callSig(atm(), "getAllRootTaskInfos", new Class<?>[]{});
        List<TaskRec> out = new ArrayList<>();
        for (Object info : infos) out.add(read(info));
        return out;
    }

    /**
     * The display the desktop is on, or -1.
     *
     * Preference order matters. Hosting {@code pkg} (the launcher) is the strongest
     * signal, but it cannot be the only one: reinstalling the launcher APK force-stops
     * it, so keying solely on it makes the desktop undiscoverable for exactly as long as
     * an update takes. Falling back to "most visible tasks" keeps it found, and skipping
     * displays whose tasks are all invisible rejects the virtual displays that dead
     * scrcpy sessions leave behind.
     */
    static int displayHosting(String pkg) {
        List<TaskRec> all = allTasks();
        for (TaskRec t : all) {
            if (t.displayId != 0 && pkg.equals(t.pkg)) return t.displayId;
        }
        int best = -1;
        int bestVisible = 0;
        for (TaskRec t : all) {
            if (t.displayId == 0 || !t.visible) continue;
            int visible = 0;
            for (TaskRec o : all) {
                if (o.displayId == t.displayId && o.visible) visible++;
            }
            // Ties go to the newest display: ids increase, and the live session is the
            // most recently created one.
            if (visible > bestVisible || (visible == bestVisible && t.displayId > best)) {
                best = t.displayId;
                bestVisible = visible;
            }
        }
        return best;
    }

    static TaskRec taskById(int displayId, int taskId) {
        for (TaskRec t : tasksOnDisplay(displayId)) {
            if (t.taskId == taskId) return t;
        }
        throw new Refl.WmError("no task " + taskId + " on display " + displayId);
    }

    private static TaskRec read(Object info) {
        TaskRec t = new TaskRec();
        t.info = info;
        t.taskId = (Integer) Refl.field(info, "taskId");
        t.displayId = (Integer) Refl.field(info, "displayId");
        t.visible = Boolean.TRUE.equals(Refl.field(info, "isVisible"));
        t.token = Refl.field(info, "token");

        Object config = Refl.field(info, "configuration");
        Object winConfig = Refl.field(config, "windowConfiguration");
        t.windowingMode = (Integer) Refl.call(winConfig, "getWindowingMode");
        t.activityType = (Integer) Refl.call(winConfig, "getActivityType");
        Rect b = (Rect) Refl.call(winConfig, "getBounds");
        if (b != null) t.bounds.set(b);
        Rect ab = (Rect) Refl.call(winConfig, "getAppBounds");
        if (ab != null) t.appBounds.set(ab);

        ComponentName top = (ComponentName) Refl.field(info, "topActivity");
        if (top == null) top = (ComponentName) Refl.field(info, "baseActivity");
        if (top != null) {
            t.pkg = top.getPackageName();
            t.activity = top.getClassName();
        }
        return t;
    }

    // ── transactions ───────────────────────────────────────────────────────

    static Object newTransaction() {
        try {
            return wctClass().getConstructor().newInstance();
        } catch (Throwable e) {
            throw new Refl.WmError("WindowContainerTransaction: " + e);
        }
    }

    static void setBounds(Object wct, Object token, Rect bounds) {
        Refl.callSig(wct, "setBounds", new Class<?>[]{tokenClass(), Rect.class}, token, bounds);
    }

    static void setAppBounds(Object wct, Object token, Rect bounds) {
        Refl.callSig(wct, "setAppBounds", new Class<?>[]{tokenClass(), Rect.class}, token, bounds);
    }

    static void reorder(Object wct, Object token, boolean onTop) {
        Refl.callSig(wct, "reorder", new Class<?>[]{tokenClass(), boolean.class}, token, onTop);
    }

    /**
     * The single most important call in the chrome design.
     *
     * Without it, a chrome task that reaches {@code mFocusedApp} makes
     * {@code DisplayContent#mFindFocusedWindow} abort with a null result and the whole
     * display loses keyboard focus — silently, no crash, no obvious log.
     * {@code FLAG_NOT_FOCUSABLE} on the window does not prevent it, because
     * {@code ActivityRecord#isFocusable} is windowing-mode based. A non-focusable task is
     * also skipped by {@code resumeFocusedTasksTopActivities}, which is what keeps the app
     * underneath RESUMED and holding the top-resumed slot.
     */
    static void setFocusable(Object wct, Object token, boolean focusable) {
        Refl.callSig(wct, "setFocusable", new Class<?>[]{tokenClass(), boolean.class},
                token, focusable);
    }

    /**
     * Real minimise. `reorder(..., false)` only pushes a task down the stack, where the
     * fullscreen launcher occludes it — the window manager then stops it anyway, so it
     * looks the same on screen but the task is merely a casualty of occlusion. setHidden
     * states the intent, and the task stays attached to the display so it can be restored.
     */
    static void setHidden(Object wct, Object token, boolean hidden) {
        Refl.callSig(wct, "setHidden", new Class<?>[]{tokenClass(), boolean.class},
                token, hidden);
    }

    static void setAlwaysOnTop(Object wct, Object token, boolean alwaysOnTop) {
        Refl.callSig(wct, "setAlwaysOnTop", new Class<?>[]{tokenClass(), boolean.class},
                token, alwaysOnTop);
    }

    /**
     * Publish a captionBar inset over {@code frame} so apps lay out below it.
     *
     * This is the same call AOSP's own desktop caption makes
     * ({@code WindowDecoration.WindowDecorationInsets#update}). The signature grew
     * across releases — 14 took (token, owner, index, type, frame), 15 added
     * boundingRects, 16 added flags — so bind it by arity at runtime rather than
     * guessing, because being wrong here throws inside system_server.
     *
     * WM link-to-death's {@code owner}, so it must be a binder the daemon keeps.
     */
    static void addCaptionInset(Object wct, Object token, Rect frame) {
        int type = android.view.WindowInsets.Type.captionBar();
        java.lang.reflect.Method m = pickInsetsMethod("addInsetsSource");
        Class<?>[] p = m.getParameterTypes();
        try {
            if (p.length == 5) {
                m.invoke(wct, token, insetOwner(), 0, type, frame);
            } else if (p.length == 6) {
                m.invoke(wct, token, insetOwner(), 0, type, frame, null);
            } else {
                m.invoke(wct, token, insetOwner(), 0, type, frame, null, 0);
            }
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new Refl.WmError("addInsetsSource/" + p.length + ": " + c);
        }
    }

    static void removeCaptionInset(Object wct, Object token) {
        int type = android.view.WindowInsets.Type.captionBar();
        java.lang.reflect.Method m = pickInsetsMethod("removeInsetsSource");
        try {
            m.invoke(wct, token, insetOwner(), 0, type);
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new Refl.WmError("removeInsetsSource: " + c);
        }
    }

    /**
     * Android 16 ships two 7-arg {@code addInsetsSource} overloads that differ only in
     * their 5th parameter — {@code Insets} vs {@code Rect}. Selecting by arity alone
     * picks arbitrarily and then throws inside system_server, so select by type: we
     * always supply an absolute {@code Rect} frame.
     */
    private static java.lang.reflect.Method pickInsetsMethod(String name) {
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : wctClass().getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean rectFramed = p.length < 5 || p[4] == Rect.class;
            if (!rectFramed) continue;
            if (best == null || p.length > best.getParameterTypes().length) best = m;
        }
        if (best == null) throw new Refl.WmError("no Rect-framed " + name
                + " on WindowContainerTransaction");
        best.setAccessible(true);
        return best;
    }

    /** Long-lived owner for inset sources; WM holds a death recipient on it. */
    private static android.os.IBinder insetOwner;

    static android.os.IBinder insetOwner() {
        if (insetOwner == null) insetOwner = new android.os.Binder();
        return insetOwner;
    }

    /** Applies immediately. Correct for state; does NOT move an organized surface. */
    static void applyTransaction(Object wct) {
        Refl.callSig(organizer(), "applyTransaction", new Class<?>[]{wctClass()}, wct);
    }

    /** Wraps the transaction in a TRANSIT_CHANGE transition — this one moves pixels. */
    static Object startNewTransition(Object wct) {
        return Refl.callSig(organizer(), "startNewTransition",
                new Class<?>[]{int.class, wctClass()}, TRANSIT_CHANGE, wct);
    }

    static boolean hasStartNewTransition() {
        try {
            return Refl.hasMethod(organizer().getClass(), "startNewTransition",
                    int.class, wctClass());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Enforced by REMOVE_TASKS, not MANAGE_ACTIVITY_TASKS — verified granted to shell. */
    static void removeTask(int taskId) {
        Refl.callSig(atm(), "removeTask", new Class<?>[]{int.class}, taskId);
    }

    /** Legacy mover, kept as a fallback and as the A/B control for the gate. */
    static void resizeTask(int taskId, Rect bounds) {
        Refl.callSig(atm(), "resizeTask", new Class<?>[]{int.class, Rect.class, int.class},
                taskId, bounds, 0 /* RESIZE_MODE_SYSTEM */);
    }

    /**
     * Move a whole root task to another display.
     *
     * This is how a window that belongs to the desktop is taken back after the phone
     * claimed it. ActivityStarter looks for a reusable task across EVERY display
     * ({@code RootWindowContainer#findTask} searches the preferred display area first and
     * then all the others), so a tap on the phone's own launcher — or a notification —
     * can resolve to a task living on the desktop and drag it off the monitor mid-use.
     * Nothing on the launch side can prevent that, because the launch is not ours.
     *
     * Named {@code moveRootTaskToDisplay} since Android 12; {@code moveStackToDisplay}
     * before that. Both are gated on INTERNAL_SYSTEM_WINDOW, which uid 2000 holds — the
     * same permission {@link #setShouldShowSystemDecors} already relies on.
     */
    static void moveTaskToDisplay(int taskId, int displayId) {
        Class<?>[] sig = new Class<?>[]{int.class, int.class};
        if (Refl.hasMethod(atm().getClass(), "moveRootTaskToDisplay", int.class, int.class)) {
            Refl.callSig(atm(), "moveRootTaskToDisplay", sig, taskId, displayId);
        } else {
            Refl.callSig(atm(), "moveStackToDisplay", sig, taskId, displayId);
        }
    }

    // ── display decoration policy ──────────────────────────────────────────
    //
    // The live device already runs DesktopModeWindowDecorViewModel with
    // isDesktopFirst=true on the scrcpy display, and creates a WindowDecoration +
    // Freeform Outline per task — but reserves no caption strip (task bounds ==
    // app bounds). These two knobs are what decides whether the platform's own
    // caption is allowed to appear. Both are gated on INTERNAL_SYSTEM_WINDOW,
    // which uid 2000 holds.

    static boolean shouldShowSystemDecors(int displayId) {
        return Boolean.TRUE.equals(Refl.callSig(windowManager(), "shouldShowSystemDecors",
                new Class<?>[]{int.class}, displayId));
    }

    static void setShouldShowSystemDecors(int displayId, boolean on) {
        Refl.callSig(windowManager(), "setShouldShowSystemDecors",
                new Class<?>[]{int.class, boolean.class}, displayId, on);
    }

    static boolean shouldShowIme(int displayId) {
        return Boolean.TRUE.equals(Refl.callSig(windowManager(), "shouldShowIme",
                new Class<?>[]{int.class}, displayId));
    }

    static void setShouldShowIme(int displayId, boolean on) {
        Refl.callSig(windowManager(), "setShouldShowIme",
                new Class<?>[]{int.class, boolean.class}, displayId, on);
    }

    static int uid() {
        return (Integer) Refl.callStatic(Refl.cls("android.os.Process"), "myUid",
                new Class<?>[]{});
    }
}
