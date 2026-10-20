package com.ccrstech.openandroiddex.launcher;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Linux apps as windows of their own on the desktop (doc/linux-windows.md):
 * the launcher's end of the guest's second X display.
 *
 * <p>What it ties together:
 * <ul>
 * <li>the window session in the guest — {@link LinuxService} runs
 * linux-win.sh, which starts apps on display {@code :2};</li>
 * <li>{@link LinuxWm}, this app as that display's window manager, over its
 * abstract X socket;</li>
 * <li>a {@link LinuxWindowActivity} — a task of its own, with the desktop's
 * title bar and a taskbar button — for every top-level window, and an
 * overlay for every menu, both drawn by {@link LinuxWinGl};</li>
 * <li>what the taskbar and the title bars ask about those tasks: their
 * titles, icons, and what ✕ does.</li>
 * </ul>
 *
 * <p>Lives in the launcher's process, with the taskbar and the caption service
 * it answers, and on its main thread except where noted. The window
 * manager's callbacks arrive on its own thread and are posted here.
 */
final class LinuxWindows implements LinuxWm.Host {

    /** One top-level window, and the task that shows it. Main thread. */
    static final class Entry {
        final LinuxWm wm;
        final LinuxWm.Win win;
        LinuxWindowActivity activity;
        int taskId = -1;
        Bitmap icon;
        /** When its task was asked for, so a task that never came can be asked for again. */
        long launchedAt;

        Entry(LinuxWm wm, LinuxWm.Win win) {
            this.wm = wm;
            this.win = win;
        }

        String title() {
            String t = win.title;
            if (t == null || t.isEmpty()) t = win.wmClass;
            return t == null || t.isEmpty() ? "Linux" : t;
        }

        /** Whether it wants a taskbar button: not a dialog over another window, not one that asked to be left off. */
        boolean onTaskbar() {
            return win.transientFor == 0 && !win.skipTaskbar && taskId > 0;
        }
    }

    /** A menu, tooltip or list, in an overlay of its own over everything. Main thread. */
    private static final class Popup {
        final LinuxWm.Win win;
        LinuxWinView view;
        FrameLayout frame;
        WindowManager windowManager;
        WindowManager.LayoutParams params;

        Popup(LinuxWm.Win win) {
            this.win = win;
        }
    }

    /** Told whenever the set of windows, or a title or an icon, changes: the taskbar. */
    interface Listener {
        void onLinuxWindowsChanged();
    }

    /** What the window display's X socket is called — abstract, as linux-win.sh's Xvnc binds it. */
    static final String SOCKET = "/tmp/.X11-unix/X2";
    /** No window left for this long ends the window session; reopening one is a few seconds' start. */
    private static final long IDLE_STOP_MS = 3 * 60_000;
    /** How long a launch waits for the display before giving up on it. */
    private static final long CONNECT_WAIT_MS = 90_000;

    private static LinuxWindows instance;

    static synchronized LinuxWindows get(Context ctx) {
        if (instance == null) instance = new LinuxWindows(ctx.getApplicationContext());
        return instance;
    }

