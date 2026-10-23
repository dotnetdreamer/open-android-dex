package com.ccrstech.openandroiddex.wmd;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * A content provider's {@code call()}, from this process, as the shell —
 * what {@code content call} does, without the JVM that tool boots for every
 * call (1.7 s on a Galaxy A13, measured on this phone's desktop exit).
 *
 * <p>The provider is reached as an "external" one, as that tool reaches it:
 * this process is no app the activity manager knows, so it cannot acquire a
 * provider the way an app does ("Unable to find app for caller"). The
 * {@code call} binding changed shape twice — the caller's package, then its
 * feature, then an AttributionSource — so it is found by its parameters.
 */
final class Providers {

    private Providers() {
    }

    /** One held provider: acquiring costs a binder round trip, and a cold start of its process. */
    private static final class Held {
        final Object provider;
        final IBinder token;

        Held(Object provider, IBinder token) {
            this.provider = provider;
            this.token = token;
        }
    }

    private static final java.util.Map<String, Held> HELD = new java.util.HashMap<>();

    /** {@code method} with {@code arg} on {@code authority}; its {@code json} extra, parsed. */
    static JSONObject call(String authority, String method, JSONObject arg) throws Exception {
        Held h = held(authority);
        Bundle result;
        try {
            result = invoke(h.provider, authority, method, arg == null ? null : arg.toString());
        } catch (Exception e) {
            // The provider's process died (an update, a force-stop): once more, freshly.
            synchronized (HELD) {
                HELD.remove(authority);
            }
            result = invoke(held(authority).provider, authority, method, arg == null ? null : arg.toString());
        }
        String json = result == null ? null : result.getString("json");
        return json == null ? new JSONObject() : new JSONObject(json);
    }

    private static Held held(String authority) throws Exception {
        synchronized (HELD) {
            Held h = HELD.get(authority);
            if (h != null) return h;
            Object am = Refl.callStatic(Refl.cls("android.app.ActivityManager"), "getService", new Class<?>[]{});
            IBinder token = new Binder();
            Object holder = null;
            for (Method m : am.getClass().getMethods()) {
                if (!m.getName().equals("getContentProviderExternal")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4) holder = m.invoke(am, authority, 0, token, "openandroiddex-apps");
                else if (p.length == 3) holder = m.invoke(am, authority, 0, token);
                if (holder != null) break;
            }
            if (holder == null) throw new IllegalStateException("no provider " + authority);
            h = new Held(Refl.field(holder, "provider"), token);
            HELD.put(authority, h);
            return h;
        }
    }

    private static Bundle invoke(Object provider, String authority, String method, String arg) throws Exception {
        for (Method m : provider.getClass().getMethods()) {
            if (!m.getName().equals("call")) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] a;
            if (p.length == 5 && p[0].getName().equals("android.content.AttributionSource")) {
                a = new Object[]{PhoneContext.get().getAttributionSource(), authority, method, arg, null};
            } else if (p.length == 6 && p[0] == String.class) {
                a = new Object[]{PhoneContext.SHELL_PACKAGE, null, authority, method, arg, null};
            } else if (p.length == 5 && p[0] == String.class && Build.VERSION.SDK_INT < 30) {
                a = new Object[]{PhoneContext.SHELL_PACKAGE, authority, method, arg, null};
            } else {
                continue;
            }
            try {
                return (Bundle) m.invoke(provider, a);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                throw c instanceof Exception ? (Exception) c : new Exception(c);
            }
        }
        throw new IllegalStateException("no call() on the provider of " + authority);
    }
}
