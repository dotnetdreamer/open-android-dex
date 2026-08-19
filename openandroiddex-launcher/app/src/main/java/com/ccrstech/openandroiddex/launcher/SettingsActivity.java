package com.ccrstech.openandroiddex.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The desktop's Settings window: a sidebar of sections on the left, the
 * selected section's cards on the right — the shape every desktop settings app
 * has, and the shape the commercial DeX uses too.
 *
 * Everything here writes through {@link DexPrefs}, which broadcasts the change;
 * the launcher, the desktop grid and the window captions rebuild themselves on
 * that broadcast, so a toggle flipped here is visible on the desktop behind
 * this window before the finger leaves the mouse button. Nothing is "applied"
 * with an OK button and nothing needs a restart, with one honest exception:
 * text already on screen keeps its old language until its window is rebuilt.
 *
 * The UI is built in code rather than XML for the same reason the rest of the
 * launcher is — it has to be re-created at whatever density `wm density` last
 * put on the desktop display, and a layout inflated from resources brings the
 * phone's density with it.
 */
public class SettingsActivity extends Activity {

    /** Kept as the historical name so an upgrade does not lose the choice. */
    static final String KEY_DENSITY = DexPrefs.KEY_DENSITY;

    private static final String SEC_DISPLAY = "display";
    private static final String SEC_PERF = "performance";
    private static final String SEC_LANGUAGE = "language";
    private static final String SEC_WALLPAPER = "wallpaper";
    private static final String SEC_WINDOWS = "windows";
    private static final String SEC_MOUSE = "mouse";
    private static final String SEC_TOUCHPAD = "touchpad";
    private static final String SEC_STREAM = "stream";
    private static final String SEC_CLIPBOARD = "clipboard";
    private static final String SEC_ABOUT = "about";

    /** Below this the window cannot hold sidebar and content side by side. */
    private static final int NARROW_DP = 660;

    private static final String REPO_URL = "https://github.com/dotnetdreamer/Android-Dex";
    private static final String RELEASES_URL = REPO_URL + "/releases/latest";
    private static final String WMD_URL = REPO_URL + "/tree/main/openandroiddex-wmd";

    /** Relative UI scale, over a 160dpi-at-1080p desktop baseline. */
    private static final float[] SIZE_FACTORS = {0.75f, 0.875f, 1f, 1.25f, 1.5f};

    private DexTheme theme;
    private int[] presetDpis;
    private int densityIndex = -1;

    private String section = SEC_DISPLAY;
    /** Section to return to when the header's back chevron is used. */
    private String previousSection;
    private boolean narrow;
    /** Narrow layout only: false while the nav list is covering the content. */
    private boolean showingContent;
    /** True while the language list is expanded past the first few entries. */
    private boolean allLanguages;

    private LinearLayout navList;
    private FrameLayout contentHost;
    private LinearLayout sidebar;
    private EditText searchField;
    private String query = "";

    private final List<Nav> navs = new ArrayList<>();

    /** One sidebar entry, and everything its section can be found by. */
    private static final class Nav {
        final String id;
        final int title;
        final int subtitle;
        final String glyph;
        final int tint;
        /** Extra words the search box matches on. */
        final int[] keywords;

