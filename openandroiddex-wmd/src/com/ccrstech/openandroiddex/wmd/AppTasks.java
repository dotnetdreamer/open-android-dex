package com.ccrstech.openandroiddex.wmd;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Apps onto a window's display, and what is running there now.
 *
 * <p>The launch is the one place a click waits on the phone, so it is made in
 * this process, straight into ActivityTaskManager, as the shell — the same call
 * {@code am start} makes, without {@code am}: a fork of {@code cmd} costs tens of
 * milliseconds, and the JVM {@code am} itself used to boot cost a second on a
 * Galaxy A13. The call's signature gained and lost parameters across the
 * releases this runs on, so it is bound by its parameters' types rather than
 * by a guess at their count; {@code cmd activity start-activity} is the answer
 * on a build where no binding fits.
 */
final class AppTasks {

    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN: the app fills its window's display. */
    private static final int FULLSCREEN = 1;

    private AppTasks() {
    }

    /** What is on a display: its root tasks, topmost first, and what each one shows. */
    static final class Task {
        int id;
        String pkg = "";
        String activity = "";
        boolean visible;
        /** ActivityInfo.screenOrientation of the top activity; -1 when unknown. */
        int orientation = -1;
        /** The task's own colour (TaskDescription), 0 when it has none. */
        int color;
        String label;
    }

    // ── launching ──

