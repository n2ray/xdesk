package com.termux.x11;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The X servers that have announced themselves (ACTION_START): XDesk shows one at a time and keeps the others
 * running, to be switched to (Termux's X and the built-in Linux's X at the same time). Before, a second X server was
 * told "already has an active X server connection" and gave up.
 *
 * Only for the window without a tag (XDesk's); tagged windows keep upstream's one server each. The sender's uid
 * tells the built-in Linux's X (this app's own uid, started by BuiltinLinux) from Termux's; it comes from Android
 * (Receiver, API 34+) or from the socket once connected (MainActivity.madeByXServer).
 */
public final class XServers {
    /** What XDesk is told. Main thread. */
    public interface Listener {
        /** An X server not seen before announced itself while another one is shown. */
        void started(IBinder server, int uid);
        /** The window now shows another X server (switchTo, or a server that went away and another took its place). */
        void shownChanged();
    }

    /** Any app could send ACTION_START on an older Android: a handful at most are kept. */
    private static final int MAX = 8;
    public static final int UNKNOWN_UID = -1;
    private static final Map<IBinder, Integer> known = new LinkedHashMap<>();
    private static IBinder preferred;
    private static IBinder shown;
    public static volatile Listener listener;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private XServers() { }

    static final int NEW = 1, KNOWN = 0, FULL = -1;

    /** @return NEW, KNOWN, or FULL (not kept: too many) */
    static synchronized int add(IBinder b, int uid) {
        if (known.containsKey(b)) {
            if (uid != UNKNOWN_UID) known.put(b, uid);
            return KNOWN;
        }
        dropDead();
        if (known.size() >= MAX) return FULL;
        known.put(b, uid);
        return NEW;
    }

    private static void dropDead() {
        known.keySet().removeIf(b -> !b.isBinderAlive());
        if (preferred != null && !preferred.isBinderAlive()) preferred = null;
    }

    static synchronized void remove(IBinder b) {
        known.remove(b);
        if (b == preferred) preferred = null;
    }

    /** The socket of a connection said who made it (MainActivity.madeByXServer). */
    static synchronized void setUid(IBinder b, int uid) {
        if (known.containsKey(b) || known.size() < MAX) known.put(b, uid);
    }

    public static synchronized int uid(IBinder b) {
        Integer u = b != null ? known.get(b) : null;
        return u != null ? u : UNKNOWN_UID;
    }

    /** The X servers that are still there, oldest first. */
    public static synchronized List<IBinder> all() {
        dropDead();
        return new ArrayList<>(known.keySet());
    }

    /** The newest live X server made by this uid, or null. */
    public static synchronized IBinder newest(int uid) {
        dropDead();
        IBinder found = null;
        for (Map.Entry<IBinder, Integer> e : known.entrySet()) if (e.getValue() == uid) found = e.getKey();
        return found;
    }

    /** The built-in Linux's X server: the only one this app starts itself. */
    public static IBinder builtin() {
        return newest(Process.myUid());
    }

    /** Whether this X server is the built-in Linux's. */
    public static boolean isBuiltin(IBinder b) {
        return b != null && uid(b) == Process.myUid();
    }

    /** The one the user chose last: taken back when it announces itself again after a reconnect. */
    static synchronized IBinder preferred() {
        return preferred;
    }

    /** The X server the window shows now, or null. */
    public static synchronized IBinder shown() {
        return shown != null && shown.isBinderAlive() ? shown : null;
    }

    static void setShown(IBinder b) {
        boolean changed;
        synchronized (XServers.class) {
            changed = b != shown;
            shown = b;
        }
        if (!changed) return;
        ui.post(() -> {
            Listener l = listener;
            if (l != null) l.shownChanged();
        });
    }

    static void announceStarted(IBinder b, int uid) {
        ui.post(() -> {
            Listener l = listener;
            if (l != null) l.started(b, uid);
        });
    }

    /**
     * Show this X server in XDesk's window, keeping the one shown now running. Main thread.
     * @return false when there is no window (it is then taken when the window connects next)
     */
    public static boolean switchTo(IBinder b) {
        if (b == null || !b.isBinderAlive()) return false;
        synchronized (XServers.class) {
            preferred = b;
        }
        MainActivity m = MainActivity.getInstance("");
        if (m == null) return false;
        m.switchServer(b);
        return true;
    }
}
