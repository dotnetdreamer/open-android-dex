package com.ccrstech.openandroiddex.wmd;

import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The app windows' wire: one socket per window, one more for the phone as a
 * whole (doc/apps-on-pc.md, "The wire").
 *
 * <p>Every message is a type byte, a big-endian u32 length and that many bytes.
 * A raw stream rather than the WebSocket the desktop's own stream used: the
 * other end is native code in the computer's app, not a browser, and a frame
 * is then one header and one write — nothing to mask, nothing to upgrade.
 * <pre>
 * both ways        0x01 JSON, UTF-8               — everything that is not a frame or a key
 * phone → computer 0x02 flags u8, pts µs u64, H.264 Annex B   — 1 key frame, 2 codec config
 *                  0x03 package length u8, package, PNG       — an app's icon
 * computer → phone 0x10                                       — one video frame arrived
 *                  0x20 buttons u8, x u16, y u16, meta u32    — the mouse, display pixels
 *                  0x21 x u16, y u16, h f32, v f32, meta u32  — the wheel, in notches
 *                  0x22 down u8, repeat u8, code u16, meta u32 — a key: Android's code and meta
 *                  0x23 UTF-8                                 — characters typed
 * </pre>
 * Writes come from several threads — the encoder's drain, the session, the
 * task watcher — and each message goes out whole, under one lock, in one write.
 */
final class Wire {

    static final byte JSON = 0x01;
    static final byte VIDEO = 0x02;
    static final byte ICON = 0x03;
    static final byte ACK = 0x10;
    static final byte POINTER = 0x20;
    static final byte SCROLL = 0x21;
    static final byte KEY = 0x22;
    static final byte TEXT = 0x23;

    /** Nothing the computer sends is near this; a key frame the phone sends can be. */
    private static final int MAX_IN = 4 * 1024 * 1024;

    /** One message read. */
    static final class Msg {
        final byte type;
        final byte[] data;

        Msg(byte type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private final InputStream in;
    private final OutputStream out;
    private final byte[] head = new byte[5];
    /** Grown to the largest message sent so far, so a frame is one write. */
    private byte[] buf = new byte[64 * 1024];

    Wire(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** The next message, or null at the end of the stream. */
    Msg read() throws IOException {
        if (!fill(head, 5, true)) return null;
        int len = ((head[1] & 0xff) << 24) | ((head[2] & 0xff) << 16) | ((head[3] & 0xff) << 8) | (head[4] & 0xff);
        if (len < 0 || len > MAX_IN) throw new IOException("a message of " + len + " bytes");
        byte[] data = new byte[len];
        fill(data, len, false);
        return new Msg(head[0], data);
    }

    private boolean fill(byte[] b, int len, boolean eofOk) throws IOException {
        int at = 0;
        while (at < len) {
            int n = in.read(b, at, len - at);
            if (n < 0) {
                if (eofOk && at == 0) return false;
                throw new EOFException();
            }
            at += n;
        }
        return true;
    }

    void json(JSONObject j) throws IOException {
        byte[] b = j.toString().getBytes(StandardCharsets.UTF_8);
        send(JSON, null, 0, b, b.length);
    }

    /** A message of {@code type}: {@code pre} (its first {@code preLen} bytes) then {@code body}'s first {@code len}. */
    synchronized void send(byte type, byte[] pre, int preLen, byte[] body, int len) throws IOException {
        int total = preLen + len;
        int need = 5 + total;
        if (buf.length < need) buf = new byte[Math.max(need, buf.length * 2)];
        buf[0] = type;
        buf[1] = (byte) (total >>> 24);
        buf[2] = (byte) (total >>> 16);
        buf[3] = (byte) (total >>> 8);
        buf[4] = (byte) total;
        if (preLen > 0) System.arraycopy(pre, 0, buf, 5, preLen);
        if (len > 0) System.arraycopy(body, 0, buf, 5 + preLen, len);
        out.write(buf, 0, need);
        out.flush();
    }

    static int u16(byte[] d, int at) {
        return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff);
    }

    static int i32(byte[] d, int at) {
        return ((d[at] & 0xff) << 24) | ((d[at + 1] & 0xff) << 16) | ((d[at + 2] & 0xff) << 8) | (d[at + 3] & 0xff);
    }
}
