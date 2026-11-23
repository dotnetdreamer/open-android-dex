package com.ccrstech.openandroiddex.wmd;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
        boolean styled = styledIconsWork(px);
        Throwable why = null;
        if (styled) {
            try {
                return png(styledIcon(ri, pkg), px);
            } catch (Throwable t) {
                why = t;
            }
        }
        try {
            byte[] own = ownIcon(ri, pkg, px);
            if (own != null) return own;
        } catch (Throwable t) {
            if (why == null) why = t;
        }
        // No icon of its own: the styled one, even if that is the default.
        if (!styled) {
            try {
                return png(styledIcon(ri, pkg), px);
            } catch (Throwable t) {
                if (why == null) why = t;
            }
        }
        StringBuilder text = new StringBuilder(String.valueOf(why));
        for (Throwable c = why == null ? null : why.getCause(); c != null; c = c.getCause()) text.append(" <- ").append(c);
        AppServer.log("no icon for " + pkg + " (" + text + ")");
        if (why != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            why.printStackTrace(new java.io.PrintWriter(sw));
            System.out.println(sw);
        }
        return null;
    }

    /** The icon as the phone's own launcher draws it: the maker's styling over the app's icon. */
    private Drawable styledIcon(ResolveInfo ri, String pkg) throws PackageManager.NameNotFoundException {
        return ri != null ? ri.loadIcon(pm) : pm.getApplicationIcon(pkg);
    }

    /**
     * The icon in the app's own package, as the app ships it: for a phone whose
     * styling does not work here ({@link #styledIconsWork}). Null when the app
     * has no icon of its own.
     */
    private byte[] ownIcon(ResolveInfo ri, String pkg, int px) throws Exception {
        ApplicationInfo app = ri != null ? ri.activityInfo.applicationInfo : pm.getApplicationInfo(pkg, 0);
        int res = ri != null ? ri.getIconResource() : app.icon;
        if (res == 0) return null;
        Drawable d = pm.getResourcesForApplication(app).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXHIGH, null);
        return d == null ? null : png(d, px);
    }

    /** Whether {@link #styledIcon} draws real icons in this process: decided by the first icon asked for. */
    private Boolean styledWorks;

    /**
     * Whether the maker's icon styling works in this process, which is no app.
     * MIUI and ColorOS restyle every icon through code that wants a real app
     * around it: ColorOS's OplusUXIconLoader reads a setting, and the provider
     * refuses a process it does not know ("Unable to find app for caller");
     * MIUI's falls back. Either way each app comes back as the platform's
     * default icon, the green or blue robot (Redmi 9A, realme C100), and the
     * icon in the app's own package has to do. One UI's styling works, and keeps
     * its live Calendar and Clock icons, so it stays first where it works.
     *
     * <p>Told by drawing three apps that have an icon of their own: the same
     * picture twice, or one that cannot be drawn, and it does not work.
     */
    private synchronized boolean styledIconsWork(int px) {
        if (styledWorks != null) return styledWorks;
        List<ResolveInfo> sample = new ArrayList<>();
        Iterable<ResolveInfo> from = entries.isEmpty()
                ? pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                : entries.values();
        for (ResolveInfo ri : from) {
            if (ri.activityInfo == null || ri.getIconResource() == 0) continue;
            if (OWN_LAUNCHER.equals(ri.activityInfo.packageName)) continue;
            sample.add(ri);
            if (sample.size() == 3) break;
        }
        if (sample.size() < 2) return true; // nothing to tell by: as before, undecided
        boolean works = true;
        List<byte[]> drawn = new ArrayList<>();
        for (ResolveInfo ri : sample) {
            try {
                byte[] p = png(ri.loadIcon(pm), px);
                for (byte[] q : drawn) {
                    if (Arrays.equals(p, q)) works = false;
                }
                drawn.add(p);
            } catch (Throwable t) {
                works = false;
            }
        }
        styledWorks = works;
        AppServer.log(works
                ? "icons: the phone's own styling"
                : "icons: each app's own, the phone's styling does not work here");
        return works;
    }

    /**
     * The corners a launcher rounds a square icon to, as a share of its side.
     * An icon painted right into all four corners is one the phone's launcher
     * masks as it draws it: an app's old-style icon, and MIUI's own icons for
     * its apps (Clock, Settings), which it hands over square. Everything else
     * already has its shape, an adaptive icon the phone's mask, and is left as
     * it is.
     */
    private static final float CORNER = 0.22f;

    /** {@code d} drawn {@code px} square, as a PNG; a square one with its corners rounded. */
    private static byte[] png(Drawable d, int px) {
        if (d == null) throw new IllegalStateException("no drawable");
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(c);
        if (opaque(b, 0, 0) && opaque(b, px - 1, 0) && opaque(b, 0, px - 1) && opaque(b, px - 1, px - 1)) {
            Bitmap round = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas rc = new Canvas(round);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            rc.drawRoundRect(0, 0, px, px, px * CORNER, px * CORNER, p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            rc.drawBitmap(b, 0, 0, p);
            b.recycle();
            b = round;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(px * px);
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return out.toByteArray();
    }

    /** Painted at that pixel. Not quite 0xFF: scaling softens a square icon's own edge (MIUI's FM Radio: 0xE8). */
    private static boolean opaque(Bitmap b, int x, int y) {
        return Color.alpha(b.getPixel(x, y)) > 0xC0;
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
