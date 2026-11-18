package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A title bar's menu, opened under the button it belongs to: More (☰) and Sound, the two
 * menus the computer's own window for an app has (apps/win/mod.rs, more_menu and
 * sound_menu), on the phone's desktop.
 *
 * <p>A window of its own on the desktop's display, an accessibility overlay of
 * CaptionService: above every app window and the taskbar, needing no permission. The Linux
 * apps' menus are the same kind of window (LinuxWindows#showPopup). Not a PopupWindow from
 * the bar itself: the bar is a SurfaceControlViewHost reparented into another app's window,
 * where a popup has no window of ours to hang from and would be cropped to that app's
 * window anyway (doc/custom-titlebar-v2.md §0.2.5).
 *
 * <p>Gone when a row is picked, on a press anywhere outside it, and when CaptionService says
 * so: its window moved, closed or lost its bar. Main thread only.
 */
final class CaptionMenu {

    /** One row of a menu, or a line between two groups of them. */
    static final class Item {
        final String label;
        /** The key that does the same, shown at the row's end, or null. */
        final String hint;
        /** The tick in front: the choice the window, or the phone, is on now. */
        final boolean checked;
        /** What picking it does; null for a separator. */
        final Runnable action;

        private Item(String label, String hint, boolean checked, Runnable action) {
            this.label = label;
            this.hint = hint;
            this.checked = checked;
            this.action = action;
        }

        boolean separator() {
            return action == null;
        }
    }

    static Item item(String label, String hint, boolean checked, Runnable action) {
        return new Item(label, hint, checked, action);
    }

    static Item separator() {
        return new Item(null, null, false, null);
    }

    /** Which button a menu belongs to, so a second press on that button closes it. */
    static final int MORE = 1;
    static final int SOUND = 2;

    final int taskId;
    final int kind;

    private final WindowManager windowManager;
    private final FrameLayout frame;
    private final float density;
    /** The shadow's room around the card: a window we add has no surface insets of its own. */
    private final int margin;
    private boolean shown;
    /** Told once, when the menu goes: whether a press outside it was what closed it. */
    private final Gone gone;

    interface Gone {
        void onGone(CaptionMenu menu, boolean byOutsidePress);
    }

    /**
     * @param ctx an accessibility-overlay window context on the desktop display
     *            (CaptionService#overlayContext): its Resources are that display's, so its
     *            dp are the desktop's and not the phone panel's
     */
    CaptionMenu(Context ctx, int taskId, int kind, List<Item> items, Gone gone) {
        this.taskId = taskId;
        this.kind = kind;
        this.gone = gone;
        windowManager = ctx.getSystemService(WindowManager.class);
        density = ctx.getResources().getDisplayMetrics().density;
        margin = px(10);
        DexTheme theme = DexTheme.of(ctx);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(5), px(5), px(5), px(5));
        // Opaque, unlike the taskbar's glass flyouts: this one opens over an app's own
        // content, which a see-through card would show through its rows.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.cardSolid);
        bg.setCornerRadius(px(Math.round(theme.radius(10))));
        bg.setStroke(Math.max(1, px(1) / 2), theme.divider);
        card.setBackground(bg);
        card.setElevation(px(8));
        card.setMinimumWidth(px(200));
        // A press between the rows — on a line, or the card's own padding — is a press on the
        // menu, not outside it: kept here, so only the shadow's margin closes it.
        card.setOnTouchListener((v, e) -> true);

        for (Item it : items) {
            if (it.separator()) {
                View line = new View(ctx);
                line.setBackgroundColor(theme.divider);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1)));
                lp.setMargins(px(6), px(4), px(6), px(4));
                card.addView(line, lp);
            } else {
                card.addView(row(ctx, theme, it), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        frame = new FrameLayout(ctx) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    dismiss(true);
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                // The shadow's margin is outside the card, if not outside the window.
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) dismiss(true);
                return true;
            }
        };
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(margin, margin, margin, margin);
        frame.addView(card, cardLp);
        DexFonts.applyTo(ctx, frame);
        DexCursors.decorate(frame);
    }

    private View row(Context ctx, DexTheme theme, Item it) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(6), px(12), px(6));
        GradientDrawable lit = new GradientDrawable();
        lit.setColor(theme.hover);
        lit.setCornerRadius(px(Math.round(theme.radius(6))));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, lit);
        bg.addState(new int[]{android.R.attr.state_hovered}, lit);
        bg.addState(new int[0], new android.graphics.drawable.ColorDrawable(0x00000000));
        row.setBackground(bg);

        TextView check = new TextView(ctx);
        check.setText(it.checked ? "✓" : "");
        check.setTextColor(theme.accent);
        check.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12));
        check.setGravity(Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(px(18), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(ctx);
        name.setText(it.label);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(px(260));
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (it.hint != null) {
            TextView hint = new TextView(ctx);
            hint.setText(it.hint);
            hint.setTextColor(theme.textFaint);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12));
            hint.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = px(24);
            row.addView(hint, lp);
        }

        row.setClickable(true);
        row.setOnClickListener(v -> {
            // Gone first: what the row does may raise a window or move this one.
            dismiss(false);
            it.action.run();
        });
        return row;
    }

    private int px(int dp) {
        return Math.round(dp * density);
    }

    /**
     * Show the menu with its card's top-left at the bottom-left of the button that opened
     * it, or above the button where there is no room below — inside the display, and clear
     * of the taskbar along its bottom.
     *
     * @param anchor  the button's rect on the display: {left, top, right, bottom}
     * @param display the display's size, {width, height}
     * @param bottom  the rows at the display's bottom no menu should cover (the taskbar)
     */
    boolean show(int[] anchor, int[] display, int bottom) {
        frame.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = frame.getMeasuredWidth();
        int h = frame.getMeasuredHeight();
        int[] at = CaptionMath.menuAt(anchor, w, h, margin, display, bottom);
        int x = at[0];
        int y = at[1];
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        lp.setTitle("openandroiddex-caption-menu");
        try {
            windowManager.addView(frame, lp);
            shown = true;
            return true;
        } catch (RuntimeException e) {
            DexLog.warn("caption", "cannot show a title bar menu: " + e);
            return false;
        }
    }

    boolean showing() {
        return shown;
    }

    /** Take it off the screen. Nothing happens the second time. */
    void dismiss() {
        dismiss(false);
    }

    private void dismiss(boolean byOutsidePress) {
        if (!shown) return;
        shown = false;
        try {
            windowManager.removeViewImmediate(frame);
        } catch (RuntimeException ignored) {
        }
        if (gone != null) gone.onGone(this, byOutsidePress);
    }
}
