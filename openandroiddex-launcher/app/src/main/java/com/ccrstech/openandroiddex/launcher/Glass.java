package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;

/**
 * Background blur behind our own floating surfaces — the app drawer, the tray
 * flyouts, the desktop icon menu.
 *
 * Android 12 brought real cross-window blur back (FLAG_BLUR_BEHIND plus
 * {@code setBlurBehindRadius}), but whether it actually renders is a device
 * decision: SurfaceFlinger has to have blur support compiled in and the user
 * must not be in a battery-saver/animations-off state. {@link #blurSupported}
 * is the honest answer to that, and Settings shows a note when it is false
 * rather than leaving a slider that quietly does nothing.
 *
 * When blur is unavailable the surfaces are still translucent — the
 * transparency slider is pure alpha and works everywhere.
 */
final class Glass {

    private Glass() {
    }

    static boolean blurSupported(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Blur whatever is behind a window we add ourselves. Call before
     * {@code addView}; the params are read at add time.
     */
    @SuppressWarnings("deprecation")   // FLAG_BLUR_BEHIND is live again since API 31
    static void apply(Context ctx, WindowManager.LayoutParams lp, float densityDpi) {
        int radius = DexTheme.of(ctx).blurPx(densityDpi);
        if (radius <= 0 || !blurSupported(ctx)) return;
        lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        lp.setBlurBehindRadius(radius);
    }

    /**
     * The same, for a PopupWindow — which owns its window and does not expose
     * it. Its content view's root carries the real WindowManager params once
     * the popup is showing, so the blur is set there and pushed with an
     * update. Best-effort by construction: a framework that stops handing out
     * those params simply leaves the popup unblurred.
     */
    @SuppressWarnings("deprecation")
    static void apply(Context ctx, PopupWindow popup, float densityDpi) {
        int radius = DexTheme.of(ctx).blurPx(densityDpi);
        if (radius <= 0 || !blurSupported(ctx) || !popup.isShowing()) return;
        try {
            View root = popup.getContentView().getRootView();
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            if (lp == null) return;
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(radius);
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            if (wm != null) wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }
}
