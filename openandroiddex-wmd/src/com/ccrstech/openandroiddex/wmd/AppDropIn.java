package com.ccrstech.openandroiddex.wmd;

import android.net.LocalSocket;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Files dropped on the app in one of the computer's windows, given to that
 * app (doc/apps-on-pc.md, "Dropping files"): the server's half.
 *
 * <p>Each file comes first, on a connection of its own ({@link AppDrop} with
 * {@code "stage"}), into the launcher's cache, where an app can be given it
 * (the launcher's DropsProvider): the shell can leave no file anywhere an app
 * may read. Then one connection asks for the drop itself:
 * {@code {"t":"dropIn", "window", "batch", "x", "y", "files": [{i, name}]}},
 * (x, y) in the window's display pixels. The window carries a drag of them
 * there from the launcher's corner, as a drag out of another app would come,
 * and lets go ({@link AppWindow#dropIn}): the app under that point takes them
 * or not, as it takes any drop.
 *
 * <p>The answer is one message: {@code {"t":"dropIn", "ok":true, "taken":true}},
 * the files left where the app may read them for a day; or, taken by nothing,
 * each in Download, where a drop on the title bar goes, as {@link AppDrop}
 * answers for a file — {@code {"taken":false, "why", "files":[…]}} — and the
 * launcher's copies gone. {@code why} is {@code "refused"} when the app was
 * offered them and did not take them, {@code "cannot"} when no drag could be
 * made at all: no launcher window on the display (Android 11 and older, a
 * launcher from before this, Android 17 with no overlay allowed).
 *
 * <p>Taken says only that the app took the drop, not which of its files: a
 * chat may take the photos of one and not the PDF with them. So a file the
 * app has not opened {@link #OPENED_WAIT_MS} after it took them goes to
 * Download as well, and a taken answer lists those the same way, in
 * {@code files}. It stays the app's to read too, which one that reads
 * nothing before it sends may still do.
 */
final class AppDropIn {

    /**
     * How long an app that took a drop has to open its files before the ones
     * it has not are put in Download as well: an app that shows what it was
     * given reads it as it takes it, a second or two on a slow phone.
     */
    private static final long OPENED_WAIT_MS = 5000;

    private final AppServer server;
    private final LocalSocket sock;
    private final Wire wire;
    private final JSONObject ask;

    AppDropIn(AppServer server, LocalSocket sock, Wire wire, JSONObject ask) {
        this.server = server;
        this.sock = sock;
        this.wire = wire;
        this.ask = ask;
    }

    /** Whether {@code batch} could name a drop's files: letters, digits and dashes, as the launcher takes them. */
    static boolean isBatch(String batch) {
        return batch != null && batch.matches("[A-Za-z0-9-]{1,64}");
    }

    /** Where file {@code i} of {@code batch} is staged, through the launcher; null for what could be no such file. */
    static Uri staged(String batch, int i, String name) {
        if (!isBatch(batch) || i < 0 || name == null || name.isEmpty()) return null;
        return new Uri.Builder().scheme("content").authority(AppServer.LINUX_PROVIDER)
                .appendPath("drop").appendPath(batch).appendPath(String.valueOf(i)).appendPath(name).build();
    }

    /** Drop them, say how it went, and close. On the connection's own thread. */
    void run() {
        JSONObject answer;
        try {
            answer = drop();
        } catch (Exception e) {
            AppServer.log("a drop on an app: " + e);
            answer = new JSONObject();
            try {
                answer.put("t", "dropIn").put("ok", false).put("message", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        try {
            wire.json(answer);
        } catch (IOException ignored) {
            // the computer has gone: there is nobody to tell
        }
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }

    private JSONObject drop() throws Exception {
        String batch = ask.optString("batch");
        JSONArray files = ask.optJSONArray("files");
        if (files == null || files.length() == 0 || !isBatch(batch)) throw new IOException("nothing to drop");
        AppWindow w = server.window(ask.optString("window"));
        Boolean taken = w == null ? null : w.dropIn(batch, files, ask.optInt("x"), ask.optInt("y"));
        if (Boolean.TRUE.equals(taken)) {
            JSONObject answer = new JSONObject().put("t", "dropIn").put("ok", true).put("taken", true);
            JSONArray unopened = unopened(batch, files);
            if (unopened.length() > 0) {
                AppServer.log("a drop on an app: " + unopened.length() + " of " + files.length()
                        + " file(s) not opened by it: to Download as well");
                answer.put("files", toDownload(batch, unopened));
            }
            return answer;
        }

        // Taken by nothing: each where a drop goes otherwise.
        JSONArray landed = toDownload(batch, files);
        try {
            Providers.call(AppServer.LINUX_PROVIDER, "drop.forget", new JSONObject().put("batch", batch));
        } catch (Exception e) {
            AppServer.log("a drop on an app: its staged files stay a day (" + e + ")");
        }
        return new JSONObject().put("t", "dropIn").put("ok", true).put("taken", false)
                .put("why", taken == null ? "cannot" : "refused").put("files", landed);
    }

    /**
     * Those of {@code files} the app that took them has not opened, waiting
     * up to {@link #OPENED_WAIT_MS} for it to open them all. None where the
     * launcher cannot say, as taken was all there was to go on before.
     */
    private static JSONArray unopened(String batch, JSONArray files) {
        JSONArray left = new JSONArray();
        try {
            JSONObject r = Providers.call(AppServer.LINUX_PROVIDER, "drop.opened",
                    new JSONObject().put("batch", batch).put("files", files).put("wait", OPENED_WAIT_MS));
            JSONArray opened = r.optBoolean("ok") ? r.optJSONArray("opened") : null;
            if (opened == null) {
                AppServer.log("a drop on an app: the launcher cannot say which files it opened (" + r.optString("message") + ")");
                return left;
            }
            Set<Integer> had = new HashSet<>();
            for (int k = 0; k < opened.length(); k++) had.add(opened.optInt(k, -1));
            for (int k = 0; k < files.length(); k++) {
                JSONObject f = files.optJSONObject(k);
                if (f != null && !had.contains(f.optInt("i", -1))) left.put(f);
            }
        } catch (Exception e) {
            AppServer.log("a drop on an app: which files it opened is not known (" + e + ")");
        }
        return left;
    }

    /** Each of {@code files} of {@code batch} copied to Download, as {@link AppDrop} answers for a file. */
    private static JSONArray toDownload(String batch, JSONArray files) throws Exception {
        JSONArray landed = new JSONArray();
        for (int k = 0; k < files.length(); k++) {
            JSONObject f = files.optJSONObject(k);
            String name = f == null ? "" : f.optString("name");
            Uri uri = f == null ? null : staged(batch, f.optInt("i", -1), name);
            JSONObject r;
            try {
                if (uri == null) throw new IOException("no such file");
                r = AppDrop.saveToDownload(name, "", part -> {
                    try (Content.Opened staged = Content.open(uri)) {
                        AppDrop.copy(staged.in, part);
                    }
                });
            } catch (Exception e) {
                AppServer.log("a file for an app, to Download after all: " + name + ": " + e);
                r = new JSONObject().put("t", "put").put("ok", false).put("message", String.valueOf(e.getMessage()));
            }
            landed.put(r);
        }
        return landed;
    }
}
