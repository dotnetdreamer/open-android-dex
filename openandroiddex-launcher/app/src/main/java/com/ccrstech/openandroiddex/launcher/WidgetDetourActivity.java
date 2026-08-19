package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.SparseIntArray;

import java.lang.ref.WeakReference;

/**
 * A task to put the widget flow's system detours in — and nothing else.
 *
 * Adding a widget goes through up to two activities we do not own (the
 * platform's bind confirmation, the provider's configure screen) and both have
 * to be started FOR A RESULT. That combination has no valid caller on the
 * desktop:
 *
 *   - With FLAG_ACTIVITY_NEW_TASK, ActivityStarter cancels the result on the
 *     spot and drops resultTo. The bind dialog reads its caller off exactly
 *     that link (getCallingPackage()) and finishes when it is null, so it
 *     never even draws.
 *   - Without it, a standard-launchMode activity lands in the CALLER's task —
 *     and ActivityOptions launch bounds are applied to the resolved TASK, not
 *     to the activity. Called from the desktop they resized the desktop: the
 *     whole shell was drawn shrunk inside the dialog's 520dp window, with the
 *     rest of the display black and the small bounds remembered afterwards.
 *
 * So the detour is called from here instead. This activity never sets a
 * content view; it exists to own a task that can be given the dialog's bounds
 * and thrown away. Both stages run in THIS instance — handing bind→configure
 * back to the desktop would cross a task boundary the result cannot cross.
 */
public final class WidgetDetourActivity extends Activity {

    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_DISPLAY = "display";
    static final String EXTRA_BOUNDS_BIND = "bounds_bind";
    static final String EXTRA_BOUNDS_CONFIGURE = "bounds_configure";
    static final int STAGE_BIND = 1;
    static final int STAGE_CONFIGURE = 2;

    private static final int REQ_BIND = 61;
    private static final int REQ_CONFIGURE = 62;
    /**
     * A detour that never opens delivers no result — a background-activity-start
     * abort, a Knox/DeX policy, a configure activity that declares
     * singleInstance. Without this the task would sit on the desktop as an
     * empty transparent window forever, holding the widget id with it.
     * Disarmed by onPause, which is the signal that something did come up.
     */
    private static final long NO_SHOW_MS = 8_000;

    /** For the desktop: close a detour a newer add has superseded. */
    private static WeakReference<WidgetDetourActivity> live;
    /**
     * Detour tasks, counted. CaptionService asks by task id which windows are
     * detours, because their PACKAGE is no help — while a dialog is up the task
     * reports com.android.settings, or the widget provider.
     *
     * Counted, and not one live id: dismiss() only marks the old task's root
     * finishing, and the framework refuses to re-enter a finishing task, so a
     * superseding add always runs two detour tasks side by side for as long as
     * the old one takes to go. A single slot would drop the closing one back
     * into ordinary-window treatment for those few frames.
     */
    private static final SparseIntArray detourTasks = new SparseIntArray();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable noShow = new Runnable() {
        @Override
        public void run() {
            DexLog.warn("widgets", "detour never opened — dropping widget " + widgetId);
            report(false);
            finishAndRemoveTask();
        }
    };

    private int widgetId = -1;
    /**
     * Read once, in onCreate: by onDestroy the activity is off its task and
     * getTaskId() can answer -1, which would leave the entry behind forever.
     */
    private int myTask = -1;
    private Rect bindBounds, configureBounds;
    private boolean reported;

    /** Close a live detour. Safe to call when there is none. */
    static void dismiss() {
        WidgetDetourActivity a = live == null ? null : live.get();
        if (a != null && !a.isFinishing()) a.finishAndRemoveTask();
    }

    /** Is this task one of ours, standing in for a system dialog? */
    static boolean isDetourTask(int taskId) {
        synchronized (detourTasks) {
            return taskId >= 0 && detourTasks.get(taskId, 0) > 0;
        }
    }

