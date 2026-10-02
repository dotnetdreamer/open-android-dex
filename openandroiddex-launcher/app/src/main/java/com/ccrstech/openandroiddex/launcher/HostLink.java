package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.SystemClock;

/**
 * What the phone knows about the computer's file service: which OS it is, the
 * credential to present, and when it last said anything.
 *
 * Everything here arrives on {@link LauncherActivity#ACTION_FILES}, which the
 * desktop app re-sends on the same cadence as the running-apps heartbeat. It is
 * the only channel: the launcher cannot ask the PC anything (the request queue
 * runs one way), and {@link HostFiles} needs a token before it can open its
 * first socket.
 *
 * <p><b>Memory only, and never {@link DexPrefs}.</b> The token authorises a
 * socket, and a token that outlives the process that minted it authorises the
 * wrong one: files.rs mints a fresh secret per session, so a value kept in
 * SharedPreferences would be a credential this phone offers, after a reboot,
 * to whatever happens to be listening on the reversed port. Compare
 * {@link DexPrefs#KEY_HOST_TOUCHPAD}, the other thing the PC reports about
 * itself, which IS persisted: it only annotates a Settings section
 * (SettingsActivity dims the gesture rows when it is false), so being stale
 * costs a dimmed row, and Settings has to be able to draw that row before the
 * first heartbeat of a session arrives. Being stale here costs a secret. The
 * cost of not persisting is one beacon's wait — under a second in practice,
 * and the File transfer window has an honest "not answering" state for it.
 *
 * <p>Static because it can be: the launcher, the File transfer window and
 * every other window of ours run in one process, so the receiver that hears
 * the beacon and the client that spends it are the same JVM. No content
 * provider, no service, no binder.
 *
 * <p><b>The receiver is exported</b> — it has to be, the sender is {@code am
 * broadcast} from adb — so any app on this phone can send a beacon, and what
 * arrives is only "something that claims to be the computer". Two things keep
 * that claim to one wasted round trip. The beacon carries no port, and
 * {@link HostFiles#DEVICE_PORT} is a compile-time constant on both sides, so a
 * forgery cannot point the client anywhere but loopback. And a token is not
 * believed because it arrived — it is believed because the host answered a
 * HELLO with it: the credential that last worked is the INCUMBENT, a different
 * one that turns up is only a CANDIDATE, and {@link HostFiles} promotes a
 * candidate over the incumbent solely on an {@code OK}. See {@link #proved}
 * and {@link #refused}.
 *
 * <p>That distinction is not decoration. While every beacon simply overwrote
 * the token, a local app broadcasting ACTION_FILES with 32 alphanumerics in
 * its {@code token} extra twice a second destroyed the working credential
 * every time: each connect presented the forgery, got {@code ERR auth}, and
 * the window sat on "The computer is not answering" — Retry included — for as
 * long as the app kept talking. Now a genuine new session still works on its
 * first try, because its token authenticates, while a forged one costs one
 * refused round trip and is then remembered as refused so its rebroadcasts
 * cost nothing at all.
 *
 * <p>Written from the receiver's thread, read from the File transfer window's
 * threads. The readers take no lock — one volatile field each, and none of
 * them needs two fields to agree. The mutators ARE synchronized, because
 * incumbent, candidate and "has this been proven" are one decision split
 * across fields, and a half-applied one presents the wrong credential.
 */
final class HostLink {

    private HostLink() {
    }

    private static volatile String os;

    /**
     * The credential that last produced a successful HELLO — or, until one
     * ever has, simply the last beacon's. The one a forged beacon may not
     * touch.
     */
    private static volatile String token;

    /**
     * A different token that has arrived since, which no host has answered
     * yet. Tried ahead of the incumbent and promoted over it only by
     * {@link #proved}; a restarted computer's real token and a hostile app's
     * forgery are indistinguishable until the host rules on one.
     */
    private static volatile String candidate;

    /** Whether {@link #token} has ever been answered by a host. */
    private static volatile boolean proven;

