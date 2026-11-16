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
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

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
    private static final int FLAG_FULLSCREEN = 1, FLAG_ACTIVE = 2, FLAG_AUX = 8, FLAG_MAXIMIZED = 16, FLAG_OWN_FRAME = 32;
    /**
     * Between maximizing a window and making it full screen. A program that draws its own frame (Chromium, GTK header
     * bars) ignores a full screen state the window manager gives it and goes on drawing its shadow and round corners
     * inside the full screen window; told it is maximized first, it draws flush to the edges, and the full screen
     * state then only makes the window manager give it the whole screen. It has to see "maximized" alone once.
     */
    private static final long MAXIMIZE_FIRST_MS = 250;
    /** A window gone from the list this long has closed (a program may withdraw and map it again at once). */
    private static final long GONE_MS = 700;
    /** After this long a launch takes a dialog or a splash for its window too: the program may have nothing else. */
    private static final long AUX_AFTER_MS = 4000;
    private static final String CATALOG_FILE = "linux-apps.tsv";
    private static final String XDESK = "/data/data/com.termux/files/usr/bin/xdesk";

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
        long since = SystemClock.uptimeMillis();
        /** Windows already asked about (isDescendant), so each is asked once. */
        final Set<Integer> asked = new HashSet<>();
        /** The window it got (0: none). A status that arrives once it is there only reports a software retry. */
        int window;
        /** launchStatus said retried=software (CLI.md §5.2): say it once, when the window is there. */
        boolean softwareRetried, retriedNoticed;
        /** No more launchStatus calls: the window came, the wait ended, or the X server cannot answer it. */
        volatile boolean stopped;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> new Thread(r, "xdesk-linux"));

    private List<App> catalog = Collections.emptyList();
    private long catalogAt;
    private boolean catalogLoading;
    /** When the list was last asked of Termux, for as long as no X server has given one (0: never). */
    private long termuxCatalogAt;
    private final LinkedHashMap<Integer, Win> windows = new LinkedHashMap<>();
    private final Map<Integer, Bitmap> windowIcons = new HashMap<>();
    private long activeCounter;
    private boolean haveSnapshot;
    private ICmdEntryInterface server;
    private Runnable changed;
    private Pending pending;
    /** The window this source made full screen (0: none), restored when another content takes the screen. */
    private int fullscreened;
    /** The window this source maximized on the way to full screen (0: none), put back with it. */
    private int maximized;
    private Runnable fullscreenLater;
    /** The content on the screen and its window, from show() until hidden() or the window closes. */
    private String shownKey;
    private int shownWindow;
    /**
     * Just after a launch the program may open more windows (Chromium restores its last session): for a moment the one
     * of them that becomes active is the one to show, not the first that appeared.
     */
    private App settleApp;
    private String settleKey;
    private long settleUntil;
    private static final long SETTLE_MS = 3000;

    /** Termux is gone or new (Devices.forgetGoneTermux): the program list kept from it is no longer true. */
    static void forgetCatalog(Context c) {
        File f = new File(c.getFilesDir(), CATALOG_FILE);
        if (f.exists() && !f.delete()) Log.w(TAG, "Could not delete " + f);
        LinuxSource l = get();
        if (l == null) return;
        l.catalog = Collections.emptyList();
        l.catalogAt = 0;
        l.termuxCatalogAt = 0;
        l.notifyChanged();
    }

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
        else if (server == null && catalog.isEmpty()) loadCatalogFromTermux();
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
        if (key == null || key.equals(shownKey)) {
            shownKey = null;
            shownWindow = 0;
            ui.removeCallbacks(shownGone);
        }
        if (key != null && key.equals(settleKey)) settleApp = null;
        restore(ensureServer());
    }

    /** The window this source made full screen (and maximized) back as it was. */
    /**
     * A program with its own frame (Chromium, GTK header bars) that did not take the full screen state in: it still
     * draws its shadow margin, inside the full screen window (it says so: _GTK_FRAME_EXTENTS, FLAG_OWN_FRAME). Seen
     * when the state comes while the program is still starting. Out of full screen and in again, with the window
     * maximized in between, and it draws to the edges (tried on the device). A few times at most, never in a loop.
     */
    private static final long OWN_FRAME_SETTLE_MS = 700, OWN_FRAME_BACK_MS = 350;
    private static final int OWN_FRAME_TRIES = 3;
    private int frameWindow, frameTries;
    private Runnable frameCheck;

    private boolean ownFrameInFullscreen() {
        Win w = windows.get(fullscreened);
        return w != null && (w.flags & FLAG_FULLSCREEN) != 0 && (w.flags & FLAG_OWN_FRAME) != 0;
    }

    private void checkOwnFrame() {
        if (frameWindow != fullscreened) {
            frameWindow = fullscreened;
            frameTries = 0;
        }
        if (frameCheck != null || fullscreenLater != null || frameTries >= OWN_FRAME_TRIES || !ownFrameInFullscreen()) return;
        final int window = fullscreened;
        frameCheck = () -> {
            frameCheck = null;
            final ICmdEntryInterface s = server;
            // Still so after it had the time to follow by itself
            if (s == null || fullscreened != window || fullscreenLater != null || !ownFrameInFullscreen()) return;
            frameTries++;
            Log.i(TAG, "window " + window + " keeps its own frame in full screen, setting the state again (" + frameTries + ")");
            command(s, window, XWindows.MAXIMIZE_ON);
            maximized = window;
            command(s, window, XWindows.FULLSCREEN_OFF);
            fullscreenLater = () -> {
                fullscreenLater = null;
                if (server == s && fullscreened == window && windows.containsKey(window)) command(s, window, XWindows.FULLSCREEN_ON);
            };
            ui.postDelayed(fullscreenLater, OWN_FRAME_BACK_MS);
        };
        ui.postDelayed(frameCheck, OWN_FRAME_SETTLE_MS);
    }

    private void restore(ICmdEntryInterface s) {
        if (frameCheck != null) ui.removeCallbacks(frameCheck);
        frameCheck = null;
        if (fullscreenLater != null) ui.removeCallbacks(fullscreenLater);
        fullscreenLater = null;
        Win f = windows.get(fullscreened), m = windows.get(maximized);
        if (s != null && f != null) {
            command(s, fullscreened, XWindows.FULLSCREEN_OFF);
            // What the next snapshot will say: a show() before it comes must not take the window for full screen still
            f.flags &= ~FLAG_FULLSCREEN;
        }
        if (s != null && m != null) {
            command(s, maximized, XWindows.MAXIMIZE_OFF);
            m.flags &= ~FLAG_MAXIMIZED;
        }
        fullscreened = 0;
        maximized = 0;
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
        // The environment goes into the launch token: the window that carries it is that environment's (scopeOfStartupId)
        p.startupId = "xdesk-" + scopeToken(scopeOfApp(a)) + "--" + a.shortStem.replaceAll("[^A-Za-z0-9._-]", "") + "-" + SystemClock.uptimeMillis() + "_TIME0";
        p.timeout = () -> {
            if (pending != p) return;
            p.stopped = true;
            pending = null;
            ui.removeCallbacks(auxNow);
            Log.i(TAG, "No window from " + a.key);
            p.result.failed(p.key, context.getString(R.string.xd_linux_no_window, a.name));
        };
        pending = p;
        ui.postDelayed(p.timeout, WINDOW_TIMEOUT_MS);
        ui.removeCallbacks(auxNow);
        ui.postDelayed(auxNow, AUX_AFTER_MS + 100);
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
                    failPending(p, launchError(a, reply.substring(1)));
                } else {
                    try { p.pid = Integer.parseInt(reply.trim()); } catch (NumberFormatException e) { p.pid = -1; }
                    Log.i(TAG, a.key + " started as pid " + p.pid);
                    matchPending();
                    // How it went, once a second, until the window comes or the 15 seconds are over (CLI.md §5.4)
                    if (pending == p) ui.postDelayed(() -> pollStatus(p), 1000);
                }
            });
        });
    }

    private void failPending(Pending p, String reason) {
        ui.removeCallbacks(p.timeout);
        p.stopped = true;
        if (pending == p) pending = null;
        Log.i(TAG, "Launch failed: " + reason);
        p.result.failed(p.key, reason);
    }

    private void cancelPending() {
        if (pending == null) return;
        pending.stopped = true;
        ui.removeCallbacks(pending.timeout);
        ui.removeCallbacks(auxNow);
        pending = null;
    }

    // --- why a launched program did not come up (CLI.md §5.4) ---

    /** The status xdesk wrote when the program exited: key=value lines, "--", then the log's last lines. */
    private static final class LaunchStatus {
        String reason = "", detail = "", retried = "";
        /** running=1: written when the wrapper retries with software rendering, before the program has ended. */
        boolean running;
        final List<String> tail = new ArrayList<>();
    }

    /** Asks the X server how the launch went, on the background thread: it reads files in Termux. UI thread. */
    private void pollStatus(final Pending p) {
        if (p.stopped || pending != p) return;
        final ICmdEntryInterface s = server;
        if (s == null) return;
        bg.execute(() -> {
            if (p.stopped) return;
            String status;
            try {
                status = s.launchStatus(p.pid);
            } catch (RemoteException | AbstractMethodError | SecurityException e) {
                // An X server from before this call: it will never have a status for this launch
                Log.i(TAG, "No launchStatus from the X server: " + e);
                p.stopped = true;
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "launchStatus", e);
                status = null;
            }
            final String answer = status;
            ui.post(() -> statusArrived(p, answer));
        });
    }

    /** UI thread. A null answer means the program is still running (or has no status yet): keep waiting. */
    private void statusArrived(final Pending p, String status) {
        if (status == null) {
            if (!p.stopped && pending == p) ui.postDelayed(() -> pollStatus(p), 1000);
            return;
        }
        final LaunchStatus st = parseStatus(status);
        if (st.running) {
            // Not the end: the retry with software rendering runs now, its window is still to come (CLI.md §5.2)
            if ("software".equals(st.retried)) p.softwareRetried = true;
            if (!p.stopped && pending == p) ui.postDelayed(() -> pollStatus(p), 1000);
            else showRetried(p);
            return;
        }
        p.stopped = true;
        p.softwareRetried = "software".equals(st.retried);
        final boolean failed = !"ok".equals(st.reason);
        if (failed) logLaunchTail(p.app, st);
        if (pending != p) {
            // The window came while this answer was on its way: only the software note can still be due
            showRetried(p);
            return;
        }
        if (failed) {
            failPending(p, launchStatusText(p.app, st));
            return;
        }
        // Exit code 0 and no window: a command-line program, or a single-instance one already running elsewhere
        failPending(p, context.getString(R.string.xd_linux_launch_ok_no_window, p.app.name));
    }

    private static LaunchStatus parseStatus(String text) {
        LaunchStatus st = new LaunchStatus();
        boolean log = false;
        for (String line : text.split("\n", -1)) {
            if (!log) {
                if (line.trim().equals("--")) {
                    log = true;
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("reason")) st.reason = v;
                else if (k.equals("detail")) st.detail = v;
                else if (k.equals("retried")) st.retried = v;
                else if (k.equals("running")) st.running = "1".equals(v);
            } else {
                st.tail.add(line);
            }
        }
        return st;
    }

    /** The reason in the user's words; a detail the shell left out is the log's last line. */
    private String launchStatusText(App a, LaunchStatus st) {
        String detail = st.detail;
        if (detail.isEmpty()) {
            for (int i = st.tail.size() - 1; i >= 0; i--) {
                if (!st.tail.get(i).trim().isEmpty()) {
                    detail = st.tail.get(i).trim();
                    break;
                }
            }
        }
        switch (st.reason) {
            case "not-found": return context.getString(R.string.xd_linux_launch_not_found, a.name, detail);
            case "missing-lib": return context.getString(R.string.xd_linux_launch_missing_lib, a.name, detail);
            case "no-display": return context.getString(R.string.xd_linux_launch_no_display, a.name);
            case "gpu": return context.getString(R.string.xd_linux_launch_gpu, a.name);
            case "crash": return context.getString(R.string.xd_linux_launch_crash, a.name, detail);
            case "error": return context.getString(R.string.xd_linux_launch_error, a.name, detail);
            default: return context.getString(R.string.xd_linux_launch_failed, a.name, detail.isEmpty() ? st.reason : detail);
        }
    }

    /** The log's last lines in logcat, so the message the user saw can be checked (CLI.md §5.4). */
    private static void logLaunchTail(App a, LaunchStatus st) {
        StringBuilder out = new StringBuilder();
        for (String line : st.tail) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        if (out.length() > 0) Log.w(TAG, "Launch of " + a.key + " ended (" + st.reason + "):\n" + out);
    }

    /** The window is there: the software rendering note, once (CLI.md §5.2). UI thread. */
    private void showRetried(Pending p) {
        if (!p.softwareRetried || p.retriedNoticed || p.window == 0 || !windows.containsKey(p.window)) return;
        p.retriedNoticed = true;
        Toast.makeText(context, context.getString(R.string.xd_linux_launch_retried, p.app.name), Toast.LENGTH_LONG).show();
    }

    /**
     * "xdesk-error CODE DETAIL" from xdesk before it started anything (CLI.md §5.1, §5.4), in the user's words; other
     * text is shown as it came.
     */
    private String launchError(App a, String why) {
        String text = why.trim();
        String prefix = "xdesk-error ";
        if (!text.startsWith(prefix)) return context.getString(R.string.xd_linux_launch_failed, a.name, text);
        String rest = text.substring(prefix.length()).trim();
        int space = rest.indexOf(' ');
        String code = space < 0 ? rest : rest.substring(0, space);
        String detail = space < 0 ? "" : rest.substring(space + 1).trim();
        switch (code) {
            case "not-installed": {
                // The command, then possibly more from the shell; the user only needs the command
                int end = detail.indexOf(' ');
                if (end > 0) detail = detail.substring(0, end);
                return context.getString(R.string.xd_linux_launch_not_found, a.name, detail);
            }
            case "no-terminal": return context.getString(R.string.xd_linux_launch_no_terminal, a.name);
            case "no-env": return context.getString(R.string.xd_linux_launch_no_env, a.name, detail);
            case "no-app": return context.getString(R.string.xd_linux_not_found);
            case "bad-key": return context.getString(R.string.xd_linux_launch_failed, a.name, detail);
            default: return context.getString(R.string.xd_linux_launch_failed, a.name, detail.isEmpty() ? text : detail);
        }
    }

    /** The launch has waited long enough for a main window: a dialog or splash of the program will do now. */
    private final Runnable auxNow = this::matchPending;

    /** A new window is the launched program's: its startup id, its WM_CLASS, or a process it started. */
    private void matchPending() {
        final Pending p = pending;
        if (p == null) return;
        final ICmdEntryInterface s = server;
        List<Win> fresh = new ArrayList<>();
        // A splash or a dialog is not the program's window while it may still open one (it would be made full screen)
        boolean aux = SystemClock.uptimeMillis() - p.since >= AUX_AFTER_MS;
        for (Win w : windows.values()) if (!p.before.contains(w.id) && (aux || (w.flags & FLAG_AUX) == 0)) fresh.add(w);
        // A new window of unknown environment is looked up among the launched program's environment: the same program
        // may be installed in another one too, which appOf(w) would prefer
        final String scope = scopeOfApp(p.app);
        for (Win w : fresh) {
            if ((!w.startupId.isEmpty() && w.startupId.equals(p.startupId))
                    || (windowScopes.containsKey(w.id) ? appOf(w) : appOf(w, scope)) == p.app) {
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

    private void settle(Map<Integer, Win> old) {
        if (settleApp == null) return;
        if (SystemClock.uptimeMillis() > settleUntil) { settleApp = null; return; }
        ICmdEntryInterface s = server;
        if (s == null) return;
        for (Win w : windows.values()) {
            Win was = old.get(w.id);
            boolean nowActive = (w.flags & FLAG_ACTIVE) != 0 && (was == null || (was.flags & FLAG_ACTIVE) == 0);
            if (nowActive && w.id != shownWindow && (w.flags & FLAG_AUX) == 0 && settleKey.equals(shownKey) && appOf(w) == settleApp) {
                Log.i(TAG, settleApp.key + " moved to window " + w.id);
                show(s, w.id, settleKey, null);
                return;
            }
        }
    }

    private void matched(Pending p, int window) {
        ui.removeCallbacks(p.timeout);
        ui.removeCallbacks(auxNow);
        p.stopped = true;
        p.window = window;
        pending = null;
        settleApp = p.app;
        settleKey = p.key;
        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;
        Log.i(TAG, p.app.key + " has window " + window);
        showRetried(p);
        // Its windows are the app's from now on, whatever their class: Chromium's are chromium-browser, its id chromium
        Win w = windows.get(window);
        // Launched by xdesk in that app's environment, whatever the window says about itself
        if (w != null) windowScopes.put(window, scopeOfApp(p.app));
        if (w != null && appOf(w) != p.app && !w.cls.isEmpty()) learnedClasses.put(w.cls.toLowerCase(Locale.ROOT), p.app.key);
        ICmdEntryInterface s = server;
        if (s == null) {
            p.result.failed(p.key, context.getString(R.string.xd_linux_no_x));
            return;
        }
        show(s, window, p.key, p.result);
    }

    /**
     * The one full screen window: the one before back to normal, this one activated (raised, unminimized) and full
     * screen, maximized first so that a program with its own frame draws none (MAXIMIZE_FIRST_MS).
     */
    private void show(final ICmdEntryInterface s, final int window, String key, Result result) {
        if (fullscreened != window || (maximized != 0 && maximized != window)) restore(s);
        Win w = windows.get(window);
        boolean already = w != null && (w.flags & FLAG_FULLSCREEN) != 0;
        command(s, window, XWindows.ACTIVATE);
        if (!already && fullscreenLater == null) {
            if (w != null && (w.flags & FLAG_MAXIMIZED) == 0 && maximized != window) {
                command(s, window, XWindows.MAXIMIZE_ON);
                maximized = window;
                fullscreenLater = () -> {
                    fullscreenLater = null;
                    if (server == s && fullscreened == window && windows.containsKey(window)) command(s, window, XWindows.FULLSCREEN_ON);
                };
                ui.postDelayed(fullscreenLater, MAXIMIZE_FIRST_MS);
            } else {
                command(s, window, XWindows.FULLSCREEN_ON);
            }
        }
        // One the user made full screen stays so afterwards
        if (fullscreened != window) fullscreened = already ? 0 : window;
        if (key != null) {
            shownKey = key;
            shownWindow = window;
            ui.removeCallbacks(shownGone);
        }
        if (result != null) result.shown(key, null);
    }

    /**
     * The window of the content on the screen closed: another window of the same program takes its place, else the
     * content has ended and the host hears it (MACMENU.md §8: the previous content, or the Launchpad home).
     */
    private final Runnable shownGone = () -> {
        final String key = shownKey;
        if (key == null || windows.containsKey(shownWindow)) return;
        ICmdEntryInterface s = server;
        App a = key.startsWith(APP) ? app(key.substring(APP.length())) : null;
        Win next = a != null ? newestWindowOf(a) : null;
        if (next != null && s != null) {
            Log.i(TAG, key + " goes on in window " + next.id);
            show(s, next.id, key, null);
            return;
        }
        Log.i(TAG, key + " has no window any more");
        shownKey = null;
        shownWindow = 0;
        Contents.Host h = Contents.host();
        if (h != null) h.contentGone(key);
    };

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
        windowScopes.clear();
        haveSnapshot = false;
        fullscreened = 0;
        maximized = 0;
        if (fullscreenLater != null) ui.removeCallbacks(fullscreenLater);
        fullscreenLater = null;
        if (frameCheck != null) ui.removeCallbacks(frameCheck);
        frameCheck = null;
        // The host ends a Linux content itself when its X goes
        shownKey = null;
        shownWindow = 0;
        ui.removeCallbacks(shownGone);
        settleApp = null;
        if (pending != null) {
            // Launched on the X server that is gone: with the new one, or none, its window will never be seen
            final Pending p = pending;
            cancelPending();
            ui.post(() -> p.result.failed(p.key, context.getString(R.string.xd_linux_no_x)));
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
        checkOwnFrame();
        learnScopes();
        if (fullscreened != 0 && !windows.containsKey(fullscreened)) fullscreened = 0;
        if (maximized != 0 && !windows.containsKey(maximized)) maximized = 0;
        settle(old);
        matchPending();
        if (shownKey != null && !windows.containsKey(shownWindow) && !ui.hasCallbacks(shownGone)) ui.postDelayed(shownGone, GONE_MS);
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

    /**
     * Only the X server gives the list (with icons), and with no desktop to start nothing ever starts X: a Termux with
     * programs and no desktop showed none (CLI.md §3.1). Before any X has run, xdesk app catalog: the same list without
     * icons. The X server's list replaces it once one runs. Not saved, so an old xdesk without it costs nothing later.
     */
    private void loadCatalogFromTermux() {
        long now = SystemClock.elapsedRealtime();
        if (termuxCatalogAt != 0 && now - termuxCatalogAt < CATALOG_MAX_AGE_MS) return;
        termuxCatalogAt = now;
        TermuxCommand.run(context, XDESK + " app catalog 2>/dev/null", new TermuxCommand.Callback() {
            @Override public void onOutput(String stdout) {
                // An xdesk without the command prints its usage: no catalog header, so no programs. No icons to decode,
                // so the UI thread is fine
                List<App> apps = parseCatalog(stdout.getBytes(StandardCharsets.UTF_8));
                if (apps.isEmpty() || !catalog.isEmpty()) return;
                catalog = apps;
                Log.i(TAG, "Catalog from Termux, without icons: " + apps.size() + " apps");
                notifyChanged();
            }
            @Override public void onFailure(String message) {
                Log.i(TAG, "No catalog from Termux: " + message);
            }
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
            // A packager's unfilled template (Termux's chromium.desktop: @@startup_wm_class) names no class
            if (a.wmClass.contains("@@")) a.wmClass = "";
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

    /**
     * The environment a key's program comes from: "termux" or a session's name (xdesk's SCOPE), or null when it is
     * not known (a window no program of the catalog claims). For Devices (DEVICES.md §1). UI thread.
     */
    String scopeOf(String key) {
        String appKey = null;
        if (key.startsWith(APP)) {
            appKey = key.substring(APP.length());
        } else if (key.startsWith(WINDOW)) {
            Win w = windows.get(parseWindow(key));
            String known = w != null ? windowScopes.get(w.id) : null;
            if (known != null) return known;
            App a = w != null ? appOf(w) : null;
            if (a != null) appKey = a.key;
        }
        if (appKey == null) return null;
        int slash = appKey.indexOf('/');
        return slash > 0 ? appKey.substring(0, slash) : null;
    }

    private App app(String key) {
        for (App a : catalog) if (a.key.equals(key)) return a;
        return null;
    }

    /** WM_CLASS (lowercase) → app key, learned from a launched app's first window (matched). */
    private final HashMap<String, String> learnedClasses = new HashMap<>();

    /**
     * The app a window belongs to: StartupWMClass, else WM_CLASS equal to the desktop id or its last part, else the
     * class one of its launches showed (learnedClasses), else a class that is the id and a dash-suffix ("chromium" for
     * chromium-browser, the longest such id). Without the last two, an app whose class is not its id seemed to have no
     * window, so every pick launched it again, and a single-instance program's new window (opened by its running
     * process, not the launched one) was never found: no picture.
     */
    private App appOf(Win w) {
        // All environments share one X server (DEVICES.md §2), and the same program may be installed in several: a
        // window whose environment is known is only ever a program of that one. Not known (started from a desktop's
        // panel or a terminal): the environment whose whole desktop runs first, else Termux, then any
        String scope = windowScopes.get(w.id);
        if (scope != null) return appOf(w, scope);
        App a = appOf(w, homeScope());
        return a != null ? a : appOf(w, null);
    }

    /** @param scope only programs of this environment, null: of any */
    private App appOf(Win w, String scope) {
        String inst = w.instance.toLowerCase(Locale.ROOT), cls = w.cls.toLowerCase(Locale.ROOT);
        if (inst.isEmpty() && cls.isEmpty()) return null;
        for (App a : catalog)
            if (in(a, scope) && !a.wmClass.isEmpty() && (a.wmClass.equals(cls) || a.wmClass.equals(inst))) return a;
        for (App a : catalog)
            if (in(a, scope) && a.wmClass.isEmpty() && (a.stem.equals(cls) || a.stem.equals(inst) || a.shortStem.equals(cls) || a.shortStem.equals(inst)))
                return a;
        String learned = learnedClasses.get(cls);
        if (learned != null) {
            App a = app(learned);
            // Learned from the same program of another environment: this environment's one, if it has it
            if (a != null && !in(a, scope)) a = app(scope + learned.substring(learned.indexOf('/')));
            if (a != null) return a;
        }
        App best = null;
        for (App a : catalog)
            if (in(a, scope) && a.wmClass.isEmpty() && (best == null || a.stem.length() > best.stem.length())
                    && (cls.startsWith(a.stem + "-") || inst.startsWith(a.stem + "-")))
                best = a;
        return best;
    }

    // --- which environment a window belongs to ---

    /**
     * The environment (xdesk's SCOPE: "termux" or a proot or chroot session's name) of the windows where it is known:
     * the launch token XDesk gave the program (DESKTOP_STARTUP_ID, which also survives a restart of XDesk under a
     * running X), the launch that was matched to the window, or another window of the same process. A window says
     * nothing else about where its program runs: proot and chroot programs are ordinary clients of the one X server.
     */
    private final HashMap<Integer, String> windowScopes = new HashMap<>();

    private static String scopeOfApp(App a) {
        int slash = a.key.indexOf('/');
        return slash > 0 ? a.key.substring(0, slash) : Devices.TERMUX;
    }

    private static boolean in(App a, String scope) {
        return scope == null || (a.key.startsWith(scope) && a.key.length() > scope.length() && a.key.charAt(scope.length()) == '/');
    }

    private static String scopeToken(String scope) {
        return scope.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** "xdesk-SCOPE--…", as launch() makes it: the scope, if it is one of the catalog's. */
    private String scopeOfStartupId(String id) {
        if (id == null || !id.startsWith("xdesk-")) return null;
        String best = null;
        for (App a : catalog) {
            String scope = scopeOfApp(a);
            if ((best == null || scope.length() > best.length()) && id.startsWith("xdesk-" + scopeToken(scope) + "--")) best = scope;
        }
        return best;
    }

    private String homeScope() {
        Devices d = Devices.get(context);
        return d.deviceOfSession(d.appsOnlyRunning() ? null : d.runningSession()).substring(Devices.LINUX.length());
    }

    private void learnScopes() {
        windowScopes.keySet().retainAll(windows.keySet());
        for (Win w : windows.values()) {
            if (windowScopes.containsKey(w.id)) continue;
            String scope = scopeOfStartupId(w.startupId);
            if (scope != null) windowScopes.put(w.id, scope);
        }
        // The program's other windows: the same process as one whose environment is known
        for (Win w : windows.values()) {
            if (windowScopes.containsKey(w.id)) continue;
            for (Win o : windows.values()) {
                String scope = windowScopes.get(o.id);
                boolean same = (w.pid > 0 && w.pid == o.pid) || (w.clientPid > 0 && w.clientPid == o.clientPid);
                if (scope != null && same && o != w) {
                    windowScopes.put(w.id, scope);
                    break;
                }
            }
        }
    }

    /** The program's most recently active window; a dialog or a splash (FLAG_AUX) only when it has no other. */
    private Win newestWindowOf(App a) {
        Win best = null;
        for (Win w : windows.values()) {
            if (appOf(w) != a) continue;
            boolean aux = (w.flags & FLAG_AUX) != 0;
            if (best == null || ((best.flags & FLAG_AUX) != 0 && !aux)
                    || (aux == ((best.flags & FLAG_AUX) != 0) && w.lastActive > best.lastActive)) best = w;
        }
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
                + ", maximized " + maximized + ", shown " + shownKey + " in " + shownWindow
                + (pending != null ? ", waiting for " + pending.key + " pid " + pending.pid : ""));
        for (Entry e : apps()) Log.i(TAG, "app " + e.key + " \"" + e.label + "\"" + (e.icon != null ? " icon" : "") + (e.running ? " running" : ""));
        for (Entry e : windows()) Log.i(TAG, "window " + e.key + " \"" + e.label + "\" " + e.detail + (e.icon != null ? " icon" : ""));
    }
}
