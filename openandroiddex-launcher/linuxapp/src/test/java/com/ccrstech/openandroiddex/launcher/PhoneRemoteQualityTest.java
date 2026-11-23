package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The picture Remote desktop sends: the quality's size, then the window's
 * Picture size as a part of that (half the width and the height for Half
 * size), so that each step makes the picture smaller whatever the quality
 * already took off a large screen; and what a window may ask for.
 *
 * <p>The sizes on a real phone, and what they do to click to picture, are
 * test/phone-remote's (doc/phone-remote.md, "Picture size").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PhoneRemoteQualityTest {

    private static int[] picture(String quality, double scale, int w, int h) {
        return PhoneRemote.Quality.of(quality).scaled(PhoneRemote.sizeScale(scale)).pictureFor(w, h);
    }

    @Test
    public void aScreenNoLargerThanTheQualityIsSentWhole() {
        // The Galaxy S25 Ultra as its owner keeps it: 720x1560 (HD+).
        assertArrayEquals(new int[]{720, 1560}, picture(PhoneRemote.MEDIUM, 1, 720, 1560));
        assertArrayEquals(new int[]{720, 1560}, picture(PhoneRemote.HIGH, 1, 720, 1560));
        // Low's longest side is 1280.
        assertArrayEquals(new int[]{590, 1280}, picture(PhoneRemote.LOW, 1, 720, 1560));
    }

    @Test
    public void pictureSizeIsAPartOfTheQualitysPicture() {
        assertArrayEquals(new int[]{540, 1170}, picture(PhoneRemote.MEDIUM, 0.75, 720, 1560));
        assertArrayEquals(new int[]{360, 780}, picture(PhoneRemote.MEDIUM, 0.5, 720, 1560));
        assertArrayEquals(new int[]{240, 518}, picture(PhoneRemote.MEDIUM, 0.333, 720, 1560));
        // Half of Low's 590x1280, not half of the screen.
        assertArrayEquals(new int[]{294, 640}, picture(PhoneRemote.LOW, 0.5, 720, 1560));
    }

    @Test
    public void onALargerScreenEveryStepStillMakesThePictureSmaller() {
        // 1440x3120 (QHD+): Medium already sends 886x1920.
        assertArrayEquals(new int[]{886, 1920}, picture(PhoneRemote.MEDIUM, 1, 1440, 3120));
        assertArrayEquals(new int[]{664, 1440}, picture(PhoneRemote.MEDIUM, 0.75, 1440, 3120));
        assertArrayEquals(new int[]{442, 960}, picture(PhoneRemote.MEDIUM, 0.5, 1440, 3120));
        // High sends the screen's own size, so its steps are the screen's.
        assertArrayEquals(new int[]{720, 1560}, picture(PhoneRemote.HIGH, 0.5, 1440, 3120));
        // Turned: the same picture on its side.
        assertArrayEquals(new int[]{960, 442}, picture(PhoneRemote.MEDIUM, 0.5, 3120, 1440));
    }

    @Test
    public void aWindowAsksForAPartBetweenAQuarterAndAll() {
        assertEquals(1, PhoneRemote.sizeScale(1), 0);
        assertEquals(0.5, PhoneRemote.sizeScale(0.5), 0);
        // Nothing, nonsense or more than all: all of it.
        assertEquals(1, PhoneRemote.sizeScale(0), 0);
        assertEquals(1, PhoneRemote.sizeScale(-1), 0);
        assertEquals(1, PhoneRemote.sizeScale(Double.NaN), 0);
        assertEquals(1, PhoneRemote.sizeScale(2), 0);
        // Less than a quarter: a quarter, the smallest that still reads.
        assertEquals(0.25, PhoneRemote.sizeScale(0.1), 0);
        assertArrayEquals(new int[]{180, 390}, picture(PhoneRemote.MEDIUM, 0.01, 720, 1560));
    }

    @Test
    public void allOfThePictureIsTheQualityItself() {
        PhoneRemote.Quality medium = PhoneRemote.Quality.of(PhoneRemote.MEDIUM);
        assertSame(medium, medium.scaled(1));
        // The bitrate rule reads the picture's own size, the same at every Picture size.
        assertEquals(medium.bitrateFor(360, 780), medium.scaled(0.5).bitrateFor(360, 780));
    }
}
