package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the Web viewer while it is serving: the screen capture, the peer
 * connections, the page server, and the notification that says the phone is
 * being watched.
 *
 * <p><b>This one lives in the launcher's own process</b>, unlike
 * {@link LinuxService} and {@link DockerService}, which were split out because
 * a foreground service that misses the platform's five-second startForeground
 * deadline dies with an uncaught exception and used to take the desktop with
 * it. The trade is deliberate and goes the other way here: the viewer's control
 * path runs through {@link CaptionService}, which is the accessibility service
 * in THIS process, and nothing else on the phone can dispatch a gesture.
 * Reaching it across a process boundary would put an IPC hop on every pointer
 * move. The deadline risk is answered instead by entering the foreground as the
 * very first thing this service does, before it touches a socket or a codec.
 *
 * <p>The capture token is not ours to keep. {@link MediaProjection} is granted
 * for one session by the system's own dialog, cannot be re-used once stopped,
 * and is revocable from the status bar at any moment — so a settings change
 * reconfigures the live capture rather than restarting the projection, and the
 * user is never asked to consent twice for turning the quality down.
 */
public class WebService extends Service implements WebServer.Host {

    static final String ACTION_START = "com.ccrstech.openandroiddex.launcher.web.START";
    static final String ACTION_STOP = "com.ccrstech.openandroiddex.launcher.web.STOP";
    /** Settings moved — apply what can be applied, keep the consent. */
    static final String ACTION_APPLY = "com.ccrstech.openandroiddex.launcher.web.APPLY";
    /** The access code changed; every session opened with the old one ends. */
    static final String ACTION_REVOKE = "com.ccrstech.openandroiddex.launcher.web.REVOKE";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "web";
    private static final int NOTIF_ID = 0x1E;

    /** The live service, for the window and the tile to read state from. */
    private static volatile WebService live;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MediaProjection projection;
    private WebServer server;
    private WebInput input;
    private WebRtcHub rtc;
    private WebSignal signal;
    private volatile String error;
    /**
     * True while WE are tearing the session down.
     *
     * shutdown() stops the projection, and stopping a projection fires the very
     * callback that exists to notice somebody ELSE stopping it. Without this
     * flag a perfectly ordinary Stop reported itself as "revoked" — an error
     * the window then showed — and re-entered shutdown() and stopSelf() from
     * inside the callback.
     */
    private volatile boolean stopping;

    // ── what the UI asks ──

    static boolean isRunning() {
        WebService s = live;
        return s != null && s.server != null && s.server.running();
    }

    static int viewerCount() {
        WebService s = live;
        return s == null || s.rtc == null ? 0 : s.rtc.viewerCount();
    }

    static String lastError() {
        WebService s = live;
        return s == null ? null : s.error;
    }

    /** True once WebRTC's native side is loaded and ready to take a viewer. */
    static boolean rtcReady() {
        WebService s = live;
        return s != null && s.rtc != null;
    }

    /** "connecting" | "waiting" | "error", or null when no rendezvous is set. */
    static String signalState() {
        WebService s = live;
        return s == null || s.signal == null ? null : s.signal.state();
    }

    /** What the window says is being sent. */
    static String streamSummary(Context ctx) {
        Point size = Web.captureSize(ctx);
        return size.x + "×" + size.y;
    }

    /**
     * Begin serving. Goes through the consent screen first — the system's
     * capture dialog is the only way to a projection token, and it must be
     * asked for from an activity.
     */
    static void start(Context ctx) {
        try {
            ctx.startActivity(new Intent(ctx, WebConsentActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("web", "could not ask for screen capture", e);
        }
    }

    /**
     * Guarded on isRunning, and that guard is load-bearing rather than tidy.
     * Starting this service when there is no projection means startForeground
     * cannot satisfy its declared mediaProjection type — and a foreground
     * service that fails to enter the foreground inside five seconds is killed
     * with an uncaught exception, in the launcher's own process.
     */
    static void stop(Context ctx) {
        if (!isRunning()) return;
        send(ctx, ACTION_STOP);
    }

    static void apply(Context ctx) {
        if (!isRunning()) return;
        send(ctx, ACTION_APPLY);
    }

    /**
     * A new access code was made. Every session token handed out under the old
     * one is dropped and every viewer disconnected — otherwise "change the
     * code" would mean "add a second code".
     */
    static void newCode(Context ctx) {
        if (!isRunning()) return;
        send(ctx, ACTION_REVOKE);
    }

    private static void send(Context ctx, String action) {
        try {
            ctx.startForegroundService(new Intent(ctx, WebService.class).setAction(action));
        } catch (Exception e) {
            DexLog.warn("web", "could not reach the service", e);
        }
    }

    // ── lifecycle ──

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        goForeground(getString(R.string.wb_notif_starting));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-entered on every start, for the reason spelled out on
        // LinuxService: the five-second deadline is armed per
        // startForegroundService call, not per service instance.
        goForeground(currentNotificationText());
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
        } else if (ACTION_APPLY.equals(action)) {
            applySettings();
        } else if (ACTION_REVOKE.equals(action)) {
            if (server != null) server.revokeSessions();
            if (rtc != null) rtc.dropAll();
            applySettings();
        } else if (ACTION_START.equals(action)) {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (data == null) {
                fail("no-consent");
                stopSelf();
            } else {
                begin(code, data);
            }
        }
        // NOT sticky: a restart after a kill would have no projection token,
        // and a viewer looking at a dead session is worse than one looking at
        // an obviously stopped one.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        io.shutdownNow();
        live = null;
        super.onDestroy();
    }

