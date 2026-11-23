package com.ccrstech.openandroiddex.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether the Linux desktop is running, as the kernel knows it.
 *
 * <p>The {@code :linux} process holds an exclusive lock on one file
 * ({@link Linux#rtLock}) from just before it spawns linux-rt.sh until that
 * process has exited. Any OTHER process can ask whether the lock is held, and
 * the kernel answers. When {@code :linux} dies, however it dies (an update, a
 * force-stop, Android reclaiming memory, a crash), the kernel drops its locks
 * with it, so the answer turns at that moment and never names a desktop that
 * is gone. A runtime never outlives the process that started it: Android kills
 * an app process's whole group with it.
 *
 * <p>This replaced naming the process by pid (rt.owner). A pid says nothing on
 * its own once the process is gone: the next {@code :linux} can be given the
 * same number, and then a leftover rt.pid read as a running desktop. Asking
 * /proc for the process's start time would tell the two apart, but an app
 * cannot read another process's /proc, not even one of its own uid (see
 * Linux.readStatus), so that check would never pass.
 *
 * <p>Two rules make it exact:
 * <ul>
 *   <li>{@code :linux} never opens the file anywhere but here. A POSIX lock
 *       belongs to the process, and closing ANY descriptor of the file in it
 *       drops the lock.</li>
 *   <li>{@link #heldElsewhere} is for other processes only (the home screen,
 *       in the main process). It takes a shared lock for an instant to ask,
 *       which is why taking the exclusive one here tries again rather than
 *       failing at the first refusal.</li>
 * </ul>
 * No Android classes, so the same code is tested across real processes on a
 * plain JVM.
 */
final class RuntimeLock {

    private RuntimeLock() {
    }

    /** Guards everything below; also what {@link #unlessHeldHere} runs under. */
    private static final Object GUARD = new Object();
    private static RandomAccessFile file;
    private static FileLock lock;
    /** Spawns between {@link #beforeSpawn} and {@link #spawned}/{@link #spawnFailed}. */
    private static int pending;
    /** Runtimes this process spawned that have not been seen to exit. */
    private static final Set<Process> live = new HashSet<>();

    /** How long a spawn waits out another process's question before going ahead unlocked. */
    private static final long TAKE_FOR_MS = 3000;

    /**
     * Hold the lock for a runtime about to be spawned. Before the spawn, so
     * there is no moment with a runtime and no lock; then {@link #spawned}
     * with what was spawned, or {@link #spawnFailed}.
     *
     * @return false when the lock could not be had (it is not, then, what
     *         says the desktop is up; the spawn goes ahead regardless)
     */
    static boolean beforeSpawn(File f) {
        synchronized (GUARD) {
            pending++;
            if (lock != null && lock.isValid()) return true;
            long end = System.currentTimeMillis() + TAKE_FOR_MS;
            try {
                if (file == null) {
                    File dir = f.getParentFile();
                    if (dir != null) dir.mkdirs();
                    file = new RandomAccessFile(f, "rw");
                }
                while (true) {
                    try {
                        lock = file.getChannel().tryLock();
                    } catch (OverlappingFileLockException ours) {
                        lock = null; // cannot happen: only this class takes it here
                    }
                    if (lock != null) return true;
                    // Another process is asking this instant: ask again.
                    if (System.currentTimeMillis() >= end) return false;
                    try {
                        GUARD.wait(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** The runtime spawned after {@link #beforeSpawn}: it keeps the lock until {@link #exited}. */
    static void spawned(Process p) {
        synchronized (GUARD) {
            if (pending > 0) pending--;
            live.add(p);
            releaseIfIdle();
        }
    }

    /** The spawn after {@link #beforeSpawn} did not happen. */
    static void spawnFailed() {
        synchronized (GUARD) {
            if (pending > 0) pending--;
            releaseIfIdle();
        }
    }

    /** {@code p} has exited (its waitFor returned). */
    static void exited(Process p) {
        synchronized (GUARD) {
            live.remove(p);
            releaseIfIdle();
        }
    }

    /** This process has a runtime up, or one on its way. */
    static boolean heldHere() {
        synchronized (GUARD) {
            return pending > 0 || !live.isEmpty();
        }
    }

    /**
     * Run {@code r} only if this process has no runtime up or on its way, and
     * with no spawn able to begin while it runs. What a new service uses to
     * drop a leftover rt.pid without dropping the one a spawn has just written.
     *
     * @return whether it ran
     */
    static boolean unlessHeldHere(Runnable r) {
        synchronized (GUARD) {
            if (pending > 0 || !live.isEmpty()) return false;
            r.run();
            return true;
        }
    }

    /**
     * Is the lock held by another process, which is to say: is a desktop
     * running? Never ask from {@code :linux} (see the class comment).
     *
     * @return null when it cannot be told
     */
    static Boolean heldElsewhere(File f) {
        if (!f.exists()) return false; // nothing has ever held it
        try (FileInputStream in = new FileInputStream(f)) {
            // Shared, from a read-only descriptor: it conflicts with the
            // holder's exclusive lock and with nothing else, and it creates
            // and writes nothing.
            FileLock probe = in.getChannel().tryLock(0, Long.MAX_VALUE, true);
            if (probe == null) return true;
            probe.release();
            return false;
        } catch (OverlappingFileLockException sameProcess) {
            // The holder is this very process. Only a test puts both halves in
            // one process; in the app they never share one.
            return true;
        } catch (IOException e) {
            return null;
        }
    }

    private static void releaseIfIdle() {
        if (pending > 0 || !live.isEmpty()) return;
        try {
            if (lock != null) lock.release();
        } catch (IOException ignored) {
            // closing the file below drops it anyway
        }
        try {
            if (file != null) file.close();
        } catch (IOException ignored) {
        }
        lock = null;
        file = null;
        GUARD.notifyAll();
    }
}
