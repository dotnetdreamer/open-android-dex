package com.ccrstech.openandroiddex.launcher;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.text.format.Formatter;
import android.util.Property;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LinuxOnDroid's home screen: the first thing the Play Store app shows, and
 * the place Linux is started from.
 *
 * <p>Only LinuxOnDroid has it. The desktop's Linux tile and the launcher's own
 * entry in the phone's app list still open the Linux window straight away,
 * because a desktop session has nothing to put in front of Linux. So does
 * {@link LinuxAppActivity}, which LinuxOnDroid keeps as the way in that skips
 * this screen: the notification's, and the one an Open Android DeX desktop will
 * use to open this app's Linux. Like every class here it is compiled into both
 * APKs; only linuxapp's manifest declares it.
 *
 * <p>What it shows is read off disk the way the Linux window reads it
 * (state.env, rt.pid, the tick list, the uninstall and removing markers), plus
 * one fact the window never needed: whether the process that started the
 * runtime is still alive. rt.pid outlives a runtime killed with its process,
 * and any later start of the service brings a {@code :linux} process back,
 * possibly with the same pid, so the answer is the runtime's lock, which the
 * kernel drops when that process dies (see {@link RuntimeLock}). It drives
 * nothing itself: Start opens the Linux
 * window through {@link LinuxAppActivity#openWindow}, Shut down goes through
 * that window when it is open, and everything else is a LinuxService intent or
 * another of the app's own windows. Adding apps is asked here, in a sheet of
 * this screen's own, and never in the Linux window: that window's life is the
 * session's, and closing one opened only to ask a question stopped Linux.
 *
 * <p>The page is a hero (what Linux is doing, and the one button for it) over
 * a column of sections. The things this screen is meant to hold next, saved
 * commands among them, are sections of their own beside the ones in
 * {@link #fillSections}. Built in code, like every window in this app.
 */
public class LinuxHomeActivity extends Activity {

    /** How often the screen re-reads Linux's state while it is in front. */
    private static final long POLL_MS = 1000;
    /** Storage use is a call into installd; it needs no second-by-second freshness. */
    private static final long STORAGE_MS = 30_000;
    /** What the README tells people to keep free for Linux. */
    private static final long SPACE_NEEDED = 3_000_000_000L;
    /**
     * How long Try again keeps the failed screen away while the run it started
     * has yet to write a phase of its own: state.env says "error" until it
     * does. The Linux window's retryPending, with a limit.
     */
    private static final long RETRY_GRACE_MS = 20_000;
    /** How long Shut down keeps "running" away while rt.pid is on its way out. */
    private static final long STOP_GRACE_MS = 8_000;
    /** No phase at all for this long, though onCreate asked for an install: ask once more. */
    private static final long NONE_KICK_MS = 10_000;
    /**
     * An uninstall marked and no {@code :linux} process at work on it for this
     * long: ask again. The service finishes a removal on any start, so this
     * only matters when the first ask never reached it.
     */
    private static final long REMOVE_KICK_MS = 10_000;

    private static final int S_UNSUPPORTED = 0;
    private static final int S_NEW = 1;
    private static final int S_INSTALLING = 2;
    private static final int S_FAILED = 3;
    private static final int S_READY = 4;
    private static final int S_RUNNING = 5;
    private static final int S_REMOVING = 6;

    /** How the last session ended, when it ended by itself: what READY says about it. */
    private static final int ENDED_NO = 0;
    private static final int ENDED_LOGOUT = 1;
    private static final int ENDED_FAILED = 2;
    private static final int ENDED_KILLED = 3;

    /** The product's colours: the icon's amber, and one hue per kind of thing. */
    private static final int AMBER = LinuxHomeViews.AMBER;
    private static final int INK_ON_AMBER = 0xFF1C1403;
    private static final int GREEN = 0xFF4ADE80;
    private static final int BLUE = 0xFF6EA8FF;
    private static final int TEAL = 0xFF4FD1C5;
    private static final int VIOLET = 0xFFB49CFF;
    private static final int RED = 0xFFFF7A7A;
    private static final int DANGER = 0xFFE5484D;
    private static final int GREY = 0xFF7A8699;
    private static final int TITLE = 0xFFF5F7FB;
    private static final int BODY = 0xFFB4BFCF;
    private static final int CARD = 0xFF141B27;
    private static final int CARD_EDGE = 0x14FFFFFF;

    private DexTheme theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean resumed;
    private boolean destroyed;
    /** No motion beyond what the screen needs: Android's animations off, or Reduce quality. */
    private boolean still;
    /** Laid out for a wide window: the terminal beside the words, three tiles a row. */
    private boolean wide;
    private String versionName = "";

    private LinuxHomeViews.Column column;
    /** "Android may close Linux", at the top while the child-process limit is on. */
    private LinearLayout limitCard;
    private View scrim;
    private ImageView moreButton;
    private LinearLayout heroContent;
    private LinearLayout sections;
    private TextView footer;
    private LinuxHomeViews.Glow glowWarm;
    private LinuxHomeViews.Glow glowCool;
    private final List<Animator> drift = new ArrayList<>();

    // The hero's moving parts, replaced whenever the hero is rebuilt.
    private LinuxHomeViews.Terminal terminal;
    private LinuxHomeViews.Dot dot;
    private TextView pillText;
    private TextView spaceNote;
    private View primaryButton;

    /** What the screen shows now, and the two shapes it was built for. */
    private Snapshot shown;
    private String heroShape = "";
    private String sectionShape = "";

    /** Poll thread only, apart from the resets. */
    private volatile long storageAt;
    private volatile long storageBytes = -1;
    private volatile long retryAt;
    private volatile long stopAt;
    /**
     * An install was just asked for over a container that reads as ready
     * (apps added, Reinstall): show it as on its way until the service writes
     * a phase of its own. retryAt's job, for a ready container.
     */
    private volatile long busyAt;
    private long noneSince;
    private boolean noneKicked;
    private long removeKickAt;
    /** Try again on a removal that stopped: Removing until that run has begun. */
    private volatile long removeAgainAt;
    private Dialog dialog;
    /** The Add apps sheet while it is open, and the ticks in it. */
    private Dialog picker;
    private final java.util.LinkedHashSet<String> picks = new java.util.LinkedHashSet<>();

    /** Everything the screen shows, read off disk in one go. */
    private static final class Snapshot {
        int state;
        Linux.Status st;
        boolean installed;
        boolean uninstalled;
        boolean removing;
        /** The {@code :linux} process is alive. */
        boolean linuxAlive;
        /** rt.pid is there AND the runtime's lock is held: its process is alive (RuntimeLock). */
        boolean runtimeUp;
        /** A removal that stopped with files left, and what it left; null otherwise. */
        Linux.RemoveFailure removeFailed;
        /** Android's child-process limit, as far as an app can read it. */
        ChildProcessLimit.State limit = ChildProcessLimit.State.OFF;
        /** The user hid the card about it. */
        boolean limitHidden;
        /** One of the ENDED_ values. */
        int ended;
        final List<String> apps = new ArrayList<>();
        boolean remoteAvailable;
        boolean remoteOn;
        boolean allFiles;
        /** Wall clock the running desktop started at; 0 when not known. */
        long runningSince;
        long freeBytes;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = DexTheme.of(this);
        still = theme.perf || !ValueAnimator.areAnimatorsEnabled();
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // the footer just goes without it
        }
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.windowBg()));
        }
        buildFrame();

        // What the Linux window does as it opens, for the same reasons: an
        // install something cut off picks itself up, a build that carries
        // something new for the guest starts bringing it in, and remote access
        // comes back if the user left it on. Both are nothing at all when there
        // is nothing to do, and neither ever starts a FIRST install: that waits
        // for the app choice, which only the Linux window asks.
        LinuxService.provision(this);
        LinuxService.remoteResume(this);

        // Read once here, on the main thread (a few small files and one binder
        // call), so the first frame is the real screen and not an empty hero.
        // Rebuilt by the system (a change of display size, of font size) it
        // comes back as it was: no rising in, no typing out again.
        boolean fresh = savedInstanceState == null;
        apply(read(), fresh, !fresh);

        pollThread = new HandlerThread("linux-home");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        setMotion(true);
        pollNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        setMotion(false);
        if (pollHandler != null) pollHandler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        if (picker != null) {
            picker.dismiss();
            picker = null;
        }
        for (Animator a : drift) a.cancel();
        drift.clear();
        if (pollThread != null) pollThread.quitSafely();
        // Nothing to stop: this screen is not what keeps Linux running. The
        // Linux window and the service own that, whether this is open or not.
    }

    /**
     * A rotation, a fold, a resize in multi-window: re-laid out in place
     * (configChanges in the manifest) rather than rebuilt, so nothing types
     * itself out again because the phone was turned.
     */
    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // An open sheet or question keeps its place, at the new width.
        if (dialog != null && dialog.isShowing()) sizeDialog(dialog, 420);
        if (picker != null && picker.isShowing()) sizeDialog(picker, 520);
        boolean w = c.screenWidthDp >= 600;
        if (w == wide || shown == null) return;
        wide = w;
        column.setMaxWidth(dp(wide ? 880 : 560));
        heroShape = "";
        sectionShape = "";
        apply(shown, false, true);
    }

    // ── reading ──

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            final Snapshot s = read();
            long now = SystemClock.uptimeMillis();
            if (storageAt == 0 || now - storageAt > STORAGE_MS) {
                storageAt = now;
                storageBytes = appBytes();
            }
            main.post(() -> {
                if (!destroyed) apply(s, false, false);
            });
            pollHandler.removeCallbacks(this);
            if (resumed) pollHandler.postDelayed(this, POLL_MS);
        }
    };

    private void pollNow() {
        if (pollHandler == null) return;
        pollHandler.removeCallbacks(poll);
        pollHandler.post(poll);
    }

    private Snapshot read() {
        Snapshot s = new Snapshot();
        s.st = Linux.readStatus(this);
        s.installed = Linux.isInstalled(this);
        s.uninstalled = Linux.isUninstalled(this);
        s.removing = Linux.isRemoving(this);
        if (s.removing) {
            s.removeFailed = Linux.removeFailure(this);
            // Try again was pressed a moment ago: that run has yet to clear
            // the last one's verdict, and it is a removal under way now.
            if (s.removeFailed != null && removeAgainAt != 0
                    && SystemClock.uptimeMillis() - removeAgainAt < REMOVE_KICK_MS) {
                s.removeFailed = null;
            }
        }
        s.linuxAlive = linuxProcessPid() > 0;
        // The runtime is up only while the process that started it lives:
        // rt.pid survives a runtime killed with its process, and any later
        // start of the service (remote access coming back, say) brings a new
        // :linux up beside that leftover, possibly with the same pid. The
        // runtime's lock is the kernel's answer (RuntimeLock). Only if it
        // cannot be asked at all does a live :linux stand in for it.
        Boolean up = Linux.desktopRunning(this, s.st);
        s.runtimeUp = up != null ? up : s.st.running && s.linuxAlive;
        if (!s.st.running && s.st.sessionEnded) {
            s.ended = ChildProcessLimit.killedByLimit(this, s.st) ? ENDED_KILLED
                    : s.st.sessionExit == 0 ? ENDED_LOGOUT : ENDED_FAILED;
        }
        for (String id : Linux.OPTIONAL_APPS) {
            if (Linux.hasApp(this, id)) s.apps.add(id);
        }
        s.remoteAvailable = LinuxRemote.available(this);
        s.remoteOn = s.remoteAvailable && LinuxRemote.isEnabled(this);
        s.allFiles = Linux.hasAllFiles();
        if (s.st.running) s.runningSince = new File(Linux.root(this), "rt.pid").lastModified();
        s.freeBytes = getFilesDir().getUsableSpace();
        s.limit = ChildProcessLimit.state(this);
        s.limitHidden = homePrefs().getBoolean(PREF_LIMIT_HIDDEN, false);
        s.state = stateOf(s, Linux.needsAppChoice(this));
        return s;
    }

    private int stateOf(Snapshot s, boolean needsChoice) {
        if (!Linux.abiSupported()) return S_UNSUPPORTED;
        // Asked for, and not done until the service says so by clearing the
        // marker: an install in flight has to be stopped first, and the wipe
        // waits its turn behind whatever else the service is doing.
        if (s.removing) return S_REMOVING;
        // The two states in which nothing will be installed until someone says
        // so: never chosen, or chosen away.
        if (s.uninstalled || needsChoice) return S_NEW;
        long now = SystemClock.uptimeMillis();
        String phase = s.st.phase;
        if ("error".equals(phase)) {
            return retryAt != 0 && now - retryAt < RETRY_GRACE_MS ? S_INSTALLING : S_FAILED;
        }
        if ("ready".equals(phase)) {
            // Apps just added, or Reinstall just confirmed: the service has
            // yet to write the phase that says so.
            if (busyAt != 0 && now - busyAt < RETRY_GRACE_MS) return S_INSTALLING;
            boolean up = s.runtimeUp && !(stopAt != 0 && now - stopAt < STOP_GRACE_MS);
            return up ? S_RUNNING : S_READY;
        }
        // A phase on the way to ready, or none yet: the choice is made, so an
        // install is what comes next (onCreate asked for it).
        return S_INSTALLING;
    }

    /**
     * The pid of the {@code :linux} process, which runs the container, or 0
     * when it is not running. Any thread. Only this app's own processes are
     * listed to it, which is all this needs.
     */
    private int linuxProcessPid() {
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    am == null ? null : am.getRunningAppProcesses();
            if (procs == null) return 0;
            String want = getPackageName() + ":linux";
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (want.equals(p.processName)) return p.pid;
            }
        } catch (Exception ignored) {
            // unknown reads as not running, which is the cautious answer
        }
        return 0;
    }

    /**
     * What this app takes on the phone, container included: the number in
     * Settings, from the same place. No permission is needed to ask about
     * yourself. -1 when it cannot be had.
     */
    private long appBytes() {
        try {
            StorageStatsManager ssm = getSystemService(StorageStatsManager.class);
            if (ssm == null) return -1;
            StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT,
                    getPackageName(), android.os.Process.myUserHandle());
            return st.getAppBytes() + st.getDataBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    // ── showing ──

    /**
     * One snapshot onto the screen. Main thread.
     *
     * The hero is rebuilt only when its SHAPE changes (another state, another
     * width) and the sections only when theirs does; a value that moves (the
     * install's progress, the session's uptime, the storage line) is set where
     * it stands. A rebuild would retype the terminal and reset every press
     * state on the page once a second.
     *
     * @param entering the first show: the page rises in
     * @param quiet    a re-layout (a rotation): no fades and no typing
     */
    private void apply(Snapshot s, boolean entering, boolean quiet) {
        if (!"error".equals(s.st.phase)) retryAt = 0;
        if (!"ready".equals(s.st.phase)) busyAt = 0;
        if (!s.st.running) stopAt = 0;
        trackNone(s);
        trackRemoval(s);

        boolean stateChanged = shown != null && shown.state != s.state;
        boolean motion = !still && !quiet;
        String hs = s.state + "|" + wide + "|"
                + (s.state == S_NEW && s.freeBytes < SPACE_NEEDED) + "|"
                + (s.state == S_READY ? s.ended : ENDED_NO) + "|"
                + (s.state == S_REMOVING && s.removeFailed != null);
        if (!hs.equals(heroShape)) {
            heroShape = hs;
            fillHero(s, motion && (entering || stateChanged), motion && stateChanged);
        }
        String ss = s.state + "|" + wide + "|" + s.apps + "|" + s.remoteAvailable + s.remoteOn
                + "|" + s.allFiles;
        if (!ss.equals(sectionShape)) {
            sectionShape = ss;
            fillSections(s, motion && !entering && shown != null);
        }
        // Always there: the privacy policy is in it, and Google Play wants that
        // reachable from inside the app before anything is installed too.
        // showMore offers Reinstall and Uninstall only when this used to show.
        moreButton.setVisibility(View.VISIBLE);
        limitCard.setVisibility(s.state != S_UNSUPPORTED && s.limit != ChildProcessLimit.State.OFF
                && !s.limitHidden ? View.VISIBLE : View.GONE);
        // The uninstall finished while this screen watched: now, and not when
        // it was asked for, is when the storage really is free.
        if (shown != null && shown.state == S_REMOVING && s.state != S_REMOVING
                && s.uninstalled && !entering) {
            Toast.makeText(this, getString(R.string.ln_uninstall_done), Toast.LENGTH_LONG).show();
            storageAt = 0;
        }
        shown = s;
        live(s);
        if (entering && !still) enter();
    }

    /**
     * An uninstall marked and no {@code :linux} process to do it: the ask
     * never reached the service (or the process died in the middle of the
     * wipe). Ask again, now and then, for as long as that stays true; any
     * start finishes it, so a second one is never a second wipe. Not for one
     * that stopped with files left: that one waits for Try again, rather
     * than going round every ten seconds for as long as this screen is open.
     */
    private void trackRemoval(Snapshot s) {
        if (s.state != S_REMOVING || s.removeFailed != null) {
            removeKickAt = 0;
            return;
        }
        if (s.linuxAlive) return;
        long now = SystemClock.uptimeMillis();
        if (removeKickAt != 0 && now - removeKickAt < REMOVE_KICK_MS) return;
        if (removeKickAt == 0) {
            // First seen: give the ask that set the marker its moment.
            removeKickAt = now;
            return;
        }
        removeKickAt = now;
        DexLog.step("linux", "home: uninstall marked but nothing at work on it; asking again");
        LinuxService.uninstall(this);
    }

    /** The values that move, set in place. */
    private void live(Snapshot s) {
        if (pillText != null) pillText.setText(pillWords(s));
        if (terminal != null) {
            if (s.state == S_INSTALLING) {
                terminal.setText(1, installLine(s.st));
                terminal.setText(2, aptProgress(s.st.pct));
            } else if (s.state == S_RUNNING) {
                terminal.setText(1, uptimeLine(s));
            }
        }
        if (spaceNote != null) spaceNote.setText(spaceWords(s));
        footer.setText(footerWords(s));
    }

    /**
     * A phase of "none" that stays: the install onCreate asked for has not
     * begun. Asked once more, as the Linux window does; the service ignores a
     * second ask while the first is under way.
     */
    private void trackNone(Snapshot s) {
        if (s.state != S_INSTALLING || !"none".equals(s.st.phase)) {
            noneSince = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (noneSince == 0) {
            noneSince = now;
        } else if (!noneKicked && now - noneSince > NONE_KICK_MS) {
            noneKicked = true;
            LinuxService.provision(this);
        }
    }

    // ── the frame ──

    /** The parts that live as long as the screen: the page, the bar, the hero's light. */
    private void buildFrame() {
        FrameLayout root = new FrameLayout(this);
        // The page: the window colour, lit from the top by the two colours the
        // hero is lit with, so the hero sits in its own light.
        GradientDrawable warm = new GradientDrawable();
        warm.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        warm.setColors(new int[]{0x1AFFB547, 0x00FFB547});
        warm.setGradientCenter(0.9f, 0f);
        warm.setGradientRadius(dp(440));
        GradientDrawable cool = new GradientDrawable();
        cool.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        cool.setColors(new int[]{0x1A5B7CFF, 0x005B7CFF});
        cool.setGradientCenter(0.05f, 0.04f);
        cool.setGradientRadius(dp(400));
        root.setBackground(new LayerDrawable(new Drawable[]{
                new ColorDrawable(theme.windowBg()), cool, warm}));

        final ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        FrameLayout center = new FrameLayout(this);
        column = new LinuxHomeViews.Column(this);
        column.setMaxWidth(dp(wide ? 880 : 560));
        center.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        scroller.addView(center, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The page scrolls under the status bar (edge to edge, as targetSdk 35
        // draws every window), and this fades it out before it reaches the
        // clock, so text never runs under the icons.
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{theme.windowBg(), theme.windowBg() & 0x00FFFFFF}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            scroller.setPadding(l + dp(20), t + dp(6), r + dp(20), b + dp(28));
            ViewGroup.LayoutParams lp = scrim.getLayoutParams();
            lp.height = t > 0 ? t + dp(12) : 0;
            scrim.setLayoutParams(lp);
            return insets;
        });
        root.requestApplyInsets();

        column.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Android's child-process limit, first thing on the page: with it on,
        // Android can close the desktop, or stop an install part way, and only
        // the user can switch it off (ChildProcessLimit). Shown while it reads
        // as on; apply() decides.
        limitCard = limitCard();
        LinearLayout.LayoutParams limitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        limitLp.topMargin = dp(12);
        column.addView(limitCard, limitLp);
        limitCard.setVisibility(View.GONE);

        // The hero: a deep card, lit by two drifting lights, clipped to its
        // corners so the lights never spill past them.
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B2444, 0xFF0F1528});
        heroBg.setCornerRadius(dp(28));
        hero.setBackground(heroBg);
        hero.setClipToOutline(true);
        // The edge as a foreground, over the lights rather than under them.
        GradientDrawable heroEdge = new GradientDrawable();
        heroEdge.setCornerRadius(dp(28));
        heroEdge.setStroke(Math.max(1, dp(1)), 0x17FFFFFF);
        hero.setForeground(heroEdge);
        glowCool = new LinuxHomeViews.Glow(this, 0x665B7CFF, 0.06f, 0.94f, 0.9f);
        glowWarm = new LinuxHomeViews.Glow(this, 0x70FFB547, 0.92f, 0.06f, 0.85f);
        hero.addView(glowCool, overhang());
        hero.addView(glowWarm, overhang());
        heroContent = new LinearLayout(this);
        heroContent.setOrientation(LinearLayout.VERTICAL);
        hero.addView(heroContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(12);
        column.addView(hero, heroLp);

        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        column.addView(sections, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        footer = text("", 12, theme.textFaint, 400);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(36);
        column.addView(footer, footLp);

        DexFonts.applyTo(this, column);
        DexCursors.decorate(root);
    }

    /**
     * A light's place in the hero: the hero's size and then some on every side.
     * The lights drift by up to about 30 dp, and a light only the hero's size
     * would show its own straight edge inside the card as it moved.
     */
    private FrameLayout.LayoutParams overhang() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int m = -dp(56);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    /** The app's mark and name, and the menu: Settings, the two things that are not everyday, and the privacy policy. */
    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(56));

        ImageView icon = new ImageView(this);
        // The adaptive icon, drawn through the launcher's own mask, so it is
        // the same shape here as in the phone's app list.
        icon.setImageResource(R.mipmap.ic_linux);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView name = text(getString(R.string.ln_label), 20, TITLE, 700);
        name.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(dp(12));
        bar.addView(name, nameLp);

        moreButton = new ImageView(this);
        moreButton.setImageDrawable(tinted(R.drawable.hm_ic_more, theme.textDim));
        moreButton.setScaleType(ImageView.ScaleType.CENTER);
        moreButton.setBackground(pressable(0x00000000, dp(22), 0));
        moreButton.setContentDescription(getString(R.string.hm_more));
        moreButton.setOnClickListener(this::showMore);
        bar.addView(moreButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    // ── the hero ──

    /**
     * The hero for this state. Crossfaded on a change of state, so one screen
     * becomes the next rather than jumping to it.
     */
    private void fillHero(Snapshot s, boolean type, boolean fade) {
        heroContent.animate().cancel();
        if (!fade) {
            heroContent.setAlpha(1f);
            buildHero(s, type);
            return;
        }
        heroContent.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            if (destroyed) return;
            buildHero(s, type);
            heroContent.animate().alpha(1f).setDuration(280).start();
        }).start();
    }

    private void buildHero(Snapshot s, boolean type) {
        heroContent.removeAllViews();
        spaceNote = null;
        primaryButton = null;
        int pad = dp(wide ? 30 : 20);
        heroContent.setPadding(pad, pad, pad, dp(wide ? 30 : 22));

        terminal = new LinuxHomeViews.Terminal(this);
        terminal.setTitle("ubuntu@android: ~");

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(pill(s), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(titleWords(s), wide ? 32 : 28, TITLE, 700);
        title.setLetterSpacing(-0.015f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        words.addView(title, titleLp);

        TextView sub = text(subWords(s), 15, BODY, 400);
        sub.setLineSpacing(0, 1.18f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        words.addView(sub, subLp);

        addActions(words, s);

        if (wide) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(words, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams termLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            termLp.setMarginStart(dp(30));
            row.addView(terminal, termLp);
            heroContent.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            heroContent.addView(terminal, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wordsLp.topMargin = dp(22);
            heroContent.addView(words, wordsLp);
        }

        terminal.show(termLines(s), hasCaret(s.state)
                || (s.state == S_REMOVING && s.removeFailed != null), type);
        terminal.setRunning(resumed);
        dot.setRunning(resumed && !still);
        DexFonts.applyTo(this, words);
        DexCursors.decorate(heroContent);
    }

    /** The status pill: a coloured dot, breathing while something is going on, and a word or two. */
    private View pill(Snapshot s) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(8), dp(5), dp(12), dp(5));
        p.setBackground(fill(0x14FFFFFF, dp(999), 0x14FFFFFF));
        dot = new LinuxHomeViews.Dot(this);
        dot.set(dotColor(s), s.state == S_INSTALLING || s.state == S_RUNNING
                || s.state == S_REMOVING);
        p.addView(dot, new LinearLayout.LayoutParams(dp(16), dp(16)));
        pillText = text(pillWords(s), 12.5f, 0xFFDDE4EE, 500);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(4));
        p.addView(pillText, lp);
        return p;
    }

    /** The button for this state, and what goes under it. */
    private void addActions(LinearLayout words, Snapshot s) {
        View cta = null;
        View second = null;
        switch (s.state) {
            case S_NEW:
                cta = primary(getString(R.string.hm_new_go), R.drawable.hm_ic_download,
                        () -> openLinux(null, true));
                spaceNote = text(spaceWords(s), 12.5f,
                        s.freeBytes < SPACE_NEEDED ? AMBER : theme.textFaint, 400);
                spaceNote.setGravity(Gravity.CENTER_HORIZONTAL);
                second = spaceNote;
                break;
            case S_INSTALLING:
                // The window narrates the install step by step, with Skip and
                // Restart for a step that sticks. This screen only summarises.
                cta = primary(getString(R.string.hm_installing_go), R.drawable.hm_ic_arrow,
                        () -> openLinux(null, true));
                break;
            case S_FAILED:
                cta = primary(getString(R.string.hm_failed_go), 0, this::retry);
                // The window's error screen, which can Skip an app that will not
                // install, and says which step it was.
                second = ghost(getString(R.string.hm_failed_more), theme.textDim,
                        () -> openLinux(null, false));
                break;
            case S_READY:
                // EXTRA_START: if the last session ended by itself, the window
                // would otherwise stop on a screen saying so, with a Start of
                // its own, and this button would be the first of two taps.
                cta = primary(getString(R.string.hm_ready_go), R.drawable.hm_ic_play, () -> {
                    Bundle b = new Bundle();
                    b.putBoolean(LinuxActivity.EXTRA_START, true);
                    openLinux(b, true);
                });
                // Android closed the last session for running too many
                // programs: the hero says so above, and this is how to stop it
                // happening again, the same explanation as the card at the top.
                if (s.ended == ENDED_KILLED && s.limit != ChildProcessLimit.State.OFF) {
                    second = ghost(getString(R.string.hm_limit_fix), theme.textDim,
                            this::showLimitHelp);
                }
                break;
            case S_RUNNING:
                cta = primary(getString(R.string.hm_running_go), R.drawable.hm_ic_arrow,
                        () -> openLinux(null, true));
                second = ghost(getString(R.string.ln_close_shutdown), RED, this::confirmStop);
                break;
            case S_REMOVING:
                // Under way: nothing to press. Stopped with files left: again.
                if (s.removeFailed != null) {
                    cta = primary(getString(R.string.ln_remove_again), 0, this::removeAgain);
                }
                break;
            default:
                break; // unsupported: nothing to press
        }
        if (cta != null) {
            primaryButton = cta;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(22);
            words.addView(cta, lp);
        }
        if (second != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(second == spaceNote ? 12 : 6);
            words.addView(second, lp);
        }
    }

    /**
     * The hero's title. Ready says how the last session ended when it ended by
     * itself, with the Linux window's own words for it: the window no longer
     * gets to say them, because Start goes past that screen (EXTRA_START).
     */
    private String titleWords(Snapshot s) {
        switch (s.state) {
            case S_NEW: return getString(R.string.hm_new_title);
            case S_INSTALLING: return getString(R.string.hm_installing_title);
            case S_FAILED: return getString(R.string.hm_failed_title);
            case S_READY:
                if (s.ended == ENDED_KILLED) return getString(R.string.ln_session_killed);
                if (s.ended == ENDED_FAILED) return getString(R.string.ln_session_failed);
                return getString(R.string.hm_ready_title);
            case S_RUNNING: return getString(R.string.hm_running_title);
            case S_REMOVING:
                return getString(s.removeFailed != null
                        ? R.string.ln_remove_failed_title : R.string.hm_removing_title);
            default: return getString(R.string.hm_unsupported_title);
        }
    }

    private String subWords(Snapshot s) {
        switch (s.state) {
            case S_NEW: return getString(R.string.hm_new_sub);
            case S_INSTALLING: return getString(R.string.hm_installing_sub);
            case S_FAILED: return getString(R.string.hm_failed_sub);
            case S_READY:
                if (s.ended == ENDED_KILLED) return ChildProcessLimit.explain(this, false);
                if (s.ended == ENDED_FAILED) return getString(R.string.ln_session_failed_sub);
                return getString(R.string.hm_ready_sub);
            case S_RUNNING: return getString(R.string.hm_running_sub);
            case S_REMOVING:
                // "Your shared folder stays" only where there is one to stay
                // (Linux.sharedFolderOffered): not in the Play build.
                return s.removeFailed != null
                        ? getString(R.string.ln_remove_failed_sub, leftWords(s.removeFailed))
                        : getString(Linux.sharedFolderOffered(this) ? R.string.hm_removing_sub
                                : R.string.hm_removing_sub_noshare);
            default: return getString(R.string.hm_unsupported_sub);
        }
    }

    /** How much a stopped removal left, as the phone's Settings would write it. */
    private String leftWords(Linux.RemoveFailure f) {
        return Formatter.formatShortFileSize(this, Math.max(0, f.bytes));
    }

    private static int dotColor(Snapshot s) {
        switch (s.state) {
            case S_REMOVING: return s.removeFailed != null ? RED : AMBER;
            case S_INSTALLING: return AMBER;
            case S_FAILED: return RED;
            case S_READY: return s.ended == ENDED_KILLED ? AMBER : GREEN;
            case S_RUNNING: return GREEN;
            default: return GREY;
        }
    }

    private String pillWords(Snapshot s) {
        switch (s.state) {
            case S_NEW: return getString(R.string.hm_pill_new);
            case S_INSTALLING:
                return s.st.pct > 0
                        ? getString(R.string.hm_pill_installing_pct, s.st.pct)
                        : getString(R.string.hm_pill_installing);
            case S_FAILED: return getString(R.string.hm_pill_failed);
            case S_READY:
                return getString(s.ended == ENDED_KILLED
                        ? R.string.hm_pill_failed : R.string.hm_pill_ready);
            case S_RUNNING: return getString(R.string.hm_pill_running);
            case S_REMOVING:
                return getString(s.removeFailed != null
                        ? R.string.hm_pill_failed : R.string.hm_pill_removing);
            default: return getString(R.string.hm_pill_unsupported);
        }
    }

    private String spaceWords(Snapshot s) {
        String free = Formatter.formatShortFileSize(this, Math.max(0, s.freeBytes));
        return getString(s.freeBytes < SPACE_NEEDED ? R.string.hm_new_space_low
                : R.string.hm_new_space, free);
    }

    private String footerWords(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        if (s.state != S_UNSUPPORTED) sb.append("Ubuntu 24.04 LTS");
        long used = storageBytes;
        if (s.installed && used > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(getString(R.string.hm_used, Formatter.formatShortFileSize(this, used)));
        }
        if (versionName != null && !versionName.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(getString(R.string.hm_version, versionName));
        }
        return sb.toString();
    }

    // ── the terminal's lines ──

    /**
     * What the terminal says. The commands are commands, the same in every
     * language, as are package names and {@code uname}'s answer; everything
     * Linux says back is in the user's language, as a guest set to it would
     * say it (apt's own question and progress line included).
     */
    private List<LinuxHomeViews.Terminal.Line> termLines(Snapshot s) {
        List<LinuxHomeViews.Terminal.Line> out = new ArrayList<>();
        switch (s.state) {
            case S_NEW:
                // apt's own question, and the button under it is the answer.
                out.add(line(LinuxHomeViews.Terminal.CMD, "apt install xfce4"));
                out.add(line(LinuxHomeViews.Terminal.OUT, getString(R.string.hm_term_need)));
                out.add(line(LinuxHomeViews.Terminal.OUT, getString(R.string.hm_term_continue)));
                break;
            case S_INSTALLING:
                out.add(line(LinuxHomeViews.Terminal.CMD, "apt install xfce4"));
                out.add(moving(LinuxHomeViews.Terminal.OUT, installLine(s.st)));
                // Built to fill the line at whatever size the type is (cellsAfter).
                out.add(new LinuxHomeViews.Terminal.Line(LinuxHomeViews.Terminal.OUT,
                        aptProgress(s.st.pct), LinuxHomeViews.Terminal.FIT_FILLS));
                break;
            case S_FAILED:
                out.add(line(LinuxHomeViews.Terminal.CMD, "apt install xfce4"));
                out.add(line(LinuxHomeViews.Terminal.ERR,
                        "E: " + LinuxActivity.msgText(this, s.st)));
                out.add(line(LinuxHomeViews.Terminal.CMD, ""));
                break;
            case S_READY:
                if (s.ended == ENDED_KILLED || s.ended == ENDED_FAILED) {
                    // How the last one went, as the shell that ran it would
                    // say, and the same command waiting to go again.
                    out.add(line(LinuxHomeViews.Terminal.CMD, "startxfce4"));
                    out.add(line(LinuxHomeViews.Terminal.ERR, s.ended == ENDED_KILLED
                            ? getString(R.string.hm_term_killed)
                            : getString(R.string.hm_term_exit, s.st.sessionExit)));
                    out.add(line(LinuxHomeViews.Terminal.CMD, "startxfce4"));
                    break;
                }
                // The command typed and waiting for its Enter: Start is the Enter.
                out.add(line(LinuxHomeViews.Terminal.CMD, "uname -sm"));
                out.add(line(LinuxHomeViews.Terminal.OUT, "Linux " + machine()));
                out.add(line(LinuxHomeViews.Terminal.CMD, "startxfce4"));
                break;
            case S_RUNNING:
                out.add(line(LinuxHomeViews.Terminal.CMD, "startxfce4"));
                out.add(moving(LinuxHomeViews.Terminal.GOOD, uptimeLine(s)));
                out.add(line(LinuxHomeViews.Terminal.CMD, ""));
                break;
            case S_REMOVING:
                out.add(line(LinuxHomeViews.Terminal.CMD, "rm -r linux"));
                if (s.removeFailed != null) {
                    // rm's own complaint, and the prompt back for another go.
                    out.add(line(LinuxHomeViews.Terminal.ERR, "rm: " + getString(
                            R.string.hm_term_remove_failed, leftWords(s.removeFailed))));
                    out.add(line(LinuxHomeViews.Terminal.CMD, ""));
                } else {
                    out.add(line(LinuxHomeViews.Terminal.OUT, getString(R.string.hm_term_removing)));
                }
                break;
            default:
                out.add(line(LinuxHomeViews.Terminal.CMD, "uname -m"));
                out.add(line(LinuxHomeViews.Terminal.OUT, machine()));
                out.add(line(LinuxHomeViews.Terminal.ERR,
                        "E: " + getString(R.string.hm_term_needs_64)));
                break;
        }
        return out;
    }

    private static boolean hasCaret(int state) {
        return state == S_NEW || state == S_FAILED || state == S_READY || state == S_RUNNING;
    }

    private static LinuxHomeViews.Terminal.Line line(int kind, String text) {
        return new LinuxHomeViews.Terminal.Line(kind, text);
    }

    /** A line whose value moves while it is shown, set in place by live(): see Terminal.FIT_MOVING. */
    private static LinuxHomeViews.Terminal.Line moving(int kind, String text) {
        return new LinuxHomeViews.Terminal.Line(kind, text, LinuxHomeViews.Terminal.FIT_MOVING);
    }

    /**
     * The step the install is on, as the Linux window words it. Without the
     * step's own percentage, which the window adds: here it would sit one line
     * above the whole install's, and two different numbers read as one wrong one.
     */
    private String installLine(Linux.Status st) {
        if ("none".equals(st.phase) || "error".equals(st.phase)) {
            return getString(R.string.ln_waiting);
        }
        return LinuxActivity.msgText(this, st);
    }

    /**
     * apt's progress line, as wide as the terminal has room for. Measured in
     * the terminal's own face rather than counted in characters: "Progress" is
     * a word of the user's language here, and a CJK one is two cells wide.
     */
    private String aptProgress(int pct) {
        int p = Math.max(0, Math.min(100, pct));
        String head = getString(R.string.hm_term_progress)
                + String.format(Locale.US, " [%3d%%] [", p);
        int width = terminal != null ? terminal.cellsAfter(head + "]") : 32 - head.length();
        width = Math.max(6, width);
        int full = Math.round(width * p / 100f);
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < width; i++) sb.append(i < full ? '#' : '.');
        return sb.append(']').toString();
    }

    private String uptimeLine(Snapshot s) {
        if (s.runningSince <= 0) return getString(R.string.hm_term_running);
        long secs = Math.max(0, (System.currentTimeMillis() - s.runningSince) / 1000);
        return getString(R.string.hm_term_up, String.format(Locale.US, "%d:%02d:%02d",
                secs / 3600, (secs / 60) % 60, secs % 60));
    }

    /** What {@code uname -m} says inside the guest on this phone. */
    private static String machine() {
        switch (Linux.primaryAbi()) {
            case "arm64-v8a": return "aarch64";
            case "x86_64": return "x86_64";
            case "armeabi-v7a": return "armv7l";
            case "x86": return "i686";
            default: return Linux.primaryAbi();
        }
    }

    // ── the sections ──

    /**
     * Everything under the hero, for this state: before the first install,
     * what Linux gives you; once it is there, what is inside it and the tools
     * around it. A new kind of thing on this screen (saved commands, say) is
     * one more section here.
     */
    private void fillSections(Snapshot s, boolean fade) {
        sections.removeAllViews();
        switch (s.state) {
            case S_NEW:
                addWhatYouGet();
                break;
            case S_READY:
            case S_RUNNING:
                addInside(s);
                addTools(s);
                break;
            case S_INSTALLING:
            case S_FAILED:
                addTools(s);
                break;
            default:
                break;
        }
        DexFonts.applyTo(this, sections);
        DexCursors.decorate(sections);
        if (fade) {
            sections.setAlpha(0f);
            // setStartDelay(0): a view's animator keeps the entrance's delay
            // and curve for every animation after it otherwise.
            sections.animate().alpha(1f).setStartDelay(0).setDuration(280)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }
    }

    private void header(String words) {
        TextView h = text(words, 12, theme.textFaint, 600);
        h.setAllCaps(true);
        h.setLetterSpacing(0.09f);
        if (Build.VERSION.SDK_INT >= 28) h.setAccessibilityHeading(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(32);
        lp.bottomMargin = dp(12);
        lp.setMarginStart(dp(6));
        sections.addView(h, lp);
    }

    /** Before the first install: four plain reasons to press the button. */
    private void addWhatYouGet() {
        header(getString(R.string.hm_get_title));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(6), dp(16), dp(6));
        card.setBackground(fill(CARD, dp(24), CARD_EDGE));
        feature(card, R.drawable.hm_ic_desktop, BLUE,
                R.string.hm_get_desktop, R.string.hm_get_desktop_sub);
        feature(card, R.drawable.hm_ic_apps, AMBER,
                R.string.hm_get_apps, R.string.hm_get_apps_sub);
        // Not a promise the Play build can keep: it ships without the
        // permission the folder needs (Linux.sharedFolderOffered).
        if (Linux.sharedFolderOffered(this)) {
            feature(card, R.drawable.hm_ic_folder, TEAL,
                    R.string.hm_get_files, R.string.hm_get_files_sub);
        }
        feature(card, R.drawable.hm_ic_shield, VIOLET,
                R.string.hm_get_safe, R.string.hm_get_safe_sub);
        sections.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void feature(LinearLayout card, int icon, int tint, int titleRes, int subRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(iconChip(icon, tint, 42, 13));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(getString(titleRes), 15.5f, theme.text, 600));
        TextView sub = text(getString(subRes), 13, theme.textFaint, 400);
        sub.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        texts.addView(sub, subLp);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMarginStart(dp(14));
        row.addView(texts, textsLp);
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** What is inside: the system, every optional app that made it in, and a way to add more. */
    private void addInside(Snapshot s) {
        header(getString(R.string.hm_inside_title));
        LinuxHomeViews.Flow flow = new LinuxHomeViews.Flow(this, dp(8), dp(8));
        flow.addView(chip("Ubuntu 24.04"));
        flow.addView(chip("XFCE"));
        for (String id : s.apps) {
            int res = LinuxActivity.appNameRes(id);
            if (res != 0) flow.addView(chip(getString(res)));
        }
        flow.addView(addAppsChip());
        sections.addView(flow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView chip(String words) {
        TextView c = text(words, 13.5f, 0xFFD5DCE6, 500);
        c.setGravity(Gravity.CENTER);
        c.setMinHeight(dp(36));
        c.setPadding(dp(14), dp(7), dp(14), dp(7));
        c.setBackground(fill(0x0FFFFFFF, dp(999), 0x14FFFFFF));
        return c;
    }

    /** The chip that is a button: dashed, in the accent, opening the Add apps sheet. */
    private View addAppsChip() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(dp(36));
        c.setPadding(dp(11), dp(7), dp(14), dp(7));
        GradientDrawable dash = new GradientDrawable();
        dash.setColor(0x00000000);
        dash.setCornerRadius(dp(999));
        dash.setStroke(Math.max(1, Math.round(dp(1) * 1.3f)), 0x99FFC131, dp(4), dp(3));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(999));
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFC131), dash, mask));
        ImageView plus = new ImageView(this);
        plus.setImageDrawable(tinted(R.drawable.hm_ic_plus, AMBER));
        plus.setScaleType(ImageView.ScaleType.FIT_CENTER);
        plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(plus, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView t = text(getString(R.string.hm_inside_add), 13.5f, AMBER, 600);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(6));
        c.addView(t, lp);
        asButton(c, getString(R.string.hm_inside_add));
        c.setOnClickListener(v -> showAppPicker());
        return c;
    }

    /**
     * The tiles: remote access and the shared folder. Use the GPU was one of
     * them; it is on the Settings page now, beside the other switch.
     */
    private void addTools(Snapshot s) {
        List<View> tiles = new ArrayList<>();
        if (s.remoteAvailable) {
            tiles.add(tile(R.drawable.hm_ic_remote, BLUE, getString(R.string.rm_label),
                    getString(s.remoteOn ? R.string.hm_on : R.string.hm_remote_sub_off),
                    s.remoteOn ? GREEN : 0, this::openRemote));
        }
        // Only where this build can have the folder at all (Linux.sharedFolderOffered):
        // in the Play build the grant it asks for does not exist.
        if (Linux.sharedFolderOffered(this)) {
            tiles.add(tile(R.drawable.hm_ic_folder, TEAL, getString(R.string.ln_shared),
                    getString(s.allFiles ? R.string.hm_shared_sub : R.string.hm_shared_sub_grant),
                    s.allFiles ? 0 : AMBER, this::openShared));
        }
        // No heading over nothing: a Play build on a phone without remote
        // access has no tile at all.
        if (tiles.isEmpty()) return;
        header(getString(R.string.hm_tools_title));
        int perRow = wide ? 3 : 2;
        for (int i = 0; i < tiles.size(); i += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int n = Math.min(perRow, tiles.size() - i);
            for (int j = 0; j < n; j++) {
                // MATCH_PARENT in a WRAP_CONTENT row: every tile in a row as
                // tall as its tallest, whatever the length of its words.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (j > 0) lp.setMarginStart(dp(12));
                row.addView(tiles.get(i + j), lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = dp(12);
            sections.addView(row, rowLp);
        }
    }

    /** One tile: an icon, a name and a line under it; the whole tile is the button. */
    private View tile(int icon, int tint, String name, String sub, int subColor, Runnable onTap) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setMinimumHeight(dp(120));
        t.setBackground(pressable(CARD, dp(22), CARD_EDGE));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(iconChip(icon, tint, 40, 12));
        t.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(name, 15, theme.text, 600);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        t.addView(title, titleLp);
        TextView line = text(sub, 12.5f, subColor != 0 ? subColor : theme.textFaint, 400);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lineLp.topMargin = dp(3);
        t.addView(line, lineLp);
        t.setOnClickListener(v -> onTap.run());
        return t;
    }

    // ── what the buttons do ──

    /**
     * Into the Linux window: Start, Open desktop, Install, Show progress. The
     * same way in as the trampoline's, uninstall marker and all — see
     * {@link LinuxAppActivity#openWindow}. {@code grow} opens it out of the
     * button that was pressed.
     */
    private void openLinux(Bundle extras, boolean grow) {
        Bundle options = null;
        View from = primaryButton;
        if (grow && !still && from != null && from.getWidth() > 0) {
            try {
                options = ActivityOptions.makeScaleUpAnimation(from, 0, 0,
                        from.getWidth(), from.getHeight()).toBundle();
            } catch (Exception ignored) {
                // the platform's own opening, then
            }
        }
        DexLog.step("linux", "home: open the Linux window"
                + (shown != null ? " (state " + shown.state + ")" : ""));
        LinuxAppActivity.openWindow(this, extras, options);
    }

    /** The failed screen's Try again: the Linux window's Retry for a failed install. */
    private void retry() {
        DexLog.step("linux", "home: try the install again");
        retryAt = SystemClock.uptimeMillis();
        LinuxService.retry(this);
        pollNow();
    }

    /**
     * Try again, for a removal that stopped with files left. Removing until
     * that run has cleared the last one's verdict, and the storage is said to
     * be free only when a run leaves nothing (apply).
     */
    private void removeAgain() {
        DexLog.step("linux", "home: try the removal again");
        removeAgainAt = SystemClock.uptimeMillis();
        removeKickAt = removeAgainAt;
        LinuxService.uninstall(this);
        pollNow();
    }

    /**
     * Add apps: a sheet of this screen's own, over it.
     *
     * Not the Linux window's chooser, though that asks the same question. The
     * window's life is the session's (closing it stops Linux), so a window
     * opened only to ask this stopped a session by closing, including one a
     * computer had started while the question was up. Nothing here touches a
     * session: the answer is the tick list and a provision, as the window's
     * Install sends, and the install that follows shows on this screen. The
     * apps, their order and their words are the window's own
     * ({@link Linux#OPTIONAL_APPS}, {@link LinuxActivity#appNameRes},
     * {@link LinuxActivity#appSubRes}).
     *
     * What is already in the guest is listed as installed and cannot be
     * unticked: nothing here uninstalls (linux-setup.sh's drop_unwanted says
     * so too), and a box that did nothing when cleared would be a lie.
     */
    private void showAppPicker() {
        if (isFinishing() || (picker != null && picker.isShowing())) return;
        picks.clear();
        final java.util.Set<String> before = new java.util.LinkedHashSet<>();
        final java.util.Set<String> inGuest = new java.util.HashSet<>();
        for (String id : Linux.OPTIONAL_APPS) {
            if (LinuxActivity.appNameRes(id) == 0) continue;
            if (Linux.wantsApp(this, id)) before.add(id);
            if (Linux.hasApp(this, id)) inGuest.add(id);
        }
        picks.addAll(before);

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(fill(0xFF161D2A, dp(26), CARD_EDGE));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPaddingRelative(dp(24), dp(22), dp(24), dp(6));
        TextView title = text(getString(R.string.hm_inside_add), 19, TITLE, 700);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        head.addView(title);
        TextView body = text(getString(R.string.ln_apps_body_again), 14, BODY, 400);
        body.setLineSpacing(0, 1.18f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(8);
        head.addView(body, bodyLp);
        panel.addView(head);

        final TextView go = smallButton(getString(R.string.ln_apps_go), AMBER, INK_ON_AMBER, () -> {
            java.util.Set<String> now = new java.util.LinkedHashSet<>(picks);
            d.dismiss();
            if (now.equals(before)) return; // nothing changed, nothing to do
            DexLog.step("linux", "home: apps chosen " + now);
            Linux.setApps(this, now);
            busyAt = SystemClock.uptimeMillis();
            LinuxService.provision(this);
            pollNow();
        });

        // The list scrolls inside the sheet, so a phone held sideways still
        // reaches the buttons under it.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPaddingRelative(dp(12), dp(6), dp(12), dp(6));
        for (String id : Linux.OPTIONAL_APPS) {
            if (LinuxActivity.appNameRes(id) == 0) continue;
            list.addView(appRow(id, inGuest.contains(id), () -> pickerChanged(go, before)));
        }
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPaddingRelative(dp(20), dp(10), dp(20), dp(18));
        buttons.addView(smallButton(getString(R.string.ln_close_cancel), 0x00000000,
                theme.text, d::dismiss));
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.setMarginStart(dp(8));
        buttons.addView(go, goLp);
        panel.addView(buttons);
        pickerChanged(go, before);

        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        d.setOnDismissListener(x -> {
            if (picker == d) picker = null;
        });
        picker = d;
        d.show();
        sizeDialog(d, 520);
    }

    /**
     * One app in the sheet: a tick, its name and its line. The whole row is
     * the target, as in the window's chooser, and it reads as a checkbox. An
     * app already in the guest has no tick to clear, and says it is installed.
     */
    private View appRow(String id, boolean installed, Runnable changed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPaddingRelative(dp(8), dp(10), dp(12), dp(10));
        if (!installed) row.setBackground(pressable(0x00000000, dp(16), 0));

        final android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setChecked(installed || picks.contains(id));
        box.setEnabled(!installed);
        box.setClickable(false);
        box.setFocusable(false);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int[][] states = {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
        box.setButtonTintList(new ColorStateList(states,
                new int[]{0x8C4ADE80, AMBER, 0x80FFFFFF}));
        row.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        String name = getString(LinuxActivity.appNameRes(id));
        String sub = installed ? getString(R.string.hm_apps_installed)
                : getString(LinuxActivity.appSubRes(id));
        texts.addView(text(name, 15.5f, installed ? theme.textDim : theme.text, 600));
        TextView line = text(sub, 12.5f, installed ? GREEN : theme.textFaint, installed ? 500 : 400);
        line.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lineLp.topMargin = dp(2);
        texts.addView(line, lineLp);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMarginStart(dp(8));
        row.addView(texts, textsLp);

        row.setContentDescription(name + ", " + sub);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.CheckBox.class.getName());
                info.setCheckable(true);
                info.setChecked(box.isChecked());
                info.setEnabled(!installed);
            }
        });
        if (!installed) {
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                boolean on = !box.isChecked();
                box.setChecked(on);
                if (on) picks.add(id); else picks.remove(id);
                changed.run();
            });
        }
        return row;
    }

    /**
     * The sheet's main button for the ticks as they stand: Install when it has
     * something to fetch, Save when the change only drops an app that was
     * never fetched, and nothing to press while nothing has changed.
     */
    private void pickerChanged(TextView go, java.util.Set<String> before) {
        boolean changed = !picks.equals(before);
        boolean fetches = false;
        for (String id : picks) {
            if (!before.contains(id)) fetches = true;
        }
        String label = getString(fetches || !changed ? R.string.ln_apps_go : R.string.hm_apps_save);
        go.setText(label);
        go.setContentDescription(label);
        go.setEnabled(changed);
        // Off is a quiet grey, not the accent faded: amber at half strength
        // over this panel reads as a brown button that might still do something.
        go.setTextColor(changed ? INK_ON_AMBER : theme.textFaint);
        go.setBackground(pressable(changed ? AMBER : 0x14FFFFFF, dp(14), 0));
    }

    /**
     * A dialog's width for this screen: {@code maxDp} at most, with a margin
     * on either side. Its height is its content's, up to the screen's: the
     * sheet's list has the weight, so it is what gives way, and scrolls.
     */
    private void sizeDialog(Dialog d, int maxDp) {
        if (d.getWindow() == null) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        d.getWindow().setLayout(Math.min(screen - dp(32), dp(maxDp)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Developer options, where the child process switch is: the Linux window's way there. */
    // ── Android's child-process limit ("signal 9") ──

    private static final String PREF_LIMIT_HIDDEN = "child_limit_hidden";

    /**
     * What turns the limit off from a computer, as other Linux apps' guides
     * give it: the first keeps Google Play services from undoing the second
     * after a restart (it also stops other remote config updates); the second
     * raises the cap, which is all Android 12 has; the third is the switch
     * itself, from 12L. The Open Android DeX computer app runs the second and
     * third whenever it sees the phone (adb.rs, ensure_child_process_limit_off).
     */
    static final String LIMIT_COMMANDS =
            "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\"\n"
            + "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\"\n"
            + "adb shell \"settings put global settings_enable_monitor_phantom_procs false\"";

    private android.content.SharedPreferences homePrefs() {
        return getSharedPreferences("linux-home", MODE_PRIVATE);
    }

    /**
     * The card at the top of the page while Android's limit on child
     * processes is on. Shown before anything has gone wrong, not only after:
     * the limit stops installs part way too, and it is one switch, once. Hide
     * is there because an app cannot see everything: a cap raised over adb
     * (Android 12 has nothing else) reads as on, for ever.
     */
    private LinearLayout limitCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(fill(0x1FFFB547, dp(22), 0x4DFFB547));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tinted(R.drawable.hm_ic_shield, AMBER));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = text(getString(R.string.hm_limit_title), 16, TITLE, 700);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(10));
        head.addView(title, titleLp);
        card.addView(head);
        TextView body = text(getString(R.string.hm_limit_body), 13.5f, BODY, 400);
        body.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(6);
        card.addView(body, bodyLp);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        buttons.addView(smallButton(getString(R.string.hm_limit_fix), AMBER, INK_ON_AMBER,
                this::showLimitHelp));
        TextView hide = smallButton(getString(R.string.hm_limit_hide), 0x00000000, theme.textDim, () -> {
            DexLog.step("linux", "home: the child-process card hidden");
            homePrefs().edit().putBoolean(PREF_LIMIT_HIDDEN, true).apply();
            limitCard.setVisibility(View.GONE);
        });
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hideLp.setMarginStart(dp(6));
        buttons.addView(hide, hideLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        card.addView(buttons, rowLp);
        return card;
    }

    /**
     * How to switch the limit off, in full: what it does to Linux (other Linux
     * apps show it as "signal 9"), the Developer options switch and where it
     * hides on some Samsung and other phones, the computer app that does it
     * for you, and the adb commands for a phone with no switch at all.
     */
    private void showLimitHelp() {
        if (isFinishing()) return;
        if (dialog != null && dialog.isShowing()) return;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(0xFF161D2A, dp(26), CARD_EDGE));
        panel.addView(text(getString(R.string.hm_limit_dialog_title), 19, TITLE, 700));

        // Long on a small phone, and the buttons must stay in reach: the
        // words scroll, capped at most of the screen, and the buttons do not.
        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.58f);
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        helpText(body, getString(R.string.hm_limit_why), BODY, 400, 10);
        helpText(body, getString(R.string.hm_limit_turn_off), TITLE, 700, 16);
        helpText(body, "1.  " + getString(R.string.hm_limit_step1), BODY, 400, 6);
        helpText(body, "2.  " + getString(R.string.hm_limit_step2), BODY, 400, 6);
        helpText(body, getString(R.string.hm_limit_vendor), theme.textDim, 400, 8);
        helpText(body, getString(R.string.hm_limit_keep_on), theme.textDim, 400, 6);
        helpText(body, getString(R.string.hm_limit_no_switch), TITLE, 700, 16);
        helpText(body, getString(R.string.hm_limit_computer), BODY, 400, 6);
        // The commands as they are typed: monospace, one per line, left to
        // right in every language, scrolling sideways rather than wrapping.
        android.widget.HorizontalScrollView codeScroll = new android.widget.HorizontalScrollView(this);
        codeScroll.setBackground(fill(0xFF0B1019, dp(12), CARD_EDGE));
        codeScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView code = new TextView(this);
        code.setText(LIMIT_COMMANDS);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        code.setTextColor(0xFFDDE4EE);
        code.setTextDirection(View.TEXT_DIRECTION_LTR);
        code.setLineSpacing(0, 1.25f);
        code.setTextIsSelectable(true);
        code.setPadding(dp(12), dp(10), dp(12), dp(10));
        codeScroll.addView(code);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(8);
        body.addView(codeScroll, codeLp);
        helpText(body, getString(R.string.hm_limit_commands_note), theme.textDim, 400, 8);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(4);
        panel.addView(scroll, scrollLp);

        // Straight to the switch when Developer options are on; to About
        // phone, where Build number turns them on, when they are not.
        final boolean devOn = developerOptionsOn();
        TextView go = smallButton(getString(devOn ? R.string.hm_limit_open_dev : R.string.hm_limit_open_about),
                AMBER, INK_ON_AMBER, () -> {
                    d.dismiss();
                    if (devOn) openDeveloperOptions(); else openAboutPhone();
                });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.topMargin = dp(16);
        panel.addView(go, goLp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView copy = smallButton(getString(R.string.hm_limit_copy), 0x00000000, theme.text, () -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", LIMIT_COMMANDS));
                // Android 13 and later say so themselves.
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(this, getString(R.string.hm_limit_copied), Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = smallButton(getString(R.string.sc_close), 0x00000000, theme.text, d::dismiss);
        row.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        panel.addView(row, rowLp);

        DexFonts.applyTo(this, panel);
        // applyTo sets the app's font everywhere; the commands stay monospace.
        code.setTypeface(Typeface.MONOSPACE);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        dialog = d;
        DexLog.step("linux", "home: how to switch the child-process limit off (limit "
                + ChildProcessLimit.state(this) + ", Developer options " + (devOn ? "on" : "off") + ")");
        d.show();
        sizeDialog(d, 460);
    }

    private void helpText(LinearLayout into, String words, int color, int weight, int topDp) {
        TextView t = text(words, weight >= 700 ? 15 : 14, color, weight);
        t.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        lp.setMarginEnd(dp(4));
        into.addView(t, lp);
    }

    private boolean developerOptionsOn() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** About phone, where tapping Build number seven times turns Developer options on. */
    private void openAboutPhone() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("linux", "home: cannot open About phone", e);
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // nothing more to offer
            }
        }
    }

    private void openDeveloperOptions() {
        try {
            startActivity(ChildProcessLimit.switchIntent());
        } catch (Exception e) {
            DexLog.warn("linux", "home: cannot open Developer options", e);
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // nothing more to offer
            }
        }
    }

    private void openRemote() {
        try {
            startActivity(new Intent(this, LinuxRemoteActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("remote", "home: cannot open the Remote access window", e);
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The shared folder in a file manager, or first the grant it cannot be
     * made without — the desktop tile's rule (LauncherActivity.openSharedFolder),
     * on the phone's own screen.
     */
    private void openShared() {
        if (!Linux.ensureSharedDir() && !Linux.hasAllFiles()) {
            WebFiles.requestAllFiles(this, null);
            return;
        }
        Intent intent = Linux.sharedFolderIntent(this);
        if (intent != null) {
            try {
                startActivity(intent);
                return;
            } catch (Exception e) {
                DexLog.warn("linux", "home: file manager refused the shared folder", e);
            }
        }
        Toast.makeText(this, getString(R.string.ln_shared_where,
                Linux.sharedDir().getAbsolutePath()), Toast.LENGTH_LONG).show();
    }

    /** Shut down, asked first: it ends a session that may hold unsaved work. */
    private void confirmStop() {
        confirm(getString(R.string.ln_close_title), getString(R.string.hm_stop_body),
                getString(R.string.ln_close_shutdown), () -> {
                    DexLog.step("linux", "home: shut down");
                    stopAt = SystemClock.uptimeMillis();
                    // Through the window when there is one: its next poll would
                    // otherwise see no runtime and start one again.
                    if (!LinuxActivity.shutdownIfOpen()) LinuxService.stop(this);
                    pollNow();
                });
    }

    /** The published privacy policy, the same page the Play listing links to. */
    static final String PRIVACY_URL =
            "https://github.com/dotnetdreamer/open-android-dex/blob/main/doc/linuxondroid-privacy.md";

    /**
     * The menu: always Settings first; Reinstall and Uninstall, neither
     * everyday and both for good, once there is a Linux that is not on its way
     * out; and always the privacy policy, which Google Play wants reachable
     * from inside the app. A line between the three kinds, where Android
     * draws one (9 and later).
     */
    private void showMore(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 4, 0, getString(R.string.settings_label));
        if (shown != null && shown.installed && shown.state != S_REMOVING
                && Linux.isInstalled(this)) {
            menu.getMenu().add(1, 1, 1, getString(R.string.ln_reinstall));
            menu.getMenu().add(1, 2, 2, getString(R.string.ln_uninstall));
        }
        menu.getMenu().add(2, 3, 3, getString(R.string.hm_privacy));
        if (Build.VERSION.SDK_INT >= 28) menu.getMenu().setGroupDividerEnabled(true);
        // One explicit id per branch and no trailing else, for the reason the
        // desktop tile's menu gives: a new item must never fall into a wipe.
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 4) {
                openSettings();
            } else if (item.getItemId() == 1) {
                confirm(plain(getString(R.string.ln_reinstall)),
                        getString(R.string.ln_reinstall_body),
                        getString(R.string.ln_reinstall_go), () -> {
                            DexLog.step("linux", "home: reinstall");
                            // An install in flight is stopped by the service
                            // rather than waited out; until it writes a phase
                            // of its own, this is an install on its way.
                            busyAt = SystemClock.uptimeMillis();
                            LinuxService.reset(this);
                            storageAt = 0;
                            pollNow();
                        });
            } else if (item.getItemId() == 2) {
                // Without the sentence about the shared folder where this
                // build has none to keep (Linux.sharedFolderOffered).
                confirm(plain(getString(R.string.ln_uninstall)),
                        getString(Linux.sharedFolderOffered(this) ? R.string.ln_uninstall_body
                                : R.string.ln_uninstall_body_noshare),
                        getString(R.string.ln_uninstall_go), () -> {
                            DexLog.step("linux", "home: uninstall");
                            // The window first, if it is open: the wipe takes
                            // its session away, and it would sit on "waiting"
                            // over a container that is going until the end.
                            LinuxActivity.shutdownIfOpen();
                            // Sets the removing marker before it asks, so the
                            // next read is already "Removing". The storage is
                            // said to be free when it is (apply), not now.
                            LinuxService.uninstall(this);
                            removeKickAt = SystemClock.uptimeMillis();
                            pollNow();
                        });
            } else if (item.getItemId() == 3) {
                openPrivacyPolicy();
            }
            return true;
        });
        menu.show();
    }

    /** Settings: Start Linux with the phone, and Use the GPU (LinuxSettingsActivity). */
    private void openSettings() {
        try {
            startActivity(new Intent(this, LinuxSettingsActivity.class));
        } catch (Exception e) {
            DexLog.warn("linux", "home: cannot open Settings", e);
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /** The privacy policy in the browser, or its address when nothing can open it. */
    private void openPrivacyPolicy() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("linux", "home: nothing opens the privacy policy", e);
            Toast.makeText(this, PRIVACY_URL, Toast.LENGTH_LONG).show();
        }
    }

    /** A menu label as a dialog title: without the "…" that says a question follows. */
    private static String plain(String label) {
        return label.endsWith("…") ? label.substring(0, label.length() - 1) : label;
    }

    /**
     * The one question this screen asks, in its own style: a title, what will
     * happen, and a red button for the part that cannot be undone.
     */
    private void confirm(String titleWords, String bodyWords, String goWords, Runnable go) {
        if (isFinishing()) return;
        if (dialog != null && dialog.isShowing()) return;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(0xFF161D2A, dp(26), CARD_EDGE));
        TextView head = text(titleWords, 19, TITLE, 700);
        panel.addView(head);
        TextView body = text(bodyWords, 14.5f, BODY, 400);
        body.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(10);
        bodyLp.setMarginEnd(dp(4));
        panel.addView(body, bodyLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = smallButton(getString(R.string.ln_close_cancel), 0x00000000,
                theme.text, d::dismiss);
        buttons.addView(cancel);
        TextView yes = smallButton(goWords, DANGER, 0xFFFFFFFF, () -> {
            d.dismiss();
            go.run();
        });
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.setMarginStart(dp(8));
        buttons.addView(yes, yesLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(22);
        panel.addView(buttons, rowLp);

        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        dialog = d;
        d.show();
        sizeDialog(d, 420);
    }

    // ── motion ──

    /** The page rising in, piece by piece, the first time it is shown. */
    private void enter() {
        PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(18));
            v.animate().alpha(1f).translationY(0f).setStartDelay(70L * i)
                    .setDuration(560).setInterpolator(ease).start();
        }
    }

    /** Everything that moves on its own: only while the screen is in front. */
    private void setMotion(boolean on) {
        if (terminal != null) terminal.setRunning(on);
        if (dot != null) dot.setRunning(on && !still);
        if (still) return;
        if (!on) {
            for (Animator a : drift) a.pause();
            return;
        }
        if (drift.isEmpty()) {
            drift.add(sway(glowWarm, View.TRANSLATION_X, -dp(30), dp(24), 9_000));
            drift.add(sway(glowWarm, View.TRANSLATION_Y, -dp(12), dp(28), 11_500));
            drift.add(sway(glowWarm, View.ALPHA, 0.7f, 1f, 7_000));
            drift.add(sway(glowCool, View.TRANSLATION_X, dp(28), -dp(22), 10_500));
            drift.add(sway(glowCool, View.TRANSLATION_Y, dp(16), -dp(24), 12_500));
        } else {
            for (Animator a : drift) a.resume();
        }
    }

    private static Animator sway(View v, Property<View, Float> p, float from, float to, long ms) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, p, from, to);
        a.setDuration(ms);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
        return a;
    }

    // ── building blocks ──

    private TextView text(String words, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(words);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(weight(weight));
        // The language's direction, not the words': in Arabic, "LinuxOnDroid"
        // and "Firefox" sit on the right with everything else, where by their
        // own first letter they would sit on the left.
        DexLocale.followLayout(t);
        return t;
    }

    /** A weight of the system face: exact from Android 9, the nearest style before. */
    private static Typeface weight(int w) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, w, false);
        if (w >= 600) return Typeface.DEFAULT_BOLD;
        if (w >= 500) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return Typeface.DEFAULT;
    }

    /**
     * The hero's button: the full width, in the icon's amber, with a small give
     * when it is pressed. Flat on purpose: an elevation shadow on a surface
     * this dark reads as a smudge under the button, not as depth.
     */
    private View primary(String label, int icon, Runnable onTap) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(56));
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFFCE55, 0xFFFFA53A});
        face.setCornerRadius(dp(18));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(18));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), face, mask));
        // Our own animator in place of the platform's, which would add a
        // pressed elevation and with it the shadow this button goes without.
        b.setStateListAnimator(still ? null : pressGive());
        if (icon != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(tinted(icon, INK_ON_AMBER));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.setMarginEnd(dp(10));
            b.addView(iv, lp);
        }
        TextView t = text(label, 16, INK_ON_AMBER, 650);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        b.addView(t);
        asButton(b, label);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
            onTap.run();
        });
        return b;
    }

    /** A quiet button under the main one: words, and a ripple when pressed. */
    private TextView ghost(String label, int color, Runnable onTap) {
        TextView b = text(label, 15, color, 600);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setBackground(pressable(0x00000000, dp(16), 0));
        asButton(b, label);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    private TextView smallButton(String label, int fillColor, int textColor, Runnable onTap) {
        TextView b = text(label, 14.5f, textColor, 600);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setMinWidth(dp(88));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(pressable(fillColor, dp(14), 0));
        asButton(b, label);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    /** Reported as a button, with its label, however it is built. */
    private static void asButton(View v, String label) {
        v.setContentDescription(label);
        v.setFocusable(true);
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    /** Pressed: in to 97%, quickly; released: back out, a little slower. */
    private static StateListAnimator pressGive() {
        StateListAnimator sla = new StateListAnimator();
        ObjectAnimator in = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f));
        in.setDuration(90);
        ObjectAnimator out = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        out.setDuration(180);
        sla.addState(new int[]{android.R.attr.state_pressed}, in);
        sla.addState(new int[0], out);
        return sla;
    }

    private View iconChip(int res, int tint, int sizeDp, int radiusDp) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(tinted(res, tint));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackground(fill((0x26 << 24) | (tint & 0x00FFFFFF), dp(radiusDp), 0));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private Drawable tinted(int res, int color) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    private GradientDrawable fill(int color, float radius, int edge) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (edge != 0) d.setStroke(Math.max(1, dp(1)), edge);
        return d;
    }

    /** A fill that ripples where it is touched, clipped to its own corners. */
    private Drawable pressable(int color, float radius, int edge) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF),
                fill(color, radius, edge), mask);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
