package com.ccrstech.openandroiddex.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The phone's notifications, on the desktop.
 *
 * A phone plugged into a monitor is still a phone: calls come in, messages
 * arrive, a download finishes. None of that reached this desktop — the panel
 * they are drawn on belongs to the phone's own shell, which is not what is
 * being streamed, so the only way to see a notification was to unplug and look
 * at the handset. A call was the worst case: it rang somewhere off screen with
 * no way to answer it from the keyboard in front of you.
 *
 * A NotificationListenerService is the only way an ordinary app may read them,
 * and it comes with the one grant that also unlocks {@link DexMedia} — the
 * active media sessions are gated on a listener component, not on a permission
 * of their own. So this single switch is what puts both the notification centre
 * and the transport controls on the desktop.
 *
 * <b>Nothing here acts on the user's behalf.</b> Every button on the desktop
 * fires a {@link PendingIntent} the posting app itself created — its own
 * "Answer", its own "Reply", its own content intent — so answering a call from
 * the taskbar runs exactly the code the dialer's own notification would have
 * run. We hold no ANSWER_PHONE_CALLS, no CALL_PHONE and no SEND_SMS, and
 * deliberately never will: a desktop shell has no business being able to place
 * a call that no app asked it to.
 *
 * The service lives in the launcher's process (no {@code android:process} in
 * the manifest, unlike Linux and Docker) because its whole audience is in
 * there: the taskbar draws the flyout, the banner is one of the launcher's own
 * overlay windows, and both want the list without a round trip.
 */
public class DexNotifications extends NotificationListenerService {

    /**
     * The live service, or null while the grant is missing or the platform has
     * not bound us yet. Volatile because the UI reads it from the main thread
     * and the platform sets it from the binder's.
     */
    private static volatile DexNotifications live;

    /** True once the platform has bound and handed over the first list. */
    static boolean connected() {
        return live != null;
    }

    /**
     * Whether the desktop is showing notifications at all — the Settings
     * switch. Separate from {@link #connected()}: the grant is the phone's to
     * give and the switch is the user's to flip, and a user who wants the media
     * controls but not the notification list must be able to say so.
     */
    static boolean enabled(Context ctx) {
        return DexPrefs.getBool(ctx, DexPrefs.KEY_NOTIFICATIONS, DexPrefs.DEF_NOTIFICATIONS);
    }

    /** The component the grant names — also what {@link DexMedia} authenticates with. */
    static ComponentName component(Context ctx) {
        return new ComponentName(ctx, DexNotifications.class);
    }

    // ── the snapshot the desktop draws ─────────────────────────────────────

    /**
     * One notification, flattened.
     *
     * A copy rather than the {@link StatusBarNotification} itself, because the
     * UI outlives it: a flyout built from live objects goes stale the moment
     * the posting app updates the notification, and a {@code Notification}
     * holds bitmaps we would then be pinning for as long as a popup is open.
     * The key is what goes back to the platform for every action.
     */
    static final class Item {
        String key = "";
        String pkg = "";
        String title = "";
        String text = "";
        /** Wall time the app posted it — what the flyout's "5m" is measured from. */
        long when;
        /** The posting app's icon, for the row. Never the notification's own. */
        Drawable icon;
        /** Ongoing (a download, a foreground service): shown, but not dismissible. */
        boolean ongoing;
        boolean clearable;
        /** A ringing call: gets a banner and answer/decline buttons of its own. */
        boolean incomingCall;
        /** A call in progress — the banner offers to hang it up instead. */
        boolean ongoingCall;
        /** The app's own buttons, in the order it declared them. */
        final List<Action> actions = new ArrayList<>();

        /** Whether tapping the row opens anything. */
        boolean openable;
    }

    /**
     * One of the app's own buttons.
     *
     * The INDEX is carried rather than implied by this list's own position,
     * and that is not defensive tidiness: an action with no title is skipped
     * when the list is built, so from the first such action onward a
     * position-implied index names a different button than the one drawn —
     * "Mark as read" firing the app's "Delete". The label rides along so the
     * click can check the button is still the one that was drawn.
     */
    static final class Action {
        final String label;
        final int index;

