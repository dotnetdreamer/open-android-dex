package com.ccrstech.openandroiddex.launcher;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLSocketFactory;

/**
 * "Email me when an address changes": when what a person needs to reach this
 * phone changes (the Cloudflare tunnel's address after a restart, the Wi-Fi
 * address, a switch going off or on), the phone emails them the new
 * addresses, and, unless they turned it off, a sign-in link for each, so
 * that a person far away gets back in without anyone at the phone. Design
 * record: doc/address-email.md.
 *
 * <p><b>What is watched</b> ({@link Reach}): Linux's "Open in a browser",
 * Remote desktop and its "From anywhere", whether the door (the browser
 * server) is up, its Wi-Fi addresses, the tunnel's address, and remote
 * access (the desktop app's way in, whose address never changes). One email
 * says all of it; {@link Reach#key} is what decides whether two looks are the
 * same information.
 *
 * <p><b>When.</b> Anything that may have changed something calls {@link
 * #poke} (the tunnel finding an address, the service starting or switching,
 * a boot, the network). That only records what it saw and schedules the
 * job ({@link EmailNoticeJob}), which waits until things are still for
 * {@link #QUIET_MS} and complete (a tunnel that is wanted has its address),
 * sends one email, and records what it said: the same information is never
 * sent twice. No network: the job waits for one. A failure that may pass is
 * tried again later and later; one that only new settings can fix waits for
 * them and says so, on the card and in a notification.
 *
 * <p><b>Without being told.</b> A restart or an update may never tell the
 * app (on a Galaxy S25, Android 17, an update while the phone dozed sent no
 * MY_PACKAGE_REPLACED for minutes, and nothing of the app ran). So a
 * persisted job also looks every {@link #WATCH_EVERY_MS} (Android keeps it
 * through restarts and updates, not through Force stop), and when the door
 * or remote access should be up and LinuxOnDroid's {@code :linux} process is
 * not running, asks it back, as RemoteResumeReceiver would have.
 *
 * <p><b>Across processes.</b> The tunnel and the door run in {@code :linux},
 * the job and the Settings page in the app's own process: the settings and
 * the state are files under {@code files/notify/}, changed under one lock
 * across both ({@link #locked}), as LinuxBrowser's are. Emails are sent from
 * the app's own process only, one at a time ({@link #SENDING}).
 */
final class EmailNotice {

    private EmailNotice() {
    }

    static final int JOB_STEP = 0x454d01;
    static final int JOB_WATCH = 0x454d02;

    /** Still for this long before an email goes: a switch turned off and on again by mistake makes one email, not two. */
    static final long QUIET_MS = 45_000;
    /** Off the Wi-Fi: often back in a moment (a router restarting), so waited for longer. */
    static final long QUIET_WIFI_LOST_MS = 3 * 60_000L;
    /** Something wanted and not there yet (the tunnel's address): waited for this long, then the email says so. */
    static final long MAX_WAIT_MS = 5 * 60_000L;
    /** How often a wait for something wanted looks again. */
    static final long SETTLE_POLL_MS = 15_000;
    /** The look that needs no callback. JobScheduler's shortest period. */
    static final long WATCH_EVERY_MS = 15 * 60_000L;
    /** LinuxOnDroid asked back no more often than this. */
    static final long RESUME_EVERY_MS = 2 * 60_000L;
    /** A send that has said nothing for this long is taken as gone (its process ended). */
    static final long SENDING_STALE_MS = 3 * 60_000L;
    /** Retries of a failure that may pass, the longest wait between them. */
    static final long RETRY_MAX_MS = 30 * 60_000L;
    /** Addresses on the phone's own networks put in an email, at the most. */
    static final int MAX_LAN = 4;

    /** {@link #plan}: nothing to do until something changes. */
    static final long IDLE = -1;
    /** {@link #plan}: send now. */
    static final long SEND = 0;

    static final String CHANNEL = "email-notice";
    static final int NOTIF_ID = 0x62;

    /** The time. The tests' own clock in the tests, which wait minutes in no time. */
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** Within one process; the file lock is between the two. */
    private static final Object LOCK = new Object();
    /** One email at a time, from this process (the job and the Settings page's test). */
    private static final ReentrantLock SENDING = new ReentrantLock();

