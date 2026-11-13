package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.termux.x11.app.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Linux programs and X windows as contents (DISPLAY.md §1.2, §1.5, phase 1): "l:a:SCOPE/ID" is a program from a
 * .desktop file (xdesk apps), "l:w:ID" an X window. Opening one puts the X desktop on the screen and makes that one
 * window the full screen window there, through the window manager (xfwm4): no other X server, no extra composition.
 *
 * Everything that needs Termux runs in the X server process, which is Termux (ICmdEntryInterface, XWindows): the
 * catalog, launching, the process tree. The window list is pushed from inside the X server when it changes; nothing
 * is polled. UI thread, except where noted.
 *
 * Debug without the chooser (shell only, e.g. rsh or adb):
 *   am broadcast -p io.github.n2ray.xdesk.x11 -a io.github.n2ray.xdesk.LINUX_DEBUG --es cmd dump
 *   ... --es cmd open --es key l:a:termux/org.xfce.mousepad   (also: close, hidden, refresh)
 * and read logcat -s XDeskLinux.
 */
final class LinuxSource implements ContentSource {
    private static final String TAG = "XDeskLinux";
    static final String PREFIX = "l:", APP = "l:a:", WINDOW = "l:w:";
    private static final String DEBUG_ACTION = "io.github.n2ray.xdesk.LINUX_DEBUG";
    private static final long WINDOW_TIMEOUT_MS = 15000, CATALOG_MAX_AGE_MS = 60000;
    private static final int FLAG_FULLSCREEN = 1, FLAG_ACTIVE = 2;
    private static final String CATALOG_FILE = "linux-apps.tsv";

    private static LinuxSource instance;

    /** Registers the Linux source with Contents once, and the debug receiver. Any thread. */
    static synchronized LinuxSource install(Context context) {
        if (instance == null) {
            instance = new LinuxSource(context.getApplicationContext());
            Contents.register(instance);
        }
        return instance;
    }

    /** The installed source, or null. */
    static synchronized LinuxSource get() {
        return instance;
    }

    private static final class App {
        String key, name, wmClass;
        boolean terminal;
        Bitmap icon;
        /** Lowercase names a window of it may carry in WM_CLASS: the desktop id and its last part. */
        String stem, shortStem;
    }

    private static final class Win {
        int id, pid, clientPid, flags;
        String instance, cls, startupId, title;
        long lastActive;
    }

    private static final class Pending {
        String key;
        App app;
        String startupId;
        int pid = -1;
        Set<Integer> before;
        Result result;
        Runnable timeout;
        /** Windows already asked about (isDescendant), so each is asked once. */
        final Set<Integer> asked = new HashSet<>();
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> new Thread(r, "xdesk-linux"));

    private List<App> catalog = Collections.emptyList();
    private long catalogAt;
    private boolean catalogLoading;
    private final LinkedHashMap<Integer, Win> windows = new LinkedHashMap<>();
    private final Map<Integer, Bitmap> windowIcons = new HashMap<>();
    private long activeCounter;
    private boolean haveSnapshot;
    private ICmdEntryInterface server;
    private Runnable changed;
    private Pending pending;
    /** The window this source made full screen (0: none), restored when another content takes the screen. */
    private int fullscreened;

    private LinuxSource(Context c) {
        context = c;
        bg.execute(() -> {
            byte[] saved = read(new File(context.getFilesDir(), CATALOG_FILE));
            if (saved == null) return;
            final List<App> apps = parseCatalog(saved);
            ui.post(() -> {
                if (catalogAt == 0 && catalog.isEmpty()) {
                    catalog = apps;
                    notifyChanged();
                }
            });
        });
        registerDebug();
    }

    // --- ContentSource ---

    @Override public String prefix() { return PREFIX; }

    @Override public String title() { return context.getString(R.string.xd_linux_title); }

