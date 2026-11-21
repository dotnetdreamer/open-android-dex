package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The app's half of handing a Linux app to ":linuxapps" (LinuxAppRunner): the
 * claim, the start, the stops and the clean-up, against a real folder with a
 * fake process start, a fake kill(2) and a fake /proc. The guest's half, the
 * race of both halves for one request, and these rules against real processes
 * and a real proot are test/linux-apps-test.mjs; Android ranking the
 * processes is the phone test (doc/linux-apps-process.md).
 *
 * <p>The ids and headers below are ones oadx-run really wrote, in
 * ubuntu:24.04, in that test.
 */
public class LinuxAppRunnerTest {

    private static final int SELF = 7000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir;
    private final List<String[]> spawned = Collections.synchronizedList(new ArrayList<>());
    private final List<FakeProcess> procs = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> groupKills = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> quits = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> kills = Collections.synchronizedList(new ArrayList<>());
    private final Set<Integer> aliveLeaders = ConcurrentHashMap.newKeySet();
    /** oadx-run's pids (the ids' last part, all under 4000 here): alive unless listed. */
    private final Set<Integer> deadRequesters = ConcurrentHashMap.newKeySet();
    /** The app process each group belongs to, for a proot that finishes on SIGQUIT. */
    private final Map<Integer, FakeProcess> byGroup = new ConcurrentHashMap<>();
    /** Does proot finish when asked: true, as the real one does. */
    private volatile boolean prootQuits = true;
    private final AtomicReference<List<LinuxAppRunner.Proc>> census = new AtomicReference<>();
    /** Called after each fake SIGKILL to one process. */
    private volatile java.util.function.IntConsumer onKill;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong clock = new AtomicLong(1_000);
    private volatile boolean spawnFails;
    /** Set: spawn waits on it, as a slow pb.start() does. */
    private volatile CountDownLatch spawnGate;
    private final CountDownLatch spawnEntered = new CountDownLatch(1);
    private LinuxAppRunner runner;

