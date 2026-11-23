package com.ccrstech.openandroiddex.launcher;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A phone for {@link BrowserSettingsTest}: its clock, its storage, what was started, what is on screen. */
final class BrowserTestKit {

    private BrowserTestKit() {
    }

    static void phone() {
        // A phone has been up for a while. Robolectric's clock starts near
        // zero, where "10 s since the last start" (LinuxActivity's
        // START_RETRY_MS, measured from 0) is not yet true of a first start.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", new String[]{"arm64-v8a"});
        // Robolectric has no external storage until it is given one, and
        // Environment.isExternalStorageManager() indexes into its list.
        Context app = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageDirectory(
                new File(app.getFilesDir().getParentFile(), "sdcard").toPath());
        org.robolectric.shadows.ShadowEnvironment.addExternalDir("sdcard");
        android.app.AppOpsManager ops = app.getSystemService(android.app.AppOpsManager.class);
        shadowOf(ops).setMode("android:manage_external_storage",
                android.os.Process.myUid(), app.getPackageName(), android.app.AppOpsManager.MODE_ALLOWED);
    }

    /**
     * Let the poll threads deliver and animations run out: the clock moves
     * {@code ms}. Never a loop on System.currentTimeMillis(): in a class of
     * this package, instrumented for the shadows, that is Robolectric's
     * paused clock, and it would never end.
     */
    static void settle(long ms) throws InterruptedException {
        ShadowLooper main = shadowOf(Looper.getMainLooper());
        for (long t = 0; t < ms; t += 100) {
            main.idleFor(Duration.ofMillis(100));
            Thread.sleep(15);
        }
        main.idle();
    }

    static List<Intent> drainServices() {
        Application app = RuntimeEnvironment.getApplication();
        List<Intent> out = new ArrayList<>();
        Intent i;
        while ((i = shadowOf(app).getNextStartedService()) != null) out.add(i);
        return out;
    }

    /** The last word of each intent's action: "BROWSER_ON". */
    static List<String> actions(List<Intent> intents) {
        List<String> out = new ArrayList<>();
        for (Intent i : intents) {
            String a = i.getAction();
            out.add(a == null ? "(none)" : a.substring(a.lastIndexOf('.') + 1));
        }
        return out;
    }

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

    static View byDescription(View root, String desc) {
        for (View v : all(root)) {
            if (desc.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())
                    && v.isShown()) return v;
        }
        return null;
    }

    static boolean showsText(View root, String text) {
        for (View v : all(root)) {
            if (v instanceof TextView && v.isShown()
                    && text.contentEquals(((TextView) v).getText())) return true;
        }
        return false;
    }

    static List<String> texts(View root) {
        List<String> out = new ArrayList<>();
        for (View v : all(root)) {
            if (v instanceof TextView && v.isShown()) out.add(((TextView) v).getText().toString());
        }
        return out;
    }

    // ── pictures, when asked for: -PbrowserShots=<folder> ──

    /** A window as the screen shows it: PixelCopy (outline clips and all) when it works, else a software draw. */
    static Bitmap window(Window w) throws Exception {
        View decor = w.getDecorView();
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, decor.getWidth()), Math.max(1, decor.getHeight()),
                Bitmap.Config.ARGB_8888);
        try {
            final int[] result = {-1};
            PixelCopy.request(w, bmp, r -> result[0] = r, new Handler(Looper.getMainLooper()));
            for (int i = 0; i < 50 && result[0] == -1; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(20);
            }
            if (result[0] == PixelCopy.SUCCESS) return bmp;
        } catch (Throwable ignored) {
            // drawn in software below
        }
        decor.draw(new Canvas(bmp));
        return bmp;
    }

    static Bitmap window(Activity a) throws Exception {
        return window(a.getWindow());
    }

    /** A dialog over its activity: the activity, the platform's dim (0.6), the dialog in the middle. */
    static Bitmap overlay(Bitmap under, Bitmap dialog) {
        Bitmap out = under.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        c.drawColor(0x99000000);
        c.drawBitmap(dialog, (out.getWidth() - dialog.getWidth()) / 2f,
                (out.getHeight() - dialog.getHeight()) / 2f, null);
        return out;
    }

    static void save(Bitmap bmp, String name) throws Exception {
        String where = System.getProperty("browser.shots", "");
        if (where.isEmpty()) return;
        File dir = new File(where);
        dir.mkdirs();
        File f = new File(dir, name + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("picture: " + f + " " + bmp.getWidth() + "x" + bmp.getHeight());
    }
}
