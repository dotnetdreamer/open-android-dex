package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

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

    static void opened(Activity a) {
        if (open.add(a.getClass().getName())) changed();
    }

    static void closed(Activity a) {
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
}
