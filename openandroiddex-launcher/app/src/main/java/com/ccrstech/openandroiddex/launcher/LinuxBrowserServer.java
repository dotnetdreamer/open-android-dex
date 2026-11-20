package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;

/**
 * Linux in a computer's browser: an HTTPS server on the phone's own network
 * that serves the viewer page and hands a browser that was let in to
 * {@link LinuxStream}, the same hardware-encoded H.264 the desktop app gets
 * over adb. The browser decodes it on its GPU (WebCodecs) and draws the
 * pointer itself. Nothing is installed on the computer. Design record:
 * doc/linux-browser.md.
 *
 * <p><b>One port, both schemes.</b> The first byte of every connection is
 * looked at before anything reads it: 0x16 is a TLS handshake, and the rest is
 * plain HTTP. Both are served the same way. https is the way in: the phone's
 * card shows it, and over it the browser decodes on its GPU (WebCodecs is
 * only given to secure pages). Plain HTTP is there for a browser that will not
 * take the phone's certificate at all, as a managed laptop's may not: a person
 * types {@code 192.168.1.20:7443}, the browser asks over plain HTTP, and the
 * page offers the secure address first ({@code viewer.js}), going there by
 * itself once this browser has taken the certificate. Over plain HTTP the
 * page decodes with the browser's own video player (Media Source
 * Extensions), and what crosses the network is not encrypted.
 *
 * <p><b>Who gets in.</b>
 * <ul>
 *   <li>Only a connection that came in on one of the addresses the phone
 *       offers ({@link LinuxBrowser#addresses}: Wi-Fi, its hotspot, a cable, a
 *       VPN), from a private address. Not mobile data, where other
 *       subscribers may share the carrier's private range, and not loopback,
 *       which every app on the phone can reach. Anything else is closed before
 *       a byte is read.</li>
 *   <li>Only a browser with a token. A token is had once, for the six-digit
 *       code the phone shows, through {@code POST /pair}, which takes one try
 *       at a time; the code's own budget is in {@link LinuxBrowser}. Five wrong
 *       codes from one address also lock that address out, 30 s doubling to
 *       ten minutes.</li>
 *   <li>The token comes with the WebSocket's request ({@code ?t=}) and is
 *       checked before a single frame is read: a peer without one gets a
 *       sentence saying so and a closed socket.</li>
 *   <li>{@code Host} must be an address, not a name, so a page elsewhere that
 *       points its own name at the phone (DNS rebinding) is answered with
 *       nothing; and every request that changes something must come from this
 *       origin's own page ({@code Origin}).</li>
 *   <li>Every connection has {@link #preAuthMs} to be let in, however slowly
 *       it sends, and one address may hold eight at once.</li>
 * </ul>
 *
 * <p><b>The page.</b> Files from the APK's assets, never from the guest:
 * anything running as root in Linux can rewrite the guest's web root, and the
 * page holds the browser's token. Served with a policy that lets it load
 * nothing but itself.
 *
 * <p>Pure Java but for {@link Sniffer}, which the service gives it: the
 * stand-in tests on a computer run this very file.
 */
final class LinuxBrowserServer {

    /** What the server needs from the process it runs in. */
    interface Host {
        /**
         * A browser let in: its WebSocket, upgraded, and the hello it opened
         * with. The desktop is the host's to give it, once {@code gate} says
         * it still may have it. {@code ended} runs once the stream is over.
         * False when there is nothing to hand it to.
         */
        boolean stream(LinuxStreamSocket ws, String hello, Closeable sock, String who, Gate gate, Runnable ended);

        /** The phone's name, which the page shows once it has been let in. */
        String phoneName();

        /** One of the page's files, or null. */
        byte[] asset(String name);

        /** Something the Settings page shows has changed. */
        void changed();
    }

    /**
     * Whether a browser let in may still have the desktop. Asked by its stream
     * at the moment it takes the desktop, under the lock that a sign-out or
     * the switch going off reads the watching stream by: one of those that
     * came first is seen here, and one that comes after finds the stream and
     * ends it. Asked any earlier, a browser let in a moment before either
     * would watch on, since from the token's check to that moment its
     * connection is in neither place: {@link #dropOpen} no longer has it, and
     * nothing watches yet.
     */
    interface Gate {
        /** Null while it may; else what its page is told: "unauthorized" or "off". */
        String refusal();
    }

