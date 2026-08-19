package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Every user-facing preference the desktop shell has, in one place.
 *
 * The launcher, the desktop grid, the window captions and the Settings window
 * all read from here, so a setting is stored exactly once and there is a single
 * name for it. Writing through {@link #put} (rather than the SharedPreferences
 * editor directly) is what makes a change visible immediately: it fires
 * {@link #ACTION_CHANGED} inside our own package, and the surfaces that draw
 * themselves from these values rebuild on it.
 *
 * Values are deliberately primitive (int/bool/String) — they cross into
 * SharedPreferences, into a broadcast extra, and in one case (density) into an
 * adb command on the PC side.
 */
final class DexPrefs {

    private DexPrefs() {
    }

    /** Sent (package-local) whenever any setting below changes. */
    static final String ACTION_CHANGED = "com.ccrstech.openandroiddex.launcher.SETTINGS_CHANGED";
    /** Extra on {@link #ACTION_CHANGED}: the key that changed, or "*" for a bulk reset. */
    static final String EXTRA_KEY = "key";

    // ── Display & UI ──
    /**
     * "dark" | "light" | "paper". Newer than {@link #KEY_DARK}, which is still
     * read as the fallback so an upgrade keeps the theme the user was on.
     */
    static final String KEY_THEME = "ui_theme";
    static final String THEME_DARK = "dark";
    static final String THEME_LIGHT = "light";
    static final String THEME_PAPER = "paper";
    /** Superseded by {@link #KEY_THEME}; only read, never written any more. */
    static final String KEY_DARK = "ui_dark";
    /** Paper mode: which grain, and how much of it (0–100). */
    static final String KEY_PAPER_TEXTURE = "ui_paper_texture";
    static final String KEY_GRAIN = "ui_grain";
    static final String KEY_GLASS = "ui_glass";
    /** 0–100; blur radius behind our own panels, where the device supports it. */
    static final String KEY_BLUR = "ui_blur";
    /** 0–100; how see-through panels/taskbar/cards are. */
    static final String KEY_TRANSPARENCY = "ui_transparency";
    /** 0–100; corner-radius multiplier, 50 = the design's own radii. */
    static final String KEY_ROUNDING = "ui_rounding";
    /** Display dpi override — historically "display_density", kept for upgrades. */
    static final String KEY_DENSITY = "display_density";

    // ── Performance ──
    /**
     * "Reduce quality": trade the desktop's finish for frames.
     *
     * One switch with three separate consumers, because the cost of a frame is
     * spread over three places and no one of them owns it:
     * {@link DexTheme} drops blur, surface transparency and paper grain; the
     * launcher stops asking for a shadow under the taskbar and launches apps
     * without a transition; and the PC — the only side with the privilege —
     * zeroes the platform's animation scales and caps the stream's bitrate.
     *
     * Deliberately NOT a write over the glass/grain settings it suppresses.
     * The dialled-in values stay exactly where the user left them and come
     * back untouched when the switch goes off again.
     */
    static final String KEY_PERF = "perf_mode";
    static final boolean DEF_PERF = false;

    // ── Language & font ──
    /** BCP-47 tag, or "" to follow the phone. */
    static final String KEY_LANGUAGE = "ui_language";
    /** Key into {@link DexFonts}. */
    static final String KEY_FONT = "ui_font";

    // ── Wallpaper ──
    static final String KEY_WALLPAPER = "wallpaper";
    /** 0–80; black scrim painted over the wallpaper. */
    static final String KEY_WALL_DIM = "wallpaper_dim";

    // ── Windows & apps ──
    /** "cascade" | "center" | "maximized" */
    static final String KEY_LAUNCH_MODE = "window_launch_mode";
    /** "compact" | "standard" | "large" */
    static final String KEY_WINDOW_SIZE = "window_size";
    /** "small" | "medium" | "large" — desktop/drawer icon scale. */
    static final String KEY_ICON_SIZE = "icon_size";

    // ── Mouse & cursor ──
    // Everything here except KEY_MOUSE_MODE is drawn by us, on our own
    // windows, by DexCursors. The platform will not scale a custom pointer
    // bitmap for anyone, so a size is a re-render rather than a multiplier —
    // which is also why the size is a percentage and not a bucket.
    /** Key into {@link DexCursors#STYLES}; "system" leaves the platform's alone. */
    static final String KEY_CURSOR_STYLE = "cursor_style";
    /** 50–300; percent of the pointer's natural size. */
    static final String KEY_CURSOR_SIZE = "cursor_size";
    /** Key into {@link DexCursors#COLOURS}. */
    static final String KEY_CURSOR_COLOR = "cursor_color";
    /** Key into {@link DexCursors#OUTLINES}: the keyline around the shape. */
    static final String KEY_CURSOR_OUTLINE = "cursor_outline";
    static final String KEY_CURSOR_SHADOW = "cursor_shadow";
    /**
     * -7…7, the platform's own {@code Settings.System.pointer_speed} range.
     *
     * Stored here, applied by the PC over adb: it is a private setting the
     * launcher's uid may not write. Only bites while the PHONE draws the
     * pointer (uhid), where the mouse is relative and Android scales it — in
     * the default mode the motion is the computer's and this does nothing.
     */
    static final String KEY_CURSOR_SPEED = "cursor_speed";

    // ── Stream & clipboard ──
    // These are PC-side settings: the phone stores them so the UI can show
    // what is selected, but the value that matters is the copy pushed to the
    // desktop app through the request queue, which bakes it into scrcpy's
    // command line. Their keys carry the PC_PREFIX so the launcher knows not
    // to repaint itself over them.
    static final String PC_PREFIX = "stream_";
    static final String KEY_RESOLUTION = PC_PREFIX + "res";
    static final String KEY_BITRATE = PC_PREFIX + "bitrate";
    static final String KEY_FPS = PC_PREFIX + "fps";
    static final String KEY_CODEC = PC_PREFIX + "codec";
    static final String KEY_ENCODER = PC_PREFIX + "encoder";
    static final String KEY_AUDIO = PC_PREFIX + "audio";
    static final String KEY_CLIP_SYNC = PC_PREFIX + "clipboard";
    /**
     * "sdk" | "uhid" — which side of the cable draws the mouse pointer.
     *
     * The one setting in this section that decides whether the rest of it is
     * visible at all. Under scrcpy's default (sdk) the events are injected
     * below the stage that owns the pointer sprite, so Android draws no cursor
     * and the PC's own is what floats over the video; a PointerIcon set by any
     * app is computed and then discarded. Under uhid a virtual HID mouse
     * exists, Android draws the pointer into the stream, and everything
     * {@link DexCursors} renders shows up.
     */
    static final String KEY_MOUSE_MODE = PC_PREFIX + "mouse";
    // ── Touchpad gestures ──
    // Also PC-side, and pushed over the same queue — but unlike everything
    // above they are NOT scrcpy arguments. The desktop app reads them off disk
    // on each gesture, so a remapped swipe takes effect on the next swipe
    // rather than at the next session, and this section carries no restart
    // footer.
    static final String KEY_GESTURES = PC_PREFIX + "gestures";
    /** One per gesture. The suffix is the wire slot the desktop app looks up. */
    static final String KEY_GESTURE_3UP = PC_PREFIX + "gest3up";
    static final String KEY_GESTURE_3DOWN = PC_PREFIX + "gest3down";
    static final String KEY_GESTURE_3LEFT = PC_PREFIX + "gest3left";
    static final String KEY_GESTURE_3RIGHT = PC_PREFIX + "gest3right";
    static final String KEY_GESTURE_3TAP = PC_PREFIX + "gest3tap";

    /**
     * Whether the computer on the other end has a touchpad we can read.
     *
     * Not a setting and not pushed anywhere — it arrives on the PC's
     * running-apps broadcast and exists so the Settings window can dim the
     * gesture rows on a desktop PC instead of offering controls that cannot
     * do anything. Deliberately without the PC_PREFIX: it is never sent back.
     */
    static final String KEY_HOST_TOUCHPAD = "host_touchpad";

    /** JSON array, most recent first — see {@link DexClipboard}. Not a setting. */
    static final String KEY_CLIP_HISTORY = "clipboard_history";

    // ── Defaults ──
    static final boolean DEF_DARK = true;
    static final String DEF_PAPER_TEXTURE = PaperTexture.MATTE;
    /** Paperman dials 15–30%; ours is a wider range with a similar middle. */
    static final int DEF_GRAIN = 35;
    static final boolean DEF_GLASS = true;
    static final int DEF_BLUR = 60;
    static final int DEF_TRANSPARENCY = 20;
    static final int DEF_ROUNDING = 50;
    static final String DEF_WALLPAPER = "midnight";
    /** What Paper mode opens on — see {@link #wallpaper}. */
    static final String DEF_PAPER_WALLPAPER = "deckle";
    static final int DEF_WALL_DIM = 0;
    static final String DEF_LAUNCH_MODE = "cascade";
    static final String DEF_WINDOW_SIZE = "standard";
    static final String DEF_ICON_SIZE = "medium";
    static final String DEF_CURSOR_STYLE = DexCursors.STYLE_DEX;
    static final int DEF_CURSOR_SIZE = 100;
    static final String DEF_CURSOR_COLOR = "white";
    static final String DEF_CURSOR_OUTLINE = DexCursors.OUTLINE_CONTRAST;
    static final boolean DEF_CURSOR_SHADOW = true;
    static final int DEF_CURSOR_SPEED = 0;
    /** Stays scrcpy's own default: uhid captures the PC's mouse, so it is opt-in. */
    static final String DEF_MOUSE_MODE = "sdk";
    static final String DEF_FONT = "default";
    /** These four MUST match desktopOptions() in the desktop app's App.tsx. */
    static final String DEF_RESOLUTION = "1920x1080";
    static final int DEF_BITRATE = 8;
    static final int DEF_FPS = 0;                 // 0 = scrcpy decides
    static final boolean DEF_AUDIO = true;
    static final String DEF_CODEC = "auto";
    static final String DEF_ENCODER = "auto";
    static final boolean DEF_CLIP_SYNC = true;
    /**
     * The Samsung DeX three-finger set, which is also Windows 11's and macOS's
     * own — so a laptop user's existing habits keep working, pointed at Android
     * instead of at their computer.
     *
     * MUST match default_action() in the desktop app's gestures/mod.rs: that
     * side is what runs when nothing has been stored yet, and this side is
     * what the Settings window shows as selected.
     */
    static final boolean DEF_GESTURES = true;
    static final String DEF_GESTURE_3UP = "openapps";
    static final String DEF_GESTURE_3DOWN = "showdesktop";
    static final String DEF_GESTURE_3LEFT = "prevwindow";
    static final String DEF_GESTURE_3RIGHT = "nextwindow";
    static final String DEF_GESTURE_3TAP = "back";

    /**
     * False for settings the desktop app owns (stream, clipboard sync): the
     * launcher draws nothing from them, so repainting the shell when one
     * changes would be pure waste.
     */
    static boolean affectsShell(String key) {
        return key != null && !key.startsWith(PC_PREFIX) && !KEY_CLIP_HISTORY.equals(key)
                // The web viewer's own settings, for the same reason as the
                // PC's: nothing on this desktop is drawn from any of them, so
                // nudging the bitrate must not repaint the shell. See Web.
                && !key.startsWith(Web.PREFIX)
                // Reported BY the PC, never drawn by the shell.
                && !KEY_HOST_TOUCHPAD.equals(key)
                // Stored here, applied by adb on the phone. The shell paints
                // nothing from it, so repainting the whole desktop on every
                // nudge of the speed slider would be pure waste.
                && !KEY_CURSOR_SPEED.equals(key);
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE);
    }

    static boolean getBool(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    static int getInt(Context ctx, String key, int def) {
        return prefs(ctx).getInt(key, def);
    }

    static String getString(Context ctx, String key, String def) {
        String v = prefs(ctx).getString(key, def);
        return v == null ? def : v;
    }

    /**
     * Writes below are no-ops when the value is already stored. Several of
     * these keys make the shell (and the Settings window itself) rebuild, and
     * re-selecting the option that is already selected — or letting go of a
     * slider without having moved it — must not cost a rebuild.
     */
    static void put(Context ctx, String key, boolean value) {
        if (prefs(ctx).contains(key) && prefs(ctx).getBoolean(key, !value) == value) return;
        prefs(ctx).edit().putBoolean(key, value).apply();
        broadcast(ctx, key);
    }

    static void put(Context ctx, String key, int value) {
        if (prefs(ctx).contains(key) && prefs(ctx).getInt(key, ~value) == value) return;
        prefs(ctx).edit().putInt(key, value).apply();
        broadcast(ctx, key);
    }

    static void put(Context ctx, String key, String value) {
        if (value.equals(prefs(ctx).getString(key, null))) return;
        prefs(ctx).edit().putString(key, value).apply();
        broadcast(ctx, key);
    }

    /**
     * Tell the rest of the app a setting moved. Package-scoped on purpose:
     * these are our own surfaces (launcher, captions, Settings), and an
     * exported broadcast would let any app repaint the desktop.
     */
    static void broadcast(Context ctx, String key) {
        DexTheme.invalidate();
        DexCursors.invalidate();
        ctx.sendBroadcast(new Intent(ACTION_CHANGED)
                .setPackage(ctx.getPackageName())
                .putExtra(EXTRA_KEY, key));
    }

    /** Clamp a slider value that may have come from an older build or a bad write. */
    static int pct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /**
     * The selected theme, migrating the old boolean when this device has never
     * seen the three-way setting. Reading the old key rather than rewriting it
     * keeps the migration idempotent — the first write of KEY_THEME retires it.
     */
    static String theme(Context ctx) {
        String stored = getString(ctx, KEY_THEME, "");
        if (!stored.isEmpty()) return stored;
        return getBool(ctx, KEY_DARK, DEF_DARK) ? THEME_DARK : THEME_LIGHT;
    }

    /**
     * The wallpaper, whose DEFAULT depends on the theme: Paper opens on the
     * warm sheet it was designed against, everything else on Midnight.
     *
     * Deliberately a default rather than a write. Switching to Paper does not
     * touch a wallpaper the user picked on purpose, and switching back does not
     * strand them on a cream sheet under a dark theme — which is what happens
     * if a theme change assigns a wallpaper behind their back.
     */
    static String wallpaper(Context ctx) {
        return getString(ctx, KEY_WALLPAPER,
                THEME_PAPER.equals(theme(ctx)) ? DEF_PAPER_WALLPAPER : DEF_WALLPAPER);
    }
}
