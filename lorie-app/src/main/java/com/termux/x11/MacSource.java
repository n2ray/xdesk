package com.termux.x11;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacActivity;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The paired Macs as one ContentSource (DISPLAY.md §1.3, §1.5 Mac column), prefix "m:":
 * - "m:<mac>:desktop" the Mac desktop (Sill's Desktop source);
 * - "m:<mac>:win:<window id>" a Mac window;
 * - "m:<mac>:app:<bundle id>" an installed app: its newest window if it has one, else launched, and the host picks
 *   its first window (Sill's pendingLaunch).
 * Opening puts MacActivity full screen on the display; the picture is the Mac's HEVC decoded into its SurfaceView.
 * Taking it off the screen (hidden) stops the stream and keeps the connection; close() closes the window on the
 * Mac. Nothing connects until the chooser asks for the catalog or something is opened.
 */
final class MacSource implements ContentSource {
    private static final String TAG = "XDeskMac";
    static final String PREFIX = "m:";
    /** A Mac taken off the screen waits this long before its stream stops: another of its windows may follow. */
    private static final long HIDE_DELAY_MS = 400;
    private static MacSource instance;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable changed;
    private final HashMap<String, MacInput> inputs = new HashMap<>();
    private final HashMap<String, Runnable> pendingHide = new HashMap<>();
    /** Set by XDeskActivity: Home and overview on a Mac window open XDesk's switcher for that Mac. */
    static MacInput.Switcher switcher;

    private MacSource(Context c) {
        context = c.getApplicationContext();
    }

    /** Register once per process (XDeskActivity.onCreate). Cheap: reads the saved Macs, connects nothing. */
    static void start(Context c) {
        if (instance != null) return;
        instance = new MacSource(c);
        MacClients.addListener(() -> instance.ui.post(instance::watchClients));
        instance.watchClients();
        Contents.register(instance);
    }

    private void watchClients() {
        for (MacClient m : MacClients.all(context)) m.setListener(c -> fireChanged());
        fireChanged();
    }

    /** Icons come one by one after a connect: one refresh of the chooser for a burst of them. */
    private final Runnable fire = () -> {
        if (changed != null) changed.run();
    };

    private void fireChanged() {
        ui.removeCallbacks(fire);
        ui.postDelayed(fire, 150);
    }

    @Override public String prefix() {
        return PREFIX;
    }

    @Override public String title() {
        List<MacClient> all = MacClients.all(context);
        return all.size() == 1 ? context.getString(R.string.xd_mac_section_one, all.get(0).name()) : "Mac";
    }

    private static String macPrefix(MacClient m) {
        return PREFIX + m.mac().id + ":";
    }

    private static boolean hasWindow(MacClient m, String bundle) {
        for (MacClient.Window w : m.windows()) if (bundle.equals(w.bundleId)) return true;
        return false;
    }

    @Override public List<Entry> apps() {
        ArrayList<Entry> out = new ArrayList<>();
        for (MacClient m : MacClients.all(context)) {
            m.touch();
            for (MacClient.App a : m.apps())
                out.add(new Entry(macPrefix(m) + "app:" + a.bundleId, a.name, m.icon(a.bundleId), null, hasWindow(m, a.bundleId)));
        }
        return out;
    }

    @Override public List<Entry> windows() {
        ArrayList<Entry> out = new ArrayList<>();
        for (MacClient m : MacClients.all(context)) {
            m.touch();
            String state = m.locked() ? context.getString(R.string.xd_mac_locked_short)
                    : m.state() == MacClient.State.CONNECTED ? null
                    : m.state() == MacClient.State.FAILED && m.failure() != null ? m.failure()
                    : context.getString(R.string.xd_mac_offline);
            out.add(new Entry(macPrefix(m) + "desktop", context.getString(R.string.xd_mac_desktop, m.name()), null, state,
                    m.state() == MacClient.State.CONNECTED));
            for (MacClient.Window w : m.windows())
                out.add(new Entry(macPrefix(m) + "win:" + w.id, w.appName, m.icon(w.bundleId), w.title, true));
        }
        return out;
    }

    /** {client, kind ("desktop", "win", "app"), rest} or null. */
    private Object[] parse(String key) {
        if (key == null || !key.startsWith(PREFIX)) return null;
        String[] p = key.substring(PREFIX.length()).split(":", 3);
        if (p.length < 2) return null;
        MacClient m = MacClients.get(context, p[0]);
        if (m == null) return null;
        return new Object[] {m, p[1], p.length > 2 ? p[2] : ""};
    }

    @Override public void open(final String key, final int displayId, final Result result) {
        final Object[] k = parse(key);
        if (k == null) {
            result.failed(key, context.getString(R.string.xd_mac_not_paired));
            return;
        }
        final MacClient m = (MacClient) k[0];
        Runnable hide = pendingHide.remove(m.mac().id);
        if (hide != null) ui.removeCallbacks(hide);
        new Thread(() -> {
            boolean up = m.awaitConnected(10_000);
            ui.post(() -> {
                if (!up) {
                    String why = m.failure();
                    result.failed(key, why != null && !why.isEmpty() ? context.getString(R.string.xd_mac_cannot_connect_because, m.name(), why)
                            : context.getString(R.string.xd_mac_cannot_connect, m.name()));
                    return;
                }
                place(key, k, m, displayId, result);
            });
        }, "mac-open").start();
    }

    private void place(String key, Object[] k, MacClient m, int displayId, Result result) {
        String kind = (String) k[1], rest = (String) k[2];
        if ("desktop".equals(kind)) {
            m.show(MacClient.DESKTOP);
        } else if ("win".equals(kind)) {
            try {
                m.show(Long.parseLong(rest));
            } catch (NumberFormatException e) {
                result.failed(key, context.getString(R.string.xd_mac_not_paired));
                return;
            }
        } else if ("app".equals(kind)) {
            long newest = -1;
            // The Mac lists windows first seen first: the last of the app's is its newest
            for (MacClient.Window w : m.windows()) if (rest.equals(w.bundleId)) newest = w.id;
            if (newest >= 0) m.show(newest);
            else m.launch(rest);
        } else {
            result.failed(key, context.getString(R.string.xd_mac_not_paired));
            return;
        }
        Contents.Host host = Contents.host();
        if (host != null) host.freeDisplay();
        if (!MacActivity.isShowing(m.mac().id)) {
            try {
                Intent i = new Intent(context, MacActivity.class);
                i.putExtra(MacActivity.EXTRA_MAC, m.mac().id);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(displayId);
                context.startActivity(i, o.toBundle());
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot show the Mac", e);
                result.failed(key, e.getMessage());
                return;
            }
        }
        result.shown(key, input(m));
    }

    private MacInput input(MacClient m) {
        MacInput in = inputs.get(m.mac().id);
        if (in == null) {
            final String p = macPrefix(m);
            in = new MacInput(m, p, prefix -> {
                MacInput.Switcher s = switcher;
                if (s != null) s.show(prefix);
            });
            inputs.put(m.mac().id, in);
        }
        return in;
    }

    @Override public void close(String key) {
        Object[] k = parse(key);
        if (k == null) return;
        MacClient m = (MacClient) k[0];
        String kind = (String) k[1], rest = (String) k[2];
        if ("win".equals(kind)) {
            try {
                m.windowCommand(Long.parseLong(rest), "close");
            } catch (NumberFormatException ignored) { }
        } else if ("app".equals(kind)) {
            // Sill can't quit an app: its windows close
            for (MacClient.Window w : m.windows()) if (rest.equals(w.bundleId)) m.windowCommand(w.id, "close");
        }
    }

    @Override public void hidden(String key) {
        Object[] k = parse(key);
        if (k == null) return;
        final MacClient m = (MacClient) k[0];
        final String id = m.mac().id;
        MacInput in = inputs.get(id);
        if (in != null) in.releaseAll();
        Runnable old = pendingHide.remove(id);
        if (old != null) ui.removeCallbacks(old);
        Runnable hide = new Runnable() {
            @Override public void run() {
                pendingHide.remove(id);
                m.show(MacClient.NONE);
                MacActivity.finishFor(id);
            }
        };
        pendingHide.put(id, hide);
        ui.postDelayed(hide, HIDE_DELAY_MS);
    }

    @Override public void setListener(Runnable changed) {
        this.changed = changed;
    }
}
