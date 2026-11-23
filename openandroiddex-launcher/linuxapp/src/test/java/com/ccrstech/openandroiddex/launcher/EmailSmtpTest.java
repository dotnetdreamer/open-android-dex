package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLSocketFactory;

/**
 * The SMTP client of "Email me when an address changes" ({@link EmailSmtp}),
 * against a stand-in server on this computer ({@link FakeSmtp}): SSL/TLS and
 * STARTTLS with a throwaway certificate trusted only here, AUTH PLAIN and
 * LOGIN, the message as a mail app reads it, and each way a send fails,
 * the password never on the wire in the clear. Plain JUnit: no Android, no
 * real mail server, nothing outside this computer.
 */
public class EmailSmtpTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeSmtp server;

    private static final String PASSWORD = "abcd efgh ijkl mnop";
    private static final EmailSmtp.Timing QUICK = new EmailSmtp.Timing(3000, 3000, 15000);

    @After
    public void stop() {
        if (server != null) server.close();
    }

    private FakeSmtp start(FakeSmtp.Mode mode, String... ips) throws Exception {
        server = new FakeSmtp(mode, tmp.newFolder(), Arrays.asList(ips));
        return server;
    }

    private EmailSmtp.Server to(String host, EmailSmtp.Security sec) {
        return new EmailSmtp.Server(host, server.port(), sec, "owner@example.com", PASSWORD);
    }

    private static EmailSmtp.Message message(String subject) {
        return new EmailSmtp.Message("LinuxOnDroid on Galaxy S25", "owner@example.com", "reader@example.com", subject,
                "Line one\nLine two über\n.a line that starts with a dot", "<p>Hello <b>there</b> صباح الخير</p>");
    }

    private EmailSmtp.Failure fails(EmailSmtp.Server s, SSLSocketFactory f, EmailSmtp.Timing t) {
        try {
            EmailSmtp.send(s, message("x"), f, t, System.currentTimeMillis());
        } catch (EmailSmtp.Failure e) {
            assertFalse("the password is never in what a failure says", e.getMessage().contains(PASSWORD));
            assertFalse(e.said.contains(PASSWORD));
            return e;
        }
        fail("the send should have failed");
        return null;
    }

    // ── what goes ──

    @Test
    public void ssl_signsInOverTls_andTheMessageReadsBack() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.auth = "PLAIN";
        EmailSmtp.send(to("localhost", EmailSmtp.Security.SSL), message("Galaxy S25: a new address from anywhere"),
                server.trusting(), QUICK, System.currentTimeMillis());
        assertEquals(1, server.mails.size());
        assertEquals("owner@example.com", server.user);
        assertEquals(PASSWORD, server.password);
        assertTrue("signed in over TLS", server.loginOverTls);
        assertFalse("nothing at all in the clear", server.inTheClear(""));
        FakeSmtp.Mail mail = server.mails.get(0);
        assertTrue(mail.tls);
        assertEquals("owner@example.com", mail.from);
        assertEquals("reader@example.com", mail.to);
        MailParse m = MailParse.of(mail.data);
        assertEquals("Galaxy S25: a new address from anywhere", m.header("subject"));
        assertEquals("\"LinuxOnDroid on Galaxy S25\" <owner@example.com>", m.header("from"));
        assertEquals("<reader@example.com>", m.header("to"));
        assertEquals("1.0", m.header("mime-version"));
        assertEquals("auto-generated", m.header("auto-submitted"));
        assertTrue(m.header("message-id").matches("<[0-9a-f]{32}@example\\.com>"));
        assertTrue(m.header("date"), m.header("date").matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"));
        assertTrue(m.header("content-type").startsWith("multipart/alternative; boundary="));
        assertEquals("Line one\r\nLine two über\r\n.a line that starts with a dot", m.text);
        assertEquals("<p>Hello <b>there</b> صباح الخير</p>", m.html);
        for (String line : m.raw.split("\r\n")) {
            assertTrue("a line of at most 998 characters", line.length() <= 998);
            assertTrue("nothing but ASCII on the wire: " + line, line.chars().allMatch(c -> c < 128));
        }
    }

    @Test
    public void starttls_turnsTlsOnBeforeTheLogin_andLoginWorksToo() throws Exception {
        start(FakeSmtp.Mode.STARTTLS);
        server.auth = "LOGIN";
        EmailSmtp.send(to("localhost", EmailSmtp.Security.STARTTLS), message("Hello"), server.trusting(), QUICK,
                System.currentTimeMillis());
        assertEquals(1, server.mails.size());
        assertEquals(PASSWORD, server.password);
        assertTrue(server.loginOverTls);
        assertTrue(server.mails.get(0).tls);
        // In the clear: the greeting's EHLO and STARTTLS, and nothing more.
        List<String> clear = new java.util.ArrayList<>();
        synchronized (server.lines) {
            for (String l : server.lines) if (l.startsWith("plain|")) clear.add(l.substring(6));
        }
        assertEquals(2, clear.size());
        assertTrue(clear.get(0).startsWith("EHLO ["));
        assertEquals("STARTTLS", clear.get(1));
        String b64 = Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8));
        assertFalse(server.inTheClear(b64));
        assertFalse(server.inTheClear("AUTH"));
    }

    @Test
    public void plainWithInitialResponseRefused_answersTheQuestionInstead() throws Exception {
        // A server that asks for the PLAIN answer in a second step (334).
        start(FakeSmtp.Mode.SSL);
        server.auth = "PLAIN";
        server.plainAsks = true;
        EmailSmtp.send(to("localhost", EmailSmtp.Security.SSL), message("x"), server.trusting(), QUICK,
                System.currentTimeMillis());
        assertEquals(PASSWORD, server.password);
        assertEquals(1, server.mails.size());
    }

    // ── what is refused before the password goes ──

    @Test
    public void noStarttlsOffered_nothingIsSent_notEvenTheName() throws Exception {
        start(FakeSmtp.Mode.NO_TLS);
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.STARTTLS), server.trusting(), QUICK);
        assertEquals(EmailSmtp.Fault.NO_TLS, f.fault);
        assertFalse(f.fault.passing);
        assertFalse("no login in the clear", server.saw("AUTH"));
        assertFalse(server.saw("MAIL"));
        assertNull(server.password);
        assertTrue(server.mails.isEmpty());
    }

    @Test
    public void untrustedCertificate_isRefused_beforeTheLogin() throws Exception {
        start(FakeSmtp.Mode.SSL);
        // The platform's authorities: they have never heard of this certificate.
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.SSL), (SSLSocketFactory) SSLSocketFactory.getDefault(), QUICK);
        assertEquals(EmailSmtp.Fault.CERTIFICATE, f.fault);
        assertNull(server.password);
        assertFalse(server.saw("AUTH"));
    }

    @Test
    public void untrustedCertificate_afterStarttls_isRefused_beforeTheLogin() throws Exception {
        start(FakeSmtp.Mode.STARTTLS);
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.STARTTLS), (SSLSocketFactory) SSLSocketFactory.getDefault(), QUICK);
        assertEquals(EmailSmtp.Fault.CERTIFICATE, f.fault);
        assertNull(server.password);
        assertFalse(server.saw("AUTH"));
    }

    @Test
    public void certificateForAnotherName_isRefused() throws Exception {
        // Trusted, but for "localhost" only: asked for as 127.0.0.1, it names another server.
        start(FakeSmtp.Mode.SSL);
        EmailSmtp.Failure f = fails(to("127.0.0.1", EmailSmtp.Security.SSL), server.trusting(), QUICK);
        assertEquals(EmailSmtp.Fault.CERTIFICATE, f.fault);
        assertNull(server.password);
    }

    @Test
    public void certificateNamingTheAddress_isTakenForTheAddress() throws Exception {
        start(FakeSmtp.Mode.SSL, "127.0.0.1");
        EmailSmtp.send(to("127.0.0.1", EmailSmtp.Security.SSL), message("x"), server.trusting(), QUICK,
                System.currentTimeMillis());
        assertEquals(1, server.mails.size());
    }

    @Test
    public void sslAgainstAStarttlsPort_saysTheSecurityDoesNotMatch() throws Exception {
        start(FakeSmtp.Mode.STARTTLS);
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK);
        assertEquals("from " + f.getCause(), EmailSmtp.Fault.SECURITY, f.fault);
        assertNull(server.password);
    }

    // ── what the server says ──

    @Test
    public void refusedLogin_isSaidInTheServersWords_andNothingFollows() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.authReply = "535 5.7.8 Username and Password not accepted. For more information, go to"
                + "\r\n535 5.7.8 https://support.example.com/mail/?p=BadCredentials";
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK);
        assertEquals(EmailSmtp.Fault.LOGIN, f.fault);
        assertTrue(f.said, f.said.startsWith("535 5.7.8 Username and Password not accepted."));
        assertFalse(server.saw("MAIL FROM"));
    }

    @Test
    public void normalPasswordAtGmail_saysAnAppPasswordIsNeeded() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.authReply = "534-5.7.9 Application-specific password required. For more information, go to\r\n"
                + "534 5.7.9 https://support.example.com/mail/?p=InvalidSecondFactor";
        assertEquals(EmailSmtp.Fault.APP_PASSWORD, fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK).fault);
    }

    @Test
    public void outlookWithoutBasicAuth_saysAppPasswordsAreOff() throws Exception {
        start(FakeSmtp.Mode.STARTTLS);
        server.authReply = "535 5.7.139 Authentication unsuccessful, basic authentication is disabled.";
        assertEquals(EmailSmtp.Fault.BASIC_OFF,
                fails(to("localhost", EmailSmtp.Security.STARTTLS), server.trusting(), QUICK).fault);
    }

    @Test
    public void refusedRecipient() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.rcptReply = "550 5.1.1 The email account that you tried to reach does not exist";
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK);
        assertEquals(EmailSmtp.Fault.RECIPIENT, f.fault);
        assertFalse(f.fault.passing);
        assertTrue(server.mails.isEmpty());
    }

    @Test
    public void refusedMessage() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.dataReply = "554 5.7.1 Message rejected as spam";
        assertEquals(EmailSmtp.Fault.REFUSED, fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK).fault);
    }

    @Test
    public void busyServer_isAFailureThatPasses() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.mailReply = "451 4.3.0 Mail server temporarily rejected message";
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK);
        assertEquals(EmailSmtp.Fault.BUSY, f.fault);
        assertTrue("tried again later", f.fault.passing);
    }

    @Test
    public void serverOfferingNoPasswordLogin_isSaid() throws Exception {
        start(FakeSmtp.Mode.SSL);
        server.auth = "XOAUTH2 OAUTHBEARER";
        assertEquals(EmailSmtp.Fault.NO_LOGIN, fails(to("localhost", EmailSmtp.Security.SSL), server.trusting(), QUICK).fault);
        assertNull(server.password);
    }

    // ── the network ──

    @Test
    public void silentServer_timesOut() throws Exception {
        start(FakeSmtp.Mode.SILENT);
        long t0 = System.nanoTime();
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.STARTTLS), server.trusting(),
                new EmailSmtp.Timing(2000, 1000, 5000));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(EmailSmtp.Fault.TIMEOUT, f.fault);
        assertTrue(f.fault.passing);
        assertTrue("gave up after its read time, not later: " + ms + " ms", ms < 4000);
    }

    @Test
    public void tricklingServer_isCutAtTheDeadline() throws Exception {
        // A byte every 300 ms: no read ever waits its full second, so only the deadline ends it.
        start(FakeSmtp.Mode.TRICKLE);
        long t0 = System.nanoTime();
        EmailSmtp.Failure f = fails(to("localhost", EmailSmtp.Security.STARTTLS), server.trusting(),
                new EmailSmtp.Timing(2000, 1000, 2500));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(EmailSmtp.Fault.TIMEOUT, f.fault);
        assertTrue("cut at about 2.5 s: " + ms + " ms", ms >= 2000 && ms < 6000);
    }

    @Test
    public void nothingListening_isANetworkFaultThatPasses() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        try {
            EmailSmtp.send(new EmailSmtp.Server("127.0.0.1", port, EmailSmtp.Security.SSL, "owner@example.com", PASSWORD),
                    message("x"), (SSLSocketFactory) SSLSocketFactory.getDefault(), QUICK, System.currentTimeMillis());
            fail();
        } catch (EmailSmtp.Failure f) {
            assertEquals(EmailSmtp.Fault.NETWORK, f.fault);
            assertTrue(f.fault.passing);
        }
    }

    // ── the rules, one by one ──

    @Test
    public void namesInCertificates() throws Exception {
        LinuxBrowserCert c = LinuxBrowserCert.make(tmp.newFolder(), Collections.singletonList("127.0.0.1"), "x",
                System.currentTimeMillis());
        assertTrue(EmailSmtp.nameMatches("localhost", c.cert));
        assertTrue(EmailSmtp.nameMatches("LOCALHOST", c.cert));
        assertTrue("a trailing dot is the same name", EmailSmtp.nameMatches("localhost.", c.cert));
        assertTrue(EmailSmtp.nameMatches("127.0.0.1", c.cert));
        assertFalse(EmailSmtp.nameMatches("127.0.0.2", c.cert));
        assertFalse(EmailSmtp.nameMatches("evil.localhost", c.cert));
        assertFalse("a name is not an address's", EmailSmtp.nameMatches("::1", c.cert));
        assertTrue(EmailSmtp.dnsMatches("smtp.example.com", "*.example.com"));
        assertFalse("one label only", EmailSmtp.dnsMatches("a.smtp.example.com", "*.example.com"));
        assertFalse("not the bare domain", EmailSmtp.dnsMatches("example.com", "*.example.com"));
        assertFalse("never for a top level", EmailSmtp.dnsMatches("example.com", "*.com"));
        assertFalse("no part-wildcards", EmailSmtp.dnsMatches("smtp.example.com", "s*.example.com"));
        assertFalse(EmailSmtp.dnsMatches("smtp.example.com", "smtp.*.com"));
        assertTrue(EmailSmtp.dnsMatches("smtp.gmail.com", "smtp.gmail.com"));
    }

    @Test
    public void addresses_noWayToEndACommand() {
        assertTrue(EmailSmtp.validAddress("owner@example.com"));
        assertTrue(EmailSmtp.validAddress("first.last+tag@mail.example.co.uk"));
        assertFalse(EmailSmtp.validAddress("owner@example"));
        assertFalse(EmailSmtp.validAddress("owner example.com"));
        assertFalse(EmailSmtp.validAddress("owner@example.com>\r\nRCPT TO:<other@example.com"));
        assertFalse(EmailSmtp.validAddress("<owner@example.com>"));
        assertFalse(EmailSmtp.validAddress("owner@exa mple.com"));
        assertFalse(EmailSmtp.validAddress(""));
        assertFalse(EmailSmtp.validAddress(null));
        assertTrue(EmailSmtp.validHost("smtp.gmail.com"));
        assertFalse(EmailSmtp.validHost("smtp.gmail.com\r\nX"));
        assertFalse(EmailSmtp.validHost(""));
    }

    @Test
    public void headers_cannotBeInjected_andLongWordsAreFolded() {
        assertEquals("a Bcc: x@example.com", EmailSmtp.headerText("a\r\nBcc: x@example.com"));
        String long_ = "Galaxy S25 Ultra: eine neue Adresse für „Von überall“, und noch mehr Wörter mit Umlauten äöü";
        String enc = EmailSmtp.headerText(long_);
        assertTrue(enc.startsWith("=?UTF-8?B?"));
        for (String line : enc.split("\r\n")) assertTrue(line.length() <= 76);
        assertEquals(long_, MailParse.words(enc.replace("\r\n", "")));
        // A character never cut in two between words.
        String emoji = "Telefon 📱📱📱📱📱📱📱📱📱📱📱📱📱📱📱 ende";
        assertEquals(emoji, MailParse.words(EmailSmtp.headerText(emoji).replace("\r\n", "")));
        assertEquals("\"Tom \\\"S25\\\"\"", EmailSmtp.displayName("Tom \"S25\""));
    }

    @Test
    public void dots_areStuffed_andTheEndMarked() {
        byte[] out = EmailSmtp.dotStuffed("a\r\n.b\r\n..c\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals("a\r\n..b\r\n...c\r\n.\r\n", new String(out, StandardCharsets.US_ASCII));
        assertEquals("x\r\n.\r\n", new String(EmailSmtp.dotStuffed("x".getBytes(StandardCharsets.US_ASCII)), StandardCharsets.US_ASCII));
    }

    @Test
    public void serverToString_neverHoldsThePassword() {
        assertFalse(new EmailSmtp.Server("smtp.example.com", 465, EmailSmtp.Security.SSL, "u@example.com", PASSWORD)
                .toString().contains(PASSWORD));
    }

    @Test
    public void certificateMakerForTheTests_isNotTrustedByThePlatform() throws Exception {
        // The safety of the tests above: the throwaway certificate is trusted by the test's own factory alone.
        File d = tmp.newFolder();
        LinuxBrowserCert c = LinuxBrowserCert.make(d, Collections.emptyList(), "x", System.currentTimeMillis());
        assertNotNull(c.cert);
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        javax.net.ssl.X509TrustManager platform = (javax.net.ssl.X509TrustManager) tmf.getTrustManagers()[0];
        try {
            platform.checkServerTrusted(new java.security.cert.X509Certificate[]{c.cert}, "ECDHE_ECDSA");
            fail("the platform must not trust the test certificate");
        } catch (java.security.cert.CertificateException expected) {
            // as it should be
        }
    }
}
