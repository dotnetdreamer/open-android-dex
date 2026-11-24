package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * The Settings page's card for "Email me when an address changes": its own
 * section, off until switched on, the email services and their notes, what
 * is typed kept when the page goes (the password sealed, and never shown
 * again), the test email against a stand-in server on this computer, its
 * failure in plain words, the links' switch, and Forget. Pictures in three
 * languages when asked for ({@code -PbrowserShots=<folder>}).
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {BrowserDisplayShadow.class, BrowserGpuShadow.class, BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class EmailNoticeSettingsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context app;
    private FakeSmtp server;
    private static final String PASSWORD = "abcd efgh ijkl mnop";

    @Before
    public void phone() throws Exception {
        BrowserTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        for (File d : new File[]{EmailNotice.dir(app), LinuxService.browser(app).dir(), PhoneRemote.dir(app)}) {
            File[] files = d.listFiles();
            if (files != null) for (File f : files) f.delete();
        }
        BrowserGpuShadow.available = true;
        BrowserAddressShadow.wifi("192.168.2.25");
        EmailSecret.sealer = new EmailNoticeTest.TestSealer();
        EmailNotice.inline = true;
        EmailNotice.timing = new EmailSmtp.Timing(3000, 3000, 15000);
        server = new FakeSmtp(FakeSmtp.Mode.STARTTLS, tmp.newFolder(), Collections.<String>emptyList());
        EmailNotice.tls = server.trusting();
        Settings.Global.putString(app.getContentResolver(), Settings.Global.DEVICE_NAME, "Galaxy S25 Ultra");
        BrowserTestKit.drainServices();
    }

    @After
    public void done() {
        server.close();
        EmailNotice.inline = false;
        EmailNotice.tls = null;
        EmailNotice.timing = EmailSmtp.Timing.PHONE;
        EmailSecret.sealer = new EmailSecret.Keystore();
    }

    private ActivityController<LinuxSettingsActivity> page() throws Exception {
        ActivityController<LinuxSettingsActivity> c = Robolectric.buildActivity(LinuxSettingsActivity.class).setup();
        BrowserTestKit.settle(400);
        return c;
    }

    private static View root(Activity a) {
        return a.getWindow().getDecorView();
    }

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

    private void tap(View root, String words) throws Exception {
        target(root, words).performClick();
        BrowserTestKit.settle(200);
    }

    private static boolean checked(View row) {
        for (View v : BrowserTestKit.all(row)) if (v instanceof Switch) return ((Switch) v).isChecked();
        throw new AssertionError("no switch in the row");
    }

    private EditText field(View root, int label) {
        View v = BrowserTestKit.byDescription(root, app.getString(label));
        assertTrue("a field for " + app.getString(label), v instanceof EditText);
        return (EditText) v;
    }

    private boolean shows(View root, int words) {
        return BrowserTestKit.showsText(root, app.getString(words));
    }

    /** Waits for the test email's answer, as a person watching the card does. */
    private void waitFor(ActivityController<LinuxSettingsActivity> c, String words) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (BrowserTestKit.showsText(root(c.get()), words)) return;
            BrowserTestKit.settle(100);
        }
        throw new AssertionError("never shown: " + words + " in " + BrowserTestKit.texts(root(c.get())));
    }

    /** On, Other, the stand-in server, the addresses and the password typed. */
    private void fillIn(ActivityController<LinuxSettingsActivity> c) throws Exception {
        View r = root(c.get());
        if (!EmailNotice.Prefs.load(app).on) {
            tap(r, app.getString(R.string.em_set_title));
            r = root(c.get());
        }
        tap(r, app.getString(R.string.em_prov_other));
        r = root(c.get());
        field(r, R.string.em_set_to).setText("reader@example.com");
        field(r, R.string.em_set_user).setText("owner@example.com");
        field(r, R.string.em_set_password).setText(PASSWORD);
        field(r, R.string.em_set_server).setText("localhost");
        field(r, R.string.em_set_port).setText(String.valueOf(server.port()));
        tap(r, app.getString(R.string.em_sec_starttls));
    }

    /**
     * Android 15 and later hide a whole window from screen sharing while a
     * password field is on it: seen through Remote desktop, in the person's
     * own browser, the whole Settings page was black. The password field says
     * it is not that kind of secret; its dots stay.
     */
    @Test
    @Config(sdk = 35)
    public void thePasswordField_leavesThePageVisibleInScreenSharing() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), app.getString(R.string.em_set_title));
        EditText pw = field(root(c.get()), R.string.em_set_password);
        assertEquals(View.CONTENT_SENSITIVITY_NOT_SENSITIVE, pw.getContentSensitivity());
        assertFalse(pw.isContentSensitive());
        assertTrue("still dots",
                (pw.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.pause().stop().destroy();
    }

    @Test
    public void offByDefault_inASectionOfItsOwn() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(shows(r, R.string.em_set_header));
        assertTrue(shows(r, R.string.em_set_sub));
        assertFalse(checked(target(r, app.getString(R.string.em_set_title))));
        assertFalse("nothing under the switch while off", shows(r, R.string.em_set_to));
        assertFalse(shows(r, R.string.em_set_test));
        c.pause().stop().destroy();
    }

    @Test
    public void switchedOn_theFormSaysWhatIsMissing_oneThingAtATime() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), app.getString(R.string.em_set_title));
        View r = root(c.get());
        assertTrue(EmailNotice.Prefs.load(app).on);
        assertTrue(checked(target(r, app.getString(R.string.em_set_title))));
        for (int w : new int[]{R.string.em_set_service, R.string.em_set_to, R.string.em_set_user, R.string.em_set_password,
                R.string.em_set_links, R.string.em_set_test, R.string.em_set_forget}) {
            assertTrue(app.getString(w), shows(r, w));
        }
        assertTrue("the first thing missing", shows(r, R.string.em_need_to));
        assertTrue("the links are on unless switched off", checked(target(r, app.getString(R.string.em_set_links))));
        // Gmail by default: its server filled in, and its note on app passwords.
        assertTrue(shows(r, R.string.em_note_gmail));
        assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.em_set_server_line, "smtp.gmail.com", 465,
                app.getString(R.string.em_sec_ssl))));
        c.pause().stop().destroy();
    }

    @Test
    public void eachService_fillsInItsServer_andSaysHowToGetAnAppPassword() throws Exception {
        EmailNotice.setOn(app, true);
        ActivityController<LinuxSettingsActivity> c = page();
        for (EmailNotice.Provider p : EmailNotice.PROVIDERS) {
            tap(root(c.get()), app.getString(p.name));
            View r = root(c.get());
            assertTrue(p.id, shows(r, p.note));
            EmailNotice.Prefs s = EmailNotice.Prefs.load(app);
            assertEquals(p.id, s.provider);
            if ("other".equals(p.id)) {
                assertTrue("another service: its server typed in", shows(r, R.string.em_set_server));
                assertTrue(shows(r, R.string.em_set_port));
                assertTrue(shows(r, R.string.em_set_security));
            } else {
                assertEquals(p.host, s.host);
                assertEquals(p.port, s.port);
                assertEquals(p.security, s.security);
                assertFalse(shows(r, R.string.em_set_server));
                assertTrue(BrowserTestKit.showsText(r, app.getString(R.string.em_set_server_line, p.host, p.port,
                        app.getString(p.security == EmailSmtp.Security.SSL ? R.string.em_sec_ssl : R.string.em_sec_starttls))));
            }
        }
        c.pause().stop().destroy();
    }

    @Test
    public void whatIsTyped_isKeptWhenThePageGoes_thePasswordSealedAndNeverShown() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        fillIn(c);
        c.pause().stop().destroy();
        EmailNotice.Prefs s = EmailNotice.Prefs.load(app);
        assertEquals("reader@example.com", s.to);
        assertEquals("owner@example.com", s.user);
        assertEquals("localhost", s.host);
        assertEquals(server.port(), s.port);
        assertEquals(EmailSmtp.Security.STARTTLS, s.security);
        assertEquals(PASSWORD, EmailNotice.password(app));
        for (File f : EmailNotice.dir(app).listFiles()) {
            assertFalse(f.getName() + " holds no password",
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains(PASSWORD));
        }
        c = page();
        View r = root(c.get());
        EditText pw = field(r, R.string.em_set_password);
        assertEquals("never shown again", "", pw.getText().toString());
        assertTrue("dots, which are not the password", pw.getHint().toString().matches("•+"));
        assertTrue("and what they mean, under the field", shows(r, R.string.em_set_password_saved));
        assertFalse("not in the page's saved state", pw.isSaveEnabled());
        assertTrue("ready, but no email goes until a test goes through", shows(r, R.string.em_state_need_test));
        c.pause().stop().destroy();
    }

    @Test
    public void theTestEmail_reachesTheServer_andTheCardSaysSo() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        fillIn(c);
        tap(root(c.get()), app.getString(R.string.em_set_test));
        waitFor(c, app.getString(R.string.em_set_test_ok, "reader@example.com"));
        assertEquals(1, server.mails.size());
        assertTrue(server.loginOverTls);
        MailParse m = MailParse.of(server.mails.get(0).data);
        assertEquals("Galaxy S25 Ultra: a test email from LinuxOnDroid", m.header("subject"));
        BrowserTestKit.settle(1200);
        View r = root(c.get());
        assertTrue("on, and saying where the emails go",
                BrowserTestKit.showsText(r, app.getString(R.string.em_state_on, "reader@example.com")));
        assertTrue(EmailNotice.Prefs.load(app).active(app));
        c.pause().stop().destroy();
    }

    @Test
    public void aRefusedTest_saysWhyInPlainWords_andTheServersOwn() throws Exception {
        server.authReply = "535 5.7.8 Username and Password not accepted";
        ActivityController<LinuxSettingsActivity> c = page();
        fillIn(c);
        tap(root(c.get()), app.getString(R.string.em_set_test));
        String why = app.getString(R.string.em_set_not_sent, app.getString(R.string.em_fault_login));
        waitFor(c, why);
        assertTrue(BrowserTestKit.showsText(root(c.get()),
                app.getString(R.string.em_set_said, "535 5.7.8 Username and Password not accepted")));
        assertFalse("not active: the test did not go through", EmailNotice.Prefs.load(app).active(app));
        assertTrue(server.mails.isEmpty());
        c.pause().stop().destroy();
    }

    @Test
    public void theLinksSwitch_turnsTheLinksOff_andEndsTheLastOnes() throws Exception {
        EmailNotice.setOn(app, true);
        LinuxBrowser door = LinuxService.browser(app);
        java.util.List<String> s = door.newLinks(Collections.singletonList("192.168.2.25"), System.currentTimeMillis());
        ActivityController<LinuxSettingsActivity> c = page();
        tap(root(c.get()), app.getString(R.string.em_set_links));
        assertFalse(EmailNotice.Prefs.load(app).links);
        assertFalse(checked(target(root(c.get()), app.getString(R.string.em_set_links))));
        assertEquals(LinuxBrowser.Link.OFF, door.tryLink(s.get(0), "192.168.2.25", System.currentTimeMillis(), "x").link);
        c.pause().stop().destroy();
    }

    @Test
    public void forget_takesEverythingAway() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        fillIn(c);
        tap(root(c.get()), app.getString(R.string.em_set_forget));
        BrowserTestKit.settle(300);
        assertFalse(EmailNotice.Prefs.load(app).on);
        assertFalse(EmailNotice.hasPassword(app));
        assertEquals("", EmailNotice.Prefs.load(app).to);
        assertFalse(checked(target(root(c.get()), app.getString(R.string.em_set_title))));
        c.pause().stop().destroy();
    }

    @Test
    public void pictures() throws Exception {
        shot("en-w412dp-h892dp-xxhdpi", "email-en");
        shot("de-w360dp-h780dp-xxhdpi", "email-de-360");
        shot("ar-w412dp-h892dp-xxhdpi", "email-ar");
    }

    private void shot(String qualifiers, String name) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ActivityController<LinuxSettingsActivity> c = page();
        fillIn(c);
        View card = target(root(c.get()), app.getString(R.string.em_set_title));
        scrollTo(card, 200);
        BrowserTestKit.save(BrowserTestKit.window(c.get()), name);
        assertNotNull(card);
        // The lower half, after a test email went: the answer, and what the emails do now.
        tap(root(c.get()), app.getString(R.string.em_set_test));
        waitFor(c, app.getString(R.string.em_set_test_ok, "reader@example.com"));
        BrowserTestKit.settle(1200);
        scrollTo(target(root(c.get()), app.getString(R.string.em_set_links)), 120);
        BrowserTestKit.save(BrowserTestKit.window(c.get()), name + "-tested");
        c.pause().stop().destroy();
        server.mails.clear();
        EmailNotice.forget(app);
    }

    private static void scrollTo(View v, int above) throws Exception {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p instanceof android.widget.ScrollView) {
                android.widget.ScrollView sv = (android.widget.ScrollView) p;
                int[] at = new int[2];
                v.getLocationInWindow(at);
                int[] top = new int[2];
                sv.getLocationInWindow(top);
                sv.scrollTo(0, Math.max(0, sv.getScrollY() + at[1] - top[1] - above));
                break;
            }
        }
        BrowserTestKit.settle(200);
    }
}
