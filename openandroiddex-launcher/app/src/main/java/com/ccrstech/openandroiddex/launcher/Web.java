package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The Web viewer's settings, secrets and addresses.
 *
 * The viewer is the answer to "the PC app is not here": a browser somewhere
 * else becomes a second seat at this phone, with nothing to install. Everything
 * on the phone side is inside this APK.
 *
 * <p><b>The transport is WebRTC and only WebRTC.</b> An earlier version also
 * streamed H.264 down a WebSocket, with a UPnP port forward and a tunnel field
 * to make the phone reachable. All of that was solving the problem WebRTC
 * already solves — reachability — and it is gone. What is left of HTTP is a
 * page to load and a place to exchange an offer, and even that is optional:
 * with a rendezvous configured the phone never has to be reachable at all.
 *
 * <p><b>What gets streamed is the phone's own display.</b> MediaProjection
 * mirrors the display the user consented to, and the public API has no way to
 * ask for another one — {@code VirtualDisplayConfig.Builder} carries no
 * {@code setDisplayIdToMirror} outside the system, and an app-created virtual
 * display is untrusted, so no other app's activity may be placed on one. With
 * a PC attached the DeX desktop lives on scrcpy's display and this viewer shows
 * the phone behind it; without one the launcher IS the phone's display and the
 * viewer shows the desktop shell. The second case is what this exists for.
 */
final class Web {

    private Web() {
    }

    /**
     * Prefix on every key below, and the reason none of them repaints the
     * shell: {@link DexPrefs#affectsShell} skips them, exactly as it skips the
     * PC's. Nothing on the desktop is drawn from a web setting.
     */
    static final String PREFIX = "web_";

    /**
     * The port the page is served on when the phone is reachable at all.
     *
     * Fixed rather than a setting: it is one page and one signalling socket,
     * not a service anybody port-forwards any more, and a number in a settings
     * screen that nobody has a reason to change is a question with no answer.
     */
    static final int PORT = 8787;

    /** Whether the page may click and type. Off leaves a read-only screen. */
    static final String KEY_CONTROL = PREFIX + "control";
    static final boolean DEF_CONTROL = true;
    /** Whether the page may browse, download and upload files. */
    static final String KEY_FILES = PREFIX + "files";
    static final boolean DEF_FILES = true;
    /** Longest edge of the capture: "720" | "1080" | "native". */
    static final String KEY_QUALITY = PREFIX + "quality";
    static final String DEF_QUALITY = "720";
    static final String KEY_FPS = PREFIX + "fps";
    static final int DEF_FPS = 30;
    /** Mbps ceiling handed to the peer connection's video encoding. */
    static final String KEY_BITRATE = PREFIX + "bitrate";
    static final int DEF_BITRATE = 6;
    /** Where the file panel is rooted. */
    static final String KEY_ROOT = PREFIX + "root";
    static final String DEF_ROOT = "/sdcard";
    /** Where uploads land, and what the desktop's drop card names. */
    static final String DEF_UPLOAD_DIR = "/sdcard/Download";

    /** The six-digit access code. Persisted so a restart does not move it. */
    static final String KEY_PIN = PREFIX + "pin";

    /** Broadcast (package-local) whenever the server's state changes. */
    static final String ACTION_STATE = "com.ccrstech.openandroiddex.launcher.web.STATE";

    static boolean control(Context ctx) {
        return DexPrefs.getBool(ctx, KEY_CONTROL, DEF_CONTROL);
    }

    static boolean files(Context ctx) {
        return DexPrefs.getBool(ctx, KEY_FILES, DEF_FILES);
    }

    static int fps(Context ctx) {
        return Math.max(5, Math.min(60, DexPrefs.getInt(ctx, KEY_FPS, DEF_FPS)));
    }

    static int bitrate(Context ctx) {
        return Math.max(1, Math.min(30, DexPrefs.getInt(ctx, KEY_BITRATE, DEF_BITRATE)));
    }

    static String root(Context ctx) {
        String r = DexPrefs.getString(ctx, KEY_ROOT, DEF_ROOT);
        return r.isEmpty() ? DEF_ROOT : r;
    }

