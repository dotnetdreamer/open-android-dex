package com.ccrstech.openandroiddex.launcher;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LinuxBrowser's sign-out while browsers' requests read its list of tokens,
 * as every request a browser makes does ({@link LinuxBrowser#tokenValid}),
 * for test/linux-browser-test.mjs. Prints {@code STORE signouts=<n>
 * left=<rounds a token outlived its sign-out> kept=<rounds the code stayed>
 * reads=<n>}.
 *
 * <p>A file is written whole (a temporary file moved over the old one). On
 * Windows, where the stand-in tests run, that move is refused while another
 * thread has the file open, and until 2026-11-20 the refusal was only logged:
 * a sign-out that met a read left every browser in (one round in 72 of the
 * suite's race of a sign-out and a pairing). Android replaces a file whoever
 * is reading it.
 */
final class StoreCheck {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("oadx-store-").toFile();
        LinuxBrowser store = new LinuxBrowser(dir);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        // Two browsers' requests, now and then, as a page's are (a ping every
        // two seconds, files as it loads): a read, then a moment's rest.
        for (int r = 0; r < 2; r++) {
            Thread t = new Thread(() -> {
                while (!done.get()) {
                    store.tokenValid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
                    reads.incrementAndGet();
                    try {
                        Thread.sleep(0, 200_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        int rounds = 200;
        int left = 0;
        int kept = 0;
        for (int i = 0; i < rounds; i++) {
            String token = store.addToken("Browser " + i);
            String before = store.code();
            store.signOutAll();
            if (store.tokenCount() != 0 || store.tokenValid(token)) {
                left++;
                // The next round starts from none.
                while (store.tokenCount() != 0) store.signOutAll();
            }
            if (store.code().equals(before)) kept++;
        }
        done.set(true);
        System.out.println("STORE signouts=" + rounds + " left=" + left + " kept=" + kept + " reads=" + reads.get());
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
