package com.ccrstech.openandroiddex.launcher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * LinuxService's end of {@link LinuxAppsService}: puts the guest's half in
 * place, keeps ":linuxapps" up while a Linux session runs, and closes a
 * session's apps when the session goes.
 *
 * <p>The binding is the whole point, more than the messages: it is what sets
 * the importance Android ranks ":linuxapps" by. {@code BIND_NOT_PERCEPTIBLE}
 * from a client at 200 or better (":linux" runs a foreground service) puts the
 * service at 250, below the client and never level with it. Level would not
 * do: Android then picks by age across both processes, and the desktop is
 * older than every app.
 *
 * <p>Only on Android 12 and later, where the limit exists. Before that the
 * guest's half is not put in place and every app starts in the desktop's
 * process, as it always did.
 */
final class LinuxAppsLink {

    /** The display numbers linux-rt.sh and linux-win.sh run. */
    static final String DESKTOP = ":1";
    static final String WINDOWS = ":2";

    /**
     * The guest's name for this app's own Android user, which the sessions'
     * D-Bus policy lets in (assets/linux/guest/oadx-apps-dbus.conf says why).
     */
    static final String GUEST_USER = "oadx-android";

    /**
     * The bus resolves a policy's user through /etc/passwd ("Unknown username
     * 10556" for a bare number, seen on the S25), so this app's Android user
     * gets a line there: no login, no home. The uid is this install's, so the
     * line is put right at every start; the rest of the file is left as it is.
     */
    static void passwdUser(File passwd, int uid) throws Exception {
        if (!passwd.isFile()) return;
        String old = new String(java.nio.file.Files.readAllBytes(passwd.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String text = passwdText(old, uid);
        if (text.equals(old)) return;
        int mode = 0644;
        try {
            mode = Os.stat(passwd.getAbsolutePath()).st_mode & 0777;
        } catch (ErrnoException | RuntimeException ignored) {
            // the usual one
        }
        write(passwd, text, mode);
    }

    /** /etc/passwd with this app's user line put right, and every other line as it was. */
    static String passwdText(String old, int uid) {
        String line = GUEST_USER + ":x:" + uid + ":" + uid
                + ":LinuxOnDroid app user (oadx-run):/nonexistent:/usr/sbin/nologin";
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(old.split("\n", -1)));
        // The empty piece after the file's last newline; any other empty line stays.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        lines.removeIf(l -> l.startsWith(GUEST_USER + ":"));
        lines.add(line);
        return String.join("\n", lines) + "\n";
    }

    private final Service owner;
    /** The binding held, null when none: each binding is a connection of its own. */
    private Conn conn;
    private Messenger remote;
    private final List<Message> pending = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** When the apps process last connected, and the wait before the last rebind. */
    private long connectedAt;
    private long rebindDelayMs;

    /**
     * One binding. A callback from one that is no longer the binding held (let
     * go, or replaced by a rebind) is about a binding that is gone, and changes
     * nothing.
     */
    private final class Conn implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LinuxAppsLink.this) {
                if (conn != this) return;
                remote = new Messenger(service);
                connectedAt = android.os.SystemClock.elapsedRealtime();
                for (Message m : pending) deliver(m);
                pending.clear();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Its process died, and Android took its apps with it. Android does
            // not always bring a bound service back: after a crash it left this
            // one down with the binding still in place (S25, Android 17), and
            // every app started meanwhile ran in the desktop's process. So this
            // side binds again, a second later, doubling the wait each time it
            // happens again within a minute, so a process that dies at once is
            // not started over and over. Should Android bring it back first,
            // the rebind finds it connected and leaves it be.
            long delay;
            synchronized (LinuxAppsLink.this) {
                if (conn != this) return;
                remote = null;
                delay = nextDelay();
            }
            DexLog.warn(LinuxAppsService.AREA, "the apps process died, and its apps with it; starting it again in "
                    + (delay / 1000) + " s");
            main.postDelayed(() -> rebind(this), delay);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // A binding Android will not bring back, connected or not (Android
            // need not say disconnected first): make a new one, on the same
            // wait as a death. Android gives up on a service that keeps
            // crashing this way, and a new binding at once would start it
            // over, just to crash again.
            long delay;
            synchronized (LinuxAppsLink.this) {
                if (conn != this) return;
                remote = null;
                delay = nextDelay();
            }
            DexLog.warn(LinuxAppsService.AREA, "the apps process's binding died; binding again in "
                    + (delay / 1000) + " s");
            main.postDelayed(() -> rebind(this), delay);
        }
    }

    /**
     * The wait before the next rebind: a second, doubling each time the
     * service dies again within a minute of coming up, sixty at most. Holding
     * this.
     */
    private long nextDelay() {
        long up = android.os.SystemClock.elapsedRealtime() - connectedAt;
        rebindDelayMs = up > 60_000 ? 1_000 : Math.min(Math.max(1_000, rebindDelayMs * 2), 60_000);
        return rebindDelayMs;
    }

    /**
     * How long a new binding may wait for the service before the wait is cut
     * short ({@link #force}). Android's own restart of a service whose process
     * died comes after a second at first, and longer after each crash.
     */
    static final long FORCE_MS = 3_000;

    /**
     * A fresh binding in place of {@code dead}: unless the sessions let go
     * meanwhile, a newer binding replaced it, or Android brought the service
     * back on it. The new one is made before the old one goes, so a service
     * that is up, or that Android is bringing up just now (its own restart and
     * this one are both a second after the death), is never left with no
     * binding -- unbound, Android destroys it, and every app it runs with it.
     */
    private void rebind(Conn dead) {
        Conn fresh;
        synchronized (this) {
            if (conn != dead || remote != null) return;
            fresh = bindNew();
            if (fresh == null) {
                // The dead one stays held: Android may yet bring it back, and
                // the sessions' release still lets it go.
                DexLog.warn(LinuxAppsService.AREA, "could not bind the apps process again: apps stay in the desktop's");
                return;
            }
            conn = fresh;
            unbindQuietly(dead);
        }
        main.postDelayed(() -> force(fresh), FORCE_MS);
    }

    /**
     * The new binding has had {@link #FORCE_MS} and the service is still not
     * up. Android holds a service back while a restart of its own is due ("If
     * waiting for a restart, then do nothing", ActiveServices), and a binding
     * made meanwhile waits with it; with no binding left that wait is
     * cancelled, and the next binding brings the service up at once. (The
     * first version of this rebind did only that, unbind and bind, and the
     * service was back 1.1 s after a crash on the S25.) Once, not again.
     */
    private void force(Conn waiting) {
        synchronized (this) {
            if (conn != waiting || remote != null) return;
            // Up, and its connection on its way to this thread: letting go now
            // would destroy it (and close what it has started). The service
            // says so itself, its pid in "ready", written once it is watching.
            if (serviceUp()) return;
            unbindQuietly(waiting);
            conn = null;
            Conn fresh = bindNew();
            if (fresh != null) {
                conn = fresh;
            } else {
                DexLog.warn(LinuxAppsService.AREA, "could not bind the apps process again: apps stay in the desktop's");
            }
        }
    }

    /** Is the pid alive: kill(pid, 0). A field, for tests. */
    java.util.function.IntPredicate alive = pid -> {
        try {
            Os.kill(pid, 0);
            return true;
        } catch (ErrnoException | RuntimeException e) {
            return false;
        }
    };

    /**
     * Does a LinuxAppsService instance say it is up: "ready" in the requests
     * folder names a live process. A dead instance's (it does not clear it
     * when its process is killed) names one that is gone.
     */
    private boolean serviceUp() {
        File ready = new File(LinuxAppsService.requests(owner), "ready");
        String s = Linux.readFile(ready).trim();
        if (!s.matches("[0-9]{1,10}")) return false;
        int pid = Integer.parseInt(s);
        return pid > 1 && alive.test(pid);
    }

    /** bindService for a new connection; null when Android says no (then unbound again, as it asks). */
    private Conn bindNew() {
        Conn c = new Conn();
        boolean ok;
        try {
            ok = owner.bindService(new Intent(owner, LinuxAppsService.class), c,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_PERCEPTIBLE);
        } catch (RuntimeException e) {
            DexLog.warn(LinuxAppsService.AREA, "could not bind the apps process: " + e);
            return null;
        }
        if (!ok) {
            unbindQuietly(c);
            return null;
        }
        return c;
    }

    LinuxAppsLink(Service owner) {
        this.owner = owner;
    }

    /** Android 12 and later: the only ones with the limit this is for. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Put the guest's half in place: oadx-run and oadx-apps-menu in
     * /usr/local/bin, apt's hook, and linux-app.sh beside the other scripts.
     * On every session start, for the reason the session scripts are copied
     * then: a guest set up by an older app gets it with no setup pass. io thread.
     */
    void stage() {
        if (!supported()) return;
        File root = Linux.root(owner);
        File rootfs = new File(root, "rootfs");
        if (!new File(rootfs, "usr/bin").isDirectory()) return; // nothing provisioned
        try {
            copy("linux/linux-app.sh", Linux.appScript(owner), 0700);
            File bin = new File(rootfs, "usr/local/bin");
            if (!bin.isDirectory()) bin.mkdirs();
            copy("linux/guest/oadx-run", new File(bin, "oadx-run"), 0755);
            copy("linux/guest/oadx-apps-menu", new File(bin, "oadx-apps-menu"), 0755);
            File apt = new File(rootfs, "etc/apt/apt.conf.d");
            if (apt.isDirectory()) {
                write(new File(apt, "99oadx-apps"),
                        "// Written by LinuxOnDroid at every start. After apt installs or removes an\n"
                                + "// app, its menu entry is routed through oadx-run (see oadx-apps-menu).\n"
                                + "DPkg::Post-Invoke { \"if [ -x /usr/local/bin/oadx-apps-menu ]; then "
                                + "/usr/local/bin/oadx-apps-menu --menu-only >/dev/null 2>&1; fi; true\"; };\n",
                        0644);
            }
            // The desktop's session bus for apps in a proot of their own: before
            // the bus starts, which it does after this. Without it every app
            // that talks to the desktop over D-Bus failed from there: Log Out
            // said "The connection is closed" (S25).
            File busDir = new File(rootfs, "etc/dbus-1/session.d");
            if (!busDir.isDirectory()) busDir.mkdirs();
            copy("linux/guest/oadx-apps-dbus.conf", new File(busDir, "oadx-apps.conf"), 0644);
            passwdUser(new File(rootfs, "etc/passwd"), android.os.Process.myUid());
        } catch (Exception e) {
            // The last copies stay, or none: oadx-run then starts each app in
            // the desktop's process, as before.
            DexLog.warn(LinuxAppsService.AREA, "could not put the apps process's guest half in place: " + e);
        }
    }

    /** Keep ":linuxapps" up: a session is starting. Any thread. */
    void hold() {
        if (!supported()) return;
        synchronized (this) {
            if (conn != null) return;
            // A session of its own: whatever another one's crashes made the
            // rebind wait does not carry over.
            rebindDelayMs = 0;
            conn = bindNew();
        }
        if (!holding()) DexLog.warn(LinuxAppsService.AREA, "the apps process would not bind: apps stay in the desktop's");
    }

    /** Is a binding held. For tests. */
    synchronized boolean holding() {
        return conn != null;
    }

    /** Close the apps of one session, the ones it started until now. Any thread. */
    void stopDisplay(String display) {
        Message m = Message.obtain(null, LinuxAppsService.MSG_STOP_DISPLAY);
        Bundle b = new Bundle();
        b.putString(LinuxAppsService.KEY_DISPLAY, display);
        b.putLong(LinuxAppsService.KEY_BEFORE, android.os.SystemClock.elapsedRealtime());
        m.setData(b);
        synchronized (this) {
            if (conn == null) return; // nothing runs there
            if (remote == null) {
                pending.add(m);
            } else {
                deliver(m);
            }
        }
    }

    /**
     * Let ":linuxapps" go: no session is left. Android destroys the service,
     * which closes every app it started. Any thread.
     */
    synchronized void release() {
        Conn c = conn;
        if (c == null) return;
        conn = null;
        remote = null;
        pending.clear();
        unbindQuietly(c);
    }

    private void unbindQuietly(Conn c) {
        try {
            owner.unbindService(c);
        } catch (IllegalArgumentException ignored) {
            // not bound after all
        }
    }

    private void deliver(Message m) {
        try {
            remote.send(m);
        } catch (RemoteException e) {
            // Gone, and its apps with it.
        }
    }

    private void copy(String asset, File dest, int mode) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (InputStream in = owner.getAssets().open(asset);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        chmod(tmp, mode);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new java.io.IOException("cannot replace " + dest);
        }
    }

    private static void write(File dest, String text, int mode) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        chmod(tmp, mode);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new java.io.IOException("cannot replace " + dest);
        }
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (ErrnoException | RuntimeException e) {
            f.setReadable(true, false);
            if ((mode & 0111) != 0) f.setExecutable(true, false);
        }
    }
}
