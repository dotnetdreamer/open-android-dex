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
 * display"). On a phone the activity's context is one, and getDisplay()
 * answers with the display the window is on: the phone's own, here.
 */
@Implements(className = ShadowContextImpl.CLASS_NAME)
public class BrowserDisplayShadow extends ShadowContextImpl {

    @Implementation
    protected Display getDisplay() {
        DisplayManager dm = RuntimeEnvironment.getApplication().getSystemService(DisplayManager.class);
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }
}
