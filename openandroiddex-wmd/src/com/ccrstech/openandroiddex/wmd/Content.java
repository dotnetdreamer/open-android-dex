package com.ccrstech.openandroiddex.wmd;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.provider.OpenableColumns;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A content URI read as the shell: what an app's ContentResolver does, from a
 * process that is no app the activity manager knows, so its provider is
 * reached as an external one, as {@code content read} reaches it (Providers),
 * and held for one read only. The provider's binder calls gained and changed
 * their first parameters from release to release — the caller's package, its
 * attribution tag, then an AttributionSource — so each is bound by its
 * parameters' types.
 *
 * <p>The shell reads what it was granted: a drag's items, which the launcher
 * passes on as they are dropped on it (PcDrags), and a clip's, which the
 * clipboard grants whichever package reads it.
 */
final class Content {

    private Content() {
    }

    /** What a URI is, as its provider says: its name, size (-1 unknown) and type, each null if it would not say. */
    static final class Described {
        String name;
        long size = -1;
        String mime;
    }

    /** An open URI: its bytes, and how many there are (-1 unknown); closing lets go of its provider. */
    static final class Opened implements Closeable {
        final InputStream in;
        final long length;
        private final AssetFileDescriptor afd;
        private final Held held;

        private Opened(AssetFileDescriptor afd, Held held) throws IOException {
            this.afd = afd;
            this.held = held;
            this.in = afd.createInputStream();
            this.length = afd.getLength();
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                afd.close();
            } catch (IOException ignored) {
            }
            held.close();
        }
    }

    /** {@code uri}'s bytes, to read. */
    static Opened open(Uri uri) throws IOException {
        Held h = Held.acquire(uri);
        try {
            AssetFileDescriptor afd = null;
            FileNotFoundException notFound = null;
            try {
                afd = (AssetFileDescriptor) call(h.provider, "openAssetFile", uri, "r");
            } catch (FileNotFoundException e) {
                notFound = e;
            }
            // A provider that serves only typed streams: a virtual document's, say.
            if (afd == null) afd = (AssetFileDescriptor) call(h.provider, "openTypedAssetFile", uri, "*/*");
            if (afd == null) throw notFound != null ? notFound : new FileNotFoundException("nothing at " + uri);
            return new Opened(afd, h);
        } catch (IOException | RuntimeException e) {
            h.close();
            throw e;
        }
    }

    /** What {@code uri} is called, how big it is and its type: whatever its provider says. */
    static Described describe(Uri uri) {
        Described d = new Described();
        Held h;
        try {
            h = Held.acquire(uri);
        } catch (IOException e) {
            return d;
        }
        try {
            try {
                d.mime = (String) call(h.provider, "getType", uri, null);
            } catch (Exception ignored) {
            }
            try (Cursor c = (Cursor) call(h.provider, "query", uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE})) {
                if (c != null && c.moveToFirst()) {
                    int name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int size = c.getColumnIndex(OpenableColumns.SIZE);
                    if (name >= 0 && !c.isNull(name)) d.name = c.getString(name);
                    if (size >= 0 && !c.isNull(size)) d.size = c.getLong(size);
                }
            } catch (Exception ignored) {
                // a provider that answers no queries: the computer names it from the URI
            }
        } finally {
            h.close();
        }
        return d;
    }

    /**
     * {@code what} on {@code provider}: openAssetFile (mode), openTypedAssetFile
     * (mime type), query (projection) or getType — in whichever shape this
     * release's interface has it.
     */
    private static Object call(Object provider, String what, Uri uri, Object arg) throws IOException {
        Object attribution = PhoneContext.get().getAttributionSource();
        String shell = PhoneContext.SHELL_PACKAGE;
        for (Method m : provider.getClass().getMethods()) {
            if (!m.getName().equals(what)) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean attributed = p.length > 0 && p[0].getName().equals("android.content.AttributionSource");
            Object[] a;
            switch (what) {
                case "openAssetFile":
                    // (attribution, uri, mode, signal) · (pkg, tag, uri, mode, signal) · (pkg, uri, mode, signal)
                    if (attributed && p.length == 4) a = new Object[]{attribution, uri, arg, null};
                    else if (p.length == 5 && p[0] == String.class && p[1] == String.class) a = new Object[]{shell, null, uri, arg, null};
                    else if (p.length == 4 && p[0] == String.class && p[1] == Uri.class) a = new Object[]{shell, uri, arg, null};
                    else continue;
                    break;
                case "openTypedAssetFile":
                    // (attribution, uri, mime, opts, signal) · (pkg, tag, uri, mime, opts, signal) · (pkg, uri, mime, opts, signal)
                    if (attributed && p.length == 5) a = new Object[]{attribution, uri, arg, null, null};
                    else if (p.length == 6 && p[0] == String.class && p[1] == String.class) a = new Object[]{shell, null, uri, arg, null, null};
                    else if (p.length == 5 && p[0] == String.class && p[1] == Uri.class) a = new Object[]{shell, uri, arg, null, null};
                    else continue;
                    break;
                case "query":
                    // (attribution, uri, projection, args, signal) · (pkg, tag, uri, …) · (pkg, uri, …)
                    if (attributed && p.length == 5) a = new Object[]{attribution, uri, arg, null, null};
                    else if (p.length == 6 && p[0] == String.class && p[1] == String.class) a = new Object[]{shell, null, uri, arg, null, null};
                    else if (p.length == 5 && p[0] == String.class && p[1] == Uri.class) a = new Object[]{shell, uri, arg, null, null};
                    else continue;
                    break;
                default:
                    // getType: (attribution, uri) from Android 14, (uri) before it
                    if (attributed && p.length == 2) a = new Object[]{attribution, uri};
                    else if (p.length == 1 && p[0] == Uri.class) a = new Object[]{uri};
                    else continue;
            }
            try {
                return m.invoke(provider, a);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof IOException) throw (IOException) c;
                if (c instanceof RuntimeException) throw (RuntimeException) c;
                throw new IOException(String.valueOf(c), c);
            } catch (IllegalAccessException e) {
                throw new IOException(e);
            }
        }
        throw new IOException("no " + what + " on the provider of " + uri.getAuthority());
    }

    /** A provider, held for one call or one read: let go of after, so this process keeps none awake. */
    private static final class Held implements Closeable {
        final Object provider;
        final String authority;
        final IBinder token;

        private Held(Object provider, String authority, IBinder token) {
            this.provider = provider;
            this.authority = authority;
            this.token = token;
        }

        static Held acquire(Uri uri) throws IOException {
            String authority = uri.getAuthority();
            if (authority == null) throw new FileNotFoundException("no provider for " + uri);
            // "10@media": another user's, which the shell of user 0 is not given.
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            IBinder token = new Binder();
            try {
                Object am = Refl.callStatic(Refl.cls("android.app.ActivityManager"), "getService", new Class<?>[]{});
                Object holder = null;
                for (Method m : am.getClass().getMethods()) {
                    if (!m.getName().equals("getContentProviderExternal")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4) holder = m.invoke(am, authority, 0, token, "openandroiddex-apps");
                    else if (p.length == 3) holder = m.invoke(am, authority, 0, token);
                    if (holder != null) break;
                }
                if (holder == null) throw new FileNotFoundException("no provider " + authority);
                return new Held(Refl.field(holder, "provider"), authority, token);
            } catch (InvocationTargetException e) {
                throw new IOException("the provider of " + authority + ": " + e.getCause(), e.getCause());
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IOException("the provider of " + authority + ": " + e, e);
            }
        }

        @Override
        public void close() {
            try {
                Object am = Refl.callStatic(Refl.cls("android.app.ActivityManager"), "getService", new Class<?>[]{});
                for (Method m : am.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("removeContentProviderExternalAsUser") && p.length == 3) {
                        m.invoke(am, authority, token, 0);
                        return;
                    }
                }
                for (Method m : am.getClass().getMethods()) {
                    if (m.getName().equals("removeContentProviderExternal") && m.getParameterTypes().length == 2) {
                        m.invoke(am, authority, token);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // held until this process ends, then: no worse than Providers' own
            }
        }
    }
}
