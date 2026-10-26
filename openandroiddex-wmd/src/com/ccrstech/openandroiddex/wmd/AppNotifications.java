package com.ccrstech.openandroiddex.wmd;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The phone's notifications, for the computer (doc/phone-notifications.md):
 * each one that would have interrupted the phone's owner, as it arrives; each
 * one gone, as it goes; and what the computer's notification asks done with
 * one — opened, replied to, one of its own buttons pressed, a call answered.
 *
 * <p>A listener of the shell's own, registered with the notification manager
 * directly ({@code registerAsSystemService}) rather than bound as an app's
 * service, which is how an app has to read them. That call asks for
 * STATUS_BAR_SERVICE, and the shell holds it (a Galaxy A13 on Android 12, a
 * Redmi 9A on 11, the Android 16 emulator): nothing on the phone is granted,
 * and nothing is left changed when the computer goes — the registration dies
 * with this process. It is made only while the computer asks for
 * notifications ({@code notifs}), so a computer that has them off reads none.
 *
 * <p>What counts as news is the phone's own answer, not a policy of ours: a
 * notification the phone put silently in its shade — its importance below
 * default, Do Not Disturb holding it back, an update the app marked "alert
 * once" — is not news on the computer either. The repost rule is the
 * desktop's (the launcher's DexNotifications): what a notification SAYS is
 * compared, so a chat app rewriting its notification to show someone typing is
 * not a second alert, and a second message is. Ongoing ones (music, a
 * download, a VPN) are state, not news, and are not sent at all; a ringing
 * call is its own message, with its own answer and decline.
 *
 * <p>It listens for one computer's control connection — the one that asked —
 * and the server stops it when that connection goes ({@link AppServer}): a
 * computer that comes back asks again, so nothing is read for a computer that
 * is not there to hear it, or that turned notifications off meanwhile.
 *
 * <p>Everything the computer's buttons do fires a PendingIntent the posting app
 * made — its own reply, its own "Mark as read", its own content intent — and
 * fires it from here, as the shell, which may start activities from the
 * background and put them on any display: so the conversation opens in the
 * computer's window for that app, not on the phone's screen. Nothing here
 * places a call or sends a message no app asked for.
 *
 * <p>The listener's callbacks run on a thread of its own: an avatar to scale
 * and encode must never hold up the server's main looper, which the encoders'
 * callbacks share.
 */
final class AppNotifications extends NotificationListenerService {

    /** Where what the phone says goes: the computer's control connection, whichever it is now. */
    interface Sink {
        void send(JSONObject message);
    }

    /** The tag a test notification is posted with, so the computer can tell it for one. */
    static final String TEST_TAG = "openandroiddex-test";
    /** A test notification leaves the phone after this: it was for the computer's screen. */
    private static final long TEST_LINGER_MS = 12_000;
    /** The size an avatar is sent at: a toast's circle at 2x, and small. */
    private static final int AVATAR_PX = 128;
    /** A picture (a photo message, a screenshot) is sent no larger than this on its longer side. */
    private static final int PICTURE_PX = 720;
    /** How many of a conversation's last messages the computer is given. */
    private static final int MESSAGES = 3;
    /** After a reply from the computer, the app's own update of the notification is not news. */
    private static final long REPLY_ECHO_MS = 10_000;
    /** The same notification alerting again sooner than this is an update, not a second alert. */
    private static final long REALERT_MS = 1500;
    /** How long an app may start a service or an activity after one of its intents was fired. */
    private static final String ALLOW_MS = "10000";

    // CallStyle's own extras, by value: the constants are @hide, the bundle keys are not.
    private static final String EXTRA_ANSWER_INTENT = "android.answerIntent";
    private static final String EXTRA_DECLINE_INTENT = "android.declineIntent";
    private static final String EXTRA_HANG_UP_INTENT = "android.hangUpIntent";
    private static final String EXTRA_CALL_TYPE = "android.callType";
    private static final int CALL_TYPE_INCOMING = 1;
    private static final int CALL_TYPE_ONGOING = 2;
    private static final int CALL_TYPE_SCREENING = 3;

    /** WindowConfiguration's windowing modes: an app window here, or a desktop's window. */
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;

    private enum Call { NONE, RINGING, ONGOING }

    private final AppServer server;
    private final Sink sink;
    private final HandlerThread thread = new HandlerThread("apps-notifications");
    private final Handler handler;
    private volatile Context context;

