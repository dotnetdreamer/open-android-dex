package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The helper's reading of SurfaceFlinger's dump (PhoneHelper.litIn): whether
 * the phone's own panel is lit, the way the helper tells another tool's dark
 * screen (an Open Android DeX desktop's, scrcpy's) from a lit one. The dumps
 * are real phones', cut to their display parts: a Galaxy S25 Ultra on Android
 * 17 ({@code dumpsys SurfaceFlinger --displays}) and a Redmi Note 7 on Android
 * 10 (whose dumpsys knows no --displays and prints everything).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PhoneHelperPanelTest {

    /** The S25's dump, 2026-11-23, with its power mode as given. */
    private static String s25(String mode) {
        return """
                Display 4630947191136750483
                    port=147
                    connectionType=Internal
                    colorModes=
                        ColorMode::NATIVE
                        ColorMode::SRGB
                        ColorMode::DISPLAY_P3
                    deviceProductInfo={name="", manufacturerPnpId=QCM, productId=1, manufactureWeek=27, manufactureYear=2006, relativeAddress=[]}
                    name=""
                    powerMode=%s
                    forceOptimizationPolicyForPower=false
                    optimizationPolicy=optimizeForPerformance
                    renderRate=120.00 Hz
                    activeMode={id=14, hwcId=14, resolution=720x1560, vsyncRate=120.00 Hz, dpi=250.52x250.78, group=14, vrrConfig=N/A, hdrOutputType=INVALID}

                Virtual Display Helper Threads (Pooled by UID)
                    Status=None active
                """.formatted(mode);
    }

    /** The Redmi Note 7's dump, 2026-11-23: its start, and its display, with the power mode as given. */
    private static String redmi(String mode) {
        return """
                Build configuration: [sf PRESENT_TIME_OFFSET=0 FORCE_HWC_FOR_RBG_TO_YUV=1 MAX_VIRT_DISPLAY_DIM=4096 RUNNING_WITHOUT_SYNC_FRAMEWORK=0 NUM_FRAMEBUFFER_SURFACE_BUFFERS=3] [libui] [libgui]

                Display identification data:
                Display 0 (HWC display 0): no identification data

                Wide-Color information:
                Device has wide color built-in display: 0
                Display 0 color modes:
                    ColorMode::NATIVE (0)
                Displays (1 entries)
                + DisplayDevice{0, primary, "Internal display"}
                   powerMode=%s, activeConfig=0, numLayers=3
                mIsScreenProjection=0,    Composition Display State: ["Internal display"]
                   isVirtual=false hwcId=0
                Display 0 HWC layers:
                """.formatted(mode);
    }

    @Test
    public void android17_saysOnAndOff() {
        assertEquals(Boolean.TRUE, PhoneHelper.litIn(s25("ON")));
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(s25("OFF")));
        // An always-on display's modes are not a lit panel.
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(s25("DOZE")));
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(s25("DOZE_SUSPEND")));
        assertEquals(Boolean.TRUE, PhoneHelper.litIn(s25("ON_SUSPEND")));
    }

    @Test
    public void android10_saysItsNumbers() {
        assertEquals(Boolean.TRUE, PhoneHelper.litIn(redmi("2")));
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(redmi("0")));
    }

    /** As adb's shell on Windows hands it over: every line with a carriage return. */
    @Test
    public void carriageReturnsChangeNothing() {
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(s25("OFF").replace("\n", "\r\n")));
        assertEquals(Boolean.TRUE, PhoneHelper.litIn(redmi("2").replace("\n", "\r\n")));
    }

    /** Android 11 and 12, from AOSP's DisplayDevice::dump: "internal" in the name, and the mode as a word and a number. */
    @Test
    public void android12_saysAWordAndANumber() {
        String a12 = """
                Displays (1 entries)
                + DisplayDevice{0, internal, primary, "Built-in Screen"}
                   powerMode=Off (0), activeConfig=0, deviceProductInfo={}, numLayers=4
                """;
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(a12));
        assertEquals(Boolean.TRUE, PhoneHelper.litIn(a12.replace("Off (0)", "On (2)")));
    }

    /** Only built-in panels count: a lit TV or a virtual display does not make the phone's own panel lit. */
    @Test
    public void otherDisplaysDoNotCount() {
        String withOthers = s25("OFF") + """
                Display 4619827259835644672
                    port=0
                    connectionType=External
                    powerMode=ON
                """;
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(withOthers));
        String old = """
                + DisplayDevice{0, internal, primary, "Built-in Screen"}
                   powerMode=Off (0), activeConfig=0
                + DisplayDevice{1, virtual, "scrcpy"}
                   powerMode=On (2), activeConfig=0
                """;
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(old));
    }

    /** A foldable's two panels: lit while either is. */
    @Test
    public void twoBuiltInPanels_litWhileEitherIs() {
        String inner = s25("ON");
        String outer = s25("OFF").replace("4630947191136750483", "4630947191136750484").replace("port=147", "port=148");
        assertEquals(Boolean.TRUE, PhoneHelper.litIn(outer + inner));
        assertEquals(Boolean.FALSE, PhoneHelper.litIn(outer + s25("OFF")));
    }

    /** Nothing to go by: unknown, and the helper then does as it always did. */
    @Test
    public void anythingElseIsUnknown() {
        assertNull(PhoneHelper.litIn(""));
        assertNull(PhoneHelper.litIn("Can't find service: SurfaceFlinger\n"));
        assertNull(PhoneHelper.litIn(s25("SOMETHING_NEW")));
        // A display block whose type is not said.
        assertNull(PhoneHelper.litIn(s25("OFF").replace("    connectionType=Internal\n", "")));
    }
}
