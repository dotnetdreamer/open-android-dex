package com.ccrstech.openandroiddex.launcher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Drives the launcher's own LinuxStreamRfb — the file itself, compiled for a
 * computer's JVM — against rfb-standin.mjs, and prints what it saw as JSON
 * lines for test/usb-desktop-test.mjs to check.
 *
 * Usage: RfbCheck <port> <password> [<texts> <clips>]
 *
 * {@code <texts>} is a file of the computer's clipboard texts, base64 UTF-8,
 * one a line, given to the guest in turn; {@code <clips>} is how many of the
 * guest's to wait for at the end. Along the way it prints
 * {@code {"offered":true}} once the texts are given (the harness has
 * something in the guest paste again), takes the last one back, and prints
 * {@code {"ready":true}} when it is waiting for the guest's.
 */
public final class RfbCheck {

    /** rfb-standin.mjs's pixelAt, in the B,G,R,X it sends. */
    static int[] expected(int x, int y, int n, int w) {
        int bar = (n * 12) % Math.max(1, w);
        if (x >= bar && x < bar + 48) return new int[]{255, 255, 255};
        return new int[]{(x * 255 / Math.max(1, w - 1)), (y * 7) & 0xff, (n * 3) & 0xff};
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String password = args[1];
        List<String> texts = new ArrayList<>();
        int expectClips = 1;
        if (args.length > 3) {
            for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.US_ASCII)) {
                texts.add(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
            }
            expectClips = Integer.parseInt(args[3]);
        } else {
            texts.add("héllo\r\nworld ☃");
        }
        LinuxStreamRfb rfb;
        try {
            rfb = LinuxStreamRfb.connect(port, password, 2000);
        } catch (LinuxStreamRfb.AuthFailed e) {
            System.out.println("{\"authFailed\":true}");
            return;
        }
        final List<String> resizes = new ArrayList<>();
        final int[] rects = {0};
        final int[] updates = {0};
        final int[] mismatches = {0};
        final int[] checked = {0};
        final String[] cursor = {""};
        final List<String> clips = new ArrayList<>();
        final int[] frame = {1};
        final LinuxStreamRfb r = rfb;
        LinuxStreamRfb.Handler h = new LinuxStreamRfb.Handler() {
            @Override
            public void onResize(int w, int hh) {
                resizes.add(w + "x" + hh);
            }

            @Override
            public void onRect(int x, int y, int w, int hh, ByteBuffer px) {
                rects[0]++;
                // Every 97th pixel of every rectangle, against what the stand-in drew.
                for (int i = 0; i < w * hh; i += 97) {
                    int px0 = x + i % w, py0 = y + i / w;
                    int[] want = expected(px0, py0, frame[0], r.width);
                    int b = px.get(i * 4) & 0xff, g = px.get(i * 4 + 1) & 0xff, rr = px.get(i * 4 + 2) & 0xff;
                    checked[0]++;
                    if (rr != want[0] || g != want[1] || b != want[2]) mismatches[0]++;
                }
            }

            @Override
            public void onUpdateEnd() {
                updates[0]++;
                frame[0]++;
            }

            @Override
            public void onCursor(int hx, int hy, int w, int hh, byte[] rgba) {
                // the stand-in's: red, a triangle of opaque pixels
                cursor[0] = hx + "," + hy + "," + w + "x" + hh + ",r" + (rgba[0] & 0xff) + ",a0=" + (rgba[3] & 0xff)
                        + ",aLast=" + (rgba[rgba.length - 1] & 0xff) + ",a1=" + (rgba[7] & 0xff);
            }

            @Override
            public void onClipboard(String text) {
                clips.add(text);
            }
        };
        String swizzle = rfb.swizzle();
        // the full update, then five incremental ones
        while (updates[0] < 1) rfb.readMessage(h);
        for (int i = 0; i < 5; i++) {
            rfb.requestUpdate(true);
            int before = updates[0];
            while (updates[0] == before) rfb.readMessage(h);
        }
        rfb.pointer(10, 20, 1);
        rfb.pointer(99999, 99999, 0); // held to the desktop
        rfb.key(0x61, true);
        rfb.key(0x61, false);
        // The computer's clipboard, each text in turn, with time for the
        // guest to ask for it; then once more when the harness pastes again,
        // and then taken back.
        for (String t : texts) {
            rfb.clipboard(t);
            pump(rfb, h, updates, 250);
        }
        System.out.println("{\"offered\":true}");
        System.out.flush();
        pump(rfb, h, updates, 600);
        rfb.clipboard("");
        pump(rfb, h, updates, 250);
        boolean resizable = rfb.canResize();
        rfb.setDesktopSize(800, 600);
        rfb.requestUpdate(true);
        // The resize and the picture at the new size come in one update.
        while (!resizes.contains("800x600")) rfb.readMessage(h);
        rfb.requestUpdate(false);
        int before = updates[0];
        while (updates[0] == before) rfb.readMessage(h);
        // the guest's clipboard, which the stand-in sends unasked once this line asks for it
        System.out.println("{\"ready\":true}");
        System.out.flush();
        long until = System.currentTimeMillis() + 8000;
        while (clips.size() < expectClips && System.currentTimeMillis() < until) pump(rfb, h, updates, 50);
        rfb.close();
        StringBuilder got = new StringBuilder();
        for (String c : clips) {
            if (got.length() > 0) got.append(',');
            got.append('"').append(Base64.getEncoder().encodeToString(c.getBytes(StandardCharsets.UTF_8))).append('"');
        }
        System.out.println("{\"swizzle\":\"" + swizzle + "\",\"name\":\"" + rfb.name + "\",\"resizes\":\"" + String.join(" ", resizes)
                + "\",\"updates\":" + updates[0] + ",\"rects\":" + rects[0] + ",\"checked\":" + checked[0]
                + ",\"mismatches\":" + mismatches[0] + ",\"cursor\":\"" + cursor[0] + "\",\"resizable\":" + resizable
                + ",\"clips\":[" + got + "]}");
    }

    /** Keep the conversation going for {@code ms}: updates asked for and read, and whatever else Xvnc says between them. */
    static void pump(LinuxStreamRfb rfb, LinuxStreamRfb.Handler h, int[] updates, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        do {
            rfb.requestUpdate(true);
            int before = updates[0];
            while (updates[0] == before) rfb.readMessage(h);
        } while (System.currentTimeMillis() < until);
    }
}
