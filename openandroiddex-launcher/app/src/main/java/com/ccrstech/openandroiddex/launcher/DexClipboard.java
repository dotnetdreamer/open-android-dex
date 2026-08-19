package com.ccrstech.openandroiddex.launcher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading and remembering the device clipboard, within what Android allows.
 *
 * Since Android 10 an app may only read the clipboard while it holds window
 * focus (or is the default IME). A background clipboard watcher is therefore
 * impossible without privilege we do not have — what IS possible, and is what
 * this does, is to snapshot the clipboard whenever the Settings window is in
 * front and keep a short history of what it saw. Everything the user copies on
 * the desktop while Settings is open, or copies from the PC (scrcpy pushes it
 * into the device clipboard), lands here.
 *
 * The PC half of the pipe is scrcpy's own two-way clipboard sync, so setting
 * the clipboard here is also how you get text onto the PC.
 */
final class DexClipboard {

    private DexClipboard() {
    }

    /** Entries kept. Long enough to be useful, short enough to stay a list. */
    private static final int MAX = 20;
    /** Nothing longer is stored — a copied document would bloat the prefs file. */
    private static final int MAX_CHARS = 4000;

    static ClipboardManager manager(Context ctx) {
        return (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /**
     * The clipboard's current text, or "" when it is empty, holds something
     * that is not text, or is unreadable because the caller does not have
     * focus. All three are the same thing to the UI: nothing to show.
     */
    static String current(Context ctx) {
        try {
            ClipboardManager cm = manager(ctx);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(ctx);
            return text == null ? "" : text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Put text on the device clipboard; scrcpy carries it to the PC. */
    static boolean set(Context ctx, String text) {
        try {
            ClipboardManager cm = manager(ctx);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("Open Android DeX", text));
            remember(ctx, text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean clear(Context ctx) {
        try {
            ClipboardManager cm = manager(ctx);
            if (cm == null) return false;
            // clearPrimaryClip is API 28+; an empty clip is the fallback and
            // reads the same to every consumer
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                cm.clearPrimaryClip();
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Most recent first. Stored as a JSON array so any text survives a round trip. */
    static List<String> history(Context ctx) {
        List<String> out = new ArrayList<>();
        String raw = DexPrefs.getString(ctx, DexPrefs.KEY_CLIP_HISTORY, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String entry = array.optString(i, "");
                if (!entry.isEmpty()) out.add(entry);
            }
        } catch (Exception ignored) {
            // a corrupt history is not worth a crash; it is a convenience list
        }
        return out;
    }

    /** Move {@code text} to the front of the history, de-duplicated. */
    static void remember(Context ctx, String text) {
        if (text == null) return;
        String entry = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        if (entry.trim().isEmpty()) return;
        List<String> items = history(ctx);
        if (!items.isEmpty() && items.get(0).equals(entry)) return;   // nothing new
        items.remove(entry);
        items.add(0, entry);
        while (items.size() > MAX) {
            items.remove(items.size() - 1);
        }
        save(ctx, items);
    }

    static void forget(Context ctx, String entry) {
        List<String> items = history(ctx);
        if (items.remove(entry)) save(ctx, items);
    }

    static void clearHistory(Context ctx) {
        save(ctx, new ArrayList<>());
    }

    private static void save(Context ctx, List<String> items) {
        JSONArray array = new JSONArray();
        for (String entry : items) {
            array.put(entry);
        }
        // written straight, not through DexPrefs.put: the history is not a
        // setting and must not make the shell rebuild itself
        DexPrefs.prefs(ctx).edit()
                .putString(DexPrefs.KEY_CLIP_HISTORY, array.toString()).apply();
    }
}
