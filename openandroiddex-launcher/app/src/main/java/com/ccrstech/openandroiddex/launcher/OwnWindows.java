package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.SparseIntArray;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Which of OUR OWN desktop windows are open — Settings and Linux.
 *
 * The taskbar cannot learn this the way it learns about everybody else's
 * windows. That list comes from the PC enforcer, which builds it from what is
 * VISIBLE on the display and resolves each package to a launcher icon; our own
 * package has no launcher entry on this desktop (loadApps skips it), so our
 * windows resolved to nothing and never appeared. The stopgap was to pin them
 * only while CaptionService reported them minimised, which made the Linux
 * window's taskbar presence depend on an accessibility-service round trip that
 * has to survive a caption, a hide transition and a broadcast — and when any
 * link in that chain missed, a minimised window was simply unreachable.
 *
 * It never needed a round trip: these activities live in the launcher's own
 * process, so they can just say so. A window is listed from onCreate to
 * onDestroy — open whether it is on top, behind something, or minimised —
 * which is what a taskbar is supposed to show. CaptionService's minimised set
 * is still what decides whether a click RESTORES or merely raises.
 *
 * Main thread only: every caller is an activity lifecycle callback or the
 * launcher's own UI.
 */
final class OwnWindows {

    private OwnWindows() {}

    /** Told whenever the set changes, so the taskbar can rebuild. */
    interface Listener {
        void onOwnWindowsChanged();
    }

    /** Activity class names, in the order the windows were opened. */
    private static final LinkedHashSet<String> open = new LinkedHashSet<>();

    /**
     * Everyone who wants to know: the taskbar, and the Task Manager while it is
     * open. A set rather than one slot because both need the same news at the
     * same time — but only with an explicit remove on the other side, or a
     * density rebuild would leave a dead listener behind on every pass.
     */
    private static final java.util.concurrent.CopyOnWriteArraySet<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    /**
     * A window of ours is open; from onCreate. It also keeps its content below
     * the bar CaptionService draws over its top (see {@link #keepClearOfBar}):
     * every window listed here wears one on the desktop.
     */
    static void opened(Activity a) {
        if (open.add(a.getClass().getName())) changed();
        keepClearOfBar(a);
    }

    /** From onDestroy. */
    static void closed(Activity a) {
        stopKeepingClear(a);
        if (open.remove(a.getClass().getName())) changed();
    }

    static List<String> list() {
        return new ArrayList<>(open);
    }

    /**
     * Register for changes. Plain statics rather than a broadcast because every
     * side of this is the same process. Whoever registers MUST unregister —
     * see the note on {@link #listeners}.
     */
    static void setListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    static void clearListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        for (Listener l : listeners) l.onOwnWindowsChanged();
    }

    /**
     * Close one of our windows the way its caption's ✕ does — by asking it,
     * for the windows that must not just go. Answers false for every other
     * window, whose task the caller then removes.
     *
     * The Linux window is asked because closing it ends a container the user
     * may have unsaved work inside, and removing the task gives the activity no
     * opportunity to ask first; it closes itself once the user confirms. The
     * Docker window is asked for the same reason: its ✕ stops a machine with
     * the user's running containers in it. The File transfer window joins them
     * once more: its ✕ can land mid-copy, and a half-copied file the user was
     * never warned about is worse than one more confirmation. A Linux app's
     * window closes the way any window manager closes one: the app is asked
     * (WM_DELETE_WINDOW), and may ask about unsaved work first.
     *
     * One owner for this, because two sides close our windows: the caption,
     * and the computer's "Close the front app" (LauncherActivity's "close").
     */
    static boolean askToClose(Context ctx, int taskId, String activity) {
        if (LinuxActivity.class.getName().equals(activity)
                || DockerActivity.class.getName().equals(activity)
                || FileTransferActivity.class.getName().equals(activity)) {
            ctx.sendBroadcast(new Intent(LauncherActivity.ACTION_CLOSE_WINDOW)
                    .setPackage(ctx.getPackageName())
                    .putExtra("activity", activity));
            return true;
        }
        return LinuxWindowActivity.class.getName().equals(activity)
                && LinuxWindows.closeTask(taskId);
    }

    // ── the bar over our windows' tops ─────────────────────────────────────

    /**
     * How far CaptionService's bar reaches down over the top of each of our
     * tasks, in px; tasks it does not cover are absent.
     *
     * The bar lies over the window itself only where the shell gives our task
     * no caption window of its own to host it. One UI on Android 17 gives our
     * own tasks none (a third-party app's freeform window does get one), so
     * there the bar is hosted on the app window, which starts at the task's
     * top, and the window's first 40 px of content sit under it. The strip the
     * daemon reserves is app bounds only, with no captionBar inset (WmDaemon's
     * STRIP says why), so nothing tells the window either; this does.
     */
    private static final SparseIntArray barOver = new SparseIntArray();

    /** The windows that keep their content below the bar, and how each re-pads. */
    private static final java.util.Map<Activity, Runnable> keepingClear =
            new java.util.HashMap<>();

    /** CaptionService, after a pass and when it goes: every task of ours its bar covers. */
    static void barsOver(SparseIntArray now) {
        SparseIntArray next = now == null ? new SparseIntArray() : now;
        if (sameBars(barOver, next)) return;
        barOver.clear();
        for (int i = 0; i < next.size(); i++) barOver.put(next.keyAt(i), next.valueAt(i));
        for (Runnable pad : new ArrayList<>(keepingClear.values())) pad.run();
    }

    static int barOver(int taskId) {
        return barOver.get(taskId, 0);
    }

    /**
     * Keep this window's content below the bar: its content view is padded at
     * the top by the part of the bar the window's own insets do not already
     * leave free. A shell that publishes a captionBar inset has made room for
     * its caption already, and a window that pads itself for the system bars
     * (systemBars() includes captionBar()) has taken that room. The content
     * view outlives setContentView, so a window that rebuilds itself keeps it.
     * {@link #opened} calls this; a window not listed there calls it from
     * onCreate, and {@link #stopKeepingClear} from onDestroy.
     */
    static void keepClearOfBar(Activity a) {
        Runnable pad = () -> {
            // Never build the window from here: before setContentView that would
            // fix its features early. The run posted below comes after onCreate.
            android.view.Window w = a.getWindow();
            View decor = w == null ? null : w.peekDecorView();
            View content = decor == null ? null : decor.findViewById(android.R.id.content);
            if (content == null) return;
            int over = barOver(a.getTaskId());
            if (over > 0 && Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = content.getRootWindowInsets();
                if (insets != null) {
                    over = Math.max(0, over - insets.getInsets(WindowInsets.Type.captionBar()).top);
                }
            }
            if (content.getPaddingTop() != over) {
                content.setPadding(content.getPaddingLeft(), over,
                        content.getPaddingRight(), content.getPaddingBottom());
            }
        };
        keepingClear.put(a, pad);
        pad.run();
        // A window opened under a bar already reported (one recreated for a theme
        // change, say) hears no report: pad it once its onCreate has built it.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (keepingClear.get(a) == pad) pad.run();
        });
    }

    static void stopKeepingClear(Activity a) {
        keepingClear.remove(a);
    }

    private static boolean sameBars(SparseIntArray a, SparseIntArray b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (b.get(a.keyAt(i), -1) != a.valueAt(i)) return false;
        }
        return true;
    }
}
