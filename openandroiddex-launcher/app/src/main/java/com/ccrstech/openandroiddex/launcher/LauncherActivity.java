package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Desktop shell for the Open Android DeX virtual display: an icon grid
 * ({@link DesktopGrid}), an app drawer and a DeX-style taskbar. Taskbar
 * layout: nav cluster (back · home · open apps) on the left, apps toggle +
 * OPEN apps in the center, clock/date with a calendar popup on the right. The
 * open-apps row mirrors what is actually running on this display — the PC side
 * broadcasts the task list (ACTION_RUNNING) on every poll, and launches/closes
 * done from here update the row optimistically so there is no visible lag.
 *
 * Holding an icon in the drawer closes the drawer and hands the gesture to the
 * drag layer, which drops the app onto the desktop grid (see startDesktopDrag).
 */
public class LauncherActivity extends Activity implements WidgetLaunch.Desktop {

    static final String PREFS = "openandroiddex";
    /** Package-visible: the factory-reset flow clears it by name. */
    static final String KEY_RECENTS = "recents";
    private static final int MAX_RECENTS = 10;
    /**
     * Taskbar height. NOT a preference: the PC side computes the bounds a
     * maximized window gets as "the display minus 52dp" (scrcpy.rs,
     * taskbar_px), so a taskbar of any other height would leave maximized
     * windows either overlapping the bar or short of it.
     */
    static final int TASKBAR_DP = 52;

    /**
     * Below this display width the shell lays its chrome out compactly — see
     * {@link #compact()}. 600dp is the platform's own handset/tablet line, and
     * it is also where the taskbar's three clusters stop fitting side by side.
     */
    static final int COMPACT_WIDTH_DP = 600;

    static final class AppEntry {
        final CharSequence label;
        final Drawable icon;
        final ComponentName component;

        AppEntry(CharSequence label, Drawable icon, ComponentName component) {
            this.label = label;
            this.icon = icon;
            this.component = component;
        }
    }

    /** PC-side enforcer pushes the display's open apps via this broadcast. */
    public static final String ACTION_RUNNING = "com.ccrstech.openandroiddex.launcher.RUNNING";

    /** CaptionService pushes the set of minimised (open but hidden) packages. */
    public static final String ACTION_MINIMISED =
            "com.ccrstech.openandroiddex.launcher.MINIMISED";

    /** Ask CaptionService to un-minimise a package. */
    public static final String ACTION_RESTORE =
            "com.ccrstech.openandroiddex.launcher.RESTORE";

    /** Ask CaptionService to hide every window, or bring them all back. */
    public static final String ACTION_SHOW_DESKTOP =
            "com.ccrstech.openandroiddex.launcher.SHOW_DESKTOP";

    /**
     * A touchpad gesture the PC recognised, as {@code --es action <what>}.
     *
     * Only the actions that need shell UI arrive this way. Window moves and
     * raises go straight from the PC to the window daemon, which is an order
     * of magnitude quicker and needs nothing from this process.
     */
    public static final String ACTION_GESTURE =
            "com.ccrstech.openandroiddex.launcher.GESTURE";

    /**
     * "Your caption's ✕ was pressed" — sent to one of OUR OWN windows instead
     * of closing its task, so the window can put a question in front of the
     * close. Only the Linux window wants one: closing it ends a container the
     * user may have work inside, and a task removal gives the activity no
     * chance to ask. Extra: "activity".
     */
    public static final String ACTION_CLOSE_WINDOW =
            "com.ccrstech.openandroiddex.launcher.CLOSE_WINDOW";

    /**
     * A file dragged from the PC onto the desktop window. The drop is taken by
     * scrcpy over there — its window is this desktop — so the PC is the only
     * side that knows a transfer is happening, and it narrates it here. See
     * {@link TransferHud} and transfer.rs.
     */
    public static final String ACTION_TRANSFER =
            "com.ccrstech.openandroiddex.launcher.TRANSFER";

    /**
     * The live desktop, so {@link WidgetDetourActivity} can tell whether there
     * is still a flow to report to — a detour restored after the process died
     * has none, and takes its whole task down instead. Weak: recreate() leaves
     * the old instance behind for a moment, and a static strong reference to
     * an Activity holds its entire view tree.
     */
    private static WeakReference<LauncherActivity> live;

    static boolean hasLiveDesktop() {
        return liveDesktop() != null;
    }

    private static LauncherActivity liveDesktop() {
        LauncherActivity a = live == null ? null : live.get();
        return a != null && !a.isDestroyed() && !a.isFinishing() ? a : null;
    }

