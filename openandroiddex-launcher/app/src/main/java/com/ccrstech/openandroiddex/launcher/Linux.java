package com.ccrstech.openandroiddex.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything the app needs to know about its self-hosted Linux container,
 * with no daemon and no PC in the loop. The launcher owns the whole stack
 * under its OWN uid: proot ships in the APK's native-lib dir, the Ubuntu
 * rootfs is downloaded into the app's private storage on first run, and the
 * provisioning + runtime scripts run as this app. That ownership is the whole
 * point — it is what lets the same code become a standalone Linux app later,
 * with no shell uid or adb anywhere.
 *
 * This class is the shared vocabulary: where things live, which architecture's
 * rootfs to fetch, the environment the scripts run under, and how to read the
 * container's current state off disk. {@link LinuxService} does the work;
 * {@link LinuxActivity} shows it.
 */
final class Linux {

    private Linux() {}

    /**
     * Bumped on any change to the scripts, the proot build, or the rootfs. The
     * setup script wipes its phase stamps when {@code state.env}'s VERSION does
     * not match, so a bump forces a clean reprovision.
     */
    static final int PAYLOAD_VERSION = 1;

    /**
     * What this build installs INSIDE an already-provisioned guest, as opposed
     * to what it builds the guest OUT OF.
     *
     * {@link #PAYLOAD_VERSION} and this are two different questions and must
     * stay two different numbers. A version bump wipes the setup script's phase
     * stamps and re-extracts the rootfs — a container reset that discards
     * everything the user ever installed or created in there. A feature bump
     * only re-runs the setup script, whose stamps then carry it straight to the
     * phases that are new. Adding the browsers to a working Ubuntu is the
     * second kind of change, and doing it as the first kind would have cost
     * every existing user their container.
     *
     * 1 = XFCE + TigerVNC + noVNC. 2 = adds Firefox and Chromium. 3 = makes
     * them usable: Chromium's flag-warning policy, Firefox's AutoConfig prefs,
     * and the default-browser wiring. 4 = adds VS Code. 5 = puts Firefox,
     * Chromium and VS Code on the dock and the desktop. 6 = keeps the dock
     * launchers off the Applications menu (they duplicated every entry) and
     * moves the panel edit to session start, where it survives. 7 = installs
     * VS Code from the official tarball, because its .deb cannot be unpacked
     * under proot at all. 8 = the VS Code settings that make extensions
     * installable in here. 9 = the LinuxOnDeX shared folder: its sidebar
     * bookmark and the desktop icon style that makes it visible. 10 = git (with
     * openssh-client), because VS Code's Source Control panel is only an advert
     * for a Download Git for Linux button without it. 11 = the vscode:// URL
     * handler, without which signing in to GitHub from VS Code dead-ends on
     * "Failed to open URI". 12 = the Node.js phase judged by version, not
     * presence: its first cut could be satisfied by noble's archive — node 18,
     * and no npm, since Ubuntu splits npm out — whenever one apt-get update
     * hiccuped, and then held that forever. The bump carries the pinned
     * NodeSource repo and the retake into every guest that asked for Node.
     * 13 = sound: there is no audio device in the container, so PulseAudio
     * gets a null sink and the app drains its monitor over loopback — see
     * {@link LinuxAudio}. Carries pavucontrol with it, which is what the panel
     * volume plugin was already trying and failing to open.
     * 14 = the look: a dark flat GTK/xfwm4 theme, Adwaita icons and Cantarell,
     * written into the guest's /usr/local/etc/xdg as xfconf SYSTEM defaults —
     * a directory of our own, prepended to XDG_CONFIG_DIRS, because three of
     * those channel files are dpkg conffiles and replacing the xfce4-session
     * one deletes the failsafe session list, which leaves xfce4-session with
     * nothing to start. They apply everywhere the user has not chosen
     * otherwise and cannot be clobbered by a session writing its own channel
     * out. The same phase carries the settings that make this display cheap —
     * no compositing, no GTK animations, outline window drags, no wallpaper,
     * no DPMS blanking — none of which buy anything with no GPU behind Xvnc.
     * It also corrects the
     * xfconf directory the earlier desktop-icon and panel edits used
     * (.config/xfconf/xfconf-perchannel-xml, which xfconf has never read), so
     * the bump is what finally makes the dock launchers land as well.
     * 15 = the dock moves to the LEFT edge, vertical. It has to be a bump
     * rather than a quiet dock.py change because a guest that already settled
     * at 14 would otherwise never run the script again. Applied ONCE per guest
     * and marked: dragging the dock back to the bottom is a reasonable thing
     * to do, and re-imposing the position at every launch would leave no way
     * to make that stick.
     * 16 = that dock made full height with struts on, which changed nothing.
     * 17 = AUTOHIDE OFF, which is what actually made the icons move.
     * panel_window_screen_struts_edge returns STRUTS_EDGE_NONE outright for
     * any panel whose autohide-behavior is not NEVER — before it ever looks at
     * the edge, the length or enable-struts — and Ubuntu ships this panel with
     * autohide on. So it reserved nothing while still being visible (nothing
     * open to hide from), xfdesktop kept laying icons from x=0, and both 15
     * and 16 were downstream of a gate already returning NONE.
     * 18 = the dock STOPS BELOW THE TOP BAR instead of running under it, which
     * is what was hiding the Applications button: two dock-type windows both
     * own the corner and struts do not arbitrate between panels, only between
     * a panel and ordinary windows. Its length is computed from the screen
     * height less the top bar's, so the two edges meet exactly — which needs
     * the geometry, so linux-rt.sh passes it and the provisioning pass no
     * longer positions anything. Costs nothing: xfwm4 takes MAX(margin,
     * struts[LEFT]) and uses the strut's start/end y ONLY to test that it
     * intersects the monitor, so a partial-height panel reserves the same full
     * left margin a full-height one does.
     * The marker dock-positioned holds a layout NUMBER rather than merely
     * existing, so a guest carrying an older layout is lifted exactly once.
     * 19 = Chromium draws on the phone's GPU when the session's GPU probe
     * passed: its launch wrapper leaves out --disable-gpu then. The wrappers
     * are now written on every setup pass rather than by the stamped browser
     * tune, whose touch-stamp had kept the first ones on every guest that
     * tuned once — so this bump is what brings the new one to those guests.
     *
     * The app CHOOSER (see {@link #apps}) is deliberately NOT a feature level.
     * A feature bump exists to carry something new INTO guests that are
     * already built, and the chooser had nothing to carry: every guest built
     * before it was built with all four apps, which is exactly what the
     * chooser reports for them. Bumping would have re-run the whole setup
     * script on every existing container to arrive where it already was. What
     * brings the script back when a selection CHANGES is {@code apps.done} —
     * see {@link #needsProvision}.
     */
    static final int FEATURE_LEVEL = 19;

