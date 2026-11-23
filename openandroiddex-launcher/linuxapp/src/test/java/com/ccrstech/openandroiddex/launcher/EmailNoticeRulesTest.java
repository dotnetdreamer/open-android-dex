package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * When "Email me when an address changes" sends ({@link EmailNotice#observe},
 * {@link EmailNotice#plan}): what counts as the same information, waiting
 * for things to settle, waiting for the tunnel's address and for no longer
 * than five minutes, a switch turned off and on again, the Wi-Fi gone for a
 * moment, failures that pass and those that do not, and when LinuxOnDroid is
 * asked back. Plain JUnit with a clock of its own: no Android, no waiting.
 */
public class EmailNoticeRulesTest {

    private static final String A = "https://quiet-river-fox.trycloudflare.com";
    private static final String B = "https://bright-lake-owl.trycloudflare.com";
    private static final long T0 = 1_800_000_000_000L;

    /** A phone as the rules see it. */
    static final class Phone implements EmailNotice.Env {
        long now = T0;
        boolean linux = true;
        boolean phone = true;
        boolean anywhere = true;
        boolean remote = true;
        boolean alive = true;
        String door = "listening 7443";
        List<String> lan = new ArrayList<>(Arrays.asList("wifi 192.168.2.25"));
        String tunnel = A;
        String name = "Galaxy S25 Ultra";
        int computers = 1;
        int boot = 7;
        long updated = 100;

        @Override public long now() { return now; }
        @Override public boolean linuxOn() { return linux; }
        @Override public boolean phoneOn() { return phone; }
        @Override public boolean anywhereOn() { return anywhere; }
        @Override public boolean remoteOn() { return remote; }
        @Override public boolean linuxAlive() { return alive; }
        @Override public String doorState() { return door; }
        @Override public List<String> lan() { return lan; }
        @Override public String tunnel() { return tunnel; }
        @Override public String phoneName() { return name; }
        @Override public int computers() { return computers; }
        @Override public int bootCount() { return boot; }
        @Override public long updatedAt() { return updated; }
    }

    private final Phone p = new Phone();
    private final EmailNotice.State st = new EmailNotice.State();

    private EmailNotice.Reach look() {
        return EmailNotice.Reach.read(p);
    }

    /** What the job would do now, after looking. */
    private long step(String cause) {
        EmailNotice.Reach r = look();
        EmailNotice.observe(st, r, p.now, cause);
        return EmailNotice.plan(st, r, p.now);
    }

    /** As the job records an email that went. */
    private void sent() {
        EmailNotice.Reach r = look();
        st.sentKey = r.key();
        st.sentLine = r.line();
        st.sentAt = p.now;
        st.causes = "";
        st.changeAt = 0;
    }

    private void after(long ms) {
        p.now += ms;
    }

    // ── the same information ──

    @Test
    public void theKey_isWhatAPersonUses_andNothingElse() {
        String k = look().key();
        p.name = "Renamed";
        p.computers = 3;
        p.boot = 8;
        assertEquals("a rename, a computer added, a restart that changed nothing", k, look().key());
        p.tunnel = B;
        assertNotEquals("a new tunnel address", k, look().key());
        p.tunnel = A;
        p.lan = Arrays.asList("wifi 192.168.2.77");
        assertNotEquals("a new Wi-Fi address", k, look().key());
    }

    @Test
    public void theKey_leavesOutWhatIsOff() {
        p.linux = false;
        p.phone = false;
        String k = look().key();
        p.lan = Arrays.asList("wifi 10.0.0.9");
        p.tunnel = B;
        assertEquals("nothing on: the Wi-Fi and the tunnel do not matter", k, look().key());
        p.phone = true;
        p.anywhere = false;
        String k2 = look().key();
        p.tunnel = A;
        assertEquals("From anywhere off: the tunnel does not matter", k2, look().key());
    }

    @Test
    public void aLineReadsBackAsTheSameLook() {
        EmailNotice.Reach r = look();
        EmailNotice.Reach back = EmailNotice.Reach.parse(r.line());
        assertEquals(r.key(), back.key());
        assertEquals(r.port, back.port);
        assertEquals(r.lan, back.lan);
    }

    @Test
    public void aStaleTunnelAddress_isNotBelieved() {
        // :linux is not running (after an update Android never said a word of):
        // the address left in the file is dead, and so is the door.
        p.alive = false;
        EmailNotice.Reach r = look();
        assertEquals("down", r.door);
        assertEquals("", r.tunnel);
        assertFalse(r.settled());
        p.alive = true;
        p.tunnel = "https://evil.example.com";
        assertEquals("only Cloudflare's quick tunnel names", "", look().tunnel);
    }

    @Test
    public void whatSettled_means() {
        assertTrue(look().settled());
        p.tunnel = "";
        assertFalse("From anywhere on and no address yet", look().settled());
        p.anywhere = false;
        assertTrue(look().settled());
        p.door = "off";
        assertFalse("the door starting", look().settled());
        p.door = "failed port 7443";
        assertTrue("another app has the port: said, not waited for", look().settled());
        assertEquals("port", look().door);
        assertTrue("no Wi-Fi addresses when the door is not up", look().lan.isEmpty());
    }

    @Test
    public void linksGoWhereABrowserCanUseThem() {
        assertEquals(Arrays.asList("quiet-river-fox.trycloudflare.com", "192.168.2.25"), look().linkHosts());
        p.door = "failed port 7443";
        assertTrue("another app's port: no links at all", look().linkHosts().isEmpty());
        p.door = "listening 7443";
        p.anywhere = false;
        assertEquals(Arrays.asList("192.168.2.25"), look().linkHosts());
    }

    // ── when ──

    @Test
    public void aChange_waitsForTheQuiet_thenSends_once() {
        step(null);
        sent();
        assertEquals(EmailNotice.IDLE, step(null));
        p.tunnel = B;
        assertEquals(EmailNotice.QUIET_MS, step("tunnel"));
        after(10_000);
        assertEquals(EmailNotice.QUIET_MS - 10_000, step(null));
        after(EmailNotice.QUIET_MS - 10_000);
        assertEquals(EmailNotice.SEND, step(null));
        assertTrue("why it changed is kept for the email", st.causes.contains("tunnel"));
        sent();
        assertEquals("never the same information twice", EmailNotice.IDLE, step(null));
        after(3_600_000);
        assertEquals(EmailNotice.IDLE, step("check"));
    }

    @Test
    public void anotherChange_whileWaiting_startsTheQuietAgain() {
        step(null);
        sent();
        p.tunnel = B;
        step("tunnel");
        after(40_000);
        p.lan = Arrays.asList("wifi 192.168.2.77");
        assertEquals("still for 45 s from the last change", EmailNotice.QUIET_MS, step("network"));
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.SEND, step(null));
    }

    @Test
    public void aSwitchOffAndOnAgain_byMistake_sendsNothing() {
        step(null);
        sent();
        p.linux = false;
        assertTrue(step("switch") > 0);
        after(8_000);
        p.linux = true;
        assertEquals("back to what the last email said", EmailNotice.IDLE, step("switch"));
        assertEquals("nothing left to say", 0, st.changeAt);
        assertEquals("", st.causes);
        after(10 * 60_000);
        assertEquals(EmailNotice.IDLE, step(null));
    }

    @Test
    public void afterARestart_theEmailWaitsForTheTunnelsAddress() {
        step(null);
        sent();
        // The phone restarted: no callback needed, the boot count says so.
        p.boot = 8;
        p.alive = false;
        long w = step(null);
        assertTrue(w > 0);
        assertTrue(st.causes.contains("boot"));
        // LinuxOnDroid is back, the tunnel still asking Cloudflare.
        after(20_000);
        p.alive = true;
        p.tunnel = "";
        step(null);
        after(EmailNotice.QUIET_MS);
        assertEquals("quiet, but the address is not there: looked at again shortly", EmailNotice.SETTLE_POLL_MS, step(null));
        after(30_000);
        p.tunnel = B;
        assertEquals(EmailNotice.QUIET_MS, step("tunnel"));
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.SEND, step(null));
        assertTrue("the restart is the reason", st.causes.startsWith("boot"));
    }

    @Test
    public void noAddressFromCloudflare_isSaidAfterFiveMinutes() {
        step(null);
        sent();
        p.tunnel = "";
        step("tunnel");
        long waited = 0;
        long next;
        while ((next = step(null)) > 0) {
            after(next);
            waited += next;
            assertTrue("no endless wait", waited <= EmailNotice.MAX_WAIT_MS + EmailNotice.SETTLE_POLL_MS);
        }
        assertEquals(EmailNotice.SEND, next);
        assertTrue("about five minutes: " + waited, waited >= EmailNotice.MAX_WAIT_MS - 1 && waited <= EmailNotice.MAX_WAIT_MS + 1);
    }

    @Test
    public void offTheWifiForAMoment_sendsNothing() {
        step(null);
        sent();
        p.lan = new ArrayList<>();
        assertEquals("off the Wi-Fi: waited for longer", EmailNotice.QUIET_WIFI_LOST_MS, step("network"));
        after(90_000);
        p.lan = Arrays.asList("wifi 192.168.2.25");
        assertEquals(EmailNotice.IDLE, step("network"));
    }

    @Test
    public void offTheWifiForLonger_isSaid() {
        step(null);
        sent();
        p.lan = new ArrayList<>();
        step("network");
        after(EmailNotice.QUIET_WIFI_LOST_MS);
        assertEquals(EmailNotice.SEND, step(null));
    }

    @Test
    public void theFirstLook_isAChangeFromNothing() {
        // Never sent (on, tested, nothing recorded yet): the baseline goes after the quiet.
        assertEquals(EmailNotice.QUIET_MS, step(null));
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.SEND, step(null));
    }

    // ── failures ──

    @Test
    public void aFailureThatPasses_isTriedLaterAndLater() {
        assertEquals(60_000, EmailNotice.backoff(1));
        assertEquals(120_000, EmailNotice.backoff(2));
        assertEquals(240_000, EmailNotice.backoff(3));
        assertEquals(480_000, EmailNotice.backoff(4));
        assertEquals(960_000, EmailNotice.backoff(5));
        assertEquals(EmailNotice.RETRY_MAX_MS, EmailNotice.backoff(6));
        assertEquals(EmailNotice.RETRY_MAX_MS, EmailNotice.backoff(60));
        step(null);
        sent();
        p.tunnel = B;
        step("tunnel");
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.SEND, step(null));
        st.attempts = 1;
        st.retryAt = p.now + EmailNotice.backoff(1);
        assertEquals(EmailNotice.backoff(1), step(null));
        after(EmailNotice.backoff(1));
        assertEquals(EmailNotice.SEND, step(null));
    }

    @Test
    public void aFailureOnlyNewSettingsFix_waitsForThem() {
        step(null);
        sent();
        p.tunnel = B;
        step("tunnel");
        after(EmailNotice.QUIET_MS);
        st.blocked = true;
        assertEquals(EmailNotice.IDLE, step(null));
        after(24 * 3_600_000L);
        assertEquals("no retries against a refused password", EmailNotice.IDLE, step(null));
        st.unblock();
        assertEquals(EmailNotice.SEND, step(null));
    }

    @Test
    public void aSendUnderWay_isNotSentAgain_unlessItsProcessDied() {
        st.sendingAt = p.now;
        assertTrue(st.sending(p.now + 60_000));
        assertFalse("three minutes without a word: gone", st.sending(p.now + EmailNotice.SENDING_STALE_MS));
    }

    // ── causes, and asking LinuxOnDroid back ──

    @Test
    public void anUpdate_isSeen_withoutACallback() {
        step(null);
        sent();
        p.updated = 200;
        p.tunnel = B;
        step(null);
        assertTrue(st.causes.contains("update"));
    }

    @Test
    public void causes_areKeptOnce_inTheOrderTheyCame() {
        EmailNotice.addCause(st, "boot");
        EmailNotice.addCause(st, "tunnel");
        EmailNotice.addCause(st, "boot");
        assertEquals("boot,tunnel", st.causes);
    }

    @Test
    public void linuxOnDroidIsAskedBack_whenItShouldRunAndDoesNot() {
        EmailNotice.Reach r = look();
        assertFalse("running: left alone", EmailNotice.needsResume(st, r, p.now));
        p.alive = false;
        r = look();
        assertTrue(EmailNotice.needsResume(st, r, p.now));
        st.resumedAt = p.now;
        assertFalse("not more than every two minutes", EmailNotice.needsResume(st, r, p.now + 60_000));
        assertTrue(EmailNotice.needsResume(st, r, p.now + EmailNotice.RESUME_EVERY_MS));
        // Nothing wanted: nothing to bring back.
        p.linux = false;
        p.phone = false;
        p.remote = false;
        assertFalse(EmailNotice.needsResume(new EmailNotice.State(), look(), p.now));
    }

    @Test
    public void aDoorThatStaysDown_whileLinuxOnDroidRuns_isAskedBackAfterAWhile() {
        step(null);
        sent();
        p.door = "off";
        step(null);
        EmailNotice.Reach r = look();
        assertFalse("starting: given time", EmailNotice.needsResume(st, r, p.now + 60_000));
        assertTrue(EmailNotice.needsResume(st, r, p.now + EmailNotice.MAX_WAIT_MS));
    }
}
