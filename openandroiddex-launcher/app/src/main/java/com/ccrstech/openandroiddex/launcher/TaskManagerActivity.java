package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;

/**
 * The desktop's Task Manager: what the phone is spending itself on, and what
 * is open on this display.
 *
 * Two halves, and they come from very different places. The gauges are real
 * measurements from {@link SysStats}. The app list has TWO sources, because no
 * single one can see everything: other apps come from the PC's running-apps
 * broadcast — an ordinary app on a modern Android cannot enumerate anyone
 * else's processes, ActivityManager#getRunningAppProcesses has returned only
 * the caller's own since Android 8 — while OUR windows come from
 * {@link OwnWindows}, because that broadcast is built from visibility and
 * resolved through the package manager, and our package has no launcher entry
 * on this desktop, so the Linux session was invisible here.
 *
 * The per-app cost column comes from the daemon too, for the same reason —
 * and it is RESIDENT memory, not PSS. Measured on device as shell:
 * /proc/<pid>/statm and /proc/<pid>/stat read fine, /proc/<pid>/smaps_rollup
 * does not. RSS counts shared pages in full for every process that maps them,
 * so these figures over-count and will not sum to the memory gauge above; they
 * are for comparing apps with each other, which is what the column is for.
 */
public class TaskManagerActivity extends Activity {

    private static final long POLL_MS = 2_000;

    private DexTheme theme;
    private SysStats stats;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private TextView[] gaugeValues;
    private TextView[] gaugeDetails;
    private ProgressBar[] gaugeBars;
    private LinearLayout appList;
    private TextView appCount;

    /** Packages the PC reports as open on this display, newest reading wins. */
    private final LinkedHashSet<String> running = new LinkedHashSet<>();

