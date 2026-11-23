package com.ccrstech.openandroiddex.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * The small pictures Files shows in its grid ({@link LinuxFiles}): a photo's,
 * a video's frame, a song's cover, a PDF's first page, made with the phone's
 * own decoders, so a HEIC from a camera shows here although no computer's
 * browser draws one. At most {@code size} pixels each way, decoded at about
 * that size rather than whole: a 50-megapixel photo costs a fraction of
 * itself. Two at a time ({@link LinuxFiles} holds the rest back), beside a
 * running desktop.
 */
final class LinuxFilesThumbs implements LinuxFiles.Thumbs {

    /** For the phone's own files: where Android's media index is told of uploads; null for Linux's files. */
    private final android.content.Context media;

    /** Linux's files: nothing of them is in Android's media index. */
    LinuxFilesThumbs() {
        this(null);
    }

    /** The phone's shared storage: uploads are told to Android's media index (MediaScannerConnection). */
    LinuxFilesThumbs(android.content.Context media) {
        this.media = media;
    }

    @Override
    public void added(File file) {
        if (media == null) return;
        android.media.MediaScannerConnection.scanFile(media.getApplicationContext(),
                new String[]{file.getAbsolutePath()}, null, null);
    }

    @Override
    public byte[] make(File file, String kind, int size) throws IOException {
        Bitmap b = null;
        try {
            switch (kind) {
                case "image":
                    b = image(file, size);
                    break;
                case "video":
                    b = video(file, size);
                    break;
                case "audio":
                    b = cover(file, size);
                    break;
                case "pdf":
                    b = pdf(file, size);
                    break;
                default:
                    return null;
            }
            if (b == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            // A picture with see-through parts (an icon, a screenshot's PNG) keeps them.
            boolean clear = b.hasAlpha() && "image".equals(kind);
            b.compress(clear ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } finally {
            if (b != null) b.recycle();
        }
    }

    private static Bitmap image(File f, int size) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            // Turned the way the camera held it, HEIC and AVIF too, the first frame of a GIF.
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, src) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    float k = Math.min(1f, (float) size / Math.max(1, Math.max(w, h)));
                    decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample(o.outWidth, o.outHeight, size);
        return fit(BitmapFactory.decodeFile(f.getPath(), d), size, rotation(f));
    }

    private static Bitmap video(File f, int size) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            Bitmap b = null;
            // A second in: past the black a film so often starts with.
            if (Build.VERSION.SDK_INT >= 27) {
                b = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size);
            }
            if (b == null) b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = r.getFrameAtTime();
            return fit(b, size, 0);
        } catch (RuntimeException e) {
            return null;
        } finally {
            release(r);
        }
    }

    /** The cover a song carries, if it has one. */
    private static Bitmap cover(File f, int size) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            byte[] pic = r.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample(o.outWidth, o.outHeight, size);
            return fit(BitmapFactory.decodeByteArray(pic, 0, pic.length, d), size, 0);
        } catch (RuntimeException e) {
            return null;
        } finally {
            release(r);
        }
    }

    /** The first page, on white, as a reader would show it. */
    private static Bitmap pdf(File f, int size) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(fd)) {
            if (pdf.getPageCount() == 0) return null;
            try (PdfRenderer.Page page = pdf.openPage(0)) {
                float k = (float) size / Math.max(1, Math.max(page.getWidth(), page.getHeight()));
                Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * k)),
                        Math.max(1, Math.round(page.getHeight() * k)), Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            }
        } catch (IOException | RuntimeException e) {
            // a PDF with a password, or not a PDF at all
            return null;
        }
    }

    /** The power of two that decodes no smaller than {@code size}: the decoder's own cheap way down. */
    private static int sample(int w, int h, int size) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= size) s *= 2;
        return s;
    }

    private static int rotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Down to {@code size} each way at most, and turned upright; the original let go. */
    private static Bitmap fit(Bitmap b, int size, int degrees) {
        if (b == null) return null;
        float k = Math.min(1f, (float) size / Math.max(b.getWidth(), b.getHeight()));
        if (k >= 1f && degrees == 0) return b;
        Matrix m = new Matrix();
        m.postScale(k, k);
        if (degrees != 0) m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        return out;
    }

    private static void release(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (Exception ignored) {
            // released already
        }
    }
}
