package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Open in a browser"'s server, on a computer's JVM, for
 * test/linux-browser-test.mjs: LinuxBrowserServer, LinuxBrowser,
 * LinuxBrowserCert and LinuxStreamSocket exactly as the phone runs them, and
 * the page from the APK's assets. Only the desktop is a stand-in: the phone's
 * encoder is not here, so a stream is H.264 made by ffmpeg, sent as
 * LinuxStream sends it (its wire, its "video" and "state" messages), the
 * smaller file while the browser asks for a smaller picture.
 *
 * <p>Says what happens as {@code EV <json>} lines on stdout: every message a
 * browser sends, every pointer and key, acknowledgements counted. Takes
 * commands on stdin: {@code state <name> [message]}, {@code burst <name>},
 * {@code clip <base64>}, {@code drop} (the stream closes without a word),
 * {@code signout}, {@code tokens}, {@code code}, {@code newcode},
 * {@code open} and {@code close} (the phone showing its code or not),
 * {@code timeouts <pre-login ms> <silent session ms>}, {@code lockafter <n>},
 * {@code stop}, {@code restart}, {@code rival}, {@code refuse on|off},
 * {@code state?}, {@code slowhandoff <ms>} (a stream let in waits that long,
 * said with {@code HANDOFF}, before it takes the desktop: the moment a
 * sign-out or the switch could miss it).
 *
 * <p>Sound is the launcher's own LinuxStreamAudio, reading a stand-in for the
 * guest's tap: a loopback port that plays a second of a 440 Hz tone and a
 * second of silence (exact zeroes, as PulseAudio's null sink makes it), over
 * and over, in real time. Said as {@code EV {"tap":..}} when a reader comes
 * and goes, and the bytes sent in the once-a-second {@code EV {"acks":..}}.
 *
 * <p>The sound follows the browser's quality as the phone's does (its Low,
 * Medium or High, and whether its page decodes Opus). The phone's Opus
 * encoder is MediaCodec, not here: a stand-in hands out packets libopus made
 * (two Ogg files, mono 32 kb/s and stereo 96 kb/s), one for each 20 ms of
 * sound it is given, through the relay's own encoder path.
 *
 * <p>Usage: BrowserHarness &lt;dir&gt; &lt;port&gt; &lt;big.h264&gt; &lt;small.h264&gt; &lt;assets dir&gt;
 * [&lt;mono.opus&gt; &lt;stereo.opus&gt;]
 */
public final class BrowserHarness {

    static final byte MSG_VIDEO = 0x01;
    static final byte MSG_CURSOR = 0x02;
    static final byte MSG_AUDIO = 0x03;

    /** The packets of an Ogg Opus file, its two header packets left out. */
    static List<byte[]> oggPackets(byte[] ogg) {
        List<byte[]> out = new ArrayList<>();
        java.io.ByteArrayOutputStream cur = new java.io.ByteArrayOutputStream();
        int p = 0;
        while (p + 27 <= ogg.length && ogg[p] == 'O' && ogg[p + 1] == 'g' && ogg[p + 2] == 'g' && ogg[p + 3] == 'S') {
            int segs = ogg[p + 26] & 0xff;
            int data = p + 27 + segs;
            for (int i = 0; i < segs; i++) {
                int len = ogg[p + 27 + i] & 0xff;
                cur.write(ogg, data, len);
                data += len;
                if (len < 255) {
                    out.add(cur.toByteArray());
                    cur.reset();
                }
            }
            p = data;
        }
        out.removeIf(b -> b.length >= 8 && (new String(b, 0, 8, StandardCharsets.ISO_8859_1).equals("OpusHead")
                || new String(b, 0, 8, StandardCharsets.ISO_8859_1).equals("OpusTags")));
        return out;
    }

    /** MediaCodec's place: one of libopus's packets for each 20 ms of sound fed in. */
    static final class StandinOpus implements LinuxStreamAudio.Encoder {
        final int channels;
        final List<byte[]> packets;
        final LinuxStreamAudio.Sink out;
        long frames;
        int next;

        StandinOpus(int channels, List<byte[]> packets, LinuxStreamAudio.Sink out) {
            this.channels = channels;
            this.packets = packets;
            this.out = out;
            ev("{\"opus\":" + channels + "}");
        }

        @Override
        public void encode(byte[] pcm, int off, int len, long atUs) throws IOException {
            frames += len / (channels * 2);
            while (frames >= 960) {
                frames -= 960;
                byte[] p = packets.get(next++ % packets.size());
                out.audio(LinuxStreamAudio.OPUS, p, 0, p.length, atUs);
            }
        }

        @Override
        public void close() {
            ev("{\"opusClosed\":" + channels + "}");
        }
    }

    /** The stand-in tap: a tone second, a silent second, 20 ms at a time, to each reader. */
    static int startTap() throws IOException {
        java.net.ServerSocket tap = new java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (true) {
                java.net.Socket c;
                try {
                    c = tap.accept();
                } catch (IOException e) {
                    return;
                }
                Thread w = new Thread(() -> feed(c), "harness-tap");
                w.setDaemon(true);
                w.start();
            }
        }, "harness-tap-accept");
        t.setDaemon(true);
        t.start();
        return tap.getLocalPort();
    }

    static void feed(java.net.Socket c) {
        ev("{\"tap\":\"open\"}");
        byte[] chunk = new byte[960 * 4]; // 20 ms of s16le stereo at 48 kHz
        long sample = 0;
        long t0 = System.nanoTime();
        try (java.io.OutputStream out = c.getOutputStream()) {
            for (long n = 0; ; n++) {
                boolean tone = (n / 50) % 2 == 0; // fifty chunks a second
                for (int i = 0; i < 960; i++, sample++) {
                    short v = tone ? (short) (Math.sin(2 * Math.PI * 440 * sample / 48000.0) * 8000) : 0;
                    chunk[i * 4] = chunk[i * 4 + 2] = (byte) v;
                    chunk[i * 4 + 1] = chunk[i * 4 + 3] = (byte) (v >> 8);
                }
                out.write(chunk);
                out.flush();
                long due = t0 + (n + 1) * 20_000_000L;
                long left = due - System.nanoTime();
                if (left > 0) Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L));
            }
        } catch (IOException | InterruptedException e) {
            // the reader went
        } finally {
            ev("{\"tap\":\"closed\"}");
        }
    }

    static List<byte[]> units(byte[] annexB) {
        // Split before each access unit delimiter (00 00 00 01 09).
        List<byte[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + 4 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1
                    && (annexB[i + 4] & 0x1f) == 9) {
                if (start >= 0) out.add(java.util.Arrays.copyOfRange(annexB, start, i));
                start = i;
            }
        }
        if (start >= 0) out.add(java.util.Arrays.copyOfRange(annexB, start, annexB.length));
        return out;
    }

    static boolean hasIdr(byte[] au) {
        for (int i = 0; i + 3 < au.length; i++) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1 && (au[i + 3] & 0x1f) == 5) return true;
        }
        return false;
    }

    static synchronized void ev(String json) {
        System.out.println("EV " + json);
        System.out.flush();
    }

    static String q(String s) {
        return LinuxBrowserServer.jsonString(s);
    }

    static volatile Session current;
    /** What LinuxStream's own lock is to it: {@link #current} is taken, and read to end it, under this. */
    static final Object CLAIM = new Object();
    static LinuxBrowserServer.Host hostImpl;
    /** Every stream ends the moment it is handed over, without a word: a phone whose desktop never answers. */
    static volatile boolean refuse;
    /** How long a stream let in waits before it takes the desktop (said with HANDOFF); 0 for not at all. */
    static volatile long slowHandoffMs;

    /** The stream watching now, as LinuxStream.endBrowser reads it. */
    static Session watching() {
        synchronized (CLAIM) {
            return current;
        }
    }

    public static void main(String[] args) throws Exception {
        // The test reads UTF-8; a Windows console's code page would turn an em dash into '?'.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        File dir = new File(args[0]);
        int port = Integer.parseInt(args[1]);
        List<byte[]> big = units(Files.readAllBytes(new File(args[2]).toPath()));
        List<byte[]> small = units(Files.readAllBytes(new File(args[3]).toPath()));
        File assets = new File(args[4]);
        LinuxStreamAudio.port = startTap();
        if (args.length > 6) {
            final List<byte[]> mono = oggPackets(Files.readAllBytes(new File(args[5]).toPath()));
            final List<byte[]> stereo = oggPackets(Files.readAllBytes(new File(args[6]).toPath()));
            LinuxStreamAudio.opus = (channels, bitrate, out) -> new StandinOpus(channels, channels == 1 ? mono : stereo, out);
        }
        LinuxBrowser store = new LinuxBrowser(dir);
        store.setOn(true);
        // The phone's card is "showing the code" from the start; `close` and `open` move that.
        store.openPairing(System.currentTimeMillis());
        LinuxBrowserCert cert = LinuxBrowserCert.loadOrMake(dir, LinuxBrowserServer.ips(), System.currentTimeMillis());
        hostImpl = new LinuxBrowserServer.Host() {
            @Override
            public boolean stream(LinuxStreamSocket ws, String hello, Closeable sock, String who,
                                  LinuxBrowserServer.Gate gate, Runnable ended) {
                if (refuse) {
                    // Over before host.stream returns, as a stream that fails at
                    // once on its own thread can be; the one watching stays.
                    ws.close();
                    ev("{\"refused\":" + q(who) + "}");
                    ended.run();
                    return true;
                }
                long wait = slowHandoffMs;
                if (wait > 0) {
                    // Let in by the server, not yet watching: where a sign-out
                    // or the switch finds it in neither place.
                    System.out.println("HANDOFF " + who);
                    System.out.flush();
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                Session s = null;
                Session old = null;
                String no;
                // As LinuxStream.claim: the gate asked under the lock the
                // watching stream is read by, and only then the desktop taken.
                synchronized (CLAIM) {
                    no = gate.refusal();
                    if (no == null) {
                        s = new Session(ws, hello, who, ended, big, small);
                        old = current;
                        current = s;
                    }
                }
                if (no != null) {
                    // Its hello never reaches the desktop: told why, and closed
                    // as LinuxStream closes it, its pings read so that no reset
                    // takes the word with it.
                    try {
                        ws.sendText("{\"t\":\"state\",\"state\":" + q(no) + "}");
                    } catch (IOException ignored) {
                    }
                    ws.closeAfterWord(LinuxBrowserServer.LAST_WORD_MS);
                    ev("{\"turnedAway\":" + q(who) + ",\"why\":" + q(no) + "}");
                    ended.run();
                    return true;
                }
                // As LinuxStream does for a browser it replaces: told, then closed.
                if (old != null) old.replaced();
                s.start();
                return true;
            }

            @Override
            public String phoneName() {
                return "Harness Phone";
            }

            @Override
            public byte[] asset(String name) {
                try {
                    return Files.readAllBytes(new File(assets, name).toPath());
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public void changed() {
                ev("{\"changed\":" + q(store.state()) + ",\"tokens\":" + store.tokenCount() + "}");
            }
        };
        LinuxBrowserServer server = LinuxBrowserServer.start(store, cert, hostImpl, null, port);
        final int boundPort = server.port();
        System.out.println("READY " + server.port() + " " + store.code() + " " + cert.fingerprint);
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            line = line.trim();
            Session s = current;
            if (line.equals("open")) {
                store.openPairing(System.currentTimeMillis());
                System.out.println("OK open");
                System.out.flush();
            } else if (line.equals("close")) {
                store.closePairing();
                System.out.println("OK close");
                System.out.flush();
            } else if (line.startsWith("refuse ")) {
                refuse = line.endsWith(" on");
                System.out.println("OK refuse " + refuse);
                System.out.flush();
            } else if (line.equals("state?")) {
                System.out.println("STATE " + store.state());
                System.out.flush();
            } else if (line.equals("conns")) {
                System.out.println("CONNS " + server.connections());
                System.out.flush();
            } else if (line.startsWith("timeouts ")) {
                // test knobs: the pre-login deadline and a silent session's
                String[] p = line.split(" ");
                LinuxBrowserServer.preAuthMs = Integer.parseInt(p[1]);
                LinuxBrowserServer.sessionReadMs = Integer.parseInt(p[2]);
                System.out.println("OK timeouts");
                System.out.flush();
            } else if (line.equals("restart")) {
                // a new server on the same port, its per-address counts fresh, the files kept
                server.stop();
                server = LinuxBrowserServer.start(store, cert, hostImpl, null, boundPort);
                System.out.println("OK restarted");
                System.out.flush();
            } else if (line.equals("newcode")) {
                System.out.println("CODE " + store.rotateCode());
                System.out.flush();
            } else if (line.startsWith("lockafter ")) {
                LinuxBrowserServer.lockAfter = Integer.parseInt(line.substring(10).trim());
                System.out.println("OK lockafter");
                System.out.flush();
            } else if (line.equals("stop")) {
                // the switch off, as LinuxService does it: closed first, then the watcher told
                server.stop();
                Session w = watching();
                if (w != null) w.tell("off");
                System.out.println("OK stopped");
                System.out.flush();
            } else if (line.startsWith("slowhandoff ")) {
                slowHandoffMs = Long.parseLong(line.substring(12).trim());
                System.out.println("OK slowhandoff " + slowHandoffMs);
                System.out.flush();
            } else if (line.equals("rival")) {
                // a second server on the same port, as another app holding it would be
                try {
                    LinuxBrowserServer.start(new LinuxBrowser(new File(dir, "rival")), cert, hostImpl, null, server.port());
                    System.out.println("RIVAL listening");
                } catch (LinuxBrowserServer.PortTaken e) {
                    System.out.println("RIVAL taken " + e.port + " " + new LinuxBrowser(new File(dir, "rival")).state());
                }
                System.out.flush();
            } else if (line.startsWith("state ")) {
                String[] p = line.split(" ", 3);
                if (s != null) s.text("{\"t\":\"state\",\"state\":" + q(p[1]) + (p.length > 2 ? ",\"message\":" + q(p[2]) : "") + "}");
            } else if (line.startsWith("clip ")) {
                String text = new String(Base64.getDecoder().decode(line.substring(5)), StandardCharsets.UTF_8);
                if (s != null) s.text("{\"t\":\"clip\",\"text\":" + q(text) + "}");
            } else if (line.startsWith("burst ")) {
                // Frames, then the word, in one breath: the frames are still
                // in the browser's decoder when it reads the word.
                if (s != null) s.burstThen("{\"t\":\"state\",\"state\":" + q(line.substring(6).trim()) + "}");
            } else if (line.equals("drop")) {
                if (s != null) s.drop();
            } else if (line.equals("signout")) {
                // as LinuxService does it: the tokens, the watcher told and ended, the half-open dropped
                store.signOutAll();
                Session w = watching();
                if (w != null) w.tell("unauthorized");
                server.dropOpen();
                System.out.println("CODE " + store.code());
                System.out.flush();
            } else if (line.equals("code")) {
                System.out.println("CODE " + store.code());
                System.out.flush();
            } else if (line.equals("tokens")) {
                System.out.println("TOKENS " + store.tokenCount());
                System.out.flush();
            }
        }
        server.stop();
        System.exit(0);
    }

    /** One browser's stream: a desktop of the window's size, drawn from the files. */
    static final class Session {
        final LinuxStreamSocket ws;
        final String who;
        final Runnable ended;
        final List<byte[]> big;
        final List<byte[]> small;
        volatile boolean closing;
        volatile double scale = 1;
        volatile int dw;
        volatile int dh;
        volatile boolean restart = true;
        volatile int acks;
        Thread pump;
        /** The launcher's own relay, as LinuxStream runs it for a browser; null while sound is off. */
        LinuxStreamAudio sound;
        final java.util.concurrent.atomic.AtomicLong soundBytes = new java.util.concurrent.atomic.AtomicLong();
        long soundSeen;
        /** As LinuxStream's: the browser's quality, and whether its page decodes Opus. */
        volatile String mode = "medium";
        volatile boolean pageOpus;

        Session(LinuxStreamSocket ws, String hello, String who, Runnable ended, List<byte[]> big, List<byte[]> small) {
            this.ws = ws;
            this.who = who;
            this.ended = ended;
            this.big = big;
            this.small = small;
            dw = num(hello, "w", 1280);
            dh = num(hello, "h", 800);
            scale = dbl(hello, "scale", 1);
            ev("{\"hello\":" + hello + ",\"who\":" + q(who) + "}");
            mode = word(hello, "mode", "medium");
            pageOpus = hello.matches("(?s).*\"codecs\"\\s*:\\s*\\[[^\\]]*\"opus\".*");
            if (hello.matches("(?s).*\"audio\"\\s*:\\s*true.*")) sound(true);
        }

        LinuxStreamAudio.Format soundFormat() {
            return LinuxStreamAudio.Format.forMode(mode, pageOpus && LinuxStreamAudio.opus != null);
        }

        /** As LinuxStream's: the tap read for this browser, or not at all. */
        synchronized void sound(boolean on) {
            if (on && !closing) {
                if (sound == null) {
                    sound = new LinuxStreamAudio(new LinuxStreamAudio.Sink() {
                        @Override
                        public void format(LinuxStreamAudio.Format f) throws IOException {
                            ws.sendText(f.json());
                            ev("{\"soundFormat\":" + f.json() + "}");
                        }

                        @Override
                        public void audio(int codec, byte[] data, int off, int len, long atUs) throws IOException {
                            ByteBuffer head = ByteBuffer.allocate(10);
                            head.put(MSG_AUDIO).put((byte) codec).putLong(atUs);
                            ws.sendBinary(head.array(), off == 0 ? data : java.util.Arrays.copyOfRange(data, off, off + len), len);
                            soundBytes.addAndGet(len);
                        }
                    }, who, soundFormat()).start();
                    ev("{\"sound\":true}");
                }
            } else if (sound != null) {
                sound.stop();
                sound = null;
                ev("{\"sound\":false}");
            }
        }

        static String word(String json, String key, String dflt) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([a-z]+)\"").matcher(json);
            return m.find() ? m.group(1) : dflt;
        }

        static int num(String json, String key, int dflt) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : dflt;
        }

        static double dbl(String json, String key, double dflt) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
            return m.find() ? Double.parseDouble(m.group(1)) : dflt;
        }

        void text(String json) {
            try {
                ws.sendText(json);
            } catch (IOException e) {
                close();
            }
        }

        void video() {
            boolean scaled = scale < 0.99;
            int cw = scaled ? 852 : 1280;
            int ch = scaled ? 480 : 720;
            text("{\"t\":\"video\",\"codec\":\"avc\",\"encoder\":\"harness.h264\",\"hw\":true,\"w\":" + cw + ",\"h\":" + ch
                    + ",\"cw\":" + cw + ",\"ch\":" + ch + ",\"dw\":" + dw + ",\"dh\":" + dh
                    + ",\"bitrate\":10000000,\"fps\":60,\"lowLatency\":true}");
            restart = true;
        }

        void start() {
            text("{\"t\":\"state\",\"state\":\"running\"}");
            video();
            // A 12x12 pointer: a white square with a black edge, its hot spot 1,1.
            byte[] rgba = new byte[12 * 12 * 4];
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    boolean edge = x == 0 || y == 0 || x == 11 || y == 11;
                    int o = (y * 12 + x) * 4;
                    rgba[o] = rgba[o + 1] = rgba[o + 2] = (byte) (edge ? 0 : 255);
                    rgba[o + 3] = (byte) 255;
                }
            }
            ByteBuffer head = ByteBuffer.allocate(9);
            head.put(MSG_CURSOR).putShort((short) 1).putShort((short) 1).putShort((short) 12).putShort((short) 12);
            try {
                ws.sendBinary(head.array(), rgba, rgba.length);
            } catch (IOException e) {
                close();
                return;
            }
            pump = new Thread(this::pumpLoop, "harness-pump");
            pump.setDaemon(true);
            pump.start();
            Thread input = new Thread(this::inputLoop, "harness-input");
            input.setDaemon(true);
            input.start();
        }

        void pumpLoop() {
            int i = 0;
            long t0 = System.nanoTime();
            long frame = 0;
            try {
                while (!closing) {
                    List<byte[]> file = scale < 0.99 ? small : big;
                    if (restart) {
                        i = 0;
                        restart = false;
                    }
                    if (i >= file.size()) i = 0; // round again, from the key frame
                    byte[] au = file.get(i++);
                    int flags = hasIdr(au) ? 1 : 0;
                    ByteBuffer head = ByteBuffer.allocate(10);
                    head.put(MSG_VIDEO).put((byte) flags).putLong(System.nanoTime() / 1000);
                    ws.sendBinary(head.array(), au, au.length);
                    frame++;
                    // Sixty a second, to the frame: Windows sleeps in 15.6 ms
                    // steps, so the last of each wait is spun.
                    long due = t0 + frame * 16_666_667L;
                    for (long left; (left = due - System.nanoTime()) > 0; ) {
                        if (left > 18_000_000L) Thread.sleep(1);
                        else Thread.onSpinWait();
                    }
                    if (frame % 60 == 0) {
                        // As LinuxStream's, the sound's bitrate with the picture's.
                        long sb = soundBytes.get();
                        long soundKbps = (sb - soundSeen) * 8 / 1000;
                        soundSeen = sb;
                        text("{\"t\":\"stats\",\"fps\":60,\"kbps\":7700,\"encodeMs\":4.2,\"soundKbps\":" + soundKbps + "}");
                        ev("{\"acks\":" + acks + ",\"frames\":" + frame + ",\"soundBytes\":" + soundBytes.get() + "}");
                    }
                }
            } catch (Exception e) {
                close();
            }
        }

        void inputLoop() {
            try {
                while (!closing) {
                    LinuxStreamSocket.Message m = ws.read();
                    if (m == null) break;
                    if (m.opcode == LinuxStreamSocket.OP_BINARY) {
                        byte[] d = m.data;
                        if (d.length >= 6 && d[0] == 0x10) {
                            ev("{\"pointer\":[" + (d[1] & 0xff) + "," + (((d[2] & 0xff) << 8) | (d[3] & 0xff)) + ","
                                    + (((d[4] & 0xff) << 8) | (d[5] & 0xff)) + "]}");
                        } else if (d.length >= 6 && d[0] == 0x11) {
                            int ks = ((d[2] & 0xff) << 24) | ((d[3] & 0xff) << 16) | ((d[4] & 0xff) << 8) | (d[5] & 0xff);
                            ev("{\"key\":[" + (d[1] & 0xff) + "," + ks + "]}");
                        } else if (d.length >= 1 && d[0] == 0x12) {
                            acks++;
                        }
                        continue;
                    }
                    String t = m.text();
                    ev("{\"msg\":" + t + "}");
                    if (t.contains("\"t\":\"ping\"")) {
                        Matcher n = Pattern.compile("\"n\"\\s*:\\s*([0-9.]+)").matcher(t);
                        text("{\"t\":\"pong\",\"n\":" + (n.find() ? n.group(1) : "0") + "}");
                    } else if (t.contains("\"t\":\"quality\"")) {
                        double s = dbl(t, "scale", 1);
                        if (Math.abs(s - scale) > 0.01) {
                            scale = s;
                            video();
                        }
                        String m2 = word(t, "mode", mode);
                        if (!m2.equals(mode)) {
                            mode = m2;
                            LinuxStreamAudio a;
                            synchronized (this) {
                                a = sound;
                            }
                            if (a != null) a.setFormat(soundFormat());
                        }
                    } else if (t.contains("\"t\":\"resize\"")) {
                        dw = num(t, "w", dw);
                        dh = num(t, "h", dh);
                        video();
                    } else if (t.contains("\"t\":\"keyframe\"")) {
                        restart = true;
                    } else if (t.contains("\"t\":\"audio\"")) {
                        if (t.contains("\"codecs\"")) pageOpus = t.matches("(?s).*\"codecs\"\\s*:\\s*\\[[^\\]]*\"opus\".*");
                        sound(t.matches("(?s).*\"on\"\\s*:\\s*true.*"));
                    }
                }
            } catch (IOException e) {
                // gone
            }
            close();
        }

        void burstThen(String json) {
            List<byte[]> file = scale < 0.99 ? small : big;
            try {
                for (int i = 1; i <= 3; i++) {
                    byte[] au = file.get(i);
                    ByteBuffer head = ByteBuffer.allocate(10);
                    head.put(MSG_VIDEO).put((byte) 0).putLong(System.nanoTime() / 1000 + i);
                    ws.sendBinary(head.array(), au, au.length);
                }
                ws.sendText(json);
            } catch (IOException e) {
                close();
            }
        }

        void replaced() {
            tell("replaced");
        }

        /** A word, then the end: as LinuxStream.tellThenClose. */
        void tell(String state) {
            text("{\"t\":\"state\",\"state\":" + q(state) + "}");
            close();
        }

        void drop() {
            close();
        }

        void close() {
            if (closing) return;
            closing = true;
            sound(false);
            ws.close();
            ev("{\"ended\":" + q(who) + "}");
            ended.run();
            synchronized (CLAIM) {
                if (current == this) current = null;
            }
        }
    }
}
