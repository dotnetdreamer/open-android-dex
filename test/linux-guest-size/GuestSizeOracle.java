package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * LinuxGuestSize on a computer's JVM, for test/linux-guest-size-test.mjs: one
 * question per line on stdin, one answer per line on stdout.
 *
 *   box <phone|desktop> <w> <h> <dpr>                          forBox, CSS px  -> "<w> <h>"
 *   window <phone|desktop> <wpx> <hpx> <dpr> <docked> <pinned>  forWindow, px   -> "<w> <h>"
 *   bars          the heights it assumes -> "<narrow> <wide> <desktop> <keyrow> <keyrow short>"
 */
public final class GuestSizeOracle {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (String line; (line = in.readLine()) != null; ) {
            String[] a = line.trim().split(" ");
            if (a[0].equals("bars")) {
                out.append(LinuxGuestSize.BAR_PHONE_NARROW).append(' ')
                        .append(LinuxGuestSize.BAR_PHONE).append(' ')
                        .append(LinuxGuestSize.BAR_DESKTOP).append(' ')
                        .append(LinuxGuestSize.KEYROW).append(' ')
                        .append(LinuxGuestSize.KEYROW_SHORT).append('\n');
                continue;
            }
            boolean phone = a[1].equals("phone");
            double dpr = Double.parseDouble(a[4]);
            int[] r = a[0].equals("window")
                    ? LinuxGuestSize.forWindow(phone, Integer.parseInt(a[2]), Integer.parseInt(a[3]), dpr,
                            a[5].equals("1"), a[6].equals("1"))
                    : LinuxGuestSize.forBox(phone, Double.parseDouble(a[2]), Double.parseDouble(a[3]), dpr);
            out.append(r[0]).append(' ').append(r[1]).append('\n');
        }
        System.out.print(out);
    }
}
