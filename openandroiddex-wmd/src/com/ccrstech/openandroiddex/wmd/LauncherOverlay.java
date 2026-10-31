package com.ccrstech.openandroiddex.wmd;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * The launcher let draw over other apps, while something of this server's
 * needs it: the phone's touchpad, which is an overlay ({@link AppPad}), and,
 * from Android 17, the drag catchers on the windows' displays ({@link
 * AppDrags}), which are overlays there.
 *
 * <p>Given by whichever needs it first, unless the launcher has it already (a
 * desktop session gives it too, for its taskbar); taken back when the last
 * need goes, unless a desktop is running then — its taskbar is an overlay as
 * well. Who gave it is kept on the phone, not in this process: a server that
 * dies with it given (Android 17's fonts end one at its first live Calendar
 * icon) used to leave it given for good, the next server taking it for the
 * launcher's own.
 */
final class LauncherOverlay {

    /** This server gave it, in this run or an earlier one, and it is not back yet. */
    private static final File GIVEN = new File("/data/local/tmp/oadx-overlay-given");

    private static final Set<String> needs = new HashSet<>();

    private LauncherOverlay() {
    }

    /** {@code who} needs the launcher to draw over other apps: true if it may now. */
    static synchronized boolean need(String who) {
        needs.add(who);
        if (allowed()) return true;
        String out = AppTasks.exec(5, "cmd", "appops", "set", WmDaemon.LAUNCHER, "SYSTEM_ALERT_WINDOW", "allow");
        if (!allowed()) {
            AppServer.log("the launcher was not let draw over other apps, for " + who + " (" + out.trim() + ")");
            return false;
        }
        try {
            if (!GIVEN.exists() && !GIVEN.createNewFile()) {
                AppServer.log("the launcher's overlay app-op was given, but not marked as given here");
            }
        } catch (Exception e) {
            AppServer.log("the launcher's overlay app-op was given, but not marked as given here (" + e + ")");
        }
        AppServer.log("the launcher may draw over other apps now, for " + who);
        return true;
    }

    /**
     * {@code who} no longer needs it. With nothing else needing it, it is taken
     * back — if this server gave it, and no desktop ({@code desktop}) is
     * running on it.
     */
    static synchronized void release(String who, boolean desktop) {
        if (!needs.remove(who) || !needs.isEmpty()) return;
        if (desktop || !GIVEN.exists()) return;
        AppTasks.exec(5, "cmd", "appops", "set", WmDaemon.LAUNCHER, "SYSTEM_ALERT_WINDOW", "default");
        if (!GIVEN.delete()) AppServer.log("the launcher's overlay app-op is back, but still marked as given here");
        AppServer.log("the launcher's drawing over other apps is taken back: nothing here needs it");
    }

    private static boolean allowed() {
        String out = AppTasks.exec(5, "cmd", "appops", "get", WmDaemon.LAUNCHER, "SYSTEM_ALERT_WINDOW");
        return out.contains("SYSTEM_ALERT_WINDOW: allow");
    }
}
