package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

/**
 * The phone's Wi-Fi kept out of power save while a computer watches a desktop
 * over it — wireless debugging, or the tunnel.
 *
 * In power save the phone's radio sleeps between beacons, and a frame or an
 * acknowledgement waits for it to wake: the round trips measured over wireless
 * debugging went from 8 ms to 150 and back from one moment to the next, at any
 * load (doc/usb-desktop.md, "Pacing"), and the streams size their window to
 * that. A low-latency lock is the platform's switch for real-time traffic; it
 * holds while the app is in front, which a desktop's launcher — whose window
 * is the desktop, and whose title bars are an accessibility service the system
 * keeps bound — is for as long as the desktop is up.
 *
 * Android 10 to 13 also get the high-performance lock, which holds in the
 * background as well and keeps power save off on its own; the platform takes
 * the strongest lock that applies. From 14 a high-performance request is turned
 * into a low-latency one, so there is nothing more to take (RemoteHost says the
 * same for remote access). Before 10 there is only the high-performance lock.
 *
 * One hold per process, kept for each reason that asked — the desktop's
 * launcher, the Linux stream — and let go with the last of them.
 */
final class WifiLow {

    private static final Set<String> reasons = new HashSet<>();
    private static WifiManager.WifiLock lowLatency;
    private static WifiManager.WifiLock highPerf;

    private WifiLow() {
    }

    /** {@code why} wants the Wi-Fi awake ({@code on}), or no longer does. Any thread. */
    static synchronized void hold(Context ctx, String why, boolean on) {
        boolean changed = on ? reasons.add(why) : reasons.remove(why);
        if (!changed) return;
        boolean want = !reasons.isEmpty();
        try {
            if (lowLatency == null && highPerf == null) {
                WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
                if (wm == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "OpenDeX:low-latency");
                    lowLatency.setReferenceCounted(false);
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @SuppressWarnings("deprecation")
                    int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    highPerf = wm.createWifiLock(mode, "OpenDeX:high-perf");
                    highPerf.setReferenceCounted(false);
                }
            }
            for (WifiManager.WifiLock lock : new WifiManager.WifiLock[]{lowLatency, highPerf}) {
                if (lock == null) continue;
                if (want && !lock.isHeld()) lock.acquire();
                else if (!want && lock.isHeld()) lock.release();
            }
            DexLog.step("wifi", want ? "keeping Wi-Fi out of power save (" + String.join(", ", reasons) + ")"
                    : "letting Wi-Fi save power again");
        } catch (Exception e) {
            DexLog.warn("wifi", "Wi-Fi lock", e);
        }
    }
}
