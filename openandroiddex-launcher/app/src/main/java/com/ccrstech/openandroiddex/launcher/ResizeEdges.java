package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The edges a desktop window is resized by — ours, not One UI's.
 *
 * <p>One UI's own grab zone around a freeform window is a few pixels wide, and the pointer
 * the computer draws over the video never changes shape over it: with scrcpy's default
 * pointer the device's PointerIcon is computed and dropped (DexCursors). Both made a
 * window's edge something to hunt for (issue #5). This puts a zone of our own around
 * every window, {@link ResizeGeometry}'s size, says what the pointer should look like over
 * it — to the phone's pointer, the touchpad's, and the computer's — and carries out the
 * drag itself through the window daemon.
 *
 * <p><b>Four small windows per desktop window</b>, one per side: accessibility overlays on
 * the desktop display, the type the touchpad's arrow and a Linux app's menus use. Their
 * frames sit over other windows, so two things are load-bearing:
 * <ul>
 *   <li>an accessibility overlay is a TRUSTED overlay, so a frame over another app's window
 *       does not trip untrusted-touch occlusion (doc/custom-titlebar-v2.md §2) — that app
 *       keeps every touch outside our touchable region;</li>
 *   <li>the touchable region ({@link AttachedSurfaceControl#setTouchableRegion}) is only
 *       the part of the strip the pointer can really reach: the windows in front of this
 *       one, the taskbar, its menus and a keyboard are cut out of it
 *       ({@link ResizeGeometry#reachable}). These windows are above every app, so a strip
 *       left whole would steal clicks from whatever lies in front of its own window.</li>
 * </ul>
 * Not one display-sized window with a region: it would be a display-sized buffer, and on a
 * 4K desktop that is tens of megabytes held for a transparent picture.
 *
 * <p><b>The drag.</b> Where the shell re-crops a window the moment its bounds change (One
 * UI, and AOSP's own desktop mode), it is live, the way AOSP's FluidResizeTaskPositioner
 * does it: the daemon's SIZE (applyTransaction, bounds and caption strip together) per
 * frame, latest wins, then one RESIZE transition on release. Where nothing re-crops until
 * a transition runs — a shell that draws no caption windows on this display — a live drag
 * would only show the window clipped to where it was, so there the drag draws an outline
 * and the window takes it on release.
 *
 * <p>Everything here runs on the service's main thread; only the daemon's round trips go
 * to its client thread.
 */
final class ResizeEdges {

    private static final String TITLE = "openandroiddex-edge";
    /**
     * How long the pointer may be between two strips before it is told it has left them:
     * going round a corner is a HOVER_EXIT from one strip and an ENTER on the next, and the
     * pointer should not flash the arrow in between.
     */
    private static final long LEAVE_MS = 60L;

    private final CaptionService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int display = -1;
    private Context window;
    private WindowManager windows;
    private ResizeGeometry.Sizes sizes = ResizeGeometry.sizes(1f);
    private int[] limits = {0, 0, 1, 1};
    private boolean live = true;

    /** taskId -> that window's four strips. */
    private final SparseArray<Edges> byTask = new SparseArray<>();
    private Drag drag;
    private EdgeView hovered;
    /** What every pointer was last told: a ResizeGeometry.SHAPE_*. */
    private int shape = ResizeGeometry.SHAPE_NONE;
    /**
     * The daemon connection that shape last went over for the computer's cursor
     * ({@link WmClient#connections}), taken or refused; -1 before any. The daemon puts the
     * arrow back when a connection ends, so a shape still wanted goes again once the client
     * is on a new one — on the next hover, not only when the shape changes. Written on the
     * client's thread.
     */
    private volatile int shapeSentOn = -1;
    /** A shape is on its way to the daemon: the next hover does not send it again. */
    private volatile boolean shapeSending;

    private final Runnable left = () -> {
        if (hovered == null && drag == null) say(ResizeGeometry.SHAPE_NONE);
    };

    ResizeEdges(CaptionService service) {
        this.service = service;
    }

    /** One desktop window's strips, by ResizeGeometry.BAND_*. */
    private static final class Edges {
        final int taskId;
        int[] rect;
        final EdgeView[] views = new EdgeView[4];
        final WindowManager.LayoutParams[] params = new WindowManager.LayoutParams[4];
        final Region[] regions = new Region[4];

        Edges(int taskId) {
            this.taskId = taskId;
        }
    }

    /** A drag under way: what was grabbed, and where the window has been taken so far. */
    private static final class Drag {
        final EdgeView view;
        final int taskId;
        final int display;
        final int[] start;
        final int edges;
        final int downX;
        final int downY;
        final int minW;
        final int minH;
        final int[] limits;
        final int caption;
        final boolean live;
        int[] last;
        /** Guarded by itself: the newest rect for the daemon, and whether a send is queued. */
        int[] want;
        boolean pending;
        Outline outline;

        Drag(EdgeView view, int taskId, int display, int[] start, int edges, int downX,
             int downY, int[] min, int[] limits, int caption, boolean live) {
            this.view = view;
            this.taskId = taskId;
            this.display = display;
            this.start = start;
            this.edges = edges;
            this.downX = downX;
            this.downY = downY;
            this.minW = min[0];
            this.minH = min[1];
            this.limits = limits;
            this.caption = caption;
            this.live = live;
        }
    }

    // ── following the desktop ──

    /**
     * Put the strips where the windows are now. Called on every reconcile pass; only what
     * changed is moved, and nothing is touched while a drag is under way — the drag changes
     * all of it, and puts it right itself when it ends.
     *
     * @param stack     the desktop's windows, topmost first
     * @param resizable per window: may it be resized by its edges at all
     * @param covers    what lies above every window (taskbar, its menus, a keyboard)
     * @param live      whether the shell re-crops a window as its bounds change
     */
    void update(int displayId, float density, List<WmClient.Task> stack, boolean[] resizable,
                List<int[]> covers, int[] displayRect, int[] limits, boolean live) {
        if (drag != null) return;
        if (Build.VERSION.SDK_INT < 34) return;      // setTouchableRegion
        if (displayId != display) {
            clear();
            display = displayId;
        }
        if (window == null) {
            window = overlayWindow(displayId);
            if (window == null) return;
            windows = window.getSystemService(WindowManager.class);
        }
        this.sizes = ResizeGeometry.sizes(density);
        this.limits = limits.clone();
        this.live = live;

        List<int[]> rects = new ArrayList<>();
        for (WmClient.Task t : stack) rects.add(new int[]{t.left, t.top, t.right, t.bottom});
        List<List<List<int[]>>> reach =
                ResizeGeometry.reachable(rects, resizable, displayRect, covers, sizes);

        Set<Integer> kept = new HashSet<>();
        for (int n = 0; n < stack.size(); n++) {
            if (!resizable[n]) continue;
            WmClient.Task t = stack.get(n);
            kept.add(t.taskId);
            Edges e = byTask.get(t.taskId);
            if (e == null) {
                e = new Edges(t.taskId);
                byTask.put(t.taskId, e);
            }
            e.rect = rects.get(n);
            int[][] bands = ResizeGeometry.bands(e.rect, sizes);
            for (int b = 0; b < 4; b++) {
                place(e, b, ResizeGeometry.intersect(bands[b], displayRect), reach.get(n).get(b));
            }
        }
        for (int i = byTask.size() - 1; i >= 0; i--) {
            if (kept.contains(byTask.keyAt(i))) continue;
            remove(byTask.valueAt(i));
            byTask.removeAt(i);
        }
    }

    /** Take every strip down and let the pointer go back to an arrow. */
    void clear() {
        main.removeCallbacks(left);
        if (drag != null) {
            hideOutline(drag);
            drag = null;
        }
        for (int i = 0; i < byTask.size(); i++) remove(byTask.valueAt(i));
        byTask.clear();
        hovered = null;
        say(ResizeGeometry.SHAPE_NONE);
        window = null;
        windows = null;
        display = -1;
    }

    /** One strip, where {@code frame} is, touchable only over {@code parts}. */
    private void place(Edges e, int band, int[] frame, List<int[]> parts) {
        if (frame == null) {
            removeStrip(e, band);
            return;
        }
        Region region = new Region();
        for (int[] p : parts) {
            region.op(p[0] - frame[0], p[1] - frame[1], p[2] - frame[0], p[3] - frame[1],
                    Region.Op.UNION);
        }
        int w = frame[2] - frame[0];
        int h = frame[3] - frame[1];
        EdgeView v = e.views[band];
        WindowManager.LayoutParams lp = e.params[band];
        if (v == null) {
            v = new EdgeView(window, e.taskId);
            v.originX = frame[0];
            v.originY = frame[1];
            v.setRegion(region);
            lp = params(frame[0], frame[1], w, h);
            try {
                windows.addView(v, lp);
            } catch (RuntimeException ex) {
                service.trace("edge window refused (" + ex.getMessage() + ")");
                return;
            }
            e.views[band] = v;
            e.params[band] = lp;
            e.regions[band] = region;
            return;
        }
        if (lp.x != frame[0] || lp.y != frame[1] || lp.width != w || lp.height != h) {
            lp.x = frame[0];
            lp.y = frame[1];
            lp.width = w;
            lp.height = h;
            v.originX = frame[0];
            v.originY = frame[1];
            try {
                windows.updateViewLayout(v, lp);
            } catch (RuntimeException ignored) {
                // the display went away under it; the next pass clears
            }
        }
        if (!region.equals(e.regions[band])) {
            e.regions[band] = region;
            v.setRegion(region);
        }
    }

    private WindowManager.LayoutParams params(int x, int y, int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // x/y are display pixels, edges past the display's included
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        lp.windowAnimations = 0;
        lp.setTitle(TITLE);
        CaptionService.accessibilityTitle(lp, TITLE);
        return lp;
    }

    private void removeStrip(Edges e, int band) {
        EdgeView v = e.views[band];
        if (v == null) return;
        if (hovered == v) {
            hovered = null;
            main.removeCallbacks(left);
            main.postDelayed(left, LEAVE_MS);
        }
        try {
            windows.removeViewImmediate(v);
        } catch (RuntimeException ignored) {
        }
        e.views[band] = null;
        e.params[band] = null;
        e.regions[band] = null;
    }

    private void remove(Edges e) {
        for (int b = 0; b < 4; b++) removeStrip(e, b);
    }

    private Context overlayWindow(int displayId) {
        try {
            Display d = service.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (d == null) return null;
            return service.createDisplayContext(d).createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
        } catch (RuntimeException e) {
            service.trace("no window context for edges on " + displayId + ": " + e);
            return null;
        }
    }

    // ── the pointer ──

    /**
     * Tell every pointer what it is over. The phone's own pointer asks for itself
     * ({@link EdgeView#onResolvePointerIcon}); the touchpad's arrow is ours to change; the
     * computer's cursor is the daemon's to pass on.
     */
    private void say(int next) {
        boolean changed = next != shape;
        if (!changed && (shapeSending || shapeSentOn == service.daemonConnections())) return;
        shape = next;
        if (changed) DesktopPointer.shape(dexRole(next));
        final int d = display;
        if (d < 0) return;
        final String name = ResizeGeometry.shapeName(next);
        // Set before the post, not from its result: the client's thread may be done first.
        shapeSending = true;
        boolean posted = service.daemon(wm -> {
            wm.shape(d, name);
            shapeSentOn = wm.connections();
            shapeSending = false;
        });
        if (!posted) shapeSending = false;
    }

    private int shapeAt(int taskId, int x, int y) {
        Edges e = byTask.get(taskId);
        if (e == null || e.rect == null) return ResizeGeometry.SHAPE_NONE;
        return ResizeGeometry.shapeFor(ResizeGeometry.edgesAt(e.rect, sizes, x, y));
    }

    static int dexRole(int shape) {
        switch (shape) {
            case ResizeGeometry.SHAPE_EW: return DexCursors.ROLE_RESIZE_H;
            case ResizeGeometry.SHAPE_NS: return DexCursors.ROLE_RESIZE_V;
            case ResizeGeometry.SHAPE_NWSE: return DexCursors.ROLE_RESIZE_NWSE;
            case ResizeGeometry.SHAPE_NESW: return DexCursors.ROLE_RESIZE_NESW;
            default: return DexCursors.ROLE_ARROW;
        }
    }

    private boolean hover(EdgeView v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                main.removeCallbacks(left);
                hovered = v;
                if (drag == null) {
                    say(shapeAt(v.taskId, Math.round(e.getRawX()), Math.round(e.getRawY())));
                }
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (hovered == v) {
                    hovered = null;
                    main.removeCallbacks(left);
                    main.postDelayed(left, LEAVE_MS);
                }
                return true;
            default:
                return false;
        }
    }

    // ── the drag ──

    private boolean touch(EdgeView v, MotionEvent e) {
        int x = Math.round(e.getRawX());
        int y = Math.round(e.getRawY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (drag != null) return false;
                // A mouse's other buttons are not a grab: the right one is the launcher's
                // context menu everywhere else. A finger or a pen has no buttons at all.
                int buttons = e.getButtonState();
                if (buttons != 0 && (buttons & MotionEvent.BUTTON_PRIMARY) == 0) return false;
                Edges edges = byTask.get(v.taskId);
                if (edges == null || edges.rect == null) return false;
                int grab = ResizeGeometry.edgesAt(edges.rect, sizes, x, y);
                if (grab == 0) return false;
                main.removeCallbacks(left);
                drag = new Drag(v, v.taskId, display, edges.rect.clone(), grab, x, y,
                        service.minSize(v.taskId), limits.clone(),
                        service.captionPx(v.taskId), live);
                say(ResizeGeometry.shapeFor(grab));
                service.grabbed(v.taskId);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                Drag d = drag;
                if (d == null || d.view != v) return false;
                int[] r = ResizeGeometry.resized(d.start, d.edges, x - d.downX, y - d.downY,
                        d.minW, d.minH, d.limits);
                if (Arrays.equals(r, d.last)) return true;
                d.last = r;
                if (d.live) {
                    service.resizing(d.taskId, r);
                    send(d, r);
                } else {
                    showOutline(d, r);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                Drag d = drag;
                if (d == null || d.view != v) return false;
                drag = null;
                hideOutline(d);
                commit(d);
                Edges now = byTask.get(d.taskId);
                if (now != null && d.last != null) now.rect = d.last;
                // The arrow again, even though the pointer is usually still on the edge it
                // dragged. After a press Android sends hover only to the window the pointer
                // moves onto next, and that is this strip only if the pointer stays on it:
                // moved into the window or onto its title bar, nothing ever tells us, and the
                // double arrow stayed on the computer's cursor (seen on the S25). Still on
                // the edge, the next move over it — the strips are moved to the new edges by
                // the reconcile kicked below — says so again.
                say(ResizeGeometry.SHAPE_NONE);
                service.kick();
                return true;
            }
            default:
                return false;
        }
    }

    /** The newest rect to the daemon; one in flight at a time, the latest replacing the rest. */
    private void send(Drag d, int[] r) {
        synchronized (d) {
            d.want = r;
            if (d.pending) return;
            d.pending = true;
        }
        service.daemon(wm -> {
            int[] want;
            synchronized (d) {
                want = d.want;
                d.pending = false;
            }
            wm.size(d.display, d.taskId, want[0], want[1], want[2], want[3], d.caption);
        });
    }

    /**
     * Where the window ends up. Queued behind any live send still waiting, on the same
     * thread, so the last word is this one; a transition, so a shell that did not follow
     * the drag re-crops the window now, and the caption strip is set in the same step.
     */
    private void commit(Drag d) {
        final int[] r = d.last;
        if (r == null) return;                       // a click on an edge, not a drag
        service.resized(d.taskId, r);
        service.daemon(wm -> wm.resize(d.display, d.taskId, r[0], r[1], r[2], r[3], d.caption));
    }

    /** Where nothing follows the drag: an outline where the window is going. */
    private void showOutline(Drag d, int[] r) {
        if (d.outline == null) d.outline = new Outline(window, sizes.inside);
        d.outline.show(r);
    }

    private void hideOutline(Drag d) {
        if (d.outline == null) return;
        d.outline.hide();
        d.outline = null;
    }

    /**
     * Where a window is going, for a shell that does not move it while it goes: an
     * outline in the accent, a wash inside it, and nothing that can be clicked. Shared
     * with the title bar's own drag (CaptionService), which needs it on the same displays
     * for the same reason — see the class comment.
     */
    static final class Outline {
        private final Context window;
        private final WindowManager windows;
        private final int stroke;
        private View view;
        private WindowManager.LayoutParams lp;

        /** {@code window}: an accessibility-overlay context on the desktop display. */
        Outline(Context window, int stroke) {
            this.window = window;
            this.windows = window == null ? null : window.getSystemService(WindowManager.class);
            this.stroke = Math.max(2, stroke);
        }

        void show(int[] r) {
            if (windows == null) return;
            int w = r[2] - r[0];
            int h = r[3] - r[1];
            if (view == null) {
                View v = new View(window);
                GradientDrawable g = new GradientDrawable();
                DexTheme theme = DexTheme.of(window);
                g.setColor((0x22 << 24) | (theme.accent & 0x00FFFFFF));
                g.setStroke(stroke, theme.accent);
                v.setBackground(g);
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.LEFT;
                p.x = r[0];
                p.y = r[1];
                p.windowAnimations = 0;
                p.setTitle(TITLE);
                try {
                    windows.addView(v, p);
                } catch (RuntimeException ex) {
                    return;
                }
                view = v;
                lp = p;
                return;
            }
            lp.x = r[0];
            lp.y = r[1];
            lp.width = w;
            lp.height = h;
            try {
                windows.updateViewLayout(view, lp);
            } catch (RuntimeException ignored) {
            }
        }

        void hide() {
            if (view == null) return;
            try {
                windows.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
            lp = null;
        }
    }

    /** One strip: draws nothing, takes the pointer only where its region says. */
    private final class EdgeView extends View {
        final int taskId;
        int originX;
        int originY;
        private Region region;

        EdgeView(Context ctx, int taskId) {
            super(ctx);
            this.taskId = taskId;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setRegion(Region r) {
            region = r;
            AttachedSurfaceControl root = getRootSurfaceControl();
            if (root != null) root.setTouchableRegion(r);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // Asked for before the window existed: there was no surface to give it to.
            if (region != null) setRegion(region);
        }

        @Override
        public boolean onHoverEvent(MotionEvent e) {
            return hover(this, e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return touch(this, e);
        }

        @Override
        public PointerIcon onResolvePointerIcon(MotionEvent e, int pointerIndex) {
            Drag d = drag;
            int s = d != null && d.view == this
                    ? ResizeGeometry.shapeFor(d.edges)
                    : shapeAt(taskId, Math.round(e.getRawX()), Math.round(e.getRawY()));
            return DexCursors.icon(getContext(), dexRole(s));
        }
    }
}
