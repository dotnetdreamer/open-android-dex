package com.ccrstech.openandroiddex.sendtest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the files it is told to and shares them to the launcher's "Send to
 * computer", by name:
 *
 *   am start -n com.ccrstech.openandroiddex.sendtest/.Share --es files "a.bin:1000,empty.bin:0" [--es hold 2000] [--es says 10] [--es tag t1]
 *
 * Each entry is {@code name:size}; a name may be anything (it is only the
 * display name). File i's byte j is {@code (j * 31 + i * 7 + 3) & 0xff}, which
 * the test computes on its side to check every byte that arrived. With
 * {@code says}, each file says it is that many bytes and is handed over as a
 * pipe, which has no size of its own, so the whole file comes after the
 * smaller size it said.
 *
 * <p>{@code --es mode} shares what a sloppy or hostile app shares instead:
 * <ul>
 * <li>{@code strings}: SEND_MULTIPLE whose EXTRA_STREAM is a list of Strings (paths);</li>
 * <li>{@code parcel}: SEND whose EXTRA_STREAM is a Parcelable that is no Uri (an Intent);</li>
 * <li>{@code text-and-picture}: a link as EXTRA_TEXT with a picture of its own in
 *     ClipData only, as a browser shares a page with its preview.</li>
 * <li>{@code knocks}: no share at all — {@code n} knocks on the computer's port
 *     (half S, half C) as fast as they go, with 100 connections held open
 *     and silent beside them, as any app with the Internet could.</li>
 * </ul>
 */
public class Share extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String mode = getIntent().getStringExtra("mode");
        if ("knocks".equals(mode)) {
            knocks(getIntent().getIntExtra("n", 3000));
            finish();
            return;
        }
        if (mode != null) {
            odd(mode);
            finish();
            return;
        }
        String spec = getIntent().getStringExtra("files");
        String hold = getIntent().getStringExtra("hold");
        String says = getIntent().getStringExtra("says");
        // A tag keeps this share's files apart from the next one's (several shares at once): nothing is cleared.
        String tag = getIntent().getStringExtra("tag");
        File dir = new File(getCacheDir(), "share");
        if (tag == null) deleteAll(dir);
        dir.mkdirs();
        ArrayList<Uri> uris = new ArrayList<>();
        String[] entries = spec == null ? new String[0] : spec.split(",");
        try {
            for (int i = 0; i < entries.length; i++) {
                int colon = entries[i].lastIndexOf(':');
                String name = entries[i].substring(0, colon);
                long size = Long.parseLong(entries[i].substring(colon + 1));
                String file = "f" + (tag == null ? "" : tag + "-") + i;
                write(new File(dir, file), i, size);
                Uri.Builder b = new Uri.Builder().scheme("content").authority(Files.AUTHORITY).appendPath(file)
                        .appendQueryParameter("name", name);
                if (hold != null) b.appendQueryParameter("hold", hold);
                if (says != null) b.appendQueryParameter("says", says);
                uris.add(b.build());
            }
        } catch (IOException e) {
            Log.w("oadx-sendtest", "could not make the files", e);
            finish();
            return;
        }
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setComponent(entry());
        send.setType("application/octet-stream");
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clip = ClipData.newRawUri("", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        // A task of its own, as a share sheet starts it: not this app's task, which goes when it finishes.
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(send);
        Log.i("oadx-sendtest", "shared " + uris.size() + " file(s)");
        finish();
    }

    private static ComponentName entry() {
        return new ComponentName("com.ccrstech.openandroiddex.launcher", "com.ccrstech.openandroiddex.launcher.SendActivity");
    }

    private void odd(String mode) {
        Intent send;
        switch (mode) {
            case "strings": {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.setType("image/jpeg");
                ArrayList<String> paths = new ArrayList<>();
                paths.add("/sdcard/DCIM/Camera/oadx-not-a-uri.jpg");
                paths.add("file:///sdcard/oadx-not-a-uri-either.jpg");
                send.putStringArrayListExtra(Intent.EXTRA_STREAM, paths);
                break;
            }
            case "parcel":
                send = new Intent(Intent.ACTION_SEND);
                send.setType("image/jpeg");
                send.putExtra(Intent.EXTRA_STREAM, new Intent("oadx.not.a.uri"));
                break;
            case "text-and-picture": {
                File dir = new File(getCacheDir(), "share");
                deleteAll(dir);
                dir.mkdirs();
                try {
                    write(new File(dir, "f0"), 0, 4000);
                } catch (IOException e) {
                    Log.w("oadx-sendtest", "could not make the picture", e);
                    return;
                }
                Uri picture = new Uri.Builder().scheme("content").authority(Files.AUTHORITY).appendPath("f0")
                        .appendQueryParameter("name", "oadx-preview.png").appendQueryParameter("type", "image/png").build();
                send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra("text"));
                send.setClipData(ClipData.newRawUri("", picture));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                break;
            }
            default:
                Log.w("oadx-sendtest", "no such mode: " + mode);
                return;
        }
        send.setComponent(entry());
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(send);
        Log.i("oadx-sendtest", "shared as " + mode);
    }

    private static void knocks(int n) {
        new Thread(() -> {
            long t0 = SystemClock.uptimeMillis();
            List<Socket> silent = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress("127.0.0.1", 7190), 500);
                    silent.add(s);
                } catch (IOException e) {
                    break;
                }
            }
            int ok = 0;
            for (int i = 0; i < n; i++) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", 7190), 500);
                    s.getOutputStream().write(i % 2 == 0 ? 'S' : 'C');
                    ok++;
                } catch (IOException ignored) {
                }
            }
            for (Socket s : silent) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            Log.i("oadx-sendtest", "knocked " + ok + " of " + n + " times, " + silent.size() + " held silent, in "
                    + (SystemClock.uptimeMillis() - t0) + " ms");
        }, "oadx-knocks").start();
    }

    private static void write(File f, int i, long size) throws IOException {
        byte[] buf = new byte[1 << 16];
        try (FileOutputStream out = new FileOutputStream(f)) {
            long j = 0;
            while (j < size) {
                int n = (int) Math.min(buf.length, size - j);
                for (int k = 0; k < n; k++) buf[k] = (byte) (((j + k) * 31 + i * 7 + 3) & 0xff);
                out.write(buf, 0, n);
                j += n;
            }
        }
    }

    private static void deleteAll(File d) {
        File[] all = d.listFiles();
        if (all != null) for (File f : all) f.delete();
    }
}
