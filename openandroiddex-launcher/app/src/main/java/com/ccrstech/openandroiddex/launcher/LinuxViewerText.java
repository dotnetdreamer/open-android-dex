package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The Linux viewer page's words, in the Linux window's language.
 *
 * <p>The page (assets/linux/novnc: dex.html, dex.js) is served from inside the
 * guest, where Android's resources cannot reach it, and it is written in
 * English. So the window writes the words in its own language beside the page,
 * as {@code dex-text-<lang>.json}, from the {@code vw_*} strings (and two the
 * app already has), and adds {@code lang=<lang>} to the page's address. The
 * page loads them before it boots (dex.js, loadText). No JavaScript bridge:
 * the page comes from inside the guest, and nothing in there gets a way into
 * the app.
 *
 * <p>English, a language the app has no translation for, or a guest with no web
 * root yet: nothing is written and no lang is given, and the page stays as it
 * is written. A computer's page (ctx=pc) is never given one: the computer app
 * is in English.
 */
final class LinuxViewerText {

    private LinuxViewerText() {
    }

    /**
     * The page's key (data-t in dex.html, TEXT in dex.js) and the string for it.
     * Every key the page has is here; ViewerTextTest holds the two to each other.
     */
    static final Object[][] KEYS = {
            {"starting", R.string.vw_starting},
            {"connecting", R.string.vw_connecting},
            {"connected", R.string.vw_connected},
            {"reconnecting", R.string.vw_reconnecting},
            {"modeDirect", R.string.vw_mode_direct},
            {"modeTouch", R.string.vw_mode_touch},
            {"modeMouse", R.string.vw_mode_mouse},
            {"kbShow", R.string.vw_kb_show},
            {"kbHide", R.string.vw_kb_hide},
            {"fit", R.string.vw_fit},
            {"coachCollapse", R.string.vw_coach_collapse},
            {"coachBubble", R.string.vw_coach_bubble},
            {"toastMouse", R.string.vw_toast_mouse},
            {"toastTouch", R.string.vw_toast_touch},
            {"toastDirect", R.string.vw_toast_direct},
            {"toastCad", R.string.vw_toast_cad},
            {"toastMiddle", R.string.vw_toast_middle},
            {"toastRight", R.string.vw_toast_right},
            {"footDesktop", R.string.vw_foot_desktop},
            {"footTouch", R.string.vw_foot_touch},
            {"fullOn", R.string.vw_full_on},
            {"fullOff", R.string.vw_full_off},
            {"clipOn", R.string.vw_clip_on},
            {"clipOff", R.string.vw_clip_off},
            {"controls", R.string.vw_controls},
            {"mode", R.string.vw_mode},
            {"keyboard", R.string.vw_keyboard},
            {"keys", R.string.vw_keys},
            {"zoom", R.string.vw_zoom},
            {"clipboard", R.string.vw_clipboard},
            {"fullTitle", R.string.vw_full_title},
            {"hideBar", R.string.vw_hide_bar},
            {"zoomOut", R.string.vw_zoom_out},
            {"zoomIn", R.string.vw_zoom_in},
            {"zoomLevel", R.string.vw_zoom_level},
            {"showControls", R.string.vw_show_controls},
            {"dock", R.string.vw_dock},
            {"direct", R.string.vw_direct},
            {"directHint", R.string.vw_direct_hint},
            {"touch", R.string.vw_touch},
            {"touchHint", R.string.vw_touch_hint},
            {"mouse", R.string.vw_mouse},
            {"mouseHint", R.string.vw_mouse_hint},
            {"speed", R.string.vw_speed},
            {"speedHint", R.string.vw_speed_hint},
            {"scrolling", R.string.vw_scrolling},
            {"natural", R.string.vw_natural},
            {"reverse", R.string.vw_reverse},
            {"nextTap", R.string.vw_next_tap},
            {"middleClick", R.string.vw_middle_click},
            {"rightClick", R.string.vw_right_click},
            {"armHint", R.string.vw_arm_hint},
            {"gWorks", R.string.vw_g_works},
            {"gTap", R.string.vw_g_tap},
            {"gClick", R.string.vw_g_click},
            {"gTwoTap", R.string.vw_g_two_tap},
            {"gRightClick", R.string.vw_g_right_click},
            {"gDrag", R.string.vw_g_drag},
            {"gDragAct", R.string.vw_g_drag_act},
            {"gTwoFingers", R.string.vw_g_two_fingers},
            {"gScroll", R.string.vw_g_scroll},
            {"gWheel", R.string.vw_g_wheel},
            {"gLongTap", R.string.vw_g_long_tap},
            {"gOneFinger", R.string.vw_g_one_finger},
            {"gPan", R.string.vw_g_pan},
            {"gPinch", R.string.vw_g_pinch},
            {"gZoom", R.string.vw_g_zoom},
            {"gLongDrag", R.string.vw_g_long_drag},
            {"gSelect", R.string.vw_g_select},
            {"gMoveMouse", R.string.vw_g_move_mouse},
            {"gDoubleDrag", R.string.vw_g_double_drag},
            {"keyEsc", R.string.vw_key_esc},
            {"keyTab", R.string.vw_key_tab},
            {"keyCtrl", R.string.vw_key_ctrl},
            {"keyAlt", R.string.vw_key_alt},
            {"keySuper", R.string.vw_key_super},
            {"keyShift", R.string.vw_key_shift},
            {"keyLeft", R.string.vw_key_left},
            {"keyUp", R.string.vw_key_up},
            {"keyDown", R.string.vw_key_down},
            {"keyRight", R.string.vw_key_right},
            {"keyHome", R.string.vw_key_home},
            {"keyEnd", R.string.vw_key_end},
            {"keyPgUp", R.string.vw_key_pg_up},
            {"keyPgDn", R.string.vw_key_pg_dn},
            {"keyDel", R.string.vw_key_del},
            {"keyCad", R.string.vw_key_cad},
            {"remote", R.string.rm_label},
            {"close", R.string.sc_close},
    };