    /** The first byte a connection sent, left where it is; -1 when none came in time. */
    interface Sniffer {
        int peek(Socket s, int timeoutMs) throws IOException;
    }

    /** The port is another app's: said, rather than moving to one a bookmark does not know. */
    static final class PortTaken extends IOException {
        final int port;

        PortTaken(int port, Throwable cause) {
            super("port " + port + " is in use by another app", cause);
            this.port = port;
        }
    }

    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_BODY = 4 * 1024;
    private static final int MAX_HANDLERS = 24;
    /** A browser opens six at most. */
    private static final int MAX_PER_PEER = 8;
    private static final int FIRST_BYTE_MS = 10_000;
    private static final int IDLE_MS = 15_000;
    /** Wrong codes from one address before it waits. Not final: the test raises it to reach the code's own budget. */
    static volatile int lockAfter = 5;
    private static final long LOCK_FIRST_MS = 30_000;
    private static final long LOCK_MAX_MS = 10 * 60_000;

    /**
     * From the first byte to being let in (the page's files, a pairing, a
     * stream's request), however slowly the far end sends: a byte now and then
     * would otherwise hold a connection for ever, and enough of them every
     * slot. Not final: the test makes it short.
     */
    static volatile int preAuthMs = 30_000;

    /**
     * A browser that sends nothing for this long is gone: its laptop shut or
     * left the network, and no FIN came. The page sends a ping every two
     * seconds, and a hidden tab's timers can slow to once a minute. Without
     * it the phone would hold its wake lock for a session nobody sees. Not
     * final: the test makes it short.
     */
    static volatile int sessionReadMs = 75_000;

    /** The page's files and their types. Nothing else is served. */
    private static final Map<String, String> FILES = new HashMap<>();

    static {
        FILES.put("index.html", "text/html; charset=utf-8");
        FILES.put("viewer.css", "text/css; charset=utf-8");
        FILES.put("viewer.js", "text/javascript; charset=utf-8");
        FILES.put("h264.js", "text/javascript; charset=utf-8");
        FILES.put("mse.js", "text/javascript; charset=utf-8");
        FILES.put("keysym.js", "text/javascript; charset=utf-8");
        FILES.put("icon.svg", "image/svg+xml");
    }

    /**
     * Itself and nothing else. connect-src 'self' covers this origin's wss://,
     * and from the plain page its ws:// and the same address over https, which
     * it looks for (CSP 3); img-src data: is the pointer's shape, which
     * arrives as pixels and is shown as a data URL; media-src blob: is the
     * plain page's picture, a MediaSource the page fills itself.
     */
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: blob:; media-src blob:; connect-src 'self'; font-src 'self'; manifest-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final LinuxBrowser store;
    private final Host host;
    private final Sniffer sniffer;
    private final ServerSocket server;
    private final Thread acceptor;
    private final Thread reaper;
    private final AtomicInteger handlers = new AtomicInteger();
    /** Every connection not yet handed to a stream: what {@link #stop} and a sign-out close. */
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    /** When each of those must have been let in by. */
    private final Map<Socket, Long> deadlines = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perPeer = new ConcurrentHashMap<>();
    private volatile LinuxBrowserCert cert;
    private volatile boolean stopped;
    private long certCheckedAt;
    /** The addresses answered on, and when they were last read. */
    private volatile Set<String> offered = new HashSet<>();
    private volatile long offeredAt;
    /**
     * The streams running now, oldest first, each as its number, address and
     * what it is; the state names the newest. Under {@link #watchLock}, with
     * every state line written while a stream starts or ends.
     */
    private final java.util.LinkedHashSet<String> watchers = new java.util.LinkedHashSet<>();
    private final Object watchLock = new Object();

    private static final class Fails {
        int count;
        long until;
        long last;
    }

    /** Per address; and the lock every pairing takes, one at a time. */
    private final Map<String, Fails> fails = new HashMap<>();

