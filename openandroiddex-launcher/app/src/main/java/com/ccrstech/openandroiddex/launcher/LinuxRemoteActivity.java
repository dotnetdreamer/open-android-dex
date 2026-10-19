package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.List;

/**
 * The Remote access window: whether computers can reach this phone's Linux,
 * which ones may, and the code that lets a new one in.
 *
 * It reads the same files {@link RemoteHost} and the daemon write —
 * status.json, pairing.json, peers.json — on a thread of its own once a
 * second, and drives everything through {@link LinuxService} intents. Nothing
 * here talks to the daemon directly: the window lives in whatever process
 * opened it, and the daemon belongs to {@code :linux}.
 *
 * The pairing code is shown here and nowhere else. It is a credential for ten
 * minutes — whoever types it is let in — so it is not in a notification, not
 * in a log line, and not in anything a screenshot of the desktop's taskbar
 * would catch.
 *
 * Built in code at the display's current density, like every other window on
 * this desktop: an inflated layout brings the phone's density with it.
 */
public class LinuxRemoteActivity extends Activity {

    private static final long POLL_MS = 1000;
    private static final int REQ_NOTIFICATIONS = 0x52;

    private DexTheme theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler pollHandler;
    private LinearLayout column;
    private boolean live;
    /** What the window last showed, so a poll only rebuilds on a real change. */
    private String shownSignature = "";
    /** Ticks every second while a code is up, without a rebuild under it. */
    private TextView countdown;
    private long countdownUntil;
    private volatile LinuxRemote.Identity identity;

    /** Everything the window shows, read off disk in one go. */
    private static final class Snapshot {
        boolean available;
        boolean enabled;
        LinuxRemote.Status status;
        LinuxRemote.Pairing pairing;
        List<LinuxRemote.Peer> peers;
        boolean linuxInstalled;
        String linuxPhase;
        boolean sshdInstalled;
        boolean sshdGaveUp;
        boolean batteryExempt;
        /**
         * A code still on screen past its expiry: nothing is listening for it
         * any more (the daemon would have said so, unless it is gone), so it is
         * shown as expired rather than as a countdown stuck at 0:00.
         */
        boolean pairingExpired;

