package com.ccrstech.openandroiddex.launcher;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Samsung's phone-wide switch for the slowdown One UI puts on apps that only
 * run in the background: off while remote access is on, back as it was once
 * remote access is off.
 *
 * <p>With the slowdown on, Samsung's Device Health moves this app's Linux
 * process to the phone's small cores about 12 s after the launcher's last
 * window leaves the screen, and a desktop that a computer starts is born there
 * for life, 5–6x slower (see LinuxService.coreGroupForLinux). Settings.Global
 * "fgservice_filter_disabled" = 1 switches it off — for every app on the
 * phone, which is why it is off only while remote access is on, when the
 * phone is there to be used from elsewhere. Galaxy S24, 2026-11-13: Linux was
 * on the big cores within a second of the write.
 *
 * <p>Needs WRITE_SECURE_SETTINGS. The desktop app grants it over the cable and
 * keeps it in place, at its exit and on an unplug, while remote access is on
 * or while {@link #ours} says the switch is still this app's to put back — so
 * turning remote access off on the phone, with no cable, puts it back too.
 * Without the permission nothing is written and the undo waits.
 *
 * <p>The undo is a file, written before the setting and removed only once the
 * setting reads back as it was, so a process killed at any point leaves either
 * nothing changed or a record that the next {@link #sync} completes. A file
 * and not preferences, because both processes act on it: :linux whenever
 * remote access changes, the main process from RemoteResumeReceiver (after
 * every desktop exit, a boot, an update), and RemoteProvider reports it.
 */
final class SamsungSlowdown {

    static final String KEY = "fgservice_filter_disabled";
    static final String OFF = "1";
    /** The undo: what the setting held before this app turned the slowdown off. */
    static final String RECORD = "samsung-slowdown";

    /** The setting, as {@link #sync(File, boolean, Phone)} needs it. */
    interface Phone {
        String get();

        /** False when the write did not land. */
        boolean put(String value);
    }

    private static String lastWarning;

    private SamsungSlowdown() {
    }

    /** Off while remote access is on, as it was before while it is not. */
    static void sync(Context ctx, boolean remoteOn) {
        if (!"samsung".equalsIgnoreCase(Build.MANUFACTURER)) return;
        final ContentResolver cr = ctx.getContentResolver();
        boolean may = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        Phone phone = !may ? null : new Phone() {
            @Override
            public String get() {
                return Settings.Global.getString(cr, KEY);
            }

            @Override
            public boolean put(String value) {
                return Settings.Global.putString(cr, KEY, value);
            }
        };
        String said = sync(ctx.getFilesDir(), remoteOn, phone);
        if (said == null) return;
        if (said.startsWith("!")) {
            // Once each, not on every start the service gets.
            synchronized (SamsungSlowdown.class) {
                if (said.equals(lastWarning)) return;
                lastWarning = said;
            }
            DexLog.warn("linux", said.substring(1));
        } else {
            DexLog.step("linux", said);
        }
    }

    /** This app has the slowdown switched off, and has yet to put it back. */
    static boolean ours(Context ctx) {
        return new File(ctx.getFilesDir(), RECORD).exists();
    }

    /**
     * The whole of it, apart from Android: {@code phone} is null when this app
     * may not write the setting. Returns what to log — a leading '!' for a
     * warning — or null when nothing happened. Under a lock that holds across
     * both processes, and synchronized because a JVM's file locks do not hold
     * between its own threads.
     */
    static synchronized String sync(File dir, boolean remoteOn, Phone phone) {
        File record = new File(dir, RECORD);
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(dir, RECORD + ".lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            boolean ours = record.exists();
            if (!remoteOn && !ours) return null;
            if (phone == null) {
                if (ours == remoteOn) return null; // off, and ours: as it should be
                return remoteOn
                        ? "!cannot switch Samsung's background slowdown off: no WRITE_SECURE_SETTINGS"
                                + " (the desktop app grants it over the cable)"
                        : "!cannot switch Samsung's background slowdown back on yet: no WRITE_SECURE_SETTINGS"
                                + " — it goes back once the desktop app has connected";
            }
            String now = phone.get();
            if (remoteOn) {
                if (OFF.equals(now)) return null; // off already — by us, or by someone else
                // The undo before the write: killed in between, the record
                // says what to put back and the setting is still that.
                if (!ours) writeRecord(record, now);
                if (!phone.put(OFF) || !OFF.equals(phone.get())) {
                    return "!could not switch Samsung's background slowdown off — tried again on the next start";
                }
                return "Samsung's background slowdown off while remote access is on";
            }
            String before = readRecord(record);
            if (!Objects.equals(now, before)
                    && (!phone.put(before) || !Objects.equals(phone.get(), before))) {
                return "!could not switch Samsung's background slowdown back on — tried again on the next start";
            }
            if (!record.delete() && record.exists()) {
                return "!switched Samsung's background slowdown back on, but could not remove its record";
            }
            return "Samsung's background slowdown back on — remote access is off";
        } catch (Exception e) {
            return "!could not change Samsung's background slowdown switch: " + e;
        }
    }

    /** "none" for a setting that was unset, "v:" and the value otherwise. Atomic. */
    private static void writeRecord(File record, String before) throws IOException {
        File tmp = new File(record.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((before == null ? "none" : "v:" + before).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(record)) throw new IOException("cannot write " + record);
    }

    private static String readRecord(File record) throws IOException {
        byte[] buf = new byte[(int) Math.min(record.length(), 4096)];
        int n = 0;
        try (FileInputStream in = new FileInputStream(record)) {
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
        }
        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
        if (s.startsWith("v:")) return s.substring(2);
        if (s.equals("none")) return null;
        throw new IOException("unreadable " + record + ": " + s);
    }
}
