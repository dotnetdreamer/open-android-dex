package com.ccrstech.openandroiddex.launcher;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;

/**
 * Android's own question for Remote desktop's sound, "allow LinuxOnDroid to
 * record audio?", and nothing of its own: the system dialog is all there is
 * to see ({@link PhoneSound}). Asked from the Settings page, from the
 * notification a computer's ask leaves, or over the phone's screen when
 * control is on. A yes reaches the computer that asked at once.
 */
public class PhoneSoundActivity extends Activity {

    private static final int REQ = 0x61;
    private boolean asked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        asked = savedInstanceState != null && savedInstanceState.getBoolean("asked");
        if (asked) return;
        if (!PhoneSound.supported() || PhoneSound.allowed(this)) {
            done();
            return;
        }
        // A computer watching is told that Android's question is one only the phone may answer.
        PhoneStream.askingAndroid("permission");
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ);
        asked = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", asked);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ) return;
        DexLog.step("phone", PhoneSound.allowed(this) ? "sound was allowed" : "sound was not allowed");
        done();
    }

    private void done() {
        PhoneSound.cancelAsk(this);
        PhoneStream.soundAllowedChanged();
        finish();
        overridePendingTransition(0, 0);
    }
}
