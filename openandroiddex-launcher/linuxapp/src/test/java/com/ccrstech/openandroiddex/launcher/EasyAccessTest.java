package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.widget.Switch;
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
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Easy access's switch on Remote desktop's card (PhoneEasyAccess): the
 * switch is Android's own word for this app's PROJECT_MEDIA; on asks first,
 * off does not; the phone's helper sets it (here a real loopback helper with
 * Android's app-ops stood in for); without the one-time setup the switch opens
 * the setup; what went wrong is said under the switch.
 *
 * <p>Off a phone, under Robolectric. The helper's {@code cmd appops} on a real
 * phone: doc/phone-remote.md, "Easy access".
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {BrowserDisplayShadow.class, BrowserGpuShadow.class, BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class EasyAccessTest {

    private Context app;
    private java.net.ServerSocket helper;
    private PhoneHelperTest.FakeOps ops;

    @Before
    public void phone() throws Exception {
        BrowserTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        BrowserGpuShadow.available = true;
        BrowserAddressShadow.wifi("192.168.2.25");
        for (File d : new File[]{PhoneRemote.dir(app), PhoneHelperStart.dir(app)}) {
            if (d.isDirectory()) {
                for (File f : d.listFiles()) f.delete();
            }
        }
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Global.putString(app.getContentResolver(), ChildProcessLimit.FLAG, "false");
        // Set up for the helper: Developer options, Wireless debugging, paired.
        developer(true);
        Settings.Global.putInt(app.getContentResolver(), "adb_wifi_enabled", 1);
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        PhoneRemote.processStartForTest = android.os.SystemClock.elapsedRealtime();
        PhoneHelperStart.forgetOutcome();
        PhoneEasyAccess.forgetForTest();
        PhoneRemote.setEnabled(app, true);
        // A phone as it comes: PROJECT_MEDIA at Android's default, "ignore".
        unattended(false);
        BrowserTestKit.drainServices();
    }

    @After
    public void done() throws Exception {
        PhoneRemote.processStartForTest = -1;
        if (helper != null) helper.close();
        helper = null;
    }

    private void developer(boolean on) {
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, on ? 1 : 0);
    }

    private void paired() throws Exception {
        File f = new File(PhoneHelperStart.dir(app), "paired");
        assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
        Files.write(f.toPath(), "adb-TEST-x1".getBytes(StandardCharsets.UTF_8));
    }

    /** This app's PROJECT_MEDIA as Android would answer it. */
    private void unattended(boolean on) throws Exception {
        AppOpsManager ops = app.getSystemService(AppOpsManager.class);
        int op = AppOpsManager.class.getField("OP_PROJECT_MEDIA").getInt(null);
        shadowOf(ops).setMode(op, android.os.Process.myUid(), app.getPackageName(),
                on ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
    }

    /**
     * The phone's helper, running: a real one on a loopback port, with the
     * key this app keeps, and Android's app-ops stood in for. A change it is
     * asked for is Android's at once (the app's PROJECT_MEDIA), unless refused.
     */
    private void helperRuns() throws Exception {
        ops = new PhoneHelperTest.FakeOps();
        ops.android = (pkg, on) -> {
            try {
                unattended(on);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        String key = "e5".repeat(32);
        helper = PhoneHelperTest.serveHelper(new PhoneHelper(app.getPackageName(), new PhoneHelperTest.FakePower(),
                new PhoneHelperTest.FakeInput(), ops), key.getBytes(StandardCharsets.UTF_8));
        assertTrue(PhoneHelperLink.adopt(app, String.valueOf(helper.getLocalPort()), key));
    }

    /** No helper answers: the app's port points at a port nothing listens on. */
    private void noHelper() throws Exception {
        int free;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            free = s.getLocalPort();
        }
        assertTrue(PhoneHelperLink.adopt(app, String.valueOf(free), "1a".repeat(32)));
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

    private boolean easyOn(Activity a) {
        return checked(target(root(a), app.getString(R.string.pr_set_easy)));
    }

    private void tapEasy(Activity a) throws Exception {
        target(root(a), app.getString(R.string.pr_set_easy)).performClick();
        BrowserTestKit.settle(200);
    }

    /** The page's main thread kept going (its ticks redraw the card) until {@code ok}, in real time. */
    private static boolean until(BooleanSupplier ok, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            BrowserTestKit.settle(200);
            if (ok.getAsBoolean()) return true;
        }
        return ok.getAsBoolean();
    }

    /** A change done: nothing under way, and the card drawn again since (its tick, once a second). */
    private static void changed() throws InterruptedException {
        assertTrue("the change ended", until(() -> PhoneEasyAccess.working().isEmpty(), 15_000));
        BrowserTestKit.settle(1200);
    }

    // ── the switch ──

    /** The switch is Android's own word: on only while PROJECT_MEDIA lets the app through. */
    @Test
    public void theSwitchIsAndroidsOwnWord() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse(easyOn(c.get()));
        assertTrue("says what it does", BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_unattended_on)));
        c.pause().stop().destroy();
        unattended(true);
        c = page();
        assertTrue(easyOn(c.get()));
        c.pause().stop().destroy();
    }

    /**
     * Not set up for the helper: the card says Easy access needs Screen off's
     * setup, and the switch opens that setup instead of changing anything.
     */
    @Test
    public void notSetUp_theSwitchOpensScreenOffsSetup() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_easy_setup)));
        tapEasy(c.get());
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("a dialog", d);
        assertTrue(d.isShowing());
        View dr = d.getWindow().getDecorView();
        assertTrue("Screen off's setup", BrowserTestKit.showsText(dr, app.getString(R.string.ph_setup_title)));
        assertTrue("whose words say Easy access needs the same setup",
                BrowserTestKit.showsText(dr, app.getString(R.string.ph_setup_body))
                        && app.getString(R.string.ph_setup_body).contains(app.getString(R.string.pr_set_easy)));
        assertFalse("not Easy access's own question", BrowserTestKit.showsText(dr, app.getString(R.string.pr_easy_title)));
        d.dismiss();
        assertFalse(PhoneRemote.unattended(app));
        assertFalse(easyOn(c.get()));
        c.pause().stop().destroy();
    }

    /**
     * Set up, with the helper running: on asks first, then the helper sets
     * this app's PROJECT_MEDIA and the switch shows Android's new word; off
     * asks nothing and takes it away again.
     */
    @Test
    public void on_asksFirst_thenTheHelperSetsIt_andOffAsksNothing() throws Exception {
        paired();
        helperRuns();
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse("set up: nothing to say under the switch",
                BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy_setup)));
        tapEasy(c.get());
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        View dr = d.getWindow().getDecorView();
        assertTrue(BrowserTestKit.showsText(dr, app.getString(R.string.pr_easy_title)));
        assertTrue(BrowserTestKit.showsText(dr, app.getString(R.string.pr_easy_body)));
        assertEquals("nothing set before the answer", List.of(), ops.calls);
        target(dr, app.getString(R.string.pr_easy_yes)).performClick();
        changed();
        assertEquals(List.of(app.getPackageName() + " on"), ops.calls);
        assertTrue(PhoneRemote.unattended(app));
        assertTrue("the switch shows Android's word", easyOn(c.get()));
        assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy_turning_on)));

        // Off: no question.
        tapEasy(c.get());
        Dialog after = ShadowDialog.getLatestDialog();
        assertTrue("no new question", after == d || !after.isShowing());
        changed();
        assertEquals(List.of(app.getPackageName() + " on", app.getPackageName() + " off"), ops.calls);
        assertFalse(PhoneRemote.unattended(app));
        assertFalse(easyOn(c.get()));
        c.pause().stop().destroy();
    }

    /** Not now: nothing asked of the helper, nothing changed. */
    @Test
    public void notNow_changesNothing() throws Exception {
        paired();
        helperRuns();
        ActivityController<LinuxSettingsActivity> c = page();
        tapEasy(c.get());
        Dialog d = ShadowDialog.getLatestDialog();
        target(d.getWindow().getDecorView(), app.getString(R.string.pr_disclose_no)).performClick();
        BrowserTestKit.settle(1200);
        assertFalse(d.isShowing());
        assertTrue(PhoneEasyAccess.working().isEmpty());
        assertEquals(List.of(), ops.calls);
        assertFalse(easyOn(c.get()));
        c.pause().stop().destroy();
    }

    /** Android refuses: the switch stays as Android has it, and the card says why in Android's words. */
    @Test
    public void aRefusalIsSaid_inAndroidsWords() throws Exception {
        paired();
        helperRuns();
        ops.refuse = "Error: something Android said";
        ActivityController<LinuxSettingsActivity> c = page();
        tapEasy(c.get());
        target(ShadowDialog.getLatestDialog().getWindow().getDecorView(), app.getString(R.string.pr_easy_yes)).performClick();
        changed();
        assertEquals(1, ops.calls.size());
        assertFalse(easyOn(c.get()));
        String said = app.getString(R.string.pr_set_easy_failed, "Error: something Android said");
        assertTrue(BrowserTestKit.showsText(root(c.get()), said));
        c.pause().stop().destroy();
        // Settings opened again later: an earlier page's "did not change" is not said again.
        c = page();
        assertFalse(BrowserTestKit.showsText(root(c.get()), said));
        c.pause().stop().destroy();
    }

    /**
     * Set up, but the helper cannot start (Developer options went off): the
     * card says the helper did not start, and Screen off's line says why.
     */
    @Test
    public void noHelper_isSaid_andScreenOffsLineSaysWhy() throws Exception {
        paired();
        developer(false);
        noHelper();
        ActivityController<LinuxSettingsActivity> c = page();
        tapEasy(c.get());
        target(ShadowDialog.getLatestDialog().getWindow().getDecorView(), app.getString(R.string.pr_easy_yes)).performClick();
        changed();
        assertEquals(PhoneEasyAccess.NO_HELPER, PhoneEasyAccess.failed(app));
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_easy_nohelper)));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.ph_set_devoff)));
        assertFalse(easyOn(c.get()));
        c.pause().stop().destroy();
    }

    /**
     * Remote desktop off, Easy access still given (Android keeps it): the
     * switch stays on the card, so Easy access can be taken away without
     * turning Remote desktop on first, which would also start a From
     * anywhere link. Once off, the switch goes with it.
     */
    @Test
    public void remoteDesktopOff_easyAccessStaysToBeTurnedOff() throws Exception {
        paired();
        helperRuns();
        PhoneRemote.setEnabled(app, false);
        unattended(true);
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse("Remote desktop is off", checked(target(root(c.get()), app.getString(R.string.pr_set_title))));
        assertTrue("Easy access shows, on", easyOn(c.get()));
        tapEasy(c.get());
        changed();
        assertEquals(List.of(app.getPackageName() + " off"), ops.calls);
        assertFalse(PhoneRemote.unattended(app));
        assertFalse("gone once off", BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy)));
        assertFalse("Remote desktop stays off", PhoneRemote.isEnabled(app));
        c.pause().stop().destroy();
    }

    /** Both off: no Easy access on the card, as before. */
    @Test
    public void bothOff_noEasyAccessSwitch() throws Exception {
        PhoneRemote.setEnabled(app, false);
        ActivityController<LinuxSettingsActivity> c = page();
        assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy)));
        c.pause().stop().destroy();
    }

    /**
     * The helper answers after the app stopped waiting: the card says the
     * helper did not answer in time (not that the helper did not start), and
     * once Android says what was asked after all, the switch shows it and the
     * line goes.
     */
    @Test
    public void noAnswerInTime_isSaid_andGoesWhenAndroidAgrees() throws Exception {
        paired();
        helperRuns();
        ops.delayMs = 2500;
        PhoneEasyAccess.answerMs = 600;
        ActivityController<LinuxSettingsActivity> c = page();
        tapEasy(c.get());
        target(ShadowDialog.getLatestDialog().getWindow().getDecorView(), app.getString(R.string.pr_easy_yes)).performClick();
        changed();
        assertEquals(PhoneEasyAccess.NO_ANSWER, PhoneEasyAccess.failed(app));
        View r = root(c.get());
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.pr_set_easy_noanswer)));
        assertFalse("not 'did not start'", BrowserTestKit.showsText(r, app.getString(R.string.pr_set_easy_nohelper)));
        assertFalse(easyOn(c.get()));
        // The helper's change lands after all.
        assertTrue(until(() -> PhoneRemote.unattended(app), 10_000));
        BrowserTestKit.settle(1200);
        assertTrue("the switch shows Android's word", easyOn(c.get()));
        assertFalse("and the line went", BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy_noanswer)));
        c.pause().stop().destroy();
    }

    /** One change at a time: a second tap while one is under way changes nothing more. */
    @Test
    public void oneChangeAtATime() throws Exception {
        paired();
        helperRuns();
        unattended(true);
        ActivityController<LinuxSettingsActivity> c = page();
        // Off asks nothing: two taps in a row, before the helper's answer.
        target(root(c.get()), app.getString(R.string.pr_set_easy)).performClick();
        target(root(c.get()), app.getString(R.string.pr_set_easy)).performClick();
        changed();
        assertEquals(List.of(app.getPackageName() + " off"), ops.calls);
        assertFalse(easyOn(c.get()));
        c.pause().stop().destroy();
    }

    /** Android 10: no Wireless debugging, so the card says a computer with adb starts the helper. */
    @Test
    public void android10_saysAComputerStartsTheHelper() throws Exception {
        int was = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 29);
            ActivityController<LinuxSettingsActivity> c = page();
            assertTrue(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy_pc)));
            assertFalse(BrowserTestKit.showsText(root(c.get()), app.getString(R.string.pr_set_easy_setup)));
            c.pause().stop().destroy();
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", was);
        }
    }

    /** The words before Android's question are this app's, in each language the app has. */
    @Test
    public void theQuestionIsInEveryLanguage() {
        for (String lang : new String[]{"ar", "de", "es", "fr", "gu", "hi", "pt", "zh"}) {
            android.content.res.Configuration conf = new android.content.res.Configuration(app.getResources().getConfiguration());
            conf.setLocale(java.util.Locale.forLanguageTag(lang));
            Context l = app.createConfigurationContext(conf);
            assertFalse(lang, app.getString(R.string.pr_easy_body).equals(l.getString(R.string.pr_easy_body)));
            assertFalse(lang, app.getString(R.string.pr_set_easy).equals(l.getString(R.string.pr_set_easy)));
            assertTrue(lang + ": the setup says Easy access needs it",
                    l.getString(R.string.ph_setup_body).contains(l.getString(R.string.pr_set_easy)));
        }
    }
}
