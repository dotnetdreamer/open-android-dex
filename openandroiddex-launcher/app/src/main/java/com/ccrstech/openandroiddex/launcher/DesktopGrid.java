package com.ccrstech.openandroiddex.launcher;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The desktop surface: a snapping icon grid, DeX/Windows style.
 *
 * Cells are derived from the available space (a target cell size, then
 * stretched so columns and rows exactly fill the surface), so the layout
 * survives a display-size change — {@link #reflow()} re-homes anything that
 * falls outside the new grid instead of losing it.
 *
 * Icons are placed by dragging one out of the app drawer (see
 * LauncherActivity's drag layer, which drops onto {@link #dropApp}) and moved
 * by long-pressing them here: the tile follows the pointer, the cell under it
 * lights up, and on release it snaps home with an overshoot. Dropping onto an
 * occupied cell swaps the two icons; dropping onto the "Remove" pill that
 * appears while dragging takes the icon off the desktop.
 *
 * Placement is persisted as `component:col:row|…` so the desktop comes back
 * exactly as it was left.
 *
 * The desktop also hosts real Android home-screen widgets (AppWidgetHost),
 * DeX/Windows style: a widget occupies a spanW×spanH block of the same cells
 * the icons snap to, moves by the same hold-and-drag, and is resized from a
 * handle frame its right-click menu opens. Records live under
 * {@link #KEY_WIDGETS} as `appWidgetId:col:row:spanW:spanH|…` — the provider
 * is NOT stored because the id alone recovers it from AppWidgetManager, and
 * an id whose provider is gone (app uninstalled) is released on reload.
 */
class DesktopGrid extends ViewGroup {

    /** Persisted placement, in LauncherActivity.PREFS. */
    static final String KEY_ITEMS = "desktop_icons";
    /** Persisted widget placement — see the class doc for the format. */
    static final String KEY_WIDGETS = "desktop_widgets";

    private static final Interpolator SNAP = new OvershootInterpolator(1.4f);
    private static final int SETTLE_MS = 220;

    /** One placed shortcut: which app, which cell, and the tile showing it. */
    static final class Item {
        final LauncherActivity.AppEntry app;
        int col, row;
        View view;

        Item(LauncherActivity.AppEntry app, int col, int row) {
            this.app = app;
            this.col = col;
            this.row = row;
        }
    }

    /** One hosted widget: which allocation, which cells, and the view showing it. */
    static final class WidgetItem {
        final int appWidgetId;
        int col, row;
        /** Block actually laid out on THIS grid — base, clamped by reflow. */
        int spanW, spanH;
        /**
         * The size the user chose, and what is persisted. Kept apart from the
         * effective span so a round trip through a denser display-size preset
         * (fewer columns → clamp) gives the widget its real size back instead
         * of quietly rewriting the record with the shrunken one.
         */
        int baseSpanW, baseSpanH;
        AppWidgetProviderInfo info;
        AppWidgetHostView view;
        /**
         * Last size (DP) pushed through updateAppWidgetSize — a binder call, so
         * it is only repeated when the size actually changed.
         *
         * Dp and not px, which is what the provider is actually told: a
         * `wm density` change moves the dp while the span's pixel size stands
         * perfectly still, and a px-keyed cache swallows exactly that update —
         * leaving the provider laying out for the old scale with nothing to
         * correct it.
         */
        int sizedW, sizedH;

        WidgetItem(int appWidgetId, int col, int row, int spanW, int spanH) {
            this.appWidgetId = appWidgetId;
            this.col = col;
            this.row = row;
            this.spanW = spanW;
            this.spanH = spanH;
            this.baseSpanW = spanW;
            this.baseSpanH = spanH;
        }
    }

    private final LauncherActivity host;
    private final List<Item> items = new ArrayList<>();
    private final List<WidgetItem> widgets = new ArrayList<>();
    /**
     * Views whose cell just changed: onLayout puts them at the new cell, then
     * back-translates them to where they visually were and animates that away
     * — the snap. Keyed by view because bringToFront reorders children.
     */
    private final Map<View, float[]> settling = new HashMap<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect tmpRect = new Rect();
    private final RectF tmpF = new RectF();
    private final int[] loc = new int[2];

    /** Target cell size before stretching — the grid pitch users perceive. */
    private final int wantCellW;
    private final int wantCellH;
    private int cellW, cellH;
    private int cols = 1, rows = 1;

    // ── drag state (moving an icon or a widget already on the desktop) ──
    private Item dragItem;
    private WidgetItem dragWidget;
    private float dragStartRawX, dragStartRawY;
    private float lastRawX, lastRawY;
    /** False until a pointer has actually been somewhere — see showMenu's fallback. */
    private boolean pointerSeen;
    private int targetCol = -1, targetRow = -1;
    /** Cells the drop highlight covers — 1×1 for icons, the span for widgets. */
    private int targetSpanW = 1, targetSpanH = 1;
    private boolean overTrash;

    // ── resize state (a widget wearing its handle frame) ──
    /** Widget in resize mode (frame + handles drawn over it), or null. */
    private WidgetItem resizeItem;
    /** Edge being dragged: 0 none, 1 left, 2 top, 3 right, 4 bottom. */
    private int resizeEdge;
    /** The frame's live geometry while a handle is held, in cells. */
    private int pendCol, pendRow, pendSpanW, pendSpanH;

    /** The open right-click menu, or null. One at a time. */
    private PopupWindow menu;

    DesktopGrid(LauncherActivity host) {
        super(host);
        this.host = host;
        // the cell follows the icon-size setting, so "Large" gets more room
        // rather than just a bigger icon crammed into the same pitch
        this.wantCellW = host.dp(host.iconDp() + 46);
        this.wantCellH = host.dp(host.iconDp() + 54);
        setWillNotDraw(false);      // onDraw paints the grid guides / drop hints
        setClipChildren(false);     // a lifted tile is scaled up past its cell
        setClipToPadding(false);
        paint.setTextAlign(Paint.Align.CENTER);
        // Empty-desktop gestures: holding the wallpaper opens the same menu a
        // right-click does. Clickable is what makes the grid own a gesture no
        // tile claimed — without it the long press never fires.
        setClickable(true);
        setOnLongClickListener(v -> {
            if (dragItem != null || dragWidget != null || resizeItem != null) return false;
            showDesktopMenu();
            return true;
        });
    }

    // ── geometry ──

    int columns() {
        return cols;
    }

    /** True once onMeasure has produced real cells — cols/rows START at 1. */
    boolean measured() {
        return cellW > 0 && cellH > 0;
    }

    int rowCount() {
        return rows;
    }

    /** Cell containing a screen point, or null when it is off the grid. */
    int[] cellAtScreen(float rawX, float rawY) {
        if (cellW <= 0 || cellH <= 0 || getWidth() == 0) return null;
        getLocationOnScreen(loc);
        int col = colAt(rawX - loc[0]);
        int row = rowAt(rawY - loc[1]);
        return (col < 0 || row < 0) ? null : new int[]{col, row};
    }

    /** Screen bounds of a cell — the drawer's drag layer highlights with this. */
    void cellRectOnScreen(int col, int row, Rect out) {
        getLocationOnScreen(loc);
        int left = loc[0] + getPaddingLeft() + col * cellW;
        int top = loc[1] + getPaddingTop() + row * cellH;
        out.set(left, top, left + cellW, top + cellH);
    }

    private int colAt(float x) {
        if (cellW <= 0) return -1;
        int col = (int) Math.floor((x - getPaddingLeft()) / (float) cellW);
        return (col < 0 || col >= cols) ? -1 : col;
    }

    private int rowAt(float y) {
        if (cellH <= 0) return -1;
        int row = (int) Math.floor((y - getPaddingTop()) / (float) cellH);
        return (row < 0 || row >= rows) ? -1 : row;
    }

    private void cellBoundsLocal(int col, int row, RectF out, float inset) {
        float left = getPaddingLeft() + col * cellW;
        float top = getPaddingTop() + row * cellH;
        out.set(left + inset, top + inset, left + cellW - inset, top + cellH - inset);
    }

    /** The "Remove" drop pill, shown in the strip above the first row. */
    private void trashRect(Rect out) {
        int w = Math.max(host.dp(160), Math.min(host.dp(240), getWidth() / 3));
        int h = host.dp(38);
        int cx = getWidth() / 2;
        int top = host.dp(8);
        out.set(cx - w / 2, top, cx + w / 2, top + h);
    }

    // ── model ──

    private Item itemAt(int col, int row) {
        for (Item it : items) {
            if (it.col == col && it.row == row) return it;
        }
        return null;
    }

    private Item itemFor(ComponentName component) {
        for (Item it : items) {
            if (it.app.component.getPackageName().equals(component.getPackageName())) return it;
        }
        return null;
    }

    /** The widget whose span covers (col,row), or null. */
    private WidgetItem widgetAt(int col, int row) {
        for (WidgetItem w : widgets) {
            if (col >= w.col && col < w.col + w.spanW
                    && row >= w.row && row < w.row + w.spanH) return w;
        }
        return null;
    }

    /** All cells of a spanW×spanH block at (col,row) in bounds and unused. */
    private boolean regionFree(boolean[] used, int col, int row, int spanW, int spanH) {
        if (col < 0 || row < 0 || col + spanW > cols || row + spanH > rows) return false;
        for (int r = row; r < row + spanH; r++) {
            for (int c = col; c < col + spanW; c++) {
                if (used[r * cols + c]) return false;
            }
        }
        return true;
    }

    private void markRegion(boolean[] used, int col, int row, int spanW, int spanH) {
        for (int r = Math.max(0, row); r < Math.min(rows, row + spanH); r++) {
            for (int c = Math.max(0, col); c < Math.min(cols, col + spanW); c++) {
                used[r * cols + c] = true;
            }
        }
    }

    /**
     * Free spanW×spanH block closest to (prefCol,prefRow), by squared distance
     * of top-left corners, or null when nothing that big is free.
     */
    private int[] findSlot(boolean[] used, int spanW, int spanH, int prefCol, int prefRow) {
        int bestC = -1, bestR = -1;
        long bestDist = Long.MAX_VALUE;
        for (int r = 0; r + spanH <= rows; r++) {
            for (int c = 0; c + spanW <= cols; c++) {
                if (!regionFree(used, c, r, spanW, spanH)) continue;
                long dc = c - prefCol, dr = r - prefRow;
                long dist = dc * dc + dr * dr;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestC = c;
                    bestR = r;
                }
            }
        }
        return bestC < 0 ? null : new int[]{bestC, bestR};
    }

    /**
     * Provider dimension (px) → cells. WHOSE px changed in Android 12: since
     * S, AppWidgetManager re-resolves the provider's dp values against the
     * metrics of the context it was created from — our activity on THIS
     * display — so they arrive already in desktop px. Before S they were
     * resolved system-side at the PHONE's density, which has to be undone
     * before our own is applied; running that undo on S+ would divide by the
     * phone's ~3x scalar a second time and shrink every widget to 1×1.
     */
    private int spanFromProviderPx(int providerPx, int cellPx) {
        if (providerPx <= 0 || cellPx <= 0) return 1;
        float px;
        if (Build.VERSION.SDK_INT >= 31) {
            px = providerPx;
        } else {
            float sysDensity = android.content.res.Resources.getSystem()
                    .getDisplayMetrics().density;
            px = host.dp(providerPx / Math.max(0.5f, sysDensity));
        }
        return Math.max(1, (int) Math.ceil(px / (float) cellPx));
    }

    /** Default span for a provider, clamped so it always fits SOME desktop. */
    int[] spanFor(AppWidgetProviderInfo info) {
        int sw = Math.min(Math.max(1, cols), spanFromProviderPx(info.minWidth, cellW));
        int sh = Math.min(Math.max(1, rows), spanFromProviderPx(info.minHeight, cellH));
        return new int[]{sw, sh};
    }

    /** Smallest span resize may shrink to, from the provider's resize floor. */
    private int[] minSpanFor(AppWidgetProviderInfo info) {
        int sw = Math.max(1, spanFromProviderPx(info.minResizeWidth, cellW));
        int sh = Math.max(1, spanFromProviderPx(info.minResizeHeight, cellH));
        // a floor above the default would make the default itself illegal
        int[] def = spanFor(info);
        return new int[]{Math.min(sw, def[0]), Math.min(sh, def[1])};
    }

    /** Largest span resize may grow to — provider cap (API 31) or the grid. */
    private int[] maxSpanFor(AppWidgetProviderInfo info) {
        int sw = cols, sh = rows;
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.maxResizeWidth > 0) {
                sw = Math.min(sw, spanFromProviderPx(info.maxResizeWidth, cellW));
            }
            if (info.maxResizeHeight > 0) {
                sh = Math.min(sh, spanFromProviderPx(info.maxResizeHeight, cellH));
            }
        }
        return new int[]{Math.max(1, sw), Math.max(1, sh)};
    }

    /**
     * Free cell closest to (col,row), or -1 when the grid is full. Distance is
     * squared euclidean so a full row pushes sideways before dropping down.
     */
    private int nearestFree(boolean[] used, int col, int row) {
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (used[idx]) continue;
                long dc = c - col, dr = r - row;
                long dist = dc * dc + dr * dr;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = idx;
                }
            }
        }
        return best;
    }

    private boolean[] occupancy(Item ignore) {
        return occupancy(ignore, null);
    }

    /**
     * Re-home anything the current grid can no longer show where it was: the
     * display got smaller/denser, or two records collided. Runs from onMeasure,
     * the first point at which the column count is known. Widgets claim their
     * blocks first — they are the least movable — and icons fill in around
     * them.
     */
    private void reflow() {
        boolean[] used = new boolean[Math.max(1, cols * rows)];
        boolean widgetsMoved = false;
        List<WidgetItem> homelessW = new ArrayList<>();
        for (WidgetItem w : widgets) {
            // effective span at THIS grid, never persisted — coming back to a
            // roomier display restores the size the user actually chose
            w.spanW = Math.min(w.baseSpanW, cols);
            w.spanH = Math.min(w.baseSpanH, rows);
            if (!regionFree(used, w.col, w.row, w.spanW, w.spanH)) {
                homelessW.add(w);
                continue;
            }
            markRegion(used, w.col, w.row, w.spanW, w.spanH);
        }
        for (WidgetItem w : homelessW) {
            int col = Math.max(0, Math.min(w.col, cols - w.spanW));
            int row = Math.max(0, Math.min(w.row, rows - w.spanH));
            int[] slot = findSlot(used, w.spanW, w.spanH, col, row);
            if (slot == null) {
                // grid full — park it on the clamped block rather than lose it
                w.col = col;
                w.row = row;
            } else {
                w.col = slot[0];
                w.row = slot[1];
            }
            markRegion(used, w.col, w.row, w.spanW, w.spanH);
            widgetsMoved = true;
        }
        if (widgetsMoved) saveWidgets();

        List<Item> homeless = new ArrayList<>();
        for (Item it : items) {
            if (it.col < 0 || it.row < 0 || it.col >= cols || it.row >= rows) {
                homeless.add(it);
                continue;
            }
            int idx = it.row * cols + it.col;
            if (used[idx]) {
                homeless.add(it);
                continue;
            }
            used[idx] = true;
        }
        if (homeless.isEmpty()) return;
        for (Item it : homeless) {
            int col = Math.max(0, Math.min(it.col, cols - 1));
            int row = Math.max(0, Math.min(it.row, rows - 1));
            int slot = nearestFree(used, col, row);
            if (slot < 0) {
                // grid full — park it on the clamped cell rather than lose it
                it.col = col;
                it.row = row;
                continue;
            }
            used[slot] = true;
            it.col = slot % cols;
            it.row = slot / cols;
        }
        save();
    }

    // ── persistence ──

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append('|');
            sb.append(it.app.component.flattenToString())
                    .append(':').append(it.col)
                    .append(':').append(it.row);
        }
        host.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, sb.toString()).apply();
    }

    private void saveWidgets() {
        StringBuilder sb = new StringBuilder();
        for (WidgetItem w : widgets) {
            if (sb.length() > 0) sb.append('|');
            sb.append(w.appWidgetId)
                    .append(':').append(w.col)
                    .append(':').append(w.row)
                    .append(':').append(w.baseSpanW)
                    .append(':').append(w.baseSpanH);
        }
        host.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_WIDGETS, sb.toString()).apply();
    }

    /**
     * Rebuild every tile from the stored placement. Called once the app list is
     * known (components are resolved against it) and again after a density
     * rebuild, which throws the whole view tree away.
     */
    void reload() {
        cancelDrag();
        exitResize();
        // the tiles the menu points at are about to be thrown away
        dismissMenu();
        removeAllViews();
        items.clear();
        widgets.clear();
        settling.clear();
        loadWidgets();
        String stored = host.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, "");
        for (String record : stored.split("\\|")) {
            if (record.isEmpty()) continue;
            int rowSep = record.lastIndexOf(':');
            if (rowSep <= 0) continue;
            int colSep = record.lastIndexOf(':', rowSep - 1);
            if (colSep <= 0) continue;
            ComponentName component = ComponentName.unflattenFromString(record.substring(0, colSep));
            if (component == null) continue;
            int col, row;
            try {
                col = Integer.parseInt(record.substring(colSep + 1, rowSep));
                row = Integer.parseInt(record.substring(rowSep + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            // uninstalled (or not yet visible) apps are skipped, not dropped —
            // the record survives until something else rewrites the list
            LauncherActivity.AppEntry app = host.findByComponent(component);
            if (app == null) continue;
            addItem(new Item(app, col, row));
        }
        requestLayout();
        invalidate();
    }

    private void addItem(Item it) {
        it.view = createTile(it);
        items.add(it);
        addView(it.view);
    }

    // ── widgets: load, attach, add, remove ──

    /**
     * Rebuild the widget views from the stored records. Ids whose provider is
     * gone (app uninstalled, or the phone forgot the allocation) are released
     * and their record dropped — unlike an icon, a widget id has server-side
     * state that would otherwise leak.
     */
    private void loadWidgets() {
        String stored = host.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_WIDGETS, "");
        boolean dirty = false;
        for (String record : stored.split("\\|")) {
            if (record.isEmpty()) continue;
            String[] parts = record.split(":");
            if (parts.length != 5) {
                dirty = true;
                continue;
            }
            WidgetItem w;
            try {
                w = new WidgetItem(Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Math.max(1, Integer.parseInt(parts[3])),
                        Math.max(1, Integer.parseInt(parts[4])));
            } catch (NumberFormatException e) {
                dirty = true;
                continue;
            }
            w.info = host.widgetManager().getAppWidgetInfo(w.appWidgetId);
            if (w.info == null) {
                try {
                    host.widgetHost().deleteAppWidgetId(w.appWidgetId);
                } catch (Exception ignored) {
                }
                dirty = true;
                continue;
            }
            if (!attachWidget(w)) {
                // the record is being dropped, so the allocation must go with
                // it — an id with no record can never be cleaned up later
                try {
                    host.widgetHost().deleteAppWidgetId(w.appWidgetId);
                } catch (Exception ignored) {
                }
                dirty = true;
                continue;
            }
            widgets.add(w);
        }
        if (dirty) saveWidgets();
    }

    /** Build the live host view for a record and wire the desktop gestures. */
    private boolean attachWidget(WidgetItem w) {
        AppWidgetHostView view;
        try {
            view = host.widgetHost().createView(host, w.appWidgetId, w.info);
        } catch (Exception e) {
            DexLog.warn("widgets", "cannot inflate widget " + w.appWidgetId
                    + " (" + w.info.provider.flattenToShortString() + ")", e);
            return false;
        }
        w.view = view;
        view.setTag(w);
        if (view instanceof WidgetHostView) {
            ((WidgetHostView) view).setCallbacks(new WidgetHostView.Callbacks() {
                @Override
                public void onWidgetLongPress(WidgetHostView v) {
                    beginWidgetDrag(w);
                }

                @Override
                public void onWidgetMenu(WidgetHostView v) {
                    showWidgetMenu(w);
                }

                @Override
                public void onWidgetDefaultClick(WidgetHostView v, View tapped) {
                    host.launchWidgetApp(w.info, tapped);
                }
            });
        }
        addView(view);
        return true;
    }

    /**
     * Place a freshly bound-and-configured widget near (prefCol,prefRow) —
     * the cell the desktop menu was opened on. False when no free block that
     * size exists; the caller releases the id.
     */
    boolean addWidget(int appWidgetId, AppWidgetProviderInfo info, int prefCol, int prefRow) {
        if (cols <= 0 || rows <= 0) return false;
        int[] span = spanFor(info);
        prefCol = Math.max(0, Math.min(prefCol, cols - span[0]));
        prefRow = Math.max(0, Math.min(prefRow, rows - span[1]));
        int[] slot = findSlot(occupancy(null), span[0], span[1], prefCol, prefRow);
        if (slot == null) return false;
        WidgetItem w = new WidgetItem(appWidgetId, slot[0], slot[1], span[0], span[1]);
        w.info = info;
        if (!attachWidget(w)) return false;
        widgets.add(w);
        // pop in where it landed instead of blinking into existence
        w.view.setAlpha(0f);
        w.view.setScaleX(0.85f);
        w.view.setScaleY(0.85f);
        w.view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(SETTLE_MS).setInterpolator(SNAP).start();
        saveWidgets();
        requestLayout();
        return true;
    }

    /** Take a widget off the desktop and release its id. */
    private void removeWidget(WidgetItem w) {
        if (resizeItem == w) exitResize();
        widgets.remove(w);
        try {
            host.widgetHost().deleteAppWidgetId(w.appWidgetId);
        } catch (Exception ignored) {
        }
        View v = w.view;
        v.animate().cancel();
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150)
                .withEndAction(() -> removeView(v)).start();
        saveWidgets();
        invalidate();
    }

    /**
     * Tell the provider how big its window is, in dp of THIS display, so it
     * picks the right layout. Skipped when unchanged: it is a binder call and
     * this runs from onMeasure.
     */
    private void pushWidgetSize(WidgetItem w) {
        int wPx = w.spanW * cellW;
        int hPx = w.spanH * cellH;
        if (wPx <= 0 || hPx <= 0) return;
        int density = Math.max(1, host.uiDensity());
        float wDp = wPx * 160f / density;
        float hDp = hPx * 160f / density;
        // Whole dp is a fine cache key — a sub-dp wobble is not worth a binder
        // call — but the size itself is handed over UNROUNDED below, because
        // the provider's size buckets are compared against it.
        if (w.sizedW == Math.round(wDp) && w.sizedH == Math.round(hDp)) return;
        w.sizedW = Math.round(wDp);
        w.sizedH = Math.round(hDp);
        try {
            applyWidgetSize(w.view, wDp, hDp);
        } catch (Exception ignored) {
        }
    }

    /**
     * Hand the provider its size the way a modern widget expects to be told.
     *
     * The deprecated four-int overload sets only OPTION_APPWIDGET_MIN/MAX_*
     * and leaves OPTION_APPWIDGET_SIZES **empty** — and a responsive widget
     * chooses its layout from that list. Glance-based providers (every One UI
     * "Integrated" widget is one) read it, find nothing, log
     * `mode=unknown from options`, and throw NoSuchElementException out of
     * recomposition; the provider then answers the host with null RemoteViews,
     * which is what the desktop drew as **"Can't show content"**.
     *
     * Measured on an S25 against One UI Home hosting the very same provider —
     * the two hosts side by side are the whole argument:
     *
     *   ours  w=273.0     h=188.0       → "mode=unknown from options" → throws
     *   home  w=124.05286 h=178.32599   → "mode=medium from options"  → draws
     *
     * Note which one is bigger. 273×188 dp is ample, so size was never what
     * this was about; the fractions are the tell, because they can only have
     * come from a SizeF list. Ours were whole numbers because they were being
     * read back off the integer MIN/MAX options, the only ones we set.
     *
     * Passed UNROUNDED for the same reason: the provider compares the value
     * against its own size buckets, so rounding a cell across a bucket edge
     * would pick the wrong layout. One size and not several — a desktop cell
     * is a single exact rect, unlike a phone home screen, which has to offer
     * both portrait and landscape.
     */
    private static void applyWidgetSize(AppWidgetHostView view, float wDp, float hDp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(new Bundle(),
                    Collections.singletonList(new SizeF(wDp, hDp)));
            return;
        }
        int w = Math.round(wDp);
        int h = Math.round(hDp);
        view.updateAppWidgetSize(null, w, h, w, h);
    }

    // ── tiles ──

    private View createTile(Item it) {
        LinearLayout tile = host.newIconTile(host.iconDp());
        ((ImageView) tile.getChildAt(0)).setImageDrawable(it.app.icon);
        TextView label = (TextView) tile.getChildAt(1);
        label.setText(it.app.label);
        // the desktop has no panel behind it — these labels sit on the
        // wallpaper, so their ink follows the wallpaper and carries a shadow
        // in the opposite direction to stay readable over its busiest parts
        boolean lightInk = host.deskLightInk();
        label.setTextColor(lightInk ? 0xFFe7ecf3 : 0xFF101828);
        label.setShadowLayer(host.dp(3), 0, host.dp(1),
                lightInk ? 0xCC000000 : 0x99FFFFFF);
        tile.setTag(it);
        tile.setOnClickListener(v -> host.launch(it.app));
        tile.setOnLongClickListener(v -> {
            beginDrag(it);
            return true;
        });
        // right-click (forwarded by scrcpy) — long-press is taken by the drag
        tile.setOnContextClickListener(v -> {
            showMenu(it);
            return true;
        });
        return tile;
    }

    /**
     * Right-click menu for a desktop icon, opened AT THE POINTER.
     *
     * It used to be a PopupMenu anchored to the tile, and a PopupMenu draws over its
     * anchor — so the icon you right-clicked vanished behind its own menu. Anchoring to
     * the pointer is both the fix and what every other desktop does; the click point ends
     * up at a CORNER of the menu, so the menu grows away from the icon.
     *
     * Near an edge it flips rather than clamps, for the same reason: clamping would slide
     * the menu back over the icon, flipping keeps the pointer on a corner.
     */
    private void showMenu(Item it) {
        LinearLayout panel = newMenuPanel();
        panel.addView(menuRow(host.getString(R.string.lx_open), () -> host.launch(it.app)),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
        panel.addView(menuRow(host.getString(R.string.lx_remove_from_desktop), () -> remove(it)),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
        showPanelAtPointer(panel, it.view);
    }

    /** Right-click menu for a widget: resize (when the provider allows), remove. */
    private void showWidgetMenu(WidgetItem w) {
        if (dragItem != null || dragWidget != null) return;
        if (!widgets.contains(w)) return;   // right-click raced a removal's fade-out
        LinearLayout panel = newMenuPanel();
        if (w.info != null
                && w.info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) {
            panel.addView(menuRow(host.getString(R.string.lx_resize), () -> enterResize(w)),
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT));
        }
        panel.addView(menuRow(host.getString(R.string.lx_remove_widget), () -> removeWidget(w)),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
        showPanelAtPointer(panel, w.view);
    }

    /** Right-click (or long-press) on the empty desktop. */
    private void showDesktopMenu() {
        int[] cell = cellAtScreen(lastRawX, lastRawY);
        final int prefCol = cell != null ? cell[0] : 0;
        final int prefRow = cell != null ? cell[1] : 0;
        LinearLayout panel = newMenuPanel();
        panel.addView(menuRow(host.getString(R.string.lx_add_widget),
                        () -> host.showWidgetPicker(prefCol, prefRow)),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
        showPanelAtPointer(panel, null);
    }

    private LinearLayout newMenuPanel() {
        dismissMenu();
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
        return panel;
    }

    /**
     * Show a menu panel AT THE POINTER — see the geometry notes on
     * {@link #showMenu}'s original PopupMenu problem in the git history: a
     * menu anchored to its view draws over the very thing that was clicked.
     */
    private void showPanelAtPointer(LinearLayout panel, View fallbackAnchor) {
        menu = new PopupWindow(panel, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        menu.setBackgroundDrawable(host.roundedFill(DexTheme.of(host).card(), 12));
        menu.setOutsideTouchable(true);          // click anywhere else to dismiss
        menu.setElevation(host.dp(12));
        DexCursors.decorate(panel);   // its own window; nothing on the grid reaches it

        int px, py;
        if (pointerSeen) {
            px = (int) lastRawX;
            py = (int) lastRawY;
        } else if (fallbackAnchor != null) {
            // context click with no pointer behind it (keyboard menu key, a11y): fall
            // back to just under the tile, which still leaves the icon uncovered
            fallbackAnchor.getLocationOnScreen(loc);
            px = loc[0] + fallbackAnchor.getWidth() / 2;
            py = loc[1] + fallbackAnchor.getHeight();
        } else {
            getLocationOnScreen(loc);
            px = loc[0] + getWidth() / 2;
            py = loc[1] + getHeight() / 2;
        }

        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        panel.measure(unspecified, unspecified);
        int w = panel.getMeasuredWidth();
        int h = panel.getMeasuredHeight();

        // Placed in screen space — that is what the pointer and the grid box are in — and
        // converted at the end, because showAtLocation offsets from the WINDOW.
        View root = getRootView();
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        getLocationOnScreen(loc);
        int maxX = rootLoc[0] + root.getWidth();
        // The taskbar floats OVER the bottom of the display, so the box the menu has to
        // stay inside is the grid's content area, not the display.
        int maxY = loc[1] + getHeight() - getPaddingBottom();

        int x = px + w > maxX ? Math.max(rootLoc[0], px - w) : px;
        int y = py + h > maxY ? Math.max(rootLoc[1], py - h) : py;
        menu.showAtLocation(this, Gravity.TOP | Gravity.START, x - rootLoc[0], y - rootLoc[1]);
    }

    private View menuRow(String text, Runnable action) {
        DexTheme theme = DexTheme.of(host);
        TextView row = new TextView(host);
        row.setText(text);
        row.setTextColor(theme.text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_PX, host.sp(13));
        row.setSingleLine(true);
        row.setPadding(host.dp(12), host.dp(8), host.dp(18), host.dp(8));
        row.setBackground(host.tapBackground(0x00000000, theme.hover, 9));
        row.setOnClickListener(v -> {
            dismissMenu();
            action.run();
        });
        return row;
    }

    private void dismissMenu() {
        if (menu == null) return;
        if (menu.isShowing()) menu.dismiss();
        menu = null;
    }

    // ── public entry point: a drop coming from the app drawer ──

    /**
     * Place an app on the desktop at (col,row), nudging to the nearest free
     * cell when that one is taken. An app already on the desktop is moved
     * rather than duplicated. False means the grid is full.
     */
    boolean dropApp(LauncherActivity.AppEntry app, int col, int row) {
        if (cols <= 0 || rows <= 0) return false;
        col = Math.max(0, Math.min(col, cols - 1));
        row = Math.max(0, Math.min(row, rows - 1));

        Item existing = itemFor(app.component);
        if (existing != null) {
            // a widget's block cannot swap with a 1×1 icon — land beside it
            if (widgetAt(col, row) != null) {
                int slot = nearestFree(occupancy(existing), col, row);
                if (slot < 0) return false;
                col = slot % cols;
                row = slot / cols;
            }
            Item other = itemAt(col, row);
            if (other != null && other != existing) {
                markSettle(other.view);
                other.col = existing.col;
                other.row = existing.row;
            }
            markSettle(existing.view);
            existing.col = col;
            existing.row = row;
            save();
            requestLayout();
            return true;
        }

        if (itemAt(col, row) != null || widgetAt(col, row) != null) {
            int slot = nearestFree(occupancy(null), col, row);
            if (slot < 0) return false;
            col = slot % cols;
            row = slot / cols;
        }
        Item it = new Item(app, col, row);
        addItem(it);
        // pop in where it landed instead of blinking into existence
        it.view.setAlpha(0f);
        it.view.setScaleX(0.65f);
        it.view.setScaleY(0.65f);
        it.view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(SETTLE_MS).setInterpolator(SNAP).start();
        save();
        requestLayout();
        return true;
    }

    private void remove(Item it) {
        items.remove(it);
        View v = it.view;
        v.animate().cancel();
        v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
                .withEndAction(() -> {
                    removeView(v);
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                }).start();
        save();
        invalidate();
    }

    // ── dragging an icon around the desktop ──

    /**
     * Hover and button events, which are NOT touch events.
     *
     * A right-click arrives as ACTION_BUTTON_PRESS on this path, and the context-click
     * callback carries no coordinates — so this is where the position the menu opens at
     * comes from. Ignored mid-drag: the drag reads the same fields from the touch stream.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (dragItem == null && dragWidget == null) {
            lastRawX = ev.getRawX();
            lastRawY = ev.getRawY();
            pointerSeen = true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /**
     * The pointer over the desktop, which means four different things
     * depending on what the grid is in the middle of.
     *
     * Resolved rather than set, because all four states live in this one view
     * and change several times inside a single gesture: an icon being dragged,
     * that icon over the trash, a widget in resize mode with the mouse near one
     * of its handles, and the ordinary case of bare wallpaper. Tiles get their
     * own hand from the tree pass, so this only answers for what is left.
     */
    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent ev, int pointerIndex) {
        Context ctx = getContext();
        if (dragItem != null || dragWidget != null) {
            return DexCursors.icon(ctx,
                    overTrash ? DexCursors.ROLE_NO_DROP : DexCursors.ROLE_GRABBING);
        }
        if (resizeItem != null) {
            getLocationOnScreen(loc);
            float x = ev.getRawX() - loc[0];
            float y = ev.getRawY() - loc[1];
            // The same grab radius the touch path uses, so the pointer promises
            // exactly the handles that will actually answer a press.
            float grab = host.dp(22);
            float[] c = new float[2];
            for (int edge = 1; edge <= 4; edge++) {
                boolean horizontal = edge == 1 || edge == 3;
                if (horizontal && !horizontalResizable(resizeItem)) continue;
                if (!horizontal && !verticalResizable(resizeItem)) continue;
                handleCenter(edge, c);
                if (Math.hypot(x - c[0], y - c[1]) <= grab) {
                    return DexCursors.icon(ctx, horizontal
                            ? DexCursors.ROLE_RESIZE_H : DexCursors.ROLE_RESIZE_V);
                }
            }
        }
        return super.onResolvePointerIcon(ev, pointerIndex);
    }

    /** Right-click on the bare wallpaper — children consume theirs first. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                && (ev.getActionButton() & MotionEvent.BUTTON_SECONDARY) != 0
                && dragItem == null && dragWidget == null && resizeItem == null) {
            showDesktopMenu();
            return true;
        }
        return super.onGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastRawX = ev.getRawX();
        lastRawY = ev.getRawY();
        pointerSeen = true;
        if (dragItem != null || dragWidget != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (dragItem != null) updateDrag();
                    else updateWidgetDrag();
                    break;
                case MotionEvent.ACTION_UP:
                    if (dragItem != null) endDrag(true);
                    else endWidgetDrag(true);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (dragItem != null) endDrag(false);
                    else endWidgetDrag(false);
                    break;
                default:
                    break;
            }
            return true;
        }
        if (resizeItem != null && onResizeTouch(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    private void beginDrag(Item it) {
        if (dragItem != null || dragWidget != null || cellW <= 0) return;
        dismissMenu();          // dragging the icon its menu belongs to
        exitResize();
        dragItem = it;
        dragStartRawX = lastRawX;
        dragStartRawY = lastRawY;
        targetCol = it.col;
        targetRow = it.row;
        targetSpanW = 1;
        targetSpanH = 1;
        overTrash = false;

        View v = it.view;
        v.animate().cancel();
        v.setElevation(host.dp(14));
        v.setScaleX(1.12f);
        v.setScaleY(1.12f);
        v.setAlpha(0.94f);
        v.bringToFront();
        // the tile is still "pressed" from the long press — let it go, and
        // stop it claiming the rest of the gesture (dispatchTouchEvent above
        // takes over from here)
        cancelChildTouch();
        invalidate();
    }

    private void cancelChildTouch() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private void updateDrag() {
        View v = dragItem.view;
        v.setTranslationX(lastRawX - dragStartRawX);
        v.setTranslationY(lastRawY - dragStartRawY);
        // snap off the tile's centre, not the grab point — the cell that lights
        // up is then the one the icon visibly covers
        float cx = v.getLeft() + v.getTranslationX() + cellW / 2f;
        float cy = v.getTop() + v.getTranslationY() + cellH / 2f;
        trashRect(tmpRect);
        overTrash = tmpRect.contains((int) cx, (int) cy);
        if (!overTrash) {
            int col = colAt(cx);
            int row = rowAt(cy);
            // dragging past the edge — or over a widget's block, which a 1×1
            // icon cannot swap with — keeps the last valid cell rather than
            // dropping the target: releasing there still does the obvious thing
            if (col >= 0 && row >= 0 && widgetAt(col, row) == null) {
                targetCol = col;
                targetRow = row;
            }
        }
        invalidate();
    }

    // ── dragging a widget around the desktop ──

    private void beginWidgetDrag(WidgetItem w) {
        if (dragItem != null || dragWidget != null || cellW <= 0) return;
        if (!widgets.contains(w)) return;   // long press raced a removal
        dismissMenu();
        exitResize();
        dragWidget = w;
        dragStartRawX = lastRawX;
        dragStartRawY = lastRawY;
        targetCol = w.col;
        targetRow = w.row;
        targetSpanW = w.spanW;
        targetSpanH = w.spanH;
        overTrash = false;

        View v = w.view;
        v.animate().cancel();
        v.setElevation(host.dp(14));
        v.setScaleX(1.03f);
        v.setScaleY(1.03f);
        v.setAlpha(0.92f);
        v.bringToFront();
        // The widget's content still thinks it owns the gesture — take it away.
        // cancelPendingInputEvents first, because a provider that armed its own
        // long-press pending intent on the same DOWN is otherwise racing us:
        // whether the drag ALSO launches the app would be a callback-queue
        // coincidence.
        v.cancelPendingInputEvents();
        cancelChildTouch();
        invalidate();
    }

    private void updateWidgetDrag() {
        WidgetItem w = dragWidget;
        View v = w.view;
        v.setTranslationX(lastRawX - dragStartRawX);
        v.setTranslationY(lastRawY - dragStartRawY);
        // the trash reads the POINTER, not the block's centre — a big widget's
        // centre can be nowhere near where the user thinks they are pointing
        getLocationOnScreen(loc);
        float px = lastRawX - loc[0];
        float py = lastRawY - loc[1];
        trashRect(tmpRect);
        overTrash = tmpRect.contains((int) px, (int) py);
        if (!overTrash) {
            // snap the block's top-left corner to the nearest cell
            float left = v.getLeft() + v.getTranslationX() - getPaddingLeft();
            float top = v.getTop() + v.getTranslationY() - getPaddingTop();
            int col = Math.max(0, Math.min(Math.round(left / cellW), cols - w.spanW));
            int row = Math.max(0, Math.min(Math.round(top / cellH), rows - w.spanH));
            // like the icon path: advance only onto blocks the release will
            // actually take, so the highlight never promises a refused drop
            if ((col != targetCol || row != targetRow)
                    && regionFree(occupancy(null, w), col, row, w.spanW, w.spanH)) {
                targetCol = col;
                targetRow = row;
            }
        }
        invalidate();
    }

    private void endWidgetDrag(boolean commit) {
        WidgetItem w = dragWidget;
        dragWidget = null;
        if (w == null) return;
        boolean trashed = commit && overTrash;
        overTrash = false;

        if (trashed) {
            targetCol = targetRow = -1;
            removeWidget(w);
            return;
        }
        if (commit && targetCol >= 0 && targetRow >= 0
                && (targetCol != w.col || targetRow != w.row)
                && regionFree(occupancy(null, w), targetCol, targetRow, w.spanW, w.spanH)) {
            w.col = targetCol;
            w.row = targetRow;
            saveWidgets();
        }
        markSettle(w.view);
        targetCol = targetRow = -1;
        requestLayout();
        invalidate();
    }

    // ── resizing a widget ──

    private void enterResize(WidgetItem w) {
        cancelDrag();
        resizeItem = w;
        resizeEdge = 0;
        pendCol = w.col;
        pendRow = w.row;
        pendSpanW = w.spanW;
        pendSpanH = w.spanH;
        w.view.bringToFront();
        invalidate();
    }

    void exitResize() {
        if (resizeItem == null) return;
        resizeItem = null;
        resizeEdge = 0;
        invalidate();
    }

    /** Centre of a resize handle, in local px. Edges: 1 L, 2 T, 3 R, 4 B. */
    private void handleCenter(int edge, float[] out) {
        float left = getPaddingLeft() + pendCol * cellW;
        float top = getPaddingTop() + pendRow * cellH;
        float right = left + pendSpanW * cellW;
        float bottom = top + pendSpanH * cellH;
        switch (edge) {
            case 1: out[0] = left;                 out[1] = (top + bottom) / 2f; break;
            case 2: out[0] = (left + right) / 2f;  out[1] = top;                 break;
            case 3: out[0] = right;                out[1] = (top + bottom) / 2f; break;
            default: out[0] = (left + right) / 2f; out[1] = bottom;              break;
        }
    }

    private boolean horizontalResizable(WidgetItem w) {
        return w.info == null
                || (w.info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
    }

    private boolean verticalResizable(WidgetItem w) {
        return w.info == null
                || (w.info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
    }

    /**
     * The whole touch story of resize mode. A DOWN on a handle starts a pull,
     * a DOWN anywhere else leaves resize mode (and is swallowed — the click
     * that dismisses a mode should not also do something). Pulls preview on
     * the frame only; the widget itself moves once, on release.
     */
    private boolean onResizeTouch(MotionEvent ev) {
        WidgetItem w = resizeItem;
        getLocationOnScreen(loc);
        float x = ev.getRawX() - loc[0];
        float y = ev.getRawY() - loc[1];
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float grab = host.dp(22);
                float[] c = new float[2];
                for (int edge = 1; edge <= 4; edge++) {
                    boolean h = edge == 1 || edge == 3;
                    if (h && !horizontalResizable(w)) continue;
                    if (!h && !verticalResizable(w)) continue;
                    handleCenter(edge, c);
                    if (Math.hypot(x - c[0], y - c[1]) <= grab) {
                        resizeEdge = edge;
                        return true;
                    }
                }
                exitResize();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (resizeEdge == 0) return true;
                updateResizePull(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                if (resizeEdge != 0) commitResize();
                resizeEdge = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                pendCol = w.col;
                pendRow = w.row;
                pendSpanW = w.spanW;
                pendSpanH = w.spanH;
                resizeEdge = 0;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** Move the pulled edge to the cell line nearest the pointer, if legal. */
    private void updateResizePull(float x, float y) {
        WidgetItem w = resizeItem;
        int[] min = minSpanFor(w.info);
        int[] max = maxSpanFor(w.info);
        int col = pendCol, row = pendRow, sw = pendSpanW, sh = pendSpanH;
        switch (resizeEdge) {
            case 1: {  // left edge — the right edge stays put
                int right = w.col + w.spanW;
                int newCol = Math.round((x - getPaddingLeft()) / cellW);
                newCol = Math.min(newCol, right - min[0]);   // keep at least the floor
                newCol = Math.max(newCol, right - max[0]);   // and at most the cap
                newCol = Math.max(0, newCol);
                col = newCol;
                sw = right - newCol;
                break;
            }
            case 3: {  // right edge
                int newRight = Math.round((x - getPaddingLeft()) / cellW);
                newRight = Math.max(w.col + min[0], Math.min(newRight, w.col + max[0]));
                newRight = Math.min(cols, newRight);
                sw = newRight - w.col;
                break;
            }
            case 2: {  // top edge — the bottom stays put
                int bottom = w.row + w.spanH;
                int newRow = Math.round((y - getPaddingTop()) / cellH);
                newRow = Math.min(newRow, bottom - min[1]);
                newRow = Math.max(bottom - max[1], newRow);
                newRow = Math.max(0, newRow);
                row = newRow;
                sh = bottom - newRow;
                break;
            }
            default: { // bottom edge
                int newBottom = Math.round((y - getPaddingTop()) / cellH);
                newBottom = Math.max(w.row + min[1], Math.min(newBottom, w.row + max[1]));
                newBottom = Math.min(rows, newBottom);
                sh = newBottom - w.row;
                break;
            }
        }
        if (sw < 1 || sh < 1) return;
        if (col == pendCol && row == pendRow && sw == pendSpanW && sh == pendSpanH) return;
        // only preview shapes the widget could actually take
        if (!regionFree(occupancy(null, w), col, row, sw, sh)) return;
        pendCol = col;
        pendRow = row;
        pendSpanW = sw;
        pendSpanH = sh;
        invalidate();
    }

    private void commitResize() {
        WidgetItem w = resizeItem;
        if (w.col == pendCol && w.row == pendRow
                && w.spanW == pendSpanW && w.spanH == pendSpanH) return;
        markSettle(w.view);
        w.col = pendCol;
        w.row = pendRow;
        w.spanW = pendSpanW;
        w.spanH = pendSpanH;
        // a resize is the user restating what size this widget SHOULD be
        w.baseSpanW = pendSpanW;
        w.baseSpanH = pendSpanH;
        saveWidgets();
        requestLayout();
        invalidate();
    }

    /** Occupancy with one widget carved out — resize/move test their own block. */
    private boolean[] occupancy(Item ignoreIcon, WidgetItem ignoreWidget) {
        boolean[] used = new boolean[Math.max(1, cols * rows)];
        for (Item it : items) {
            if (it == ignoreIcon) continue;
            if (it.col < 0 || it.row < 0 || it.col >= cols || it.row >= rows) continue;
            used[it.row * cols + it.col] = true;
        }
        for (WidgetItem w : widgets) {
            if (w == ignoreWidget) continue;
            markRegion(used, w.col, w.row, w.spanW, w.spanH);
        }
        return used;
    }

    private void endDrag(boolean commit) {
        Item it = dragItem;
        dragItem = null;
        if (it == null) return;
        boolean trashed = commit && overTrash;
        overTrash = false;

        if (trashed) {
            targetCol = targetRow = -1;
            remove(it);
            return;
        }
        if (commit && targetCol >= 0 && targetRow >= 0
                && (targetCol != it.col || targetRow != it.row)) {
            Item other = itemAt(targetCol, targetRow);
            int fromCol = it.col, fromRow = it.row;
            if (other != null) {                 // occupied → the two swap
                markSettle(other.view);
                other.col = fromCol;
                other.row = fromRow;
            }
            it.col = targetCol;
            it.row = targetRow;
            save();
        }
        markSettle(it.view);
        targetCol = targetRow = -1;
        requestLayout();
        invalidate();
    }

    private void cancelDrag() {
        View v = null;
        if (dragItem != null) {
            v = dragItem.view;
            dragItem = null;
        } else if (dragWidget != null) {
            v = dragWidget.view;
            dragWidget = null;
        }
        if (v == null) return;
        targetCol = targetRow = -1;
        overTrash = false;
        v.animate().cancel();
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        v.setElevation(0f);
    }

    /** Remember where a tile is right now so onLayout can animate it home. */
    private void markSettle(View v) {
        settling.put(v, new float[]{
                v.getLeft() + v.getTranslationX(),
                v.getTop() + v.getTranslationY()});
    }

    private void settle(View v, float fromX, float fromY) {
        v.animate().cancel();
        v.setTranslationX(fromX);
        v.setTranslationY(fromY);
        v.animate().translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(SETTLE_MS).setInterpolator(SNAP)
                .withEndAction(() -> v.setElevation(0f)).start();
    }

    // ── layout ──

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int availW = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        int availH = Math.max(1, height - getPaddingTop() - getPaddingBottom());
        cols = Math.max(1, availW / wantCellW);
        rows = Math.max(1, availH / wantCellH);
        cellW = availW / cols;
        cellH = availH / rows;
        reflow();

        int cellWSpec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY);
        int cellHSpec = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof WidgetItem) {
                WidgetItem w = (WidgetItem) tag;
                child.measure(
                        MeasureSpec.makeMeasureSpec(w.spanW * cellW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(w.spanH * cellH, MeasureSpec.EXACTLY));
                if (widgets.contains(w)) pushWidgetSize(w);
                continue;
            }
            child.measure(cellWSpec, cellHSpec);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            int spanW, spanH, col, row;
            if (tag instanceof Item) {
                Item it = (Item) tag;
                // a tile being removed is mid fade-out: leave it where it is
                if (!items.contains(it)) continue;
                col = it.col;
                row = it.row;
                spanW = spanH = 1;
            } else if (tag instanceof WidgetItem) {
                WidgetItem w = (WidgetItem) tag;
                if (!widgets.contains(w)) continue;
                col = w.col;
                row = w.row;
                spanW = w.spanW;
                spanH = w.spanH;
            } else {
                continue;
            }
            int left = getPaddingLeft() + col * cellW;
            int top = getPaddingTop() + row * cellH;
            child.layout(left, top, left + spanW * cellW, top + spanH * cellH);
            float[] from = settling.remove(child);
            if (from != null) settle(child, from[0] - left, from[1] - top);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelDrag();
        exitResize();
        // A PopupWindow left showing outlives the window token it was added with — which
        // the density rebuild throws away wholesale.
        dismissMenu();
        settling.clear();
    }

    // ── painting: grid guides, drop target, remove pill, empty hint ──

    @Override
    protected void onDraw(Canvas canvas) {
        if (dragItem != null || dragWidget != null) {
            drawGuides(canvas);
            drawTrash(canvas);
        } else if (items.isEmpty() && widgets.isEmpty()) {
            drawEmptyHint(canvas);
        }
        if (resizeItem != null) drawResizeFrame(canvas);
    }

    private void drawGuides(Canvas canvas) {
        float radius = host.dp(14);
        float inset = host.dp(5);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, host.dp(1)));
        // faint lines on the wallpaper: white would vanish on a pale one
        paint.setColor(host.deskLightInk() ? 0x12FFFFFF : 0x1A101828);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                cellBoundsLocal(col, row, tmpF, inset);
                canvas.drawRoundRect(tmpF, radius, radius, paint);
            }
        }
        if (targetCol < 0 || targetRow < 0 || overTrash) return;
        int accent = DexTheme.of(host).accent & 0x00FFFFFF;
        // the drop target: one cell for an icon, the whole block for a widget
        float left = getPaddingLeft() + targetCol * cellW;
        float top = getPaddingTop() + targetRow * cellH;
        tmpF.set(left + inset, top + inset,
                left + targetSpanW * cellW - inset, top + targetSpanH * cellH - inset);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2E000000 | accent);
        canvas.drawRoundRect(tmpF, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, host.dp(1.5f)));
        paint.setColor(0xAA000000 | accent);
        canvas.drawRoundRect(tmpF, radius, radius, paint);
    }

    /**
     * The resize frame: an accent outline on the block the widget WOULD take
     * (it previews ahead of the commit), with a grab handle on each edge the
     * provider allows to move.
     */
    private void drawResizeFrame(Canvas canvas) {
        WidgetItem w = resizeItem;
        float left = getPaddingLeft() + pendCol * cellW;
        float top = getPaddingTop() + pendRow * cellH;
        tmpF.set(left, top, left + pendSpanW * cellW, top + pendSpanH * cellH);
        float inset = host.dp(3);
        tmpF.inset(inset, inset);
        int accent = DexTheme.of(host).accent;
        float radius = host.dp(10);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, host.dp(2)));
        paint.setColor(accent);
        canvas.drawRoundRect(tmpF, radius, radius, paint);

        float[] c = new float[2];
        float handleR = host.dp(7);
        for (int edge = 1; edge <= 4; edge++) {
            boolean h = edge == 1 || edge == 3;
            if (h && !horizontalResizable(w)) continue;
            if (!h && !verticalResizable(w)) continue;
            handleCenter(edge, c);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawCircle(c[0], c[1], handleR, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(c[0], c[1], handleR - Math.max(2f, host.dp(2)), paint);
        }
    }

    private void drawTrash(Canvas canvas) {
        trashRect(tmpRect);
        tmpF.set(tmpRect);
        float radius = tmpF.height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(overTrash ? 0xF2E81123 : 0xB3121722);
        canvas.drawRoundRect(tmpF, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, host.dp(1)));
        paint.setColor(overTrash ? 0xFFFF6B6B : 0x33FFFFFF);
        canvas.drawRoundRect(tmpF, radius, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(overTrash ? 0xFFFFFFFF : 0xFFc4ccd8);
        paint.setTextSize(host.sp(12.5f));
        float baseline = tmpF.centerY() - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText("✕   " + host.getString(R.string.lx_remove_from_desktop),
                tmpF.centerX(), baseline, paint);
    }

    private void drawEmptyHint(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        // over the wallpaper, like the tile labels — not a themed surface
        paint.setColor(host.deskLightInk() ? 0x66e7ecf3 : 0x77101828);
        paint.setTextSize(host.sp(12.5f));
        canvas.drawText(host.getString(R.string.lx_desktop_hint),
                getWidth() / 2f, getHeight() / 2f, paint);
    }
}
