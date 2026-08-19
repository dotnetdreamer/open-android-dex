package com.ccrstech.openandroiddex.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.io.RandomAccessFile;

/**
 * What the phone is doing right now: processor, memory, storage.
 *
 * One sampler shared by the taskbar's tray gauge and the Task Manager window,
 * because a CPU figure only exists as a DIFFERENCE between two readings — two
 * independent samplers reading /proc/stat at different moments would disagree
 * with each other on screen. Call {@link #sample()} on a timer and read the
 * fields.
 *
 * Memory and storage come from public APIs and are always available. The
 * processor does not: Android has no supported system-wide CPU API, so the
 * only source is /proc/stat, which the platform may or may not let an app
 * read. That is why {@link #cpuPercent} can be -1 — "unknown" is a real answer
 * here and the UI must be able to show it rather than a confident zero.
 */
final class SysStats {

    /** 0..100, or -1 when the platform will not let us read /proc/stat. */
    int cpuPercent = -1;
    /** 0..100 of physical RAM in use. */
    int memPercent;
    long memUsedBytes;
    long memTotalBytes;
    /** 0..100 of internal storage in use. */
    int diskPercent;
    long diskUsedBytes;
    long diskTotalBytes;

    private final ActivityManager am;
    /** Previous jiffy totals; 0 until the first successful read. */
    private long prevBusy;
    private long prevTotal;
    /** Latched once the processor proves unreadable, so we stop asking. */
    private boolean cpuUnavailable;
    /**
     * The daemon, which runs as shell (uid 2000) and CAN read /proc/stat.
     * Built lazily and only if the direct read fails, so a device that lets us
     * read it never opens a socket at all.
     */
    private WmClient wm;
    /** A CPU read is socket I/O — one in flight at a time, never on the caller. */
    private final java.util.concurrent.atomic.AtomicBoolean cpuBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    SysStats(Context ctx) {
        am = (ActivityManager) ctx.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
    }

    /**
     * Take a reading. Safe on the main thread: memory and storage are cheap
     * local calls, and the processor is refreshed on a background thread whose
     * last answer is what the fields report.
     */
    void sample() {
        sampleMemory();
        sampleDisk();
        refreshCpuAsync();
    }

    private void refreshCpuAsync() {
        if (cpuUnavailable) return;
        if (!cpuBusy.compareAndSet(false, true)) return; // one already running
        new Thread(() -> {
            try {
                sampleCpu();
            } finally {
                cpuBusy.set(false);
            }
        }, "sysstats-cpu").start();
    }

    private void sampleMemory() {
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            memTotalBytes = info.totalMem;
            memUsedBytes = info.totalMem - info.availMem;
            memPercent = percent(memUsedBytes, memTotalBytes);
        } catch (Throwable ignored) {
        }
    }

    private void sampleDisk() {
        try {
            // The data partition, not the whole device: it is the one whose
            // filling up the user can do anything about, and the one the Linux
            // container eats into.
            StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            diskTotalBytes = fs.getTotalBytes();
            diskUsedBytes = diskTotalBytes - fs.getAvailableBytes();
            diskPercent = percent(diskUsedBytes, diskTotalBytes);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Processor load, as the share of jiffies that were not idle since the
     * previous call.
     *
     * The first call establishes the baseline and reports nothing — an
     * instantaneous CPU percentage does not exist, only an average over an
     * interval, and claiming one for a zero-length interval would be a made-up
     * number.
     */
    private void sampleCpu() {
        long[] now = readProcStat();
        if (now == null) {
            // /proc/stat is labelled proc_stat, and untrusted_app has not been
            // allowed to read it since Android 9 — expected on a real phone,
            // which is why the daemon has a CPUSTAT verb. It runs as shell.
            if (wm == null) wm = new WmClient();
            now = wm.cpuStat();
        }
        if (now == null) {
            cpuUnavailable = true;
            cpuPercent = -1;
            DexLog.step("sys", "no processor figure: /proc/stat is not readable by "
                    + "this uid and the window daemon did not answer");
            return;
        }
        long busy = now[0];
        long total = now[1];
        if (prevTotal != 0 && total > prevTotal) {
            cpuPercent = percent(busy - prevBusy, total - prevTotal);
        }
        prevBusy = busy;
        prevTotal = total;
    }

    /** {busy, total} jiffies, or null when this uid may not read /proc/stat. */
    private static long[] readProcStat() {
        String line;
        try (RandomAccessFile f = new RandomAccessFile("/proc/stat", "r")) {
            line = f.readLine();
        } catch (Throwable t) {
            return null;
        }
        if (line == null || !line.startsWith("cpu ")) return null;
        long total = 0;
        long idle = 0;
        String[] parts = line.trim().split("\\s+");
        // parts[0] is "cpu"; then user nice system idle iowait irq softirq steal
        for (int i = 1; i < parts.length; i++) {
            long v;
            try {
                v = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                continue;
            }
            total += v;
            // idle + iowait: waiting on storage is not the processor working
            if (i == 4 || i == 5) idle += v;
        }
        return new long[]{total - idle, total};
    }

    private static int percent(long part, long whole) {
        if (whole <= 0) return 0;
        int p = (int) (part * 100 / whole);
        return Math.max(0, Math.min(100, p));
    }

    /** "5.2 GB" — one decimal below 100, none above, so the width is stable. */
    static String bytes(long b) {
        if (b <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = b;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (v >= 100 ? String.format(java.util.Locale.US, "%.0f", v)
                : String.format(java.util.Locale.US, "%.1f", v)) + " " + units[u];
    }
}
