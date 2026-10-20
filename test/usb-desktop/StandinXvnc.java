package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A stand-in for Xvnc on the phone's own loopback, for StreamHarness: what
 * the stream reads in production is a local socket, and a stand-in across
 * {@code adb reverse} measures the cable instead of the phone.
 *
 * <p>The worst case for the pipeline, on purpose: every frame changes every
 * pixel — a patterned strip sliding sideways eight pixels a frame, fine lines
 * included — sent as raw at up to 60 updates a second. Xvnc only ever sends
 * what changed, so a real desktop asks less of everything downstream.
 *
 * <p>The clipboard is TigerVNC's extended one, as Xvnc 1.13 does it: its
 * capabilities in answer to the encodings, the guest's text announced and
 * provided when asked for, a client's text asked for as soon as it is
 * announced (something in the guest pasting at once) — so the stream's
 * zlib runs on the phone's own runtime.
 *
 * <p>Says what it is told as {@code EV {...}} lines on stdout.
 */
final class StandinXvnc {
    final int port;
    private final String password;
    private final ServerSocket server;
    private volatile int width;
    private volatile int height;
    private volatile OutputStream out;
    private final Object writeLock = new Object();
    private byte[] strip;
    private int stripW;
    /** The client asked for the extended clipboard, and was told it is there. */
    private volatile boolean extClip;
    /** The guest's clipboard, for a client that asks. */
    private volatile String guestText = "";

    private static final int CLIP_TEXT = 1;
    private static final int CLIP_CAPS = 1 << 24;
    private static final int CLIP_REQUEST = 1 << 25;
    private static final int CLIP_PEEK = 1 << 26;
    private static final int CLIP_NOTIFY = 1 << 27;
    private static final int CLIP_PROVIDE = 1 << 28;

