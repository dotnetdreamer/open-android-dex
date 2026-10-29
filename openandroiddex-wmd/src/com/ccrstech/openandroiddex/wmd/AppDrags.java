package com.ccrstech.openandroiddex.wmd;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drags carried out of the computer's windows onto the computer
 * (doc/apps-on-pc.md, "Dragging out"): the server's half.
 *
 * <p>The window that hears a drag on an app window's display is the
 * launcher's (PcDrags): one pixel at its top left, which this process — no
 * app — could not have. It is asked for as a window opens and let go of as it
 * closes, and what the drags there do — started, dropped on it, ended — comes
 * here on one call to the launcher that waits until something happens, as
 * Linux's windows do (AppServer.pumpLinux). Each goes to its window
 * ({@link AppWindow#dragEvent}), which tells the computer, and which drops a
 * drag on the pixel when the computer's pointer leaves the window with one.
 *
 * <p>A launcher from before this, or a phone before Android 12, has no such
 * window: dragging out simply does not happen — the windows work as they did.
 * It is asked again as the next window opens, not before: the desktop, or the
 * phone's touchpad, may have brought the launcher up to date meanwhile.
 */
final class AppDrags {

    /** How long a call to the launcher waits for a drag to do something. */
    private static final long WAIT_MS = 10_000;

    private final Map<Integer, AppWindow> byDisplay = new ConcurrentHashMap<>();
    /** Displays the launcher would put no window on: not asked again. */
    private final Set<Integer> refusedOn = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** One thing at a time asked of the launcher, off the windows' threads: its first call may start its process. */
    private final ExecutorService asking = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apps-drags-ask");
        t.setDaemon(true);
        return t;
    });
    /** Why the launcher could not catch a drag at all when last asked: too old for it, or the phone too. */
    private volatile String unsupported;
    private boolean polling;

    /** {@code w} has its display: drags there are heard from now on. */
    void watch(AppWindow w) {
        int display = w.displayId;
        if (display < 0) return;
        byDisplay.put(display, w);
        asking.execute(() -> ask(display, true));
    }

    /** {@code w}'s display is going: its window there goes with it. */
    void unwatch(AppWindow w) {
        int display = w.displayId;
        if (byDisplay.remove(display) == null) return;
        refusedOn.remove(display);
        if (unsupported != null) return;
        asking.execute(() -> {
            try {
                Providers.call(AppServer.LINUX_PROVIDER, "drag.unwatch", new JSONObject().put("display", display));
            } catch (Exception ignored) {
                // the display, and the window with it, are going anyway
            }
        });
    }

    /** Asks for the launcher's window on {@code display}; a window just opened asks even where the last one could not. */
    private void ask(int display, boolean opened) {
        if ((unsupported != null && !opened) || !byDisplay.containsKey(display) || refusedOn.contains(display)) return;
        try {
            JSONObject r = Providers.call(AppServer.LINUX_PROVIDER, "drag.watch", new JSONObject().put("display", display));
            if (r.optBoolean("ok")) {
                unsupported = null;
                poll();
                return;
            }
            String why = r.optString("message");
            if (why.startsWith("no method") || why.contains("needs Android")) {
                cannot(why, "nothing can be dragged out of the windows here");
            } else {
                refusedOn.add(display);
                AppServer.log("drags: no window to catch them on display " + display + " (" + why + ")");
            }
        } catch (Exception e) {
            cannot(String.valueOf(e), "no launcher to catch them");
        }
    }

    /** The launcher cannot catch a drag: said once, not again for each window that opens. */
    private void cannot(String why, String saying) {
        if (!why.equals(unsupported)) AppServer.log("drags: " + saying + " (" + why + ")");
        unsupported = why;
    }

    /** What the drags on every watched display do, until none is watched. */
    private synchronized void poll() {
        if (polling) return;
        polling = true;
        Thread t = new Thread(() -> {
            int failures = 0;
            while (!byDisplay.isEmpty()) {
                JSONObject r;
                try {
                    r = Providers.call(AppServer.LINUX_PROVIDER, "drag.events", new JSONObject().put("wait", WAIT_MS));
                    failures = 0;
                } catch (Exception e) {
                    if (++failures >= 5) {
                        AppServer.log("drags: the launcher stopped answering (" + e + ")");
                        break;
                    }
                    android.os.SystemClock.sleep(1000);
                    continue;
                }
                JSONArray events = r.optJSONArray("events");
                for (int i = 0; events != null && i < events.length(); i++) {
                    JSONObject e = events.optJSONObject(i);
                    AppWindow w = e == null ? null : byDisplay.get(e.optInt("display", -1));
                    if (w != null) w.dragEvent(e);
                }
                // A launcher whose process was ended has no windows any more: each
                // asked for again — and a drag it was hearing will never be said to end.
                JSONArray watching = r.optJSONArray("watching");
                if (watching != null) {
                    Set<Integer> has = new HashSet<>();
                    for (int i = 0; i < watching.length(); i++) has.add(watching.optInt(i));
                    for (Map.Entry<Integer, AppWindow> e : byDisplay.entrySet()) {
                        int d = e.getKey();
                        if (has.contains(d) || refusedOn.contains(d)) continue;
                        e.getValue().dragLost();
                        asking.execute(() -> ask(d, false));
                    }
                }
            }
            synchronized (AppDrags.this) {
                polling = false;
            }
            // A window that opened as this ended.
            if (!byDisplay.isEmpty() && failures == 0) poll();
        }, "apps-drags");
        t.setDaemon(true);
        t.start();
    }
}
