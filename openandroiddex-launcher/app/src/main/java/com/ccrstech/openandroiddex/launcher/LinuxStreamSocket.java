package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The server end of one WebSocket, binary and text, for {@link LinuxStream} —
 * and of the one plain HTTP request it also answers, {@code GET /probe}.
 *
 * <p>A WebSocket and not a protocol of our own because the far end is a page:
 * the computer's window reads the stream with the browser's own socket, and
 * the computer's app in between only carries bytes — it checks who is asking
 * and splices, exactly as it does for websockify's. The same splice can carry
 * this over the tunnel without either end changing.
 *
 * <p>Not {@link WebSocketConn}: that one is text-only, queues every message and
 * drops the connection on a backlog, which is right for signalling and wrong
 * for video, where the writer must block so the encoder slows down with the
 * link. This one writes on the caller's thread, under one lock, and nothing
 * else.
 *
 * <p>Pure Java — no android.* — so the stand-in test on a computer runs this
 * very file. So does the window daemon's dex (openandroiddex-wmd/build.sh), for
 * the Android desktop's own stream (doc/dex-video.md): hence public.
 */
public final class LinuxStreamSocket implements Closeable {

    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    private static final int OP_CONT = 0x0;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    /** RFC 6455's fixed handshake salt. */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** No request the window makes comes near this. */
    private static final int MAX_HEAD = 16 * 1024;
    /**
     * The longest message a computer sends: clipboard text, which Xvnc caps at
     * 256 KB, in UTF-8. Anything longer closes the socket rather than growing
     * a buffer on the far end's say-so.
     */
    private static final int MAX_INBOUND = 2 * 1024 * 1024;

    /** The request a connection opened with. */
    public static final class Request {
        public final String method;
        public final String path;
        /** Lower-cased names; the last value of a repeated header. */
        public final Map<String, String> headers;

        Request(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }
    }

    /** One message from the computer. */
    public static final class Message {
        public final int opcode;
        public final byte[] data;

        Message(int opcode, byte[] data) {
            this.opcode = opcode;
            this.data = data;
        }

        public String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private final InputStream in;
    private final OutputStream out;
    private final Closeable underlying;
    /**
     * Every frame goes out whole under this. A lock rather than a monitor so
     * that {@link #close} can skip its goodbye instead of queueing behind a
     * video frame stuck on a link that has stopped moving.
     */
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    public LinuxStreamSocket(InputStream in, OutputStream out, Closeable underlying) {
        this.in = new BufferedInputStream(in, 16 * 1024);
        this.out = new BufferedOutputStream(out, 64 * 1024);
        this.underlying = underlying;
    }

    /** The request line and headers the connection opened with. */
    public Request readRequest() throws IOException {
        String head = readHead();
        String[] lines = head.split("\r\n");
        String[] first = lines.length == 0 ? new String[0] : lines[0].split(" ");
        if (first.length < 3 || !first[2].startsWith("HTTP/1.")) throw new IOException("not HTTP");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        String path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return new Request(first[0], path, headers);
    }

