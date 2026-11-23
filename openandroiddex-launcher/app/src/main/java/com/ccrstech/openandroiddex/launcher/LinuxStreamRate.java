package com.ccrstech.openandroiddex.launcher;

/**
 * The stream's bitrate, held to what the link carries, read from how late
 * its frames arrive ({@link LinuxStream}), for a computer that says when each
 * frame arrived: a browser's page ({@code "ackAt":true} in its hello).
 *
 * <p><b>A queue, not a full link.</b> A link that takes frames slower than
 * they come does not fail at once: each frame waits behind the one before,
 * and the wait grows a little with every frame until the window of frames in
 * flight is full and the phone has to stop. Waiting for that (the stream's
 * rule until 2026-11-20, still the one for a computer that says only that a
 * frame arrived: a second in which the pump spent a fifth of its time on
 * acknowledgements) is waiting until a fifth of a second of picture is
 * already late. The queue shows long before that, as frames that stop
 * arriving as soon as the link's own delay says they should, so the bitrate
 * drops the moment they do.
 *
 * <p><b>Which delay.</b> With each acknowledgement the page says when the
 * frame arrived, by its own clock. Against when the phone had the frame ready,
 * that is the frame's trip one way, plus the difference between the two
 * clocks, which is the same for every frame: so the link's own trip is the
 * shortest of the last ten seconds ({@link #BASE_WINDOW_NS}), and the queue
 * now is the shortest of the last {@link #INTERVAL_NS} over it. The way there,
 * not the round trip: a phone's Wi-Fi in power save holds what comes to the
 * phone, acknowledgements included, for up to 150 ms and then lets it through
 * in a burst (measured on a Galaxy S25 Ultra, 2026-11-19), while the frames it
 * sends go at once. A round trip cannot tell that from a queue, and the old
 * rule cut the bitrate for it; the trip one way never sees it. And the
 * shortest, not the median: a queue delays every frame, so even the luckiest
 * is late, while a delay that comes and goes is no queue. CoDel finds a
 * router's standing queue the same way.
 *
 * <p><b>Down fast, up slowly.</b> A queue over {@link #TARGET_NS} (plus a
 * quarter of the link's own round trip, for a long path's wider spread),
 * there at two decisions in a row, cuts the bitrate by 15%, by 30% when it is
 * four times that; and when it is over twice the target, to 85% of what the
 * page received in that interval if that is lower still: a link that is
 * plainly full carries what came through it, where a smaller queue says only
 * what the encoder made. Then it waits for the cut to show (one round trip and an
 * interval) before cutting again. Once the queue has been under half the
 * target for a second and a half of motion, the bitrate climbs 8% each half
 * second, up to what the computer asked for; 4% while the last cut is less
 * than {@link #NEAR_CUT_NS} ago, since the link's limit is close.
 *
 * <p><b>Never blind.</b> Two more ways to see a queue, for when the one above
 * cannot: a frame unacknowledged {@link #HELD_NS} past the link's round trip,
 * looked at while the pump waits ({@link #held}), since a link that stops
 * sends no acknowledgements to decide by; and a shortest round trip of a
 * second over {@link #LAN_RTT_NS}, which on the network a browser reaches
 * the phone by is always a queue, since one there from the start is
 * otherwise taken for the link's own delay.
 *
 * <p>Tuned so on 2026-11-20 after a first version on a Galaxy S25 Ultra's
 * Wi-Fi, with this computer on Wi-Fi too: the computer's own power save and
 * the two ends contending held whole intervals of frames now and then, and
 * cutting to what came through at each such moment walked the bitrate down
 * to a third less than the old rule carried, at the same round trip.
 *
 * <p>Pure Java, the clock passed in: test/linux-browser/RateCheck.java runs it
 * against links simulated on a computer, beside the rule it replaced.
 */
final class LinuxStreamRate {

