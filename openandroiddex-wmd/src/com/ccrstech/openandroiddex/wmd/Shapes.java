package com.ccrstech.openandroiddex.wmd;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the pointer should look like over a display, as the launcher last said — SHAPE and
 * SHAPEWAIT. Pure Java, so test/window-resize-test.mjs runs it on a computer.
 *
 * <p>The computer draws its own cursor over the desktop's video, and only the launcher
 * knows when that cursor is over one of its windows' resize edges (ResizeEdges). This is
 * the hand-over: the launcher sets a shape here as it changes, and the PC waits for the
 * next one over its forward of this port, then turns its cursor into that.
 *
 * <p>A state, not a queue. Only the newest shape matters, so a PC that was busy for a
 * moment skips the ones in between rather than replaying a pointer's whole journey along
 * a window's border. Every change gets a new number, so "has it changed since I looked?"
 * is a comparison.
 *
 * <p>A shape lasts only as long as the connection that set it: a launcher that dies, or is
 * reinstalled, while the pointer is over an edge would otherwise leave the computer's
 * cursor a double arrow over the whole desktop until the next edge.
 */
final class Shapes {

    private Shapes() {
    }

    /** CSS cursor names, as the PC hands them on (scrcpy's pointer_shape.c, wm.rs). */
    static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "default", "ew-resize", "ns-resize", "nwse-resize", "nesw-resize",
            "move", "pointer", "text", "crosshair", "not-allowed", "wait", "progress"));

    /** The longest one SHAPEWAIT may hold the line: under the PC's read timeout. */
    static final long WAIT_MAX_MS = 1200L;

    private static final class State {
        long seq;
        String name = "default";
        Object owner;
    }

    private static final Map<Integer, State> BY_DISPLAY = new HashMap<>();
    /** One counter for every display, so a number never means two things. */
    private static long counter;

    /** The display's shape is {@code name} now, for as long as {@code owner} lasts. */
    static synchronized long set(int display, String name, Object owner) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("no such shape " + name);
        State s = BY_DISPLAY.get(display);
        if (s == null) {
            s = new State();
            BY_DISPLAY.put(display, s);
        }
        s.owner = "default".equals(name) ? null : owner;
        if (!name.equals(s.name)) {
            s.name = name;
            s.seq = ++counter;
            Shapes.class.notifyAll();
        }
        return s.seq;
    }

    /**
     * The display's shape once it is no longer number {@code seen}, or after {@code waitMs}
     * either way: {@code {seq, name}}. A {@code seen} that is not the current number —
     * -1, the first time — answers at once.
     */
    static synchronized String[] await(int display, long seen, long waitMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, Math.min(waitMs, WAIT_MAX_MS));
        while (true) {
            State s = BY_DISPLAY.get(display);
            long seq = s == null ? 0L : s.seq;
            if (seq != seen) return new String[]{Long.toString(seq), s == null ? "default" : s.name};
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return new String[]{Long.toString(seq), s == null ? "default" : s.name};
            Shapes.class.wait(left);
        }
    }

    /** {@code owner}'s connection has gone: whatever it set goes back to the arrow. */
    static synchronized void dropOwnedBy(Object owner) {
        if (owner == null) return;
        boolean changed = false;
        for (State s : BY_DISPLAY.values()) {
            if (s.owner != owner) continue;
            s.owner = null;
            if (!"default".equals(s.name)) {
                s.name = "default";
                s.seq = ++counter;
                changed = true;
            }
        }
        if (changed) Shapes.class.notifyAll();
    }

    /** For the tests: start again. */
    static synchronized void reset() {
        BY_DISPLAY.clear();
        counter = 0L;
    }
}
