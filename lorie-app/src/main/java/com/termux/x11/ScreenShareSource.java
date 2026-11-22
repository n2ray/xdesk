// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.termux.x11.app.R;
import com.termux.x11.screenshare.ScreenShareActivity;
import com.termux.x11.screenshare.ScreenShareLoginActivity;
import com.termux.x11.screenshare.ScreenShareSession;
import com.termux.x11.screenshare.ScreenShareStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Macs reached through macOS's own Screen Sharing (no app on the Mac, unlike Sill), as one ContentSource, prefix
 * "v:": "v:<id>:desktop" is that Mac's desktop, one virtual screen the size of the screen it shows on (or the
 * 1920×1080 baseline). Opening it asks the password on the phone (ScreenShareLoginActivity), then
 * ScreenShareActivity shows the picture on the chosen display and XDesk's input goes to ScreenShareInput. Taken
 * off the screen (hidden) or closed, the session ends: the Mac's display layout comes back and the ports are free.
 */
final class ScreenShareSource implements ContentSource {
    static final String PREFIX = "v:";
    private static ScreenShareSource instance;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable changed;
    private final HashMap<String, ScreenShareInput> inputs = new HashMap<>();
    /** Host id -> the open() waiting for its login. */
    private final HashMap<String, Object[]> pending = new HashMap<>();

    private ScreenShareSource(Context c) {
        context = c.getApplicationContext();
    }

    static void start(Context c) {
        if (instance != null) return;
        instance = new ScreenShareSource(c);
        ScreenShareActivity.events = new ScreenShareActivity.Events() {
            @Override public void connected(String hostId, ScreenShareSession session) {
                instance.notifyChanged();
            }
            @Override public void ended(String hostId, String reason) {
                instance.ended(hostId);
            }
        };
        ScreenShareLoginActivity.outcome = new ScreenShareLoginActivity.Outcome() {
            @Override public void started(String hostId) {
                instance.loginDone(hostId);
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

    boolean running(String hostId) {
        ScreenShareSession s = ScreenShareSession.current();
        return s != null && s.host.id.equals(hostId) && ScreenShareActivity.isShowing(hostId);
    }

    /** Ends that Mac's session (the device's Disconnect, or the Mac removed). */
    void disconnect(String hostId) {
        ScreenShareActivity.finishFor(hostId);
        ScreenShareSession s = ScreenShareSession.current();
        if (s != null && s.host.id.equals(hostId)) s.close();
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
            result.shown(key, input(id));
            return;
        }
        Object[] old = pending.put(id, new Object[] { key, displayId, result });
        if (old != null) ((Result) old[2]).failed((String) old[0], context.getString(R.string.xd_ss_cancelled));
        Intent i = new Intent(context, ScreenShareLoginActivity.class);
        i.putExtra(ScreenShareLoginActivity.EXTRA_HOST, id);
        i.putExtra(ScreenShareLoginActivity.EXTRA_DISPLAY, displayId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    private void loginDone(String hostId) {
        Object[] p = pending.remove(hostId);
        Contents.Host host = Contents.host();
        if (host != null) host.freeDisplay();
        if (p != null) ((Result) p[2]).shown((String) p[0], input(hostId));
        notifyChanged();
    }

    private void loginCancelled(String hostId) {
        Object[] p = pending.remove(hostId);
        if (p != null) ((Result) p[2]).failed((String) p[0], context.getString(R.string.xd_ss_cancelled));
    }

    private void ended(String hostId) {
        ScreenShareInput in = inputs.get(hostId);
        if (in != null) in.releaseAll();
        Contents.Host host = Contents.host();
        if (host != null) host.contentEnded(key(hostId));
        notifyChanged();
    }

    private ScreenShareInput input(String hostId) {
        ScreenShareInput in = inputs.get(hostId);
        if (in == null) {
            in = new ScreenShareInput(hostId);
            inputs.put(hostId, in);
        }
        return in;
    }

    @Override public void close(String key) {
        String id = hostOf(key);
        if (id != null) disconnect(id);
    }

    @Override public void hidden(String key) {
        String id = hostOf(key);
        if (id == null) return;
        ScreenShareInput in = inputs.get(id);
        if (in != null) in.releaseAll();
        disconnect(id);
    }

    @Override public void setListener(Runnable changed) {
        this.changed = changed;
    }
}
