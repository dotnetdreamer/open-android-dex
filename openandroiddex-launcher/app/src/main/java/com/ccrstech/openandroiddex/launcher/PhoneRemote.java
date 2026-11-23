package com.ccrstech.openandroiddex.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Remote desktop: this phone's own screen, seen and used from a computer.
 *
 * <p>What is switched on lives in files, not preferences, because three
 * processes read it: the Settings page and the services in the app's own
 * process, and {@link LinuxService} in {@code :linux}, which keeps the
 * remote-access tunnel up while this is on. SharedPreferences cache per
 * process and would tell {@code :linux} a stale answer.
 *
 * <ul>
 *   <li>{@code on}: the switch. Turning it on asks Android for screen sharing
 *       ({@link PhoneConsentActivity}); it stays on when sharing ends, so a
 *       computer that comes later can ask the phone again.</li>
 *   <li>{@code boot}: start with the phone. Android asks for screen sharing
 *       once per session, so after a restart the question comes up again on
 *       the phone, by itself, as soon as it is unlocked.</li>
 * </ul>
 *
 * <p>The picture's quality is not here: the person at the computer sees the
 * picture and how the link holds up, so the window there picks Low, Medium or
 * High ({@link Quality}), and the phone makes what it asks for.</p>
 *
 * <p>Design record: doc/phone-remote.md.
 */
final class PhoneRemote {

    static final String LOW = "low";
    static final String MEDIUM = "medium";
    static final String HIGH = "high";

