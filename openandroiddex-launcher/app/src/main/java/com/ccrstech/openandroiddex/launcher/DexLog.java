package com.ccrstech.openandroiddex.launcher;

import android.util.Log;

/**
 * Breadcrumbs for the phone half of a desktop launch.
 *
 * The PC streams `logcat -s OpenDeX` for as long as a session lives and folds
 * the lines into its own trace file, so anything written here lands in the
 * same timeline as the adb commands and scrcpy output that produced it. That
 * is the whole point: when a desktop half-starts, the interesting question is
 * usually which side got as far as what, and the two sides used to keep
 * separate diaries — the PC's said nothing about the launcher, and the
 * launcher kept none at all.
 *
 * Keep it to steps and state, not to per-frame chatter: this is a shared log,
 * and a noisy tag drowns the one line that mattered.
 */
final class DexLog {
    static final String TAG = "OpenDeX";

    private DexLog() {
    }

    /** One step, attributed to the part of the launcher that took it. */
    static void step(String area, String message) {
        Log.i(TAG, "[" + area + "] " + message);
    }

    static void warn(String area, String message) {
        Log.w(TAG, "[" + area + "] " + message);
    }

    static void warn(String area, String message, Throwable t) {
        Log.w(TAG, "[" + area + "] " + message, t);
    }
}