    /**
     * Forget every detour task. Called by a desktop that has just started:
     * none of them can predate it, so any entry left here is from a life that
     * ended without an onDestroy.
     */
    static void forgetTasks() {
        synchronized (detourTasks) {
            detourTasks.clear();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        live = new WeakReference<>(this);
        // before the early returns below, so onDestroy's decrement is balanced
        myTask = getTaskId();
        synchronized (detourTasks) {
            detourTasks.put(myTask, detourTasks.get(myTask, 0) + 1);
        }
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        bindBounds = getIntent().getParcelableExtra(EXTRA_BOUNDS_BIND);
        configureBounds = getIntent().getParcelableExtra(EXTRA_BOUNDS_CONFIGURE);
        // Restored with no desktop behind us: the process died mid-detour
        // (the session-end force-stop, LMK) and took the flow's state with it.
        // Take the whole task down — the orphaned dialog standing on us
        // included — rather than leave it wired to an id nobody remembers;
        // the desktop's releaseOrphanedAdd frees that id from its marker.
        if (savedInstanceState != null || !LauncherActivity.hasLiveDesktop()) {
            DexLog.step("widgets", "detour task outlived its desktop — dropping it");
            finishAndRemoveTask();
            return;
        }
        // singleTask + NEW_TASK makes findTask search EVERY display for our
        // affinity, so a task that outlived a session (display removal
        // reparents it to display 0) could swallow this start and open the
        // dialog on the phone. Same hazard launch() documents.
        if (getDisplay().getDisplayId() != getIntent().getIntExtra(EXTRA_DISPLAY, -1)) {
            DexLog.warn("widgets", "detour landed on display " + getDisplay().getDisplayId()
                    + ", not the desktop — dropping it");
            report(false);
            finishAndRemoveTask();
            return;
        }
        startStage(getIntent().getIntExtra(EXTRA_STAGE, 0));
    }

    /** singleTask: a superseding add re-delivers here. Treat it as a fresh detour. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        report(false);                  // the add this task was opened for is over
        reported = false;
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        bindBounds = intent.getParcelableExtra(EXTRA_BOUNDS_BIND);
        configureBounds = intent.getParcelableExtra(EXTRA_BOUNDS_CONFIGURE);
        startStage(intent.getIntExtra(EXTRA_STAGE, 0));
    }

    private void startStage(int stage) {
        if (widgetId < 0 || (stage != STAGE_BIND && stage != STAGE_CONFIGURE)) {
            report(false);
            finishAndRemoveTask();
            return;
        }
        try {
            if (stage == STAGE_BIND) {
                ComponentName provider = getIntent()
                        .getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER);
                UserHandle profile = getIntent()
                        .getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE);
                Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile);
                // No NEW_TASK, deliberately: the dialog must land in THIS
                // task, which is the entire point of this class.
                startActivityForResult(bind, REQ_BIND, options(bindBounds));
            } else {
                // A host object is not a handle the service checks — the
                // configure sender is authorised on (uid, package) alone, the
                // same property DexWidgetHost.wipe already relies on. Never
                // started: a second listening host for the same host id would
                // compete with the desktop's for updates. intentFlags stays 0
                // because the system mints the PendingIntent with exactly
                // those flags, and a NEW_TASK there cancels the result too.
                new DexWidgetHost(this).startAppWidgetConfigureActivityForResult(
                        this, widgetId, 0, REQ_CONFIGURE, options(configureBounds));
            }
            handler.postDelayed(noShow, NO_SHOW_MS);
        } catch (Exception e) {
            DexLog.warn("widgets", "detour stage " + stage + " could not open", e);
            report(false);
            finishAndRemoveTask();
        }
    }

    /** Something came up on top of us — the detour exists, so stop watching for it. */
    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(noShow);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        handler.removeCallbacks(noShow);
        boolean ok = resultCode == RESULT_OK;
        if (requestCode == REQ_BIND && ok && needsConfigure()) {
            startStage(STAGE_CONFIGURE);
            return;
        }
        report(ok);
        finishAndRemoveTask();
    }

    /** Post-bind, the service is the authority on whether a setup screen is owed. */
    private boolean needsConfigure() {
        AppWidgetProviderInfo info = null;
        try {
            info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
        } catch (Exception ignored) {
        }
        return DexWidgetHost.needsConfigure(info);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(noShow);
        synchronized (detourTasks) {
            int left = detourTasks.get(myTask, 0) - 1;
            if (left > 0) detourTasks.put(myTask, left);
            else detourTasks.delete(myTask);
        }
        if (live != null && live.get() == this) live = null;
        // The caption ✕ (CaptionService → wmd close → removeTask) destroys the
        // task with no activity result at all, and so does dismiss(). Only a
        // real destroy is a cancel — a config relaunch is not, or a density
        // change would release an id whose dialog is still on screen.
        if (!isChangingConfigurations()) report(false);
    }

    /**
     * Exactly once per detour — the desktop treats a report as final.
     *
     * A direct call and not a broadcast: the desktop is in this process, on
     * this thread, and a broadcast would come back through system_server some
     * milliseconds later, by which time a second add could already have taken
     * the pending id over and turned this verdict into a superseded one.
     */
    private void report(boolean ok) {
        if (reported || widgetId < 0) return;
        reported = true;
        LauncherActivity.onDetourResult(widgetId, ok);
    }

    /**
     * The window the desktop asked for. Applied to THIS task — which is why
     * this class exists — and applied again for stage 2, so the configure
     * screen gets its own, larger rect.
     */
    private Bundle options(Rect bounds) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(getDisplay().getDisplayId());
        if (bounds != null) opts.setLaunchBounds(bounds);
        // Hidden API, same reflective call the desktop's launches use: bounds
        // alone are not honored on a decoration-free display.
        try {
            ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                    .invoke(opts, 5 /* WINDOWING_MODE_FREEFORM */);
        } catch (Exception ignored) {
        }
        return opts.toBundle();
    }
}
