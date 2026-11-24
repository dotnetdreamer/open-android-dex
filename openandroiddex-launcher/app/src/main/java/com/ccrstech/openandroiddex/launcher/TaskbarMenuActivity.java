package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * A taskbar tile's right-click menu, as a window of its own, where a popup of the taskbar
 * would be out of sight.
 *
 * Where the desktop display is a Samsung phone's untrusted one (the Galaxy A13, Android 12),
 * overlays draw under apps, so the taskbar lives inside the desktop activity — the bottom of
 * the display's windows — and every popup it opens is a sub-window of it: under any app
 * window over it. The Close of a window's tile opened behind that very window (2026-11-24).
 * An activity started on top comes above them all, as a menu has to (see {@link #show}).
 *
 * One at a time, on the main thread: the pick goes to whoever opened it, after the menu has
 * gone, so what it starts is not under the menu's closing.
 */
public class TaskbarMenuActivity extends Activity {

    /** A menu asked for: its items top to bottom, its card's size in px, and who hears the pick. */
    private static final class Request {
        final String[] items;
        final int width;
        final int height;
        final IntConsumer pick;

        Request(String[] items, int width, int height, IntConsumer pick) {
            this.items = items;
            this.width = width;
            this.height = height;
            this.pick = pick;
        }
    }

    /**
     * The menu asked for and not yet answered; null otherwise. The callback cannot travel in
     * an intent, so the items keep it company here.
     */
    private static Request pending;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Request request;
    private boolean answered;

    /**
     * Open the menu above {@code anchor}, a view of {@code from}: {@code items} top to bottom,
     * and {@code pick} told the index chosen. Nothing is told when the menu is put away
     * without a choice.
     *
     * A freeform window of its own whose top left is the card's, just above the tile. One UI
     * keeps every freeform window above every full-screen one, so a see-through full-screen
     * menu came up under the very window it was for (Galaxy A13, 2026-11-24); the newest
     * freeform window is on top of them all. Started WITHOUT naming the display: that display
     * refuses the launcher a named launch (untrusted: "Permission Denial … with
     * launchDisplayId"), and an unnamed one from the desktop lands on the desktop, as the
     * File transfer window's does.
     *
     * One UI then makes the window no smaller than its freeform minimum (440 px square on the
     * A13, from the card's top left on) and draws its handle over the window's top middle, so
     * the window is see-through but for the card, which sits a strip below the window's top
     * where the handle has room. A click on the see-through rest puts the menu away, as one
     * outside a popup does; so does anything else taking the focus (onPause).
     */
    static void show(Activity from, View anchor, String[] items, IntConsumer pick) {
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int[] tile = {at[0], at[1], at[0] + anchor.getWidth(), at[1] + anchor.getHeight()};

        android.util.DisplayMetrics dm = from.getResources().getDisplayMetrics();
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, dm));
        float widest = 0;
        for (String item : items) widest = Math.max(widest, paint.measureText(item));
        int w = Math.max(px(dm, MIN_WIDTH_DP), (int) Math.ceil(widest) + px(dm, ROW_START_DP + ROW_END_DP + 8));
        int h = items.length * px(dm, ROW_DP) + 2 * px(dm, CARD_PAD_DP);
        int margin = px(dm, 4);
        int displayWidth = from.getWindow().getDecorView().getWidth();
        if (displayWidth <= 0) displayWidth = dm.widthPixels;
        int left = (tile[0] + tile[2]) / 2 - w / 2;
        left = Math.max(margin, Math.min(left, displayWidth - w - margin));
        int strip = px(dm, HANDLE_STRIP_DP);
        int top = tile[1] - h - margin;
        if (top - strip < margin) top = tile[3] + margin;
        pending = new Request(items, w, h, pick);

        Intent intent = new Intent(from, TaskbarMenuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchBounds(new android.graphics.Rect(left, top - strip, left + w, top + h));
        try {
            ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                    .invoke(opts, 5 /* WINDOWING_MODE_FREEFORM */);
        } catch (Exception ignored) {
        }
        from.startActivity(intent, opts.toBundle());
    }

    private static final int MIN_WIDTH_DP = 176;
    private static final int ROW_DP = 40;
    private static final int ROW_START_DP = 16;
    private static final int ROW_END_DP = 24;
    private static final int CARD_PAD_DP = 4;
    /** The see-through strip above the card, where One UI's window handle is drawn. */
    private static final int HANDLE_STRIP_DP = 14;

    private static int px(android.util.DisplayMetrics dm, float dp) {
        return Math.round(dp * dm.density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        request = pending;
        if (request == null || request.items.length == 0) {
            finish();
            return;
        }
        final String[] items = request.items;

        // See-through but for the card, a strip below the window's top (see show).
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) close(-1);
            return true;
        });
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0262A30);
        bg.setCornerRadius(dp(8));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x33FFFFFF);
        card.setBackground(bg);
        card.setPadding(0, dp(CARD_PAD_DP), 0, dp(CARD_PAD_DP));
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView row = new TextView(this);
            row.setText(items[i]);
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ROW_START_DP), 0, dp(ROW_END_DP), 0);
            row.setSingleLine(true);
            row.setBackground(rowBackground());
            row.setFocusable(true);
            row.setOnClickListener(v -> close(index));
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
        }
        // Its own size, which the window's (One UI's freeform minimum) may well exceed.
        android.widget.FrameLayout.LayoutParams at =
                new android.widget.FrameLayout.LayoutParams(request.width, request.height);
        at.topMargin = dp(HANDLE_STRIP_DP);
        // A click on the card but off its rows is not one outside the menu.
        card.setClickable(true);
        root.addView(card, at);
        setContentView(root);
    }

    /** A row lit while the pointer is over it or it is pressed, as the popup's are. */
    private android.graphics.drawable.Drawable rowBackground() {
        StateListDrawable lit = new StateListDrawable();
        lit.addState(new int[]{android.R.attr.state_hovered}, new ColorDrawable(0x26FFFFFF));
        lit.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(0x1AFFFFFF));
        lit.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), lit, null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            close(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The menu goes; {@code index} is the item chosen, or -1 for none. */
    private void close(int index) {
        if (answered) return;
        answered = true;
        if (pending == request) pending = null;
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
        IntConsumer pick = request != null ? request.pick : null;
        if (pick != null && index >= 0) main.post(() -> pick.accept(index));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Put away by anything else coming in front — the menu is never left behind it.
        if (request != null && !answered && !isChangingConfigurations()) close(-1);
    }

    /**
     * A click anywhere else — another window, the desktop — takes the focus, and the menu
     * goes. Not pausing is what it does: windows side by side are all resumed at once
     * (Android 10 on), and the menu stayed open over the desktop that had been clicked
     * (Galaxy A13, 2026-11-24).
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && request != null && !answered) close(-1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!answered && request != null && pending == request) pending = null;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
