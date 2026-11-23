package com.ccrstech.openandroiddex.launcher;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.List;

/**
 * Where this "phone" can be reached: LinuxBrowser.addresses() reads the
 * network interfaces, which on a test computer are the computer's. The rest
 * of LinuxBrowser (the switch, the code, the browsers, the state) stays real.
 */
@Implements(LinuxBrowser.class)
public class BrowserAddressShadow {

    static List<LinuxBrowser.Address> list = new ArrayList<>();

    static void wifi(String... ips) {
        list = new ArrayList<>();
        for (String ip : ips) list.add(new LinuxBrowser.Address(ip, "wifi"));
    }

    @Implementation
    protected static List<LinuxBrowser.Address> addresses() {
        return new ArrayList<>(list);
    }
}
