package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The WebRTC half of the Web viewer: which ICE servers to use, where the
 * rendezvous is, and which room this phone answers in.
 *
 * <p><b>Why WebRTC is here at all.</b> The WebSocket transport in
 * {@link WebStream} only works if the browser can already reach this phone —
 * on the same network, through a tunnel, or through a port the router
 * forwarded. On mobile data behind carrier-grade NAT none of those exist, and
 * no amount of port mapping creates one. WebRTC is the only thing that does:
 * ICE finds a path, and where it cannot, a TURN server relays. That is worth a
 * ~12 MB native dependency in an APK that otherwise has none.
 *
 * <p><b>Two things have to be true for "from anywhere" to work</b>, and they
 * are different things:
 * <ul>
 * <li><b>A TURN server</b>, which carries the media when neither peer can be
 *     reached directly. STUN alone is not enough behind symmetric NAT.
 * <li><b>A rendezvous</b> — somewhere both ends can post an offer and an
 *     answer. TURN cannot do this: it relays between peers that already know
 *     each other's relayed addresses, which is exactly what has not been
 *     established yet. When the phone is reachable, its own server is the
 *     rendezvous and nothing else is needed; when it is not, the phone dials
 *     OUT to {@link #signalUrl} and waits there. {@code openandroiddex-signal}
 *     in this repository is a ~200-line server for that, meant to sit beside
 *     an existing coturn.
 * </ul>
 *
 * <p>The room id is a credential in the same sense the access code is: knowing
 * it lets someone start a connection attempt. It is not sufficient on its own —
 * the access code is still checked over the data channel before a single frame
 * is sent — but it is random rather than guessable for that reason.
 */
final class WebRtc {

    private WebRtc() {
    }

    /** Off falls the whole viewer back to the WebSocket transport. */
    static final String KEY_ENABLED = Web.PREFIX + "rtc";
    static final boolean DEF_ENABLED = true;
    /** Comma-separated STUN urls. */
    static final String KEY_STUN = Web.PREFIX + "stun";
    static final String DEF_STUN = "stun:stun.l.google.com:19302";
    /** One TURN url, e.g. {@code turn:turn.example.com:3478} or {@code turns:…:5349}. */
    static final String KEY_TURN = Web.PREFIX + "turn";
    static final String KEY_TURN_USER = Web.PREFIX + "turn_user";
    static final String KEY_TURN_PASS = Web.PREFIX + "turn_pass";
    /**
     * Force every candidate through TURN.
     *
     * Costs bandwidth on the relay and hides both endpoints' addresses from
     * each other. Off by default because on a home network the direct path is
     * both faster and free; worth turning on when the viewer must not learn
     * the phone's address, or to prove the relay works.
     */
    static final String KEY_RELAY_ONLY = Web.PREFIX + "turn_relay_only";
    /** WebSocket url of the rendezvous, or empty for "only when reachable". */
    static final String KEY_SIGNAL = Web.PREFIX + "signal";
    /** This phone's room at that rendezvous. */
    static final String KEY_ROOM = Web.PREFIX + "room";

    static boolean enabled(Context ctx) {
        return DexPrefs.getBool(ctx, KEY_ENABLED, DEF_ENABLED);
    }

    static String signalUrl(Context ctx) {
        return DexPrefs.getString(ctx, KEY_SIGNAL, "").trim();
    }

    static boolean hasRendezvous(Context ctx) {
        String url = signalUrl(ctx);
        return url.startsWith("ws://") || url.startsWith("wss://");
    }

    /** The room, made once and kept — a restart must not move the address. */
    static String room(Context ctx) {
        String stored = DexPrefs.getString(ctx, KEY_ROOM, "");
        if (stored.length() >= 8) return stored;
        return newRoom(ctx);
    }

    static String newRoom(Context ctx) {
        String room = Web.newToken().substring(0, 16);
        DexPrefs.put(ctx, KEY_ROOM, room);
        return room;
    }

    /**
     * The address to hand someone for a rendezvous session.
     *
     * The room goes in the FRAGMENT, not the query: a fragment is never sent
     * to the server in a request line and never lands in its access log. The
     * rendezvous only learns the room when the page itself hands it over on
     * the socket, which is unavoidable, rather than on every asset fetch.
     */
    static String rendezvousLink(Context ctx) {
        String signal = signalUrl(ctx);
        if (signal.isEmpty()) return "";
        String page = signal.replaceFirst("^ws", "http");
        int cut = page.lastIndexOf('/');
        if (cut > "https://".length()) page = page.substring(0, cut);
        return page + "/#" + room(ctx);
    }

    /**
     * The ICE servers, as WebRTC wants them.
     *
     * TURN entries carry credentials, which is why nothing here is ever logged
     * and why {@link #describe} exists for the places that want to say what is
     * configured without saying what the password is.
     */
    static List<PeerConnection.IceServer> iceServers(Context ctx) {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        for (String url : DexPrefs.getString(ctx, KEY_STUN, DEF_STUN).split(",")) {
            String trimmed = url.trim();
            if (trimmed.isEmpty()) continue;
            servers.add(PeerConnection.IceServer.builder(trimmed).createIceServer());
        }
        String turn = DexPrefs.getString(ctx, KEY_TURN, "").trim();
        if (!turn.isEmpty()) {
            servers.add(PeerConnection.IceServer.builder(turn)
                    .setUsername(DexPrefs.getString(ctx, KEY_TURN_USER, ""))
                    .setPassword(DexPrefs.getString(ctx, KEY_TURN_PASS, ""))
                    .createIceServer());
        }
        return servers;
    }

    /**
     * The same list for the browser, as JSON.
     *
     * The page needs its own copy — ICE is symmetric, and a browser with no
     * TURN server cannot answer a phone that is only reachable through one.
     * Sent over the authenticated channel and nowhere else: these are
     * credentials, even when they are the short-lived kind coturn's REST
     * scheme hands out.
     */
    static String iceServersJson(Context ctx) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String url : DexPrefs.getString(ctx, KEY_STUN, DEF_STUN).split(",")) {
            String trimmed = url.trim();
            if (trimmed.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"urls\":\"").append(escape(trimmed)).append("\"}");
        }
        String turn = DexPrefs.getString(ctx, KEY_TURN, "").trim();
        if (!turn.isEmpty()) {
            if (!first) sb.append(',');
            sb.append("{\"urls\":\"").append(escape(turn))
                    .append("\",\"username\":\"")
                    .append(escape(DexPrefs.getString(ctx, KEY_TURN_USER, "")))
                    .append("\",\"credential\":\"")
                    .append(escape(DexPrefs.getString(ctx, KEY_TURN_PASS, "")))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    static PeerConnection.RTCConfiguration configuration(Context ctx) {
        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(iceServers(ctx));
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // Keep gathering after the first working pair: a phone that moves from
        // Wi-Fi to cellular mid-session gets new candidates instead of a dead
        // connection.
        config.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        config.keyType = PeerConnection.KeyType.ECDSA;
        config.enableCpuOveruseDetection = true;
        if (DexPrefs.getBool(ctx, KEY_RELAY_ONLY, false)) {
            config.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
        }
        return config;
    }

    /** What the window says is configured, with no secret in it. */
    static String describe(Context ctx) {
        String turn = DexPrefs.getString(ctx, KEY_TURN, "").trim();
        if (turn.isEmpty()) return ctx.getString(R.string.wb_rtc_no_turn);
        int at = turn.indexOf(':');
        String host = at >= 0 ? turn.substring(at + 1) : turn;
        return ctx.getString(R.string.wb_rtc_turn_at, host);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** True once there is enough configured for a relay to be possible. */
    static boolean turnConfigured(Context ctx) {
        return !DexPrefs.getString(ctx, KEY_TURN, "").trim().isEmpty();
    }

    /** Sanity limit on how many browsers may watch at once through WebRTC. */
    static final int MAX_PEERS = 4;

    static List<PeerConnection.IceServer> emptyServers() {
        return Collections.emptyList();
    }
}
