package com.ccrstech.openandroiddex.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Files, both directions: what the page may list and download, and where a
 * dropped file ends up.
 *
 * <p>Uploads land in {@code /sdcard/Download} — the same folder a file dragged
 * onto the desktop from a PC lands in, and they narrate themselves over the
 * same {@link LauncherActivity#ACTION_TRANSFER} broadcast, so a file dropped
 * into the browser tab raises exactly the card on the desktop that a file
 * dropped onto the scrcpy window does. One drop story, two ways in.
 *
 * <p><b>The all-files grant decides how much of this works.</b> With it (the
 * launcher already asks for it for the Linux shared folder) the whole of
 * shared storage can be browsed and written. Without it, scoped storage denies
 * this uid a plain {@code open()} anywhere under {@code /sdcard} — so listing
 * comes back empty and uploads take the MediaStore route instead, which needs
 * no permission at all but can only ever write, and only into Downloads. Both
 * are handled; the page is told which one it is looking at.
 *
 * <p>Every path from the network is canonicalised and checked against the root
 * before anything opens it. {@code ../} is the oldest bug in file serving and
 * it is not going to be this one.
 */
final class WebFiles {

    private WebFiles() {
    }

    /** Biggest single upload we accept. Big enough for a film, bounded on purpose. */
    static final long MAX_UPLOAD = 8L * 1024 * 1024 * 1024;

    static boolean hasAllFiles() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    static File root(Context ctx) {
        return new File(Web.root(ctx));
    }

    /**
     * A path from the page, resolved and proven to be inside the root.
     *
     * @return null when it is not, which every caller turns into a 403.
     */
    static File resolve(Context ctx, String path) {
        if (path == null || path.isEmpty()) return root(ctx);
        try {
            File rootDir = root(ctx).getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            String rootPath = rootDir.getPath();
            String targetPath = target.getPath();
            if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator)) {
                return target;
            }
            DexLog.warn("web", "refused a path outside the root: " + path);
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** One directory, as the page's file panel wants it. */
    static JSONObject list(Context ctx, File dir) {
        JSONObject out = new JSONObject();
        try {
            out.put("path", dir.getAbsolutePath());
            out.put("root", root(ctx).getAbsolutePath());
            out.put("granted", hasAllFiles());
            File parent = dir.getParentFile();
            File rootDir = root(ctx);
            boolean atRoot = dir.getAbsolutePath().equals(rootDir.getAbsolutePath());
            out.put("parent", atRoot || parent == null ? JSONObject.NULL : parent.getAbsolutePath());
            JSONArray entries = new JSONArray();
            File[] children = dir.listFiles();
            if (children != null) {
                // Folders first, then names, case-insensitively — the order a
                // file manager uses, because this IS one.
                Arrays.sort(children, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : children) {
                    if (f.isHidden()) continue;
                    JSONObject e = new JSONObject();
                    e.put("name", f.getName());
                    e.put("path", f.getAbsolutePath());
                    e.put("dir", f.isDirectory());
                    e.put("size", f.isDirectory() ? 0 : f.length());
                    e.put("modified", f.lastModified());
                    entries.put(e);
                }
            }
            out.put("entries", entries);
        } catch (Exception e) {
            DexLog.warn("web", "could not list " + dir, e);
        }
        return out;
    }

    /**
     * Take an upload off the socket.
     *
     * The body is streamed straight to its final name with a {@code .part}
     * suffix and renamed at the end, so a connection that dies half way leaves
     * an obviously unfinished file rather than a plausible truncated one.
     *
     * @return the file it landed in, or null if it could not be written.
     */
    static File receive(Context ctx, String name, InputStream body, long length,
                        Progress progress) throws IOException {
        String safe = safeName(name);
        File dir = new File(Web.DEF_UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
        if (!hasAllFiles() || !dir.canWrite()) {
            return receiveViaMediaStore(ctx, safe, body, length, progress);
        }
        File target = unique(new File(dir, safe));
        File part = new File(target.getAbsolutePath() + ".part");
        long written = 0;
        try (OutputStream out = new java.io.FileOutputStream(part)) {
            written = pump(body, out, length, progress);
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("could not rename " + part);
        }
        DexLog.step("web", "received " + target.getName() + " (" + written + " bytes)");
        scan(ctx, target);
        return target;
    }

    /**
     * The no-permission path. MediaStore will write into Downloads for any
     * app, which is the one place a phone without the all-files grant can
     * still accept a file — and it is where the user would look for it anyway.
     */
    private static File receiveViaMediaStore(Context ctx, String name, InputStream body,
                                             long length, Progress progress) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("no writable Downloads folder");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime(name));
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore refused the file");
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("MediaStore gave no stream");
            pump(body, out, length, progress);
        } catch (IOException e) {
            ctx.getContentResolver().delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, values, null, null);
        DexLog.step("web", "received " + name + " through MediaStore");
        // No File to hand back — the caller only uses it for the "Open"
        // button, which MediaStore's own Downloads UI already provides.
        return null;
    }

    interface Progress {
        void at(long bytes, int percent);
    }

    private static long pump(InputStream in, OutputStream out, long length, Progress progress)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int lastPct = -1;
        // -1 means "until the stream ends". A Content-Length of zero and a
        // missing one are the same value on the wire, and treating the second
        // as an empty file would silently write nothing.
        long remaining = length > 0 ? length : -1;
        while (remaining != 0) {
            int want = remaining > 0 && remaining < buf.length ? (int) remaining : buf.length;
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            out.write(buf, 0, n);
            total += n;
            if (remaining > 0) remaining -= n;
            int pct = length > 0 ? (int) (total * 100 / length) : -1;
            if (progress != null && pct != lastPct) {
                lastPct = pct;
                progress.at(total, pct);
            }
        }
        out.flush();
        return total;
    }

    /** A free name for {@code name} in {@code dir} — see {@link #unique}. */
    static File uniqueIn(File dir, String name) {
        return unique(new File(dir, safeName(name)));
    }

    /** "report.pdf" beside an existing one becomes "report (2).pdf". */
    private static File unique(File wanted) {
        if (!wanted.exists()) return wanted;
        String name = wanted.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(wanted.getParentFile(), stem + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return wanted;
    }

    /**
     * A file name from the network is not a path. Separators and the two
     * relative names are the whole attack, and stripping them is the whole
     * defence.
     */
    static String safeName(String name) {
        if (name == null) return "upload";
        String cleaned = name.replace('\\', '/');
        int cut = cleaned.lastIndexOf('/');
        if (cut >= 0) cleaned = cleaned.substring(cut + 1);
        cleaned = cleaned.replaceAll("[\\x00-\\x1f]", "").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) return "upload";
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    /**
     * Hand a written file to the media scanner.
     *
     * Same reason the PC-side drop does it: the phone's Files app lists
     * Downloads out of MediaStore, and a file written through the filesystem
     * leaves no row there — so without this the upload is on the phone and
     * invisible in the folder the card just pointed at.
     */
    static void scan(Context ctx, File file) {
        try {
            MediaScannerConnection.scanFile(ctx.getApplicationContext(),
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            DexLog.warn("web", "media scan failed for " + file, e);
        }
    }

    // ── the desktop's drop card ──

    /**
     * Narrate an upload to {@link TransferHud}, in the PC's own dialect.
     *
     * The seq extra is deliberately NOT sent: it exists so the card can ignore
     * a replay from a restarted PC, and a second, independent counter coming
     * from here would look exactly like that restart.
     */
    static void progress(Context ctx, String name, int pct) {
        ctx.sendBroadcast(new Intent(LauncherActivity.ACTION_TRANSFER)
                .setPackage(ctx.getPackageName())
                .putExtra("name", b64(name))
                .putExtra("dir", b64(Web.DEF_UPLOAD_DIR))
                .putExtra("pct", pct));
    }

    static void finished(Context ctx, String name, String landedName, boolean ok) {
        Intent i = new Intent(LauncherActivity.ACTION_TRANSFER)
                .setPackage(ctx.getPackageName())
                .putExtra("name", b64(name))
                .putExtra("dir", b64(Web.DEF_UPLOAD_DIR))
                .putExtra("state", "done")
                .putExtra("ok", ok ? 1 : 0)
                .putExtra("fail", ok ? 0 : 1);
        if (ok && landedName != null) i.putExtra("landed", b64(landedName));
        ctx.sendBroadcast(i);
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    // ── content types ──

    /**
     * Enough of a type table to make a browser do the right thing: show what
     * it can show, download what it cannot. Everything unknown is
     * octet-stream, which downloads — the safe default for a file the phone
     * cannot vouch for.
     */
    static String mime(String name) {
        String lower = name.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        switch (ext) {
            case "txt": case "log": case "md": case "ini": case "cfg": return "text/plain";
            case "html": case "htm": return "text/html";
            case "css": return "text/css";
            case "js": return "text/javascript";
            case "json": return "application/json";
            case "xml": return "text/xml";
            case "csv": return "text/csv";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            case "mp3": return "audio/mpeg";
            case "ogg": return "audio/ogg";
            case "wav": return "audio/wav";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            default: return "application/octet-stream";
        }
    }
}
