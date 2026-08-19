package com.ccrstech.openandroiddex.launcher;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/**
 * What the user sees when they drag a file from Windows onto the desktop.
 *
 * The drop itself is taken by scrcpy on the PC — its window IS this desktop —
 * and it copies the file to {@code /sdcard/Download}. Nothing about that is
 * visible from here, so the PC narrates the transfer over
 * {@link LauncherActivity#ACTION_TRANSFER}: a card appears in the corner with
 * the file name and a progress bar, and when the batch finishes it turns into
 * a confirmation that stays for {@link #DONE_MS} and then fades out.
 *
 * It is an overlay window rather than a view inside the desktop, for the same
 * reason the taskbar is: files get dropped while a maximized app window covers
 * the whole display, and a progress card behind that window is no progress
 * card at all. Without the overlay permission it falls back into the activity,
 * where at least a drop onto the bare desktop is confirmed.
 *
 * A copied file is handed to the media scanner on completion. `adb push`
 * writes through the filesystem and leaves no MediaStore row, and the phone's
 * file manager lists Downloads from MediaStore — so without the scan the file
 * is on the phone but invisible in the folder this card just told the user to
 * look in.
 */
final class TransferHud {

    /** How long the finished card stays up before it goes away by itself. */
    private static final int DONE_MS = 5000;
    /** Card width — wide enough for a long file name beside both buttons. */
    private static final int CARD_DP = 420;

    private final LauncherActivity act;
    private final DexTheme theme;

    private View card;
    private boolean overlay;
    private TextView glyph;
    private TextView title;
    private TextView subtitle;
    private ProgressBar bar;
    /** Opens the copied file itself; only there once its URI is known. */
    private TextView openBtn;
    /** Opens the folder it landed in. */
    private TextView folderBtn;

    /** Broadcasts are fired without waiting, so they can arrive out of order. */
    private int lastSeq = -1;
    /**
     * MediaStore URI of the file just copied, when exactly one was — the media
     * scan hands it back, and it is the only handle the launcher has on a file
     * it has no permission to read for itself.
     */
    private Uri scanned;
    /**
     * Bumped for every card shown. The media scan answers asynchronously, and
     * by then the card may already be a different transfer's (or gone) — the
     * callback checks this before touching anything.
     */
    private int generation;

    private final Runnable dismiss = this::hide;

    TransferHud(LauncherActivity act) {
        this.act = act;
        this.theme = DexTheme.of(act);
    }

    // ── the PC's account of a drop ──────────────────────────────────────

    void onBroadcast(Intent intent) {
        int seq = intent.getIntExtra("seq", -1);
        if (seq >= 0) {
            // a much smaller seq means the PC side restarted — resync from it
            if (lastSeq >= 0 && seq < lastSeq && lastSeq - seq < 100) return;
            lastSeq = seq;
        }
        boolean done = "done".equals(intent.getStringExtra("state"));
        String name = decode(intent.getStringExtra("name"));
        String dir = decode(intent.getStringExtra("dir"));
        int idx = intent.getIntExtra("idx", 1);
        int total = intent.getIntExtra("total", 1);
        int pct = intent.getIntExtra("pct", -1);
        int ok = intent.getIntExtra("ok", 0);
        int failed = intent.getIntExtra("fail", 0);
        boolean install = intent.getBooleanExtra("install", false);
        if (dir.isEmpty()) dir = "/sdcard/Download";
        DexLog.step("transfer", (done ? "done " : "active ") + idx + "/" + total
                + " " + pct + "% " + name);

        show();
        if (card == null) {
            DexLog.warn("transfer", "no surface for the transfer card — dropped " + name);
            return;
        }
        act.handler().removeCallbacks(dismiss);
        generation++;
        scanned = null;

        String folder = folderLabel(dir);
        if (!done) {
            glyph.setText("↓");
            glyph.setTextColor(theme.accent);
            title.setText(name);
            String state = install
                    ? act.getString(R.string.lx_tx_installing)
                    : act.getString(R.string.lx_tx_copying, folder);
            if (total > 1) {
                state = state + " · " + act.getString(R.string.lx_tx_of, idx, total);
            }
            if (pct >= 0) state = state + " · " + pct + "%";
            subtitle.setText(state);
            bar.setVisibility(View.VISIBLE);
            // No size to measure against (a dropped folder, or an install):
            // an indeterminate bar is the honest answer.
            bar.setIndeterminate(pct < 0);
            if (pct >= 0) bar.setProgress(pct);
            openBtn.setVisibility(View.GONE);
            folderBtn.setVisibility(View.GONE);
            return;
        }

        bar.setVisibility(View.GONE);
        boolean anyOk = ok > 0;
        glyph.setText(anyOk ? "✓" : "!");
        glyph.setTextColor(anyOk ? theme.positive : theme.danger);
        if (!anyOk) {
            title.setText(name);
            subtitle.setText(act.getString(R.string.lx_tx_failed));
        } else if (failed > 0) {
            title.setText(act.getString(R.string.lx_tx_copied_n, ok, folder));
            subtitle.setText(act.getString(R.string.lx_tx_failed_n, failed, ok + failed));
        } else if (install) {
            title.setText(name);
            subtitle.setText(act.getString(R.string.lx_tx_installed));
        } else if (ok > 1) {
            title.setText(act.getString(R.string.lx_tx_copied_n, ok, folder));
            subtitle.setText(folder);
        } else {
            title.setText(name);
            subtitle.setText(act.getString(R.string.lx_tx_copied, folder));
        }
        boolean openable = !install && anyOk;
        folderBtn.setVisibility(openable ? View.VISIBLE : View.GONE);
        // "Open" appears only once the scan below has handed back a URI for
        // the file — which is only the case for a single copied file, and only
        // a moment later. A button that cannot do what it says is worse than
        // no button.
        openBtn.setVisibility(View.GONE);
        // The whole card opens the folder, not just the pill: this desktop is
        // driven by a mouse over a video stream, and a 50px target at the far
        // corner of the screen is a target people miss. The pills stay as the
        // thing that says the card can be clicked at all.
        card.setOnClickListener(openable ? v -> open() : null);
        card.setClickable(openable);
        if (openable) scan(dir, decode(intent.getStringExtra("landed")));
        act.handler().postDelayed(dismiss, DONE_MS);
    }

