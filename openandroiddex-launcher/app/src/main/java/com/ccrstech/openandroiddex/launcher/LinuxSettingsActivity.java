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
            ticks.postDelayed(this, 1000);
        }
    };
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
        if (browserCard != null) ticks.postDelayed(browserTick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticks.removeCallbacks(browserTick);
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

        // LinuxOnDroid's alone: a desktop on a computer already has the
        // desktop app, which shows Linux there over the cable or the tunnel.
        if (homeLook) {
            header(getString(R.string.bw_set_header));
            browserCard = card();
            content.addView(browserCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            drawBrowser(true);
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

        header(getString(R.string.hm_set_graphics));
        boolean gpu = LinuxGpu.available();
        LinearLayout graphics = card();
        graphics.addView(switchRow(R.drawable.hm_ic_gpu, violet, getString(R.string.ln_gpu_menu),
                getString(gpu ? R.string.hm_gpu_sub : R.string.hm_set_gpu_none),
                gpu && LinuxGpu.enabled(this), gpu, this::toggleGpu));
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

        panel.addView(stepTitle(2, getString(R.string.bw_set_step_code)), boxParams(dp(20)));
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
                drawBrowser(true);
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
        TextView body = text(getString(R.string.bw_set_sign_out_body), 14.5f, theme.textFaint, 400);
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