    /**
     * A bind/configure detour finished. The widget id is the whole staleness
     * test: the detour names the widget it was opened for even when the system
     * hands back a data-less RESULT_CANCELED, which is the one case the old
     * in-task onActivityResult could not attribute and needed a counter for.
     */
    static void onDetourResult(int widgetId, boolean ok) {
        LauncherActivity desktop = liveDesktop();
        if (desktop == null) return;    // releaseOrphanedAdd frees the id instead
        if (widgetId != desktop.pendingWidgetId) {
            // a superseded flow reporting in: abandonPendingWidget has already
            // released its id, but never delete one that made it onto the
            // desktop — the same forgiveness releaseOrphanedAdd applies to an
            // id that outlived its marker
            if (widgetId >= 0 && !desktop.isRecordedWidget(widgetId)) {
                try {
                    desktop.widgetHost.deleteAppWidgetId(widgetId);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (ok) desktop.placePendingWidget();
        else desktop.abandonPendingWidget();
    }

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();
    /** Launch history (persisted) — feeds the Recents popup. */
    private final LinkedHashSet<String> recents = new LinkedHashSet<>();
    /** Packages currently open on this display — feeds the taskbar row. */
    private final LinkedHashSet<String> runningPkgs = new LinkedHashSet<>();
    private BaseAdapter adapter;
    private TextView clockView;
    private TextView dateView;
    private LinearLayout openAppsRow;
    private EditText searchField;
    /** Drawer window root: the panel plus the drag layer painted over it. */
    private DrawerRoot drawer;
    /** The drawer's visible chrome — hidden for the duration of a drag. */
    private LinearLayout drawerPanel;
    private DragLayer dragLayer;
    private boolean drawerShown = false;
    /** true while the drawer is attached as its own always-on-top window. */
    private boolean drawerOverlay = false;
    private FrameLayout rootFrame;
    /** Desktop icon grid — the drop target for drags out of the drawer. */
    private DesktopGrid desktopGrid;
    /** App being dragged out of the drawer; non-null only during that drag. */
    private AppEntry dragApp;
    /** Cell the drag is currently over, or null when it is off the grid. */
    private int[] dragCell;
    private final Rect dragCellRect = new Rect();
    /** Latest pointer position seen by the drawer window, in screen pixels. */
    private float drawerRawX, drawerRawY;
    private View taskbarView;
    private boolean taskbarOverlay = false;
    private PopupWindow recentsPopup;
    private PopupWindow calendarPopup;
    private PopupWindow batteryPopup;
    private PopupWindow qsPopup;
    private PopupWindow exitPopup;
    private PopupWindow widgetPicker;

    // ── desktop widgets (AppWidgetHost) ──
    private DexWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    /**
     * The widget mid-add. Adding detours through up to two system activities
     * (bind confirmation, the provider's configure screen), both of them run
     * by {@link WidgetDetourActivity} in a task of its own; this is what
     * detourReceiver picks the flow back up with — or releases, so a cancelled
     * add never leaks an allocated id.
     */
    private int pendingWidgetId = -1;
    private AppWidgetProviderInfo pendingWidgetInfo;
    private int pendingWidgetCol, pendingWidgetRow;
    /**
     * The pending id, mirrored to prefs for the whole detour. The in-memory
     * fields die with the process — and the process DOES die mid-detour: the
     * session-end force-stop, LMK (stateNotNeeded, nothing is saved), or
     * recreate() on a language change. An id that was already allocated (and
     * possibly bound) server-side would then have no record anywhere and leak
     * forever; onCreate reconciles this key instead (see releaseOrphanedAdd).
     */
    private static final String KEY_PENDING_WIDGET = "widget_pending_id";
    /**
     * Set the moment the default clock widget is PLACED — never on a failed
     * attempt, so a launch where the bind grant had not landed yet (or the
     * clock app was disabled) tries again next time. Once set it never seeds
     * again: removing the clock is a choice, not a bug to fix. Package-visible
     * because the reset flows clear it — a desktop reset to "fresh" gets its
     * default clock back.
     */
    static final String KEY_CLOCK_SEEDED = "widget_clock_seeded";
    /** Tray battery pill ("⚡ 87%") — refreshed by batteryReceiver. */
    private TextView batteryPill;
    /** Tray fullscreen toggle — glyph mirrors the PC window's state. */
    private TextView fsButton;
    private boolean pcFullscreen = false;
    /** Latest ACTION_BATTERY_CHANGED sticky intent — feeds pill + flyout. */
    private Intent lastBattery;
    private boolean torchOn = false;
    private CameraManager.TorchCallback torchCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int cascade = 0;
    /** Last broadcast seq applied — the PC fires broadcasts without waiting,
     *  so they can arrive out of order. */
    private int lastSeq = -1;

    private final BroadcastReceiver runningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int seq = intent.getIntExtra("seq", -1);
            if (seq >= 0) {
                // drop stale in-flight broadcasts; a much smaller seq means
                // the PC side restarted — accept and resync from those
                if (lastSeq >= 0 && seq < lastSeq && lastSeq - seq < 100) return;
                lastSeq = seq;
            }
            // authoritative PC-window fullscreen state rides along
            if (intent.hasExtra("fs")) {
                setPcFullscreen(intent.getBooleanExtra("fs", pcFullscreen));
            }
            // …and so does whether that computer has a touchpad we can read.
            // Stored rather than acted on: the only consumer is the Settings
            // window, which may not be open, and which cannot ask the PC
            // anything itself — the request queue only runs the other way.
            if (intent.hasExtra("tp")) {
                DexPrefs.put(LauncherActivity.this, DexPrefs.KEY_HOST_TOUCHPAD,
                        intent.getBooleanExtra("tp", false));
            }
            String csv = intent.getStringExtra("pkgs");
            LinkedHashSet<String> next = new LinkedHashSet<>();
            if (csv != null) {
                for (String p : csv.split(",")) {
                    if (!p.trim().isEmpty()) next.add(p.trim());
                }
            }
            // Minimised apps are still open. The PC builds this list from visibility, and
            // a minimised window is not visible, so it would otherwise vanish from the
            // taskbar the moment it is minimised — indistinguishable from being closed.
            next.addAll(minimisedPkgs);
            // keep icon order stable: surviving entries keep their slot,
            // newly opened apps append on the right
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            for (String p : runningPkgs) {
                if (next.contains(p)) merged.add(p);
            }
            merged.addAll(next);
            if (!merged.equals(runningPkgs)) {
                runningPkgs.clear();
                runningPkgs.addAll(merged);
                refreshOpenApps();
            }
        }
    };

    /**
     * A three-finger gesture on the computer's touchpad.
     *
     * Exported, like the running-apps and file-transfer receivers above and for
     * the same reason: the sender is `adb shell am broadcast` from the PC,
     * which is a different uid. Nothing here does more than a taskbar button
     * already does unprompted, which is the bar these receivers are held to.
     */
    private final BroadcastReceiver gestureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getStringExtra("action");
            if (action == null) return;
            switch (action) {
                case "openapps":
                    toggleRecentsPopup();
                    break;
                case "drawer":
                    toggleDrawer();
                    break;
                case "notifications":
                    toggleQuickSettingsPopup();
                    break;
                case "showdesktop":
                    // Owned by CaptionService: it holds the live window list
                    // and the record of what has already been hidden, and the
                    // toggle needs both.
                    sendBroadcast(new Intent(ACTION_SHOW_DESKTOP).setPackage(getPackageName()));
                    break;
                default:
                    break;
            }
        }
    };

    /** Our own windows opened or closed — rebuild their taskbar tiles. */
    private final OwnWindows.Listener ownWindowsListener = this::refreshOpenApps;

    /** Packages CaptionService has minimised — open, but deliberately not visible. */
    private final LinkedHashSet<String> minimisedPkgs = new LinkedHashSet<>();
    /**
     * The per-window view of the same set: top activity class names. The
     * package set cannot tell our own two windows (Settings, Linux) apart, and
     * a tile that restores the WRONG own-package window instead of opening its
     * own is the result — so own-package tiles route on this one.
     */
    private final LinkedHashSet<String> minimisedActivities = new LinkedHashSet<>();

    private final BroadcastReceiver minimisedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String csv = intent.getStringExtra("pkgs");
            LinkedHashSet<String> next = new LinkedHashSet<>();
            if (csv != null) {
                for (String p : csv.split(",")) {
                    if (!p.trim().isEmpty()) next.add(p.trim());
                }
            }
            LinkedHashSet<String> nextActs = new LinkedHashSet<>();
            String activitiesCsv = intent.getStringExtra("activities");
            if (activitiesCsv != null) {
                for (String a : activitiesCsv.split(",")) {
                    if (!a.trim().isEmpty()) nextActs.add(a.trim());
                }
            }
            // Two of our OWN windows share one package, so the package set can
            // stay identical while the set of minimised windows changes — react
            // to the activity set too, or the taskbar misses a minimised
            // Settings/Linux swap.
            if (next.equals(minimisedPkgs) && nextActs.equals(minimisedActivities)) return;
            minimisedActivities.clear();
            minimisedActivities.addAll(nextActs);
            minimisedPkgs.clear();
            minimisedPkgs.addAll(next);
            // Show a newly minimised app straight away rather than waiting for the PC's
            // next running-broadcast. A restored one is NOT dropped here — it is running
            // and visible, so the next running-broadcast carries it on its own merit.
            runningPkgs.addAll(minimisedPkgs);
            refreshOpenApps();
        }
    };

    /** Drop progress + the confirmation that follows it. Built on first use. */
    private TransferHud transferHud;

    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (isFinishing()) return;
            if (transferHud == null) transferHud = new TransferHud(LauncherActivity.this);
            transferHud.onBroadcast(intent);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            lastBattery = intent;
            updateBatteryPill();
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 30_000);
        }
    };

    /** Shared by the tray gauge and the Task Manager — see {@link SysStats}. */
    SysStats sysStats;
    private TextView[] perfLabels;
    private ProgressBar[] perfBars;
    private View perfGaugeView;

    /**
     * Driven from onStart/onStop, not onResume/onPause like the clock: a
     * freeform window on the desktop pauses this activity while the taskbar
     * overlay stays on screen, and a performance gauge that stops moving the
     * moment you open an app is worse than none.
     */
    private final Runnable perfTick = new Runnable() {
        @Override
        public void run() {
            updatePerfGauge();
            handler.postDelayed(this, 2_000);
        }
    };

    /**
     * Density the current view tree was built for. All sizing (dp/sp) is
     * computed from this instead of Resources: a `wm density` change is
     * delivered to Resources only while the activity is foreground —
     * behind a focused app window (exactly where the Settings window puts
     * us) that delivery is deferred indefinitely, and views built from
     * Resources would come out at the old scale.
     */
    private int uiDensity = 160;

    /** Display width in px the view tree was built for — see refreshDisplayMetrics. */
    private int uiWidthPx;

    /** True when that width is too narrow for the full desktop chrome. */
    private boolean compact;

    /** Palette + surface geometry for this build of the view tree. */
    private DexTheme theme;

    /**
     * A setting moved in the Settings window. Everything the desktop draws
     * comes out of {@link DexTheme}/{@link DexPrefs}, so the honest response
     * to most of them is to build the shell again — it costs a few
     * milliseconds and cannot leave a stale colour behind.
     */
    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String key = intent.getStringExtra(DexPrefs.EXTRA_KEY);
            if (DexPrefs.KEY_DENSITY.equals(key)) return;   // the display listener owns that one
            if (!DexPrefs.affectsShell(key)) return;        // a desktop-app setting
            if (DexPrefs.KEY_LANGUAGE.equals(key) || "*".equals(key)) {
                // Resources were resolved against the old locale in
                // attachBaseContext; only a restart re-resolves them.
                recreate();
                return;
            }
            rebuildShell();
        }
    };

    private final android.hardware.display.DisplayManager.DisplayListener displayListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (getDisplay() != null && displayId == getDisplay().getDisplayId()) {
                        maybeRebuildForDisplay();
                    }
                }
            };

    /** The desktop carries its own language — see {@link DexLocale}. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        refreshDisplayMetrics();
        // First line the PC sees from us: which display we actually landed on
        // (it asked for one specific id), at what scale, and which of the two
        // chrome layouts that width bought.
        DexLog.step("launcher", "onCreate on display " + getDisplay().getDisplayId()
                + " at " + uiDensity + "dpi, " + displayWidthDp() + "dp wide"
                + (compact ? " — compact chrome" : ""));
        theme = DexTheme.of(this);
        loadRecents();
        reapplyDensity();
        reapplyPerfMode();
        reapplyPointerSpeed();
        // before buildUi — the desktop grid rebuilds its widget views from
        // these on every reload()
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new DexWidgetHost(this);
        // Before any createView — that call copies the host's handler onto the
        // view and applies the provider's first RemoteViews in one go, so this
        // is the last moment a widget's own clicks can be claimed.
        WidgetLaunch.install(widgetHost, this);
        try {
            // for the activity's whole life, not onStart..onStop: the desktop
            // spends most of a session STOPPED behind maximized app windows,
            // and it is exactly then that its widgets stay visible beside them
            widgetHost.startListening();
        } catch (Exception e) {
            DexLog.warn("widgets", "startListening failed — widgets will not update", e);
        }
        live = new WeakReference<>(this);
        // A recreate() (language change) leaves the previous instance's detour
        // task standing, and releaseOrphanedAdd is about to free the id that
        // dialog is bound to. Close it first, so the release is honest. The
        // forget covers the other direction: an entry from a life that ended
        // without an onDestroy can name a task id nothing will ever retire.
        WidgetDetourActivity.dismiss();
        WidgetDetourActivity.forgetTasks();
        releaseOrphanedAdd();
        buildUi();
        setupTaskbar();
        loadApps();
        IntentFilter filter = new IntentFilter(ACTION_RUNNING);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(runningReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(runningReceiver, filter);
        }
        // Our own windows report themselves directly — no broadcast, they are
        // in this very process. Registering REPLACES any earlier listener, so a
        // density-driven relaunch of the desktop cannot leave a dead one behind.
        OwnWindows.setListener(ownWindowsListener);
        // Same process, so not exported.
        IntentFilter minimisedFilter = new IntentFilter(ACTION_MINIMISED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(minimisedReceiver, minimisedFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(minimisedReceiver, minimisedFilter);
        }
        IntentFilter settingsFilter = new IntentFilter(DexPrefs.ACTION_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }
        IntentFilter gestureFilter = new IntentFilter(ACTION_GESTURE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(gestureReceiver, gestureFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(gestureReceiver, gestureFilter);
        }
        // From the PC (adb), like the running-apps broadcast: exported.
        IntentFilter transferFilter = new IntentFilter(ACTION_TRANSFER);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(transferReceiver, transferFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(transferReceiver, transferFilter);
        }
        // sticky: returns the latest battery snapshot immediately
        lastBattery = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        updateBatteryPill();
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            torchCallback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    torchOn = enabled;
                }
            };
            cm.registerTorchCallback(torchCallback, handler);
        } catch (Exception ignored) {
        }
        // Fires on every display config change regardless of lifecycle
        // state — the reliable signal that `wm density` landed.
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.registerDisplayListener(displayListener, handler);
        DexLog.step("launcher", "desktop ready — " + allApps.size() + " apps, taskbar "
                + (taskbarOverlay ? "as an overlay window" : "inside the activity"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Drops the sampler's view references. The gauge lives in the
        // activity's own view tree now, so it goes with the window either way.
        detachPerfGauge();
        try {
            if (widgetHost != null) widgetHost.stopListening();
        } catch (Exception ignored) {
        }
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            dm.unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(runningReceiver);
        } catch (Exception ignored) {
        }
        OwnWindows.clearListener(ownWindowsListener);
        try {
            unregisterReceiver(minimisedReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(gestureReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(transferReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        if (transferHud != null) transferHud.detach();
        if (live != null && live.get() == this) live = null;
        // never end a session — or a recreate — with a detour task standing
        // on the display, waiting for a desktop that is going away
        WidgetDetourActivity.dismiss();
        try {
            if (torchCallback != null) {
                ((CameraManager) getSystemService(CAMERA_SERVICE))
                        .unregisterTorchCallback(torchCallback);
            }
        } catch (Exception ignored) {
        }
        dismissPopups();
        hideDrawer();
        if (taskbarOverlay && taskbarView != null) {
            try {
                getWindowManager().removeViewImmediate(taskbarView);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(perfTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(perfTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    public void onBackPressed() {
        if (drawerShown) hideDrawer();
        // the desktop is the bottom of the stack — never finish it
    }

    /**
     * A fresh scrcpy display always comes up at the phone's ~340+ dpi, which
     * reads as absurdly zoomed on a monitor. Ask the PC to apply the user's
     * stored display size — or, before any choice was made, the "Default"
     * preset — so the desktop always starts at a sane scale. Once the
     * override lands, stored == current and this is a no-op.
     */
    private void reapplyDensity() {
        int stored = DexPrefs.getInt(this, DexPrefs.KEY_DENSITY, -1);
        if (stored <= 0) {
            Point size = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            stored = SettingsActivity.defaultDpi(size.x, size.y);
        }
        if (stored != currentDisplayDensity()) {
            RequestProvider.enqueue("density", String.valueOf(stored));
        }
    }

    /**
     * Re-arm the half of "Reduce quality" that lives off the phone.
     *
     * The platform's animation scales are global state that Exit DeX puts back
     * — deliberately, since leaving a phone with its animations switched off is
     * not something a desktop session gets to do. So every fresh session has to
     * ask for them again, exactly like {@link #reapplyDensity()} does.
     *
     * Only ever asks for "on". Off is the phone's own business: nothing here
     * has anything to restore, and the PC already restored it at the end of
     * the last session.
     */
    private void reapplyPerfMode() {
        if (DexPrefs.getBool(this, DexPrefs.KEY_PERF, DexPrefs.DEF_PERF)) {
            RequestProvider.enqueue("perf", "on");
        }
    }

    /**
     * Ask the PC for the pointer speed again, for the same reason
     * {@link #reapplyPerfMode()} asks for the animation scales.
     *
     * It is a Settings.System row that Exit DeX puts back — leaving a phone
     * with a desktop's pointer speed on it is not something a session gets to
     * do — so every fresh session has to raise it once more.
     *
     * Only when it is not the platform default: "0" is what the PC restored to
     * anyway on a phone that never had its own value, and re-sending it would
     * make the PC snapshot and write for nothing.
     */
    private void reapplyPointerSpeed() {
        int speed = DexPrefs.getInt(this, DexPrefs.KEY_CURSOR_SPEED, DexPrefs.DEF_CURSOR_SPEED);
        if (speed != DexPrefs.DEF_CURSOR_SPEED) {
            RequestProvider.enqueue("cursor", "speed." + speed);
        }
    }

    /** The display's live logical density — fresh even when Resources lag. */
    private int currentDisplayDensity() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm.densityDpi;
    }

    /**
     * Re-read the density and width the view tree has to match, and say whether
     * either moved.
     *
     * Width counts as much as density here: rotating the phone leaves the
     * density alone but carries the shell across {@link #COMPACT_WIDTH_DP},
     * and the tree on screen was built for the other side of that line.
     */
    private boolean refreshDisplayMetrics() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int density = currentDisplayDensity();
        boolean changed = density != uiDensity || size.x != uiWidthPx;
        uiDensity = density;
        uiWidthPx = size.x;
        compact = Math.round(size.x * 160f / Math.max(1, density)) < COMPACT_WIDTH_DP;
        return changed;
    }

    /** Rebuild every view for the display's current density and width, if either moved. */
    private void maybeRebuildForDisplay() {
        if (isFinishing()) return;
        if (!refreshDisplayMetrics()) return;
        rebuildShell();
    }

    /**
     * Throw the whole view tree away and build it again — the one path that
     * cannot leave a stale colour, radius, font or scale behind. Used for both
     * a display-size change and any settings change that repaints the shell.
     */
    private void rebuildShell() {
        if (isFinishing()) return;
        theme = DexTheme.of(this);
        // A `wm density` change moves the pointer's size with everything else
        // and arrives here WITHOUT a settings write, so the cursor cache is
        // dropped on this path too rather than only on DexPrefs.broadcast.
        DexCursors.invalidate();
        dismissPopups();
        hideDrawer();
        // Built at the old density, in the old palette, and possibly parented
        // to the rootFrame that is about to be thrown away.
        if (transferHud != null) {
            transferHud.detach();
            transferHud = null;
        }
        if (taskbarView != null) {
            if (taskbarOverlay) {
                try {
                    getWindowManager().removeViewImmediate(taskbarView);
                } catch (Exception ignored) {
                }
                taskbarOverlay = false;
            } else if (rootFrame != null) {
                rootFrame.removeView(taskbarView);
            }
        }
        buildUi();
        setupTaskbar();
        refreshOpenApps();
        updateClock();
    }

    /** Config-change fast path (foreground); the DisplayListener covers the rest. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        maybeRebuildForDisplay();
    }

    /** dp → px from uiDensity, NOT Resources (see uiDensity docs). */
    int dp(float v) {
        return Math.round(v * uiDensity / 160f);
    }

    /** The density the view tree was built for — the grid sizes widgets by it. */
    int uiDensity() {
        return uiDensity;
    }

    /** The shell's main-thread handler, for anything posting onto the UI. */
    Handler handler() {
        return handler;
    }

    /**
     * The desktop's root. Null between rebuilds — anything parenting a view
     * here has to cope with that.
     */
    FrameLayout rootFrame() {
        return rootFrame;
    }

    DexWidgetHost widgetHost() {
        return widgetHost;
    }

    AppWidgetManager widgetManager() {
        return widgetManager;
    }

    /** sp → px for setTextSize — fontScale intentionally ignored. */
    float sp(float v) {
        return v * uiDensity / 160f;
    }

    /**
     * A painted surface: rounded by the "Item rounding" setting, and grained
     * in Paper mode.
     */
    Drawable roundedFill(int color, float radiusDp) {
        return theme.surface(color, dp(theme.radius(radiusDp)));
    }

    /**
     * The same shape with no grain — for the transient layers of a button
     * (hover tint, ripple mask). Grain belongs to the surface underneath;
     * repeating it in every overlay just stacks noise on noise.
     */
    private GradientDrawable plainFill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(theme.radius(radiusDp)));
        return d;
    }

    /**
     * True when text painted straight onto the wallpaper should be light ink.
     * This follows the WALLPAPER, not the theme: those pixels sit on the
     * backdrop, not on any of our surfaces, so a light theme over a dark
     * wallpaper still needs white labels.
     */
    boolean deskLightInk() {
        return Wallpapers.lightInk(
                DexPrefs.wallpaper(this),
                DexPrefs.getInt(this, DexPrefs.KEY_WALL_DIM, DexPrefs.DEF_WALL_DIM));
    }

    /** Logical width of this display in dp — the number the layout branches on. */
    int displayWidthDp() {
        return Math.round(uiWidthPx * 160f / Math.max(1, uiDensity));
    }

    /**
     * True when the shell is on a display too narrow for the desktop chrome —
     * the phone's own screen, or a desktop display driven at a phone-like
     * "Display size".
     *
     * This is a LAYOUT switch, not a feature switch. Every control the desktop
     * bar and drawer have is still built in compact; they are drawn tighter and
     * arranged so they cannot overlap. Hiding controls below a width would make
     * the phone shell a different product from the desktop one, and the width
     * changes under us on every rotation.
     */
    boolean compact() {
        return compact;
    }

    /**
     * Height of the system's own bottom bar (gesture pill or nav keys) on this
     * display, in px.
     *
     * For views inside the ACTIVITY window only. targetSdk 35 runs that window
     * edge to edge, so nothing else insets what we draw in it. Our overlay
     * WINDOWS must NOT add these: WindowManager already lays an
     * APPLICATION_OVERLAY out inside the safe area — measured on a 1080x2400
     * phone screen, parent=[0,121][1080,2337] against a 121/63 status/nav pair
     * — and adding the inset a second time floats the bar a whole gesture pill
     * clear of the edge it is anchored to.
     *
     * Zero on a desktop display, which has no system bars — measured: an
     * overlay there gets parent=[0,0][1920,1080], the whole surface. So this
     * asks the display rather than the compact flag. A phone screen turned
     * landscape is past 600dp and still has a status bar; gating on width
     * would slide the desktop's own chrome underneath it.
     */
    int bottomSystemInset() {
        return systemInset(false);
    }

    /** Same, for the status bar / cutout at the top. */
    int topSystemInset() {
        return systemInset(true);
    }

    private int systemInset(boolean top) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        try {
            android.graphics.Insets in = getWindowManager().getCurrentWindowMetrics()
                    .getWindowInsets()
                    .getInsets(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            return top ? in.top : in.bottom;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Icon edge in dp for app tiles, from the "Desktop icons" setting. */
    int iconDp() {
        switch (DexPrefs.getString(this, DexPrefs.KEY_ICON_SIZE, DexPrefs.DEF_ICON_SIZE)) {
            case "small":
                return 42;
            case "large":
                return 62;
            default:
                return 50;
        }
    }

    /**
     * Background for a taskbar control: a resting fill, a brighter fill while
     * the pointer hovers — this desktop is mouse-driven over scrcpy, so hover
     * reads as much as press — and a ripple that animates on tap. The mask
     * clips the ripple to the same rounded rect so it never bleeds outside
     * the button. Views get hover/pressed state for free once they are
     * clickable, which setOnClickListener does.
     */
    Drawable tapBackground(int restColor, int hoverColor, float radiusDp) {
        StateListDrawable content = new StateListDrawable();
        content.addState(new int[]{android.R.attr.state_hovered}, plainFill(hoverColor, radiusDp));
        content.addState(new int[0], plainFill(restColor, radiusDp));
        return new RippleDrawable(
                ColorStateList.valueOf(theme.ripple), content, plainFill(0xFFFFFFFF, radiusDp));
    }

    /** Hover shade for a button that is already painted in a solid colour. */
    private static int lighten(int color) {
        return Color.argb(Color.alpha(color),
                Math.min(255, Color.red(color) + 24),
                Math.min(255, Color.green(color) + 24),
                Math.min(255, Color.blue(color) + 24));
    }

    /**
     * Empty icon-over-label tile — child 0 is the ImageView, child 1 the label.
     * Shared by the drawer grid, the drawer's pinned row and the desktop grid so
     * an app looks identical wherever it appears. Callers fill in the drawable
     * and the text.
     *
     * The label is fixed at two lines: without it a long name overflows and is
     * clipped at BOTH ends by the center gravity ("ACE Money Transfer" → "CE
     * Money Transfe"), and cells in a row stop lining up.
     */
    LinearLayout newIconTile(int iconDp) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(10), dp(6), dp(10));
        // hover highlight + tap ripple, same feedback as the taskbar controls
        tile.setBackground(tapBackground(0x00000000, theme.hover, 14));

        ImageView icon = new ImageView(this);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        TextView label = new TextView(this);
        label.setTextColor(theme.textDim);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(6), dp(2), 0);
        tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Tiles are made lazily by the drawer's adapter, long after buildUi's
        // one-shot pass over the tree — so the chosen font and pointer are
        // applied here.
        DexFonts.applyTo(this, tile);
        DexCursors.apply(tile, DexCursors.ROLE_HAND);
        return tile;
    }

    /**
     * Taskbar as a TYPE_APPLICATION_OVERLAY window: floats above the app
     * windows, so it is usable while apps are maximized. Falls back to an
     * in-activity bar without the overlay permission (granted by the PC
     * via appops).
     */
    private void setupTaskbar() {
        // Survives a density rebuild: the CPU figure is a delta between two
        // readings, and a fresh sampler would have to throw the first one away.
        if (sysStats == null) sysStats = new SysStats(this);
        setupPerfGauge();
        taskbarView = buildTaskbar();
        // Its own TYPE_APPLICATION_OVERLAY window, so the activity's pointers
        // do not reach it — a PointerIcon resolves inside one ViewRootImpl and
        // no further. Done here rather than after attachTaskbarOverlay so the
        // in-activity fallback is covered by the same line.
        DexCursors.decorate(taskbarView);
        if (attachTaskbarOverlay()) return;
        // No overlay permission yet: show an in-activity bar so the desktop is
        // usable, but keep watching. The PC grants the app-op over adb, and if
        // it lands late this upgrades to the floating bar instead of leaving
        // the taskbar stuck underneath every app window.
        addTaskbarToActivity();
        scheduleOverlayUpgrade(0);
    }

    private void addTaskbarToActivity() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TASKBAR_DP), Gravity.BOTTOM);
        // targetSdk 35 runs the activity window edge to edge, so on the phone's
        // own screen this would otherwise land under the gesture pill.
        lp.bottomMargin = bottomSystemInset();
        rootFrame.addView(taskbarView, lp);
    }

    /** Add the taskbar as an always-on-top overlay window; false if not permitted. */
    private boolean attachTaskbarOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            DexLog.warn("taskbar", "no overlay permission yet — app windows will cover the bar");
            return false;
        }
        WindowManager.LayoutParams barLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TASKBAR_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        // No y offset for the gesture pill here, unlike addTaskbarToActivity:
        // an overlay window is already laid out inside the safe area (see
        // bottomSystemInset), and offsetting it again lifts the bar off the
        // bottom edge by the height of the pill.
        barLp.gravity = Gravity.BOTTOM;
        try {
            getWindowManager().addView(taskbarView, barLp);
        } catch (Exception e) {
            DexLog.warn("taskbar", "overlay window rejected", e);
            return false;
        }
        taskbarOverlay = true;
        return true;
    }

    /**
     * The performance gauge, top right, in the desktop's OWN view tree.
     *
     * It began as a TYPE_APPLICATION_OVERLAY window, so it would stay visible
     * over app windows. That put it above everything — including the title bar
     * of any app window in the top-right corner, whose close and maximise
     * controls it sat on top of and swallowed the taps for. A status readout
     * is not worth a window control, so it lives on the desktop layer instead:
     * on the desktop it reads like a widget, and an app window covers it, which
     * is where it belongs.
     *
     * Consequence, and it is the intended one: the gauge is visible on the
     * desktop only. The Task Manager it opens is the surface for looking at
     * load while something else is in front.
     */
    private void setupPerfGauge() {
        detachPerfGauge();
        // buildUi() always runs first (see onCreate and the density rebuild),
        // so this is the fresh root — but never assume a view tree exists.
        if (rootFrame == null) return;
        perfGaugeView = buildPerfGauge();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = topSystemInset() + dp(12);
        lp.rightMargin = dp(12);
        // Added last, so it draws over the icon grid rather than under it.
        rootFrame.addView(perfGaugeView, lp);
    }

    private void detachPerfGauge() {
        if (perfGaugeView != null && perfGaugeView.getParent() instanceof ViewGroup) {
            ((ViewGroup) perfGaugeView.getParent()).removeView(perfGaugeView);
        }
        perfGaugeView = null;
        perfLabels = null;
        perfBars = null;
    }

    private void scheduleOverlayUpgrade(int attempt) {
        if (attempt >= 10 || taskbarOverlay) return;
        handler.postDelayed(() -> {
            if (taskbarOverlay || isFinishing()) return;
            if (android.provider.Settings.canDrawOverlays(LauncherActivity.this)) {
                rootFrame.removeView(taskbarView);
                if (!attachTaskbarOverlay()) addTaskbarToActivity();
            } else {
                scheduleOverlayUpgrade(attempt + 1);
            }
        }, 1000);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        root.setBackground(Wallpapers.drawable(
                DexPrefs.wallpaper(this),
                DexPrefs.getInt(this, DexPrefs.KEY_WALL_DIM, DexPrefs.DEF_WALL_DIM),
                theme.grainAlpha, theme.paperTexture));

        // ── desktop layer: the icon grid fills the canvas, inset above the
        // taskbar strip and below the title / the grid's own "Remove" pill ──
        desktopGrid = new DesktopGrid(this);
        // The insets are 0 on a desktop display, which has no system bars of
        // its own; on the phone's screen they are what keeps the first row of
        // icons out from under the status bar and the gesture pill.
        desktopGrid.setPadding(dp(compact ? 10 : 18),
                topSystemInset() + dp(compact ? 34 : 56),
                dp(compact ? 10 : 18),
                bottomSystemInset() + dp(TASKBAR_DP + 14));
        root.addView(desktopGrid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // drawn over the grid but never clickable, so taps fall through to it
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        // over the wallpaper, not over a panel — ink follows the backdrop
        title.setTextColor(deskLightInk() ? 0x88e7ecf3 : 0x99101828);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(compact ? 14 : 28),
                topSystemInset() + dp(compact ? 10 : 18), 0, 0);
        root.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The drawer is built here but attached on demand — as its own
        // always-on-top overlay window when permitted (see showDrawer), so it
        // covers app windows instead of hiding behind them.
        buildDrawer();
        drawer.setVisibility(View.GONE);

        setContentView(root);
        DexCursors.decorate(root);
        // The grid is clickable ONLY so the empty-desktop long-press fires
        // (DesktopGrid's constructor), and decorate's tree walk cannot tell
        // that from a real target — left alone it puts the link hand over the
        // whole wallpaper. Tiles keep their own hand from newIconTile.
        DexCursors.apply(desktopGrid, DexCursors.ROLE_ARROW);
        DexFonts.applyTo(this, root);
        // a density rebuild throws the whole view tree away — repopulate the
        // fresh grid (a no-op on the very first pass, before loadApps)
        desktopGrid.reload();

        // Pre-provision Linux once the desktop is up, so the distro is
        // installed (or visibly installing) before the user ever opens the
        // tile — the app owns this now, no PC push. Idempotent: it no-ops once
        // the distro is ready and at this build's feature level.
        //
        // POSTED, not called here. This is the busiest moment the launcher
        // has — the app list, the grid, the taskbar overlay and the caption
        // service all land in the same second — and anything that arms a
        // platform deadline against the main thread is asking to miss it.
        if (Linux.abiSupported()) {
            // The shared folder exists from the first desktop session, not
            // from the first Linux launch: someone staging files in My Files
            // or over USB before ever opening Linux has to find it there. Off
            // the main thread — it touches the filesystem, and the scan is a
            // blocking binder round trip.
            new Thread(() -> {
                Linux.ensureSharedDir();
                Linux.scanShared(this);
            }, "linux-shared-dir").start();
            root.postDelayed(() -> LinuxService.provision(this), 3000);
        }
    }

    private GridView buildAppGrid() {
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        // A 58dp gutter per column costs a whole column on a phone-width
        // display, so compact packs the cells closer instead of dropping to
        // three-across with half the drawer empty.
        grid.setColumnWidth(dp(iconDp() + (compact ? 20 : 58)));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(8));
        // Each cell is clickable itself (see getView) so it highlights under
        // the mouse pointer — AbsListView's shared selector cannot track
        // hover per item. The selector is therefore disabled entirely.
        grid.setSelector(new ColorDrawable(0x00000000));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shownApps.size(); }
            @Override public Object getItem(int i) { return shownApps.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout cell;
                ImageView icon;
                TextView label;
                if (convert instanceof LinearLayout) {
                    cell = (LinearLayout) convert;
                } else {
                    cell = newIconTile(iconDp());
                }
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
                AppEntry app = shownApps.get(i);
                icon.setImageDrawable(app.icon);
                label.setText(app.label);
                // click on the cell (not the list) — being clickable is what
                // makes the hover state above light up under the mouse
                cell.setOnClickListener(v -> launch(app));
                // hold an app → the drawer gets out of the way and the app
                // rides the pointer onto the desktop grid
                cell.setOnLongClickListener(v -> {
                    startDesktopDrag(app);
                    return true;
                });
                return cell;
            }
        };
        grid.setAdapter(adapter);
        return grid;
    }

    /**
     * The drawer's own top padding, before whatever its host adds.
     *
     * As an overlay window this is the whole of it — that window is already
     * laid out below the status bar. Inside the activity it is not, so
     * {@link #showDrawer} adds the top inset there.
     */
    private int drawerTopPad() {
        return dp(compact ? 14 : 24);
    }

    private void buildDrawer() {
        LinearLayout panel = new LinearLayout(this);
        drawerPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        // the drawer is the largest surface we own — in Paper mode it is where
        // the grain reads most, so it goes through surface() like the rest
        panel.setBackground(theme.surface(theme.panel(), 0f));
        int pad = dp(compact ? 14 : 48);
        // The in-activity flavour; showDrawer sets it again for whichever host
        // the drawer actually gets.
        panel.setPadding(pad, topSystemInset() + drawerTopPad(), pad,
                bottomSystemInset() + dp(TASKBAR_DP + 12));
        panel.setClickable(true);

        panel.addView(buildPinnedRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(this);
        searchField = search;
        search.setHint(getString(R.string.lx_search_apps));
        search.setHintTextColor(theme.textFaint);
        search.setTextColor(theme.text);
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        search.setSingleLine(true);
        search.setBackground(roundedFill(theme.field, 22));
        search.setPadding(dp(20), dp(11), dp(20), dp(11));
        // blinking caret so it is obvious typing goes here
        search.setCursorVisible(true);
        search.setOnClickListener(v -> search.requestFocus());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        panel.addView(search, new LinearLayout.LayoutParams(
                compact ? ViewGroup.LayoutParams.MATCH_PARENT : dp(360),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        GridView grid = buildAppGrid();
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(16);
        panel.addView(grid, gridLp);

        // The window root is a frame so the drag layer can sit over the panel:
        // once a drag starts the panel is hidden and only the layer paints,
        // leaving the desktop grid visible straight through this window.
        drawer = new DrawerRoot(this);
        drawer.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dragLayer = new DragLayer(this);
        dragLayer.setVisibility(View.GONE);
        drawer.addView(dragLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Third window of the shell, and the one whose pointer changes mid
        // gesture — see DragLayer.onResolvePointerIcon.
        DexCursors.decorate(drawer);
        // Same trap as the desktop grid: the panel is clickable only to swallow
        // taps that would otherwise fall through, and every AbsListView is
        // clickable straight from its own constructor. Neither is something to
        // click, so the hand is taken back; the cells keep theirs.
        DexCursors.apply(panel, DexCursors.ROLE_ARROW);
        DexCursors.apply(grid, DexCursors.ROLE_ARROW);
    }

    /**
     * Drawer window root. Two jobs beyond holding the panel:
     *
     * - Back/Esc. As an overlay window the drawer is not part of the activity,
     *   so Activity.onBackPressed never fires for it.
     * - The drag out of the drawer. Once {@link #startDesktopDrag} arms it,
     *   every remaining event of the gesture is consumed here instead of
     *   reaching the app list — the pointer is placing an icon now, not
     *   scrolling. Keeping the gesture in the window that received the DOWN is
     *   also what makes the drag survive the drawer "closing".
     */
    private final class DrawerRoot extends FrameLayout {

        DrawerRoot(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (dragApp != null) {
                        finishDesktopDrag(false);
                    } else {
                        hideDrawer();
                    }
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            drawerRawX = ev.getRawX();
            drawerRawY = ev.getRawY();
            if (dragApp == null) return super.dispatchTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    updateDesktopDrag();
                    break;
                case MotionEvent.ACTION_UP:
                    finishDesktopDrag(true);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    finishDesktopDrag(false);
                    break;
                default:
                    break;
            }
            return true;
        }

        /** Release the tile the long press left pressed, and its touch target. */
        void cancelChildTouch() {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
    }

    /**
     * Painted over the (hidden) drawer while an app is being dragged out of it:
     * faint outlines of every desktop cell, a highlight on the one under the
     * pointer, and the app icon riding the cursor. It lives in the drawer's
     * window but takes its geometry from the desktop grid in the activity
     * window — both report screen coordinates, so the highlight lands exactly
     * on the cell the drop will use.
     */
    private final class DragLayer extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        /** Scratch for the guide loop. */
        private final Rect cellScreen = new Rect();
        /** The cell the drop would use — kept apart from the scratch above. */
        private final Rect targetScreen = new Rect();
        private final int[] loc = new int[2];
        private Drawable ghost;
        private boolean hasCell;
        private float pointerX, pointerY;

        DragLayer(Context context) {
            super(context);
        }

        void begin(Drawable icon) {
            ghost = icon;
            hasCell = false;
        }

        void setPointer(float rawX, float rawY) {
            getLocationOnScreen(loc);
            pointerX = rawX - loc[0];
            pointerY = rawY - loc[1];
            invalidate();
        }

        void setCell(Rect screenRect) {
            targetScreen.set(screenRect);
            hasCell = true;
            invalidate();
        }

        void clearCell() {
            hasCell = false;
            invalidate();
        }

        /**
         * The one pointer in the shell whose meaning changes inside a single
         * gesture: a closed hand while the icon has somewhere to land, the
         * no-drop ring while it does not.
         *
         * An override rather than setPointerIcon because this is re-asked on
         * every hover event, and the answer moves with the mouse. Note it only
         * ever resolves while the drag is a HOVER — a left-button drag arrives
         * from scrcpy as a touch, and the framework does not re-resolve the
         * pointer for a non-mouse source.
         */
        @Override
        public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
            return DexCursors.icon(getContext(),
                    hasCell ? DexCursors.ROLE_GRABBING : DexCursors.ROLE_NO_DROP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (ghost == null || desktopGrid == null) return;
            getLocationOnScreen(loc);
            float radius = dp(14);

            // cell guides — the same grid the desktop will snap to
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(1)));
            paint.setColor(0x12FFFFFF);
            for (int row = 0; row < desktopGrid.rowCount(); row++) {
                for (int col = 0; col < desktopGrid.columns(); col++) {
                    desktopGrid.cellRectOnScreen(col, row, cellScreen);
                    localCell(cellScreen);
                    canvas.drawRoundRect(rectF, radius, radius, paint);
                }
            }
            if (hasCell) {
                int accent = theme.accent & 0x00FFFFFF;
                localCell(targetScreen);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x2E000000 | accent);
                canvas.drawRoundRect(rectF, radius, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(1.5f)));
                paint.setColor(0xAA000000 | accent);
                canvas.drawRoundRect(rectF, radius, radius, paint);
            }

            // the icon itself, held slightly above the pointer so the cursor
            // never covers what is being placed
            int size = dp(58);
            int left = Math.round(pointerX - size / 2f);
            int top = Math.round(pointerY - size * 0.75f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            rectF.set(left - dp(8), top - dp(6), left + size + dp(8), top + size + dp(10));
            canvas.drawRoundRect(rectF, radius, radius, paint);
            ghost.setBounds(left, top, left + size, top + size);
            ghost.setAlpha(235);
            ghost.draw(canvas);
            ghost.setAlpha(255);
        }

        /** Screen-space cell rect → this view's coordinates, inset like the grid. */
        private void localCell(Rect screenRect) {
            float inset = dp(5);
            rectF.set(screenRect.left - loc[0] + inset, screenRect.top - loc[1] + inset,
                    screenRect.right - loc[0] - inset, screenRect.bottom - loc[1] - inset);
        }
    }

    // ── drag from the drawer onto the desktop grid ──

    /**
     * A drawer icon was held: close the drawer's chrome and let the app ride
     * the pointer. The drawer window stays attached (invisible) because it owns
     * the in-flight gesture — removing it would cancel the drag outright — and
     * is torn down for real when the drop lands.
     */
    private void startDesktopDrag(AppEntry app) {
        if (desktopGrid == null || dragLayer == null || dragApp != null) return;
        drawer.cancelChildTouch();
        dismissPopups();
        dragApp = app;
        dragCell = null;
        drawerPanel.setVisibility(View.INVISIBLE);
        dragLayer.begin(app.icon);
        dragLayer.setVisibility(View.VISIBLE);
        // whatever was on top of the desktop, the grid is the drop target —
        // bring it up so the user can see where the icon is going
        bringDesktopForward();
        updateDesktopDrag();
    }

    private void updateDesktopDrag() {
        dragLayer.setPointer(drawerRawX, drawerRawY);
        dragCell = desktopGrid.cellAtScreen(drawerRawX, drawerRawY);
        if (dragCell == null) {
            dragLayer.clearCell();
            return;
        }
        desktopGrid.cellRectOnScreen(dragCell[0], dragCell[1], dragCellRect);
        dragLayer.setCell(dragCellRect);
    }

    /** End the drag: place the app when it was released over a cell, then close. */
    private void finishDesktopDrag(boolean commit) {
        AppEntry app = dragApp;
        int[] cell = dragCell;
        dragApp = null;
        dragCell = null;
        dragLayer.setVisibility(View.GONE);
        dragLayer.begin(null);
        // hideDrawer detaches the very window whose touch dispatch we are
        // inside — unwind out of it first. It also restores the panel, which is
        // why that is not done here: the drawer would flash back for a frame.
        handler.post(this::hideDrawer);
        if (!commit || app == null || cell == null) return;
        // the drop lands in the activity's window, so it is safe to run now —
        // and running it now is what makes the icon appear as the pointer lifts
        if (!desktopGrid.dropApp(app, cell[0], cell[1])) {
            Toast.makeText(this, getString(R.string.lx_no_room), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Pinned system row at the top of the drawer — DeX-style. Holds our own
     * in-desktop tools (Settings and Linux), which loadApps skips because
     * they live in this package.
     */
    private View buildPinnedRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(this);
        header.setText(getString(R.string.lx_system_apps).toUpperCase(Locale.getDefault()));
        header.setTextColor(theme.textFaint);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setPadding(dp(8), 0, 0, dp(4));
        wrap.addView(header);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout tile = newIconTile(iconDp());
        tile.setOnClickListener(v -> launchSettings());

        ImageView icon = (ImageView) tile.getChildAt(0);
        icon.setImageResource(android.R.drawable.ic_menu_preferences);
        icon.setColorFilter(theme.accent);
        icon.setBackground(roundedFill(theme.accentSoft, 12));
        int iconPad = dp(9);
        icon.setPadding(iconPad, iconPad, iconPad, iconPad);

        ((TextView) tile.getChildAt(1)).setText(getString(R.string.settings_label));

        row.addView(tile, new LinearLayout.LayoutParams(dp(iconDp() + (compact ? 20 : 58)),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout linuxTile = newIconTile(iconDp());
        linuxTile.setOnClickListener(v -> launchLinux());
        // Right-click (forwarded by scrcpy) and long-press both reach the one
        // thing you cannot do from inside a broken container: throw it away.
        linuxTile.setOnContextClickListener(v -> {
            showLinuxMenu(v);
            return true;
        });
        linuxTile.setOnLongClickListener(v -> {
            showLinuxMenu(v);
            return true;
        });

        // A font glyph rather than a drawable: nothing in the framework draws
        // a penguin, and the emoji is the one icon every device already ships.
        // Swapped in at index 0 so the tile keeps newIconTile's child layout.
        TextView penguin = new TextView(this);
        penguin.setText("🐧");
        penguin.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(iconDp()) * 0.52f);
        penguin.setGravity(Gravity.CENTER);
        penguin.setBackground(roundedFill(theme.accentSoft, 12));
        linuxTile.removeViewAt(0);
        linuxTile.addView(penguin, 0, new LinearLayout.LayoutParams(dp(iconDp()), dp(iconDp())));

        ((TextView) linuxTile.getChildAt(1)).setText(getString(R.string.ln_label));

        row.addView(linuxTile, new LinearLayout.LayoutParams(dp(iconDp() + (compact ? 20 : 58)),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Docker sits beside Linux rather than inside it, because it is not a
        // guest of the Ubuntu container and could never be one: it is a whole
        // virtual machine of its own (Docker.java has the kernel measurements
        // that force that). Hidden outright where the APK has no QEMU for the
        // ABI — an offer that cannot be honoured is worse than no offer.
        if (Docker.abiSupported()) {
            LinearLayout dockerTile = newIconTile(iconDp());
            dockerTile.setOnClickListener(v -> launchDocker());
            dockerTile.setOnContextClickListener(v -> {
                showDockerMenu(v);
                return true;
            });
            dockerTile.setOnLongClickListener(v -> {
                showDockerMenu(v);
                return true;
            });

            // Same reasoning as the penguin above: a font glyph, because the
            // framework draws no whale and the emoji ships on every device.
            TextView whale = new TextView(this);
            whale.setText("🐳");
            whale.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(iconDp()) * 0.52f);
            whale.setGravity(Gravity.CENTER);
            whale.setBackground(roundedFill(theme.accentSoft, 12));
            dockerTile.removeViewAt(0);
            dockerTile.addView(whale, 0,
                    new LinearLayout.LayoutParams(dp(iconDp()), dp(iconDp())));

            ((TextView) dockerTile.getChildAt(1)).setText(getString(R.string.dk_label));
            row.addView(dockerTile, new LinearLayout.LayoutParams(dp(iconDp() + (compact ? 20 : 58)),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // The Web viewer sits with the other in-desktop tools because it is
        // one: it hands this phone's screen to a browser somewhere else. Never
        // hidden on a device basis — MediaProjection is on every Android this
        // APK runs on, and the one thing that can be missing (the
        // accessibility service, for control) is something the window itself
        // says how to fix.
        LinearLayout webTile = newIconTile(iconDp());
        webTile.setOnClickListener(v -> launchWeb());
        webTile.setOnContextClickListener(v -> {
            showWebMenu(v);
            return true;
        });
        webTile.setOnLongClickListener(v -> {
            showWebMenu(v);
            return true;
        });

        // Same reasoning as the penguin and the whale above: a font glyph,
        // because the framework draws no globe and the emoji ships everywhere.
        TextView globe = new TextView(this);
        globe.setText("🌐");
        globe.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(iconDp()) * 0.52f);
        globe.setGravity(Gravity.CENTER);
        globe.setBackground(roundedFill(theme.accentSoft, 12));
        webTile.removeViewAt(0);
        webTile.addView(globe, 0, new LinearLayout.LayoutParams(dp(iconDp()), dp(iconDp())));

        ((TextView) webTile.getChildAt(1)).setText(getString(R.string.wb_label));
        row.addView(webTile, new LinearLayout.LayoutParams(dp(iconDp() + (compact ? 20 : 58)),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (compact) {
            // Four system tiles still overrun a phone's width once "Desktop
            // icons" is set to large, and the ones that would be clipped are
            // Docker and the Web viewer. Scrolling costs nothing here and
            // clipping costs a tile.
            HorizontalScrollView rowScroll = new HorizontalScrollView(this);
            rowScroll.setHorizontalScrollBarEnabled(false);
            rowScroll.addView(row);
            wrap.addView(rowScroll);
        } else {
            wrap.addView(row);
        }

        View divider = new View(this);
        divider.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(6);
        divLp.bottomMargin = dp(16);
        wrap.addView(divider, divLp);
        return wrap;
    }

    /**
     * Open the in-desktop Settings window, centered and freeform. Wide enough
     * for the section list and a section side by side — below ~660dp the
     * window falls back to one pane at a time, which is a worse first sight of
     * it than simply opening big.
     */
    private void launchSettings() {
        hideDrawer();
        dismissPopups();
        // Settings wears our caption, so it can be minimised — and it is a
        // window with no taskbar icon to bring it back (loadApps skips our
        // own package). `am start` does not clear the hidden flag, so a restore
        // has to go to CaptionService, exactly like a taskbar icon does —
        // addressed by ACTIVITY, not package: the Linux window shares our
        // package, and a pkg-only restore could un-hide that one instead.
        if (minimisedActivities.contains(SettingsActivity.class.getName())) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", getPackageName())
                    .putExtra("activity", SettingsActivity.class.getName()));
            return;
        }
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int w = Math.min(dp(940), size.x * 9 / 10);
        int h = Math.min(dp(640), size.y * 9 / 10);
        int x = (size.x - w) / 2;
        int y = (size.y - h) / 2;
        Intent intent = new Intent(this, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(), new Rect(x, y, x + w, y + h));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.lx_cannot_open_settings),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Open the Linux window, centered and freeform. Sized for a usable XFCE
     * session: the container's display is created at whatever this window
     * first measures, so opening big is opening sharp.
     */
    // ── tray: performance gauge ──

    /**
     * Three live gauges in the tray — processor, memory, storage — and the way
     * into the Task Manager.
     *
     * Built from a label and a horizontal ProgressBar per metric rather than a
     * custom-drawn widget: it is the same idiom as the install bar, it inherits
     * the theme's accent, and it stays legible at every density the desktop can
     * be put into. Percentages sit IN the label because a bare bar at this size
     * reads as decoration, not as a number.
     */
    private View buildPerfGauge() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(dp(10), dp(6), dp(10), dp(6));
        // Its own surface: over the wallpaper there is no panel behind it.
        wrap.setBackground(tapBackground(theme.bar(), theme.hover, 12));
        wrap.setContentDescription(getString(R.string.tm_title));
        wrap.setOnClickListener(v -> launchTaskManager());
        wrap.setOnLongClickListener(v -> {
            launchTaskManager();
            return true;
        });

        perfLabels = new TextView[3];
        perfBars = new ProgressBar[3];
        int[] captions = {R.string.tm_cpu_short, R.string.tm_mem_short, R.string.tm_disk_short};
        for (int i = 0; i < 3; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView label = new TextView(this);
            label.setText(getString(captions[i]));
            label.setTextColor(theme.textDim);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(9));
            label.setGravity(Gravity.CENTER);
            col.addView(label);

            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressTintList(ColorStateList.valueOf(theme.accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(34), dp(3));
            barLp.topMargin = dp(2);
            col.addView(bar, barLp);

            perfLabels[i] = label;
            perfBars[i] = bar;

            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) colLp.leftMargin = dp(7);
            wrap.addView(col, colLp);
        }
        updatePerfGauge();
        return wrap;
    }

    private void updatePerfGauge() {
        if (perfLabels == null || sysStats == null) return;
        sysStats.sample();
        int[] values = {sysStats.cpuPercent, sysStats.memPercent, sysStats.diskPercent};
        int[] captions = {R.string.tm_cpu_short, R.string.tm_mem_short, R.string.tm_disk_short};
        for (int i = 0; i < 3; i++) {
            int v = values[i];
            // -1 is "the platform will not tell us", which must not render as 0%
            perfLabels[i].setText(v < 0
                    ? getString(captions[i])
                    : getString(captions[i]) + " " + v + "%");
            perfBars[i].setProgress(Math.max(v, 0));
            // Storage is the one that matters when it is nearly full, and the
            // processor when it is pegged; colour the bar rather than add chrome.
            perfBars[i].setProgressTintList(ColorStateList.valueOf(
                    v >= 90 ? theme.danger : theme.accent));
        }
    }

    private void launchTaskManager() {
        dismissPopups();
        if (minimisedActivities.contains(TaskManagerActivity.class.getName())) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", getPackageName())
                    .putExtra("activity", TaskManagerActivity.class.getName()));
            return;
        }
        Intent intent = new Intent(this, TaskManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(), desktopWindowRect(dp(720), dp(560)));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.tm_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /** Right-click / long-press on the Linux tile. */
    private void showLinuxMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, getString(R.string.ln_label));
        menu.getMenu().add(0, 2, 1, getString(R.string.ln_shared));
        // Offered only while the grant is missing, because it is the one thing
        // that makes files OTHER Android apps put in the folder show up inside
        // Linux. Nothing here needs it — see Linux.hasAllFiles().
        if (!Linux.hasAllFiles()) {
            menu.getMenu().add(0, 3, 2, getString(R.string.ln_shared_grant));
        }
        menu.getMenu().add(0, 4, 3, getString(R.string.ln_reinstall));
        // Only while there is something to remove. Offering "Uninstall" over a
        // container that is already gone is an action that would do nothing,
        // and it is the entry that reads most like it should.
        if (Linux.isInstalled(this)) {
            menu.getMenu().add(0, 5, 4, getString(R.string.ln_uninstall));
        }
        // One explicit id per branch and NO trailing else. The else used to be
        // the reinstall, so any item added after it would have dropped the user
        // straight into a container wipe.
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                launchLinux();
            } else if (id == 2) {
                openSharedFolder();
            } else if (id == 3) {
                requestAllFilesAccess();
            } else if (id == 4) {
                confirmLinuxReinstall();
            } else if (id == 5) {
                confirmLinuxUninstall();
            }
            return true;
        });
        menu.show();
    }

    /**
     * The shared folder, in an Android file manager, as a freeform window on
     * this display like everything else the desktop launches.
     *
     * Named through ExternalStorageProvider rather than as a {@code file://}
     * path: the launcher holds no storage permission by default (the folder
     * deliberately needs none), so a file URI would open a manager that then
     * shows nothing.
     *
     * The handler is RESOLVED rather than left implicit, for the reason
     * TransferHud records: more than one app answers this on a Samsung phone,
     * and the system chooser — launched into a freeform window on a secondary
     * display — opens and closes again without ever showing anything. With no
     * concrete handler we say where the folder is instead of firing an intent
     * that would vanish.
     */
    private void openSharedFolder() {
        dismissPopups();
        java.io.File dir = Linux.sharedDir();
        // A top-level folder on shared storage cannot be created without the
        // all-files grant, so a failure here has exactly one likely cause and
        // exactly one useful next step. Offering the grant beats opening a file
        // manager onto a folder that is not there.
        if (!Linux.ensureSharedDir() && !Linux.hasAllFiles()) {
            requestAllFilesAccess();
            return;
        }
        Uri uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + Linux.SHARED_NAME);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName target = null;
        try {
            ComponentName fallback = null;
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(intent, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if ("android".equals(pkg)) continue;
                ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                // NOT the head of the list, unlike TransferHud's version of
                // this: that intent carries no URI, and this one carries a
                // document URI that MANAGE_DOCUMENTS protects. We cannot grant
                // read on a URI we do not own, so handing it to whichever app
                // happens to answer first opens a window onto nothing. The
                // documents UI and Samsung's My Files are the two that hold it.
                if (pkg.contains("documentsui") || pkg.contains("myfiles")) {
                    target = cn;
                    break;
                }
                if (fallback == null) fallback = cn;
            }
            if (target == null) target = fallback;
        } catch (Exception e) {
            DexLog.warn("linux", "cannot resolve a file manager for the shared folder", e);
        }
        DexLog.step("linux", "open shared folder " + dir + " → "
                + (target != null ? target.flattenToShortString() : "no handler"));
        if (target != null) {
            intent.setComponent(target);
            ActivityOptions opts = shapeForDesktop(
                    ActivityOptions.makeBasic(), desktopWindowRect(dp(900), dp(640)));
            try {
                startActivity(intent, opts.toBundle());
                return;
            } catch (Exception e) {
                DexLog.warn("linux", "file manager refused the shared folder", e);
            }
        }
        // Nothing would open it — say where it is, which is still actionable.
        Toast.makeText(this, getString(R.string.ln_shared_where, dir.getAbsolutePath()),
                Toast.LENGTH_LONG).show();
    }

    /**
     * Ask before sending the user to the OS "All files access" screen.
     *
     * The confirmation is not politeness. Revoking this op makes the platform
     * kill the whole app id (StorageManagerService.killAppForOpChange), which
     * takes a running container down mid-write AND kills the launcher — the
     * HOME task of the desktop display. Someone who turns it on from here has
     * to know what turning it off again does.
     */
    private void requestAllFilesAccess() {
        dismissPopups();
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ln_shared_grant_title))
                .setMessage(getString(R.string.ln_shared_grant_body))
                .setNegativeButton(getString(R.string.st_cancel), null)
                .setPositiveButton(getString(R.string.ln_shared_grant_go),
                        (d, w) -> openAllFilesScreen())
                .show();
    }

    /**
     * The OS "All files access" screen for this app.
     *
     * An on-device Settings screen, so it works with a PC and without one —
     * the Linux feature is app-owned and must never need the desktop host to
     * grant anything. We never set the app-op ourselves.
     */
    private void openAllFilesScreen() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(), desktopWindowRect(dp(820), dp(620)));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            DexLog.warn("linux", "cannot open the all-files-access screen", e);
            Toast.makeText(this, getString(R.string.ln_shared_grant_failed),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Wipe the container and install it again from nothing.
     *
     * The one repair that has to live OUT here rather than in the Linux window:
     * the cases that need it — a half-finished install, a guest someone broke
     * from the inside, a rootfs that will not start — are exactly the ones
     * where that window never gets far enough to offer anything.
     */
    private void confirmLinuxReinstall() {
        dismissPopups();
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ln_reinstall))
                .setMessage(getString(R.string.ln_reinstall_body))
                .setNegativeButton(getString(R.string.st_cancel), null)
                .setPositiveButton(getString(R.string.ln_reinstall_go), (d, w) -> {
                    LinuxService.reset(this);
                    Toast.makeText(this, getString(R.string.ln_reinstall_started),
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    /**
     * Remove the container and leave it removed.
     *
     * Deliberately NOT a variant of Reinstall. That one wipes and immediately
     * downloads again, which is the right answer for a broken container and
     * the wrong one for someone who wants the ~1.5 GB back. Because the
     * desktop provisions on every launch, "removed" has to be a state we
     * remember (Linux's uninstall marker) or the next start would quietly
     * undo it — opening Linux is what deliberately reverses it.
     */
    private void confirmLinuxUninstall() {
        dismissPopups();
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ln_uninstall))
                .setMessage(getString(R.string.ln_uninstall_body))
                .setNegativeButton(getString(R.string.st_cancel), null)
                .setPositiveButton(getString(R.string.ln_uninstall_go), (d, w) -> {
                    LinuxService.uninstall(this);
                    Toast.makeText(this, getString(R.string.ln_uninstall_done),
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void launchLinux() {
        hideDrawer();
        dismissPopups();
        // Opening Linux is the way back from an uninstall, and the only one:
        // clearing the marker here — on an explicit request for Linux, never
        // on the desktop's own provision-on-launch — is what keeps "uninstall"
        // meaning uninstalled while still leaving the tile working.
        Linux.setUninstalled(this, false);
        // Same restore dance as Settings above: our own package has no
        // taskbar icon, so a minimised window can only come back through
        // CaptionService — and it must name THIS activity, or a minimised
        // Settings window would be restored in its place.
        if (minimisedActivities.contains(LinuxActivity.class.getName())) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", getPackageName())
                    .putExtra("activity", LinuxActivity.class.getName()));
            return;
        }
        Intent intent = new Intent(this, LinuxActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(), desktopWindowRect(dp(1100), dp(750)));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ln_cannot_open),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Right-click / long-press on the Docker tile. */
    private void showDockerMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, getString(R.string.dk_label));
        menu.getMenu().add(0, 2, 1, getString(R.string.dk_reinstall));
        // One explicit id per branch and no trailing else, for the reason
        // spelled out on showLinuxMenu: an else that lands on the destructive
        // item turns every future addition into a wipe.
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                launchDocker();
            } else if (id == 2) {
                confirmDockerReset();
            }
            return true;
        });
        menu.show();
    }

    /**
     * Throw the Docker machine away and build it again.
     *
     * Out here rather than in the Docker window for the same reason the Linux
     * reinstall is: the states that need it — a half-finished install, a guest
     * someone broke from the inside, a disk that will not boot — are exactly
     * the ones where that window never gets far enough to offer anything.
     */
    private void confirmDockerReset() {
        dismissPopups();
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_reinstall))
                .setMessage(getString(R.string.dk_reinstall_body))
                .setNegativeButton(getString(R.string.st_cancel), null)
                .setPositiveButton(getString(R.string.dk_reinstall_go), (d, w) -> {
                    DockerService.reset(this);
                    Toast.makeText(this, getString(R.string.dk_reinstall_started),
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    /**
     * Open the Docker window, centered and freeform.
     *
     * Deliberately does NOT provision on the way in. The window itself asks
     * for that once it is on screen, so the ~40 MB of Alpine and the virtual
     * disk are only fetched by someone who is looking at the progress bar for
     * them — unlike Linux, which the desktop pre-provisions because its window
     * is useless until the whole distro is there.
     */
    private void launchDocker() {
        hideDrawer();
        dismissPopups();
        // Same restore dance as Settings and Linux: our own package has no
        // taskbar icon, so a minimised window can only come back through
        // CaptionService — and it must name THIS activity.
        if (minimisedActivities.contains(DockerActivity.class.getName())) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", getPackageName())
                    .putExtra("activity", DockerActivity.class.getName()));
            return;
        }
        Intent intent = new Intent(this, DockerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(), desktopWindowRect(dp(900), dp(640)));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.dk_cannot_open),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Right-click / long-press on the Web viewer tile. */
    private void showWebMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, getString(R.string.wb_label));
        boolean running = WebService.isRunning();
        menu.getMenu().add(0, 2, 1, getString(running ? R.string.wb_stop : R.string.wb_start));
        // One explicit id per branch and no trailing else, for the reason
        // spelled out on showLinuxMenu.
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                launchWeb();
            } else if (id == 2) {
                if (WebService.isRunning()) {
                    WebService.stop(this);
                } else {
                    WebService.start(this);
                }
            }
            return true;
        });
        menu.show();
    }

    /**
     * Open the Web viewer window, centered and freeform.
     *
     * Deliberately does NOT start serving on the way in. Handing a phone's
     * screen to the network has to be an explicit press, on a window that has
     * already said what the access code is and who can reach it.
     */
    private void launchWeb() {
        hideDrawer();
        dismissPopups();
        // Same restore dance as Settings, Linux and Docker: our own package
        // has no taskbar icon, so a minimised window can only come back
        // through CaptionService — and it must name THIS activity.
        if (minimisedActivities.contains(WebActivity.class.getName())) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", getPackageName())
                    .putExtra("activity", WebActivity.class.getName()));
            return;
        }
        Intent intent = new Intent(this, WebActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(), desktopWindowRect(dp(760), dp(720)));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.wb_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /** Small square nav button (back / home / recents). */
    private TextView navButton(String glyph, String description, View.OnClickListener onClick) {
        TextView btn = new TextView(this);
        btn.setText(glyph);
        btn.setContentDescription(description);
        btn.setTextColor(theme.textDim);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(tapBackground(0x00000000, theme.hover, 10));
        btn.setOnClickListener(onClick);
        return btn;
    }

    /**
     * Taskbar. On a desktop-width display the three clusters anchor
     * independently in a FrameLayout — nav keys left, apps + open apps truly
     * centered, clock/date right.
     *
     * That arrangement cannot hold on the phone's own screen. At ~410dp the
     * tray alone is half the width and the centered cluster is drawn straight
     * over it, because a FrameLayout has no opinion about overlap. Compact
     * therefore lays the same three clusters out in a ROW, where overlap is not
     * expressible, and gives the middle one all the slack — so the open-apps
     * strip is what shrinks and scrolls, and the tray keeps its full width.
     */
    private View buildTaskbar() {
        View nav = buildNavCluster();
        View apps = buildAppsCluster();
        View tray = buildTrayCluster();

        ViewGroup bar;
        if (compact) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBaselineAligned(false);
            row.addView(nav, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            // the one elastic cluster — the open-apps strip inside it scrolls
            row.addView(apps, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(tray, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            bar = row;
        } else {
            FrameLayout frame = new FrameLayout(this);
            frame.addView(nav, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.START | Gravity.CENTER_VERTICAL));
            frame.addView(apps, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            frame.addView(tray, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END | Gravity.CENTER_VERTICAL));
            bar = frame;
        }

        bar.setBackground(theme.surface(theme.bar(), 0f));
        // The taskbar is the one elevated surface that is on screen the whole
        // session, so its shadow is the one that keeps costing — every other
        // elevation in the shell belongs to something transient (a menu, a
        // dragged icon). Reduce quality drops it and leaves the rest alone.
        bar.setElevation(theme.perf ? 0f : dp(8));

        updateClock();
        return bar;
    }

    /** Taskbar's left cluster: back · home · open apps. */
    private View buildNavCluster() {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setPadding(dp(compact ? 4 : 8), 0, 0, 0);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                dp(compact ? 33 : 40), dp(compact ? 34 : 38));
        navLp.setMargins(dp(compact ? 1 : 2), 0, dp(compact ? 1 : 2), 0);
        // back is executed by the PC (adb key inject) via the request queue
        left.addView(navButton("◁", getString(R.string.lx_back),
                v -> RequestProvider.enqueue("key", "back")), navLp);
        left.addView(navButton("○", getString(R.string.lx_home), v -> goHome()),
                new LinearLayout.LayoutParams(navLp));
        left.addView(navButton("▢", getString(R.string.lx_recent_apps),
                v -> toggleRecentsPopup()), new LinearLayout.LayoutParams(navLp));
        return left;
    }

    /** Taskbar's middle cluster: the apps toggle and the open-apps strip. */
    private View buildAppsCluster() {
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER_VERTICAL);

        TextView apps = new TextView(this);
        // Compact keeps the glyph and drops the word: "Apps" is ~30dp that the
        // open-apps strip needs far more than this button does, and the tile is
        // still the same shape in the same place.
        apps.setText(compact ? "⊞" : "⊞  " + getString(R.string.lx_apps));
        apps.setContentDescription(getString(R.string.lx_apps));
        apps.setTextColor(theme.text);
        apps.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        apps.setGravity(Gravity.CENTER);
        apps.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        apps.setPadding(dp(compact ? 9 : 14), dp(8), dp(compact ? 9 : 14), dp(8));
        apps.setBackground(tapBackground(theme.field, theme.hover, 10));
        apps.setOnClickListener(v -> toggleDrawer());
        center.addView(apps);

        // The divider separates two clusters that compact has already pushed
        // apart with the row's own spacing — it would only cost width.
        if (!compact) {
            View divider = new View(this);
            divider.setBackgroundColor(theme.divider);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(26));
            divLp.setMargins(dp(10), 0, dp(10), 0);
            center.addView(divider, divLp);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        openAppsRow = new LinearLayout(this);
        openAppsRow.setOrientation(LinearLayout.HORIZONTAL);
        openAppsRow.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(openAppsRow);
        // Compact: the strip takes whatever the other two clusters leave and
        // scrolls inside it, so a tenth open app pushes nothing off the bar.
        LinearLayout.LayoutParams scrollLp = compact
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        if (compact) scrollLp.leftMargin = dp(4);
        center.addView(scroll, scrollLp);
        return center;
    }

    /**
     * Taskbar's right cluster, DeX style: battery · quick settings · clock/date
     * · fullscreen · exit.
     *
     * Compact shaves each control rather than dropping any of them; measured
     * against a 411dp phone screen the cluster costs ~200dp there against the
     * nav cluster's ~110dp, which still leaves the open-apps strip real room.
     */
    private View buildTrayCluster() {
        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(0, 0, dp(compact ? 4 : 8), 0);

        int square = dp(compact ? 32 : 36);
        int gap = dp(compact ? 3 : 6);

        batteryPill = new TextView(this);
        batteryPill.setTextColor(theme.text);
        batteryPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(compact ? 11 : 12));
        batteryPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        batteryPill.setGravity(Gravity.CENTER);
        batteryPill.setPadding(dp(compact ? 6 : 10), dp(compact ? 4 : 6),
                dp(compact ? 6 : 10), dp(compact ? 4 : 6));
        batteryPill.setBackground(tapBackground(theme.field, theme.hover, 10));
        batteryPill.setContentDescription(getString(R.string.lx_battery_info));
        batteryPill.setOnClickListener(v -> toggleBatteryPopup());
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMargins(0, 0, gap, 0);
        tray.addView(batteryPill, pillLp);
        updateBatteryPill();

        ImageView gear = new ImageView(this);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setColorFilter(theme.textDim);
        int gearPad = dp(compact ? 7 : 8);
        gear.setPadding(gearPad, gearPad, gearPad, gearPad);
        gear.setBackground(tapBackground(0x00000000, theme.hover, 10));
        gear.setContentDescription(getString(R.string.lx_quick_settings));
        gear.setOnClickListener(v -> toggleQuickSettingsPopup());
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(square, square);
        gearLp.setMargins(0, 0, gap, 0);
        tray.addView(gear, gearLp);

        LinearLayout clockWrap = new LinearLayout(this);
        clockWrap.setOrientation(LinearLayout.VERTICAL);
        clockWrap.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clockWrap.setPadding(dp(compact ? 5 : 10), dp(3), dp(compact ? 5 : 10), dp(3));
        clockWrap.setBackground(tapBackground(0x00000000, theme.hover, 10));
        clockView = new TextView(this);
        clockView.setTextColor(theme.text);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(compact ? 12.5f : 13.5f));
        clockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clockView.setGravity(Gravity.END);
        clockWrap.addView(clockView);
        dateView = new TextView(this);
        dateView.setTextColor(theme.textFaint);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        dateView.setGravity(Gravity.END);
        // Compact drops the date LINE, not the clock: "Wed, 19 Aug" is twice
        // the width of "14:05" and it is what sets the cluster's width. It is
        // one tap away in the calendar flyout this same control opens. The view
        // is still built, because updateClock writes to it either way.
        if (!compact) clockWrap.addView(dateView);
        clockWrap.setOnClickListener(v -> toggleCalendarPopup());
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.setMargins(0, 0, gap, 0);
        tray.addView(clockWrap, clockLp);

        // PC-window fullscreen toggle; executed by the PC via the request
        // queue, icon confirmed by the running broadcast's "fs" extra
        fsButton = navButton("⛶", getString(R.string.lx_fullscreen), v -> {
            RequestProvider.enqueue("fullscreen", "toggle");
            setPcFullscreen(!pcFullscreen); // optimistic — broadcast re-syncs
        });
        tray.addView(fsButton, new LinearLayout.LayoutParams(square, square));
        setPcFullscreen(pcFullscreen);

        // Leaving DeX is the one taskbar action that ends the whole session, so
        // it sits at the far end of the tray in the danger colour and asks
        // first — nothing else here is irreversible.
        //
        // The framework's power-off drawable rather than a ⏻ glyph: this is the
        // shape people already read as "off", and it does not depend on the
        // device's fonts carrying U+23FB.
        ImageView exit = new ImageView(this);
        exit.setImageResource(android.R.drawable.ic_lock_power_off);
        exit.setColorFilter(theme.danger);
        int exitPad = dp(compact ? 7 : 8);
        exit.setPadding(exitPad, exitPad, exitPad, exitPad);
        exit.setBackground(tapBackground(0x00000000, theme.hover, 10));
        exit.setContentDescription(getString(R.string.lx_exit_dex));
        exit.setOnClickListener(v -> toggleExitPopup());
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(square, square);
        exitLp.leftMargin = dp(2);
        tray.addView(exit, exitLp);
        return tray;
    }

    /** Bring the desktop surface above the app windows. */
    private void bringDesktopForward() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.moveTaskToFront(getTaskId(), 0);
        } catch (Exception e) {
            try {
                startActivity(new Intent(this, LauncherActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) {
            }
        }
    }

    private void goHome() {
        dismissPopups();
        hideDrawer();
        bringDesktopForward();
    }

    private void toggleDrawer() {
        dismissPopups();
        if (drawerShown) {
            hideDrawer();
        } else {
            showDrawer();
        }
    }

    /**
     * Show the app drawer as its own TYPE_APPLICATION_OVERLAY window so it
     * floats above every app window, exactly like the taskbar. Unlike the
     * taskbar this window must be FOCUSABLE — the search field has to receive
     * keystrokes — but it is NOT_TOUCH_MODAL so the taskbar underneath stays
     * clickable, and it is sized to stop short of the taskbar strip so the
     * two overlays never overlap. Falls back to an in-activity drawer (pulled
     * forward with moveTaskToFront) when the overlay is not permitted.
     */
    private void showDrawer() {
        if (drawerShown) return;
        int pad = dp(compact ? 14 : 48);
        if (android.provider.Settings.canDrawOverlays(this)) {
            Point size = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    // The overlay is laid out inside the safe area, so the
                    // height it may claim is the display less BOTH system bars,
                    // less the taskbar strip it must stop short of.
                    Math.max(dp(200), size.y - topSystemInset()
                            - bottomSystemInset() - dp(TASKBAR_DP)),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            // the drawer is the biggest translucent surface we own — blur is
            // what makes it read as glass rather than as a dimmed screenshot
            Glass.apply(this, lp, uiDensity);
            try {
                drawerPanel.setPadding(pad, drawerTopPad(), pad, dp(12));
                drawer.setVisibility(View.VISIBLE);
                getWindowManager().addView(drawer, lp);
                drawerOverlay = true;
                drawerShown = true;
            } catch (Exception e) {
                drawerOverlay = false;
            }
        }
        if (!drawerShown) {
            // no overlay: the drawer rides inside the activity, so the whole
            // desktop task has to come forward for it to be seen
            bringDesktopForward();
            // Inside the activity nothing insets us: this one pays for both
            // system bars as well as the taskbar strip.
            drawerPanel.setPadding(pad, topSystemInset() + drawerTopPad(), pad,
                    bottomSystemInset() + dp(TASKBAR_DP + 12));
            if (drawer.getParent() == null) {
                rootFrame.addView(drawer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            drawer.setVisibility(View.VISIBLE);
            drawerOverlay = false;
            drawerShown = true;
        }
        searchField.setText("");
        searchField.requestFocus();
    }

    private void hideDrawer() {
        // a drag in flight loses its window here — drop it
        if (dragApp != null) {
            dragApp = null;
            dragCell = null;
            dragLayer.setVisibility(View.GONE);
            dragLayer.begin(null);
        }
        if (drawerShown) {
            drawerShown = false;
            if (drawerOverlay) {
                try {
                    getWindowManager().removeViewImmediate(drawer);
                } catch (Exception ignored) {
                }
                drawerOverlay = false;
            }
            drawer.setVisibility(View.GONE);
        }
        // put back the chrome a drag hid, so the next open is a clean drawer
        if (drawerPanel != null) drawerPanel.setVisibility(View.VISIBLE);
    }

    private void dismissPopups() {
        if (recentsPopup != null && recentsPopup.isShowing()) recentsPopup.dismiss();
        if (calendarPopup != null && calendarPopup.isShowing()) calendarPopup.dismiss();
        if (batteryPopup != null && batteryPopup.isShowing()) batteryPopup.dismiss();
        if (qsPopup != null && qsPopup.isShowing()) qsPopup.dismiss();
        if (exitPopup != null && exitPopup.isShowing()) exitPopup.dismiss();
        if (widgetPicker != null && widgetPicker.isShowing()) widgetPicker.dismiss();
        recentsPopup = null;
        calendarPopup = null;
        batteryPopup = null;
        qsPopup = null;
        exitPopup = null;
        widgetPicker = null;
    }

    private PopupWindow makePopup(View content) {
        PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setBackgroundDrawable(roundedFill(theme.card(), 14));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        DexFonts.applyTo(this, content);
        // Every tray flyout is its own window; this one call covers all of them.
        DexCursors.decorate(content);
        return popup;
    }

    /**
     * Show a tray flyout above the taskbar. Blur is applied after the fact
     * because a PopupWindow only owns real WindowManager params once it is on
     * screen — see {@link Glass#apply(Context, PopupWindow, float)}.
     */
    private void showTrayPopup(PopupWindow popup, int horizontalGravity) {
        popup.showAtLocation(taskbarView, Gravity.BOTTOM | horizontalGravity,
                dp(8), dp(TASKBAR_DP + 8));
        Glass.apply(this, popup, uiDensity);
    }

    // ── Open-apps popup: what's running on this display, most recent
    // (topmost) first, each row with a kill button ──
    private void toggleRecentsPopup() {
        if (recentsPopup != null && recentsPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView header = new TextView(this);
        header.setText(getString(R.string.lx_open_apps));
        header.setTextColor(theme.textFaint);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        header.setPadding(dp(10), dp(4), dp(10), dp(6));
        panel.addView(header);

        final int[] shown = {0};
        for (String pkg : new ArrayList<>(runningPkgs)) {
            AppEntry entry = findByPackage(pkg);
            if (entry == null) continue;
            final AppEntry app = entry;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(5), dp(6), dp(5));
            row.setBackground(tapBackground(0x00000000, theme.hover, 9));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView label = new TextView(this);
            label.setText(app.label);
            label.setTextColor(theme.text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(10), 0, dp(12), 0);
            row.addView(label, new LinearLayout.LayoutParams(dp(210),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            // kill button: closes the app right here, row disappears
            TextView close = new TextView(this);
            close.setText("✕");
            close.setContentDescription(getString(R.string.lx_close_named, app.label));
            close.setTextColor(theme.textFaint);
            close.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
            close.setGravity(Gravity.CENTER);
            close.setBackground(tapBackground(0x00000000, 0xFFE81123, 12));
            row.addView(close, new LinearLayout.LayoutParams(dp(28), dp(28)));
            close.setOnClickListener(v -> {
                RequestProvider.enqueue("close", pkg);
                appClosedLocally(pkg);
                panel.removeView(row);
                if (--shown[0] <= 0) dismissPopups();
            });
            row.setOnClickListener(v -> {
                dismissPopups();
                launch(app); // relaunch = refocus
            });
            panel.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shown[0]++;
        }
        if (shown[0] == 0) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.lx_nothing_open));
            empty.setTextColor(theme.textFaint);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
            empty.setPadding(dp(10), dp(4), dp(10), dp(8));
            panel.addView(empty);
        }
        recentsPopup = makePopup(panel);
        showTrayPopup(recentsPopup, Gravity.START);
    }

    // ── Calendar popup: current month, today highlighted ──
    private void toggleCalendarPopup() {
        if (calendarPopup != null && calendarPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        calendarPopup = makePopup(buildCalendarView());
        showTrayPopup(calendarPopup, Gravity.END);
    }

    private View buildCalendarView() {
        Locale locale = Locale.getDefault();
        Calendar now = Calendar.getInstance();
        int today = now.get(Calendar.DAY_OF_MONTH);
        int cell = dp(32);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText(new SimpleDateFormat("MMMM yyyy", locale).format(now.getTime()));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), 0, 0, dp(8));
        panel.addView(title);

        LinearLayout week = new LinearLayout(this);
        week.setOrientation(LinearLayout.HORIZONTAL);
        for (String wd : new String[]{"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"}) {
            TextView head = new TextView(this);
            head.setText(wd);
            head.setTextColor(theme.textFaint);
            head.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
            head.setGravity(Gravity.CENTER);
            week.addView(head, new LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        panel.addView(week);

        Calendar first = (Calendar) now.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday-start
        int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);

        LinearLayout row = null;
        for (int slot = 0; slot < offset + days; slot++) {
            if (slot % 7 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(row);
            }
            TextView dayCell = new TextView(this);
            dayCell.setGravity(Gravity.CENTER);
            if (slot >= offset) {
                int day = slot - offset + 1;
                dayCell.setText(String.valueOf(day));
                dayCell.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
                if (day == today) {
                    dayCell.setTextColor(Color.WHITE);
                    dayCell.setTypeface(Typeface.DEFAULT_BOLD);
                    GradientDrawable dot = new GradientDrawable();
                    dot.setColor(theme.accent);
                    dot.setCornerRadius(cell / 2f);
                    dayCell.setBackground(dot);
                } else {
                    dayCell.setTextColor(theme.textDim);
                }
            }
            row.addView(dayCell, new LinearLayout.LayoutParams(cell, cell));
        }
        return panel;
    }

    // ── Tray: battery pill + info flyout ──

    /** Reflect the PC window's fullscreen state on the tray toggle glyph. */
    private void setPcFullscreen(boolean on) {
        pcFullscreen = on;
        if (fsButton != null) {
            fsButton.setText(on ? "❐" : "⛶");
            fsButton.setContentDescription(getString(
                    on ? R.string.lx_exit_fullscreen : R.string.lx_fullscreen));
        }
    }

    private int batteryPercent() {
        int level = lastBattery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = lastBattery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return scale > 0 && level >= 0 ? 100 * level / scale : 0;
    }

    private boolean isCharging() {
        int status = lastBattery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void updateBatteryPill() {
        if (batteryPill == null) return;
        if (lastBattery == null) {
            batteryPill.setText("🔋 —");
            return;
        }
        // Narrow tray: the battery glyph is the first thing to go. The number
        // is what the pill is for, and charging still shows as ⚡.
        batteryPill.setText(compact
                ? (isCharging() ? "⚡" : "") + batteryPercent() + "%"
                : (isCharging() ? "⚡ " : "🔋 ") + batteryPercent() + "%");
    }

    private void toggleBatteryPopup() {
        if (batteryPopup != null && batteryPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        batteryPopup = makePopup(buildBatteryView());
        showTrayPopup(batteryPopup, Gravity.END);
    }

    private void addInfoRow(LinearLayout panel, String emoji, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        icon.setPadding(0, 0, dp(10), 0);
        row.addView(icon);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(theme.textDim);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        row.addView(lab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(theme.text);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(val);
        panel.addView(row, new LinearLayout.LayoutParams(dp(250),
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View buildBatteryView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(12));
        int pct = lastBattery != null ? batteryPercent() : 0;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView pctView = new TextView(this);
        pctView.setText(pct + "%");
        pctView.setTextColor(theme.text);
        pctView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        pctView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(pctView);
        if (lastBattery != null && isCharging()) {
            TextView chip = new TextView(this);
            chip.setText("⚡ " + getString(R.string.lx_charging));
            chip.setTextColor(theme.positive);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
            chip.setBackground(roundedFill(0x2E22C55E, 9));
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.leftMargin = dp(10);
            head.addView(chip, chipLp);
        }
        panel.addView(head);

        // charge level bar — weights carve the green fill out of the track
        LinearLayout level = new LinearLayout(this);
        level.setOrientation(LinearLayout.HORIZONTAL);
        level.setBackground(roundedFill(theme.divider, 3));
        View fill = new View(this);
        fill.setBackground(roundedFill(0xFF22c55e, 3));
        level.addView(fill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(pct, 2)));
        level.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 100 - Math.max(pct, 2)));
        LinearLayout.LayoutParams levelLp = new LinearLayout.LayoutParams(dp(250), dp(6));
        levelLp.topMargin = dp(10);
        levelLp.bottomMargin = dp(12);
        panel.addView(level, levelLp);

        String plug = "—", temp = "—", volt = "—", health = "—", tech = "—";
        if (lastBattery != null) {
            switch (lastBattery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                case BatteryManager.BATTERY_PLUGGED_AC: plug = "AC"; break;
                case BatteryManager.BATTERY_PLUGGED_USB: plug = "USB"; break;
                case BatteryManager.BATTERY_PLUGGED_WIRELESS: plug = "Wireless"; break;
                case 8: plug = "Dock"; break; // BATTERY_PLUGGED_DOCK, API 33
                default: plug = "None";
            }
            int t = lastBattery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) temp = String.format(Locale.US, "%.1f °C", t / 10f);
            int v = lastBattery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (v > 0) volt = String.format(Locale.US, "%.2f V", v / 1000f);
            switch (lastBattery.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                case BatteryManager.BATTERY_HEALTH_GOOD: health = "GOOD"; break;
                case BatteryManager.BATTERY_HEALTH_OVERHEAT: health = "OVERHEAT"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD: health = "DEAD"; break;
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: health = "OVER VOLTAGE"; break;
                case BatteryManager.BATTERY_HEALTH_COLD: health = "COLD"; break;
                case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: health = "FAILURE"; break;
                default: health = "UNKNOWN";
            }
            String technology = lastBattery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (technology != null && !technology.isEmpty()) tech = technology;
        }
        String current = "—";
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (now != Integer.MIN_VALUE) {
                // reported in µA on most devices, already-mA on a few
                current = (Math.abs(now) >= 5000 ? now / 1000 : now) + " mA";
            }
        } catch (Exception ignored) {
        }

        addInfoRow(panel, "🔌", getString(R.string.lx_plug_type), plug);
        addInfoRow(panel, "🌡", getString(R.string.lx_temperature), temp);
        addInfoRow(panel, "⚡", getString(R.string.lx_voltage), volt);
        addInfoRow(panel, "🔁", getString(R.string.lx_current), current);
        addInfoRow(panel, "💚", getString(R.string.lx_health), health);
        addInfoRow(panel, "🔬", getString(R.string.lx_technology), tech);
        return panel;
    }

    // ── Tray: quick-settings flyout (DeX-style toggle tiles) ──

    /** Read a Settings.Global int as a boolean, permission-free. */
    private boolean globalSettingOn(String key) {
        try {
            return android.provider.Settings.Global.getInt(
                    getContentResolver(), key, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean locationOn() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                return lm.isLocationEnabled();
            }
            return android.provider.Settings.Secure.getInt(getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void setTorch(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    cm.setTorchMode(id, on);
                    return;
                }
            }
            Toast.makeText(this, getString(R.string.lx_no_flash), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.lx_torch_unavailable),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Framework icon by internal name(s) with a public-resource fallback —
     * quick-settings art (wifi/signal/flashlight) is not in android.R, but
     * loading it by name works on every device tried; tiles fall back to a
     * text glyph when neither resolves.
     */
    private Drawable sysIcon(String[] internalNames, int fallbackRes) {
        for (String name : internalNames) {
            try {
                int id = getResources().getIdentifier(name, "drawable", "android");
                if (id != 0) {
                    Drawable d = getResources().getDrawable(id, getTheme());
                    if (d != null) return d;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            if (fallbackRes != 0) return getResources().getDrawable(fallbackRes, getTheme());
        } catch (Exception ignored) {
        }
        return null;
    }

    private void styleQsCircle(View circle, View iconView, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? theme.accent : theme.field);
        circle.setBackground(bg);
        int tint = on ? 0xFFFFFFFF : theme.text;
        if (iconView instanceof ImageView) {
            ((ImageView) iconView).setColorFilter(tint);
        } else if (iconView instanceof TextView) {
            ((TextView) iconView).setTextColor(tint);
        }
    }

    /**
     * One quick-settings tile: circular icon (white fill when active) over a
     * label. Toggling restyles optimistically; real state is re-read the next
     * time the panel opens (svc commands land within a poll period). Tiles
     * with toggles=false (Lock) fire the action without flipping visuals.
     */
    private View qsTile(String label, Drawable icon, String glyph,
                        boolean initialOn, boolean toggles, Consumer<Boolean> action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(8), dp(4), dp(8));
        tile.setBackground(tapBackground(0x00000000, theme.hover, 14));

        FrameLayout circle = new FrameLayout(this);
        View iconView;
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            int p = dp(11);
            iv.setPadding(p, p, p, p);
            iconView = iv;
        } else {
            TextView tv = new TextView(this);
            tv.setText(glyph);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
            tv.setGravity(Gravity.CENTER);
            iconView = tv;
        }
        circle.addView(iconView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(circle, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(theme.textDim);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        lab.setGravity(Gravity.CENTER_HORIZONTAL);
        lab.setSingleLine(true);
        lab.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(dp(84),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labLp.topMargin = dp(6);
        tile.addView(lab, labLp);

        final boolean[] on = {initialOn};
        styleQsCircle(circle, iconView, on[0]);
        tile.setOnClickListener(v -> {
            boolean next = !on[0];
            action.accept(next);
            if (toggles) {
                on[0] = next;
                styleQsCircle(circle, iconView, next);
            }
        });
        return tile;
    }

    private void toggleQuickSettingsPopup() {
        if (qsPopup != null && qsPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        qsPopup = makePopup(buildQuickSettingsView());
        showTrayPopup(qsPopup, Gravity.END);
    }

    private View buildQuickSettingsView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(8));

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean muted = false;
        try {
            muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (Exception ignored) {
        }

        List<View> tiles = new ArrayList<>();
        tiles.add(qsTile(getString(R.string.lx_wifi),
                sysIcon(new String[]{"ic_wifi_signal_4", "ic_qs_wifi_full_4",
                        "stat_sys_wifi_signal_4"}, 0), "📶",
                globalSettingOn("wifi_on"), true,
                on -> RequestProvider.enqueue("qs", on ? "wifi.on" : "wifi.off")));
        tiles.add(qsTile(getString(R.string.lx_bluetooth),
                sysIcon(new String[0], android.R.drawable.stat_sys_data_bluetooth), "ᛒ",
                globalSettingOn("bluetooth_on"), true,
                on -> RequestProvider.enqueue("qs", on ? "bt.on" : "bt.off")));
        tiles.add(qsTile(getString(R.string.lx_mobile_data),
                sysIcon(new String[]{"ic_signal_cellular_4_4_bar", "ic_qs_signal_full_4",
                        "stat_sys_signal_4"}, 0), "⇅",
                globalSettingOn("mobile_data") || globalSettingOn("mobile_data0"), true,
                on -> RequestProvider.enqueue("qs", on ? "data.on" : "data.off")));
        tiles.add(qsTile(getString(R.string.lx_airplane),
                sysIcon(new String[]{"ic_qs_airplane", "ic_airplanemode_active",
                        "stat_sys_airplane_mode"}, 0), "✈",
                globalSettingOn(android.provider.Settings.Global.AIRPLANE_MODE_ON), true,
                on -> RequestProvider.enqueue("qs", on ? "airplane.on" : "airplane.off")));
        tiles.add(qsTile(getString(R.string.lx_mute),
                sysIcon(new String[0], android.R.drawable.ic_lock_silent_mode), "🔇",
                muted, true,
                on -> {
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.lx_cannot_volume),
                                Toast.LENGTH_SHORT).show();
                    }
                }));
        tiles.add(qsTile(getString(R.string.lx_rotate),
                sysIcon(new String[0], android.R.drawable.ic_menu_rotate), "⟳",
                android.provider.Settings.System.getInt(getContentResolver(),
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) != 0, true,
                on -> RequestProvider.enqueue("qs", on ? "rotate.on" : "rotate.off")));
        tiles.add(qsTile(getString(R.string.lx_location),
                sysIcon(new String[0], android.R.drawable.ic_menu_mylocation), "◎",
                locationOn(), true,
                on -> RequestProvider.enqueue("qs", on ? "location.on" : "location.off")));
        tiles.add(qsTile(getString(R.string.lx_torch),
                sysIcon(new String[]{"ic_qs_flashlight", "ic_signal_flashlight"}, 0), "🔦",
                torchOn, true,
                this::setTorch));
        tiles.add(qsTile(getString(R.string.lx_lock),
                sysIcon(new String[0], android.R.drawable.ic_lock_idle_lock), "🔒",
                false, false,
                on -> {
                    RequestProvider.enqueue("qs", "lock.on");
                    dismissPopups();
                }));

        // Every child gets an explicit width: a MATCH_PARENT plain View in a
        // wrap-content panel measures to the whole available width and used
        // to blow the popup up ~120dp wider than the tile grid.
        int panelWidth = dp(92) * 3;
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(row, new LinearLayout.LayoutParams(panelWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(tiles.get(i), new LinearLayout.LayoutParams(dp(92),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        View divider = new View(this);
        divider.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(panelWidth, dp(1));
        divLp.topMargin = dp(8);
        divLp.bottomMargin = dp(2);
        panel.addView(divider, divLp);

        LinearLayout allRow = new LinearLayout(this);
        allRow.setOrientation(LinearLayout.HORIZONTAL);
        allRow.setGravity(Gravity.CENTER_VERTICAL);
        allRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        allRow.setBackground(tapBackground(0x00000000, theme.hover, 10));
        allRow.setOnClickListener(v -> launchSettings());
        ImageView gear = new ImageView(this);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setColorFilter(theme.textDim);
        allRow.addView(gear, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView allLabel = new TextView(this);
        allLabel.setText(getString(R.string.lx_all_settings));
        allLabel.setTextColor(theme.text);
        allLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        allLabel.setPadding(dp(10), 0, 0, 0);
        allRow.addView(allLabel);
        panel.addView(allRow, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView foot = new TextView(this);
        foot.setText(getString(R.string.lx_qs_footer));
        foot.setTextColor(theme.textFaint);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10));
        foot.setPadding(dp(10), dp(2), dp(10), dp(4));
        panel.addView(foot, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    // ── Tray: exit DeX ──

    private void toggleExitPopup() {
        if (exitPopup != null && exitPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        hideDrawer();
        exitPopup = makePopup(buildExitView());
        showTrayPopup(exitPopup, Gravity.END);
    }

    /**
     * Confirm flyout for leaving DeX. A tray popup rather than a dialog on
     * purpose: it inherits the taskbar's overlay window, so it is visible over
     * app windows — an Activity dialog would open behind whatever is
     * maximized, which is exactly when someone reaches for this button.
     */
    private View buildExitView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText(getString(R.string.lx_exit_title));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView body = new TextView(this);
        body.setText(getString(R.string.lx_exit_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        body.setPadding(0, dp(6), 0, dp(12));
        panel.addView(body, new LinearLayout.LayoutParams(dp(270),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextView cancel = new TextView(this);
        cancel.setText(getString(R.string.lx_cancel));
        cancel.setTextColor(theme.textDim);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(16), dp(8), dp(16), dp(8));
        cancel.setBackground(tapBackground(0x00000000, theme.hover, 10));
        cancel.setOnClickListener(v -> dismissPopups());
        buttons.addView(cancel);

        TextView confirm = new TextView(this);
        confirm.setText(getString(R.string.lx_exit_dex));
        confirm.setTextColor(0xFFFFFFFF);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        confirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(dp(18), dp(8), dp(18), dp(8));
        confirm.setBackground(tapBackground(theme.danger, lighten(theme.danger), 10));
        confirm.setOnClickListener(v -> requestExit());
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmLp.leftMargin = dp(8);
        buttons.addView(confirm, confirmLp);

        panel.addView(buttons, new LinearLayout.LayoutParams(dp(270),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /**
     * Ask the PC to end the session and put the phone back the way it was.
     *
     * It has to be the PC: the display belongs to scrcpy on the host, and so
     * does everything the desktop profile switched on here (freeform support,
     * the hidden-API policy, the caption service, the window daemon). All this
     * side can do is raise the request — which the queue logs, so an exit that
     * is never drained reads as a gap in the trace instead of a dead button.
     */
    private void requestExit() {
        dismissPopups();
        hideDrawer();
        DexLog.step("exit", "exit requested from the taskbar");
        RequestProvider.enqueue("exit", "dex");
        Toast.makeText(this, getString(R.string.lx_exiting), Toast.LENGTH_LONG).show();
    }

    // ── Open-apps row (center of the taskbar) ──
    AppEntry findByPackage(String pkg) {
        for (AppEntry a : allApps) {
            if (a.component.getPackageName().equals(pkg)) return a;
        }
        return null;
    }

    /**
     * Resolve a stored desktop shortcut. An app update can rename its launcher
     * activity, which would orphan the icon — fall back to the package's
     * current entry point, and the next save rewrites the record.
     */
    AppEntry findByComponent(ComponentName component) {
        for (AppEntry a : allApps) {
            if (a.component.equals(component)) return a;
        }
        return findByPackage(component.getPackageName());
    }

    /** Drop an app we just closed ourselves — no need to wait for the poll. */
    void appClosedLocally(String pkg) {
        if (runningPkgs.remove(pkg)) refreshOpenApps();
    }

    private void refreshOpenApps() {
        if (openAppsRow == null) return;
        openAppsRow.removeAllViews();
        // Our own windows (Settings, Linux) never resolve to an AppEntry —
        // loadApps() skips our package — so the loop below drops them. Add them
        // by hand, or an open Linux window would have no taskbar presence at
        // all and a minimised one no way back.
        addOwnWindowTiles();
        for (String pkg : runningPkgs) {
            AppEntry entry = findByPackage(pkg);
            if (entry == null) continue;
            final AppEntry app = entry;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(compact ? 3 : 5), dp(4), dp(compact ? 3 : 5), dp(3));
            item.setBackground(tapBackground(0x00000000, theme.hover, 10));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setContentDescription(app.label);
            item.addView(icon, new LinearLayout.LayoutParams(
                    dp(compact ? 27 : 34), dp(compact ? 27 : 34)));

            // running indicator
            View dot = new View(this);
            dot.setBackground(roundedFill(theme.accent, 2));
            LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(dp(compact ? 10 : 12), dp(3));
            dotLp.topMargin = dp(3);
            item.addView(dot, dotLp);

            item.setOnClickListener(v -> launch(app)); // relaunch = refocus
            // right-click (forwarded by scrcpy) or long-press → app menu
            item.setOnContextClickListener(v -> {
                showTaskbarAppMenu(v, app);
                return true;
            });
            item.setOnLongClickListener(v -> {
                showTaskbarAppMenu(v, app);
                return true;
            });
            // Built on every app launch, long after the taskbar's tree pass —
            // so, like the drawer's cells, this one asks for its own hand.
            DexCursors.apply(item, DexCursors.ROLE_HAND);
            openAppsRow.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * Taskbar tiles for our own windows (Settings, Linux). They carry no
     * AppEntry, so they get an emoji glyph instead of an icon.
     *
     * The set comes from {@link OwnWindows} — the activities themselves, same
     * process — and NOT from the minimised broadcast, which is what it used to
     * be. Two things were wrong with that: an open-but-not-minimised window had
     * no tile at all (so the Linux session was invisible in the taskbar until
     * you hid it), and the one state that DID show a tile was the one that
     * depended on a caption, a hide transition and a broadcast all landing.
     * CaptionService's set is still consulted, but only to decide what a click
     * means: a hidden window has to be un-hidden through the service, since
     * `am start` does not clear the hidden flag.
     */
    private void addOwnWindowTiles() {
        LinkedHashSet<String> windows = new LinkedHashSet<>(OwnWindows.list());
        // Belt: a window minimised by the service but somehow not registered
        // still deserves its way back.
        windows.addAll(minimisedActivities);
        for (String activity : windows) {
            final String label;
            final String glyph;
            if (LinuxActivity.class.getName().equals(activity)) {
                label = getString(R.string.ln_label);
                glyph = "🐧";
            } else if (SettingsActivity.class.getName().equals(activity)) {
                label = getString(R.string.settings_label);
                glyph = "⚙"; // gear
            } else if (DockerActivity.class.getName().equals(activity)) {
                label = getString(R.string.dk_label);
                glyph = "🐳";
            } else if (TaskManagerActivity.class.getName().equals(activity)) {
                label = getString(R.string.tm_title);
                glyph = "📊";
            } else {
                continue;
            }

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(compact ? 3 : 5), dp(4), dp(compact ? 3 : 5), dp(3));
            item.setBackground(tapBackground(0x00000000, theme.hover, 10));

            TextView icon = new TextView(this);
            icon.setText(glyph);
            icon.setGravity(Gravity.CENTER);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(compact ? 18 : 22));
            icon.setContentDescription(label);
            item.addView(icon, new LinearLayout.LayoutParams(
                    dp(compact ? 27 : 34), dp(compact ? 27 : 34)));

            View dot = new View(this);
            dot.setBackground(roundedFill(theme.accent, 2));
            LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(dp(compact ? 10 : 12), dp(3));
            dotLp.topMargin = dp(3);
            item.addView(dot, dotLp);

            // Every launcher already starts with the "is it minimised?" check,
            // so one click handler covers restore and raise.
            final String target = activity;
            item.setOnClickListener(v -> {
                if (LinuxActivity.class.getName().equals(target)) {
                    launchLinux();
                } else if (DockerActivity.class.getName().equals(target)) {
                    launchDocker();
                } else if (TaskManagerActivity.class.getName().equals(target)) {
                    launchTaskManager();
                } else {
                    launchSettings();
                }
            });
            // Built on every app launch, long after the taskbar's tree pass —
            // so, like the drawer's cells, this one asks for its own hand.
            DexCursors.apply(item, DexCursors.ROLE_HAND);
            openAppsRow.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showTaskbarAppMenu(View anchor, AppEntry app) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, getString(R.string.lx_maximize_restore));
        menu.getMenu().add(0, 2, 1, getString(R.string.lx_close_app));
        menu.getMenu().add(0, 3, 2, getString(R.string.lx_app_info));
        menu.setOnMenuItemClickListener(item -> {
            String pkg = app.component.getPackageName();
            if (item.getItemId() == 1) {
                // The window caption's ⤢ button only ever maximizes — One UI
                // never turns it into a restore button on this display — so
                // this is the way back to a windowed size. The PC side knows
                // which state the window is in and flips it.
                RequestProvider.enqueue("window", pkg);
            } else if (item.getItemId() == 2) {
                // executed by the PC side (adb force-stop) via the queue;
                // drop the icon immediately — the running broadcast would
                // only remove it a poll later
                RequestProvider.enqueue("close", pkg);
                appClosedLocally(pkg);
                Toast.makeText(this, getString(R.string.lx_closing, app.label),
                        Toast.LENGTH_SHORT).show();
            } else {
                openAppInfo(app);
            }
            return true;
        });
        menu.show();
    }

    private void openAppInfo(AppEntry app) {
        Intent intent = new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + app.component.getPackageName()))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        ActivityOptions opts = shapeForDesktop(ActivityOptions.makeBasic(),
                new Rect(size.x / 4, size.y / 8, size.x * 3 / 4, size.y * 7 / 8));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.lx_cannot_open_app_info),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ── Desktop widgets: picker + the bind/configure detours ──

    /**
     * The "Add widget" panel: every provider on the phone, grouped by app,
     * with the provider's own preview image where it ships one. Opened from
     * the desktop's right-click menu; the cell that was clicked is where the
     * widget will try to land.
     */
    void showWidgetPicker(int prefCol, int prefRow) {
        dismissPopups();
        hideDrawer();
        // the picker lives in the activity window — make sure it is in front
        bringDesktopForward();

        List<AppWidgetProviderInfo> providers = new ArrayList<>();
        try {
            providers.addAll(widgetManager.getInstalledProviders());
        } catch (Exception e) {
            DexLog.warn("widgets", "cannot list widget providers", e);
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(12));

        TextView title = new TextView(this);
        title.setText(getString(R.string.lx_widgets));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), 0, 0, dp(6));
        panel.addView(title);

        if (providers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.lx_no_widgets));
            empty.setTextColor(theme.textFaint);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
            empty.setPadding(dp(4), dp(8), dp(4), dp(10));
            panel.addView(empty);
        } else {
            panel.addView(buildWidgetList(providers, prefCol, prefRow),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int w = Math.min(dp(460), size.x * 4 / 5);
        int h = providers.isEmpty()
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : Math.min(dp(560), size.y * 3 / 4);
        widgetPicker = new PopupWindow(panel, w, h);
        widgetPicker.setBackgroundDrawable(roundedFill(theme.card(), 16));
        widgetPicker.setOutsideTouchable(true);
        widgetPicker.setFocusable(true);    // Esc closes it, like a dialog
        widgetPicker.setElevation(dp(16));
        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);   // built inline, so makePopup misses it
        widgetPicker.showAtLocation(rootFrame, Gravity.CENTER, 0, 0);
        Glass.apply(this, widgetPicker, uiDensity);
    }

    /** One app-group after another, each provider a row with preview + size. */
    private View buildWidgetList(List<AppWidgetProviderInfo> providers,
                                 int prefCol, int prefRow) {
        PackageManager pm = getPackageManager();

        // group by app, sorted by app label — the order people scan a list in
        List<String> pkgOrder = new ArrayList<>();
        java.util.Map<String, List<AppWidgetProviderInfo>> byPkg = new java.util.HashMap<>();
        java.util.Map<String, CharSequence> labels = new java.util.HashMap<>();
        java.util.Map<String, Drawable> icons = new java.util.HashMap<>();
        for (AppWidgetProviderInfo info : providers) {
            String pkg = info.provider.getPackageName();
            if (!byPkg.containsKey(pkg)) {
                byPkg.put(pkg, new ArrayList<>());
                pkgOrder.add(pkg);
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    labels.put(pkg, ai.loadLabel(pm));
                    icons.put(pkg, ai.loadIcon(pm));
                } catch (Exception e) {
                    labels.put(pkg, pkg);
                }
            }
            byPkg.get(pkg).add(info);
        }
        pkgOrder.sort((a, b) -> labels.get(a).toString()
                .compareToIgnoreCase(labels.get(b).toString()));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String pkg : pkgOrder) {
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(4), dp(10), dp(4), dp(4));
            Drawable appIcon = icons.get(pkg);
            if (appIcon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(appIcon);
                header.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
            }
            TextView appName = new TextView(this);
            appName.setText(labels.get(pkg));
            appName.setTextColor(theme.textFaint);
            appName.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
            appName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            appName.setSingleLine(true);
            appName.setEllipsize(TextUtils.TruncateAt.END);
            appName.setPadding(dp(8), 0, 0, 0);
            header.addView(appName);
            list.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (AppWidgetProviderInfo info : byPkg.get(pkg)) {
                list.addView(widgetPickerRow(info, icons.get(pkg), prefCol, prefRow),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View widgetPickerRow(AppWidgetProviderInfo info, Drawable appIcon,
                                 int prefCol, int prefRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(tapBackground(0x00000000, theme.hover, 12));

        Drawable preview = null;
        try {
            preview = info.loadPreviewImage(this, uiDensity);
        } catch (Exception ignored) {
        }
        ImageView pv = new ImageView(this);
        pv.setImageDrawable(preview != null ? preview : appIcon);
        pv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(dp(96), dp(60));
        row.addView(pv, pvLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        CharSequence label = null;
        try {
            label = info.loadLabel(getPackageManager());
        } catch (Exception ignored) {
        }
        name.setText(label != null ? label : info.provider.getShortClassName());
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        TextView cells = new TextView(this);
        int[] span = desktopGrid.spanFor(info);
        cells.setText(span[0] + " × " + span[1]);
        cells.setTextColor(theme.textFaint);
        cells.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        text.addView(cells);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            dismissPopups();
            addWidgetFlow(info, prefCol, prefRow);
        });
        return row;
    }

    /**
     * Allocate an id and walk it through bind → configure → place. Straight
     * through when the PC granted us bind (`cmd appwidget grantbind`);
     * otherwise via the system's confirmation dialog, opened as a desktop
     * window like everything else here — but from a task of its own, because
     * launch bounds belong to the task the started activity lands in (see
     * {@link WidgetDetourActivity}).
     */
    private void addWidgetFlow(AppWidgetProviderInfo info, int col, int row) {
        // One add at a time. finishActivity cannot reach the detour any more:
        // it matches on the result link, and the detour now runs from a task
        // this one did not start it for a result in. So it is closed by name,
        // unconditionally — an add that binds silently still has to clear a
        // dialog a previous add left standing. Releasing the previous id first
        // is what makes that detour's parting report harmless: it can no
        // longer match pendingWidgetId.
        WidgetDetourActivity.dismiss();
        abandonPendingWidget();     // a superseded add must not leak its id
        int id;
        try {
            id = widgetHost.allocateAppWidgetId();
        } catch (Exception e) {
            DexLog.warn("widgets", "cannot allocate a widget id", e);
            Toast.makeText(this, getString(R.string.lx_widget_failed),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingWidgetId = id;
        pendingWidgetInfo = info;
        pendingWidgetCol = col;
        pendingWidgetRow = row;
        // survives the process dying mid-detour — reconciled by onCreate
        DexPrefs.prefs(this).edit().putInt(KEY_PENDING_WIDGET, id).apply();
        boolean bound = false;
        try {
            bound = widgetManager.bindAppWidgetIdIfAllowed(
                    id, info.getProfile(), info.provider, null);
        } catch (Exception e) {
            DexLog.warn("widgets", "bindAppWidgetIdIfAllowed threw for "
                    + info.provider.flattenToShortString(), e);
        }
        if (bound) {
            if (DexWidgetHost.needsConfigure(info)) {
                startDetour(WidgetDetourActivity.STAGE_CONFIGURE, id, info);
            } else {
                placePendingWidget();
            }
            return;
        }
        startDetour(WidgetDetourActivity.STAGE_BIND, id, info);
    }

    /**
     * Hand a system detour to a task of its own. Both rects ride along: the
     * stage's rect sizes the detour's task, and the other is there so it can
     * size a second stage without needing the desktop's density.
     */
    private void startDetour(int stage, int id, AppWidgetProviderInfo info) {
        Rect bind = desktopWindowRect(dp(520), dp(560));
        Rect configure = desktopWindowRect(dp(640), dp(680));
        Intent detour = new Intent(this, WidgetDetourActivity.class)
                // A task of its own — the whole point. NOT MULTIPLE_TASK: one
                // detour task ever is what keeps a stray one findable and
                // closable.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WidgetDetourActivity.EXTRA_STAGE, stage)
                .putExtra(WidgetDetourActivity.EXTRA_DISPLAY, getDisplay().getDisplayId())
                .putExtra(WidgetDetourActivity.EXTRA_BOUNDS_BIND, bind)
                .putExtra(WidgetDetourActivity.EXTRA_BOUNDS_CONFIGURE, configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            startActivity(detour, desktopWindowOptions(
                    stage == WidgetDetourActivity.STAGE_BIND ? bind : configure));
        } catch (Exception e) {
            DexLog.warn("widgets", "widget detour could not open", e);
            abandonPendingWidget();
            Toast.makeText(this, getString(R.string.lx_widget_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void placePendingWidget() {
        int id = pendingWidgetId;
        AppWidgetProviderInfo info = pendingWidgetInfo;
        int col = pendingWidgetCol, row = pendingWidgetRow;
        pendingWidgetId = -1;
        pendingWidgetInfo = null;
        if (id < 0 || info == null || desktopGrid == null) return;
        if (desktopGrid.addWidget(id, info, col, row)) {
            DexLog.step("widgets", "placed " + info.provider.flattenToShortString()
                    + " as widget " + id);
        } else {
            try {
                widgetHost.deleteAppWidgetId(id);
            } catch (Exception ignored) {
            }
            Toast.makeText(this, getString(R.string.lx_no_room), Toast.LENGTH_SHORT).show();
        }
        // cleared AFTER the record exists: dying between the two leaves the id
        // both recorded and marked pending, which releaseOrphanedAdd forgives —
        // the reverse order would leak it
        DexPrefs.prefs(this).edit().remove(KEY_PENDING_WIDGET).apply();
    }

    private void abandonPendingWidget() {
        if (pendingWidgetId >= 0) {
            try {
                widgetHost.deleteAppWidgetId(pendingWidgetId);
            } catch (Exception ignored) {
            }
            DexPrefs.prefs(this).edit().remove(KEY_PENDING_WIDGET).apply();
        }
        pendingWidgetId = -1;
        pendingWidgetInfo = null;
    }

    /**
     * Release the id a previous life of this activity allocated and then died
     * on, mid bind/configure. Skipped when the id made it into the desktop
     * records — that means the add actually completed and only the marker
     * survived the crash window between saving and clearing.
     */
    private void releaseOrphanedAdd() {
        int orphan = DexPrefs.prefs(this).getInt(KEY_PENDING_WIDGET, -1);
        if (orphan < 0) return;
        if (!isRecordedWidget(orphan)) {
            try {
                widgetHost.deleteAppWidgetId(orphan);
            } catch (Exception ignored) {
            }
            DexLog.step("widgets", "released widget id " + orphan
                    + " orphaned by a death mid-add");
        }
        DexPrefs.prefs(this).edit().remove(KEY_PENDING_WIDGET).apply();
    }

    /** Is this id on the desktop? Then the add completed and only a marker survived. */
    private boolean isRecordedWidget(int id) {
        for (String record : DexPrefs.prefs(this)
                .getString(DesktopGrid.KEY_WIDGETS, "").split("\\|")) {
            if (record.startsWith(id + ":")) return true;
        }
        return false;
    }

    /**
     * First-launch default: a clock widget in the top-right corner, the same
     * first sight the commercial DeX desktop gives. Called by the grid once
     * its geometry exists (and again after every shell rebuild — the persisted
     * flag makes those calls no-ops). Entirely silent: only providers that can
     * bind without a dialog and need no configure screen qualify, so a phone
     * where seeding cannot happen quietly simply starts with an empty desktop.
     */
    void maybeSeedClockWidget(DesktopGrid from) {
        // A post can outlive its world: a shell rebuild swaps the grid out
        // under it (the replacement seeds on its own once measured), and
        // recreate() destroys this instance with isFinishing() still false —
        // seeding a detached tree would save a record the live instance never
        // loaded. `measured()` and not columns(): cols starts at 1, so it
        // cannot tell a real grid from one that has never seen onMeasure.
        if (from != desktopGrid || !from.measured() || isFinishing() || isDestroyed()) return;
        if (DexPrefs.prefs(this).getBoolean(KEY_CLOCK_SEEDED, false)) return;
        // A manual add owns KEY_PENDING_WIDGET while its detour is open, and
        // that marker is the only thing that can release its id if the process
        // dies mid-dialog. Seeding borrows the same key, so it waits — a reset
        // that re-arms seeding while a dialog is up would otherwise erase the
        // add's death insurance. The next shell rebuild posts this again.
        if (pendingWidgetId >= 0) return;
        AppWidgetProviderInfo clock = findClockProvider();
        if (clock == null) return;
        int id;
        try {
            id = widgetHost.allocateAppWidgetId();
        } catch (Exception e) {
            return;
        }
        // same death-mid-add insurance as the manual flow: until the record
        // exists, this marker is the only thing that can release the id
        DexPrefs.prefs(this).edit().putInt(KEY_PENDING_WIDGET, id).apply();
        boolean bound = false;
        try {
            bound = widgetManager.bindAppWidgetIdIfAllowed(
                    id, clock.getProfile(), clock.provider, null);
        } catch (Exception ignored) {
        }
        // top-right: addWidget clamps the preferred column by the span itself
        if (bound && desktopGrid.addWidget(id, clock, desktopGrid.columns() - 1, 0)) {
            DexPrefs.prefs(this).edit().putBoolean(KEY_CLOCK_SEEDED, true).apply();
            DexLog.step("widgets", "seeded default clock "
                    + clock.provider.flattenToShortString() + " top-right");
        } else {
            try {
                widgetHost.deleteAppWidgetId(id);
            } catch (Exception ignored) {
            }
        }
        DexPrefs.prefs(this).edit().remove(KEY_PENDING_WIDGET).apply();
    }

    /**
     * The clock widget this phone most plausibly means by "the clock": prefer
     * the actual Clock app (Samsung's clockpackage, AOSP/Google deskclock)
     * over anything else with "clock" in its name, and an analog face — the
     * look the commercial DeX desktop seeds — over a digital one.
     */
    private AppWidgetProviderInfo findClockProvider() {
        List<AppWidgetProviderInfo> providers;
        try {
            providers = widgetManager.getInstalledProviders();
        } catch (Exception e) {
            return null;
        }
        AppWidgetProviderInfo best = null;
        int bestScore = 0;
        for (AppWidgetProviderInfo info : providers) {
            String pkg = info.provider.getPackageName().toLowerCase(Locale.ROOT);
            String cls = info.provider.getClassName().toLowerCase(Locale.ROOT);
            if (!pkg.contains("clock") && !cls.contains("clock")) continue;
            // seeding is silent — no setup screens
            if (DexWidgetHost.needsConfigure(info)) continue;
            int score = 1;
            if (pkg.contains("clockpackage") || pkg.contains("deskclock")) score += 4;
            if (cls.contains("analog")) score += 2;
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }
        return best;
    }

    /** Centered rect for a desktop window of this size, clamped to the display. */
    private Rect desktopWindowRect(int wPx, int hPx) {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int w = Math.min(wPx, size.x * 9 / 10);
        int h = Math.min(hPx, size.y * 9 / 10);
        int x = (size.x - w) / 2;
        int y = (size.y - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    /**
     * Freeform launch options for a rect on this display. NEW-TASK LAUNCHES
     * ONLY: bounds and windowing mode are applied to the TASK the activity
     * resolves into, so handing these to a start that joins our own task
     * resizes the desktop itself.
     */
    Bundle desktopWindowOptions(Rect bounds) {
        return shapeForDesktop(ActivityOptions.makeBasic(), bounds).toBundle();
    }

    /**
     * Stamp "this belongs on the desktop, freeform, at this rect" onto options.
     *
     * MUTATES rather than replaces, because one caller ({@link WidgetLaunch})
     * hands us the PLATFORM'S own options for a click inside a widget — they
     * carry the provider's transition, its shared elements and the
     * background-start mode that lets the send through at all, and rebuilding
     * would drop every one of them.
     *
     * setLaunchWindowingMode is hidden API, reachable only because the PC sets
     * hidden_api_policy=1: bounds alone are not honored on a decoration-free
     * display.
     */
    @Override
    public ActivityOptions shapeForDesktop(ActivityOptions opts, Rect bounds, int displayId) {
        // -1 is a real value to the framework; leaving the field unset is what
        // lets a click from an already-detached view inherit ours instead.
        if (displayId >= 0) opts.setLaunchDisplayId(displayId);
        if (bounds != null) opts.setLaunchBounds(bounds);
        try {
            ActivityOptions.class
                    .getMethod("setLaunchWindowingMode", int.class)
                    .invoke(opts, 5 /* WINDOWING_MODE_FREEFORM */);
        } catch (Exception ignored) {
        }
        return opts;
    }

    private ActivityOptions shapeForDesktop(ActivityOptions opts, Rect bounds) {
        return shapeForDesktop(opts, bounds, displayId());
    }

    /** Never -1 in practice; a caller holding a detached view gets "leave it unset". */
    @Override
    public int displayId() {
        Display display = getDisplay();
        return display != null ? display.getDisplayId() : -1;
    }

    @Override
    public Context desktopContext() {
        return this;
    }

    /**
     * Open the app behind a widget that has not drawn yet — the placeholder
     * tap the platform would otherwise start with no options at all.
     *
     * Resolved through LauncherApps rather than rebuilt as an ACTION_MAIN
     * intent, because that is what keeps a work-profile or Dual-App widget
     * opening the right user's copy.
     */
    void launchWidgetApp(AppWidgetProviderInfo info, View tapped) {
        if (info == null || tapped == null) return;
        try {
            LauncherApps apps = getSystemService(LauncherApps.class);
            List<LauncherActivityInfo> activities = apps.getActivityList(
                    info.provider.getPackageName(), info.getProfile());
            if (activities.isEmpty()) return;
            LauncherActivityInfo target = activities.get(0);
            // Where the tap came from — some apps animate out of it.
            int[] at = new int[2];
            tapped.getLocationOnScreen(at);
            Rect from = new Rect(at[0], at[1],
                    at[0] + tapped.getWidth(), at[1] + tapped.getHeight());
            apps.startMainActivity(target.getComponentName(), target.getUser(), from,
                    desktopWindowOptions(nextWindowBounds()));
        } catch (Exception e) {
            DexLog.warn("widgets", "cannot open the app behind "
                    + info.provider.flattenToShortString(), e);
        }
    }

    private void updateClock() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        if (clockView != null) {
            clockView.setText(new SimpleDateFormat("HH:mm", locale).format(now));
        }
        if (dateView != null) {
            dateView.setText(new SimpleDateFormat("EEE, d MMM", locale).format(now));
        }
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        allApps.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null) continue;
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            allApps.add(new AppEntry(
                    ri.loadLabel(pm),
                    ri.loadIcon(pm),
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
        }
        allApps.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        filter("");
        refreshOpenApps();
        // desktop shortcuts are stored as components and resolved against this
        // list, so they can only be built once it exists
        if (desktopGrid != null) desktopGrid.reload();
    }

    private void filter(String query) {
        shownApps.clear();
        String q = query.trim().toLowerCase(Locale.getDefault());
        for (AppEntry app : allApps) {
            if (q.isEmpty()
                    || app.label.toString().toLowerCase(Locale.getDefault()).contains(q)
                    || app.component.getPackageName().toLowerCase(Locale.getDefault()).contains(q)) {
                shownApps.add(app);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadRecents() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_RECENTS, "");
        for (String flat : stored.split("\\|")) {
            if (!flat.isEmpty()) recents.add(flat);
        }
    }

    private void saveRecents() {
        StringBuilder sb = new StringBuilder();
        for (String flat : recents) {
            if (sb.length() > 0) sb.append('|');
            sb.append(flat);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENTS, sb.toString()).apply();
    }

    private void noteRecent(ComponentName cn) {
        String flat = cn.flattenToString();
        recents.remove(flat);
        List<String> ordered = new ArrayList<>(recents);
        recents.clear();
        recents.add(flat);
        for (String s : ordered) {
            if (recents.size() >= MAX_RECENTS) break;
            recents.add(s);
        }
        saveRecents();
    }

    /** Launch windowed from the first frame; relaunching refocuses the task. */
    void launch(AppEntry app) {
        hideDrawer();
        dismissPopups();
        // A minimised app is already running; it just needs un-hiding. `am start` does
        // NOT clear the hidden flag — measured: the task stays visible=false and the
        // click appears to do nothing — so ask CaptionService, which owns the window
        // connection, to restore it instead of launching a second time.
        final String pkg = app.component.getPackageName();
        if (minimisedPkgs.contains(pkg)) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", pkg));
            // Only CaptionService knows which task that package's minimised window is, and
            // it is restarted on every reconnect. If the restore is not acknowledged — the
            // pin disappears once the window is back — assume nobody is listening and start
            // the app normally, so the icon is never a dead end.
            handler.postDelayed(() -> {
                if (!minimisedPkgs.contains(pkg)) return;
                minimisedPkgs.remove(pkg);
                startWindowed(app);
            }, RESTORE_FALLBACK_MS);
            return;
        }
        startWindowed(app);
    }

    /**
     * How long a restore gets before the click falls back to a plain launch.
     *
     * Must clear a WORKING restore comfortably — the un-hide is a transition, and the pin
     * is only dropped by the poll that sees it committed. Firing early would re-launch a
     * window that is already on its way back, and a launch carries bounds, so it would
     * resize it too.
     */
    private static final long RESTORE_FALLBACK_MS = 2_000L;

    /**
     * The rect the next desktop window gets, from the "App launch mode" and
     * "Default window size" settings.
     *
     * One copy, because two paths reach it: the drawer/taskbar (startWindowed)
     * and a click inside a hosted widget ({@link WidgetLaunch}). A
     * widget-opened window that ignored those settings is the bug this was
     * split out for.
     *
     * Maximized deliberately stops 2px short of the display edges: the PC-side
     * enforcer reads a window that truly fills the display as the user pressing
     * One UI's maximize button, and would immediately un-maximize it again
     * (scrcpy.rs, Enforcer::fills).
     *
     * Advances the cascade counter, so the two paths interleave instead of
     * dealing two windows onto the same spot.
     */
    @Override
    public Rect nextWindowBounds() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        String mode = DexPrefs.getString(this, DexPrefs.KEY_LAUNCH_MODE, DexPrefs.DEF_LAUNCH_MODE);
        float scale;
        switch (DexPrefs.getString(this, DexPrefs.KEY_WINDOW_SIZE, DexPrefs.DEF_WINDOW_SIZE)) {
            case "compact":
                scale = 0.8f;
                break;
            case "large":
                scale = 1.28f;
                break;
            default:
                scale = 1f;
        }
        int w = Math.min(size.x - dp(16), Math.round(size.x * 0.55f * scale));
        int h = Math.min(size.y - dp(TASKBAR_DP + 16), Math.round(size.y * 0.72f * scale));
        int x;
        int y;
        if ("maximized".equals(mode)) {
            x = dp(2);
            y = dp(2);
            w = size.x - dp(4);
            h = size.y - dp(TASKBAR_DP) - dp(4);
        } else if ("center".equals(mode)) {
            x = (size.x - w) / 2;
            y = Math.max(dp(8), (size.y - dp(TASKBAR_DP) - h) / 2);
        } else {
            int step = dp(30);
            x = dp(64) + (cascade % 5) * step;
            y = dp(36) + (cascade % 5) * step;
            cascade++;
        }
        return new Rect(x, y, x + w, y + h);
    }

    /** Start an app in a freeform window on the desktop display. */
    private void startWindowed(AppEntry app) {
        final String pkg = app.component.getPackageName();
        Rect bounds = nextWindowBounds();

        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Reduce quality: skip the open transition on the launch the user
        // actually waits for. Mostly redundant while the PC has the animation
        // scales at zero — and that is the point, because this is the half
        // that still works when the request queue has not been drained yet, or
        // when a hardened device dropped the `settings put`.
        if (theme.perf) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        // Whether this launch may adopt an existing task is the whole of the
        // phone-vs-desktop question, so it is decided here rather than left to the
        // platform's default.
        //
        // NEW_TASK alone means "reuse a task for this component if there is one", and
        // ActivityStarter looks for that task on EVERY display: RootWindowContainer
        // #findTask searches the preferred display area first and then all the others.
        // So launching an app that happens to be open on the PHONE did not open it here
        // — it dragged the phone's task onto this display, and the phone lost its window.
        // The same search running the other way (the phone's own launcher, a notification)
        // is what makes a tap on the phone surface the window over HERE instead.
        //
        // MULTIPLE_TASK says "mint your own task", which is what keeps the two screens
        // independent: the phone keeps its instance, the desktop gets one of its own.
        // It is added only when the app has no window here yet, because relaunching IS
        // how the taskbar icon and the desktop shortcut refocus an app that is already
        // open — with MULTIPLE_TASK on that path every click would open another copy.
        //
        // runningPkgs is the right question to ask: the PC builds it from the task list
        // of THIS display alone (scrcpy.rs, Enforcer::broadcast) and minimised windows
        // are merged into it, so it means "has a window on the desktop", open or hidden.
        //
        // Apps whose launcher activity is singleTask/singleInstance are the honest
        // exception — getReusableTask() forces task reuse for those regardless of this
        // flag, so they can only ever live on one display at a time. The PC-side reclaim
        // pass (scrcpy.rs, Enforcer::reclaim_pass) is what covers them.
        boolean ownTask = !runningPkgs.contains(pkg);
        if (ownTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        ActivityOptions opts = shapeForDesktop(ActivityOptions.makeBasic(), bounds);
        try {
            startActivity(intent, opts.toBundle());
            // The flags are in here on purpose: when a window turns up on the wrong
            // screen, the first question is whether this launch asked for a task of its
            // own or was content to adopt one that already existed somewhere.
            DexLog.step("launch", app.component.flattenToShortString()
                    + " → display " + getDisplay().getDisplayId()
                    + " at " + bounds.left + "," + bounds.top
                    + " " + bounds.width() + "x" + bounds.height()
                    + (ownTask ? " [NEW_TASK|MULTIPLE_TASK — no window here yet]"
                               : " [NEW_TASK — refocusing the window already here]"));
            noteRecent(app.component);
            // Show the taskbar icon immediately — the running broadcast would only
            // confirm it a poll later; the titlebar appears the moment the tracker sees
            // the real window. This also closes the MULTIPLE_TASK decision above for the
            // next click: from here on the app counts as "open here", so a second tap
            // refocuses this window instead of minting a second one.
            if (runningPkgs.add(pkg)) refreshOpenApps();
        } catch (Exception e) {
            DexLog.warn("launch", "cannot open " + app.component.flattenToShortString(), e);
            Toast.makeText(this, getString(R.string.lx_cannot_open, app.label),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
