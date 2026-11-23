package com.ccrstech.openandroiddex.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The certificate the phone signs its browser connection with
 * ({@link LinuxBrowserServer}): made here, by the phone, for its own addresses.
 *
 * <p><b>Why HTTPS at all, on a home network.</b> The page decodes the desktop
 * on the computer's GPU with WebCodecs, and browsers offer WebCodecs, the
 * clipboard and keyboard lock only to a secure context. A page from
 * {@code http://192.168.x.x} is not one; the same page over HTTPS is, once the
 * person has said yes to the certificate. Measured in Chrome 157 and Edge 157
 * (2026-11-19): over http {@code VideoDecoder} is undefined; over https with a
 * self-signed certificate, after Advanced and Continue, {@code isSecureContext}
 * is true, H.264 is supported in hardware and {@code wss://} opens. It also
 * keeps the desktop's pixels and every key typed off the network in the clear,
 * which matters on a café's Wi-Fi.
 *
 * <p><b>Why self-signed.</b> No certificate authority signs for 192.168.x.x,
 * and the alternatives (a domain of ours with DNS tricks, a key published for
 * everyone) need a server or are not private. So the browser warns once per
 * address; the phone's Settings say so in the same breath as the address.
 *
 * <p><b>Kept, not remade.</b> A browser's "continue" is remembered for this
 * certificate at this address, so the same one is served, with the same key,
 * until it has a month left, whatever happens to the phone's addresses. A
 * certificate remade for a new set of addresses would bring the warning back
 * at every address a browser had already said yes to, and teach people to
 * click through it, which is all a man in the middle needs. An address the
 * certificate does not name is one more mismatch in the same warning, which a
 * browser lets a person past once for that address.
 *
 * <p>Named "LinuxOnDroid", not after the phone: anyone on the network can read
 * a certificate, and a phone's name is often its owner's.
 *
 * <p>The certificate itself, in the shape every browser takes: X.509 v3, EC
 * P-256 signed with SHA-256, the addresses in subjectAltName (Safari and
 * Chrome read names only there), serverAuth, not a CA, and 397 days, under
 * the 398 Apple allows a server certificate. Written out in DER here, since
 * Android has no public API that makes a certificate.
 *
 * <p>Pure Java: the stand-in tests on a computer run this very file.
 */
final class LinuxBrowserCert {

    private static final String KEY_FILE = "tls-key.der";
    private static final String CERT_FILE = "tls-cert.der";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final int VALID_DAYS = 397;
    /** Remade when less than this is left. */
    private static final long RENEW_BEFORE_MS = 30 * DAY_MS;
    private static final char[] PASS = "linuxondroid".toCharArray();

    final X509Certificate cert;
    final SSLContext context;
    /** SHA-256 of the certificate, as browsers show it: upper-case hex pairs and colons. */
    final String fingerprint;

    private LinuxBrowserCert(X509Certificate cert, PrivateKey key) throws Exception {
        this.cert = cert;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("tls", key, PASS, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        this.context = ctx;
        this.fingerprint = colonHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    /** What every certificate here is called. */
    static final String NAME = "LinuxOnDroid";

    /**
     * The certificate in {@code dir} while it has a month left; otherwise a
     * new one naming {@code ips}, kept there.
     */
    static synchronized LinuxBrowserCert loadOrMake(File dir, List<String> ips, long now) throws Exception {
        LinuxBrowserCert kept = load(dir);
        if (kept != null && !kept.renewDue(now)) return kept;
        return make(dir, ips, NAME, now);
    }

    /** Less than a month left, or not yet valid by this clock. */
    boolean renewDue(long now) {
        return cert.getNotAfter().getTime() - now <= RENEW_BEFORE_MS || cert.getNotBefore().getTime() > now;
    }

    /** The kept certificate, or null when there is none or it does not read. */
    static LinuxBrowserCert load(File dir) {
        File keyF = new File(dir, KEY_FILE);
        File certF = new File(dir, CERT_FILE);
        if (!keyF.isFile() || !certF.isFile()) return null;
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(keyF.toPath())));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(Files.readAllBytes(certF.toPath())));
            return new LinuxBrowserCert(cert, key);
        } catch (Exception e) {
            return null;
        }
    }

    /** Every address in {@code ips} is one of the certificate's. */
    boolean covers(List<String> ips) {
        Set<String> have = new HashSet<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(7).equals(san.get(0))) have.add(String.valueOf(san.get(1)));
                }
            }
        } catch (Exception e) {
            return false;
        }
        for (String ip : ips) {
            if (!have.contains(ip)) return false;
        }
        return true;
    }

    static LinuxBrowserCert make(File dir, List<String> ips, String name, long now) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair pair = g.generateKeyPair();
        byte[] der = build(pair, ips, name, now);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        // The certificate checks out against its own key before anything keeps it.
        cert.verify(pair.getPublic());
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        write(new File(dir, KEY_FILE), pair.getPrivate().getEncoded());
        write(new File(dir, CERT_FILE), der);
        return new LinuxBrowserCert(cert, pair.getPrivate());
    }

    // ── the certificate, in DER ──

    private static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_ORG = "2.5.4.10";
    private static final String OID_SAN = "2.5.29.17";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_EXT_KEY_USAGE = "2.5.29.37";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    static byte[] build(KeyPair pair, List<String> ips, String name, long now) throws Exception {
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F; // positive
        serial[0] |= 0x40; // and sixteen bytes long, never shortened
        byte[] sigAlg = seq(oid(OID_ECDSA_SHA256));
        byte[] subject = seq(
                set(seq(oid(OID_ORG), utf8("LinuxOnDroid"))),
                set(seq(oid(OID_CN), utf8(name))));
        // An hour back, for a computer whose clock is a little behind the phone's.
        byte[] validity = seq(time(now - 3600_000L), time(now + VALID_DAYS * DAY_MS));

        List<byte[]> names = new ArrayList<>();
        for (String ip : ips) {
            byte[] a = ipBytes(ip);
            if (a != null) names.add(tlv(0x87, a)); // [7] iPAddress
        }
        names.add(tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII))); // [2] dNSName
        byte[] san = seq(names.toArray(new byte[0][]));
        byte[] extensions = seq(
                seq(oid(OID_BASIC_CONSTRAINTS), bool(true), octets(seq())),
                // digitalSignature: the one an ECDSA server key does
                seq(oid(OID_KEY_USAGE), bool(true), octets(tlv(0x03, new byte[]{7, (byte) 0x80}))),
                seq(oid(OID_EXT_KEY_USAGE), octets(seq(oid(OID_SERVER_AUTH)))),
                seq(oid(OID_SAN), octets(san)));

        byte[] tbs = seq(
                tlv(0xA0, integer(new byte[]{2})), // v3
                integer(serial),
                sigAlg,
                subject,
                validity,
                subject,
                pair.getPublic().getEncoded(), // SubjectPublicKeyInfo, already DER
                tlv(0xA3, extensions));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pair.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length); // no unused bits
        return seq(tbs, sigAlg, tlv(0x03, bits));
    }

    static byte[] ipBytes(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return null;
        byte[] b = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(p[i]);
                if (v < 0 || v > 255) return null;
                b[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return b;
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else if (n < 0x10000) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write(n >> 8);
            out.write(n);
        }
        out.write(value, 0, n);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] integer(byte[] value) {
        return tlv(0x02, new BigInteger(1, value).toByteArray());
    }

    private static byte[] bool(boolean v) {
        return tlv(0x01, new byte[]{(byte) (v ? 0xFF : 0)});
    }

    private static byte[] octets(byte[] v) {
        return tlv(0x04, v);
    }

    private static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] oid(String dotted) {
        String[] arcs = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(arcs[0]) * 40 + Integer.parseInt(arcs[1]));
        for (int i = 2; i < arcs.length; i++) {
            long v = Long.parseLong(arcs[i]);
            int n = 0;
            byte[] tmp = new byte[10];
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v > 0);
            for (int k = n - 1; k >= 0; k--) out.write(tmp[k] | (k > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    /** UTCTime until 2049, GeneralizedTime after, as RFC 5280 asks. */
    private static byte[] time(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(ms);
        int year = c.get(Calendar.YEAR);
        String rest = String.format(Locale.ROOT, "%02d%02d%02d%02d%02dZ", c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        if (year < 2050) {
            return tlv(0x17, (String.format(Locale.ROOT, "%02d", year % 100) + rest).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(0x18, (String.format(Locale.ROOT, "%04d", year) + rest).getBytes(StandardCharsets.US_ASCII));
    }

    static String colonHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xff));
        }
        return sb.toString();
    }

    private static void write(File f, byte[] content) throws IOException {
        File tmp = new File(f.getPath() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content);
            out.getFD().sync();
        }
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