        Nav(String id, int title, int subtitle, String glyph, int tint, int[] keywords) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.glyph = glyph;
            this.tint = tint;
            this.keywords = keywords;
        }
    }

    /**
     * Settings that change how this window itself is painted. Anything else
     * (wallpaper, window sizes, display density) only touches the desktop
     * behind us, and re-creating for those would throw away the user's place
     * in the list for no visible gain.
     */
    private static final List<String> SELF_AFFECTING = java.util.Arrays.asList(
            DexPrefs.KEY_THEME, DexPrefs.KEY_DARK, DexPrefs.KEY_PAPER_TEXTURE,
            DexPrefs.KEY_GRAIN, DexPrefs.KEY_GLASS, DexPrefs.KEY_TRANSPARENCY,
            DexPrefs.KEY_ROUNDING, DexPrefs.KEY_LANGUAGE, DexPrefs.KEY_FONT,
            DexPrefs.KEY_CURSOR_STYLE, DexPrefs.KEY_CURSOR_SIZE, DexPrefs.KEY_CURSOR_COLOR,
            DexPrefs.KEY_CURSOR_OUTLINE, DexPrefs.KEY_CURSOR_SHADOW,
            // suppresses glass and grain, so it repaints this window too
            DexPrefs.KEY_PERF, "*");

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String key = intent.getStringExtra(DexPrefs.EXTRA_KEY);
            // The cheapest correct answer for "the palette moved" is to build
            // the window again; onSaveInstanceState carries the open section
            // across, so it comes back where the user left it.
            if (key != null && SELF_AFFECTING.contains(key)) recreate();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Our own package has no taskbar icon, so this window's only presence
        // there is the tile OwnWindows drives.
        OwnWindows.opened(this);
        theme = DexTheme.of(this);
        if (savedInstanceState != null) {
            section = savedInstanceState.getString("section", SEC_DISPLAY);
            allLanguages = savedInstanceState.getBoolean("allLanguages", false);
            showingContent = savedInstanceState.getBoolean("showingContent", false);
        }
        presetDpis = computePresets();
        densityIndex = selectedDensityIndex();
        buildNavs();
        buildUi();
        IntentFilter filter = new IntentFilter(DexPrefs.ACTION_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OwnWindows.closed(this);
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("section", section);
        out.putBoolean("allLanguages", allLanguages);
        out.putBoolean("showingContent", showingContent);
    }

    /** The window can be resized by its caption — re-lay out when it crosses the split. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (narrow != (newConfig.screenWidthDp < NARROW_DP)) buildUi();
    }

    // ── measurements ──

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private String s(int res) {
        return getString(res);
    }

    // ── drawables shared by every row ──

    /** A painted surface — grained in Paper mode. See {@link DexTheme#surface}. */
    private Drawable roundedFill(int color, float radiusDp) {
        return theme.surface(color, dp(theme.radius(radiusDp)));
    }

    /** The same shape without grain, for a button's transient layers. */
    private GradientDrawable plainFill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(theme.radius(radiusDp)));
        return d;
    }

    /** Rest / hover / ripple — this desktop is mouse-driven, so hover matters. */
    private Drawable tapBackground(int restColor, int hoverColor, float radiusDp) {
        StateListDrawable content = new StateListDrawable();
        content.addState(new int[]{android.R.attr.state_hovered}, plainFill(hoverColor, radiusDp));
        content.addState(new int[0], plainFill(restColor, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(theme.ripple), content,
                plainFill(0xFFFFFFFF, radiusDp));
    }

    // ── shell ──

    private void buildNavs() {
        navs.clear();
        navs.add(new Nav(SEC_DISPLAY, R.string.st_nav_display, R.string.st_nav_display_sub,
                "🎨", 0xFFa78bfa, new int[]{R.string.st_theme, R.string.st_theme_paper,
                R.string.st_paper_texture, R.string.st_grain, R.string.st_glass,
                R.string.st_blur, R.string.st_transparency, R.string.st_rounding,
                R.string.st_display_size}));
        navs.add(new Nav(SEC_PERF, R.string.st_nav_perf, R.string.st_nav_perf_sub,
                "⚡", 0xFFfacc15, new int[]{R.string.st_perf, R.string.st_perf_anim,
                R.string.st_perf_effects, R.string.st_perf_stream}));
        navs.add(new Nav(SEC_LANGUAGE, R.string.st_nav_language, R.string.st_nav_language_sub,
                "🌐", 0xFF2dd4bf, new int[]{R.string.st_language, R.string.st_font_style}));
        navs.add(new Nav(SEC_WALLPAPER, R.string.st_nav_wallpaper, R.string.st_nav_wallpaper_sub,
                "🖼", 0xFF60a5fa, new int[]{R.string.st_choose_wallpaper,
                R.string.st_darkness}));
        navs.add(new Nav(SEC_WINDOWS, R.string.st_nav_windows, R.string.st_nav_windows_sub,
                "🪟", 0xFFfb923c, new int[]{R.string.st_launch_mode,
                R.string.st_window_size, R.string.st_icon_size}));
        navs.add(new Nav(SEC_MOUSE, R.string.st_nav_mouse, R.string.st_nav_mouse_sub,
                "➤", 0xFFf472b6, new int[]{R.string.st_cursor_style,
                R.string.st_cursor_size, R.string.st_cursor_color, R.string.st_cursor_outline,
                R.string.st_cursor_speed, R.string.st_cursor_render}));
        navs.add(new Nav(SEC_TOUCHPAD, R.string.st_nav_touchpad, R.string.st_nav_touchpad_sub,
                "⌗", 0xFF38bdf8, new int[]{R.string.st_touchpad,
                R.string.st_gesture_3up, R.string.st_gesture_3down, R.string.st_gesture_3left,
                R.string.st_gesture_3right, R.string.st_gesture_3tap}));
        navs.add(new Nav(SEC_STREAM, R.string.st_nav_stream, R.string.st_nav_stream_sub,
                "🎞", 0xFFf59e0b, new int[]{R.string.st_resolution, R.string.st_codec,
                R.string.st_encoder, R.string.st_bitrate, R.string.st_fps,
                R.string.st_audio_forward}));
        navs.add(new Nav(SEC_CLIPBOARD, R.string.st_nav_clipboard, R.string.st_nav_clipboard_sub,
                "📋", 0xFF4ade80, new int[]{R.string.st_clip_sync, R.string.st_clip_current,
                R.string.st_clip_history}));
        navs.add(new Nav(SEC_ABOUT, R.string.st_nav_about, R.string.st_nav_about_sub,
                "ℹ", 0xFF94a3b8, new int[]{R.string.st_updates, R.string.st_maintenance,
                R.string.st_factory_reset, R.string.st_reset_home}));
    }

    private void buildUi() {
        narrow = getResources().getConfiguration().screenWidthDp < NARROW_DP;
        if (!narrow) showingContent = true;

        LinearLayout root = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) finish();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(theme.surface(theme.windowBg(), 0f));

        sidebar = buildSidebar();
        root.addView(sidebar, new LinearLayout.LayoutParams(
                narrow ? ViewGroup.LayoutParams.MATCH_PARENT : dp(268),
                ViewGroup.LayoutParams.MATCH_PARENT));

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(
                narrow ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                ViewGroup.LayoutParams.MATCH_PARENT, narrow ? 0 : 1f));

        setContentView(root);
        applyNarrowVisibility();
        showSection(section, false);
        DexFonts.applyTo(this, root);
        DexCursors.decorate(root);
    }

    private void applyNarrowVisibility() {
        if (!narrow) {
            sidebar.setVisibility(View.VISIBLE);
            contentHost.setVisibility(View.VISIBLE);
            return;
        }
        sidebar.setVisibility(showingContent ? View.GONE : View.VISIBLE);
        contentHost.setVisibility(showingContent ? View.VISIBLE : View.GONE);
    }

    private LinearLayout buildSidebar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(theme.surface(theme.panel(), 0f));
        bar.setPadding(dp(14), dp(18), dp(14), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), 0, dp(4), dp(16));

        TextView wordmark = new TextView(this);
        wordmark.setText(s(R.string.st_title));
        wordmark.setTextColor(theme.text);
        wordmark.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(21));
        wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        head.addView(wordmark, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView searchIcon = new TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        searchIcon.setGravity(Gravity.CENTER);
        searchIcon.setContentDescription(s(R.string.st_search));
        searchIcon.setBackground(tapBackground(0x00000000, theme.hover, 9));
        searchIcon.setOnClickListener(v -> {
            searchField.requestFocus();
            searchField.setSelection(searchField.getText().length());
        });
        head.addView(searchIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        bar.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(navList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        searchField = new EditText(this);
        searchField.setHint(s(R.string.st_search));
        searchField.setHintTextColor(theme.textFaint);
        searchField.setTextColor(theme.text);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        searchField.setSingleLine(true);
        searchField.setBackground(roundedFill(theme.field, 22));
        searchField.setPadding(dp(18), dp(11), dp(18), dp(11));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.getDefault());
                refreshNav();
            }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp(12);
        bar.addView(searchField, searchLp);

        refreshNav();
        return bar;
    }

    /** Rebuild the nav list, honouring the search box. */
    private void refreshNav() {
        if (navList == null) return;
        navList.removeAllViews();
        int shown = 0;
        for (Nav nav : navs) {
            if (!matches(nav)) continue;
            navList.addView(navRow(nav), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(s(R.string.st_no_results));
            empty.setTextColor(theme.textFaint);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
            empty.setPadding(dp(12), dp(14), dp(12), dp(14));
            navList.addView(empty);
        }
        DexFonts.applyTo(this, navList);
        DexCursors.decorate(navList);
    }

    private boolean matches(Nav nav) {
        if (query.isEmpty()) return true;
        if (contains(s(nav.title)) || contains(s(nav.subtitle))) return true;
        for (int keyword : nav.keywords) {
            if (contains(s(keyword))) return true;
        }
        return false;
    }

    private boolean contains(String haystack) {
        return haystack.toLowerCase(Locale.getDefault()).contains(query);
    }

    private View navRow(Nav nav) {
        boolean active = nav.id.equals(section);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        row.setBackground(active
                ? roundedFill(blend(nav.tint, 0x2E), 14)
                : tapBackground(0x00000000, theme.hover, 14));
        row.setOnClickListener(v -> {
            showingContent = true;
            showSection(nav.id, true);
            applyNarrowVisibility();
        });

        TextView icon = new TextView(this);
        icon.setText(nav.glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundedFill(blend(nav.tint, 0x33), 12));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(s(nav.title));
        title.setTextColor(active ? theme.text : theme.textDim);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText(s(nav.subtitle));
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** A tint at the given alpha, over whatever the surface is. */
    private int blend(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    // ── content pane ──

    private void showSection(String id, boolean remember) {
        if (remember && !id.equals(section)) previousSection = section;
        section = id;
        refreshNav();
        contentHost.removeAllViews();

        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(18), dp(24), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(theme.textDim);
        back.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24));
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(s(R.string.st_back));
        back.setBackground(tapBackground(0x00000000, theme.hover, 10));
        back.setOnClickListener(v -> goBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        backLp.rightMargin = dp(8);
        header.addView(back, backLp);

        TextView title = new TextView(this);
        title.setText(s(navFor(id).title));
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(19));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pane.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(24), dp(28));

        switch (id) {
            case SEC_PERF:
                buildPerformanceSection(body);
                break;
            case SEC_LANGUAGE:
                buildLanguageSection(body);
                break;
            case SEC_WALLPAPER:
                buildWallpaperSection(body);
                break;
            case SEC_WINDOWS:
                buildWindowsSection(body);
                break;
            case SEC_MOUSE:
                buildMouseSection(body);
                break;
            case SEC_TOUCHPAD:
                buildTouchpadSection(body);
                break;
            case SEC_STREAM:
                buildStreamSection(body);
                break;
            case SEC_CLIPBOARD:
                buildClipboardSection(body);
                break;
            case SEC_ABOUT:
                buildAboutSection(body);
                break;
            default:
                buildDisplaySection(body);
        }

        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        contentHost.addView(pane, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        DexFonts.applyTo(this, pane);
        DexCursors.decorate(pane);
    }

    private void goBack() {
        if (narrow && showingContent) {
            showingContent = false;
            applyNarrowVisibility();
            return;
        }
        if (previousSection != null && !previousSection.equals(section)) {
            String target = previousSection;
            previousSection = null;
            showSection(target, false);
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private Nav navFor(String id) {
        for (Nav nav : navs) {
            if (nav.id.equals(id)) return nav;
        }
        return navs.get(0);
    }

    // ── building blocks ──

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedFill(theme.card(), 16));
        card.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    /** Small all-caps label that names a group, like the tray's own headers. */
    private void groupHeader(LinearLayout parent, String text) {
        TextView header = new TextView(this);
        header.setText(text.toUpperCase(Locale.getDefault()));
        header.setTextColor(theme.textFaint);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setLetterSpacing(0.08f);
        header.setPadding(dp(12), dp(14), dp(12), dp(2));
        parent.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** The same header, centred inside a card (the DISPLAY SIZE look). */
    private void cardHeader(LinearLayout card, String text) {
        TextView header = new TextView(this);
        header.setText(text.toUpperCase(Locale.getDefault()));
        header.setTextColor(theme.textFaint);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setLetterSpacing(0.08f);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, dp(10), 0, dp(8));
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void note(LinearLayout parent, String text) {
        TextView note = new TextView(this);
        note.setText("ⓘ  " + text);
        note.setTextColor(theme.textFaint);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        note.setPadding(dp(14), dp(8), dp(12), 0);
        parent.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Title + explanation on the left, an On/Off word and a switch on the
     * right. {@code accentTitle} marks the row that owns the card (Dark mode,
     * Glass effects) the way the reference UI does.
     */
    private View toggleRow(LinearLayout parent, String title, String subtitle,
                           boolean initial, boolean accentTitle,
                           java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(tapBackground(0x00000000, theme.hover, 12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(accentTitle ? theme.accent : theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(label);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(theme.textFaint);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(this);
        state.setText(initial ? s(R.string.st_on) : s(R.string.st_off));
        state.setTextColor(initial ? theme.accent : theme.textFaint);
        state.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateLp.rightMargin = dp(10);
        row.addView(state, stateLp);

        Toggle toggle = new Toggle(this, initial);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(46), dp(26)));

        View.OnClickListener flip = v -> {
            boolean next = !toggle.isOn();
            toggle.setOn(next, true);
            state.setText(next ? s(R.string.st_on) : s(R.string.st_off));
            state.setTextColor(next ? theme.accent : theme.textFaint);
            onChange.accept(next);
        };
        row.setOnClickListener(flip);
        toggle.setOnClickListener(flip);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * Label, live percentage, and a draggable track.
     *
     * The value is committed when the finger lifts, not on every pixel: these
     * sliders repaint the whole desktop shell (and this window with it), and
     * doing that ~100 times per drag would stutter and make the drag itself
     * hard to control. The percentage still follows the thumb, so the drag
     * never feels dead.
     */
    private SeekBar sliderRow(LinearLayout parent, String label, int value,
                              java.util.function.IntConsumer onCommit) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(14), dp(6), dp(14), dp(2));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(theme.textDim);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pct = new TextView(this);
        pct.setText(value + "%");
        pct.setTextColor(theme.accent);
        pct.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        pct.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(pct);
        wrap.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(value);
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        bar.setThumbTintList(ColorStateList.valueOf(theme.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        bar.setPadding(dp(2), dp(6), dp(2), dp(6));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pct.setText(progress + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onCommit.accept(seekBar.getProgress());
            }
        });
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    /**
     * One choice in a group: optional glyph, title, optional explanation, and
     * a ring on the right that fills when it is the current one. The whole
     * group is rebuilt on selection so exactly one ring can ever be lit.
     */
    private void choiceRow(LinearLayout parent, String glyph, String title, String subtitle,
                           boolean selected, Runnable onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(tapBackground(0x00000000, theme.hover, 12));
        row.setOnClickListener(v -> onPick.run());

        if (glyph != null) {
            TextView icon = new TextView(this);
            icon.setText(glyph);
            icon.setTextColor(selected ? theme.accent : theme.textDim);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
            icon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(26), dp(26));
            glyphLp.rightMargin = dp(10);
            row.addView(icon, glyphLp);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(selected ? theme.accent : theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(label);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(theme.textFaint);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View ring = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0x00000000);
        d.setStroke(dp(selected ? 6 : 2), selected ? theme.accent : theme.textFaint);
        ring.setBackground(d);
        row.addView(ring, new LinearLayout.LayoutParams(dp(20), dp(20)));

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Icon, title, explanation, and a chevron — a row that opens something. */
    private void actionRow(LinearLayout parent, String glyph, int tint, String title,
                           String subtitle, Runnable onClick) {
        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        icon.setGravity(Gravity.CENTER);
        actionRow(parent, icon, tint, title, subtitle, onClick);
    }

    /**
     * The same row with a real drawable. For actions whose meaning is carried by
     * a shape the platform already draws — power-off above all — where a font
     * glyph is both less recognisable and at the mercy of the device's fonts.
     */
    private void actionRow(LinearLayout parent, int iconRes, int tint, String title,
                           String subtitle, Runnable onClick) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(tint);
        int pad = dp(7);
        icon.setPadding(pad, pad, pad, pad);
        actionRow(parent, icon, tint, title, subtitle, onClick);
    }

    private void actionRow(LinearLayout parent, View icon, int tint, String title,
                           String subtitle, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(16), dp(11));
        row.setBackground(tapBackground(0x00000000, theme.hover, 12));
        row.setOnClickListener(v -> onClick.run());

        icon.setBackground(roundedFill(blend(tint, 0x33), 11));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(label);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(theme.textFaint);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(theme.textFaint);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17));
        row.addView(chevron);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ── section: Display & UI ──

    private void buildDisplaySection(LinearLayout body) {
        LinearLayout themeCard = card(body);
        cardHeader(themeCard, s(R.string.st_theme));
        String[][] themes = {
                {DexPrefs.THEME_DARK, s(R.string.st_theme_dark), s(R.string.st_theme_dark_sub),
                        "◐"},
                {DexPrefs.THEME_LIGHT, s(R.string.st_theme_light), s(R.string.st_theme_light_sub),
                        "☀"},
                {DexPrefs.THEME_PAPER, s(R.string.st_theme_paper), s(R.string.st_theme_paper_sub),
                        "▤"},
        };
        for (String[] entry : themes) {
            choiceRow(themeCard, entry[3], entry[1], entry[2], entry[0].equals(theme.mode),
                    () -> DexPrefs.put(this, DexPrefs.KEY_THEME, entry[0]));
        }

        // Paper is the only theme with a finish to choose, so its controls
        // appear with it rather than sitting greyed out the rest of the time.
        if (theme.paper) buildPaperCard(body);

        LinearLayout glassCard = card(body);
        boolean glassOn = DexPrefs.getBool(this, DexPrefs.KEY_GLASS, DexPrefs.DEF_GLASS);
        // no explicit rebuild: KEY_GLASS repaints this window, so the receiver
        // above re-creates it (and the sliders come back enabled/disabled)
        toggleRow(glassCard, s(R.string.st_glass), s(R.string.st_glass_sub), glassOn, true,
                on -> DexPrefs.put(this, DexPrefs.KEY_GLASS, on));

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.VERTICAL);
        sliders.setAlpha(glassOn ? 1f : 0.4f);
        glassCard.addView(sliders, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar blur = sliderRow(sliders, s(R.string.st_blur),
                DexPrefs.getInt(this, DexPrefs.KEY_BLUR, DexPrefs.DEF_BLUR),
                v -> DexPrefs.put(this, DexPrefs.KEY_BLUR, v));
        SeekBar alpha = sliderRow(sliders, s(R.string.st_transparency),
                DexPrefs.getInt(this, DexPrefs.KEY_TRANSPARENCY, DexPrefs.DEF_TRANSPARENCY),
                v -> DexPrefs.put(this, DexPrefs.KEY_TRANSPARENCY, v));
        SeekBar round = sliderRow(sliders, s(R.string.st_rounding),
                DexPrefs.getInt(this, DexPrefs.KEY_ROUNDING, DexPrefs.DEF_ROUNDING),
                v -> DexPrefs.put(this, DexPrefs.KEY_ROUNDING, v));
        blur.setEnabled(glassOn);
        alpha.setEnabled(glassOn);
        round.setEnabled(glassOn);

        if (glassOn && !Glass.blurSupported(this)) {
            note(glassCard, s(R.string.st_blur_unsupported));
        }

        LinearLayout sizeCard = card(body);
        cardHeader(sizeCard, s(R.string.st_display_size));
        String[] labels = {s(R.string.st_size_xs), s(R.string.st_size_s), s(R.string.st_size_m),
                s(R.string.st_size_l), s(R.string.st_size_xl)};
        String[] glyphs = {"⛶", "🔍", "🖵", "🔎", "⤡"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            choiceRow(sizeCard, glyphs[i], labels[i], null, i == densityIndex,
                    () -> applyDensity(index));
        }
        note(body, s(R.string.st_display_size_note));
    }

    /**
     * Paper mode's own controls: which grain, and how much of it.
     *
     * Each texture is previewed at the size it is actually felt — a swatch of
     * the real tile, at the current intensity, over the real card colour. A
     * name alone ("Weave", "Cold Press") tells nobody what they are choosing.
     */
    private void buildPaperCard(LinearLayout body) {
        LinearLayout paperCard = card(body);
        cardHeader(paperCard, s(R.string.st_paper_texture));

        String current = DexPrefs.getString(this, DexPrefs.KEY_PAPER_TEXTURE,
                DexPrefs.DEF_PAPER_TEXTURE);
        int[] names = {R.string.st_paper_matte, R.string.st_paper_weave,
                R.string.st_paper_press, R.string.st_paper_vellum};
        int[] details = {R.string.st_paper_matte_sub, R.string.st_paper_weave_sub,
                R.string.st_paper_press_sub, R.string.st_paper_vellum_sub};

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(6));
        for (int i = 0; i < PaperTexture.ALL.length; i++) {
            final String id = PaperTexture.ALL[i];
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(paperSwatch(id, s(names[i]), s(details[i]), id.equals(current)), lp);
        }
        paperCard.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sliderRow(paperCard, s(R.string.st_grain),
                DexPrefs.getInt(this, DexPrefs.KEY_GRAIN, DexPrefs.DEF_GRAIN),
                v -> DexPrefs.put(this, DexPrefs.KEY_GRAIN, v));
        note(body, s(R.string.st_paper_note));
    }

    /** One texture tile: a real swatch of the grain, its name, and its feel. */
    private View paperSwatch(String id, String name, String detail, boolean selected) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        tile.setBackground(selected
                ? plainFill(blend(theme.accent, 0x2E), 14)
                : tapBackground(0x00000000, theme.hover, 14));
        tile.setOnClickListener(v -> DexPrefs.put(this, DexPrefs.KEY_PAPER_TEXTURE, id));

        View swatch = new View(this);
        // the swatch shows THIS texture, not the selected one, and at the
        // intensity currently dialled in — so the row is a comparison
        swatch.setBackground(PaperTexture.surface(theme.cardSolid, dp(theme.radius(12)),
                theme.grainAlpha, id));
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        tile.addView(swatch, swatchLp);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(selected ? theme.accent : theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(8), dp(2), 0);
        tile.addView(label);

        TextView sub = new TextView(this);
        sub.setText(detail);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(sub);
        return tile;
    }

    // ── section: Language & Font ──

    private void buildLanguageSection(LinearLayout body) {
        LinearLayout langCard = card(body);
        cardHeader(langCard, s(R.string.st_language));
        String currentTag = DexPrefs.getString(this, DexPrefs.KEY_LANGUAGE, "");
        int limit = allLanguages ? DexLocale.LANGUAGES.length
                : Math.min(DexLocale.PRIMARY, DexLocale.LANGUAGES.length);
        for (int i = 0; i < limit; i++) {
            DexLocale.Lang lang = DexLocale.LANGUAGES[i];
            boolean selected = lang.tag.equals(currentTag);
            String title = lang.tag.isEmpty() ? s(R.string.st_lang_auto) : lang.native_;
            String sub = lang.tag.isEmpty() ? s(R.string.st_lang_auto_sub) : lang.english;
            choiceRow(langCard, lang.region, title, sub, selected, () -> {
                if (lang.tag.equals(DexPrefs.getString(this, DexPrefs.KEY_LANGUAGE, ""))) return;
                DexPrefs.put(this, DexPrefs.KEY_LANGUAGE, lang.tag);
                // our own receiver recreates this window; the launcher rebuilds
                // on the same broadcast
            });
        }
        if (!allLanguages && DexLocale.LANGUAGES.length > DexLocale.PRIMARY) {
            int more = DexLocale.LANGUAGES.length - DexLocale.PRIMARY;
            TextView expand = new TextView(this);
            expand.setText("⌄   " + getString(R.string.st_lang_more, more));
            expand.setTextColor(theme.accent);
            expand.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
            expand.setGravity(Gravity.CENTER_VERTICAL);
            expand.setPadding(dp(16), dp(12), dp(14), dp(12));
            expand.setBackground(tapBackground(0x00000000, theme.hover, 12));
            expand.setOnClickListener(v -> {
                allLanguages = true;
                showSection(SEC_LANGUAGE, false);
            });
            langCard.addView(expand, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        note(body, s(R.string.st_language_note));

        LinearLayout fontCard = card(body);
        cardHeader(fontCard, s(R.string.st_font_style));
        String currentFont = DexPrefs.getString(this, DexPrefs.KEY_FONT, DexPrefs.DEF_FONT);
        for (DexFonts.Option option : DexFonts.available()) {
            boolean selected = option.key.equals(currentFont);
            int before = fontCard.getChildCount();
            choiceRow(fontCard, null, option.name, option.detail, selected,
                    () -> DexPrefs.put(this, DexPrefs.KEY_FONT, option.key));
            // show each choice IN its own face — the only preview that means anything
            Typeface face = DexFonts.typefaceOf(option);
            if (face != null && fontCard.getChildCount() > before) {
                DexFonts.applyTo(fontCard.getChildAt(before), face);
            }
        }
    }

    // ── section: Wallpaper ──

    private void buildWallpaperSection(LinearLayout body) {
        String current = DexPrefs.wallpaper(this);
        int dim = DexPrefs.getInt(this, DexPrefs.KEY_WALL_DIM, DexPrefs.DEF_WALL_DIM);

        LinearLayout pickCard = card(body);
        cardHeader(pickCard, s(R.string.st_choose_wallpaper));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(2), dp(8), dp(6));
        LinearLayout row = null;
        for (int i = 0; i < Wallpapers.ALL.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Wallpapers.Spec spec = Wallpapers.ALL[i];
            LinearLayout.LayoutParams cellLp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cellLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(wallpaperTile(spec, spec.id.equals(current), dim), cellLp);
        }
        // keep the last row's tiles the same width as a full row's
        if (Wallpapers.ALL.length % 3 != 0 && row != null) {
            for (int i = Wallpapers.ALL.length % 3; i < 3; i++) {
                LinearLayout.LayoutParams fillerLp =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                fillerLp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(new View(this), fillerLp);
            }
        }
        pickCard.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout dimCard = card(body);
        LinearLayout dimHead = new LinearLayout(this);
        dimHead.setOrientation(LinearLayout.HORIZONTAL);
        dimHead.setGravity(Gravity.CENTER_VERTICAL);
        dimHead.setPadding(dp(14), dp(12), dp(14), dp(2));
        TextView dimIcon = new TextView(this);
        dimIcon.setText("◑");
        dimIcon.setTextColor(theme.accent);
        dimIcon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        LinearLayout.LayoutParams dimIconLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dimIconLp.rightMargin = dp(10);
        dimHead.addView(dimIcon, dimIconLp);
        LinearLayout dimTexts = new LinearLayout(this);
        dimTexts.setOrientation(LinearLayout.VERTICAL);
        TextView dimTitle = new TextView(this);
        dimTitle.setText(s(R.string.st_darkness));
        dimTitle.setTextColor(theme.text);
        dimTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        dimTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dimTexts.addView(dimTitle);
        TextView dimSub = new TextView(this);
        dimSub.setText(s(R.string.st_darkness_sub));
        dimSub.setTextColor(theme.textFaint);
        dimSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        dimTexts.addView(dimSub);
        dimHead.addView(dimTexts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dimCard.addView(dimHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageDrawable(Wallpapers.drawable(current, dim));
        preview.setClipToOutline(true);
        preview.setBackground(roundedFill(0xFF000000, 12));

        // 0–80: past that the wallpaper is gone and the slider stops meaning anything
        SeekBar dimBar = new SeekBar(this);
        dimBar.setMax(80);
        dimBar.setProgress(Math.min(80, dim));
        dimBar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        dimBar.setThumbTintList(ColorStateList.valueOf(theme.accent));
        dimBar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        dimBar.setPadding(dp(16), dp(10), dp(16), dp(4));
        TextView dimValue = new TextView(this);
        dimValue.setText(dim + "%");
        dimValue.setTextColor(theme.accent);
        dimValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        dimValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dimHead.addView(dimValue);

        dimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                dimValue.setText(progress + "%");
                preview.setImageDrawable(Wallpapers.drawable(
                        DexPrefs.wallpaper(SettingsActivity.this), progress));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // written on release, not per pixel: every write repaints the
                // desktop behind this window
                DexPrefs.put(SettingsActivity.this, DexPrefs.KEY_WALL_DIM, seekBar.getProgress());
            }
        });
        dimCard.addView(dimBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(dp(18), 0, dp(18), dp(8));
        TextView low = new TextView(this);
        low.setText(s(R.string.st_darkness_none));
        low.setTextColor(theme.textFaint);
        low.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        legend.addView(low, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView high = new TextView(this);
        high.setText(s(R.string.st_darkness_max));
        high.setTextColor(theme.textFaint);
        high.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        legend.addView(high);
        dimCard.addView(legend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        previewLp.setMargins(dp(14), dp(2), dp(14), dp(10));
        dimCard.addView(preview, previewLp);
    }

    private View wallpaperTile(Wallpapers.Spec spec, boolean selected, int dim) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(5), dp(5), dp(5), dp(8));
        tile.setBackground(selected
                ? roundedFill(blend(theme.accent, 0x2E), 14)
                : tapBackground(0x00000000, theme.hover, 14));
        tile.setOnClickListener(v -> {
            DexPrefs.put(this, DexPrefs.KEY_WALLPAPER, spec.id);
            showSection(SEC_WALLPAPER, false);
        });

        FrameLayout frame = new FrameLayout(this);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setImageBitmap(Wallpapers.thumbnail(spec.id, 240, 150, dim));
        thumb.setClipToOutline(true);
        thumb.setBackground(roundedFill(0xFF000000, 12));
        frame.addView(thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
        if (selected) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextColor(0xFFFFFFFF);
            check.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
            check.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(theme.accent);
            check.setBackground(dot);
            FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(20), dp(20),
                    Gravity.END | Gravity.TOP);
            checkLp.setMargins(0, dp(6), dp(6), 0);
            frame.addView(check, checkLp);
        }
        tile.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(spec.name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(4), dp(8), dp(4), 0);
        tile.addView(name);

        TextView state = new TextView(this);
        state.setText(selected ? s(R.string.st_wall_active) : s(R.string.st_wall_apply));
        state.setTextColor(selected ? theme.positive : theme.textFaint);
        state.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        state.setSingleLine(true);
        state.setPadding(dp(4), dp(1), dp(4), 0);
        tile.addView(state);
        return tile;
    }

    // ── section: Windows & apps ──

    private void buildWindowsSection(LinearLayout body) {
        LinearLayout launchCard = card(body);
        cardHeader(launchCard, s(R.string.st_launch_mode));
        String mode = DexPrefs.getString(this, DexPrefs.KEY_LAUNCH_MODE, DexPrefs.DEF_LAUNCH_MODE);
        String[][] modes = {
                {"cascade", s(R.string.st_launch_cascade), s(R.string.st_launch_cascade_sub)},
                {"center", s(R.string.st_launch_center), s(R.string.st_launch_center_sub)},
                {"maximized", s(R.string.st_launch_max), s(R.string.st_launch_max_sub)},
        };
        for (String[] entry : modes) {
            choiceRow(launchCard, null, entry[1], entry[2], entry[0].equals(mode), () -> {
                DexPrefs.put(this, DexPrefs.KEY_LAUNCH_MODE, entry[0]);
                showSection(SEC_WINDOWS, false);
            });
        }

        LinearLayout sizeCard = card(body);
        cardHeader(sizeCard, s(R.string.st_window_size));
        String size = DexPrefs.getString(this, DexPrefs.KEY_WINDOW_SIZE, DexPrefs.DEF_WINDOW_SIZE);
        String[][] sizes = {
                {"compact", s(R.string.st_size_compact), s(R.string.st_size_compact_sub)},
                {"standard", s(R.string.st_size_standard), s(R.string.st_size_standard_sub)},
                {"large", s(R.string.st_size_large), s(R.string.st_size_large_sub)},
        };
        for (String[] entry : sizes) {
            choiceRow(sizeCard, null, entry[1], entry[2], entry[0].equals(size), () -> {
                DexPrefs.put(this, DexPrefs.KEY_WINDOW_SIZE, entry[0]);
                showSection(SEC_WINDOWS, false);
            });
        }

        LinearLayout iconCard = card(body);
        cardHeader(iconCard, s(R.string.st_icon_size));
        String icons = DexPrefs.getString(this, DexPrefs.KEY_ICON_SIZE, DexPrefs.DEF_ICON_SIZE);
        String[][] iconSizes = {
                {"small", s(R.string.st_icons_small), null},
                {"medium", s(R.string.st_icons_medium), null},
                {"large", s(R.string.st_icons_large), null},
        };
        for (String[] entry : iconSizes) {
            choiceRow(iconCard, null, entry[1], entry[2], entry[0].equals(icons), () -> {
                DexPrefs.put(this, DexPrefs.KEY_ICON_SIZE, entry[0]);
                showSection(SEC_WINDOWS, false);
            });
        }
        note(body, s(R.string.st_windows_note));
    }

    // ── section: Mouse & cursor ──

    /**
     * The pointer: what it looks like, how big it is, and — first — which side
     * of the cable is drawing it at all.
     *
     * That last one is not a detail tucked in at the end. Under scrcpy's
     * default mouse mode the events reach us as injected input, below the stage
     * that owns the pointer sprite, so Android draws no cursor and what floats
     * over the video is the PC's own. Every {@link DexCursors} render in that
     * mode is computed, handed to the system and dropped. So the section says
     * so at the top, in the state where it is true, rather than letting the
     * user conclude the styles are broken.
     */
    private void buildMouseSection(LinearLayout body) {
        String mode = DexPrefs.getString(this, DexPrefs.KEY_MOUSE_MODE, DexPrefs.DEF_MOUSE_MODE);
        boolean phoneDraws = "uhid".equals(mode);
        String style = DexCursors.style(this);
        boolean custom = !DexCursors.STYLE_SYSTEM.equals(style);

        // First, because it is the gate: in the default mode Android draws no
        // pointer at all and every card below it is inert. A style picker above
        // a switch that decides whether styles exist reads as a broken feature.
        buildRenderingCard(body, mode);

        buildCursorPreview(body, style);
        buildCursorStyleCard(body, style);
        if (custom) {
            buildCursorSizeCard(body);
            buildCursorColourCard(body);
            buildCursorOutlineCard(body);
        }
        buildPointerSpeedCard(body, phoneDraws);
        // The rendering mode is a scrcpy command-line argument like the ones in
        // the Stream section, so it only lands on a fresh session. The footer
        // stays at the BOTTOM even though its card is now at the top — it is
        // the section's "and here is how to apply it", not the card's.
        restartFooter(body);
    }

    // ── section: Touchpad ──

    /**
     * One gesture and the actions it can be given.
     *
     * The pairing is the whole table: the wire value the desktop app reads (it
     * must match {@code Action::parse} in gestures/mod.rs) and the label the
     * user picks it by. Kept together so a new action cannot be added to one
     * side alone.
     */
    private static final String[] GESTURE_VALUES = {
            "none", "openapps", "showdesktop", "nextwindow", "prevwindow",
            "drawer", "maximize", "home", "back", "notifications",
    };

    /** Index-aligned with {@link #GESTURE_VALUES} — see the note there. */
    private static final int[] GESTURE_LABELS = {
            R.string.st_ga_none,
            R.string.st_ga_openapps,
            R.string.st_ga_showdesktop,
            R.string.st_ga_nextwindow,
            R.string.st_ga_prevwindow,
            R.string.st_ga_drawer,
            R.string.st_ga_maximize,
            R.string.st_ga_home,
            R.string.st_ga_back,
            R.string.st_ga_notifications,
    };

    /**
     * Three-finger gestures, and nothing else.
     *
     * One and two finger input is not offered because it is not ours to offer:
     * the computer's own driver already turns those into a pointer, a wheel
     * and a right-click that reach the phone unchanged, and the launcher's
     * context menus depend on that right-click. Four fingers are left to the
     * computer on purpose, so a user still has their own window switcher while
     * the desktop has three.
     */
    private void buildTouchpadSection(LinearLayout body) {
        boolean on = DexPrefs.getBool(this, DexPrefs.KEY_GESTURES, DexPrefs.DEF_GESTURES);
        // Reported by the desktop app on its running-apps broadcast. False on a
        // desktop PC with a mouse, and on a Mac that has not been granted
        // Accessibility yet — both are worth saying rather than leaving the
        // user to wonder why a section full of controls does nothing.
        boolean hostReady = DexPrefs.getBool(this, DexPrefs.KEY_HOST_TOUCHPAD, false);

        LinearLayout master = card(body);
        toggleRow(master, s(R.string.st_touchpad), s(R.string.st_touchpad_sub), on, true,
                next -> {
                    pcConfig(DexPrefs.KEY_GESTURES, next.booleanValue());
                    showSection(SEC_TOUCHPAD, false);
                });
        note(master, s(R.string.st_touchpad_takeover));
        if (!hostReady) {
            note(master, s(R.string.st_touchpad_none));
            note(master, s(R.string.st_touchpad_permission));
        }

        // Dim rather than hide, the same way the pointer-speed card does: a
        // section that empties itself reads as broken, while a dimmed one
        // reads as "not yet".
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setAlpha(on && hostReady ? 1f : 0.4f);
        body.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        gestureCard(rows, R.string.st_gesture_3up, DexPrefs.KEY_GESTURE_3UP,
                DexPrefs.DEF_GESTURE_3UP, on);
        gestureCard(rows, R.string.st_gesture_3down, DexPrefs.KEY_GESTURE_3DOWN,
                DexPrefs.DEF_GESTURE_3DOWN, on);
        gestureCard(rows, R.string.st_gesture_3left, DexPrefs.KEY_GESTURE_3LEFT,
                DexPrefs.DEF_GESTURE_3LEFT, on);
        gestureCard(rows, R.string.st_gesture_3right, DexPrefs.KEY_GESTURE_3RIGHT,
                DexPrefs.DEF_GESTURE_3RIGHT, on);
        gestureCard(rows, R.string.st_gesture_3tap, DexPrefs.KEY_GESTURE_3TAP,
                DexPrefs.DEF_GESTURE_3TAP, on);

        // Deliberately NOT restartFooter(): unlike every other PC-side setting
        // in this window, a gesture mapping is not a scrcpy argument — the
        // desktop app reads it fresh on each gesture.
        note(body, s(R.string.st_touchpad_live));
    }

    /** One gesture's card: its name, then the action it is bound to. */
    private void gestureCard(LinearLayout body, int titleRes, String key, String def,
                             boolean enabled) {
        LinearLayout card = card(body);
        cardHeader(card, s(titleRes));
        String current = DexPrefs.getString(this, key, def);
        for (int i = 0; i < GESTURE_VALUES.length; i++) {
            final String value = GESTURE_VALUES[i];
            String subtitle = null;
            if ("openapps".equals(value)) subtitle = s(R.string.st_ga_openapps_sub);
            if ("showdesktop".equals(value)) subtitle = s(R.string.st_ga_showdesktop_sub);
            choiceRow(card, null, s(GESTURE_LABELS[i]), subtitle, value.equals(current),
                    () -> {
                        if (!enabled) return;
                        pcConfig(key, value);
                        showSection(SEC_TOUCHPAD, false);
                    });
        }
    }

    /**
     * Every pointer the shell can show, at the size it will actually be.
     *
     * A style picker that shows only arrows is a picker that hides the half of
     * the work — the resize handles and the drag cursors are where a style
     * either holds together or falls apart, so they are all here, each labelled
     * with the surface it belongs to.
     */
    private void buildCursorPreview(LinearLayout body, String style) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_preview));

        if (DexCursors.STYLE_SYSTEM.equals(style)) {
            note(card, s(R.string.st_cursor_style_system_sub));
            return;
        }

        int[][] roles = {
                {DexCursors.ROLE_ARROW, R.string.st_cursor_role_arrow},
                {DexCursors.ROLE_HAND, R.string.st_cursor_role_hand},
                {DexCursors.ROLE_TEXT, R.string.st_cursor_role_text},
                {DexCursors.ROLE_GRAB, R.string.st_cursor_role_grab},
                {DexCursors.ROLE_GRABBING, R.string.st_cursor_role_grabbing},
                {DexCursors.ROLE_RESIZE_NWSE, R.string.st_cursor_role_resize},
                {DexCursors.ROLE_WAIT, R.string.st_cursor_role_wait},
                {DexCursors.ROLE_NO_DROP, R.string.st_cursor_role_nodrop},
        };
        // The preview tracks the size slider, so a huge pointer gets a bigger
        // cell rather than a scaled-down lie about what was picked.
        int cell = dp(30 + DexCursors.size(this) * 22 / 100f);
        LinearLayout row = null;
        for (int i = 0; i < roles.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(8), dp(2), dp(8), dp(2));
                card.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(cursorCell(roles[i][0], s(roles[i][1]), null, cell), lp);
        }
        note(card, s(R.string.st_cursor_note));
    }

    /** One rendered pointer over a neutral plate, with what it means under it. */
    private View cursorCell(int role, String label, String styleOverride, int px) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Over the card's own colour, not over white: a white pointer on a
        // white plate would preview as nothing, which is exactly the mistake
        // the outline setting exists to prevent.
        shot.setBackground(roundedFill(theme.field, 10));
        shot.setImageBitmap(DexCursors.preview(this, role, styleOverride, px));
        int pad = dp(6);
        shot.setPadding(pad, pad, pad, pad);
        cell.addView(shot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px + pad * 2));

        if (label != null) {
            TextView name = new TextView(this);
            name.setText(label);
            name.setTextColor(theme.textFaint);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10));
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(2), dp(5), dp(2), 0);
            cell.addView(name);
        }
        return cell;
    }

    private void buildCursorStyleCard(LinearLayout body, String current) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_style));
        int[][] names = {
                {R.string.st_cursor_style_system, R.string.st_cursor_style_system_sub},
                {R.string.st_cursor_style_dex, R.string.st_cursor_style_dex_sub},
                {R.string.st_cursor_style_outline, R.string.st_cursor_style_outline_sub},
                {R.string.st_cursor_style_solid, R.string.st_cursor_style_solid_sub},
                {R.string.st_cursor_style_mini, R.string.st_cursor_style_mini_sub},
                {R.string.st_cursor_style_ring, R.string.st_cursor_style_ring_sub},
                {R.string.st_cursor_style_pixel, R.string.st_cursor_style_pixel_sub},
                {R.string.st_cursor_style_shadow, R.string.st_cursor_style_shadow_sub},
        };
        LinearLayout row = null;
        for (int i = 0; i < DexCursors.STYLES.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(8), dp(2), dp(8), dp(2));
                card.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(styleTile(DexCursors.STYLES[i], s(names[i][0]), s(names[i][1]),
                    DexCursors.STYLES[i].equals(current)), lp);
        }
    }

    /**
     * One style, drawn in ITS OWN style rather than in the selected one — the
     * same rule the paper swatches follow, and the only thing that makes the
     * row a comparison instead of eight identical thumbnails.
     */
    private View styleTile(String id, String name, String detail, boolean selected) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        tile.setBackground(selected
                ? plainFill(blend(theme.accent, 0x2E), 14)
                : tapBackground(0x00000000, theme.hover, 14));
        tile.setOnClickListener(v -> DexPrefs.put(this, DexPrefs.KEY_CURSOR_STYLE, id));

        if (DexCursors.STYLE_SYSTEM.equals(id)) {
            // Nothing of ours to draw, so show the platform's word for it
            // rather than an empty plate.
            TextView glyph = new TextView(this);
            glyph.setText("↖");
            glyph.setTextColor(selected ? theme.accent : theme.textDim);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
            glyph.setGravity(Gravity.CENTER);
            glyph.setBackground(roundedFill(theme.field, 10));
            tile.addView(glyph, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        } else {
            tile.addView(cursorCell(DexCursors.ROLE_ARROW, null, id, dp(32)),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(selected ? theme.accent : theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(8), dp(2), 0);
        tile.addView(label);

        TextView sub = new TextView(this);
        sub.setText(detail);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setMaxLines(3);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(sub);
        return tile;
    }

    /**
     * Five named steps and a slider over the whole range.
     *
     * Both, not one: the named steps are what someone who wants a bigger
     * pointer actually wants, and the slider is what makes "all sizes" true.
     * {@link #sliderRow} is not reusable here — its track is hard-coded to
     * 0–100 because everything else in this window is a percentage of itself.
     */
    private void buildCursorSizeCard(LinearLayout body) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_size));
        int size = DexCursors.size(this);

        int[][] steps = {
                {75, R.string.st_cursor_size_small},
                {100, R.string.st_cursor_size_default},
                {150, R.string.st_cursor_size_large},
                {200, R.string.st_cursor_size_xl},
                {300, R.string.st_cursor_size_huge},
        };
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(6));
        for (int[] step : steps) {
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(2), dp(3), dp(2));
            row.addView(sizePill(step[0], s(step[1]), size == step[0]), lp);
        }
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(4), dp(14), 0);
        TextView low = new TextView(this);
        low.setText(DexCursors.MIN_SIZE + "%");
        low.setTextColor(theme.textFaint);
        low.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        head.addView(low, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(this);
        value.setText(size + "%");
        value.setTextColor(theme.accent);
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(value);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView live = new ImageView(this);
        live.setScaleType(ImageView.ScaleType.FIT_CENTER);

        SeekBar bar = new SeekBar(this);
        bar.setMax(DexCursors.MAX_SIZE - DexCursors.MIN_SIZE);
        bar.setProgress(size - DexCursors.MIN_SIZE);
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        bar.setThumbTintList(ColorStateList.valueOf(theme.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        bar.setPadding(dp(16), dp(6), dp(16), dp(6));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = DexCursors.MIN_SIZE + progress;
                value.setText(percent + "%");
                // The preview follows the thumb; the pref does not. A write
                // repaints the whole shell behind this window and re-creates
                // this one, which at ~100 events per drag is a stutter and an
                // uncontrollable slider.
                live.setImageBitmap(DexCursors.preview(SettingsActivity.this,
                        DexCursors.ROLE_ARROW, null,
                        dp(22 + percent * 26 / 100f)));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                DexPrefs.put(SettingsActivity.this, DexPrefs.KEY_CURSOR_SIZE,
                        DexCursors.MIN_SIZE + seekBar.getProgress());
            }
        });
        card.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        live.setImageBitmap(DexCursors.preview(this, DexCursors.ROLE_ARROW, null,
                dp(22 + size * 26 / 100f)));
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        liveLp.setMargins(dp(14), dp(2), dp(14), dp(4));
        live.setBackground(roundedFill(theme.field, 12));
        card.addView(live, liveLp);

        note(card, s(R.string.st_cursor_size_note));
    }

    /** One named size. A pill rather than a radio: five of them fit one row. */
    private View sizePill(int percent, String label, boolean selected) {
        TextView pill = new TextView(this);
        pill.setText(label);
        pill.setTextColor(selected ? theme.accent : theme.textDim);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setPadding(dp(6), dp(9), dp(6), dp(9));
        pill.setBackground(selected
                ? plainFill(blend(theme.accent, 0x2E), 11)
                : tapBackground(theme.field, theme.hover, 11));
        pill.setOnClickListener(v ->
                DexPrefs.put(this, DexPrefs.KEY_CURSOR_SIZE, percent));
        return pill;
    }

    private void buildCursorColourCard(LinearLayout body) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_color));
        String current = DexPrefs.getString(this, DexPrefs.KEY_CURSOR_COLOR,
                DexPrefs.DEF_CURSOR_COLOR);
        int[] labels = {R.string.st_cursor_white, R.string.st_cursor_black,
                R.string.st_cursor_accent, R.string.st_cursor_red, R.string.st_cursor_green,
                R.string.st_cursor_yellow, R.string.st_cursor_pink, R.string.st_cursor_blue,
                R.string.st_cursor_purple};

        LinearLayout row = null;
        for (int i = 0; i < DexCursors.COLOURS.length; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(8), dp(2), dp(8), dp(2));
                card.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(colourTile(DexCursors.COLOURS[i], s(labels[i]),
                    DexCursors.COLOURS[i].equals(current)), lp);
        }
        // the last row is short — keep its tiles the width of a full row's
        if (DexCursors.COLOURS.length % 5 != 0 && row != null) {
            for (int i = DexCursors.COLOURS.length % 5; i < 5; i++) {
                LinearLayout.LayoutParams filler =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                filler.setMargins(dp(3), dp(4), dp(3), dp(4));
                row.addView(new View(this), filler);
            }
        }
    }

    private View colourTile(String id, String label, boolean selected) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(6), dp(4), dp(6));
        tile.setBackground(selected
                ? plainFill(blend(theme.accent, 0x2E), 12)
                : tapBackground(0x00000000, theme.hover, 12));
        tile.setOnClickListener(v -> DexPrefs.put(this, DexPrefs.KEY_CURSOR_COLOR, id));

        // The swatch is the pointer itself in that hue, not a filled circle:
        // the arrow is where a colour has to work, and a disc says nothing
        // about how the keyline will sit on it.
        ImageView shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shot.setBackground(roundedFill(theme.field, 10));
        shot.setImageBitmap(DexCursors.previewIn(this, id, dp(26)));
        int pad = dp(5);
        shot.setPadding(pad, pad, pad, pad);
        tile.addView(shot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26) + pad * 2));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(selected ? theme.accent : theme.textDim);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(1), dp(6), dp(1), 0);
        tile.addView(name);
        return tile;
    }

    private void buildCursorOutlineCard(LinearLayout body) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_outline));
        String current = DexPrefs.getString(this, DexPrefs.KEY_CURSOR_OUTLINE,
                DexPrefs.DEF_CURSOR_OUTLINE);
        String[][] options = {
                {DexCursors.OUTLINE_CONTRAST, s(R.string.st_cursor_outline_auto),
                        s(R.string.st_cursor_outline_auto_sub)},
                {DexCursors.OUTLINE_BLACK, s(R.string.st_cursor_outline_black), null},
                {DexCursors.OUTLINE_WHITE, s(R.string.st_cursor_outline_white), null},
                {DexCursors.OUTLINE_NONE, s(R.string.st_cursor_outline_none), null},
        };
        for (String[] option : options) {
            choiceRow(card, null, option[1], option[2], option[0].equals(current),
                    () -> DexPrefs.put(this, DexPrefs.KEY_CURSOR_OUTLINE, option[0]));
        }
        toggleRow(card, s(R.string.st_cursor_shadow), s(R.string.st_cursor_shadow_sub),
                DexPrefs.getBool(this, DexPrefs.KEY_CURSOR_SHADOW, DexPrefs.DEF_CURSOR_SHADOW),
                false, on -> DexPrefs.put(this, DexPrefs.KEY_CURSOR_SHADOW, on.booleanValue()));
    }

    /** The platform's own pointer-speed bounds. */
    private static final int SPEED_MIN = -7;
    private static final int SPEED_MAX = 7;

    private static int clampSpeed(int v) {
        return Math.max(SPEED_MIN, Math.min(SPEED_MAX, v));
    }

    /**
     * Pointer speed: the platform's own -7…7, written to Settings.System.
     *
     * The one control here the launcher cannot apply itself — pointer_speed is
     * a private setting, and this app holds neither WRITE_SETTINGS nor the
     * shell uid that is exempt from the restriction. It leaves the same way
     * "Reduce quality" does, over the request queue to the PC's adb shell,
     * which snapshots the phone's own value first and puts it back on exit.
     *
     * Dimmed unless the phone is drawing the pointer, because that is the only
     * mode where it does anything: a uhid mouse is RELATIVE and Android scales
     * its deltas, whereas the default mode maps the computer's pointer
     * position straight onto the screen and there is no motion here to scale.
     * That difference is also why this control exists at all — relative motion
     * at the stock speed reads as slower than the 1:1 mapping it replaced.
     */
    private void buildPointerSpeedCard(LinearLayout body, boolean phoneDraws) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_speed));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setAlpha(phoneDraws ? 1f : 0.4f);
        card.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int speed = clampSpeed(DexPrefs.getInt(this, DexPrefs.KEY_CURSOR_SPEED,
                DexPrefs.DEF_CURSOR_SPEED));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(6), dp(16), 0);
        TextView slow = new TextView(this);
        slow.setText(s(R.string.st_cursor_speed_slow));
        slow.setTextColor(theme.textFaint);
        slow.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        head.addView(slow, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(this);
        value.setText(speedLabel(speed));
        value.setTextColor(theme.accent);
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(value);
        TextView fast = new TextView(this);
        fast.setText(s(R.string.st_cursor_speed_fast));
        fast.setTextColor(theme.textFaint);
        fast.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(10.5f));
        fast.setGravity(Gravity.END);
        head.addView(fast, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrap.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(this);
        bar.setMax(SPEED_MAX - SPEED_MIN);
        bar.setProgress(speed - SPEED_MIN);
        bar.setEnabled(phoneDraws);
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        bar.setThumbTintList(ColorStateList.valueOf(theme.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.divider));
        bar.setPadding(dp(16), dp(6), dp(16), dp(8));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(speedLabel(SPEED_MIN + progress));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // On release, not per pixel: every commit is an adb round trip
                // on the PC, and a drag would raise a hundred of them.
                int next = SPEED_MIN + seekBar.getProgress();
                DexPrefs.put(SettingsActivity.this, DexPrefs.KEY_CURSOR_SPEED, next);
                RequestProvider.enqueue("cursor", "speed." + next);
            }
        });
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        note(card, s(R.string.st_cursor_speed_sub));
    }

    /** "+3" reads as a setting; "3" reads as a count. */
    private static String speedLabel(int speed) {
        return speed > 0 ? "+" + speed : String.valueOf(speed);
    }

    /**
     * Which side draws the pointer. A scrcpy command-line argument like every
     * other one in the Stream section, so it lands on a fresh session and
     * carries the same restart footer.
     */
    private void buildRenderingCard(LinearLayout body, String mode) {
        LinearLayout card = card(body);
        cardHeader(card, s(R.string.st_cursor_render));
        String[][] modes = {
                {"sdk", s(R.string.st_cursor_render_pc), s(R.string.st_cursor_render_pc_sub)},
                {"uhid", s(R.string.st_cursor_render_phone),
                        s(R.string.st_cursor_render_phone_sub)},
        };
        for (String[] entry : modes) {
            choiceRow(card, null, entry[1], entry[2], entry[0].equals(mode), () -> {
                pcConfig(DexPrefs.KEY_MOUSE_MODE, entry[0]);
                showSection(SEC_MOUSE, false);
            });
        }
        // Under the choices, not at the foot of the section: this is the one
        // thing about the mode a user needs to know BEFORE they pick it, not
        // after they have lost their mouse to it.
        note(card, s(R.string.st_cursor_render_note));
        note(card, s(R.string.st_cursor_render_req));
    }

    // ── section: Performance ──

    /**
     * One switch that trades the desktop's finish for frames.
     *
     * It is a single row and then an itemised list of what it actually does,
     * because "reduce quality" on its own is a promise with no content — and
     * the four things it does land in four different places (this window, the
     * shell, the platform, the video stream), only two of which the user can
     * see change immediately.
     *
     * Nothing here writes over the glass or grain settings it suppresses: the
     * dialled-in values stay in SharedPreferences and come back the moment the
     * switch goes off. {@link DexTheme} reads the switch and ignores them while
     * it is on, which is also why flipping it re-creates this window.
     */
    private void buildPerformanceSection(LinearLayout body) {
        boolean on = DexPrefs.getBool(this, DexPrefs.KEY_PERF, DexPrefs.DEF_PERF);

        LinearLayout perfCard = card(body);
        toggleRow(perfCard, s(R.string.st_perf), s(R.string.st_perf_sub), on, true, next -> {
            DexPrefs.put(this, DexPrefs.KEY_PERF, next.booleanValue());
            // The half only adb can do — animation scales now, stream bitrate
            // at the next spawn. The pref write above has already repainted
            // our own half through the broadcast.
            RequestProvider.enqueue("perf", next ? "on" : "off");
        });

        groupHeader(body, s(R.string.st_perf_what));
        LinearLayout whatCard = card(body);
        String[][] effects = {
                {"◑", s(R.string.st_perf_effects), s(R.string.st_perf_effects_sub)},
                {"⟳", s(R.string.st_perf_anim), s(R.string.st_perf_anim_sub)},
                {"▤", s(R.string.st_perf_shadow), s(R.string.st_perf_shadow_sub)},
                {"🎞", s(R.string.st_perf_stream), s(R.string.st_perf_stream_sub)},
        };
        for (String[] effect : effects) {
            effectRow(whatCard, effect[0], effect[1], effect[2], on);
        }

        note(body, s(R.string.st_perf_note));
        // The bitrate cap is a scrcpy argument like every other one in the
        // Stream section, so it only lands on a fresh session — the rest of
        // the switch is already live.
        restartFooter(body);
    }

    /**
     * One line of "here is what the switch is doing". Not a control: it has no
     * click, and it dims as a group when the switch is off, so the card reads
     * as a description of a state rather than four more things to set.
     */
    private void effectRow(LinearLayout parent, String glyph, String title,
                           String subtitle, boolean active) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setAlpha(active ? 1f : 0.4f);

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextColor(active ? theme.accent : theme.textDim);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        iconLp.rightMargin = dp(10);
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13.5f));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(label);
        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ── sections: Scrcpy Config & Clipboard (settings the desktop app owns) ──

    /**
     * Store a desktop-app setting on the phone AND push it to the PC.
     *
     * The phone-side copy is what this window draws from — the PC has no way
     * to answer a question over the request queue, which only goes one way.
     * The pushed copy is the one that ends up on scrcpy's command line, and it
     * survives the desktop app restarting because that side writes it to disk.
     */
    private void pcConfig(String key, String value) {
        DexPrefs.put(this, key, value);
        push(key, value);
    }

    /**
     * Each overload stores the value in its OWN type. SharedPreferences is
     * strongly typed per key — storing an int as its decimal string makes the
     * next {@code getInt} throw ClassCastException, and the wire form
     * (always a string) must not decide how it is stored.
     */
    private void pcConfig(String key, int value) {
        DexPrefs.put(this, key, value);
        push(key, String.valueOf(value));
    }

    private void pcConfig(String key, boolean value) {
        DexPrefs.put(this, key, value);
        push(key, value ? "on" : "off");
    }

    private void push(String key, String value) {
        RequestProvider.enqueue("cfg", key.substring(DexPrefs.PC_PREFIX.length()) + "." + value);
    }

    /**
     * Ask the desktop app to cycle the session.
     *
     * Everything in this section is a scrcpy command-line argument, and scrcpy
     * reads its arguments once. The desktop app answers this by stopping the
     * stream and starting a new one with the stored values — a couple of
     * seconds of black, not a reconnect dance.
     */
    private void restartDesktop() {
        RequestProvider.enqueue("restart", "desktop");
        Toast.makeText(this, s(R.string.st_restart_toast), Toast.LENGTH_LONG).show();
    }

    /** The "…takes effect after a restart" footer, with the button that does it. */
    private void restartFooter(LinearLayout body) {
        note(body, s(R.string.st_restart_note));
        TextView button = new TextView(this);
        button.setText("↻   " + s(R.string.st_restart_now));
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(20), dp(11), dp(20), dp(11));
        button.setBackground(tapBackground(theme.accent, lighten(theme.accent), 12));
        button.setOnClickListener(v -> restartDesktop());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.leftMargin = dp(14);
        body.addView(button, lp);
    }

    private void buildStreamSection(LinearLayout body) {
        LinearLayout resCard = card(body);
        cardHeader(resCard, s(R.string.st_resolution));
        String res = DexPrefs.getString(this, DexPrefs.KEY_RESOLUTION, DexPrefs.DEF_RESOLUTION);
        String[][] resolutions = {
                {"1280x720", "1280 × 720", s(R.string.st_res_720)},
                {"1600x900", "1600 × 900", s(R.string.st_res_900)},
                {"1920x1080", "1920 × 1080", s(R.string.st_res_1080)},
                {"2560x1440", "2560 × 1440", s(R.string.st_res_1440)},
        };
        for (String[] entry : resolutions) {
            choiceRow(resCard, null, entry[1], entry[2], entry[0].equals(res),
                    () -> {
                        pcConfig(DexPrefs.KEY_RESOLUTION, entry[0]);
                        rescaleDensityFor(entry[0]);
                        showSection(SEC_STREAM, false);
                    });
        }
        note(body, s(R.string.st_res_note));

        LinearLayout codecCard = card(body);
        cardHeader(codecCard, s(R.string.st_codec));
        String codec = DexPrefs.getString(this, DexPrefs.KEY_CODEC, DexPrefs.DEF_CODEC);
        String[][] codecs = {
                {DexEncoders.CODEC_AUTO, s(R.string.st_codec_auto), s(R.string.st_codec_auto_sub)},
                {DexEncoders.CODEC_H264, "H.264", s(R.string.st_codec_h264_sub)},
                {DexEncoders.CODEC_H265, "H.265 (HEVC)", s(R.string.st_codec_h265_sub)},
                {DexEncoders.CODEC_AV1, "AV1", s(R.string.st_codec_av1_sub)},
        };
        for (String[] entry : codecs) {
            choiceRow(codecCard, null, entry[1], entry[2], entry[0].equals(codec), () -> {
                pcConfig(DexPrefs.KEY_CODEC, entry[0]);
                // an encoder belongs to one codec — a stale pick would make
                // scrcpy refuse to start, so it goes back to Auto
                pcConfig(DexPrefs.KEY_ENCODER, DexPrefs.DEF_ENCODER);
                showSection(SEC_STREAM, false);
            });
        }

        LinearLayout encoderCard = card(body);
        cardHeader(encoderCard, s(R.string.st_encoder));
        List<DexEncoders.Encoder> encoders = DexEncoders.forCodec(codec);
        if (encoders.isEmpty()) {
            // "Auto" codec, or a codec this phone cannot encode at all
            note(encoderCard, s(DexEncoders.CODEC_AUTO.equals(codec)
                    ? R.string.st_encoder_needs_codec : R.string.st_encoder_none));
        } else {
            String encoder = DexPrefs.getString(this, DexPrefs.KEY_ENCODER, DexPrefs.DEF_ENCODER);
            choiceRow(encoderCard, null, s(R.string.st_encoder_auto),
                    s(R.string.st_encoder_auto_sub), DexPrefs.DEF_ENCODER.equals(encoder),
                    () -> {
                        pcConfig(DexPrefs.KEY_ENCODER, DexPrefs.DEF_ENCODER);
                        showSection(SEC_STREAM, false);
                    });
            for (DexEncoders.Encoder option : encoders) {
                String detail = option.knownAcceleration
                        ? s(option.hardware ? R.string.st_encoder_hw : R.string.st_encoder_sw)
                        : s(R.string.st_encoder_unknown);
                choiceRow(encoderCard, null, option.name, detail, option.name.equals(encoder),
                        () -> {
                            pcConfig(DexPrefs.KEY_ENCODER, option.name);
                            showSection(SEC_STREAM, false);
                        });
            }
        }

        LinearLayout perfCard = card(body);
        cardHeader(perfCard, s(R.string.st_bitrate));
        int bitrate = DexPrefs.getInt(this, DexPrefs.KEY_BITRATE, DexPrefs.DEF_BITRATE);
        for (int mbps : new int[]{2, 4, 8, 12, 20}) {
            final int value = mbps;
            choiceRow(perfCard, null, getString(R.string.st_bitrate_value, mbps),
                    mbps == DexPrefs.DEF_BITRATE ? s(R.string.st_default_suffix) : null,
                    mbps == bitrate, () -> {
                        pcConfig(DexPrefs.KEY_BITRATE, value);
                        showSection(SEC_STREAM, false);
                    });
        }

        LinearLayout fpsCard = card(body);
        cardHeader(fpsCard, s(R.string.st_fps));
        int fps = DexPrefs.getInt(this, DexPrefs.KEY_FPS, DexPrefs.DEF_FPS);
        for (int value : new int[]{0, 30, 60, 90, 120}) {
            final int chosen = value;
            choiceRow(fpsCard, null,
                    value == 0 ? s(R.string.st_fps_auto) : getString(R.string.st_fps_value, value),
                    value == 0 ? s(R.string.st_fps_auto_sub) : null,
                    value == fps, () -> {
                        pcConfig(DexPrefs.KEY_FPS, chosen);
                        showSection(SEC_STREAM, false);
                    });
        }

        LinearLayout audioCard = card(body);
        boolean audio = DexPrefs.getBool(this, DexPrefs.KEY_AUDIO, DexPrefs.DEF_AUDIO);
        toggleRow(audioCard, s(R.string.st_audio_forward), s(R.string.st_audio_forward_sub),
                audio, true, on -> pcConfig(DexPrefs.KEY_AUDIO, on.booleanValue()));

        restartFooter(body);
    }

    private void buildClipboardSection(LinearLayout body) {
        LinearLayout syncCard = card(body);
        boolean sync = DexPrefs.getBool(this, DexPrefs.KEY_CLIP_SYNC, DexPrefs.DEF_CLIP_SYNC);
        toggleRow(syncCard, s(R.string.st_clip_sync), s(R.string.st_clip_sync_sub), sync, true,
                on -> pcConfig(DexPrefs.KEY_CLIP_SYNC, on.booleanValue()));
        note(body, s(R.string.st_clip_sync_note));

        groupHeader(body, s(R.string.st_clip_current));
        LinearLayout currentCard = card(body);
        String current = DexClipboard.current(this);

        TextView preview = new TextView(this);
        preview.setText(current.isEmpty() ? s(R.string.st_clip_empty) : current);
        preview.setTextColor(current.isEmpty() ? theme.textFaint : theme.text);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        preview.setMaxLines(4);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setBackground(roundedFill(theme.field, 12));
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLp.setMargins(dp(10), dp(10), dp(10), dp(4));
        currentCard.addView(preview, previewLp);

        EditText input = new EditText(this);
        input.setHint(s(R.string.st_clip_placeholder));
        input.setHintTextColor(theme.textFaint);
        input.setTextColor(theme.text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        input.setMaxLines(3);
        input.setBackground(roundedFill(theme.field, 12));
        input.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(dp(10), dp(8), dp(10), dp(4));
        currentCard.addView(input, inputLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(10), dp(4), dp(10), dp(6));
        actions.addView(pillButton(s(R.string.st_clip_set), theme.accent, () -> {
            String text = input.getText().toString();
            if (text.isEmpty()) return;
            if (DexClipboard.set(this, text)) {
                input.setText("");
                Toast.makeText(this, s(R.string.st_clip_copied), Toast.LENGTH_SHORT).show();
                showSection(SEC_CLIPBOARD, false);
            }
        }));
        actions.addView(pillButton(s(R.string.st_clip_clear), theme.danger, () -> {
            DexClipboard.clear(this);
            Toast.makeText(this, s(R.string.st_clip_cleared), Toast.LENGTH_SHORT).show();
            showSection(SEC_CLIPBOARD, false);
        }));
        currentCard.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        groupHeader(body, s(R.string.st_clip_history));
        LinearLayout historyCard = card(body);
        List<String> history = DexClipboard.history(this);
        if (history.isEmpty()) {
            note(historyCard, s(R.string.st_clip_history_empty));
        } else {
            for (String entry : history) {
                historyCard.addView(clipboardRow(entry));
            }
            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setPadding(dp(10), dp(6), dp(10), dp(4));
            footer.addView(pillButton(s(R.string.st_clip_clear_history), theme.danger, () -> {
                DexClipboard.clearHistory(this);
                showSection(SEC_CLIPBOARD, false);
            }));
            historyCard.addView(footer);
        }
        note(body, s(R.string.st_clip_note));
    }

    /** One history entry: the text, a re-copy tap, and a remove button. */
    private View clipboardRow(String entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(8), dp(9));
        row.setBackground(tapBackground(0x00000000, theme.hover, 12));
        row.setOnClickListener(v -> {
            if (DexClipboard.set(this, entry)) {
                Toast.makeText(this, s(R.string.st_clip_copied), Toast.LENGTH_SHORT).show();
                showSection(SEC_CLIPBOARD, false);
            }
        });

        TextView text = new TextView(this);
        // one line: the history is for finding something, not for reading it
        text.setText(entry.replace('\n', ' '));
        text.setTextColor(theme.text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = new TextView(this);
        remove.setText("✕");
        remove.setContentDescription(s(R.string.st_clip_remove));
        remove.setTextColor(theme.textFaint);
        remove.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        remove.setGravity(Gravity.CENTER);
        remove.setBackground(tapBackground(0x00000000, blend(theme.danger, 0x66), 10));
        remove.setOnClickListener(v -> {
            DexClipboard.forget(this, entry);
            showSection(SEC_CLIPBOARD, false);
        });
        row.addView(remove, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(lp);
        return row;
    }

    private View pillButton(String label, int tint, Runnable onClick) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(tint);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(9), dp(16), dp(9));
        button.setBackground(tapBackground(blend(tint, 0x1F), blend(tint, 0x3D), 11));
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    /**
     * The clipboard is only readable while we hold focus (Android 10+), so the
     * moment focus arrives is the moment to snapshot it — that is how anything
     * copied on the desktop, or pushed over from the PC, reaches the history.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        String text = DexClipboard.current(this);
        if (text.isEmpty()) return;
        List<String> before = DexClipboard.history(this);
        DexClipboard.remember(this, text);
        // only redraw when the snapshot actually added something
        if (SEC_CLIPBOARD.equals(section) && !DexClipboard.history(this).equals(before)) {
            showSection(SEC_CLIPBOARD, false);
        }
    }

    // ── section: About & reset ──

    private void buildAboutSection(LinearLayout body) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setBackground(roundedFill(theme.card(), 18));
        hero.setPadding(dp(18), dp(22), dp(18), dp(20));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(12);
        body.addView(hero, heroLp);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setBackground(roundedFill(blend(theme.accent, 0x22), 20));
        int logoPad = dp(6);
        logo.setPadding(logoPad, logoPad, logoPad, logoPad);
        hero.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextView name = new TextView(this);
        name.setText(s(R.string.app_name));
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(19));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setPadding(0, dp(12), 0, 0);
        hero.addView(name);

        TextView tagline = new TextView(this);
        tagline.setText(s(R.string.st_about_tagline));
        tagline.setTextColor(theme.textFaint);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        tagline.setGravity(Gravity.CENTER_HORIZONTAL);
        tagline.setPadding(0, dp(4), 0, dp(10));
        hero.addView(tagline);

        TextView version = new TextView(this);
        version.setText(getString(R.string.st_about_version, versionName()));
        version.setTextColor(theme.accent);
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        version.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        version.setBackground(roundedFill(blend(theme.accent, 0x1F), 12));
        version.setPadding(dp(14), dp(6), dp(14), dp(6));
        hero.addView(version);

        TextView display = new TextView(this);
        int displayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
        display.setText(getString(R.string.st_about_display, displayId,
                getResources().getConfiguration().densityDpi));
        display.setTextColor(theme.textFaint);
        display.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        display.setPadding(0, dp(10), 0, 0);
        hero.addView(display);

        groupHeader(body, s(R.string.st_session));
        LinearLayout session = card(body);
        actionRow(session, android.R.drawable.ic_lock_power_off, 0xFFf87171,
                s(R.string.st_exit_dex), s(R.string.st_exit_dex_sub), this::confirmExitDex);

        groupHeader(body, s(R.string.st_updates));
        LinearLayout updates = card(body);
        actionRow(updates, "↻", 0xFF60a5fa, s(R.string.st_updates_check),
                s(R.string.st_updates_check_sub), () -> openUrl(RELEASES_URL));

        groupHeader(body, s(R.string.st_maintenance));
        LinearLayout maintenance = card(body);
        actionRow(maintenance, "☷", 0xFF34d399, s(R.string.st_reset_home),
                s(R.string.st_reset_home_sub), this::confirmResetHome);
        actionRow(maintenance, "⏻", 0xFFf87171, s(R.string.st_factory_reset),
                s(R.string.st_factory_reset_sub), this::showFactoryReset);

        groupHeader(body, s(R.string.st_resources));
        LinearLayout resources = card(body);
        actionRow(resources, "⚙", 0xFF60a5fa, s(R.string.st_res_repo),
                s(R.string.st_res_repo_sub), () -> openUrl(REPO_URL));
        actionRow(resources, "⌘", 0xFFa78bfa, s(R.string.st_res_daemon),
                s(R.string.st_res_daemon_sub), () -> openUrl(WMD_URL));
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Open a link in a window on this display. A browser started without
     * bounds would come up fullscreen over the desktop, so it gets the same
     * freeform treatment every other launch here does.
     */
    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(getDisplay().getDisplayId());
        opts.setLaunchBounds(new android.graphics.Rect(
                size.x / 6, size.y / 10, size.x * 5 / 6, size.y * 9 / 10));
        // only the reflective call belongs in a try — sharing one with the two
        // above meant a throw from either silently dropped the bounds and the
        // browser came up fullscreen over the desktop
        try {
            android.app.ActivityOptions.class
                    .getMethod("setLaunchWindowingMode", int.class)
                    .invoke(opts, 5 /* WINDOWING_MODE_FREEFORM */);
        } catch (Exception ignored) {
        }
        try {
            startActivity(intent, opts.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.st_no_browser), Toast.LENGTH_SHORT).show();
        }
    }

    // ── exit ──

    /**
     * Leave DeX: the desktop app tears the display down and undoes the profile
     * it applied to the phone. The same request the taskbar's power button
     * raises — this is the entry point for someone who is already in Settings.
     */
    private void confirmExitDex() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = dialogPanel(dialog, android.R.drawable.ic_lock_power_off,
                theme.danger, s(R.string.st_exit_dex), s(R.string.st_exit_dex_sub));

        TextView body = new TextView(this);
        body.setText(s(R.string.st_exit_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        body.setPadding(dp(20), dp(4), dp(20), dp(16));
        panel.addView(body);

        panel.addView(dialogButtons(dialog, s(R.string.st_exit_dex), theme.danger, () -> {
            RequestProvider.enqueue("exit", "dex");
            Toast.makeText(this, s(R.string.st_exit_toast), Toast.LENGTH_LONG).show();
            // this window would otherwise be the last thing on a display that
            // is about to disappear
            finish();
        }));
        dialog.show();
    }

    // ── reset flows ──

    private void confirmResetHome() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = dialogPanel(dialog, "☷", theme.accent,
                s(R.string.st_reset_home), s(R.string.st_reset_home_sub));

        TextView body = new TextView(this);
        body.setText(s(R.string.st_reset_home_body));
        body.setTextColor(theme.textDim);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        body.setPadding(dp(20), dp(4), dp(20), dp(16));
        panel.addView(body);

        panel.addView(dialogButtons(dialog, s(R.string.st_reset), theme.accent, () -> {
            // releases the widget ids AND drops their records — leaving the
            // ids allocated would keep every provider updating a ghost
            DexWidgetHost.wipe(this);
            getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE).edit()
                    .remove(DesktopGrid.KEY_ITEMS)
                    .apply();
            DexPrefs.broadcast(this, DesktopGrid.KEY_ITEMS);
            Toast.makeText(this, s(R.string.st_reset_home_done), Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    /** What a factory reset can clear, and which stored keys that means. */
    private static final class ResetScope {
        final int label;
        final String[] keys;
        boolean checked = true;

        ResetScope(int label, String... keys) {
            this.label = label;
            this.keys = keys;
        }
    }

    private void showFactoryReset() {
        List<ResetScope> scopes = new ArrayList<>();
        scopes.add(new ResetScope(R.string.st_scope_desktop,
                DesktopGrid.KEY_ITEMS, DesktopGrid.KEY_WIDGETS));
        scopes.add(new ResetScope(R.string.st_scope_recents, LauncherActivity.KEY_RECENTS));
        scopes.add(new ResetScope(R.string.st_scope_theme, DexPrefs.KEY_THEME, DexPrefs.KEY_DARK,
                DexPrefs.KEY_PAPER_TEXTURE, DexPrefs.KEY_GRAIN, DexPrefs.KEY_GLASS,
                DexPrefs.KEY_BLUR, DexPrefs.KEY_TRANSPARENCY, DexPrefs.KEY_ROUNDING,
                DexPrefs.KEY_WALLPAPER, DexPrefs.KEY_WALL_DIM, DexPrefs.KEY_FONT,
                // with the effects it suppresses: putting the look back to
                // stock has to include the switch that was hiding it
                DexPrefs.KEY_PERF));
        scopes.add(new ResetScope(R.string.st_scope_windows, DexPrefs.KEY_LAUNCH_MODE,
                DexPrefs.KEY_WINDOW_SIZE, DexPrefs.KEY_ICON_SIZE));
        // KEY_MOUSE_MODE deliberately lives in the STREAM scope, not here: it
        // is a stream_* key, and runFactoryReset turns any one of those into a
        // blanket "cfg reset.all" on the PC — so putting it here would let
        // resetting the cursor wipe the resolution, bitrate and codec too.
        scopes.add(new ResetScope(R.string.st_scope_cursor, DexPrefs.KEY_CURSOR_STYLE,
                DexPrefs.KEY_CURSOR_SIZE, DexPrefs.KEY_CURSOR_COLOR,
                DexPrefs.KEY_CURSOR_OUTLINE, DexPrefs.KEY_CURSOR_SHADOW,
                DexPrefs.KEY_CURSOR_SPEED));
        // The touchpad keys ride in this scope for the same reason
        // KEY_MOUSE_MODE does, and it is not a preference: they are stream_*
        // keys, and runFactoryReset turns any one of those into a blanket
        // "cfg reset.all" on the PC. Left out of a scope entirely they would
        // be wiped on the PC by a stream reset while the phone kept its copy,
        // and the two sides would disagree with nothing to reconcile them.
        scopes.add(new ResetScope(R.string.st_scope_stream, DexPrefs.KEY_RESOLUTION,
                DexPrefs.KEY_BITRATE, DexPrefs.KEY_FPS, DexPrefs.KEY_CODEC,
                DexPrefs.KEY_ENCODER, DexPrefs.KEY_AUDIO, DexPrefs.KEY_CLIP_SYNC,
                DexPrefs.KEY_CLIP_HISTORY, DexPrefs.KEY_MOUSE_MODE,
                DexPrefs.KEY_GESTURES, DexPrefs.KEY_GESTURE_3UP,
                DexPrefs.KEY_GESTURE_3DOWN, DexPrefs.KEY_GESTURE_3LEFT,
                DexPrefs.KEY_GESTURE_3RIGHT, DexPrefs.KEY_GESTURE_3TAP));
        scopes.add(new ResetScope(R.string.st_scope_display, DexPrefs.KEY_DENSITY,
                DexPrefs.KEY_LANGUAGE));

        Dialog dialog = new Dialog(this);
        LinearLayout panel = dialogPanel(dialog, "⚠", theme.danger,
                s(R.string.st_factory_reset), s(R.string.st_factory_reset_sub));

        TextView warning = new TextView(this);
        warning.setText(s(R.string.st_factory_body));
        warning.setTextColor(theme.textDim);
        warning.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12.5f));
        warning.setPadding(dp(20), dp(2), dp(20), dp(12));
        panel.addView(warning);

        for (ResetScope scope : scopes) {
            panel.addView(checkRow(scope));
        }

        TextView footer = new TextView(this);
        footer.setText("ⓘ  " + s(R.string.st_factory_footer));
        footer.setTextColor(theme.textFaint);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        footer.setBackground(roundedFill(blend(theme.danger, 0x1A), 12));
        footer.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.setMargins(dp(20), dp(8), dp(20), dp(4));
        panel.addView(footer, footerLp);

        panel.addView(dialogButtons(dialog, s(R.string.st_reset), theme.danger,
                () -> runFactoryReset(scopes)));
        dialog.show();
    }

    private View checkRow(ResetScope scope) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(7), dp(20), dp(7));
        row.setBackground(tapBackground(0x00000000, theme.hover, 10));

        TextView box = new TextView(this);
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        boxLp.rightMargin = dp(12);
        row.addView(box, boxLp);

        TextView label = new TextView(this);
        label.setText(s(scope.label));
        label.setTextColor(theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Runnable paint = () -> {
            box.setText(scope.checked ? "✓" : "");
            box.setBackground(scope.checked
                    ? roundedFill(theme.danger, 6)
                    : roundedFill(theme.field, 6));
        };
        paint.run();
        row.setOnClickListener(v -> {
            scope.checked = !scope.checked;
            paint.run();
        });
        return row;
    }

    private void runFactoryReset(List<ResetScope> scopes) {
        SharedPreferences.Editor editor =
                getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE).edit();
        boolean density = false;
        boolean stream = false;
        boolean widgets = false;
        // Only when it was actually ON: the undo below is a write to the
        // phone's global animation scales, and a reset of a desktop that never
        // reduced quality has no business touching them.
        boolean perf = DexPrefs.getBool(this, DexPrefs.KEY_PERF, DexPrefs.DEF_PERF);
        boolean clearingPerf = false;
        // Same story one setting along: a pointer speed this desktop pushed
        // lives on the PHONE and outlives the pref that asked for it, so a
        // reset that drops the pref has to tell the PC to put it back. Only
        // when it was actually moved — a reset of a desktop that never touched
        // the speed has no business writing the phone's own value away.
        boolean speed = DexPrefs.getInt(this, DexPrefs.KEY_CURSOR_SPEED,
                DexPrefs.DEF_CURSOR_SPEED) != DexPrefs.DEF_CURSOR_SPEED;
        boolean clearingSpeed = false;
        for (ResetScope scope : scopes) {
            if (!scope.checked) continue;
            for (String key : scope.keys) {
                editor.remove(key);
                if (DexPrefs.KEY_DENSITY.equals(key)) density = true;
                if (key.startsWith(DexPrefs.PC_PREFIX)) stream = true;
                if (DesktopGrid.KEY_WIDGETS.equals(key)) widgets = true;
                if (DexPrefs.KEY_PERF.equals(key)) clearingPerf = true;
                if (DexPrefs.KEY_CURSOR_SPEED.equals(key)) clearingSpeed = true;
            }
        }
        // release the widget ids while their records still exist — after
        // apply() the list is gone and the allocations would leak
        if (widgets) DexWidgetHost.wipe(this);
        editor.apply();
        if (stream) {
            // the desktop app keeps its own copy of these — clearing only the
            // phone's would leave the two disagreeing about what is applied
            RequestProvider.enqueue("cfg", "reset.all");
        }
        if (speed && clearingSpeed) {
            RequestProvider.enqueue("cursor", "speed." + DexPrefs.DEF_CURSOR_SPEED);
        }
        if (perf && clearingPerf) {
            // The pref is gone, so nothing will ask for the mode again — but
            // the animation scales it set live on the PHONE, not in these
            // prefs, and only the PC can put them back. Without this the reset
            // leaves a device whose animations are off and no switch that
            // admits to it.
            RequestProvider.enqueue("perf", "off");
        }
        if (density) {
            // back to the "Default" preset for this display, the same value a
            // never-configured desktop gets
            Point size = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            RequestProvider.enqueue("density", String.valueOf(defaultDpi(size.x, size.y)));
        }
        DexPrefs.broadcast(this, "*");
        Toast.makeText(this, s(R.string.st_factory_done), Toast.LENGTH_LONG).show();
        finish();
    }

    // ── dialog chrome ──

    private LinearLayout dialogPanel(Dialog dialog, String glyph, int tint,
                                     String title, String subtitle) {
        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextColor(tint);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        icon.setGravity(Gravity.CENTER);
        return dialogPanel(dialog, icon, tint, title, subtitle);
    }

    /** Same header with a drawable — the dialog half of the icon {@link #actionRow} uses. */
    private LinearLayout dialogPanel(Dialog dialog, int iconRes, int tint,
                                     String title, String subtitle) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(tint);
        int pad = dp(9);
        icon.setPadding(pad, pad, pad, pad);
        return dialogPanel(dialog, icon, tint, title, subtitle);
    }

    private LinearLayout dialogPanel(Dialog dialog, View icon, int tint,
                                     String title, String subtitle) {
        // both must precede setContentView below, or the framework throws
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window != null) {
            int fill = theme.paper ? 0xFF464d2d : theme.dark ? 0xFF161d29 : 0xFFffffff;
            window.setBackgroundDrawable(roundedFill(fill, 18));
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(18), 0, dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), 0, dp(20), dp(14));
        icon.setBackground(roundedFill(blend(tint, 0x2E), 12));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconLp.rightMargin = dp(14);
        head.addView(icon, iconLp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        texts.addView(name);
        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(theme.textFaint);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
        texts.addView(sub);
        head.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // scrollable so a small desktop (or a large display size) can still
        // reach the buttons at the bottom of the reset dialog
        ScrollView host = new ScrollView(this);
        host.setVerticalScrollBarEnabled(false);
        host.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(host, new ViewGroup.LayoutParams(
                Math.min(dp(430), getResources().getDisplayMetrics().widthPixels - dp(48)),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        DexFonts.applyTo(this, host);
        DexCursors.decorate(host);
        return panel;
    }

    private View dialogButtons(Dialog dialog, String confirmText, int confirmTint,
                               Runnable onConfirm) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(4));

        TextView cancel = new TextView(this);
        cancel.setText(s(R.string.st_cancel));
        cancel.setTextColor(theme.textDim);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        cancel.setBackground(tapBackground(0x00000000, theme.hover, 10));
        cancel.setOnClickListener(v -> dialog.dismiss());
        bar.addView(cancel);

        TextView confirm = new TextView(this);
        confirm.setText(confirmText);
        confirm.setTextColor(0xFFFFFFFF);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        confirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(dp(22), dp(10), dp(22), dp(10));
        confirm.setBackground(tapBackground(confirmTint, lighten(confirmTint), 10));
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmLp.leftMargin = dp(8);
        bar.addView(confirm, confirmLp);
        return bar;
    }

    private static int lighten(int color) {
        return Color.argb(Color.alpha(color),
                Math.min(255, Color.red(color) + 24),
                Math.min(255, Color.green(color) + 24),
                Math.min(255, Color.blue(color) + 24));
    }

    // ── display size ──

    /**
     * The "Default" preset for a display: 160dpi at 1080p, scaled by
     * resolution. Also the density LauncherActivity auto-applies to a fresh
     * desktop before the user makes any choice.
     */
    static int defaultDpi(int w, int h) {
        return Math.round(160f * Math.min(w, h) / 1080f);
    }

    /**
     * Carry the chosen display size across a resolution change.
     *
     * Display size is stored as an absolute dpi, but it MEANS a preset — 160dpi
     * is "Default" at 1080p and far too small at 1440p. Without this, picking a
     * new resolution would silently change how big everything looks. The stored
     * dpi is therefore recomputed for the resolution that is about to be
     * created, keeping the same preset.
     *
     * Written straight to prefs, with no `density` request: the display it
     * applies to does not exist yet. The launcher enqueues it on the next start
     * (see reapplyDensity), which is the one moment the new display is there.
     */
    private void rescaleDensityFor(String resolution) {
        String[] parts = resolution.split("x");
        if (parts.length != 2) return;
        try {
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            int index = Math.max(0, Math.min(densityIndex, SIZE_FACTORS.length - 1));
            int dpi = Math.round(defaultDpi(w, h) * SIZE_FACTORS[index]);
            DexPrefs.prefs(this).edit().putInt(DexPrefs.KEY_DENSITY, dpi).apply();
        } catch (NumberFormatException ignored) {
            // the list is ours and well-formed; a bad entry just means no rescale
        }
    }

    /** Preset dpi values, scaled so 1080p → {120, 140, 160, 200, 240}. */
    private int[] computePresets() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int base = defaultDpi(size.x, size.y);
        int[] out = new int[SIZE_FACTORS.length];
        for (int i = 0; i < SIZE_FACTORS.length; i++) {
            out[i] = Math.round(base * SIZE_FACTORS[i]);
        }
        return out;
    }

    /**
     * Preset to highlight: the stored choice, or — before any choice was
     * made — whichever preset is closest to the display's current density
     * (a fresh scrcpy display inherits the phone's ~340+ dpi, which reads
     * as "Very large").
     */
    private int selectedDensityIndex() {
        int stored = DexPrefs.getInt(this, DexPrefs.KEY_DENSITY, -1);
        int target = stored > 0 ? stored : getResources().getConfiguration().densityDpi;
        int best = 0;
        for (int i = 1; i < presetDpis.length; i++) {
            if (Math.abs(presetDpis[i] - target) < Math.abs(presetDpis[best] - target)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Persist the choice and ask the PC to apply it: the launcher has no
     * right to `wm density`, but the PC side drains this queue with adb's.
     * The resulting configuration change re-creates every activity on the
     * display, this window included, at the new scale.
     */
    private void applyDensity(int index) {
        if (index == densityIndex) return;
        densityIndex = index;
        DexPrefs.prefs(this).edit().putInt(DexPrefs.KEY_DENSITY, presetDpis[index]).apply();
        RequestProvider.enqueue("density", String.valueOf(presetDpis[index]));
        showSection(SEC_DISPLAY, false);
        Toast.makeText(this, s(R.string.st_display_size_applying), Toast.LENGTH_SHORT).show();
    }

    // ── the switch ──

    /** A track and a thumb that slides — a Switch we can colour to the theme. */
    private final class Toggle extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean on;
        /** 0 = left, 1 = right; animated so the flip reads as a movement. */
        private float pos;

        Toggle(Context ctx, boolean on) {
            super(ctx);
            this.on = on;
            this.pos = on ? 1f : 0f;
            setClickable(true);
        }

        boolean isOn() {
            return on;
        }

        void setOn(boolean value, boolean animate) {
            if (on == value) return;
            on = value;
            if (!animate) {
                pos = value ? 1f : 0f;
                invalidate();
                return;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(pos, value ? 1f : 0f);
            animator.setDuration(140);
            animator.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            track.set(0, 0, w, h);
            paint.setColor(blendColors(theme.field, theme.accent, pos));
            canvas.drawRoundRect(track, h / 2f, h / 2f, paint);

            float inset = h * 0.14f;
            float radius = h / 2f - inset;
            float cx = inset + radius + pos * (w - 2 * (inset + radius));
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, h / 2f, radius, paint);
        }
    }

    private static int blendColors(int from, int to, float t) {
        // the "off" colour is a translucent overlay; composite it on the card
        // first so the track does not fade through to whatever is behind
        return Color.argb(
                Math.round(Color.alpha(from) + (255 - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}
