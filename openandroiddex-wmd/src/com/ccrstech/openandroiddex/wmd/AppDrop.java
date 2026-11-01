package com.ccrstech.openandroiddex.wmd;

import android.net.LocalSocket;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A file dropped on one of the computer's app windows (doc/apps-on-pc.md,
 * "Dropping files"): into the phone's Download folder, where a drop on the
 * desktop lands too — and an APK installed instead, as there.
 *
 * <p>A connection of its own per file, so a big one never holds up a window's
 * picture or its keys. Its first message says what is coming —
 * {@code {"t":"put", "name", "size", "dir"?, "install"?}} — and exactly
 * {@code size} bytes follow it, raw: nothing else is on this connection, so
 * there is nothing to frame. The phone answers as they land
 * ({@code {"t":"got","bytes"}}) and once with how it went:
 * {@code {"t":"put","ok":true,"name","path"}}, or {@code "ok":false} and a
 * {@code message}.
 *
 * <p>Never an overwrite and never a question: a name already taken becomes
 * "report (2).pdf", as wherever else files arrive on this phone
 * (WebFiles.uniqueIn). The bytes go to a ".part" beside it, renamed once the
 * last one is there; on any failure the ".part" goes.
 *
 * <p>A file dropped on the app in a window rather than on its title bar says
 * {@code "stage"} (its drop's batch) and {@code "i"}: it goes to the launcher's
 * cache instead, and the answer says {@code "staged"}; the app is given the
 * batch once the last of it is there ({@link AppDropIn}).
 */
final class AppDrop {

    static final String DOWNLOAD = "/sdcard/Download";
    /** Where an APK waits for the installer: the shell's own folder, emptied once it is in. */
    private static final String STAGING = "/data/local/tmp";
    private static final int INSTALL_TIMEOUT_S = 180;
    /** How often the computer hears how far a file is: enough for a bar, not a message per read. */
    private static final long GOT_EVERY_BYTES = 512 * 1024;
    private static final long GOT_EVERY_MS = 250;
    /** A name's most, in UTF-8: the filesystem's 255, less a ".part" and " (999)". */
    private static final int NAME_MAX_BYTES = 200;

    private final LocalSocket sock;
    private final Wire wire;
    private final JSONObject put;

    AppDrop(LocalSocket sock, Wire wire, JSONObject put) {
        this.sock = sock;
        this.wire = wire;
        this.put = put;
    }

    /**
     * The server is stopping: the connection goes, and with it the file's
     * ".part", deleted as any drop cut short deletes it.
     */
    void stop() {
        try {
            sock.shutdownInput();
        } catch (IOException ignored) {
        }
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * APKs a server stopped mid-install left waiting for the installer: an
     * install still runs when its server must end, and nothing after that
     * runs to tidy it. Ours alone, by their name.
     */
    static void sweep() {
        File[] left = new File(STAGING).listFiles((dir, name) -> name.startsWith("oadx-drop-") && name.endsWith(".apk"));
        if (left == null) return;
        for (File f : left) {
            if (f.delete()) AppServer.log("swept " + f.getName() + ", left by a server stopped mid-install");
        }
    }

    /** Take the file, say how it went, and close. On the connection's own thread. */
    void run() {
        JSONObject result;
        try {
            result = put.optBoolean("install") ? install() : put.has("stage") ? stage() : save();
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            AppServer.log("a file from the computer, " + put.optString("name") + ": " + why);
            result = failed(why);
        }
        try {
            wire.json(result);
        } catch (IOException ignored) {
            // the computer has gone: there is nobody to tell
        }
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }

    private JSONObject save() throws Exception {
        long size = size();
        return saveToDownload(put.optString("name"), put.optString("dir"), part -> receive(part, size));
    }

    private JSONObject install() throws Exception {
        long size = size();
        return install(put.optString("name"), apk -> receive(apk, size));
    }

    /**
     * Into the launcher's cache, for the app the file was dropped on, which is
     * given it once the last of the drop is there (AppDropIn). A launcher from
     * before this takes no file: the bytes on their way go to Download, as a
     * drop's did, and the computer hears where.
     */
    private JSONObject stage() throws Exception {
        long size = size();
        int i = put.optInt("i", -1);
        String name = safeName(put.optString("name"));
        android.net.Uri uri = AppDropIn.staged(put.optString("stage"), i, name);
        if (uri == null) throw new IOException("no such drop");
        Content.Writing w;
        try {
            w = Content.write(uri);
        } catch (IOException | RuntimeException e) {
            AppServer.log("a file for an app: the launcher took none (" + e + "): to Download instead");
            return save();
        }
        try (Content.Writing staged = w) {
            receive(staged.out, size);
        }
        return new JSONObject().put("t", "put").put("ok", true).put("name", name).put("i", i).put("staged", true);
    }

    /** Where a file's bytes come from, written into {@code into}: off the computer's connection, or the launcher's cache. */
    interface Bytes {
        void into(File into) throws Exception;
    }

    /**
     * Into Download, where a drop lands: a name nothing there has, claimed, the
     * bytes into its ".part", and the name once they are whole. What the
     * computer is told.
     */
    static JSONObject saveToDownload(String name, String subdir, Bytes bytes) throws Exception {
        File dir = new File(DOWNLOAD, safeDir(subdir));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        File target;
        File part;
        // Chosen and claimed together: two windows dropping "photo.jpg" at once get one each.
        synchronized (AppDrop.class) {
            target = unique(new File(dir, safeName(name)));
            part = new File(target.getPath() + ".part");
            if (!part.createNewFile()) throw new IOException("cannot write in " + dir);
        }
        bytes.into(part);
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("cannot name it " + target.getName());
        }
        // From Android 11 the phone's storage tells the media store itself, as
        // the file closes; before it, a file written by path stays invisible
        // to the gallery and the file pickers until it is scanned.
        if (Build.VERSION.SDK_INT < 30) scan(target);
        AppServer.log("a file from the computer: " + target.getPath() + " (" + target.length() + " bytes)");
        return new JSONObject().put("t", "put").put("ok", true)
                .put("name", target.getName()).put("path", target.getPath());
    }

    /** An APK installed, as {@code adb install} would install it, and deleted. What the computer is told. */
    static JSONObject install(String name, Bytes bytes) throws Exception {
        File apk = new File(STAGING, "oadx-drop-" + SystemClock.elapsedRealtimeNanos() + ".apk");
        try {
            bytes.into(apk);
            // pm is `cmd package` from Android 7: a binder call, not a JVM.
            String out = AppTasks.exec(INSTALL_TIMEOUT_S, "pm", "install", "-r", apk.getPath()).trim();
            if (!out.contains("Success")) {
                String why = installerSaid(out);
                AppServer.log("installing " + name + ": " + why);
                return failed(why);
            }
            AppServer.log("installed " + name);
            return new JSONObject().put("t", "put").put("ok", true).put("name", name);
        } finally {
            apk.delete();
        }
    }

    /**
     * The line of what the installer printed that says why: its
     * "Failure [INSTALL_…]", or, when it threw instead (a file that is no APK
     * at all, on Android 12), the error its stack trace starts with.
     */
    static String installerSaid(String out) {
        String error = null;
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.startsWith("Failure")) return l;
            int at = l.indexOf("Error: ");
            if (error == null && at >= 0) error = l.substring(at + "Error: ".length());
        }
        if (error != null) return error;
        for (String line : out.split("\n")) {
            if (!line.trim().isEmpty()) return line.trim();
        }
        return "The phone would not install it.";
    }

    private long size() throws IOException {
        long size = put.optLong("size", -1);
        if (size < 0) throw new IOException("no size");
        return size;
    }

    /** Exactly {@code size} bytes off the connection into {@code into}, the computer told as they land. */
    private void receive(File into, long size) throws Exception {
        boolean whole = false;
        try (FileOutputStream out = new FileOutputStream(into)) {
            receive(out, size);
            // On the storage before it gets its name: a phone that dies now
            // leaves a ".part", never a file that looks whole and is not.
            out.getFD().sync();
            whole = true;
        } finally {
            if (!whole) into.delete();
        }
    }

    /** Exactly {@code size} bytes off the connection into {@code out}, the computer told as they land. */
    private void receive(OutputStream out, long size) throws Exception {
        InputStream in = sock.getInputStream();
        byte[] buf = new byte[256 * 1024];
        long got = 0;
        long saidBytes = 0;
        long saidAt = SystemClock.uptimeMillis();
        while (got < size) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, size - got));
            if (n < 0) throw new IOException("the computer stopped sending");
            out.write(buf, 0, n);
            got += n;
            long now = SystemClock.uptimeMillis();
            if (got - saidBytes >= GOT_EVERY_BYTES || now - saidAt >= GOT_EVERY_MS) {
                wire.json(new JSONObject().put("t", "got").put("bytes", got));
                saidBytes = got;
                saidAt = now;
            }
        }
        wire.json(new JSONObject().put("t", "got").put("bytes", got));
    }

    /** All of {@code in} into {@code into}, made whole on the storage, or nothing left there. */
    static void copy(InputStream in, File into) throws IOException {
        boolean whole = false;
        try (FileOutputStream out = new FileOutputStream(into)) {
            byte[] buf = new byte[256 * 1024];
            for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
            out.getFD().sync();
            whole = true;
        } finally {
            if (!whole) into.delete();
        }
    }

    private static JSONObject failed(String message) {
        JSONObject j = new JSONObject();
        try {
            j.put("t", "put").put("ok", false).put("message", message);
        } catch (Exception ignored) {
        }
        return j;
    }

    /** The media store told of a file it would not otherwise see: Android 10 and older. */
    private static void scan(File f) {
        Thread t = new Thread(() -> AppTasks.exec(15, "am", "broadcast", "-a",
                "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", Uri.fromFile(f).toString()), "apps-scan");
        t.setDaemon(true);
        t.start();
    }

    /** "report.pdf" beside one already there — or one still arriving — becomes "report (2).pdf". */
    static File unique(File wanted) {
        if (free(wanted)) return wanted;
        String name = wanted.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(wanted.getParentFile(), stem + " (" + i + ")" + ext);
            if (free(candidate)) return candidate;
        }
        return new File(wanted.getParentFile(), stem + " (" + SystemClock.uptimeMillis() + ")" + ext);
    }

    private static boolean free(File f) {
        return !f.exists() && !new File(f.getPath() + ".part").exists();
    }

    /**
     * A name from the computer is not a path: separators and the two relative
     * names are the whole attack, and taking them out the whole defence. The
     * characters shared storage refuses (it may be FAT underneath) become "_".
     */
    static String safeName(String name) {
        String cleaned = name == null ? "" : name.replace('\\', '/');
        int cut = cleaned.lastIndexOf('/');
        if (cut >= 0) cleaned = cleaned.substring(cut + 1);
        StringBuilder b = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            b.append(c < 0x20 || c == 0x7f || "\"*:<>?|".indexOf(c) >= 0 ? '_' : c);
        }
        cleaned = b.toString().trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) return "file";
        return shorten(cleaned);
    }

    /** "trip/photos" from the computer, each part a safe name; nothing that climbs out. */
    static String safeDir(String dir) {
        if (dir == null || dir.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String part : dir.replace('\\', '/').split("/")) {
            String p = part.trim();
            if (p.isEmpty() || ".".equals(p) || "..".equals(p)) continue;
            if (b.length() > 0) b.append('/');
            b.append(safeName(p));
        }
        return b.toString();
    }

    /** Within NAME_MAX_BYTES of UTF-8, the extension kept. */
    private static String shorten(String name) {
        if (name.getBytes(StandardCharsets.UTF_8).length <= NAME_MAX_BYTES) return name;
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        String stem = name.substring(0, name.length() - ext.length());
        while (!stem.isEmpty() && (stem + ext).getBytes(StandardCharsets.UTF_8).length > NAME_MAX_BYTES) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }
        return stem.isEmpty() ? "file" + ext : stem + ext;
    }
}
