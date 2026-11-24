package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
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
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * "Open in a browser" on LinuxOnDroid's Settings page: the switch, what it
 * starts and stops, the two steps it shows (the address, the code), how it
 * follows the server's state line, copying the address, signing every browser
 * out; and pictures in five languages and three widths (saved when asked for:
 * {@code ./gradlew :linuxapp:testDebugUnitTest -PbrowserShots=<folder>}).
 *
 * <p>Off a phone: the page and the service run under Robolectric, the server's
 * state is the file it writes, and the network is {@link BrowserAddressShadow}.
 * The server and the stream themselves are test/linux-browser-test.mjs's.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {BrowserDisplayShadow.class, BrowserGpuShadow.class, BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class BrowserSettingsTest {

    private Context app;
    private File dir;

    @Before
    public void phone() throws Exception {
        BrowserTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        BrowserGpuShadow.available = true;
        BrowserAddressShadow.wifi("192.168.2.25");
        dir = new File(app.getFilesDir(), "browser");
        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) f.delete();
        }
        app.getSharedPreferences("linux-boot", Context.MODE_PRIVATE).edit().clear().commit();
        Settings.Global.putString(app.getContentResolver(), ChildProcessLimit.FLAG, "false");
        ShadowToast.reset();
        BrowserTestKit.drainServices();
    }

    private ActivityController<LinuxSettingsActivity> page() throws Exception {
        ActivityController<LinuxSettingsActivity> c = Robolectric.buildActivity(LinuxSettingsActivity.class).setup();
        BrowserTestKit.settle(400);
        return c;
    }

    private static View root(Activity a) {
        return a.getWindow().getDecorView();
    }

    /** The row whose title is {@code title}: its nearest clickable ancestor. */
    private static View row(View root, String title) {
        for (View v : BrowserTestKit.all(root)) {
            if (v instanceof TextView && v.isShown() && title.contentEquals(((TextView) v).getText())) {
                if (v.hasOnClickListeners()) return v;
                for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
                    View pv = (View) p;
                    if (pv.hasOnClickListeners() || !pv.isEnabled()) return pv;
                }
            }
        }
        throw new AssertionError("no row " + title);
    }

    private static boolean checked(View row) {
        for (View v : BrowserTestKit.all(row)) if (v instanceof Switch) return ((Switch) v).isChecked();
        throw new AssertionError("no switch in the row");
    }

    /**
     * Now, by the clock the page reads. Robolectric answers
     * System.currentTimeMillis() from its own paused clock in the app's
     * classes and from the real one in this test class: the page wrote
     * pairing-until 630503 while this class read 1795116331826. On a phone
     * both are the same clock.
     */
    private static long pageNow() {
        return org.robolectric.shadows.ShadowSystem.currentTimeMillis();
    }

    private String title() {
        return app.getString(R.string.bw_set_title);
    }

    private void state(String line) throws Exception {
        dir.mkdirs();
        Files.write(new File(dir, "state").toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String joined(View root) {
        return String.join(" | ", BrowserTestKit.texts(root)).replace("\n", " / ");
    }

    @Test
    public void off_byDefault_andSaysWhatItIs() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        System.out.println("off: " + joined(r));
        List<String> texts = BrowserTestKit.texts(r);
        int mine = texts.indexOf(app.getString(R.string.bw_set_header));
        int startup = texts.indexOf(app.getString(R.string.hm_set_startup));
        assertTrue("its own section, the first on the page", mine >= 0 && mine < startup);
        assertFalse("off until switched on", checked(row(r, title())));
        assertTrue("says what it is for", BrowserTestKit.showsText(r, app.getString(R.string.bw_set_sub)));
        assertFalse("no address while off", joined(r).contains("192.168"));
        AccessibilityNodeInfo info = row(r, title()).createAccessibilityNodeInfo();
        assertEquals("a switch to accessibility", Switch.class.getName(), String.valueOf(info.getClassName()));
        assertFalse(info.isChecked());
        c.pause().stop().destroy();
    }

    @Test
    public void on_startsTheServer_andShowsTheTwoSteps() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        row(root(c.get()), title()).performClick();
        BrowserTestKit.settle(300);
        View r = root(c.get());
        assertTrue("the switch is recorded", new File(dir, "on").exists());
        List<String> started = BrowserTestKit.actions(BrowserTestKit.drainServices());
        System.out.println("started: " + started);
        assertTrue("the service is told", started.contains("BROWSER_ON"));
        assertTrue("checked", checked(row(r, title())));
        String all = joined(r);
        System.out.println("on, nothing from the server yet: " + all);
        assertTrue("step 1: the address as it is typed, the phone's own, the port", BrowserTestKit.texts(r).contains("192.168.2.25:7443"));
        // "482913 <made at> <wrong tries>"
        String code = new String(Files.readAllBytes(new File(dir, "code").toPath()), StandardCharsets.UTF_8).trim().split("\\s+")[0];
        assertTrue("a code was made", code.matches("\\d{6}"));
        assertTrue("step 2: the code, in two threes", all.contains(code.substring(0, 3) + " " + code.substring(3)));
        assertTrue("…and how long it works, for whom", all.contains(app.getString(R.string.bw_set_code_hint)));
        long until = Long.parseLong(new String(Files.readAllBytes(new File(dir, "pairing-until").toPath()), StandardCharsets.UTF_8).trim());
        long left = until - pageNow();
        assertTrue("the code works while it is shown, and ten minutes after: " + left, left > 9 * 60_000 && left <= 10 * 60_000);
        assertTrue("getting ready, until the server says", all.contains(app.getString(R.string.bw_set_starting)));
        assertTrue("the warning a browser will give, said first", all.contains(app.getString(R.string.bw_set_warning)));
        assertTrue("what Low, Medium and High are", all.contains(app.getString(R.string.bw_set_quality)));
        for (View v : BrowserTestKit.all(r)) {
            if (v instanceof TextView && "192.168.2.25:7443".contentEquals(((TextView) v).getText())) {
                assertEquals("the address on one line", 1, ((TextView) v).getLineCount());
            }
        }

        state("listening 7443");
        BrowserTestKit.settle(1200);
        all = joined(root(c.get()));
        assertTrue("waiting, once listening", all.contains(app.getString(R.string.bw_set_waiting)));

        state("watching 7443 3 192.168.2.34 Edge on Windows");
        BrowserTestKit.settle(1200);
        all = joined(root(c.get()));
        System.out.println("watching: " + all);
        assertTrue("who is watching, and from where", all.contains(
                app.getString(R.string.bw_set_watching, "Edge on Windows · 192.168.2.34")));

        state("failed port 7443");
        BrowserTestKit.settle(1200);
        all = joined(root(c.get()));
        System.out.println("port taken: " + all);
        assertTrue("7443 taken by another app: said, and what to do", all.contains(app.getString(R.string.bw_set_port, 7443)));
        assertFalse("…and no address, which would lead a person to that app", all.contains("192.168.2.25:7443"));
        assertFalse("…nor the code, to be typed into it", all.contains(code.substring(0, 3) + " " + code.substring(3)));

        state("failed Address already in use");
        BrowserTestKit.settle(1200);
        all = joined(root(c.get()));
        assertTrue("a failure, said", all.contains(app.getString(R.string.bw_set_failed, "Address already in use")));
        assertFalse("…without the steps", all.contains("192.168.2.25:7443"));

        state("listening 7443");
        BrowserTestKit.settle(1200);
        all = joined(root(c.get()));
        assertTrue("listening again: the steps are back", all.contains("192.168.2.25:7443")
                && all.contains(code.substring(0, 3) + " " + code.substring(3)));
        c.pause().stop().destroy();
    }

    @Test
    public void aFailedStart_isForgotten_whenSwitchedOffAndOn() throws Exception {
        // The service, switched off with no server to stop, clears the failure.
        new LinuxBrowser(dir).setOn(true);
        state("failed port 7443");
        android.app.Application a = RuntimeEnvironment.getApplication();
        LinuxService.browserOff(a);
        org.robolectric.android.controller.ServiceController<LinuxService> svc =
                Robolectric.buildService(LinuxService.class, new Intent(a, LinuxService.class)
                        .setAction(LinuxService.ACTION_BROWSER_OFF)).create().startCommand(0, 1);
        BrowserTestKit.settle(300);
        assertEquals("off, not failed", "off", new LinuxBrowser(dir).state());
        svc.destroy();
    }

    @Test
    public void off_again_stopsTheServer_andHidesTheSteps() throws Exception {
        ActivityController<LinuxSettingsActivity> c = page();
        row(root(c.get()), title()).performClick();
        BrowserTestKit.settle(300);
        BrowserTestKit.drainServices();
        row(root(c.get()), title()).performClick();
        BrowserTestKit.settle(300);
        View r = root(c.get());
        assertFalse("the switch is gone", new File(dir, "on").exists());
        assertTrue("the service is told", BrowserTestKit.actions(BrowserTestKit.drainServices()).contains("BROWSER_OFF"));
        assertFalse("codes stop working with it", new LinuxBrowser(dir).pairingOpen(pageNow()));
        assertFalse("unchecked", checked(row(r, title())));
        assertFalse("no address", joined(r).contains("192.168"));
        c.pause().stop().destroy();
    }

    @Test
    public void aUsedUpCode_isNotShown_andTheOwnerMakesANewOne() throws Exception {
        LinuxBrowser b = new LinuxBrowser(dir);
        b.setOn(true);
        state("listening 7443");
        // Ten wrong codes tried against 482913.
        dir.mkdirs();
        Files.write(new File(dir, "code").toPath(), ("482913 " + System.currentTimeMillis() + " 10\n").getBytes(StandardCharsets.UTF_8));
        ActivityController<LinuxSettingsActivity> c = page();
        String all = joined(root(c.get()));
        System.out.println("used up: " + all);
        assertTrue("said why it stopped", all.contains(app.getString(R.string.bw_set_used_up)));
        assertFalse("the dead code is not shown", all.contains("482 913"));
        assertFalse("nor does the page open pairing for it", b.pairingOpen(pageNow()));
        row(root(c.get()), app.getString(R.string.bw_set_new_code)).performClick();
        BrowserTestKit.settle(300);
        all = joined(root(c.get()));
        String fresh = b.code();
        assertNotEquals("a new code", "482913", fresh);
        assertFalse("not used up", b.codeUsedUp());
        assertTrue("shown, in two threes", all.contains(fresh.substring(0, 3) + " " + fresh.substring(3)));
        assertFalse("the note has gone", all.contains(app.getString(R.string.bw_set_used_up)));
        assertTrue("and it works now", b.pairingOpen(pageNow()));
        LinuxBrowser.Tried tried = b.tryCode(fresh, pageNow(), "Edge on Windows · 192.168.2.34");
        assertEquals("a right try of the new code lets a browser in", LinuxBrowser.Pair.OK, tried.pair);
        assertTrue("…with a token that works", b.tokenValid(tried.token));
        assertEquals("…remembered as what it said it was", 1, b.tokenCount());
        c.pause().stop().destroy();
    }

    @Test
    public void theAddress_copies() throws Exception {
        new LinuxBrowser(dir).setOn(true);
        state("listening 7443");
        ActivityController<LinuxSettingsActivity> c = page();
        View box = BrowserTestKit.byDescription(root(c.get()),
                app.getString(R.string.bw_set_copy) + " https://192.168.2.25:7443");
        assertNotNull("the address is a button that says what it does", box);
        box.performClick();
        ClipboardManager cm = app.getSystemService(ClipboardManager.class);
        assertEquals("https://192.168.2.25:7443", String.valueOf(cm.getPrimaryClip().getItemAt(0).getText()));
        c.pause().stop().destroy();
    }

    @Test
    public void noNetwork_saysHowToGetOne() throws Exception {
        BrowserAddressShadow.list.clear();
        new LinuxBrowser(dir).setOn(true);
        ActivityController<LinuxSettingsActivity> c = page();
        String all = joined(root(c.get()));
        System.out.println("no network: " + all);
        assertTrue(all.contains(app.getString(R.string.bw_set_no_network)));
        assertFalse("no address to give", all.contains("192.168"));
        assertTrue("away from Wi-Fi: Tailscale", all.contains(app.getString(R.string.bw_set_away)));
        c.pause().stop().destroy();
    }

    @Test
    public void twoAddresses_bothOffered() throws Exception {
        BrowserAddressShadow.list.clear();
        BrowserAddressShadow.list.add(new LinuxBrowser.Address("192.168.2.25", "wifi"));
        BrowserAddressShadow.list.add(new LinuxBrowser.Address("192.168.43.1", "hotspot"));
        new LinuxBrowser(dir).setOn(true);
        state("listening 7443");
        ActivityController<LinuxSettingsActivity> c = page();
        String all = joined(root(c.get()));
        assertTrue("Wi-Fi first", all.contains("192.168.2.25:7443"));
        assertTrue("the hotspot's too, named", all.contains(app.getString(R.string.wb_if_hotspot) + "  192.168.43.1:7443"));
        assertTrue("under the addresses, the way from away: Tailscale", all.contains(app.getString(R.string.bw_set_away)));
        c.pause().stop().destroy();
    }

    @Test
    public void signOut_asksFirst_thenEveryBrowserOut_andANewCode() throws Exception {
        LinuxBrowser b = new LinuxBrowser(dir);
        b.setOn(true);
        state("listening 7443");
        String before = b.code();
        b.addToken("Chrome on Windows · 192.168.2.34");
        b.addToken("Safari on macOS · 192.168.2.40");
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue(joined(r).contains(app.getString(R.string.bw_set_browsers, 2)));
        row(r, app.getString(R.string.bw_set_sign_out)).performClick();
        BrowserTestKit.settle(300);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("asked first", d);
        assertTrue(BrowserTestKit.showsText(d.getWindow().getDecorView(), app.getString(R.string.bw_set_sign_out_title)));
        BrowserTestKit.save(BrowserTestKit.overlay(BrowserTestKit.window(c.get()), BrowserTestKit.window(d.getWindow())), "browser-signout-en");
        View yes = null;
        for (View v : BrowserTestKit.all(d.getWindow().getDecorView())) {
            if (v instanceof TextView && app.getString(R.string.bw_set_sign_out_ok).contentEquals(((TextView) v).getText())) yes = v;
        }
        assertNotNull(yes);
        BrowserTestKit.drainServices();
        yes.performClick();
        BrowserTestKit.settle(300);
        assertTrue("the server is told, to end what the tokens let in",
                BrowserTestKit.actions(BrowserTestKit.drainServices()).contains("BROWSER_SIGNOUT"));
        assertEquals("every browser out", 0, b.tokenCount());
        assertNotEquals("a new code", before, b.code());
        assertEquals(app.getString(R.string.bw_set_signed_out), ShadowToast.getTextOfLatestToast());
        assertFalse("the count is gone with them", joined(root(c.get())).contains(app.getString(R.string.bw_set_browsers, 2)));
        c.pause().stop().destroy();
    }

    @Test
    public void onMobileDataWithAVpn_theVpnIsTheWayIn() throws Exception {
        // No Wi-Fi, mobile data, and Tailscale: its address is the only one offered.
        BrowserAddressShadow.list.clear();
        BrowserAddressShadow.list.add(new LinuxBrowser.Address("100.101.102.103", "vpn"));
        new LinuxBrowser(dir).setOn(true);
        state("listening 7443");
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        String all = joined(r);
        System.out.println("a VPN only: " + all);
        assertTrue("the VPN's address, as it is typed", BrowserTestKit.texts(r).contains("100.101.102.103:7443"));
        assertTrue("…named as a VPN", all.contains(app.getString(R.string.bw_if_vpn)));
        assertFalse("not the no-network note", all.contains(app.getString(R.string.bw_set_no_network)));
        assertTrue("the note that names the VPN address", all.contains(app.getString(R.string.bw_set_away)));
        c.pause().stop().destroy();
    }

    @Test
    public void whichNetworks_areOffered() {
        // Names the phones in hand use: the S25's Wi-Fi and its mobile data
        // (rmnet_data0, 192.0.0.2 behind the carrier's 464XLAT), Samsung's and
        // Qualcomm's hotspots, a cable, VPNs.
        assertEquals("wifi", LinuxBrowser.kindOf("wlan0"));
        for (String hotspot : new String[]{"swlan0", "ap0", "ap_br_wlan2", "softap0", "wlan1"}) {
            assertEquals(hotspot, "hotspot", LinuxBrowser.kindOf(hotspot));
        }
        for (String wired : new String[]{"rndis0", "ncm0", "usb0", "eth0"}) {
            assertEquals(wired, "wired", LinuxBrowser.kindOf(wired));
        }
        for (String vpn : new String[]{"tun0", "wg0", "tailscale0", "ppp0"}) {
            assertEquals(vpn, "vpn", LinuxBrowser.kindOf(vpn));
        }
        for (String mobile : new String[]{"rmnet_data0", "rmnet_ipa0", "ccmni0", "clat4", "v4-rmnet_data0", "seth_lte0", "wwan0", "p2p0", "dummy0"}) {
            assertEquals("never offered: " + mobile, null, LinuxBrowser.kindOf(mobile));
        }
    }

    @Test
    public void whoMayConnect() throws Exception {
        java.util.function.Function<String, Boolean> local = ip -> {
            try {
                return LinuxBrowser.isLocalPeer(java.net.InetAddress.getByName(ip));
            } catch (java.net.UnknownHostException e) {
                throw new AssertionError(e);
            }
        };
        for (String ok : new String[]{"192.168.2.34", "10.1.2.3", "172.20.0.5", "169.254.9.9", "100.66.225.73", "fd7a:115c:a1e0::1", "fe80::1", "::ffff:192.168.2.34"}) {
            assertTrue("let in: " + ok, local.apply(ok));
        }
        for (String no : new String[]{"127.0.0.1", "::1", "8.8.8.8", "192.0.0.2", "100.128.0.1", "2001:db8::1", "::ffff:8.8.8.8"}) {
            assertFalse("never: " + no, local.apply(no));
        }
    }

    // ── pictures ──

    private void shot(String qualifiers, String name, String stateLine, int tokens) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        LinuxBrowser b = new LinuxBrowser(dir);
        b.setOn(true);
        Files.write(new File(dir, "code").toPath(), "482913\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "tokens").toPath(), new byte[0]);
        for (int i = 0; i < tokens; i++) b.addToken("browser " + i);
        state(stateLine);
        ActivityController<LinuxSettingsActivity> c = page();
        BrowserTestKit.save(BrowserTestKit.window(c.get()), name);
        c.pause().stop().destroy();
    }

    @Test
    public void pictures() throws Exception {
        shot("en-w412dp-h892dp-xxhdpi", "browser-en-waiting", "listening 7443", 0);
        shot("en-w412dp-h892dp-xxhdpi", "browser-en-watching", "watching 7443 3 192.168.2.34 Edge on Windows", 2);
        shot("de-w360dp-h780dp-xxhdpi", "browser-de-360", "listening 7443", 1);
        shot("ar-w412dp-h892dp-xxhdpi", "browser-ar", "watching 7443 1 192.168.2.34 Chrome on Windows", 1);
        shot("hi-w360dp-h780dp-xxhdpi", "browser-hi-360", "listening 7443", 0);
        shot("zh-w412dp-h892dp-xxhdpi", "browser-zh", "listening 7443", 0);
        shot("en-w900dp-h560dp-xhdpi", "browser-wide", "listening 7443", 1);
        shot("en-w412dp-h892dp-xxhdpi", "browser-en-port", "failed port 7443", 0);
        RuntimeEnvironment.setQualifiers("en-w412dp-h892dp-xxhdpi");
        state("listening 7443");
        Files.write(new File(dir, "code").toPath(), ("482913 " + System.currentTimeMillis() + " 10\n").getBytes(StandardCharsets.UTF_8));
        ActivityController<LinuxSettingsActivity> used = page();
        BrowserTestKit.save(BrowserTestKit.window(used.get()), "browser-en-used-up");
        used.pause().stop().destroy();
        BrowserAddressShadow.list.clear();
        shot("en-w412dp-h892dp-xxhdpi", "browser-en-no-network", "listening 7443", 0);
        new LinuxBrowser(dir).setOn(false);
        shot2Off();
    }

    private void shot2Off() throws Exception {
        RuntimeEnvironment.setQualifiers("en-w412dp-h892dp-xxhdpi");
        new LinuxBrowser(dir).setOn(false);
        ActivityController<LinuxSettingsActivity> c = page();
        BrowserTestKit.save(BrowserTestKit.window(c.get()), "browser-en-off");
        c.pause().stop().destroy();
    }
}
