package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each app's window was last left, so opening it again puts it back.
 *
 * A desktop that deals every window onto the same cascade is a desktop the user
 * re-arranges every session. This is the other half: {@link CaptionService}
 * watches the freeform tasks it already polls and writes down each one's rect
 * once it stops moving, and {@link LauncherActivity} asks here before it falls
 * back to {@link LauncherActivity#nextWindowBounds()}.
 *
 * The rect is stored WITH the display it was measured on. A desktop's pixel
 * size is not a constant — the Display size setting rewrites the density, the
 * Stream section's resolution preset rewrites the whole display, and a session
 * on a laptop is a different display from one on a monitor. A rect recalled
 * flat onto a smaller display puts half the window (or all of it) off screen,
 * which is worse than the cascade it replaced. So a recall onto a display of a
 * different size is SCALED into it and then clamped, which keeps what the user
 * actually chose — "top-left quarter", "tall and narrow on the right" — rather
 * than the pixel numbers that expressed it.
 *
 * One preferences string rather than a row per app: the whole map is read on
 * every launch and rewritten whenever a window settles, and at this size (a few
 * hundred bytes) parsing it costs less than the SharedPreferences lookups a
 * key-per-app scheme would need to enumerate it.
 *
 * Records are held most-recently-used first and the tail is dropped past
 * {@link #MAX_RECORDS}, so a phone with a thousand apps installed cannot grow
 * this without bound — the apps that get used are the ones that stay.
 */
final class WindowMemory {

    private WindowMemory() {
    }

    /**
     * How many apps are remembered. Well past what any desktop holds at once,
     * and small enough that the whole string stays a few kilobytes.
     */
    private static final int MAX_RECORDS = 120;

    /** Records are {@code key l t r b dw dh}, joined by this. */
    private static final String SEP = "|";

    /**
     * A window smaller than this in either axis is not a window the user chose
     * — it is a task caught mid-transition, or a rect that survived a display
     * change it should not have. Recalling one would open an app into a sliver.
     */
    private static final int MIN_PX = 120;

    /** Whether the desktop is remembering at all — the Settings switch. */
    static boolean enabled(Context ctx) {
        return DexPrefs.getBool(ctx, DexPrefs.KEY_WINDOW_MEMORY, DexPrefs.DEF_WINDOW_MEMORY);
    }

    /**
     * What a window is remembered by.
     *
     * The package, because that is the granularity the desktop thinks in: the
     * taskbar pins packages, the PC's running list is packages, and a user who
     * puts "Chrome" somewhere means Chrome, not one of its activities.
     *
     * Our OWN package is the exception, and it has to be: Settings, Linux,
     * Docker, the Web viewer and the Task Manager are five separate windows
     * behind one package name, and keyed on the package alone the last one
     * moved would decide where all five open. They each own a task (see their
     * taskAffinity in the manifest), so the activity is what tells them apart.
     */
    static String keyFor(Context ctx, String pkg, String activity) {
        if (pkg == null || pkg.isEmpty()) return "";
        if (!ctx.getPackageName().equals(pkg)) return pkg;
        if (activity == null || activity.isEmpty() || "?".equals(activity)) return pkg;
        return pkg + "/" + activity.substring(activity.lastIndexOf('.') + 1);
    }

    /** The key an activity of ours opens under — the recall side of {@link #keyFor}. */
    static String keyFor(Context ctx, Class<?> own) {
        return ctx.getPackageName() + "/" + own.getSimpleName();
    }

    /**
     * Write down where this window is now, as the newest record.
     *
     * A no-op when the rect is already the newest thing stored, because this is
     * called off a poll: rewriting an unchanged map several times a second
     * would burn a SharedPreferences commit per window per pass for nothing.
     */
    static void remember(Context ctx, String key, Rect bounds, Point display) {
        if (!enabled(ctx) || key == null || key.isEmpty() || bounds == null) return;
        if (bounds.width() < MIN_PX || bounds.height() < MIN_PX) return;
        if (display == null || display.x <= 0 || display.y <= 0) return;

        List<String> records = load(ctx);
        String record = key + " " + bounds.left + " " + bounds.top + " "
                + bounds.right + " " + bounds.bottom + " " + display.x + " " + display.y;
        if (!records.isEmpty() && records.get(0).equals(record)) return;

        List<String> next = new ArrayList<>(records.size() + 1);
        next.add(record);
        String prefix = key + " ";
        for (String existing : records) {
            if (existing.startsWith(prefix)) continue;   // superseded by the record above
            if (next.size() >= MAX_RECORDS) break;
            next.add(existing);
        }
        save(ctx, next);
    }

    /**
     * Where this window went last time, fitted to the display it is opening on
     * — or null when nothing is remembered, the feature is off, or what was
     * remembered cannot be made to fit.
     *
     * {@code bottomInset} is the taskbar: it is composited above app windows
     * for the whole session, so a recalled window that runs under it owns rows
     * it can never show.
     */
    static Rect recall(Context ctx, String key, Point display, int bottomInset) {
        if (!enabled(ctx) || key == null || key.isEmpty()) return null;
        if (display == null || display.x <= 0 || display.y <= 0) return null;
        String prefix = key + " ";
        for (String record : load(ctx)) {
            if (!record.startsWith(prefix)) continue;
            String[] p = record.split(" ");
            if (p.length < 7) return null;
            try {
                return fit(new Rect(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                                Integer.parseInt(p[3]), Integer.parseInt(p[4])),
                        Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                        display, bottomInset);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * The stored rect, put onto this display.
     *
     * Scaled first when the display it was measured on was a different size —
     * see the class note — and then clamped, because scaling preserves
     * proportion but not the two hard limits: a window may not be taller than
     * the space above the taskbar, and it may not span the full display width.
     *
     * That last one is the handshake with the PC enforcer, which reads a task
     * spanning the whole width as somebody else maximising the window and
     * reacts to it (scrcpy.rs, Enforcer::fills). A recalled window is a launch,
     * not a maximise, so it stops 4px short — the same margin
     * {@link CaptionService}'s own maximise leaves.
     */
    private static Rect fit(Rect stored, int storedW, int storedH,
                            Point display, int bottomInset) {
        Rect r = new Rect(stored);
        if (storedW > 0 && storedH > 0 && (storedW != display.x || storedH != display.y)) {
            float sx = (float) display.x / storedW;
            float sy = (float) display.y / storedH;
            r.set(Math.round(r.left * sx), Math.round(r.top * sy),
                    Math.round(r.right * sx), Math.round(r.bottom * sy));
        }
        int maxW = Math.max(MIN_PX, display.x - 4);
        int maxH = Math.max(MIN_PX, display.y - bottomInset - 4);
        int w = Math.min(r.width(), maxW);
        int h = Math.min(r.height(), maxH);
        if (w < MIN_PX || h < MIN_PX) return null;
        int x = Math.max(0, Math.min(r.left, display.x - w));
        int y = Math.max(0, Math.min(r.top, display.y - bottomInset - h));
        return new Rect(x, y, x + w, y + h);
    }

    /** How many windows are remembered — what the Settings row counts. */
    static int count(Context ctx) {
        return load(ctx).size();
    }

    /** Forget every window. The Settings row, and the reset scope. */
    static void forget(Context ctx) {
        DexPrefs.prefs(ctx).edit().remove(DexPrefs.KEY_WINDOW_GEOMETRY).apply();
    }

    private static List<String> load(Context ctx) {
        String raw = DexPrefs.getString(ctx, DexPrefs.KEY_WINDOW_GEOMETRY, "");
        List<String> records = new ArrayList<>();
        if (raw.isEmpty()) return records;
        for (String record : raw.split("\\" + SEP)) {
            if (!record.isEmpty()) records.add(record);
        }
        return records;
    }

    /**
     * Written straight through the editor rather than {@link DexPrefs#put}: a
     * settled window is not a setting, and broadcasting one would repaint the
     * whole shell every time a drag stopped.
     */
    private static void save(Context ctx, List<String> records) {
        StringBuilder joined = new StringBuilder();
        for (String record : records) {
            if (joined.length() > 0) joined.append(SEP);
            joined.append(record);
        }
        DexPrefs.prefs(ctx).edit()
                .putString(DexPrefs.KEY_WINDOW_GEOMETRY, joined.toString())
                .apply();
    }
}
