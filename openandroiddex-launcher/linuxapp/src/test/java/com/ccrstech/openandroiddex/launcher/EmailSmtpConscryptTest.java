package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Collections;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The same TLS cases as {@link EmailSmtpTest}, with the client on Conscrypt,
 * the TLS a phone uses (BoringSSL underneath): its errors are worded
 * otherwise ("Unable to parse TLS packet header"), and how a send failed is
 * read from those words. Conscrypt comes with Robolectric on this computer;
 * without it the class is skipped, never passed in silence.
 */
public class EmailSmtpConscryptTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Provider conscrypt;
    private FakeSmtp server;
    private static final EmailSmtp.Timing QUICK = new EmailSmtp.Timing(3000, 3000, 15000);

    @Before
    public void conscrypt() throws Exception {
        try {
            conscrypt = (Provider) Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            Assume.assumeTrue("Conscrypt is not on this computer's test classpath: " + e, false);
        }
    }

    @After
    public void stop() {
        if (server != null) server.close();
    }

    /** A Conscrypt client trusting {@code server}'s certificate, or (null) only the JDK's authorities. */
    private SSLSocketFactory client(FakeSmtp trusted) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (trusted == null) {
            tmf.init((KeyStore) null);
        } else {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("fake-smtp", trusted.cert.cert);
            tmf.init(ks);
        }
        SSLContext c = SSLContext.getInstance("TLS", conscrypt);
        c.init(null, tmf.getTrustManagers(), new SecureRandom());
        assertTrue(c.getProvider().getName(), c.getProvider().getName().toLowerCase().contains("conscrypt"));
        return c.getSocketFactory();
    }

    private EmailSmtp.Message message() {
        return new EmailSmtp.Message("LinuxOnDroid", "owner@example.com", "reader@example.com", "x", "t", "<p>h</p>");
    }

    private EmailSmtp.Failure fails(String host, EmailSmtp.Security sec, SSLSocketFactory f) {
        try {
            EmailSmtp.send(new EmailSmtp.Server(host, server.port(), sec, "owner@example.com", "secret pw"), message(), f, QUICK,
                    System.currentTimeMillis());
        } catch (EmailSmtp.Failure e) {
            return e;
        }
        fail("the send should have failed");
        return null;
    }

    @Test
    public void ssl_andStarttls_sendOnConscrypt() throws Exception {
        server = new FakeSmtp(FakeSmtp.Mode.SSL, tmp.newFolder(), Collections.<String>emptyList());
        EmailSmtp.send(new EmailSmtp.Server("localhost", server.port(), EmailSmtp.Security.SSL, "owner@example.com", "secret pw"),
                message(), client(server), QUICK, System.currentTimeMillis());
        assertEquals(1, server.mails.size());
        server.close();
        server = new FakeSmtp(FakeSmtp.Mode.STARTTLS, tmp.newFolder(), Collections.<String>emptyList());
        EmailSmtp.send(new EmailSmtp.Server("localhost", server.port(), EmailSmtp.Security.STARTTLS, "owner@example.com", "secret pw"),
                message(), client(server), QUICK, System.currentTimeMillis());
        assertEquals(1, server.mails.size());
        assertTrue(server.loginOverTls);
    }

    @Test
    public void sslAgainstAStarttlsPort_isSaidAsTheSecurityChoice() throws Exception {
        server = new FakeSmtp(FakeSmtp.Mode.STARTTLS, tmp.newFolder(), Collections.<String>emptyList());
        EmailSmtp.Failure f = fails("localhost", EmailSmtp.Security.SSL, client(server));
        assertEquals("from " + f.getCause(), EmailSmtp.Fault.SECURITY, f.fault);
    }

    @Test
    public void anotherName_isRefused() throws Exception {
        server = new FakeSmtp(FakeSmtp.Mode.SSL, tmp.newFolder(), Collections.<String>emptyList());
        EmailSmtp.Failure f = fails("127.0.0.1", EmailSmtp.Security.SSL, client(server));
        assertEquals("from " + f.getCause(), EmailSmtp.Fault.CERTIFICATE, f.fault);
        assertNull(server.password);
    }

    @Test
    public void anUntrustedCertificate_isRefused() throws Exception {
        server = new FakeSmtp(FakeSmtp.Mode.STARTTLS, tmp.newFolder(), Collections.<String>emptyList());
        EmailSmtp.Failure f = fails("localhost", EmailSmtp.Security.STARTTLS, client(null));
        assertEquals("from " + f.getCause(), EmailSmtp.Fault.CERTIFICATE, f.fault);
        assertNull(server.password);
    }
}
