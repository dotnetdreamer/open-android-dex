package com.ccrstech.openandroiddex.launcher;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A WebSocket, in both directions — the launcher is a server to browsers on the
 * same network and a client to the rendezvous, so it genuinely needs both ends
 * of RFC 6455.
 *
 * <p>Hand-written rather than pulled in, like everything else in this APK bar
 * WebRTC itself: the protocol is a length prefix, a mask and six opcodes.
 *
 * <p>It carries <b>signalling only</b> — offers, answers and ICE candidates,
 * a few kilobytes per session. An earlier version also pushed video frames
 * through here and needed a byte-bounded queue that threw away stale ones to
 * survive a slow link; the media moved to WebRTC, which does congestion
 * control properly, and that whole apparatus went with it. What is left is a
 * small queue so a write cannot block the reader.
 *
 * <p>The one asymmetry worth remembering: client-to-server frames are masked
 * and server-to-client frames must not be. The same codec therefore does the
 * opposite thing depending on which end it is, which is what {@code client} is.
 */
final class WebSocketConn {

    /** RFC 6455's fixed handshake salt. */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONT = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    /** Queued messages. Signalling is small; anything past this is a fault. */
    private static final int QUEUE_MAX = 128;
    /** Longest single message we will accept. */
    private static final int MAX_INBOUND = 256 * 1024;

    interface Listener {
        void onText(WebSocketConn conn, String text);

        void onClosed(WebSocketConn conn);
    }

    final String id;
    final String peer;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final boolean client;
    private final Random mask = new Random();

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread writer;

    WebSocketConn(String id, Socket socket, InputStream in, OutputStream out, Listener listener) {
        this(id, socket, in, out, listener, false);
    }

    WebSocketConn(String id, Socket socket, InputStream in, OutputStream out, Listener listener,
                  boolean client) {
        this.id = id;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.listener = listener;
        this.client = client;
        this.peer = socket.getInetAddress() == null
                ? "?" : socket.getInetAddress().getHostAddress();
    }

    /** The one computed value in the handshake: base64(sha1(key + GUID)). */
    static String acceptFor(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    void start() {
        writer = new Thread(this::writeLoop, "web-ws-tx");
        writer.setDaemon(true);
        writer.start();
    }

    boolean isClosed() {
        return closed.get();
    }

    void sendText(String text) {
        if (closed.get()) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (queue) {
            if (queue.size() >= QUEUE_MAX) {
                DexLog.warn("web", "signalling backlog on " + peer + " — dropping the connection");
                closeQuietly();
                return;
            }
            queue.addLast(payload);
            queue.notifyAll();
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] payload;
                synchronized (queue) {
                    while (queue.isEmpty() && !closed.get()) queue.wait();
                    if (closed.get()) break;
                    payload = queue.pollFirst();
                }
                writeFrame(OP_TEXT, payload);
            }
        } catch (Exception e) {
            // A viewer closing its tab lands here as a broken pipe. Not news.
        } finally {
            closeQuietly();
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        byte[] header;
        int len = payload.length;
        int maskBit = client ? 0x80 : 0;
        if (len < 126) {
            header = new byte[]{(byte) (0x80 | opcode), (byte) (maskBit | len)};
        } else if (len <= 0xFFFF) {
            header = new byte[]{(byte) (0x80 | opcode), (byte) (maskBit | 126),
                    (byte) (len >> 8), (byte) len};
        } else {
            header = new byte[]{(byte) (0x80 | opcode), (byte) (maskBit | 127),
                    0, 0, 0, 0,
                    (byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len};
        }
        synchronized (out) {
            out.write(header);
            if (client) {
                byte[] key = new byte[4];
                mask.nextBytes(key);
                out.write(key);
                byte[] masked = new byte[payload.length];
                for (int i = 0; i < payload.length; i++) {
                    masked[i] = (byte) (payload[i] ^ key[i & 3]);
                }
                out.write(masked);
            } else {
                out.write(payload);
            }
            out.flush();
        }
    }

    /**
     * Read until the socket goes away. Runs on the connection's own thread.
     *
     * Continuation frames are assembled; a message longer than
     * {@link #MAX_INBOUND} closes the connection rather than growing a buffer
     * on a stranger's say-so.
     */
    void readLoop() {
        byte[] assembled = null;
        int assembledOp = 0;
        try {
            while (!closed.get()) {
                int b0 = readByte();
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len > MAX_INBOUND
                        || (assembled != null && assembled.length + len > MAX_INBOUND)) {
                    DexLog.warn("web", "oversized frame from " + peer + " — closing");
                    break;
                }
                byte[] maskKey = new byte[4];
                if (masked) for (int i = 0; i < 4; i++) maskKey[i] = (byte) readByte();
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];
                }

                if (opcode == OP_CLOSE) break;
                if (opcode == OP_PING) {
                    writeFrame(OP_PONG, payload);
                    continue;
                }
                if (opcode == OP_PONG) continue;
                if (opcode == OP_BINARY) continue;      // nothing here speaks binary

                if (opcode == OP_CONT) {
                    if (assembled == null) continue;    // stray continuation
                    assembled = concat(assembled, payload);
                } else {
                    assembled = payload;
                    assembledOp = opcode;
                }
                if (!fin) continue;
                byte[] message = assembled;
                int op = assembledOp;
                assembled = null;
                if (op == OP_TEXT) {
                    listener.onText(this, new String(message, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            // Same as the writer: a closed tab is not an error worth a stack.
        } finally {
            closeQuietly();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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

    void closeQuietly() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (queue) {
            queue.clear();
            queue.notifyAll();
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        try {
            listener.onClosed(this);
        } catch (Exception ignored) {
        }
    }
}
