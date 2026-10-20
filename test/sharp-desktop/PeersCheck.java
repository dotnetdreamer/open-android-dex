package com.ccrstech.openandroiddex.wmd;

/**
 * The window daemon's caller check (Peers), on a computer's JVM, for
 * test/sharp-desktop-test.mjs: one line per check, "ok <name>" or
 * "FAIL <name> — <detail>".
 *
 * The tables are lines read off a Galaxy S25 Ultra (Android 16) with the
 * launcher and the PC's `adb forward` both connected to 7191, plus the cases
 * built around them that the check exists to refuse.
 */
public final class PeersCheck {

    private static int failed;

    private static void check(String name, boolean ok, Object detail) {
        if (ok) {
            System.out.println("ok " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " — " + detail);
        }
    }

    private static final int PORT = 7191;

    private static final String HEADER =
            "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";

    public static void main(String[] args) {
        String tcp6 = HEADER
                // the launcher's live socket to us, port 0xE3E8 = 58344
                + "  10: 0000000000000000FFFF00000100007F:E3E8 0000000000000000FFFF00000100007F:1C17 01 00000000:00000000 00:00000000 00000000 10601        0 13058726 1 0000000000000000 20 4 28 10 -1\n"
                // our own end of it: local port 7191, which must never be mistaken for the caller
                + "  11: 0000000000000000FFFF00000100007F:1C17 0000000000000000FFFF00000100007F:E3E8 01 00000000:00000000 00:00000000 00000000  2000        0 13058727 1 0000000000000000 20 4 28 10 -1\n"
                // a connection that is gone: TIME_WAIT, printed as uid 0 inode 0
                + "   7: 0000000000000000FFFF00000100007F:E3F6 0000000000000000FFFF00000100007F:1C17 06 00000000:00000000 03:000004D9 00000000     0        0 0 3 0000000000000000\n"
                // an app that wrote and closed: an orphan in FIN_WAIT2, uid 0 inode 0
                + "  12: 0000000000000000FFFF00000100007F:E400 0000000000000000FFFF00000100007F:1C17 05 00000000:00000000 03:000004EA 00000000     0        0 0 3 0000000000000000\n"
                // some app's connection to another HOST's 7191, from a port number a local caller also has
                + "  13: 0000000000000000FFFF0000C800000A:E3E9 0000000000000000FFFF00000102A8C0:1C17 01 00000000:00000000 00:00000000 00000000 10234        0 13060001 1 0000000000000000 20 4 28 10 -1\n"
                // ...and the local caller on that port, a stranger
                + "  14: 0000000000000000FFFF00000100007F:E3E9 0000000000000000FFFF00000100007F:1C17 01 00000000:00000000 00:00000000 00000000 10234        0 13060002 1 0000000000000000 20 4 28 10 -1\n"
                // over ::1
                + "  15: 00000000000000000000000001000000:E500 00000000000000000000000001000000:1C17 01 00000000:00000000 00:00000000 00000000 10601        0 13060003 1 0000000000000000 20 4 28 10 -1\n";
        String tcp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                // adbd's end of the PC's `adb forward`, port 0xCB80
                + "   2: 0100007F:CB80 0100007F:1C17 01 00000000:00000000 00:00000000 00000000  2000        0 13090001 1 0000000000000000 20 4 30 10 -1\n"
                // and an old one of its, closed
                + "   1: 0100007F:CB77 0100007F:1C17 06 00000000:00000000 03:00000117 00000000     0        0 0 3 0000000000000000\n";

        check("the launcher's socket reads as its uid", Peers.uidIn(tcp6, 0xE3E8, PORT) == 10601,
                Peers.uidIn(tcp6, 0xE3E8, PORT));
        check("adbd's forward, in the IPv4 table, reads as shell", Peers.uidIn(tcp, 0xCB80, PORT) == Peers.SHELL_UID,
                Peers.uidIn(tcp, 0xCB80, PORT));
        check("a closed connection is not root: uid 0 with no inode is refused",
                Peers.uidIn(tcp6, 0xE3F6, PORT) == Peers.UNKNOWN && Peers.uidIn(tcp, 0xCB77, PORT) == Peers.UNKNOWN,
                Peers.uidIn(tcp6, 0xE3F6, PORT) + "/" + Peers.uidIn(tcp, 0xCB77, PORT));
        check("an app that wrote and hung up before the check is refused, not trusted",
                Peers.uidIn(tcp6, 0xE400, PORT) == Peers.UNKNOWN, Peers.uidIn(tcp6, 0xE400, PORT));
        check("a connection to another host's 7191 is not mistaken for the caller",
                Peers.uidIn(tcp6, 0xE3E9, PORT) == 10234, Peers.uidIn(tcp6, 0xE3E9, PORT));
        String ownEndFirst = HEADER
                + "  11: 0000000000000000FFFF00000100007F:1C17 0000000000000000FFFF00000100007F:E3E8 01 00000000:00000000 00:00000000 00000000  2000        0 13058727 1 0000000000000000 20 4 28 10 -1\n"
                + "  10: 0000000000000000FFFF00000100007F:E3E8 0000000000000000FFFF00000100007F:1C17 01 00000000:00000000 00:00000000 00000000 10601        0 13058726 1 0000000000000000 20 4 28 10 -1\n";
        check("our own end of the connection is never the answer, even listed first",
                Peers.uidIn(ownEndFirst, 0xE3E8, PORT) == 10601, Peers.uidIn(ownEndFirst, 0xE3E8, PORT));
        check("::1 counts as loopback", Peers.uidIn(tcp6, 0xE500, PORT) == 10601, Peers.uidIn(tcp6, 0xE500, PORT));
        check("a port nobody holds is unknown", Peers.uidIn(tcp6 + tcp, 0x1234, PORT) == Peers.UNKNOWN,
                Peers.uidIn(tcp6 + tcp, 0x1234, PORT));
        check("junk lines prove nothing", Peers.uidIn("garbage\n 1: x y\n\n", 1, PORT) == Peers.UNKNOWN, "-");

        check("loopback: 127.0.0.1 and the rest of 127/8, as the kernel prints them",
                Peers.loopback("0100007F") && Peers.loopback("0200007F"), "-");
        check("loopback: not 192.168.1.1", !Peers.loopback("0101A8C0"), "-");
        check("loopback: the IPv4-mapped spelling, and only of 127/8",
                Peers.loopback("0000000000000000FFFF00000100007F")
                        && !Peers.loopback("0000000000000000FFFF00000101A8C0"), "-");
        check("loopback: ::1, and not ::", Peers.loopback("00000000000000000000000001000000")
                && !Peers.loopback("00000000000000000000000000000000"), "-");

        String listing = "package:com.ccrstech.openandroiddex.launcher.extra uid:10999\n"
                + "package:com.ccrstech.openandroiddex.launcher uid:10601\n"
                + "Error: java.lang.SecurityException: Shell does not have permission to access user 150\n"
                + " com.android.server.am.ActivityManagerService.handleIncomingUser:8\n";
        String launcher = "com.ccrstech.openandroiddex.launcher";
        check("the launcher's app id, past a longer name the filter also matched and an error for user 150",
                Peers.appIdFromList(listing, launcher) == 10601, Peers.appIdFromList(listing, launcher));
        check("…in another user, the app id all the same",
                Peers.appIdFromList("package:" + launcher + " uid:1010601", launcher) == 10601,
                Peers.appIdFromList("package:" + launcher + " uid:1010601", launcher));
        check("…and none when it is not installed", Peers.appIdFromList("", launcher) == -1, "-");
        check("the dumpsys fallback", Peers.appIdFromDump("Packages:\n  Package [x] (1):\n    appId=10601\n") == 10601
                && Peers.appIdFromDump("Unable to find package: x") == -1, "-");

        check("the same app in any user", Peers.sameApp(10601, 10601) && Peers.sameApp(1010601, 10601), "-");
        check("not another app, and never a system uid or an unknown launcher",
                !Peers.sameApp(10602, 10601) && !Peers.sameApp(2000, 2000) && !Peers.sameApp(10601, -1), "-");

        System.out.println("done " + failed);
    }
}
