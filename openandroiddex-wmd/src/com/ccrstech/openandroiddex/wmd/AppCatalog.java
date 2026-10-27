package com.ccrstech.openandroiddex.wmd;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's apps as its own launcher lists them: what the computer's Apps
 * page shows, with their real names and icons.
 *
 * <p>Asked of the package manager in this process, as the shell, through
 * {@link PhoneContext} — the computer used to guess a name from the package
 * ("Youtube" for YouTube, "Gm" for Gmail) because {@code cmd package} gives no
 * labels and no icons. Each entry carries when the app was last updated, so the
 * computer keeps the icons it already has and asks only for the rest; an icon is
 * drawn once at the size asked for — an adaptive icon in the phone's own mask —
 * and sent as a PNG.
 */
final class AppCatalog {

    /** The desktop's own shell: a launcher in a window of its own is not an app anyone opens. */
    private static final String OWN_LAUNCHER = WmDaemon.LAUNCHER;

    private final PackageManager pm;
    /** package → what its launcher entry resolved to, from the last list. */
    private final Map<String, ResolveInfo> entries = new HashMap<>();

    AppCatalog() {
        pm = PhoneContext.get().getPackageManager();
    }

    /** Every app with a launcher entry, one entry each, as JSON. */
    synchronized JSONArray list() throws Exception {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        entries.clear();
        JSONArray out = new JSONArray();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (OWN_LAUNCHER.equals(pkg) || entries.containsKey(pkg)) continue;
            entries.put(pkg, ri);
            JSONObject a = new JSONObject();
            a.put("pkg", pkg);
            a.put("activity", ri.activityInfo.name);
            CharSequence label = ri.loadLabel(pm);
            a.put("label", label == null ? pkg : label.toString().trim());
            ApplicationInfo app = ri.activityInfo.applicationInfo;
            a.put("system", app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0);
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                a.put("updated", info.lastUpdateTime);
                a.put("version", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
            } catch (PackageManager.NameNotFoundException e) {
                a.put("updated", 0);
            }
            out.put(a);
        }
        return out;
    }

    /** The launcher entry for {@code pkg}: from the last list, else asked for now. Null if it has none. */
    synchronized ComponentName component(String pkg, String activity) {
        if (activity != null && !activity.isEmpty()) return new ComponentName(pkg, activity);
        ResolveInfo ri = entries.get(pkg);
        if (ri == null) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
            if (found.isEmpty()) return null;
            ri = found.get(0);
        }
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    /** {@code pkg}'s launcher icon, {@code px} square, as a PNG; null if it cannot be drawn. */
    byte[] icon(String pkg, int px) {
        // Some icons have text in them (One UI's live Calendar); without fonts, drawing one ends the process.
        PhoneContext.loadFonts();
        ResolveInfo ri;
        synchronized (this) {
            ri = entries.get(pkg);
        }
        try {
            Drawable d = ri != null ? ri.loadIcon(pm) : pm.getApplicationIcon(pkg);
            if (d == null) return null;
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream(px * px);
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            b.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            StringBuilder why = new StringBuilder(t.toString());
            for (Throwable c = t.getCause(); c != null; c = c.getCause()) why.append(" <- ").append(c);
            AppServer.log("no icon for " + pkg + " (" + why + ")");
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            System.out.println(sw);
            return null;
        }
    }

    /** What the window's title bar says. */
    synchronized String label(String pkg) {
        try {
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(app);
            return l == null ? pkg : l.toString();
        } catch (Throwable t) {
            return pkg;
        }
    }
}
