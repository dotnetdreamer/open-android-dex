package com.ccrstech.openandroiddex.wmd;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * AppMute's rules on this computer's JVM, with a stand-in for the phone's
 * music stream and its capture (test/apps-mute-test.mjs). Prints one line a
 * check: "  ok  …" or "  FAIL …".
 */
public final class MuteRules {

    /** The phone's music stream, which several servers share, as on a phone. */
    static final class Stream implements AppMute.Phone {
        volatile boolean muted;
        /** It quietly does not take. */
        volatile boolean keepsMute, keepsSound;
        /** It throws. */
        volatile boolean throwsOnUnmute, throwsOnMute, throwsOnCapturing;
        /** It mutes, and then throws: the call failed, the stream muted all the same. */
        volatile boolean mutesThenThrows;
        /** Reads that answer before reading throws: -1, never. */
        volatile int readsLeft = -1;
        volatile Boolean capturing = false;
        /** Run once, as an unmute begins: another server doing something meanwhile. */
        final AtomicReference<Runnable> onUnmute = new AtomicReference<>();
        final List<String> log = new ArrayList<>();

        @Override
        public boolean muted() {
            synchronized (this) {
                if (readsLeft == 0) throw new IllegalStateException("the audio service has gone");
                if (readsLeft > 0) readsLeft--;
                return muted;
            }
        }

        @Override
        public void mute() {
            synchronized (this) {
                if (throwsOnMute) throw new SecurityException("refused");
                if (!keepsSound) muted = true;
                if (mutesThenThrows) throw new IllegalStateException("muted, then the audio service went");
            }
        }

        @Override
        public void unmute() {
            Runnable meanwhile = onUnmute.getAndSet(null);
            if (meanwhile != null) meanwhile.run();
            synchronized (this) {
                if (throwsOnUnmute) throw new SecurityException("refused");
                if (!keepsMute) muted = false;
            }
        }

        @Override
        public Boolean capturing() {
            if (throwsOnCapturing) throw new IllegalStateException("no audio policy");
            return capturing;
        }

        @Override
        public synchronized void log(String line) {
            log.add(line);
        }

        synchronized boolean said(String part) {
            for (String l : log) if (l.contains(part)) return true;
            return false;
        }
    }

    /** Another process holding the servers' lock for a while: `Holder <lock file> <ms>`. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            try (RandomAccessFile f = new RandomAccessFile(args[0], "rw"); FileLock l = f.getChannel().lock()) {
                System.out.println("locked");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
        }
    }

    static final long HOLD = 400;
    static final BooleanSupplier OPEN = () -> true;
    static int passed, failed;
    static File dir;

    static void check(String name, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok || detail == null ? "" : " — " + detail));
    }

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    static File mark(String name) {
        return new File(dir, name);
    }

    static String owner(File mark) throws Exception {
        return mark.exists() ? new String(Files.readAllBytes(mark.toPath()), StandardCharsets.UTF_8) : null;
    }

    static final int[] told = new int[1];

    static AppMute server(Stream s, File mark, String owner) {
        AppMute m = new AppMute(s, mark, owner, HOLD, () -> told[0]++);
        m.recover();
        return m;
    }

    /** Waits up to `ms` for `cond`. */
    static boolean within(long ms, BooleanSupplier cond) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    interface Section {
        void run() throws Exception;
    }

