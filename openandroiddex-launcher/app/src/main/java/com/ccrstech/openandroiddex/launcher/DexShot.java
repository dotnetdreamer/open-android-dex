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
     * How the desktop learns what happened, in the two beats it happens in.
     *
     * Both are delivered on the main thread. They are separate because the
     * FLASH must not wait for the PNG: compressing ~1920×1080 of ARGB is tens
     * of milliseconds, and a shutter that lands a tenth of a second after the
     * shutter moment reads as lag rather than as confirmation.
     */
    interface Result {

        /** The system has the pixels. Nothing is saved yet. */
        void captured();

        /**
         * The PNG is in MediaStore, or it is not.
         *
         * @param thumb a downscaled copy for the preview, or null on failure
         * @param uri   the row it went into, or null on failure
         */
        void saved(Bitmap thumb, Uri uri);
    }

    /**
     * Long edge of the preview copy, in pixels.
     *
     * Fixed rather than derived from the card's dp size: this runs before
     * anything here knows what density the desktop is at, and 640 px is more
     * than any preview at any preset asks for while costing under a megabyte
     * of the four seconds it is on screen.
     */
    private static final int THUMB_PX = 640;

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
    static void take(Context ctx, Result out) {
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
                // First beat, before any work: the pixels are taken, so the
                // desktop may flash. Nothing raised from here can land in the
                // image any more.
                if (out != null) main(out::captured);
                String name = stamp();
                Uri uri = null;
                Bitmap thumb = null;
                // The buffer is the system's, on loan: close it whatever
                // happens, and copy out of it before it goes.
                try (HardwareBuffer buffer = shot.getHardwareBuffer()) {
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, shot.getColorSpace());
                    if (wrapped != null) {
                        Bitmap soft = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                        wrapped.recycle();
                        if (soft != null) {
                            // Scaled BEFORE the PNG: `soft` is the only full
                            // copy there is and it is recycled a line later.
                            thumb = thumb(soft);
                            uri = save(app, soft, name);
                            soft.recycle();
                        }
                    }
                } catch (Exception e) {
                    DexLog.warn("shot", "could not save the capture", e);
                }
                if (uri == null) {
                    if (thumb != null) thumb.recycle();
                    say(app, app.getString(R.string.lx_shot_failed));
                    if (out != null) main(() -> out.saved(null, null));
                    return;
                }
                DexLog.step("shot", "desktop display " + display + " saved as " + name);
                if (out == null) {
                    // No desktop to show a preview on — the toast is all there
                    // is, even though it lands on the phone's own panel.
                    say(app, app.getString(R.string.lx_shot_saved));
                    return;
                }
                final Bitmap preview = thumb;
                final Uri saved = uri;
                main(() -> out.saved(preview, saved));
            }

            @Override
            public void onFailure(int error) {
                DexLog.warn("shot", "takeScreenshot refused display " + display
                        + " with error " + error);
                say(app, app.getString(R.string.lx_shot_failed));
                if (out != null) main(() -> out.saved(null, null));
            }
        });
    }

    /**
     * A copy small enough to hold on to while the preview is up.
     *
     * Never the capture itself: that bitmap is ~8 MB of ARGB and belongs to the
     * save path, which recycles it the moment the PNG is written.
     */
    private static Bitmap thumb(Bitmap full) {
        try {
            int longest = Math.max(full.getWidth(), full.getHeight());
            if (longest <= 0) return null;
            if (longest <= THUMB_PX) return full.copy(Bitmap.Config.ARGB_8888, false);
            float scale = (float) THUMB_PX / longest;
            return Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * scale)),
                    Math.max(1, Math.round(full.getHeight() * scale)), true);
        } catch (Exception e) {
            DexLog.warn("shot", "could not scale the preview", e);
            return null;
        }
    }

    /** The file name, which is also what the log calls this capture. */
    private static String stamp() {
        return "DeX_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".png";
    }

    /**
     * Into Pictures/Screenshots, where the phone puts its own.
     *
     * @return the MediaStore row it went into, or null if the store would not
     *         take it. The URI is ours — we inserted it — which is what lets
     *         the preview hand a readable image to a gallery app.
     */
    private static Uri save(Context ctx, Bitmap bitmap, String name) {
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
            return uri;
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
        main(() -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
    }

    /** Everything the desktop is told is told on its own thread. */
    private static void main(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
