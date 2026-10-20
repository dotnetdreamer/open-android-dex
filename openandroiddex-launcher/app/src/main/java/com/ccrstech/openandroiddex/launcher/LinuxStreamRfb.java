package com.ccrstech.openandroiddex.launcher;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Xvnc's own pixels, for {@link LinuxStream}: a VNC client on loopback that
 * asks for nothing but raw rectangles.
 *
 * <p>Raw because everything that would make VNC efficient on a network is
 * pure cost here. The bytes cross loopback inside one phone; the expensive
 * part of the old path was Xvnc squeezing every change into JPEG and zlib on
 * the CPU, and the page undoing it in JavaScript. Raw is a copy on Xvnc's side
 * and a texture upload on ours, and the squeezing moves to the phone's video
 * encoder, which exists for exactly that.
 *
 * <p>The pixel format is Xvnc's own whenever it is plain 8-bit channels in a
 * 32-bit word (it is, at depth 24), so Xvnc sends its framebuffer as it holds
 * it with no per-pixel conversion; the channel order is undone for free in the
 * shader ({@link #swizzle}).
 *
 * <p>The cursor comes as a shape (the Cursor pseudo-encoding) and is not drawn
 * into the picture: the computer draws it itself, so the pointer moves at the
 * computer's own speed whatever the video is doing.
 *
 * <p>Shared, always: the phone's own Linux window may be watching the same
 * desktop, and an exclusive client would throw it off.
 *
 * <p>The clipboard is UTF-8 wherever Xvnc speaks TigerVNC's extended
 * clipboard, which it has since 1.10: Xvnc is told there is text and asks for
 * it only when something in the guest pastes, so the computer's latest text is
 * kept here to hand over then ({@link #clipboard}). A server without it gets
 * standard cut text, which is Latin-1.
 *
 * <p>Pure Java, so the stand-in test on a computer runs this very file.
 */
final class LinuxStreamRfb implements Closeable {

    static final int ENC_RAW = 0;
    static final int ENC_LAST_RECT = -224;
    static final int ENC_DESKTOP_SIZE = -223;
    static final int ENC_CURSOR = -239;
    static final int ENC_EXTENDED_DESKTOP_SIZE = -308;
    /** TigerVNC's extended clipboard: UTF-8, deflated, and handed over when asked for. */
    static final int ENC_EXTENDED_CLIPBOARD = 0xC0A1E5CE;

    // Its flags: the formats in the low bits, the actions in the high byte.
    static final int CLIP_TEXT = 1;
    static final int CLIP_CAPS = 1 << 24;
    static final int CLIP_REQUEST = 1 << 25;
    static final int CLIP_PEEK = 1 << 26;
    static final int CLIP_NOTIFY = 1 << 27;
    static final int CLIP_PROVIDE = 1 << 28;

    /**
     * The most Xvnc takes in one clipboard update (its MaxCutText), counted
     * the way it counts: UTF-8, CRLF line ends, and a NUL. The window holds
     * back anything larger (clipboardShare.ts's GUEST_MAX).
     */
    static final int GUEST_MAX = 262144;
    /** The most of the guest's clipboard handed on (clipboardShare.ts's COMPUTER_MAX). */
    static final int COMPUTER_MAX = 4 * 1024 * 1024;
    /** An extended clipboard message larger than any text handed on, deflated, is skipped unread. */
    private static final int MAX_CLIP_MESSAGE = COMPUTER_MAX + 64 * 1024;

    /** Xvnc refused the password — Linux was reinstalled with a new one, say. */
    static final class AuthFailed extends IOException {
        AuthFailed(String why) {
            super(why);
        }
    }

    /** What the reader hands on. Called on the reading thread. */
    interface Handler {
        /** The framebuffer is now {@code w}×{@code h} — at the start, and after every resize. */
        void onResize(int w, int h) throws IOException;

        /**
         * Raw pixels for one rectangle, {@link #bytesPerPixel} each, rows
         * top-down, no padding. {@code pixels} is reused: valid only during
         * the call.
         */
        void onRect(int x, int y, int w, int h, ByteBuffer pixels) throws IOException;

        /** The update that began with the last {@link #onRect} is complete. */
        void onUpdateEnd() throws IOException;

        /** A new pointer shape, as RGBA rows; w or h of 0 is no cursor at all. */
        void onCursor(int hotX, int hotY, int w, int h, byte[] rgba) throws IOException;

        /** The guest's clipboard changed: its text, line ends as the guest has them (LF from the extended clipboard). */
        void onClipboard(String text) throws IOException;
    }

    private final SocketChannel ch;
    /** Small reads come out of this; empty and in read mode to begin with. */
    private final ByteBuffer in = ByteBuffer.allocateDirect(64 * 1024);
    /** One rectangle's pixels; grown to the largest seen. */
    private ByteBuffer pixels = ByteBuffer.allocateDirect(0);
    private final Object writeLock = new Object();

    int width;
    int height;
    final int bytesPerPixel = 4;
    /** Byte index of each channel inside one 4-byte pixel. */
    private int rByte;
    private int gByte;
    private int bByte;
    /** Xvnc's one screen, as its ExtendedDesktopSize said — for SetDesktopSize. */
    private long screenId;
    private long screenFlags;
    /** Xvnc said it takes SetDesktopSize. */
    private volatile boolean canResize;
    /** The desktop's name, as Xvnc gives it. */
    String name = "";

    /** The clipboard's state: written from the computer's side and from Xvnc's. */
    private final Object clipLock = new Object();
    /** Xvnc has answered the encodings, so which clipboard it speaks is known. */
    private boolean clipKnown;
    /** ...and it is the extended one. */
    private boolean extendedClip;
    /** The computer's text as the guest was last given it, kept for Xvnc to ask for; null for none. */
    private String offered;
    /** The computer's text from before Xvnc answered the encodings. */
    private String early;

    private LinuxStreamRfb(SocketChannel ch) {
        this.ch = ch;
        in.flip();
    }

    /**
     * Connect to Xvnc on 127.0.0.1:{@code port} and log in.
     *
     * @throws AuthFailed when Xvnc refuses {@code password}
     */
    static LinuxStreamRfb connect(int port, String password, int timeoutMs) throws IOException {
        SocketChannel ch = SocketChannel.open();
        boolean done = false;
        try {
            ch.socket().connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            ch.socket().setTcpNoDelay(true);
            // Raw at 60 frames a second is a lot of bytes; a deep receive
            // buffer lets Xvnc hand a whole update over and go back to drawing.
            ch.socket().setReceiveBufferSize(4 * 1024 * 1024);
            ch.socket().setSoTimeout(0);
            LinuxStreamRfb rfb = new LinuxStreamRfb(ch);
            rfb.handshake(password);
            done = true;
            return rfb;
        } finally {
            if (!done) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handshake(String password) throws IOException {
        byte[] version = readBytes(12);
        String v = new String(version, StandardCharsets.US_ASCII);
        if (!v.startsWith("RFB ")) throw new IOException("not a VNC server: " + v.trim());
        int minor;
        try {
            minor = Integer.parseInt(v.substring(8, 11));
        } catch (NumberFormatException e) {
            throw new IOException("unreadable RFB version " + v.trim());
        }
        boolean v38 = minor >= 8;
        if (minor < 7) throw new IOException("RFB " + v.trim() + " is too old");
        write(("RFB 003.00" + (v38 ? "8" : "7") + "\n").getBytes(StandardCharsets.US_ASCII));

        int count = u8();
        if (count == 0) throw new IOException("Xvnc refused: " + reason());
        boolean vncAuth = false, none = false;
        for (int i = 0; i < count; i++) {
            int t = u8();
            if (t == 2) vncAuth = true;
            if (t == 1) none = true;
        }
        if (vncAuth) {
            write(new byte[]{2});
            byte[] challenge = readBytes(16);
            write(Des.vncResponse(challenge, password));
        } else if (none) {
            write(new byte[]{1});
        } else {
            throw new IOException("no security type this client speaks");
        }
        if (vncAuth || v38) {
            long result = u32();
            if (result != 0) {
                String why = v38 ? reason() : "refused";
                throw new AuthFailed(why);
            }
        }

        write(new byte[]{1}); // ClientInit: shared
        int w = u16();
        int h = u16();
        byte[] pf = readBytes(16);
        long nameLen = u32();
        name = new String(readBytes((int) Math.min(nameLen, 4096)), StandardCharsets.UTF_8);
        if (nameLen > 4096) skip(nameLen - 4096);
        width = w;
        height = h;
        choosePixelFormat(pf);
        sendEncodings();
        requestUpdate(false);
    }

    /**
     * Xvnc's own format if it is 32-bit true colour with byte-aligned 8-bit
     * channels — no conversion on its side — else ask for R,G,B,X in memory.
     */
    private void choosePixelFormat(byte[] pf) throws IOException {
        int bpp = pf[0] & 0xff;
        boolean bigEndian = pf[2] != 0;
        boolean trueColour = pf[3] != 0;
        int rMax = ((pf[4] & 0xff) << 8) | (pf[5] & 0xff);
        int gMax = ((pf[6] & 0xff) << 8) | (pf[7] & 0xff);
        int bMax = ((pf[8] & 0xff) << 8) | (pf[9] & 0xff);
        int rShift = pf[10] & 0xff, gShift = pf[11] & 0xff, bShift = pf[12] & 0xff;
        boolean plain = bpp == 32 && trueColour && rMax == 255 && gMax == 255 && bMax == 255
                && rShift % 8 == 0 && gShift % 8 == 0 && bShift % 8 == 0
                && rShift <= 24 && gShift <= 24 && bShift <= 24
                && rShift != gShift && gShift != bShift && rShift != bShift;
        if (plain) {
            rByte = bigEndian ? 3 - rShift / 8 : rShift / 8;
            gByte = bigEndian ? 3 - gShift / 8 : gShift / 8;
            bByte = bigEndian ? 3 - bShift / 8 : bShift / 8;
            return;
        }
        ByteBuffer m = ByteBuffer.allocate(20);
        m.put((byte) 0).put(new byte[3]);
        m.put((byte) 32).put((byte) 24).put((byte) 0).put((byte) 1);
        m.putShort((short) 255).putShort((short) 255).putShort((short) 255);
        m.put((byte) 0).put((byte) 8).put((byte) 16).put(new byte[3]);
        write(m.array());
        rByte = 0;
        gByte = 1;
        bByte = 2;
    }

    /**
     * How a GLSL {@code texture2D(...)} of this format's bytes, uploaded as
     * GL_RGBA, reads back as red, green, blue: "bgr" for Xvnc's usual
     * little-endian B,G,R,X.
     */
    String swizzle() {
        String c = "rgba";
        return "" + c.charAt(rByte) + c.charAt(gByte) + c.charAt(bByte);
    }

    private void sendEncodings() throws IOException {
        int[] encodings = {ENC_RAW, ENC_EXTENDED_DESKTOP_SIZE, ENC_DESKTOP_SIZE, ENC_CURSOR, ENC_EXTENDED_CLIPBOARD};
        ByteBuffer m = ByteBuffer.allocate(4 + 4 * encodings.length);
        m.put((byte) 2).put((byte) 0).putShort((short) encodings.length);
        for (int e : encodings) m.putInt(e);
        write(m.array());
    }

    // ── to Xvnc ──

    /** The whole framebuffer; incremental means "when something changes". */
    void requestUpdate(boolean incremental) throws IOException {
        ByteBuffer m = ByteBuffer.allocate(10);
        m.put((byte) 3).put((byte) (incremental ? 1 : 0));
        m.putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
        write(m.array());
    }

    /** RFB's button mask: 1 left, 2 middle, 4 right, 8/16 wheel up/down, 32/64 wheel left/right. */
    void pointer(int x, int y, int buttons) throws IOException {
        ByteBuffer m = ByteBuffer.allocate(6);
        m.put((byte) 5).put((byte) buttons);
        m.putShort((short) clamp(x, width)).putShort((short) clamp(y, height));
        write(m.array());
    }

    void key(int keysym, boolean down) throws IOException {
        ByteBuffer m = ByteBuffer.allocate(8);
        m.put((byte) 4).put((byte) (down ? 1 : 0)).putShort((short) 0).putInt(keysym);
        write(m.array());
    }

    /**
     * The computer's clipboard, for the guest. With the extended clipboard
     * Xvnc is only told there is text, and asks for it when something in the
     * guest pastes ({@link #provide}); without it, it goes at once as standard
     * cut text, Latin-1, where a character it cannot carry becomes '?'. Empty
     * text takes back what this connection gave the guest. Text Xvnc would
     * refuse — over its MaxCutText — is not offered at all.
     */
    void clipboard(String text) throws IOException {
        String lf = text.replace("\r\n", "\n").replace('\r', '\n');
        synchronized (clipLock) {
            // Asked before Xvnc's answer to the encodings, which says how.
            if (!clipKnown) {
                early = lf;
                return;
            }
            offer(lf);
        }
    }

    /** Under {@link #clipLock}, once it is known which clipboard Xvnc speaks. */
    private void offer(String lf) throws IOException {
        if (lf.isEmpty()) {
            offered = null;
            // No formats: nothing on offer, and Xvnc lets go of what was.
            if (extendedClip) writeClipboard(CLIP_NOTIFY, null);
            else cutText(new byte[0]);
            return;
        }
        if (extendedClip) {
            if (guestSize(lf) > GUEST_MAX) return;
            offered = lf;
            writeClipboard(CLIP_NOTIFY | CLIP_TEXT, null);
        } else {
            byte[] b = lf.getBytes(StandardCharsets.ISO_8859_1);
            if (b.length > GUEST_MAX) return;
            offered = lf;
            cutText(b);
        }
    }

    /** The size Xvnc counts a text at: UTF-8, CRLF line ends, and a NUL. */
    static int guestSize(String lf) {
        return utf8Crlf(lf).length + 1;
    }

    private static byte[] utf8Crlf(String lf) {
        return lf.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Standard cut text: Latin-1, LF line ends. */
    private void cutText(byte[] latin1) throws IOException {
        ByteBuffer m = ByteBuffer.allocate(8 + latin1.length);
        m.put((byte) 6).put(new byte[3]).putInt(latin1.length).put(latin1);
        write(m.array());
    }

    /** ClientCutText in its extended form: a negative length, then the flags and the rest. */
    private void writeClipboard(int flags, byte[] rest) throws IOException {
        int n = 4 + (rest == null ? 0 : rest.length);
        ByteBuffer m = ByteBuffer.allocate(8 + n);
        m.put((byte) 6).put(new byte[3]).putInt(-n).putInt(flags);
        if (rest != null) m.put(rest);
        write(m.array());
    }

    /**
     * Xvnc's capabilities, its answer to the encodings: ours in return — the
     * same actions, text only, and none of it unasked (a size of 0), so Xvnc
     * says when the guest has text and this asks for it — and then whatever
     * the computer gave before they were known.
     */
    private void serverCaps(int flags) throws IOException {
        int needed = CLIP_TEXT | CLIP_NOTIFY | CLIP_REQUEST | CLIP_PROVIDE;
        if ((flags & needed) != needed) return;
        synchronized (clipLock) {
            boolean was = extendedClip;
            extendedClip = true;
            if (!was) writeClipboard(CLIP_CAPS | CLIP_REQUEST | CLIP_PEEK | CLIP_NOTIFY | CLIP_PROVIDE | CLIP_TEXT, new byte[4]);
            if (!clipKnown) known();
            // A server that answered after its first picture was taken for one
            // without it: what went as Latin-1 is offered again, whole.
            else if (!was && offered != null) offer(offered);
        }
    }

    /** Under {@link #clipLock}: which clipboard Xvnc speaks is settled. Offer what was waiting. */
    private void known() throws IOException {
        clipKnown = true;
        String e = early;
        early = null;
        if (e != null) offer(e);
    }

    /** Xvnc asks for the text: something in the guest is pasting it. */
    private void provide() throws IOException {
        synchronized (clipLock) {
            // Nothing on offer is a request that crossed a take-back: an empty
            // text answers it, rather than leave the paste waiting.
            byte[] text = utf8Crlf(offered == null ? "" : offered);
            ByteBuffer raw = ByteBuffer.allocate(4 + text.length + 1);
            raw.putInt(text.length + 1).put(text).put((byte) 0);
            writeClipboard(CLIP_PROVIDE | CLIP_TEXT, deflate(raw.array()));
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        try {
            d.setInput(data);
            d.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length / 3));
            byte[] buf = new byte[16 * 1024];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    boolean canResize() {
        return canResize;
    }

    /** Ask Xvnc to become {@code w}×{@code h}; the answer arrives as an ExtendedDesktopSize. */
    void setDesktopSize(int w, int h) throws IOException {
        if (!canResize) return;
        ByteBuffer m = ByteBuffer.allocate(24);
        m.put((byte) 251).put((byte) 0).putShort((short) w).putShort((short) h);
        m.put((byte) 1).put((byte) 0);
        m.putInt((int) screenId).putShort((short) 0).putShort((short) 0)
                .putShort((short) w).putShort((short) h).putInt((int) screenFlags);
        write(m.array());
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(v, Math.max(0, size - 1)));
    }

    private void write(byte[] b) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(b);
        synchronized (writeLock) {
            while (buf.hasRemaining()) ch.write(buf);
        }
    }

    // ── from Xvnc ──

    /** Read one server message and hand on what is in it. Blocks. */
    void readMessage(Handler h) throws IOException {
        int type = u8();
        switch (type) {
            case 0:
                framebufferUpdate(h);
                break;
            case 1: { // SetColourMapEntries: never for true colour, skipped if it comes
                u8();
                u16();
                int n = u16();
                skip(n * 6L);
                break;
            }
            case 2: // Bell
                break;
            case 3: {
                skip(3);
                int len = s32();
                if (len < 0) {
                    extendedClipboard(h, -(long) len);
                } else if (len > COMPUTER_MAX) {
                    skip(len);
                } else {
                    String text = new String(readBytes(len), StandardCharsets.ISO_8859_1);
                    h.onClipboard(text);
                }
                break;
            }
            default:
                throw new IOException("unknown message " + type + " from Xvnc");
        }
    }

    /** One extended clipboard message, {@code n} bytes: its flags, then what the action carries. */
    private void extendedClipboard(Handler h, long n) throws IOException {
        if (n < 4 || n > MAX_CLIP_MESSAGE) {
            skip(n);
            return;
        }
        int flags = s32();
        byte[] rest = readBytes((int) n - 4);
        // One action a message; caps may name several, as what the sender takes.
        if ((flags & CLIP_CAPS) != 0) {
            serverCaps(flags);
        } else if ((flags & CLIP_REQUEST) != 0) {
            if ((flags & CLIP_TEXT) != 0) provide();
        } else if ((flags & CLIP_PEEK) != 0) {
            synchronized (clipLock) {
                writeClipboard(CLIP_NOTIFY | (offered != null ? CLIP_TEXT : 0), null);
            }
        } else if ((flags & CLIP_NOTIFY) != 0) {
            // The guest has new text: asked for at once, as TigerVNC's own
            // viewer does. Whether it reaches the computer is the window's call.
            if ((flags & CLIP_TEXT) != 0) writeClipboard(CLIP_REQUEST | CLIP_TEXT, null);
        } else if ((flags & CLIP_PROVIDE) != 0) {
            String text = inflateText(flags, rest);
            if (text != null) h.onClipboard(text);
        }
    }

    /**
     * The text in a provide: the first format's length, its bytes, the NUL
     * taken off and line ends made LF. Null when it holds no text, is larger
     * than is handed on, or is not what it says.
     */
    static String inflateText(int flags, byte[] deflated) {
        if ((flags & CLIP_TEXT) == 0) return null;
        Inflater inf = new Inflater();
        try {
            inf.setInput(deflated);
            byte[] head = new byte[4];
            if (!inflateFully(inf, head)) return null;
            long len = ((head[0] & 0xffL) << 24) | ((head[1] & 0xffL) << 16) | ((head[2] & 0xffL) << 8) | (head[3] & 0xffL);
            if (len > COMPUTER_MAX) return null;
            byte[] text = new byte[(int) len];
            if (!inflateFully(inf, text)) return null;
            int end = text.length;
            while (end > 0 && text[end - 1] == 0) end--;
            return new String(text, 0, end, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /** Fill {@code out} from {@code inf}; false if the stream ends first. TigerVNC sync-flushes, never finishes. */
    private static boolean inflateFully(Inflater inf, byte[] out) throws DataFormatException {
        int off = 0;
        while (off < out.length) {
            int got = inf.inflate(out, off, out.length - off);
            if (got == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) return false;
            off += got;
        }
        return true;
    }

    private void framebufferUpdate(Handler h) throws IOException {
        // Xvnc answers the encodings ahead of any picture: no capabilities by
        // now is a server without the extended clipboard.
        synchronized (clipLock) {
            if (!clipKnown) known();
        }
        u8();
        int n = u16();
        for (int i = 0; i < n; i++) {
            int x = u16(), y = u16(), w = u16(), hh = u16();
            int enc = s32();
            switch (enc) {
                case ENC_RAW: {
                    int len = w * hh * bytesPerPixel;
                    ByteBuffer px = readPixels(len);
                    h.onRect(x, y, w, hh, px);
                    break;
                }
                case ENC_DESKTOP_SIZE:
                    resized(h, w, hh);
                    break;
                case ENC_EXTENDED_DESKTOP_SIZE: {
                    // x is why (0 Xvnc itself, 1 this client, 2 another), y the
                    // status of a request; w×h is the size either way.
                    int screens = u8();
                    skip(3);
                    for (int s = 0; s < screens; s++) {
                        long id = u32();
                        u16();
                        u16();
                        u16();
                        u16();
                        long flags = u32();
                        if (s == 0) {
                            screenId = id;
                            screenFlags = flags;
                        }
                    }
                    canResize = true;
                    resized(h, w, hh);
                    break;
                }
                case ENC_CURSOR: {
                    byte[] px = readBytes(w * hh * bytesPerPixel);
                    byte[] mask = readBytes(((w + 7) / 8) * hh);
                    h.onCursor(x, y, w, hh, cursorRgba(w, hh, px, mask));
                    break;
                }
                case ENC_LAST_RECT:
                    h.onUpdateEnd();
                    return;
                default:
                    throw new IOException("Xvnc sent encoding " + enc + ", which was never asked for");
            }
        }
        h.onUpdateEnd();
    }

    /** A size report — a change, or the same size again (a refused request, a layout note). */
    private void resized(Handler h, int w, int hh) throws IOException {
        width = w;
        height = hh;
        h.onResize(w, hh);
    }

    private byte[] cursorRgba(int w, int h, byte[] px, byte[] mask) {
        byte[] out = new byte[w * h * 4];
        int stride = (w + 7) / 8;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = (y * w + x) * bytesPerPixel;
                int o = (y * w + x) * 4;
                boolean on = (mask[y * stride + x / 8] & (0x80 >> (x & 7))) != 0;
                out[o] = px[p + rByte];
                out[o + 1] = px[p + gByte];
                out[o + 2] = px[p + bByte];
                out[o + 3] = (byte) (on ? 255 : 0);
            }
        }
        return out;
    }

    // ── reading ──

    /** At least {@code n} bytes in {@link #in}, n no more than its capacity. */
    private void fill(int n) throws IOException {
        if (in.remaining() >= n) return;
        in.compact();
        try {
            while (in.position() < n) {
                if (ch.read(in) < 0) throw new EOFException("Xvnc closed the connection");
            }
        } finally {
            in.flip();
        }
    }

    private int u8() throws IOException {
        fill(1);
        return in.get() & 0xff;
    }

    private int u16() throws IOException {
        fill(2);
        return in.getShort() & 0xffff;
    }

    private int s32() throws IOException {
        fill(4);
        return in.getInt();
    }

    private long u32() throws IOException {
        return s32() & 0xffffffffL;
    }

    private String reason() throws IOException {
        int len = (int) Math.min(u32(), 4096);
        return new String(readBytes(len), StandardCharsets.UTF_8);
    }

    private void skip(long n) throws IOException {
        while (n > 0) {
            int step = (int) Math.min(n, in.capacity());
            fill(step);
            in.position(in.position() + step);
            n -= step;
        }
    }

    private byte[] readBytes(int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int step = Math.min(n - off, in.capacity());
            fill(step);
            in.get(out, off, step);
            off += step;
        }
        return out;
    }

    /** {@code len} bytes straight into the pixel buffer: what was read ahead first, then the socket. */
    private ByteBuffer readPixels(int len) throws IOException {
        if (pixels.capacity() < len) {
            // Rounded up so a desktop growing a little does not reallocate every update.
            pixels = ByteBuffer.allocateDirect(Math.max(len, pixels.capacity() * 2));
        }
        ByteBuffer px = pixels;
        px.clear();
        px.limit(len);
        int take = Math.min(in.remaining(), len);
        if (take > 0) {
            int limit = in.limit();
            in.limit(in.position() + take);
            px.put(in);
            in.limit(limit);
        }
        while (px.hasRemaining()) {
            if (ch.read(px) < 0) throw new EOFException("Xvnc closed the connection");
        }
        px.flip();
        return px;
    }

    @Override
    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * DES, encryption only, which is all VncAuth needs: the 16-byte challenge
     * encrypted under the password. Here rather than through javax.crypto
     * because single DES is gone from some Android builds' providers, and a
     * login that depends on which provider a phone ships is not one to rely on.
     */
    static final class Des {
        private static final int[] IP = {
                58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
                62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
                57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
                61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7};
        private static final int[] FP = {
                40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
                38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
                36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
                34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25};
        private static final int[] E = {
                32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11, 12, 13,
                12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21, 22, 23, 24, 25,
                24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1};
        private static final int[] P = {
                16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
                2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25};
        private static final int[] PC1 = {
                57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
                10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
                63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
                14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4};
        private static final int[] PC2 = {
                14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10,
                23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
                41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48,
                44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32};
        private static final int[] SHIFTS = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
        private static final int[][] S = {
                {14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13},
                {15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                        3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
                        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9},
                {10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12},
                {7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                        3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14},
                {2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3},
                {12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13},
                {4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12},
                {13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11}};

        private Des() {
        }

        /** Bits of {@code in} (the low {@code width} bits, MSB first) picked by 1-based {@code table}. */
        private static long permute(long in, int[] table, int width) {
            long out = 0;
            for (int t : table) out = (out << 1) | ((in >>> (width - t)) & 1L);
            return out;
        }

        static byte[] encrypt(byte[] key, byte[] block) {
            long k = toLong(key);
            long cd = permute(k, PC1, 64);
            long c = (cd >>> 28) & 0xFFFFFFFL;
            long d = cd & 0xFFFFFFFL;
            long[] sub = new long[16];
            for (int i = 0; i < 16; i++) {
                for (int s = 0; s < SHIFTS[i]; s++) {
                    c = ((c << 1) | (c >>> 27)) & 0xFFFFFFFL;
                    d = ((d << 1) | (d >>> 27)) & 0xFFFFFFFL;
                }
                sub[i] = permute((c << 28) | d, PC2, 56);
            }
            long b = permute(toLong(block), IP, 64);
            long l = (b >>> 32) & 0xFFFFFFFFL;
            long r = b & 0xFFFFFFFFL;
            for (int i = 0; i < 16; i++) {
                long next = l ^ f(r, sub[i]);
                l = r;
                r = next;
            }
            long out = permute((r << 32) | l, FP, 64);
            byte[] bytes = new byte[8];
            for (int i = 0; i < 8; i++) bytes[i] = (byte) (out >>> (56 - 8 * i));
            return bytes;
        }

        private static long f(long r, long k) {
            long x = permute(r, E, 32) ^ k;
            long out = 0;
            for (int i = 0; i < 8; i++) {
                int six = (int) ((x >>> (42 - 6 * i)) & 0x3F);
                int row = ((six & 0x20) >>> 4) | (six & 1);
                int col = (six >>> 1) & 0xF;
                out = (out << 4) | S[i][row * 16 + col];
            }
            return permute(out, P, 32);
        }

        private static long toLong(byte[] b) {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (b[i] & 0xffL);
            return v;
        }

        /**
         * VncAuth's answer. The key is the password's first eight bytes, zero
         * padded, each byte with its bits in reverse order — VNC's own quirk,
         * which every server and viewer reproduces.
         */
        static byte[] vncResponse(byte[] challenge, String password) {
            byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
            byte[] key = new byte[8];
            for (int i = 0; i < 8 && i < pw.length; i++) key[i] = (byte) (Integer.reverse(pw[i] & 0xff) >>> 24);
            byte[] out = new byte[16];
            for (int half = 0; half < 2; half++) {
                byte[] block = new byte[8];
                System.arraycopy(challenge, half * 8, block, 0, 8);
                System.arraycopy(encrypt(key, block), 0, out, half * 8, 8);
            }
            return out;
        }
    }
}