    /** The instance if there is one — for callers that must not make one (the caption service's poll). */
    static LinuxWindows peek() {
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LinuxWm wm;
    private volatile boolean connecting;
    /** Shared with every connection's X server in turn; made once, grown when the display grows. */
    private LinuxWm.SharedSegment segment;
    private int segmentBytes;
    private final Map<Integer, Entry> entries = new HashMap<>();
    private final Map<Integer, Popup> popups = new HashMap<>();
    /** taskId → title, for the caption service. */
    private final Map<Integer, String> titles = new ConcurrentHashMap<>();
    /** taskId → its window's class, for where the window is remembered. */
    private final Map<Integer, String> classes = new ConcurrentHashMap<>();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile PointerIcon pointer;
    /** The views windows are shown in, for the pointer's shape. Main thread. */
    private final List<LinuxWinView> views = new ArrayList<>();
    /** The guest's apps, for the icon of a window that has none of its own. */
    private volatile List<LinuxApps.App> apps = new ArrayList<>();
    private final Map<String, Bitmap> appIcons = new ConcurrentHashMap<>();
    /** The clipboard text last handed each way, so neither side gets its own back. */
    private String fromLinux;
    private String toLinux;
    /** When a click went outside a menu, and whether one of our windows took it since. */
    private long outsideAt;
    private long windowPointerAt;

    private LinuxWindows(Context app) {
        this.app = app;
        // Text copied on the desktop — or by scrcpy, from the computer — while
        // a Linux window has the focus goes to Linux straight away: it is
        // about to be pasted there. Android tells only the focused app.
        main.post(() -> {
            try {
                android.content.ClipboardManager cm = DexClipboard.manager(app);
                if (cm != null) cm.addPrimaryClipChangedListener(this::desktopClipboardChanged);
            } catch (RuntimeException e) {
                DexLog.warn("linuxwin", "no clipboard listener: " + e);
            }
        });
    }

    /** The Linux window that has the focus, or null. Main thread. */
    private Entry focusedEntry;
    private Context focusedContext;

    private void desktopClipboardChanged() {
        Entry e = focusedEntry;
        if (e != null && focusedContext != null && !e.win.closed) clipboardToLinux(e.wm, focusedContext);
    }

    /**
     * The desktop's clipboard to Linux, if it changed since either side last
     * handed it over. {@code focused}: a window of ours that has the focus,
     * which is what Android asks of a clipboard read.
     */
    void clipboardToLinux(LinuxWm w, Context focused) {
        String text = DexClipboard.current(focused);
        if (text.isEmpty() || text.equals(toLinux) || text.equals(fromLinux)) return;
        toLinux = text;
        w.paste(text);
    }

    // ── opening apps ──

    /**
     * Open {@code a} as a window of its own: the window session first if it
     * is not running, then the app in it. The window appears when the app
     * maps it ({@link #opened}).
     */
    void launch(LinuxApps.App a) {
        LauncherActivity desktop = LauncherActivity.desktopShell();
        if (desktop == null) return;
        Point size = displaySize(desktop);
        int dpi = Math.round(96f * desktop.getResources().getDisplayMetrics().densityDpi / 160f);
        main.removeCallbacks(idleStop);
        LinuxService.winRun(app, a.command(), size.x, size.y, dpi);
        DexLog.step("linuxwin", "opening " + a.id + " (" + a.command() + ")");
        LinuxWm w = wm;
        if (w != null && w.running()) w.displaySize(size.x, size.y);
        connect(size, dpi);
    }

    /**
     * The desktop came up while a window session was already running — the
     * launcher's process restarted under it, the session living on in the
     * service's: be its window manager again, and its windows come back.
     */
    void resume() {
        if (!new File(Linux.root(app), "win.pid").exists()) return;
        LauncherActivity desktop = LauncherActivity.desktopShell();
        if (desktop == null) return;
        Point size = displaySize(desktop);
        int dpi = Math.round(96f * desktop.getResources().getDisplayMetrics().densityDpi / 160f);
        // Briefly: a win.pid left behind by a session that was killed with its
        // process names nothing, and the socket will never answer.
        connect(size, dpi, 3000);
    }

    private void connect(Point size, int dpi) {
        connect(size, dpi, CONNECT_WAIT_MS);
    }

    /** Be the window display's window manager, once it answers. Safe to call again. */
    private void connect(Point size, int dpi, long waitMs) {
        if (wm != null && wm.running()) return;
        if (connecting) return;
        connecting = true;
        new Thread(() -> {
            try {
                long until = System.currentTimeMillis() + waitMs;
                IOException last = null;
                while (System.currentTimeMillis() < until) {
                    try {
                        LinuxWm w = open(size, dpi);
                        wm = w;
                        return;
                    } catch (IOException e) {
                        last = e;
                    }
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                DexLog.warn("linuxwin", "the window display did not answer: " + last);
            } finally {
                connecting = false;
            }
        }, "linux-win-connect").start();
    }

    private LinuxWm open(Point size, int dpi) throws IOException {
        byte[] cookie = readCookie(new File(Linux.root(app), "tmp/.oadx-win.xauth"));
        if (cookie == null) throw new IOException("no cookie yet");
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            X11 x = X11.open(s.getInputStream(), s.getOutputStream(), s, s::setFileDescriptorsForSend, cookie);
            LinuxWm.Options o = new LinuxWm.Options();
            o.dpi = dpi;
            o.bottomReserve = Math.round(LauncherActivity.TASKBAR_DP * dpi / 96f);
            o.settings = LinuxWm.parseXfconf(xsettingsXml());
            LinuxWm w = new LinuxWm(x, this, o);
            w.start(segment(size.x * size.y * 4));
            w.displaySize(size.x, size.y);
            return w;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** The cookie in the Xauthority file the service wrote for the session, or null. */
    static byte[] readCookie(File f) {
        if (!f.isFile() || f.length() > 4096) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return n <= 0 ? null : X11.cookieFrom(java.util.Arrays.copyOf(b, n));
        } catch (IOException e) {
            return null;
        }
    }

    /** The guest's XSETTINGS channel — its system defaults, then the user's changes over them. */
    private String xsettingsXml() {
        File root = new File(Linux.root(app), "rootfs");
        StringBuilder sb = new StringBuilder();
        for (String p : new String[]{
                "etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml",
                "usr/local/etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml",
                "root/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml"}) {
            sb.append(Linux.readFile(new File(root, p))).append('\n');
        }
        return sb.toString();
    }

    /**
     * Memory the X server writes images straight into (MIT-SHM by descriptor),
     * so a frame does not cross the socket. Android 11's memfd_create; on
     * anything older the images come over the socket, which works the same.
     */
    private synchronized LinuxWm.SharedSegment segment(int bytes) {
        if (Build.VERSION.SDK_INT < 30 || bytes <= 0) return null;
        if (segment != null && segmentBytes >= bytes) return segment;
        try {
            FileDescriptor fd = android.system.Os.memfd_create("oadx-x11", 0);
            android.system.Os.ftruncate(fd, bytes);
            FileChannel ch = new FileInputStream(fd).getChannel();
            ByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, bytes);
            segmentBytes = bytes;
            return segment = new LinuxWm.SharedSegment() {
                @Override
                public FileDescriptor fd() {
                    return fd;
                }

                @Override
                public ByteBuffer buffer() {
                    return map;
                }
            };
        } catch (Exception e) {
            DexLog.warn("linuxwin", "no shared memory: " + e);
            return null;
        }
    }

    private static Point displaySize(LauncherActivity desktop) {
        Point p = new Point();
        desktop.getWindowManager().getDefaultDisplay().getRealSize(p);
        return p;
    }

    // ── the window manager's news (its thread) ──

    @Override
    public void opened(LinuxWm.Win w) {
        main.post(() -> {
            if (w.closed) return;
            Entry e = new Entry(w.owner, w);
            entries.put(w.id, e);
            e.icon = iconFor(w);
            main.removeCallbacks(idleStop);
            startTask(e);
        });
    }

    /** Ask for the Android window: a new task, where the X window is, its caption strip above it. */
    private void startTask(Entry e) {
        LauncherActivity desktop = LauncherActivity.desktopShell();
        if (desktop == null) return;
        e.launchedAt = android.os.SystemClock.uptimeMillis();
        Point size = displaySize(desktop);
        float density = desktop.getResources().getDisplayMetrics().density;
        int caption = Math.round(40 * density);
        int taskbar = Math.round(LauncherActivity.TASKBAR_DP * density);
        int[] g = e.win.geometry();
        Rect bounds = null;
        String key = memoryKey(e.win);
        if (key != null && e.win.transientFor == 0) {
            bounds = WindowMemory.recall(app, key, size, taskbar);
        }
        if (bounds == null) {
            int top = Math.max(0, g[1] - caption);
            bounds = new Rect(g[0], top, g[0] + g[2], top + caption + g[3]);
        }
        Intent i = new Intent(desktop, LinuxWindowActivity.class)
                .putExtra(LinuxWindowActivity.EXTRA_XID, e.win.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            ActivityOptions opts = desktop.shapeForDesktop(ActivityOptions.makeBasic(), bounds, desktop.displayId());
            desktop.startActivity(i, opts.toBundle());
        } catch (RuntimeException ex) {
            DexLog.warn("linuxwin", "cannot open a window for " + e.win + ": " + ex);
        }
    }

    @Override
    public void changed(LinuxWm.Win w) {
        main.post(() -> {
            Entry e = entry(w);
            if (e == null) return;
            if (w.icon != null) e.icon = iconFor(w);
            if (e.taskId > 0) titles.put(e.taskId, e.title());
            if (e.activity != null) e.activity.describe();
            changed();
        });
    }

    @Override
    public void closed(LinuxWm.Win w) {
        main.post(() -> {
            Entry e = entry(w);
            if (e == null) return;
            entries.remove(w.id);
            if (e.taskId > 0) {
                titles.remove(e.taskId);
                classes.remove(e.taskId);
            }
            if (e.activity != null) e.activity.gone();
            changed();
            if (entries.isEmpty()) {
                main.removeCallbacks(idleStop);
                main.postDelayed(idleStop, IDLE_STOP_MS);
            }
        });
    }

    private final Runnable idleStop = this::idleStopNow;

    /** No window has been open for a while: end the window session, and with it the apps' processes. */
    private void idleStopNow() {
        if (!entries.isEmpty()) return;
        DexLog.step("linuxwin", "no Linux window for " + IDLE_STOP_MS / 60_000 + " minutes; ending the window session");
        LinuxWm w = wm;
        wm = null;
        if (w != null) w.stop();
        LinuxService.winStop(app);
    }

    @Override
    public void popup(LinuxWm.Win w, boolean shown) {
        main.post(() -> {
            if (shown && !w.closed) showPopup(w);
            else hidePopup(w);
        });
    }

    @Override
    public void damaged(LinuxWm.Win w) {
        LinuxWinGl.get().wake();
    }

    @Override
    public void cursor(LinuxWm.Cursor c) {
        if (c.width <= 0 || c.height <= 0 || c.width > 256 || c.height > 256) return;
        int[] px = new int[c.argb.length];
        for (int i = 0; i < px.length; i++) {
            // XFIXES gives premultiplied alpha; a Bitmap from colours wants it straight.
            int p = c.argb[i];
            int a = p >>> 24;
            if (a == 0) {
                px[i] = 0;
            } else if (a == 255) {
                px[i] = p;
            } else {
                int r = Math.min(255, ((p >> 16) & 0xff) * 255 / a);
                int g = Math.min(255, ((p >> 8) & 0xff) * 255 / a);
                int b = Math.min(255, (p & 0xff) * 255 / a);
                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(px, c.width, c.height, Bitmap.Config.ARGB_8888);
        main.post(() -> {
            try {
                pointer = PointerIcon.create(bmp, Math.min(c.hotX, c.width - 1), Math.min(c.hotY, c.height - 1));
            } catch (RuntimeException e) {
                pointer = null;
            }
            // The view under the pointer asks again only when the pointer moves;
            // set it now so the shape changes where it stands.
            for (LinuxWinView v : views) v.setPointerIcon(pointer);
        });
    }

    @Override
    public void copied(String text) {
        main.post(() -> {
            if (text == null || text.isEmpty() || text.equals(fromLinux)) return;
            fromLinux = text;
            toLinux = text;
            DexClipboard.set(app, text);
        });
    }

    @Override
    public void activate(LinuxWm.Win w) {
        main.post(() -> {
            Entry e = entry(w);
            if (e != null && e.taskId > 0) raiseTask(e.taskId);
        });
    }

    @Override
    public void minimise(LinuxWm.Win w) {
        main.post(() -> {
            Entry e = entry(w);
            if (e != null && e.taskId > 0) CaptionService.minimiseTask(e.taskId);
        });
    }

    @Override
    public void resize(LinuxWm.Win w, int width, int height) {
        main.post(() -> {
            Entry e = entry(w);
            // A maximised window keeps the display's size whatever the app asks.
            if (e != null && e.taskId > 0 && !CaptionService.maximisedTask(e.taskId)) {
                CaptionService.resizeTask(e.taskId, width, height);
            }
        });
    }

    @Override
    public void fullscreen(LinuxWm.Win w, boolean on) {
        main.post(() -> {
            Entry e = entry(w);
            if (e != null && e.taskId > 0) CaptionService.maximiseTask(e.taskId, on);
        });
    }

    @Override
    public void log(String line) {
        DexLog.step("linuxwin", line);
    }

    /** The entry for exactly this window — not one an earlier session had under the same id. */
    private Entry entry(LinuxWm.Win w) {
        Entry e = entries.get(w.id);
        return e != null && e.win == w ? e : null;
    }

    // ── the windows' side (main thread) ──

    /** A {@link LinuxWindowActivity} came up for {@code xid}; null when that window is gone. */
    Entry attach(int xid, LinuxWindowActivity a) {
        Entry e = entries.get(xid);
        if (e == null || e.win.closed) return null;
        if (e.activity != null && e.activity != a) e.activity.gone();
        e.activity = a;
        e.taskId = a.getTaskId();
        titles.put(e.taskId, e.title());
        String cls = memoryKey(e.win);
        if (cls != null) classes.put(e.taskId, cls);
        changed();
        return e;
    }

    /**
     * Its activity went. {@code finishing} and the window still there: the
     * task was closed from outside — the Task Manager, say — so the app is
     * asked to close the window, as the ✕ does; if it keeps it (unsaved work)
     * the window gets a task again.
     */
    void detach(Entry e, LinuxWindowActivity a, boolean finishing) {
        if (e.activity != a) return;
        e.activity = null;
        if (focusedEntry == e) {
            focusedEntry = null;
            focusedContext = null;
        }
        if (e.taskId > 0) {
            titles.remove(e.taskId);
            classes.remove(e.taskId);
        }
        e.taskId = -1;
        changed();
        if (!finishing || e.win.closed) return;
        e.wm.close(e.win);
        main.postDelayed(() -> {
            if (!e.win.closed && e.activity == null && entries.get(e.win.id) == e) startTask(e);
        }, 1500);
    }

    void placed(LinuxWm.Win w, int x, int y, int width, int height) {
        Entry e = entry(w);
        if (e != null) e.wm.placed(w, x, y, width, height);
    }

    void shown(Entry e, boolean on) {
        e.wm.shown(e.win, on);
    }

    /**
     * The window has the keyboard, or lost it. Coming to the front is also
     * when the desktop's clipboard goes to Linux, if it changed since — the
     * rule the desktop window follows (clipboardShare.ts).
     */
    void focused(Entry e, boolean has, Context focusedWindow) {
        if (!has) {
            if (focusedEntry == e) {
                focusedEntry = null;
                focusedContext = null;
            }
            e.wm.releaseAll();
            return;
        }
        focusedEntry = e;
        focusedContext = focusedWindow;
        e.wm.focus(e.win);
        clipboardToLinux(e.wm, focusedWindow);
    }

    void close(Entry e) {
        e.wm.close(e.win);
    }

    void register(LinuxWinView v) {
        if (!views.contains(v)) views.add(v);
    }

    void unregister(LinuxWinView v) {
        views.remove(v);
    }

    PointerIcon pointerIcon() {
        return pointer;
    }

    /** One of our windows (not a menu) took a pointer event. */
    void pointerInWindow(LinuxWinView v) {
        windowPointerAt = android.os.SystemClock.uptimeMillis();
    }

    // ── menus ──

    private void showPopup(LinuxWm.Win w) {
        int[] g = w.geometry();
        Popup p = popups.get(w.id);
        if (p != null) {
            p.params.x = g[0];
            p.params.y = g[1];
            p.params.width = g[2];
            p.params.height = g[3];
            try {
                p.windowManager.updateViewLayout(p.frame, p.params);
            } catch (RuntimeException ignored) {
            }
            return;
        }
        LauncherActivity desktop = LauncherActivity.desktopShell();
        LinuxWm source = w.owner;
        if (desktop == null || source == null || !source.running()) return;
        Display display = desktop.getDisplay();
        if (display == null) return;
        // Above every window, the taskbar included, where a menu belongs. The
        // caption service's overlay first — it needs no permission and sits
        // above everything; the app's own overlay permission otherwise.
        Context ctx = CaptionService.overlayContext(display.getDisplayId());
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        if (ctx == null) {
            ctx = app.createDisplayContext(display);
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        p = new Popup(w);
        p.windowManager = ctx.getSystemService(WindowManager.class);
        p.view = new LinuxWinView(ctx, this, source, w, true);
        p.frame = new FrameLayout(ctx) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    outsideClick();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        p.frame.addView(p.view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        p.params = new WindowManager.LayoutParams(g[2], g[3], type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.params.gravity = Gravity.TOP | Gravity.LEFT;
        p.params.x = g[0];
        p.params.y = g[1];
        p.params.setTitle("Linux menu");
        if (Build.VERSION.SDK_INT >= 28) {
            p.params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            p.windowManager.addView(p.frame, p.params);
            popups.put(w.id, p);
        } catch (RuntimeException e) {
            DexLog.warn("linuxwin", "cannot show a menu: " + e);
        }
    }

    private void hidePopup(LinuxWm.Win w) {
        Popup p = popups.remove(w.id);
        if (p == null) return;
        try {
            p.windowManager.removeViewImmediate(p.frame);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * A click outside every menu. If it landed on one of our windows, the app
     * saw it and closes its menu itself; anywhere else — an Android window, the
     * desktop — X never heard of it, so the menu is told, as a click on the
     * bare X screen beside it would.
     */
    private void outsideClick() {
        outsideAt = android.os.SystemClock.uptimeMillis();
        main.postDelayed(() -> {
            if (windowPointerAt >= outsideAt) return;
            LinuxWm w = wm;
            if (w != null && !popups.isEmpty()) w.clickOutside();
        }, 120);
    }

    // ── what the taskbar and the title bars ask ──

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : listeners) l.onLinuxWindowsChanged();
    }

    /** The windows the taskbar shows, in the order they opened. Main thread. */
    List<Entry> taskbar() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.onTaskbar() && !e.win.closed) out.add(e);
        out.sort((a, b) -> Long.compare(a.launchedAt, b.launchedAt));
        return out;
    }

    /** The caption's title for one of our tasks, or null for a task that is not a Linux window. */
    static String titleForTask(int taskId) {
        LinuxWindows w = instance;
        return w == null ? null : w.titles.get(taskId);
    }

    /** Where a Linux window's place is remembered: by its app, not by the activity they all share. */
    static String memoryKeyForTask(int taskId) {
        LinuxWindows w = instance;
        return w == null ? null : w.classes.get(taskId);
    }

    private static String memoryKey(LinuxWm.Win w) {
        String c = w.wmClass != null && !w.wmClass.isEmpty() ? w.wmClass : w.wmInstance;
        return c == null || c.isEmpty() ? null : "linux:" + c;
    }

    /** The caption's ✕ on one of our tasks. Main thread. */
    static boolean closeTask(int taskId) {
        LinuxWindows w = instance;
        if (w == null) return false;
        for (Entry e : w.entries.values()) {
            if (e.taskId == taskId) {
                w.close(e);
                return true;
            }
        }
        return false;
    }

    /** Bring a window forward: back from minimised, or simply to the front. */
    void raiseTask(int taskId) {
        if (CaptionService.minimisedTask(taskId)) {
            CaptionService.restoreTask(taskId);
            return;
        }
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            am.moveTaskToFront(taskId, 0);
        } catch (RuntimeException e) {
            DexLog.warn("linuxwin", "cannot bring task " + taskId + " forward: " + e);
        }
    }

    // ── icons ──

    /** The guest's apps, read off the main thread; their icons too. */
    void loadApps(Runnable then) {
        new Thread(() -> {
            File rootfs = new File(Linux.root(app), "rootfs");
            Map<String, Object> s = LinuxWm.parseXfconf(xsettingsXml());
            Object theme = s.get("Net/IconThemeName");
            List<LinuxApps.App> list = LinuxApps.scan(rootfs, Locale.getDefault(),
                    theme instanceof String ? (String) theme : null);
            for (LinuxApps.App a : list) {
                if (a.icon != null && !appIcons.containsKey(a.id)) {
                    Bitmap b = decodeIcon(a.icon, 128);
                    if (b != null) appIcons.put(a.id, b);
                }
            }
            apps = list;
            if (then != null) main.post(then);
        }, "linux-apps").start();
    }

    List<LinuxApps.App> apps() {
        return apps;
    }

    Bitmap appIcon(LinuxApps.App a) {
        return appIcons.get(a.id);
    }

    /** A PNG at about {@code px} square; null if it is not one. */
    static Bitmap decodeIcon(File f, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= px) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The window's own icon (_NET_WM_ICON); else its app's, by window class; else none. */
    private Bitmap iconFor(LinuxWm.Win w) {
        if (w.icon != null && w.iconWidth > 0 && w.iconHeight > 0) {
            try {
                return Bitmap.createBitmap(w.icon, w.iconWidth, w.iconHeight, Bitmap.Config.ARGB_8888);
            } catch (RuntimeException ignored) {
            }
        }
        for (LinuxApps.App a : apps) {
            if (a.wmClass.equalsIgnoreCase(w.wmClass) || a.wmClass.equalsIgnoreCase(w.wmInstance)
                    || a.id.equalsIgnoreCase(w.wmInstance)) {
                Bitmap b = appIcons.get(a.id);
                if (b != null) return b;
            }
        }
        return null;
    }

}
