package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Files over the data channel, for the sessions where HTTP is not an option.
 *
 * In a rendezvous session the phone's web server is unreachable — that is the
 * whole reason WebRTC is there — so {@code /api/upload} and {@code /api/download}
 * do not exist from the browser's point of view. The same transfers therefore
 * run over the {@code file} data channel instead, in both directions, framed
 * as JSON commands with binary chunks carrying a four-byte transfer id.
 *
 * <p><b>Backpressure is the whole difficulty.</b> SCTP will happily accept more
 * than the link can carry and grow a buffer until the connection dies, so a
 * download pauses whenever the channel's backlog passes {@link #BACKLOG_LIMIT}
 * rather than pushing a whole file into it. The browser does the same on its
 * side against {@code bufferedAmount}.
 *
 * <p>Uploads land in the same folder, are scanned into MediaStore the same way
 * and raise the same desktop card as an HTTP upload or a drag from a PC — see
 * {@link WebFiles}. One drop story, now three ways in.
 */
final class WebRtcFiles {

    /** Chunk size. Comfortably under the 256 KB an SCTP message may be. */
    private static final int CHUNK = 16 * 1024;
    /** Pause a download while more than this is already queued. */
    private static final long BACKLOG_LIMIT = 1024 * 1024;
    /** How long to wait between backlog checks while paused. */
    private static final long BACKLOG_WAIT_MS = 20;
    /** Concurrent transfers per viewer. */
    private static final int MAX_ACTIVE = 4;

    interface JsonSink {
        void send(String json);
    }

    interface BinarySink {
        void send(byte[] framed);
    }

    interface Backlog {
        long bytes();
    }

