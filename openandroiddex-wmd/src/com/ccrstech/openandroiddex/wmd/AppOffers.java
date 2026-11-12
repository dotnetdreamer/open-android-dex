package com.ccrstech.openandroiddex.wmd;

import android.net.LocalSocket;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the computer may take from the phone: the items of a drag carried out
 * of one of its windows (AppDrags), or of what the phone's clipboard holds
 * when that is a picture or a file (AppClipboard), or of a share to "Send to
 * computer", open already (AppSend) — by number, for as long as the phone may
 * read them ({@link #KEEP_MS}).
 *
 * <p>The computer takes each file on a connection of its own, as it sends a
 * dropped one (AppDrop), so a big one holds up nothing: its first message is
 * {@code {"t":"get","id","i"}}, the phone answers {@code {"t":"get","ok":true,
 * "name","mime","size"}} — the size -1 where the provider does not say — and
 * the file's bytes follow in pieces ({@link Wire#PIECE}), then an empty piece:
 * the end mark. A connection that closes before it is a file cut short, never
 * one taken for whole — with or without a size. Or {@code "ok": false} and a
 * {@code message}.
 */
final class AppOffers {

    /** The launcher takes back the shell's read of a drop after 15 minutes (PcDrags.GRANT_MS). */
    static final long KEEP_MS = 15 * 60_000L;
    private static final int KEEP_MAX = 32;

    /** Told how far a file has gone: {@code bytes} of {@code total} (-1 unknown). */
    interface Progress {
        void sent(long bytes, long total);
    }

    /** One thing on offer: a file (its URI, or the file itself, open), or text. */
    static final class Item {
        String uri;
        String name;
        String mime;
        long size = -1;
        String text;
        /**
         * The file, already open — what was shared to the launcher's "Send to
         * computer", which the launcher opened and handed over (AppSend): the
         * shell holds no grant to open its URI itself. Read once, then closed.
         */
        Content.Opened opened;
        /** It came open ({@link #opened}): once read or closed, there is nothing more to give. */
        boolean handedOver;
        /** Hears the bytes as they go, for the phone's card (AppSend); null if nobody asks. */
        Progress progress;

        synchronized Content.Opened takeOpened() {
            Content.Opened o = opened;
            opened = null;
            return o;
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject j = new JSONObject();
            if (uri != null) j.put("name", name).put("mime", mime == null ? "" : mime).put("size", size);
            if (text != null) j.put("text", text);
            return j;
        }
    }

    private static final class Offer {
        final List<Item> items;
        final long at = SystemClock.uptimeMillis();
        /** A share's (AppSend), which drops it as the share ends: never closed for its age. */
        final boolean held;

        Offer(List<Item> items, boolean held) {
            this.items = items;
            this.held = held;
        }
    }

    private final Map<Integer, Offer> offers = new LinkedHashMap<>();
    private int next;

    /** Offer {@code items}: their number, for the computer to take them by. */
    int put(List<Item> items) {
        return put(items, false);
    }

    /**
     * Offer a share's {@code items}, which its owner drops as the share ends
     * ({@link #drop}): a share still pending, or waiting its turn on the
     * computer, is never closed here — not for its age, not for the offers
     * that came after it. Shares are held to a few where they are let in
     * (AppSend's openers, the computer's caps), so they are not counted
     * against {@link #KEEP_MAX}.
     */
    int putHeld(List<Item> items) {
        return put(items, true);
    }

    private synchronized int put(List<Item> items, boolean held) {
        long now = SystemClock.uptimeMillis();
        int loose = 0;
        for (Offer o : offers.values()) {
            if (!o.held) loose++;
        }
        for (Iterator<Offer> i = offers.values().iterator(); i.hasNext(); ) {
            Offer o = i.next();
            if (o.held) continue;
            if (closes(now - o.at, loose, held)) {
                i.remove();
                loose--;
                closeAll(o);
            }
        }
        int id = ++next;
        offers.put(id, new Offer(items, held));
        return id;
    }

    /**
     * Whether a drag's or a clip's offer ({@code age} ms old, the oldest first)
     * is closed as another is put: once it is {@link #KEEP_MS} old, or while
     * {@code loose} of them are open already and the new one is one too. A
     * share's ({@code held}) is never closed this way, nor makes room for itself.
     */
    static boolean closes(long age, int loose, boolean newHeld) {
        return age > KEEP_MS || (!newHeld && loose >= KEEP_MAX);
    }

    /** Offer {@code id} taken back: what it still holds open, closed — a file on its way ends there. */
    void drop(int id) {
        Offer o;
        synchronized (this) {
            o = offers.remove(id);
        }
        if (o != null) closeAll(o);
    }

    private static void closeAll(Offer o) {
        for (Item it : o.items) {
            Content.Opened open = it.takeOpened();
            if (open != null) open.close();
        }
    }

    private synchronized Item get(int id, int i) {
        Offer o = offers.get(id);
        return o == null || i < 0 || i >= o.items.size() ? null : o.items.get(i);
    }

    /** The items, as the computer hears of them: what each is called and how big, or its text. */
    static JSONArray describe(List<Item> items) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Item it : items) a.put(it.toJson());
        return a;
    }

    /**
     * A file's name, type and size, where the launcher did not say them: the
     * provider's, as the shell may ask — else the URI's last part, with the
     * extension its type has.
     */
    static void complete(Item it) {
        if (it.uri == null) return;
        Uri uri = Uri.parse(it.uri);
        if (it.name == null || it.mime == null || it.size < 0) {
            Content.Described d = Content.describe(uri);
            if (it.name == null) it.name = d.name;
            if (it.mime == null) it.mime = d.mime;
            if (it.size < 0) it.size = d.size;
        }
        if (it.name == null || it.name.isEmpty()) {
            String last = uri.getLastPathSegment();
            it.name = last == null || last.isEmpty() || last.matches("\\d+") ? "file" : last;
        }
        // "IMG_1234" from a gallery: the type's extension, so the computer knows what it is.
        String ext = it.mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(it.mime);
        if (ext != null && it.name.lastIndexOf('.') <= 0) it.name = it.name + "." + ext;
    }

    // ── a file, to the computer (the connection's own thread) ──

    void serve(LocalSocket sock, Wire wire, JSONObject req) {
        Item it = get(req.optInt("id", -1), req.optInt("i", -1));
        try {
            if (it == null || it.uri == null) {
                wire.json(failed("That is no longer on offer: drag or copy it again."));
                return;
            }
            // Opened already (a share's), or opened now as the shell (a drag's, a clip's).
            Content.Opened handed = it.takeOpened();
            if (handed == null && it.handedOver) {
                // A share's file taken once already, or taken back: never opened again as the shell.
                wire.json(failed("That is no longer on offer: send it again."));
                return;
            }
            boolean begun = false;
            try (Content.Opened o = handed != null ? handed : Content.open(Uri.parse(it.uri))) {
                long size = o.length >= 0 ? o.length : it.size;
                // exact: the file's own length, not what the sending app said (a pipe has none): fewer bytes
                // than an exact size is a file cut short; a said one is a hint either way.
                wire.json(new JSONObject().put("t", "get").put("ok", true).put("name", it.name)
                        .put("mime", it.mime == null ? "" : it.mime).put("size", size).put("exact", o.length >= 0));
                begun = true;
                byte[] buf = new byte[256 * 1024];
                long sent = 0;
                long t0 = SystemClock.uptimeMillis();
                if (it.progress != null) it.progress.sent(0, size);
                for (int n; (n = o.in.read(buf)) > 0; ) {
                    wire.send(Wire.PIECE, null, 0, buf, n);
                    sent += n;
                    if (it.progress != null) it.progress.sent(sent, size);
                }
                // The end mark: only now is the file whole.
                wire.send(Wire.PIECE, null, 0, buf, 0);
                AppServer.log("sent " + it.name + " to the computer (" + sent + " bytes in "
                        + (SystemClock.uptimeMillis() - t0) + " ms)");
            } catch (IOException | RuntimeException e) {
                AppServer.log("could not read " + it.name + " (" + e + ")");
                // After the answer the pieces: the connection is closed without the end mark, which the
                // computer takes for a file cut short.
                if (!begun) wire.json(failed("The phone could not read it."));
            }
        } catch (Exception ignored) {
            // the computer went: there is nobody to tell
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static JSONObject failed(String message) {
        JSONObject j = new JSONObject();
        try {
            j.put("t", "get").put("ok", false).put("message", message);
        } catch (Exception ignored) {
        }
        return j;
    }

    /** Items from a launcher's or clipboard's list of what it carries. */
    static List<Item> items(JSONArray a) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Item it = new Item();
            String uri = j.optString("uri", "");
            if (uri.startsWith("content:")) it.uri = uri;
            if (j.has("name")) it.name = j.optString("name");
            if (j.has("mime")) it.mime = j.optString("mime");
            it.size = j.optLong("size", -1);
            if (j.has("text")) it.text = j.optString("text");
            if (it.uri != null || it.text != null) out.add(it);
        }
        return out;
    }
}
