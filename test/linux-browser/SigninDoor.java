package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's door on this computer's JVM, for test/linux-browser/signin-check.mjs: reached as
 * cloudflared on a phone reaches it (loopback, the tunnel's name in Host), with one sign-in link
 * made for that name. Prints {"port","secret"}; on stdin "state" prints the browsers let in and the
 * links that still work, "link" a new link's secret; ends when stdin closes, or after two minutes.
 * Compiled with test/linux-browser/DexLog.java in place of Android's log, as LinkCheck is.
 */
final class SigninDoor {
    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]);
        File dir = Files.createTempDirectory("oadx-signin-").toFile();
        LinuxBrowser store = new LinuxBrowser(dir);
        store.setOn(true);
        long now = System.currentTimeMillis();
        LinuxBrowserCert cert = LinuxBrowserCert.make(dir, Collections.<String>emptyList(), LinuxBrowserCert.NAME, now);
        LinuxBrowserServer door = LinuxBrowserServer.start(store, cert, new LinuxBrowserServer.Host() {
            @Override
            public boolean stream(LinuxStreamSocket ws, String hello, java.io.Closeable sock, String who,
                                  LinuxBrowserServer.Gate gate, Runnable ended) {
                return false;
            }

            @Override
            public String phoneName() {
                return "Test phone";
            }

            @Override
            public byte[] asset(String name) {
                try {
                    return Files.readAllBytes(new File(assets, name).toPath());
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void changed() {
            }

            @Override
            public boolean linuxOn() {
                return false;
            }
        }, null, 0);
        int port = door.port();
        // A browser sends the port in Host, which a real tunnel's 443 leaves out.
        String host = "quiet-river-fox.trycloudflare.com:" + port;
        LinuxBrowserServer.tunnelHost = host;
        List<String> s = store.newLinks(Collections.singletonList(host), now);
        store.confirmLinks(s);
        System.out.println("{\"port\":" + port + ",\"secret\":\"" + s.get(0) + "\"}");
        System.out.flush();
        Thread stop = new Thread(() -> {
            try {
                Thread.sleep(120_000);
            } catch (InterruptedException ignored) {
            }
            System.exit(2);
        });
        stop.setDaemon(true);
        stop.start();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = in.readLine()) != null; ) {
            if (line.equals("state")) {
                System.out.println("{\"tokens\":" + store.tokenCount() + ",\"links\":" + store.linkCount(System.currentTimeMillis()) + "}");
            } else if (line.equals("link")) {
                List<String> next = store.newLinks(Collections.singletonList(host), System.currentTimeMillis());
                store.confirmLinks(next);
                System.out.println("{\"secret\":\"" + next.get(0) + "\"}");
            }
            System.out.flush();
        }
        door.stop();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
        System.exit(0);
    }
}
