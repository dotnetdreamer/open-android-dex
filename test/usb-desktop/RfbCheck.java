package com.ccrstech.openandroiddex.launcher;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the launcher's own LinuxStreamRfb — the file itself, compiled for a
 * computer's JVM — against rfb-standin.mjs, and prints what it saw as one
 * JSON line for test/usb-desktop-test.mjs to check.
 *
 * Usage: RfbCheck <port> <password>
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
        final String[] clip = {""};
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
                clip[0] = text;
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
        rfb.clipboard("héllo\r\nworld ☃");
        boolean resizable = rfb.canResize();
        rfb.setDesktopSize(800, 600);
        rfb.requestUpdate(true);
        // The resize and the picture at the new size come in one update.
        while (!resizes.contains("800x600")) rfb.readMessage(h);
        rfb.requestUpdate(false);
        int before = updates[0];
        while (updates[0] == before) rfb.readMessage(h);
        // a clipboard the stand-in sends unasked, after this line asks for it
        System.out.println("{\"ready\":true}");
        System.out.flush();
        rfb.requestUpdate(true);
        long until = System.currentTimeMillis() + 3000;
        while (clip[0].isEmpty() && System.currentTimeMillis() < until) rfb.readMessage(h);
        rfb.close();
        System.out.println("{\"swizzle\":\"" + swizzle + "\",\"name\":\"" + rfb.name + "\",\"resizes\":\"" + String.join(" ", resizes)
                + "\",\"updates\":" + updates[0] + ",\"rects\":" + rects[0] + ",\"checked\":" + checked[0]
                + ",\"mismatches\":" + mismatches[0] + ",\"cursor\":\"" + cursor[0] + "\",\"resizable\":" + resizable
                + ",\"clip\":\"" + clip[0] + "\"}");
    }
}
