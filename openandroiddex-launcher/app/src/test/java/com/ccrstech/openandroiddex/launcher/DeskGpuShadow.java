package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Which GPU this "phone" has: LinuxGpu.offer() looks for /dev/kgsl-3d0, an
 * Xclipse or the VirGL server in nativeLibraryDir, none of which a PC has.
 */
@Implements(LinuxGpu.class)
public class DeskGpuShadow {

    static boolean available = true;

    @Implementation
    protected static LinuxGpu.Offer offer(Context ctx) {
        return available ? LinuxGpu.Offer.DRIVER : LinuxGpu.Offer.NONE;
    }
}
