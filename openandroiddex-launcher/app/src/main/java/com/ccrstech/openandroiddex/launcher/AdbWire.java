package com.ccrstech.openandroiddex.launcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Android's own debugging link, adb, as this app speaks it to the phone it
 * runs on: Wireless debugging's pairing with a code, and the encrypted link
 * after it, enough to run one shell command. That command starts
 * {@link PhoneHelper} with no computer (doc/phone-remote.md, "The helper
 * without a computer"); it is how Shizuku and LADB start theirs.
 *
 * <p>Plain Java on purpose (javax.net.ssl and javax.crypto), so the computer's
 * tests run every byte of it against the computer's own adb. Written from
 * AOSP's packages/modules/adb (pairing_connection, pairing_auth,
 * aes_128_gcm, tls_connection, daemon/auth.cpp, adb.cpp) and BoringSSL's
 * crypto/curve25519/spake25519.c, which adbd uses.
 *
 * <p><b>Pairing</b> (Settings, Developer options, Wireless debugging, "Pair
 * device with pairing code"): TLS 1.3, each side showing a certificate nobody
 * checks; 64 bytes of the TLS session's keying material appended to the
 * code are SPAKE2's password, so only a peer that saw the code, on this very
 * TLS session, ends with the same key; AES-128-GCM under that key carries
 * each side's {@code PeerInfo}: the app's adb public key one way, the phone's
 * Wireless debugging name the other. Android then keeps the key with the
 * computers it trusts ("Paired devices").
 *
 * <p><b>The link</b>: adb's packets (CNXN, then STLS: TLS 1.3 on the same
 * socket, this app's certificate checked against the keys adbd trusts), then
 * a {@code shell:} stream. Only to this phone's own loopback address: the
 * trust manager refuses anything else ({@link LoopbackOnly}). adbd's
 * certificate is a fresh key each time adbd starts, so nothing on the phone
 * could vouch for it; a stranger on the loopback port pretending to be adbd
 * learns only the command, which carries no secret (the helper makes its own
 * key and hands it to the app through {@link PhoneHelperDoor}, which Android
 * tells who called).
 */
final class AdbWire {

    private AdbWire() {
    }

    // ── packets (adb.h) ──

    static final int A_CNXN = 0x4e584e43;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_AUTH = 0x48545541;
    static final int A_STLS = 0x534c5453;
    static final int A_VERSION = 0x01000001;
    static final int A_STLS_VERSION = 0x01000000;
    static final int MAX_PAYLOAD = 1024 * 1024;

    /** adbd took the TLS handshake and then refused this app's key: not paired, or no longer. */
    static final class NotPairedException extends IOException {
        NotPairedException(String why, Throwable cause) {
            super(why, cause);
        }
    }

    /** The phone's pairing refused the code (or the code was typed for another pairing). */
    static final class WrongCodeException extends IOException {
        WrongCodeException(String why, Throwable cause) {
            super(why, cause);
        }
    }

    /** One adb packet: command, two arguments, data. */
    static final class Packet {
        final int cmd;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    static void writePacket(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    static Packet readPacket(InputStream in) throws IOException {
        byte[] h = new byte[24];
        new DataInputStream(in).readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // the sum: adbd sends 0 from A_VERSION_SKIP_CHECKSUM on
        int magic = b.getInt();
        if (magic != ~cmd) throw new IOException("not an adb packet");
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("adb packet too large: " + len);
        byte[] data = new byte[len];
        new DataInputStream(in).readFully(data);
        return new Packet(cmd, arg0, arg1, data);
    }

    // ── the app's adb key ──

    /** This app's own adb key: RSA 2048, its certificate for TLS, and the line adbd keeps for it. */
    static final class Keys {
        final PrivateKey priv;
        final RSAPublicKey pub;
        final X509Certificate cert;

        Keys(PrivateKey priv, RSAPublicKey pub) throws GeneralSecurityException {
            this.priv = priv;
            this.pub = pub;
            this.cert = selfSigned(priv, pub, System.currentTimeMillis());
        }

        static Keys make() throws GeneralSecurityException {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
            KeyPair kp = g.generateKeyPair();
            return new Keys(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
        }

        /** The key kept in {@code file} (PKCS#8), made and kept there first if there is none yet. */
        static synchronized Keys inFile(File file) throws IOException {
            try {
                if (file.isFile()) {
                    KeyFactory f = KeyFactory.getInstance("RSA");
                    PrivateKey priv = f.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(file.toPath())));
                    RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
                    RSAPublicKey pub = (RSAPublicKey) f.generatePublic(
                            new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
                    return new Keys(priv, pub);
                }
                Keys k = make();
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room for the adb key");
                File part = new File(file.getPath() + ".part");
                try (OutputStream out = new FileOutputStream(part)) {
                    out.write(k.priv.getEncoded());
                }
                if (!part.renameTo(file)) throw new IOException("could not keep the adb key");
                return k;
            } catch (GeneralSecurityException | ClassCastException e) {
                throw new IOException("adb key: " + e, e);
            }
        }

        /** The line Android keeps for this key: adb's public key format, base64, a space and a name. */
        String line(String name) {
            return Base64.getEncoder().encodeToString(androidPublicKey(pub)) + " " + name;
        }
    }

    /**
     * adb's public key format (system/core libcrypto_utils android_pubkey.h):
     * the modulus' length in 32-bit words, -1/n mod 2^32, the modulus,
     * R^2 mod n with R = 2^2048, and the exponent, all little-endian.
     */
    static byte[] androidPublicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        if (n.bitLength() != 2048) throw new IllegalArgumentException("adb keys are 2048 bits, not " + n.bitLength());
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n), 256));
        b.putInt(k.getPublicExponent().intValue());
        return b.array();
    }

    /** {@code v} as {@code len} bytes, least significant first. */
    static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static BigInteger fromLittleEndian(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[be.length - 1 - i] = b[i];
        return new BigInteger(be);
    }

    // ── the certificate (as adb's x509_generator makes one: C=US, O=Android, CN=Adb) ──

    static X509Certificate selfSigned(PrivateKey priv, RSAPublicKey pub, long now) throws GeneralSecurityException {
        byte[] sha256Rsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00});
        byte[] name = seq(
                set(seq(oid(2, 5, 4, 6), der(0x13, ascii("US")))),
                set(seq(oid(2, 5, 4, 10), der(0x13, ascii("Android")))),
                set(seq(oid(2, 5, 4, 3), der(0x13, ascii("Adb")))));
        byte[] validity = seq(time(now - 24L * 3600_000), time(now + 10L * 365 * 24 * 3600_000));
        byte[] tbs = seq(
                der(0xa0, der(0x02, new byte[]{2})),
                der(0x02, new byte[]{1}),
                sha256Rsa, name, validity, name,
                pub.getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = seq(tbs, sha256Rsa, der(0x03, bits));
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(cert));
        } catch (CertificateException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] time(long ms) {
        Date d = new Date(ms);
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.US);
        year.setTimeZone(TimeZone.getTimeZone("UTC"));
        boolean utc = Integer.parseInt(year.format(d)) < 2050;
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(utc ? 0x17 : 0x18, ascii(f.format(d)));
    }

    private static byte[] seq(byte[]... parts) {
        return der(0x30, parts);
    }

    private static byte[] set(byte[]... parts) {
        return der(0x31, parts);
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(40 * arcs[0] + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) b.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return der(0x06, b.toByteArray());
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int len = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = len > 0xffff ? 3 : len > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write(len >>> (8 * i));
        }
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    // ── TLS ──

    /** The TLS session's keying material (RFC 8705's exporter); Android's SSLSockets on the phone. */
    interface Exporter {
        byte[] export(SSLSocket s, String label, int length) throws IOException;
    }

    /** TLS 1.3 showing this app's certificate, to this phone's own adbd only. */
    static SSLContext tls(Keys k) throws IOException {
        try {
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(new KeyManager[]{new OwnKey(k)}, new TrustManager[]{new LoopbackOnly()}, new SecureRandom());
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS: " + e, e);
        }
    }

    /** The one key: this app's, whatever authorities adbd lists (it lists the keys it trusts). */
    static final class OwnKey extends X509ExtendedKeyManager {
        static final String ALIAS = "adb";
        private final Keys k;

        OwnKey(Keys k) {
            this.k = k;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[]{k.cert};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return k.priv;
        }
    }

    /**
     * Only this phone's own address. adbd's certificate is made fresh each
     * time adbd starts (daemon/auth.cpp), so there is nothing to check it
     * against: pairing proves itself with the code, and the link carries no
     * secret. What this refuses is any other machine.
     */
    static final class LoopbackOnly extends X509ExtendedTrustManager {
        private static void here(Socket s) throws CertificateException {
            if (s == null || s.getInetAddress() == null || !s.getInetAddress().isLoopbackAddress()) {
                throw new CertificateException("adb only to this phone's own address");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            here(socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("adb only on a socket");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("adb only on a socket to this phone");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("the app is never adb's server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("the app is never adb's server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("the app is never adb's server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ── pairing (pairing_connection.cpp) ──

    static final String EXPORT_LABEL = "adb-label\u0000";
    static final byte[] CLIENT_NAME = "adb pair client\u0000".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SERVER_NAME = "adb pair server\u0000".getBytes(StandardCharsets.US_ASCII);
    /** sizeof(PeerInfo): a type byte and its data, zero-filled. */
    static final int PEER_INFO = 8192;
    static final int PEER_RSA_PUB_KEY = 0;
    static final int PEER_DEVICE_GUID = 1;
    private static final int PACKET_SPAKE2 = 0;
    private static final int PACKET_PEER_INFO = 1;
    private static final int MAX_PAIRING_PAYLOAD = PEER_INFO * 2;

    /**
     * Pairs this app's key with the phone's Wireless debugging, whose pairing
     * listens on {@code port} of this phone while "Pair device with pairing
     * code" is open. Returns the phone's Wireless debugging name (its
     * {@code adb-…} GUID, what its connect service is called), or throws
     * {@link WrongCodeException} for a code it refused.
     */
    static String pair(int port, String code, Keys k, String name, Exporter ex, int timeoutMs) throws IOException {
        SSLSocket s = (SSLSocket) tls(k).getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.startHandshake();
            byte[] theirs = pairOn(s, true, code.trim().getBytes(StandardCharsets.UTF_8),
                    peerInfo(PEER_RSA_PUB_KEY, k.line(name)), ex, new SecureRandom());
            return theirs[0] == PEER_DEVICE_GUID ? cString(theirs, 1) : "";
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** A PeerInfo: the type, then the words, then zeros. */
    static byte[] peerInfo(int type, String words) {
        byte[] info = new byte[PEER_INFO];
        info[0] = (byte) type;
        byte[] w = words.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(w, 0, info, 1, Math.min(w.length, PEER_INFO - 2));
        return info;
    }

    /** The NUL-ended words in {@code b} from {@code at}. */
    static String cString(byte[] b, int at) {
        int end = at;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }

    /**
     * One side of pairing on a TLS 1.3 socket whose handshake is done: the
     * SPAKE2 messages, then the PeerInfos under the key they agree on. Returns
     * the other side's PeerInfo. The server's side is here for the tests,
     * which pair the computer's own adb with it.
     */
    static byte[] pairOn(SSLSocket s, boolean client, byte[] code, byte[] myInfo, Exporter ex, SecureRandom rnd)
            throws IOException {
        byte[] ekm = ex.export(s, EXPORT_LABEL, 64);
        if (ekm == null || ekm.length != 64) throw new IOException("no keying material from TLS");
        byte[] password = new byte[code.length + ekm.length];
        System.arraycopy(code, 0, password, 0, code.length);
        System.arraycopy(ekm, 0, password, code.length, ekm.length);
        Spake2 sp = new Spake2(client, password, rnd);
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        writePairing(out, PACKET_SPAKE2, sp.msg());
        byte[] theirMsg = readPairing(in, PACKET_SPAKE2);
        byte[] keyMaterial;
        try {
            keyMaterial = sp.key(theirMsg);
        } catch (GeneralSecurityException e) {
            throw new IOException("pairing: the other side's message is not a point: " + e.getMessage(), e);
        }
        Gcm gcm = new Gcm(hkdfSha256(keyMaterial, "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII), 16));
        writePairing(out, PACKET_PEER_INFO, gcm.seal(myInfo));
        byte[] sealed;
        try {
            sealed = readPairing(in, PACKET_PEER_INFO);
        } catch (EOFException | SSLException e) {
            // The other side could not open ours: a code that differs ends here.
            throw new WrongCodeException("the phone closed the pairing: a wrong code", e);
        }
        byte[] info;
        try {
            info = gcm.open(sealed);
        } catch (GeneralSecurityException e) {
            throw new WrongCodeException("the phone's answer does not open: a wrong code", e);
        }
        if (info.length != PEER_INFO) throw new IOException("pairing: a PeerInfo of " + info.length + " bytes");
        return info;
    }

    private static void writePairing(OutputStream out, int type, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(6 + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 1).put((byte) type).putInt(payload.length).put(payload);
        out.write(b.array());
        out.flush();
    }

    private static byte[] readPairing(InputStream in, int type) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int version = d.readUnsignedByte();
        int t = d.readUnsignedByte();
        int len = d.readInt();
        if (version != 1) throw new IOException("pairing: version " + version);
        if (t != type) throw new IOException("pairing: packet " + t + ", not " + type);
        if (len <= 0 || len > MAX_PAIRING_PAYLOAD) throw new IOException("pairing: a payload of " + len);
        byte[] b = new byte[len];
        d.readFully(b);
        return b;
    }

    /** RFC 5869, an empty salt (BoringSSL's HKDF with no salt: HashLen zeros). */
    static byte[] hkdfSha256(byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            ByteArrayOutputStream okm = new ByteArrayOutputStream();
            byte[] t = new byte[0];
            for (int i = 1; okm.size() < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                okm.write(t, 0, t.length);
            }
            byte[] out = new byte[length];
            System.arraycopy(okm.toByteArray(), 0, out, 0, length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** aes_128_gcm.cpp: AES-128-GCM, a nonce of a counter per direction (8 bytes, little-endian) and 4 zeros. */
    static final class Gcm {
        private final SecretKeySpec key;
        private long sealed;
        private long opened;

        Gcm(byte[] key) {
            this.key = new SecretKeySpec(key, "AES");
        }

        byte[] seal(byte[] plain) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(sealed++)));
                return c.doFinal(plain);
            } catch (GeneralSecurityException e) {
                throw new IOException("pairing: AES-GCM: " + e, e);
            }
        }

        byte[] open(byte[] sealedBytes) throws GeneralSecurityException {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(opened++)));
            return c.doFinal(sealedBytes);
        }

        private static byte[] nonce(long n) {
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(n).putInt(0).array();
        }
    }

    // ── SPAKE2 over Ed25519, BoringSSL's (spake25519.c), as adbd speaks it ──

    /**
     * BoringSSL's SPAKE2: Alice (adb's client, this app) masks with M, Bob
     * (the phone's pairing) with N; the names are adb's ("adb pair client"
     * and "adb pair server", with their NULs, as adb passes sizeof).
     * Exact integers on the curve rather than BoringSSL's tables: a pairing
     * is one handshake, a few dozen milliseconds of BigInteger.
     */
    static final class Spake2 {
        private final boolean alice;
        private final BigInteger priv;
        private final BigInteger passwordScalar;
        private final byte[] passwordHash;
        private final byte[] myMsg;

        Spake2(boolean alice, byte[] password, SecureRandom rnd) {
            this.alice = alice;
            byte[] r = new byte[64];
            rnd.nextBytes(r);
            // x25519_sc_reduce, then left_shift_3: a multiple of the cofactor.
            priv = fromLittleEndian(r).mod(Ed.L).shiftLeft(3);
            passwordHash = sha512(password);
            BigInteger s = fromLittleEndian(passwordHash).mod(Ed.L);
            // BoringSSL's fix for its missing left_shift_3 (the password scalar
            // hack): s, s+l, s+2l... whichever is a multiple of eight.
            BigInteger order = Ed.L;
            if (s.testBit(0)) s = s.add(order);
            order = order.shiftLeft(1);
            if (s.testBit(1)) s = s.add(order);
            order = order.shiftLeft(1);
            if (s.testBit(2)) s = s.add(order);
            passwordScalar = s;
            Ed.Point mask = (alice ? Ed.M : Ed.N).times(passwordScalar);
            myMsg = Ed.B.times(priv).plus(mask).encode();
        }

        byte[] msg() {
            return myMsg.clone();
        }

        /** The 64 bytes of key material both sides end with when the passwords match. */
        byte[] key(byte[] theirMsg) throws GeneralSecurityException {
            if (theirMsg.length != 32) throw new GeneralSecurityException("a SPAKE2 message of " + theirMsg.length + " bytes");
            Ed.Point q = Ed.Point.decode(theirMsg);
            if (q == null) throw new GeneralSecurityException("not on the curve");
            Ed.Point peersMask = (alice ? Ed.N : Ed.M).times(passwordScalar);
            byte[] dh = q.plus(peersMask.negate()).times(priv).encode();
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            byte[] aliceName = CLIENT_NAME;
            byte[] bobName = SERVER_NAME;
            withLength(sha, aliceName);
            withLength(sha, bobName);
            withLength(sha, alice ? myMsg : theirMsg);
            withLength(sha, alice ? theirMsg : myMsg);
            withLength(sha, dh);
            withLength(sha, passwordHash);
            return sha.digest();
        }

        private static void withLength(MessageDigest sha, byte[] b) {
            sha.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(b.length).array());
            sha.update(b);
        }

        private static byte[] sha512(byte[] b) {
            try {
                return MessageDigest.getInstance("SHA-512").digest(b);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Edwards25519 in affine coordinates: -x^2 + y^2 = 1 + d x^2 y^2 over p = 2^255 - 19. */
    static final class Ed {
        static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
        static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
        static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
        /** sqrt(-1). */
        static final BigInteger I = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
        static final Point B = Point.decode(littleEndian(BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P), 32));
        /** BoringSSL's M and N, as its spake25519.c gives them encoded (made from SHA-256 of a seed). */
        static final Point M = Point.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
        static final Point N = Point.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

        private Ed() {
        }

        static byte[] hex(String h) {
            byte[] b = new byte[h.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            return b;
        }

        static final class Point {
            static final Point ZERO = new Point(BigInteger.ZERO, BigInteger.ONE);
            final BigInteger x;
            final BigInteger y;

            Point(BigInteger x, BigInteger y) {
                this.x = x;
                this.y = y;
            }

            Point plus(Point o) {
                BigInteger xx = x.multiply(o.x).mod(P);
                BigInteger yy = y.multiply(o.y).mod(P);
                BigInteger k = D.multiply(xx).multiply(yy).mod(P);
                BigInteger nx = x.multiply(o.y).add(o.x.multiply(y)).multiply(BigInteger.ONE.add(k).modInverse(P)).mod(P);
                BigInteger ny = yy.add(xx).multiply(BigInteger.ONE.subtract(k).mod(P).modInverse(P)).mod(P);
                return new Point(nx, ny);
            }

            Point negate() {
                return new Point(P.subtract(x).mod(P), y);
            }

            Point times(BigInteger s) {
                Point r = ZERO;
                Point a = this;
                for (int i = 0; i < s.bitLength(); i++) {
                    if (s.testBit(i)) r = r.plus(a);
                    a = a.plus(a);
                }
                return r;
            }

            byte[] encode() {
                byte[] out = littleEndian(y, 32);
                if (x.testBit(0)) out[31] |= (byte) 0x80;
                return out;
            }

            /** x25519_ge_frombytes_vartime: y from 255 bits, x's parity from the top bit; null off the curve. */
            static Point decode(byte[] s) {
                if (s.length != 32) return null;
                byte[] c = s.clone();
                boolean odd = (c[31] & 0x80) != 0;
                c[31] &= 0x7f;
                BigInteger y = fromLittleEndian(c).mod(P);
                BigInteger yy = y.multiply(y).mod(P);
                BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
                BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
                BigInteger xx = u.multiply(v.modInverse(P)).mod(P);
                BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
                if (!x.multiply(x).mod(P).equals(xx)) {
                    x = x.multiply(I).mod(P);
                    if (!x.multiply(x).mod(P).equals(xx)) return null;
                }
                if (x.testBit(0) != odd) x = P.subtract(x).mod(P);
                return new Point(x, y);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Point && ((Point) o).x.equals(x) && ((Point) o).y.equals(y);
            }

            @Override
            public int hashCode() {
                return x.hashCode() * 31 + y.hashCode();
            }
        }
    }

    // ── the link (adb.cpp, transport TLS) ──

    /** One adb connection to this phone's adbd over Wireless debugging's TLS port. */
    static final class Link implements Closeable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        /** What adbd said it is: {@code device::ro.product.name=…;features=…}. */
        final String banner;
        private int nextLocal = 1;

        private Link(Socket socket, InputStream in, OutputStream out, String banner) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.banner = banner;
        }

        /**
         * adbd on {@code port} of this phone: CNXN, adbd's STLS, TLS on the same
         * socket with this app's key, adbd's CNXN. {@link NotPairedException}
         * when adbd refuses the key.
         */
        static Link open(int port, Keys k, int timeoutMs) throws IOException {
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
                raw.setSoTimeout(timeoutMs);
                raw.setTcpNoDelay(true);
                writePacket(raw.getOutputStream(), A_CNXN, A_VERSION, MAX_PAYLOAD,
                        "host::\u0000".getBytes(StandardCharsets.UTF_8));
                Packet p = readPacket(raw.getInputStream());
                if (p.cmd == A_AUTH) throw new IOException("adb without TLS on port " + port + ": not Wireless debugging's");
                if (p.cmd != A_STLS) throw new IOException("adb on port " + port + " answered " + Integer.toHexString(p.cmd));
                writePacket(raw.getOutputStream(), A_STLS, A_STLS_VERSION, 0, new byte[0]);
                SSLSocket s = (SSLSocket) tls(k).getSocketFactory().createSocket(raw, "127.0.0.1", port, true);
                s.setUseClientMode(true);
                s.setEnabledProtocols(new String[]{"TLSv1.3"});
                s.setSoTimeout(timeoutMs);
                try {
                    s.startHandshake();
                } catch (SSLException e) {
                    if (refusedKey(e)) throw new NotPairedException("adbd refused this app's key", e);
                    throw e;
                }
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                Packet c;
                try {
                    c = readPacket(in);
                } catch (EOFException e) {
                    // TLS 1.3: adbd checks the app's certificate after the client's
                    // half of the handshake, and an unknown key ends it here.
                    throw new NotPairedException("adbd closed the link after the app's key", e);
                } catch (SSLException e) {
                    if (refusedKey(e)) throw new NotPairedException("adbd refused this app's key", e);
                    throw e;
                }
                if (c.cmd != A_CNXN) throw new IOException("adbd's first packet is " + Integer.toHexString(c.cmd));
                return new Link(s, in, out, new String(c.data, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
                throw e;
            }
        }

        /** A TLS alert about the certificate: adbd's word that it does not know this key (BoringSSL and Conscrypt both name it). */
        static boolean refusedKey(SSLException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                String m = t.getMessage();
                if (m != null && m.toLowerCase(Locale.ROOT).contains("certificate")) return true;
            }
            return false;
        }

        /** Runs {@code command} in adb's shell and returns what it wrote, once the shell ends its stream. */
        String shell(String command, int timeoutMs) throws IOException {
            int local = nextLocal++;
            socket.setSoTimeout(timeoutMs);
            writePacket(out, A_OPEN, local, 0, ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            int remote = 0;
            long until = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < until) {
                Packet p = readPacket(in);
                if (p.arg1 != local) continue;
                if (p.cmd == A_OKAY) {
                    remote = p.arg0;
                } else if (p.cmd == A_WRTE) {
                    said.write(p.data, 0, p.data.length);
                    writePacket(out, A_OKAY, local, p.arg0, new byte[0]);
                } else if (p.cmd == A_CLSE) {
                    if (remote == 0) throw new IOException("adbd refused the shell");
                    writePacket(out, A_CLSE, local, remote, new byte[0]);
                    return new String(said.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            throw new IOException("the shell did not end within " + timeoutMs + " ms");
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