    /**
     * Answer {@code req} as a WebSocket upgrade.
     *
     * <p>Who may ask was settled before this — the socket only takes adb and
     * this app (see {@link LinuxStream}), and the computer's end checked Host
     * and Origin before passing the request on. The checks here are the same
     * two again, looser, for a computer app older or newer than this one.
     */
    public void upgrade(Request req) throws IOException {
        String key = req.headers.get("sec-websocket-key");
        String host = req.headers.get("host");
        String origin = req.headers.get("origin");
        if (!"GET".equals(req.method)) throw new IOException("not a GET");
        if (!"websocket".equalsIgnoreCase(req.headers.get("upgrade")) || key == null) {
            throw new IOException("not a WebSocket upgrade");
        }
        if (host != null && !loopback(host)) throw new IOException("another host: " + host);
        if (origin != null && !allowedOrigin(origin)) throw new IOException("another origin: " + origin);
        String accept = Base64.getEncoder().encodeToString(sha1(key + GUID));
        writeRaw(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    /** A plain HTTP answer, after which the connection closes. */
    public void respond(int status, String reason, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        writeLock.lock();
        try {
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } finally {
            writeLock.unlock();
        }
        closed = true;
        try {
            underlying.close();
        } catch (IOException ignored) {
        }
    }

    private void writeRaw(byte[] b) throws IOException {
        writeLock.lock();
        try {
            out.write(b);
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /** `127.0.0.1:<port>` or `localhost:<port>`, the way the computer's forward addresses itself. */
    public static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0) h = h.substring(0, colon);
        return h.equals("127.0.0.1") || h.equals("localhost");
    }

    /**
     * The desktop app's own page — bundled (tauri://localhost on a Mac and
     * Linux, http://tauri.localhost on Windows) or from its dev server on
     * localhost — or a page on loopback. A web page anywhere else names itself
     * here and is refused.
     */
    public static boolean allowedOrigin(String origin) {
        String o = origin.toLowerCase(Locale.ROOT);
        if (o.equals("tauri://localhost") || o.equals("http://tauri.localhost")
                || o.equals("https://tauri.localhost")) {
            return true;
        }
        return o.startsWith("http://") && loopback(o.substring("http://".length()));
    }

    private String readHead() throws IOException {
        byte[] buf = new byte[MAX_HEAD];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("closed before the request");
            if (n == buf.length) throw new IOException("request head too large");
            buf[n++] = (byte) b;
            if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n') {
                return new String(buf, 0, n - 4, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static byte[] sha1(String s) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("no SHA-1", e);
        }
    }

    /**
     * The next text or binary message, continuations assembled; pings
     * answered on the way. Null once the computer closed it.
     */
    public Message read() throws IOException {
        byte[] assembled = null;
        int assembledOp = 0;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            long already = assembled == null ? 0 : assembled.length;
            if (len < 0 || len + already > MAX_INBOUND) throw new IOException("message too large");
            byte[] mask = new byte[4];
            if (masked) readFully(mask);
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            switch (opcode) {
                case OP_CLOSE:
                    return null;
                case OP_PING:
                    writeFrame(OP_PONG, null, payload, payload.length);
                    continue;
                case OP_PONG:
                    continue;
                case OP_CONT:
                    if (assembled == null) continue; // a stray continuation
                    byte[] joined = new byte[assembled.length + payload.length];
                    System.arraycopy(assembled, 0, joined, 0, assembled.length);
                    System.arraycopy(payload, 0, joined, assembled.length, payload.length);
                    assembled = joined;
                    break;
                default:
                    assembled = payload;
                    assembledOp = opcode;
            }
            if (fin && assembled != null) {
                if (assembledOp == OP_TEXT || assembledOp == OP_BINARY) return new Message(assembledOp, assembled);
                assembled = null;
            }
        }
    }

    public void sendText(String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        writeFrame(OP_TEXT, null, b, b.length);
    }

    /** One binary message: {@code head} then the first {@code len} bytes of {@code body}. */
    public void sendBinary(byte[] head, byte[] body, int len) throws IOException {
        writeFrame(OP_BINARY, head, body, len);
    }

    private void writeFrame(int opcode, byte[] head, byte[] body, int bodyLen) throws IOException {
        int headLen = head == null ? 0 : head.length;
        long len = (long) headLen + bodyLen;
        byte[] frame;
        if (len < 126) {
            frame = new byte[]{(byte) (0x80 | opcode), (byte) len};
        } else if (len <= 0xFFFF) {
            frame = new byte[]{(byte) (0x80 | opcode), 126, (byte) (len >> 8), (byte) len};
        } else {
            frame = new byte[10];
            frame[0] = (byte) (0x80 | opcode);
            frame[1] = 127;
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) (len >>> (56 - 8 * i));
        }
        writeLock.lock();
        try {
            if (closed) throw new IOException("closed");
            out.write(frame);
            if (headLen > 0) out.write(head);
            if (bodyLen > 0) out.write(body, 0, bodyLen);
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private int readByte() throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Say goodbye when nothing else is writing, then close the socket — which
     * is also what frees a writer stuck on a dead link. Any thread, any number
     * of times.
     */
    @Override
    public void close() {
        if (closed) return;
        if (writeLock.tryLock()) {
            try {
                if (!closed) {
                    out.write(new byte[]{(byte) (0x80 | OP_CLOSE), 0});
                    out.flush();
                }
            } catch (IOException ignored) {
                // already gone
            } finally {
                writeLock.unlock();
            }
        }
        closed = true;
        try {
            underlying.close();
        } catch (IOException ignored) {
        }
    }
}
