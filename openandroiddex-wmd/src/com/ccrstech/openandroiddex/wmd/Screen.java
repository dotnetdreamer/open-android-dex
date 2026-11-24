package com.ccrstech.openandroiddex.wmd;

import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's own panel, on and off.
 *
 * The desktop does not live on it. scrcpy creates the desktop's virtual display with
 * OWN_DISPLAY_GROUP and ALWAYS_UNLOCKED, which gives it a power state of its own and
 * keeps the keyguard off it, so the built-in panel can be dark while the desktop keeps
 * running at full speed. This is the mechanism for making it dark.
 *
 * It is here rather than in the launcher for the usual reason: SurfaceControl takes a
 * power mode only from a process with the shell's authority, which this one has and an
 * app uid never will. scrcpy's own MOD+o shortcut is the same call from the same uid.
 *
 * POWER_MODE_OFF is not sleep. The device stays interactive — no keyguard, no doze, no
 * always-on clock, and the desktop's input keeps flowing — which is what a desktop
 * session wants, and also why the panel comes back by itself if anything wakes the
 * device. We blank on request and do not re-assert; scrcpy made the same choice for
 * virtual-display sessions.
 */
final class Screen {

    /** android.view.SurfaceControl power modes. */
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    /** Whether WE are why the panel is dark. Only that is ours to undo — see restore(). */
    private static volatile boolean forcedOff = false;

    private static Class<?> displayControl;
    private static boolean displayControlResolved;

    private Screen() {
    }

    static boolean isOff() {
        return forcedOff;
    }

    /** Power every built-in panel. Throws {@link Refl.WmError} with a reportable reason. */
    static synchronized void power(boolean on) {
        Class<?> sc = Refl.cls("android.view.SurfaceControl");
        int mode = on ? POWER_MODE_NORMAL : POWER_MODE_OFF;
        // A held refresh rate has to be one the panel has shown before it goes dark,
        // or the desktop runs at whatever it showed last (see Refresh).
        if (!on) Refresh.beforePanelOff();
        for (IBinder token : tokens()) {
            Refl.callStatic(sc, "setDisplayPowerMode",
                    new Class<?>[]{IBinder.class, int.class}, token, mode);
        }
        forcedOff = !on;
        System.out.println("screen: panel " + (on ? "on" : "off"));
        System.out.flush();
    }

    /**
     * Undo a blank of ours, and only ours.
     *
     * A phone we darkened must not be left dark once the desktop is gone: the framework
     * still believes that panel is on, so the power button spends its first press turning
     * a display off that already is, and the user is left pressing it twice at a phone
     * that looks broken. The flag is what keeps this from fighting a screen that is off
     * for any other reason — asleep, locked, the user's own power button.
     *
     * Every exit has to call this by hand. A shutdown hook was the obvious answer and it
     * does not work: ART leaves SIGTERM to the kernel, so `pkill` takes this process
     * without running one (measured on SM-S938B). The callers are the watchdog, on both
     * its paths, and the PC ahead of the kill in adb.rs's restore_phone.
     */
    static void restore() {
        if (!forcedOff) return;
        try {
            power(true);
        } catch (Throwable t) {
            System.out.println("screen: could not restore the panel (" + t + ")");
            System.out.flush();
        }
    }

    /**
     * Tokens for the physical displays.
     *
     * Android 14 moved these two lookups out of SurfaceControl and into DisplayControl,
     * in services.jar. SurfaceControl is asked first regardless of version: which of the
     * two carries them is a per-build, per-OEM fact rather than a clean version boundary.
     */
    private static IBinder[] tokens() {
        List<Panel> panels = panels();
        IBinder[] tokens = new IBinder[panels.size()];
        for (int i = 0; i < tokens.length; i++) tokens[i] = panels.get(i).token;
        return tokens;
    }

    /** A physical display: SurfaceFlinger's id for it and its token. */
    static final class Panel {
        /** -1 before Android 10, which had no ids. */
        final long id;
        final IBinder token;

        Panel(long id, IBinder token) {
            this.id = id;
            this.token = token;
        }
    }

    /** The physical displays, the way {@link #tokens} finds them. */
    static List<Panel> panels() {
        Class<?> sc = Refl.cls("android.view.SurfaceControl");
        if (Refl.hasMethod(sc, "getPhysicalDisplayIds")) {
            List<Panel> panels = panelsFrom(sc);
            if (!panels.isEmpty()) return panels;
        }
        Class<?> dc = displayControl();
        if (dc != null) {
            List<Panel> panels = panelsFrom(dc);
            if (!panels.isEmpty()) return panels;
        }
        // Before Android 10 there was one display and one call that returned it.
        if (Refl.hasMethod(sc, "getBuiltInDisplay", int.class)) {
            IBinder token = (IBinder) Refl.callStatic(sc, "getBuiltInDisplay",
                    new Class<?>[]{int.class}, 0);
            if (token != null) {
                List<Panel> one = new ArrayList<>(1);
                one.add(new Panel(-1, token));
                return one;
            }
        }
        throw new Refl.WmError("no physical display token");
    }

    private static List<Panel> panelsFrom(Class<?> owner) {
        List<Panel> panels = new ArrayList<>();
        if (!Refl.hasMethod(owner, "getPhysicalDisplayIds")
                || !Refl.hasMethod(owner, "getPhysicalDisplayToken", long.class)) {
            return panels;
        }
        long[] ids;
        try {
            ids = (long[]) Refl.callStatic(owner, "getPhysicalDisplayIds", new Class<?>[]{});
        } catch (Throwable t) {
            return panels;
        }
        if (ids == null) return panels;
        for (long id : ids) {
            IBinder token = (IBinder) Refl.callStatic(owner, "getPhysicalDisplayToken",
                    new Class<?>[]{long.class}, id);
            if (token != null) panels.add(new Panel(id, token));
        }
        return panels;
    }

    /**
     * {@code com.android.server.display.DisplayControl}, loaded from services.jar.
     *
     * Not on any classpath we are launched with, and its methods are JNI into
     * libandroid_servers, which app_process does not load for us. Both halves have to be
     * arranged by hand, and loadLibrary0 has to be given the CLASS rather than a
     * ClassLoader so the library binds to the loader that class came from.
     */
    private static synchronized Class<?> displayControl() {
        if (displayControlResolved) return displayControl;
        displayControlResolved = true;
        try {
            ClassLoader loader = (ClassLoader) Refl.callStatic(
                    Refl.cls("com.android.internal.os.ClassLoaderFactory"), "createClassLoader",
                    new Class<?>[]{String.class, String.class, String.class, ClassLoader.class,
                            int.class, boolean.class, String.class},
                    "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> found = loader.loadClass("com.android.server.display.DisplayControl");
            Refl.call(Runtime.getRuntime(), "loadLibrary0", found, "android_servers");
            displayControl = found;
        } catch (Throwable t) {
            System.out.println("screen: no DisplayControl (" + t + ")");
            System.out.flush();
        }
        return displayControl;
    }
}
