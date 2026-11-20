package com.ccrstech.openandroiddex.launcher;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Which GPU this "phone" has: LinuxGpu.available() looks for /dev/kgsl-3d0,
 * which a PC does not have.
 */
@Implements(LinuxGpu.class)
public class DeskGpuShadow {

    static boolean available = true;

    @Implementation
    protected static boolean available() {
        return available;
    }
}
