package com.ccrstech.openandroiddex.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The window manager's pure parts, on a computer's JVM, for
 * test/linux-windows-test.mjs: one line per check, "ok <name>" or
 * "FAIL <name> — <detail>".
 */
public final class UnitCheck {

    private static int failed;

    private static void check(String name, boolean ok, Object detail) {
        if (ok) {
            System.out.println("ok " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " — " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        keys();
        xauthority();
        xfconf();
        xsettings();
        coalesce();
        apps();
        System.out.println("done " + failed);
    }

    // ── X11Keys ──

    private static void keys() {
        // A us layout's shape, as Xvnc reports it: seven columns per keycode.
        int min = 8;
        int[][] map = new int[248][7];
        put(map, min, 38, 'a', 'A');
        put(map, min, 10, '1', '!');
        put(map, min, 26, 'e', 'E', 0, 0, 0x20ac, 0);   // € on Level 3
        put(map, min, 50, X11Keys.XK_SHIFT_L);
        put(map, min, 92, X11Keys.XK_ISO_LEVEL3_SHIFT);
        put(map, min, 65, ' ');
        put(map, min, 30, 'u', 'U');
        put(map, min, 9, 0xff1b);                         // Escape
        X11Keys k = new X11Keys(min, map);
        X11Keys.Place a = k.find('a');
        check("a letter is its key's plain column", a != null && a.keycode == 38 && !a.needsShift(), a);
        X11Keys.Place cap = k.find('A');
        check("its capital is the same key with Shift", cap != null && cap.keycode == 38 && cap.needsShift(), cap);
        X11Keys.Place bang = k.find('!');
        check("a shifted symbol needs Shift", bang != null && bang.keycode == 10 && bang.needsShift(), bang);
        X11Keys.Place euro = k.find(0x20ac);
        check("a Level 3 symbol needs the Level 3 shift", euro != null && euro.keycode == 26 && euro.needsLevel3()
                && !euro.needsShift(), euro);
        check("a keysym the layout lacks is not found", k.find(0xe9) == null, k.find(0xe9));
        check("modifiers by keycode", k.keycodeOf(X11Keys.XK_SHIFT_L) == 50
                && k.keycodeOf(X11Keys.XK_ISO_LEVEL3_SHIFT) == 92, k.keycodeOf(X11Keys.XK_SHIFT_L));

        HashSet<Integer> busy = new HashSet<>();
        int[] loan = k.lend(0xe9, busy);
        check("é is lent a keycode nobody uses, 9 or above", loan != null && loan[0] >= 9 && loan[1] == 0xe9
                && Arrays.stream(map[loan[0] - min]).limit(2).allMatch(v -> v == 0xe9), Arrays.toString(loan));
        int lent = k.lentTo(0xe9);
        check("…and found there afterwards, Shift or not", lent == loan[0] && k.find(0xe9) != null
                && k.find(0xe9).keycode == lent && !k.find(0xe9).needsShift(), lent);
        check("lending it again changes nothing", k.lend(0xe9, busy) == null && k.lentTo(0xe9) == lent, k.lentTo(0xe9));
        // Fill every spare keycode, holding the first one down: it must not be taken back.
        busy.add(lent);
        int spare = 0;
        for (int[] row : map) if (Arrays.stream(row).allMatch(v -> v == 0)) spare++;
        for (int i = 0; i < spare + 3; i++) k.lend(0x01000000 | (0x4e00 + i), busy);
        check("a keycode held down is never lent away", k.lentTo(0xe9) == lent, k.lentTo(0xe9));
        int newest = k.lentTo(0x01000000 | (0x4e00 + spare + 2));
        check("the least recently used loan is the one reused", newest != 0 && newest != lent, newest);

        check("Latin-1 is itself", X11Keys.unicodeKeysym('a') == 'a' && X11Keys.unicodeKeysym(0xe9) == 0xe9, "");
        check("beyond Latin-1 is the Unicode block", X11Keys.unicodeKeysym(0x20ac) == 0x010020ac
                && X11Keys.unicodeKeysym(0x1f642) == 0x0101f642, Integer.toHexString(X11Keys.unicodeKeysym(0x20ac)));
        check("line ends, tab, backspace and escape are their keys", X11Keys.unicodeKeysym('\n') == 0xff0d
                && X11Keys.unicodeKeysym('\t') == 0xff09 && X11Keys.unicodeKeysym(8) == 0xff08
                && X11Keys.unicodeKeysym(0x1b) == 0xff1b, "");
        check("lower case of letters, Latin-1 and Unicode", X11Keys.toLower('Q') == 'q'
                && X11Keys.toLower(0xc9) == 0xe9 && X11Keys.toLower(0x01000000 | 0x0416) == (0x01000000 | 0x0436)
                && X11Keys.toLower('5') == '5', "");
    }

    private static void put(int[][] map, int min, int keycode, int... keysyms) {
        for (int i = 0; i < keysyms.length; i++) map[keycode - min][i] = keysyms[i];
    }

    // ── the cookie ──

    private static void xauthority() {
        byte[] cookie = new byte[16];
        for (int i = 0; i < 16; i++) cookie[i] = (byte) (i * 17 + 3);
        byte[] f = X11.xauthority("2", cookie);
        ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.BIG_ENDIAN);
        int family = b.getShort() & 0xffff;
        int addr = b.getShort();
        int numLen = b.getShort();
        byte[] num = new byte[numLen];
        b.get(num);
        int nameLen = b.getShort();
        byte[] name = new byte[nameLen];
        b.get(name);
        check("Xauthority: any address, display 2, MIT-MAGIC-COOKIE-1, big-endian lengths",
                family == 0xffff && addr == 0 && "2".equals(new String(num, StandardCharsets.US_ASCII))
                        && "MIT-MAGIC-COOKIE-1".equals(new String(name, StandardCharsets.US_ASCII)), family);
        check("the cookie comes back out of it", Arrays.equals(X11.cookieFrom(f), cookie), "");
        check("a truncated file has none", X11.cookieFrom(Arrays.copyOf(f, f.length - 5)) == null, "");
    }

    // ── xfconf → XSETTINGS ──

    static final String SYSTEM = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n"
            + "<channel name=\"xsettings\" version=\"1.0\">\n"
            + "  <property name=\"Net\" type=\"empty\">\n"
            + "    <property name=\"ThemeName\" type=\"string\" value=\"Arc-Dark\"/>\n"
            + "    <property name=\"IconThemeName\" type=\"string\" value=\"Adwaita\"/>\n"
            + "    <property name=\"CursorBlink\" type=\"bool\" value=\"false\"/>\n"
            + "  </property>\n"
            + "  <property name=\"Gtk\" type=\"empty\">\n"
            + "    <property name=\"FontName\" type=\"string\" value=\"Cantarell 11\"/>\n"
            + "    <property name=\"DialogsUseHeader\" type=\"bool\" value=\"false\"/>\n"
            + "  </property>\n"
            + "  <property name=\"Xft\" type=\"empty\">\n"
            + "    <property name=\"Antialias\" type=\"int\" value=\"1\"/>\n"
            + "    <property name=\"HintStyle\" type=\"string\" value=\"hintslight\"/>\n"
            + "    <property name=\"DPI\" type=\"int\" value=\"96\"/>\n"
            + "  </property>\n"
            + "  <property name=\"Gdk\" type=\"empty\">\n"
            + "    <property name=\"WindowScalingFactor\" type=\"int\" value=\"1\"/>\n"
            + "  </property>\n"
            + "</channel>\n";

    static final String USER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<channel name=\"xsettings\" version=\"1.0\">\n"
            + "  <property name=\"Net\" type=\"empty\">\n"
            + "    <property name=\"ThemeName\" type=\"string\" value=\"Greybird &amp; Co\"/>\n"
            + "    <property name=\"Unused\" type=\"double\" value=\"1.5\"/>\n"
            + "  </property>\n"
            + "</channel>\n";

    private static void xfconf() {
        Map<String, Object> s = LinuxWm.parseXfconf(SYSTEM + "\n" + USER);
        check("strings by their XSETTINGS names", "Adwaita".equals(s.get("Net/IconThemeName"))
                && "Cantarell 11".equals(s.get("Gtk/FontName")), s);
        check("bools as 0 and 1, ints as ints", Integer.valueOf(0).equals(s.get("Net/CursorBlink"))
                && Integer.valueOf(1).equals(s.get("Xft/Antialias")), s);
        check("the user's file wins, entities undone", "Greybird & Co".equals(s.get("Net/ThemeName")), s.get("Net/ThemeName"));
        check("the guest's DPI is left out; the display's is used", !s.containsKey("Xft/DPI"), s.keySet());
        check("a type XSETTINGS cannot carry is left out", !s.containsKey("Net/Unused"), s.keySet());
        check("nesting closes properly", s.containsKey("Gdk/WindowScalingFactor") && !s.containsKey("Gtk/Gdk/WindowScalingFactor"),
                s.keySet());
    }

    private static void xsettings() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Net/ThemeName", "Arc-Dark");
        s.put("Xft/DPI", 144 * 1024);
        byte[] blob = LinuxWm.xsettings(s, 7);
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int order = b.get();
        b.position(4);
        int serial = b.getInt();
        int n = b.getInt();
        Map<String, Object> back = new LinkedHashMap<>();
        boolean serials = true;
        for (int i = 0; i < n; i++) {
            int type = b.get();
            b.get();
            int len = b.getShort();
            byte[] name = new byte[len];
            b.get(name);
            b.position(b.position() + X11.pad4(len) - len);
            serials &= b.getInt() == 7;
            if (type == 0) {
                back.put(new String(name, StandardCharsets.US_ASCII), b.getInt());
            } else if (type == 1) {
                int vl = b.getInt();
                byte[] v = new byte[vl];
                b.get(v);
                b.position(b.position() + X11.pad4(vl) - vl);
                back.put(new String(name, StandardCharsets.US_ASCII), new String(v, StandardCharsets.UTF_8));
            }
        }
        check("XSETTINGS: little-endian, the serial, the count", order == 0 && serial == 7 && n == 2, serial + "/" + n);
        check("…and every setting reads back, each carrying the serial", back.equals(s) && serials, back);
        check("…to the last byte", !b.hasRemaining(), b.remaining());
    }