    @Override public List<Entry> apps() {
        ensureServer();
        if (server != null && !catalogLoading && SystemClock.elapsedRealtime() - catalogAt > CATALOG_MAX_AGE_MS) loadCatalog();
        List<Entry> out = new ArrayList<>(catalog.size());
        for (App a : catalog) out.add(new Entry(APP + a.key, a.name, a.icon, null, newestWindowOf(a) != null));
        return out;
    }

    @Override public List<Entry> windows() {
        ensureServer();
        List<Win> list = new ArrayList<>(windows.values());
        Collections.sort(list, (a, b) -> Long.compare(b.lastActive, a.lastActive));
        List<Entry> out = new ArrayList<>(list.size());
        for (Win w : list) {
            App a = appOf(w);
            Bitmap icon = windowIcons.get(w.id);
            if (icon == null && a != null) icon = a.icon;
            String label = a != null ? a.name : !w.cls.isEmpty() ? w.cls : w.title;
            out.add(new Entry(WINDOW + w.id, label, icon, w.title, true));
        }
        return out;
    }

    @Override public void open(final String key, int displayId, final Result result) {
        Contents.Host h = Contents.host();
        Runnable then = () -> openNow(key, result, 0);
        if (h != null) h.needDesktop(then, result, key);
        else then.run();
    }

    @Override public void close(String key) {
        ICmdEntryInterface s = ensureServer();
        if (s == null || key == null) return;
        if (key.startsWith(WINDOW)) {
            command(s, parseWindow(key), XWindows.CLOSE);
        } else if (key.startsWith(APP)) {
            App a = app(key.substring(APP.length()));
            if (a == null) return;
            for (Win w : windows.values()) if (appOf(w) == a) command(s, w.id, XWindows.CLOSE);
        }
    }

    @Override public void setListener(Runnable changed) { this.changed = changed; }

    @Override public void hidden(String key) {
        if (pending != null && pending.key.equals(key)) cancelPending();
        ICmdEntryInterface s = ensureServer();
        if (fullscreened != 0 && s != null && windows.containsKey(fullscreened)) command(s, fullscreened, XWindows.FULLSCREEN_OFF);
        fullscreened = 0;
    }

    // --- opening ---

    private void openNow(String key, Result result, int tries) {
        ICmdEntryInterface s = ensureServer();
        if (s == null) {
            result.failed(key, context.getString(R.string.xd_linux_no_x));
            return;
        }
        // The first window list comes right after the listener is set; a launch needs it to tell new windows apart
        if (!haveSnapshot && tries < 20) {
            ui.postDelayed(() -> openNow(key, result, tries + 1), 100);
            return;
        }
        if (key.startsWith(WINDOW)) {
            int id = parseWindow(key);
            if (!windows.containsKey(id)) {
                result.failed(key, context.getString(R.string.xd_linux_window_gone));
                return;
            }
            show(s, id, key, result);
            return;
        }
        final App a = key.startsWith(APP) ? app(key.substring(APP.length())) : null;
        if (a == null) {
            result.failed(key, context.getString(R.string.xd_linux_not_found));
            return;
        }
        Win w = newestWindowOf(a);
        if (w != null) {
            show(s, w.id, key, result);
            return;
        }
        launch(s, a, key, result);
    }

    private void launch(final ICmdEntryInterface s, final App a, final String key, final Result result) {
        cancelPending();
        final Pending p = new Pending();
        p.key = key;
        p.app = a;
        p.result = result;
        p.before = new HashSet<>(windows.keySet());
        p.startupId = "xdesk-" + a.shortStem.replaceAll("[^A-Za-z0-9._-]", "") + "-" + SystemClock.uptimeMillis() + "_TIME0";
        p.timeout = () -> {
            if (pending != p) return;
            pending = null;
            Log.i(TAG, "No window from " + a.key);
            p.result.failed(p.key, context.getString(R.string.xd_linux_no_window, a.name));
        };
        pending = p;
        ui.postDelayed(p.timeout, WINDOW_TIMEOUT_MS);
        Log.i(TAG, "Launching " + a.key);
        bg.execute(() -> {
            String r;
            try {
                r = s.launchApp(a.key, p.startupId);
            } catch (Exception e) {
                r = "!" + e.getMessage();
            }
            final String reply = r;
            ui.post(() -> {
                if (pending != p) return;
                if (reply == null) {
                    failPending(p, context.getString(R.string.xd_linux_old_x));
                } else if (reply.startsWith("!")) {
                    failPending(p, context.getString(R.string.xd_linux_launch_failed, a.name, reply.substring(1)));
                } else {
                    try { p.pid = Integer.parseInt(reply.trim()); } catch (NumberFormatException e) { p.pid = -1; }
                    Log.i(TAG, a.key + " started as pid " + p.pid);
                    matchPending();
                }
            });
        });
    }