    private LinuxBrowserServer(LinuxBrowser store, LinuxBrowserCert cert, Host host, Sniffer sniffer, int port)
            throws IOException {
        this.store = store;
        this.cert = cert;
        this.host = host;
        this.sniffer = sniffer;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        try {
            s.bind(new InetSocketAddress(port), 16);
        } catch (IOException taken) {
            s.close();
            store.setState("failed port " + port);
            throw new PortTaken(port, taken);
        }
        this.server = s;
        this.certCheckedAt = System.currentTimeMillis();
        acceptor = new Thread(this::acceptLoop, "linux-browser");
        acceptor.setDaemon(true);
        reaper = new Thread(this::reapLoop, "linux-browser-reaper");
        reaper.setDaemon(true);
        acceptor.start();
        reaper.start();
        store.setState("listening " + port());
        DexLog.step("browser", "listening on port " + port() + " (" + LinuxBrowser.addresses() + ")");
    }

    static LinuxBrowserServer start(LinuxBrowser store, LinuxBrowserCert cert, Host host, Sniffer sniffer, int port)
            throws IOException {
        return new LinuxBrowserServer(store, cert, host, sniffer, port);
    }

    int port() {
        return server.getLocalPort();
    }

    /** What is open now, for a log or a test: connections not yet streams, handlers, and each address's count. */
    String connections() {
        StringBuilder b = new StringBuilder("open ").append(open.size()).append(", handlers ").append(handlers.get());
        for (Map.Entry<String, AtomicInteger> e : perPeer.entrySet()) {
            if (e.getValue().get() != 0) b.append(", ").append(e.getKey()).append(' ').append(e.getValue().get());
        }
        return b.toString();
    }

    LinuxBrowserCert cert() {
        return cert;
    }

    /** Stop listening, and close every connection not yet a stream: a page half-loaded, a pairing, a request. */
    void stop() {
        stopped = true;
        closeQuietly(server);
        dropOpen();
        synchronized (watchLock) {
            store.setState("off");
        }
    }

    /**
     * Close every connection not yet handed to a stream. A sign-out on the
     * phone does this, so a browser keeping a connection open cannot finish a
     * request it began before; a stream already running is the stream's to end.
     */
    void dropOpen() {
        for (Socket s : open.toArray(new Socket[0])) closeQuietly(s);
    }

