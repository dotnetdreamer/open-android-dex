package com.ccrstech.openandroiddex.launcher;

import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Both faces of the phone's notifications on this desktop: the tray flyout that
 * lists them, and the banner that a ringing call interrupts with.
 *
 * They are one class because they are one decision — what a notification looks
 * like here, and which of its buttons the desktop is willing to press — and
 * because the banner is only ever raised for a row this same code would
 * otherwise have drawn in the list.
 *
 * The banner is an overlay window rather than a view inside the desktop, for
 * the reason {@link TransferHud} spells out at length: calls arrive while a
 * maximized app window covers the whole display, and an answer button behind
 * that window is no answer button at all. Unlike the transfer card it is
 * anchored at the TOP — that is where a phone puts a heads-up, it is the one
 * strip of the desktop the taskbar never occupies, and a call is worth the
 * space it takes.
 *
 * Everything the buttons do belongs to the app that posted the notification —
 * see the note on {@link DexNotifications}. This class picks which of those
 * intents a click sends; it never invents one.
 */
final class NotificationPanel {

    /** Flyout width. Wide enough for two lines of a message plus its buttons. */
    private static final int PANEL_DP = 380;
    /** Past this the list scrolls: a busy phone must not push the flyout offscreen. */
    private static final int LIST_MAX_DP = 420;
    /** Banner width — a caller's name, a label, and both buttons on one row. */
    private static final int BANNER_DP = 460;
    /** Most of an app's own buttons the flyout will draw. */
    private static final int MAX_ACTIONS = 3;

    /**
     * A width in dp, brought down to what the display can actually show.
     *
     * The desktop is 1920dp wide and every constant here was chosen against it.
     * The phone's own screen is 381dp — narrower than this panel — and the
     * flyout is anchored with an 8dp offset from the edge on top of that, so
     * the unclamped width put "Clear all" and every dismiss button off the
     * right of the screen. The launcher runs on that screen with no PC at all,
     * so this is not an edge case, it is the standalone shell.
     */
    private int fit(int wantDp) {
        int margin = act.dp(24);
        return Math.min(act.dp(wantDp), Math.max(act.dp(220), act.uiWidthPx() - margin));
    }

    private final LauncherActivity act;
    /**
     * Re-read on every build rather than held from construction.
     *
     * This object outlives a theme change — it is created once per desktop and
     * the flyout is built per open — so a cached palette would draw the bell's
     * panel in the colours of whatever theme was selected when the desktop
     * first came up.
     */
    private DexTheme theme;

    /** The live banner, or null when no call is up. */
    private View banner;
    private boolean bannerOverlay;
    /** Which notification the banner belongs to, so a stale end cannot close a new one. */
    private String bannerKey;

    NotificationPanel(LauncherActivity act) {
        this.act = act;
        this.theme = DexTheme.of(act);
    }

    // ── the tray flyout ────────────────────────────────────────────────────

    /**
     * The notification centre. Built fresh on every open rather than kept and
     * refreshed: it is open for seconds, the list behind it is a volatile
     * snapshot, and rebuilding is what guarantees a row can never fire an
     * action belonging to a notification that has since been replaced.
     */
    View build() {
        theme = DexTheme.of(act);
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(8));

