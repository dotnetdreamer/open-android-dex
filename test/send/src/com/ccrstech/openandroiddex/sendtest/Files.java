package com.ccrstech.openandroiddex.sendtest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The test app's own files, by name, under its cache: what it shares. Its
 * display name is the URI's "name" query parameter, so a share can carry a
 * hostile name that is no file name here. A "hold" parameter makes opening
 * wait that many ms (a share taken back while it is being opened). A "says"
 * parameter is the size it says the file is, whatever it is; the file then
 * comes through a pipe, which has no size of its own to give it away.
 */
public class Files extends ContentProvider {

    static final String AUTHORITY = "com.ccrstech.openandroiddex.sendtest.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String id = uri.getLastPathSegment();
        if (id == null || id.contains("/") || id.contains("..")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(getContext().getCacheDir(), "share/" + id);
        if (!f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String hold = uri.getQueryParameter("hold");
        if (hold != null) SystemClock.sleep(Long.parseLong(hold));
        File f = file(uri);
        if (uri.getQueryParameter("says") == null) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (InputStream in = new FileInputStream(f);
                     OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } catch (IOException ignored) {
                    // the reader went: as a real app's pipe would
                }
            }, "oadx-pipe").start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            String name = uri.getQueryParameter("name");
            String says = uri.getQueryParameter("says");
            c.addRow(new Object[]{name == null ? uri.getLastPathSegment() : name, says != null ? Long.parseLong(says) : file(uri).length()});
        } catch (FileNotFoundException ignored) {
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String t = uri.getQueryParameter("type");
        return t == null ? "application/octet-stream" : t;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
