package com.ccrstech.openandroiddex.wmd;

import android.os.IBinder;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The desktop's refresh rate, held on the phone's own panel (doc/refresh-rate.md).
 *
 * <p>The desktop is a virtual display, and a virtual display keeps no time of its own:
 * SurfaceFlinger paces it, and every app on it, off the phone's built-in panel. Lit,
 * that panel runs at its top rate while anything on the desktop moves. Dark — the
 * taskbar's phone-screen tile — SurfaceFlinger puts it at 60 Hz and the desktop goes
 * with it. Measured on the S25 Ultra (Android 17): 120 fps lit, 60 dark.
 *
 * <p>What this holds is SurfaceFlinger's display-mode policy for the panel, which only a
 * caller with ACCESS_SURFACE_FLINGER may set — the shell's authority, this process's:
 * one mode, its rate pinned as both floor and ceiling. Nothing is written to settings.
 * The phone's DisplayManager owns that policy and sends it again whenever its own wishes
 * change (a touch, the heat, an app asking for 60), so a tick puts ours back; and once
 * this process is gone, the next such send ends the hold without anyone undoing it.
 *
 * <p>Ours is told from the phone's by what it says — its default mode, and every range
 * pinned at its rate — never by reading it back after writing it: a policy the phone
 * sent in between would be taken for ours, and written over with an older one when the
 * rate is given back.
 *
 * <p>Two rules keep it from working against the phone. The rate held is never above the
 * top of the policy the phone last sent, where battery saver, heat and Motion smoothness
 * put their limits ({@link RefreshRates#pick}); with every mode above it, nothing is held
 * and the phone's policy stands. And a rate is set while the panel is lit: SurfaceFlinger
 * times a dark panel off the last rate it saw the panel run at, so a rate set in the dark
 * does not land. Going dark waits for a new rate to take ({@link #beforePanelOff}), and a
 * rate changed in the dark lights the panel for that moment ({@link #hold}).
 */
final class Refresh {

    /** How often a held rate is checked against the policy the phone has sent since. */
    private static final long TICK_MS = 200L;

    /** How long a panel stays lit at a newly set rate before it may go dark. */
    private static final long SETTLE_MS = 200L;

    /** How long a dark panel is lit to show a rate changed in the dark, power-up included. */
    private static final long RELIGHT_MS = 400L;

    /** Rates within this of each other are the same rate (120 is listed as 120.00001). */
    private static final float SAME_RATE = 0.01f;

    /** The rate asked for; 0 when nothing is. Everything here is guarded by the class. */
    private static int wanted = 0;
    /** Panels held, or still to be given back after a release that failed. */
    private static final List<Held> HELD = new ArrayList<>();
    private static Thread ticker;
    /** Until when the panel must stay lit for the last rate set to take. */
    private static long settleUntil = 0L;
    /** The last failure said in the log, so a stuck one is said once. */
    private static String failure;

    private Refresh() {
    }

    /** One panel: the policy to give back, and the mode ours pins, if any. */
    private static final class Held {
        final Screen.Panel panel;
        /** The phone's own policy, the latest seen: what goes back on release. */
        Object theirs;
        /** The mode ours pins; -1 while ours is not in place (the phone's ceiling forbids). */
        int modeId = -1;
        /** The rate ours pins. */
        float rate;

        Held(Screen.Panel panel) {
            this.panel = panel;
        }
    }

    /** A rate asked for, or one still to give back. */
    static synchronized boolean isHeld() {
        return wanted > 0 || !HELD.isEmpty();
    }

    /**
     * Hold the phone's panels at the mode nearest {@code hz} — at or below it, within the
     * phone's own ceiling — or, with 0 or less, give the rate back. Returns the rate held
     * in whole hertz: 0 for none, which includes a ceiling below every rate the panel has.
     * Throws when a rate could not be given back; the daemon keeps it to try again.
     *
     * <p>Asking again for the rate already held costs nothing. The PC asks with every
     * arm, which is what picks a hold back up in a daemon started again.
     */
    static int hold(int hz) {
        if (hz <= 0) {
            if (!release()) throw new Refl.WmError("could not give the refresh rate back");
            return 0;
        }
        // The panels are looked up before the lock is taken, never under it: the lookup
        // can take Screen's lock, and Screen.power holds that one while it waits for this.
        List<Screen.Panel> panels = null;
        boolean relight;
        int held;
        while (true) {
            if (panels == null && !isHeld()) panels = builtInPanels();
            synchronized (Refresh.class) {
                if (hz == wanted && !HELD.isEmpty()) return heldLocked();
                if (HELD.isEmpty() && panels == null) continue;   // released meanwhile
                int before = heldLocked();
                wanted = hz;
                try {
                    if (HELD.isEmpty()) {
                        for (Screen.Panel p : panels) HELD.add(new Held(p));
                    }
                    for (Held h : HELD) applyLocked(h);
                } catch (RuntimeException e) {
                    releaseLocked();
                    throw e;
                }
                startTickerLocked();
                held = heldLocked();
                relight = held > 0 && held != before && Screen.isOff();
                if (held != before) {
                    log(held > 0 ? "refresh: holding " + held + " Hz (asked for " + hz + ")"
                            : "refresh: the phone's ceiling is below every rate — holding none");
                }
                break;
            }
        }
        if (relight) relight();
        return held;
    }

    /**
     * Give the rate back: the phone's own policy goes back on every panel where ours is
     * still in place. False when a panel's could not be put back — it stays listed, so
     * the next release tries again (the watchdog's, each second, once the desktop has
     * gone). Never throws: the watchdog and the cleanup call this.
     */
    static synchronized boolean release() {
        return releaseLocked();
    }

    /** "held rate…": the rate held (0 for none), then the panel's rates at its size, fastest first. */
    static String status() {
        Screen.Panel first = null;
        try {
            if (!isHeld()) first = builtInPanels().get(0);   // outside the lock: see hold
        } catch (Throwable ignored) {
        }
        synchronized (Refresh.class) {
            StringBuilder s = new StringBuilder().append(heldLocked());
            try {
                Screen.Panel p = HELD.isEmpty() ? first : HELD.get(0).panel;
                Object info = dynamicInfo(p);
                List<RefreshRates.Mode> modes = modes(info);
                Object policy = HELD.isEmpty() ? specs(p.token) : HELD.get(0).theirs;
                for (int hz : RefreshRates.offered(modes, sizeOf(policy, info, modes))) {
                    s.append(' ').append(hz);
                }
            } catch (Throwable t) {
                // The rates unread: the held rate alone is still the answer.
            }
            return s.toString();
        }
    }

    /**
     * Called by {@link Screen} as it darkens the panel: a held rate is put back in place,
     * and given the time to take while the panel can still show it.
     */
    static void beforePanelOff() {
        long wait;
        synchronized (Refresh.class) {
            if (wanted <= 0) return;
            for (Held h : HELD) {
                try {
                    applyLocked(h);
                } catch (Throwable t) {
                    sayFailure(t);
                }
            }
            wait = settleUntil - System.currentTimeMillis();
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ── holding ─────────────────────────────────────────────────────────────

    /** The rate pinned on the first panel — still so after a release that failed — or 0. */
    private static int heldLocked() {
        if (HELD.isEmpty() || HELD.get(0).modeId < 0) return 0;
        return Math.round(HELD.get(0).rate);
    }

    /** Put ours in place on one panel, after reading what the phone wants. */
    private static void applyLocked(Held h) {
        IBinder token = h.panel.token;
        Object now = specs(token);
        boolean oursInPlace = isOurs(now, h);
        if (!oursInPlace) h.theirs = now;
        Object info = dynamicInfo(h.panel);
        List<RefreshRates.Mode> modes = modes(info);
        RefreshRates.Mode mode = RefreshRates.pick(modes, sizeOf(h.theirs, info, modes),
                wanted, ceiling(h.theirs));
        if (mode == null) {
            // Every rate the panel has is above the phone's ceiling (or its resolution is
            // not listed): hold nothing here, and leave the phone's policy standing until
            // it sends another.
            if (oursInPlace) set(token, h.theirs);
            h.modeId = -1;
            h.rate = 0;
            failure = null;
            return;
        }
        if (!(oursInPlace && h.modeId == mode.id)) {
            Object mine = specs(token);   // a fresh copy, to edit
            pin(mine, mode.id, mode.rate);
            set(token, mine);
            settleUntil = System.currentTimeMillis() + SETTLE_MS;
        }
        h.modeId = mode.id;
        h.rate = mode.rate;
        failure = null;
    }

    /** True when every panel's policy is back to the phone's, or was already. */
    private static boolean releaseLocked() {
        wanted = 0;
        boolean all = true;
        for (Iterator<Held> it = HELD.iterator(); it.hasNext(); ) {
            Held h = it.next();
            try {
                // Only over ours. A policy the phone has sent since is the phone's
                // already, and newer than the one kept here.
                if (h.theirs != null && isOurs(specs(h.panel.token), h)) {
                    set(h.panel.token, h.theirs);
                }
                it.remove();
            } catch (Throwable t) {
                all = false;
                log("refresh: could not give the rate back (" + t.getMessage() + ") — will try again");
            }
        }
        if (all) log("refresh: the rate is the phone's again");
        return all;
    }

    private static void startTickerLocked() {
        if (ticker != null) return;
        ticker = new Thread(Refresh::tick, "wmd-refresh");
        ticker.setDaemon(true);
        ticker.start();
    }

    private static void tick() {
        while (true) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (Refresh.class) {
                if (wanted <= 0) {
                    ticker = null;
                    return;
                }
                for (Held h : HELD) {
                    try {
                        Object now = specs(h.panel.token);
                        // Held: is ours still there? Standing aside: has the phone sent
                        // anything new since — a ceiling moved, say?
                        boolean changed = h.modeId >= 0 ? !isOurs(now, h) : !now.equals(h.theirs);
                        if (changed) applyLocked(h);
                    } catch (Throwable t) {
                        sayFailure(t);
                    }
                }
            }
        }
    }

    /**
     * A rate changed with the panel dark — ours, from the taskbar's tile — does not land
     * until the panel has shown it, so the panel is lit for that moment (at least
     * RELIGHT_MS after it is asked to light) and darkened again. Only on a change to a
     * rate held; giving the rate back in the dark needs no light.
     */
    private static void relight() {
        try {
            Screen.power(true);
            synchronized (Refresh.class) {
                settleUntil = Math.max(settleUntil, System.currentTimeMillis() + RELIGHT_MS);
            }
            Screen.power(false);   // waits in beforePanelOff
        } catch (Throwable t) {
            log("refresh: could not light the panel for the new rate (" + t.getMessage() + ")");
        }
    }

    private static void sayFailure(Throwable t) {
        String what = String.valueOf(t.getMessage());
        if (!what.equals(failure)) {
            failure = what;
            log("refresh: " + what);
        }
    }

    // ── the panels and their policy, by reflection ──────────────────────────

    /**
     * The phone's own panels: a foldable has two, and holding both keeps the rate across a
     * fold. Never a monitor on a cable, whose mode is the monitor's to choose. A build
     * that cannot tell them apart gets the first, the built-in one.
     */
    private static List<Screen.Panel> builtInPanels() {
        List<Screen.Panel> all = Screen.panels();
        List<Screen.Panel> builtIn = new ArrayList<>();
        Class<?> sc = Refl.cls("android.view.SurfaceControl");
        for (Screen.Panel p : all) {
            try {
                Object info = p.id >= 0 && Refl.hasMethod(sc, "getStaticDisplayInfo", long.class)
                        ? Refl.callStatic(sc, "getStaticDisplayInfo", new Class<?>[]{long.class}, p.id)
                        : Refl.callStatic(sc, "getStaticDisplayInfo", new Class<?>[]{IBinder.class}, p.token);
                if (Boolean.TRUE.equals(Refl.field(info, "isInternal"))) builtIn.add(p);
            } catch (Throwable ignored) {
                // Unknown counts as not built in; the fallback below covers a build
                // where nothing can be told.
            }
        }
        if (builtIn.isEmpty() && !all.isEmpty()) builtIn.add(all.get(0));
        if (builtIn.isEmpty()) throw new Refl.WmError("no built-in panel");
        return builtIn;
    }

    private static Object dynamicInfo(Screen.Panel p) {
        Class<?> sc = Refl.cls("android.view.SurfaceControl");
        if (p.id >= 0 && Refl.hasMethod(sc, "getDynamicDisplayInfo", long.class)) {
            return Refl.callStatic(sc, "getDynamicDisplayInfo", new Class<?>[]{long.class}, p.id);
        }
        return Refl.callStatic(sc, "getDynamicDisplayInfo", new Class<?>[]{IBinder.class}, p.token);
    }

    private static List<RefreshRates.Mode> modes(Object info) {
        Object[] raw = (Object[]) Refl.field(info, "supportedDisplayModes");
        List<RefreshRates.Mode> modes = new ArrayList<>(raw.length);
        for (Object m : raw) {
            // "peakRefreshRate" since Android 15, "refreshRate" before it.
            String rate = Refl.hasField(m, "peakRefreshRate") ? "peakRefreshRate" : "refreshRate";
            modes.add(new RefreshRates.Mode((Integer) Refl.field(m, "id"),
                    (Integer) Refl.field(m, "width"), (Integer) Refl.field(m, "height"),
                    (Float) Refl.field(m, rate)));
        }
        return modes;
    }

    /**
     * The mode whose size counts: the policy's default, which is the resolution the phone
     * is set to; the active mode where that one is not listed.
     */
    private static int sizeOf(Object policy, Object info, List<RefreshRates.Mode> modes) {
        int id = (Integer) Refl.field(policy, "defaultMode");
        if (RefreshRates.byId(modes, id) != null) return id;
        return (Integer) Refl.field(info, "activeDisplayModeId");
    }

    private static Object specs(IBinder token) {
        Object specs = Refl.callStatic(Refl.cls("android.view.SurfaceControl"),
                "getDesiredDisplayModeSpecs", new Class<?>[]{IBinder.class}, token);
        if (specs == null) throw new Refl.WmError("no display-mode policy");
        return specs;
    }

    private static void set(IBinder token, Object specs) {
        Class<?> sc = Refl.cls("android.view.SurfaceControl");
        Class<?> type = specs.getClass();
        Object ok;
        if (Refl.hasMethod(sc, "setDesiredDisplayModeSpecs", IBinder.class, type)) {
            ok = Refl.callStatic(sc, "setDesiredDisplayModeSpecs",
                    new Class<?>[]{IBinder.class, type}, token, specs);
        } else {
            // Android 17 takes several at once, each carrying its own display's token.
            Object batch = Array.newInstance(type, 1);
            Array.set(batch, 0, specs);
            ok = Refl.callStatic(sc, "setDesiredDisplayModeSpecs",
                    new Class<?>[]{IBinder.class, batch.getClass()}, token, batch);
        }
        if (Boolean.FALSE.equals(ok)) throw new Refl.WmError("the display-mode policy was refused");
    }

    /** The top of a policy: the fastest it lets the panel run. */
    private static float ceiling(Object specs) {
        if (Refl.hasField(specs, "primaryRanges")) {
            Object ranges = Refl.field(specs, "primaryRanges");
            float physical = (Float) Refl.field(Refl.field(ranges, "physical"), "max");
            float render = (Float) Refl.field(Refl.field(ranges, "render"), "max");
            return Math.min(physical, render);
        }
        return (Float) Refl.field(specs, "primaryRefreshRateMax");
    }

    /** The four ranges' ends of a policy, in a fixed order, for {@link #pin} and {@link #isOurs}. */
    private static final String[] FLAT = {"primaryRefreshRateMin", "primaryRefreshRateMax",
            "appRequestRefreshRateMin", "appRequestRefreshRateMax"};

    /** Edit a policy in place to hold one mode: its rate as floor and ceiling, everywhere. */
    private static void pin(Object specs, int modeId, float rate) {
        Refl.setField(specs, "defaultMode", modeId);
        if (Refl.hasField(specs, "primaryRanges")) {
            for (String ranges : new String[]{"primaryRanges", "appRequestRanges"}) {
                Object pair = Refl.field(specs, ranges);
                for (String kind : new String[]{"physical", "render"}) {
                    Object range = Refl.field(pair, kind);
                    Refl.setField(range, "min", rate);
                    Refl.setField(range, "max", rate);
                }
            }
        } else {
            // Android 12 and 13: the same ranges as four plain floats.
            for (String f : FLAT) Refl.setField(specs, f, rate);
        }
    }

    /** Whether a policy is ours on this panel: our mode, every range pinned at our rate. */
    private static boolean isOurs(Object specs, Held h) {
        if (h.modeId < 0 || (Integer) Refl.field(specs, "defaultMode") != h.modeId) return false;
        if (Refl.hasField(specs, "primaryRanges")) {
            for (String ranges : new String[]{"primaryRanges", "appRequestRanges"}) {
                Object pair = Refl.field(specs, ranges);
                for (String kind : new String[]{"physical", "render"}) {
                    Object range = Refl.field(pair, kind);
                    if (!sameRate((Float) Refl.field(range, "min"), h.rate)
                            || !sameRate((Float) Refl.field(range, "max"), h.rate)) {
                        return false;
                    }
                }
            }
            return true;
        }
        for (String f : FLAT) {
            if (!sameRate((Float) Refl.field(specs, f), h.rate)) return false;
        }
        return true;
    }

    private static boolean sameRate(float a, float b) {
        return Math.abs(a - b) < SAME_RATE;
    }

    private static void log(String line) {
        System.out.println(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                .format(new java.util.Date()) + " " + line);
        System.out.flush();
    }
}
