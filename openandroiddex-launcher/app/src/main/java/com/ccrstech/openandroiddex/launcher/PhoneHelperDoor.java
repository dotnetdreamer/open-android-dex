package com.ccrstech.openandroiddex.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * Where a helper the app started itself (PhoneHelper {@code --register},
 * through {@link PhoneHelperStart}) hands the app its port and its key:
 * Android's {@code content call}, run by the helper as the shell.
 *
 * <p>The one thing that makes such a key trustworthy is who hands it over,
 * and Android says that: {@link Binder#getCallingUid()} is the caller's uid,
 * which no app can fake. Only the shell (2000) or root (0) is believed. The
 * manifest also guards the door with DUMP, a permission the shell holds and
 * no app is given, so another app cannot reach it at all.
 */
public final class PhoneHelperDoor extends ContentProvider {

    /** The door's authority after the app's package. */
    static final String SUFFIX = ".helper";
    static final String METHOD = "helper";
    static final String OK = "ok";
    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) {
            DexLog.warn("phone", "helper door: a call from uid " + uid + " refused");
            return null;
        }
        if (!METHOD.equals(method) || arg == null) return null;
        String[] w = arg.trim().split(" ");
        if (w.length != 2 || !w[0].matches("\\d{4,5}") || !w[1].matches("[0-9a-f]{64}")) return null;
        if (getContext() == null || !PhoneHelperLink.adopt(getContext(), w[0], w[1])) return null;
        DexLog.step("phone", "the phone's helper handed over its key (uid " + uid + ", port " + w[0] + ")");
        Bundle b = new Bundle();
        b.putString(METHOD, OK);
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
