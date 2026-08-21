package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;

/**
 * The desktop's Docker window.
 *
 * What is behind it is a whole virtual machine — Alpine on QEMU's TCG, because
 * this phone's kernel has no PID or user namespaces and its hypervisor only
 * admits protected VMs, so a container cannot be made on Android itself at all
 * (the reasoning, with the measurements, is on {@link Docker}). The window
 * itself is deliberately thin: {@link DockerService} owns the VM, this reads
 * {@link Docker}'s on-disk state and talks to the engine over the forwarded
 * loopback port through {@link DockerApi}.
 *
 * Three panes, because that covers what someone actually does with a Docker
 * host: the containers, the images, and a shell. The shell is the guest's real
 * serial console, so {@code docker run}, {@code docker compose} and everything
 * else lives there rather than being reimplemented out here — and it is a
 * line-at-a-time console rather than a terminal emulator, which is honest
 * about what it is instead of pretending to be one badly.
 *
 * Built in code like the rest of the launcher: the window must be created at
 * whatever density {@code wm density} last put on this display, and an
 * inflated layout brings the phone's density with it.
 */
public class DockerActivity extends Activity {

    /** Status-poll cadence. Files while installing, one HTTP GET once up. */
    private static final long POLL_MS = 2000;
    /**
     * Cooldown between START sends — a cooldown, not a once-flag, for the same
     * reason {@link LinuxActivity} uses one: the service's "already running"
     * check reads rt.pid, which the script writes a beat after the spawn, so
     * re-sending inside that gap would boot a second VM while never re-sending
     * would leave a crashed one dead for as long as the window is open.
     */
    private static final long START_RETRY_MS = 15_000;
    /** Same idea for provisioning, which is far longer and far less frequent. */
    private static final long PROVISION_RETRY_MS = 60_000;
    /** Gap between console attach attempts, so a missing socket is not a spin. */
    private static final long CONSOLE_RETRY_MS = 3_000;

    private static final int PANE_CONTAINERS = 0;
    private static final int PANE_IMAGES = 1;
    private static final int PANE_VOLUMES = 2;
    private static final int PANE_CONSOLE = 3;
    /**
     * One container, opened by clicking its row.
     *
     * Not a fourth tab: it is a place you go INTO from the list and come back
     * out of, and the tab strip stays showing where the list is. Everything in
     * here is the answer to "it is not working and I want to know why", which
     * a row two lines high cannot be.
     */
    private static final int PANE_DETAIL = 4;

    private static final int TAB_LOGS = 0;
    private static final int TAB_INSPECT = 1;
    private static final int TAB_FILES = 2;
    private static final int TAB_EXEC = 3;

    /**
     * What the detail pane is showing.
     *
     * One pane, two subjects, rather than a second pane that would be the same
     * header, the same tab strip and the same file browser with different words
     * in it. A volume has fewer tabs, and its files live inside a container
     * instead of being one — everything else about looking at it is identical.
     */
    private static final int KIND_CONTAINER = 0;
    private static final int KIND_VOLUME = 1;

    /** A volume has two: what is in it, and who is holding it. */
    private static final int VTAB_FILES = 0;
    private static final int VTAB_USERS = 1;

    /** Where a volume is mounted in the container we browse it through. */
    private static final String PEEK_AT = "/volume";

    /** How much of a container's output the Logs tab asks for. */
    private static final int LOG_TAIL = 500;
    /** As much of a file as the viewer will pull out of a container. */
    private static final int FILE_VIEW_MAX = 200_000;

    private DexTheme theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler poll;

    private LinearLayout root;
    private TextView statusLine;
    private TextView subLine;
    private TextView detailLine;
    private ProgressBar bar;
    private Button powerBtn;
    private LinearLayout paneHost;
    private final Button[] tabs = new Button[4];
    private int pane = PANE_CONTAINERS;

    private TextView consoleView;
    private ScrollView consoleScroll;
    private EditText consoleInput;

    /**
     * The changing parts of a container row, kept so a poll can refresh them
     * without rebuilding the list.
     *
     * The list signature is deliberately structural — id and state — so that
     * scrolling is not interrupted every two seconds. But "Exited (0) 10
     * minutes ago" is written into the row at build time and then never
     * touched, so a container that started and exited between two polls left
     * the whole row frozen at the previous state: the press looked like it had
     * done nothing at all.
     */
    private static final class Row {
        TextView sub;
        TextView ports;
        TextView cpu;
        TextView memory;
        View dot;
        Button toggle;
        DockerApi.Container c;
    }

    private final java.util.Map<String, Row> containerRows = new java.util.HashMap<>();

    /** The container the detail pane is showing, and what it last knew of it. */
    private String detailId;
    private DockerApi.Container detailC;
    private List<DockerApi.Container> detailUsers = new java.util.ArrayList<>();
    private int detailTab = TAB_LOGS;
    private TextView detailBody;
    private ScrollView detailScroll;
    private TextView detailStatus;
    private LinearLayout detailActions;
    private final Button[] detailTabs = new Button[4];
    private EditText execInput;
    /** Last painted header state, so a poll that changed nothing repaints nothing. */
    private String detailSig = "";
    /** Written by the poll, read on main: what the open container is costing. */
    private volatile DockerApi.Stats detailStats;
    /** The same, for every running container in the list. */
    private volatile java.util.Map<String, DockerApi.Stats> listStats =
            java.util.Collections.emptyMap();
    /** Ports docker-rt.sh forwards, read once from the script on disk. */
    private List<Integer> forwarded = java.util.Collections.emptyList();
    private int ticks;
    private int detailKind = KIND_CONTAINER;
    private DockerApi.Volume detailV;
    /**
     * The throwaway container started to look inside an idle volume, if any.
     *
     * Torn down when the pane closes: it is scaffolding, and one left behind
     * would sit in the container list looking like something the user made.
     */
    private String peekId;

    /** Where the Files tab is looking: which container, and from what root. */
    private String filesExecId;
    private String filesRoot = "/";
    private String filesPath = "/";
    private LinearLayout detailList;
    private TextView filesBar;
    /** True while the log view is pinned to the bottom, so a refresh follows. */
    private boolean logFollow = true;

    /** Volatile: written by the poll thread, read by {@link #render} on main. */
    private volatile long startSentAt;
    private long provisionSentAt;
    /** When the VM was first seen alive, for the "… (42s)" on the slow stages. */
    private long runningSince;
    private int port;
    /** Last rendered engine state, so a poll only rebuilds when it changed. */
    private String lastSignature = "";

    private ConsoleLink console;
    /**
     * A link that has been told to go but has not stopped yet.
     *
     * A connect that is queued behind another client cannot be cancelled — the
     * thread is inside connect() with no socket to close — so the only safe
     * thing is to refuse to start a second one on top of it. Stacking them is
     * what turned one busy handoff into a queue of them.
     */
    private ConsoleLink zombie;
    /** Earliest elapsed-time we may try to attach again; see {@link #attachConsole}. */
    private long consoleRetryAt;
    /** So a console that simply is not there says so once, not once per poll. */
    private boolean saidDetached;
    /**
     * The console scrollback, kept here rather than in the TextView.
     *
     * The link now outlives the pane, so something that is not a view has to
     * hold what it says while the user is looking at Containers.
     */
    private final StringBuilder consoleBuffer = new StringBuilder();

    /**
     * The caption's ✕ arrives here rather than removing the task, so the
     * question can be asked first — see {@link CaptionService}'s onClose.
     * Filtered by activity name because the Linux window shares this process
     * and this broadcast.
     */
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String who = i.getStringExtra("activity");
            if (who != null && !DockerActivity.class.getName().equals(who)) return;
            confirmClose();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The taskbar's only way to know this window exists — our own package
        // has no icon on this desktop.
        OwnWindows.opened(this);
        theme = DexTheme.of(this);
        port = Docker.enginePort(this);
        forwarded = Docker.forwardedPorts(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new android.graphics.drawable.ColorDrawable(theme.windowBg()));
        setContentView(root);

        buildChrome();
        setPane(PANE_CONTAINERS);

