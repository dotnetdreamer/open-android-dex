package com.ccrstech.openandroiddex.wmd;

import android.os.IBinder;
import android.os.SystemClock;

/**
 * The phone's own panel while the computer shows its apps: woken if it sleeps, kept
 * awake, and dark — what scrcpy's {@code --stay-awake --turn-screen-off} do for a
 * mirror.
 *
 * <p>Without it a phone that times out (or is asleep when the first window opens)
 * stops taking the computer's clicks: before Android 13 the windows' displays share
 * the phone's display group, and a sleeping group runs no input. So while any window
 * is open the default display is told someone is using it, a sleeping phone is
 * woken, and the built-in panel is blanked through {@link Screen} — which leaves the
 * phone awake, the windows running and their input flowing.
 *
 * <p>The panel is lit again with the last window, and with the server. A person's
 * power button while it is dark sleeps the phone (Android thinks the screen is on);
 * the next {@link #tick} wakes it and darkens it again.
 */
final class AppPanel {

    /** How long a sleeping phone gets to wake before its first window's display is made. */
    private static final long WAKE_WAIT_MS = 2000;
    /** And how long its screen then gets to finish coming on (scrcpy's POWER waits as long). */
    private static final long WAKE_SETTLE_MS = 500;
    /** android.view.KeyEvent.KEYCODE_WAKEUP: never POWER, so an awake phone is not put to sleep. */
    private static final String KEYCODE_WAKEUP = "224";

    private final Object power;
    /** Windows are open: the phone is kept awake. */
    private volatile boolean wanted;
    /** The computer's choice: the panel goes dark while they are. Awake and woken either way. */
    private volatile boolean dark = true;
    private boolean said;

    AppPanel() {
        Object p = null;
        try {
            IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                    "getService", new Class<?>[]{String.class}, "power");
            if (b != null) {
                p = Refl.callStatic(Refl.cls("android.os.IPowerManager$Stub"),
                        "asInterface", new Class<?>[]{IBinder.class}, b);
            }
        } catch (Throwable t) {
            AppServer.log("panel: no power service (" + t.getMessage() + ")");
        }
        power = p;
    }

    /** A window is opening: wake the phone if it sleeps, then darken the panel if the computer wants it dark. */
    void windowOpened(boolean wantDark) {
        synchronized (this) {
            dark = wantDark;
            wanted = true;
            wakeIfAsleep();
            if (dark) darken();
            else lighten("the screen is not to be turned off");
        }
    }

    /** The computer changed its choice while windows may be open. */
    void setDark(boolean wantDark) {
        synchronized (this) {
            if (dark == wantDark) return;
            dark = wantDark;
            if (!wanted) return;
            if (dark) darken();
            else lighten("the screen is not to be turned off");
        }
    }

    /** The last window has closed, or the server is stopping: the panel is the phone's again. */
    void windowsClosed(String why) {
        synchronized (this) {
            wanted = false;
            lighten(why);
        }
    }

    private void lighten(String why) {
        if (!Screen.isOff()) return;
        try {
            Screen.power(true);
            AppServer.log("panel: lit again (" + why + ")");
        } catch (Throwable t) {
            AppServer.log("panel: could not light it again (" + t + ")");
        }
    }

    /** Every few seconds while windows are open: awake, and dark. */
    void tick() {
        if (!wanted || power == null) return;
        synchronized (this) {
            if (!wanted) return;
            try {
                if (!interactive()) {
                    wakeIfAsleep();
                    darken();
                }
                long now = SystemClock.uptimeMillis();
                if (Refl.hasMethod(power.getClass(), "userActivity", int.class, long.class, int.class, int.class)) {
                    Refl.callSig(power, "userActivity",
                            new Class<?>[]{int.class, long.class, int.class, int.class}, 0, now, 0, 0);
                } else {
                    Refl.callSig(power, "userActivity",
                            new Class<?>[]{long.class, int.class, int.class}, now, 0, 0);
                }
            } catch (Throwable t) {
                if (!said) {
                    said = true;
                    AppServer.log("panel: cannot keep the phone awake (" + t.getMessage() + ")");
                }
            }
        }
    }

    private boolean interactive() {
        if (power == null) return true;
        try {
            return Boolean.TRUE.equals(Refl.callSig(power, "isInteractive", new Class<?>[]{}));
        } catch (Throwable t) {
            return true;
        }
    }

    private void wakeIfAsleep() {
        if (interactive()) return;
        try {
            new ProcessBuilder("input", "keyevent", KEYCODE_WAKEUP).redirectErrorStream(true).start().waitFor();
        } catch (Throwable t) {
            AppServer.log("panel: could not wake the phone (" + t + ")");
            return;
        }
        long deadline = SystemClock.uptimeMillis() + WAKE_WAIT_MS;
        while (!interactive() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20);
        }
        if (interactive()) {
            SystemClock.sleep(WAKE_SETTLE_MS);
            AppServer.log("panel: the phone was asleep, woken first");
        } else {
            AppServer.log("panel: the phone was asleep and did not wake within " + WAKE_WAIT_MS + " ms");
        }
    }

    private void darken() {
        if (!dark || Screen.isOff()) return;
        try {
            Screen.power(false);
            AppServer.log("panel: dark, the phone stays awake");
        } catch (Throwable t) {
            AppServer.log("panel: could not darken it (" + t + ")");
        }
    }
}
