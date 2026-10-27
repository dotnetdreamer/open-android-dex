package com.ccrstech.openandroiddex.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The window manager of the guest's second X display — the one Linux apps
 * open on when they are to be windows of their own on the desktop rather than
 * a part of XFCE. See doc/linux-windows.md.
 *
 * <p>It is a real X window manager, with three differences from any other:
 * <ul>
 * <li><b>It draws nothing.</b> Every top-level window is redirected by the
 * Composite extension into a pixmap of its own, and DAMAGE says what changed
 * in it; {@link #fetch} reads those pixels for the desktop to show in an
 * Android window of the window's own. Nothing is drawn on the X screen at
 * all — nobody looks at it.</li>
 * <li><b>Android owns where windows are.</b> The X screen is the desktop
 * display's size, and each X window is kept exactly where its Android window's
 * content is ({@link #placed}), and stacked as Android stacks them. So the
 * screen coordinates the desktop sees are the X coordinates too: input goes in
 * where the pointer is, and a menu an app opens near the screen's edge flips
 * the way it should.</li>
 * <li><b>Input comes from Android</b>, put back with XTEST ({@link #pointer},
 * {@link #key}).</li>
 * </ul>
 *
 * <p>Menus, tooltips and drop-down lists (override-redirect windows) are
 * "popups": the desktop shows them over everything at their own place on the
 * screen, because they routinely reach past the edge of the window they
 * belong to.
 *
 * <p>Threads: the X reader hands events to a thread of this class's own,
 * which is the only one that changes the window list. The desktop calls in
 * from its UI and render threads; those calls are requests on the one
 * connection, ordered by it — a raise sent before a click is done before the
 * click.
 *
 * <p>Pure Java: test/linux-windows-test.mjs checks its pure parts on a
 * computer, and it ran there against the guest's real Xvnc too
 * (doc/linux-windows.md, "Verified, and not yet").
 */
final class LinuxWm {

    /** What the desktop does with windows. Called on the window manager's thread unless noted. */
    interface Host {
        /** A top-level window was mapped: give it a window of its own. */
        void opened(Win w);

        /** Its title, icon, or what it is changed. */
        void changed(Win w);

        /** It was withdrawn or destroyed: its window goes. */
        void closed(Win w);

        /** A popup appeared, moved or resized ({@code shown}), or went away. */
        void popup(Win w, boolean shown);

        /** There are new pixels in {@code w}. Any thread; must not block. */
        void damaged(Win w);

        /** The pointer's shape changed. */
        void cursor(Cursor c);

        /** Something in Linux copied text. */
        void copied(String text);

        /** The app asked for its window to come to the front. */
        void activate(Win w);

        /** The app asked to be minimised. */
        void minimise(Win w);

        /** The app asked for its window to be this big (content, in pixels). */
        void resize(Win w, int width, int height);

        /** The app asked to go fullscreen, or to leave it. */
        void fullscreen(Win w, boolean on);

        void log(String line);
    }

    static final int KIND_WINDOW = 0;
    static final int KIND_POPUP = 1;

    /** The most of Linux's clipboard handed to the desktop, as for the desktop window (clipboardShare.ts). */
    static final int COPY_MAX = 4 * 1024 * 1024;
    /** A resize an app asks for within this long of being given a size is its answer to that size. */
    static final long RESIZE_ANSWER_MS = 1500;

    /** One X top-level the desktop shows. */
    static final class Win {
        /** The window manager it belongs to: a reconnect to a new session is a new one, and X ids repeat. */
        final LinuxWm owner;
        final int id;
        final int kind;
        int damage;
        /** Where it is on the X screen — which is where it is on the desktop display. Guarded by itself. */
        int x;
        int y;
        int width;
        int height;
        String title = "";
        String wmClass = "";
        String wmInstance = "";
        int transientFor;
        int pid;
        boolean dialog;
        boolean modal;
        boolean skipTaskbar;
        /** ARGB, {@link #iconSize} square at most, or null. */
        int[] icon;
        int iconWidth;
        int iconHeight;
        boolean deleteWindow;
        boolean takeFocus;
        /** WM_HINTS' input field: the window takes focus when given it. */
        boolean acceptsFocus = true;
        int minWidth;
        int minHeight;
        boolean fullscreen;
        /** The desktop has said where its Android window is. */
        boolean placed;
        /** When this window manager last gave it a size of Android's (uptime ms). */
        volatile long sizedAt;
        /** The desktop window is on screen, not minimised. */
        boolean shown = true;
        volatile boolean closed;
        /** Whatever the desktop hangs on it: its Android window, its texture. */
        volatile Object tag;

        // Pixels: what changed since the last fetch, and whether all of it did.
        private final ArrayList<int[]> dirty = new ArrayList<>();
        private boolean whole = true;

        Win(LinuxWm owner, int id, int kind) {
            this.owner = owner;
            this.id = id;
            this.kind = kind;
        }

        synchronized int[] geometry() {
            return new int[]{x, y, width, height};
        }

        /** True when there is something to fetch. */
        synchronized boolean dirty() {
            return whole || !dirty.isEmpty();
        }

        synchronized void invalidate() {
            whole = true;
            dirty.clear();
        }

        @Override
        public String toString() {
            return "0x" + Integer.toHexString(id) + (kind == KIND_POPUP ? " popup" : "") + " \"" + title + "\" "
                    + width + "x" + height + "+" + x + "+" + y;
        }
    }

    /** The pointer's image: premultiplied ARGB, as XFIXES gives it. */
    static final class Cursor {
        final int serial;
        final int width;
        final int height;
        final int hotX;
        final int hotY;
        final int[] argb;

        Cursor(int serial, int width, int height, int hotX, int hotY, int[] argb) {
            this.serial = serial;
            this.width = width;
            this.height = height;
            this.hotX = hotX;
            this.hotY = hotY;
            this.argb = argb;
        }
    }

    /** Where the pixels of one rectangle go: rows of {@code w * 4} bytes, B,G,R,X. */
    interface Pixels {
        /** Before the rectangles: the window's size they belong to, and whether they cover all of it. */
        void begin(int width, int height, boolean whole);

        void rect(int x, int y, int w, int h, ByteBuffer pixels);
    }

    /** MIT-SHM, when the socket can pass descriptors: the server writes images straight into this. */
    interface SharedSegment {
        java.io.FileDescriptor fd();

        ByteBuffer buffer();
    }

    /** The settings published to every app (XSETTINGS) — the guest's own theme, at the desktop's density. */
    static final class Options {
        /** In pixels per inch: 96 at the desktop's 160 dpi. */
        int dpi = 96;
        /** Kept clear at the bottom of the screen for the taskbar, in pixels. */
        int bottomReserve;
        /** Name → Integer or String, xfconf's xsettings channel as found in the guest. */
        Map<String, Object> settings = new LinkedHashMap<>();
        /** Largest icon kept, in pixels square. */
        int iconSize = 96;
        /**
         * Each window is on a display of its own, every one at the X screen's
         * origin — the phone's apps in windows of their own on a computer
         * (doc/apps-on-pc.md). The windows overlap on the X screen where none
         * overlaps on the computer, so the pointer raises the window it is in
         * the moment it moves in it, not only when it clicks: X sends motion and
         * the wheel to the topmost window under the pointer, and the stacking
         * order is nobody's to see.
         */
        boolean raiseOnPointer;
    }

    private final X11 x;
    private final Host host;
    private final Options opt;
    private final LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>();
    private final Map<Integer, Win> wins = new HashMap<>();
    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean stopped;

    private X11.Extension composite;
    private X11.Extension damage;
    private X11.Extension xfixes;
    private X11.Extension xtest;
    private X11.Extension shm;
    private X11.Extension bigRequests;
    private X11.Extension randr;
    private int shmSeg;
    private SharedSegment segment;

    /** Our own window: the check window EWMH asks for, selection owner and requestor. */
    private int self;
    private X11Keys keys;
    private int screenWidth;
    private int screenHeight;

    // atoms
    private int aWmProtocols, aWmDelete, aWmTakeFocus, aWmState, aWmChangeState;
    private int aNetWmName, aUtf8, aNetWmIcon, aNetWmPid, aNetWmType, aNetWmState;
    private int aTypeNormal, aTypeDialog, aTypeUtility, aTypeSplash, aTypeMenu, aTypeDropdown, aTypePopupMenu,
            aTypeTooltip, aTypeNotification, aTypeCombo, aTypeDnd, aTypeDock, aTypeDesktop, aTypeToolbar;
    private int aStateModal, aStateFullscreen, aStateSkipTaskbar, aStateMaxV, aStateMaxH, aStateHidden,
            aStateAttention;
    private int aNetSupported, aNetCheck, aNetActive, aNetClientList, aNetWorkarea, aNetFrameExtents,
            aNetRequestFrameExtents, aNetCloseWindow, aNetDesktopGeometry, aNetNumberOfDesktops,
            aNetCurrentDesktop, aNetWmDesktop, aNetAllowedActions, aNetMoveResizeWindow, aNetWmPing;
    private int aActMove, aActResize, aActMinimize, aActFullscreen, aActMaxH, aActMaxV, aActClose;
    private int aWmS0, aXsettingsS0, aXsettingsSettings, aManager;
    private int aClipboard, aTargets, aText, aTextPlain, aTextPlainUtf8, aIncr, aOurSelection, aTimestamp;

    // input state, under `lock`
    private int buttons;
    private final HashSet<Integer> held = new HashSet<>();
    private int pointerX = -1;
    private int pointerY = -1;
    private Win focused;
    /** Top of the X stack among our windows, to raise only when it changes. */
    private int topmost;

    // clipboard: set from the desktop's threads, read by the WM thread's SelectionRequest
    private volatile String clipText;
    private volatile boolean clipOwned;
    /** The window X's focus was last put on, by a focus or a click. Under `lock`. */
    private int xFocus;
    private final java.io.ByteArrayOutputStream incr = new java.io.ByteArrayOutputStream();
    private boolean incrActive;
    private int cursorSerial = -1;
    private int settingsSerial;

    LinuxWm(X11 x, Host host, Options opt) {
        this.x = x;
        this.host = host;
        this.opt = opt;
    }

    // ── setting up ──

    /**
     * Become the display's window manager and compositor, adopt whatever is
     * already on it, and start handling events. Throws if another window
     * manager has the display, or an extension this needs is missing.
     */
    void start(SharedSegment seg) throws IOException {
        x.start(new X11.Listener() {
            @Override
            public void event(X11.Event e) {
                events.add(e);
            }

            @Override
            public void error(X11.XError e) {
                // A window that went away before a request about it arrived is
                // the window manager's daily bread; anything else is worth a line.
                boolean badDamage = damage != null && e.code == damage.firstError;
                if (e.code != X11.BAD_WINDOW && e.code != X11.BAD_DRAWABLE && e.code != X11.BAD_MATCH
                        && !badDamage) {
                    host.log("x11: " + e.getMessage());
                }
            }

            @Override
            public void closed(Throwable why) {
                events.add(new Closed(why));
            }
        });
        screenWidth = x.screenWidth;
        screenHeight = x.screenHeight;
        internAtoms();

        composite = need("Composite");
        damage = need("DAMAGE");
        xfixes = need("XFIXES");
        xtest = need("XTEST");
        x.compositeQueryVersion(composite);
        x.damageQueryVersion(damage);
        x.xfixesQueryVersion(xfixes);
        x.xtestGetVersion(xtest);
        bigRequests = x.queryExtension("BIG-REQUESTS");
        // A clipboard text past 256 KB goes in one property only with this.
        if (bigRequests.present) x.enableBigRequests(bigRequests);
        randr = x.queryExtension("RANDR");
        if (randr.present) x.randrQueryVersion(randr);

        self = x.newId();
        x.createWindow(self, x.root, -100, -100, 1, 1, 2 /* InputOnly */,
                X11.CW_OVERRIDE_REDIRECT | X11.CW_EVENT_MASK, new int[]{1, X11.PROPERTY_CHANGE_MASK});

        // Being the window manager is SubstructureRedirect on the root, which
        // only one client may hold: a BadAccess here is somebody else's.
        x.changeWindowAttributes(x.root, X11.CW_EVENT_MASK, X11.SUBSTRUCTURE_REDIRECT_MASK
                | X11.SUBSTRUCTURE_NOTIFY_MASK | X11.STRUCTURE_NOTIFY_MASK | X11.PROPERTY_CHANGE_MASK);
        X11.Reply attrs = x.getWindowAttributes(x.root).get();
        if ((attrs.u32(36) & X11.SUBSTRUCTURE_REDIRECT_MASK) == 0) {
            throw new IOException("another window manager has this display");
        }
        // Manual: the server draws nothing to the screen; the pixmaps are all there is.
        x.compositeRedirectSubwindows(composite, x.root, true);
        x.xtestGrabControl(xtest, true);
        x.xfixesSelectCursorInput(xfixes, x.root, X11.XFIXES_DISPLAY_CURSOR_MASK);
        x.xfixesSelectSelectionInput(xfixes, x.root, aClipboard, X11.XFIXES_SET_SELECTION_OWNER_MASK);

        if (seg != null && x.canPassFds()) {
            X11.Extension s = x.queryExtension("MIT-SHM");
            if (s.present) {
                X11.Reply v = x.shmQueryVersion(s);
                if (v.u16(8) > 1 || v.u16(10) >= 2) {
                    try {
                        shmSeg = x.newId();
                        x.shmAttachFd(s, shmSeg, seg.fd(), false);
                        x.sync();
                        shm = s;
                        segment = seg;
                    } catch (IOException e) {
                        host.log("x11: no shared memory (" + e.getMessage() + "); images come over the socket");
                    }
                }
            }
        }

        keys = new X11Keys(x.minKeycode, x.getKeyboardMapping());
        publishEwmh();
        publishSettings();
        x.setSelectionOwner(self, aWmS0, X11.CURRENT_TIME);
        x.sendClientMessage(x.root, X11.STRUCTURE_NOTIFY_MASK, x.root, aManager, X11.CURRENT_TIME, aWmS0, self);
        x.sync();
        adopt();

        thread = new Thread(this::loop, "linux-wm");
        thread.setDaemon(true);
        thread.start();
        host.log("x11: window manager on " + x.vendor + ", " + screenWidth + "x" + screenHeight
                + (shm != null ? ", shared memory" : ""));
    }

    void stop() {
        stopped = true;
        events.add(new Closed(null));
        x.close();
    }

    boolean running() {
        return !stopped && !x.isClosed();
    }

    private X11.Extension need(String name) throws IOException {
        X11.Extension e = x.queryExtension(name);
        if (!e.present) throw new IOException("the X server has no " + name);
        return e;
    }

    private void internAtoms() throws IOException {
        String[] names = {
                "WM_PROTOCOLS", "WM_DELETE_WINDOW", "WM_TAKE_FOCUS", "WM_STATE", "WM_CHANGE_STATE",
                "_NET_WM_NAME", "UTF8_STRING", "_NET_WM_ICON", "_NET_WM_PID", "_NET_WM_WINDOW_TYPE",
                "_NET_WM_STATE",
                "_NET_WM_WINDOW_TYPE_NORMAL", "_NET_WM_WINDOW_TYPE_DIALOG", "_NET_WM_WINDOW_TYPE_UTILITY",
                "_NET_WM_WINDOW_TYPE_SPLASH", "_NET_WM_WINDOW_TYPE_MENU", "_NET_WM_WINDOW_TYPE_DROPDOWN_MENU",
                "_NET_WM_WINDOW_TYPE_POPUP_MENU", "_NET_WM_WINDOW_TYPE_TOOLTIP",
                "_NET_WM_WINDOW_TYPE_NOTIFICATION", "_NET_WM_WINDOW_TYPE_COMBO", "_NET_WM_WINDOW_TYPE_DND",
                "_NET_WM_WINDOW_TYPE_DOCK", "_NET_WM_WINDOW_TYPE_DESKTOP", "_NET_WM_WINDOW_TYPE_TOOLBAR",
                "_NET_WM_STATE_MODAL", "_NET_WM_STATE_FULLSCREEN", "_NET_WM_STATE_SKIP_TASKBAR",
                "_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ", "_NET_WM_STATE_HIDDEN",
                "_NET_WM_STATE_DEMANDS_ATTENTION",
                "_NET_SUPPORTED", "_NET_SUPPORTING_WM_CHECK", "_NET_ACTIVE_WINDOW", "_NET_CLIENT_LIST",
                "_NET_WORKAREA", "_NET_FRAME_EXTENTS", "_NET_REQUEST_FRAME_EXTENTS", "_NET_CLOSE_WINDOW",
                "_NET_DESKTOP_GEOMETRY", "_NET_NUMBER_OF_DESKTOPS", "_NET_CURRENT_DESKTOP", "_NET_WM_DESKTOP",
                "_NET_WM_ALLOWED_ACTIONS", "_NET_MOVERESIZE_WINDOW", "_NET_WM_PING",
                "_NET_WM_ACTION_MOVE", "_NET_WM_ACTION_RESIZE", "_NET_WM_ACTION_MINIMIZE",
                "_NET_WM_ACTION_FULLSCREEN", "_NET_WM_ACTION_MAXIMIZE_HORZ", "_NET_WM_ACTION_MAXIMIZE_VERT",
                "_NET_WM_ACTION_CLOSE",
                "WM_S0", "_XSETTINGS_S0", "_XSETTINGS_SETTINGS", "MANAGER",
                "CLIPBOARD", "TARGETS", "TEXT", "text/plain", "text/plain;charset=utf-8", "INCR",
                "OADX_SELECTION", "TIMESTAMP",
        };
        X11.Pending[] p = new X11.Pending[names.length];
        for (int i = 0; i < names.length; i++) p[i] = x.internAtomAsync(names[i], false);
        int[] a = new int[names.length];
        for (int i = 0; i < names.length; i++) a[i] = p[i].get().u32(8);
        int i = 0;
        aWmProtocols = a[i++]; aWmDelete = a[i++]; aWmTakeFocus = a[i++]; aWmState = a[i++];
        aWmChangeState = a[i++];
        aNetWmName = a[i++]; aUtf8 = a[i++]; aNetWmIcon = a[i++]; aNetWmPid = a[i++]; aNetWmType = a[i++];
        aNetWmState = a[i++];
        aTypeNormal = a[i++]; aTypeDialog = a[i++]; aTypeUtility = a[i++]; aTypeSplash = a[i++];
        aTypeMenu = a[i++]; aTypeDropdown = a[i++]; aTypePopupMenu = a[i++]; aTypeTooltip = a[i++];
        aTypeNotification = a[i++]; aTypeCombo = a[i++]; aTypeDnd = a[i++]; aTypeDock = a[i++];
        aTypeDesktop = a[i++]; aTypeToolbar = a[i++];
        aStateModal = a[i++]; aStateFullscreen = a[i++]; aStateSkipTaskbar = a[i++]; aStateMaxV = a[i++];
        aStateMaxH = a[i++]; aStateHidden = a[i++]; aStateAttention = a[i++];
        aNetSupported = a[i++]; aNetCheck = a[i++]; aNetActive = a[i++]; aNetClientList = a[i++];
        aNetWorkarea = a[i++]; aNetFrameExtents = a[i++]; aNetRequestFrameExtents = a[i++];
        aNetCloseWindow = a[i++]; aNetDesktopGeometry = a[i++]; aNetNumberOfDesktops = a[i++];
        aNetCurrentDesktop = a[i++]; aNetWmDesktop = a[i++]; aNetAllowedActions = a[i++];
        aNetMoveResizeWindow = a[i++]; aNetWmPing = a[i++];
        aActMove = a[i++]; aActResize = a[i++]; aActMinimize = a[i++]; aActFullscreen = a[i++];
        aActMaxH = a[i++]; aActMaxV = a[i++]; aActClose = a[i++];
        aWmS0 = a[i++]; aXsettingsS0 = a[i++]; aXsettingsSettings = a[i++]; aManager = a[i++];
        aClipboard = a[i++]; aTargets = a[i++]; aText = a[i++]; aTextPlain = a[i++]; aTextPlainUtf8 = a[i++];
        aIncr = a[i++]; aOurSelection = a[i++]; aTimestamp = a[i++];
    }

    /** What EWMH says a window manager puts on the root: what it supports, and that it is there. */
    private void publishEwmh() throws IOException {
        x.changeProperty32(x.root, aNetSupported, X11.ATOM_ATOM,
                aNetSupported, aNetCheck, aNetActive, aNetClientList, aNetWorkarea, aNetFrameExtents,
                aNetRequestFrameExtents, aNetCloseWindow, aNetDesktopGeometry, aNetNumberOfDesktops,
                aNetCurrentDesktop, aNetWmDesktop, aNetWmName, aNetWmIcon, aNetWmPid, aNetWmType, aNetWmState,
                aStateModal, aStateFullscreen, aStateSkipTaskbar, aStateHidden, aStateAttention,
                aNetAllowedActions, aNetMoveResizeWindow);
        x.changeProperty32(x.root, aNetCheck, X11.ATOM_WINDOW, self);
        x.changeProperty32(self, aNetCheck, X11.ATOM_WINDOW, self);
        x.changePropertyString(self, aNetWmName, aUtf8, "Open Android DeX");
        x.changeProperty32(x.root, aNetNumberOfDesktops, X11.ATOM_CARDINAL, 1);
        x.changeProperty32(x.root, aNetCurrentDesktop, X11.ATOM_CARDINAL, 0);
        publishGeometry();
        publishClientList();
    }

    private void publishGeometry() throws IOException {
        x.changeProperty32(x.root, aNetDesktopGeometry, X11.ATOM_CARDINAL, screenWidth, screenHeight);
        // Menus and lists are kept inside the work area: off the taskbar.
        x.changeProperty32(x.root, aNetWorkarea, X11.ATOM_CARDINAL, 0, 0, screenWidth,
                Math.max(1, screenHeight - opt.bottomReserve));
    }

    private void publishClientList() throws IOException {
        List<Integer> ids = new ArrayList<>();
        synchronized (lock) {
            for (Win w : wins.values()) {
                if (w.kind == KIND_WINDOW) ids.add(w.id);
            }
        }
        int[] a = new int[ids.size()];
        for (int i = 0; i < a.length; i++) a[i] = ids.get(i);
        x.changeProperty32(x.root, aNetClientList, X11.ATOM_WINDOW, a);
    }

    /**
     * XSETTINGS: the guest's own theme, fonts and switches — what xfsettingsd
     * gives the XFCE desktop — with the DPI of the desktop display, so a
     * Linux app is the size an Android one is beside it.
     */
    private void publishSettings() throws IOException {
        byte[] blob = xsettings(atDpi(opt.settings, opt.dpi), ++settingsSerial);
        x.changeProperty(self, aXsettingsSettings, aXsettingsSettings, 8, blob, blob.length);
        x.setSelectionOwner(self, aXsettingsS0, X11.CURRENT_TIME);
        x.sendClientMessage(x.root, X11.STRUCTURE_NOTIFY_MASK, x.root, aManager, X11.CURRENT_TIME,
                aXsettingsS0, self);
    }

    /**
     * The guest's settings at the desktop display's DPI.
     *
     * Up to about 1.75x of 96 that is the font DPI alone, as before: text grows,
     * widgets and icons stay drawn at one pixel per pixel. Past it — a desktop
     * fitted to a Retina or 4K screen is 192 here — that would be big text in
     * small widgets, so GTK is also told to draw everything at a whole multiple
     * (Gdk/WindowScalingFactor) with the font DPI left over beside it
     * (Gdk/UnscaledDPI), the way GNOME publishes a HiDPI screen. Chromium,
     * Electron and Firefox take their scale from the same values.
     *
     * The guest's own xsettings.xml pins WindowScalingFactor at 1 for the XFCE
     * desktop on :1, which goes out over VNC and is scaled by the viewer; these
     * windows are composited one X pixel per Android pixel, so the scale is
     * theirs to have and is set here over it.
     */
    static Map<String, Object> atDpi(Map<String, Object> settings, int dpi) {
        Map<String, Object> s = new LinkedHashMap<>(settings);
        int scale = Math.max(1, (int) Math.floor(dpi / 96.0 + 0.25));
        s.put("Gdk/WindowScalingFactor", scale);
        s.put("Gdk/UnscaledDPI", Math.round(dpi * 1024f / scale));
        s.put("Xft/DPI", dpi * 1024);
        return s;
    }

    /** The XSETTINGS wire format: little-endian, Integer and String settings, each stamped with the serial. */
    static byte[] xsettings(Map<String, Object> settings, int serial) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        int n = 0;
        for (Object v : settings.values()) if (v instanceof Integer || v instanceof String) n++;
        head.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).putInt(serial).putInt(n);
        out.write(head.array(), 0, 12);
        for (Map.Entry<String, Object> e : settings.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof Integer) && !(v instanceof String)) continue;
            byte[] name = e.getKey().getBytes(StandardCharsets.US_ASCII);
            byte[] str = v instanceof String ? ((String) v).getBytes(StandardCharsets.UTF_8) : null;
            int len = 4 + X11.pad4(name.length) + 4 + (str == null ? 4 : 4 + X11.pad4(str.length));
            ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
            b.put((byte) (str == null ? 0 : 1)).put((byte) 0).putShort((short) name.length).put(name);
            b.position(4 + X11.pad4(name.length));
            b.putInt(serial);
            if (str == null) {
                b.putInt((Integer) v);
            } else {
                b.putInt(str.length).put(str);
            }
            out.write(b.array(), 0, len);
        }
        return out.toByteArray();
    }

    /**
     * xfconf's channel files (xsettings.xml), as the names XSETTINGS uses —
     * "Net/ThemeName" for /Net/ThemeName — with bool as 0 or 1, the way
     * xfsettingsd publishes them for the desktop on :1. Several files one
     * after another: a later value wins, so the user's own file goes last.
     * The DPI is left out; it is the desktop display's, not the guest's.
     */
    static Map<String, Object> parseXfconf(String xml) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(/?)property\\b([^>]*?)(/?)>|<channel\\b[^>]*>|</channel>").matcher(xml);
        java.util.ArrayDeque<String> path = new java.util.ArrayDeque<>();
        while (m.find()) {
            String tag = m.group();
            if (tag.startsWith("<channel") || tag.startsWith("</channel")) {
                path.clear();
                continue;
            }
            if (!m.group(1).isEmpty()) {
                if (!path.isEmpty()) path.removeLast();
                continue;
            }
            String attrs = m.group(2);
            String name = xmlAttr(attrs, "name");
            if (name == null) continue;
            path.addLast(name);
            String type = xmlAttr(attrs, "type");
            String value = xmlAttr(attrs, "value");
            if (type != null && value != null) {
                String key = String.join("/", path);
                switch (type) {
                    case "string":
                        out.put(key, value);
                        break;
                    case "int":
                    case "uint":
                        try {
                            out.put(key, Integer.parseInt(value.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    case "bool":
                        out.put(key, "true".equals(value.trim()) ? 1 : 0);
                        break;
                    default:
                }
            }
            if (!m.group(3).isEmpty()) path.removeLast();
        }
        out.remove("Xft/DPI");
        return out;
    }

    private static String xmlAttr(String attrs, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        if (!m.find()) return null;
        return m.group(1).replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Windows already mapped when this started — the desktop app restarted under a running session. */
    private void adopt() throws IOException {
        for (int id : x.queryTree(x.root)) {
            if (id == self) continue;
            X11.Reply a;
            try {
                a = x.getWindowAttributes(id).get();
            } catch (X11.XError e) {
                continue;
            }
            if (X11.mapState(a) != 2 || X11.windowClass(a) == 2) continue;
            if (X11.overrideRedirect(a)) {
                popupMapped(id);
            } else {
                manage(id, true);
            }
        }
    }

    // ── events ──

    private static final class Closed {
        final Throwable why;

        Closed(Throwable why) {
            this.why = why;
        }
    }

    private void loop() {
        try {
            while (!stopped) {
                Object o = events.take();
                if (o instanceof Closed) {
                    if (!stopped) host.log("x11: the display went away: " + ((Closed) o).why);
                    break;
                }
                if (o instanceof Runnable) {
                    ((Runnable) o).run();
                    continue;
                }
                try {
                    handle((X11.Event) o);
                } catch (X11.XError e) {
                    // About a window that is already gone, nearly always.
                } catch (IOException e) {
                    if (!x.isClosed()) host.log("x11: " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopped = true;
            List<Win> all;
            synchronized (lock) {
                all = new ArrayList<>(wins.values());
                wins.clear();
            }
            for (Win w : all) {
                w.closed = true;
                if (w.kind == KIND_POPUP) host.popup(w, false);
                else host.closed(w);
            }
        }
    }

    /** Run {@code r} on the window manager's thread, after the events queued before it. */
    void post(Runnable r) {
        events.add(r);
    }

    private void handle(X11.Event e) throws IOException {
        int code = e.code;
        if (code == damage.firstEvent) {
            onDamage(e);
            return;
        }
        if (code == xfixes.firstEvent + 1) {
            onCursor(e.u32(8));
            return;
        }
        if (code == xfixes.firstEvent) {
            onSelectionOwner(e.u32(8), e.u32(12));
            return;
        }
        switch (code) {
            case X11.MAP_REQUEST:
                manage(e.u32(8), false);
                break;
            case X11.MAP_NOTIFY:
                if (e.u8(12) != 0) popupMapped(e.u32(8));
                break;
            case X11.UNMAP_NOTIFY:
                // Our own SubstructureNotify on the root carries every unmap
                // once; a synthetic one is a client withdrawing (ICCCM 4.1.4).
                if (e.u32(4) == x.root || e.synthetic) forget(e.u32(8), false);
                break;
            case X11.DESTROY_NOTIFY:
                forget(e.u32(8), true);
                break;
            case X11.REPARENT_NOTIFY:
                if (e.u32(12) != x.root) forget(e.u32(8), false);
                break;
            case X11.CONFIGURE_NOTIFY:
                if (e.u32(8) == x.root) {
                    screenWidth = e.u16(20);
                    screenHeight = e.u16(22);
                    publishGeometry();
                } else {
                    onConfigured(e.u32(8), e.s16(16), e.s16(18), e.u16(20), e.u16(22));
                }
                break;
            case X11.CONFIGURE_REQUEST:
                onConfigureRequest(e);
                break;
            case X11.PROPERTY_NOTIFY:
                onProperty(e.u32(4), e.u32(8), e.u8(16));
                break;
            case X11.CLIENT_MESSAGE:
                onClientMessage(e);
                break;
            case X11.SELECTION_REQUEST:
                onSelectionRequest(e.u32(4), e.u32(12), e.u32(16), e.u32(20), e.u32(24));
                break;
            case X11.SELECTION_NOTIFY:
                onSelectionNotify(e.u32(12), e.u32(20));
                break;
            case X11.SELECTION_CLEAR:
                if (e.u32(12) == aClipboard) clipOwned = false;
                break;
            case X11.MAPPING_NOTIFY:
                if (e.u8(4) != 2) {
                    int[][] map = x.getKeyboardMapping();
                    synchronized (lock) {
                        keys.setMap(map);
                    }
                }
                break;
            default:
        }
    }

    private Win win(int id) {
        synchronized (lock) {
            return wins.get(id);
        }
    }

    // ── managing windows ──

    /**
     * A top-level asked to be mapped: read what it is, decide where it goes,
     * map it, and ask the desktop for a window. {@code adopted} — it was
     * already mapped before this window manager started.
     */
    private void manage(int id, boolean adopted) throws IOException {
        if (win(id) != null || id == self) {
            if (!adopted) x.mapWindow(id);
            return;
        }
        X11.Pending geo = x.getGeometry(id);
        X11.Pending attrs = x.getWindowAttributes(id);
        X11.Pending types = x.getPropertyAsync(id, aNetWmType, X11.ATOM_ATOM, 0, 32, false);
        X11.Pending states = x.getPropertyAsync(id, aNetWmState, X11.ATOM_ATOM, 0, 32, false);
        X11.Pending trans = x.getPropertyAsync(id, X11.ATOM_WM_TRANSIENT_FOR, X11.ATOM_WINDOW, 0, 1, false);
        X11.Pending hints = x.getPropertyAsync(id, X11.ATOM_WM_NORMAL_HINTS, X11.ATOM_ANY, 0, 18, false);
        X11.Reply g;
        X11.Reply a;
        try {
            g = geo.get();
            a = attrs.get();
        } catch (X11.XError gone) {
            return;
        }
        if (X11.windowClass(a) == 2) {
            // Input-only: nothing to show. Map it if it asked; it is not ours to keep.
            if (!adopted) x.mapWindow(id);
            return;
        }
        int[] typeList = ints(types);
        int[] stateList = ints(states);
        boolean popupType = false;
        boolean dialog = false;
        for (int t : typeList) {
            if (t == aTypeMenu || t == aTypeDropdown || t == aTypePopupMenu || t == aTypeTooltip
                    || t == aTypeNotification || t == aTypeCombo || t == aTypeDnd) {
                popupType = true;
            }
            if (t == aTypeDialog || t == aTypeUtility || t == aTypeToolbar || t == aTypeSplash) dialog = true;
            if (t == aTypeDock || t == aTypeDesktop) {
                // A panel or a desktop on a display that has neither: not a window.
                if (!adopted) x.mapWindow(id);
                return;
            }
        }
        Win w = new Win(this, id, popupType ? KIND_POPUP : KIND_WINDOW);
        w.x = g.s16(12);
        w.y = g.s16(14);
        w.width = g.u16(16);
        w.height = g.u16(18);
        w.dialog = dialog;
        int[] t = ints(trans);
        w.transientFor = t.length > 0 ? t[0] : 0;
        for (int s : stateList) {
            if (s == aStateModal) w.modal = true;
            if (s == aStateSkipTaskbar) w.skipTaskbar = true;
            if (s == aStateFullscreen) w.fullscreen = true;
        }
        int[] h = ints(hints);
        boolean userPos = false;
        if (h.length >= 18) {
            int flags = h[0];
            userPos = (flags & (1 | 4)) != 0 && (w.x != 0 || w.y != 0);
            if ((flags & 16) != 0) {
                w.minWidth = h[5];
                w.minHeight = h[6];
            }
        }
        readTitle(w);
        readClass(w);
        readProtocols(w);
        readWmHints(w);
        readPid(w);
        if (w.kind == KIND_WINDOW) readIcon(w);

        if (w.kind == KIND_WINDOW) {
            place(w, userPos);
            x.configureWindow(id, X11.CONFIG_X | X11.CONFIG_Y | X11.CONFIG_WIDTH | X11.CONFIG_HEIGHT
                    | X11.CONFIG_BORDER, w.x, w.y, w.width, w.height, 0);
            x.changeProperty32(id, aWmState, aWmState, 1, 0); // NormalState
            x.changeProperty32(id, aNetFrameExtents, X11.ATOM_CARDINAL, 0, 0, 0, 0);
            x.changeProperty32(id, aNetWmDesktop, X11.ATOM_CARDINAL, 0);
            x.changeProperty32(id, aNetAllowedActions, X11.ATOM_ATOM, aActMove, aActResize, aActMinimize,
                    aActFullscreen, aActMaxH, aActMaxV, aActClose);
        }
        x.changeWindowAttributes(id, X11.CW_EVENT_MASK, X11.PROPERTY_CHANGE_MASK);
        w.damage = x.newId();
        x.damageCreate(damage, w.damage, id, X11.DAMAGE_DELTA_RECTANGLES);
        synchronized (lock) {
            wins.put(id, w);
            // A window that maps goes on top of the stack.
            if (w.kind == KIND_WINDOW && !adopted) topmost = id;
        }
        if (!adopted) x.mapWindow(id);
        if (w.kind == KIND_WINDOW) {
            publishClientList();
            host.opened(w);
        } else {
            host.popup(w, true);
        }
    }

    /**
     * Where a new window goes before the desktop has its Android window: where
     * it asked to be, if it said so and that is on the screen; over its parent,
     * for a dialog; otherwise centred. Sized to fit, never smaller than the
     * least it says it can be.
     */
    private void place(Win w, boolean userPos) {
        int areaH = Math.max(200, screenHeight - opt.bottomReserve);
        w.width = Math.max(Math.max(w.width, w.minWidth), 64);
        w.height = Math.max(Math.max(w.height, w.minHeight), 48);
        w.width = Math.min(w.width, screenWidth);
        w.height = Math.min(w.height, areaH);
        Win parent = w.transientFor != 0 ? win(w.transientFor) : null;
        if (parent != null) {
            int[] pg = parent.geometry();
            w.x = pg[0] + (pg[2] - w.width) / 2;
            w.y = pg[1] + (pg[3] - w.height) / 2;
        } else if (!userPos) {
            w.x = (screenWidth - w.width) / 2;
            w.y = (areaH - w.height) / 2;
        }
        w.x = Math.max(0, Math.min(w.x, screenWidth - w.width));
        w.y = Math.max(0, Math.min(w.y, areaH - w.height));
    }

    private void popupMapped(int id) throws IOException {
        if (win(id) != null) return;
        X11.Reply g;
        X11.Reply a;
        try {
            X11.Pending gp = x.getGeometry(id);
            X11.Pending ap = x.getWindowAttributes(id);
            g = gp.get();
            a = ap.get();
        } catch (X11.XError gone) {
            return;
        }
        if (X11.windowClass(a) == 2) return;
        Win w = new Win(this, id, KIND_POPUP);
        w.x = g.s16(12);
        w.y = g.s16(14);
        w.width = g.u16(16);
        w.height = g.u16(18);
        // Toolkits keep 1×1 windows off-screen for their own bookkeeping
        // (Chromium's, GTK's); there is nothing in them to show.
        if (w.width * w.height <= 4 || w.x + w.width <= 0 || w.y + w.height <= 0
                || w.x >= screenWidth || w.y >= screenHeight) {
            return;
        }
        w.damage = x.newId();
        x.damageCreate(damage, w.damage, id, X11.DAMAGE_DELTA_RECTANGLES);
        synchronized (lock) {
            wins.put(id, w);
        }
        host.popup(w, true);
    }

    private void forget(int id, boolean destroyed) throws IOException {
        Win w;
        synchronized (lock) {
            w = wins.remove(id);
            if (w != null && focused == w) focused = null;
        }
        if (w == null) return;
        w.closed = true;
        if (!destroyed) {
            try {
                x.damageDestroy(damage, w.damage);
                if (w.kind == KIND_WINDOW) x.changeProperty32(id, aWmState, aWmState, 0, 0); // Withdrawn
            } catch (IOException ignored) {
            }
        }
        if (w.kind == KIND_POPUP) {
            host.popup(w, false);
        } else {
            publishClientList();
            host.closed(w);
        }
    }

    private void onConfigured(int id, int nx, int ny, int nw, int nh) {
        Win w = win(id);
        if (w == null) return;
        boolean resized;
        synchronized (w) {
            resized = nw != w.width || nh != w.height;
            w.x = nx;
            w.y = ny;
            w.width = nw;
            w.height = nh;
            if (resized) {
                w.whole = true;
                w.dirty.clear();
            }
        }
        if (w.kind == KIND_POPUP) host.popup(w, true);
        if (resized) host.damaged(w);
    }

    /**
     * A client asking to move, resize or restack itself. A window not yet
     * managed gets what it asks for — apps size themselves before mapping.
     * A managed one's position is Android's; a new size is passed on to the
     * desktop, which resizes the Android window if it can, and either way
     * the client is told where it really is (ICCCM 4.1.5).
     */
    private void onConfigureRequest(X11.Event e) throws IOException {
        int id = e.u32(8);
        int mask = e.u16(26);
        Win w = win(id);
        if (w == null || w.kind == KIND_POPUP) {
            List<Integer> values = new ArrayList<>();
            int out = 0;
            if ((mask & X11.CONFIG_X) != 0) { out |= X11.CONFIG_X; values.add(e.s16(16)); }
            if ((mask & X11.CONFIG_Y) != 0) { out |= X11.CONFIG_Y; values.add(e.s16(18)); }
            if ((mask & X11.CONFIG_WIDTH) != 0) { out |= X11.CONFIG_WIDTH; values.add(e.u16(20)); }
            if ((mask & X11.CONFIG_HEIGHT) != 0) { out |= X11.CONFIG_HEIGHT; values.add(e.u16(22)); }
            if ((mask & X11.CONFIG_BORDER) != 0) { out |= X11.CONFIG_BORDER; values.add(e.u16(24)); }
            if (out != 0) {
                int[] v = new int[values.size()];
                for (int i = 0; i < v.length; i++) v[i] = values.get(i);
                x.configureWindow(id, out, v);
            }
            return;
        }
        int[] g = w.geometry();
        int nw = (mask & X11.CONFIG_WIDTH) != 0 ? e.u16(20) : g[2];
        int nh = (mask & X11.CONFIG_HEIGHT) != 0 ? e.u16(22) : g[3];
        // An app asking for a size right after it was given one is answering
        // that size — a terminal rounding it to whole character cells, say —
        // not asking for a window of another shape: the window manager's size
        // stands, as any window manager's would over such hints.
        long since = System.nanoTime() / 1_000_000 - w.sizedAt;
        if ((nw != g[2] || nh != g[3]) && !w.fullscreen && since > RESIZE_ANSWER_MS) host.resize(w, nw, nh);
        sendConfigureNotify(w);
    }

    private void sendConfigureNotify(Win w) throws IOException {
        int[] g = w.geometry();
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) X11.CONFIGURE_NOTIFY).put((byte) 0).putShort((short) 0).putInt(w.id).putInt(w.id)
                .putInt(0).putShort((short) g[0]).putShort((short) g[1]).putShort((short) g[2])
                .putShort((short) g[3]).putShort((short) 0).put((byte) 0);
        x.sendEvent(w.id, false, X11.STRUCTURE_NOTIFY_MASK, b.array());
    }

    private void onProperty(int window, int atom, int state) throws IOException {
        if (window == self) {
            if (atom == aOurSelection && state == 0 && incrActive) readIncrChunk();
            return;
        }
        Win w = win(window);
        if (w == null) return;
        boolean changed = true;
        if (atom == aNetWmName || atom == X11.ATOM_WM_NAME) {
            readTitle(w);
        } else if (atom == aNetWmIcon) {
            readIcon(w);
        } else if (atom == X11.ATOM_WM_CLASS) {
            readClass(w);
        } else if (atom == aWmProtocols) {
            readProtocols(w);
            changed = false;
        } else if (atom == X11.ATOM_WM_HINTS) {
            readWmHints(w);
            changed = false;
        } else {
            changed = false;
        }
        if (changed && w.kind == KIND_WINDOW) host.changed(w);
    }

    private void onClientMessage(X11.Event e) throws IOException {
        int window = e.u32(4);
        int type = e.u32(8);
        int d0 = e.u32(12);
        int d1 = e.u32(16);
        int d2 = e.u32(20);
        Win w = win(window);
        if (type == aNetRequestFrameExtents) {
            x.changeProperty32(window, aNetFrameExtents, X11.ATOM_CARDINAL, 0, 0, 0, 0);
            return;
        }
        if (w == null || w.kind != KIND_WINDOW) return;
        if (type == aNetActive) {
            host.activate(w);
        } else if (type == aWmChangeState && d0 == 3) {
            host.minimise(w);
        } else if (type == aNetCloseWindow) {
            close(w);
        } else if (type == aNetWmState) {
            // d0: 0 remove, 1 add, 2 toggle; d1, d2: the states.
            if (d1 == aStateFullscreen || d2 == aStateFullscreen) {
                boolean on = d0 == 1 || (d0 == 2 && !w.fullscreen);
                w.fullscreen = on;
                setNetState(w);
                host.fullscreen(w, on);
            }
            if (d1 == aStateHidden && d0 == 1) host.minimise(w);
        } else if (type == aNetMoveResizeWindow) {
            int flags = d0 >> 8;
            int[] g = w.geometry();
            int nw = (flags & 4) != 0 ? e.u32(24) : g[2];
            int nh = (flags & 8) != 0 ? e.u32(28) : g[3];
            if (nw != g[2] || nh != g[3]) host.resize(w, nw, nh);
        }
    }

    private void setNetState(Win w) throws IOException {
        List<Integer> s = new ArrayList<>();
        if (w.fullscreen) s.add(aStateFullscreen);
        if (w.modal) s.add(aStateModal);
        if (w.skipTaskbar) s.add(aStateSkipTaskbar);
        int[] a = new int[s.size()];
        for (int i = 0; i < a.length; i++) a[i] = s.get(i);
        x.changeProperty32(w.id, aNetWmState, X11.ATOM_ATOM, a);
    }

    // ── reading what a window is ──

    private static int[] ints(X11.Pending p) throws IOException {
        try {
            X11.Property prop = X11.property(p.get());
            return prop == null || prop.format != 32 ? new int[0] : prop.ints();
        } catch (X11.XError e) {
            return new int[0];
        }
    }

    private void readTitle(Win w) throws IOException {
        X11.Property p = x.getProperty(w.id, aNetWmName, aUtf8, 1024);
        if (p == null) p = x.getProperty(w.id, X11.ATOM_WM_NAME, X11.ATOM_ANY, 1024);
        if (p == null) return;
        String s = p.type == X11.ATOM_STRING
                ? new String(p.value, StandardCharsets.ISO_8859_1).replace("\0", "")
                : p.string();
        w.title = s.trim();
    }

    private void readClass(Win w) throws IOException {
        X11.Property p = x.getProperty(w.id, X11.ATOM_WM_CLASS, X11.ATOM_STRING, 256);
        if (p == null) return;
        String[] parts = new String(p.value, StandardCharsets.ISO_8859_1).split("\0");
        if (parts.length > 0) w.wmInstance = parts[0];
        if (parts.length > 1) w.wmClass = parts[1];
    }

    private void readProtocols(Win w) throws IOException {
        X11.Property p = x.getProperty(w.id, aWmProtocols, X11.ATOM_ATOM, 32);
        w.deleteWindow = false;
        w.takeFocus = false;
        if (p == null) return;
        for (int a : p.ints()) {
            if (a == aWmDelete) w.deleteWindow = true;
            if (a == aWmTakeFocus) w.takeFocus = true;
        }
    }

    private void readWmHints(Win w) throws IOException {
        X11.Property p = x.getProperty(w.id, X11.ATOM_WM_HINTS, X11.ATOM_ANY, 9);
        if (p == null || p.format != 32) return;
        int[] h = p.ints();
        // InputHint (1): the input field says whether it takes focus.
        if (h.length >= 2 && (h[0] & 1) != 0) w.acceptsFocus = h[1] != 0;
    }

    private void readPid(Win w) throws IOException {
        X11.Property p = x.getProperty(w.id, aNetWmPid, X11.ATOM_CARDINAL, 1);
        if (p != null && p.format == 32 && p.value.length >= 4) w.pid = p.ints()[0];
    }

    /**
     * _NET_WM_ICON: the size nearest to the largest kept (opt.iconSize) from
     * above, else the largest there is. A few hundred KB at most; read once
     * and whenever it changes.
     */
    private void readIcon(Win w) throws IOException {
        X11.Property p = x.getProperty(w.id, aNetWmIcon, X11.ATOM_CARDINAL, 1 << 20);
        if (p == null || p.format != 32) return;
        int[] v = p.ints();
        int best = -1;
        int bestSize = 0;
        for (int i = 0; i + 2 <= v.length; ) {
            int iw = v[i];
            int ih = v[i + 1];
            if (iw <= 0 || ih <= 0 || (long) iw * ih > v.length - i - 2) break;
            int size = Math.max(iw, ih);
            boolean better = best < 0
                    || (bestSize < opt.iconSize && size > bestSize)
                    || (size >= opt.iconSize && size < bestSize);
            if (better) {
                best = i;
                bestSize = size;
            }
            i += 2 + iw * ih;
        }
        if (best < 0) return;
        int iw = v[best];
        int ih = v[best + 1];
        int[] argb = new int[iw * ih];
        System.arraycopy(v, best + 2, argb, 0, argb.length);
        w.icon = argb;
        w.iconWidth = iw;
        w.iconHeight = ih;
    }

    // ── the desktop's side ──

    /**
     * The Android window of {@code w} now shows its content at x, y,
     * width × height on the desktop display: put the X window exactly there.
     */
    void placed(Win w, int nx, int ny, int nw, int nh) {
        if (w.closed || nw <= 0 || nh <= 0) return;
        int[] g = w.geometry();
        w.placed = true;
        if (g[0] == nx && g[1] == ny && g[2] == nw && g[3] == nh) return;
        try {
            if (nw != g[2] || nh != g[3]) w.sizedAt = System.nanoTime() / 1_000_000;
            x.configureWindow(w.id, X11.CONFIG_X | X11.CONFIG_Y | X11.CONFIG_WIDTH | X11.CONFIG_HEIGHT,
                    nx, ny, nw, nh);
            // The geometry is ours to know now; the ConfigureNotify confirms it.
            synchronized (w) {
                boolean resized = nw != w.width || nh != w.height;
                w.x = nx;
                w.y = ny;
                w.width = nw;
                w.height = nh;
                if (resized) {
                    w.whole = true;
                    w.dirty.clear();
                }
            }
        } catch (IOException e) {
            // the connection is going; the loop says so
        }
    }

    /**
     * The desktop display's DPI changed (a desktop dragged onto a screen of
     * another scale): every app is told the new settings, which GTK applies
     * as it runs — scale factor included — and the rest at their next start.
     */
    void dpi(int dpi) {
        if (dpi == opt.dpi || dpi <= 0) return;
        opt.dpi = dpi;
        try {
            publishSettings();
            host.log("x11: settings at " + dpi + " dpi");
        } catch (IOException e) {
            host.log("x11: cannot publish the settings at " + dpi + " dpi: " + e.getMessage());
        }
    }

    /**
     * The desktop display is {@code w} × {@code h}: the X screen grows to it
     * if it is smaller — the pointer cannot go past the screen's edge, so a
     * part of the display beyond it could take no input. Never shrinks: a
     * window hanging off the display's edge is fine, and its pixels are its
     * own pixmap's either way.
     */
    void displaySize(int w, int h) {
        int nw = Math.max(screenWidth, w);
        int nh = Math.max(screenHeight, h);
        if (nw == screenWidth && nh == screenHeight) return;
        if (nw > 8192 || nh > 8192 || randr == null || !randr.present) return;
        try {
            x.randrSetScreenSize(randr, nw, nh);
            host.log("x11: screen grown to " + nw + "x" + nh);
        } catch (IOException e) {
            host.log("x11: cannot grow the screen to " + nw + "x" + nh + ": " + e.getMessage());
        }
    }

    /**
     * The Android window was minimised ({@code false}) or came back. A
     * window nobody can see is put at the bottom of the X stack, so it cannot
     * take clicks meant for one that is showing where it used to be.
     */
    void shown(Win w, boolean on) {
        w.shown = on;
        if (on || w.closed) return;
        try {
            x.configureWindow(w.id, X11.CONFIG_STACK_MODE, 1 /* Below */);
            synchronized (lock) {
                if (topmost == w.id) topmost = 0;
            }
        } catch (IOException ignored) {
        }
    }

    /** The Android window of {@code w} has the keyboard now; null — none of ours does. */
    void focus(Win w) {
        synchronized (lock) {
            focused = w;
            try {
                if (w == null || w.closed) {
                    // None: keys go nowhere until one of ours is in front again.
                    x.setInputFocus(0, 0, X11.CURRENT_TIME);
                    xFocus = 0;
                    return;
                }
                x.batch(() -> {
                    raise(w);
                    if (w.acceptsFocus) {
                        x.setInputFocus(w.id, 1, X11.CURRENT_TIME);
                        xFocus = w.id;
                    }
                    if (w.takeFocus) {
                        x.sendClientMessage(w.id, 0, w.id, aWmProtocols, aWmTakeFocus, X11.CURRENT_TIME);
                    }
                    x.changeProperty32(x.root, aNetActive, X11.ATOM_WINDOW, w.id);
                });
            } catch (IOException ignored) {
            }
        }
    }

    /** Under `lock`. */
    private void raise(Win w) throws IOException {
        if (topmost == w.id) return;
        x.configureWindow(w.id, X11.CONFIG_STACK_MODE, X11.STACK_ABOVE);
        topmost = w.id;
    }

    /** Ask the app to close the window — WM_DELETE_WINDOW if it takes that, else its connection is closed. */
    void close(Win w) {
        try {
            if (w.deleteWindow) {
                x.sendClientMessage(w.id, 0, w.id, aWmProtocols, aWmDelete, X11.CURRENT_TIME);
            } else {
                x.killClient(w.id);
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * The pointer, at screen (= X) coordinates, with these buttons down:
     * bit 0 left, 1 middle, 2 right. Pressing raises the window first, in the
     * same breath, so the click lands on the window Android just brought up.
     */
    void pointer(Win w, int px, int py, int newButtons) {
        synchronized (lock) {
            try {
                x.batch(() -> {
                    if (opt.raiseOnPointer && w != null && !w.closed && w.kind == KIND_WINDOW) raise(w);
                    if (w != null && !w.closed && (newButtons & ~buttons) != 0 && w.kind == KIND_WINDOW) {
                        raise(w);
                        // Click to focus, as any window manager does: the
                        // window Android just focused gets X's keyboard too,
                        // even if an app moved X's focus elsewhere meanwhile.
                        if (w.acceptsFocus && xFocus != w.id) {
                            x.setInputFocus(w.id, 1, X11.CURRENT_TIME);
                            xFocus = w.id;
                        }
                    }
                    if (px != pointerX || py != pointerY) {
                        x.xtestFakeInput(xtest, X11.MOTION_NOTIFY, 0, px, py);
                        pointerX = px;
                        pointerY = py;
                    }
                    int[] xButton = {1, 2, 3};
                    for (int b = 0; b < 3; b++) {
                        int bit = 1 << b;
                        if ((newButtons & bit) != 0 && (buttons & bit) == 0) {
                            x.xtestFakeInput(xtest, X11.BUTTON_PRESS, xButton[b], 0, 0);
                        } else if ((newButtons & bit) == 0 && (buttons & bit) != 0) {
                            x.xtestFakeInput(xtest, X11.BUTTON_RELEASE, xButton[b], 0, 0);
                        }
                    }
                    buttons = newButtons;
                });
                x.syncIfFarAhead();
            } catch (IOException ignored) {
            }
        }
    }

    /** Scroll by whole steps: up/left negative. Buttons 4–7, as every X toolkit reads a wheel. */
    void wheel(int px, int py, int steps, boolean horizontal) {
        wheel(null, px, py, steps, horizontal);
    }

    /** The wheel over {@code w}, which comes to the top first where windows share the screen's origin. */
    void wheel(Win w, int px, int py, int steps, boolean horizontal) {
        if (steps == 0) return;
        int button = horizontal ? (steps < 0 ? 6 : 7) : (steps < 0 ? 4 : 5);
        synchronized (lock) {
            try {
                x.batch(() -> {
                    if (opt.raiseOnPointer && w != null && !w.closed && w.kind == KIND_WINDOW) raise(w);
                    if (px != pointerX || py != pointerY) {
                        x.xtestFakeInput(xtest, X11.MOTION_NOTIFY, 0, px, py);
                        pointerX = px;
                        pointerY = py;
                    }
                    for (int i = 0; i < Math.min(Math.abs(steps), 10); i++) {
                        x.xtestFakeInput(xtest, X11.BUTTON_PRESS, button, 0, 0);
                        x.xtestFakeInput(xtest, X11.BUTTON_RELEASE, button, 0, 0);
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * A key. {@code typed}: a character the desktop's layout made — Shift and
     * Level 3 are set to whatever the X layout needs for it and put back
     * after, and the modifiers the user holds are let go around it if the
     * character came out of one (AltGr, a Mac's Option). Otherwise the key is
     * pressed as it is, with whatever modifiers are down: a shortcut, or a
     * named key.
     */
    void key(int keysym, boolean down, boolean typed) {
        synchronized (lock) {
            try {
                if (!down) {
                    int kc = keys.lentTo(keysym);
                    if (kc == 0) {
                        X11Keys.Place p = keys.find(keysym);
                        kc = p != null ? p.keycode : 0;
                    }
                    if (kc != 0 && held.remove(kc)) x.xtestFakeInput(xtest, X11.KEY_RELEASE, kc, 0, 0);
                    return;
                }
                X11Keys.Place p = keys.find(keysym);
                if (p == null) {
                    int[] loan = keys.lend(keysym, held);
                    if (loan != null) x.changeKeyboardMapping(loan[0], new int[]{keysym, keysym});
                    int kc = keys.lentTo(keysym);
                    if (kc == 0) return;
                    p = new X11Keys.Place(kc, 0);
                    typed = true;
                }
                final X11Keys.Place place = p;
                final boolean asTyped = typed;
                x.batch(() -> {
                    List<Integer> released = new ArrayList<>();
                    List<Integer> pressed = new ArrayList<>();
                    if (asTyped) {
                        int shift = keys.keycodeOf(X11Keys.XK_SHIFT_L);
                        boolean shiftDown = isHeld(X11Keys.XK_SHIFT_L) || isHeld(X11Keys.XK_SHIFT_R);
                        if (place.needsShift() && !shiftDown && shift != 0) {
                            pressed.add(shift);
                        } else if (!place.needsShift() && shiftDown) {
                            releaseHeld(released, X11Keys.XK_SHIFT_L, X11Keys.XK_SHIFT_R);
                        }
                        int l3 = keys.keycodeOf(X11Keys.XK_ISO_LEVEL3_SHIFT);
                        if (place.needsLevel3() && l3 != 0 && !held.contains(l3)) pressed.add(l3);
                        // Typed text is never a shortcut: Ctrl, Alt and Super come off
                        // around it (AltGr held as Alt, Option on a Mac).
                        releaseHeld(released, X11Keys.XK_ALT_L, X11Keys.XK_ALT_R, X11Keys.XK_CONTROL_L,
                                X11Keys.XK_CONTROL_R, X11Keys.XK_SUPER_L, X11Keys.XK_SUPER_R);
                    }
                    for (int kc : released) x.xtestFakeInput(xtest, X11.KEY_RELEASE, kc, 0, 0);
                    for (int kc : pressed) x.xtestFakeInput(xtest, X11.KEY_PRESS, kc, 0, 0);
                    x.xtestFakeInput(xtest, X11.KEY_PRESS, place.keycode, 0, 0);
                    held.add(place.keycode);
                    if (asTyped) {
                        // A typed character goes down and up at once, so the
                        // modifiers can be put back straight away.
                        x.xtestFakeInput(xtest, X11.KEY_RELEASE, place.keycode, 0, 0);
                        held.remove(place.keycode);
                    }
                    for (int i = pressed.size() - 1; i >= 0; i--) {
                        x.xtestFakeInput(xtest, X11.KEY_RELEASE, pressed.get(i), 0, 0);
                    }
                    for (int kc : released) x.xtestFakeInput(xtest, X11.KEY_PRESS, kc, 0, 0);
                });
                x.syncIfFarAhead();
            } catch (IOException ignored) {
            }
        }
    }

    /** Under `lock`. */
    private boolean isHeld(int keysym) {
        int kc = keys.keycodeOf(keysym);
        return kc != 0 && held.contains(kc);
    }

    /** Under `lock`: the held keycodes of these keysyms, into {@code out}. */
    private void releaseHeld(List<Integer> out, int... keysyms) {
        for (int ks : keysyms) {
            int kc = keys.keycodeOf(ks);
            if (kc != 0 && held.contains(kc) && !out.contains(kc)) out.add(kc);
        }
    }

    /**
     * A click on the bare X screen, where no window is: what a menu needs to
     * hear when the click that should close it went to an Android window, so
     * X never saw it. With a menu up the press goes to the menu's grab and
     * closes it; with nothing grabbing it lands on the root, which nothing
     * listens to.
     */
    void clickOutside() {
        int[] at = barePoint();
        if (at == null) return;
        synchronized (lock) {
            try {
                x.batch(() -> {
                    x.xtestFakeInput(xtest, X11.MOTION_NOTIFY, 0, at[0], at[1]);
                    x.xtestFakeInput(xtest, X11.BUTTON_PRESS, 1, 0, 0);
                    x.xtestFakeInput(xtest, X11.BUTTON_RELEASE, 1, 0, 0);
                    pointerX = at[0];
                    pointerY = at[1];
                });
            } catch (IOException ignored) {
            }
        }
    }

    /** A point of the screen no window covers — a corner, or the middle of an edge — or null. */
    private int[] barePoint() {
        int w = screenWidth - 1;
        int h = screenHeight - 1;
        int[][] candidates = {{w, h}, {0, h}, {w, 0}, {0, 0}, {w / 2, h}, {w, h / 2}, {0, h / 2}, {w / 2, 0}};
        List<Win> all = windows();
        for (int[] c : candidates) {
            boolean covered = false;
            for (Win win : all) {
                int[] g = win.geometry();
                if (c[0] >= g[0] && c[0] < g[0] + g[2] && c[1] >= g[1] && c[1] < g[1] + g[3]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return c;
        }
        return null;
    }

    /** Every key and button let go: the window lost focus with something held. */
    void releaseAll() {
        synchronized (lock) {
            try {
                x.batch(() -> {
                    for (int kc : held) x.xtestFakeInput(xtest, X11.KEY_RELEASE, kc, 0, 0);
                    held.clear();
                    for (int b = 0; b < 3; b++) {
                        if ((buttons & (1 << b)) != 0) x.xtestFakeInput(xtest, X11.BUTTON_RELEASE, b + 1, 0, 0);
                    }
                    buttons = 0;
                });
            } catch (IOException ignored) {
            }
        }
    }

    // ── pixels ──

    private void onDamage(X11.Event e) {
        Win w = win(e.u32(4));
        if (w == null) return;
        int rx = e.s16(16);
        int ry = e.s16(18);
        int rw = e.u16(20);
        int rh = e.u16(22);
        boolean wake;
        synchronized (w) {
            wake = !w.whole && w.dirty.isEmpty();
            if (!w.whole) {
                if (w.dirty.size() >= 64) {
                    w.whole = true;
                    w.dirty.clear();
                } else {
                    w.dirty.add(new int[]{rx, ry, rw, rh});
                }
            }
        }
        if (wake || w.whole) host.damaged(w);
    }

    /** Images over the socket land in these, so a frame allocates nothing; grown as needed. */
    private final ArrayList<byte[]> sinks = new ArrayList<>();

    /**
     * Read what changed in {@code w} since the last call and hand it to
     * {@code out}, one rectangle at a time. Returns the window's size the
     * rectangles belong to, or null when there was nothing to read or the
     * window is gone. On the desktop's render thread.
     *
     * <p>The damage is cleared in the same breath as the images are asked
     * for, and before them: whatever is drawn after the clear is reported
     * afresh, and whatever was drawn before it is in the images.
     */
    int[] fetch(Win w, Pixels out) {
        if (w.closed) return null;
        List<int[]> rects;
        int ww;
        int wh;
        boolean wholeFetch;
        synchronized (w) {
            if (!w.whole && w.dirty.isEmpty()) return null;
            ww = w.width;
            wh = w.height;
            wholeFetch = w.whole;
            if (w.whole) {
                rects = new ArrayList<>();
                rects.add(new int[]{0, 0, ww, wh});
            } else {
                rects = coalesce(w.dirty, ww, wh);
            }
            w.whole = false;
            w.dirty.clear();
        }
        if (ww <= 0 || wh <= 0) return null;
        try {
            List<X11.Pending> replies = new ArrayList<>();
            List<int[]> asked = new ArrayList<>();
            int[] offsets = new int[rects.size()];
            final List<int[]> fr = rects;
            x.batch(() -> {
                x.damageSubtract(damage, w.damage);
                int off = 0;
                int capacity = segment != null ? segment.buffer().capacity() : 0;
                for (int i = 0; i < fr.size(); i++) {
                    int[] r = fr.get(i);
                    int bytes = r[2] * r[3] * 4;
                    if (shm != null && off + bytes <= capacity) {
                        offsets[asked.size()] = off;
                        replies.add(x.shmGetImage(shm, w.id, r[0], r[1], r[2], r[3], shmSeg, off));
                        off += bytes;
                    } else {
                        offsets[asked.size()] = -1;
                        replies.add(x.getImage(w.id, r[0], r[1], r[2], r[3], sink(replies.size(), bytes)));
                    }
                    asked.add(r);
                }
            });
            out.begin(ww, wh, wholeFetch);
            for (int i = 0; i < replies.size(); i++) {
                int[] r = asked.get(i);
                X11.Reply rep;
                try {
                    rep = replies.get(i).get();
                } catch (X11.XError e) {
                    // Resized under us, or unmapped: the next fetch reads it whole.
                    w.invalidate();
                    return null;
                }
                int bytes = r[2] * r[3] * 4;
                ByteBuffer px;
                if (offsets[i] >= 0) {
                    ByteBuffer seg = segment.buffer().duplicate();
                    seg.position(offsets[i]).limit(offsets[i] + bytes);
                    px = seg.slice();
                } else {
                    if (rep.length < bytes) {
                        w.invalidate();
                        return null;
                    }
                    px = ByteBuffer.wrap(rep.data, 0, bytes);
                }
                out.rect(r[0], r[1], r[2], r[3], px);
            }
            return new int[]{ww, wh};
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] sink(int index, int bytes) {
        while (sinks.size() <= index) sinks.add(new byte[0]);
        byte[] b = sinks.get(index);
        if (b.length < bytes) {
            b = new byte[bytes];
            sinks.set(index, b);
        }
        return b;
    }

    /**
     * Damage rectangles, clipped to the window and made fewer: one bounding
     * box when that costs little more than the parts, which it nearly always
     * does for a toolkit repainting a region.
     */
    static List<int[]> coalesce(List<int[]> in, int ww, int wh) {
        List<int[]> clipped = new ArrayList<>();
        long area = 0;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int[] r : in) {
            int ax = Math.max(0, r[0]);
            int ay = Math.max(0, r[1]);
            int bx = Math.min(ww, r[0] + r[2]);
            int by = Math.min(wh, r[1] + r[3]);
            if (bx <= ax || by <= ay) continue;
            clipped.add(new int[]{ax, ay, bx - ax, by - ay});
            area += (long) (bx - ax) * (by - ay);
            x0 = Math.min(x0, ax);
            y0 = Math.min(y0, ay);
            x1 = Math.max(x1, bx);
            y1 = Math.max(y1, by);
        }
        if (clipped.size() <= 1) return clipped;
        long box = (long) (x1 - x0) * (y1 - y0);
        if (box <= area * 2 || clipped.size() > 8) {
            List<int[]> one = new ArrayList<>();
            one.add(new int[]{x0, y0, x1 - x0, y1 - y0});
            return one;
        }
        return clipped;
    }

    // ── the cursor ──

    private void onCursor(int serial) throws IOException {
        if (serial == cursorSerial) return;
        X11.Reply r = x.xfixesGetCursorImage(xfixes).get();
        int w = r.u16(12);
        int h = r.u16(14);
        int hx = r.u16(16);
        int hy = r.u16(18);
        cursorSerial = r.u32(20);
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length && i * 4 + 3 < r.length; i++) argb[i] = r.dataU32(i * 4);
        host.cursor(new Cursor(cursorSerial, w, h, hx, hy, argb));
    }

    // ── the clipboard ──

    /**
     * The desktop's clipboard, for Linux: this window manager holds CLIPBOARD
     * and PRIMARY both and hands {@code text} to whoever pastes — Ctrl+V,
     * Shift+Insert in a terminal (which reads PRIMARY) and a middle click
     * alike, as Xvnc does for the desktop on :1. Empty takes it back.
     *
     * The ownership goes out from the calling thread, at once: a paste key
     * sent right after this must find the new owner, and requests on the one
     * connection are done in the order they were sent.
     */
    void paste(String text) {
        try {
            if (text == null || text.isEmpty()) {
                clipText = null;
                if (clipOwned) {
                    x.setSelectionOwner(0, aClipboard, X11.CURRENT_TIME);
                    x.setSelectionOwner(0, X11.ATOM_PRIMARY, X11.CURRENT_TIME);
                }
                clipOwned = false;
                return;
            }
            clipText = text;
            x.setSelectionOwner(self, aClipboard, X11.CURRENT_TIME);
            x.setSelectionOwner(self, X11.ATOM_PRIMARY, X11.CURRENT_TIME);
            clipOwned = true;
        } catch (IOException ignored) {
        }
    }

    /** Someone in Linux took CLIPBOARD: ask them for its text. */
    private void onSelectionOwner(int owner, int selection) throws IOException {
        if (selection != aClipboard || owner == self || owner == 0) return;
        clipOwned = false;
        x.convertSelection(self, aClipboard, aUtf8, aOurSelection, X11.CURRENT_TIME);
    }

    private void onSelectionNotify(int selection, int property) throws IOException {
        if (selection != aClipboard) return;
        if (property == 0) return; // the owner had no text
        X11.Property p = x.getProperty(self, aOurSelection, X11.ATOM_ANY, 1 << 20);
        if (p == null) return;
        if (p.type == aIncr) {
            // Too large for one property: it comes in pieces, each one a
            // PropertyNotify, the last of them empty (ICCCM 2.7.2).
            incr.reset();
            incrActive = true;
            x.deleteProperty(self, aOurSelection);
            return;
        }
        x.deleteProperty(self, aOurSelection);
        deliverCopied(p.value, p.type);
    }

    private void readIncrChunk() throws IOException {
        X11.Property p = x.getProperty(self, aOurSelection, X11.ATOM_ANY, 1 << 20);
        if (p == null) return;
        x.deleteProperty(self, aOurSelection);
        if (p.value.length == 0) {
            incrActive = false;
            deliverCopied(incr.toByteArray(), p.type);
            incr.reset();
            return;
        }
        if (incr.size() + p.value.length <= COPY_MAX) incr.write(p.value, 0, p.value.length);
    }

    private void deliverCopied(byte[] value, int type) {
        String text = type == X11.ATOM_STRING
                ? new String(value, StandardCharsets.ISO_8859_1)
                : new String(value, StandardCharsets.UTF_8);
        if (text.length() > COPY_MAX) text = text.substring(0, COPY_MAX);
        host.copied(text);
    }

    /** Someone is pasting what we hold. */
    private void onSelectionRequest(int time, int requestor, int selection, int target, int property)
            throws IOException {
        if (property == 0) property = target; // an obsolete client (ICCCM 2.2)
        boolean ok = false;
        String text = clipText;
        if ((selection == aClipboard || selection == X11.ATOM_PRIMARY) && text != null) {
            if (target == aTargets) {
                x.changeProperty32(requestor, property, X11.ATOM_ATOM,
                        aTargets, aUtf8, aTextPlainUtf8, X11.ATOM_STRING, aText, aTextPlain, aTimestamp);
                ok = true;
            } else if (target == aUtf8 || target == aTextPlainUtf8 || target == aText) {
                byte[] b = text.getBytes(StandardCharsets.UTF_8);
                if (fits(b.length)) {
                    x.changeProperty(requestor, property, target == aText ? aUtf8 : target, 8, b, b.length);
                    ok = true;
                }
            } else if (target == X11.ATOM_STRING || target == aTextPlain) {
                byte[] b = text.getBytes(StandardCharsets.ISO_8859_1);
                if (fits(b.length)) {
                    x.changeProperty(requestor, property, X11.ATOM_STRING, 8, b, b.length);
                    ok = true;
                }
            } else if (target == aTimestamp) {
                x.changeProperty32(requestor, property, X11.ATOM_CARDINAL, 0);
                ok = true;
            }
        }
        ByteBuffer e = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        e.put((byte) X11.SELECTION_NOTIFY).put((byte) 0).putShort((short) 0).putInt(time).putInt(requestor)
                .putInt(selection).putInt(target).putInt(ok ? property : 0);
        x.sendEvent(requestor, false, 0, e.array());
    }

    /** A property this long goes in one request. */
    private boolean fits(int bytes) {
        return bytes + 64 <= x.maxRequestLength * 4;
    }

    // ── for tests and the desktop ──

    List<Win> windows() {
        synchronized (lock) {
            return new ArrayList<>(wins.values());
        }
    }

    int screenWidth() {
        return screenWidth;
    }

    int screenHeight() {
        return screenHeight;
    }

    boolean sharedMemory() {
        return shm != null;
    }
}