    /**
     * Guest binaries whose presence the setup log reports, as
     * name=path-inside-the-rootfs.
     *
     * VS Code is asked about by its real path, not the /usr/bin/code symlink:
     * that link's target is guest-absolute, so from out here it resolves
     * against ANDROID's filesystem and always reads as broken.
     */
    private static final String[][] GUEST_APPS = {
            {"firefox", "usr/bin/firefox"},
            {"chromium", "usr/bin/chromium"},
            {"code", "opt/vscode/bin/code"},
            {"git", "usr/bin/git"},
            {"nodejs", "usr/bin/node"},
            {"gimp", "usr/bin/gimp"},
            {"intellij", "opt/intellij/bin/idea.sh"},
    };

    /**
     * The ids the chooser offers, in the order it lists them — which is also
     * the order {@link #setApps} writes them in and therefore the order the
     * setup script's echo comes back in. One list, so the two can never drift.
     */
    static final String[] OPTIONAL_APPS = ids(GUEST_APPS);

    private static String[] ids(String[][] apps) {
        String[] out = new String[apps.length];
        for (int i = 0; i < apps.length; i++) out[i] = apps[i][0];
        return out;
    }

    /**
     * Which of the optional apps actually made it into the guest.
     *
     * The setup log lives in private storage that nothing can read without a
     * debuggable build, so "VS Code is nowhere to be found" was unanswerable
     * from the outside — its phase is non-fatal by design and says so only in
     * that log. This line goes to logcat, which the PC's session trace picks up.
     *
     * Three answers, not two: {@code off} is an app the user did not tick, and
     * telling it apart from {@code NO} is the whole point of the line. Without
     * it, an install that did exactly what it was asked to do reads in the log
     * as three failures.
     */
    static String installedApps(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (String[] app : GUEST_APPS) {
            boolean here = new File(root(ctx), "rootfs/" + app[1]).exists();
            if (sb.length() > 0) sb.append(' ');
            sb.append(app[0]).append('=')
                    .append(here ? "yes" : (wantsApp(ctx, app[0]) ? "NO" : "off"));
        }
        return sb.toString();
    }

    /**
     * Is this optional app in the guest? The same test {@link #installedApps}
     * reports, one app at a time, for the home screen's list of what is inside.
     */
    static boolean hasApp(Context ctx, String id) {
        for (String[] app : GUEST_APPS) {
            if (app[0].equals(id)) return new File(root(ctx), "rootfs/" + app[1]).exists();
        }
        return false;
    }

    // ── which optional apps the user asked for ──

    /**
     * The selection when nothing was ever stored: the four apps every guest
     * was built with before the chooser existed.
     *
     * Deliberately NOT the whole of {@link #GUEST_APPS}. Node.js, GIMP and
     * IntelliJ arrived after the chooser, so a default of "everything" would
     * claim them on behalf of every pre-chooser guest — none of which has
     * them, and whose owners never asked — and quietly pre-tick gigabytes of
     * developer tooling for every fresh install. Ticked is a decision the
     * user makes; this string is what stands until they do.
     *
     * The setup script's own LINUX_APPS fallback is this same string, and the
     * two meet byte-for-byte through {@code apps.done} in
     * {@link #needsProvision}: a drift between them would mean a container
     * that re-provisions itself on every single launch.
     */
    private static final String DEFAULT_APPS = "firefox chromium code git";

    /** What {@link #apps} stores when the user ticked nothing at all. */
    static final String NO_APPS = "none";

    /**
     * The user's tick list, kept OUTSIDE the container.
     *
     * A sibling of {@code linux/}, exactly like the uninstall marker and for
     * the same reason: a reset deletes that whole tree, and a selection stored
     * in there would be thrown away by the one operation whose entire job is to
     * rebuild the guest from it.
     *
     * A file rather than SharedPreferences, because {@link LinuxService} runs
     * in its own {@code :linux} process and cross-process preferences have not
     * been honest since MODE_MULTI_PROCESS was deprecated. Everything else this
     * class shares with the service is a file for the same reason.
     */
    private static File appsFile(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-apps");
    }

