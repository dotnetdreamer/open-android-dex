package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * The Remote access page's "Add a computer": the code a computer types is on
 * the page whenever something listens for it. The tunnel runs for Linux's
 * remote access or for Remote desktop (LinuxRemote.tunnelOn), and Remote
 * desktop's card sends a person here to add the computer that opens this
 * phone's screen: with only Remote desktop on, pairing leaves Linux's remote
 * access off (LinuxService.remotePair), and the code still has to show.
 *
 * <p>Found on the S25, 2026-11-23: Remote desktop on, Linux's remote access off,
 * "Add a computer" showed no code at all, so no computer could be added.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {PageBarTest.OnDisplay.class, DeskGpuShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class RemoteAccessPairingTest {

    private static final String CODE = "K7QM29XV";
    private Context app;

    @Before
    public void phone() throws Exception {
        DeskTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        // The tunnel's daemon is there (LinuxRemote.available), as in every build that has the page.
        java.io.File lib = new java.io.File(app.getFilesDir().getParentFile(), "lib");
        DeskTestKit.write(new java.io.File(lib, "libdexremote.so"), "");
        app.getApplicationInfo().nativeLibraryDir = lib.getAbsolutePath();
    }

    @After
    public void putBack() {
        PhoneRemote.setEnabled(app, false);
        LinuxRemote.setEnabled(app, false);
        LinuxRemote.writePairing(app, "", "", 0, "", "");
    }

    private static boolean shows(Activity a, String text) {
        for (View v : DeskTestKit.all(a.getWindow().getDecorView())) {
            if (v instanceof TextView && v.isShown() && text.contentEquals(((TextView) v).getText())) return true;
        }
        return false;
    }

    /** The page with a code open for a computer, as addComputer leaves it; whether the code shows. */
    private boolean codeShown() throws Exception {
        LinuxRemote.writePairing(app, "open", CODE, System.currentTimeMillis() / 1000 + LinuxRemote.PAIR_TTL_SECONDS, "", "");
        ActivityController<LinuxRemoteActivity> c = Robolectric.buildActivity(LinuxRemoteActivity.class).setup();
        DeskTestKit.settle(600);
        boolean shown = shows(c.get(), LinuxRemote.displayCode(CODE));
        c.pause().stop().destroy();
        return shown;
    }

    @Test
    public void withOnlyRemoteDesktopOn_aComputersCodeShows() throws Exception {
        PhoneRemote.setEnabled(app, true);
        LinuxRemote.setEnabled(app, false);
        assertTrue("the tunnel runs for Remote desktop", LinuxRemote.tunnelOn(app));
        assertTrue("the code a computer types is on the page", codeShown());
    }

    @Test
    public void withLinuxsRemoteAccessOn_aComputersCodeShows() throws Exception {
        LinuxRemote.setEnabled(app, true);
        assertTrue(codeShown());
    }

    @Test
    public void withNothingListening_aLeftoverCodeStaysHidden() throws Exception {
        // A code left behind by a process that died with it on screen means nothing.
        PhoneRemote.setEnabled(app, false);
        LinuxRemote.setEnabled(app, false);
        assertFalse(codeShown());
    }
}
