package com.ccrstech.openandroiddex.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The door's sign-in links ("Email me when an address changes",
 * EmailNotice; POST /link in LinuxBrowserServer), with real sockets, on a
 * computer's JVM: the launcher's own door classes, compiled with
 * test/linux-browser/DexLog.java in place of Android's log, exactly as
 * StoreCheck is. Through the Cloudflare tunnel's way in (loopback, the
 * tunnel's name in Host, the address Cloudflare saw in CF-Connecting-IP), as
 * cloudflared on a phone brings a browser from anywhere.
 *
 * <p>Run (from the repository's root, JDK 17 or newer):
 * <pre>
 *   javac -nowarn -d &lt;classes&gt; openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher/{LinuxBrowser,
 *       LinuxBrowserCert,LinuxBrowserServer,LinuxStreamSocket,LinuxStreamAudio,LinuxAudioLag,LinuxFiles,LinuxStreamRate}.java
 *       test/linux-browser/DexLog.java test/linux-browser/LinkCheck.java
 *   java -cp &lt;classes&gt; com.ccrstech.openandroiddex.launcher.LinkCheck openandroiddex-launcher/app/src/main/assets/linux/browser
 * </pre>
 * Prints a line per check, "LINK passed=&lt;n&gt; failed=&lt;n&gt;" last, and exits 1
 * on any failure. Nothing leaves this computer.
 */
final class LinkCheck {

    private static final String TUNNEL = "quiet-river-fox.trycloudflare.com";
    private static int passed;
    private static int failed;

    private static void check(String what, boolean ok, Object detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + "  (" + detail + ")");
        }
    }

    /** status, then the body. */
    private static String[] call(SSLSocketFactory tls, int port, String method, String path, String origin, String from,
                                 String body, String token) throws Exception {
        Socket raw = new Socket("127.0.0.1", port);
        Socket s = raw;
        if (tls != null) {
            SSLSocket ssl = (SSLSocket) tls.createSocket(raw, "localhost", port, true);
            ssl.startHandshake();
            s = ssl;
        }
        s.setSoTimeout(10_000);
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(TUNNEL).append("\r\n");
        if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
        req.append("User-Agent: Mozilla/5.0 (Windows NT 10.0) Chrome/150.0 Safari/537.36 Edg/150.0\r\n");
        if (from != null) req.append("CF-Connecting-IP: ").append(from).append("\r\n");
        if (token != null) req.append("Authorization: Bearer ").append(token).append("\r\n");
        if (body != null) {
            req.append("Content-Type: application/x-www-form-urlencoded\r\n");
            req.append("Content-Length: ").append(b.length).append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
        InputStream in = s.getInputStream();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            for (int n; (n = in.read(buf)) > 0; ) all.write(buf, 0, n);
        } catch (java.io.IOException closed) {
            // the door closes after its answer
        }
        s.close();
        String text = all.toString("ISO-8859-1");
        int sp = text.indexOf(' ');
        String status = sp > 0 && text.length() >= sp + 4 ? text.substring(sp + 1, sp + 4) : "none";
        int split = text.indexOf("\r\n\r\n");
        return new String[]{status, split >= 0 ? text.substring(split + 4) : "", split >= 0 ? text.substring(0, split) : text};
    }

    private static String[] link(SSLSocketFactory tls, int port, String secret, String from) throws Exception {
        return call(tls, port, "POST", "/link", "https://" + TUNNEL, from, "k=" + secret, null);
    }

    /** An email's links, made and gone out with it (the last email's replaced). */
    private static List<String> email(LinuxBrowser store, List<String> hosts, long at) {
        List<String> s = store.newLinks(hosts, at);
        store.confirmLinks(s);
        return s;
    }

    private static String token(String json) {
        int a = json.indexOf("\"token\":\"");
        if (a < 0) return null;
        int b = json.indexOf('"', a + 9);
        return json.substring(a + 9, b);
    }

    public static void main(String[] args) throws Exception {
        File assets = new File(args.length > 0 ? args[0] : "openandroiddex-launcher/app/src/main/assets/linux/browser");
        File dir = Files.createTempDirectory("oadx-link-").toFile();
        // Everything the door logs, kept, to look for a secret in it afterwards.
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = System.err;
        System.setErr(new PrintStream(log, true, "UTF-8"));
        LinuxBrowserServer door = null;
        try {
            LinuxBrowser store = new LinuxBrowser(dir);
            store.setOn(true);
            long now = System.currentTimeMillis();
            LinuxBrowserCert cert = LinuxBrowserCert.make(dir, Collections.<String>emptyList(), LinuxBrowserCert.NAME, now);
            LinuxBrowserServer.tunnelHost = TUNNEL;
            LinuxBrowserServer.lockAfter = 5;
            door = LinuxBrowserServer.start(store, cert, new LinuxBrowserServer.Host() {
                @Override
                public boolean stream(LinuxStreamSocket ws, String hello, java.io.Closeable sock, String who,
                                      LinuxBrowserServer.Gate gate, Runnable ended) {
                    return false;
                }

                @Override
                public String phoneName() {
                    return "Test phone";
                }

                @Override
                public byte[] asset(String name) {
                    try {
                        return Files.readAllBytes(new File(assets, name).toPath());
                    } catch (Exception e) {
                        return null;
                    }
                }

                @Override
                public void changed() {
                }

                @Override
                public boolean linuxOn() {
                    return false;
                }
            }, null, 0);
            int port = door.port();

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("door", cert.cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, tmf.getTrustManagers(), new SecureRandom());
            SSLSocketFactory tls = c.getSocketFactory();

            System.out.println("the page");
            for (String f : new String[]{"signin.html", "signin.js", "signin.css"}) {
                String[] r = call(tls, port, "GET", "/" + f, null, "203.0.113.9", null, null);
                check(f + " through the tunnel", r[0].equals("200") && r[1].length() > 100, r[0]);
                check(f + " with the door's policy", r[2].contains("Content-Security-Policy: default-src 'none'")
                        && r[2].contains("Referrer-Policy: no-referrer"), r[2]);
            }

            System.out.println("a link, once");
            List<String> s = email(store, Arrays.asList(TUNNEL, "192.168.2.25"), now);
            String[] ok = link(tls, port, s.get(0), "203.0.113.9");
            String token = token(ok[1]);
            check("the right link lets the browser in", ok[0].equals("200") && token != null, ok[0] + " " + ok[1]);
            String[] hello = call(tls, port, "GET", "/hello", null, "203.0.113.9", null, token);
            check("with a token the door knows", hello[1].contains("\"name\":\"Test phone\""), hello[1]);
            String[] again = link(tls, port, s.get(0), "203.0.113.9");
            check("the same link again: used", again[0].equals("403") && again[1].contains("\"used\""), again[0] + " " + again[1]);
            String[] wifi = link(tls, port, s.get(1), "203.0.113.9");
            check("the Wi-Fi link through the tunnel: elsewhere", wifi[0].equals("403") && wifi[1].contains("\"elsewhere\""),
                    wifi[0] + " " + wifi[1]);

            System.out.println("the rules of the door");
            List<String> fresh = email(store, Collections.singletonList(TUNNEL), now);
            String[] plain = call(null, port, "POST", "/link", "http://" + TUNNEL, "203.0.113.9", "k=" + fresh.get(0), null);
            check("never over plain http", plain[0].equals("403") && plain[1].contains("\"plain\""), plain[0] + " " + plain[1]);
            String[] noOrigin = call(tls, port, "POST", "/link", null, "203.0.113.9", "k=" + fresh.get(0), null);
            check("only from the page's own origin", noOrigin[0].equals("403"), noOrigin[0]);
            String[] otherOrigin = call(tls, port, "POST", "/link", "https://evil.example.com", "203.0.113.9", "k=" + fresh.get(0), null);
            check("not from another site's page", otherOrigin[0].equals("403"), otherOrigin[0]);
            String[] get = call(tls, port, "GET", "/link?k=" + fresh.get(0), null, "203.0.113.9", null, null);
            check("not by GET", !get[0].equals("200") || !get[1].contains("token"), get[0]);
            check("and the link is still good after all of those", store.linkCount(now) == 1, store.linkCount(now));

            System.out.println("replaced");
            List<String> newer = email(store, Collections.singletonList(TUNNEL), now);
            String[] old = link(tls, port, fresh.get(0), "203.0.113.9");
            check("the last email's link: replaced", old[0].equals("403") && old[1].contains("\"replaced\""), old[1]);

            System.out.println("wrong links, from one address");
            String[] w = null;
            for (int i = 0; i < 4; i++) w = link(tls, port, String.format(Locale.ROOT, "%043d", i), "198.51.100.7");
            check("four wrong: told so", w[0].equals("403") && w[1].contains("\"wrong\""), w[0] + " " + w[1]);
            String[] fifth = link(tls, port, String.format(Locale.ROOT, "%043d", 9), "198.51.100.7");
            check("the fifth: that address waits", fifth[0].equals("429") && fifth[1].contains("\"locked\""), fifth[0] + " " + fifth[1]);
            String[] rightButLocked = link(tls, port, newer.get(0), "198.51.100.7");
            check("even a right link waits there", rightButLocked[0].equals("429"), rightButLocked[0]);
            String[] dead = link(tls, port, s.get(0), "192.0.2.44");
            check("a used link is not held against its address", dead[0].equals("403") && dead[1].contains("\"used\""), dead[1]);
            String[] elsewhere = link(tls, port, newer.get(0), "192.0.2.44");
            String second = token(elsewhere[1]);
            check("the owner's link still works from another address", elsewhere[0].equals("200") && second != null, elsewhere[1]);

            System.out.println("signed out");
            List<String> last = email(store, Collections.singletonList(TUNNEL), now);
            store.signOutAll();
            door.dropOpen();
            String[] off = link(tls, port, last.get(0), "192.0.2.44");
            check("every browser signed out: the link too", off[0].equals("403") && off[1].contains("\"off\""), off[1]);
            String[] gone = call(tls, port, "GET", "/hello", null, "192.0.2.44", null, second);
            check("and the browsers it let in", !gone[1].contains("Test phone"), gone[1]);

            System.out.println("a day old");
            List<String> yesterday = email(store, Collections.singletonList(TUNNEL), now - LinuxBrowser.LINK_LIFE_MS - 60_000);
            String[] stale = link(tls, port, yesterday.get(0), "192.0.2.44");
            check("more than a day old: old", stale[0].equals("403") && stale[1].contains("\"old\""), stale[1]);

            System.out.flush();
            String said = log.toString("UTF-8");
            boolean leaked = false;
            for (List<String> set : Arrays.asList(s, fresh, newer, last, yesterday)) {
                for (String secret : set) leaked |= said.contains(secret);
            }
            check("no secret in the door's log", !leaked, "a secret was logged");
            check("no token in the door's log", token != null && !said.contains(token) && (second == null || !said.contains(second)),
                    "a token was logged");
        } finally {
            if (door != null) door.stop();
            LinuxBrowserServer.tunnelHost = null;
            System.setErr(err);
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
        System.out.println("LINK passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
