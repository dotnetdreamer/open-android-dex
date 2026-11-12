package com.ccrstech.openandroiddex.wmd;

import java.util.Arrays;

/**
 * The window daemon's answer to DESKTOP (DesktopDisplay), on a computer's JVM, for
 * test/desktop-display-test.mjs: one line per check, "ok <name>" or
 * "FAIL <name> - <detail>".
 *
 * The task trees are the ones a Galaxy S25 Ultra (Android 17) showed while issue #11
 * was being fixed: the desktop on display 13, a second display of the same size, 15, a
 * Calculator window on the desktop and the Clock on the other display.
 */
public final class DesktopDisplayCheck {

    private static int failed;

    private static void check(String name, int got, int want) {
        if (got == want) {
            System.out.println("ok " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " - got " + got + ", want " + want);
        }
    }

    private static final String L = "com.ccrstech.openandroiddex.launcher";
    private static final String DESKTOP = L + "/" + L + ".LauncherActivity";
    private static final String SETTINGS = L + "/" + L + ".SettingsActivity";
    private static final String CALCULATOR =
            "com.sec.android.app.popupcalculator/com.sec.android.app.popupcalculator.Calculator";
    private static final String CLOCK =
            "com.sec.android.app.clockpackage/com.sec.android.app.clockpackage.ClockPackage";
    private static final String PERMISSION =
            "com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity";
    private static final String HOME =
            "com.sec.android.app.launcher/com.sec.android.app.launcher.activities.LauncherActivity";

    private static DesktopDisplay.Task task(int display, String top, String root) {
        return new DesktopDisplay.Task(display, top, root);
    }

    private static int pick(int lastSeen, DesktopDisplay.Task... tasks) {
        return DesktopDisplay.pick(Arrays.asList(tasks), DESKTOP, lastSeen);
    }

    public static void main(String[] args) {
        check("the desktop's own display, past a window on it and the other display's app",
                pick(-1, task(13, CALCULATOR, CALCULATOR), task(13, DESKTOP, DESKTOP),
                        task(15, CLOCK, CLOCK), task(0, HOME, HOME)), 13);
        check("the desktop with another app's activity on top of it, in its own task",
                pick(-1, task(13, PERMISSION, DESKTOP)), 13);
        check("the desktop drawn on the phone's own screen is not a display of its own",
                pick(-1, task(0, DESKTOP, DESKTOP)), -1);

        // The review's counterexample: no desktop, a window on the display it was last
        // seen on, the launcher's Settings on another one. "Any task of the launcher's"
        // answered 15.
        check("the launcher's Settings on another display is not the desktop",
                pick(13, task(13, CALCULATOR, CALCULATOR), task(15, SETTINGS, SETTINGS)), 13);
        check(".nor with nothing remembered", pick(-1, task(15, SETTINGS, SETTINGS)), -1);

        // The update gap, as measured on the phone: the launcher force-stopped with the
        // Calculator on 13 and the Clock on 15. "Most visible tasks" broke that tie
        // toward the newer display, 15.
        check("the update gap: the display the desktop was last seen on, while it holds a task",
                pick(13, task(13, CALCULATOR, CALCULATOR), task(15, CLOCK, CLOCK)), 13);
        check(".and no desktop once that display is empty, not the other display's app",
                pick(13, task(15, CLOCK, CLOCK), task(0, HOME, HOME)), -1);

        System.out.println("done " + failed);
    }
}
