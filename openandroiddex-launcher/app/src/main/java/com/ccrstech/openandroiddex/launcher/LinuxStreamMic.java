package com.ccrstech.openandroiddex.launcher;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;

/**
 * A browser's microphone, into Linux ({@link LinuxStream}): what the page
 * records goes into a pipe in Linux's /tmp, which the guest's PulseAudio reads
 * as a microphone of its own, "Browser microphone", the default one
 * (module-pipe-source, loaded by linux-rt.sh beside /etc/pulse/dex.pa). Any
 * Linux app that records hears the computer.
 *
 * <p>The page sends PCM: s16le, 48 kHz, one channel, 10 ms at a time. Written
 * as it comes, never waited on: the pipe is opened without blocking and held
 * to one page of the kernel's (4 KB, 42 ms of sound), so that when nothing in
 * Linux is recording, and PulseAudio reads nothing, what piles up is at most
 * that, and the rest is dropped rather than heard late once something starts.
 *
 * <p>No pipe (a Linux started by an older LinuxOnDroid, or no PulseAudio in
 * it), or nobody reading it: the browser is told, and the microphone is
 * tried again every couple of seconds while it is on.
 */
final class LinuxStreamMic {

    /** What the page sends, all of it: one channel of s16le at this rate. */
    static final int RATE = 48000;
    /** F_SETPIPE_SZ (Linux's fcntl.h); not in OsConstants. */
    private static final int F_SETPIPE_SZ = 1031;
    private static final int PIPE_BYTES = 4096;
    private static final long RETRY_MS = 2000;

    /** What the browser is told: the microphone reaches Linux, or not. */
    interface Status {
        void reached(boolean yes);
    }

    private final File pipe;
    private final Status status;
    private FileDescriptor fd;
    private long triedAt;
    private Boolean said;

    /** Bytes written into Linux, and dropped (a full pipe, or none), for the stats and the tests. */
    long written;
    long dropped;

    LinuxStreamMic(File pipe, Status status) {
        this.pipe = pipe;
        this.status = status;
    }

    /**
     * One run of the page's PCM. On the stream's input thread. Written a
     * pipe's page at most at a time: the kernel writes that much or nothing,
     * so a full pipe drops whole pieces and the samples never fall out of
     * step. (A pipe whose reader went answers EPIPE: ART blocks SIGPIPE in
     * every thread it runs, so it is an error here and not the process's end.)
     */
    synchronized void write(byte[] d, int off, int len) {
        len -= len % 2;
        if (len <= 0) return;
        if (fd == null && !open()) {
            dropped += len;
            return;
        }
        while (len > 0) {
            int n = Math.min(len, PIPE_BYTES);
            try {
                written += Os.write(fd, d, off, n);
            } catch (ErrnoException e) {
                dropped += n;
                if (e.errno != OsConstants.EAGAIN) {
                    // The reader went: PulseAudio stopped, or Linux did.
                    dropped += len - n;
                    close();
                    say(false);
                    return;
                }
            } catch (java.io.InterruptedIOException e) {
                dropped += n;
            }
            off += n;
            len -= n;
        }
    }

    private boolean open() {
        long now = System.currentTimeMillis();
        if (now - triedAt < RETRY_MS) return false;
        triedAt = now;
        if (pipe == null) {
            say(false);
            return false;
        }
        try {
            // Without blocking: a pipe with no reader would hold this thread,
            // and with it the browser's keys, until one came.
            fd = Os.open(pipe.getPath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
        } catch (ErrnoException e) {
            fd = null;
            say(false);
            return false;
        }
        try {
            Os.fcntlInt(fd, F_SETPIPE_SZ, PIPE_BYTES);
        } catch (ErrnoException | RuntimeException ignored) {
            // a bigger pipe still works; only a backlog waits longer
        }
        say(true);
        return true;
    }

    private void say(boolean yes) {
        if (said != null && said == yes) return;
        said = yes;
        DexLog.step("stream", yes ? "the browser's microphone goes into Linux" : "the browser's microphone cannot reach Linux ("
                + (pipe == null ? "no pipe" : pipe.exists() ? "nothing reads " + pipe.getName() : "no " + pipe.getName()) + ")");
        status.reached(yes);
    }

    /** The microphone on: the pipe opened now, so that the browser hears at once whether it reaches Linux. */
    synchronized void start() {
        triedAt = 0;
        said = null;
        if (fd == null) open();
    }

    /** The microphone off, or the session over. */
    synchronized void close() {
        FileDescriptor f = fd;
        fd = null;
        if (f != null) {
            try {
                Os.close(f);
            } catch (ErrnoException ignored) {
            }
        }
    }
}
