package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.Arrays;
import java.util.Map;

/**
 * Every window of ours keeps its content below the bar CaptionService draws
 * over its top.
 *
 * On One UI 17 (S25 Ultra) our own windows get no caption window of their
 * own, so the bar is drawn on the app window, and the window daemon's strip
 * publishes no captionBar inset: the first 40 px of each window sat under the
 * bar (the desktop's Settings showed "DeX Settings" cut in half). Each window
 * here is built, a bar is reported over its task the way CaptionService
 * reports one, and its page must move down by exactly the bar and back up
 * when the bar goes.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w1280dp-h800dp-mdpi",
        shadows = {DeskDisplayShadow.class, DeskGpuShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class DesktopBarRoomTest {

    private Context app;

    @Before
    public void phone() {
        DeskTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        DeskGpuShadow.available = true;
        ReflectionHelpers.setStaticField(LinuxActivity.class, "live", null);
    }

    @After
    public void down() {
        reportBars(null);           // static: a failed test must not leave a bar behind
    }

    private static void reportBars(SparseIntArray over) {
        ReflectionHelpers.callStaticMethod(OwnWindows.class, "barsOver",
                ClassParameter.from(SparseIntArray.class, over));
    }

    private static SparseIntArray bar(int taskId, int px) {
        SparseIntArray over = new SparseIntArray();
        over.put(taskId, px);
        return over;
    }

    private static int topOf(View v) {
        int[] at = new int[2];
        v.getLocationInWindow(at);
        return at[1];
    }

    /** What the window set as its content: its top is where the window's page starts. */
    private static View page(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        assertNotNull(a.getClass().getSimpleName() + " has content", content);
        assertTrue(a.getClass().getSimpleName() + " has content", content.getChildCount() > 0);
        return content.getChildAt(0);
    }

    private void keepsClear(Class<? extends Activity> k) throws Exception {
        ActivityController<? extends Activity> c = Robolectric.buildActivity(k).setup();
        DeskTestKit.settle(600);
        Activity a = c.get();
        assertFalse(k.getSimpleName() + " stayed open", a.isFinishing());
        View page = page(a);
        int bare = topOf(page);

        reportBars(bar(a.getTaskId() + 1, 40));
        DeskTestKit.settle(200);
        assertEquals(k.getSimpleName() + ": another window's bar leaves this one alone", bare, topOf(page));

        reportBars(bar(a.getTaskId(), 40));
        DeskTestKit.settle(200);
        assertEquals(k.getSimpleName() + ": the page starts below the bar", bare + 40, topOf(page));

        reportBars(null);                              // the service went: no bar
        DeskTestKit.settle(200);
        assertEquals(k.getSimpleName() + ": back up when the bar goes", bare, topOf(page));

        c.pause().stop().destroy();
        reportBars(bar(a.getTaskId(), 40));            // reaches no closed window
        DeskTestKit.settle(100);
    }

    @Test
    public void desktopSettings_startsBelowTheBar() throws Exception {
        keepsClear(SettingsActivity.class);
    }

    @Test
    public void taskManager_startsBelowTheBar() throws Exception {
        keepsClear(TaskManagerActivity.class);
    }

    @Test
    public void fileTransfer_startsBelowTheBar() throws Exception {
        keepsClear(FileTransferActivity.class);
    }

    @Test
    public void remoteAccess_startsBelowTheBar() throws Exception {
        keepsClear(LinuxRemoteActivity.class);
    }

    @Test
    public void linuxSettings_startsBelowTheBar() throws Exception {
        keepsClear(LinuxSettingsActivity.class);
    }

    @Test
    public void docker_startsBelowTheBar() throws Exception {
        keepsClear(DockerActivity.class);
    }

    @Test
    public void webViewer_startsBelowTheBar() throws Exception {
        keepsClear(WebActivity.class);
    }

    /** The Linux desktop's window: its picture, and so the size Linux is started at, below the bar. */
    @Test
    public void linuxWindow_startsBelowTheBar() throws Exception {
        DeskTestKit.readyContainer(app);
        keepsClear(LinuxActivity.class);
    }

    /**
     * A window opened while its task already has a bar hears no new report:
     * the desktop's Settings recreates itself for a theme change, and the new
     * one must start below the bar all the same.
     */
    @Test
    public void desktopSettings_recreatedUnderABar_startsBelowIt() throws Exception {
        ActivityController<SettingsActivity> c = Robolectric.buildActivity(SettingsActivity.class).setup();
        DeskTestKit.settle(600);
        int bare = topOf(page(c.get()));
        reportBars(bar(c.get().getTaskId(), 40));
        DeskTestKit.settle(200);
        c.recreate();
        DeskTestKit.settle(600);
        assertEquals(bare + 40, topOf(page(c.get())));
        c.pause().stop().destroy();
    }

    /** An X connection that goes nowhere: requests are written to memory and dropped. */
    private static X11 fakeX() throws Exception {
        java.lang.reflect.Constructor<X11> k = X11.class.getDeclaredConstructor(
                java.io.InputStream.class, java.io.OutputStream.class, java.io.Closeable.class,
                X11.FdPassing.class, java.nio.ByteBuffer.class);
        k.setAccessible(true);
        // The server's setup reply: zeros are a vendor of no letters and no pixel
        // formats. Offset 18 is the largest request it takes, in 4-byte units: at 0
        // every request would be refused as too long.
        java.nio.ByteBuffer setup = java.nio.ByteBuffer.allocate(128);
        setup.putShort(18, (short) 0xffff);
        return k.newInstance(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream(), (java.io.Closeable) () -> { }, null, setup);
    }

    /**
     * A Linux app's window. The real LinuxWindowActivity is built around one X
     * window, over an X connection that goes nowhere. Its page must move below
     * the bar, and so must the X window the app draws in, which is placed
     * wherever its view is: down by the bar and shorter by it, so nothing the
     * app draws is under it.
     */
    @Test
    public void linuxAppWindow_startsBelowTheBar() throws Exception {
        LinuxWindows lw = LinuxWindows.get(app);
        LinuxWm wm = new LinuxWm(fakeX(), lw, new LinuxWm.Options());
        LinuxWm.Win win = new LinuxWm.Win(wm, 0x600003, LinuxWm.KIND_WINDOW);
        win.title = "Mousepad";
        Map<Integer, LinuxWindows.Entry> entries = ReflectionHelpers.getField(lw, "entries");
        entries.put(win.id, new LinuxWindows.Entry(wm, win));
        Intent open = new Intent(app, LinuxWindowActivity.class)
                .putExtra(LinuxWindowActivity.EXTRA_XID, win.id);
        ActivityController<LinuxWindowActivity> c =
                Robolectric.buildActivity(LinuxWindowActivity.class, open).setup();
        try {
            DeskTestKit.settle(600);
            LinuxWindowActivity a = c.get();
            assertFalse("the window found its X window", a.isFinishing());
            View page = page(a);
            int bare = topOf(page);
            int[] xBare = win.geometry();
            assertTrue("the X window was placed: " + Arrays.toString(xBare), xBare[3] > 0);

            reportBars(bar(a.getTaskId() + 1, 40));
            DeskTestKit.settle(600);
            assertEquals("another window's bar leaves this one alone", bare, topOf(page));

            reportBars(bar(a.getTaskId(), 40));
            DeskTestKit.settle(600);              // the view reports where it is every 250 ms
            int[] xUnder = win.geometry();
            assertEquals("the page starts below the bar", bare + 40, topOf(page));
            assertEquals("the X window starts below the bar", xBare[1] + 40, xUnder[1]);
            assertEquals("and is shorter by it", xBare[3] - 40, xUnder[3]);
            assertEquals("same width", xBare[2], xUnder[2]);

            reportBars(null);
            DeskTestKit.settle(600);
            assertEquals("back up when the bar goes", bare, topOf(page));
            assertEquals("the X window too", Arrays.toString(xBare), Arrays.toString(win.geometry()));
        } finally {
            win.closed = true;                    // nothing to tell X on the way out
            c.pause().stop().destroy();
            ReflectionHelpers.setStaticField(LinuxWindows.class, "instance", null);
        }
    }
}
