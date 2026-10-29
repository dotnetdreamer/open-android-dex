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
 * when that is a picture or a file (AppClipboard) — by number, for as long
 * as the phone may read them ({@link #KEEP_MS}).
 *
 * <p>The computer takes each file on a connection of its own, as it sends a
 * dropped one (AppDrop), so a big one holds up nothing: its first message is
 * {@code {"t":"get","id","i"}}, the phone answers {@code {"t":"get","ok":true,
 * "name","mime","size"}} — the size -1 where the provider does not say — and
 * the file's bytes follow, raw, until the connection closes. Or {@code "ok":
 * false} and a {@code message}.
 */
final class AppOffers {

    /** The launcher takes back the shell's read of a drop after 15 minutes (PcDrags.GRANT_MS). */
    static final long KEEP_MS = 15 * 60_000L;
    private static final int KEEP_MAX = 32;

    /** One thing on offer: a file (its URI), or text. */
    static final class Item {
        String uri;
        String name;
        String mime;
        long size = -1;
        String text;

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

        Offer(List<Item> items) {
            this.items = items;
        }
    }

    private final Map<Integer, Offer> offers = new LinkedHashMap<>();
    private int next;

    /** Offer {@code items}: their number, for the computer to take them by. */
    synchronized int put(List<Item> items) {
        long now = SystemClock.uptimeMillis();
        for (Iterator<Offer> i = offers.values().iterator(); i.hasNext(); ) {
            Offer o = i.next();
            if (now - o.at > KEEP_MS || offers.size() >= KEEP_MAX) i.remove();
        }
        int id = ++next;
        offers.put(id, new Offer(items));
        return id;
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
            try (Content.Opened o = Content.open(Uri.parse(it.uri))) {
                long size = o.length >= 0 ? o.length : it.size;
                wire.json(new JSONObject().put("t", "get").put("ok", true).put("name", it.name)
                        .put("mime", it.mime == null ? "" : it.mime).put("size", size));
                OutputStream out = sock.getOutputStream();
                byte[] buf = new byte[256 * 1024];
                long sent = 0;
                long t0 = SystemClock.uptimeMillis();
                for (int n; (n = o.in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sent += n;
                }
                out.flush();
                AppServer.log("sent " + it.name + " to the computer (" + sent + " bytes in "
                        + (SystemClock.uptimeMillis() - t0) + " ms)");
            } catch (IOException | RuntimeException e) {
                AppServer.log("could not read " + it.name + " (" + e + ")");
                wire.json(failed("The phone could not read it."));
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
