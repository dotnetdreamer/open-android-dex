package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * One browser, connected over WebRTC: a peer connection carrying the screen on
 * a video track and everything else on two data channels.
 *
 * <p><b>The phone offers.</b> It has the media, so it creates the connection,
 * the channels and the offer; the browser only ever answers. That removes the
 * whole renegotiation dance from the normal path — there is exactly one
 * offer/answer per viewer, and the browser needs no state machine for a
 * second one.
 *
 * <p><b>Nothing is sent before the access code is checked.</b> The video sender
 * exists from the start, but its encoding is created inactive, so the track is
 * attached and transmitting nothing. Authentication happens on the control
 * channel; only then does the encoding go active. The alternative — adding the
 * track after auth — means a second offer, and a leak if it is ever got wrong.
 * A disabled encoding cannot leak a frame by mistake.
 *
 * <p>Two channels rather than one: control messages are small, ordered and
 * latency-critical, and a file transfer would otherwise sit in front of a
 * pointer move in the same SCTP stream.
 */
final class WebRtcPeer implements PeerConnection.Observer {

    /** Wrong codes before this peer is dropped. */
    private static final int MAX_AUTH_TRIES = 3;
    /** A peer that has not authenticated within this is not a viewer. */
    private static final long AUTH_DEADLINE_MS = 60_000;

    interface Signal {
        /** Send one signalling message towards this peer. */
        void toPeer(String peerId, String json);

        /** This peer is finished — drop it from the hub. */
        void gone(String peerId);
    }

    private final Context ctx;
    private final WebServer.Host host;
    private final Signal signal;
    final String peerId;

    private PeerConnection pc;
    private DataChannel control;
    private DataChannel files;
    private RtpSender videoSender;
    private WebRtcFiles transfers;

    private volatile boolean authenticated;
    private int authTries;
    private final long created = SystemClock.elapsedRealtime();
    private volatile boolean closed;

    WebRtcPeer(Context ctx, WebServer.Host host, Signal signal, String peerId) {
        this.ctx = ctx;
        this.host = host;
        this.signal = signal;
        this.peerId = peerId;
    }

    boolean authenticated() {
        return authenticated;
    }

    boolean expired() {
        return !authenticated && SystemClock.elapsedRealtime() - created > AUTH_DEADLINE_MS;
    }

