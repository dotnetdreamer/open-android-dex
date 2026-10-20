package com.ccrstech.openandroiddex.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Pairing over the USB cable: the desktop app trusts this phone, and is
 * trusted by it, in one {@code adb shell content call} — no code to type,
 * because adb has already proved which computer is on the other end of the
 * cable, and a person plugged it in.
 *
 * <pre>
 * content call --uri content://com.ccrstech.openandroiddex.launcher.remote \
 *              --method pair --arg &lt;base64url JSON&gt;
 * </pre>
 *
 * Methods, each answered with one {@code result} extra holding base64url JSON
 * (the {@code content} tool prints a Bundle with toString, and JSON in there
 * would be mangled by the commas and braces it shares with the Bundle's own):
 *
 * <ul>
 * <li>{@code status} — whether this install can do remote access at all, is
 *     doing it, its identity, and which computers it trusts.</li>
 * <li>{@code pair} — trust {@code {id, name, os, ssh}}, optionally turning
 *     remote access on, and answer with this phone's id and host key.</li>
 * <li>{@code forget} — stop trusting {@code {id}}.</li>
 * <li>{@code stream} — the desktop as video over this same cable
 *     ({@link LinuxStream}): wake the service that serves it, and name the
 *     socket to dial.</li>
 * </ul>
 *
 * <b>Only the shell may call this.</b> Twice over: the manifest gives the
 * provider {@code android.permission.DUMP}, which the shell holds and no
 * third-party app can, and which the system checks when the provider is
 * acquired; and {@link #call} checks the uid itself, because Android applies
 * no permission check to {@code call()} — a provider that relied on the
 * manifest alone for it would be one refactor away from letting any app on
 * the phone pair itself and get a root shell in Linux.
 *
 * Declared only by the desktop launcher's manifest. LinuxOnDroid has no PC
 * flow to serve, and the authority is a fixed string that two installed apps
 * cannot both declare.
 */
public class RemoteProvider extends ContentProvider {

    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    /** No legitimate request is anywhere near this. */
    private static final int MAX_ARG = 8192;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) {
            throw new SecurityException("remote pairing is for adb only");
        }
        Context ctx = getContext();
        JSONObject out = new JSONObject();
        // Binder calls run with the caller's identity; everything below reads
        // and writes our own files and starts our own service, as us.
        long token = Binder.clearCallingIdentity();
        try {
            JSONObject in = parse(arg);
            switch (method == null ? "" : method) {
                case "status":
                    describe(ctx, out);
                    break;
                case "pair":
                    pair(ctx, in, out);
                    break;
                case "forget":
                    out.put("ok", LinuxRemote.removePeer(ctx, in.optString("id")));
                    break;
                case "stream":
                    stream(ctx, out);
                    break;
                default:
                    out.put("ok", false);
                    out.put("error", "unknown-method");
            }
        } catch (Exception e) {
            DexLog.warn("remote", "usb pairing call failed", e);
            try {
                out.put("ok", false);
                out.put("error", "failed");
            } catch (Exception ignored) {
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        Bundle result = new Bundle();
        result.putString("result", Base64.encodeToString(out.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
        return result;
    }

    private static JSONObject parse(String arg) throws Exception {
        if (arg == null || arg.isEmpty()) return new JSONObject();
        if (arg.length() > MAX_ARG) throw new IllegalArgumentException("argument too large");
        byte[] raw = Base64.decode(arg, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    /** What a computer needs to know before and after pairing. */
    private static void describe(Context ctx, JSONObject out) throws Exception {
        out.put("ok", true);
        out.put("available", LinuxRemote.available(ctx));
        out.put("enabled", LinuxRemote.isEnabled(ctx));
        out.put("name", LinuxRemote.phoneName(ctx));
        out.put("user", "root");
        out.put("linux", linuxState(ctx));
        LinuxRemote.Identity id = LinuxRemote.available(ctx) ? LinuxRemote.identity(ctx) : null;
        if (id != null) {
            out.put("id", id.id);
            out.put("hostKey", id.hostKey);
        }
        JSONArray peers = new JSONArray();
        for (LinuxRemote.Peer p : LinuxRemote.peers(ctx)) peers.put(p.id);
        out.put("peers", peers);
    }

    private static void pair(Context ctx, JSONObject in, JSONObject out) throws Exception {
        if (!LinuxRemote.available(ctx)) {
            out.put("ok", false);
            out.put("error", "unavailable");
            return;
        }
        LinuxRemote.Peer peer = new LinuxRemote.Peer();
        peer.id = in.optString("id");
        String name = in.optString("name").replaceAll("\\p{Cntrl}", " ").trim();
        peer.name = name.length() > 64 ? name.substring(0, 64) : name;
        String os = in.optString("os");
        peer.os = "windows".equals(os) || "macos".equals(os) || "linux".equals(os) ? os : "other";
        peer.ssh = in.optString("ssh").trim();
        peer.added = System.currentTimeMillis() / 1000;
        peer.via = "usb";
        if (!LinuxRemote.addPeer(ctx, peer)) {
            out.put("ok", false);
            out.put("error", "invalid");
            return;
        }
        // The identity before the service. The answer below carries it, and
        // on a phone that has never had one this call's `init` and the
        // daemon's `serve` would otherwise both be making it at once; the
        // binary takes turns on a lock, but going first is simpler still.
        LinuxRemote.identity(ctx);
        // Over a cable the person at the computer is plainly also at the phone,
        // so "set it up" can mean switching it on as well — when they asked.
        // Switched on afresh even when it already was: that is what clears a
        // give-up on openssh-server and brings back a service that is not
        // running, and "set it up" means both.
        Boolean started = null;
        if (in.optBoolean("enable")) {
            started = LinuxService.remoteOn(ctx);
            // Android refuses a background app a foreground service unless an
            // exemption applies, and nothing here is one. It is on, and comes
            // up at the next chance; until then a tap on this brings it.
            if (!started) RemoteResumeReceiver.offerResume(ctx);
        }
        describe(ctx, out);
        if (started != null) out.put("started", started.booleanValue());
    }

    /**
     * A computer on the cable wants the desktop as video. The socket lives in
     * {@link LinuxService}'s process, so that is woken to listen; it stays for
     * as long as a session is open. {@code started} false means Android
     * refused to start it from the background — the same wall a pairing hits,
     * with the same way round: open Linux on the phone once.
     */
    private static void stream(Context ctx, JSONObject out) throws Exception {
        boolean ok = LinuxRemote.available(ctx) && Linux.isInstalled(ctx);
        out.put("ok", ok);
        out.put("socket", LinuxStream.socketName(ctx));
        out.put("linux", linuxState(ctx));
        out.put("name", LinuxRemote.phoneName(ctx));
        if (ok) out.put("started", LinuxService.wakeForStream(ctx));
    }

    /**
     * What a computer should tell its user: ready, installing, missing,
     * failed (the setup stopped; a retry in the Linux window helps), or
     * ssh-failed (openssh-server would not install; switching remote access
     * off and on retries). Mirrors RemoteHost's own reading of the guest.
     */
    private static String linuxState(Context ctx) {
        if (!Linux.isInstalled(ctx)) return "missing";
        Linux.Status st = Linux.readStatus(ctx);
        if ("none".equals(st.phase)) return "missing";
        boolean sshd = LinuxRemote.sshdInstalled(ctx);
        if (sshd && ("ready".equals(st.phase) || "error".equals(st.phase))) return "ready";
        if ("error".equals(st.phase)) return "failed";
        if ("ready".equals(st.phase) && LinuxRemote.sshdGaveUp(ctx)) return "ssh-failed";
        return "installing";
    }

    // ── nothing here is a table ──

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
