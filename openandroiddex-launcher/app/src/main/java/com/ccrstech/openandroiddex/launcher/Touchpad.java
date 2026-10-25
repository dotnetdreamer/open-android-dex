package com.ccrstech.openandroiddex.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's own screen as the touchpad of the desktop a computer is showing — what the
 * notification Samsung DeX puts on the phone opens, done this project's way. Opened by
 * {@link TouchpadNotice}'s notification (through {@link TouchpadActivity}) and by
 * Touchpad in the desktop's quick settings. {@link TouchpadGestures} says what the
 * fingers mean, and {@link DesktopPointer} is where that lands. See doc/phone-touchpad.md.
 *
 * <p><b>A window over the phone's screen, not an activity, because of focus.</b> Below
 * Android 14 only the topmost display has a focused window, and touching a focusable
 * window makes its display the topmost — so a pad that could be focused would take the
 * desktop's keyboard on every touch, and the computer's keys would wait for a window that
 * is no longer focused, holding every key behind them (AppTasks.focus in the daemon).
 *
 * <p>An activity was built first, and measured wrong on a Galaxy A13 (Android 12). Even
 * with its task made one that cannot take focus (WindowContainerTransaction.setFocusable,
 * from the daemon) and its window FLAG_NOT_FOCUSABLE, the launch itself had made it the
 * phone display's focused app, and nothing hands that back: the display was left with a
 * focused app and no focusable window. A Back from the phone's navigation bar waits on
 * such an app, every touch on the pad waits behind it — the same app, so the queue is
 * not cut — and the wait ends in "not responding". An overlay never enters that
 * bookkeeping: NOT_FOCUSABLE, it cannot be focused, and a touch on it moves no display to
 * the top. The phone's focused app stays the phone's own, and the desktop keeps its
 * keyboard. It is the shape the dock's own touchpad already has ({@link DexPointer}).
 *
 * <p><b>What that costs, and what is done about it.</b> An overlay is not in the phone's
 * stack of screens, so Home, a call being answered or an app opened from the shade would
 * come up underneath it. The pad watches what is in front of the phone's display (the
 * daemon's TOP) and gets out of the way as soon as that changes, and goes on the phone's
 * Home and Recents keys as well, which change nothing in front when the phone was showing
 * its home screen already. The phone's own Back goes to the app underneath, which is
 * still the phone's foreground app; ✕ is the pad's.
 *
 * <p>Android 11 and later: a window context, and the daemon's ways of finding tasks there.
 */
@android.annotation.TargetApi(Build.VERSION_CODES.R)
final class Touchpad implements TouchpadGestures.Listener {

    /** The pad keeps the phone awake this long after the last touch, then lets it sleep. */
    private static final long AWAKE_MS = 2 * 60_000L;
    /** How often the "waiting for the desktop" line is checked. */
    private static final long STATUS_MS = 1000L;
    /** How often the front of the phone's display is looked at, while the screen is on. */
    private static final long WATCH_MS = 500L;
    /**
     * The keyboard is handed back to the desktop as the pad opens, and once more this
     * long after: the notification's trampoline is still finishing when the pad opens,
     * and the phone's display takes the top again as it goes.
     */
    private static final long FOCUS_AGAIN_MS = 700L;

    /** Not the shell's palette: a full-screen white touchpad would be a torch in the hand. */
    private static final int PAD_BG = 0xFF0B0E14;
    private static final int PAD_SURFACE = 0xFF141923;
    private static final int PAD_EDGE = 0x1FFFFFFF;
    private static final int PAD_TEXT = 0xE6FFFFFF;
    private static final int PAD_DIM = 0x99FFFFFF;
    private static final int PAD_FAINT = 0x5CFFFFFF;
    private static final int PAD_SHEET = 0xFF1B2130;

    /** The pad that is up, or null. Main thread, like everything here. */
    private static Touchpad current;

    /**
     * Open the pad on the phone's screen for the desktop on {@code desktopDisplay}, or
     * leave it up if it already is. False, having said why, if it cannot be shown.
     */
    static boolean open(Context ctx, int desktopDisplay) {
        if (current != null) {
            if (current.desktop == desktopDisplay) return true;
            current.close();
        }
        Touchpad pad = new Touchpad(ctx.getApplicationContext(), desktopDisplay);
        if (!pad.attach()) return false;
        current = pad;
        return true;
    }

