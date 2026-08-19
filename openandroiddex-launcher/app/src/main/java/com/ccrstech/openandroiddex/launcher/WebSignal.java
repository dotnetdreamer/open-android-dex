package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The phone's own way out: an outbound WebSocket to a rendezvous, so a browser
 * can reach it even when nothing can reach the phone.
 *
 * <p>This is the piece that makes "from anywhere" true rather than aspirational.
 * A phone on mobile data sits behind carrier-grade NAT with no inbound path at
 * all — no port forward exists to be made, and a tunnel would need a process
 * on the phone we do not ship. What the phone can always do is dial out. So it
 * dials out to a small relay ({@code openandroiddex-signal} in this repository,
 * meant to live beside an existing coturn), announces its room, and waits.
 * A browser opens the same room on that server, the two exchange an offer and
 * an answer through it, and from then on the media and everything else goes
 * peer-to-peer or through TURN. The relay carries kilobytes and never sees a
 * credential or a frame.
 *
 * <p><b>It is a WebSocket CLIENT</b>, which is why {@link WebSocketConn} grew a
 * client mode: our frames must be masked and the server's are not, the exact
 * reverse of every other socket in this app.
 *
 * <p>Reconnects with a backoff, forever, because the condition it is retrying
 * against — a phone that changed networks, a relay that restarted — is normally
 * temporary and the user is not watching.
 */
final class WebSignal implements WebRtcHub.Route {

    /** Backoff bounds for reconnecting to the rendezvous. */
    private static final long RETRY_MIN_MS = 2_000;
    private static final long RETRY_MAX_MS = 60_000;
    /** Keepalive. Carrier NAT bindings die quietly and much faster than this. */
    private static final long PING_MS = 25_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Context ctx;
    private final WebRtcHub hub;
    private final String url;
    private final String room;

    private Thread thread;
    private volatile boolean running;
    private volatile WebSocketConn conn;
    private volatile String state = "idle";

    WebSignal(Context ctx, WebRtcHub hub, String url, String room) {
        this.ctx = ctx.getApplicationContext();
        this.hub = hub;
        this.url = url;
        this.room = room;
    }

    /** "idle" | "connecting" | "waiting" | "error" — what the window shows. */
    String state() {
        return state;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "web-signal");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        WebSocketConn c = conn;
        if (c != null) c.closeQuietly();
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
        state = "idle";
    }

    private void loop() {
        long backoff = RETRY_MIN_MS;
        while (running) {
            try {
                state = "connecting";
                Web.announce(ctx);
                connectOnce();
                backoff = RETRY_MIN_MS;      // a session that opened resets it
            } catch (Exception e) {
                state = "error";
                DexLog.warn("web", "rendezvous unreachable (" + e.getMessage() + ")");
                Web.announce(ctx);
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(RETRY_MAX_MS, backoff * 2);
        }
        state = "idle";
    }

    private void connectOnce() throws Exception {
        URI uri = URI.create(url);
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        String host = uri.getHost();
        if (host == null) throw new IOException("no host in " + url);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

        Socket socket;
        if (secure) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ssl.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            // Certificate validation does NOT include the hostname unless this
            // is set — an SSLSocket will happily accept a valid certificate
            // issued for somebody else. HttpsURLConnection does this for you;
            // a raw socket does not.
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            socket = ssl;
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        }
        socket.setTcpNoDelay(true);
        // No read timeout: this socket is idle by design between viewers, and
        // the ping below is what proves it is still alive.
        socket.setSoTimeout(0);
        socket.setKeepAlive(true);

        InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + (uri.getPort() > 0 ? ":" + port : "") + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: OpenAndroidDeX\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String response = readHeaders(in);
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            socket.close();
            throw new IOException("rendezvous refused the upgrade: "
                    + response.split("\r\n")[0]);
        }
        String expected = WebSocketConn.acceptFor(key);
        if (expected != null && !response.toLowerCase(Locale.US)
                .contains(expected.toLowerCase(Locale.US))) {
            socket.close();
            throw new IOException("rendezvous handshake did not verify");
        }

        WebSocketConn socketConn = new WebSocketConn("signal", socket, in, out, listener, true);
        conn = socketConn;
        socketConn.start();
        socketConn.sendText("{\"t\":\"host\",\"room\":\"" + room + "\"}");
        state = "waiting";
        DexLog.step("web", "rendezvous connected, hosting room " + room);
        Web.announce(ctx);

        startPings(socketConn);
        // Blocks for the life of the connection.
        socketConn.readLoop();
        conn = null;
        state = "connecting";
        DexLog.step("web", "rendezvous disconnected");
        Web.announce(ctx);
    }

    private void startPings(WebSocketConn socketConn) {
        Thread pinger = new Thread(() -> {
            while (running && !socketConn.isClosed()) {
                try {
                    Thread.sleep(PING_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (socketConn.isClosed()) return;
                socketConn.sendText("{\"t\":\"ping\"}");
            }
        }, "web-signal-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int state = 0;
        while (buffer.size() < 8192) {
            int b = in.read();
            if (b < 0) throw new IOException("rendezvous closed during the handshake");
            buffer.write(b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
        }
        return buffer.toString("UTF-8");
    }

    private final WebSocketConn.Listener listener = new WebSocketConn.Listener() {
        @Override
        public void onText(WebSocketConn c, String text) {
            // Everything from the rendezvous is signalling for the hub. It is
            // NOT trusted content: the hub validates the shape, and the peer
            // still has to know the access code before it sees a frame.
            hub.onSignal(text, WebSignal.this);
        }

        @Override
        public void onClosed(WebSocketConn c) {
        }
    };

    /** {@link WebRtcHub.Route} — answers go back up the same socket. */
    @Override
    public void send(String peerId, String json) {
        WebSocketConn c = conn;
        if (c != null && !c.isClosed()) c.sendText(json);
    }
}
