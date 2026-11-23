package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * "Start Linux with the phone": LinuxOnDroid's setting that brings the Linux
 * desktop up in the background once the phone has started, so it is already
 * running when someone opens it. Switched on the Settings page
 * ({@link LinuxSettingsActivity}); acted on by {@link RemoteResumeReceiver}
 * when BOOT_COMPLETED arrives, through {@link LinuxService#bootStart}.
 *
 * <p>It starts the desktop, not a window. Android lets an app start a
 * foreground service from BOOT_COMPLETED (Android 15 refuses some service
 * types there, never specialUse, which is LinuxService's), but not open an
 * activity, and nobody would be looking at one. The service's notification
 * says Linux is running, the home screen shows it as running, and Open goes
 * straight into the session that is already up. The guest's sound stays off
 * until a window opens, as for a computer: the phone may be in a pocket.
 *
 * <p>One start per boot. BOOT_COMPLETED is not only sent at boot: from Android
 * 15, an app that was force-stopped gets it again when the user next opens it
 * (Android 15's "Changes to package stopped state"; seen on the S25, Android
 * 17). Without a check, Linux would start behind the home screen of someone
 * who had just stopped it. So each start is recorded against the boot it was
 * for, and switching the setting on records the boot already running: the
 * first start is the next boot's.
 *
 * <p>The desktop build compiles this too and never shows the switch, so there
 * it is always off and the receiver does nothing with it.
 */
final class LinuxBoot {

    private LinuxBoot() {
    }

    private static final String PREFS = "linux-boot";
    private static final String KEY_ON = "start_on_boot";
    /** The boot ({@link #bootId}) whose start has been made, or that was running when the switch went on. */
    private static final String KEY_BOOT = "boot_done";

    /** "Linux did not start with the phone: tap to start it." */
    static final int NOTIF_REFUSED = 0x21;
    private static final String CHANNEL = "linux";

    /**
     * Two readings of the boot clock further apart than this are two boots.
     * Only for a phone that does not keep Settings.Global.BOOT_COUNT. Within
     * one boot the reading moves only when the wall clock is set, and between
     * two boots it moves by at least the first boot's uptime.
     */
    static final long SAME_BOOT_SLACK_MS = 5_000;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, false);
    }

    /**
     * The switch. Committed, not applied: the receiver can run in a process
     * started for the next boot, and an apply still in its queue when the
     * phone was switched off would be lost.
     */
    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).putString(KEY_BOOT, bootId(ctx)).commit();
        DexLog.step("linux", "start with the phone: " + (on ? "on" : "off"));
    }

    /**
     * This boot, in a form that compares: "n:" and Android's own count of
     * boots when the phone keeps one, which every phone since Android 7 does;
     * otherwise "t:" and the wall-clock time the phone booted at.
     */
    static String bootId(Context ctx) {
        try {
            int n = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (n >= 0) return "n:" + n;
        } catch (Exception ignored) {
            // read as unknown, below
        }
        return "t:" + (System.currentTimeMillis() - SystemClock.elapsedRealtime());
    }

    static boolean sameBoot(String a, String b) {
        if (a == null || b == null) return false;
        if (a.startsWith("n:") || b.startsWith("n:")) return a.equals(b);
        try {
            long ta = Long.parseLong(a.substring(2));
            long tb = Long.parseLong(b.substring(2));
            return Math.abs(ta - tb) < SAME_BOOT_SLACK_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * BOOT_COMPLETED: start the desktop if the setting is on, this boot has
     * not had its start, and there is a Linux ready to start. Called from the
     * receiver, after it has finished any uninstall and resumed any install
     * that a shutdown cut off; neither of those leaves a Linux to start.
     *
     * @return what happened, for the log and for tests
     */
    static String onBoot(Context ctx) {
        if (!enabled(ctx)) return "off";
        String now = bootId(ctx);
        if (sameBoot(now, prefs(ctx).getString(KEY_BOOT, null))) {
            DexLog.step("linux", "start with the phone: this boot (" + now + ") has had its start already");
            return "done";
        }
        // Recorded before anything else, so that whatever follows (a refusal,
        // a Linux that is not ready) is this boot's answer, and an app opened
        // after a force-stop later in it does not try again.
        prefs(ctx).edit().putString(KEY_BOOT, now).commit();
        String why = whyNot(ctx);
        if (why != null) {
            DexLog.step("linux", "start with the phone: not starting, " + why);
            return why;
        }
        if (LinuxService.bootStart(ctx)) {
            DexLog.step("linux", "start with the phone: starting Linux (" + now + ")");
            return "started";
        }
        DexLog.step("linux", "start with the phone: Android refused the start; offering it in a notification");
        offerStart(ctx);
        return "refused";
    }

    /** Why there is nothing to start now, or null when there is a Linux to start. */
    static String whyNot(Context ctx) {
        if (!Linux.abiSupported()) return "this phone cannot run it";
        if (Linux.isRemoving(ctx)) return "it is being uninstalled";
        if (!Linux.isInstalled(ctx) || Linux.needsAppChoice(ctx)) return "it is not installed";
        if (!"ready".equals(Linux.readStatus(ctx).phase)) return "it is not installed yet";
        // A build that brings something new for the guest: that comes first,
        // and it comes when the app opens, as it always has. Starting the
        // desktop now would leave it running over a setup run.
        if (Linux.needsProvision(ctx)) return "it has an update to bring in first";
        return null;
    }

    /**
     * Android said no to the start, which BOOT_COMPLETED is exempt from, but a
     * phone maker's own rules, or the user's battery restriction, may still
     * say. The tap opens the Linux window rather than starting the service
     * from the notification: whatever refused a start from the background can
     * refuse that one too, while an app's own notification may always open
     * its window, and the window starts Linux as the Start button does.
     */
    static void offerStart(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, ctx.getString(R.string.ln_label), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent tap = PendingIntent.getActivity(ctx, 4,
                    new Intent(ctx, LinuxAppActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle(ctx.getString(R.string.ln_label))
                    .setContentText(ctx.getString(R.string.hm_boot_refused))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_REFUSED, n);
        } catch (Exception e) {
            DexLog.warn("linux", "cannot offer to start Linux", e);
        }
    }
}
