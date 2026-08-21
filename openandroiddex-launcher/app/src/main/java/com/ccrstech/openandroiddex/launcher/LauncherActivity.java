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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
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
import android.widget.SeekBar;
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
 * ({@link DesktopGrid}), an app launcher surface and a taskbar.
 *
 * It wears one of two shells, chosen in Settings and read from
 * {@link DexPrefs#KEY_SHELL}:
 *
 * - DeX (the default). Nav cluster (back · home · open apps) on the left, apps
 *   toggle + OPEN apps in the center, clock/date with a calendar popup on the
 *   right; the apps button opens a full-surface drawer.
 * - Windows 11. Back alone on the left (Android needs a Back key and Windows
 *   has nowhere to put one), a centred Start · Search · Task view · Desktop
 *   cluster with the open apps beside it, the same tray on the right; Start
 *   opens a floating Start menu — pinned tiles, Recommended, an account strip
 *   and a power button — instead of a drawer.
 *
 * The two share everything below the layout: the same app list, the same
 * open-apps strip, the same tray flyouts, the same drag onto the desktop grid.
 * What differs is where the controls sit and, through {@link DexTheme}, what
 * they are painted in — so a shell is a switch here rather than a second
 * launcher to keep in step.
 *
 * The open-apps row mirrors what is actually running on this display — the PC
 * side broadcasts the task list (ACTION_RUNNING) on every poll, and
 * launches/closes done from here update the row optimistically so there is no
 * visible lag.
 *
 * Holding an icon in the drawer (or in the Start menu) closes it and hands the
 * gesture to the drag layer, which drops the app onto the desktop grid — see
 * startDesktopDrag.
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
     * Height of the phone dock — the bar's replacement on the phone's own
     * screen ({@link #onPhone}). Taller than the taskbar because nothing on
     * the other side of the glass is aiming a pointer at it: these are finger
     * targets, and 56dp is the platform's own floor for one.
     *
     * Free to differ from {@link #TASKBAR_DP}: the 52 there is a contract with
     * the PC, which sizes maximized windows against it, and there is no PC on
     * this display.
     */
    static final int DOCK_DP = 56;

    /**
     * How long the PC may go quiet before the phone stops assuming one is
     * there. Generous against the heartbeat it is measured on: that is every
     * fiftieth poll of a loop that ticks between 50 and 300ms, so ~15s at its
     * slowest, and being wrong here costs an exit that closes only the phone's
     * window.
     */
    private static final long PC_SILENCE_MS = 30_000L;

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
    /** The full app list, shared by the DeX drawer and the Start menu's "All apps". */
    private GridView appGrid;
    private DragLayer dragLayer;

    // ── Windows 11 Start menu (null under the DeX shell) ──
    /** Holds the pinned/recommended page and the app list, one visible at a time. */
    private FrameLayout startContent;
    private ScrollView startPinnedScroll;
    private LinearLayout startPinnedGrid;
    private LinearLayout startRecommended;
    private TextView startHeader;
    private TextView startAllAppsButton;
    /**
     * The menu is showing its app list rather than its pinned tiles.
     *
     * Reset by {@link #hideDrawer}, so Start always opens on Pinned the way
     * Windows does — a menu that reopens two pages deep is a menu people stop
     * trusting to be where they left it.
     */
    private boolean startAllApps;
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
    /** The phone dock's Mouse button, so it can show the touchpad's state. */
    private TextView padButton;
    /** The phone's touchpad and pointer; null until asked for. See {@link DexPointer}. */
    private DexPointer pointer;
    private PopupWindow padPopup;
    private PopupWindow homePopup;
    private PopupWindow recentsPopup;
    private PopupWindow calendarPopup;
    private PopupWindow batteryPopup;
    private PopupWindow qsPopup;
    private PopupWindow notifPopup;
    private PopupWindow exitPopup;
    private PopupWindow widgetPicker;
    private PopupWindow mediaPopup;

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
    /** Tray battery pill ("⚡ 87%") — refreshed by batteryReceiver. */
    private TextView batteryPill;
    /** Tray fullscreen toggle — glyph mirrors the PC window's state. */
    private TextView fsButton;
    private boolean pcFullscreen = false;
    /** Latest ACTION_BATTERY_CHANGED sticky intent — feeds pill + flyout. */
    private Intent lastBattery;
    private boolean torchOn = false;
    private CameraManager.TorchCallback torchCallback;
    /** Tray "Phone screen" tile — lit while the phone's own panel is lit. */
    private boolean phoneScreenOn = true;
    /** Lazy: the one tray tile whose work is a daemon call rather than a shell command. */
    private WmClient qsWm;

    // ── notifications ──
    /** The flyout and the call banner both live in here — see {@link NotificationPanel}. */
    private NotificationPanel notifications;
    /** Tray bell, and the count drawn over it. Null while the tray is being rebuilt. */
    private View bellButton;
    private TextView bellBadge;

    /**
     * The desktop's ear on the phone's notifications.
     *
     * Registering REPLACES nothing — the registry is a set — so this is paired
     * with an explicit clear in onDestroy. Without that pair a density-driven
     * relaunch of the desktop leaves a listener pointing at a dead activity
     * behind on every pass, exactly as {@link OwnWindows} warns.
     */
    private final DexNotifications.Listener notificationListener =
            new DexNotifications.Listener() {
                @Override
                public void onNotificationsChanged() {
                    // Off the binder thread and onto ours: this touches the
                    // tray badge, and on a busy phone it arrives several times
                    // a second.
                    handler.post(() -> {
                        updateBellBadge();
                        // A flyout standing open while notifications arrive
                        // behind it goes stale, and its rows would fire actions
                        // for entries that have since been replaced. Rebuilding
                        // in place is not worth it for a surface that is open
                        // for seconds — but leaving it wrong is not an option
                        // either, so it is rebuilt from the same code that
                        // opened it.
                        if (notifPopup != null && notifPopup.isShowing()) {
                            dismissPopups();
                            toggleNotificationsPopup();
                        }
                    });
                }

                @Override
                public void onHeadsUp(DexNotifications.Item item) {
                    handler.post(() -> {
                        if (!DexNotifications.enabled(LauncherActivity.this)) return;
                        notificationPanel().showHeadsUp(item);
                    });
                }

                @Override
                public void onIncomingCall(DexNotifications.Item call) {
                    handler.post(() -> {
                        if (!DexNotifications.enabled(LauncherActivity.this)) return;
                        notificationPanel().showCall(call);
                    });
                }

                @Override
                public void onCallEnded(String key) {
                    handler.post(() -> {
                        if (notifications != null) notifications.hideCall(key);
                    });
                }
            };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int cascade = 0;
    /** Last broadcast seq applied — the PC fires broadcasts without waiting,
     *  so they can arrive out of order. */
    private int lastSeq = -1;
    /**
     * uptime of the last word from the PC, or 0 if it has never spoken.
     *
     * The running-apps broadcast doubles as the PC's heartbeat: it is re-sent
     * every fiftieth poll even when nothing has changed, so silence here means
     * silence on the whole channel. Only {@link #pcAlive} reads it.
     */
    private long pcSeenAt;

    private final BroadcastReceiver runningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            pcSeenAt = SystemClock.uptimeMillis();
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
                // The wire value predates the notification centre and still
                // means quick settings — renaming it would silently retarget
                // every gesture already configured. The centre has a slot of
                // its own below.
                case "notifications":
                    toggleQuickSettingsPopup();
                    break;
                case "notifcentre":
                    toggleNotificationsPopup();
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
            // The call banner and the pop-up cards are overlay WINDOWS, so a
            // shell rebuild leaves them standing — in the old theme's colours,
            // and in the case of a switch just turned off, with nothing left
            // that would ever take them down. Both are transient by nature, so
            // dropping them is the whole fix: the next notification builds new
            // ones against the new palette.
            if (notifications != null) notifications.detach();
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
        // After the dock, which is what its button reports into.
        restorePad();
        // Posted, and late: this is a flyout anchored to the dock, and onCreate
        // is the one moment the dock may still be an in-activity bar waiting on
        // the overlay app-op (see scheduleOverlayUpgrade).
        handler.postDelayed(this::maybeOfferHome, 1500);
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
        // Same again for the notification listener: it is a service in this
        // very process, so it reports directly rather than over a broadcast.
        // Registered whether or not the grant is in place — the service binds
        // whenever the user (or the PC's deploy) turns access on, which can
        // happen mid-session, and this is what picks it up without a relaunch.
        DexNotifications.setListener(notificationListener);
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
        if (qsWm != null) qsWm.shutdown();
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
        DexNotifications.clearListener(notificationListener);
        // The call banner is an overlay window, so — like the taskbar and the
        // transfer card — it outlives this activity unless it is taken down
        // here. A banner left standing over a dead desktop cannot be dismissed
        // by anything.
        if (notifications != null) notifications.detach();
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
        // The pad and the pointer are overlay WINDOWS: nothing takes them down
        // with the activity, and a leaked one is a cursor floating over the
        // phone's own home screen with no way left to dismiss it.
        if (pointer != null) pointer.detach();
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
        // The pad and the pointer are windows of their own — buildUi cannot
        // reach them, so a palette or density change would leave the touchpad
        // painted in the shell's last theme until it was toggled off and on.
        if (pointer != null) pointer.refresh();
        relayoutDock();
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
    /** The shell's display width in px — what a flyout has to fit inside. */
    int uiWidthPx() {
        return uiWidthPx;
    }

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
     * True when the shell is drawn on the phone's OWN screen rather than on a
     * display of its own.
     *
     * <p>Unlike {@link #compact()} this IS a feature switch, and the only one
     * in the shell. A desktop display is a picture on a computer with a mouse
     * and a keyboard in front of it; the phone's screen is a touchscreen the
     * user is holding, with its own back gesture, its own clock, its own
     * battery icon and no pointer at all. The taskbar's three clusters are
     * either duplicated by the phone's own chrome (clock, battery, nav keys)
     * or aimed at a computer that is not there (the fullscreen toggle drives
     * the PC's scrcpy window) — so on this display the bar is replaced by a
     * dock of the three things that still mean something: the drawer, the
     * touchpad, and the way out. See {@link #buildPhoneDock}.
     *
     * <p>By DISPLAY and not by width, for the reason {@link #compact()} gives:
     * a desktop display driven at a phone-like "Display size" is narrow and
     * still a desktop. A null display is a window on its way out; treat it as
     * the phone, which is the branch that assumes nothing.
     */
    boolean onPhone() {
        Display display = getDisplay();
        return display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY;
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
        boolean dock = onPhone();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dock ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                dp(dock ? DOCK_DP : TASKBAR_DP),
                dock ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.BOTTOM);
        // targetSdk 35 runs the activity window edge to edge, so on the phone's
        // own screen this would otherwise land under the gesture pill.
        lp.bottomMargin = bottomSystemInset() + dockLift();
        rootFrame.addView(taskbarView, lp);
    }

    /** Add the taskbar as an always-on-top overlay window; false if not permitted. */
    private boolean attachTaskbarOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            DexLog.warn("taskbar", "no overlay permission yet — app windows will cover the bar");
            return false;
        }
        boolean dock = onPhone();
        WindowManager.LayoutParams barLp = new WindowManager.LayoutParams(
                dock ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                dp(dock ? DOCK_DP : TASKBAR_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        // No y offset for the gesture pill here, unlike addTaskbarToActivity:
        // an overlay window is already laid out inside the safe area (see
        // bottomSystemInset), and offsetting it again lifts the bar off the
        // bottom edge by the height of the pill. The dock's own offset is a
        // different thing — it is the touchpad underneath it, not an inset.
        barLp.gravity = dock ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.BOTTOM;
        barLp.y = dockLift();
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
     * How far the phone dock floats off the bottom edge, in px.
     *
     * <p>A gap always, because the dock is a pill and a pill flush to the edge
     * reads as a bar that failed to reach it — plus the whole touchpad when
     * one is up, so the dock is never the thing your thumb hits while aiming
     * for the pad. Zero on a desktop display, where the bar IS the edge.
     */
    private int dockLift() {
        if (!onPhone()) return 0;
        int pad = 0;
        if (pointer != null && pointer.showing()) {
            Point size = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            pad = DexPointer.heightPx(this, size.y);
        }
        return dp(10) + pad;
    }

    /**
     * Height at the bottom of the display that belongs to the shell's own
     * chrome, in px — what every full-surface panel and every launched window
     * has to stop short of.
     *
     * On a desktop display that is the taskbar and nothing else, which is why
     * this used to be {@code dp(TASKBAR_DP)} spelled out at each call site. On
     * the phone it is the dock, and the touchpad underneath it when one is up:
     * a window sized against the taskbar's 52dp there would put its bottom
     * quarter under a pad the user is dragging a finger across.
     *
     * <p>NOT the system's own bars — those are {@link #bottomSystemInset},
     * which some callers owe and others (anything already laid out inside the
     * safe area) do not.
     */
    private int bottomChrome() {
        return onPhone() ? dockLift() + dp(DOCK_DP) : dp(TASKBAR_DP);
    }

    /**
     * The same reserve in DISPLAY coordinates, which is a different number.
     *
     * {@link #bottomChrome} measures our own chrome from where it is drawn, and
     * the dock is drawn inside the safe area — the window manager lays an
     * APPLICATION_OVERLAY out above the gesture pill. A launch rect is in raw
     * display pixels, where that pill is real estate like any other, so it owes
     * the system inset on top. Measured on a 1080x2340 phone: chrome 187px,
     * pill 43px, and a window sized against the first alone runs 43px under the
     * dock.
     *
     * <p>Identical to {@link #bottomChrome} on a desktop display, which has no
     * system bars at all.
     */
    private int bottomReserve() {
        return bottomSystemInset() + bottomChrome();
    }

    /** What the icon grid owes the chrome below it, in px. */
    private int deskBottomInset() {
        return bottomReserve() + dp(onPhone() ? 8 : 14);
    }

    /** Re-seat the dock and the icon grid after the touchpad came or went. */
    private void relayoutDock() {
        if (!onPhone()) return;
        if (desktopGrid != null) {
            desktopGrid.setPadding(desktopGrid.getPaddingLeft(), desktopGrid.getPaddingTop(),
                    desktopGrid.getPaddingRight(), deskBottomInset());
        }
        if (taskbarView == null) return;
        try {
            if (taskbarOverlay) {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) taskbarView.getLayoutParams();
                lp.y = dockLift();
                getWindowManager().updateViewLayout(taskbarView, lp);
            } else if (taskbarView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) taskbarView.getLayoutParams();
                lp.bottomMargin = bottomSystemInset() + dockLift();
                taskbarView.setLayoutParams(lp);
            }
        } catch (Exception e) {
            DexLog.warn("pointer", "cannot re-seat the dock", e);
        }
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
                deskBottomInset());
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
        appGrid = grid;
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

    /**
     * The launcher surface, in whichever shape this shell wants it, inside the
     * window root that hosts the drag onto the desktop.
     *
     * Both shells go through here so the drag layer, the Back/Esc handling and
     * the overlay window they all live in are written once — the DeX drawer and
     * the Start menu differ only in the panel and in how much of the window it
     * covers.
     */
    private void buildDrawer() {
        LinearLayout panel = theme.win11 ? buildStartPanel() : buildDexDrawerPanel();
        drawerPanel = panel;

        // The window root is a frame so the drag layer can sit over the panel:
        // once a drag starts the panel is hidden and only the layer paints,
        // leaving the desktop grid visible straight through this window.
        drawer = new DrawerRoot(this);
        drawer.addView(panel, theme.win11
                ? startPanelParams()
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        if (theme.win11) {
            // The Start menu leaves most of its window empty, and a click in
            // that emptiness is a click on the desktop — which in Windows
            // dismisses the menu. Only reached when the panel did not take the
            // touch itself, since it is clickable.
            drawer.setOnClickListener(v -> hideDrawer());
        }
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
        // click, so the hand is taken back — and it has to be taken back AFTER
        // decorate's walk, which would otherwise put it straight back. The
        // cells inside keep theirs.
        DexCursors.apply(panel, DexCursors.ROLE_ARROW);
        DexCursors.apply(appGrid, DexCursors.ROLE_ARROW);
        // Its own window, so buildUi's pass over the activity's tree never
        // reaches it — the drawer asks for the chosen font itself.
        DexFonts.applyTo(this, drawer);
    }

    /** The DeX shell's drawer: one full-surface panel of apps. */
    private LinearLayout buildDexDrawerPanel() {
        // Dropped rather than left dangling: switching shell rebuilds the whole
        // view tree, and a stale startContent would let updateStartMode hide
        // the drawer's own app grid — the two shells share that one view.
        startContent = null;
        startPinnedScroll = null;
        startPinnedGrid = null;
        startRecommended = null;
        startHeader = null;
        startAllAppsButton = null;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // the drawer is the largest surface we own — in Paper mode it is where
        // the grain reads most, so it goes through surface() like the rest
        panel.setBackground(theme.surface(theme.panel(), 0f));
        int pad = dp(compact ? 14 : 48);
        // The in-activity flavour; showDrawer sets it again for whichever host
        // the drawer actually gets.
        panel.setPadding(pad, topSystemInset() + drawerTopPad(), pad,
                bottomReserve() + dp(12));
        panel.setClickable(true);

        panel.addView(buildPinnedRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = newSearchField(getString(R.string.lx_search_apps));
        search.setBackground(roundedFill(theme.field, 22));
        search.setPadding(dp(20), dp(11), dp(20), dp(11));
        panel.addView(search, new LinearLayout.LayoutParams(
                compact ? ViewGroup.LayoutParams.MATCH_PARENT : dp(360),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(16);
        panel.addView(buildAppGrid(), gridLp);

        return panel;
    }

    /**
     * The app-filtering field, which both shells have and neither draws the
     * same: the drawer's is a wide pill of its own, the Start menu's sits in a
     * search row at the top of the menu. Only the chrome differs, so only the
     * chrome is left to the caller.
     */
    private EditText newSearchField(String hint) {
        EditText search = new EditText(this);
        searchField = search;
        search.setHint(hint);
        search.setHintTextColor(theme.textFaint);
        search.setTextColor(theme.text);
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        search.setSingleLine(true);
        // blinking caret so it is obvious typing goes here
        search.setCursorVisible(true);
        search.setOnClickListener(v -> search.requestFocus());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        return search;
    }

    // ── Windows 11: the Start menu ──

    /**
     * Tiles per row of the pinned grid. Windows 11 pins six across; a
     * phone-width display cannot show six icons and their labels, so compact
     * drops to four.
     */
    private static final int START_COLS = 6;
    private static final int START_COLS_COMPACT = 4;
    /** Rows of pinned tiles, as Windows 11 lays them out. */
    private static final int START_ROWS = 2;

    /**
     * The Start menu: a search row, a page of pinned tiles with Recommended
     * under it, and an account strip carrying the power button.
     *
     * A panel rather than a window of its own, so it lives in the same overlay
     * the DeX drawer does and inherits everything that window already knows how
     * to do — Esc to close, and a hold on any tile turning into a drag onto the
     * desktop grid.
     */
    private LinearLayout buildStartPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(theme.surface(theme.panel(), dp(theme.radius(12))));
        int pad = dp(compact ? 14 : 22);
        panel.setPadding(pad, dp(compact ? 12 : 18), pad, 0);
        // Swallows the taps the window root would otherwise read as "clicked
        // the desktop, close the menu".
        panel.setClickable(true);
        // Windows floats the menu over the wallpaper; with no shadow under it
        // the panel reads as a hole cut in the desktop rather than a sheet
        // over it. Reduce quality drops it with the rest of the finish.
        if (!theme.perf) panel.setElevation(dp(20));

        panel.addView(buildStartSearch(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(16), dp(2), dp(8));

        startHeader = new TextView(this);
        startHeader.setTextColor(theme.text);
        startHeader.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        startHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(startHeader, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        startAllAppsButton = new TextView(this);
        startAllAppsButton.setTextColor(theme.text);
        startAllAppsButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        startAllAppsButton.setPadding(dp(12), dp(7), dp(12), dp(7));
        startAllAppsButton.setBackground(tapBackground(theme.field, theme.hover, 8));
        startAllAppsButton.setOnClickListener(v -> {
            if (startListMode()) {
                // Back out of the list AND of whatever was typed to reach it,
                // or the pinned page would come back filtered by a query with
                // nowhere left to show itself.
                startAllApps = false;
                searchField.setText("");
            } else {
                startAllApps = true;
            }
            updateStartMode();
        });
        DexCursors.apply(startAllAppsButton, DexCursors.ROLE_HAND);
        head.addView(startAllAppsButton);
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View pinnedPage = buildStartPinnedPage();
        startContent = new FrameLayout(this);
        startContent.addView(pinnedPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        startContent.addView(buildAppGrid(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(startContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        panel.addView(buildStartFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fillStartPinned();
        updateStartMode();
        return panel;
    }

    /** The menu's search row: a magnifier and the field that filters the apps. */
    private View buildStartSearch() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundedFill(theme.field, 8));
        row.setPadding(dp(14), 0, dp(14), 0);

        TextView magnifier = new TextView(this);
        magnifier.setText("🔍");
        magnifier.setTextColor(theme.textFaint);
        magnifier.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        LinearLayout.LayoutParams magLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        magLp.rightMargin = dp(10);
        row.addView(magnifier, magLp);

        EditText search = newSearchField(getString(R.string.w11_search_hint));
        search.setBackground(null);
        search.setPadding(0, dp(11), 0, dp(11));
        row.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** The pinned tiles and Recommended, scrolling together as one page. */
    private View buildStartPinnedPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        startPinnedGrid = new LinearLayout(this);
        startPinnedGrid.setOrientation(LinearLayout.VERTICAL);
        page.addView(startPinnedGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        startRecommended = new LinearLayout(this);
        startRecommended.setOrientation(LinearLayout.VERTICAL);
        page.addView(startRecommended, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        startPinnedScroll = new ScrollView(this);
        startPinnedScroll.setVerticalScrollBarEnabled(false);
        startPinnedScroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return startPinnedScroll;
    }

    /**
     * (Re)fill the pinned grid and the Recommended list.
     *
     * Run twice on a cold start, and it has to be: buildUi builds this menu
     * before loadApps has read the app list, so the first pass has only our own
     * tiles to place and the second — from loadApps — is what fills the rest of
     * the grid in. Cheap enough to repeat; ~16 views.
     */
    private void fillStartPinned() {
        if (!theme.win11 || startPinnedGrid == null) return;
        int cols = compact ? START_COLS_COMPACT : START_COLS;

        startPinnedGrid.removeAllViews();
        List<View> tiles = new ArrayList<>(systemTiles());
        for (AppEntry app : startPinnedApps(cols * START_ROWS - tiles.size())) {
            tiles.add(startAppTile(app));
        }
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                startPinnedGrid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(tiles.get(i), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // Pad the last row out to the full column count: without it a half-full
        // row spreads its tiles across the whole width and they stop lining up
        // with the ones above.
        int spare = tiles.size() % cols;
        if (row != null && spare != 0) {
            for (int i = spare; i < cols; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }

        startRecommended.removeAllViews();
        List<AppEntry> recent = startRecommendedApps(compact ? 3 : 6);
        // Nothing launched from this desktop yet — a heading over an empty
        // block reads as something that failed to load.
        if (recent.isEmpty()) return;
        startRecommended.addView(sectionLabel(getString(R.string.w11_recommended)));
        int recCols = compact ? 1 : 2;
        LinearLayout recRow = null;
        for (int i = 0; i < recent.size(); i++) {
            if (i % recCols == 0) {
                recRow = new LinearLayout(this);
                recRow.setOrientation(LinearLayout.HORIZONTAL);
                startRecommended.addView(recRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            recRow.addView(recommendedRow(recent.get(i)), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int recSpare = recent.size() % recCols;
        if (recRow != null && recSpare != 0) {
            for (int i = recSpare; i < recCols; i++) {
                recRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        // Built after the drawer's own font pass (and again on every refill),
        // so this block asks for the chosen face itself.
        DexFonts.applyTo(this, startPinnedScroll);
    }

    /**
     * What fills the pinned grid after our own tiles: the apps on the desktop,
     * then the app list from the top to make the rows up.
     *
     * Dragging an icon onto the desktop grid is the only pinning gesture this
     * shell has, so it is what "Pinned" means here — a pinned area that ignored
     * it would be a second, invisible list to curate. Deliberately NOT the
     * recents: those are what Recommended below is made of, and a menu whose
     * two halves show the same four apps has one half.
     */
    private List<AppEntry> startPinnedApps(int max) {
        List<AppEntry> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (desktopGrid != null) {
            for (AppEntry app : desktopGrid.pinnedApps()) {
                if (out.size() >= max) return out;
                if (seen.add(app.component.flattenToString())) out.add(app);
            }
        }
        // A desktop with nothing on it yet, or room left over after it: the app
        // list from the top, so the grid is never a half-empty box.
        for (AppEntry app : allApps) {
            if (out.size() >= max) break;
            if (seen.add(app.component.flattenToString())) out.add(app);
        }
        return out;
    }

    /** Recommended: what was actually launched from here, most recent first. */
    private List<AppEntry> startRecommendedApps(int max) {
        List<AppEntry> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String flat : recents) {
            if (out.size() >= max) break;
            ComponentName cn = ComponentName.unflattenFromString(flat);
            if (cn == null) continue;
            AppEntry app = findByComponent(cn);
            // findByComponent falls back to the package, so two stored records
            // of a renamed launcher activity can resolve to one app.
            if (app == null || !seen.add(app.component.flattenToString())) continue;
            out.add(app);
        }
        return out;
    }

    /** A pinned app: the same tile the drawer and the desktop grid draw. */
    private LinearLayout startAppTile(AppEntry app) {
        LinearLayout tile = newIconTile(iconDp());
        ((ImageView) tile.getChildAt(0)).setImageDrawable(app.icon);
        ((TextView) tile.getChildAt(1)).setText(app.label);
        tile.setOnClickListener(v -> launch(app));
        tile.setOnLongClickListener(v -> {
            startDesktopDrag(app);
            return true;
        });
        return tile;
    }

    /** The heading over a block of the Start menu. */
    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(4), dp(16), dp(4), dp(8));
        return label;
    }

    /** One Recommended entry: icon, name, and why it is here. */
    private View recommendedRow(AppEntry app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(tapBackground(0x00000000, theme.hover, 8));
        row.setOnClickListener(v -> launch(app));
        row.setOnLongClickListener(v -> {
            startDesktopDrag(app);
            return true;
        });
        DexCursors.apply(row, DexCursors.ROLE_HAND);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLp.rightMargin = dp(10);
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(app.label);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = new TextView(this);
        sub.setText(getString(R.string.w11_recent));
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * The strip Windows keeps at the foot of the menu: who you are on the left,
     * the power button on the right.
     */
    private View buildStartFooter() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        View line = new View(this);
        line.setBackgroundColor(theme.divider);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout account = new LinearLayout(this);
        account.setOrientation(LinearLayout.HORIZONTAL);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(8), dp(6), dp(14), dp(6));
        account.setBackground(tapBackground(0x00000000, theme.hover, 8));
        account.setOnClickListener(v -> launchSettings());
        DexCursors.apply(account, DexCursors.ROLE_HAND);

        TextView avatar = new TextView(this);
        avatar.setText("👤");
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        // A circle, which is the one shape an account picture has in Windows.
        avatar.setBackground(theme.surface(theme.accentSoft, dp(14)));
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        avatarLp.rightMargin = dp(10);
        account.addView(avatar, avatarLp);

        TextView who = new TextView(this);
        // The phone IS the account here — there is no user to name — and the
        // model is what tells two of these desktops apart.
        who.setText(TextUtils.isEmpty(Build.MODEL)
                ? getString(R.string.app_name) : Build.MODEL);
        who.setTextColor(theme.text);
        who.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        account.addView(who);
        row.addView(account);

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        ImageView power = new ImageView(this);
        power.setImageResource(android.R.drawable.ic_lock_power_off);
        power.setColorFilter(theme.textDim);
        int powerPad = dp(8);
        power.setPadding(powerPad, powerPad, powerPad, powerPad);
        power.setBackground(tapBackground(0x00000000, theme.hover, 8));
        power.setContentDescription(getString(R.string.w11_power));
        power.setOnClickListener(v -> {
            // The exit flyout hangs off the taskbar, which this menu covers —
            // so the menu closes first, exactly as Start does in Windows.
            hideDrawer();
            toggleExitPopup();
        });
        DexCursors.apply(power, DexCursors.ROLE_HAND);
        row.addView(power, new LinearLayout.LayoutParams(dp(34), dp(34)));

        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** True when the menu is showing its app list rather than its pinned page. */
    private boolean startListMode() {
        return startAllApps || (searchField != null && searchField.getText().length() > 0);
    }

    /**
     * Put the menu on the right page, and label its header and its one button
     * for the page it is on.
     *
     * Typing switches to the list without going through the button: a query
     * that left the menu on its pinned page would be filtering a grid the user
     * cannot see.
     */
    private void updateStartMode() {
        if (!theme.win11 || startContent == null || appGrid == null) return;
        boolean searching = searchField != null && searchField.getText().length() > 0;
        boolean list = startAllApps || searching;
        startPinnedScroll.setVisibility(list ? View.GONE : View.VISIBLE);
        appGrid.setVisibility(list ? View.VISIBLE : View.GONE);
        startHeader.setText(getString(searching ? R.string.w11_results
                : list ? R.string.w11_all_apps : R.string.w11_pinned));
        startAllAppsButton.setText(list
                ? "‹  " + getString(R.string.w11_back)
                : getString(R.string.w11_all_apps) + "  ›");
    }

    /**
     * Where the Start menu sits in its window: bottom centre, clear of the
     * taskbar, and never wider than Windows 11 draws it.
     *
     * The WINDOW still covers the whole desktop. That is what makes a click
     * beside the menu a click on the wallpaper (which closes it), and what lets
     * a drag out of the menu paint cell guides across the grid it is being
     * dropped onto — a window cropped to the menu could do neither.
     */
    private FrameLayout.LayoutParams startPanelParams() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int available = Math.max(dp(240), size.y - topSystemInset() - bottomReserve());
        int width = compact
                ? Math.max(dp(260), size.x - dp(20))
                : Math.min(dp(620), Math.round(size.x * 0.92f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width,
                Math.min(dp(compact ? 540 : 700), Math.round(available * 0.94f)),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(8);
        return lp;
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
     * The desktop's own tiles: Settings, Linux, Docker where the ABI can run
     * it, and the Web viewer.
     *
     * These are the in-desktop tools, and they exist as tiles precisely because
     * nothing else will list them: loadApps skips our own package, so an app
     * grid built from the launcher intent never contains them.
     *
     * A list rather than a row, because both shells show these four and neither
     * shows them the same way — the DeX drawer puts them in a labelled row
     * above the app list, the Windows 11 Start menu pins them into its grid.
     *
     * Docker sits beside Linux rather than inside it, because it is not a guest
     * of the Ubuntu container and could never be one: it is a whole virtual
     * machine of its own (Docker.java has the kernel measurements that force
     * that). Hidden outright where the APK has no QEMU for the ABI — an offer
     * that cannot be honoured is worse than no offer. The Web viewer is never
     * hidden on a device basis: MediaProjection is on every Android this APK
     * runs on, and the one thing that can be missing (the accessibility
     * service, for control) is something the window itself says how to fix.
     */
    private List<LinearLayout> systemTiles() {
        List<LinearLayout> tiles = new ArrayList<>();

        LinearLayout settings = newIconTile(iconDp());
        settings.setOnClickListener(v -> launchSettings());
        ImageView gear = (ImageView) settings.getChildAt(0);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setColorFilter(theme.accent);
        gear.setBackground(roundedFill(theme.accentSoft, 12));
        int gearPad = dp(9);
        gear.setPadding(gearPad, gearPad, gearPad, gearPad);
        ((TextView) settings.getChildAt(1)).setText(getString(R.string.settings_label));
        tiles.add(settings);

        tiles.add(glyphTile("🐧", getString(R.string.ln_label),
                v -> launchLinux(), this::showLinuxMenu));
        if (Docker.abiSupported()) {
            tiles.add(glyphTile("🐳", getString(R.string.dk_label),
                    v -> launchDocker(), this::showDockerMenu));
        }
        tiles.add(glyphTile("🌐", getString(R.string.wb_label),
                v -> launchWeb(), this::showWebMenu));
        return tiles;
    }

    /**
     * A system tile whose icon is a font glyph: nothing in the framework draws
     * a penguin, a whale or a globe, and the emoji is the one icon every device
     * already ships. Swapped in at index 0 so the tile keeps newIconTile's
     * child layout.
     *
     * Right-click (forwarded by scrcpy) and long-press both reach the tile's
     * menu — the one place you can do what a broken container will not let you
     * do from inside it: throw it away.
     */
    private LinearLayout glyphTile(String glyph, String label,
                                   View.OnClickListener onClick, Consumer<View> onMenu) {
        LinearLayout tile = newIconTile(iconDp());
        tile.setOnClickListener(onClick);
        tile.setOnContextClickListener(v -> {
            onMenu.accept(v);
            return true;
        });
        tile.setOnLongClickListener(v -> {
            onMenu.accept(v);
            return true;
        });

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(iconDp()) * 0.52f);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundedFill(theme.accentSoft, 12));
        tile.removeViewAt(0);
        tile.addView(icon, 0, new LinearLayout.LayoutParams(dp(iconDp()), dp(iconDp())));
        ((TextView) tile.getChildAt(1)).setText(label);
        return tile;
    }

    /** The DeX drawer's header row of system tiles, over a rule. */
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
        for (LinearLayout tile : systemTiles()) {
            row.addView(tile, new LinearLayout.LayoutParams(
                    dp(iconDp() + (compact ? 20 : 58)),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

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
                ActivityOptions.makeBasic(),
                desktopWindowRect(TaskManagerActivity.class, dp(720), dp(560)));
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
        // The way back to the install chooser. Without it that screen is a
        // one-way door: it is shown once, before the first container exists,
        // and someone who unticked VS Code to save the download would have no
        // route to it short of deleting the whole container and starting
        // again. Offered even before there is a container — the screen is the
        // first thing an install goes through anyway — but not while Linux is
        // uninstalled, where provisioning is deliberately inert and the button
        // would lead nowhere.
        if (!Linux.isUninstalled(this)) {
            menu.getMenu().add(0, 6, 3, getString(R.string.ln_apps_menu));
        }
        menu.getMenu().add(0, 4, 4, getString(R.string.ln_reinstall));
        // Only while there is something to remove. Offering "Uninstall" over a
        // container that is already gone is an action that would do nothing,
        // and it is the entry that reads most like it should.
        if (Linux.isInstalled(this)) {
            menu.getMenu().add(0, 5, 5, getString(R.string.ln_uninstall));
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
            } else if (id == 6) {
                chooseLinuxApps();
            }
            return true;
        });
        menu.show();
    }

    /**
     * Reopen the install chooser — which apps go into the container.
     *
     * The screen itself lives in the Linux window, because that is where the
     * install it drives is narrated and because a second copy of it in a dialog
     * out here would be a second copy to keep true. So this is a start with one
     * extra on it; LinuxActivity is singleTask, so an open window takes it
     * through onNewIntent rather than growing a second one.
     *
     * The restore comes first for the same reason it does in launchLinux: a
     * minimised window is not on any display, and starting it without asking
     * CaptionService to bring it back leaves the chooser on a window nobody can
     * see.
     */
    private void chooseLinuxApps() {
        hideDrawer();
        dismissPopups();
        if (minimisedActivities.contains(LinuxActivity.class.getName())) {
            sendBroadcast(new Intent(ACTION_RESTORE)
                    .setPackage(getPackageName())
                    .putExtra("pkg", getPackageName())
                    .putExtra("activity", LinuxActivity.class.getName()));
        }
        Intent intent = new Intent(this, LinuxActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LinuxActivity.EXTRA_CHOOSE_APPS, true);
        ActivityOptions opts = shapeForDesktop(
                ActivityOptions.makeBasic(),
                desktopWindowRect(LinuxActivity.class, dp(1100), dp(750)));
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            DexLog.warn("linux", "cannot open the app chooser", e);
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
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
                ActivityOptions.makeBasic(),
                desktopWindowRect(LinuxActivity.class, dp(1100), dp(750)));
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
        ActivityOptions opts = shapeForDesktop(ActivityOptions.makeBasic(),
                desktopWindowRect(DockerActivity.class, dp(900), dp(640)));
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
                ActivityOptions.makeBasic(),
                desktopWindowRect(WebActivity.class, dp(760), dp(720)));
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
        if (onPhone()) return buildPhoneDock();
        View nav = theme.win11 ? buildWin11NavCluster() : buildNavCluster();
        View apps = theme.win11 ? buildWin11Cluster() : buildAppsCluster();
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

    /**
     * The phone dock: Apps · Mouse · Exit DeX, and nothing else.
     *
     * <p>What the taskbar's other controls were for is either already on this
     * screen or on the other end of a cable that is not plugged in. Back, home
     * and recents are the phone's own gestures. The clock, the date and the
     * battery are in the status bar an inch above this. The fullscreen toggle
     * resizes a scrcpy window on a computer. The open-apps strip mirrors a
     * display that, here, is this one. Three things survive that reading:
     *
     * <ul>
     *   <li><b>Apps</b> — the drawer, which is the only route to Linux,
     *       Docker, the Web viewer and Settings: {@link #loadApps} skips our
     *       own package, so those four exist as tiles in the drawer and
     *       nowhere else.
     *   <li><b>Mouse</b> — the touchpad ({@link DexPointer}). A desktop drawn
     *       for a pointer, on a display that has none.
     *   <li><b>Exit DeX</b> — the way out, which on this display may have to
     *       be taken locally; see {@link #requestExit}.
     * </ul>
     *
     * <p>A centred pill rather than a full-width strip: it is three buttons,
     * and stretching them across a phone would put the two outer ones where no
     * thumb reaches.
     */
    private View buildPhoneDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(6), 0, dp(6), 0);
        dock.setBackground(theme.surface(theme.bar(), dp(DOCK_DP) / 2f));
        dock.setElevation(theme.perf ? 0f : dp(8));

        dock.addView(dockButton("⊞", getString(R.string.lx_apps),
                theme.text, v -> toggleDrawer(), null));

        padButton = dockButton("🖱", getString(R.string.lx_pad),
                theme.text, v -> togglePad(), v -> showPadOptions());
        dock.addView(padButton);
        updatePadButton();

        // A drawable and not a ⏻, for the reason buildTrayCluster spells out:
        // U+23FB is not in every device's fonts, and the one control here that
        // ends the session is the last one that may render as a tofu box.
        dock.addView(dockIconButton(android.R.drawable.ic_lock_power_off,
                getString(R.string.lx_exit_dex), theme.danger, v -> toggleExitPopup()));
        return dock;
    }

    /** A dock button whose icon is a drawable rather than a glyph. */
    private TextView dockIconButton(int iconRes, String label, int tint,
                                    View.OnClickListener onClick) {
        TextView btn = dockButton("", label, tint, onClick, null);
        btn.setText(label);
        Drawable icon = getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            // Sized to the glyph line the other two buttons draw, so the three
            // labels sit on one baseline.
            icon.setBounds(0, 0, Math.round(sp(10.5f) * 1.7f), Math.round(sp(10.5f) * 1.7f));
            icon.setTint(tint);
        }
        btn.setCompoundDrawables(null, icon, null, null);
        btn.setCompoundDrawablePadding(dp(2));
        return btn;
    }

    /**
     * A dock button: glyph over label, sized for a thumb.
     *
     * <p>Labelled, unlike every control on the taskbar. There is no hover on a
     * touchscreen, so a tooltip has nowhere to appear and a bare glyph is the
     * whole of what the button ever says about itself.
     */
    private TextView dockButton(String glyph, String label, int tint,
                                View.OnClickListener onClick, View.OnClickListener onHold) {
        TextView btn = new TextView(this);
        btn.setText(dockLabel(glyph, label));
        btn.setContentDescription(label);
        btn.setTextColor(tint);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        btn.setLineSpacing(0f, 0.95f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(6), dp(14), dp(6));
        btn.setBackground(tapBackground(0x00000000, theme.hover, 16));
        btn.setOnClickListener(onClick);
        if (onHold != null) {
            btn.setOnLongClickListener(v -> {
                onHold.onClick(v);
                return true;
            });
        }
        return btn;
    }

    /**
     * Glyph over label, in one TextView. Two views would be tidier to read and
     * would cost the button its single content description — a screen reader
     * would announce the emoji, then the word.
     */
    private CharSequence dockLabel(String glyph, String label) {
        SpannableString text = new SpannableString(glyph + "\n" + label);
        text.setSpan(new RelativeSizeSpan(1.7f), 0, glyph.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
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
     * Taskbar's left cluster in the Windows 11 shell: the Back key, alone.
     *
     * Windows puts nothing at that end of the bar, and its centred cluster is
     * the whole point of the layout — but this desktop is Android underneath
     * and Back is not a convenience, it is how half the apps on it are
     * dismissed. Anchored where it cannot push the centred cluster off centre.
     */
    private View buildWin11NavCluster() {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setPadding(dp(compact ? 4 : 8), 0, 0, 0);
        // executed by the PC (adb key inject) via the request queue
        left.addView(win11BarButton(barGlyph("◁"), getString(R.string.lx_back),
                v -> RequestProvider.enqueue("key", "back")));
        return left;
    }

    /**
     * Taskbar's centre in the Windows 11 shell: Start · Search · Task view ·
     * Desktop, then the open apps.
     *
     * The four buttons are Windows 11's own centred group, mapped onto what
     * this desktop actually has: Start opens the Start menu, Search opens it
     * straight into its app list, Task view is the open-apps flyout the DeX
     * shell reaches through its ▢ key, and Desktop — where Windows keeps its
     * Widgets button — brings the wallpaper forward.
     */
    private View buildWin11Cluster() {
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER_VERTICAL);

        ImageView start = new ImageView(this);
        start.setImageDrawable(Win11.logo(theme.accent));
        center.addView(win11BarButton(start, getString(R.string.w11_start),
                v -> toggleDrawer()));
        center.addView(win11BarButton(barGlyph("🔍"), getString(R.string.w11_search),
                v -> showStartSearching()));
        center.addView(win11BarButton(barGlyph("▢"), getString(R.string.w11_task_view),
                v -> toggleRecentsPopup()));
        center.addView(win11BarButton(barGlyph("▭"), getString(R.string.w11_desktop),
                v -> goHome()));

        // The divider separates two clusters that compact has already pushed
        // apart with the row's own spacing — it would only cost width.
        if (!compact) {
            View divider = new View(this);
            divider.setBackgroundColor(theme.divider);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(22));
            divLp.setMargins(dp(8), 0, dp(8), 0);
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

    /** A glyph sized for a Windows 11 taskbar button. */
    private TextView barGlyph(String glyph) {
        TextView glyphView = new TextView(this);
        glyphView.setText(glyph);
        glyphView.setTextColor(theme.textDim);
        glyphView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(compact ? 13 : 15));
        glyphView.setGravity(Gravity.CENTER);
        return glyphView;
    }

    /**
     * A square taskbar button, Windows 11 shaped: the icon centred in a hover
     * fill that is a good deal smaller than the bar, so the row reads as
     * separate buttons rather than one continuous strip.
     *
     * Carries its own LayoutParams — every caller adds it to the same
     * horizontal row, and the size is the button's business, not theirs.
     */
    private View win11BarButton(View content, String description,
                                View.OnClickListener onClick) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(tapBackground(0x00000000, theme.hover, 6));
        button.setContentDescription(description);
        button.setOnClickListener(onClick);
        int icon = dp(compact ? 17 : 20);
        boolean drawn = content instanceof ImageView;
        button.addView(content, new FrameLayout.LayoutParams(
                drawn ? icon : ViewGroup.LayoutParams.WRAP_CONTENT,
                drawn ? icon : ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        int side = dp(compact ? 34 : 40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(dp(1), 0, dp(1), 0);
        button.setLayoutParams(lp);
        return button;
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

        // PC-window fullscreen toggle; executed by the PC via the request
        // queue, icon confirmed by the running broadcast's "fs" extra
        fsButton = navButton("⛶", getString(R.string.lx_fullscreen), v -> {
            RequestProvider.enqueue("fullscreen", "toggle");
            setPcFullscreen(!pcFullscreen); // optimistic — broadcast re-syncs
        });
        tray.addView(fsButton, new LinearLayout.LayoutParams(square, square));
        setPcFullscreen(pcFullscreen);

        // Leaving DeX is the one taskbar action that ends the whole session, so
        // it wears the danger colour and asks first — nothing else here is
        // irreversible.
        //
        // It used to sit at the very corner. The bell has that slot now: the
        // corner is the easiest target on the whole display (you can throw the
        // pointer at it without aiming), and the easiest target should not be
        // "end the session". It is also where every desktop this one dresses as
        // keeps its notifications.
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

        // The clock and the bell are the last two, in that order, and they are
        // a pair: the calendar and the notification list are the two surfaces
        // people reach for by throwing the pointer at the corner of the screen,
        // and every desktop this one dresses as keeps them side by side there.
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
        clockLp.setMargins(dp(2), 0, 0, 0);
        tray.addView(clockWrap, clockLp);

        // Last, so it lands in the corner — see the note on the exit button.
        //
        // Absent entirely when the setting is off, rather than present and
        // inert: "Show notifications on the desktop" is the whole feature, and
        // a bell that opens a panel explaining it has been turned off is not
        // what off means. The switch lives in Settings → Notifications, which
        // is where it goes back on. The stale references are cleared here
        // because this method also runs on every shell rebuild.
        bellButton = null;
        bellBadge = null;
        if (DexNotifications.enabled(this)) {
            LinearLayout.LayoutParams bellLp = new LinearLayout.LayoutParams(square, square);
            bellLp.leftMargin = dp(2);
            tray.addView(buildBell(square), bellLp);
        }
        return tray;
    }

    // ── Tray: notifications ────────────────────────────────────────────────

    /**
     * The tray's bell, with the count of what is waiting drawn over its
     * shoulder.
     *
     * A glyph rather than a framework drawable, unlike the gear and the power
     * button beside it: there is no public bell in android.R, and the internal
     * quick-settings art {@link #sysIcon} finds elsewhere is inconsistently
     * named across OEM builds — a tray icon that is a bell on one phone and
     * nothing on the next is worse than a glyph that is the same everywhere.
     *
     * Always built, even with no notification access: the button is how the
     * user FINDS the grant (the flyout explains it and opens the phone's
     * screen), so hiding it until the grant exists would hide the way in.
     */
    private View buildBell(int square) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(tapBackground(0x00000000, theme.hover, 10));
        wrap.setContentDescription(getString(R.string.lx_notifications));
        wrap.setOnClickListener(v -> toggleNotificationsPopup());

        TextView glyph = new TextView(this);
        glyph.setText("🔔");
        glyph.setTextColor(theme.textDim);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(compact ? 12.5f : 14));
        glyph.setGravity(Gravity.CENTER);
        wrap.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bellBadge = new TextView(this);
        bellBadge.setTextColor(0xFFFFFFFF);
        bellBadge.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(8.5f));
        bellBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bellBadge.setGravity(Gravity.CENTER);
        bellBadge.setIncludeFontPadding(false);
        bellBadge.setBackground(roundedFill(theme.danger, 8));
        bellBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(14), Gravity.TOP | Gravity.END);
        badgeLp.setMargins(0, dp(compact ? 2 : 4), dp(compact ? 1 : 3), 0);
        wrap.addView(bellBadge, badgeLp);

        bellButton = wrap;
        updateBellBadge();
        wrap.setLayoutParams(new LinearLayout.LayoutParams(square, square));
        return wrap;
    }

    /**
     * Redraw the count. Cheap enough to call on every posted notification,
     * which is what the listener does.
     *
     * Hidden rather than zeroed when there is nothing waiting: a permanent "0"
     * over a bell reads as a broken badge. Capped at 9+ because past that the
     * pill is wider than the button it sits on.
     */
    private void updateBellBadge() {
        if (bellBadge == null) return;
        int count = DexNotifications.enabled(this) ? DexNotifications.badgeCount() : 0;
        if (count <= 0) {
            bellBadge.setVisibility(View.GONE);
            return;
        }
        bellBadge.setText(count > 9 ? "9+" : String.valueOf(count));
        bellBadge.setPadding(dp(count > 9 ? 3 : 4), 0, dp(count > 9 ? 3 : 4), 0);
        bellBadge.setMinWidth(dp(14));
        bellBadge.setVisibility(View.VISIBLE);
    }

    /** Built on first use — a session may never see a notification. */
    private NotificationPanel notificationPanel() {
        if (notifications == null) notifications = new NotificationPanel(this);
        return notifications;
    }

    void toggleNotificationsPopup() {
        // The gesture reaches this too, and a gesture must not resurrect a
        // surface the setting has taken off the taskbar.
        if (!DexNotifications.enabled(this)) return;
        if (notifPopup != null && notifPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        notifPopup = makePopup(notificationPanel().build());
        showTrayPopup(notifPopup, Gravity.END);
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
        // Pinned is a view of the desktop, which the user can have rearranged
        // since the last open. ~16 views; cheaper than watching the grid.
        fillStartPinned();
        int pad = dp(compact ? 14 : 48);
        if (android.provider.Settings.canDrawOverlays(this)) {
            Point size = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    // The overlay is laid out inside the safe area, so the
                    // height it may claim is the display less BOTH system bars,
                    // less the taskbar strip it must stop short of.
                    Math.max(dp(200), size.y - topSystemInset() - bottomReserve()),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            // The drawer is the biggest translucent surface we own — blur is
            // what makes it read as glass rather than as a dimmed screenshot.
            //
            // NOT for the Start menu, which covers the same window but paints
            // in only a corner of it: FLAG_BLUR_BEHIND applies to the whole
            // window, so it would blur the entire desktop to frost a panel
            // that is a fifth of it. The panel is translucent either way.
            if (!theme.win11) Glass.apply(this, lp, uiDensity);
            try {
                // The Start menu carries its own padding and sits where its
                // LayoutParams put it; only the full-surface drawer is
                // re-inset for the window it landed in. The overlay window
                // already stops short of the taskbar, so the menu owes it
                // nothing but the gap Windows leaves under the panel.
                if (theme.win11) startPanelBottomMargin(dp(8));
                else drawerPanel.setPadding(pad, drawerTopPad(), pad, dp(12));
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
            // system bars as well as the taskbar strip. The Start menu pays it
            // as a margin instead, since its panel does not fill the window.
            if (theme.win11) {
                startPanelBottomMargin(bottomReserve() + dp(8));
            } else {
                drawerPanel.setPadding(pad, topSystemInset() + drawerTopPad(), pad,
                        bottomReserve() + dp(12));
            }
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

    /**
     * Lift the Start menu off the bottom of its window.
     *
     * Set on every open rather than once at build: the drawer's window is an
     * overlay when the app-op has been granted and the activity's own root
     * when it has not, and only one of those two is already laid out above the
     * taskbar. Getting it wrong either floats the menu a taskbar clear of the
     * bar or slides it underneath.
     */
    private void startPanelBottomMargin(int px) {
        if (drawerPanel == null
                || !(drawerPanel.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) drawerPanel.getLayoutParams();
        if (lp.bottomMargin == px) return;
        lp.bottomMargin = px;
        drawerPanel.setLayoutParams(lp);
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
        // and Start opens on Pinned again, the way Windows does
        startAllApps = false;
    }

    /**
     * The taskbar's Search button: the Start menu, opened straight into its app
     * list with the caret in the search field.
     *
     * Windows opens a search surface of its own here; ours is the same menu on
     * its other page, because that page IS the search — everything this desktop
     * can find is an app.
     */
    private void showStartSearching() {
        dismissPopups();
        if (!drawerShown) showDrawer();
        startAllApps = true;
        updateStartMode();
        if (searchField != null) searchField.requestFocus();
    }

    void dismissPopups() {
        if (recentsPopup != null && recentsPopup.isShowing()) recentsPopup.dismiss();
        if (calendarPopup != null && calendarPopup.isShowing()) calendarPopup.dismiss();
        if (batteryPopup != null && batteryPopup.isShowing()) batteryPopup.dismiss();
        if (qsPopup != null && qsPopup.isShowing()) qsPopup.dismiss();
        if (notifPopup != null && notifPopup.isShowing()) notifPopup.dismiss();
        if (exitPopup != null && exitPopup.isShowing()) exitPopup.dismiss();
        if (widgetPicker != null && widgetPicker.isShowing()) widgetPicker.dismiss();
        if (mediaPopup != null && mediaPopup.isShowing()) mediaPopup.dismiss();
        if (padPopup != null && padPopup.isShowing()) padPopup.dismiss();
        if (homePopup != null && homePopup.isShowing()) homePopup.dismiss();
        recentsPopup = null;
        calendarPopup = null;
        batteryPopup = null;
        qsPopup = null;
        notifPopup = null;
        exitPopup = null;
        widgetPicker = null;
        mediaPopup = null;
        padPopup = null;
        homePopup = null;
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
        // A flyout is anchored to the taskbar, and the taskbar is momentarily
        // absent while the shell rebuilds (a theme change, a density change).
        // Every OTHER caller here is a click on that very bar and cannot see
        // that window — but the notification flyout is also re-opened from a
        // posted notification, which arrives whenever the phone says so.
        if (taskbarView == null || taskbarView.getWindowToken() == null) return;
        // The phone dock is a centred pill, not a full-width bar: an END-
        // anchored flyout would hang off a corner the dock does not reach.
        boolean dock = onPhone();
        popup.showAtLocation(taskbarView,
                Gravity.BOTTOM | (dock ? Gravity.CENTER_HORIZONTAL : horizontalGravity),
                dock ? 0 : dp(8), bottomChrome() + dp(8));
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
     * The phone's own panel, on or off, without disturbing the desktop.
     *
     * The desktop is on a virtual display with a power state of its own and no keyguard,
     * so darkening the phone costs the session nothing — no pause, no reconnect, and none
     * of what the Lock tile beside it brings with it (sleep, keyguard, always-on clock).
     *
     * Straight to the window daemon rather than through the PC's request queue: the work
     * is a framework call the daemon already holds the authority for, and 7191 answers in
     * well under a millisecond. The panel comes back by itself if anything wakes the
     * device, and the daemon restores it if the session ends while it is dark.
     */
    private void setPhoneScreen(boolean on) {
        if (qsWm == null) qsWm = new WmClient();
        final WmClient client = qsWm;
        phoneScreenOn = on;
        client.post(() -> {
            if (client.screen(on)) return;
            runOnUiThread(() -> {
                // Nothing moved, so the next tray build must not claim it did.
                phoneScreenOn = !on;
                Toast.makeText(this, getString(R.string.lx_cannot_screen),
                        Toast.LENGTH_SHORT).show();
            });
        });
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

    /**
     * What the phone is playing, with the buttons to drive it — the card at the
     * top of the quick-settings panel.
     *
     * Null when nothing is playing OR when the notification grant is missing,
     * and the panel treats those the same on purpose: both mean there is no
     * transport to offer, and a card explaining a grant here would be the
     * second copy of an explanation the notification flyout already gives
     * properly.
     */
    private View buildMediaCard(int panelWidth) {
        DexMedia.Now now = DexMedia.now(this);
        if (now == null) return null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(roundedFill(theme.field, 12));
        card.setPadding(dp(10), dp(8), dp(6), dp(8));

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (now.art != null) {
            art.setImageBitmap(now.art);
        } else {
            // No album art is normal — a podcast app, a browser tab. The
            // playing app's own icon says as much as a grey square would.
            try {
                art.setImageDrawable(getPackageManager().getApplicationIcon(now.pkg));
            } catch (Exception ignored) {
            }
        }
        art.setClipToOutline(true);
        art.setBackground(roundedFill(theme.hover, 8));
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        artLp.rightMargin = dp(10);
        card.addView(art, artLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(now.title);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setSelected(true);   // what makes the marquee actually run
        texts.addView(title);
        TextView artist = new TextView(this);
        artist.setText(now.artist.isEmpty()
                ? getString(R.string.lx_media_playing) : now.artist);
        artist.setTextColor(theme.textFaint);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(artist);
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Skip buttons are drawn only where the session says it can take them:
        // a podcast on a live stream has no next track, and an inert button is
        // worse than an absent one.
        if (now.canSkipPrevious) {
            card.addView(mediaButton("⏮", getString(R.string.lx_media_previous),
                    () -> DexMedia.previous(this)));
        }
        final TextView play = mediaButton(now.playing ? "⏸" : "▶",
                getString(now.playing ? R.string.lx_media_pause : R.string.lx_media_play), null);
        play.setOnClickListener(v -> {
            boolean playing = DexMedia.togglePlay(this);
            play.setText(playing ? "⏸" : "▶");
            play.setContentDescription(getString(
                    playing ? R.string.lx_media_pause : R.string.lx_media_play));
        });
        card.addView(play);
        if (now.canSkipNext) {
            card.addView(mediaButton("⏭", getString(R.string.lx_media_next),
                    () -> DexMedia.next(this)));
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);
        return card;
    }

    /** One transport glyph. Carries its own LayoutParams — every caller is the same row. */
    private TextView mediaButton(String glyph, String description, Runnable onClick) {
        TextView button = new TextView(this);
        button.setText(glyph);
        button.setTextColor(theme.text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(tapBackground(0x00000000, theme.hover, 14));
        if (onClick != null) button.setOnClickListener(v -> onClick.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        return button;
    }

    /**
     * One volume slider.
     *
     * Committed live rather than on release, unlike the sliders in the Settings
     * window: those repaint the whole shell on every value, this one moves a
     * number in AudioManager, and a volume control that only takes effect when
     * you let go is a volume control you cannot aim.
     */
    private View buildVolumeRow(int stream, String label, int panelWidth) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.textDim);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        name.setPadding(dp(6), 0, 0, 0);
        wrap.addView(name);

        SeekBar bar = new SeekBar(this);
        bar.setMax(DexMedia.max(this, stream));
        bar.setProgress(DexMedia.volume(this, stream));
        // Tinted to the shell's accent so the slider belongs to this panel
        // rather than to the phone's own theme. Null-checked because the track
        // and thumb come from the platform's SeekBar style, which an OEM
        // framework is free to have replaced with something that supplies
        // neither — and a crash here would take the whole tray down.
        android.graphics.PorterDuffColorFilter tint = new android.graphics.PorterDuffColorFilter(
                theme.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        if (bar.getProgressDrawable() != null) bar.getProgressDrawable().setColorFilter(tint);
        if (bar.getThumb() != null) bar.getThumb().setColorFilter(tint);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** Said once per drag, not once per pixel, when the phone refuses. */
            private boolean complained;

            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser) return;
                if (DexMedia.setVolume(LauncherActivity.this, stream, value) || complained) return;
                complained = true;
                Toast.makeText(LauncherActivity.this,
                        getString(R.string.lx_volume_refused), Toast.LENGTH_LONG).show();
                // Put the thumb back where the phone actually is, so the slider
                // is not left showing a level that was never applied.
                seekBar.setProgress(DexMedia.volume(LauncherActivity.this, stream));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                complained = false;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /**
     * A quick-settings row: a label on the left, the current value and a chevron
     * on the right. The shape a setting takes when it has more than two states,
     * or when tapping it leads somewhere.
     */
    private View qsValueRow(String label, String value, int panelWidth, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(tapBackground(0x00000000, theme.hover, 10));
        row.setOnClickListener(v -> onClick.run());

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView current = new TextView(this);
        current.setText(value);
        current.setTextColor(theme.textFaint);
        current.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        current.setSingleLine(true);
        current.setEllipsize(TextUtils.TruncateAt.END);
        current.setGravity(Gravity.END);
        row.addView(current, new LinearLayout.LayoutParams(dp(130),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(theme.textFaint);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        chevron.setPadding(dp(6), 0, 0, 0);
        row.addView(chevron);

        row.setLayoutParams(new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * Sound output: where the phone's audio goes, and which of the phone's own
     * outputs it comes out of.
     *
     * Two rows because they are two different reasons for "there is no sound",
     * and the first one is the one this desktop causes. scrcpy's default audio
     * source DIVERTS the phone's output to the computer rather than copying it,
     * so a video playing in a window here is silent on the handset — which
     * looks like a bug in the phone and is actually a choice nobody was ever
     * offered.
     *
     * The first row cycles rather than opening a submenu: three values, all of
     * them one word, in a flyout that is already the height of the display.
     */
    private void addSoundOutput(LinearLayout panel, int panelWidth) {
        View divider = new View(this);
        divider.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(panelWidth, dp(1));
        divLp.topMargin = dp(8);
        divLp.bottomMargin = dp(4);
        panel.addView(divider, divLp);

        TextView header = new TextView(this);
        header.setText(getString(R.string.lx_sound_output));
        header.setTextColor(theme.textFaint);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10));
        header.setPadding(dp(10), dp(2), dp(10), dp(4));
        panel.addView(header, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ONE row, and it opens OUR media-output flyout — every place this
        // desktop can send sound, one tap each, applied live.
        //
        // This used to open the phone's own picker instead, on the reasoning
        // that an ordinary app cannot SELECT a route (MODIFY_AUDIO_ROUTING is
        // a signature permission) and a list of our own would be buttons that
        // do nothing. Both facts still hold — but neither applies any more:
        // the forwarding rows are answered by the PC cycling its audio-only
        // scrcpy companion, and the phone's own outputs are switched by the
        // window daemon, which runs at uid 2000 and holds the routing
        // permission this app never will. The platform picker is still one
        // tap away, at the bottom of the flyout, for everything it alone can
        // do — casting, mostly.
        panel.addView(qsValueRow(getString(R.string.lx_media_output),
                DexMedia.modeLabel(this), panelWidth, this::toggleMediaOutputPopup));
    }

    /**
     * The media-output flyout: one flat list of everywhere the sound can go.
     *
     * Three kinds of row behind one look, because that is how a person thinks
     * about it — "play it there" — even though the mechanisms could not be
     * more different:
     * <ul>
     * <li>The two forwarding rows (computer / computer and phone) write the
     *     audio mode; the PC hears the cfg push and cycles its audio-only
     *     scrcpy companion. Audible in a couple of seconds, desktop untouched.</li>
     * <li>The phone's own outputs (speaker, each connected headset) turn
     *     forwarding off and ask the window daemon — uid 2000, which holds
     *     MODIFY_AUDIO_ROUTING — to pin the phone's media to that device.</li>
     * <li>The last row opens the platform's own picker, the one control that
     *     can reach what only the phone knows how to offer (casting targets).</li>
     * </ul>
     */
    private void toggleMediaOutputPopup() {
        if (mediaPopup != null && mediaPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView header = new TextView(this);
        header.setText(getString(R.string.lx_media_output));
        header.setTextColor(theme.textFaint);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        header.setPadding(dp(10), dp(4), dp(10), dp(6));
        panel.addView(header);

        String mode = DexMedia.audioMode(this);
        panel.addView(mediaRow(getString(R.string.lx_audio_computer),
                DexMedia.AUDIO_COMPUTER.equals(mode),
                () -> pickForwarding(DexMedia.AUDIO_COMPUTER)));
        panel.addView(mediaRow(getString(R.string.lx_audio_both),
                DexMedia.AUDIO_BOTH.equals(mode),
                () -> pickForwarding(DexMedia.AUDIO_BOTH)));

        panel.addView(mediaDivider());

        // The phone's own outputs. Which row is lit: only meaningful while
        // sound stays on the phone, and then it is the remembered pick when
        // one exists — MediaRouter cannot see a strategy pin, it names the
        // active Bluetooth device even while the pin routes past it — and the
        // active route by name otherwise. When nothing matches, it is the
        // speaker: the one output with no name of its own.
        java.util.List<DexMedia.Output> outputs = DexMedia.outputs(this);
        int lit = -1;
        if (DexMedia.AUDIO_PHONE.equals(mode)) {
            DexMedia.Output pick = DexMedia.phonePick(this);
            String route = pick != null ? pick.name : DexMedia.outputName(this);
            for (int ix = 0; ix < outputs.size(); ix++) {
                if (outputs.get(ix).name.equalsIgnoreCase(route)) {
                    lit = ix;
                    break;
                }
            }
            if (lit < 0) lit = 0;
        }
        for (int ix = 0; ix < outputs.size(); ix++) {
            final DexMedia.Output out = outputs.get(ix);
            panel.addView(mediaRow(out.name, ix == lit, () -> routeToPhoneOutput(out)));
        }

        panel.addView(mediaDivider());
        panel.addView(mediaRow(getString(R.string.lx_output_more), false,
                this::openPhoneOutputPicker));

        mediaPopup = makePopup(panel);
        showTrayPopup(mediaPopup, Gravity.END);
    }

    /** One flyout row: a check column and a name. The check is the state. */
    private View mediaRow(String label, boolean selected, Runnable onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setBackground(tapBackground(0x00000000, theme.hover, 9));
        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setTextColor(theme.accent);
        check.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        row.addView(check, new LinearLayout.LayoutParams(dp(18),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(dp(230),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> {
            dismissPopups();
            onPick.run();
        });
        return row;
    }

    private View mediaDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        divider.setLayoutParams(lp);
        return divider;
    }

    /**
     * A forwarding pick: the mode does the work (the PC cycles its audio
     * companion), and any phone-output pin is handed back so the phone half
     * of "computer and phone" comes out of whatever the phone would use on
     * its own. Best effort on the unpin: with nothing pinned the daemon
     * answers ERR, which is the same outcome spelled differently.
     */
    private void pickForwarding(String mode) {
        DexMedia.setAudioMode(this, mode);   // also retires the phone pick
        if (qsWm == null) qsWm = new WmClient();
        final WmClient client = qsWm;
        client.post(client::audioRouteClear);
    }

    /**
     * Send the sound to one of the phone's own outputs: the media route
     * pinned to the device by the window daemon — whose uid holds the routing
     * permission this app never will — and only THEN forwarding off. In that
     * order, and the order is the honesty (same shape as
     * {@link #setPhoneScreen}): a pin that failed must not have already
     * silenced the computer and moved the sound somewhere the user did not
     * tap. On failure nothing changes and the platform's own picker opens
     * instead, so the tap still ends somewhere the choice can be made.
     */
    private void routeToPhoneOutput(DexMedia.Output out) {
        if (qsWm == null) qsWm = new WmClient();
        final WmClient client = qsWm;
        client.post(() -> {
            final boolean routed = client.audioRoute(out.type, out.address);
            runOnUiThread(() -> {
                if (routed) {
                    DexMedia.setAudioMode(this, DexMedia.AUDIO_PHONE);
                    DexMedia.rememberPhonePick(this, out);
                } else {
                    DexLog.warn("media", "the daemon could not route to " + out.name
                            + " — offering the phone's own picker");
                    openPhoneOutputPicker();
                }
            });
        });
    }

    /** The platform's own output picker — the pre-flyout behaviour, kept as the fallback. */
    private void openPhoneOutputPicker() {
        if (!DexMedia.openOutputSwitcher(this,
                desktopWindowOptions(desktopWindowRect(dp(560), dp(620))))) {
            Toast.makeText(this, getString(R.string.lx_output_no_switcher),
                    Toast.LENGTH_LONG).show();
        }
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
        tiles.add(qsTile(getString(R.string.lx_phone_screen), null, "📱",
                phoneScreenOn, true,
                this::setPhoneScreen));
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
        // Clamped to the display for the same reason NotificationPanel.fit
        // exists: the tile grid was sized against a 1920dp desktop, and the
        // phone's own screen is 381dp.
        int panelWidth = Math.min(dp(92) * 3, Math.max(dp(220), uiWidthPx - dp(24)));

        // What is playing goes ABOVE the toggles, where every shell this one
        // dresses as puts it — and because it is the one control here that is
        // about right now rather than about a setting.
        View media = buildMediaCard(panelWidth);
        if (media != null) panel.addView(media);

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

        // Volume under the toggles: two sliders, not the platform's eight
        // streams — see DexMedia.STREAMS. Unlike everything above them these
        // need no grant and no PC, so they are the one part of this panel that
        // works on a phone with nothing plugged into it.
        panel.addView(buildVolumeRow(AudioManager.STREAM_MUSIC,
                getString(R.string.lx_volume_media), panelWidth));
        panel.addView(buildVolumeRow(AudioManager.STREAM_RING,
                getString(R.string.lx_volume_ring), panelWidth));

        // Under the sliders, because it answers the question a slider that
        // changed nothing audible leaves behind.
        addSoundOutput(panel, panelWidth);

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

    // ── Dock: the phone's touchpad ──

    /**
     * Turn the touchpad and its pointer on or off.
     *
     * <p>Both grants this needs are the PC's to give over adb, and on a phone
     * that was never plugged into one they may simply be absent. Say which one
     * is missing rather than flipping a switch that then does nothing: a
     * pointer you cannot see and a pointer that cannot click look identical
     * from the outside, and neither looks like a permission.
     */
    private void togglePad() {
        dismissPopups();
        if (pointer == null) pointer = new DexPointer(this);
        if (pointer.showing()) {
            pointer.detach();
            DexPrefs.put(this, DexPrefs.KEY_PAD_ON, false);
            relayoutDock();
            updatePadButton();
            return;
        }
        if (!pointer.canDrawOverlay()) {
            Toast.makeText(this, getString(R.string.lx_pad_no_overlay), Toast.LENGTH_LONG).show();
            return;
        }
        if (!pointer.attach()) {
            Toast.makeText(this, getString(R.string.lx_pad_failed), Toast.LENGTH_LONG).show();
            return;
        }
        DexPrefs.put(this, DexPrefs.KEY_PAD_ON, true);
        relayoutDock();
        updatePadButton();
        // Attached, visible, and unable to click a thing. The pad is still
        // worth leaving up — the accessibility service is granted from the
        // Settings window this very dock opens — but the pointer has to say so
        // itself, because nothing else about it looks broken.
        if (!pointer.canInject()) {
            Toast.makeText(this, getString(R.string.lx_pad_no_service), Toast.LENGTH_LONG).show();
        }
    }

    /** Bring the touchpad back on a fresh desktop if it was on when the last one ended. */
    private void restorePad() {
        if (!onPhone()) return;
        if (!DexPrefs.getBool(this, DexPrefs.KEY_PAD_ON, DexPrefs.DEF_PAD_ON)) return;
        if (pointer == null) pointer = new DexPointer(this);
        if (!pointer.attach()) return;
        relayoutDock();
        updatePadButton();
    }

    /** The Mouse button carries the only state the dock has. */
    private void updatePadButton() {
        if (padButton == null) return;
        boolean on = pointer != null && pointer.showing();
        padButton.setText(dockLabel("🖱", getString(on ? R.string.lx_pad_on : R.string.lx_pad)));
        padButton.setTextColor(on ? theme.accent : theme.text);
        padButton.setBackground(tapBackground(on ? theme.accentSoft : 0x00000000, theme.hover, 16));
    }

    /**
     * The touchpad's settings, on a hold of the Mouse button.
     *
     * <p>The same three dials the Linux viewer's interaction sheet offers for
     * its Mouse method, in the same order and with the same ranges — pointer
     * speed, scroll direction, and how much of the screen the pad takes. They
     * are here rather than in the Settings window because this surface only
     * exists on the phone's display and only while the pad is up; a row in
     * Settings would be a control for something the user cannot see from
     * there, on a desktop where it can never appear.
     */
    private void showPadOptions() {
        if (padPopup != null && padPopup.isShowing()) {
            dismissPopups();
            return;
        }
        dismissPopups();
        hideDrawer();
        padPopup = makePopup(buildPadOptionsView());
        showTrayPopup(padPopup, Gravity.CENTER_HORIZONTAL);
    }

    private View buildPadOptionsView() {
        int panelWidth = dp(280);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView title = new TextView(this);
        title.setText(getString(R.string.lx_pad_title));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView body = new TextView(this);
        body.setText(getString(R.string.lx_pad_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        body.setPadding(0, dp(4), 0, dp(8));
        panel.addView(body, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(padSliderRow(getString(R.string.lx_pad_speed),
                DexPrefs.KEY_PAD_SPEED, DexPointer.MIN_SPEED, DexPointer.MAX_SPEED,
                DexPrefs.DEF_PAD_SPEED, panelWidth));
        panel.addView(padSliderRow(getString(R.string.lx_pad_size),
                DexPrefs.KEY_PAD_HEIGHT, DexPointer.MIN_HEIGHT, DexPointer.MAX_HEIGHT,
                DexPrefs.DEF_PAD_HEIGHT, panelWidth));

        TextView scrollName = new TextView(this);
        scrollName.setText(getString(R.string.lx_pad_scrolling));
        scrollName.setTextColor(theme.textDim);
        scrollName.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        scrollName.setPadding(dp(6), dp(6), 0, dp(4));
        panel.addView(scrollName);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        boolean natural = DexPrefs.getBool(this, DexPrefs.KEY_PAD_NATURAL, DexPrefs.DEF_PAD_NATURAL);
        chips.addView(padChip(getString(R.string.lx_pad_natural), natural, true));
        chips.addView(padChip(getString(R.string.lx_pad_reverse), !natural, false));
        panel.addView(chips, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView foot = new TextView(this);
        foot.setText(getString(R.string.lx_pad_foot));
        foot.setTextColor(theme.textFaint);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        foot.setPadding(dp(6), dp(10), dp(6), dp(2));
        panel.addView(foot, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /**
     * One touchpad dial.
     *
     * <p>Committed on release, not live: both of these rebuild the pad's
     * windows, and doing that once per pixel of a drag would take the very
     * surface the finger is dragging on out from under it.
     */
    private View padSliderRow(String label, String key, int min, int max, int def, int panelWidth) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.textDim);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        name.setPadding(dp(6), 0, 0, 0);
        wrap.addView(name);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min,
                DexPrefs.getInt(this, key, def) - min)));
        // Same null-checked tint as the volume rows, and for the same reason:
        // an OEM SeekBar style is free to supply neither drawable.
        android.graphics.PorterDuffColorFilter tint = new android.graphics.PorterDuffColorFilter(
                theme.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        if (bar.getProgressDrawable() != null) bar.getProgressDrawable().setColorFilter(tint);
        if (bar.getThumb() != null) bar.getThumb().setColorFilter(tint);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                DexPrefs.put(LauncherActivity.this, key, seekBar.getProgress() + min);
                if (pointer == null) return;
                pointer.refresh();
                relayoutDock();
                // A taller pad moves the dock, and this flyout is anchored to
                // the dock — but a PopupWindow does not follow its anchor. Show
                // it again where the dock now is, rather than leave it lying
                // across the pad it just resized.
                if (DexPrefs.KEY_PAD_HEIGHT.equals(key)) {
                    dismissPopups();
                    showPadOptions();
                }
            }
        });
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** Natural / reverse, as a pair of chips — the viewer sheet's own control. */
    private TextView padChip(String label, boolean selected, boolean natural) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextColor(selected ? 0xFFFFFFFF : theme.textDim);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setBackground(selected
                ? tapBackground(theme.accent, lighten(theme.accent), 14)
                : tapBackground(theme.field, theme.hover, 14));
        chip.setOnClickListener(v -> {
            DexPrefs.put(this, DexPrefs.KEY_PAD_NATURAL, natural);
            if (pointer != null) pointer.refresh();
            dismissPopups();
            showPadOptions();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    // ── Dock: the home app ──

    /**
     * Does the phone's Home button / swipe-up gesture come back to this
     * desktop?
     *
     * <p>Only if we hold the home role. The manifest declares
     * {@code category.HOME}, so the shell is ELIGIBLE — but eligibility is not
     * the same as being chosen, and while the phone's own launcher holds the
     * role, Home from an app the desktop launched leaves the desktop entirely.
     * Back does not, which is why it is the one that already behaves.
     *
     * <p>There is no way to take the role from code: it is a user choice the
     * platform guards, and the most an app may do is ask. See
     * {@link #openHomeChooser}.
     *
     * <p>Static and Context-based so the Settings window can ask the same
     * question without a live desktop.
     */
    static boolean isDefaultHome(Context ctx) {
        try {
            ResolveInfo info = ctx.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                    && ctx.getPackageName().equals(info.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ask to become the home app.
     *
     * <p>The role dialog first — one tap, in place, and it names us — falling
     * back to the phone's home-app screen where the role is unavailable (below
     * API 29, and on builds that have taken it out). Neither is something we
     * can answer on the user's behalf, and that is the point: taking over Home
     * is exactly the kind of change that should cost a deliberate yes.
     */
    static void openHomeChooser(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                android.app.role.RoleManager roles =
                        ctx.getSystemService(android.app.role.RoleManager.class);
                if (roles != null
                        && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    Intent ask = roles.createRequestRoleIntent(
                            android.app.role.RoleManager.ROLE_HOME);
                    ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(ask);
                    return;
                }
            } catch (Exception e) {
                DexLog.warn("home", "role request refused — falling back to settings", e);
            }
        }
        try {
            ctx.startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("home", "no home-app screen on this phone", e);
            Toast.makeText(ctx, ctx.getString(R.string.lx_home_no_chooser),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Offer the home role, once, on the phone's own screen.
     *
     * <p>Once: a prompt that returns every session is a prompt people learn to
     * dismiss without reading, and the answer is remembered either way — taking
     * the role settles it, and declining is a decision too. The Settings
     * window's Windows section carries the same action permanently, so "Not
     * now" is never a door that closes.
     */
    private void maybeOfferHome() {
        if (isFinishing() || !onPhone()) return;
        if (isDefaultHome(this)) return;
        if (DexPrefs.getBool(this, DexPrefs.KEY_HOME_ASKED, false)) return;
        if (taskbarView == null || taskbarView.getWindowToken() == null) return;
        DexLog.step("home", "phone's launcher holds the home role — offering to take it");
        dismissPopups();
        homePopup = makePopup(buildHomeView());
        showTrayPopup(homePopup, Gravity.CENTER_HORIZONTAL);
    }

    private View buildHomeView() {
        int panelWidth = dp(280);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText(getString(R.string.lx_home_title));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView body = new TextView(this);
        body.setText(getString(R.string.lx_home_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        body.setPadding(0, dp(6), 0, dp(12));
        panel.addView(body, new LinearLayout.LayoutParams(panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextView later = new TextView(this);
        later.setText(getString(R.string.lx_home_not_now));
        later.setTextColor(theme.textDim);
        later.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        later.setGravity(Gravity.CENTER);
        later.setPadding(dp(16), dp(8), dp(16), dp(8));
        later.setBackground(tapBackground(0x00000000, theme.hover, 10));
        later.setOnClickListener(v -> {
            DexPrefs.put(this, DexPrefs.KEY_HOME_ASKED, true);
            dismissPopups();
        });
        buttons.addView(later);

        TextView confirm = new TextView(this);
        confirm.setText(getString(R.string.lx_home_set));
        confirm.setTextColor(0xFFFFFFFF);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        confirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(dp(18), dp(8), dp(18), dp(8));
        confirm.setBackground(tapBackground(theme.accent, lighten(theme.accent), 10));
        confirm.setOnClickListener(v -> {
            DexPrefs.put(this, DexPrefs.KEY_HOME_ASKED, true);
            dismissPopups();
            openHomeChooser(this);
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmLp.leftMargin = dp(8);
        buttons.addView(confirm, confirmLp);

        panel.addView(buttons, new LinearLayout.LayoutParams(panelWidth,
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
        // The extra sentence only where it is true — and where it warns about
        // the one thing this button does that nothing else here does: send the
        // user through a system chooser. See requestExit.
        body.setText(onPhone() && !pcAlive() && isDefaultHome(this)
                ? getString(R.string.lx_exit_body) + " " + getString(R.string.lx_exit_body_home)
                : getString(R.string.lx_exit_body));
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
        DexLog.step("exit", "exit requested from the " + (onPhone() ? "dock" : "taskbar"));
        RequestProvider.enqueue("exit", "dex");
        // Raised even when nothing is listening: the queue is durable, and a PC
        // that is merely slow to poll must still get the real exit rather than
        // a phone that closed its own window and left the session up.
        //
        // But on the phone's own screen the queue may genuinely have nobody on
        // the other end — the desktop runs unplugged there — and then this is
        // the whole of the exit. Without this branch the dock's one
        // irreversible button would be the one that does nothing at all.
        if (onPhone() && !pcAlive()) {
            DexLog.step("exit", "no PC on the channel — closing the desktop here");
            if (pointer != null) pointer.detach();
            // A home screen cannot close itself: finishing the one the platform
            // has chosen just relaunches it, and Exit DeX would read as a dead
            // button. Hand the role back first — that IS the exit once the
            // desktop is what Home comes back to.
            if (isDefaultHome(this)) {
                DexLog.step("exit", "we hold the home role — handing it back on the way out");
                openHomeChooser(this);
            }
            finishAndRemoveTask();
            return;
        }
        Toast.makeText(this, getString(R.string.lx_exiting), Toast.LENGTH_LONG).show();
    }

    /**
     * Is there a computer driving this session?
     *
     * <p>Two independent answers, because either can be the stale one. The
     * window daemon's desktop display is authoritative but only while the
     * caption service is up and the daemon is answering; the PC's heartbeat
     * (see {@link #pcSeenAt}) needs neither, but lags a session that has only
     * just started. A yes from either is a yes.
     */
    private boolean pcAlive() {
        if (CaptionService.desktopDisplay() > Display.DEFAULT_DISPLAY) return true;
        return pcSeenAt > 0 && SystemClock.uptimeMillis() - pcSeenAt < PC_SILENCE_MS;
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

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setContentDescription(app.label);
            LinearLayout item = taskbarTile(icon);

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
            openAppsRow.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * One tile in the open-apps strip: an icon, and the line under it that says
     * the app is running.
     *
     * Shared by the two things that can be open on this display — an app, whose
     * icon is a Drawable, and one of our own windows, whose icon is an emoji —
     * and by both shells: Windows 11 draws a smaller icon over a longer
     * indicator and tucks it into a tighter corner radius, which is the whole
     * of the difference between them.
     *
     * Built on every app launch, long after the taskbar's own tree pass, so it
     * asks for its pointer here rather than relying on that walk.
     */
    private LinearLayout taskbarTile(View icon) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(compact ? 3 : 5), dp(4), dp(compact ? 3 : 5), dp(3));
        item.setBackground(tapBackground(0x00000000, theme.hover, theme.win11 ? 6 : 10));

        int side = theme.win11 ? dp(compact ? 22 : 26) : dp(compact ? 27 : 34);
        item.addView(icon, new LinearLayout.LayoutParams(side, side));

        View dot = new View(this);
        dot.setBackground(roundedFill(theme.accent, 2));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                theme.win11 ? dp(compact ? 12 : 16) : dp(compact ? 10 : 12), dp(3));
        dotLp.topMargin = dp(theme.win11 ? 5 : 3);
        item.addView(dot, dotLp);

        DexCursors.apply(item, DexCursors.ROLE_HAND);
        return item;
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

            TextView icon = new TextView(this);
            icon.setText(glyph);
            icon.setGravity(Gravity.CENTER);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    theme.win11 ? sp(compact ? 15 : 18) : sp(compact ? 18 : 22));
            icon.setContentDescription(label);
            LinearLayout item = taskbarTile(icon);

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

    /** Centered rect for a desktop window of this size, clamped to the display. */
    Rect desktopWindowRect(int wPx, int hPx) {
        // Settings, Linux, Docker, the Web viewer and the Task Manager all ask
        // for a rect in the high hundreds of dp — sizes chosen for a 1920x1080
        // desktop. On a 381dp-wide phone every one of them clamps to the same
        // nine tenths of the screen, which is a window with a sliver of
        // wallpaper around it and the dock across its foot. Maximize instead,
        // for the reason nextWindowBounds gives.
        if (onPhone()) return nextWindowBounds();
        Point size = displaySize();
        int w = Math.min(wPx, size.x * 9 / 10);
        int h = Math.min(hPx, size.y * 9 / 10);
        int x = (size.x - w) / 2;
        int y = (size.y - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    /**
     * The same rect for one of OUR windows, but put back where that window was
     * last left.
     *
     * Our own windows are keyed on the activity rather than the package,
     * because five of them share one package name — see
     * {@link WindowMemory#keyFor(Context, String, String)}. The centred default
     * below is what a window that has never been moved still gets.
     */
    private Rect desktopWindowRect(Class<?> own, int wPx, int hPx) {
        // Nothing to recall on the phone: every window there opens maximized,
        // and a rect scaled down from a 1920x1080 desktop would land a window
        // somewhere in the middle of a screen that has room for one.
        if (onPhone()) return nextWindowBounds();
        Rect remembered = WindowMemory.recall(this, WindowMemory.keyFor(this, own),
                displaySize(), bottomReserve());
        return remembered != null ? remembered : desktopWindowRect(wPx, hPx);
    }

    /** The desktop display in pixels — what every launch rect is clamped against. */
    private Point displaySize() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        return size;
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

    /**
     * Launch options for a window opened by clicking a notification, or null
     * when no desktop is live.
     *
     * Static, and reached through the live-desktop reference, because the
     * caller is {@link DexNotifications} — a Service, which has no display of
     * its own and would otherwise open the app on the phone's screen. See the
     * note on its {@code send}.
     *
     * The app's remembered rect, so a notification opens its app in the same
     * place the drawer or a taskbar icon would. The cascade is the fallback for
     * an app that has never been moved, exactly as it is for every other
     * launch — a notification is not a special kind of window.
     */
    static Bundle notificationLaunchOptions(String pkg) {
        LauncherActivity desktop = liveDesktop();
        if (desktop == null || pkg == null || pkg.isEmpty()) return null;
        try {
            return desktop.desktopWindowOptions(desktop.windowBoundsFor(pkg));
        } catch (Exception e) {
            DexLog.warn("notifications", "cannot shape a window for " + pkg, e);
            return null;
        }
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
                    desktopWindowOptions(windowBoundsFor(info.provider.getPackageName())));
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
        // AFTER the grid: the Start menu pins what is on the desktop, and the
        // menu itself was built by buildUi before either list existed. A no-op
        // under the DeX shell.
        fillStartPinned();
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
        // Typing is what puts the Start menu on its list page — see
        // updateStartMode. A no-op under the DeX shell.
        updateStartMode();
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
     *
     * The FALLBACK, not the first answer: {@link #windowBoundsFor} asks the
     * window memory before it comes here, and only reaches this when the app
     * has never been moved.
     */
    public Rect nextWindowBounds() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        // The phone's screen has room for exactly one window, so the launch
        // mode does not apply there: a cascade deals 380dp-wide windows down a
        // diagonal that runs off the bottom in three, and "center" leaves a
        // border of wallpaper on all four sides of the only thing on screen.
        // Maximized is the only one of the three that means anything here — and
        // maximized, not fullscreen: it stops above the dock, so the way out
        // stays visible instead of being covered by what it launched.
        String mode = onPhone() ? "maximized"
                : DexPrefs.getString(this, DexPrefs.KEY_LAUNCH_MODE, DexPrefs.DEF_LAUNCH_MODE);
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
        int h = Math.min(size.y - bottomReserve() - dp(16), Math.round(size.y * 0.72f * scale));
        int x;
        int y;
        if ("maximized".equals(mode)) {
            x = dp(2);
            // Zero on a desktop display, which has no status bar — so this is
            // the same rect it has always been there. On the phone it is what
            // keeps the window's caption out from under the clock.
            y = topSystemInset() + dp(2);
            w = size.x - dp(4);
            h = size.y - bottomReserve() - y - dp(2);
        } else if ("center".equals(mode)) {
            x = (size.x - w) / 2;
            y = Math.max(dp(8), (size.y - bottomReserve() - h) / 2);
        } else {
            int step = dp(30);
            x = dp(64) + (cascade % 5) * step;
            y = dp(36) + (cascade % 5) * step;
            cascade++;
        }
        return new Rect(x, y, x + w, y + h);
    }

    /**
     * Where this app's window goes: back where the user last left it, or onto
     * the launch mode when it has never been moved.
     *
     * Asked per app rather than per launch, which is the whole difference
     * between this and {@link #nextWindowBounds()} — and why the fallback is
     * only reached when there is no record: {@code nextWindowBounds} advances
     * the cascade counter, so calling it speculatively would deal an empty slot
     * on every remembered launch and walk the cascade across the display.
     */
    @Override
    public Rect windowBoundsFor(String pkg) {
        if (onPhone()) return nextWindowBounds();   // see desktopWindowRect(Class,…)
        Rect remembered = WindowMemory.recall(this, WindowMemory.keyFor(this, pkg, null),
                displaySize(), bottomReserve());
        return remembered != null ? remembered : nextWindowBounds();
    }

    /** Start an app in a freeform window on the desktop display. */
    private void startWindowed(AppEntry app) {
        final String pkg = app.component.getPackageName();
        Rect bounds = windowBoundsFor(pkg);

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