    @Before
    public void setUp() throws IOException {
        dir = tmp.newFolder(".oadx-apps");
        runner = new LinuxAppRunner(dir,
                (id, display) -> {
                    if (spawnFails) throw new IOException("no linux-app.sh");
                    spawnEntered.countDown();
                    CountDownLatch gate = spawnGate;
                    if (gate != null) {
                        try {
                            gate.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                    }
                    spawned.add(new String[]{id, display});
                    FakeProcess p = new FakeProcess();
                    procs.add(p);
                    return p;
                },
                new LinuxAppRunner.Killer() {
                    @Override
                    public boolean alive(int pid) {
                        if (pid < 4000) return !deadRequesters.contains(pid);
                        return aliveLeaders.contains(pid);
                    }

                    @Override
                    public void quit(int pid) {
                        quits.add(pid);
                        if (!prootQuits) return;
                        // proot: every process it traces SIGKILLed, then it exits.
                        aliveLeaders.remove(pid);
                        FakeProcess p = byGroup.get(pid);
                        if (p != null) p.end(0);
                    }

                    @Override
                    public void kill(int pid) {
                        kills.add(pid);
                        // Dead: the next look at /proc no longer lists it.
                        List<LinuxAppRunner.Proc> c = census.get();
                        if (c != null) {
                            List<LinuxAppRunner.Proc> left = new ArrayList<>();
                            for (LinuxAppRunner.Proc p : c) if (p.pid != pid) left.add(p);
                            census.set(left);
                        }
                        java.util.function.IntConsumer hook = onKill;
                        if (hook != null) hook.accept(pid);
                    }

                    @Override
                    public void killGroup(int pgid) {
                        groupKills.add(pgid);
                        aliveLeaders.remove(pgid);
                    }
                },
                census::get, SELF, log::add, clock::get, System::currentTimeMillis);
        runner.quitAgainMs = 40;
        runner.graceMs = 200;
    }

    private File request(String id, String header) throws IOException {
        File f = new File(dir, id + ".req");
        write(f, "#!/bin/sh\n" + header + "\nrm -f -- \"$0\"\nexport X='1'\nexec 'app'\n");
        return f;
    }

    private static void write(File f, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── the claim ──

    @Test
    public void claimsTheRequestAndStartsItOnItsDisplay() throws IOException {
        request("1795200495089367441-257", "# oadx-run 1 display=:1 name=probe");
        LinuxAppRunner.App app = runner.offer("1795200495089367441-257.req");
        assertNotNull(app);
        assertEquals(":1", app.display);
        assertEquals("probe", app.name);
        assertEquals(1, spawned.size());
        assertEquals("1795200495089367441-257", spawned.get(0)[0]);
        assertEquals(":1", spawned.get(0)[1]);
        assertFalse("the request is claimed", new File(dir, "1795200495089367441-257.req").exists());
        assertTrue("and left for linux-app.sh to claim again", new File(dir, "1795200495089367441-257.run").exists());
        assertEquals(1, runner.running().size());
    }

    @Test
    public void aRequestReportedTwiceStartsOnce() throws IOException {
        request("1795-1", "# oadx-run 1 display=:2 name=xfce4-terminal");
        assertNotNull(runner.offer("1795-1.req"));
        assertNull(runner.offer("1795-1.req"));
        assertEquals(1, spawned.size());
    }

    @Test
    public void aRequestOadxRunTookBackIsLeftAlone() {
        assertNull(runner.offer("1795-2.req")); // oadx-run renamed it to .self first
        assertTrue(spawned.isEmpty());
    }

    @Test
    public void onlyRequestsAreTaken() throws IOException {
        String[] names = {"ready", ".1795-3.tmp", "1795-3.run", "1795-3.go", "1795-3.pid", "1795-3.self",
                "abc-1.req", "1-2-3.req", "-257.req", "1795-.req", "../1795-3.req", "1795-3.req.tmp"};
        for (String n : names) {
            if (!n.startsWith("..")) write(new File(dir, n), "#!/bin/sh\n# oadx-run 1 display=:1 name=x\n");
        }
        for (String n : names) assertNull(n, runner.offer(n));
        assertTrue(spawned.isEmpty());
        for (String n : names) {
            if (!n.startsWith("..")) assertTrue(n + " untouched", new File(dir, n).exists());
        }
    }

    @Test
    public void aStartThatFailsLeavesTheClaimForOadxRunToTakeBack() throws IOException {
        request("1795-4", "# oadx-run 1 display=:1 name=firefox");
        spawnFails = true;
        assertNull(runner.offer("1795-4.req"));
        assertTrue("oadx-run takes .run back at its ten seconds and runs it itself",
                new File(dir, "1795-4.run").exists());
        assertTrue(runner.running().isEmpty());
        assertTrue(log.get(log.size() - 1).startsWith("could not start firefox"));
    }

    @Test
    public void ids_areTheOnesOadxRunMakes() {
        assertTrue(LinuxAppRunner.ID.matcher("1795200495089367441-257").matches()); // date +%s%N
        assertTrue(LinuxAppRunner.ID.matcher("1795200495-257").matches());          // date +%s
        assertFalse(LinuxAppRunner.ID.matcher("-257").matches());
        assertFalse(LinuxAppRunner.ID.matcher("1795-").matches());
        assertFalse(LinuxAppRunner.ID.matcher("1795-25x").matches());
        assertFalse(LinuxAppRunner.ID.matcher("1795;rm-1").matches());
    }

    @Test
    public void header_readsWhatOadxRunWrites_andFallsBack() throws IOException {
        File f = new File(dir, "h");
        write(f, "#!/bin/sh\n# oadx-run 1 display=bad_display___x_ name=pro_be\nrm -f -- \"$0\"\n");
        String[] h = LinuxAppRunner.header(f);
        assertEquals("bad_display___x_", h[0]);
        assertEquals("pro_be", h[1]);
        write(f, "#!/bin/sh\n# oadx-run 1 display= name=\n");
        h = LinuxAppRunner.header(f);
        assertEquals("", h[0]);
        assertEquals("an app", h[1]);
        write(f, "#!/bin/sh\n# oadx-run 2 something else\n");
        h = LinuxAppRunner.header(f);
        assertEquals("", h[0]);
        assertEquals("an app", h[1]);
        write(f, "#!/bin/sh\n# oadx-run 1 display=:1;x name=a\n");
        assertEquals("a display with anything else in it does not read", "", LinuxAppRunner.header(f)[0]);
        assertEquals("", LinuxAppRunner.header(new File(dir, "missing"))[0]);
    }

    @Test
    public void display_dropsTheScreenNumberGtkAdds() {
        assertEquals(":1", LinuxAppRunner.display(":1.0"));
        assertEquals(":1", LinuxAppRunner.display(":1"));
        assertEquals(":2", LinuxAppRunner.display(":2.0"));
        assertEquals("host:10", LinuxAppRunner.display("host:10.0"));
        assertEquals("", LinuxAppRunner.display(""));
        assertEquals(":", LinuxAppRunner.display(":"));
        assertEquals(":.0", LinuxAppRunner.display(":.0")); // not a display number: as it came
    }

    @Test
    public void anAppXfceStarted_onScreenZero_isClosedWithTheDesktop() throws IOException {
        // What every app the S25's desktop started said: ":1.0". The desktop's
        // stop names ":1", and missed every one of them.
        LinuxAppRunner.App a = started("1-9", ":1.0", 100, 4901);
        assertEquals(":1", a.display);
        assertEquals(1, runner.stopDisplay(":1", 200));
        assertEquals(Collections.singletonList(4901), quits);
    }

    // ── the stops ──

    private LinuxAppRunner.App started(String id, String display, long at, int pgid) throws IOException {
        clock.set(at);
        request(id, "# oadx-run 1 display=" + display + " name=app" + id.replace('-', '_'));
        LinuxAppRunner.App app = runner.offer(id + ".req");
        assertNotNull(app);
        if (pgid > 0) {
            write(new File(dir, id + ".pid"), pgid + "\n");
            byGroup.put(pgid, procs.get(procs.size() - 1));
        }
        return app;
    }

    @Test
    public void aSessionsStopClosesItsAppsStartedUntilThen_andNoOthers() throws IOException {
        LinuxAppRunner.App a = started("1-1", ":1", 100, 4101);
        LinuxAppRunner.App b = started("2-1", ":2", 100, 4102);
        LinuxAppRunner.App c = started("3-1", ":1", 300, 4103); // after the stop was sent: a newer session's
        assertEquals(1, runner.stopDisplay(":1", 200));
        assertEquals(Collections.singletonList(4101), quits);
        assertTrue(a.stopped);
        assertFalse(b.stopped);
        assertFalse(c.stopped);
        assertTrue("its proot finished", !procs.get(0).isAlive());
        assertTrue(procs.get(1).isAlive());
        assertTrue(procs.get(2).isAlive());
    }

    @Test
    public void aStopAsksProotToCloseEverythingItTraces_notAGroupKill() throws Exception {
        // A group SIGKILL misses what an app moved out of its group (setsid:
        // VS Code's window, a terminal's shells); proot's SIGQUIT does not.
        LinuxAppRunner.App app = started("1-20", ":1", 100, 4201);
        aliveLeaders.add(4201);
        Thread t = new Thread(() -> runner.watch(app));
        t.start();
        assertEquals(1, runner.stopDisplay(":1", 200));
        assertEquals("SIGQUIT to proot, at once", Collections.singletonList(4201), quits);
        t.join(5_000);
        assertFalse(t.isAlive());
        assertFalse("proot finished: no force on its own", procs.get(0).destroyed);
        Thread.sleep(runner.graceMs + 150);
        assertEquals("only the watcher's look for leftovers in the group, once",
                Collections.singletonList(4201), groupKills);
        assertFalse(procs.get(0).destroyed);
        // The second SIGQUIT may beat the watcher to "ended": to a proot gone, harmless.
        assertTrue("nothing more once it has ended: " + quits, quits.size() <= 2);
    }

    @Test
    public void aProotThatDoesNotFinish_isAskedAgain_thenItsGroupGoesByForce() throws Exception {
        prootQuits = false;
        LinuxAppRunner.App app = started("1-21", ":1", 100, 4211);
        aliveLeaders.add(4211);
        assertEquals(1, runner.stopDisplay(":1", 200));
        Thread.sleep(runner.quitAgainMs + 100);
        assertEquals("asked twice", Arrays.asList(4211, 4211), quits);
        assertTrue(groupKills.isEmpty());
        Thread.sleep(runner.graceMs);
        assertEquals("then the group, by force", Collections.singletonList(4211), groupKills);
        assertTrue(procs.get(0).destroyed);
        assertTrue(app.stopped);
    }

    @Test
    public void stopAllClosesEveryApp() throws IOException {
        started("1-2", ":1", 100, 4201);
        started("2-2", ":2", 100, 4202);
        started("3-2", "", 100, 4203);
        assertEquals(3, runner.stopAll());
        List<Integer> q = new ArrayList<>(quits);
        Collections.sort(q);
        assertEquals(Arrays.asList(4201, 4202, 4203), q);
        for (FakeProcess p : procs) assertFalse(p.isAlive());
    }

    @Test
    public void anAppIsCountedOnce_whenItsSessionAndThenTheServiceCloseIt() throws IOException {
        // Seen on the S25: a logout closed the app with its session, and the
        // service going away a moment later said it closed one app as well.
        started("1-10", ":1", 100, 5101);
        started("2-10", ":2", 100, 5102);
        assertEquals(1, runner.stopDisplay(":1", 200));
        assertEquals(0, runner.stopDisplay(":1", 200));
        assertEquals("only the window session's app is left to close", 1, runner.closeAll());
        assertEquals(0, runner.closeAll());
        assertEquals(0, runner.stopAll());
    }

    @Test
    public void aStopBeforeThePidIsWritten_killsTheShellItself() throws IOException {
        started("1-3", ":1", 100, -1);
        runner.closeAll();
        assertTrue("nothing to ask yet", quits.isEmpty());
        assertTrue("nothing to group-kill yet", groupKills.isEmpty());
        assertTrue(procs.get(0).destroyed);
    }

    // ── what starts, and what does not ──

    @Test
    public void afterCloseAll_nothingIsClaimed_andOadxRunRunsItWhereItAsked() throws IOException {
        runner.closeAll();
        request("1795-30", "# oadx-run 1 display=:1 name=firefox");
        assertNull(runner.offer("1795-30.req"));
        assertTrue("oadx-run takes .req back at its ten seconds", new File(dir, "1795-30.req").exists());
        assertTrue(spawned.isEmpty());
    }

    @Test(timeout = 10_000)
    public void closeAll_whileAnAppIsStarting_closesItTheMomentItIsStarted() throws Exception {
        // The finding: shutdown saw no apps while one was starting, and the
        // app registered a live child after the clean-up.
        spawnGate = new CountDownLatch(1);
        clock.set(100);
        request("1795-31", "# oadx-run 1 display=:1 name=code");
        AtomicReference<LinuxAppRunner.App> got = new AtomicReference<>();
        Thread t = new Thread(() -> got.set(runner.offer("1795-31.req")));
        t.start();
        assertTrue(spawnEntered.await(5, TimeUnit.SECONDS));
        assertEquals("nothing registered yet to close", 0, runner.closeAll());
        spawnGate.countDown();
        t.join(5_000);
        LinuxAppRunner.App app = got.get();
        assertNotNull("it did start", app);
        assertTrue("and was closed at once", app.stopped);
        assertTrue("before linux-app.sh wrote a pid: the shell itself", procs.get(0).destroyed);
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("closing code (1795-31) at once")));
    }

