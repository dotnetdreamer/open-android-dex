package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * What LinuxAppsLink writes into the guest so that apps in a proot of their
 * own can use the desktop's D-Bus: the bus policy, and the passwd line its
 * user name resolves through. The bus itself, taking and refusing clients by
 * these rules, is test/linux-apps-test.mjs (a real dbus-daemon) and the phone.
 */
public class LinuxAppsLinkTest {

    private static final String PASSWD = "root:x:0:0:root:/root:/bin/bash\n"
            + "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n"
            + "sshd:x:101:65534::/run/sshd:/usr/sbin/nologin\n";

    private static final String OURS =
            "oadx-android:x:10556:10556:LinuxOnDroid app user (oadx-run):/nonexistent:/usr/sbin/nologin\n";

    @Test
    public void passwd_gainsOneLineForThisAppsUser_andKeepsTheRest() {
        assertEquals(PASSWD + OURS, LinuxAppsLink.passwdText(PASSWD, 10556));
    }

    @Test
    public void passwd_isLeftAsItIs_whenTheLineIsAlreadyRight() {
        String once = LinuxAppsLink.passwdText(PASSWD, 10556);
        assertEquals(once, LinuxAppsLink.passwdText(once, 10556));
    }

    @Test
    public void passwd_aNewInstallsUid_replacesTheOldLine_neverAddsASecond() {
        String old = PASSWD + "oadx-android:x:10123:10123:old:/nonexistent:/usr/sbin/nologin\n";
        String now = LinuxAppsLink.passwdText(old, 10556);
        assertEquals(PASSWD + OURS, now);
        assertEquals(1, now.split("oadx-android:", -1).length - 1);
    }

    @Test
    public void passwd_aWorkProfileUid_isTakenWhole() {
        // A second Android user's app: user 10 times 100000, plus the app id.
        assertTrue(LinuxAppsLink.passwdText(PASSWD, 1010556)
                .endsWith("oadx-android:x:1010556:1010556:LinuxOnDroid app user (oadx-run):/nonexistent:/usr/sbin/nologin\n"));
    }

    @Test
    public void passwd_oddFiles_stayOddOnlyWhereTheyWere() {
        // No newline at the end: one is added before ours, nothing else moves.
        assertEquals(PASSWD + OURS, LinuxAppsLink.passwdText(PASSWD.substring(0, PASSWD.length() - 1), 10556));
        // A blank line in the middle someone left there: still there.
        String withBlank = "root:x:0:0:root:/root:/bin/bash\n\nsshd:x:101:65534::/run/sshd:/usr/sbin/nologin\n";
        assertEquals(withBlank + OURS, LinuxAppsLink.passwdText(withBlank, 10556));
        // A user whose name only starts the same way is someone else's.
        String lookalike = PASSWD + "oadx-androidx:x:2000:2000::/home/x:/bin/sh\n";
        assertEquals(lookalike + OURS, LinuxAppsLink.passwdText(lookalike, 10556));
    }

    @Test
    public void busPolicy_isOneBusConfig_withBothWaysIn_andOnlyThisAppsUser() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // The DOCTYPE names a DTD on the web: parse without fetching it.
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder b = f.newDocumentBuilder();
        // The file the app copies to /etc/dbus-1/session.d/oadx-apps.conf.
        byte[] conf = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/assets/linux/guest/oadx-apps-dbus.conf"));
        String text = new String(conf, StandardCharsets.UTF_8);
        Document d = b.parse(new ByteArrayInputStream(conf));
        assertEquals("busconfig", d.getDocumentElement().getTagName());
        NodeList auth = d.getElementsByTagName("auth");
        assertEquals(2, auth.getLength());
        // EXTERNAL stays: the distro's session.conf allows only it, and every
        // <auth> narrows the list to the ones named.
        assertEquals("EXTERNAL", auth.item(0).getTextContent());
        assertEquals("DBUS_COOKIE_SHA1", auth.item(1).getTextContent());
        NodeList allow = d.getElementsByTagName("allow");
        assertEquals(1, allow.getLength());
        assertEquals(LinuxAppsLink.GUEST_USER, ((Element) allow.item(0)).getAttribute("user"));
        assertEquals("default", ((Element) allow.item(0).getParentNode()).getAttribute("context"));
        assertFalse("never every user: other apps on the phone are other users", text.contains("user=\"*\""));
        assertFalse("never anonymous", text.contains("ANONYMOUS") || text.contains("allow_anonymous"));
    }
}
