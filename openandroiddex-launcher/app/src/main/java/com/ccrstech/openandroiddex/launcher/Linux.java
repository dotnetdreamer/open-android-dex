package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

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
    static final int FEATURE_LEVEL = 13;

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

    private static String primaryAbi() {
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
        // rather than cost it a folder.
        if (ensureSharedDir()) env.put("LINUX_SHARED", sharedDir().getAbsolutePath());
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

    /** Marker linux-rt.sh drops when a session ends by itself; see Status. */
    static File rtExit(Context ctx) {
        return new File(root(ctx), "rt.exit");
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
