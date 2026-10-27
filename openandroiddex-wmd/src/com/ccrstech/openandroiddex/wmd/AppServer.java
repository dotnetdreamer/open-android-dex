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
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone's apps, each in a window of its own on the computer
 * (doc/apps-on-pc.md): the phone's half.
 *
 * <p>One process for the phone, started by the computer over adb and resident
 * for as long as the computer's app holds it — so opening an app costs a
 * display, a launch and an encoder (tens of milliseconds), never a process: the
 * JVM this process is took over a second to start on a Galaxy A13, and starting
 * one per app is what made scrcpy's per-app windows slow to open. Every window
 * is a connection of its own ({@link AppWindow}), each with its own display and
 * encoder, so one app's key frame never waits behind another's; one more
 * connection carries what belongs to the phone as a whole — the list of apps and
 * their icons, the clipboard, where its sound plays ({@link AppAudio}), its
 * notifications while the computer wants them ({@link AppNotifications}). A
 * file dropped on a window comes on a connection of its own too ({@link AppDrop}).
 *
 * <p>The socket is abstract and named afresh by the computer for every run, and
 * each connection's peer is put to the kernel (SO_PEERCRED): the shell, which
 * adbd is, and root. Not the launcher, whose uid is also the Linux guest's: this
 * socket takes a mouse and a keyboard into any app on the phone. The launcher's
 * touchpad, for the computer's windows, has a loopback port of its own while the
 * computer asks for it, and a token ({@link AppPad}).
 *
 * <p>Usage: {@code app_process / com.ccrstech.openandroiddex.wmd.AppServer socket-name}.
 * It stops when the computer says so — "stop" on its stdin, or on the control
 * connection — and every app goes back to the phone's own screen as it does.
 * The computer's adb shell closing is not the computer gone: the cable may
 * have been pulled with Wi-Fi still there. So the server is held by that
 * shell, by a control connection, or by any window's connection, and stops
 * only when none of them has held it for {@link AppWindow#AWAY_MS} — long
 * enough for the computer to come back another way to the same windows.
 */
public final class AppServer {

    /** What the displays are called, in dumpsys and to anything on the phone that asks. */
    static final String DISPLAY_NAME = "Open Android DeX app";
    /** The launcher's provider for its Linux apps (AppsProvider): the guest lives in its storage, not ours. */
    static final String LINUX_PROVIDER = WmDaemon.LAUNCHER + ".apps";

    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    /**
     * How often a window's display is told someone is using it. It has its own
     * power state (OWN_DISPLAY_GROUP, from Android 13) and screen timeout, and a
     * person reading a page moves nothing; Android's shortest timeout is 15 s.
     */
    private static final long AWAKE_EVERY_MS = 4000;

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*, the hidden ones by value
    private static final int FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    private static final int FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int FLAG_OWN_FOCUS = 1 << 14;
    private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;
    /** The flags that take a permission the shell may not hold on every build. */
    private static final int FLAGS_PRIVILEGED = FLAG_TRUSTED | FLAG_OWN_FOCUS | FLAG_ALWAYS_UNLOCKED;
    /** WindowManager.DISPLAY_IME_POLICY_HIDE: no on-screen keyboard; the computer's is the keyboard. */
    private static final int IME_POLICY_HIDE = 2;

    private static volatile boolean stopping;
    private static boolean trustedRefused;
    /** The computer's adb shell is still there: its stdin has not closed. */
    private static volatile boolean shellHeld = true;

    private final String socketName;
    private LocalServerSocket server;
    private DisplayManager displays;
    private volatile AppCatalog catalog;
    private final AppClipboard clipboard;
    /** What the clipboard, and with it the process's Context, took to make. */
    private final long clipboardMs;
    /** The phone's sound outputs, for the windows' menus; null where the shell may not ask. */
    private final AppAudio audio;
    private final List<AppWindow> windows = new CopyOnWriteArrayList<>();
    /** Every window from its first message until it has ended, app handed back: what stopping waits for. */
    private final List<AppWindow> running = new CopyOnWriteArrayList<>();
    /** Files on their way from the computer: stopping ends them too, and waits for their ".part" to go. */
    private final List<AppDrop> drops = new CopyOnWriteArrayList<>();
    /**
     * The keys of windows that ended while their computer was away — their app
     * opened on the phone, or the computer late — so a computer that comes back
     * for one is told it has gone, rather than opening it again.
     */
    private final java.util.Set<String> endedAway = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 64;
                }
            });
    private final AtomicInteger serials = new AtomicInteger();
    private volatile Wire control;
    /** A window has been asked for: what is left of the warm-up is its to pay. */
    private volatile boolean windowAsked;
    /** The warm-up has run, or is running: at start, or when the Apps page asked for it later. */
    private final java.util.concurrent.atomic.AtomicBoolean warmed = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * The phone's notifications, while the control connection that asked for
     * them is up ({@link #notifyingFor}); null otherwise.
     */
    private volatile AppNotifications notifications;
    /**
     * The control connection the phone's notifications are for. They stop when
     * it goes or another takes its place: a computer back on a new connection
     * asks again if it still wants them — and one that turned them off while
     * it was away, never reaching the phone to say so, is not read for.
     */
    private Wire notifyingFor;
    /**
     * What the computer's notifications ask of the phone, one at a time and off
     * the control connection's thread: a slow phone firing an intent, or posting
     * a test, must not hold up the clipboard or a list of apps behind it.
     */
    private final java.util.concurrent.ExecutorService notifying =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "apps-notify");
                t.setDaemon(true);
                return t;
            });
    /** The phone's screen as the touchpad of its windows on the computer, while the computer asks for it. */
    private final AppPad pad = new AppPad(this::toComputer);
    /**
     * The pad asked for and let go, one at a time and off the control
     * connection's thread: both fork `cmd` and may wake the launcher's process.
     */
    private final java.util.concurrent.ExecutorService padding =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "apps-pad-ask");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out.println("usage: AppServer socket-name [--no-warm-up]");
                System.exit(2);
            }
            // Started for a window that is already waiting (a pinned taskbar
            // button): the window pays the warm-up's costs itself, and the two
            // side by side would only slow it. Started for the phone's
            // notifications alone, it would sit beside a desktop that may be
            // starting: the Apps page asks for it when it is looked at ("warm").
            boolean warm = !(args.length > 1 && "--no-warm-up".equals(args[1]));
            // MediaCodec and the framework's managers make Handlers, which need a looper.
            Looper.prepareMainLooper();
            AppServer s = new AppServer(args[0]);
            instance = s;
            s.keepAwake();
            s.watchTasks();
            s.watchStdin();
            s.watchLease();
            say("apps ready on @" + args[0] + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")");
            log("ready " + sinceStart() + " ms after the process started; the clipboard and its Context took "
                    + s.clipboardMs + " ms");
            if (warm) s.warmUp();
            else log("no warm-up: started for a window that is waiting, or for the phone's notifications alone");
            Looper.loop();
        } catch (Throwable t) {
            System.out.println("ERROR: the app server could not start: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    /**
     * How long ago this process started, in ms: /proc's start time, in clock
     * ticks since boot. The framework's own (Process.getStartUptimeMillis) is
     * set for an app's process, never for app_process.
     */
    private static long sinceStart() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/stat")) {
            byte[] buf = new byte[1024];
            String stat = new String(buf, 0, Math.max(0, in.read(buf)), StandardCharsets.US_ASCII);
            // After "pid (name) " the fields count from the third: the start time is the 22nd.
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return SystemClock.elapsedRealtime() - Long.parseLong(f[19]) * 10;
        } catch (Exception e) {
            return -1;
        }
    }

    private AppServer(String socketName) throws IOException {
        this.socketName = socketName;
        long t0 = SystemClock.uptimeMillis();
        clipboard = AppClipboard.open();
        clipboardMs = SystemClock.uptimeMillis() - t0;
        if (clipboard != null) {
            clipboard.listen(text -> {
                Wire c = control;
                if (c == null) return;
                try {
                    c.json(new JSONObject().put("t", "clip").put("text", text));
                } catch (Exception ignored) {
                    // the computer's end went; the next control connection asks again
                }
            });
        }
        audio = AppAudio.open(this::audioChanged);
        AppDrop.sweep();
        server = new LocalServerSocket(socketName);
        Thread accept = new Thread(this::acceptLoop, "apps-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /**
     * Everything the first window would otherwise pay for, paid now, while
     * nobody waits: the GPU driver (eglInitialize loads it, a few hundred
     * milliseconds on a Galaxy A13), the codec list, one encoder made and let
     * go, the display and package managers. Measured on that phone, the first
     * window's display and encoder took 534 and 404 ms cold.
     *
     * <p>Once. A server the computer started only for the phone's
     * notifications is started without it (on a slow phone it would sit beside
     * a desktop that is starting); the Apps page asks for it ({@code warm})
     * when it is looked at.
     */
    private void warmUp() {
        if (warmed.getAndSet(true)) return;
        Thread t = new Thread(() -> {
            long t0 = SystemClock.uptimeMillis();
            try {
                // A window asked for already (an app opened from a pinned taskbar
                // button, with the server) pays these itself; doing them beside
                // it only slows it on a phone this slow.
                if (windowAsked) {
                    log("warm-up skipped: a window is opening");
                    return;
                }
                // A phone-sized window: what most first windows are.
                AppVideo v = AppVideo.open("warm", 400, 864);
                try {
                    v.start(1_000_000, 30, () -> 0, new com.ccrstech.openandroiddex.launcher.LinuxStreamEncoder.Output() {
                        @Override
                        public void packet(byte[] data, int len, long ptsUs, int flags) {
                        }

                        @Override
                        public void stopped(Throwable why) {
                        }
                    });
                } finally {
                    v.close();
                }
                createDisplayManager();
                // The list makes this itself when it is asked for.
                if (!windowAsked) catalog();
                log("warmed up in " + (SystemClock.uptimeMillis() - t0) + " ms");
            } catch (Throwable e) {
                log("warming up: " + e);
            }
        }, "apps-warm");
        t.setDaemon(true);
        t.start();
    }

    /** This computer's text, for the phone's next paste. */
    void setClipboard(String text) {
        if (clipboard != null) clipboard.set(text);
    }

    /** The phone's clipboard, which the windows' typing borrows; null where the shell has none. */
    AppClipboard clipboard() {
        return clipboard;
    }

    /** An output came or went: the computer's menus list what is there. */
    private void audioChanged() {
        Wire c = control;
        if (c != null) sendAudio(c);
    }

    /**
     * Where the sound can go, to the computer. Nothing about the phone's
     * sound may end the control connection it rides on: a phone that will
     * not say is a menu with the computer's half only.
     */
    private void sendAudio(Wire w) {
        if (audio == null) return;
        try {
            w.json(audio.state());
        } catch (Throwable t) {
            // the computer's end went, or the phone would not say; either way, nothing to send
        }
    }

    /**
     * Pin the phone's media to one of its outputs, or let the phone choose
     * again ({@code clear}): what the computer's menu asked. An unpin with
     * nothing pinned is no error — the audio service would call it one.
     */
    private JSONObject route(JSONObject j) throws Exception {
        JSONObject r = new JSONObject().put("t", "audioRouted");
        try {
            if (j.optBoolean("clear")) {
                if (!Audio.pinned().isEmpty()) Audio.clear();
            } else {
                Audio.route(j.getInt("type"), j.optString("address"));
            }
            r.put("ok", true);
        } catch (Throwable e) {
            log("the sound could not be moved (" + e.getMessage() + ")");
            r.put("ok", false).put("message", String.valueOf(e.getMessage()));
        }
        return r;
    }

    /**
     * The launcher's own record of where sound plays — what the desktop's
     * flyout and Settings show — told what the computer chose here. Best
     * effort, off the control connection's thread: a launcher not running yet
     * is a process to start, and one too old to know the method says so.
     */
    private static void tellLauncher(JSONObject choice) {
        Thread t = new Thread(() -> {
            try {
                JSONObject r = Providers.call(LINUX_PROVIDER, "media.mode", choice);
                if (!r.optBoolean("ok", false)) log("the launcher keeps its own sound setting (" + r.optString("message") + ")");
            } catch (Exception e) {
                log("the launcher keeps its own sound setting (" + e + ")");
            }
        }, "apps-audio-mode");
        t.setDaemon(true);
        t.start();
    }

    AppCatalog catalog() {
        AppCatalog c = catalog;
        if (c == null) {
            synchronized (this) {
                if (catalog == null) catalog = new AppCatalog();
                c = catalog;
            }
        }
        return c;
    }

    // ── displays ──

    /**
     * A display for one app window, drawing into {@code sink}: trusted where
     * the shell may make one (input focus, the keyboard), its own power group
     * and unlocked from Android 13, its own focus from 14. {@code trusted[0]}
     * says which it got.
     */
    private synchronized DisplayManager createDisplayManager() {
        if (displays == null) {
            try {
                Constructor<DisplayManager> make = DisplayManager.class.getDeclaredConstructor(Context.class);
                make.setAccessible(true);
                displays = make.newInstance(PhoneContext.get());
            } catch (ReflectiveOperationException e) {
                throw new Refl.WmError("no DisplayManager: " + e);
            }
        }
        return displays;
    }

    synchronized VirtualDisplay createDisplay(int w, int h, int dpi, Surface sink, boolean[] trusted) {
        DisplayManager displays = createDisplayManager();
        // scrcpy's flags for a new display, less three: no system decorations
        // (no launcher, no navigation bar — the window is the app), no rotating
        // with the content (an app that wants landscape is letterboxed until
        // the window is turned, which the computer does), and no destroying the
        // content when it goes (the app goes back to the phone).
        int flags = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH;
        if (Build.VERSION.SDK_INT >= 30) flags |= FLAG_TRUSTED;
        if (Build.VERSION.SDK_INT >= 33) {
            flags |= FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED | FLAG_TOUCH_FEEDBACK_DISABLED;
        }
        if (Build.VERSION.SDK_INT >= 34) flags |= FLAG_OWN_FOCUS | FLAG_DEVICE_DISPLAY_GROUP;
        if (!trustedRefused) {
            try {
                VirtualDisplay d = displays.createVirtualDisplay(DISPLAY_NAME, w, h, dpi, sink, flags);
                trusted[0] = (flags & FLAG_TRUSTED) != 0;
                return d;
            } catch (SecurityException e) {
                trustedRefused = true;
                log("the phone will not make a trusted display for the shell (" + e.getMessage()
                        + ") — making untrusted ones: a window takes the keyboard once it has been clicked");
            }
        }
        trusted[0] = false;
        return displays.createVirtualDisplay(DISPLAY_NAME, w, h, dpi, sink, flags & ~FLAGS_PRIVILEGED);
    }

    /** No on-screen keyboard for the display: where it may say so, a trusted display (Android 11+). */
    void hideIme(int displayId) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Refl.callSig(Wm.windowManager(), "setDisplayImePolicy", new Class<?>[]{int.class, int.class},
                    displayId, IME_POLICY_HIDE);
        } catch (Refl.WmError e) {
            // An untrusted display: the keyboard, if an app asks for one, opens on the phone.
        }
    }

    // ── connections ──

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
            Thread t = new Thread(() -> serve(s), "apps-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /** The first message says what the connection is: the phone's, a window's, or a dropped file's. */
    private void serve(LocalSocket s) {
        Wire wire;
        Wire.Msg first;
        try {
            s.setSoTimeout(15_000);
            wire = new Wire(s.getInputStream(), s.getOutputStream());
            first = wire.read();
            s.setSoTimeout(0);
        } catch (IOException e) {
            closeQuietly(s);
            return;
        }
        if (first == null || first.type != Wire.JSON) {
            closeQuietly(s);
            return;
        }
        JSONObject hello;
        try {
            hello = new JSONObject(first.text());
        } catch (Exception e) {
            closeQuietly(s);
            return;
        }
        switch (hello.optString("t")) {
            case "control":
                control(s, wire);
                break;
            case "attach":
                attach(s, wire, hello);
                break;
            case "open": {
                windowAsked = true;
                int serial = serials.incrementAndGet();
                Thread.currentThread().setName("app-window-" + serial);
                AppWindow w = new AppWindow(this, s, wire, hello, serial);
                running.add(w);
                try {
                    w.run();
                } finally {
                    running.remove(w);
                }
                break;
            }
            case "put": {
                Thread.currentThread().setName("apps-put");
                AppDrop d = new AppDrop(s, wire, hello);
                drops.add(d);
                try {
                    d.run();
                } finally {
                    drops.remove(d);
                }
                break;
            }
            default:
                closeQuietly(s);
        }
    }

    /**
     * A window again, on a new connection: the computer came back to it — over
     * another way to the phone, or the same one back. The window takes the
     * connection over and this thread leaves it to the window's own; a window
     * the server does not have is said to be closed, if it ended while the
     * computer was away, or unknown — a computer can open its app again then.
     */
    private void attach(LocalSocket s, Wire wire, JSONObject req) {
        String key = req.optString("key");
        AppWindow w = null;
        for (AppWindow r : running) {
            if (!key.isEmpty() && key.equals(r.key)) {
                w = r;
                break;
            }
        }
        try {
            if (w != null && w.attach(s, wire, req)) return;
            boolean ended = w != null || endedAway.contains(key);
            wire.json(new JSONObject().put("t", "state").put("state", ended ? "closed" : "unknown"));
        } catch (Exception e) {
            if (!stopping) log("a window coming back: " + e);
        }
        closeQuietly(s);
    }

    /** The window with {@code key} ended while its computer was away. */
    void endedAway(String key) {
        if (!key.isEmpty()) endedAway.add(key);
    }

    /** The phone as a whole: its apps, their icons, the clipboard, its sound, the clock. */
    private void control(LocalSocket s, Wire wire) {
        if (!shellHeld) log("the computer is back, over another way to the phone");
        try {
            JSONObject hello = new JSONObject();
            hello.put("t", "hello").put("v", 1).put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
                    .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                    .put("trusted", Build.VERSION.SDK_INT >= 30 && !trustedRefused)
                    .put("ownFocus", Build.VERSION.SDK_INT >= 34)
                    .put("clipboard", clipboard != null);
            wire.json(hello);
            // Where the sound can go, before anyone opens a window's menu to ask.
            sendAudio(wire);
            synchronized (this) {
                // The notifications a connection before this one asked for stop
                // first: this one asks for its own, and none of theirs reaches it.
                stopNotifying(null, "a new control connection; the computer asks again if it wants them");
                // Only now the phone's own word goes this way: the computer takes the
                // first message on a connection for the hello, and a notification
                // written ahead of it would be taken for one, and lost.
                control = wire;
            }
            while (!stopping) {
                Wire.Msg m = wire.read();
                if (m == null) break;
                if (m.type != Wire.JSON) continue;
                JSONObject j = new JSONObject(m.text());
                switch (j.optString("t")) {
                    case "apps": {
                        long t0 = SystemClock.uptimeMillis();
                        JSONArray list = catalog().list();
                        wire.json(new JSONObject().put("t", "apps").put("list", list));
                        log("listed " + list.length() + " apps in " + (SystemClock.uptimeMillis() - t0) + " ms");
                        break;
                    }
                    case "icons": {
                        JSONArray pkgs = j.optJSONArray("pkgs");
                        int px = Math.max(16, Math.min(256, j.optInt("px", 96)));
                        if (pkgs == null) break;
                        for (int i = 0; i < pkgs.length(); i++) {
                            String pkg = pkgs.optString(i);
                            byte[] png = catalog().icon(pkg, px);
                            if (png == null) continue;
                            byte[] name = pkg.getBytes(StandardCharsets.UTF_8);
                            byte[] pre = new byte[1 + name.length];
                            pre[0] = (byte) name.length;
                            System.arraycopy(name, 0, pre, 1, name.length);
                            wire.send(Wire.ICON, pre, pre.length, png, png.length);
                        }
                        wire.json(new JSONObject().put("t", "iconsDone"));
                        break;
                    }
                    case "clip":
                        if (clipboard != null) clipboard.set(j.optString("text"));
                        break;
                    case "clipNow": {
                        String text = clipboard != null ? clipboard.read() : null;
                        wire.json(new JSONObject().put("t", "clip").put("text", text == null ? "" : text));
                        break;
                    }
                    case "audio":
                        sendAudio(wire);
                        break;
                    case "audioRoute":
                        wire.json(route(j));
                        sendAudio(wire);
                        break;
                    case "audioMode":
                        tellLauncher(j);
                        break;
                    case "ping":
                        wire.json(new JSONObject().put("t", "pong").put("n", j.opt("n"))
                                .put("at", System.nanoTime() / 1000));
                        break;
                    case "stop":
                        // The computer is going, and says so: no waiting for it to come back.
                        shutdown("the computer asked");
                        break;
                    case "warm":
                        warmUp();
                        break;
                    case "notifs":
                        wire.json(notifications(j.optBoolean("on"), wire));
                        break;
                    case "notifOpen":
                    case "notifAction":
                    case "notifReply":
                    case "notifDismiss":
                    case "notifCall":
                    case "notifTest":
                        notifying.execute(() -> notificationAsked(j));
                        break;
                    case "padOn": {
                        boolean open = j.optBoolean("open");
                        padding.execute(() -> {
                            JSONObject r;
                            try {
                                r = pad.arm(open);
                            } catch (Exception e) {
                                r = new JSONObject();
                                try {
                                    r.put("t", "padOn").put("open", open).put("ok", false).put("message", String.valueOf(e));
                                } catch (Exception ignored) {
                                }
                            }
                            toComputer(r);
                        });
                        break;
                    }
                    case "padOff":
                        padding.execute(() -> pad.disarm("the computer asked"));
                        break;
                    case "linuxApps": {
                        JSONObject r;
                        try {
                            r = Providers.call(LINUX_PROVIDER, "linux.apps", null);
                        } catch (Exception e) {
                            // No launcher, or one too old to have the provider: no Linux apps.
                            r = new JSONObject().put("supported", false).put("installed", false)
                                    .put("message", String.valueOf(e));
                        }
                        wire.json(r.put("t", "linuxApps"));
                        break;
                    }
                    case "linuxIcons": {
                        JSONArray ids = j.optJSONArray("ids");
                        int px = Math.max(16, Math.min(256, j.optInt("px", 96)));
                        for (int i = 0; ids != null && i < ids.length(); i++) {
                            String id = ids.optString(i);
                            try {
                                String b64 = Providers.call(LINUX_PROVIDER, "linux.icon",
                                        new JSONObject().put("id", id).put("px", px)).optString("png");
                                if (b64.isEmpty()) continue;
                                byte[] png = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                                byte[] name = ("linux:" + id).getBytes(StandardCharsets.UTF_8);
                                byte[] pre = new byte[1 + name.length];
                                pre[0] = (byte) name.length;
                                System.arraycopy(name, 0, pre, 1, name.length);
                                wire.send(Wire.ICON, pre, pre.length, png, png.length);
                            } catch (Exception e) {
                                log("no icon for Linux's " + id + " (" + e + ")");
                            }
                        }
                        wire.json(new JSONObject().put("t", "iconsDone"));
                        break;
                    }
                    case "linuxRun": {
                        JSONObject arg = new JSONObject().put("id", j.optString("id"))
                                .put("w", j.optInt("w", 1280)).put("h", j.optInt("h", 800)).put("dpi", j.optInt("dpi", 96));
                        letLauncherStartItsService();
                        JSONObject r;
                        try {
                            r = Providers.call(LINUX_PROVIDER, "linux.run", arg);
                        } catch (Exception e) {
                            // No launcher, or one too old to have the provider: said, and the connection stays.
                            r = new JSONObject().put("ok", false).put("message", String.valueOf(e));
                        }
                        log("Linux's " + j.optString("id") + ": " + r);
                        // The computer waits to hear: a refusal is its to show.
                        wire.json(new JSONObject().put("t", "linuxRan").put("ok", r.optBoolean("ok", true))
                                .put("message", r.optString("message")));
                        if (r.optBoolean("ok", true)) pumpLinux();
                        break;
                    }
                    default:
                }
            }
        } catch (Exception e) {
            if (!stopping) log("control: " + e);
        } finally {
            if (control == wire) control = null;
            stopNotifying(wire, "the connection that asked for them has gone");
            closeQuietly(s);
        }
    }

    // ── the phone's notifications ──

    /** The phone's notifications, while the connection that asked for them is up; null otherwise. */
    AppNotifications notifications() {
        return notifications;
    }

    /**
     * The computer turned its notifications on or off, on {@code wire}: the
     * listener with it, for that connection. A phone that will not have the
     * shell listen says why, and the computer shows that rather than
     * notifications that never come.
     */
    private synchronized JSONObject notifications(boolean on, Wire wire) throws org.json.JSONException {
        JSONObject r = new JSONObject().put("t", "notifs").put("on", on);
        if (on && wire != control) {
            // A connection another has taken the place of: nothing it asked for would reach it.
            return r.put("ok", false).put("message", "not the computer's connection now");
        }
        if (on && notifications == null) {
            try {
                notifications = AppNotifications.start(this, this::toComputer);
                notifyingFor = wire;
                log("notifications: listening for the computer");
            } catch (Throwable t) {
                log("notifications: the phone will not let the shell listen (" + t + ")");
                return r.put("ok", false).put("message", t.getMessage() != null ? t.getMessage() : t.toString());
            }
        } else if (on) {
            // Asked again while listening — an answer that went missing: everything again.
            notifications.resync();
        } else {
            stopNotifying(null, "they are off on the computer");
        }
        return r.put("ok", true);
    }

    /**
     * No longer listening, if the notifications were for {@code wire} — or
     * whoever they were for, with null.
     */
    private synchronized void stopNotifying(Wire wire, String why) {
        AppNotifications n = notifications;
        if (n == null || (wire != null && notifyingFor != wire)) return;
        notifications = null;
        notifyingFor = null;
        n.stop();
        log("notifications: no longer listening (" + why + ")");
    }

    /** What one of the computer's notifications asked for, and how it went: {@code notifDone}. */
    private void notificationAsked(JSONObject j) {
        String t = j.optString("t");
        String key = j.optString("key");
        AppNotifications n = notifications;
        String failed;
        if (n == null) {
            failed = "notifications are off";
        } else {
            // A display of the computer's: a window's, by its key, or the one asked for (a desktop's).
            int display = j.has("window") ? displayOf(j.optString("window")) : j.optInt("display", -1);
            boolean freeform = j.optBoolean("freeform");
            try {
                switch (t) {
                    case "notifOpen":
                        failed = n.open(key, display, freeform);
                        break;
                    case "notifAction":
                        failed = n.action(key, j.optInt("i", -1), j.optString("label"), display, freeform);
                        break;
                    case "notifReply":
                        failed = n.reply(key, j.optInt("i", -1), j.optString("label"), j.optString("text"));
                        break;
                    case "notifDismiss":
                        failed = n.dismiss(key);
                        break;
                    case "notifCall":
                        failed = n.call(key, j.optBoolean("answer"), j.optInt("i", -1), j.optString("label"));
                        break;
                    default:
                        failed = n.test(j.optString("title"), j.optString("text"));
                }
            } catch (Throwable e) {
                // Whatever went wrong, the computer hears that it did: a reply must not look sent.
                failed = String.valueOf(e);
            }
        }
        if (failed != null) log("notifications: " + t + " " + key + ": " + failed);
        try {
            JSONObject done = new JSONObject().put("t", "notifDone").put("what", t).put("key", key).put("ok", failed == null);
            if (failed != null) done.put("message", failed);
            toComputer(done);
        } catch (org.json.JSONException ignored) {
        }
    }

    /**
     * The display of the window the computer calls {@code key}, for an intent
     * to be fired onto; -1 if there is none — or if its display is untrusted
     * (Android 12), which takes no activity an app's own intent asks onto it:
     * fired with no display, the intent joins the app's task, in that window.
     */
    private int displayOf(String key) {
        for (AppWindow w : running) {
            if (!key.isEmpty() && key.equals(w.key)) return w.trusted() ? w.displayId : -1;
        }
        return -1;
    }

    /** One message to the computer, on whichever control connection it has now. */
    private void toComputer(JSONObject j) {
        Wire c = control;
        if (c == null) return;
        try {
            c.json(j);
        } catch (Exception ignored) {
            // the computer's end went; what it missed comes again when it is back (resync)
        }
    }

    // ── the phone's Linux apps ──

    private volatile boolean pumping;

    /**
     * Android 12 and later let an app start a foreground service from the
     * background only when something exempts it — and Linux runs in one
     * (LinuxService). On a desktop the launcher is the app in front; here it
     * is no app in front at all, and its start was refused ("could not start
     * the service", on an Android 15 emulator). The shell may put an app on the
     * power manager's temporary allow-list, which is exactly that exemption,
     * for half a minute: nothing to undo, and only when a Linux app is opened.
     */
    private static void letLauncherStartItsService() {
        String out = AppTasks.exec(5, "cmd", "deviceidle", "tempwhitelist", "-d", "30000", WmDaemon.LAUNCHER);
        if (out.contains("Error") || out.contains("Exception")) log("temporary allow-list refused: " + out.trim());
    }

    /** Ask the launcher to close a Linux window, as a computer's ✕ does. */
    void linuxClose(int xid) {
        try {
            Providers.call(LINUX_PROVIDER, "linux.close", new JSONObject().put("xid", xid));
        } catch (Exception e) {
            log("cannot close Linux window 0x" + Integer.toHexString(xid) + " (" + e + ")");
        }
    }

    /**
     * What Linux's windows do, from the launcher to the computer, for as long
     * as a session for the computer is up: each call waits until something
     * happens — a window opened, retitled, closed — so a new window reaches the
     * computer as it maps, and an idle session costs one waiting call.
     */
    private void pumpLinux() {
        synchronized (this) {
            if (pumping) return;
            pumping = true;
        }
        Thread t = new Thread(() -> {
            int quiet = 0;
            try {
                while (!stopping) {
                    JSONObject r = Providers.call(LINUX_PROVIDER, "linux.events", new JSONObject().put("wait", 15_000));
                    JSONArray events = r.optJSONArray("events");
                    int n = events == null ? 0 : events.length();
                    boolean ended = false;
                    for (int i = 0; i < n; i++) {
                        JSONObject e = events.getJSONObject(i);
                        String kind = e.optString("t");
                        ended |= "ended".equals(kind);
                        Wire c = control;
                        if (c != null) c.json(e.put("kind", kind).put("t", "linux"));
                    }
                    if (ended) break;
                    // Asked by a desktop instead, or never started: nothing more is coming.
                    quiet = !r.optBoolean("pc") && n == 0 ? quiet + 1 : 0;
                    if (quiet >= 2) break;
                }
            } catch (Exception e) {
                if (!stopping) log("Linux's windows: " + e);
            } finally {
                pumping = false;
            }
        }, "apps-linux");
        t.setDaemon(true);
        t.start();
    }

    // ── the windows ──

    void watch(AppWindow w) {
        windows.add(w);
    }

    void forget(AppWindow w) {
        windows.remove(w);
    }

    /** Every window's display, looked at a few times a second: an app that has gone closes its window. */
    private void watchTasks() {
        Thread t = new Thread(() -> {
            while (!stopping) {
                for (AppWindow w : windows) {
                    try {
                        if (!w.watchTick()) windows.remove(w);
                    } catch (Throwable e) {
                        log("watch: " + e);
                    }
                }
                SystemClock.sleep(AppWindow.WATCH_MS);
            }
        }, "apps-watch");
        t.setDaemon(true);
        t.start();
    }

    /** Tell each window's display it is in use every few seconds, for as long as it exists. */
    private void keepAwake() {
        IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                "getService", new Class<?>[]{String.class}, "power");
        if (b == null) {
            log("no power service: a window may go dark while nothing moves in it");
            return;
        }
        Object power = Refl.callStatic(Refl.cls("android.os.IPowerManager$Stub"),
                "asInterface", new Class<?>[]{IBinder.class}, b);
        Thread t = new Thread(() -> {
            // userActivity(displayId, time, event, flags) from Android 12, so
            // only a window's own display group wakes; before it, without the
            // display, the whole phone — which on those versions is the same thing.
            boolean perDisplay = Refl.hasMethod(power.getClass(), "userActivity",
                    int.class, long.class, int.class, int.class);
            boolean said = false;
            while (!stopping) {
                try {
                    long now = SystemClock.uptimeMillis();
                    if (perDisplay) {
                        for (AppWindow w : windows) {
                            Refl.callSig(power, "userActivity",
                                    new Class<?>[]{int.class, long.class, int.class, int.class}, w.displayId(), now, 0, 0);
                        }
                    } else if (!windows.isEmpty()) {
                        Refl.callSig(power, "userActivity",
                                new Class<?>[]{long.class, int.class, int.class}, now, 0, 0);
                    }
                } catch (Throwable e) {
                    if (!said) {
                        said = true;
                        log("cannot keep the windows awake (" + e.getMessage() + ")");
                    }
                }
                SystemClock.sleep(AWAKE_EVERY_MS);
            }
        }, "apps-awake");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The computer's adb shell: "stop" on it is the computer going, now; its
     * end alone may be only the way it came by gone (the cable pulled), and
     * the lease decides.
     */
    private void watchStdin() {
        Thread t = new Thread(() -> {
            try {
                java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    if ("stop".equals(line.trim())) {
                        shutdown("the computer asked");
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
            shellHeld = false;
            if (!stopping) log("the computer's adb shell closed; waiting for the computer to come back another way");
        }, "apps-stdin");
        t.setDaemon(true);
        t.start();
    }

    /**
     * What keeps the server: the computer's adb shell, its control connection,
     * or a window's. With none of them for {@link AppWindow#AWAY_MS} — the
     * computer gone, not just one way to the phone — it stops, and every app
     * goes back to the phone.
     */
    private void watchLease() {
        Thread t = new Thread(() -> {
            long lostAt = 0;
            while (!stopping) {
                boolean held = shellHeld || control != null;
                for (AppWindow w : running) held |= w.held();
                long now = SystemClock.uptimeMillis();
                if (held) {
                    lostAt = 0;
                } else if (lostAt == 0) {
                    lostAt = now;
                } else if (now - lostAt >= AppWindow.AWAY_MS) {
                    shutdown("the computer did not come back");
                    return;
                }
                SystemClock.sleep(500);
            }
        }, "apps-lease");
        t.setDaemon(true);
        t.start();
    }

    private void shutdown(String why) {
        synchronized (AppServer.class) {
            if (stopping) return;
            stopping = true;
        }
        log("stopping: " + why);
        // Woken by a connection, then closed: closing alone does not wake a
        // thread blocked in accept().
        try (LocalSocket poke = new LocalSocket()) {
            poke.connect(new LocalSocketAddress(socketName));
        } catch (IOException ignored) {
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        AppNotifications listening = notifications;
        if (listening != null) listening.stop();
        // The pad and its offer go from the phone, and the overlay app-op back if it was given for them.
        if (pad.armed()) pad.disarm("the server is stopping");
        List<AppWindow> open = new ArrayList<>(running);
        for (AppWindow w : open) w.closeAll(null);
        // A file still arriving is cut short as a dropped link cuts it: its
        // ".part" deleted on its own thread, which ending first would not let run.
        for (AppDrop d : drops) d.stop();
        // Each window's own thread lets its display go as its socket closes and
        // hands its app back behind the phone's screen: ending first would leave
        // the apps where Android puts a dead display's tasks — on top.
        long until = SystemClock.uptimeMillis() + 3000;
        while ((!running.isEmpty() || !drops.isEmpty()) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(20);
        }
        System.out.flush();
        System.exit(0);
    }

    // ── saying so ──

    /** The server, for its log once the computer's shell has gone. */
    private static volatile AppServer instance;

    /**
     * A line the computer reads — on the adb shell that started the server,
     * or once that has gone (the cable pulled), on the control connection.
     */
    static void say(String line) {
        System.out.println("INFO: " + line);
        System.out.flush();
        AppServer s = instance;
        Wire c = s == null ? null : s.control;
        if (!shellHeld && c != null) {
            try {
                c.json(new JSONObject().put("t", "log").put("line", "INFO: " + line));
            } catch (Exception ignored) {
                // the computer's end went too
            }
        }
    }

    /** A line for the computer's log. */
    static void log(String line) {
        say("apps: " + line);
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
