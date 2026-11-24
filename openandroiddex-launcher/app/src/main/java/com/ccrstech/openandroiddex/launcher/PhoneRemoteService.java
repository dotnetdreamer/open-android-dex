package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Remote desktop's screen sharing: the foreground service that holds the
 * MediaProjection while it lasts, and the notification that says so.
 *
 * <p><b>Android asks, once per session.</b> The consent ({@link
 * PhoneConsentActivity}) is good for one projection, and a projection ends
 * when the user stops it (the status bar's sharing chip, or this
 * notification's Stop), when another app starts one, and with every restart.
 * Remote desktop stays switched on through all of those: a computer that
 * comes when nothing is shared is told it waits, and the phone asks again
 * ({@link #askForScreen}), on its screen, where only someone holding it can
 * say yes.
 *
 * <p><b>The order Android requires.</b> The service enters the foreground with
 * the mediaProjection type before it takes the projection (Android 10), and
 * only with a consent in hand (Android 14 refuses the type otherwise); the
 * projection's callback is registered before its display is made (Android 14).
 *
 * <p>The projection itself costs nothing while nobody watches: its display
 * draws nowhere ({@link PhoneScreen}). But Android 15 and later hide the
 * phone's notification details while any screen is shared, so the share ends
 * 15 s after the last computer leaves ({@link #idleCheck}): with unattended
 * access it comes back by itself for the next computer; without, the next
 * computer's arrival asks someone at the phone. Only "Keep full speed ready"
 * ({@link PhoneRemote#keepsReady}) holds it, where only someone at the phone
 * could give the next one.
 */
public class PhoneRemoteService extends Service {

    static final String ACTION_START = "com.ccrstech.openandroiddex.launcher.phone.START";
    static final String ACTION_STOP = "com.ccrstech.openandroiddex.launcher.phone.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private static final String CHANNEL = "phone_remote";
    private static final int NOTIF_ID = 0x5E;
    private static final int NOTIF_ASK = 0x5F;
    private static final int NOTIF_CONTROL = 0x60;
    /**
     * The questions (allow screen sharing, turn control back on): someone at
     * the phone must act while a computer waits, so they are heard and seen,
     * unlike the quiet channel of the running service.
     */
    private static final String CHANNEL_ASKS = "phone-remote-asks";
    /** The phone asks again no more often than this: a computer retrying must not flood its screen. */
    private static final long ASK_EVERY_MS = 15_000;

    private static volatile PhoneScreen screen;
    /** The projection behind {@link #screen}: the phone's sound is captured through it too ({@link PhoneSound}). */
    private static volatile MediaProjection sharedProjection;
    private static volatile String watcher;
    private static volatile PhoneRemoteService running;
    private static long askedAt;
    private static long notifiedAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private boolean foreground;

    /** Screen sharing is on: a projection is held and mirrored. */
    static boolean sharing() {
        return screen != null;
    }

    static PhoneScreen screen() {
        return screen;
    }

    /** The screen sharing's projection while the screen is shared, else null: what the phone's sound is read through. */
    static MediaProjection projection() {
        return screen != null ? sharedProjection : null;
    }

    /** Who is watching, for the notification; null when nobody is. Any thread. */
    static void watching(Context ctx, String who) {
        watcher = who;
        PhoneRemoteService s = running;
        if (s != null) {
            s.main.post(() -> {
                s.showNotification();
                s.idleCheck();
            });
        }
    }

    /** No computer for this long: the screen is no longer shared, unless a held share is wanted. */
    private static final long IDLE_STOP_MS = 15_000;
    private final Runnable idleStop = this::stopIfIdle;

    /**
     * Nobody watching: screen sharing stops a little later
     * ({@link PhoneRemote#stopsWhenIdle}). With unattended access it comes
     * back by itself, about 100 ms after the next computer opens the phone;
     * without, the next computer's arrival asks someone at the phone, and
     * until then the computer gets the slow view. Android 15 and later hide
     * every notification's details on the phone, and keep them from being
     * swiped, while any app shares the screen (its screen share protections),
     * so a share held all day for computers that might come left the phone's
     * own notifications unusable (the user's report on the S25, 2026-11-22,
     * first with unattended access, then on a Play install without it). Only
     * "Keep full speed ready" holds it. The wait lets a page that reloads keep
     * its share. Main thread.
     */
    private void idleCheck() {
        main.removeCallbacks(idleStop);
        if (watcher == null && screen != null && PhoneRemote.stopsWhenIdle(this)) {
            main.postDelayed(idleStop, IDLE_STOP_MS);
        }
    }

    private void stopIfIdle() {
        if (watcher != null || screen == null || !PhoneRemote.stopsWhenIdle(this)) return;
        stopSharing("no computer has the screen open");
        stopSelf();
    }

    /** "Keep full speed ready" moved: a share nobody watches is held, or ends a little later. Any thread. */
    static void readyChanged() {
        PhoneRemoteService s = running;
        if (s != null) s.main.post(s::idleCheck);
    }

    /**
     * Ask, on the phone, for screen sharing: the system's own question, over
     * whatever is in front. Only someone holding the phone (or a computer
     * already in control of it) can answer it. An app whose accessibility
     * service is on may start it from the background; otherwise, and in case
     * the start was turned down quietly, a notification asks too.
     */
    static void askForScreen(Context ctx, String why) {
        if (sharing() || !PhoneRemote.isEnabled(ctx)) return;
        Context app = ctx.getApplicationContext();
        PhoneControlService control = PhoneControlService.live();
        // Each way of asking is held back on its own: a start from the
        // background that Android may have refused quietly (no control
        // service yet, as at boot) must not hold back the control service's
        // own try a moment later, which Android lets through.
        synchronized (PhoneRemoteService.class) {
            long now = SystemClock.elapsedRealtime();
            long last = control != null ? askedAt : notifiedAt;
            if (last != 0 && now - last < ASK_EVERY_MS) return;
            if (control != null) {
                askedAt = now;
            } else {
                notifiedAt = now;
            }
        }
        DexLog.step("phone", "asking for screen sharing: " + why);
        Context starter = control != null ? control : app;
        try {
            starter.startActivity(new Intent(app, PhoneConsentActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception e) {
            DexLog.warn("phone", "cannot ask on the screen", e);
        }
        // With unattended access Android asks nobody, so nobody needs telling,
        // as long as the question could start: from the control service it
        // can. Without it, a start from the background is one Android refuses
        // without a word (Android 10 and newer), and only a notification gets
        // through to someone at the phone, unattended access or not.
        if (!PhoneRemote.unattended(app) || control == null) postAsk(app);
    }

    private static void postAsk(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent tap = PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, PhoneConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = asking(ctx, nm)
                .setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(ctx.getString(R.string.pr_notif_ask))
                .setContentText(ctx.getString(R.string.pr_notif_ask_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIF_ASK, n);
        } catch (Exception ignored) {
            // no notification permission: the question on the screen is all there is
        }
    }

    private static void cancelAsk(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ASK);
    }

    /**
     * Control was on and Android turned control off without the user (the
     * app was force-stopped: PhoneRemote.controlLost), and a computer has
     * just opened the screen: a tap takes the person at the phone to
     * Android's accessibility settings, the only place control can go back on.
     */
    static void postControlLost(Context ctx) {
        postControl(ctx, R.string.pr_notif_control, R.string.pr_notif_control_text);
    }

    /**
     * Control is on in Android's settings but Android has not connected it
     * (PhoneRemote.controlUnbound): the same tap to Android's accessibility
     * settings, where turning it off and on connects it.
     */
    static void postControlUnbound(Context ctx) {
        postControl(ctx, R.string.pr_notif_control_unbound, R.string.pr_notif_control_unbound_text);
    }

    private static void postControl(Context ctx, int title, int text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent tap = PendingIntent.getActivity(ctx, 2,
                PhoneRemote.accessibilitySettings(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = asking(ctx, nm)
                .setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(ctx.getString(title))
                .setContentText(ctx.getString(text))
                .setStyle(new Notification.BigTextStyle().bigText(ctx.getString(text)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIF_CONTROL, n);
        } catch (Exception ignored) {
            // no notification permission: the Settings page says it too
        }
    }

    static void cancelControlLost(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_CONTROL);
    }

    private static final int NOTIF_AUTOSTART = 0x62;

    /**
     * A Xiaomi phone with Remote desktop on and MIUI's Autostart off for this
     * app: Clear all in Recent apps would stop Remote desktop for good, since
     * MIUI refuses every restart. Autostart can go off again without the user
     * (on a Redmi Note 7, MIUI 12.5, an allow set over adb was off again within
     * 20 minutes, with no update; one set on MIUI's own page lasted), so this
     * looks each time the stream starts listening, and a tap opens MIUI's own
     * Autostart list. Gone once Autostart is on.
     */
    static void checkXiaomiAutostart(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (!PhoneRemote.isEnabled(ctx) || !Boolean.FALSE.equals(PhoneRemote.xiaomiAutostart(ctx))) {
            nm.cancel(NOTIF_AUTOSTART);
            return;
        }
        Intent open = new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (open.resolveActivity(ctx.getPackageManager()) == null) {
            open = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        PendingIntent tap = PendingIntent.getActivity(ctx, 5, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = asking(ctx, nm)
                .setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(ctx.getString(R.string.pr_notif_autostart))
                .setContentText(ctx.getString(R.string.pr_set_autostart_off))
                .setStyle(new Notification.BigTextStyle().bigText(ctx.getString(R.string.pr_set_autostart_off)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIF_AUTOSTART, n);
            DexLog.step("phone", "MIUI's Autostart is off: asked on the phone to turn it on");
        } catch (Exception ignored) {
            // no notification permission: the Settings card says it too
        }
    }

    /** A notification on the questions' channel (also the helper's pairing code, PhoneHelperStart). */
    static Notification.Builder asking(Context ctx, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) {
            return new Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }
        if (nm.getNotificationChannel(CHANNEL_ASKS) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ASKS, ctx.getString(R.string.pr_channel_asks),
                    NotificationManager.IMPORTANCE_HIGH);
            c.setShowBadge(true);
            nm.createNotificationChannel(c);
        }
        return new Notification.Builder(ctx, CHANNEL_ASKS);
    }

    // ── the service ──

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSharing("stopped from the phone");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            // Brought back by Android after the process died: the projection
            // died with it, and only the user can give another.
            if (projection == null) stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) {
            // A second consent while sharing already: the first one stands.
            return START_NOT_STICKY;
        }
        MediaProjection mp;
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            mp = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            DexLog.warn("phone", "no projection", e);
            mp = null;
        }
        if (mp == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection = mp;
        final MediaProjection held = mp;
        mp.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (projection != held) return;
                stopSharing("screen sharing ended");
                stopSelf();
            }
        }, main);
        new Thread(() -> {
            PhoneScreen s;
            try {
                s = PhoneScreen.open(this, held);
            } catch (Exception e) {
                DexLog.warn("phone", "cannot share the screen", e);
                s = null;
            }
            PhoneScreen opened = s;
            main.post(() -> {
                if (projection != held) {
                    if (opened != null) opened.close();
                    return;
                }
                if (opened == null) {
                    stopSharing("the screen could not be mirrored");
                    stopSelf();
                    return;
                }
                sharedProjection = held;
                screen = opened;
                cancelAsk(this);
                PhoneStream.hold(this, this);
                PhoneStream.screenChanged();
                showNotification();
                DexLog.step("phone", "sharing the screen");
                // Shared with nobody watching (switched on, or after a
                // restart): stops again shortly where it can come back.
                idleCheck();
            });
        }, "phone-open").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(idleStop);
        stopSharing("the service stopped");
        if (running == this) running = null;
        super.onDestroy();
    }

    private void stopSharing(String why) {
        PhoneScreen s = screen;
        screen = null;
        if (sharedProjection == projection) sharedProjection = null;
        MediaProjection mp = projection;
        projection = null;
        PhoneStream.screenChanged();
        if (s != null) s.close();
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            DexLog.step("phone", "stopped sharing the screen: " + why);
        }
        PhoneStream.release(this);
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    private boolean enterForeground() {
        if (foreground) return true;
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
            return true;
        } catch (Exception e) {
            DexLog.warn("phone", "cannot enter the foreground", e);
            return false;
        }
    }

    private void showNotification() {
        if (!foreground) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, notification());
            } catch (Exception ignored) {
            }
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        channel(this, nm);
        String who = watcher;
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PhoneRemoteService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, LinuxSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return builder(this)
                .setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(getString(who != null ? R.string.pr_notif_live : R.string.pr_notif_on))
                .setContentText(getString(R.string.pr_notif_on_text))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, getString(R.string.pr_notif_stop), stop)
                .build();
    }

    private static Notification.Builder builder(Context ctx) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
    }

    private static void channel(Context ctx, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, ctx.getString(R.string.pr_channel),
                NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }
}
