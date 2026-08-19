package com.ccrstech.openandroiddex.launcher;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AdapterView;

/**
 * The view a hosted widget renders into, wrapped so the desktop stays in
 * charge of the pointer.
 *
 * A widget's content is interactive — buttons, lists, whole scrolling feeds —
 * so the stock long-press machinery never fires here: the RemoteViews child
 * under the pointer takes the stream on DOWN and keeps it. "Hold to move" and
 * "right-click for the menu" still have to work over every pixel of the
 * widget, exactly as they do over an icon, so both gestures are detected on
 * the dispatch path BEFORE the content consumes the event:
 *
 * - Long press: onInterceptTouchEvent sees every event of the gesture even
 *   while a child owns it, so a timer armed on DOWN and cancelled by
 *   movement/UP is a long-press detector the content cannot starve. The view
 *   is also clickable, so when NO child wants the stream the ordinary View
 *   long-press path fires instead — both roads end at the same callback,
 *   which is idempotent (the grid ignores it once a drag is live).
 *
 * - Right-click: a mouse button arrives as a generic motion event
 *   (ACTION_BUTTON_PRESS), dispatched parent-first — consuming it here opens
 *   our menu and keeps the widget from reacting to a button it was never
 *   designed for.
 */
class WidgetHostView extends AppWidgetHostView {

    interface Callbacks {
        /** The pointer held still on the widget — the grid starts a move. */
        void onWidgetLongPress(WidgetHostView view);

        /** Right-click — the grid opens the widget's menu at the pointer. */
        void onWidgetMenu(WidgetHostView view);

        /** A tap on a widget that has not drawn yet — open its app ourselves. */
        void onWidgetDefaultClick(WidgetHostView view, View tapped);
    }

    private Callbacks callbacks;
    private final Runnable longPress = () -> {
        pendingLongPress = false;
        if (callbacks != null) callbacks.onWidgetLongPress(this);
    };
    private boolean pendingLongPress;
    private float downX, downY;

    WidgetHostView(Context context) {
        super(context);
        // owns the stream when the widget's content declines it — see above
        setClickable(true);
        setOnLongClickListener(v -> {
            if (callbacks != null) callbacks.onWidgetLongPress(this);
            return true;
        });
    }

    void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        watchForHold(ev);
        // never steal: once the hold fires, the GRID takes the whole gesture
        return false;
    }

    /**
     * The second half of the detector. When NO child claims the stream this
     * view handles it as a plain clickable View — and a ViewGroup whose own
     * onTouchEvent took the gesture never consults onInterceptTouchEvent
     * again, so the UP that should disarm the timer would be invisible there.
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        watchForHold(ev);
        return super.onTouchEvent(ev);
    }

    private void watchForHold(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A mouse right-press is a touch DOWN too (the BUTTON_PRESS
                // that opens the menu follows on the generic pipeline). A held
                // right button is a menu being read, never a drag — arming here
                // would yank the menu away at the long-press timeout.
                if ((ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) break;
                downX = ev.getRawX();
                downY = ev.getRawY();
                if (!pendingLongPress) {
                    pendingLongPress = true;
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (pendingLongPress) {
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(ev.getRawX() - downX) > slop
                            || Math.abs(ev.getRawY() - downY) > slop) {
                        cancelLongPress2();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress2();
                break;
            default:
                break;
        }
    }

    /** Named apart from View.cancelLongPress(), which covers the other road. */
    private void cancelLongPress2() {
        if (!pendingLongPress) return;
        pendingLongPress = false;
        removeCallbacks(longPress);
    }

    /**
     * A scrolling child (a list widget) claims the gesture with this — after
     * which onInterceptTouchEvent stops seeing the stream, so the movement
     * that would have cancelled the hold timer becomes invisible. The claim
     * itself IS that movement: a scroll is not a hold.
     */
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) cancelLongPress2();
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                && (ev.getActionButton() & MotionEvent.BUTTON_SECONDARY) != 0) {
            if (callbacks != null) callbacks.onWidgetMenu(this);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /**
     * The placeholder a widget shows before its provider has pushed any
     * RemoteViews. The platform wires its own click listener onto this one,
     * and that listener starts the app with a null options bundle — no
     * display, no bounds, no windowing mode — so a tap on a widget that has
     * not drawn yet opened it fullscreen on the PHONE. It is also the one
     * click that never reaches {@link WidgetLaunch}: it is an ordinary View
     * listener, not a RemoteViews interaction.
     */
    @Override
    protected View getDefaultView() {
        View view = super.getDefaultView();
        try {
            AppWidgetProviderInfo info = getAppWidgetInfo();
            // AdapterView.setOnClickListener throws by contract, and a
            // collection widget's initial layout is normally a list root —
            // the same test the platform makes before wiring its own.
            // initialLayout == 0 means super handed back the error card.
            if (view != null && !(view instanceof AdapterView)
                    && info != null && info.initialLayout != 0) {
                // Read `callbacks` at CLICK time: this runs inside createView,
                // before the grid has attached the view or set them.
                view.setOnClickListener(v -> {
                    if (callbacks != null) callbacks.onWidgetDefaultClick(this, v);
                });
            }
        } catch (Throwable t) {
            DexLog.warn("widgets", "could not claim a widget's placeholder tap", t);
        }
        return view;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelLongPress2();
    }
}
