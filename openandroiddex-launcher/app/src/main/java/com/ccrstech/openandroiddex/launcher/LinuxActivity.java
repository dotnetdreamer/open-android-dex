package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.Dialog;
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
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The desktop's Linux window: Ubuntu under proot, XFCE through TigerVNC's
 * Xvnc, rendered by the noVNC page a loopback websockify serves — this
 * activity is only the viewer plus the install narration in front of it.
 *
 * The app owns the container: {@link LinuxService} downloads the rootfs, runs
 * the install, and hosts the runtime, all under this app's own uid. This
 * window drives it purely through the service and by reading {@link Linux}'s
 * on-disk state — no daemon, no PC. It shows whichever of four screens the
 * state calls for: installing (with real progress), starting, the live
 * WebView, or an error with a Retry.
 *
 * Closing the window shuts the container down — the caption's ✕ is routed here
 * as {@link LauncherActivity#ACTION_CLOSE_WINDOW} rather than removing the
 * task, precisely so the question can be asked first. A MINIMISED window
 * (onStop only) keeps everything running, so reopening is instant; that is the
 * way to put the session aside without ending it. The installed distro lives
 * in private storage and survives either way.
 *
 * The UI is built in code for the same reason the rest of the launcher is:
 * it must be created at whatever density `wm density` last put on this
 * display, and an inflated layout brings the phone's density with it.
 */
public class LinuxActivity extends Activity {

    /** Status-poll cadence. Cheap: it stats a few files in private storage. */
    private static final long POLL_MS = 1500;
    /**
     * phase=none for this long means provisioning never started (state.env
     * absent), so ask the service once.
     */
    private static final long NONE_KICK_MS = 10_000;
    /**
     * Cooldown between START sends. A cooldown rather than a once-flag: the
     * service's "already running" check reads rt.pid, which the runtime writes
     * a beat after the spawn — re-sending inside that gap would boot a second
     * Xvnc, but never re-sending would leave a crashed runtime dead for as
     * long as the window stays open.
     */
    private static final long START_RETRY_MS = 10_000;
    /** noVNC page loads attempted, one second apart, before giving up. */
    private static final int MAX_LOAD_TRIES = 40;

    private static final String UI_NONE = "";
    private static final String UI_INSTALL = "install";
    private static final String UI_START = "start";
    private static final String UI_VNC = "vnc";
    private static final String UI_ERROR = "error";

    private DexTheme theme;
    /** Off-main thread for the status poll (it reads files, not a socket). */
    private android.os.HandlerThread pollThread;
    private Handler pollHandler;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    /** Which of the four screens is up — so a poll only rebuilds on a change. */
    private String uiState = UI_NONE;
    private ProgressBar installBar;
    private TextView installMsg;
    private WebView webView;
    /**
     * The opaque "starting / connecting" card. It sits ON TOP of the WebView
     * while the guest desktop boots and noVNC hands-shakes, so the user never
     * sees a black half-loaded page — and its message updates with the real
     * startup stage. Removed only once noVNC has actually painted a frame.
     */
    private FrameLayout startCard;
    private TextView startMsg;
    /** Set by the health probe once noVNC's canvas has a real framebuffer. */
    private boolean vncConnected;

    /** When phase=none was first seen; 0 while it is anything else. */
    private long noneSince;
    /** The none-timer re-kick fires once per window, not once per poll. */
    private boolean kicked;
    private long startSentAt;
    /**
     * Set by Retry: phase stays "error" until the PC overwrites state.env,
     * and without the latch the very next poll would flip the screen
     * straight back to the error it just retried.
     */
    private boolean retryPending;
    /**
     * Latched when MAX_LOAD_TRIES runs out. Without it the error screen
     * lives exactly one poll: the next ready+running status would re-enter
     * showVnc, reset the counter and start another 40-try cycle — the
     * terminal error state would flash for 1.5s forever. Cleared by Retry
     * (which restarts the runtime) and by the runtime going away on its own.
     */
    private boolean connectFailed;
    /**
     * The rt pid the current WebView was built for. A loaded noVNC page never
     * reconnects on its own, so when the runtime is replaced (session-end
     * kill, crash, Retry's stop) the viewer must be rebuilt — and the swap is
     * detected by IDENTITY, not by a RUNNING 0→1 edge: a stop+start can
     * bounce entirely inside one poll period, which an edge check misses
     * (measured on device — the window showed noVNC's "connection is closed"
     * over a perfectly live desktop until closed and reopened).
     */
    private int shownRtPid;
    private int loadTries;
    /** Consecutive polls the noVNC page reported itself disconnected. */
    private int deadPagePolls;
    private boolean destroyed;
    /**
     * Latched when the user restarts an ended session, until the marker
     * linux-rt.sh left behind is actually gone. LinuxService clears it
     * asynchronously, so without the latch the next poll or two would put the
     * same screen straight back up.
     */
    private boolean sessionRestartPending;
    /** Title currently on the terminal screen, so a different one rebuilds it. */
    private String shownActionTitle;
    /** The close question, so a second ✕ does not stack another one. */
    private Dialog closeDialog;

    /**
     * The caption's ✕. It arrives as a broadcast instead of a task removal
     * because a removed task cannot ask anything — see
     * {@link LauncherActivity#ACTION_CLOSE_WINDOW}.
     */
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            // Addressed by activity name. This used to be the only window that
            // asked before closing, so an unfiltered receiver was harmless;
            // the Docker window asks too now, and both live in this process,
            // so an unfiltered one would put BOTH questions on screen when
            // either caption's ✕ was pressed.
            String who = intent.getStringExtra("activity");
            if (who != null && !LinuxActivity.class.getName().equals(who)) return;
            confirmShutdown();
        }
    };

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
        root = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // ESC closes the window like Settings — but only while OUR
                // chrome is up. Once the guest desktop is live, Escape
                // belongs to it (vim, dialogs), not to the window.
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                        && !UI_VNC.equals(uiState)) {
                    if (event.getAction() == KeyEvent.ACTION_UP) finish();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setBackground(theme.surface(theme.windowBg(), 0f));
        setContentView(root);
        applyInsets();
        DexCursors.decorate(root);
        // Kick provisioning ourselves — the app owns it now, no PC push. Safe
        // and idempotent: the service no-ops if the distro is already installed
        // AND already carries this build's features.
        LinuxService.provision(this);
        IntentFilter closeFilter = new IntentFilter(LauncherActivity.ACTION_CLOSE_WINDOW);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, closeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, closeFilter);
        }
        pollThread = new android.os.HandlerThread("linux-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        pollHandler.post(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        OwnWindows.closed(this);
        if (closeDialog != null) {
            closeDialog.dismiss();
            closeDialog = null;
        }
        try {
            unregisterReceiver(closeReceiver);
        } catch (Exception ignored) {
        }
        if (pollThread != null) pollThread.quit();
        dropWebView();
        // No viewer left, so no reason to keep the container burning battery.
        // This is onDestroy, not onStop, so a MINIMISED window keeps running.
        //
        // Except when the window is only being REBUILT. This activity does not
        // declare `density` in its configChanges (the desktop activity does),
        // so a Display size change destroys and recreates it — and stopping
        // there would kill a live Ubuntu session because the user moved a
        // slider in Settings.
        if (!isChangingConfigurations()) LinuxService.stop(this);
    }

    /**
     * The whole state machine hangs off this poll. It runs off the main thread
     * (it stats a few files) and is deliberately NOT gated on onResume: on this
     * desktop a window spends most of its life STOPPED behind other windows,
     * and an install that only advanced while we were frontmost would look
     * permanently stuck.
     */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            final Linux.Status st = Linux.readStatus(LinuxActivity.this);
            // Read the runtime log here (off-main) so the connecting screen can
            // name the real startup stage.
            final String rtLog = st.running ? Linux.rtLog(LinuxActivity.this) : "";
            main.post(() -> apply(st, rtLog));
            pollHandler.postDelayed(this, POLL_MS);
        }
    };

    /** One status → UI step. Main thread. */
    private void apply(Linux.Status st, String rtLog) {
        if (destroyed) return;
        if (st == null) return;

        if (!"error".equals(st.phase)) retryPending = false;
        // A connect verdict is only about THIS runtime while it lives; a
        // phase change or the runtime dying invalidates it.
        if (!"ready".equals(st.phase) || !st.running) {
            connectFailed = false;
            vncConnected = false;
        }

        if ("error".equals(st.phase) && !retryPending) {
            showError(s(R.string.ln_error), msgWords(st));
            return;
        }

        if ("ready".equals(st.phase)) {
            noneSince = 0;
            if (connectFailed) return; // sticky error screen until Retry
            if (!st.sessionEnded) sessionRestartPending = false;
            // A session that ended by itself stays ended until the user says
            // otherwise. The "no runtime? start one" rule below is what made a
            // logout boot a fresh desktop a second later.
            if (!st.running && st.sessionEnded && !sessionRestartPending) {
                boolean clean = st.sessionExit == 0;
                showAction(s(clean ? R.string.ln_session_ended : R.string.ln_session_failed),
                        s(clean ? R.string.ln_session_ended_sub : R.string.ln_session_failed_sub),
                        s(R.string.ln_session_start));
                return;
            }
            if (st.running) {
                ensureVnc(st);        // WebView loads underneath the card
                probeVncHealth();     // async: sets vncConnected / reloads a dead page
                if (vncConnected) {
                    hideStartCard();  // reveal the live desktop
                } else {
                    showStartCard(connectingMsg(rtLog));
                    // Keep nudging until we actually connect: harmless while the
                    // runtime is healthy (the service dedups on its runtimeUp
                    // flag), and it is what re-launches a stale runtime whose
                    // pid file outlived it — the port would otherwise never open.
                    requestStart();
                }
            } else {
                requestStart();
                enterStarting();
            }
            return;
        }

        // none / pushing / extracting / configuring / apt-update /
        // installing-desktop — and "error" right after Retry, while the PC
        // has yet to overwrite state.env
        // The container can be uninstalled out from under this window, from the
        // tile's own menu. Nothing below could make progress if it were —
        // provision() is deliberately inert while uninstalled — so the window
        // would sit on "waiting" for as long as it stayed open. Close instead.
        if (Linux.isUninstalled(this)) {
            finish();
            return;
        }
        trackNone(st);
        showInstalling(st);
    }

    private void trackNone(Linux.Status st) {
        if (!"none".equals(st.phase)) {
            noneSince = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (noneSince == 0) {
            noneSince = now;
        } else if (now - noneSince > NONE_KICK_MS && !kicked) {
            kicked = true;
            LinuxService.provision(this);
        }
    }

    /**
     * Keep the window's content out from under the system bars.
     *
     * On the desktop display this does nothing at all — a scrcpy virtual
     * display has no status bar and no gesture strip, so every inset is zero.
     * It is the phone-only case this exists for: targetSdk 35 draws every
     * activity edge-to-edge by default, so without it the viewer's control bar
     * sits UNDER the status bar and the key row under the gesture strip.
     *
     * It has to be done here rather than in the page. `env(safe-area-inset-*)`
     * is the obvious answer and is the wrong one: in a WebView those report the
     * DISPLAY CUTOUT and never the system bars, so on a phone without a notch
     * they are all zero while the status bar is very much there. The page has
     * no safe-area handling of its own at all, deliberately: with the viewport
     * already moved, a device where env() is NOT zero would be padded for the
     * same cutout twice.
     *
     * Padding the root rather than letting the picture bleed under the bars is
     * deliberate: this window is someone's desktop, and a status bar sitting on
     * top of it would put XFCE's own panel and menus underneath.
     */
    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                left = i.left; top = i.top; right = i.right; bottom = i.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            // Consume nothing: the WebView is a child of this view and the
            // start card is its sibling, and both want to sit inside the
            // padding just set rather than to see the insets themselves.
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * Ask the service to boot the container at a sensible desktop size.
     *
     * The window's own pixels are the right answer on the desktop display: the
     * viewer scales the framebuffer to the stage in CSS px, so a framebuffer
     * sized in device pixels lands 1:1 whatever dpi the display-size preset
     * picked. They are the wrong answer on the phone's own panel: at density
     * ~2.75 this window is ~972x2062 PIXELS, so Xvnc would be told to make a
     * desktop that shape and the viewer would squeeze it back into ~354 CSS px
     * of width. XFCE's 10 pt menus end up under a millimetre tall, and no
     * amount of chrome design rescues that. The phone gets a fixed landscape
     * desktop it pans and zooms around instead — which also means rotating the
     * phone does not reflow XFCE.
     *
     * The two are told apart by DISPLAY, not by density. Density looks like it
     * would work and does not: the desktop display's density is whatever
     * SettingsActivity's resolution and size presets computed, 1.0 only at the
     * 1080p preset (the same trap CaptionService documents), so a 1440p desktop
     * would have taken the phone branch and booted a 1280x800 guest into a
     * 2560-wide window.
     */
    private void requestStart() {
        long now = SystemClock.uptimeMillis();
        if (now - startSentAt < START_RETRY_MS) return;
        startSentAt = now;
        final int w, h;
        android.view.Display display = getDisplay();
        if (display != null && display.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
            w = Math.max(root.getWidth(), 800);
            h = Math.max(root.getHeight(), 600);
        } else {
            w = 1280;
            h = 800;
        }
        LinuxService.start(this, w, h);
        DexLog.step("linux", "START " + w + "x" + h);
    }

    /**
     * Retry. Two different failures land on the error screen: a runtime we
     * could not connect to (stop it and let the poll boot a fresh one) and a
     * failed install (kick a fresh provision and watch).
     */
    private void retryProvision() {
        if (connectFailed || Linux.rtExit(this).exists()) {
            connectFailed = false;
            // The stop below clears rt.exit, but asynchronously — hold the
            // ended screen off until the poll actually sees it gone.
            sessionRestartPending = true;
            vncConnected = false;
            loadTries = 0;
            startSentAt = 0;
            uiState = UI_NONE;
            shownActionTitle = null;
            LinuxService.stop(this);
            enterStarting();
            return;
        }
        LinuxService.provision(this);
        retryPending = true;
        kicked = true;          // the none-timer must not enqueue a second one
        noneSince = 0;
        loadTries = 0;
        startSentAt = 0;
        uiState = UI_NONE;
        shownActionTitle = null;
        showInstalling(new Linux.Status());
    }

    // ── closing ──

    /**
     * The close question.
     *
     * Closing this window is not like closing the others: it ends a Linux
     * session that may have editors, builds or downloads inside it, and nothing
     * in the guest gets a chance to save. That is worth one confirmation. The
     * alternative is spelled out rather than implied — MINIMISING keeps
     * everything running, which is what people actually want most of the time.
     *
     * Shut down goes through the service, which kills the runtime's whole
     * process group; finishing the activity alone would only close the viewer.
     */
    private void confirmShutdown() {
        if (isFinishing()) return;
        if (closeDialog != null && closeDialog.isShowing()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(plainFill(theme.windowBg(), 16));
        panel.setPadding(dp(22), dp(20), dp(22), dp(14));

        TextView head = new TextView(this);
        head.setText(s(R.string.ln_close_title));
        head.setTextColor(theme.text);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(head);

        TextView body = new TextView(this);
        body.setText(s(R.string.ln_close_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(8);
        panel.addView(body, bodyLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(18);
        buttons.addView(dialogButton(s(R.string.ln_close_cancel), theme.hover, theme.text,
                dialog::dismiss));
        buttons.addView(dialogButton(s(R.string.ln_close_shutdown), theme.danger, 0xFFFFFFFF,
                () -> {
                    dialog.dismiss();
                    shutdownAndFinish();
                }));
        panel.addView(buttons, rowLp);

        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        dialog.setContentView(panel);
        closeDialog = dialog;
        dialog.show();
    }

    /** Stop the container for real, then close the viewer. */
    private void shutdownAndFinish() {
        DexLog.step("linux", "shutdown requested");
        LinuxService.stop(this);
        finish();
    }

    private TextView dialogButton(String text, int fill, int textColor, Runnable onClick) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(20), dp(9), dp(20), dp(9));
        button.setBackground(tapBackground(fill, lighten(fill), 12));
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    // ── the four screens ──

    private void showInstalling(Linux.Status st) {
        if (UI_INSTALL.equals(uiState)) {
            installBar.setProgress(st.pct);
            installMsg.setText(installText(st));
            return;
        }
        uiState = UI_INSTALL;
        LinearLayout column = centeredColumn();
        glyph(column);
        title(column, s(R.string.ln_installing));

        installBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        installBar.setMax(100);
        installBar.setProgress(st.pct);
        installBar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        installBar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(300), dp(6));
        barLp.topMargin = dp(20);
        column.addView(installBar, barLp);

        installMsg = subtext(column, installText(st));
        present(column);
    }

    /** Runtime not up yet: our card only, no WebView behind it. */
    private void enterStarting() {
        if (webView != null) dropWebView();
        uiState = UI_START;
        showStartCard(s(R.string.ln_stage_display));
    }

    /**
     * The startup narration, from what the runtime log actually shows: the X
     * server coming up, then the desktop services, then the VNC handshake. A
     * lingering connect adds the attempt count so it never looks frozen.
     */
    private String connectingMsg(String rtLog) {
        if (rtLog == null) rtLog = "";
        if (!rtLog.contains("Listening for VNC") && !rtLog.contains("VNC server")) {
            return s(R.string.ln_stage_display);
        }
        if (!rtLog.contains("6080")) {
            return s(R.string.ln_stage_services);
        }
        String msg = s(R.string.ln_stage_connecting);
        if (loadTries > 3) msg = msg + "  (" + loadTries + ")";
        return msg;
    }

    /** Build (once) / update the opaque starting-connecting overlay, on top. */
    private void showStartCard(String msg) {
        if (startCard == null) {
            // Drop any install column, but keep a WebView loading underneath.
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                if (root.getChildAt(i) != webView) root.removeViewAt(i);
            }
            FrameLayout card = new FrameLayout(this);
            card.setBackground(theme.surface(theme.windowBg(), 0f));

            LinearLayout column = centeredColumn();
            glyph(column);
            title(column, s(R.string.ln_starting));
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(theme.accent));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(30), dp(30));
            slp.topMargin = dp(18);
            slp.gravity = Gravity.CENTER_HORIZONTAL;
            column.addView(spinner, slp);
            startMsg = subtext(column, msg);

            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.CENTER;
            card.addView(column, clp);
            DexFonts.applyTo(this, card);
            DexCursors.decorate(card);

            root.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            startCard = card;
        } else {
            startMsg.setText(msg);
            root.bringChildToFront(startCard); // stay above a freshly rebuilt WebView
        }
    }

    private void hideStartCard() {
        if (startCard == null) return;
        root.removeView(startCard);
        startCard = null;
        startMsg = null;
        // The WebView went in at index 0, under an opaque card, and nothing
        // ever focused it. The viewer's keyboard button raises the IME by
        // focusing a field inside the page, which cannot happen in a view that
        // has never held window focus.
        if (webView != null) webView.requestFocus();
    }

    /** Build the WebView (once, or on a runtime swap) underneath the card. */
    private void ensureVnc(Linux.Status st) {
        if (UI_VNC.equals(uiState) && webView != null && st.rtPid == shownRtPid) return;
        uiState = UI_VNC;
        shownRtPid = st.rtPid;
        loadTries = 0;
        vncConnected = false;
        dropWebView();
        webView = new WebView(this);
        webView.setBackgroundColor(theme.windowBg());
        // WebView's own selection handles and magnifier have nothing to grab
        // over a canvas, and a long press is the guest's right-click.
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        WebSettings settings = webView.getSettings();
        // noVNC is all JavaScript (canvas + websocket), and keeps its input
        // preferences in localStorage
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Text zoom otherwise follows the system font scale, and a phone at
        // 130% inflates the viewer's whole control bar.
        settings.setTextZoom(100);
        // dex.html is ours (assets/linux/novnc/, staged into the guest beside
        // Ubuntu's own pages by LinuxService.stageViewer). It owns its own
        // scaling, so no &scale; and `v` is the runtime pid, which is exactly
        // the version of the staged files — websockify sends no Cache-Control,
        // and without a changing query the WebView happily serves yesterday's
        // page out of its heuristic cache.
        final String url = "http://127.0.0.1:" + st.port
                + "/dex.html?password=" + st.pass + "&v=" + st.rtPid;
        webView.setWebViewClient(new WebViewClient() {
            /** RUNNING=1 means the session leader is alive, not that websockify
             *  is accepting yet — the first load usually races it. Keep
             *  knocking; a port that never opens is an error. */
            private void fail(WebView view) {
                if (++loadTries > MAX_LOAD_TRIES) {
                    connectFailed = true;
                    showError(s(R.string.ln_connect_failed), null);
                    return;
                }
                main.postDelayed(() -> {
                    if (!destroyed && UI_VNC.equals(uiState)) view.loadUrl(url);
                }, 1000);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) fail(view);
            }

            // onReceivedError does NOT fire for HTTP status codes, and a page
            // that 404s still "loads": without this a mis-staged viewer would
            // sit on "Connecting to the desktop… (n)" forever and never reach
            // the error screen with its Retry.
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame()) fail(view);
            }
        });
        // Index 0: always BELOW the starting/connecting card if one is up.
        // Keeps the shell's arrow (inherited from root) rather than being hidden
        // with ROLE_NONE. The Linux guest's X server draws its own cursor INTO
        // the framebuffer, so there are briefly two here — but hiding ours
        // would leave a dead area whenever the guest is not drawing one yet,
        // and a doubled pointer is the lesser of those two.
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(url);
        DexLog.step("linux", "vnc client loading port " + st.port);
    }

    /**
     * A noVNC page that loaded fine but lost (or never won) its WebSocket
     * shows "connection is closed" FOREVER — it has no reconnect, and
     * onReceivedError never fires because the page load itself succeeded
     * (measured: the first load races the booting Xvnc and dies with ws code
     * 1006). So while the runtime is up, each poll reads the page's own
     * status element and reloads a self-declared-dead page, on the same try
     * budget as load failures.
     */
    private void probeVncHealth() {
        if (!UI_VNC.equals(uiState) || webView == null) return;
        webView.evaluateJavascript(
                // Two facts in one round-trip: is noVNC actually painting (its
                // canvas has a real framebuffer size) and what does its status
                // line say. Returns "<0|1>|<status text>".
                //
                // This is a CONTRACT with the page, not an inspection of it:
                // dex.html keeps a hidden #status carrying frozen English
                // literals for exactly these three tokens, and keeps its only
                // canvas inside #screen. See doc/linux-viewer.md. The
                // 'noVNC_status' fallback is Ubuntu's own vnc.html, which is
                // still on disk and can still be loaded by hand.
                "(function(){"
                        + "var s=document.getElementById('status')"
                        + "||document.getElementById('noVNC_status');"
                        + "var t=s&&s.textContent?s.textContent:'';"
                        + "var c=document.querySelector('#screen canvas')"
                        + "||document.querySelector('canvas');"
                        + "var up=(c&&c.width>0&&c.height>0)?1:0;"
                        + "return up+'|'+t;})()",
                raw -> {
                    if (destroyed || !UI_VNC.equals(uiState) || webView == null) return;
                    if (raw == null) return;
                    // strip evaluateJavascript's surrounding quotes
                    String v = raw.length() >= 2 && raw.charAt(0) == '"'
                            ? raw.substring(1, raw.length() - 1) : raw;
                    boolean painting = v.startsWith("1|");
                    String text = v.length() > 2 ? v.substring(2) : "";
                    if (painting) {
                        // The desktop is actually on screen — take the card down
                        // and keep it down for this runtime.
                        vncConnected = true;
                        deadPagePolls = 0;
                        loadTries = 0;
                        hideStartCard();
                        return;
                    }
                    boolean dead = text.contains("closed") || text.contains("Failed")
                            || text.contains("went wrong");
                    if (!dead) {
                        deadPagePolls = 0;
                        return; // still connecting — the card stays up with its stage message
                    }
                    if (++deadPagePolls < 2) return;   // one poll of grace
                    deadPagePolls = 0;
                    vncConnected = false;
                    if (++loadTries > MAX_LOAD_TRIES) {
                        connectFailed = true;
                        showError(s(R.string.ln_connect_failed), null);
                        return;
                    }
                    webView.reload();
                });
    }

    private void showError(String titleText, String detail) {
        showAction(titleText, detail, s(R.string.ln_retry));
    }

    /**
     * The terminal screen: a title, an optional line of detail, and one button
     * that puts a fresh runtime up. Three different endings share it — a failed
     * install, a viewer that could not connect, and a session that ended — so
     * the rebuild check is on the TITLE, not just on being in this state.
     */
    private void showAction(String titleText, String detail, String buttonLabel) {
        if (UI_ERROR.equals(uiState) && titleText.equals(shownActionTitle)) return;
        uiState = UI_ERROR;
        shownActionTitle = titleText;
        LinearLayout column = centeredColumn();
        glyph(column);
        title(column, titleText);
        if (detail != null && !detail.isEmpty()) subtext(column, detail);

        TextView retry = new TextView(this);
        retry.setText(buttonLabel);
        retry.setTextColor(0xFFFFFFFF);
        retry.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        retry.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(26), dp(10), dp(26), dp(10));
        retry.setBackground(tapBackground(theme.accent, lighten(theme.accent), 12));
        retry.setOnClickListener(v -> retryProvision());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(retry, lp);
        present(column);
    }

    // ── building blocks ──

    private LinearLayout centeredColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        return column;
    }

    /** Swap the built column in as the window's centered content. */
    private void present(LinearLayout column) {
        dropWebView();
        // The connecting card is one of the views being cleared; forget it too
        // so a later showStartCard rebuilds instead of updating a dead view.
        startCard = null;
        startMsg = null;
        root.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(column, lp);
        DexFonts.applyTo(this, column);
        DexCursors.decorate(column);
    }

    /** The WebView holds a websocket and a decoder — never leave one behind. */
    private void dropWebView() {
        if (webView == null) return;
        root.removeView(webView);
        webView.destroy();
        webView = null;
    }

    private void glyph(LinearLayout column) {
        TextView penguin = new TextView(this);
        penguin.setText("🐧");
        penguin.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(44));
        penguin.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(penguin);
    }

    private void title(LinearLayout column, String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(title, lp);
    }

    private TextView subtext(LinearLayout column, String text) {
        TextView sub = new TextView(this);
        sub.setText(text);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(sub, lp);
        return sub;
    }

    /** What the progress subtext says for this status. */
    private String installText(Linux.Status st) {
        // "error" reaches here only behind the Retry latch, where the honest
        // reading is the same as none's: nothing has started yet.
        if ("none".equals(st.phase) || "error".equals(st.phase)) {
            return s(R.string.ln_waiting);
        }
        return msgWords(st);
    }

    /**
     * state.env's MSG is dash-separated-no-whitespace; undo that for people.
     *
     * A colon, when the script sends one, separates the verb from a name the
     * script did not invent — "setting-up:libgtk-3-0t64". Only the verb is
     * de-dashed: a package name's dashes are part of it, and replacing those
     * too rendered it "setting up libgtk 3 0t64", which reads as four things
     * instead of one. No colon means the whole token is ours, and the old
     * blanket rule is still right for it.
     */
    private static String msgWords(Linux.Status st) {
        String m = "-".equals(st.msg) ? st.phase : st.msg;
        int c = m.indexOf(':');
        if (c < 0) return m.replace('-', ' ');
        return (m.substring(0, c).replace('-', ' ') + " " + m.substring(c + 1)).trim();
    }

    // ── measurements ──

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

    // ── drawables (the launcher's button idiom, private copy) ──

    private GradientDrawable plainFill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(theme.radius(radiusDp)));
        return d;
    }

    /** Rest / hover / ripple — this desktop is mouse-driven, so hover matters. */
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