    /** The window's language as the page is given it, or null for the page's own English. */
    static String lang(Context ctx) {
        Locale l = ctx.getResources().getConfiguration().getLocales().get(0);
        String tag = l.getLanguage();
        if (tag.isEmpty() || "en".equals(tag)) return null;
        for (DexLocale.Lang known : DexLocale.LANGUAGES) {
            if (known.tag.equals(tag)) return tag;
        }
        return null;
    }

    /** Every key's words, from this context's resources, as the page reads them. */
    static String json(Context ctx) throws org.json.JSONException {
        JSONObject words = new JSONObject();
        for (Object[] k : KEYS) words.put((String) k[0], ctx.getString((Integer) k[1]));
        return words.toString();
    }

    /** The guest's web root, where LinuxService.stageViewer puts the page. */
    static File webRoot(Context ctx) {
        return new File(Linux.root(ctx), "rootfs/usr/share/novnc");
    }

    /**
     * Write this window's words beside the page.
     *
     * @return the lang to give the page, or null when it should stay English
     */
    static String stage(Context ctx) {
        String lang = lang(ctx);
        if (lang == null) return null;
        File dir = webRoot(ctx);
        if (!dir.isDirectory()) return null;
        File out = new File(dir, "dex-text-" + lang + ".json");
        File tmp = new File(dir, "dex-text-" + lang + ".json.tmp");
        try {
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(json(ctx).getBytes(StandardCharsets.UTF_8));
            }
            // A page that fetches it reads all of it or none of it.
            if (!tmp.renameTo(out) && !(out.delete() && tmp.renameTo(out))) {
                throw new java.io.IOException("cannot replace " + out);
            }
            return lang;
        } catch (Exception e) {
            tmp.delete();
            DexLog.warn("linux", "viewer: cannot write its words in " + lang + "; it stays in English", e);
            return null;
        }
    }
}
