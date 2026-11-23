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
