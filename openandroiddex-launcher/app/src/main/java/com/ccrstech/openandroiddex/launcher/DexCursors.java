package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsSeekBar;
import android.widget.EditText;
import android.widget.TextView;

/**
 * The mouse pointers offered in Settings &rarr; Mouse &amp; cursor.
 *
 * They are painted, not shipped, for the same reason the wallpapers are: the
 * desktop is re-created at whatever density {@code wm density} last put on the
 * display, and a cursor is the one bitmap in the system that the framework
 * refuses to scale for you. {@code PointerIcon.create(Bitmap, ...)} hands the
 * bitmap to native verbatim, 1:1 in physical display pixels — it is multiplied
 * neither by the display density, nor by {@code pointer_scale}, nor by the
 * large-pointer accessibility setting. So every size, every colour and every
 * style in that section is a fresh render, and drawing them from paths is what
 * makes "50% to 300%" a real range rather than five checked-in PNGs.
 *
 * Two axes, deliberately kept apart:
 * <ul>
 *   <li>a ROLE — what the pointer is saying (arrow, link, resize, grabbing).
 *       Fixed by the surface under the mouse, never by the user.</li>
 *   <li>a STYLE — how it says it. The user's choice, applied to every role at
 *       once, because a desktop whose arrow is Outline and whose resize handle
 *       is Solid looks broken rather than customised.</li>
 * </ul>
 *
 * <b>Where this is visible.</b> A PointerIcon is only ever drawn by Android
 * when Android is the side drawing the pointer — that is, when the stream runs
 * in {@code --mouse=uhid} mode (Settings &rarr; Mouse &amp; cursor &rarr; Pointer
 * rendering). Under scrcpy's default {@code --mouse=sdk} the events are
 * injected below PointerChoreographer, no pointer sprite is ever created, and
 * everything here is computed, marshalled and dropped on the floor while the PC
 * draws its own cursor over the video. That is not a bug in this file, and the
 * settings section says so in as many words.
 */
final class DexCursors {

    private DexCursors() {
    }

    // ── styles (the user's pick) ──

    /** Draw nothing of our own; hand back the platform's pointer for the role. */
    static final String STYLE_SYSTEM = "system";
    /** The default: crisp filled arrow with a contrasting keyline. */
    static final String STYLE_DEX = "dex";
    /** Hollow. Never fights the wallpaper for the same pixels. */
    static final String STYLE_OUTLINE = "outline";
    /** Fill only, fatter, no keyline — the shape a low bitrate cannot eat. */
    static final String STYLE_SOLID = "solid";
    /** DeX at 78%: for dense work where the pointer keeps covering the target. */
    static final String STYLE_MINI = "mini";
    /** A ring and a dot instead of an arrow: nothing is ever hidden under it. */
    static final String STYLE_RING = "ring";
    /** DeX, rendered at a third of the resolution and upscaled hard. */
    static final String STYLE_PIXEL = "pixel";
    /** Filled, with a soft drop shadow — the most "desktop OS" of the set. */
    static final String STYLE_SHADOW = "shadow";

    static final String[] STYLES = {
            STYLE_SYSTEM, STYLE_DEX, STYLE_OUTLINE, STYLE_SOLID,
            STYLE_MINI, STYLE_RING, STYLE_PIXEL, STYLE_SHADOW,
    };

    // ── colours ──

    static final String[] COLOURS = {
            "white", "black", "accent", "red", "green", "yellow", "pink", "blue", "purple",
    };

    /**
     * The hues, matching Android 16's own vector-cursor palette where one
     * exists — so the pointer this file draws over our windows and the pointer
     * the platform draws over everything else are the same colour, not two
     * shades of nearly-the-same.
     */
    private static int hue(String id, DexTheme theme) {
        switch (id) {
            case "black": return 0xFF101010;
            case "accent": return theme.accent;
            case "red": return 0xFFF55E57;
            case "green": return 0xFF1AA64A;
            case "yellow": return 0xFFFFC400;
            case "pink": return 0xFFF94AAB;
            case "blue": return 0xFF009DC9;
            case "purple": return 0xFFAD72FF;
            default: return 0xFFFFFFFF;
        }
    }

    // ── outlines ──

    static final String OUTLINE_CONTRAST = "contrast";
    static final String OUTLINE_BLACK = "black";
    static final String OUTLINE_WHITE = "white";
    static final String OUTLINE_NONE = "none";
    static final String[] OUTLINES = {
            OUTLINE_CONTRAST, OUTLINE_BLACK, OUTLINE_WHITE, OUTLINE_NONE,
    };

    // ── roles (fixed by the surface, never by the user) ──