        IntentFilter closeFilter = new IntentFilter(LauncherActivity.ACTION_CLOSE_WINDOW);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, closeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, closeFilter);
        }
        DexCursors.decorate(root);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Dragged narrower or wider: how many columns fit has changed, and the
        // list only rebuilds when its signature does.
        lastSignature = "";
        if (poll != null) poll.post(this::tick);
    }

    @Override
    protected void onStart() {
        super.onStart();
        pollThread = new HandlerThread("docker-poll");
        pollThread.start();
        poll = new Handler(pollThread.getLooper());
        poll.post(this::tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Minimising the window must NOT stop the VM: containers are meant to
        // keep running while the user does something else, which is the whole
        // difference between putting this aside and shutting it down.
        if (peekId != null) {
            removePeek(peekId);
            peekId = null;
        }
        if (poll != null) poll.removeCallbacksAndMessages(null);
        if (pollThread != null) pollThread.quitSafely();
        pollThread = null;
        poll = null;
        // The console is NOT dropped here. Minimising is the case this window
        // is built around, and every detach costs a handoff of the one client
        // slot QEMU serves — the buffer keeps filling meanwhile.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OwnWindows.closed(this);
        try {
            unregisterReceiver(closeReceiver);
        } catch (Exception ignored) {
        }
        closeConsole();
    }

    // ── chrome ──

    private void buildChrome() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(16), dp(8));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        statusLine = new TextView(this);
        statusLine.setTextColor(theme.text);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        statusLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusLine.setText(getString(R.string.dk_preparing));
        titles.addView(statusLine);

        subLine = new TextView(this);
        subLine.setTextColor(theme.textDim);
        subLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        titles.addView(subLine);

        // The guest's own last word, verbatim.
        //
        // Between "installing" and "the engine answered" there are minutes in
        // which the only thing that knows anything is the VM's console, and a
        // stage name alone made that look like a hang. One live line of it is
        // the difference between "stuck" and "unpacking the 214th package".
        detailLine = new TextView(this);
        detailLine.setTextColor(theme.textFaint);
        detailLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        detailLine.setTypeface(Typeface.MONOSPACE);
        detailLine.setSingleLine(true);
        detailLine.setEllipsize(TextUtils.TruncateAt.START);
        titles.addView(detailLine);

        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        powerBtn = flatButton(getString(R.string.dk_start));
        powerBtn.setOnClickListener(v -> onPower());
        head.addView(powerBtn);

        root.addView(head);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(10), 0, dp(10), dp(6));
        String[] labels = {
                getString(R.string.dk_tab_containers),
                getString(R.string.dk_tab_images),
                getString(R.string.dk_tab_volumes),
                getString(R.string.dk_tab_console),
        };
        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            tabs[i] = flatButton(labels[i]);
            tabs[i].setOnClickListener(v -> setPane(which));
            tabRow.addView(tabs[i]);
        }
        root.addView(tabRow);

        View rule = new View(this);
        rule.setBackgroundColor(theme.divider);
        root.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        paneHost = new LinearLayout(this);
        paneHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(paneHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void setPane(int which) {
        pane = which;
        for (int i = 0; i < tabs.length; i++) {
            // The detail pane is entered FROM the container list and returns to
            // it, so the strip keeps pointing there while you are inside one.
            int lit = which != PANE_DETAIL ? which
                    : detailKind == KIND_VOLUME ? PANE_VOLUMES : PANE_CONTAINERS;
            tabs[i].setTextColor(i == lit ? theme.accent : theme.textDim);
        }
        paneHost.removeAllViews();
        if (which == PANE_DETAIL) {
            consoleView = null;
            consoleScroll = null;
            consoleInput = null;
            showDetail();
            lastSignature = "";
            if (poll != null) poll.post(this::tick);
            return;
        }
        detailId = null;
        detailC = null;
        detailV = null;
        detailBody = null;
        detailScroll = null;
        detailList = null;
        execInput = null;
        filesExecId = null;
        if (peekId != null) {
            removePeek(peekId);
            peekId = null;
        }
        if (which == PANE_CONSOLE) {
            paneHost.addView(buildConsolePane(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            openConsole();
        } else {
            // The link deliberately stays up — see openConsole. Only the views
            // go, and appendConsole keeps filling the buffer behind them.
            consoleView = null;
            consoleScroll = null;
            consoleInput = null;
            lastSignature = ""; // force a repaint of the list we just switched to
            if (poll != null) poll.post(this::tick);
        }
    }

    // ── the poll ──

    private void tick() {
        if (poll == null) return;
        ticks++;
        Docker.Status st = Docker.readStatus(this);
        long now = android.os.SystemClock.elapsedRealtime();
        boolean provisioning = Docker.needsProvision(this);

        if (provisioning) {
            // On a cooldown rather than once, and rather than "only while the
            // phase is not already pushing": a service killed mid-download
            // leaves that phase behind forever, and a window that trusted it
            // would sit on a frozen progress bar with nothing left to nudge it.
            // The service itself no-ops while a run is genuinely in flight.
            if (now - provisionSentAt > PROVISION_RETRY_MS) {
                provisionSentAt = now;
                DockerService.provision(this);
            }
        } else if (!st.running && !"error".equals(st.phase) && !st.died) {
            // Not after a kill. A machine Android just took away for memory
            // would be restarted straight into the same wall, and an automatic
            // retry loop is a worse thing to leave running on someone's phone
            // than a button that says Start.
            // Opening the window is the ask. Nobody comes to a Docker window to
            // press Start — they come because they want the engine — and the
            // cooldown is what keeps that from becoming a boot loop.
            if (now - startSentAt > START_RETRY_MS) {
                startSentAt = now;
                DockerService.start(this);
            }
        }

        String engineVersion = null;
        List<DockerApi.Container> containers = null;
        List<DockerApi.Image> images = null;
        List<DockerApi.Volume> volumes = null;
        if (st.running && "ready".equals(st.phase)) {
            engineVersion = DockerApi.version(port);
            if (engineVersion != null) {
                if (pane == PANE_CONTAINERS || pane == PANE_DETAIL) {
                    containers = DockerApi.containers(port);
                    if (pane == PANE_CONTAINERS && ticks % 3 == 0) {
                        listStats = DockerApi.statsOf(port, containers);
                    }
                    // Every third tick, and only for the one on screen: a
                    // sample costs the engine a second of waiting by design.
                    if (pane == PANE_DETAIL && detailKind == KIND_CONTAINER
                            && detailId != null && ticks % 3 == 0) {
                        detailStats = DockerApi.stats(port, detailId);
                    }
                    if (pane == PANE_DETAIL && detailKind == KIND_VOLUME) {
                        volumes = DockerApi.volumes(port);
                    }
                } else if (pane == PANE_IMAGES) {
                    images = DockerApi.images(port);
                    // Also the containers: which images are in use, and what is
                    // holding the one you just tried to delete, is half of what
                    // this pane is for. One more GET on loopback.
                    containers = DockerApi.containers(port);
                } else if (pane == PANE_VOLUMES) {
                    volumes = DockerApi.volumes(port);
                    containers = DockerApi.containers(port);
                }
            }
        }

        // Only while there is something to narrate: once the engine is up the
        // console is the Console tab's business, not the header's.
        String tail = (st.running && engineVersion == null) ? lastConsoleLine() : "";

        // Whether START is already on its way. The cooldown above IS the answer:
        // while it holds, this window has sent a start and every further press
        // is swallowed, and it keeps being refreshed for as long as the poll
        // goes on retrying — so it stays true across the whole boot rather than
        // expiring in the middle of one. A machine that stopped on its own or
        // failed clears it, because then the button is the way back.
        boolean pending = !st.running && !st.died && !"error".equals(st.phase)
                && (provisioning || now - startSentAt < START_RETRY_MS);

        final Docker.Status fst = st;
        final String fver = engineVersion;
        final List<DockerApi.Container> fc = containers;
        final List<DockerApi.Image> fi = images;
        final List<DockerApi.Volume> fv = volumes;
        final String ftail = tail;
        final boolean fpending = pending;
        main.post(() -> render(fst, fver, fc, fi, fv, ftail, fpending));

        poll.postDelayed(this::tick, POLL_MS);
    }

    /**
     * The most recent line of guest output worth showing.
     *
     * Reads the tail of the log rather than the whole thing — a compose build
     * can leave megabytes in there — and skips our own @@OADX markers, which
     * the header already renders in words. Control characters are stripped
     * because the guest's console is a real terminal and sprays escapes.
     */
    private String lastConsoleLine() {
        java.io.File log = Docker.consoleLog(this);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
            long len = raf.length();
            int want = (int) Math.min(len, 4096);
            raf.seek(len - want);
            byte[] buf = new byte[want];
            raf.readFully(buf);
            String[] lines = new String(buf, java.nio.charset.StandardCharsets.UTF_8).split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String s = lines[i].replaceAll("\\[[0-9;?]*[a-zA-Z]", "")
                        .replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
                if (s.isEmpty() || s.startsWith("@@OADX")) continue;
                return s;
            }
        } catch (Exception e) {
            // No log yet is the normal state before the first boot.
        }
        return "";
    }

    /** A stage code from the state file or the guest, in words. */
    private String stageText(Docker.Status st) {
        String m = st.msg == null ? "" : st.msg;
        if (m.startsWith("downloading-")) {
            return getString(R.string.dk_s_downloading, m.substring("downloading-".length()));
        }
        if (m.startsWith("kernel-")) {
            return getString(R.string.dk_s_kernel, m.substring("kernel-".length()));
        }
        switch (m) {
            case "preparing": return getString(R.string.dk_s_preparing);
            case "building-boot-image": return getString(R.string.dk_s_boot_image);
            case "creating-disk": return getString(R.string.dk_s_creating_disk);
            case "ready-to-boot": return getString(R.string.dk_s_ready_to_boot);
            case "unpacking-base": return getString(R.string.dk_s_unpacking);
            case "fetching-installer": return getString(R.string.dk_s_installer);
            case "formatting-disk": return getString(R.string.dk_s_formatting);
            case "installing-alpine": return getString(R.string.dk_s_installing);
            case "configuring": return getString(R.string.dk_s_configuring);
            case "starting": return getString(R.string.dk_s_starting);
            case "switch-root":
            case "mounting":
            case "modloop": return getString(R.string.dk_s_engine_wait);
            default: return m;
        }
    }

    private void render(Docker.Status st, String version,
                        List<DockerApi.Container> containers, List<DockerApi.Image> images,
                        List<DockerApi.Volume> volumes, String tail, boolean pending) {
        boolean up = st.running && version != null;
        if (pending) {
            powerPending();
        } else {
            powerLive(st.running ? R.string.dk_stop : R.string.dk_start);
        }

        if (st.running) {
            if (runningSince == 0) runningSince = android.os.SystemClock.elapsedRealtime();
        } else {
            runningSince = 0;
        }

        if ("error".equals(st.phase)) {
            statusLine.setText(getString(R.string.dk_error));
            subLine.setText(st.msg);
            bar.setVisibility(View.GONE);
        } else if (st.died) {
            // Distinct from "not running": nobody asked for this, and the
            // reason is nearly always memory. Saying which one it was is the
            // whole point of tracking it.
            statusLine.setText(getString(R.string.dk_died));
            subLine.setText(getString(R.string.dk_died_sub));
            bar.setVisibility(View.GONE);
        } else if (!st.running) {
            statusLine.setText(getString(R.string.dk_stopped));
            subLine.setText(getString(R.string.dk_stopped_sub));
            bar.setVisibility(View.GONE);
        } else if (up) {
            statusLine.setText(getString(R.string.dk_running, version));
            subLine.setText(getString(R.string.dk_endpoint, port));
            bar.setVisibility(View.GONE);
        } else {
            statusLine.setText(getString("install".equals(st.phase)
                    ? R.string.dk_installing : R.string.dk_booting));
            String stage = stageText(st);
            long secs = runningSince == 0 ? 0
                    : (android.os.SystemClock.elapsedRealtime() - runningSince) / 1000;
            subLine.setText(secs > 0
                    ? getString(R.string.dk_stage_elapsed, stage, secs) : stage);
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(Math.max(0, Math.min(100, st.pct)));
        }
        detailLine.setText(tail);
        detailLine.setVisibility(tail.isEmpty() ? View.GONE : View.VISIBLE);

        // Above the early-out below: whether the console is attached, and what
        // a row says about itself, have nothing to do with whether the shape of
        // the list changed.
        if (pane == PANE_CONSOLE) attachConsole();
        if (pane == PANE_CONTAINERS) updateContainerRows(containers);

        // A repaint of a list the user might be scrolling is worse than a
        // slightly stale one, so only rebuild when something actually moved.
        String sig = st.phase + '/' + st.pct + '/' + st.running + '/' + version + '/' + pane
                + '/' + signature(containers) + signature2(images) + signature3(volumes);
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;

        if (pane == PANE_CONTAINERS) showContainers(containers, up);
        else if (pane == PANE_IMAGES) showImages(images, containers, up);
        else if (pane == PANE_VOLUMES) showVolumes(volumes, containers, up);
        else if (pane == PANE_DETAIL) refreshDetail(containers);
    }

    private static String signature(List<DockerApi.Container> cs) {
        if (cs == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (DockerApi.Container c : cs) sb.append(c.id).append(c.state).append(';');
        return sb.toString();
    }

    private static String signature2(List<DockerApi.Image> is) {
        if (is == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (DockerApi.Image i : is) sb.append(i.id).append(';');
        return sb.toString();
    }

    private static String signature3(List<DockerApi.Volume> vs) {
        if (vs == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (DockerApi.Volume v : vs) sb.append(v.name).append(';');
        return sb.toString();
    }

    // ── panes ──

    private void showContainers(List<DockerApi.Container> cs, boolean up) {
        if (pane != PANE_CONTAINERS) return;
        paneHost.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(12));
        sv.addView(list);
        paneHost.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!up) {
            list.addView(hint(getString(R.string.dk_engine_down)));
            return;
        }
        if (cs == null || cs.isEmpty()) {
            list.addView(hint(getString(R.string.dk_no_containers)));
            return;
        }
        containerRows.clear();
        if (wideList()) list.addView(columnHeader());
        for (DockerApi.Container c : cs) list.addView(containerRow(c));
    }

    /**
     * Whether there is room for the table.
     *
     * A freeform window is dragged to whatever width its owner feels like, and
     * six columns in 400dp is six columns of ellipsis. Below the threshold the
     * row falls back to the two-line form, which says the same things in the
     * space it has.
     */
    private boolean wideList() {
        return getResources().getConfiguration().screenWidthDp >= 640;
    }

    /** Column weights, in one place so the header and the rows cannot drift. */
    private static final float W_NAME = 2.4f;
    private static final float W_ID = 1.4f;
    private static final float W_IMAGE = 2.2f;
    private static final float W_PORTS = 1.5f;
    private static final float W_CPU = 0.9f;
    private static final float W_MEM = 1.0f;
    private static final float W_ACTIONS = 3.0f;

    private View columnHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(dp(12), dp(2), dp(8), dp(8));
        head.addView(columnLabel(R.string.dk_col_name, W_NAME, Gravity.START));
        head.addView(columnLabel(R.string.dk_col_id, W_ID, Gravity.START));
        head.addView(columnLabel(R.string.dk_col_image, W_IMAGE, Gravity.START));
        head.addView(columnLabel(R.string.dk_col_ports, W_PORTS, Gravity.START));
        head.addView(columnLabel(R.string.dk_col_cpu, W_CPU, Gravity.END));
        head.addView(columnLabel(R.string.dk_col_mem, W_MEM, Gravity.END));
        head.addView(columnLabel(R.string.dk_col_actions, W_ACTIONS, Gravity.END));
        return head;
    }

    private TextView columnLabel(int text, float weight, int gravity) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(theme.textFaint);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10));
        t.setSingleLine(true);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return t;
    }

    private TextView cell(String text, float weight, int gravity, int colour, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return t;
    }

    /** "0.4%" — blank rather than 0.0 for something that is not running. */
    private String cpuText(DockerApi.Container c) {
        DockerApi.Stats st = listStats.get(c.id);
        return st == null ? "—" : String.format(java.util.Locale.US, "%.1f%%", st.cpu);
    }

    private String memText(DockerApi.Container c) {
        DockerApi.Stats st = listStats.get(c.id);
        return st == null ? "—"
                : android.text.format.Formatter.formatShortFileSize(this, st.memory);
    }

    /**
     * Repaint what a row says without rebuilding it.
     *
     * Runs on every poll, above the signature check, because the words in a row
     * age even when its shape does not.
     */
    private void updateContainerRows(List<DockerApi.Container> cs) {
        if (cs == null || containerRows.isEmpty()) return;
        for (DockerApi.Container c : cs) {
            Row row = containerRows.get(c.id);
            if (row == null) continue;
            row.c = c;
            row.sub.setText(row.ports == null ? subLineOf(c) : c.status);
            row.sub.setTextColor(c.isRunning() ? theme.positive : theme.textFaint);
            row.toggle.setText(toggleLabel(c));
            if (row.dot != null) {
                row.dot.setBackground(theme.surface(
                        c.isRunning() ? theme.positive : theme.textFaint, dp(4)));
            }
            if (row.ports != null) {
                String p = c.portText();
                row.ports.setText(p.isEmpty() ? "—" : p);
                row.ports.setTextColor(browsablePort(c) > 0 ? theme.accent : theme.textDim);
            }
            if (row.cpu != null) row.cpu.setText(cpuText(c));
            if (row.memory != null) row.memory.setText(memText(c));
        }
    }

    private View containerRow(DockerApi.Container c) {
        boolean wide = wideList();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(theme.surface(theme.card(), dp(10)));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));

        Row holder = new Row();
        holder.c = c;
        containerRows.put(c.id, holder);

        // The dot carries the state, so the name column does not have to spend
        // half its width on the word "running".
        View dot = new View(this);
        holder.dot = dot;
        dot.setBackground(theme.surface(
                c.isRunning() ? theme.positive : theme.textFaint, dp(4)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        row.addView(dot, dotLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(name);

        TextView sub = new TextView(this);
        holder.sub = sub;
        // Wide, the image and ports have columns of their own and the second
        // line is just the status; narrow, it carries all three.
        sub.setText(wide ? c.status : subLineOf(c));
        sub.setTextColor(c.isRunning() ? theme.positive : theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, wide ? W_NAME : 1f));

        if (wide) {
            row.addView(cell(c.shortId(), W_ID, Gravity.START, theme.textFaint, 11));
            row.addView(cell(c.image, W_IMAGE, Gravity.START, theme.textDim, 11.5f));

            String p = c.portText();
            int open = browsablePort(c);
            TextView ports = cell(p.isEmpty() ? "—" : p, W_PORTS, Gravity.START,
                    open > 0 ? theme.accent : theme.textDim, 11.5f);
            holder.ports = ports;
            // The port IS the link, the way it is in Docker Desktop. Only when
            // something is actually forwarding it.
            ports.setOnClickListener(v -> {
                int now = browsablePort(holder.c);
                if (now > 0) openInBrowser(now);
            });
            if (open > 0) DexCursors.apply(ports, DexCursors.ROLE_HAND);
            row.addView(ports);

            holder.cpu = cell(cpuText(c), W_CPU, Gravity.END, theme.textDim, 11.5f);
            row.addView(holder.cpu);
            holder.memory = cell(memText(c), W_MEM, Gravity.END, theme.textDim, 11.5f);
            row.addView(holder.memory);
        }

        // The row itself opens the container, the way the list in Docker
        // Desktop does: the buttons are the verbs worth one click, and
        // everything you would want when it is misbehaving is inside.
        row.setOnClickListener(v -> openDetail(holder.c));
        DexCursors.apply(row, DexCursors.ROLE_HAND);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        if (!wide) {
            int open = browsablePort(c);
            if (open > 0) {
                Button visit = flatButton(getString(R.string.dk_c_open_browser));
                visit.setTextColor(theme.accent);
                visit.setOnClickListener(v -> openInBrowser(browsablePort(holder.c)));
                actions.addView(visit);
            }
        }

        // Every listener reads the holder rather than the container captured at
        // build time: the row outlives the snapshot it was built from.
        Button toggle = flatButton(toggleLabel(c));
        holder.toggle = toggle;
        toggle.setOnClickListener(v -> act(R.string.dk_action_failed, () -> toggle(holder.c)));
        actions.addView(toggle);

        Button more = flatButton(getString(R.string.dk_image_more));
        more.setOnClickListener(v -> containerMenu(holder.c));
        actions.addView(more);

        Button rm = flatButton(getString(R.string.dk_container_remove));
        rm.setTextColor(theme.danger);
        rm.setOnClickListener(v -> confirmRemoveContainer(holder.c, false));
        actions.addView(rm);

        row.addView(actions, wide
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, W_ACTIONS)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    /** Image, state, and where it answers — the row in one line. */
    private String subLineOf(DockerApi.Container c) {
        StringBuilder sb = new StringBuilder(c.image).append(" · ").append(c.status);
        String ports = c.portText();
        if (!ports.isEmpty()) sb.append(" · ").append(ports);
        return sb.toString();
    }

    /**
     * The first published port that can actually be opened from the phone.
     *
     * Publishing a port only gets it as far as the VM's own interface; it
     * reaches the phone when docker-rt.sh forwards it too. Offering the link
     * for anything else would be offering a page that cannot load.
     */
    private int browsablePort(DockerApi.Container c) {
        if (!c.isRunning()) return 0;
        for (Integer host : c.ports.keySet()) {
            if (forwarded.contains(host)) return host;
        }
        return 0;
    }

    private void openInBrowser(int hostPort) {
        String url = "http://127.0.0.1:" + hostPort;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("docker", "no browser for " + url, e);
            say(getString(R.string.dk_c_open_browser), url);
        }
    }

    private String toggleLabel(DockerApi.Container c) {
        return getString(c.isPaused() ? R.string.dk_container_resume
                : c.isRunning() ? R.string.dk_container_stop : R.string.dk_container_start);
    }

    private String toggle(DockerApi.Container c) {
        if (c.isPaused()) return DockerApi.unpauseContainer(port, c.id);
        return c.isRunning() ? DockerApi.stopContainer(port, c.id)
                : DockerApi.startContainer(port, c.id);
    }

    private void containerMenu(DockerApi.Container c) {
        String[] items = {
                getString(R.string.dk_c_open),
                getString(R.string.dk_c_restart),
                getString(c.isPaused() ? R.string.dk_container_resume : R.string.dk_c_pause),
                getString(R.string.dk_c_copy_run),
                getString(R.string.dk_c_console),
                getString(R.string.dk_c_rename),
                getString(R.string.dk_image_copy_id),
        };
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: openDetail(c); break;
                        case 1:
                            act(R.string.dk_action_failed,
                                    () -> DockerApi.restartContainer(port, c.id));
                            break;
                        case 2:
                            act(R.string.dk_action_failed, () -> c.isPaused()
                                    ? DockerApi.unpauseContainer(port, c.id)
                                    : DockerApi.pauseContainer(port, c.id));
                            break;
                        case 3: copyRunLine(c); break;
                        case 4: execInConsole(c); break;
                        case 5: renameSheet(c); break;
                        default: copy(c.shortId()); break;
                    }
                })
                .show();
    }

    private void confirmRemoveContainer(DockerApi.Container c, boolean thenBack) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_remove_title, c.name))
                .setMessage(R.string.dk_remove_body)
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_container_remove, (d, w) -> {
                    if (thenBack) setPane(PANE_CONTAINERS);
                    act(R.string.dk_action_failed, () -> DockerApi.removeContainer(port, c.id));
                })
                .show();
    }

    private void renameSheet(DockerApi.Container c) {
        EditText field = sheetField(R.string.dk_c_rename_hint, false);
        field.setText(c.name);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setView(box)
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_c_rename_go, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) return;
                    act(R.string.dk_action_failed,
                            () -> DockerApi.renameContainer(port, c.id, name));
                })
                .show();
    }

    /** Hand the VM console an exec line for this container, caret waiting. */
    private void execInConsole(DockerApi.Container c) {
        setPane(PANE_CONSOLE);
        if (consoleInput == null) return;
        consoleInput.setText("docker exec -it " + c.name + " sh");
        consoleInput.setSelection(consoleInput.getText().length());
        consoleInput.requestFocus();
    }

    private void copyRunLine(DockerApi.Container c) {
        new Thread(() -> {
            DockerApi.ContainerInfo info = DockerApi.inspectContainer(port, c.id);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (info == null) say(c.name, getString(R.string.dk_c_details_failed));
                else copy(info.runLine);
            });
        }, "docker-runline").start();
    }

    // ── one container, from the inside ──

    private void openDetail(DockerApi.Container c) {
        detailKind = KIND_CONTAINER;
        detailId = c.id;
        detailC = c;
        detailStats = null;
        detailV = null;
        detailTab = TAB_LOGS;
        filesExecId = c.id;
        filesRoot = "/";
        filesPath = "/";
        logFollow = true;
        detailSig = "";
        setPane(PANE_DETAIL);
    }

    private void openVolume(DockerApi.Volume v, List<DockerApi.Container> users) {
        detailKind = KIND_VOLUME;
        detailV = v;
        detailC = null;
        detailId = v.name;
        detailTab = VTAB_FILES;
        detailUsers = users;
        // Resolved when the listing starts: a container already holding this
        // volume is worth minutes of not starting one.
        filesExecId = null;
        filesRoot = PEEK_AT;
        filesPath = PEEK_AT;
        detailSig = "";
        setPane(PANE_DETAIL);
    }

    /**
     * The detail pane: a header that stays put and one of four views under it.
     *
     * Logs first and by default, because "it is not working" is what brings
     * anyone here. Files is the engine's own diff against the image rather than
     * a file browser — the API hands out whole subtrees as tar archives, which
     * is a download, not a listing. Exec runs one command at a time for the
     * same kind of reason: an interactive session is a hijacked socket, and
     * there is already a real terminal one tab away.
     */
    private void showDetail() {
        paneHost.removeAllViews();
        if (detailKind == KIND_VOLUME) {
            showVolumeDetail();
            return;
        }
        if (detailC == null) {
            paneHost.addView(hint(getString(R.string.dk_c_gone)));
            return;
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        paneHost.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(6), dp(8), dp(6));

        Button back = flatButton(getString(R.string.dk_c_back));
        back.setTextColor(theme.accent);
        back.setOnClickListener(v -> setPane(PANE_CONTAINERS));
        head.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(6), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(detailC.name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(name);

        detailStatus = new TextView(this);
        detailStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        detailStatus.setSingleLine(true);
        detailStatus.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(detailStatus);

        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        detailActions = new LinearLayout(this);
        detailActions.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(detailActions);
        wrap.addView(head);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(10), 0, dp(10), dp(4));
        String[] labels = {
                getString(R.string.dk_c_logs),
                getString(R.string.dk_c_inspect),
                getString(R.string.dk_c_files),
                getString(R.string.dk_c_exec),
        };
        for (int i = 0; i < detailTabs.length; i++) {
            final int which = i;
            detailTabs[i] = flatButton(labels[i]);
            detailTabs[i].setTextColor(i == detailTab ? theme.accent : theme.textDim);
            detailTabs[i].setOnClickListener(v -> {
                detailTab = which;
                logFollow = true;
                detailSig = "";
                showDetail();
            });
            tabRow.addView(detailTabs[i]);
        }
        wrap.addView(tabRow);

        View rule = new View(this);
        rule.setBackgroundColor(theme.divider);
        wrap.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        if (detailTab == TAB_FILES) wrap.addView(buildFilesBar());

        detailScroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        detailScroll.addView(content);

        detailBody = new TextView(this);
        detailBody.setTypeface(Typeface.MONOSPACE);
        detailBody.setTextColor(theme.text);
        detailBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        detailBody.setPadding(dp(12), dp(8), dp(12), dp(8));
        detailBody.setTextIsSelectable(true);
        detailBody.setText(R.string.dk_c_loading);
        content.addView(detailBody);

        // The listing is rows rather than text because every one of them is a
        // place to go next.
        detailList = null;
        if (detailTab == TAB_FILES) {
            detailList = new LinearLayout(this);
            detailList.setOrientation(LinearLayout.VERTICAL);
            detailList.setPadding(dp(8), dp(4), dp(8), dp(12));
            content.addView(detailList);
        }

        wrap.addView(detailScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (detailTab == TAB_EXEC) wrap.addView(buildExecEntry());

        renderDetailHeader();
        loadDetailBody();
    }

    private View buildExecEntry() {
        LinearLayout entry = new LinearLayout(this);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setPadding(dp(10), dp(6), dp(10), dp(10));

        execInput = commandField(R.string.dk_c_exec_hint, this::runExec);
        entry.addView(execInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button go = flatButton(getString(R.string.dk_console_send));
        go.setOnClickListener(v -> runExec());
        entry.addView(go);
        execInput.post(execInput::requestFocus);
        return entry;
    }

    /** The header line and buttons, repainted from every poll. */
    private void renderDetailHeader() {
        if (detailC == null || detailStatus == null) return;
        // Rebuilding four buttons every two seconds costs a pressed button its
        // press, and the status line its selection.
        DockerApi.Stats st = detailStats;
        String cost = st == null ? "" : getString(R.string.dk_c_cost,
                String.format(java.util.Locale.US, "%.1f", st.cpu),
                android.text.format.Formatter.formatShortFileSize(this, st.memory));

        String sig = detailC.state + '/' + detailC.status + '/' + cost;
        if (sig.equals(detailSig) && detailActions.getChildCount() > 0) return;
        detailSig = sig;

        detailStatus.setText(subLineOf(detailC) + cost);
        detailStatus.setTextColor(detailC.isRunning() ? theme.positive : theme.textFaint);

        detailActions.removeAllViews();
        DockerApi.Container c = detailC;

        int open = browsablePort(c);
        if (open > 0) {
            Button visit = flatButton(getString(R.string.dk_c_open_browser));
            visit.setTextColor(theme.accent);
            visit.setOnClickListener(v -> openInBrowser(open));
            detailActions.addView(visit);
        }

        Button toggle = flatButton(toggleLabel(c));
        toggle.setOnClickListener(v -> act(R.string.dk_action_failed, () -> toggle(c)));
        detailActions.addView(toggle);

        Button restart = flatButton(getString(R.string.dk_c_restart));
        restart.setOnClickListener(v -> act(R.string.dk_action_failed,
                () -> DockerApi.restartContainer(port, c.id)));
        detailActions.addView(restart);

        Button more = flatButton(getString(R.string.dk_image_more));
        more.setOnClickListener(v -> containerMenu(c));
        detailActions.addView(more);

        Button rm = flatButton(getString(R.string.dk_container_remove));
        rm.setTextColor(theme.danger);
        rm.setOnClickListener(v -> confirmRemoveContainer(c, true));
        detailActions.addView(rm);
    }

    /**
     * The poll found the container again — or did not.
     *
     * A container that has been removed elsewhere leaves this pane pointing at
     * nothing, and the honest thing is to say so rather than keep showing a
     * status that stopped being true.
     */
    private void refreshDetail(List<DockerApi.Container> cs) {
        if (detailId == null) return;
        if (detailKind == KIND_VOLUME) {
            if (detailV == null) return;
            List<DockerApi.Container> users = DockerApi.usersOf(cs, detailV);
            // Only when it changed: the header is cheap to redraw but the file
            // list under it is not, and showDetail rebuilds both.
            String sig = String.valueOf(users.size());
            if (sig.equals(detailSig)) return;
            detailSig = sig;
            detailUsers = users;
            showDetail();
            return;
        }
        DockerApi.Container found = null;
        for (int i = 0; cs != null && i < cs.size(); i++) {
            if (detailId.equals(cs.get(i).id)) {
                found = cs.get(i);
                break;
            }
        }
        if (found == null) {
            if (detailC == null) return; // already said so
            detailC = null;
            showDetail();
            return;
        }
        detailC = found;
        renderDetailHeader();
        if (detailTab == TAB_LOGS) loadDetailBody();
    }

    /** Fill the body for whichever tab is showing, off the main thread. */
    private void loadDetailBody() {
        if (detailC == null || detailBody == null) return;
        final int tab = detailTab;
        final String id = detailId;
        // Read off the main thread's copy before the worker starts: an empty
        // log means two different things depending on these.
        final boolean running = detailC.isRunning();
        final String status = detailC.status;
        if (tab == TAB_EXEC) {
            // Up front, rather than after someone has typed a paragraph of
            // setup into a box that was never going to accept it: exec is a
            // process inside a live container, and there is nothing to be
            // inside when it has stopped.
            detailBody.setText(running ? "" : getString(R.string.dk_c_exec_stopped));
            return;
        }
        if (tab == TAB_FILES && running) {
            detailBody.setVisibility(View.GONE);
            loadFiles();
            return;
        }
        // Follow the tail only while the reader is already at the bottom, so a
        // refresh cannot yank the view away from something being read.
        logFollow = detailScroll == null || !detailScroll.canScrollVertically(1);
        new Thread(() -> {
            final String text;
            if (tab == TAB_LOGS) {
                String out = DockerApi.logs(port, id, LOG_TAIL);
                // A blank Logs tab is the least helpful thing this window can
                // show, and the commonest reason for one is a container that
                // did exactly what it was told and stopped. Say which it was.
                text = out == null ? getString(R.string.dk_c_logs_failed)
                        : !out.trim().isEmpty() ? out
                        : running ? getString(R.string.dk_c_logs_empty)
                        : getString(R.string.dk_c_logs_exited, status);
            } else if (tab == TAB_FILES) {
                // Only reached when the container is down; a live one is
                // browsed for real, above.
                String out = DockerApi.changes(port, id, 400);
                String diff = out == null ? getString(R.string.dk_c_files_failed)
                        : out.trim().isEmpty() ? getString(R.string.dk_c_files_none) : out;
                text = getString(R.string.dk_c_files_stopped) + "\n\n" + diff;
            } else {
                DockerApi.ContainerInfo info = DockerApi.inspectContainer(port, id);
                text = info == null ? getString(R.string.dk_c_details_failed) : inspectText(info);
            }
            main.post(() -> {
                if (detailBody == null || tab != detailTab || !id.equals(detailId)) return;
                // Only when it actually moved: setText on every poll drops any
                // selection mid-drag, which is exactly what someone copying an
                // error out of a log is doing.
                if (text.contentEquals(detailBody.getText())) return;
                detailBody.setText(text);
                if (tab == TAB_LOGS && logFollow && detailScroll != null) {
                    detailScroll.post(() -> detailScroll.fullScroll(View.FOCUS_DOWN));
                }
            });
        }, "docker-detail").start();
    }

    /** The same pane, for a volume: what is stored in it, and who holds it. */
    private void showVolumeDetail() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        paneHost.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(6), dp(8), dp(6));

        Button back = flatButton(getString(R.string.dk_v_back));
        back.setTextColor(theme.accent);
        back.setOnClickListener(v -> setPane(PANE_VOLUMES));
        head.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(6), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(detailV.name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(name);

        TextView sub = new TextView(this);
        StringBuilder line = new StringBuilder(detailV.driver);
        String age = ago(detailV.created);
        if (!age.isEmpty()) line.append(" · ").append(age);
        line.append(" · ").append(detailUsers.isEmpty()
                ? getString(R.string.dk_v_idle)
                : getResources().getQuantityString(R.plurals.dk_vol_in_use,
                        detailUsers.size(), detailUsers.size()));
        sub.setText(line);
        sub.setTextColor(detailUsers.isEmpty() ? theme.textFaint : theme.positive);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(sub);

        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button more = flatButton(getString(R.string.dk_image_more));
        more.setOnClickListener(v -> volumeMenu(detailV, detailUsers));
        head.addView(more);

        Button rm = flatButton(getString(R.string.dk_image_remove));
        rm.setTextColor(theme.danger);
        rm.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_remove_title, detailV.name))
                .setMessage(detailUsers.isEmpty() ? getString(R.string.dk_vol_remove_body)
                        : getString(R.string.dk_vol_remove_used, detailUsers.size()))
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_image_remove, (d, w) -> {
                    setPane(PANE_VOLUMES);
                    act(R.string.dk_vol_remove_failed,
                            () -> DockerApi.removeVolume(port, detailV.name));
                })
                .show());
        head.addView(rm);
        wrap.addView(head);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(10), 0, dp(10), dp(4));
        String[] labels = {
                getString(R.string.dk_v_tab_files),
                getString(R.string.dk_v_tab_users),
        };
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            Button tab = flatButton(labels[i]);
            tab.setTextColor(i == detailTab ? theme.accent : theme.textDim);
            tab.setOnClickListener(v -> {
                detailTab = which;
                showDetail();
            });
            tabRow.addView(tab);
        }
        wrap.addView(tabRow);

        View rule = new View(this);
        rule.setBackgroundColor(theme.divider);
        wrap.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        if (detailTab == VTAB_FILES) wrap.addView(buildFilesBar());

        detailScroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        detailScroll.addView(content);

        detailBody = new TextView(this);
        detailBody.setTypeface(Typeface.MONOSPACE);
        detailBody.setTextColor(theme.text);
        detailBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        detailBody.setPadding(dp(12), dp(8), dp(12), dp(8));
        detailBody.setTextIsSelectable(true);
        content.addView(detailBody);

        detailList = null;
        if (detailTab == VTAB_FILES) {
            detailList = new LinearLayout(this);
            detailList.setOrientation(LinearLayout.VERTICAL);
            detailList.setPadding(dp(8), dp(4), dp(8), dp(12));
            content.addView(detailList);
        }
        wrap.addView(detailScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (detailTab == VTAB_USERS) {
            detailBody.setText(usersText());
            return;
        }
        detailBody.setVisibility(View.GONE);
        loadVolumeFiles();
    }

    private String usersText() {
        if (detailUsers.isEmpty()) return getString(R.string.dk_vol_usage_none);
        StringBuilder sb = new StringBuilder();
        for (DockerApi.Container c : detailUsers) {
            sb.append(c.name).append('\n')
                    .append(c.volumes.get(detailV.name)).append('\n')
                    .append(c.status).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Find something to list the volume through, then list it.
     *
     * A container already holding it is free; otherwise one gets started, which
     * takes a few seconds on this machine and is worth saying out loud rather
     * than looking like a hang.
     */
    private void loadVolumeFiles() {
        if (filesExecId != null) {
            loadFiles();
            return;
        }
        for (DockerApi.Container c : detailUsers) {
            String at = c.volumes.get(detailV.name);
            if (c.isRunning() && at != null && !at.isEmpty()) {
                filesExecId = c.id;
                filesRoot = at;
                filesPath = at;
                if (filesBar != null) filesBar.setText(filesPath);
                loadFiles();
                return;
            }
        }

        final String volume = detailV.name;
        if (detailList != null) {
            detailList.removeAllViews();
            detailList.addView(hint(getString(R.string.dk_v_peek_note)));
        }
        new Thread(() -> {
            String image = smallestImage();
            final String id = image == null ? null
                    : DockerApi.peek(port, image, volume, PEEK_AT);
            main.post(() -> {
                if (detailKind != KIND_VOLUME || detailV == null
                        || !volume.equals(detailV.name)) {
                    if (id != null) removePeek(id);
                    return;
                }
                if (id == null) {
                    if (detailList == null) return;
                    detailList.removeAllViews();
                    detailList.addView(hint(getString(image == null
                            ? R.string.dk_v_no_image : R.string.dk_v_peek_failed)));
                    return;
                }
                peekId = id;
                filesExecId = id;
                loadFiles();
            });
        }, "docker-peek").start();
    }

    /** The least of the images to hand — this one only has to run ls. */
    private String smallestImage() {
        List<DockerApi.Image> all = DockerApi.images(port);
        DockerApi.Image best = null;
        for (DockerApi.Image im : all) {
            if (im.tag.startsWith("<")) continue; // untagged, and maybe a layer
            if (best == null || im.size < best.size) best = im;
        }
        return best == null ? null : best.ref();
    }

    private void removePeek(String id) {
        new Thread(() -> DockerApi.removeContainer(port, id), "docker-unpeek").start();
    }

    private View buildFilesBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(8), dp(4));

        filesBar = new TextView(this);
        filesBar.setText(filesPath);
        filesBar.setTypeface(Typeface.MONOSPACE);
        filesBar.setTextColor(theme.textDim);
        filesBar.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        filesBar.setSingleLine(true);
        filesBar.setEllipsize(TextUtils.TruncateAt.START);
        bar.addView(filesBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button up = flatButton(getString(R.string.dk_c_files_up));
        up.setOnClickListener(v -> {
            // Stops at the root it was given: above a volume's mount point is
            // the scaffolding container, which is nobody's business.
            if (filesPath.equals(filesRoot)) return;
            int cut = filesPath.lastIndexOf('/');
            filesPath = cut <= 0 ? "/" : filesPath.substring(0, cut);
            if (filesPath.length() < filesRoot.length()) filesPath = filesRoot;
            filesBar.setText(filesPath);
            loadFiles();
        });
        bar.addView(up);
        return bar;
    }

    /**
     * List the current folder inside the container.
     *
     * Not on the poll: a filesystem does not change under you every two
     * seconds, and each listing is an exec — a process spawned in the guest.
     * It reloads when you navigate, and not otherwise.
     */
    private void loadFiles() {
        if (detailList == null || filesExecId == null) return;
        final String id = filesExecId;
        final String path = filesPath;
        detailList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText(R.string.dk_c_loading);
        loading.setTextColor(theme.textFaint);
        loading.setPadding(dp(6), dp(6), dp(6), dp(6));
        detailList.addView(loading);

        new Thread(() -> {
            DockerApi.Listing listing = DockerApi.listDir(port, id, path);
            main.post(() -> {
                if (detailList == null || !id.equals(filesExecId)
                        || !path.equals(filesPath)) {
                    return;
                }
                showListing(listing);
            });
        }, "docker-files").start();
    }

    private void showListing(DockerApi.Listing listing) {
        detailList.removeAllViews();
        if (listing.error != null) {
            TextView t = new TextView(this);
            t.setText(listing.error.isEmpty()
                    ? getString(R.string.dk_c_files_failed) : listing.error);
            t.setTextColor(theme.textFaint);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
            t.setPadding(dp(6), dp(6), dp(6), dp(6));
            detailList.addView(t);
            return;
        }
        if (listing.entries.isEmpty()) {
            detailList.addView(hint(getString(R.string.dk_c_files_empty)));
            return;
        }
        for (DockerApi.Entry e : listing.entries) detailList.addView(fileRow(e));
    }

    private View fileRow(DockerApi.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));

        TextView name = new TextView(this);
        name.setText(e.dir ? e.name + "/" : e.name);
        name.setTextColor(e.dir ? theme.accent : theme.text);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView meta = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (!e.dir) {
            sb.append(android.text.format.Formatter.formatShortFileSize(this, e.size))
                    .append(" · ");
        }
        sb.append(e.when).append(" · ").append(e.mode);
        if (!e.link.isEmpty()) sb.append(" → ").append(e.link);
        meta.setText(sb);
        meta.setTextColor(theme.textFaint);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(meta);

        // A symlink is followed rather than guessed at: ls tells us where it
        // points, but not whether that is a folder, and trying is cheaper than
        // another round trip to find out.
        boolean enterable = e.dir || e.symlink;
        row.setOnClickListener(v -> {
            if (enterable) {
                filesPath = filesPath.endsWith("/") ? filesPath + e.name
                        : filesPath + "/" + e.name;
                if (filesBar != null) filesBar.setText(filesPath);
                loadFiles();
            } else {
                openFile(e);
            }
        });
        DexCursors.apply(row, DexCursors.ROLE_HAND);
        return row;
    }

    /** Show the head of a file. Not an editor — this window cannot write. */
    private void openFile(DockerApi.Entry e) {
        final String id = filesExecId;
        final String path = filesPath.endsWith("/") ? filesPath + e.name
                : filesPath + "/" + e.name;
        if (e.size > FILE_VIEW_MAX) {
            say(e.name, getString(R.string.dk_c_file_big,
                    android.text.format.Formatter.formatShortFileSize(this, e.size)));
            return;
        }
        new Thread(() -> {
            String body = DockerApi.readFile(port, id, path, FILE_VIEW_MAX);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                textSheet(e.name, body == null || body.isEmpty()
                        ? getString(R.string.dk_c_file_empty) : body);
            });
        }, "docker-cat").start();
    }

    private void runExec() {
        if (execInput == null || detailC == null) return;
        String command = execInput.getText().toString().trim();
        if (command.isEmpty()) return;
        final String id = detailId;
        execInput.setText("");
        appendExec(echoOf(command));
        new Thread(() -> {
            String out = DockerApi.exec(port, id, command);
            main.post(() -> {
                if (!id.equals(detailId) || detailTab != TAB_EXEC) return;
                appendExec(out == null || out.isEmpty()
                        ? getString(R.string.dk_c_exec_silent) + "\n" : out);
            });
        }, "docker-exec").start();
    }

    /** A multi-line command echoed the way a shell echoes one. */
    private static String echoOf(String command) {
        StringBuilder sb = new StringBuilder();
        String[] lines = command.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append(i == 0 ? "$ " : "> ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private void appendExec(String text) {
        if (detailBody == null) return;
        CharSequence had = detailBody.getText();
        if (had == null || getString(R.string.dk_c_loading).contentEquals(had)) {
            detailBody.setText(text);
        } else {
            detailBody.append(text);
        }
        if (detailScroll != null) {
            detailScroll.post(() -> detailScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String inspectText(DockerApi.ContainerInfo info) {
        StringBuilder sb = new StringBuilder();
        field(sb, R.string.dk_d_name, info.name);
        field(sb, R.string.dk_d_id, info.id);
        field(sb, R.string.dk_d_image, info.image);
        field(sb, R.string.dk_d_state, info.state);
        field(sb, R.string.dk_d_exit, info.exit);
        field(sb, R.string.dk_d_error, info.error);
        field(sb, R.string.dk_d_created, info.created);
        field(sb, R.string.dk_d_started, info.started);
        field(sb, R.string.dk_d_finished, info.finished);
        field(sb, R.string.dk_d_command, info.command);
        field(sb, R.string.dk_d_entrypoint, info.entrypoint);
        field(sb, R.string.dk_d_workdir, info.workdir);
        field(sb, R.string.dk_d_restart, info.restart);
        field(sb, R.string.dk_d_ports, info.ports);
        DockerApi.Stats st = detailStats;
        if (st != null) {
            field(sb, R.string.dk_d_cpu,
                    String.format(java.util.Locale.US, "%.1f%%", st.cpu));
            field(sb, R.string.dk_d_memory,
                    android.text.format.Formatter.formatShortFileSize(this, st.memory)
                    + " of " + android.text.format.Formatter.formatShortFileSize(
                            this, st.memoryLimit));
        }
        field(sb, R.string.dk_d_mounts, info.mounts);
        field(sb, R.string.dk_d_networks, info.networks);
        field(sb, R.string.dk_d_ip, info.ip);
        field(sb, R.string.dk_d_env, info.env);
        field(sb, R.string.dk_d_run, info.runLine);
        return sb.toString().trim();
    }

    /**
     * The volumes, and the one thing the list is really for: which of them
     * anything is still using.
     *
     * No size column, unlike Docker Desktop. The only endpoint that knows a
     * volume's size is /system/df, which walks every layer and volume on the
     * disk to answer — on a phone, under TCG, that is seconds of the VM's whole
     * attention, and this pane repaints every two.
     */
    private void showVolumes(List<DockerApi.Volume> vs, List<DockerApi.Container> cs,
                             boolean up) {
        if (pane != PANE_VOLUMES) return;
        paneHost.removeAllViews();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        paneHost.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!up) {
            wrap.addView(hint(getString(R.string.dk_engine_down)));
            return;
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        bar.setPadding(dp(12), dp(4), dp(10), 0);
        Button create = flatButton(getString(R.string.dk_vol_new));
        create.setTextColor(theme.accent);
        create.setOnClickListener(v -> createVolumeSheet());
        bar.addView(create);
        wrap.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(6), dp(12), dp(12));
        sv.addView(list);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (vs == null || vs.isEmpty()) {
            list.addView(hint(getString(R.string.dk_no_volumes)));
            return;
        }
        for (DockerApi.Volume v : vs) list.addView(volumeRow(v, cs));
    }

    private View volumeRow(DockerApi.Volume v, List<DockerApi.Container> cs) {
        List<DockerApi.Container> users = DockerApi.usersOf(cs, v);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(theme.surface(theme.card(), dp(10)));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(v.name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(name);

        StringBuilder sub = new StringBuilder(v.driver);
        String age = ago(v.created);
        if (!age.isEmpty()) sub.append(" · ").append(age);
        if (!users.isEmpty()) {
            sub.append(" · ").append(getResources().getQuantityString(
                    R.plurals.dk_vol_in_use, users.size(), users.size()));
        }

        TextView meta = new TextView(this);
        meta.setText(sub);
        meta.setTextColor(users.isEmpty() ? theme.textFaint : theme.positive);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(meta);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(view -> openVolume(v, users));
        DexCursors.apply(row, DexCursors.ROLE_HAND);

        Button more = flatButton(getString(R.string.dk_image_more));
        more.setOnClickListener(view -> volumeMenu(v, users));
        row.addView(more);

        Button rm = flatButton(getString(R.string.dk_image_remove));
        rm.setTextColor(theme.danger);
        rm.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_remove_title, v.name))
                .setMessage(users.isEmpty() ? getString(R.string.dk_vol_remove_body)
                        : getString(R.string.dk_vol_remove_used, users.size()))
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_image_remove, (d, w) -> act(
                        R.string.dk_vol_remove_failed,
                        () -> DockerApi.removeVolume(port, v.name)))
                .show());
        row.addView(rm);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void volumeMenu(DockerApi.Volume v, List<DockerApi.Container> users) {
        String[] items = {
                getString(R.string.dk_image_details),
                getString(R.string.dk_vol_usage),
                getString(R.string.dk_vol_copy_mount),
                getString(R.string.dk_vol_console),
        };
        new AlertDialog.Builder(this)
                .setTitle(v.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            say(v.name, volumeText(v));
                            break;
                        case 1:
                            StringBuilder sb = new StringBuilder();
                            for (DockerApi.Container c : users) {
                                sb.append(c.name).append('\n').append(c.status).append("\n\n");
                            }
                            say(getString(R.string.dk_usage_title, v.name),
                                    users.isEmpty() ? getString(R.string.dk_vol_usage_none)
                                            : sb.toString().trim());
                            break;
                        case 2:
                            copy(v.name + ":/data");
                            break;
                        default:
                            setPane(PANE_CONSOLE);
                            if (consoleInput == null) return;
                            consoleInput.setText("docker run --rm -it -v " + v.name
                                    + ":/data alpine sh");
                            consoleInput.setSelection(consoleInput.getText().length());
                            consoleInput.requestFocus();
                            break;
                    }
                })
                .show();
    }

    private String volumeText(DockerApi.Volume v) {
        StringBuilder sb = new StringBuilder();
        field(sb, R.string.dk_d_name, v.name);
        field(sb, R.string.dk_v_driver, v.driver);
        field(sb, R.string.dk_v_mountpoint, v.mountpoint);
        field(sb, R.string.dk_d_created, v.created);
        field(sb, R.string.dk_v_labels, v.labels);
        field(sb, R.string.dk_v_options, v.options);
        return sb.toString().trim();
    }

    private void createVolumeSheet() {
        EditText field = sheetField(R.string.dk_vol_new_hint, false);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.dk_vol_new)
                .setView(box)
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_vol_create, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) return;
                    act(R.string.dk_vol_create_failed,
                            () -> DockerApi.createVolume(port, name));
                })
                .show();
    }

    /**
     * "3 days ago" from the RFC 3339 the engine writes.
     *
     * Parsed rather than shown raw because a wall of timestamps is not what
     * anyone is scanning a list for, and empty rather than wrong if the format
     * ever moves — an unreadable date is better left out than guessed at.
     */
    private static String ago(String rfc3339) {
        if (rfc3339 == null || rfc3339.isEmpty()) return "";
        try {
            long at = java.time.OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli();
            return android.text.format.DateUtils.getRelativeTimeSpanString(at,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void showImages(List<DockerApi.Image> is, List<DockerApi.Container> cs,
                            boolean up) {
        if (pane != PANE_IMAGES) return;
        paneHost.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(12));
        sv.addView(list);
        paneHost.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!up) {
            list.addView(hint(getString(R.string.dk_engine_down)));
            return;
        }
        if (is == null || is.isEmpty()) {
            list.addView(hint(getString(R.string.dk_no_images)));
            return;
        }
        for (DockerApi.Image im : is) list.addView(imageRow(im, cs));
    }

    /**
     * One image, with the things a Docker host is actually asked to do to one.
     *
     * Run and Remove are on the row because they are the two anyone came here
     * for; the rest is behind More, which keeps a row readable at the width a
     * freeform window is usually dragged to. What is NOT here is the pair
     * Docker Desktop puts in the same menu — vulnerability scanning is Docker
     * Scout, an online service, and pushing needs Hub credentials this window
     * has nowhere to keep. Both would be buttons that apologise when pressed.
     */
    private View imageRow(DockerApi.Image im, List<DockerApi.Container> cs) {
        List<DockerApi.Container> users = DockerApi.containersOf(cs, im);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(theme.surface(theme.card(), dp(10)));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView tag = new TextView(this);
        tag.setText(im.tag);
        tag.setTextColor(theme.text);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        tag.setSingleLine(true);
        tag.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(tag);

        StringBuilder sub = new StringBuilder(im.shortId());
        sub.append(" · ").append(
                android.text.format.Formatter.formatShortFileSize(this, im.size));
        if (im.created > 0) {
            sub.append(" · ").append(android.text.format.DateUtils.getRelativeTimeSpanString(
                    im.created * 1000L, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS));
        }
        if (!users.isEmpty()) {
            sub.append(" · ").append(getResources().getQuantityString(
                    R.plurals.dk_image_in_use, users.size(), users.size()));
        }

        TextView meta = new TextView(this);
        meta.setText(sub);
        meta.setTextColor(users.isEmpty() ? theme.textFaint : theme.positive);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(meta);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button run = flatButton(getString(R.string.dk_image_run));
        run.setTextColor(theme.accent);
        run.setOnClickListener(v -> runSheet(im));
        row.addView(run);

        Button more = flatButton(getString(R.string.dk_image_more));
        more.setOnClickListener(v -> imageMenu(im, users));
        row.addView(more);

        Button rm = flatButton(getString(R.string.dk_image_remove));
        rm.setTextColor(theme.danger);
        rm.setOnClickListener(v -> removeImage(im, users));
        row.addView(rm);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void imageMenu(DockerApi.Image im, List<DockerApi.Container> users) {
        String[] items = {
                getString(R.string.dk_image_details),
                getString(R.string.dk_image_usage),
                getString(R.string.dk_image_pull),
                getString(R.string.dk_image_copy_id),
                getString(R.string.dk_image_console),
        };
        new AlertDialog.Builder(this)
                .setTitle(im.tag)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: detailsSheet(im); break;
                        case 1: usageSheet(im, users); break;
                        case 2: pullAgain(im); break;
                        case 3: copy(im.shortId()); break;
                        default: runInConsole(im); break;
                    }
                })
                .show();
    }

    // ── image actions ──

    /**
     * The Run sheet: everything optional except the image.
     *
     * Detached, always — {@code docker run -d}. The window has no terminal to
     * attach a container to, and the console it does have belongs to the VM.
     * The port note is not a disclaimer for its own sake: QEMU forwards exactly
     * one port out of this machine and it is the engine's own, so a published
     * port is reachable from inside the VM and nowhere else, and finding that
     * out after writing a server is a bad afternoon.
     */
    private void runSheet(DockerApi.Image im) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        EditText name = sheetField(R.string.dk_run_name, false);
        EditText ports = sheetField(R.string.dk_run_ports, false);
        EditText volumes = sheetField(R.string.dk_run_volumes, false);
        EditText env = sheetField(R.string.dk_run_env, true);
        EditText command = sheetField(R.string.dk_run_command, false);
        box.addView(name);
        box.addView(ports);
        box.addView(volumes);
        box.addView(env);
        box.addView(command);

        TextView note = new TextView(this);
        note.setText(R.string.dk_run_note);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_run_title, im.tag))
                .setView(scroll)
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_image_run, (d, w) -> {
                    DockerApi.RunSpec spec = new DockerApi.RunSpec();
                    spec.image = im.ref();
                    spec.name = name.getText().toString().trim();
                    spec.command = command.getText().toString().trim();
                    spec.ports.addAll(splitList(ports.getText().toString()));
                    spec.volumes.addAll(splitList(volumes.getText().toString()));
                    for (String line : env.getText().toString().split("\n")) {
                        String e = line.trim();
                        if (!e.isEmpty()) spec.env.add(e);
                    }
                    runContainer(spec);
                })
                .show();
    }

    private void runContainer(DockerApi.RunSpec spec) {
        new Thread(() -> {
            String why = DockerApi.run(port, spec);
            main.post(() -> {
                lastSignature = "";
                if (isFinishing() || isDestroyed()) return;
                if (why != null && !why.isEmpty()) {
                    say(getString(R.string.dk_run_failed), why);
                    return;
                }
                // Straight to the list it just appeared in: a container that
                // starts and exits — which is most of them, run with no command
                // — has already happened by the time anyone switches tabs, and
                // its status is the only place that shows.
                setPane(PANE_CONTAINERS);
            });
        }, "docker-run").start();
    }

    private void detailsSheet(DockerApi.Image im) {
        new Thread(() -> {
            DockerApi.ImageInfo info = DockerApi.inspect(port, im.ref());
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (info == null) {
                    say(im.tag, getString(R.string.dk_details_failed));
                    return;
                }
                textSheet(im.tag, detailsText(info));
            });
        }, "docker-inspect").start();
    }

    private String detailsText(DockerApi.ImageInfo info) {
        StringBuilder sb = new StringBuilder();
        field(sb, R.string.dk_d_tags, info.tags);
        field(sb, R.string.dk_d_id, info.id);
        field(sb, R.string.dk_d_digest, info.digest);
        field(sb, R.string.dk_d_created, info.created);
        field(sb, R.string.dk_d_platform, info.platform);
        field(sb, R.string.dk_d_size,
                android.text.format.Formatter.formatShortFileSize(this, info.size));
        field(sb, R.string.dk_d_layers, String.valueOf(info.layers));
        field(sb, R.string.dk_d_entrypoint, info.entrypoint);
        field(sb, R.string.dk_d_command, info.command);
        field(sb, R.string.dk_d_workdir, info.workdir);
        field(sb, R.string.dk_d_ports, info.ports);
        field(sb, R.string.dk_d_env, info.env);
        return sb.toString().trim();
    }

    /** One labelled line, skipped entirely when the engine had nothing to say. */
    private void field(StringBuilder sb, int label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append(getString(label)).append('\n').append(value.trim()).append("\n\n");
    }

    private void usageSheet(DockerApi.Image im, List<DockerApi.Container> users) {
        StringBuilder sb = new StringBuilder();
        for (DockerApi.Container c : users) {
            sb.append(c.name).append('\n').append(c.status).append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_usage_title, im.tag))
                .setMessage(users.isEmpty() ? getString(R.string.dk_usage_none)
                        : sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void pullAgain(DockerApi.Image im) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(12));
        box.addView(new ProgressBar(this));
        TextView msg = new TextView(this);
        msg.setText(R.string.dk_pull_body);
        msg.setPadding(dp(16), 0, 0, 0);
        box.addView(msg);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_pull_title, im.ref()))
                .setView(box)
                .create();
        dialog.show();

        new Thread(() -> {
            String why = DockerApi.pull(port, im.ref());
            main.post(() -> {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                    // dismissed by the user, or the window went away under us
                }
                lastSignature = "";
                if (isFinishing() || isDestroyed()) return;
                if (why != null && !why.isEmpty()) say(getString(R.string.dk_pull_failed), why);
                else Toast.makeText(this, R.string.dk_pull_done, Toast.LENGTH_SHORT).show();
            });
        }, "docker-pull").start();
    }

    private void removeImage(DockerApi.Image im, List<DockerApi.Container> users) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_remove_title, im.tag))
                .setMessage(users.isEmpty() ? getString(R.string.dk_image_remove_body)
                        : getString(R.string.dk_image_remove_used, users.size()))
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_image_remove, (d, w) -> new Thread(() -> {
                    String why = DockerApi.removeImage(port, im.ref());
                    main.post(() -> {
                        lastSignature = "";
                        if (isFinishing() || isDestroyed()) return;
                        if (why != null && !why.isEmpty()) {
                            say(getString(R.string.dk_image_remove_failed), why);
                        }
                    });
                }, "docker-rmi").start())
                .show();
    }

    /** Hand the console the command and the caret, and let the user finish it. */
    private void runInConsole(DockerApi.Image im) {
        setPane(PANE_CONSOLE);
        if (consoleInput == null) return;
        consoleInput.setText("docker run --rm " + im.ref() + " ");
        consoleInput.setSelection(consoleInput.getText().length());
        consoleInput.requestFocus();
    }

    // ── sheet plumbing ──

    /**
     * A field for a dialog, deliberately unstyled.
     *
     * Everything in the window proper is painted from {@link DexTheme}, but a
     * dialog is drawn by the platform in whatever light or dark the phone is
     * set to. Colouring its contents ourselves is how you get white text on a
     * white sheet the first time someone turns dark mode off.
     */
    private EditText sheetField(int hint, boolean multiline) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (!multiline) f.setSingleLine(true);
        f.setMaxLines(multiline ? 4 : 1);
        return f;
    }

    /** Split a comma- or newline-separated list, dropping the empties. */
    private static List<String> splitList(String text) {
        List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[,\n]")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private void copy(String text) {
        android.content.ClipboardManager cb = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(android.content.ClipData.newPlainText("docker", text));
        }
        Toast.makeText(this, R.string.dk_copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * Say something that matters in a dialog rather than a Toast.
     *
     * "Port is already allocated" and "image is in use by a stopped container"
     * are the whole answer to what just happened, and this window lives on the
     * desktop display where a text Toast is not reliably drawn at all.
     */
    /**
     * A dialog for something long enough to need scrolling and monospace: an
     * inspect dump, the head of a file.
     */
    private void textSheet(String title, String body) {
        if (isFinishing() || isDestroyed()) return;
        TextView view = new TextView(this);
        view.setText(body);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        view.setTextIsSelectable(true);
        view.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(R.string.dk_copy, (d, w) -> copy(body))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void say(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * The guest's serial console, plain.
     *
     * Deliberately not a terminal emulator. The VM's console is where the real
     * {@code docker} CLI lives, and a half-implemented vt100 would break exactly
     * the things people reach for it with (compose output, progress bars,
     * anything that moves the cursor). A scrollback plus a line entry is a
     * smaller promise that it can actually keep.
     */
    private View buildConsolePane() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        consoleScroll = new ScrollView(this);
        consoleView = new TextView(this);
        consoleView.setTypeface(Typeface.MONOSPACE);
        consoleView.setTextColor(theme.text);
        consoleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        consoleView.setPadding(dp(12), dp(8), dp(12), dp(8));
        consoleView.setTextIsSelectable(true);
        // Selectable text is FOCUSABLE text, and this view is first in the pane,
        // so it takes the initial focus and then eats every key typed into the
        // window — a TextView has nowhere to put them. Anything that means text
        // is handed on to the entry field, focus and all.
        consoleView.setOnKeyListener((v, code, ev) -> typedAtScrollback(ev));
        consoleScroll.addView(consoleView);
        wrap.addView(consoleScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout entry = new LinearLayout(this);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setPadding(dp(10), dp(6), dp(10), dp(10));

        consoleInput = commandField(R.string.dk_console_hint, this::sendConsoleLine);
        entry.addView(consoleInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = flatButton(getString(R.string.dk_console_send));
        send.setOnClickListener(v -> sendConsoleLine());
        entry.addView(send);

        wrap.addView(entry);
        // Posted rather than called: the framework hands initial focus to the
        // first focusable view during the first traversal, which has not
        // happened yet, and would take it straight back off the field.
        consoleInput.post(consoleInput::requestFocus);
        return wrap;
    }

    /**
     * A command box, for the two places this window takes one.
     *
     * Multi-line, because everything anyone actually pastes in here arrives
     * with newlines in it — a heredoc for the VM console, a setup script for a
     * container — and a single-line box silently welds those into one
     * unrunnable string. Enter still sends, since that is what a prompt does;
     * Shift+Enter is the new line.
     *
     * Monospace and no autocorrect: an IME that capitalises a flag or
     * "corrects" node:alpine sends the wrong thing entirely.
     */
    private EditText commandField(int hint, Runnable send) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        f.setImeOptions(EditorInfo.IME_ACTION_SEND);
        f.setMaxLines(6);
        f.setGravity(Gravity.TOP | Gravity.START);
        f.setTypeface(Typeface.MONOSPACE);
        f.setTextColor(theme.text);
        f.setHintTextColor(theme.textFaint);
        f.setBackground(theme.surface(theme.field, dp(8)));
        f.setPadding(dp(10), dp(8), dp(10), dp(8));
        // Enter arrives by two routes and handling one leaves the other dead: a
        // physical keyboard — which is most of this desktop — sends a key
        // event, an IME never does and sends an editor action instead.
        f.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
                return false;
            }
            if (ev.isShiftPressed()) return false; // a new line, not a send
            send.run();
            return true;
        });
        f.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE) {
                send.run();
                return true;
            }
            return false;
        });
        return f;
    }

    /**
     * A key that landed on the scrollback instead of the entry field.
     *
     * Only keys that mean text move the focus. Arrows and Ctrl chords stay where
     * they are, so selecting and copying an error message out of the log still
     * works — that is what the scrollback is focusable for in the first place.
     */
    private boolean typedAtScrollback(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (consoleInput == null || consoleInput.hasFocus()) return false;
        if (ev.isCtrlPressed() || ev.isAltPressed() || ev.isMetaPressed()) return false;
        boolean text = ev.getUnicodeChar(ev.getMetaState()) != 0
                || ev.getKeyCode() == KeyEvent.KEYCODE_DEL;
        if (!text) return false;
        consoleInput.requestFocus();
        return consoleInput.dispatchKeyEvent(ev);
    }

    // ── console link ──

    /**
     * Reader half of the console: a thread on the unix socket QEMU's chardev
     * serves, feeding the scrollback.
     *
     * The socket is only the LIVE side. Everything the guest ever printed is in
     * console.log, which the same chardev writes whether or not anything is
     * attached — so opening this pane shows the tail of that first and the
     * socket takes over from there. Without that, attaching after a boot would
     * show an empty screen and a cursor.
     */
    private final class ConsoleLink extends Thread {
        private volatile boolean closed;
        /** Volatile: {@link #write} reads it from the main thread. */
        private volatile LocalSocket sock;
        /** Set by the first byte the guest sends back; see {@link #probe}. */
        private volatile boolean heard;
        /** Whether we have already said this console looks dead. */
        private volatile boolean complained;

        @Override
        public void run() {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(
                        Docker.consoleSocket(DockerActivity.this).getAbsolutePath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
                sock = s;
                if (closed) return; // closed while we were still connecting
                probe();
                byte[] buf = new byte[4096];
                int n;
                while (!closed && (n = s.getInputStream().read(buf)) > 0) {
                    if (!heard) {
                        heard = true;
                        // Take back the verdict if it turns out to be wrong: a
                        // stale "this is dead" over a console that is plainly
                        // answering is worse than never having said it.
                        if (complained) {
                            main.post(() -> appendConsole(
                                    "[" + getString(R.string.dk_console_awake) + "]\n"));
                        }
                    }
                    final String chunk = new String(buf, 0, n,
                            java.nio.charset.StandardCharsets.UTF_8);
                    main.post(() -> appendConsole(chunk));
                }
            } catch (Exception e) {
                if (!closed) DexLog.warn("docker", "console link failed", e);
            } finally {
                // The socket is closed HERE, on every exit, and that is the
                // load-bearing line in this class. The chardev in QEMU serves
                // ONE client and stops accepting while it has one, so a link
                // leaked by a close() that raced the connect, or by a read loop
                // that simply ended, holds the slot for the life of the
                // process. Every later attach then lands in the listen backlog,
                // where connect() succeeds, reads never return and writes go
                // nowhere: a console that worked once and was silent forever
                // after, with nothing anywhere saying why.
                try {
                    s.close();
                } catch (Exception ignored) {
                }
                main.post(() -> {
                    if (console != this) return;
                    console = null;
                    if (!closed) {
                        appendConsole("\n[" + getString(R.string.dk_console_detached) + "]\n");
                    }
                });
            }
        }

        /**
         * Prove the pipe carries in both directions before the user finds out
         * the hard way.
         *
         * A bare newline costs the guest one fresh prompt and nothing else, and
         * a console that is genuinely attached answers within milliseconds —
         * the shell on ttyAMA0 echoes what it is given. Silence means our bytes
         * are going into a socket nobody is reading.
         */
        private void probe() {
            write("");
            // Two chances and eight seconds before saying anything. The first
            // window was two, which called a perfectly live console dead every
            // time the pane was reopened: this VM runs on TCG with four busy
            // vCPUs, and the QEMU I/O thread that has to accept our connection
            // and carry the echo back does not always get a turn inside a
            // second or two.
            main.postDelayed(() -> {
                if (closed || heard || console != this) return;
                write("");
                main.postDelayed(() -> {
                    if (closed || heard || console != this) return;
                    complained = true;
                    appendConsole("\n[" + getString(R.string.dk_console_silent) + "]\n");
                }, 5000);
            }, 3000);
        }

        /** @return null when the line left, else why it did not. */
        String write(String line) {
            LocalSocket s = sock;
            if (s == null) return getString(R.string.dk_console_connecting);
            try {
                OutputStream out = s.getOutputStream();
                out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                return null;
            } catch (Exception e) {
                DexLog.warn("docker", "console write failed", e);
                String why = e.getMessage();
                return why == null ? e.getClass().getSimpleName() : why;
            }
        }

        void close() {
            closed = true;
            LocalSocket s = sock;
            try {
                if (s != null) s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Show the console pane, over a link that is very probably already up.
     *
     * Reopening no longer means reconnecting. QEMU serves ONE client on this
     * chardev and stops accepting while it has one, so every detach/attach is a
     * handoff — and under TCG, with four vCPUs busy, the I/O thread can take
     * seconds to notice the old client left. Tearing the link down on every tab
     * switch turned that into a queue of connects blocked in the listen
     * backlog, each of which looked, from in here, exactly like a console that
     * was not attached.
     */
    private void openConsole() {
        if (consoleBuffer.length() == 0) {
            // First look: seed with the tail of the log so it is never blank.
            String log = Docker.readFile(Docker.consoleLog(this));
            if (log.length() > 16000) log = log.substring(log.length() - 16000);
            consoleBuffer.append(log);
        }
        consoleView.setText(consoleBuffer);
        scrollConsoleDown();
        attachConsole();
    }

    /**
     * Attach to the live console unless we already are.
     *
     * Separate from the seeding above because the poll calls it too. A VM that
     * restarts while this pane is open takes its socket with it, and a pane that
     * attached exactly once would sit there looking perfectly normal, swallowing
     * everything typed into it, for as long as the window stayed open.
     */
    private void attachConsole() {
        if (console != null) return;
        if (zombie != null) {
            if (zombie.isAlive()) return; // still inside connect(); do not stack
            zombie = null;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < consoleRetryAt) return;
        consoleRetryAt = now + CONSOLE_RETRY_MS;
        if (!Docker.consoleSocket(this).exists()) {
            if (!saidDetached) {
                saidDetached = true;
                appendConsole("\n[" + getString(R.string.dk_console_detached) + "]\n");
            }
            return;
        }
        saidDetached = false;
        ConsoleLink c = new ConsoleLink();
        console = c;
        c.setDaemon(true);
        c.start();
    }

    private void closeConsole() {
        ConsoleLink c = console;
        console = null;
        consoleRetryAt = 0;
        if (c == null) return;
        c.close();
        // Kept until the thread actually ends: close() cannot interrupt a
        // connect that is still waiting its turn in the backlog.
        zombie = c.isAlive() ? c : null;
    }

    private void appendConsole(String chunk) {
        consoleBuffer.append(chunk);
        // Bounded scrollback: a compose build can print megabytes, and a
        // TextView that keeps all of it stops laying out.
        if (consoleBuffer.length() > 40000) {
            consoleBuffer.delete(0, consoleBuffer.length() - 24000);
            if (consoleView != null) consoleView.setText(consoleBuffer);
        } else if (consoleView != null) {
            consoleView.append(chunk);
        }
        scrollConsoleDown();
    }

    private void scrollConsoleDown() {
        if (consoleScroll != null) consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Send what is in the entry field, and say in the pane what happened.
     *
     * In the pane rather than a Toast: this window lives on the desktop display
     * and a text Toast is drawn by the system on its own, so the one warning
     * this had was being rendered where nobody was looking. Between that, a
     * write that returned silently when the socket was not up yet, and a guest
     * that only echoes while it is really listening, a dead console was
     * pixel-for-pixel identical to a working one.
     *
     * Split on newlines because pasting is a normal way to get a command in
     * here, and a single-line field otherwise turns a pasted block into one
     * unrunnable run-on line.
     */
    private void sendConsoleLine() {
        if (consoleInput == null) return;
        String text = consoleInput.getText().toString();
        attachConsole(); // in case the link dropped while the pane sat open
        ConsoleLink c = console;
        if (c == null) {
            appendConsole("\n[" + getString(R.string.dk_console_detached) + "]\n");
            return;
        }
        consoleInput.setText("");
        for (String line : text.split("\r?\n")) {
            String why = c.write(line);
            if (why != null) {
                appendConsole("\n[" + getString(R.string.dk_console_not_sent, why) + "]\n");
                return;
            }
        }
    }

    // ── actions ──

    private void onPower() {
        Docker.Status st = Docker.readStatus(this);
        if (st.running) {
            confirmClose();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - startSentAt < START_RETRY_MS) return;
        startSentAt = now;
        // Now, not on the next poll. Two seconds of a live Start button that
        // has already been pressed and will ignore the next press is how this
        // read as broken: the press has to change something the moment it lands.
        powerPending();
        DockerService.start(this);
    }

    /**
     * The power button while the machine is on its way up.
     *
     * Disabled rather than merely relabelled, because the press really is a
     * no-op here — {@link #onPower} swallows it under the same cooldown that
     * keeps the poll from booting a second VM — and an hourglass over a button
     * that answers nothing is the difference between "working" and "broken".
     */
    private void powerPending() {
        powerBtn.setText(getString(R.string.dk_starting));
        powerBtn.setEnabled(false);
        powerBtn.setAlpha(0.5f);
        DexCursors.apply(powerBtn, DexCursors.ROLE_WAIT);
    }

    private void powerLive(int label) {
        powerBtn.setText(getString(label));
        powerBtn.setEnabled(true);
        powerBtn.setAlpha(1f);
        DexCursors.apply(powerBtn, DexCursors.ROLE_HAND);
    }

    /**
     * Run one engine verb off the main thread and show what it said if it said
     * no.
     *
     * The message is the point. "Container is paused", "port is already
     * allocated", "you cannot remove a running container" each tell you what to
     * do next, and every one of them used to arrive as the same six words.
     */
    private void act(int failTitle, java.util.concurrent.Callable<String> call) {
        new Thread(() -> {
            String why;
            try {
                why = call.call();
            } catch (Exception e) {
                why = String.valueOf(e.getMessage());
            }
            final String fwhy = why;
            main.post(() -> {
                lastSignature = ""; // repaint on the next poll either way
                if (fwhy == null || fwhy.isEmpty()) return;
                say(getString(failTitle), fwhy);
            });
        }, "docker-action").start();
    }

    /**
     * The ✕ ends the VM, and asks first — closing this window stops every
     * container in it, which is not what "close the window" means everywhere
     * else. Minimising is the way to put it aside.
     */
    private void confirmClose() {
        if (!Docker.readStatus(this).running) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dk_close_title)
                .setMessage(R.string.dk_close_body)
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_close_stop, (d, w) -> {
                    DockerService.stop(this);
                    finish();
                })
                .show();
    }

    // ── tiny view helpers ──

    private Button flatButton(String label) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(theme.textDim);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(theme.textFaint);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(16), dp(28), dp(16), dp(16));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
