package com.ccrstech.openandroiddex.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The guest's apps, as the desktop's app list shows them: read from the same
 * .desktop files XFCE's Applications menu is built from, so what is in the
 * menu is in the drawer, under the same name and icon, and opens as a window
 * of its own (doc/linux-windows.md).
 *
 * <p>Read from the outside, through the container's files: nothing runs in
 * the guest to list them. Two things follow. A symbolic link in the rootfs
 * names a GUEST path — an icon in hicolor is often a link into the app's own
 * directory — so links are followed by hand, inside the rootfs
 * ({@link #resolve}). And only PNG icons are any use, since Android draws no
 * SVG; an app with nothing else gets the desktop's own Linux icon.
 *
 * <p>Left out: anything the menu hides (NoDisplay, Hidden, OnlyShowIn other
 * desktops, a TryExec that is not there), and XFCE's settings dialogs, which
 * configure the XFCE desktop on the other display, not anything a window
 * here could change.
 *
 * <p>Pure Java; the icon is decoded by the caller.
 */
final class LinuxApps {

    private LinuxApps() {
    }

    /** One app, as its .desktop file describes it. */
    static final class App {
        /** The file's name without ".desktop" — firefox, org.xfce.mousepad. */
        final String id;
        final String name;
        final String comment;
        /** The Exec line, field codes and all. */
        final String exec;
        final boolean terminal;
        /** A PNG on this side of the fence, or null. */
        final File icon;
        /** What its windows are called in WM_CLASS, as the file says or guessed from Exec. */
        final String wmClass;

        App(String id, String name, String comment, String exec, boolean terminal, File icon, String wmClass) {
            this.id = id;
            this.name = name;
            this.comment = comment;
            this.exec = exec;
            this.terminal = terminal;
            this.icon = icon;
            this.wmClass = wmClass;
        }

        /** The shell command that starts it on the window display. */
        String command() {
            return commandFor(exec, terminal);
        }

        @Override
        public String toString() {
            return id + " \"" + name + "\" " + command() + (icon != null ? " icon=" + icon : "");
        }
    }

    /** Where menus come from, in the order a later one overrides an earlier one (XDG). */
    private static final String[] DIRS = {
            "usr/share/applications",
            "usr/local/share/applications",
            "root/.local/share/applications",
    };

    /** XFCE's own settings and session plumbing: they act on the desktop on :1, not here. */
    private static final String[] SKIP_CATEGORIES = {
            "Settings", "DesktopSettings", "X-XFCE-SettingsDialog", "X-XFCE-SystemSettings",
            "X-XFCE-HardwareSettings", "X-XFCE-PersonalSettings", "Screensaver",
    };

    private static final String[] SKIP_BINARIES = {
            "xfce4-session-logout", "xflock4", "xfce4-panel", "xfdesktop", "xfce4-session",
            "xfce4-settings-manager", "xfce4-power-manager-settings",
    };

    /**
     * Every app worth listing in {@code rootfs}, sorted by name. {@code theme}
     * is the guest's icon theme (xsettings' Net/IconThemeName), searched
     * before hicolor.
     */
    static List<App> scan(File rootfs, Locale locale, String theme) {
        Map<String, File> files = new LinkedHashMap<>();
        for (String dir : DIRS) {
            File d = resolve(rootfs, "/" + dir);
            File[] list = d != null ? d.listFiles() : null;
            if (list == null) continue;
            for (File f : list) {
                String n = f.getName();
                if (n.endsWith(".desktop")) files.put(n.substring(0, n.length() - 8), f);
            }
        }
        List<App> out = new ArrayList<>();
        for (Map.Entry<String, File> e : files.entrySet()) {
            App app = parse(rootfs, e.getKey(), e.getValue(), locale, theme);
            if (app != null) out.add(app);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** One .desktop file, or null when it is not an app the menu would show. */
    static App parse(File rootfs, String id, File file, Locale locale, String theme) {
        Map<String, String> kv = readEntry(file);
        if (kv == null) return null;
        if (!"Application".equals(kv.get("Type"))) return null;
        if ("true".equalsIgnoreCase(kv.get("NoDisplay")) || "true".equalsIgnoreCase(kv.get("Hidden"))) return null;
        String only = kv.get("OnlyShowIn");
        if (only != null && !listHas(only, "XFCE")) return null;
        String not = kv.get("NotShowIn");
        if (not != null && listHas(not, "XFCE")) return null;
        String exec = kv.get("Exec");
        if (exec == null || exec.trim().isEmpty()) return null;
        String cats = kv.getOrDefault("Categories", "");
        for (String c : SKIP_CATEGORIES) {
            if (listHas(cats, c)) return null;
        }
        String bin = binaryOf(exec);
        for (String b : SKIP_BINARIES) {
            if (b.equals(bin)) return null;
        }
        String tryExec = kv.get("TryExec");
        if (tryExec != null && !tryExec.isEmpty() && findBinary(rootfs, tryExec) == null) return null;
        String name = localized(kv, "Name", locale);
        if (name == null || name.isEmpty()) return null;
        String comment = localized(kv, "Comment", locale);
        File icon = findIcon(rootfs, kv.get("Icon"), theme);
        String wmClass = kv.get("StartupWMClass");
        if (wmClass == null || wmClass.isEmpty()) wmClass = bin;
        return new App(id, name, comment == null ? "" : comment, exec.trim(),
                "true".equalsIgnoreCase(kv.get("Terminal")), icon, wmClass);
    }

    /** The [Desktop Entry] group's keys, localized ones included; null if unreadable. */
    static Map<String, String> readEntry(File file) {
        Map<String, String> kv = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            boolean inEntry = false;
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null && lines++ < 2000) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    if (inEntry) break; // the actions' groups that follow are not the app
                    inEntry = line.equals("[Desktop Entry]");
                    continue;
                }
                if (!inEntry) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                if (!kv.containsKey(k)) kv.put(k, unescape(line.substring(eq + 1).trim()));
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return kv;
    }

    /** \s \n \t \r \\ in a string value (the spec's escapes); everything else as it is. */
    private static String unescape(String v) {
        if (v.indexOf('\\') < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                switch (n) {
                    case 's': sb.append(' '); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append('\\').append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Name[de_DE], then Name[de], then Name. */
    private static String localized(Map<String, String> kv, String key, Locale locale) {
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        String v = null;
        if (!country.isEmpty()) v = kv.get(key + "[" + lang + "_" + country + "]");
        if (v == null) v = kv.get(key + "[" + lang + "]");
        if (v == null) v = kv.get(key);
        return v;
    }

    private static boolean listHas(String list, String item) {
        for (String s : list.split(";")) {
            if (s.trim().equals(item)) return true;
        }
        return false;
    }

    /** The program an Exec line runs: its first word, unquoted, without the path, env stripped. */
    static String binaryOf(String exec) {
        List<String> words = words(exec);
        int i = 0;
        if (!words.isEmpty() && words.get(0).equals("env")) {
            i = 1;
            while (i < words.size() && words.get(i).contains("=")) i++;
        }
        if (i >= words.size()) return "";
        String w = words.get(i);
        return w.substring(w.lastIndexOf('/') + 1);
    }

    /** An Exec line split as the spec splits it: on blanks, with "double quotes" and backslash escapes. */
    private static List<String> words(String exec) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < exec.length(); i++) {
            char c = exec.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < exec.length()) {
                    cur.append(exec.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ' ' || c == '\t') {
                if (cur.length() > 0 || any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }

    /**
     * The Exec line as a shell command, with nothing to open: every field
     * code (%f %U %i …) dropped, %% kept as %. The spec's quoting is a subset
     * of the shell's, so the rest goes to {@code sh -c} as it is. A terminal
     * app gets a terminal of the guest's around it.
     */
    static String commandFor(String exec, boolean terminal) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exec.length(); i++) {
            char c = exec.charAt(i);
            if (c == '%' && i + 1 < exec.length()) {
                char n = exec.charAt(++i);
                if (n == '%') sb.append('%');
                // every other field code expands to nothing here
                continue;
            }
            sb.append(c);
        }
        String cmd = sb.toString().replaceAll("\\s+", " ").trim();
        if (terminal) cmd = "xfce4-terminal -x " + cmd;
        return cmd;
    }

    // ── files inside the rootfs ──

    /**
     * A guest path as a file on this side: every symbolic link on the way
     * followed inside {@code rootfs}, since an absolute link in there names a
     * guest path, not one of Android's. Null if it leads nowhere or loops.
     */
    static File resolve(File rootfs, String guestPath) {
        Path root = rootfs.toPath();
        String[] parts = guestPath.split("/");
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        for (String p : parts) if (!p.isEmpty()) todo.addLast(p);
        List<String> done = new ArrayList<>();
        int hops = 0;
        while (!todo.isEmpty()) {
            String p = todo.pollFirst();
            if (p.equals(".")) continue;
            if (p.equals("..")) {
                if (!done.isEmpty()) done.remove(done.size() - 1);
                continue;
            }
            done.add(p);
            Path here = root.resolve(String.join("/", done));
            if (Files.isSymbolicLink(here)) {
                if (++hops > 40) return null;
                String target;
                try {
                    target = Files.readSymbolicLink(here).toString();
                } catch (IOException e) {
                    return null;
                }
                done.remove(done.size() - 1);
                // proot's link2symlink writes host paths into the rootfs; a
                // guest one is relative to the rootfs.
                String rootStr = root.toString();
                if (target.startsWith(rootStr + "/")) target = target.substring(rootStr.length());
                if (target.startsWith("/")) done.clear();
                String[] t = target.split("/");
                for (int i = t.length - 1; i >= 0; i--) if (!t[i].isEmpty()) todo.addFirst(t[i]);
            }
        }
        File f = root.resolve(String.join("/", done)).toFile();
        return f.exists() ? f : null;
    }

    /** A program by name on the guest's PATH, or by absolute path; null if it is not there. */
    static File findBinary(File rootfs, String name) {
        if (name.startsWith("/")) return resolve(rootfs, name);
        for (String dir : new String[]{"/usr/local/bin", "/usr/bin", "/bin", "/usr/local/sbin", "/usr/sbin", "/sbin"}) {
            File f = resolve(rootfs, dir + "/" + name);
            if (f != null) return f;
        }
        return null;
    }

    private static final String[] SIZES = {
            "128x128", "96x96", "256x256", "64x64", "192x192", "512x512", "48x48", "32x32",
    };

    /**
     * The best PNG for an Icon= value: a path as it is, else the theme's
     * apps at the sizes nearest a taskbar's, then hicolor's, then pixmaps.
     */
    static File findIcon(File rootfs, String icon, String theme) {
        if (icon == null || icon.isEmpty()) return null;
        if (icon.startsWith("/")) {
            File f = resolve(rootfs, icon);
            return f != null && icon.endsWith(".png") ? f : null;
        }
        String name = icon.endsWith(".png") || icon.endsWith(".svg") || icon.endsWith(".xpm")
                ? icon.substring(0, icon.length() - 4) : icon;
        List<String> themes = new ArrayList<>();
        if (theme != null && !theme.isEmpty()) themes.add(theme);
        themes.add("hicolor");
        for (String t : themes) {
            for (String size : SIZES) {
                File f = resolve(rootfs, "/usr/share/icons/" + t + "/" + size + "/apps/" + name + ".png");
                if (f != null) return f;
            }
        }
        return resolve(rootfs, "/usr/share/pixmaps/" + name + ".png");
    }
}
