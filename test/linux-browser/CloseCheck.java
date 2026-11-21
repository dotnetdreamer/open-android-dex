package com.ccrstech.openandroiddex.launcher;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * LinuxStreamSocket's close on a link whose far end has stopped reading, for
 * test/linux-browser-test.mjs {@code --device}, run on the phone by
 * {@code app_process}: Android's own sockets, Linux's own TCP.
 *
 * <p>The send buffer is full, and nobody holds the socket's lock (a writer
 * that finished its frame and waits for acknowledgements leaves it so): the
 * goodbye's two bytes have nowhere to go. Until 2026-11-21 close() wrote them
 * and waited there for as long as the link stayed up, and with it whoever
 * ended the session. Prints
 * {@code CLOSE returned=<bool> ms=<n> woke=<bool>}: whether close() returned
 * within five seconds, how long it took, and whether the writer it was stuck
 * behind was woken too.
 *
 * <p>Then the goodbye said by {@code sayClose} on a link as full, stuck in
 * its write: {@code SAYCLOSE returned=<bool> ms=<n> woke=<bool>}, the close
 * that the session's deadline brings, and whether that frees it.
 */
public final class CloseCheck {
    public static void main(String[] args) throws Exception {
        report("CLOSE", false);
        report("SAYCLOSE", true);
        System.exit(0);
    }

    private static void report(String name, boolean viaSayClose) throws Exception {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReceiveBufferSize(4096);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            Socket far = new Socket();
            far.setReceiveBufferSize(4096);
            far.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), ss.getLocalPort()));
            Socket near = ss.accept();
            near.setSendBufferSize(4096);
            OutputStream raw = near.getOutputStream();
            LinuxStreamSocket ws = new LinuxStreamSocket(near.getInputStream(), raw, near);
            // The far end never reads: written to around the socket's lock
            // until the buffers are full and the write waits.
            final java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
            Thread filler = new Thread(() -> {
                byte[] b = new byte[1024];
                try {
                    while (true) {
                        raw.write(b);
                        written.addAndGet(b.length);
                    }
                } catch (Exception done) {
                    // woken by the close
                }
            }, "close-check-filler");
            filler.setDaemon(true);
            filler.start();
            long last = -1;
            for (int i = 0; i < 100 && written.get() != last; i++) {
                last = written.get();
                Thread.sleep(100);
            }
            Thread stuck = null;
            if (viaSayClose) {
                // The goodbye waits in its write, its lock held.
                stuck = new Thread(() -> ws.sayClose(100), "close-check-goodbye");
                stuck.setDaemon(true);
                stuck.start();
                Thread.sleep(300);
            }
            long t0 = System.nanoTime();
            Thread closer = new Thread(ws::close, "close-check-closer");
            closer.setDaemon(true);
            closer.start();
            closer.join(5000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            filler.join(2000);
            if (stuck != null) stuck.join(2000);
            boolean woke = !filler.isAlive() && (stuck == null || !stuck.isAlive());
            System.out.println(name + " returned=" + !closer.isAlive() + " ms=" + ms + " woke=" + woke
                    + " filled=" + written.get());
            System.out.flush();
            try {
                far.close();
            } catch (Exception ignored) {
            }
            if (closer.isAlive()) {
                // Stuck for good: nothing after this can be measured in this process.
                System.exit(0);
            }
        }
    }
}
