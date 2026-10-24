package com.ccrstech.openandroiddex.wmd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

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
 *
 * <p>It also lends itself to typing ({@link #lend}): characters no key types
 * reach an app as a paste, and the clipboard is given back what it held.
 */
final class AppClipboard {

    interface Listener {
        void copied(String text);
    }

    /** The label a computer's text carries on the phone, where any app may read it. */
    private static final String LABEL = "Open Android DeX";
    /**
     * How long typed text stays on the clipboard after its paste: a web page
     * takes its paste a moment after the key, from its own process.
     */
    private static final long LOAN_MS = 1000;

    private final ClipboardManager clipboard;
    private final Handler handler;
    private volatile Listener listener;
    /** What the computer gave, so its arrival is not sent back up as a copy. */
    private volatile String given;
    /** Typed text on loan to a paste, what the clipboard held before, and when it is given back. */
    private String lent;
    private ClipData lentFrom;
    private long loanEndsAt;

    private AppClipboard(ClipboardManager clipboard, Handler handler) {
        this.clipboard = clipboard;
        this.handler = handler;
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
            AppClipboard c = new AppClipboard(make.newInstance(PhoneContext.get(), handler), handler);
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
    synchronized void set(String text) {
        if (text == null || text.isEmpty()) return;
        // What the person copied replaces any loan: it is not given back over.
        lent = null;
        lentFrom = null;
        if (text.equals(read())) return;
        put(ClipData.newPlainText(LABEL, text), text);
    }

    /**
     * Hold {@code text} for one paste — {@code paste} injects it — then give
     * the clipboard back what it held. Neither change goes up to the computer,
     * and a run of loans gives back what was there before the first.
     */
    synchronized void lend(String text, Runnable paste) {
        if (lent == null) lentFrom = clip();
        lent = text;
        put(ClipData.newPlainText(LABEL, text), text);
        paste.run();
        loanEndsAt = SystemClock.uptimeMillis() + LOAN_MS;
        handler.postDelayed(this::giveBackWhenDue, LOAN_MS);
    }

    /** The person pastes: what they copied, not text still on loan. */
    synchronized void giveBackNow() {
        if (lent != null) giveBack();
    }

    private synchronized void giveBackWhenDue() {
        if (lent != null && SystemClock.uptimeMillis() >= loanEndsAt) giveBack();
    }

    private void giveBack() {
        String was = lent;
        ClipData from = lentFrom;
        lent = null;
        lentFrom = null;
        // Copied over meanwhile, on the phone: that stays.
        if (!was.equals(read())) return;
        if (from != null) {
            put(from, textOf(from));
        } else if (Build.VERSION.SDK_INT >= 28) {
            try {
                clipboard.clearPrimaryClip();
            } catch (Throwable t) {
                AppServer.log("cannot clear the clipboard (" + t + ")");
            }
        }
    }

    /** Set the clipboard without saying so: {@code text} is not sent back up as a copy. */
    private void put(ClipData clip, String text) {
        try {
            given = text;
            clipboard.setPrimaryClip(clip);
        } catch (Throwable t) {
            AppServer.log("cannot set the clipboard (" + t + ")");
        }
    }

    private ClipData clip() {
        try {
            return clipboard.getPrimaryClip();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String textOf(ClipData clip) {
        if (clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).getText();
        return text == null ? null : text.toString();
    }
}
