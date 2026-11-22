package com.termux.x11;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in Linux's programs (XDesk's own Ubuntu, BuiltinLinux), read straight from its files: the system is the
 * app's, so no command inside it is needed to list them. The same catalog format as xdesk's (CLI.md §3), with keys
 * "builtin/DESKTOP-ID", so LinuxSource treats them like any environment's. Used by the app (the list) and by the
 * built-in Linux's X server (XWindows: opening one, through the launch command the app gave it).
 */
@SuppressWarnings("WeakerAccess")
public final class BuiltinApps {
    private static final String TAG = "XDeskBuiltinApps";
    public static final String SCOPE = "builtin";
    private static final int MAX_ICON_BYTES = 256 * 1024;
    private static final String[] SIZES = {"64x64", "48x48", "96x96", "128x128", "256x256", "32x32", "512x512"};
    private static final String[] THEMES_AFTER = {"hicolor", "Adwaita", "AdwaitaLegacy", "elementary-xfce", "elementary-xfce-dark", "gnome"};
    private static final String[] PATH = {"/usr/local/bin", "/usr/bin", "/bin", "/usr/local/sbin", "/usr/sbin", "/sbin", "/usr/games"};

    private final File root, home;

    public BuiltinApps(File root, File home) {
        this.root = root;
        this.home = home;
    }

    /** One desktop file's [Desktop Entry] keys (the first of each). */
    static final class Entry {
        final String id;
        final File file;
        final Map<String, String> keys = new HashMap<>();

        Entry(String id, File file) {
            this.id = id;
            this.file = file;
        }

        String get(String k) {
            String v = keys.get(k);
            return v != null ? v : "";
        }
    }

    /** Application directories, most important first: an id found earlier hides the same id later. */
    private File[] dirs() {
        return new File[] {
            new File(home, ".local/share/applications"),
            new File(root, "usr/local/share/applications"),
            new File(root, "usr/share/applications"),
        };
    }

