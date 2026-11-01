package com.ccrstech.openandroiddex.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone's Linux apps, for a computer that shows the phone's apps in
 * windows of its own (doc/apps-on-pc.md, "Linux apps").
 *
 * <p>The computer's half on the phone — the app server, the shell's process —
 * cannot see the guest: it lives in this app's own storage. So it asks here,
 * in-process and as the shell, through these methods; each answers with one
 * {@code json} extra:
 *
 * <ul>
 * <li>{@code linux.apps} — whether Linux can run here and is installed, and
 *     its apps: what XFCE's Applications menu lists.</li>
 * <li>{@code linux.icon} {@code {id, px}} — one app's icon, a PNG in base64.</li>
 * <li>{@code linux.run} {@code {id, w, h, dpi}} — open it, its windows bound
 *     for the computer: the window session starts at that size and density
 *     if it is not up. {@code ok} false, with a {@code message}, when it cannot
 *     be: the phone's own desktop has the window session.</li>
 * <li>{@code linux.events} {@code {wait}} — what happened to its windows
 *     since the last call (opened, retitled, closed), waiting up to
 *     {@code wait} ms for something to: the app server asks again at once.</li>
 * <li>{@code linux.close} {@code {xid}} — ask the app to close a window, as
 *     the computer's ✕ does; the app may ask about unsaved work first.</li>
 * <li>{@code linux.install} — set Linux up, as opening it on the phone does:
 *     the computer's Apps page offers it when the phone can run Linux and has
 *     none yet, or its setup stopped.</li>
 * <li>{@code media.mode} {@code {mode, pick?}} — where the computer's app
 *     windows sent the sound ("computer", "both", or "phone" and the output
 *     picked), recorded as the desktop's own choice so its flyout agrees.</li>
 * <li>{@code pad.offer} {@code {port, token}} — the phone's screen offered as the
 *     touchpad of the computer's windows (Touchpad, HostPointer): where the app
 *     server takes the pad, and the notification that opens it, where this app may
 *     post one ({@code offered}). {@code pad.open}, the same and the pad up now —
 *     a window's menu on the computer asked. {@code pad.close} — no longer offered:
 *     the pad and the notification down; says whether a desktop is up
 *     ({@code desktop}), which the overlay permission is still needed for.</li>
 * <li>{@code drag.watch} {@code {display}} — hear the drags on an app window's
 *     display, and take the one the app server drops on its corner
 *     ({@link PcDrags}); {@code drag.unwatch} {@code {display}} — its window
 *     closed. {@code drag.events} {@code {wait}} — what those drags did since
 *     the last call (started, dropped here, ended), waiting up to {@code wait}
 *     ms for something to, as {@code linux.events} does, and the displays it
 *     has a window on ({@code watching}).</li>
 * <li>{@code drop.arm} {@code {display, batch, files: [{i, name}], pixel?}} —
 *     files a computer dropped on that display's app, staged here (below): the
 *     next press on the display's corner starts a drag of them, which the app
 *     server carries to where the computer's pointer let go. It lands on an
 *     overlay put up for it where this app may draw over others, else (or
 *     with {@code pixel}) on the drag pixel; {@code on} says which
 *     ({@link PcDrags#arm}). {@code drop.disarm} {@code {display}} — no press
 *     came. {@code drop.opened} {@code {batch, files, wait}} — the app took
 *     them: which it has opened ({@code opened}, each one's {@code i}),
 *     waiting up to {@code wait} ms for all of them. {@code drop.forget}
 *     {@code {batch}} — the app did not take them: they go.</li>
 * </ul>
 *
 * <p>{@code linux.apps} also says how far the setup is ({@code phase},
 * {@code pct}): Linux.readStatus's reading of the guest's state.env.
 *
 * <p>A dropped file is staged by opening {@code drop/<batch>/<i>/<name>} here
 * for writing, and read back for reading: a file in this app's cache, which
 * {@link DropsProvider} serves to the app it is dropped on.
 *
 * <b>Only the shell may call this</b>, as {@link RemoteProvider}: the manifest
 * guards it with {@code android.permission.DUMP}, and {@link #call} checks the
 * uid itself, because Android applies no permission check to {@code call()}.
 */
public class AppsProvider extends ContentProvider {

    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    private static final long LIST_WAIT_S = 20;
    private static final long EVENTS_WAIT_MAX_MS = 30_000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) {
            throw new SecurityException("the phone's apps on a computer are for adb only");
        }
        Context ctx = getContext();
        JSONObject out = new JSONObject();
        long token = Binder.clearCallingIdentity();
        try {
            JSONObject in = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
            LinuxWindows windows = LinuxWindows.get(ctx);
            switch (method == null ? "" : method) {
                case "linux.apps": {
                    boolean supported = Linux.abiSupported();
                    boolean installed = supported && Linux.isInstalled(ctx);
                    out.put("supported", supported).put("installed", installed);
                    if (installed) {
                        Linux.Status st = Linux.readStatus(ctx);
                        out.put("phase", st.phase).put("pct", st.pct);
                    }
                    JSONArray list = new JSONArray();
                    if (installed) {
                        CountDownLatch done = new CountDownLatch(1);
                        windows.loadApps(done::countDown);
                        done.await(LIST_WAIT_S, TimeUnit.SECONDS);
                        for (LinuxApps.App a : windows.apps()) {
                            list.put(new JSONObject().put("id", a.id).put("name", a.name)
                                    .put("cls", a.wmClass).put("comment", a.comment));
                        }
                    }
                    out.put("list", list);
                    break;
                }
                case "linux.icon": {
                    LinuxApps.App a = find(windows, in.optString("id"));
                    int px = Math.max(16, Math.min(256, in.optInt("px", 96)));
                    Bitmap b = a == null ? null : windows.appIcon(a);
                    if (b == null && a != null && a.icon != null) b = LinuxWindows.decodeIcon(a.icon, px);
                    if (b != null) out.put("png", png(b, px));
                    break;
                }
                case "linux.run": {
                    LinuxApps.App a = find(windows, in.optString("id"));
                    if (a == null) {
                        out.put("ok", false).put("message", "no Linux app " + in.optString("id"));
                        break;
                    }
                    int w = in.optInt("w", 1280);
                    int h = in.optInt("h", 800);
                    int dpi = in.optInt("dpi", 96);
                    String[] refused = new String[1];
                    onMain(() -> refused[0] = windows.launchForPc(a, w, h, dpi));
                    if (refused[0] != null) out.put("ok", false).put("message", refused[0]);
                    else out.put("ok", true);
                    break;
                }
                case "linux.events": {
                    long wait = Math.max(0, Math.min(EVENTS_WAIT_MAX_MS, in.optLong("wait", 10_000)));
                    out.put("events", windows.takePcEvents(wait)).put("pc", windows.forPc());
                    break;
                }
                case "linux.install": {
                    if (!Linux.abiSupported()) {
                        out.put("ok", false).put("message", "this phone cannot run Linux");
                        break;
                    }
                    // Asked for by a person on the computer: an earlier uninstall is undone.
                    Linux.setUninstalled(ctx, false);
                    LinuxService.provision(ctx);
                    out.put("ok", true);
                    break;
                }
                case "linux.close": {
                    int xid = in.optInt("xid");
                    onMain(() -> windows.closeXid(xid));
                    out.put("ok", true);
                    break;
                }
                case "media.mode": {
                    JSONObject p = in.optJSONObject("pick");
                    DexMedia.Output pick = null;
                    if (p != null) {
                        pick = new DexMedia.Output();
                        pick.type = p.optInt("type");
                        pick.address = p.optString("address");
                        pick.name = p.optString("name");
                        pick.builtin = pick.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
                    }
                    DexMedia.adoptFromComputer(ctx, in.optString("mode"), pick);
                    out.put("ok", true);
                    break;
                }
                case "pad.offer":
                case "pad.open": {
                    int port = in.optInt("port", -1);
                    String key = in.optString("token");
                    if (port <= 0 || port > 65535 || key.isEmpty()) {
                        out.put("ok", false).put("message", "no port or token for the pad");
                        break;
                    }
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        out.put("ok", false).put("message", "the phone's touchpad needs Android 11");
                        break;
                    }
                    boolean open = "pad.open".equals(method);
                    boolean[] done = new boolean[2];
                    onMain(() -> {
                        Touchpad.offeredByComputer(port, key);
                        done[1] = TouchpadNotice.showForComputer(ctx);
                        done[0] = !open || Touchpad.openForComputer(ctx);
                    });
                    out.put("ok", done[0]).put("offered", done[1]);
                    if (!done[0]) out.put("message", "the touchpad could not be opened on the phone");
                    break;
                }
                case "pad.close": {
                    onMain(() -> {
                        Touchpad.computerGone();
                        TouchpadNotice.hideForComputer(ctx);
                    });
                    out.put("ok", true).put("desktop", LauncherActivity.desktopShell() != null);
                    break;
                }
                case "drag.watch": {
                    int display = in.optInt("display", -1);
                    String[] refused = new String[1];
                    onMain(() -> refused[0] = PcDrags.get(ctx).watch(display));
                    out.put("ok", refused[0] == null);
                    if (refused[0] != null) out.put("message", refused[0]);
                    break;
                }
                case "drag.unwatch": {
                    int display = in.optInt("display", -1);
                    onMain(() -> PcDrags.get(ctx).unwatch(display));
                    // A desktop keeps the overlay app-op the server may give back with its last window.
                    out.put("ok", true).put("desktop", LauncherActivity.desktopShell() != null);
                    break;
                }
                case "drag.caps": {
                    // Asked first on Android 17, where a launcher from before this
                    // puts a presentation on the window's display and its app is
                    // never shown: this one puts an overlay there, or nothing.
                    out.put("ok", true).put("overlayFrom", PcDrags.OVERLAY_ONLY_FROM);
                    break;
                }
                case "drag.events": {
                    long wait = Math.max(0, Math.min(EVENTS_WAIT_MAX_MS, in.optLong("wait", 10_000)));
                    PcDrags drags = PcDrags.get(ctx);
                    out.put("events", drags.takeEvents(wait)).put("watching", drags.watching());
                    break;
                }
                case "drop.arm": {
                    int display = in.optInt("display", -1);
                    String batch = in.optString("batch");
                    JSONArray files = in.optJSONArray("files");
                    boolean pixel = in.optBoolean("pixel");
                    DropsProvider.sweep(ctx);
                    String[] on = new String[1];
                    String[] why = new String[1];
                    onMain(() -> on[0] = PcDrags.get(ctx).arm(display, batch, files, pixel, why));
                    // A new window takes no touch before it has drawn: the answer waits for that.
                    if ("overlay".equals(on[0])) PcDrags.get(ctx).sourceDrawn(display, 1000);
                    out.put("ok", on[0] != null);
                    if (on[0] != null) out.put("on", on[0]);
                    else out.put("message", why[0]);
                    break;
                }
                case "drop.disarm": {
                    int display = in.optInt("display", -1);
                    onMain(() -> PcDrags.get(ctx).disarm(display));
                    out.put("ok", true);
                    break;
                }
                case "drop.opened": {
                    long wait = Math.max(0, Math.min(EVENTS_WAIT_MAX_MS, in.optLong("wait", 0)));
                    out.put("ok", true).put("opened", DropsProvider.opened(in.optString("batch"), in.optJSONArray("files"), wait));
                    break;
                }
                case "drop.forget": {
                    DropsProvider.forget(ctx, in.optString("batch"));
                    out.put("ok", true);
                    break;
                }
                default:
                    out.put("ok", false).put("message", "no method " + method);
            }
        } catch (Exception e) {
            try {
                out.put("ok", false).put("message", e.toString());
            } catch (Exception ignored) {
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        Bundle b = new Bundle();
        b.putString("json", out.toString());
        return b;
    }

    private static LinuxApps.App find(LinuxWindows windows, String id) {
        List<LinuxApps.App> apps = windows.apps();
        if (apps.isEmpty()) {
            CountDownLatch done = new CountDownLatch(1);
            windows.loadApps(done::countDown);
            try {
                done.await(LIST_WAIT_S, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            apps = windows.apps();
        }
        for (LinuxApps.App a : apps) if (a.id.equals(id)) return a;
        return null;
    }

    /** LinuxWindows' own thread is the main one; a binder thread waits for its turn there. */
    private static void onMain(Runnable r) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
    }

    private static String png(Bitmap b, int px) {
        Bitmap scaled = b.getWidth() == px && b.getHeight() == px ? b : Bitmap.createScaledBitmap(b, px, px, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * A dropped file being staged ({@code drop/<batch>/<i>/<name>}): opened for
     * writing as its bytes come in, and for reading when the app it was dropped
     * on did not take it and it goes to Download after all. The shell only.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) {
            throw new SecurityException("the phone's apps on a computer are for adb only");
        }
        List<String> path = uri.getPathSegments();
        File f = path.size() == 4 && "drop".equals(path.get(0))
                ? DropsProvider.file(getContext(), path.subList(1, 4)) : null;
        if (f == null) throw new FileNotFoundException(uri.toString());
        if ("r".equals(mode)) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        File dir = f.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new FileNotFoundException("no room for " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