        Action(String label, int index) {
            this.label = label;
            this.index = index;
        }
    }

    /**
     * The current list, newest first, ongoing last. Replaced wholesale on every
     * change rather than mutated, so a reader never sees a half-built list and
     * no lock is needed on the drawing side.
     */
    private static volatile List<Item> snapshot = Collections.emptyList();

    static List<Item> items() {
        return snapshot;
    }

    /** What the tray badge counts: everything the user could still act on. */
    static int badgeCount() {
        int n = 0;
        for (Item item : snapshot) {
            if (!item.ongoing) n++;
        }
        return n;
    }

    // ── who wants to know ──────────────────────────────────────────────────

    /**
     * Told whenever the list changes. Plain statics rather than a broadcast
     * because every side of this is the same process — the same reasoning as
     * {@link OwnWindows}, including the part that matters most: whoever
     * registers MUST unregister, or a density-driven rebuild of the desktop
     * leaves a dead listener behind on every pass.
     */
    interface Listener {
        void onNotificationsChanged();

        /**
         * Something arrived that is worth showing on its own, without the user
         * having opened anything.
         *
         * Separate from the list changing, and deliberately much rarer: the
         * list changes on every repost an app makes — a download ticking, a
         * chat app marking someone as typing — and a desktop that threw a card
         * on screen for each of those would be unusable. See
         * {@link DexNotifications#onNotificationPosted} for what survives it.
         */
        void onHeadsUp(Item item);

        /**
         * A call just started ringing. Separate again, because a call is not a
         * card in the corner: it gets a banner across the top with its own
         * buttons, and it gets it once, not on every repost.
         */
        void onIncomingCall(Item call);

        /** The call is over — answered, declined, or the caller gave up. */
        void onCallEnded(String key);
    }

    private static final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    static void setListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    static void clearListener(Listener l) {
        listeners.remove(l);
    }

    /**
     * The call the banner is currently up for, so a repost of the same
     * notification — dialers update theirs every second to tick the duration —
     * does not raise a second banner.
     */
    private static String ringingKey;

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onListenerConnected() {
        live = this;
        DexLog.step("notifications", "listener connected — the desktop can see the "
                + "phone's notifications and media sessions");
        rebuild();
        // Everything already on the phone counts as seen, not as news.
        //
        // Without this the desktop coming up is followed, seconds later, by a
        // card for every ongoing notification the phone happens to hold — a
        // music player, a VPN, a fitness tracker — because the first repost any
        // of them makes would look like a first sighting. The user did not just
        // receive those; they were already there.
        for (Item item : snapshot) {
            lastSaid.put(item.key, item.title + "\n" + item.text);
        }
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
        snapshot = Collections.emptyList();
        lastSaid.clear();
        DexLog.step("notifications", "listener disconnected");
        fanOut();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (live == this) live = null;
    }

    /**
     * Title + text of each notification as last seen, keyed by notification
     * key — the repost filter.
     *
     * Apps repost constantly and the platform hands every repost to
     * {@link #onNotificationPosted}: a download updates its percentage several
     * times a second, a chat app rewrites its notification when the other side
     * starts typing, a music app rewrites it every track. Every one of those is
     * a "posted" callback for a notification the user has already seen, and
     * popping a card for each is the difference between a feature and an
     * infestation. Comparing what it SAYS is the cheap test that survives all
     * of them, and still lets a second message from the same chat through.
     */
    private final java.util.Map<String, String> lastSaid = new java.util.HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Item item = toItem(sbn);
        rebuild();
        if (item == null) return;

        String said = item.title + "\n" + item.text;
        boolean firstSighting = !lastSaid.containsKey(item.key);
        String before = lastSaid.put(item.key, said);
        if (!firstSighting && said.equals(before)) return;  // a repost with nothing new to say

