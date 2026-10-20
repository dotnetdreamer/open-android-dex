package com.ccrstech.openandroiddex.wmd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The phone's clipboard, read or set from the shell the way the desktop's own
 * stream does it (PhoneContext + the framework's ClipboardManager), for
 * test/dex-video-test.mjs --device: the far side of a copy, on the phone.
 *
 * Usage: ClipCheck get            → the clipboard's text, base64, on one line ("-": none)
 *        ClipCheck set <base64>   → "set"
 *        ClipCheck clear          → "cleared" — how the test puts an empty clipboard back
 */
public final class ClipCheck {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Constructor<ClipboardManager> make = ClipboardManager.class.getDeclaredConstructor(Context.class, Handler.class);
        make.setAccessible(true);
        ClipboardManager cm = make.newInstance(PhoneContext.get(), new Handler(Looper.getMainLooper()));
        if ("clear".equals(args[0])) {
            cm.clearPrimaryClip();
            System.out.println("cleared");
            return;
        }
        if ("set".equals(args[0])) {
            String text = new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8);
            cm.setPrimaryClip(ClipData.newPlainText("test", text));
            System.out.println("set");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        System.out.println(text == null ? "-" : Base64.getEncoder().encodeToString(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