    /** The window the queue's shortest delay is read over. */
    static final long INTERVAL_NS = 200_000_000L;
    /** The link's own delay: the shortest of this long. */
    static final long BASE_WINDOW_NS = 10_000_000_000L;
    private static final int BUCKETS = 10;
    private static final long BUCKET_NS = BASE_WINDOW_NS / BUCKETS;
    /** A queue longer than this, and a quarter of the link's own round trip, is the link falling behind. */
    static final long TARGET_NS = 15_000_000L;
    /** Trips needed in an interval before it says anything: a still desktop sends few frames. */
    private static final int MIN_SAMPLES = 3;
    static final double DOWN = 0.85;
    static final double DOWN_HARD = 0.7;
    static final double UP = 1.08;
    static final double UP_NEAR = 1.04;
    /** How long the queue must stay short before the bitrate climbs. */
    static final long CALM_NS = 1_500_000_000L;
    /** Between two climbs. */
    static final long UP_EVERY_NS = 500_000_000L;
    /** A climb this soon after a cut is a small one. */
    static final long NEAR_CUT_NS = 6_000_000_000L;
    /** No climb this soon after a cut. */
    static final long UP_AFTER_CUT_NS = 2_000_000_000L;
    /** Decisions no closer together than this. */
    private static final long DECIDE_EVERY_NS = 50_000_000L;
    /** A frame unacknowledged this much past the link's own round trip: the link has stopped ({@link #held}). */
    static final long HELD_NS = 300_000_000L;
    /**
     * The longest the shortest round trip of a second can be, with nothing
     * queued, on the local network (or the phone's VPN) a browser reaches the
     * phone by: Wi-Fi in power save holds acknowledgements for up to 150 ms,
     * but not every one for a second. Past it there is a queue, whatever the
     * link's own delay was measured at; and that may be wrong, since a queue
     * there from the first frame (a stream that starts above what its link
     * carries, its first key frame many frames' worth) is otherwise taken for
     * the link's own and never seen again. Found on the S25 at 1.5 Mb/s,
     * 2026-11-20: a 2.6 s round trip with a queue read as 3.5 ms.
     */
    static final long LAN_RTT_NS = 400_000_000L;
    /** The link's own round trip as far as cuts are timed by it: no longer than this. */
    private static final long LAN_BASE_NS = 100_000_000L;

    private final int floor;
    private int ceiling;
    private int bitrate;

    // the last interval's frames, as their acknowledgements came, oldest first:
    // when, the trip one way, when the page had it, and its size
    private static final int RING = 256;
    private final long[] at = new long[RING];
    private final long[] trip = new long[RING];
    private final long[] arrived = new long[RING];
    private final int[] size = new int[RING];
    private int head;
    private int count;

    // the shortest of each second of the last ten: round trips, and trips one way
    private final long[] rttMin = new long[BUCKETS];
    private final long[] rttEpoch = new long[BUCKETS];
    private final long[] tripMin = new long[BUCKETS];
    private final long[] tripEpoch = new long[BUCKETS];

    private long lastDecide;
    private long holdUntil;
    private long calmSince = -1;
    private long lastUp;
    private long lastCut = Long.MIN_VALUE / 2;
    private long lastQueueNs = -1;
    /** When {@link #lastQueueNs} was read: a reading this old says nothing of the link now. */
    private long lastQueueAt = Long.MIN_VALUE / 2;
    /** When the queue last went over the target, or -1 while it is under. */
    private long highSince = -1;

    /** Cuts made, by what made them, for the log and the tests. */
    int cutsByQueue;
    int cutsByFull;
    int cutsByHeld;

    LinuxStreamRate(int bitrate, int ceiling, int floor) {
        this.floor = floor;
        this.ceiling = Math.max(floor, ceiling);
        this.bitrate = Math.max(floor, Math.min(this.ceiling, bitrate));
        java.util.Arrays.fill(rttEpoch, -1);
        java.util.Arrays.fill(tripEpoch, -1);
    }

    synchronized int bitrate() {
        return bitrate;
    }

    /** The queue the last decision saw, in ns; -1 before there was one. */
    synchronized long queueNs() {
        return lastQueueNs;
    }

    /** The bitrate to go at now and the most to climb to: a quality chosen, the route changed. */
    synchronized void set(int bitrate, int ceiling, long now) {
        this.ceiling = Math.max(floor, ceiling);
        this.bitrate = Math.max(floor, Math.min(this.ceiling, bitrate));
        calmSince = -1;
        holdUntil = now + INTERVAL_NS;
        lastUp = now;
    }

