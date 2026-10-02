package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The desktop's File transfer window: this computer down the left, this phone
 * down the right, and the two buttons that move a selection between them.
 *
 * <p><b>Why the window is on the phone.</b> The obvious alternative was a pane
 * inside the Tauri app, where the host filesystem is a function call away and
 * none of {@link HostFiles} would exist. It was rejected on one measurement:
 * the DeX window is normally fullscreen, so on macOS a second Tauri window
 * opens on a different Space and on Windows it opens behind the mirror. A file
 * manager that teleports the user off the desktop they are working on is not
 * the feature that was asked for, and no amount of window-management code fixes
 * being on the wrong screen. Every other SYSTEM APPS tile — Settings, Linux,
 * Docker, the Web viewer, the Task Manager — opens an in-desktop freeform
 * window with a caption strip, a taskbar tile and remembered geometry, and this
 * is the sixth of them, built the same way for the same reason.
 *
 * <p><b>Why two panes and not a picker.</b> A "send to phone" dialog would be
 * half the code. It also cannot answer the question the user actually has,
 * which is <em>where does this land</em>: the folder on the far side has to be
 * visible and navigable while the selection is being made, or the copy is a
 * guess. Both panes therefore navigate independently and the destination is
 * whatever folder the other pane is standing in — which is also why the copy
 * buttons carry that folder's NAME rather than a bare arrow. Multi-select has
 * no precedent anywhere in this shell (not one row in the launcher, the drawer,
 * the Task Manager or the Docker window is multi-selectable), so an arrow
 * between two lists is legible only to someone who already knows the idiom.
 * "Copy to Downloads", greyed out and reading "Select files first" until
 * something is ticked, is legible before anything is clicked.
 *
 * <p><b>Why a fixed 50/50 split.</b> A draggable splitter is a hit target to
 * miss, a piece of geometry to remember per window, and a second thing that can
 * disagree with {@link WindowMemory}. Nothing in this shell has one. Below
 * {@code NARROW_DP} the panes stack and the arrow column becomes a row of ↓ / ↑
 * — the same threshold and the same rebuild-on-cross that Settings uses, which
 * is why the manifest entry declares {@code configChanges}.
 *
 * <p><b>Why the listings are capped at {@link #LIST_CAP}.</b> There is no view
 * recycling anywhere in this shell — the drawer's GridView is the only
 * adapter-backed list in the whole launcher — so every row here is a real
 * inflated LinearLayout. An uncapped {@code /sdcard/DCIM} would build tens of
 * thousands of them on the main thread and stall the desktop the window is
 * drawn on. The cap is a cap and not a fix; introducing the shell's first
 * adapter would be a larger change than this feature.
 *
 * <p><b>Why the phone pane goes through {@link WebFiles}.</b> The Web viewer
 * already owns a file lister with the sort order, the hidden-file rule, the
 * {@code .part} rule and the containment check settled and shipped. A second
 * implementation here would be two answers to "what is in this folder" that
 * could drift, and the panes have to agree — a file copied into the phone pane
 * has to appear in the phone pane.
 *
 * <p>Faults are shown, never hidden. The tile that opens this window is gated
 * on the display being a computer's, not on the beacon, so a computer that
 * cannot serve files is a fault with a Retry button rather than a tile that
 * silently is not there.
 */
public class FileTransferActivity extends Activity {

    /** Below this the window cannot hold two panes side by side. */
    private static final int NARROW_DP = 660;

    /**
     * Rows built per listing. The host caps its own LIST at the same number and
     * says so in the reply; this is the phone-side half of the same cap, and
     * the reason for both is at the top of this file.
     */
    private static final int LIST_CAP = 5_000;

    private DexTheme theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Built once per layout: {@code getDateFormat} parses a pattern per call. */
    private java.text.DateFormat dates;

    private HostFiles host;
    private FileTransfer copier;

    private LinearLayout root;
    private LinearLayout arrows;
    private TextView status;
    private boolean narrow;

    private final Pane hostPane = new Pane(true);
    private final Pane phonePane = new Pane(false);

    /**
     * The beacon token this window has already acted on.
     *
     * The beacon repeats for as long as the desktop lives, so reconnecting on
     * every one of them would re-run ROOTS every few seconds — a flicker of
     * "Loading…" forever against a computer whose reverse tunnel is broken. A
     * token that has CHANGED means a restarted host and a genuinely new
     * credential, which is worth one automatic attempt; everything else is the
     * Retry button's job.
     */
    private String seenToken;

    // ── the copy in flight, as the two rows that narrate it need it ──
    private String copyName;
    private int copyIndex;
    private int copyTotal;
    private int copyPct;
    /** The folder named in the finished line, captured when the job was started. */
    private String copyDest;
    /** True while the job in flight is computer → phone. */
    private boolean copyToPhone;
    /**
     * This window's own answer to "is a copy running", rather than
     * {@link FileTransfer#busy()}.
     *
     * The engine posts {@code onDone} from the worker and clears its own
     * running flag immediately afterwards, so a listener that asked
     * {@code busy()} from inside {@code onDone} would be asking a question
     * whose answer depends on which of two threads got there first — and
     * losing that race leaves a Cancel button on screen for a copy that has
     * already ended. This flag is written only on the main thread, by the two
     * places that know: the click that starts a job, and the callback that ends
     * one.
     */
    private boolean copying;
    /** The last finished job's summary, held until the next one starts. */
    private String lastResult;
    private ProgressBar progressBar;

    private AlertDialog closeDialog;

    /**
     * One side of the window: its state, and the views that show it.
     *
     * Both panes are the same shape and differ in exactly two places — where a
     * listing comes from, and what "the folder above this one" means — so they
     * share every renderer here rather than existing as two near-identical
     * halves of a thousand-line file.
     */
    private static final class Pane {
        final boolean isHost;

        /** Host path, or the phone's absolute path. Null until the first listing. */
        String path;
        /** The folder above {@link #path}, or null at a root. */
        String parent;
        final List<Root> roots = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        /** Ticked rows, keyed by path so a refresh can prune what has gone. */
        final LinkedHashMap<String, FileTransfer.Item> selection = new LinkedHashMap<>();
        /** The views a tick has to repaint, so one click is not a 5000-row rebuild. */
        final HashMap<String, View> rowViews = new HashMap<>();
        final HashMap<String, TextView> boxViews = new HashMap<>();

        int req;
        boolean loading;
        /** Host only: the computer is not answering at all. */
        boolean down;
        /** This one folder could not be read; the far side is otherwise fine. */
        boolean unreadable;
        boolean truncated;
        long free = -1;

        TextView meta;
        TextView bar;
        LinearLayout chips;
        LinearLayout notice;
        LinearLayout list;

        Pane(boolean isHost) {
            this.isHost = isHost;
        }
    }

    /** A starting point on either side: a drive, a volume, or a well-known folder. */
    private static final class Root {
        final String label;
        final String path;

        Root(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    /** One row, from either side's listing — the two arrive in the same shape. */
    private static final class Entry {
        final String path;
        final String name;
        final boolean dir;
        final long size;
        final long modified;

        Entry(String path, String name, boolean dir, long size, long modified) {
            this.path = path;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    // ── lifecycle ──

    /** The desktop carries its own language — see {@link DexLocale}. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The taskbar's only way to know this window exists — our own package
        // has no icon on this desktop.
        OwnWindows.opened(this);
        theme = DexTheme.of(this);
        // The WINDOW's background as well as the content view's: a window whose
        // only opaque surface is a child view shows black for any frame where
        // that child has not drawn yet.
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.windowBg()));
        }
        host = new HostFiles();
        copier = new FileTransfer(this, host, transferListener);
        phonePane.path = phoneRoot().getAbsolutePath();
        buildUi();

        // The beacon comes from the PC over `am broadcast`, so from another uid
        // — EXPORTED, like ACTION_RUNNING. ACTION_CLOSE_WINDOW is our own
        // caption service talking to us inside one process, so NOT_EXPORTED.
        IntentFilter filesFilter = new IntentFilter(LauncherActivity.ACTION_FILES);
        IntentFilter closeFilter = new IntentFilter(LauncherActivity.ACTION_CLOSE_WINDOW);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(filesReceiver, filesFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(closeReceiver, closeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(filesReceiver, filesFilter);
            registerReceiver(closeReceiver, closeFilter);
        }

        loadPhone();
        // Whatever the launcher's own receiver has already heard. Deliberately
        // HostLink.token() and not HostLink.available(): a beacon a few seconds
        // late should cost one refused round trip, not a window that opens
        // straight into its error state.
        seenToken = HostLink.token();
        if (seenToken != null) {
            loadHostRoots();
        } else {
            hostPane.down = true;
            renderPane(hostPane);
            renderArrows();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OwnWindows.closed(this);
        if (closeDialog != null) {
            closeDialog.dismiss();
            closeDialog = null;
        }
        try {
            unregisterReceiver(filesReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(closeReceiver);
        } catch (Exception ignored) {
        }
        // The engine borrowed the socket, so it goes first: shutting the
        // transport down under a copy that is still reading would surface as an
        // I/O failure the user cannot act on.
        if (copier != null) copier.shutdown();
        if (host != null) host.shutdown();
    }

    /** The window can be resized by its caption — re-lay out when it crosses the split. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (narrow != (newConfig.screenWidthDp < NARROW_DP)) buildUi();
    }

    /**
     * A beacon arrived: the computer's file service is up, and this is the
     * credential to present.
     *
     * The launcher's own receiver stamps {@link HostLink} too — both are
     * harmless, it is one memory write — but this one exists so an OPEN window
     * recovers by itself when a restarted desktop mints a new token, instead of
     * sitting on "The computer is not answering" until someone presses Retry.
     */
    private final BroadcastReceiver filesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String token = intent.getStringExtra("token");
            if (token == null || token.isEmpty()) return;
            HostLink.seen(intent.getStringExtra("os"), token);
            if (token.equals(seenToken)) return;   // the same session, still talking
            seenToken = token;
            if (hostPane.down && !hostPane.loading) loadHostRoots();
        }
    };

    /**
     * The caption's ✕. It arrives as a broadcast instead of a task removal
     * because a removed task cannot ask anything — see
     * {@link LauncherActivity#ACTION_CLOSE_WINDOW}.
     */
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            // Addressed by activity name, and the filter is load-bearing: the
            // Linux window, the Docker window and this one all ask before
            // closing and all live in this one process, so an unfiltered
            // receiver would put three questions on screen for one ✕.
            String who = intent.getStringExtra("activity");
            if (who != null && !FileTransferActivity.class.getName().equals(who)) return;
            requestClose();
        }
    };

    /**
     * Back is a third way out of this window, so it asks the same question.
     *
     * The Linux window settled this one already: the caption's ✕ was never the
     * only route, because a keyboard and scrcpy both deliver Back to whatever
     * has focus, and a copy killed silently behind one key press is no better
     * with a title bar above it. With nothing in flight this is a plain Back —
     * {@link #requestClose()} falls straight through to {@code finish()}.
     */
    @Override
    public void onBackPressed() {
        if (!copying) {
            super.onBackPressed();
            return;
        }
        requestClose();
    }

    /**
     * Close, unless a copy would be abandoned without saying so.
     *
     * Reached from the caption's ✕ and from Escape alike. Escape deliberately
     * does NOT take the shorter route Settings and the Task Manager take: those
     * windows have nothing in flight to lose, and a key that silently killed a
     * half-finished folder copy would be the one destructive keystroke in the
     * shell.
     */
    private void requestClose() {
        if (!copying || copier == null) {
            finish();
            return;
        }
        if (closeDialog != null) return;
        closeDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ft_close_busy_title)
                .setMessage(R.string.ft_close_busy_body)
                .setNegativeButton(R.string.ft_cancel, null)
                .setPositiveButton(R.string.ft_close_busy_go, (d, w) -> {
                    copier.cancel();
                    finish();
                })
                .show();
        closeDialog.setOnDismissListener(d -> closeDialog = null);
    }

    // ── layout ──

    private void buildUi() {
        narrow = getResources().getConfiguration().screenWidthDp < NARROW_DP;
        dates = android.text.format.DateFormat.getDateFormat(this);

        root = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) requestClose();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(theme.surface(theme.windowBg(), 0f));
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        content.addView(buildPane(hostPane, HostLink.hostLabel(this)), paneLp());
        content.addView(buildArrows(), arrowsLp());
        content.addView(buildPane(phonePane, s(R.string.ft_phone)), paneLp());

        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, contentLp);
        root.addView(buildFoot(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        DexFonts.applyTo(this, root);
        DexCursors.decorate(root);

        renderPane(hostPane);
        renderPane(phonePane);
        renderArrows();
        renderStatus();
    }

    private LinearLayout.LayoutParams paneLp() {
        return narrow
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private LinearLayout.LayoutParams arrowsLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                narrow ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!narrow) lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    /**
     * One pane: heading, roots, path bar, an optional notice, and the listing.
     *
     * The pane's own views are handed to {@link Pane} rather than kept in
     * fields here, because a layout rebuild replaces them and a listing that
     * was already in flight has to land in the NEW ones.
     */
    private View buildPane(final Pane pane, String heading) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(roundedFill(theme.card(), 14));
        col.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(heading);
        title.setAllCaps(true);
        title.setTextColor(theme.textFaint);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pane.meta = new TextView(this);
        pane.meta.setTextColor(theme.textFaint);
        pane.meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        pane.meta.setGravity(Gravity.END);
        head.addView(pane.meta);
        col.addView(head);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        pane.chips = new LinearLayout(this);
        pane.chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(pane.chips);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(8);
        col.addView(chipScroll, chipLp);

        col.addView(buildBar(pane), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pane.notice = new LinearLayout(this);
        pane.notice.setOrientation(LinearLayout.HORIZONTAL);
        pane.notice.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(pane.notice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        pane.list = new LinearLayout(this);
        pane.list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(pane.list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(4);
        col.addView(scroller, listLp);
        return col;
    }

    /**
     * The path, and the three things that can be done to it.
     *
     * The Docker window's files bar, with two more buttons: a monospace path
     * ellipsized at the START, because the end of a path is the part that says
     * where you are.
     */
    private View buildBar(final Pane pane) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(4), 0, dp(2));

        pane.bar = new TextView(this);
        pane.bar.setTypeface(Typeface.MONOSPACE);
        pane.bar.setTextColor(theme.textDim);
        pane.bar.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        pane.bar.setSingleLine(true);
        pane.bar.setEllipsize(TextUtils.TruncateAt.START);
        bar.addView(pane.bar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button up = flatButton(s(R.string.ft_up));
        up.setOnClickListener(v -> {
            if (pane.parent != null) navigate(pane, pane.parent);
        });
        bar.addView(up);

        Button folder = flatButton(s(R.string.ft_new_folder));
        folder.setOnClickListener(v -> newFolder(pane));
        bar.addView(folder);

        Button refresh = flatButton(s(R.string.ft_refresh));
        refresh.setOnClickListener(v -> load(pane));
        bar.addView(refresh);
        return bar;
    }

    /** The arrow column — filled by {@link #renderArrows()}, which owns its two states. */
    private View buildArrows() {
        arrows = new LinearLayout(this);
        arrows.setOrientation(narrow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        arrows.setGravity(Gravity.CENTER);
        arrows.setPadding(dp(10), dp(10), dp(10), dp(10));
        return arrows;
    }

    private View buildFoot() {
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.topMargin = dp(6);
        foot.addView(line, lineLp);

        status = new TextView(this);
        status.setTextColor(theme.textDim);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        status.setPadding(dp(2), dp(8), dp(2), dp(2));
        // Held open at one line so a copy starting and finishing does not make
        // the whole window jump by the height of a line of text.
        status.setMinLines(1);
        foot.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return foot;
    }

    // ── rendering ──

    private void renderPane(Pane pane) {
        if (pane.list == null) return;
        pane.bar.setText(pane.path == null ? "" : pane.path);
        pane.meta.setText(metaFor(pane));
        renderChips(pane);
        renderNotice(pane);

        pane.list.removeAllViews();
        pane.rowViews.clear();
        pane.boxViews.clear();
        if (pane.loading) {
            pane.list.addView(hint(s(R.string.ft_loading)));
        } else if (pane.down) {
            // The fault path the tile's !onPhone() gate deliberately leaves
            // open: a computer that cannot serve files says so and offers to
            // try again, rather than the window never being offered at all.
            pane.list.addView(fault(s(R.string.ft_host_unreachable),
                    s(R.string.ft_retry), this::retryHost));
        } else if (pane.unreadable) {
            pane.list.addView(hint(s(R.string.ft_unreadable)));
        } else if (pane.entries.isEmpty()) {
            pane.list.addView(hint(s(R.string.ft_empty)));
        } else {
            for (Entry e : pane.entries) pane.list.addView(row(pane, e));
            if (pane.truncated) {
                pane.list.addView(hint(getString(R.string.ft_truncated, LIST_CAP)));
            }
        }
        // Rows are built here, after buildUi's one pass over the tree, and
        // again on every refresh — so the face and the pointers are re-applied
        // with them. Views added later keep the window root's arrow otherwise.
        DexFonts.applyTo(this, pane.list);
        DexCursors.decorate(pane.list);
    }

    /**
     * The right-hand end of a pane's heading: what is ticked, or what is free.
     *
     * One slot for both because they are never both interesting — a user with
     * a selection is deciding whether to copy it, and a user with none is
     * deciding whether there is room.
     */
    private String metaFor(Pane pane) {
        if (!pane.selection.isEmpty()) {
            return getString(R.string.ft_selected, pane.selection.size());
        }
        if (pane.free >= 0) {
            return getString(R.string.ft_free,
                    android.text.format.Formatter.formatShortFileSize(this, pane.free));
        }
        return "";
    }

    private void renderChips(Pane pane) {
        pane.chips.removeAllViews();
        for (Root r : pane.roots) {
            boolean on = r.path.equals(pane.path);
            TextView chip = new TextView(this);
            chip.setText(r.label);
            chip.setTextColor(on ? theme.accent : theme.textDim);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setBackground(on ? plainFill(theme.accentSoft, 10)
                    : tapBackground(0x00000000, theme.hover, 10));
            chip.setOnClickListener(v -> navigate(pane, r.path));
            DexCursors.apply(chip, DexCursors.ROLE_HAND);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            pane.chips.addView(chip, lp);
        }
        DexFonts.applyTo(this, pane.chips);
    }

    /**
     * The degraded-storage note, over the phone pane only.
     *
     * Rare in practice: {@code adb_start_launcher} app-op-grants all-files
     * access on every desktop connect and deliberately never revokes it. It is
     * here for the phone that was started some other way, and the button goes
     * through the confirm-first flow rather than straight to the OS screen —
     * see {@link #requestAllFilesAccess()}.
     */
    private void renderNotice(Pane pane) {
        pane.notice.removeAllViews();
        boolean show = !pane.isHost && !WebFiles.hasAllFiles();
        pane.notice.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        TextView text = new TextView(this);
        text.setText(s(R.string.ft_no_storage));
        text.setTextColor(theme.textFaint);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        pane.notice.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button grant = flatButton(s(R.string.ft_grant));
        grant.setTextColor(theme.accent);
        grant.setOnClickListener(v -> requestAllFilesAccess());
        pane.notice.addView(grant);
        DexFonts.applyTo(this, pane.notice);
        DexCursors.decorate(pane.notice);
    }

    /**
     * One listing row: a tick box, a glyph, a name and its measurements.
     *
     * <p><b>Clicking a folder enters it; clicking a file ticks it; clicking the
     * box ticks either.</b> That split is the whole interaction, and it is the
     * one thing in this window that has to need no explanation. A folder is not
     * a thing you usually want to select, so the common gesture on it is the
     * cheap one; the box is there for the case where you do.
     */
    private View row(final Pane pane, final Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));

        TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        box.setTextColor(theme.windowBg());
        box.setOnClickListener(v -> toggle(pane, e));
        DexCursors.apply(box, DexCursors.ROLE_HAND);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        boxLp.rightMargin = dp(10);
        row.addView(box, boxLp);

        TextView glyph = new TextView(this);
        glyph.setText(e.dir ? "📁" : "📄");
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(
                dp(22), ViewGroup.LayoutParams.WRAP_CONTENT);
        glyphLp.rightMargin = dp(6);
        row.addView(glyph, glyphLp);

        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextColor(e.dir ? theme.accent : theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView meta = new TextView(this);
        meta.setText(metaOf(e));
        meta.setTextColor(theme.textFaint);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(10);
        row.addView(meta, metaLp);

        row.setOnClickListener(v -> {
            if (e.dir) navigate(pane, e.path); else toggle(pane, e);
        });
        DexCursors.apply(row, DexCursors.ROLE_HAND);

        pane.rowViews.put(e.path, row);
        pane.boxViews.put(e.path, box);
        paintRow(pane, e.path);
        return row;
    }

    private String metaOf(Entry e) {
        StringBuilder sb = new StringBuilder();
        if (!e.dir) {
            sb.append(android.text.format.Formatter.formatShortFileSize(this, e.size));
        }
        if (e.modified > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(dates.format(new java.util.Date(e.modified)));
        }
        return sb.toString();
    }

    /**
     * Repaint one row's tick state.
     *
     * Not a re-render of the pane: with the listing cap at five thousand and no
     * view recycling, rebuilding the list on every click would put a visible
     * stall between the finger and the tick.
     */
    private void paintRow(Pane pane, String path) {
        boolean on = pane.selection.containsKey(path);
        View row = pane.rowViews.get(path);
        if (row != null) {
            row.setBackground(on ? plainFill(theme.accentSoft, 10)
                    : tapBackground(0x00000000, theme.hover, 10));
        }
        TextView box = pane.boxViews.get(path);
        if (box != null) {
            box.setText(on ? "✓" : "");
            box.setBackground(checkBox(on));
        }
    }

    /**
     * The two copy buttons, or the row that replaces them while a job runs.
     *
     * With the computer unreachable there is nothing to name and nowhere to
     * copy in either direction, so the column is left empty: the left pane is
     * already saying why, with the Retry button under it, and a second disabled
     * control repeating the same news would only compete with it.
     */
    private void renderArrows() {
        if (arrows == null) return;
        arrows.removeAllViews();
        progressBar = null;
        if (copying) {
            renderProgressRow();
        } else if (hostPane.path != null && !hostPane.down) {
            arrows.addView(copyButton(true), arrowChildLp(false));
            arrows.addView(copyButton(false), arrowChildLp(true));
        }
        DexFonts.applyTo(this, arrows);
        DexCursors.decorate(arrows);
    }

    private LinearLayout.LayoutParams arrowChildLp(boolean second) {
        LinearLayout.LayoutParams lp = narrow
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(dp(148), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (second) {
            if (narrow) lp.leftMargin = dp(10); else lp.topMargin = dp(10);
        }
        return lp;
    }

    /**
     * One direction, as a button that says where it goes.
     *
     * The destination is recomputed from the OTHER pane's current folder every
     * time this is built, which is what makes "Copy to Downloads" true rather
     * than decorative. Nothing ticked reads "Select files first" at half alpha
     * and refuses the pointer — the affordance has to be legible before the
     * first click, because nothing else in this shell is multi-selectable and
     * the gesture is therefore not one the user arrives already knowing.
     */
    private View copyButton(final boolean toPhone) {
        Pane source = toPhone ? hostPane : phonePane;
        boolean ready = !source.selection.isEmpty();
        String dest = toPhone ? phoneDirName() : hostDirName();
        String label = ready
                ? getString(toPhone ? R.string.ft_to_phone_into : R.string.ft_to_pc_into, dest)
                : s(R.string.ft_select_first);

        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setBackground(tapBackground(theme.field, lighten(theme.field), 12));
        b.setAlpha(ready ? 1f : 0.45f);

        TextView arrow = new TextView(this);
        arrow.setText(narrow ? (toPhone ? "↓" : "↑") : (toPhone ? "→" : "←"));
        arrow.setTextColor(ready ? theme.accent : theme.textFaint);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        arrow.setGravity(Gravity.CENTER);
        b.addView(arrow);

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(theme.textDim);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = dp(2);
        b.addView(caption, capLp);

        if (ready) {
            b.setOnClickListener(v -> startCopy(toPhone));
            DexCursors.apply(b, DexCursors.ROLE_HAND);
        } else {
            DexCursors.apply(b, DexCursors.ROLE_NO_DROP);
        }
        return b;
    }

    private void renderProgressRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(narrow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(Math.max(copyPct, 0));
        progressBar.setIndeterminate(copyPct < 0);
        progressBar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        LinearLayout.LayoutParams barLp = narrow
                ? new LinearLayout.LayoutParams(0, dp(5), 1f)
                : new LinearLayout.LayoutParams(dp(128), dp(5));
        barLp.gravity = Gravity.CENTER_VERTICAL;
        box.addView(progressBar, barLp);

        Button cancel = flatButton(s(R.string.ft_cancel));
        cancel.setTextColor(theme.danger);
        cancel.setOnClickListener(v -> {
            if (copier != null) copier.cancel();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.gravity = Gravity.CENTER;
        if (!narrow) cancelLp.topMargin = dp(4);
        box.addView(cancel, cancelLp);

        arrows.addView(box, narrow
                ? new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(
                        dp(148), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * The foot strip: what is happening, or what just happened.
     *
     * The narration lives here rather than in the arrow column because a file
     * name is long and the column is not — and because a line that stays in one
     * place across the whole of a copy is easier to read than one that appears
     * between two buttons.
     */
    private void renderStatus() {
        if (status == null) return;
        if (copying && copyName != null) {
            status.setText(getString(R.string.ft_copying, copyName,
                    Math.max(1, Math.min(copyIndex, copyTotal)), Math.max(copyTotal, 1)));
            return;
        }
        status.setText(lastResult == null ? "" : lastResult);
    }

    // ── navigation ──

    /**
     * Move a pane into a folder.
     *
     * The selection is dropped on the way: a tick that refers to a folder the
     * user has since left is a copy they did not ask for, and carrying it would
     * make the count in the heading a claim about somewhere they cannot see.
     */
    private void navigate(Pane pane, String path) {
        if (path == null) return;
        if (!pane.isHost && WebFiles.resolve(phoneRoot(), path) == null) return;
        pane.selection.clear();
        pane.path = path;
        load(pane);
        renderArrows();
    }

    private void retryHost() {
        // Drop the control socket first: the failure that put this pane in its
        // error state may well have been a socket whose peer had gone but which
        // still looked open, and Retry has to mean a fresh connection.
        if (host != null) host.close();
        loadHostRoots();
    }

    /**
     * ROOTS, and then the first of them.
     *
     * The drives come back before the window can list anything, so this is the
     * one place the two round trips are not interchangeable: a failure here is
     * "the computer is not answering", a failure in {@link #load} is "this one
     * folder cannot be read".
     */
    private void loadHostRoots() {
        if (hostPane.list == null) return;
        hostPane.loading = true;
        hostPane.down = false;
        hostPane.unreadable = false;
        hostPane.roots.clear();
        hostPane.entries.clear();
        hostPane.selection.clear();
        // The heading's other half goes too. A pane that ends up saying "not
        // answering" must not still be claiming, an inch to the right, how much
        // room the computer has — that number came from a machine we are about
        // to discover is gone. load() clears it by itself, because a failed
        // FREE answers -1; only this path has no answer at all to overwrite it.
        hostPane.free = -1;
        renderPane(hostPane);
        renderArrows();
        new Thread(() -> {
            final List<Root> found = new ArrayList<>();
            boolean ok = false;
            try {
                JSONArray arr = host.roots();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null) continue;
                    String path = r.optString("path", "");
                    String label = r.optString("label", "");
                    if (path.isEmpty()) continue;
                    found.add(new Root(label.isEmpty() ? path : label, path));
                }
                ok = true;
            } catch (Exception e) {
                DexLog.warn("files", "the computer would not list its drives", e);
            }
            final boolean reachable = ok;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                hostPane.loading = false;
                if (!reachable || found.isEmpty()) {
                    hostPane.down = true;
                    hostPane.path = null;
                    hostPane.parent = null;
                    renderPane(hostPane);
                    renderArrows();
                    return;
                }
                hostPane.roots.addAll(found);
                hostPane.path = found.get(0).path;
                load(hostPane);
            });
        }, "filetransfer-roots").start();
    }

    private void loadPhone() {
        phonePane.roots.clear();
        phonePane.roots.addAll(phoneRoots());
        load(phonePane);
    }

    /**
     * List a pane's current folder, off the main thread.
     *
     * <p>The guard on the way back is the Docker window's: the request id AND
     * the path, because the window can be rebuilt, navigated somewhere else or
     * closed while a listing is in flight, and a slow answer landing in a
     * folder the user has left is worse than no answer at all.
     */
    private void load(final Pane pane) {
        if (pane.list == null || pane.path == null) return;
        final int id = ++pane.req;
        final String path = pane.path;
        pane.loading = true;
        pane.unreadable = false;
        pane.truncated = false;
        pane.entries.clear();
        renderPane(pane);

        new Thread(() -> {
            final List<Entry> found = new ArrayList<>();
            final boolean[] flags = new boolean[3];   // truncated, unreadable, down
            final long[] free = {-1};
            String parent = null;
            if (pane.isHost) {
                try {
                    JSONObject listing = host.list(path);
                    flags[0] = parse(listing, found);
                    parent = listing.isNull("parent") ? null : listing.optString("parent", null);
                    try {
                        free[0] = host.free(path)[0];
                    } catch (Exception ignored) {
                        // A volume that will not report its size is still a
                        // volume you can copy into; only the heading loses.
                    }
                } catch (HostFiles.HostException e) {
                    // The CODE decides which of the two error states this is,
                    // never the exception type. HostFiles wraps a failed
                    // connect, a socket whose peer has gone and a request that
                    // was never answered in the same HostException that every
                    // ordinary refusal arrives as — so branching on the type
                    // alone would report a computer that has been unplugged as
                    // "this folder cannot be read", which leaves the pane with
                    // no Retry button under it and the copy buttons still
                    // pointed at a host that is not there.
                    boolean gone = HostFiles.CODE_OFFLINE.equals(e.code)
                            || HostFiles.CODE_AUTH.equals(e.code);
                    flags[gone ? 2 : 1] = true;
                } catch (Exception e) {
                    flags[2] = true;
                }
            } else {
                File dir = new File(path);
                // Asked before listing, because WebFiles.list cannot tell the
                // two empties apart: a folder with nothing in it and a folder
                // this uid may not open both come back with no entries, and
                // "This folder is empty" over a permission failure is the kind
                // of wrong answer nobody debugs.
                if (!dir.isDirectory() || !dir.canRead()) {
                    flags[1] = true;
                } else {
                    flags[0] = parse(WebFiles.list(FileTransferActivity.this, dir), found);
                    File up = dir.getParentFile();
                    if (up != null && WebFiles.resolve(phoneRoot(), up.getAbsolutePath()) != null) {
                        parent = up.getAbsolutePath();
                    }
                    free[0] = WebFiles.freeBytes(dir);
                }
            }
            final String parentPath = parent;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (id != pane.req || !path.equals(pane.path) || pane.list == null) return;
                pane.loading = false;
                pane.truncated = flags[0];
                pane.unreadable = flags[1];
                pane.down = flags[2];
                pane.parent = parentPath;
                pane.free = free[0];
                pane.entries.clear();
                pane.entries.addAll(found);
                // A refresh can find a ticked file gone — deleted on the other
                // side, or renamed by the copy that just finished. Prune rather
                // than carry a selection nothing on screen corresponds to.
                pane.selection.keySet().retainAll(pathsOf(found));
                renderPane(pane);
                renderArrows();
            });
        }, "filetransfer-list").start();
    }

    /**
     * Read a listing reply into rows, stopping at the cap.
     *
     * One parser for both sides on purpose: the host's LIST and
     * {@code WebFiles.list} answer in the same shape precisely so that the two
     * panes cannot disagree about what a folder contains.
     *
     * @return true when there was more than the cap would take.
     */
    private static boolean parse(JSONObject listing, List<Entry> out) {
        if (listing == null) return false;
        JSONArray entries = listing.optJSONArray("entries");
        if (entries == null) return listing.optBoolean("truncated", false);
        for (int i = 0; i < entries.length(); i++) {
            if (out.size() >= LIST_CAP) return true;
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String path = e.optString("path", "");
            String name = e.optString("name", "");
            if (path.isEmpty() || name.isEmpty()) continue;
            out.add(new Entry(path, name, e.optBoolean("dir", false),
                    e.optLong("size", 0), e.optLong("modified", 0)));
        }
        return listing.optBoolean("truncated", false);
    }

    private static java.util.Set<String> pathsOf(List<Entry> entries) {
        java.util.HashSet<String> paths = new java.util.HashSet<>();
        for (Entry e : entries) paths.add(e.path);
        return paths;
    }

    private void toggle(Pane pane, Entry e) {
        if (pane.selection.remove(e.path) == null) {
            pane.selection.put(e.path,
                    new FileTransfer.Item(e.path, e.name, e.dir, e.size));
        }
        paintRow(pane, e.path);
        pane.meta.setText(metaFor(pane));
        renderArrows();
    }

    // ── the phone's own starting points ──

    /**
     * Where the phone pane may go.
     *
     * Deliberately NOT {@code Web.root(ctx)}: that pref is the Web viewer's
     * jail, no UI writes it, and reusing it would couple this window's reach to
     * another feature's unused setting — the reasoning is written out in full
     * on {@link WebFiles#resolve(File, String)}.
     */
    private File phoneRoot() {
        File sd = Environment.getExternalStorageDirectory();
        return sd != null ? sd : new File(Web.DEF_ROOT);
    }

    /**
     * The chips over the phone pane.
     *
     * The public folders are named by the literal names they have on disk, the
     * way {@code Web.DEF_UPLOAD_DIR} names Downloads, rather than through
     * {@code Environment.getExternalStoragePublicDirectory} — those names are
     * what every file manager on the phone already shows for the same folders,
     * and the API that returns them has been deprecated since API 29. The
     * storage root is the one that needs a word of our own, because its name on
     * disk is "0".
     */
    private List<Root> phoneRoots() {
        List<Root> out = new ArrayList<>();
        File sd = phoneRoot();
        out.add(new Root(s(R.string.ft_phone), sd.getAbsolutePath()));
        for (String name : new String[]{"Download", "Documents", "DCIM", "Pictures",
                "Movies", "Music"}) {
            File dir = new File(sd, name);
            if (dir.isDirectory()) out.add(new Root(name, dir.getAbsolutePath()));
        }
        return out;
    }

    /** What the → button calls its destination. */
    private String phoneDirName() {
        if (phonePane.path == null) return s(R.string.ft_phone);
        File dir = new File(phonePane.path);
        if (dir.getAbsolutePath().equals(phoneRoot().getAbsolutePath())) {
            return s(R.string.ft_phone);
        }
        String name = dir.getName();
        return name.isEmpty() ? dir.getAbsolutePath() : name;
    }

    /**
     * What the ← button calls its destination.
     *
     * Split on the HOST's separator, which is why {@link HostFiles#sep()}
     * exists: a Windows path cut on '/' keeps the whole of {@code C:\Users\ik}
     * and the button then names a path instead of a folder.
     */
    private String hostDirName() {
        String path = hostPane.path;
        if (path == null || path.isEmpty()) return "";
        char sep = host.sep();
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.charAt(trimmed.length() - 1) == sep) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int cut = trimmed.lastIndexOf(sep);
        String name = cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
        // A drive root ("C:\") and the filesystem root ("/") have no leaf, and
        // the path itself is what a user calls them.
        return name.isEmpty() ? path : name;
    }

    // ── copying ──

    private void startCopy(boolean toPhone) {
        if (copier == null || copying) return;
        Pane source = toPhone ? hostPane : phonePane;
        if (source.selection.isEmpty()) return;
        List<FileTransfer.Item> items = new ArrayList<>(source.selection.values());

        if (toPhone) {
            File dest = phonePane.path == null ? null : new File(phonePane.path);
            if (dest == null || !dest.isDirectory()) {
                Toast.makeText(this, s(R.string.ft_failed), Toast.LENGTH_SHORT).show();
                return;
            }
            copyDest = phoneDirName();
            copier.toPhone(items, dest);
        } else {
            if (hostPane.path == null) return;
            copyDest = hostDirName();
            copier.toHost(items, hostPane.path);
        }
        copying = true;
        copyToPhone = toPhone;
        copyName = null;
        copyIndex = 0;
        copyTotal = items.size();
        copyPct = 0;
        lastResult = null;
        renderArrows();
        renderStatus();
    }

    /**
     * The engine's narration. Every callback is already on the main thread.
     *
     * Progress writes into the two views that show it rather than rebuilding
     * the arrow column: it fires on every changed percent of every file, and a
     * rebuild there would throw away the Cancel button under the user's pointer
     * several times a second.
     */
    private final FileTransfer.Listener transferListener = new FileTransfer.Listener() {
        @Override
        public void onProgress(String name, int index, int total, int pct) {
            copyName = name;
            copyIndex = index;
            copyTotal = total;
            copyPct = pct;
            if (progressBar == null) {
                renderArrows();
            } else {
                progressBar.setIndeterminate(pct < 0);
                if (pct >= 0) progressBar.setProgress(pct);
            }
            renderStatus();
        }

        @Override
        public void onDone(int copied, int failed, int skippedLinks, boolean cancelled) {
            copying = false;
            lastResult = summary(copied, failed, skippedLinks);
            copyName = null;
            // The ticks have been acted on. Leaving them would invite a second
            // accidental copy of the same files into the same folder, which
            // "keep both" naming would then quietly duplicate.
            Pane source = copyToPhone ? hostPane : phonePane;
            source.selection.clear();
            source.meta.setText(metaFor(source));
            for (String path : source.rowViews.keySet()) paintRow(source, path);
            // The destination has new rows in it, and a new amount of free
            // space. Nothing else changed, so only that side is re-listed.
            load(copyToPhone ? phonePane : hostPane);
            renderArrows();
            renderStatus();
        }
    };

    /**
     * What the foot strip says when a job ends.
     *
     * A cancelled job gets the same sentence as a finished one, because it is
     * the same truth: this many files landed in that folder. The dialog that
     * offered the cancel already said the ones already copied stay where they
     * are.
     */
    private String summary(int copied, int failed, int skippedLinks) {
        if (copied == 0 && failed > 0) return s(R.string.ft_failed);
        String dest = copyDest == null ? "" : copyDest;
        // Branched rather than pluralised, the way TransferHud does it. One
        // file is the common case — and the user's own worked example — so
        // "1 files copied" is the sentence this window would say most often.
        // A <plurals> would be the tidier answer in a project that had any;
        // this one has none, and adding the first would land two more
        // MissingQuantity errors in values-ar without fixing the English.
        StringBuilder sb = new StringBuilder(copied == 1
                ? getString(R.string.ft_done_one, dest)
                : getString(R.string.ft_done, copied, dest));
        if (failed > 0) {
            // Already grammatical at one: "1 of 2 failed".
            sb.append(" · ").append(getString(R.string.ft_failed_n, failed, copied + failed));
        }
        if (skippedLinks > 0) {
            sb.append(" · ").append(skippedLinks == 1
                    ? s(R.string.ft_skipped_links_one)
                    : getString(R.string.ft_skipped_links, skippedLinks));
        }
        return sb.toString();
    }

    // ── making a folder ──

    private void newFolder(final Pane pane) {
        if (pane.path == null || pane.down) return;
        final EditText field = new EditText(this);
        field.setHint(R.string.ft_new_folder_hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.ft_new_folder_title)
                .setView(box)
                .setNegativeButton(R.string.ft_cancel, null)
                .setPositiveButton(R.string.ft_new_folder, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) createFolder(pane, name);
                })
                .show();
    }

    /**
     * Create it, then re-list only if the pane is still standing where it was.
     *
     * The name goes through {@code WebFiles.safeName} on both sides: separators
     * and the two relative names are the whole attack, and the host has no way
     * to know the string it is being handed came from a text field rather than
     * from one of its own LIST replies.
     */
    private void createFolder(final Pane pane, String typed) {
        final String name = WebFiles.safeName(typed);
        final String parent = pane.path;
        if (parent == null) return;
        if (pane.isHost) {
            new Thread(() -> {
                boolean ok;
                try {
                    host.mkdir(HostFiles.join(parent, name, host.sep()));
                    ok = true;
                } catch (Exception e) {
                    DexLog.warn("files", "the computer refused a new folder", e);
                    ok = false;
                }
                final boolean made = ok;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!made) {
                        Toast.makeText(this, s(R.string.ft_new_folder_failed),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (parent.equals(pane.path)) load(pane);
                });
            }, "filetransfer-mkdir").start();
            return;
        }
        // One syscall against local storage — off the main thread it would only
        // buy a race with the refresh that follows it.
        File dir = new File(parent, name);
        if (dir.isDirectory() || dir.mkdirs()) {
            load(pane);
        } else {
            Toast.makeText(this, s(R.string.ft_new_folder_failed), Toast.LENGTH_SHORT).show();
        }
    }

    // ── the all-files grant ──

    /**
     * Offer the grant, shaped for this display.
     *
     * <p>The dialog itself — and the reason there has to be one rather than a
     * button that goes straight to the OS screen — lives in
     * {@link WebFiles#requestAllFiles}, which is where the launcher's own
     * offer goes too. It is written down once on purpose: revoking this op
     * makes the platform kill the whole app id
     * ({@code StorageManagerService.killAppForOpChange}), which takes down the
     * launcher — the HOME task of the desktop display — and this window with
     * it, so someone who turns it on from here has to know what turning it off
     * again does. A second copy of that sentence here would be a second place
     * to forget it.
     *
     * <p>What is left is the half only a window already on the desktop can do.
     * These five lines are {@code LauncherActivity.shapeForDesktop} without its
     * bounds, copied for the same reason the {@code dp}/{@code sp} helpers at
     * the foot of this file are copied: it is a private instance method of a
     * class this window holds no reference to. Without them a system screen
     * started from here opens fullscreen over the desktop, which on a display
     * the user is mirroring reads as the desktop having been replaced. No
     * launch bounds, because {@code desktopWindowRect} is the launcher's as
     * well and the platform's own freeform rect is already a window in the
     * right mode on the right display, which is the whole of what is wanted.
     */
    private void requestAllFilesAccess() {
        ActivityOptions opts = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) {
            opts.setLaunchDisplayId(getDisplay().getDisplayId());
        }
        try {
            ActivityOptions.class
                    .getMethod("setLaunchWindowingMode", int.class)
                    .invoke(opts, 5 /* WINDOWING_MODE_FREEFORM */);
        } catch (Exception ignored) {
        }
        WebFiles.requestAllFiles(this, opts.toBundle());
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
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
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

    /** A hint with the one thing that can be done about it under it. */
    private View fault(String message, String label, Runnable onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(hint(message));
        Button b = flatButton(label);
        b.setTextColor(theme.accent);
        b.setOnClickListener(v -> onClick.run());
        box.addView(b);
        return box;
    }

    /** The leading tick box: filled in the accent, hollow with a faint edge. */
    private Drawable checkBox(boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(theme.radius(4)));
        if (on) {
            d.setColor(theme.accent);
        } else {
            d.setColor(0x00000000);
            d.setStroke(dp(1.5f), theme.textFaint);
        }
        return d;
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

    /** A painted surface — grained in Paper mode. See {@link DexTheme#surface}. */
    private Drawable roundedFill(int color, float radiusDp) {
        return theme.surface(color, dp(theme.radius(radiusDp)));
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

    private static int lighten(int color) {
        return Color.argb(Color.alpha(color),
                Math.min(255, Color.red(color) + 24),
                Math.min(255, Color.green(color) + 24),
                Math.min(255, Color.blue(color) + 24));
    }
}
