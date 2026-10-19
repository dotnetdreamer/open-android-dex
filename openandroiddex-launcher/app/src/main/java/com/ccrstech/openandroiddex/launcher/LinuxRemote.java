package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Remote access: SSH into this phone's Linux from any computer, on any network.
 *
 * The pieces, and who owns each:
 *
 * <ul>
 * <li><b>The tunnel</b> is {@code libdexremote.so} (openandroiddex-remote/),
 *     an iroh endpoint that paired computers dial by key from wherever they
 *     are. It runs while remote access is on, as a child of
 *     {@link LinuxService} — see {@link RemoteHost}.</li>
 * <li><b>The SSH server</b> is OpenSSH inside the guest, on loopback, in a
 *     proot of its own (linux-ssh.sh) so it outlives the desktop session.</li>
 * <li><b>This class</b> is the vocabulary both of them, the window and the
 *     USB pairing provider share: where the files are, the list of paired
 *     computers, the sshd configuration, and reading the daemon's status.</li>
 * </ul>
 *
 * Everything lives in {@code files/linux-remote}, a SIBLING of the container
 * rather than a file in it, for the reason the uninstall marker and the app
 * selection are siblings: a reinstall deletes {@code files/linux} whole, and
 * the phone's identity, the host key every computer pinned and the list of
 * computers must not go with it. Re-pairing every computer because Ubuntu was
 * reinstalled would make the reinstall a far bigger decision than it is.
 *
 * Only the {@code ssh/} subdirectory is visible inside the guest (linux-ssh.sh
 * binds it onto /etc/oadx-ssh). The endpoint key — the phone's identity on the
 * network — stays outside it, where nothing running in Linux can read it.
 */
final class LinuxRemote {

    private LinuxRemote() {}

    /**
     * Where the guest's sshd listens, on 127.0.0.1, when the port is free.
     * Termux's sshd default is 8022, and a phone that runs both must not have
     * them fight; 7022 sits beside the launcher's own 7191-7199 instead.
     */
    static final int PREFERRED_SSH_PORT = 7022;

    /** How long an "Add a computer" code stays good. */
    static final int PAIR_TTL_SECONDS = 600;

