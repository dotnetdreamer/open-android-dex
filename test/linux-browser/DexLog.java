package com.ccrstech.openandroiddex.launcher;

/**
 * The launcher's DexLog, for a computer's JVM: the same three calls, to
 * stderr, since android.util.Log is not there. Compiled in its place by
 * test/linux-browser-test.mjs, never shipped.
 */
final class DexLog {
    private DexLog() {
    }

    static void step(String area, String message) {
        System.err.println("[" + area + "] " + message);
    }

    static void warn(String area, String message) {
        System.err.println("[" + area + "] WARN " + message);
    }

    static void warn(String area, String message, Throwable t) {
        System.err.println("[" + area + "] WARN " + message + ": " + t);
    }
}
