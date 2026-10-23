package com.ccrstech.openandroiddex.wmd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Constructor;

/**
 * The phone's clipboard, for the computer's app windows: what the phone copies
 * goes up, what the computer copied comes down.
 *
 * <p>Only a process like this one can do it in the background. An app reads
 * the clipboard only while it has focus (Android 10 and later); the shell holds
 * READ_CLIPBOARD_IN_BACKGROUND, the permission that exists for exactly this.
 * Through the framework's ClipboardManager, made with {@link PhoneContext}: the
 * clipboard service's calls gained an argument in nearly every release, and each
 * release's ClipboardManager makes them the way that release wants.
 *
 * <p>One for the phone, not one per window: there is one clipboard. When to hand
 * what over is the computer's decision; this only says what the phone copied,
 * and sets what it is given. What it set itself is not said back.
 */
final class AppClipboard {

    interface Listener {
        void copied(String text);
    }

    /** The label a computer's text carries on the phone, where any app may read it. */
    private static final String LABEL = "Open Android DeX";

    private final ClipboardManager clipboard;
    private volatile Listener listener;
    /** What the computer gave, so its arrival is not sent back up as a copy. */
    private volatile String given;

    private AppClipboard(ClipboardManager clipboard) {
        this.clipboard = clipboard;
    }

    /** Null on a phone where the shell may not use the clipboard; the windows then have none. */
    static AppClipboard open() {
        try {
            HandlerThread thread = new HandlerThread("apps-clipboard");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            Constructor<ClipboardManager> make =
                    ClipboardManager.class.getDeclaredConstructor(Context.class, Handler.class);
            make.setAccessible(true);
            AppClipboard c = new AppClipboard(make.newInstance(PhoneContext.get(), handler));
            c.clipboard.addPrimaryClipChangedListener(c::changed);
            return c;
        } catch (Throwable t) {
            AppServer.log("no clipboard (" + t + ")");
            return null;
        }
    }

    void listen(Listener l) {
        listener = l;
    }

    private void changed() {
        String text = read();
        if (text == null || text.equals(given)) return;
        given = null;
        Listener l = listener;
        if (l != null) l.copied(text);
    }

    String read() {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            return text == null ? null : text.toString();
        } catch (Throwable t) {
            AppServer.log("cannot read the clipboard (" + t + ")");
            return null;
        }
    }

    /** The computer's text, for the next paste. */
    void set(String text) {
        if (text == null || text.isEmpty() || text.equals(read())) return;
        try {
            given = text;
            clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text));
        } catch (Throwable t) {
            AppServer.log("cannot set the clipboard (" + t + ")");
        }
    }
}