    /**
     * The last candidate the host refused.
     *
     * One slot, and it is what keeps the forgery cost at one round trip in
     * total rather than one per broadcast: an app re-sending the same rejected
     * token every 500 ms would otherwise be tried again on every reconnect.
     */
    private static volatile String rejected;

    /**
     * uptime of the last beacon, or 0 if none has arrived.
     *
     * The same clock {@code LauncherActivity.pcSeenAt} is stamped from, on
     * purpose: {@link #available()} and {@code pcAlive()} are two answers to
     * nearly the same question and must not be able to disagree because one of
     * them was measured against a wall clock a time sync had just moved.
     */
    private static volatile long seenAt;

    /**
     * A beacon arrived.
     *
     * The two extras are not equally load-bearing, and they fail differently. A
     * token that is missing or not something we could put on a request line is
     * the whole beacon wasted — {@link #usable} explains why — so it counts as
     * silence and nothing is stamped. An {@code os} we do not recognise costs
     * only the pane heading, which falls back to "This computer", so the beacon
     * is still taken: refusing a working file service because a future host
     * called itself something new would be a worse trade than a generic label.
     *
     * <p>What a beacon may never do is evict a credential the host has already
     * answered — see the class comment for the wedge that came of letting it.
     * A token that differs from a proven incumbent is parked as a candidate
     * for {@link HostFiles} to try, and only {@link #proved} promotes it.
     */
    static synchronized void seen(String os, String token) {
        if (!usable(token)) return;
        // A candidate the host has already refused, while a proven credential
        // still stands: re-sending it cannot make it true, and taking it again
        // would hand a forger one wasted round trip per broadcast instead of
        // one in total.
        if (proven && token.equals(rejected)) return;
        String next = "win".equals(os) || "mac".equals(os) || "linux".equals(os) ? os : null;
        // One read of each field into a local, and the comparisons the way
        // round that cannot throw. Both receivers deliver on the main looper
        // today, so the two arrivals are serialised — but nothing here says so,
        // and forget() is reachable from any thread: "field != null then
        // field.equals(...)" is a null dereference the moment one lands between
        // the two reads, and it would be a crash in a beacon handler.
        String incumbent = HostLink.token;
        boolean fresh = !token.equals(incumbent) && !token.equals(candidate);
        HostLink.os = next;
        // The stamp is published before the token it belongs to, so no reader
        // can ever see a fresh credential carrying the previous beacon's age —
        // the direction that would make available() lie in the unsafe way.
        seenAt = SystemClock.uptimeMillis();
        if (token.equals(incumbent)) {
            // The session we are already talking to, re-announcing itself. It
            // outlives anything that turned up against it in the meantime.
            candidate = null;
        } else if (proven) {
            candidate = token;
        } else {
            // Nothing held here has ever been answered, so there is nothing to
            // protect and the newest claim is worth exactly as much as the one
            // it replaces. This is also the ordinary first-beacon path.
            HostLink.token = token;
            candidate = null;
        }
        // Once per session, not once per heartbeat: this fires every fiftieth
        // poll for as long as a desktop lives, and DexLog is a shared log. A
        // candidate is deliberately logged in proved() instead, so the line
        // records a computer that answered rather than an app that claimed —
        // otherwise a forger rotating tokens writes a line per broadcast.
        if (fresh && !proven) {
            DexLog.step("files", "the computer's file service announced itself ("
                    + (next == null ? "unknown os" : next) + ")");
        }
    }

    /**
     * The host answered a HELLO carrying {@code token}: this credential is the
     * computer's own, not something an app on the phone claimed.
     *
     * The only way a candidate ever becomes the incumbent. Called from both of
     * {@link HostFiles}'s greeting paths — the control socket and every data
     * socket — because either is equally good evidence, and a copy already in
     * flight when the desktop restarts would otherwise never promote the new
     * session's token.
     */
    static synchronized void proved(String token) {
        if (token == null) return;
        if (token.equals(candidate)) {
            HostLink.token = token;
            candidate = null;
            // A new session's proof retires the old session's refusals too:
            // "rejected" is a memo about the credential that lost, and it must
            // not outlive the credential it lost to.
            rejected = null;
            DexLog.step("files", "the computer's file service announced itself ("
                    + (os == null ? "unknown os" : os) + ")");
        }
        if (token.equals(HostLink.token)) proven = true;
    }

