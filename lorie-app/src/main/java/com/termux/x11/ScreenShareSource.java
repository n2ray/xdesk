// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.screenshare.ScreenShareActivity;
import com.termux.x11.screenshare.ScreenShareLoginActivity;
import com.termux.x11.screenshare.ScreenShareSession;
import com.termux.x11.screenshare.ScreenShareSizing;
import com.termux.x11.screenshare.ScreenShareStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Macs reached through macOS's own Screen Sharing (no app on the Mac, unlike Sill), as one ContentSource, prefix
 * "v:": "v:<id>:desktop" is that Mac's desktop, one virtual screen the size of the screen it first shows on (or the
 * 1920×1080 baseline).
 *
 * The connection is the user's: opened once (the password asked on the phone, or unlocked if they chose to keep
 * it, ScreenShareLoginActivity), it stays until they disconnect (the device's Disconnect, closing the entry) or
 * XDesk ends. Putting a Linux or Android app on the screen only takes the picture away (hidden: the decoder goes,
 * the connection and the Mac's virtual screen stay); opening the Mac again shows it at once, from a fresh IDR.
 */
final class ScreenShareSource implements ContentSource {
    static final String PREFIX = "v:";
    private static final String TAG = ScreenShareSession.TAG;
    private static ScreenShareSource instance;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable changed;
    private final HashMap<String, ScreenShareInput> inputs = new HashMap<>();
    /** Host id -> the open() waiting for its login: key, display id, result. */
    private final HashMap<String, Object[]> pending = new HashMap<>();

    private final ScreenShareSession.Listener sessions = new ScreenShareSession.Listener() {
        @Override public void connected(ScreenShareSession s) {
            notifyChanged();
        }
        @Override public void ended(ScreenShareSession s, String reason) {
            String id = s.host.id;
            if (reason != null) Toast.makeText(context, context.getString(R.string.xd_ss_ended, s.host.label(), reason),
                Toast.LENGTH_LONG).show();
            ScreenShareInput in = inputs.get(id);
            if (in != null) in.releaseAll();
            boolean wasShown = ScreenShareActivity.isShowing(id);
            ScreenShareActivity.finishFor(id);
            Contents.Host host = Contents.host();
            if (host != null && wasShown) host.contentEnded(key(id));
            notifyChanged();
        }
    };

    private ScreenShareSource(Context c) {
        context = c.getApplicationContext();
    }

    static void start(Context c) {
        if (instance != null) return;
        instance = new ScreenShareSource(c);
        ScreenShareLoginActivity.outcome = new ScreenShareLoginActivity.Outcome() {
            @Override public void password(String hostId, char[] password) {
                instance.connect(hostId, password);
            }
            @Override public void cancelled(String hostId) {
                instance.loginCancelled(hostId);
            }
        };
        Contents.register(instance);
    }

    static ScreenShareSource get() {
        return instance;
    }

    static String key(String hostId) {
        return PREFIX + hostId + ":desktop";
    }

    static String hostOf(String key) {
        if (key == null || !key.startsWith(PREFIX)) return null;
        int end = key.indexOf(':', PREFIX.length());
        return end > 0 ? key.substring(PREFIX.length(), end) : key.substring(PREFIX.length());
    }

    /** A Mac added, changed or removed in the settings. */
    void hostsChanged() {
        notifyChanged();
    }

    /** Connected (or connecting) to this Mac, whether or not it is on a screen now. */
    boolean running(String hostId) {
        ScreenShareSession s = ScreenShareSession.current();
        return s != null && s.host.id.equals(hostId) && s.alive();
    }

    /** The user's disconnect: the device's Disconnect, the entry closed, the Mac removed. */
    void disconnect(String hostId) {
        ScreenShareSession s = ScreenShareSession.current();
        if (s != null && s.host.id.equals(hostId)) s.close();
        else ScreenShareActivity.finishFor(hostId);
    }

    private void notifyChanged() {
        Runnable r = changed;
        if (r != null) ui.post(r);
        ui.post(() -> Devices.get(context).touch());
        Contents.Host host = Contents.host();
        if (host != null) host.contentChanged();
    }

    @Override public String prefix() {
        return PREFIX;
    }

    @Override public String title() {
        return context.getString(R.string.xd_ss_title);
    }

    @Override public List<Entry> apps() {
        ArrayList<Entry> out = new ArrayList<>();
        for (ScreenShareStore.Host h : ScreenShareStore.all(context))
            out.add(new Entry(key(h.id), h.label(), null, context.getString(R.string.xd_ss_entry_note), running(h.id)));
        return out;
    }

    @Override public List<Entry> windows() {
        ArrayList<Entry> out = new ArrayList<>();
        for (ScreenShareStore.Host h : ScreenShareStore.all(context))
            if (running(h.id)) out.add(new Entry(key(h.id), h.label(), null, context.getString(R.string.xd_ss_entry_note), true));
        return out;
    }

    @Override public void open(String key, int displayId, Result result) {
        String id = hostOf(key);
        ScreenShareStore.Host h = id != null ? ScreenShareStore.find(context, id) : null;
        if (h == null) {
            result.failed(key, context.getString(R.string.xd_ss_unknown));
            return;
        }
        if (running(id)) {
            show(id, displayId, key, result);
            return;
        }
        Object[] old = pending.put(id, new Object[] { key, displayId, result });
        if (old != null) ((Result) old[2]).failed((String) old[0], context.getString(R.string.xd_ss_cancelled));
        // Opened on the external screen and no kept password to unlock with the finger: the password is asked there,
        // where one is looking (owner, 22 November). A kept one needs the phone's fingerprint prompt anyway
        if (displayId != android.view.Display.DEFAULT_DISPLAY
            && !com.termux.x11.screenshare.ScreenSharePasswords.has(context, id)
            && MacLogin.ask(context, h, displayId)) return;
        Intent i = new Intent(context, ScreenShareLoginActivity.class);
        i.putExtra(ScreenShareLoginActivity.EXTRA_HOST, id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    /** The sizes for this Mac on that display (its output now; an assumed 1920×1080 one when it is not there). */
    static ScreenShareSizing sizing(Context c, int displayId, int tier) {
        Display d = c.getSystemService(DisplayManager.class).getDisplay(displayId);
        int[] out = ScreenShareSizing.output(d);
        return out != null ? ScreenShareSizing.of(tier, out[0], out[1]) : ScreenShareSizing.of(tier, 0, 0);
    }

    /** The login's password: connect, then show the Mac where it was asked for. */
    private void connect(String hostId, char[] password) {
        Object[] p = pending.remove(hostId);
        ScreenShareStore.Host h = ScreenShareStore.find(context, hostId);
        if (h == null || p == null) {
            Arrays.fill(password, (char) 0);
            return;
        }
        int displayId = (Integer) p[1];
        // 桌面缩放: worked out afresh at each connection from the screen it shows on, as it is driven now (its mode,
        // its rotation), and this Mac's tier; another screen, a turn or a new mode counts from the next connection
        ScreenShareSizing z = h.fixed1080 ? ScreenShareSizing.fixed1080()
            : sizing(context, displayId, h.scale);
        ScreenShareSession.start(context, h, password, z, sessions);
        show(hostId, displayId, (String) p[0], (Result) p[2]);
        notifyChanged();
    }

    private void show(String hostId, int displayId, String key, Result result) {
        Contents.Host host = Contents.host();
        if (host != null) host.freeDisplay();
        if (!ScreenShareActivity.isShowing(hostId)) {
            try {
                Intent i = new Intent(context, ScreenShareActivity.class);
                i.putExtra(ScreenShareActivity.EXTRA_HOST, hostId);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions o = ActivityOptions.makeBasic();
                if (displayId >= 0) o.setLaunchDisplayId(displayId);
                context.startActivity(i, o.toBundle());
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot show the Mac", e);
                result.failed(key, e.getMessage());
                return;
            }
        }
        result.shown(key, input(hostId));
    }

    private void loginCancelled(String hostId) {
        Object[] p = pending.remove(hostId);
        if (p != null) ((Result) p[2]).failed((String) p[0], context.getString(R.string.xd_ss_cancelled));
    }

    private ScreenShareInput input(String hostId) {
        ScreenShareInput in = inputs.get(hostId);
        if (in == null) {
            in = new ScreenShareInput(hostId);
            inputs.put(hostId, in);
        }
        return in;
    }

    /** The explicit close (quit) of the entry: the user disconnects. */
    @Override public void close(String key) {
        String id = hostOf(key);
        if (id != null) disconnect(id);
    }

    /** Something else took the screen: the picture goes, the connection stays. */
    @Override public void hidden(String key) {
        String id = hostOf(key);
        if (id == null) return;
        ScreenShareInput in = inputs.get(id);
        if (in != null) in.releaseAll();
        ScreenShareActivity.finishFor(id);
    }

    @Override public void setListener(Runnable changed) {
        this.changed = changed;
    }
}
