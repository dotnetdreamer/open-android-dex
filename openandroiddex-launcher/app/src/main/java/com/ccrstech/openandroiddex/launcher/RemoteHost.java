package com.ccrstech.openandroiddex.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Remote access while it is on: the tunnel daemon, the guest's sshd, and the
 * three things Android makes the app do on their behalf.
 *
 * One per process ({@link #of}), driven by {@link LinuxService} in
 * {@code :linux}, and running on a thread of its own — the service's own
 * executor is the one provisioning blocks for minutes at a time, and a pairing
 * must not queue behind an apt run. One, not one per service instance: a
 * service Android destroys and creates again would otherwise have two of
 * these at once, the old one's stop racing the new one's start over the same
 * sshd and the same pairing.
 *
 * <ul>
 * <li><b>The daemon</b> ({@code libdexremote.so serve}) is a child process.
 *     Commands go down its stdin and events come up its stdout, one line each;
 *     when this process dies its stdin closes and the daemon exits with it, so
 *     there is never a tunnel open that nothing on the phone knows about.</li>
 * <li><b>sshd</b> runs from linux-ssh.sh, in its own proot, while the guest is
 *     installed and has one — whatever the desktop is doing. Restarted when it
 *     falls over, and given up on (and reported as failed) when it keeps
 *     falling over.</li>
 * <li><b>Network changes.</b> Native code on Android cannot watch the
 *     network; iroh says so and asks the app to tell it. Every default-network
 *     change becomes a {@code net} line, carrying the new network's DNS
 *     servers, which native code cannot ask for either.</li>
 * <li><b>Staying awake.</b> A partial wake lock is held while a computer is
 *     connected — a CPU that sleeps between keystrokes turns an SSH session into
 *     a slideshow and lets the NAT mapping lapse — and while the phone is on a
 *     charger, where it costs nothing. Otherwise the phone sleeps as usual: the
 *     relay connection is a socket, and a packet arriving on it wakes the CPU
 *     the way a push notification does.</li>
 * </ul>
 */
final class RemoteHost {

    /** Told when anything the notification shows has changed. */
    interface Listener {
        void remoteChanged();
    }

    private static RemoteHost instance;

    static synchronized RemoteHost of(Context ctx) {
        if (instance == null) instance = new RemoteHost(ctx);
        return instance;
    }

    /** An sshd or daemon that has lasted this long is not crash-looping. */
    private static final long STABLE_MS = 60_000;
    /** How often to ask again for an sshd install that failed, until it gives up. */
    private static final long PROVISION_RETRY_MS = 5 * 60_000;

    private final Context ctx;
    private volatile Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-host");
        t.setDaemon(true);
        return t;
    });

    // ── the daemon ──
    private Process daemon;
    private BufferedWriter daemonIn;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean online;
    private volatile int sessions;
    /** Lines sent before the daemon said `ready`. */
    private final List<String> pending = new ArrayList<>();
    private int daemonRestarts;
    private long daemonReadyAt;
    private ScheduledFuture<?> tick;

    // ── sshd ──
    private Process sshProc;
    private int sshPort = LinuxRemote.PREFERRED_SSH_PORT;
    private String sshState = "";
    /** The running sshd has accepted a connection; it is not probed after that. */
    private boolean sshAnswered;
    /** Consecutive starts that died within a minute. */
    private int sshQuickDeaths;
    private long sshStartedAt;
    /** When provisioning was last asked for sshd's sake (elapsedRealtime), or 0. */
    private long provisionKickedAt;
    /** A reset is deleting the container: sshd stays stopped until it is done. */
    private boolean sshHeld;

    // ── pairing ──
    /** The code on screen, or "" for none. */
    private String pairCode = "";
    /** When it stops being good, unix seconds, by the wall clock the screen counts down. */
    private long pairUntil;
    /** The pairing endpoint listening for {@link #pairCode}, once the daemon has said. */
    private String pairEndpoint = "";
    /**
     * `pair` lines the daemon has not answered yet. It answers each with one
     * `pair-listening` or one id-less `pair-failed`, in order, so the answer
     * that brings this to zero is the one for the latest code; earlier ones
     * belong to codes already replaced.
     */
    private int pairOutstanding;
    /** The computer a pair-request named, so `paired` can say who. */
    private String pairName = "";

    // ── wake ──
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean charging;
    private BroadcastReceiver powerReceiver;
    private ConnectivityManager.NetworkCallback netCallback;
    private Runnable netDebounce;

    private RemoteHost(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Who to tell about changes: the service instance of the moment, or null. */
    void setListener(Listener l) {
        listener = l;
    }

    private void changed() {
        Listener l = listener;
        if (l != null) l.remoteChanged();
    }

    boolean running() {
        return running;
    }

    /** One line for the ongoing notification, or null when off. */
    String summary() {
        if (!running) return null;
        if (!online) return ctx.getString(R.string.rm_note_offline);
        if (sessions > 0) {
            return ctx.getResources().getQuantityString(R.plurals.rm_note_connected, sessions, sessions);
        }
        return ctx.getString(R.string.rm_note_on);
    }

    // ── lifecycle ──

    /**
     * Start, or — already running — take it as someone asking now: a guest
     * still waiting for its sshd is provisioned again at the next tick rather
     * than at the end of the backoff. That is what makes "Set up SSH" from a
     * computer, which clears an install give-up, retry straight away.
     */
    void start() {
        worker.execute(() -> {
            if (running) {
                provisionKickedAt = 0;
            } else {
                startNow();
            }
        });
    }

    void stop() {
        worker.execute(this::stopNow);
    }

    /**
     * Stop sshd and keep it stopped: the container is about to be deleted
     * under it, and the three-second tick would otherwise start it again on
     * the doomed tree while the delete runs. {@link #releaseSsh} ends this.
     */
    void holdSsh() {
        try {
            worker.submit(() -> {
                sshHeld = true;
                stopSshNow();
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            DexLog.warn("remote", "stopping sshd for a wipe", e);
        }
    }

    void releaseSsh() {
        worker.execute(() -> {
            sshHeld = false;
            evaluate();
        });
    }

    void pair(String code) {
        worker.execute(() -> {
            // Already on its way: "Add a computer" with remote access off
            // starts the host, whose start reopens the code the window has
            // just written — this same one.
            if (code.equals(pairCode) && pairOutstanding > 0) return;
            pairCode = code;
            pairEndpoint = "";
            pairOutstanding++;
            pairUntil = System.currentTimeMillis() / 1000 + LinuxRemote.PAIR_TTL_SECONDS;
            pairName = "";
            LinuxRemote.writePairing(ctx, "starting", code, pairUntil, "", "");
            send("pair " + code + " " + LinuxRemote.PAIR_TTL_SECONDS + " " + pairUntil);
        });
    }

    void cancelPair() {
        worker.execute(() -> {
            send("pair-cancel");
            pairCode = "";
            pairEndpoint = "";
            LinuxRemote.writePairing(ctx, "", "", 0, "", "");
        });
    }

    private void startNow() {
        if (running) return;
        if (!LinuxRemote.available(ctx)) {
            DexLog.warn("remote", "no libdexremote.so for this ABI — remote access is unavailable");
            return;
        }
        running = true;
        daemonRestarts = 0;
        sshQuickDeaths = 0;
        provisionKickedAt = 0;
        LinuxRemote.writeSshdConfig(ctx);
        watchPower();
        watchNetwork();
        spawnDaemon();
        resumePairing(LinuxRemote.readPairing(ctx));
        tick = worker.scheduleWithFixedDelay(this::evaluate, 1, 3, TimeUnit.SECONDS);
        DexLog.step("remote", "remote access started");
        changed();
    }

    private void stopNow() {
        // Switched off: no pairing outlives that, whether or not anything was
        // running — a code left on screen by a process that died must not come
        // back at the next turn-on. A stop that is not a switch-off (the
        // service going while remote access stays on) leaves it to be reopened.
        if (!LinuxRemote.isEnabled(ctx)) {
            pending.clear();
            pairCode = "";
            pairEndpoint = "";
            pairOutstanding = 0;
            pairName = "";
            LinuxRemote.writePairing(ctx, "", "", 0, "", "");
        }
        if (!running) return;
        running = false;
        if (tick != null) tick.cancel(false);
        tick = null;
        unwatchNetwork();
        unwatchPower();
        stopDaemon();
        stopSshNow();
        ready = false;
        online = false;
        sessions = 0;
        // Nothing queued for the daemon outlives it.
        pending.clear();
        pairOutstanding = 0;
        pairEndpoint = "";
        updateLocks();
        DexLog.step("remote", "remote access stopped");
        changed();
    }

    /**
     * A code the window still shows, with no daemon behind it any more — this
     * process died, the daemon did, or the phone restarted. Opened again until
     * the deadline it was first shown with, so whoever is typing it is not
     * told it is wrong; shown as expired when too little is left to bother.
     */
    private void resumePairing(LinuxRemote.Pairing shown) {
        boolean open = "open".equals(shown.state) || "starting".equals(shown.state);
        if (!open || shown.code.isEmpty()) return;
        long left = shown.until - System.currentTimeMillis() / 1000;
        if (left < 30) {
            LinuxRemote.writePairing(ctx, "failed", shown.code, 0, "", "expired");
            pairCode = "";
            return;
        }
        pairCode = shown.code;
        pairUntil = shown.until;
        pairEndpoint = "";
        pairOutstanding = 1;
        LinuxRemote.writePairing(ctx, "starting", pairCode, pairUntil, "", "");
        // The absolute deadline goes with it: the line may wait out a restart
        // delay before it is sent, and the time left must not start over then.
        send("pair " + pairCode + " " + left + " " + pairUntil);
        DexLog.step("remote", "reopening the pairing code that was on screen (" + left + " s left)");
    }

    // ── the daemon ──

    private void spawnDaemon() {
        try {
            File dir = LinuxRemote.dir(ctx);
            dir.mkdirs();
            List<String> cmd = new ArrayList<>();
            cmd.add(LinuxRemote.binary(ctx).getAbsolutePath());
            cmd.add("serve");
            cmd.add("--dir");
            cmd.add(dir.getAbsolutePath());
            cmd.add("--ssh-port");
            cmd.add(String.valueOf(sshPort));
            cmd.add("--name");
            cmd.add(LinuxRemote.phoneName(ctx));
            String dns = dnsServers();
            if (!dns.isEmpty()) {
                cmd.add("--dns");
                cmd.add(dns);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.environment().put("DEXREMOTE_LOG", "warn,dexremote=info");
            // Appended, not replaced: a daemon that died left why in here, and
            // the next start is the moment someone would look. Kept to about
            // a megabyte and one older copy.
            File log = LinuxRemote.logFile(ctx);
            if (log.length() > 1_000_000) log.renameTo(new File(log.getPath() + ".1"));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(log));
            Process p = pb.start();
            daemon = p;
            daemonIn = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            ready = false;
            Thread reader = new Thread(() -> readEvents(p), "remote-events");
            reader.setDaemon(true);
            reader.start();
            DexLog.step("remote", "daemon started (sshd port " + sshPort + ", dns " + (dns.isEmpty() ? "public" : dns) + ")");
        } catch (Exception e) {
            DexLog.warn("remote", "cannot start the daemon", e);
            scheduleDaemonRestart();
        }
    }

    private void readEvents(Process p) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                final String event = line;
                worker.execute(() -> onEvent(p, event));
            }
        } catch (Exception ignored) {
            // The stream ends with the process; the exit is handled below.
        }
        int rc;
        try {
            rc = p.waitFor();
        } catch (InterruptedException e) {
            return;
        }
        worker.execute(() -> onDaemonExit(p, rc));
    }

    private void onDaemonExit(Process p, int rc) {
        if (p != daemon) return;   // a stopped one, replaced already
        boolean wasReady = ready;
        daemon = null;
        daemonIn = null;
        ready = false;
        online = false;
        sessions = 0;
        updateLocks();
        changed();
        if (!running) return;
        DexLog.warn("remote", "daemon exited " + rc + " — restarting");
        // The backoff starts over only after a daemon that lasted: one that
        // dies straight after `ready` is crash-looping all the same.
        if (wasReady && SystemClock.elapsedRealtime() - daemonReadyAt >= STABLE_MS) daemonRestarts = 0;
        // Whatever it was asked and never answered dies with it — a commit
        // for its pairing means nothing to the next one. The code on screen,
        // if any, is opened again on the new daemon.
        pending.clear();
        pairOutstanding = 0;
        pairEndpoint = "";
        if (!pairCode.isEmpty()) {
            LinuxRemote.Pairing shown = new LinuxRemote.Pairing();
            shown.state = "open";
            shown.code = pairCode;
            shown.until = pairUntil;
            resumePairing(shown);
        }
        scheduleDaemonRestart();
    }

    /** 2 s, 5 s, 15 s, then once a minute: a crash loop must not become a battery loop. */
    private void scheduleDaemonRestart() {
        if (!running) return;
        long[] delays = {2, 5, 15, 60};
        long delay = delays[Math.min(daemonRestarts, delays.length - 1)];
        daemonRestarts++;
        worker.schedule(() -> {
            if (running && daemon == null) spawnDaemon();
        }, delay, TimeUnit.SECONDS);
    }

    private void stopDaemon() {
        Process p = daemon;
        daemon = null;
        if (p == null) return;
        try {
            if (daemonIn != null) {
                daemonIn.write("quit\n");
                daemonIn.flush();
            }
        } catch (Exception ignored) {
        }
        daemonIn = null;
        try {
            // A clean quit closes every session with a reason the computer can
            // print ("Remote access was turned off"), which a kill cannot.
            if (!p.waitFor(4, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
        }
    }

    /**
     * Write one command line, or hold it until the daemon is ready for it.
     * `ssh`, `net` and `port` are never held: they describe the present, and
     * `ready` sends the present whole.
     */
    private void send(String line) {
        if (!ready || daemonIn == null) {
            if (!line.startsWith("ssh ") && !line.startsWith("net") && !line.startsWith("port ")) {
                pending.add(line);
            }
            return;
        }
        try {
            daemonIn.write(line);
            daemonIn.write('\n');
            daemonIn.flush();
        } catch (Exception e) {
            DexLog.warn("remote", "daemon pipe: " + e);
        }
    }

    private void onEvent(Process from, String line) {
        if (from != daemon) return;
        String[] w = line.split(" ");
        switch (w[0]) {
            case "ready": {
                ready = true;
                daemonReadyAt = SystemClock.elapsedRealtime();
                DexLog.step("remote", "daemon ready as " + LinuxRemote.shortId(w.length > 1 ? w[1] : ""));
                // The present, whole, before anything queued: a daemon starts
                // out knowing the port and DNS it was spawned with, either of
                // which may have moved since, and nothing about sshd.
                send("port " + sshPort);
                String dns = dnsServers();
                send(dns.isEmpty() ? "net" : "net " + dns);
                sshState = "";
                evaluate();
                List<String> queued = new ArrayList<>(pending);
                pending.clear();
                for (String q : queued) send(q);
                break;
            }
            case "online":
                online = true;
                DexLog.step("remote", "reachable from anywhere via " + (w.length > 1 ? w[1] : "a relay"));
                changed();
                break;
            case "offline":
                online = false;
                DexLog.step("remote", "no relay — not reachable until the network is back");
                changed();
                break;
            case "sessions":
                sessions = w.length > 1 ? parseInt(w[1]) : 0;
                updateLocks();
                changed();
                break;
            case "need-ssh":
                onNeedSsh();
                break;
            case "pair-listening":
                onPairListening(w);
                break;
            case "pair-request":
                onPairRequest(w);
                break;
            case "paired":
                // `paired <computer> <pairing>`. Another code's computer is
                // saved like any other; there is just nothing on screen for it.
                if (w.length > 2 && !w[2].equals(pairEndpoint)) {
                    changed();
                    break;
                }
                LinuxRemote.writePairing(ctx, "paired", pairCode, 0, pairName, "");
                pairCode = "";
                pairEndpoint = "";
                changed();
                break;
            case "pair-failed":
                onPairFailed(w);
                break;
            case "pair-closed": {
                // `pair-closed <pairing>`: its endpoint is gone. The code on
                // screen goes with it, unless something more final was said.
                if (w.length > 1 && !w[1].equals(pairEndpoint)) break;
                LinuxRemote.Pairing p = LinuxRemote.readPairing(ctx);
                if ("open".equals(p.state) || "starting".equals(p.state)) {
                    LinuxRemote.writePairing(ctx, "", "", 0, "", "");
                }
                pairCode = "";
                pairEndpoint = "";
                break;
            }
            case "revoked":
                DexLog.step("remote", "closed the sessions of a removed computer");
                break;
            default:
                break;
        }
    }

    /** `pair-listening <pairing> <until>`: the answer to one `pair`. */
    private void onPairListening(String[] w) {
        if (pairOutstanding > 0) pairOutstanding--;
        // An earlier code's, already replaced — or the code was cancelled.
        if (pairOutstanding > 0 || pairCode.isEmpty()) return;
        pairEndpoint = w.length > 1 ? w[1] : "";
        if (w.length > 2) pairUntil = parseLong(w[2]);
        LinuxRemote.writePairing(ctx, "open", pairCode, pairUntil, "", "");
    }

    /**
     * `pair-failed <reason> [<pairing>]`. With a pairing it is the end of an
     * open code, and counts only if it is the one on screen; without one it
     * answers a `pair` that could not open anything.
     */
    private void onPairFailed(String[] w) {
        String reason = w.length > 1 ? w[1] : "error";
        if (w.length > 2) {
            if (!w[2].equals(pairEndpoint)) return;
        } else {
            if (pairOutstanding > 0) pairOutstanding--;
            if (pairOutstanding > 0) return;
        }
        if (pairCode.isEmpty()) return;
        LinuxRemote.writePairing(ctx, "failed", pairCode, 0, "", reason);
        pairCode = "";
        pairEndpoint = "";
    }

    /**
     * `pair-request <id> <name> <os> <key> <pairing>`: the daemon checked the
     * code; we save the computer — if its code is the one on screen. A request
     * for a code that was replaced or cancelled while the computer typed it is
     * refused: whoever had it on their screen has moved on.
     */
    private void onPairRequest(String[] w) {
        if (w.length < 2) return;
        if (w.length < 6 || pairCode.isEmpty() || !w[5].equals(pairEndpoint)) {
            DexLog.step("remote", "refused a pairing for a code no longer on screen");
            send("pair-reject " + w[1]);
            return;
        }
        LinuxRemote.Peer peer = new LinuxRemote.Peer();
        peer.id = w[1];
        peer.name = LinuxRemote.unb64(w[2]);
        peer.os = w[3];
        peer.ssh = LinuxRemote.unb64(w[4]);
        peer.added = System.currentTimeMillis() / 1000;
        peer.via = "code";
        boolean saved = LinuxRemote.addPeer(ctx, peer);
        pairName = peer.name;
        send((saved ? "pair-commit " : "pair-reject ") + peer.id);
    }

    /**
     * The code on screen, past its deadline by the wall clock. The daemon ends
     * it too, but counts awake time between its checks; this is the same
     * deadline enforced from this side.
     */
    private void expirePairing() {
        if (pairCode.isEmpty() || pairUntil <= 0) return;
        if (System.currentTimeMillis() / 1000 <= pairUntil + 2) return;
        DexLog.step("remote", "the pairing code ran out");
        send("pair-cancel");
        LinuxRemote.writePairing(ctx, "failed", pairCode, 0, "", "expired");
        pairCode = "";
        pairEndpoint = "";
    }

    // ── sshd ──

    /**
     * sshd can run on this guest: it is in there, and the guest is not being
     * rebuilt. A setup run that failed at an optional step ("error") counts —
     * it leaves a guest that logs in fine, and getting in over SSH is one way
     * to see what went wrong.
     */
    private static boolean sshUsable(Linux.Status st, boolean sshdInstalled) {
        return sshdInstalled && ("ready".equals(st.phase) || "error".equals(st.phase));
    }

    /** What the guest can offer right now, and sshd brought in line with it. */
    private void evaluate() {
        if (!running) return;
        // The marker is the truth. Off is normally an intent to the service,
        // but an intent Android refused would otherwise leave this running
        // with nothing on screen saying so.
        if (!LinuxRemote.isEnabled(ctx)) {
            DexLog.step("remote", "remote access was switched off");
            stopNow();
            return;
        }
        expirePairing();
        String state;
        Linux.Status st = Linux.readStatus(ctx);
        boolean sshd = LinuxRemote.sshdInstalled(ctx);
        if (!Linux.isInstalled(ctx) || "none".equals(st.phase)) {
            state = "missing";
            stopSshNow();
        } else if (sshHeld) {
            state = "installing";
        } else if (sshUsable(st, sshd)) {
            ensureSsh();
            if (sshProc == null) {
                state = sshQuickDeaths >= 3 ? "failed" : "down";
            } else if (sshAnswered || portOpen(sshPort)) {
                // Probed only until it first answers. Each probe is a
                // connection sshd forks for and logs, under ptrace; after
                // that the process watcher says when it dies, and the daemon
                // (need-ssh) when a connection to it fails.
                sshAnswered = true;
                // Only an sshd that has lasted wipes the slate: one that
                // answers and then dies is crash-looping all the same.
                if (SystemClock.elapsedRealtime() - sshStartedAt >= STABLE_MS) sshQuickDeaths = 0;
                state = "up";
            } else {
                state = "starting";
            }
        } else if ("error".equals(st.phase)
                || ("ready".equals(st.phase) && LinuxRemote.sshdGaveUp(ctx))) {
            // Nothing changes by waiting: the setup stopped, or gave up on
            // openssh-server. A retry in the Linux window, or a reinstall, does.
            state = "failed";
        } else {
            state = "installing";
            // A guest with no sshd yet that is otherwise "ready" — built before
            // setup_ssh, or whose install failed — is provisioned again, every
            // few minutes until the script gives up after its third failure:
            // with no window or desktop opening, nothing else would ask, and
            // "installing" would be shown for ever.
            if ("ready".equals(st.phase)) kickProvision(PROVISION_RETRY_MS);
        }
        if (!state.equals(sshState)) {
            sshState = state;
            send("ssh " + state);
            DexLog.step("remote", "sshd is " + state);
        }
    }

    /** Ask for a provisioning run, unless one was asked for within {@code minGapMs}. */
    private void kickProvision(long minGapMs) {
        long now = SystemClock.elapsedRealtime();
        if (provisionKickedAt != 0 && now - provisionKickedAt < minGapMs) return;
        provisionKickedAt = now;
        DexLog.step("remote", "the guest has no sshd yet — provisioning it");
        LinuxService.provision(ctx);
    }

    /** A computer could not reach sshd just now. */
    private void onNeedSsh() {
        // Someone is waiting on it right now: worth another attempt even if
        // the last few failed.
        sshQuickDeaths = 0;
        // Up as far as anyone knew, and yet a connection to it just failed:
        // hung, or gone without its process noticing. Start it over.
        if (sshProc != null && sshAnswered && !portOpen(sshPort)) {
            DexLog.warn("remote", "sshd stopped answering — restarting it");
            stopSshNow();
        }
        ensureSsh();
        // And say so now: the daemon gives a failed sshd three seconds before
        // it tells the computer, and the next tick could miss that.
        evaluate();
        // A guest with no sshd at all: someone is waiting, so ask sooner.
        Linux.Status st = Linux.readStatus(ctx);
        if ("ready".equals(st.phase) && !LinuxRemote.sshdInstalled(ctx) && !LinuxRemote.sshdGaveUp(ctx)) {
            kickProvision(30_000);
        }
    }

    private void ensureSsh() {
        if (!running || sshHeld || sshProc != null) return;
        if (!sshUsable(Linux.readStatus(ctx), LinuxRemote.sshdInstalled(ctx))) return;
        if (sshQuickDeaths >= 3) return;   // `failed` until someone asks again
        try {
            // Copied on every start, like the viewer page: an APK update then
            // ships a new script without anyone reprovisioning.
            File root = Linux.root(ctx);
            File script = new File(root, "linux-ssh.sh");
            try (InputStream in = ctx.getAssets().open("linux/linux-ssh.sh");
                 OutputStream out = new java.io.FileOutputStream(script)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            LinuxRemote.writeSshdConfig(ctx);
            forgetStalePid();
            int port = pickPort();
            if (port != sshPort) {
                sshPort = port;
                send("port " + port);
            }
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath());
            pb.directory(root);
            Map<String, String> env = pb.environment();
            env.putAll(Linux.scriptEnv(ctx));
            env.put("LINUX_REMOTE_SSH", LinuxRemote.guestDir(ctx).getAbsolutePath());
            env.put("LINUX_SSH_PORT", String.valueOf(sshPort));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(root, "ssh.log"));
            Process p = pb.start();
            sshProc = p;
            sshAnswered = false;
            sshStartedAt = SystemClock.elapsedRealtime();
            Thread watch = new Thread(() -> {
                int rc;
                try {
                    rc = p.waitFor();
                } catch (InterruptedException e) {
                    return;
                }
                worker.execute(() -> onSshExit(p, rc));
            }, "remote-sshd");
            watch.setDaemon(true);
            watch.start();
            DexLog.step("remote", "sshd starting on 127.0.0.1:" + sshPort);
        } catch (Exception e) {
            DexLog.warn("remote", "cannot start sshd", e);
            sshQuickDeaths++;
        }
    }

    private void onSshExit(Process p, int rc) {
        if (p != sshProc) return;
        sshProc = null;
        sshAnswered = false;
        // sshd -D only ever exits on a fatal error, so an exit within a minute
        // of the start is a start that did not take.
        boolean quick = SystemClock.elapsedRealtime() - sshStartedAt < STABLE_MS;
        if (quick) sshQuickDeaths++;
        DexLog.warn("remote", "sshd exited " + rc + (quick ? " (quickly, " + sshQuickDeaths + " in a row)" : ""));
        // evaluate() restarts it on the next tick while the guest has one.
        evaluate();
    }

    private void stopSshNow() {
        Process p = sshProc;
        sshProc = null;
        sshAnswered = false;
        if (p == null) {
            forgetStalePid();
            return;
        }
        killSshGroup();
        try {
            p.destroyForcibly();
        } catch (Throwable ignored) {
        }
    }

    /**
     * An ssh.pid this host did not write in this process is never signalled.
     * The sshd it named died with the process that started it — Android kills
     * a dead app process's whole cgroup, children included — so the number may
     * belong to anything by now, one of this app's own processes among them,
     * and a group kill on it could take the desktop down.
     */
    private void forgetStalePid() {
        new File(Linux.root(ctx), "ssh.pid").delete();
    }

    /**
     * Everything under the linux-ssh.sh this host started: proot, sshd, and
     * every login.
     *
     * By process group, for the reason LinuxService.stopRuntime gives — this
     * uid cannot read other processes' /proc, and kill(2) on a negative pid
     * needs none — but SIGQUIT first. sshd setsid()s each login out of the
     * group, so a group SIGKILL reaches proot and the listener and not the
     * logins; and proot sets no PTRACE_O_EXITKILL (proot 0.15,
     * src/tracee/event.c), so with proot gone they would run on untraced:
     * a tmux someone left, a nohup job, VS Code's server, all outside the
     * container's paths. On SIGQUIT proot SIGKILLs every process it traces,
     * whatever session it is in, and exits; it ignores SIGTERM. The group is
     * given up to two seconds to be gone — kill -0 says when — before the
     * SIGKILL for whatever is left.
     */
    private void killSshGroup() {
        File root = Linux.root(ctx);
        String pidFile = new File(root, "ssh.pid").getAbsolutePath();
        String script = "P=$(cat '" + pidFile + "' 2>/dev/null); rm -f '" + pidFile + "'; "
                + "if [ -n \"$P\" ]; then /system/bin/kill -3 -\"$P\" 2>/dev/null; sleep 0.3; i=0; "
                + "while [ $i -lt 17 ] && /system/bin/kill -0 -\"$P\" 2>/dev/null; do sleep 0.1; i=$((i+1)); done; "
                + "/system/bin/kill -9 -\"$P\" 2>/dev/null; "
                + "/system/bin/kill -9 \"$P\" 2>/dev/null; fi; true";
        try {
            new ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            DexLog.warn("remote", "sshd group kill", e);
        }
    }

    /** The preferred port when it is free, else whatever the kernel hands out. */
    private int pickPort() {
        for (int port : new int[]{sshPort, LinuxRemote.PREFERRED_SSH_PORT, 0}) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                return s.getLocalPort();
            } catch (Exception ignored) {
                // taken — try the next
            }
        }
        return sshPort;
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── staying awake ──

    private void updateLocks() {
        boolean wantWake = running && (sessions > 0 || charging);
        boolean wantWifi = running && sessions > 0;
        try {
            if (wake == null) {
                PowerManager pm = ctx.getSystemService(PowerManager.class);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDeX:remote");
                wake.setReferenceCounted(false);
            }
            if (wantWake && !wake.isHeld()) {
                wake.acquire();
                DexLog.step("remote", "holding the CPU awake (" + (sessions > 0 ? sessions + " session(s)" : "charging") + ")");
            } else if (!wantWake && wake.isHeld()) {
                wake.release();
                DexLog.step("remote", "letting the CPU sleep");
            }
            // Below Android 14 only. From 14 a HIGH_PERF request is quietly
            // turned into a LOW_LATENCY lock, which holds only while the app is
            // in front with the screen on — never the case for a phone being
            // reached from a computer — so there is nothing worth taking.
            // LOW_LATENCY was never an option for the same reason.
            if (wifi == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager wm = ctx.getSystemService(WifiManager.class);
                if (wm != null) {
                    @SuppressWarnings("deprecation")
                    int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifi = wm.createWifiLock(mode, "OpenDeX:remote");
                    wifi.setReferenceCounted(false);
                }
            }
            if (wifi != null) {
                if (wantWifi && !wifi.isHeld()) wifi.acquire();
                else if (!wantWifi && wifi.isHeld()) wifi.release();
            }
        } catch (Exception e) {
            DexLog.warn("remote", "wake lock", e);
        }
    }

    private void watchPower() {
        Intent sticky = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        charging = sticky != null && sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        powerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean now = Intent.ACTION_POWER_CONNECTED.equals(intent.getAction());
                worker.execute(() -> {
                    charging = now;
                    updateLocks();
                });
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        ctx.registerReceiver(powerReceiver, filter);
        updateLocks();
    }

    private void unwatchPower() {
        if (powerReceiver != null) {
            try {
                ctx.unregisterReceiver(powerReceiver);
            } catch (Exception ignored) {
            }
            powerReceiver = null;
        }
    }

    // ── the network ──

    /**
     * Tell the daemon about every change of default network, a beat after it
     * settles, and which DNS servers the new one has. This is the whole of
     * iroh's network monitoring on Android — native code here gets no
     * netlink events — and it is what makes a phone that walks out of Wi-Fi
     * onto mobile data move its sessions with it instead of letting them time
     * out, and ask the carrier's resolvers rather than a router it left.
     */
    private void watchNetwork() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                netChanged();
            }

            @Override
            public void onLost(Network network) {
                netChanged();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                netChanged();
            }

            private void netChanged() {
                worker.execute(() -> {
                    if (netDebounce != null) return;
                    netDebounce = () -> {
                        netDebounce = null;
                        String dns = dnsServers();
                        send(dns.isEmpty() ? "net" : "net " + dns);
                    };
                    worker.schedule(netDebounce, 1500, TimeUnit.MILLISECONDS);
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            DexLog.warn("remote", "cannot watch the network", e);
            netCallback = null;
        }
    }

    private void unwatchNetwork() {
        if (netCallback == null) return;
        try {
            ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        netCallback = null;
    }

    /** The active network's resolvers, comma-separated — see dexremote's --dns. */
    private String dnsServers() {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network net = cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp == null) return "";
            StringBuilder sb = new StringBuilder();
            for (InetAddress a : lp.getDnsServers()) {
                String host = a.getHostAddress();
                if (host == null) continue;
                int scope = host.indexOf('%');
                if (scope >= 0) continue;   // link-local with a scope id; skip
                if (sb.length() > 0) sb.append(',');
                sb.append(host);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
