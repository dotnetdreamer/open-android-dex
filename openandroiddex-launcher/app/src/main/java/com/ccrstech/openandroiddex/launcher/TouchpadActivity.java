package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.widget.Toast;

/**
 * What the touchpad's notification opens ({@link TouchpadNotice}): it puts the pad up on
 * the phone's screen ({@link Touchpad}) and is gone before it draws.
 *
 * <p>A trampoline, because the pad is a window and not a screen of its own (Touchpad says
 * why) while a notification that opens an activity is the kind the shade closes itself
 * for — one that sends a broadcast leaves the shade down over the pad it just opened.
 * Finishing at once is also what hands the phone's display back to the app the user had
 * in front: it is the next thing resumed there, and so the display's focused app again.
 *
 * <p>Android 11 and later, as the pad is (its window context, and the daemon calls that
 * find its tasks there). Nothing offers it below that.
 */
public final class TouchpadActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LauncherActivity shell = LauncherActivity.desktopShell();
        int desktop = shell == null ? -1 : shell.displayId();
        if (desktop < 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Touchpad.computerOffers()) {
            // No desktop: the computer's own windows of the phone's apps, as its server offers.
            Touchpad.openForComputer(this);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || desktop <= Display.DEFAULT_DISPLAY) {
            // A notification that outlived its desktop — or the desktop drawn on the
            // phone's own screen, which has the dock's strip instead (DexPointer).
            DexLog.step("touchpad", "no desktop on a display of its own — nothing to be the touchpad of");
            if (desktop < 0) TouchpadNotice.hide(this);
            Toast.makeText(this, getString(R.string.tp_no_desktop), Toast.LENGTH_LONG).show();
        } else {
            Touchpad.open(this, desktop);
        }
        finish();
    }
}
