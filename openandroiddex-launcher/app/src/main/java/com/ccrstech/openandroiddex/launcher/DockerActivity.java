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
    private static final int PANE_CONSOLE = 2;

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
    private final Button[] tabs = new Button[3];
    private int pane = PANE_CONTAINERS;

    private TextView consoleView;
    private ScrollView consoleScroll;
    private EditText consoleInput;

    /** Volatile: written by the poll thread, read by {@link #render} on main. */
    private volatile long startSentAt;
    private long provisionSentAt;
    /** When the VM was first seen alive, for the "… (42s)" on the slow stages. */
    private long runningSince;
    private int port;
    /** Last rendered engine state, so a poll only rebuilds when it changed. */
    private String lastSignature = "";

    private ConsoleLink console;
    /** Earliest elapsed-time we may try to attach again; see {@link #attachConsole}. */
    private long consoleRetryAt;
    /** So a console that simply is not there says so once, not once per poll. */
    private boolean saidDetached;

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
        if (poll != null) poll.removeCallbacksAndMessages(null);
        if (pollThread != null) pollThread.quitSafely();
        pollThread = null;
        poll = null;
        closeConsole();
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
            tabs[i].setTextColor(i == which ? theme.accent : theme.textDim);
        }
        paneHost.removeAllViews();
        if (which == PANE_CONSOLE) {
            paneHost.addView(buildConsolePane(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            openConsole();
        } else {
            closeConsole();
            lastSignature = ""; // force a repaint of the list we just switched to
            if (poll != null) poll.post(this::tick);
        }
    }

    // ── the poll ──

    private void tick() {
        if (poll == null) return;
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
        if (st.running && "ready".equals(st.phase)) {
            engineVersion = DockerApi.version(port);
            if (engineVersion != null) {
                if (pane == PANE_CONTAINERS) containers = DockerApi.containers(port);
                else if (pane == PANE_IMAGES) images = DockerApi.images(port);
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
        final String ftail = tail;
        final boolean fpending = pending;
        main.post(() -> render(fst, fver, fc, fi, ftail, fpending));

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
                        String tail, boolean pending) {
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

        // Above the early-out below: whether the console is attached has
        // nothing to do with whether the container list changed.
        if (pane == PANE_CONSOLE) attachConsole();

        // A repaint of a list the user might be scrolling is worse than a
        // slightly stale one, so only rebuild when something actually moved.
        String sig = st.phase + '/' + st.pct + '/' + st.running + '/' + version + '/' + pane
                + '/' + signature(containers) + signature2(images);
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;

        if (pane == PANE_CONTAINERS) showContainers(containers, up);
        else if (pane == PANE_IMAGES) showImages(images, up);
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
        for (DockerApi.Container c : cs) list.addView(containerRow(c));
    }

    private View containerRow(DockerApi.Container c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(theme.surface(theme.card(), dp(10)));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));

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
        sub.setText(c.image + " · " + c.status);
        sub.setTextColor(c.isRunning() ? theme.positive : theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button toggle = flatButton(getString(
                c.isRunning() ? R.string.dk_container_stop : R.string.dk_container_start));
        toggle.setOnClickListener(v -> engineCall(() ->
                c.isRunning() ? DockerApi.stopContainer(port, c.id)
                        : DockerApi.startContainer(port, c.id)));
        row.addView(toggle);

        Button rm = flatButton(getString(R.string.dk_container_remove));
        rm.setTextColor(theme.danger);
        rm.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dk_remove_title, c.name))
                .setMessage(R.string.dk_remove_body)
                .setNegativeButton(R.string.dk_cancel, null)
                .setPositiveButton(R.string.dk_container_remove, (d, w) ->
                        engineCall(() -> DockerApi.removeContainer(port, c.id)))
                .show());
        row.addView(rm);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void showImages(List<DockerApi.Image> is, boolean up) {
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
        for (DockerApi.Image im : is) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(theme.surface(theme.card(), dp(10)));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView tag = new TextView(this);
            tag.setText(im.tag);
            tag.setTextColor(theme.text);
            tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
            tag.setSingleLine(true);
            tag.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(tag);

            TextView size = new TextView(this);
            size.setText(android.text.format.Formatter.formatShortFileSize(this, im.size));
            size.setTextColor(theme.textFaint);
            size.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
            row.addView(size);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            list.addView(row, lp);
        }
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

        consoleInput = new EditText(this);
        consoleInput.setHint(R.string.dk_console_hint);
        // A command line, so no suggestions and no autocorrect: an IME that
        // helpfully capitalises a flag or "corrects" node:alpine sends the wrong
        // thing. Input type before setSingleLine, which derives from it.
        consoleInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        consoleInput.setSingleLine(true);
        consoleInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        consoleInput.setTypeface(Typeface.MONOSPACE);
        consoleInput.setTextColor(theme.text);
        consoleInput.setHintTextColor(theme.textFaint);
        consoleInput.setBackground(theme.surface(theme.field, dp(8)));
        consoleInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        // Enter arrives by two routes and a listener for one leaves the other
        // dead: a physical keyboard — which is most of this desktop — sends a
        // key event, an IME never does and sends an editor action instead.
        consoleInput.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                sendConsoleLine();
                return true;
            }
            return false;
        });
        consoleInput.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEND
                    || action == EditorInfo.IME_ACTION_DONE
                    || action == EditorInfo.IME_NULL) {
                sendConsoleLine();
                return true;
            }
            return false;
        });
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
                    heard = true;
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
            main.postDelayed(() -> {
                if (closed || heard || console != this) return;
                appendConsole("\n[" + getString(R.string.dk_console_silent) + "]\n");
            }, 2000);
        }

        /** @return null when the line left, else why it did not. */
        String write(String line) {
            LocalSocket s = sock;
            if (s == null) return getString(R.string.dk_console_detached);
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

    private void openConsole() {
        closeConsole();
        // Seed with the tail of the log so the pane is never blank.
        String log = Docker.readFile(Docker.consoleLog(this));
        if (log.length() > 16000) log = log.substring(log.length() - 16000);
        consoleView.setText(log);
        scrollConsoleDown();
        saidDetached = false;
        consoleRetryAt = 0;
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
        if (console != null || consoleView == null) return;
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
        if (c != null) c.close();
    }

    private void appendConsole(String chunk) {
        if (consoleView == null) return;
        consoleView.append(chunk);
        // Bounded scrollback: a compose build can print megabytes, and a
        // TextView that keeps all of it stops laying out.
        CharSequence all = consoleView.getText();
        if (all.length() > 40000) {
            consoleView.setText(all.subSequence(all.length() - 24000, all.length()));
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

    private void engineCall(java.util.concurrent.Callable<Boolean> call) {
        new Thread(() -> {
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(call.call());
            } catch (Exception e) {
                ok = false;
            }
            final boolean fok = ok;
            main.post(() -> {
                if (!fok) Toast.makeText(this, R.string.dk_action_failed, Toast.LENGTH_SHORT).show();
                lastSignature = ""; // repaint on the next poll either way
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