    // The listener's thread only.
    /** What each notification last said: the repost filter. */
    private final Map<String, String> lastSaid = new HashMap<>();
    /** When each last alerted. */
    private final Map<String, Long> alertedAt = new HashMap<>();
    /** The keys the computer has been told of: only those are said to be gone. */
    private final Set<String> told = new HashSet<>();
    /**
     * The calls ringing now, so a dialer's update every second is not a new
     * call. More than one: a call on a messaging app can ring over a phone
     * call, and each must be said to have ended, or one rings on here.
     */
    private final Set<String> ringing = new HashSet<>();

    /** When the computer last replied to each key; written from the control connection's thread. */
    private final Map<String, Long> repliedAt = new ConcurrentHashMap<>();
    /** Stopped: a callback already on its way when it was says nothing to whoever the computer is now. */
    private volatile boolean stopped;

    private AppNotifications(AppServer server, Sink sink) {
        this.server = server;
        this.sink = sink;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Listening, until {@link #stop}; throws if the phone will not have the shell listen. */
    static AppNotifications start(AppServer server, Sink sink) throws Exception {
        AppNotifications n = new AppNotifications(server, sink);
        try {
            n.register();
        } catch (Exception e) {
            n.thread.quitSafely();
            throw e;
        }
        return n;
    }

    private void register() throws Exception {
        Looper looper = thread.getLooper();
        // The listener hands its callbacks to its context's main looper: this
        // one's is the listener's own thread.
        Context ctx = new ContextWrapper(PhoneContext.get()) {
            @Override
            public Looper getMainLooper() {
                return looper;
            }
        };
        context = ctx;
        Method register = NotificationListenerService.class.getMethod("registerAsSystemService",
                Context.class, ComponentName.class, int.class);
        try {
            register.invoke(this, ctx, new ComponentName(PhoneContext.SHELL_PACKAGE, AppNotifications.class.getName()), 0);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
        }
    }

    /**
     * No longer listening: the computer turned notifications off, the
     * connection that asked went, or the server is stopping.
     */
    void stop() {
        stopped = true;
        try {
            NotificationListenerService.class.getMethod("unregisterAsSystemService").invoke(this);
        } catch (Throwable t) {
            AppServer.log("notifications: could not stop listening (" + t + ")");
        }
        thread.quitSafely();
    }

    /** Everything again, for a computer that asked again while this listened: its first answer went missing. */
    void resync() {
        handler.post(() -> {
            if (connected) snapshot();
        });
    }

    // ── what the phone posts ──

    /** The notification manager has handed over its list: the listener's thread only. */
    private boolean connected;

    @Override
    public void onListenerConnected() {
        connected = true;
        // Everything already on the phone counts as seen, not as news: a
        // computer that turns notifications on is not shown the whole shade.
        for (StatusBarNotification sbn : active(null)) {
            Notification n = sbn.getNotification();
            if (n != null) lastSaid.put(sbn.getKey(), Saying.of(n, "").said());
        }
        snapshot();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap ranking) {
        try {
            posted(sbn, ranking);
        } catch (Throwable t) {
            AppServer.log("notifications: " + sbn.getPackageName() + ": " + t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking) {
        if (sbn == null) return;
        String key = sbn.getKey();
        lastSaid.remove(key);
        alertedAt.remove(key);
        if (ringing.remove(key)) send(new Msg("callEnded").key(key));
        if (told.remove(key)) send(new Msg("removed").key(key));
    }

    /** What the computer should have now: every notification it would show, none of them news. */
    private void snapshot() {
        JSONArray items = new JSONArray();
        JSONArray calls = new JSONArray();
        told.clear();
        ringing.clear();
        for (StatusBarNotification sbn : active(null)) {
            if (!forComputer(sbn)) continue;
            Call c = callOf(sbn);
            if (c == Call.RINGING) {
                ringing.add(sbn.getKey());
                calls.put(describeCall(sbn));
                continue;
            }
            if (c == Call.ONGOING || ongoing(sbn)) continue;
            try {
                items.put(describe(sbn, false, false));
                told.add(sbn.getKey());
            } catch (Throwable t) {
                AppServer.log("notifications: " + sbn.getPackageName() + ": " + t);
            }
        }
        send(new Msg("snapshot").put("items", items).put("calls", calls));
    }

    private void posted(StatusBarNotification sbn, RankingMap ranking) throws Exception {
        if (!forComputer(sbn)) return;
        String key = sbn.getKey();
        Call call = callOf(sbn);
        if (call == Call.RINGING) {
            if (ringing.add(key)) {
                AppServer.log("notifications: a call is ringing (" + sbn.getPackageName() + ")");
                send(new Msg("call").put("call", describeCall(sbn)));
            }
            return;
        }
        // Answered here or on the phone: it rings no more, and is an ongoing call now.
        if (ringing.remove(key)) send(new Msg("callEnded").key(key));
        if (call == Call.ONGOING || ongoing(sbn)) {
            // State, not news — and gone from the computer's list if it was there.
            if (told.remove(key)) send(new Msg("removed").key(key));
            return;
        }
        Notification n = sbn.getNotification();
        String said = Saying.of(n, "").said();
        boolean first = !lastSaid.containsKey(key);
        String before = lastSaid.put(key, said);
        boolean changed = first || !said.equals(before);
        if (!changed && told.contains(key)) return;  // a repost with nothing new to say
        boolean alert = changed && alerting(sbn, ranking, first);
        told.add(key);
        send(describe(sbn, alert, alert));
    }

    /**
     * Would the PHONE have interrupted its owner for this one? The computer
     * defers to that, because the owner already said: per-app importance in
     * Android's settings, Do Not Disturb with its exceptions, and the app's own
     * "alert once" on an update. Where the ranking cannot be read — an OEM
     * framework that answers differently — the answer is yes: a notification
     * shown that was not strictly needed is better than a feature that says
     * nothing.
     */
    private boolean alerting(StatusBarNotification sbn, RankingMap ranking, boolean first) {
        if (isTest(sbn)) return true;
        Notification n = sbn.getNotification();
        String key = sbn.getKey();
        long now = SystemClock.uptimeMillis();
        if (!first && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) return false;
        Long replied = repliedAt.get(key);
        if (replied != null && now - replied < REPLY_ECHO_MS) return false;
        // A conversation whose newest message is its owner's own — sent from
        // the phone, or a reply from the computer the app has added — is not news.
        if (Saying.fromSelf(n)) return false;
        Long last = alertedAt.get(key);
        if (last != null && now - last < REALERT_MS) return false;
        try {
            Ranking r = new Ranking();
            if (ranking != null && ranking.getRanking(key, r)) {
                if (r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return false;
                if (!r.matchesInterruptionFilter()) return false;
                if (r.isSuspended()) return false;
            }
        } catch (Throwable ignored) {
            // the phone would not say: shown
        }
        alertedAt.put(key, now);
        return true;
    }

    /**
     * Group summaries are left out because their children are in the same
     * list — a chat app posts one summary and one notification per
     * conversation, and showing both shows every message twice. The launcher's
     * own are about a desktop or a Linux the computer already shows.
     */
    private static boolean forComputer(StatusBarNotification sbn) {
        Notification n = sbn == null ? null : sbn.getNotification();
        if (n == null) return false;
        if (WmDaemon.LAUNCHER.equals(sbn.getPackageName())) return false;
        return (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    private static boolean ongoing(StatusBarNotification sbn) {
        return (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private static boolean isTest(StatusBarNotification sbn) {
        return PhoneContext.SHELL_PACKAGE.equals(sbn.getPackageName()) && TEST_TAG.equals(sbn.getTag());
    }

    /**
     * A call, decided narrowly rather than by category alone: a missed call and
     * a voicemail both carry CATEGORY_CALL and neither is ringing. CallStyle
     * (Android 12) says which it is outright (its call type: incoming, ongoing,
     * screening), and with its intents — counted only when there, since a
     * style may put its keys in the extras with nothing behind them. A
     * full-screen intent is how dialers said "ringing" before it.
     */
    private static Call callOf(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras == null ? new Bundle() : n.extras;
        switch (x.getInt(EXTRA_CALL_TYPE, 0)) {
            case CALL_TYPE_INCOMING:
                return Call.RINGING;
            case CALL_TYPE_ONGOING:
            case CALL_TYPE_SCREENING:
                return Call.ONGOING;
            default:
        }
        boolean category = Notification.CATEGORY_CALL.equals(n.category);
        if (intent(n, EXTRA_ANSWER_INTENT) != null) return Call.RINGING;
        if (category && n.fullScreenIntent != null) return Call.RINGING;
        if (category && (intent(n, EXTRA_HANG_UP_INTENT) != null || ongoing(sbn))) return Call.ONGOING;
        return Call.NONE;
    }

    /** One of CallStyle's own intents, if the notification has it. */
    private static PendingIntent intent(Notification n, String extra) {
        Object pi = n.extras == null ? null : n.extras.get(extra);
        return pi instanceof PendingIntent ? (PendingIntent) pi : null;
    }

    // ── what the computer is told ──

    /** One notification, as the computer shows it; its pictures only when it alerts. */
    private JSONObject describe(StatusBarNotification sbn, boolean alert, boolean pictures) throws Exception {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        String app = label(pkg);
        Saying s = Saying.of(n, app);
        Msg m = new Msg("posted").key(sbn.getKey())
                .put("pkg", pkg).put("app", app)
                .put("title", s.title).put("text", s.text)
                .put("when", n.when > 0 ? n.when : sbn.getPostTime())
                .put("category", n.category == null ? "" : n.category)
                .put("conversation", s.conversation)
                .put("clearable", sbn.isClearable())
                .put("open", n.contentIntent != null)
                .put("alert", alert)
                .put("actions", actions(n, false));
        if (isTest(sbn)) m.put("test", true);
        if (pictures) {
            String avatar = png(avatar(n), AVATAR_PX);
            if (avatar != null) m.put("avatar", avatar);
            String picture = jpeg(picture(n));
            if (picture != null) m.put("picture", picture);
        }
        return m.json;
    }

    /**
     * A ringing call, as the computer shows it: who, and what answers it.
     * CallStyle's own answer and decline say what they are ({@code answer},
     * {@code decline}). A dialer from before CallStyle has only its buttons, in
     * an order of its own — AOSP's puts Decline before Answer — so those go as
     * they are, each by its index, in its own words and the colour it gave
     * them: nothing here guesses which one connects the call.
     */
    private JSONObject describeCall(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        String app = label(pkg);
        Saying s = Saying.of(n, app);
        JSONObject j = new JSONObject();
        try {
            j.put("key", sbn.getKey()).put("pkg", pkg).put("app", app).put("title", s.title).put("text", s.text);
            boolean answer = intent(n, EXTRA_ANSWER_INTENT) != null;
            boolean decline = intent(n, EXTRA_DECLINE_INTENT) != null || intent(n, EXTRA_HANG_UP_INTENT) != null;
            if (answer || decline) j.put("answer", answer).put("decline", decline);
            else j.put("actions", actions(n, true));
            String avatar = png(avatar(n), AVATAR_PX);
            if (avatar != null) j.put("avatar", avatar);
        } catch (Exception ignored) {
        }
        return j;
    }

    /**
     * The app's own buttons, each with the index it has in the notification —
     * never a position in this list, which skips some: a click must fire the
     * button that was drawn, not the one after it. The system's own suggestions
     * (contextual actions: "Open Maps", a smart reply) are left out; they are
     * the phone's guesses, not the app's buttons. With {@code tints}, a
     * button's colour too, where its app gave its words one.
     */
    private static JSONArray actions(Notification n, boolean tints) throws Exception {
        JSONArray out = new JSONArray();
        if (n.actions == null) return out;
        for (int i = 0; i < n.actions.length; i++) {
            Notification.Action a = n.actions[i];
            if (a == null || a.title == null || a.actionIntent == null) continue;
            if (Build.VERSION.SDK_INT >= 29 && a.isContextual()) continue;
            JSONObject j = new JSONObject().put("i", i).put("label", a.title.toString());
            String tint = tints ? tint(a.title) : null;
            if (tint != null) j.put("tint", tint);
            RemoteInput[] inputs = a.getRemoteInputs();
            if (freeForm(inputs) != null) {
                j.put("reply", true);
                CharSequence hint = freeForm(inputs).getLabel();
                if (hint != null && hint.length() > 0) j.put("hint", hint.toString());
            }
            if (Build.VERSION.SDK_INT >= 28) j.put("semantic", a.getSemanticAction());
            out.put(j);
        }
        return out;
    }

    /**
     * The colour an app gave a button's words, as {@code #rrggbb}, if it gave
     * one: a dialer from before CallStyle colours its answer green and its
     * decline red (AOSP's {@code ForegroundColorSpan} over the whole label).
     */
    private static String tint(CharSequence title) {
        if (!(title instanceof Spanned)) return null;
        Spanned s = (Spanned) title;
        ForegroundColorSpan[] spans = s.getSpans(0, s.length(), ForegroundColorSpan.class);
        if (spans == null || spans.length == 0) return null;
        return String.format(Locale.ROOT, "#%06x", spans[0].getForegroundColor() & 0xffffff);
    }

    private static RemoteInput freeForm(RemoteInput[] inputs) {
        if (inputs == null) return null;
        for (RemoteInput r : inputs) {
            if (r != null && r.getAllowFreeFormInput()) return r;
        }
        return null;
    }

    private String label(String pkg) {
        try {
            String l = server.catalog().label(pkg);
            if (l != null && !l.isEmpty()) return l;
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    // ── what a notification says ──

    /**
     * A notification's title and text as a person reads them: a conversation
     * as its last few messages, each with who said it when there are more than
     * two people in it; an inbox as its lines; a long text whole.
     */
    static final class Saying {
        String title = "";
        String text = "";
        /** A group conversation's own name, when it has one. */
        String conversation = "";

        /** What the repost filter compares. */
        String said() {
            return title + "\n" + text;
        }

        static Saying of(Notification n, String app) {
            Saying s = new Saying();
            Bundle x = n.extras;
            if (x == null) x = new Bundle();
            s.title = str(x, Notification.EXTRA_TITLE);
            if (s.title.isEmpty()) s.title = str(x, Notification.EXTRA_TITLE_BIG);
            Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
            CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (messages != null && messages.length > 0) {
                s.conversation = str(x, Notification.EXTRA_CONVERSATION_TITLE);
                boolean group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || !s.conversation.isEmpty();
                StringBuilder b = new StringBuilder();
                for (int i = Math.max(0, messages.length - MESSAGES); i < messages.length; i++) {
                    if (!(messages[i] instanceof Bundle)) continue;
                    Bundle m = (Bundle) messages[i];
                    CharSequence text = m.getCharSequence("text");
                    if (text == null) continue;
                    String who = sender(m);
                    if (b.length() > 0) b.append('\n');
                    if (group && who != null) b.append(who).append(": ");
                    b.append(text.toString().trim());
                }
                s.text = b.toString();
                if (!s.conversation.isEmpty()) s.title = s.conversation;
            } else if (lines != null && lines.length > 0) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < lines.length && i < 4; i++) {
                    if (lines[i] == null) continue;
                    if (b.length() > 0) b.append('\n');
                    b.append(lines[i].toString().trim());
                }
                s.text = b.toString();
            } else {
                s.text = str(x, Notification.EXTRA_TEXT);
                String big = str(x, Notification.EXTRA_BIG_TEXT);
                if (big.length() > s.text.length()) s.text = big;
            }
            if (s.text.isEmpty()) s.text = str(x, Notification.EXTRA_SUB_TEXT);
            if (s.title.isEmpty()) s.title = app;
            return s;
        }

        /** Whether a conversation's newest message is its owner's own. */
        static boolean fromSelf(Notification n) {
            Bundle x = n.extras;
            Parcelable[] messages = x == null ? null : x.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages == null || messages.length == 0 || !(messages[messages.length - 1] instanceof Bundle)) {
                return false;
            }
            String who = sender((Bundle) messages[messages.length - 1]);
            // MessagingStyle's own rule: a message with no sender is the user's.
            if (who == null) return true;
            String self = null;
            if (Build.VERSION.SDK_INT >= 28) {
                Object me = x.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
                if (me instanceof android.app.Person && ((android.app.Person) me).getName() != null) {
                    self = ((android.app.Person) me).getName().toString();
                }
            }
            if (self == null) self = str(x, Notification.EXTRA_SELF_DISPLAY_NAME);
            return !self.isEmpty() && self.equals(who);
        }

        /** Who said one of MessagingStyle's messages: its Person from Android 9, its name before. */
        static String sender(Bundle m) {
            if (Build.VERSION.SDK_INT >= 28) {
                Object p = m.getParcelable("sender_person");
                if (p instanceof android.app.Person && ((android.app.Person) p).getName() != null) {
                    return ((android.app.Person) p).getName().toString();
                }
            }
            CharSequence s = m.getCharSequence("sender");
            return s == null ? null : s.toString();
        }

        static String str(Bundle x, String key) {
            CharSequence v = x.getCharSequence(key);
            return v == null ? "" : v.toString().trim();
        }
    }

    // ── its pictures ──

    /** Who it is from: a conversation's last sender's own picture, else the notification's large icon. */
    private Bitmap avatar(Notification n) {
        Icon icon = null;
        Bundle x = n.extras;
        Parcelable[] messages = x == null ? null : x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (Build.VERSION.SDK_INT >= 28 && messages != null && messages.length > 0
                && messages[messages.length - 1] instanceof Bundle) {
            Object p = ((Bundle) messages[messages.length - 1]).getParcelable("sender_person");
            if (p instanceof android.app.Person) icon = ((android.app.Person) p).getIcon();
        }
        if (icon == null) icon = n.getLargeIcon();
        return icon == null ? null : bitmap(icon);
    }

    /** A photo message's photo, a screenshot's screenshot: BigPictureStyle's picture. */
    private Bitmap picture(Notification n) {
        Bundle x = n.extras;
        if (x == null) return null;
        Object p = x.getParcelable(Notification.EXTRA_PICTURE);
        if (p instanceof Bitmap) return (Bitmap) p;
        if (Build.VERSION.SDK_INT >= 31) {
            Object i = x.getParcelable(Notification.EXTRA_PICTURE_ICON);
            if (i instanceof Icon) return bitmap((Icon) i);
        }
        return null;
    }

    private Bitmap bitmap(Icon icon) {
        try {
            Drawable d = icon.loadDrawable(context);
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap();
            }
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : AVATAR_PX;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : AVATAR_PX;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        } catch (Throwable t) {
            // An icon in a provider the shell may not read, a resource gone with an update.
            return null;
        }
    }

    private static String png(Bitmap b, int px) {
        if (b == null) return null;
        Bitmap s = b.getWidth() == px && b.getHeight() == px ? b : Bitmap.createScaledBitmap(b, px, px, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String jpeg(Bitmap b) {
        if (b == null) return null;
        float k = Math.min(1f, PICTURE_PX / (float) Math.max(b.getWidth(), b.getHeight()));
        Bitmap s = k >= 1f ? b : Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                Math.max(1, Math.round(b.getHeight() * k)), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ── what the computer's buttons do ──

    /**
     * Open what the notification is about, as tapping it on the phone would:
     * on {@code displayId} — the computer's window for the app, or a desktop,
     * {@code freeform} as a desktop's windows are — or, with -1, on the
     * phone's own screen. Null when it went; else why not.
     */
    String open(String key, int displayId, boolean freeform) {
        StatusBarNotification sbn = find(key);
        if (sbn == null) return "the notification has gone";
        PendingIntent content = sbn.getNotification().contentIntent;
        if (content == null) return "the notification opens nothing";
        String failed = fire(content, sbn.getPackageName(), null, displayId, freeform, "opened");
        if (failed == null) cancelIfAuto(sbn);
        return failed;
    }

    /**
     * A new window's app, started by its notification's own intent onto the
     * window's display, so it opens at the conversation rather than at its
     * front screen: true if it was. An intent that is no activity (a broadcast
     * that starts one itself) cannot be pointed at a display, so false, and
     * the window starts the app as it would — its task then on the window's
     * display, where the activity the broadcast starts lands too — and then
     * {@link #after} fires it.
     */
    boolean launch(String key, String pkg, int displayId) {
        StatusBarNotification sbn = find(key);
        if (sbn == null || !pkg.equals(sbn.getPackageName())) return false;
        PendingIntent content = sbn.getNotification().contentIntent;
        if (content == null || !isActivity(content, true)) return false;
        if (fire(content, pkg, null, displayId, false, "opened in its window") != null) return false;
        cancelIfAuto(sbn);
        return true;
    }

    /**
     * The notification's own intent, once its window's app is started there —
     * where {@link #launch} could not put it on the window's display, or was
     * not tried: a broadcast that starts an activity itself; an untrusted
     * display, which takes no activity another app asks onto it (Android 12);
     * Android 13, which aborts the app's intent from the shell while the app
     * is nowhere on screen. Fired with no display, with its app on screen in
     * the window now, the activity joins the app's task — there. Only after
     * the app is started: before, with no display, it would open on the
     * phone's own screen. True if it was fired.
     */
    boolean after(String key) {
        StatusBarNotification sbn = find(key);
        PendingIntent content = sbn == null ? null : sbn.getNotification().contentIntent;
        if (content == null) return false;
        if (fire(content, sbn.getPackageName(), null, -1, false, "opened in its window") != null) return false;
        cancelIfAuto(sbn);
        return true;
    }

    /**
     * One of the app's own buttons. {@code label} is what it said when the
     * computer showed it: a notification can be rewritten while its toast is
     * up, and a click must never land on a different button than the one read
     * — for a messaging app, "Mark as read" against "Delete". A button that
     * opens something opens it where {@link #open} would.
     */
    String action(String key, int index, String label, int displayId, boolean freeform) {
        StatusBarNotification sbn = find(key);
        Notification.Action a = button(sbn, index, label);
        if (a == null) return "the button has changed or gone";
        // Said by its index, not its words: a button can carry the message ("Copy 123456").
        return fire(a.actionIntent, sbn.getPackageName(), null, displayId, freeform, "button " + index);
    }

    /** A reply typed on the computer, into the button's own RemoteInput, as the phone's shade sends one. */
    String reply(String key, int index, String label, String text) {
        StatusBarNotification sbn = find(key);
        Notification.Action a = button(sbn, index, label);
        if (a == null) return "the reply button has changed or gone";
        RemoteInput[] inputs = a.getRemoteInputs();
        if (freeForm(inputs) == null) return "the button takes no reply";
        Bundle results = new Bundle();
        for (RemoteInput r : inputs) {
            if (r != null && r.getAllowFreeFormInput()) results.putCharSequence(r.getResultKey(), text);
        }
        Intent fill = new Intent();
        RemoteInput.addResultsToIntent(inputs, fill, results);
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
        repliedAt.put(key, SystemClock.uptimeMillis());
        return fire(a.actionIntent, sbn.getPackageName(), fill, -1, false, "a reply");
    }

    /** Swiped away, as the phone's own shade would — what is still running stays. */
    String dismiss(String key) {
        if (find(key) == null) return "the notification has gone";
        cancelNotification(key);
        return null;
    }

    /**
     * A ringing call answered or declined from the computer, on the phone —
     * where a call is taken. By CallStyle's own intent ({@code answer}), or,
     * for a dialer from before it, by the button the computer showed
     * ({@code index} at 0 or more), if it still says {@code label}: those
     * buttons come in an order of each dialer's own (AOSP's: Decline, then
     * Answer), and guessing which is which could connect a call the person
     * meant to refuse.
     */
    String call(String key, boolean answer, int index, String label) {
        StatusBarNotification sbn = find(key);
        if (sbn == null) return "the call has ended";
        String pkg = sbn.getPackageName();
        if (index >= 0) {
            Notification.Action a = button(sbn, index, label);
            if (a == null) return "the call's button has changed or gone";
            return fire(a.actionIntent, pkg, null, -1, false, "call button " + index);
        }
        Notification n = sbn.getNotification();
        String[] extras = answer
                ? new String[]{EXTRA_ANSWER_INTENT}
                : new String[]{EXTRA_DECLINE_INTENT, EXTRA_HANG_UP_INTENT};
        for (String extra : extras) {
            PendingIntent pi = intent(n, extra);
            if (pi != null) return fire(pi, pkg, null, -1, false, answer ? "answer" : "decline");
        }
        return "the call has no " + (answer ? "answer" : "decline") + " of its own";
    }

    /**
     * A notification from the phone, for a person to see one arrive: posted by
     * the shell, as {@code cmd notification post} does, with a tag that says it
     * is a test. It leaves the phone again after a while; the computer keeps
     * its own until it is dismissed there.
     */
    String test(String title, String text) {
        String out = AppTasks.exec(5, "cmd", "notification", "post", "-S", "bigtext", "-t", title, TEST_TAG, text);
        String said = out.toLowerCase(java.util.Locale.ROOT);
        if (said.contains("error") || said.contains("exception") || said.contains("unknown command")) return out.trim();
        handler.postDelayed(() -> {
            for (StatusBarNotification sbn : active(null)) {
                if (!isTest(sbn)) continue;
                told.remove(sbn.getKey());
                cancelNotification(sbn.getKey());
            }
        }, TEST_LINGER_MS);
        return null;
    }

    /**
     * Fire one of the app's own intents. An activity goes where it was asked
     * to — a display of the computer's, or the phone's screen — as the shell
     * may put it; anything else (a reply's broadcast, a service that marks a
     * message read) has the app let out of its background limits first, for a
     * few seconds, as the phone's own shade does when it fires one: without
     * that a service the app starts from it is refused.
     */
    private String fire(PendingIntent pi, String pkg, Intent fill, int displayId, boolean freeform, String what) {
        Bundle options = null;
        try {
            if (isActivity(pi, displayId >= 0)) {
                ActivityOptions o = ActivityOptions.makeBasic();
                if (displayId >= 0) {
                    o.setLaunchDisplayId(displayId);
                    try {
                        Refl.callSig(o, "setLaunchWindowingMode", new Class<?>[]{int.class}, freeform ? FREEFORM : FULLSCREEN);
                    } catch (Refl.WmError ignored) {
                        // the display's own default
                    }
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    // From Android 14 the app's activity starts from the background
                    // only on the sender's say-so. From 16, "allowed" lends only the
                    // sender's being visible, which the shell never is; its
                    // permission to (START_ACTIVITIES_FROM_BACKGROUND) comes with
                    // "allow always" — without it the Android 16 emulator refused a
                    // cold start of Messages ("Background activity launch blocked").
                    o.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                            ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                }
                options = o.toBundle();
            } else {
                allowBackground(pkg);
            }
            pi.send(context, 0, fill, null, null, null, options);
            // With no display, an activity joins its app's task wherever that is — the phone's screen, or a window's.
            AppServer.log("notifications: " + pkg + ": " + what
                    + (displayId >= 0 ? " on display " + displayId : options != null ? ", with no display" : ""));
            return null;
        } catch (PendingIntent.CanceledException e) {
            return "the app took it back";
        } catch (Throwable t) {
            AppServer.log("notifications: " + pkg + ": " + what + " failed (" + t + ")");
            return String.valueOf(t.getMessage());
        }
    }

    /**
     * The app let out of its background limits for a few seconds — the power
     * manager's temporary allow-list, which the shell may put an app on. Its
     * output is not read: a {@code cmd} that hung would hold up every click
     * queued behind this one, so it is waited for a few seconds at most.
     */
    private static void allowBackground(String pkg) {
        try {
            Process p = new ProcessBuilder("cmd", "deviceidle", "tempwhitelist", "-d", ALLOW_MS, pkg)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
                    .start();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
        } catch (Exception e) {
            AppServer.log("notifications: " + pkg + " could not be let out of its background limits (" + e + ")");
        }
    }

    /**
     * Whether an intent starts an activity, or is a broadcast or a service:
     * public from Android 12, there (hidden) since 6. {@code otherwise} when
     * the platform will not say.
     */
    private static boolean isActivity(PendingIntent pi, boolean otherwise) {
        try {
            if (Build.VERSION.SDK_INT >= 31) return pi.isActivity();
            return (Boolean) PendingIntent.class.getMethod("isActivity").invoke(pi);
        } catch (Throwable t) {
            return otherwise;
        }
    }

    /** Tapping a notification on the phone dismisses it when its app said so: here too. */
    private void cancelIfAuto(StatusBarNotification sbn) {
        if ((sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) cancelNotification(sbn.getKey());
    }

    /** The button at {@code index}, if it still says {@code label}. */
    private static Notification.Action button(StatusBarNotification sbn, int index, String label) {
        if (sbn == null) return null;
        Notification.Action[] all = sbn.getNotification().actions;
        if (all == null || index < 0 || index >= all.length) return null;
        Notification.Action a = all[index];
        if (a == null || a.actionIntent == null || a.title == null) return null;
        if (label != null && !label.isEmpty() && !label.equals(a.title.toString())) return null;
        return a;
    }

    /** The notification behind a key now, or null: the app may have taken it back since it was shown. */
    private StatusBarNotification find(String key) {
        if (key == null || key.isEmpty()) return null;
        StatusBarNotification[] found = active(new String[]{key});
        return found.length > 0 ? found[0] : null;
    }

    private StatusBarNotification[] active(String[] keys) {
        try {
            StatusBarNotification[] all = keys == null ? getActiveNotifications() : getActiveNotifications(keys);
            return all == null ? new StatusBarNotification[0] : all;
        } catch (Throwable t) {
            return new StatusBarNotification[0];
        }
    }

    private void send(Msg m) {
        send(m.json);
    }

    private void send(JSONObject j) {
        if (!stopped) sink.send(j);
    }

    /** One message to the computer: {@code {"t":"notif","kind":…}}. */
    private static final class Msg {
        final JSONObject json = new JSONObject();

        Msg(String kind) {
            put("t", "notif").put("kind", kind);
        }

        Msg key(String key) {
            return put("key", key);
        }

        Msg put(String name, Object value) {
            try {
                json.put(name, value);
            } catch (org.json.JSONException ignored) {
                // a name and a value the JSON library takes
            }
            return this;
        }
    }
}
