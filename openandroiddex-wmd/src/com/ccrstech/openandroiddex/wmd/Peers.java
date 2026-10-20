package com.ccrstech.openandroiddex.wmd;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who is on the other end of a connection to this daemon, by the kernel's account.
 *
 * The socket is loopback TCP (see WmDaemon for why not a LocalSocket), and loopback
 * TCP carries no SO_PEERCRED: any app holding INTERNET can connect. Until this check,
 * every one of them could drive everything this process does as uid 2000 — and through
 * ARM, hand it a shell command to run later with that authority.
 *
 * The kernel knows who connected. /proc/net/tcp6 and /proc/net/tcp list every socket
 * with the uid that created it, and shell may read them (untrusted_app may not since
 * Android 10, which is why the answer comes from this side). The caller's end is the
 * line whose LOCAL port is the peer's port and whose REMOTE port is ours. Measured on
 * SM-S938B (Android 16): the launcher's end reads uid 10601, adbd's end of an
 * `adb forward` reads 2000.
 *
 * Only a socket a process still holds counts, which the inode says: an orphan — a
 * client that connected, wrote and closed before this read ran — is printed with uid 0
 * and inode 0, and read naively that is root. Nothing that talks to this daemon hangs
 * up before its answer, so an orphan is refused rather than believed.
 */
final class Peers {

    /** Readable tables, and no live socket in them at that port. Refused. */
    static final int UNKNOWN = -1;

    /** Neither table could be read at all, so nobody can be told apart. */
    static final int UNREADABLE = -2;

    static final int ROOT_UID = 0;
    static final int SHELL_UID = 2000;

    /** uid = userId * PER_USER_RANGE + appId (UserHandle.PER_USER_RANGE). */
    static final int PER_USER_RANGE = 100000;

    /** First app id (Process.FIRST_APPLICATION_UID); below it are system ids. */
    static final int FIRST_APPLICATION_UID = 10000;

    private static final String[] TABLES = {"/proc/net/tcp6", "/proc/net/tcp"};

    private Peers() {
    }

    /**
     * The uid owning the client end of the loopback connection from {@code peerPort} to
     * {@code ourPort}, or {@link #UNKNOWN} / {@link #UNREADABLE}.
     *
     * Both tables, because both happen: a Java client is a dual-stack socket and shows in
     * tcp6 under its IPv4-mapped address, while adbd's forward connects with a plain IPv4
     * socket and shows in tcp.
     */
    static int uidOf(int peerPort, int ourPort) {
        boolean readAny = false;
        for (String path : TABLES) {
            String table = read(path);
            if (table == null) continue;
            readAny = true;
            int uid = uidIn(table, peerPort, ourPort);
            if (uid != UNKNOWN) return uid;
        }
        return readAny ? UNKNOWN : UNREADABLE;
    }

    /** {@link #uidOf} over one table's text — the part that can be checked off a phone. */
    static int uidIn(String table, int peerPort, int ourPort) {
        for (String raw : table.split("\n")) {
            // sl local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode …
            String[] f = raw.trim().split("\\s+");
            if (f.length < 10 || !f[0].endsWith(":")) continue;   // the header, or junk
            int lc = f[1].lastIndexOf(':');
            int rc = f[2].lastIndexOf(':');
            if (lc < 0 || rc < 0) continue;
            try {
                if (Integer.parseInt(f[1].substring(lc + 1), 16) != peerPort) continue;
                if (Integer.parseInt(f[2].substring(rc + 1), 16) != ourPort) continue;
            } catch (NumberFormatException e) {
                continue;
            }
            // A connection to some other host's 7191 can share the peer's port number
            // on a different local address; only both ends on loopback are ours.
            if (!loopback(f[1].substring(0, lc)) || !loopback(f[2].substring(0, rc))) continue;
            if ("0".equals(f[9])) continue;                       // an orphan: see above
            try {
                return Integer.parseInt(f[7]);
            } catch (NumberFormatException e) {
                // keep looking; a malformed line proves nothing either way
            }
        }
        return UNKNOWN;
    }

    /**
     * 127.0.0.0/8, its IPv4-mapped spelling, or ::1, as the kernel prints them: each
     * 32-bit word in host (little-endian) order, so 127.0.0.1 is 0100007F and the 127
     * lands at the END.
     */
    static boolean loopback(String hex) {
        if (hex.length() == 8) return hex.endsWith("7F");
        if (hex.length() == 32) {
            return (hex.startsWith("0000000000000000FFFF0000") && hex.endsWith("7F"))
                    || hex.equals("00000000000000000000000001000000");
        }
        return false;
    }

    /** Whether {@code uid}, in any user, is the app whose app id is {@code appId}. */
    static boolean sameApp(int uid, int appId) {
        return appId >= FIRST_APPLICATION_UID && uid >= 0 && uid % PER_USER_RANGE == appId;
    }

    private static final Pattern LISTED_UID = Pattern.compile("^package:(\\S+) uid:(\\d+)");
    private static final Pattern DUMPED_APP_ID = Pattern.compile("\\bappId=(\\d+)");

    /**
     * {@code pkg}'s app id from `cmd package list packages -U --user 0 <pkg>`, or -1.
     *
     * The filter there is a SUBSTRING match, so "…launcher.extra" would be listed too:
     * the name has to match whole. A phone with more users (Samsung's Secure Folder is
     * user 150) adds an error for each one the shell may not see, which is why the user
     * is pinned and why errors in the text are skipped rather than fatal.
     */
    static int appIdFromList(String listing, String pkg) {
        for (String line : listing.split("\n")) {
            Matcher m = LISTED_UID.matcher(line.trim());
            if (m.find() && m.group(1).equals(pkg)) {
                try {
                    return Integer.parseInt(m.group(2)) % PER_USER_RANGE;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** {@code pkg}'s app id from `dumpsys package <pkg>`, the slow fallback, or -1. */
    static int appIdFromDump(String dump) {
        Matcher m = DUMPED_APP_ID.matcher(dump);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A whole /proc file, or null when it cannot be read. They are a few KiB. */
    private static String read(String path) {
        try (InputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[16384];
            StringBuilder out = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            return out.toString();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
