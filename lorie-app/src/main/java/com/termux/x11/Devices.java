package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacActivity;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The devices of DEVICES.md: the phone (internal, always connected) and the external devices (Linux environments,
 * paired Macs; Windows and other Android devices later), each with a state and connect/disconnect. Keys of the
 * content sources keep their format; {@link #deviceOf} assigns every key to a device. Also keeps the launcher's
 * hidden entries and the devices the user disconnected.
 *
 * Built from what the sources already have in memory: nothing here connects, reads files or blocks. UI thread.
 */
final class Devices {
    /** xdesk's "apps only" variant of a session: X, xfwm4 and xfsettingsd, no panel or desktop. */
    static final String APPS_ONLY = "+apps";
    static final String PHONE = "phone", LINUX = "linux:", MAC = "mac:", TERMUX = "termux";
    private static final String PREFS = "xdesk_devices", PREF_HIDDEN = "hidden", PREF_OFF = "off", PREF_TAB = "tab",
        PREF_SWITCHER_TAB = "switcher_tab";
    /** A Linux connect that has not shown up in xdesk status by then is no longer "connecting". */
    private static final long LINUX_CONNECT_MS = 45_000;

    enum Type { PHONE, LINUX, MAC, WINDOWS, ANDROID }

    enum State { NOT_SET_UP, DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    static final class Device {
        final String id;
        final Type type;
        final String name;
        final State state;
        /** A short user-facing line about the state (a failure, "2 windows"), or null. */
        final String note;
        /** Connect or disconnect can't be pressed now (a stop is under way). */
        final boolean busy;

        Device(String id, Type type, String name, State state, String note, boolean busy) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.state = state;
            this.note = note;
            this.busy = busy;
        }

        boolean external() {
            return type != Type.PHONE;
        }

        /** A Linux environment without a desktop session has nothing to start: its programs start X themselves. */
        boolean canConnect(Devices d) {
            if (type == Type.MAC) return true;
            if (type == Type.LINUX) return !d.sessionsOf(id).isEmpty();
            return false;
        }
    }

    /** What only XDeskActivity can do for a Linux environment. All calls on the UI thread. */
    interface Actions {
        /** Start (or reattach) this desktop session, as tapping its row in the launcher. */
        void startLinux(String session);
        /** Ask, then end the X session with every Linux program (xdesk stop). */
        void stopLinux();
        /** Termux is missing, has no permission or no sessions: what the launcher's Termux hint does. */
        void setUpLinux();
    }

    private static Devices instance;

    static synchronized Devices get(Context c) {
        if (instance == null) instance = new Devices(c.getApplicationContext());
        return instance;
    }

    /** For MacSource: the user disconnected this Mac, so looking at the launcher must not connect it. */
    static boolean userDisconnectedMac(String macId) {
        Devices d = instance;
        return d != null && d.off.contains(MAC + macId);
    }

    /** For MacSource.open: opening something of a device connects it (DEVICES.md §2). */
    static void userOpensMac(String macId) {
        Devices d = instance;
        if (d != null) d.ui.post(() -> d.setOff(MAC + macId, false));
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> hidden = new LinkedHashSet<>(), off = new LinkedHashSet<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private Actions actions;

    private List<String> sessions = new ArrayList<>();
    private String defaultSession, runningSession, stoppingSession, termuxHint;
    private boolean xRunning;
    /** The Linux device being connected and since when (uptime), or null. */
    private String linuxConnecting;
    private long linuxConnectingSince;

    private Devices(Context c) {
        context = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hidden.addAll(lines(prefs.getString(PREF_HIDDEN, "")));
        off.addAll(lines(prefs.getString(PREF_OFF, "")));
    }

    private static List<String> lines(String s) {
        if (s == null || s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    void setActions(Actions a) {
        actions = a;
    }

    Actions actions() {
        return actions;
    }

    void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Something the devices are made of changed elsewhere (a Mac paired or removed): tell the views. */
    void touch() {
        changed();
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    // --- what the launcher hears about Termux (ChooserView.setTermux, setStopping) ---

    void setLinux(List<String> sessionNames, String defaultName, String running, boolean x, String hint) {
        sessions = new ArrayList<>(sessionNames);
        defaultSession = defaultName;
        runningSession = running;
        xRunning = x;
        termuxHint = hint;
        if (linuxConnecting != null && running != null && linuxConnecting.equals(deviceOfSession(running))) linuxConnecting = null;
        changed();
    }

    void setLinuxStopping(String name) {
        if (Objects.equals(name, stoppingSession)) return;
        stoppingSession = name;
        changed();
    }

    List<String> sessions() { return sessions; }
    String defaultSession() { return defaultSession; }
    String runningSession() { return runningSession; }
    String stoppingSession() { return stoppingSession; }
    String termuxHint() { return termuxHint; }
    boolean xRunning() { return xRunning; }

    // --- keys to devices ---

    static String deviceOfSession(String session) {
        if (session == null) return LINUX + TERMUX;
        if (session.endsWith(APPS_ONLY)) session = session.substring(0, session.length() - APPS_ONLY.length());
        return session.equals(TERMUX) || session.startsWith(TERMUX + "-") ? LINUX + TERMUX : LINUX + session;
    }

    /** The desktop sessions of a Linux device, in xdesk's order. */
    List<String> sessionsOf(String deviceId) {
        ArrayList<String> out = new ArrayList<>();
        for (String s : sessions) if (deviceOfSession(s).equals(deviceId)) out.add(s);
        return out;
    }

    /** The device a key of the chooser, the docks or the recents belongs to. Never null. */
    String deviceOf(String key) {
        if (key == null || key.startsWith("a:")) return PHONE;
        if (key.startsWith("t:")) return deviceOfSession(key.substring(2));
        if (key.startsWith(LinuxSource.PREFIX)) {
            LinuxSource l = LinuxSource.get();
            String scope = l != null ? l.scopeOf(key) : null;
            if (scope != null) return LINUX + scope;
            // A window no program claims: the running session's environment, else Termux, which runs X
            return deviceOfSession(runningSession);
        }
        if (key.startsWith(MacSource.PREFIX)) {
            int end = key.indexOf(':', MacSource.PREFIX.length());
            return MAC + (end > 0 ? key.substring(MacSource.PREFIX.length(), end) : "");
        }
        return PHONE;
    }

    // --- the list ---

    /** The phone first, then the Linux environments (Termux first), then the Macs in pairing order. */
    List<Device> all() {
        ArrayList<Device> out = new ArrayList<>();
        out.add(new Device(PHONE, Type.PHONE, context.getString(R.string.xd_dev_phone), State.CONNECTED, null, false));
        for (String id : linuxIds()) out.add(linux(id));
        for (MacClient m : MacClients.all(context)) out.add(mac(m));
        return out;
    }

    List<Device> external() {
        List<Device> all = all();
        return all.subList(1, all.size());
    }

    Device find(String id) {
        for (Device d : all()) if (d.id.equals(id)) return d;
        return null;
    }

    int connectedCount() {
        int n = 0;
        for (Device d : external()) if (d.state == State.CONNECTED) n++;
        return n;
    }

    private List<String> linuxIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(LINUX + TERMUX);
        for (String s : sessions) ids.add(deviceOfSession(s));
        LinuxSource l = LinuxSource.get();
        if (l != null) {
            for (ContentSource.Entry e : l.apps()) {
                String scope = l.scopeOf(e.key);
                if (scope != null) ids.add(LINUX + scope);
            }
        }
        return new ArrayList<>(ids);
    }

    static String linuxName(Context c, String deviceId) {
        String scope = deviceId.substring(LINUX.length());
        if (scope.equals(TERMUX)) return "Termux";
        // proot-<distro>-<desktop>, as xdesk setup names them
        if (scope.startsWith("proot-")) {
            String rest = scope.substring(6);
            int dash = rest.lastIndexOf('-');
            String distro = dash > 0 ? rest.substring(0, dash) : rest;
            if (!distro.isEmpty()) distro = Character.toUpperCase(distro.charAt(0)) + distro.substring(1);
            return c.getString(R.string.xd_dev_proot_name, distro);
        }
        return scope;
    }

    private Device linux(String id) {
        String name = linuxName(context, id);
        boolean termux = id.equals(LINUX + TERMUX);
        int windows = 0;
        LinuxSource l = LinuxSource.get();
        if (l != null) for (ContentSource.Entry e : l.windows()) if (deviceOf(e.key).equals(id)) windows++;
        boolean running = runningSession != null && deviceOfSession(runningSession).equals(id);
        boolean stopping = stoppingSession != null;
        if (linuxConnecting != null && SystemClock.uptimeMillis() - linuxConnectingSince > LINUX_CONNECT_MS) linuxConnecting = null;
        if (stopping && (running || termux || windows > 0)) {
            return new Device(id, Type.LINUX, name, State.CONNECTED, context.getString(R.string.xd_chooser_stopping), true);
        }
        if (running || windows > 0 || (termux && xRunning)) {
            String note = windows > 0 ? context.getResources().getQuantityString(R.plurals.xd_dev_windows, windows, windows)
                    : running ? (runningSession.endsWith(APPS_ONLY) ? context.getString(R.string.xd_dev_apps_only)
                        : context.getString(R.string.xd_dev_whole_desktop) + " · " + runningSession)
                    : context.getString(R.string.xd_dev_x_running);
            return new Device(id, Type.LINUX, name, State.CONNECTED, note, false);
        }
        if (id.equals(linuxConnecting)) return new Device(id, Type.LINUX, name, State.CONNECTING, null, false);
        if (termux && termuxHint != null && sessions.isEmpty()) return new Device(id, Type.LINUX, name, State.NOT_SET_UP, termuxHint, false);
        String note = sessionsOf(id).isEmpty() ? context.getString(R.string.xd_dev_linux_no_session) : null;
        return new Device(id, Type.LINUX, name, State.DISCONNECTED, note, false);
    }

    private Device mac(MacClient m) {
        String id = MAC + m.mac().id;
        if (m.needsPairing())
            return new Device(id, Type.MAC, m.name(), State.FAILED, m.failure() != null ? m.failure() : context.getString(R.string.xd_dev_pair_again), false);
        MacClient.State s = m.state();
        if (off.contains(id) && s != MacClient.State.CONNECTED && s != MacClient.State.CONNECTING)
            return new Device(id, Type.MAC, m.name(), State.DISCONNECTED, null, false);
        switch (s) {
            case CONNECTED:
                return new Device(id, Type.MAC, m.name(), State.CONNECTED, m.locked() ? context.getString(R.string.xd_mac_locked_short) : null, false);
            case CONNECTING:
                return new Device(id, Type.MAC, m.name(), State.CONNECTING, null, false);
            case FAILED:
                return new Device(id, Type.MAC, m.name(), State.FAILED, m.failure(), false);
            default:
                return new Device(id, Type.MAC, m.name(), State.DISCONNECTED, m.failure(), false);
        }
    }

    // --- connect and disconnect (DEVICES.md §2) ---

    void connect(String id) {
        if (id.startsWith(MAC)) {
            setOff(id, false);
            MacClient m = MacClients.get(context, id.substring(MAC.length()));
            if (m != null) m.touch();
        } else if (id.startsWith(LINUX)) {
            Device d = find(id);
            if (d != null && d.state == State.NOT_SET_UP) {
                if (actions != null) actions.setUpLinux();
                return;
            }
            List<String> own = sessionsOf(id);
            if (own.isEmpty() || actions == null) return;
            String session = own.contains(defaultSession) ? defaultSession : own.get(0);
            linuxConnecting = id;
            linuxConnectingSince = SystemClock.uptimeMillis();
            // Connecting is X with only the window manager: the whole desktop is the 整个桌面 entry (DEVICES.md §2)
            actions.startLinux(session + APPS_ONLY);
        }
        changed();
    }

    void disconnect(String id) {
        if (id.startsWith(MAC)) {
            String macId = id.substring(MAC.length());
            setOff(id, true);
            // Its picture leaves the screen first: the host then puts back what was there before
            MacActivity.failedFor(macId, null);
            MacClient m = MacClients.get(context, macId);
            if (m != null) m.shutdown();
        } else if (id.startsWith(LINUX)) {
            linuxConnecting = null;
            if (actions != null) actions.stopLinux();
        }
        changed();
    }

    private void setOff(String id, boolean on) {
        if (on ? !off.add(id) : !off.remove(id)) return;
        prefs.edit().putString(PREF_OFF, String.join("\n", off)).apply();
    }

    // --- hidden entries and the tab ---

    boolean isHidden(String key) {
        return hidden.contains(key);
    }

    void setHidden(String key, boolean hide) {
        if (hide ? !hidden.add(key) : !hidden.remove(key)) return;
        prefs.edit().putString(PREF_HIDDEN, String.join("\n", hidden)).apply();
        changed();
    }

    List<String> hiddenKeys() {
        return Collections.unmodifiableList(new ArrayList<>(hidden));
    }

    String tab() {
        return prefs.getString(PREF_TAB, null);
    }

    void setTab(String tab) {
        prefs.edit().putString(PREF_TAB, tab).apply();
    }

    /** The external screen's launcher (SwitcherOverlay) keeps its own last tab. */
    String switcherTab() {
        return prefs.getString(PREF_SWITCHER_TAB, null);
    }

    void setSwitcherTab(String tab) {
        prefs.edit().putString(PREF_SWITCHER_TAB, tab).apply();
    }

    /** A Mac that is connected but locked: its apps come only after the desktop is opened and unlocked. */
    static boolean isLocked(Context c, String deviceId) {
        if (!deviceId.startsWith(MAC)) return false;
        MacClient m = MacClients.get(c, deviceId.substring(MAC.length()));
        return m != null && m.state() == MacClient.State.CONNECTED && m.locked();
    }

    /**
     * Whether a device's windows and apps are listed: only once it is connected, the list is the real one then
     * (DEVICES.md §3). One that can't be connected (a Linux environment without a session) lists its programs.
     */
    boolean listsCatalog(Device d) {
        return !d.external() || d.state == State.CONNECTED || !d.canConnect(this);
    }

    /** A favourite of a device that isn't connected: drawn dim, opening it connects. */
    boolean offline(String key) {
        String id = deviceOf(key);
        if (id.equals(PHONE)) return false;
        Device d = find(id);
        return d != null && d.state != State.CONNECTED;
    }

    /** The state's colour for dots on tabs and rows. */
    static int color(State s) {
        switch (s) {
            case CONNECTED: return Style.OK;
            case CONNECTING: return Style.WARN;
            case FAILED: return Style.DANGER;
            default: return Style.SURFACE_RAISED;
        }
    }

    static int stateLabel(State s) {
        switch (s) {
            case NOT_SET_UP: return R.string.xd_dev_state_not_set_up;
            case CONNECTING: return R.string.xd_dev_state_connecting;
            case CONNECTED: return R.string.xd_dev_state_connected;
            case FAILED: return R.string.xd_dev_state_failed;
            default: return R.string.xd_dev_state_disconnected;
        }
    }
}
