package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A PNG of the whole desktop, in the phone's own screenshot folder.
 *
 * <p>The capture is {@link AccessibilityService#takeScreenshot}, through
 * {@link CaptionService}. Two nearer-looking routes are not routes at all:
 *
 * <ul>
 *   <li><b>MediaProjection</b> — what {@link WebService} already holds — mirrors
 *       the display it was consented for, which is the phone's own panel. The
 *       desktop is a SECONDARY display, and a projection cannot be pointed at
 *       one. {@code takeScreenshot} is the only public API that takes a display
 *       id, and the desktop's is exactly what {@link CaptionService} tracks.
 *   <li><b>screencap over the window daemon</b> would work — uid 2000 can run
 *       it — but the daemon's protocol is line-oriented ASCII, so the image
 *       would have to come back base64 or through a file two uids can both
 *       reach; and {@code screencap -d} wants SurfaceFlinger's display TOKEN
 *       ("11529215047514294217"), not the logical id anything else here uses,
 *       which would mean parsing {@code dumpsys SurfaceFlinger} and guessing
 *       which leftover virtual display is the live one. This hands us a
 *       Bitmap in our own process instead.
 * </ul>
 *
 * <p>Nothing here needs a storage permission: an app may always insert its own
 * image into {@link MediaStore}, and inserting is what puts the file in the
 * Gallery — a PNG written to the folder by hand is invisible until something
 * scans it.
 */
final class DexShot {

    private DexShot() {}

    /**
     * One thread, kept for the life of the process.
     *
     * It is both the callback's executor and where the PNG is compressed —
     * ~1920×1080 of ARGB is tens of milliseconds of deflate that must not be
     * the desktop's UI thread. The system delivers the buffer wherever it is
     * told to, so there is no second hop.
     */
    private static final Executor IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dex-shot");
        t.setDaemon(true);
        return t;
    });

    /**
     * Capture the desktop display and save it.
     *
     * Everything the user hears about is a toast, because the alternative is a
     * dialog in front of the thing they just photographed.
     */
    static void take(Context ctx) {
        Context app = ctx.getApplicationContext();
        CaptionService service = CaptionService.live();
        int display = CaptionService.desktopDisplay();
        // takeScreenshot is API 30. Below that there is no display-addressable
        // capture at all, so the tile says so rather than failing quietly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || service == null || display < 0) {
            say(app, app.getString(R.string.lx_shot_unavailable));
            return;
        }
        service.takeScreenshot(display, IO, new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult shot) {
                String name = null;
                // The buffer is the system's, on loan: close it whatever
                // happens, and copy out of it before it goes.
                try (HardwareBuffer buffer = shot.getHardwareBuffer()) {
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, shot.getColorSpace());
                    if (wrapped != null) {
                        Bitmap soft = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                        wrapped.recycle();
                        if (soft != null) {
                            name = save(app, soft);
                            soft.recycle();
                        }
                    }
                } catch (Exception e) {
                    DexLog.warn("shot", "could not save the capture", e);
                }
                if (name == null) {
                    say(app, app.getString(R.string.lx_shot_failed));
                    return;
                }
                DexLog.step("shot", "desktop display " + display + " saved as " + name);
                say(app, app.getString(R.string.lx_shot_saved));
            }

            @Override
            public void onFailure(int error) {
                DexLog.warn("shot", "takeScreenshot refused display " + display
                        + " with error " + error);
                say(app, app.getString(R.string.lx_shot_failed));
            }
        });
    }

    /**
     * Into Pictures/Screenshots, where the phone puts its own.
     *
     * @return the file name it was given, or null if the store would not take it.
     */
    private static String save(Context ctx, Bitmap bitmap) {
        String name = "DeX_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".png";
        ContentResolver cr = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Screenshots");
        // Pending until the bytes are in: a half-written PNG that the Gallery
        // has already indexed is a broken thumbnail forever.
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    cr.delete(uri, null, null);
                    return null;
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            return name;
        } catch (Exception e) {
            DexLog.warn("shot", "MediaStore would not take the screenshot", e);
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    /** A toast from whichever thread happens to be holding the news. */
    private static void say(Context ctx, String text) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
    }
}
