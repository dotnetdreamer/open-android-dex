package com.ccrstech.openandroiddex.launcher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Linux's files, for a browser that was let in ("Open in a browser", the page's
 * Files): look through them, send files and whole folders to Linux, bring them
 * back, see a photo, a video or a PDF, all without the desktop's picture.
 * Design record: doc/linux-browser.md, "Files".
 *
 * <p><b>Straight off the phone's storage.</b> The guest's {@code /} is a
 * folder of this app's ({@code files/linux/rootfs}), and proot runs as this
 * app's own user, so this process reads and writes it directly: nothing goes
 * through Linux, which need not even be running, and nothing goes through the
 * video. A transfer moves at the speed of the Wi-Fi and the flash. What Linux
 * sees elsewhere (proot's {@code -b}: the phone's shared folder at
 * {@code ~/Desktop/LinuxOnDeX}, {@code /tmp}) is a {@link Mount}.
 *
 * <p><b>Paths are Linux's.</b> A browser names {@code /root/Documents}, never
 * a path of the phone's, and a symbolic link is followed the way Linux would
 * follow it: an absolute one starts again at the guest's {@code /}, and
 * {@code ..} stops there ({@link #resolve}). Nothing is reached outside Linux
 * that way, whatever a link in it says; and {@code /proc}, {@code /sys} and
 * {@code /dev}, which are the phone's own, are not shown at all.
 *
 * <p><b>Sending.</b> A big file is one PUT ({@code put}) into a hidden part
 * file beside where it goes, renamed into place once it is whole; cut off, it
 * carries on from what arrived ({@code part}). Small files go many to a
 * request ({@code batch}): one round trip for a folder of a thousand of them
 * rather than a thousand. A file already there, the same size and time, is
 * not sent again, which makes sending something twice cheap and a retry safe.
 *
 * <p><b>Bringing back.</b> A file with Range ({@code get}), so a browser can
 * resume one and a video can seek; several, or a folder, as one ZIP made on
 * the way ({@code zip}), stored rather than compressed (most of what people
 * move is compressed already, and the phone keeps up with the Wi-Fi), its
 * length known before the first byte so the browser shows how long is left.
 * A browser fetches those two by itself, with no way to add the token, so the
 * page asks for a ticket first: good for those files only, for a few hours,
 * and only while that browser stays signed in ({@link #ticket}).
 *
 * <p><b>Deleting</b> moves to Linux's own Trash (the freedesktop.org layout
 * Linux's file manager reads), so Undo is a rename back. What is on the
 * phone's shared folder cannot go there (another filesystem); the page asks
 * before deleting that for good.
 *
 * <p>Pure Java but for {@link Thumbs}, which the service gives it: the stand-in
 * tests on a computer run this very file.
 */
final class LinuxFiles {

    /** A folder of the phone's that Linux sees at another path (proot's {@code -b}). */
    static final class Mount {
        final String guest;
        final File host;
        /** What it is to a person: "shared" (the phone's shared folder), or "" for plumbing ({@code /tmp}). */
        final String role;

        Mount(String guest, File host, String role) {
            this.guest = guest;
            this.host = host;
            this.role = role == null ? "" : role;
        }
    }

    /** Where Linux is. Asked at every request: an install, a wipe or a grant changes it under a running server. */
    interface Layout {
        /** The guest's {@code /}, or null while there is none to show; {@link #state} says why. */
        File rootfs();

        /** "ready" while {@link #rootfs} is there; else "missing" or "installing". */
        String state();

        /** proot's binds as linux-rt.sh makes them now. */
        List<Mount> mounts();
    }

    /** Small pictures of photos, videos, PDFs and songs, made with Android's decoders; there are none on a computer's JVM. */
    interface Thumbs {
        /** A JPEG or PNG at most {@code size} pixels each way, or null when there is none to make. */
        byte[] make(File file, String kind, int size) throws IOException;
    }

    /** The first transfer began, or the last one ended: the phone stays awake between. */
    interface Busy {
        void busy(boolean on);
    }

    /** An answer other than yes: an HTTP status, and a word the page knows. */
    static final class Refusal extends Exception {
        final int status;
        final String code;

        Refusal(int status, String code) {
            super(code, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    /** A request's body: no more than its length, counted, so that the next request is found where this one ends. */
    static final class Body extends InputStream {
        private final InputStream in;
        private final Runnable alive;
        private long left;

        Body(InputStream in, long length, Runnable alive) {
            this.in = in;
            this.left = Math.max(0, length);
            this.alive = alive;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
                if (alive != null) alive.run();
            }
            return n;
        }

        long left() {
            return left;
        }

        /** What is left read away, when it is little: the connection can carry the next request. */
        boolean drain(long max) throws IOException {
            if (left > max) return false;
            byte[] scratch = new byte[8192];
            while (left > 0) {
                if (read(scratch, 0, scratch.length) < 0) return false;
            }
            return true;
        }
    }

    /** One request, as the server read it, its token already checked. */
    static final class Call {
        final String method;
        /** What comes after {@code /fs/}: "list", "get", "put", … */
        final String op;
        final Map<String, List<String>> params;
        final Map<String, String> headers;
        /** The body still unread (put, batch), or null. */
        final Body body;
        /** The browser's token: what a ticket made now is tied to. */
        final String token;
        /** The ticket it came in with, when that is how. */
        final Ticket ticket;
        /** Bytes moved: the server closes a transfer that stops moving (a laptop shut mid-download). */
        final Runnable alive;

        Call(String method, String op, Map<String, List<String>> params, Map<String, String> headers, Body body,
             String token, Ticket ticket, Runnable alive) {
            this.method = method;
            this.op = op;
            this.params = params;
            this.headers = headers;
            this.body = body;
            this.token = token;
            this.ticket = ticket;
            this.alive = alive != null ? alive : () -> { };
        }

        String param(String k) {
            List<String> v = params.get(k);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        List<String> all(String k) {
            List<String> v = params.get(k);
            return v == null ? Collections.<String>emptyList() : v;
        }

        long num(String k, long dflt) {
            String v = param(k);
            if (v == null) return dflt;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
    }

    /** What a browser may fetch by itself for a while: one file ("get"), or several as a ZIP ("zip"). */
    static final class Ticket {
        final String token;
        final String kind;
        final List<String> paths;
        final String name;
        final long until;

        Ticket(String token, String kind, List<String> paths, String name, long until) {
            this.token = token;
            this.kind = kind;
            this.paths = paths;
            this.name = name;
            this.until = until;
        }
    }

    static final String HOME = "/root";
    static final String TRASH = HOME + "/.local/share/Trash";
    static final String TRASH_FILES = TRASH + "/files";
    static final String TRASH_INFO = TRASH + "/info";

    /** The phone's own, bound into the guest by proot: not Linux's to show. */
    private static final Set<String> HIDDEN_TOP = new HashSet<>(Arrays.asList("proc", "sys", "dev"));
    /** A part file's name starts so: hidden from every listing, as Linux's own ".part" files would be. */
    static final String PART = ".lodpart-";
    /** Room kept free on the phone: a full flash takes Android down with it. */
    private static final long MARGIN = 256L << 20;
    private static final long TICKET_MS = 6 * 3600_000L;
    private static final int BUF = 256 * 1024;
    private static final int MAX_ZIP_ENTRIES = 500_000;
    private static final int MAX_FIND = 300;
    private static final long FIND_MS = 4000;
    private static final long THUMB_CACHE_BYTES = 24L << 20;

    /** At or past this, a size or an offset goes in ZIP64's fields. Not final: the test makes it small, to check them. */
    static volatile long zip64At = 0xFFFFFFFFL;

    private final Layout layout;
    private final Thumbs thumbs;
    private final Busy busyHook;
    private final AtomicInteger busy = new AtomicInteger();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** Two at a time: a folder of photos decoded at once would take the cores the desktop needs. */
    private final Semaphore thumbSlots = new Semaphore(2);
    private final LinkedHashMap<String, byte[]> thumbCache = new LinkedHashMap<>(64, 0.75f, true);
    private long thumbBytes;

    LinuxFiles(Layout layout, Thumbs thumbs, Busy busy) {
        this.layout = layout;
        this.thumbs = thumbs;
        this.busyHook = busy;
    }

    /** Transfers running now, for a test. */
    int busyCount() {
        return busy.get();
    }

    // ── one request ──

    /** What keeps the phone awake while it runs. */
    private static final Set<String> LONG = new HashSet<>(Arrays.asList(
            "get", "put", "batch", "zip", "move", "trash", "delete", "emptytrash"));

    /** What changes something, or hands out a ticket: never by GET. */
    private static final Set<String> WRITES = new HashSet<>(Arrays.asList(
            "ticket", "put", "unpart", "batch", "mkdir", "rename", "move", "trash", "untrash", "emptytrash", "delete"));

    /**
     * Answer {@code c} on {@code out}.
     *
     * @return whether the connection may carry another request: not when a
     *         body was left unread or a transfer was cut
     */
    boolean handle(Call c, OutputStream out, boolean keep) throws IOException {
        boolean counted = LONG.contains(c.op);
        if (counted && busy.incrementAndGet() == 1 && busyHook != null) busyHook.busy(true);
        try {
            // A change is never a GET: only the page's own POST and PUT, which
            // the server has already seen come from this origin.
            if (WRITES.contains(c.op) && !"POST".equals(c.method) && !"PUT".equals(c.method)) throw new Refusal(405, "method");
            switch (c.op) {
                case "info":
                    return json(out, 200, info(), keep);
                case "list":
                    return json(out, 200, list(c.param("path")), keep);
                case "get":
                    return get(c, out, keep);
                case "thumb":
                    return thumb(c, out, keep);
                case "ticket":
                    return json(out, 200, ticket(c), keep);
                case "put":
                    return put(c, out, keep);
                case "part":
                    return json(out, 200, part(c), keep);
                case "unpart":
                    return json(out, 200, unpart(c), keep);
                case "batch":
                    return batch(c, out, keep);
                case "zip":
                    return zip(c, out, keep);
                case "exists":
                    return json(out, 200, exists(c), keep);
                case "mkdir":
                    return json(out, 200, mkdir(c), keep);
                case "rename":
                    return json(out, 200, rename(c), keep);
                case "move":
                    return json(out, 200, move(c), keep);
                case "trash":
                    return json(out, 200, trash(c), keep);
                case "untrash":
                    return json(out, 200, untrash(c), keep);
                case "trashlist":
                    return json(out, 200, trashList(), keep);
                case "emptytrash":
                    return json(out, 200, emptyTrash(), keep);
                case "delete":
                    return json(out, 200, delete(c), keep);
                case "find":
                    return json(out, 200, find(c), keep);
                case "du":
                    return json(out, 200, du(c), keep);
                default:
                    throw new Refusal(404, "op");
            }
        } catch (Refusal r) {
            // A body left unread is the next request's start for all this
            // connection knows: it carries no more.
            boolean k = keep && (c.body == null || c.body.drain(64 * 1024));
            return json(out, r.status, "{\"error\":" + q(r.code) + "}", k);
        } finally {
            if (counted && busy.decrementAndGet() == 0 && busyHook != null) busyHook.busy(false);
        }
    }

    // ── where things are ──

    /** A path of Linux's, and the file on the phone that it is. */
    static final class Where {
        final String guest;
        final File host;

        Where(String guest, File host) {
            this.guest = guest;
            this.host = host;
        }
    }

    static List<String> parts(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static String join(List<String> comps) {
        return comps.isEmpty() ? "/" : "/" + String.join("/", comps);
    }

    static String parentOf(String guest) {
        int i = guest.lastIndexOf('/');
        return i <= 0 ? "/" : guest.substring(0, i);
    }

    static String nameOf(String guest) {
        return guest.substring(guest.lastIndexOf('/') + 1);
    }

    static String child(String dir, String name) {
        return "/".equals(dir) ? "/" + name : dir + "/" + name;
    }

    /** A name a file can have in Linux: not empty, not . or .., no slash or NUL, at most 255 bytes. */
    static boolean goodName(String n) {
        return n != null && !n.isEmpty() && !n.equals(".") && !n.equals("..") && n.indexOf('/') < 0
                && n.indexOf('\0') < 0 && n.getBytes(StandardCharsets.UTF_8).length <= 255;
    }

    /** One of this class's own files, which no listing shows; and the hard links proot keeps as hidden files. */
    static boolean internal(String name) {
        return name.startsWith(PART) || name.startsWith(".l2s.");
    }

    /** {@code comps} of the guest's path on the phone: under the innermost bind, or the rootfs; null for the hidden. */
    private static File hostOf(File rootfs, List<Mount> mounts, List<String> comps) {
        if (!comps.isEmpty() && HIDDEN_TOP.contains(comps.get(0))) return null;
        Mount best = null;
        int bestLen = -1;
        for (Mount m : mounts) {
            List<String> mp = parts(m.guest);
            if (mp.size() > bestLen && mp.size() <= comps.size() && comps.subList(0, mp.size()).equals(mp)) {
                best = m;
                bestLen = mp.size();
            }
        }
        File f = best != null ? best.host : rootfs;
        for (int i = Math.max(0, bestLen); i < comps.size(); i++) f = new File(f, comps.get(i));
        return f;
    }

    private Mount mountAt(String guest) {
        for (Mount m : layout.mounts()) {
            if (m.guest.equals(guest)) return m;
        }
        return null;
    }

    /** Inside one of proot's binds, rather than Linux's own rootfs. */
    private boolean underMount(String guest) {
        for (Mount m : layout.mounts()) {
            if (guest.startsWith(m.guest + "/")) return true;
        }
        return false;
    }

    /**
     * Where {@code path} is, every symbolic link on the way followed as Linux
     * follows it (the last one too, when {@code followLast}): absolute links
     * start again at the guest's {@code /}, relative ones from their folder,
     * and {@code ..} stops at {@code /}. A link proot made itself holds a path
     * of the phone's into the rootfs, which is read as the guest path it is.
     * Forty links at most, as Linux allows.
     */
    Where resolve(String path, boolean followLast) throws Refusal {
        File rootfs = layout.rootfs();
        if (rootfs == null) throw new Refusal(503, layout.state());
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0 || path.length() > 8192) {
            throw new Refusal(400, "path");
        }
        List<Mount> mounts = layout.mounts();
        String rootAbs = rootfs.getAbsolutePath().replace(File.separatorChar, '/');
        ArrayDeque<String> todo = new ArrayDeque<>(parts(path));
        List<String> done = new ArrayList<>();
        int links = 0;
        while (!todo.isEmpty()) {
            String c = todo.pollFirst();
            if (c.equals(".")) continue;
            if (c.equals("..")) {
                if (!done.isEmpty()) done.remove(done.size() - 1);
                continue;
            }
            done.add(c);
            if (todo.isEmpty() && !followLast) break;
            File h = hostOf(rootfs, mounts, done);
            if (h == null) throw new Refusal(404, "hidden");
            Path p;
            try {
                p = h.toPath();
            } catch (RuntimeException e) {
                // A name the phone's filesystem cannot hold (only ever on a computer's: "C:")
                throw new Refusal(400, "path");
            }
            if (!Files.isSymbolicLink(p)) continue;
            if (++links > 40) throw new Refusal(400, "loop");
            String target;
            try {
                target = Files.readSymbolicLink(p).toString();
            } catch (IOException e) {
                throw new Refusal(404, "link");
            }
            if (File.separatorChar != '/') target = target.replace(File.separatorChar, '/');
            done.remove(done.size() - 1);
            if (target.equals(rootAbs) || target.startsWith(rootAbs + "/")) {
                target = target.substring(rootAbs.length());
                if (target.isEmpty()) target = "/";
            }
            if (target.startsWith("/")) done.clear();
            List<String> t = parts(target);
            for (int i = t.size() - 1; i >= 0; i--) todo.addFirst(t.get(i));
        }
        File host = hostOf(rootfs, mounts, done);
        if (host == null) throw new Refusal(404, "hidden");
        return new Where(join(done), host);
    }

    private static BasicFileAttributes attrs(File f, boolean follow) {
        try {
            return follow ? Files.readAttributes(f.toPath(), BasicFileAttributes.class)
                    : Files.readAttributes(f.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static boolean exists(File f) {
        return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isDirNoFollow(File f) {
        return Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean executable(File f) {
        try {
            return Files.getPosixFilePermissions(f.toPath()).contains(PosixFilePermission.OWNER_EXECUTE);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Where dir(String path) throws Refusal {
        Where w = resolve(path, true);
        BasicFileAttributes a = attrs(w.host, true);
        if (a == null) throw new Refusal(404, "missing");
        if (!a.isDirectory()) throw new Refusal(409, "notdir");
        return w;
    }

    /**
     * Not to be renamed, moved or deleted from a browser: {@code /} and every
     * folder right under it (Linux itself), the home folder, the Trash's own
     * folders, and a bind's mount point (deleting the phone's shared folder
     * would empty it on the phone too).
     */
    private void guard(Where w) throws Refusal {
        String g = w.guest;
        if (g.equals("/") || parentOf(g).equals("/") || g.equals(HOME) || g.equals(TRASH) || g.equals(TRASH_FILES)
                || g.equals(TRASH_INFO) || g.equals(HOME + "/.local") || g.equals(HOME + "/.local/share")) {
            throw new Refusal(403, "protected");
        }
        for (Mount m : layout.mounts()) {
            if (m.guest.equals(g) || m.guest.startsWith(g + "/")) throw new Refusal(403, "protected");
        }
    }

    // ── looking ──

    /** The folders a person keeps things in, as Linux names them (~/.config/user-dirs.dirs). */
    private static final String[][] XDG = {
            {"desktop", "XDG_DESKTOP_DIR", "Desktop"},
            {"documents", "XDG_DOCUMENTS_DIR", "Documents"},
            {"downloads", "XDG_DOWNLOAD_DIR", "Downloads"},
            {"pictures", "XDG_PICTURES_DIR", "Pictures"},
            {"music", "XDG_MUSIC_DIR", "Music"},
            {"videos", "XDG_VIDEOS_DIR", "Videos"},
    };

    private Map<String, String> userDirs() {
        Map<String, String> named = new HashMap<>();
        try {
            Where w = resolve(HOME + "/.config/user-dirs.dirs", true);
            if (w.host.length() < 64 * 1024) {
                for (String line : new String(Files.readAllBytes(w.host.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0 || line.startsWith("#")) continue;
                    String v = line.substring(eq + 1).trim();
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                    v = v.replace("$HOME", HOME);
                    if (v.startsWith("/") && !v.equals(HOME)) named.put(line.substring(0, eq).trim(), v);
                }
            }
        } catch (Refusal | IOException ignored) {
            // no file: the usual names
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] x : XDG) out.put(x[0], named.containsKey(x[1]) ? named.get(x[1]) : HOME + "/" + x[2]);
        return out;
    }

    private boolean isDir(String guest) {
        try {
            return resolve(guest, true).host.isDirectory();
        } catch (Refusal r) {
            return false;
        }
    }

    private String info() throws Refusal {
        File rootfs = layout.rootfs();
        if (rootfs == null) throw new Refusal(503, layout.state());
        StringBuilder b = new StringBuilder("{\"home\":").append(q(HOME)).append(",\"places\":[");
        b.append("{\"id\":\"home\",\"path\":").append(q(HOME)).append('}');
        for (Map.Entry<String, String> e : userDirs().entrySet()) {
            if (isDir(e.getValue())) {
                b.append(",{\"id\":").append(q(e.getKey())).append(",\"path\":").append(q(e.getValue())).append('}');
            }
        }
        for (Mount m : layout.mounts()) {
            if (!m.role.isEmpty() && m.host.isDirectory()) {
                b.append(",{\"id\":").append(q(m.role)).append(",\"path\":").append(q(m.guest))
                        .append(",\"free\":").append(m.host.getUsableSpace()).append(",\"total\":").append(m.host.getTotalSpace())
                        .append('}');
            }
        }
        b.append(",{\"id\":\"root\",\"path\":\"/\"}");
        b.append(",{\"id\":\"trash\",\"path\":").append(q(TRASH_FILES)).append('}');
        b.append("],\"free\":").append(Math.max(0, rootfs.getUsableSpace() - MARGIN))
                .append(",\"total\":").append(rootfs.getTotalSpace()).append('}');
        return b.toString();
    }

    private String list(String path) throws Refusal {
        Where w = dir(path);
        String[] names = w.host.list();
        if (names == null) throw new Refusal(403, "denied");
        StringBuilder b = new StringBuilder(96 + names.length * 72);
        b.append("{\"path\":").append(q(w.guest)).append(",\"w\":").append(Files.isWritable(w.host.toPath()) ? 1 : 0)
                .append(",\"entries\":[");
        Set<String> seen = new HashSet<>();
        boolean first = true;
        for (String n : names) {
            if (internal(n) || ("/".equals(w.guest) && HIDDEN_TOP.contains(n))) continue;
            String e;
            try {
                e = entry(n, new File(w.host, n), child(w.guest, n));
            } catch (RuntimeException broken) {
                e = null; // one entry the phone cannot read is left out, not the folder
            }
            if (e == null) continue;
            seen.add(n);
            if (!first) b.append(',');
            first = false;
            b.append(e);
        }
        // A bind proot made its own mount point for, and took away again: a
        // running Linux shows the folder, so this does too.
        for (Mount m : layout.mounts()) {
            if (!parentOf(m.guest).equals(w.guest) || seen.contains(nameOf(m.guest)) || !m.host.isDirectory()) continue;
            String e = entry(nameOf(m.guest), m.host, m.guest);
            if (e == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append(e);
        }
        return b.append("]}").toString();
    }

    /**
     * One entry: {@code n} name, {@code t} d(irectory), f(ile), l(ink) or
     * o(ther), {@code s} size, {@code m} modified (ms), {@code lt} what a link
     * leads to (d, f, or x when nowhere), {@code v} a bind ("shared"), {@code x}
     * executable.
     */
    private String entry(String name, File f, String guest) {
        Mount m = mountAt(guest);
        if (m != null) f = m.host;
        BasicFileAttributes a = attrs(f, false);
        if (a == null) return null;
        char t;
        long size = a.size();
        long mtime = a.lastModifiedTime().toMillis();
        String lt = null;
        File real = f;
        if (a.isSymbolicLink()) {
            t = 'l';
            try {
                Where tw = resolve(guest, true);
                BasicFileAttributes ta = attrs(tw.host, false);
                if (ta == null) {
                    lt = "x";
                } else {
                    lt = ta.isDirectory() ? "d" : "f";
                    size = ta.size();
                    mtime = ta.lastModifiedTime().toMillis();
                    real = tw.host;
                }
            } catch (Refusal r) {
                lt = "x";
            }
        } else {
            t = a.isDirectory() ? 'd' : a.isRegularFile() ? 'f' : 'o';
        }
        StringBuilder e = new StringBuilder(96).append("{\"n\":").append(q(name)).append(",\"t\":\"").append(t).append('"');
        if (t != 'd' && !"d".equals(lt)) e.append(",\"s\":").append(size);
        e.append(",\"m\":").append(mtime);
        if (lt != null) e.append(",\"lt\":\"").append(lt).append('"');
        if (m != null) e.append(",\"v\":").append(q(m.role.isEmpty() ? "mount" : m.role));
        if ((t == 'f' || "f".equals(lt)) && executable(real)) e.append(",\"x\":1");
        return e.append('}').toString();
    }

    private String exists(Call c) throws Refusal {
        Where d = dir(c.param("dir"));
        StringBuilder b = new StringBuilder("{\"exists\":{");
        boolean first = true;
        for (String n : c.all("n")) {
            if (!goodName(n)) continue;
            BasicFileAttributes a = attrs(new File(d.host, n), false);
            if (a == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append(q(n)).append(":\"").append(a.isDirectory() ? 'd' : a.isSymbolicLink() ? 'l' : 'f').append('"');
        }
        return b.append("}}").toString();
    }

    /** Names under a folder that have {@code q} in them (or match it, with * and ?), looked for briefly, links not followed. */
    private String find(Call c) throws Refusal {
        Where start = dir(c.param("path"));
        String query = c.param("q");
        if (query == null || query.trim().isEmpty()) throw new Refusal(400, "q");
        query = query.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Pattern glob = null;
        if (query.indexOf('*') >= 0 || query.indexOf('?') >= 0) {
            StringBuilder re = new StringBuilder();
            for (char ch : query.toCharArray()) {
                if (ch == '*') re.append(".*");
                else if (ch == '?') re.append('.');
                else re.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
            }
            glob = java.util.regex.Pattern.compile(re.toString());
        }
        boolean dots = query.startsWith(".");
        long end = System.currentTimeMillis() + FIND_MS;
        ArrayDeque<Where> todo = new ArrayDeque<>();
        todo.add(start);
        StringBuilder b = new StringBuilder("{\"path\":").append(q(start.guest)).append(",\"results\":[");
        int found = 0;
        boolean more = false;
        while (!todo.isEmpty()) {
            if (System.currentTimeMillis() > end) {
                more = true;
                break;
            }
            Where d = todo.pollFirst();
            String[] names = d.host.list();
            if (names == null) continue;
            Arrays.sort(names);
            for (String n : names) {
                if (internal(n) || ("/".equals(d.guest) && HIDDEN_TOP.contains(n))) continue;
                File f = new File(d.host, n);
                String g = child(d.guest, n);
                BasicFileAttributes a = attrs(f, false);
                if (a == null) continue;
                String lower = n.toLowerCase(Locale.ROOT);
                if (glob != null ? glob.matcher(lower).matches() : lower.contains(query)) {
                    if (found == MAX_FIND) {
                        more = true;
                        todo.clear();
                        break;
                    }
                    String e = entry(n, f, g);
                    if (e != null) {
                        if (found > 0) b.append(',');
                        b.append(e, 0, e.length() - 1).append(",\"p\":").append(q(g)).append('}');
                        found++;
                    }
                }
                if (a.isDirectory() && (dots || !n.startsWith("."))) {
                    Mount m = mountAt(g);
                    todo.add(new Where(g, m != null ? m.host : f));
                }
            }
        }
        return b.append("],\"more\":").append(more).append('}').toString();
    }

    /** How much a folder holds, counted for a few seconds at most. */
    private String du(Call c) throws Refusal {
        Where w = resolve(c.param("path"), true);
        long[] n = new long[3];
        boolean[] cut = {false};
        long end = System.currentTimeMillis() + 5000;
        try {
            Files.walkFileTree(w.host.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > end) {
                        cut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    n[2]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    n[0] += attrs.size();
                    n[1]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new Refusal(403, "denied");
        }
        return "{\"bytes\":" + n[0] + ",\"files\":" + n[1] + ",\"dirs\":" + Math.max(0, n[2] - 1) + ",\"partial\":" + cut[0] + "}";
    }

    // ── bringing back ──

    /** One file, whole or a range of it: what a download, a preview and a video's seek all are. */
    private boolean get(Call c, OutputStream out, boolean keep) throws IOException, Refusal {
        String path = c.ticket != null ? c.ticket.paths.get(0) : c.param("path");
        Where w = resolve(path, true);
        BasicFileAttributes a = attrs(w.host, true);
        if (a == null) throw new Refusal(404, "missing");
        if (!a.isRegularFile()) throw new Refusal(409, "notfile");
        long size = a.size();
        long mtime = a.lastModifiedTime().toMillis();
        String name = nameOf(w.guest);
        boolean dl = "1".equals(c.param("dl"));
        String type = dl ? "application/octet-stream" : inlineType(name);
        String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(mtime) + "\"";
        long start = 0;
        long end = size - 1;
        int status = 200;
        String range = c.headers.get("range");
        String ifRange = c.headers.get("if-range");
        if (range != null && size > 0 && (ifRange == null || ifRange.equals(etag))) {
            long[] r = range(range, size);
            if (r == null) {
                head(out, 416, "application/json", 0, keep, "Content-Range", "bytes */" + size);
                out.flush();
                return keep;
            }
            start = r[0];
            end = r[1];
            status = 206;
        }
        long len = size == 0 ? 0 : end - start + 1;
        List<String> h = new ArrayList<>(Arrays.asList(
                "Accept-Ranges", "bytes",
                "ETag", etag,
                "Last-Modified", httpDate(mtime),
                "Cache-Control", "private, no-cache",
                "Content-Disposition", disposition(dl, name)));
        if (status == 206) {
            h.add("Content-Range");
            h.add("bytes " + start + "-" + end + "/" + size);
        }
        if ("application/pdf".equals(type)) {
            // The page's preview frames it; the browser's own viewer draws it.
            h.addAll(Arrays.asList("Content-Security-Policy", "frame-ancestors 'self'", "X-Frame-Options", "SAMEORIGIN"));
        } else {
            // Whatever is in it, it is never this page: an HTML file from
            // Linux opened here would otherwise run as the page, token and all.
            h.addAll(Arrays.asList("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data: blob:; "
                    + "media-src 'self' blob:; style-src 'unsafe-inline'; frame-ancestors 'self'", "X-Frame-Options", "SAMEORIGIN"));
        }
        head(out, status, type, len, keep, h.toArray(new String[0]));
        if (!"HEAD".equals(c.method) && len > 0) {
            try (FileInputStream in = new FileInputStream(w.host)) {
                in.getChannel().position(start);
                // Short of what was said (the file shrank): the connection
                // closes, and the browser knows the download did not finish.
                if (pump(in, out, len, c.alive, false) < len) return false;
            }
        }
        out.flush();
        return keep;
    }

    /** {@code bytes=a-b}, {@code a-} or {@code -n}, one range, inside {@code size}; null when it is not. */
    static long[] range(String header, long size) {
        String h = header.trim();
        if (!h.startsWith("bytes=") || h.indexOf(',') >= 0) return null;
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (a.isEmpty()) {
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, size - n);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(b));
            }
            if (start < 0 || start >= size || end < start) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String ticket(Call c) throws Refusal {
        String kind = c.param("kind");
        List<String> paths = c.all("p");
        if (!"get".equals(kind) && !"zip".equals(kind)) throw new Refusal(400, "kind");
        if (paths.isEmpty() || paths.size() > 10_000 || ("get".equals(kind) && paths.size() != 1)) {
            throw new Refusal(400, "paths");
        }
        for (String p : paths) resolve(p, false);
        long now = System.currentTimeMillis();
        if (tickets.size() > 256) tickets.values().removeIf(t -> t.until < now);
        if (tickets.size() > 4096) throw new Refusal(429, "tickets");
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String k = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String name = c.param("name");
        tickets.put(k, new Ticket(c.token, kind, new ArrayList<>(paths), name, now + TICKET_MS));
        return "{\"k\":" + q(k) + "}";
    }

    /** A ticket still good, or null. The server also asks whether its browser is still signed in. */
    Ticket ticket(String k) {
        if (k == null || k.length() < 20 || k.length() > 64) return null;
        Ticket t = tickets.get(k);
        if (t == null) return null;
        if (t.until < System.currentTimeMillis()) {
            tickets.remove(k);
            return null;
        }
        return t;
    }

    /** Every ticket of a browser that is signed out, gone. */
    void dropTickets() {
        tickets.clear();
    }

    private boolean thumb(Call c, OutputStream out, boolean keep) throws IOException, Refusal {
        Where w = resolve(c.param("path"), true);
        BasicFileAttributes a = attrs(w.host, true);
        if (a == null || !a.isRegularFile()) throw new Refusal(404, "missing");
        String kind = thumbKind(nameOf(w.guest));
        if (thumbs == null || kind == null) throw new Refusal(404, "nothumb");
        int size = (int) Math.max(48, Math.min(512, c.num("s", 256)));
        String key = w.host.getAbsolutePath() + "|" + a.size() + "|" + a.lastModifiedTime().toMillis() + "|" + size;
        byte[] img;
        synchronized (thumbCache) {
            img = thumbCache.get(key);
        }
        if (img == null) {
            boolean got;
            try {
                got = thumbSlots.tryAcquire(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new Refusal(503, "busy");
            }
            if (!got) throw new Refusal(503, "busy");
            try {
                synchronized (thumbCache) {
                    img = thumbCache.get(key);
                }
                if (img == null) {
                    try {
                        img = thumbs.make(w.host, kind, size);
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        img = null;
                    }
                    if (img == null) img = new byte[0];
                    synchronized (thumbCache) {
                        thumbCache.put(key, img);
                        thumbBytes += img.length + key.length();
                        Iterator<Map.Entry<String, byte[]>> it = thumbCache.entrySet().iterator();
                        while (thumbBytes > THUMB_CACHE_BYTES && it.hasNext()) {
                            Map.Entry<String, byte[]> old = it.next();
                            thumbBytes -= old.getValue().length + old.getKey().length();
                            it.remove();
                        }
                    }
                }
            } finally {
                thumbSlots.release();
            }
        }
        if (img.length == 0) throw new Refusal(404, "nothumb");
        String type = (img[0] & 0xff) == 0x89 ? "image/png" : "image/jpeg";
        head(out, 200, type, img.length, keep, "Cache-Control", "private, max-age=86400");
        if (!"HEAD".equals(c.method)) out.write(img);
        out.flush();
        return keep;
    }

    // ── sending ──

    /** The part file for one upload: its name, size and time in its name, so a resume never adds to another file's bytes. */
    private static File partFile(File dir, String name, long size, long mtime) {
        return new File(dir, PART + LinuxBrowser.hash(name + "\0" + size + "\0" + mtime).substring(0, 20));
    }

    /** Already there: the same size, and the same time to the two seconds a FAT folder keeps. */
    private static boolean same(File f, long size, long mtime) {
        BasicFileAttributes a = attrs(f, false);
        return a != null && a.isRegularFile() && a.size() == size && mtime > 0
                && Math.abs(a.lastModifiedTime().toMillis() - mtime) < 2000;
    }

    /**
     * One file, or the rest of one: {@code path} where it goes, {@code size}
     * its whole length, {@code off} where this body starts (what {@code part}
     * said arrived), {@code mtime} its time, {@code on} what to do when the
     * name is taken (fail, replace, rename).
     */
    private boolean put(Call c, OutputStream out, boolean keep) throws IOException, Refusal {
        String path = c.param("path");
        if (path == null) throw new Refusal(400, "path");
        long size = c.num("size", -1);
        long off = c.num("off", 0);
        long mtime = c.num("mtime", 0);
        String on = orElse(c.param("on"), "fail");
        long len = c.body == null ? 0 : c.body.left();
        if (size < 0 || off < 0 || off > size || len > size - off) throw new Refusal(400, "size");
        String name = nameOf(path);
        if (!goodName(name)) throw new Refusal(400, "name");
        Where dir = dir(parentOf(path));
        File dest = new File(dir.host, name);
        File part = partFile(dir.host, name, size, mtime);
        if (off == 0) {
            if (same(dest, size, mtime)) {
                boolean k = keep && (c.body == null || c.body.drain(1 << 20));
                return json(out, 200, "{\"done\":true,\"same\":true,\"path\":" + q(child(dir.guest, name)) + "}", k);
            }
            // Said before a byte is taken, not after the whole file.
            if ("fail".equals(on) && exists(dest)) throw new Refusal(409, "exists");
            if (dir.host.getUsableSpace() - MARGIN < size) throw new Refusal(507, "full");
            Files.deleteIfExists(part.toPath());
        } else {
            long have = exists(part) ? part.length() : 0;
            if (have != off) {
                boolean k = keep && c.body.drain(64 * 1024);
                return json(out, 409, "{\"error\":\"offset\",\"have\":" + have + "}", k);
            }
        }
        if (len > 0) {
            try (FileOutputStream fo = new FileOutputStream(part, true)) {
                if (!copyIn(c.body, fo, len)) return false; // the browser went: what came is kept, for a resume
            }
        } else if (!exists(part)) {
            Files.createFile(part.toPath());
        }
        long have = part.length();
        if (have < size) return json(out, 200, "{\"done\":false,\"have\":" + have + "}", keep);
        if (have > size) {
            Files.deleteIfExists(part.toPath());
            throw new Refusal(409, "toolong");
        }
        File fin = place(part, dest, on, mtime);
        if (fin == null) return json(out, 200, "{\"done\":true,\"skipped\":true}", keep);
        return json(out, 200, "{\"done\":true,\"path\":" + q(child(dir.guest, fin.getName())) + "}", keep);
    }

    private String part(Call c) throws Refusal {
        String path = c.param("path");
        if (path == null || !goodName(nameOf(path))) throw new Refusal(400, "path");
        Where dir = dir(parentOf(path));
        File part = partFile(dir.host, nameOf(path), c.num("size", -1), c.num("mtime", 0));
        return "{\"have\":" + (exists(part) ? part.length() : 0) + "}";
    }

    private String unpart(Call c) throws Refusal {
        String path = c.param("path");
        if (path == null || !goodName(nameOf(path))) throw new Refusal(400, "path");
        Where dir = dir(parentOf(path));
        File part = partFile(dir.host, nameOf(path), c.num("size", -1), c.num("mtime", 0));
        try {
            Files.deleteIfExists(part.toPath());
        } catch (IOException e) {
            throw new Refusal(403, "denied");
        }
        return "{\"ok\":true}";
    }

    /**
     * Many small files and folders in one body, into {@code dir}, each a record:
     * <pre>
     *   u8 kind     1 a file, 2 a folder, 0 the end
     *   u16 length  then the path under dir, UTF-8, '/' between names
     *   u64 size    (a file) then u64 its time in ms, then its bytes
     * </pre>
     * Big-endian. {@code on} says what a taken name gets: replace (folders
     * merge), rename, or skip. A file already there the same is not written
     * again.
     */
    private boolean batch(Call c, OutputStream out, boolean keep) throws IOException, Refusal {
        Where dir = dir(c.param("dir"));
        String on = orElse(c.param("on"), "replace");
        if (!on.equals("replace") && !on.equals("rename") && !on.equals("skip")) throw new Refusal(400, "on");
        long total = c.num("total", 0);
        if (total > 0 && dir.host.getUsableSpace() - MARGIN < total) throw new Refusal(507, "full");
        if (c.body == null) throw new Refusal(400, "body");
        DataInputStream in = new DataInputStream(c.body);
        int done = 0;
        int same = 0;
        long bytes = 0;
        StringBuilder skipped = new StringBuilder();
        StringBuilder renamed = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        try {
            while (true) {
                int kind = in.read();
                if (kind <= 0) break;
                if (kind != 1 && kind != 2) throw new Refusal(400, "record");
                byte[] pb = new byte[in.readUnsignedShort()];
                in.readFully(pb);
                String rel = new String(pb, StandardCharsets.UTF_8);
                long size = 0;
                long mtime = 0;
                if (kind == 1) {
                    size = in.readLong();
                    mtime = in.readLong();
                    if (size < 0 || size > c.body.left()) throw new Refusal(400, "size");
                }
                List<String> comps = relParts(rel);
                if (comps == null) {
                    skip(in, size);
                    add(failed, rel, "name");
                    continue;
                }
                File parent;
                try {
                    parent = mkdirs(dir.host, kind == 1 ? comps.subList(0, comps.size() - 1) : comps);
                } catch (Refusal r) {
                    skip(in, size);
                    add(failed, rel, r.code);
                    continue;
                }
                if (kind == 2) {
                    done++;
                    continue;
                }
                String name = comps.get(comps.size() - 1);
                File dest = new File(parent, name);
                if (same(dest, size, mtime)) {
                    skip(in, size);
                    same++;
                    continue;
                }
                if ("skip".equals(on) && exists(dest)) {
                    skip(in, size);
                    if (skipped.length() > 0) skipped.append(',');
                    skipped.append(q(rel));
                    continue;
                }
                byte[] r = new byte[6];
                random.nextBytes(r);
                File tmp = new File(parent, PART + "b" + Base64.getUrlEncoder().withoutPadding().encodeToString(r));
                boolean whole;
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    whole = copyIn(c.body, fo, size);
                } catch (Refusal | IOException e) {
                    Files.deleteIfExists(tmp.toPath());
                    throw e;
                }
                if (!whole) {
                    Files.deleteIfExists(tmp.toPath());
                    return false; // the browser went mid-way: nothing to tell it
                }
                File fin;
                try {
                    fin = place(tmp, dest, on, mtime);
                } catch (Refusal x) {
                    add(failed, rel, x.code);
                    continue;
                }
                if (fin != null && !fin.getName().equals(name)) add(renamed, rel, fin.getName());
                done++;
                bytes += size;
            }
        } catch (EOFException cut) {
            return false;
        }
        return json(out, 200, "{\"done\":" + done + ",\"same\":" + same + ",\"bytes\":" + bytes
                + ",\"skipped\":[" + skipped + "],\"renamed\":[" + renamed + "],\"failed\":[" + failed + "]}", keep);
    }

    private static void add(StringBuilder list, String a, String b) {
        if (list.length() > 0) list.append(',');
        list.append('[').append(q(a)).append(',').append(q(b)).append(']');
    }

    /** A path under the folder something is sent to: names only, none of them . or .., sixty-four deep at most. */
    static List<String> relParts(String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.length() > 4096) return null;
        List<String> out = new ArrayList<>();
        for (String s : rel.split("/", -1)) {
            if (!goodName(s)) return null;
            out.add(s);
        }
        return out.size() > 64 ? null : out;
    }

    /** Folders made as needed under {@code base}, none of them through a link: what is sent stays where it is sent. */
    private static File mkdirs(File base, List<String> comps) throws Refusal {
        File cur = base;
        for (String c : comps) {
            File next = new File(cur, c);
            BasicFileAttributes a = attrs(next, false);
            if (a == null) {
                if (!next.mkdir() && !isDirNoFollow(next)) throw new Refusal(403, "denied");
            } else if (!a.isDirectory()) {
                throw new Refusal(409, a.isSymbolicLink() ? "link" : "notdir");
            }
            cur = next;
        }
        return cur;
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        byte[] scratch = new byte[64 * 1024];
        while (n > 0) {
            int got = in.read(scratch, 0, (int) Math.min(scratch.length, n));
            if (got < 0) throw new EOFException();
            n -= got;
        }
    }

    /**
     * {@code n} bytes of a body into a file. False when the body ended first
     * (the browser went); a write that fails is the phone being full, said so.
     */
    private static boolean copyIn(InputStream in, OutputStream to, long n) throws IOException, Refusal {
        byte[] buf = new byte[BUF];
        while (n > 0) {
            int got = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (got < 0) return false;
            try {
                to.write(buf, 0, got);
            } catch (IOException e) {
                throw new Refusal(507, "full");
            }
            n -= got;
        }
        return true;
    }

    /**
     * A finished upload, {@code tmp}, into its place {@code dest}, as {@code on}
     * says when the name is taken.
     *
     * @return where it went, or null when skipped
     */
    private static File place(File tmp, File dest, String on, long mtime) throws IOException, Refusal {
        if (mtime > 0) {
            try {
                Files.setLastModifiedTime(tmp.toPath(), FileTime.fromMillis(mtime));
            } catch (IOException | RuntimeException ignored) {
                // a folder that keeps no times (the shared folder, at times): now will do
            }
        }
        if (exists(dest)) {
            switch (on) {
                case "replace":
                    if (isDirNoFollow(dest)) {
                        Files.deleteIfExists(tmp.toPath());
                        throw new Refusal(409, "isdir");
                    }
                    Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return dest;
                case "rename":
                    dest = freeName(dest.getParentFile(), dest.getName(), false);
                    break;
                case "skip":
                    Files.deleteIfExists(tmp.toPath());
                    return null;
                default:
                    Files.deleteIfExists(tmp.toPath());
                    throw new Refusal(409, "exists");
            }
        }
        for (int i = 0; ; i++) {
            try {
                Files.move(tmp.toPath(), dest.toPath());
                return dest;
            } catch (FileAlreadyExistsException e) {
                // taken in the moment between: the next free name
                if (i > 3 || "replace".equals(on)) throw e;
                dest = freeName(dest.getParentFile(), dest.getName(), false);
            }
        }
    }

    /** The extensions that are two: "backup.tar.gz" becomes "backup (2).tar.gz". */
    private static final String[] DOUBLE = {".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tar.lz4"};

    /** {@code name} in {@code dir}, or the first of "name (2)", "name (3)", … that is free. */
    static File freeName(File dir, String name, boolean folder) {
        File f = new File(dir, name);
        if (!exists(f)) return f;
        String base = name;
        String ext = "";
        if (!folder) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String d : DOUBLE) {
                if (lower.endsWith(d) && name.length() > d.length()) {
                    base = name.substring(0, name.length() - d.length());
                    ext = name.substring(name.length() - d.length());
                    break;
                }
            }
            if (ext.isEmpty()) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    base = name.substring(0, dot);
                    ext = name.substring(dot);
                }
            }
        }
        for (int i = 2; i < 100_000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!exists(c)) return c;
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    // ── folders and names ──

    private String mkdir(Call c) throws Refusal {
        String path = c.param("path");
        if (path == null || !goodName(nameOf(path))) throw new Refusal(400, "name");
        Where parent = dir(parentOf(path));
        File f = new File(parent.host, nameOf(path));
        if (exists(f)) {
            if (!"rename".equals(c.param("on"))) throw new Refusal(409, "exists");
            f = freeName(parent.host, f.getName(), true);
        }
        if (!f.mkdir()) throw new Refusal(403, "denied");
        return "{\"path\":" + q(child(parent.guest, f.getName())) + "}";
    }

    private String rename(Call c) throws Refusal {
        Where w = resolve(c.param("path"), false);
        guard(w);
        String name = c.param("name");
        if (!goodName(name)) throw new Refusal(400, "name");
        if (!exists(w.host)) throw new Refusal(404, "missing");
        File dest = new File(w.host.getParentFile(), name);
        try {
            if (exists(dest)) {
                // A folder that ignores case (the shared folder) says the new
                // name is there when it is this very file: through a third name.
                if (!Files.isSameFile(w.host.toPath(), dest.toPath())) throw new Refusal(409, "exists");
                File via = freeName(w.host.getParentFile(), PART + "r", false);
                Files.move(w.host.toPath(), via.toPath());
                Files.move(via.toPath(), dest.toPath());
            } else {
                Files.move(w.host.toPath(), dest.toPath());
            }
        } catch (IOException e) {
            throw new Refusal(403, "denied");
        }
        return "{\"path\":" + q(child(parentOf(w.guest), name)) + "}";
    }

    /**
     * Items into a folder: moved ({@code copy} unset), or copied. A move within
     * one filesystem is a rename, at once; across (into the shared folder) it
     * is a copy, then the original goes. {@code on}: rename (the default),
     * replace or skip.
     */
    private String move(Call c) throws Refusal {
        Where to = dir(c.param("dir"));
        boolean copy = "1".equals(c.param("copy"));
        String on = orElse(c.param("on"), "rename");
        StringBuilder moved = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (String p : c.all("p")) {
            Where w;
            try {
                w = resolve(p, false);
                if (!copy || w.guest.equals("/")) guard(w);
                if (!exists(w.host)) throw new Refusal(404, "missing");
                if (to.guest.equals(w.guest) || to.guest.startsWith(w.guest + "/")) throw new Refusal(409, "inside");
            } catch (Refusal r) {
                add(failed, p, r.code);
                continue;
            }
            String name = nameOf(w.guest);
            if (!copy && parentOf(w.guest).equals(to.guest)) continue; // where it is already
            File dest = new File(to.host, name);
            try {
                if (exists(dest)) {
                    if ("skip".equals(on)) continue;
                    if ("replace".equals(on) && !Files.isSameFile(w.host.toPath(), dest.toPath())) {
                        deleteTree(dest.toPath(), c.alive);
                    } else {
                        dest = freeName(to.host, name, isDirNoFollow(w.host));
                    }
                }
                if (copy) {
                    copyTree(w.host.toPath(), dest.toPath(), c.alive);
                } else {
                    moveTree(w.host.toPath(), dest.toPath(), c.alive);
                }
                add(moved, p, child(to.guest, dest.getName()));
            } catch (IOException e) {
                add(failed, p, e instanceof java.nio.file.AccessDeniedException ? "denied" : "io");
            }
        }
        return "{\"moved\":[" + moved + "],\"failed\":[" + failed + "]}";
    }

    private static void moveTree(Path from, Path to, Runnable alive) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException across) {
            copyTree(from, to, alive);
            deleteTree(from, alive);
        }
    }

    /** A copy of a file or a folder, links copied as links where the far side keeps them and left out where it does not. */
    private static void copyTree(Path from, Path to, Runnable alive) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                if (from.equals(file)) dest = to;
                try {
                    Files.copy(file, dest, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException e) {
                    if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    Files.copy(file, dest, LinkOption.NOFOLLOW_LINKS);
                }
                alive.run();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p, Runnable alive) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                if (alive != null) alive.run();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── the Trash ──

    /** The Trash's two folders, made when first needed, as Linux's file manager would. */
    private Where[] trashDirs() throws Refusal {
        Where home = dir(HOME);
        File files;
        File info;
        try {
            files = mkdirs(home.host, Arrays.asList(".local", "share", "Trash", "files"));
            info = mkdirs(home.host, Arrays.asList(".local", "share", "Trash", "info"));
        } catch (Refusal r) {
            throw new Refusal(409, "trash");
        }
        return new Where[]{new Where(TRASH_FILES, files), new Where(TRASH_INFO, info)};
    }

    private static final DateTimeFormatter DELETED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    /** Into Linux's Trash, a .trashinfo beside each saying where from and when (freedesktop.org's Trash spec). */
    private String trash(Call c) throws Refusal {
        Where[] t = trashDirs();
        StringBuilder done = new StringBuilder();
        StringBuilder cannot = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (String p : c.all("p")) {
            Where w;
            try {
                w = resolve(p, false);
                guard(w);
                if (!exists(w.host)) throw new Refusal(404, "missing");
                if (w.guest.startsWith(TRASH + "/")) throw new Refusal(409, "intrash");
            } catch (Refusal r) {
                add(failed, p, r.code);
                continue;
            }
            if (underMount(w.guest)) {
                // The phone's shared folder (or /tmp): not Linux's home, and on
                // a phone another filesystem. Deleted only when asked again.
                if (cannot.length() > 0) cannot.append(',');
                cannot.append(q(w.guest));
                continue;
            }
            String base = nameOf(w.guest);
            boolean moved = false;
            for (int i = 1; i < 10_000 && !moved; i++) {
                String id = i == 1 ? base : base + "." + i;
                if (!goodName(id + ".trashinfo")) id = "item." + i;
                File info = new File(t[1].host, id + ".trashinfo");
                File into = new File(t[0].host, id);
                if (exists(into)) continue;
                try {
                    Files.createFile(info.toPath());
                } catch (FileAlreadyExistsException e) {
                    continue;
                } catch (IOException e) {
                    break;
                }
                try {
                    Files.write(info.toPath(), ("[Trash Info]\nPath=" + trashPath(w.guest) + "\nDeletionDate="
                            + LocalDateTime.now().format(DELETED) + "\n").getBytes(StandardCharsets.UTF_8));
                    Files.move(w.host.toPath(), into.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    if (done.length() > 0) done.append(',');
                    done.append("{\"p\":").append(q(w.guest)).append(",\"id\":").append(q(id)).append('}');
                    moved = true;
                } catch (IOException e) {
                    // Another filesystem (the shared folder): it cannot be
                    // moved here, only deleted, and the page asks first.
                    info.delete();
                    if (cannot.length() > 0) cannot.append(',');
                    cannot.append(q(w.guest));
                    break;
                }
            }
        }
        return "{\"trashed\":[" + done + "],\"cannot\":[" + cannot + "],\"failed\":[" + failed + "]}";
    }

    /** {@code /root/a b} as a .trashinfo says it: every byte but the plain ones %-escaped. */
    static String trashPath(String guest) {
        StringBuilder b = new StringBuilder();
        for (byte x : guest.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "/-._~".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return b.toString();
    }

    static String unescape(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    b.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException ignored) {
                    // a lone %: itself
                }
            }
            byte[] u = new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8);
            b.write(u, 0, u.length);
            i += Character.charCount(c);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** What a .trashinfo says: where from, and when. */
    private static String[] trashInfo(File info) {
        String path = null;
        String when = "";
        try {
            for (String line : new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("Path=")) path = unescape(line.substring(5).trim());
                else if (line.startsWith("DeletionDate=")) when = line.substring(13).trim();
            }
        } catch (IOException ignored) {
            // unreadable: no way back for it
        }
        return new String[]{path, when};
    }

    private String trashList() throws Refusal {
        Where[] t = trashDirs();
        String[] names = t[0].host.list();
        StringBuilder b = new StringBuilder("{\"path\":").append(q(TRASH_FILES)).append(",\"entries\":[");
        boolean first = true;
        for (String n : names == null ? new String[0] : names) {
            if (internal(n)) continue;
            String e = entry(n, new File(t[0].host, n), child(TRASH_FILES, n));
            if (e == null) continue;
            String[] info = trashInfo(new File(t[1].host, n + ".trashinfo"));
            if (!first) b.append(',');
            first = false;
            b.append(e, 0, e.length() - 1).append(",\"id\":").append(q(n));
            if (info[0] != null) b.append(",\"o\":").append(q(info[0]));
            b.append(",\"d\":").append(q(info[1])).append('}');
        }
        return b.append("]}").toString();
    }

    /** Back from the Trash to where each came from, under another name if that one is taken now. */
    private String untrash(Call c) throws Refusal {
        Where[] t = trashDirs();
        StringBuilder done = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (String id : c.all("id")) {
            if (!goodName(id)) {
                add(failed, String.valueOf(id), "name");
                continue;
            }
            File item = new File(t[0].host, id);
            File info = new File(t[1].host, id + ".trashinfo");
            String from = trashInfo(info)[0];
            if (!exists(item) || from == null || !from.startsWith("/") || !goodName(nameOf(from))) {
                add(failed, id, "lost");
                continue;
            }
            try {
                Where parent = madeDir(parentOf(from));
                File dest = new File(parent.host, nameOf(from));
                if (exists(dest)) dest = freeName(parent.host, nameOf(from), isDirNoFollow(item));
                moveTree(item.toPath(), dest.toPath(), c.alive);
                info.delete();
                if (done.length() > 0) done.append(',');
                done.append(q(child(parent.guest, dest.getName())));
            } catch (Refusal r) {
                add(failed, id, r.code);
            } catch (IOException e) {
                add(failed, id, "io");
            }
        }
        return "{\"restored\":[" + done + "],\"failed\":[" + failed + "]}";
    }

    /** A folder of Linux's, made with every folder above it that is missing (a folder whose parent went to the Trash too). */
    private Where madeDir(String guest) throws Refusal {
        List<String> comps = parts(guest);
        int have = comps.size();
        Where w = null;
        while (have >= 0) {
            try {
                w = dir(join(comps.subList(0, have)));
                break;
            } catch (Refusal r) {
                if (have == 0 || r.status != 404) throw r;
                have--;
            }
        }
        File f = mkdirs(w.host, comps.subList(have, comps.size()));
        List<String> all = new ArrayList<>(parts(w.guest));
        all.addAll(comps.subList(have, comps.size()));
        return new Where(join(all), f);
    }

    private String emptyTrash() throws Refusal {
        Where[] t = trashDirs();
        int n = 0;
        for (Where d : t) {
            String[] names = d.host.list();
            for (String name : names == null ? new String[0] : names) {
                try {
                    deleteTree(new File(d.host, name).toPath(), null);
                    if (d == t[0]) n++;
                } catch (IOException ignored) {
                    // the rest still goes
                }
            }
        }
        return "{\"deleted\":" + n + "}";
    }

    /** For good, folders and all; a link is deleted, never what it points to. */
    private String delete(Call c) throws Refusal {
        StringBuilder done = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (String p : c.all("p")) {
            try {
                Where w = resolve(p, false);
                guard(w);
                if (!exists(w.host)) throw new Refusal(404, "missing");
                deleteTree(w.host.toPath(), c.alive);
                if (parentOf(w.guest).equals(TRASH_FILES)) {
                    new File(resolve(TRASH_INFO, true).host, nameOf(w.guest) + ".trashinfo").delete();
                }
                if (done.length() > 0) done.append(',');
                done.append(q(w.guest));
            } catch (Refusal r) {
                add(failed, p, r.code);
            } catch (IOException e) {
                add(failed, p, "io");
            }
        }
        return "{\"deleted\":[" + done + "],\"failed\":[" + failed + "]}";
    }

    // ── a ZIP, made on the way ──

    /** One entry: a file (its data from {@code file}) or a folder (no data). */
    private static final class Z {
        String name;
        byte[] nameBytes;
        File file;
        long size;
        long mtime;
        boolean dir;
        boolean exec;
        long offset;
        long crc;
        /** The local header has ZIP64's sizes; the central entry has ZIP64's size, offset or both. */
        boolean bigSize;
        boolean bigOffset;
    }

    private boolean zip(Call c, OutputStream out, boolean keep) throws IOException, Refusal {
        Ticket t = c.ticket;
        if (t == null || !"zip".equals(t.kind)) throw new Refusal(403, "ticket");
        List<Z> entries = new ArrayList<>();
        Set<String> tops = new HashSet<>();
        for (String p : t.paths) {
            Where w = resolve(p, true);
            String top = w.guest.equals("/") ? "Linux" : nameOf(w.guest);
            String unique = top;
            for (int i = 2; !tops.add(unique.toLowerCase(Locale.ROOT)); i++) unique = top + " (" + i + ")";
            gather(w, unique, entries, 0);
        }
        long total = layout(entries);
        String name = t.name != null && goodName(t.name) ? t.name : "Linux files.zip";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name += ".zip";
        head(out, 200, "application/zip", total, keep, "Content-Disposition", disposition(true, name),
                "Cache-Control", "no-store");
        if (!"HEAD".equals(c.method)) writeZip(entries, out, c.alive);
        out.flush();
        return keep;
    }

    /** {@code w} and, for a folder, all it holds; links to files as the files, links to folders left out (they loop). */
    private void gather(Where w, String zipPath, List<Z> out, int depth) throws Refusal {
        if (out.size() >= MAX_ZIP_ENTRIES) throw new Refusal(413, "toomany");
        BasicFileAttributes a = attrs(w.host, true);
        if (a == null) return;
        if (a.isRegularFile()) {
            Z z = new Z();
            z.name = zipPath;
            z.file = w.host;
            z.size = a.size();
            z.mtime = a.lastModifiedTime().toMillis();
            z.exec = executable(w.host);
            out.add(z);
            return;
        }
        if (!a.isDirectory() || depth > 128) return;
        Z d = new Z();
        d.name = zipPath + "/";
        d.dir = true;
        d.mtime = a.lastModifiedTime().toMillis();
        out.add(d);
        String[] names = w.host.list();
        if (names == null) return;
        Arrays.sort(names);
        for (String n : names) {
            if (internal(n) || ("/".equals(w.guest) && HIDDEN_TOP.contains(n))) continue;
            String g = child(w.guest, n);
            File f = new File(w.host, n);
            Mount m = mountAt(g);
            BasicFileAttributes ca = attrs(m != null ? m.host : f, false);
            if (ca == null) continue;
            if (ca.isSymbolicLink()) {
                try {
                    Where tw = resolve(g, true);
                    BasicFileAttributes ta = attrs(tw.host, false);
                    if (ta != null && ta.isRegularFile()) gather(new Where(g, tw.host), zipPath + "/" + n, out, depth + 1);
                } catch (Refusal ignored) {
                    // a link to nowhere
                }
            } else if (ca.isDirectory() || ca.isRegularFile()) {
                gather(new Where(g, m != null ? m.host : f), zipPath + "/" + n, out, depth + 1);
            }
        }
    }

    private static final int UT_LOCAL = 4 + 5;
    private static final int UT_CENTRAL = 4 + 5;

    /** Every entry's offset, ZIP64 where a size or an offset needs it; the whole length, to the byte. */
    static long layout(List<Z> entries) {
        long pos = 0;
        for (Z z : entries) {
            z.nameBytes = z.name.getBytes(StandardCharsets.UTF_8);
            z.offset = pos;
            z.bigSize = z.size >= zip64At;
            z.bigOffset = z.offset >= zip64At;
            pos += 30 + z.nameBytes.length + UT_LOCAL + (z.bigSize ? 20 : 0) + z.size;
        }
        long cdStart = pos;
        for (Z z : entries) pos += 46 + z.nameBytes.length + UT_CENTRAL + centralZip64(z);
        long cdSize = pos - cdStart;
        if (zip64End(entries.size(), cdStart, cdSize)) pos += 56 + 20;
        return pos + 22;
    }

    private static int centralZip64(Z z) {
        int fields = (z.bigSize ? 2 : 0) + (z.bigOffset ? 1 : 0);
        return fields == 0 ? 0 : 4 + 8 * fields;
    }

    private static boolean zip64End(int count, long cdStart, long cdSize) {
        return count >= 0xFFFF || cdStart >= zip64At || cdSize >= zip64At;
    }

    private static void writeZip(List<Z> entries, OutputStream out, Runnable alive) throws IOException {
        byte[] buf = new byte[BUF];
        long pos = 0;
        for (Z z : entries) {
            if (!z.dir) z.crc = crcOf(z.file, z.size, buf);
            Le h = new Le(64 + z.nameBytes.length);
            h.u32(0x04034b50);
            h.u16(z.bigSize ? 45 : 20);
            h.u16(0x0800); // names in UTF-8
            h.u16(0); // stored
            h.u32(dos(z.mtime));
            h.u32((int) z.crc);
            h.u32(z.bigSize ? 0xFFFFFFFF : (int) z.size);
            h.u32(z.bigSize ? 0xFFFFFFFF : (int) z.size);
            h.u16(z.nameBytes.length);
            h.u16(UT_LOCAL + (z.bigSize ? 20 : 0));
            h.bytes(z.nameBytes);
            if (z.bigSize) {
                h.u16(0x0001);
                h.u16(16);
                h.u64(z.size);
                h.u64(z.size);
            }
            unixTime(h, z.mtime);
            if (pos != z.offset) throw new IOException("zip layout: " + pos + " != " + z.offset);
            out.write(h.buf, 0, h.n);
            pos += h.n;
            if (!z.dir) {
                long sent;
                try (FileInputStream in = new FileInputStream(z.file)) {
                    sent = pump(in, out, z.size, alive, true);
                } catch (IOException e) {
                    // Gone since it was listed: zeros, so that the rest of the
                    // archive is still where its directory says.
                    sent = 0;
                }
                if (sent < z.size) zeros(out, z.size - sent, buf);
                pos += z.size;
            }
        }
        long cdStart = pos;
        for (Z z : entries) {
            Le h = new Le(96 + z.nameBytes.length);
            h.u32(0x02014b50);
            h.u16((3 << 8) | 45); // made on Unix, to ZIP 4.5
            h.u16(z.bigSize || z.bigOffset ? 45 : 20);
            h.u16(0x0800);
            h.u16(0);
            h.u32(dos(z.mtime));
            h.u32((int) z.crc);
            h.u32(z.bigSize ? 0xFFFFFFFF : (int) z.size);
            h.u32(z.bigSize ? 0xFFFFFFFF : (int) z.size);
            h.u16(z.nameBytes.length);
            h.u16(UT_CENTRAL + centralZip64(z));
            h.u16(0); // comment
            h.u16(0); // disk
            h.u16(0); // internal attributes
            int mode = z.dir ? 040755 : z.exec ? 0100755 : 0100644;
            h.u32((mode << 16) | (z.dir ? 0x10 : 0));
            h.u32(z.bigOffset ? 0xFFFFFFFF : (int) z.offset);
            h.bytes(z.nameBytes);
            if (z.bigSize || z.bigOffset) {
                h.u16(0x0001);
                h.u16(8 * ((z.bigSize ? 2 : 0) + (z.bigOffset ? 1 : 0)));
                if (z.bigSize) {
                    h.u64(z.size);
                    h.u64(z.size);
                }
                if (z.bigOffset) h.u64(z.offset);
            }
            unixTime(h, z.mtime);
            out.write(h.buf, 0, h.n);
            pos += h.n;
        }
        long cdSize = pos - cdStart;
        Le e = new Le(128);
        int n = entries.size();
        if (zip64End(n, cdStart, cdSize)) {
            long at = pos;
            e.u32(0x06064b50);
            e.u64(44);
            e.u16((3 << 8) | 45);
            e.u16(45);
            e.u32(0);
            e.u32(0);
            e.u64(n);
            e.u64(n);
            e.u64(cdSize);
            e.u64(cdStart);
            e.u32(0x07064b50);
            e.u32(0);
            e.u64(at);
            e.u32(1);
        }
        // Only what does not fit says "see ZIP64", as every reader expects.
        e.u32(0x06054b50);
        e.u16(0);
        e.u16(0);
        e.u16(Math.min(n, 0xFFFF));
        e.u16(Math.min(n, 0xFFFF));
        e.u32(cdSize >= zip64At ? 0xFFFFFFFF : (int) cdSize);
        e.u32(cdStart >= zip64At ? 0xFFFFFFFF : (int) cdStart);
        e.u16(0);
        out.write(e.buf, 0, e.n);
    }

    /** A file's CRC-32 over exactly {@code size} bytes, zeros after its end if it is shorter now. */
    private static long crcOf(File f, long size, byte[] buf) {
        CRC32 crc = new CRC32();
        long left = size;
        try (FileInputStream in = new FileInputStream(f)) {
            while (left > 0) {
                int got = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (got < 0) break;
                crc.update(buf, 0, got);
                left -= got;
            }
        } catch (IOException ignored) {
            // unreadable: zeros, as writeZip sends
        }
        if (left > 0) {
            Arrays.fill(buf, (byte) 0);
            while (left > 0) {
                int n = (int) Math.min(buf.length, left);
                crc.update(buf, 0, n);
                left -= n;
            }
        }
        return crc.getValue();
    }

    private static void zeros(OutputStream out, long n, byte[] buf) throws IOException {
        Arrays.fill(buf, (byte) 0);
        while (n > 0) {
            int k = (int) Math.min(buf.length, n);
            out.write(buf, 0, k);
            n -= k;
        }
    }

    /** The phone's local time, as MS-DOS kept it: from 1980, to two seconds. */
    private static int dos(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        int year = cal.get(Calendar.YEAR);
        if (year < 1980) return (1 << 21) | (1 << 16);
        if (year > 2107) year = 2107;
        int date = ((year - 1980) << 9) | ((cal.get(Calendar.MONTH) + 1) << 5) | cal.get(Calendar.DAY_OF_MONTH);
        int time = (cal.get(Calendar.HOUR_OF_DAY) << 11) | (cal.get(Calendar.MINUTE) << 5) | (cal.get(Calendar.SECOND) / 2);
        return (date << 16) | time;
    }

    /** The time in UTC too (the "UT" extra field), which Linux's and macOS's unzip read. */
    private static void unixTime(Le h, long ms) {
        h.u16(0x5455);
        h.u16(5);
        h.u8(1);
        h.u32((int) Math.max(0, Math.min(0xFFFFFFFFL, ms / 1000)));
    }

    /** Little-endian, for ZIP's headers. */
    private static final class Le {
        final byte[] buf;
        int n;

        Le(int cap) {
            buf = new byte[cap];
        }

        void u8(int v) {
            buf[n++] = (byte) v;
        }

        void u16(int v) {
            u8(v);
            u8(v >>> 8);
        }

        void u32(int v) {
            u16(v);
            u16(v >>> 16);
        }

        void u64(long v) {
            u32((int) v);
            u32((int) (v >>> 32));
        }

        void bytes(byte[] b) {
            System.arraycopy(b, 0, buf, n, b.length);
            n += b.length;
        }
    }

    // ── kinds of file ──

    private static final Map<String, String> TYPES = new HashMap<>();
    private static final Map<String, String> THUMB = new HashMap<>();

    private static void type(String type, String... exts) {
        for (String e : exts) TYPES.put(e, type);
    }

    private static void thumb(String kind, String... exts) {
        for (String e : exts) THUMB.put(e, kind);
    }

    static {
        type("image/jpeg", "jpg", "jpeg", "jfif");
        type("image/png", "png");
        type("image/gif", "gif");
        type("image/webp", "webp");
        type("image/avif", "avif");
        type("image/bmp", "bmp");
        type("image/svg+xml", "svg");
        type("image/x-icon", "ico");
        type("video/mp4", "mp4", "m4v");
        type("video/webm", "webm");
        type("video/quicktime", "mov");
        type("video/x-matroska", "mkv");
        type("video/ogg", "ogv");
        type("audio/mpeg", "mp3");
        type("audio/mp4", "m4a");
        type("audio/aac", "aac");
        type("audio/ogg", "ogg", "oga", "opus");
        type("audio/flac", "flac");
        type("audio/wav", "wav");
        type("audio/webm", "weba");
        type("application/pdf", "pdf");
        // Read as text however they would run: the page shows them, never runs them.
        type("text/plain; charset=utf-8", "txt", "text", "md", "markdown", "log", "csv", "tsv", "json", "jsonl", "xml",
                "yaml", "yml", "toml", "ini", "conf", "cfg", "env", "properties", "sh", "bash", "zsh", "fish", "py",
                "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "hpp", "cs", "java", "kt", "kts", "rs",
                "go", "rb", "php", "pl", "lua", "sql", "css", "scss", "less", "html", "htm", "vue", "svelte", "dart",
                "swift", "r", "gradle", "cmake", "mk", "diff", "patch", "srt", "vtt", "tex", "desktop", "service",
                "gitignore", "dockerfile", "makefile", "lock", "rst", "adoc", "bat", "ps1");
        thumb("image", "jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "heic", "heif", "avif");
        thumb("video", "mp4", "m4v", "webm", "mov", "mkv", "3gp");
        thumb("audio", "mp3", "m4a", "flac", "ogg", "opus");
        thumb("pdf", "pdf");
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        String e = dot >= 0 ? name.substring(dot + 1) : name;
        return e.toLowerCase(Locale.ROOT);
    }

    /** What a browser is told a file is when it shows it: what it plays or draws as such, and text for the rest it can read. */
    static String inlineType(String name) {
        String t = TYPES.get(ext(name));
        return t != null ? t : "application/octet-stream";
    }

    static String thumbKind(String name) {
        return THUMB.get(ext(name));
    }

    // ── HTTP ──

    private static String orElse(String v, String d) {
        return v == null || v.isEmpty() ? d : v;
    }

    static String q(String s) {
        return LinuxBrowserServer.jsonString(s == null ? "" : s);
    }

    /** A name as a download is given it: ASCII for an old browser, and the real one, UTF-8, for the rest (RFC 6266). */
    static String disposition(boolean attachment, String name) {
        StringBuilder ascii = new StringBuilder();
        for (char ch : name.toCharArray()) ascii.append(ch >= 0x20 && ch < 0x7f && ch != '"' && ch != '\\' ? ch : '_');
        StringBuilder enc = new StringBuilder();
        for (byte x : name.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                enc.append((char) c);
            } else {
                enc.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return (attachment ? "attachment" : "inline") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc;
    }

    private static String httpDate(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.format(new java.util.Date(ms));
    }

    private static boolean json(OutputStream out, int status, String body, boolean keep) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        head(out, status, "application/json", b.length, keep, "Cache-Control", "no-store",
                "Content-Security-Policy", "default-src 'none'");
        out.write(b);
        out.flush();
        return keep;
    }

    private static void head(OutputStream out, int status, String type, long length, boolean keep, String... more)
            throws IOException {
        StringBuilder h = new StringBuilder(512).append("HTTP/1.1 ").append(status).append(' ').append(reason(status))
                .append("\r\nContent-Type: ").append(type).append("\r\nContent-Length: ").append(length)
                .append("\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
                        + "\r\nCross-Origin-Resource-Policy: same-origin\r\n");
        for (int i = 0; i + 1 < more.length; i += 2) h.append(more[i]).append(": ").append(more[i + 1]).append("\r\n");
        h.append("Connection: ").append(keep ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    /**
     * {@code n} bytes from a file to the browser.
     *
     * @return how many there were: fewer when the file ended first
     */
    private static long pump(InputStream in, OutputStream out, long n, Runnable alive, boolean zipping) throws IOException {
        byte[] buf = new byte[BUF];
        long sent = 0;
        while (sent < n) {
            int got;
            try {
                got = in.read(buf, 0, (int) Math.min(buf.length, n - sent));
            } catch (IOException e) {
                if (zipping) break;
                throw e;
            }
            if (got < 0) break;
            out.write(buf, 0, got);
            sent += got;
            alive.run();
        }
        return sent;
    }
}