    StandinXvnc(int w, int h, String password) throws IOException {
        this.width = w;
        this.height = h;
        this.password = password;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    Thread c = new Thread(() -> serve(s), "standin-xvnc");
                    c.setDaemon(true);
                    c.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "standin-xvnc-accept");
        t.setDaemon(true);
        t.start();
    }

    private static void ev(String json) {
        System.out.println("EV " + json);
        System.out.flush();
    }

    /** A strip twice the desktop's width, B,G,R,X; frame n shows columns n*8 onwards. */
    private void makeStrip() {
        stripW = width * 2;
        strip = new byte[stripW * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < stripW; x++) {
                int o = (y * stripW + x) * 4;
                boolean check = ((x / 32) + (y / 32)) % 2 == 0;
                boolean line = x % 7 == 0 || y % 9 == 0;
                int r = line ? 20 : x * 255 / stripW;
                int g = line ? 20 : y * 255 / Math.max(1, height);
                int b = line ? 20 : (check ? 230 : 40);
                strip[o] = (byte) b;
                strip[o + 1] = (byte) g;
                strip[o + 2] = (byte) r;
            }
        }
    }

    /** A JSON string, every character outside ASCII escaped, so the EV line survives any console. */
    static String json(String t) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** ServerCutText in its extended form. */
    private void extended(OutputStream o, int flags, byte[] rest) throws IOException {
        int n = 4 + rest.length;
        java.nio.ByteBuffer m = java.nio.ByteBuffer.allocate(8 + n);
        m.put((byte) 3).put(new byte[3]).putInt(-n).putInt(flags).put(rest);
        synchronized (writeLock) {
            o.write(m.array());
            o.flush();
        }
    }

    /** As Xvnc provides text: its length, UTF-8 with CRLF and a NUL, deflated and sync-flushed. */
    private static byte[] provideBody(String text) {
        byte[] t = (text.replace("\r\n", "\n").replace("\n", "\r\n") + "\0").getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(4 + t.length);
        raw.putInt(t.length).put(t);
        Deflater d = new Deflater();
        d.setInput(raw.array());
        java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        do {
            n = d.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            z.write(buf, 0, n);
        } while (n == buf.length);
        d.end();
        return z.toByteArray();
    }

    void sendClipboard(String text) {
        OutputStream o = out;
        if (o == null) return;
        guestText = text;
        if (extClip) {
            try {
                extended(o, CLIP_NOTIFY | CLIP_TEXT, new byte[0]);
            } catch (IOException ignored) {
            }
            return;
        }
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        byte[] m = new byte[8 + t.length];
        m[0] = 3;
        m[4] = (byte) (t.length >>> 24);
        m[5] = (byte) (t.length >>> 16);
        m[6] = (byte) (t.length >>> 8);
        m[7] = (byte) t.length;
        System.arraycopy(t, 0, m, 8, t.length);
        try {
            synchronized (writeLock) {
                o.write(m);
                o.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSendBufferSize(4 * 1024 * 1024);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            OutputStream o = new BufferedOutputStream(s.getOutputStream(), 1024 * 1024);
            o.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            o.flush();
            in.readFully(new byte[12]);
            o.write(new byte[]{1, 2});
            o.flush();
            if (in.readUnsignedByte() != 2) return;
            byte[] challenge = new byte[16];
            new SecureRandom().nextBytes(challenge);
            o.write(challenge);
            o.flush();
            byte[] response = new byte[16];
            in.readFully(response);
            if (!Arrays.equals(response, LinuxStreamRfb.Des.vncResponse(challenge, password))) {
                ev("{\"type\":\"auth-failed\"}");
                o.write(new byte[]{0, 0, 0, 1, 0, 0, 0, 0});
                o.flush();
                return;
            }
            o.write(new byte[]{0, 0, 0, 0});
            o.flush();
            ev("{\"type\":\"client-init\",\"shared\":" + in.readUnsignedByte() + "}");
            byte[] name = "standin:phone".getBytes(StandardCharsets.US_ASCII);
            java.nio.ByteBuffer init = java.nio.ByteBuffer.allocate(24 + name.length);
            init.putShort((short) width).putShort((short) height);
            init.put(new byte[]{32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
            init.putInt(name.length).put(name);
            o.write(init.array());
            o.flush();
            out = o;
            makeStrip();
            boolean layoutPending = true;
            boolean cursorPending = true;
            int frame = 0;
            long lastSent = 0;
            while (true) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0:
                        in.readFully(new byte[19]);
                        break;
                    case 2: {
                        in.readUnsignedByte();
                        int n = in.readUnsignedShort();
                        boolean wantsClip = false;
                        for (int i = 0; i < n; i++) wantsClip |= in.readInt() == 0xC0A1E5CE;
                        if (wantsClip && !extClip) {
                            // Xvnc's answer, ahead of any picture: its actions, text, nothing unasked.
                            extClip = true;
                            extended(o, CLIP_CAPS | CLIP_REQUEST | CLIP_PEEK | CLIP_NOTIFY | CLIP_PROVIDE | CLIP_TEXT, new byte[4]);
                        }
                        break;
                    }
                    case 3: {
                        boolean incremental = in.readUnsignedByte() == 1;
                        in.readFully(new byte[8]);
                        if (incremental) {
                            long wait = 16_667_000L - (System.nanoTime() - lastSent);
                            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                        }
                        lastSent = System.nanoTime();
                        frame++;
                        sendUpdate(o, frame, layoutPending, cursorPending);
                        layoutPending = false;
                        cursorPending = false;
                        break;
                    }
                    case 4: {
                        boolean down = in.readUnsignedByte() == 1;
                        in.readUnsignedShort();
                        long keysym = in.readInt() & 0xffffffffL;
                        ev("{\"type\":\"key\",\"down\":" + down + ",\"keysym\":" + keysym + "}");
                        break;
                    }
                    case 5: {
                        int b = in.readUnsignedByte();
                        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                        ev("{\"type\":\"pointer\",\"buttons\":" + b + ",\"x\":" + x + ",\"y\":" + y + "}");
                        break;
                    }
                    case 6: {
                        in.readFully(new byte[3]);
                        int len = in.readInt();
                        byte[] t = new byte[Math.abs(len)];
                        in.readFully(t);
                        if (len >= 0) {
                            ev("{\"type\":\"clipboard\",\"text\":" + json(new String(t, StandardCharsets.ISO_8859_1)) + "}");
                        } else {
                            clientClipboard(o, t);
                        }
                        break;
                    }
                    case 251: {
                        in.readUnsignedByte();
                        int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                        int screens = in.readUnsignedByte();
                        in.readUnsignedByte();
                        in.readFully(new byte[16 * screens]);
                        ev("{\"type\":\"set-desktop-size\",\"w\":" + w + ",\"h\":" + h + "}");
                        width = w;
                        height = h;
                        makeStrip();
                        layoutPending = true;
                        break;
                    }
                    default:
                        ev("{\"type\":\"unknown\",\"message\":" + type + "}");
                        return;
                }
            }
        } catch (Exception e) {
            ev("{\"type\":\"closed\"}");
        } finally {
            out = null;
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** A client's extended clipboard message: its flags, then what the action carries. */
    private void clientClipboard(OutputStream o, byte[] m) throws IOException {
        int flags = ((m[0] & 0xff) << 24) | ((m[1] & 0xff) << 16) | ((m[2] & 0xff) << 8) | (m[3] & 0xff);
        if ((flags & CLIP_CAPS) != 0) {
            ev("{\"type\":\"clip-caps\",\"flags\":" + (flags & 0xffffffffL) + "}");
        } else if ((flags & CLIP_REQUEST) != 0) {
            ev("{\"type\":\"clip-request\"}");
            extended(o, CLIP_PROVIDE | CLIP_TEXT, provideBody(guestText));
        } else if ((flags & CLIP_PEEK) != 0) {
            extended(o, CLIP_NOTIFY | (guestText.isEmpty() ? 0 : CLIP_TEXT), new byte[0]);
        } else if ((flags & CLIP_NOTIFY) != 0) {
            boolean text = (flags & CLIP_TEXT) != 0;
            ev("{\"type\":\"clip-notify\",\"text\":" + text + "}");
            // Something in the guest pastes straight away.
            if (text) extended(o, CLIP_REQUEST | CLIP_TEXT, new byte[0]);
        } else if ((flags & CLIP_PROVIDE) != 0) {
            Inflater inf = new Inflater();
            try {
                inf.setInput(m, 4, m.length - 4);
                byte[] head = new byte[4];
                int got = 0;
                while (got < 4) {
                    int k = inf.inflate(head, got, 4 - got);
                    if (k == 0 && (inf.finished() || inf.needsInput())) throw new IOException("short");
                    got += k;
                }
                int n = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
                byte[] t = new byte[n];
                got = 0;
                while (got < n) {
                    int k = inf.inflate(t, got, n - got);
                    if (k == 0 && (inf.finished() || inf.needsInput())) throw new IOException("short");
                    got += k;
                }
                String text = new String(t, 0, Math.max(0, n - 1), StandardCharsets.UTF_8);
                ev("{\"type\":\"clip-provide\",\"bytes\":" + n + ",\"nul\":" + (n > 0 && t[n - 1] == 0)
                        + ",\"text\":" + json(text.replace("\r\n", "\n")) + "}");
            } catch (Exception e) {
                ev("{\"type\":\"clip-bad\",\"error\":" + json(String.valueOf(e)) + "}");
            } finally {
                inf.end();
            }
        }
    }

    private void sendUpdate(OutputStream o, int frame, boolean layout, boolean cursor) throws IOException {
        int w = width, h = height;
        int rects = 1 + (layout ? 1 : 0) + (cursor ? 1 : 0);
        synchronized (writeLock) {
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(4);
            head.put((byte) 0).put((byte) 0).putShort((short) rects);
            o.write(head.array());
            if (layout) {
                java.nio.ByteBuffer r = java.nio.ByteBuffer.allocate(12 + 4 + 16);
                r.putShort((short) 0).putShort((short) 0).putShort((short) w).putShort((short) h).putInt(-308);
                r.put((byte) 1).put(new byte[3]);
                r.putInt(7).putShort((short) 0).putShort((short) 0).putShort((short) w).putShort((short) h).putInt(0);
                o.write(r.array());
            }
            if (cursor) {
                int cw = 12, ch = 18;
                java.nio.ByteBuffer r = java.nio.ByteBuffer.allocate(12);
                r.putShort((short) 1).putShort((short) 2).putShort((short) cw).putShort((short) ch).putInt(-239);
                o.write(r.array());
                byte[] px = new byte[cw * ch * 4];
                for (int i = 0; i < cw * ch; i++) px[i * 4 + 2] = (byte) 255;
                o.write(px);
                int stride = (cw + 7) / 8;
                byte[] mask = new byte[stride * ch];
                for (int y = 0; y < ch; y++) for (int x = 0; x <= Math.min(cw - 1, y); x++) mask[y * stride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                o.write(mask);
            }
            java.nio.ByteBuffer r = java.nio.ByteBuffer.allocate(12);
            r.putShort((short) 0).putShort((short) 0).putShort((short) w).putShort((short) h).putInt(0);
            o.write(r.array());
            int off = (frame * 8) % (stripW - w);
            for (int y = 0; y < h; y++) o.write(strip, (y * stripW + off) * 4, w * 4);
            o.flush();
        }
    }
}
