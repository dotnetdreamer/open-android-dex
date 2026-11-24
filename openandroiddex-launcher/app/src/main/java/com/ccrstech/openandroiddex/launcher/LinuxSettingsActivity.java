package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * LinuxOnDroid's Settings page, opened from the home screen's menu: the
 * switches that change how Linux runs rather than what is in it.
 *
 * <ul>
 *   <li><b>Start Linux with the phone</b> ({@link LinuxBoot}): the desktop
 *       comes up in the background after a restart.</li>
 *   <li><b>Use the GPU</b> ({@link LinuxGpu}): was a tile on the home screen.
 *       It lands at the next start of Linux, as it always has. On a phone
 *       whose GPU we do not drive it is shown, switched off and greyed, with
 *       the reason, rather than left out.</li>
 *   <li><b>Open in a browser</b> ({@link LinuxBrowserServer}), LinuxOnDroid
 *       only: Linux on a computer's screen, through its browser. Switched on,
 *       the card says the two things a person needs at the computer, the
 *       address and the code, as two numbered steps, and follows what is
 *       happening while it is in front: no network, waiting, or which browser
 *       is showing Linux now. The desktop build has the desktop app for that.</li>
 * </ul>
 *
 * Read from disk each time the page comes to the front, since the phone's
 * own settings, which the page links to, can be where the user just was.
 *
 * <p>Both apps have it. LinuxOnDroid opens it from its home screen's menu, in
 * the home screen's own colours. The desktop opens it from the Linux tile's
 * menu as a window of its own, in the desktop's theme, light, dark or paper,
 * since a dark page in a light desktop reads as another app's. There the
 * window's bar carries Back, so the page leaves its own out, and the taskbar
 * lists the window (OwnWindows) like the desktop's other windows. Built in
 * code, like every window in this app.
 */
public class LinuxSettingsActivity extends Activity {

    private static final int AMBER = LinuxHomeViews.AMBER;