    /**
     * The host answered {@code ERR auth} to {@code token}.
     *
     * @return the credential worth presenting instead — the proven incumbent
     *         the candidate was standing in front of — or null when there is
     *         nothing better to try. Non-null only for a beacon that was
     *         forged or superseded, which is exactly the case where retrying
     *         immediately turns a wedged window back into a working one.
     */
    static synchronized String refused(String token) {
        if (token == null) return null;
        if (token.equals(candidate)) {
            candidate = null;
            rejected = token;
            String incumbent = HostLink.token;
            return incumbent != null && !incumbent.equals(token) ? incumbent : null;
        }
        if (token.equals(HostLink.token)) {
            // The credential that used to work has stopped working, so this is
            // no longer the computer that minted it. Dropping the proof is what
            // lets the next beacon become the incumbent directly instead of
            // queueing behind a dead token for the rest of the session.
            proven = false;
            rejected = null;
        }
        return null;
    }

    /**
     * Is there a computer offering files right now?
     *
     * Deliberately {@code LauncherActivity.PC_SILENCE_MS} rather than a window
     * of its own: the beacon rides the same heartbeat as the running-apps
     * broadcast, so a second constant could only ever produce a phone that
     * believes a PC is attached while believing its files are not, or the
     * reverse. One number, one answer.
     */
    static boolean available() {
        return token != null && seenAt > 0
                && SystemClock.uptimeMillis() - seenAt < LauncherActivity.PC_SILENCE_MS;
    }

    /**
     * The credential to present: the candidate when one is waiting to be
     * tried, otherwise the incumbent, and null when no beacon has ever
     * arrived.
     *
     * The candidate goes first because a restarted computer is the common case
     * and a forgery the rare one. The caller is expected to report an
     * {@code ERR auth} back through {@link #refused} and try whatever it hands
     * back — that is what holds the rare case to a single round trip instead
     * of a dead window.
     *
     * <p>Not gated on {@link #available()} on purpose. Freshness decides whether
     * to OFFER the computer's pane; whether a token still works is the host's
     * call, and asking costs one loopback round trip that either succeeds or
     * comes back {@code ERR auth}. Refusing to try would turn a beacon that is
     * merely a few seconds late into a window that cannot be retried.
     */
    static String token() {
        String next = candidate;
        return next != null ? next : token;
    }

    /** "win" | "mac" | "linux", or null when the beacon did not say. */
    static String os() {
        return os;
    }

    /**
     * What to call the computer in the window's own voice — "This PC", "This
     * Mac", "This computer".
     *
     * Not the name the host reports over HELLO ({@link HostFiles#hostLabel()}):
     * that one is the machine's own hostname, which is frequently something
     * nobody chose, and this heading sits above a pane the user is reading at a
     * glance beside "This phone".
     */
    static String hostLabel(Context ctx) {
        String os = HostLink.os;
        if ("win".equals(os)) return ctx.getString(R.string.ft_pc_win);
        if ("mac".equals(os)) return ctx.getString(R.string.ft_pc_mac);
        if ("linux".equals(os)) return ctx.getString(R.string.ft_pc_linux);
        return ctx.getString(R.string.ft_pc_generic);
    }

    /** Drop everything, the proof included. The next beacon re-arms it. */
    static synchronized void forget() {
        token = null;
        candidate = null;
        rejected = null;
        proven = false;
        os = null;
        seenAt = 0;
    }

    /**
     * Is this something we can put on a request line?
     *
     * The token goes into {@code HELLO <token> 1} verbatim, and the protocol is
     * whitespace-split — so a value carrying a space or a newline would not be
     * refused by the host, it would be misread as a different request. files.rs
     * sends 32 hex characters; anything that is not plain alphanumeric is a
     * forgery or a bug, and either way is not worth sending. This is not
     * authentication — that happens on the other end — it is keeping a hostile
     * extra from rewriting our own protocol.
     */
    private static boolean usable(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!ok) return false;
        }
        return true;
    }
}
