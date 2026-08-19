package com.ccrstech.openandroiddex.launcher;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHost;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.util.Pair;
import android.view.Display;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Makes a click INSIDE a hosted widget open its app the way the app drawer does.
 *
 * A widget's content carries its own PendingIntents. With no handler installed
 * the platform sends them itself, and the options it builds for that send
 * (RemoteViews.RemoteResponse#getLaunchOptions) carry a launch display id but NO
 * bounds and NO windowing mode — so One UI resolves the launch to fullscreen and
 * the clock widget filled the desktop while the drawer icon opened a window. It
 * cannot be repaired afterwards from the PC: `am start -n <component>` re-enters
 * the task that already exists instead of relaunching it, widget deep-link
 * targets are usually non-exported so uid 2000 may not start them at all, and
 * `am task resize` is refused on a task that is not already freeform.
 *
 * So we take the platform's OWN options and add the two fields it never sets,
 * instead of building options from scratch: theirs already hold the provider's
 * transition, its shared elements, its pending-intent launch flags and the
 * background-activity-start mode — and the send stays the provider's own
 * PendingIntent, so its identity, its data and its extras all survive.
 *
 * Every member reached here is hidden API, available only because the PC sets
 * hidden_api_policy=1 before the launcher process is forked. Resolution is
 * all-or-nothing on purpose: a half-installed handler would send a collection
 * widget's rows without their fill-in intent — silently opening the wrong mail,
 * the wrong article — which is worse than opening the right thing fullscreen.
 * When anything is missing nothing is installed and the platform default runs
 * untouched.
 */
final class WidgetLaunch implements InvocationHandler {

    /** What the desktop shell must answer for a widget launch to be placed. */
    interface Desktop {
        /** The rect the next desktop window gets — the same one the drawer uses. */
        Rect nextWindowBounds();

        /** Display to fall back on when the clicked view is already detached. */
        int displayId();

        /** Stamp display + bounds + freeform onto options someone else built. */
        ActivityOptions shapeForDesktop(ActivityOptions opts, Rect bounds, int displayId);

        /**
         * OUR context — the only one that can resolve OUR resource ids. A view
         * inside a widget answers getContext() with a RemoteViewsContextWrapper
         * whose getResources() is the PROVIDER's table, so a launcher string id
         * looked up through it misses (or, worse, hits a stranger's string).
         */
        Context desktopContext();
    }

    private final Desktop desktop;
    private final Class<?> responseClass;
    private final Method getLaunchOptions;    // RemoteResponse#getLaunchOptions(View)
    private final Method startPendingIntent;  // RemoteViews#startPendingIntent(View,PendingIntent,Pair)
    private final Method getPendingFlags;     // ActivityOptions#getPendingIntentLaunchFlags()
    private final Method setPendingFlags;     // ActivityOptions#setPendingIntentLaunchFlags(int)

    /** Throws unless the whole set resolves — see the all-or-nothing rule above. */
    private WidgetLaunch(Desktop desktop) throws Exception {
        this.desktop = desktop;
        responseClass = Class.forName("android.widget.RemoteViews$RemoteResponse");
        getLaunchOptions = responseClass.getMethod("getLaunchOptions", View.class);
        startPendingIntent = RemoteViews.class.getMethod(
                "startPendingIntent", View.class, PendingIntent.class, Pair.class);
        getPendingFlags = ActivityOptions.class.getMethod("getPendingIntentLaunchFlags");
        setPendingFlags = ActivityOptions.class.getMethod(
                "setPendingIntentLaunchFlags", int.class);
    }

    /**
     * Must run before the first createView. AppWidgetHost#createView copies the
     * HOST's handler onto the view and applies the provider's first RemoteViews
     * in the same call, so a handler installed on the view — in its constructor
     * or in onCreateView — is overwritten one instruction later, and one
     * installed after createView returns never reaches the click listeners that
     * are already wired.
     */
    static void install(AppWidgetHost host, Desktop desktop) {
        try {
            WidgetLaunch launch = new WidgetLaunch(desktop);
            Object hook = handlerHook();
            Class<?> iface = hook instanceof Method
                    ? ((Method) hook).getParameterTypes()[0]
                    : ((Field) hook).getType();
            if (!iface.isInterface()) throw new NoSuchMethodException("not an interface: " + iface);
            Object proxy = Proxy.newProxyInstance(
                    WidgetLaunch.class.getClassLoader(), new Class<?>[]{iface}, launch);
            if (hook instanceof Method) {
                ((Method) hook).invoke(host, proxy);
            } else {
                ((Field) hook).set(host, proxy);
            }
            DexLog.step("widgets", "widget clicks routed through the desktop launcher");
        } catch (Throwable t) {
            // Degraded, never broken: clicks keep the platform's own behaviour,
            // so they still open — fullscreen. Loud, because it also means the
            // hidden-API lever is not set for this process, which other parts of
            // the desktop quietly depend on.
            DexLog.warn("widgets", "widget taps will open fullscreen", t);
        }
    }

    /**
     * The host-side handler slot, whatever this platform calls it: the setter
     * was renamed with its interface at API 31 and older builds do not all
     * expose one, so the field is the second road. The interface type is read
     * off whichever we find, so no signature is guessed from Build.VERSION.
     */
    private static Object handlerHook() throws Exception {
        for (String name : new String[]{"setInteractionHandler", "setOnClickHandler"}) {
            for (Method m : AppWidgetHost.class.getMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 1) return m;
            }
        }
        for (String name : new String[]{"mInteractionHandler", "mOnClickHandler"}) {
            try {
                Field f = AppWidgetHost.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchMethodException("AppWidgetHost exposes no interaction handler");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        int argc = args == null ? 0 : args.length;
        // Object's own methods reach an InvocationHandler too, and the framework
        // both stores and compares this handler (AppWidgetHostView wraps it in an
        // InteractionLogger). Returning null for hashCode() would unbox to an NPE
        // thrown out of framework code.
        if (argc == 0 && "hashCode".equals(name)) return System.identityHashCode(proxy);
        if (argc == 1 && "equals".equals(name)) return proxy == args[0];
        if (argc == 0 && "toString".equals(name)) return "WidgetLaunch";
        // onScroll(AbsListView) is a default method on the SAME interface. Only a
        // call carrying a PendingIntent is a click.
        if (argc < 2 || !(args[0] instanceof View) || !(args[1] instanceof PendingIntent)
                || (!"onInteraction".equals(name) && !"onClickHandler".equals(name))) {
            return boolean.class.equals(method.getReturnType()) ? Boolean.FALSE : null;
        }
        send((View) args[0], (PendingIntent) args[1], argc > 2 ? args[2] : null);
        // RemoteResponse#handleViewInteraction calls us and returns void — it
        // never reads this. `false` would not hand the click back to the
        // platform, it would drop it, so every path above must already have sent.
        return Boolean.TRUE;
    }

    private void send(View view, PendingIntent pi, Object response) {
        Context ctx = view.getContext();
        try {
            if (responseClass.isInstance(response)) {
                Object raw = getLaunchOptions.invoke(response, view);
                Pair<?, ?> pair = raw instanceof Pair ? (Pair<?, ?>) raw : null;
                // For a collection item the FIRST half is that row's fill-in
                // intent — the only thing separating one row from another — and
                // startPendingIntent casts the second half unchecked.
                if (pair != null && pair.second instanceof ActivityOptions) {
                    shape(view, pi, (ActivityOptions) pair.second);
                    // The platform's own send: it keeps the OEM lockscreen
                    // detour, sends through the widget's context exactly as stock
                    // does so background-start accounting is unchanged, and never
                    // throws.
                    if (Boolean.TRUE.equals(startPendingIntent.invoke(null, view, pi, pair))) return;
                    // It returns false only after swallowing an exception of its
                    // own — a cancelled PendingIntent, or a display denial. Its
                    // fallbacks are ours too, so there is nothing left to try.
                    failed(ctx);
                    return;
                }
            }
            // API 26–27 pass the fill-in Intent where later builds pass a
            // RemoteResponse. Same two fields on a fresh bundle, and the fill-in
            // is carried through, so a collection row still opens itself.
            Intent fillIn = response instanceof Intent ? (Intent) response : null;
            ActivityOptions opts = ActivityOptions.makeBasic();
            shape(view, pi, opts);
            pi.send(ctx, 0, fillIn, null, null, null, opts.toBundle());
        } catch (Throwable t) {
            DexLog.warn("widgets", "widget click could not be delivered", t);
            failed(ctx);
        }
    }

    /**
     * Add what the platform's options never carry. MUTATES rather than rebuilds:
     * these are the provider's own options and they hold its transition, its
     * shared elements and the background-activity-start mode that lets the send
     * through at all.
     */
    private void shape(View view, PendingIntent pi, ActivityOptions opts) {
        // Only an activity has a window to place. A widget button that fires a
        // broadcast — a refresh arrow — travels this same handler and is left
        // exactly as the provider built it.
        if (!isActivity(pi)) return;
        try {
            // Launch bounds are a property of the TASK the activity resolves
            // into. The widget view's context is this activity, so the send goes
            // out as Activity#startIntentSender with our token as resultTo: an
            // intent without NEW_TASK joins the DESKTOP'S OWN task, and the rect
            // below would then shrink the shell itself — the failure
            // WidgetDetourActivity exists to work around. OR it in rather than
            // assign: getLaunchOptions already puts NEW_TASK here on one of its
            // branches, and the provider may have set more.
            int flags = (Integer) getPendingFlags.invoke(opts);
            setPendingFlags.invoke(opts, flags | Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Throwable t) {
            // Without that guarantee the bounds are the dangerous half, so drop
            // the whole shaping: the click still opens, the way it does today.
            DexLog.warn("widgets", "no NEW_TASK guarantee — sending the click unshaped", t);
            return;
        }
        Display display = view.getDisplay();
        desktop.shapeForDesktop(opts, desktop.nextWindowBounds(),
                display != null ? display.getDisplayId() : desktop.displayId());
    }

    /** Unanswerable before API 31 — and shaping a non-activity send is inert. */
    private static boolean isActivity(PendingIntent pi) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return pi.isActivity();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Say so, and never do worse than the thing that already went wrong.
     *
     * The text comes from OUR context and the toast from the WIDGET'S: only
     * ours can resolve the string id, and only the widget's is on the desktop
     * display. Nothing here may throw — this runs on the click dispatch path,
     * inside a Proxy, and the frames above it (RemoteResponse
     * #handleViewInteraction, the RemoteViews click listener) catch nothing at
     * all, so an exception raised here would take the whole shell down over a
     * failed toast.
     */
    private void failed(Context ctx) {
        try {
            Toast.makeText(ctx, desktop.desktopContext()
                    .getString(R.string.lx_widget_click_failed), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