    private void failPending(Pending p, String reason) {
        ui.removeCallbacks(p.timeout);
        if (pending == p) pending = null;
        Log.i(TAG, "Launch failed: " + reason);
        p.result.failed(p.key, reason);
    }

    private void cancelPending() {
        if (pending == null) return;
        ui.removeCallbacks(pending.timeout);
        pending = null;
    }

    /** A new window is the launched program's: its startup id, its WM_CLASS, or a process it started. */
    private void matchPending() {
        final Pending p = pending;
        if (p == null) return;
        final ICmdEntryInterface s = server;
        List<Win> fresh = new ArrayList<>();
        for (Win w : windows.values()) if (!p.before.contains(w.id)) fresh.add(w);
        for (Win w : fresh) {
            if ((!w.startupId.isEmpty() && w.startupId.equals(p.startupId)) || appOf(w) == p.app) {
                matched(p, w.id);
                return;
            }
        }
        if (p.pid <= 0 || s == null) return;
        for (final Win w : fresh) {
            if (!p.asked.add(w.id)) continue;
            final int pid = w.pid > 0 ? w.pid : w.clientPid;
            if (pid <= 0) continue;
            bg.execute(() -> {
                boolean mine;
                try { mine = s.isDescendant(pid, p.pid); } catch (Exception e) { mine = false; }
                if (mine) ui.post(() -> { if (pending == p && windows.containsKey(w.id)) matched(p, w.id); });
            });
        }
    }

    private void matched(Pending p, int window) {
        ui.removeCallbacks(p.timeout);
        pending = null;
        Log.i(TAG, p.app.key + " has window " + window);
        ICmdEntryInterface s = server;
        if (s == null) {
            p.result.failed(p.key, context.getString(R.string.xd_linux_no_x));
            return;
        }
        show(s, window, p.key, p.result);
    }

    /** The one full screen window: the one before back to normal, this one activated (raised, unminimized) and full screen. */
    private void show(ICmdEntryInterface s, int window, String key, Result result) {
        if (fullscreened != 0 && fullscreened != window && windows.containsKey(fullscreened))
            command(s, fullscreened, XWindows.FULLSCREEN_OFF);
        Win w = windows.get(window);
        boolean already = w != null && (w.flags & FLAG_FULLSCREEN) != 0;
        command(s, window, XWindows.ACTIVATE);
        if (!already) command(s, window, XWindows.FULLSCREEN_ON);
        // One the user made full screen stays so afterwards
        if (fullscreened != window) fullscreened = already ? 0 : window;
        result.shown(key, null);
    }

    private static void command(ICmdEntryInterface s, int window, int command) {
        if (window == 0) return;
        try {
            s.windowCommand(window, command); // oneway
        } catch (Exception e) {
            Log.w(TAG, "windowCommand", e);
        }
    }

    // --- the X server ---

    /** One per X server: what an earlier one still sends is dropped. */
    private IXWindowListener.Stub listenerFor(final ICmdEntryInterface s) {
        return new IXWindowListener.Stub() {
            @Override public void windows(byte[] snapshot) {
                final List<Win> list = parseWindows(snapshot);
                ui.post(() -> { if (server == s) windowsChanged(list); });
            }
            @Override public void icon(int window, int width, int height, int[] argb) {
                Bitmap b = null;
                if (width > 0 && height > 0 && argb != null && argb.length >= width * height) {
                    try {
                        b = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
                    } catch (RuntimeException e) {
                        b = null;
                    }
                }
                final Bitmap icon = b;
                ui.post(() -> {
                    if (server != s) return;
                    if (icon != null) windowIcons.put(window, icon);
                    else windowIcons.remove(window);
                    if (windows.containsKey(window)) notifyChanged();
                });
            }
        };
    }

