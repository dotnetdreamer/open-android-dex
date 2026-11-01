package com.ccrstech.openandroiddex.launcher;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Files a computer dropped on one of the phone's apps in its window there
 * (doc/apps-on-pc.md, "Dropping files"), served to the app they were dropped
 * on: each one a content URI the drag carries ({@link PcDrags#arm}), readable
 * by whoever the drop grants it to — and by nobody else, as this provider is
 * not exported.
 *
 * <p>The shell's app server writes them, through {@link AppsProvider}
 * ({@code drop/<batch>/<i>/<name>}, opened for writing), into this app's
 * cache: the shell could not leave a file anywhere this app may read, nor this
 * app read one of the shell's. A batch stays a day after its drop, as the app
 * it went to may read it again while it sends it, and goes with the first drop
 * after that.
 *
 * <p>Which of a batch's files have been opened is kept ({@link #opened}):
 * Android says only that a drop was taken, not which of its files the app
 * took, and one it never opens — a chat taking the photos of a drop and not
 * the PDF with them — goes to Download as well.
 */
public class DropsProvider extends ContentProvider {

    static final String AUTHORITY = "com.ccrstech.openandroiddex.launcher.drops";
    private static final long KEEP_MS = 24 * 3600_000L;

    /** The files an app has opened, as "batch/i". Only this provider serves them, and this app opens none of them itself. */
    private static final Set<String> OPENED = new HashSet<>();

    static File root(Context ctx) {
        return new File(ctx.getCacheDir(), "drops");
    }

    /**
     * The file for {@code batch}, {@code i} and {@code name}, as the URI's path
     * says them: a batch and an index of letters, digits and dashes, a name
     * with no slash and no dots alone. Null for anything else, which could
     * otherwise point outside the folder.
     */
    static File file(Context ctx, String batch, String i, String name) {
        if (batch == null || i == null || name == null) return null;
        if (!batch.matches("[A-Za-z0-9-]{1,64}") || !i.matches("[0-9]{1,6}")) return null;
        if (name.isEmpty() || name.length() > 255 || name.contains("/") || name.contains("\0")
                || name.equals(".") || name.equals("..")) {
            return null;
        }
        return new File(new File(new File(root(ctx), batch), i), name);
    }

    static File file(Context ctx, List<String> path) {
        return path.size() == 3 ? file(ctx, path.get(0), path.get(1), path.get(2)) : null;
    }

    static Uri uri(String batch, int i, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(batch).appendPath(String.valueOf(i)).appendPath(name).build();
    }

    /**
     * What a drag of {@code files} ({@code [{i, name}]}, staged as
     * {@code batch}) carries: each one's URI and type. Null when none of them
     * is here.
     */
    static ClipData clip(Context ctx, String batch, JSONArray files) {
        List<Uri> uris = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        String label = null;
        for (int k = 0; files != null && k < files.length(); k++) {
            JSONObject f = files.optJSONObject(k);
            if (f == null) continue;
            String name = f.optString("name");
            File file = file(ctx, batch, String.valueOf(f.optInt("i", -1)), name);
            if (file == null || !file.isFile()) continue;
            uris.add(uri(batch, f.optInt("i"), name));
            String mime = mime(name);
            if (!mimes.contains(mime)) mimes.add(mime);
            if (label == null) label = name;
        }
        if (uris.isEmpty()) return null;
        if (uris.size() > 1) label = uris.size() + " files";
        ClipData clip = new ClipData(new ClipDescription(label, mimes.toArray(new String[0])), new ClipData.Item(uris.get(0)));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        return clip;
    }

    /** A file's type by its name, as Android's own pickers type it: anything unknown is plain bytes. */
    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    /**
     * Of {@code files} ({@code [{i, name}]}) in {@code batch}, the {@code i}
     * of each an app has opened, waiting up to {@code waitMs} for it to open
     * them all. Any thread but the main one.
     */
    static JSONArray opened(String batch, JSONArray files, long waitMs) throws InterruptedException {
        long until = SystemClock.uptimeMillis() + waitMs;
        synchronized (OPENED) {
            while (true) {
                JSONArray got = new JSONArray();
                int asked = 0;
                for (int k = 0; files != null && k < files.length(); k++) {
                    JSONObject f = files.optJSONObject(k);
                    if (f == null) continue;
                    asked++;
                    int i = f.optInt("i", -1);
                    if (OPENED.contains(batch + "/" + i)) got.put(i);
                }
                long left = until - SystemClock.uptimeMillis();
                if (got.length() == asked || left <= 0) return got;
                OPENED.wait(left);
            }
        }
    }

    /** A batch, gone: its app was not given it, or the server gave up on it. */
    static void forget(Context ctx, String batch) {
        if (batch == null || !batch.matches("[A-Za-z0-9-]{1,64}")) return;
        delete(new File(root(ctx), batch));
        unopen(batch);
    }

    /** Batches older than a day. */
    static void sweep(Context ctx) {
        File[] batches = root(ctx).listFiles();
        if (batches == null) return;
        long now = System.currentTimeMillis();
        for (File b : batches) {
            if (now - b.lastModified() <= KEEP_MS) continue;
            delete(b);
            unopen(b.getName());
        }
    }

    private static void unopen(String batch) {
        synchronized (OPENED) {
            OPENED.removeIf(k -> k.startsWith(batch + "/"));
        }
    }

    private static void delete(File f) {
        File[] inside = f.listFiles();
        if (inside != null) {
            for (File c : inside) delete(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = file(getContext(), uri.getPathSegments());
        if (f == null || !f.isFile()) return null;
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String c : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(c)) {
                names.add(c);
                values.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(c)) {
                names.add(c);
                values.add(f.length());
            }
        }
        MatrixCursor cursor = new MatrixCursor(names.toArray(new String[0]), 1);
        cursor.addRow(values.toArray());
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        List<String> path = uri.getPathSegments();
        return path.size() == 3 ? mime(path.get(2)) : null;
    }

    /**
     * Its one type, where it fits {@code filter}. A text field asks this before
     * it takes a drop as text, and reads nothing from a provider that names no
     * type (the default): it took the drop and put nothing in.
     */
    @Override
    public String[] getStreamTypes(Uri uri, String filter) {
        String mime = getType(uri);
        return mime != null && ClipDescription.compareMimeTypes(mime, filter) ? new String[]{mime} : null;
    }

    /** The file, for the app it was dropped on, which has it from now on ({@link #opened}). */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("a dropped file is only read");
        List<String> path = uri.getPathSegments();
        File f = file(getContext(), path);
        if (f == null || !f.isFile()) throw new FileNotFoundException(uri.toString());
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        synchronized (OPENED) {
            OPENED.add(path.get(0) + "/" + path.get(1));
            OPENED.notifyAll();
        }
        return pfd;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