    /**
     * One frame of {@code bytes}, as its acknowledgement came at {@code now}:
     * when the phone had it ready ({@code readyNs}, the phone's clock) and
     * when the page had it ({@code arrivedNs}, the page's clock, both in ns).
     */
    synchronized void ack(long now, long readyNs, long arrivedNs, int bytes) {
        long rttNs = now - readyNs;
        if (rttNs < 0) return;
        long tripNs = arrivedNs - readyNs;
        if (count == RING) {
            head = (head + 1) % RING;
            count--;
        }
        int i = (head + count) % RING;
        at[i] = now;
        trip[i] = tripNs;
        arrived[i] = arrivedNs;
        size[i] = bytes;
        count++;
        keep(rttMin, rttEpoch, now, rttNs);
        keep(tripMin, tripEpoch, now, tripNs);
    }

    /** What the page received over the interval, in bits a second; 0 when that cannot be said. */
    private long received() {
        if (count < MIN_SAMPLES) return 0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (int k = 0; k < count; k++) {
            int i = (head + k) % RING;
            first = Math.min(first, arrived[i]);
            last = Math.max(last, arrived[i]);
        }
        long span = last - first;
        if (span < INTERVAL_NS / 4) return 0;
        long bytes = 0;
        for (int k = 0; k < count; k++) {
            int i = (head + k) % RING;
            // the first frame's bytes came before the span began
            if (arrived[i] != first) bytes += size[i];
        }
        return bytes * 8L * 1_000_000_000L / span;
    }

    private static void keep(long[] min, long[] epochs, long now, long v) {
        long epoch = now / BUCKET_NS;
        int b = (int) (epoch % BUCKETS);
        if (epochs[b] != epoch) {
            epochs[b] = epoch;
            min[b] = v;
        } else if (v < min[b]) {
            min[b] = v;
        }
    }

    private static long shortest(long[] min, long[] epochs, long now) {
        long epoch = now / BUCKET_NS;
        long best = Long.MAX_VALUE;
        for (int b = 0; b < BUCKETS; b++) {
            if (epochs[b] >= 0 && epoch - epochs[b] < BUCKETS) best = Math.min(best, min[b]);
        }
        return best;
    }

    /** The shortest of this second and the last: what the link does now. */
    private static long shortestRecent(long[] min, long[] epochs, long now) {
        long epoch = now / BUCKET_NS;
        long best = Long.MAX_VALUE;
        for (int b = 0; b < BUCKETS; b++) {
            if (epochs[b] >= 0 && epoch - epochs[b] <= 1) best = Math.min(best, min[b]);
        }
        return best;
    }

    /** The link's own round trip, held to what a local network's can be ({@link #LAN_BASE_NS}). */
    private long linkRtt(long now) {
        long b = shortest(rttMin, rttEpoch, now);
        return b == Long.MAX_VALUE ? LAN_BASE_NS : Math.min(b, LAN_BASE_NS);
    }

    private long target(long now) {
        long rttBase = shortest(rttMin, rttEpoch, now);
        return TARGET_NS + (rttBase == Long.MAX_VALUE ? 0 : rttBase / 4);
    }