        String signature() {
            StringBuilder sb = new StringBuilder();
            sb.append(available).append(enabled).append(linuxInstalled).append(linuxPhase)
                    .append(sshdInstalled).append(sshdGaveUp).append(batteryExempt)
                    .append(status.live).append(status.online).append(status.ssh)
                    .append(pairing.state).append(pairing.code).append(pairing.reason)
                    .append(pairingExpired);
            for (LinuxRemote.Session s : status.sessions) {
                // RTT is left out on purpose: it moves every poll, and a row that
                // re-renders every second cannot be clicked reliably.
                sb.append(s.peer).append(s.direct);
            }
            for (LinuxRemote.Peer p : peers) sb.append(p.id);
            return sb.toString();
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
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.windowBg()));
        }
        ScrollView scroller = new ScrollView(this);
        scroller.setBackground(theme.surface(theme.windowBg(), 0f));
        scroller.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroller.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
        // The phone draws edge to edge at targetSdk 35, and this window is the
        // whole screen there; on the desktop display every inset is zero.
        scroller.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            }
            return insets;
        });
        DexCursors.decorate(scroller);
        pollThread = new HandlerThread("remote-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        // Spawns the binary once to read (or make) the identity — up to ten
        // seconds on a first start — so on a thread of its own: the poll's
        // thread would make the window wait for it before showing anything.
        Thread loadIdentity = new Thread(() -> {
            identity = LinuxRemote.identity(this);
            main.post(() -> {
                shownSignature = "";
                if (pollHandler != null) pollHandler.post(poll);
            });
        }, "remote-identity");
        loadIdentity.setDaemon(true);
        loadIdentity.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        live = true;
        pollHandler.post(poll);
    }

    @Override
    protected void onStop() {
        super.onStop();
        live = false;
        pollHandler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OwnWindows.closed(this);
        if (pollThread != null) pollThread.quit();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!live) return;
            final Snapshot snap = read();
            main.post(() -> show(snap));
            pollHandler.removeCallbacks(this);
            pollHandler.postDelayed(this, POLL_MS);
        }
    };

    private Snapshot read() {
        Snapshot s = new Snapshot();
        s.available = LinuxRemote.available(this);
        s.enabled = LinuxRemote.isEnabled(this);
        s.status = LinuxRemote.readStatus(this);
        s.pairing = LinuxRemote.readPairing(this);
        // A code means nothing with remote access off — nothing is listening
        // for it — and one can be left behind by a process that died with it
        // on screen. The host clears the file at its next stop; until then it
        // is simply not shown.
        boolean pairingOpen = "open".equals(s.pairing.state) || "starting".equals(s.pairing.state);
        if (!s.enabled && pairingOpen) s.pairing = new LinuxRemote.Pairing();
        s.peers = LinuxRemote.peers(this);
        s.linuxInstalled = Linux.isInstalled(this);
        Linux.Status st = Linux.readStatus(this);
        s.linuxPhase = st.phase;
        s.sshdInstalled = LinuxRemote.sshdInstalled(this);
        s.sshdGaveUp = LinuxRemote.sshdGaveUp(this);
        s.batteryExempt = batteryExempt();
        boolean open = "open".equals(s.pairing.state) || "starting".equals(s.pairing.state);
        s.pairingExpired = open && s.pairing.until > 0
                && System.currentTimeMillis() / 1000 > s.pairing.until + 2;
        return s;
    }

    private boolean batteryExempt() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void show(Snapshot snap) {
        if (isFinishing() || isDestroyed()) return;
        String sig = snap.signature();
        if (!sig.equals(shownSignature)) {
            shownSignature = sig;
            rebuild(snap);
        }
        tickCountdown();
    }

    // ── the window ──

    private void rebuild(Snapshot snap) {
        column.removeAllViews();
        countdown = null;
        header();
        if (!snap.available) {
            LinearLayout card = card();
            card.addView(note(s(R.string.rm_unavailable), theme.textDim));
            column.addView(card);
            DexFonts.applyTo(this, column);
            return;
        }
        statusCard(snap);
        computersCard(snap);
        if (!snap.peers.isEmpty()) connectCard();
        footnote();
        DexFonts.applyTo(this, column);
        DexCursors.decorate(column);
    }

    private void header() {
        TextView title = new TextView(this);
        title.setText(s(R.string.rm_label));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(21));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(title);

        TextView sub = new TextView(this);
        sub.setText(s(R.string.rm_subtitle));
        sub.setTextColor(theme.textDim);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(16);
        column.addView(sub, lp);
    }

    private void statusCard(Snapshot snap) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView state = new TextView(this);
        TextView detail = new TextView(this);
        String stateText;
        int stateColor;
        if (!snap.enabled) {
            stateText = s(R.string.rm_state_off);
            stateColor = theme.textDim;
            detail.setText(s(R.string.rm_state_off_sub));
        } else if (!snap.status.live) {
            stateText = s(R.string.rm_state_starting);
            stateColor = theme.textDim;
        } else if (snap.status.online) {
            stateText = "● " + s(R.string.rm_state_online);
            stateColor = theme.positive;
        } else {
            stateText = s(R.string.rm_state_offline);
            stateColor = theme.danger;
        }
        state.setText(stateText);
        state.setTextColor(stateColor);
        state.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(state);
        if (snap.enabled && snap.status.live) {
            detail.setText(sessionSummary(snap));
        }
        detail.setTextColor(theme.textDim);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        if (detail.getText().length() > 0) labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(button(s(snap.enabled ? R.string.rm_turn_off : R.string.rm_turn_on),
                snap.enabled ? theme.danger : theme.accent, 0xFFFFFFFF, () -> {
                    if (LinuxRemote.isEnabled(this)) {
                        LinuxService.remoteOff(this);
                    } else {
                        LinuxService.remoteOn(this);
                        askForBackground(snap.batteryExempt);
                        askForNotifications();
                    }
                    shownSignature = "";
                    pollHandler.postDelayed(poll, 300);
                }));
        card.addView(row);

        if (snap.enabled) {
            // What stands between "on" and a computer actually getting a shell
            // — read the way RemoteHost reads it: a guest with sshd in it is
            // usable when ready, and when a setup run stopped at an optional
            // step ("error").
            boolean usable = snap.sshdInstalled
                    && ("ready".equals(snap.linuxPhase) || "error".equals(snap.linuxPhase));
            if (!snap.linuxInstalled || "none".equals(snap.linuxPhase)) {
                card.addView(note(s(R.string.rm_linux_missing), theme.textDim));
                card.addView(inlineButton(s(R.string.rm_open_linux), this::openLinux));
            } else if (!usable && "error".equals(snap.linuxPhase)) {
                card.addView(note(s(R.string.rm_linux_error), theme.danger));
                card.addView(inlineButton(s(R.string.rm_open_linux), this::openLinux));
            } else if (!usable && "ready".equals(snap.linuxPhase) && snap.sshdGaveUp) {
                card.addView(note(s(R.string.rm_ssh_install_failed), theme.danger));
            } else if (!usable) {
                card.addView(note(s(R.string.rm_linux_installing), theme.textDim));
            } else if ("failed".equals(snap.status.ssh)) {
                card.addView(note(s(R.string.rm_ssh_failed), theme.danger));
            } else if ("starting".equals(snap.status.ssh) || "down".equals(snap.status.ssh)) {
                card.addView(note(s(R.string.rm_ssh_starting), theme.textFaint));
            }
            if (!snap.batteryExempt) {
                card.addView(note(s(R.string.rm_battery), theme.textDim));
                if (onPhone()) {
                    card.addView(inlineButton(s(R.string.rm_battery_allow),
                            () -> askForBackground(snap.batteryExempt)));
                } else {
                    card.addView(note(getString(R.string.rm_battery_desktop,
                            getApplicationInfo().loadLabel(getPackageManager())), theme.textFaint));
                }
            }
        }
        column.addView(card);
    }

    private String sessionSummary(Snapshot snap) {
        int n = snap.status.sessions.size();
        if (n > 0) return getResources().getQuantityString(R.plurals.rm_sessions, n, n);
        int paired = snap.peers.size();
        return paired == 0 ? s(R.string.rm_no_computers)
                : getResources().getQuantityString(R.plurals.rm_paired_count, paired, paired);
    }

    private void computersCard(Snapshot snap) {
        LinearLayout card = card();
        card.addView(sectionTitle(s(R.string.rm_computers)));
        if (snap.peers.isEmpty() && !isPairing(snap.pairing)) {
            card.addView(note(s(R.string.rm_no_computers), theme.textFaint));
        }
        for (LinuxRemote.Peer p : snap.peers) card.addView(peerRow(p, snap));

        String state = snap.pairing.state;
        if (snap.pairingExpired) {
            card.addView(resultPanel(false, s(R.string.rm_pair_failed_expired), null));
        } else if ("starting".equals(state) || "open".equals(state)) {
            card.addView(codePanel(snap.pairing));
        } else if ("paired".equals(state)) {
            card.addView(resultPanel(true, getString(R.string.rm_paired_ok, snap.pairing.name),
                    getString(R.string.rm_paired_how, LinuxRemote.sshAlias(this))));
        } else if ("failed".equals(state)) {
            int why = "bad-code".equals(snap.pairing.reason) ? R.string.rm_pair_failed_bad_code
                    : "expired".equals(snap.pairing.reason) ? R.string.rm_pair_failed_expired
                    : R.string.rm_pair_failed_other;
            card.addView(resultPanel(false, s(why), null));
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(12);
            TextView add = button(s(R.string.rm_add), theme.accent, 0xFFFFFFFF, this::addComputer);
            card.addView(add, lp);
        }
        column.addView(card);
    }

    private static boolean isPairing(LinuxRemote.Pairing p) {
        return !p.state.isEmpty();
    }

    private View peerRow(LinuxRemote.Peer p, Snapshot snap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));

        TextView icon = new TextView(this);
        icon.setText("macos".equals(p.os) ? "💻" : "🖥");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        row.addView(icon);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(p.name.isEmpty() ? LinuxRemote.shortId(p.id) : p.name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        text.addView(name);

        TextView sub = new TextView(this);
        LinuxRemote.Session session = null;
        for (LinuxRemote.Session s : snap.status.sessions) {
            if (s.peer.equals(p.id)) session = s;
        }
        String os = s("windows".equals(p.os) ? R.string.rm_os_windows
                : "macos".equals(p.os) ? R.string.rm_os_macos
                : "linux".equals(p.os) ? R.string.rm_os_linux : R.string.rm_os_other);
        if (session != null) {
            sub.setText(os + " · " + getString(session.direct ? R.string.rm_connected_direct
                    : R.string.rm_connected_relay, (int) session.rttMs));
            sub.setTextColor(theme.positive);
        } else {
            // A date, not "3 days ago": DateUtils capitalises relative phrases
            // for the start of a sentence ("Paired Yesterday"), and a date
            // tells two computers apart better anyway.
            String when = DateUtils.formatDateTime(this, p.added * 1000L,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
            sub.setText(os + " · " + getString(R.string.rm_paired_when, when));
            sub.setTextColor(theme.textFaint);
        }
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = new TextView(this);
        remove.setText(s(R.string.rm_remove));
        remove.setTextColor(theme.danger);
        remove.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        remove.setPadding(dp(10), dp(6), dp(10), dp(6));
        remove.setBackground(tapBackground(0x00000000, theme.hover, 8));
        remove.setOnClickListener(v -> confirmRemove(p));
        DexCursors.apply(remove, DexCursors.ROLE_HAND);
        row.addView(remove);
        return row;
    }

    private void confirmRemove(LinuxRemote.Peer p) {
        String name = p.name.isEmpty() ? LinuxRemote.shortId(p.id) : p.name;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.rm_remove_title, name))
                .setMessage(s(R.string.rm_remove_body))
                .setNegativeButton(s(R.string.rm_cancel), null)
                .setPositiveButton(s(R.string.rm_remove), (d, w) -> pollHandler.post(() -> {
                    LinuxRemote.removePeer(this, p.id);
                    poll.run();
                }))
                .show();
    }

    /** The code, big, with how to use it and how long it lasts. */
    private View codePanel(LinuxRemote.Pairing pairing) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(plainFill(theme.field, 12));
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        panel.setLayoutParams(lp);

        boolean open = "open".equals(pairing.state);
        TextView title = new TextView(this);
        title.setText(s(open ? R.string.rm_code_title : R.string.rm_code_preparing));
        title.setTextColor(theme.textDim);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        panel.addView(title);

        TextView code = new TextView(this);
        code.setText(LinuxRemote.displayCode(pairing.code));
        code.setTextColor(open ? theme.text : theme.textFaint);
        code.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(34));
        code.setTypeface(Typeface.MONOSPACE);
        code.setLetterSpacing(0.12f);
        code.setPadding(0, dp(6), 0, dp(4));
        // Selectable, so the desktop's own clipboard sharing can carry it to a
        // computer the phone is plugged into.
        code.setTextIsSelectable(true);
        panel.addView(code);

        countdown = new TextView(this);
        countdown.setTextColor(theme.textFaint);
        countdown.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        countdownUntil = pairing.until;
        panel.addView(countdown);
        tickCountdown();

        panel.addView(note(s(R.string.rm_code_how_app), theme.textDim));
        panel.addView(note(getString(R.string.rm_code_how_cli, LinuxRemote.displayCode(pairing.code)),
                theme.textFaint));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        panel.addView(button(s(R.string.rm_cancel), theme.hover, theme.text, () -> {
            LinuxService.remotePairCancel(this);
            shownSignature = "";
            pollHandler.post(() -> {
                LinuxRemote.writePairing(this, "", "", 0, "", "");
                poll.run();
            });
        }), blp);
        return panel;
    }

    private View resultPanel(boolean ok, String headline, String how) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(plainFill(theme.field, 12));
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        panel.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText((ok ? "✓ " : "") + headline);
        head.setTextColor(ok ? theme.positive : theme.danger);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        panel.addView(head);
        if (how != null) {
            TextView cmd = new TextView(this);
            cmd.setText(how);
            cmd.setTextColor(theme.text);
            cmd.setTypeface(Typeface.MONOSPACE);
            cmd.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
            cmd.setPadding(0, dp(6), 0, 0);
            panel.addView(cmd);
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        blp.rightMargin = dp(8);
        buttons.addView(button(s(R.string.rm_done), theme.hover, theme.text, () -> {
            shownSignature = "";
            pollHandler.post(() -> {
                LinuxRemote.writePairing(this, "", "", 0, "", "");
                poll.run();
            });
        }), blp);
        if (!ok) {
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(12);
            buttons.addView(button(s(R.string.rm_try_again), theme.accent, 0xFFFFFFFF, this::addComputer), alp);
        }
        panel.addView(buttons);
        return panel;
    }

    private void tickCountdown() {
        if (countdown == null) return;
        long left = countdownUntil - System.currentTimeMillis() / 1000;
        if (left < 0) left = 0;
        countdown.setText(getString(R.string.rm_code_expires,
                String.format(java.util.Locale.ROOT, "%d:%02d", left / 60, left % 60)));
    }

    /** How a paired computer gets in, and how to check it is talking to this phone. */
    private void connectCard() {
        LinearLayout card = card();
        card.addView(sectionTitle(s(R.string.rm_connect)));
        String alias = LinuxRemote.sshAlias(this);
        card.addView(copyRow("ssh " + alias, s(R.string.rm_connect_terminal)));
        card.addView(note(getString(R.string.rm_connect_vscode, alias), theme.textDim));
        if (identity != null) {
            card.addView(note(getString(R.string.rm_host_key, fingerprint(identity.hostKey)), theme.textFaint));
            card.addView(note(getString(R.string.rm_phone_id, LinuxRemote.shortId(identity.id)), theme.textFaint));
        }
        column.addView(card);
    }

    private View copyRow(String value, String caption) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(tapBackground(0x00000000, theme.hover, 10));
        DexCursors.apply(row, DexCursors.ROLE_HAND);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(theme.text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        v.setTypeface(Typeface.MONOSPACE);
        text.addView(v);
        TextView c = new TextView(this);
        c.setText(caption);
        c.setTextColor(theme.textFaint);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        text.addView(c);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = new TextView(this);
        copy.setText(s(R.string.rm_copy));
        copy.setTextColor(theme.accent);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        row.addView(copy);
        row.setOnClickListener(x -> {
            DexClipboard.set(this, value);
            Toast.makeText(this, s(R.string.rm_copied), Toast.LENGTH_SHORT).show();
        });
        return row;
    }

    private void footnote() {
        TextView text = new TextView(this);
        text.setText(s(R.string.rm_security_note));
        text.setTextColor(theme.textFaint);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        column.addView(text, lp);
    }

    // ── actions ──

    private void addComputer() {
        // Pairing switches remote access on if it was off, which makes this the
        // same moment Turn on is for the battery and notification questions.
        if (!LinuxRemote.isEnabled(this)) {
            askForBackground(batteryExempt());
            askForNotifications();
        }
        String code = LinuxRemote.newCode();
        long until = System.currentTimeMillis() / 1000 + LinuxRemote.PAIR_TTL_SECONDS;
        shownSignature = "";
        pollHandler.post(() -> {
            // Written here as well as by the service, so the code is on screen
            // the moment the button is pressed — the service's own write
            // follows as soon as the daemon has an endpoint listening for it.
            LinuxRemote.writePairing(this, "starting", code, until, "", "");
            LinuxService.remotePair(this, code);
            poll.run();
        });
    }

    private void openLinux() {
        try {
            startActivity(new Intent(this, LinuxAppActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Ask to be left out of battery optimisation: the platform's own dialog,
     * and only on the phone — on the desktop display a system dialog opens on
     * the phone's screen, behind the person who asked (see
     * LinuxActivity.askForNotifications), so there it is a sentence instead.
     *
     * This is not a nicety. Without it Doze defers the service's network and
     * timers for an idle phone, and One UI's sleeping-apps sweep stops it
     * altogether after a few days, and "reachable from anywhere" quietly stops
     * being true.
     */
    private void askForBackground(boolean alreadyExempt) {
        if (alreadyExempt || !onPhone()) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * The ongoing notification is the one sign on the phone that remote access
     * is on — a door is open — and its Turn off the quickest way to shut it.
     * From Android 13 it needs this permission to be shown at all, so ask as
     * remote access goes on. On the phone only, for the reason
     * LinuxActivity.askForNotifications gives: on the desktop display the
     * system dialog would open on the phone's own screen, behind the user.
     * Asked once; Android stops showing it of its own accord after a refusal.
     */
    private void askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || !onPhone()) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } catch (Exception e) {
            DexLog.warn("remote", "could not ask about notifications", e);
        }
    }

    private boolean onPhone() {
        @SuppressWarnings("deprecation")
        android.view.Display display = android.os.Build.VERSION.SDK_INT >= 30
                ? getDisplay() : getWindowManager().getDefaultDisplay();
        return display == null || display.getDisplayId() == android.view.Display.DEFAULT_DISPLAY;
    }

    /** `SHA256:…` of an `ssh-ed25519 …` line, as ssh itself would print it. */
    private static String fingerprint(String keyLine) {
        try {
            String[] parts = keyLine.trim().split("\\s+");
            byte[] blob = Base64.decode(parts[1], Base64.DEFAULT);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            return "?";
        }
    }

    // ── small pieces (the launcher's idiom, private copy) ──

    private String s(int res) {
        return getString(res);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(theme.surface(theme.cardSolid, dp(14)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(theme.textDim);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setAllCaps(true);
        title.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        title.setLayoutParams(lp);
        return title;
    }

    private TextView note(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView inlineButton(String text, Runnable onClick) {
        TextView view = button(text, theme.hover, theme.text, onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView button(String text, int fill, int textColor, Runnable onClick) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(10), dp(18), dp(10));
        view.setBackground(tapBackground(fill, lighten(fill), 12));
        view.setOnClickListener(v -> onClick.run());
        DexCursors.apply(view, DexCursors.ROLE_HAND);
        return view;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
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
