package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Send to computer", in the phone's share menu (doc/send-to-computer.md, "The
 * share entry"): a small card that comes up from the bottom of the screen over
 * the app something was shared from, says where it goes and how it went, and
 * goes by itself. It is never a full screen.
 *
 * <p>Declared disabled: the computer's half on the phone enables it the first
 * time a computer that takes shares links, so a phone that only ever runs this
 * app on its own never grows an entry that can do nothing.
 *
 * <p>The card knocks on the computer's port ({@link SendQueue#knock}), queues
 * what was shared, and knocks again; the computer's half asks this app for it
 * and opens each file through {@link AppsProvider}. This activity must outlive
 * that opening — its permission to read what was shared ends with it — so a
 * tap outside the card does nothing until the files are open (Back and Cancel
 * take the share back, and say so by going); after that a tap lets the card go
 * and the transfer carries on. A rotation keeps the instance
 * (configChanges); a recreated one re-attaches to its share by number rather
 * than send it twice.
 *
 * <p>It follows the phone's language, not the desktop's choice: it sits over a
 * phone app, beside a share sheet in the phone's language.
 */
public class SendActivity extends Activity implements SendQueue.Listener {

    private static final String SAVED_SHARE = "share";
    /** Tries at the knock while nobody answers, over about 1.5 s. */
    private static final int KNOCKS = 3;
    private static final long KNOCK_GAP_MS = 500;
    /** From the knock to the computer taking it. */
    private static final long TAKE_MS = 4000;
    /** Without a word of progress while files go; and for text to be done. */
    private static final long SENDING_MS = 30_000;
    private static final long TEXT_MS = 5000;
    /** How long a card that said it worked stays. */
    private static final long DONE_MS = 1500;
    /** How long a taken share's files may take to open (a cloud file is fetched first) before its silence counts. */
    private static final long OPENING_MS = 5 * 60_000;
    /** How long a share may wait its turn on the computer (as many files coming in as it takes at once). */
    private static final long WAITING_MS = 15 * 60_000;
    private static final int PREVIEW_PX = 512;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SendQueue.Share share;
    /** The share the watchdog was armed for. */
    private SendQueue.Share watched;
    private boolean finished;
    private long lastWord;

    // The card.
    private LinearLayout card;
    private TextView title;
    private TextView line;
    private ProgressBar bar;
    private LinearLayout buttons;
    private int ink;
    private int inkSoft;
    private int accent;

