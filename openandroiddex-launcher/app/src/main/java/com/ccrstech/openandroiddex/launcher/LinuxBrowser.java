package com.ccrstech.openandroiddex.launcher;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * "Open in a browser": Linux on a computer's screen, through any browser on the
 * same network, with nothing installed there. Design record:
 * doc/linux-browser.md.
 *
 * <p>This class is the feature's vocabulary, kept in files under
 * {@code files/browser/} because two processes read and write it: the Settings
 * page in the app's own process, and {@link LinuxBrowserServer} in
 * {@code :linux} beside the desktop. A file is current in both at once; a
 * SharedPreferences cache in each process is not. Every change that reads
 * before it writes (the code, the browsers let in) holds one lock, across
 * both processes ({@link #locked}), and every file is replaced whole.
 *
 * <ul>
 *   <li>{@code on}: the switch. The server listens while it is there.</li>
 *   <li>{@code code}: six digits, shown on the phone only, with when they
 *       were made and how many wrong codes have been tried against them. A
 *       code lets one browser in and is used up; {@link #CODE_TRIES} wrong
 *       ones from anywhere end it too, until the phone makes another.</li>
 *   <li>{@code pairing-until}: a code works only while the phone's card is
 *       showing it, and for {@link #PAIR_WINDOW_MS} after.</li>
 *   <li>{@code tokens}: the browsers let in, by the SHA-256 of each one's
 *       token (never the token itself), when, and what it said it was.</li>
 *   <li>{@code state}: one line from the server for the Settings page.</li>
 *   <li>{@code tls-key.der}, {@code tls-cert.der}: {@link LinuxBrowserCert}.</li>
 * </ul>
 *
 * <p>Why the code is guarded so: what it opens is the whole desktop, a
 * terminal in it, and six digits are a million guesses. Rate limits by
 * address do not bound that, since an app on the phone or a peer on the
 * network can come from as many addresses as it likes. A budget for the code
 * itself does: ten wrong guesses in its whole life, a one-in-a-hundred-thousand
 * chance, and only while its owner has the card open.
 *
 * <p>Pure Java, so the stand-in tests on a computer run this very file.
 */
final class LinuxBrowser {

    /**
     * The port. Fixed, so a bookmark and a browser's "continue" for this
     * phone's certificate keep working; and one that nothing on a phone
     * usually holds: the guest's sshd is 7022 for the same reason. When another
     * app has it, the server says so rather than moving: the page's token lives
     * in what the browser stores for this address and port, and whatever
     * answered there next would be handed it.
     */
    static final int PORT = 7443;

    /** Wrong codes, from anywhere, that use a code up. */
    static final int CODE_TRIES = 10;

    /** How long a code works after the phone last showed it. */
    static final long PAIR_WINDOW_MS = 10 * 60_000L;

    /** Within one process; the file lock below is between the two. */
    private static final Object LOCK = new Object();

    private final File dir;

    LinuxBrowser(File dir) {
        this.dir = dir;
    }

    File dir() {
        return dir;
    }

    // ── the switch ──

    boolean isOn() {
        return new File(dir, "on").exists();
    }

    void setOn(boolean on) {
        File f = new File(dir, "on");
        if (!on) {
            f.delete();
            return;
        }
        try {
            dir.mkdirs();
            f.createNewFile();
        } catch (IOException e) {
            DexLog.warn("browser", "cannot record the switch as on: " + e);
        }
    }

    // ── the code ──

    /** What a pairing asked for got. */
    enum Pair { OK, WRONG, USED_UP, CLOSED }

    /** The code and its count: "482913 <made at> <wrong tries>". */
    private static final class Code {
        final String digits;
        final long madeAt;
        final int wrong;

        Code(String digits, long madeAt, int wrong) {
            this.digits = digits;
            this.madeAt = madeAt;
            this.wrong = wrong;
        }
    }

    private Code readCode() {
        String[] p = read(new File(dir, "code")).trim().split("\\s+");
        if (p.length < 1 || !p[0].matches("\\d{6}")) return null;
        long made = 0;
        int wrong = 0;
        try {
            if (p.length > 1) made = Long.parseLong(p[1]);
            if (p.length > 2) wrong = Integer.parseInt(p[2]);
        } catch (NumberFormatException ignored) {
            // an older file: a code with nothing against it
        }
        return new Code(p[0], made, wrong);
    }

    private void writeCode(Code c) {
        write(new File(dir, "code"), c.digits + " " + c.madeAt + " " + c.wrong + "\n");
    }

    private Code freshCode() {
        Code c = new Code(String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000)),
                System.currentTimeMillis(), 0);
        writeCode(c);
        return c;
    }

    /** The code browsers are asked for now; made the first time it is asked for. */
    String code() {
        return locked(() -> {
            Code c = readCode();
            return (c != null ? c : freshCode()).digits;
        });
    }

    /** A new code: the old one stops working at once. */
    String rotateCode() {
        return locked(() -> freshCode().digits);
    }

    /** Too many wrong codes were tried against this one: it opens nothing until there is a new one. */
    boolean codeUsedUp() {
        return locked(() -> {
            Code c = readCode();
            return c != null && c.wrong >= CODE_TRIES;
        });
    }

    /** The phone is showing the code: it works from now until {@link #PAIR_WINDOW_MS} after. */
    void openPairing(long now) {
        write(new File(dir, "pairing-until"), (now + PAIR_WINDOW_MS) + "\n");
    }

    /** Codes stop working now: the switch went off, or a test says so. */
    void closePairing() {
        new File(dir, "pairing-until").delete();
    }

    boolean pairingOpen(long now) {
        try {
            return Long.parseLong(read(new File(dir, "pairing-until")).trim()) > now;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** What a try of a code got; when it was right, the browser it let in. */
    static final class Tried {
        final Pair pair;
        /** On OK: the token only that browser is given, and what it said it was. */
        final String token;
        final String label;

        Tried(Pair pair, String token, String label) {
            this.pair = pair;
            this.token = token;
            this.label = label;
        }
    }

    /**
     * One try of {@code given}: compared in constant time, digits only, and
     * counted. A right code is used up by the browser it lets in, which is let
     * in here, as {@code label}, under the same lock: a sign-out ({@link
     * #signOutAll}) is wholly before this, and the code it made is not the one
     * tried, or wholly after, and takes this browser's token with the rest.
     * Let in a step later, a browser that tried its code just before a
     * sign-out would outlive it.
     */
    Tried tryCode(String given, long now, String label) {
        return locked(() -> {
            Code c = readCode();
            if (c == null) c = freshCode();
            if (c.wrong >= CODE_TRIES) return new Tried(Pair.USED_UP, null, null);
            if (!pairingOpen(now)) return new Tried(Pair.CLOSED, null, null);
            String g = given == null ? "" : given.replaceAll("[^0-9]", "");
            if (MessageDigest.isEqual(g.getBytes(StandardCharsets.US_ASCII),
                    c.digits.getBytes(StandardCharsets.US_ASCII))) {
                freshCode();
                String clean = cleanLabel(label);
                return new Tried(Pair.OK, letIn(clean), clean);
            }
            Code more = new Code(c.digits, c.madeAt, c.wrong + 1);
            writeCode(more);
            return new Tried(more.wrong >= CODE_TRIES ? Pair.USED_UP : Pair.WRONG, null, null);
        });
    }

    // ── the browsers let in ──

    /** Let a browser in: its token, which only it is given. */
    String addToken(String label) {
        String clean = cleanLabel(label);
        return locked(() -> letIn(clean));
    }

    /** One line, as long as a card can show. */
    private static String cleanLabel(String label) {
        String clean = label == null ? "" : label.replaceAll("[\\t\\r\\n]", " ").trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    /** A new token, recorded by its hash; under {@link #locked}. */
    private String letIn(String clean) {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String line = hash(token) + "\t" + System.currentTimeMillis() + "\t" + clean + "\n";
        write(new File(dir, "tokens"), read(new File(dir, "tokens")) + line);
        return token;
    }

    boolean tokenValid(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) return false;
        byte[] want = hash(token).getBytes(StandardCharsets.US_ASCII);
        boolean found = false;
        for (String line : read(new File(dir, "tokens")).split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            // Every line compared, the same way, whatever the first ones said.
            found |= MessageDigest.isEqual(want, line.substring(0, tab).getBytes(StandardCharsets.US_ASCII));
        }
        return found;
    }

    /** One browser signs itself out. */
    void removeToken(String token) {
        if (token == null) return;
        String h = hash(token);
        locked(() -> {
            StringBuilder keep = new StringBuilder();
            for (String line : read(new File(dir, "tokens")).split("\n")) {
                if (line.isEmpty() || line.startsWith(h + "\t")) continue;
                keep.append(line).append('\n');
            }
            write(new File(dir, "tokens"), keep.toString());
            return null;
        });
    }

    int tokenCount() {
        int n = 0;
        for (String line : read(new File(dir, "tokens")).split("\n")) {
            if (line.indexOf('\t') > 0) n++;
        }
        return n;
    }

    /** Every browser out, and a new code, so none of them can come straight back. */
    void signOutAll() {
        locked(() -> {
            write(new File(dir, "tokens"), "");
            freshCode();
            return null;
        });
    }

    // ── what the server says it is doing ──

    /**
     * One line for the Settings page: {@code listening <port>},
     * {@code watching <port> <n> <address> <what>}, {@code failed port <port>},
     * {@code failed <why>}, {@code off}.
     */
    void setState(String line) {
        write(new File(dir, "state"), line + "\n");
    }

    String state() {
        return read(new File(dir, "state")).trim();
    }

    // ── where the phone can be reached ──

    /** An address the phone can be opened at, and what kind of network it is on. */
    static final class Address {
        final String ip;
        /** wifi, hotspot, wired, vpn, other */
        final String kind;

        Address(String ip, String kind) {
            this.ip = ip;
            this.kind = kind;
        }

        String url(int port) {
            return "https://" + ip + (port == 443 ? "" : ":" + port);
        }

        /**
         * What a person types: the address and the port, no scheme. The
         * server answers plain http on the same port with the way to https,
         * so this is enough, and it fits on one line of a phone.
         */
        String typed(int port) {
            return ip + (port == 443 ? "" : ":" + port);
        }

        @Override
        public String toString() {
            return kind + " " + ip;
        }
    }

    /**
     * Every address a computer near the phone can reach it at, best first:
     * Wi-Fi, then the phone's own hotspot, then a cable, then a VPN that
     * hands out private addresses (Tailscale's 100.64/10, say).
     *
     * <p>Mobile data never: a carrier's address is behind its NAT, where other
     * subscribers may be, or public, and a door into Linux is not to be opened
     * there. IPv4 only: it is what people can type, and every network here
     * has one. These are also the only addresses the server answers on
     * ({@link LinuxBrowserServer}): a connection that came in on any other,
     * whatever its own address, is closed unread.
     */
    static List<Address> addresses() {
        List<Address> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                String kind = kindOf(nic.getName());
                if (kind == null) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || !isLocalPeer(a)) continue;
                    out.add(new Address(a.getHostAddress(), kind));
                }
            }
        } catch (Exception e) {
            DexLog.warn("browser", "cannot list the network interfaces: " + e);
        }
        Collections.sort(out, (x, y) -> rank(x.kind) - rank(y.kind));
        return out;
    }

    private static int rank(String kind) {
        switch (kind) {
            case "wifi": return 0;
            case "hotspot": return 1;
            case "wired": return 2;
            case "vpn": return 3;
            default: return 4;
        }
    }

    /** What an interface's name says it is, or null for one never offered (mobile data). */
    static String kindOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("clat")
                || n.startsWith("v4-") || n.startsWith("seth") || n.startsWith("wwan") || n.startsWith("dummy")
                || n.startsWith("p2p") || n.startsWith("aware") || n.startsWith("nan")) {
            return null;
        }
        // Samsung's and most others' hotspot; wlan1 is the hotspot beside a Wi-Fi on wlan0.
        if (n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || n.equals("wlan1")) return "hotspot";
        if (n.startsWith("wlan")) return "wifi";
        if (n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) return "wired";
        if (n.startsWith("tun") || n.startsWith("tailscale") || n.startsWith("wg") || n.startsWith("ppp")) return "vpn";
        return "other";
    }

    /**
     * An address on a network of the phone's own: private (10/8, 172.16/12,
     * 192.168/16), link-local, the 100.64/10 that carriers and Tailscale share,
     * IPv6's unique-local and link-local. Not loopback: nothing on the phone
     * itself is a browser this is for, and every app on it can reach loopback.
     */
    static boolean isLocalPeer(InetAddress a) {
        if (a == null || a.isLoopbackAddress()) return false;
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        if (b.length == 16) {
            InetAddress v4 = mappedV4(a);
            if (v4 != null) return isLocalPeer(v4);
            return (b[0] & 0xfe) == 0xfc; // fc00::/7
        }
        return false;
    }

    /** ::ffff:a.b.c.d, which a dual-stack socket reports an IPv4 peer as, as a.b.c.d; else null. */
    static InetAddress mappedV4(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 16) return null;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return null;
        }
        if ((b[10] & 0xff) != 0xff || (b[11] & 0xff) != 0xff) return null;
        try {
            return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
        } catch (Exception e) {
            return null;
        }
    }

    // ── files ──

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code work}, with every other change to these files waiting: threads
     * here on the monitor, the other process on an fcntl lock of a file of
     * its own (never one that matters otherwise: closing any descriptor of a
     * locked file lets the lock go).
     */
    private <T> T locked(Callable<T> work) {
        synchronized (LOCK) {
            dir.mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(new File(dir, ".lock"), "rw");
                 FileLock ignored = raf.getChannel().lock()) {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Whole or not at all: a temporary file of this writer's own, then an
     * atomic rename over the old one. A reader in the other process sees the
     * old file or the new one, and two writers never share a temporary file.
     */
    private void write(File f, String content) {
        File tmp = new File(dir, f.getName() + "." + Long.toHexString(System.nanoTime())
                + Thread.currentThread().getId() + ".tmp");
        try {
            dir.mkdirs();
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            tmp.delete();
            DexLog.warn("browser", "cannot write " + f.getName() + ": " + e);
        }
    }
}
