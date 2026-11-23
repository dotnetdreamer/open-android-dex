package com.ccrstech.openandroiddex.launcher;

import android.hardware.display.DisplayManager;
import android.view.Display;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContextImpl;

/**
 * Robolectric gives an activity a base context that is not an activity
 * context, so Context.getDisplay() throws in it ("not associated with a
 * display"). On a phone it answers with the display the window is on. This
 * answers with the default display, where every window in these tests is.
 */
@Implements(className = ShadowContextImpl.CLASS_NAME)
public class DeskDisplayShadow extends ShadowContextImpl {

    @Implementation
    protected Display getDisplay() {
        DisplayManager dm = RuntimeEnvironment.getApplication().getSystemService(DisplayManager.class);
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }
}
