package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Font styles offered in Settings → Language &amp; Font.
 *
 * Two kinds of entry: the families every Android build ships (resolved by
 * name), and fonts that only exist on some devices — Samsung's Sharp Sans and
 * SEC Roboto, which are what makes a One UI phone look like a One UI phone.
 * Those are probed on disk and simply do not appear in the list when the file
 * is not there, so the picker never offers a font that would silently fall
 * back to the default.
 *
 * The chosen face is pushed over a finished view tree ({@link #applyTo}) rather
 * than threaded through every widget: the desktop builds its UI in code, and a
 * single walk at the end is both less invasive and impossible to forget at one
 * call site.
 */
final class DexFonts {

    private DexFonts() {
    }

    static final class Option {
        final String key;
        final String name;
        /** Where the face comes from, shown under the name. */
        final String detail;
        /** Non-null for on-disk fonts. */
        final String path;
        /** Non-null for built-in families. */
        final String family;

        private Option(String key, String name, String detail, String path, String family) {
            this.key = key;
            this.name = name;
            this.detail = detail;
            this.path = path;
            this.family = family;
        }

        static Option family(String key, String name, String detail, String family) {
            return new Option(key, name, detail, null, family);
        }

        static Option file(String key, String name, String detail, String path) {
            return new Option(key, name, detail, path, null);
        }
    }

    /** Device fonts worth offering, probed in order; first hit per key wins. */
    private static final String[][] DEVICE_FONTS = {
            {"sharpsans", "SamsungSharpSans", "One UI display font",
                    "/system/fonts/SamsungSharpSans-Bold.ttf",
                    "/system/fonts/SamsungSharpSans-Medium.ttf",
                    "/system/fonts/SamsungSharpSans-Regular.ttf"},
            {"secroboto", "SEC Roboto", "Samsung system font",
                    "/system/fonts/SECRobotoLight.ttf",
                    "/system/fonts/SamsungOne-400.ttf",
                    "/system/fonts/SamsungOneUI-Regular.ttf"},
            {"notoserif", "Noto Serif", "Serif system font",
                    "/system/fonts/NotoSerif-Regular.ttf"},
    };

    static List<Option> available() {
        List<Option> out = new ArrayList<>();
        out.add(Option.family("default", "Default", "System default", null));
        out.add(Option.family("condensed", "Condensed", "Narrower, fits more per row",
                "sans-serif-condensed"));
        out.add(Option.family("light", "Light", "Thinner strokes", "sans-serif-light"));
        out.add(Option.family("mono", "Monospace", "Fixed width", "monospace"));
        for (String[] probe : DEVICE_FONTS) {
            for (int i = 3; i < probe.length; i++) {
                if (new File(probe[i]).canRead()) {
                    out.add(Option.file(probe[0], probe[1], probe[2], probe[i]));
                    break;
                }
            }
        }
        return out;
    }

    static Option option(String key) {
        for (Option o : available()) {
            if (o.key.equals(key)) return o;
        }
        return null;
    }

    private static String cachedKey;
    private static Typeface cachedFace;

    /**
     * The user's face, or null when they are on the system default.
     *
     * Cached by key: this is asked for once per app tile and per popup, and
     * {@code Typeface.createFromFile} re-reads and parses the font file every
     * time it is called.
     */
    static Typeface typeface(Context ctx) {
        String key = DexPrefs.getString(ctx, DexPrefs.KEY_FONT, DexPrefs.DEF_FONT);
        if (key.equals(cachedKey)) return cachedFace;
        Option opt = DexPrefs.DEF_FONT.equals(key) ? null : option(key);
        cachedFace = opt == null ? null : typefaceOf(opt);
        cachedKey = key;
        return cachedFace;
    }

    static Typeface typefaceOf(Option opt) {
        try {
            if (opt.path != null) return Typeface.createFromFile(opt.path);
            if (opt.family != null) return Typeface.create(opt.family, Typeface.NORMAL);
        } catch (Exception ignored) {
            // a font that will not load is one we must not select
        }
        return null;
    }

    /** Re-face every TextView under {@code root}, keeping each one's style. */
    static void applyTo(View root, Typeface face) {
        if (face == null || root == null) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            Typeface current = tv.getTypeface();
            tv.setTypeface(face, current == null ? Typeface.NORMAL : current.getStyle());
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i), face);
            }
        }
    }

    static void applyTo(Context ctx, View root) {
        applyTo(root, typeface(ctx));
    }
}
