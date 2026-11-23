package com.ccrstech.openandroiddex.launcher;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The phone's helper ({@link PhoneHelper}) started by the app itself, with no
 * computer: through the phone's own Wireless debugging (Android 11 and
 * newer), paired once with this app's adb key ({@link AdbWire}), the way
 * Shizuku starts its server (doc/phone-remote.md, "The helper without a
 * computer").
 *
 * <p><b>Nothing here is needed.</b> Without Developer options, Wireless
 * debugging or the pairing, Remote desktop is what it was: the picture, taps,
 * typing, files. Only the two things the helper does (the phone's screen off,
 * and presses on screens Android keeps from the app) wait for it, and the
 * Settings card and the computer's window say what is missing.
 *
 * <p><b>When it starts.</b> When a computer opens the phone
 * ({@link #ensure}, from PhoneStream), when the computer asks for the screen
 * off, and right after pairing. A start needs Developer options on (Android
 * keeps adb off otherwise), and Wireless debugging on: switched on by the app
 * if the helper's first run gave it WRITE_SECURE_SETTINGS (the shell may grant
 * that one) and the phone is on Wi-Fi (Wireless debugging works on Wi-Fi
 * only). On a Wi-Fi Android was never allowed on, Android puts the switch
 * back and asks on the phone; a start the computer did not ask for is not
 * tried again there until the phone joins another network. Switched off again
 * two minutes after the last computer leaves, or after a start no computer
 * watches, but only when the app switched it on: a person's own Wireless
 * debugging (and the computers on it) are left alone. That switch-off is kept
 * on disk and in an alarm Android keeps ({@link #switchOffLater}), so this
 * process dying first, or the phone sleeping, does not leave Wireless
 * debugging on.
 *
 * <p><b>The helper's life.</b> As adbd's: an adbd that stops (Developer
 * options or Wireless debugging off, with USB debugging off too, or a restart)
 * takes the helper with it, and the next computer starts it again.
 *
 * <p><b>Pairing.</b> Settings' "Set up Screen off" posts a notification with a
 * reply box ({@link #askCode}); the person opens "Pair device with pairing
 * code" in Wireless debugging and types the code there, without leaving the
 * pairing dialog (leaving Settings closes it). {@link CodeReceiver} pairs on
 * a background thread: the pairing port found over mDNS on this phone's own
 * addresses, then {@link AdbWire#pair}.
 */
final class PhoneHelperStart {

    /** What stands between a computer and the helper, for the Settings card and the computer's window. */
    static final String READY = "ready";
    /** Paired, nothing in the way: starts when a computer comes. */
    static final String WAITING = "waiting";
    static final String STARTING = "starting";
    /** Android 10 and older: no Wireless debugging; a computer with adb starts the helper. */
    static final String PC = "pc";
    static final String SETUP = "setup";
    /** Paired once, and Wireless debugging no longer knows the key (forgotten, or authorizations revoked). */
    static final String UNPAIRED = "unpaired";
    static final String DEV_OFF = "devoff";
    static final String WIRELESS_OFF = "wireless";
    static final String WIFI = "wifi";
    /** Android asks on the phone whether Wireless debugging may run on this Wi-Fi. */
    static final String ASK = "ask";
    static final String FAILED = "failed";

    private static final String CONNECT_SERVICE = "_adb-tls-connect._tcp";
    private static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp";
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final long SWITCH_OFF_MS = 120_000;
    /**
     * While a computer watches, how often the switch-off looks again: an
     * alarm stays set, for a process that dies mid-session.
     */
    private static final long WATCHED_LOOK_MS = 10 * 60_000;
    private static final int NOTIF_PAIR = 0x65;
    static final String ACTION_CODE = "com.ccrstech.openandroiddex.launcher.helper.CODE";
    static final String ACTION_SWITCH_OFF = "com.ccrstech.openandroiddex.launcher.helper.SWITCH_OFF";
    static final String CODE_KEY = "code";

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "phone-helper-start");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static boolean busy;
    /** The last try's outcome while it still holds (ASK, FAILED, UNPAIRED), and why; "" when none. */
    private static volatile String outcome = "";
    private static volatile String why = "";
    /** The Wi-Fi network Android asked about, so a start nobody asked for is not tried again on it. */
    private static volatile long askedOn = -1;
    private static volatile Runnable changed = () -> { };

    private PhoneHelperStart() {
    }

    /** Whoever shows the state (PhoneStream) is told of a change, on any thread. */
    static void onChange(Runnable r) {
        changed = r != null ? r : () -> { };
    }

    private static void tell() {
        try {
            changed.run();
        } catch (RuntimeException e) {
            DexLog.warn("phone", "helper start state", e);
        }
    }

    // ── what is kept (no backup: a key that opens adb on this phone stays on this phone) ──

    static File dir(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), "phone-helper");
    }

    private static File keyFile(Context ctx) {
        return new File(dir(ctx), "adbkey");
    }

    /** Written once pairing worked: the phone's Wireless debugging name (its connect service's). */
    private static File pairedFile(Context ctx) {
        return new File(dir(ctx), "paired");
    }

    private static File portFile(Context ctx) {
        return new File(dir(ctx), "port");
    }

    /** The app switched Wireless debugging on, so the app may switch it off. */
    private static File switchedFile(Context ctx) {
        return new File(dir(ctx), "switched-on");
    }

    /** When that switch-off is due (the wall clock, ms): kept, for a process that dies before then. */
    private static File switchOffFile(Context ctx) {
        return new File(dir(ctx), "switch-off-at");
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    static boolean paired(Context ctx) {
        return pairedFile(ctx).isFile();
    }

    private static String read(File f) {
        try {
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String words) throws IOException {
        File d = f.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("no room in " + d);
        File part = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(words.getBytes(StandardCharsets.UTF_8));
        }
        if (!part.renameTo(f)) throw new IOException("could not keep " + f.getName());
    }

    // ── the phone's settings ──

    static boolean devOptionsOn(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
    }

    /** Wireless debugging's switch; null where Android does not let the app read it. */
    static Boolean wirelessOn(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), ADB_WIFI_ENABLED, 0) != 0;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** The app may switch Wireless debugging itself: the helper's shell granted WRITE_SECURE_SETTINGS. */
    static boolean canSwitch(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The Wi-Fi network the phone is on (its handle), or -1 with none. */
    static long wifiNetwork(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return -1;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n.getNetworkHandle();
            }
        } catch (RuntimeException ignored) {
            // no answer: no Wi-Fi known
        }
        return -1;
    }

    // ── the state ──

    /**
     * What a computer's window and the Settings card say about the helper
     * now, from the settings alone (no try): READY, STARTING, PC, SETUP,
     * UNPAIRED, DEV_OFF, WIRELESS_OFF, WIFI, ASK, FAILED, or WAITING.
     */
    static String state(Context ctx) {
        if (PhoneHelperLink.linked()) return READY;
        synchronized (LOCK) {
            if (busy) return STARTING;
        }
        if (!supported()) return PC;
        if (!paired(ctx)) return SETUP;
        if (UNPAIRED.equals(outcome)) return UNPAIRED;
        if (!devOptionsOn(ctx)) return DEV_OFF;
        Boolean on = wirelessOn(ctx);
        if (Boolean.FALSE.equals(on)) {
            if (!canSwitch(ctx)) return WIRELESS_OFF;
            if (wifiNetwork(ctx) < 0) return WIFI;
            if (ASK.equals(outcome) && askedOn == wifiNetwork(ctx)) return ASK;
        }
        String o = outcome;
        return FAILED.equals(o) ? FAILED : WAITING;
    }

    /** The last start's outcome and the pairing's forgotten (the tests' fresh phone). */
    static void forgetOutcome() {
        outcome = "";
        why = "";
        askedOn = -1;
        pairing = "";
    }

    /** Why the last start failed, in the system's words: for FAILED. */
    static String why() {
        return why;
    }

    private static void ended(String o, String w) {
        outcome = o;
        why = w == null ? "" : w;
        if (!o.isEmpty()) DexLog.warn("phone", "helper start: " + o + (why.isEmpty() ? "" : " (" + why + ")"));
    }

    // ── a start ──

    /**
     * Starts the helper if it is not running and the phone is set up for it:
     * on a thread of its own, at most one at a time; the state says how it
     * went. {@code asked}: a person asked (the computer's Screen off,
     * Settings, pairing): switched on even where Android asks about the Wi-Fi.
     */
    static void ensure(Context ctx, boolean asked) {
        Context app = ctx.getApplicationContext();
        if (!supported() || !paired(app)) return;
        synchronized (LOCK) {
            if (busy) return;
            busy = true;
        }
        tell();
        WORK.execute(() -> {
            try {
                if (!PhoneHelperLink.present(app)) run(app, asked);
                else ended("", "");
            } catch (RuntimeException e) {
                ended(FAILED, e.toString());
            } finally {
                synchronized (LOCK) {
                    busy = false;
                }
                tell();
            }
        });
    }

    private static void run(Context ctx, boolean asked) {
        if (!devOptionsOn(ctx)) {
            ended(DEV_OFF, "");
            return;
        }
        if (Boolean.FALSE.equals(wirelessOn(ctx))) {
            if (!canSwitch(ctx)) {
                ended(WIRELESS_OFF, "");
                return;
            }
            long net = wifiNetwork(ctx);
            if (net < 0) {
                ended(WIFI, "");
                return;
            }
            if (!asked && ASK.equals(outcome) && askedOn == net) return;
            if (!switchOn(ctx)) {
                askedOn = net;
                ended(ASK, "");
                return;
            }
        }
        AdbWire.Keys k;
        try {
            k = AdbWire.Keys.inFile(keyFile(ctx));
        } catch (IOException e) {
            ended(FAILED, e.getMessage());
            return;
        }
        AdbWire.Link link = null;
        try {
            int last = parse(read(portFile(ctx)));
            if (last > 0) {
                try {
                    link = AdbWire.Link.open(last, k, 4000);
                } catch (AdbWire.NotPairedException e) {
                    throw e;
                } catch (IOException e) {
                    link = null; // adbd moved: its port is new after every start
                }
            }
            if (link == null) {
                int port = Mdns.find(ctx, CONNECT_SERVICE, read(pairedFile(ctx)), 8000);
                if (port <= 0) {
                    ended(FAILED, "Wireless debugging did not answer on this phone");
                    return;
                }
                link = AdbWire.Link.open(port, k, 8000);
                write(portFile(ctx), String.valueOf(port));
            }
            String said = link.shell(PhoneHelperLink.selfStartCommand(ctx), 20_000);
            DexLog.step("phone", "helper start over Wireless debugging: " + said.trim());
        } catch (AdbWire.NotPairedException e) {
            // The person removed this app in Paired devices, or revoked authorizations.
            // The pairing is kept: a start that works later (or a new pairing) clears this.
            ended(UNPAIRED, e.getMessage());
            return;
        } catch (IOException e) {
            ended(FAILED, e.getMessage());
            return;
        } finally {
            if (link != null) link.close();
        }
        // The helper hands its key to PhoneHelperDoor (content call) before it listens.
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            if (PhoneHelperLink.present(ctx)) {
                ended("", "");
                PhoneHelperLink.lookNow();
                DexLog.step("phone", "the phone's helper started without a computer");
                return;
            }
            sleep(400);
        }
        ended(FAILED, "the helper did not answer after its start");
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Wireless debugging on, by the app. False when Android put the switch
     * back (this Wi-Fi was never allowed: Android asks on the phone instead).
     */
    private static boolean switchOn(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            write(switchedFile(ctx), String.valueOf(System.currentTimeMillis()));
            Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1);
        } catch (IOException | SecurityException e) {
            switchedFile(ctx).delete();
            DexLog.warn("phone", "Wireless debugging could not be switched on: " + e);
            return false;
        }
        DexLog.step("phone", "switched Wireless debugging on for the phone's helper");
        // Off again whatever comes next: a start no computer watches (Settings' "Try again"), a
        // computer that left while this start still ran, this process dying. A computer that
        // watches puts it off until two minutes after it leaves (computersGone).
        Context app = ctx.getApplicationContext();
        synchronized (SWITCH_LOCK) {
            switchOffLater(app, System.currentTimeMillis() + SWITCH_OFF_MS);
        }
        // Android puts it back within half a second on a network it was never allowed on.
        for (int i = 0; i < 10; i++) {
            sleep(250);
            if (Boolean.FALSE.equals(wirelessOn(ctx))) {
                synchronized (SWITCH_LOCK) {
                    switchedFile(ctx).delete();
                    forgetSwitchOff(app);
                }
                DexLog.step("phone", "Android asks on the phone about Wireless debugging on this Wi-Fi");
                return false;
            }
        }
        return true;
    }

    /**
     * The last computer left: Wireless debugging off again after a while, if
     * the app switched it on and none came back. A person's own switch, and
     * any computer on it, are never touched.
     */
    static void computersGone(Context ctx) {
        Context app = ctx.getApplicationContext();
        synchronized (SWITCH_LOCK) {
            // Under the lock: a look that saw a computer still watching (its ten minutes) is
            // either done before this, or sees no computer after it; it never writes over these two.
            if (!switchedFile(app).isFile()) return;
            switchOffLater(app, System.currentTimeMillis() + SWITCH_OFF_MS);
        }
    }

    /**
     * The app's process started again (the phone restarted, the app was
     * updated, or the desktop app's clean-up stopped it): a switch-off its last
     * process left pending is looked at again, and done if it is due.
     * Nothing happens unless the app switched Wireless debugging on.
     */
    static void resume(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!switchedFile(app).isFile()) return;
        WORK.execute(() -> switchOffIfDue(app));
    }

    /**
     * Wireless debugging off at {@code at} (the wall clock), if the app
     * switched it on and no computer watches then. Kept three ways: on disk;
     * as a message on this process's main thread, on time while the process
     * lives; and as an alarm Android keeps, for when the process died first
     * (the main thread's message dies with it) or the phone slept (the main
     * thread's clock stops in deep sleep; the alarm's does not).
     */
    private static void switchOffLater(Context app, long at) {
        try {
            write(switchOffFile(app), String.valueOf(at));
        } catch (IOException e) {
            DexLog.warn("phone", "the switch-off of Wireless debugging could not be kept: " + e);
        }
        MAIN.removeCallbacksAndMessages(SWITCH_OFF_TOKEN);
        long in = Math.max(0, at - System.currentTimeMillis());
        MAIN.postAtTime(() -> WORK.execute(() -> switchOffIfDue(app)), SWITCH_OFF_TOKEN, SystemClock.uptimeMillis() + in);
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, switchOffIntent(app));
        } catch (RuntimeException e) {
            DexLog.warn("phone", "no alarm for the switch-off of Wireless debugging: " + e);
        }
    }

    private static PendingIntent switchOffIntent(Context app) {
        Intent i = new Intent(ACTION_SWITCH_OFF).setClass(app, SwitchOffReceiver.class).setPackage(app.getPackageName());
        return PendingIntent.getBroadcast(app, 9, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** No switch-off pending any more: the kept time, the main thread's message and the alarm. */
    private static void forgetSwitchOff(Context app) {
        MAIN.removeCallbacksAndMessages(SWITCH_OFF_TOKEN);
        switchOffFile(app).delete();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            am.cancel(switchOffIntent(app));
        } catch (RuntimeException ignored) {
            // nothing set
        }
    }

    /**
     * The switch-off's time came (the main thread's message, Android's alarm,
     * or the app's start): Wireless debugging off if the app switched it on,
     * the time is due, and no computer watches; looked at again later
     * otherwise. A switch-off that does not take (no WRITE_SECURE_SETTINGS any
     * more, the switch still on after it) keeps the mark and is tried again in
     * ten minutes: the helper's next start grants the permission again. On
     * WORK, so never in the middle of a start. Package-private for the tests.
     */
    static void switchOffIfDue(Context app) {
        synchronized (SWITCH_LOCK) {
            if (!switchedFile(app).isFile()) {
                // Switched off already, or a person's own switch: nothing to do, nothing kept.
                forgetSwitchOff(app);
                return;
            }
            long now = System.currentTimeMillis();
            if (PhoneHelperLink.watched()) {
                // The computer's leaving sets two minutes again (computersGone).
                switchOffLater(app, now + WATCHED_LOOK_MS);
                return;
            }
            long at = parseLong(read(switchOffFile(app)));
            // None kept (written by an earlier version), or one the clock went back past: two minutes from now.
            if (at <= 0 || at - now > WATCHED_LOOK_MS) {
                switchOffLater(app, now + SWITCH_OFF_MS);
                return;
            }
            if (now < at) {
                // Early: Android's alarm may come before its time, and the main thread's after a restart.
                switchOffLater(app, at);
                return;
            }
            Boolean on = wirelessOn(app);
            if (!Boolean.FALSE.equals(on)) {
                String why = switchOff(app);
                if (why != null) {
                    DexLog.warn("phone", "Wireless debugging is still on (" + why + "): tried again in ten minutes");
                    switchOffLater(app, now + WATCHED_LOOK_MS);
                    return;
                }
                DexLog.step("phone", "switched Wireless debugging off again: no computer for two minutes");
            }
            // Off: by the app now, or by a person meanwhile.
            switchedFile(app).delete();
            forgetSwitchOff(app);
        }
        tell();
    }

    /** Wireless debugging's switch off, and read back: null once it is off, else why not. */
    private static String switchOff(Context app) {
        if (!canSwitch(app)) return "no permission to switch it";
        try {
            if (!Settings.Global.putInt(app.getContentResolver(), ADB_WIFI_ENABLED, 0)) return "Android did not take the switch";
        } catch (SecurityException e) {
            return String.valueOf(e.getMessage());
        }
        return Boolean.FALSE.equals(wirelessOn(app)) ? null : "the switch reads on after it";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final Object SWITCH_OFF_TOKEN = new Object();
    /**
     * The mark and the kept time change under this: a look that finds a
     * computer watching, and that computer's leaving, never write over each
     * other's time.
     */
    private static final Object SWITCH_LOCK = new Object();

    /** The tests' stand-in for this process dying: the switch-off waiting on its main thread goes with it. */
    static void processDiedForTest() {
        MAIN.removeCallbacksAndMessages(SWITCH_OFF_TOKEN);
    }

    /**
     * Android's alarm for the switch-off ({@link #switchOffLater}): it comes
     * even when the app's process died in between, and starts it again.
     */
    public static final class SwitchOffReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null || !ACTION_SWITCH_OFF.equals(intent.getAction())) return;
            Context app = ctx.getApplicationContext();
            PendingResult pr = goAsync();
            WORK.execute(() -> {
                try {
                    switchOffIfDue(app);
                } finally {
                    if (pr != null) pr.finish();
                }
            });
        }
    }

    // ── pairing ──

    /**
     * The notification the pairing code is typed into: shown while the
     * person opens "Pair device with pairing code" (leaving Settings would
     * close that dialog, so the code goes into a notification, as Shizuku's
     * does). {@code words}: what it says above the box.
     */
    static void askCode(Context ctx, String words) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent i = new Intent(ACTION_CODE).setClass(ctx, CodeReceiver.class).setPackage(ctx.getPackageName());
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent reply = PendingIntent.getBroadcast(ctx, 8, i, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        RemoteInput box = new RemoteInput.Builder(CODE_KEY).setLabel(ctx.getString(R.string.ph_pair_hint)).build();
        Notification.Action type = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.hm_ic_remote),
                ctx.getString(R.string.ph_pair_action), reply).addRemoteInput(box).build();
        Notification n = PhoneRemoteService.asking(ctx, nm)
                .setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(ctx.getString(R.string.ph_pair_title))
                .setContentText(words)
                .setStyle(new Notification.BigTextStyle().bigText(words))
                .addAction(type)
                .setOnlyAlertOnce(false)
                .setAutoCancel(false)
                .setTimeoutAfter(10 * 60_000)
                .build();
        try {
            nm.notify(NOTIF_PAIR, n);
        } catch (Exception e) {
            DexLog.warn("phone", "pairing notification", e);
        }
    }

    /** The pairing notification saying {@code words} with no box: done, or working. */
    private static void sayOnPairing(Context ctx, String words, boolean done) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = PhoneRemoteService.asking(ctx, nm)
                .setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(ctx.getString(R.string.ph_pair_title))
                .setContentText(words)
                .setStyle(new Notification.BigTextStyle().bigText(words))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(done ? 30_000 : 60_000)
                .build();
        try {
            nm.notify(NOTIF_PAIR, n);
        } catch (Exception ignored) {
            // no notification permission
        }
    }

    /** What pairing came to, for whoever shows it (Settings). "" while none. */
    private static volatile String pairing = "";

    static String pairing() {
        return pairing;
    }

    /** The code typed into the notification: paired on a thread, then the helper started. */
    static void pairWith(Context ctx, String code, Runnable done) {
        Context app = ctx.getApplicationContext();
        String c = code == null ? "" : code.replaceAll("\\s", "");
        if (!c.matches("\\d{6}")) {
            askCode(app, app.getString(R.string.ph_pair_six));
            done.run();
            return;
        }
        sayOnPairing(app, app.getString(R.string.ph_pair_working), false);
        pairing = "working";
        tell();
        WORK.execute(() -> {
            try {
                int port = Mdns.find(app, PAIRING_SERVICE, null, 8000);
                if (port <= 0) {
                    pairing = "closed";
                    askCode(app, app.getString(R.string.ph_pair_closed));
                    return;
                }
                AdbWire.Keys k = AdbWire.Keys.inFile(keyFile(app));
                String guid = AdbWire.pair(port, c, k, keyName(app),
                        (s, label, len) -> android.net.ssl.SSLSockets.exportKeyingMaterial(s, label, null, len), 10_000);
                write(pairedFile(app), guid.isEmpty() ? "-" : guid);
                outcome = "";
                why = "";
                pairing = "paired";
                DexLog.step("phone", "paired with the phone's Wireless debugging (" + guid + ")");
                sayOnPairing(app, app.getString(R.string.ph_pair_done), true);
            } catch (AdbWire.WrongCodeException e) {
                pairing = "wrong";
                askCode(app, app.getString(R.string.ph_pair_wrong));
            } catch (IOException | RuntimeException e) {
                pairing = "failed";
                DexLog.warn("phone", "pairing", e);
                askCode(app, app.getString(R.string.ph_pair_failed, String.valueOf(e.getMessage())));
            } finally {
                tell();
                done.run();
            }
            if ("paired".equals(pairing)) {
                MAIN.post(() -> ensure(app, true));
            }
        });
    }

    static void cancelPairing(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_PAIR);
        pairing = "";
    }

    /** How the app's key is listed in Wireless debugging's Paired devices: the app's name. */
    static String keyName(Context ctx) {
        String label = ctx.getString(R.string.ln_label).replaceAll("[^A-Za-z0-9._-]", "");
        return (label.isEmpty() ? "LinuxOnDroid" : label) + "@" + Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "");
    }

    /** The reply box's code. */
    public static final class CodeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_CODE.equals(intent.getAction())) return;
            android.os.Bundle r = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = r == null ? null : r.getCharSequence(CODE_KEY);
            PendingResult pr = goAsync();
            pairWith(ctx, code == null ? "" : code.toString(), () -> {
                if (pr != null) pr.finish();
            });
        }
    }

    // ── mDNS: Wireless debugging's services on this phone ──

    /** Wireless debugging's ports, found the way adb's own computers find them. */
    static final class Mdns {
        private Mdns() {
        }

        /**
         * The port of a {@code type} service on this phone's own addresses, the
         * one called {@code want} first; -1 with none within {@code ms}.
         */
        static int find(Context ctx, String type, String want, long ms) {
            NsdManager nsd = ctx.getSystemService(NsdManager.class);
            if (nsd == null) return -1;
            Set<String> mine = ownAddresses();
            BlockingQueue<NsdServiceInfo> found = new LinkedBlockingQueue<>();
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    DexLog.warn("phone", "mDNS: no discovery of " + serviceType + " (" + errorCode + ")");
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    found.add(info);
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (RuntimeException e) {
                DexLog.warn("phone", "mDNS", e);
                return -1;
            }
            int other = -1;
            long until = System.currentTimeMillis() + ms;
            try {
                while (true) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    NsdServiceInfo info = found.poll(left, TimeUnit.MILLISECONDS);
                    if (info == null) break;
                    NsdServiceInfo r = resolve(nsd, info, Math.max(500, until - System.currentTimeMillis()));
                    if (r == null || r.getPort() <= 0 || !ours(r, mine)) continue;
                    String name = r.getServiceName() == null ? "" : r.getServiceName();
                    if (want == null || want.isEmpty() || "-".equals(want) || name.equals(want) || name.startsWith(want)) {
                        return r.getPort();
                    }
                    other = r.getPort();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    nsd.stopServiceDiscovery(listener);
                } catch (RuntimeException ignored) {
                    // already stopped
                }
            }
            return other;
        }

        @SuppressWarnings("deprecation")
        private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo info, long ms) {
            CountDownLatch done = new CountDownLatch(1);
            NsdServiceInfo[] got = new NsdServiceInfo[1];
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                        done.countDown();
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        got[0] = i;
                        done.countDown();
                    }
                });
                done.await(ms, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | InterruptedException e) {
                return null;
            }
            return got[0];
        }

        @SuppressWarnings("deprecation")
        private static boolean ours(NsdServiceInfo r, Set<String> mine) {
            InetAddress h = r.getHost();
            if (h == null) return false;
            if (h.isLoopbackAddress()) return true;
            String a = h.getHostAddress();
            int pct = a == null ? -1 : a.indexOf('%');
            return a != null && mine.contains(pct >= 0 ? a.substring(0, pct) : a);
        }

        private static Set<String> ownAddresses() {
            Set<String> out = new HashSet<>();
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        String s = a.getHostAddress();
                        if (s == null) continue;
                        int pct = s.indexOf('%');
                        out.add(pct >= 0 ? s.substring(0, pct) : s);
                    }
                }
            } catch (Exception ignored) {
                // none known
            }
            return out;
        }
    }
}
