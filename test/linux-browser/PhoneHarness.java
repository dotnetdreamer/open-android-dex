package com.ccrstech.openandroiddex.launcher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * "Open in a browser" on a real phone, for test/linux-browser-test.mjs
 * {@code --device}: LinuxBrowserServer, LinuxBrowserPeek, LinuxStream and
 * its encoder exactly as LinuxOnDroid runs them, started by
 * {@code app_process} as the shell user. Nothing is installed and no app is
 * touched. The desktop is test/usb-desktop/StandinXvnc on the phone's own
 * loopback, changing every pixel of every frame: the hardest picture there
 * is for the encoder, the network and the decoder.
 *
 * <p>Usage: PhoneHarness &lt;dir&gt; &lt;port&gt; &lt;W&gt;x&lt;H&gt; &lt;password&gt; &lt;page dir&gt;
 *
 * <p>Prints {@code READY <port> <code> <fingerprint>}, the stand-in's
 * {@code EV} lines, and {@code STREAMING true|false}. Takes
 * {@code clip <base64>}, {@code code}, {@code slowhandoff <ms>} (a browser
 * let in waits that long, said with {@code HANDOFF}, before LinuxStream
 * has it: the moment a sign-out or the switch could miss it),
 * {@code desktop starting|running} (a Linux still booting, or up), and
 * {@code signout}, {@code off}, {@code on} and {@code alive}, which run on a
 * main thread that, as an app's does, dies on any network use. Exits when
 * stdin closes, or after five minutes with nobody watching.
 *
 * <p>A browser's microphone goes where Linux's PulseAudio would read it: a
 * pipe in the state directory ({@code mic}), made here and read as
 * module-pipe-source reads it (open both ways, read as it comes), the bytes
 * and the loudness said once a second as {@code MIC <bytes> <rms>}.
 * {@code micpipe none} points LinuxStream at a pipe that is not there (a
 * Linux started by an older app), {@code micpipe fifo} back at this one.
 */
