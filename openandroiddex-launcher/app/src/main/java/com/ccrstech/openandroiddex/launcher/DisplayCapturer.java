package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.view.Surface;

import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * WebRTC's video source, fed from the projection {@link WebService} already
 * owns.
 *
 * This is {@code ScreenCapturerAndroid} with one thing taken out: it does not
 * create the {@link MediaProjection}. That matters because a projection token
 * is single-use — on Android 14 and later, {@code getMediaProjection} may be
 * called once for a consent, and calling it again invalidates the first — so
 * the two transports cannot each ask for their own. One projection is created
 * by the service, and both the WebRTC track (here) and the WebSocket
 * fallback's encoder ({@link WebStream}) hang a virtual display off it. Each
 * display is created only while something is actually watching through it, so
 * a session with one WebRTC viewer costs exactly one.
 *
 * <p>The frames arrive as GPU textures on the {@link SurfaceTextureHelper}'s
 * thread and go straight to WebRTC's encoder without ever being copied into
 * Java — which is what makes a second, simultaneous encode of the same screen
 * affordable at all.
 */
final class DisplayCapturer implements VideoCapturer, VideoSink {

    /**
     * The flags ScreenCapturerAndroid uses. PRESENTATION rather than
     * AUTO_MIRROR: a display created from a projection token mirrors the
     * captured display by construction, and AUTO_MIRROR on top of that is
     * refused on some devices.
     */
    private static final int FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

    private final MediaProjection projection;
    private final int densityDpi;

    private SurfaceTextureHelper helper;
    private CapturerObserver observer;
    private VirtualDisplay display;
    private Surface surface;
    private int width;
    private int height;
    private boolean disposed;

    DisplayCapturer(MediaProjection projection, int densityDpi) {
        this.projection = projection;
        this.densityDpi = densityDpi;
    }

    @Override
    public synchronized void initialize(SurfaceTextureHelper helper, Context ctx,
                                        CapturerObserver observer) {
        this.helper = helper;
        this.observer = observer;
    }

    @Override
    public synchronized void startCapture(int width, int height, int ignoredFramerate) {
        if (disposed) return;
        this.width = width;
        this.height = height;
        helper.setTextureSize(width, height);
        helper.startListening(this);
        surface = new Surface(helper.getSurfaceTexture());
        try {
            display = projection.createVirtualDisplay("openandroiddex-rtc",
                    width, height, densityDpi, FLAGS, surface, null, null);
            observer.onCapturerStarted(true);
            DexLog.step("web", "WebRTC capture started at " + width + "x" + height);
        } catch (Exception e) {
            DexLog.warn("web", "could not create the WebRTC capture display", e);
            observer.onCapturerStarted(false);
        }
    }

    /**
     * Rotation and quality changes both land here.
     *
     * The virtual display is resized in place rather than rebuilt: WebRTC's
     * encoder follows the frame size on its own, so there is nothing to
     * renegotiate and no keyframe to wait for — unlike the WebSocket path,
     * where the same event costs a full pipeline rebuild.
     */
    @Override
    public synchronized void changeCaptureFormat(int width, int height, int ignoredFramerate) {
        if (disposed || display == null) return;
        if (width == this.width && height == this.height) return;
        this.width = width;
        this.height = height;
        helper.setTextureSize(width, height);
        try {
            display.resize(width, height, densityDpi);
            DexLog.step("web", "WebRTC capture resized to " + width + "x" + height);
        } catch (Exception e) {
            DexLog.warn("web", "could not resize the WebRTC capture display", e);
        }
    }

    @Override
    public synchronized void stopCapture() {
        if (helper != null) helper.stopListening();
        release();
        if (observer != null) observer.onCapturerStopped();
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        release();
    }

    private void release() {
        if (display != null) {
            try {
                display.release();
            } catch (Exception ignored) {
            }
            display = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    /** Frames from the texture thread, handed straight to WebRTC. */
    @Override
    public void onFrame(VideoFrame frame) {
        CapturerObserver o = observer;
        if (o != null) o.onFrameCaptured(frame);
    }

    /**
     * True, and it changes WebRTC's whole strategy: a screencast source is
     * encoded to preserve detail in still text rather than motion in a face,
     * and it is allowed to drop to a very low frame rate when nothing moves.
     */
    @Override
    public boolean isScreencast() {
        return true;
    }
}
