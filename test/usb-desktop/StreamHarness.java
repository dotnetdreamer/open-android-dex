package com.ccrstech.openandroiddex.launcher;

/**
 * The launcher's LinuxStream — the same classes, nothing changed — run by
 * {@code app_process} as the shell user, for test/usb-desktop-test.mjs
 * {@code --device}. It needs no app installed and touches none: the desktop is
 * {@link StandinXvnc} on the phone's own loopback ({@code standin:<W>x<H>}),
 * or whatever RFB server is on a port, and its state is always "running".
 *
 * Usage: StreamHarness <socket name> <rfb port | standin:WxH> <password>
 *
 * A line {@code clip <base64 UTF-8>} on stdin is the stand-in's clipboard,
 * sent to the client. Exits when its stdin closes (the adb shell that started it went
 * away), or after two minutes with no session.
 */
public final class StreamHarness {
    public static void main(String[] args) throws Exception {
        String name = args[0];
        String password = args[2];
        // MediaCodec makes Handlers on some devices, which need a looper.
        android.os.Looper.prepareMainLooper();
        StandinXvnc standin = null;
        if (args[1].startsWith("standin:")) {
            String[] wh = args[1].substring("standin:".length()).split("x");
            standin = new StandinXvnc(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), password);
            LinuxStream.rfbPort = standin.port;
        } else {
            LinuxStream.rfbPort = Integer.parseInt(args[1]);
        }
        final StandinXvnc xvnc = standin;
        final long[] idleSince = {System.currentTimeMillis()};
        final int[] sessions = {0};
        LinuxStream.start(name, new LinuxStream.Host() {
            @Override
            public String desktopState() {
                return "running";
            }

            @Override
            public void startDesktop(int w, int h) {
            }

            @Override
            public String vncPassword() {
                return password;
            }

            @Override
            public synchronized void streaming(boolean on) {
                sessions[0] += on ? 1 : -1;
                idleSince[0] = System.currentTimeMillis();
                System.out.println("streaming " + on);
                System.out.flush();
            }

            @Override
            public String phoneId() {
                return "harness";
            }

            @Override
            public String phoneName() {
                return "harness";
            }
        });
        System.out.println("listening @" + name);
        System.out.flush();
        Thread stdin = new Thread(() -> {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("clip ") && xvnc != null) {
                        xvnc.sendClipboard(new String(java.util.Base64.getDecoder().decode(line.substring(5).trim()),
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
            System.exit(0);
        });
        stdin.setDaemon(true);
        stdin.start();
        while (true) {
            Thread.sleep(1000);
            if (sessions[0] <= 0 && System.currentTimeMillis() - idleSince[0] > 120_000) System.exit(0);
        }
    }
}