    /**
     * Has the user never said what to install?
     *
     * The tick file is the record of having been asked. The rootfs is the
     * grandfather clause: a guest built before the chooser existed was built
     * with all four apps, and asking its owner to choose now would be asking
     * about a decision taken for them long ago — one this screen could not
     * reverse anyway, since unticking never removes anything.
     *
     * The ROOTFS and not {@code state.env}, which is the obvious test and the
     * wrong one: {@link LinuxService#reset} recreates state.env as its first
     * act, so a reinstall asked for by someone who has never been through the
     * chooser would have skipped straight past it into a full download.
     *
     * Everything that starts an install is gated on this — see
     * {@link LinuxService#provision(Context)} — so the ~1.5 GB download cannot
     * begin behind a question the user has not been shown yet.
     */
    static boolean needsAppChoice(Context ctx) {
        if (appsFile(ctx).exists()) return false;
        return !new File(root(ctx), "rootfs").isDirectory();
    }

    /**
     * The selection, as the setup script wants it: space-separated ids in
     * {@link #GUEST_APPS} order, or {@link #NO_APPS}.
     *
     * Defaults to {@link #DEFAULT_APPS} when nothing was ever stored. That
     * default is what makes this invisible to every container that predates
     * it, and it is also the honest answer for a bare-shell run of the script.
     */
    static String apps(Context ctx) {
        String stored = readText(appsFile(ctx)).trim();
        return stored.isEmpty() ? DEFAULT_APPS : stored;
    }

    /** Did the user tick this id? */
    static boolean wantsApp(Context ctx, String id) {
        return (" " + apps(ctx) + " ").contains(" " + id + " ");
    }

    /**
     * Record the tick list. Canonical order and canonical spelling, because the
     * string is compared against the script's echo of it — see
     * {@link #needsProvision}.
     *
     * A write that fails is logged and otherwise survivable: {@link #apps}
     * falls back to everything, which is the pre-chooser behaviour, and
     * {@code state.env} appears a moment later so the question is not asked
     * twice. Losing a preference is worth far less than refusing to install.
     */
    static void setApps(Context ctx, java.util.Collection<String> picked) {
        StringBuilder sb = new StringBuilder();
        for (String[] app : GUEST_APPS) {
            if (!picked.contains(app[0])) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(app[0]);
        }
        String sel = sb.length() == 0 ? NO_APPS : sb.toString();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(appsFile(ctx))) {
            out.write(sel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            DexLog.warn("linux", "could not store the app selection", e);
            return;
        }
        DexLog.step("linux", "apps selected: " + sel);
    }

    /**
     * The selection the guest was actually BUILT with — the setup script writes
     * it as its last act, next to {@code state.env}.
     *
     * Absent means a container built before the chooser existed, which was
     * built with exactly {@link #DEFAULT_APPS}. Reading it as anything else
     * would put every one of those through a provisioning pass to discover
     * they were already right.
     */
    private static String appsBuilt(Context ctx) {
        String done = readText(new File(root(ctx), "apps.done")).trim();
        return done.isEmpty() ? DEFAULT_APPS : done;
    }

    /** The folder Android and the guest share, under Documents/. */
    static final String SHARED_NAME = "LinuxOnDeX";

    /**
     * The one folder both sides can reach: {@code /sdcard/LinuxOnDeX}, at the
     * top of internal storage where the user actually looks for it.
     *
     * That placement is not free. MediaProvider gates TOP-LEVEL names on shared
     * storage, so creating this needs MANAGE_EXTERNAL_STORAGE — unlike a
     * subdirectory of a default folder such as Documents/, which any app may
     * make. The trade was made deliberately: a folder buried two levels down is
     * a folder nobody finds, and this one is meant to be the obvious place to
     * drop a file. {@link #hasAllFiles()} is therefore a REQUIREMENT here, not
     * the optional widening it would have been under Documents/.
     *
     * Deliberately NOT under Download/: that is scrcpy's push target for the
     * desktop's drag-and-drop, and mixing the two would make every guest
     * download look like a file the user dropped from Windows.
     *
     * This is an EXCHANGE folder, not a work directory. Shared storage is
     * mounted noexec, has no file locking, and no symlinks — so a binary, a
     * git repo or anything taking a lockfile does not belong in here. The UI
     * says so rather than letting the user find out.
     */
    static File sharedDir() {
        return new File(Environment.getExternalStorageDirectory(), SHARED_NAME);
    }