    // ── where it lives ──

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "notify");
    }

    /** Whether this APK has the feature: LinuxOnDroid declares the job, the desktop build does not. */
    static boolean offered(Context ctx) {
        try {
            ctx.getPackageManager().getServiceInfo(new ComponentName(ctx, EmailNoticeJob.class),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── the settings ──

    /** An email service the card offers, with where its SMTP server is. */
    static final class Provider {
        final String id;
        final int name;
        final int note;
        final String host;
        final int port;
        final EmailSmtp.Security security;

        Provider(String id, int name, int note, String host, int port, EmailSmtp.Security security) {
            this.id = id;
            this.name = name;
            this.note = note;
            this.host = host;
            this.port = port;
            this.security = security;
        }
    }

    /**
     * Gmail, Outlook, iCloud, Yahoo, and any other. Checked in November
     * 2026: Gmail, iCloud and Yahoo take an app password over SMTP; Microsoft
     * turned that off for personal Outlook and Hotmail addresses in September
     * 2024 ("5.7.139 basic authentication is disabled"), which the card says
     * rather than leave Outlook out (a work address may still be allowed).
     */
    static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new Provider("gmail", R.string.em_prov_gmail, R.string.em_note_gmail, "smtp.gmail.com", 465, EmailSmtp.Security.SSL),
            new Provider("outlook", R.string.em_prov_outlook, R.string.em_note_outlook, "smtp.office365.com", 587, EmailSmtp.Security.STARTTLS),
            new Provider("icloud", R.string.em_prov_icloud, R.string.em_note_icloud, "smtp.mail.me.com", 587, EmailSmtp.Security.STARTTLS),
            new Provider("yahoo", R.string.em_prov_yahoo, R.string.em_note_yahoo, "smtp.mail.yahoo.com", 465, EmailSmtp.Security.SSL),
            new Provider("other", R.string.em_prov_other, R.string.em_note_other, "", 587, EmailSmtp.Security.STARTTLS)));

    static Provider provider(String id) {
        for (Provider p : PROVIDERS) {
            if (p.id.equals(id)) return p;
        }
        return PROVIDERS.get(0);
    }

    /** What the card keeps: the switch, the links, where and how to send. Never the password (see {@link #savePassword}). */
    static final class Prefs {
        boolean on;
        boolean links = true;
        String provider = "gmail";
        String to = "";
        String user = "";
        String host = "smtp.gmail.com";
        int port = 465;
        EmailSmtp.Security security = EmailSmtp.Security.SSL;
        /** {@link #fingerprint} of the settings a test email last went through with. */
        String tested = "";

        static Prefs load(Context ctx) {
            Properties p = readProps(new File(dir(ctx), "settings"));
            Prefs s = new Prefs();
            s.on = "1".equals(p.getProperty("on"));
            s.links = !"0".equals(p.getProperty("links"));
            s.provider = p.getProperty("provider", s.provider);
            s.to = p.getProperty("to", "");
            s.user = p.getProperty("user", "");
            s.host = p.getProperty("host", s.host);
            try {
                s.port = Integer.parseInt(p.getProperty("port", String.valueOf(s.port)));
            } catch (NumberFormatException e) {
                s.port = 0;
            }
            s.security = "starttls".equals(p.getProperty("security")) ? EmailSmtp.Security.STARTTLS
                    : "ssl".equals(p.getProperty("security")) ? EmailSmtp.Security.SSL : s.security;
            s.tested = p.getProperty("tested", "");
            return s;
        }

        void save(Context ctx) {
            Properties p = new Properties();
            p.setProperty("on", on ? "1" : "0");
            p.setProperty("links", links ? "1" : "0");
            p.setProperty("provider", provider);
            p.setProperty("to", to.trim());
            p.setProperty("user", user.trim());
            p.setProperty("host", host.trim());
            p.setProperty("port", String.valueOf(port));
            p.setProperty("security", security == EmailSmtp.Security.STARTTLS ? "starttls" : "ssl");
            p.setProperty("tested", tested);
            writeProps(new File(dir(ctx), "settings"), p);
        }

        /** The sending address: the account signed in with, as Gmail, iCloud and Yahoo require. */
        String from() {
            return user.contains("@") ? user.trim() : to.trim();
        }

        /** The first thing still missing, as the card's words, or 0 when nothing is. */
        int missing(Context ctx) {
            if (!EmailSmtp.validAddress(to.trim())) return R.string.em_need_to;
            if (user.trim().isEmpty() || (!"other".equals(provider) && !EmailSmtp.validAddress(user.trim()))) {
                return R.string.em_need_user;
            }
            if (!EmailSmtp.validAddress(from())) return R.string.em_need_user;
            if (!EmailSmtp.validHost(host) || port < 1 || port > 65535) return R.string.em_need_server;
            if (!hasPassword(ctx)) return R.string.em_need_password;
            return 0;
        }

        /** What a test email proves: these settings and this password, sealed as they are now. */
        String fingerprint(Context ctx) {
            String sealed = PhoneRemote.read(new File(dir(ctx), "password")).trim();
            return LinuxBrowser.hash(to.trim().toLowerCase(Locale.ROOT) + "\n" + user.trim() + "\n"
                    + host.trim().toLowerCase(Locale.ROOT) + "\n" + port + "\n" + security + "\n" + LinuxBrowser.hash(sealed));
        }

        boolean tested(Context ctx) {
            return !tested.isEmpty() && tested.equals(fingerprint(ctx));
        }

        /** Emails go by themselves: on, complete, and a test email went through with exactly these settings. */
        boolean active(Context ctx) {
            return on && missing(ctx) == 0 && tested(ctx);
        }
    }

    // ── the password ──

    /** Sealed with the Keystore's key ({@link EmailSecret}); an empty one is left as it was. @return false when it could not be sealed */
    static boolean savePassword(Context ctx, String password) {
        if (password == null || password.isEmpty()) return true;
        try {
            byte[] sealed = EmailSecret.sealer.seal(password.getBytes(StandardCharsets.UTF_8));
            PhoneRemote.write(new File(dir(ctx), "password"), Base64.getEncoder().encodeToString(sealed));
            return true;
        } catch (Exception e) {
            DexLog.warn("email", "cannot seal the password: " + e.getClass().getSimpleName());
            return false;
        }
    }

    static boolean hasPassword(Context ctx) {
        return !PhoneRemote.read(new File(dir(ctx), "password")).trim().isEmpty();
    }

    /** The password, opened for one send; null when there is none or it no longer opens (a restored backup). */
    static String password(Context ctx) {
        String b64 = PhoneRemote.read(new File(dir(ctx), "password")).trim();
        if (b64.isEmpty()) return null;
        try {
            return new String(EmailSecret.sealer.open(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DexLog.warn("email", "the saved password does not open: " + e.getClass().getSimpleName());
            return null;
        }
    }

    // ── what a person needs to reach the phone ──

    /** What the phone is asked about; the phone's own on a phone ({@link AndroidEnv}). */
    interface Env {
        long now();

        boolean linuxOn();

        boolean phoneOn();

        boolean anywhereOn();

        boolean remoteOn();

        /** LinuxOnDroid's :linux process runs, where the door, the tunnel and remote access live. */
        boolean linuxAlive();

        /** The door's own line (LinuxBrowser.state). */
        String doorState();

        /** The phone's addresses on its own networks, best first, as "wifi 192.168.1.20". */
        List<String> lan();

        String tunnel();

        String phoneName();

        int computers();

        int bootCount();

        long updatedAt();
    }

    /**
     * One look at everything a person needs to reach the phone. {@link #key}
     * is the part that decides "the same information"; the rest is for the
     * words.
     */
    static final class Reach {
        boolean linux;
        boolean phone;
        boolean anywhere;
        /** The door: up, port (another app has the port), failed, down (not running, or starting), "" when not wanted. */
        String door = "";
        List<String> lan = new ArrayList<>();
        String tunnel = "";
        boolean remote;
        int port = LinuxBrowser.PORT;
        // For the words and for why, not for the key:
        String name = "";
        int computers;
        boolean alive = true;
        int boot = -1;
        long updated;

        static Reach read(Env env) {
            Reach r = new Reach();
            r.linux = env.linuxOn();
            r.phone = env.phoneOn();
            r.anywhere = r.phone && env.anywhereOn();
            r.remote = env.remoteOn();
            r.alive = env.linuxAlive();
            String state = env.doorState();
            if (r.linux || r.phone) r.door = doorOf(state, r.alive);
            String[] parts = state.split(" ");
            if (parts.length > 1 && (parts[0].equals("listening") || parts[0].equals("watching"))) {
                try {
                    r.port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    // the usual one
                }
            }
            if ("up".equals(r.door)) {
                for (String a : env.lan()) {
                    if (r.lan.size() < MAX_LAN) r.lan.add(a);
                }
            }
            String t = env.tunnel().trim();
            if (r.anywhere && r.alive && t.matches("https://[a-z0-9-]+\\.trycloudflare\\.com")) r.tunnel = t;
            r.name = env.phoneName();
            r.computers = env.computers();
            r.boot = env.bootCount();
            r.updated = env.updatedAt();
            return r;
        }

        static String doorOf(String state, boolean alive) {
            if (!alive) return "down";
            if (state.startsWith("listening") || state.startsWith("watching")) return "up";
            if (state.startsWith("failed port")) return "port";
            if (state.startsWith("failed")) return "failed";
            return "down";
        }

        boolean doorWanted() {
            return linux || phone;
        }

        boolean up() {
            return "up".equals(door);
        }

        /** What a person uses, and nothing else: a phone renamed, a computer added, a restart that changed nothing, send no email. */
        String key() {
            StringBuilder b = new StringBuilder();
            b.append("linux=").append(linux ? 1 : 0).append(";phone=").append(phone ? 1 : 0);
            if (doorWanted()) {
                b.append(";door=").append(door);
                if (up()) b.append(";lan=").append(String.join("|", lan));
            }
            if (phone) {
                b.append(";anywhere=").append(anywhere ? 1 : 0);
                if (anywhere && up()) b.append(";tunnel=").append(tunnel);
            }
            b.append(";remote=").append(remote ? 1 : 0);
            return b.toString();
        }

        /** Nothing on its way: the door up (or failed, which is said) when wanted, the tunnel's address there when wanted. */
        boolean settled() {
            if (doorWanted() && "down".equals(door)) return false;
            return !(anywhere && up() && tunnel.isEmpty());
        }

        /** The whole look on one line, kept as what the last email said. */
        String line() {
            return key() + ";port=" + port;
        }

        static Reach parse(String line) {
            if (line == null || line.isEmpty()) return null;
            Reach r = new Reach();
            for (String part : line.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq);
                String v = part.substring(eq + 1);
                switch (k) {
                    case "linux": r.linux = v.equals("1"); break;
                    case "phone": r.phone = v.equals("1"); break;
                    case "anywhere": r.anywhere = v.equals("1"); break;
                    case "door": r.door = v; break;
                    case "lan":
                        if (!v.isEmpty()) r.lan = new ArrayList<>(Arrays.asList(v.split("\\|")));
                        break;
                    case "tunnel": r.tunnel = v; break;
                    case "remote": r.remote = v.equals("1"); break;
                    case "port":
                        try {
                            r.port = Integer.parseInt(v);
                        } catch (NumberFormatException ignored) {
                            // the usual one
                        }
                        break;
                    default:
                        break;
                }
            }
            return r;
        }

        /** The Wi-Fi addresses and the tunnel's name, where a sign-in link can be used. */
        List<String> linkHosts() {
            List<String> hosts = new ArrayList<>();
            if (!up()) return hosts;
            if (phone && anywhere && !tunnel.isEmpty()) hosts.add(tunnel.substring("https://".length()));
            for (String a : lan) hosts.add(ipOf(a));
            return hosts;
        }
    }

    static String ipOf(String lanEntry) {
        int sp = lanEntry.indexOf(' ');
        return sp >= 0 ? lanEntry.substring(sp + 1) : lanEntry;
    }

    static String kindOf(String lanEntry) {
        int sp = lanEntry.indexOf(' ');
        return sp >= 0 ? lanEntry.substring(0, sp) : "other";
    }

    // ── what was seen and said ──

    /** The state between looks, in a file both processes change under {@link #locked}. */
    static final class State {
        /** {@link Reach#key} of the last email that went, "" before the first. */
        String sentKey = "";
        String sentLine = "";
        long sentAt;
        /** The last email's headline, for the card. */
        String sentHead = "";
        /** The key as last looked at, and since when it has been so. */
        String seenKey = "";
        long seenAt;
        /** When the information first stopped being what was sent; 0 while it is. */
        long changeAt;
        /** What happened since the last email, as known: boot, update, stopped, tunnel, network. */
        String causes = "";
        int attempts;
        long retryAt;
        /** The last failure ({@link EmailSmtp.Fault}, or PASSWORD), "" when the last send went. */
        String fault = "";
        String said = "";
        long faultAt;
        /** A failure only new settings can fix: nothing is tried until they come. */
        boolean blocked;
        /** A send under way: when it began, so another look does not send the same. */
        long sendingAt;
        int boot = -1;
        long updated;
        long resumedAt;

        static State load(Context ctx) {
            Properties p = readProps(new File(dir(ctx), "state"));
            State s = new State();
            s.sentKey = p.getProperty("sent.key", "");
            s.sentLine = p.getProperty("sent.line", "");
            s.sentAt = num(p.getProperty("sent.at"));
            s.sentHead = p.getProperty("sent.head", "");
            s.seenKey = p.getProperty("seen.key", "");
            s.seenAt = num(p.getProperty("seen.at"));
            s.changeAt = num(p.getProperty("change.at"));
            s.causes = p.getProperty("causes", "");
            s.attempts = (int) num(p.getProperty("attempts"));
            s.retryAt = num(p.getProperty("retry.at"));
            s.fault = p.getProperty("fault", "");
            s.said = p.getProperty("said", "");
            s.faultAt = num(p.getProperty("fault.at"));
            s.blocked = "1".equals(p.getProperty("blocked"));
            s.sendingAt = num(p.getProperty("sending.at"));
            s.boot = p.getProperty("boot") == null ? -1 : (int) num(p.getProperty("boot"));
            s.updated = num(p.getProperty("updated"));
            s.resumedAt = num(p.getProperty("resumed.at"));
            return s;
        }

        void save(Context ctx) {
            Properties p = new Properties();
            p.setProperty("sent.key", sentKey);
            p.setProperty("sent.line", sentLine);
            p.setProperty("sent.at", String.valueOf(sentAt));
            p.setProperty("sent.head", sentHead);
            p.setProperty("seen.key", seenKey);
            p.setProperty("seen.at", String.valueOf(seenAt));
            p.setProperty("change.at", String.valueOf(changeAt));
            p.setProperty("causes", causes);
            p.setProperty("attempts", String.valueOf(attempts));
            p.setProperty("retry.at", String.valueOf(retryAt));
            p.setProperty("fault", fault);
            p.setProperty("said", said);
            p.setProperty("fault.at", String.valueOf(faultAt));
            p.setProperty("blocked", blocked ? "1" : "0");
            p.setProperty("sending.at", String.valueOf(sendingAt));
            p.setProperty("boot", String.valueOf(boot));
            p.setProperty("updated", String.valueOf(updated));
            p.setProperty("resumed.at", String.valueOf(resumedAt));
            writeProps(new File(dir(ctx), "state"), p);
        }

        boolean sending(long now) {
            return sendingAt > 0 && now - sendingAt < SENDING_STALE_MS;
        }

        /** New settings, or a test that went through: a failure that waited for them is tried again. */
        void unblock() {
            blocked = false;
            attempts = 0;
            retryAt = 0;
        }
    }

    // ── the rules, pure, for the tests ──

    /** What a look saw: the information, since when, and why it may have changed. */
    static void observe(State st, Reach r, long now, String cause) {
        if (cause != null && !cause.isEmpty()) addCause(st, cause);
        // A restart or an update that the app was never told of is still seen.
        if (r.boot >= 0) {
            if (st.boot >= 0 && r.boot != st.boot) addCause(st, "boot");
            st.boot = r.boot;
        }
        if (r.updated > 0) {
            if (st.updated > 0 && r.updated != st.updated) addCause(st, "update");
            st.updated = r.updated;
        }
        String key = r.key();
        if (!key.equals(st.seenKey)) {
            st.seenKey = key;
            st.seenAt = now;
        }
        if (key.equals(st.sentKey)) {
            // Back to what the last email said: nothing to say, whatever happened.
            st.changeAt = 0;
            st.causes = "";
        } else if (st.changeAt == 0) {
            st.changeAt = now;
        }
    }

    static void addCause(State st, String cause) {
        for (String c : st.causes.split(",")) {
            if (c.equals(cause)) return;
        }
        st.causes = st.causes.isEmpty() ? cause : st.causes + "," + cause;
    }

    /**
     * What to do now: {@link #IDLE}, {@link #SEND}, or wait this many
     * milliseconds and look again.
     */
    static long plan(State st, Reach r, long now) {
        String key = r.key();
        if (key.equals(st.sentKey)) return IDLE;
        if (st.blocked) return IDLE;
        long quiet = quietFor(st, r);
        long still = now - st.seenAt;
        if (still < quiet) return quiet - still;
        long waited = now - st.changeAt;
        if (!r.settled() && waited < MAX_WAIT_MS) return Math.min(SETTLE_POLL_MS, MAX_WAIT_MS - waited);
        if (st.retryAt > now) return st.retryAt - now;
        return SEND;
    }

    static long quietFor(State st, Reach r) {
        Reach before = Reach.parse(st.sentLine);
        if (before != null && !before.lan.isEmpty() && r.lan.isEmpty() && r.up()) return QUIET_WIFI_LOST_MS;
        return QUIET_MS;
    }

    /** After the {@code n}th failure in a row that may pass: 1, 2, 4, 8, 16 minutes, then every half hour. */
    static long backoff(int n) {
        long ms = 60_000L << Math.min(5, Math.max(0, n - 1));
        return Math.min(RETRY_MAX_MS, ms);
    }

    /**
     * Whether LinuxOnDroid should be asked back: the door or remote access
     * wanted and its process not running, or the door down for longer than
     * a start takes. Not while it is merely starting.
     */
    static boolean needsResume(State st, Reach r, long now) {
        boolean gone = !r.alive && (r.doorWanted() || r.remote);
        boolean stuck = r.doorWanted() && "down".equals(r.door) && st.changeAt > 0 && now - st.changeAt >= MAX_WAIT_MS;
        return (gone || stuck) && now - st.resumedAt >= RESUME_EVERY_MS;
    }

    // ── looking, from anywhere ──

    /**
     * Something may have changed: recorded, and the job scheduled for when
     * an email could go. Cheap, safe from any process and thread, and never
     * throws: LinuxService calls this from :linux, beside the desktop.
     */
    static void poke(Context ctx, String cause) {
        try {
            if (!offered(ctx)) return;
            Prefs s = Prefs.load(ctx);
            if (!s.active(ctx)) return;
            Reach r = Reach.read(new AndroidEnv(ctx));
            long now = clock.getAsLong();
            locked(ctx, () -> {
                // Switched off or forgotten while this looked: nothing recorded, nothing scheduled.
                if (!Prefs.load(ctx).active(ctx)) return null;
                State st = State.load(ctx);
                observe(st, r, now, cause);
                long n = st.sending(now) ? IDLE : plan(st, r, now);
                st.save(ctx);
                // Under the lock the switch goes off by, so a job is never left behind it.
                ensureWatch(ctx);
                if (n >= 0) scheduleStep(ctx, n);
                return null;
            });
        } catch (RuntimeException e) {
            DexLog.warn("email", "looking at the addresses: " + e);
        }
    }

    private static final ScheduledExecutorService LATER = newWorker();
    private static final AtomicBoolean NETWORK_POKE_DUE = new AtomicBoolean();

    private static ScheduledExecutorService newWorker() {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "email-notice");
            t.setDaemon(true);
            return t;
        });
        e.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return e;
    }

    /** The tests': {@link #pokeLater} and {@link #fromService} on the caller's thread, so that what they do is done. */
    static volatile boolean inline;

    /** {@link #poke}, off the caller's thread: for a main thread, and for cloudflared's reader. */
    static void pokeLater(Context ctx, String cause) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (inline) {
            poke(app, cause);
            return;
        }
        try {
            LATER.execute(() -> poke(app, cause));
        } catch (RuntimeException e) {
            DexLog.warn("email", "cannot look at the addresses: " + e);
        }
    }

    /**
     * LinuxService, at every start (in :linux): every switch comes through
     * there, and so does every start of the process. {@code restarted}: an
     * Android restart of the service after its process was killed.
     */
    static void fromService(Context ctx, boolean restarted, boolean doorWanted) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Runnable look = () -> {
            try {
                watchNetwork(app, doorWanted && offered(app) && Prefs.load(app).active(app));
            } catch (RuntimeException e) {
                DexLog.warn("email", "watching the network: " + e);
            }
            poke(app, restarted ? "stopped" : null);
        };
        if (inline) {
            look.run();
            return;
        }
        try {
            LATER.execute(look);
        } catch (RuntimeException e) {
            DexLog.warn("email", "cannot look at the addresses: " + e);
        }
    }

    private static ConnectivityManager.NetworkCallback network;

    /**
     * While the door is up (in :linux): a new Wi-Fi, or a new address on
     * it, looked at a few seconds after it settles rather than at the next
     * look every quarter of an hour.
     */
    static synchronized void watchNetwork(Context ctx, boolean on) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        if (on && network == null) {
            network = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    changed();
                }

                @Override
                public void onLost(Network n) {
                    changed();
                }

                @Override
                public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                    changed();
                }

                private void changed() {
                    if (!NETWORK_POKE_DUE.compareAndSet(false, true)) return;
                    try {
                        LATER.schedule(() -> {
                            NETWORK_POKE_DUE.set(false);
                            poke(ctx, "network");
                        }, 3, TimeUnit.SECONDS);
                    } catch (RuntimeException e) {
                        NETWORK_POKE_DUE.set(false);
                    }
                }
            };
            try {
                cm.registerDefaultNetworkCallback(network);
            } catch (RuntimeException e) {
                DexLog.warn("email", "cannot watch the network: " + e);
                network = null;
            }
        } else if (!on && network != null) {
            try {
                cm.unregisterNetworkCallback(network);
            } catch (RuntimeException ignored) {
            }
            network = null;
        }
    }

    // ── the job ──

    static void scheduleStep(Context ctx, long delayMs) {
        try {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_STEP, new ComponentName(ctx, EmailNoticeJob.class))
                    .setMinimumLatency(Math.max(0, delayMs))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            DexLog.warn("email", "cannot schedule the email: " + e);
        }
    }

    /** The look every quarter of an hour that needs no callback, kept through restarts and updates. */
    static void ensureWatch(Context ctx) {
        try {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(JOB_WATCH) != null) return;
            js.schedule(new JobInfo.Builder(JOB_WATCH, new ComponentName(ctx, EmailNoticeJob.class))
                    .setPeriodic(WATCH_EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            DexLog.warn("email", "cannot schedule the look: " + e);
        }
    }

    static void cancelJobs(Context ctx) {
        try {
            JobScheduler js = ctx.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.cancel(JOB_STEP);
            js.cancel(JOB_WATCH);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * One run of the job (and of the look every quarter of an hour): what is
     * there now, LinuxOnDroid asked back if it should be running and is not,
     * and the email when one is due. In the app's own process.
     *
     * @return when to run again, or {@link #IDLE}
     */
    static long step(Context ctx) {
        if (!offered(ctx)) return IDLE;
        Prefs s = Prefs.load(ctx);
        if (!s.active(ctx)) {
            // Off, forgotten, or new settings not tested yet: no look left going for nothing.
            locked(ctx, () -> {
                if (!Prefs.load(ctx).active(ctx)) cancelJobs(ctx);
                return null;
            });
            return IDLE;
        }
        AndroidEnv env = new AndroidEnv(ctx);
        Reach r = Reach.read(env);
        long now = env.now();
        final boolean[] resume = {false};
        final boolean[] claimed = {false};
        final State[] before = {null};
        long next = locked(ctx, () -> {
            State st = State.load(ctx);
            observe(st, r, now, null);
            if (needsResume(st, r, now)) {
                st.resumedAt = now;
                addCause(st, "stopped");
                resume[0] = true;
            }
            long n;
            if (st.sending(now)) {
                n = SETTLE_POLL_MS;
            } else {
                n = plan(st, r, now);
                if (n == SEND) {
                    st.sendingAt = now;
                    claimed[0] = true;
                    before[0] = copy(st);
                }
            }
            st.save(ctx);
            return n;
        });
        if (resume[0]) resumeLinux(ctx);
        if (!claimed[0]) return next;
        Outcome out = deliver(ctx, s, r, before[0], false, now);
        if (out.ok) {
            // Something new while the email went: looked at as any change is.
            Reach again = Reach.read(env);
            return locked(ctx, () -> {
                State st = State.load(ctx);
                observe(st, again, env.now(), null);
                long n = plan(st, again, env.now());
                st.save(ctx);
                return n;
            });
        }
        return out.fault != null && out.fault.passing ? out.retryIn : IDLE;
    }

    private static State copy(State st) {
        State c = new State();
        c.sentKey = st.sentKey;
        c.sentLine = st.sentLine;
        c.causes = st.causes;
        return c;
    }

    /** The door and remote access back, as after a boot (RemoteResumeReceiver), when Android never said. */
    private static void resumeLinux(Context ctx) {
        DexLog.step("email", "LinuxOnDroid's door or remote access should be up and is not: asking LinuxOnDroid back");
        boolean ok = LinuxService.browserResume(ctx);
        ok &= LinuxService.remoteResume(ctx);
        if (!ok) RemoteResumeReceiver.offerResume(ctx);
    }

    /** What a send came to, for the job and the card. */
    static final class Outcome {
        final boolean ok;
        /** The kind, or null with {@link #local} for a failure on the phone (no password). */
        final EmailSmtp.Fault fault;
        final String local;
        final String said;
        final long retryIn;

        Outcome(boolean ok, EmailSmtp.Fault fault, String local, String said, long retryIn) {
            this.ok = ok;
            this.fault = fault;
            this.local = local;
            this.said = said == null ? "" : said;
            this.retryIn = retryIn;
        }
    }

    /** The platform's TLS, its trusted authorities: replaced by the tests with one that trusts their certificate. */
    static volatile SSLSocketFactory tls = null;

    private static SSLSocketFactory tls() {
        SSLSocketFactory f = tls;
        return f != null ? f : (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    /** The timing a send gets; shorter in the tests. */
    static volatile EmailSmtp.Timing timing = EmailSmtp.Timing.PHONE;

    /**
     * One email with what {@code r} says, its links made just before it goes
     * and dropped if it does not; the state recorded either way. A test
     * email ({@code test}) says it is one and is what a person waits for on
     * the card.
     */
    static Outcome deliver(Context ctx, Prefs s, Reach r, State before, boolean test, long now) {
        String password = password(ctx);
        LinuxBrowser door = LinuxService.browser(ctx);
        if (password == null) {
            recordFailure(ctx, null, "PASSWORD", "", now, test);
            return new Outcome(false, null, "PASSWORD", "", 0);
        }
        SENDING.lock();
        try {
            if (!test) {
                // Another send may have said exactly this while this one waited for its turn.
                State cur = State.load(ctx);
                if (r.key().equals(cur.sentKey)) {
                    locked(ctx, () -> {
                        State st = State.load(ctx);
                        st.sendingAt = 0;
                        st.save(ctx);
                        return null;
                    });
                    return new Outcome(true, null, null, "", 0);
                }
            }
            boolean withLinks = s.on && s.links;
            Map<String, String> links = new LinkedHashMap<>();
            List<String> hosts = withLinks ? r.linkHosts() : Collections.<String>emptyList();
            List<String> secrets = Collections.emptyList();
            if (withLinks) {
                secrets = door.newLinks(hosts, now);
                for (int i = 0; i < hosts.size(); i++) links.put(hosts.get(i), secrets.get(i));
            } else {
                door.dropLinks("off");
            }
            Context words = DexLocale.wrap(ctx);
            Reach was = before == null ? null : Reach.parse(before.sentLine);
            Mail mail = compose(words, s, r, was, before == null ? "" : before.causes, links, test, now);
            EmailSmtp.Message m = new EmailSmtp.Message(words.getString(R.string.em_from_name, r.name),
                    s.from(), s.to.trim(), mail.subject, mail.text, mail.html);
            try {
                EmailSmtp.send(new EmailSmtp.Server(s.host, s.port, s.security, s.user, password), m, tls(), timing, now);
            } catch (EmailSmtp.Failure f) {
                // This email's links stop; the last email's, which the person has, work on.
                if (!secrets.isEmpty()) door.dropLinks(secrets, "unsent");
                long retry = recordFailure(ctx, f.fault, f.fault.name(), f.said, now, test);
                DexLog.warn("email", (test ? "the test email" : "the email") + " did not go: " + f.fault);
                return new Outcome(false, f.fault, null, f.said, retry);
            }
            // Gone: only this email's links work from now on.
            if (withLinks) door.confirmLinks(secrets);
            DexLog.step("email", (test ? "a test email" : "an email") + " went to " + masked(s.to) + ": " + mail.head
                    + (links.isEmpty() ? "" : ", with " + links.size() + " sign-in link" + (links.size() == 1 ? "" : "s")));
            locked(ctx, () -> {
                State st = State.load(ctx);
                st.sentKey = r.key();
                st.sentLine = r.line();
                st.sentAt = now;
                st.sentHead = mail.head;
                st.causes = "";
                st.fault = "";
                st.said = "";
                st.sendingAt = 0;
                st.unblock();
                st.changeAt = st.seenKey.equals(st.sentKey) ? 0 : now;
                st.save(ctx);
                return null;
            });
            cancelFaultNote(ctx);
            return new Outcome(true, null, null, "", 0);
        } finally {
            SENDING.unlock();
        }
    }

    /** @return when a failure that may pass is tried again, in ms */
    private static long recordFailure(Context ctx, EmailSmtp.Fault fault, String name, String said, long now, boolean test) {
        long[] retry = {0};
        locked(ctx, () -> {
            State st = State.load(ctx);
            if (!test) {
                st.sendingAt = 0;
                st.attempts++;
                st.fault = name;
                st.said = said;
                st.faultAt = now;
                if (fault != null && fault.passing) {
                    retry[0] = backoff(st.attempts);
                    st.retryAt = now + retry[0];
                } else {
                    st.blocked = true;
                }
            }
            st.save(ctx);
            return null;
        });
        if (!test && (fault == null || !fault.passing)) faultNote(ctx, name);
        return retry[0];
    }

    /**
     * The card's "Send a test email": a real email with what is there now,
     * so the person sees exactly what they will get, links and all when the
     * switch is on. A test that goes through is what lets the emails start
     * by themselves, and counts as the email for what it said.
     */
    static Outcome sendTest(Context ctx) {
        Prefs s = Prefs.load(ctx);
        int missing = s.missing(ctx);
        if (missing != 0) return new Outcome(false, null, "MISSING", "", 0);
        AndroidEnv env = new AndroidEnv(ctx);
        Reach r = Reach.read(env);
        long now = env.now();
        State st = locked(ctx, () -> {
            State x = State.load(ctx);
            observe(x, r, now, null);
            x.save(ctx);
            return copy(x);
        });
        Outcome out = deliver(ctx, s, r, st, true, now);
        if (out.ok) {
            Prefs fresh = Prefs.load(ctx);
            fresh.tested = fresh.fingerprint(ctx);
            fresh.save(ctx);
            // Active from now on: the look every quarter of an hour is scheduled by the poke.
            if (fresh.on) pokeLater(ctx, null);
        }
        return out;
    }

    /** The card's switch. Off: no job, no links, no notification left; the settings stay for next time. */
    static void setOn(Context ctx, boolean on) {
        locked(ctx, () -> {
            Prefs s = Prefs.load(ctx);
            s.on = on;
            s.save(ctx);
            if (on) {
                State st = State.load(ctx);
                st.unblock();
                st.save(ctx);
            } else {
                // Under the lock a look schedules by: none is left behind the switch.
                cancelJobs(ctx);
            }
            return null;
        });
        if (on) {
            pokeLater(ctx, null);
        } else {
            LinuxService.browser(ctx).dropLinks("off");
            cancelFaultNote(ctx);
        }
    }

    /** New settings were saved: a failure that waited for them may be tried again. */
    static void settingsChanged(Context ctx) {
        locked(ctx, () -> {
            State st = State.load(ctx);
            st.unblock();
            st.save(ctx);
            return null;
        });
        cancelFaultNote(ctx);
        pokeLater(ctx, null);
    }

    /** The card's "Forget the email settings": the settings, the password and its key, what was sent, the links. */
    static void forget(Context ctx) {
        locked(ctx, () -> {
            cancelJobs(ctx);
            File[] files = dir(ctx).listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().equals(".lock")) f.delete();
                }
            }
            return null;
        });
        LinuxService.browser(ctx).dropLinks("off");
        cancelFaultNote(ctx);
        EmailSecret.sealer.forget();
    }

    // ── the card's view of things ──

    static State state(Context ctx) {
        return State.load(ctx);
    }

    /** "a…@example.com": enough to recognise, not the whole address, for a log. */
    static String masked(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        if (at <= 0) return "someone";
        return address.charAt(0) + "…" + address.substring(at);
    }

    /** The words for a failure: {@link EmailSmtp.Fault}'s name, PASSWORD, or MISSING. */
    static int faultWords(String fault) {
        switch (fault == null ? "" : fault) {
            case "NETWORK": return R.string.em_fault_network;
            case "TIMEOUT": return R.string.em_fault_timeout;
            case "BUSY": return R.string.em_fault_busy;
            case "CERTIFICATE": return R.string.em_fault_certificate;
            case "SECURITY": return R.string.em_fault_security;
            case "TLS": return R.string.em_fault_tls;
            case "NO_TLS": return R.string.em_fault_no_tls;
            case "NO_LOGIN": return R.string.em_fault_no_login;
            case "LOGIN": return R.string.em_fault_login;
            case "APP_PASSWORD": return R.string.em_fault_app_password;
            case "BASIC_OFF": return R.string.em_fault_basic_off;
            case "SENDER": return R.string.em_fault_sender;
            case "RECIPIENT": return R.string.em_fault_recipient;
            case "REFUSED": return R.string.em_fault_refused;
            case "PASSWORD": return R.string.em_fault_password;
            default: return R.string.em_fault_protocol;
        }
    }

    // ── the notification, for a failure only the person can fix ──

    private static void faultNote(Context ctx, String fault) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            Context words = DexLocale.wrap(ctx);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, words.getString(R.string.em_channel),
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent open = PendingIntent.getActivity(ctx, 0x62,
                    new Intent(ctx, LinuxSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String text = capital(words.getString(faultWords(fault)));
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(words.getString(R.string.em_note_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (RuntimeException e) {
            DexLog.warn("email", "cannot post the notification: " + e);
        }
    }

    private static void cancelFaultNote(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (RuntimeException ignored) {
        }
    }

    static String capital(String s) {
        if (s == null || s.isEmpty()) return "";
        int first = s.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + s.substring(Character.charCount(first));
    }

    // ── the email ──

    /** An email's words: the subject, the text, the HTML, and its headline (for the card). */
    static final class Mail {
        final String subject;
        final String text;
        final String html;
        final String head;

        Mail(String subject, String text, String html, String head) {
            this.subject = subject;
            this.text = text;
            this.html = html;
            this.head = head;
        }
    }

    /**
     * The email: what changed and why (when known), every way in that is
     * there now, each with its sign-in link ({@code links}, by host), how to
     * sign in, and why the email came. Plain text and HTML of the same words,
     * in the phone's language; the HTML readable on a phone, its colours
     * written into each element (mail apps drop most style sheets).
     */
    static Mail compose(Context w, Prefs s, Reach now, Reach before, String causes, Map<String, String> links,
                        boolean test, long at) {
        boolean rtl = w.getResources().getConfiguration().getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
        List<String> changes = new ArrayList<>();
        boolean[] switched = {false};
        String head = headline(w, now, before, test, changes, switched);
        String subject = w.getString(R.string.em_subject, now.name, head);
        // A switch turned off or on says why itself; a restart or an update may still be the reason for the rest.
        String why = test || before == null ? "" : cause(w, causes, switched[0]);
        boolean linksOn = !links.isEmpty();

        Text t = new Text();
        Html h = new Html(rtl, w.getResources().getConfiguration().getLocales().get(0).toLanguageTag(), subject);
        t.title(subject);
        h.title(subject);
        if (test) {
            t.para(w.getString(R.string.em_test_banner));
            h.banner(w.getString(R.string.em_test_banner));
            if (!s.on && s.links) {
                t.para(w.getString(R.string.em_test_no_links));
                h.note(w.getString(R.string.em_test_no_links));
            }
        }
        if (!changes.isEmpty()) {
            t.heading(w.getString(R.string.em_what_changed));
            h.changesStart(w.getString(R.string.em_what_changed));
            for (String c : changes) {
                t.bullet(c);
                h.change(c);
            }
            if (!why.isEmpty()) {
                t.line(w.getString(R.string.em_why, why));
                h.why(w.getString(R.string.em_why, why));
            }
            h.changesEnd();
        }

        boolean anyRoute = false;
        if (now.doorWanted() && !now.up()) {
            String words = "port".equals(now.door) ? w.getString(R.string.em_route_port, now.port)
                    : "failed".equals(now.door) ? w.getString(R.string.em_route_failed)
                    : w.getString(R.string.em_route_down);
            t.heading(w.getString(R.string.em_sec_browsers));
            t.para(words);
            h.section(w.getString(R.string.em_sec_browsers));
            h.para(words);
            h.sectionEnd();
        } else if (now.doorWanted()) {
            String lifeWords = w.getString(R.string.em_sign_in_life);
            // From anywhere first: the one a person far away needs.
            if (now.phone && now.anywhere) {
                anyRoute = true;
                t.heading(w.getString(R.string.em_sec_anywhere));
                h.section(w.getString(R.string.em_sec_anywhere));
                if (now.tunnel.isEmpty()) {
                    t.para(w.getString(R.string.em_anywhere_wait));
                    h.para(w.getString(R.string.em_anywhere_wait));
                } else {
                    String page = now.tunnel + "/phone.html";
                    t.line(page);
                    // Shown as the bare name, which is what a person types (the
                    // tunnel's own page is the phone's), as the Settings card does.
                    h.address(page, now.tunnel.substring("https://".length()));
                    String secret = links.get(now.tunnel.substring("https://".length()));
                    if (secret != null) {
                        String link = now.tunnel + "/signin.html#k=" + secret + "&to=phone";
                        t.line(w.getString(R.string.em_sign_in) + ":");
                        t.line(link);
                        t.line(lifeWords);
                        h.button(link, w.getString(R.string.em_sign_in), lifeWords);
                    }
                }
                h.sectionEnd();
            }
            if (now.lan.isEmpty()) {
                anyRoute = true;
                t.heading(w.getString(R.string.em_sec_wifi));
                t.para(w.getString(R.string.em_no_wifi));
                h.section(w.getString(R.string.em_sec_wifi));
                h.para(w.getString(R.string.em_no_wifi));
                h.sectionEnd();
            } else {
                if (now.linux) {
                    anyRoute = true;
                    lanSection(w, t, h, now, links, true, lifeWords);
                }
                if (now.phone) {
                    anyRoute = true;
                    lanSection(w, t, h, now, links, false, lifeWords);
                }
            }
        }
        if (!now.doorWanted()) {
            t.heading(w.getString(R.string.em_sec_browsers));
            t.para(w.getString(R.string.em_route_nothing));
            h.section(w.getString(R.string.em_sec_browsers));
            h.para(w.getString(R.string.em_route_nothing));
            h.sectionEnd();
        }
        if (now.remote && now.computers > 0) {
            String words = now.alive ? w.getString(R.string.em_computers_on) : w.getString(R.string.em_computers_down);
            t.heading(w.getString(R.string.em_sec_computers));
            t.para(words);
            h.section(w.getString(R.string.em_sec_computers));
            h.para(words);
            h.sectionEnd();
        }

        if (anyRoute) {
            t.heading(w.getString(R.string.em_sec_how));
            h.section(w.getString(R.string.em_sec_how));
            List<String> how = new ArrayList<>();
            if (linksOn) how.add(w.getString(R.string.em_how_link));
            how.add(w.getString(R.string.em_how_code));
            how.add(w.getString(before != null && !before.tunnel.isEmpty() && !now.tunnel.isEmpty()
                    && !before.tunnel.equals(now.tunnel) ? R.string.em_how_new_address : R.string.em_how_kept));
            if (!now.lan.isEmpty()) how.add(w.getString(R.string.em_how_warning));
            for (String p : how) {
                t.para(p);
                h.para(p);
            }
            h.sectionEnd();
        }
        if (linksOn) {
            t.para(w.getString(R.string.em_private));
            h.note(w.getString(R.string.em_private));
        }
        String sent = w.getString(R.string.em_sent_at, when(w, at));
        String footer = w.getString(R.string.em_footer, now.name);
        t.small(footer);
        t.small(sent);
        h.footer(footer, sent);
        return new Mail(subject, t.toString(), h.toString(), head);
    }

    /** The Wi-Fi addresses for Linux ({@code linux}) or the phone's screen, each with its sign-in link. */
    private static void lanSection(Context w, Text t, Html h, Reach now, Map<String, String> links, boolean linux,
                                   String lifeWords) {
        String title = w.getString(linux ? R.string.em_sec_wifi_linux : R.string.em_sec_wifi_phone);
        t.heading(title);
        h.section(title);
        for (int i = 0; i < now.lan.size(); i++) {
            String ip = ipOf(now.lan.get(i));
            String base = "https://" + ip + (now.port == 443 ? "" : ":" + now.port);
            String page = linux ? base : base + "/phone.html";
            String typed = ip + (now.port == 443 ? "" : ":" + now.port) + (linux ? "" : "/phone.html");
            String kind = kindName(w, kindOf(now.lan.get(i)));
            String secret = links.get(ip);
            String link = secret == null ? null : base + "/signin.html#k=" + secret + "&to=" + (linux ? "linux" : "phone");
            if (i == 0) {
                t.line(page + "  (" + kind + ")");
                h.address(page, typed);
                h.small(kind);
                if (link != null) {
                    t.line(w.getString(R.string.em_sign_in) + ":");
                    t.line(link);
                    t.line(lifeWords);
                    h.button(link, w.getString(R.string.em_sign_in), lifeWords);
                }
            } else {
                String also = w.getString(R.string.em_also_at, kind);
                t.line(also + ": " + page);
                if (link != null) t.line(w.getString(R.string.em_sign_in) + ": " + link);
                h.also(also, page, typed, link, w.getString(R.string.em_sign_in));
            }
        }
        h.sectionEnd();
    }

    private static String kindName(Context w, String kind) {
        switch (kind) {
            case "wifi": return w.getString(R.string.wb_if_wifi);
            case "hotspot": return w.getString(R.string.wb_if_hotspot);
            case "wired": return w.getString(R.string.wb_if_wired);
            case "vpn": return w.getString(R.string.bw_if_vpn);
            default: return w.getString(R.string.bw_if_other);
        }
    }

    /**
     * The headline (subject and title) and, into {@code changes}, a line for
     * each thing that is not what the last email said.
     */
    static String headline(Context w, Reach now, Reach before, boolean test, List<String> changes, boolean[] switched) {
        if (test) return w.getString(R.string.em_head_test);
        if (before == null) {
            changes.add(w.getString(R.string.em_chg_first));
            return w.getString(R.string.em_head_first);
        }
        List<Integer> heads = new ArrayList<>();
        switched[0] = before.linux != now.linux || before.phone != now.phone || before.remote != now.remote
                || (now.phone && before.phone && before.anywhere != now.anywhere);
        if (before.linux != now.linux) {
            changes.add(w.getString(now.linux ? R.string.em_chg_linux_on : R.string.em_chg_linux_off));
            heads.add(now.linux ? R.string.em_head_linux_on : R.string.em_head_linux_off);
        }
        if (before.phone != now.phone) {
            changes.add(w.getString(now.phone ? R.string.em_chg_phone_on : R.string.em_chg_phone_off));
            heads.add(now.phone ? R.string.em_head_phone_on : R.string.em_head_phone_off);
        }
        boolean wasUp = before.up();
        boolean isUp = now.up();
        if (now.doorWanted() && before.doorWanted() && wasUp != isUp) {
            changes.add(isUp ? w.getString(R.string.em_chg_up)
                    : "port".equals(now.door) ? w.getString(R.string.em_route_port, now.port)
                    : "failed".equals(now.door) ? w.getString(R.string.em_route_failed)
                    : w.getString(R.string.em_route_down));
            heads.add(isUp ? R.string.em_head_up : R.string.em_head_down);
        }
        if (now.phone && before.phone && before.anywhere != now.anywhere) {
            changes.add(w.getString(now.anywhere ? R.string.em_chg_anywhere_on : R.string.em_chg_anywhere_off));
            heads.add(now.anywhere ? R.string.em_head_anywhere : R.string.em_head_anywhere_off);
        } else if (now.phone && now.anywhere && isUp && !before.tunnel.equals(now.tunnel)) {
            if (now.tunnel.isEmpty()) {
                changes.add(w.getString(R.string.em_chg_anywhere_gone));
            } else {
                changes.add(w.getString(before.tunnel.isEmpty() ? R.string.em_chg_anywhere_ready : R.string.em_chg_anywhere_new));
            }
            heads.add(R.string.em_head_anywhere);
        }
        if (isUp && wasUp && now.doorWanted() && !before.lan.equals(now.lan)) {
            if (now.lan.isEmpty()) {
                changes.add(w.getString(R.string.em_chg_wifi_gone));
                heads.add(R.string.em_head_wifi);
            } else if (before.lan.isEmpty()) {
                changes.add(w.getString(R.string.em_chg_wifi_back));
                heads.add(R.string.em_head_wifi);
            } else {
                // The Wi-Fi's own address, or another network's (a hotspot, a VPN): said as what it is.
                boolean wifi = !before.lan.get(0).equals(now.lan.get(0)) && "wifi".equals(kindOf(now.lan.get(0)));
                changes.add(w.getString(wifi ? R.string.em_chg_wifi_new : R.string.em_chg_lan_new));
                heads.add(wifi ? R.string.em_head_wifi : R.string.em_head_changed);
            }
        }
        if (before.remote != now.remote) {
            changes.add(w.getString(now.remote ? R.string.em_chg_remote_on : R.string.em_chg_remote_off));
            heads.add(now.remote ? R.string.em_head_remote_on : R.string.em_head_remote_off);
        }
        if (heads.isEmpty()) return w.getString(R.string.em_head_first);
        if (heads.size() == 1) return w.getString(heads.get(0));
        return w.getString(R.string.em_head_changed);
    }

    /**
     * Why, as far as is known, the likeliest first. After a switch, only a
     * restart, an update or LinuxOnDroid having stopped: the tunnel starting
     * again is what a switch does, not a reason of its own.
     */
    static String cause(Context w, String causes, boolean switched) {
        List<String> c = Arrays.asList(causes.split(","));
        if (c.contains("boot")) return w.getString(R.string.em_cause_boot);
        if (c.contains("update")) return w.getString(R.string.em_cause_update);
        if (c.contains("stopped")) return w.getString(R.string.em_cause_stopped);
        if (switched) return "";
        if (c.contains("tunnel")) return w.getString(R.string.em_cause_tunnel);
        if (c.contains("network")) return w.getString(R.string.em_cause_network);
        return "";
    }

    /** "22 November 2026, 07:12 CET", in the phone's language and time zone. */
    static String when(Context w, long at) {
        Locale locale = w.getResources().getConfiguration().getLocales().get(0);
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, locale);
        TimeZone zone = TimeZone.getDefault();
        f.setTimeZone(zone);
        return f.format(new Date(at)) + " " + zone.getDisplayName(zone.inDaylightTime(new Date(at)), TimeZone.SHORT, locale);
    }

    /** The plain text part: short lines, the links whole on lines of their own. */
    private static final class Text {
        private final StringBuilder b = new StringBuilder();

        void title(String s) {
            b.append(s).append("\n\n");
        }

        void heading(String s) {
            if (b.length() > 0 && !endsWithBlank()) b.append('\n');
            b.append(s).append('\n');
        }

        void para(String s) {
            b.append(s).append("\n\n");
        }

        void line(String s) {
            b.append(s).append('\n');
        }

        void bullet(String s) {
            b.append("• ").append(s).append('\n');
        }

        void small(String s) {
            b.append(s).append('\n');
        }

        private boolean endsWithBlank() {
            int n = b.length();
            return n >= 2 && b.charAt(n - 1) == '\n' && b.charAt(n - 2) == '\n';
        }

        @Override
        public String toString() {
            return b.toString().replaceAll("\n{3,}", "\n\n").trim() + "\n";
        }
    }

    /**
     * The HTML part. Every colour and size is written into its element: mail
     * apps keep few style sheets. One column, 560 px at the most, large
     * buttons; dark mode is the mail app's to make.
     */
    private static final class Html {
        private final StringBuilder b = new StringBuilder(8192);
        private final boolean rtl;
        private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
        private static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace";

        Html(boolean rtl, String lang, String title) {
            this.rtl = rtl;
            b.append("<!doctype html><html lang=\"").append(esc(lang)).append("\" dir=\"").append(rtl ? "rtl" : "ltr")
                    .append("\"><head><meta charset=\"utf-8\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                    .append("<meta name=\"color-scheme\" content=\"light\"><title>").append(esc(title)).append("</title></head>")
                    .append("<body style=\"margin:0;padding:0;background:#f3f4f6;\">")
                    .append("<div style=\"max-width:560px;margin:0 auto;padding:24px 16px 32px;font-family:").append(FONT)
                    .append(";font-size:15px;line-height:1.5;color:#111827;text-align:").append(rtl ? "right" : "left").append(";\">")
                    .append("<div style=\"font-size:13px;font-weight:600;color:#6b7280;letter-spacing:0.04em;\">LinuxOnDroid</div>");
        }

        void title(String s) {
            b.append("<h1 style=\"margin:6px 0 18px;font-size:22px;line-height:1.3;font-weight:700;color:#111827;\">")
                    .append(esc(s)).append("</h1>");
        }

        void banner(String s) {
            b.append("<div style=\"margin:0 0 14px;padding:12px 14px;border-radius:12px;background:#eef2ff;border:1px solid #c7d2fe;color:#312e81;\">")
                    .append(esc(s)).append("</div>");
        }

        void note(String s) {
            b.append("<div style=\"margin:0 0 14px;padding:12px 14px;border-radius:12px;background:#fff7ed;border:1px solid #fed7aa;color:#7c2d12;\">")
                    .append(esc(s)).append("</div>");
        }

        void changesStart(String heading) {
            b.append("<div style=\"margin:0 0 14px;padding:14px 16px;border-radius:14px;background:#fffbeb;border:1px solid #fcd34d;\">")
                    .append("<div style=\"font-weight:700;margin:0 0 6px;color:#78350f;\">").append(esc(heading)).append("</div>");
        }

        void change(String s) {
            b.append("<div style=\"margin:2px 0;color:#451a03;\">&#8226;&nbsp;").append(esc(s)).append("</div>");
        }

        void why(String s) {
            b.append("<div style=\"margin:8px 0 0;color:#92400e;font-size:14px;\">").append(esc(s)).append("</div>");
        }

        void changesEnd() {
            b.append("</div>");
        }

        void section(String heading) {
            b.append("<div style=\"margin:0 0 12px;padding:16px;border-radius:14px;background:#ffffff;border:1px solid #e5e7eb;\">")
                    .append("<div style=\"font-size:16px;font-weight:700;margin:0 0 8px;color:#111827;\">").append(esc(heading)).append("</div>");
        }

        void sectionEnd() {
            b.append("</div>");
        }

        void para(String s) {
            b.append("<p style=\"margin:6px 0;color:#374151;\">").append(esc(s)).append("</p>");
        }

        void small(String s) {
            b.append("<div style=\"margin:2px 0 4px;font-size:13px;color:#6b7280;\">").append(esc(s)).append("</div>");
        }

        /** An address, whole, left to right in every language, as a link to itself. */
        void address(String url, String shown) {
            b.append("<div dir=\"ltr\" style=\"margin:4px 0 6px;padding:10px 12px;border-radius:10px;background:#f3f4f6;font-family:")
                    .append(MONO).append(";font-size:15px;word-break:break-all;text-align:left;\">")
                    .append("<a href=\"").append(esc(url)).append("\" style=\"color:#1d4ed8;text-decoration:none;\">")
                    .append(esc(shown)).append("</a></div>");
        }

        void button(String url, String label, String under) {
            b.append("<div style=\"margin:10px 0 2px;\"><a href=\"").append(esc(url))
                    .append("\" style=\"display:inline-block;padding:12px 20px;border-radius:12px;background:#15803d;color:#ffffff;")
                    .append("font-weight:700;text-decoration:none;\">").append(esc(label)).append("</a></div>")
                    .append("<div style=\"font-size:13px;color:#6b7280;\">").append(esc(under)).append("</div>");
        }

        /** Another network's address: its name and the address on one line, its sign-in link under them. */
        void also(String label, String url, String shown, String link, String signIn) {
            b.append("<div style=\"margin:12px 0 0;font-size:14px;color:#374151;\">").append(esc(label)).append(": ")
                    .append("<a dir=\"ltr\" href=\"").append(esc(url)).append("\" style=\"color:#1d4ed8;font-family:").append(MONO)
                    .append(";word-break:break-all;\">").append(esc(shown)).append("</a></div>");
            if (link != null) {
                b.append("<div style=\"margin:4px 0 0;font-size:14px;\"><a href=\"").append(esc(link))
                        .append("\" style=\"color:#15803d;font-weight:700;\">").append(esc(signIn)).append("</a></div>");
            }
        }

        void footer(String why, String sent) {
            b.append("<div style=\"margin:20px 4px 0;font-size:12.5px;line-height:1.5;color:#6b7280;\">")
                    .append(esc(why)).append("<br>").append(esc(sent)).append("</div>");
        }

        @Override
        public String toString() {
            return b + "</div></body></html>";
        }

        static String esc(String s) {
            StringBuilder o = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '&': o.append("&amp;"); break;
                    case '<': o.append("&lt;"); break;
                    case '>': o.append("&gt;"); break;
                    case '"': o.append("&quot;"); break;
                    case '\'': o.append("&#39;"); break;
                    default: o.append(c);
                }
            }
            return o.toString();
        }
    }

    // ── the phone, as Env ──

    /** The phone's own answers, read from the same files and calls as the Settings page. */
    static final class AndroidEnv implements Env {
        private final Context ctx;

        AndroidEnv(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public long now() {
            return clock.getAsLong();
        }

        @Override
        public boolean linuxOn() {
            return LinuxService.browser(ctx).isOn() && !Linux.isRemoving(ctx);
        }

        @Override
        public boolean phoneOn() {
            return PhoneRemote.offered(ctx) && PhoneRemote.isEnabled(ctx);
        }

        @Override
        public boolean anywhereOn() {
            return PhoneTunnel.switchedOn(ctx) && PhoneTunnel.available(ctx);
        }

        @Override
        public boolean remoteOn() {
            return LinuxRemote.tunnelOn(ctx) && LinuxRemote.available(ctx);
        }

        @Override
        public boolean linuxAlive() {
            return linuxProcessAlive(ctx);
        }

        @Override
        public String doorState() {
            return LinuxService.browser(ctx).state();
        }

        @Override
        public List<String> lan() {
            List<String> out = new ArrayList<>();
            for (LinuxBrowser.Address a : LinuxBrowser.addresses()) out.add(a.kind + " " + a.ip);
            return out;
        }

        @Override
        public String tunnel() {
            return PhoneTunnel.address(ctx);
        }

        @Override
        public String phoneName() {
            return LinuxRemote.phoneName(ctx);
        }

        @Override
        public int computers() {
            try {
                return LinuxRemote.available(ctx) ? LinuxRemote.peers(ctx).size() : 0;
            } catch (RuntimeException e) {
                return 0;
            }
        }

        @Override
        public int bootCount() {
            try {
                return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            } catch (RuntimeException e) {
                return -1;
            }
        }

        @Override
        public long updatedAt() {
            try {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** LinuxOnDroid's :linux process is running: this one, or one of this app's that Android lists. */
    static boolean linuxProcessAlive(Context ctx) {
        String want = ctx.getPackageName() + ":linux";
        if (want.equals(processName())) return true;
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> list = am == null ? null : am.getRunningAppProcesses();
            if (list != null) {
                for (ActivityManager.RunningAppProcessInfo p : list) {
                    if (want.equals(p.processName)) return true;
                }
            }
        } catch (RuntimeException ignored) {
            // asked again at the next look
        }
        return false;
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName();
        try {
            byte[] b = java.nio.file.Files.readAllBytes(new File("/proc/self/cmdline").toPath());
            int end = 0;
            while (end < b.length && b[end] != 0) end++;
            return new String(b, 0, end, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ── files ──

    interface Work<T> {
        T run() throws Exception;
    }

    /** {@code work} with every other change to the state waiting: threads here on the monitor, the other process on a file lock. */
    static <T> T locked(Context ctx, Work<T> work) {
        synchronized (LOCK) {
            File d = dir(ctx);
            d.mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(new File(d, ".lock"), "rw");
                 FileLock ignored = raf.getChannel().lock()) {
                return work.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Properties readProps(File f) {
        Properties p = new Properties();
        if (!f.isFile()) return p;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            DexLog.warn("email", "cannot read " + f.getName() + ": " + e.getClass().getSimpleName());
        }
        return p;
    }

    /** Whole or not at all: written beside, then renamed over. */
    private static void writeProps(File f, Properties p) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                java.io.Writer w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
                p.store(w, null);
                w.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) throw new java.io.IOException("cannot rename " + tmp.getName());
            }
        } catch (Exception e) {
            DexLog.warn("email", "cannot write " + f.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    private static long num(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** SHA-256 hex of the bytes, for the tests' view of a sealed file. */
    static String sha(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
