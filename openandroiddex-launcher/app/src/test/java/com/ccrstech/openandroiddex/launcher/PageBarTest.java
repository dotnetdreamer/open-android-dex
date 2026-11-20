package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContextImpl;
import org.robolectric.shadows.ShadowDisplayManager;

/**
 * The top bar Settings and Remote access share (LinuxHomeViews.pageBar).
 *
 * Opened from LinuxOnDroid's home screen, Remote access had no Back of its own
 * (2026-11-20): on the phone's own screen a page needs one. On a desktop display
 * the window's bar has Back already, so there the page shows only its name.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {PageBarTest.OnDisplay.class, DeskGpuShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class PageBarTest {

    /** The display the windows in a test are on: the phone's own, or a desktop's. */
    static int displayId = Display.DEFAULT_DISPLAY;

    @Implements(className = ShadowContextImpl.CLASS_NAME)
    public static class OnDisplay extends ShadowContextImpl {
        @Implementation
        protected Display getDisplay() {
            DisplayManager dm = RuntimeEnvironment.getApplication().getSystemService(DisplayManager.class);
            return dm.getDisplay(displayId);
        }
    }

    private Context app;

    @Before
    public void phone() {
        DeskTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        displayId = Display.DEFAULT_DISPLAY;
    }

    @After
    public void phoneScreenAgain() {
        displayId = Display.DEFAULT_DISPLAY;
    }

    private ActivityController<? extends Activity> open(Class<? extends Activity> page) throws Exception {
        ActivityController<? extends Activity> c = Robolectric.buildActivity(page).setup();
        DeskTestKit.settle(600);
        return c;
    }

    private View back(Activity a) {
        String label = app.getString(R.string.lx_back);
        for (View v : DeskTestKit.all(a.getWindow().getDecorView())) {
            if (v.isShown() && label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) {
                return v;
            }
        }
        return null;
    }

    private static boolean shows(Activity a, String text) {
        for (View v : DeskTestKit.all(a.getWindow().getDecorView())) {
            if (v instanceof TextView && v.isShown() && text.contentEquals(((TextView) v).getText())) return true;
        }
        return false;
    }

    private void backClosesIt(Class<? extends Activity> page, String name) throws Exception {
        ActivityController<? extends Activity> c = open(page);
        assertTrue(page.getSimpleName() + " shows its name", shows(c.get(), name));
        View back = back(c.get());
        assertNotNull(page.getSimpleName() + " has a Back on the phone's own screen", back);
        back.performClick();
        assertTrue(page.getSimpleName() + ": Back closes it", c.get().isFinishing());
        c.pause().stop().destroy();
    }

    private void nameOnly(Class<? extends Activity> page, String name) throws Exception {
        ActivityController<? extends Activity> c = open(page);
        assertTrue(page.getSimpleName() + " shows its name", shows(c.get(), name));
        assertNull(page.getSimpleName() + ": no second Back under the window's bar", back(c.get()));
        assertFalse(c.get().isFinishing());
        c.pause().stop().destroy();
    }

    @Test
    public void remoteAccess_onThePhone_hasBack() throws Exception {
        backClosesIt(LinuxRemoteActivity.class, app.getString(R.string.rm_label));
    }

    @Test
    public void linuxSettings_onThePhone_hasBack() throws Exception {
        backClosesIt(LinuxSettingsActivity.class, app.getString(R.string.ln_settings_title));
    }

    @Test
    public void onADesktop_theWindowsBarHasBack_soThePageShowsOnlyItsName() throws Exception {
        displayId = ShadowDisplayManager.addDisplay("w1280dp-h800dp-mdpi");
        assertNotEquals(Display.DEFAULT_DISPLAY, displayId);
        nameOnly(LinuxRemoteActivity.class, app.getString(R.string.rm_label));
        nameOnly(LinuxSettingsActivity.class, app.getString(R.string.ln_settings_title));
    }
}