    /** One section: anything it throws is a failure of its own, and the next section still runs. */
    static void section(String name, Section body) {
        System.out.println(name);
        try {
            body.run();
        } catch (Throwable t) {
            check(name + ": it ran to its end", false, String.valueOf(t));
        }
    }

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("oadx-mute-").toFile();
        try {
            section("on and off", MuteRules::onAndOff);
            section("off, held for the computer's capture", MuteRules::theHold);
            section("ended by itself", MuteRules::endedByItself);
            section("an unmute that fails", MuteRules::failures);
            section("a phone whose reads throw", MuteRules::readsThatThrow);
            section("a hold whose read throws", MuteRules::aHoldWhoseReadThrows);
            section("a mute that took, and threw", MuteRules::aMuteThatTookAndThrew);
            section("a mark lost, then a newer server's", MuteRules::aLostMarkThenANewerOne);
            section("no turn among the servers", MuteRules::noTurn);
            section("a server replaced", MuteRules::twoServers);
            section("another process holding the turn", MuteRules::anotherProcess);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
        System.out.println(passed + "/" + (passed + failed) + " passed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void onAndOff() throws Exception {
        Stream s = new Stream();
        File mk = mark("a");
        AppMute a = server(s, mk, "A");
        check("refused with no window open, and nothing changed", "no window is open".equals(a.mute(() -> false)) && !s.muted && !a.on() && !mk.exists());
        check("on: yes once the stream is muted", a.mute(OPEN) == null && s.muted && a.on());
        check("its mark names this server", "A".equals(owner(mk)), owner(mk));
        check("on again: nothing more", a.mute(OPEN) == null && s.muted && a.on());
        check("off: the stream plays again, the mark gone", a.unmute(false) == null && !s.muted && !a.on() && !mk.exists());
        check("off again: nothing to do", a.unmute(false) == null && !s.muted);

        Stream refusing = new Stream();
        refusing.keepsSound = true;
        AppMute r = server(refusing, mark("r"), "R");
        check("a mute that does not take is refused, the Mute off and nothing marked",
                "the phone kept its media sound on".equals(r.mute(OPEN)) && !r.on() && !mark("r").exists());
        Stream throwing = new Stream();
        throwing.throwsOnMute = true;
        AppMute t = server(throwing, mark("t"), "T");
        String said = t.mute(OPEN);
        check("a mute that throws is refused, and nothing changed", said != null && !t.on() && !throwing.muted && !mark("t").exists(), said);

        Stream own = new Stream();
        own.muted = true; // muted on the phone already
        AppMute o = server(own, mark("o"), "O");
        check("on over the phone's own mute: no mark, it is not this Mute's", o.mute(OPEN) == null && o.on() && !mark("o").exists());
        check("asked off, it plays anyway: the person asked for sound", o.unmute(false) == null && !own.muted);
    }

    static void theHold() throws Exception {
        Stream s = new Stream();
        File mk = mark("h");
        AppMute a = server(s, mk, "A");
        a.mute(OPEN);
        s.capturing = false;
        check("held: the Mute is off, the stream still silent, the hold marked", a.unmute(true) == null && !a.on() && s.muted && "A".equals(owner(mk)));
        Thread.sleep(150);
        check("still held while no capture runs", s.muted);
        s.capturing = true;
        check("plays once the capture runs", within(300, () -> !s.muted) && !mk.exists() && s.said("capture began"));

        a.mute(OPEN);
        s.capturing = false;
        long t0 = System.currentTimeMillis();
        a.unmute(true);
        boolean played = within(HOLD + 500, () -> !s.muted);
        long took = System.currentTimeMillis() - t0;
        check("no capture: held for the whole hold, then it plays", played && took >= HOLD - 20 && s.said("no capture began"), took + " ms");

        a.mute(OPEN);
        s.capturing = null;
        t0 = System.currentTimeMillis();
        a.unmute(true);
        Thread.sleep(HOLD / 2);
        boolean heldHalf = s.muted;
        played = within(HOLD + 500, () -> !s.muted);
        took = System.currentTimeMillis() - t0;
        check("a phone that cannot say: held the whole hold too, not let go on a guess",
                heldHalf && played && took >= HOLD - 20 && s.said("could not say"), took + " ms");

        a.mute(OPEN);
        s.capturing = false;
        a.unmute(true);
        check("on again during a hold", a.mute(OPEN) == null && a.on() && s.muted);
        s.capturing = true;
        Thread.sleep(HOLD + 200);
        check("the hold's wait gives up: the stream stays muted", s.muted && a.on());
        check("and the mute is this Mute's, marked", "A".equals(owner(mk)));
        a.unmute(false);
    }

    static void endedByItself() throws Exception {
        Stream s = new Stream();
        File mk = mark("l");
        AppMute a = server(s, mk, "A");
        a.mute(OPEN);
        check("lifted: the stream plays, the mark gone", a.lift("the last window has closed") && !s.muted && !a.on() && !mk.exists());
        check("lifted again: nothing to lift", !a.lift("again"));

        a.mute(OPEN);
        s.capturing = false;
        a.unmute(true);
        check("lifted during a hold: plays at once", a.lift("the server is stopping") && !s.muted && !mk.exists());

        Stream own = new Stream();
        own.muted = true;
        AppMute o = server(own, mark("lo"), "O");
        o.mute(OPEN);
        check("lifted over the phone's own mute: it stays muted, as the phone had it",
                o.lift("the last window has closed") && own.muted && own.said("as the phone had it"));
    }

    static void failures() throws Exception {
        Stream s = new Stream();
        File mk = mark("f");
        AppMute a = server(s, mk, "A");
        a.mute(OPEN);
        s.keepsMute = true;
        String said = a.unmute(false);
        check("asked off, not taken: refused, and the Mute stays on", "the phone's media is not playing again".equals(said) && a.on() && s.muted, said);
        check("its mark stays", "A".equals(owner(mk)));
        s.throwsOnUnmute = true;
        said = a.unmute(false);
        check("one that throws: refused too, still on and marked", said != null && a.on() && "A".equals(owner(mk)), said);
        s.throwsOnUnmute = false;
        check("lifted, not taken: the Mute ends, the stream stays marked", a.lift("the server is stopping") && !a.on() && s.muted && "A".equals(owner(mk)));
        AppMute b = server(s, mk, "B");
        check("the next server cannot undo it either: it stays marked", s.muted && "A".equals(owner(mk)) && s.said("stays marked"));
        s.keepsMute = false;
        AppMute c = server(s, mk, "C");
        check("the one after it can: the stream plays, the mark gone", !s.muted && !mk.exists());

        Stream h = new Stream();
        File hk = mark("fh");
        AppMute d = server(h, hk, "D");
        d.mute(OPEN);
        h.capturing = false;
        int before = told[0];
        d.unmute(true);
        h.keepsMute = true;
        h.capturing = true;
        check("a hold whose unmute does not take: the Mute is on again", within(HOLD + 300, () -> told[0] > before) && d.on() && h.muted);
        check("the computer is told, and the mark stays", told[0] == before + 1 && "D".equals(owner(hk)));
        h.keepsMute = false;
        d.unmute(false);
        check("off when it takes", !h.muted && !d.on() && !hk.exists());
    }

    static void readsThatThrow() throws Exception {
        Stream s = new Stream();
        File mk = mark("rt");
        AppMute a = server(s, mk, "A");
        s.readsLeft = 0;
        String said = a.mute(OPEN);
        check("the state before not read: refused, nothing done", said != null && !a.on() && !s.muted && !mk.exists(), said);
        Stream own = new Stream();
        own.muted = true; // the phone's own mute, which an unread state must not make the Mute's
        AppMute o = server(own, mark("rto"), "O");
        own.readsLeft = 0;
        said = o.mute(OPEN);
        own.readsLeft = -1;
        check("…over the phone's own mute: refused, and an end afterwards leaves it muted",
                said != null && !o.lift("the last window has closed") && own.muted && !mark("rto").exists(), said);

        s.readsLeft = 1; // the read before muting answers, the one after throws
        said = a.mute(OPEN);
        check("muted, but not read back: refused, the Mute off — not taken as muted", said != null && !a.on(), said);
        check("…what may have taken undone, and marked till the phone says it plays", !s.muted && "A".equals(owner(mk)) && s.said("may have left"));
        s.readsLeft = -1;
        a.lift("the last window has closed");
        check("an end undoes what is still marked, once the phone reads it playing", !s.muted && !mk.exists());

        a.mute(OPEN);
        s.readsLeft = 0;
        said = a.unmute(false);
        check("unmuted, but not read back: refused, the Mute still on, still marked", said != null && a.on() && "A".equals(owner(mk)), said);
        s.readsLeft = -1;
        check("then off when it reads", a.unmute(false) == null && !s.muted && !a.on() && !mk.exists());

        a.mute(OPEN);
        s.readsLeft = 0;
        boolean lifted = a.lift("the last window has closed");
        check("a lift whose read throws: no exception, the Mute over, the mark kept", lifted && !a.on() && "A".equals(owner(mk)));
        AppMute b = server(s, mk, "B");
        check("a start whose read throws: no exception, the mark kept", "A".equals(owner(mk)) && s.said("is not undone"));
        s.readsLeft = -1;
        AppMute c = server(s, mk, "C");
        check("the next start, reading again, undoes it", !s.muted && !mk.exists());

        Stream p = new Stream();
        AppMute q = server(p, mark("rp"), "Q");
        q.mute(OPEN);
        p.throwsOnCapturing = true;
        long t0 = System.currentTimeMillis();
        q.unmute(true);
        boolean played = within(HOLD + 500, () -> !p.muted);
        check("an audio policy that throws is one that cannot say: held the whole hold", played && System.currentTimeMillis() - t0 >= HOLD - 20 && p.said("could not say"));
    }

    /** The third audit's: a mute that took and then threw lost its mark, and the next server could not undo it. */
    static void aMuteThatTookAndThrew() throws Exception {
        Stream s = new Stream();
        File mk = mark("mt");
        AppMute a = server(s, mk, "A");
        s.mutesThenThrows = true;
        String said = a.mute(OPEN);
        check("refused, and what it did undone at once: the media plays, no mark", said != null && !a.on() && !s.muted && !mk.exists(), said);
        s.keepsMute = true; // …and this time the undoing does not take either
        said = a.mute(OPEN);
        check("not undone: refused, the media muted, and marked for whoever can", said != null && !a.on() && s.muted && "A".equals(owner(mk)), said);
        s.mutesThenThrows = false;
        a.lift("the last window has closed");
        check("an end that cannot undo it either keeps the mark", s.muted && "A".equals(owner(mk)));
        s.keepsMute = false;
        AppMute b = server(s, mk, "B");
        check("the next server undoes it", !s.muted && !mk.exists() && s.said("left muted by server A"));
    }

    /**
     * The third audit's: a mark this server could not write once made every
     * later mark its own — a newer server's included — once writing worked again.
     */
    static void aLostMarkThenANewerOne() throws Exception {
        Stream s = new Stream();
        File mk = mark("lost");
        AppMute a = server(s, mk, "A");
        mk.mkdir(); // the mark cannot be written: a folder where it goes
        a.mute(OPEN);
        check("its mark lost, the mute is still its own", a.on() && s.muted && s.said("could not be written"));
        mk.delete(); // writing works again
        s.muted = false; // the phone's volume turned up by hand
        AppMute b = server(s, mk, "B");
        b.mute(OPEN);
        check("a newer server mutes, and marks it", b.on() && s.muted && "B".equals(owner(mk)));
        a.lift("the last window has closed");
        check("the earlier one, ending, leaves the newer mark's mute alone", s.muted && "B".equals(owner(mk)) && s.said("another server's to unmute now"));
        b.unmute(false);
        check("which its own server undoes", !s.muted && !mk.exists());
    }

    /** The third audit's: with no turn among the servers to be had, the change was made anyway. */
    static void noTurn() throws Exception {
        Stream s = new Stream();
        File mk = mark("nt");
        File turn = new File(mk.getPath() + ".lock");
        AppMute a = server(s, mk, "A");
        a.mute(OPEN);
        turn.delete();
        turn.mkdir(); // the lock cannot be had: a folder where it goes
        String said = a.unmute(false);
        check("no turn: an unmute is refused, the Mute stays on, the media muted, the mark kept",
                said != null && a.on() && s.muted && "A".equals(owner(mk)) && s.said(AppMute.NO_TURN), said);
        check("…and an unmute held for the capture too", a.unmute(true) != null && a.on() && s.muted);
        check("no turn: an end leaves the media muted and marked", a.lift("the server is stopping") && s.muted && "A".equals(owner(mk)));
        AppMute b = server(s, mk, "B");
        check("no turn: a start leaves the mark be", s.muted && "A".equals(owner(mk)) && s.said("not looked for"));
        turn.delete(); // a turn again
        AppMute c = server(s, mk, "C");
        check("with a turn again, the next start undoes it", !s.muted && !mk.exists());
        turn.delete(); // the file that start made, and a folder in its place again
        turn.mkdir();
        said = c.mute(OPEN);
        check("no turn: a mute is refused, nothing done", AppMute.NO_TURN.equals(said) && !c.on() && !s.muted && !mk.exists(), said);
        turn.delete();
    }

    /** The defect: in a hold's wait, a read that threw ended the wait's thread with the Mute said off. */
    static void aHoldWhoseReadThrows() throws Exception {
        Stream s = new Stream();
        File mk = mark("ht");
        AppMute a = server(s, mk, "A");
        a.mute(OPEN);
        s.capturing = false;
        int before = told[0];
        a.unmute(true);
        s.readsLeft = 0;
        s.capturing = true;
        boolean back = within(HOLD + 400, () -> told[0] > before);
        check("the Mute on again, and the computer told", back && a.on() && told[0] == before + 1, "told " + (told[0] - before) + ", on " + a.on());
        check("…and the mark stays for the next server", "A".equals(owner(mk)));
        s.readsLeft = -1;
        check("then off when it reads", a.unmute(false) == null && !s.muted && !a.on() && !mk.exists());
    }

    static void twoServers() throws Exception {
        Stream s = new Stream();
        File mk = mark("two");
        AppMute a = server(s, mk, "A");
        a.mute(OPEN);
        AppMute b = server(s, mk, "B");
        check("the next server undoes the earlier one's mute as it starts", !s.muted && !mk.exists() && s.said("left muted by server A"));
        b.mute(OPEN);
        check("and mutes it as its own", s.muted && "B".equals(owner(mk)));
        check("the earlier one's lift leaves the later one's mute alone",
                a.lift("the last window has closed") && s.muted && "B".equals(owner(mk)) && s.said("another server's to unmute now"));
        b.unmute(false);
        check("the later one unmutes it", !s.muted && !mk.exists());

        a = server(s, mk, "A2");
        a.mute(OPEN);
        s.capturing = false;
        a.unmute(true);
        b = server(s, mk, "B2");
        b.mute(OPEN);
        s.capturing = true;
        Thread.sleep(HOLD + 200);
        check("an earlier server's hold, ending, leaves the later one's mute alone", s.muted && b.on() && "B2".equals(owner(mk)));
        b.lift("the last window has closed");
        check("the later one's lift unmutes", !s.muted && !mk.exists());

        // The defect: a newer server starting and muting between the older one's
        // reading the mark and its unmuting. Now the newer one waits its turn.
        AppMute old = server(s, mk, "A3");
        old.mute(OPEN);
        AtomicReference<AppMute> newer = new AtomicReference<>();
        Thread meanwhile = new Thread(() -> {
            AppMute n = server(s, mk, "B3");
            n.mute(OPEN);
            newer.set(n);
        });
        s.onUnmute.set(() -> {
            meanwhile.start();
            try {
                Thread.sleep(300); // time for the newer one to get in, if it could
            } catch (InterruptedException ignored) {
            }
        });
        old.lift("the last window has closed");
        meanwhile.join(3000);
        AppMute n = newer.get();
        check("a server muting while an older one unmutes waits its turn: its mute stands",
                n != null && n.on() && s.muted && "B3".equals(owner(mk)), "newer " + (n == null ? "none" : "on " + n.on()) + ", muted " + s.muted + ", mark " + owner(mk));
        if (n != null) n.unmute(false);

        File lost = mark("cannot");
        Stream ls = new Stream();
        AppMute l = server(ls, lost, "L");
        lost.mkdir(); // a folder where the mark goes
        l.mute(OPEN);
        check("a mark that cannot be written is said", ls.said("could not be written"));
        lost.delete();
        check("and the mute is still undone by its own server, no other's mark there", l.lift("the last window has closed") && !ls.muted);
    }

    /** The servers are processes: another one holding their lock is waited for, not raced. */
    static void anotherProcess() throws Exception {
        Stream s = new Stream();
        File mk = mark("proc");
        AppMute a = server(s, mk, "A");
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        Process other = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                MuteRules.Holder.class.getName(), mk.getPath() + ".lock", "700").redirectErrorStream(true).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(other.getInputStream(), StandardCharsets.UTF_8));
        String first = out.readLine();
        long t0 = System.currentTimeMillis();
        String said = a.mute(OPEN);
        long waited = System.currentTimeMillis() - t0;
        other.waitFor();
        check("a mute waits while another process has the turn", "locked".equals(first) && said == null && s.muted && waited >= 500, first + ", waited " + waited + " ms");
        a.unmute(false);
    }
}
