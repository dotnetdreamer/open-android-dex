package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

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
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Screen off's helper started by the app itself (PhoneHelperStart), off a
 * phone: what the Settings card and the computer's window are told in each
 * state of the phone's own settings, the setup's steps and its pairing
 * notification, the code box, and PhoneHelperDoor's caller check. Pairing
 * and the adb link themselves are AdbWireTest's; a real phone's are
 * test/phone-remote's.
 *
 * <p>The point of most of it: with no Developer options, no Wireless
 * debugging and no pairing, nothing breaks; the card says what Screen off
 * would need, and the rest of Remote desktop is as before.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {BrowserDisplayShadow.class, BrowserGpuShadow.class, BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class PhoneHelperStartTest {

    private Context app;

    @Before
    public void phone() throws Exception {
        // The kit's phone has All files access, which Android 10 has no app-op for.
        if (android.os.Build.VERSION.SDK_INT >= 30) BrowserTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        BrowserGpuShadow.available = true;
        BrowserAddressShadow.wifi("192.168.2.25");
        for (File d : new File[]{PhoneRemote.dir(app), PhoneHelperStart.dir(app)}) {
            if (d.isDirectory()) {
                for (File f : d.listFiles()) f.delete();
            }
        }
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        Settings.Global.putString(app.getContentResolver(), ChildProcessLimit.FLAG, "false");
        // A phone as most come: no Developer options, so no Wireless debugging.
        developer(false);
        wireless(false);
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        onWifi(true);
        PhoneRemote.processStartForTest = android.os.SystemClock.elapsedRealtime();
        PhoneHelperStart.forgetOutcome();
        PhoneRemote.setEnabled(app, true);
        BrowserTestKit.drainServices();
    }

    @After
    public void done() {
        PhoneRemote.processStartForTest = -1;
        ShadowBinder.reset();
    }

    private void developer(boolean on) {
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, on ? 1 : 0);
    }

    private void wireless(boolean on) {
        Settings.Global.putInt(app.getContentResolver(), "adb_wifi_enabled", on ? 1 : 0);
    }

    private void paired() throws Exception {
        File f = new File(PhoneHelperStart.dir(app), "paired");
        assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
        Files.write(f.toPath(), "adb-TEST-x1".getBytes(StandardCharsets.UTF_8));
    }

    /** The phone on Wi-Fi (Wireless debugging's network) or on mobile data only. */
    private void onWifi(boolean wifi) {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        shadowOf(cm).clearAllNetworks();
        Network n = ShadowNetwork.newInstance(wifi ? 101 : 102);
        NetworkInfo info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                wifi ? ConnectivityManager.TYPE_WIFI : ConnectivityManager.TYPE_MOBILE, 0, true, NetworkInfo.State.CONNECTED);
        shadowOf(cm).addNetwork(n, info);
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        shadowOf(caps).addTransportType(wifi ? NetworkCapabilities.TRANSPORT_WIFI : NetworkCapabilities.TRANSPORT_CELLULAR);
        shadowOf(cm).setNetworkCapabilities(n, caps);
    }

    // ── what the phone's settings come to ──

    @Test
    @Config(sdk = 29)
    public void android10_needsAComputer() {
        assertEquals(PhoneHelperStart.PC, PhoneHelperStart.state(app));
    }

    @Test
    public void notPaired_isSetup_whateverTheSwitches() {
        assertEquals(PhoneHelperStart.SETUP, PhoneHelperStart.state(app));
        developer(true);
        wireless(true);
        assertEquals(PhoneHelperStart.SETUP, PhoneHelperStart.state(app));
    }

    @Test
    public void paired_saysWhatIsMissing() throws Exception {
        paired();
        assertEquals(PhoneHelperStart.DEV_OFF, PhoneHelperStart.state(app));
        developer(true);
        // Wireless debugging off, and no permission to switch it: the person switches it.
        assertEquals(PhoneHelperStart.WIRELESS_OFF, PhoneHelperStart.state(app));
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        // The app may switch it, on Wi-Fi only.
        assertEquals(PhoneHelperStart.WAITING, PhoneHelperStart.state(app));
        onWifi(false);
        assertEquals(PhoneHelperStart.WIFI, PhoneHelperStart.state(app));
        // Already on (Wi-Fi then, whatever the networks list says): it can start.
        wireless(true);
        assertEquals(PhoneHelperStart.WAITING, PhoneHelperStart.state(app));
    }

    @Test
    public void ensure_onAPhoneNotSetUp_triesNothing() {
        PhoneHelperStart.ensure(app, true);
        assertEquals(PhoneHelperStart.SETUP, PhoneHelperStart.state(app));
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
        assertEquals(0, Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0));
    }

    /**
     * The app switches Wireless debugging on only with WRITE_SECURE_SETTINGS
     * and on Wi-Fi, and marks that it did; a network Android refuses
     * (Android puts the switch back, here a stand-in thread) is not tried
     * again unasked; asked, it is.
     */
    @Test
    public void ensure_switchesWirelessDebugging_onlyWhereItMay() throws Exception {
        paired();
        developer(true);
        PhoneHelperStart.ensure(app, false);
        awaitIdle();
        assertEquals(PhoneHelperStart.WIRELESS_OFF, PhoneHelperStart.state(app));
        assertEquals(0, Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0));

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        // Android on a Wi-Fi never allowed: the switch goes back at once, and Android asks on the phone.
        final int[] putBack = {0};
        final boolean[] stop = {false};
        Thread android = new Thread(() -> {
            while (!stop[0]) {
                if (Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0) == 1) {
                    Settings.Global.putInt(app.getContentResolver(), "adb_wifi_enabled", 0);
                    putBack[0]++;
                }
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        android.start();
        try {
            PhoneHelperStart.ensure(app, false);
            awaitIdle();
            assertEquals(PhoneHelperStart.ASK, PhoneHelperStart.state(app));
            assertEquals(1, putBack[0]);
            assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
            // A computer's arrival on the same network: not asked again.
            PhoneHelperStart.ensure(app, false);
            awaitIdle();
            assertEquals(1, putBack[0]);
            // The person presses Screen off: asked again.
            PhoneHelperStart.ensure(app, true);
            awaitIdle();
            assertEquals(2, putBack[0]);
        } finally {
            stop[0] = true;
            android.join(2000);
        }
    }

    /** Switched on by the app: off again two minutes after the last computer; a person's own switch is left. */
    @Test
    public void computersGone_switchesOffOnlyWhatTheAppSwitchedOn() throws Exception {
        paired();
        developer(true);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        // The person's own Wireless debugging: never touched.
        wireless(true);
        PhoneHelperStart.computersGone(app);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.MINUTES);
        awaitIdle();
        assertEquals(1, Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0));
        // Switched on by the app (its mark): switched off after two minutes, not before.
        wireless(false);
        PhoneHelperStart.ensure(app, true); // switches on, then finds no adbd here: failed
        awaitIdle();
        assertEquals(1, Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0));
        assertTrue(new File(PhoneHelperStart.dir(app), "switched-on").exists());
        assertEquals(PhoneHelperStart.FAILED, PhoneHelperStart.state(app));
        PhoneHelperStart.computersGone(app);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(90, java.util.concurrent.TimeUnit.SECONDS);
        awaitIdle();
        assertEquals(1, Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0));
        org.robolectric.shadows.ShadowLooper.idleMainLooper(40, java.util.concurrent.TimeUnit.SECONDS);
        awaitIdle();
        assertEquals(0, Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0));
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
    }

    // ── the switch-off, whatever happens to the app's process ──

    private int wirelessNow() {
        return Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0);
    }

    /** Until Wireless debugging's switch is {@code want} (the switch-off runs on the start's thread), 10 s at most. */
    private void awaitWireless(int want) throws Exception {
        File mark = new File(PhoneHelperStart.dir(app), "switched-on");
        // Switched off is done when its mark has gone too (a moment after the switch).
        for (int i = 0; i < 200 && (wirelessNow() != want || (want == 0 && mark.exists())); i++) Thread.sleep(50);
    }

    /** Android starting the receiver an alarm names, as it does in a new process: no main thread's message in between. */
    private void deliver(org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm) throws Exception {
        Intent named = shadowOf(alarm.operation).getSavedIntent();
        android.content.BroadcastReceiver r = (android.content.BroadcastReceiver)
                Class.forName(named.getComponent().getClassName()).getDeclaredConstructor().newInstance();
        r.onReceive(app, named);
    }

    /** A start the app switched Wireless debugging on for: on, and marked as the app's. */
    private void switchedOnByAStart() throws Exception {
        paired();
        developer(true);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        wireless(false);
        PhoneHelperStart.ensure(app, true); // switches on, then finds no adbd here: failed
        awaitIdle();
        assertEquals(1, wirelessNow());
        assertTrue(new File(PhoneHelperStart.dir(app), "switched-on").exists());
    }

    /**
     * A start no computer watches (Settings' "Try again"), or one the
     * computer left while it still ran: no computer leaves afterwards, so
     * nothing ever switched Wireless debugging off. Now off two minutes after
     * the app switched it on.
     */
    @Test
    public void aStartNoComputerWatches_isSwitchedOffAgainAfterTwoMinutes() throws Exception {
        switchedOnByAStart();
        org.robolectric.shadows.ShadowLooper.idleMainLooper(90, java.util.concurrent.TimeUnit.SECONDS);
        awaitIdle();
        assertEquals("not before two minutes", 1, wirelessNow());
        org.robolectric.shadows.ShadowLooper.idleMainLooper(60, java.util.concurrent.TimeUnit.SECONDS);
        awaitWireless(0);
        assertEquals(0, wirelessNow());
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
    }

    /**
     * The app's process dies within the two minutes (Android needs the
     * memory, an update, a crash): the switch-off it had waiting on its main
     * thread dies with it. Android's alarm does not: it comes, and Android
     * starts the receiver it names in a new process.
     */
    @Test
    public void theSwitchOffOutlivesTheAppsProcess() throws Exception {
        switchedOnByAStart();
        PhoneHelperStart.computersGone(app);
        // The app's clock: under Robolectric the app's System.currentTimeMillis follows the
        // uptime clock (the test's own System.currentTimeMillis is the PC's).
        long left = android.os.SystemClock.uptimeMillis();
        // The process dies: what it had waiting on its main thread goes with it. Three minutes pass.
        PhoneHelperStart.processDiedForTest();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(3));
        assertEquals(1, wirelessNow());
        android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).peekNextScheduledAlarm();
        assertNotNull("an alarm Android keeps", alarm);
        // The wall clock, which runs on through the phone's deep sleep (the main thread's stops).
        assertEquals(android.app.AlarmManager.RTC, alarm.getType());
        assertEquals("two minutes after the last computer left", left + 120_000, alarm.getTriggerAtMs());
        deliver(alarm);
        awaitWireless(0);
        assertEquals(0, wirelessNow());
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
        assertNull("nothing left set", shadowOf(am).peekNextScheduledAlarm());
    }

    /**
     * The phone restarts (or the app is updated, or the desktop app's
     * clean-up force-stops it) with the switch-off pending: that ends Android's
     * alarms as well. The app's restart receiver takes it up.
     */
    @Test
    public void anUpdate_takesUpTheSwitchOffItsLastProcessLeft() throws Exception {
        switchedOnByAStart();
        PhoneHelperStart.computersGone(app);
        PhoneHelperStart.processDiedForTest();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5));
        android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).peekNextScheduledAlarm();
        if (alarm != null) am.cancel(alarm.operation);
        new RemoteResumeReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        awaitWireless(0);
        assertEquals(0, wirelessNow());
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
    }

    /**
     * A person force-stops the app (Android ends its alarms with it) and opens
     * it again later: Android 11 to 14 send no BOOT_COMPLETED for that, but
     * Remote desktop's listener starts, and takes up the switch-off.
     */
    @Test
    public void aForceStop_isTakenUpWhenRemoteDesktopsListenerStarts() throws Exception {
        switchedOnByAStart();
        PhoneHelperStart.computersGone(app);
        // Force-stopped: the process dies (its main thread's switch-off with it), and Android ends its alarms.
        PhoneHelperStart.processDiedForTest();
        android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).peekNextScheduledAlarm();
        assertNotNull(alarm);
        am.cancel(alarm.operation);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5));
        // Opened again five minutes later: Remote desktop's listener starts.
        Robolectric.buildService(PhoneListenService.class).create().startCommand(0, 1);
        awaitWireless(0);
        assertEquals(0, wirelessNow());
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
    }

    /**
     * The switch-off does not take: the app lost WRITE_SECURE_SETTINGS
     * meanwhile (a reinstall, a person's revoke). The mark stays and the
     * switch-off is tried again in ten minutes, instead of being dropped for
     * good; the helper's next start grants the permission again.
     */
    @Test
    public void aSwitchOffThatDoesNotTakeIsTriedAgain() throws Exception {
        switchedOnByAStart();
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        PhoneHelperStart.computersGone(app);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.SECONDS);
        awaitIdle();
        Thread.sleep(500);
        assertEquals("could not switch it", 1, wirelessNow());
        assertTrue("the mark stays", new File(PhoneHelperStart.dir(app), "switched-on").exists());
        android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).peekNextScheduledAlarm();
        assertNotNull("tried again later", alarm);
        assertTrue("in about ten minutes", alarm.getTriggerAtMs() - android.os.SystemClock.uptimeMillis() > 9 * 60_000);
        // The helper's next start grants it again: the next look switches it off.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(11, java.util.concurrent.TimeUnit.MINUTES);
        awaitWireless(0);
        assertEquals(0, wirelessNow());
        assertFalse(new File(PhoneHelperStart.dir(app), "switched-on").exists());
    }

    /** A mark with no time kept (an earlier version's): off two minutes after the restart, not at once. */
    @Test
    public void aMarkWithNoTime_isSwitchedOffTwoMinutesAfterTheRestart() throws Exception {
        switchedOnByAStart();
        assertTrue(new File(PhoneHelperStart.dir(app), "switch-off-at").delete());
        android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(am).peekNextScheduledAlarm();
        if (alarm != null) am.cancel(alarm.operation);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(30));
        new RemoteResumeReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        awaitIdle();
        Thread.sleep(300);
        assertEquals(1, wirelessNow());
        org.robolectric.shadows.ShadowLooper.idleMainLooper(90, java.util.concurrent.TimeUnit.SECONDS);
        awaitIdle();
        Thread.sleep(300);
        assertEquals("not before two minutes", 1, wirelessNow());
        org.robolectric.shadows.ShadowLooper.idleMainLooper(60, java.util.concurrent.TimeUnit.SECONDS);
        awaitWireless(0);
        assertEquals(0, wirelessNow());
    }

    /**
     * A computer watches when the time comes (it came back, or a start it
     * asked for ran long): nothing is switched off while it watches, an alarm
     * stays set (for a process that dies mid-session), and the switch-off
     * comes two minutes after it leaves.
     */
    @Test
    public void whileAComputerWatches_nothingIsSwitchedOff() throws Exception {
        java.lang.reflect.Field kept = PhoneHelperLink.class.getDeclaredField("app");
        kept.setAccessible(true);
        kept.set(null, null);
        switchedOnByAStart();
        Object computer = new Object();
        PhoneHelperLink.session(app, computer, true, false);
        try {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.MINUTES);
            awaitIdle();
            Thread.sleep(300);
            assertEquals(1, wirelessNow());
            android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
            assertNotNull("still an alarm, for a process that dies mid-session", shadowOf(am).peekNextScheduledAlarm());
        } finally {
            PhoneHelperLink.session(app, computer, false, false);
        }
        // The computer's leaving: the worker ends (no helper here) and starts the two minutes.
        for (int i = 0; i < 200 && PhoneHelperLink.watched(); i++) Thread.sleep(50);
        Thread.sleep(4000);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(90, java.util.concurrent.TimeUnit.SECONDS);
        awaitIdle();
        Thread.sleep(300);
        assertEquals("not before two minutes", 1, wirelessNow());
        org.robolectric.shadows.ShadowLooper.idleMainLooper(60, java.util.concurrent.TimeUnit.SECONDS);
        awaitWireless(0);
        assertEquals(0, wirelessNow());
    }

    /** Until the start's thread is done (it is one thread; a no-op task after it ends with it). */
    private static void awaitIdle() throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        while (PhoneHelperStart.STARTING.equals(PhoneHelperStart.state(RuntimeEnvironment.getApplication()))
                && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        Thread.sleep(150);
    }

    // ── the Settings card ──

    @Test
    public void card_withNoDeveloperOptions_offersTheSetup_andTheRestIsAsBefore() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_set_setup_line)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_set_setup)));
        // Remote desktop's own lines are there as ever.
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_title)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_off)));
        c.pause().stop().destroy();
    }

    @Test
    public void card_saysEachThingScreenOffNeeds() throws Exception {
        paired();
        ActivityController<LinuxSettingsActivity> c = page();
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.ph_set_devoff)));
        c.pause().stop().destroy();
        developer(true);
        c = page();
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.ph_set_wireless)));
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.ph_set_open_dev)));
        c.pause().stop().destroy();
        wireless(true);
        c = page();
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.ph_set_waiting)));
        c.pause().stop().destroy();
    }

    // ── the setup ──

    @Test
    public void setup_withNothingOn_startsAtDeveloperOptions_andPostsNoCode() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        Dialog d = openSetup(c);
        View r = d.getWindow().getDecorView();
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_setup_dev)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_setup_open_about)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_setup_wireless)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_setup_pair)));
        assertNull(pairingNotification());
        d.dismiss();
        c.pause().stop().destroy();
    }

    @Test
    public void setup_withBothOn_postsTheCodeBox() throws Exception {
        developer(true);
        wireless(true);
        ActivityController<LinuxSettingsActivity> c = page();
        Dialog d = openSetup(c);
        View r = d.getWindow().getDecorView();
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_setup_dev_done)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_setup_wireless_done)));
        Notification n = pairingNotification();
        assertNotNull(n);
        assertEquals(1, n.actions.length);
        RemoteInput[] box = n.actions[0].getRemoteInputs();
        assertNotNull(box);
        assertEquals(PhoneHelperStart.CODE_KEY, box[0].getResultKey());
        d.dismiss();
        c.pause().stop().destroy();
    }

    @Test
    public void setup_withNoWifi_saysSo() throws Exception {
        developer(true);
        onWifi(false);
        ActivityController<LinuxSettingsActivity> c = page();
        Dialog d = openSetup(c);
        assertTrue(BrowserTestKit.showsText(d.getWindow().getDecorView(), app.getString(R.string.ph_setup_wifi)));
        d.dismiss();
        c.pause().stop().destroy();
    }

    @Test
    public void setup_withNotificationsRefused_asksForThemFirst() throws Exception {
        developer(true);
        wireless(true);
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ActivityController<LinuxSettingsActivity> c = page();
        Dialog d = openSetup(c);
        assertTrue(BrowserTestKit.showsText(d.getWindow().getDecorView(), app.getString(R.string.ph_setup_notifs)));
        assertNull(pairingNotification());
        // Allowed (Android's question, or the app's notification settings): the box goes up by itself.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        BrowserTestKit.settle(1500);
        assertNotNull(pairingNotification());
        assertFalse(BrowserTestKit.showsText(d.getWindow().getDecorView(), app.getString(R.string.ph_setup_notifs)));
        d.dismiss();
        c.pause().stop().destroy();
    }

    @Test
    public void codeBox_takesSixDigitsOnly() {
        Intent typed = new Intent(PhoneHelperStart.ACTION_CODE).setClass(app, PhoneHelperStart.CodeReceiver.class);
        Bundle words = new Bundle();
        words.putCharSequence(PhoneHelperStart.CODE_KEY, "12 34");
        RemoteInput.addResultsToIntent(new RemoteInput[]{new RemoteInput.Builder(PhoneHelperStart.CODE_KEY).build()}, typed, words);
        new PhoneHelperStart.CodeReceiver().onReceive(app, typed);
        Notification n = pairingNotification();
        assertNotNull(n);
        assertEquals(app.getString(R.string.ph_pair_six), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse(PhoneHelperStart.paired(app));
    }

    // ── the door the helper hands its key through ──

    @Test
    public void door_takesAKeyFromTheShellOnly() throws Exception {
        PhoneHelperDoor door = Robolectric.setupContentProvider(PhoneHelperDoor.class, app.getPackageName() + PhoneHelperDoor.SUFFIX);
        String key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        File f = PhoneHelperLink.keyFile(app);
        f.delete();
        ShadowBinder.setCallingUid(10234); // another app
        assertNull(door.call(PhoneHelperDoor.METHOD, "45678 " + key, null));
        assertFalse(f.exists());
        ShadowBinder.setCallingUid(2000); // the shell
        assertNull(door.call(PhoneHelperDoor.METHOD, "45678 nothex", null));
        assertNull(door.call("other", "45678 " + key, null));
        assertFalse(f.exists());
        Bundle ok = door.call(PhoneHelperDoor.METHOD, "45678 " + key, null);
        assertNotNull(ok);
        assertEquals(PhoneHelperDoor.OK, ok.getString(PhoneHelperDoor.METHOD));
        assertEquals("45678 " + key, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        String[] k = PhoneHelperLink.key(app);
        assertEquals("45678", k[0]);
        assertEquals(key, k[1]);
        // The computer's command now starts a helper with the same port and key.
        assertTrue(PhoneHelperLink.startCommand(app).contains(" 45678 " + key));
    }

    @Test
    public void selfStart_carriesNoKey() {
        String cmd = PhoneHelperLink.selfStartCommand(app);
        String[] k = PhoneHelperLink.key(app);
        assertFalse(cmd.contains(k[1]));
        assertTrue(cmd.contains(" --register"));
        assertTrue(cmd.contains("pm grant " + app.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
        // adbd's plain shell takes its process group along when it ends: the
        // shell waits for the helper's own session before it says started.
        assertTrue(cmd.contains(" --register >/dev/null 2>&1 </dev/null & " + PhoneHelperLink.DETACH_WAIT + "echo started"));
        assertTrue(cmd.endsWith("echo started"));
    }

    // ── helpers ──

    private Notification pairingNotification() {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        return shadowOf(nm).getNotification(0x65);
    }

    private Dialog openSetup(ActivityController<LinuxSettingsActivity> c) throws Exception {
        View r = root(c.get());
        View link = target(r, app.getString(R.string.ph_set_setup));
        assertNotNull("the Set up link", link);
        link.performClick();
        BrowserTestKit.settle(400);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
        return d;
    }

    private ActivityController<LinuxSettingsActivity> page() throws Exception {
        ActivityController<LinuxSettingsActivity> c = Robolectric.buildActivity(LinuxSettingsActivity.class).setup();
        BrowserTestKit.settle(400);
        return c;
    }

    private static View root(Activity a) {
        return a.getWindow().getDecorView();
    }

    /** The view showing {@code words}, or its nearest clickable ancestor. */
    private static View target(View root, String words) {
        for (View v : BrowserTestKit.all(root)) {
            if (v instanceof android.widget.TextView && words.contentEquals(((android.widget.TextView) v).getText())) {
                View t = v;
                while (t != null && !t.isClickable()) {
                    t = t.getParent() instanceof View ? (View) t.getParent() : null;
                }
                return t != null ? t : v;
            }
        }
        return null;
    }
}
