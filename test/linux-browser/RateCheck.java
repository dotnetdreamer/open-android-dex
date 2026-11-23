package com.ccrstech.openandroiddex.launcher;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

/**
 * LinuxStreamRate against simulated links, beside the rule it replaced, for
 * test/linux-browser-test.mjs. A simulation, not a network: frames at 60 a
 * second sized by the bitrate, paced by LinuxStream's window of frames in
 * flight, through one bottleneck of a given speed, with a given round trip,
 * and optionally a phone's Wi-Fi in power save (what comes to the phone,
 * acknowledgements included, held while the radio dozes, 40 to 150 ms at a
 * time, about every 400 ms, then let through at once). Prints
 * {@code RATE <link> <rule> key=value…}.
 *
 * <p>Two rules: the new one, as a browser's page drives it (each
 * acknowledgement saying when its frame arrived, by a clock a second off the
 * phone's and 20 ppm fast); and the old one, as LinuxStream.adapt has it for
 * a computer that says only that a frame arrived: once a second, a second in
 * which the pump waited over a fifth of its time on acknowledgements cuts the
 * bitrate by 30%; three seconds of motion under 2% climb by 25%.
 */
public final class RateCheck {

    static final int FPS = 60;
    static final long FRAME_NS = 1_000_000_000L / FPS;
    static final int MIN_WINDOW = 2;
    static final int MAX_WINDOW = 12;
    static final int FLOOR = 500_000;

    interface Rule {
        int bitrate();

        /** A frame of {@code bytes} acknowledged at {@code now}: ready then by the phone's clock, arrived then by the page's. */
        void ack(long now, long readyNs, long arrivedNs, int bytes);

        void afterAck(long now, boolean moving);

        /** While the pump waits on acknowledgements: the oldest frame not acknowledged, ready then. */
        default void waiting(long now, long oldestReadyNs) {
        }

        /** Once a second, with the share of it the pump waited on acknowledgements. */
        void second(long now, double waitShare, int frames);
    }

    static final class NewRule implements Rule {
        final LinuxStreamRate r;

        NewRule(int ceiling) {
            r = new LinuxStreamRate(ceiling, ceiling, FLOOR);
        }

        public int bitrate() {
            return r.bitrate();
        }

        public void ack(long now, long readyNs, long arrivedNs, int bytes) {
            r.ack(now, readyNs, arrivedNs, bytes);
        }

        public void afterAck(long now, boolean moving) {
            r.decide(now, moving);
        }

        public void waiting(long now, long oldestReadyNs) {
            r.held(now, oldestReadyNs);
        }

        public void second(long now, double waitShare, int frames) {
            r.full(now, waitShare);
        }
    }

    static final class OldRule implements Rule {
        int bitrate;
        final int ceiling;
        int keptUp;

        OldRule(int ceiling) {
            this.ceiling = ceiling;
            this.bitrate = ceiling;
        }

        public int bitrate() {
            return bitrate;
        }

        public void ack(long now, long readyNs, long arrivedNs, int bytes) {
        }

        public void afterAck(long now, boolean moving) {
        }

        public void second(long now, double behind, int frames) {
            if (behind > 0.2) {
                keptUp = 0;
                bitrate = Math.max(FLOOR, (int) (bitrate * 0.7));
            } else if (behind < 0.02 && frames >= FPS / 4 && bitrate < ceiling) {
                if (++keptUp >= 3) {
                    keptUp = 0;
                    bitrate = Math.min(ceiling, (int) (bitrate * 1.25));
                }
            }
        }
    }

    /** A link: its speed in bits a second from a time on, its round trip, and power save or not. */
    static final class Link {
        final String name;
        final long[] fromNs;
        final long[] bps;
        final long baseNs;
        final boolean powerSave;

        Link(String name, long baseMs, boolean powerSave, long... pairs) {
            this.name = name;
            this.baseNs = baseMs * 1_000_000L;
            this.powerSave = powerSave;
            fromNs = new long[pairs.length / 2];
            bps = new long[pairs.length / 2];
            for (int i = 0; i < fromNs.length; i++) {
                fromNs[i] = pairs[2 * i] * 1_000_000_000L;
                bps[i] = pairs[2 * i + 1];
            }
        }

        /** Stalls: every so many seconds, for so long, at so few bits a second. */
        long stallEveryNs;
        long stallForNs;
        long stallBps;

        Link stalls(double everyS, double forS, long bps) {
            stallEveryNs = (long) (everyS * 1e9);
            stallForNs = (long) (forS * 1e9);
            stallBps = bps;
            return this;
        }

        long bpsAt(long t) {
            if (stallEveryNs > 0 && t % stallEveryNs >= stallEveryNs - stallForNs) return stallBps;
            long v = bps[0];
            for (int i = 0; i < fromNs.length; i++) if (t >= fromNs[i]) v = bps[i];
            return v;
        }
    }

