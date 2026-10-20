package com.ccrstech.openandroiddex.launcher;

/**
 * Whether the guest's sound is running late, judged from how full the tap's
 * socket is: {@link LinuxAudio}'s drift cap, kept free of Android so that it
 * can be checked on a computer (test/linux-windows-test.mjs).
 *
 * <p>The tap does not trickle. PulseAudio's simple protocol asks its source
 * for 300 ms of latency, so a quiet guest's monitor arrives as one 300 ms
 * burst every 300 ms — 57,600 bytes, measured on the phone — and right after
 * each one the socket holds a burst's worth with no lag at all. Held against
 * that, a 250 ms cap fired on every burst and threw most of it away, several
 * times a second, for as long as a session ran.
 *
 * <p>Lag is the part that never drains. So the cap is held against the lowest
 * the backlog gets over a window several bursts long: a sawtooth of bursts
 * bottoms out near zero every time, and only drift, or a stall, lifts the
 * bottom.
 */
final class LinuxAudioLag {

    private final int max;
    private final int keep;
    private final long windowMs;

    private int floor = Integer.MAX_VALUE;
    private long from = Long.MIN_VALUE;

    /**
     * @param max      the most backlog, in bytes, that may stay unplayed
     * @param keep     how much of it a skip leaves behind
     * @param windowMs how long the low point is watched before it counts
     */
    LinuxAudioLag(int max, int keep, long windowMs) {
        this.max = max;
        this.keep = keep;
        this.windowMs = windowMs;
    }

    /**
     * One look at the backlog, taken before each read.
     *
     * @return how many bytes to skip now; almost always 0
     */
    int skip(int backlog, long nowMs) {
        if (from == Long.MIN_VALUE) from = nowMs;
        if (backlog < floor) floor = backlog;
        if (nowMs - from < windowMs) return 0;
        int low = floor;
        floor = Integer.MAX_VALUE;
        from = nowMs;
        return low > max ? low - keep : 0;
    }
}
