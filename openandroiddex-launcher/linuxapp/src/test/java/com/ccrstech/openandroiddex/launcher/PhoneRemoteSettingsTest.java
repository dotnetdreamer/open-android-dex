package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Remote desktop's card on LinuxOnDroid's Settings page: the switch and what it
 * asks Android for, the control service's words before Android's settings,
 * the three qualities, starting with the phone; and pictures in three
 * languages (saved when asked for: {@code -PbrowserShots=<folder>}).
 *
 * <p>Off a phone, under Robolectric. The stream, the tunnel and click to
 * picture are test/phone-remote's, on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {BrowserDisplayShadow.class, BrowserGpuShadow.class, BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class PhoneRemoteSettingsTest {

    private Context app;
    private File dir;

    @Before
    public void phone() throws Exception {
        BrowserTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        BrowserGpuShadow.available = true;
        BrowserAddressShadow.wifi("192.168.2.25");
        dir = PhoneRemote.dir(app);
        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) f.delete();
        }
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Global.putString(app.getContentResolver(), ChildProcessLimit.FLAG, "false");
        // A phone as it comes: PROJECT_MEDIA at its default, which Android
        // answers with "ignore" (Robolectric would say "allowed").
        unattended(false);
        // The app's process has just started (Robolectric says 0, as if
        // long ago): Android still has time to connect control.
        PhoneRemote.processStartForTest = android.os.SystemClock.elapsedRealtime();
        BrowserTestKit.drainServices();
    }

    @org.junit.After
    public void processStartIsAndroids() {
        PhoneRemote.processStartForTest = -1;
    }

    private void unattended(boolean on) throws Exception {
        android.app.AppOpsManager ops = app.getSystemService(android.app.AppOpsManager.class);
        int op = android.app.AppOpsManager.class.getField("OP_PROJECT_MEDIA").getInt(null);
        shadowOf(ops).setMode(op, android.os.Process.myUid(), app.getPackageName(),
                on ? android.app.AppOpsManager.MODE_ALLOWED : android.app.AppOpsManager.MODE_IGNORED);
    }

    @Test
    public void unattendedAccess_isSaid_onlyWhenAComputerGaveIt() throws Exception {
        PhoneRemote.setEnabled(app, true);
        assertFalse(PhoneRemote.unattended(app));
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_unattended_on)));
        c.pause().stop().destroy();
        unattended(true);
        assertTrue(PhoneRemote.unattended(app));
        c = page();
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_unattended_on)));
        c.pause().stop().destroy();
    }

    /**
     * Control was on (the control service's mark) and Android turned control
     * off without the user (the app was force-stopped, as a Xiaomi phone's
     * Clear all does): the card says so, not just "Control is off", and
     * still offers to turn it on. Turned off in Android's settings (no mark):
     * the plain words.
     */
    @Test
    public void controlTurnedOffByAndroid_isSaid() throws Exception {
        PhoneRemote.setEnabled(app, true);
        PhoneRemote.markControl(app, true);
        assertTrue(PhoneRemote.controlLost(app));
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_lost)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_off)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_turn_on)));
        c.pause().stop().destroy();

        // Back on: neither line, and nothing lost.
        controlOn();
        assertFalse(PhoneRemote.controlLost(app));
        c = page();
        r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_on)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_lost)));
        c.pause().stop().destroy();

        // Turned off by the user (the unbind clears the mark): the plain words.
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        PhoneRemote.markControl(app, false);
        c = page();
        r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_off)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_lost)));
        c.pause().stop().destroy();
    }

    /** On a Xiaomi phone only, a word on Clear all in Recent apps; on others, none. */
    @Test
    public void xiaomiTip_onlyOnXiaomi() throws Exception {
        PhoneRemote.setEnabled(app, true);
        String was = android.os.Build.MANUFACTURER;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MANUFACTURER", "Xiaomi");
            ActivityController<LinuxSettingsActivity> c = page();
            assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_xiaomi_tip)));
            c.pause().stop().destroy();
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MANUFACTURER", "samsung");
            c = page();
            assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_xiaomi_tip)));
            c.pause().stop().destroy();
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MANUFACTURER", was);
        }
    }

    /**
     * The page made again while Android's battery question is up (the phone
     * turned, the theme changed): screen sharing's question still follows
     * the answer.
     */
    @Test
    public void batteryQuestion_outlivesThePageBeingMadeAgain() throws Exception {
        PhoneRemote.setKeepsReady(app, true); // a share to hold: asked for at the switch
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_title);
        Intent battery = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull(battery);
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.getAction());
        c.recreate();
        BrowserTestKit.settle(400);
        shadowOf(c.get()).receiveResult(battery, Activity.RESULT_OK, null);
        BrowserTestKit.settle(200);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("screen sharing's question, after the page was made again", next);
        assertEquals(PhoneConsentActivity.class.getName(), next.getComponent().getClassName());
        c.pause().stop().destroy();
    }

    /**
     * A phone without Android's two-button battery question: its list of
     * battery optimisation instead, and screen sharing's question only once
     * the person is back from the list (until 2026-11-22 it opened over the
     * list at once).
     */
    @Test
    public void noBatteryQuestion_theListFirst_thenScreenSharing() throws Exception {
        // An intent nothing answers throws, as on a phone.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        android.content.pm.ResolveInfo list = new android.content.pm.ResolveInfo();
        list.activityInfo = new android.content.pm.ActivityInfo();
        list.activityInfo.packageName = "com.android.settings";
        list.activityInfo.name = "com.android.settings.Settings$HighPowerApplicationsActivity";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), list);
        PhoneRemote.setKeepsReady(app, true); // a share to hold: asked for at the switch
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_title);
        Intent first = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("the list, as this phone has no question", first);
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, first.getAction());
        assertNull("screen sharing's question waits for the person to come back",
                shadowOf(c.get()).getNextStartedActivity());
        shadowOf(c.get()).receiveResult(first, Activity.RESULT_CANCELED, null);
        BrowserTestKit.settle(200);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("then screen sharing's question", next);
        assertEquals(PhoneConsentActivity.class.getName(), next.getComponent().getClassName());
        c.pause().stop().destroy();
    }

    /** Already left out of battery optimisation: no battery question, screen sharing's at once. */
    @Test
    public void switchedOn_whenAlreadyLeftOut_asksOnlyForScreenSharing() throws Exception {
        batteryExempt(true);
        PhoneRemote.setKeepsReady(app, true); // a share to hold: asked for at the switch
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_title);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("Android's question, straight away", next);
        assertEquals(PhoneConsentActivity.class.getName(), next.getComponent().getClassName());
        assertNull("and nothing else", shadowOf(c.get()).getNextStartedActivity());
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_bg_on)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_bg_allow)));
        c.pause().stop().destroy();
    }

    /**
     * Not left out: the card says Android may stop Remote desktop, and its
     * link asks Android's question again. Allow turns the line green, and
     * the link asks nothing more (no screen sharing question from it).
     */
    @Test
    public void notLeftOut_isSaid_andTheLinkAsksAndroid() throws Exception {
        PhoneRemote.setEnabled(app, true);
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_bg_off)));
        tap(r, R.string.pr_set_bg_allow);
        Intent battery = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull(battery);
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.getAction());
        assertEquals("package:" + app.getPackageName(), String.valueOf(battery.getData()));
        batteryExempt(true);
        shadowOf(c.get()).receiveResult(battery, Activity.RESULT_OK, null);
        BrowserTestKit.settle(400);
        assertNull("the link asks for nothing else", shadowOf(c.get()).getNextStartedActivity());
        r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_bg_on)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_bg_off)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_bg_allow)));
        c.pause().stop().destroy();
    }

    /**
     * MIUI's Autostart, on a Xiaomi phone only: off (MIUI's own op says
     * "ignore") is said, with the link to MIUI's list; on, nothing; on
     * another maker's phone, nothing whatever the op says.
     */
    @Test
    public void xiaomiAutostart_offIsSaid_onlyOnXiaomi() throws Exception {
        PhoneRemote.setEnabled(app, true);
        String was = android.os.Build.MANUFACTURER;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MANUFACTURER", "Xiaomi");
            autostart(android.app.AppOpsManager.MODE_IGNORED);
            assertEquals(Boolean.FALSE, PhoneRemote.xiaomiAutostart(app));
            ActivityController<LinuxSettingsActivity> c = page();
            View r = root(c.get());
            assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_autostart_off)));
            assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_autostart_allow)));
            c.pause().stop().destroy();

            autostart(android.app.AppOpsManager.MODE_ALLOWED);
            assertEquals(Boolean.TRUE, PhoneRemote.xiaomiAutostart(app));
            c = page();
            r = root(c.get());
            assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_autostart_off)));
            assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_autostart_allow)));
            c.pause().stop().destroy();

            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MANUFACTURER", "samsung");
            autostart(android.app.AppOpsManager.MODE_IGNORED);
            assertNull(PhoneRemote.xiaomiAutostart(app));
            c = page();
            assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_autostart_off)));
            c.pause().stop().destroy();
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MANUFACTURER", was);
        }
    }

    private void batteryExempt(boolean on) {
        shadowOf(app.getSystemService(android.os.PowerManager.class))
                .setIgnoringBatteryOptimizations(app.getPackageName(), on);
    }

    /** MIUI's Autostart op for this app, as MIUI's app ops would answer it. */
    private void autostart(int mode) {
        android.app.AppOpsManager ops = app.getSystemService(android.app.AppOpsManager.class);
        shadowOf(ops).setMode(PhoneRemote.MIUI_OP_AUTOSTART, android.os.Process.myUid(), app.getPackageName(), mode);
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
            if (v instanceof TextView && v.isShown() && words.contentEquals(((TextView) v).getText())) {
                if (v.hasOnClickListeners()) return v;
                for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
                    if (((View) p).hasOnClickListeners()) return (View) p;
                }
            }
        }
        throw new AssertionError("nothing shows " + words);
    }

    private static boolean checked(View row) {
        for (View v : BrowserTestKit.all(row)) if (v instanceof Switch) return ((Switch) v).isChecked();
        throw new AssertionError("no switch in the row");
    }

    private void tap(View root, int words) throws Exception {
        target(root, app.getString(words)).performClick();
        BrowserTestKit.settle(200);
    }

    private void controlOn() {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                PhoneRemote.controlComponent(app).flattenToString());
        Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /**
     * The row Android's Settings is asked to mark: in the page's arguments,
     * where AOSP reads it, and on the intent itself, the same row.
     */
    private static String markedRow(Intent i) {
        Bundle pageArgs = i.getBundleExtra(":settings:show_fragment_args");
        assertNotNull("the page's arguments carry the mark (AOSP reads it there)", pageArgs);
        String row = pageArgs.getString(":settings:fragment_args_key");
        assertEquals("the same row on the intent itself", row, i.getStringExtra(":settings:fragment_args_key"));
        return row;
    }

    /** Remote desktop's own row on most phones; on a Samsung, the "Installed apps" row that holds it. */
    private String rowToGoTo(String maker) {
        return maker.equals("samsung") ? "accessibility_installed_services"
                : PhoneRemote.controlComponent(app).flattenToString();
    }

    @Test
    public void offByDefault_inItsOwnSection() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue("the section", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_header)));
        assertTrue("says what it is for", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sub)));
        assertFalse("off until switched on", checked(target(r, app.getString(R.string.pr_set_title))));
        assertFalse(PhoneRemote.isEnabled(app));
        assertFalse("nothing under the switch while off",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_boot)));
        c.pause().stop().destroy();
    }

    /**
     * Without "Keep full speed ready" (the default since 2026-11-22): the
     * switch asks Android's battery question and nothing about screen
     * sharing, whatever the answer, as nothing is shared until a computer
     * opens the phone; the card says how the computer's arrival asks, with
     * nothing amber to fix, and no question after a restart.
     */
    @Test
    public void switchedOn_byDefault_asksNothingAboutScreenSharing() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_title);
        Intent battery = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull(battery);
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.getAction());
        shadowOf(c.get()).receiveResult(battery, Activity.RESULT_OK, null);
        BrowserTestKit.settle(200);
        assertNull("no question about screen sharing", shadowOf(c.get()).getNextStartedActivity());
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_asks_off)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_off)));
        assertFalse("nothing to fix", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_share)));
        assertFalse("the switch, off", checked(target(r, app.getString(R.string.pr_set_ready))));
        assertTrue("says what it costs", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_ready_sub)));
        assertFalse("no question after a restart for a share nobody keeps",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_boot)));
        c.pause().stop().destroy();

        // Already left out of battery optimisation: no question at all. With
        // control, the slow view's words.
        tap(root((c = page()).get()), R.string.pr_set_title); // off
        batteryExempt(true);
        controlOn();
        tap(root(c.get()), R.string.pr_set_title); // on
        assertNull("nothing asked", shadowOf(c.get()).getNextStartedActivity());
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_sharing_asks)));
        c.pause().stop().destroy();
    }

    /**
     * "Keep full speed ready" on: the share to hold is asked for at once
     * (someone is at the phone), the card says when none is held, and the
     * question after a restart comes with it. Off again: nothing asked, and
     * the restart question goes.
     */
    @Test
    public void keepReady_asksForScreenSharingNow_withTheRestartQuestion() throws Exception {
        PhoneRemote.setEnabled(app, true);
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_ready);
        assertTrue(PhoneRemote.keepsReady(app));
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("the share to hold, asked for now", next);
        assertEquals(PhoneConsentActivity.class.getName(), next.getComponent().getClassName());
        View r = root(c.get());
        assertTrue(checked(target(r, app.getString(R.string.pr_set_ready))));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_off)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_share)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_boot)));

        tap(r, R.string.pr_set_ready);
        assertFalse(PhoneRemote.keepsReady(app));
        assertNull("nothing asked", shadowOf(c.get()).getNextStartedActivity());
        r = root(c.get());
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_boot)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_asks_off)));
        c.pause().stop().destroy();
    }

    /** With unattended access the share starts by itself: no switch to keep one, no restart question. */
    @Test
    public void unattended_noKeepReadySwitch() throws Exception {
        PhoneRemote.setEnabled(app, true);
        PhoneRemote.setKeepsReady(app, true);
        PhoneRemote.setStartsWithPhone(app, true);
        unattended(true);
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_ready)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_boot)));
        c.pause().stop().destroy();
    }

    /** When a share nobody watches ends, and when a restart asks for one. */
    @Test
    public void whenSharingEnds_andWhenARestartAsks() throws Exception {
        PhoneRemote.setEnabled(app, true);
        PhoneRemote.setStartsWithPhone(app, true);
        assertTrue("by default: ends a little after the last computer", PhoneRemote.stopsWhenIdle(app));
        assertFalse("and nothing is asked after a restart", PhoneRemote.asksAfterRestart(app));
        PhoneRemote.setKeepsReady(app, true);
        assertFalse("kept ready: held", PhoneRemote.stopsWhenIdle(app));
        assertTrue(PhoneRemote.asksAfterRestart(app));
        PhoneRemote.setStartsWithPhone(app, false);
        assertFalse("the restart question is its own switch", PhoneRemote.asksAfterRestart(app));
        PhoneRemote.setStartsWithPhone(app, true);
        unattended(true);
        assertTrue("unattended: ends, and comes back by itself", PhoneRemote.stopsWhenIdle(app));
        assertFalse("nobody needs asking", PhoneRemote.asksAfterRestart(app));
    }

    @Test
    public void switchedOn_asksAndroidForScreenSharing_andShowsWhatIsNext() throws Exception {
        PhoneRemote.setKeepsReady(app, true); // a share to hold: asked for at the switch
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_title);
        assertTrue("remembered for every process", new File(dir, "on").exists());
        // Android's battery question first: the app is not left out of
        // battery optimisation yet (Robolectric's phone, as a new one is).
        Intent battery = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("Android's battery question, straight away", battery);
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.getAction());
        assertEquals("package:" + app.getPackageName(), String.valueOf(battery.getData()));
        assertNull("screen sharing's question waits for its answer", shadowOf(c.get()).getNextStartedActivity());
        // Deny as much as Allow: screen sharing's question comes next.
        shadowOf(c.get()).receiveResult(battery, Activity.RESULT_CANCELED, null);
        BrowserTestKit.settle(200);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("then Android's question about screen sharing", next);
        assertEquals(PhoneConsentActivity.class.getName(), next.getComponent().getClassName());
        View r = root(c.get());
        assertTrue(checked(target(r, app.getString(R.string.pr_set_title))));
        assertTrue("nothing shared yet, control off",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_off)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_share)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_off)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_boot)));
        // The picture's quality is the computer's window's to choose, not the phone's.
        for (String q : new String[]{"Low", "Medium", "High"}) {
            assertFalse("no " + q + " on the phone", BrowserTestKit.showsText(r, q));
        }

        // Off again: the marker goes, and so does everything under the switch.
        tap(r, R.string.pr_set_title);
        assertFalse(new File(dir, "on").exists());
        assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_boot)));
        c.pause().stop().destroy();
    }

    @Test
    public void control_saysWhatItDoes_beforeAndroidsSettings() throws Exception {
        PhoneRemote.setEnabled(app, true);
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), R.string.pr_set_control_turn_on);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("a disclosure first, not Android's page", d);
        View dv = d.getWindow().getDecorView();
        assertTrue(BrowserTestKit.showsText(dv, app.getString(R.string.pr_disclose_title)));
        assertTrue(BrowserTestKit.showsText(dv, app.getString(R.string.pr_disclose_body)));
        assertTrue("a way to say no", BrowserTestKit.showsText(dv, app.getString(R.string.pr_disclose_no)));
        assertEquals("nothing opened before the choice", null, shadowOf(c.get()).getNextStartedActivity());
        target(dv, app.getString(R.string.pr_disclose_yes)).performClick();
        BrowserTestKit.settle(200);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("Android's own page after Continue", next);
        assertTrue(next.getAction(), next.getAction().contains("ACCESSIBILITY"));
        c.pause().stop().destroy();
    }

    @Test
    public void controlOn_isSaid_andTheShareLinkStays() throws Exception {
        PhoneRemote.setEnabled(app, true);
        controlOn();
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_on)));
        assertTrue("by default: the computer's arrival asks, the slow view until then",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_asks)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_turn_on)));
        c.pause().stop().destroy();

        // A share kept ready and none held: the slow view carries on, and the link stays.
        PhoneRemote.setKeepsReady(app, true);
        c = page();
        r = root(c.get());
        assertTrue("with control on, the slow view carries on",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_sharing_slow)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_share)));
        c.pause().stop().destroy();
    }

    /**
     * Control on in Android's settings but not connected (Android 10 left it
     * so after an update): while the app has only just started, the card
     * gives Android time and says control is on; past the grace, the card
     * says it is not connected, and its link opens Android's accessibility
     * settings, where turning it off and on connects it.
     */
    @Test
    public void controlOnButNotConnected_isSaid_afterTheGrace() throws Exception {
        PhoneRemote.setEnabled(app, true);
        controlOn();
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse("just started: Android may still connect it", PhoneRemote.controlUnbound(app));
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_control_on)));
        c.pause().stop().destroy();

        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(PhoneRemote.UNBOUND_GRACE_MS + 5_000));
        assertTrue(PhoneRemote.controlUnbound(app));
        c = page();
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_unbound)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_on)));
        tap(r, R.string.pr_set_control_reconnect);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("Android's accessibility settings", next);
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, next.getAction());
        assertEquals("Remote desktop's own row marked", rowToGoTo(android.os.Build.MANUFACTURER), markedRow(next));
        c.pause().stop().destroy();

        // Control off in Android's settings: not "not connected", the plain words.
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        assertFalse(PhoneRemote.controlUnbound(app));
    }

    /**
     * Android keeps the page with control's own switch for app stores and its
     * own apps (a Galaxy S25 refuses it). After the words and Continue, the
     * accessibility list opens with the way to Remote desktop marked: its own
     * row on most phones, the "Installed apps" row on a Samsung.
     */
    @Test
    public void control_whenAndroidKeepsItsOwnPage_opensTheListWithTheWayMarked() throws Exception {
        // Only the accessibility list opens for an app here: the page for one service is refused.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        ComponentName list = new ComponentName("com.android.settings",
                "com.android.settings.Settings$AccessibilitySettingsActivity");
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        pm.addActivityIfNotPresent(list);
        IntentFilter f = new IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(list, f);

        for (String maker : new String[]{"Google", "samsung"}) {
            org.robolectric.shadows.ShadowBuild.setManufacturer(maker);
            PhoneRemote.setEnabled(app, true);
            ActivityController<LinuxSettingsActivity> c = page();
            tap(root(c.get()), R.string.pr_set_control_turn_on);
            Dialog d = ShadowDialog.getLatestDialog();
            target(d.getWindow().getDecorView(), app.getString(R.string.pr_disclose_yes)).performClick();
            BrowserTestKit.settle(200);
            Intent next = shadowOf(c.get()).getNextStartedActivity();
            assertNotNull(maker + ": Android's accessibility list", next);
            assertEquals(maker, Settings.ACTION_ACCESSIBILITY_SETTINGS, next.getAction());
            assertEquals(maker + ": the row to go to", rowToGoTo(maker), markedRow(next));
            c.pause().stop().destroy();
        }
    }

    /** Both control notifications open the accessibility list with the way to Remote desktop marked. */
    @Test
    public void controlNotifications_openTheListWithTheWayMarked() {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        int[] titles = {R.string.pr_notif_control, R.string.pr_notif_control_unbound};
        for (String maker : new String[]{"Google", "samsung"}) {
            org.robolectric.shadows.ShadowBuild.setManufacturer(maker);
            for (int title : titles) {
                nm.cancelAll();
                if (title == R.string.pr_notif_control) PhoneRemoteService.postControlLost(app);
                else PhoneRemoteService.postControlUnbound(app);
                Notification n = null;
                for (Notification one : shadowOf(nm).getAllNotifications()) {
                    CharSequence t = one.extras.getCharSequence(Notification.EXTRA_TITLE);
                    if (t != null && app.getString(title).contentEquals(t)) n = one;
                }
                assertNotNull(maker + ": " + app.getString(title), n);
                Intent tap = shadowOf(n.contentIntent).getSavedIntent();
                assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, tap.getAction());
                assertEquals(maker + ": " + app.getString(title), rowToGoTo(maker), markedRow(tap));
            }
        }
        nm.cancelAll();
    }

    @Test
    public void startingWithThePhone_isKept() throws Exception {
        PhoneRemote.setEnabled(app, true);
        PhoneRemote.setKeepsReady(app, true); // the restart question is for a share kept ready
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse(PhoneRemote.startsWithPhone(app));
        tap(root(c.get()), R.string.pr_set_boot);
        assertTrue(PhoneRemote.startsWithPhone(app));
        tap(root(c.get()), R.string.pr_set_boot);
        assertFalse(PhoneRemote.startsWithPhone(app));
        c.pause().stop().destroy();
    }

    /** "All files access" (the MANAGE_EXTERNAL_STORAGE app-op), as Android's own screen sets it. */
    private void allFiles(boolean on) {
        android.app.AppOpsManager ops = app.getSystemService(android.app.AppOpsManager.class);
        shadowOf(ops).setMode("android:manage_external_storage", android.os.Process.myUid(), app.getPackageName(),
                on ? android.app.AppOpsManager.MODE_ALLOWED : android.app.AppOpsManager.MODE_ERRORED);
    }

    @Test
    public void files_withAllFilesAccess_saysABrowserCanOpenThem() throws Exception {
        allFiles(true);
        PhoneRemote.setEnabled(app, true);
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue("a browser's Files has this phone's files",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_on)));
        assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_allow)));
        c.pause().stop().destroy();
    }

    @Test
    public void files_withoutAllFilesAccess_askFirst_thenOpenAndroidsScreen() throws Exception {
        allFiles(false);
        PhoneRemote.setEnabled(app, true);
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue("says the phone's files wait for the permission",
                BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_off)));
        BrowserTestKit.drainServices();
        tap(r, R.string.pr_set_files_allow);
        android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("what the permission is for, and what switching it off does, before Android's screen", d);
        assertEquals(app.getString(R.string.pr_set_files_grant_body), shadowOf(d).getMessage().toString());
        assertEquals("nothing opened before the choice", null, shadowOf(c.get()).getNextStartedActivity());
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        BrowserTestKit.settle(200);
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("Android's All files access screen, for this app", next);
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, next.getAction());
        assertEquals("package:" + app.getPackageName(), next.getDataString());

        // Allowed there, and back: the card says so by itself (it is read again every second).
        allFiles(true);
        BrowserTestKit.settle(1500);
        assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_files_on)));
        assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_files_allow)));
        c.pause().stop().destroy();
    }

    /**
     * Android 10: the storage permission alone is not access. Android can
     * grant the permission and still keep the storage from the app (legacy
     * storage refused); then the card says the files are off, as the door
     * does (noaccess), with no link, since asking again changes nothing. With
     * legacy storage on, the files are on. (Robolectric has Android 14's
     * framework here; the version Android 10 code reads is set for the test.)
     */
    @Test
    public void files_onAndroid10_needLegacyStorage_notThePermissionAlone() throws Exception {
        PhoneRemote.setEnabled(app, true);
        int was = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 29);
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE);
            org.robolectric.shadows.ShadowEnvironment.setIsExternalStorageLegacy(false);
            assertFalse("the permission, but the storage kept from the app", WebFiles.phoneFilesAllowed(app));
            ActivityController<LinuxSettingsActivity> c = page();
            View r = root(c.get());
            assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_off)));
            assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_on)));
            assertFalse("no link that cannot help", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_allow)));
            c.pause().stop().destroy();

            org.robolectric.shadows.ShadowEnvironment.setIsExternalStorageLegacy(true);
            assertTrue(WebFiles.phoneFilesAllowed(app));
            c = page();
            r = root(c.get());
            assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_on)));
            assertFalse(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_files_off)));
            c.pause().stop().destroy();
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", was);
        }
    }

    @Test
    public void files_inABuildWithoutAllFilesAccess_areNotOffered() throws Exception {
        // Google Play's build: MANAGE_EXTERNAL_STORAGE taken out of its manifest (Linux.sharedFolderOffered).
        org.robolectric.util.ReflectionHelpers.setStaticField(Linux.class, "sharedOffered", Boolean.FALSE);
        try {
            allFiles(false);
            PhoneRemote.setEnabled(app, true);
            ActivityController<LinuxSettingsActivity> c = page();
            View r = root(c.get());
            for (int words : new int[]{R.string.pr_set_files_on, R.string.pr_set_files_off, R.string.pr_set_files_allow}) {
                assertFalse("nothing of the phone's files: " + app.getString(words), BrowserTestKit.showsText(r, app.getString(words)));
            }
            assertTrue("the rest of the card as before", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_control_off)));
            c.pause().stop().destroy();
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(Linux.class, "sharedOffered", null);
        }
    }

    @Test
    public void qualities_holdTheirNumbers() {
        PhoneRemote.Quality low = PhoneRemote.Quality.of(PhoneRemote.LOW);
        PhoneRemote.Quality high = PhoneRemote.Quality.of(PhoneRemote.HIGH);
        PhoneRemote.Quality medium = PhoneRemote.Quality.of("nonsense");
        assertEquals("anything else is Medium", PhoneRemote.MEDIUM, medium.id);
        assertTrue(low.longSide < medium.longSide && high.longSide == 0);
        assertTrue(low.maxBitrate < medium.maxBitrate && medium.maxBitrate < high.maxBitrate);
        assertEquals("held to the ceiling", low.maxBitrate, low.bitrateFor(1440, 3120));
        assertEquals("held to the floor", medium.minBitrate, medium.bitrateFor(100, 100));
    }

    @Test
    public void pictures() throws Exception {
        shot("en-w412dp-h892dp-xxhdpi", "phone-remote-en-off", false, false);
        shot("en-w412dp-h892dp-xxhdpi", "phone-remote-en-on", true, false);
        shot("en-w412dp-h892dp-xxhdpi", "phone-remote-en-control", true, true);
        shot("de-w360dp-h780dp-xxhdpi", "phone-remote-de-360", true, false);
        shot("ar-w412dp-h892dp-xxhdpi", "phone-remote-ar", true, true);
        // "Keep full speed ready", at the card's end: off (the default), then on.
        shot("en-w412dp-h892dp-xxhdpi", "phone-remote-en-ready-off", true, true, R.string.pr_set_ready);
        PhoneRemote.setKeepsReady(app, true);
        shot("en-w412dp-h892dp-xxhdpi", "phone-remote-en-ready-on", true, true, R.string.pr_set_ready);
        shot("de-w360dp-h780dp-xxhdpi", "phone-remote-de-ready-on", true, true, R.string.pr_set_ready);
        PhoneRemote.setKeepsReady(app, false);
        unattended(true);
        shot("en-w412dp-h892dp-xxhdpi", "phone-remote-en-unattended", true, true);
    }

    private void shot(String qualifiers, String name, boolean on, boolean control) throws Exception {
        shot(qualifiers, name, on, control, R.string.pr_set_title);
    }

    private void shot(String qualifiers, String name, boolean on, boolean control, int scrollTo) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        PhoneRemote.setEnabled(app, on);
        if (control) {
            controlOn();
        } else {
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        }
        ActivityController<LinuxSettingsActivity> c = page();
        // The card is below the browser's: scrolled to, as a person would.
        View card = target(root(c.get()), app.getString(scrollTo));
        card.getParent().requestChildFocus(card, card);
        for (android.view.ViewParent p = card.getParent(); p != null; p = p.getParent()) {
            if (p instanceof android.widget.ScrollView) {
                int[] at = new int[2];
                card.getLocationInWindow(at);
                ((android.widget.ScrollView) p).scrollTo(0, Math.max(0, at[1] - 300));
                break;
            }
        }
        BrowserTestKit.settle(200);
        BrowserTestKit.save(BrowserTestKit.window(c.get()), name);
        c.pause().stop().destroy();
    }
}
