package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A click on a window of ours in the Task Manager brings that window back,
 * whatever state it is in, and whatever the window daemon makes of the request.
 *
 * The row sends CaptionService a restore broadcast. Until 2026-11-19 its
 * receiver looked only among the windows it had minimised, so the row did
 * nothing for a window that was open behind another one; and an un-hide was
 * sent and forgotten, so a SHOW the daemon could not carry out left the window
 * hidden with nothing said.
 *
 * These click the real row and follow it to where a window really moves: the
 * window daemon. The daemon is a fake on its real port (127.0.0.1:7191) with a
 * desktop of its own: SHOW un-hides a task and brings it to the front, FRONT
 * brings one forward, LIST reports where everything is, and each test can make
 * it fail the way a real one can. Everything between the click and the socket
 * is the production code: the row, the broadcast, CaptionService's receiver
 * and WmClient. The fake binds the port first, so a port already in use (the
 * PC app forwards it during a session) fails the test rather than reaching a
 * real phone's daemon.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w1280dp-h800dp-mdpi",
        shadows = {DeskDisplayShadow.class, DeskGpuShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class DesktopRaiseTest {

    private static final String PKG = "com.ccrstech.openandroiddex.launcher";
    private static final String WEB = PKG + ".WebActivity";
    private static final String OTHER_PKG = "com.example.notes";
    private static final int DISPLAY = 2;
    private static final int OTHER_TASK = 41;
    private static final int WEB_TASK = 37;
    private static final int DESK_TASK = 12;

    /** The window daemon's protocol on its real port, over a desktop it keeps; every command is recorded. */
    static final class FakeDaemon implements AutoCloseable {

        static final class Task {
            final int id;
            final int mode;
            final String pkg;
            final String activity;
            boolean visible;

            Task(int id, int mode, boolean visible, String pkg, String activity) {
                this.id = id;
                this.mode = mode;
                this.visible = visible;
                this.pkg = pkg;
                this.activity = activity;
            }
        }

        final ServerSocket server = new ServerSocket();
        final List<String> asked = new CopyOnWriteArrayList<>();
        final LinkedBlockingQueue<String> arrivals = new LinkedBlockingQueue<>();
        /** Topmost first. Guarded by itself. */
        final List<Task> stack = new ArrayList<>();
        volatile String frontReply = "OK";
        /** From which SHOW on (counting from 0) a SHOW is carried out; a working daemon does every one. */
        volatile int showsWorkFrom = 0;
        /** What a SHOW that is not carried out answers: OK for one that fails quietly, ERR for a refusal. */
        volatile String failedShowReply = "OK";
        /** Whether a SHOW also brings the task forward (Android 12 ignores that across a fullscreen app). */
        volatile boolean showRaises = true;
        /** A task closed the moment it is asked for, or -1. */
        volatile int goneOnShow = -1;
        private int shows;

        FakeDaemon() throws IOException {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7191));
            Thread t = new Thread(this::accept, "fake-wmd");
            t.setDaemon(true);
            t.start();
        }

        void add(Task t) {
            synchronized (stack) {
                stack.add(t);
            }
        }

        Task task(int id) {
            synchronized (stack) {
                for (Task t : stack) if (t.id == id) return t;
                return null;
            }
        }

        int topShown() {
            synchronized (stack) {
                for (Task t : stack) if (t.visible) return t.id;
                return -1;
            }
        }

        long count(String prefix) {
            return asked.stream().filter(c -> c.startsWith(prefix)).count();
        }

        private void toFront(Task t) {
            stack.remove(t);
            stack.add(0, t);
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread t = new Thread(() -> serve(s), "fake-wmd-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket s) {
            try (Socket c = s;
                 BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                 PrintWriter out = new PrintWriter(c.getOutputStream(), true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    asked.add(line);
                    arrivals.add(line);
                    out.println(answer(line));
                }
            } catch (IOException ignored) {
                // the client went away
            }
        }

        private String answer(String line) {
            String[] a = line.split(" ");
            synchronized (stack) {
                switch (a[0]) {
                    case "LIST": {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < stack.size(); i++) {
                            Task t = stack.get(i);
                            // TASK ix id display mode actType vis l t r b al at ar ab pkg activity minW minH defMinDp
                            sb.append("TASK ").append(i).append(' ').append(t.id).append(' ').append(DISPLAY)
                                    .append(' ').append(t.mode).append(" 1 ").append(t.visible ? 1 : 0)
                                    .append(" 200 120 980 820 200 160 980 820 ").append(t.pkg).append(' ')
                                    .append(t.activity).append(" -1 -1 -1\n");
                        }
                        return sb.append("END").toString();
                    }
                    case "SHOW": {
                        int n = shows++;
                        Task t = task(Integer.parseInt(a[2]));
                        if (t == null) return "ERR no task " + a[2];
                        if (t.id == goneOnShow) {
                            stack.remove(t);
                            return "OK";
                        }
                        if (n < showsWorkFrom) return failedShowReply;
                        t.visible = true;
                        if (showRaises) toFront(t);
                        return "OK";
                    }
                    case "FRONT": {
                        Task t = task(Integer.parseInt(a[2]));
                        if (t == null) return "ERR no task " + a[2];
                        if (frontReply.startsWith("OK")) toFront(t);
                        return frontReply;
                    }
                    default:
                        return "OK";
                }
            }
        }

        /**
         * Waits for a command that starts with {@code prefix}; null if none came.
         * Real time, through the queue: this class is instrumented, so its own
         * System.currentTimeMillis() would be Robolectric's paused clock.
         */
        String await(String prefix, long seconds) throws InterruptedException {
            for (String seen : asked) if (seen.startsWith(prefix)) return seen;
            for (long left = TimeUnit.SECONDS.toMillis(seconds); left > 0; left -= 100) {
                String next = arrivals.poll(100, TimeUnit.MILLISECONDS);
                if (next != null && next.startsWith(prefix)) return next;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private Context app;
    private FakeDaemon daemon;
    private CaptionService service;
    private BroadcastReceiver restore;
    private WmClient client;

    @Before
    public void phone() throws Exception {
        DeskTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        DeskGpuShadow.available = true;
        openSet().clear();
        daemon = new FakeDaemon();
        // The service as onServiceConnected leaves it for this: its daemon client, the
        // desktop's display, and the restore receiver registered the way it registers it.
        service = Robolectric.buildService(CaptionService.class).get();
        client = new WmClient();
        ReflectionHelpers.setField(service, "wm", client);
        ReflectionHelpers.setField(service, "desktopDisplayId", DISPLAY);
        restore = ReflectionHelpers.getField(service, "restoreReceiver");
        service.registerReceiver(restore, new IntentFilter(LauncherActivity.ACTION_RESTORE),
                Context.RECEIVER_NOT_EXPORTED);
    }

    @After
    public void down() throws Exception {
        try {
            service.unregisterReceiver(restore);
        } catch (RuntimeException ignored) {
            // never registered: the port was taken
        }
        if (client != null) client.shutdown();
        if (daemon != null) daemon.close();
        openSet().clear();
    }

    private static Set<String> openSet() {
        return ReflectionHelpers.getStaticField(OwnWindows.class, "open");
    }

    /** Topmost first: a notes app, the Web viewer (shown or hidden), the desktop itself. */
    private void desktop(boolean webShown) {
        daemon.add(new FakeDaemon.Task(OTHER_TASK, 5, true, OTHER_PKG, OTHER_PKG + ".MainActivity"));
        daemon.add(new FakeDaemon.Task(WEB_TASK, 5, webShown, PKG, WEB));
        daemon.add(new FakeDaemon.Task(DESK_TASK, 1, true, PKG, PKG + ".LauncherActivity"));
    }

    /** What a minimise leaves in the service: the task, by package and activity. */
    private void minimisedByTheService() throws Exception {
        Class<?> min = Class.forName(CaptionService.class.getName() + "$Min");
        Object m = ReflectionHelpers.callConstructor(min,
                ClassParameter.from(String.class, PKG), ClassParameter.from(String.class, WEB),
                ClassParameter.from(long.class, 0L));
        Map<Integer, Object> minimised = ReflectionHelpers.getField(service, "minimised");
        minimised.put(WEB_TASK, m);
    }

    /** The Web viewer's row in the Task Manager. */
    private View webRow(ActivityController<TaskManagerActivity> tm) throws Exception {
        DeskTestKit.settle(600);
        String desk = tm.get().getString(R.string.tm_this_desktop);
        String label = app.getString(R.string.wb_label);
        List<String> names = new ArrayList<>();
        for (View v : DeskTestKit.all(tm.get().getWindow().getDecorView())) {
            if (!(v instanceof TextView) || !desk.contentEquals(((TextView) v).getText())) continue;
            TextView name = (TextView) ((ViewGroup) v.getParent()).getChildAt(0);
            names.add(name.getText().toString());
            if (label.contentEquals(name.getText())) return (View) name.getParent().getParent();
        }
        throw new AssertionError("no Web viewer row in the Task Manager: " + names);
    }

    private static void click(View row) {
        row.performClick();
        shadowOf(Looper.getMainLooper()).idle();     // the broadcast reaches the receiver
    }

    /** Lets the service's later looks at the task happen (its clock is Robolectric's). */
    private static void meanwhile(long ms) throws InterruptedException {
        DeskTestKit.settle(ms);
    }

    /** What the service started, if anything, once its posted work has run. */
    private Intent started() throws InterruptedException {
        Intent got = null;
        for (int i = 0; i < 10 && got == null; i++) {
            Thread.sleep(50);
            shadowOf(Looper.getMainLooper()).idle();
            got = shadowOf((android.app.Application) app).getNextStartedActivity();
        }
        return got;
    }

    private void drainStarts() {
        while (shadowOf((android.app.Application) app).getNextStartedActivity() != null) {
            // what opening the windows themselves started
        }
    }

    /** Opens the Web viewer and the Task Manager, clicks the Web viewer's row, then {@code check}s. */
    private void clickWebRow(ThrowingRunnable check) throws Exception {
        ActivityController<WebActivity> web = Robolectric.buildActivity(WebActivity.class).setup();
        ActivityController<TaskManagerActivity> tm = Robolectric.buildActivity(TaskManagerActivity.class).setup();
        try {
            View row = webRow(tm);
            drainStarts();
            click(row);
            check.run();
        } finally {
            tm.pause().stop().destroy();
            web.pause().stop().destroy();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ── a window behind others ──────────────────────────────────────────────

    @Test
    public void taskManagerRow_bringsTheWebViewerForward_whenItIsBehindAnotherWindow() throws Exception {
        desktop(true);
        clickWebRow(() -> {
            assertEquals("the Web viewer comes to the front: " + daemon.asked,
                    "FRONT " + DISPLAY + " " + WEB_TASK, daemon.await("FRONT ", 3));
            meanwhile(300);
            assertEquals("it is in front", WEB_TASK, daemon.topShown());
            assertEquals("nothing is shown that was not hidden: " + daemon.asked, 0, daemon.count("SHOW "));
            assertNull("nothing is started", started());
        });
    }

    /** Android 10 cannot reorder, Android 12 will not across a fullscreen app: FRONT says ERR. */
    @Test
    public void whereTheDaemonWillNotRaiseIt_theWebViewerIsStartedAgain() throws Exception {
        desktop(true);
        daemon.frontReply = "ERR not raised";
        clickWebRow(() -> {
            assertNotNull("the raise was tried first", daemon.await("FRONT " + DISPLAY + " " + WEB_TASK, 3));
            Intent started = null;
            for (int i = 0; i < 6 && started == null; i++) started = started();
            assertNotNull("the Web viewer is started again, which brings its task forward", started);
            assertEquals(WEB, started.getComponent().getClassName());
            assertTrue("in its own task", (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        });
    }

    // ── a hidden window ─────────────────────────────────────────────────────

    @Test
    public void taskManagerRow_stillRestoresTheWebViewer_whenItIsMinimised() throws Exception {
        desktop(false);
        minimisedByTheService();
        clickWebRow(() -> {
            assertEquals("SHOW " + DISPLAY + " " + WEB_TASK, daemon.await("SHOW ", 3));
            meanwhile(2800);                                  // past every look the service takes
            assertEquals("shown, in front", WEB_TASK, daemon.topShown());
            assertEquals("one SHOW was enough: " + daemon.asked, 1, daemon.count("SHOW "));
            assertEquals("no raise on top of the restore: " + daemon.asked, 0, daemon.count("FRONT "));
            assertNull("nothing is started", started());
        });
    }

    /** Hidden by an earlier run of the service: this one has no record of hiding it. */
    @Test
    public void taskManagerRow_unhidesTheWebViewer_whenTheServiceForgotItHidIt() throws Exception {
        desktop(false);
        clickWebRow(() -> {
            assertEquals("SHOW " + DISPLAY + " " + WEB_TASK, daemon.await("SHOW ", 3));
            meanwhile(2800);
            assertEquals("shown, in front", WEB_TASK, daemon.topShown());
            assertEquals("one SHOW was enough: " + daemon.asked, 1, daemon.count("SHOW "));
        });
    }

    /** The daemon answers OK and nothing happens: the service looks, and asks once more. */
    @Test
    public void aShowThatDoesNotTake_isAskedOnceMore_thenLeftMinimised() throws Exception {
        desktop(false);
        minimisedByTheService();
        daemon.showsWorkFrom = Integer.MAX_VALUE;            // never carried out
        daemon.failedShowReply = "OK";                       // and never said so
        clickWebRow(() -> {
            assertNotNull(daemon.await("SHOW ", 3));
            assertTrue("asked again: " + daemon.asked, awaitCount("SHOW ", 2, 1500));
            meanwhile(2800);
            assertEquals("twice, and no more: " + daemon.asked, 2, daemon.count("SHOW "));
            assertFalse("still hidden", daemon.task(WEB_TASK).visible);
            assertEquals("no raise of a hidden window: " + daemon.asked, 0, daemon.count("FRONT "));
            assertNull("no start: it would not clear the hidden flag", started());
        });
    }

    /** A refusal (a daemon restarting, say) and then a SHOW that takes: the window comes back. */
    @Test
    public void aShowTheDaemonRefuses_thenCarriesOut_bringsTheWebViewerBack() throws Exception {
        desktop(false);
        minimisedByTheService();
        daemon.showsWorkFrom = 1;
        daemon.failedShowReply = "ERR busy";
        clickWebRow(() -> {
            assertNotNull(daemon.await("SHOW ", 3));
            meanwhile(2800);
            assertEquals("asked twice: " + daemon.asked, 2, daemon.count("SHOW "));
            assertEquals("shown, in front", WEB_TASK, daemon.topShown());
            assertNull("nothing is started", started());
        });
    }

    /** Un-hidden, but left behind the notes app (Android 12, across a fullscreen app): brought forward. */
    @Test
    public void unhiddenButLeftBehind_isBroughtForward() throws Exception {
        desktop(false);
        minimisedByTheService();
        daemon.showRaises = false;
        clickWebRow(() -> {
            assertNotNull(daemon.await("SHOW ", 3));
            assertEquals("then raised: " + daemon.asked, "FRONT " + DISPLAY + " " + WEB_TASK,
                    awaitAfter("FRONT ", 2800));
            assertEquals("in front", WEB_TASK, daemon.topShown());
            assertNull("nothing is started", started());
        });
    }

    @Test
    public void unhiddenButLeftBehind_andTheRaiseRefused_isStartedAgain() throws Exception {
        desktop(false);
        minimisedByTheService();
        daemon.showRaises = false;
        daemon.frontReply = "ERR not raised";
        clickWebRow(() -> {
            assertNotNull(daemon.await("SHOW ", 3));
            assertNotNull("raise tried: " + daemon.asked, awaitAfter("FRONT ", 2800));
            Intent started = null;
            for (int i = 0; i < 6 && started == null; i++) started = started();
            assertNotNull("started again, which brings a shown task forward", started);
            assertEquals(WEB, started.getComponent().getClassName());
        });
    }

    /** Closed by the time it is asked for: nothing to show, nothing asked again. */
    @Test
    public void aTaskGoneWhenAskedFor_isLeftAlone() throws Exception {
        desktop(false);
        minimisedByTheService();
        daemon.goneOnShow = WEB_TASK;
        clickWebRow(() -> {
            assertNotNull(daemon.await("SHOW ", 3));
            meanwhile(2800);
            assertEquals("asked once: " + daemon.asked, 1, daemon.count("SHOW "));
            assertEquals(0, daemon.count("FRONT "));
            assertNull("nothing is started", started());
        });
    }

    /** Advances the service's clock until {@code n} commands starting with {@code prefix} have come. */
    private boolean awaitCount(String prefix, long n, long ms) throws InterruptedException {
        for (long t = 0; t <= ms; t += 200) {
            if (daemon.count(prefix) >= n) return true;
            meanwhile(200);
        }
        return daemon.count(prefix) >= n;
    }

    /** Advances the service's clock while waiting, in real time, for a command. */
    private String awaitAfter(String prefix, long ms) throws InterruptedException {
        for (long t = 0; t <= ms; t += 200) {
            String got = daemon.await(prefix, 0);
            if (got != null) return got;
            meanwhile(200);
        }
        return daemon.await(prefix, 1);
    }

    // ── never the desktop; another app's stale pin ──────────────────────────

    /** "Show me this window" never means the desktop itself, whoever asks. */
    @Test
    public void aRestoreForOurPackage_neverRaisesTheDesktop() throws Exception {
        daemon.add(new FakeDaemon.Task(OTHER_TASK, 5, true, OTHER_PKG, OTHER_PKG + ".MainActivity"));
        daemon.add(new FakeDaemon.Task(DESK_TASK, 1, true, PKG, PKG + ".LauncherActivity"));
        app.sendBroadcast(new Intent(LauncherActivity.ACTION_RESTORE).setPackage(PKG).putExtra("pkg", PKG));
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("the receiver looked", daemon.await("LIST ", 3));
        meanwhile(300);
        assertEquals("the desktop is not raised or shown: " + daemon.asked, 0,
                daemon.count("FRONT ") + daemon.count("SHOW "));
    }

    /** A taskbar pin that outlived its minimise: the app's window comes forward all the same. */
    @Test
    public void aStalePinForAnotherApp_bringsItsWindowForward() throws Exception {
        daemon.add(new FakeDaemon.Task(WEB_TASK, 5, true, PKG, WEB));
        daemon.add(new FakeDaemon.Task(OTHER_TASK, 5, true, OTHER_PKG, OTHER_PKG + ".MainActivity"));
        daemon.add(new FakeDaemon.Task(DESK_TASK, 1, true, PKG, PKG + ".LauncherActivity"));
        app.sendBroadcast(new Intent(LauncherActivity.ACTION_RESTORE).setPackage(PKG).putExtra("pkg", OTHER_PKG));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("FRONT " + DISPLAY + " " + OTHER_TASK, daemon.await("FRONT ", 3));
    }
}
