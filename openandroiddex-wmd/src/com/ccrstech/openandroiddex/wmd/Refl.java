package com.ccrstech.openandroiddex.wmd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Reflection helpers.
 *
 * Everything this daemon does lives behind {@code @hide}, so nothing here can be
 * called directly — we compile against the public {@code android.jar} and bind at
 * runtime. That is safe specifically because a process launched as
 * {@code CLASSPATH=… app_process /system/bin <Main>} runs {@code RuntimeInit}
 * rather than {@code ZygoteInit}, has no application context, and is therefore
 * outside the hidden-API blocklist.
 *
 * Lookups are cached because the drag path calls a handful of them per frame and
 * {@code getMethod} walks the class hierarchy on every call.
 */
final class Refl {

    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    private Refl() {
    }

    static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new WmError("no class " + name);
        }
    }

    /** Best-effort class lookup — returns null instead of throwing, for probes. */
    static Class<?> clsOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... args) {
        StringBuilder key = new StringBuilder(owner.getName()).append('#').append(name);
        for (Class<?> a : args) key.append(';').append(a.getName());
        Method cached = METHODS.get(key.toString());
        if (cached != null) return cached;
        Method m = null;
        for (Class<?> c = owner; c != null && m == null; c = c.getSuperclass()) {
            try {
                m = c.getDeclaredMethod(name, args);
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (m == null) {
            // interfaces (binder proxies expose the AIDL interface, not the impl)
            for (Class<?> i : owner.getInterfaces()) {
                try {
                    m = i.getDeclaredMethod(name, args);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        if (m == null) throw new WmError("no method " + owner.getName() + "#" + name);
        m.setAccessible(true);
        METHODS.put(key.toString(), m);
        return m;
    }

    /** True if the method exists. Hidden-API availability varies by build and OEM. */
    static boolean hasMethod(Class<?> owner, String name, Class<?>... args) {
        try {
            method(owner, name, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Object call(Object target, String name, Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : unbox(args[i].getClass());
        }
        return invoke(method(target.getClass(), name, types), target, args);
    }

    /** Explicit-signature call, for when an argument's runtime type is a subclass. */
    static Object callSig(Object target, String name, Class<?>[] types, Object... args) {
        return invoke(method(target.getClass(), name, types), target, args);
    }

    static Object callStatic(Class<?> owner, String name, Class<?>[] types, Object... args) {
        return invoke(method(owner, name, types), null, args);
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            throw new WmError(m.getName() + ": " + cause);
        }
    }

    static Object field(Object target, String name) {
        String key = target.getClass().getName() + "." + name;
        Field cached = FIELDS.get(key);
        try {
            if (cached == null) {
                Field f = null;
                for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                    try {
                        f = c.getDeclaredField(name);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (f == null) throw new WmError("no field " + key);
                f.setAccessible(true);
                FIELDS.put(key, f);
                cached = f;
            }
            return cached.get(target);
        } catch (WmError e) {
            throw e;
        } catch (Throwable t) {
            throw new WmError("field " + key + ": " + t);
        }
    }

    private static Class<?> unbox(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Long.class) return long.class;
        if (c == Float.class) return float.class;
        return c;
    }

    /** Failure carrying a message short enough to put on a protocol line. */
    static final class WmError extends RuntimeException {
        WmError(String message) {
            super(message);
        }
    }
}
