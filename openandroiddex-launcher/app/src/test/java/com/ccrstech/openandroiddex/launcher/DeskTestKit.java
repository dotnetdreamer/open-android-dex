package com.ccrstech.openandroiddex.launcher;

import static org.robolectric.Shadows.shadowOf;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the desktop's window tests share: a phone as the windows expect to
 * find it, a clock that moves, and a way to find things on screen.
 *
 * Run with {@code ./gradlew :app:testDebugUnitTest}. Robolectric builds the
 * real activities at a desktop's size; nothing here talks to a phone.
 */
final class DeskTestKit {

    private DeskTestKit() {
    }

    /** A 64-bit phone that has been up a while, with storage and All files access. */
    static void phone() {
        // Robolectric's clock starts near zero, where "10 s since the last
        // start" (LinuxActivity's START_RETRY_MS) is not yet true of a first one.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", new String[]{"arm64-v8a"});
        Context app = RuntimeEnvironment.getApplication();
        // No external storage until it is given one, and
        // Environment.isExternalStorageManager() indexes into its list.
        ShadowEnvironment.setExternalStorageDirectory(
                new File(app.getFilesDir().getParentFile(), "sdcard").toPath());
        ShadowEnvironment.addExternalDir("sdcard");
        AppOpsManager ops = app.getSystemService(AppOpsManager.class);
        shadowOf(ops).setMode("android:manage_external_storage",
                android.os.Process.myUid(), app.getPackageName(), AppOpsManager.MODE_ALLOWED);
    }

    /** A Linux container that finished setting up, so the Linux window opens on its desktop. */
    static void readyContainer(Context ctx) throws Exception {
        write(new File(ctx.getFilesDir(), "linux-apps"), "firefox code git nodejs");
        write(new File(Linux.root(ctx), "apps.done"), "firefox code git nodejs");
        write(new File(Linux.root(ctx), "state.env"), "VERSION=" + Linux.PAYLOAD_VERSION
                + "\nFEATURES=" + Linux.FEATURE_LEVEL + "\nPHASE=ready\nPCT=100\n");
        File rootfs = new File(Linux.root(ctx), "rootfs");
        for (String p : new String[]{"usr/bin/firefox", "opt/vscode/bin/code", "usr/bin/git", "usr/bin/node"}) {
            write(new File(rootfs, p), "");
        }
        write(new File(Linux.root(ctx), ".stamp-vscode"), String.valueOf(Linux.FEATURE_LEVEL));
        File lib = new File(ctx.getFilesDir().getParentFile(), "lib");
        write(new File(lib, "libdexremote.so"), "");
        ctx.getApplicationInfo().nativeLibraryDir = lib.getAbsolutePath();
    }

    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Let posted work run and animations finish: the main looper's clock moves
     * {@code ms}. Not a wall-clock wait: in these tests the app's own classes
     * are instrumented, and their System.currentTimeMillis() is Robolectric's.
     */
    static void settle(long ms) throws InterruptedException {
        ShadowLooper main = shadowOf(Looper.getMainLooper());
        for (long t = 0; t < ms; t += 100) {
            main.idleFor(Duration.ofMillis(100));
            Thread.sleep(15);
        }
        main.idle();
    }

    /** Every view under {@code v}, itself included. */
    static List<View> all(View v) {
        List<View> out = new ArrayList<>();
        collect(v, out);
        return out;
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
}
