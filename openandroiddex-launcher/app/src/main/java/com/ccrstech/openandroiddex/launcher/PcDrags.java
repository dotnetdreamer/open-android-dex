package com.ccrstech.openandroiddex.launcher;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A drag in one of the phone's apps, in its window on a computer, carried out
 * of that window onto the computer (doc/apps-on-pc.md, "Dragging out"): this
 * app's half.
 *
 * <p>Android tells every window on a display that a drag an app let go of —
 * a "global" one — has started there and when it ends, and gives what it
 * carries, with the right to read it, to the window it is dropped on. The
 * computer's half on the phone, the shell's app server, is no app, and Android
 * 14 gives no window to a process the activity manager did not start ("Unknown
 * pid"). So the window is this app's: one pixel at the top left of each app
 * window's display, out of everyone's way. When the computer's pointer leaves
 * its window with a drag held, the server moves the phone's pointer onto that
 * pixel and lets go, and the drop is this window's.
 *
 * <p>The pixel takes no touch and shows nothing while no drag is under way —
 * fully transparent and untouchable, a window Android lets every touch pass
 * through. It takes one only from the moment a drag starts until it ends, when
 * the drag has the pointer anyway.
 *
 * <p>Up to Android 16 the pixel is a presentation window. Android 17 hides
 * every other app's activities on a display that shows a presentation: with no
 * activity of this app's on screen (no desktop running) the window's own app
 * was never shown, and its window on the computer waited on "Opening on …" for
 * good. There it is an overlay, which hides nothing beneath it, for as long as
 * the server has this app let draw over others (its LauncherOverlay), and none
 * where it has not: dragging out stops, the window does not. An overlay has a
 * cost of its own: Android hides every app's overlays while an app that wants
 * none is on screen — the Files app, Settings, a prompt such as "Allow wireless
 * debugging" — so a drag out of those is not heard on Android 17. Nor would a
 * presentation be that came only at the hand-over: Android 17 tells no window
 * that comes while a drag is under way that it started (tried, 2026-10-31).
 *
 * <p>What is dropped is handed on, not kept: each content URI granted to the
 * shell (for {@link #GRANT_MS}, then taken back), which reads it itself, and
 * the items — with their names and sizes, which this app may ask for — said
 * to the server as an event ({@link AppsProvider}'s {@code drag.events}).
 * Android 12 and later: the drop and its read permission come through the
 * platform's own content listener, no hidden call.
 */
final class PcDrags {

    static final String SHELL_PACKAGE = "com.android.shell";
    /**
     * WindowManager.LayoutParams.TYPE_PRESENTATION, hidden by name: a window on
     * a public presentation display, which every app window's display is, and
     * which any app may add — no overlay permission, and not one of the windows
     * Android hides while Settings shows.
     */
    private static final int TYPE_PRESENTATION = WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW + 37;
    /**
     * Android 17, by number: the first that hides the apps under a
     * presentation (see the class comment), and so the first where the
     * pixel is an overlay only. Said to the server as {@code drag.caps}.
     */
    static final int OVERLAY_ONLY_FROM = 37;
    /** How long the shell may read what was dropped: the computer copies it as it is dropped there. */
    private static final long GRANT_MS = 15 * 60_000L;
    private static final int EVENTS_MAX = 64;

    private static PcDrags instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** By display id. Changed on the main thread; the server's calls list it. */
    private final Map<Integer, Catcher> catchers = new java.util.concurrent.ConcurrentHashMap<>();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    /** What was granted to the shell, and until when. Main thread. */
    private final List<Grant> grants = new ArrayList<>();

    private static final class Grant {
        final Uri uri;
        final long until;

        Grant(Uri uri, long until) {
            this.uri = uri;
            this.until = until;
        }
    }

    static synchronized PcDrags get(Context ctx) {
        if (instance == null) instance = new PcDrags(ctx.getApplicationContext());
        return instance;
    }

    private PcDrags(Context app) {
        this.app = app;
        app.getSystemService(DisplayManager.class).registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // The window went with its display: nothing to take down.
                catchers.remove(displayId);
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        }, main);
    }

    /**
     * A catcher on {@code displayId}, if it has none yet. Main thread. Null
     * when it is there, else why not.
     */
    String watch(int displayId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "dragging out needs Android 12";
        if (catchers.containsKey(displayId)) return null;
        Display d = app.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (d == null) return "no display " + displayId;
        String refused = null;
        // A presentation window first: no permission to ask for. Where it is
        // refused, an overlay, if this app may draw over others. From Android
        // 17 an overlay only: a presentation there hides the window's own app.
        boolean overlayOnly = Build.VERSION.SDK_INT >= OVERLAY_ONLY_FROM;
        int[] types = overlayOnly
                ? new int[]{WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}
                : new int[]{TYPE_PRESENTATION, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY};
        for (int type : types) {
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && !android.provider.Settings.canDrawOverlays(app)) {
                if (overlayOnly) refused = "this app may not draw over other apps";
                break;
            }
            try {
                Context window = app.createDisplayContext(d).createWindowContext(type, null);
                WindowManager wm = window.getSystemService(WindowManager.class);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.alpha = 0f;
                lp.setTitle("Open Android DeX drop");
                Catcher c = new Catcher(window, displayId, wm, lp);
                wm.addView(c, lp);
                catchers.put(displayId, c);
                DexLog.step("drags", "catching drags on display " + displayId
                        + (type == TYPE_PRESENTATION ? "" : ", as an overlay"));
                return null;
            } catch (RuntimeException e) {
                refused = String.valueOf(e);
                DexLog.warn("drags", "no window on display " + displayId + " (type " + type + "): " + e);
            }
        }
        return refused != null ? refused : "this app may not put a window there";
    }

    /** The display's window has closed. Main thread. */
    void unwatch(int displayId) {
        Catcher c = catchers.remove(displayId);
        if (c == null) return;
        try {
            c.wm.removeViewImmediate(c);
        } catch (RuntimeException ignored) {
            // the display, and the window with it, went first
        }
    }

    /** What happened since the last call, waiting up to {@code waitMs} for something to. Any thread but the main one. */
    JSONArray takeEvents(long waitMs) {
        JSONArray out = new JSONArray();
        long until = SystemClock.uptimeMillis() + waitMs;
        synchronized (events) {
            while (events.isEmpty()) {
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    events.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            while (!events.isEmpty()) out.put(events.pollFirst());
        }
        return out;
    }

    /** The displays this app has a window on: one ended with this app's process is asked for again. */
    JSONArray watching() {
        JSONArray a = new JSONArray();
        for (Integer d : catchers.keySet()) a.put(d);
        return a;
    }

    private void event(JSONObject j) {
        synchronized (events) {
            // Nobody asking: the oldest go, never this app's memory.
            while (events.size() >= EVENTS_MAX) events.pollFirst();
            events.addLast(j);
            events.notifyAll();
        }
    }

    // ── a drag, as one display's window hears it (main thread) ──

    private void started(Catcher c, ClipDescription desc) {
        if (c.dragging) return;
        c.dragging = true;
        // Touchable now, and shown, so the server's drop can land: Android
        // gives input to no window with nothing in it to see.
        c.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        c.lp.alpha = 1f;
        relayout(c);
        JSONObject j = new JSONObject();
        try {
            JSONArray mimes = new JSONArray();
            for (int i = 0; desc != null && i < desc.getMimeTypeCount(); i++) mimes.put(desc.getMimeType(i));
            j.put("k", "started").put("display", c.display).put("mimes", mimes);
            if (desc != null && desc.getLabel() != null) j.put("label", desc.getLabel().toString());
        } catch (Exception ignored) {
        }
        event(j);
    }

    private void ended(Catcher c, boolean result) {
        if (!c.dragging) return;
        c.dragging = false;
        c.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        c.lp.alpha = 0f;
        relayout(c);
        JSONObject j = new JSONObject();
        try {
            j.put("k", "ended").put("display", c.display).put("result", result);
        } catch (Exception ignored) {
        }
        event(j);
    }

    private void relayout(Catcher c) {
        try {
            c.wm.updateViewLayout(c, c.lp);
        } catch (RuntimeException e) {
            DexLog.warn("drags", "display " + c.display + ": " + e);
        }
    }

    /**
     * Dropped here: what it carries, each URI the shell's to read. Its read
     * permission is this app's for now (the platform took it as it called the
     * listener), and a holder of one may pass it on.
     */
    private void dropped(Catcher c, ClipData clip) {
        expireGrants();
        JSONObject j = new JSONObject();
        try {
            JSONArray items = new JSONArray();
            for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
                ClipData.Item it = clip.getItemAt(i);
                JSONObject item = new JSONObject();
                Uri uri = it.getUri();
                if (uri != null && "content".equals(uri.getScheme())) {
                    item.put("uri", uri.toString());
                    try {
                        app.grantUriPermission(SHELL_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        grants.add(new Grant(uri, SystemClock.uptimeMillis() + GRANT_MS));
                    } catch (RuntimeException e) {
                        item.put("grant", String.valueOf(e));
                        DexLog.warn("drags", "could not hand " + uri.getAuthority() + " to the shell: " + e);
                    }
                    describe(uri, item);
                } else if (uri != null) {
                    item.put("uri", uri.toString());
                }
                CharSequence text = it.getText();
                if (text != null) item.put("text", text.toString());
                if (item.length() > 0) items.put(item);
            }
            j.put("k", "dropped").put("display", c.display).put("items", items);
            if (clip != null && clip.getDescription().getLabel() != null) {
                j.put("label", clip.getDescription().getLabel().toString());
            }
        } catch (Exception e) {
            DexLog.warn("drags", "a drop on display " + c.display + ": " + e);
        }
        event(j);
    }

    /** Its type, name and size, as its provider says them: this app may ask. */
    private void describe(Uri uri, JSONObject item) {
        try {
            String mime = app.getContentResolver().getType(uri);
            if (mime != null) item.put("mime", mime);
        } catch (Exception ignored) {
        }
        try (Cursor cur = app.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int name = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int size = cur.getColumnIndex(OpenableColumns.SIZE);
                if (name >= 0 && !cur.isNull(name)) item.put("name", cur.getString(name));
                if (size >= 0 && !cur.isNull(size)) item.put("size", cur.getLong(size));
            }
        } catch (Exception e) {
            DexLog.warn("drags", "no name for " + uri.getAuthority() + ": " + e);
        }
    }

    /** What the shell may still read and no longer needs to. */
    private void expireGrants() {
        long now = SystemClock.uptimeMillis();
        for (Iterator<Grant> i = grants.iterator(); i.hasNext(); ) {
            Grant g = i.next();
            if (g.until > now) continue;
            i.remove();
            try {
                app.revokeUriPermission(SHELL_PACKAGE, g.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
            }
        }
        if (!grants.isEmpty()) {
            main.removeCallbacks(expire);
            main.postDelayed(expire, GRANT_MS);
        }
    }

    private final Runnable expire = this::expireGrants;

    /** One display's pixel: it hears every drag there, and takes the one the server drops on it. */
    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private final class Catcher extends View {
        final int display;
        final WindowManager wm;
        final WindowManager.LayoutParams lp;
        boolean dragging;

        Catcher(Context ctx, int display, WindowManager wm, WindowManager.LayoutParams lp) {
            super(ctx);
            this.display = display;
            this.wm = wm;
            this.lp = lp;
            // The platform's own way in for a drop: it takes the read
            // permission the drag carries, then hands the content over.
            // Every type there is — "*/*" itself it refuses.
            setOnReceiveContentListener(new String[]{"application/*", "audio/*", "font/*", "image/*",
                    "message/*", "model/*", "multipart/*", "text/*", "video/*"}, (v, payload) -> {
                dropped(this, payload.getClip());
                return null;
            });
        }

        @Override
        public boolean onDragEvent(DragEvent e) {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    started(this, e.getClipDescription());
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    ended(this, e.getResult());
                    break;
                default:
            }
            return super.onDragEvent(e);
        }
    }
}
