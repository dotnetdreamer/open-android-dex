package com.ccrstech.openandroiddex.wmd;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's newest photos and screenshots, for the computer's Photos tab and
 * its "Keep a copy" (doc/send-to-computer.md, "The photos are asked for").
 *
 * <p>The shell reads them from the media store, reached as an external
 * provider as {@code content query} reaches it, and needs nothing installed.
 * It is <b>not told</b> of a new picture: a content observer registers from
 * this process and is never called (measured on Android 15 and 17). So it
 * asks — the newest {@code limit} ids, one query of a millisecond or two, every
 * {@code every} ms while the computer wants it — and inotify on the folders the
 * last look saw is only a nudge to ask now rather than at the next tick. From
 * Android 11 a look first asks the store's generation, and reads the list only
 * once it has moved, or every {@link #FULL_LOOK_MS}; the total is counted again
 * only once it has moved.
 *
 * <p>What counts: images under {@code DCIM/} or {@code Pictures/Screenshots/},
 * published and not empty — the camera and the screenshots of a Samsung or a
 * Pixel. No videos (the wire's {@code kind} leaves room for them). Newest by
 * when the picture appeared on the phone ({@code date_added}).
 *
 * <p>Thumbnails are the phone's own (its decoders, the camera's embedded
 * preview), at most {@link #THUMB_PX} on the long side; an original is sent on
 * a {@code photo} connection of its own ({@link #original}).
 */
final class AppPhotos {

    static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    static final int THUMB_PX = 512;
    private static final int THUMB_QUALITY = 78;
    private static final int LIMIT_MAX = 1000;
    /** inotify asks for a look at most this often. */
    private static final long NUDGE_MS = 150;
    /**
     * The list is read at least this often, whatever the media store's
     * generation says: in between, a look whose generation has not moved reads
     * nothing (a large library's list, ordered by when each picture appeared,
     * reads every image row).
     */
    static final long FULL_LOOK_MS = 10_000;

    private static final String[] COLUMNS_Q = {"_id", "_display_name", "relative_path", "bucket_display_name", "_data",
            "date_added", "date_modified", "datetaken", "_size", "width", "height", "mime_type"};
    private static final String[] COLUMNS_OLD = {"_id", "_display_name", "_data", "bucket_display_name", "_data",
            "date_added", "date_modified", "datetaken", "_size", "width", "height", "mime_type"};

    /** One picture. */
    static final class Row {
        long id;
        String name;
        String folder;
        String album;
        String data;
        long added;
        long modified;
        long taken;
        long size;
        int w;
        int h;
        String mime;

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("kind", "image").put("name", name).put("album", album)
                    .put("path", folder + name).put("taken", taken).put("added", added).put("modified", modified)
                    .put("size", size).put("w", w).put("h", h).put("mime", mime);
        }

        /** Its name, folder, size or time changed: the computer hears it again. */
        boolean same(Row o) {
            return name.equals(o.name) && folder.equals(o.folder) && size == o.size && modified == o.modified;
        }

        /** Newer than {@code o} in the order the page shows: when it appeared, then its id. */
        boolean after(Row o) {
            return added != o.added ? added > o.added : id > o.id;
        }
    }

    private final Wire wire;
    private final HandlerThread thread;
    private final Handler h;
    /** Thumbnails: off the look's thread, which they would hold up. */
    private final ExecutorService asked = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "photos-asked");
        t.setDaemon(true);
        return t;
    });
    /**
     * What the computer waits on — pages of the list, the status — on a lane of
     * their own: never behind a page of thumbnails. Each answer gives back the
     * number its asking carried ({@code ask}), which is how the computer knows it.
     */
    private final ExecutorService answers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "photos-answers");
        t.setDaemon(true);
        return t;
    });
    // On the look's thread only.
    private boolean on;
    private int limit = 200;
    private long every = 1000;
    private LinkedHashMap<Long, Row> last;
    private final Map<String, FileObserver> watching = new HashMap<>();
    private long nudged;
    /** The media store's generation when the list was last read (-1: unknown), and when that was. */
    private long seenGeneration = -1;
    private long readAt;
    /** The total and the generation it was counted at: counted again only once something changed. */
    private long[] lastTotal;
    private long lastTotalGeneration = -1;
    private volatile boolean stopped;
    private final Runnable look = this::look;
    /** The nudge's second look, for a save still settling. */
    private final Runnable settled = this::look;

    AppPhotos(Wire wire) {
        this.wire = wire;
        thread = new HandlerThread("photos-look");
        thread.start();
        h = new Handler(thread.getLooper());
    }

    /** A message on the link. */
    void handle(JSONObject j) {
        switch (j.optString("t")) {
            case "photos":
                h.post(() -> photos(j));
                break;
            case "older":
                answers.execute(() -> older(j));
                break;
            case "all":
                answers.execute(() -> all(j));
                break;
            case "thumbs": {
                JSONArray ids = j.optJSONArray("ids");
                asked.execute(() -> {
                    for (int i = 0; ids != null && i < ids.length() && !stopped; i++) thumb(ids.optLong(i));
                });
                break;
            }
            case "status":
                answers.execute(() -> status(j));
                break;
            default:
        }
    }

    void stop() {
        stopped = true;
        h.post(() -> {
            on = false;
            unwatch();
            thread.quitSafely();
        });
        asked.shutdownNow();
        answers.shutdownNow();
    }

    /** {@code say}, as the answer to {@code asking}: with the number it carried. */
    private static JSONObject answer(JSONObject asking, JSONObject say) throws org.json.JSONException {
        return say.put("ask", asking.opt("ask"));
    }

    // ── the look ──

    private void photos(JSONObject j) {
        boolean was = on && last != null;
        on = j.optBoolean("on", true);
        h.removeCallbacks(look);
        if (!on) {
            unwatch();
            last = null;
            AppServer.log("photos: no longer looking");
            return;
        }
        int newLimit = Math.max(1, Math.min(LIMIT_MAX, j.optInt("limit", 200)));
        long newEvery = Math.max(250, j.optLong("every", 1000));
        if (was && newLimit == limit) {
            // Only how often (a window hidden, or shown again): the looks go on from what the last one saw,
            // and the computer's list — older pages and all — stands. No new snapshot.
            every = newEvery;
            AppServer.log("photos: looking every " + every + " ms");
            h.postDelayed(look, Math.min(every, 250));
            return;
        }
        limit = newLimit;
        every = newEvery;
        try {
            long t0 = SystemClock.uptimeMillis();
            // Read before the list: a change made while it is read is seen by the next look.
            long g = generation();
            List<Row> rows = newest(limit + 1);
            boolean more = rows.size() > limit;
            if (more) rows = rows.subList(0, limit);
            last = byId(rows);
            seenGeneration = g;
            readAt = t0;
            // Counted again only once something changed: a link made again costs no walk of every row.
            long[] total = g >= 0 && g == lastTotalGeneration && lastTotal != null ? lastTotal : total();
            lastTotal = total;
            lastTotalGeneration = g;
            JSONArray items = new JSONArray();
            for (Row r : rows) items.put(r.json());
            say(new JSONObject().put("t", "photos").put("kind", "snapshot").put("items", items).put("more", more)
                    .put("total", new JSONObject().put("count", total[0]).put("bytes", total[1]))
                    .put("now", System.currentTimeMillis() / 1000));
            AppServer.log("photos: " + rows.size() + " of " + total[0] + " listed in " + (SystemClock.uptimeMillis() - t0)
                    + " ms; looking every " + every + " ms");
            watch(rows);
        } catch (Exception e) {
            AppServer.log("photos: the media store would not say (" + e + ")");
            say(failed(e));
        }
        h.postDelayed(look, every);
    }

    private void look() {
        if (!on || stopped) return;
        h.removeCallbacks(look);
        long g = generation();
        long t0 = SystemClock.uptimeMillis();
        if (last != null && !mustRead(g, seenGeneration, t0 - readAt)) {
            h.postDelayed(look, every);
            return;
        }
        try {
            List<Row> rows = newest(limit);
            seenGeneration = g;
            readAt = t0;
            LinkedHashMap<Long, Row> now = byId(rows);
            Row oldest = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            boolean full = rows.size() >= limit;
            List<Row> fresh = new ArrayList<>();
            for (Row r : rows) {
                Row was = last == null ? null : last.get(r.id);
                if (was == null || !was.same(r)) fresh.add(r);
            }
            JSONArray gone = new JSONArray();
            if (last != null) {
                for (Row was : last.values()) {
                    if (now.containsKey(was.id)) continue;
                    // One only pushed below the newest `limit` by newer ones is older, not gone.
                    if (full && oldest != null && !was.after(oldest)) continue;
                    gone.put(was.id);
                }
            }
            last = now;
            if (!fresh.isEmpty()) {
                JSONArray items = new JSONArray();
                for (Row r : fresh) items.put(r.json());
                say(new JSONObject().put("t", "photos").put("kind", "new").put("items", items));
                watch(rows);
                // Their thumbnails straight after, newest first: what the page shows next.
                for (Row r : fresh) asked.execute(() -> thumb(r.id));
            }
            if (gone.length() > 0) say(new JSONObject().put("t", "photos").put("kind", "gone").put("ids", gone));
        } catch (Exception e) {
            AppServer.log("photos: a look failed (" + e + ")");
        }
        h.postDelayed(look, every);
    }

    /** A folder changed: a look now, at most one each {@link #NUDGE_MS}, and one more soon after for a save still settling. */
    private void nudge() {
        h.post(() -> {
            if (!on) return;
            long now = SystemClock.uptimeMillis();
            if (now - nudged < NUDGE_MS) return;
            nudged = now;
            h.removeCallbacks(look);
            h.removeCallbacks(settled);
            h.post(look);
            // Its own Runnable: look() takes every queued `look` off the queue, which would take this too.
            h.postDelayed(settled, 400);
        });
    }

    /** inotify on each folder the last look saw, and the two a new screenshot lands in. */
    private void watch(List<Row> rows) {
        Set<String> dirs = new HashSet<>();
        for (Row r : rows) {
            if (r.data == null) continue;
            File parent = new File(r.data).getParentFile();
            if (parent != null) dirs.add(parent.getPath());
        }
        String root = "/storage/emulated/0/";
        for (String d : new String[]{"DCIM/Camera", "DCIM/Screenshots", "Pictures/Screenshots"}) {
            if (new File(root + d).isDirectory()) dirs.add(root + d);
        }
        for (java.util.Iterator<Map.Entry<String, FileObserver>> i = watching.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<String, FileObserver> e = i.next();
            if (!dirs.contains(e.getKey())) {
                e.getValue().stopWatching();
                i.remove();
            }
        }
        for (String d : dirs) {
            if (watching.containsKey(d) || watching.size() >= 32) continue;
            try {
                @SuppressWarnings("deprecation")
                FileObserver fo = new FileObserver(d, FileObserver.CREATE | FileObserver.CLOSE_WRITE
                        | FileObserver.MOVED_TO | FileObserver.MOVED_FROM | FileObserver.DELETE) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && !path.startsWith(".")) nudge();
                    }
                };
                fo.startWatching();
                watching.put(d, fo);
            } catch (RuntimeException ignored) {
                // a folder the shell may not watch: the once-a-second look still finds it
            }
        }
    }

    private void unwatch() {
        for (FileObserver fo : watching.values()) fo.stopWatching();
        watching.clear();
    }

    // ── pages of the list ──

    private void older(JSONObject j) {
        try {
            JSONObject b = j.optJSONObject("before");
            long added = b == null ? Long.MAX_VALUE : b.optLong("added");
            long id = b == null ? Long.MAX_VALUE : b.optLong("id");
            int n = Math.max(1, Math.min(LIMIT_MAX, j.optInt("limit", 200)));
            List<Row> rows = query("(date_added < " + added + " OR (date_added = " + added + " AND _id < " + id + "))",
                    "date_added DESC, _id DESC", n + 1);
            boolean more = rows.size() > n;
            if (more) rows = rows.subList(0, n);
            JSONArray items = new JSONArray();
            for (Row r : rows) items.put(r.json());
            say(answer(j, new JSONObject().put("t", "photos").put("kind", "older").put("items", items).put("more", more)));
        } catch (Exception e) {
            sayFailed(j, e);
        }
    }

    /** Every picture since {@code since}, oldest first, a page at a time: Keep a copy's list. */
    private void all(JSONObject j) {
        try {
            long since = j.optLong("since", 0);
            JSONObject a = j.optJSONObject("after");
            int n = Math.max(1, Math.min(LIMIT_MAX, j.optInt("limit", 500)));
            String where = "date_added >= " + since;
            if (a != null) {
                long added = a.optLong("added");
                long id = a.optLong("id");
                where += " AND (date_added > " + added + " OR (date_added = " + added + " AND _id > " + id + "))";
            }
            List<Row> rows = query(where, "date_added ASC, _id ASC", n + 1);
            boolean more = rows.size() > n;
            if (more) rows = rows.subList(0, n);
            JSONArray items = new JSONArray();
            for (Row r : rows) items.put(r.json());
            JSONObject say = new JSONObject().put("t", "photos").put("kind", "all").put("items", items);
            if (more && !rows.isEmpty()) {
                Row l = rows.get(rows.size() - 1);
                say.put("next", new JSONObject().put("added", l.added).put("id", l.id));
            }
            say(answer(j, say));
        } catch (Exception e) {
            sayFailed(j, e);
        }
    }

    private void status(JSONObject j) {
        try {
            BatteryManager bm = (BatteryManager) PhoneContext.get().getSystemService(Context.BATTERY_SERVICE);
            say(answer(j, new JSONObject().put("t", "status").put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("charging", bm.isCharging()).put("now", System.currentTimeMillis() / 1000)));
        } catch (Exception e) {
            try {
                say(answer(j, new JSONObject().put("t", "status").put("battery", -1).put("charging", true)
                        .put("now", System.currentTimeMillis() / 1000)));
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    /** A page that could not be read, said to its asker only. */
    private void sayFailed(JSONObject asking, Exception e) {
        try {
            say(answer(asking, failed(e)));
        } catch (org.json.JSONException ignored) {
        }
    }

    // ── a thumbnail ──

    private void thumb(long id) {
        try {
            Row r = one(id);
            if (r == null) {
                say(new JSONObject().put("t", "thumb").put("id", id).put("failed", true));
                return;
            }
            byte[] jpeg;
            try {
                jpeg = thumbnail(r);
            } catch (OutOfMemoryError e) {
                // A picture too big to decode here: skipped. An Error left to run would end the whole server.
                AppServer.log("photos: no memory for the thumbnail of " + id);
                jpeg = null;
            }
            if (jpeg == null) {
                say(new JSONObject().put("t", "thumb").put("id", id).put("modified", r.modified).put("failed", true));
                return;
            }
            say(new JSONObject().put("t", "thumb").put("id", id).put("modified", r.modified)
                    .put("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP)));
        } catch (Exception e) {
            AppServer.log("photos: no thumbnail for " + id + " (" + e + ")");
        }
    }

    /** A JPEG of {@code r}, at most {@link #THUMB_PX} on its long side, upright; null if the phone cannot decode it. */
    static byte[] thumbnail(Row r) {
        Bitmap b = null;
        try {
            if (Build.VERSION.SDK_INT >= 29 && r.data != null && new File(r.data).canRead()) {
                b = ThumbnailUtils.createImageThumbnail(new File(r.data), new android.util.Size(THUMB_PX, THUMB_PX), null);
            }
        } catch (IOException | RuntimeException ignored) {
            // the older way, below
        }
        if (b == null) b = decodeSmall(r);
        if (b == null) return null;
        // ThumbnailUtils keeps the shorter side at least the size asked for: a wide picture comes back wider.
        int longest = Math.max(b.getWidth(), b.getHeight());
        if (longest > THUMB_PX) {
            float k = THUMB_PX / (float) longest;
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            if (s != b) b.recycle();
            b = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out);
        b.recycle();
        return out.toByteArray();
    }

    /** Before Android 10, or where the file is not readable as a file: sampled down, turned upright by its EXIF. */
    private static Bitmap decodeSmall(Row r) {
        try (Content.Opened o = Content.open(Uri.withAppendedPath(IMAGES, String.valueOf(r.id)))) {
            byte[] all = readAll(o.in, 64L * 1024 * 1024);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(all, 0, all.length, bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(all, 0, all.length, o2);
            if (b == null) return null;
            int rot = 0;
            try {
                ExifInterface exif = new ExifInterface(new java.io.ByteArrayInputStream(all));
                switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                    default:
                }
            } catch (IOException ignored) {
            }
            float scale = Math.min(1f, THUMB_PX / (float) Math.max(b.getWidth(), b.getHeight()));
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rot);
            Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (out != b) b.recycle();
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, long most) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > most) throw new IOException("too big for a thumbnail");
        }
        return out.toByteArray();
    }

    // ── an original (a connection of its own) ──

    /**
     * {@code {"t":"photo","id","size","modified","offset"}}: the picture's bytes
     * from {@code offset}, raw, after a line that says what they are — or why
     * not: {@code gone}, {@code changed} (it is not what the computer was told;
     * it asks for the list again) or {@code unreadable}.
     */
    static void original(LocalSocket s, Wire wire, JSONObject req) {
        Thread.currentThread().setName("photos-original");
        long id = req.optLong("id", -1);
        try {
            Row r = one(id);
            if (r == null) {
                wire.json(refused("gone", "It is no longer on the phone."));
                return;
            }
            if (req.has("size") && (req.optLong("size") != r.size || req.optLong("modified") != r.modified)) {
                wire.json(refused("changed", "It changed on the phone."));
                return;
            }
            long offset = Math.max(0, Math.min(r.size, req.optLong("offset", 0)));
            InputStream in = null;
            Content.Opened opened = null;
            try {
                if (r.data != null && new File(r.data).canRead()) {
                    FileInputStream f = new FileInputStream(r.data);
                    f.getChannel().position(offset);
                    in = f;
                } else {
                    opened = Content.open(Uri.withAppendedPath(IMAGES, String.valueOf(r.id)));
                    in = opened.in;
                    for (long left = offset; left > 0; ) {
                        long n = in.skip(left);
                        if (n <= 0) throw new IOException("could not skip to " + offset);
                        left -= n;
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (in != null) in.close();
                if (opened != null) opened.close();
                wire.json(refused("unreadable", "The phone could not read it."));
                return;
            }
            try {
                wire.json(new JSONObject().put("t", "photo").put("ok", true).put("name", r.name).put("size", r.size)
                        .put("offset", offset).put("mime", r.mime));
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[256 * 1024];
                long sent = 0;
                long t0 = SystemClock.uptimeMillis();
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sent += n;
                }
                out.flush();
                AppServer.log("photos: sent " + id + " (" + sent + " bytes from " + offset + " in "
                        + (SystemClock.uptimeMillis() - t0) + " ms)");
            } finally {
                in.close();
                if (opened != null) opened.close();
            }
        } catch (Exception ignored) {
            // the computer went
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static JSONObject refused(String why, String message) {
        try {
            return new JSONObject().put("t", "photo").put("ok", false).put("why", why).put("message", message);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    // ── the media store ──

    private static Content.Source media;

    private static synchronized Content.Source media() throws IOException {
        if (media == null) media = Content.source(IMAGES.getAuthority());
        return media;
    }

    /** What counts: the camera's folders and the screenshots, published, not empty, never a hidden file. */
    static String counted() {
        if (Build.VERSION.SDK_INT >= 29) {
            return "(relative_path LIKE 'DCIM/%' OR relative_path LIKE 'Pictures/Screenshots/%')"
                    + " AND is_pending = 0 AND _size > 0 AND _display_name NOT LIKE '.%'";
        }
        return "(_data LIKE '%/DCIM/%' OR _data LIKE '%/Pictures/Screenshots/%') AND _size > 0 AND _display_name NOT LIKE '.%'";
    }

    private static List<Row> newest(int n) throws IOException {
        return query(null, "date_added DESC, _id DESC", n);
    }

    private static Row one(long id) throws IOException {
        List<Row> rows = query("_id = " + id, null, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** {@link #counted} rows, with {@code where} besides; {@code n} at most (0: all). */
    static List<Row> query(String where, String sort, int n) throws IOException {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, counted() + (where == null ? "" : " AND " + where));
        String order = sort == null ? "_id DESC" : sort;
        if (n > 0 && Build.VERSION.SDK_INT >= 30) args.putInt(ContentResolver.QUERY_ARG_LIMIT, n);
        else if (n > 0) order += " LIMIT " + n;
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
        boolean q = Build.VERSION.SDK_INT >= 29;
        List<Row> out = new ArrayList<>();
        Cursor c;
        try {
            c = media().query(IMAGES, q ? COLUMNS_Q : COLUMNS_OLD, args);
        } catch (IOException | RuntimeException e) {
            // The media store's process restarted: once more, freshly.
            synchronized (AppPhotos.class) {
                if (media != null) media.close();
                media = null;
            }
            c = media().query(IMAGES, q ? COLUMNS_Q : COLUMNS_OLD, args);
        }
        if (c == null) return out;
        try {
            while (c.moveToNext() && (n <= 0 || out.size() < n)) {
                Row r = new Row();
                r.id = c.getLong(0);
                r.name = str(c, 1);
                r.data = str(c, 4);
                r.folder = q ? str(c, 2) : folderOf(r.data, r.name);
                r.album = str(c, 3);
                if (r.album.isEmpty()) r.album = albumOf(r.folder);
                r.added = c.getLong(5);
                r.modified = c.getLong(6);
                long taken = c.isNull(7) ? 0 : c.getLong(7);
                r.taken = taken > 0 ? taken : r.added * 1000;
                r.size = c.getLong(8);
                r.w = c.getInt(9);
                r.h = c.getInt(10);
                r.mime = str(c, 11);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * The media store's generation (Android 11 and later): it moves with every
     * write the store makes — a picture added, changed or removed — so while it
     * stands, a look has nothing to read. -1 where the phone cannot say.
     */
    private static long generation() {
        if (Build.VERSION.SDK_INT < 30) return -1;
        try {
            return MediaStore.getGeneration(PhoneContext.get(), MediaStore.VOLUME_EXTERNAL);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Whether a look reads the list: the store's generation moved since it was
     * last read ({@code seen}), or cannot be known, or the last read is
     * {@link #FULL_LOOK_MS} old.
     */
    static boolean mustRead(long generation, long seen, long sinceRead) {
        return generation < 0 || generation != seen || sinceRead >= FULL_LOOK_MS;
    }

    /** How many pictures count, and how many bytes they are: "Everything on the phone (193 photos, 612 MB)". */
    private static long[] total() throws IOException {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, counted());
        long count = 0;
        long bytes = 0;
        try (Cursor c = media().query(IMAGES, new String[]{"_size"}, args)) {
            while (c != null && c.moveToNext()) {
                count++;
                bytes += c.getLong(0);
            }
        }
        return new long[]{count, bytes};
    }

    private static String str(Cursor c, int i) {
        String s = c.isNull(i) ? null : c.getString(i);
        return s == null ? "" : s;
    }

    /** "DCIM/Camera/" from a path, before Android 10 had relative_path. */
    private static String folderOf(String data, String name) {
        for (String root : new String[]{"/DCIM/", "/Pictures/"}) {
            int at = data.indexOf(root);
            if (at >= 0) {
                String rel = data.substring(at + 1);
                return rel.endsWith(name) ? rel.substring(0, rel.length() - name.length()) : rel;
            }
        }
        return "";
    }

    private static String albumOf(String folder) {
        String f = folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder;
        int at = f.lastIndexOf('/');
        return at >= 0 ? f.substring(at + 1) : f;
    }

    private static LinkedHashMap<Long, Row> byId(List<Row> rows) {
        LinkedHashMap<Long, Row> m = new LinkedHashMap<>();
        for (Row r : rows) m.put(r.id, r);
        return m;
    }

    private JSONObject failed(Exception e) {
        try {
            return new JSONObject().put("t", "photos").put("kind", "failed").put("message", String.valueOf(e));
        } catch (org.json.JSONException x) {
            return new JSONObject();
        }
    }

    private void say(JSONObject j) {
        if (stopped) return;
        try {
            wire.json(j);
        } catch (IOException e) {
            stopped = true;
        }
    }
}
