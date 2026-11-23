package com.ccrstech.openandroiddex.launcher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps Remote desktop's stream ({@link PhoneStream}) listening while
 * Remote desktop is on, when neither control nor screen sharing is there to
 * hold it. That is the state after the app was stopped: a Xiaomi phone's
 * Clear all in Recent apps force-stops it, Android then drops its
 * accessibility service, and screen sharing ends with the process. Until
 * 2026-11-21 a browser let in by the door, or a computer through the tunnel,
 * then found nothing at all ("Remote desktop is not running on the phone")
 * and the phone was never asked; now it finds a stream that says it waits
 * and asks the phone for screen sharing.
 *
 * <p>Started by {@link LinuxService} (in :linux), a foreground service while
 * Remote desktop is on, so Android lets it start this one; not a foreground
 * service itself, since all it holds is a socket. In the app's own process,
 * as the stream is.
 */
public final class PhoneListenService extends Service {

    /** After a start (an update, a restart), how long control has to connect before the phone says so. */
    static final long CONTROL_CHECK_MS = 30_000;

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Control on in Android's settings and still not connected a while after
     * Remote desktop started (seen after an update on Android 10): the person
     * at the phone hears it now, before a computer needs control.
     */
    private final Runnable controlCheck = () -> {
        if (PhoneRemote.isEnabled(this) && PhoneRemote.controlUnbound(this)) {
            DexLog.warn("phone", "control is on in Android's settings but not connected");
            PhoneRemoteService.postControlUnbound(this);
        }
    };

    /** Listening, if Remote desktop is on and this APK has it. Any process, any thread. */
    static void ensure(Context ctx) {
        if (!PhoneRemote.offered(ctx) || !PhoneRemote.isEnabled(ctx)) return;
        try {
            ctx.startService(new Intent(ctx, PhoneListenService.class));
        } catch (Exception e) {
            // Not allowed from the background just now: the door asks again
            // when a browser comes (LinuxService's phoneLine).
            DexLog.warn("phone", "cannot keep the stream listening", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PhoneRemote.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        PhoneStream.hold(this, this);
        main.removeCallbacks(controlCheck);
        main.postDelayed(controlCheck, CONTROL_CHECK_MS);
        // Wireless debugging the app switched on for Screen off's helper, with
        // its switch-off lost to a force-stop (which ends Android's alarms, and
        // after which Android 11 to 14 send no BOOT_COMPLETED when the app
        // opens again): looked at again whenever Remote desktop's listener starts.
        PhoneHelperStart.resume(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(controlCheck);
        PhoneStream.release(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
