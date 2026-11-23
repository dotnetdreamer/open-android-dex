package com.ccrstech.openandroiddex.wmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which of the panel's modes HZ holds, and which rates it reports: the rules behind
 * {@link Refresh}. Free of Android types so a computer's JVM can check them as they
 * are (test/refresh-rate-test.mjs).
 */
final class RefreshRates {

    private RefreshRates() {
    }

    /** One of a panel's display modes, as SurfaceFlinger lists it. */
    static final class Mode {
        final int id;
        final int width;
        final int height;
        final float rate;

        Mode(int id, int width, int height, float rate) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.rate = rate;
        }
    }

    /**
     * The mode to hold for {@code wanted} Hz, or null for none: mode {@code sizeOf} not
     * among {@code modes}, or no mode the phone's ceiling allows.
     *
     * <p>Only modes the size of {@code sizeOf} count: that is the resolution the phone
     * is set to, and holding a mode of another size would change it. Of those, the
     * fastest at or below both {@code wanted} and {@code ceiling}, the top of the
     * phone's own policy (0 for none) — battery saver, heat and Motion smoothness put
     * their limits there, and a hold must not lift them. Asked for less than the
     * slowest, the slowest, if the ceiling allows it. With every mode above the ceiling,
     * none: the phone's own policy stands. Rates are compared with half a hertz to
     * spare: SurfaceFlinger lists 120 Hz as 120.00001.
     */
    static Mode pick(List<Mode> modes, int sizeOf, float wanted, float ceiling) {
        Mode size = byId(modes, sizeOf);
        if (size == null) return null;
        float limit = ceiling > 0 ? ceiling + 0.5f : Float.MAX_VALUE;
        float goal = Math.min(wanted + 0.5f, limit);
        Mode best = null;
        Mode slowest = null;
        for (Mode m : modes) {
            if (m.width != size.width || m.height != size.height) continue;
            if (slowest == null || m.rate < slowest.rate) slowest = m;
            if (m.rate <= goal && (best == null || m.rate > best.rate)) best = m;
        }
        if (best != null) return best;
        return slowest != null && slowest.rate <= limit ? slowest : null;
    }

    /** The rates the panel has at mode {@code sizeOf}'s size: whole hertz, fastest first, each once. */
    static List<Integer> offered(List<Mode> modes, int sizeOf) {
        List<Integer> rates = new ArrayList<>();
        Mode size = byId(modes, sizeOf);
        if (size == null) return rates;
        for (Mode m : modes) {
            if (m.width != size.width || m.height != size.height) continue;
            int hz = Math.round(m.rate);
            if (!rates.contains(hz)) rates.add(hz);
        }
        Collections.sort(rates, Collections.reverseOrder());
        return rates;
    }

    static Mode byId(List<Mode> modes, int id) {
        for (Mode m : modes) {
            if (m.id == id) return m;
        }
        return null;
    }
}
