package com.ccrstech.openandroiddex.wmd;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.lang.reflect.Constructor;

/**
 * The desktop's display, and its picture for a computer's window — the
 * desktop's own video path, beside scrcpy (doc/dex-video.md).
 *
 * The computer runs this over adb, one process per desktop, exactly where it
 * would have run scrcpy's server, and the session is this process: the display
 * is made when it starts and goes when it ends — taking every window on it, as
 * scrcpy's does (DESTROY_CONTENT_ON_REMOVAL) — whether the computer stops it,
 * the cable is pulled or adbd restarts. It says what it made in scrcpy's own
 * words ("New display: WxH/dpi (id=N)", "Display resized: …"), which the
 * computer already reads.
 *
 * The picture goes to one computer at a time over an abstract socket that only
 * adb can reach (SO_PEERCRED: the shell, which adbd is, or root) — not the
 * launcher, whose uid is also the Linux guest's: this socket takes a mouse and
 * a keyboard. Its own process rather than the window daemon's, which the
 * launcher talks to: a fault in a video encoder should not take the window
 * chrome down with it, and the daemon must outlive every display.
 *
 * Usage: app_process / com.ccrstech.openandroiddex.wmd.Desktop WxH/dpi socket-name
 * Exits when its stdin closes — the computer's adb shell went away.
 */
public final class Desktop {

    /** The display's name, in dumpsys and to anything on the phone that asks. */
    static final String DISPLAY_NAME = "Open Android DeX";

    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    /**
     * How often the display is told someone is using it. It has its own power
     * state (OWN_DISPLAY_GROUP) and its own screen timeout, and a person reading
     * a page moves nothing; Android's shortest timeout is 15 seconds. scrcpy's
     * --keep-active, which this replaces.
     */
    private static final long AWAKE_EVERY_MS = 4000;

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*, the hidden ones by value
    private static final int FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    private static final int FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int FLAG_OWN_FOCUS = 1 << 14;
    private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;
    /** The flags that take a permission the shell may not hold on every build. */
    private static final int FLAGS_PRIVILEGED = FLAG_TRUSTED | FLAG_OWN_FOCUS | FLAG_ALWAYS_UNLOCKED;

