package com.ccrstech.openandroiddex.launcher;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;
import android.window.TrustedPresentationThresholds;

import java.util.function.Consumer;

/**
 * The phone's own screen as a game controller for its apps' windows on a computer
 * (doc/game-mode.md, "The phone as a controller"). Offered by the computer's app server
 * through {@link AppsProvider} while the computer shows one of the phone's apps; opened
 * from {@link GamepadNotice}'s notification (through {@link GamepadActivity}), which is up
 * while game controls are on in one of those windows, and from any of the windows' menus.
 * {@link GamepadView} is the controller, {@link GamepadLink} its way to the server, which
 * makes it a gamepad of the phone's own for the window in front on the computer.
 *
 * <p><b>A window over the phone's screen, not an activity</b>, for the touchpad's reason
 * ({@link Touchpad}), which matters more here: below Android 15 a gamepad's input goes to
 * the display that has the focus, and a controller that could be focused would take it to
 * the phone's own screen with its first touch. An overlay that is not focusable never
 * enters that bookkeeping. And it gets out of the way as the touchpad does: an app brought
 * to the front of the phone's screen, Home and Recents put it away, and Android keeping it
 * off the screen while Settings shows is said.
 *
 * <p><b>Landscape.</b> The window asks for it ({@code screenOrientation}), as a game would:
 * a phone held as a controller is held sideways. Where Android gives it a portrait screen
 * anyway, the controller is drawn turned ({@link GamepadView}).
 *
 * <p>Android 11 and later, as the touchpad: a window context.
 */
@android.annotation.TargetApi(Build.VERSION_CODES.R)
final class PhoneGamepad implements GamepadView.Listener, GamepadLink.Listener {

    /** The controller keeps the phone awake this long after the last touch: a cutscene is no reason to sleep. */
    private static final long AWAKE_MS = 5 * 60_000L;
    /** How often the front of the phone's display is looked at, while the screen is on. */
    private static final long WATCH_MS = 500L;
    /** About how long the phone's screen takes to turn landscape for the controller. */
    private static final long TURN_MS = 1200L;
    /** The window goes this soon after it asked for no orientation, at the latest: see {@link #close}. */
    private static final long REMOVE_LATEST_MS = 250L;
    /** A task new in front is looked at once more, this soon, before the controller goes (Touchpad says why). */
    private static final long FRONT_AGAIN_MS = 300L;
    /** A showing or a hiding of the window shorter than this is not taken as one. */
    private static final int SHOWN_STABLE_MS = 300;
    /** No word from Android that the window is on the screen this long after it opened: it is not. */
    private static final long SHOWN_WITHIN_MS = 2500L;
    /** Off the screen this long, the phone awake and unlocked, before the controller says it is kept off. */
    private static final long OFF_FOR_MS = 1500L;

    /** The controller that is up, or null. Main thread, like everything here. */
    private static PhoneGamepad current;
    /** Where the computer's app server takes the controller — its port and this run's token — while it offers it. */
    private static int computerPort = -1;
    private static String computerToken;

    /**
     * The computer offers the controller, through its server at {@code port}. One that is
     * up already goes to that server, if it is a new one: the computer came back another
     * way, cable or Wi‑Fi.
     */
    static void offeredByComputer(int port, String token) {
        computerPort = port;
        computerToken = token;
        if (current != null && !current.link.sameAs(port, token)) current.relink(port, token);
    }

    /** Whether the computer offers the controller now. */
    static boolean computerOffers() {
        return computerPort > 0 && computerToken != null;
    }

    /** Open the controller, or leave it up. False if the computer offers none, or it cannot be shown. */
    static boolean openForComputer(Context ctx) {
        if (!computerOffers()) return false;
        if (current != null) return true;
        PhoneGamepad pad = new PhoneGamepad(ctx.getApplicationContext());
        if (!pad.attach(computerPort, computerToken)) return false;
        current = pad;
        return true;
    }

    /** The computer no longer offers it: down, and forgotten. */
    static void computerGone() {
        computerPort = -1;
        computerToken = null;
        if (current != null) current.close();
    }

