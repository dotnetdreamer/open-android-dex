package com.ccrstech.openandroiddex.launcher;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Which GPU this "phone" has: LinuxGpu.available() looks for /dev/kgsl-3d0,
 * which a computer does not have. The switch itself (a file) stays real.
 */
@Implements(LinuxGpu.class)
public class BrowserGpuShadow {

    static boolean available = true;

    @Implementation
    protected static boolean available() {
        return available;
    }
}
