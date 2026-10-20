package com.ccrstech.openandroiddex.wmd;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * A Context for a process that is not an app.
 *
 * The desktop's own stream ({@link Desktop}) talks to the display and clipboard
 * services through the framework's public classes — DisplayManager,
 * ClipboardManager — rather than through their binder interfaces, which the
 * rest of this daemon reflects into. The reason is the one thing those two
 * services do more than any other: change their binder signatures from release
 * to release. Each release's own copy of the public class already speaks that
 * release's signature; it only wants a Context, and app_process has none — no
 * Application, no ActivityThread.
 *
 * So one is made: a bare ActivityThread, made the current one, and its system
 * context — wrapped to name the shell's package. Every service checks that the
 * package a call names belongs to the uid making it, and com.android.shell is
 * uid 2000's.
 */
final class PhoneContext extends ContextWrapper {

    static final String SHELL_PACKAGE = "com.android.shell";

    private static PhoneContext instance;

    static synchronized PhoneContext get() {
        if (instance == null) instance = new PhoneContext(systemContext());
        return instance;
    }

    private PhoneContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    /** Android 12 and later: who a service call is from, carried with it. */
    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build();
    }

    /** Android 14 and later: which device a call comes from — 0 is the phone itself, not a virtual one. */
    @Override
    public int getDeviceId() {
        return 0;
    }

    private static Context systemContext() {
        Class<?> at = Refl.cls("android.app.ActivityThread");
        Object thread;
        try {
            Constructor<?> make = at.getDeclaredConstructor();
            make.setAccessible(true);
            thread = make.newInstance();
            set(at, null, "sCurrentActivityThread", thread);
            set(at, thread, "mSystemThread", true);
        } catch (Throwable t) {
            throw new Refl.WmError("no ActivityThread: " + t);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            // One UI looks a display up through the current thread's configuration
            // (DisplayManagerGlobal → ActivityThread.getConfiguration), and a bare
            // thread has no ConfigurationController to answer with: making a
            // display then dies in a null pointer. Found by scrcpy (its issue
            // 4467); the class exists from Android 12 on.
            try {
                Class<?> controller = Refl.cls("android.app.ConfigurationController");
                Constructor<?> make = controller.getDeclaredConstructor(Refl.cls("android.app.ActivityThreadInternal"));
                make.setAccessible(true);
                set(at, thread, "mConfigurationController", make.newInstance(thread));
            } catch (Throwable t) {
                System.out.println("desktop: no ConfigurationController (" + t + ") — fine off One UI");
            }
        }
        return (Context) Refl.callSig(thread, "getSystemContext", new Class<?>[]{});
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
