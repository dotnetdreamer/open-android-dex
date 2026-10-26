package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The line in the phone's notification shade that turns its screen into the desktop's
 * touchpad ({@link Touchpad}), up for as long as a computer is showing the
 * desktop. It is how anyone finds out the phone can be one.
 *
 * <p>Put up and taken down by the desktop itself (LauncherActivity), the one thing that
 * knows a session is live on a display of its own. Taking it down mostly happens
 * without us: the computer's clean-up at the end of a session force-stops this app, and
 * a force-stop clears an app's notifications; a cable pulled out takes the desktop's
 * display, and with it the activity that put this up.
 *
 * <p><b>Ongoing, and not a foreground service.</b> Nothing has to be kept alive by it —
 * the desktop's own process is already in the foreground, on its display. From Android 14
 * an ongoing notification can be swiped away; that is the user's answer for the session,
 * and it is not put back until the next desktop.
 *
 * <p>From Android 13 it needs POST_NOTIFICATIONS, which the computer grants for the
 * session (adb.rs, grant_post_notifications) and takes back at the end. Without it the
 * notification is simply not shown, and quick settings on the desktop still opens the pad.
 *
 * <p>The same line offers the pad for the computer's own windows of the phone's apps, with
 * no desktop at all ({@link #showForComputer}), while the computer's app server asks for
 * it through {@link AppsProvider} — taken down as it stops asking.
 */
final class TouchpadNotice {

    private static final String CHANNEL = "touchpad";
    /** Unique among this app's: Linux's is 0x1D, Docker's 0x2D, the Web viewer's 0x1E. */
    private static final int NOTIF_ID = 0x7A;
    /** {@link #shownFor} while it is up for the computer's windows of the phone's apps. */
    private static final int FOR_COMPUTER = -2;

    /** The desktop display it was put up for, {@link #FOR_COMPUTER}, or -1 while it is down. Main thread. */
    private static int shownFor = -1;

    private TouchpadNotice() {
    }

    /**
     * Up, for the desktop on {@code desktopDisplay}. Cheap to call again — the desktop
     * calls it on every heartbeat from the computer — and a no-op while it is up for the
     * same display, so a notification the user swiped away stays away.
     */
    static void show(Context ctx, int desktopDisplay) {
        if (shownFor == desktopDisplay) return;
        post(ctx, desktopDisplay, R.string.tp_notice_text);
    }

    /**
     * Up, for the computer's windows of the phone's apps, while it offers the pad
     * ({@link Touchpad#offeredByComputer}) — where this app may already post one: the
     * computer does not grant it that (revoking it would end this app's processes, the
     * phone's Linux with them), and the pad is in each window's menu there anyway. Not
     * over a desktop's own. True if it is up.
     */
    static boolean showForComputer(Context ctx) {
        if (shownFor >= 0) return false;
        if (shownFor == FOR_COMPUTER) return true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) {
            DexLog.step("touchpad", "this app may not post notifications — the computer offers the pad in its windows' menu");
            return false;
        }
        post(ctx, FOR_COMPUTER, R.string.tp_notice_text_computer);
        return shownFor == FOR_COMPUTER;
    }

    /** Down, if it is up for the computer's windows. */
    static void hideForComputer(Context ctx) {
        if (shownFor == FOR_COMPUTER) hide(ctx);
    }

    private static void post(Context ctx, int forDisplay, int text) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            // Every time, not once: it is how the channel's name follows the desktop's
            // language. The user's own settings for the channel are kept.
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ctx.getString(R.string.tp_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            // An activity, not a broadcast: tapping one is what closes the shade, and the
            // pad is a screen. IMMUTABLE because nothing may rewrite where it goes.
            PendingIntent open = PendingIntent.getActivity(ctx, 0,
                    new Intent(ctx, TouchpadActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_touchpad)
                    .setColor(DexTheme.of(ctx).accent)
                    .setContentTitle(ctx.getString(R.string.tp_notice_title))
                    .setContentText(ctx.getString(text))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setShowWhen(false)
                    // A watch has no use for a touchpad that is the phone.
                    .setLocalOnly(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build();
            nm.notify(NOTIF_ID, n);
            shownFor = forDisplay;
            DexLog.step("touchpad", forDisplay == FOR_COMPUTER ? "notification up for the computer's windows"
                    : "notification up for the desktop on display " + forDisplay);
        } catch (Exception e) {
            DexLog.warn("touchpad", "could not put the notification up", e);
        }
    }

    /** Down. Always asked of the platform, which may have one up from an earlier process. */
    static void hide(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {
        }
        if (shownFor >= 0) DexLog.step("touchpad", "notification down");
        shownFor = -1;
    }
}