    static final class Result {
        double[] lateMs;
        int frames;
        int cuts;
        double meanMbps;
        double firstCutAfterS = -1;
        double mbpsAt;
    }

    /**
     * {@code fromS}..{@code toS}: the part measured; {@code eventS}: a change
     * of speed to time the first cut from; {@code mbpsAtS}: when to read the
     * bitrate.
     */
    static Result run(Link link, Rule rule, double seconds, double fromS, double toS, double eventS, double mbpsAtS, long seed) {
        Random rnd = new Random(seed);
        long end = (long) (seconds * 1e9);
        long[] dozeFrom = new long[4096];
        long[] dozeTo = new long[4096];
        int dozes = 0;
        if (link.powerSave) {
            long t = 0;
            while (t < end && dozes < dozeFrom.length) {
                t += (long) (-Math.log(1 - rnd.nextDouble()) * 400e6);
                long len = (long) ((40 + rnd.nextDouble() * 110) * 1e6);
                dozeFrom[dozes] = t;
                dozeTo[dozes] = t + len;
                dozes++;
                t += len;
            }
        }
        // The computer's clock: a second off the phone's, and 20 ppm fast.
        final long offsetNs = 1_000_000_000L;
        ArrayDeque<long[]> pending = new ArrayDeque<>(); // {readyNs, ackAtNs, arriveNs, bytes}
        long txFree = 0;
        long[] rtts = new long[32];
        int rttCount = 0;
        long rttMedian = -1;
        long nextTick = 0;
        long waitFrom = -1;
        long waitedNs = 0;
        int framesThisSecond = 0;
        long secondStart = 0;
        double[] late = new double[(int) (seconds * FPS) + 16];
        int lateN = 0;
        int cuts = 0;
        int lastRate = rule.bitrate();
        long bitsSent = 0;
        int sent = 0;
        double firstCut = -1;
        double mbpsAt = -1;
        long eventNs = eventS < 0 ? -1 : (long) (eventS * 1e9);
        long lastWaitCheck = 0;
        // time in 0.25 ms steps: fine enough for frames 16.7 ms apart
        for (long now = 0; now < end; now += 250_000L) {
            while (!pending.isEmpty() && pending.peekFirst()[1] <= now) {
                long[] f = pending.pollFirst();
                long rtt = f[1] - f[0];
                rule.ack(now, f[0], f[2] + offsetNs + f[2] / 50_000, (int) f[3]);
                rtts[rttCount++ % rtts.length] = rtt;
                long[] s = Arrays.copyOf(rtts, Math.min(rttCount, rtts.length));
                Arrays.sort(s);
                rttMedian = s[s.length / 2];
                rule.afterAck(now, true);
                int br = rule.bitrate();
                if (br < lastRate) {
                    cuts++;
                    if (eventNs >= 0 && now >= eventNs && firstCut < 0) firstCut = (now - eventNs) / 1e9;
                }
                lastRate = br;
            }
            int window = rttMedian < 0 ? MIN_WINDOW + 1
                    : (int) Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, (rttMedian + FRAME_NS - 1) / FRAME_NS + 1));
            if (now >= nextTick) {
                if (pending.size() >= window) {
                    if (waitFrom < 0) waitFrom = now;
                    // As LinuxStream's pump: the oldest frame looked at every 100 ms while it waits.
                    if (now - lastWaitCheck >= 100_000_000L) {
                        lastWaitCheck = now;
                        rule.waiting(now, pending.peekFirst()[0]);
                        int br = rule.bitrate();
                        if (br < lastRate) {
                            cuts++;
                            if (eventNs >= 0 && now >= eventNs && firstCut < 0) firstCut = (now - eventNs) / 1e9;
                        }
                        lastRate = br;
                    }
                } else {
                    if (waitFrom >= 0) {
                        waitedNs += now - waitFrom;
                        waitFrom = -1;
                    }
                    // a frame of a desktop where every pixel moves, at the bitrate now
                    int br = rule.bitrate();
                    double size = br / 8.0 / FPS * (0.6 + rnd.nextDouble());
                    // The first is a key frame of the whole desktop: ten frames' worth.
                    if (sent == 0) size *= 10;
                    long start = Math.max(now, txFree);
                    long bps = link.bpsAt(start);
                    long txEnd = start + (long) (size * 8 * 1e9 / bps);
                    txFree = txEnd;
                    long arrive = txEnd + link.baseNs / 2;
                    long ackAt = arrive + link.baseNs / 2 + (long) (rnd.nextDouble() * 1e6);
                    for (int d = 0; d < dozes; d++) {
                        if (ackAt >= dozeFrom[d] && ackAt < dozeTo[d]) {
                            ackAt = dozeTo[d];
                            break;
                        }
                        if (dozeFrom[d] > ackAt) break;
                    }
                    // one TCP stream: a later frame's ack never overtakes an earlier one's
                    if (!pending.isEmpty()) ackAt = Math.max(ackAt, pending.peekLast()[1]);
                    pending.addLast(new long[]{now, ackAt, arrive, (long) size});
                    sent++;
                    bitsSent += (long) (size * 8);
                    framesThisSecond++;
                    if (now >= fromS * 1e9 && now < toS * 1e9 && lateN < late.length) late[lateN++] = (arrive - now) / 1e6;
                    nextTick = now + FRAME_NS;
                }
            }
            if (now - secondStart >= 1_000_000_000L) {
                long w = waitedNs + (waitFrom >= 0 ? now - waitFrom : 0);
                if (waitFrom >= 0) waitFrom = now;
                rule.second(now, w / 1e9, framesThisSecond);
                int br = rule.bitrate();
                if (br < lastRate) {
                    cuts++;
                    if (eventNs >= 0 && now >= eventNs && firstCut < 0) firstCut = (now - eventNs) / 1e9;
                }
                lastRate = br;
                waitedNs = 0;
                framesThisSecond = 0;
                secondStart = now;
            }
            if (mbpsAtS >= 0 && mbpsAt < 0 && now >= mbpsAtS * 1e9) mbpsAt = rule.bitrate() / 1e6;
        }
        Result r = new Result();
        r.lateMs = Arrays.copyOf(late, lateN);
        Arrays.sort(r.lateMs);
        r.frames = sent;
        r.cuts = cuts;
        r.meanMbps = bitsSent / seconds / 1e6;
        r.firstCutAfterS = firstCut;
        r.mbpsAt = mbpsAt;
        return r;
    }

    static double pct(double[] s, double p) {
        if (s.length == 0) return -1;
        return s[Math.min(s.length - 1, (int) (s.length * p))];
    }

    static void report(String link, String name, Rule rule, Result r, double measuredS) {
        String by = rule instanceof NewRule
                ? " byQueue=" + ((NewRule) rule).r.cutsByQueue + " byFull=" + ((NewRule) rule).r.cutsByFull
                + " byHeld=" + ((NewRule) rule).r.cutsByHeld : "";
        System.out.printf(java.util.Locale.ROOT,
                "RATE %s %s medianMs=%.1f p95Ms=%.1f fps=%.1f mbps=%.2f cuts=%d firstCutS=%.3f mbpsAt=%.2f%s%n",
                link, name, pct(r.lateMs, 0.5), pct(r.lateMs, 0.95), r.lateMs.length / measuredS, r.meanMbps, r.cuts,
                r.firstCutAfterS, r.mbpsAt, by);
    }

    public static void main(String[] args) {
        int ceiling = 25_000_000;
        Object[][] links = {
                // link, seconds, measured from, to, event, bitrate read at
                {new Link("cable", 2, false, 0, 100_000_000L), 30.0, 5.0, 30.0, -1.0, -1.0},
                {new Link("powersave", 8, true, 0, 100_000_000L), 30.0, 5.0, 30.0, -1.0, -1.0},
                {new Link("slow", 5, false, 0, 8_000_000L), 30.0, 5.0, 30.0, -1.0, -1.0},
                {new Link("slowpowersave", 8, true, 0, 8_000_000L), 30.0, 5.0, 30.0, -1.0, -1.0},
                {new Link("drop", 5, false, 0, 40_000_000L, 10, 6_000_000L), 30.0, 10.0, 15.0, 10.0, -1.0},
                {new Link("recover", 5, false, 0, 6_000_000L, 10, 60_000_000L), 30.0, 10.0, 30.0, -1.0, 25.0},
                // A stream that starts at High's 25 Mb/s on a link of 1.5: a queue there
                // before the link's own delay was ever seen (found on the S25).
                {new Link("start", 10, false, 0, 1_500_000L), 30.0, 5.0, 30.0, -1.0, -1.0},
                // Wi-Fi that stops now and then: 1.5 s at 50 kb/s every 6 s, 30 Mb/s between.
                {new Link("stalls", 8, false, 0, 30_000_000L).stalls(6, 1.5, 50_000L), 30.0, 5.0, 30.0, -1.0, -1.0},
        };
        for (Object[] l : links) {
            Link link = (Link) l[0];
            double secs = (Double) l[1];
            double from = (Double) l[2];
            double to = (Double) l[3];
            double event = (Double) l[4];
            double at = (Double) l[5];
            Rule a = new NewRule(ceiling);
            report(link.name, "new", a, run(link, a, secs, from, to, event, at, 7), to - from);
            Rule c = new OldRule(ceiling);
            report(link.name, "old", c, run(link, c, secs, from, to, event, at, 7), to - from);
        }
    }
}