    /**
     * Whether the bitrate should change, at {@code now}: called as
     * acknowledgements come. {@code moving} is whether the desktop is
     * changing (frames flowing): only motion says the link can carry more.
     *
     * @return the new bitrate, or 0 to keep it
     */
    synchronized int decide(long now, boolean moving) {
        if (now - lastDecide < DECIDE_EVERY_NS) return 0;
        lastDecide = now;
        while (count > 0 && now - at[head] > INTERVAL_NS) {
            head = (head + 1) % RING;
            count--;
        }
        if (count < MIN_SAMPLES) return 0;
        long base = shortest(tripMin, tripEpoch, now);
        if (base == Long.MAX_VALUE) return 0;
        long shortTrip = Long.MAX_VALUE;
        for (int k = 0; k < count; k++) shortTrip = Math.min(shortTrip, trip[(head + k) % RING]);
        long queue = Math.max(0, shortTrip - base);
        // A round trip this long is a queue whatever the base says (LAN_RTT_NS).
        long recentRtt = shortestRecent(rttMin, rttEpoch, now);
        if (recentRtt != Long.MAX_VALUE && recentRtt > LAN_RTT_NS) queue = Math.max(queue, recentRtt - LAN_BASE_NS);
        lastQueueNs = queue;
        lastQueueAt = now;
        long target = target(now);
        if (queue > target) {
            calmSince = -1;
            // Standing for two decisions in a row, not one: Wi-Fi that hands
            // over a burst late (the computer's own in power save, or both
            // ends contending) can hold every frame of an interval once.
            if (highSince < 0) highSince = now;
            if (now - highSince < DECIDE_EVERY_NS) return 0;
            if (now < holdUntil || bitrate <= floor) return 0;
            double f = queue > 4 * target ? DOWN_HARD : DOWN;
            // What came through is what the link carries only when the link
            // is plainly full; otherwise it is only what the encoder made.
            long carried = queue > 2 * target ? received() : 0;
            long byLink = carried > 0 ? (long) (carried * DOWN) : Long.MAX_VALUE;
            int next = (int) Math.max(floor, Math.min((long) (bitrate * f), byLink));
            // The cut shows a round trip later, and is read over an interval.
            holdUntil = now + linkRtt(now) + INTERVAL_NS;
            lastCut = now;
            bitrate = next;
            cutsByQueue++;
            return next;
        }
        highSince = -1;
        if (queue * 2 > target || !moving) {
            calmSince = -1;
            return 0;
        }
        if (calmSince < 0) calmSince = now;
        if (bitrate >= ceiling || now - calmSince < CALM_NS || now - lastCut < UP_AFTER_CUT_NS
                || now - lastUp < UP_EVERY_NS) {
            return 0;
        }
        lastUp = now;
        double f = now - lastCut < NEAR_CUT_NS ? UP_NEAR : UP;
        int next = Math.min(ceiling, Math.max(bitrate + 1, (int) (bitrate * f)));
        bitrate = next;
        return next;
    }

    /**
     * The old rule's backstop, once a second: a second in which the pump
     * waited over a fifth of its time on acknowledgements is a link that is
     * full, and cuts by 30%; unless the frames themselves arrived on time
     * (the trip one way shows no queue), when what waited was the
     * acknowledgements, held by the phone's Wi-Fi in power save.
     *
     * @return the new bitrate, or 0 to keep it
     */
    synchronized int full(long now, double share) {
        if (share <= 0.2 || bitrate <= floor) return 0;
        // Only a reading of this last moment speaks for the frames: one from
        // before acknowledgements stopped coming says nothing.
        if (lastQueueNs >= 0 && now - lastQueueAt < 2 * INTERVAL_NS && lastQueueNs * 2 <= target(now)) return 0;
        int next = Math.max(floor, (int) (bitrate * DOWN_HARD));
        holdUntil = now + INTERVAL_NS;
        lastCut = now;
        calmSince = -1;
        bitrate = next;
        cutsByFull++;
        return next;
    }

    /**
     * A frame still not acknowledged long past the link's own round trip:
     * asked while the pump waits on acknowledgements, which is exactly when
     * none come to decide by. Over {@link #HELD_NS} late (twice the longest
     * hold a phone's power save was measured to make), the link has stopped
     * taking what it is given, and the bitrate drops by 30%, again each
     * round trip and interval while it stays so. Found on the S25, 2026-11-20:
     * with acknowledgements too few to decide by, the bitrate stayed at its
     * ceiling while a second of frames queued in the phone's socket.
     *
     * @param oldestReadyNs when the oldest frame not yet acknowledged was ready
     * @return the new bitrate, or 0 to keep it
     */
    synchronized int held(long now, long oldestReadyNs) {
        long late = now - oldestReadyNs - linkRtt(now);
        if (late <= HELD_NS) return 0;
        lastQueueNs = late;
        lastQueueAt = now;
        calmSince = -1;
        if (now < holdUntil || bitrate <= floor) return 0;
        int next = Math.max(floor, (int) (bitrate * DOWN_HARD));
        holdUntil = now + linkRtt(now) + INTERVAL_NS;
        lastCut = now;
        bitrate = next;
        cutsByHeld++;
        return next;
    }
}
