package com.ccrstech.openandroiddex.launcher;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Socket;

/**
 * The first byte a browser's connection sent, looked at without taking it:
 * {@link LinuxBrowserServer}'s {@link LinuxBrowserServer.Sniffer} on a phone.
 *
 * <p>The server answers TLS and plain HTTP on one port, and only the first
 * byte tells them apart. A computer's Java can be handed that byte back when
 * it layers TLS over the socket; Android's SSLSocketFactory has no such
 * method. So the byte is never taken: {@code recv} with {@code MSG_PEEK}, on
 * a duplicate of the socket's descriptor, after a {@code poll} that bounds the
 * wait.
 */
final class LinuxBrowserPeek implements LinuxBrowserServer.Sniffer {

    @Override
    public int peek(Socket s, int timeoutMs) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(s)) {
            FileDescriptor fd = pfd.getFileDescriptor();
            StructPollfd p = new StructPollfd();
            p.fd = fd;
            p.events = (short) OsConstants.POLLIN;
            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (true) {
                int left = (int) (deadline - SystemClock.uptimeMillis());
                if (left <= 0) return -1;
                try {
                    if (Os.poll(new StructPollfd[]{p}, left) == 0) return -1;
                    break;
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EINTR) throw new IOException(e);
                }
            }
            byte[] b = new byte[1];
            int got = Os.recvfrom(fd, b, 0, 1, OsConstants.MSG_PEEK, null);
            return got == 1 ? b[0] & 0xff : -1;
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }
}
