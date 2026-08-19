package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The desktop's Web window: where to open the viewer, the code that opens it,
 * and every switch that decides what the other end may do.
 *
 * <p>The window is the only place the access code is ever shown, which is what
 * makes it a credential rather than a formality — it is not in a log, not in a
 * URL, and not recoverable from the page.
 *
 * <p>Built in code, at the density this display currently has, for the same
 * reason as the rest of the shell: an inflated layout arrives carrying the
 * phone's density and lands wrong on a desktop that {@code wm density} has
 * resized.
 */
public class WebActivity extends Activity {

    private DexTheme theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScrollView scroller;
    private LinearLayout column;
    /**
     * False between onStop and onStart.
     *
     * The service announces its state from several background threads, and
     * every announcement rebuilds this whole view tree. Doing that to a window
     * that is not on screen is pure waste, and doing it while one is being torn
     * down and rebuilt is how a window ends up showing an empty frame.
     */
    private boolean live;
    /** Coalesces a burst of announcements into one rebuild. */
    private final Runnable rebuildTask = this::rebuild;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!live || isFinishing()) return;
            main.removeCallbacks(rebuildTask);
            main.postDelayed(rebuildTask, 120);
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
        theme = DexTheme.of(this);
        // The WINDOW's own background as well as the content view's. A window
        // whose only opaque surface is a child view shows black for any frame
        // where that child has not drawn yet — which is exactly what a freeform
        // window looks like while the system dialog in front of it goes away.
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.windowBg()));
        }
        scroller = new ScrollView(this);
        scroller.setBackground(theme.surface(theme.windowBg(), 0f));
        scroller.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroller.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
        rebuild();
    }

    @Override
    protected void onStart() {
        super.onStart();
        live = true;
        IntentFilter filter = new IntentFilter(Web.ACTION_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        rebuild();
    }

    @Override
    protected void onStop() {
        super.onStop();
        live = false;
        main.removeCallbacks(rebuildTask);
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
    }

    // ── the whole window, rebuilt on every state change ──

    private void rebuild() {
        if (column == null) return;
        column.removeAllViews();
        boolean running = WebService.isRunning();

        header();
        statusCard(running);
        if (running) addressCard();
        accessCard();
        pictureCard();
        webrtcCard();
        footnote();
    }

    private void header() {
        TextView title = new TextView(this);
        title.setText(getString(R.string.wb_label));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(21));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.wb_subtitle));
        sub.setTextColor(theme.textDim);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(16);
        column.addView(sub, lp);
    }

    private void statusCard(boolean running) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView state = new TextView(this);
        state.setText(running ? getString(R.string.wb_state_on) : getString(R.string.wb_state_off));
        state.setTextColor(running ? theme.positive : theme.textDim);
        state.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(state);

        TextView detail = new TextView(this);
        detail.setText(statusText(running));
        detail.setTextColor(theme.textDim);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        labels.addView(detail);

        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button(running ? getString(R.string.wb_stop) : getString(R.string.wb_start),
                running ? theme.danger : theme.accent, 0xFFFFFFFF, () -> {
                    if (WebService.isRunning()) {
                        WebService.stop(this);
                    } else {
                        WebService.start(this);
                    }
                    // The service answers with a state broadcast; this only
                    // stops the button looking dead in the meantime.
                    main.postDelayed(this::rebuild, 500);
                }));
        card.addView(row);

        String error = WebService.lastError();
        if (error != null && !running) card.addView(note(errorText(error), theme.danger));
        if (running && !WebService.rtcReady()) {
            card.addView(note(getString(R.string.wb_rtc_starting), theme.textDim));
        }
        if (Web.control(this) && CaptionService.live() == null) {
            card.addView(note(getString(R.string.wb_control_needs_service), theme.danger));
        }
        column.addView(card);
    }

    private String statusText(boolean running) {
        if (!running) return getString(R.string.wb_state_off_sub);
        int viewers = WebService.viewerCount();
        String who = viewers == 0
                ? getString(R.string.wb_no_viewers)
                : getResources().getQuantityString(R.plurals.wb_viewers, viewers, viewers);
        return who + " · " + WebService.streamSummary(this);
    }

    private String errorText(String code) {
        switch (code) {
            case "port-busy":
                return getString(R.string.wb_err_port);
            case "revoked":
                return getString(R.string.wb_err_revoked);
            case "no-consent":
                return getString(R.string.wb_err_consent);
            case "rtc-unavailable":
                return getString(R.string.wb_rtc_unavailable);
            default:
                return getString(R.string.wb_err_generic);
        }
    }

    /**
     * Where to point a browser.
     *
     * The rendezvous link comes first when there is one, because it is the only
     * address here that works when the browser is not on this network — which
     * is the whole reason the viewer is WebRTC. The local addresses below it
     * are for the ordinary case of a laptop in the same room.
     */
    private void addressCard() {
        LinearLayout card = card();
        card.addView(sectionTitle(getString(R.string.wb_open_on)));

        String rendezvous = WebRtc.enabled(this) && WebRtc.hasRendezvous(this)
                ? WebRtc.rendezvousLink(this) : "";
        if (!rendezvous.isEmpty()) {
            card.addView(addressRow(rendezvous, getString(R.string.wb_rtc_link)));
        }

        List<Web.Address> addresses = Web.addresses(this);
        if (addresses.isEmpty() && rendezvous.isEmpty()) {
            card.addView(note(getString(R.string.wb_no_network), theme.textDim));
        }
        for (Web.Address address : addresses) {
            card.addView(addressRow(address.url, address.label));
        }
        if (rendezvous.isEmpty()) {
            card.addView(note(getString(R.string.wb_local_only), theme.textFaint));
        }
        column.addView(card);
    }

    private View addressRow(String url, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(tapBackground(0x00000000, theme.hover, 10));
        DexCursors.apply(row, DexCursors.ROLE_HAND);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView value = new TextView(this);
        value.setText(url);
        value.setTextColor(theme.text);
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        value.setTypeface(Typeface.MONOSPACE);
        text.addView(value);

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(theme.textFaint);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        text.addView(caption);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = new TextView(this);
        copy.setText(getString(R.string.wb_copy));
        copy.setTextColor(theme.accent);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        row.addView(copy);

        row.setOnClickListener(v -> {
            DexClipboard.set(this, url);
            Toast.makeText(this, getString(R.string.wb_copied), Toast.LENGTH_SHORT).show();
        });
        return row;
    }

    private void accessCard() {
        LinearLayout card = card();
        card.addView(sectionTitle(getString(R.string.wb_access)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView pin = new TextView(this);
        pin.setText(spaced(Web.pin(this)));
        pin.setTextColor(theme.text);
        pin.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(26));
        pin.setTypeface(Typeface.MONOSPACE);
        row.addView(pin, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(button(getString(R.string.wb_new_code), theme.hover, theme.text, () -> {
            Web.newPin(this);
            // A new code has to mean the old sessions end, or it is not a new
            // code — it is a second one.
            WebService.newCode(this);
            rebuild();
            Toast.makeText(this, getString(R.string.wb_new_code_done),
                    Toast.LENGTH_SHORT).show();
        }));
        card.addView(row);
        card.addView(note(getString(R.string.wb_access_note), theme.textFaint));

        card.addView(toggle(getString(R.string.wb_allow_control),
                getString(R.string.wb_allow_control_sub),
                Web.control(this), value -> {
                    DexPrefs.put(this, Web.KEY_CONTROL, value);
                    WebService.apply(this);
                }));
        card.addView(toggle(getString(R.string.wb_allow_files),
                getString(R.string.wb_allow_files_sub),
                Web.files(this), value -> {
                    DexPrefs.put(this, Web.KEY_FILES, value);
                    WebService.apply(this);
                }));
        if (Web.files(this) && !WebFiles.hasAllFiles()) {
            card.addView(note(getString(R.string.wb_files_limited), theme.textDim));
        }
        column.addView(card);
    }

    private void pictureCard() {
        LinearLayout card = card();
        card.addView(sectionTitle(getString(R.string.wb_picture)));
        card.addView(chips(new String[]{"720", "1080", "native"},
                new String[]{getString(R.string.wb_q_720), getString(R.string.wb_q_1080),
                        getString(R.string.wb_q_native)},
                DexPrefs.getString(this, Web.KEY_QUALITY, Web.DEF_QUALITY),
                value -> {
                    DexPrefs.put(this, Web.KEY_QUALITY, value);
                    WebService.apply(this);
                }));
        card.addView(stepper(getString(R.string.wb_fps), Web.fps(this), 5, 60, 5, value -> {
            DexPrefs.put(this, Web.KEY_FPS, value);
            WebService.apply(this);
        }));
        card.addView(stepper(getString(R.string.wb_bitrate), Web.bitrate(this), 1, 30, 1, value -> {
            DexPrefs.put(this, Web.KEY_BITRATE, value);
            WebService.apply(this);
        }));
        card.addView(note(getString(R.string.wb_picture_note), theme.textFaint));
        column.addView(card);
    }

    /**
     * The two separate things WebRTC needs to reach a phone nobody can connect
     * to: a relay to carry the media, and a rendezvous to introduce the two
     * ends. Neither substitutes for the other, which is why they are two blocks
     * here rather than one switch.
     */
    private void webrtcCard() {
        LinearLayout card = card();
        card.addView(sectionTitle(getString(R.string.wb_rtc)));
        card.addView(note(WebRtc.describe(this),
                WebRtc.turnConfigured(this) ? theme.textFaint : theme.textDim));

        card.addView(textField(getString(R.string.wb_stun), WebRtc.KEY_STUN,
                WebRtc.DEF_STUN, false));
        card.addView(textField(getString(R.string.wb_turn), WebRtc.KEY_TURN,
                "turn:turn.example.com:3478", false));
        card.addView(textField(getString(R.string.wb_turn_user), WebRtc.KEY_TURN_USER, "", false));
        card.addView(textField(getString(R.string.wb_turn_pass), WebRtc.KEY_TURN_PASS, "", true));
        card.addView(toggle(getString(R.string.wb_relay_only),
                getString(R.string.wb_relay_only_sub),
                DexPrefs.getBool(this, WebRtc.KEY_RELAY_ONLY, false), value -> {
                    DexPrefs.put(this, WebRtc.KEY_RELAY_ONLY, value);
                    WebService.apply(this);
                }));

        card.addView(textField(getString(R.string.wb_signal), WebRtc.KEY_SIGNAL,
                "wss://dex.example.com/signal", false));
        card.addView(note(getString(R.string.wb_signal_sub), theme.textFaint));

        if (WebRtc.hasRendezvous(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(10);
            row.setLayoutParams(rowLp);

            TextView room = new TextView(this);
            room.setText(getString(R.string.wb_room) + "  " + WebRtc.room(this));
            room.setTextColor(theme.textDim);
            room.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
            room.setTypeface(Typeface.MONOSPACE);
            row.addView(room, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(button(getString(R.string.wb_new_room), theme.hover, theme.text, () -> {
                WebRtc.newRoom(this);
                // The room IS the address. A new one has to mean the old link
                // stops working, so the session is restarted rather than left
                // hosting a room nobody will look for any more.
                if (WebService.isRunning()) {
                    Toast.makeText(this, getString(R.string.wb_restart_needed),
                            Toast.LENGTH_LONG).show();
                }
                rebuild();
            }));
            card.addView(row);

            String state = WebService.signalState();
            if (state != null) {
                boolean waiting = "waiting".equals(state);
                boolean failed = "error".equals(state);
                int colour = waiting ? theme.positive : (failed ? theme.danger : theme.textDim);
                int text = waiting ? R.string.wb_signal_waiting
                        : (failed ? R.string.wb_signal_error : R.string.wb_signal_connecting);
                card.addView(note(getString(text), colour));
            }
        }
        column.addView(card);
    }

    private void footnote() {
        TextView text = new TextView(this);
        text.setText(getString(R.string.wb_security_note));
        text.setTextColor(theme.textFaint);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        column.addView(text, lp);
    }

    /** "123456" reads as a number; "123 456" reads as something to type. */
    private static String spaced(String pin) {
        return pin.length() == 6 ? pin.substring(0, 3) + " " + pin.substring(3) : pin;
    }

    // ── small pieces ──

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
        lp.bottomMargin = dp(8);
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

    private interface OnBool {
        void set(boolean value);
    }

    private View toggle(String title, String subtitle, boolean value, OnBool onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        row.setLayoutParams(rowLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(theme.text);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        labels.addView(head);
        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(value);
        toggle.setOnCheckedChangeListener((v, checked) -> onChange.set(checked));
        DexCursors.apply(toggle, DexCursors.ROLE_HAND);
        row.addView(toggle);
        return row;
    }

    private interface OnString {
        void set(String value);
    }

    /** A one-of-N picker, as a row of pills. */
    private View chips(String[] values, String[] labels, String selected, OnString onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            boolean on = value.equals(selected);
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextColor(on ? theme.accent : theme.textDim);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setBackground(on ? plainFill(theme.accentSoft, 10)
                    : tapBackground(0x00000000, theme.hover, 10));
            chip.setOnClickListener(v -> {
                onPick.set(value);
                rebuild();
            });
            DexCursors.apply(chip, DexCursors.ROLE_HAND);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    private interface OnInt {
        void set(int value);
    }

    /**
     * A number with − and + rather than a slider.
     *
     * The values here are ones people nudge rather than sweep, and this desktop
     * is driven by a mouse over a video stream where a slider thumb is a small
     * target that moves.
     */
    private View stepper(String title, int value, int min, int max, int step, OnInt onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        row.setLayoutParams(rowLp);

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(theme.text);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        row.addView(head, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final int[] current = {value};
        TextView readout = new TextView(this);
        readout.setText(String.valueOf(value));
        readout.setTextColor(theme.textDim);
        readout.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        readout.setGravity(Gravity.CENTER);
        readout.setMinWidth(dp(56));

        row.addView(button("−", theme.hover, theme.text, () -> {
            current[0] = Math.max(min, current[0] - step);
            readout.setText(String.valueOf(current[0]));
            onChange.set(current[0]);
        }));
        row.addView(readout);
        row.addView(button("+", theme.hover, theme.text, () -> {
            current[0] = Math.min(max, current[0] + step);
            readout.setText(String.valueOf(current[0]));
            onChange.set(current[0]);
        }));
        return row;
    }

    /**
     * A labelled text setting, written back when focus leaves it.
     *
     * On focus loss rather than on every keystroke: these reconfigure a live
     * session, and doing that per character while somebody types a URL is both
     * useless and destructive.
     */
    private View textField(String label, String key, String hint, boolean secret) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(10);
        wrap.setLayoutParams(wrapLp);

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(theme.textDim);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        wrap.addView(caption);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_URI);
        if (secret) {
            field.setTransformationMethod(new PasswordTransformationMethod());
            // And then tell the platform this one is not the kind of secret it
            // thinks it is.
            //
            // Android 15's sensitive-content protection marks the whole WINDOW
            // secure while a password field is on screen and a screen capture
            // is running, so the capture sees a black rectangle instead. That
            // is right on a phone and catastrophic here: this desktop IS a
            // capture — scrcpy's virtual display is not a secure one — so the
            // moment the viewer's own MediaProjection started, this window went
            // black on the only screen the user has. Measured in SurfaceFlinger
            // as `(Secure) … SENSITIVE_FOR_PRIVACY … isSecure=true` on the
            // WebActivity layer.
            //
            // The masking above stays, so the credential is still not shoulder-
            // surfable. What is given up is the platform blanking a window that
            // the user is deliberately looking at over a video link.
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                field.setContentSensitivity(View.CONTENT_SENSITIVITY_NOT_SENSITIVE);
            }
        }
        field.setHint(hint);
        field.setText(DexPrefs.getString(this, key, ""));
        field.setTextColor(theme.text);
        field.setHintTextColor(theme.textFaint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        field.setBackground(plainFill(theme.field, 10));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setOnFocusChangeListener((v, has) -> {
            if (has) return;
            String value = field.getText().toString().trim();
            if (value.equals(DexPrefs.getString(this, key, ""))) return;
            DexPrefs.put(this, key, value);
            WebService.apply(this);
        });
        wrap.addView(field);
        return wrap;
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
