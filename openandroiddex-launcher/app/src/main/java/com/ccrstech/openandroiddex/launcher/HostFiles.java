package com.ccrstech.openandroiddex.launcher;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Transport to the computer's file service, over the loopback port adb
 * reverses onto this phone.
 *
 * This is {@link WmClient}'s twin and is meant to be read beside it: one
 * persistent socket, a line protocol a person can drive by hand, one request
 * and one reply per call, and the same single-reconnect retry with the same
 * reason behind it. Pure mechanism — which files to copy, and what to tell the
 * user about it, live in {@link FileTransfer} and the window. Every call
 * blocks, so nothing here may run on the UI thread; {@link #post} is how a UI
 * thread asks for something.
 *
 * <p><b>Why a socket at all.</b> Three cheaper-looking routes were measured
 * and rejected. The {@code content query} request queue that carries the
 * launcher's other messages to the PC costs ~990 ms per round because it boots
 * a JVM per invocation, plus the poll interval on top — a folder click would
 * take over a second, and AGENTS.md forbids new traffic on it outright.
 * Relaying through openandroiddex-wmd was rejected because wmd is fixed as
 * task management with no UI and no policy, and a file service is nothing but
 * policy. {@code adb push}/{@code adb pull} was rejected twice over: the
 * launcher cannot invoke adb at all, and on the PC side {@code run_adb_full}
 * buffers to EOF and hard-kills at 25 s, while adb's own progress output has
 * no trailing newlines — the hazard transfer.rs already documents. A socket
 * through the adb tunnel costs 2.57 ms median / 5.30 ms p95 per round trip —
 * measured on the forward that carries the window daemon, which is this same
 * plumbing pointed the other way — streams the bytes themselves, and makes
 * cancel a socket close.
 *
 * <p><b>Why the port is a constant.</b> {@link #DEVICE_PORT} is compiled into
 * both ends and the beacon deliberately never carries one, so a hostile local
 * app cannot point this client somewhere else. What it CAN do is bind 7192
 * before the session starts and answer in the host's place — exactly the
 * exposure the window daemon already accepts on 7191, and the reason the
 * HELLO token is minted fresh per session rather than stored (see
 * {@link HostLink}).
 *
 * <p>Requires android.permission.INTERNET: without it an app cannot create any
 * socket, including one to 127.0.0.1.
 */
final class HostFiles {

    private static final String HOST = "127.0.0.1";

    /**
     * The phone-side port, fixed on both ends. The host binds whatever it can
     * get in its own range and {@code adb reverse tcp:7192 tcp:&lt;hostPort&gt;}
     * hides that from this side entirely.
     */
    static final int DEVICE_PORT = 7192;

    /** The only protocol version there has ever been; sent so there can be a second. */
    private static final int VERSION = 1;

    /**
     * Loopback through adb either answers immediately or is not there. Half a
     * second is already generous — {@link WmClient} allows 400 ms to a daemon
     * on this same phone, and the extra 100 ms here buys the hop across the
     * cable.
     */
    private static final int CONNECT_TIMEOUT_MS = 500;

    /**
     * Control replies. Longer than WmClient's 1500 ms because the answers are
     * not in memory on the other end: LIST and PLAN walk a real filesystem,
     * and the first click into a folder on a spun-down external drive is the
     * worst case this has to survive without declaring the computer dead.
     */
    private static final int READ_TIMEOUT_MS = 4000;

    /**
     * Data sockets. Sized for the longest a busy host may pause mid-file, not
     * for a click: a copy that has already moved 400 MB must not be thrown
     * away because the sender stalled five seconds behind a competing write.
     * It still bounds the wait — a cable pulled mid-copy fails here rather
     * than hanging the transfer thread forever.
     */
    private static final int DATA_TIMEOUT_MS = 20_000;

    /**
     * PLAN's own deadline, and deliberately the data socket's number rather
     * than the control socket's.
     *
     * One PLAN walks a whole tree — files.rs caps it at 2000 directories and
     * 20000 files, a read_dir per directory plus a metadata() per entry —
     * while {@link #READ_TIMEOUT_MS} is sized for a single folder click. A
     * folder on a network mount or a spun-down drive can honestly need longer
     * than 4 s, and the answer to that is to wait, not to call a working
     * computer unreadable.
     */
    private static final int PLAN_TIMEOUT_MS = DATA_TIMEOUT_MS;

    /** Same as WebFiles.pump, and for the same reason: it is where copy throughput stops improving. */
    private static final int BUF = 64 * 1024;

    /**
     * Only bounds the lines read off a DATA socket, where every reply is a
     * greeting, {@code OK &lt;size&gt;}, {@code OK} or {@code ERR} — tens of
     * bytes. The control socket's replies are deliberately not capped here:
     * a LIST of 5000 entries is base64 JSON well past a megabyte, and
     * {@link java.io.BufferedReader#readLine()} is what reads those.
     */
    private static final int MAX_LINE = 64 * 1024;

    // ── error vocabulary ──
    // The host sends: auth, proto, notfound, denied, notdir, isdir, exists,
    // io, space, toobig, unknown. The two below are ours and never arrive on
    // the wire — they are how "the socket failed" and "the user stopped it"
    // reach a caller that is already catching HostException for everything else.
    static final String CODE_AUTH = "auth";
    static final String CODE_PROTO = "proto";
    static final String CODE_IO = "io";
    static final String CODE_UNKNOWN = "unknown";
    /** No answer at all — the service is gone, or the reverse is. R.string.ft_host_unreachable. */
    static final String CODE_OFFLINE = "offline";
    /** The caller's own {@link Cancel} said stop. Not a failure to report as one. */
    static final String CODE_CANCELLED = "cancelled";

    /** Byte-accurate, because the bytes come down this socket rather than through adb. */
    interface Progress {
        void onBytes(long done, long total);
    }

    /**
     * Asked between 64 kB chunks. A cancel is therefore noticed within one
     * chunk of wire time, or one {@link #DATA_TIMEOUT_MS} if the peer has
     * stopped talking entirely — and {@link #abort()} exists for the case
     * where even that is too long to make the user wait.
     */
    interface Cancel {
        boolean cancelled();
    }

    /**
     * Anything the host refused, or the socket did.
     *
     * An IOException rather than a checked type of its own because every
     * caller is already inside a try around stream I/O, and the two failures
     * are not usefully different at the call site: a copy that could not read
     * the source and a copy the host denied both end the same way. The
     * {@link #code} is there for the one place that does care — the window,
     * choosing between "not answering" and a message about this file.
     */
    static final class HostException extends IOException {
        final String code;

        HostException(String code, String detail) {
            super(detail == null || detail.isEmpty() ? code : code + ": " + detail);
            this.code = code == null ? CODE_UNKNOWN : code;
        }
    }

    private final HandlerThread thread = new HandlerThread("host-files");
    private final Handler io;

    /**
     * The control socket and its streams.
     *
     * Volatile, not lock-protected, because {@link #close()} is called from the
     * main thread — the window's Retry button drops the socket before it tries
     * again — while a worker thread may be sitting inside {@link #request} for
     * two read timeouts. See {@link #close()} for why the races that leaves are
     * all benign.
     */
    private volatile Socket socket;
    private volatile BufferedReader in;
    private volatile PrintWriter out;

    /**
     * Identity from HELLO. Volatile because the window reads them to draw a
     * heading while the io thread is still connecting, and deliberately NOT
     * cleared by {@link #close()}: a reconnect is the same computer, and a
     * pane heading that blinks back to a placeholder every time the socket
     * turns over reads as a fault that has not happened.
     */
    private volatile String hostLabel;
    private volatile String os;
    private volatile char sep = '/';

    /**
     * The data socket of the copy in flight, so {@link #abort()} can close it
     * out from under a blocked read. One transfer at a time is the design
     * ({@link FileTransfer} drains its queue on a single thread); two would
     * simply leave the older one to notice its own {@link Cancel}.
     */
    private volatile Socket transfer;

    /** {@link #connected()} without a lock — see there for why it is a flag. */
    private volatile boolean greeted;

    /**
     * Set once by {@link #shutdown()} and never cleared.
     *
     * Without it, shutdown does not end anything: {@link FileTransfer#shutdown}
     * sets its flags and returns without joining, so the copy engine's thread is
     * routinely still inside a verb when the window's onDestroy gets here, and
     * the very next thing that thread does is call {@link #connect} — which
     * would open a fresh socket to the computer on behalf of a window that no
     * longer exists. A verb that arrives after this point fails
     * {@link #CODE_OFFLINE}, which every caller already handles.
     */
    private volatile boolean shutDown;

    /** Constructs; does not connect. The first verb does that. */
    HostFiles() {
        thread.start();
        io = new Handler(thread.getLooper());
    }

    /** Run something on this client's own thread — the only safe way from the UI. */
    void post(Runnable r) {
        io.post(r);
    }

    void postDelayed(Runnable r, long delayMs) {
        io.postDelayed(r, delayMs);
    }

    /**
     * Give up the thread and both sockets.
     *
     * Not the same as {@link #close()}, which only drops the control socket so
     * the next verb can rebuild it. Call this from onDestroy — a window that
     * closes mid-copy has already told the user the copy stops.
     *
     * <p>The flag goes up first and the sockets come down after, in that order:
     * the reverse would let a worker already past the flag check reconnect
     * behind us. {@link #close()} is called here rather than posted to the io
     * thread because it takes no lock and because a post would be dropped
     * outright if the looper had already gone.
     */
    void shutdown() {
        shutDown = true;
        abort();
        close();
        thread.quitSafely();
    }

    /** The name the computer gave for itself over HELLO. May be null. */
    String hostLabel() {
        return hostLabel;
    }

    /** "win" | "mac" | "linux", or null until HELLO has been answered. */
    String os() {
        return os;
    }

    /**
     * The host's path separator — '\\' on Windows, '/' everywhere else, and
     * '/' until HELLO says otherwise.
     *
     * Only {@link #join} needs it. Every path the phone sends back came out of
     * a LIST reply whole, precisely so that neither side has to reassemble the
     * other's paths.
     */
    char sep() {
        return sep;
    }

    /**
     * Whether a control socket is currently open and greeted.
     *
     * Informational, for a window deciding what to draw. It is not evidence
     * the computer is alive — see {@link #request}: a socket whose peer has
     * gone still looks open. Only a verb returning without throwing is proof.
     *
     * <p>Reads a flag rather than the socket, and takes no lock, because the
     * caller is the main thread: every other method here holds the monitor for
     * as long as a verb takes, so a synchronized accessor would let a folder
     * listing on a sleeping external drive block a frame for the whole of
     * {@link #READ_TIMEOUT_MS}.
     */
    boolean connected() {
        return greeted;
    }

    // ── verbs ──

    /** The drives, volumes and home folders the computer offers as starting points. */
    JSONArray roots() throws IOException {
        String[] reply = answer("ROOTS", request("ROOTS"));
        try {
            return new JSONArray(payload(reply, 1, "ROOTS"));
        } catch (JSONException e) {
            throw new HostException(CODE_PROTO, "ROOTS: " + e.getMessage());
        }
    }

    /**
     * One folder: its parent, its entries, and whether the host stopped
     * counting. Entries carry their own full host path.
     */
    JSONObject list(String path) throws IOException {
        return object("LIST", request("LIST " + b64(path)));
    }

    /**
     * A flat recursive walk of a host folder — the directories to create and
     * the files to fetch, in one answer.
     *
     * The sending side always enumerates, so tree recursion exists exactly
     * once per direction: here for computer→phone, and in {@link FileTransfer}
     * over {@code File.listFiles()} for phone→computer. The alternative — the
     * phone driving a LIST per subfolder — pays a round trip per directory and
     * has to hold half a walk in the UI's own state.
     */
    JSONObject plan(String path) throws IOException {
        // The one verb with a deadline of its own, and for one reason: the
        // walk is expensive. A big tree on a slow volume outlasts a folder
        // click's 4 s, and the honest answer to that is to wait rather than to
        // call a working computer unreadable. It keeps the reconnect retry —
        // see {@link #request}, which no longer re-issues a request the host
        // actually accepted and then timed out on, so the 8.1 s double-walk
        // this deadline would otherwise have bought is gone either way.
        return object("PLAN", request("PLAN " + b64(path), PLAN_TIMEOUT_MS));
    }

    /** Create a folder on the computer, parents included. */
    void mkdir(String path) throws IOException {
        answer("MKDIR", request("MKDIR " + b64(path)));
    }

    /** {free, total} bytes on the volume holding {@code path}. */
    long[] free(String path) throws IOException {
        String[] reply = answer("FREE", request("FREE " + b64(path)));
        return new long[]{number(reply, 1, "FREE"), number(reply, 2, "FREE")};
    }

    /**
     * A free name for {@code nm} in host directory {@code dir} — "report.pdf"
     * beside an existing one comes back "report (2).pdf".
     *
     * Asked of the host rather than worked out here because only the host can
     * see what is already in that folder, and because the answer has to match
     * what WebFiles.uniqueIn does on the phone side; two implementations of a
     * naming rule is one too many.
     */
    String unique(String dir, String nm) throws IOException {
        String[] reply = answer("UNIQUE", request("UNIQUE " + b64(dir) + " " + b64(nm)));
        return payload(reply, 1, "UNIQUE");
    }

    // ── bulk transfer ──

    /**
     * Stream one host file into {@code out}. Returns the byte count, which is
     * always the size the host announced — a short answer throws instead.
     *
     * On its own socket, opened and closed for this one file. That is the
     * point: a copy of a 4 GB video cannot sit in front of the user's next
     * folder click, and a cancel is then a close of a socket nothing else is
     * using. The cost is a connect and a HELLO per file, ~5 ms on loopback,
     * which is invisible beside any file worth a progress bar.
     */
    long get(String path, OutputStream out, Progress p, Cancel c) throws IOException {
        Conn conn = open("GET " + b64(path));
        try {
            String[] head = answer("GET", readLine(conn.in));
            long size = number(head, 1, "GET");
            // A negative length would otherwise skip the loop and report a
            // complete copy of nothing, which the caller would rename into
            // place as a real file.
            if (size < 0) throw new HostException(CODE_PROTO, "GET: negative length " + size);
            byte[] buf = new byte[BUF];
            long done = 0;
            int lastPct = -1;
            while (done < size) {
                if (c != null && c.cancelled()) {
                    throw new HostException(CODE_CANCELLED, "stopped after " + done + " bytes");
                }
                int want = (int) Math.min(buf.length, size - done);
                int n = conn.in.read(buf, 0, want);
                if (n < 0) {
                    // Not "the file was smaller than it said": the host sent a
                    // size and then stopped, so the destination holds a
                    // truncated file. The caller's .part rename is what keeps
                    // that from being mistaken for the real thing.
                    throw new HostException(CODE_IO,
                            "the computer stopped sending after " + done + " of " + size + " bytes");
                }
                out.write(buf, 0, n);
                done += n;
                lastPct = tick(p, done, size, lastPct);
            }
            out.flush();
            if (p != null && lastPct != 100) p.onBytes(done, size);
            return done;
        } finally {
            conn.close();
        }
    }

    /**
     * Stream {@code size} bytes from {@code in} to a new file at {@code path}
     * on the computer. Returns the name it actually landed under, which
     * differs from the one asked for when the host had to de-duplicate it.
     *
     * The size is sent up front and not negotiable. A length-delimited body is
     * what lets the host write to {@code .part} and rename only on a complete
     * file — the same invariant WebFiles.receive holds on this side — and what
     * lets an interrupted copy be told apart from a short one.
     */
    String put(String path, long size, InputStream in, Progress p, Cancel c) throws IOException {
        // Caught here rather than on the wire: File.length() answers 0 for
        // anything it cannot stat, but a caller that ever hands us a negative
        // would otherwise put a length the host has to reject onto the request
        // line, and the mirror of get()'s check is where anyone would look.
        if (size < 0) throw new HostException(CODE_PROTO, "PUT: negative length " + size);
        Conn conn = open("PUT " + b64(path) + " " + size);
        try {
            answer("PUT", readLine(conn.in));       // the host is ready for the body
            byte[] buf = new byte[BUF];
            long done = 0;
            int lastPct = -1;
            while (done < size) {
                if (c != null && c.cancelled()) {
                    throw new HostException(CODE_CANCELLED, "stopped after " + done + " bytes");
                }
                int want = (int) Math.min(buf.length, size - done);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    throw new HostException(CODE_IO,
                            "the file ended after " + done + " of " + size + " bytes");
                }
                conn.out.write(buf, 0, n);
                done += n;
                lastPct = tick(p, done, size, lastPct);
            }
            conn.out.flush();
            if (p != null && lastPct != 100) p.onBytes(done, size);
            String[] reply = answer("PUT", readLine(conn.in));
            // A host that answers a bare OK has still landed the file. Report
            // the name we asked for rather than failing a copy that worked;
            // the only thing lost is the "(2)" when it had to rename.
            return reply.length > 1 ? payload(reply, 1, "PUT") : nameOf(path);
        } finally {
            conn.close();
        }
    }

    /**
     * Close the copy in flight, if there is one, from another thread.
     *
     * {@link Cancel} is enough while bytes are moving. This is for the case
     * where they are not — a window closing on a transfer that is waiting out
     * {@link #DATA_TIMEOUT_MS} against a computer that has already gone.
     */
    void abort() {
        Socket s = transfer;
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    /** @return the percentage last reported, so only a change is sent on. */
    private static int tick(Progress p, long done, long total, int lastPct) {
        int pct = total > 0 ? (int) (done * 100 / total) : 100;
        if (p == null || pct == lastPct) return lastPct;
        p.onBytes(done, total);
        return pct;
    }

    // ── the control socket ──

    /**
     * One request, one reply, with a single reconnect retry.
     *
     * The retry is not belt-and-braces: Socket#isConnected() stays true for the
     * life of the object once it has ever connected, so a socket whose peer has
     * gone away still looks healthy. Writes then succeed into the void and
     * readLine() returns null at EOF rather than throwing — so without treating
     * null as a dead connection, a desktop app restart wedges the client
     * permanently. Restarting the PC app is the single most likely thing to
     * happen to this socket during a session.
     *
     * <p>Repeating the request is only safe because every verb that comes
     * through here is idempotent: ROOTS, LIST, FREE, UNIQUE and PLAN ask
     * questions, and MKDIR is mkdir -p, so a second one on a folder the first
     * attempt did create is a success either way. The two verbs that are not
     * idempotent — GET and PUT — do not use this path at all; they get their
     * own socket in {@link #open}, and a PUT that dies half-written must be
     * re-driven by whoever knows what was already copied, not by the transport.
     *
     * <p>What the retry is NOT for is a request the host accepted and then ran
     * out of clock on. That one has already spent the whole deadline, and
     * asking again makes the host redo the same work to reach the same failure:
     * on {@link #plan}, whose deadline is five times the others, it was 8.1 s of
     * waiting to report a perfectly healthy computer as unable to read the
     * folder. {@link #spentDeadline} is how the two are told apart.
     */
    private String request(String command) {
        return request(command, READ_TIMEOUT_MS);
    }

    /**
     * The same, for a verb that needs a deadline of its own — {@link #plan} is
     * the one, and the only one.
     *
     * The monitor lives here rather than on the one-argument form: it has to
     * cover a request and its reply as a pair, and both attempts of a retry.
     */
    private synchronized String request(String command, int readTimeoutMs) {
        String reply = attempt(command, readTimeoutMs);
        // A dead connection fails in microseconds — EOF, a failed write, a
        // refused connect — so asking again costs nothing and is the whole
        // reason the retry exists. A spent deadline is the opposite trade and
        // is the one case that does not get a second one.
        if (reply != null || spentDeadline) return reply;
        close();
        return attempt(command, readTimeoutMs);
    }

    /**
     * Whether the last {@link #attempt} died on its read deadline rather than
     * on a connection that was already gone.
     *
     * Plain, not volatile: it is written and read only inside {@link #request},
     * which holds the monitor across both attempts, so the one thread that can
     * see it is the one that set it.
     */
    private boolean spentDeadline;

    private String attempt(String command, int readTimeoutMs) {
        spentDeadline = false;
        if (!connect()) return null;
        // Into a local before anything touches it, for close()'s reason: the
        // main thread can null the field mid-verb, and the deadline has to come
        // back off the same socket it went on.
        Socket s = socket;
        try {
            if (s != null && readTimeoutMs != READ_TIMEOUT_MS) s.setSoTimeout(readTimeoutMs);
            out.println(command);
            if (out.checkError()) {
                close();
                return null;
            }
            String reply = in.readLine();
            if (reply == null) close();     // EOF: peer is gone
            return reply;
        } catch (SocketTimeoutException e) {
            // The host took the request and never answered inside the deadline.
            // Recorded before the socket goes, so the retry above does not
            // charge the user a second full wait for the same answer.
            spentDeadline = true;
            close();
            return null;
        } catch (Exception e) {
            close();
            return null;
        } finally {
            // Always back to the control deadline. This socket is pooled across
            // every verb, so a LIST left holding PLAN's 20 s would take five
            // times as long to notice a computer that has gone.
            if (s != null && readTimeoutMs != READ_TIMEOUT_MS) {
                try {
                    s.setSoTimeout(READ_TIMEOUT_MS);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Lazily (re)connects and greets. A host that is not there just means every
     * verb throws {@link #CODE_OFFLINE}, which the window has a state for.
     */
    private boolean connect() {
        // Silently, and before anything else: a window that has been destroyed
        // is not an outage anyone can be told about, and logging one per queued
        // verb would bury the line that mattered.
        if (shutDown) return false;
        if (socket != null && !socket.isClosed()) return true;
        close();
        String token = HostLink.token();
        if (token == null) {
            outage("no beacon from the computer yet — nothing has said the file service is up");
            return false;
        }
        return connectWith(token, true);
    }

    /**
     * One connect-and-greet with one specific credential.
     *
     * {@code fallback} is what makes a forged beacon survivable. Any app on
     * this phone can broadcast one, so {@link HostLink#token()} may hand back
     * an unproven candidate rather than the credential that last worked; a
     * refusal here is therefore as likely to mean "someone else is shouting"
     * as "the desktop restarted". Without the second dial, an app broadcasting
     * a well-formed token twice a second would fail one verb in two and the
     * window would flicker between working and "not answering". It costs one
     * loopback connect, only after an {@code ERR auth}, and only once —
     * {@link HostLink#refused} answers null the second time.
     */
    private boolean connectWith(String token, boolean fallback) {
        // The socket is not published into the field until HELLO has been
        // answered, so it is this local that has to be closed on the way out:
        // a greeting that fails leaves a live connection nothing can reach,
        // and the host counts those.
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(HOST, DEVICE_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter w = new PrintWriter(s.getOutputStream(), true);
            w.println("HELLO " + token + " " + VERSION);
            String[] hello = answer("HELLO", r.readLine());
            greet(hello);
            // Proof rather than a claim, and the only kind there is: a token is
            // the computer's own because the computer answered it. See
            // HostLink, where an unproven beacon may not evict this one.
            HostLink.proved(token);
            socket = s;
            in = r;
            out = w;
            greeted = true;
            if (!everConnected) {
                everConnected = true;
                reportedFailure = false;
                DexLog.step("files", "connected to the computer's file service on "
                        + HOST + ":" + DEVICE_PORT + " (" + os + ")");
            }
            return true;
        } catch (HostException e) {
            closeQuietly(s);
            close();
            if (fallback && CODE_AUTH.equals(e.code)) {
                String incumbent = HostLink.refused(token);
                // The refused token was a candidate standing in front of one
                // the host has already answered — a forgery, or a beacon a
                // restart has overtaken. Try the credential that worked before
                // giving the window an error it would only offer Retry for.
                if (incumbent != null) return connectWith(incumbent, false);
            }
            // A refused token is worth its own line: it means the beacon we are
            // holding was minted by a session that has since been replaced, and
            // the next heartbeat fixes it without anyone doing anything.
            outage(CODE_AUTH.equals(e.code)
                    ? "the computer refused our token — waiting for the next beacon"
                    : "the computer's file service answered nonsense (" + e.getMessage() + ")");
            return false;
        } catch (Exception e) {
            closeQuietly(s);
            close();
            outage("the computer's file service is unreachable on "
                    + HOST + ":" + DEVICE_PORT + " (" + e + ")");
            return false;
        }
    }

    /**
     * Cache what HELLO said about the machine on the other end.
     *
     * Nothing is published until the whole greeting has been read, because a
     * half-applied one is worse than none: these fields outlive
     * {@link #close()} on purpose, so a malformed greeting from a squatter on
     * 7192 would otherwise wipe the identity of the session that is still
     * running and leave the pane heading blank until a real reconnect.
     */
    private void greet(String[] hello) throws IOException {
        String nextOs = hello.length > 2 ? hello[2].trim() : null;
        if (nextOs == null || nextOs.isEmpty()) {
            throw new HostException(CODE_PROTO, "HELLO named no operating system");
        }
        // The separator crosses as a hex byte rather than as itself: a bare
        // backslash in a whitespace-split line is a character every layer
        // between here and the host feels entitled to interpret. A greeting
        // that omits it, garbles it, or names some third character falls back
        // to what the OS implies — not to '/', which would be silently wrong
        // on exactly one of the three platforms and nowhere a test would look.
        char nextSep = "win".equals(nextOs) ? '\\' : '/';
        if (hello.length > 3) {
            try {
                int raw = Integer.parseInt(hello[3].trim(), 16);
                if (raw == '\\' || raw == '/') nextSep = (char) raw;
            } catch (NumberFormatException ignored) {
                // keep the OS default
            }
        }
        hostLabel = hello.length > 1 ? decode(hello[1]) : null;
        os = nextOs;
        sep = nextSep;
    }

    /**
     * Once per outage, not once per call.
     *
     * A file service that is not there means one window shows an error and
     * offers Retry — worth a line, but this runs on every folder click, and
     * DexLog is folded into the PC's own trace where a repeated line buries
     * the one that mattered. Same shape as WmClient's, for the same reason.
     */
    private void outage(String message) {
        if (everConnected || !reportedFailure) {
            reportedFailure = true;
            everConnected = false;
            DexLog.warn("files", message);
        }
    }

    /** Whether the last connect attempt got through, so outages are logged once. */
    private boolean everConnected;
    private boolean reportedFailure;

    /**
     * Drop the control socket. The next verb rebuilds it; the identity read
     * from HELLO is kept on purpose.
     *
     * <p><b>Deliberately not synchronized</b>, unlike everything else that
     * touches these fields. The window calls this from the main thread — Retry
     * has to mean a genuinely fresh connection, and the socket it is retrying
     * past is exactly the kind that still looks open — and by then a worker may
     * have been holding the monitor for two {@link #READ_TIMEOUT_MS} waits plus
     * their connects. Nine seconds of blocked main thread is an ANR, and it
     * would be raised by the one button whose whole job is to unstick things.
     *
     * <p>The races that leaves were checked one by one and all end the same
     * way. Racing {@link #connect}: either this runs before the fields are
     * published, and the new socket simply survives a Retry that arrived a
     * microsecond early, or it runs after and the socket is closed — there is
     * no interleaving that drops the last reference to a live socket, because
     * the field is read and cleared before the close. Racing {@link #attempt}:
     * the null field or the closed socket surfaces as an exception or as
     * checkError(), both of which that method already treats as a dead
     * connection and retries through.
     */
    void close() {
        greeted = false;
        Socket s = socket;
        socket = null;
        in = null;
        out = null;
        closeQuietly(s);
    }

    // ── data sockets ──

    /** One short-lived, greeted socket and the streams over it. */
    private final class Conn {
        final Socket socket;
        final BufferedInputStream in;
        final OutputStream out;

        Conn(Socket socket, BufferedInputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void close() {
            unclaim(socket);
            closeQuietly(socket);
        }
    }

    /**
     * Open a socket for one file, greet, and send {@code command}.
     *
     * HELLO is answered before the verb goes out rather than pipelined behind
     * it. Pipelining would save one loopback round trip per file — measured
     * ~2.5 ms — and would cost the ability to say which of the two requests a
     * refusal answered, on the one code path where a wrong answer means a
     * truncated file on someone's disk.
     */
    private Conn open(String command) throws IOException {
        // The engine's thread is not joined by its own shutdown, so it can get
        // here after the window has gone; without this it would open a data
        // socket nobody is left to close. See {@link #shutDown}.
        if (shutDown) {
            throw new HostException(CODE_OFFLINE, "the File transfer window has closed");
        }
        String token = HostLink.token();
        if (token == null) {
            throw new HostException(CODE_OFFLINE, "no beacon from the computer");
        }
        try {
            return openWith(command, token);
        } catch (HostException e) {
            // Same fallback as connectWith, and needed here too: a copy already
            // under way takes its next file straight through this path, so a
            // hostile beacon landing between two files would fail that file
            // outright rather than costing it a reconnect.
            String incumbent = CODE_AUTH.equals(e.code) ? HostLink.refused(token) : null;
            if (incumbent == null) throw e;
            return openWith(command, incumbent);
        }
    }

    /** One data socket, greeted with one specific credential. */
    private Conn openWith(String command, String token) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(HOST, DEVICE_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(DATA_TIMEOUT_MS);
            // Nagle off even though this socket exists to move bulk: the last
            // partial chunk of a PUT is followed by a wait for the host's
            // reply, which is precisely the case where Nagle and delayed ACK
            // add a fixed stall to every single file.
            s.setTcpNoDelay(true);
            // Claimed before the greeting, not after: a host that accepts the
            // connection and then says nothing is exactly the case abort() is
            // for, and it is also the one that would otherwise sit here for
            // the full data timeout.
            transfer = s;
            BufferedInputStream bin = new BufferedInputStream(s.getInputStream(), BUF);
            OutputStream bout = new BufferedOutputStream(s.getOutputStream(), BUF);
            writeLine(bout, "HELLO " + token + " " + VERSION);
            answer("HELLO", readLine(bin));
            // Evidence a control socket may never get: a copy can run for
            // minutes without a single control verb, and this is what promotes
            // a restarted computer's token in that window. See HostLink.
            HostLink.proved(token);
            writeLine(bout, command);
            return new Conn(s, bin, bout);
        } catch (HostException e) {
            unclaim(s);
            closeQuietly(s);
            throw e;
        } catch (IOException e) {
            unclaim(s);
            closeQuietly(s);
            throw new HostException(CODE_OFFLINE, e.toString());
        }
    }

    /**
     * Give up the abort handle, but only if it is still this socket's.
     *
     * The one place the field is released, so the guard cannot be forgotten on
     * one of the paths: a later transfer that claimed it while this one was
     * failing or finishing must not be left with nothing {@link #abort()} can
     * reach. A bare {@code transfer = null} on any of the four exits would do
     * exactly that, and it would show up as a Cancel that does nothing.
     */
    private void unclaim(Socket s) {
        if (transfer == s) transfer = null;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * One line, a byte at a time, off the same stream the body will come from.
     *
     * A Reader over a data socket would be a bug that only shows up on large
     * files: it fills its own char buffer from the socket, so the first
     * megabyte of the file is decoded as text and lost before anyone asks for
     * it. Reading through the BufferedInputStream keeps everything after the
     * newline exactly where the body reader expects it — WebServer reads its
     * request head the same way, for the same reason.
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (line.size() < MAX_LINE) {
            int b = in.read();
            if (b < 0) return line.size() == 0 ? null : line.toString("UTF-8");
            if (b == '\n') {
                String s = line.toString("UTF-8");
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            line.write(b);
        }
        throw new HostException(CODE_PROTO, "a reply line ran past " + MAX_LINE + " bytes");
    }

    // ── replies ──

    /**
     * The one place a reply line becomes either tokens to read or an exception
     * to throw. Every verb goes through it, so there is exactly one definition
     * of what a refusal is and no verb can quietly treat one as an empty result.
     */
    private static String[] answer(String verb, String reply) throws IOException {
        if (reply == null) {
            throw new HostException(CODE_OFFLINE, verb + ": the computer did not answer");
        }
        String[] p = reply.trim().split("\\s+");
        if (p.length == 0) throw new HostException(CODE_PROTO, verb + ": empty reply");
        if ("OK".equals(p[0])) return p;
        if ("ERR".equals(p[0])) {
            String code = p.length > 1 ? p[1] : CODE_UNKNOWN;
            String detail = p.length > 2 ? decode(p[2]) : null;
            throw new HostException(code, detail);
        }
        throw new HostException(CODE_PROTO, verb + ": " + reply);
    }

    private static JSONObject object(String verb, String reply) throws IOException {
        String[] p = answer(verb, reply);
        try {
            return new JSONObject(payload(p, 1, verb));
        } catch (JSONException e) {
            throw new HostException(CODE_PROTO, verb + ": " + e.getMessage());
        }
    }

    private static String payload(String[] p, int idx, String verb) throws IOException {
        if (p.length <= idx) throw new HostException(CODE_PROTO, verb + ": reply is missing a field");
        String decoded = decode(p[idx]);
        if (decoded == null) throw new HostException(CODE_PROTO, verb + ": undecodable field");
        return decoded;
    }

    private static long number(String[] p, int idx, String verb) throws IOException {
        if (p.length <= idx) throw new HostException(CODE_PROTO, verb + ": reply is missing a number");
        try {
            return Long.parseLong(p[idx].trim());
        } catch (NumberFormatException e) {
            throw new HostException(CODE_PROTO, verb + ": " + p[idx] + " is not a number");
        }
    }

    // ── encoding ──

    /**
     * URL-safe base64 without padding, which is what makes the line protocol
     * safe to split on whitespace.
     *
     * Paths are the one thing on this wire nobody controls: they carry spaces,
     * quotes, {@code $}, {@code |}, non-ASCII, and on Windows a separator that
     * is an escape character almost everywhere else. Percent-encoding was the
     * alternative and is worse here — it leaves {@code +} and {@code %} live,
     * and both ends would need to agree on which characters are safe. Padding
     * is dropped because {@code =} is the one base64 character a shell might
     * still find interesting.
     */
    static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** The inverse. Empty for anything that will not decode — see {@link #payload}. */
    static String unb64(String s) {
        String decoded = decode(s);
        return decoded == null ? "" : decoded;
    }

    private static String decode(String s) {
        if (s == null) return null;
        try {
            return new String(Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Join a host directory and a name with the HOST's separator.
     *
     * The only place the phone builds a host path at all — everything else it
     * sends came back whole from a LIST or PLAN reply. Both separators are
     * treated as terminal because a Windows drive root is {@code C:\} and a
     * path a user typed may well end in {@code /}; doubling one produces a
     * path Windows accepts and macOS does not, which is exactly the class of
     * bug that only appears on one of the two machines.
     */
    static String join(String dir, String name, char sep) {
        if (dir == null || dir.isEmpty()) return name;
        char last = dir.charAt(dir.length() - 1);
        if (last == sep || last == '/' || last == '\\') return dir + name;
        return dir + sep + name;
    }

    /** The last segment of a host path, either separator. */
    private static String nameOf(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }
}
