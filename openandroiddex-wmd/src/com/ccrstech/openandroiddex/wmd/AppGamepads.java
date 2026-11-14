package com.ccrstech.openandroiddex.wmd;

import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputDevice;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The computer's game controllers, as the phone's own (doc/game-mode.md).
 *
 * <p>Each controller the computer reads becomes a gamepad on the phone, made
 * on {@code /dev/uhid} — which the shell's uid may open — with the identity of
 * an Xbox 360 pad, so Android loads the key layout every phone ships for it
 * and a game sees an ordinary controller: no injected key events, which a game
 * would see coming from no device at all. The computer sends each
 * controller's whole state on the window that is in front (Wire's 0x25); this
 * turns it into the pad's input report.
 *
 * <p>Whose a pad's input is, is the window's: the pad is tied to that window's
 * display ({@code IInputManager.addUniqueIdAssociationByPort}, the pad's
 * {@code phys} being its "port"), so it presses that app and no other, whatever
 * display has Android's focus — the phone's own screen in its owner's hand
 * included. It is made tied to a display that does not exist, which Android
 * disables it for, so it is never free for a moment; and a window whose display
 * goes leaves its pad disabled by Android itself. Where the phone will not tie
 * one (below Android 15 the shell may not), the pad's input goes wherever the
 * focus is: the window's display is made the focused one before the pad is
 * made, and looked at again, at most every {@link #CHECK_MS}, while its reports
 * change — the keyboard's way here. Android 12 and older make a display the
 * shell may not trust the focused one only when it is touched: until the window
 * is clicked, its pad's input is held, never sent to the phone's own screen or
 * another window's app, and the computer is told.
 *
 * <p>The phone's own screen can be one more controller ({@link AppController}):
 * its pad is made the same way, in a slot of its own ({@link #PHONE}) that the
 * computer's reports never reach, for the window in front on the computer.
 *
 * <p>A pad goes when its window ends, when the computer says its controller
 * went — the phone's own, when it closes — and when the server stops. A server that is killed loses its pads with
 * its file descriptors, but its ties would stay in system_server until the
 * phone restarts: so the ports it made are written down as it makes them, and
 * the next server takes back the ties of one that is no longer running.
 */
final class AppGamepads {

    /** The computer's controllers at once: slots 0 to 3. */
    static final int SLOTS = 4;
    /**
     * The phone's own screen as a controller (AppController): a slot past the
     * computer's, which the computer's reports never reach.
     */
    static final int PHONE = SLOTS;
    /** The report the computer sends: buttons u16, hat u8, four i16 sticks, two u8 triggers, little-endian. */
    static final int REPORT = 13;

    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final short BUS_VIRTUAL = 0x06;
    private static final int VENDOR = 0x045e;
    private static final int PRODUCT = 0x028e;
    /**
     * The name Android shows. scrcpy's own pads end in "Pad", not "pad", so a
     * new device of this exact name is one of ours ({@link #ready}).
     */
    static final String NAME = "Microsoft X-Box 360 pad";
    /** A display unique id no display has: a pad tied to it is disabled by Android. */
    private static final String NOWHERE = "oadx:nowhere";
    /**
     * How long a new or re-tied pad may take to be where it was tied before its
     * report goes anyway: 32 to 51 ms on the S24. Short, as the window's input
     * thread waits for it.
     */
    private static final long READY_MS = 500;
    /**
     * The focus route: whether the window's display has the focus — made so if
     * it can be — is looked at most this often while reports change. Between
     * two looks a touch on the phone's own screen takes the pad's input there.
     */
    private static final long CHECK_MS = 100;
    private static final File MARKERS = new File("/data/local/tmp");
    private static final String MARKER_PREFIX = "oadx-pads-";

    /**
     * A gamepad as the Xbox 360 key layout reads it: 15 buttons, a hat, two
     * sticks, two triggers — one 13-byte report. Written from the HID usage
     * tables; one report with everything held gave every BTN_ and ABS_ the
     * layout names (doc/game-mode.md).
     */
    static final byte[] DESCRIPTOR = bytes(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x05,       // Usage (Game Pad)
            0xA1, 0x01,       // Collection (Application)
            0x05, 0x09,       //   Usage Page (Button)
            0x19, 0x01,       //   Usage Minimum (1)
            0x29, 0x0F,       //   Usage Maximum (15)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x0F,       //   Report Count (15)
            0x81, 0x02,       //   Input (Data,Var,Abs)
            0x95, 0x01,       //   Report Count (1)
            0x81, 0x03,       //   Input (Const)            1 bit of padding
            0x05, 0x01,       //   Usage Page (Generic Desktop)
            0x09, 0x39,       //   Usage (Hat switch)
            0x15, 0x01,       //   Logical Minimum (1)
            0x25, 0x08,       //   Logical Maximum (8)
            0x35, 0x00,       //   Physical Minimum (0)
            0x46, 0x3B, 0x01, //   Physical Maximum (315)
            0x65, 0x14,       //   Unit (degrees)
            0x75, 0x04,       //   Report Size (4)
            0x95, 0x01,       //   Report Count (1)
            0x81, 0x42,       //   Input (Data,Var,Abs,Null)
            0x65, 0x00,       //   Unit (none)
            0x35, 0x00,       //   Physical Minimum (0)
            0x45, 0x00,       //   Physical Maximum (0)     as the logical ones, from here on
            0x95, 0x01,       //   Report Count (1)
            0x81, 0x03,       //   Input (Const)            4 bits of padding
            0x09, 0x30,       //   Usage (X)                left stick
            0x09, 0x31,       //   Usage (Y)
            0x09, 0x33,       //   Usage (Rx)               right stick
            0x09, 0x34,       //   Usage (Ry)
            0x16, 0x00, 0x80, //   Logical Minimum (-32768)
            0x26, 0xFF, 0x7F, //   Logical Maximum (32767)
            0x75, 0x10,       //   Report Size (16)
            0x95, 0x04,       //   Report Count (4)
            0x81, 0x02,       //   Input (Data,Var,Abs)
            0x09, 0x32,       //   Usage (Z)                left trigger
            0x09, 0x35,       //   Usage (Rz)               right trigger
            0x15, 0x00,       //   Logical Minimum (0)
            0x26, 0xFF, 0x00, //   Logical Maximum (255)
            0x75, 0x08,       //   Report Size (8)
            0x95, 0x02,       //   Report Count (2)
            0x81, 0x02,       //   Input (Data,Var,Abs)
            0xC0);            // End Collection

    private static final byte[] NEUTRAL = new byte[REPORT];

    /** One controller's pad on the phone. */
    private static final class Pad {
        final int slot;
        final String port;
        final FileDescriptor fd;
        /** The window whose display it is tied to (or, on the focus route, aimed at); null before the first. */
        AppWindow owner;
        byte[] last = NEUTRAL;
        /** The focus route: when it was last looked whether the owner's display has the focus, and whether it had. */
        long checkedAt;
        boolean here;
        /** The focus route: its input is held for want of the focus, and the computer was told. */
        boolean held;
        /** Android's id for it, once seen; -1 before. */
        int deviceId = -1;
        /** Destroyed: its reading thread ends. */
        volatile boolean gone;

        Pad(int slot, String port, FileDescriptor fd) {
            this.slot = slot;
            this.port = port;
            this.fd = fd;
        }
    }

    private final String suffix;
    private final Pad[] pads = new Pad[SLOTS + 1];
    /** Every port this run has made, for the marker and the end. */
    private final Set<String> ports = new LinkedHashSet<>();
    private final File marker;
    /** Null until the first tie is tried; false where the phone will not tie a pad to a display. */
    private Boolean ties;
    /** Why /dev/uhid could not be used here, once it could not; null while it can. */
    private String refused;
    private static Object inputManager;

    /** The pads of the server on socket {@code socketName}: its last part names its ports. */
    AppGamepads(String socketName) {
        String s = socketName.substring(socketName.lastIndexOf('-') + 1).replaceAll("[^0-9A-Za-z]", "");
        suffix = s.isEmpty() ? Integer.toHexString(Process.myPid()) : s.substring(0, Math.min(16, s.length()));
        marker = new File(MARKERS, MARKER_PREFIX + suffix);
        sweep();
    }

    /**
     * {@code report} is the state of the computer's controller in {@code slot},
     * said on window {@code w}: the pad for it made if there is none, tied to
     * {@code w}'s display if it is another's, and the report written if it
     * differs from the last. A sentence for the computer if no pad can be made
     * on this phone, else null.
     */
    synchronized String report(AppWindow w, int slot, byte[] report) {
        if (slot < 0 || slot >= SLOTS) return null;
        return put(w, slot, report);
    }

    /**
     * The phone's own controller's state, for window {@code w} — the one in
     * front on the computer (AppController): as {@link #report}, in its own slot.
     */
    synchronized String phone(AppWindow w, byte[] report) {
        return put(w, PHONE, report);
    }

    /** Whether the pad in {@code slot} is held for want of the phone's focus (the focus route). */
    synchronized boolean held(int slot) {
        Pad p = slot >= 0 && slot < pads.length ? pads[slot] : null;
        return p != null && p.held;
    }

    private String put(AppWindow w, int slot, byte[] report) {
        if (report.length != REPORT) return null;
        if (refused != null) return refused;
        Pad p = pads[slot];
        if (p == null) {
            try {
                p = make(slot, w);
            } catch (Unfinished e) {
                AppServer.log("game controller " + slot + " could not be finished (" + e.getMessage() + "); the next report tries again");
                return null;
            } catch (Exception e) {
                refused = String.valueOf(e.getMessage());
                AppServer.log("a game controller cannot be made on this phone: " + refused);
                return refused;
            }
            pads[slot] = p;
        }
        try {
            if (p.owner != w) {
                // Let go where it was, then aimed at this window: the person's controller went to another window.
                if (!Arrays.equals(p.last, NEUTRAL)) {
                    write(p, NEUTRAL);
                    SystemClock.sleep(30);
                }
                aim(p, w);
                p.owner = w;
                p.last = NEUTRAL;
                p.held = false;
            }
            if (Arrays.equals(report, p.last)) return null;
            if (Boolean.FALSE.equals(ties) && !focused(p, w)) {
                // Its input would go to the display that has the focus: the phone's own
                // screen, or another window's app. Held, and let go there, until this one has it.
                if (!Arrays.equals(p.last, NEUTRAL)) {
                    write(p, NEUTRAL);
                    p.last = NEUTRAL;
                }
                if (!p.held) {
                    p.held = true;
                    AppServer.log("game controller " + slot + ": the window on display " + w.displayId()
                            + " does not have the phone's focus; its input waits for a click there");
                    w.padWaitsForClick(slot);
                }
                return null;
            }
            if (p.held) {
                p.held = false;
                AppServer.log("game controller " + slot + ": the window on display " + w.displayId() + " has the focus now");
            }
            write(p, report);
            p.last = report.clone();
        } catch (Exception e) {
            AppServer.log("game controller " + slot + ": " + e.getMessage());
            destroy(p);
        }
        return null;
    }

    /**
     * {@code w} lost the computer's focus, or its connection: its pads let go, still tied to it.
     * Not the phone's own controller's, which plays on while the computer's front is something
     * else (AppController): its state stands until its fingers change it.
     */
    synchronized void rest(AppWindow w) {
        for (Pad p : pads) {
            if (p == null || p.slot == PHONE || p.owner != w || Arrays.equals(p.last, NEUTRAL)) continue;
            try {
                write(p, NEUTRAL);
                p.last = NEUTRAL;
            } catch (Exception e) {
                destroy(p);
            }
        }
    }

    /** {@code w} ended: its pads go from the phone. */
    synchronized void ended(AppWindow w) {
        for (Pad p : pads) {
            if (p != null && p.owner == w) destroy(p);
        }
    }

    /** The computer's controller in {@code slot} went: its pad goes. */
    synchronized void gone(int slot) {
        if (slot >= 0 && slot < SLOTS && pads[slot] != null) destroy(pads[slot]);
    }

    /** The phone's own controller closed: its pad goes, as a controller unplugged. */
    synchronized void phoneGone() {
        if (pads[PHONE] != null) destroy(pads[PHONE]);
    }

    /** The phone's own controller has no window to play in: what its pad holds is let go where it is. */
    synchronized void phoneRest() {
        Pad p = pads[PHONE];
        if (p == null || Arrays.equals(p.last, NEUTRAL)) return;
        try {
            write(p, NEUTRAL);
            p.last = NEUTRAL;
        } catch (Exception e) {
            destroy(p);
        }
    }

    /** The server is stopping: every pad and every tie goes, and the marker. */
    synchronized void stop() {
        for (Pad p : pads) {
            if (p != null) destroy(p);
        }
        for (String port : ports) untie(port);
        if (marker.exists() && !marker.delete()) AppServer.log("game controllers: " + marker + " is left");
    }

    // ── making one ──

    private Pad make(int slot, AppWindow w) throws Exception {
        String port = "oadx-pad:" + suffix + ":" + slot;
        boolean tied = false;
        if (!Boolean.FALSE.equals(ties)) {
            // Disabled from its first moment: never a pad whose input goes wherever the phone's focus is.
            tied = tie(port, NOWHERE);
        }
        if (!tied) w.focusHere();
        Set<Integer> before = deviceIds();
        FileDescriptor fd;
        try {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0);
        } catch (ErrnoException e) {
            if (tied) untie(port);
            throw new IOException("/dev/uhid: " + e.getMessage());
        }
        try {
            byte[] req = create(port);
            Os.write(fd, req, 0, req.length);
        } catch (Exception e) {
            closeQuietly(fd);
            if (tied) untie(port);
            throw new IOException("UHID_CREATE2: " + e.getMessage());
        }
        Pad p = new Pad(slot, port, fd);
        try {
            drain(p);
            if (ports.add(port)) writeMarker();
            long t0 = SystemClock.uptimeMillis();
            aim(p, w, before);
            p.owner = w;
            AppServer.log("game controller " + slot + " made for window on display " + w.displayId()
                    + (tied ? ", tied to it" : ", by the focus") + ", ready in " + (SystemClock.uptimeMillis() - t0) + " ms");
            return p;
        } catch (Throwable t) {
            // Made, but not finished: taken off the phone again, and the next report tries anew.
            destroy(p);
            throw new Unfinished(t);
        }
    }

    /** A pad that was made but could not be finished: no reason to refuse controllers for the run. */
    private static final class Unfinished extends Exception {
        Unfinished(Throwable cause) {
            super(String.valueOf(cause), cause);
        }
    }

    /** The CREATE2 request: struct uhid_create2_req after its type, then the descriptor. */
    private static byte[] create(String port) {
        ByteBuffer b = ByteBuffer.allocate(4 + 276 + DESCRIPTOR.length).order(ByteOrder.nativeOrder());
        b.putInt(UHID_CREATE2);
        b.put(NAME.getBytes(StandardCharsets.UTF_8));   // name[128]
        b.position(4 + 128);
        b.put(port.getBytes(StandardCharsets.US_ASCII)); // phys[64]
        b.position(4 + 128 + 64 + 64);                  // uniq[64] left empty
        b.putShort((short) DESCRIPTOR.length);
        b.putShort(BUS_VIRTUAL);
        b.putInt(VENDOR);
        b.putInt(PRODUCT);
        b.putInt(0); // version
        b.putInt(0); // country
        b.put(DESCRIPTOR);
        return b.array();
    }

    /**
     * What the kernel says back (start, open, close, output reports for rumble)
     * is read and dropped. Waited for with a timeout: closing the descriptor
     * does not wake a read blocked on it, and the thread must end with its pad.
     */
    private static void drain(Pad p) {
        Thread t = new Thread(() -> {
            ByteBuffer ev = ByteBuffer.allocate(4380);
            android.system.StructPollfd[] fds = {new android.system.StructPollfd()};
            fds[0].fd = p.fd;
            fds[0].events = (short) OsConstants.POLLIN;
            try {
                while (!p.gone) {
                    fds[0].revents = 0;
                    if (Os.poll(fds, 500) <= 0 || p.gone) continue;
                    ev.clear();
                    if (Os.read(p.fd, ev) <= 0) return;
                }
            } catch (Throwable ignored) {
                // closed: the pad went
            }
        }, "apps-pad-" + p.slot);
        t.setDaemon(true);
        t.start();
    }

    private void aim(Pad p, AppWindow w) {
        aim(p, w, null);
    }

    /**
     * {@code p}'s input to {@code w}'s app: tied to its display, or that display
     * made the focused one. Waits until Android has the pad there — a report
     * written before would be dropped, or land where it was — or
     * {@link #READY_MS}. {@code before}: the input devices there were before
     * {@code p} was made, to tell the new one from the rest; null for a pad
     * that exists already.
     */
    private void aim(Pad p, AppWindow w, Set<Integer> before) {
        boolean tied = false;
        if (!Boolean.FALSE.equals(ties)) {
            String unique = w.displayUniqueId();
            tied = unique != null && tie(p.port, unique);
        }
        if (!tied) {
            p.checkedAt = SystemClock.uptimeMillis();
            p.here = w.focusHere();
        }
        long until = SystemClock.uptimeMillis() + READY_MS;
        int display = w.displayId();
        while (SystemClock.uptimeMillis() < until) {
            if (ready(p, before, display, tied)) return;
            SystemClock.sleep(10);
        }
        AppServer.log("game controller " + p.slot + ": not seen on display " + display + " after " + READY_MS + " ms; its report goes anyway");
    }

    /**
     * Whether Android has {@code p} enabled — and, where it says which display
     * a device is tied to, tied to {@code display}. A pad not seen yet is the
     * new device of our name, one not in {@code before}.
     */
    private static boolean ready(Pad p, Set<Integer> before, int display, boolean tied) {
        if (p.deviceId < 0) {
            if (before == null) return false;
            for (int id : deviceIds()) {
                if (before.contains(id)) continue;
                InputDevice d = device(id);
                if (d != null && NAME.equals(d.getName())) {
                    p.deviceId = id;
                    break;
                }
            }
            if (p.deviceId < 0) return false;
        }
        InputDevice d = device(p.deviceId);
        if (d == null || !d.isEnabled()) return false;
        if (!tied) return true;
        Integer at = associatedDisplay(d);
        return at == null || at == display;
    }

    /**
     * The focus route: whether {@code w}'s display has the phone's focus, where
     * {@code p}'s input goes — made so where it can be. Looked at most every
     * {@link #CHECK_MS}: a binder call or two, and a stick's reports come many
     * times a second.
     */
    private static boolean focused(Pad p, AppWindow w) {
        long now = SystemClock.uptimeMillis();
        if (now - p.checkedAt >= CHECK_MS) {
            p.checkedAt = now;
            p.here = w.focusHere();
        }
        return p.here;
    }

    // ── writing to it ──

    private static void write(Pad p, byte[] report) throws ErrnoException, IOException {
        ByteBuffer b = ByteBuffer.allocate(6 + report.length).order(ByteOrder.nativeOrder());
        b.putInt(UHID_INPUT2);
        b.putShort((short) report.length);
        b.put(report);
        Os.write(p.fd, b.array(), 0, b.capacity());
    }

    /** {@code p} goes: destroyed, closed — which takes it off the phone — and then its tie, so it is never free. */
    private void destroy(Pad p) {
        p.gone = true;
        try {
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
            b.putInt(UHID_DESTROY);
            Os.write(p.fd, b.array(), 0, 4);
        } catch (Throwable ignored) {
            // closing takes it off as well
        }
        closeQuietly(p.fd);
        if (pads[p.slot] == p) pads[p.slot] = null;
        if (!Boolean.FALSE.equals(ties)) untie(p.port);
        AppServer.log("game controller " + p.slot + " gone");
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (Throwable ignored) {
        }
    }

    // ── ties: an input port to a display ──

    /**
     * {@code port} tied to the display {@code uniqueId}. False, and the focus
     * route for the rest of the run, where the phone has no such call for the
     * shell or refuses it.
     */
    private boolean tie(String port, String uniqueId) {
        if (Boolean.FALSE.equals(ties)) return false;
        try {
            Object im = manager();
            String name = Refl.hasMethod(im.getClass(), "addUniqueIdAssociationByPort", String.class, String.class)
                    ? "addUniqueIdAssociationByPort" : "addUniqueIdAssociation";
            Refl.callSig(im, name, new Class<?>[]{String.class, String.class}, port, uniqueId);
            if (ties == null) AppServer.log("game controllers are tied to their window's display (" + name + ")");
            ties = true;
            return true;
        } catch (Throwable e) {
            if (Boolean.TRUE.equals(ties)) {
                // It tied before: this one failed for itself.
                AppServer.log("game controller tie " + port + " failed: " + e.getMessage());
                return false;
            }
            ties = false;
            AppServer.log("this phone does not tie a game controller to a display (" + e.getMessage()
                    + "): the window's display is made the focused one instead");
            return false;
        }
    }

    private static void untie(String port) {
        try {
            Object im = manager();
            String name = Refl.hasMethod(im.getClass(), "removeUniqueIdAssociationByPort", String.class)
                    ? "removeUniqueIdAssociationByPort" : "removeUniqueIdAssociation";
            Refl.callSig(im, name, new Class<?>[]{String.class}, port);
        } catch (Throwable ignored) {
            // nothing tied, or nothing to tie with
        }
    }

    // ── what is left of a killed server ──

    /** This run's ports, for the next server should this one be killed. */
    private void writeMarker() {
        StringBuilder s = new StringBuilder().append(Process.myPid()).append('\n');
        for (String port : ports) s.append(port).append('\n');
        File tmp = new File(MARKERS, marker.getName() + ".new");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppServer.log("game controllers: cannot write " + tmp + " (" + e.getMessage() + ")");
            return;
        }
        if (!tmp.renameTo(marker)) AppServer.log("game controllers: cannot write " + marker);
    }

    /** The ties of servers that are no longer running taken back, and their markers. */
    private void sweep() {
        File[] files = MARKERS.listFiles((dir, name) -> name.startsWith(MARKER_PREFIX));
        if (files == null) return;
        for (File f : files) {
            if (f.equals(marker)) continue;
            try {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.US_ASCII);
                if (!lines.isEmpty() && running(lines.get(0).trim())) continue;
                int n = 0;
                for (String port : lines.subList(Math.min(1, lines.size()), lines.size())) {
                    if (port.trim().isEmpty()) continue;
                    untie(port.trim());
                    n++;
                }
                if (f.delete()) AppServer.log("game controllers: took back " + n + " tie(s) a stopped server left (" + f.getName() + ")");
            } catch (Exception e) {
                AppServer.log("game controllers: cannot sweep " + f + " (" + e.getMessage() + ")");
            }
        }
    }

    /** Whether process {@code pid} is an app server still. */
    private static boolean running(String pid) {
        try {
            byte[] cmd = Files.readAllBytes(new File("/proc/" + Integer.parseInt(pid) + "/cmdline").toPath());
            return new String(cmd, StandardCharsets.US_ASCII).contains("AppServer");
        } catch (Exception e) {
            return false;
        }
    }

    // ── the input service ──

    private static synchronized Object manager() {
        if (inputManager == null) {
            IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                    "getService", new Class<?>[]{String.class}, "input");
            if (b == null) throw new Refl.WmError("no input service");
            inputManager = Refl.callStatic(Refl.cls("android.hardware.input.IInputManager$Stub"),
                    "asInterface", new Class<?>[]{IBinder.class}, b);
        }
        return inputManager;
    }

    private static Set<Integer> deviceIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        try {
            for (int id : (int[]) Refl.callSig(manager(), "getInputDeviceIds", new Class<?>[]{})) ids.add(id);
        } catch (Throwable ignored) {
            // the wait gives up on its own
        }
        return ids;
    }

    private static InputDevice device(int id) {
        try {
            return (InputDevice) Refl.callSig(manager(), "getInputDevice", new Class<?>[]{int.class}, id);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The display Android has {@code d} tied to (hidden, API 29+); null where it will not say. */
    private static Integer associatedDisplay(InputDevice d) {
        try {
            Object v = Refl.callSig(d, "getAssociatedDisplayId", new Class<?>[]{});
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