    static final int ROLE_ARROW = 0;
    static final int ROLE_HAND = 1;
    static final int ROLE_TEXT = 2;
    static final int ROLE_VERTICAL_TEXT = 3;
    static final int ROLE_MOVE = 4;
    static final int ROLE_RESIZE_H = 5;
    static final int ROLE_RESIZE_V = 6;
    static final int ROLE_RESIZE_NWSE = 7;
    static final int ROLE_RESIZE_NESW = 8;
    static final int ROLE_GRAB = 9;
    static final int ROLE_GRABBING = 10;
    static final int ROLE_NO_DROP = 11;
    static final int ROLE_CROSSHAIR = 12;
    static final int ROLE_CELL = 13;
    static final int ROLE_WAIT = 14;
    static final int ROLE_HELP = 15;
    static final int ROLE_MENU = 16;
    static final int ROLE_COPY = 17;
    static final int ROLE_ALIAS = 18;
    static final int ROLE_ZOOM_IN = 19;
    static final int ROLE_ZOOM_OUT = 20;
    static final int ROLE_NONE = 21;
    static final int ROLE_COUNT = 22;

    /**
     * What the platform would have drawn for each role.
     *
     * Also the fallback for every role under {@link #STYLE_SYSTEM}, and the
     * answer whenever a render fails — a pointer that failed to draw must come
     * back as the stock one, never as nothing.
     *
     * Note there are no {@code TYPE_*_RESIZE} constants in Android: the four
     * resize roles map onto the DOUBLE_ARROW family.
     */
    private static final int[] SYSTEM_TYPE = {
            PointerIcon.TYPE_ARROW,                                // ARROW
            PointerIcon.TYPE_HAND,                                 // HAND
            PointerIcon.TYPE_TEXT,                                 // TEXT
            PointerIcon.TYPE_VERTICAL_TEXT,                        // VERTICAL_TEXT
            PointerIcon.TYPE_ALL_SCROLL,                           // MOVE
            PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,              // RESIZE_H
            PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,                // RESIZE_V
            PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,       // RESIZE_NWSE
            PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,      // RESIZE_NESW
            PointerIcon.TYPE_GRAB,                                 // GRAB
            PointerIcon.TYPE_GRABBING,                             // GRABBING
            PointerIcon.TYPE_NO_DROP,                              // NO_DROP
            PointerIcon.TYPE_CROSSHAIR,                            // CROSSHAIR
            PointerIcon.TYPE_CELL,                                 // CELL
            PointerIcon.TYPE_WAIT,                                 // WAIT
            PointerIcon.TYPE_HELP,                                 // HELP
            PointerIcon.TYPE_CONTEXT_MENU,                         // MENU
            PointerIcon.TYPE_COPY,                                 // COPY
            PointerIcon.TYPE_ALIAS,                                // ALIAS
            PointerIcon.TYPE_ZOOM_IN,                              // ZOOM_IN
            PointerIcon.TYPE_ZOOM_OUT,                             // ZOOM_OUT
            PointerIcon.TYPE_NULL,                                 // NONE
    };

    // ── geometry ──

    /** Every shape below is drawn in a 24&times;24 box, y down. */
    private static final float BOX = 24f;

    /**
     * The arrow at 100%, in dp.
     *
     * Chosen to sit just above the platform's own pointer at the same density:
     * this desktop is looked at through a video encoder, and the stock arrow is
     * the first thing a low bitrate smears.
     */
    private static final float BASE_DP = 26f;

    /**
     * A hard ceiling on the rendered bitmap, ours rather than the framework's.
     *
     * There is no maximum anywhere in the platform — {@code SpriteController}
     * makes a SurfaceControl exactly the size of whatever bitmap it is handed —
     * which is precisely why one is needed here. Without it the size slider
     * quietly becomes a memory knob on a 4K desktop at 300%.
     */
    private static final int MAX_PX = 128;

    // ── cache ──

    private static final SparseArray<PointerIcon> CACHE = new SparseArray<>();
    /** Set by {@link #invalidate}; makes the next {@link #icon} re-read and redraw. */
    private static boolean stale = true;
    /** The display density the cached pointers were drawn at. */
    private static int density;

    /**
     * The pointer for {@code role}, under the user's current settings.
     *
     * Cached per role: this is asked once per hover event that crosses a view
     * boundary, and a render is a bitmap allocation plus a dozen path ops.
     */
    static synchronized PointerIcon icon(Context ctx, int role) {
        if (role < 0 || role >= ROLE_COUNT) role = ROLE_ARROW;
        if (stale) {
            CACHE.clear();
            // Read ONCE per invalidation. See the note on densityDpi.
            density = densityDpi(ctx);
            stale = false;
        }
        PointerIcon cached = CACHE.get(role);
        if (cached != null) return cached;

        PointerIcon icon = render(ctx, role);
        if (icon == null) icon = PointerIcon.getSystemIcon(ctx, SYSTEM_TYPE[role]);
        CACHE.put(role, icon);
        return icon;
    }

