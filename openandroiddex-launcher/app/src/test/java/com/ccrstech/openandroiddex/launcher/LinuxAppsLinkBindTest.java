package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * LinuxAppsLink's binding to ":linuxapps" through the apps process dying:
 * the rebind it makes when Android does not bring the service back, and the
 * one it must not make when Android does. The finding this is for: a retry
 * still waiting when Android had reconnected the service unbound it, and an
 * unbound service is destroyed, with every app it runs.
 *
 * <p>The owner records every bind and unbind in order; the connection
 * callbacks are called the way Android calls them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LinuxAppsLinkBindTest {

    /** A Service to own the link, recording what it binds and unbinds. */
    public static class Owner extends Service {
        final List<String> calls = new ArrayList<>();
        final List<ServiceConnection> conns = new ArrayList<>();
        private final Map<ServiceConnection, String> names = new IdentityHashMap<>();
        private final Map<ServiceConnection, Boolean> real = new IdentityHashMap<>();
        /**
         * The next this many binds are taken and never connected: a service
         * Android holds back while a restart of its own is due.
         */
        int holdBack;

        String name(ServiceConnection c) {
            String n = names.get(c);
            if (n == null) {
                n = "c" + (names.size() + 1);
                names.put(c, n);
                conns.add(c);
            }
            return n;
        }

        @Override
        public boolean bindService(Intent service, ServiceConnection conn, int flags) {
            calls.add("bind " + name(conn));
            if (holdBack > 0) {
                holdBack--;
                return true;
            }
            real.put(conn, true);
            return super.bindService(service, conn, flags);
        }

        @Override
        public void unbindService(ServiceConnection conn) {
            calls.add("unbind " + name(conn));
            if (real.remove(conn) != null) super.unbindService(conn);
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }

    private Owner owner;
    private LinuxAppsLink link;
    private ComponentName comp;
    private final List<String> received = new ArrayList<>();
    private IBinder apps;

    @Before
    public void setUp() {
        owner = Robolectric.buildService(Owner.class).create().get();
        comp = new ComponentName(owner, LinuxAppsService.class);
        apps = binderRecordingAs("apps");
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(comp, apps);
        link = new LinuxAppsLink(owner);
    }

    private IBinder binderRecordingAs(String who) {
        return new Messenger(new Handler(Looper.getMainLooper(), m -> {
            received.add(who + " " + m.what + " " + m.getData().getString(LinuxAppsService.KEY_DISPLAY));
            return true;
        })).getBinder();
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private ServiceConnection connected() {
        link.hold();
        idle(10);
        assertEquals(Collections.singletonList("bind c1"), owner.calls);
        return owner.conns.get(0);
    }

    @Test
    public void aServiceAndroidBroughtBack_isNotUnboundByTheRetry() {
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);  // the apps process died
        c1.onServiceConnected(comp, apps); // and Android started it again, on this binding
        idle(2_500);                     // past the retry's one second
        assertEquals("no unbind, no second binding: the service and its apps stay",
                Collections.singletonList("bind c1"), owner.calls);
        link.stopDisplay(LinuxAppsLink.DESKTOP);
        idle(10);
        assertEquals(Collections.singletonList("apps " + LinuxAppsService.MSG_STOP_DISPLAY + " :1"), received);
    }

    @Test
    public void aServiceLeftDown_isBoundAgain_theNewBindingBeforeTheOldGoes() {
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);
        idle(900);
        assertEquals("not yet: a second's wait", Collections.singletonList("bind c1"), owner.calls);
        idle(300);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
        idle(10);
        // A late callback on the binding let go changes nothing.
        IBinder stale = binderRecordingAs("stale");
        c1.onServiceConnected(comp, stale);
        link.stopDisplay(LinuxAppsLink.WINDOWS);
        idle(10);
        assertEquals(Collections.singletonList("apps " + LinuxAppsService.MSG_STOP_DISPLAY + " :2"), received);
        // Nor a late disconnect on it.
        c1.onServiceDisconnected(comp);
        idle(2_500);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
        // Connected in time: nothing forced.
        idle(LinuxAppsLink.FORCE_MS + 500);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
    }

    @Test
    public void aRestartAndroidHoldsBack_isCutShort_afterItsWait() {
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);
        owner.holdBack = 1; // the new binding waits on Android's own restart
        idle(1_200);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
        idle(LinuxAppsLink.FORCE_MS - 500);
        assertEquals("still within its wait", Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
        idle(800);
        assertEquals("no binding left, then a new one: the service comes up at once",
                Arrays.asList("bind c1", "bind c2", "unbind c1", "unbind c2", "bind c3"), owner.calls);
        idle(10);
        link.stopDisplay(LinuxAppsLink.DESKTOP);
        idle(10);
        assertEquals(Collections.singletonList("apps " + LinuxAppsService.MSG_STOP_DISPLAY + " :1"), received);
        idle(LinuxAppsLink.FORCE_MS * 3);
        assertEquals("once, not again", 5, owner.calls.size());
    }

    @Test
    public void aRestartAndroidMakesWithinTheWait_isLeftBe() {
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);
        owner.holdBack = 1;
        idle(1_200);
        ServiceConnection c2 = owner.conns.get(1);
        c2.onServiceConnected(comp, apps); // Android's restart, on the new binding
        idle(LinuxAppsLink.FORCE_MS + 1_000);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
    }

    @Test
    public void theSessionsLettingGoDuringTheWait_cancelsTheRetry() {
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);
        link.release();
        idle(2_500);
        assertEquals(Arrays.asList("bind c1", "unbind c1"), owner.calls);
        assertFalse(link.holding());
    }

    @Test
    public void aBindingThatDied_isReplaced_onADeathsWait_newFirst() {
        ServiceConnection c1 = connected();
        c1.onBindingDied(comp);
        assertEquals("not at once: Android gives up on a service that keeps crashing this way",
                Collections.singletonList("bind c1"), owner.calls);
        idle(1_200);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
        assertTrue(link.holding());
        link.release();
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1", "unbind c2"), owner.calls);
    }

    @Test
    public void noForce_whileTheServiceSaysItIsUp() throws Exception {
        // "ready" names a live LinuxAppsService instance: its connection is on
        // its way, and letting go of the binding now would destroy it.
        java.io.File ready = new java.io.File(LinuxAppsService.requests(owner), "ready");
        assertTrue(ready.getParentFile().isDirectory() || ready.getParentFile().mkdirs());
        java.nio.file.Files.write(ready.toPath(), "4242\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        link.alive = pid -> pid == 4242;
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);
        owner.holdBack = 1;
        idle(1_200);
        idle(LinuxAppsLink.FORCE_MS + 1_000);
        assertEquals(Arrays.asList("bind c1", "bind c2", "unbind c1"), owner.calls);
        // A dead instance's "ready" does not hold the force back.
        link.alive = pid -> false;
        link.release();
        owner.calls.clear();
        ServiceConnection c3 = connectedAgain();
        c3.onServiceDisconnected(comp);
        owner.holdBack = 1;
        idle(1_200 + LinuxAppsLink.FORCE_MS + 500);
        assertEquals(5, owner.calls.size());
        assertTrue(owner.calls.toString(), owner.calls.get(3).startsWith("unbind"));
    }

    @Test
    public void theWaitStartsOver_withEachSession() {
        ServiceConnection c1 = connected();
        c1.onServiceDisconnected(comp);
        idle(1_200);                                 // 1 s, then bound again (c2), connected
        ServiceConnection c2 = owner.conns.get(1);
        c2.onServiceDisconnected(comp);              // dies again within a minute: 2 s
        idle(1_200);
        assertEquals("still waiting its 2 s", 3, owner.calls.size());
        idle(1_000);
        assertEquals(5, owner.calls.size());         // bind c3, unbind c2
        link.release();
        owner.calls.clear();
        ServiceConnection next = connectedAgain();   // a new session
        next.onServiceDisconnected(comp);
        idle(1_200);
        assertEquals("a second again, not four", 3, owner.calls.size());
    }

    /** hold() after a release: the next connection, whatever its name. */
    private ServiceConnection connectedAgain() {
        link.hold();
        idle(10);
        return owner.conns.get(owner.conns.size() - 1);
    }

    @Test
    public void aStopBeforeTheConnection_isDeliveredOnceConnected() {
        link.hold();
        link.stopDisplay(LinuxAppsLink.DESKTOP);
        assertTrue(received.isEmpty());
        idle(10);
        assertEquals(Collections.singletonList("apps " + LinuxAppsService.MSG_STOP_DISPLAY + " :1"), received);
    }
}