    @Test(timeout = 10_000)
    public void aSessionStop_whileItsAppIsStarting_closesItTheMomentItIsStarted() throws Exception {
        spawnGate = new CountDownLatch(1);
        clock.set(100);
        request("1795-32", "# oadx-run 1 display=:1.0 name=thunar");
        AtomicReference<LinuxAppRunner.App> got = new AtomicReference<>();
        Thread t = new Thread(() -> got.set(runner.offer("1795-32.req")));
        t.start();
        assertTrue(spawnEntered.await(5, TimeUnit.SECONDS));
        assertEquals(0, runner.stopDisplay(":1", 150));
        spawnGate.countDown();
        t.join(5_000);
        assertTrue(got.get().stopped);
        assertTrue(procs.get(0).destroyed);
    }

    @Test
    public void aRequestMadeBeforeItsSessionStopped_isNotStarted_andOadxRunStopsWaiting() throws IOException {
        clock.set(10_000);
        runner.stopDisplay(":1", 9_000);
        // Written three seconds ago: at 7 000, before the stop at 9 000.
        File f = request("1795-33", "# oadx-run 1 display=:1.0 name=mousepad");
        assertTrue(f.setLastModified(System.currentTimeMillis() - 3_000));
        assertNull(runner.offer("1795-33.req"));
        assertTrue(spawned.isEmpty());
        assertFalse("no .req", new File(dir, "1795-33.req").exists());
        assertFalse("and no .run: oadx-run's wait ends, and it starts nothing", new File(dir, "1795-33.run").exists());
        assertTrue(log.get(log.size() - 1), log.get(log.size() - 1).startsWith("did not start mousepad (1795-33)"));
        // The same session's display, asked for after the stop: a newer session's app.
        request("1795-34", "# oadx-run 1 display=:1 name=mousepad");
        assertNotNull(runner.offer("1795-34.req"));
        // And another display's, asked for before it: not that session's to stop.
        File g = request("1795-35", "# oadx-run 1 display=:2 name=mousepad");
        assertTrue(g.setLastModified(System.currentTimeMillis() - 3_000));
        assertNotNull(runner.offer("1795-35.req"));
        assertEquals(2, spawned.size());
    }