    // ── damage ──

    private static void coalesce() {
        List<int[]> in = new ArrayList<>();
        in.add(new int[]{10, 10, 20, 20});
        in.add(new int[]{25, 12, 20, 20});
        List<int[]> out = LinuxWm.coalesce(in, 100, 100);
        check("rectangles that overlap become one box", out.size() == 1 && Arrays.equals(out.get(0), new int[]{10, 10, 35, 22}),
                Arrays.deepToString(out.toArray()));
        in.clear();
        in.add(new int[]{0, 0, 10, 10});
        in.add(new int[]{900, 600, 10, 10});
        out = LinuxWm.coalesce(in, 1000, 1000);
        check("far apart, they stay two", out.size() == 2, out.size());
        in.clear();
        in.add(new int[]{-5, -5, 20, 20});
        in.add(new int[]{95, 95, 20, 20});
        in.add(new int[]{200, 200, 5, 5});
        out = LinuxWm.coalesce(in, 100, 100);
        check("clipped to the window, and nothing left of one outside it", out.size() == 2
                && Arrays.equals(out.get(0), new int[]{0, 0, 15, 15}) && Arrays.equals(out.get(1), new int[]{95, 95, 5, 5}),
                Arrays.deepToString(out.toArray()));
        in.clear();
        for (int i = 0; i < 12; i++) in.add(new int[]{i * 80, (i % 2) * 500, 4, 4});
        out = LinuxWm.coalesce(in, 1000, 1000);
        check("many small ones become their bounding box", out.size() == 1, out.size());
    }

