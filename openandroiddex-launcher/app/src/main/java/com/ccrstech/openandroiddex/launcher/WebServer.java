package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two jobs, and nothing else: hand out the viewer page, and be a place for a
 * browser on this network to exchange an offer with the phone.
 *
 * <p>This used to be a small web application — a file API, downloads, uploads,
 * a video stream. All of it now rides the peer connection instead, where it
 * works whether or not this server can be reached at all. What is left is four
 * routes, and even they are optional: with a rendezvous configured the browser
 * loads the page from there and never touches this.
 *
 * <h3>The door</h3>
 * It binds every interface, because a page only loadable from the phone is not
 * a page. Three things hold it shut:
 * <ul>
 * <li>A six-digit code, exchanged once for a 128-bit session token.
 * <li>A lockout per client address, growing on each run of failures, so six
 *     digits cannot be walked through at network speed.
 * <li>A {@code Host} header check. A LAN server on a phone is the classic
 *     DNS-rebinding target — a page on the internet points its own hostname at
 *     this address and then talks to it as same-origin. Only literal addresses
 *     and {@code localhost} are answered.
 * </ul>
 *
 * <p>Signalling on {@code /ws} is authenticated by the token, so a peer that
 * arrives this way is already known; the data channel still asks, and replays
 * the token rather than the code.
 */
final class WebServer {

    /** What the page and the socket are authenticated by. */
    private static final String COOKIE = "dexweb";
    /** Mutating routes want it here as well — a cross-origin form cannot set it. */
    private static final String TOKEN_HEADER = "x-dex-token";

    /**
     * Concurrent connections.
     *
     * Generous, because a browser does not open one connection per request: it
     * PRE-OPENS speculative ones and leaves them idle, and every one of those
     * occupies a slot here until it either sends a request or times out. A
     * page load plus its assets plus preconnects went past a cap of 16 on a
     * real Brave session, and the connection that lost the race was the
     * WebSocket — refused with no response at all, which the browser reports
     * as a bare "WebSocket connection failed:" with an empty reason. Threads
     * are daemon and idle; the cap is a runaway guard, not a budget.
     */
    private static final int MAX_CONNECTIONS = 64;
    /** Longest request line + headers we will read from a stranger. */
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    /**
     * How long a connection may sit without sending a request line.
     *
     * Short on purpose: this is what a browser's speculative preconnect looks
     * like, and holding a slot for it for a minute is how the cap above gets
     * exhausted by a single page load.
     */
    private static final int REQUEST_TIMEOUT_MS = 12_000;

    /** Sent to a connection we cannot serve, so it is never dropped in silence. */
    private static final byte[] BUSY = ("HTTP/1.1 503 Service Unavailable\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    /** A session token is good for a day of not being used. */
    private static final long TOKEN_TTL_MS = 24 * 60 * 60 * 1000L;
    /** Failures before the lockout starts, and how long the first one lasts. */
    private static final int FAILS_BEFORE_LOCK = 5;
    private static final long LOCK_BASE_MS = 30_000L;
    private static final long LOCK_MAX_MS = 10 * 60_000L;

    /** What the server needs from the service that owns it. */
    interface Host {
        Context context();

        WebInput input();

        /** The WebRTC side, or null while it is still starting. */
        WebRtcHub rtc();

        /** True for a token this door handed out and has not dropped. */
        boolean validSession(String token);

        /** A viewer arrived or left — the notification and window follow it. */
        void onViewersChanged();
    }

    private final Host host;
    private final Context ctx;

    private ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "web-conn");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean running;
    private volatile int port;

    /** token → last use. */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    /** client address → failed attempts and when the lock lifts. */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    /** Sockets currently carrying signalling for a local browser. */
    private final List<WebSocketConn> signalling = new CopyOnWriteArrayList<>();

    WebServer(Host host) {
        this.host = host;
        this.ctx = host.context();
    }

    int port() {
        return port;
    }

    boolean running() {
        return running;
    }

