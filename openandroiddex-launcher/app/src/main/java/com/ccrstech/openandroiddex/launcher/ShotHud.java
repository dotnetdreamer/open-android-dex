package com.ccrstech.openandroiddex.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * What a screenshot looks like from the outside: the display flashes, and a
 * thumbnail of what was captured sits above the taskbar until it goes away.
 *
 * <p>Both halves exist because {@link DexShot} is otherwise completely silent.
 * The capture is a system call that draws nothing, and the only confirmation it
 * had was a {@link android.widget.Toast} — which, raised from the application
 * context, lands on the PHONE's panel. On a DeX session that is a different
 * screen from the one being photographed, and quite possibly one that is face
 * down on the desk.
 *
 * <p>An overlay window rather than views inside the desktop, for the reason
 * {@link TransferHud} records: a screenshot is usually taken WITH something
 * maximized over the whole display, and a preview behind that window is no
 * preview at all. It falls back into the activity when the overlay permission
 * is missing, which still covers the bare desktop.
 *
 * <p><b>Nothing here is on screen while the capture happens.</b> The flash is
 * fired from {@link DexShot.Result#captured()} — after the system has the
 * pixels — for the obvious reason: an overlay raised a moment earlier would be
 * IN the screenshot. That is also why the platform's own screenshot flash comes
 * after the shutter rather than with it.
 */
final class ShotHud {

    /** How long the preview stays before it fades out by itself. */
    private static final int SHOW_MS = 4500;

    /** Width of the thumbnail; its height follows the display's aspect. */
    private static final int PREVIEW_DP = 200;

    /** Peak opacity of the flash. Not 1.0 — this is a signal, not a strobe. */
    private static final float FLASH_ALPHA = 0.85f;

    private static final int FLASH_IN_MS = 70;
    private static final int FLASH_OUT_MS = 220;
    private static final int FADE_OUT_MS = 220;

    private final LauncherActivity act;
    private final DexTheme theme;

    private View card;
    private boolean overlay;
    /** The white sheet, while it is up. Held so teardown can take it away. */
    private View flashView;
    private boolean flashOverlay;
    /** The image the card points at, for the tap that opens it. */
    private Uri shot;

    private final Runnable dismiss = this::fade;

    ShotHud(LauncherActivity act) {
        this.act = act;
        this.theme = DexTheme.of(act);
    }

    // ── the two halves, in the order they happen ────────────────────────

    /**
     * The shutter: one white sheet over the whole display, in and out.
     *
     * Deliberately not touchable and not focusable. It is up for under a third
     * of a second, and a sheet that ate one click of whatever the user was
     * doing would be a worse bug than having no flash at all.
     */
    void flash() {
        clearFlash();
        View sheet = new View(act);
        sheet.setBackgroundColor(0xFFFFFFFF);
        sheet.setAlpha(0f);
        if (!attachFlash(sheet)) return;
        flashView = sheet;
        sheet.animate().alpha(FLASH_ALPHA).setDuration(FLASH_IN_MS).withEndAction(
                () -> sheet.animate().alpha(0f).setDuration(FLASH_OUT_MS)
                        .withEndAction(() -> {
                            if (flashView == sheet) clearFlash();
                        }));
    }

    /**
     * The preview, once the PNG is actually in the store.
     *
     * A card that appeared before the save could point at nothing when tapped,
     * and would have claimed a screenshot exists at the moment it was still
     * possible for MediaStore to refuse it.
     */
    void show(Bitmap thumb, Uri uri) {
        detachCard();
        if (thumb == null || thumb.getWidth() <= 0 || thumb.getHeight() <= 0) return;
        shot = uri;
        build(thumb);
        if (card == null) {
            DexLog.warn("shot", "no surface for the preview card");
            return;
        }
        attach();
        if (card == null) return;
        act.handler().postDelayed(dismiss, SHOW_MS);
    }

    // ── the card ────────────────────────────────────────────────────────

    private void build(Bitmap thumb) {
        LinearLayout column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(act.roundedFill(theme.card(), 16));
        column.setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(8));
        column.setElevation(act.dp(10));

        int w = act.dp(PREVIEW_DP);
        int h = Math.round(w * (float) thumb.getHeight() / thumb.getWidth());

        ImageView image = new ImageView(act);
        image.setImageBitmap(thumb);
        // The box is already the bitmap's aspect ratio, so FIT_XY neither
        // stretches nor leaves the letterbox FIT_CENTER would draw in the
        // frame colour.
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        // A frame of its own rather than the themed surface: this one exists to
        // be CLIPPED to, and clipToOutline needs a drawable that reports an
        // outline — which the Paper theme's grained surface does not.
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(theme.field);
        frame.setCornerRadius(act.dp(theme.radius(10)));
        frame.setStroke(Math.max(1, act.dp(1)), theme.divider);
        image.setBackground(frame);
        image.setClipToOutline(true);
        image.setContentDescription(act.getString(R.string.lx_screenshot));
        column.addView(image, new LinearLayout.LayoutParams(w, h));

        TextView caption = new TextView(act);
        caption.setText(R.string.lx_shot_saved);
        caption.setTextColor(theme.textFaint);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, act.sp(11.5f));
        caption.setMaxLines(1);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = act.dp(7);
        column.addView(caption, capLp);

        // The whole card is the target, not a pill in the corner of it: this
        // desktop is driven by a mouse over a video stream, and the card is
        // only up for a few seconds.
        if (shot != null) {
            column.setOnClickListener(v -> open());
            column.setClickable(true);
        }

        DexFonts.applyTo(act, column);
        DexCursors.decorate(column);
        card = column;
    }

    private void attach() {
        if (android.provider.Settings.canDrawOverlays(act)) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Not focusable: the user may well have been typing when
                    // they took this. Taps still reach the card.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = act.dp(16);
            // Above the taskbar, by the same measurement TransferHud uses —
            // the two cards share this corner and must not sit at different
            // heights in it.
            lp.y = act.dp(LauncherActivity.TASKBAR_DP + 14);
            Glass.apply(act, lp, act.uiDensity());
            try {
                act.getWindowManager().addView(card, lp);
                overlay = true;
                return;
            } catch (Exception e) {
                DexLog.warn("shot", "overlay window rejected", e);
            }
        }
        if (act.rootFrame() == null) {
            card = null;
            return;
        }
        attachInActivity();
        overlay = false;
    }

    /** The in-activity fallback placement, matching the overlay's corner. */
    private void attachInActivity() {
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = act.dp(16);
        lp.bottomMargin = act.dp(LauncherActivity.TASKBAR_DP + 14);
        act.rootFrame().addView(card, lp);
    }

    /**
     * The screenshot itself, in whatever views images, as a freeform window on
     * this display like everything else the desktop launches.
     *
     * The MediaStore URI is ours — we inserted the row — so unlike
     * {@link TransferHud}'s files we can actually grant read on it. The handler
     * is still resolved by hand: the system chooser, launched into a freeform
     * window on a secondary display, opens and closes again without ever
     * showing anything.
     */
    private void open() {
        act.handler().removeCallbacks(dismiss);
        if (shot == null) {
            detach();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(shot, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName target = null;
        try {
            for (ResolveInfo ri : act.getPackageManager().queryIntentActivities(intent, 0)) {
                if (ri.activityInfo == null) continue;
                if ("android".equals(ri.activityInfo.packageName)) continue;
                target = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                break;
            }
        } catch (Exception e) {
            DexLog.warn("shot", "cannot resolve an image viewer", e);
        }
        if (target != null) intent.setComponent(target);
        DexLog.step("shot", "open " + shot + " → "
                + (target != null ? target.flattenToShortString() : "whatever resolves"));
        try {
            Point size = new Point();
            act.getWindowManager().getDefaultDisplay().getRealSize(size);
            int w = Math.min(act.dp(900), size.x * 3 / 4);
            int h = Math.min(act.dp(620), size.y * 3 / 4);
            int x = (size.x - w) / 2;
            int y = (size.y - h) / 2;
            act.startActivity(intent, act.desktopWindowOptions(new Rect(x, y, x + w, y + h)));
        } catch (Exception e) {
            DexLog.warn("shot", "nothing would open the screenshot", e);
        }
        detachCard();
    }

    // ── teardown ────────────────────────────────────────────────────────

    /** Fade the card out, then take it off. */
    private void fade() {
        View v = card;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
            // A tap during the fade replaces the card; only retire our own.
            if (card == v) detachCard();
        });
    }

    /**
     * Take everything off screen — the activity is going away or being rebuilt.
     * The next capture builds it all again.
     */
    void detach() {
        detachCard();
        clearFlash();
    }

    /**
     * The card only. Separate from {@link #detach()} because the preview
     * replaces itself while the FLASH is still animating: the shutter fires on
     * the capture and the card arrives a PNG compression later, so a card
     * teardown that also cleared the sheet would cut the flash short every
     * single time.
     */
    private void detachCard() {
        act.handler().removeCallbacks(dismiss);
        shot = null;
        View v = card;
        card = null;
        if (v != null) {
            v.animate().cancel();
            try {
                if (overlay) {
                    act.getWindowManager().removeViewImmediate(v);
                } else if (act.rootFrame() != null) {
                    act.rootFrame().removeView(v);
                }
            } catch (Exception ignored) {
            }
        }
        overlay = false;
    }

    // ── the flash's own window ──────────────────────────────────────────

    private boolean attachFlash(View sheet) {
        if (android.provider.Settings.canDrawOverlays(act)) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            try {
                act.getWindowManager().addView(sheet, lp);
                flashOverlay = true;
                return true;
            } catch (Exception e) {
                DexLog.warn("shot", "flash window rejected", e);
            }
        }
        // Without the overlay grant the flash covers the desktop and not the
        // window on top of it — half a signal, but still the right half when
        // the desktop itself is what was photographed.
        if (act.rootFrame() == null) return false;
        act.rootFrame().addView(sheet, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        flashOverlay = false;
        return true;
    }

    private void clearFlash() {
        View v = flashView;
        flashView = null;
        if (v == null) return;
        v.animate().cancel();
        try {
            if (flashOverlay) {
                act.getWindowManager().removeViewImmediate(v);
            } else if (act.rootFrame() != null) {
                act.rootFrame().removeView(v);
            }
        } catch (Exception ignored) {
        }
    }
}