    private final Runnable watchdog = this::watch;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildCard();
        SendQueue.listen(this);
        int again = saved == null ? -1 : saved.getInt(SAVED_SHARE, -1);
        SendQueue.Share was = again > 0 ? SendQueue.get(again) : null;
        if (was != null) {
            share = was;
            changed(was);
            return;
        }
        start(getIntent());
    }

    /**
     * A second share while this card is still up: its task is brought forward
     * and the share handed to this instance. The first carries on by itself (it
     * is queued, or with the computer already); the card shows the new one.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        main.removeCallbacks(finishing);
        main.removeCallbacks(watchdog);
        finished = false;
        share = null;
        bar.setVisibility(View.GONE);
        start(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (share != null && share.id > 0) out.putInt(SAVED_SHARE, share.id);
    }

    @Override
    protected void onDestroy() {
        SendQueue.unlisten(this);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    /** A tap outside the card: see {@link #hide}. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) hide();
        return true;
    }

    // ── what was shared ──

    /** What a share intent carries, read defensively: any app may send anything here. */
    static final class Parsed {
        final List<Uri> uris = new ArrayList<>();
        String text;
        String subject;
    }

    /**
     * The share's files and text. EXTRA_STREAM is read as whatever it is — a
     * Uri, a list, an array — and only real Uris are kept: an app that puts
     * Strings or another Parcelable there (or one this app cannot unparcel)
     * must not take the launcher down with it. Text with no EXTRA_STREAM is a
     * text share even when ClipData carries a picture: a browser's Share puts
     * the page's preview there, and the link is what was meant. ClipData's Uris
     * count only when there is neither a stream nor text.
     */
    static Parsed parse(Intent intent) {
        Parsed p = new Parsed();
        if (intent == null) return p;
        try {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) p.text = t.toString();
        } catch (RuntimeException e) {
            DexLog.warn("send", "unreadable EXTRA_TEXT: " + e);
        }
        try {
            p.subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        } catch (RuntimeException ignored) {
        }
        boolean streamGiven = false;
        try {
            Bundle extras = intent.getExtras();
            @SuppressWarnings("deprecation")
            Object stream = extras == null ? null : extras.get(Intent.EXTRA_STREAM);
            streamGiven = stream != null;
            if (stream instanceof Uri) {
                p.uris.add((Uri) stream);
            } else if (stream instanceof java.util.Collection) {
                for (Object o : (java.util.Collection<?>) stream) if (o instanceof Uri) p.uris.add((Uri) o);
            } else if (stream instanceof Object[]) {
                for (Object o : (Object[]) stream) if (o instanceof Uri) p.uris.add((Uri) o);
            }
        } catch (RuntimeException e) {
            // BadParcelableException and the like: a stream this app cannot even read.
            streamGiven = true;
            DexLog.warn("send", "unreadable EXTRA_STREAM: " + e);
        }
        boolean hasText = p.text != null && !p.text.trim().isEmpty();
        if (!streamGiven && !hasText) {
            try {
                ClipData clip = intent.getClipData();
                for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) p.uris.add(u);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return p;
    }

    private void start(Intent intent) {
        say(getString(R.string.sc_looking), null, false);
        setButtons(new String[]{getString(R.string.sc_cancel)}, new Runnable[]{this::cancel});
        Parsed parsed = parse(intent);
        List<Uri> uris = parsed.uris;
        String text = parsed.text;
        String subject = parsed.subject;
        // Only what may be read as the app that shared it: never a file:// path, never our own.
        List<Uri> usable = new ArrayList<>();
        for (Uri u : uris) {
            if (ContentResolver.SCHEME_CONTENT.equals(u.getScheme()) && !ours(u.getAuthority(), getPackageName())) {
                usable.add(u);
            }
        }
        SendQueue.Share s = new SendQueue.Share();
        if (!usable.isEmpty()) {
            if (usable.size() > SendQueue.ITEMS_MAX) {
                fail(getString(R.string.sc_too_many, SendQueue.ITEMS_MAX), null, false);
                return;
            }
            // Files and text together: the files. The text is dropped (the design record says so).
            s.kind = "files";
        } else if (text != null && !text.trim().isEmpty()) {
            if (text.length() > SendQueue.TEXT_MAX) {
                fail(getString(R.string.sc_too_much_text), null, false);
                return;
            }
            s.kind = "text";
            s.text = text;
            s.subject = subject;
        } else {
            DexLog.step("send", "nothing here to send (" + uris.size() + " stream(s), none this app may read)");
            fail(getString(R.string.sc_nothing), null, false);
            return;
        }
        share = s;
        final List<Uri> files = usable;
        new Thread(() -> send(s, files), "send-card").start();
    }

    /**
     * Whether {@code authority} is one of this app's own providers, as the
     * system resolves it: without a user id in front — {@code 0@authority}
     * reaches the same provider (ContentProvider.getAuthorityWithoutUserId) —
     * and none named is none of ours.
     */
    static boolean ours(String authority, String pkg) {
        if (authority == null) return false;
        String plain = authority.substring(authority.lastIndexOf('@') + 1);
        return plain.startsWith(pkg);
    }

    /** On a worker: anyone there, then what it is, then the knock that says it waits. */
    private void send(SendQueue.Share s, List<Uri> files) {
        if (!present()) {
            main.post(() -> {
                if (share != s || s.cancelled) return;
                DexLog.step("send", "no computer is connected");
                fail(getString(R.string.sc_none_title), getString(R.string.sc_none_body), true);
            });
            return;
        }
        if (!files.isEmpty()) {
            if (files.size() > 1) main.post(() -> say(getString(R.string.sc_preparing), null, false));
            for (int i = 0; i < files.size(); i++) {
                if (s.cancelled) return;
                s.items.add(describe(i, files.get(i)));
            }
            if (s.items.size() == 1 && isPicture(s.items.get(0).mime)) s.preview = preview(s.items.get(0).uri);
        }
        if (s.cancelled) return;
        SendQueue.offer(s);
        lastWord = SystemClock.uptimeMillis();
        try {
            SendQueue.knock(SendQueue.KNOCK_SHARE);
        } catch (IOException e) {
            // Gone between the two knocks, or a knock refused for another reason: the wait says which.
            DexLog.warn("send", "the second knock: " + e);
        }
        main.post(() -> armWatch(s, TAKE_MS));
    }

    /** Whether the computer's half is listening: three knocks over about 1.5 s; only "refused" is nobody. */
    private boolean present() {
        for (int i = 0; i < KNOCKS; i++) {
            try {
                if (SendQueue.knock(SendQueue.KNOCK_PRESENT)) return true;
            } catch (ConnectException e) {
                SystemClock.sleep(KNOCK_GAP_MS);
            } catch (IOException e) {
                // Not refused — a timeout, a network that would not say: let the wait for the take decide.
                return true;
            }
        }
        return false;
    }

    private SendQueue.Item describe(int i, Uri uri) {
        SendQueue.Item it = new SendQueue.Item();
        it.i = i;
        it.uri = uri;
        ContentResolver cr = getContentResolver();
        try {
            it.mime = cr.getType(uri);
        } catch (RuntimeException ignored) {
        }
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int z = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) it.name = c.getString(n);
                if (z >= 0 && !c.isNull(z)) it.size = c.getLong(z);
            }
        } catch (RuntimeException ignored) {
            // a provider that answers no queries: named from its URI
        }
        if (it.name == null || it.name.isEmpty()) {
            String last = uri.getLastPathSegment();
            it.name = last == null || last.isEmpty() || TextUtils.isDigitsOnly(last) ? "file" : last;
            String ext = it.mime == null ? null : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it.mime);
            if (ext != null && it.name.lastIndexOf('.') <= 0) it.name = it.name + "." + ext;
        }
        return it;
    }

    private static boolean isPicture(String mime) {
        return mime != null && (mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp")
                || mime.equals("image/heic") || mime.equals("image/heif") || mime.equals("image/gif"));
    }

    /** A small JPEG of the picture, for the computer's notification: never the file itself. */
    private String preview(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_PX) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            float scale = Math.min(1f, PREVIEW_PX / (float) Math.max(b.getWidth(), b.getHeight()));
            Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true) : b;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 78, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ── how it goes ──

    @Override
    public void changed(SendQueue.Share s) {
        if (s != share || finished) return;
        lastWord = SystemClock.uptimeMillis();
        String computer = s.computer == null || s.computer.isEmpty() ? "?" : s.computer;
        JSONObject d = s.done;
        if (s.refused) {
            fail(getString(R.string.sc_off, computer), null, false);
            return;
        }
        if (d != null) {
            done(s, d, computer);
            return;
        }
        if (s.taken) {
            say(getString(s.waiting ? R.string.sc_waiting : R.string.sc_sending, computer), null, false);
            setButtons(new String[]{getString(R.string.sc_cancel)}, new Runnable[]{this::cancel});
            if ("files".equals(s.kind)) {
                int n = s.items.size();
                if (n > 1) line(getString(R.string.sc_count, Math.min(n, s.index + 1), n));
                if (s.total > 0) {
                    bar.setVisibility(View.VISIBLE);
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(1000, s.bytes * 1000 / s.total));
                }
            }
            armWatch(s, "text".equals(s.kind) ? TEXT_MS : SENDING_MS);
        }
    }

    /** The watchdog, for share {@code s} alone: a later share's card is not failed by an earlier one's timer. */
    private void armWatch(SendQueue.Share s, long ms) {
        // Posted for a share the card has since left (a second share came): nothing to arm.
        if (s != share || finished) return;
        watched = s;
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, ms);
    }

    private void done(SendQueue.Share s, JSONObject d, String computer) {
        main.removeCallbacks(watchdog);
        if (s.cancelled) {
            finishSoon(0);
            return;
        }
        String did = d.optString("did");
        int total = "files".equals(s.kind) ? s.items.size() : 1;
        int landed = d.optJSONArray("landed") == null ? 0 : d.optJSONArray("landed").length();
        int unread = s.failedToOpen;
        if (d.optBoolean("ok")) {
            switch (did) {
                case "opened":
                    succeed(getString(R.string.sc_opened, computer), null);
                    return;
                case "offered":
                    succeed(getString(R.string.sc_sent, computer), null);
                    return;
                case "clipboard":
                    succeed(getString(R.string.sc_copied, computer), getString(R.string.sc_copied_hint));
                    return;
                default:
                    if (landed >= total) {
                        succeed(getString(R.string.sc_saved, computer), null);
                    } else {
                        String why = unread > 0 ? getString(R.string.sc_failed_read, unread, total) : null;
                        fail(getString(R.string.sc_saved_some, landed, total, computer), why, false);
                    }
                    return;
            }
        }
        String message = d.optString("message");
        String why;
        switch (message) {
            case "lost":
                why = getString(R.string.sc_failed_lost, computer);
                break;
            case "space":
                why = getString(R.string.sc_failed_space, computer);
                break;
            case "unreadable":
                why = getString(R.string.sc_failed_read, total, total);
                break;
            case "busy":
                why = getString(R.string.sc_failed_busy, computer);
                break;
            case "stuck":
                why = getString(R.string.sc_failed_stuck);
                break;
            default:
                why = null;
        }
        fail(getString(R.string.sc_failed), why, false);
    }

    /** No word in the time it should take: what was waited for did not come. */
    private void watch() {
        SendQueue.Share s = share;
        if (s == null || s != watched || finished || s.done != null) return;
        if (!s.taken) {
            DexLog.step("send", "the computer did not take share " + s.id + " in time");
            SendQueue.cancel(s);
            fail(getString(R.string.sc_silent_title), getString(R.string.sc_silent_body), true);
            return;
        }
        // Taken, its files still being opened: a cloud file can take a while, and that is no silence.
        if ("files".equals(s.kind) && !s.opened && SystemClock.uptimeMillis() - s.takenAt < OPENING_MS) {
            armWatch(s, SENDING_MS);
            return;
        }
        // Waiting its turn on the computer behind other files: no silence either.
        if (s.waiting && SystemClock.uptimeMillis() - s.takenAt < WAITING_MS) {
            armWatch(s, SENDING_MS);
            return;
        }
        long quiet = SystemClock.uptimeMillis() - lastWord;
        long most = "text".equals(s.kind) ? TEXT_MS : SENDING_MS;
        if (quiet < most) {
            armWatch(s, most - quiet);
            return;
        }
        fail(getString(R.string.sc_silent_title), getString(R.string.sc_silent_body), false);
    }

    /** Back, or Cancel: taken back (the computer drops what it has of it), and the card goes. */
    private void cancel() {
        SendQueue.Share s = share;
        if (s != null && s.done == null && !s.cancelled) {
            SendQueue.cancel(s);
            DexLog.step("send", "share " + s.id + " taken back on the card");
        }
        finishSoon(0);
    }

    private void again() {
        Intent i = getIntent();
        share = null;
        bar.setVisibility(View.GONE);
        start(i);
    }

    // ── the card's words ──

    private void succeed(String t, String l) {
        say(t, l, true);
        setButtons(new String[0], new Runnable[0]);
        finishSoon(DONE_MS);
    }

    private void fail(String t, String l, boolean retry) {
        main.removeCallbacks(watchdog);
        say(t, l, true);
        if (retry) {
            setButtons(new String[]{getString(R.string.sc_try_again), getString(R.string.sc_close)},
                    new Runnable[]{this::again, () -> finishSoon(0)});
        } else {
            setButtons(new String[]{getString(R.string.sc_close)}, new Runnable[]{() -> finishSoon(0)});
        }
    }

    private void say(String t, String l, boolean end) {
        title.setText(t);
        line(l);
        if (end) bar.setVisibility(View.GONE);
    }

    private void line(String l) {
        line.setText(l == null ? "" : l);
        line.setVisibility(l == null || l.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * A tap outside the card. Before hand-over it does nothing: this activity
     * holds the share's read permission until the computer's half has the
     * files open, and a share hidden then would fail where nobody sees it (the
     * card says Cancel for that). After hand-over, or once the card has said
     * how it went, the card goes and the transfer carries on.
     */
    private void hide() {
        SendQueue.Share s = share;
        boolean handedOver = s != null && s.taken && ("text".equals(s.kind) || s.opened);
        boolean said = s == null || s.done != null || s.refused || s.cancelled;
        if (!handedOver && !said) return;
        finishSoon(0);
    }

    private final Runnable finishing = () -> {
        if (finished) return;
        finished = true;
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    };

    private void finishSoon(long ms) {
        main.removeCallbacks(finishing);
        main.postDelayed(finishing, ms);
    }

    // ── the card, built in code ──

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildCard() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int surface = dark ? 0xFF26272B : 0xFFFFFFFF;
        ink = dark ? 0xFFF1F2F4 : 0xFF17181B;
        inkSoft = dark ? 0xFFA7AAB2 : 0xFF5C5F67;
        accent = dark ? 0xFF56D27A : 0xFF1F8A47;

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> hide());
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(20), dp(18), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        card.setElevation(dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        head.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        title = new TextView(this);
        title.setTextColor(ink);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(12);
        head.addView(title, tl);
        card.addView(head);

        line = new TextView(this);
        line.setTextColor(inkSoft);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        line.setVisibility(View.GONE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(6);
        ll.leftMargin = dp(40);
        card.addView(line, ll);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 21) bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bl.topMargin = dp(10);
        bl.leftMargin = dp(40);
        card.addView(bar, bl);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btl.topMargin = dp(6);
        card.addView(buttons, btl);

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int side = dp(12);
        // On the phone's desktop the taskbar (52dp, an overlay of ours, no inset) sits along the bottom: above it.
        android.view.Display display = getWindowManager().getDefaultDisplay();
        final int above = display != null && display.getDisplayId() != android.view.Display.DEFAULT_DISPLAY ? dp(52 + 12) : dp(16);
        cl.setMargins(side, 0, side, above);
        // A wide screen (the phone's desktop, a tablet): a card, not a band.
        int widest = dp(480);
        if (getResources().getDisplayMetrics().widthPixels > widest + 2 * side) cl.width = widest;
        root.addView(card, cl);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) card.getLayoutParams();
            p.bottomMargin = above + insets.getSystemWindowInsetBottom();
            card.setLayoutParams(p);
            return insets;
        });
        setContentView(root);
        card.setTranslationY(dp(40));
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f).setDuration(180).start();
    }

    private void setButtons(String[] labels, Runnable[] actions) {
        buttons.removeAllViews();
        buttons.setVisibility(labels.length == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextColor(i == 0 && labels.length > 1 ? accent : (labels.length == 1 ? accent : inkSoft));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            Runnable a = actions[i];
            b.setOnClickListener(v -> a.run());
            buttons.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}
