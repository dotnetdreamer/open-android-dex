package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * "Email me when an address changes" end to end, off a phone: the job's
 * step, the files both processes share, JobScheduler, the sealed password,
 * the sign-in links in the door's store, and the email as it reaches a
 * stand-in mail server on this computer ({@link FakeSmtp}, STARTTLS, a
 * certificate trusted only here). The clock is the test's: minutes pass in
 * no time. Nothing leaves this computer; every address is example.com's.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, shadows = {BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class EmailNoticeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PASSWORD = "abcd efgh ijkl mnop";
    private static final String A = "https://quiet-river-fox.trycloudflare.com";
    private static final String B = "https://bright-lake-owl.trycloudflare.com";
    private static final String WIFI = "192.168.2.25";

    private Context app;
    private FakeSmtp server;
    private final long[] now = {1_800_000_000_000L};
    private TestSealer sealer;

    /**
     * AES-GCM with a key of the test's: what the Keystore does on a phone, as
     * far as the files can tell. Forgotten, the key is replaced, as the
     * Keystore makes a new one at the next seal: what was sealed before no
     * longer opens.
     */
    static final class TestSealer implements EmailSecret.Sealer {
        private SecretKey key;
        boolean forgotten;

        TestSealer() throws Exception {
            key = newKey();
        }

        private static SecretKey newKey() throws Exception {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            return g.generateKey();
        }

        @Override
        public byte[] seal(byte[] plain) throws Exception {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] body = c.doFinal(plain);
            byte[] out = new byte[12 + body.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(body, 0, out, 12, body.length);
            return out;
        }

        @Override
        public byte[] open(byte[] sealed) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(sealed, 12)));
            return c.doFinal(sealed, 12, sealed.length - 12);
        }

        @Override
        public void forget() {
            forgotten = true;
            try {
                key = newKey();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Before
    public void phone() throws Exception {
        app = RuntimeEnvironment.getApplication();
        for (File d : new File[]{EmailNotice.dir(app), LinuxService.browser(app).dir(), PhoneRemote.dir(app)}) wipe(d);
        sealer = new TestSealer();
        EmailSecret.sealer = sealer;
        EmailNotice.clock = () -> now[0];
        EmailNotice.inline = true;
        EmailNotice.timing = new EmailSmtp.Timing(3000, 3000, 15000);
        server = new FakeSmtp(FakeSmtp.Mode.STARTTLS, tmp.newFolder(), Collections.<String>emptyList());
        EmailNotice.tls = server.trusting();
        Settings.Global.putString(app.getContentResolver(), Settings.Global.DEVICE_NAME, "Galaxy S25 Ultra");
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        // The tunnel's binary, so "From anywhere" is offered (PhoneTunnel.available).
        File lib = tmp.newFolder("lib");
        new File(lib, "libcloudflared.so").createNewFile();
        app.getApplicationInfo().nativeLibraryDir = lib.getAbsolutePath();
        linuxRunning(true);
        BrowserAddressShadow.wifi(WIFI);
        // Linux in a browser, Remote desktop and From anywhere on; the door listening; the tunnel's address.
        LinuxService.browser(app).setOn(true);
        PhoneRemote.setEnabled(app, true);
        PhoneTunnel.setSwitchedOn(app, true);
        LinuxService.browser(app).setState("listening 7443");
        tunnel(A);
        EmailNotice.Prefs s = new EmailNotice.Prefs();
        s.on = true;
        s.provider = "other";
        s.to = "reader@example.com";
        s.user = "owner@example.com";
        s.host = "localhost";
        s.port = server.port();
        s.security = EmailSmtp.Security.STARTTLS;
        s.save(app);
        assertTrue(EmailNotice.savePassword(app, PASSWORD));
        drainStarts();
    }

    @After
    public void done() {
        if (server != null) server.close();
        EmailNotice.clock = System::currentTimeMillis;
        EmailNotice.inline = false;
        EmailNotice.tls = null;
        EmailNotice.timing = EmailSmtp.Timing.PHONE;
        EmailSecret.sealer = new EmailSecret.Keystore();
    }

    private static void wipe(File d) {
        File[] files = d.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private void linuxRunning(boolean on) {
        ActivityManager am = app.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> list = new ArrayList<>();
        ActivityManager.RunningAppProcessInfo main = new ActivityManager.RunningAppProcessInfo();
        main.processName = app.getPackageName();
        list.add(main);
        if (on) {
            ActivityManager.RunningAppProcessInfo linux = new ActivityManager.RunningAppProcessInfo();
            linux.processName = app.getPackageName() + ":linux";
            list.add(linux);
        }
        shadowOf(am).setProcesses(list);
    }

    private void tunnel(String url) {
        File f = new File(PhoneRemote.dir(app), "tunnel-address");
        if (url == null) {
            f.delete();
        } else {
            PhoneRemote.write(f, url);
        }
    }

    private void after(long ms) {
        now[0] += ms;
    }

    /** The clock moved, the job run: how many times LinuxOnDroid was asked to start meanwhile. */
    private int startsAfter(long ms) {
        after(ms);
        job();
        return drainStarts().size();
    }

    private List<Intent> drainStarts() {
        List<Intent> out = new ArrayList<>();
        Intent i;
        while ((i = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null) out.add(i);
        return out;
    }

    private JobInfo pending(int id) {
        return app.getSystemService(JobScheduler.class).getPendingJob(id);
    }

    /** The baseline every scenario starts from: a test email that went, which is also what lets the emails start. */
    private MailParse baseline() {
        EmailNotice.Outcome out = EmailNotice.sendTest(app);
        assertTrue("the test email went: " + out.fault + " " + out.said, out.ok);
        assertEquals(1, server.mails.size());
        MailParse m = MailParse.of(server.mails.get(0).data);
        server.mails.clear();
        return m;
    }

    /** The job, run as Android runs it: until it has nothing to do now (a wait, or idle). */
    private long job() {
        return EmailNotice.step(app);
    }

    private static List<String> secrets(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("signin\\.html#k=([A-Za-z0-9_-]{43})&to=").matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ── the test email ──

    @Test
    public void theTestEmail_isTheRealEmail_withASignInLinkForEachAddress() throws Exception {
        MailParse m = baseline();
        assertEquals("Galaxy S25 Ultra: a test email from LinuxOnDroid", m.header("subject"));
        assertEquals("\"LinuxOnDroid on Galaxy S25 Ultra\" <owner@example.com>", m.header("from"));
        assertEquals("<reader@example.com>", m.header("to"));
        assertTrue("signed in over TLS", server.loginOverTls);
        assertEquals(PASSWORD, server.password);
        String t = m.text;
        assertTrue(t, t.contains(A + "/phone.html"));
        assertTrue(t, t.contains("https://" + WIFI + ":7443  ("));
        assertTrue(t, t.contains("https://" + WIFI + ":7443/phone.html"));
        assertTrue(t, t.contains(A + "/signin.html#k="));
        assertTrue(t, t.contains("https://" + WIFI + ":7443/signin.html#k="));
        assertTrue(t, t.contains("&to=linux"));
        assertTrue(t, t.contains(app.getString(R.string.em_test_banner)));
        assertTrue(t, t.contains(app.getString(R.string.em_how_link)));
        assertTrue(t, t.contains(app.getString(R.string.em_private)));
        assertTrue(m.html, m.html.contains("href=\"" + A + "/signin.html#k="));
        assertTrue("an & in a link is &amp; in HTML", m.html.contains("&amp;to=phone\""));
        assertFalse("no raw & in an href", m.html.matches("(?s).*href=\"[^\"]*&to=.*"));
        // One secret for the tunnel, one for the Wi-Fi address (Linux's and the phone's page share it).
        List<String> s = secrets(t);
        assertEquals(3, s.size());
        assertEquals(s.get(1), s.get(2));
        LinuxBrowser door = LinuxService.browser(app);
        assertEquals(2, door.linkCount(now[0]));
        assertEquals(LinuxBrowser.Link.OK, door.tryLink(s.get(0), "quiet-river-fox.trycloudflare.com", now[0], "x").link);
        assertEquals(LinuxBrowser.Link.OK, door.tryLink(s.get(1), WIFI, now[0], "x").link);
        // The settings proved: emails go by themselves from now on, and this one counts.
        EmailNotice.Prefs p = EmailNotice.Prefs.load(app);
        assertTrue(p.tested(app));
        assertTrue(p.active(app));
        assertFalse("the password is not on disk as it is",
                new String(Files.readAllBytes(new File(EmailNotice.dir(app), "password").toPath()), StandardCharsets.UTF_8)
                        .contains(PASSWORD));
        for (File f : EmailNotice.dir(app).listFiles()) {
            assertFalse(f.getName(), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains(PASSWORD));
        }
        assertNotNull("the look every quarter of an hour", pending(EmailNotice.JOB_WATCH));
        assertTrue(pending(EmailNotice.JOB_WATCH).isPersisted());
        assertEquals(EmailNotice.WATCH_EVERY_MS, pending(EmailNotice.JOB_WATCH).getIntervalMillis());
    }

    // ── when an address changes ──

    @Test
    public void aNewTunnelAddress_waitsForTheQuiet_thenOneEmail_neverTwo() throws Exception {
        List<String> before = secrets(baseline().text);
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        JobInfo j = pending(EmailNotice.JOB_STEP);
        assertNotNull("the job, for when things are quiet", j);
        assertEquals(EmailNotice.QUIET_MS, j.getMinLatencyMillis());
        assertEquals("only with a network", JobInfo.NETWORK_TYPE_ANY, j.getNetworkType());
        assertTrue("and kept through a restart", j.isPersisted());
        after(10_000);
        assertEquals(EmailNotice.QUIET_MS - 10_000, job());
        assertTrue("nothing before the quiet", server.mails.isEmpty());
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.IDLE, job());
        assertEquals(1, server.mails.size());
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertEquals("Galaxy S25 Ultra: a new address from anywhere", m.header("subject"));
        assertTrue(m.text, m.text.contains(app.getString(R.string.em_chg_anywhere_new)));
        assertTrue(m.text, m.text.contains(app.getString(R.string.em_why, app.getString(R.string.em_cause_tunnel))));
        assertTrue(m.text, m.text.contains(B + "/phone.html"));
        assertFalse(m.text, m.text.contains(A));
        assertTrue(m.text, m.text.contains(app.getString(R.string.em_how_new_address)));
        LinuxBrowser door = LinuxService.browser(app);
        assertEquals("the last email's links are replaced", LinuxBrowser.Link.REPLACED,
                door.tryLink(before.get(0), "quiet-river-fox.trycloudflare.com", now[0], "x").link);
        // Never the same information twice: looked at again, and again an hour later.
        EmailNotice.poke(app, "check");
        assertEquals(EmailNotice.IDLE, job());
        after(3_600_000);
        assertEquals(EmailNotice.IDLE, job());
        assertEquals(1, server.mails.size());
    }

    @Test
    public void aSwitchTurnedOffAndOnByMistake_sendsNothing() {
        baseline();
        LinuxService.browser(app).setOn(false);
        EmailNotice.poke(app, null);
        after(8_000);
        LinuxService.browser(app).setOn(true);
        EmailNotice.poke(app, null);
        after(10 * 60_000);
        assertEquals(EmailNotice.IDLE, job());
        assertTrue(server.mails.isEmpty());
    }

    @Test
    public void fromAnywhere_offAndOnAgain_oneEmail_withTheNewAddress() {
        baseline();
        PhoneTunnel.setSwitchedOn(app, false);
        tunnel(null);
        EmailNotice.poke(app, "tunnel");
        after(12_000);
        PhoneTunnel.setSwitchedOn(app, true);
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        job();
        assertEquals(1, server.mails.size());
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertEquals("Galaxy S25 Ultra: a new address from anywhere", m.header("subject"));
        assertTrue(m.text.contains(B + "/phone.html"));
    }

    @Test
    public void aSecondNetworksAddress_isSaidAsWhatItIs_andTheWifisAsTheWifis() {
        baseline();
        BrowserAddressShadow.list = new ArrayList<>(Arrays.asList(new LinuxBrowser.Address(WIFI, "wifi"),
                new LinuxBrowser.Address("100.101.7.12", "vpn")));
        EmailNotice.poke(app, "network");
        after(EmailNotice.QUIET_MS);
        job();
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertTrue(m.text, m.text.contains(app.getString(R.string.em_chg_lan_new)));
        assertFalse("the Wi-Fi's address did not change", m.text.contains(app.getString(R.string.em_chg_wifi_new)));
        assertTrue(m.text.contains(app.getString(R.string.em_also_at, app.getString(R.string.bw_if_vpn)) + ": https://100.101.7.12:7443"));
        BrowserAddressShadow.wifi("192.168.2.80");
        EmailNotice.poke(app, "network");
        after(EmailNotice.QUIET_MS);
        job();
        MailParse n = MailParse.of(server.mails.get(1).data);
        assertEquals("Galaxy S25 Ultra: a new Wi-Fi address", n.header("subject"));
        assertTrue(n.text.contains(app.getString(R.string.em_chg_wifi_new)));
    }

    @Test
    public void aSwitchTurnedOff_isSaid_withNoReasonMadeUp() {
        baseline();
        PhoneRemote.setEnabled(app, false);
        EmailNotice.poke(app, null);
        after(EmailNotice.QUIET_MS);
        job();
        assertEquals(1, server.mails.size());
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertEquals("Galaxy S25 Ultra: Remote desktop is off", m.header("subject"));
        assertTrue(m.text.contains(app.getString(R.string.em_chg_phone_off)));
        assertFalse("a switch says why itself", m.text.contains("Why:"));
        assertFalse("the phone's screen is not offered any more", m.text.contains("/phone.html"));
        assertTrue("Linux in a browser still is", m.text.contains("https://" + WIFI + ":7443"));
    }

    // ── after a restart, with no callback at all ──

    @Test
    public void afterARestart_LinuxOnDroidIsAskedBack_andTheEmailWaitsForTheNewAddress() {
        baseline();
        // A restart (or an update) that never told the app: :linux is gone, its old address left in the file.
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        linuxRunning(false);
        EmailNotice.poke(app, null);
        after(EmailNotice.QUIET_MS);
        long w = job();
        assertTrue(w > 0);
        assertTrue("the old address is never emailed", server.mails.isEmpty());
        List<String> actions = new ArrayList<>();
        for (Intent i : drainStarts()) actions.add(String.valueOf(i.getAction()));
        assertTrue("LinuxOnDroid asked back, as after a boot: " + actions,
                actions.contains(LinuxService.ACTION_BROWSER_ON) || actions.contains(LinuxService.ACTION_REMOTE_RESUME));
        assertEquals("not asked again at the very next look", 0, startsAfter(10_000));
        assertTrue(EmailNotice.state(app).causes.contains("boot"));
        // Back: the door listening, the tunnel still asking Cloudflare.
        linuxRunning(true);
        tunnel(null);
        EmailNotice.poke(app, null);
        after(EmailNotice.QUIET_MS);
        assertEquals("quiet, the address not there yet", EmailNotice.SETTLE_POLL_MS, job());
        assertTrue(server.mails.isEmpty());
        after(20_000);
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        job();
        assertEquals("one email, with the new address", 1, server.mails.size());
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertTrue(m.text.contains(B + "/phone.html"));
        assertTrue(m.text, m.text.contains(app.getString(R.string.em_why, app.getString(R.string.em_cause_boot))));
        assertTrue(m.text.contains(B + "/signin.html#k="));
    }

    @Test
    public void noAddressFromCloudflare_theEmailSaysSo_afterFiveMinutes() {
        baseline();
        tunnel(null);
        EmailNotice.poke(app, "tunnel");
        long waited = 0;
        long next;
        while ((next = job()) > 0) {
            assertTrue(server.mails.isEmpty());
            after(next);
            waited += next;
            assertTrue(waited <= EmailNotice.MAX_WAIT_MS + EmailNotice.SETTLE_POLL_MS);
        }
        assertEquals(1, server.mails.size());
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertTrue(m.text.contains(app.getString(R.string.em_anywhere_wait)));
        assertTrue(m.text.contains(app.getString(R.string.em_chg_anywhere_gone)));
        assertFalse(m.text.contains(A));
        // The address comes later: one more email, with the address.
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        job();
        assertEquals(2, server.mails.size());
        assertTrue(MailParse.of(server.mails.get(1).data).text.contains(app.getString(R.string.em_chg_anywhere_ready)));
    }

    // ── failures ──

    @Test
    public void theServerUnreachable_isTriedAgainLater_andTheUnsentLinksStop() throws Exception {
        List<String> had = secrets(baseline().text);
        int port = server.port();
        server.close();
        BrowserAddressShadow.wifi("192.168.2.77");
        EmailNotice.poke(app, "network");
        after(EmailNotice.QUIET_MS);
        assertEquals("a minute, then again", EmailNotice.backoff(1), job());
        EmailNotice.State st = EmailNotice.state(app);
        assertEquals("NETWORK", st.fault);
        assertEquals(1, st.attempts);
        assertFalse(st.blocked);
        LinuxBrowser door = LinuxService.browser(app);
        assertEquals("only the links of the email that went still work: the person has that email", 2, door.linkCount(now[0]));
        assertEquals(LinuxBrowser.Link.OK, door.tryLink(had.get(0), "quiet-river-fox.trycloudflare.com", now[0], "x").link);
        after(30_000);
        assertEquals(30_000, job());
        // The server is back where it was.
        server = new FakeSmtp(FakeSmtp.Mode.STARTTLS, tmp.newFolder(), Collections.<String>emptyList(), port);
        EmailNotice.tls = server.trusting();
        after(30_000);
        assertEquals(EmailNotice.IDLE, job());
        assertEquals(1, server.mails.size());
        assertTrue(MailParse.of(server.mails.get(0).data).text.contains("https://192.168.2.77:7443"));
        st = EmailNotice.state(app);
        assertEquals(0, st.attempts);
        assertEquals("", st.fault);
    }

    @Test
    public void aRefusedPassword_isNotTriedAgain_untilTheSettingsChange_andThePhoneSaysSo() {
        baseline();
        server.authReply = "535 5.7.8 Username and Password not accepted";
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.IDLE, job());
        EmailNotice.State st = EmailNotice.state(app);
        assertTrue(st.blocked);
        assertEquals("LOGIN", st.fault);
        assertTrue(st.said.contains("5.7.8"));
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        Notification n = shadowOf(nm).getNotification(EmailNotice.NOTIF_ID);
        assertNotNull("said on the phone", n);
        assertEquals(app.getString(R.string.em_note_title), shadowOf(n).getContentTitle().toString());
        int tries = server.connections;
        after(24 * 3_600_000L);
        EmailNotice.poke(app, "check");
        assertEquals(EmailNotice.IDLE, job());
        assertEquals("no retries against a refused password", tries, server.connections);
        // New settings (a new app password): tried again, and the notification goes.
        server.authReply = "235 2.7.0 Accepted";
        EmailNotice.settingsChanged(app);
        assertEquals(EmailNotice.IDLE, job());
        assertEquals(1, server.mails.size());
        assertNull(shadowOf(nm).getNotification(EmailNotice.NOTIF_ID));
    }

    @Test
    public void aSendThatDiedHalfway_isNotWaitedForForever_butOneUnderWayIs() {
        baseline();
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        EmailNotice.State st = EmailNotice.state(app);
        st.sendingAt = now[0] - 30_000;
        st.save(app);
        assertEquals("another look leaves a send under way alone", EmailNotice.SETTLE_POLL_MS, job());
        assertTrue(server.mails.isEmpty());
        st = EmailNotice.state(app);
        st.sendingAt = now[0] - EmailNotice.SENDING_STALE_MS - 1000;
        st.save(app);
        job();
        assertEquals("the process that was sending is gone: sent again, once", 1, server.mails.size());
    }

    @Test
    public void theSavedPasswordNoLongerOpening_isSaid() {
        baseline();
        // The key is gone (a backup restored on another phone has no key either).
        sealer.forget();
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.IDLE, job());
        assertEquals("PASSWORD", EmailNotice.state(app).fault);
        assertTrue(EmailNotice.state(app).blocked);
        assertTrue(server.mails.isEmpty());
    }

    // ── what is offered, and what is not ──

    @Test
    public void linksOff_noLinkInTheEmail_andTheLastOnesStop() {
        List<String> before = secrets(baseline().text);
        EmailNotice.Prefs p = EmailNotice.Prefs.load(app);
        p.links = false;
        p.save(app);
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        job();
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertFalse(m.text.contains("signin.html"));
        assertFalse(m.html.contains("signin.html"));
        assertFalse(m.text.contains(app.getString(R.string.em_how_link)));
        assertTrue(m.text.contains(app.getString(R.string.em_how_code)));
        assertEquals(LinuxBrowser.Link.OFF,
                LinuxService.browser(app).tryLink(before.get(0), "quiet-river-fox.trycloudflare.com", now[0], "x").link);
    }

    @Test
    public void off_orUntested_sendsNothingByItself() {
        EmailNotice.Prefs p = EmailNotice.Prefs.load(app);
        assertFalse("not tested yet", p.active(app));
        EmailNotice.poke(app, "tunnel");
        assertNull(pending(EmailNotice.JOB_STEP));
        assertEquals(EmailNotice.IDLE, job());
        baseline();
        EmailNotice.setOn(app, false);
        assertNull("the jobs go with the switch", pending(EmailNotice.JOB_WATCH));
        tunnel(B);
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        assertEquals(EmailNotice.IDLE, job());
        assertTrue(server.mails.isEmpty());
        assertEquals("and the links", 0, LinuxService.browser(app).linkCount(now[0]));
        // New settings untest them: a new password needs a new test.
        EmailNotice.setOn(app, true);
        assertTrue(EmailNotice.Prefs.load(app).active(app));
        EmailNotice.savePassword(app, "a new one");
        assertFalse(EmailNotice.Prefs.load(app).active(app));
    }

    @Test
    public void aPortTakenByAnotherApp_isSaid_andNoAddressLeadsThere() {
        LinuxService.browser(app).setState("failed port 7443");
        EmailNotice.Outcome out = EmailNotice.sendTest(app);
        assertTrue(out.ok);
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertTrue(m.text, m.text.contains(app.getString(R.string.em_route_port, 7443)));
        assertFalse("not the tunnel, which leads to that app", m.text.contains(A));
        assertFalse(m.text.contains("signin.html"));
        assertEquals(0, LinuxService.browser(app).linkCount(now[0]));
    }

    @Test
    public void forget_leavesNothing() {
        baseline();
        EmailNotice.forget(app);
        assertTrue(sealer.forgotten);
        File[] left = EmailNotice.dir(app).listFiles((d, n) -> !n.equals(".lock"));
        assertEquals(0, left == null ? 0 : left.length);
        assertEquals(0, LinuxService.browser(app).linkCount(now[0]));
        assertNull(pending(EmailNotice.JOB_WATCH));
        assertFalse(EmailNotice.Prefs.load(app).on);
    }

    // ── the words ──

    @Test
    public void thePhonesName_inEveryPart_andNeverMarkupInTheHtml() {
        Settings.Global.putString(app.getContentResolver(), Settings.Global.DEVICE_NAME, "Tom's <S25> & \"Co\"");
        EmailNotice.Outcome out = EmailNotice.sendTest(app);
        assertTrue(out.ok);
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertEquals("Tom's <S25> & \"Co\": a test email from LinuxOnDroid", m.header("subject"));
        assertTrue(m.html.contains("Tom&#39;s &lt;S25&gt; &amp; &quot;Co&quot;"));
        assertFalse("nothing of the name is markup", m.html.contains("<S25>"));
        assertTrue(m.text.contains("Tom's <S25> & \"Co\""));
    }

    @Test
    @Config(qualifiers = "ar")
    public void inArabic_theEmailReadsRightToLeft_theAddressesLeftToRight() {
        EmailNotice.Outcome out = EmailNotice.sendTest(app);
        assertTrue(out.ok);
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertTrue(m.html.startsWith("<!doctype html><html lang=\"ar"));
        assertTrue(m.html.contains("dir=\"rtl\""));
        assertTrue("an address stays left to right", m.html.contains("<div dir=\"ltr\""));
        assertEquals(app.getString(R.string.em_subject, "Galaxy S25 Ultra", app.getString(R.string.em_head_test)),
                m.header("subject"));
        assertTrue(m.header("subject").contains("رسالة تجريبية"));
        assertTrue("the subject on the wire is encoded words", m.headers.get("subject").startsWith("=?UTF-8?B?"));
    }

    /**
     * The email after a restart, as files to look at, only when pictures are
     * asked for ({@code -PbrowserShots=<folder>}): its HTML and its text, the
     * secrets in it replaced, since the folder outlives the test.
     */
    private void writeRestartEmail(String name) throws Exception {
        String where = System.getProperty("browser.shots", "");
        if (where.isEmpty()) return;
        LinuxRemote.setEnabled(app, false);
        baseline();
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        tunnel(B);
        BrowserAddressShadow.list = new ArrayList<>(Arrays.asList(new LinuxBrowser.Address(WIFI, "wifi"),
                new LinuxBrowser.Address("100.101.7.12", "vpn")));
        EmailNotice.poke(app, "tunnel");
        after(EmailNotice.QUIET_MS);
        job();
        MailParse m = MailParse.of(server.mails.get(0).data);
        File dir = new File(where);
        dir.mkdirs();
        String html = m.html.replaceAll("#k=[A-Za-z0-9_-]{43}", "#k=SECRET");
        String text = m.text.replaceAll("#k=[A-Za-z0-9_-]{43}", "#k=SECRET");
        Files.write(new File(dir, name + ".html").toPath(), html.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, name + ".txt").toPath(), ("Subject: " + m.header("subject") + "\n\n" + text)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void emailAfterARestart_asFiles() throws Exception {
        writeRestartEmail("email-message-en");
    }

    @Test
    @Config(qualifiers = "ar")
    public void emailAfterARestart_asFiles_inArabic() throws Exception {
        writeRestartEmail("email-message-ar");
    }

    @Test
    @Config(qualifiers = "de")
    public void inGerman_theWordsAreGerman() {
        EmailNotice.Outcome out = EmailNotice.sendTest(app);
        assertTrue(out.ok);
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertEquals("Galaxy S25 Ultra: eine Test-E-Mail von LinuxOnDroid", m.header("subject"));
        assertTrue(m.text.contains("Ohne Code anmelden"));
        assertTrue(m.html.contains("lang=\"de"));
    }
}
