package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * The X11 wire protocol, as much of it as {@link LinuxWm} needs, and nothing
 * above it.
 *
 * <p>Linux apps get windows of their own on the desktop by being clients of a
 * second X display in the guest ({@code :2}, linux-win.sh) whose window
 * manager is this app: it is told about every window before it maps, reads
 * each one's pixels out of the Composite extension's off-screen pixmap, and
 * puts input back with XTEST. Xlib and XCB are C libraries for the guest's
 * side of the fence; this end is Java in the launcher, so it speaks the
 * protocol itself — a few dozen requests, all of them fixed layouts.
 *
 * <p>Byte order is ours to choose at setup, and is little-endian, which is
 * what every phone and every Xvnc build uses for its pixels anyway.
 *
 * <p>Threads: requests may be sent from any thread; one reader thread
 * ({@link #start}) decodes everything the server sends, completes the reply
 * each request is waiting for, and hands events and unclaimed errors to the
 * {@link Listener} — which must never wait on a reply itself, or the thread
 * that would deliver it is the one waiting.
 *
 * <p>Pure Java, so a computer can run it against the guest's real Xvnc
 * over {@code adb forward} — how it was checked; see doc/linux-windows.md.
 */
final class X11 implements Closeable {

    // ── core requests ──
    static final int CREATE_WINDOW = 1;
    static final int CHANGE_WINDOW_ATTRIBUTES = 2;
    static final int GET_WINDOW_ATTRIBUTES = 3;
    static final int DESTROY_WINDOW = 4;
    static final int MAP_WINDOW = 8;
    static final int UNMAP_WINDOW = 10;
    static final int CONFIGURE_WINDOW = 12;
    static final int GET_GEOMETRY = 14;
    static final int QUERY_TREE = 15;
    static final int INTERN_ATOM = 16;
    static final int CHANGE_PROPERTY = 18;
    static final int DELETE_PROPERTY = 19;
    static final int GET_PROPERTY = 20;
    static final int SET_SELECTION_OWNER = 22;
    static final int CONVERT_SELECTION = 24;
    static final int SEND_EVENT = 25;
    static final int SET_INPUT_FOCUS = 42;
    static final int GET_INPUT_FOCUS = 43;
    static final int GET_IMAGE = 73;
    static final int QUERY_EXTENSION = 98;
    static final int CHANGE_KEYBOARD_MAPPING = 100;
    static final int GET_KEYBOARD_MAPPING = 101;
    static final int KILL_CLIENT = 113;
    static final int GET_MODIFIER_MAPPING = 119;

    // ── core events ──
    static final int KEY_PRESS = 2;
    static final int KEY_RELEASE = 3;
    static final int BUTTON_PRESS = 4;
    static final int BUTTON_RELEASE = 5;
    static final int MOTION_NOTIFY = 6;
    static final int FOCUS_IN = 9;
    static final int FOCUS_OUT = 10;
    static final int CREATE_NOTIFY = 16;
    static final int DESTROY_NOTIFY = 17;
    static final int UNMAP_NOTIFY = 18;
    static final int MAP_NOTIFY = 19;
    static final int MAP_REQUEST = 20;
    static final int REPARENT_NOTIFY = 21;
    static final int CONFIGURE_NOTIFY = 22;
    static final int CONFIGURE_REQUEST = 23;
    static final int PROPERTY_NOTIFY = 28;
    static final int SELECTION_CLEAR = 29;
    static final int SELECTION_REQUEST = 30;
    static final int SELECTION_NOTIFY = 31;
    static final int CLIENT_MESSAGE = 33;
    static final int MAPPING_NOTIFY = 34;
    static final int GENERIC_EVENT = 35;

    // ── event masks ──
    static final int KEY_PRESS_MASK = 0x1;
    static final int STRUCTURE_NOTIFY_MASK = 0x20000;
    static final int SUBSTRUCTURE_NOTIFY_MASK = 0x80000;
    static final int SUBSTRUCTURE_REDIRECT_MASK = 0x100000;
    static final int FOCUS_CHANGE_MASK = 0x200000;
    static final int PROPERTY_CHANGE_MASK = 0x400000;

    // ── window attribute value-mask bits ──
    static final int CW_BACK_PIXEL = 0x2;
    static final int CW_OVERRIDE_REDIRECT = 0x200;
    static final int CW_EVENT_MASK = 0x800;

    // ── ConfigureWindow value-mask bits ──
    static final int CONFIG_X = 0x1;
    static final int CONFIG_Y = 0x2;
    static final int CONFIG_WIDTH = 0x4;
    static final int CONFIG_HEIGHT = 0x8;
    static final int CONFIG_BORDER = 0x10;
    static final int CONFIG_SIBLING = 0x20;
    static final int CONFIG_STACK_MODE = 0x40;
    static final int STACK_ABOVE = 0;

    // ── properties ──
    static final int PROP_REPLACE = 0;
    static final int ATOM_ANY = 0;
    static final int ATOM_PRIMARY = 1;
    static final int ATOM_ATOM = 4;
    static final int ATOM_CARDINAL = 6;
    static final int ATOM_STRING = 31;
    static final int ATOM_WINDOW = 33;
    static final int ATOM_WM_NAME = 39;
    static final int ATOM_WM_NORMAL_HINTS = 40;
    static final int ATOM_WM_HINTS = 35;
    static final int ATOM_WM_CLASS = 67;
    static final int ATOM_WM_TRANSIENT_FOR = 68;

    // ── errors worth naming ──
    static final int BAD_WINDOW = 3;
    static final int BAD_MATCH = 8;
    static final int BAD_DRAWABLE = 9;
    static final int BAD_ACCESS = 10;

    static final int CURRENT_TIME = 0;

    /** A server-side failure: the error packet the server sent for one request. */
    static final class XError extends IOException {
        final int code;
        final long seq;
        final int badValue;
        final int major;
        final int minor;

        XError(int code, long seq, int badValue, int major, int minor) {
            super("X error " + code + " (request " + major + "." + minor + ", value 0x"
                    + Integer.toHexString(badValue) + ", seq " + seq + ")");
            this.code = code;
            this.seq = seq;
            this.badValue = badValue;
            this.major = major;
            this.minor = minor;
        }
    }

    /** One event, 32 bytes (more for a GenericEvent), little-endian. */
    static final class Event {
        /** The event's code with the SendEvent bit taken off. */
        final int code;
        /** It came from another client's SendEvent, not from the server. */
        final boolean synthetic;
        final ByteBuffer b;

        Event(byte[] raw) {
            code = raw[0] & 0x7f;
            synthetic = (raw[0] & 0x80) != 0;
            b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        }

        int u8(int at) {
            return b.get(at) & 0xff;
        }

        int u16(int at) {
            return b.getShort(at) & 0xffff;
        }

        int s16(int at) {
            return b.getShort(at);
        }

        int u32(int at) {
            return b.getInt(at);
        }
    }

    /** A reply: its 32-byte head and whatever followed it. */
    static final class Reply {
        final ByteBuffer head;
        /** The bytes after the head; for an image read into the caller's own array, that array. */
        final byte[] data;
        /** How many of {@link #data} are the reply's. */
        final int length;

        Reply(byte[] head, byte[] data, int length) {
            this.head = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            this.data = data;
            this.length = length;
        }

        // Offsets are the protocol's, from the start of the reply: past the
        // 32-byte head they read the data that followed it.

        int u8(int at) {
            return at < 32 ? head.get(at) & 0xff : data[at - 32] & 0xff;
        }

        int u16(int at) {
            return at < 32 ? head.getShort(at) & 0xffff : u8(at) | u8(at + 1) << 8;
        }

        int s16(int at) {
            return (short) u16(at);
        }

        int u32(int at) {
            return at < 32 ? head.getInt(at) : dataU32(at - 32);
        }

        /** A 32-bit value at {@code at} in the data that followed the head. */
        int dataU32(int at) {
            return (data[at] & 0xff) | (data[at + 1] & 0xff) << 8 | (data[at + 2] & 0xff) << 16
                    | (data[at + 3] & 0xff) << 24;
        }
    }

    /** What the reader thread hands on. */
    interface Listener {
        void event(Event e);

        /** An error for a request nobody was waiting on — usually a window that went away first. */
        void error(XError e);

        /** The connection is gone; nothing more will come. */
        void closed(Throwable why);
    }

    /** Passing a file descriptor with the next write: what MIT-SHM's AttachFd needs, and only a Unix socket can. */
    interface FdPassing {
        void sendWithNextWrite(FileDescriptor[] fds) throws IOException;
    }

    /** An extension as QueryExtension found it. */
    static final class Extension {
        final String name;
        final boolean present;
        final int major;
        final int firstEvent;
        final int firstError;

        Extension(String name, boolean present, int major, int firstEvent, int firstError) {
            this.name = name;
            this.present = present;
            this.major = major;
            this.firstEvent = firstEvent;
            this.firstError = firstError;
        }
    }

    // ── what the server said at setup ──
    final int root;
    final int rootVisual;
    final int rootDepth;
    final int screenWidth;
    final int screenHeight;
    final int minKeycode;
    final int maxKeycode;
    /** In 4-byte units; requests are never longer than this. BIG-REQUESTS raises it. */
    volatile int maxRequestLength;
    final String vendor;
    private final int idBase;
    private final int idMask;
    private int idNext;

    private final InputStream in;
    private final OutputStream out;
    private final Closeable sock;
    private final FdPassing fds;
    private final Object writeLock = new Object();
    /**
     * Requests sent, which is also the sequence number of the last one.
     * Written under {@link #writeLock}; read without it by the reader, which
     * must never wait for a writer: a writer blocked on a full socket is
     * waiting for the server, and the server for this reader.
     */
    private volatile long sent;
    /** Nested {@link #batch} depth: while above zero, writes wait for the outermost to end. */
    private int batching;
    private final byte[] scratch = new byte[256];

    /** Requests waiting for a reply, oldest first. Guarded by itself. */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** The newest sequence number the server has said it got to. Reader thread. */
    private volatile long seen;
    private volatile boolean closed;
    private volatile Throwable closedWhy;
    private Thread reader;
    private Listener listener;

    private X11(InputStream in, OutputStream out, Closeable sock, FdPassing fds, ByteBuffer setup) {
        this.in = in;
        this.out = new BufferedOutputStream(out, 64 * 1024);
        this.sock = sock;
        this.fds = fds;
        // Success: 8 bytes already read; `setup` is the additional data.
        idBase = setup.getInt(4);
        idMask = setup.getInt(8);
        int vendorLen = setup.getShort(16) & 0xffff;
        maxRequestLength = setup.getShort(18) & 0xffff;
        int formats = setup.get(21) & 0xff;
        minKeycode = setup.get(26) & 0xff;
        maxKeycode = setup.get(27) & 0xff;
        byte[] v = new byte[vendorLen];
        setup.position(32);
        setup.get(v);
        vendor = new String(v, StandardCharsets.ISO_8859_1);
        int screen = 32 + pad4(vendorLen) + 8 * formats;
        root = setup.getInt(screen);
        screenWidth = setup.getShort(screen + 20) & 0xffff;
        screenHeight = setup.getShort(screen + 22) & 0xffff;
        rootVisual = setup.getInt(screen + 32);
        rootDepth = setup.get(screen + 38) & 0xff;
    }

    /**
     * Set up a connection on a socket that is already open: the setup
     * exchange, with an MIT-MAGIC-COOKIE-1 when {@code cookie} is not null.
     */
    static X11 open(InputStream in, OutputStream out, Closeable sock, FdPassing fds, byte[] cookie)
            throws IOException {
        String authName = cookie != null ? "MIT-MAGIC-COOKIE-1" : "";
        byte[] name = authName.getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = cookie != null ? cookie : new byte[0];
        ByteBuffer b = ByteBuffer.allocate(12 + pad4(name.length) + pad4(data.length)).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'l').put((byte) 0).putShort((short) 11).putShort((short) 0)
                .putShort((short) name.length).putShort((short) data.length).putShort((short) 0);
        b.put(name);
        b.position(12 + pad4(name.length));
        b.put(data);
        out.write(b.array());
        out.flush();

        byte[] head = new byte[8];
        readFully(in, head, 0, 8);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int extra = (h.getShort(6) & 0xffff) * 4;
        byte[] rest = new byte[extra];
        readFully(in, rest, 0, extra);
        if (head[0] != 1) {
            int reasonLen = head[1] & 0xff;
            String reason = new String(rest, 0, Math.min(reasonLen, rest.length), StandardCharsets.ISO_8859_1);
            throw new IOException(head[0] == 0 ? "X refused the connection: " + reason.trim()
                    : "X wants more authentication than a cookie");
        }
        // Re-base so offsets match the protocol's: the 8 bytes already read come first.
        ByteBuffer setup = ByteBuffer.allocate(8 + extra).order(ByteOrder.LITTLE_ENDIAN);
        setup.put(head).put(rest);
        setup.position(0);
        ByteBuffer additional = ByteBuffer.wrap(setup.array(), 8, extra).slice().order(ByteOrder.LITTLE_ENDIAN);
        return new X11(in, out, sock, fds, additional);
    }

    /** Start reading. Events, unclaimed errors and the end all go to {@code l}. */
    void start(Listener l) {
        listener = l;
        reader = new Thread(this::readLoop, "x11-read");
        reader.setDaemon(true);
        reader.start();
    }

    boolean isClosed() {
        return closed;
    }

    /** A fresh resource id for this client. */
    synchronized int newId() {
        int id = idBase | (idNext & idMask);
        idNext++;
        return id;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            sock.close();
        } catch (IOException ignored) {
        }
        failPending(new IOException("connection closed"));
    }

    // ── sending ──

    /**
     * Requests sent inside {@code r} go out in one write: a frame's worth of
     * image requests, or a raise and the click that needs it, so nothing waits
     * on a flush in between. Nesting is fine; the outermost one flushes.
     */
    void batch(IoRunnable r) throws IOException {
        synchronized (writeLock) {
            batching++;
            try {
                r.run();
            } finally {
                if (--batching == 0 && !closed) {
                    try {
                        out.flush();
                    } catch (IOException e) {
                        lost(e);
                        throw e;
                    }
                }
            }
        }
    }

    interface IoRunnable {
        void run() throws IOException;
    }

    /** One request as built; returns its sequence number. */
    private long send(byte[] req, int len, Pending wait) throws IOException {
        if (closed) throw new IOException("connection closed", closedWhy);
        if ((len & 3) != 0) throw new IllegalArgumentException("request not padded: " + len);
        if (len / 4 > maxRequestLength) throw new IOException("request too long: " + len);
        synchronized (writeLock) {
            long seq = ++sent;
            if (wait != null) {
                wait.seq = seq;
                synchronized (pending) {
                    pending.addLast(wait);
                }
            }
            try {
                if (len / 4 > 0xffff) {
                    // BIG-REQUESTS: a zero length, then the real one in a word
                    // of its own that counts itself too.
                    byte[] big = new byte[8];
                    big[0] = req[0];
                    big[1] = req[1];
                    ByteBuffer.wrap(big).order(ByteOrder.LITTLE_ENDIAN).putInt(4, len / 4 + 1);
                    out.write(big, 0, 8);
                    out.write(req, 4, len - 4);
                } else {
                    out.write(req, 0, len);
                }
                if (batching == 0) out.flush();
            } catch (IOException e) {
                lost(e);
                throw e;
            }
            return seq;
        }
    }

    /** A request builder over a reusable array: header first, then the body. */
    private final class Req {
        final byte[] a;
        final ByteBuffer b;

        Req(int opcode, int data, int lengthBytes) {
            a = lengthBytes <= scratch.length ? scratch : new byte[lengthBytes];
            b = ByteBuffer.wrap(a, 0, lengthBytes).order(ByteOrder.LITTLE_ENDIAN);
            b.put((byte) opcode).put((byte) data).putShort((short) (lengthBytes / 4));
        }
    }

    // The scratch array is shared, so everything that builds into it holds the
    // write lock from building to sending.

    private long sendVoid(int opcode, int data, int[] words) throws IOException {
        synchronized (writeLock) {
            Req r = new Req(opcode, data, 4 + 4 * words.length);
            for (int w : words) r.b.putInt(w);
            return send(r.a, r.b.position(), null);
        }
    }

    private Pending sendReply(int opcode, int data, int[] words, byte[] sink) throws IOException {
        Pending p = new Pending(sink);
        synchronized (writeLock) {
            Req r = new Req(opcode, data, 4 + 4 * words.length);
            for (int w : words) r.b.putInt(w);
            send(r.a, r.b.position(), p);
        }
        return p;
    }

    // ── receiving ──

    /** A request that expects a reply. */
    final class Pending {
        long seq;
        /** Where an image's bytes go, so a frame does not allocate. May be null. */
        final byte[] sink;
        private Reply reply;
        private IOException error;
        private boolean done;

        Pending(byte[] sink) {
            this.sink = sink;
        }

        synchronized void complete(Reply r) {
            reply = r;
            done = true;
            notifyAll();
        }

        synchronized void fail(IOException e) {
            error = e;
            done = true;
            notifyAll();
        }

        /**
         * The reply, waiting up to ten seconds for it. Flushes first: inside
         * a {@link #batch} the request may not have left yet, and waiting for
         * the answer to a request still in our own buffer is waiting forever.
         */
        Reply get() throws IOException {
            flushOut();
            synchronized (this) {
                return await();
            }
        }

        private Reply await() throws IOException {
            long until = System.nanoTime() + 10_000_000_000L;
            while (!done) {
                long left = (until - System.nanoTime()) / 1_000_000;
                if (left <= 0) throw new IOException("no reply in time (seq " + seq + ")");
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            if (error != null) throw error;
            return reply;
        }
    }

    private void readLoop() {
        byte[] head = new byte[32];
        try {
            while (!closed) {
                readFully(in, head, 0, 32);
                int type = head[0] & 0xff;
                long seq = fullSeq(u16(head, 2));
                if (type == 0) {
                    XError e = new XError(head[1] & 0xff, seq, u32(head, 4), head[10] & 0xff, u16(head, 8));
                    Pending p = claim(seq);
                    if (p != null) {
                        p.fail(e);
                    } else if (listener != null) {
                        listener.error(e);
                    }
                } else if (type == 1) {
                    long extra = (u32(head, 4) & 0xffffffffL) * 4;
                    if (extra > Integer.MAX_VALUE - 64) throw new IOException("reply too long: " + extra);
                    Pending p = claim(seq);
                    int n = (int) extra;
                    byte[] data;
                    if (p != null && p.sink != null && p.sink.length >= n) {
                        data = p.sink;
                    } else {
                        data = new byte[n];
                    }
                    readFully(in, data, 0, n);
                    if (p != null) p.complete(new Reply(head.clone(), data, n));
                } else {
                    byte[] raw;
                    if ((type & 0x7f) == GENERIC_EVENT) {
                        int extra = (int) ((u32(head, 4) & 0xffffffffL) * 4);
                        raw = new byte[32 + extra];
                        System.arraycopy(head, 0, raw, 0, 32);
                        readFully(in, raw, 32, extra);
                    } else {
                        raw = head.clone();
                    }
                    if (listener != null) listener.event(new Event(raw));
                }
            }
        } catch (Throwable t) {
            lost(t);
        }
    }

    /**
     * The full sequence number for the low 16 bits the server sent: the
     * newest request sent whose number ends that way. Never more than 65535
     * requests are left unanswered (see {@link #sync}), so it is never
     * ambiguous.
     */
    private long fullSeq(int seq16) {
        long last = sent;
        long full = last - ((last - seq16) & 0xffff);
        if (full > seen) seen = full;
        return full;
    }

    /** The waiting request this reply or error is for, if any — taking it off the queue. */
    private Pending claim(long seq) {
        synchronized (pending) {
            while (!pending.isEmpty()) {
                Pending p = pending.peekFirst();
                if (p.seq == seq) return pending.pollFirst();
                if (p.seq > seq) return null; // an error for a request that wanted no reply
                // Older than this, and never answered: cannot happen on a sane
                // server; fail it rather than leave its caller hanging.
                pending.pollFirst().fail(new IOException("no reply for seq " + p.seq));
            }
            return null;
        }
    }

    private void flushOut() throws IOException {
        synchronized (writeLock) {
            if (closed) return;
            try {
                out.flush();
            } catch (IOException e) {
                lost(e);
                throw e;
            }
        }
    }

    private void lost(Throwable why) {
        boolean first = !closed;
        closed = true;
        closedWhy = why;
        try {
            sock.close();
        } catch (IOException ignored) {
        }
        failPending(why instanceof IOException ? (IOException) why : new IOException(why));
        if (first && listener != null) listener.closed(why);
    }

    private void failPending(IOException e) {
        synchronized (pending) {
            while (!pending.isEmpty()) pending.pollFirst().fail(e);
        }
    }

    /**
     * A round trip: once it returns, the server has done everything sent
     * before it. Also what keeps the 16-bit sequence numbers unambiguous.
     */
    void sync() throws IOException {
        sendReply(GET_INPUT_FOCUS, 0, new int[0], null).get();
    }

    /** {@link #sync} if this many requests have gone out without one. */
    void syncIfFarAhead() throws IOException {
        long ahead;
        synchronized (writeLock) {
            ahead = sent - seen;
        }
        if (ahead > 30_000) sync();
    }

    // ── core requests ──

    void createWindow(int wid, int parent, int x, int y, int w, int h, int cls, int mask, int[] values)
            throws IOException {
        synchronized (writeLock) {
            Req r = new Req(CREATE_WINDOW, 0, 32 + 4 * values.length);
            r.b.putInt(wid).putInt(parent).putShort((short) x).putShort((short) y)
                    .putShort((short) w).putShort((short) h).putShort((short) 0).putShort((short) cls)
                    .putInt(0).putInt(mask);
            for (int v : values) r.b.putInt(v);
            send(r.a, r.b.position(), null);
        }
    }

    void changeWindowAttributes(int window, int mask, int... values) throws IOException {
        int[] words = new int[2 + values.length];
        words[0] = window;
        words[1] = mask;
        System.arraycopy(values, 0, words, 2, values.length);
        sendVoid(CHANGE_WINDOW_ATTRIBUTES, 0, words);
    }

    /** Map state, override-redirect, class: see {@link #mapState}. */
    Pending getWindowAttributes(int window) throws IOException {
        return sendReply(GET_WINDOW_ATTRIBUTES, 0, new int[]{window}, null);
    }

    /** 0 unmapped, 1 unviewable, 2 viewable, from a GetWindowAttributes reply. */
    static int mapState(Reply r) {
        return r.u8(26);
    }

    static boolean overrideRedirect(Reply r) {
        return r.u8(27) != 0;
    }

    /** 1 InputOutput, 2 InputOnly. */
    static int windowClass(Reply r) {
        return r.u16(12);
    }

    void destroyWindow(int window) throws IOException {
        sendVoid(DESTROY_WINDOW, 0, new int[]{window});
    }

    void mapWindow(int window) throws IOException {
        sendVoid(MAP_WINDOW, 0, new int[]{window});
    }

    void unmapWindow(int window) throws IOException {
        sendVoid(UNMAP_WINDOW, 0, new int[]{window});
    }

    /**
     * Values in mask-bit order: x, y, width, height, border, sibling, stack
     * mode — only the ones whose bit is set.
     */
    void configureWindow(int window, int mask, int... values) throws IOException {
        synchronized (writeLock) {
            Req r = new Req(CONFIGURE_WINDOW, 0, 12 + 4 * values.length);
            r.b.putInt(window).putShort((short) mask).putShort((short) 0);
            for (int v : values) r.b.putInt(v);
            send(r.a, r.b.position(), null);
        }
    }

    /** Reply: x {@code s16(12)}, y {@code s16(14)}, w {@code u16(16)}, h {@code u16(18)}, border {@code u16(20)}. */
    Pending getGeometry(int drawable) throws IOException {
        return sendReply(GET_GEOMETRY, 0, new int[]{drawable}, null);
    }

    /** The children of {@code window}, bottom-most first. */
    int[] queryTree(int window) throws IOException {
        Reply r = sendReply(QUERY_TREE, 0, new int[]{window}, null).get();
        int n = r.u16(16);
        int[] kids = new int[n];
        for (int i = 0; i < n; i++) kids[i] = r.dataU32(i * 4);
        return kids;
    }

    Pending internAtomAsync(String name, boolean onlyIfExists) throws IOException {
        byte[] s = name.getBytes(StandardCharsets.ISO_8859_1);
        Pending p = new Pending(null);
        synchronized (writeLock) {
            Req r = new Req(INTERN_ATOM, onlyIfExists ? 1 : 0, 8 + pad4(s.length));
            r.b.putShort((short) s.length).putShort((short) 0).put(s);
            send(r.a, 8 + pad4(s.length), p);
        }
        return p;
    }

    int internAtom(String name) throws IOException {
        return internAtomAsync(name, false).get().u32(8);
    }

    /** {@code format} 8, 16 or 32; {@code data} already in this order's bytes. */
    void changeProperty(int window, int property, int type, int format, byte[] data, int count)
            throws IOException {
        int len = 24 + pad4(data.length);
        synchronized (writeLock) {
            Req r = new Req(CHANGE_PROPERTY, PROP_REPLACE, len);
            r.b.putInt(window).putInt(property).putInt(type).put((byte) format)
                    .put((byte) 0).put((byte) 0).put((byte) 0).putInt(count).put(data);
            send(r.a, len, null);
        }
    }

    void changeProperty32(int window, int property, int type, int... values) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) b.putInt(v);
        changeProperty(window, property, type, 32, b.array(), values.length);
    }

    void changePropertyString(int window, int property, int type, String value) throws IOException {
        byte[] s = value.getBytes(StandardCharsets.UTF_8);
        changeProperty(window, property, type, 8, s, s.length);
    }

    void deleteProperty(int window, int property) throws IOException {
        sendVoid(DELETE_PROPERTY, 0, new int[]{window, property});
    }

    /** A property as it came back: type, format, and the value's bytes. */
    static final class Property {
        final int type;
        final int format;
        final byte[] value;
        final int bytesAfter;

        Property(int type, int format, byte[] value, int bytesAfter) {
            this.type = type;
            this.format = format;
            this.value = value;
            this.bytesAfter = bytesAfter;
        }

        int[] ints() {
            ByteBuffer b = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[value.length / 4];
            for (int i = 0; i < out.length; i++) out[i] = b.getInt();
            return out;
        }

        String string() {
            int end = value.length;
            while (end > 0 && value[end - 1] == 0) end--;
            return new String(value, 0, end, StandardCharsets.UTF_8);
        }
    }

    Pending getPropertyAsync(int window, int property, int type, int offsetWords, int lengthWords, boolean delete)
            throws IOException {
        return sendReply(GET_PROPERTY, delete ? 1 : 0,
                new int[]{window, property, type, offsetWords, lengthWords}, null);
    }

    /** Null when the window has no such property. */
    static Property property(Reply r) {
        int type = r.u32(8);
        if (type == 0) return null;
        int format = r.u8(1);
        int count = r.u32(16);
        int bytes = Math.min(r.length, count * (format / 8));
        byte[] v = new byte[bytes];
        System.arraycopy(r.data, 0, v, 0, bytes);
        return new Property(type, format, v, r.u32(12));
    }

    /** The whole property (up to {@code maxWords} 4-byte units), or null. */
    Property getProperty(int window, int property, int type, int maxWords) throws IOException {
        return property(getPropertyAsync(window, property, type, 0, maxWords, false).get());
    }

    void setSelectionOwner(int owner, int selection, int time) throws IOException {
        sendVoid(SET_SELECTION_OWNER, 0, new int[]{owner, selection, time});
    }

    void convertSelection(int requestor, int selection, int target, int property, int time) throws IOException {
        sendVoid(CONVERT_SELECTION, 0, new int[]{requestor, selection, target, property, time});
    }

    /** SendEvent with a 32-byte event built by the caller. */
    void sendEvent(int destination, boolean propagate, int mask, byte[] event32) throws IOException {
        synchronized (writeLock) {
            Req r = new Req(SEND_EVENT, propagate ? 1 : 0, 44);
            r.b.putInt(destination).putInt(mask).put(event32, 0, 32);
            send(r.a, 44, null);
        }
    }

    /** A ClientMessage of format 32 to {@code window}, as ICCCM and EWMH spell them. */
    void sendClientMessage(int destination, int mask, int window, int type, int... data) throws IOException {
        ByteBuffer e = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        e.put((byte) CLIENT_MESSAGE).put((byte) 32).putShort((short) 0).putInt(window).putInt(type);
        for (int i = 0; i < 5; i++) e.putInt(i < data.length ? data[i] : 0);
        sendEvent(destination, false, mask, e.array());
    }

    /** {@code revertTo}: 0 None, 1 PointerRoot, 2 Parent. */
    void setInputFocus(int window, int revertTo, int time) throws IOException {
        sendVoid(SET_INPUT_FOCUS, revertTo, new int[]{window, time});
    }

    /**
     * A ZPixmap image of part of a drawable. The pixels arrive in
     * {@code sink} when it is large enough (rows of {@code w * bpp} bytes, no
     * padding at 32 bits per pixel), so a frame allocates nothing.
     */
    Pending getImage(int drawable, int x, int y, int w, int h, byte[] sink) throws IOException {
        Pending p = new Pending(sink);
        synchronized (writeLock) {
            Req r = new Req(GET_IMAGE, 2 /* ZPixmap */, 20);
            r.b.putInt(drawable).putShort((short) x).putShort((short) y).putShort((short) w)
                    .putShort((short) h).putInt(-1);
            send(r.a, 20, p);
        }
        return p;
    }

    Extension queryExtension(String name) throws IOException {
        byte[] s = name.getBytes(StandardCharsets.ISO_8859_1);
        Pending p = new Pending(null);
        synchronized (writeLock) {
            Req r = new Req(QUERY_EXTENSION, 0, 8 + pad4(s.length));
            r.b.putShort((short) s.length).putShort((short) 0).put(s);
            send(r.a, 8 + pad4(s.length), p);
        }
        Reply rep = p.get();
        return new Extension(name, rep.u8(8) != 0, rep.u8(9), rep.u8(10), rep.u8(11));
    }

    /** The keyboard map: {@code [keycode - minKeycode][column]} keysyms. */
    int[][] getKeyboardMapping() throws IOException {
        int count = maxKeycode - minKeycode + 1;
        Pending p;
        synchronized (writeLock) {
            p = new Pending(null);
            Req r = new Req(GET_KEYBOARD_MAPPING, 0, 8);
            r.b.put((byte) minKeycode).put((byte) count).putShort((short) 0);
            send(r.a, 8, p);
        }
        Reply rep = p.get();
        int per = rep.u8(1);
        int[][] map = new int[count][per];
        for (int k = 0; k < count; k++) {
            for (int c = 0; c < per; c++) map[k][c] = rep.dataU32((k * per + c) * 4);
        }
        return map;
    }

    /** Give {@code keycode} these keysyms, one per column. */
    void changeKeyboardMapping(int keycode, int[] keysyms) throws IOException {
        synchronized (writeLock) {
            Req r = new Req(CHANGE_KEYBOARD_MAPPING, 1, 8 + 4 * keysyms.length);
            r.b.put((byte) keycode).put((byte) keysyms.length).putShort((short) 0);
            for (int k : keysyms) r.b.putInt(k);
            send(r.a, r.b.position(), null);
        }
    }

    /** Eight rows — Shift, Lock, Control, Mod1…Mod5 — of keycodes (0 = none). */
    int[][] getModifierMapping() throws IOException {
        Reply rep = sendReply(GET_MODIFIER_MAPPING, 0, new int[0], null).get();
        int per = rep.u8(1);
        int[][] mods = new int[8][per];
        for (int m = 0; m < 8; m++) {
            for (int i = 0; i < per; i++) mods[m][i] = rep.data[m * per + i] & 0xff;
        }
        return mods;
    }

    void killClient(int resource) throws IOException {
        sendVoid(KILL_CLIENT, 0, new int[]{resource});
    }

    // ── extensions ──

    /** Composite: windows drawn into pixmaps of their own instead of the screen. */
    void compositeQueryVersion(Extension c) throws IOException {
        sendReply(c.major, 0, new int[]{0, 4}, null).get();
    }

    /** {@code manual}: nothing is drawn to the screen at all; the pixmaps are all there is. */
    void compositeRedirectSubwindows(Extension c, int window, boolean manual) throws IOException {
        sendVoid(c.major, 2, new int[]{window, manual ? 1 : 0});
    }

    /** DAMAGE: what changed in a drawable, as events. */
    void damageQueryVersion(Extension d) throws IOException {
        sendReply(d.major, 0, new int[]{1, 1}, null).get();
    }

    static final int DAMAGE_DELTA_RECTANGLES = 1;

    void damageCreate(Extension d, int damage, int drawable, int level) throws IOException {
        sendVoid(d.major, 1, new int[]{damage, drawable, level});
    }

    void damageDestroy(Extension d, int damage) throws IOException {
        sendVoid(d.major, 2, new int[]{damage});
    }

    /** Clear the damage: what is drawn after this is reported afresh. */
    void damageSubtract(Extension d, int damage) throws IOException {
        sendVoid(d.major, 3, new int[]{damage, 0, 0});
    }

    /** XFIXES: the cursor's image, and selection owners changing. */
    void xfixesQueryVersion(Extension f) throws IOException {
        sendReply(f.major, 0, new int[]{5, 0}, null).get();
    }

    static final int XFIXES_SET_SELECTION_OWNER_MASK = 1;
    static final int XFIXES_DISPLAY_CURSOR_MASK = 1;

    void xfixesSelectSelectionInput(Extension f, int window, int selection, int mask) throws IOException {
        sendVoid(f.major, 2, new int[]{window, selection, mask});
    }

    void xfixesSelectCursorInput(Extension f, int window, int mask) throws IOException {
        sendVoid(f.major, 3, new int[]{window, mask});
    }

    /** Reply: w {@code u16(12)}, h {@code u16(14)}, hot x/y {@code u16(16)/u16(18)}, serial {@code u32(20)}, then w×h ARGB words. */
    Pending xfixesGetCursorImage(Extension f) throws IOException {
        return sendReply(f.major, 4, new int[0], null);
    }

    /** XTEST: input as if from the keyboard and mouse. */
    void xtestGetVersion(Extension t) throws IOException {
        Pending p = new Pending(null);
        synchronized (writeLock) {
            Req r = new Req(t.major, 0, 8);
            r.b.put((byte) 2).put((byte) 0).putShort((short) 2);
            send(r.a, 8, p);
        }
        p.get();
    }

    /**
     * {@code type} KEY_PRESS…MOTION_NOTIFY; {@code detail} the keycode or
     * button (0 for a motion, which is absolute at x, y on the root).
     */
    void xtestFakeInput(Extension t, int type, int detail, int x, int y) throws IOException {
        synchronized (writeLock) {
            Req r = new Req(t.major, 2, 36);
            r.b.put((byte) type).put((byte) detail).putShort((short) 0).putInt(CURRENT_TIME)
                    .putInt(type == MOTION_NOTIFY ? root : 0).putInt(0).putInt(0)
                    .putShort((short) x).putShort((short) y).putInt(0).putShort((short) 0)
                    .put((byte) 0).put((byte) 0);
            send(r.a, 36, null);
        }
    }

    /** This client's requests go on while another client has grabbed the server. */
    void xtestGrabControl(Extension t, boolean impervious) throws IOException {
        sendVoid(t.major, 3, new int[]{impervious ? 1 : 0});
    }

    /** MIT-SHM: reply {@code u16(8)} major, {@code u16(10)} minor. */
    Reply shmQueryVersion(Extension s) throws IOException {
        return sendReply(s.major, 0, new int[0], null).get();
    }

    boolean canPassFds() {
        return fds != null;
    }

    /**
     * Share memory with the server by handing it a descriptor (MIT-SHM 1.2):
     * no System V IPC, which Android's kernel does not have.
     */
    void shmAttachFd(Extension s, int seg, FileDescriptor fd, boolean readOnly) throws IOException {
        if (fds == null) throw new IOException("this socket cannot pass descriptors");
        synchronized (writeLock) {
            try {
                out.flush();
                fds.sendWithNextWrite(new FileDescriptor[]{fd});
            } catch (IOException e) {
                lost(e);
                throw e;
            }
            Req r = new Req(s.major, 6, 12);
            r.b.putInt(seg).put((byte) (readOnly ? 1 : 0)).put((byte) 0).put((byte) 0).put((byte) 0);
            send(r.a, 12, null);
            try {
                out.flush();
            } catch (IOException e) {
                lost(e);
                throw e;
            }
        }
    }

    void shmDetach(Extension s, int seg) throws IOException {
        sendVoid(s.major, 2, new int[]{seg});
    }

    /** ShmGetImage, ZPixmap: the pixels land in the segment at {@code offset}; the reply only says how many. */
    Pending shmGetImage(Extension s, int drawable, int x, int y, int w, int h, int seg, int offset)
            throws IOException {
        Pending p = new Pending(null);
        synchronized (writeLock) {
            Req r = new Req(s.major, 4, 32);
            r.b.putInt(drawable).putShort((short) x).putShort((short) y).putShort((short) w)
                    .putShort((short) h).putInt(-1).put((byte) 2).put((byte) 0).put((byte) 0)
                    .put((byte) 0).putInt(seg).putInt(offset);
            send(r.a, 32, p);
        }
        return p;
    }

    /** Requests up to the length the reply names (in 4-byte units), with the long form past 65535. */
    void enableBigRequests(Extension b) throws IOException {
        Reply r = sendReply(b.major, 0, new int[0], null).get();
        int max = r.u32(8);
        if (max > maxRequestLength) maxRequestLength = Math.min(max, 4 << 20);
    }

    /** RANDR's version handshake, which it wants before anything else. */
    void randrQueryVersion(Extension r) throws IOException {
        sendReply(r.major, 0, new int[]{1, 5}, null).get();
    }

    /** RRSetScreenSize: grow the screen to hold a larger desktop. */
    void randrSetScreenSize(Extension r, int w, int h) throws IOException {
        synchronized (writeLock) {
            Req q = new Req(r.major, 7, 20);
            // millimetres at 96 dpi, which nothing here reads
            q.b.putInt(root).putShort((short) w).putShort((short) h)
                    .putInt(Math.max(1, w * 254 / 960)).putInt(Math.max(1, h * 254 / 960));
            send(q.a, 20, null);
        }
    }

    // ── helpers ──

    static int pad4(int n) {
        return (n + 3) & ~3;
    }

    static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) throw new EOFException("X server closed the connection");
            off += n;
            len -= n;
        }
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int u32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    /** The cookie of the first entry of an Xauthority file ({@link #xauthority}'s shape), or null. */
    static byte[] cookieFrom(byte[] file) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
            bb.getShort(); // family
            for (int field = 0; field < 3; field++) {
                int len = bb.getShort() & 0xffff;
                bb.position(bb.position() + len); // address, display number, name
            }
            int len = bb.getShort() & 0xffff;
            if (len == 0 || len > 64) return null;
            byte[] cookie = new byte[len];
            bb.get(cookie);
            return cookie;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * An Xauthority file holding one cookie for display {@code number}, for
     * any address — what Xvnc's {@code -auth} reads and what libXau finds
     * through XAUTHORITY for every client in the guest.
     */
    static byte[] xauthority(String number, byte[] cookie) {
        byte[] name = "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.ISO_8859_1);
        byte[] num = number.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + 2 + num.length + 2 + name.length + 2 + cookie.length)
                .order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) 0xffff); // FamilyWild
        b.putShort((short) 0);      // no address
        b.putShort((short) num.length).put(num);
        b.putShort((short) name.length).put(name);
        b.putShort((short) cookie.length).put(cookie);
        return b.array();
    }
}
