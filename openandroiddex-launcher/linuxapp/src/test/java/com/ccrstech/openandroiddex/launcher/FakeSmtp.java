package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * A stand-in mail server on this computer, for the email tests (never a real
 * one): SSL/TLS from the first byte, STARTTLS, a server that offers no TLS at
 * all, one that never says a word, or one that says a byte at a time. Its
 * certificate is made here, for {@code localhost} (LinuxBrowserCert, the
 * door's own maker), thrown away with the test, and trusted only by the
 * factory {@link #trusting()} gives: never by the platform, never by the
 * phone.
 *
 * <p>It records every line it was sent, and whether TLS was on when it came,
 * so a test can say what crossed the network in the clear.
 */
final class FakeSmtp implements Closeable {

    enum Mode { SSL, STARTTLS, NO_TLS, SILENT, TRICKLE }

    /** One message as the server took it: the envelope, the bytes after DATA (dots undone), and whether TLS carried it. */
    static final class Mail {
        final String from;
        final String to;
        final byte[] data;
        final boolean tls;

        Mail(String from, String to, byte[] data, boolean tls) {
            this.from = from;
            this.to = to;
            this.data = data;
            this.tls = tls;
        }

        String text() {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    final Mode mode;
    final LinuxBrowserCert cert;
    private final ServerSocket server;
    private volatile boolean closed;

    /** What EHLO offers to sign in with. */
    volatile String auth = "PLAIN LOGIN";
    volatile String authReply = "235 2.7.0 Accepted";
    volatile String mailReply = "250 2.1.0 OK";
    volatile String rcptReply = "250 2.1.5 OK";
    volatile String dataReply = "250 2.0.0 OK queued";
    /** Takes no first answer with AUTH PLAIN: asks for it again (334), as some servers do. */
    volatile boolean plainAsks;

    /** Every line received, as "tls|…" or "plain|…". */
    final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    final List<Mail> mails = Collections.synchronizedList(new ArrayList<>());
    volatile String user;
    volatile String password;
    volatile boolean loginOverTls;
    volatile int connections;

    FakeSmtp(Mode mode, File certDir, List<String> certIps) throws Exception {
        this(mode, certDir, certIps, 0);
    }

    /** On {@code port} (0: any free one), as a server that comes back where it was. */
    FakeSmtp(Mode mode, File certDir, List<String> certIps, int port) throws Exception {
        this.mode = mode;
        this.cert = LinuxBrowserCert.make(certDir, certIps, "LinuxOnDroid test server", System.currentTimeMillis());
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        this.server = s;
        Thread t = new Thread(this::acceptLoop, "fake-smtp");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** A client factory that trusts this server's certificate, and nothing else. */
    SSLSocketFactory trusting() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("fake-smtp", cert.cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, tmf.getTrustManagers(), new SecureRandom());
        return c.getSocketFactory();
    }

    /** Whether any line that came before TLS was on contains {@code needle}. */
    boolean inTheClear(String needle) {
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith("plain|") && l.contains(needle)) return true;
            }
        }
        return false;
    }

    /** Whether any line at all starts with {@code command} (case ignored). */
    boolean saw(String command) {
        synchronized (lines) {
            for (String l : lines) {
                String line = l.substring(l.indexOf('|') + 1).toUpperCase(Locale.ROOT);
                if (line.startsWith(command.toUpperCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                connections++;
                Thread t = new Thread(() -> serve(s), "fake-smtp-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket raw = s) {
            raw.setSoTimeout(20_000);
            if (mode == Mode.SILENT) {
                // Takes the connection, says nothing, until the client goes.
                raw.getInputStream().read();
                return;
            }
            if (mode == Mode.TRICKLE) {
                OutputStream o = raw.getOutputStream();
                while (!closed) {
                    o.write('2');
                    o.flush();
                    Thread.sleep(300);
                }
                return;
            }
            Socket io = raw;
            boolean tls = false;
            if (mode == Mode.SSL) {
                io = wrap(raw);
                tls = true;
            }
            Conn c = new Conn(io);
            c.say("220 fake.example.com ESMTP ready");
            String from = null;
            String to = null;
            for (String line; (line = c.line()) != null; ) {
                lines.add((tls ? "tls|" : "plain|") + line);
                String up = line.toUpperCase(Locale.ROOT);
                if (up.startsWith("EHLO ")) {
                    List<String> caps = new ArrayList<>();
                    caps.add("fake.example.com greets you");
                    if (mode == Mode.STARTTLS && !tls) caps.add("STARTTLS");
                    // Like most servers, sign-in is offered once TLS is on; the
                    // server with no TLS offers it in the clear, as a trap.
                    if (tls || mode == Mode.NO_TLS) caps.add("AUTH " + auth);
                    caps.add("SIZE 35882577");
                    caps.add("8BITMIME");
                    c.multi(250, caps);
                } else if (up.equals("STARTTLS")) {
                    c.say("220 2.0.0 Ready to start TLS");
                    io = wrap(raw);
                    tls = true;
                    c = new Conn(io);
                } else if (up.startsWith("AUTH PLAIN")) {
                    String b64 = line.length() > 10 ? line.substring(10).trim() : "";
                    if (b64.isEmpty() || plainAsks) {
                        c.say("334 ");
                        b64 = c.line();
                        lines.add((tls ? "tls|" : "plain|") + b64);
                    }
                    String[] parts = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8).split("\0", -1);
                    user = parts.length > 1 ? parts[1] : "";
                    password = parts.length > 2 ? parts[2] : "";
                    loginOverTls = tls;
                    c.say(authReply);
                } else if (up.equals("AUTH LOGIN")) {
                    c.say("334 VXNlcm5hbWU6");
                    String u = c.line();
                    lines.add((tls ? "tls|" : "plain|") + u);
                    c.say("334 UGFzc3dvcmQ6");
                    String p = c.line();
                    lines.add((tls ? "tls|" : "plain|") + p);
                    user = new String(Base64.getDecoder().decode(u), StandardCharsets.UTF_8);
                    password = new String(Base64.getDecoder().decode(p), StandardCharsets.UTF_8);
                    loginOverTls = tls;
                    c.say(authReply);
                } else if (up.startsWith("MAIL FROM:")) {
                    from = between(line);
                    c.say(mailReply);
                } else if (up.startsWith("RCPT TO:")) {
                    to = between(line);
                    c.say(rcptReply);
                } else if (up.equals("DATA")) {
                    c.say("354 Go ahead");
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    for (String l; (l = c.line()) != null; ) {
                        if (l.equals(".")) break;
                        if (l.startsWith("..")) l = l.substring(1);
                        body.write((l + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                    mails.add(new Mail(from, to, body.toByteArray(), tls));
                    c.say(dataReply);
                } else if (up.equals("QUIT")) {
                    c.say("221 2.0.0 Bye");
                    return;
                } else {
                    c.say("502 5.5.2 Command not recognized");
                }
            }
        } catch (Exception ignored) {
            // the client went, or the test ended
        }
    }

    private static String between(String line) {
        int a = line.indexOf('<');
        int b = line.indexOf('>');
        return a >= 0 && b > a ? line.substring(a + 1, b) : line;
    }

    private Socket wrap(Socket raw) throws IOException {
        SSLSocket ssl = (SSLSocket) cert.context.getSocketFactory()
                .createSocket(raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
        ssl.setUseClientMode(false);
        ssl.startHandshake();
        return ssl;
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    /** Lines in and replies out, CRLF, on whatever the connection is now. */
    private static final class Conn {
        private final InputStream in;
        private final OutputStream out;

        Conn(Socket s) throws IOException {
            in = new BufferedInputStream(s.getInputStream());
            out = s.getOutputStream();
        }

        String line() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (true) {
                int x = in.read();
                if (x < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
                if (x == '\n') {
                    String s = b.toString("ISO-8859-1");
                    return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
                }
                b.write(x);
            }
        }

        /** One reply, or several lines of one when it holds CRLFs ("534-5.7.9 …\r\n534 5.7.9 …"). */
        void say(String reply) throws IOException {
            out.write((reply + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        void multi(int code, List<String> lines) throws IOException {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                b.append(code).append(i == lines.size() - 1 ? ' ' : '-').append(lines.get(i)).append("\r\n");
            }
            out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
    }
}
