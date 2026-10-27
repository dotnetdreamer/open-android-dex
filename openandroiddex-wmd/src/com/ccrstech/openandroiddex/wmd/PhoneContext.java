package com.ccrstech.openandroiddex.wmd;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * A Context for a process that is not an app.
 *
 * The app windows' server ({@link AppServer}) talks to the display, clipboard
 * and package services through the framework's public classes —
 * DisplayManager, ClipboardManager, PackageManager — rather than through their
 * binder interfaces, which the rest of this daemon reflects into. The reason is
 * the one thing those services do more than any other: change their binder
 * signatures from release to release. Each release's own copy of the public class already speaks that
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
        if (instance == null) {
            instance = new PhoneContext(systemContext());
            giveTheThreadAnApplication(instance);
        }
        return instance;
    }

    /**
     * The bare thread's Application: this context, as one. An adaptive icon —
     * every launcher icon since Android 8, and the platform's default one —
     * finds its mask through {@code currentActivityThread().getApplication()},
     * and a thread that has none makes every icon a null pointer
     * (AdaptiveIconDrawable's constructor, Android 12). Made on this context
     * rather than the system's, so anything that names the current app's
     * package (ActivityThread.currentOpPackageName) names the shell's, which is
     * uid 2000's — never "android", which is not.
     */
    private static void giveTheThreadAnApplication(Context base) {
        try {
            android.app.Application app = new android.app.Application();
            java.lang.reflect.Method attach = android.app.Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            attach.invoke(app, base);
            Class<?> at = Refl.cls("android.app.ActivityThread");
            Object thread = Refl.callStatic(at, "currentActivityThread", new Class<?>[]{});
            set(at, thread, "mInitialApplication", app);
        } catch (Throwable t) {
            System.out.println("apps: no Application for the thread (" + t + ") — icons will be missing");
        }
    }

    /** Its own lock, not the class's: get() must not wait behind the fonts loading. */
    private static final Object FONTS = new Object();
    private static boolean fontsLoaded;

    /**
     * The phone's fonts, which an app's process is handed as it starts and
     * app_process never is. Text measured or drawn without a typeface of its
     * own takes the default one, and with none loaded hwui aborts the process
     * ("src == nullptr && gDefaultTypeface == nullptr") — no catch stops that.
     * One UI's live Calendar icon measures today's date as it is drawn: asked
     * for it, the whole server went, its windows with it, and every icon after
     * Calendar's in the list never came.
     *
     * <p>Up to Android 11, Typeface's static block loads them, the first time
     * the class is touched. Later releases load them with a hidden
     * loadPreinstalledSystemFontMap, which the block now leaves to whoever
     * starts the process (ENABLE_LAZY_TYPEFACE_INITIALIZATION): called here,
     * once; where the block made a load too, a second costs only time. Before
     * the first icon is drawn, not at start, where a window may be waiting.
     */
    static void loadFonts() {
        synchronized (FONTS) {
            if (fontsLoaded) return;
            fontsLoaded = true;
            // Touched, the class runs its static block: all it takes up to Android 11.
            Typeface touched = Typeface.DEFAULT;
            if (Build.VERSION.SDK_INT < 31 && touched != null) return;
            long t0 = SystemClock.uptimeMillis();
            try {
                Refl.callStatic(Typeface.class, "loadPreinstalledSystemFontMap", new Class<?>[]{});
                AppServer.log("fonts loaded in " + (SystemClock.uptimeMillis() - t0) + " ms");
            } catch (Throwable t) {
                AppServer.log("no fonts (" + t + ") — an icon with text in it will stop the server");
            }
        }
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

    private android.content.ContentResolver resolver;

    /**
     * A content resolver that names the shell's package. The system context's
     * names "android", which a provider refuses from uid 2000 ("Given calling
     * package android does not match caller's uid 2000") — and a launcher
     * icon can read a setting while it is drawn: One UI's live Calendar and
     * Clock icons do.
     */
    @Override
    public synchronized android.content.ContentResolver getContentResolver() {
        if (resolver == null) {
            try {
                Class<?> at = Refl.cls("android.app.ActivityThread");
                Object thread = Refl.callStatic(at, "currentActivityThread", new Class<?>[]{});
                Constructor<?> make = Refl.cls("android.app.ContextImpl$ApplicationContentResolver")
                        .getDeclaredConstructor(Context.class, at);
                make.setAccessible(true);
                resolver = (android.content.ContentResolver) make.newInstance(this, thread);
            } catch (Throwable t) {
                resolver = super.getContentResolver();
            }
        }
        return resolver;
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
                System.out.println("apps: no ConfigurationController (" + t + ") — fine off One UI");
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
