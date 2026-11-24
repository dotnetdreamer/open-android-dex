package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

/**
 * Easy access: a computer the person allowed sees this phone's screen with
 * nobody at the phone, even while it is locked (doc/phone-remote.md, "Easy
 * access"). Underneath it is Android's PROJECT_MEDIA app-op for this app
 * ({@link PhoneRemote#unattended}), which lets screen sharing start with no
 * question on the phone and go on while the phone is locked. No app may set
 * that op for itself; the shell may, so the phone's helper sets it
 * ({@link PhoneHelper}'s {@code project}), started first through the phone's
 * own Wireless debugging when it does not run ({@link PhoneHelperStart#withHelper}).
 *
 * <p>Android's own answer is the switch's state: read again from Android
 * after every change, never kept here. What is kept here is only what the
 * card says meanwhile: a change under way, and why the last one did not
 * happen, said only while Android still disagrees with what was asked.
 */
final class PhoneEasyAccess {

    /** {@link #working()}: a change under way, to on or to off. */
    static final String TURNING_ON = "on";
    static final String TURNING_OFF = "off";
    /** {@link #failed}: no helper answered (none could be started); the card's Screen off line says why. */
    static final String NO_HELPER = "\u0000no-helper";
    /** {@link #failed}: a helper took the question and gave no answer in time. */
    static final String NO_ANSWER = "\u0000no-answer";

    /**
     * How long the helper may take to answer. Well past the helper's own
     * limit for a change (PhoneHelper.ShellOps.BUDGET_MS, 12 s), so a helper
     * that runs always answers first: the app never says a change did not
     * happen while the helper is still making it. Package-private for the tests.
     */
    static volatile long answerMs = 30_000;

    private static final Object LOCK = new Object();
    private static String working = "";
    private static String failed = "";
    /** What the change that failed asked for: its failure is said only while Android still says otherwise. */
    private static Boolean failedWanted;

    private PhoneEasyAccess() {
    }

    /** "" while nothing is under way, else {@link #TURNING_ON} or {@link #TURNING_OFF}. */
    static String working() {
        synchronized (LOCK) {
            return working;
        }
    }

    /**
     * Why the last change did not happen ({@link #NO_HELPER}, {@link #NO_ANSWER},
     * or the helper's or Android's words); "" when it did, or when Android
     * says what was asked after all (an answer that came too late, a
     * computer's adb).
     */
    static String failed(Context ctx) {
        synchronized (LOCK) {
            if (!failed.isEmpty() && failedWanted != null && PhoneRemote.unattended(ctx) == failedWanted) {
                failed = "";
                failedWanted = null;
            }
            return failed;
        }
    }

    /**
     * Easy access turned on or off: on the helper's start thread, never the
     * caller's. One change at a time; a second while one is under way is
     * dropped (the switch shows the first).
     */
    static void set(Context ctx, boolean on) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (!working.isEmpty()) return;
            working = on ? TURNING_ON : TURNING_OFF;
            failed = "";
            failedWanted = null;
        }
        PhoneHelperStart.withHelper(app, here -> {
            String why;
            try {
                why = here ? apply(app, on) : NO_HELPER;
            } catch (RuntimeException e) {
                why = e.toString();
            }
            if (why == null) {
                DexLog.step("phone", "easy access is " + (on ? "on" : "off") + " (set by the phone's helper)");
            } else {
                DexLog.warn("phone", "easy access stayed " + (on ? "off" : "on") + ": "
                        + (NO_HELPER.equals(why) ? "no helper" : NO_ANSWER.equals(why) ? "no answer from the helper" : why));
            }
            synchronized (LOCK) {
                working = "";
                failed = why == null ? "" : why;
                failedWanted = why == null ? null : on;
            }
        });
    }

    /**
     * The helper asked, then Android's word read back: null once this app's
     * PROJECT_MEDIA is as wanted, else why not. On a worker thread.
     * Package-private for the tests.
     */
    static String apply(Context app, boolean on) {
        if (PhoneRemote.unattended(app) == on) return null;
        String word = on ? "on" : "off";
        String answer = PhoneHelperLink.askOnce(app, "project " + word, answerMs);
        if (answer == null) return NO_HELPER;
        if (PhoneHelperLink.NO_ANSWER.equals(answer)) {
            // Asked, and no answer in time: Android's own word decides.
            return PhoneRemote.unattended(app) == on ? null : NO_ANSWER;
        }
        if (answer.startsWith("error ")) return answer.substring("error ".length());
        if (!answer.equals("project " + word)) return answer;
        // The helper read Android's answer back already; this is the app's own view of it.
        return PhoneRemote.unattended(app) == on ? null : "Android did not take the change";
    }

    /**
     * A fresh Settings page: why an earlier page's change did not happen is
     * not this page's to say (the person may have fixed it since). A change
     * still under way stays.
     */
    static void pageOpened() {
        synchronized (LOCK) {
            failed = "";
            failedWanted = null;
        }
    }

    /** The tests' fresh start: nothing under way, nothing failed, the usual wait. */
    static void forgetForTest() {
        synchronized (LOCK) {
            working = "";
            failed = "";
            failedWanted = null;
        }
        answerMs = 30_000;
    }
}
