package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings remote access back after the three things that end every process of
 * this app without asking it: a reboot, an update, and the desktop app's own
 * clean-up at the end of a DeX session, which force-stops the launcher to give
 * the phone its home screen back.
 *
 * That last one is why {@link #ACTION_RESUME} exists and why this receiver is
 * exported at all: the desktop app sends it over adb right after the
 * force-stop. It is guarded by {@code android.permission.DUMP}, which the shell
 * and the system hold and no ordinary app can — and even unguarded it could
 * do nothing a caller would want, since all it ever does is restart what the
 * user switched on and left on.
 *
 * Android only lets a background app start a foreground service in specific
 * cases. Boot and our own update are two of them, and being exempt from
 * battery optimisation — which the Remote access window asks for — covers the
 * third. When none applies, the start is refused, and the honest fallback is a
 * notification the user can tap: a tap on our own notification is always
 * allowed to start the service.
 */
public class RemoteResumeReceiver extends BroadcastReceiver {

    static final String ACTION_RESUME = "com.ccrstech.openandroiddex.launcher.remote.RESUME";

    private static final String CHANNEL = "linux";
    private static final int NOTIF_ID = 0x1E;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !ACTION_RESUME.equals(action)) {
            return;
        }
        if (!LinuxRemote.isEnabled(ctx) || !LinuxRemote.available(ctx)) return;
        DexLog.step("remote", "resuming remote access after " + action);
        if (!LinuxService.remoteResume(ctx)) offerResume(ctx);
    }

    /** "Remote access is paused — tap to resume", for when Android said no. */
    static void offerResume(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, ctx.getString(R.string.ln_label), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent tap = PendingIntent.getForegroundService(ctx, 3,
                    new Intent(ctx, LinuxService.class).setAction(LinuxService.ACTION_REMOTE_RESUME),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle(ctx.getString(R.string.ln_label))
                    .setContentText(ctx.getString(R.string.rm_note_resume))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Exception e) {
            DexLog.warn("remote", "cannot offer to resume", e);
        }
    }
}
