package com.ccrstech.openandroiddex.launcher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Build;

/**
 * The desktop's AppWidgetHost. One host id, forever: the ids the system hands
 * out are keyed by (package, hostId), so changing HOST_ID would orphan every
 * widget already placed on the desktop.
 *
 * Binding an id to a provider normally needs BIND_APPWIDGET — a
 * signature|privileged permission no sideloaded APK can hold. The PC side
 * whitelists this package instead (`cmd appwidget grantbind` over adb, the
 * same shell-side grant the system launcher gets at build time), and when
 * that grant is missing — the APK running without the PC — the add flow falls
 * back to the system's own bind-confirmation dialog.
 */
final class DexWidgetHost extends AppWidgetHost {

    static final int HOST_ID = 0x0DE5;

    DexWidgetHost(Context context) {
        super(context, HOST_ID);
    }

    /**
     * Our subclass, so hold-to-move and right-click work over widget content.
     * NOT where the interaction handler goes: createView copies the HOST's
     * handler onto the view the instruction after this returns — see
     * {@link WidgetLaunch#install}.
     */
    @Override
    protected AppWidgetHostView onCreateView(
            Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
        return new WidgetHostView(context);
    }

    /**
     * Does this provider owe the user a setup screen? Asked by the add flow,
     * by the detour that runs it and by the silent clock seeding, so it lives
     * here rather than in three copies. WIDGET_FEATURE_CONFIGURATION_OPTIONAL
     * is the provider saying its defaults are fine — honouring it is what
     * keeps the seeding silent.
     */
    static boolean needsConfigure(AppWidgetProviderInfo info) {
        if (info == null || info.configure == null) return false;
        return !(Build.VERSION.SDK_INT >= 28
                && (info.widgetFeatures
                        & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0);
    }

    /**
     * Release every widget id the desktop has records for, then drop the
     * records. Used by the Settings reset flows, which run in their own
     * activity: a fresh host object with the same HOST_ID reaches the same
     * system-side allocations, so nothing leaks. Without the delete the ids
     * would stay allocated in AppWidgetService — and a provider keeps
     * receiving updates for a widget nobody will ever draw again.
     */
    static void wipe(Context ctx) {
        String stored = DexPrefs.prefs(ctx).getString(DesktopGrid.KEY_WIDGETS, "");
        if (stored == null || stored.isEmpty()) return;
        AppWidgetHost host = new DexWidgetHost(ctx.getApplicationContext());
        for (String record : stored.split("\\|")) {
            int sep = record.indexOf(':');
            if (sep <= 0) continue;
            try {
                host.deleteAppWidgetId(Integer.parseInt(record.substring(0, sep)));
            } catch (Exception ignored) {
            }
        }
        DexPrefs.prefs(ctx).edit().remove(DesktopGrid.KEY_WIDGETS).apply();
    }
}
