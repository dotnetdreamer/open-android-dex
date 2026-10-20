package com.ccrstech.openandroiddex.launcher;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

/**
 * The cable-pulled undo, for when the window daemon cannot do it.
 *
 * The daemon (openandroiddex-wmd) holds a dead-man switch: the PC re-arms it every
 * two seconds with the settings to put back, and silence with the desktop gone and
 * no computer on the USB port makes it undo the session. But a restarting adbd
 * kills it — init takes adbd's whole cgroup down, `setsid` or not — and the S25
 * Ultra restarts adbd three seconds after every unplug, long before the switch
 * trips. This process is an app's, so it outlives that.
 *
 * So this keeps a copy of what the PC armed (the daemon's ARMED), and once the
 * desktop has been gone, off a computer's USB port, for the armed time with the
 * daemon no longer answering, it writes those settings back itself — with
 * WRITE_SECURE_SETTINGS, which the PC grants for the session — and switches the
 * caption service off, which is what tells the PC, plugged back in, to set the
 * desktop up again. What needs the shell's uid (the app-ops, the widget-bind
 * whitelist, the pointer speed) waits for the PC. A daemon still answering, and
 * armed, does the whole undo itself, so this leaves it to that.
 */
final class UnplugWatch {

    private static final String AREA = "unplug";
    private static final long TICK_MS = 1000L;
    /** How often the armed copy is refreshed while the daemon answers. */
    private static final long FETCH_EVERY_MS = 3000L;

    private final CaptionService service;
    private final Runnable switchOff;
    private final WmClient wm = new WmClient();
    private volatile boolean stopped;

    // All on the client's own thread.
    private String chain;
    private long ttlMs;
    private long fetchedAt;
    /** The daemon answered ARMED with a switch, so it will undo the session itself. */
    private boolean daemonArmed;
    /** The desktop's display as the daemon last named it; -1 before it has. */
    private int desktop = -1;
    /** When the desktop was first seen gone with no computer on the cable; 0 while not. */
    private long goneSince;

    /** {@code switchOff} runs once the undo is done: the service turning itself off. */
    UnplugWatch(CaptionService service, Runnable switchOff) {
        this.service = service;
        this.switchOff = switchOff;
    }

    void start() {
        wm.post(this::tick);
    }

    void stop() {
        stopped = true;
        wm.shutdown();
    }

    private void tick() {
        if (stopped) return;
        long now = SystemClock.elapsedRealtime();
        boolean up = wm.isUp();
        if (up && (chain == null || now - fetchedAt >= FETCH_EVERY_MS)) {
            fetchedAt = now;
            // NONE from a daemon the PC has just restarted and not re-armed yet
            // keeps the copy already here: the settings to put back are the same.
            String[] armed = wm.armed();
            daemonArmed = armed != null;
            if (armed != null) {
                try {
                    ttlMs = Long.parseLong(armed[0]) * 1000L;
                    chain = armed[1];
                } catch (NumberFormatException ignored) {
                }
            }
            int d = wm.desktopDisplay();
            if (d > 0) desktop = d;
        }
        if (chain == null || desktopShowing() || onComputer()) {
            if (goneSince != 0) DexLog.step(AREA, "the desktop or the computer is back — nothing to undo");
            goneSince = 0;
            wm.postDelayed(this::tick, TICK_MS);
            return;
        }
        if (goneSince == 0) {
            goneSince = now;
            DexLog.step(AREA, "desktop gone and no computer on the cable — undoing in "
                    + (ttlMs / 1000L) + "s unless it comes back");
        }
        if ((up && daemonArmed) || now - goneSince < ttlMs) {
            wm.postDelayed(this::tick, TICK_MS);
            return;
        }
        undo();
    }

    /**
     * The desktop's display, or any scrcpy display, still exists. Asked of the
     * platform rather than the daemon, which is gone by the time this matters.
     */
    private boolean desktopShowing() {
        DisplayManager dm = service.getSystemService(DisplayManager.class);
        if (dm == null) return true;            // no way to tell: do nothing
        if (desktop > 0 && dm.getDisplay(desktop) != null) return true;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && "scrcpy".equals(d.getName())) return true;
        }
        return false;
    }

    /**
     * On a computer's USB port: the cable never came out, or is already back,
     * and the PC is about to set the desktop up again around whatever is here —
     * the daemon's watchdog holds for the same reason. Wall chargers say AC.
     */
    private boolean onComputer() {
        Intent battery = service.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null
                && (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) & BatteryManager.BATTERY_PLUGGED_USB) != 0;
    }

    private void undo() {
        stopped = true;
        boolean may = service.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (!may) DexLog.warn(AREA, "no WRITE_SECURE_SETTINGS — the settings wait for the computer");
        ContentResolver cr = service.getContentResolver();
        int put = 0;
        int left = 0;
        for (String step : chain.split(";")) {
            String[] w = step.trim().split("\\s+");
            if (w[0].isEmpty()) continue;
            try {
                if (may && apply(cr, w)) {
                    put++;
                } else {
                    left++;
                }
            } catch (Throwable t) {
                left++;
                DexLog.warn(AREA, "could not undo `" + step.trim() + "`: " + t);
            }
        }
        DexLog.step(AREA, "the window daemon is gone, so the launcher undid the session: "
                + put + " settings put back, " + left + " left for the computer");
        wm.shutdown();
        switchOff.run();
    }

    /**
     * One step of the PC's undo, which is `settings put|delete <namespace> <key>
     * [value]` with values of letters, digits and dots (adb.rs, restorable). Only
     * the global and secure namespaces: an app is refused the private system
     * settings, pointer_speed among them, even holding WRITE_SECURE_SETTINGS.
     */
    private static boolean apply(ContentResolver cr, String[] w) {
        if (w.length < 4 || !"settings".equals(w[0])) return false;
        String ns = w[2];
        boolean global = "global".equals(ns);
        if (!global && !"secure".equals(ns)) return false;
        String key = w[3];
        if ("put".equals(w[1]) && w.length == 5) {
            return global ? Settings.Global.putString(cr, key, w[4]) : Settings.Secure.putString(cr, key, w[4]);
        }
        if ("delete".equals(w[1]) && w.length == 4) {
            // Settings has no public delete; this is the provider call `settings delete` makes.
            cr.call(Uri.parse("content://settings"), "DELETE_" + ns, key, null);
            return true;
        }
        return false;
    }
}