    final DesktopVideo video;
    final DesktopInput input;
    /** Null where the shell may not use the clipboard. */
    final DesktopClipboard clipboard;
    final int displayId;
    private final VirtualDisplay display;
    /** The density the display was made at; `wm density` may put another over it. */
    private final int baseDpi;
    private volatile int width;
    private volatile int height;
    private DesktopSession current;
    private LocalServerSocket server;
    private String socketName;
    private volatile boolean stopping;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("ERROR: the desktop could not start: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: Desktop WxH/dpi socket-name");
            System.exit(2);
        }
        String[] sizeDpi = args[0].split("/");
        String[] wh = sizeDpi[0].split("x");
        int w = even(Integer.parseInt(wh[0]));
        int h = even(Integer.parseInt(wh[1]));
        int dpi = sizeDpi.length > 1 ? Integer.parseInt(sizeDpi[1]) : 160;
        // MediaCodec and the framework's managers make Handlers, which need a looper.
        Looper.prepareMainLooper();
        Desktop d = new Desktop(w, h, dpi, args[1]);
        d.keepAwake();
        d.watchStdin();
        Looper.loop();
    }

    private Desktop(int w, int h, int dpi, String socket) throws IOException {
        width = w;
        height = h;
        baseDpi = dpi;
        video = DesktopVideo.open(w, h);
        display = createDisplay(w, h, dpi);
        displayId = display.getDisplay().getDisplayId();
        input = new DesktopInput(displayId);
        clipboard = DesktopClipboard.open(text -> {
            DesktopSession s = current();
            if (s != null) s.clip(text);
        });
        // Listening before the display is announced: the computer opens its
        // window on that line, and the window connects at once.
        listen(socket);
        say("New display: " + w + "x" + h + "/" + dpi + " (id=" + displayId + ")");
    }

    private VirtualDisplay createDisplay(int w, int h, int dpi) {
        DisplayManager dm;
        try {
            Constructor<DisplayManager> make = DisplayManager.class.getDeclaredConstructor(Context.class);
            make.setAccessible(true);
            dm = make.newInstance(PhoneContext.get());
        } catch (ReflectiveOperationException e) {
            throw new Refl.WmError("no DisplayManager: " + e);
        }
        // scrcpy's flags for a new display, which every phone this desktop has
        // run on has been given — less system decorations, which the launcher
        // replaces. Trusted (input focus, freeform windows) from Android 11,
        // where the flag exists; scrcpy waits for 13, and a build that will not
        // let the shell have it is answered with the display without it.
        int flags = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH
                | FLAG_ROTATES_WITH_CONTENT | FLAG_DESTROY_CONTENT_ON_REMOVAL;
        if (Build.VERSION.SDK_INT >= 30) flags |= FLAG_TRUSTED;
        if (Build.VERSION.SDK_INT >= 33) {
            flags |= FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED | FLAG_TOUCH_FEEDBACK_DISABLED;
        }
        if (Build.VERSION.SDK_INT >= 34) flags |= FLAG_OWN_FOCUS | FLAG_DEVICE_DISPLAY_GROUP;
        try {
            return dm.createVirtualDisplay(DISPLAY_NAME, w, h, dpi, video.sink(), flags);
        } catch (SecurityException e) {
            log("the phone will not make a trusted display for the shell (" + e.getMessage()
                    + ") — making an untrusted one: windows on it may not take the keyboard");
            return dm.createVirtualDisplay(DISPLAY_NAME, w, h, dpi, video.sink(), flags & ~FLAGS_PRIVILEGED);
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** The density windows on the display see now: the window manager's, with any `wm density` over it. */
    int dpi() {
        try {
            Object d = Refl.callSig(Wm.windowManager(), "getBaseDisplayDensity", new Class<?>[]{int.class}, displayId);
            if (d instanceof Integer && (Integer) d > 0) return (Integer) d;
        } catch (Throwable ignored) {
            // a build without it: the one the display was made at
        }
        return baseDpi;
    }

    /**
     * The window is {@code w}x{@code h} pixels now, and so is the display: true
     * if it changed. The density stays — a display that follows its window
     * shows more of the desktop, not a bigger one; the computer moves the
     * density itself when the window goes to a screen of another scale.
     */
    synchronized boolean resize(int w, int h) throws IOException {
        if (w == width && h == height) return false;
        video.resize(w, h);
        display.resize(w, h, baseDpi);
        width = w;
        height = h;
        say("Display resized: " + w + "x" + h + "/" + dpi() + " (id=" + displayId + ")");
        return true;
    }

    // ── one computer at a time ──

    private void listen(String name) throws IOException {
        socketName = name;
        server = new LocalServerSocket(name);
        Thread accept = new Thread(this::acceptLoop, "desktop-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void acceptLoop() {
        while (!stopping) {
            LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!stopping) log("accept: " + e);
                return;
            }
            if (stopping) {
                closeQuietly(s);
                return;
            }
            int uid;
            try {
                Credentials c = s.getPeerCredentials();
                uid = c.getUid();
            } catch (IOException e) {
                closeQuietly(s);
                continue;
            }
            if (uid != SHELL_UID && uid != ROOT_UID) {
                log("refused a connection from uid " + uid);
                closeQuietly(s);
                continue;
            }
            new DesktopSession(this, s, uid).begin();
        }
    }

    private synchronized DesktopSession current() {
        return current != null && !current.closing ? current : null;
    }

    /** {@code s} is the session now; the one before it, if any, ends. */
    void claim(DesktopSession s) {
        DesktopSession old;
        synchronized (this) {
            old = current;
            current = s;
        }
        if (old != null && old != s) old.closeAll("replaced by a new session");
    }

    /** A session ended: if it was still the one, nobody is watching — true then. */
    boolean ended(DesktopSession s) {
        synchronized (this) {
            if (current != s) return false;
            current = null;
        }
        video.stop();
        input.releaseAll();
        return true;
    }

    // ── staying up ──

    /** Tell the display it is in use every few seconds, for as long as it exists. */
    private void keepAwake() {
        IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                "getService", new Class<?>[]{String.class}, "power");
        if (b == null) {
            log("no power service: the display may time out while nothing moves");
            return;
        }
        Object power = Refl.callStatic(Refl.cls("android.os.IPowerManager$Stub"),
                "asInterface", new Class<?>[]{IBinder.class}, b);
        Thread t = new Thread(() -> {
            // userActivity(displayId, time, event, flags) from Android 12, so
            // only the desktop's own display group wakes; before it, without
            // the display, the whole phone — which on those versions is the
            // same thing.
            boolean perDisplay = Refl.hasMethod(power.getClass(), "userActivity",
                    int.class, long.class, int.class, int.class);
            boolean said = false;
            while (!stopping) {
                try {
                    long now = SystemClock.uptimeMillis();
                    if (perDisplay) {
                        Refl.callSig(power, "userActivity",
                                new Class<?>[]{int.class, long.class, int.class, int.class}, displayId, now, 0, 0);
                    } else {
                        Refl.callSig(power, "userActivity",
                                new Class<?>[]{long.class, int.class, int.class}, now, 0, 0);
                    }
                } catch (Throwable e) {
                    if (!said) {
                        said = true;
                        log("cannot keep the display awake (" + e.getMessage() + ")");
                    }
                }
                SystemClock.sleep(AWAKE_EVERY_MS);
            }
        }, "desktop-awake");
        t.setDaemon(true);
        t.start();
    }

    private void watchStdin() {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[64];
                while (System.in.read(buf) >= 0) {
                    // nothing is sent this way; only its end means anything
                }
            } catch (IOException ignored) {
            }
            shutdown("the computer's adb shell closed");
        }, "desktop-stdin");
        t.setDaemon(true);
        t.start();
    }

    private void shutdown(String why) {
        synchronized (this) {
            if (stopping) return;
            stopping = true;
        }
        log("stopping: " + why);
        // Woken by a connection, then closed: closing alone does not wake a
        // thread blocked in accept() (see LinuxStream.shutdown).
        try (LocalSocket poke = new LocalSocket()) {
            poke.connect(new LocalSocketAddress(socketName));
        } catch (IOException ignored) {
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        DesktopSession s;
        synchronized (this) {
            s = current;
            current = null;
        }
        if (s != null) s.closeAll("the desktop is closing");
        video.stop();
        display.release();
        System.out.flush();
        System.exit(0);
    }

    // ── saying so ──

    /** A line the computer reads, in scrcpy's own words. */
    static void say(String line) {
        System.out.println("INFO: " + line);
        System.out.flush();
    }

    /** A line for the computer's log. */
    static void log(String line) {
        say("desktop: " + line);
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static int even(int v) {
        return v & ~1;
    }
}
