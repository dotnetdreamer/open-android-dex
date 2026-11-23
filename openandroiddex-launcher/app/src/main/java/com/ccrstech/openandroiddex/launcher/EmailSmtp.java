package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One email, sent through the user's own email service (Gmail, iCloud, Yahoo,
 * a work server): the small part of SMTP that takes, written here because
 * Android has no mail library. Used by "Email me when an address changes"
 * ({@link EmailNotice}). Design record: doc/address-email.md.
 *
 * <p><b>Always encrypted, before the password goes.</b> SSL/TLS (port 465)
 * speaks TLS from the first byte; STARTTLS (port 587) asks the server to turn
 * TLS on before anything else, and a server that does not offer STARTTLS is
 * refused, never written to in the clear. Either way the login and the
 * message cross the network only inside TLS, TLS 1.2 or 1.3.
 *
 * <p><b>The server is the one named.</b> Its certificate must chain to an
 * authority the phone trusts (the platform's), and must name the server's
 * host. The name is checked here as well as by the platform, the same way on
 * a phone and on a computer's JVM: Android's own SSLSocket checks no names
 * unless told how, and how it is told differs between versions.
 *
 * <p><b>Never in the clear, never written down.</b> The password exists only
 * as the AUTH command's base64, on the encrypted socket. No reply, error or
 * log line here carries it: a failure says what the server answered, which a
 * server never fills with the password.
 *
 * <p>Pure Java, so the tests on a computer run this very file against a
 * stand-in server (linuxapp/src/test, EmailSmtpTest).
 */
final class EmailSmtp {

    private EmailSmtp() {
    }

    enum Security { SSL, STARTTLS }

    /** Where the email goes through, and who signs in there. */
    static final class Server {
        final String host;
        final int port;
        final Security security;
        final String user;
        final String password;

        Server(String host, int port, Security security, String user, String password) {
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.security = security;
            this.user = user == null ? "" : user.trim();
            this.password = password == null ? "" : password;
        }

        @Override
        public String toString() {
            // Never the password, wherever this ends up.
            return host + ":" + port + " " + security + " as " + user;
        }
    }

    /**
     * Why an email did not go, in the kinds a person can act on. {@code
     * passing} is a fault a later try may not meet (the network, a busy
     * server): those are tried again, the rest wait for new settings.
     */
    enum Fault {
        /** No route to the server, its name not found, the connection refused or cut. */
        NETWORK(true),
        /** The server took too long to answer. */
        TIMEOUT(true),
        /** The server said to try later (4xx). */
        BUSY(true),
        /** Its certificate is not trusted, or names another server. */
        CERTIFICATE(false),
        /** SSL/TLS against a STARTTLS port, or the other way round. */
        SECURITY(false),
        /** The encrypted connection could not be made for another reason. */
        TLS(false),
        /** A STARTTLS server that does not offer STARTTLS: nothing is sent in the clear. */
        NO_TLS(false),
        /** The server offers no way to sign in that this knows (PLAIN or LOGIN). */
        NO_LOGIN(false),
        /** The name or the password was refused. */
        LOGIN(false),
        /** Gmail's "application-specific password required": the normal password was used. */
        APP_PASSWORD(false),
        /** Microsoft's "basic authentication is disabled": app passwords no longer work there. */
        BASIC_OFF(false),
        /** The sending address was refused. */
        SENDER(false),
        /** The address to send to was refused. */
        RECIPIENT(false),
        /** The message itself was refused (as spam, too large, a policy). */
        REFUSED(false),
        /** The server said something that is not SMTP. */
        PROTOCOL(false);

        final boolean passing;

        Fault(boolean passing) {
            this.passing = passing;
        }
    }

    /** An email that did not go: the kind, and the server's own words when it said any. */
    static final class Failure extends Exception {
        final Fault fault;
        /** What the server answered, printable and short, or "" (never the password). */
        final String said;

        Failure(Fault fault, String said) {
            super(fault + (said == null || said.isEmpty() ? "" : ": " + said));
            this.fault = fault;
            this.said = said == null ? "" : said;
        }

        /** With what the platform threw, for a log line (a socket's or TLS's words, never the password). */
        Failure(Fault fault, Throwable cause) {
            this(fault, "");
            initCause(cause);
        }
    }

    /** How long each part may take. */
    static final class Timing {
        final int connectMs;
        final int readMs;
        final long totalMs;

        Timing(int connectMs, int readMs, long totalMs) {
            this.connectMs = connectMs;
            this.readMs = readMs;
            this.totalMs = totalMs;
        }

        /** A phone on a slow network still gets through; a server that trickles is given up. */
        static final Timing PHONE = new Timing(15_000, 30_000, 90_000);
    }

    // ── the message ──

    /**
     * An email with a plain text part and an HTML part of the same words
     * (multipart/alternative): a mail app shows the HTML, and one that shows
     * no HTML, or a watch, the text.
     */
    static final class Message {
        final String fromName;
        final String from;
        final String to;
        final String subject;
        final String text;
        final String html;

        Message(String fromName, String from, String to, String subject, String text, String html) {
            this.fromName = fromName == null ? "" : fromName;
            this.from = from;
            this.to = to;
            this.subject = subject == null ? "" : subject;
            this.text = text == null ? "" : text;
            this.html = html;
        }

        /**
         * The message as it goes after DATA, before dot-stuffing: RFC 5322
         * headers, every line CRLF, nothing but ASCII (the words are UTF-8
         * in base64, a header's in encoded words).
         */
        byte[] bytes(long now, SecureRandom random) {
            StringBuilder b = new StringBuilder(4096);
            SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
            header(b, "Date", date.format(new Date(now)));
            header(b, "From", (fromName.isEmpty() ? "" : displayName(fromName) + " ") + "<" + from + ">");
            header(b, "To", "<" + to + ">");
            header(b, "Subject", headerText(subject));
            header(b, "Message-ID", "<" + hex(random, 16) + "@" + domainOf(from) + ">");
            header(b, "MIME-Version", "1.0");
            // Sent by the phone itself: out-of-office replies are not to answer it (RFC 3834).
            header(b, "Auto-Submitted", "auto-generated");
            header(b, "X-Auto-Response-Suppress", "All");
            if (html == null) {
                part(b, "text/plain", text);
            } else {
                // "=_" cannot start a line of base64, so the boundary is never in a part.
                String boundary = "=_lod_" + hex(random, 12);
                header(b, "Content-Type", "multipart/alternative; boundary=\"" + boundary + "\"");
                b.append("\r\n");
                b.append("--").append(boundary).append("\r\n");
                part(b, "text/plain", text);
                b.append("--").append(boundary).append("\r\n");
                part(b, "text/html", html);
                b.append("--").append(boundary).append("--\r\n");
            }
            return b.toString().getBytes(StandardCharsets.US_ASCII);
        }

        private static void part(StringBuilder b, String type, String body) {
            header(b, "Content-Type", type + "; charset=UTF-8");
            header(b, "Content-Transfer-Encoding", "base64");
            b.append("\r\n");
            String lines = Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                    .encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            b.append(lines);
            if (!lines.isEmpty()) b.append("\r\n");
        }

        private static void header(StringBuilder b, String name, String value) {
            b.append(name).append(": ").append(value).append("\r\n");
        }
    }

    /**
     * A header's words: printable ASCII as they are, anything else as RFC
     * 2047 encoded words (UTF-8, base64), cut between characters so that each
     * is under 76 characters, and folded onto lines of their own.
     */
    static String headerText(String s) {
        String one = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (printableAscii(one) && one.length() <= 200) return one;
        return encodedWords(one);
    }

    /** A display name: a quoted string when it is plain ASCII, encoded words otherwise. */
    static String displayName(String s) {
        String one = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (printableAscii(one) && one.length() <= 120) {
            return "\"" + one.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return encodedWords(one);
    }

    private static String encodedWords(String s) {
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String c = new String(Character.toChars(cp));
            int n = c.getBytes(StandardCharsets.UTF_8).length;
            // 45 bytes of UTF-8 are 60 of base64: the word with its marks is 72.
            if (bytes + n > 45 && chunk.length() > 0) {
                word(out, chunk.toString());
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.append(c);
            bytes += n;
            i += Character.charCount(cp);
        }
        if (chunk.length() > 0 || out.length() == 0) word(out, chunk.toString());
        return out.toString();
    }

    private static void word(StringBuilder out, String chunk) {
        if (out.length() > 0) out.append("\r\n ");
        out.append("=?UTF-8?B?")
                .append(Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8)))
                .append("?=");
    }

    private static boolean printableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    /** The part after the @ when it is a plain domain, for the Message-ID. */
    private static String domainOf(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        String d = at < 0 ? "" : address.substring(at + 1);
        return d.matches("[A-Za-z0-9.-]{1,253}") ? d : "linuxondroid.invalid";
    }

    private static String hex(SecureRandom random, int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(n * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    /**
     * An address as a mail server takes it in MAIL FROM and RCPT TO: ASCII,
     * one @, a domain with a dot, nothing that could end a command or open
     * another (no spaces, angle brackets, CR or LF). Names in other scripts
     * (SMTPUTF8) are not offered.
     */
    static boolean validAddress(String a) {
        if (a == null || a.length() > 254) return false;
        return a.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    }

    /** A server's name or address as typed: letters, digits, dots and dashes, or an IP address. */
    static boolean validHost(String h) {
        if (h == null) return false;
        String t = h.trim();
        return t.length() > 0 && t.length() <= 253 && t.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?");
    }

    /** Lines that start with a dot get one more (RFC 5321, 4.5.2), and the end mark. */
    static byte[] dotStuffed(byte[] message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + 64);
        boolean lineStart = true;
        for (byte x : message) {
            if (lineStart && x == '.') out.write('.');
            out.write(x);
            lineStart = x == '\n';
        }
        if (!lineStart) out.write(new byte[]{'\r', '\n'}, 0, 2);
        out.write(new byte[]{'.', '\r', '\n'}, 0, 3);
        return out.toByteArray();
    }

    // ── sending ──

    /**
     * Sends {@code m} through {@code s}, or says why not. {@code tls} makes
     * the encrypted sockets: the platform's own (its trusted authorities) on
     * a phone, one that trusts a test certificate in the tests.
     */
    static void send(Server s, Message m, SSLSocketFactory tls, Timing t, long now) throws Failure {
        if (!validAddress(m.from)) throw new Failure(Fault.SENDER, "");
        if (!validAddress(m.to)) throw new Failure(Fault.RECIPIENT, "");
        Socket raw = new Socket();
        Deadline deadline = new Deadline(raw, t.totalMs);
        try {
            try {
                raw.connect(new InetSocketAddress(asciiHost(s.host), s.port), t.connectMs);
            } catch (UnknownHostException e) {
                throw new Failure(Fault.NETWORK, "");
            } catch (SocketTimeoutException e) {
                throw new Failure(Fault.TIMEOUT, "");
            } catch (IllegalArgumentException e) {
                throw new Failure(Fault.NETWORK, "");
            }
            raw.setSoTimeout(t.readMs);
            Conn c = new Conn(raw);
            if (s.security == Security.SSL) c.upgrade(tls, s.host);
            Reply hello;
            try {
                hello = c.read();
            } catch (SocketTimeoutException e) {
                // STARTTLS against a port that waits for TLS first says nothing at all.
                throw new Failure(s.security == Security.STARTTLS && s.port == 465 ? Fault.SECURITY : Fault.TIMEOUT, "");
            }
            c.expect(hello, Step.GREETING, 220);
            String name = ehloName(raw);
            List<String> caps = c.ehlo(name);
            if (s.security == Security.STARTTLS) {
                if (!has(caps, "STARTTLS")) throw new Failure(Fault.NO_TLS, "");
                c.command("STARTTLS", Step.TLS, 220);
                c.upgrade(tls, s.host);
                caps = c.ehlo(name);
            }
            c.login(caps, s.user, s.password);
            c.command("MAIL FROM:<" + m.from + ">", Step.SENDER, 250);
            c.command("RCPT TO:<" + m.to + ">", Step.RECIPIENT, 250, 251);
            c.command("DATA", Step.MESSAGE, 354);
            c.out.write(dotStuffed(m.bytes(now, new SecureRandom())));
            c.out.flush();
            c.expect(c.read(), Step.MESSAGE, 250);
            try {
                c.command("QUIT", Step.QUIT, 221);
            } catch (Failure | IOException ignored) {
                // The message is taken; how the server says goodbye does not matter.
            }
        } catch (Failure f) {
            throw deadline.fired && f.fault == Fault.NETWORK ? new Failure(Fault.TIMEOUT, f) : f;
        } catch (SocketTimeoutException e) {
            throw new Failure(Fault.TIMEOUT, e);
        } catch (SSLException e) {
            throw new Failure(deadline.fired ? Fault.TIMEOUT : tlsFault(e), e);
        } catch (IOException e) {
            throw new Failure(deadline.fired ? Fault.TIMEOUT : Fault.NETWORK, e);
        } finally {
            deadline.cancel();
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** A handshake that failed: the certificate, a plain server where TLS was asked for, or something else. */
    static Fault tlsFault(SSLException e) {
        for (Throwable x = e; x != null; x = x.getCause()) {
            if (x instanceof CertificateException
                    || x instanceof java.security.cert.CertPathValidatorException
                    || x instanceof javax.net.ssl.SSLPeerUnverifiedException) {
                return Fault.CERTIFICATE;
            }
            String why = String.valueOf(x.getMessage()).toLowerCase(Locale.ROOT);
            if (why.contains("certificate") || why.contains("trust anchor") || why.contains("pkix")
                    || why.contains("no subject alternative") || why.contains("hostname")) {
                return Fault.CERTIFICATE;
            }
            // A plain server where TLS was asked for: the JDK says "Unsupported or
            // unrecognized SSL message", Conscrypt (Android's TLS, and the tests'
            // when Robolectric installs it) "Unable to parse TLS packet header" or
            // BoringSSL's WRONG_VERSION_NUMBER.
            if (why.contains("unrecognized ssl message") || why.contains("wrong_version_number")
                    || why.contains("plaintext connection") || why.contains("unsupported or unrecognized")
                    || why.contains("tls packet header") || why.contains("not an ssl/tls record")) {
                return Fault.SECURITY;
            }
        }
        return e instanceof SSLHandshakeException ? Fault.TLS : Fault.NETWORK;
    }

    private static String asciiHost(String host) {
        try {
            return IDN.toASCII(host.trim());
        } catch (Exception e) {
            return host.trim();
        }
    }

    /** The name this end gives in EHLO: its own address, in brackets, as RFC 5321 allows. */
    private static String ehloName(Socket raw) {
        InetAddress a = raw.getLocalAddress();
        if (a == null) return "[127.0.0.1]";
        String ip = a.getHostAddress();
        int scope = ip.indexOf('%');
        if (scope >= 0) ip = ip.substring(0, scope);
        return a instanceof Inet6Address ? "[IPv6:" + ip + "]" : "[" + ip + "]";
    }

    private static boolean has(List<String> caps, String what) {
        for (String c : caps) {
            if (c.equals(what) || c.startsWith(what + " ")) return true;
        }
        return false;
    }

    /** What a server offers to sign in with: "AUTH PLAIN LOGIN", and the old "AUTH=LOGIN". */
    static Set<String> authMechanisms(List<String> caps) {
        Set<String> out = new HashSet<>();
        for (String c : caps) {
            if (c.startsWith("AUTH ") || c.startsWith("AUTH=")) {
                for (String m : c.substring(5).trim().split("\\s+")) {
                    if (!m.isEmpty()) out.add(m);
                }
            }
        }
        return out;
    }

    /** Which step a reply answers: the same code means different things at each. */
    private enum Step { GREETING, HELLO, TLS, LOGIN, SENDER, RECIPIENT, MESSAGE, QUIT }

    /** A server's reply: its code and its lines' words. */
    static final class Reply {
        final int code;
        final List<String> lines;

        Reply(int code, List<String> lines) {
            this.code = code;
            this.lines = lines;
        }

        /** The words, on one line, printable, short: what a person may be shown. */
        String said() {
            StringBuilder b = new StringBuilder();
            b.append(code);
            for (String l : lines) b.append(' ').append(l);
            String one = b.toString().replaceAll("[^\\x20-\\x7e]", " ").replaceAll("\\s+", " ").trim();
            return one.length() > 300 ? one.substring(0, 300) : one;
        }
    }

    /** The fault a refusal at {@code step} is. */
    static Fault faultOf(Reply r, Step step) {
        String words = r.said().toLowerCase(Locale.ROOT);
        if (r.code >= 400 && r.code < 500) {
            // "454 4.7.0 Too many login attempts" and the like: later, again.
            return Fault.BUSY;
        }
        switch (step) {
            case LOGIN:
                if (words.contains("5.7.139") || words.contains("basic authentication is disabled")) return Fault.BASIC_OFF;
                if (words.contains("5.7.9") || words.contains("application-specific password")
                        || words.contains("app password") || words.contains("apppassword")) {
                    return Fault.APP_PASSWORD;
                }
                if (r.code == 530 || r.code == 538) return Fault.NO_TLS;
                return Fault.LOGIN;
            case SENDER:
                return Fault.SENDER;
            case RECIPIENT:
                return Fault.RECIPIENT;
            case TLS:
                return Fault.NO_TLS;
            case GREETING:
            case HELLO:
                return r.code == 554 || r.code == 521 ? Fault.REFUSED : Fault.PROTOCOL;
            default:
                return Fault.REFUSED;
        }
    }

    /** One connection: replies read, commands written, TLS put over it. */
    private static final class Conn {
        private final Socket raw;
        private Socket io;
        private InputStream in;
        OutputStream out;

        Conn(Socket raw) throws IOException {
            this.raw = raw;
            use(raw);
        }

        private void use(Socket s) throws IOException {
            io = s;
            in = new BufferedInputStream(s.getInputStream(), 4096);
            out = s.getOutputStream();
        }

        /**
         * TLS over the connection as it stands: the platform's checks of
         * the certificate (the trusted authorities, and its own name check
         * where it makes one), then this file's name check, the same on every
         * Android version.
         */
        void upgrade(SSLSocketFactory tls, String host) throws IOException, Failure {
            SSLSocket ssl = (SSLSocket) tls.createSocket(raw, asciiHost(host), raw.getPort(), true);
            ssl.setUseClientMode(true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            List<String> modern = new ArrayList<>();
            for (String v : ssl.getSupportedProtocols()) {
                if (v.equals("TLSv1.3") || v.equals("TLSv1.2")) modern.add(v);
            }
            if (!modern.isEmpty()) p.setProtocols(modern.toArray(new String[0]));
            if (!isIpLiteral(host)) {
                try {
                    p.setServerNames(Collections.singletonList(new SNIHostName(asciiHost(host))));
                } catch (IllegalArgumentException ignored) {
                    // not a name SNI takes: the platform sends none
                }
            }
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate)
                    || !nameMatches(host, (X509Certificate) chain[0])) {
                throw new Failure(Fault.CERTIFICATE, "");
            }
            use(ssl);
        }

        List<String> ehlo(String name) throws IOException, Failure {
            write("EHLO " + name);
            Reply r = read();
            expect(r, Step.HELLO, 250);
            List<String> caps = new ArrayList<>();
            // The first line is the server's name; the rest are what it offers.
            for (int i = 1; i < r.lines.size(); i++) caps.add(r.lines.get(i).trim().toUpperCase(Locale.ROOT));
            return caps;
        }

        void login(List<String> caps, String user, String password) throws IOException, Failure {
            Set<String> ways = authMechanisms(caps);
            Base64.Encoder b64 = Base64.getEncoder();
            if (ways.contains("PLAIN")) {
                String both = b64.encodeToString(("\0" + user + "\0" + password).getBytes(StandardCharsets.UTF_8));
                write("AUTH PLAIN " + both);
                Reply r = read();
                // A server that takes no first answer asks for it again.
                if (r.code == 334) {
                    write(both);
                    r = read();
                }
                expect(r, Step.LOGIN, 235);
            } else if (ways.contains("LOGIN")) {
                command("AUTH LOGIN", Step.LOGIN, 334);
                command(b64.encodeToString(user.getBytes(StandardCharsets.UTF_8)), Step.LOGIN, 334);
                command(b64.encodeToString(password.getBytes(StandardCharsets.UTF_8)), Step.LOGIN, 235);
            } else {
                throw new Failure(Fault.NO_LOGIN, "");
            }
        }

        void command(String line, Step step, int... ok) throws IOException, Failure {
            write(line);
            expect(read(), step, ok);
        }

        void expect(Reply r, Step step, int... ok) throws Failure {
            for (int c : ok) {
                if (r.code == c) return;
            }
            throw new Failure(faultOf(r, step), r.said());
        }

        private void write(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** A whole reply, its lines joined; a server that sends no end or no code is cut short. */
        Reply read() throws IOException, Failure {
            List<String> lines = new ArrayList<>();
            for (int n = 0; n < 100; n++) {
                String line = readLine();
                if (line == null) throw new IOException("closed");
                if (line.length() < 3 || !Character.isDigit(line.charAt(0)) || !Character.isDigit(line.charAt(1))
                        || !Character.isDigit(line.charAt(2))) {
                    throw new Failure(Fault.PROTOCOL, "");
                }
                int code = Integer.parseInt(line.substring(0, 3));
                lines.add(line.length() > 4 ? line.substring(4) : "");
                if (line.length() == 3 || line.charAt(3) == ' ') return new Reply(code, lines);
                if (line.charAt(3) != '-') throw new Failure(Fault.PROTOCOL, "");
            }
            throw new Failure(Fault.PROTOCOL, "");
        }

        private String readLine() throws IOException, Failure {
            ByteArrayOutputStream b = new ByteArrayOutputStream(128);
            while (true) {
                int x = in.read();
                if (x < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
                if (x == '\n') {
                    byte[] got = b.toByteArray();
                    int len = got.length > 0 && got[got.length - 1] == '\r' ? got.length - 1 : got.length;
                    return new String(got, 0, len, StandardCharsets.ISO_8859_1);
                }
                if (b.size() >= 4096) throw new Failure(Fault.PROTOCOL, "");
                b.write(x);
            }
        }
    }

    // ── the server's name in its certificate ──

    /**
     * Whether {@code cert} names {@code host} (RFC 6125): an IP address
     * against the certificate's IP addresses, a name against its DNS names,
     * a wildcard only as the whole first label and never for less than two
     * labels after it. The subject's common name is not read, as browsers no
     * longer read it.
     */
    static boolean nameMatches(String host, X509Certificate cert) {
        String h = asciiHost(host).toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        boolean ip = isIpLiteral(h);
        Collection<List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (Exception e) {
            return false;
        }
        if (sans == null) return false;
        for (List<?> san : sans) {
            if (san.size() < 2 || !(san.get(0) instanceof Integer)) continue;
            int type = (Integer) san.get(0);
            String value = String.valueOf(san.get(1)).toLowerCase(Locale.ROOT);
            if (ip && type == 7) {
                if (sameIp(h, value)) return true;
            } else if (!ip && type == 2) {
                if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
                if (dnsMatches(h, value)) return true;
            }
        }
        return false;
    }

    static boolean dnsMatches(String host, String pattern) {
        if (pattern.isEmpty()) return false;
        if (!pattern.contains("*")) return host.equals(pattern);
        // "*.example.com" and nothing else: the star alone as the first label.
        if (!pattern.startsWith("*.") || pattern.indexOf('*', 1) >= 0) return false;
        String rest = pattern.substring(1); // ".example.com"
        if (rest.indexOf('.', 1) < 0) return false; // "*.com"
        if (!host.endsWith(rest)) return false;
        String first = host.substring(0, host.length() - rest.length());
        return !first.isEmpty() && first.indexOf('.') < 0;
    }

    static boolean isIpLiteral(String h) {
        String t = h.trim();
        if (t.startsWith("[") && t.endsWith("]")) return true;
        if (t.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;
        return t.indexOf(':') >= 0 && t.matches("[0-9A-Fa-f:.]+");
    }

    private static boolean sameIp(String a, String b) {
        try {
            String x = a.startsWith("[") ? a.substring(1, a.length() - 1) : a;
            String y = b.startsWith("[") ? b.substring(1, b.length() - 1) : b;
            // Literals only: getByName never looks a literal up.
            return isIpLiteral(x) && isIpLiteral(y) && InetAddress.getByName(x).equals(InetAddress.getByName(y));
        } catch (Exception e) {
            return false;
        }
    }

    /** Closes the connection when the whole send has taken too long, however slowly a server trickles. */
    private static final class Deadline implements Runnable {
        private final Socket socket;
        private final long ms;
        private final Thread thread;
        volatile boolean fired;

        Deadline(Socket socket, long ms) {
            this.socket = socket;
            this.ms = ms;
            thread = new Thread(this, "email-deadline");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                return;
            }
            fired = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        void cancel() {
            thread.interrupt();
        }
    }
}