public final class PhoneHarness {
    /** How long a browser let in waits before LinuxStream has it; 0 for not at all. */
    static volatile long slowHandoffMs;
    /**
     * What the desktop is, as LinuxService would say it: "running", or
     * "starting" ({@code desktop starting}) to hold a session in
     * LinuxStream's wait for it, as a Linux that takes its time to boot does.
     */
    static volatile String desktop = "running";

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        int port = Integer.parseInt(args[1]);
        String[] wh = args[2].split("x");
        String password = args[3];
        File page = new File(args[4]);
        // MediaCodec makes Handlers on some devices, which need a looper.
        android.os.Looper.prepareMainLooper();
        StandinXvnc xvnc = new StandinXvnc(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), password);
        LinuxStream.rfbPort = xvnc.port;
        final long[] idleSince = {System.currentTimeMillis()};
        final int[] sessions = {0};
        LinuxStream.start("oadx-browser-test.linux-stream", new LinuxStream.Host() {
            @Override
            public String desktopState() {
                return desktop;
            }

            @Override
            public void startDesktop(int w, int h) {
            }

            @Override
            public String vncPassword() {
                return password;
            }

            @Override
            public String stoppedWhy(long waitMs) {
                return null;
            }

            @Override
            public synchronized void streaming(boolean on) {
                sessions[0] += on ? 1 : -1;
                idleSince[0] = System.currentTimeMillis();
                System.out.println("STREAMING " + on);
                System.out.flush();
            }

            @Override
            public String phoneId() {
                return "harness";
            }

            @Override
            public String phoneName() {
                return "Phone harness";
            }
        });
        // Linux's microphone, as PulseAudio's pipe source holds it.
        final File micFifo = new File(dir, "mic");
        dir.mkdirs();
        micFifo.delete();
        // The shell may not make a pipe in /data/local/tmp (SELinux, on the
        // S25: EACCES); then a plain file, read as it grows, stands in for it.
        boolean fifo;
        try {
            android.system.Os.mkfifo(micFifo.getPath(), 0600);
            fifo = true;
        } catch (android.system.ErrnoException e) {
            new java.io.FileOutputStream(micFifo).close();
            fifo = false;
            System.out.println("MIC a plain file for the pipe (" + e.getMessage() + ")");
            System.out.flush();
        }
        final boolean isFifo = fifo;
        LinuxStream.micPipe = micFifo;
        Thread micReader = new Thread(() -> {
            try {
                java.io.FileDescriptor fd = android.system.Os.open(micFifo.getPath(),
                        isFifo ? android.system.OsConstants.O_RDWR : android.system.OsConstants.O_RDONLY, 0);
                byte[] buf = new byte[4096];
                long total = 0;
                long reported = System.currentTimeMillis();
                double loud = 0;
                while (true) {
                    int n = android.system.Os.read(fd, buf, 0, buf.length);
                    if (n <= 0) {
                        Thread.sleep(5);
                        continue;
                    }
                    total += n;
                    double sum = 0;
                    for (int k = 0; k + 1 < n; k += 2) {
                        short v = (short) ((buf[k] & 0xff) | (buf[k + 1] << 8));
                        sum += (double) v * v;
                    }
                    loud = Math.max(loud * 0.9, Math.sqrt(sum / Math.max(1, n / 2)));
                    long now = System.currentTimeMillis();
                    if (now - reported >= 1000) {
                        System.out.println("MIC " + total + " " + Math.round(loud));
                        System.out.flush();
                        reported = now;
                    }
                }
            } catch (Exception e) {
                System.out.println("MIC failed " + e);
                System.out.flush();
            }
        }, "harness-mic");
        micReader.setDaemon(true);
        micReader.start();
        LinuxBrowser store = new LinuxBrowser(dir);
        store.setOn(true);
        // The phone's card "showing the code": pairing is open, as it is while the page is in front.
        store.openPairing(System.currentTimeMillis());
        LinuxBrowserCert cert = LinuxBrowserCert.loadOrMake(dir, LinuxBrowserServer.ips(), System.currentTimeMillis());
        LinuxBrowserServer.Host host = new LinuxBrowserServer.Host() {
            @Override
            public boolean stream(LinuxStreamSocket ws, String hello, Closeable sock, String who,
                                  LinuxBrowserServer.Gate gate, Runnable ended) {
                long wait = slowHandoffMs;
                if (wait > 0) {
                    // Let in by the server (its token checked, no longer one of
                    // the connections a sign-out drops) and not yet LinuxStream's.
                    System.out.println("HANDOFF " + who);
                    System.out.flush();
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                try {
                    return LinuxStream.serve(ws, new JSONObject(hello), sock, who, gate, ended);
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public String phoneName() {
                return android.os.Build.MODEL + " harness";
            }

            @Override
            public byte[] asset(String name) {
                try {
                    return Files.readAllBytes(new File(page, name).toPath());
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void changed() {
                System.out.println("STATE " + store.state());
                System.out.flush();
            }
        };
        final LinuxBrowserServer[] server = {LinuxBrowserServer.start(store, cert, host, new LinuxBrowserPeek(), port)};
        System.out.println("READY " + server[0].port() + " " + store.code() + " " + cert.fingerprint + " "
                + LinuxBrowser.addresses().get(0).ip);
        System.out.flush();
        // This thread becomes the main thread LinuxService's commands run on,
        // with the rule an app's main thread has: no network, or the process
        // dies (NetworkOnMainThreadException). The switch going off crashed
        // LinuxOnDroid on the S25 that way, and nothing else here has the rule.
        android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                .detectNetwork().penaltyDeathOnNetwork().build());
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Thread stdin = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("clip ")) {
                        xvnc.sendClipboard(new String(Base64.getDecoder().decode(line.substring(5).trim()), StandardCharsets.UTF_8));
                    } else if (line.equals("code")) {
                        System.out.println("CODE " + store.code());
                        System.out.flush();
                    } else if (line.startsWith("slowhandoff ")) {
                        slowHandoffMs = Long.parseLong(line.substring(12).trim());
                        System.out.println("OK slowhandoff " + slowHandoffMs);
                        System.out.flush();
                    } else if (line.equals("signout")) {
                        // As the Settings page and LinuxService's BROWSER_SIGNOUT do
                        // it: the tokens, then on the main thread what they let in.
                        store.signOutAll();
                        main.post(() -> {
                            LinuxStream.endBrowser("unauthorized");
                            server[0].dropOpen();
                            System.out.println("OK signout");
                            System.out.flush();
                        });
                    } else if (line.equals("off")) {
                        // As LinuxService's stopBrowserServer does it, on the main thread.
                        main.post(() -> {
                            server[0].stop();
                            LinuxStream.endBrowser("off");
                            System.out.println("OK off");
                            System.out.flush();
                        });
                    } else if (line.equals("on")) {
                        // As LinuxService's startBrowserServer: off the main thread, the same port.
                        try {
                            server[0] = LinuxBrowserServer.start(store, cert, host, new LinuxBrowserPeek(), port);
                            System.out.println("OK on");
                        } catch (Exception e) {
                            System.out.println("FAILED on " + e);
                        }
                        System.out.flush();
                    } else if (line.startsWith("desktop ")) {
                        desktop = line.substring(8).trim();
                        System.out.println("OK desktop " + desktop);
                        System.out.flush();
                    } else if (line.startsWith("micpipe ")) {
                        LinuxStream.micPipe = line.endsWith(" none") ? new File(dir, "no-such-pipe") : micFifo;
                        System.out.println("OK micpipe " + LinuxStream.micPipe.getName());
                        System.out.flush();
                    } else if (line.equals("alive")) {
                        main.post(() -> {
                            System.out.println("ALIVE");
                            System.out.flush();
                        });
                    }
                }
            } catch (Exception ignored) {
            }
            server[0].stop();
            System.exit(0);
        });
        stdin.setDaemon(true);
        stdin.start();
        Runnable idle = new Runnable() {
            @Override
            public void run() {
                if (sessions[0] <= 0 && System.currentTimeMillis() - idleSince[0] > 300_000) {
                    server[0].stop();
                    System.exit(0);
                }
                main.postDelayed(this, 1000);
            }
        };
        main.postDelayed(idle, 1000);
        android.os.Looper.loop();
    }
}
