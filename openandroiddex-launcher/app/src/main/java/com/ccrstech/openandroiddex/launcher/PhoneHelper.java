package com.ccrstech.openandroiddex.launcher;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Remote desktop's helper on the phone: what an app may not do, done as the
 * shell (adb's user) for the app, the way scrcpy's server does it
 * (doc/phone-remote.md, "The phone's helper").
 *
 * <ul>
 *   <li><b>Screen off.</b> The phone's own panel dark while the phone runs on
 *       for a computer (SurfaceControl's power mode, as scrcpy's screen off).
 *       No app may power the panel down and leave the phone awake.</li>
 *   <li><b>Input Android protects.</b> The computer's taps, drags, wheel and
 *       keys as real input events (InputManager's injectInputEvent). The
 *       app's own way, its accessibility service, may not press what Android
 *       keeps for a person at the phone or a declared accessibility tool:
 *       permission dialogs, the installer, sensitive Settings switches.</li>
 * </ul>
 *
 * <p><b>How it runs.</b> Never inside the app: started from adb's shell with
 * the app's own APK as its class path,
 * <pre>
 *   CLASSPATH=&lt;the app's base.apk&gt; app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper &lt;package&gt; &lt;port&gt; &lt;key&gt;
 * </pre>
 * by a computer on the cable once after each restart of the phone
 * ({@link PhoneHelperLink#startCommand}, which knows the port and the key), or
 * by the app itself through the phone's own Wireless debugging
 * ({@link PhoneHelperStart}) as {@code PhoneHelper <package> --register}: no
 * port or key on that command line, since that link may not even be adbd's;
 * the helper makes both and hands them to the app's {@link PhoneHelperDoor}
 * with Android's {@code content call}, which tells the door the caller is the
 * shell. It lives until the phone restarts, adbd stops (init takes adbd's
 * processes with it), or it is asked to go
 * ({@code PhoneHelper --quit <package>} from the shell). It uses nothing of the
 * app's but this file (and the door's constant names): no Context exists in
 * such a process.
 *
 * <p><b>Who it listens to.</b> Android's SELinux keeps the shell and an app
 * from each other's local sockets in both directions (connectto, untrusted_app
 * to shell and shell to untrusted_app, both denied on a Redmi Note 7; adb's own
 * forwards come from adbd, another domain). So the helper listens on a TCP
 * port of the loopback address, which every app may reach, and the app and the
 * helper each prove they hold the app's key (HMAC-SHA256 over the other's
 * random challenge) before a line goes. The key is the app's, kept in its
 * private files and given to the helper on its command line (the shell's and
 * root's to read, no app's: Android hides other users' processes). The app's
 * uid is also its Linux guest's, which could read the key: as with the stream
 * itself, a computer that can open the phone, the app and its Linux are
 * trusted alike; nothing else on the phone is.
 *
 * <p><b>The wire</b>, a line each way, after the handshake:
 * <pre>
 *                      ← helper 1 &lt;challenge&gt;
 *   → app &lt;proof&gt; &lt;challenge&gt;
 *                      ← ok &lt;proof&gt; on|off &lt;sdk&gt;   (or the end: a wrong proof)
 *   → off              ← off | error &lt;why&gt;      the panel dark, the phone still awake
 *   → on               ← on | error &lt;why&gt;
 *   → forget           ← on                     the computer is about to put the phone to sleep
 *                                                (its lock key): the panel is Android's
 *                      ← asleep                 (unasked) the phone fell asleep while dark: a person
 *                                                pressed its power button, and it was woken again
 *   → touch d|m|u x y                           a finger, in the screen's pixels (no answer)
 *   → scroll x y h v                            the wheel at x, y: AXIS_HSCROLL, AXIS_VSCROLL
 *   → key d|u &lt;keycode&gt; &lt;meta&gt;                a key, as KeyEvent has it
 *   → quit             ← bye                    an app with a newer helper: this one goes
 * </pre>
 * The handshake's three lines never change, whatever the version, so any
 * app can tell any helper to go.
 *
 * <p><b>Never left dark.</b> POWER_MODE_OFF is not sleep: Android still
 * believes the screen is on, so a panel left dark would look like a broken
 * phone. The panel is lit again when the app's connection ends for any reason
 * (the app's session over, the app killed or updated: the kernel closes the
 * socket either way), on {@code on}, and on {@code --quit}. A press of the
 * phone's power button while it is dark puts the phone to sleep, and the
 * helper wakes it at once ({@link #lookAwake}), so the press brings the screen
 * back as a person expects. A sleep the computer asks for itself is said first
 * ({@code forget}) and left alone. A finger down when the connection ends is
 * lifted.
 */
public final class PhoneHelper {

    /** The wire's version: what the helper's first line says. A change that breaks it bumps it. */
    static final int PROTOCOL = 1;
    /** The helper's own socket, for the shell only: one helper at a time, and its goodbye. */
    static final String LOCK_SUFFIX = ".helper-own";

    /** android.view.SurfaceControl power modes. */
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;
    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    /** How often a dark panel's phone is asked whether it is still awake. */
    private static final long AWAKE_LOOK_MS = 300;
    /** A connection that has not proved itself by then is dropped. */
    private static final int HANDSHAKE_MS = 3000;

    private final String pkg;
    private final Power power;
    private final Input input;
    private final Object lock = new Object();
    /** The connection that made the panel dark, or null: only its end lights the panel again. */
    private Conn owner;
    private volatile boolean quitting;
    /** The finger, while one is down: when it went down. Input's own. */
    private long touchDownAt = -1;
    private float touchX, touchY;
    /** What was put in, for {@code --state}: presses, scrolls and key events. */
    private volatile int touches, scrolls, keys;

    /** The panel's power, and whether Android has the phone awake: SurfaceControl's here, a stand-in in tests. */
    interface Power {
        void set(boolean on) throws Exception;

        boolean awake();

        /** Wakes the phone as its power button would (Android lights the panel itself then). */
        void wake();
    }

    /** Where input events go: InputManager's injectInputEvent here, a list in tests. */
    interface Input {
        boolean inject(InputEvent e);
    }

    PhoneHelper(String pkg, Power power, Input input) {
        this.pkg = pkg;
        this.power = power;
        this.input = input;
    }

    public static void main(String[] args) {
        if (args.length > 0 && ("--quit".equals(args[0]) || "--state".equals(args[0]))) {
            String pkg = args.length > 1 ? args[1] : "com.ccrstech.linuxondroid";
            System.out.println("helper: " + say(pkg + LOCK_SUFFIX, args[0].substring(2)));
            return;
        }
        boolean register = args.length == 2 && "--register".equals(args[1]);
        if (args.length < 3 && !register) {
            System.out.println("helper: usage: PhoneHelper <package> <port> <key> | PhoneHelper <package> --register");
            return;
        }
        String pkg = args[0];
        LocalServerSocket own;
        try {
            own = new LocalServerSocket(pkg + LOCK_SUFFIX);
        } catch (IOException e) {
            System.out.println("helper: already running (" + e.getMessage() + ")");
            return;
        }
        ServerSocket server;
        byte[] key;
        if (register) {
            // Started by the app itself, through the phone's own Wireless
            // debugging (PhoneHelperStart): nobody gave a port or a key, and the
            // adb link that started this carried none (it may not even have been
            // adbd's). The helper makes both and hands them to the app through
            // Android (PhoneHelperDoor), which tells the app who is calling.
            SecureRandom r = new SecureRandom();
            server = null;
            for (int i = 0; i < 40 && server == null; i++) {
                try {
                    server = new ServerSocket(42000 + r.nextInt(7000), 4, InetAddress.getLoopbackAddress());
                } catch (IOException taken) {
                    // another port
                }
            }
            if (server == null) {
                System.out.println("helper: no free port");
                return;
            }
            byte[] kb = new byte[32];
            r.nextBytes(kb);
            String k = hex(kb);
            String refused = tellApp(pkg, server.getLocalPort() + " " + k);
            if (refused != null) {
                System.out.println("helper: the app did not take the key (" + refused + ")");
                return;
            }
            key = k.getBytes(StandardCharsets.UTF_8);
        } else {
            int port = Integer.parseInt(args[1]);
            key = args[2].getBytes(StandardCharsets.UTF_8);
            try {
                server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                System.out.println("helper: port " + port + " is taken (" + e.getMessage() + ")");
                return;
            }
        }
        int port = server.getLocalPort();
        PhoneHelper h = new PhoneHelper(pkg, new Panel(), new Injector());
        System.out.println("helper: ready for " + pkg + " on Android " + Build.VERSION.SDK_INT + ", port " + port);
        System.out.flush();
        Thread shell = new Thread(() -> h.serveOwn(own), "helper-own");
        shell.setDaemon(true);
        shell.start();
        Thread watch = new Thread(h::watchAwake, "helper-awake");
        watch.setDaemon(true);
        watch.start();
        h.serveApp(server, key);
    }

    // ── the app ──

    private void serveApp(ServerSocket server, byte[] key) {
        while (!quitting) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                break;
            }
            Thread t = new Thread(() -> {
                Conn c = handshake(s, key);
                if (c != null) run(c);
            }, "helper-app");
            t.setDaemon(true);
            t.start();
        }
        lightBeforeGoing();
        System.exit(0);
    }

    /** The app's proof of the key, and the helper's back; null (and the socket closed) for anything else. Package-private for the tests. */
    Conn handshake(Socket s, byte[] key) {
        try {
            s.setSoTimeout(HANDSHAKE_MS);
            s.setTcpNoDelay(true);
            Conn c = new Conn(s.getInputStream(), s.getOutputStream(), s);
            String mine = challenge();
            c.say("helper " + PROTOCOL + " " + mine);
            BufferedReader in = c.reader();
            String line = in.readLine();
            String[] w = line == null ? new String[0] : line.split(" ");
            if (w.length != 3 || !"app".equals(w[0]) || !same(w[1], proof(key, "app", mine))) {
                System.out.println("helper: a connection without the app's key");
                s.close();
                return null;
            }
            c.say("ok " + proof(key, "helper", w[2]) + " " + (isDark() ? "off" : "on") + " " + Build.VERSION.SDK_INT);
            s.setSoTimeout(0);
            return c;
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /** One connection: its lines until it ends, then the panel lit again if it made it dark. */
    private void run(Conn c) {
        try {
            String line;
            BufferedReader in = c.reader();
            while ((line = in.readLine()) != null) {
                String reply = handle(c, line.trim());
                if (reply != null) c.say(reply);
                if (quitting) {
                    System.out.println("helper: quit by the app");
                    System.out.flush();
                    System.exit(0);
                }
            }
        } catch (IOException ignored) {
            // the app went: the same as a goodbye
        } finally {
            ended(c);
            c.close();
        }
    }

    /** One line from {@code c}: the answer to send, or null for none. Package-private for the tests. */
    String handle(Conn c, String line) {
        if (line.startsWith("touch ") || line.startsWith("scroll ") || line.startsWith("key ")) {
            try {
                inputLine(line);
            } catch (RuntimeException e) {
                // A malformed line is dropped: input has no answers.
            }
            return null;
        }
        switch (line) {
            case "off":
                synchronized (lock) {
                    if (!power.awake()) return "error asleep";
                    try {
                        power.set(false);
                    } catch (Throwable t) {
                        return "error " + oneLine(t);
                    }
                    owner = c;
                }
                return "off";
            case "on":
                synchronized (lock) {
                    if (owner != null) {
                        try {
                            if (power.awake()) power.set(true);
                        } catch (Throwable t) {
                            return "error " + oneLine(t);
                        }
                        owner = null;
                    }
                }
                return "on";
            case "forget":
                synchronized (lock) {
                    owner = null;
                }
                return "on";
            case "quit":
                // An app with a newer helper than this one: this one goes (its panel lit), and the
                // computer is shown the command for the new one. The handshake never changes, so
                // any later app can say this to any earlier helper.
                quitting = true;
                lightBeforeGoing();
                liftFinger();
                return "bye";
            default:
                return "error unknown " + line;
        }
    }

    /**
     * {@code c} ended: the panel lit again if {@code c} made it dark, and a
     * finger left down lifted. Should lighting fail, the panel stays on record
     * as {@code c}'s, and {@link #relightLeft} tries again.
     */
    void ended(Conn c) {
        c.gone = true;
        liftFinger();
        synchronized (lock) {
            // Under the lock throughout: a new connection darkening the panel meanwhile keeps it dark.
            if (owner == c) lightIfOurs();
        }
    }

    boolean isDark() {
        synchronized (lock) {
            return owner != null;
        }
    }

    /**
     * Lights the panel again if it is dark because of us and Android still has
     * the phone awake. The record of who darkened it goes only once that worked
     * (or Android has the phone asleep, and the panel is Android's): after a
     * failure the panel is still ours to light, a new connection is told it is
     * dark, and {@link #relightLeft} tries again.
     *
     * @return true when no panel of ours is left dark
     */
    private boolean lightIfOurs() {
        synchronized (lock) {
            if (owner == null) return true;
            try {
                if (power.awake()) power.set(true);
            } catch (Throwable t) {
                System.out.println("helper: could not light the panel again (" + t + "), trying again");
                return false;
            }
            owner = null;
            return true;
        }
    }

    /**
     * A panel left dark by a connection that has ended, because lighting it
     * failed then: tried again, at each look of the watch (every
     * {@link #AWAKE_LOOK_MS}). Package-private for the tests.
     */
    void relightLeft() {
        synchronized (lock) {
            if (owner != null && owner.gone) lightIfOurs();
        }
    }

    /** This helper goes, so nothing tries again after it: lighting the panel is tried a few times first. */
    private void lightBeforeGoing() {
        for (int i = 0; i < 5 && !lightIfOurs(); i++) sleep(200);
    }

    /**
     * While the panel is dark: Android putting the phone to sleep makes the
     * panel Android's again, and the connection that made it dark is told.
     * That sleep is a person's press of the power button (the computer's own
     * lock key says {@code forget} first): the person wanted the screen ON,
     * which it looked as if it was not, so the phone is woken again at once
     * and that one press brings the screen back. Without this the first press
     * would only put to sleep a phone that looked asleep already.
     */
    private void watchAwake() {
        while (!quitting) {
            sleep(AWAKE_LOOK_MS);
            lookAwake();
            relightLeft();
        }
    }

    /** Package-private for the tests. */
    void lookAwake() {
        Conn told;
        synchronized (lock) {
            if (owner == null) return;
            boolean awake;
            try {
                awake = power.awake();
            } catch (Throwable t) {
                return;
            }
            if (awake) return;
            told = owner;
            owner = null;
        }
        try {
            power.wake();
        } catch (Throwable t) {
            System.out.println("helper: could not wake the phone (" + t + ")");
        }
        told.say("asleep");
    }

    // ── input ──

    private synchronized void inputLine(String line) {
        String[] w = line.split(" ");
        long now = SystemClock.uptimeMillis();
        switch (w[0]) {
            case "touch": {
                char a = w[1].charAt(0);
                float x = Float.parseFloat(w[2]), y = Float.parseFloat(w[3]);
                int action;
                if (a == 'd') {
                    // A finger still down (its up lost) is lifted before the next goes down.
                    liftFinger();
                    touchDownAt = now;
                    touches++;
                    action = MotionEvent.ACTION_DOWN;
                } else if (touchDownAt < 0) {
                    return;
                } else {
                    action = a == 'u' ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                }
                touchX = x;
                touchY = y;
                input.inject(touch(touchDownAt, now, action, x, y));
                if (action == MotionEvent.ACTION_UP) touchDownAt = -1;
                return;
            }
            case "scroll": {
                float x = Float.parseFloat(w[1]), y = Float.parseFloat(w[2]);
                float h = Float.parseFloat(w[3]), v = Float.parseFloat(w[4]);
                MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
                pp[0].id = 0;
                pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
                MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
                pc[0].x = x;
                pc[0].y = y;
                pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
                pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
                input.inject(MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0,
                        1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0));
                scrolls++;
                return;
            }
            case "key": {
                int action = "d".equals(w[1]) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                int code = Integer.parseInt(w[2]);
                int meta = w.length > 3 ? Integer.parseInt(w[3]) : 0;
                input.inject(new KeyEvent(now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0, 0, InputDevice.SOURCE_KEYBOARD));
                keys++;
                return;
            }
            default:
        }
    }

    /** A finger down with no up: lifted where it was (the app went mid-touch). */
    private synchronized void liftFinger() {
        if (touchDownAt < 0) return;
        input.inject(touch(touchDownAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, touchX, touchY));
        touchDownAt = -1;
    }

    private static MotionEvent touch(long downAt, long now, int action, float x, float y) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
        pc[0].size = 1f;
        return MotionEvent.obtain(downAt, now, action, 1, pp, pc, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    // ── the key ──

    /** A fresh random challenge, as hex. */
    static String challenge() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    /** HMAC-SHA256 of who says it and the other side's challenge, under the key, as hex. Package-private for the link and the tests. */
    static String proof(byte[] key, String who, String challenge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal((who + " " + challenge).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same proof, compared in constant time. */
    static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    /**
     * The port and the key told to the app's door ({@link PhoneHelperDoor})
     * with Android's own {@code content call}, run as this helper's user:
     * Android gives the door the caller's uid, and the door takes a key from
     * the shell or root only. Null once the door took them; else what went
     * wrong.
     */
    static String tellApp(String pkg, String portAndKey) {
        try {
            Process p = new ProcessBuilder("content", "call", "--uri", "content://" + pkg + PhoneHelperDoor.SUFFIX,
                    "--method", PhoneHelperDoor.METHOD, "--arg", portAndKey).redirectErrorStream(true).start();
            StringBuilder said = new StringBuilder();
            Thread read = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null && said.length() < 4000) said.append(line).append('\n');
                } catch (IOException ignored) {
                    // ended
                }
            }, "helper-tell");
            read.setDaemon(true);
            read.start();
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                return "no answer from the app";
            }
            read.join(2000);
            String out = said.toString().trim();
            return out.contains(PhoneHelperDoor.METHOD + "=" + PhoneHelperDoor.OK) ? null : out;
        } catch (Exception e) {
            return e.toString();
        }
    }

    // ── the helper's own socket: its goodbye and its state, from the shell ──

    private void serveOwn(LocalServerSocket own) {
        while (!quitting) {
            try (LocalSocket s = own.accept()) {
                int uid = s.getPeerCredentials().getUid();
                if (uid != SHELL_UID && uid != ROOT_UID) continue;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line = in.readLine();
                OutputStream out = s.getOutputStream();
                if ("quit".equals(line)) {
                    quitting = true;
                    lightIfOurs();
                    liftFinger();
                    out.write("bye\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    System.out.println("helper: quit");
                    System.out.flush();
                    System.exit(0);
                }
                out.write(("panel " + (isDark() ? "off" : "on") + " touches " + touches + " scrolls " + scrolls
                        + " keys " + keys + " path " + Panel.path + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                sleep(200);
            }
        }
    }

    /** One line to the helper's own socket and its answer: the shell's own client. */
    private static String say(String name, String line) {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            return "no helper running (" + e.getMessage() + ")";
        }
    }

    // ── the panel and input themselves, as the shell ──

    /** SurfaceControl's power mode on every built-in panel, and PowerManager's awake. */
    static final class Panel implements Power {
        private Class<?> displayControl;
        private boolean displayControlResolved;
        private Object powerManager;
        /** Where the panel's tokens came from, for {@code --state}: SurfaceControl, DisplayControl, or a single display's call. */
        static volatile String path = "not-yet";

        @Override
        public synchronized void set(boolean on) throws Exception {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            int mode = on ? POWER_MODE_NORMAL : POWER_MODE_OFF;
            IBinder[] tokens = tokens(sc);
            if (tokens.length == 0) throw new IllegalStateException("no panel found");
            Method m = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            for (IBinder t : tokens) m.invoke(null, t, mode);
        }

        @Override
        public void wake() {
            // KEYCODE_WAKEUP, as adb's own `input keyevent` sends it: the same on every Android version.
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"input", "keyevent", "224"});
                p.waitFor();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized boolean awake() {
            try {
                if (powerManager == null) {
                    IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class).invoke(null, "power");
                    powerManager = Class.forName("android.os.IPowerManager$Stub")
                            .getMethod("asInterface", IBinder.class).invoke(null, b);
                }
                return (Boolean) powerManager.getClass().getMethod("isInteractive").invoke(powerManager);
            } catch (Throwable t) {
                // Unknown: taken as awake, so a dark panel is still lit again at the end.
                return true;
            }
        }

        /**
         * The built-in panels' tokens. Android 14 moved the two lookups out of
         * SurfaceControl into DisplayControl, in services.jar; which build has
         * which is a per-build fact, so SurfaceControl is asked first. The same
         * order as openandroiddex-wmd's Screen and scrcpy's Device.
         */
        private IBinder[] tokens(Class<?> sc) throws Exception {
            IBinder[] found = tokensFrom(sc);
            if (found.length > 0) {
                path = "SurfaceControl:" + found.length;
                return found;
            }
            Class<?> dc = displayControl();
            if (dc != null) {
                found = tokensFrom(dc);
                if (found.length > 0) {
                    path = "DisplayControl:" + found.length;
                    return found;
                }
            }
            // Android 10 to 13: the one built-in display.
            try {
                IBinder t = (IBinder) sc.getMethod("getInternalDisplayToken").invoke(null);
                if (t != null) {
                    path = "getInternalDisplayToken";
                    return new IBinder[]{t};
                }
            } catch (NoSuchMethodException ignored) {
            }
            // Android 8 and 9.
            try {
                IBinder t = (IBinder) sc.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
                if (t != null) {
                    path = "getBuiltInDisplay";
                    return new IBinder[]{t};
                }
            } catch (NoSuchMethodException ignored) {
            }
            path = "none";
            return new IBinder[0];
        }

        private static IBinder[] tokensFrom(Class<?> owner) {
            try {
                Method ids = owner.getMethod("getPhysicalDisplayIds");
                Method token = owner.getMethod("getPhysicalDisplayToken", long.class);
                long[] all = (long[]) ids.invoke(null);
                if (all == null) return new IBinder[0];
                List<IBinder> out = new ArrayList<>();
                for (long id : all) {
                    IBinder t = (IBinder) token.invoke(null, id);
                    if (t != null) out.add(t);
                }
                return out.toArray(new IBinder[0]);
            } catch (Throwable t) {
                return new IBinder[0];
            }
        }

        /**
         * {@code com.android.server.display.DisplayControl}, loaded from
         * services.jar, its JNI in libandroid_servers bound to that loader by
         * hand (loadLibrary0 is given the class, not a ClassLoader).
         *
         * <p>Lint's BlockedPrivateApi is about an app's own process, which
         * Android holds to the app's target SDK. This runs only in the
         * helper, an app_process the shell starts (never in the app), which
         * no target SDK governs: the way scrcpy's server reaches the same
         * class.
         */
        @android.annotation.SuppressLint("BlockedPrivateApi")
        private Class<?> displayControl() {
            if (displayControlResolved) return displayControl;
            displayControlResolved = true;
            try {
                Method create = Class.forName("com.android.internal.os.ClassLoaderFactory").getMethod(
                        "createClassLoader", String.class, String.class, String.class,
                        ClassLoader.class, int.class, boolean.class, String.class);
                ClassLoader loader = (ClassLoader) create.invoke(null, "/system/framework/services.jar",
                        null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
                Class<?> found = loader.loadClass("com.android.server.display.DisplayControl");
                Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                load.setAccessible(true);
                load.invoke(Runtime.getRuntime(), found, "android_servers");
                displayControl = found;
            } catch (Throwable t) {
                System.out.println("helper: no DisplayControl (" + t + ")");
            }
            return displayControl;
        }
    }

    /** InputManager's injectInputEvent, as the shell may call it: InputManagerGlobal from Android 14. */
    static final class Injector implements Input {
        private Object manager;
        private Method inject;

        @Override
        public synchronized boolean inject(InputEvent e) {
            try {
                if (inject == null) {
                    try {
                        manager = Class.forName("android.hardware.input.InputManagerGlobal")
                                .getMethod("getInstance").invoke(null);
                    } catch (ClassNotFoundException old) {
                        manager = Class.forName("android.hardware.input.InputManager")
                                .getMethod("getInstance").invoke(null);
                    }
                    inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                }
                // INJECT_INPUT_EVENT_MODE_ASYNC: a finger's moves never wait on the app they reach.
                return (Boolean) inject.invoke(manager, e, 0);
            } catch (Throwable t) {
                System.out.println("helper: input refused (" + t + ")");
                return false;
            }
        }
    }

    // ── small things ──

    /** One connection to the app. Package-private for the tests, which make them without a socket. */
    static class Conn {
        private final InputStream in;
        private final OutputStream out;
        private final java.io.Closeable socket;
        private BufferedReader reader;
        /** Ended (the app went): a panel still dark on its account is the watch's to light ({@link #relightLeft}). */
        volatile boolean gone;

        Conn(InputStream in, OutputStream out, java.io.Closeable socket) {
            this.in = in;
            this.out = out;
            this.socket = socket;
        }

        synchronized BufferedReader reader() {
            if (reader == null) reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader;
        }

        void say(String line) {
            try {
                synchronized (this) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
                // its reader sees the end
            }
        }

        void close() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String oneLine(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        return String.valueOf(c).replace('\n', ' ');
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