    /** The X server XDesk is connected to now; follows a new one (a restarted X) by listening to it. */
    private ICmdEntryInterface ensureServer() {
        ICmdEntryInterface s = MainActivity.verifiedServer;
        if (s != null && !s.asBinder().isBinderAlive()) s = null;
        if (s == server) return s;
        server = s;
        windows.clear();
        windowIcons.clear();
        haveSnapshot = false;
        fullscreened = 0;
        if (pending != null) {
            Pending p = pending;
            cancelPending();
            if (s == null) p.result.failed(p.key, context.getString(R.string.xd_linux_no_x));
        }
        if (s != null) {
            final ICmdEntryInterface listenTo = s;
            bg.execute(() -> {
                try {
                    listenTo.setWindowListener(listenerFor(listenTo));
                } catch (Exception e) {
                    Log.w(TAG, "setWindowListener", e);
                }
            });
            loadCatalog();
        }
        notifyChanged();
        return s;
    }

    private void windowsChanged(List<Win> list) {
        haveSnapshot = true;
        LinkedHashMap<Integer, Win> old = new LinkedHashMap<>(windows);
        windows.clear();
        for (Win w : list) {
            Win was = old.get(w.id);
            w.lastActive = was != null ? was.lastActive : 0;
            if ((w.flags & FLAG_ACTIVE) != 0 && (was == null || (was.flags & FLAG_ACTIVE) == 0)) w.lastActive = ++activeCounter;
            windows.put(w.id, w);
        }
        windowIcons.keySet().retainAll(windows.keySet());
        if (fullscreened != 0 && !windows.containsKey(fullscreened)) fullscreened = 0;
        matchPending();
        notifyChanged();
    }

    private boolean changePosted;

    /** Posted and coalesced: apps() and windows() may notice a change while the chooser is reading them. */
    private void notifyChanged() {
        if (changePosted) return;
        changePosted = true;
        ui.post(() -> {
            changePosted = false;
            if (changed != null) changed.run();
        });
    }

    // --- the catalog ---