    // ── locations ──

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-remote");
    }

    /** The part bound into the guest: sshd_config, host key, authorized_keys. */
    static File guestDir(Context ctx) {
        return new File(dir(ctx), "ssh");
    }

    /**
     * {@link #guestDir}, made sure of before anything is written into it.
     *
     * Code inside Linux can write there, and can even swap the directory
     * itself for a symlink — an rmdir through proot reaches the real one. A
     * write through that link would land wherever it points, so a real
     * directory is put back first.
     */
    private static File realGuestDir(Context ctx) {
        File g = guestDir(ctx);
        try {
            java.nio.file.Path p = g.toPath();
            java.nio.file.LinkOption here = java.nio.file.LinkOption.NOFOLLOW_LINKS;
            // A link, or a plain file in its place: either way not a directory
            // of ours, and every write into it would fail or go astray.
            if (java.nio.file.Files.exists(p, here) && !java.nio.file.Files.isDirectory(p, here)) {
                DexLog.warn("remote", "the guest's ssh directory was not a directory; putting one back");
                java.nio.file.Files.delete(p);
            }
        } catch (Exception e) {
            DexLog.warn("remote", "checking the guest's ssh directory", e);
        }
        g.mkdirs();
        return g;
    }

    private static File peersFile(Context ctx) {
        return new File(dir(ctx), "peers.json");
    }

    private static File authorizedKeys(Context ctx) {
        return new File(guestDir(ctx), "authorized_keys");
    }

    static File statusFile(Context ctx) {
        return new File(dir(ctx), "status.json");
    }

    /** What the window shows about an "Add a computer" in progress. */
    static File pairingFile(Context ctx) {
        return new File(dir(ctx), "pairing.json");
    }

    static File logFile(Context ctx) {
        return new File(dir(ctx), "remote.log");
    }

    /** Remote access is on while this exists. A file for LinuxService's sake. */
    private static File enabledMarker(Context ctx) {
        return new File(dir(ctx), "enabled");
    }

    static File binary(Context ctx) {
        return new File(Linux.nativeDir(ctx), "libdexremote.so");
    }

    /**
     * Can this install do remote access at all? The binary ships for the two
     * ABIs the container does, and remote access is SSH into that container,
     * so the two questions have one answer.
     */
    static boolean available(Context ctx) {
        return Linux.abiSupported() && binary(ctx).exists();
    }

    // ── on / off ──

    static boolean isEnabled(Context ctx) {
        return enabledMarker(ctx).exists();
    }

    static void setEnabled(Context ctx, boolean on) {
        File f = enabledMarker(ctx);
        if (!on) {
            f.delete();
            return;
        }
        try {
            dir(ctx).mkdirs();
            f.createNewFile();
        } catch (Exception e) {
            DexLog.warn("remote", "cannot record remote access as on", e);
        }
    }

    // ── the paired computers ──

    /** A computer allowed in. {@code peers.json} is a list of these. */
    static final class Peer {
        /** Its endpoint id, hex: what the tunnel checks before anything else. */
        String id;
        String name;
        /** windows, macos, linux or other — picks an icon, decides nothing. */
        String os;
        /** Its SSH key line, which goes into the guest's authorized_keys. */
        String ssh;
        /** When it was paired, epoch seconds. */
        long added;
        /** How: "code" or "usb". */
        String via;
    }

    /** Within one process FileLock throws rather than waiting; this serialises that half. */
    private static final Object LOCK = new Object();

    private interface Locked<T> {
        T run() throws Exception;
    }

    /**
     * Read-modify-write of the list, safe across processes.
     *
     * Three writers exist: LinuxService (a pairing by code, in :linux), the
     * window (Remove, in the main process) and RemoteProvider (a pairing over
     * USB, in the launcher's process). A lock file and an atomic rename keep
     * every one of them whole; the daemon only ever reads.
     */
    private static <T> T locked(Context ctx, Locked<T> body) throws Exception {
        synchronized (LOCK) {
            File dir = dir(ctx);
            dir.mkdirs();
            realGuestDir(ctx);
            try (RandomAccessFile raf = new RandomAccessFile(new File(dir, "peers.lock"), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock ignored = channel.lock()) {
                return body.run();
            }
        }
    }

    static List<Peer> peers(Context ctx) {
        List<Peer> out = new ArrayList<>();
        String text = Linux.readFile(peersFile(ctx));
        if (text.isEmpty()) return out;
        try {
            JSONArray list = new JSONObject(text).optJSONArray("peers");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Peer p = new Peer();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.os = o.optString("os", "other");
                p.ssh = o.optString("ssh");
                p.added = o.optLong("added");
                p.via = o.optString("via", "code");
                if (!p.id.isEmpty()) out.add(p);
            }
        } catch (Exception e) {
            DexLog.warn("remote", "peers.json is unreadable", e);
        }
        return out;
    }

    /** True when {@code id} is 64 lower-case hex digits: an endpoint id. */
    static boolean isEndpointId(String id) {
        return id != null && id.matches("[0-9a-f]{64}");
    }

    /** True for one `ssh-ed25519 <base64> [comment]` line. */
    static boolean isSshKey(String line) {
        return line != null && line.matches("ssh-ed25519 [A-Za-z0-9+/]{60,80}={0,2}( [^\\r\\n]*)?");
    }

    /**
     * Trust a computer: into peers.json, and its key into authorized_keys.
     * Replaces an earlier pairing of the same computer.
     */
    static boolean addPeer(Context ctx, Peer peer) {
        if (!isEndpointId(peer.id) || !isSshKey(peer.ssh)) {
            DexLog.warn("remote", "refusing a malformed computer entry");
            return false;
        }
        try {
            return locked(ctx, () -> {
                List<Peer> list = peers(ctx);
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).id.equals(peer.id)) list.remove(i);
                }
                list.add(peer);
                writePeers(ctx, list);
                DexLog.step("remote", "paired " + peer.name + " (" + shortId(peer.id) + ") via " + peer.via);
                return true;
            });
        } catch (Exception e) {
            DexLog.warn("remote", "could not save the pairing", e);
            return false;
        }
    }

    /**
     * Forget a computer. The daemon notices within two seconds and closes any
     * session it has open — Remove means now, not at the next reconnect.
     */
    static boolean removePeer(Context ctx, String id) {
        try {
            return locked(ctx, () -> {
                List<Peer> list = peers(ctx);
                boolean removed = false;
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).id.equals(id)) {
                        list.remove(i);
                        removed = true;
                    }
                }
                if (removed) {
                    writePeers(ctx, list);
                    DexLog.step("remote", "removed computer " + shortId(id));
                }
                return removed;
            });
        } catch (Exception e) {
            DexLog.warn("remote", "could not remove the computer", e);
            return false;
        }
    }

    private static void writePeers(Context ctx, List<Peer> list) throws Exception {
        JSONArray arr = new JSONArray();
        StringBuilder keys = new StringBuilder(
                "# Written by Open Android DeX from its paired computers. Changes are overwritten.\n");
        for (Peer p : list) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("os", p.os);
            o.put("ssh", p.ssh);
            o.put("added", p.added);
            o.put("via", p.via);
            arr.put(o);
            keys.append(p.ssh.trim()).append('\n');
        }
        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("peers", arr);
        // authorized_keys first. A computer in peers.json whose key is not yet
        // in authorized_keys gets through the tunnel and is then refused by
        // sshd; the other order would let a removed computer's key outlive it.
        realGuestDir(ctx);
        writeAtomic(ctx, authorizedKeys(ctx), keys.toString());
        writeAtomic(ctx, peersFile(ctx), root.toString());
    }

    /** Everything that goes with remote access, gone — for "Forget all" and uninstall. */
    static void forgetAll(Context ctx) {
        try {
            locked(ctx, () -> {
                writePeers(ctx, new ArrayList<>());
                return null;
            });
        } catch (Exception e) {
            DexLog.warn("remote", "could not clear the computers", e);
        }
    }

    // ── the guest's sshd ──

    /**
     * The sshd configuration, written into the guest's view of {@link #guestDir}
     * before every start, so an APK update changes it without a reinstall.
     *
     * Every line is a decision:
     * <ul>
     * <li>Loopback only, and keys only. The one way in from outside is the
     *     tunnel, which has already checked the computer's identity; sshd then
     *     checks a second key. Other apps on this phone can reach loopback, so
     *     this is not a formality.</li>
     * <li>UsePAM no: Ubuntu's sshd PAM stack ends in pam_loginuid, which
     *     writes /proc/self/loginuid, which nothing in a proot can — and it is
     *     "required", so every login would be closed the moment it opened.</li>
     * <li>StrictModes no: proot reports file owners the way it fakes them, and
     *     one uid owns this entire container anyway.</li>
     * <li>SFTP is the packaged sftp-server with {@link #SFTP_SHIM} preloaded,
     *     not internal-sftp: scp has spoken SFTP since OpenSSH 9, and so do
     *     file managers and VS Code, and OpenSSH's SFTP code refuses to run
     *     under proot without the shim — see openandroiddex-linux/sftp-shim.
     *     Preloaded into that one program, never into sshd or a login.</li>
     * <li>TCP forwarding on: VS Code's Remote-SSH reaches its server through it.</li>
     * <li>The port is on sshd's command line (linux-ssh.sh), not here.</li>
     * </ul>
     */
    static String sshdConfig() {
        return "# Written by Open Android DeX every time remote access starts.\n"
                + "# This file belongs to the app; edits here are overwritten.\n"
                + "ListenAddress 127.0.0.1\n"
                + "HostKey /etc/oadx-ssh/ssh_host_ed25519_key\n"
                + "AuthorizedKeysFile /etc/oadx-ssh/authorized_keys\n"
                + "PubkeyAuthentication yes\n"
                + "PasswordAuthentication no\n"
                + "KbdInteractiveAuthentication no\n"
                + "PermitRootLogin prohibit-password\n"
                + "AllowUsers root\n"
                + "UsePAM no\n"
                + "StrictModes no\n"
                + "UseDNS no\n"
                + "PrintMotd no\n"
                + "PrintLastLog no\n"
                + "Subsystem sftp /usr/bin/env LD_PRELOAD=/etc/oadx-ssh/" + SFTP_SHIM
                + " /usr/lib/openssh/sftp-server\n"
                + "AllowTcpForwarding yes\n"
                + "AllowAgentForwarding yes\n"
                + "X11Forwarding no\n"
                + "PermitTunnel no\n"
                + "ClientAliveInterval 30\n"
                + "ClientAliveCountMax 4\n"
                + "LoginGraceTime 30\n"
                + "MaxStartups 10:30:60\n"
                + "AcceptEnv LANG LC_*\n"
                // Sound from anything started over SSH goes where the desktop's
                // goes: the PulseAudio daemon linux-rt.sh runs, when it runs.
                + "SetEnv LANG=C.UTF-8 PULSE_SERVER=tcp:127.0.0.1:4713\n";
    }

    /**
     * What makes OpenSSH's SFTP server run under proot: one prctl answered the
     * way proot meant to answer it (openandroiddex-linux/sftp-shim/nodump.c),
     * built per ABI into the assets and copied in beside sshd_config.
     */
    static final String SFTP_SHIM = "liboadx-nodump.so";

    static void writeSshdConfig(Context ctx) {
        try {
            File guest = realGuestDir(ctx);
            writeAtomic(ctx, new File(guest, "sshd_config"), sshdConfig());
            File keys = authorizedKeys(ctx);
            if (!keys.exists()) writeAtomic(ctx, keys, "");
        } catch (Exception e) {
            DexLog.warn("remote", "cannot write sshd_config", e);
        }
        // Copied on every start, like the config: an APK update then ships a
        // new one, and code in the guest that replaced it gets it back.
        try (java.io.InputStream in = ctx.getAssets().open("linux/sftp/" + Linux.primaryAbi() + "/" + SFTP_SHIM)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            writeAtomic(ctx, new File(realGuestDir(ctx), SFTP_SHIM), buf.toByteArray());
        } catch (Exception e) {
            // Logins still work without it; only SFTP — scp, sftp, file
            // managers — fails at its first request.
            DexLog.warn("remote", "cannot stage the SFTP shim for " + Linux.primaryAbi(), e);
        }
    }

    /**
     * Is OpenSSH in the guest, and finished? linux-setup.sh's setup_ssh puts
     * it there, and stamps it only once dpkg calls the package installed: an
     * install cut short after unpacking leaves the binary without its
     * postinst — no privilege-separation user — and an sshd that dies at every
     * start, which the binary alone would call ready.
     */
    static boolean sshdInstalled(Context ctx) {
        File root = Linux.root(ctx);
        return new File(root, "rootfs/usr/sbin/sshd").exists() && new File(root, ".stamp-ssh").exists();
    }

    /**
     * Has setup_ssh stopped trying, at this feature level? It settles after
     * three failed installs the way VS Code's phase does, so a guest whose
     * openssh-server will not install is not rebuilt on every launch for as
     * long as remote access is on. The stamp holds the level it settled at,
     * and a new level is a new attempt — see linux-setup.sh.
     */
    static boolean sshdGaveUp(Context ctx) {
        String at = Linux.readFile(new File(Linux.root(ctx), ".stamp-ssh-gaveup")).trim();
        try {
            return !at.isEmpty() && Integer.parseInt(at) >= Linux.FEATURE_LEVEL;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Fresh attempts, for someone who just switched remote access on: the
     * give-up was about not paying for the same failure on every launch, not
     * about refusing a person who asks again.
     */
    static void clearSshdGaveUp(Context ctx) {
        new File(Linux.root(ctx), ".stamp-ssh-gaveup").delete();
        new File(Linux.root(ctx), ".ssh-attempts").delete();
    }

    // ── the phone's identity ──

    /** This phone as a computer sees it. */
    static final class Identity {
        /** Endpoint id, hex. */
        String id;
        /** The sshd host key, one `ssh-ed25519 …` line. */
        String hostKey;
    }

    private static Identity cachedIdentity;

    /**
     * The phone's endpoint id and host key, made on first use.
     *
     * Through the binary's {@code init} rather than by reading files: making an
     * Ed25519 key needs Ed25519, which Android only grew in API 33, and the
     * binary is the one place that already knows both file formats. Idempotent
     * and safe beside a running daemon — it only creates what is missing.
     * Blocks for a few milliseconds; never call it on the main thread.
     */
    static synchronized Identity identity(Context ctx) {
        if (cachedIdentity != null) return cachedIdentity;
        if (!available(ctx)) return null;
        try {
            Process p = new ProcessBuilder(binary(ctx).getAbsolutePath(),
                    "init", "--dir", dir(ctx).getAbsolutePath())
                    .redirectErrorStream(false)
                    .start();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String[] words = out.split("\\s+");
            if (p.exitValue() != 0 || words.length < 2 || !isEndpointId(words[0])) {
                DexLog.warn("remote", "dexremote init said: " + out);
                return null;
            }
            Identity id = new Identity();
            id.id = words[0];
            id.hostKey = unb64(words[1]);
            cachedIdentity = id;
            return id;
        } catch (Exception e) {
            DexLog.warn("remote", "cannot read the phone's identity", e);
            return null;
        }
    }

    // ── what the daemon says ──

    /** One open SSH connection, from status.json. */
    static final class Session {
        String peer;
        String name;
        long since;
        boolean direct;
        long rttMs;
    }

    /** A snapshot of status.json. */
    static final class Status {
        /** The daemon has written a status in the last half minute. */
        boolean live;
        boolean online;
        String relay = "";
        String ssh = "down";
        List<Session> sessions = new ArrayList<>();
        long pairingUntil;
    }

    static Status readStatus(Context ctx) {
        Status st = new Status();
        File f = statusFile(ctx);
        String text = Linux.readFile(f);
        if (text.isEmpty()) return st;
        try {
            JSONObject o = new JSONObject(text);
            long updated = o.optLong("updated");
            st.live = Math.abs(System.currentTimeMillis() / 1000 - updated) < 30;
            st.online = o.optBoolean("online");
            st.relay = o.optString("relay", "");
            st.ssh = o.optString("ssh", "down");
            st.pairingUntil = o.optLong("pairing_until");
            JSONArray list = o.optJSONArray("sessions");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject s = list.getJSONObject(i);
                    Session session = new Session();
                    session.peer = s.optString("peer");
                    session.name = s.optString("name");
                    session.since = s.optLong("since");
                    session.direct = s.optBoolean("direct");
                    session.rttMs = s.optLong("rtt_ms");
                    st.sessions.add(session);
                }
            }
        } catch (Exception e) {
            // A torn read: the daemon replaces the file whole, so the next
            // poll sees a complete one.
        }
        return st;
    }

    /** An "Add a computer" in progress, as LinuxService last described it. */
    static final class Pairing {
        /** starting, open, paired, failed — or "" for none. */
        String state = "";
        String code = "";
        long until;
        /** The computer, once paired. */
        String name = "";
        /** Why it failed: bad-code, expired, rejected, error, invalid. */
        String reason = "";
    }

    static Pairing readPairing(Context ctx) {
        Pairing p = new Pairing();
        String text = Linux.readFile(pairingFile(ctx));
        if (text.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(text);
            p.state = o.optString("state");
            p.code = o.optString("code");
            p.until = o.optLong("until");
            p.name = o.optString("name");
            p.reason = o.optString("reason");
        } catch (Exception ignored) {
        }
        return p;
    }

    static void writePairing(Context ctx, String state, String code, long until, String name, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("code", code);
            o.put("until", until);
            o.put("name", name);
            o.put("reason", reason);
            dir(ctx).mkdirs();
            writeAtomic(ctx, pairingFile(ctx), o.toString());
        } catch (Exception e) {
            DexLog.warn("remote", "cannot write pairing.json", e);
        }
    }

    // ── pairing codes ──

    /**
     * Crockford's base32 — no I, L, O or U, so nothing on screen can be read as
     * something else. The computer folds those back in if typed anyway (see
     * openandroiddex-remote/src/pairing.rs, which this must match).
     */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Eight characters, 40 bits, from the platform's CSPRNG. */
    static String newCode() {
        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(8);
        for (byte b : raw) sb.append(ALPHABET.charAt(b & 31));
        return sb.toString();
    }

    /** "K7QM29XV" → "K7QM-29XV": two groups to read out and check. */
    static String displayCode(String code) {
        return code != null && code.length() == 8 ? code.substring(0, 4) + "-" + code.substring(4) : code;
    }

    // ── names ──

    /** The phone's own name: what Settings → About calls it, else the model. */
    static String phoneName(Context ctx) {
        String name = null;
        try {
            name = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) name = Build.MODEL;
        return name == null ? "Phone" : name.trim();
    }

    /**
     * The name a computer will give this phone in its SSH config — the same
     * rule as openandroiddex-remote's computer.rs, so the window can say what
     * to type. A second phone of the same model gets "-2" there, which this
     * cannot know; it is a hint, and the computer prints the real one.
     */
    static String sshAlias(Context ctx) {
        StringBuilder out = new StringBuilder();
        for (char c : phoneName(ctx).toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        String s = out.toString().replaceAll("^-+|-+$", "");
        if (s.length() > 32) s = s.substring(0, 32).replaceAll("-+$", "");
        return s.isEmpty() ? "phone" : s;
    }

    /** "e375f017…" → "e375 f017": enough of an id to tell two apart. */
    static String shortId(String hex) {
        if (hex == null || hex.length() < 8) return hex == null ? "" : hex;
        return hex.substring(0, 4) + " " + hex.substring(4, 8);
    }

    // ── small helpers ──

    static String unb64(String s) {
        try {
            return new String(Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Replace a file whole, so a reader in another process never sees half.
     *
     * The temporary file is made in {@link #dir}, never beside {@code f}. Beside
     * it would be in the guest's directory for authorized_keys and
     * sshd_config, where code inside Linux could plant a symlink under the
     * temporary name and have this write follow it into the endpoint key or
     * the list of computers. The rename then replaces whatever is at {@code f},
     * a link included, without following it. Named per process and thread, as
     * the window and the service both write pairing.json.
     */
    static void writeAtomic(Context ctx, File f, String content) throws Exception {
        writeAtomic(ctx, f, content.getBytes(StandardCharsets.UTF_8));
    }

    static void writeAtomic(Context ctx, File f, byte[] content) throws Exception {
        File tmp = new File(dir(ctx), "." + f.getName() + "." + android.os.Process.myPid()
                + "." + Thread.currentThread().getId() + ".tmp");
        tmp.delete();
        // CREATE_NEW is O_EXCL: it will not open anything already there, a
        // link least of all.
        try (FileChannel out = FileChannel.open(tmp.toPath(),
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(content);
            while (buf.hasRemaining()) out.write(buf);
            out.force(true);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new java.io.IOException("cannot replace " + f);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
