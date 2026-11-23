package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Remote desktop's own adb (AdbWire): what the phone's helper is started
 * with when no computer is there (PhoneHelperStart).
 *
 * <p>The pairing runs over real TLS 1.3 with Conscrypt, the TLS a phone has
 * (BoringSSL underneath), which comes with Robolectric on this computer; the
 * classes that need it are skipped without it, never passed in silence. The
 * last test pairs the computer's own adb, Google's implementation, with this
 * file's pairing, when asked: {@code -PadbPairCheck=<path to adb>}.
 */
public class AdbWireTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Provider conscrypt;
    private AdbWire.Exporter export;

    @Before
    public void conscrypt() {
        try {
            conscrypt = (Provider) Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance();
            Method m = Class.forName("org.conscrypt.Conscrypt").getMethod("exportKeyingMaterial",
                    SSLSocket.class, String.class, byte[].class, int.class);
            export = (s, label, len) -> {
                try {
                    return (byte[]) m.invoke(null, s, label, null, len);
                } catch (ReflectiveOperationException e) {
                    throw new IOException(e.getCause() != null ? e.getCause() : e);
                }
            };
        } catch (Throwable e) {
            conscrypt = null;
        }
    }

    @After
    public void noConscrypt() {
        if (conscrypt != null) Security.removeProvider(conscrypt.getName());
    }

    /** The app's own TLS ("TLS" from the first provider) is Conscrypt's, as on a phone. */
    private void conscryptFirst() {
        Assume.assumeTrue("Conscrypt is not on this computer's test classpath", conscrypt != null);
        Security.insertProviderAt(conscrypt, 1);
    }

    // ── the curve and SPAKE2 ──

    @Test
    public void mAndN_areBoringSslsPoints() {
        // spake25519.c's comment gives both in decimal.
        assertEquals(new BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"), AdbWire.Ed.M.x);
        assertEquals(new BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"), AdbWire.Ed.M.y);
        assertEquals(new BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"), AdbWire.Ed.N.x);
        assertEquals(new BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"), AdbWire.Ed.N.y);
    }

    @Test
    public void basePoint_isEd25519s_andOfOrderL() {
        assertEquals(new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"), AdbWire.Ed.B.x);
        assertEquals(AdbWire.Ed.Point.ZERO, AdbWire.Ed.B.times(AdbWire.Ed.L));
        assertEquals(AdbWire.Ed.B, AdbWire.Ed.Point.decode(AdbWire.Ed.B.encode()));
    }

    @Test
    public void spake2_bothSidesAgree_onlyWithTheSamePassword() throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] pw = "123456 and the TLS session's 64 bytes".getBytes(StandardCharsets.UTF_8);
        AdbWire.Spake2 alice = new AdbWire.Spake2(true, pw, r);
        AdbWire.Spake2 bob = new AdbWire.Spake2(false, pw, r);
        byte[] ka = alice.key(bob.msg());
        byte[] kb = bob.key(alice.msg());
        assertEquals(64, ka.length);
        assertArrayEquals(ka, kb);
        AdbWire.Spake2 wrong = new AdbWire.Spake2(false, "654321".getBytes(StandardCharsets.UTF_8), r);
        assertFalse(Arrays.equals(new AdbWire.Spake2(true, pw, r).key(wrong.msg()), wrong.key(alice.msg())));
    }

    @Test
    public void spake2_refusesWhatIsNotAPoint() {
        AdbWire.Spake2 alice = new AdbWire.Spake2(true, new byte[]{1}, new SecureRandom());
        byte[] notOnCurve = new byte[32];
        notOnCurve[0] = 2; // y = 2 has no x on Edwards25519
        try {
            alice.key(notOnCurve);
            fail("a message off the curve was taken");
        } catch (java.security.GeneralSecurityException expected) {
            // refused
        }
    }

    // ── the pieces the pairing is made of ──

    @Test
    public void hkdf_isRfc5869s_caseThree() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                hex(AdbWire.hkdfSha256(ikm, new byte[0], 42)));
    }

    @Test
    public void androidPublicKey_isAdbsLayout() throws Exception {
        AdbWire.Keys k = AdbWire.Keys.make();
        byte[] b = AdbWire.androidPublicKey(k.pub);
        assertEquals(524, b.length);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, bb.getInt());
        long n0inv = bb.getInt() & 0xffffffffL;
        byte[] mod = new byte[256];
        bb.get(mod);
        byte[] rr = new byte[256];
        bb.get(rr);
        assertEquals(65537, bb.getInt());
        BigInteger n = AdbWire.fromLittleEndian(mod);
        assertEquals(k.pub.getModulus(), n);
        // n0inv * n ≡ -1 (mod 2^32)
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv).multiply(n).mod(r32));
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), AdbWire.fromLittleEndian(rr));
        String line = k.line("LinuxOnDroid@test");
        assertTrue(line.endsWith(" LinuxOnDroid@test"));
        assertArrayEquals(b, Base64.getDecoder().decode(line.substring(0, line.indexOf(' '))));
    }

    @Test
    public void certificate_isSelfSigned_forTheKey() throws Exception {
        AdbWire.Keys k = AdbWire.Keys.make();
        X509Certificate c = k.cert;
        c.verify(k.pub);
        c.checkValidity();
        assertArrayEquals(k.pub.getEncoded(), c.getPublicKey().getEncoded());
        String subject = c.getSubjectX500Principal().getName();
        assertTrue(subject, subject.contains("CN=Adb") && subject.contains("O=Android") && subject.contains("C=US"));
    }

    @Test
    public void key_isKeptInItsFile_andTheSameNextTime() throws Exception {
        File f = new File(tmp.getRoot(), "helper/adbkey");
        AdbWire.Keys a = AdbWire.Keys.inFile(f);
        assertTrue(f.isFile());
        AdbWire.Keys b = AdbWire.Keys.inFile(f);
        assertEquals(a.pub.getModulus(), b.pub.getModulus());
        assertEquals(a.line("x"), b.line("x"));
    }

    // ── pairing, over real TLS 1.3 ──

    @Test
    public void pairing_withTheRightCode_givesEachSideTheOther() throws Exception {
        conscryptFirst();
        AdbWire.Keys app = AdbWire.Keys.make();
        try (PairServer phone = new PairServer(conscrypt, "482913", "adb-TESTSERIAL-a1B2c3")) {
            String guid;
            try {
                guid = AdbWire.pair(phone.port(), "482913", app, "LinuxOnDroid@test", export, 10_000);
            } catch (IOException e) {
                phone.join();
                throw new AssertionError("the app's side: " + e + "; the phone's side: " + phone.failed, phone.failed);
            }
            assertEquals("adb-TESTSERIAL-a1B2c3", guid);
            phone.join();
            assertNull(String.valueOf(phone.failed), phone.failed);
            assertEquals(AdbWire.PEER_RSA_PUB_KEY, phone.theirs[0]);
            assertEquals(app.line("LinuxOnDroid@test"), AdbWire.cString(phone.theirs, 1));
        }
    }

    @Test
    public void pairing_withAWrongCode_isRefused_onBothSides() throws Exception {
        conscryptFirst();
        AdbWire.Keys app = AdbWire.Keys.make();
        try (PairServer phone = new PairServer(conscrypt, "482913", "adb-TESTSERIAL-a1B2c3")) {
            try {
                AdbWire.pair(phone.port(), "482914", app, "LinuxOnDroid@test", export, 10_000);
                fail("a wrong code paired");
            } catch (AdbWire.WrongCodeException expected) {
                // refused
            }
            phone.join();
            assertTrue(String.valueOf(phone.failed), phone.failed instanceof AdbWire.WrongCodeException);
            assertNull(phone.theirs);
        }
    }

    @Test
    public void pairing_refusesAnythingButThisPhone() throws Exception {
        // The trust manager takes the loopback address only.
        AdbWire.LoopbackOnly t = new AdbWire.LoopbackOnly();
        try (Socket s = new Socket()) {
            t.checkServerTrusted(new X509Certificate[0], "RSA", s);
            fail("an unconnected socket was trusted");
        } catch (CertificateException expected) {
            // refused
        }
        try {
            t.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("a socketless check was trusted");
        } catch (CertificateException expected) {
            // refused
        }
    }

    // ── the link to adbd ──

    @Test
    public void link_runsAShellCommand_withAKeyAdbdKnows() throws Exception {
        linkRunsAShellCommand();
    }

    @Test
    public void link_runsAShellCommand_onConscrypt() throws Exception {
        conscryptFirst();
        linkRunsAShellCommand();
    }

    private void linkRunsAShellCommand() throws Exception {
        AdbWire.Keys app = AdbWire.Keys.make();
        try (FakeAdbd adbd = new FakeAdbd(app.pub.getEncoded(), "started\n")) {
            try (AdbWire.Link l = AdbWire.Link.open(adbd.port(), app, 10_000)) {
                assertTrue(l.banner, l.banner.startsWith("device::"));
                assertEquals("started\n", l.shell("echo started", 10_000));
            }
            adbd.join();
            assertNull(String.valueOf(adbd.failed), adbd.failed);
            assertEquals("shell:echo started", adbd.opened);
        }
    }

    @Test
    public void link_refusedKey_isNeverALink() throws Exception {
        // The computer's own TLS: adbd's refusal may end the handshake mid-write
        // (a reset, not an alert), which is not proof enough to say "not paired".
        AdbWire.Keys app = AdbWire.Keys.make();
        AdbWire.Keys someoneElse = AdbWire.Keys.make();
        try (FakeAdbd adbd = new FakeAdbd(someoneElse.pub.getEncoded(), "")) {
            try {
                AdbWire.Link.open(adbd.port(), app, 10_000).close();
                fail("adbd's refusal was taken for a link");
            } catch (IOException expected) {
                // refused
            }
        }
    }

    @Test
    public void link_saysNotPaired_onConscrypt() throws Exception {
        conscryptFirst();
        notPaired();
    }

    private void notPaired() throws Exception {
        AdbWire.Keys app = AdbWire.Keys.make();
        AdbWire.Keys someoneElse = AdbWire.Keys.make();
        try (FakeAdbd adbd = new FakeAdbd(someoneElse.pub.getEncoded(), "")) {
            try {
                AdbWire.Link.open(adbd.port(), app, 10_000).close();
                fail("adbd's refusal was taken for a link");
            } catch (AdbWire.NotPairedException expected) {
                // said
            }
        }
    }

    @Test
    public void link_saysWhenTheShellIsRefused() throws Exception {
        AdbWire.Keys app = AdbWire.Keys.make();
        try (FakeAdbd adbd = new FakeAdbd(app.pub.getEncoded(), null)) {
            try (AdbWire.Link l = AdbWire.Link.open(adbd.port(), app, 10_000)) {
                l.shell("nope", 10_000);
                fail("a refused shell read as run");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("refused"));
            }
        }
    }

    // ── against the computer's own adb (Google's pairing, BoringSSL) ──

    /**
     * The computer's adb pairs with this file's pairing, as the phone's
     * Wireless debugging would: run with -PadbPairCheck=<adb>. Its own adb
     * server on port 5047 with its own keys in a temporary folder, no USB, no
     * mDNS, no emulators; stopped after.
     */
    @Test
    public void thePcsOwnAdb_pairsWithThisPairing() throws Exception {
        String adb = System.getProperty("adb.pair.check");
        Assume.assumeTrue("set -PadbPairCheck=<path to adb> to pair the computer's adb", adb != null && !adb.isEmpty());
        conscryptFirst();
        File home = tmp.newFolder("adb-home");
        File dot = new File(home, ".android");
        assertTrue(dot.mkdirs() || dot.isDirectory());
        // adb on Windows keeps its keys in the profile's .android whatever ANDROID_USER_HOME
        // says, and a pairing adds the phone's name to adb_known_hosts.pb there: the name
        // this test made up is taken out again after (and only that one).
        File profile = new File(System.getProperty("user.home"), ".android");
        try (PairServer phone = new PairServer(conscrypt, "713046", "adb-PCCHECK-x9Y8z7")) {
            String wrong = runAdb(adb, home, "pair", "127.0.0.1:" + phone.port(), "713047");
            phone.join();
            assertTrue("adb with a wrong code: " + wrong, phone.failed instanceof AdbWire.WrongCodeException);
            assertFalse(wrong, wrong.contains("Successfully paired"));
        }
        try (PairServer phone = new PairServer(conscrypt, "713046", "adb-PCCHECK-x9Y8z7")) {
            String said = runAdb(adb, home, "pair", "127.0.0.1:" + phone.port(), "713046");
            phone.join();
            assertNull(String.valueOf(phone.failed), phone.failed);
            assertTrue(said, said.contains("Successfully paired") && said.contains("adb-PCCHECK-x9Y8z7"));
            // What adb sent is its public key, in the format this file writes.
            assertEquals(AdbWire.PEER_RSA_PUB_KEY, phone.theirs[0]);
            String line = AdbWire.cString(phone.theirs, 1);
            byte[] key = Base64.getDecoder().decode(line.substring(0, line.indexOf(' ')).trim());
            assertEquals(524, key.length);
            File pubFile = new File(dot, "adbkey.pub").isFile() ? new File(dot, "adbkey.pub") : new File(profile, "adbkey.pub");
            String pub = new String(java.nio.file.Files.readAllBytes(pubFile.toPath()), StandardCharsets.UTF_8);
            assertEquals(pub.substring(0, pub.indexOf(' ')), line.substring(0, line.indexOf(' ')));
        } finally {
            runAdb(adb, home, "kill-server");
            forgetKnownHost(new File(profile, "adb_known_hosts.pb"), "adb-PCCHECK-x9Y8z7");
            forgetKnownHost(new File(dot, "adb_known_hosts.pb"), "adb-PCCHECK-x9Y8z7");
        }
    }

    /** adb_known_hosts.pb without the one host this test made up (repeated HostInfo{guid}), byte for byte otherwise. */
    private static void forgetKnownHost(File f, String guid) throws IOException {
        if (!f.isFile()) return;
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int i = 0;
        boolean dropped = false;
        while (i < b.length) {
            int start = i;
            int[] tag = varint(b, i);
            int[] len = varint(b, tag[1]);
            int end = len[1] + len[0];
            int[] t2 = varint(b, len[1]);
            int[] n = varint(b, t2[1]);
            String g = new String(b, n[1], n[0], StandardCharsets.UTF_8);
            if (guid.equals(g)) {
                dropped = true;
            } else {
                kept.write(b, start, end - start);
            }
            i = end;
        }
        if (!dropped) return;
        File part = new File(f.getPath() + ".part");
        java.nio.file.Files.write(part.toPath(), kept.toByteArray());
        java.nio.file.Files.move(part.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** {value, next index}. */
    private static int[] varint(byte[] b, int i) {
        int v = 0;
        int shift = 0;
        while (true) {
            int x = b[i++] & 0xff;
            v |= (x & 0x7f) << shift;
            shift += 7;
            if (x < 0x80) return new int[]{v, i};
        }
    }

    private static String runAdb(String adb, File home, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = adb;
        cmd[1] = "-P";
        cmd[2] = "5047";
        System.arraycopy(args, 0, cmd, 3, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("ANDROID_USER_HOME", new File(home, ".android").getPath());
        env.put("ANDROID_SDK_HOME", home.getPath());
        env.put("ADB_USB", "0");
        env.put("ADB_MDNS_AUTO_CONNECT", "0");
        env.put("ADB_MDNS", "0");
        env.put("ADB_LOCAL_TRANSPORT_MAX_PORT", "5554");
        env.remove("ANDROID_ADB_SERVER_PORT");
        env.remove("ADB_SERVER_SOCKET");
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread read = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } catch (IOException ignored) {
                // ended
            }
        });
        read.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
        read.join(5000);
        return out.toString("UTF-8");
    }

    // ── stand-ins for the phone ──

    /** Accepts any certificate: the tests' phone, which checks nothing (as Wireless debugging's pairing). */
    static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] c, String a) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] c, String a) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** The phone's half of pairing: TLS 1.3 needing a client certificate, then AdbWire.pairOn as the server. */
    final class PairServer implements Closeable {
        private final SSLServerSocket server;
        private final Thread thread;
        volatile byte[] theirs;
        volatile Throwable failed;

        PairServer(Provider p, String code, String guid) throws Exception {
            SSLContext c = SSLContext.getInstance("TLS", p);
            c.init(new KeyManager[]{new AdbWire.OwnKey(AdbWire.Keys.make())}, new TrustManager[]{new TrustAll()}, new SecureRandom());
            server = (SSLServerSocket) c.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
            server.setNeedClientAuth(true);
            server.setEnabledProtocols(new String[]{"TLSv1.3"});
            thread = new Thread(() -> {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.setSoTimeout(20_000);
                    s.startHandshake();
                    theirs = AdbWire.pairOn(s, false, code.getBytes(StandardCharsets.UTF_8),
                            AdbWire.peerInfo(AdbWire.PEER_DEVICE_GUID, guid), export, new SecureRandom());
                } catch (Throwable t) {
                    failed = t;
                }
            }, "pair-server");
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void join() throws InterruptedException {
            thread.join(30_000);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /**
     * adbd on Wireless debugging's port: CNXN, STLS, TLS 1.3 taking one key
     * only, CNXN, then one shell stream answered with {@code output} (null:
     * the shell refused).
     */
    final class FakeAdbd implements Closeable {
        private final ServerSocket server;
        private final Thread thread;
        volatile Throwable failed;
        volatile String opened;

        FakeAdbd(byte[] trustedKey, String output) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            AdbWire.Keys own = AdbWire.Keys.make();
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(new KeyManager[]{new AdbWire.OwnKey(own)}, new TrustManager[]{new X509ExtendedTrustManager() {
                private void check(X509Certificate[] chain) throws CertificateException {
                    if (chain == null || chain.length == 0 || !Arrays.equals(trustedKey, chain[0].getPublicKey().getEncoded())) {
                        throw new CertificateException("not a known key");
                    }
                }

                @Override
                public void checkClientTrusted(X509Certificate[] ch, String a, Socket s) throws CertificateException {
                    check(ch);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] ch, String a, Socket s) {
                }

                @Override
                public void checkClientTrusted(X509Certificate[] ch, String a, SSLEngine e) throws CertificateException {
                    check(ch);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] ch, String a, SSLEngine e) {
                }

                @Override
                public void checkClientTrusted(X509Certificate[] ch, String a) throws CertificateException {
                    check(ch);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] ch, String a) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            thread = new Thread(() -> {
                try (Socket raw = server.accept()) {
                    raw.setSoTimeout(20_000);
                    AdbWire.Packet cnxn = AdbWire.readPacket(raw.getInputStream());
                    assertEquals(AdbWire.A_CNXN, cnxn.cmd);
                    assertEquals(AdbWire.A_VERSION, cnxn.arg0);
                    AdbWire.writePacket(raw.getOutputStream(), AdbWire.A_STLS, AdbWire.A_STLS_VERSION, 0, new byte[0]);
                    AdbWire.Packet stls = AdbWire.readPacket(raw.getInputStream());
                    assertEquals(AdbWire.A_STLS, stls.cmd);
                    SSLSocket s = (SSLSocket) c.getSocketFactory().createSocket(raw, "127.0.0.1", raw.getPort(), true);
                    s.setUseClientMode(false);
                    s.setNeedClientAuth(true);
                    s.setEnabledProtocols(new String[]{"TLSv1.3"});
                    s.startHandshake();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    AdbWire.writePacket(out, AdbWire.A_CNXN, AdbWire.A_VERSION, AdbWire.MAX_PAYLOAD,
                            "device::ro.product.name=test;features=shell_v2".getBytes(StandardCharsets.UTF_8));
                    AdbWire.Packet open = AdbWire.readPacket(in);
                    assertEquals(AdbWire.A_OPEN, open.cmd);
                    opened = AdbWire.cString(open.data, 0);
                    int local = open.arg0;
                    if (output == null) {
                        AdbWire.writePacket(out, AdbWire.A_CLSE, 0, local, new byte[0]);
                        return;
                    }
                    AdbWire.writePacket(out, AdbWire.A_OKAY, 7, local, new byte[0]);
                    AdbWire.writePacket(out, AdbWire.A_WRTE, 7, local, output.getBytes(StandardCharsets.UTF_8));
                    AdbWire.Packet ok = AdbWire.readPacket(in);
                    assertEquals(AdbWire.A_OKAY, ok.cmd);
                    AdbWire.writePacket(out, AdbWire.A_CLSE, 7, local, new byte[0]);
                    AdbWire.Packet back = AdbWire.readPacket(in);
                    assertEquals(AdbWire.A_CLSE, back.cmd);
                } catch (Throwable t) {
                    failed = t;
                }
            }, "fake-adbd");
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void join() throws InterruptedException {
            thread.join(30_000);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