    /** Every desktop file by id (a file in a subdirectory: "kde4/foo.desktop" is "kde4-foo"). */
    LinkedHashMap<String, File> files() {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();
        for (File d : dirs()) {
            File[] list = d.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.isDirectory()) {
                    File[] sub = f.listFiles();
                    if (sub == null) continue;
                    for (File g : sub) add(out, f.getName() + "-" + g.getName(), g);
                } else {
                    add(out, f.getName(), f);
                }
            }
        }
        return out;
    }

    private static void add(Map<String, File> out, String name, File f) {
        if (!name.endsWith(".desktop") || !f.isFile()) return;
        String id = name.substring(0, name.length() - ".desktop".length());
        if (!out.containsKey(id)) out.put(id, f);
    }

    static Entry read(String id, File f) {
        Entry e = new Entry(id, f);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            boolean group = false;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (line.startsWith("[")) {
                    group = line.equals("[Desktop Entry]");
                    continue;
                }
                if (!group || line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim().replace('\t', ' ');
                if (!e.keys.containsKey(k)) e.keys.put(k, v);
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        return e;
    }

    /** The system's language (sudo update-locale LANG=...), else English: "zh_CN". */
    String locale() {
        try {
            for (String line : Files.readAllLines(new File(root, "etc/default/locale").toPath(), StandardCharsets.UTF_8)) {
                Matcher m = Pattern.compile("^\\s*(?:LANGUAGE|LANG)=\"?([A-Za-z_]+)").matcher(line);
                if (m.find()) return m.group(1);
            }
        } catch (IOException | RuntimeException ignored) { }
        return "en_US";
    }

    private String iconTheme() {
        try {
            String x = new String(Files.readAllBytes(new File(home, ".config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml").toPath()),
                StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("name=\"IconThemeName\"[^>]*value=\"([^\"]*)\"").matcher(x);
            if (m.find()) return m.group(1);
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    /** The name in the system's language, as xdesk's catalog does. */
    static String name(Entry e, String loc) {
        String lang = loc.contains("_") ? loc.substring(0, loc.indexOf('_')) : loc;
        String n = e.get("Name[" + loc + "]");
        if (n.isEmpty()) n = e.get("Name[" + lang + "]");
        if (n.isEmpty()) n = e.get("Name");
        return n;
    }

    /** Whether the desktop's menu shows it: an application, not hidden, for XFCE, its TryExec there. */
    boolean shown(Entry e) {
        if (!"Application".equals(e.get("Type")) || e.get("Exec").isEmpty() || e.get("Name").isEmpty()) return false;
        if ("true".equals(e.get("NoDisplay")) || "true".equals(e.get("Hidden"))) return false;
        String only = e.get("OnlyShowIn");
        if (!only.isEmpty() && !(";" + only + ";").contains(";XFCE;")) return false;
        if ((";" + e.get("NotShowIn") + ";").contains(";XFCE;")) return false;
        String tryExec = e.get("TryExec");
        return tryExec.isEmpty() || installed(tryExec);
    }

    private boolean installed(String program) {
        if (program.startsWith("/")) return new File(root, program.substring(1)).exists();
        for (String d : PATH) if (new File(root, d.substring(1) + "/" + program).exists()) return true;
        return false;
    }

    /** A PNG for an Icon= value (a path, or a name in the themes, then pixmaps), or null. SVG is left to a letter icon. */
    File icon(String icon, String theme) {
        if (icon.isEmpty()) return null;
        if (icon.startsWith("/")) {
            File f = new File(root, icon.substring(1));
            return icon.endsWith(".png") && f.isFile() ? f : null;
        }
        if (icon.endsWith(".png") || icon.endsWith(".svg") || icon.endsWith(".xpm")) icon = icon.substring(0, icon.length() - 4);
        List<String> themes = new ArrayList<>();
        if (theme != null && !theme.isEmpty()) themes.add(theme);
        for (String t : THEMES_AFTER) if (!themes.contains(t)) themes.add(t);
        File[] bases = {new File(home, ".local/share/icons"), new File(root, "usr/local/share/icons"), new File(root, "usr/share/icons")};
        for (String t : themes) {
            for (File base : bases) {
                File dir = new File(base, t);
                if (!dir.isDirectory()) continue;
                for (String s : SIZES) {
                    // SIZE/apps (hicolor, Adwaita), then apps/SIZE (elementary), then any context
                    File f = new File(dir, s + "/apps/" + icon + ".png");
                    if (f.isFile()) return f;
                    f = new File(dir, "apps/" + s.substring(0, s.indexOf('x')) + "/" + icon + ".png");
                    if (f.isFile()) return f;
                }
            }
        }
        File f = new File(root, "usr/share/pixmaps/" + icon + ".png");
        return f.isFile() ? f : null;
    }

    /** The catalog, as xdesk apps writes it: a header, then KEY, name, terminal (0/1), StartupWMClass, icon (base64 PNG). */
    public byte[] catalog() {
        String loc = locale(), theme = iconTheme();
        StringBuilder out = new StringBuilder("#xdesk-apps 1\n");
        int n = 0;
        for (Map.Entry<String, File> f : files().entrySet()) {
            Entry e = read(f.getKey(), f.getValue());
            if (e == null || !shown(e)) continue;
            String wm = e.get("StartupWMClass");
            if (wm.contains("@@")) wm = "";
            String png = "";
            File ic = icon(e.get("Icon"), theme);
            if (ic != null && ic.length() <= MAX_ICON_BYTES) {
                try {
                    png = Base64.encodeToString(Files.readAllBytes(ic.toPath()), Base64.NO_WRAP);
                } catch (IOException | RuntimeException ignored) { }
            }
            out.append(SCOPE).append('/').append(clean(e.id)).append('\t').append(clean(name(e, loc))).append('\t')
                .append("true".equals(e.get("Terminal")) ? '1' : '0').append('\t').append(clean(wm)).append('\t')
                .append(png).append('\n');
            n++;
        }
        Log.i(TAG, n + " apps");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    /** The desktop file of a catalog key ("builtin/ID"), or null. */
    Entry entry(String key) {
        if (key == null || !key.startsWith(SCOPE + "/")) return null;
        String id = key.substring(SCOPE.length() + 1);
        File f = files().get(id);
        if (f == null) return null;
        Entry e = read(id, f);
        return e != null && shown(e) ? e : null;
    }

    /** The Exec= value as a shell command: string escapes undone, field codes (%f %U ...) dropped, %% kept as %. */
    static String command(String exec) {
        String e = exec.replace("\\\\", "\u0001").replace("\\s", " ").replace("\\t", "\t").replace("\\n", " ")
            .replace("\\r", "").replace("\u0001", "\\");
        e = e.replace("%%", "\u0002").replaceAll("%[fFuUdDnNvmick]", "").replace("\u0002", "%");
        return e.trim();
    }

    /**
     * Opens a program in the built-in Linux, in the X server process (XWindows): the app gave the command that runs
     * a script in the container (XDESK_BUILTIN_LAUNCH, one word per line), whose last word is xdesk-launch. "pid N" or
     * "!reason", like xdesk launch.
     */
    public String launch(String key, String startupId, String launchCommand) {
        Entry e = entry(key);
        if (e == null) return "!No app " + key;
        String cmd = command(e.get("Exec"));
        if (cmd.isEmpty()) return "!App " + key + " has no command";
        if (launchCommand == null || launchCommand.isEmpty()) return "!The built-in Linux cannot run programs from here";
        List<String> argv = new ArrayList<>();
        for (String w : launchCommand.split("\n")) if (!w.isEmpty()) argv.add(w);
        argv.add(startupId != null ? startupId : "");
        argv.add("true".equals(e.get("Terminal")) ? "1" : "0");
        argv.add(e.get("Path"));
        argv.add(cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.environment().remove("CLASSPATH");
            pb.environment().remove("LD_PRELOAD");
            Process p = pb.start();
            String last = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("pid ")) return line.substring(4).trim();
                    if (!line.trim().isEmpty()) last = line.trim();
                }
            }
            return "!" + (last.isEmpty() ? "could not start " + key : last);
        } catch (IOException | RuntimeException ex) {
            return "!" + ex.getMessage();
        }
    }

    /** For the app: something in the directories changed since this (their times and the newest file). */
    public long signature() {
        long s = locale().hashCode();
        for (File d : dirs()) {
            s = s * 31 + d.lastModified();
            File[] list = d.listFiles();
            if (list != null) for (File f : list) s = s * 31 + f.lastModified();
        }
        return s;
    }
}
