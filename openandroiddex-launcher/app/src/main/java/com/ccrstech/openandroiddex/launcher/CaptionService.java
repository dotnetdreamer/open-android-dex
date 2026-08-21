package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-window title bars that cannot separate from their window.
 *
 * The mechanism is {@link AccessibilityService#attachAccessibilityOverlayToWindow}. Its
 * server side does not put the surface on a display overlay layer — it forwards the
 * SurfaceControl to the target app's own process, where
 * AccessibilityInteractionController runs
 * {@code t.reparent(sc, mViewRootImpl.getSurfaceControl())}. Our caption therefore
 * becomes a CHILD LAYER of the app's own window surface, which is the same structural
 * guarantee AOSP's desktop caption gets from {@code .setParent(mTaskSurface)}:
 *
 *   - z-order among other freeform windows is correct with no invariant to maintain
 *   - the caption translates, raises and animates with its window for free
 *   - TYPE_APPLICATION_OVERLAY's policy-layer-11 problem never arises
 *   - Window#setHideOverlayWindows cannot suppress it
 *   - the system marks it a trusted overlay, so it does not trip untrusted-touch blocking
 *
 * The space it draws into is reserved by the shell-uid daemon via setAppBounds +
 * addInsetsSource(captionBar()) — see WmClient#strip. That half is measured working on
 * SM-S938B and on an AOSP Pixel alike; see doc/custom-titlebar-v2.md §0.
 *
 * The caption is clipped to the task bounds by Task#updateSurfaceSize's setWindowCrop,
 * so all chrome must live strictly inside the task rect. Hence "reserve a strip at the
 * top" rather than "float a bar above the window".
 *
 * WHAT WE ATTACH TO is the shell's own caption window when there is one — inside it our
 * bar covers the platform's exactly — and the task's app window when there is not. The
 * second case is not hypothetical: measured on an AOSP Pixel (API 36), Chrome's caption
 * window took 12 SECONDS to appear in the accessibility window list after its window was
 * already on screen, and a shell that does not decorate this display produces none at
 * all. Waiting for it unconditionally is what left windows wearing no bar; the wait is
 * therefore bounded per task by HOST_GRACE_MS — see {@link #hostOnAppWindow}.
 *
 * The app window is a good host where nothing occludes it: it is sized to the FULL task
 * rect (measured on that Pixel: task 184,36-1240,814, app window 1056x778 — the whole
 * task, strip included, with only the app's LAYOUT inset by our captionBar source), so a
 * bar at its top lands exactly in the reserved strip. What it cannot do is beat a caption
 * the shell actually draws: that one is a sibling in the task's decor container at
 * z=30000, above the app window, so a bar hosted underneath it is invisible. Hence the
 * grace before falling back, and hence pickHost still running on every pass afterwards.
 */
public final class CaptionService extends AccessibilityService {

    private static final String TAG = "DexCaption";

    /**
     * The connected service, or null when accessibility is off.
     *
     * Held for {@link WebInput}, which needs a real AccessibilityService to
     * dispatch a gesture — that is the only way an app on this phone can put a
     * touch into another app's window, and this is the only accessibility
     * service we have. A strong static reference to a Service is safe in a way
     * one to an Activity is not: the system owns its lifetime, and onDestroy
     * below clears it.
     */
    private static volatile CaptionService live;

    static CaptionService live() {
        return live;
    }

    /**
     * The display the desktop shell is hosted on, or &lt;0 when there is no live
     * desktop session (or the window daemon has not answered yet).
     *
     * Read by the Web viewer, which always captures the DEFAULT display: when
     * this is a SECONDARY display (id &gt; 0) the desktop is elsewhere and the
     * viewer is necessarily showing the phone, which the page says out loud.
     */
    static int desktopDisplay() {
        CaptionService s = live;
        return s == null ? -1 : s.desktopDisplayId;
    }

    /**
     * Fallback caption height in px on the desktop display (density 1.0, so px == dp).
     *
     * Used only when One UI's own decoration cannot be measured. Our bar must be at least
     * as tall as the platform's caption, otherwise the difference shows as a sliver of the
     * old title bar peeking out underneath ours — One UI's is 40 px on this device.
     */
    private static final int CAPTION_PX = 40;

    /** A candidate taller than this is the app window, not a caption. */
    private static final int CAPTION_MAX_PX = 72;

    /** taskId -> caption height actually in use, measured from the platform decor. */
    private final Map<Integer, Integer> captionHeights = new HashMap<>();

    private int captionHeight(int taskId) {
        Integer h = captionHeights.get(taskId);
        return h == null ? CAPTION_PX : h;
    }

    /** Poll cadence for reconciling captions against the live task list, when idle. */
    private static final long RECONCILE_MS = 400L;

    /** Cadence while windows are moving. One LIST round trip measures ~2.5 ms. */
    private static final long RECONCILE_BUSY_MS = 60L;


    private final Handler main = new Handler(Looper.getMainLooper());
    private WmClient wm;

    /** a11y window id -> live caption host. */
    /**
     * taskId -> its one caption.
     *
     * Keyed by TASK, not by accessibility window id: a task's host window id changes
     * whenever the app's window is recreated, and a window-keyed map orphans the old
     * entry under the old key. The surface stays in the layer tree, so the task ends up
     * wearing two identical bars with two live touch handlers, and every rebuild leaks
     * another SurfaceControlViewHost.
     */
    private final SparseArray<Caption> captions = new SparseArray<>();
    /** taskId -> last known task, refreshed by the daemon poll. */
    private final Map<Integer, WmClient.Task> tasks = new HashMap<>();

    /** The same tasks in the daemon's z-order, topmost first. See applyTasks. */
    private List<WmClient.Task> lastStack = new ArrayList<>();

    /**
     * taskId -> opaque strip that hides One UI's own caption until ours attaches.
     *
     * A freshly launched window shows the platform's bare title bar for the handful of
     * passes it takes the caption host to appear in the accessibility window list and our
     * real caption to attach on top of it — that gap is the launch "flash". A curtain the
     * same colour as our bar, drawn at the task's caption strip the instant the window is
     * seen, covers it so the swap to the real caption is seamless.
     */
    private final SparseArray<View> curtains = new SparseArray<>();
    /** taskId -> uptime the curtain went up, so one that never yields a host can give up. */
    private final Map<Integer, Long> curtainSince = new HashMap<>();
    /** Task ids that must NOT be curtained: already dressed, or gave up waiting for a host. */
    private final Set<Integer> noCurtain = new HashSet<>();

    /**
     * Tasks we maximised. A hint only — onMaximise branches on the window's LIVE rect, not
     * on this, because nothing reconciles a remembered flag against a window that anything
     * else on the desktop can resize. Kept because it is still the cheapest way to say "our
     * ▢ put it there" for the drag and snap paths, which unset it.
     */
    private final Set<Integer> maximized = new HashSet<>();
    /** taskId -> {l,t,r,b} to restore to, captured the first time it is maximised/snapped. */
    private final Map<Integer, int[]> restoreBounds = new HashMap<>();
    /**
     * Longest a curtain stays up without a real caption landing. After this we remove it and
     * accept the platform caption, so an app that never exposes a host (e.g. activity
     * embedding) falls back to a visible One UI bar rather than wearing a dead strip forever.
     */
    private static final long CURTAIN_MAX_MS = 1500L;

    /** Volatile: written on the service's main thread, read by the Web server's. */
    private volatile int desktopDisplayId = -1;
    private boolean running;
    private int ticks;

    /**
     * taskId -> uptime we started waiting for a shell caption window to host on.
     *
     * The shell's caption window is the host we WANT — inside it our bar covers the
     * platform's exactly. But waiting for it unconditionally is what left windows bare:
     * measured on an AOSP Pixel, Chrome's caption window took 12 SECONDS to reach the
     * accessibility window list after its window was already on screen, and a shell that
     * does not decorate this display at all never produces one. Either way the curtain
     * gives up at CURTAIN_MAX_MS and the user is left looking at an undressed window.
     *
     * So the wait is bounded per task: after HOST_GRACE_MS we host on the app window
     * instead, and keep looking. This is a wait, not a verdict — {@link #pickHost} still
     * runs every pass, and if the shell's caption turns up later the bar rebuilds onto it.
     */
    private final Map<Integer, Long> hostWaitSince = new HashMap<>();

    /**
     * How long a task may go without a shell caption window before we host on its app
     * window instead.
     *
     * Not shorter, because One UI's caption window is missing from the list for roughly
     * one pass in two even when it exists, and hosting on the app window there is
     * INVISIBLE — below One UI's own caption in the task's decor container at z=30000. A
     * blink must never be enough to trigger this; only a real absence. Longer than
     * CURTAIN_MAX_MS would mean the curtain gives up first and the bare window shows
     * through, which is the whole thing we are fixing.
     */
    private static final long HOST_GRACE_MS = 1200L;

    /**
     * Accessibility title stamped on our own bars, so a host picker can tell OUR caption
     * from the platform's. Geometry cannot: ours sits at the same origin, spans the same
     * width and is the same height as the thing it covers.
     */
    private static final String CAPTION_WINDOW_TITLE = "openandroiddex-caption";

    private static final class Caption {
        SurfaceControlViewHost host;
        TextView title;
        int taskId;
        int width;
        int height;
        /** Accessibility window this is attached to; changes when the host is recreated. */
        int windowId;
    }

    /** Breadcrumb file: logcat from this uid is unreliable on One UI, a file is not. */
    private void trace(String what) {
        Log.i(TAG, what);
        try (java.io.FileWriter w =
                     new java.io.FileWriter(new java.io.File(getFilesDir(), "caption.log"), true)) {
            w.write(System.currentTimeMillis() + " " + what + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * A theme or shell change repaints the captions. There is no way to
     * re-colour or re-measure a surface that is already attached inside
     * another app's window, so the bars are dropped and the reconcile pass —
     * which rebuilds any task without one — puts them back in the new look on
     * its next tick.
     *
     * The allowlist below is the whole contract: a setting that is NOT in it
     * leaves every open window wearing the old chrome until it is closed and
     * opened again. Anything that reaches {@link #build} has to be here.
     */
    private final android.content.BroadcastReceiver themeReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                    String key = intent.getStringExtra(DexPrefs.EXTRA_KEY);
                    if (!DexPrefs.KEY_THEME.equals(key) && !DexPrefs.KEY_DARK.equals(key)
                            // The shell decides the bar's palette AND its
                            // geometry — Windows 11 captions carry wider
                            // buttons and a red close. Neither can be changed
                            // on a bar that is already attached.
                            && !DexPrefs.KEY_SHELL.equals(key)
                            && !DexPrefs.KEY_PAPER_TEXTURE.equals(key)
                            && !DexPrefs.KEY_GRAIN.equals(key)
                            // suppresses the grain, so the captions carry the
                            // old finish until they are rebuilt too
                            && !DexPrefs.KEY_PERF.equals(key) && !"*".equals(key)
                            // a PointerIcon is stored on the view, not asked
                            // for per event, so a new cursor needs a new bar
                            && !DexCursors.isCursorKey(key)) {
                        return;
                    }
                    for (int i = 0; i < captions.size(); i++) release(captions.valueAt(i));
                    captions.clear();
                    trace("theme, shell or cursor changed — captions dropped for rebuild");
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        trace("onCreate");
    }

    @Override
    protected void onServiceConnected() {
        trace("onServiceConnected sdk=" + android.os.Build.VERSION.SDK_INT);
        // Published even on the API < 34 path below, where captions are inert:
        // gesture dispatch has no such floor, so the Web viewer's control
        // still works on a phone this service can draw nothing on.
        live = this;
        if (android.os.Build.VERSION.SDK_INT < 34) {
            // attachAccessibilityOverlayToWindow is API 34. Below that there is no way to
            // get a surface into another app's window, so the service is inert rather
            // than half-working.
            Log.w(TAG, "needs API 34+, captions disabled");
            return;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        wm = new WmClient();
        running = true;
        android.content.IntentFilter f =
                new android.content.IntentFilter(LauncherActivity.ACTION_RESTORE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(restoreReceiver, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(restoreReceiver, f);
        }
        // "Show desktop" lives here rather than in the launcher because both
        // halves of the toggle do — the live task list AND the record of what
        // we hid. Splitting them would mean two owners for one piece of state.
        android.content.IntentFilter desktop =
                new android.content.IntentFilter(LauncherActivity.ACTION_SHOW_DESKTOP);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(showDesktopReceiver, desktop, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(showDesktopReceiver, desktop);
        }
        android.content.IntentFilter settings =
                new android.content.IntentFilter(DexPrefs.ACTION_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(themeReceiver, settings, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(themeReceiver, settings);
        }
        // This service is restarted on every reconnect (see restart_caption_service), and
        // the minimised map does not survive that. Say so, or the launcher keeps pinning
        // packages nothing here can un-hide any more — a taskbar icon that does nothing.
        publishMinimised();
        main.post(reconcile);
        trace("connected");
    }

    @Override
    public void onDestroy() {
        running = false;
        live = null;
        try {
            unregisterReceiver(restoreReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(showDesktopReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(themeReceiver);
        } catch (Exception ignored) {
        }
        main.removeCallbacks(reconcile);
        for (int i = 0; i < captions.size(); i++) release(captions.valueAt(i));
        captions.clear();
        for (int i = 0; i < curtains.size(); i++) {
            try {
                View v = curtains.valueAt(i);
                v.getContext().getSystemService(WindowManager.class).removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }
        curtains.clear();
        curtainSince.clear();
        noCurtain.clear();
        maximized.clear();
        restoreBounds.clear();
        dropAnchor();
        if (wm != null) {
            // Leave no window permanently inset if we go away.
            final WmClient client = wm;
            final Map<Integer, WmClient.Task> snapshot = new HashMap<>(tasks);
            final int display = desktopDisplayId;
            client.post(() -> {
                for (WmClient.Task t : snapshot.values()) client.unstrip(display, t.taskId);
                client.shutdown();
            });
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Surface recreation silently orphans an attached overlay, and window changes are
        // when that happens, so re-run the whole reconcile rather than patching one entry.
        main.removeCallbacks(reconcile);
        main.post(reconcile);
    }

    @Override
    public void onInterrupt() {
    }

    // ── reconcile ──────────────────────────────────────────────────────────

    private final Runnable reconcile = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // getWindows() and every AccessibilityWindowInfo touch must stay on the
            // service's main thread; only the socket round trip goes to the io thread.
            wm.post(() -> {
                final int display = wm.desktopDisplay();
                final List<WmClient.Task> live = display < 0 ? null : wm.list(display);
                main.post(() -> {
                    if (!running) return;
                    if (display != desktopDisplayId) {
                        trace("desktop display = " + display);
                        desktopDisplayId = display;
                        dropAnchor();               // the anchor belongs to the old display
                        hostWaitSince.clear();      // those task ids are on the old display
                    }
                    if (ticks++ % 25 == 0) {
                        trace("tick display=" + display + " tasks="
                                + (live == null ? "?" : live.size())
                                + " captions=" + captions.size());
                    }
                    // A null list is "the daemon did not answer", not "nothing is open" —
                    // see WmClient#list. Skipping the pass costs one tick; acting on it
                    // would release every caption and un-pin every minimised window.
                    boolean busy = live != null && applyTasks(display, live);
                    main.removeCallbacks(reconcile);
                    // Poll fast while geometry is actually changing, idle slowly otherwise.
                    // A fixed 400ms tick means a window moved by anything other than our own
                    // title bar — a platform edge-resize, an app moving itself — drags its
                    // caption behind it for up to a full tick. Chasing that with a
                    // permanently fast tick would spend a round trip to the daemon 16x a
                    // second forever, for windows that are usually still.
                    main.postDelayed(reconcile, busy ? RECONCILE_BUSY_MS : RECONCILE_MS);
                });
            });
        }
    };

    /**
     * The desktop is the non-default display the scrcpy session created. Its id changes
     * every session, so it must never be hard-coded — and it is re-resolved whenever the
     * display goes away, because a reconnect produces a new id.
     *
     * Note getWindows() is DEFAULT-DISPLAY ONLY; windows on a secondary display are only
     * visible through getWindowsOnAllDisplays().
     */
    private List<AccessibilityWindowInfo> windowsOnDisplay(int display) {
        SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
        return all == null ? null : all.get(display);
    }

    private List<AccessibilityWindowInfo> windowsOn(int display) {
        SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
        if (all == null) return null;
        return all.get(display);
    }

    /** Set by ensureCaption when a bar had to be moved or rebuilt this pass. */
    private boolean moved;

    /**
     * The desktop itself — the launcher's own home task, which must never wear
     * a caption because it IS the background.
     *
     * This used to exclude our whole package, which also excluded our Settings
     * window: it is a normal freeform window and was the one window on the
     * desktop still wearing One UI's own title bar. Settings runs in its own
     * task (see its taskAffinity in the manifest), so the activity name tells
     * the two apart — and the Linux window is exempted the same way, for the
     * same reason.
     */
    private boolean isDesktopTask(WmClient.Task t) {
        return getPackageName().equals(t.pkg)
                && !SettingsActivity.class.getName().equals(t.activity)
                && !LinuxActivity.class.getName().equals(t.activity)
                && !DockerActivity.class.getName().equals(t.activity)
                && !TaskManagerActivity.class.getName().equals(t.activity);
    }

    /** @return true if any caption geometry changed, i.e. windows are on the move. */
    private boolean applyTasks(int display, List<WmClient.Task> live) {
        moved = false;
        tasks.clear();
        // Kept in the daemon's order — TOPMOST FIRST — because stacking depends on it.
        // The map alone cannot carry this; HashMap order is not the z-order.
        List<WmClient.Task> stack = new ArrayList<>();
        for (WmClient.Task t : live) {
            if (t.isFreeform() && t.visible && !isDesktopTask(t)) {
                tasks.put(t.taskId, t);
                stack.add(t);
            }
        }
        // Kept because `tasks` cannot carry it — see onShowDesktop, which is
        // the one caller that has to put windows back in the order it found
        // them.
        lastStack = stack;

        // A minimised task stops being minimised when it comes back, and stops existing
        // when it is really closed. Either way the taskbar should stop pinning it.
        //
        // "Came back" cannot just mean `visible`. setHidden is applied inside a TRANSITION,
        // and TaskInfo#isVisible only flips when that transition COMMITS — a couple of
        // hundred ms later, while this poll runs every 60ms and is kicked immediately by
        // the window-change event the minimise itself produces. Reading that pre-transition
        // `true` as "it is back" un-pinned every window on the very next pass after it was
        // minimised, so the app vanished from the taskbar and the open-apps popup the
        // instant it was minimised — the PC's list is built from visibility and cannot tell
        // a minimised task from a closed one, and this map was the only thing that could.
        //
        // So a visible reading counts only once the hide has actually been SEEN to land,
        // with a grace window to unpin a hide that never lands at all.
        if (!minimised.isEmpty()) {
            boolean changed = false;
            long now = SystemClock.uptimeMillis();
            for (java.util.Iterator<Map.Entry<Integer, Min>> it =
                    minimised.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Min> e = it.next();
                Min m = e.getValue();
                WmClient.Task t = byId(live, e.getKey());
                if (t == null) {
                    it.remove();                       // closed while it was minimised
                    changed = true;
                } else if (!t.visible) {
                    m.hidden = true;                   // the hide landed; now it can be undone
                } else if (m.hidden || now - m.since > HIDE_GRACE_MS) {
                    it.remove();                       // restored — or the hide never took
                    changed = true;
                }
            }
            if (changed) publishMinimised();
        }

        List<AccessibilityWindowInfo> windows = windowsOnDisplay(display);
        if (windows == null) {
            if (ticks % 25 == 0) trace("no a11y windows for display " + display);
            return false;
        }

        // One caption per TASK. For each task pick the single best host window — see
        // pickHost — rather than decorating every a11y window that overlaps the rect.
        // Height is measured from the platform's own decoration first, because the strip
        // we reserve and the bar we draw both have to cover it exactly.
        int missing = 0;
        for (WmClient.Task t : stack) {
            // The shell's caption window first — it is the better host, and it is still
            // checked on every pass even for a task already wearing an app-window bar, so
            // a caption that turns up late is adopted rather than left drawing over us.
            AccessibilityWindowInfo host = pickHost(windows, t);
            if (host == null) {
                host = hostOnAppWindow(windows, t);   // null until the grace runs out
            } else {
                hostWaitSince.remove(t.taskId);
            }
            if (host != null) captionHeights.put(t.taskId, measureCaption(host));
            final int px = captionHeight(t.taskId);
            // Reserve — or RE-reserve — the strip. The app-bounds override does not follow
            // a task that moves, so this also repairs a stale one after a drag or resize.
            if (!t.hasStrip(px)) {
                final int taskId = t.taskId;
                wm.post(() -> wm.strip(display, taskId, px));
            }
            // Title and size first, and without needing the host — a window resized on a
            // pass where the host is missing must still get a correctly sized bar.
            boolean ok = syncCaption(t);
            if (host == null) {
                // The host window is not in the accessibility list this pass. On One UI
                // that is the platform's caption blinking out; do NOT fall back to the app
                // window there, because a caption hosted below One UI's own never appears,
                // which reads as "it works sometimes" rather than as a failure. Where the
                // shell draws no caption at all the app window IS the host and was already
                // tried above. Either way: keep whatever bar this task has, try next pass.
                //
                // Only a task with NO bar yet is worth hurrying for. Counting the others
                // would hold the fast tick on forever, since the host is absent for a good
                // fraction of passes even when everything on screen is already correct.
                //
                // While the host is missing the platform's own caption is on screen with
                // nothing of ours over it — the launch flash. Cover it with an opaque strip,
                // but only for a task we have never dressed (never throw a curtain over a
                // working caption whose host just blinked out), and give up after
                // CURTAIN_MAX_MS so a window that never yields a host is not left with a dead
                // bar.
                if (captions.get(t.taskId) == null && !noCurtain.contains(t.taskId)) {
                    Long since = curtainSince.get(t.taskId);
                    if (since != null
                            && SystemClock.uptimeMillis() - since > CURTAIN_MAX_MS) {
                        hideCurtain(t.taskId);
                        noCurtain.add(t.taskId);
                    } else {
                        showCurtain(t);
                    }
                }
                if (!ok) missing++;
                continue;
            }
            ensureCaption(host, t);
        }
        if (missing > 0) {
            moved = true;          // poll fast until every task has a bar
            if (ticks % 25 == 0) {
                trace("waiting for a caption host on " + missing + "/" + stack.size()
                        + " tasks");
            }
        }

        // Drop captions whose task is gone (closed, or no longer freeform/visible).
        // Nothing else releases them: the loop above only ever visits LIVE tasks, so a
        // closed window's SurfaceControlViewHost would be held for the life of the
        // service. Descending, because removal shifts later indices.
        for (int i = captions.size() - 1; i >= 0; i--) {
            int taskId = captions.keyAt(i);
            if (tasks.containsKey(taskId)) continue;
            release(captions.valueAt(i));
            captions.removeAt(i);
            captionHeights.remove(taskId);
            hostWaitSince.remove(taskId);
            noCurtain.remove(taskId);
            maximized.remove(taskId);
            restoreBounds.remove(taskId);
        }

        // Curtains outlive their caption's absence, so sweep them on the same rule: a task
        // that is no longer live (closed, or no longer freeform/visible) must not keep an
        // opaque strip floating where its window used to be.
        for (int i = curtains.size() - 1; i >= 0; i--) {
            int taskId = curtains.keyAt(i);
            if (tasks.containsKey(taskId)) continue;
            hideCurtain(taskId);
            noCurtain.remove(taskId);
        }
        // A task that closed while still waiting for a host never reaches the sweep above,
        // because it never had a caption to release.
        hostWaitSince.keySet().retainAll(tasks.keySet());
        rememberGeometry(display, stack);
        return moved;
    }

    // ── remembering where each window was left ─────────────────────────────

    /** Last rect seen for a live task, so a change can be told from a repeat. */
    private final Map<Integer, int[]> geometrySeen = new HashMap<>();
    /** When that rect was first seen — the settle clock. */
    private final Map<Integer, Long> geometrySince = new HashMap<>();
    /** Task ids whose current rect has already been written down. */
    private final Set<Integer> geometrySaved = new HashSet<>();

    /**
     * How long a window has to hold still before its position is worth keeping.
     *
     * A drag arrives here as a stream of distinct rects at the busy poll rate,
     * and a resize the same; writing each one would be a SharedPreferences
     * commit every 60ms for the length of the gesture, and the intermediate
     * positions are not what the user chose anyway. Comfortably longer than the
     * busy poll and shorter than "did it save?" — the user lets go, and by the
     * time they have moved the pointer it is recorded.
     */
    private static final long GEOMETRY_SETTLE_MS = 700L;

    /**
     * Write down where each window has come to rest, for
     * {@link LauncherActivity#windowBoundsFor} to open it at next time.
     *
     * This is done from the reconcile poll rather than from the drag handler
     * because the poll is the only place that sees ALL of it. Our own title bar
     * is one of several things that move a window: One UI's edge-resize, the
     * PC-side enforcer, the caption's own maximise and snap, and an app moving
     * itself all bypass {@link #dragHandler} entirely, and a memory that only
     * learned from our own drags would forget every one of them.
     *
     * Only tasks in {@code stack} are considered, which is already the right
     * set: freeform, visible, and not the desktop itself. A minimised window is
     * not in it, so a minimise cannot be mistaken for a move.
     */
    private void rememberGeometry(int display, List<WmClient.Task> stack) {
        if (stack.isEmpty()) {
            geometrySeen.clear();
            geometrySince.clear();
            geometrySaved.clear();
            return;
        }
        if (!WindowMemory.enabled(this)) return;
        long now = SystemClock.uptimeMillis();
        for (WmClient.Task t : stack) {
            int[] rect = {t.left, t.top, t.right, t.bottom};
            int[] previous = geometrySeen.get(t.taskId);
            if (previous == null || !java.util.Arrays.equals(previous, rect)) {
                geometrySeen.put(t.taskId, rect);
                geometrySince.put(t.taskId, now);
                geometrySaved.remove(t.taskId);
                continue;
            }
            if (geometrySaved.contains(t.taskId)) continue;
            Long since = geometrySince.get(t.taskId);
            if (since == null || now - since < GEOMETRY_SETTLE_MS) continue;
            geometrySaved.add(t.taskId);
            WindowMemory.remember(this, WindowMemory.keyFor(this, t.pkg, t.activity),
                    new android.graphics.Rect(t.left, t.top, t.right, t.bottom),
                    displaySize(display));
        }
        // A closed window's entries would otherwise be held for the life of the
        // service, and its task id can be handed to a different app later.
        geometrySeen.keySet().retainAll(tasks.keySet());
        geometrySince.keySet().retainAll(tasks.keySet());
        geometrySaved.retainAll(tasks.keySet());
    }

    /**
     * Height of One UI's own caption for this window, when we can see it.
     *
     * The platform's decoration is itself an accessibility window: same origin and width
     * as the task, only a caption tall. Matching its height is what stops a sliver of the
     * old title bar showing beneath ours.
     */
    private int measureCaption(AccessibilityWindowInfo host) {
        android.graphics.Rect r = new android.graphics.Rect();
        host.getBoundsInScreen(r);
        int h = r.height();
        if (h > CAPTION_MAX_PX) return CAPTION_PX;   // that was the app window
        // Only ever grow to cover, never shrink to match. Once our bar exists it is itself
        // an accessibility window at the same origin and width as the platform's caption,
        // so a measurement can land on *us* — and a plain `return h` then latches the
        // height at whatever we last drew. Shrinking is what leaves a sliver of the old
        // title bar visible below ours; being a few px too tall costs nothing.
        return Math.max(h, CAPTION_PX);
    }

    /**
     * The platform's own caption window for this task: TYPE_APPLICATION, taskId -1, at the
     * task origin, task-wide, only a caption tall. Both the host we attach to and the thing
     * we measure, so our bar covers One UI's exactly.
     *
     * Returns null when it is not in the list this pass — which happens, transiently, for
     * roughly one task in two. Null means "not now", not "never": the caller keeps the bar
     * the task already has and tries again on the next pass.
     *
     * Ties break on the lowest window id so the result does not depend on the list order,
     * which is not stable between passes and would otherwise rebuild the caption endlessly.
     */
    private AccessibilityWindowInfo pickHost(List<AccessibilityWindowInfo> windows,
                                             WmClient.Task task) {
        AccessibilityWindowInfo decor = null;
        int taskHeight = task.bottom - task.top;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            w.getBoundsInScreen(r);
            // The host must span the FULL task width. Our surface is a child of the host
            // window's surface and is cropped to it: hosting on a narrower window yields a
            // bar clipped to that window even though the buffer is task-wide. Measured on
            // two-pane Settings — 705px buffer, composited 397..693. So an app using
            // activity embedding gets no caption rather than a half-covered one.
            if (Math.abs(r.left - task.left) > 2 || Math.abs(r.right - task.right) > 2) continue;
            if (r.top < task.top - 2 || r.top > task.appTop + 2) continue;
            // Caption-shaped only. There is deliberately NO fallback to the app window:
            // hosting there is below One UI's caption and therefore invisible, so falling
            // back does not degrade gracefully, it fails silently — and because this
            // window blinks out of the list for the odd pass, the fallback also caused a
            // rebuild every time it did, flipping the host back and forth forever.
            // Returning null instead means "keep what this task already has, try again".
            if (r.height() <= CAPTION_PX * 3
                    && !isOurs(w)
                    && (decor == null || w.getId() < decor.getId())) {
                decor = w;
            }
        }
        return decor;
    }

    /**
     * The task's OWN app window: same rect as the task, and taller than any caption.
     *
     * This is the host on a shell that draws no caption window — see the class javadoc.
     * The height test is what keeps it honest: it excludes both a platform caption and
     * OUR bar, which is itself a window in this list at the same origin and width, and
     * which would otherwise be re-adopted as its own host on the pass after it attached.
     *
     * Matched on the task rect rather than on a package name because a window's package
     * costs a getRoot() round trip per window per pass, and the rect is already decisive:
     * a full-width, full-height window at the task's origin belongs to that task.
     *
     * Ties break on the lowest window id — the base window rather than a later child of
     * the same size (YouTube keeps two) — so the host does not change with list order,
     * which is not stable between passes and would rebuild the bar endlessly.
     */
    private AccessibilityWindowInfo appHost(List<AccessibilityWindowInfo> windows,
                                            WmClient.Task task) {
        AccessibilityWindowInfo app = null;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (isOurs(w)) continue;
            w.getBoundsInScreen(r);
            if (Math.abs(r.left - task.left) > 2 || Math.abs(r.right - task.right) > 2) continue;
            if (Math.abs(r.top - task.top) > 2) continue;
            if (r.height() <= CAPTION_MAX_PX) continue;      // a caption, or one of ours
            if (app == null || w.getId() < app.getId()) app = w;
        }
        return app;
    }

    /** One of our own bars, by the title stamped on it in {@link #params}. */
    private static boolean isOurs(AccessibilityWindowInfo w) {
        CharSequence t = w.getTitle();
        return t != null && CAPTION_WINDOW_TITLE.contentEquals(t);
    }

    /**
     * The app window, but only once this task has waited HOST_GRACE_MS for a shell caption
     * window that never came.
     *
     * The clock starts on the first pass this task wanted a host and had none, and is
     * cleared the moment a shell caption does turn up, so a task that merely blinks never
     * reaches the grace. A task already wearing an app-window bar keeps returning here
     * (same window, so ensureCaption is a no-op) until pickHost finds something better.
     *
     * @return the host to attach to, or null meaning "not yet — keep waiting".
     */
    private AccessibilityWindowInfo hostOnAppWindow(List<AccessibilityWindowInfo> windows,
                                                    WmClient.Task task) {
        // Only ever dress an UNDRESSED window. A task that already has a bar is having its
        // shell caption blink out of the list — the normal case, not an absence — and
        // moving it to the app window and back on every blink rebuilds the surface twice a
        // second. Measured doing exactly that before this guard: 231 -> 233 -> 231 in nine
        // seconds. Keeping the bar it has is what the null-host path already does.
        if (captions.get(task.taskId) != null) return null;
        long now = SystemClock.uptimeMillis();
        Long since = hostWaitSince.get(task.taskId);
        if (since == null) {
            hostWaitSince.put(task.taskId, now);
            return null;
        }
        if (now - since < HOST_GRACE_MS) return null;
        AccessibilityWindowInfo app = appHost(windows, task);
        if (app != null) {
            trace("no caption host for task " + task.taskId
                    + " after " + (now - since) + "ms — hosting on its app window");
        }
        return app;
    }

    /**
     * Attach the bar INSIDE One UI's own caption window for this task.
     *
     * This is the whole design, and everything good about it follows from where the surface
     * lives rather than from code here. Inside the platform's caption window our surface is
     * a descendant of the task, so it moves with the window, clips with it, stacks with it,
     * and disappears behind whatever the window disappears behind — all for free, at the
     * compositor's expense rather than ours. There is no lag because nothing has to
     * reposition it, and no occlusion logic because nothing has to compute it.
     *
     * The alternatives were both tried and are both worse:
     *  - the app's own window: also glued, but permanently BELOW One UI's caption, which is
     *    a sibling of the app window in the task's decor container at z=30000. Invisible.
     *  - the DISPLAY's accessibility overlay: always visible, but above every app window, so
     *    it has to be moved by the reconcile loop (visible lag when dragging) and manually
     *    cropped against the windows in front (a rectangle cannot express an occluder that
     *    lands in the middle of a bar, so bars end up visibly cut).
     *
     * The one weakness is that the host window is not always in the accessibility window
     * list — measured missing for roughly one task in two, transiently. That is what made
     * captions "sometimes not appear", and it is a retry problem, not a design problem:
     * pickHost is re-evaluated every pass and this attaches as soon as the window shows up.
     */
    /**
     * Keep an existing bar's title and size current. Deliberately independent of the host
     * window, so a resize lands even on the passes where the host is not in the list.
     *
     * @return true if the bar is now correct and needs no rebuild.
     */
    private boolean syncCaption(WmClient.Task task) {
        Caption c = captions.get(task.taskId);
        if (c == null) return false;
        c.title.setText(labelFor(task));

        int width = task.right - task.left;
        int height = captionHeight(task.taskId);
        if (c.width == width && c.height == height) return true;
        if (!relayout(c.host, width, height)) return false;   // caller rebuilds
        c.width = width;
        c.height = height;
        moved = true;                                          // a resize is motion
        return true;
    }

    private void ensureCaption(AccessibilityWindowInfo host, WmClient.Task task) {
        int id = host.getId();
        int width = task.right - task.left;
        int height = captionHeight(task.taskId);
        Caption c = captions.get(task.taskId);

        // Already correct, and still on the window we think it is.
        if (c != null && c.windowId == id && c.width == width && c.height == height) return;
        if (c != null) {
            release(c);
            captions.remove(task.taskId);
        }

        try {
            Caption made = build(task, width, height);
            if (made == null) return;
            SurfaceControl sc = surfaceOf(made);
            if (sc == null) {
                release(made);
                return;
            }
            // Host-relative, and the host is not always at the task's origin. One UI's
            // caption window is (offset 0), but an app window on a shell without captions
            // may start below the strip we reserved, and a bar pinned to its top-left
            // would then sit ON the app's content with the strip left empty above it.
            // Measure the offset instead of assuming it; the surface is cropped to the
            // TASK, not to the host, so a negative y is legal and lands in the strip.
            android.graphics.Rect hb = new android.graphics.Rect();
            host.getBoundsInScreen(hb);
            final float dy = task.top - hb.top;
            attachAccessibilityOverlayToWindow(id, sc);
            // The attach only reparents and marks the surface trusted — it never shows it.
            // In normal SurfaceControlViewHost use the host SurfaceView does that
            // (SurfaceView#setChildSurfacePackage calls show). Nothing does it for an
            // accessibility overlay, so the surface sits in the tree permanently
            // "hidden by ... layer flag". We own the layer, so we show it ourselves and
            // lift it above the host's own content.
            new SurfaceControl.Transaction()
                    .setVisibility(sc, true)          // show() is @hide; this is the public one
                    .setLayer(sc, Integer.MAX_VALUE)
                    .setPosition(sc, 0f, dy)          // host-relative: the task moves us
                    .apply();
            made.windowId = id;
            captions.put(task.taskId, made);
            // The real caption now covers the platform's — retire the launch curtain and
            // stop curtaining this task (a later host blink-out must not re-cover it).
            noCurtain.add(task.taskId);
            hideCurtain(task.taskId);
            moved = true;          // a resize is motion too — keep polling fast
            trace("attached window=" + id + " task=" + task.taskId
                    + " w=" + width + " h=" + height + " sc=" + sc.isValid());
        } catch (Throwable t) {
            trace("attach failed task=" + task.taskId + ": " + t);
        }
    }

    private static SurfaceControl surfaceOf(Caption c) {
        return c.host == null || c.host.getSurfacePackage() == null
                ? null : c.host.getSurfacePackage().getSurfaceControl();
    }

    private Caption build(WmClient.Task task, int width, int height) {
        Display display = getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(task.displayId);
        // Views MUST be inflated against the target display's Context, not the service's.
        // The service's resources describe the phone panel (~450dpi); the desktop runs at
        // 160dpi. Using the wrong one scales every sp/dp by ~2.8 and the caption comes out
        // enormous.
        final android.content.Context ui = createDisplayContext(display);

        Caption c = new Caption();
        c.taskId = task.taskId;
        c.width = width;
        c.height = height;
        c.host = newHost(display);
        if (c.host == null) return null;

        DexTheme theme = DexTheme.of(this);
        LinearLayout bar = new LinearLayout(ui);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Always opaque: this strip is inside the app's own window surface, so
        // anything see-through here would show the app, not the desktop. In
        // Paper mode it carries the same grain as the rest of the chrome.
        bar.setBackground(theme.surface(theme.caption, 0f));
        bar.setPadding(px(10), 0, 0, 0);
        // The strip is the window's move handle — its drag listener is attached
        // below — so it says so. Whether a pointer resolves at all here is the
        // one open question in this file: the bar lives in a
        // SurfaceControlViewHost reparented into the APP's window surface, not
        // in a window of ours, and if hover does not cross that boundary the
        // platform arrow simply stands. That is an acceptable outcome; nothing
        // else depends on it.
        DexCursors.apply(bar, DexCursors.ROLE_GRAB);

        c.title = new TextView(ui);
        c.title.setText(labelFor(task));
        c.title.setTextColor(theme.textDim);
        // Absolute px against the desktop display: the strip is a fixed CAPTION_PX tall,
        // so the text has to be sized in the same space rather than in scaled sp.
        c.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        c.title.setSingleLine(true);
        bar.addView(c.title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Snap to half the display, left or right — the same controls the commercial DeX
        // exposes from its title-bar dropdown, as explicit buttons here (a PopupWindow inside
        // a SurfaceControlViewHost on a virtual display is unreliable; buttons are not).
        //
        // The widget detour's window is an alert box waiting for an answer, so it gets none
        // of them. Snapping and maximising resize a dialog that will not grow, and — is
        // worse than useless: onMinimise HIDES the task, a hidden dialog delivers no result,
        // and the add would hang with an allocated widget id nobody can reach. ✕ stays,
        // because ours is a real close (wmd removeTask, not One UI's hide), which the detour
        // turns into a clean cancel.
        if (!WidgetDetourActivity.isDetourTask(task.taskId)) {
            bar.addView(button(ui, "◧", false, v -> onSnapLeft(task)), buttonParams(height));
            bar.addView(button(ui, "◨", false, v -> onSnapRight(task)), buttonParams(height));
            bar.addView(button(ui, "—", false, v -> onMinimise(task)), buttonParams(height));
            bar.addView(button(ui, "▢", false, v -> onMaximise(task)), buttonParams(height));
        }
        bar.addView(button(ui, "✕", true, v -> onClose(task)), buttonParams(height));
        bar.setOnTouchListener(dragHandler(c, task));

        setView(c.host, bar, width, height);
        return c;
    }

    /**
     * A SurfaceControlViewHost needs an InputTransferToken. Which one is right depends
     * entirely on where the surface is going to live.
     *
     * For an overlay attached to a WINDOW it must belong to a live window, so that
     * WindowlessWindowManager#addToDisplay -> IWindowSession#grantInputChannel accepts it;
     * a freshly-minted Binder fails in the worst possible way, with the constructor and the
     * attach both succeeding and the surface simply never appearing in the layer tree.
     *
     * For an overlay attached to a DISPLAY — what we do now — a NEW token is correct, and
     * borrowing one is actively wrong: the borrowed token parents our surface under that
     * window, and the reparent onto the display's overlay layer then leaves it with no
     * parent at all. Same silent symptom, opposite cause.
     */
    private SurfaceControlViewHost newHost(Display display) {
        Object token = anchorToken(display);
        if (token == null) {
            if (ticks % 25 == 0) trace("no host token yet");
            return null;
        }
        try {
            return SurfaceControlViewHost.class
                    .getConstructor(android.content.Context.class, Display.class,
                            Class.forName("android.window.InputTransferToken"))
                    .newInstance(this, display, token);
        } catch (Throwable t) {
            trace("SurfaceControlViewHost failed: " + t);
            return null;
        }
    }

    /** 1x1 invisible window that exists only to own a valid host token. */
    private View anchorView;
    private int anchorDisplay = -1;

    /**
     * Our own token source, so captions do not depend on the launcher Activity being up.
     * An AccessibilityService may add TYPE_ACCESSIBILITY_OVERLAY windows without
     * SYSTEM_ALERT_WINDOW, and a window context binds it to the desktop display.
     *
     * The anchor draws nothing and takes no input; only its InputTransferToken matters.
     * A window overlay needs a token belonging to a LIVE window — a freshly minted Binder,
     * or a fresh InputTransferToken, fails in the worst possible way: the constructor and
     * the attach both succeed and the surface never appears in the layer tree.
     */
    private Object anchorToken(Display display) {
        try {
            if (anchorView == null || anchorDisplay != display.getDisplayId()) {
                dropAnchor();
                android.content.Context ctx = createDisplayContext(display)
                        .createWindowContext(
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
                View v = new View(ctx);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                ctx.getSystemService(WindowManager.class).addView(v, lp);
                anchorView = v;
                anchorDisplay = display.getDisplayId();
                trace("anchor added on display " + anchorDisplay);
            }
            android.view.AttachedSurfaceControl root = anchorView.getRootSurfaceControl();
            return root == null ? null : root.getInputTransferToken();
        } catch (Throwable t) {
            trace("anchor failed: " + t);
            return null;
        }
    }

    private void dropAnchor() {
        if (anchorView == null) return;
        try {
            anchorView.getContext().getSystemService(WindowManager.class)
                    .removeViewImmediate(anchorView);
        } catch (Throwable ignored) {
        }
        anchorView = null;
        anchorDisplay = -1;
    }

    /**
     * Only {@code setView(View, int, int)} is public, and it builds LayoutParams with
     * {@code flags = 0} — i.e. a focusable window. We want FLAG_NOT_FOCUSABLE so keyboard
     * focus stays with the app while touch still lands on the strip (input dispatch does
     * not require focus for touch). The LayoutParams overload that would let us say so is
     * {@code @hide}, so try it reflectively and fall back to the public one.
     */
    private static void setView(SurfaceControlViewHost host, View view, int w, int h) {
        try {
            SurfaceControlViewHost.class
                    .getMethod("setView", View.class, WindowManager.LayoutParams.class)
                    .invoke(host, view, params(w, h));
            return;
        } catch (Throwable ignored) {
            // hidden-API enforcement or a signature change — public path below still works
        }
        host.setView(view, w, h);
    }

    private static WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        // So a host picker can recognise our own bars in the window list — see isOurs.
        // The field is @hide, hence the reflection, and the whole thing is optional: if it
        // does not land, or an embedded hierarchy never surfaces the title, every picker
        // still rejects our bars on height. This is only ever a second, cheaper filter.
        try {
            WindowManager.LayoutParams.class
                    .getField("accessibilityTitle")
                    .set(lp, CAPTION_WINDOW_TITLE);
        } catch (Throwable ignored) {
        }
        return lp;
    }

    /**
     * Resize an existing bar in place.
     *
     * Rebuilding instead would need the host window, and the host is missing on a fair
     * share of passes — so a window resized while it is missing would keep a bar at the old
     * width. The surface is then clipped to the (narrower) window and the buttons, which
     * sit at the right end, simply vanish until the window is widened again.
     *
     * Note the public {@code relayout(int,int)} builds LayoutParams with {@code flags = 0},
     * i.e. a FOCUSABLE window — the same trap as {@code setView}. Every resize would hand
     * the bar keyboard focus and take it from the app. Prefer the LayoutParams overload.
     *
     * @return false if the host cannot be resized, in which case the caller rebuilds.
     */
    private static boolean relayout(SurfaceControlViewHost host, int w, int h) {
        if (host == null) return false;
        try {
            SurfaceControlViewHost.class
                    .getMethod("relayout", WindowManager.LayoutParams.class)
                    .invoke(host, params(w, h));
            return true;
        } catch (Throwable ignored) {
            // fall through to the public form
        }
        try {
            host.relayout(w, h);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** px on the desktop display, which runs at density 1.0 — so dp and px coincide. */
    private static int px(int dp) {
        return dp;
    }

    private LinearLayout.LayoutParams buttonParams(int height) {
        // 46px under the Windows 11 shell, which is the width its own caption
        // buttons are; ours is narrower because the DeX strip carries five.
        return new LinearLayout.LayoutParams(px(DexTheme.of(this).win11 ? 46 : 38), height);
    }

    /**
     * One caption control.
     *
     * {@code close} marks the ✕, and only matters under the Windows 11 shell:
     * a red close button is the one piece of that title bar everybody can name,
     * and it is also a real affordance — the destructive control being the one
     * you cannot hit by accident without seeing it go red first.
     *
     * The fills are a StateListDrawable rather than a ripple: this view lives
     * in a SurfaceControlViewHost reparented into the app's own window surface,
     * where whether hover crosses the boundary at all is the open question of
     * this file (see build). Pressed does cross it, so the button is never
     * silent even where hover turns out not to resolve.
     */
    private TextView button(android.content.Context ui, String glyph, boolean close,
                            View.OnClickListener onClick) {
        DexTheme theme = DexTheme.of(this);
        TextView b = new TextView(ui);
        b.setText(glyph);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(onClick);

        boolean hot = close && theme.win11;
        int lit = hot ? 0xFFc42b1c : theme.hover;
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(lit));
        bg.addState(new int[]{android.R.attr.state_hovered}, new ColorDrawable(lit));
        bg.addState(new int[0], new ColorDrawable(0x00000000));
        b.setBackground(bg);
        b.setTextColor(hot
                // white on the red, in both themes — the fill is the same red
                // either way, so the ink over it cannot follow the palette
                ? new ColorStateList(
                        new int[][]{{android.R.attr.state_pressed},
                                {android.R.attr.state_hovered}, {}},
                        new int[]{0xFFffffff, 0xFFffffff, theme.textDim})
                : ColorStateList.valueOf(theme.textDim));

        DexCursors.apply(b, DexCursors.ROLE_HAND);
        return b;
    }

    /**
     * Drag the window by its title bar.
     *
     * The bar is a child of the app's window surface, so it travels with the task for
     * free — we only have to move the task. Bounds are pushed through the daemon's MOVE,
     * which uses applyTransaction (measured 2.49 ms on this device) rather than a
     * transition: transitions serialize behind one another and cannot be issued per
     * frame, applyTransaction can.
     *
     * Latest-wins coalescing, never drop: the final frame of a gesture carries the final
     * position, so discarding it would leave the window short of where it was released.
     */
    private View.OnTouchListener dragHandler(Caption caption, WmClient.Task task) {
        return new View.OnTouchListener() {
            private int grabDx, grabDy;
            private boolean dragging;
            private int wantX, wantY;
            private boolean pending;
            /** Uptime of the last tap that was a tap, not a drag. 0 = no tap pending. */
            private long lastTap;
            private int lastTapX, lastTapY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int rawX = (int) e.getRawX();
                int rawY = (int) e.getRawY();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        // Double-click the bar to maximise/restore, as on Windows. Only the
                        // empty space gets here: the buttons are child views and consume
                        // their own taps, so this cannot fire from ✕ or ▢. The title is a
                        // plain TextView, not clickable, so it counts as empty space.
                        if (lastTap != 0
                                && SystemClock.uptimeMillis() - lastTap <= DOUBLE_TAP_MS
                                && Math.abs(rawX - lastTapX) <= DOUBLE_TAP_SLOP
                                && Math.abs(rawY - lastTapY) <= DOUBLE_TAP_SLOP) {
                            lastTap = 0;          // consumed: a third tap starts a new pair
                            dragging = false;
                            onMaximise(task);
                            return true;
                        }
                        grabDx = rawX - task.left;
                        grabDy = rawY - task.top;
                        dragging = false;
                        wm.post(() -> wm.front(task.displayId, task.taskId));
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!dragging
                                && Math.abs(rawX - (task.left + grabDx)) < touchSlop
                                && Math.abs(rawY - (task.top + grabDy)) < touchSlop) {
                            return true;   // still a tap; let the buttons win
                        }
                        dragging = true;
                        // Dragging a maximised window ungrips it, so the maximise button goes
                        // back to "maximise" and does not try to restore to a stale rect.
                        maximized.remove(task.taskId);
                        // No surface move here. The bar lives inside the task, so moving
                        // the task moves it — the compositor keeps them together with no
                        // lag and nothing for us to synchronise.
                        send(rawX - grabDx, rawY - grabDy);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            send(rawX - grabDx, rawY - grabDy);   // replay the last point
                            dragging = false;
                            lastTap = 0;   // a drag is not half of a double-click
                            return true;
                        }
                        // Remember it as the first half of a possible double-click. Doing
                        // this on UP rather than DOWN is what makes a drag-then-tap not
                        // count as one.
                        lastTap = SystemClock.uptimeMillis();
                        lastTapX = rawX;
                        lastTapY = rawY;
                        return false;      // a tap — let onClick run
                }
                return false;
            }

            private void send(int x, int y) {
                wantX = x;
                wantY = y;
                if (pending) return;       // a pump is already in flight; it will see the
                pending = true;            // newest position when it drains
                wm.post(this::pump);
            }

            private void pump() {
                int x, y;
                synchronized (this) {
                    x = wantX;
                    y = wantY;
                    pending = false;
                }
                wm.move(task.displayId, task.taskId, x, y);
                task.left = x;
                task.top = y;
            }
        };
    }

    private int touchSlop = 6;

    /** Two taps this close together, in time and in space, are a double-click. */
    private static final long DOUBLE_TAP_MS =
            android.view.ViewConfiguration.getDoubleTapTimeout();
    /**
     * Absolute px on the desktop display, which runs at density 1.0 — the scaled value
     * from ViewConfiguration describes the phone panel (~450dpi) and would be ~3x too
     * large here. Deliberately looser than touchSlop: a double-click is aimed once and
     * clicked twice, and a few px of drift between the two must not lose the gesture.
     */
    private static final int DOUBLE_TAP_SLOP = 24;

    /**
     * Window title: the top activity's label, falling back to the application
     * label and finally the package name.
     *
     * Activity first because that is what a window is — most apps leave the
     * activity label unset and {@code ActivityInfo#loadLabel} then returns the
     * application label anyway, so this only differs where the app meant it to
     * (our own Settings window is titled "Settings", not "Open Android DeX").
     */
    private String labelFor(WmClient.Task task) {
        PackageManager pm = getPackageManager();
        if (task.activity != null && !task.activity.isEmpty() && !"?".equals(task.activity)) {
            try {
                return pm.getActivityInfo(
                        new ComponentName(task.pkg, task.activity), 0).loadLabel(pm).toString();
            } catch (Exception ignored) {
                // aliases and non-exported entry points can fail to resolve
            }
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(task.pkg, 0)).toString();
        } catch (Exception e) {
            return task.pkg;
        }
    }

    // ── caption actions ────────────────────────────────────────────────────

    /**
     * ✕ — a real close (wmd removeTask), not One UI's hide.
     *
     * The Linux window is the exception: closing it ends a container the user
     * may have unsaved work inside, and removing the task gives the activity no
     * opportunity to ask first. So that one is ASKED rather than closed, and it
     * closes itself once the user confirms. Everything else, including our own
     * Settings window, closes here as before.
     */
    private void onClose(WmClient.Task task) {
        final int display = task.displayId;
        final int id = task.taskId;
        // The Docker window is asked for the same reason as the Linux one: its
        // ✕ stops a machine with the user's running containers in it, and a
        // task removal gives the activity no chance to say so.
        if (getPackageName().equals(task.pkg)
                && (LinuxActivity.class.getName().equals(task.activity)
                || DockerActivity.class.getName().equals(task.activity))) {
            sendBroadcast(new android.content.Intent(LauncherActivity.ACTION_CLOSE_WINDOW)
                    .setPackage(getPackageName())
                    .putExtra("activity", task.activity));
            return;
        }
        wm.post(() -> {
            wm.unstrip(display, id);
            wm.close(id);
        });
    }

    /**
     * Maximise, or restore if already maximised.
     *
     * The size to restore to is whatever the window had the moment it was first maximised or
     * snapped, so "maximise then bring it back" lands on exactly the old geometry. Both legs
     * go through RESIZE, which sets bounds and the caption strip in one animated transition —
     * the old path did a bare BOUNDS and let the reconcile re-strip a frame later, which is
     * the maximise/restore flicker.
     *
     * WHICH leg to take is decided from the window's LIVE rect, not from the {@link
     * #maximized} set. That set is a belief nothing ever reconciles against the screen, and
     * it goes out of phase every time something other than this button resizes the window:
     * the taskbar's app menu, the PC enforcer's own toggle, One UI, or simply this service
     * being restarted on a reconnect while everybody else's state survives. Out of phase, a
     * press meant to MAXIMISE ran the restore leg and dropped the window onto a stale
     * remembered rect — after a snap that rect is the left half of the display, which is
     * exactly the "maximise does not go full width" report. The rect on screen cannot be out
     * of phase with itself, so it is the only safe thing to branch on.
     */
    private void onMaximise(WmClient.Task task) {
        final int display = task.displayId;
        final int id = task.taskId;
        final int px = captionHeight(id);
        // The poll-refreshed record, never the snapshot this listener closed over when the
        // bar was built: pump() only ever writes left/top back, so a captured right/bottom
        // can be several resizes old.
        final WmClient.Task live = tasks.containsKey(id) ? tasks.get(id) : task;
        final int[] max = maxRect(display);
        if (near(live, max)) {
            maximized.remove(id);
            int[] r = restoreBounds.remove(id);
            // Never "restore" to the window's current rect: at this point that IS the
            // maximise pin, so the press would do nothing and the button reads as dead.
            final int[] rr = r != null ? r : defaultRestore(display);
            trace("restore task " + id + " -> " + rr[0] + "," + rr[1] + "," + rr[2] + ","
                    + rr[3]);
            wm.post(() -> wm.resize(display, id, rr[0], rr[1], rr[2], rr[3], px));
            return;
        }
        rememberRestore(live);
        maximized.add(id);
        trace("maximise task " + id + " -> " + max[0] + "," + max[1] + "," + max[2] + ","
                + max[3]);
        wm.post(() -> wm.resize(display, id, max[0], max[1], max[2], max[3], px));
    }

    private void onSnapLeft(WmClient.Task task) {
        snap(task, true);
    }

    private void onSnapRight(WmClient.Task task) {
        snap(task, false);
    }

    /**
     * Fill the left or right half of the display; a snapped window is not "maximised".
     *
     * Height stops at the taskbar for the same reason maximise does — the taskbar is an
     * overlay drawn above app windows, so the rows underneath it are pixels the window can
     * never show. It matters twice over here: the PC enforcer adopts whatever rect a
     * non-maximised window is observed at as that task's restore target, so a snap that
     * runs under the taskbar teaches the PC to restore under the taskbar too.
     */
    private void snap(WmClient.Task task, boolean left) {
        final int display = task.displayId;
        final int id = task.taskId;
        final int px = captionHeight(id);
        rememberRestore(tasks.containsKey(id) ? tasks.get(id) : task);
        maximized.remove(id);
        final android.graphics.Point size = displaySize(display);
        final int half = size.x / 2;
        final int bottom = size.y - taskbarPx(display);
        if (left) {
            wm.post(() -> wm.resize(display, id, 0, 0, half, bottom, px));
        } else {
            wm.post(() -> wm.resize(display, id, half, 0, size.x, bottom, px));
        }
    }

    /** Capture the window's current rect as its restore target, once, until it is restored. */
    private void rememberRestore(WmClient.Task task) {
        if (!restoreBounds.containsKey(task.taskId)) {
            restoreBounds.put(task.taskId,
                    new int[]{task.left, task.top, task.right, task.bottom});
        }
    }

    /**
     * The desktop display's size, in ITS pixels. Feeds maximise and both snaps, so every
     * rect those three produce is only as right as this is.
     *
     * Asked through a WINDOW context, and the "window" there is load-bearing.
     *
     * A display context alone is NOT enough, and this is measured rather than reasoned:
     * {@code createDisplayContext(d).getSystemService(WindowManager.class)} is a non-UI
     * context, so the WindowManager it hands back is not bound to that display — it answers
     * for the process's own, which for this service is the phone panel. On SM-S938B, with
     * the desktop at 1920x1080 (display 82), it returned 1080x2340 and maximise traced
     * {@code 0,0,1076,2288}: a window 56% of the width, left-flush, running off the bottom.
     * That is the "maximise does not go full width" report, exactly.
     *
     * {@link android.content.Context#createWindowContext} is what actually associates the
     * WindowManager with the display — the same call the caption anchor and the curtain
     * already make a few hundred lines up, which is why THEIR geometry was always right and
     * only this was wrong.
     *
     * The fallback is the display context's Resources, which are display-adjusted even
     * though its WindowManager is not (that asymmetry is the whole bug): it is what
     * {@link #taskbarPx} reads, and it demonstrably reported the desktop's density of 1.0
     * while the metrics above were reporting the phone.
     */
    private android.graphics.Point displaySize(int display) {
        final android.graphics.Point size = new android.graphics.Point();
        Display d = null;
        try {
            d = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(display);
            android.graphics.Rect b = createDisplayContext(d)
                    .createWindowContext(
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .getSystemService(WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            size.set(b.width(), b.height());
            return size;
        } catch (Throwable t) {
            trace("window metrics unavailable for " + display + " (" + t + ")");
        }
        try {
            android.util.DisplayMetrics dm =
                    createDisplayContext(d).getResources().getDisplayMetrics();
            size.set(dm.widthPixels, dm.heightPixels);
            return size;
        } catch (Throwable t) {
            // Deliberately not the service's own metrics: a phone-shaped answer looks
            // plausible and silently mis-sizes every window, which is worse than a default
            // that is at least obviously a default.
            trace("display size unavailable for " + display + " (" + t + ")");
            size.set(1920, 1080);
        }
        return size;
    }

    /**
     * Where a maximised window sits: the whole display, bar the taskbar and four pixels of
     * width. Both insets are load-bearing and neither is cosmetic.
     *
     * The taskbar is a 52dp overlay pinned to the bottom of the desktop and always composited
     * ABOVE app windows, so a window owning those rows owns pixels it can never show.
     *
     * The 4px is the handshake with the PC. The enforcer treats a freeform task spanning the
     * FULL display width as "something other than us just made this full-width" (scrcpy.rs,
     * Enforcer::fills) and reacts to it, and its own pin is 4px narrow precisely so its own
     * work is not mistaken for that event. Maximising to exactly (0,0,W,H) — what this used
     * to send — made every press of our ▢ indistinguishable from it, so one press was counted
     * twice: once here, once by the enforcer's toggle. Whenever the two toggles were out of
     * phase (a taskbar-menu maximise, a parked retry, a caption-service restart on reconnect)
     * the enforcer's half ran as a RESTORE onto the last rect it had observed the window
     * windowed at — the left half of the display, after a snap. Hence "maximise goes half
     * width". Same arithmetic as maxed_rect on the PC side, so both agree on which rect means
     * maximised.
     */
    private int[] maxRect(int display) {
        final android.graphics.Point size = displaySize(display);
        return new int[]{0, 0, size.x - 4, size.y - taskbarPx(display)};
    }

    /**
     * The taskbar's height in the DESKTOP display's pixels.
     *
     * Read from a display context rather than through px(int): that helper assumes the
     * desktop runs at density 1.0, which is only true at the 1080p preset — the others pick
     * a dpi (SettingsActivity#defaultDpi, pushed by LauncherActivity#reapplyDensity), and the
     * launcher lays its taskbar out in dp against that same density. This is also why it
     * cannot come from the service's own Resources, which describe the phone panel.
     */
    private int taskbarPx(int display) {
        try {
            Display d = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(display);
            float density = createDisplayContext(d).getResources()
                    .getDisplayMetrics().density;
            return Math.round(LauncherActivity.TASKBAR_DP * density);
        } catch (Throwable t) {
            trace("taskbar height unavailable for " + display + " (" + t + ")");
            return px(LauncherActivity.TASKBAR_DP);
        }
    }

    /**
     * Is this task sitting on that rect? The ±8px slack is the same the PC enforcer allows
     * (scrcpy.rs, near), so "maximised" means the same thing on both sides of the wire even
     * when the two computed the pin from densities that round differently.
     */
    private static boolean near(WmClient.Task t, int[] r) {
        return Math.abs(t.left - r[0]) <= 8
                && Math.abs(t.top - r[1]) <= 8
                && Math.abs(t.right - r[2]) <= 8
                && Math.abs(t.bottom - r[3]) <= 8;
    }

    /**
     * Restore target of last resort: a centred window at three fifths of the display, the
     * same shape the enforcer hands a launch (scrcpy.rs, default_windowed). Only reached when
     * the remembered rect was lost — the caption is torn down and rebuilt whenever a task
     * stops being freeform/visible for a pass, and that sweep drops restoreBounds with it.
     */
    private int[] defaultRestore(int display) {
        final android.graphics.Point size = displaySize(display);
        return new int[]{size.x / 5, size.y / 8, size.x * 4 / 5, size.y * 8 / 9};
    }

    /**
     * Minimise, i.e. hide the window but keep the app open.
     *
     * This used to send the task to the back, which reads as a CLOSE: the launcher is a
     * fullscreen task, so anything at the bottom is fully occluded, the window manager
     * stops it, and it drops out of the taskbar's open-apps list along with everything else
     * that is not visible. setHidden says what we actually mean, and the package is pinned
     * below so the taskbar keeps offering it.
     */
    private void onMinimise(WmClient.Task task) {
        final int display = task.displayId;
        final int id = task.taskId;
        minimised.put(id, new Min(task.pkg, task.activity, SystemClock.uptimeMillis()));
        publishMinimised();
        wm.post(() -> wm.hide(display, id));
    }

    /** One window we minimised: what to pin in the taskbar, and how far the hide has got. */
    private static final class Min {
        final String pkg;
        /**
         * The task's top activity. Package alone cannot address a restore:
         * our own package owns TWO windows (Settings and Linux), and a
         * pkg-only match would un-hide whichever came first.
         */
        final String activity;
        /** uptime the minimise was issued at — the grace window starts here. */
        final long since;
        /** Set once the task has actually been observed hidden. */
        boolean hidden;
        /**
         * Where in the stack this window was when it went away, 0 = topmost.
         *
         * Only "show desktop" fills it in, because only "show desktop" puts
         * several windows away at once and has to give them back in the order
         * they were in. A window minimised on its own comes back on top, which
         * is what the user just asked for, and carries {@link #LOOSE}.
         */
        int depth = LOOSE;

        /** Not part of a batch — restore before anything that is. */
        static final int LOOSE = -1;

        Min(String pkg, String activity, long since) {
            this.pkg = pkg;
            this.activity = activity;
            this.since = since;
        }
    }

    /**
     * How long a hide gets to land before we stop pinning its package.
     *
     * Only reached when the hide genuinely failed (daemon gone, task already dying); a
     * transition that runs normally is observed committed well inside it.
     */
    private static final long HIDE_GRACE_MS = 2_000L;

    /**
     * taskId -> the window we minimised.
     *
     * Needed because a minimised task and a closed one are indistinguishable downstream:
     * One UI's caption ✕ also merely hides the task, so "not visible" cannot mean "gone".
     * Only the side that performed the minimise knows the difference, and that is here.
     */
    private final Map<Integer, Min> minimised = new HashMap<>();

    /** Taskbar click on a minimised app — un-hide it rather than launching it again. */
    /**
     * "Show desktop", from a touchpad gesture — one action, both directions.
     *
     * A toggle rather than two commands because the user performs one gesture
     * and expects it to undo itself, and because only this service can answer
     * which way round it currently is.
     */
    private final android.content.BroadcastReceiver showDesktopReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent i) {
                    onShowDesktop();
                }
            };

    /**
     * Hide every window, or bring back the ones we hid.
     *
     * Goes through the same {@code minimised} map the caption's — button does,
     * and for the same reason: the PC builds the taskbar's open-apps list from
     * visibility, so a window hidden without being recorded here simply
     * disappears from the taskbar with nothing able to bring it back. "Show
     * desktop" would then mean "close everything".
     */
    private void onShowDesktop() {
        if (wm == null) return;
        final int display = desktopDisplayId;
        List<WmClient.Task> open = new ArrayList<>(tasks.values());
        // Which way the toggle goes is decided by what is ON SCREEN, not by
        // whether anything happens to be minimised. A user who minimised one
        // window by hand and then asks for the desktop means "clear the other
        // two", not "put that one back".
        if (open.isEmpty()) {
            // DEEPEST FIRST. wm.show un-hides AND raises, so replaying the
            // stack from the bottom puts every window back where it was; an
            // unordered walk — and `minimised` is a HashMap — would hand back
            // a desktop shuffled into an arbitrary order with the wrong window
            // focused.
            List<Map.Entry<Integer, Min>> back = new ArrayList<>(minimised.entrySet());
            java.util.Collections.sort(back, (a, b) -> b.getValue().depth - a.getValue().depth);
            for (Map.Entry<Integer, Min> e : back) {
                final int id = e.getKey();
                wm.post(() -> wm.show(display, id));
            }
            // Left in `minimised` on purpose: the reconcile drops each entry
            // once the un-hide has actually landed, which is also what keeps
            // the taskbar from un-pinning a window mid-transition.
            return;
        }
        // `stack` order, not the map's: `tasks` is a HashMap and `lastStack`
        // is the daemon's topmost-first list, which is the only record of how
        // the desktop looked before this gesture.
        List<WmClient.Task> ordered = lastStack.isEmpty() ? open : lastStack;
        for (int i = 0; i < ordered.size(); i++) {
            WmClient.Task t = ordered.get(i);
            if (!tasks.containsKey(t.taskId)) continue;
            Min m = new Min(t.pkg, t.activity, SystemClock.uptimeMillis());
            m.depth = i;
            minimised.put(t.taskId, m);
            final int id = t.taskId;
            wm.post(() -> wm.hide(display, id));
        }
        // Once, after the whole batch: the launcher rebuilds its taskbar on
        // every one of these, and N windows should not cost N rebuilds.
        publishMinimised();
    }

    private final android.content.BroadcastReceiver restoreReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent i) {
                    String pkg = i.getStringExtra("pkg");
                    if (pkg == null) return;
                    // Optional narrowing for the one package with several
                    // windows (ours: Settings and Linux). Absent = any window
                    // of the package, the taskbar's meaning.
                    String activity = i.getStringExtra("activity");
                    for (Map.Entry<Integer, Min> e : minimised.entrySet()) {
                        if (!pkg.equals(e.getValue().pkg)) continue;
                        if (activity != null && !activity.equals(e.getValue().activity)) continue;
                        final int taskId = e.getKey();
                        final int display = desktopDisplayId;
                        wm.post(() -> wm.show(display, taskId));
                        break;
                    }
                    // The reconcile drops it from `minimised` once it is visible again.
                }
            };

    private static WmClient.Task byId(List<WmClient.Task> tasks, int taskId) {
        for (WmClient.Task t : tasks) {
            if (t.taskId == taskId) return t;
        }
        return null;
    }

    /** Tell the launcher which packages to keep in the taskbar despite being invisible. */
    private void publishMinimised() {
        StringBuilder pkgs = new StringBuilder();
        StringBuilder activities = new StringBuilder();
        for (Min m : minimised.values()) {
            if (pkgs.length() > 0) {
                pkgs.append(',');
                activities.append(',');
            }
            pkgs.append(m.pkg);
            activities.append(m.activity);
        }
        sendBroadcast(new android.content.Intent(LauncherActivity.ACTION_MINIMISED)
                .setPackage(getPackageName())
                .putExtra("pkgs", pkgs.toString())
                // aligned with pkgs — the launcher needs the per-window view
                // to route its own two windows' restore correctly
                .putExtra("activities", activities.toString()));
    }

    private void release(Caption c) {
        if (c == null) return;
        try {
            if (c.host != null) c.host.release();
        } catch (Throwable ignored) {
        }
    }

    // ── launch curtain ─────────────────────────────────────────────────────
    //
    // A display-level cover for One UI's own caption during the window between a task
    // appearing and our real caption attaching to it.

    /**
     * Put an opaque strip over the task's caption area, or move an existing one to match.
     *
     * Drawn as a display-level {@code TYPE_ACCESSIBILITY_OVERLAY} — the same window type the
     * anchor uses, and the only kind available here: the task's caption host is not yet in
     * the accessibility list, so there is no window to parent a window-level overlay to. The
     * colour matches the real bar so the handoff in {@link #ensureCaption} is invisible.
     */
    private void showCurtain(WmClient.Task task) {
        int w = task.right - task.left;
        int h = captionHeight(task.taskId);
        if (w <= 0 || h <= 0) return;
        try {
            View existing = curtains.get(task.taskId);
            if (existing != null) {
                existing.getContext().getSystemService(WindowManager.class)
                        .updateViewLayout(existing, curtainParams(task, w, h));
                return;
            }
            Display display = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(task.displayId);
            if (display == null) return;
            android.content.Context ctx = createDisplayContext(display)
                    .createWindowContext(
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            View v = new View(ctx);
            v.setBackgroundColor(DexTheme.of(this).caption);   // == the real bar's background
            ctx.getSystemService(WindowManager.class).addView(v, curtainParams(task, w, h));
            curtains.put(task.taskId, v);
            curtainSince.put(task.taskId, SystemClock.uptimeMillis());
            trace("curtain up task=" + task.taskId + " w=" + w + " h=" + h);
        } catch (Throwable t) {
            trace("curtain failed task=" + task.taskId + ": " + t);
        }
    }

    /**
     * Absolute placement on the desktop display: gravity TOP|LEFT with x/y at the task
     * origin, sized to the task width and the caption height. NOT_TOUCHABLE so a stray tap
     * during the (brief) cover still reaches the app; NOT_FOCUSABLE so it never takes focus.
     */
    private static WindowManager.LayoutParams curtainParams(WmClient.Task task, int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = task.left;
        lp.y = task.top;
        return lp;
    }

    private void hideCurtain(int taskId) {
        View v = curtains.get(taskId);
        if (v == null) return;
        curtains.remove(taskId);
        curtainSince.remove(taskId);
        try {
            v.getContext().getSystemService(WindowManager.class).removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }
}