        int width = fit(PANEL_DP);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(act.dp(6), act.dp(2), act.dp(2), act.dp(6));
        TextView title = new TextView(act);
        title.setText(act.getString(R.string.lx_notifications));
        title.setTextColor(theme.textFaint);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11));
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(head, new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Not connected is a different state from empty, and saying "no
        // notifications" when the phone simply has not let us look would send
        // the user hunting for a bug that is a switch.
        if (!DexNotifications.connected()) {
            panel.addView(grantCard(width));
            return panel;
        }

        List<DexNotifications.Item> items = DexNotifications.items();
        if (items.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText(act.getString(R.string.lx_no_notifications));
            empty.setTextColor(theme.textFaint);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12.5f));
            empty.setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(10));
            panel.addView(empty, new LinearLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return panel;
        }

        TextView clear = new TextView(act);
        clear.setText(act.getString(R.string.lx_clear_all));
        clear.setTextColor(theme.accent);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11.5f));
        clear.setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4));
        clear.setBackground(act.tapBackground(0x00000000, theme.hover, 8));
        clear.setOnClickListener(v -> {
            DexNotifications.clearAll();
            act.dismissPopups();
        });
        head.addView(clear);

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        for (DexNotifications.Item item : items) {
            list.addView(row(item, width), new LinearLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // AT_MOST rather than a fixed height, so a short list is a short flyout
        // and only a long one scrolls. A plain wrap-content ScrollView would
        // grow past the display on a busy phone and take its own scrollbar off
        // the bottom of the screen with it.
        ScrollView scroll = new ScrollView(act) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(
                        act.dp(LIST_MAX_DP), MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(list, new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /**
     * What the flyout shows before the phone has let us read anything: what the
     * feature is, and the one screen that turns it on.
     *
     * The button opens the phone's own notification-access screen in a desktop
     * window, because this grant is the user's to give and nothing here can
     * give it for them. The desktop app grants it over adb on a cabled
     * session — this is the path for a session that has no PC behind it, and
     * for a phone whose policy refused the adb write.
     */
    private View grantCard(int width) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(act.dp(8), act.dp(2), act.dp(8), act.dp(6));

        TextView body = new TextView(act);
        body.setText(act.getString(R.string.lx_notif_grant_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12.5f));
        body.setLineSpacing(act.dp(2), 1f);
        card.addView(body, new LinearLayout.LayoutParams(width - act.dp(16),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView open = new TextView(act);
        open.setText(act.getString(R.string.lx_notif_grant_open));
        open.setTextColor(0xFFFFFFFF);
        open.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12.5f));
        open.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        open.setGravity(Gravity.CENTER);
        open.setPadding(act.dp(14), act.dp(9), act.dp(14), act.dp(9));
        open.setBackground(act.tapBackground(theme.accent, theme.accent, 10));
        open.setOnClickListener(v -> {
            act.dismissPopups();
            openNotificationAccess();
        });
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = act.dp(12);
        card.addView(open, openLp);
        return card;
    }

    /**
     * The phone's notification-access screen, in a window on this desktop.
     *
     * The per-app deep link where the platform has one (Android 11+), because
     * the plain list is long and our entry is not near the top of it. Both are
     * system screens started with our own launch bounds, the same way the
     * all-files-access screen is opened from the Linux window.
     */
    private void openNotificationAccess() {
        Intent intent = null;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            DexNotifications.component(act).flattenToString());
            if (intent.resolveActivity(act.getPackageManager()) == null) intent = null;
        }
        if (intent == null) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(intent, act.desktopWindowOptions(
                    act.desktopWindowRect(act.dp(820), act.dp(620))));
        } catch (Exception e) {
            DexLog.warn("notifications", "cannot open the notification-access screen", e);
            Toast.makeText(act, act.getString(R.string.lx_notif_grant_failed),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** One notification: who, what, when, its own buttons, and a way to be rid of it. */
    private View row(DexNotifications.Item item, int width) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(act.dp(8), act.dp(8), act.dp(4), act.dp(8));
        row.setBackground(act.tapBackground(0x00000000, theme.hover, 10));

        ImageView icon = new ImageView(act);
        if (item.icon != null) icon.setImageDrawable(item.icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(act.dp(28), act.dp(28));
        iconLp.rightMargin = act.dp(10);
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText(item.title);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(13));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView when = new TextView(act);
        when.setText(ago(item.when));
        when.setTextColor(theme.textFaint);
        when.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(10.5f));
        when.setPadding(act.dp(8), 0, act.dp(2), 0);
        titleRow.addView(when);
        texts.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!item.text.isEmpty()) {
            TextView text = new TextView(act);
            text.setText(item.text);
            text.setTextColor(theme.textDim);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12));
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (!item.actions.isEmpty()) {
            texts.addView(actionRow(item, act::dismissPopups), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Ongoing entries have no ✕ because the platform will not take one:
        // cancelNotification on a foreground service's notification is a no-op,
        // and a button that does nothing is worse than no button.
        if (item.clearable) {
            TextView close = new TextView(act);
            close.setText("✕");
            close.setContentDescription(act.getString(R.string.lx_notif_dismiss));
            close.setTextColor(theme.textFaint);
            close.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11));
            close.setGravity(Gravity.CENTER);
            close.setBackground(act.tapBackground(0x00000000, theme.hover, 12));
            close.setOnClickListener(v -> {
                DexNotifications.dismiss(item.key);
                // The list rebuilds from the listener callback; taking the row
                // out here as well is what makes the click feel immediate on a
                // stream with a frame of latency in it.
                row.setVisibility(View.GONE);
            });
            LinearLayout.LayoutParams closeLp =
                    new LinearLayout.LayoutParams(act.dp(26), act.dp(26));
            closeLp.gravity = Gravity.TOP;
            row.addView(close, closeLp);
        } else {
            // Keep the text column the same width in both cases, so a list of
            // mixed rows does not have ragged right edges.
            View spacer = new View(act);
            row.addView(spacer, new LinearLayout.LayoutParams(act.dp(26), act.dp(1)));
        }

        if (item.openable) {
            row.setOnClickListener(v -> {
                act.dismissPopups();
                if (!DexNotifications.open(item.key)) {
                    Toast.makeText(act, act.getString(R.string.lx_notif_gone),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
        return row;
    }

    /**
     * The app's own buttons, in a strip that scrolls sideways.
     *
     * Scrolls rather than wraps because notification actions are short and
     * many: a messaging app posts "Reply", "Mark as read" and "Mute", and
     * wrapping those onto three lines makes one message taller than three.
     */
    /**
     * @param onFired what to do with the surface this strip is in once a button
     *                has been pressed. The flyout closes; a pop-up card drops
     *                itself. Passed in rather than decided here because the
     *                same strip is drawn in both.
     */
    private View actionRow(DexNotifications.Item item, Runnable onFired) {
        LinearLayout strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int shown = Math.min(item.actions.size(), MAX_ACTIONS);
        for (int i = 0; i < shown; i++) {
            final DexNotifications.Action action = item.actions.get(i);
            TextView button = new TextView(act);
            button.setText(action.label);
            button.setTextColor(theme.accent);
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11.5f));
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setSingleLine(true);
            button.setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6));
            button.setBackground(act.tapBackground(0x00000000, theme.hover, 8));
            // The label goes back with the click, so a repost that reordered
            // the app's buttons under this flyout cannot redirect it — see
            // DexNotifications.action.
            button.setOnClickListener(v -> {
                onFired.run();
                if (!DexNotifications.action(item.key, action.index, action.label)) {
                    Toast.makeText(act, act.getString(R.string.lx_notif_gone),
                            Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = act.dp(4);
            strip.addView(button, lp);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(strip);
        return scroll;
    }

    /** "now", "5 min ago" — the platform's own wording, in the desktop's language. */
    private String ago(long when) {
        long now = System.currentTimeMillis();
        if (when <= 0 || now - when < DateUtils.MINUTE_IN_MILLIS) {
            return act.getString(R.string.lx_notif_now);
        }
        return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    // ── the incoming-call banner ───────────────────────────────────────────

    /**
     * Raise the banner for a ringing call.
     *
     * Replaces any banner already up rather than stacking a second one: a phone
     * rings one call at a time, and call waiting reposts the same notification
     * with new extras rather than adding another.
     */
    void showCall(DexNotifications.Item call) {
        if (call == null) return;
        if (!DexPrefs.getBool(act, DexPrefs.KEY_NOTIF_CALLS, DexPrefs.DEF_NOTIF_CALLS)) return;
        hideCall(null);
        theme = DexTheme.of(act);
        bannerKey = call.key;
        View card = buildBanner(call);
        if (Settings.canDrawOverlays(act)) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    fit(BANNER_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Not focusable, like the taskbar and the transfer card: a
                    // call must not take the caret out of whatever the user was
                    // typing. The buttons still take their own touches.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = act.dp(16);
            Glass.apply(act, lp, act.uiDensity());
            try {
                act.getWindowManager().addView(card, lp);
                banner = card;
                bannerOverlay = true;
                DexLog.step("notifications", "incoming call from " + call.pkg
                        + " — banner up");
                return;
            } catch (Exception e) {
                DexLog.warn("notifications", "overlay window rejected for the call banner", e);
            }
        }
        // No overlay permission: the banner lives in the desktop instead. It is
        // then only visible over the desktop itself, which is still better than
        // a call that announces itself nowhere.
        FrameLayout root = act.rootFrame();
        if (root == null) {
            bannerKey = null;
            return;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                fit(BANNER_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = act.dp(16);
        root.addView(card, lp);
        banner = card;
        bannerOverlay = false;
    }

    /**
     * Take the banner down.
     *
     * @param key the call that ended, or null to close whatever is up. A key
     *            that does not match is ignored: the end of the PREVIOUS call
     *            can arrive after the next one has already raised its banner,
     *            and closing the live one would leave a ringing phone with
     *            nothing on screen.
     */
    void hideCall(String key) {
        if (banner == null) return;
        if (key != null && !key.equals(bannerKey)) return;
        View card = banner;
        banner = null;
        bannerKey = null;
        if (bannerOverlay) {
            try {
                act.getWindowManager().removeViewImmediate(card);
            } catch (Exception ignored) {
            }
            bannerOverlay = false;
            return;
        }
        FrameLayout root = act.rootFrame();
        if (root != null) root.removeView(card);
    }

    /**
     * Take down everything this class has floating over the desktop.
     *
     * Called when the desktop goes away — an overlay window outlives its
     * activity — and on a settings change, because both surfaces below are
     * transient and the next notification rebuilds them against whatever the
     * new palette and switches say.
     */
    void detach() {
        hideCall(null);
        dropHeadsUpWindow();
    }

    // ── the pop-up cards ───────────────────────────────────────────────────

    /**
     * How long a card stays before it goes away by itself. Long enough to read
     * a message and reach for its Reply button, short enough that a card is
     * never something you have to clear.
     */
    private static final long HEADS_UP_MS = 7_000L;
    /**
     * How many are on screen at once. Past three the stack is taller than it is
     * useful and starts eating the desktop — the fourth pushes the oldest out,
     * which is where the bell's list takes over.
     */
    private static final int HEADS_UP_MAX = 3;
    /** Card width. Narrower than the flyout: this is a glance, not a list. */
    private static final int HEADS_UP_DP = 360;

    /**
     * One overlay window holding a column of cards, rather than a window per
     * card.
     *
     * A window each would mean computing every card's y from the heights of the
     * ones below it and re-computing all of them whenever any one expired —
     * with each card measuring itself asynchronously, so the numbers are not
     * even known when they are needed. A column lets the layout do it.
     */
    private ViewGroup headsUpColumn;
    private boolean headsUpOverlay;

    /**
     * Show a card in the corner for a notification that just arrived.
     *
     * Bottom right, above the taskbar, because that is where this desktop's
     * other transient card already appears ({@link TransferHud}) and where the
     * computer the user is sitting at puts its own. Not the top: that is the
     * call banner's, and a call must never be crowded by a card about
     * something else.
     */
    void showHeadsUp(DexNotifications.Item item) {
        if (item == null) return;
        if (!DexPrefs.getBool(act, DexPrefs.KEY_NOTIF_POPUP, DexPrefs.DEF_NOTIF_POPUP)) {
            DexLog.step("notifications", "pop-up cards are switched off in Settings");
            return;
        }
        theme = DexTheme.of(act);
        if (!ensureHeadsUpWindow()) {
            DexLog.warn("notifications", "nowhere to put a card — no overlay permission "
                    + "and the desktop has no root view");
            return;
        }

        View card = buildHeadsUpCard(item);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(8);
        // Appended, so the newest card is the one nearest the tray — the
        // direction the eye is already travelling when the bell's badge moves.
        headsUpColumn.addView(card, lp);
        while (headsUpColumn.getChildCount() > HEADS_UP_MAX) {
            dropCard(headsUpColumn.getChildAt(0));
        }
        act.handler().postDelayed(() -> dropCard(card), HEADS_UP_MS);
    }

    /**
     * Take one card out, and the whole window with it once the last one goes.
     *
     * Safe to call twice on the same card: the expiry timer and a click on ✕
     * race by design, and the second call sees a card with no parent.
     */
    private void dropCard(View card) {
        if (card == null || headsUpColumn == null) return;
        if (card.getParent() != headsUpColumn) return;
        headsUpColumn.removeView(card);
        if (headsUpColumn.getChildCount() == 0) dropHeadsUpWindow();
    }

    private boolean ensureHeadsUpWindow() {
        if (headsUpColumn != null) return true;
        LinearLayout column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.END);

        if (Settings.canDrawOverlays(act)) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    fit(HEADS_UP_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Not focusable: a card must never take the caret out of
                    // whatever the user is typing. Its buttons still take
                    // their own touches.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = act.dp(16);
            lp.y = act.dp(LauncherActivity.TASKBAR_DP + 14);
            try {
                act.getWindowManager().addView(column, lp);
                headsUpColumn = column;
                headsUpOverlay = true;
                DexLog.step("notifications", "pop-up cards will use an overlay window");
                return true;
            } catch (Exception e) {
                DexLog.warn("notifications", "overlay window rejected for the pop-up cards", e);
            }
        }
        // No overlay permission: the cards live in the desktop instead, where
        // they are only visible over the desktop itself. Still better than a
        // notification that announces itself nowhere.
        FrameLayout root = act.rootFrame();
        if (root == null) return false;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                fit(HEADS_UP_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = act.dp(16);
        lp.bottomMargin = act.dp(LauncherActivity.TASKBAR_DP + 14);
        root.addView(column, lp);
        headsUpColumn = column;
        headsUpOverlay = false;
        // Worth saying once per stack: in here the cards are only visible while
        // the desktop itself is, which is not what the feature promises.
        DexLog.warn("notifications", "no overlay permission — pop-up cards will sit inside "
                + "the desktop and be hidden by any window over it");
        return true;
    }

    private void dropHeadsUpWindow() {
        if (headsUpColumn == null) return;
        ViewGroup column = headsUpColumn;
        headsUpColumn = null;
        if (headsUpOverlay) {
            try {
                act.getWindowManager().removeViewImmediate(column);
            } catch (Exception ignored) {
            }
            headsUpOverlay = false;
            return;
        }
        FrameLayout root = act.rootFrame();
        if (root != null) root.removeView(column);
    }

    /**
     * One card: who it is from, what it says, and the app's own buttons.
     *
     * Deliberately the same shape as a row in the bell's list rather than a new
     * design — it IS the same notification, and a card that looked different
     * would read as a different thing. The differences are the ones the corner
     * needs: the app's name above the title, because there is no list around it
     * to give context, and ✕ dismisses the CARD rather than the notification,
     * so getting a card out of the way never loses the thing it was about.
     */
    private View buildHeadsUpCard(DexNotifications.Item item) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(act.roundedFill(theme.card(), 14));
        card.setPadding(act.dp(12), act.dp(10), act.dp(6), act.dp(10));
        card.setElevation(act.dp(12));

        ImageView icon = new ImageView(act);
        if (item.icon != null) icon.setImageDrawable(item.icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(act.dp(28), act.dp(28));
        iconLp.rightMargin = act.dp(10);
        card.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView from = new TextView(act);
        from.setText(appLabel(item.pkg));
        from.setTextColor(theme.textFaint);
        from.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(10));
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(from);

        TextView title = new TextView(act);
        title.setText(item.title);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(13));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);

        if (!item.text.isEmpty()) {
            TextView text = new TextView(act);
            text.setText(item.text);
            text.setTextColor(theme.textDim);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12));
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(text);
        }
        if (!item.actions.isEmpty()) {
            // Pressing a button answers the card, so the card goes — leaving
            // it up for its remaining seconds would invite a second press on a
            // notification that has already been dealt with.
            texts.addView(actionRow(item, () -> dropCard(card)),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(act);
        close.setText("✕");
        close.setContentDescription(act.getString(R.string.lx_notif_dismiss));
        close.setTextColor(theme.textFaint);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11));
        close.setGravity(Gravity.CENTER);
        close.setBackground(act.tapBackground(0x00000000, theme.hover, 12));
        close.setOnClickListener(v -> dropCard(card));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(act.dp(26), act.dp(26));
        closeLp.gravity = Gravity.TOP;
        card.addView(close, closeLp);

        if (item.openable) {
            card.setOnClickListener(v -> {
                dropCard(card);
                if (!DexNotifications.open(item.key)) {
                    Toast.makeText(act, act.getString(R.string.lx_notif_gone),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
        DexFonts.applyTo(act, card);
        DexCursors.decorate(card);
        return card;
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = act.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private View buildBanner(DexNotifications.Item call) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(act.roundedFill(theme.card(), 16));
        card.setPadding(act.dp(16), act.dp(12), act.dp(12), act.dp(12));
        card.setElevation(act.dp(12));

        ImageView icon = new ImageView(act);
        if (call.icon != null) icon.setImageDrawable(call.icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(act.dp(34), act.dp(34));
        iconLp.rightMargin = act.dp(12);
        card.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView who = new TextView(act);
        who.setText(call.title);
        who.setTextColor(theme.text);
        who.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(15));
        who.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(who);
        TextView what = new TextView(act);
        what.setText(call.text.isEmpty() ? act.getString(R.string.lx_call_incoming) : call.text);
        what.setTextColor(theme.textFaint);
        what.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11.5f));
        what.setSingleLine(true);
        what.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(what);
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(callButton(act.getString(R.string.lx_call_decline), theme.danger, () -> {
            if (!DexNotifications.decline(call.key)) {
                Toast.makeText(act, act.getString(R.string.lx_call_gone),
                        Toast.LENGTH_SHORT).show();
            }
            hideCall(call.key);
        }));
        card.addView(callButton(act.getString(R.string.lx_call_answer), theme.positive, () -> {
            if (!DexNotifications.answer(call.key)) {
                Toast.makeText(act, act.getString(R.string.lx_call_gone),
                        Toast.LENGTH_SHORT).show();
            }
            hideCall(call.key);
        }));
        DexFonts.applyTo(act, card);
        DexCursors.decorate(card);
        return card;
    }

    /**
     * Answer / decline. Solid colour rather than the flyout's flat text
     * buttons, because these two are the only buttons on this desktop where
     * hitting the wrong one cannot be undone.
     */
    private View callButton(String label, int colour, Runnable onClick) {
        TextView button = new TextView(act);
        button.setText(label);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(12.5f));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10));
        button.setBackground(act.tapBackground(colour, colour, 12));
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = act.dp(8);
        button.setLayoutParams(lp);
        return button;
    }
}
