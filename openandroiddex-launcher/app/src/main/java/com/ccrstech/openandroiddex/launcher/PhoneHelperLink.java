package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The app's half of {@link PhoneHelper}, which runs as the shell: the phone's
 * screen off for a computer, and the computer's input as real input events.
 *
 * <p><b>The link.</b> While a computer watches, a thread of its own connects
 * to the helper on the loopback address, at the port this app chose, and each
 * side proves it holds this app's key ({@link #keyFile}). Android's SELinux
 * keeps an app and the shell from each other's local sockets, in both
 * directions; a TCP port any app may reach, the key keeps everything but this
 * app and the helper out. Looked for again every few seconds while no helper
 * answers, so a helper started while a computer watches is found by itself.
 *
 * <p><b>Screen off.</b> Each computer's session says whether it wants the
 * phone's own screen dark ({@link #session}). The panel is dark while a live
 * session wants it and the phone is awake, and lit the moment none does: the
 * session over, the computer gone quiet (its session ends after
 * {@link PhoneStream}'s silence), the app's process gone (the helper's
 * connection ends with it, and the helper lights the panel then). A person at
 * the phone pressing its power button gets the screen back with that one press
 * (the helper wakes the phone it fell asleep), and the computer's wish for a
 * dark screen is dropped until the computer asks again ({@code asleep}).
 *
 * <p><b>Input.</b> {@link PhoneInput} gives the helper the computer's fingers,
 * wheel and keys while it is linked ({@link #input}), and uses its
 * accessibility service otherwise.
 */
final class PhoneHelperLink {

    /** How often a missing helper is looked for while a computer watches. */
    private static final long LOOK_AGAIN_MS = 3000;

    private static final Object LOCK = new Object();
    /** Each watching session, and whether it wants the panel dark. Under LOCK. */
    private static final Map<Object, Boolean> SESSIONS = new HashMap<>();
    private static Thread worker;
    private static Context app;
    /** The helper's connection while one is open, for input and sleepComing. Under LOCK. */
    private static Link link;
    /** "none" (no helper), "on" or "off": what the computer is told. */
    private static volatile String state = "none";
    /** The panel is dark because the worker asked; the worker's own. */
    private static volatile boolean dark;
    /** The helper's last answer to "on" that was not "on", logged once; the worker's own. */
    private static String onRefused;
    private static volatile Runnable changed = () -> { };
    /** sleepComing's asks, and how many the worker has passed on. Under LOCK. */
    private static long forgetAsked;
    private static long forgetDone;

    private PhoneHelperLink() {
    }

    /** Whoever shows the state (PhoneStream) is told of a change, on any thread. */
    static void onChange(Runnable r) {
        changed = r != null ? r : () -> { };
    }

    /**
     * {@code who} watches ({@code live}) and wants the panel dark or not; a
     * session that is not live any more is forgotten. Any thread; never waits
     * on the helper.
     */
    static void session(Context ctx, Object who, boolean live, boolean wantDark) {
        synchronized (LOCK) {
            if (app == null) app = ctx.getApplicationContext();
            Boolean was = live ? SESSIONS.put(who, wantDark) : SESSIONS.remove(who);
            if (!SESSIONS.isEmpty() && worker == null) startWorker();
            if (was == null || was != wantDark || !live) kick();
        }
    }

    /**
     * The computer is about to put the phone to sleep itself (its lock key):
     * the panel is Android's from here, and no session wants it dark any more.
     * Told to the helper before the key goes, or the helper would take the
     * sleep for a person's press and wake the phone again. A second at most.
     */
    static void sleepComing() {
        synchronized (LOCK) {
            for (Map.Entry<Object, Boolean> e : SESSIONS.entrySet()) e.setValue(false);
            if (link == null || !dark || worker == null) return;
            long mine = ++forgetAsked;
            kick();
            long until = System.currentTimeMillis() + 1000;
            while (forgetDone < mine && link != null) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** "none", "on" or "off", for the computer's window. */
    static String state() {
        return state;
    }

    /** Whether the helper is linked: the computer's input goes through it. */
    static boolean linked() {
        synchronized (LOCK) {
            return link != null;
        }
    }

    /**
     * Whether {@code who} still wants the panel dark: false once the person at
     * the phone took the screen back with its power button, until {@code who}
     * asks again. The window's choice follows this.
     */
    static boolean wants(Object who) {
        synchronized (LOCK) {
            return Boolean.TRUE.equals(SESSIONS.get(who));
        }
    }

    /** An input line for the helper ({@code touch}, {@code scroll}, {@code key}); false if no helper is linked. */
    static boolean input(String line) {
        Link l;
        synchronized (LOCK) {
            l = link;
        }
        return l != null && l.send(line);
    }

    /**
     * What a computer with adb runs once after each restart of the phone to
     * start the helper: the app's own APK as its class path, the port and the
     * key, the helper in a session of its own so the shell's end does not
     * take it. Null if the key cannot be made (no private storage).
     */
    static String startCommand(Context ctx) {
        String[] k = key(ctx);
        if (k == null) return null;
        String apk = ctx.getApplicationInfo().sourceDir;
        return "adb shell \"CLASSPATH=" + apk + " setsid nohup app_process / "
                + PhoneHelper.class.getName() + " " + ctx.getPackageName() + " " + k[0] + " " + k[1]
                + " >/dev/null 2>&1 </dev/null &\"";
    }

    /**
     * The start the app runs itself, over the phone's own Wireless debugging
     * (PhoneHelperStart): no port and no key on it, since that link may not
     * be adbd's; the helper makes both and hands them to PhoneHelperDoor. It
     * also lets the app switch Wireless debugging on by itself next time
     * (WRITE_SECURE_SETTINGS, which the shell may grant).
     *
     * <p>The shell waits until the helper leads a session of its own before
     * it says "started" and ends. That link runs adbd's plain shell service
     * (no shell protocol), and when that shell ends, adbd takes everything
     * still in its process group with it. A helper that has not yet run
     * setsid at that moment never starts. Seen on Android 17 (S25): an
     * {@code & echo started} start died 5 times out of 5 there, and the same
     * start over the shell protocol ({@code adb shell}, the computer's
     * command) lived 5 times out of 5. The wait ends at the first look in
     * practice, and after 3 s at most.
     */
    static String selfStartCommand(Context ctx) {
        String apk = ctx.getApplicationInfo().sourceDir;
        String pkg = ctx.getPackageName();
        return "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; CLASSPATH=" + apk
                + " setsid nohup app_process / " + PhoneHelper.class.getName() + " " + pkg
                + " --register >/dev/null 2>&1 </dev/null & " + DETACH_WAIT + "echo started";
    }

    /**
     * After {@code cmd &}: waits until that job leads its own session
     * (setsid has run), is gone or a zombie, or 3 s pass. Field 6 of
     * /proc/PID/stat is the session; the 4th word after the name's ") ".
     */
    static final String DETACH_WAIT = "p=$!; i=0; while [ $i -lt 150 ]; do"
            + " s=$(cat /proc/$p/stat 2>/dev/null) || break; s=${s##*) }; set -- $s;"
            + " [ \"$1\" = Z ] && break; [ \"$4\" = \"$p\" ] && break; sleep 0.02; i=$((i+1)); done; ";

    /**
     * The port and the key a helper made for itself, from the shell
     * (PhoneHelperDoor checked who called): kept as this app's, so the link
     * and any computer's start command go to the helper that runs.
     */
    static boolean adopt(Context ctx, String port, String key) {
        boolean kept;
        synchronized (PhoneHelperLink.class) {
            File f = keyFile(ctx);
            File part = new File(f.getPath() + ".part");
            try {
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room for the key");
                try (OutputStream out = new FileOutputStream(part)) {
                    out.write((port + " " + key + "\n").getBytes(StandardCharsets.UTF_8));
                }
                kept = part.renameTo(f);
            } catch (IOException e) {
                DexLog.warn("phone", "helper key from the helper", e);
                kept = false;
            }
        }
        if (kept) lookNow();
        return kept;
    }

    /** A watching session's worker looks for the helper now, not at its next look. */
    static void lookNow() {
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    /** Whether a computer watches: a session is live. */
    static boolean watched() {
        synchronized (LOCK) {
            return !SESSIONS.isEmpty();
        }
    }

    /** Whether a helper answers on the port with the key: linked now, or a handshake that works. */
    static boolean present(Context ctx) {
        if (linked()) return true;
        Link l = Link.open(ctx);
        if (l == null) return false;
        l.close();
        return true;
    }

    // ── the key ──

    /** The port and the key, in this app's private files: made once, kept for every helper after. */
    static File keyFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "phone-remote"), "helper-key");
    }

    /** {port, key}, made on first use; null if they cannot be kept. */
    static synchronized String[] key(Context ctx) {
        File f = keyFile(ctx);
        try {
            if (f.isFile()) {
                String[] w = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ");
                if (w.length == 2 && w[0].matches("\\d{4,5}") && w[1].matches("[0-9a-f]{64}")) return w;
            }
            SecureRandom r = new SecureRandom();
            // A port of its own, away from the door's (7443) and the ports phones commonly use.
            String port = String.valueOf(42000 + r.nextInt(7000));
            byte[] b = new byte[32];
            r.nextBytes(b);
            StringBuilder hex = new StringBuilder();
            for (byte x : b) hex.append(String.format("%02x", x & 0xff));
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            try (OutputStream out = new FileOutputStream(f)) {
                out.write((port + " " + hex + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return new String[]{port, hex.toString()};
        } catch (IOException e) {
            DexLog.warn("phone", "helper key", e);
            return null;
        }
    }

    // ── the helper's link, on the worker's thread while a computer watches ──

    /**
     * The app's side of the handshake on a connected socket: the helper's
     * challenge answered with this app's proof and a challenge of its own,
     * whose proof the helper must send back. {"on"|"off", sdk, the helper's
     * version} if it did; an IOException otherwise. Package-private for the
     * tests.
     */
    static String[] handshake(Socket s, BufferedReader in, byte[] key) throws IOException {
        s.setSoTimeout(3000);
        String[] hello = String.valueOf(in.readLine()).split(" ");
        if (hello.length != 3 || !"helper".equals(hello[0])) throw new IOException("no helper's hello");
        String mine = PhoneHelper.challenge();
        OutputStream out = s.getOutputStream();
        out.write(("app " + PhoneHelper.proof(key, "app", hello[2]) + " " + mine + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        String[] ok = String.valueOf(in.readLine()).split(" ");
        if (ok.length != 4 || !"ok".equals(ok[0]) || !PhoneHelper.same(ok[1], PhoneHelper.proof(key, "helper", mine))) {
            DexLog.warn("phone", "helper: the loopback port's answer is not the helper's");
            throw new IOException("not the helper");
        }
        s.setSoTimeout(0);
        return new String[]{ok[2], ok[3], hello[1]};
    }

    /** Wakes the worker: a session's wish changed. Under LOCK. */
    private static void kick() {
        if (link != null) link.lines.add(Link.KICK);
        LOCK.notifyAll();
    }

    private static void startWorker() {
        worker = new Thread(PhoneHelperLink::work, "phone-helper");
        worker.setDaemon(true);
        worker.start();
    }

    private static void work() {
        Link l = null;
        try {
            while (true) {
                boolean want;
                long forget;
                synchronized (LOCK) {
                    if (SESSIONS.isEmpty()) break;
                    want = SESSIONS.containsValue(true);
                    forget = forgetAsked > forgetDone ? forgetAsked : 0;
                }
                if (l == null) {
                    l = Link.open(app);
                    if (l == null) {
                        dark = false;
                        set("none");
                        synchronized (LOCK) {
                            forgetDone = forgetAsked;
                            LOCK.notifyAll();
                            if (!SESSIONS.isEmpty()) {
                                try {
                                    LOCK.wait(LOOK_AGAIN_MS);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        }
                        continue;
                    }
                    synchronized (LOCK) {
                        link = l;
                    }
                    dark = l.darkAtStart;
                    DexLog.step("phone", "the phone's helper is here (Android " + l.sdk + ")");
                    set(dark ? "off" : "on");
                    continue;
                }
                if (forget > 0) {
                    // The computer's own lock key is next: the panel is Android's.
                    String r = l.ask("forget");
                    dark = false;
                    set("on");
                    synchronized (LOCK) {
                        forgetDone = forget;
                        LOCK.notifyAll();
                    }
                    if (r == null) l = lost(l);
                    continue;
                }
                if (want && !dark && awake()) {
                    String r = l.ask("off");
                    if (r == null) {
                        l = lost(l);
                        continue;
                    }
                    if ("off".equals(r)) {
                        dark = true;
                        set("off");
                        DexLog.step("phone", "the phone's screen is off for the computer");
                    } else {
                        // Asleep, or refused: tried again when a session asks again, not in a loop.
                        DexLog.warn("phone", "helper: " + r);
                        dropWants();
                        set(state);
                    }
                } else if (!want && dark) {
                    String r = l.ask("on");
                    if (r == null) {
                        l = lost(l);
                        continue;
                    }
                    if ("on".equals(r)) {
                        dark = false;
                        onRefused = null;
                        set("on");
                        DexLog.step("phone", "the phone's screen is on again");
                    } else {
                        // The helper could not light it ("error ..."): still dark, and the
                        // computer is told so. Tried again at the next look below (a
                        // session's change, or 5 s), not in a loop; said once per answer.
                        if (!r.equals(onRefused)) DexLog.warn("phone", "helper: the phone's screen did not come on: " + r);
                        onRefused = r;
                        set("off");
                    }
                }
                // Until a session's wish changes, or the helper says something by itself.
                String said = l.next(5000);
                if (Link.GONE.equals(said)) {
                    l = lost(l);
                } else if ("asleep".equals(said)) {
                    DexLog.step("phone", "the phone's power button: its screen is the phone's again");
                    dark = false;
                    dropWants();
                    set("on");
                }
            }
        } finally {
            // Nobody watches: the panel lit again (the helper also does so when this connection ends).
            if (l != null) {
                if (dark) l.ask("on");
                l.close();
            }
            dark = false;
            boolean again;
            synchronized (LOCK) {
                link = null;
                worker = null;
                again = !SESSIONS.isEmpty();
                if (again) startWorker();
                LOCK.notifyAll();
            }
            if (!again) {
                set("none");
                // Wireless debugging off again later, if the app switched it on for this.
                PhoneHelperStart.computersGone(app);
            }
        }
    }

    private static Link lost(Link l) {
        l.close();
        synchronized (LOCK) {
            if (link == l) link = null;
            LOCK.notifyAll();
        }
        DexLog.warn("phone", "the phone's helper went away");
        dark = false;
        set("none");
        return null;
    }

    /** No session wants the panel dark any more (the person at the phone took it back). */
    private static void dropWants() {
        synchronized (LOCK) {
            for (Map.Entry<Object, Boolean> e : SESSIONS.entrySet()) e.setValue(false);
        }
    }

    private static boolean awake() {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private static void set(String s) {
        state = s;
        try {
            changed.run();
        } catch (RuntimeException e) {
            DexLog.warn("phone", "helper state", e);
        }
    }

    /** The helper's connection: lines out (input from any thread, asks from the worker) and what it says. */
    private static final class Link {
        static final String GONE = "\u0000gone";
        static final String KICK = "\u0000kick";
        private final Socket socket;
        private final OutputStream out;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        boolean darkAtStart;
        String sdk = "?";

        private Link(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        /** The helper on the loopback port, if one answers and proves it holds the key; else null. */
        static Link open(Context ctx) {
            String[] k = key(ctx);
            if (k == null) return null;
            byte[] key = k[1].getBytes(StandardCharsets.UTF_8);
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(k[0])), 1000);
                s.setTcpNoDelay(true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String[] ok = handshake(s, in, key);
                Link l = new Link(s);
                if (!String.valueOf(PhoneHelper.PROTOCOL).equals(ok[2])) {
                    // A helper from an earlier build of the app (an update keeps it running): told to go,
                    // so the computer is shown the command for this build's.
                    DexLog.warn("phone", "helper: version " + ok[2] + " is not this app's " + PhoneHelper.PROTOCOL + ": told to go");
                    l.send("quit");
                    s.close();
                    return null;
                }
                l.darkAtStart = "off".equals(ok[0]);
                l.sdk = ok[1];
                l.read(in);
                return l;
            } catch (IOException | RuntimeException e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                return null;
            }
        }

        private void read(BufferedReader in) {
            Thread r = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) lines.add(line.trim());
                } catch (IOException ignored) {
                    // closed
                }
                lines.add(GONE);
                // Gone between computers too: nothing is sent to a helper that is not there.
                synchronized (LOCK) {
                    if (link == this) link = null;
                }
            }, "phone-helper-read");
            r.setDaemon(true);
            r.start();
        }

        /** One line out, whole, from any thread. */
        boolean send(String line) {
            byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                try {
                    out.write(b);
                    out.flush();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }

        /**
         * Sends {@code line} and returns the helper's answer, or null if it
         * went. The worker's only: answers come back in order, and input lines
         * get none. An unasked "asleep" meanwhile is put back for the worker; a
         * kick is dropped (the worker looks again after every answer anyway).
         */
        String ask(String line) {
            if (!send(line)) return null;
            boolean slept = false;
            try {
                while (true) {
                    String l = lines.poll(3, TimeUnit.SECONDS);
                    if (l == null || GONE.equals(l)) {
                        if (l != null) lines.add(GONE);
                        return null;
                    }
                    if (KICK.equals(l)) continue;
                    if ("asleep".equals(l)) {
                        slept = true;
                        continue;
                    }
                    if (slept) lines.add("asleep");
                    return l;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        /** What the helper said by itself within {@code ms}, {@link #GONE} if it went, or null (a kick, or nothing). */
        String next(long ms) {
            try {
                String l = lines.poll(ms, TimeUnit.MILLISECONDS);
                return KICK.equals(l) ? null : l;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return GONE;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
