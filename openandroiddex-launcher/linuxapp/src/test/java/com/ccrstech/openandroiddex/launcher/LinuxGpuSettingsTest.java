package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.widget.ScrollView;
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

/**
 * "Graphics for Linux apps" on LinuxOnDroid's Settings page, in each state the
 * phone can be in (LinuxGpu.Offer), and every one saying the switch is for
 * Linux apps: a GPU one of our drivers runs (Turnip, RADV), any other GPU
 * through Android's own driver (VirGL), a 64-bit phone where neither works,
 * and a 32-bit phone, where Linux itself cannot run. Pictures of each when
 * asked for ({@code ./gradlew :linuxapp:testDebugUnitTest -PbrowserShots=<folder>}).
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w412dp-h892dp-xxhdpi",
        shadows = {BrowserDisplayShadow.class, BrowserGpuShadow.class, BrowserAddressShadow.class},
        instrumentedPackages = {"com.ccrstech.openandroiddex.launcher"})
public class LinuxGpuSettingsTest {

    private Context app;

    @Before
    public void phone() throws Exception {
        BrowserTestKit.phone();
        app = RuntimeEnvironment.getApplication();
        BrowserGpuShadow.available = true;
        BrowserGpuShadow.offer = null;
        BrowserAddressShadow.wifi("192.168.2.25");
        LinuxGpu.setEnabled(app, true);
        Settings.Global.putString(app.getContentResolver(), ChildProcessLimit.FLAG, "false");
        BrowserTestKit.drainServices();
    }

    @After
    public void back() {
        BrowserGpuShadow.offer = null;
    }

    private ActivityController<LinuxSettingsActivity> page() throws Exception {
        ActivityController<LinuxSettingsActivity> c = Robolectric.buildActivity(LinuxSettingsActivity.class).setup();
        BrowserTestKit.settle(400);
        return c;
    }

    private static View root(Activity a) {
        return a.getWindow().getDecorView();
    }

    /** The text view showing exactly {@code text}. */
    private static TextView text(View root, String text) {
        for (View v : BrowserTestKit.all(root)) {
            if (v instanceof TextView && v.isShown() && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        }
        throw new AssertionError("nothing shows " + text);
    }

    /** The switch in the row whose title is the graphics switch's. */
    private Switch gpuSwitch(View root) {
        View t = text(root, app.getString(R.string.ln_gpu_menu));
        for (android.view.ViewParent p = t.getParent(); p instanceof View; p = p.getParent()) {
            for (View v : BrowserTestKit.all((View) p)) if (v instanceof Switch) return (Switch) v;
        }
        throw new AssertionError("no graphics switch");
    }

    /** The state's words under the switch, the header, the switch's state, and a picture of it. */
    private void state(LinuxGpu.Offer offer, int words, boolean usable, String shot, String qualifiers) throws Exception {
        if (qualifiers != null) RuntimeEnvironment.setQualifiers(qualifiers);
        BrowserGpuShadow.offer = offer;
        ActivityController<LinuxSettingsActivity> c = page();
        View r = root(c.get());
        assertTrue("the header says Linux apps", BrowserTestKit.showsText(r, app.getString(R.string.hm_set_graphics)));
        TextView sub = text(r, app.getString(words));
        Switch sw = gpuSwitch(r);
        assertEquals(offer + ": usable", usable, sw.isEnabled());
        assertEquals(offer + ": on only where usable", usable, sw.isChecked());
        for (int other : new int[]{R.string.hm_gpu_sub, R.string.hm_gpu_sub_virgl,
                R.string.hm_set_gpu_none, R.string.hm_set_gpu_32bit}) {
            if (other != words) {
                assertFalse(offer + ": one state's words only", BrowserTestKit.showsText(r, app.getString(other)));
            }
        }
        // The card is far down the page: scrolled to, as a person would.
        for (android.view.ViewParent p = sub.getParent(); p != null; p = p.getParent()) {
            if (p instanceof ScrollView) {
                int[] at = new int[2];
                sub.getLocationInWindow(at);
                ((ScrollView) p).scrollTo(0, Math.max(0, at[1] - 900));
                break;
            }
        }
        BrowserTestKit.settle(200);
        BrowserTestKit.save(BrowserTestKit.window(c.get()), shot);
        c.pause().stop().destroy();
    }

    @Test
    public void everyState_saysTheSwitchIsForLinuxApps() throws Exception {
        assertTrue(app.getString(R.string.hm_set_graphics).contains("Linux"));
        state(LinuxGpu.Offer.DRIVER, R.string.hm_gpu_sub, true, "gpu-en-driver", null);
        state(LinuxGpu.Offer.VIRGL, R.string.hm_gpu_sub_virgl, true, "gpu-en-virgl", null);
        state(LinuxGpu.Offer.NONE, R.string.hm_set_gpu_none, false, "gpu-en-none", null);
        state(LinuxGpu.Offer.NOT_64_BIT, R.string.hm_set_gpu_32bit, false, "gpu-en-32bit", null);
        for (int s : new int[]{R.string.hm_gpu_sub, R.string.hm_gpu_sub_virgl,
                R.string.hm_set_gpu_none, R.string.hm_set_gpu_32bit}) {
            assertTrue("every line names Linux: " + app.getString(s), app.getString(s).contains("Linux"));
        }
    }

    /** The longest words, German, at a narrow width: nothing cut off. */
    @Test
    public void german_narrow() throws Exception {
        state(LinuxGpu.Offer.VIRGL, R.string.hm_gpu_sub_virgl, true, "gpu-de-360-virgl", "de-w360dp-h780dp-xxhdpi");
        state(LinuxGpu.Offer.NOT_64_BIT, R.string.hm_set_gpu_32bit, false, "gpu-de-360-32bit", "de-w360dp-h780dp-xxhdpi");
    }

    /** "Adreno512v2" from KGSL, "Adreno (TM) 830" from GL: the series that decides Turnip or VirGL. */
    @Test
    public void adrenoNames() {
        assertEquals(512, LinuxGpu.adrenoNumber("Adreno512v2"));
        assertEquals(512, LinuxGpu.adrenoNumber("Adreno (TM) 512"));
        assertEquals(830, LinuxGpu.adrenoNumber("Adreno (TM) 830"));
        assertEquals(618, LinuxGpu.adrenoNumber("Adreno618"));
        assertEquals(-1, LinuxGpu.adrenoNumber("Mali-G57 MC2"));
        assertEquals(-1, LinuxGpu.adrenoNumber("PowerVR Rogue GE8320"));
        assertEquals(-1, LinuxGpu.adrenoNumber(""));
        assertEquals(-1, LinuxGpu.adrenoNumber(null));
    }
}