    /**
     * The size the screen is captured at.
     *
     * A longest-edge cap rather than a resolution: the phone may be portrait or
     * landscape and the aspect is whatever the display has at the moment. Both
     * dimensions land on a multiple of 16 because an encoder given an odd size
     * will accept it and then smear one edge on some devices.
     */
    static Point captureSize(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point real = new Point();
        if (display != null) display.getRealSize(real);
        if (real.x <= 0 || real.y <= 0) real.set(1080, 1920);

        String quality = DexPrefs.getString(ctx, KEY_QUALITY, DEF_QUALITY);
        int maxEdge = "1080".equals(quality) ? 1920 : ("native".equals(quality) ? 0 : 1280);
        float factor = 1f;
        int longest = Math.max(real.x, real.y);
        if (maxEdge > 0 && longest > maxEdge) factor = maxEdge / (float) longest;
        return new Point(
                Math.max(160, (Math.round(real.x * factor) / 16) * 16),
                Math.max(160, (Math.round(real.y * factor) / 16) * 16));
    }

    // ── the access code ──

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The code the page asks for, made once and kept.
     *
     * SecureRandom rather than Math.random(): this is the only thing between a
     * stranger who has guessed a room and a live view of the phone.
     */
    static String pin(Context ctx) {
        String stored = DexPrefs.getString(ctx, KEY_PIN, "");
        if (stored.length() == 6) return stored;
        return newPin(ctx);
    }

    static String newPin(Context ctx) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append((char) ('0' + RANDOM.nextInt(10)));
        String pin = sb.toString();
        DexPrefs.put(ctx, KEY_PIN, pin);
        return pin;
    }

    /** A session token — 128 bits, hex. */
    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Constant-time string compare.
     *
     * The access code is six digits — small enough that a timing oracle on
     * String.equals, which returns at the first differing character, is worth
     * closing rather than arguing about.
     */
    static boolean secureEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length && i < y.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    // ── addresses ──

    /** One way in, as the user would type it. */
    static final class Address {
        /** "http://192.168.1.20:8787" */
        final String url;
        /** "Wi-Fi", "Hotspot", "Mobile data", … */
        final String label;
        /** Sort weight — lower is likelier to reach the computer in the room. */
        final int rank;

        Address(String url, String label, int rank) {
            this.url = url;
            this.label = label;
            this.rank = rank;
        }
    }

    /**
     * Every address this phone can be reached at on its own, best first.
     *
     * These only matter when the browser is on the same network. The address
     * that works from anywhere is the rendezvous link, which {@link WebRtc}
     * builds and which needs none of this.
     *
     * Enumerated from {@link NetworkInterface} rather than asked of
     * ConnectivityManager: a phone can be on Wi-Fi, sharing a hotspot and on
     * cellular at once. IPv6 is deliberately left out — it is the address
     * people mistype, and every one of these networks carries a v4 address too.
     */
    static List<Address> addresses(Context ctx) {
        List<Address> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                String name = nic.getName();
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    out.add(new Address("http://" + a.getHostAddress() + ":" + PORT,
                            interfaceLabel(ctx, name), interfaceRank(name)));
                }
            }
        } catch (Exception e) {
            DexLog.warn("web", "could not enumerate interfaces", e);
        }
        Collections.sort(out, (x, y) -> x.rank - y.rank);
        return out;
    }

    /**
     * Cellular last, and not because it is slow: a carrier address is behind
     * carrier-grade NAT, so it is the one entry here that cannot be opened from
     * anywhere at all. That is the case the rendezvous exists for.
     */
    private static int interfaceRank(String name) {
        if (name.startsWith("wlan")) return 0;
        if (name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis")) return 1;
        if (name.startsWith("ap") || name.startsWith("swlan")) return 2;
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) return 4;
        return 3;
    }

    private static String interfaceLabel(Context ctx, String name) {
        if (name.startsWith("wlan")) return ctx.getString(R.string.wb_if_wifi);
        if (name.startsWith("ap") || name.startsWith("swlan")) {
            return ctx.getString(R.string.wb_if_hotspot);
        }
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
            return ctx.getString(R.string.wb_if_mobile);
        }
        if (name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis")) {
            return ctx.getString(R.string.wb_if_wired);
        }
        return name;
    }

    /** Tell the desktop and the Web window that the server's state moved. */
    static void announce(Context ctx) {
        ctx.sendBroadcast(new Intent(ACTION_STATE).setPackage(ctx.getPackageName()));
    }
}
