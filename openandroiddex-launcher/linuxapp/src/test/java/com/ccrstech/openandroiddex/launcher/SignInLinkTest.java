package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The sign-in links an email carries ({@link LinuxBrowser#newLinks},
 * {@link LinuxBrowser#tryLink}): once, at its own address, within its day,
 * replaced by the next email's, ended by a sign-out of every browser and by
 * switching the links off, and nothing kept on the phone but each secret's
 * hash. Plain JUnit, on the same store file the door uses.
 */
public class SignInLinkTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LinuxBrowser store;
    private File dir;
    private static final long NOW = 1_800_000_000_000L;
    private static final String TUNNEL = "quiet-river-fox.trycloudflare.com";
    private static final String WIFI = "192.168.2.25";

    @Before
    public void store() throws Exception {
        dir = tmp.newFolder("browser");
        store = new LinuxBrowser(dir);
    }

    /** An email's links, made and gone out with it. */
    private List<String> links() {
        return email(NOW);
    }

    private List<String> email(long at) {
        List<String> s = store.newLinks(Arrays.asList(TUNNEL, WIFI), at);
        store.confirmLinks(s);
        return s;
    }

    @Test
    public void aLink_letsOneBrowserIn_once() {
        List<String> s = links();
        assertEquals(2, s.size());
        assertNotEquals(s.get(0), s.get(1));
        assertTrue("256 bits, base64url", s.get(0).matches("[A-Za-z0-9_-]{43}"));
        assertEquals(2, store.linkCount(NOW));
        LinuxBrowser.LinkTried t = store.tryLink(s.get(0), TUNNEL, NOW + 60_000, "Edge on Windows · tunnel 203.0.113.9");
        assertEquals(LinuxBrowser.Link.OK, t.link);
        assertNotNull(t.token);
        assertTrue("the token is a browser's like any code's", store.tokenValid(t.token));
        assertEquals(1, store.tokenCount());
        assertEquals("used up", LinuxBrowser.Link.USED, store.tryLink(s.get(0), TUNNEL, NOW + 61_000, "x").link);
        assertEquals("still one browser", 1, store.tokenCount());
        assertEquals("the other address's link still works", 1, store.linkCount(NOW + 61_000));
    }

    @Test
    public void aLink_worksOnlyAtItsOwnAddress() {
        List<String> s = links();
        assertEquals(LinuxBrowser.Link.ELSEWHERE, store.tryLink(s.get(0), WIFI, NOW, "x").link);
        assertEquals(LinuxBrowser.Link.ELSEWHERE, store.tryLink(s.get(1), TUNNEL, NOW, "x").link);
        assertEquals(LinuxBrowser.Link.ELSEWHERE, store.tryLink(s.get(1), "other.trycloudflare.com", NOW, "x").link);
        assertEquals("not used up by a try elsewhere", LinuxBrowser.Link.OK,
                store.tryLink(s.get(1), "192.168.2.25", NOW, "x").link);
        assertEquals("the address's case does not matter", LinuxBrowser.Link.OK,
                store.tryLink(s.get(0), TUNNEL.toUpperCase(), NOW, "x").link);
    }

    @Test
    public void aLink_endsAfterADay() {
        List<String> s = links();
        long late = NOW + LinuxBrowser.LINK_LIFE_MS + 1;
        assertEquals(LinuxBrowser.Link.OLD, store.tryLink(s.get(0), TUNNEL, late, "x").link);
        assertEquals("and stays ended", LinuxBrowser.Link.OLD, store.tryLink(s.get(0), TUNNEL, NOW, "x").link);
        assertEquals(0, store.tokenCount());
        assertEquals("a day less a second is still in time", LinuxBrowser.Link.OK,
                store.tryLink(s.get(1), WIFI, NOW + LinuxBrowser.LINK_LIFE_MS - 1000, "x").link);
    }

    @Test
    public void theNextEmailsLinks_replaceTheLastOnes() {
        List<String> first = links();
        List<String> second = email(NOW + 1000);
        assertEquals(LinuxBrowser.Link.REPLACED, store.tryLink(first.get(0), TUNNEL, NOW + 2000, "x").link);
        assertEquals(LinuxBrowser.Link.OK, store.tryLink(second.get(0), TUNNEL, NOW + 2000, "x").link);
        assertEquals(1, store.linkCount(NOW + 2000));
    }

    @Test
    public void anEmailThatDidNotGo_leavesTheLastEmailsLinksWorking() {
        List<String> first = links();
        List<String> unsent = store.newLinks(Arrays.asList(TUNNEL, WIFI), NOW + 1000);
        assertEquals("while the next one is on its way, the last email's links work", 4, store.linkCount(NOW + 1000));
        store.dropLinks(unsent, "unsent");
        assertEquals(LinuxBrowser.Link.OFF, store.tryLink(unsent.get(0), TUNNEL, NOW + 2000, "x").link);
        assertEquals("the person's email still opens the phone", LinuxBrowser.Link.OK,
                store.tryLink(first.get(0), TUNNEL, NOW + 2000, "x").link);
        assertEquals(1, store.linkCount(NOW + 2000));
    }

    @Test
    public void signingOutEveryBrowser_endsTheLinksToo() {
        List<String> s = links();
        String token = store.tryLink(s.get(0), TUNNEL, NOW, "x").token;
        store.signOutAll();
        assertFalse(store.tokenValid(token));
        assertEquals(0, store.linkCount(NOW));
        assertEquals(LinuxBrowser.Link.OFF, store.tryLink(s.get(1), WIFI, NOW, "x").link);
        assertEquals("a used one says it was used", LinuxBrowser.Link.USED, store.tryLink(s.get(0), TUNNEL, NOW, "x").link);
    }

    @Test
    public void switchingTheLinksOff_endsThem() {
        List<String> s = links();
        store.dropLinks("off");
        assertEquals(LinuxBrowser.Link.OFF, store.tryLink(s.get(0), TUNNEL, NOW, "x").link);
        assertEquals(0, store.tokenCount());
    }

    @Test
    public void aWrongOrMalformedSecret_letsNobodyIn() {
        links();
        assertEquals(LinuxBrowser.Link.WRONG, store.tryLink("A".repeat(43), TUNNEL, NOW, "x").link);
        assertEquals(LinuxBrowser.Link.WRONG, store.tryLink("short", TUNNEL, NOW, "x").link);
        assertEquals(LinuxBrowser.Link.WRONG, store.tryLink(null, TUNNEL, NOW, "x").link);
        assertEquals(LinuxBrowser.Link.WRONG, store.tryLink("a b".repeat(15), TUNNEL, NOW, "x").link);
        assertEquals("the six-digit code is not a link", LinuxBrowser.Link.WRONG,
                store.tryLink(store.code(), TUNNEL, NOW, "x").link);
        assertEquals(0, store.tokenCount());
        assertEquals("no budget a stranger can use up: the links still work", 2, store.linkCount(NOW));
    }

    @Test
    public void manyWrongSecrets_doNotUseUpTheOwnersLink() {
        List<String> s = links();
        for (int i = 0; i < 200; i++) store.tryLink(String.format("%043d", i), TUNNEL, NOW, "x");
        assertEquals(LinuxBrowser.Link.OK, store.tryLink(s.get(0), TUNNEL, NOW, "x").link);
    }

    @Test
    public void thePhoneKeepsOnlyHashes() throws Exception {
        List<String> s = links();
        store.tryLink(s.get(0), TUNNEL, NOW, "x");
        StringBuilder all = new StringBuilder();
        for (File f : dir.listFiles()) {
            if (f.isFile()) all.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        for (String secret : s) {
            assertFalse("no secret on disk", all.toString().contains(secret));
            assertTrue("its hash instead", all.toString().contains(LinuxBrowser.hash(secret)));
        }
    }

    @Test
    public void theDeadList_staysShort() {
        for (int i = 0; i < 100; i++) email(NOW + i);
        assertEquals("only the last email's", 2, store.linkCount(NOW + 100));
        String dead = new String(readOr(new File(dir, "links-dead")), StandardCharsets.UTF_8);
        assertTrue(dead.split("\n").length <= 64);
    }

    @Test
    public void aCodeStillWorks_besideTheLinks() {
        links();
        store.openPairing(NOW);
        LinuxBrowser.Tried t = store.tryCode(store.code(), NOW + 1000, "Chrome");
        assertEquals(LinuxBrowser.Pair.OK, t.pair);
        assertEquals("a code's browser leaves the links as they were", 2, store.linkCount(NOW + 1000));
    }

    private static byte[] readOr(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