    private final BroadcastReceiver runningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String csv = intent.getStringExtra("pkgs");
            LinkedHashSet<String> next = new LinkedHashSet<>();
            if (csv != null) {
                for (String p : csv.split(",")) {
                    if (!p.trim().isEmpty()) next.add(p.trim());
                }
            }
            if (next.equals(running)) return;
            running.clear();
            running.addAll(next);
            rebuildAppList();
        }
    };

    private final OwnWindows.Listener ownWindowsListener = this::rebuildAppList;

    /**
     * Per-app cost, keyed by package: the daemon's last answer, and the answer
     * before it, because processor time is cumulative and only the DIFFERENCE
     * between two readings is a percentage.
     */
    private java.util.Map<String, WmClient.ProcCost> cost = new java.util.HashMap<>();
    private java.util.Map<String, WmClient.ProcCost> prevCost = new java.util.HashMap<>();
    /** What each package's row shows now, so a refresh does not rebuild the list. */
    private final java.util.Map<String, TextView> costViews = new java.util.HashMap<>();
    private WmClient wm;
    private final java.util.concurrent.atomic.AtomicBoolean costBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateGauges();
            refreshCostAsync();
            main.postDelayed(this, POLL_MS);
        }
    };

    /**
     * Ask the daemon what each listed app is costing.
     *
     * On a background thread because it is socket I/O, and one at a time so a
     * slow answer cannot pile up behind the two-second tick. The packages asked
     * about are exactly the ones on screen, which keeps the daemon's /proc scan
     * to a bounded reply instead of a thousand lines.
     */
    private void refreshCostAsync() {
        if (running.isEmpty()) return;
        if (!costBusy.compareAndSet(false, true)) return;
        final java.util.List<String> want = new java.util.ArrayList<>(running);
        new Thread(() -> {
            try {
                if (wm == null) wm = new WmClient();
                java.util.Map<String, WmClient.ProcCost> now = wm.procs(want);
                if (now == null) return;
                main.post(() -> {
                    prevCost = cost;
                    cost = now;
                    applyCosts();
                });
            } finally {
                costBusy.set(false);
            }
        }, "taskmanager-cost").start();
    }

    /**
     * Write the numbers into the rows already on screen.
     *
     * Deliberately not a rebuild: the list is re-made only when its MEMBERSHIP
     * changes, so a row cannot lose a half-finished tap to a two-second timer.
     */
    private void applyCosts() {
        WmClient.ProcCost totalNow = cost.get(WmClient.TOTAL_KEY);
        WmClient.ProcCost totalWas = prevCost.get(WmClient.TOTAL_KEY);
        long totalDelta = (totalNow != null && totalWas != null)
                ? totalNow.jiffies - totalWas.jiffies : 0;
        for (java.util.Map.Entry<String, TextView> e : costViews.entrySet()) {
            WmClient.ProcCost now = cost.get(e.getKey());
            if (now == null) {
                // Listed but not running any process of its own — a window the
                // system has already emptied out.
                e.getValue().setText(s(R.string.tm_not_running));
                continue;
            }
            String text = SysStats.bytes(now.rssBytes);
            WmClient.ProcCost was = prevCost.get(e.getKey());
            if (was != null && totalDelta > 0) {
                long busy = now.jiffies - was.jiffies;
                if (busy >= 0) {
                    // Share of the whole processor, the same denominator the
                    // gauge above uses, so the two agree.
                    int pct = (int) Math.min(100, busy * 100 / totalDelta);
                    text = getString(R.string.tm_cost, pct, text);
                }
            }
            e.getValue().setText(text);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OwnWindows.opened(this);
        theme = DexTheme.of(this);
        stats = new SysStats(this);
        build();
        // Our own windows open and close without any broadcast — same-process
        // registry, same source the taskbar tiles use.
        OwnWindows.setListener(ownWindowsListener);
        IntentFilter filter = new IntentFilter(LauncherActivity.ACTION_RUNNING);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(runningReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(runningReceiver, filter);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OwnWindows.closed(this);
        OwnWindows.clearListener(ownWindowsListener);
        try {
            unregisterReceiver(runningReceiver);
        } catch (Exception ignored) {
        }
    }

    // ── layout ──

    private void build() {
        root = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) finish();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(theme.surface(theme.windowBg(), 0f));
        root.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView title = new TextView(this);
        title.setText(s(R.string.tm_title));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gaugesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gaugesLp.topMargin = dp(14);
        root.addView(gauges, gaugesLp);

        gaugeValues = new TextView[3];
        gaugeDetails = new TextView[3];
        gaugeBars = new ProgressBar[3];
        int[] names = {R.string.tm_cpu, R.string.tm_mem, R.string.tm_disk};
        for (int i = 0; i < 3; i++) gauges.addView(buildGauge(i, names[i]));

        TextView heading = new TextView(this);
        heading.setText(s(R.string.tm_open_apps));
        heading.setTextColor(theme.textFaint);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(20);
        headLp.bottomMargin = dp(6);
        root.addView(heading, headLp);

        appCount = new TextView(this);
        appCount.setTextColor(theme.textFaint);
        appCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        root.addView(appCount);

        ScrollView scroller = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(appList);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(6);
        root.addView(scroller, scrollLp);

        setContentView(root);
        DexFonts.applyTo(this, root);
        DexCursors.decorate(root);
        updateGauges();
        rebuildAppList();
    }

    private View buildGauge(final int index, int nameRes) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(plainFill(theme.field, 14));
        col.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView name = new TextView(this);
        name.setText(s(nameRes));
        name.setTextColor(theme.textFaint);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(name);

        TextView value = new TextView(this);
        value.setTextColor(theme.text);
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(22));
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.topMargin = dp(2);
        col.addView(value, valueLp);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        barLp.topMargin = dp(8);
        col.addView(bar, barLp);

        TextView detail = new TextView(this);
        detail.setTextColor(theme.textFaint);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(6);
        col.addView(detail, detailLp);

        gaugeValues[index] = value;
        gaugeDetails[index] = detail;
        gaugeBars[index] = bar;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) lp.leftMargin = dp(10);
        col.setLayoutParams(lp);
        return col;
    }

    // ── data ──

    private void updateGauges() {
        if (gaugeValues == null) return;
        stats.sample();
        int[] values = {stats.cpuPercent, stats.memPercent, stats.diskPercent};
        String[] details = {
                // The processor has no "X of Y" to show, and inventing one
                // (core count, frequency) would not be what the bar measures.
                stats.cpuPercent < 0 ? s(R.string.tm_cpu_unavailable) : s(R.string.tm_cpu_busy),
                getString(R.string.tm_of, SysStats.bytes(stats.memUsedBytes),
                        SysStats.bytes(stats.memTotalBytes)),
                getString(R.string.tm_of, SysStats.bytes(stats.diskUsedBytes),
                        SysStats.bytes(stats.diskTotalBytes)),
        };
        for (int i = 0; i < 3; i++) {
            int v = values[i];
            gaugeValues[i].setText(v < 0 ? "—" : v + "%");
            gaugeBars[i].setProgress(Math.max(v, 0));
            gaugeBars[i].setProgressTintList(ColorStateList.valueOf(
                    v >= 90 ? theme.danger : theme.accent));
            gaugeDetails[i].setText(details[i]);
        }
    }

    private void rebuildAppList() {
        if (appList == null) return;
        appList.removeAllViews();
        int shown = ownRows() + otherRows();
        // The rows are built here, after onCreate's one pass over the tree, and
        // again on every refresh — so the pointers are re-applied with them.
        DexCursors.decorate(appList);
        appCount.setText(shown == 0
                ? s(R.string.tm_no_apps)
                : getResources().getQuantityString(R.plurals.tm_app_count, shown, shown));
    }

    /**
     * Our own windows — Linux, Settings — which the running broadcast can never
     * describe.
     *
     * That list is built from what is VISIBLE and resolved through the package
     * manager, and our package has no launcher entry on this desktop, so every
     * window of ours resolved to nothing and the Linux session was simply
     * absent from the Task Manager. {@link OwnWindows} is the same in-process
     * registry the taskbar tiles come from, and it knows per WINDOW, which
     * matters because all of ours share one package name.
     */
    private int ownRows() {
        int shown = 0;
        for (String activity : OwnWindows.list()) {
            if (TaskManagerActivity.class.getName().equals(activity)) {
                continue; // a task manager listing itself is noise
            }
            final String label;
            final String glyph;
            if (LinuxActivity.class.getName().equals(activity)) {
                label = getString(R.string.ln_label);
                glyph = "🐧";
            } else if (DockerActivity.class.getName().equals(activity)) {
                label = getString(R.string.dk_label);
                glyph = "🐳";
            } else if (SettingsActivity.class.getName().equals(activity)) {
                label = getString(R.string.settings_label);
                glyph = "⚙";
            } else {
                continue;
            }
            appList.addView(buildOwnRow(activity, label, glyph));
            shown++;
        }
        return shown;
    }

    private int otherRows() {
        PackageManager pm = getPackageManager();
        costViews.clear();
        int shown = 0;
        for (String pkg : running) {
            if (getPackageName().equals(pkg)) continue; // ours are listed above
            CharSequence label = pkg;
            Drawable icon = null;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(info);
                icon = pm.getApplicationIcon(info);
            } catch (Exception ignored) {
            }
            appList.addView(buildRow(pkg, label, icon));
            shown++;
        }
        return shown;
    }

    /**
     * A row for one of our own windows.
     *
     * "End task" is offered only for Linux, and it goes to the window's own
     * close path so the same question gets asked — ending a Linux session is
     * not something to do from a list without warning. The others have no End
     * task at all: force-stopping our package is the one thing here that would
     * take the whole desktop down with it.
     */
    private View buildOwnRow(final String activity, String label, String glyph) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(tapBackground(0x00000000, theme.hover, 10));
        row.setOnClickListener(v -> sendBroadcast(
                new Intent(LauncherActivity.ACTION_RESTORE)
                        .setPackage(getPackageName())
                        .putExtra("pkg", getPackageName())
                        .putExtra("activity", activity)));

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        text.addView(name);
        TextView sub = new TextView(this);
        sub.setText(s(R.string.tm_this_desktop));
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        text.addView(sub);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(12);
        row.addView(text, textLp);

        // Offered for the two windows that own a running machine behind them.
        // Both route through ACTION_CLOSE_WINDOW rather than a task removal so
        // the window can put its "this stops what is running inside" question
        // in front of the end — see CaptionService's onClose.
        if (LinuxActivity.class.getName().equals(activity)
                || DockerActivity.class.getName().equals(activity)) {
            TextView end = new TextView(this);
            end.setText(s(R.string.tm_end_task));
            end.setTextColor(theme.danger);
            end.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
            end.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            end.setGravity(Gravity.CENTER);
            end.setPadding(dp(14), dp(7), dp(14), dp(7));
            end.setBackground(tapBackground(0x00000000, theme.hover, 10));
            end.setOnClickListener(v -> sendBroadcast(
                    new Intent(LauncherActivity.ACTION_CLOSE_WINDOW)
                            .setPackage(getPackageName())
                            .putExtra("activity", activity)));
            row.addView(end);
        }
        return row;
    }

    private View buildRow(final String pkg, CharSequence label, Drawable icon) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(tapBackground(0x00000000, theme.hover, 10));
        // A click is "show me this window", the same meaning as its taskbar icon.
        row.setOnClickListener(v -> switchTo(pkg));

        ImageView iconView = new ImageView(this);
        if (icon != null) iconView.setImageDrawable(icon);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        text.addView(name);
        TextView sub = new TextView(this);
        // The cost line, filled in by applyCosts once the daemon answers. It
        // starts as the package name so the row is never blank, and because a
        // guest with no daemon should still say WHICH app each row is.
        sub.setText(pkg);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        text.addView(sub);
        costViews.put(pkg, sub);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(12);
        row.addView(text, textLp);

        TextView end = new TextView(this);
        end.setText(s(R.string.tm_end_task));
        end.setTextColor(theme.danger);
        end.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        end.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        end.setGravity(Gravity.CENTER);
        end.setPadding(dp(14), dp(7), dp(14), dp(7));
        end.setBackground(tapBackground(0x00000000, theme.hover, 10));
        end.setOnClickListener(v -> endTask(pkg, name.getText()));
        row.addView(end);
        return row;
    }

    /** Bring a window forward — the same thing its taskbar icon does. */
    private void switchTo(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Toast.makeText(this, s(R.string.tm_cannot_switch), Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.tm_cannot_switch), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * End task. The kill itself is the PC's job — an app cannot force-stop
     * another one — so this goes through the same request queue the taskbar's
     * "Close app" uses, and the row disappears on the next running broadcast.
     */
    private void endTask(String pkg, CharSequence label) {
        RequestProvider.enqueue("close", pkg);
        running.remove(pkg);
        rebuildAppList();
        Toast.makeText(this, getString(R.string.lx_closing, label), Toast.LENGTH_SHORT).show();
    }

    // ── measurements and drawables (the launcher's idiom, private copy) ──

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private String s(int res) {
        return getString(res);
    }

    private GradientDrawable plainFill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(theme.radius(radiusDp)));
        return d;
    }

    private Drawable tapBackground(int restColor, int hoverColor, float radiusDp) {
        StateListDrawable content = new StateListDrawable();
        content.addState(new int[]{android.R.attr.state_hovered}, plainFill(hoverColor, radiusDp));
        content.addState(new int[0], plainFill(restColor, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(theme.ripple), content,
                plainFill(0xFFFFFFFF, radiusDp));
    }

    @SuppressWarnings("unused")
    private static int lighten(int color) {
        return Color.argb(Color.alpha(color),
                Math.min(255, Color.red(color) + 24),
                Math.min(255, Color.green(color) + 24),
                Math.min(255, Color.blue(color) + 24));
    }
}
