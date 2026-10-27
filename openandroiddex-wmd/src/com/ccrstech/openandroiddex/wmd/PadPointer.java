package com.ccrstech.openandroiddex.wmd;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.Surface;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone touchpad's pointer, drawn here where no window of the launcher's will do.
 *
 * One UI below Android 13 composes every overlay window on an untrusted virtual display
 * under the apps — accessibility overlays as well (LauncherActivity.overlaysBuried). And
 * the only windows an app can put over the point a click aims at without the click being
 * dropped as tapjacking are accessibility overlays, which need the caption service up.
 * A layer at the root of SurfaceFlinger, on that display's layer stack, is above all of
 * it: it is where the system draws a real mouse's pointer. Making one takes
 * ACCESS_SURFACE_FLINGER, which this uid holds and an app never will. Measured on a Galaxy
 * A13 (One UI 4, Android 12): the layer is composited over the desktop and every window
 * on it, and scrcpy's encoder takes it like the rest.
 *
 * The launcher still draws the arrow — the look Settings picked, at the desktop's density
 * — and sends it once (CURSOR). MOUSE then moves it in the same step as the hover it
 * injects, and LEAVE hides it. The layer is the connection's: when the socket that sent
 * it closes, for whatever reason, the arrow goes too, rather than stay frozen over the
 * desktop until this process does.
 */
final class PadPointer {

    /**
     * Largest arrow taken, either way, in pixels. The launcher's uid is the Linux guest's
     * too (WmDaemon.PC_ONLY), and a layer over everything on the desktop must not become
     * a picture of a dialog. A pointer at its biggest setting is well inside this.
     */
    static final int MAX_PX = 256;

    /** The longest base64 accepted for one: a 256 px square that will not compress. */
    static final int MAX_BASE64 = 400_000;

    /** Per display; at most one pointer on each. Guarded by the class. */
    private static final Map<Integer, PadPointer> POINTERS = new HashMap<>();

    private final Object owner;
    private final SurfaceControl layer;
    private final Surface surface;
    private final int hotX;
    private final int hotY;
    private final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
    private boolean visible;

    private PadPointer(Object owner, int displayId, Bitmap arrow, int hotX, int hotY) {
        this.owner = owner;
        this.hotX = hotX;
        this.hotY = hotY;
        // Hidden until the first move: until its stack is set below, a new root layer is
        // on the phone's own screen's.
        layer = new SurfaceControl.Builder()
                .setName("Touchpad pointer")
                .setBufferSize(arrow.getWidth(), arrow.getHeight())
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(false)
                .setHidden(true)
                .build();
        Surface drawn = null;
        try {
            // No parent: a root layer, which only a caller with ACCESS_SURFACE_FLINGER
            // gets composited. The stack is what puts it on this display and no other.
            Refl.callSig(t, "setLayerStack", new Class<?>[]{SurfaceControl.class, int.class},
                    layer, layerStack(displayId));
            // Over the very point each click aims at, and from another uid than the app
            // there: untrusted, that is "occlusion" to Android 12's tapjacking guard,
            // which drops the click — measured as input window "bbq-wrapper",
            // BLOCK_UNTRUSTED, on the A13. The session turns the guard off (adb.rs,
            // TOUCH_GLOBAL) where it can be; from Android 16 it cannot. A trusted overlay
            // is never occlusion, as the system's own pointer is not, and it takes the
            // authority this uid has.
            if (Refl.hasMethod(SurfaceControl.Transaction.class, "setTrustedOverlay",
                    SurfaceControl.class, boolean.class)) {
                Refl.callSig(t, "setTrustedOverlay",
                        new Class<?>[]{SurfaceControl.class, boolean.class}, layer, true);
            }
            t.setLayer(layer, Integer.MAX_VALUE);
            t.apply();
            drawn = new Surface(layer);
            Canvas c = drawn.lockCanvas(null);
            try {
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                c.drawBitmap(arrow, 0, 0, null);
            } finally {
                drawn.unlockCanvasAndPost(c);
            }
        } catch (RuntimeException e) {
            if (drawn != null) drawn.release();
            layer.release();
            throw e;
        }
        surface = drawn;
    }

    /**
     * Draw {@code png} as the pointer on {@code displayId}, its hot spot at ({@code x},
     * {@code y}), in place of any it had. Throws with the reason it would not.
     */
    static synchronized void set(Object owner, int displayId, int x, int y,
                                 int hotX, int hotY, byte[] png) {
        // Read the size before decoding: a small file can declare a vast image.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("not an image");
        }
        if (bounds.outWidth > MAX_PX || bounds.outHeight > MAX_PX) {
            throw new IllegalArgumentException("too big for a pointer: "
                    + bounds.outWidth + "x" + bounds.outHeight);
        }
        Bitmap arrow = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (arrow == null) throw new IllegalArgumentException("not an image");
        remove(displayId);
        try {
            PadPointer p = new PadPointer(owner, displayId, arrow, hotX, hotY);
            POINTERS.put(displayId, p);
            p.moveTo(x, y);
        } finally {
            arrow.recycle();
        }
    }

    /** Where MOUSE puts the pointer, if this process is drawing one on that display. */
    static synchronized void moveTo(int displayId, int x, int y) {
        PadPointer p = POINTERS.get(displayId);
        if (p != null) p.moveTo(x, y);
    }

    /** LEAVE: out of sight, kept for the next move. */
    static synchronized void hide(int displayId) {
        PadPointer p = POINTERS.get(displayId);
        if (p == null || !p.visible) return;
        p.visible = false;
        p.t.setVisibility(p.layer, false);
        p.t.apply();
    }

    static synchronized void remove(int displayId) {
        PadPointer p = POINTERS.remove(displayId);
        if (p != null) p.release();
    }

    /** A connection has closed: every pointer it drew goes with it. */
    static synchronized void dropOwnedBy(Object owner) {
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, PadPointer> e : POINTERS.entrySet()) {
            if (e.getValue().owner == owner) gone.add(e.getKey());
        }
        for (int displayId : gone) remove(displayId);
    }

    private void moveTo(int x, int y) {
        t.setPosition(layer, x - hotX, y - hotY);
        if (!visible) {
            visible = true;
            t.setVisibility(layer, true);
        }
        t.apply();
    }

    private void release() {
        try {
            t.setVisibility(layer, false);
            t.reparent(layer, null);
            t.apply();
        } catch (Throwable ignored) {
            // SurfaceFlinger lets go of the layer with the handle regardless
        }
        surface.release();
        layer.release();
    }

    /**
     * The display's layer stack. The same number as its id on every build seen so far —
     * LogicalDisplayMapper hands them out that way — but read rather than assumed, as
     * scrcpy reads it.
     */
    private static int layerStack(int displayId) {
        try {
            Object global = Refl.callStatic(
                    Refl.cls("android.hardware.display.DisplayManagerGlobal"),
                    "getInstance", new Class<?>[]{});
            Object info = Refl.callSig(global, "getDisplayInfo",
                    new Class<?>[]{int.class}, displayId);
            if (info != null) return (Integer) Refl.field(info, "layerStack");
        } catch (Throwable ignored) {
            // fall through to the id
        }
        return displayId;
    }
}