    // ── .desktop files ──

    private static void apps() throws IOException {
        Path root = Files.createTempDirectory("oadx-rootfs");
        try {
            write(root, "usr/share/applications/firefox.desktop",
                    "[Desktop Entry]\nType=Application\nName=Firefox Web Browser\nName[de]=Firefox-Webbrowser\n"
                            + "Exec=/usr/local/bin/dex-firefox %u\nIcon=firefox\nCategories=Network;WebBrowser;\n"
                            + "StartupWMClass=firefox\n\n[Desktop Action new-window]\nName=New Window\nExec=firefox --new-window\n");
            write(root, "usr/lib/firefox/browser/chrome/icons/default/default128.png", "png");
            link(root, "usr/share/icons/hicolor/128x128/apps/firefox.png",
                    "/usr/lib/firefox/browser/chrome/icons/default/default128.png");
            write(root, "usr/share/applications/xfce-settings-manager.desktop",
                    "[Desktop Entry]\nType=Application\nName=Settings Manager\nExec=xfce4-settings-manager\n"
                            + "Categories=XFCE;GTK;Settings;DesktopSettings;X-XFCE-SettingsDialog;\n");
            write(root, "usr/share/applications/hidden.desktop",
                    "[Desktop Entry]\nType=Application\nName=Hidden\nExec=hidden\nNoDisplay=true\n");
            write(root, "usr/share/applications/gnome-only.desktop",
                    "[Desktop Entry]\nType=Application\nName=GNOME Thing\nExec=thing\nOnlyShowIn=GNOME;\n");
            write(root, "usr/share/applications/htop.desktop",
                    "[Desktop Entry]\nType=Application\nName=Htop\nExec=htop\nTerminal=true\nIcon=htop\n");
            write(root, "usr/share/pixmaps/htop.png", "png");
            write(root, "usr/share/applications/gone.desktop",
                    "[Desktop Entry]\nType=Application\nName=Gone\nExec=gone\nTryExec=not-installed\n");
            write(root, "usr/share/applications/logout.desktop",
                    "[Desktop Entry]\nType=Application\nName=Log Out\nExec=xfce4-session-logout\n");
            write(root, "usr/local/share/applications/code.desktop",
                    "[Desktop Entry]\nType=Application\nName=Visual Studio Code\n"
                            + "Icon=/opt/vscode/resources/app/resources/linux/code.png\n"
                            + "Exec=/usr/local/bin/dex-code %F\nStartupWMClass=Code\n");
            write(root, "opt/vscode/resources/app/resources/linux/code.png", "png");
            write(root, "usr/share/applications/spaced.desktop",
                    "[Desktop Entry]\nType=Application\nName=Spaced\nExec=\"/opt/my app/run\" --flag %F 100%%\n");
            write(root, "usr/share/applications/escaped.desktop",
                    "[Desktop Entry]\nType=Application\nName=Two\\sWords\nExec=env GDK_SCALE=2 /usr/bin/two\n");
            write(root, "root/.local/share/applications/htop.desktop",
                    "[Desktop Entry]\nType=Application\nName=My Htop\nExec=htop --tree\nTerminal=true\n");
            // A rootfs whose links proot wrote with this side's absolute paths (link2symlink).
            write(root, "usr/share/icons/Adwaita/96x96/apps/.l2s.spaced.png0001", "png");
            link(root, "usr/share/icons/Adwaita/96x96/apps/spaced.png",
                    root.resolve("usr/share/icons/Adwaita/96x96/apps/.l2s.spaced.png0001").toString());
            write(root, "usr/share/applications/spaced2.desktop",
                    "[Desktop Entry]\nType=Application\nName=Spaced Two\nExec=spaced\nIcon=spaced\n");

            List<LinuxApps.App> list = LinuxApps.scan(root.toFile(), Locale.GERMANY, "Adwaita");
            Map<String, LinuxApps.App> by = new LinkedHashMap<>();
            for (LinuxApps.App a : list) by.put(a.id, a);
            check("the apps the menu shows, and none it hides", by.keySet().equals(new HashSet<>(Arrays.asList(
                    "firefox", "htop", "code", "spaced", "escaped", "spaced2"))), by.keySet());
            LinuxApps.App ff = by.get("firefox");
            check("named in the language of the phone", ff != null && "Firefox-Webbrowser".equals(ff.name), ff);
            check("the actions after the entry are not the app", ff != null && ff.exec.contains("dex-firefox"), ff);
            check("field codes dropped from the command", ff != null && "/usr/local/bin/dex-firefox".equals(ff.command()),
                    ff == null ? null : ff.command());
            File want = root.resolve("usr/lib/firefox/browser/chrome/icons/default/default128.png").toFile();
            check("an icon linked to a GUEST path is followed inside the rootfs", ff != null && want.equals(ff.icon),
                    ff == null ? null : ff.icon);
            check("its window class", ff != null && "firefox".equals(ff.wmClass), ff == null ? null : ff.wmClass);
            LinuxApps.App htop = by.get("htop");
            check("~/.local overrides the system's", htop != null && "My Htop".equals(htop.name), htop);
            check("a terminal app gets a terminal", htop != null && "xfce4-terminal -x htop --tree".equals(htop.command()),
                    htop == null ? null : htop.command());
            LinuxApps.App code = by.get("code");
            check("an absolute icon path", code != null && code.icon != null && code.icon.getPath().endsWith("code.png"), code);
            check("StartupWMClass, as VS Code's file says", code != null && "Code".equals(code.wmClass), code);
            LinuxApps.App spaced = by.get("spaced");
            check("quoting kept for the shell, %% made %", spaced != null
                    && "\"/opt/my app/run\" --flag 100%".equals(spaced.command()), spaced == null ? null : spaced.command());
            check("the program of a quoted Exec", "run".equals(LinuxApps.binaryOf("\"/opt/my app/run\" --flag")), "");
            check("…and past env", "two".equals(LinuxApps.binaryOf("env GDK_SCALE=2 /usr/bin/two")), "");
            LinuxApps.App esc = by.get("escaped");
            check("escapes in a value", esc != null && "Two Words".equals(esc.name), esc);
            check("guessed window class from Exec", esc != null && "two".equals(esc.wmClass), esc);
            LinuxApps.App s2 = by.get("spaced2");
            check("a link proot wrote with this side's path, in the guest's icon theme",
                    s2 != null && s2.icon != null && s2.icon.getName().startsWith(".l2s."), s2);
            check("sorted by name", list.get(0).name.compareToIgnoreCase(list.get(list.size() - 1).name) < 0,
                    list.get(0).name);
            check("a path that loops is nowhere", loop(root), "");
        } finally {
            deleteTree(root);
        }
    }

    private static boolean loop(Path root) throws IOException {
        link(root, "loop/a", "/loop/b");
        link(root, "loop/b", "/loop/a");
        return LinuxApps.resolve(root.toFile(), "/loop/a/x") == null;
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void link(Path root, String rel, String target) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.createSymbolicLink(p, Path.of(target));
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