    private PhoneRemote() {
    }

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "phone-remote");
    }

    // ── the switches ──

    static boolean isEnabled(Context ctx) {
        return new File(dir(ctx), "on").exists();
    }

    static void setEnabled(Context ctx, boolean on) {
        mark(ctx, "on", on);
    }

    static boolean startsWithPhone(Context ctx) {
        return new File(dir(ctx), "boot").exists();
    }

    static void setStartsWithPhone(Context ctx, boolean on) {
        mark(ctx, "boot", on);
    }

    /**
     * "Keep full speed ready": screen sharing stays on with no computer
     * connected, so a computer that comes while nobody is at the phone gets
     * the screen at full speed. Off, the default since 2026-11-22: the phone
     * shares its screen only while a computer has it open, because Android
     * 15 and later hide the phone's notification details (and keep them from
     * being swiped) while any screen is shared, and a share held all day made
     * the phone's own notifications unusable (the user's report on the S25,
     * on a Play install without unattended access).
     */
    static boolean keepsReady(Context ctx) {
        return new File(dir(ctx), "ready").exists();
    }

    static void setKeepsReady(Context ctx, boolean on) {
        mark(ctx, "ready", on);
    }

    /**
     * Screen sharing ends a little after the last computer leaves: where it
     * comes back by itself (unattended access), and wherever no held share is
     * wanted ({@link #keepsReady} off). Only a wanted share on a phone where
     * the next one needs someone at the phone is kept.
     */
    static boolean stopsWhenIdle(Context ctx) {
        return unattended(ctx) || !keepsReady(ctx);
    }

    /**
     * Ask for screen sharing when the phone has restarted: only for a share
     * kept ready with nobody at the phone, and only where Android would ask
     * someone (with unattended access the share starts by itself).
     */
    static boolean asksAfterRestart(Context ctx) {
        return isEnabled(ctx) && startsWithPhone(ctx) && keepsReady(ctx) && !unattended(ctx);
    }

    /**
     * The phone has just started (BOOT_COMPLETED, which comes once the user
     * unlocks it): the control service asks for screen sharing when Android
     * brings it back, whichever of the two comes first.
     */
    static void markBoot(Context ctx) {
        mark(ctx, "boot-pending", true);
    }

    /** The boot's mark, taken: true once per boot. */
    static boolean takeBootMark(Context ctx) {
        return new File(dir(ctx), "boot-pending").delete();
    }

    /**
     * Control was on (Android bound the control service) and was not turned
     * off in Android's settings since: a mark that outlives the process.
     * Android drops an app's accessibility service when the app is
     * force-stopped (a Xiaomi phone's Clear all in Recent apps does that),
     * without a word to the app, so the mark still standing with control
     * gone says so.
     */
    static void markControl(Context ctx, boolean on) {
        mark(ctx, "control-wanted", on);
    }

    /** How long Android may take to connect control after the app's process starts. */
    static final long UNBOUND_GRACE_MS = 20_000;

    /**
     * Control is on in Android's accessibility settings, yet Android has not
     * connected the service, well after the app's process started. Seen on a
     * Redmi Note 7 (Android 10, MIUI 12.5) after 1 update in 3 (2026-11-22):
     * it stayed so until the service was turned off and on in Android's
     * settings. The app cannot connect its own service; the person can, and
     * is told how (the card, and PhoneRemoteService.postControlUnbound).
     */
    static boolean controlUnbound(Context ctx) {
        return controlEnabled(ctx) && PhoneControlService.live() == null
                && android.os.SystemClock.elapsedRealtime() - processStart() > UNBOUND_GRACE_MS;
    }

    /** When this process started (SystemClock.elapsedRealtime's clock); a test sets its own, -1 is Android's. */
    static volatile long processStartForTest = -1;

    private static long processStart() {
        long t = processStartForTest;
        return t >= 0 ? t : android.os.Process.getStartElapsedRealtime();
    }

    /** Control was on, and Android turned control off without the user. */
    static boolean controlLost(Context ctx) {
        return new File(dir(ctx), "control-wanted").exists() && !controlEnabled(ctx);
    }

    /** A Xiaomi phone (Redmi and POCO are Xiaomi's too), whose Clear all in Recent apps force-stops apps. */
    static boolean xiaomi() {
        return "xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER);
    }

    /** MIUI's own app-op for Autostart: whether the system may start this app by itself. */
    static final int MIUI_OP_AUTOSTART = 10008;

    /**
     * On a Xiaomi phone, whether MIUI lets Android start this app by itself
     * (its Autostart permission); null where that cannot be told, or on any
     * other phone. With it off, Clear all in Recent apps kills the app and
     * MIUI refuses every restart after: Android tries to bring the control
     * service back and is turned down (Redmi Note 7, MIUI 12.5,
     * 2026-11-22: "MIUIOP(10008): ignore", refused at the moment of Clear
     * all). Read through AppOpsManager's int form, which MIUI's own op needs.
     */
    static Boolean xiaomiAutostart(Context ctx) {
        if (!xiaomi()) return null;
        try {
            android.app.AppOpsManager ops = ctx.getSystemService(android.app.AppOpsManager.class);
            java.lang.reflect.Method check = android.app.AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            Object mode = check.invoke(ops, MIUI_OP_AUTOSTART, android.os.Process.myUid(), ctx.getPackageName());
            return mode instanceof Integer ? (Integer) mode == android.app.AppOpsManager.MODE_ALLOWED : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * MIUI's Autostart switch for this app: its Autostart list, where
     * LinuxOnDroid has a switch of its own, or else the app's own info page,
     * which on MIUI has an Autostart switch at its top. MIUI asks nothing
     * itself: the person turns the switch on.
     */
    static boolean openXiaomiAutostart(android.app.Activity a) {
        android.content.Intent[] tries = {
                new android.content.Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + a.getPackageName())),
        };
        for (android.content.Intent i : tries) {
            try {
                a.startActivity(i);
                return true;
            } catch (Exception ignored) {
                // not on this phone: the next
            }
        }
        return false;
    }

    /** Android's battery optimisation leaves this app alone (Doze lets it run in the background). */
    static boolean batteryExempt(Context ctx) {
        android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    private static void mark(Context ctx, String name, boolean on) {
        File f = new File(dir(ctx), name);
        if (!on) {
            f.delete();
            return;
        }
        try {
            dir(ctx).mkdirs();
            f.createNewFile();
        } catch (Exception e) {
            DexLog.warn("phone", "cannot record " + name, e);
        }
    }

    // ── what is happening ──

    /**
     * The Settings page's view of things, read from the services themselves:
     * they run in the page's own process. {@code state} is off, waiting (on,
     * with no screen sharing), ready (sharing, nobody watching) or live.
     */
    static final class Status {
        String state = "off";
        /** Who is watching, while live. */
        String who = "";
    }

    static Status status(Context ctx) {
        Status s = new Status();
        String watcher = PhoneStream.watcher();
        if (PhoneRemoteService.sharing()) {
            s.state = watcher != null ? "live" : "ready";
        } else {
            s.state = isEnabled(ctx) ? "waiting" : "off";
        }
        s.who = watcher == null ? "" : watcher;
        return s;
    }

    // ── what the APK has ──

    /** The name a computer's stream comes in on: the tunnel's daemon connects here. */
    static String socketName(Context ctx) {
        return ctx.getPackageName() + ".phone-stream";
    }

    /** Whether this APK declares Remote desktop at all: LinuxOnDroid does, the desktop build does not. */
    static boolean offered(Context ctx) {
        try {
            ctx.getPackageManager().getServiceInfo(new ComponentName(ctx, PhoneRemoteService.class),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static ComponentName controlComponent(Context ctx) {
        return new ComponentName(ctx, PhoneControlService.class);
    }

    /**
     * Unattended access: screen sharing without Android's question, which
     * also goes on when the phone locks and can start while it is locked. The
     * app cannot give itself this: it is the {@code PROJECT_MEDIA} app-op, set
     * once from a computer over adb ({@code cmd appops set <package>
     * PROJECT_MEDIA allow}), and Android lets it through both its consent
     * dialog and its stop-at-the-lock-screen rule (MediaProjectionStopController
     * says "Continuing MediaProjection for package with OP_PROJECT_MEDIA
     * AppOp"; measured on a Galaxy S25 Ultra, Android 17). Uninstalling the app
     * takes it away, and so does setting it back to default.
     */
    static boolean unattended(Context ctx) {
        try {
            android.app.AppOpsManager ops = ctx.getSystemService(android.app.AppOpsManager.class);
            int mode = android.os.Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), ctx.getPackageName())
                    : ops.checkOpNoThrow("android:project_media", android.os.Process.myUid(), ctx.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the user switched the control service on in Android's accessibility settings. */
    static boolean controlEnabled(Context ctx) {
        try {
            if (Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                return false;
            }
            String list = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(list)) return false;
            ComponentName mine = controlComponent(ctx);
            for (String one : list.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(one);
                if (mine.equals(c)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ── quality ──

    /**
     * What each quality makes. The longest side is the picture's, in the
     * phone's pixels: the screen is drawn smaller when it is larger. 0 is the
     * screen's own size. Bits per pixel decide the bitrate the picture starts
     * at, held between the two bounds; the link then takes it down when it
     * cannot carry it ({@link LinuxStreamRate}) and back up to the ceiling.
     */
    static final class Quality {
        final String id;
        final int longSide;
        /**
         * The window's Picture size: the part of the quality's own picture
         * the picture is (1 for all of it, half its width and height for
         * 0.5), so that each step makes the picture smaller whatever the
         * quality already took off a large screen.
         */
        final double scale;
        final int fps;
        final double bpp;
        final int minBitrate;
        final int maxBitrate;

        private Quality(String id, int longSide, double scale, int fps, double bpp, int minBitrate, int maxBitrate) {
            this.id = id;
            this.longSide = longSide;
            this.scale = scale;
            this.fps = fps;
            this.bpp = bpp;
            this.minBitrate = minBitrate;
            this.maxBitrate = maxBitrate;
        }

        /**
         * Low is the fast one: a picture a third the area of Medium's, so a
         * slow or far link carries every frame and the phone answers at once.
         * All three keep 60 frames a second: a phone's screen moves with the
         * finger, and fewer frames read as lag.
         */
        static Quality of(String id) {
            if (LOW.equals(id)) return new Quality(LOW, 1280, 1, 60, 0.05, 1_000_000, 4_000_000);
            if (HIGH.equals(id)) return new Quality(HIGH, 0, 1, 60, 0.12, 6_000_000, 30_000_000);
            return new Quality(MEDIUM, 1920, 1, 60, 0.08, 2_500_000, 12_000_000);
        }

        /** The bitrate a {@code w}×{@code h} picture starts at. */
        int bitrateFor(int w, int h) {
            long b = Math.round(bpp * w * h * fps);
            return (int) Math.max(minBitrate, Math.min(maxBitrate, b));
        }

        /** This quality at the window's Picture size ({@link #scale}). */
        Quality scaled(double part) {
            return part == scale ? this : new Quality(id, longSide, part, fps, bpp, minBitrate, maxBitrate);
        }

        /**
         * The picture for a {@code screenW}x{@code screenH} screen: the
         * quality's (the screen, made smaller to its longest side), then the
         * Picture size's part of that; both sides even.
         */
        int[] pictureFor(int screenW, int screenH) {
            double s = 1.0;
            int longest = Math.max(screenW, screenH);
            if (longSide > 0 && longest > longSide) s = longSide / (double) longest;
            s *= scale;
            return new int[]{
                    Math.max(2, (int) Math.round(screenW * s) & ~1),
                    Math.max(2, (int) Math.round(screenH * s) & ~1)};
        }
    }

    /**
     * A window's Picture size, as the part of the quality's picture it asked
     * for: all of it (1) for anything not between a quarter and all; a
     * quarter at the least, the smallest that still reads.
     */
    static double sizeScale(double asked) {
        return !(asked > 0) || asked >= 1 ? 1 : Math.max(0.25, asked);
    }

    // ── files ──

    static String read(File f) {
        try {
            if (!f.exists()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Whole or not at all: written beside, then renamed over. */
    static void write(File f, String text) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception e) {
            DexLog.warn("phone", "cannot write " + f.getName(), e);
        }
    }
}
