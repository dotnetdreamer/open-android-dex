package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Linux in the PHONE's own app list — the entry that opens the container
 * without a DeX session, a PC or a cable.
 *
 * Nothing about the Linux feature ever needed the desktop: {@link LinuxService}
 * downloads the rootfs, runs the install and hosts the proot / Xvnc /
 * websockify runtime under this app's own uid, and {@link LinuxActivity} is a
 * WebView onto it that already knows how to size a guest desktop for a phone
 * panel (see its requestStart). The only thing missing was a way IN: the
 * launcher's tile lives on the desktop display, so it is reachable only while
 * the desktop is up. This activity is that way in, and it is the ONLY thing
 * this class does — it starts the window and finishes.
 *
 * Why a trampoline rather than an {@code <activity-alias>} on LinuxActivity,
 * which is the shorter way to put a second icon in a drawer: an alias is its
 * OWN component as far as the window manager is concerned. The activity the
 * task reports (RunningTaskInfo.topActivity, which is what wmd hands
 * CaptionService) would be the alias name, and four places compare that name to
 * {@code LinuxActivity.class.getName()} to decide what a window IS —
 * CaptionService.isDesktopTask (an unrecognised name means "this is the desktop
 * itself", so the window would lose its caption entirely), CaptionService.onClose
 * (the ✕ would remove the task instead of asking first), the taskbar's own-window
 * tiles and the Task Manager. An alias-launched window that later moved to the
 * desktop display — which is exactly what the tile does to it — would carry the
 * alias name into all four. A trampoline keeps ONE identity for the window and
 * costs one class.
 *
 * There is one window either way. LinuxActivity is singleTask on a task
 * affinity of its own, so this start RAISES the open window rather than
 * building a second one — from here, from the desktop's tile and from the
 * notification alike — and one window is one runtime: the service refuses to
 * spawn a second Xvnc while one is up (see its runtimeUp flag), and a second
 * container on a phone is exactly the overload worth never allowing.
 *
 * It owns a task of its own for the reason {@link WidgetDetourActivity} spells
 * out at length: with the package's default affinity it would join the
 * desktop's task and drag the shell around. Nothing is ever drawn here — the
 * Detour theme is a task-shaped hole — and it is excluded from recents, because
 * the task worth returning to is the Linux window's, not this one's.
 */
public class LinuxAppActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open();
    }

    /**
     * singleTask, so a second tap while this one is still finishing arrives
     * here rather than building another instance. Both paths do the same one
     * thing, and doing it twice is harmless: LinuxActivity is singleTask too,
     * so the second start raises the window the first one opened.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        open();
    }

    private void open() {
        // Opening Linux is the way back from an uninstall, and the only one —
        // the same rule LauncherActivity.launchLinux follows, and it has to be
        // here too or this icon would open a window that immediately closes
        // itself (LinuxActivity finishes while the marker is set, because
        // provisioning is deliberately inert there).
        Linux.setUninstalled(this, false);

        // One window, wherever it already is. A start from this task cannot
        // reach the desktop display — it would MOVE the window's task to the
        // phone's, taking a live Ubuntu session off the desktop the user is
        // looking at because they brushed an icon on the phone. Say where it is
        // instead; the desktop's own tile is the way back to it.
        //
        // The question is asked of the WINDOW and not of the session: a desktop
        // can be running with the Linux window open on the phone (this icon
        // opened it, then the user connected), and there the right answer is to
        // raise the window we have, which is exactly what the start below does.
        if (LinuxActivity.isOnDesktopDisplay()) {
            DexLog.step("linux", "app icon: already open on the desktop");
            Toast.makeText(this, getString(R.string.ln_on_desktop), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // No launch bounds and no display: this is the phone's own screen, where
        // a window is the whole panel. FLAG_ACTIVITY_NEW_TASK because the window
        // belongs in ITS task (its own taskAffinity), never in this one — and
        // because this activity is finishing out from under it.
        try {
            startActivity(new Intent(this, LinuxActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("linux", "app icon: cannot open the window", e);
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
