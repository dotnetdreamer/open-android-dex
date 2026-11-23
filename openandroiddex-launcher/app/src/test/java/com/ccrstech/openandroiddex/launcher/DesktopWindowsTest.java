package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every window the desktop opens wears its bar, keeps clear of it, and can be
 * found again from the taskbar and the Task Manager.
 *
 * A window has to be registered in several places for that (CaptionService's
 * isDesktopTask, OwnWindows, addOwnWindowTiles' two chains, the Task
 * Manager's ownRows), and missing one is silent. The Web viewer was missing
 * from all of them until 2026-11-19: on One UI 17 it opened with no title bar
 * at all, so no Back and no ✕, and it was on neither the taskbar nor the Task
 * Manager.
 *
 * So the windows are read from the manifest, the way Android knows them: an
 * activity in a task of its own, drawn as an ordinary window (not one of the
 * see-through detours, nor the Send card). A window added later without its
 * registrations fails here.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w1280dp-h800dp-mdpi",
        shadows = {DeskDisplayShadow.class, DeskGpuShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class DesktopWindowsTest {

    private static final String PKG = "com.ccrstech.openandroiddex.launcher";
    private static final String WEB = PKG + ".WebActivity";
    /** A Linux app's window: its taskbar tiles are its own (LinuxWindows), one per X window. */
    private static final String LINUX_APP_WINDOW = PKG + ".LinuxWindowActivity";

    private Context app;

    @Before
    public void phone() {
        DeskTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        DeskGpuShadow.available = true;
        ReflectionHelpers.setStaticField(LinuxActivity.class, "live", null);
        openSet().clear();
    }

    @After
    public void down() {
        openSet().clear();                     // static: the next test starts with nothing open
    }

    private static Set<String> openSet() {
        return ReflectionHelpers.getStaticField(OwnWindows.class, "open");
    }

    /** Every desktop window of ours, from the manifest. */
    static List<String> desktopWindows() throws Exception {
        File manifest = new File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml");
        assertTrue("manifest at " + manifest, manifest.isFile());
        String xml = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<activity\\b([\\s\\S]*?)(/>|</activity>)").matcher(xml);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String a = m.group(1);
            String name = attr(a, "name");
            String affinity = attr(a, "taskAffinity");
            String theme = attr(a, "theme");
            if (affinity == null) continue;                     // the desktop's own task
            if (theme != null && (theme.contains("Detour") || theme.contains(".Send"))) continue;
            out.add(name.startsWith(".") ? PKG + name : name);
        }
        return out;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("android:" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String source(String activity) throws Exception {
        File f = new File(System.getProperty("user.dir"),
                "src/main/java/com/ccrstech/openandroiddex/launcher/" + shortName(activity) + ".java");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean desktop(CaptionService cs, String activity) {
        WmClient.Task t = new WmClient.Task();
        t.pkg = PKG;
        t.activity = activity;
        return ReflectionHelpers.callInstanceMethod(cs, "isDesktopTask",
                ClassParameter.from(WmClient.Task.class, t));
    }

    private static String shortName(String activity) {
        return activity.substring(PKG.length() + 1);
    }

    // ── the rule ────────────────────────────────────────────────────────────

    @Test
    public void everyDesktopWindow_wearsABar_keepsClearOfIt_andIsListed() throws Exception {
        List<String> windows = desktopWindows();
        System.out.println("desktop windows in the manifest: " + windows);
        assertTrue(windows.contains(PKG + ".SettingsActivity"));
        assertTrue(windows.contains(WEB));
        assertTrue(windows.contains(LINUX_APP_WINDOW));
        assertFalse("the desktop itself is not one", windows.contains(PKG + ".LauncherActivity"));
        CaptionService cs = Robolectric.buildService(CaptionService.class).get();
        List<String> broken = new ArrayList<>();
        for (String w : windows) {
            String src = source(w);
            boolean bar = !desktop(cs, w);
            boolean listed = src.contains("OwnWindows.opened(this)");
            boolean clear = listed || src.contains("OwnWindows.keepClearOfBar(this)");
            if (!bar) broken.add(shortName(w) + ": no bar");
            if (!clear) broken.add(shortName(w) + ": under its bar");
            if (!listed && !LINUX_APP_WINDOW.equals(w)) broken.add(shortName(w) + ": not on the taskbar");
        }
        assertTrue("every desktop window wears a bar, keeps clear of it and is listed: " + broken,
                broken.isEmpty());
        assertTrue("and the desktop never wears one", desktop(cs, PKG + ".LauncherActivity"));
    }

    // ── the taskbar ─────────────────────────────────────────────────────────

    private ActivityController<LauncherActivity> launcher() throws Exception {
        ActivityController<LauncherActivity> c = Robolectric.buildActivity(LauncherActivity.class).setup();
        DeskTestKit.settle(600);
        return c;
    }

    /**
     * The strip the desktop's taskbar puts open windows in, filled by the real
     * code. A phone's screen has no such strip (Robolectric runs on display 0,
     * where the launcher builds its phone dock), so the test hands it one.
     */
    private static LinearLayout strip(LauncherActivity l) {
        LinearLayout row = new LinearLayout(l);
        ReflectionHelpers.setField(l, "openAppsRow", row);
        ReflectionHelpers.callInstanceMethod(l, "refreshOpenApps");
        return row;
    }

    private static List<String> tileLabels(LinearLayout row) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View icon = ((ViewGroup) row.getChildAt(i)).getChildAt(0);
            CharSequence d = icon.getContentDescription();
            out.add(d == null ? "" : d.toString());
        }
        return out;
    }

    @Test
    public void everyListedWindow_hasATaskbarTile() throws Exception {
        ActivityController<LauncherActivity> c = launcher();
        try {
            List<String> listed = new ArrayList<>(desktopWindows());
            listed.remove(LINUX_APP_WINDOW);
            openSet().addAll(listed);
            List<String> labels = tileLabels(strip(c.get()));
            assertEquals("one tile per open window: " + labels, listed.size(), labels.size());
            assertFalse("every tile is named", labels.contains(""));
            assertEquals("no two tiles named alike", labels.size(), new HashSet<>(labels).size());
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void webViewer_hasATaskbarTile_thatBringsItBack() throws Exception {
        ActivityController<LauncherActivity> c = launcher();
        ActivityController<WebActivity> web = Robolectric.buildActivity(WebActivity.class).setup();
        try {
            DeskTestKit.settle(300);
            LinearLayout row = strip(c.get());
            List<String> labels = tileLabels(row);
            int at = labels.indexOf(app.getString(R.string.wb_label));
            assertTrue("the Web viewer has a tile: " + labels, at >= 0);
            while (shadowOf(c.get()).getNextStartedActivity() != null) {
                // nothing started before the click counts
            }
            row.getChildAt(at).performClick();
            DeskTestKit.settle(100);
            Intent started = shadowOf(c.get()).getNextStartedActivity();
            assertNotNull("the tile opens something", started);
            assertEquals(WEB, started.getComponent().getClassName());
        } finally {
            web.pause().stop().destroy();
            c.pause().stop().destroy();
        }
        assertFalse("closing it takes it off the taskbar", openSet().contains(WEB));
    }

    // ── the Task Manager ────────────────────────────────────────────────────

    /** The name in each of the Task Manager's rows for a window of ours. */
    private static List<TextView> ownRowNames(Activity tm) {
        String desk = tm.getString(R.string.tm_this_desktop);
        List<TextView> out = new ArrayList<>();
        for (View v : DeskTestKit.all(tm.getWindow().getDecorView())) {
            if (!(v instanceof TextView) || !desk.contentEquals(((TextView) v).getText())) continue;
            ViewGroup text = (ViewGroup) v.getParent();
            out.add((TextView) text.getChildAt(0));
        }
        return out;
    }

    @Test
    public void everyListedWindow_hasATaskManagerRow() throws Exception {
        List<String> listed = new ArrayList<>(desktopWindows());
        listed.remove(LINUX_APP_WINDOW);
        openSet().addAll(listed);
        ActivityController<TaskManagerActivity> c = Robolectric.buildActivity(TaskManagerActivity.class).setup();
        try {
            DeskTestKit.settle(600);
            List<String> names = new ArrayList<>();
            for (TextView t : ownRowNames(c.get())) names.add(t.getText().toString());
            assertEquals("a row for each, but the Task Manager itself: " + names, listed.size() - 1, names.size());
            assertEquals("no two named alike", names.size(), new HashSet<>(names).size());
        } finally {
            c.pause().stop().destroy();
        }
    }
}