    /**
     * Start {@code component}'s app on {@code displayId}, as a launcher would —
     * an app already running comes over with what it was showing.
     *
     * @return how it was started, for the log
     */
    static String start(ComponentName component, int displayId) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return start(intent, displayId);
    }

    /** The launcher's task for one Linux window ({@code xid}), on {@code displayId}. */
    static String startLinuxWindow(int xid, int displayId) {
        Intent intent = new Intent()
                .setComponent(new ComponentName(WmDaemon.LAUNCHER, WmDaemon.LAUNCHER + ".LinuxWindowActivity"))
                .putExtra("xid", xid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        return start(intent, displayId);
    }

    static String start(Intent intent, int displayId) {
        ComponentName component = intent.getComponent();
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        try {
            // Hidden, and there since Android 9. A display the desktop's freeform
            // switch was flipped for would otherwise open the app as a small
            // window inside its own window.
            Refl.callSig(options, "setLaunchWindowingMode", new Class<?>[]{int.class}, FULLSCREEN);
        } catch (Refl.WmError ignored) {
            // the display's own default, which is fullscreen
        }
        Bundle bundle = options.toBundle();
        Object atm = Wm.atm();
        Throwable failed = null;
        for (Method m : atm.getClass().getMethods()) {
            String name = m.getName();
            if (!name.equals("startActivityAsUser") && !name.equals("startActivity")) continue;
            Object[] args = bind(m.getParameterTypes(), intent, bundle);
            if (args == null) continue;
            try {
                Object r = m.invoke(atm, args);
                int code = r instanceof Integer ? (Integer) r : 0;
                // ActivityManager.START_* — anything below zero is a refusal.
                if (code < 0) throw new Refl.WmError(name + " answered " + code);
                return name + "/" + m.getParameterTypes().length + " (" + code + ")";
            } catch (Throwable t) {
                failed = t.getCause() != null ? t.getCause() : t;
            }
        }
        String why = failed != null ? failed.toString() : "no binding";
        if (intent.hasExtra("xid")) {
            throw new Refl.WmError("cannot start the Linux window's task: " + why);
        }
        String out = exec(10, "cmd", "activity", "start-activity", "--display", String.valueOf(displayId),
                "--windowingMode", String.valueOf(FULLSCREEN), "-n", component.flattenToShortString());
        if (out.contains("Error") || out.contains("Exception")) {
            throw new Refl.WmError("cannot start " + component.flattenToShortString() + ": " + why + "; " + out.trim());
        }
        return "cmd (" + why + ")";
    }

    /**
     * Arguments for one of ActivityTaskManager's start calls, by type, or null
     * if it is not one this knows. In every release the order is: the caller's
     * thread, its package, (from Android 11) its feature, the intent, the
     * resolved type, the result's token and name, the request code, the flags,
     * a profiler, the options, and (in the AsUser form) the user.
     */
    static Object[] bind(Class<?>[] p, Intent intent, Bundle options) {
        Object[] a = new Object[p.length];
        int intentAt = -1;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == Intent.class) {
                if (intentAt >= 0) return null; // two intents: some other call
                intentAt = i;
            }
        }
        if (intentAt < 1) return null;
        int stringsBefore = 0;
        int intsAfter = 0;
        for (int i = 0; i < p.length; i++) {
            Class<?> t = p[i];
            if (i == intentAt) {
                a[i] = intent;
            } else if (t == String.class) {
                // Before the intent: the package, then the feature. After it: nothing to say.
                a[i] = i < intentAt && stringsBefore++ == 0 ? PhoneContext.SHELL_PACKAGE : null;
            } else if (t == int.class) {
                if (i < intentAt) return null;
                // request code, flags, user — all zero: no result, no flags, the owner.
                a[i] = 0;
                intsAfter++;
            } else if (t == Bundle.class) {
                a[i] = options;
            } else if (t.isPrimitive()) {
                return null;
            } else {
                // the caller's thread, the result token, a profiler: none
                a[i] = null;
            }
        }
        return intsAfter >= 2 && stringsBefore >= 1 ? a : null;
    }

    /** {@code cmd …}'s output, or the reason there is none. */
    static String exec(int timeoutS, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) p.destroy();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "Error: " + e;
        }
    }

    // ── what is there ──

    /** The root tasks on {@code displayId}, topmost first. Empty if none, or if the platform will not say. */
    static List<Task> onDisplay(int displayId) {
        List<Task> out = new ArrayList<>();
        Object atm = Wm.atm();
        List<?> infos;
        if (Build.VERSION.SDK_INT >= 31) {
            infos = (List<?>) Refl.callSig(atm, "getAllRootTaskInfosOnDisplay", new Class<?>[]{int.class}, displayId);
        } else if (Refl.hasMethod(atm.getClass(), "getAllStackInfosOnDisplay", int.class)) {
            infos = (List<?>) Refl.callSig(atm, "getAllStackInfosOnDisplay", new Class<?>[]{int.class}, displayId);
        } else {
            infos = (List<?>) Refl.callSig(atm, "getAllStackInfos", new Class<?>[]{});
        }
        if (infos == null) return out;
        for (Object info : infos) {
            Integer d = (Integer) fieldOr(info, "displayId", -1);
            if (d != null && d != displayId) continue;
            // A root task that holds no task — a split screen's empty half, say — is nothing on screen.
            int[] children = (int[]) fieldOr(info, Build.VERSION.SDK_INT >= 31 ? "childTaskIds" : "taskIds", null);
            if (children != null && children.length == 0) continue;
            Task t = new Task();
            Object id = fieldOr(info, "taskId", null);
            if (id == null) id = fieldOr(info, "stackId", -1);
            t.id = (Integer) id;
            t.visible = Boolean.TRUE.equals(fieldOr(info, Build.VERSION.SDK_INT >= 31 ? "isVisible" : "visible", false));
            ComponentName top = (ComponentName) fieldOr(info, "topActivity", null);
            if (top == null) top = (ComponentName) fieldOr(info, "baseActivity", null);
            if (top != null) {
                t.pkg = top.getPackageName();
                t.activity = top.getClassName();
            }
            Object ai = fieldOr(info, "topActivityInfo", null);
            if (ai instanceof android.content.pm.ActivityInfo) {
                t.orientation = ((android.content.pm.ActivityInfo) ai).screenOrientation;
            }
            Object desc = fieldOr(info, "taskDescription", null);
            if (desc instanceof android.app.ActivityManager.TaskDescription) {
                android.app.ActivityManager.TaskDescription td = (android.app.ActivityManager.TaskDescription) desc;
                t.color = td.getPrimaryColor();
                t.label = td.getLabel();
            }
            out.add(t);
        }
        return out;
    }

    private static Object fieldOr(Object target, String name, Object fallback) {
        try {
            return Refl.field(target, name);
        } catch (Refl.WmError e) {
            return fallback;
        }
    }

    // ── the keyboard's display ──

    /**
     * Make {@code taskId} the focused task, and with it its display the one the
     * keyboard goes to. Without its own focus (before Android 14) only the
     * topmost display has a focused window at all; a key aimed at another waits
     * for one to appear — holding every key behind it — and then times out.
     */
    static void focus(int taskId) {
        Refl.callSig(Wm.atm(), "setFocusedTask", new Class<?>[]{int.class}, taskId);
    }

    /** Close the app, as swiping it out of Recents does. */
    static void remove(int taskId) {
        Wm.removeTask(taskId);
    }

    /** The display the focused task is on — where the keyboard goes now — or -1 if the platform will not say. */
    static int focusedDisplay() {
        Object atm = Wm.atm();
        String name = Build.VERSION.SDK_INT >= 31 ? "getFocusedRootTaskInfo" : "getFocusedStackInfo";
        try {
            Object info = Refl.callSig(atm, name, new Class<?>[]{});
            if (info == null) return -1;
            Object d = fieldOr(info, "displayId", -1);
            return d instanceof Integer ? (Integer) d : -1;
        } catch (Refl.WmError e) {
            return -1;
        }
    }
}