    /** "/sdcard/Download" → "Download". */
    private static String folderLabel(String dir) {
        int cut = dir.lastIndexOf('/');
        String leaf = cut >= 0 ? dir.substring(cut + 1) : dir;
        return leaf.isEmpty() ? dir : leaf;
    }

    /** Names arrive base64-encoded: they are user data on a shell command line. */
    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Put the copied files into MediaStore, so the folder the card points at
     * actually lists them — and, for a single file, keep the URI it comes back
     * with so "Open" can hand that one file to an app.
     */
    private void scan(String dir, String names) {
        String[] paths;
        if (names.isEmpty()) {
            // A drop too big to list in one broadcast — scan the folder.
            paths = new String[]{dir};
        } else {
            String[] parts = names.split("\n");
            paths = new String[parts.length];
            for (int i = 0; i < parts.length; i++) {
                paths[i] = dir + "/" + parts[i];
            }
        }
        final boolean single = !names.isEmpty() && paths.length == 1;
        final int gen = generation;
        try {
            MediaScannerConnection.scanFile(act, paths, null, (path, uri) -> {
                if (!single || uri == null) return;
                act.handler().post(() -> {
                    // the card may have been dismissed, or be a later drop's
                    if (gen != generation || card == null) return;
                    scanned = uri;
                    openBtn.setVisibility(View.VISIBLE);
                });
            });
        } catch (Exception e) {
            DexLog.warn("transfer", "media scan failed for " + dir, e);
        }
    }

    // ── the card ────────────────────────────────────────────────────────

    private void show() {
        if (card != null) {
            card.setVisibility(View.VISIBLE);
            return;
        }
        build();
        if (card == null) return;
        if (android.provider.Settings.canDrawOverlays(act)) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    act.dp(CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // focusable would steal the keyboard from whatever the
                    // user is typing in; touches still land on the buttons
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = act.dp(16);
            lp.y = act.dp(LauncherActivity.TASKBAR_DP + 14);
            Glass.apply(act, lp, act.uiDensity());
            try {
                act.getWindowManager().addView(card, lp);
                overlay = true;
                return;
            } catch (Exception e) {
                DexLog.warn("transfer", "overlay window rejected", e);
            }
        }
        // No overlay permission: the card lives in the desktop instead, which
        // is enough for a drop onto the desktop itself.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                act.dp(CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = act.dp(16);
        lp.bottomMargin = act.dp(LauncherActivity.TASKBAR_DP + 14);
        if (act.rootFrame() == null) {
            card = null;
            return;
        }
        act.rootFrame().addView(card, lp);
        overlay = false;
    }

    private void build() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(act.roundedFill(theme.card(), 16));
        row.setPadding(act.dp(14), act.dp(12), act.dp(12), act.dp(12));
        row.setElevation(act.dp(10));