    /**
     * Throw the rendered pointers away; the next {@link #icon} draws them again.
     *
     * Push, not poll. This used to be a poll: every call rebuilt a "tag" out of
     * the five prefs, the accent and the display density and compared it with
     * the last one. That is the wrong shape for this method — the framework
     * asks {@code onResolvePointerIcon} on EVERY {@code ACTION_HOVER_MOVE}, so
     * a mouse being moved ran five SharedPreferences reads, a WindowManager
     * round trip for the density and a string build per input sample, and the
     * pointer visibly lagged the hand. Nothing that feeds a render can change
     * without {@link DexPrefs#broadcast} or a display change, and both call
     * this, so the hot path is now a bounds check and a map lookup.
     */
    static void invalidate() {
        java.util.List<View> live = new java.util.ArrayList<>();
        synchronized (DexCursors.class) {
            CACHE.clear();
            stale = true;
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = ROOTS.iterator();
                 it.hasNext(); ) {
                View root = it.next().get();
                if (root == null) {
                    it.remove();
                } else {
                    live.add(root);
                }
            }
        }
        // Re-stamp every tree that is still on screen.
        //
        // This is the whole reason ROOTS exists. setPointerIcon STORES an icon
        // on a view — it is a snapshot taken when the tree was built, not a
        // question asked when the mouse moves. So a window that this particular
        // settings change does not happen to rebuild keeps the pointer it was
        // born with, and the only thing that ever fixed it was restarting the
        // session. Leaving that to each surface's own rebuild path made the
        // behaviour depend on which of thirteen windows some other feature
        // remembered to rebuild; this does not.
        //
        // Posted rather than walked here: each root belongs to a different
        // window, the caller is often mid-rebuild, and a tree that is being
        // thrown away must not be stamped on the way out — hence the attach
        // check, which drops exactly those.
        for (View root : live) {
            root.post(() -> {
                if (root.isAttachedToWindow()) decorate(root);
            });
        }
    }

    /**
     * Trees that have been given pointers, weakly so a closed window is not
     * held open by this list. Pruned on every pass rather than on close: a
     * popup is dismissed by the framework, not by us, and there is no hook
     * that would reliably fire.
     */
    private static final java.util.ArrayList<java.lang.ref.WeakReference<View>> ROOTS =
            new java.util.ArrayList<>();

    private static synchronized void remember(View root) {
        for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = ROOTS.iterator();
             it.hasNext(); ) {
            View seen = it.next().get();
            if (seen == null) {
                it.remove();
            } else if (seen == root) {
                return;                       // already tracked — decorate is re-entrant
            }
        }
        ROOTS.add(new java.lang.ref.WeakReference<>(root));
    }

    /**
     * The desktop display's real dpi, NOT the one in Resources.
     *
     * Same reason {@link LauncherActivity} keeps its own {@code uiDensity}: a
     * {@code wm density} change reaches Resources only while the activity is
     * foreground, and behind a focused app window — exactly where the Settings
     * window puts the desktop — that delivery is deferred indefinitely. A
     * pointer sized off Resources would come out at the old scale and stay
     * there, which on a cursor is far more visible than on a padding.
     *
     * Falls back to Resources when there is no window manager to ask (a
     * non-UI context), because a pointer at a slightly wrong size beats none.
     *
     * Called once per {@link #invalidate}, never per pointer: getRealMetrics
     * is a round trip to the window manager and this used to sit on the hover
     * path.
     */
    private static int densityDpi(Context ctx) {
        try {
            android.view.WindowManager wm = ctx.getSystemService(android.view.WindowManager.class);
            if (wm != null && wm.getDefaultDisplay() != null) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                if (dm.densityDpi > 0) return dm.densityDpi;
            }
        } catch (Exception ignored) {
            // non-UI context, or a display that went away mid-call
        }
        return ctx.getResources().getDisplayMetrics().densityDpi;
    }

    static String style(Context ctx) {
        return DexPrefs.getString(ctx, DexPrefs.KEY_CURSOR_STYLE, DexPrefs.DEF_CURSOR_STYLE);
    }

    /** The size slider, clamped to the range the picker actually offers. */
    static int size(Context ctx) {
        return clampSize(DexPrefs.getInt(ctx, DexPrefs.KEY_CURSOR_SIZE, DexPrefs.DEF_CURSOR_SIZE));
    }

    static int clampSize(int percent) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, percent));
    }

    static final int MIN_SIZE = 50;
    static final int MAX_SIZE = 300;

    /** True for the keys whose change means every drawn pointer is now stale. */
    static boolean isCursorKey(String key) {
        return DexPrefs.KEY_CURSOR_STYLE.equals(key) || DexPrefs.KEY_CURSOR_SIZE.equals(key)
                || DexPrefs.KEY_CURSOR_COLOR.equals(key)
                || DexPrefs.KEY_CURSOR_OUTLINE.equals(key)
                || DexPrefs.KEY_CURSOR_SHADOW.equals(key);
    }

    // ── applying ──

    /** Give one view its own pointer. Children without one inherit it. */
    static void apply(View view, int role) {
        if (view == null) return;
        try {
            view.setPointerIcon(icon(view.getContext(), role));
        } catch (Exception ignored) {
            // never worth failing a layout over
        }
    }

    /**
     * Give a finished window tree its pointers in one pass.
     *
     * Pushed over the tree rather than threaded through every widget, for the
     * reason {@link DexFonts#applyTo} is: this shell builds its UI in code
     * across a dozen files, and one walk at the end is both less invasive and
     * impossible to forget at one call site. The role each view gets is read
     * off what the view already is — a row that is clickable is a row the
     * pointer should become a hand over — so nothing has to be tagged.
     *
     * Must be called on a tree that is BUILT, and again after it is rebuilt:
     * views added later (an app tile appearing in the taskbar) keep the
     * window root's arrow until their factory asks for their own.
     */
    static void decorate(View root) {
        if (root == null) return;
        remember(root);
        // The root carries the window's default; walking it again would let a
        // clickable window root (the drawer's, which owns its own dispatch)
        // overwrite that arrow with a hand over the whole surface.
        apply(root, ROLE_ARROW);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i));
            }
        }
    }

    private static void walk(View view) {
        // An editable or selectable TextView answers TYPE_TEXT from its own
        // onResolvePointerIcon BEFORE it consults the icon set here, so the
        // beam over the search fields is the platform's and not ours. Setting
        // it anyway costs nothing and is right the day the field stops being
        // an EditText.
        if (view instanceof EditText) {
            apply(view, ROLE_TEXT);
        } else if (view instanceof AbsSeekBar) {
            apply(view, ROLE_GRAB);
        } else if (view.isClickable() || view.isLongClickable() || view.isContextClickable()) {
            apply(view, ROLE_HAND);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i));
            }
        }
    }

    // ── rendering ──

    /** How each style differs, in one place so a new style is one row. */
    private static final class Look {
        float scale = 1f;          // multiplier on the design box
        float stroke = 1.6f;       // keyline width, in design units
        boolean filled = true;
        boolean shadow = false;
        int quantise = 0;          // >0: render this many px and upscale hard
        boolean ring = false;      // arrow becomes a ring and a dot
    }

    private static Look look(String style) {
        Look l = new Look();
        switch (style) {
            case STYLE_OUTLINE:
                l.filled = false;
                l.stroke = 2.3f;
                break;
            case STYLE_SOLID:
                l.scale = 1.08f;
                l.stroke = 0f;
                break;
            case STYLE_MINI:
                l.scale = 0.78f;
                l.stroke = 1.4f;
                break;
            case STYLE_RING:
                l.ring = true;
                l.stroke = 1.5f;
                break;
            case STYLE_PIXEL:
                l.quantise = 11;
                l.stroke = 1.8f;
                break;
            case STYLE_SHADOW:
                l.shadow = true;
                break;
            default:
                break;
        }
        return l;
    }

    /**
     * Draw one role, or null when the platform's own should stand.
     *
     * Returns null rather than throwing for {@link #STYLE_SYSTEM} and for
     * {@link #ROLE_NONE}: both are "we are not drawing this one", and the
     * caller already knows how to ask the platform.
     */
    private static PointerIcon render(Context ctx, int role) {
        String style = style(ctx);
        if (STYLE_SYSTEM.equals(style) || role == ROLE_NONE) return null;

        DexTheme theme = DexTheme.of(ctx);
        Look look = look(style);
        int fill = hue(DexPrefs.getString(ctx, DexPrefs.KEY_CURSOR_COLOR,
                DexPrefs.DEF_CURSOR_COLOR), theme);
        int line = outline(DexPrefs.getString(ctx, DexPrefs.KEY_CURSOR_OUTLINE,
                DexPrefs.DEF_CURSOR_OUTLINE), fill);
        boolean shadow = look.shadow
                || DexPrefs.getBool(ctx, DexPrefs.KEY_CURSOR_SHADOW, DexPrefs.DEF_CURSOR_SHADOW);

        // the density the cache was invalidated at — see icon()
        float wanted = BASE_DP * (density / 160f) * look.scale * (size(ctx) / 100f);

        try {
            return draw(role, look, fill, line, shadow, wanted);
        } catch (Throwable t) {
            // An OOM or a bad path here must cost the stock pointer, not the
            // window: this runs inside a hover event.
            return null;
        }
    }

    private static int outline(String id, int fill) {
        switch (id) {
            case OUTLINE_BLACK: return 0xFF000000;
            case OUTLINE_WHITE: return 0xFFFFFFFF;
            case OUTLINE_NONE: return 0;
            // "Contrast" is the only one that stays readable on every wallpaper
            // AND over every app, because it is chosen from the fill rather
            // than from the backdrop — which a pointer cannot see.
            default: return luminance(fill) > 0.55f ? 0xFF000000 : 0xFFFFFFFF;
        }
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    private static PointerIcon draw(int role, Look look, int fill, int line,
                                    boolean shadow, float wantedPx) {
        Shape shape = shape(role, look);

        // Padding has to hold the half of the keyline that sits outside the
        // path, plus the shadow's blur and offset — both in design units so
        // the sums below stay in one coordinate system.
        float pad = look.stroke / 2f + (shadow ? 2.2f : 0.35f);
        float box = BOX + pad * 2f;

        float s = wantedPx / BOX;
        // The cap is on the BITMAP, which is the padded box — capping the
        // design box instead would let a shadowed 300% pointer overshoot it.
        s = Math.min(s, MAX_PX / box);
        int side = Math.max(8, Math.round(box * s));

        Bitmap bitmap;
        if (look.quantise > 0) {
            // Render small, then blow it up with filtering off. Quantising the
            // paths themselves would fight the round joins; quantising the
            // raster is what actually looks like pixel art.
            int small = Math.max(look.quantise, Math.min(side, Math.round(side / 3f)));
            Bitmap tiny = Bitmap.createBitmap(small, small, Bitmap.Config.ARGB_8888);
            paint(new Canvas(tiny), shape, look, fill, line, shadow, small / box, pad);
            bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            Paint blit = new Paint();
            blit.setFilterBitmap(false);
            blit.setAntiAlias(false);
            new Canvas(bitmap).drawBitmap(tiny, null,
                    new RectF(0f, 0f, side, side), blit);
            tiny.recycle();
        } else {
            bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            paint(new Canvas(bitmap), shape, look, fill, line, shadow, side / box, pad);
        }

        // Strictly inside the bitmap: PointerIcon.create rejects a hotspot at
        // or past the edge, and an arrow's tip lands exactly on the boundary
        // before the padding is added back.
        float hx = Math.min(side - 1f, Math.max(0f, (shape.hotX + pad) * side / box));
        float hy = Math.min(side - 1f, Math.max(0f, (shape.hotY + pad) * side / box));
        return PointerIcon.create(bitmap, hx, hy);
    }

    private static void paint(Canvas canvas, Shape shape, Look look, int fill, int line,
                              boolean shadow, float scale, float pad) {
        canvas.save();
        canvas.scale(scale, scale);
        canvas.translate(pad, pad);

        if (shadow) {
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setColor(0x8C000000);
            shade.setMaskFilter(new BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL));
            canvas.save();
            canvas.translate(0.7f, 1.1f);
            for (Path p : shape.fills) canvas.drawPath(p, shade);
            for (Path p : shape.strokes) canvas.drawPath(p, shade);
            canvas.restore();
        }

        // Keyline first, under the fill: stroking a path centres the line on
        // its edge, so drawing it on top would eat half the shape at every
        // size and make the thin styles look starved.
        if (look.stroke > 0f && line != 0) {
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeJoin(Paint.Join.ROUND);
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setColor(line);
            edge.setStrokeWidth(look.stroke);
            for (Path p : shape.fills) canvas.drawPath(p, edge);

            if (!shape.onPlate) {
                edge.setStrokeWidth(shape.lineWidth + look.stroke);
                for (Path p : shape.strokes) canvas.drawPath(p, edge);
            }
        }

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(fill);
        if (look.filled) {
            body.setStyle(Paint.Style.FILL);
            for (Path p : shape.fills) canvas.drawPath(p, body);
        } else {
            // Hollow, but not empty: a wash inside keeps the shape readable
            // over a busy wallpaper without hiding what is under the pointer.
            Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
            wash.setColor((0x38 << 24) | (fill & 0x00FFFFFF));
            for (Path p : shape.fills) canvas.drawPath(p, wash);
            body.setStyle(Paint.Style.STROKE);
            body.setStrokeJoin(Paint.Join.ROUND);
            body.setStrokeCap(Paint.Cap.ROUND);
            body.setStrokeWidth(look.stroke * 0.9f);
            for (Path p : shape.fills) canvas.drawPath(p, body);
        }

        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setStyle(Paint.Style.STROKE);
        bar.setStrokeJoin(Paint.Join.ROUND);
        bar.setStrokeCap(Paint.Cap.ROUND);
        // "No outline" leaves nothing to draw a plate glyph in, so it falls
        // back to the same contrast rule the automatic keyline uses.
        bar.setColor(shape.onPlate
                ? (line != 0 ? line : (luminance(fill) > 0.55f ? 0xFF000000 : 0xFFFFFFFF))
                : fill);
        bar.setStrokeWidth(shape.lineWidth);
        for (Path p : shape.strokes) canvas.drawPath(p, bar);

        canvas.restore();
    }

    /**
     * One drawn pointer: the closed shapes, the open strokes, and where the
     * click actually lands.
     *
     * Kept apart because the two are painted differently — a closed shape gets
     * a keyline traced around it, an open stroke gets a fatter line drawn
     * underneath it — and a magnifier or a question mark is not expressible as
     * a fill.
     */
    private static final class Shape {
        final java.util.List<Path> fills = new java.util.ArrayList<>();
        final java.util.List<Path> strokes = new java.util.ArrayList<>();
        float lineWidth = 2.1f;
        /**
         * True when the strokes lie ON one of the fills — the little sheet the
         * copy, alias and context-menu pointers carry.
         *
         * Without it those glyphs are drawn in the fill colour on top of a
         * plate of the same fill colour, i.e. invisible but for the keyline
         * halo. On a plate the ink is the keyline's instead, which is also what
         * every real cursor set does.
         */
        boolean onPlate;
        float hotX;
        float hotY;
    }

    private static Shape shape(int role, Look look) {
        Shape s = new Shape();
        switch (role) {
            case ROLE_HAND:      hand(s); break;
            case ROLE_TEXT:      beam(s, false); break;
            case ROLE_VERTICAL_TEXT: beam(s, true); break;
            case ROLE_MOVE:      move(s); break;
            case ROLE_RESIZE_H:  doubleArrow(s, 0f); break;
            case ROLE_RESIZE_V:  doubleArrow(s, 90f); break;
            case ROLE_RESIZE_NWSE: doubleArrow(s, 45f); break;
            case ROLE_RESIZE_NESW: doubleArrow(s, -45f); break;
            case ROLE_GRAB:      openHand(s); break;
            case ROLE_GRABBING:  fist(s); break;
            case ROLE_CROSSHAIR: crosshair(s); break;
            case ROLE_CELL:      cell(s); break;
            case ROLE_WAIT:      hourglass(s); break;
            case ROLE_ZOOM_IN:   magnifier(s, 1); break;
            case ROLE_ZOOM_OUT:  magnifier(s, -1); break;
            case ROLE_NO_DROP:   arrow(s, look); noDropBadge(s); break;
            case ROLE_HELP:      arrow(s, look); helpBadge(s); break;
            case ROLE_MENU:      arrow(s, look); menuBadge(s); break;
            case ROLE_COPY:      arrow(s, look); boxBadge(s, 1); break;
            case ROLE_ALIAS:     arrow(s, look); boxBadge(s, 0); break;
            default:             arrow(s, look); break;
        }
        return s;
    }

    // ── the shapes ──

    /** The pointer everything else is measured against. Tip at the origin. */
    private static void arrow(Shape s, Look look) {
        if (look.ring) {
            // A ring and a dot: the precision pointer. Its hotspot is its
            // centre, not a tip, which is the whole reason it exists — the
            // desktop grid's drag-to-place wants to see what is underneath.
            Path ring = new Path();
            ring.addCircle(12f, 12f, 7.4f, Path.Direction.CW);
            ring.addCircle(12f, 12f, 4.6f, Path.Direction.CCW);
            s.fills.add(ring);
            Path dot = new Path();
            dot.addCircle(12f, 12f, 1.7f, Path.Direction.CW);
            s.fills.add(dot);
            s.hotX = 12f;
            s.hotY = 12f;
            return;
        }
        Path p = new Path();
        p.moveTo(0f, 0f);
        p.lineTo(0f, 17.6f);
        p.lineTo(4.35f, 13.5f);
        p.lineTo(7.15f, 20.4f);
        p.lineTo(9.75f, 19.25f);
        p.lineTo(7.0f, 12.6f);
        p.lineTo(12.7f, 12.5f);
        p.close();
        s.fills.add(p);
        s.hotX = 0f;
        s.hotY = 0f;
    }

    /** The link pointer: an index finger out of a fist. */
    private static void hand(Shape s) {
        Path p = new Path();
        union(p, 7.4f, 1.4f, 10.8f, 14.4f, 1.7f);     // index, extended
        union(p, 10.8f, 6.4f, 14.1f, 14.4f, 1.65f);   // middle
        union(p, 14.1f, 7.4f, 17.4f, 14.4f, 1.65f);   // ring
        union(p, 4.6f, 11.8f, 8.0f, 19.6f, 1.7f);     // thumb
        union(p, 6.4f, 10.8f, 17.8f, 22.4f, 3.2f);    // palm
        s.fills.add(p);
        s.hotX = 9.1f;
        s.hotY = 1.4f;
    }

    /** Draggable: an open hand, fingers apart. */
    private static void openHand(Shape s) {
        Path p = new Path();
        union(p, 6.6f, 4.6f, 9.7f, 14.6f, 1.55f);
        union(p, 9.7f, 3.2f, 12.8f, 14.6f, 1.55f);
        union(p, 12.8f, 4.0f, 15.9f, 14.6f, 1.55f);
        union(p, 15.9f, 6.0f, 18.8f, 14.6f, 1.45f);
        union(p, 3.4f, 10.2f, 6.8f, 17.8f, 1.7f);     // thumb
        union(p, 5.4f, 10.6f, 19.0f, 21.6f, 3.4f);    // palm
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    /** Dragging: the same hand closed. */
    private static void fist(Shape s) {
        Path p = new Path();
        union(p, 6.8f, 6.6f, 9.7f, 11.6f, 1.4f);
        union(p, 9.7f, 5.8f, 12.8f, 11.6f, 1.4f);
        union(p, 12.8f, 6.2f, 15.9f, 11.6f, 1.4f);
        union(p, 15.9f, 7.4f, 18.6f, 11.6f, 1.3f);
        union(p, 3.8f, 11.0f, 7.2f, 16.6f, 1.7f);     // thumb, tucked
        union(p, 5.2f, 8.6f, 19.0f, 21.0f, 3.6f);     // fist
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    /** The I-beam, upright or laid on its side. */
    private static void beam(Shape s, boolean vertical) {
        Path p = new Path();
        union(p, 11.05f, 2.6f, 12.95f, 21.4f, 0.5f);   // stem
        union(p, 8.0f, 2.4f, 16.0f, 4.0f, 0.5f);       // top serif
        union(p, 8.0f, 20.0f, 16.0f, 21.6f, 0.5f);     // bottom serif
        if (vertical) rotate(p, 90f);
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    /** Four ways at once — the move pointer. */
    private static void move(Shape s) {
        Path p = new Path();
        union(p, 10.55f, 4.6f, 13.45f, 19.4f, 0.4f);
        union(p, 4.6f, 10.55f, 19.4f, 13.45f, 0.4f);
        head(p, 0f);
        head(p, 180f);
        head(p, 270f);
        head(p, 90f);
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    /** The resize family: one bar, two heads, spun to taste. */
    private static void doubleArrow(Shape s, float degrees) {
        Path p = new Path();
        union(p, 4.4f, 10.55f, 19.6f, 13.45f, 0.4f);
        head(p, 270f);
        head(p, 90f);
        if (degrees != 0f) rotate(p, degrees);
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    private static void crosshair(Shape s) {
        Path p = new Path();
        union(p, 11.1f, 1.4f, 12.9f, 9.6f, 0.4f);
        union(p, 11.1f, 14.4f, 12.9f, 22.6f, 0.4f);
        union(p, 1.4f, 11.1f, 9.6f, 12.9f, 0.4f);
        union(p, 14.4f, 11.1f, 22.6f, 12.9f, 0.4f);
        p.addCircle(12f, 12f, 1.15f, Path.Direction.CW);
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    private static void cell(Shape s) {
        Path p = new Path();
        // Four stubs rather than two crossing bars: run them through and the
        // square fills with a lattice that reads as a grid, not as one cell.
        union(p, 11.1f, 1.2f, 12.9f, 6.2f, 0.4f);
        union(p, 11.1f, 17.8f, 12.9f, 22.8f, 0.4f);
        union(p, 1.2f, 11.1f, 6.2f, 12.9f, 0.4f);
        union(p, 17.8f, 11.1f, 22.8f, 12.9f, 0.4f);
        // and the hollow square that makes it a cell rather than a crosshair
        union(p, 6.2f, 6.2f, 17.8f, 7.6f, 0.3f);
        union(p, 6.2f, 16.4f, 17.8f, 17.8f, 0.3f);
        union(p, 6.2f, 6.2f, 7.6f, 17.8f, 0.3f);
        union(p, 16.4f, 6.2f, 17.8f, 17.8f, 0.3f);
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    private static void hourglass(Shape s) {
        Path p = new Path();
        union(p, 5.6f, 2.0f, 18.4f, 4.0f, 0.7f);       // top plate
        union(p, 5.6f, 20.0f, 18.4f, 22.0f, 0.7f);     // bottom plate
        Path body = new Path();
        body.moveTo(7.4f, 4.0f);
        body.lineTo(16.6f, 4.0f);
        body.lineTo(12.9f, 11.6f);
        body.lineTo(16.6f, 20.0f);
        body.lineTo(7.4f, 20.0f);
        body.lineTo(11.1f, 11.6f);
        body.close();
        p.op(body, Path.Op.UNION);
        s.fills.add(p);
        s.hotX = 12f;
        s.hotY = 12f;
    }

    /** Zoom: a lens, a handle, and a sign. {@code sign} 1 = in, -1 = out. */
    private static void magnifier(Shape s, int sign) {
        s.lineWidth = 2.4f;
        Path lens = new Path();
        lens.addCircle(10f, 10f, 6.6f, Path.Direction.CW);
        s.strokes.add(lens);

        Path handle = new Path();
        handle.moveTo(14.9f, 14.9f);
        handle.lineTo(21.6f, 21.6f);
        s.strokes.add(handle);

        Path glyph = new Path();
        glyph.moveTo(6.6f, 10f);
        glyph.lineTo(13.4f, 10f);
        if (sign > 0) {
            glyph.moveTo(10f, 6.6f);
            glyph.lineTo(10f, 13.4f);
        }
        s.strokes.add(glyph);
        s.hotX = 10f;
        s.hotY = 10f;
    }

    // ── badges: the arrow, plus a word about what the click will do ──

    private static void noDropBadge(Shape s) {
        s.lineWidth = 2.2f;
        Path ring = new Path();
        ring.addCircle(16.4f, 16.4f, 6.0f, Path.Direction.CW);
        s.strokes.add(ring);
        Path slash = new Path();
        slash.moveTo(12.6f, 12.6f);
        slash.lineTo(20.2f, 20.2f);
        s.strokes.add(slash);
    }

    private static void helpBadge(Shape s) {
        s.lineWidth = 2.0f;
        // A question mark drawn as geometry rather than as text, so it does not
        // change shape with whatever font the device happens to ship.
        //
        // One contour: the arc leaves its end point as the current position, so
        // the curve that brings the hook back down into the stem continues from
        // it. Two separate paths left the stem floating in mid-air under an arc
        // that stopped out to the right of it.
        Path mark = new Path();
        mark.addArc(new RectF(13.2f, 10.6f, 20.2f, 17.6f), 170f, 195f);
        mark.quadTo(20.9f, 17.4f, 16.9f, 18.4f);
        mark.lineTo(16.9f, 19.7f);
        s.strokes.add(mark);
        Path dot = new Path();
        dot.addCircle(16.9f, 22.1f, 1.15f, Path.Direction.CW);
        s.fills.add(dot);
    }

    private static void menuBadge(Shape s) {
        Path p = new Path();
        union(p, 12.4f, 12.4f, 23.4f, 23.0f, 1.4f);
        s.fills.add(p);
        s.onPlate = true;
        s.lineWidth = 1.5f;
        Path lines = new Path();
        for (int i = 0; i < 3; i++) {
            float y = 15.2f + i * 2.9f;
            lines.moveTo(14.6f, y);
            lines.lineTo(21.2f, y);
        }
        s.strokes.add(lines);
    }

    /** A small sheet with a sign on it. {@code plus} 1 = copy, 0 = alias. */
    private static void boxBadge(Shape s, int plus) {
        Path p = new Path();
        union(p, 12.4f, 12.4f, 23.4f, 23.0f, 1.4f);
        s.fills.add(p);
        s.onPlate = true;
        s.lineWidth = 1.9f;
        Path glyph = new Path();
        if (plus == 1) {
            glyph.moveTo(14.8f, 17.7f);
            glyph.lineTo(21.0f, 17.7f);
            glyph.moveTo(17.9f, 14.6f);
            glyph.lineTo(17.9f, 20.8f);
        } else {
            // a shortcut's turned arrow
            glyph.moveTo(15.0f, 20.6f);
            glyph.lineTo(20.6f, 15.0f);
            glyph.moveTo(16.6f, 15.0f);
            glyph.lineTo(20.6f, 15.0f);
            glyph.lineTo(20.6f, 19.0f);
        }
        s.strokes.add(glyph);
    }

    // ── path helpers ──

    /** Add a rounded rect to {@code into}, merged rather than stacked. */
    private static void union(Path into, float l, float t, float r, float b, float radius) {
        Path part = new Path();
        part.addRoundRect(new RectF(l, t, r, b), radius, radius, Path.Direction.CW);
        into.op(part, Path.Op.UNION);
    }

    /**
     * An arrowhead on the rim of the box, pointing outwards.
     *
     * Always drawn at the top and then rotated, so the move pointer's four
     * heads and the resize family's two are the same three lines each time.
     * {@code degrees} is clockwise from "points up".
     */
    private static void head(Path into, float degrees) {
        Path part = new Path();
        part.moveTo(12f, 0.9f);
        part.lineTo(7.7f, 5.9f);
        part.lineTo(16.3f, 5.9f);
        part.close();
        Matrix m = new Matrix();
        m.setRotate(degrees, 12f, 12f);
        part.transform(m);
        into.op(part, Path.Op.UNION);
    }

    private static void rotate(Path p, float degrees) {
        Matrix m = new Matrix();
        m.setRotate(degrees, 12f, 12f);
        p.transform(m);
    }

    // ── previews ──

    /**
     * The same render the pointer gets, as a bitmap the Settings window can
     * put in an ImageView.
     *
     * Deliberately the same code path, with the same overrides the pickers
     * need: a preview drawn by a second routine is a preview that will
     * eventually disagree with the pointer it is previewing. {@code px} is the
     * bitmap the caller has room for, NOT the size setting — a picker showing
     * eight styles wants them all the same size, and the size card wants one
     * that grows with the slider.
     */
    static Bitmap preview(Context ctx, int role, String styleOverride, int px) {
        return paintPreview(ctx, role, styleOverride, null, px);
    }

    /** The arrow in one candidate hue, for the colour picker's own tiles. */
    static Bitmap previewIn(Context ctx, String colourOverride, int px) {
        return paintPreview(ctx, ROLE_ARROW, null, colourOverride, px);
    }

    private static Bitmap paintPreview(Context ctx, int role, String styleOverride,
                                       String colourOverride, int px) {
        String style = styleOverride == null ? style(ctx) : styleOverride;
        if (STYLE_SYSTEM.equals(style) || role == ROLE_NONE) return null;

        DexTheme theme = DexTheme.of(ctx);
        Look look = look(style);
        int fill = hue(colourOverride != null ? colourOverride
                : DexPrefs.getString(ctx, DexPrefs.KEY_CURSOR_COLOR, DexPrefs.DEF_CURSOR_COLOR),
                theme);
        int line = outline(DexPrefs.getString(ctx, DexPrefs.KEY_CURSOR_OUTLINE,
                DexPrefs.DEF_CURSOR_OUTLINE), fill);
        boolean shadow = look.shadow
                || DexPrefs.getBool(ctx, DexPrefs.KEY_CURSOR_SHADOW, DexPrefs.DEF_CURSOR_SHADOW);

        Shape shape = shape(role, look);
        float pad = look.stroke / 2f + (shadow ? 2.2f : 0.35f);
        float box = BOX + pad * 2f;
        int side = Math.max(8, px);
        try {
            Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            if (look.quantise > 0) {
                int small = Math.max(look.quantise, Math.round(side / 4f));
                Bitmap tiny = Bitmap.createBitmap(small, small, Bitmap.Config.ARGB_8888);
                paint(new Canvas(tiny), shape, look, fill, line, shadow, small / box, pad);
                Paint blit = new Paint();
                blit.setFilterBitmap(false);
                new Canvas(bitmap).drawBitmap(tiny, null, new RectF(0f, 0f, side, side), blit);
                tiny.recycle();
            } else {
                paint(new Canvas(bitmap), shape, look, fill, line, shadow, side / box, pad);
            }
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }
}
