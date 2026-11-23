package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Turns the screen on for a computer that opened the phone while it slept,
 * and goes: nothing is drawn, and the lock screen is left as it is, for
 * whoever knows the PIN to unlock from the computer as they would by hand.
 * Started by {@link PhoneControlService#wake}.
 */
public class PhoneWakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // Long enough for the screen to come on; then the lock screen, or
        // whatever was in front, is what shows.
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 400);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