    private final Context ctx;
    private final JsonSink json;
    private final BinarySink binary;
    private final Backlog backlog;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-rtc-files");
        t.setDaemon(true);
        return t;
    });

    /** Uploads in flight, by the id the browser chose. */
    private final Map<Integer, Upload> uploads = new HashMap<>();
    private volatile boolean closed;

    WebRtcFiles(Context ctx, JsonSink json, BinarySink binary, Backlog backlog) {
        this.ctx = ctx;
        this.json = json;
        this.binary = binary;
        this.backlog = backlog;
    }

    private static final class Upload {
        String name;
        long expected;
        long written;
        File part;
        File target;
        OutputStream out;
        int lastPct = -1;
    }

    void onJson(String text) {
        if (closed) return;
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("t");
            if (!Web.files(ctx)) {
                fail(msg.optInt("id", 0), "files-off");
                return;
            }
            switch (type) {
                case "ls":
                    list(msg.optString("path", ""));
                    break;
                case "get":
                    download(msg.optInt("id", 0), msg.optString("path", ""));
                    break;
                case "put":
                    beginUpload(msg.optInt("id", 0), msg.optString("name", "upload"),
                            msg.optLong("size", -1));
                    break;
                case "put-end":
                    finishUpload(msg.optInt("id", 0), true);
                    break;
                case "cancel":
                    finishUpload(msg.optInt("id", 0), false);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            DexLog.warn("web", "bad file command", e);
        }
    }

    /** {@code [id:4][payload]} — a chunk of whichever upload the id names. */
    void onBinary(byte[] framed) {
        if (closed || framed.length < 4) return;
        int id = ((framed[0] & 0xFF) << 24) | ((framed[1] & 0xFF) << 16)
                | ((framed[2] & 0xFF) << 8) | (framed[3] & 0xFF);
        Upload upload;
        synchronized (uploads) {
            upload = uploads.get(id);
        }
        if (upload == null) return;
        try {
            upload.out.write(framed, 4, framed.length - 4);
            upload.written += framed.length - 4;
            if (upload.expected > 0) {
                int pct = (int) (upload.written * 100 / upload.expected);
                if (pct != upload.lastPct) {
                    upload.lastPct = pct;
                    WebFiles.progress(ctx, upload.name, pct);
                }
            }
        } catch (Exception e) {
            DexLog.warn("web", "upload write failed", e);
            fail(id, "write");
            finishUpload(id, false);
        }
    }

    private void list(String path) {
        File dir = WebFiles.resolve(ctx, path);
        if (dir == null || !dir.isDirectory()) {
            fail(0, "no-dir");
            return;
        }
        try {
            json.send(new JSONObject()
                    .put("t", "ls")
                    .put("data", WebFiles.list(ctx, dir)).toString());
        } catch (Exception e) {
            DexLog.warn("web", "could not send a listing", e);
        }
    }

    private void download(final int id, String path) {
        final File file = WebFiles.resolve(ctx, path);
        if (file == null || !file.isFile() || !file.canRead()) {
            fail(id, "no-file");
            return;
        }
        io.execute(() -> {
            try {
                json.send(new JSONObject()
                        .put("t", "get-begin").put("id", id)
                        .put("name", file.getName())
                        .put("size", file.length()).toString());
                byte[] frame = new byte[4 + CHUNK];
                frame[0] = (byte) (id >> 24);
                frame[1] = (byte) (id >> 16);
                frame[2] = (byte) (id >> 8);
                frame[3] = (byte) id;
                try (FileInputStream in = new FileInputStream(file)) {
                    int n;
                    while (!closed && (n = in.read(frame, 4, CHUNK)) > 0) {
                        // Wait rather than queue: the alternative is a buffer
                        // that grows until the peer connection is killed.
                        while (!closed && backlog.bytes() > BACKLOG_LIMIT) {
                            Thread.sleep(BACKLOG_WAIT_MS);
                        }
                        if (closed) return;
                        byte[] exact = n == CHUNK ? frame : new byte[4 + n];
                        if (exact != frame) {
                            System.arraycopy(frame, 0, exact, 0, 4 + n);
                        }
                        binary.send(exact);
                    }
                }
                json.send(new JSONObject().put("t", "get-end").put("id", id).toString());
            } catch (Exception e) {
                DexLog.warn("web", "download of " + file.getName() + " failed", e);
                fail(id, "read");
            }
        });
    }

    private void beginUpload(int id, String name, long size) {
        if (size > WebFiles.MAX_UPLOAD) {
            fail(id, "too-big");
            return;
        }
        synchronized (uploads) {
            if (uploads.size() >= MAX_ACTIVE) {
                fail(id, "too-many");
                return;
            }
        }
        Upload upload = new Upload();
        upload.name = WebFiles.safeName(name);
        upload.expected = size;
        try {
            File dir = new File(Web.DEF_UPLOAD_DIR);
            if (!dir.exists()) dir.mkdirs();
            if (!WebFiles.hasAllFiles() || !dir.canWrite()) {
                // MediaStore is write-only and streamed through the resolver;
                // it has no partial-file story, so the data channel path keeps
                // to the filesystem and says so rather than half-working.
                fail(id, "no-storage");
                return;
            }
            upload.target = WebFiles.uniqueIn(dir, upload.name);
            upload.part = new File(upload.target.getAbsolutePath() + ".part");
            upload.out = new FileOutputStream(upload.part);
        } catch (Exception e) {
            DexLog.warn("web", "could not open an upload", e);
            fail(id, "open");
            return;
        }
        synchronized (uploads) {
            uploads.put(id, upload);
        }
        WebFiles.progress(ctx, upload.name, 0);
        try {
            json.send(new JSONObject().put("t", "put-ready").put("id", id).toString());
        } catch (Exception ignored) {
        }
    }

    private void finishUpload(int id, boolean keep) {
        Upload upload;
        synchronized (uploads) {
            upload = uploads.remove(id);
        }
        if (upload == null) return;
        try {
            upload.out.close();
        } catch (Exception ignored) {
        }
        if (!keep) {
            upload.part.delete();
            WebFiles.finished(ctx, upload.name, null, false);
            return;
        }
        boolean ok = upload.part.renameTo(upload.target);
        if (!ok) {
            upload.part.delete();
            WebFiles.finished(ctx, upload.name, null, false);
            fail(id, "rename");
            return;
        }
        WebFiles.scan(ctx, upload.target);
        WebFiles.finished(ctx, upload.name, upload.target.getName(), true);
        DexLog.step("web", "received " + upload.target.getName()
                + " over the data channel (" + upload.written + " bytes)");
        try {
            json.send(new JSONObject()
                    .put("t", "put-done").put("id", id)
                    .put("name", upload.target.getName()).toString());
        } catch (Exception ignored) {
        }
    }

    private void fail(int id, String why) {
        try {
            json.send(new JSONObject().put("t", "error").put("id", id).put("why", why).toString());
        } catch (Exception ignored) {
        }
    }

    void close() {
        closed = true;
        synchronized (uploads) {
            for (Map.Entry<Integer, Upload> entry : uploads.entrySet()) {
                Upload upload = entry.getValue();
                try {
                    upload.out.close();
                } catch (Exception ignored) {
                }
                // A half-written file is not left behind wearing the name of a
                // whole one: the .part suffix is what says it never finished.
                upload.part.delete();
            }
            uploads.clear();
        }
        io.shutdownNow();
    }
}