    private void acceptLoop() {
        while (!stopped) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!stopped) DexLog.warn("browser", "accept: " + e);
                return;
            }
            InetAddress remote = s.getInetAddress();
            // Not from a network the phone offers: not a byte read, not a byte said.
            if (stopped || !LinuxBrowser.isLocalPeer(remote) || !offered(s.getLocalAddress())) {
                closeQuietly(s);
                continue;
            }
            InetAddress v4 = LinuxBrowser.mappedV4(remote);
            final String peer = (v4 != null ? v4 : remote).getHostAddress();
            AtomicInteger mine = perPeer.computeIfAbsent(peer, k -> new AtomicInteger());
            if (mine.incrementAndGet() > MAX_PER_PEER) {
                mine.decrementAndGet();
                closeQuietly(s);
                continue;
            }
            if (handlers.incrementAndGet() > MAX_HANDLERS) {
                handlers.decrementAndGet();
                mine.decrementAndGet();
                closeQuietly(s);
                continue;
            }
            open.add(s);
            deadlines.put(s, System.currentTimeMillis() + preAuthMs);
            Thread t = new Thread(() -> {
                try {
                    handle(s, peer);
                } finally {
                    open.remove(s);
                    deadlines.remove(s);
                    mine.decrementAndGet();
                    handlers.decrementAndGet();
                }
            }, "linux-browser-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Close what has not been let in by its time. */
    private void reapLoop() {
        while (!stopped) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<Socket, Long> e : deadlines.entrySet()) {
                if (e.getValue() < now) {
                    deadlines.remove(e.getKey());
                    closeQuietly(e.getKey());
                }
            }
        }
    }

    /** {@code local} is one of the addresses the phone offers, read again every couple of seconds. */
    private boolean offered(InetAddress local) {
        if (local == null) return false;
        InetAddress v4 = LinuxBrowser.mappedV4(local);
        String ip = (v4 != null ? v4 : local).getHostAddress();
        long now = System.currentTimeMillis();
        if (now - offeredAt > 2000 || !offered.contains(ip)) {
            Set<String> fresh = new HashSet<>(ips());
            offered = fresh;
            offeredAt = now;
        }
        return offered.contains(ip);
    }

    /**
     * Only a certificate that is running out is made again, the same way the
     * first one was. Looked at no more than every few seconds.
     */
    private void refreshCert() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - certCheckedAt < 5000) return;
            certCheckedAt = now;
        }
        if (!cert.renewDue(now + 7L * 24 * 3600_000)) return;
        try {
            cert = LinuxBrowserCert.loadOrMake(store.dir(), ips(), now);
            DexLog.step("browser", "a new certificate (" + cert.fingerprint.substring(0, 11) + ")");
        } catch (Exception e) {
            DexLog.warn("browser", "cannot make a new certificate: " + e);
        }
    }

    static List<String> ips() {
        List<String> ips = new java.util.ArrayList<>();
        for (LinuxBrowser.Address a : LinuxBrowser.addresses()) ips.add(a.ip);
        return ips;
    }

    // ── one connection ──

    private void handle(Socket s, String peer) {
        SSLSocket tls = null;
        boolean handed = false;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(FIRST_BYTE_MS);
            int first;
            byte[] consumed = null;
            if (sniffer != null) {
                first = sniffer.peek(s, FIRST_BYTE_MS);
            } else {
                // No way to look without taking (a computer's JVM): taken, and given back below.
                first = s.getInputStream().read();
                if (first >= 0) consumed = new byte[]{(byte) first};
            }
            if (first < 0) return;
            if (first != 0x16) {
                // Plain HTTP, served as it is: for a browser that will not
                // take the phone's certificate at any price (a managed
                // laptop's says "You can't visit … right now" and offers no
                // way on). The page says it is not encrypted and offers the
                // https address; nothing else differs.
                InputStream raw = consumed == null ? s.getInputStream()
                        : new SequenceInputStream(new ByteArrayInputStream(consumed), s.getInputStream());
                s.setSoTimeout(IDLE_MS);
                handed = serve(new BufferedInputStream(raw, 8192), s, s, peer, false);
                return;
            }
            refreshCert();
            if (consumed == null) {
                tls = (SSLSocket) cert.context.getSocketFactory().createSocket(s, peer, s.getPort(), true);
                tls.setUseClientMode(false);
            } else {
                // Java 8's way to hand TLS the byte already read. Android has no
                // such method, which is why the phone looks without taking
                // (Sniffer); named by reflection so that this compiles there.
                try {
                    tls = (SSLSocket) javax.net.ssl.SSLSocketFactory.class
                            .getMethod("createSocket", Socket.class, InputStream.class, boolean.class)
                            .invoke(cert.context.getSocketFactory(), s, new ByteArrayInputStream(consumed), true);
                } catch (ReflectiveOperationException e) {
                    throw new IOException("TLS over a socket already read from: " + e);
                }
            }
            tls.setSoTimeout(IDLE_MS);
            tls.startHandshake();
            handed = serve(new BufferedInputStream(tls.getInputStream(), 8192), tls, s, peer, true);
        } catch (SocketTimeoutException | EOFException e) {
            // too slow, or gone
        } catch (IOException e) {
            // A browser that had not yet said yes to the certificate drops the
            // handshake, every time: that is the warning page, not a fault.
        } catch (RuntimeException e) {
            DexLog.warn("browser", "connection from " + peer + ": " + e);
        } finally {
            // Every way out but one closes both: a TLS socket left to the
            // collector holds native state until it gets round to it.
            if (!handed) {
                if (tls != null) closeQuietly(tls);
                closeQuietly(s);
            }
        }
    }

    /**
     * Requests, kept alive between them, until one is a stream or it goes
     * quiet: over TLS ({@code secure}), or plain for a browser that refused
     * the certificate. {@code io} is what they travel on (the TLS socket, or
     * the connection itself), {@code raw} the connection under it.
     *
     * @return true when the socket was handed to a stream
     */
    private boolean serve(InputStream in, Socket io, Socket raw, String peer, boolean secure) throws IOException {
        OutputStream out = new java.io.BufferedOutputStream(io.getOutputStream(), 16 * 1024);
        while (!stopped) {
            Req r = readHead(in);
            if (r == null || stopped) return false;
            String hostOnly = addressHost(r.headers.getOrDefault("host", ""));
            if (hostOnly == null) {
                // A name, not an address: someone else's page rebinding its name to this phone.
                send(out, 421, "Misdirected Request", "text/plain", "Open this by its address.".getBytes(StandardCharsets.UTF_8), false);
                return false;
            }
            String origin = r.headers.get("origin");
            // The page's own origin, scheme and all: an https page cannot
            // pair or stream through the plain door, nor an http one through TLS.
            String self = (secure ? "https://" : "http://") + r.headers.get("host").toLowerCase(Locale.ROOT);
            boolean sameOrigin = origin != null && origin.toLowerCase(Locale.ROOT).equals(self);
            boolean keep = !"close".equalsIgnoreCase(r.headers.get("connection"));

            if ("GET".equals(r.method) && "/stream".equals(r.path)
                    && "websocket".equalsIgnoreCase(r.headers.get("upgrade"))) {
                if (!sameOrigin) {
                    send(out, 403, "Forbidden", "text/plain", new byte[0], false);
                    return false;
                }
                return openStream(io, raw, in, r, peer, secure);
            }
            if ("POST".equals(r.method)) {
                if (r.headers.containsKey("transfer-encoding")) {
                    // A browser's form says how long it is; nothing here reads chunks.
                    send(out, 411, "Length Required", "text/plain", new byte[0], false);
                    return false;
                }
                byte[] body = readBody(in, r);
                if (!sameOrigin) {
                    send(out, 403, "Forbidden", "text/plain", new byte[0], false);
                    return false;
                }
                Map<String, String> form = form(body);
                if ("/pair".equals(r.path)) {
                    pair(out, peer, form, r.headers.getOrDefault("user-agent", ""), keep);
                } else if ("/forget".equals(r.path)) {
                    store.removeToken(form.get("token"));
                    host.changed();
                    send(out, 200, "OK", "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), keep);
                } else {
                    send(out, 404, "Not Found", "text/plain", new byte[0], keep);
                }
            } else if ("GET".equals(r.method) || "HEAD".equals(r.method)) {
                if ("/hello".equals(r.path)) {
                    // The phone's name only to a browser already let in: anyone
                    // on the network may ask, and the name is often its owner's.
                    String auth = r.headers.getOrDefault("authorization", "");
                    boolean known = auth.startsWith("Bearer ") && store.tokenValid(auth.substring(7).trim());
                    String json = known ? "{\"v\":1,\"name\":" + jsonString(host.phoneName()) + "}" : "{\"v\":1}";
                    send(out, 200, "OK", "application/json", json.getBytes(StandardCharsets.UTF_8), keep);
                } else {
                    String name = "/".equals(r.path) ? "index.html" : r.path.substring(1);
                    String type = FILES.get(name);
                    byte[] file = type == null ? null : host.asset(name);
                    if (file == null) {
                        send(out, 404, "Not Found", "text/plain", new byte[0], keep);
                    } else {
                        send(out, 200, "OK", type, "HEAD".equals(r.method) ? new byte[0] : file, keep);
                    }
                }
            } else {
                send(out, 405, "Method Not Allowed", "text/plain", new byte[0], false);
                return false;
            }
            if (!keep) return false;
        }
        return false;
    }

    // ── the code, for a token ──

    private void pair(OutputStream out, String peer, Map<String, String> form, String userAgent, boolean keep)
            throws IOException {
        long now = System.currentTimeMillis();
        String reply;
        int status;
        String label = null;
        String token = null;
        // One pairing at a time, from anywhere: twenty-four connections at
        // once get no more tries between them than one does.
        synchronized (fails) {
            if (fails.size() > 512) {
                // Only addresses long quiet and not locked: a lockout outlives a crowd.
                fails.values().removeIf(x -> x.until < now && now - x.last > LOCK_MAX_MS);
            }
            Fails f = fails.get(peer);
            if (f != null && f.until > now) {
                long secs = (f.until - now + 999) / 1000;
                send(out, 429, "Too Many Requests", "application/json",
                        ("{\"error\":\"locked\",\"retryIn\":" + secs + "}").getBytes(StandardCharsets.UTF_8), keep);
                return;
            }
            // The code tried and, when right, the browser let in, in one step:
            // a sign-out on the phone is wholly before it (the code it made is
            // not this one) or wholly after (and takes this token with the rest).
            LinuxBrowser.Tried tried = store.tryCode(form.get("code"), now, describe(userAgent) + " · " + peer);
            switch (tried.pair) {
                case OK:
                    fails.remove(peer);
                    label = tried.label;
                    token = tried.token;
                    status = 200;
                    reply = null;
                    break;
                case CLOSED:
                    status = 403;
                    reply = "{\"error\":\"closed\"}";
                    break;
                case USED_UP:
                    status = 403;
                    reply = "{\"error\":\"used\"}";
                    break;
                default: { // WRONG
                    if (f == null) {
                        f = new Fails();
                        fails.put(peer, f);
                    }
                    f.count++;
                    f.last = now;
                    if (f.count >= lockAfter) {
                        long wait = Math.min(LOCK_MAX_MS, LOCK_FIRST_MS << Math.min(10, f.count - lockAfter));
                        f.until = now + wait;
                        status = 429;
                        reply = "{\"error\":\"locked\",\"retryIn\":" + (wait / 1000) + "}";
                    } else {
                        status = 403;
                        reply = "{\"error\":\"wrong\"}";
                    }
                }
            }
        }
        if (status == 200) {
            DexLog.step("browser", "a browser was let in: " + label);
            host.changed();
            send(out, 200, "OK", "application/json", ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8), keep);
            return;
        }
        DexLog.step("browser", "pairing from " + peer + ": " + reply);
        // A code used up is news on the phone's card.
        if (reply.contains("used")) host.changed();
        send(out, status, status == 429 ? "Too Many Requests" : "Forbidden", "application/json",
                reply.getBytes(StandardCharsets.UTF_8), keep);
    }

    // ── the stream ──

    private final AtomicInteger streams = new AtomicInteger();

    /** @return true when the socket was handed to a stream */
    private boolean openStream(Socket s, Socket raw, InputStream in, Req r, String peer, boolean secure)
            throws IOException {
        // The socket's own output, not the buffered one the page's files went
        // through (it holds nothing; every answer was flushed): video frames
        // are big, and one buffer is enough.
        LinuxStreamSocket ws = new LinuxStreamSocket(in, s.getOutputStream(), s);
        ws.accept(new LinuxStreamSocket.Request(r.method, r.path, r.headers));
        // The token came with the request: a browser not let in is told so
        // before it can send a single frame, and none is read from it.
        final String token = r.query.get("t");
        if (!store.tokenValid(token)) {
            ws.sendText("{\"t\":\"state\",\"state\":\"unauthorized\"}");
            // Its hello is on its way, unread: closed over it, the word could go too.
            ws.closeAfterWord(LAST_WORD_MS);
            return false;
        }
        // The first message is the hello: the size it wants, the quality.
        LinuxStreamSocket.Message m = ws.read();
        if (m == null || m.opcode != LinuxStreamSocket.OP_TEXT || stopped) {
            ws.close();
            return false;
        }
        String hello = m.text();
        // Let in: no deadline from here, but a browser silent this long is gone.
        deadlines.remove(raw);
        open.remove(raw);
        s.setSoTimeout(sessionReadMs);
        String ua = r.headers.getOrDefault("user-agent", "");
        String who = describe(ua) + " at " + peer + (secure ? "" : ", not encrypted");
        // Numbered: two tabs of one browser are two watchers, and the first
        // one ending must not say nobody is watching.
        final String watcher = streams.incrementAndGet() + " " + peer + " " + describe(ua);
        // Counted before it is handed over: the stream runs on a thread of its
        // own and can be over before host.stream returns, and its end must
        // find it here, or the phone would say a browser is watching that is
        // long gone.
        if (!watchingNow(watcher)) {
            ws.close();
            return false;
        }
        // Asked again as the stream takes the desktop (Gate), from the file
        // the Settings page signs browsers out in: seen here the moment it is.
        Gate gate = () -> stopped ? "off" : store.tokenValid(token) ? null : "unauthorized";
        boolean taken = host.stream(ws, hello, s, who, gate, () -> notWatching(watcher));
        if (!taken) {
            notWatching(watcher);
            ws.sendText("{\"t\":\"state\",\"state\":\"failed\",\"message\":\"Linux is not ready on the phone.\"}");
            ws.closeAfterWord(LAST_WORD_MS);
            return false;
        }
        return true;
    }

    /** How long a last word waits for the browser to close its end ({@link LinuxStreamSocket#closeAfterWord}). */
    static final int LAST_WORD_MS = 1000;

    /** {@code watcher}'s stream begins; false once the server has stopped. */
    private boolean watchingNow(String watcher) {
        synchronized (watchLock) {
            if (stopped) return false;
            watchers.add(watcher);
            store.setState("watching " + port() + " " + watcher);
        }
        host.changed();
        return true;
    }

    /**
     * {@code watcher}'s stream is over. The state names whichever stream is
     * still running, newest first, or goes back to listening: one that ended
     * before an older one did must not say nobody is watching.
     */
    private void notWatching(String watcher) {
        synchronized (watchLock) {
            if (!watchers.remove(watcher)) return;
            if (!stopped) {
                String newest = null;
                for (String w : watchers) newest = w;
                store.setState(newest != null ? "watching " + port() + " " + newest : "listening " + port());
            }
        }
        host.changed();
    }

    // ── HTTP, the little of it the page needs ──

    private static final class Req {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final int contentLength;

        Req(String method, String path, Map<String, String> query, Map<String, String> headers, int contentLength) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.contentLength = contentLength;
        }
    }

    /** The next request's line and headers, or null when the connection is done. */
    private static Req readHead(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_HEAD];
        int n = 0;
        while (true) {
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (b < 0) return null;
            if (n == buf.length) throw new IOException("request head too large");
            buf[n++] = (byte) b;
            if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n') break;
        }
        String[] lines = new String(buf, 0, n - 4, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 3 || !first[2].startsWith("HTTP/1.")) throw new IOException("not HTTP");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        String path = first[1];
        Map<String, String> query = new HashMap<>();
        int q = path.indexOf('?');
        if (q >= 0) {
            query = form(path.substring(q + 1).getBytes(StandardCharsets.ISO_8859_1));
            path = path.substring(0, q);
        }
        int len;
        try {
            len = Integer.parseInt(headers.getOrDefault("content-length", "0").trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad length");
        }
        return new Req(first[0], path, query, headers, len);
    }

    private static byte[] readBody(InputStream in, Req r) throws IOException {
        if (r.contentLength < 0 || r.contentLength > MAX_BODY) throw new IOException("body too large");
        byte[] b = new byte[r.contentLength];
        int off = 0;
        while (off < b.length) {
            int got = in.read(b, off, b.length - off);
            if (got < 0) throw new EOFException();
            off += got;
        }
        return b;
    }

    private static Map<String, String> form(byte[] body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static void send(OutputStream out, int status, String reason, String type, byte[] body, boolean keep)
            throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Cross-Origin-Opener-Policy: same-origin\r\n"
                + "Content-Security-Policy: " + CSP + "\r\n"
                + "Connection: " + (keep ? "keep-alive" : "close") + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /**
     * The address in a {@code Host} header, without its port, or null when it
     * is a name: IPv4 dotted, IPv6 in brackets, or localhost.
     */
    static String addressHost(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return null;
        String bare;
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close < 0) return null;
            bare = h.substring(1, close);
            return bare.matches("[0-9a-f:.]+") ? "[" + bare + "]" : null;
        }
        int colon = h.lastIndexOf(':');
        bare = colon >= 0 ? h.substring(0, colon) : h;
        if (bare.equals("localhost")) return bare;
        return LinuxBrowserCert.ipBytes(bare) != null ? bare : null;
    }

    /** "Chrome on Windows": enough for a person to know which browser it is. */
    static String describe(String ua) {
        String browser = "A browser";
        if (ua.contains("Edg/")) browser = "Edge";
        else if (ua.contains("OPR/") || ua.contains("Opera")) browser = "Opera";
        else if (ua.contains("Firefox/")) browser = "Firefox";
        else if (ua.contains("Chrome/") || ua.contains("CriOS/")) browser = "Chrome";
        else if (ua.contains("Safari/")) browser = "Safari";
        String os = "";
        if (ua.contains("Windows")) os = "Windows";
        else if (ua.contains("iPhone") || ua.contains("iPad")) os = "iOS";
        else if (ua.contains("Mac OS X") || ua.contains("Macintosh")) os = "macOS";
        else if (ua.contains("CrOS")) os = "ChromeOS";
        else if (ua.contains("Android")) os = "Android";
        else if (ua.contains("Linux")) os = "Linux";
        return os.isEmpty() ? browser : browser + " on " + os;
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
