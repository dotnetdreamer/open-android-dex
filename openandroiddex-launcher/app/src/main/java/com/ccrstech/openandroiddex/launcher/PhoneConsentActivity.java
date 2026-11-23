package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Android's own question, "start sharing your screen?", for Remote desktop,
 * and the answer handed to {@link PhoneRemoteService}. Draws nothing of its
 * own: the system dialog is all there is to see.
 *
 * <p>From Android 14 the question offers a single app as well as the whole
 * screen; Remote desktop is the whole screen, so it asks for the display and
 * the picker is skipped ({@code createConfigForDefaultDisplay}).
 */
public class PhoneConsentActivity extends Activity {

    private static final int REQ = 0x5E;
    private boolean asked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        asked = savedInstanceState != null && savedInstanceState.getBoolean("asked");
        if (asked) return;
        if (PhoneRemoteService.sharing()) {
            finish();
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            Intent ask = Build.VERSION.SDK_INT >= 34
                    ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : mpm.createScreenCaptureIntent();
            startActivityForResult(ask, REQ);
            asked = true;
        } catch (Exception e) {
            DexLog.warn("phone", "cannot ask for screen sharing", e);
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", asked);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ) return;
        if (resultCode == RESULT_OK && data != null) {
            Intent start = new Intent(this, PhoneRemoteService.class)
                    .setAction(PhoneRemoteService.ACTION_START)
                    .putExtra(PhoneRemoteService.EXTRA_CODE, resultCode)
                    .putExtra(PhoneRemoteService.EXTRA_DATA, data);
            try {
                startForegroundService(start);
            } catch (Exception e) {
                DexLog.warn("phone", "cannot start screen sharing", e);
            }
        } else {
            DexLog.step("phone", "screen sharing was not allowed");
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
