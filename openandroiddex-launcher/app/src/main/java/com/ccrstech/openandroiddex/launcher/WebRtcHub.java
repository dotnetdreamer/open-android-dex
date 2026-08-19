package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Point;
import android.media.projection.MediaProjection;

import org.json.JSONObject;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every WebRTC viewer, and the single capture they share.
 *
 * One {@link DisplayCapturer} feeds one {@link VideoSource}, and every peer
 * gets a sender on the same {@link VideoTrack} — so a second browser costs an
 * encode of the same frames at a possibly different bitrate, not a second
 * screen capture. The capture itself is created with the first viewer and torn
 * down with the last, which is what keeps a session with nobody watching free.
 *
 * <p>Signalling is routed, not owned. A message can arrive on the phone's own
 * WebSocket (a viewer that could reach the phone) or from the rendezvous
 * (a viewer that could not), and the reply has to go back the way it came —
 * so each peer remembers its {@link Route} and the hub never has to know which
 * kind of session it is serving.
 */
final class WebRtcHub implements WebRtcPeer.Signal {

    /** Where a peer's signalling messages go back to. */
    interface Route {
        void send(String peerId, String json);
    }

    /** PeerConnectionFactory.initialize is once per PROCESS, not per instance. */
    private static boolean initialized;

    private final Context ctx;
    private final WebServer.Host host;
    private final MediaProjection projection;

    private EglBase egl;
    private PeerConnectionFactory factory;
    private SurfaceTextureHelper textureHelper;
    private DisplayCapturer capturer;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private boolean capturing;

    private final Map<String, WebRtcPeer> peers = new LinkedHashMap<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();

