package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * Android's limit on child processes, and what Linux tells people about it.
 *
 * <p>From Android 12 the system counts the processes every app has started
 * besides its own ("phantom processes": for us, proot and everything in the
 * guest), all apps together, and once there are more than 32 on the phone it
 * kills the surplus: the least important app's first and, among one app's,
 * the OLDEST first. In a Linux session the oldest are always the session's
 * own: the remote-access sshd, then linux-rt.sh, proot, Xvnc, XFCE. A desktop
 * is about twenty processes before anything is opened, and Chromium on a busy
 * site is fifteen more, so a browser was enough to take the whole desktop down
 * at once. Seen on the S25 Ultra (Android 17, 2026-11-11): 38 of them, all
 * ours, six over, the six oldest killed, proot among them, and the guest with
 * it.
 *
 * <p>The limit has an off switch, and Android ships it for exactly this:
 * Developer options, "Disable child process restrictions" (Android 14 and
 * later), which is the {@link #FLAG} feature flag (honoured since Android 12L;
 * on the S25's Android 17 the trim reads it before doing anything else).
 * Android 12 itself has no flag, only the activity_manager
 * max_phantom_processes device config. An app can change neither. The
 * computer can, over adb, and does whenever it sees the phone (adb.rs,
 * ensure_child_process_limit_off), writing the flag where it outranks the
 * Developer options switch, and raising the cap. So on a phone on its own,
 * all this can do is say what probably happened and where the switch is.
 */
final class ChildProcessLimit {

    /** FeatureFlagUtils.SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS: "true", or unset, means the limit is on. */
    static final String FLAG = "settings_enable_monitor_phantom_procs";

    /**
     * Where the Developer options switch keeps it: FeatureFlagUtils.setEnabled
     * writes this property, not the setting. FeatureFlagUtils reads the
     * setting first, the property second.
     */
    private static final String FLAG_PROPERTY = "persist.sys.fflag.override." + FLAG;

    /** What sh and Java both report for a process ended by SIGKILL: 128 + 9. */
    static final int KILLED_EXIT = 137;

    enum State { OFF, ON, UNKNOWN }

    private ChildProcessLimit() {
    }

    /** Whether the limit is on now, as far as an app is allowed to read it. */
    static State state(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return State.OFF; // no such limit before Android 12
        String v;
        try {
            v = Settings.Global.getString(ctx.getContentResolver(), FLAG);
        } catch (Exception e) {
            return State.UNKNOWN; // a key the platform keeps from apps: say nothing rather than guess
        }
        if (v == null || v.isEmpty()) v = property(FLAG_PROPERTY);
        if ("false".equalsIgnoreCase(v)) return State.OFF;
        if ("true".equalsIgnoreCase(v)) return State.ON;
        // Unset is on, from 12L. On Android 12 the only knob is the device
        // config, which the computer raises and an app cannot read.
        return Build.VERSION.SDK_INT >= 32 ? State.ON : State.UNKNOWN;
    }

    /**
     * The limit's state when a session was killed, which LinuxService writes
     * beside rt.exit as it settles one: what the windows say depends on what
     * it was THEN, not now. A computer switches it off on sight, so "now" is
     * usually off by the time anyone reads the card.
     */
    static java.io.File killMarker(Context ctx) {
        return new java.io.File(Linux.root(ctx), "rt.limit");
    }

    /**
     * The session ended with SIGKILL while the limit's switch read as on, or
     * unknown: probably Android's doing, and the windows say "probably". It is
     * an inference, not a finding. The app cannot see who sent the kill (the
     * guest's own kill -9 looks the same), nor the max_phantom_processes cap,
     * which the computer raises and an app cannot read, so a switch turned
     * back on over a raised cap still reads as on. A SIGKILL with the switch
     * off was something else, and is not put down to the limit. The marker is
     * read as the service settled the session, a moment after the kill; with
     * no marker (a verdict read before that, or a service that was gone) the
     * state now is the best guess there is.
     */
    static boolean killedByLimit(Context ctx, Linux.Status st) {
        if (!st.sessionEnded || st.sessionExit != KILLED_EXIT) return false;
        String then = Linux.readFile(killMarker(ctx)).trim();
        State at = then.isEmpty() ? state(ctx) : parse(then);
        return at != State.OFF;
    }

    private static State parse(String name) {
        try {
            return State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return State.UNKNOWN;
        }
    }

    /** Developer options has the switch: Android 14 and later. */
    static boolean hasSwitch() {
        return Build.VERSION.SDK_INT >= 34;
    }

    /** The intent for Developer options, where the switch is. */
    static Intent switchIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * A session Android killed, in a sentence or two: what happened, then the
     * one thing that stops it happening again.
     *
     * @param forComputer worded for a computer's window rather than the phone's
     */
    static String explain(Context ctx, boolean forComputer) {
        State s = state(ctx);
        String why = ctx.getString(forComputer ? R.string.ln_killed_why_pc : R.string.ln_killed_why);
        String next;
        if (s == State.OFF) {
            next = ctx.getString(R.string.ln_killed_off_now);
        } else if (hasSwitch()) {
            next = ctx.getString(forComputer ? R.string.ln_killed_switch_pc : R.string.ln_killed_switch);
        } else {
            next = ctx.getString(R.string.ln_killed_connect);
        }
        return why + " " + next;
    }

    /** A system property, or "" when it is unset or kept from apps. */
    private static String property(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, name);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
