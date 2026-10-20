package com.ccrstech.openandroiddex.launcher;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves the launcher's own LinuxStreamSocket — the file itself, on a
 * computer's JVM, over TCP in place of the phone's abstract socket — for
 * test/usb-desktop-test.mjs: {@code GET /probe} answers a JSON body, and
 * {@code /stream} upgrades and echoes every message back, text as
 * "echo:<text>" and binary with one byte, 0x7e, in front.
 *
 * Prints the port, then serves until killed.
 */
public final class SocketCheck {
    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        System.out.println(server.getLocalPort());
        System.out.flush();
        while (true) {
            Socket s = server.accept();
            new Thread(() -> serve(s)).start();
        }
    }

    private static void serve(Socket s) {
        try {
            LinuxStreamSocket conn = new LinuxStreamSocket(s.getInputStream(), s.getOutputStream(), s);
            LinuxStreamSocket.Request req = conn.readRequest();
            if ("/probe".equals(req.path)) {
                conn.respond(200, "OK", "application/json",
                        "{\"v\":1,\"id\":\"test-id\",\"name\":\"Test\",\"state\":\"running\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                conn.upgrade(req);
            } catch (Exception refused) {
                s.close();
                return;
            }
            while (true) {
                LinuxStreamSocket.Message m = conn.read();
                if (m == null) break;
                if (m.opcode == LinuxStreamSocket.OP_TEXT) {
                    conn.sendText("echo:" + m.text());
                } else {
                    conn.sendBinary(new byte[]{0x7e}, m.data, m.data.length);
                }
            }
            conn.close();
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