    WebRtcHub(Context ctx, WebServer.Host host, MediaProjection projection) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.projection = projection;
    }

    /**
     * Build the factory. Cheap enough to do on start and worth doing then: the
     * first thing it does is load ~12 MB of native code, and doing that while
     * a viewer waits for an offer is a visible pause.
     */
    boolean start() {
        try {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(ctx)
                                .createInitializationOptions());
                initialized = true;
            }
            egl = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                            egl.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                            egl.getEglBaseContext()))
                    .createPeerConnectionFactory();
            DexLog.step("web", "WebRTC ready");
            return true;
        } catch (Throwable t) {
            // A device without the native libraries for its ABI lands here.
            // The viewer is still perfectly usable over the WebSocket path, so
            // this is a downgrade rather than a failure.
            DexLog.warn("web", "WebRTC unavailable — falling back to the socket transport", t);
            return false;
        }
    }

    int peerCount() {
        synchronized (peers) {
            return peers.size();
        }
    }

    /** Authenticated viewers only — the number the notification should say. */
    int viewerCount() {
        int n = 0;
        synchronized (peers) {
            for (WebRtcPeer peer : peers.values()) {
                if (peer.authenticated()) n++;
            }
        }
        return n;
    }

    /**
     * One signalling message from a viewer.
     *
     * @param route how to answer this peer; remembered on the first message so
     *              later ICE candidates go the same way.
     */
    void onSignal(String json, Route route) {
        try {
            JSONObject msg = new JSONObject(json);
            String peerId = msg.optString("peer", "");
            if (peerId.isEmpty()) return;
            String type = msg.optString("t");
            switch (type) {
                case "join":
                    join(peerId, route);
                    break;
                case "answer":
                    withPeer(peerId, peer -> peer.onAnswer(msg.optString("sdp")));
                    break;
                case "ice":
                    withPeer(peerId, peer -> peer.onRemoteCandidate(
                            msg.optString("mid"), msg.optInt("index"),
                            msg.optString("candidate")));
                    break;
                case "leave":
                    gone(peerId);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            DexLog.warn("web", "bad signalling message", e);
        }
    }

    private interface PeerAction {
        void run(WebRtcPeer peer);
    }

    private void withPeer(String peerId, PeerAction action) {
        WebRtcPeer peer;
        synchronized (peers) {
            peer = peers.get(peerId);
        }
        if (peer != null) action.run(peer);
    }

    private void join(String peerId, Route route) {
        if (factory == null) return;
        synchronized (peers) {
            // A repeat join is a page that reloaded: throw the old connection
            // away rather than leaving a second one talking to nobody.
            WebRtcPeer existing = peers.remove(peerId);
            if (existing != null) existing.close();
            sweepLocked();
            if (peers.size() >= WebRtc.MAX_PEERS) {
                DexLog.warn("web", "refusing " + peerId + " — " + WebRtc.MAX_PEERS
                        + " WebRTC viewers already");
                // The peer id has to be IN the message, not just an argument:
                // the rendezvous routes host→viewer on that field alone, and a
                // refusal without it is a refusal nobody hears.
                route.send(peerId, "{\"t\":\"full\",\"peer\":\"" + peerId + "\"}");
                return;
            }
        }
        if (!ensureCapture()) {
            route.send(peerId,
                    "{\"t\":\"error\",\"why\":\"capture\",\"peer\":\"" + peerId + "\"}");
            return;
        }
        WebRtcPeer peer = new WebRtcPeer(ctx, host, this, peerId);
        synchronized (peers) {
            peers.put(peerId, peer);
            routes.put(peerId, route);
        }
        DexLog.step("web", "WebRTC viewer " + peerId + " joining");
        peer.start(factory, videoTrack);
        host.onViewersChanged();
    }

    /** Drop peers that connected and never proved they knew the code. */
    private void sweepLocked() {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, WebRtcPeer> entry : peers.entrySet()) {
            if (entry.getValue().expired()) stale.add(entry.getKey());
        }
        for (String id : stale) {
            WebRtcPeer peer = peers.remove(id);
            routes.remove(id);
            if (peer != null) peer.close();
            DexLog.step("web", "dropped " + id + " — never authenticated");
        }
    }

    @Override
    public void toPeer(String peerId, String json) {
        Route route;
        synchronized (peers) {
            route = routes.get(peerId);
        }
        if (route == null) return;
        try {
            // The peer id is added here rather than by every caller: it is
            // routing, not content.
            JSONObject withPeer = new JSONObject(json).put("peer", peerId);
            route.send(peerId, withPeer.toString());
        } catch (Exception e) {
            DexLog.warn("web", "could not route a signalling message", e);
        }
    }

    @Override
    public void gone(String peerId) {
        WebRtcPeer peer;
        synchronized (peers) {
            peer = peers.remove(peerId);
            routes.remove(peerId);
        }
        if (peer == null) return;
        peer.close();
        host.onViewersChanged();
        maybeStopCapture();
    }

    /** A setting moved — every live viewer takes the new bitrate and geometry. */
    void applySettings() {
        List<WebRtcPeer> current;
        synchronized (peers) {
            current = new ArrayList<>(peers.values());
        }
        for (WebRtcPeer peer : current) peer.applySettings();
        if (capturing && capturer != null) {
            Point size = plannedSize();
            capturer.changeCaptureFormat(size.x, size.y, Web.fps(ctx));
        }
    }

    /** The display rotated or resized. */
    void onDisplayChanged() {
        if (!capturing || capturer == null) return;
        Point size = plannedSize();
        capturer.changeCaptureFormat(size.x, size.y, Web.fps(ctx));
    }

    // ── capture ──

    private synchronized boolean ensureCapture() {
        if (capturing) return true;
        if (factory == null) return false;
        try {
            Point size = plannedSize();
            capturer = new DisplayCapturer(projection,
                    ctx.getResources().getDisplayMetrics().densityDpi);
            textureHelper = SurfaceTextureHelper.create("web-rtc-capture",
                    egl.getEglBaseContext());
            videoSource = factory.createVideoSource(true);
            capturer.initialize(textureHelper, ctx, videoSource.getCapturerObserver());
            capturer.startCapture(size.x, size.y, Web.fps(ctx));
            videoTrack = factory.createVideoTrack("dex-screen", videoSource);
            videoTrack.setEnabled(true);
            capturing = true;
            return true;
        } catch (Exception e) {
            DexLog.warn("web", "could not start the WebRTC capture", e);
            releaseCapture();
            return false;
        }
    }

    private synchronized void maybeStopCapture() {
        synchronized (peers) {
            if (!peers.isEmpty()) return;
        }
        if (!capturing) return;
        DexLog.step("web", "last WebRTC viewer left — stopping the capture");
        releaseCapture();
    }

    private void releaseCapture() {
        capturing = false;
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (Exception ignored) {
            }
            capturer.dispose();
            capturer = null;
        }
        if (videoTrack != null) {
            try {
                videoTrack.dispose();
            } catch (Exception ignored) {
            }
            videoTrack = null;
        }
        if (videoSource != null) {
            try {
                videoSource.dispose();
            } catch (Exception ignored) {
            }
            videoSource = null;
        }
        if (textureHelper != null) {
            textureHelper.dispose();
            textureHelper = null;
        }
    }

    private Point plannedSize() {
        return Web.captureSize(ctx);
    }

    /**
     * Disconnect everyone, but stay ready.
     *
     * What "new access code" means: the sessions opened under the old one end,
     * the capture stops with the last of them, and the next viewer to arrive
     * starts it again.
     */
    void dropAll() {
        java.util.List<String> ids;
        synchronized (peers) {
            ids = new ArrayList<>(peers.keySet());
        }
        for (String id : ids) gone(id);
    }

    void stop() {
        List<WebRtcPeer> current;
        synchronized (peers) {
            current = new ArrayList<>(peers.values());
            peers.clear();
            routes.clear();
        }
        for (WebRtcPeer peer : current) peer.close();
        releaseCapture();
        if (factory != null) {
            try {
                factory.dispose();
            } catch (Exception ignored) {
            }
            factory = null;
        }
        if (egl != null) {
            egl.release();
            egl = null;
        }
        DexLog.step("web", "WebRTC stopped");
    }
}