    private void loadCatalog() {
        final ICmdEntryInterface s = server;
        if (s == null || catalogLoading) return;
        catalogLoading = true;
        bg.execute(() -> {
            byte[] data = null;
            try {
                ParcelFileDescriptor fd = s.getAppCatalog();
                if (fd != null) {
                    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                        data = readAll(in);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getAppCatalog", e);
            }
            final List<App> apps = data != null ? parseCatalog(data) : null;
            if (data != null) write(new File(context.getFilesDir(), CATALOG_FILE), data);
            ui.post(() -> {
                catalogLoading = false;
                catalogAt = SystemClock.elapsedRealtime();
                if (apps == null) return;
                catalog = apps;
                Log.i(TAG, "Catalog: " + apps.size() + " apps");
                notifyChanged();
            });
        });
    }

    /** Background thread. */
    private static List<App> parseCatalog(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        List<App> out = new ArrayList<>();
        if (lines.length == 0 || !lines[0].startsWith("#xdesk-apps 1")) return out;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length < 5 || f[0].isEmpty() || f[1].isEmpty()) continue;
            App a = new App();
            a.key = f[0];
            a.name = f[1];
            a.terminal = "1".equals(f[2]);
            a.wmClass = f[3].toLowerCase(Locale.ROOT);
            String id = a.key.substring(a.key.indexOf('/') + 1).toLowerCase(Locale.ROOT);
            a.stem = id;
            a.shortStem = id.substring(id.lastIndexOf('.') + 1);
            if (!f[4].isEmpty()) {
                try {
                    byte[] png = Base64.decode(f[4], Base64.DEFAULT);
                    a.icon = BitmapFactory.decodeByteArray(png, 0, png.length);
                } catch (IllegalArgumentException e) {
                    a.icon = null;
                }
            }
            out.add(a);
        }
        final Collator c = Collator.getInstance();
        Collections.sort(out, (x, y) -> c.compare(x.name, y.name));
        return out;
    }

    private App app(String key) {
        for (App a : catalog) if (a.key.equals(key)) return a;
        return null;
    }

    /** The app a window belongs to: StartupWMClass, else WM_CLASS equal to the desktop id or its last part. */
    private App appOf(Win w) {
        String inst = w.instance.toLowerCase(Locale.ROOT), cls = w.cls.toLowerCase(Locale.ROOT);
        if (inst.isEmpty() && cls.isEmpty()) return null;
        for (App a : catalog)
            if (!a.wmClass.isEmpty() && (a.wmClass.equals(cls) || a.wmClass.equals(inst))) return a;
        for (App a : catalog)
            if (a.wmClass.isEmpty() && (a.stem.equals(cls) || a.stem.equals(inst) || a.shortStem.equals(cls) || a.shortStem.equals(inst)))
                return a;
        return null;
    }

    private Win newestWindowOf(App a) {
        Win best = null;
        for (Win w : windows.values())
            if (appOf(w) == a && (best == null || w.lastActive > best.lastActive)) best = w;
        return best;
    }

    // --- parsing and files ---

    private static List<Win> parseWindows(byte[] snapshot) {
        List<Win> out = new ArrayList<>();
        if (snapshot == null) return out;
        for (String line : new String(snapshot, StandardCharsets.UTF_8).split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 8) continue;
            try {
                Win w = new Win();
                w.id = (int) Long.parseLong(f[0]);
                w.pid = Integer.parseInt(f[1]);
                w.clientPid = Integer.parseInt(f[2]);
                w.flags = Integer.parseInt(f[3]);
                w.instance = f[4];
                w.cls = f[5];
                w.startupId = f[6].trim();
                w.title = f[7];
                out.add(w);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static int parseWindow(String key) {
        try {
            return (int) Long.parseLong(key.substring(WINDOW.length()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    // --- debug ---

    /**
     * Only the shell (adb, rsh) and the system hold INTERACT_ACROSS_USERS_FULL, like ConnectReceiver: no app can
     * open or close things through this.
     */
    private void registerDebug() {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                debug(i.getStringExtra("cmd"), i.getStringExtra("key"));
            }
        };
        try {
            context.registerReceiver(r, new IntentFilter(DEBUG_ACTION), "android.permission.INTERACT_ACROSS_USERS_FULL",
                    ui, Context.RECEIVER_EXPORTED);
        } catch (RuntimeException e) {
            Log.w(TAG, "debug receiver", e);
        }
    }

    private void debug(String cmd, String key) {
        if (cmd == null) cmd = "dump";
        Result log = new Result() {
            @Override public void shown(String k, InputSink input) { Log.i(TAG, "shown " + k); }
            @Override public void failed(String k, String reason) { Log.i(TAG, "failed " + k + ": " + reason); }
        };
        switch (cmd) {
            case "open":
                if (key != null) open(key, Contents.host() != null ? Contents.host().targetDisplayId() : 0, log);
                break;
            case "close":
                if (key != null) close(key);
                break;
            case "hidden":
                hidden(key);
                break;
            case "refresh":
                catalogAt = 0;
                loadCatalog();
                break;
            default:
                break;
        }
        Log.i(TAG, "server " + (ensureServer() != null) + ", snapshot " + haveSnapshot + ", fullscreen " + fullscreened
                + (pending != null ? ", waiting for " + pending.key + " pid " + pending.pid : ""));
        for (Entry e : apps()) Log.i(TAG, "app " + e.key + " \"" + e.label + "\"" + (e.icon != null ? " icon" : "") + (e.running ? " running" : ""));
        for (Entry e : windows()) Log.i(TAG, "window " + e.key + " \"" + e.label + "\" " + e.detail + (e.icon != null ? " icon" : ""));
    }
}
