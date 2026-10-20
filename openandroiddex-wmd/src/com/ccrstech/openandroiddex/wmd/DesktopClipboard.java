package com.ccrstech.openandroiddex.wmd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Constructor;

/**
 * The phone's clipboard, for the computer's window: what the phone copies goes
 * up, what the computer copied comes down.
 *
 * Only a process like this one can do it in the background. An app reads the
 * clipboard only while it has focus (Android 10 and later), which is why the
 * launcher's own DexClipboard cannot carry it; the shell holds
 * READ_CLIPBOARD_IN_BACKGROUND, the permission that exists for exactly this.
 * Through the framework's ClipboardManager, made with {@link PhoneContext}:
 * the clipboard service's calls gained an argument in nearly every release, and
 * each release's ClipboardManager makes them the way that release wants.
 *
 * When to hand what over is the window's decision (clipboardShare.ts, as for the
 * Linux desktop): this only says what the phone copied, and sets what it is
 * given. What it set itself is not said back.
 */
final class DesktopClipboard {

    interface Listener {
        void copied(String text);
    }

    /** The label a computer's text carries on the phone, where any app may read it. */
    private static final String LABEL = "Open Android DeX";

    private final ClipboardManager clipboard;
    private final Listener listener;
    /** What the computer gave, so its arrival is not sent back up as a copy. */
    private volatile String given;

    private DesktopClipboard(ClipboardManager clipboard, Listener listener) {
        this.clipboard = clipboard;
        this.listener = listener;
    }

    /** Null on a phone where the shell may not use the clipboard; the window then has none. */
    static DesktopClipboard open(Listener listener) {
        try {
            HandlerThread thread = new HandlerThread("desktop-clipboard");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            Constructor<ClipboardManager> make =
                    ClipboardManager.class.getDeclaredConstructor(Context.class, Handler.class);
            make.setAccessible(true);
            DesktopClipboard c = new DesktopClipboard(make.newInstance(PhoneContext.get(), handler), listener);
            c.clipboard.addPrimaryClipChangedListener(c::changed);
            return c;
        } catch (Throwable t) {
            System.out.println("desktop: no clipboard (" + t + ")");
            return null;
        }
    }

    private void changed() {
        String text = read();
        if (text == null || text.equals(given)) return;
        given = null;
        listener.copied(text);
    }

    private String read() {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            return text == null ? null : text.toString();
        } catch (Throwable t) {
            System.out.println("desktop: cannot read the clipboard (" + t + ")");
            return null;
        }
    }

    /**
     * The computer's text, for the next paste. Empty is the window taking its
     * text back — the switch turned off — which clears the phone's clipboard
     * only while it still holds that text, never something copied since.
     */
    void set(String text) {
        try {
            if (text.isEmpty()) {
                String had = given;
                given = null;
                if (had != null && had.equals(read())) clipboard.clearPrimaryClip();
                return;
            }
            given = text;
            clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text));
        } catch (Throwable t) {
            System.out.println("desktop: cannot set the clipboard (" + t + ")");
        }
    }
}