    /**
     * Where the folder used to live, for one-time migration.
     *
     * The first cut put it at Documents/LinuxOnDeX precisely because that
     * needed no permission. Moving it to the top of internal storage would
     * otherwise strand whatever the user had already put in there.
     */
    private static File legacySharedDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), SHARED_NAME);
    }

    /**
     * Create the shared folder and say whether it is actually usable.
     *
     * Called from {@link #scriptEnv} — in the very process that is about to
     * fork proot, so a directory created here is guaranteed visible to it.
     * Best-effort in the strict sense: a false answer costs the guest a folder
     * and nothing else. linux-rt.sh re-creates and re-tests before it binds, so
     * a folder the user deletes from Files heals on the next launch.
     */
    static boolean ensureSharedDir() {
        File dir = sharedDir();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                // Almost always the missing grant: a top-level name on shared
                // storage is EACCES without it. Say which, so the log answers
                // the question instead of raising it.
                DexLog.warn("linux", "shared folder: cannot create " + dir
                        + (hasAllFiles() ? "" : " — no all-files access yet"));
                return false;
            }
            migrateLegacyShared(dir);
            return dir.canWrite();
        } catch (Exception e) {
            DexLog.warn("linux", "shared folder: " + dir + " is unusable", e);
            return false;
        }
    }

    /**
     * Move anything left in the old Documents/LinuxOnDeX into the new folder,
     * once, then take the empty directory away.
     *
     * Entirely best-effort: a rename that fails leaves the file exactly where
     * it was, which is a worse folder but not a lost file. Nothing here may
     * throw — it runs on the path that starts the container.
     */
    private static void migrateLegacyShared(File dir) {
        File old = legacySharedDir();
        if (!old.isDirectory() || old.equals(dir)) return;
        try {
            File[] kids = old.listFiles();
            if (kids == null) return;
            int moved = 0;
            for (File kid : kids) {
                File dest = new File(dir, kid.getName());
                // Never clobber: a name that already exists in the new folder
                // is the user's current copy, and the stale one is not.
                if (dest.exists()) continue;
                if (kid.renameTo(dest)) moved++;
            }
            // Only when it actually emptied — delete() refuses otherwise, but
            // saying so out loud beats a silent no-op.
            boolean gone = old.delete();
            if (moved > 0 || gone) {
                DexLog.step("linux", "shared folder: migrated " + moved
                        + " item(s) from " + old + (gone ? ", old folder removed" : ""));
            }
        } catch (Exception e) {
            DexLog.warn("linux", "shared folder: migration from " + old + " failed", e);
        }
    }

    /**
     * The shared folder in an Android file manager, ready to start, or null when
     * nothing on this phone will open it. The desktop's tile and LinuxOnDroid's
     * home screen both open it through here; each adds only how its own window
     * is placed.
     *
     * Named through ExternalStorageProvider rather than as a {@code file://}
     * path: the app holds no storage permission by default (the folder
     * deliberately needs none to be opened), so a file URI would open a manager
     * that then shows nothing.
     *
     * The handler is RESOLVED rather than left implicit, for the reason
     * TransferHud records: more than one app answers this on a Samsung phone,
     * and the system chooser, launched into a freeform window on a secondary
     * display, opens and closes again without ever showing anything. With no
     * concrete handler the caller says where the folder is instead of firing an
     * intent that would vanish. (LinuxOnDroid can see these handlers at all
     * only through the {@code <queries>} entry in its manifest.)
     */
    static Intent sharedFolderIntent(Context ctx) {
        Uri uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + SHARED_NAME);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName target = null;
        try {
            ComponentName fallback = null;
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(intent, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if ("android".equals(pkg)) continue;
                ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                // NOT the head of the list, unlike TransferHud's version of
                // this: that intent carries no URI, and this one carries a
                // document URI that MANAGE_DOCUMENTS protects. We cannot grant
                // read on a URI we do not own, so handing it to whichever app
                // happens to answer first opens a window onto nothing. The
                // documents UI and Samsung's My Files are the two that hold it.
                if (pkg.contains("documentsui") || pkg.contains("myfiles")) {
                    target = cn;
                    break;
                }
                if (fallback == null) fallback = cn;
            }
            if (target == null) target = fallback;
        } catch (Exception e) {
            DexLog.warn("linux", "cannot resolve a file manager for the shared folder", e);
        }
        DexLog.step("linux", "open shared folder " + sharedDir() + " → "
                + (target != null ? target.flattenToShortString() : "no handler"));
        return target == null ? null : intent.setComponent(target);
    }

    /**
     * Tell MediaStore about the shared folder.
     *
     * Not decoration: Android's USB/MTP object list and the Files index are
     * served from MediaStore's DATABASE, not from the real filesystem the way
     * the documents provider is — so a file the guest wrote, and the folder
     * itself while it is still empty, do not appear over USB at all until
     * something scans them.
     *
     * Made SYNCHRONOUS on purpose, with the latch. The scanner API is
     * asynchronous, and the natural call site — the end of a Linux session —
     * runs in the :linux process, which has no other component and is reaped
     * the moment the service stops, so a scan left in flight there is simply
     * cut off. (MediaStore.scanFile would block on its own, but it is @hide.)
     * The timeout is a backstop: a scan that never calls back must not hold a
     * shutdown open.
     *
     * Never call this on the main thread.
     */
    static void scanShared(Context ctx) {
        File dir = sharedDir();
        if (!dir.isDirectory()) return;
        try {
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{dir.getAbsolutePath()}, null,
                    (path, uri) -> done.countDown());
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                DexLog.step("linux", "shared folder: media scan did not report back");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            DexLog.warn("linux", "shared folder: media scan failed", e);
        }
    }

    /**
     * Does the app hold "All files access"?
     *
     * REQUIRED, now that the folder sits at the top of internal storage: a
     * top-level name there cannot even be CREATED without it, so ungranted
     * there is no shared folder at all — see {@link #sharedDir()}.
     *
     * It is also what makes the inbound direction work. A file another app, an
     * adb push or MTP put in the folder belongs to a different package, and
     * ungranted scoped storage answers our open() with EACCES
     * (checkIfFileOpenIsPermitted) — not a missing directory entry, an
     * unreadable file. The guest could not read it even if the folder existed.
     */
    static boolean hasAllFiles() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Environment.isExternalStorageManager();
    }

    /** {@link #sharedFolderOffered}'s answer; the manifest cannot change under a live process. */
    private static volatile Boolean sharedOffered;

    /**
     * Does this build offer the shared folder at all?
     *
     * Not every build does. LinuxOnDroid's Play build ships WITHOUT
     * MANAGE_EXTERNAL_STORAGE (linuxapp/src/release/AndroidManifest.xml removes
     * it): Play allows "All files access" only for apps whose core purpose is
     * file management, backup, antivirus and the like, and a Linux container is
     * none of them. Without that permission the folder can never even be
     * created (see {@link #sharedDir()}), so a build that does not request it
     * must not offer it: no tile that leads to a grant Settings cannot show, no
     * "Your files, both ways" on the home screen, and no bind into the guest of
     * a folder another app made and this one cannot write.
     *
     * Read from this APK's own merged manifest, so the answer follows whatever
     * the build put in it and nothing has to be kept in step by hand. Any
     * build that declares the permission behaves exactly as before.
     *
     * Offered only when the manifest has been read and says so. A lookup that
     * fails (it should not, for our own package) answers no, and is not
     * remembered, so the next call reads again: a no that is wrong costs a
     * build that has the folder its tile until then, where a yes that is
     * wrong would send a Play build's user to a grant Settings cannot show.
     */
    static boolean sharedFolderOffered(Context ctx) {
        Boolean known = sharedOffered;
        if (known != null) return known;
        String[] asked;
        try {
            asked = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                    android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        } catch (Exception e) {
            DexLog.warn("linux", "cannot read this package's permissions; no shared folder for now", e);
            return false;
        }
        boolean offered = false;
        if (asked != null) {
            for (String p : asked) {
                if ("android.permission.MANAGE_EXTERNAL_STORAGE".equals(p)) {
                    offered = true;
                    break;
                }
            }
        }
        sharedOffered = offered;
        return offered;
    }

    /** noVNC over websockify; the guest binds it on loopback only. */
    static final int VNC_PORT = 6080;

    // ── locations (all app-owned) ──

    /** Root of the whole feature in the app's private storage. */
    static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "linux");
    }

    /**
     * Set while the user has explicitly uninstalled Linux.
     *
     * A SIBLING of the container, never a file inside it: uninstall deletes
     * that whole tree, so a marker kept in there would be deleted along with
     * the thing it exists to record.
     *
     * It has to exist at all because the desktop provisions on every launch
     * ({@link LauncherActivity} kicks it three seconds in). Without a
     * remembered "the user asked for this storage back", an uninstall would be
     * silently undone — and the ~1.5 GB it just freed downloaded again — by the
     * next time the launcher started. Opening Linux clears it, which is the
     * way back in; see LauncherActivity.launchLinux.
     */
    private static File uninstalledMarker(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-uninstalled");
    }

    static boolean isUninstalled(Context ctx) {
        return uninstalledMarker(ctx).exists();
    }

    static void setUninstalled(Context ctx, boolean on) {
        File f = uninstalledMarker(ctx);
        if (!on) {
            f.delete();
            return;
        }
        try {
            f.createNewFile();
        } catch (Exception e) {
            // Losing the marker means Linux quietly reinstalls itself later,
            // which is confusing rather than harmful — but say so, because the
            // storage coming back is exactly the symptom someone would report.
            DexLog.warn("linux", "uninstall marker: " + e);
        }
    }

    /**
     * Set from the moment an uninstall is asked for until its wipe is done
     * ({@link LinuxService#uninstall(Context)} sets it, the service clears it
     * once the uninstall marker is down). A sibling of the container like the
     * uninstall marker, for the same reason.
     *
     * The wipe is queued behind whatever the service is doing, and an install
     * has to be stopped before it can begin, so "asked" and "done" can be a
     * while apart. This is how a screen tells them apart, across its own
     * restarts too, instead of saying the storage is free the moment it asked.
     * It also holds provisioning off ({@link #needsProvision}), and a service
     * that starts with it set finishes the removal before anything else: an
     * uninstall the process died in the middle of is still an uninstall.
     */
    private static File removingMarker(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-removing");
    }

    static boolean isRemoving(Context ctx) {
        return removingMarker(ctx).exists();
    }

    static void setRemoving(Context ctx, boolean on) {
        File f = removingMarker(ctx);
        if (!on) {
            f.delete();
            return;
        }
        try {
            f.createNewFile();
        } catch (Exception e) {
            DexLog.warn("linux", "removing marker: " + e);
        }
    }

    /**
     * What the last removal could not delete, while the removing marker is
     * still down for it: "&lt;bytes&gt; &lt;entries&gt; &lt;first error&gt;". The marker
     * clears only once a wipe has left nothing, so a removal that fails stays
     * a removal, and this is what tells the screens it is not still under way
     * but stopped, and how much it left. Cleared as the next attempt begins.
     */
    private static File removeFailedMarker(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-remove-failed");
    }

    /** A removal that stopped with things left; null when the last one did not. */
    static final class RemoveFailure {
        long bytes;
        int entries;
        String why = "";
    }

    static RemoveFailure removeFailure(Context ctx) {
        String s = readText(removeFailedMarker(ctx)).trim();
        if (s.isEmpty()) return null;
        String[] p = s.split(" ", 3);
        RemoveFailure f = new RemoveFailure();
        try {
            f.bytes = Long.parseLong(p[0]);
            f.entries = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        } catch (NumberFormatException e) {
            // still a failure, of unknown size
        }
        if (p.length > 2) f.why = p[2];
        return f;
    }

    static void setRemoveFailure(Context ctx, long bytes, int entries, String why) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(removeFailedMarker(ctx))) {
            out.write((bytes + " " + entries + " " + (why == null ? "" : why.replace('\n', ' ')))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            DexLog.warn("linux", "remove-failed marker: " + e);
        }
    }

    static void clearRemoveFailure(Context ctx) {
        removeFailedMarker(ctx).delete();
    }

    /**
     * This build is LinuxOnDroid, the app that opens on its own home screen,
     * and not the desktop. What decides how a screen shared by both points the
     * user somewhere: "right-click Linux" means nothing on a phone with no
     * desktop. Asked of the manifest, which is the one file that makes the two
     * apps different (linuxapp/build.gradle).
     */
    static boolean hasHomeScreen(Context ctx) {
        try {
            ctx.getPackageManager().getActivityInfo(
                    new android.content.ComponentName(ctx, LinuxHomeActivity.class), 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Is there a container to remove? What the Uninstall offer keys off.
     *
     * The directory is the honest test rather than {@code state.env}: a wiped
     * container has no state file, and a half-finished one has a state file
     * saying anything at all.
     */
    static boolean isInstalled(Context ctx) {
        return !isUninstalled(ctx) && root(ctx).exists();
    }

    /** The extracted native libraries — the one dir an app may exec from. */
    static String nativeDir(Context ctx) {
        return ctx.getApplicationInfo().nativeLibraryDir;
    }

    static File prootBin(Context ctx) {
        return new File(nativeDir(ctx), "libproot.so");
    }

    static File setupScript(Context ctx) {
        return new File(root(ctx), "linux-setup.sh");
    }

    static File rtScript(Context ctx) {
        return new File(root(ctx), "linux-rt.sh");
    }

    /** The window session's runtime: Linux apps as windows of their own (doc/linux-windows.md). */
    static File winScript(Context ctx) {
        return new File(root(ctx), "linux-win.sh");
    }

    static File rootfsTarball(Context ctx) {
        return new File(root(ctx), "linux-rootfs.tar.gz");
    }

    // ── the rootfs pin, per device ABI ──

    /** True when this device's primary ABI has a rootfs we know how to fetch. */
    static boolean abiSupported() {
        return rootfsUrl() != null;
    }

    /** Ubuntu 24.04 base tarball URL for this device, or null if unsupported. */
    static String rootfsUrl() {
        switch (primaryAbi()) {
            case "arm64-v8a":
                return "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/"
                        + "ubuntu-base-24.04.4-base-arm64.tar.gz";
            case "x86_64":
                return "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/"
                        + "ubuntu-base-24.04.4-base-amd64.tar.gz";
            default:
                return null;
        }
    }

    /** SHA-256 of {@link #rootfsUrl()}; the download is rejected unless it matches. */
    static String rootfsSha256() {
        switch (primaryAbi()) {
            case "arm64-v8a":
                return "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
            case "x86_64":
                return "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58";
            default:
                return null;
        }
    }

    static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis[0] : "";
    }

    // ── the environment the scripts run under ──

    /**
     * The env that points the (path-agnostic) scripts at this app's layout:
     * the private root, proot + its loaders from the native-lib dir, and the
     * app's own external files dir as the guest's /root/storage.
     */
    static Map<String, String> scriptEnv(Context ctx) {
        String nd = nativeDir(ctx);
        Map<String, String> env = new HashMap<>();
        env.put("LINUX_ROOT", root(ctx).getAbsolutePath());
        env.put("LINUX_PROOT", nd + "/libproot.so");
        env.put("PROOT_LOADER", nd + "/libloader.so");
        env.put("PROOT_LOADER_32", nd + "/libloader32.so");
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) env.put("LINUX_STORAGE", ext.getAbsolutePath());
        // The user-visible half. Only handed over when it is actually usable:
        // linux-rt.sh treats an unset LINUX_SHARED as "no shared folder" and
        // skips the bind, which matters because proot fails FATALLY on a bad
        // -b source — an unusable path here would stop the container starting
        // rather than cost it a folder. Never in a build that offers no shared
        // folder (sharedFolderOffered): the folder may still exist, made by the
        // desktop build, and binding one this app cannot write would hand the
        // guest a folder that fails on every save.
        boolean offered = sharedFolderOffered(ctx);
        if (offered && ensureSharedDir()) {
            env.put("LINUX_SHARED", sharedDir().getAbsolutePath());
        }
        // Whether this build has the folder at all, which LINUX_SHARED cannot
        // say: it is also unset in a build that has one but not yet the grant.
        // linux-setup.sh writes the file manager's bookmark to the folder only
        // where it can ever be there (setup_share), and takes it out where it
        // cannot. Always set, so an unset value keeps meaning a bare shell run.
        env.put("LINUX_SHARED_OFFERED", offered ? "1" : "0");
        // The chooser's tick list. ALWAYS set, and never to the empty string:
        // the script treats an UNSET LINUX_APPS as DEFAULT_APPS so that a bare
        // shell run still builds the guest this app always built, and an empty
        // value would be indistinguishable from "the user ticked nothing".
        // That is what NO_APPS is for.
        env.put("LINUX_APPS", apps(ctx));
        return env;
    }

    // ── reading the current state off disk (no daemon) ──

    /** A snapshot of {@code state.env} plus live runtime facts. */
    static final class Status {
        int version;
        /** Feature level the guest was last provisioned to; 0 before this existed. */
        int features;
        String phase = "none";
        int pct;
        String msg = "-";
        /**
         * The step the setup script is on — a chooser app id, or
         * {@code desktop} — and "" when it is between steps or not running.
         * What the install screen hangs Skip and Restart off; see
         * {@link #isSkippableStep} and {@link #isRestartableStep}. On an
         * error it names the step that failed.
         */
        String step = "";
        /** How far through {@link #step} it is, 0-100, or -1 when nothing says. */
        int stepPct = -1;
        boolean running;
        /**
         * pid of the live runtime — linux-rt.sh's session leader, which is
         * also its process-group id and proot's parent. 0 when nothing is up;
         * a change means the runtime was replaced.
         */
        int rtPid;
        int port = VNC_PORT;
        String pass = "-";
        /**
         * The last session ended on its own — the user logged out, or it fell
         * over. The viewer starts a runtime whenever none is running, so
         * without this a logout silently booted a fresh desktop the instant the
         * old one went away, which is not what "log out" means.
         */
        boolean sessionEnded;
        /**
         * That session's exit status, straight from {@code xfce4-session}
         * through proot: 0 for a logout, anything else for a session that
         * broke. It picks the wording, nothing more — {@link #sessionEnded} is
         * what actually stops the restart. Duration used to stand in for this
         * and was simply wrong: logging out five seconds after logging in is
         * not a crash.
         */
        int sessionExit;
    }

    /**
     * Is there provisioning work to do at all?
     *
     * Asked by the CALLER, before a foreground service is anywhere near this —
     * see LinuxService.provision for why that distinction cost a desktop.
     * "Done" means built from this payload, finished, and carrying this build's
     * features.
     */
    static boolean needsProvision(Context ctx) {
        // An explicit uninstall outranks every test below. The user asked for
        // the storage back; provision-on-launch would otherwise hand them the
        // download again three seconds after the next desktop start.
        if (isUninstalled(ctx)) return false;
        // Nor while one is on its way: the wipe is queued, and an install
        // started now would be built only to be deleted.
        if (isRemoving(ctx)) return false;
        // Nothing may be provisioned until the user has said what to install.
        // The desktop kicks this three seconds into every launch, so without
        // the gate the download would start behind a question that has not
        // been asked — and then the chooser would be choosing for a container
        // that was already being built.
        if (needsAppChoice(ctx)) return false;
        Status st = readStatus(ctx);
        if (st.version != PAYLOAD_VERSION
                || !"ready".equals(st.phase)
                || st.features < FEATURE_LEVEL) {
            return true;
        }
        // Remote access ends in an sshd inside the guest, and a guest built
        // before linux-setup.sh learned setup_ssh has none. Asked ONLY while
        // remote access is on: that is what lets this be a condition rather
        // than a FEATURE_LEVEL bump, which would have re-run the setup script
        // on every container out there to install a server most of them will
        // never use. Fresh installs get it anyway — setup_ssh is unconditional
        // in the script — so switching remote access on there is instant.
        // Settled like VS Code's install: after three failures at this level
        // it stops asking, or every launch would rebuild the guest — and hold
        // the desktop back while it did — for as long as remote access is on.
        if (LinuxRemote.isEnabled(ctx) && !LinuxRemote.sshdInstalled(ctx)
                && !LinuxRemote.sshdGaveUp(ctx)) return true;
        // A selection that no longer matches what the guest was built with —
        // the chooser is reachable again from the Linux tile, and a guest that
        // is otherwise `ready` would never run the setup script again to
        // honour a newly ticked app. Compared against what the SCRIPT echoed
        // back, not against a flag we set ourselves, so a run that died before
        // finishing is not mistaken for one that did.
        if (!apps(ctx).equals(appsBuilt(ctx))) return true;
        // VS Code was not asked for, so its stamp says nothing about whether
        // there is work left. Without this the test below would read a missing
        // stamp as an unsettled install and provision on every single launch.
        if (!wantsApp(ctx, "code")) return false;
        // An optional install that has neither succeeded nor been given up on
        // at THIS feature level. Without it the guest reaches `ready` and
        // provisioning never runs again — so VS Code's "it retries on the next
        // launch" was never true. The level matters as much as the stamp: when
        // the install method changed underneath a settled stamp (the .deb gave
        // way to the tarball) the phase stayed skipped and the new method never
        // ran. The script settles the stamp either way after a few attempts, so
        // this cannot loop.
        return parseInt(readText(new File(root(ctx), ".stamp-vscode")).trim())
                < FEATURE_LEVEL;
    }

    /** The phases of an install that is still on its way (state.env's PHASE). */
    static boolean isInstallPhase(String phase) {
        switch (phase == null ? "" : phase) {
            case "pushing":
            case "extracting":
            case "configuring":
            case "apt-update":
            case "installing-desktop":
                return true;
            default:
                return false;
        }
    }

    /**
     * Was an install running when this app's processes last died?
     *
     * state.env says so by still naming a phase on the way: nothing that dies
     * to SIGKILL gets to write anything else. Asked right after the three
     * things that end every process of the app without asking it — the
     * desktop app's clean-up, a reboot, an update (RemoteResumeReceiver) — so
     * that an install they cut off picks itself up there and then instead of
     * at the next desktop, which is what turned a long step into one that
     * never finished: every session that ended killed it, and every one that
     * started began it again.
     */
    static boolean installInterrupted(Context ctx) {
        if (isUninstalled(ctx) || needsAppChoice(ctx)) return false;
        return isInstallPhase(readStatus(ctx).phase);
    }

    /**
     * A step the install screen can Skip: one of the chooser's own apps.
     * Skipping one is unticking it (LinuxService.skipStep), so the chooser is
     * also how it comes back.
     */
    static boolean isSkippableStep(String step) {
        if (step == null) return false;
        for (String id : OPTIONAL_APPS) {
            if (id.equals(step)) return true;
        }
        return false;
    }

    /**
     * A step the install screen can Restart: every one Skip can drop, and the
     * desktop itself, which can be started over but never done without.
     */
    static boolean isRestartableStep(String step) {
        return isSkippableStep(step) || "desktop".equals(step);
    }

    /**
     * Ask the next setup run to start {@code step} over. linux-setup.sh reads
     * this, deletes it, and drops the step's unfinished downloads (redo_clean)
     * before running it again.
     *
     * Inside the container rather than beside it like the tick list: a reset
     * wipes it together with everything it could have applied to.
     */
    static void requestStepRestart(Context ctx, String step) {
        try (java.io.FileOutputStream out =
                     new java.io.FileOutputStream(new File(root(ctx), "step.redo"))) {
            out.write(step.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            DexLog.warn("linux", "could not record the step restart", e);
        }
    }

    /** Marker linux-rt.sh drops when a session ends by itself; see Status. */
    static File rtExit(Context ctx) {
        return new File(root(ctx), "rt.exit");
    }

    /**
     * The runtime's lock file ({@link RuntimeLock}): held by the {@code :linux}
     * process for exactly as long as a desktop it spawned is alive, and dropped
     * by the kernel the moment that process dies.
     *
     * rt.pid alone cannot say whether a desktop is up. A runtime killed along
     * with its process (an update, a force-stop, Android reclaiming memory)
     * leaves rt.pid behind, and any later start of the service (remote access
     * coming back, say) brings a {@code :linux} process up again, possibly
     * with the very same pid. The lock is the one answer that cannot outlive
     * the runtime. Beside the container rather than in it: a wipe must not
     * delete the file a live holder has locked.
     */
    static File rtLock(Context ctx) {
        return new File(ctx.getFilesDir(), "linux-rt.lock");
    }

    /**
     * A desktop is running: rt.pid is there and the runtime's lock is held by
     * the process that spawned it. For other processes only; {@code :linux}
     * knows its own runtime ({@link LinuxService#runtimeAlive}) and must never
     * open the lock file (see RuntimeLock).
     *
     * @return null when the lock cannot be asked about
     */
    static Boolean desktopRunning(Context ctx, Status st) {
        if (!st.running) return false;
        return RuntimeLock.heldElsewhere(rtLock(ctx));
    }

    /**
     * The head of the runtime log, for narrating startup. The milestones the
     * connecting screen watches for (Xvnc up, websockify listening) all print
     * near the top, so the first chunk is enough.
     */
    static String rtLog(Context ctx) {
        return readText(new File(root(ctx), "rt.log"));
    }

    /** Read {@code state.env}, {@code rt.pid} and {@code vncpass} — all files. */
    static Status readStatus(Context ctx) {
        File dir = root(ctx);
        Status st = new Status();
        for (String line : readText(new File(dir, "state.env")).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (val.isEmpty()) continue;
            switch (key) {
                case "VERSION": st.version = parseInt(val); break;
                case "FEATURES": st.features = parseInt(val); break;
                case "PHASE": st.phase = val; break;
                case "PCT": st.pct = parseInt(val); break;
                case "MSG": st.msg = val; break;
                case "STEP": st.step = val; break;
                case "STEPPCT": st.stepPct = parseInt(val); break;
                default: break;
            }
        }
        // "Launched" = the runtime wrote its pid file. We deliberately do NOT
        // stat /proc/<pid> to prove it is still alive: Android hides other
        // processes' /proc from an app even for its own uid, so that check
        // always failed and the window sat at "starting" over a live desktop.
        // A stale pid (runtime died, file left behind) self-heals — the viewer
        // fails to connect, Retry stops+restarts — and LinuxService always
        // clears rt.pid before a fresh start, so the value is current in the
        // normal case. rtPid still identifies WHICH runtime, to catch a swap.
        int pid = parseInt(readText(new File(dir, "rt.pid")).trim());
        if (pid > 0) {
            st.running = true;
            st.rtPid = pid;
        }
        String exit = readText(rtExit(ctx)).trim();
        if (!exit.isEmpty()) {
            st.sessionEnded = true;
            st.sessionExit = parseInt(exit);
        }
        String pass = readText(new File(dir, "vncpass")).trim();
        if (!pass.isEmpty()) st.pass = pass;
        return st;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whole-file read; "" on any error (missing file included). */
    static String readFile(File f) {
        return readText(f);
    }

    private static String readText(File f) {
        if (f == null || !f.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1 << 16)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
