package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;
import android.widget.Toast;

/**
 * The app's icon — on the phone's home screen, and in the home of any other display a
 * launcher runs on (One UI's own DeX launcher, another mirroring app's). What it opens is
 * the desktop, as it always did. What it adds is leaving a desktop that is already
 * running on a display of its own where it is.
 *
 * <p>It used to be LauncherActivity's own MAIN/LAUNCHER entry, and LauncherActivity is
 * singleTask: a start from a launcher on another display finds the desktop that is
 * running and MOVES its task there. The computer's window went black, the desktop turned
 * up on the phone or in another app's window, and the taskbar — an overlay window, which
 * does not travel with the task — stayed behind on the display it left. Both happened in
 * one user's traces: a tap on the phone's home screen, and a tap in One UI's DeX launcher
 * on a display another mirroring app had made.
 *
 * <p>So the icon asks first, as {@link LinuxAppActivity} does for the Linux window: a
 * desktop on a display of its own other than this one stays put, and a toast says where
 * it is. Everything else — no desktop yet, or the phone's own desktop raised from its own
 * screen — is the start the icon always made, from this display.
 *
 * <p>A trampoline and not an activity-alias for the reason LinuxAppActivity gives: an
 * alias is a component of its own to everything that compares names, and the desktop's
 * task must go on reporting LauncherActivity. It owns a task, draws nothing and is out of
 * Recents, like the other trampolines here.
 */
public final class DesktopIconActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open();
    }

    /** singleTask: a second tap while this one is still finishing arrives here. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        open();
    }

    private void open() {
        LauncherActivity shell = LauncherActivity.desktopShell();
        int desktop = shell == null ? -1 : shell.displayId();
        int here = getWindowManager().getDefaultDisplay().getDisplayId();
        if (desktop > Display.DEFAULT_DISPLAY && desktop != here) {
            DexLog.step("launcher", "app icon on display " + here
                    + ": the desktop is running on display " + desktop + " — left there");
            Toast.makeText(this, getString(R.string.lx_desktop_elsewhere), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // NEW_TASK because the desktop belongs in its own task, never in this one — and
        // because this activity is finishing out from under it. Started from here, so it
        // opens on this display: the phone's screen for the phone's icon, as before.
        try {
            startActivity(new Intent(this, LauncherActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            DexLog.warn("launcher", "app icon: cannot open the desktop", e);
        }
        finish();
    }
}