        glyph = new TextView(act);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(18));
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(act.roundedFill(theme.accentSoft, 12));
        LinearLayout.LayoutParams glyphLp =
                new LinearLayout.LayoutParams(act.dp(34), act.dp(34));
        glyphLp.rightMargin = act.dp(12);
        row.addView(glyph, glyphLp);

        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(act);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(13));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);   // keeps the extension
        text.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitle = new TextView(act);
        subtitle.setTextColor(theme.textFaint);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11.5f));
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = act.dp(2);
        text.addView(subtitle, subLp);

        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(theme.divider));
        bar.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(theme.accent));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(4));
        barLp.topMargin = act.dp(8);
        text.addView(bar, barLp);

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(text, textLp);

        openBtn = pill(R.string.lx_open, v -> openFile());
        row.addView(openBtn, pillLayout());
        folderBtn = pill(R.string.lx_tx_open_folder, v -> open());
        row.addView(folderBtn, pillLayout());

        DexFonts.applyTo(act, row);
        DexCursors.decorate(row);
        card = row;
    }

    /** One of the card's action buttons. */
    private TextView pill(int label, View.OnClickListener onClick) {
        TextView btn = new TextView(act);
        btn.setText(label);
        btn.setTextColor(theme.accent);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8));
        btn.setBackground(act.tapBackground(theme.field, theme.hover, 10));
        btn.setVisibility(View.GONE);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private LinearLayout.LayoutParams pillLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = act.dp(8);
        return lp;
    }

    /**
     * The file itself, in whatever app handles its type — and the folder if
     * that does not work out.
     *
     * The MediaStore URI from the scan is the whole reason this is possible:
     * the launcher holds no storage permission, so a `file://` path would open
     * an app that then fails to load anything. The read grant is best-effort
     * for the same reason (we cannot grant what we do not hold), but a viewer
     * with its own media access reads a MediaStore URI on its own account.
     */
    private void openFile() {
        if (scanned == null) {
            open();
            return;
        }
        act.handler().removeCallbacks(dismiss);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setData(scanned)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName target = concreteHandler(intent);
        if (target != null) intent.setComponent(target);
        DexLog.step("transfer", "open file " + scanned + " → "
                + (target != null ? target.flattenToShortString() : "whatever resolves"));
        if (launch(intent)) {
            hide();
            return;
        }
        // No app for this type, or one that refused the hand-off. The folder
        // always exists.
        open();
    }

    /**
     * The folder the files landed in, as a freeform window on this display
     * like everything else the desktop launches.
     */
    private void open() {
        act.handler().removeCallbacks(dismiss);
        Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Name the file manager rather than letting the platform choose.
        // More than one app answers VIEW_DOWNLOADS on a Samsung phone, so an
        // unresolved intent brings up the "open with" chooser — and the
        // chooser, launched into a freeform window on a secondary display,
        // opens and closes again without ever showing the folder (measured).
        ComponentName target = concreteHandler(intent);
        if (target != null) intent.setComponent(target);
        DexLog.step("transfer", "open folder → "
                + (target != null ? target.flattenToShortString() : "whatever resolves"));
        if (!launch(intent)) {
            Toast.makeText(act, act.getString(R.string.lx_tx_cannot_open_folder),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        hide();
    }

    /**
     * The activity that will handle this intent, or null to let the platform
     * decide. The chooser lives in package "android" and is exactly what this
     * is here to skip: in a freeform window on a secondary display it opens
     * and closes again without ever showing anything (measured).
     */
    private ComponentName concreteHandler(Intent intent) {
        try {
            for (ResolveInfo ri : act.getPackageManager().queryIntentActivities(intent, 0)) {
                if (ri.activityInfo == null) continue;
                if ("android".equals(ri.activityInfo.packageName)) continue;
                return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        } catch (Exception e) {
            DexLog.warn("transfer", "cannot resolve a file manager", e);
        }
        return null;
    }

    private boolean launch(Intent intent) {
        try {
            Point size = new Point();
            act.getWindowManager().getDefaultDisplay().getRealSize(size);
            int w = Math.min(act.dp(900), size.x * 3 / 4);
            int h = Math.min(act.dp(620), size.y * 3 / 4);
            int x = (size.x - w) / 2;
            int y = (size.y - h) / 2;
            act.startActivity(intent, act.desktopWindowOptions(new Rect(x, y, x + w, y + h)));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            DexLog.warn("transfer", "cannot open " + intent.getAction(), e);
            return false;
        }
    }

    // ── teardown ────────────────────────────────────────────────────────

    private void hide() {
        detach();
    }

    /** Take the card off screen; the next drop builds a fresh one. */
    void detach() {
        act.handler().removeCallbacks(dismiss);
        scanned = null;
        generation++;
        View v = card;
        card = null;
        if (v == null) return;
        try {
            if (overlay) {
                act.getWindowManager().removeViewImmediate(v);
            } else if (act.rootFrame() != null) {
                act.rootFrame().removeView(v);
            }
        } catch (Exception ignored) {
        }
        overlay = false;
    }
}
