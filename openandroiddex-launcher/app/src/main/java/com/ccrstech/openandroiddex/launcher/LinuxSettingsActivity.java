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
        lightSwitches = !dark;
        thumbOff = dark ? 0xFFC9D1DC : 0xFF9AA3AF;
        trackOff = dark ? 0x40FFFFFF : 0x33000000;
    }

    /** "Settings" in LinuxOnDroid; "Linux settings" on a desktop, which has Settings of its own. */
    private String pageName() {
        return getString(homeLook ? R.string.settings_label : R.string.ln_settings_title);
    }

    /**
     * Back, and the page's name. A window on the desktop display has Back on
     * its own bar (CaptionService), so there the page shows only its name.
     */
    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(56));
        boolean withBack = homeLook || onPhoneDisplay();
        if (withBack) {
            ImageView back = new ImageView(this);
            back.setImageDrawable(tinted(R.drawable.hm_ic_back, title));
            back.setScaleType(ImageView.ScaleType.CENTER);
            back.setBackground(pressable(0x00000000, dp(22), 0));
            asButton(back, getString(R.string.lx_back));
            back.setOnClickListener(v -> finish());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            // The arrow's stroke sits where the home screen's icon sits.
            backLp.setMarginStart(-dp(10));
            bar.addView(back, backLp);
        }
        TextView name = text(pageName(), 20, title, 700);
        name.setLetterSpacing(-0.01f);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(dp(6));
        bar.addView(name, nameLp);
        DexFonts.applyTo(this, bar);
        return bar;
    }

    /** On the phone's own screen, not a desktop's: a window there has no bar of ours. */
    private boolean onPhoneDisplay() {
        try {
            android.view.Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay()
                    : getWindowManager().getDefaultDisplay();
            return d == null || d.getDisplayId() == android.view.Display.DEFAULT_DISPLAY;
        } catch (Exception e) {
            return true;
        }
    }

    /** The sections, as things stand now. */
    private void fill() {
        content.removeAllViews();

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
