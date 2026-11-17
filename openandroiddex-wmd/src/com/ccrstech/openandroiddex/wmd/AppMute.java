package com.ccrstech.openandroiddex.wmd;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The windows' Mute on the phone (doc/apps-on-pc.md, "Mute"), kept apart from
 * the platform calls it is carried out with ({@link Phone}): {@link AppAudio}
 * gives it the phone's music stream and the audio policy's word on the
 * computer's capture; test/apps-mute-test.mjs gives it stand-ins and runs it
 * on a computer, a server that fails or is replaced included.
 *
 * <p>What it keeps to:
 * <ul>
 * <li>Nothing is taken as done that the phone has not said is: on is yes only
 *     once the phone reads its media muted, off only once it reads it playing
 *     (or holds it for the capture). What it will not read back is not done.
 * <li>Off, where the computer's capture is to take the sound away from the
 *     phone's speaker: the media held silent until that capture is running, so
 *     nothing plays on the speaker in between — for {@link #HOLD_MS} at most
 *     (that long, too, where the phone cannot tell), and then it plays anyway
 *     rather than stay silent for a capture that is not coming.
 * <li>Ended by itself — its last window gone, the server stopping — it undoes
 *     what it muted, and anything of its own still marked, while the mark is
 *     still its own: a server started after it owns the phone's sound from
 *     then on. A mute the phone had of its own, or one whose state the phone
 *     would not say when it came on, is never undone by it.
 * <li>Reading the mark and changing the stream are one turn against every
 *     other server ({@link #exclusive}): one started meanwhile waits. With no
 *     turn to be had, nothing is changed.
 * <li>The mark is there whenever the stream may be muted by this server —
 *     written before the mute, gone only once the phone reads it playing — so
 *     whatever is not undone is undone by the next server.
 * <li>Nothing the phone throws gets out: a call that fails is a refusal, and
 *     a hold that fails leaves the Mute on, said to the computer.
 * </ul>
 */
final class AppMute {

    /** What the Mute is carried out with. */
    interface Phone {
        /** The phone's music stream is muted. */
        boolean muted();

        /** Mute it: may throw, or quietly not take. */
        void mute();

        /** Unmute it: may throw, or quietly not take. */
        void unmute();

        /** The computer's capture has the phone's output now; null if the phone will not say. */
        Boolean capturing();

        void log(String line);
    }

    /** How long an unmute holds the media back for the computer's capture to begin. */
    static final long HOLD_MS = 6000;
    private static final long POLL_MS = 50;
    /** Why a change was not made: no turn among the servers. */
    static final String NO_TURN = "no turn among the phone's servers to change its sound";

    /**
     * Every AppMute of this process, one at a time in {@link #exclusive}: a
     * file lock is the process's, not a thread's, and a second in the same
     * process would be refused it rather than wait.
     */
    private static final Object PROCESS = new Object();

    private final Phone phone;
    private final File mark;
    /** Beside the mark, and never deleted: what the servers take turns on. */
    private final File lockFile;
    /** This server, as the mark names it. */
    private final String owner;
    private final long holdMs;
    /** The Mute changed by itself: the computer is to hear it (a hold that ended with the media still muted). */
    private final Runnable changed;

    private final Object lock = new Object();
    /** The computer's Mute is on. */
    private boolean on;
    /** The media was muted on the phone already when the Mute came on: not the Mute's to undo when it ends by itself. */
    private boolean before;
    /** Asked off, and the media held silent until the computer's capture is running. */
    private boolean holding;
    /** Counts what was asked, so a hold's wait never acts after something newer. */
    private int generation;
    /**
     * This server's mark could not be written, and none is there: the mute is
     * its own all the same. A mark that is there — another server's — says
     * otherwise, whatever this says.
     */
    private boolean markLost;

    AppMute(Phone phone, File mark, String owner, long holdMs, Runnable changed) {
        this.phone = phone;
        this.mark = mark;
        this.lockFile = new File(mark.getPath() + ".lock");
        this.owner = owner;
        this.holdMs = holdMs;
        this.changed = changed;
    }

    /** The computer's Mute is on, as the computer is told. */
    boolean on() {
        synchronized (lock) {
            return on;
        }
    }

    /**
     * As this server starts: a mute an earlier server left marked — it was
     * killed while it held, or it could not undo it, or it is still running
     * for a computer that has gone — undone now. This server owns the phone's
     * sound from here: the earlier one, finding the mark gone, leaves it be.
     * One that cannot be undone, or not confirmed, stays marked.
     */
    void recover() {
        synchronized (lock) {
            try {
                boolean turn = exclusive(() -> {
                    String was = readMark();
                    if (was == null) return true;
                    Boolean muted = read();
                    boolean undid = false;
                    if (Boolean.TRUE.equals(muted) && call(phone::unmute, "undoing the mute " + name(was) + " left")) {
                        muted = read();
                        undid = true;
                    }
                    if (!Boolean.FALSE.equals(muted)) {
                        phone.log("sound: the mute " + name(was) + " left is not undone"
                                + (muted == null ? " (the phone will not say)" : "") + "; it stays marked");
                        return true;
                    }
                    deleteMark();
                    phone.log(undid ? "sound: the phone's media, left muted by " + name(was) + ", plays again"
                            : "sound: the mark " + name(was) + " left goes: the media plays already");
                    return true;
                }, false);
                if (!turn) phone.log("sound: a mute left behind is not looked for, with no turn among the servers; a mark stays");
            } catch (Throwable t) {
                phone.log("sound: looking for a mute left behind: " + t);
            }
        }
    }

    /**
     * On: null once the phone reads its media muted, else why not; asked
     * again, nothing more. Only while {@code windowsOpen}, asked under the
     * lock {@link #lift} takes: a window ending as the Mute comes on lifts it
     * after, or it is refused.
     */
    String mute(BooleanSupplier windowsOpen) {
        synchronized (lock) {
            if (on) return null;
            if (!windowsOpen.getAsBoolean()) return "no window is open";
            return exclusive(() -> {
                // Held for an unmute, the media is silent by this Mute's doing, not the phone's.
                Boolean was = holding ? Boolean.FALSE : read();
                // Whether the phone had a mute of its own decides what ending this one may undo: not known, not done.
                if (was == null) return "the phone will not say whether its media is muted";
                boolean own = !was;
                // Marked before it is muted: killed in between, the next server still undoes it.
                if (own) writeMark();
                boolean called = call(phone::mute, "muting the phone's media");
                Boolean now = read();
                if (called && Boolean.TRUE.equals(now)) {
                    generation++;
                    holding = false;
                    before = was;
                    on = true;
                    phone.log("sound: the phone's media muted for the computer" + (before ? " (it was muted on the phone already)" : ""));
                    return null;
                }
                // Not confirmed: what may have taken is undone — unless a hold keeps the media silent anyway —
                // and the mark goes only once the phone reads it playing.
                if (own && !holding) settle("a mute that was not confirmed");
                if (!called) return "the phone refused to mute its media";
                return now == null ? "the phone will not say whether its media muted" : "the phone kept its media sound on";
            }, NO_TURN);
        }
    }

    /**
     * Off: null once the phone reads the media playing, or holds it for the
     * capture ({@code afterCapture}); else why not, and the Mute stays on.
     * Asked off, the media plays even if it was muted on the phone before: the
     * person has asked for sound.
     */
    String unmute(boolean afterCapture) {
        synchronized (lock) {
            if (!on && !holding) return null;
            if (!afterCapture) {
                // Decided now: a hold's wait, if one is waiting, gives up.
                generation++;
                holding = false;
                if (!release("the computer asked", false)) {
                    // Not done, or not confirmed: the Mute stays on, as the answer's word of the sound says.
                    on = true;
                    return "the phone's media is not playing again";
                }
                on = false;
                return null;
            }
            // The hold is this server's to end: marked, so a server killed meanwhile has the next one end it.
            if (!exclusive(() -> {
                writeMark();
                return true;
            }, false)) {
                return NO_TURN;
            }
            int asked = ++generation;
            on = false;
            holding = true;
            Thread t = new Thread(() -> hold(asked), "apps-unmute");
            t.setDaemon(true);
            t.start();
            return null;
        }
    }

    /**
     * A hold's wait: the media plays once the capture is running, or the time
     * is up. Whatever goes wrong in it, the Mute is not left off with the
     * media silent and nobody told.
     */
    private void hold(int asked) {
        boolean tell = false;
        try {
            long t0 = now();
            boolean began = false;
            boolean unsure = false;
            while (now() - t0 < holdMs) {
                synchronized (lock) {
                    if (generation != asked || !holding) return;
                }
                Boolean capturing = capturing();
                if (Boolean.TRUE.equals(capturing)) {
                    began = true;
                    break;
                }
                unsure |= capturing == null;
                sleep(POLL_MS);
            }
            long waited = now() - t0;
            synchronized (lock) {
                if (generation != asked || !holding) return;
                holding = false;
                String why = began
                        ? "the computer's capture began " + waited + " ms after the unmute"
                        : unsure
                                ? "the phone could not say when the capture begins; held " + waited + " ms"
                                : "no capture began in " + waited + " ms; the phone plays the sound itself";
                // Not confirmed after all: the Mute is on again, and the computer hears it.
                if (!release(why, true)) {
                    on = true;
                    tell = true;
                }
            }
        } catch (Throwable t) {
            synchronized (lock) {
                if (generation == asked) {
                    holding = false;
                    on = true;
                    tell = true;
                }
            }
            phone.log("sound: holding the media for the capture failed (" + t + "); the Mute is on again");
        }
        if (tell) {
            try {
                changed.run();
            } catch (Throwable t) {
                phone.log("sound: the computer could not be told the Mute is on again (" + t + ")");
            }
        }
    }

    /**
     * The Mute ends without the computer asking: the phone's last window has
     * closed, or this server is stopping. The media plays again — this
     * server's mute, and anything of its own still marked (a mute or an unmute
     * the phone would not confirm) — unless it was the phone's own mute, which
     * stays as the phone had it, or a later server owns the phone's sound now.
     * True if there was a Mute to end. Never throws: it runs as windows end
     * and as the server stops.
     */
    boolean lift(String why) {
        synchronized (lock) {
            boolean had = on || holding;
            boolean phonesOwn = on && !holding && before;
            generation++;
            on = false;
            holding = false;
            try {
                if (phonesOwn) {
                    phone.log("sound: the computer's mute ends (" + why + "); the phone's media stays muted, as the phone had it");
                } else {
                    // Not confirmed, it stays marked: the next server undoes it.
                    release(why, true);
                }
            } catch (Throwable t) {
                phone.log("sound: ending the mute (" + why + "): " + t);
            }
            return had;
        }
    }

    /**
     * The media playing again: true once the phone reads it so — or, with
     * {@code ownOnly}, once there is nothing of this server's to undo. The mark
     * goes only then; one that did not take, or that the phone will not read
     * back, keeps it. One turn against every other server; with no turn, not
     * done.
     */
    private boolean release(String why, boolean ownOnly) {
        return exclusive(() -> {
            String m = readMark();
            boolean mine = mine(m);
            if (ownOnly && !mine) {
                if (m != null) phone.log("sound: the phone's media is another server's to unmute now (" + why + ")");
                return true;
            }
            Boolean now = call(phone::unmute, "unmuting the phone's media (" + why + ")") ? read() : Boolean.TRUE;
            if (!Boolean.FALSE.equals(now)) {
                phone.log("sound: the phone's media is not playing again"
                        + (now == null ? " as far as it will say" : "") + " (" + why + ")"
                        + (mine ? "; it stays marked" : ""));
                return false;
            }
            if (mine) dropMark();
            phone.log("sound: the phone's media plays again (" + why + ")");
            return true;
        }, false);
    }

    /**
     * A mute of this server's that was not confirmed, with the turn held:
     * undone if the phone says it took or will not say, and its mark gone only
     * once the phone reads the media playing.
     */
    private void settle(String why) {
        Boolean now = read();
        if (!Boolean.FALSE.equals(now) && call(phone::unmute, "undoing " + why)) now = read();
        if (Boolean.FALSE.equals(now)) {
            dropMark();
        } else {
            phone.log("sound: " + why + " may have left the phone's media muted; it stays marked");
        }
    }

    /**
     * {@code body} with no other server between its reading the mark and
     * changing the stream: their file lock beside the mark, and this process's
     * own turn. With no lock to be had, {@code body} does not run, and
     * {@code without} is the answer: nothing changed.
     */
    private <T> T exclusive(Supplier<T> body, T without) {
        synchronized (PROCESS) {
            RandomAccessFile file = null;
            FileLock held;
            try {
                file = new RandomAccessFile(lockFile, "rw");
                held = file.getChannel().lock();
            } catch (Throwable t) {
                phone.log("sound: " + NO_TURN + " (" + lockFile + ": " + t + "); nothing changed");
                close(file);
                return without;
            }
            try {
                return body.get();
            } finally {
                try {
                    held.release();
                } catch (Throwable ignored) {
                    // closing the file lets it go too
                }
                close(file);
            }
        }
    }

    /** The stream's state, or null where the phone will not say. */
    private Boolean read() {
        try {
            return phone.muted();
        } catch (Throwable t) {
            phone.log("sound: the phone would not say whether its media is muted (" + t + ")");
            return null;
        }
    }

    /** One call to the phone: false, and said, if it throws. */
    private boolean call(Runnable op, String what) {
        try {
            op.run();
            return true;
        } catch (Throwable t) {
            phone.log("sound: " + what + ": " + t);
            return false;
        }
    }

    /** The computer's capture is running: null where the phone cannot say, or throws. */
    private Boolean capturing() {
        try {
            return phone.capturing();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The mark {@code m} (as read) makes the mute this server's: it names this
     * one — or there is none, and this one's could not be written. Another
     * server's mark is another server's, whatever went wrong here before.
     */
    private boolean mine(String m) {
        return m == null ? markLost : owner.equals(m);
    }

    /** Whose the marked mute is; null with no mark, "" if it cannot be read. */
    private String readMark() {
        try {
            if (!mark.exists()) return null;
            return new String(Files.readAllBytes(mark.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void writeMark() {
        try {
            Files.write(mark.toPath(), owner.getBytes(StandardCharsets.UTF_8));
            markLost = false;
        } catch (Throwable t) {
            markLost = true;
            // Without it, a server killed while muted leaves the media muted until the phone's volume is turned up.
            phone.log("sound: " + mark + " could not be written (" + t + ")");
        }
    }

    /** This server's mark gone: if it names this one, or none could be written. */
    private void dropMark() {
        if (owner.equals(readMark())) deleteMark();
        markLost = false;
    }

    private void deleteMark() {
        try {
            if (mark.exists() && !mark.delete()) phone.log("sound: " + mark + " could not be deleted");
        } catch (Throwable t) {
            phone.log("sound: " + mark + " could not be deleted (" + t + ")");
        }
        markLost = false;
    }

    private static void close(RandomAccessFile file) {
        try {
            if (file != null) file.close();
        } catch (Throwable ignored) {
            // nothing more to do
        }
    }

    private static String name(String owner) {
        return owner.isEmpty() ? "an earlier server" : "server " + owner;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