        // An ONGOING entry pops the first time it is seen, and never again.
        //
        // Both halves of that matter. A download starting, a route beginning, a
        // music app taking over playback — those ARE news, and suppressing them
        // outright (which this used to do) hid the very moment worth telling
        // the user about. But an ongoing notification is also the one kind that
        // legitimately rewrites itself forever: a download ticks its percentage
        // several times a second, and every tick is a real change in what it
        // says, so the repost filter above cannot catch them. First sighting is
        // the line between "this started" and "this is still going".
        //
        // Calls are excluded because they get the banner across the top
        // instead, not because they are unimportant.
        // Silent on purpose, unlike the branches below it: this is the one
        // that fires constantly (a download ticks its percentage several times
        // a second) and DexLog is a shared tag — see the note on that class.
        if (item.ongoing && !firstSighting) return;
        if (item.incomingCall || item.ongoingCall) {
            trace(item, "a call — the banner has it");
            return;
        }
        if (!interrupting(sbn)) {
            trace(item, "the phone itself would not have interrupted for it");
            return;
        }
        if (listeners.isEmpty()) {
            trace(item, "nothing is listening — no desktop is up");
            return;
        }
        DexLog.step("notifications", "pop-up: " + item.pkg + " — " + item.title);
        for (Listener l : listeners) l.onHeadsUp(item);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) lastSaid.remove(sbn.getKey());
        rebuild();
    }

    /**
     * Why a notification did NOT get a card.
     *
     * Every branch above says so, because "nothing appeared" has half a dozen
     * legitimate causes and no way to tell them apart from the outside — and
     * the one bug report this feature will ever get is exactly that sentence.
     */
    private void trace(Item item, String why) {
        DexLog.step("notifications", "no pop-up for " + item.pkg + ": " + why);
    }

    /**
     * Would the PHONE have interrupted for this one?
     *
     * The desktop defers to the phone's own answer rather than inventing a
     * policy, because the user already set that policy: per-app importance in
     * Android's settings, and Do Not Disturb. A notification the phone put
     * silently in the shade must not throw a card onto the monitor.
     *
     * IMPORTANCE_DEFAULT rather than the IMPORTANCE_HIGH the platform requires
     * for its own heads-up. Slightly more generous on purpose: a phone shows a
     * heads-up for a second or two over one small screen, whereas this corner
     * card sits on a monitor with room to spare, and the failure people
     * actually report is a notification they never saw.
     *
     * When the ranking cannot be read at all — an OEM framework that answers
     * differently, a race with the binding — the answer is yes. Better a card
     * that was not strictly required than a feature that silently does nothing.
     */
    private boolean interrupting(StatusBarNotification sbn) {
        try {
            RankingMap map = getCurrentRanking();
            Ranking ranking = new Ranking();
            if (map == null || !map.getRanking(sbn.getKey(), ranking)) return true;
            if (ranking.getImportance() < android.app.NotificationManager.IMPORTANCE_DEFAULT) {
                return false;
            }
            // Do Not Disturb, as the phone is currently configured — including
            // the exceptions the user allowed through it.
            return ranking.matchesInterruptionFilter();
        } catch (Exception e) {
            return true;
        }
    }

    // ── building the snapshot ──────────────────────────────────────────────

    private void rebuild() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Exception e) {
            // The platform throws here when the binding is being torn down.
            DexLog.warn("notifications", "cannot read the active notifications", e);
            return;
        }
        if (active == null) active = new StatusBarNotification[0];

        List<Item> next = new ArrayList<>(active.length);
        Item ringing = null;
        for (StatusBarNotification sbn : active) {
            Item item = toItem(sbn);
            if (item == null) continue;
            next.add(item);
            if (item.incomingCall && ringing == null) ringing = item;
        }
        // Newest first, with the ongoing ones (a download, a running container)
        // below — they are state, not news, and they do not go away on their own.
        Collections.sort(next, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.ongoing != b.ongoing) return a.ongoing ? 1 : -1;
                return Long.compare(b.when, a.when);
            }
        });
        // The count, whenever it moves. This is the line that answers "the bell
        // shows nothing": it distinguishes a listener that is bound and reading
        // an empty phone from one that is bound and filtering everything out.
        if (next.size() != snapshot.size()) {
            DexLog.step("notifications", next.size() + " showing on the desktop, of "
                    + active.length + " active on the phone");
        }
        snapshot = next;

        String wasRinging = ringingKey;
        ringingKey = ringing == null ? null : ringing.key;
        fanOut();
        if (ringing != null && !ringing.key.equals(wasRinging)) {
            final Item call = ringing;
            for (Listener l : listeners) l.onIncomingCall(call);
        } else if (ringing == null && wasRinging != null) {
            for (Listener l : listeners) l.onCallEnded(wasRinging);
        }
    }

    private void fanOut() {
        for (Listener l : listeners) l.onNotificationsChanged();
    }

    /**
     * Flatten one notification, or null for the ones the desktop has no
     * business showing.
     *
     * Group summaries are dropped because their children are in the same list:
     * a chat app posts one summary plus one notification per conversation, and
     * showing both means every message appears twice. Our own package is
     * dropped for a plainer reason — the Linux container's ongoing
     * notification and the Web viewer's are both about windows that are
     * already on this desktop, so repeating them in the tray is noise about
     * ourselves.
     */
    private Item toItem(StatusBarNotification sbn) {
        if (sbn == null) return null;
        Notification n = sbn.getNotification();
        if (n == null) return null;
        if (getPackageName().equals(sbn.getPackageName())) return null;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;

        Bundle extras = n.extras;
        Item item = new Item();
        item.key = sbn.getKey();
        item.pkg = sbn.getPackageName();
        item.when = n.when > 0 ? n.when : sbn.getPostTime();
        item.ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        item.clearable = sbn.isClearable();
        item.openable = n.contentIntent != null;
        if (extras != null) {
            item.title = str(extras, Notification.EXTRA_TITLE);
            if (item.title.isEmpty()) item.title = str(extras, Notification.EXTRA_TITLE_BIG);
            item.text = str(extras, Notification.EXTRA_TEXT);
            if (item.text.isEmpty()) item.text = str(extras, Notification.EXTRA_BIG_TEXT);
            if (item.text.isEmpty()) item.text = str(extras, Notification.EXTRA_SUB_TEXT);
        }
        if (item.title.isEmpty()) item.title = appLabel(item.pkg);
        item.icon = appIcon(item.pkg);
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                Notification.Action action = n.actions[i];
                if (action != null && action.title != null) {
                    item.actions.add(new Action(action.title.toString(), i));
                }
            }
        }

        // A call is the one notification worth interrupting for, so what counts
        // as one is decided narrowly rather than by category alone: a missed
        // call and a voicemail both carry CATEGORY_CALL and neither is ringing.
        //
        // CallStyle (API 31+) states it outright by putting its intents in the
        // extras, and READING A BUNDLE KEY is not the same as calling hidden
        // API — the constants behind these names are @hide, the bundle is not.
        // A full-screen intent is the pre-CallStyle way of saying the same
        // thing, and is what dialers on older phones still use.
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        if (extras != null && extras.containsKey(EXTRA_ANSWER_INTENT)) {
            item.incomingCall = true;
        } else if (call && n.fullScreenIntent != null) {
            item.incomingCall = true;
        } else if (call && extras != null && extras.containsKey(EXTRA_HANG_UP_INTENT)) {
            item.ongoingCall = true;
        } else if (call && item.ongoing) {
            item.ongoingCall = true;
        }
        return item;
    }

    /**
     * CallStyle's own extras. Spelled out rather than referenced because the
     * constants are {@code @hide}; the VALUES are the platform's wire format
     * and are what the bundle is actually keyed on.
     */
    private static final String EXTRA_ANSWER_INTENT = "android.answerIntent";
    private static final String EXTRA_DECLINE_INTENT = "android.declineIntent";
    private static final String EXTRA_HANG_UP_INTENT = "android.hangUpIntent";

    private static String str(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString().trim();
    }

    /**
     * Icon and label per package, resolved once.
     *
     * {@link #rebuild} runs on the main thread for every notification the phone
     * posts, and re-walks the whole active list each time — a chat app in a busy
     * group does that several times a second. Both lookups below are synchronous
     * PackageManager calls, and the icon one additionally inflates and caches an
     * adaptive-icon drawable, so doing them per notification per rebuild was
     * main-thread work proportional to traffic on a surface that must never
     * stutter: the desktop's own taskbar.
     *
     * Nothing evicts it. It is bounded by the number of distinct packages that
     * have posted a notification this session, and a stale entry — an app
     * updated mid-session, which changes an icon about as often as never — costs
     * a wrong picture until the next launch, not a wrong action.
     */
    private final java.util.Map<String, Drawable> iconCache = new java.util.HashMap<>();
    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();

    private String appLabel(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        labelCache.put(pkg, label);
        return label;
    }

    private Drawable appIcon(String pkg) {
        // containsKey, not a null check: a package we could not resolve stays
        // unresolvable, and re-asking on every rebuild is exactly the cost this
        // cache exists to avoid.
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        Drawable icon = null;
        try {
            icon = getPackageManager().getApplicationIcon(pkg);
        } catch (Exception ignored) {
        }
        iconCache.put(pkg, icon);
        return icon;
    }

    // ── what the desktop's buttons do ──────────────────────────────────────

    /**
     * The live {@link StatusBarNotification} behind a key, or null once the app
     * has taken it back. Every action below re-looks-up rather than holding on
     * to one: the flyout can sit open for minutes and a notification the user
     * is about to answer may already be gone.
     */
    private StatusBarNotification find(String key) {
        if (key == null) return null;
        try {
            StatusBarNotification[] active = getActiveNotifications(new String[]{key});
            if (active != null && active.length > 0) return active[0];
        } catch (Exception e) {
            DexLog.warn("notifications", "cannot look up " + key, e);
        }
        return null;
    }

    /**
     * Fire one of the app's own action buttons.
     *
     * @param expectedLabel what the button said when it was drawn, or null to
     *                      fire whatever is at {@code index} now. A flyout can
     *                      sit open across a repost — apps rewrite their
     *                      notifications constantly — and a repost may reorder
     *                      or replace the buttons under it. Checking the label
     *                      is what stops a click landing on a different button
     *                      than the one under the pointer, which for a
     *                      messaging app is the difference between "Mark as
     *                      read" and "Delete".
     * @return false when the notification is gone, the button has changed, or
     * the app's PendingIntent has already been cancelled — all three of which
     * are normal, and none of which is worth more than leaving the button be.
     */
    static boolean action(String key, int index, String expectedLabel) {
        DexNotifications self = live;
        StatusBarNotification sbn = self == null ? null : self.find(key);
        if (sbn == null) return false;
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null || index < 0 || index >= actions.length) return false;
        Notification.Action action = actions[index];
        if (action == null || action.actionIntent == null) return false;
        if (expectedLabel != null
                && (action.title == null || !expectedLabel.equals(action.title.toString()))) {
            DexLog.step("notifications", "not firing \"" + expectedLabel + "\" on "
                    + sbn.getPackageName() + " — the app has since changed that button");
            return false;
        }
        // A RemoteInput action (an inline reply) cannot be fired bare — the app
        // is waiting for text we have no field for. Opening the app is the
        // honest answer, and it is what the row's own tap already does.
        if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
            return open(key);
        }
        return send(action.actionIntent, sbn.getPackageName(), "action " + index);
    }

    /**
     * Answer a ringing call, through the dialer's own answer intent.
     *
     * The CallStyle extra first, then the notification's first button. Order
     * matters: an app that declares both must be driven through the intent it
     * built for exactly this, not through a button that happens to sit first.
     */
    static boolean answer(String key) {
        return callExtra(key, EXTRA_ANSWER_INTENT) || callAction(key, 0);
    }

    /**
     * Decline a ringing call, or hang up one in progress — decline first,
     * because that is what the banner's button says and a ringing call that
     * declares both means the other one for later.
     */
    static boolean decline(String key) {
        return callExtra(key, EXTRA_DECLINE_INTENT)
                || callExtra(key, EXTRA_HANG_UP_INTENT)
                || callAction(key, 1);
    }

    /** One of CallStyle's own intents, or false when this notification has none. */
    private static boolean callExtra(String key, String extra) {
        DexNotifications self = live;
        StatusBarNotification sbn = self == null ? null : self.find(key);
        if (sbn == null) return false;
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return false;
        Object pending = extras.get(extra);
        if (!(pending instanceof PendingIntent)) return false;
        return send((PendingIntent) pending, sbn.getPackageName(), extra);
    }

    /**
     * The positional fallback, for dialers that predate CallStyle: they post a
     * plain notification with a full-screen intent and two ordinary actions, so
     * answer is the first and decline the second.
     *
     * Guessing a button by position is not something to do generally. It is
     * bounded here to a notification already identified as a ringing call, and
     * the alternative is a banner with no buttons on it.
     *
     * A single-button notification is answer-only: with one button on a ringing
     * call the only thing it can be is answer, and firing it from the Decline
     * button would connect the call the user just refused.
     */
    private static boolean callAction(String key, int index) {
        DexNotifications self = live;
        StatusBarNotification sbn = self == null ? null : self.find(key);
        if (sbn == null) return false;
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null || index >= actions.length) return false;
        if (index > 0 && actions.length < 2) return false;
        // No label to check against: the banner's two buttons are ours, not
        // the app's, so there is nothing the user read off this button.
        return action(key, index, null);
    }

    /** Open the app behind a notification, the way tapping it on the phone would. */
    static boolean open(String key) {
        DexNotifications self = live;
        StatusBarNotification sbn = self == null ? null : self.find(key);
        if (sbn == null) return false;
        PendingIntent content = sbn.getNotification().contentIntent;
        if (content == null) return false;
        boolean sent = send(content, sbn.getPackageName(), "content intent");
        // Tapping a notification on the phone dismisses it when the app said it
        // should. Doing the same here keeps the desktop's list from filling up
        // with things already dealt with.
        if (sent && (sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) {
            dismiss(key);
        }
        return sent;
    }

    /**
     * Send an app's PendingIntent, and put the window it opens ON THIS DESKTOP.
     *
     * The options are the whole point of this method. A PendingIntent is sent
     * in the CREATING app's identity, and the send happens from HERE — a
     * Service, which has no window and no display of its own. With nothing said
     * about where the activity should go, the window manager falls back to the
     * default display: the app opens on the phone's own screen, behind the
     * user, while the desktop they clicked on shows nothing. Clicking a
     * notification appeared to do nothing at all.
     *
     * So the launcher is asked for the same options it stamps on its own
     * launches — desktop display, freeform, and the rect this app was last left
     * at ({@link WindowMemory}), so a notification opens its app exactly where
     * the drawer would have. Null when there is no live desktop (the phone-only
     * case, where the platform's own default is already right), and the send
     * then goes out bare.
     *
     * Bounds ride on options rather than on the intent because the intent is
     * not ours to touch — it was built and frozen by the posting app.
     */
    private static boolean send(PendingIntent intent, String pkg, String what) {
        String where = pkg + " (" + what + ")";
        try {
            Bundle options = LauncherActivity.notificationLaunchOptions(pkg);
            if (options != null) {
                intent.send(live, 0, null, null, null, null, options);
            } else {
                intent.send();
            }
            DexLog.step("notifications", "opened " + where
                    + (options != null ? " on the desktop" : " with no desktop options"));
            return true;
        } catch (PendingIntent.CanceledException e) {
            // The app withdrew it between the list being drawn and the click.
            DexLog.warn("notifications", "already cancelled: " + where, e);
            return false;
        } catch (Exception e) {
            DexLog.warn("notifications", "cannot open " + where, e);
            return false;
        }
    }

    /** Swipe one away, exactly as the phone's own shade would. */
    static boolean dismiss(String key) {
        DexNotifications self = live;
        if (self == null || key == null) return false;
        try {
            self.cancelNotification(key);
            return true;
        } catch (Exception e) {
            DexLog.warn("notifications", "cannot dismiss " + key, e);
            return false;
        }
    }

    /**
     * Clear all — and only the clearable ones, which is what the platform's own
     * cancelAllNotifications does anyway. Ongoing entries stay; they belong to
     * something that is still running.
     */
    static boolean clearAll() {
        DexNotifications self = live;
        if (self == null) return false;
        try {
            self.cancelAllNotifications();
            return true;
        } catch (Exception e) {
            DexLog.warn("notifications", "cannot clear the notifications", e);
            return false;
        }
    }
}