    void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress((java.net.InetAddress) null, Web.PORT), 8);
        server = s;
        port = s.getLocalPort();
        running = true;
        Thread accept = new Thread(this::acceptLoop, "web-accept");
        accept.setDaemon(true);
        accept.start();
        DexLog.step("web", "page and signalling on 0.0.0.0:" + port);
    }

    void stop() {
        running = false;
        for (WebSocketConn c : signalling) c.closeQuietly();
        signalling.clear();
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        workers.shutdownNow();
        DexLog.step("web", "page server stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                if (connections.get() >= MAX_CONNECTIONS) {
                    // Say so rather than closing silently. A socket dropped
                    // without a response is indistinguishable, from the
                    // browser, from the server not being there at all.
                    DexLog.warn("web", "refusing a connection — " + MAX_CONNECTIONS
                            + " already open");
                    try {
                        socket.getOutputStream().write(BUSY);
                        socket.getOutputStream().flush();
                    } catch (Exception ignored) {
                    }
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                connections.incrementAndGet();
                workers.execute(() -> {
                    try {
                        serve(socket);
                    } catch (Exception e) {
                        // Nothing a stranger sends is worth a stack trace.
                    } finally {
                        connections.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                if (!running) return;
                DexLog.warn("web", "accept failed", e);
            }
        }
    }

    // ── one request ──

    private void serve(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(REQUEST_TIMEOUT_MS);
        InputStream in = new BufferedInputStream(socket.getInputStream(), 8 * 1024);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8 * 1024);
        Request req = readRequest(in);
        if (req == null) {
            socket.close();
            return;
        }
        try {
            route(socket, req, in, out);
        } catch (SocketHandedOff handed) {
            return;                       // the WebSocket owns the socket now
        } catch (Exception e) {
            DexLog.warn("web", req.method + " " + req.path + " failed", e);
        }
        try {
            out.flush();
        } catch (Exception ignored) {
        }
        socket.close();
    }

    /** Thrown to say "do not close this socket" when a WebSocket took it over. */
    private static final class SocketHandedOff extends RuntimeException {
    }

    private void route(Socket socket, Request req, InputStream in, OutputStream out)
            throws IOException {
        if (!hostAllowed(req.header("host"))) {
            DexLog.warn("web", "refused Host: " + req.header("host"));
            send(out, 421, "Misdirected Request", "text/plain",
                    "Use the address the phone shows.".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String path = req.path;
        if ("/".equals(path) || "/index.html".equals(path)) {
            sendAsset(out, "web/index.html", "text/html; charset=utf-8", req.header("host"));
            return;
        }
        if ("/app.js".equals(path)) {
            sendAsset(out, "web/app.js", "text/javascript; charset=utf-8", req.header("host"));
            return;
        }
        if ("/style.css".equals(path)) {
            sendAsset(out, "web/style.css", "text/css; charset=utf-8", req.header("host"));
            return;
        }
        if ("/favicon.ico".equals(path)) {
            send(out, 204, "No Content", null, new byte[0]);
            return;
        }
        if ("/api/auth".equals(path)) {
            handleAuth(socket, req, in, out);
            return;
        }

        String token = tokenOf(req);
        if (token == null) {
            // Worth a line for /ws in particular: a viewer whose token was
            // issued by an earlier run of this server gets exactly this, and
            // from the browser it looks like a bare "WebSocket failed".
            if ("/ws".equals(path)) {
                DexLog.warn("web", "refused a socket with an unknown session token"
                        + " — the page will be asked to sign in again");
            }
            sendJson(out, 401, "Unauthorized", "{\"error\":\"auth\"}");
            return;
        }
        tokens.put(token, SystemClock.elapsedRealtime());

        if ("/ws".equals(path)) {
            upgrade(socket, req, in, out);
            throw new SocketHandedOff();
        }
        if ("/api/state".equals(path)) {
            sendJson(out, 200, "OK", state().toString());
            return;
        }
        if ("/api/logout".equals(path)) {
            // The cookie is HttpOnly, so the page cannot drop it itself.
            tokens.remove(token);
            send(out, 200, "OK", "application/json",
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                    "Set-Cookie: " + COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
            return;
        }
        send(out, 404, "Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
    }

    // ── auth ──

    private void handleAuth(Socket socket, Request req, InputStream in, OutputStream out)
            throws IOException {
        String client = socket.getInetAddress() == null
                ? "?" : socket.getInetAddress().getHostAddress();
        long wait = lockedFor(client);
        if (wait > 0) {
            sendJson(out, 429, "Too Many Requests",
                    "{\"error\":\"locked\",\"seconds\":" + (wait / 1000 + 1) + "}");
            return;
        }
        String body = new String(readBody(req, in, 512), StandardCharsets.UTF_8);
        Map<String, String> form = parseQuery(body);
        String given = form.get("pin");
        if (given != null && Web.secureEquals(given.trim(), Web.pin(ctx))) {
            failures.remove(client);
            String token = Web.newToken();
            tokens.put(token, SystemClock.elapsedRealtime());
            sweepTokens();
            DexLog.step("web", "viewer authenticated from " + client);
            send(out, 200, "OK", "application/json",
                    ("{\"ok\":true,\"token\":\"" + token + "\"}")
                            .getBytes(StandardCharsets.UTF_8),
                    "Set-Cookie: " + COOKIE + "=" + token
                            + "; Path=/; Max-Age=86400; HttpOnly; SameSite=Strict");
            return;
        }
        long lock = noteFailure(client);
        DexLog.warn("web", "bad access code from " + client
                + (lock > 0 ? " — locked for " + (lock / 1000) + "s" : ""));
        sendJson(out, 401, "Unauthorized",
                "{\"error\":\"pin\",\"seconds\":" + (lock / 1000) + "}");
    }

    /**
     * Failures per address, with a lockout that doubles.
     *
     * Per address rather than global on purpose: one fumbled code on a laptop
     * must not lock out the phone's owner on their tablet.
     */
    private long noteFailure(String client) {
        long[] record = failures.get(client);
        if (record == null) record = new long[]{0, 0};
        record[0]++;
        long lock = 0;
        if (record[0] >= FAILS_BEFORE_LOCK) {
            long over = record[0] - FAILS_BEFORE_LOCK;
            lock = Math.min(LOCK_MAX_MS, LOCK_BASE_MS << Math.min(over, 5));
            record[1] = SystemClock.elapsedRealtime() + lock;
        }
        failures.put(client, record);
        return lock;
    }

    private long lockedFor(String client) {
        long[] record = failures.get(client);
        if (record == null || record[1] == 0) return 0;
        return Math.max(0, record[1] - SystemClock.elapsedRealtime());
    }

    private String tokenOf(Request req) {
        String header = req.header(TOKEN_HEADER);
        if (header != null && tokens.containsKey(header)) return header;
        String q = req.query.get("t");
        if (q != null && tokens.containsKey(q)) return q;
        String cookies = req.header("cookie");
        if (cookies != null) {
            for (String part : cookies.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    String value = p.substring(COOKIE.length() + 1);
                    if (tokens.containsKey(value)) return value;
                }
            }
        }
        return null;
    }

    private void sweepTokens() {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Long> e : tokens.entrySet()) {
            if (now - e.getValue() > TOKEN_TTL_MS) tokens.remove(e.getKey());
        }
    }

    /**
     * Is this a live session token?
     *
     * Asked by {@link WebRtcPeer}: a viewer that already came through this door
     * and then opened a peer connection should not be made to type the access
     * code again for what is, to them, one session.
     */
    boolean hasToken(String token) {
        if (token == null || token.isEmpty() || !tokens.containsKey(token)) return false;
        tokens.put(token, SystemClock.elapsedRealtime());
        return true;
    }

    /** Drop every session — what "change the code" means. */
    void revokeSessions() {
        tokens.clear();
        for (WebSocketConn c : signalling) c.closeQuietly();
    }

    /**
     * A Host header we are willing to answer to.
     *
     * Literal addresses and localhost only. That is the whole anti-rebinding
     * rule: an attacker's domain name resolving to this phone is not in the
     * list, and there is no configured hostname any more for it to hide behind.
     */
    private boolean hostAllowed(String header) {
        if (header == null) return false;
        String hostname = header.trim().toLowerCase(Locale.US);
        int colon = hostname.lastIndexOf(':');
        if (colon > 0 && hostname.indexOf(']') < colon) hostname = hostname.substring(0, colon);
        hostname = hostname.replace("[", "").replace("]", "");
        if (hostname.isEmpty()) return false;
        if ("localhost".equals(hostname)) return true;
        return hostname.matches("^[0-9.]+$") || hostname.matches("^[0-9a-f:]+$");
    }

    // ── signalling ──

    private void upgrade(Socket socket, Request req, InputStream in, OutputStream out)
            throws IOException {
        String key = req.header("sec-websocket-key");
        String accept = key == null ? null : WebSocketConn.acceptFor(key);
        if (accept == null) {
            send(out, 400, "Bad Request", "text/plain", "no key".getBytes());
            return;
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();

        // A signalling socket is IDLE by design: after the offer and the
        // answer it may carry nothing for hours, and the request timeout that
        // got it here would close it a few seconds later.
        socket.setSoTimeout(0);
        WebSocketConn conn = new WebSocketConn(Web.newToken().substring(0, 8),
                socket, in, out, socketListener);
        // Name the client. Writing a 101 is not the same as the client
        // ACCEPTING it — a browser verifies Sec-WebSocket-Accept and drops the
        // connection without a word if it disagrees — so this line says who
        // asked, and the close line below says how long they stayed. A socket
        // that opens and closes in the same millisecond is a rejected
        // handshake, not a viewer.
        String agent = req.header("user-agent");
        DexLog.step("web", "signalling socket open from " + conn.peer
                + " [" + (agent == null ? "no agent" : agent) + "]");
        long opened = SystemClock.elapsedRealtime();
        signalling.add(conn);
        conn.start();
        // Reads on this thread until the tab closes; the caller must not close
        // the socket behind us, which is what SocketHandedOff says.
        conn.readLoop();
        signalling.remove(conn);
        long lived = SystemClock.elapsedRealtime() - opened;
        DexLog.step("web", "signalling socket from " + conn.peer + " closed after "
                + lived + "ms" + (lived < 200
                ? " — that is a REJECTED handshake, not a viewer leaving" : ""));
    }

    private final WebSocketConn.Listener socketListener = new WebSocketConn.Listener() {
        @Override
        public void onText(WebSocketConn conn, String text) {
            WebRtcHub hub = host.rtc();
            if (hub == null) {
                // Still loading its native libraries. Say so rather than going
                // quiet — the page waits and asks again.
                conn.sendText("{\"t\":\"rtc\",\"v\":{\"t\":\"unavailable\"}}");
                return;
            }
            try {
                JSONObject msg = new JSONObject(text);
                JSONObject inner = msg.optJSONObject("v");
                hub.onSignal(inner == null ? "{}" : inner.toString(), routeFor(conn));
            } catch (Exception e) {
                DexLog.warn("web", "bad signalling from a viewer", e);
            }
        }

        @Override
        public void onClosed(WebSocketConn conn) {
            signalling.remove(conn);
        }
    };

    /** Signalling answers go back down the socket they arrived on. */
    private WebRtcHub.Route routeFor(WebSocketConn conn) {
        return (peerId, json) -> {
            try {
                conn.sendText(new JSONObject()
                        .put("t", "rtc")
                        .put("v", new JSONObject(json)).toString());
            } catch (Exception e) {
                DexLog.warn("web", "could not answer a signalling message", e);
            }
        };
    }

    private JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            // The page needs ICE servers before it can answer, and a phone that
            // is only reachable through a relay needs the browser to have one
            // too. Only sent on this authenticated route — a TURN credential is
            // a credential.
            o.put("rtc", host.rtc() != null);
            o.put("ice", new org.json.JSONArray(WebRtc.iceServersJson(ctx)));
            o.put("control", Web.control(ctx));
            o.put("controlReady", host.input() != null && host.input().available());
            o.put("files", Web.files(ctx));
        } catch (Exception ignored) {
        }
        return o;
    }

    // ── HTTP plumbing ──

    private static final class Request {
        String method = "";
        String path = "";
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> query = new HashMap<>();
        long contentLength;

        String header(String lower) {
            return headers.get(lower);
        }
    }

    private Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int state = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) return null;
            buffer.write(b);
            // Looking for CRLFCRLF without holding the whole thing as a String.
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
        }
        String[] lines = buffer.toString("UTF-8").split("\r\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request req = new Request();
        req.method = first[0].toUpperCase(Locale.US);
        String target = first[1];
        int q = target.indexOf('?');
        if (q >= 0) {
            req.path = decode(target.substring(0, q));
            req.query.putAll(parseQuery(target.substring(q + 1)));
        } else {
            req.path = decode(target);
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            req.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                    lines[i].substring(colon + 1).trim());
        }
        String len = req.header("content-length");
        if (len != null) {
            try {
                req.contentLength = Long.parseLong(len.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return req;
    }

    private byte[] readBody(Request req, InputStream in, int max) throws IOException {
        int n = (int) Math.min(max, Math.max(0, req.contentLength));
        byte[] body = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(body, off, n - off);
            if (read < 0) break;
            off += read;
        }
        return body;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void sendAsset(OutputStream out, String assetPath, String contentType,
                           String hostHeader) throws IOException {
        byte[] body;
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            body = bos.toByteArray();
        } catch (IOException e) {
            send(out, 404, "Not Found", "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // The page pulls nothing from anywhere else and never will.
        //
        // connect-src names the socket origin EXPLICITLY, built from the Host
        // header, rather than relying on 'self' or a bare `ws:` scheme source.
        // Both of those are supposed to permit a same-origin WebSocket and
        // engines have disagreed about it; a blocked socket then shows up in
        // the console as a bare "WebSocket connection failed:" with no reason,
        // which is indistinguishable from a network fault. Spelling out the
        // origin costs nothing and removes the question.
        String origin = hostOf(hostHeader);
        send(out, 200, "OK", contentType, body,
                "Cache-Control: no-store",
                "X-Content-Type-Options: nosniff",
                "Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:; "
                        + "media-src 'self' blob:; "
                        + "connect-src 'self' ws: wss: ws://" + origin + " wss://" + origin + "; "
                        + "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
    }

    /** The Host as given, minus anything that has no business in a CSP source. */
    private static String hostOf(String header) {
        if (header == null) return "*";
        String value = header.trim();
        int space = value.indexOf(' ');
        if (space > 0) value = value.substring(0, space);
        return value.matches("^[A-Za-z0-9._:\\[\\]-]+$") ? value : "*";
    }

    private void sendJson(OutputStream out, int code, String status, String json)
            throws IOException {
        send(out, code, status, "application/json", json.getBytes(StandardCharsets.UTF_8),
                "Cache-Control: no-store");
    }

    private void send(OutputStream out, int code, String status, String contentType,
                      byte[] body, String... extra) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        for (String e : extra) head.append(e).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