    // ── the end of an app ──

    @Test(timeout = 10_000)
    public void anAppThatEnds_takesWhatOutlivedItsProot_andIsForgotten() throws Exception {
        LinuxAppRunner.App app = started("1-4", ":1", 100, 4401);
        Thread t = new Thread(() -> runner.watch(app));
        t.start();
        procs.get(0).end(0);
        t.join(5_000);
        assertFalse(t.isAlive());
        assertEquals(Collections.singletonList(4401), groupKills);
        assertTrue(runner.running().isEmpty());
        assertFalse(new File(dir, "1-4.pid").exists());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("ended app1_4 (1-4), exit 0")));
    }

    @Test(timeout = 10_000)
    public void anAppWhoseFirstProcessHandedOver_isWatchedUntilItsGroupLeaderGoes() throws Exception {
        LinuxAppRunner.App app = started("1-5", ":1", 100, 4501);
        aliveLeaders.add(4501);
        Thread t = new Thread(() -> runner.watch(app));
        t.start();
        procs.get(0).end(0);
        Thread.sleep(LinuxAppRunner.POLL_MS * 2 + 200);
        assertTrue("still watching while the leader runs", t.isAlive());
        assertTrue(groupKills.isEmpty());
        assertEquals(1, runner.running().size());
        aliveLeaders.remove(4501);
        t.join(LinuxAppRunner.POLL_MS * 3);
        assertFalse(t.isAlive());
        assertEquals(Collections.singletonList(4501), groupKills);
        assertTrue(runner.running().isEmpty());
    }

    @Test(timeout = 10_000)
    public void anAppClosedWithItsSession_saysClosed() throws Exception {
        LinuxAppRunner.App app = started("1-6", ":1", 100, 4601);
        aliveLeaders.add(4601);
        Thread t = new Thread(() -> runner.watch(app));
        t.start();
        runner.stopDisplay(":1", 200); // proot finishes: the watch wakes
        t.join(5_000);
        assertFalse(t.isAlive());
        assertTrue(runner.running().isEmpty());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("closed app1_6 (1-6)")));
    }

    @Test(timeout = 10_000)
    public void anAppWhoseProotAndroidKilled_leavesNothingRunningUntraced() throws Exception {
        // Android's limit SIGKILLs the proot (its oldest process): what it
        // traced runs on untraced. In the group, the group kill takes it; what
        // left the group (setsid), only a look at /proc finds.
        LinuxAppRunner.App app = started("1-7", ":1", 100, 4701);
        LinuxAppRunner.App other = started("2-7", ":1", 100, 4801);
        census.set(Arrays.asList(
                new LinuxAppRunner.Proc(4801, SELF, 0),        // the other app's proot
                new LinuxAppRunner.Proc(4802, 4801, 4801),     // and what it traces
                new LinuxAppRunner.Proc(4803, 4802, 4801),
                new LinuxAppRunner.Proc(4710, 1, 0),           // left by the dead proot: setsid'd, reparented
                new LinuxAppRunner.Proc(4711, 4710, 0)));      // and its child
        Thread t = new Thread(() -> runner.watch(app));
        t.start();
        procs.get(0).end(137);
        t.join(5_000);
        assertFalse(t.isAlive());
        assertEquals(Collections.singletonList(4701), groupKills);
        List<Integer> k = new ArrayList<>(kills);
        Collections.sort(k);
        assertEquals(Arrays.asList(4710, 4711), k);
        assertFalse(other.stopped);
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("took 2 process(es)")));
    }

    @Test(timeout = 10_000)
    public void finish_takesWhateverIsLeft_byForce_thenWhatLeftIt() throws Exception {
        prootQuits = false;
        started("1-8", ":1", 100, 4901);
        census.set(Collections.singletonList(new LinuxAppRunner.Proc(4910, 1, 0)));
        assertEquals(1, runner.closeAll());
        runner.finish();
        Thread.sleep(runner.graceMs + LinuxAppRunner.POLL_MS + 400);
        assertTrue(groupKills.contains(4901));
        assertTrue(procs.get(0).destroyed);
        assertEquals(Collections.singletonList(4910), kills);
    }

    @Test
    public void aRequestWhoseAskerHasGone_isNotStarted() throws IOException {
        // A leftover in the folder of a session that died: no stop of it on
        // record in this (new) instance, but its oadx-run is gone with it.
        deadRequesters.add(77);
        request("1795-77", "# oadx-run 1 display=:1 name=firefox");
        assertNull(runner.offer("1795-77.req"));
        assertTrue(spawned.isEmpty());
        assertTrue("left for oadx-run to take back, should it be there after all (the sweep takes it otherwise)",
                new File(dir, "1795-77.run").exists());
        assertTrue(log.get(log.size() - 1), log.get(log.size() - 1).startsWith("did not start firefox (1795-77): whoever asked for it has gone"));
        // Its neighbour, asked for by an oadx-run still waiting: started.
        request("1795-78", "# oadx-run 1 display=:1 name=firefox");
        assertNotNull(runner.offer("1795-78.req"));
        assertEquals(78, LinuxAppRunner.requesterOf("1795200495089367441-78"));
        assertEquals(-1, LinuxAppRunner.requesterOf("x"));
    }

    @Test
    public void aSweepLooksAgain_whileItFindsSomething() {
        // 6100 forks 6101 between the look and the kill: the next look takes it.
        census.set(new ArrayList<>(Collections.singletonList(new LinuxAppRunner.Proc(6100, 1, 0))));
        onKill = pid -> {
            if (pid == 6100) census.set(new ArrayList<>(Collections.singletonList(new LinuxAppRunner.Proc(6101, 1, 0))));
        };
        assertEquals(2, runner.reap());
        assertEquals(Arrays.asList(6100, 6101), kills);
    }

    @Test
    public void whereProcSaysNothing_nothingIsReaped() {
        census.set(null);
        assertEquals(0, runner.reap());
        assertTrue(kills.isEmpty());
    }

    // ── what is left running untraced ──

    @Test
    public void orphans_areOnlyWhatRunsUntracedWithNoneOfOursOverIt() {
        Set<Integer> known = new HashSet<>(Arrays.asList(5100));
        List<LinuxAppRunner.Proc> ours = Arrays.asList(
                new LinuxAppRunner.Proc(SELF, 600, 0),       // this process
                new LinuxAppRunner.Proc(5100, SELF, 0),      // linux-app.sh, now proot
                new LinuxAppRunner.Proc(5200, SELF, 0),      // one starting: no pid file yet
                new LinuxAppRunner.Proc(5201, 5200, 0),      // its proot's first process, not yet traced
                new LinuxAppRunner.Proc(5101, 5100, 0),      // a known proot's, not yet traced
                new LinuxAppRunner.Proc(5102, 5101, 5100),   // traced
                new LinuxAppRunner.Proc(5103, 1, 5100),      // setsid'd and reparented, still traced
                new LinuxAppRunner.Proc(5300, 1, 0),         // traced by a proot that is gone
                new LinuxAppRunner.Proc(5301, 5300, 0),
                new LinuxAppRunner.Proc(5302, 5301, 0),
                new LinuxAppRunner.Proc(1, 0, 0));           // never
        List<Integer> got = LinuxAppRunner.orphans(ours, SELF, known);
        Collections.sort(got);
        assertEquals(Arrays.asList(5300, 5301, 5302), got);
    }

    // ── leftovers ──

    @Test
    public void aSweepTakesOnlyOldLeftovers_andHandsBackYoungRequestsOldestFirst() throws IOException {
        long now = 10_000_000L;
        String[] old = {"9-1.req", "9-2.run", "9-3.go", "9-4.pid", "9-5.self", ".9-6.tmp"};
        for (String n : old) touch(n, now - 60_000);
        touch("ready", now - 60_000);
        touch("oadx-apps.log", now - 60_000);
        touch("8-2.req", now - 2_000);
        touch("8-1.req", now - 5_000);
        touch("8-3.pid", now - 1_000);
        List<String> young = runner.sweep(now, 15_000);
        for (String n : old) assertFalse(n + " gone", new File(dir, n).exists());
        assertTrue("ready is not a leftover", new File(dir, "ready").exists());
        assertTrue("nor anything else of the folder's", new File(dir, "oadx-apps.log").exists());
        assertTrue("a young pid file stays", new File(dir, "8-3.pid").exists());
        assertEquals(Arrays.asList("8-1.req", "8-2.req"), young);
    }

    private void touch(String name, long mtime) throws IOException {
        File f = new File(dir, name);
        write(f, "x");
        assertTrue(f.setLastModified(mtime));
    }

    /** A process that ends when told to, or when destroyed. */
    static final class FakeProcess extends Process {
        private final CountDownLatch exit = new CountDownLatch(1);
        private final AtomicInteger code = new AtomicInteger();
        volatile boolean destroyed;

        void end(int c) {
            if (exit.getCount() == 0) return;
            code.set(c);
            exit.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exit.await();
            return code.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exit.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (exit.getCount() > 0) throw new IllegalThreadStateException();
            return code.get();
        }

        @Override
        public void destroy() {
            destroyed = true;
            end(137);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return exit.getCount() > 0;
        }
    }
}
