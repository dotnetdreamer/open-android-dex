package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remote desktop from anywhere, in a browser: a Cloudflare quick tunnel to the
 * phone's door (LinuxBrowserServer), so a browser on any network reaches the
 * same page, the same code and the same stream as one on the phone's Wi-Fi.
 *
 * <p>{@code libcloudflared.so} is cloudflared, built for Android (GOOS=android,
 * so its lookups go through Android's resolver; the Linux build's own
 * resolver wants /etc/resolv.conf, which Android does not have). It dials out
 * to Cloudflare, which gives it a random {@code https://….trycloudflare.com}
 * address and carries browsers' requests back through that one connection: no
 * port opened, nothing to set up, and nothing for anyone to pay. The address is
 * new every time the tunnel starts, which the card says.
 *
 * <p>Said plainly, as the card says it too: Cloudflare describes quick tunnels
 * as being for testing and development, without an uptime promise. The
 * address is a door like the Wi-Fi one: the same six-digit code lets a
 * browser in, once, and the same lockout holds the guessing back.
 *
 * <p>Runs in {@code :linux}, beside the door, while Remote desktop and its
 * "from anywhere" switch are on ({@link LinuxService}).
 */
final class PhoneTunnel {

    private static final Pattern ADDRESS = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final long BACKOFF_MIN_MS = 5_000;
    private static final long BACKOFF_MAX_MS = 120_000;

    private static PhoneTunnel instance;

    private final Context ctx;
    private volatile Process process;
    private volatile boolean wanted;
    private volatile int port;
    private long backoff = BACKOFF_MIN_MS;
    private Thread runner;

    private PhoneTunnel(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static synchronized PhoneTunnel of(Context ctx) {
        if (instance == null) instance = new PhoneTunnel(ctx);
        return instance;
    }

    static File binary(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
    }

    /** Whether this install can make the tunnel at all. */
    static boolean available(Context ctx) {
        return binary(ctx).exists();
    }

    static boolean switchedOn(Context ctx) {
        return new File(PhoneRemote.dir(ctx), "anywhere").exists();
    }

    static void setSwitchedOn(Context ctx, boolean on) {
        File f = new File(PhoneRemote.dir(ctx), "anywhere");
        if (!on) {
            f.delete();
            return;
        }
        try {
            PhoneRemote.dir(ctx).mkdirs();
            f.createNewFile();
        } catch (Exception e) {
            DexLog.warn("phone", "cannot record the tunnel as on", e);
        }
    }

    /** The address browsers open from anywhere, or "" while there is none. */
    static String address(Context ctx) {
        return PhoneRemote.read(addressFile(ctx)).trim();
    }

    private static File addressFile(Context ctx) {
        return new File(PhoneRemote.dir(ctx), "tunnel-address");
    }

    /** Up, pointed at the door's port, or down: the service calls this with every change. */
    synchronized void want(boolean on, int doorPort) {
        if (on && port != doorPort && process != null) stopProcess();
        port = doorPort;
        if (on == wanted) return;
        wanted = on;
        if (on) {
            backoff = BACKOFF_MIN_MS;
            runner = new Thread(this::run, "phone-tunnel");
            runner.setDaemon(true);
            runner.start();
        } else {
            stopProcess();
            if (runner != null) runner.interrupt();
            runner = null;
            forget();
        }
    }

    private void run() {
        // An address left by a process that was killed (an update, say) is dead.
        forget();
        while (wanted) {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                ProcessBuilder pb = new ProcessBuilder(binary(ctx).getAbsolutePath(), "tunnel",
                        "--no-autoupdate", "--url", "https://127.0.0.1:" + port, "--no-tls-verify");
                pb.redirectErrorStream(true);
                pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
                Process p = pb.start();
                process = p;
                DexLog.step("phone", "asking Cloudflare for an address");
                try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    boolean found = false;
                    for (String line; (line = in.readLine()) != null; ) {
                        Matcher m = ADDRESS.matcher(line);
                        // The first one is the address; later lines name it too (requests, errors).
                        if (!found && m.find() && !line.contains("api.trycloudflare.com")) {
                            found = true;
                            String url = m.group();
                            PhoneRemote.write(addressFile(ctx), url);
                            LinuxBrowserServer.tunnelHost = url.substring("https://".length());
                            DexLog.step("phone", "reachable from anywhere at " + url);
                            // A new address: emailed, when the person asked for that (EmailNotice).
                            EmailNotice.pokeLater(ctx, "tunnel");
                        } else if (line.contains("Registered tunnel connection")) {
                            backoff = BACKOFF_MIN_MS;
                        }
                    }
                }
                int rc = p.waitFor();
                if (wanted) DexLog.warn("phone", "the tunnel ended (" + rc + "); again shortly");
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (wanted) DexLog.warn("phone", "the tunnel did not start", e);
            } finally {
                process = null;
                forget();
            }
            if (!wanted) break;
            // A tunnel that lasted is started again at once; one that keeps
            // failing waits longer each time, up to two minutes.
            long lasted = android.os.SystemClock.elapsedRealtime() - started;
            long wait = lasted > 60_000 ? BACKOFF_MIN_MS : backoff;
            backoff = Math.min(BACKOFF_MAX_MS, backoff * 2);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        if (p != null) p.destroy();
    }

    private void forget() {
        boolean had = addressFile(ctx).delete();
        LinuxBrowserServer.tunnelHost = null;
        if (had) EmailNotice.pokeLater(ctx, "tunnel");
    }
}
