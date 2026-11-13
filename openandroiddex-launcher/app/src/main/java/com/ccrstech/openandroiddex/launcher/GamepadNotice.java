package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The line in the phone's notification shade that turns its screen into a game
 * controller ({@link PhoneGamepad}) for its apps' windows on a computer: up while the
 * computer says one of those windows has its game controls on — game mode — and down as
 * the last of them turns them off, or the computer stops offering the controller
 * (doc/game-mode.md, "The phone as a controller"). The windows' menus on the computer
 * offer the controller always.
 *
 * <p>Like the touchpad's ({@link TouchpadNotice}): ongoing, low importance, local only, and
 * only where this app may already post notifications — the computer does not grant that
 * for its windows, since revoking it would end this app's processes, the phone's Linux
 * with them. From Android 14 a person may swipe it away; it is not put back until game
 * mode comes on again.
 */
final class GamepadNotice {

    private static final String CHANNEL = "gamepad";
    /** Unique among this app's: the touchpad's is 0x7A, Linux's 0x1D, Docker's 0x2D, the Web viewer's 0x1E. */
    private static final int NOTIF_ID = 0x7B;

    /** Up now. Main thread. */
    private static boolean up;

    private GamepadNotice() {
    }

    /**
     * Up, if this app may post notifications; true if it is. Cheap to call again, and a
     * no-op while it is up, so one swiped away stays away until game mode comes on again.
     */
    static boolean show(Context ctx) {
        if (up) return true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) {
            DexLog.step("gamepad", "this app may not post notifications — the computer offers the controller in its windows' menu");
            return false;
        }
        try {
            // Every time, not once: it is how the channel's name follows the desktop's
            // language. The user's own settings for the channel are kept.
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ctx.getString(R.string.gp_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            // An activity, not a broadcast: tapping one is what closes the shade, and the
            // controller is a screen. IMMUTABLE because nothing may rewrite where it goes.
            PendingIntent open = PendingIntent.getActivity(ctx, 0,
                    new Intent(ctx, GamepadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_gamepad)
                    .setColor(DexTheme.of(ctx).accent)
                    .setContentTitle(ctx.getString(R.string.gp_notice_title))
                    .setContentText(ctx.getString(R.string.gp_notice_text))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setShowWhen(false)
                    // A watch has no use for a controller that is the phone.
                    .setLocalOnly(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build();
            nm.notify(NOTIF_ID, n);
            up = true;
            DexLog.step("gamepad", "notification up: game mode is on in a window on the computer");
        } catch (Exception e) {
            DexLog.warn("gamepad", "could not put the notification up", e);
        }
        return up;
    }

    /** Down. Always asked of the platform, which may have one up from an earlier process. */
    static void hide(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {
        }
        if (up) DexLog.step("gamepad", "notification down");
        up = false;
    }
}