    /** The desktop has gone, or something else needs the pad down: close it if it is up. */
    static void closeAll() {
        if (current != null) current.close();
    }

    private final Context app;
    private final int desktop;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** For what is not the pointer: the window lists a gesture walks, and the watch. */
    private final WmClient actions = new WmClient();
    private Context ui;
    private WindowManager windows;
    private WindowManager.LayoutParams lp;
    private View root;
    private DesktopPointer pointer;
    private TouchpadGestures gestures;
    private PadSurface surface;
    private TextView status;
    private View sheet;
    private float density = 1f;
    private boolean awake;
    private boolean closed;
    /** The task in front of the phone's display when the pad came up; -2 until seen. */
    private int front = -2;

    /** The window ring, touched on the actions thread only. See {@link #cycleWindows}. */
    private final List<Integer> ring = new ArrayList<>();
    private int ringAt;

    private final Runnable sleepy = () -> setAwake(false);

    private final Runnable focusAgain = () -> {
        if (!closed) pointer.focusDesktop();
    };

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            status.setVisibility(pointer.answering() ? View.GONE : View.VISIBLE);
            main.postDelayed(this, STATUS_MS);
        }
    };

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            PowerManager power = app.getSystemService(PowerManager.class);
            if (power == null || power.isInteractive()) {
                actions.post(() -> {
                    String[] top = actions.top(Display.DEFAULT_DISPLAY);
                    if (top != null) main.post(() -> onFront(top));
                });
            }
            main.postDelayed(this, WATCH_MS);
        }
    };

    private final DisplayManager.DisplayListener displays = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            // The cable came out, or the computer closed the desktop. A reconnect
            // makes a new display, and a new notification for it.
            if (displayId != desktop) return;
            DexLog.step("touchpad", "the desktop's display went away — closing the touchpad");
            close();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == desktop && !closed) pointer.displayChanged();
        }
    };

    /**
     * The phone's Home or Recents key. The platform still tells receivers why it is
     * closing system dialogs, though apps may no longer ask it to; the reasons here are
     * the two keys, never the shade, which opens over the pad and must leave it be.
     */
    private final BroadcastReceiver keys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String reason = intent.getStringExtra("reason");
            if (closed || !("homekey".equals(reason) || "recentapps".equals(reason))) return;
            DexLog.step("touchpad", "the phone's " + reason + " — putting the pad away");
            close();
        }
    };

    /** Settings moved: the pointer's look, and the pad's own two dials. */
    private final BroadcastReceiver settings = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (closed) return;
            String key = intent.getStringExtra(DexPrefs.EXTRA_KEY);
            boolean all = key == null || "*".equals(key);
            if (all || DexCursors.isCursorKey(key)) pointer.restyle();
            if (all || DexPrefs.KEY_PAD_SPEED.equals(key)) pointer.readSpeed();
            if (all || DexPrefs.KEY_PAD_NATURAL.equals(key)) {
                gestures.setNatural(DexPrefs.getBool(app, DexPrefs.KEY_PAD_NATURAL,
                        DexPrefs.DEF_PAD_NATURAL));
            }
        }
    };

    private Touchpad(Context app, int desktop) {
        this.app = app;
        this.desktop = desktop;
    }

    // ── up and down ──

    private boolean attach() {
        if (!android.provider.Settings.canDrawOverlays(app)) {
            DexLog.warn("touchpad", "no permission to draw over other apps — no touchpad");
            Toast.makeText(app, app.getString(R.string.lx_pad_no_overlay), Toast.LENGTH_LONG).show();
            return false;
        }
        Display phone = app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) return false;
        // A window context, so the window manager is the phone display's, and the views
        // are measured at the phone's density whatever display the desktop is on. The
        // views take the desktop's language, as every screen of ours does.
        Context window = app.createDisplayContext(phone)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        windows = window.getSystemService(WindowManager.class);
        ui = DexLocale.wrap(window);
        density = ui.getResources().getDisplayMetrics().density;
        pointer = new DesktopPointer(app, desktop);
        gestures = new TouchpadGestures(this, density);
        gestures.setNatural(DexPrefs.getBool(app, DexPrefs.KEY_PAD_NATURAL,
                DexPrefs.DEF_PAD_NATURAL));
        root = build();
        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // The whole point — see the class comment. Touches still arrive; keys,
                // and the focus that would bring them, never do.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        lp.setTitle("Touchpad");
        lp.windowAnimations = android.R.style.Animation_Dialog;
        try {
            windows.addView(root, lp);
        } catch (RuntimeException e) {
            DexLog.warn("touchpad", "the touchpad's window was refused", e);
            Toast.makeText(app, app.getString(R.string.lx_pad_failed), Toast.LENGTH_LONG).show();
            pointer.release();
            actions.shutdown();
            return false;
        }
        app.getSystemService(DisplayManager.class).registerDisplayListener(displays, main);
        IntentFilter filter = new IntentFilter(DexPrefs.ACTION_CHANGED);
        // Not exported, either of them: one is ours, and the other is the system's,
        // which a receiver that is not exported still hears.
        IntentFilter system = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(settings, filter, Context.RECEIVER_NOT_EXPORTED);
            app.registerReceiver(keys, system, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(settings, filter);
            app.registerReceiver(keys, system);
        }
        pointer.show();
        pointer.focusDesktop();
        main.postDelayed(focusAgain, FOCUS_AGAIN_MS);
        setAwake(true);
        main.postDelayed(sleepy, AWAKE_MS);
        main.post(statusTick);
        main.post(watch);
        DexLog.step("touchpad", "open on the phone for the desktop on display " + desktop);
        return true;
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (current == this) current = null;
        main.removeCallbacksAndMessages(null);
        gestures.cancel();
        try {
            windows.removeViewImmediate(root);
        } catch (RuntimeException ignored) {
        }
        try {
            app.getSystemService(DisplayManager.class).unregisterDisplayListener(displays);
        } catch (RuntimeException ignored) {
        }
        try {
            app.unregisterReceiver(settings);
        } catch (RuntimeException ignored) {
        }
        try {
            app.unregisterReceiver(keys);
        } catch (RuntimeException ignored) {
        }
        pointer.release();
        actions.shutdown();
        DexLog.step("touchpad", "closed");
    }

    /**
     * Something new in front of the phone's display — Home, a call answered, an app opened
     * from the shade — would be underneath the pad, so the pad goes. Our own tasks are not
     * counted: the notification's trampoline passes through on its way to opening this.
     */
    private void onFront(String[] top) {
        if (closed || app.getPackageName().equals(top[1])) return;
        int task;
        try {
            task = Integer.parseInt(top[0]);
        } catch (NumberFormatException e) {
            return;
        }
        if (front == -2) {
            front = task;
            return;
        }
        if (task == front) return;
        DexLog.step("touchpad", "the phone brought " + top[1] + " to the front — putting the pad away");
        close();
    }

    /**
     * Keep the phone's screen on while the pad is being used, and let it go dark once it
     * has not been for a while — a phone left on the desk with the pad up must not stay
     * lit all night.
     */
    private void setAwake(boolean on) {
        if (closed || on == awake) return;
        awake = on;
        if (on) {
            lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        }
        try {
            windows.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {
        }
    }

    // ── what the fingers did ──

    @Override
    public void onTouchStart() {
        main.removeCallbacks(sleepy);
        main.postDelayed(sleepy, AWAKE_MS);
        setAwake(true);
        hideSheet();
        pointer.focusDesktop();
    }

    @Override
    public void onPoint(float dxDp, float dyDp, float speed) {
        pointer.moveBy(dxDp, dyDp, speed);
    }

    @Override
    public void onButtons(int buttons) {
        pointer.press(buttons);
    }

    @Override
    public void onClick(int button) {
        if (button == MotionEvent.BUTTON_SECONDARY) {
            surface.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        }
        pointer.click(button);
    }

    @Override
    public void onWheel(float h, float v) {
        pointer.scroll(h, v);
    }

    @Override
    public void onGesture(String slot) {
        surface.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        String action = actionFor(slot);
        DexLog.step("touchpad", slot + " -> " + action);
        act(action);
    }

    @Override
    public void onArmed(boolean armed) {
        surface.setArmed(armed);
        if (armed) surface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /**
     * The action a gesture is mapped to in Settings → Touchpad — the laptop touchpad's
     * mapping, one table for both pads. The defaults are DexPrefs', which are the desktop
     * app's own (default_action in gestures/mod.rs).
     */
    private String actionFor(String slot) {
        switch (slot) {
            case "gest3up":
                return DexPrefs.getString(app, DexPrefs.KEY_GESTURE_3UP, DexPrefs.DEF_GESTURE_3UP);
            case "gest3down":
                return DexPrefs.getString(app, DexPrefs.KEY_GESTURE_3DOWN, DexPrefs.DEF_GESTURE_3DOWN);
            case "gest3left":
                return DexPrefs.getString(app, DexPrefs.KEY_GESTURE_3LEFT, DexPrefs.DEF_GESTURE_3LEFT);
            case "gest3right":
                return DexPrefs.getString(app, DexPrefs.KEY_GESTURE_3RIGHT, DexPrefs.DEF_GESTURE_3RIGHT);
            case "gest3tap":
                return DexPrefs.getString(app, DexPrefs.KEY_GESTURE_3TAP, DexPrefs.DEF_GESTURE_3TAP);
            default:
                return "none";
        }
    }

    /**
     * Carry out one action, each down the channel that owns it — the channels the desktop
     * app uses for a laptop's gesture (Sink::act in gestures/mod.rs). Shell UI is the
     * launcher's, so it is a broadcast to it; keys go to the daemon; maximise is the
     * computer's, as the taskbar's own is.
     */
    private void act(String action) {
        switch (action) {
            case "openapps":
            case "drawer":
            case "notifications":
            case "notifcentre":
            case "showdesktop":
            // Home is the taskbar's own ○, not KEYCODE_HOME: a display with no home of its
            // own does nothing with that key, and One UI still tells every app the phone's
            // Home key was pressed — this pad among them, which then puts itself away
            // (measured on a Galaxy A13).
            case "home":
                app.sendBroadcast(new Intent(LauncherActivity.ACTION_GESTURE)
                        .setPackage(app.getPackageName())
                        .putExtra("action", action));
                break;
            case "nextwindow":
                cycleWindows(true);
                break;
            case "prevwindow":
                cycleWindows(false);
                break;
            case "maximize":
                maximizeFront();
                break;
            case "back":
                pointer.key(KeyEvent.KEYCODE_BACK);
                break;
            default:
                // "none", or a value from a newer Settings than this pad
                break;
        }
    }

    /**
     * Raise the next (or previous) window on the desktop, as a ring — three swipes through
     * three windows come back where they started. The laptop's gesture does the same and
     * says why it has to be remembered rather than read off the z-order each time
     * (Sink::cycle_window). Unlike it, our own windows — Settings, Linux — are in the
     * ring; only the desktop itself is not.
     */
    private void cycleWindows(boolean forward) {
        final int display = desktop;
        actions.post(() -> {
            List<WmClient.Task> tasks = actions.list(display);
            if (tasks == null) return;
            List<Integer> open = new ArrayList<>();
            for (WmClient.Task t : tasks) {
                if (t.visible && !isDesktopTask(t)) open.add(t.taskId);
            }
            if (open.size() < 2) return;
            if (ring.size() != open.size() || !ring.containsAll(open)) {
                ring.clear();
                ring.addAll(open);
                ringAt = 0;
            }
            int n = ring.size();
            ringAt = forward ? (ringAt + 1) % n : (ringAt + n - 1) % n;
            actions.front(display, ring.get(ringAt));
        });
    }

    /**
     * Maximise or restore the window in front. Handed to the computer by package, exactly
     * as the taskbar's app menu does: that side holds the bounds to come back to, and
     * would undo a resize made from here. Our own windows are left out, as the laptop's
     * gesture leaves them: the request names a package, and ours is the desktop's too.
     */
    private void maximizeFront() {
        final int display = desktop;
        actions.post(() -> {
            List<WmClient.Task> tasks = actions.list(display);
            if (tasks == null) return;
            for (WmClient.Task t : tasks) {
                if (!t.visible || app.getPackageName().equals(t.pkg)) continue;
                RequestProvider.enqueue("window", t.pkg);
                return;
            }
        });
    }

    private boolean isDesktopTask(WmClient.Task t) {
        return app.getPackageName().equals(t.pkg)
                && LauncherActivity.class.getName().equals(t.activity);
    }

    // ── the screen ──

    private int dp(float v) {
        return Math.round(v * density);
    }

    private View build() {
        DexTheme theme = DexTheme.of(app);
        LinearLayout panel = new LinearLayout(ui);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PAD_BG);
        panel.setPadding(dp(12), dp(4), dp(12), dp(8));

        // ── the header: what this is, its options, the way out ──
        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ui);
        title.setText(ui.getString(R.string.lx_pad_title));
        title.setTextColor(PAD_DIM);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(iconButton("⚙", ui.getString(R.string.tp_options), v -> toggleSheet()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(iconButton("✕", ui.getString(R.string.tp_close), v -> close()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // ── the pad ──
        FrameLayout padFrame = new FrameLayout(ui);
        surface = new PadSurface(ui, theme.accent);
        padFrame.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Over the surface and not clickable, so a touch on the words still lands on
        // the pad underneath them.
        TextView hints = new TextView(ui);
        hints.setText(ui.getString(R.string.tp_hints));
        hints.setTextColor(PAD_FAINT);
        hints.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        hints.setLineSpacing(dp(5), 1f);
        hints.setGravity(Gravity.CENTER);
        hints.setPadding(dp(28), 0, dp(28), 0);
        padFrame.addView(hints, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        status = new TextView(ui);
        status.setText(ui.getString(R.string.tp_waiting));
        status.setTextColor(theme.accent);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(14), dp(16), 0);
        status.setVisibility(View.GONE);
        padFrame.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        sheet = buildSheet(theme);
        sheet.setVisibility(View.GONE);
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetLp.setMargins(dp(10), 0, dp(10), dp(10));
        padFrame.addView(sheet, sheetLp);

        panel.addView(padFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── the desktop's own three, as the taskbar has them ──
        LinearLayout nav = new LinearLayout(ui);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(8), 0, 0);
        nav.addView(navButton("◁", ui.getString(R.string.lx_back),
                v -> pointer.key(KeyEvent.KEYCODE_BACK)), navParams());
        nav.addView(navButton("○", ui.getString(R.string.lx_home),
                v -> act("home")), navParams());
        nav.addView(navButton("▢", ui.getString(R.string.lx_recent_apps),
                v -> act("openapps")), navParams());
        panel.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        DexFonts.applyTo(app, panel);
        return panel;
    }

    private LinearLayout.LayoutParams navParams() {
        return new LinearLayout.LayoutParams(0, dp(60), 1f);
    }

    private TextView navButton(String glyph, String label, View.OnClickListener click) {
        TextView b = new TextView(ui);
        b.setText(glyph + "\n" + label);
        b.setTextColor(PAD_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(16));
        b.setContentDescription(label);
        b.setOnClickListener(click);
        return b;
    }

    private TextView iconButton(String glyph, String description, View.OnClickListener click) {
        TextView b = new TextView(ui);
        b.setText(glyph);
        b.setTextColor(PAD_DIM);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(24));
        b.setContentDescription(description);
        b.setOnClickListener(click);
        return b;
    }

    private Drawable ripple(float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    // ── the options sheet: the pad's speed and scrolling ──

    /**
     * The same two dials the dock's touchpad offers and the Linux viewer's Mouse method
     * has, kept in the same two keys: one pointer speed for every pad on this phone.
     */
    private View buildSheet(DexTheme theme) {
        LinearLayout box = new LinearLayout(ui);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PAD_SHEET);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        // Its own touches are its own: a slide on the slider must not move the pointer.
        box.setClickable(true);

        box.addView(sheetLabel(ui.getString(R.string.lx_pad_speed)));
        SeekBar speed = new SeekBar(ui);
        speed.setMax(DexPointer.MAX_SPEED - DexPointer.MIN_SPEED);
        speed.setProgress(Math.max(0, Math.min(speed.getMax(),
                DexPrefs.getInt(app, DexPrefs.KEY_PAD_SPEED, DexPrefs.DEF_PAD_SPEED)
                        - DexPointer.MIN_SPEED)));
        android.graphics.PorterDuffColorFilter tint = new android.graphics.PorterDuffColorFilter(
                theme.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        if (speed.getProgressDrawable() != null) speed.getProgressDrawable().setColorFilter(tint);
        if (speed.getThumb() != null) speed.getThumb().setColorFilter(tint);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                DexPrefs.put(app, DexPrefs.KEY_PAD_SPEED, bar.getProgress() + DexPointer.MIN_SPEED);
            }
        });
        box.addView(speed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(sheetLabel(ui.getString(R.string.lx_pad_scrolling)));
        LinearLayout chips = new LinearLayout(ui);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        boolean natural = DexPrefs.getBool(app, DexPrefs.KEY_PAD_NATURAL, DexPrefs.DEF_PAD_NATURAL);
        TextView naturalChip = chip(ui.getString(R.string.lx_pad_natural), natural, theme);
        TextView reverseChip = chip(ui.getString(R.string.lx_pad_reverse), !natural, theme);
        naturalChip.setOnClickListener(v -> setNatural(true, naturalChip, reverseChip, theme));
        reverseChip.setOnClickListener(v -> setNatural(false, naturalChip, reverseChip, theme));
        chips.addView(naturalChip);
        chips.addView(reverseChip);
        box.addView(chips);
        return box;
    }

    private TextView sheetLabel(String text) {
        TextView label = new TextView(ui);
        label.setText(text);
        label.setTextColor(PAD_DIM);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(dp(4), dp(8), 0, dp(6));
        return label;
    }

    private TextView chip(String text, boolean selected, DexTheme theme) {
        TextView chip = new TextView(ui);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(16), dp(8), dp(16), dp(8));
        styleChip(chip, selected, theme);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.rightMargin = dp(8);
        chip.setLayoutParams(chipLp);
        return chip;
    }

    private void styleChip(TextView chip, boolean selected, DexTheme theme) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? theme.accent : 0x14FFFFFF);
        bg.setCornerRadius(dp(16));
        chip.setBackground(bg);
        chip.setTextColor(selected ? 0xFFFFFFFF : PAD_DIM);
    }

    private void setNatural(boolean natural, TextView naturalChip, TextView reverseChip,
                            DexTheme theme) {
        DexPrefs.put(app, DexPrefs.KEY_PAD_NATURAL, natural);
        styleChip(naturalChip, natural, theme);
        styleChip(reverseChip, !natural, theme);
    }

    private void toggleSheet() {
        sheet.setVisibility(sheet.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void hideSheet() {
        if (sheet != null) sheet.setVisibility(View.GONE);
    }

    /**
     * The pad itself: a rounded surface that takes every touch on it, with a soft mark
     * under each finger so the phone answers the hand while the eyes are on the
     * computer's screen, and a ring around it while a hold has armed a right-click.
     */
    private final class PadSurface extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint armedRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] xs = new float[10];
        private final float[] ys = new float[10];
        private final RectF bounds = new RectF();
        private int fingers;
        private boolean armed;

        PadSurface(Context ctx, int accent) {
            super(ctx);
            fill.setColor(PAD_SURFACE);
            edge.setColor(PAD_EDGE);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density));
            dot.setColor((accent & 0x00FFFFFF) | 0x38000000);
            armedRing.setColor((accent & 0x00FFFFFF) | 0xB0000000);
            armedRing.setStyle(Paint.Style.STROKE);
            armedRing.setStrokeWidth(dp(2.5f));
        }

        void setArmed(boolean on) {
            armed = on;
            invalidate();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (!changed) return;
            // Slide in from the side of the phone and, with gesture navigation, the phone
            // takes it as Back. The platform honours 200dp of this per edge, so it covers
            // the middle of each side, where a finger heading for the far edge of the
            // desktop most often starts.
            int w = r - l;
            int h = b - t;
            int band = Math.min(h, dp(200));
            int top = (h - band) / 2;
            int side = dp(28);
            List<Rect> keep = new ArrayList<>();
            keep.add(new Rect(0, top, side, top + band));
            keep.add(new Rect(w - side, top, w, top + band));
            setSystemGestureExclusionRects(keep);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            fingers = 0;
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
                int gone = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
                for (int i = 0; i < e.getPointerCount() && fingers < xs.length; i++) {
                    if (i == gone) continue;
                    xs[fingers] = e.getX(i);
                    ys[fingers] = e.getY(i);
                    fingers++;
                }
            }
            gestures.onTouch(e);
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = dp(24);
            bounds.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(bounds, r, r, fill);
            bounds.inset(edge.getStrokeWidth() / 2f, edge.getStrokeWidth() / 2f);
            canvas.drawRoundRect(bounds, r, r, edge);
            float mark = dp(26);
            for (int i = 0; i < fingers; i++) {
                canvas.drawCircle(xs[i], ys[i], mark, dot);
            }
            if (armed && fingers > 0) canvas.drawCircle(xs[0], ys[0], mark + dp(8), armedRing);
        }
    }
}
