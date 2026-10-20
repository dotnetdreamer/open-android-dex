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

    void sendClipboard(String text) {
        OutputStream o = out;
        if (o == null) return;
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
                        in.readFully(new byte[4 * n]);
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
                        byte[] t = new byte[in.readInt()];
                        in.readFully(t);
                        String text = new String(t, StandardCharsets.ISO_8859_1).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                        ev("{\"type\":\"clipboard\",\"text\":\"" + text + "\"}");
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