    private DexTheme theme;
    private LinearLayout content;
    /** LinuxOnDroid: the home screen's look. The desktop: the desktop theme's. */
    private boolean homeLook;
    private int title;
    private int cardFill;
    private int cardEdge;
    private int ruleColor;
    private int linkColor;
    private int green;
    private int violet;
    private int sky;
    /** "Open in a browser"'s card, kept current while the page is in front. */
    private LinearLayout browserCard;
    /** What the card was last drawn from, so an unchanged second redraws nothing. */
    private String browserDrawn = "";
    /** When this page last told the code it may be used, for ten minutes from then. */
    private long pairingOpenedAt;
    private final android.os.Handler ticks = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable browserTick = new Runnable() {
        @Override
        public void run() {
            drawBrowser(false);
            drawPhone(false);
            if (email != null) email.tick(false);
            ticks.postDelayed(this, 1000);
        }
    };
    /** Remote desktop's card: this phone's own screen on a computer. */
    private LinearLayout phoneCard;
    private String phoneDrawn = "";
    /** "Email me when an address changes" (EmailNotice): its own card, below. */
    private EmailCard email;
    /** A light theme: a white thumb would vanish on its white card. */
    private boolean lightSwitches;
    private int thumbOff;
    private int trackOff;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Made again while Android's battery question was up (the phone
        // turned, the theme changed): screen sharing's question still follows.
        if (savedInstanceState != null) consentAfterBattery = savedInstanceState.getBoolean(KEY_CONSENT_AFTER_BATTERY);
        // Opened afresh (not made again after a turn): Easy access's last "did not change" is an earlier page's.
        if (savedInstanceState == null) PhoneEasyAccess.pageOpened();
        theme = DexTheme.of(this);
        homeLook = Linux.hasHomeScreen(this);
        palette();
        OwnWindows.opened(this);
        setTitle(pageName());
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.windowBg()));
        }

        FrameLayout root = new FrameLayout(this);
        if (homeLook) {
            // The home screen's page: the window colour, lit from the top by
            // the same two lights, so this reads as a page of the same app.
            GradientDrawable warm = new GradientDrawable();
            warm.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            warm.setColors(new int[]{0x14FFB547, 0x00FFB547});
            warm.setGradientCenter(0.9f, 0f);
            warm.setGradientRadius(dp(420));
            GradientDrawable cool = new GradientDrawable();
            cool.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            cool.setColors(new int[]{0x145B7CFF, 0x005B7CFF});
            cool.setGradientCenter(0.05f, 0.04f);
            cool.setGradientRadius(dp(380));
            root.setBackground(new LayerDrawable(new Drawable[]{
                    new ColorDrawable(theme.windowBg()), cool, warm}));
        } else {
            root.setBackgroundColor(theme.windowBg());
        }

        final ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        FrameLayout center = new FrameLayout(this);
        LinuxHomeViews.Column column = new LinuxHomeViews.Column(this);
        column.setMaxWidth(dp(560));
        center.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        scroller.addView(center, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Edge to edge, as the home screen: the page fades out before it
        // reaches the status bar's icons.
        final View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{theme.windowBg(), theme.windowBg() & 0x00FFFFFF}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            scroller.setPadding(l + dp(20), t + dp(6), r + dp(20), b + dp(28));
            ViewGroup.LayoutParams lp = scrim.getLayoutParams();
            lp.height = t > 0 ? t + dp(12) : 0;
            scrim.setLayoutParams(lp);
            return insets;
        });
        root.requestApplyInsets();

        column.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        DexCursors.decorate(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
        // The address can change under the page (another Wi-Fi), and the card
        // says who is watching: looked at once a second while it is in front.
        if (browserCard != null || phoneCard != null) ticks.postDelayed(browserTick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticks.removeCallbacks(browserTick);
        // What was typed into the email card is kept, the password sealed.
        if (email != null) email.save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OwnWindows.closed(this);
    }

    /**
     * The colours. LinuxOnDroid's are its home screen's, which is always dark;
     * the desktop's come from its theme, with the two icon colours darkened
     * where the theme is light so they still read on a light card.
     */
    private void palette() {
        if (homeLook) {
            title = 0xFFF5F7FB;
            cardFill = 0xFF141B27;
            cardEdge = 0x14FFFFFF;
            ruleColor = 0x12FFFFFF;
            linkColor = AMBER;
        } else {
            title = theme.text;
            cardFill = theme.cardSolid;
            cardEdge = theme.divider;
            ruleColor = theme.divider;
            linkColor = theme.accent;
        }
        boolean dark = homeLook || theme.dark;
        green = dark ? 0xFF4ADE80 : 0xFF15803D;
        violet = dark ? 0xFFB49CFF : 0xFF6D28D9;
        sky = dark ? 0xFF7DD3FC : 0xFF0369A1;
        lightSwitches = !dark;
        thumbOff = dark ? 0xFFC9D1DC : 0xFF9AA3AF;
        trackOff = dark ? 0x40FFFFFF : 0x33000000;
    }

    /** "Settings" in LinuxOnDroid; "Linux settings" on a desktop, which has Settings of its own. */
    private String pageName() {
        return getString(homeLook ? R.string.settings_label : R.string.ln_settings_title);
    }

    /**
     * Back, and the page's name: the same top bar as Remote access
     * ({@link LinuxHomeViews#pageBar}). A window on the desktop display has
     * Back on its own bar (CaptionService), so there the page shows only its name.
     */
    private View topBar() {
        return LinuxHomeViews.pageBar(this, pageName(), title, homeLook || theme.dark);
    }

    /** The sections, as things stand now. */
    private void fill() {
        content.removeAllViews();
        browserCard = null;
        phoneCard = null;

        // LinuxOnDroid's alone: a desktop on a computer already has the
        // desktop app, which shows Linux there over the cable or the tunnel.
        if (homeLook) {
            header(getString(R.string.bw_set_header));
            browserCard = card();
            content.addView(browserCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            drawBrowser(true);
        }

        // Remote desktop: this phone itself, not Linux, on a computer. Where
        // the APK has it (PhoneRemote.offered): LinuxOnDroid.
        if (homeLook && PhoneRemote.offered(this)) {
            header(getString(R.string.pr_set_header));
            phoneCard = card();
            content.addView(phoneCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            drawPhone(true);
        }

        // Email me when an address changes (EmailNotice): LinuxOnDroid's,
        // right after the two doors whose addresses it emails.
        email = null;
        if (homeLook && EmailNotice.offered(this)) {
            header(getString(R.string.em_set_header));
            email = new EmailCard();
            content.addView(email.card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        header(getString(R.string.hm_set_startup));
        LinearLayout boot = card();
        boot.addView(switchRow(R.drawable.hm_ic_power, green,
                getString(R.string.hm_set_boot), getString(R.string.hm_set_boot_sub),
                LinuxBoot.enabled(this), true, this::toggleBoot));
        // Android lets the start through; some phones' own rules do not, and
        // where they live differs by maker. App info is the one page every
        // phone has, and the way to all of them.
        View line = new View(this);
        line.setBackgroundColor(ruleColor);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        ruleLp.setMarginStart(dp(56));
        boot.addView(line, ruleLp);
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        // Under the row's words, past its icon, on whichever side the line starts.
        hint.setPaddingRelative(dp(56), dp(10), 0, dp(4));
        // The app to allow is this one: LinuxOnDroid, or on a desktop the
        // launcher, whose App info the link below opens.
        TextView words = text(getString(R.string.hm_set_boot_hint,
                getApplicationInfo().loadLabel(getPackageManager())), 13, theme.textFaint, 400);
        words.setLineSpacing(0, 1.15f);
        hint.addView(words, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView open = text(getString(R.string.hm_set_app_settings), 14, linkColor, 600);
        open.setGravity(Gravity.CENTER_VERTICAL);
        open.setMinHeight(dp(44));
        open.setPadding(dp(10), 0, dp(10), 0);
        open.setBackground(pressable(0x00000000, dp(12), 0));
        asButton(open, getString(R.string.hm_set_app_settings));
        open.setOnClickListener(v -> openAppInfo());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.setMarginStart(-dp(10));
        openLp.topMargin = dp(2);
        hint.addView(open, openLp);
        boot.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(boot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The graphics chip for Linux apps, and every state says so: Remote
        // desktop uses the chip whatever this says. On a phone where Linux
        // cannot run, or where neither our drivers nor VirGL work, the switch
        // stays grey and the line under it says why.
        header(getString(R.string.hm_set_graphics));
        LinuxGpu.Offer offer = LinuxGpu.offer(this);
        boolean gpu = offer == LinuxGpu.Offer.DRIVER || offer == LinuxGpu.Offer.VIRGL;
        int sub = offer == LinuxGpu.Offer.DRIVER ? R.string.hm_gpu_sub
                : offer == LinuxGpu.Offer.VIRGL ? R.string.hm_gpu_sub_virgl
                : offer == LinuxGpu.Offer.NOT_64_BIT ? R.string.hm_set_gpu_32bit
                : R.string.hm_set_gpu_none;
        LinearLayout graphics = card();
        graphics.addView(switchRow(R.drawable.hm_ic_gpu, violet, getString(R.string.ln_gpu_menu),
                getString(sub), gpu && LinuxGpu.enabled(this), gpu, this::toggleGpu));
        content.addView(graphics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        DexFonts.applyTo(this, content);
        DexCursors.decorate(content);
    }

    /** @return the setting's new value */
    private boolean toggleBoot() {
        boolean on = !LinuxBoot.enabled(this);
        LinuxBoot.setEnabled(this, on);
        return on;
    }

    /** Lands when Linux next starts, and says so, as the desktop tile's switch does. */
    private boolean toggleGpu() {
        boolean on = !LinuxGpu.enabled(this);
        LinuxGpu.setEnabled(this, on);
        Toast.makeText(this, getString(on ? R.string.ln_gpu_on : R.string.ln_gpu_off),
                Toast.LENGTH_LONG).show();
        return on;
    }

    // ── Remote desktop ──

    /**
     * On: listening at once, then Android's battery question (unless the app
     * is already left out of battery optimisation) and, for a share kept
     * ready, Android's own question about screen sharing after it, so the
     * switch is all it takes. Without "Keep full speed ready" nothing is
     * shared until a computer opens the phone, whose arrival asks then. Off:
     * whatever a computer has open ends, and screen sharing stops.
     */
    private boolean togglePhone() {
        boolean on = !PhoneRemote.isEnabled(this);
        PhoneRemote.setEnabled(this, on);
        if (on) {
            PhoneStream.switchedOn(this);
            // Android's battery question first, where the app is not left out
            // of battery optimisation yet: two buttons, and Allow is all it
            // takes. Screen sharing's own question follows its answer.
            if (!PhoneRemote.batteryExempt(this)) {
                askBattery(shareToKeep());
            } else if (shareToKeep()) {
                openConsent();
            }
        } else {
            PhoneStream.switchedOff();
            stopService(new Intent(this, PhoneRemoteService.class));
            stopService(new Intent(this, PhoneListenService.class));
        }
        // The tunnel computers come through goes up and down with it.
        LinuxService.phoneRemoteChanged(this);
        ticks.post(() -> drawPhone(true));
        return on;
    }

    private void openConsent() {
        try {
            startActivity(new Intent(this, PhoneConsentActivity.class));
        } catch (Exception e) {
            DexLog.warn("phone", "cannot ask for screen sharing", e);
        }
    }

    /** Android's accessibility settings, where control is turned off and on again to connect it. */
    private void openAccessibilitySettings() {
        try {
            startActivity(PhoneRemote.accessibilitySettings(this));
        } catch (Exception e) {
            DexLog.warn("phone", "cannot open the accessibility settings", e);
        }
    }

    /**
     * A share to ask for now, with someone at the phone: one kept ready
     * ("Keep full speed ready") where the next one would need someone here,
     * and not held yet. Any other share starts when a computer opens the phone.
     */
    private boolean shareToKeep() {
        return PhoneRemote.keepsReady(this) && !PhoneRemote.unattended(this) && !PhoneRemoteService.sharing();
    }

    /** @return the setting's new value */
    private boolean toggleReady() {
        boolean on = !PhoneRemote.keepsReady(this);
        PhoneRemote.setKeepsReady(this, on);
        // On: the share to hold is asked for now, while someone is at the
        // phone. Off: a share nobody watches ends a little later.
        if (on && shareToKeep()) openConsent();
        PhoneRemoteService.readyChanged();
        ticks.post(() -> drawPhone(true));
        return on;
    }

    private static final int REQ_BATTERY = 0x7B;
    private static final String KEY_CONSENT_AFTER_BATTERY = "consentAfterBattery";
    /** Screen sharing's question waits for the battery question's answer. */
    private boolean consentAfterBattery;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_CONSENT_AFTER_BATTERY, consentAfterBattery);
    }

    /**
     * Android's own question, "let LinuxOnDroid always run in the
     * background?", with two buttons: Allow takes the app out of battery
     * optimisation (Doze) at once, nothing more to do. Without it an idle
     * phone's Doze holds Remote desktop's network and timers back, and some
     * phones stop the app after a few days. Where a phone has no such
     * question, its list of apps, and screen sharing's question waits for
     * the person to come back from it, as it waits for the answer.
     */
    private void askBattery(boolean thenConsent) {
        consentAfterBattery = thenConsent;
        try {
            startActivityForResult(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())), REQ_BATTERY);
            return;
        } catch (Exception e) {
            DexLog.warn("phone", "no battery question on this phone", e);
        }
        try {
            // For a result too: back from the list is the answer.
            startActivityForResult(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    REQ_BATTERY);
            return;
        } catch (Exception ignored) {
            // nowhere to send the person: the card keeps saying so
        }
        if (thenConsent && !PhoneRemoteService.sharing()) openConsent();
        consentAfterBattery = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BATTERY) return;
        DexLog.step("phone", PhoneRemote.batteryExempt(this)
                ? "left out of battery optimisation" : "battery optimisation stays on");
        if (consentAfterBattery && PhoneRemote.isEnabled(this) && !PhoneRemoteService.sharing()) openConsent();
        consentAfterBattery = false;
        ticks.post(() -> drawPhone(true));
    }

    /** @return the setting's new value */
    private boolean toggleAnywhere() {
        boolean on = !PhoneTunnel.switchedOn(this);
        PhoneTunnel.setSwitchedOn(this, on);
        // The service runs the tunnel beside the door: told to look again.
        LinuxService.phoneRemoteChanged(this);
        ticks.post(() -> drawPhone(true));
        return on;
    }

    /** @return the setting's new value */
    private boolean togglePhoneBoot() {
        boolean on = !PhoneRemote.startsWithPhone(this);
        PhoneRemote.setStartsWithPhone(this, on);
        return on;
    }

    /**
     * Easy access's switch. No app may give itself the app-op behind it, or
     * take it away, so the phone's helper is needed both ways: without the
     * one-time setup of Screen off, the setup opens instead. On asks first,
     * since it lets a computer in with nobody at the phone; off needs no
     * question.
     *
     * @return the switch's state now: Android's own, which changes only once the helper has done it
     */
    private boolean toggleEasy() {
        boolean on = PhoneRemote.unattended(this);
        if (!PhoneEasyAccess.working().isEmpty()) return on;
        String helper = PhoneHelperStart.state(this);
        if (PhoneHelperStart.SETUP.equals(helper) || PhoneHelperStart.UNPAIRED.equals(helper)) {
            showHelperSetup();
        } else if (on) {
            PhoneEasyAccess.set(this, false);
            ticks.post(() -> drawPhone(true));
        } else {
            confirmEasy();
        }
        return on;
    }

    /**
     * Under Easy access's switch: a change under way, why the last one did
     * not happen, or what the helper needs first. Null when there is nothing
     * to say: the switch and the words in its row say the rest.
     */
    private View easyLines() {
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPaddingRelative(dp(56), 0, 0, dp(14));
        String working = PhoneEasyAccess.working();
        String failed = PhoneEasyAccess.failed(this);
        String helper = PhoneHelperStart.state(this);
        if (!working.isEmpty()) {
            lines.addView(statusLine(sky, getString(PhoneEasyAccess.TURNING_ON.equals(working)
                    ? R.string.pr_set_easy_turning_on : R.string.pr_set_easy_turning_off)));
        } else if (PhoneEasyAccess.NO_HELPER.equals(failed)) {
            lines.addView(statusLine(AMBER, getString(R.string.pr_set_easy_nohelper)));
        } else if (PhoneEasyAccess.NO_ANSWER.equals(failed)) {
            lines.addView(statusLine(AMBER, getString(R.string.pr_set_easy_noanswer)));
        } else if (!failed.isEmpty()) {
            String why = failed.length() > 140 ? failed.substring(0, 140) + "…" : failed;
            lines.addView(statusLine(AMBER, getString(R.string.pr_set_easy_failed, why)));
        } else if (PhoneHelperStart.SETUP.equals(helper) || PhoneHelperStart.UNPAIRED.equals(helper)) {
            lines.addView(statusLine(sky, getString(R.string.pr_set_easy_setup)));
            lines.addView(link(getString(R.string.ph_set_setup), this::showHelperSetup), linkParams());
        } else if (PhoneHelperStart.PC.equals(helper)) {
            lines.addView(statusLine(sky, getString(R.string.pr_set_easy_pc)));
        } else {
            return null;
        }
        return lines;
    }

    /** Before Easy access goes on: what a computer can do then, and a choice. */
    private void confirmEasy() {
        if (isFinishing()) return;
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(homeLook ? 0xFF161D2A : theme.cardSolid, dp(26), cardEdge));
        panel.addView(text(getString(R.string.pr_easy_title), 19, title, 700));
        TextView body = text(getString(R.string.pr_easy_body), 14.5f, theme.textFaint, 400);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, boxParams(dp(10)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(dialogButton(getString(R.string.pr_disclose_no), 0x00000000, theme.text, d::dismiss));
        TextView yes = dialogButton(getString(R.string.pr_easy_yes), green, 0xFF0B0F17, () -> {
            d.dismiss();
            PhoneEasyAccess.set(this, true);
            ticks.post(() -> drawPhone(true));
        });
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.setMarginStart(dp(8));
        buttons.addView(yes, yesLp);
        panel.addView(buttons, boxParams(dp(22)));
        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        d.show();
        if (d.getWindow() != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            d.getWindow().setLayout(Math.min(screen - dp(32), dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * The card: the switch, and with it on, three things a person needs to
     * know (is the screen shared, can the computer control the phone, which
     * computers may come in) and the two choices (the picture, and starting
     * with the phone). Drawn again only when one of those changed.
     */
    private void drawPhone(boolean force) {
        if (phoneCard == null) return;
        boolean on = PhoneRemote.isEnabled(this);
        PhoneRemote.Status st = PhoneRemote.status(this);
        boolean control = PhoneRemote.controlEnabled(this);
        boolean controlLost = !control && PhoneRemote.controlLost(this);
        boolean boot = PhoneRemote.startsWithPhone(this);
        boolean tunnel = LinuxRemote.available(this);
        int computers = tunnel ? LinuxRemote.peers(this).size() : 0;
        // A browser on the same Wi-Fi, with nothing installed: the door
        // "Open in a browser" uses, up for Remote desktop too. With Linux's
        // switch off, the door's own page is the phone's, so the two steps
        // here are the address and the code, as on Linux's card.
        LinuxBrowser door = LinuxService.browser(this);
        boolean doorForPhone = on && !door.isOn();
        String doorState = doorForPhone ? door.state() : "";
        java.util.List<LinuxBrowser.Address> where = doorForPhone ? LinuxBrowser.addresses()
                : java.util.Collections.<LinuxBrowser.Address>emptyList();
        String code = doorForPhone ? door.code() : "";
        boolean usedUp = doorForPhone && door.codeUsedUp();
        int remembered = doorForPhone ? door.tokenCount() : 0;
        // From anywhere: the same door through Cloudflare's link, so the same
        // code signs a browser in there, and the card shows the code there
        // too: with no Wi-Fi the Wi-Fi steps (and their code) are not shown.
        String farUrl = on && PhoneTunnel.available(this) && PhoneTunnel.switchedOn(this) ? PhoneTunnel.address(this) : "";
        String farCode = farUrl.isEmpty() ? "" : door.code();
        boolean farUsedUp = !farUrl.isEmpty() && door.codeUsedUp();
        boolean nearCode = doorForPhone && !usedUp && !where.isEmpty() && !doorState.startsWith("failed");
        boolean farCodeShown = !farUrl.isEmpty() && !farUsedUp && !door.state().startsWith("failed");
        if (nearCode || farCodeShown) {
            long now = System.currentTimeMillis();
            if (force || now - pairingOpenedAt > 30_000) {
                door.openPairing(now);
                pairingOpenedAt = now;
            }
        }
        String drawn = on + "|" + st.state + "|" + st.who + "|" + control + "|" + controlLost + "|" + boot
                + "|" + computers + "|" + tunnel + "|" + door.isOn() + "|" + doorState + "|" + where + "|" + code
                + "|" + usedUp + "|" + remembered + "|" + PhoneTunnel.switchedOn(this) + "|" + PhoneTunnel.address(this)
                + "|" + phoneFilesOffered() + "|" + WebFiles.phoneFilesAllowed(this) + "|" + PhoneSound.allowed(this)
                + "|" + PhoneRemote.batteryExempt(this) + "|" + PhoneRemote.xiaomiAutostart(this)
                + "|" + PhoneRemote.keepsReady(this) + "|" + PhoneRemote.unattended(this)
                + "|" + PhoneRemote.controlUnbound(this)
                + "|" + PhoneHelperStart.state(this) + "|" + PhoneHelperStart.why()
                + "|" + PhoneEasyAccess.working() + "|" + PhoneEasyAccess.failed(this)
                + "|" + farCode + "|" + farUsedUp;
        refreshHelperSetup();
        if (!force && drawn.equals(phoneDrawn)) return;
        phoneDrawn = drawn;

        phoneCard.removeAllViews();
        phoneCard.addView(switchRow(R.drawable.hm_ic_remote, green, getString(R.string.pr_set_title),
                getString(R.string.pr_set_sub), on, true, this::togglePhone));
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        ruleLp.setMarginStart(dp(56));
        if (on) {
            View rule = new View(this);
            rule.setBackgroundColor(ruleColor);
            phoneCard.addView(rule, ruleLp);
            phoneCard.addView(phonePanel(st, control, computers, tunnel), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View ruleB = new View(this);
            ruleB.setBackgroundColor(ruleColor);
            phoneCard.addView(ruleB, ruleLp);
            LinearLayout inBrowser = new LinearLayout(this);
            inBrowser.setOrientation(LinearLayout.VERTICAL);
            inBrowser.setPaddingRelative(dp(56), dp(14), 0, 0);
            inBrowser.addView(text(getString(R.string.pr_set_browser), 14, theme.text, 600));
            phoneCard.addView(inBrowser);
            if (doorForPhone) {
                phoneCard.addView(browserSteps(doorState, where, code, usedUp, remembered), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                LinearLayout too = new LinearLayout(this);
                too.setOrientation(LinearLayout.VERTICAL);
                too.setPaddingRelative(dp(56), dp(8), 0, dp(14));
                too.addView(statusLine(sky, getString(R.string.pr_set_browser_too)));
                phoneCard.addView(too);
            }
            // From anywhere: the same door, through a Cloudflare tunnel (PhoneTunnel).
            if (PhoneTunnel.available(this)) {
                View ruleA = new View(this);
                ruleA.setBackgroundColor(ruleColor);
                phoneCard.addView(ruleA, ruleLp);
                boolean anywhere = PhoneTunnel.switchedOn(this);
                phoneCard.addView(switchRow(R.drawable.hm_ic_browser, sky, getString(R.string.pr_set_anywhere),
                        getString(R.string.pr_set_anywhere_sub), anywhere, true, this::toggleAnywhere));
                if (anywhere) {
                    LinearLayout far = new LinearLayout(this);
                    far.setOrientation(LinearLayout.VERTICAL);
                    far.setPaddingRelative(dp(56), 0, 0, dp(14));
                    String url = farUrl;
                    String doorNow = door.state();
                    if (url.isEmpty()) {
                        far.addView(statusLine(AMBER, getString(R.string.pr_set_anywhere_wait)));
                    } else if (doorNow.startsWith("failed")) {
                        // Nothing of ours is listening behind the link: no code to send a person to.
                        far.addView(statusLine(AMBER, failedWhy(doorNow, doorNow.split(" ", 3))));
                    } else {
                        // The same two steps as on the Wi-Fi, the code shown here too:
                        // with no Wi-Fi the Wi-Fi steps (and their code) are not there.
                        far.addView(stepTitle(1, getString(R.string.pr_set_anywhere_open)));
                        far.addView(addressBox(url.substring("https://".length()), url + "/phone.html"), boxParams(dp(10)));
                        far.addView(stepTitle(2, getString(R.string.bw_set_step_code)), boxParams(dp(20)));
                        addCode(far, farCode, farUsedUp);
                    }
                    TextView note = text(getString(R.string.pr_set_anywhere_note), 12.5f, theme.textFaint, 400);
                    note.setLineSpacing(0, 1.15f);
                    far.addView(note, boxParams(dp(10)));
                    phoneCard.addView(far);
                }
            }
            addEasyAccess(ruleLp);
            // A share held while no computer watches: only where the next
            // one would need someone at the phone (with unattended access it
            // starts by itself), and asked for again after a restart only
            // when held (PhoneRemote.asksAfterRestart).
            if (!PhoneRemote.unattended(this)) {
                boolean ready = PhoneRemote.keepsReady(this);
                View ruleR = new View(this);
                ruleR.setBackgroundColor(ruleColor);
                phoneCard.addView(ruleR, ruleLp);
                phoneCard.addView(switchRow(R.drawable.hm_ic_remote, green, getString(R.string.pr_set_ready),
                        getString(R.string.pr_set_ready_sub), ready, true, this::toggleReady));
                if (ready) {
                    View rule2 = new View(this);
                    rule2.setBackgroundColor(ruleColor);
                    phoneCard.addView(rule2, ruleLp);
                    phoneCard.addView(switchRow(R.drawable.hm_ic_power, green, getString(R.string.pr_set_boot),
                            getString(R.string.pr_set_boot_sub), boot, true, this::togglePhoneBoot));
                }
            }
        } else if (PhoneRemote.unattended(this) || !PhoneEasyAccess.working().isEmpty()
                || !PhoneEasyAccess.failed(this).isEmpty()) {
            // Remote desktop off, and Easy access still given (Android keeps
            // it): the switch stays, so it can be taken away here without
            // turning Remote desktop on first, which would also start a From
            // anywhere link. Gone once Easy access is off.
            addEasyAccess(ruleLp);
        }
        DexFonts.applyTo(this, phoneCard);
        DexCursors.decorate(phoneCard);
    }

    /**
     * Easy access: a computer the person allowed sees the screen with nobody
     * at the phone, even while it is locked (PROJECT_MEDIA, set by the
     * phone's helper: PhoneEasyAccess). The switch is Android's own answer;
     * under it, a change under way, why the last one did not happen, or the
     * setup it needs.
     */
    private void addEasyAccess(LinearLayout.LayoutParams ruleLp) {
        View ruleE = new View(this);
        ruleE.setBackgroundColor(ruleColor);
        phoneCard.addView(ruleE, ruleLp);
        phoneCard.addView(switchRow(R.drawable.hm_ic_shield, green, getString(R.string.pr_set_easy),
                getString(R.string.pr_set_unattended_on), PhoneRemote.unattended(this), true, this::toggleEasy));
        View easy = easyLines();
        if (easy != null) phoneCard.addView(easy);
    }

    /**
     * What is happening and what is missing. No picture quality here: the
     * person at the computer sees the picture and the link, so the window
     * there chooses Low, Medium or High (PhoneView), and the phone follows.
     */
    private View phonePanel(PhoneRemote.Status st, boolean control, int computers, boolean tunnel) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPaddingRelative(dp(56), dp(14), 0, dp(14));

        // The screen.
        if ("live".equals(st.state)) {
            panel.addView(statusLine(green, getString(R.string.pr_set_live)));
        } else if ("ready".equals(st.state)) {
            panel.addView(statusLine(green, getString(R.string.pr_set_sharing_on)));
        } else if (!PhoneRemote.unattended(this)) {
            if (PhoneRemote.keepsReady(this)) {
                // A share is wanted and none is held: someone here gives it.
                panel.addView(statusLine(AMBER, getString(control ? R.string.pr_set_sharing_slow : R.string.pr_set_sharing_off)));
                panel.addView(link(getString(R.string.pr_set_share), this::openConsent), linkParams());
            } else {
                // Nothing shared with no computer watching is how it should
                // be: the next computer's arrival asks.
                panel.addView(statusLine(sky, getString(control ? R.string.pr_set_sharing_asks : R.string.pr_set_sharing_asks_off)));
            }
        }
        // With unattended access (Easy access) the screen is shared only
        // while a computer has it open, and that share starts by itself
        // (PhoneRemoteService.idleCheck): none now is nothing to ask for.
        // Easy access's own switch, further down the card, says how it works.

        // The control. On in Android's settings is not yet connected: after an
        // update Android 10 has left it so until it was turned off and on.
        if (control && PhoneRemote.controlUnbound(this)) {
            panel.addView(statusLine(AMBER, getString(R.string.pr_set_control_unbound)), boxParams(dp(12)));
            panel.addView(link(getString(R.string.pr_set_control_reconnect), this::openAccessibilitySettings), linkParams());
        } else if (control) {
            panel.addView(statusLine(green, getString(R.string.pr_set_control_on)), boxParams(dp(12)));
        } else {
            panel.addView(statusLine(AMBER, getString(PhoneRemote.controlLost(this)
                    ? R.string.pr_set_control_lost : R.string.pr_set_control_off)), boxParams(dp(12)));
            panel.addView(link(getString(R.string.pr_set_control_turn_on), this::confirmControl), linkParams());
        }
        // Screen off, and the buttons Android keeps for someone at the phone:
        // the phone's helper, which the app starts itself once set up
        // (PhoneHelperStart). Nothing else on this card needs it.
        helperLines(panel);
        // Running in the background: Android's battery optimisation (Doze)
        // may stop an idle phone's Remote desktop. Android's own question,
        // two buttons, takes the app out of it.
        if (PhoneRemote.batteryExempt(this)) {
            panel.addView(statusLine(green, getString(R.string.pr_set_bg_on)), boxParams(dp(12)));
        } else {
            panel.addView(statusLine(AMBER, getString(R.string.pr_set_bg_off)), boxParams(dp(12)));
            panel.addView(link(getString(R.string.pr_set_bg_allow), () -> askBattery(false)), linkParams());
        }
        // A Xiaomi phone's Clear all in Recent apps kills this app, and with
        // MIUI's Autostart off, MIUI refuses every restart after: Remote
        // desktop stays gone. MIUI has no question of its own for it, only
        // its Autostart switch, which the link opens. Said only when MIUI
        // says Autostart is off; a phone whose switch cannot be read gets
        // the tip below alone.
        if (PhoneRemote.xiaomi()) {
            if (Boolean.FALSE.equals(PhoneRemote.xiaomiAutostart(this))) {
                panel.addView(statusLine(AMBER, getString(R.string.pr_set_autostart_off)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.pr_set_autostart_allow), () -> {
                    if (!PhoneRemote.openXiaomiAutostart(this)) {
                        Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
                    }
                }), linkParams());
            }
            TextView tip = text(getString(R.string.pr_set_xiaomi_tip), 12.5f, theme.textFaint, 400);
            tip.setLineSpacing(0, 1.15f);
            panel.addView(tip, boxParams(dp(12)));
        }

        // The phone's sound, for the computer that chooses Sound there
        // (PhoneSound): Android 10 and newer, once Android's permission is given.
        if (PhoneSound.supported()) {
            if (PhoneSound.allowed(this)) {
                panel.addView(statusLine(green, getString(R.string.pr_set_sound_on)), boxParams(dp(12)));
            } else {
                panel.addView(statusLine(AMBER, getString(R.string.pr_set_sound_off)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.pr_set_sound_allow), this::confirmSound), linkParams());
            }
        }

        // The phone's files, for a browser's Files (LinuxService's phoneFiles):
        // only in a build that can have All files access.
        if (phoneFilesOffered()) {
            if (WebFiles.phoneFilesAllowed(this)) {
                panel.addView(statusLine(green, getString(R.string.pr_set_files_on)), boxParams(dp(12)));
            } else {
                panel.addView(statusLine(AMBER, getString(R.string.pr_set_files_off)), boxParams(dp(12)));
                // Android 10 with the permission already given and the storage
                // still kept from the app: asking again changes nothing.
                if (Build.VERSION.SDK_INT >= 30 || !WebFiles.storagePermitted(this)) {
                    panel.addView(link(getString(R.string.pr_set_files_allow),
                            () -> WebFiles.requestAllFiles(this, null, R.string.pr_set_files_grant_body)), linkParams());
                }
            }
        }

        // The computers, through remote access's pairing.
        if (tunnel) {
            panel.addView(statusLine(computers > 0 ? green : AMBER,
                    computers > 0 ? getResources().getQuantityString(R.plurals.pr_set_computers, computers, computers)
                            : getString(R.string.pr_set_no_computers)), boxParams(dp(12)));
            panel.addView(link(getString(R.string.pr_set_add_computer), () -> {
                try {
                    startActivity(new Intent(this, LinuxRemoteActivity.class));
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
                }
            }), linkParams());
        } else {
            panel.addView(statusLine(AMBER, getString(R.string.pr_set_no_tunnel)), boxParams(dp(12)));
        }
        return panel;
    }

    /**
     * A browser's Files can show this phone's own storage: Android 10 and
     * newer, in a build that asks for All files access (not Google Play's).
     * The door says the same to the page (LinuxService's phoneFiles).
     */
    private boolean phoneFilesOffered() {
        return WebFiles.phoneFilesOffered(this);
    }

    private TextView link(String words, Runnable onTap) {
        TextView t = text(words, 14, linkColor, 600);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(44));
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(pressable(0x00000000, dp(12), 0));
        asButton(t, words);
        t.setOnClickListener(v -> onTap.run());
        return t;
    }

    private LinearLayout.LayoutParams linkParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        return lp;
    }

    // ── Screen off: the phone's helper, started by the app (PhoneHelperStart) ──

    /** The setup's steps while its dialog is up: drawn again each tick and on coming back from Android's settings. */
    private android.app.Dialog helperDialog;
    private LinearLayout helperSteps;
    private String helperStepsDrawn = "";
    private static final int ASK_NOTIFICATIONS = 0x48;

    /** What the card says about Screen off: ready, set up, or what is missing, with the one link that helps. */
    private void helperLines(LinearLayout panel) {
        String s = PhoneHelperStart.state(this);
        switch (s) {
            case PhoneHelperStart.READY:
                panel.addView(statusLine(green, getString(R.string.ph_set_ready)), boxParams(dp(12)));
                break;
            case PhoneHelperStart.WAITING:
                panel.addView(statusLine(green, getString(R.string.ph_set_waiting)), boxParams(dp(12)));
                break;
            case PhoneHelperStart.STARTING:
                panel.addView(statusLine(sky, getString(R.string.ph_set_starting)), boxParams(dp(12)));
                break;
            case PhoneHelperStart.PC:
                panel.addView(statusLine(sky, getString(R.string.ph_set_pc)), boxParams(dp(12)));
                break;
            case PhoneHelperStart.UNPAIRED:
                panel.addView(statusLine(AMBER, getString(R.string.ph_set_unpaired)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.ph_set_setup), this::showHelperSetup), linkParams());
                break;
            case PhoneHelperStart.DEV_OFF:
                panel.addView(statusLine(AMBER, getString(R.string.ph_set_devoff)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.ph_setup_open_about), this::openAboutPhone), linkParams());
                break;
            case PhoneHelperStart.WIRELESS_OFF:
                panel.addView(statusLine(AMBER, getString(R.string.ph_set_wireless)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.ph_set_open_dev), this::openDeveloperOptions), linkParams());
                break;
            case PhoneHelperStart.WIFI:
                panel.addView(statusLine(AMBER, getString(R.string.ph_set_wifi)), boxParams(dp(12)));
                break;
            case PhoneHelperStart.ASK:
                panel.addView(statusLine(AMBER, getString(R.string.ph_set_ask)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.ph_set_try), () -> PhoneHelperStart.ensure(this, true)), linkParams());
                break;
            case PhoneHelperStart.FAILED: {
                String why = PhoneHelperStart.why();
                if (why.length() > 140) why = why.substring(0, 140) + "…";
                panel.addView(statusLine(AMBER, getString(R.string.ph_set_failed, why)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.ph_set_try), () -> PhoneHelperStart.ensure(this, true)), linkParams());
                break;
            }
            default: // SETUP
                panel.addView(statusLine(sky, getString(R.string.ph_set_setup_line)), boxParams(dp(12)));
                panel.addView(link(getString(R.string.ph_set_setup), this::showHelperSetup), linkParams());
                break;
        }
    }

    /**
     * Screen off's setup: what it gives, then three steps (Developer
     * options, Wireless debugging, the pairing code), each ticked off as the
     * phone shows it done. The code goes into a notification, so the person
     * stays in Wireless debugging's pairing dialog (leaving it closes it).
     */
    private void showHelperSetup() {
        if (isFinishing()) return;
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(homeLook ? 0xFF161D2A : theme.cardSolid, dp(26), cardEdge));
        panel.addView(text(getString(R.string.ph_setup_title), 19, title, 700));
        TextView body = text(getString(R.string.ph_setup_body), 14.5f, theme.textFaint, 400);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, boxParams(dp(10)));
        helperSteps = new LinearLayout(this);
        helperSteps.setOrientation(LinearLayout.VERTICAL);
        panel.addView(helperSteps, boxParams(dp(8)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(dialogButton(getString(R.string.ph_setup_close), 0x00000000, theme.text, d::dismiss));
        panel.addView(buttons, boxParams(dp(18)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        d.setContentView(scroll);
        d.setOnDismissListener(x -> {
            helperDialog = null;
            helperSteps = null;
            helperStepsDrawn = "";
        });
        codeAsked = false;
        helperDialog = d;
        helperStepsDrawn = "";
        refreshHelperSetup();
        d.show();
        if (d.getWindow() != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            d.getWindow().setLayout(Math.min(screen - dp(32), dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** The steps as the phone has them now; the pairing notification offered once both switches are on. */
    private void refreshHelperSetup() {
        if (helperDialog == null || helperSteps == null) return;
        boolean dev = PhoneHelperStart.devOptionsOn(this);
        boolean wireless = Boolean.TRUE.equals(PhoneHelperStart.wirelessOn(this));
        boolean wifi = PhoneHelperStart.wifiNetwork(this) >= 0;
        boolean paired = PhoneHelperStart.paired(this) && !PhoneHelperStart.UNPAIRED.equals(PhoneHelperStart.state(this));
        String pairing = PhoneHelperStart.pairing();
        boolean notifications = notificationsAllowed();
        String drawn = dev + "|" + wireless + "|" + wifi + "|" + paired + "|" + pairing + "|" + notifications;
        if (drawn.equals(helperStepsDrawn)) return;
        helperStepsDrawn = drawn;
        helperSteps.removeAllViews();
        // 1. Developer options.
        helperSteps.addView(statusLine(dev ? green : AMBER, getString(dev ? R.string.ph_setup_dev_done : R.string.ph_setup_dev)),
                boxParams(dp(10)));
        if (!dev) helperSteps.addView(link(getString(R.string.ph_setup_open_about), this::openAboutPhone), linkParams());
        // 2. Wireless debugging, which needs Wi-Fi.
        if (!wifi && !wireless) {
            helperSteps.addView(statusLine(AMBER, getString(R.string.ph_setup_wifi)), boxParams(dp(10)));
        } else {
            helperSteps.addView(statusLine(wireless ? green : AMBER,
                    getString(wireless ? R.string.ph_setup_wireless_done : R.string.ph_setup_wireless)), boxParams(dp(10)));
            if (dev && !wireless) {
                helperSteps.addView(link(getString(R.string.ph_set_open_dev), this::openDeveloperOptions), linkParams());
            }
        }
        // 3. The pairing code, typed into the notification.
        if (paired) {
            helperSteps.addView(statusLine(green, getString(R.string.ph_setup_pair_done)), boxParams(dp(10)));
        } else if ("working".equals(pairing)) {
            helperSteps.addView(statusLine(sky, getString(R.string.ph_setup_pair_working)), boxParams(dp(10)));
        } else {
            helperSteps.addView(statusLine(dev && wireless ? AMBER : theme.textFaint, getString(R.string.ph_setup_pair)),
                    boxParams(dp(10)));
            if (!notifications) {
                helperSteps.addView(statusLine(AMBER, getString(R.string.ph_setup_notifs)), boxParams(dp(8)));
                helperSteps.addView(link(getString(R.string.ph_setup_notifs_allow), this::askNotifications), linkParams());
            } else if (dev && wireless) {
                // Posted once, as soon as it can be used (both switches on and
                // notifications allowed); again on request (swiped away).
                if (!codeAsked) {
                    codeAsked = true;
                    PhoneHelperStart.askCode(this, getString(R.string.ph_pair_text));
                }
                helperSteps.addView(link(getString(R.string.ph_setup_pair_go),
                        () -> PhoneHelperStart.askCode(this, getString(R.string.ph_pair_text))), linkParams());
            }
        }
        DexFonts.applyTo(this, helperSteps);
        DexCursors.decorate(helperSteps);
    }

    /** The pairing notification went up for this showing of the setup. */
    private boolean codeAsked;

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    /** Android's own question for notifications (Android 13), or the app's notification settings once it said no. */
    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean askedNotifications;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ASK_NOTIFICATIONS) ticks.post(this::refreshHelperSetup);
    }

    /** About phone, where Build number turns Developer options on. */
    private void openAboutPhone() {
        try {
            startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /** Developer options, at Wireless debugging where the phone's Settings marks it. */
    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Before Android's accessibility settings, what the control service does
     * and does not do, and a choice: Google Play's rule for an app that is not
     * an accessibility tool, and the honest thing anyway.
     */
    private void confirmControl() {
        if (isFinishing()) return;
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(homeLook ? 0xFF161D2A : theme.cardSolid, dp(26), cardEdge));
        panel.addView(text(getString(R.string.pr_disclose_title), 19, title, 700));
        // The Cloudflare link's sentence only where the link exists: the Play
        // build has no cloudflared (linuxapp/build.gradle), so nothing there
        // passes through Cloudflare.
        String said = getString(R.string.pr_disclose_body);
        if (PhoneTunnel.available(this)) {
            said += (said.endsWith("。") ? "" : " ") + getString(R.string.pr_disclose_cloudflare);
        }
        TextView body = text(said, 14.5f, theme.textFaint, 400);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, boxParams(dp(10)));
        TextView next = text(getString(R.string.pr_disclose_next), 14.5f, theme.text, 500);
        next.setLineSpacing(0, 1.2f);
        panel.addView(next, boxParams(dp(12)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(dialogButton(getString(R.string.pr_disclose_no), 0x00000000, theme.text, d::dismiss));
        TextView yes = dialogButton(getString(R.string.pr_disclose_yes), green, 0xFF0B0F17, () -> {
            d.dismiss();
            openAccessibility();
        });
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.setMarginStart(dp(8));
        buttons.addView(yes, yesLp);
        panel.addView(buttons, boxParams(dp(22)));
        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        d.show();
        if (d.getWindow() != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            d.getWindow().setLayout(Math.min(screen - dp(32), dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * Before Android's question for the sound ("record audio"), what it is
     * for and what it is not: the sound apps play, never the microphone.
     */
    private void confirmSound() {
        if (isFinishing()) return;
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(homeLook ? 0xFF161D2A : theme.cardSolid, dp(26), cardEdge));
        panel.addView(text(getString(R.string.pr_sound_disclose_title), 19, title, 700));
        TextView body = text(getString(R.string.pr_sound_disclose_body), 14.5f, theme.textFaint, 400);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, boxParams(dp(10)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(dialogButton(getString(R.string.pr_disclose_no), 0x00000000, theme.text, d::dismiss));
        TextView yes = dialogButton(getString(R.string.pr_disclose_yes), green, 0xFF0B0F17, () -> {
            d.dismiss();
            try {
                startActivity(new Intent(this, PhoneSoundActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.setMarginStart(dp(8));
        buttons.addView(yes, yesLp);
        panel.addView(buttons, boxParams(dp(22)));
        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        d.show();
        if (d.getWindow() != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            d.getWindow().setLayout(Math.min(screen - dp(32), dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * Android's page for this service where the phone lets an app open it
     * (most keep that page for app stores: a Galaxy S25 refuses it), and
     * otherwise the accessibility list with the way to Remote desktop marked.
     */
    private void openAccessibility() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, PhoneRemote.controlComponent(this).flattenToString()));
                return;
            } catch (Exception ignored) {
                // refused or missing: the list
            }
        }
        try {
            startActivity(PhoneRemote.accessibilitySettings(this));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Open in a browser ──

    /** @return the setting's new value */
    private boolean toggleBrowser() {
        LinuxBrowser b = LinuxService.browser(this);
        boolean on = !b.isOn();
        if (on) {
            LinuxService.browserOn(this);
        } else {
            LinuxService.browserOff(this);
        }
        // The steps open under the switch, or close: drawn after the tap's ripple.
        ticks.post(() -> drawBrowser(true));
        return on;
    }

    /**
     * The card: the switch, and with it on, what to do at the computer. Drawn
     * again only when something on it changed: the server's state line, the
     * phone's addresses, the code, how many browsers are remembered.
     */
    private void drawBrowser(boolean force) {
        if (browserCard == null) return;
        LinuxBrowser b = LinuxService.browser(this);
        boolean on = b.isOn();
        String state = on ? b.state() : "";
        java.util.List<LinuxBrowser.Address> where = on ? LinuxBrowser.addresses()
                : java.util.Collections.<LinuxBrowser.Address>emptyList();
        String code = on ? b.code() : "";
        boolean usedUp = on && b.codeUsedUp();
        int remembered = on ? b.tokenCount() : 0;
        if (on && !usedUp && !where.isEmpty() && !state.startsWith("failed")) {
            // The code works while this shows it, and for ten minutes after:
            // kept open twice a minute while the page is in front. Only then:
            // a card that shows no code (used up, no network, nothing
            // listening) opens nothing.
            long now = System.currentTimeMillis();
            if (force || now - pairingOpenedAt > 30_000) {
                b.openPairing(now);
                pairingOpenedAt = now;
            }
        }
        String drawn = on + "|" + state + "|" + where + "|" + code + "|" + usedUp + "|" + remembered;
        if (!force && drawn.equals(browserDrawn)) return;
        browserDrawn = drawn;

        browserCard.removeAllViews();
        browserCard.addView(switchRow(R.drawable.hm_ic_browser, sky, getString(R.string.bw_set_title),
                getString(R.string.bw_set_sub), on, true, this::toggleBrowser));
        if (on) {
            View rule = new View(this);
            rule.setBackgroundColor(ruleColor);
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            ruleLp.setMarginStart(dp(56));
            browserCard.addView(rule, ruleLp);
            browserCard.addView(browserSteps(state, where, code, usedUp, remembered), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        DexFonts.applyTo(this, browserCard);
        DexCursors.decorate(browserCard);
    }

    /** The two steps, the state of things, and the browsers remembered. */
    private View browserSteps(String state, java.util.List<LinuxBrowser.Address> where, String code,
                              boolean usedUp, int remembered) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Under the row's words, past its icon, as the boot card's hint sits.
        panel.setPaddingRelative(dp(56), dp(14), 0, dp(8));

        String[] parts = state.split(" ", 3);
        String kind = parts[0];
        int port = LinuxBrowser.PORT;
        if ((kind.equals("listening") || kind.equals("watching")) && parts.length > 1) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // the usual one, above
            }
        }

        if (where.isEmpty()) {
            panel.addView(statusLine(AMBER, getString(R.string.bw_set_no_network)));
            panel.addView(awayNote(), boxParams(dp(10)));
            return panel;
        }
        if (kind.equals("failed")) {
            // Nothing of ours is listening. An address and a code here would
            // send a person, and the code, to whatever holds the port.
            panel.addView(statusLine(AMBER, failedWhy(state, parts)));
            addRemembered(panel, remembered);
            return panel;
        }

        panel.addView(stepTitle(1, getString(R.string.bw_set_step_open)));
        LinuxBrowser.Address first = where.get(0);
        panel.addView(addressBox(first.typed(port), first.url(port)), boxParams(dp(10)));
        TextView net = text(kindName(first.kind), 12.5f, theme.textFaint, 500);
        net.setPaddingRelative(dp(4), dp(6), 0, 0);
        panel.addView(net);
        // A second network (the hotspot beside the Wi-Fi, say): the same
        // Linux at its own address, smaller, for a computer that is there.
        for (int i = 1; i < where.size(); i++) {
            LinuxBrowser.Address a = where.get(i);
            TextView also = text(kindName(a.kind) + "  " + a.typed(port), 13, theme.textFaint, 500);
            also.setTypeface(Typeface.MONOSPACE);
            also.setTextDirection(View.TEXT_DIRECTION_LTR);
            also.setPaddingRelative(dp(4), dp(8), 0, dp(2));
            also.setBackground(pressable(0x00000000, dp(8), 0));
            final String url = a.url(port);
            also.setOnClickListener(v -> copyAddress(url));
            asButton(also, getString(R.string.bw_set_copy) + " " + url);
            panel.addView(also);
        }
        panel.addView(awayNote(), boxParams(dp(10)));

        panel.addView(stepTitle(2, getString(R.string.bw_set_step_code)), boxParams(dp(20)));
        addCode(panel, code, usedUp);

        String why;
        int dot;
        if (kind.equals("watching") && parts.length > 2) {
            String[] w = parts[2].split(" ", 3); // number, address, what it is
            String who = w.length == 3 ? w[2] + " · " + w[1] : parts[2];
            why = getString(R.string.bw_set_watching, who);
            dot = green;
        } else if (kind.equals("listening")) {
            why = getString(R.string.bw_set_waiting);
            dot = theme.textFaint;
        } else {
            why = getString(R.string.bw_set_starting);
            dot = theme.textFaint;
        }
        LinearLayout status = statusLine(dot, why);
        panel.addView(status, boxParams(dp(18)));

        TextView warning = text(getString(R.string.bw_set_warning), 13, theme.textFaint, 400);
        warning.setLineSpacing(0, 1.15f);
        panel.addView(warning, boxParams(dp(12)));
        // A managed laptop's browser may offer no way past the warning at
        // all: the plain address is the way on, and what it costs is said.
        TextView plain = text(getString(R.string.bw_set_plain), 13, theme.textFaint, 400);
        plain.setLineSpacing(0, 1.15f);
        panel.addView(plain, boxParams(dp(8)));
        TextView quality = text(getString(R.string.bw_set_quality), 13, theme.textFaint, 400);
        quality.setLineSpacing(0, 1.15f);
        panel.addView(quality, boxParams(dp(8)));
        addRemembered(panel, remembered);
        return panel;
    }

    /**
     * The code a browser types to sign in, as the Wi-Fi steps and From
     * anywhere both show it (the same door, the same code): the digits and
     * how long they work, or, once too many wrong codes were tried, the way to
     * a new one.
     */
    private void addCode(LinearLayout panel, String code, boolean usedUp) {
        if (usedUp) {
            // Ten wrong codes were tried against it: it opens nothing now, and
            // the way on is a new one, made here, where only the owner is.
            TextView gone = text(getString(R.string.bw_set_used_up), 13.5f, theme.text, 500);
            gone.setLineSpacing(0, 1.12f);
            panel.addView(gone, boxParams(dp(8)));
            TextView fresh = text(getString(R.string.bw_set_new_code), 14, linkColor, 600);
            fresh.setGravity(Gravity.CENTER_VERTICAL);
            fresh.setMinHeight(dp(44));
            fresh.setPadding(dp(10), 0, dp(10), 0);
            fresh.setBackground(pressable(0x00000000, dp(12), 0));
            asButton(fresh, getString(R.string.bw_set_new_code));
            fresh.setOnClickListener(v -> {
                LinuxService.browser(this).rotateCode();
                // Both cards show the one code.
                drawBrowser(true);
                drawPhone(true);
            });
            LinearLayout.LayoutParams freshLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            freshLp.setMarginStart(-dp(10));
            panel.addView(fresh, freshLp);
        } else {
            TextView digits = text(code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code,
                    30, title, 700);
            digits.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            digits.setLetterSpacing(0.08f);
            digits.setTextDirection(View.TEXT_DIRECTION_LTR);
            digits.setPaddingRelative(dp(2), dp(4), 0, 0);
            // Read out as digits, not as "four hundred and eighty-two thousand".
            digits.setContentDescription(getString(R.string.bw_set_step_code) + ": " + code.replace("", " ").trim());
            panel.addView(digits);
            TextView window = text(getString(R.string.bw_set_code_hint), 12.5f, theme.textFaint, 400);
            window.setLineSpacing(0, 1.12f);
            panel.addView(window, boxParams(dp(4)));
        }
    }

    /**
     * For a computer away from the phone's Wi-Fi: Tailscale on both. Its
     * network then shows on this card as a VPN address (LinuxBrowser.kindOf:
     * tun, tailscale), and the door lets its computers in
     * (LinuxBrowser.isLocalPeer: 100.64/10 and fc00::/7).
     */
    private TextView awayNote() {
        TextView away = text(getString(R.string.bw_set_away), 13, theme.textFaint, 400);
        away.setLineSpacing(0, 1.15f);
        return away;
    }

    /** The server's own words for why it is not listening: "failed port 7443", "failed <why>". */
    private String failedWhy(String state, String[] parts) {
        if (parts.length > 2 && parts[1].equals("port")) {
            int taken = LinuxBrowser.PORT;
            try {
                taken = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException ignored) {
                // the usual one
            }
            return getString(R.string.bw_set_port, taken);
        }
        return getString(R.string.bw_set_failed, parts.length > 1 ? state.substring(7) : "");
    }

    /** How many browsers are let in, and the way to sign them all out. */
    private void addRemembered(LinearLayout panel, int remembered) {
        if (remembered > 0) {
            // The count, and under it the way to undo it: stacked, so that no
            // language's words squeeze the other's.
            TextView count = text(getString(R.string.bw_set_browsers, remembered), 13, theme.textFaint, 500);
            panel.addView(count, boxParams(dp(16)));
            TextView out = text(getString(R.string.bw_set_sign_out), 14, linkColor, 600);
            out.setGravity(Gravity.CENTER_VERTICAL);
            out.setMinHeight(dp(44));
            out.setPadding(dp(10), 0, dp(10), 0);
            out.setBackground(pressable(0x00000000, dp(12), 0));
            asButton(out, getString(R.string.bw_set_sign_out));
            out.setOnClickListener(v -> confirmSignOut());
            LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            outLp.setMarginStart(-dp(10));
            panel.addView(out, outLp);
        }
    }

    private LinearLayout.LayoutParams boxParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    /** "1  On the computer, open": a numbered step, the number in a small round chip. */
    private View stepTitle(int n, String words) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = text(String.valueOf(n), 12, sky, 700);
        chip.setGravity(Gravity.CENTER);
        chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        chip.setBackground(fill((0x26 << 24) | (sky & 0x00FFFFFF), dp(11), 0));
        // The words are the step; the number is how it looks.
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chip, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(words, 14, theme.text, 600);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.setMarginStart(dp(10));
        row.addView(t, tLp);
        return row;
    }

    /**
     * The address, large enough to read across a desk and always on one line,
     * smaller only where it has to be; the whole box copies the full https
     * link, which a paste then opens with no stop at the plain http one.
     */
    private View addressBox(String typed, String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPaddingRelative(dp(14), dp(10), dp(8), dp(10));
        int fillColor = homeLook || theme.dark ? 0x12FFFFFF : 0x0D000000;
        box.setBackground(pressable(fillColor, dp(14), cardEdge));
        TextView t = text(typed, 19, title, 600);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        // An address reads left to right in every language.
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setTextIsSelectable(false);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(12, 19, 1, TypedValue.COMPLEX_UNIT_SP);
        box.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView copy = new ImageView(this);
        copy.setImageDrawable(tinted(R.drawable.hm_ic_copy, sky));
        copy.setScaleType(ImageView.ScaleType.CENTER);
        copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(copy, new LinearLayout.LayoutParams(dp(36), dp(36)));
        asButton(box, getString(R.string.bw_set_copy) + " " + url);
        box.setOnClickListener(v -> copyAddress(url));
        return box;
    }

    private LinearLayout statusLine(int dotColor, String words) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(dotColor);
        dot.setBackground(d);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMarginEnd(dp(10));
        row.addView(dot, dotLp);
        TextView t = text(words, 13.5f, theme.text, 500);
        t.setLineSpacing(0, 1.12f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Read out when it changes: a browser connecting is news.
        row.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return row;
    }

    private String kindName(String kind) {
        switch (kind) {
            case "wifi": return getString(R.string.wb_if_wifi);
            case "hotspot": return getString(R.string.wb_if_hotspot);
            case "wired": return getString(R.string.wb_if_wired);
            case "vpn": return getString(R.string.bw_if_vpn);
            default: return getString(R.string.bw_if_other);
        }
    }

    private void copyAddress(String url) {
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText("LinuxOnDroid", url));
        // Android 13 and later say so themselves.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, getString(R.string.bw_set_copied), Toast.LENGTH_SHORT).show();
        }
    }

    /** Every browser out, and a new code: asked first, since each has to be let in again. */
    private void confirmSignOut() {
        if (isFinishing()) return;
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(20), dp(16));
        panel.setBackground(fill(homeLook ? 0xFF161D2A : theme.cardSolid, dp(26), cardEdge));
        panel.addView(text(getString(R.string.bw_set_sign_out_title), 19, title, 700));
        // The same browsers open the phone's own screen while Remote desktop
        // is on, and lose that too: said, with or without Linux's switch.
        boolean phoneToo = PhoneRemote.offered(this) && PhoneRemote.isEnabled(this);
        int what = !phoneToo ? R.string.bw_set_sign_out_body
                : LinuxService.browser(this).isOn() ? R.string.pr_sign_out_both : R.string.pr_sign_out_phone;
        TextView body = text(getString(what), 14.5f, theme.textFaint, 400);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, boxParams(dp(10)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(dialogButton(getString(R.string.ln_close_cancel), 0x00000000, theme.text, d::dismiss));
        TextView yes = dialogButton(getString(R.string.bw_set_sign_out_ok), 0xFFE5484D, 0xFFFFFFFF, () -> {
            d.dismiss();
            // The browser watching now too, not only the tokens on disk.
            LinuxService.browserSignOut(this);
            Toast.makeText(this, getString(R.string.bw_set_signed_out), Toast.LENGTH_SHORT).show();
            drawBrowser(true);
        });
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.setMarginStart(dp(8));
        buttons.addView(yes, yesLp);
        panel.addView(buttons, boxParams(dp(22)));
        DexFonts.applyTo(this, panel);
        DexCursors.decorate(panel);
        d.setContentView(panel);
        d.show();
        if (d.getWindow() != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            d.getWindow().setLayout(Math.min(screen - dp(32), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private TextView dialogButton(String label, int fillColor, int textColor, Runnable onTap) {
        TextView b = text(label, 14.5f, textColor, 600);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setMinWidth(dp(88));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(pressable(fillColor, dp(14), 0));
        asButton(b, label);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    /** This app's page in the phone's settings: battery, and on some phones, starting by itself. */
    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception e) {
            DexLog.warn("linux", "settings: cannot open App info", e);
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, getString(R.string.ln_cannot_open), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Email me when an address changes ──

    /**
     * The card for {@link EmailNotice}: the switch, and with it on, the email
     * service (four with their servers filled in, or another), the address
     * to send to, the account to send from and its app password, the sign-in
     * links' switch, a test, and what the emails are doing. The form is built
     * when the page fills and kept while the page is in front: drawn again
     * while someone types, it would take the cursor away. Only the lines under
     * it follow the job, once a second. What is typed is kept when the page
     * goes, and before a test; the password sealed (EmailSecret), and never
     * shown again.
     */
    private final class EmailCard {
        final LinearLayout card = card();
        private android.widget.EditText to;
        private android.widget.EditText user;
        private android.widget.EditText password;
        private android.widget.EditText host;
        private android.widget.EditText port;
        private String provider = "gmail";
        private EmailSmtp.Security security = EmailSmtp.Security.SSL;
        private LinearLayout status;
        private LinearLayout testLine;
        private TextView testButton;
        private String statusDrawn = "";
        private boolean testing;

        EmailCard() {
            draw();
        }

        private Context ctx() {
            return LinuxSettingsActivity.this;
        }

        void draw() {
            card.removeAllViews();
            to = user = password = host = port = null;
            status = testLine = null;
            testButton = null;
            statusDrawn = "";
            EmailNotice.Prefs s = EmailNotice.Prefs.load(ctx());
            provider = s.provider;
            security = s.security;
            card.addView(switchRow(R.drawable.hm_ic_mail, AMBER, getString(R.string.em_set_title),
                    getString(R.string.em_set_sub), s.on, true, this::toggle));
            if (s.on) {
                LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                ruleLp.setMarginStart(dp(56));
                card.addView(rule(), ruleLp);
                card.addView(form(s), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(rule(), ruleLp);
                card.addView(switchRow(R.drawable.hm_ic_shield, green, getString(R.string.em_set_links),
                        getString(R.string.em_set_links_sub), s.links, true, this::toggleLinks));
                card.addView(rule(), ruleLp);
                card.addView(actions(), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            DexFonts.applyTo(LinuxSettingsActivity.this, card);
            DexCursors.decorate(card);
            tick(true);
        }

        private View rule() {
            View line = new View(ctx());
            line.setBackgroundColor(ruleColor);
            return line;
        }

        /** The service, the addresses, the password, and the server for another service. */
        private View form(EmailNotice.Prefs s) {
            LinearLayout f = new LinearLayout(ctx());
            f.setOrientation(LinearLayout.VERTICAL);
            f.setPaddingRelative(dp(56), dp(12), 0, dp(14));
            f.addView(text(getString(R.string.em_set_service), 13, theme.textFaint, 600));
            f.addView(providers(), boxParams(dp(8)));
            EmailNotice.Provider p = EmailNotice.provider(provider);
            TextView note = text(getString(p.note), 12.5f, theme.textFaint, 400);
            note.setLineSpacing(0, 1.15f);
            f.addView(note, boxParams(dp(8)));
            to = field(f, R.string.em_set_to, s.to, R.string.em_set_to_hint,
                    android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            user = field(f, R.string.em_set_user, s.user, R.string.em_set_user_hint,
                    android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            boolean saved = EmailNotice.hasPassword(ctx());
            password = field(f, R.string.em_set_password, "", 0,
                    android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // Android 15 and later hide a whole window from screen sharing
            // while a password field is on it (sensitive content protection):
            // seen through Remote desktop, in the person's own browser, this
            // whole Settings page was black (2026-11-24). The dots stay, so
            // the password still cannot be read over a shoulder or the link;
            // the page is the person's own screen, shown over their own link,
            // as WebActivity's password field already says.
            if (Build.VERSION.SDK_INT >= 35) {
                password.setContentSensitivity(View.CONTENT_SENSITIVITY_NOT_SENSITIVE);
            }
            // A saved password shows as dots that are not it, in the page's own
            // font (Android gives a password field a typewriter's); what that
            // means is said under the field, where it has room.
            password.setTypeface(weight(400));
            if (saved) {
                password.setHint("••••••••••••");
                TextView keep = text(getString(R.string.em_set_password_saved), 12.5f, theme.textFaint, 400);
                keep.setLineSpacing(0, 1.12f);
                f.addView(keep, boxParams(dp(6)));
            }
            // Typed, the password stays in this field only: not in the page's saved state.
            password.setSaveEnabled(false);
            if ("other".equals(provider)) {
                host = field(f, R.string.em_set_server, s.host, R.string.em_set_server_hint,
                        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
                port = field(f, R.string.em_set_port, s.port > 0 ? String.valueOf(s.port) : "", 0,
                        android.text.InputType.TYPE_CLASS_NUMBER);
                f.addView(text(getString(R.string.em_set_security), 13, theme.textFaint, 600), boxParams(dp(14)));
                f.addView(securities(), boxParams(dp(8)));
            } else {
                TextView server = text(getString(R.string.em_set_server_line, p.host, p.port, securityName(p.security)),
                        12.5f, theme.textFaint, 500);
                f.addView(server, boxParams(dp(14)));
            }
            return f;
        }

        private String securityName(EmailSmtp.Security sec) {
            return getString(sec == EmailSmtp.Security.STARTTLS ? R.string.em_sec_starttls : R.string.em_sec_ssl);
        }

        /** A label, and a field under it, one line, as the page's own boxes look. */
        private android.widget.EditText field(LinearLayout into, int label, String value, int hint, int type) {
            TextView l = text(getString(label), 13, theme.textFaint, 600);
            into.addView(l, boxParams(dp(14)));
            android.widget.EditText e = new android.widget.EditText(ctx());
            e.setId(View.generateViewId());
            l.setLabelFor(e.getId());
            e.setText(value);
            if (hint != 0) e.setHint(getString(hint));
            e.setInputType(type);
            e.setSingleLine(true);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            e.setTextColor(theme.text);
            e.setHintTextColor(homeLook || theme.dark ? 0x80C9D1DC : 0x80000000);
            // An address, a server and a password read left to right in every language.
            e.setTextDirection(View.TEXT_DIRECTION_LTR);
            e.setPadding(dp(12), dp(10), dp(12), dp(10));
            e.setBackground(fill(homeLook || theme.dark ? 0x12FFFFFF : 0x0D000000, dp(12), cardEdge));
            e.setContentDescription(getString(label));
            into.addView(e, boxParams(dp(6)));
            return e;
        }

        private View providers() {
            Flow row = new Flow();
            for (EmailNotice.Provider p : EmailNotice.PROVIDERS) {
                row.addView(chip(getString(p.name), p.id.equals(provider), () -> {
                    save();
                    EmailNotice.Prefs s = EmailNotice.Prefs.load(ctx());
                    s.provider = p.id;
                    if (!"other".equals(p.id)) {
                        s.host = p.host;
                        s.port = p.port;
                        s.security = p.security;
                    }
                    s.save(ctx());
                    EmailNotice.settingsChanged(ctx());
                    ticks.post(this::draw);
                }));
            }
            return row;
        }

        private View securities() {
            Flow row = new Flow();
            for (EmailSmtp.Security sec : EmailSmtp.Security.values()) {
                row.addView(chip(securityName(sec), sec == security, () -> {
                    security = sec;
                    // The usual port for each, when the port is one of the two.
                    if (port != null) {
                        String typed = port.getText().toString().trim();
                        if (typed.isEmpty() || typed.equals("465") || typed.equals("587")) {
                            port.setText(sec == EmailSmtp.Security.SSL ? "465" : "587");
                        }
                    }
                    save();
                    ticks.post(this::draw);
                }));
            }
            return row;
        }

        /**
         * Chips side by side, onto the next line where a line is full, so
         * every choice and the one chosen are in sight at any width and in
         * any language; laid from the right in a right-to-left language.
         */
        private final class Flow extends ViewGroup {
            private final int gap = dp(8);

            Flow() {
                super(LinuxSettingsActivity.this);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = MeasureSpec.getSize(widthSpec);
                boolean bounded = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
                int x = 0;
                int y = 0;
                int line = 0;
                int widest = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View c = getChildAt(i);
                    c.measure(MeasureSpec.makeMeasureSpec(max, bounded ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    if (bounded && x > 0 && x + c.getMeasuredWidth() > max) {
                        x = 0;
                        y += line + gap;
                        line = 0;
                    }
                    x += c.getMeasuredWidth() + gap;
                    line = Math.max(line, c.getMeasuredHeight());
                    widest = Math.max(widest, x - gap);
                }
                setMeasuredDimension(bounded ? max : widest, y + line);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int width = r - l;
                boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                int x = 0;
                int y = 0;
                int line = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View c = getChildAt(i);
                    int w = c.getMeasuredWidth();
                    int h = c.getMeasuredHeight();
                    if (x > 0 && x + w > width) {
                        x = 0;
                        y += line + gap;
                        line = 0;
                    }
                    int left = rtl ? width - x - w : x;
                    c.layout(left, y, left + w, y + h);
                    x += w + gap;
                    line = Math.max(line, h);
                }
            }
        }

        private TextView chip(String label, boolean chosen, Runnable onTap) {
            TextView c = text(label, 14, chosen ? 0xFF0B0F17 : theme.text, 600);
            c.setGravity(Gravity.CENTER);
            c.setMinHeight(dp(40));
            c.setPadding(dp(14), 0, dp(14), 0);
            c.setBackground(pressable(chosen ? sky : 0x00000000, dp(20), chosen ? 0 : cardEdge));
            asButton(c, label);
            c.setSelected(chosen);
            c.setOnClickListener(v -> onTap.run());
            return c;
        }

        /** The test, its answer, what the emails are doing, and the way to forget it all. */
        private View actions() {
            LinearLayout a = new LinearLayout(ctx());
            a.setOrientation(LinearLayout.VERTICAL);
            a.setPaddingRelative(dp(56), dp(14), 0, dp(10));
            testButton = dialogButton(getString(R.string.em_set_test), green, 0xFF0B0F17, this::test);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            a.addView(testButton, lp);
            testLine = new LinearLayout(ctx());
            testLine.setOrientation(LinearLayout.VERTICAL);
            a.addView(testLine, boxParams(0));
            status = new LinearLayout(ctx());
            status.setOrientation(LinearLayout.VERTICAL);
            a.addView(status, boxParams(dp(12)));
            TextView forget = link(getString(R.string.em_set_forget), () -> {
                EmailNotice.forget(ctx());
                Toast.makeText(ctx(), getString(R.string.em_set_forgotten), Toast.LENGTH_SHORT).show();
                ticks.post(this::draw);
            });
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fLp.setMarginStart(-dp(10));
            fLp.topMargin = dp(8);
            a.addView(forget, fLp);
            return a;
        }

        /** The switch. Off: what was typed is kept for next time, and no link or email is left going. */
        private boolean toggle() {
            boolean on = !EmailNotice.Prefs.load(ctx()).on;
            if (!on) save();
            EmailNotice.setOn(ctx(), on);
            ticks.post(this::draw);
            return on;
        }

        private boolean toggleLinks() {
            save();
            EmailNotice.Prefs s = EmailNotice.Prefs.load(ctx());
            s.links = !s.links;
            s.save(ctx());
            // Off: the links of the last email stop working at once.
            if (!s.links) LinuxService.browser(ctx()).dropLinks("off");
            return s.links;
        }

        /**
         * What is typed, kept: the password sealed, the rest in the settings.
         * A field left empty keeps what was there (the password most of all).
         *
         * @return false when the phone could not keep the password safe
         */
        boolean save() {
            if (to == null) return true;
            EmailNotice.Prefs s = EmailNotice.Prefs.load(ctx());
            String before = s.fingerprint(ctx());
            s.provider = provider;
            s.to = to.getText().toString().trim();
            s.user = user.getText().toString().trim();
            if ("other".equals(provider) && host != null && port != null) {
                s.host = host.getText().toString().trim();
                try {
                    s.port = Integer.parseInt(port.getText().toString().trim());
                } catch (NumberFormatException e) {
                    s.port = 0;
                }
                s.security = security;
            } else {
                EmailNotice.Provider p = EmailNotice.provider(provider);
                s.host = p.host;
                s.port = p.port;
                s.security = p.security;
            }
            s.save(ctx());
            String typed = password.getText().toString();
            boolean kept = EmailNotice.savePassword(ctx(), typed);
            if (!typed.isEmpty() && kept) {
                password.setText("");
                password.setHint("••••••••••••");
            }
            if (!EmailNotice.Prefs.load(ctx()).fingerprint(ctx()).equals(before)) EmailNotice.settingsChanged(ctx());
            return kept;
        }

        private void test() {
            if (testing || testLine == null) return;
            testLine.removeAllViews();
            if (!save()) {
                testLine.addView(statusLine(AMBER, getString(R.string.em_set_password_error)), boxParams(dp(12)));
                return;
            }
            int missing = EmailNotice.Prefs.load(ctx()).missing(ctx());
            if (missing != 0) {
                testLine.addView(statusLine(AMBER, getString(missing)), boxParams(dp(12)));
                return;
            }
            testing = true;
            testButton.setEnabled(false);
            testButton.setAlpha(0.6f);
            testLine.addView(statusLine(theme.textFaint, getString(R.string.em_set_testing)), boxParams(dp(12)));
            final Context app = getApplicationContext();
            final String sentTo = EmailNotice.Prefs.load(ctx()).to.trim();
            new Thread(() -> {
                EmailNotice.Outcome out;
                try {
                    out = EmailNotice.sendTest(app);
                } catch (RuntimeException e) {
                    DexLog.warn("email", "the test email: " + e);
                    out = new EmailNotice.Outcome(false, EmailSmtp.Fault.PROTOCOL, null, "", 0);
                }
                final EmailNotice.Outcome done = out;
                ticks.post(() -> tested(done, sentTo));
            }, "email-test").start();
        }

        private void tested(EmailNotice.Outcome out, String sentTo) {
            testing = false;
            if (testButton == null || testLine == null || isDestroyed()) return;
            testButton.setEnabled(true);
            testButton.setAlpha(1f);
            testLine.removeAllViews();
            if (out.ok) {
                testLine.addView(statusLine(green, getString(R.string.em_set_test_ok, sentTo)), boxParams(dp(12)));
            } else {
                int words = "MISSING".equals(out.local) ? EmailNotice.Prefs.load(ctx()).missing(ctx())
                        : EmailNotice.faultWords(out.local != null ? out.local : out.fault.name());
                if (words == 0) words = R.string.em_fault_protocol;
                testLine.addView(statusLine(AMBER, getString(R.string.em_set_not_sent, getString(words))), boxParams(dp(12)));
                if (!out.said.isEmpty()) testLine.addView(said(out.said), boxParams(dp(6)));
            }
            tick(true);
        }

        private TextView said(String what) {
            TextView t = text(getString(R.string.em_set_said, what), 12, theme.textFaint, 400);
            t.setPaddingRelative(dp(18), 0, 0, 0);
            return t;
        }

        /** The lines under the form, from the job's state: drawn again only when they change. */
        void tick(boolean force) {
            if (status == null) return;
            EmailNotice.Prefs s = EmailNotice.Prefs.load(ctx());
            EmailNotice.State st = EmailNotice.state(ctx());
            int missing = s.missing(ctx());
            boolean tested = s.tested(ctx());
            String drawn = missing + "|" + tested + "|" + st.sentAt + "|" + st.sentHead + "|" + st.fault + "|" + st.blocked
                    + "|" + st.retryAt + "|" + st.changeAt + "|" + st.said + "|" + s.to;
            if (!force && drawn.equals(statusDrawn)) return;
            statusDrawn = drawn;
            status.removeAllViews();
            if (missing != 0) {
                status.addView(statusLine(AMBER, getString(missing)));
                return;
            }
            if (!tested) {
                status.addView(statusLine(AMBER, getString(R.string.em_state_need_test)));
                return;
            }
            long now = EmailNotice.clock.getAsLong();
            if (!st.fault.isEmpty() && (st.blocked || st.retryAt > now)) {
                String why = getString(EmailNotice.faultWords(st.fault));
                status.addView(statusLine(AMBER, st.blocked ? getString(R.string.em_set_not_sent, why)
                        : getString(R.string.em_state_retry, why, android.text.format.DateUtils.formatDateTime(
                        ctx(), st.retryAt, android.text.format.DateUtils.FORMAT_SHOW_TIME))));
                if (!st.said.isEmpty()) status.addView(said(st.said), boxParams(dp(6)));
            } else if (st.changeAt > 0) {
                status.addView(statusLine(sky, getString(R.string.em_state_pending)));
            } else {
                status.addView(statusLine(green, getString(R.string.em_state_on, s.to.trim())));
            }
            if (st.sentAt > 0) {
                TextView last = text(getString(R.string.em_state_last, android.text.format.DateUtils.formatDateTime(ctx(),
                        st.sentAt, android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                                | android.text.format.DateUtils.FORMAT_ABBREV_MONTH), st.sentHead), 12.5f, theme.textFaint, 400);
                last.setPaddingRelative(dp(18), 0, 0, 0);
                status.addView(last, boxParams(dp(6)));
            }
        }
    }

    // ── building blocks, in the home screen's style ──

    /** Flips a setting and returns its new value. */
    private interface Flip {
        boolean flip();
    }

    private void header(String words) {
        TextView h = text(words, 12, theme.textFaint, 600);
        h.setAllCaps(true);
        h.setLetterSpacing(0.09f);
        if (Build.VERSION.SDK_INT >= 28) h.setAccessibilityHeading(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(content.getChildCount() == 0 ? 18 : 30);
        lp.bottomMargin = dp(12);
        lp.setMarginStart(dp(6));
        content.addView(h, lp);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(6), dp(16), dp(6));
        card.setBackground(fill(cardFill, dp(24), cardEdge));
        return card;
    }

    /**
     * An icon, a name, a line under it and a switch: the whole row is the
     * target, and says so to accessibility services as a switch. A row whose
     * setting cannot be had here is greyed and does nothing.
     */
    private View switchRow(int icon, int tint, String name, String sub, boolean on,
                           boolean enabled, Flip flip) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setMinimumHeight(dp(72));
        row.addView(iconChip(icon, tint));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(name, 15.5f, theme.text, 600));
        TextView line = text(sub, 13, theme.textFaint, 400);
        line.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lineLp.topMargin = dp(2);
        texts.addView(line, lineLp);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMarginStart(dp(14));
        textsLp.setMarginEnd(dp(12));
        row.addView(texts, textsLp);

        final Switch sw = new Switch(this);
        sw.setChecked(on);
        // The row is the target; the switch only shows the state.
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{lightSwitches ? tint : 0xFFFFFFFF, thumbOff}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{tint, trackOff}));
        row.addView(sw);

        row.setContentDescription(name + ", " + sub);
        row.setFocusable(enabled);
        row.setEnabled(enabled);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(sw.isChecked());
                info.setEnabled(host.isEnabled());
            }
        });
        if (enabled) {
            row.setBackground(pressable(0x00000000, dp(16), 0));
            row.setOnClickListener(v -> sw.setChecked(flip.flip()));
        } else {
            row.setAlpha(0.55f);
            sw.setEnabled(false);
        }
        return row;
    }

    private View iconChip(int res, int tint) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(tinted(res, tint));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackground(fill((0x26 << 24) | (tint & 0x00FFFFFF), dp(13), 0));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return iv;
    }

    private TextView text(String words, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(words);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(weight(weight));
        // The language's direction, as on the home screen (DexLocale.followLayout).
        DexLocale.followLayout(t);
        return t;
    }

    private static Typeface weight(int w) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, w, false);
        if (w >= 600) return Typeface.DEFAULT_BOLD;
        if (w >= 500) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return Typeface.DEFAULT;
    }

    /** Reported as a button, with its label, however it is built. */
    private static void asButton(View v, String label) {
        v.setContentDescription(label);
        v.setFocusable(true);
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    private Drawable tinted(int res, int color) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    private GradientDrawable fill(int color, float radius, int edge) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (edge != 0) d.setStroke(Math.max(1, dp(1)), edge);
        return d;
    }

    private Drawable pressable(int color, float radius, int edge) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        // A white ripple vanishes on a light desktop's card; the theme's own shows.
        return new RippleDrawable(ColorStateList.valueOf(homeLook || theme.dark ? 0x24FFFFFF : 0x1F000000),
                fill(color, radius, edge), mask);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