    /** Something needs the controller down: closed if it is up. */
    static void closeAll() {
        if (current != null) current.close();
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Where the front of the phone's screen is asked about: the answer waits on the server. */
    private final HandlerThread watching = new HandlerThread("gamepad-watch");
    private Handler watcher;
    private Context ui;
    private WindowManager windows;
    private WindowManager.LayoutParams lp;
    private GamepadView view;
    private GamepadLink link;
    private boolean awake;
    private boolean closed;
    /** A look at the front of the phone's screen is under way. */
    private boolean looking;

    // What the server last said, for the line in the controller's middle.
    private String targetPkg;
    private String targetLabel = "";
    private boolean held;
    private String refused;
    private boolean answering = true;

    /** The task in front of the phone's display when the controller came up, and its app; -2 until seen. */
    private int front = -2;
    private String frontPkg = "";
    /** A task seen in front once, not yet twice; -2 while none. */
    private int passing = -2;
    /** Whether Android has the window on the phone's screen, as its listener last said; null until it says. */
    private Boolean presented;
    private Consumer<Boolean> shown;
    private long openedAt;
    private long offSince;
    private boolean hidden;

    private final Runnable sleepy = () -> setAwake(false);

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            checkShown();
            PowerManager power = app.getSystemService(PowerManager.class);
            if (power == null || power.isInteractive()) lookAtFront();
            main.postDelayed(this, WATCH_MS);
        }
    };

    private final Runnable lookAgain = () -> {
        if (!closed) lookAtFront();
    };

    /** The phone's Home or Recents key: the controller goes, as the touchpad does (never the shade's). */
    private final BroadcastReceiver keys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String reason = intent.getStringExtra("reason");
            if (closed || !("homekey".equals(reason) || "recentapps".equals(reason))) return;
            DexLog.step("gamepad", "the phone's " + reason + " — putting the controller away");
            close();
        }
    };

    private PhoneGamepad(Context app) {
        this.app = app;
    }

    // ── up and down ──

    private boolean attach(int port, String token) {
        if (!android.provider.Settings.canDrawOverlays(app)) {
            DexLog.warn("gamepad", "no permission to draw over other apps — no controller");
            Toast.makeText(app, app.getString(R.string.gp_no_overlay), Toast.LENGTH_LONG).show();
            return false;
        }
        Display phone = app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) return false;
        // The phone display's window manager, and its density; the desktop's language, as every screen of ours.
        Context window = app.createDisplayContext(phone)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        windows = window.getSystemService(WindowManager.class);
        ui = DexLocale.wrap(window);
        view = new GamepadView(ui, DexTheme.of(app).accent, this);
        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Never focused: the gamepad's input follows the focus below Android 15 (see the class comment).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        lp.setTitle("Game controller");
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        lp.windowAnimations = android.R.style.Animation_Dialog;
        try {
            windows.addView(view, lp);
        } catch (RuntimeException e) {
            DexLog.warn("gamepad", "the controller's window was refused", e);
            Toast.makeText(app, app.getString(R.string.gp_failed), Toast.LENGTH_LONG).show();
            return false;
        }
        watching.start();
        watcher = new Handler(watching.getLooper());
        IntentFilter system = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        // Not exported: the system's, which a receiver that is not exported still hears.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(keys, system, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(keys, system);
        }
        link = new GamepadLink(port, token, this);
        link.start();
        refresh();
        setAwake(true);
        main.postDelayed(sleepy, AWAKE_MS);
        // The first look once the phone's screen has turned for the controller: what is in front then.
        main.postDelayed(watch, TURN_MS);
        openedAt = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= 35) view.post(this::watchShown);
        DexLog.step("gamepad", "open on the phone for the computer's windows");
        return true;
    }

    /** The computer's server is another now: the controller goes there, as it is. */
    private void relink(int port, String token) {
        if (closed) return;
        DexLog.step("gamepad", "the computer's server moved to port " + port + " — the controller follows");
        link.release();
        link = new GamepadLink(port, token, this);
        link.start();
        view.releaseAll();
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (current == this) current = null;
        main.removeCallbacksAndMessages(null);
        view.releaseAll();
        // Landscape given back first, the window gone after. Android looks at the screen's
        // orientation again on a window's relayout, but not as one goes once its exit
        // animation has run: removed at once, the screen stayed landscape, the window
        // gone and still named the reason (an Android 12 emulator, auto-rotate off). The
        // relayout is the next frame's, and a post waits behind it; an asynchronous one,
        // which nothing waits behind, goes a moment later should no frame come.
        GamepadView v = view;
        WindowManager wm = windows;
        boolean[] removed = new boolean[1];
        Runnable remove = () -> {
            if (removed[0]) return;
            removed[0] = true;
            try {
                wm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
            }
        };
        try {
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            lp.windowAnimations = 0;
            wm.updateViewLayout(v, lp);
            v.post(remove);
            Handler.createAsync(Looper.getMainLooper()).postDelayed(remove, REMOVE_LATEST_MS);
        } catch (RuntimeException e) {
            remove.run();
        }
        try {
            app.unregisterReceiver(keys);
        } catch (RuntimeException ignored) {
        }
        if (Build.VERSION.SDK_INT >= 35 && shown != null) {
            try {
                windows.unregisterTrustedPresentationListener(shown);
            } catch (RuntimeException ignored) {
            }
        }
        link.release();
        watching.quitSafely();
        DexLog.step("gamepad", "closed");
    }

    /** What is in front of the phone's display, asked off the main thread, to {@link #onFront}; one look at a time. */
    private void lookAtFront() {
        if (looking) return;
        looking = true;
        GamepadLink l = link;
        watcher.post(() -> {
            String[] top = l.top();
            main.post(() -> {
                looking = false;
                if (top != null) onFront(top);
            });
        });
    }

    /** Something new in front of the phone's display would be under the controller: it goes (Touchpad.onFront). */
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
            frontPkg = top[1];
            DexLog.step("gamepad", "the phone's screen shows " + top[1] + " (task " + task + ") under the controller");
            return;
        }
        if (task == front || top[1].equals(frontPkg)) {
            // The same app: the screen turning for the controller can give the app under it,
            // the home screen most of all, a task of its own for the new shape.
            front = task;
            passing = -2;
            return;
        }
        if (task != passing) {
            passing = task;
            main.postDelayed(lookAgain, FRONT_AGAIN_MS);
            return;
        }
        DexLog.step("gamepad", "the phone brought " + top[1] + " (task " + task + ", was " + front
                + ") to the front — putting the controller away");
        close();
    }

    /** The phone's screen kept on while the controller is used; let go dark once it has not been for a while. */
    private void setAwake(boolean on) {
        if (closed || on == awake) return;
        awake = on;
        if (on) {
            lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        }
        try {
            windows.updateViewLayout(view, lp);
        } catch (RuntimeException ignored) {
        }
    }

    // ── on the screen, or kept off it (Touchpad.watchShown) ──

    @android.annotation.TargetApi(35)
    private void watchShown() {
        if (closed) return;
        Consumer<Boolean> l = on -> {
            presented = on;
            checkShown();
        };
        try {
            windows.registerTrustedPresentationListener(view.getWindowToken(),
                    new TrustedPresentationThresholds(0.5f, 0.05f, SHOWN_STABLE_MS),
                    app.getMainExecutor(), l);
            shown = l;
        } catch (RuntimeException e) {
            DexLog.warn("gamepad", "cannot tell whether the controller is on the screen", e);
        }
    }

    private void checkShown() {
        if (closed || shown == null) return;
        PowerManager power = app.getSystemService(PowerManager.class);
        KeyguardManager lock = app.getSystemService(KeyguardManager.class);
        if ((power != null && !power.isInteractive()) || (lock != null && lock.isKeyguardLocked())) {
            offSince = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean off = presented != null ? !presented : now - openedAt > SHOWN_WITHIN_MS;
        if (!off) {
            offSince = 0;
        } else if (offSince == 0) {
            offSince = now;
        }
        if (!hidden) off = off && now - offSince >= OFF_FOR_MS;
        if (off == hidden) return;
        hidden = off;
        DexLog.step("gamepad", off ? "Android is keeping the controller off the screen: Settings, or a screen like it, is showing"
                : "the controller is on the screen again");
        if (off) {
            // Its fingers can no longer lift on it: nothing stays held in the game.
            view.releaseAll();
            Toast.makeText(app, ui.getString(R.string.gp_hidden), Toast.LENGTH_LONG).show();
        }
        link.hidden(off);
    }

    // ── what the fingers hold ──

    @Override
    public void onState(GamepadView.State s) {
        if (!closed) link.send(s);
    }

    @Override
    public void onTouched() {
        main.removeCallbacks(sleepy);
        main.postDelayed(sleepy, AWAKE_MS);
        setAwake(true);
    }

    @Override
    public void onClose() {
        DexLog.step("gamepad", "✕ — closing the controller");
        close();
    }

    // ── what the server says ──

    @Override
    public void onTarget(String pkg, String label) {
        targetPkg = pkg;
        targetLabel = label == null || label.isEmpty() ? pkg : label;
        refresh();
    }

    @Override
    public void onHeld(boolean on) {
        held = on;
        refresh();
    }

    @Override
    public void onRefused(String message) {
        refused = message;
        DexLog.warn("gamepad", "the phone makes no game controller: " + message);
        refresh();
    }

    @Override
    public void onAnswering(boolean on) {
        answering = on;
        refresh();
    }

    /** The line in the controller's middle: what it plays, and what is in the way. */
    private void refresh() {
        if (closed || view == null) return;
        if (!answering) {
            view.setStatus("", ui.getString(R.string.tp_waiting_computer), true);
        } else if (refused != null) {
            view.setStatus(targetLabel, ui.getString(R.string.gp_refused), true);
        } else if (targetPkg == null) {
            view.setStatus(ui.getString(R.string.gp_title), "", false);
        } else if (targetPkg.isEmpty()) {
            view.setStatus("", ui.getString(R.string.gp_no_window), true);
        } else if (held) {
            view.setStatus(targetLabel, ui.getString(R.string.gp_held), true);
        } else {
            view.setStatus(targetLabel, ui.getString(R.string.gp_playing), false);
        }
    }
}