    /** Build the connection and put an offer on the wire. */
    void start(PeerConnectionFactory factory, VideoTrack track) {
        pc = factory.createPeerConnection(WebRtc.configuration(ctx), this);
        if (pc == null) {
            DexLog.warn("web", "could not create a peer connection for " + peerId);
            signal.gone(peerId);
            return;
        }

        DataChannel.Init controlInit = new DataChannel.Init();
        controlInit.ordered = true;
        control = pc.createDataChannel("ctl", controlInit);
        control.registerObserver(new ChannelObserver(control, true));

        DataChannel.Init fileInit = new DataChannel.Init();
        fileInit.ordered = true;
        files = pc.createDataChannel("file", fileInit);
        files.registerObserver(new ChannelObserver(files, false));
        transfers = new WebRtcFiles(ctx, this::sendFileJson, this::sendFileBinary,
                this::fileBacklog);

        videoSender = pc.addTrack(track, Collections.singletonList("dex"));
        setVideoActive(false);

        MediaConstraints constraints = new MediaConstraints();
        pc.createOffer(new Sdp("offer") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new Sdp("set-local") {
                }, sdp);
                try {
                    signal.toPeer(peerId, new JSONObject()
                            .put("t", "offer").put("sdp", sdp.description).toString());
                } catch (Exception e) {
                    DexLog.warn("web", "could not send the offer", e);
                }
            }
        }, constraints);
    }

    void onAnswer(String sdp) {
        if (pc == null) return;
        pc.setRemoteDescription(new Sdp("set-remote") {
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    void onRemoteCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        if (pc == null) return;
        pc.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
    }

    /**
     * Turn the picture on or off for THIS viewer.
     *
     * Per-sender rather than on the track: the track is shared by every peer,
     * so disabling it would black out the authenticated viewers too.
     */
    private void setVideoActive(boolean active) {
        if (videoSender == null) return;
        try {
            RtpParameters params = videoSender.getParameters();
            if (params == null || params.encodings.isEmpty()) return;
            for (RtpParameters.Encoding encoding : params.encodings) {
                encoding.active = active;
                encoding.maxBitrateBps = active ? Web.bitrate(ctx) * 1_000_000 : null;
                encoding.maxFramerate = active ? Web.fps(ctx) : null;
            }
            videoSender.setParameters(params);
        } catch (Exception e) {
            DexLog.warn("web", "could not set the video encoding", e);
        }
    }

    /** Settings moved while this viewer was connected. */
    void applySettings() {
        if (authenticated) setVideoActive(true);
        sendControl(WebControl.formatJson(ctx));
    }

    void sendControl(String json) {
        send(control, json);
    }

    private void sendFileJson(String json) {
        send(files, json);
    }

    private void sendFileBinary(byte[] data) {
        DataChannel channel = files;
        if (channel == null || closed) return;
        try {
            channel.send(new DataChannel.Buffer(ByteBuffer.wrap(data), true));
        } catch (Exception e) {
            DexLog.warn("web", "file channel send failed", e);
        }
    }

    /** How much is waiting on the file channel — the transfer's backpressure. */
    long fileBacklog() {
        DataChannel channel = files;
        try {
            return channel == null ? 0 : channel.bufferedAmount();
        } catch (Exception e) {
            return 0;
        }
    }

    private void send(DataChannel channel, String json) {
        if (channel == null || closed) return;
        try {
            if (channel.state() != DataChannel.State.OPEN) return;
            channel.send(new DataChannel.Buffer(
                    ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), false));
        } catch (Exception e) {
            DexLog.warn("web", "control channel send failed", e);
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        if (transfers != null) transfers.close();
        for (DataChannel channel : new DataChannel[]{control, files}) {
            if (channel == null) continue;
            try {
                channel.unregisterObserver();
                channel.close();
                channel.dispose();
            } catch (Exception ignored) {
            }
        }
        control = null;
        files = null;
        if (pc != null) {
            try {
                pc.close();
                pc.dispose();
            } catch (Exception ignored) {
            }
            pc = null;
        }
        DexLog.step("web", "WebRTC viewer " + peerId + " closed");
    }

    // ── the channels ──

    private final class ChannelObserver implements DataChannel.Observer {
        private final DataChannel channel;
        private final boolean isControl;

        ChannelObserver(DataChannel channel, boolean isControl) {
            this.channel = channel;
            this.isControl = isControl;
        }

        @Override
        public void onBufferedAmountChange(long previous) {
        }

        @Override
        public void onStateChange() {
            try {
                if (isControl && channel.state() == DataChannel.State.OPEN) {
                    // The page cannot ask for the access code until it knows
                    // one is wanted, and this is the first thing it can hear.
                    sendControl("{\"t\":\"hello\",\"auth\":true}");
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);
            if (isControl) {
                onControlMessage(new String(data, StandardCharsets.UTF_8));
            } else if (buffer.binary) {
                if (authenticated && transfers != null) transfers.onBinary(data);
            } else {
                if (authenticated && transfers != null) {
                    transfers.onJson(new String(data, StandardCharsets.UTF_8));
                }
            }
        }
    }

    private void onControlMessage(String text) {
        if (!authenticated) {
            tryAuth(text);
            return;
        }
        WebControl.handle(ctx, host, text, this::sendControl, true);
    }

    /**
     * The access code, over the data channel rather than over HTTP.
     *
     * It has to be here: in a rendezvous session the phone's HTTP server is
     * not reachable at all, so there is no /api/auth to post to and this
     * channel is the only thing the two ends share. Which also means the
     * rendezvous never sees a credential — it relays an offer and an answer
     * and learns nothing else.
     */
    private void tryAuth(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            if (!"auth".equals(msg.optString("t"))) {
                // Anything else before authentication is answered with the
                // demand, not with silence — a page that reloaded mid-session
                // needs to be told to ask again.
                sendControl("{\"t\":\"hello\",\"auth\":true}");
                return;
            }
            // A session token first: a viewer that came through the HTTP
            // door and then upgraded to a peer connection has already proven
            // itself once, and asking again for the same session is noise.
            // Rendezvous viewers have no token and fall through to the code.
            String token = msg.optString("token", "");
            String given = msg.optString("pin", "").trim();
            if ((!token.isEmpty() && host.validSession(token))
                    || Web.secureEquals(given, Web.pin(ctx))) {
                authenticated = true;
                setVideoActive(true);
                sendControl("{\"t\":\"auth\",\"ok\":true}");
                sendControl(WebControl.formatJson(ctx));
                DexLog.step("web", "WebRTC viewer " + peerId + " authenticated");
                host.onViewersChanged();
                return;
            }
            authTries++;
            DexLog.warn("web", "bad access code from WebRTC viewer " + peerId
                    + " (" + authTries + "/" + MAX_AUTH_TRIES + ")");
            sendControl("{\"t\":\"auth\",\"ok\":false,\"left\":"
                    + (MAX_AUTH_TRIES - authTries) + "}");
            if (authTries >= MAX_AUTH_TRIES) {
                // No lockout timer here, unlike the HTTP door: a peer is
                // cheap to drop and the rendezvous makes a new one cost a
                // fresh offer, which is its own rate limit.
                signal.gone(peerId);
            }
        } catch (Exception e) {
            DexLog.warn("web", "malformed auth from " + peerId, e);
        }
    }

    // ── PeerConnection.Observer ──

    @Override
    public void onIceCandidate(IceCandidate candidate) {
        try {
            signal.toPeer(peerId, new JSONObject()
                    .put("t", "ice")
                    .put("mid", candidate.sdpMid)
                    .put("index", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp).toString());
        } catch (Exception e) {
            DexLog.warn("web", "could not send a candidate", e);
        }
    }

    @Override
    public void onConnectionChange(PeerConnection.PeerConnectionState state) {
        DexLog.step("web", "WebRTC viewer " + peerId + " is " + state);
        if (state == PeerConnection.PeerConnectionState.FAILED
                || state == PeerConnection.PeerConnectionState.CLOSED) {
            signal.gone(peerId);
        } else if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            host.onViewersChanged();
        }
    }

    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
    }

    @Override
    public void onSignalingChange(PeerConnection.SignalingState state) {
    }

    @Override
    public void onIceConnectionReceivingChange(boolean receiving) {
    }

    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] candidates) {
    }

    @Override
    public void onAddStream(MediaStream stream) {
    }

    @Override
    public void onRemoveStream(MediaStream stream) {
    }

    @Override
    public void onDataChannel(DataChannel channel) {
        // The phone creates both channels, so anything the browser opens is
        // not part of this protocol.
        try {
            channel.close();
            channel.dispose();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRenegotiationNeeded() {
        // Fires once while the offer is being built. There is no second
        // negotiation in this design — see the class comment.
    }

    @Override
    public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
    }

    @Override
    public void onTrack(org.webrtc.RtpTransceiver transceiver) {
        // The browser sends us nothing; if it ever does, drop it rather than
        // decode it.
        MediaStreamTrack track = transceiver.getReceiver().track();
        if (track != null) track.setEnabled(false);
    }

    /** SdpObserver with the four methods most calls do not care about. */
    private abstract static class Sdp implements SdpObserver {
        private final String what;

        Sdp(String what) {
            this.what = what;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            DexLog.warn("web", what + " failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            DexLog.warn("web", what + " failed: " + error);
        }
    }
}
