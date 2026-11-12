package com.ccrstech.openandroiddex.wmd;

import java.util.List;

/**
 * Which display the desktop is on, read off the task tree: the rule behind DESKTOP
 * ({@link Wm#displayHosting}). Free of Android types so a computer's JVM can check it
 * as it is (test/desktop-display-test.mjs).
 *
 * <p>The desktop is the launcher's desktop activity on a display of its own, found at
 * the top OR the root of its task. Anything started from the desktop without NEW_TASK
 * lands in the desktop's own task, on top of it, and that task is still the desktop.
 * No other task of the launcher's is: its Settings, Linux and Docker windows can be on
 * any display, and while one of those counted, a Settings window on another display
 * was enough to call that display the desktop.
 *
 * <p>With the desktop activity nowhere — installing the APK force-stops the launcher,
 * which destroys the very signal this reads — the answer is the display the desktop
 * was last seen on, while that display still holds a task. A dead scrcpy session's
 * display holds none. And nothing else: "the display with the most visible tasks", the
 * old fallback, is any display at all once the desktop's own has gone. A reconnect
 * handed CaptionService the PC's app windows, or another mirroring app's display, as
 * the desktop that way ("desktop display = 48" in a user's trace, issue #11).
 */
final class DesktopDisplay {

    private DesktopDisplay() {
    }

    /** One root task as the rule reads it: its display, and its top and root activities as "pkg/cls". */
    static final class Task {
        final int display;
        final String top;
        final String root;

        Task(int display, String top, String root) {
            this.display = display;
            this.top = top;
            this.root = root;
        }
    }

    /**
     * The desktop's display among {@code tasks}, or -1.
     *
     * @param desktop  the desktop activity, as "pkg/cls"
     * @param lastSeen the display this last found the desktop on, or -1
     */
    static int pick(List<Task> tasks, String desktop, int lastSeen) {
        for (Task t : tasks) {
            if (t.display != 0 && (desktop.equals(t.top) || desktop.equals(t.root))) return t.display;
        }
        if (lastSeen > 0) {
            for (Task t : tasks) {
                if (t.display == lastSeen) return lastSeen;
            }
        }
        return -1;
    }
}