    private void begin(int resultCode, Intent data) {
        error = null;
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) {
                fail("no-consent");
                stopSelf();
                return;
            }
            // Registered once, here, and before any virtual display is created
            // — Android 14 refuses createVirtualDisplay without a callback, and
            // this is also the only way a user tapping "Stop sharing" in the
            // status bar reaches us.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (stopping) return;         // our own shutdown, not a revoke
                    DexLog.step("web", "screen capture stopped by the system");
                    fail("revoked");
                    shutdown();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            input = new WebInput(this);
            server = new WebServer(this);
            server.start();
            startWebRtc();
            Web.announce(this);
            goForeground(currentNotificationText());
        } catch (java.net.BindException e) {
            fail("port-busy");
            shutdown();
            stopSelf();
        } catch (Exception e) {
            DexLog.warn("web", "could not start the viewer", e);
            fail("start-failed");
            shutdown();
            stopSelf();
        }
    }

    /**
     * Build the WebRTC side on the io thread.
     *
     * Off the calling thread because starting it means loading ~12 MB of native
     * code and this is a service start. A viewer that connects in the moment
     * before it is ready is told so and asks again.
     *
     * The rendezvous is dialled only once the hub exists: a relay hosting a
     * room the phone cannot actually serve hands out a link that fails at the
     * offer.
     */
    private void startWebRtc() {
        final MediaProjection captured = projection;
        io.execute(() -> {
            WebRtcHub candidate = new WebRtcHub(this, this, captured);
            if (!candidate.start()) {
                fail("rtc-unavailable");
                return;
            }
            if (projection != captured) {
                candidate.stop();       // the session ended while libraries loaded
                return;
            }
            rtc = candidate;
            if (WebRtc.hasRendezvous(this)) {
                signal = new WebSignal(this, candidate,
                        WebRtc.signalUrl(this), WebRtc.room(this));
                signal.start();
            }
            DexLog.step("web", "viewer ready on port " + Web.PORT
                    + (signal != null ? ", hosting room " + WebRtc.room(this) : ""));
            Web.announce(this);
            goForeground(currentNotificationText());
        });
    }

    /**
     * A settings change, applied without a second consent dialog.
     *
     * Everything adjustable now lives inside the peer connections and the
     * capture, both of which take a new value in place — so this never touches
     * the projection and never asks the user anything.
     */
    private void applySettings() {
        if (rtc != null) rtc.applySettings();
        Web.announce(this);
        goForeground(currentNotificationText());
    }

    private void shutdown() {
        stopping = true;
        if (signal != null) {
            signal.stop();
            signal = null;
        }
        if (rtc != null) {
            rtc.stop();
            rtc = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        input = null;
        stopping = false;
        Web.announce(this);
    }

    private void fail(String code) {
        error = code;
        DexLog.warn("web", "viewer failed: " + code);
        Web.announce(this);
    }

    // ── WebServer.Host ──

    @Override
    public Context context() {
        return this;
    }

    @Override
    public WebInput input() {
        return input;
    }

    @Override
    public WebRtcHub rtc() {
        return rtc;
    }

    @Override
    public boolean validSession(String token) {
        WebServer s = server;
        return s != null && s.hasToken(token);
    }

    /** A viewer arrived or left — the notification and the window follow it. */
    @Override
    public void onViewersChanged() {
        goForeground(currentNotificationText());
        Web.announce(this);
    }

    // ── foreground plumbing ──

    private String currentNotificationText() {
        if (server == null || !server.running()) return getString(R.string.wb_notif_starting);
        int viewers = rtc == null ? 0 : rtc.viewerCount();
        return viewers == 0
                ? getString(R.string.wb_notif_waiting)
                : getString(R.string.wb_notif_viewers, viewers);
    }

    private void goForeground(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, getString(R.string.wb_label), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            // The notification is the only place a stop button is guaranteed to
            // be reachable: the desktop window may be behind an app, and the
            // person who wants to stop being watched wants it now.
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, WebService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2,
                    new Intent(this, WebActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setContentTitle(getString(R.string.wb_label))
                    .setContentText(text)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null,
                            getString(R.string.wb_stop), stop).build())
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // The one way this fails is a service of type mediaProjection with
            // no live projection behind it — Android 14 refuses that outright.
            // Going away immediately is the ONLY safe answer: a service that
            // has not entered the foreground five seconds after being started
            // is killed with an uncaught
            // ForegroundServiceDidNotStartInTimeException, and this one lives
            // in the launcher's process, so that would take the desktop with it.
            DexLog.warn("web", "could not enter the foreground — stopping", e);
            shutdown();
            stopSelf();
        }
    }
}
