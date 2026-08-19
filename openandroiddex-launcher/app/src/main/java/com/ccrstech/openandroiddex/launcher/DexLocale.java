package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The desktop's own language, independent of the phone's.
 *
 * A phone in Hindi and a desktop in English (or the reverse) is a normal thing
 * to want when the monitor is shared, so the shell carries its own locale
 * instead of inheriting the system one. It is applied by wrapping the base
 * context of every one of our components — that is the one mechanism that works
 * on every API level we support (per-app locales are API 33+), and it covers
 * resources, date formatting and layout direction in one go.
 *
 * Anything already inflated keeps the old language until it is rebuilt, which
 * is why the picker says so.
 */
final class DexLocale {

    private DexLocale() {
    }

    /** Tag, the name in that language, the name in English, and a region chip. */
    static final class Lang {
        final String tag;
        final String native_;
        final String english;
        final String region;

        Lang(String tag, String native_, String english, String region) {
            this.tag = tag;
            this.native_ = native_;
            this.english = english;
            this.region = region;
        }
    }

    /**
     * Offered languages. The first {@link #PRIMARY} are shown up front and the
     * rest sit behind the "show more" row, exactly as the picker renders them.
     * Every tag here has a matching {@code values-<tag>} translation.
     */
    static final Lang[] LANGUAGES = {
            new Lang("", "Automatic", "Follow the phone", "SYS"),
            new Lang("en", "English", "English", "GB"),
            new Lang("hi", "हिन्दी", "Hindi", "IN"),
            new Lang("gu", "ગુજરાતી", "Gujarati", "IN"),
            new Lang("pt", "Português", "Portuguese", "BR"),
            new Lang("es", "Español", "Spanish", "ES"),
            new Lang("fr", "Français", "French", "FR"),
            new Lang("de", "Deutsch", "German", "DE"),
            new Lang("ar", "العربية", "Arabic", "SA"),
            new Lang("zh", "中文", "Chinese", "CN"),
    };

    /** How many entries the picker shows before the "show more" row. */
    static final int PRIMARY = 4;

    /**
     * The phone's own locale, captured before we ever override the process
     * default. Switching back to "Automatic" has to restore this: JVM-level
     * formatting (dates in the taskbar clock) reads the default, and without a
     * way back it would stay in the language the user just left.
     */
    private static final Locale SYSTEM = Locale.getDefault();

    static Lang lang(String tag) {
        for (Lang l : LANGUAGES) {
            if (l.tag.equals(tag)) return l;
        }
        return LANGUAGES[0];
    }

    /**
     * Base context for an Activity/Service, in the chosen language. Returns the
     * original when the user is on "Automatic", so nothing is wrapped for free.
     */
    static Context wrap(Context base) {
        if (base == null) return null;
        String tag;
        try {
            tag = DexPrefs.getString(base, DexPrefs.KEY_LANGUAGE, "");
        } catch (Exception e) {
            return base;                       // prefs unavailable this early
        }
        if (tag.isEmpty()) {
            Locale.setDefault(SYSTEM);
            return base;
        }
        Locale locale = Locale.forLanguageTag(tag);
        if (locale.getLanguage().isEmpty()) return base;
        Locale.setDefault(locale);             // so SimpleDateFormat follows too
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }
}
