package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's helper (PhoneHelper, run as the shell on a phone): when the
 * phone's own screen goes dark and when it is lit again, and the input it puts
 * in for the app. The rules: a dark panel never outlives what asked for it, a
 * panel Android itself put out is left to Android, and a finger never stays
 * down when the app goes.
 *
 * <p>On a real phone, through the app and a browser: test/phone-remote/screen-off.mjs
 * (doc/phone-remote.md, "The phone's helper").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PhoneHelperTest {

    /** The panel as a phone has it, and every call made to it. */
    private static final class FakePower implements PhoneHelper.Power {
        volatile boolean awake = true;
        volatile boolean lit = true;
        volatile boolean failNext;
        final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void set(boolean on) throws Exception {
            calls.add(on ? "on" : "off");
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("no panel found");
            }
            lit = on;
        }

        @Override
        public boolean awake() {
            return awake;
        }

        @Override
        public void wake() {
            calls.add("wake");
            awake = true;
            // Android lights the panel itself when it wakes the phone.
            lit = true;
        }
    }

    /** Input events, kept instead of put in. */
    private static final class FakeInput implements PhoneHelper.Input {
        final List<InputEvent> events = new ArrayList<>();

        @Override
        public boolean inject(InputEvent e) {
            events.add(e);
            return true;
        }
    }

    /** A connection without a socket: what the helper says to it, kept. */
    private static final class FakeConn extends PhoneHelper.Conn {
        final List<String> heard = new ArrayList<>();

        FakeConn() {
            super(null, null, null);
        }

        @Override
        void say(String line) {
            heard.add(line);
        }
    }

    private FakePower power;
    private FakeInput input;
    private PhoneHelper helper;

    @Before
    public void setUp() {
        power = new FakePower();
        input = new FakeInput();
        helper = new PhoneHelper("com.ccrstech.linuxondroid", power, input);
    }

    // ── the panel ──

    @Test
    public void offDarkensThePanelAndOnLightsItAgain() {
        FakeConn c = new FakeConn();
        assertEquals("off", helper.handle(c, "off"));
        assertFalse(power.lit);
        assertTrue(helper.isDark());
        assertEquals("on", helper.handle(c, "on"));
        assertTrue(power.lit);
        assertFalse(helper.isDark());
    }

    @Test
    public void theConnectionThatDarkenedThePanelLightsItWhenItEnds() {
        // The app's process killed or updated: the kernel closes its socket.
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        helper.ended(c);
        assertTrue(power.lit);
        assertEquals(List.of("off", "on"), power.calls);
    }

    @Test
    public void anOldConnectionEndingLeavesTheNewOnesDarkPanel() {
        // The app back after its process went: the old connection's end comes after the new one's dark.
        FakeConn old = new FakeConn();
        FakeConn now = new FakeConn();
        helper.handle(old, "off");
        helper.handle(now, "off");
        helper.ended(old);
        assertFalse("the new connection's dark panel stays", power.lit);
        helper.ended(now);
        assertTrue(power.lit);
    }

    @Test
    public void aConnectionThatNeverDarkenedLightsNothing() {
        FakeConn c = new FakeConn();
        helper.handle(c, "on");
        helper.ended(c);
        assertEquals(List.of(), power.calls);
    }

    @Test
    public void thePowerButtonWhileDarkWakesThePhoneAndTellsTheApp() {
        // A person presses the phone's power button: Android puts the phone to sleep
        // (it believed the screen was on), and the helper wakes it, so the press
        // brings the screen back.
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        power.awake = false;
        helper.lookAwake();
        assertEquals(List.of("off", "wake"), power.calls);
        assertTrue(power.lit);
        assertEquals(List.of("asleep"), c.heard);
        assertFalse(helper.isDark());
        // The panel is Android's now: the connection's end lights nothing more.
        helper.ended(c);
        assertEquals(List.of("off", "wake"), power.calls);
    }

    @Test
    public void theComputersOwnLockIsSaidFirstAndLeftAlone() {
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        assertEquals("on", helper.handle(c, "forget"));
        power.awake = false;
        helper.lookAwake();
        assertEquals("not woken", List.of("off"), power.calls);
        assertEquals(List.of(), c.heard);
        helper.ended(c);
        assertEquals("a sleeping phone's panel is not lit", List.of("off"), power.calls);
    }

    @Test
    public void anAsleepPhoneIsNotDarkened() {
        power.awake = false;
        FakeConn c = new FakeConn();
        assertEquals("error asleep", helper.handle(c, "off"));
        assertEquals(List.of(), power.calls);
        assertFalse(helper.isDark());
    }

    @Test
    public void aPanelThatCannotBeFoundSaysSoAndStaysLit() {
        power.failNext = true;
        FakeConn c = new FakeConn();
        String r = helper.handle(c, "off");
        assertTrue(r, r.startsWith("error ") && r.contains("no panel found"));
        assertFalse(helper.isDark());
        helper.ended(c);
        assertEquals(List.of("off"), power.calls);
    }

    @Test
    public void aPanelThatWouldNotLightWhenItsConnectionEndedStaysOnRecordUntilLit() {
        // The app's process gone while dark, and the first try to light the panel fails.
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        power.failNext = true;
        helper.ended(c);
        assertFalse(power.lit);
        assertTrue("still dark because of the helper: a new connection is told so", helper.isDark());
        // The app back: its "on" lights it.
        FakeConn back = new FakeConn();
        assertEquals("on", helper.handle(back, "on"));
        assertTrue(power.lit);
        assertFalse(helper.isDark());
        assertEquals(List.of("off", "on", "on"), power.calls);
    }

    @Test
    public void aPanelLeftDarkByAnEndedConnectionIsLitAtTheWatchsNextLook() {
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        power.failNext = true;
        helper.ended(c);
        assertFalse(power.lit);
        helper.relightLeft();
        assertTrue(power.lit);
        assertFalse(helper.isDark());
        helper.relightLeft();
        assertEquals("nothing more once lit", List.of("off", "on", "on"), power.calls);
    }

    @Test
    public void theWatchLeavesALiveConnectionsDarkPanelAlone() {
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        helper.relightLeft();
        assertFalse(power.lit);
        assertEquals(List.of("off"), power.calls);
    }

    @Test
    public void theWatchDoesNotLightANewConnectionsDarkPanelForAnOldOne() {
        // The old connection's panel could not be lit; the app came back and wants it dark.
        FakeConn old = new FakeConn();
        helper.handle(old, "off");
        power.failNext = true;
        helper.ended(old);
        FakeConn now = new FakeConn();
        assertEquals("off", helper.handle(now, "off"));
        helper.relightLeft();
        assertFalse("the new connection's dark panel stays", power.lit);
        assertTrue(helper.isDark());
    }

    @Test
    public void aPanelLeftDarkWhileThePhoneSleepsIsAndroidsAgain() {
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        power.failNext = true;
        helper.ended(c);
        power.awake = false;
        helper.relightLeft();
        assertFalse(helper.isDark());
        assertEquals("not lit by the helper: Android lights it when it wakes the phone", List.of("off", "on"), power.calls);
    }

    @Test
    public void quitLightsThePanelEvenWhenTheFirstTryFails() {
        // Nothing is left to try again once this helper has gone.
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        power.failNext = true;
        assertEquals("bye", helper.handle(c, "quit"));
        assertTrue(power.lit);
        assertFalse(helper.isDark());
    }

    @Test
    public void quitFromANewerAppLightsThePanelAndLiftsTheFingerFirst() {
        FakeConn c = new FakeConn();
        helper.handle(c, "off");
        helper.handle(c, "touch d 5 5");
        assertEquals("bye", helper.handle(c, "quit"));
        assertTrue(power.lit);
        assertEquals(MotionEvent.ACTION_UP, ((MotionEvent) input.events.get(input.events.size() - 1)).getActionMasked());
    }

    @Test
    public void anythingElseIsRefused() {
        FakeConn c = new FakeConn();
        assertEquals("error unknown rm -rf /", helper.handle(c, "rm -rf /"));
        assertEquals(List.of(), power.calls);
        assertEquals(List.of(), input.events);
    }

    // ── input ──

    @Test
    public void aFingerIsATouchscreensDownMovesAndUp() {
        FakeConn c = new FakeConn();
        assertNull("input has no answer", helper.handle(c, "touch d 100 200"));
        helper.handle(c, "touch m 110 230");
        helper.handle(c, "touch u 110 230");
        assertEquals(3, input.events.size());
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        long downAt = ((MotionEvent) input.events.get(0)).getDownTime();
        for (int i = 0; i < 3; i++) {
            MotionEvent e = (MotionEvent) input.events.get(i);
            assertEquals(actions[i], e.getActionMasked());
            assertEquals(InputDevice.SOURCE_TOUCHSCREEN, e.getSource());
            assertEquals(MotionEvent.TOOL_TYPE_FINGER, e.getToolType(0));
            assertEquals("one touch: one down time", downAt, e.getDownTime());
        }
        assertEquals(110f, ((MotionEvent) input.events.get(2)).getX(), 0.01);
        assertEquals(230f, ((MotionEvent) input.events.get(2)).getY(), 0.01);
    }

    @Test
    public void aFingerLeftDownIsLiftedWhenTheAppGoes() {
        FakeConn c = new FakeConn();
        helper.handle(c, "touch d 50 60");
        helper.handle(c, "touch m 70 80");
        helper.ended(c);
        MotionEvent last = (MotionEvent) input.events.get(input.events.size() - 1);
        assertEquals(MotionEvent.ACTION_UP, last.getActionMasked());
        assertEquals(70f, last.getX(), 0.01);
        // Nothing more after that: a second end lifts nothing.
        int n = input.events.size();
        helper.ended(c);
        assertEquals(n, input.events.size());
    }

    @Test
    public void aNewPressLiftsAFingerWhoseUpWasLost() {
        FakeConn c = new FakeConn();
        helper.handle(c, "touch d 10 10");
        helper.handle(c, "touch d 20 20");
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_DOWN),
                List.of(((MotionEvent) input.events.get(0)).getActionMasked(),
                        ((MotionEvent) input.events.get(1)).getActionMasked(),
                        ((MotionEvent) input.events.get(2)).getActionMasked()));
    }

    @Test
    public void movesWithoutAFingerDownAreDropped() {
        FakeConn c = new FakeConn();
        helper.handle(c, "touch m 10 10");
        helper.handle(c, "touch u 10 10");
        assertEquals(List.of(), input.events);
    }

    @Test
    public void theWheelIsAMousesScroll() {
        FakeConn c = new FakeConn();
        helper.handle(c, "scroll 300 400 0.0 -1.5");
        MotionEvent e = (MotionEvent) input.events.get(0);
        assertEquals(MotionEvent.ACTION_SCROLL, e.getActionMasked());
        assertEquals(InputDevice.SOURCE_MOUSE, e.getSource());
        assertEquals(-1.5f, e.getAxisValue(MotionEvent.AXIS_VSCROLL), 0.001);
        assertEquals(0f, e.getAxisValue(MotionEvent.AXIS_HSCROLL), 0.001);
        assertEquals(300f, e.getX(), 0.01);
    }

    @Test
    public void aKeyIsAKeyboardsKey() {
        FakeConn c = new FakeConn();
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        helper.handle(c, "key d " + KeyEvent.KEYCODE_A + " " + ctrl);
        helper.handle(c, "key u " + KeyEvent.KEYCODE_A + " " + ctrl);
        KeyEvent d = (KeyEvent) input.events.get(0), u = (KeyEvent) input.events.get(1);
        assertEquals(KeyEvent.ACTION_DOWN, d.getAction());
        assertEquals(KeyEvent.ACTION_UP, u.getAction());
        assertEquals(KeyEvent.KEYCODE_A, d.getKeyCode());
        assertTrue(d.isCtrlPressed());
        assertEquals(InputDevice.SOURCE_KEYBOARD, d.getSource());
    }

    @Test
    public void aBrokenInputLineIsDroppedQuietly() {
        FakeConn c = new FakeConn();
        assertNull(helper.handle(c, "touch d nowhere"));
        assertNull(helper.handle(c, "key d"));
        assertEquals(List.of(), input.events);
        assertEquals(List.of(), c.heard);
    }

    // ── the key: the app and the helper prove it to each other over the loopback port ──

    /** The helper's side on one accepted socket, the app's on the other end; what each side ends with. */
    private static Object[] shake(byte[] helperKey, byte[] appKey) throws Exception {
        PhoneHelper h = new PhoneHelper("com.ccrstech.linuxondroid", new FakePower(), new FakeInput());
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            final PhoneHelper.Conn[] helperSide = new PhoneHelper.Conn[1];
            Thread t = new Thread(() -> {
                try {
                    helperSide[0] = h.handshake(server.accept(), helperKey);
                } catch (java.io.IOException ignored) {
                    // the test's client failed first
                }
            });
            t.start();
            java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort());
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
            Object appSide;
            try {
                appSide = PhoneHelperLink.handshake(s, in, appKey);
            } catch (java.io.IOException e) {
                appSide = e;
            }
            t.join(5000);
            s.close();
            return new Object[]{helperSide[0], appSide};
        }
    }

    @Test
    public void theAppAndTheHelperWithTheSameKeyTakeEachOther() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".getBytes();
        Object[] r = shake(key, key);
        assertTrue("the helper took the app", r[0] instanceof PhoneHelper.Conn);
        assertTrue("the app took the helper: " + r[1], r[1] instanceof String[]);
        assertEquals("on", ((String[]) r[1])[0]);
        assertEquals("the helper's version, for the app to compare", String.valueOf(PhoneHelper.PROTOCOL), ((String[]) r[1])[2]);
    }

    @Test
    public void aClientWithoutTheKeyIsRefusedByTheHelper() throws Exception {
        // Another app on the phone, which can reach the loopback port but has not the key.
        Object[] r = shake("the app's key".getBytes(), "a guess".getBytes());
        assertNull("the helper refused it", r[0]);
        assertTrue("and the client heard no proof", r[1] instanceof java.io.IOException);
    }

    @Test
    public void proofsAreTheKeysAndTheChallengesAndSaySideIsWhich() {
        byte[] key = "k".getBytes();
        String ch = PhoneHelper.challenge();
        assertEquals(32, ch.length());
        assertTrue(PhoneHelper.same(PhoneHelper.proof(key, "app", ch), PhoneHelper.proof(key, "app", ch)));
        // The helper's proof is not the app's: one cannot be sent back as the other.
        assertFalse(PhoneHelper.same(PhoneHelper.proof(key, "app", ch), PhoneHelper.proof(key, "helper", ch)));
        assertFalse(PhoneHelper.same(PhoneHelper.proof(key, "app", ch), PhoneHelper.proof("other".getBytes(), "app", ch)));
        assertFalse(PhoneHelper.same(null, "x"));
    }

    // ── the app's worker and the helper, over a real loopback connection ──

    /**
     * This test's helper on a loopback port: the helper's own handshake and
     * lines (run()'s loop, written out here because run() ends the process on
     * quit), with the stand-in panel.
     */
    private static java.net.ServerSocket serveHelper(PhoneHelper h, byte[] key) throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                java.net.Socket s;
                try {
                    s = server.accept();
                } catch (java.io.IOException closed) {
                    return;
                }
                Thread one = new Thread(() -> {
                    PhoneHelper.Conn c = h.handshake(s, key);
                    if (c == null) return;
                    try {
                        String line;
                        while ((line = c.reader().readLine()) != null) {
                            String reply = h.handle(c, line.trim());
                            if (reply != null) c.say(reply);
                        }
                    } catch (java.io.IOException gone) {
                        // the app's end
                    } finally {
                        h.ended(c);
                        c.close();
                    }
                }, "test-helper-conn");
                one.setDaemon(true);
                one.start();
            }
        }, "test-helper");
        accept.setDaemon(true);
        accept.start();
        return server;
    }

    private static boolean until(java.util.function.BooleanSupplier ok, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return ok.getAsBoolean();
    }

    @Test
    public void anOnTheHelperCouldNotCarryOutIsNotReportedAsLitAndIsTriedAgain() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        // The worker keeps the first context it was given (a static): this test's.
        java.lang.reflect.Field kept = PhoneHelperLink.class.getDeclaredField("app");
        kept.setAccessible(true);
        kept.set(null, null);
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.PowerManager.class)).setIsInteractive(true);
        String key = "5a".repeat(32);
        java.net.ServerSocket server = serveHelper(new PhoneHelper(app.getPackageName(), power, input),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Object page = new Object();
        Object another = new Object();
        try {
            assertTrue(PhoneHelperLink.adopt(app, String.valueOf(server.getLocalPort()), key));
            PhoneHelperLink.session(app, page, true, true);
            assertTrue("dark for the page", until(() -> "off".equals(PhoneHelperLink.state()) && !power.lit, 8000));
            // The page wants it lit again, and the helper cannot: its answer is an error.
            power.failNext = true;
            PhoneHelperLink.session(app, page, true, false);
            assertTrue("the helper was asked", until(() -> power.calls.size() >= 2, 5000));
            assertFalse("an error is not reported as lit", until(() -> "on".equals(PhoneHelperLink.state()), 1500));
            assertEquals("off", PhoneHelperLink.state());
            assertFalse(power.lit);
            // Tried again at the worker's next look: a session's change here (else within 5 s).
            PhoneHelperLink.session(app, another, true, false);
            assertTrue("lit on the next try", until(() -> "on".equals(PhoneHelperLink.state()) && power.lit, 8000));
            assertEquals(List.of("off", "on", "on"), power.calls);
        } finally {
            PhoneHelperLink.session(app, page, false, false);
            PhoneHelperLink.session(app, another, false, false);
            until(() -> "none".equals(PhoneHelperLink.state()), 8000);
            server.close();
        }
    }

    // ── screens Android keeps from the accessibility service ──

    @Test
    public void protectedScreensAreKnownByTheirApp() {
        int app = AccessibilityWindowInfo.TYPE_APPLICATION, system = AccessibilityWindowInfo.TYPE_SYSTEM;
        assertEquals("permission", PhoneInput.protectedKind("com.google.android.permissioncontroller", app));
        assertEquals("permission", PhoneInput.protectedKind("com.android.permissioncontroller", app));
        assertEquals("install", PhoneInput.protectedKind("com.google.android.packageinstaller", app));
        assertEquals("system", PhoneInput.protectedKind("com.android.systemui", app));
        // SystemUI's bars and its notification shade take the service's taps.
        assertNull(PhoneInput.protectedKind("com.android.systemui", system));
        assertNull(PhoneInput.protectedKind("com.android.settings", app));
        assertNull(PhoneInput.protectedKind(null, app));
    }
}
