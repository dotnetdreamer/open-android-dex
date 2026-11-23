package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Which GPU this "phone" has: LinuxGpu.offer() looks for /dev/kgsl-3d0, an
 * Xclipse or the VirGL server in nativeLibraryDir, none of which a computer
 * has. The switch itself (a file) stays real.
 */
@Implements(LinuxGpu.class)
public class BrowserGpuShadow {

    /** The old yes or no: a GPU one of our drivers runs, or none. */
    static boolean available = true;
    /** Set to say exactly what the switch can do; null follows {@link #available}. */
    static LinuxGpu.Offer offer;

    @Implementation
    protected static LinuxGpu.Offer offer(Context ctx) {
        if (offer != null) return offer;
        return available ? LinuxGpu.Offer.DRIVER : LinuxGpu.Offer.NONE;
    }
}
