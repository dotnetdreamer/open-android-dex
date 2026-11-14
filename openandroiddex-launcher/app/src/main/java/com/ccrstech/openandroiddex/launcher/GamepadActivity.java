package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * What the game controller's notification opens ({@link GamepadNotice}): it puts the
 * controller up on the phone's screen ({@link PhoneGamepad}) and is gone before it draws
 * — a trampoline, for {@link TouchpadActivity}'s reasons: the shade closes for an
 * activity, and finishing hands the phone's display back to the app that was in front.
 *
 * <p>Android 11 and later, as the controller is. Nothing offers it below that.
 */
public final class GamepadActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && PhoneGamepad.computerOffers()) {
            PhoneGamepad.openForComputer(this);
        } else {
            // A notification that outlived the computer's offer: this app's process was restarted.
            DexLog.step("gamepad", "no computer offers the controller — nothing for it to play");
            GamepadNotice.hide(this);
            Toast.makeText(this, getString(R.string.gp_no_game), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
