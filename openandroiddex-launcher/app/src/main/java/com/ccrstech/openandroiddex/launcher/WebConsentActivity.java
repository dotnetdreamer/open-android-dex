package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The screen-capture consent, and nothing else.
 *
 * A projection token can only be obtained by an activity putting the system's
 * own dialog on screen, and this activity exists purely to be the one that
 * does it. It owns a TASK for the same reason {@link WidgetDetourActivity}
 * does: an activity started for a result lands in the CALLER's task, so asking
 * from the desktop or from the Web window would put the system dialog inside
 * that window's task — and on a freeform display a dialog joining a task takes
 * the task's bounds with it. Its own taskAffinity keeps the consent entirely
 * out of the shell.
 *
 * On Android 14 and later the dialog otherwise offers "a single app" as well
 * as the whole screen, and a single app is not what a desktop viewer is for —
 * {@link MediaProjectionConfig#createConfigForDefaultDisplay()} asks for the
 * display and skips the app picker.
 */
public class WebConsentActivity extends Activity {

    private static final int REQUEST = 0x5EE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;      // already asked; awaiting the result
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }
        Intent ask;
        if (Build.VERSION.SDK_INT >= 34) {
            ask = mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            ask = mpm.createScreenCaptureIntent();
        }
        try {
            startActivityForResult(ask, REQUEST);
        } catch (Exception e) {
            DexLog.warn("web", "no screen capture dialog on this device", e);
            Toast.makeText(this, getString(R.string.wb_capture_unavailable),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            DexLog.step("web", "screen capture declined");
            Toast.makeText(this, getString(R.string.wb_capture_declined),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // The token goes straight to the service, which must be in the
        // foreground BEFORE it turns it into a projection — that ordering is a
        // platform requirement from Android 14 on, not a preference.
        Intent start = new Intent(this, WebService.class)
                .setAction(WebService.ACTION_START)
                .putExtra(WebService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(WebService.EXTRA_RESULT_DATA, data);
        try {
            startForegroundService(start);
        } catch (Exception e) {
            DexLog.warn("web", "could not start the viewer service", e);
            Toast.makeText(this, getString(R.string.wb_start_failed), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
