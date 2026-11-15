package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
 * paired Macs; Windows and other Android devices later), each with a state. A Mac is remote: it is connected and
 * disconnected. A Linux environment is local, reached through the xdesk command: it has no connection, its programs
 * are always listed, it is running or not, and can be stopped. Keys of the content sources keep their format;
 * {@link #deviceOf} assigns every key to a device. Also keeps the launcher's hidden entries, the Macs the user
 * disconnected and the Linux environments added as devices.
 *
 * Built from what the sources already have in memory: nothing here connects, reads files or blocks. UI thread.
 */
final class Devices {
    /** xdesk's "apps only" variant of a session: X, xfwm4 and xfsettingsd, no panel or desktop. */
    static final String APPS_ONLY = "+apps";
    static final String PHONE = "phone", LINUX = "linux:", MAC = "mac:", TERMUX = "termux";
    private static final String TERMUX_PACKAGE = "com.termux";
    /** What forgetGoneTermux did. */
    static final int TERMUX_SAME = 0, TERMUX_FORGOTTEN = 1, TERMUX_REMOVED = 2, TERMUX_REINSTALLED = 3;
    private static final String PREFS = "xdesk_devices", PREF_HIDDEN = "hidden", PREF_OFF = "off", PREF_BOARD_TAB = "board_tab",
        PREF_LINUX = "linux", PREF_ENVS = "envs", PREF_LINUX_PHONE_IME = "linux_phone_ime", PREF_TERMUX = "termux",
        PREF_TERMUX_AT = "termux_installed_at";
    /** An X started for a Linux device that has not shown up by then is no longer "starting". */
    private static final long LINUX_START_MS = 45_000;

    enum Type { PHONE, LINUX, MAC, WINDOWS, ANDROID }

    /** For a Linux device CONNECTED reads "running", CONNECTING "starting" and DISCONNECTED "not running" (stateLabel). */
    enum State { NOT_SET_UP, DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    /** A Linux environment as xdesk envs reports it: Termux itself, or a proot or chroot system. */
    static final class Env {
        final String name, kind;
        /** Its programs can be listed and opened one by one. */
        final boolean apps;
        /** Its desktop sessions, in xdesk's order. */
        final List<String> sessions;

        Env(String name, String kind, boolean apps, List<String> sessions) {
            this.name = name;
            this.kind = kind;
            this.apps = apps;
            this.sessions = sessions;
        }
    }

    static final class Device {
        final String id;
        final Type type;
        final String name;
        final State state;
        /** A short user-facing line about the state (a failure, "2 windows"), or null. */
        final String note;
        /** Connect, disconnect or stop can't be pressed now (a stop is under way). */
        final boolean busy;
        /** A Linux device while the X server all of them share runs: "Stop" is offered. */
        final boolean canStop;

        Device(String id, Type type, String name, State state, String note, boolean busy) {
            this(id, type, name, state, note, busy, false);
        }

        Device(String id, Type type, String name, State state, String note, boolean busy, boolean canStop) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.state = state;
            this.note = note;
            this.busy = busy;
            this.canStop = canStop;
        }

        boolean external() {
            return type != Type.PHONE;
        }

        /** Only a remote device is connected. A Linux environment is local: opening something of it starts what it needs. */
        boolean canConnect(Devices d) {
            return type == Type.MAC;
        }
    }

    /** What only XDeskActivity can do for a Linux environment. All calls on the UI thread. */
    interface Actions {
        /** End the X server with every Linux program (xdesk stop), after asking if anything is open. */
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
    /** The Linux environments added as devices, by name. Termux itself is always one and is not in here. */
    private final LinkedHashSet<String> addedLinux = new LinkedHashSet<>();
    /** The environments as xdesk envs last reported them; empty with an xdesk that has no such command. */
    private List<Env> reportedEnvs = new ArrayList<>();
    /** reportedEnvs, or the environments worked out from the session names. Termux first. */
    private List<Env> envs = new ArrayList<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private Actions actions;
    /** The Linux environments (by name, Termux too) whose physical keyboard types with the phone's input method. */
    private final LinkedHashSet<String> linuxPhoneIme = new LinkedHashSet<>();
    /** An input method runs on the X side (xdesk status says so): X can compose what a physical keyboard types. */
    private boolean xIme;

    private List<String> sessions = new ArrayList<>();
    private String defaultSession, runningSession, stoppingSession, termuxHint;
    private boolean xRunning;
    /** The Linux device X is being started for and since when (uptime), or null. */
    private String linuxStarting;
    private long linuxStartingSince;

    private Devices(Context c) {
        context = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hidden.addAll(lines(prefs.getString(PREF_HIDDEN, "")));
        off.addAll(lines(prefs.getString(PREF_OFF, "")));
        addedLinux.addAll(lines(prefs.getString(PREF_LINUX, "")));
        linuxPhoneIme.addAll(lines(prefs.getString(PREF_LINUX_PHONE_IME, "")));
        reportedEnvs = parseEnvs(lines(prefs.getString(PREF_ENVS, "")));
        rebuildEnvs();
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
        // The list is first shown from what was saved, without the default, until Termux answers: the one known stays,
        // or a Linux program opened in that moment would start the whole default desktop instead of apps only
        if (defaultName != null || !sessionNames.contains(defaultSession)) defaultSession = defaultName;
        runningSession = running;
        xRunning = x;
        termuxHint = hint;
        rebuildEnvs();
        if (running != null) linuxStarting = null;
        changed();
    }

    /** XDesk itself started, switched to or ended a session: known before xdesk status is read again. */
    void setLinuxRunning(String running) {
        if (Objects.equals(running, runningSession)) return;
        runningSession = running;
        if (running != null) {
            xRunning = true;
            linuxStarting = null;
        } else {
            // Only an X server that ended is told here (the light X or a desktop, with everything on it)
            xRunning = false;
        }
        changed();
    }

    // --- the X side's input method (INPUT.md §5.2) ---

    /**
     * What xdesk status last said: an input method (fcitx5) runs on the X server all Linux environments share. Then a
     * physical keyboard's keys go to X as keys and it composes, its candidates on the external screen.
     */
    boolean xIme() {
        return xIme;
    }

    void setXIme(boolean runs) {
        if (runs == xIme) return;
        xIme = runs;
        changed();
    }

    /** This Linux device is set to the phone's input method for a physical keyboard, whatever runs on the X side. */
    boolean linuxPhoneIme(String deviceId) {
        return deviceId != null && deviceId.startsWith(LINUX) && linuxPhoneIme.contains(deviceId.substring(LINUX.length()));
    }

    void setLinuxPhoneIme(String deviceId, boolean on) {
        if (deviceId == null || !deviceId.startsWith(LINUX)) return;
        String env = deviceId.substring(LINUX.length());
        if (on ? !linuxPhoneIme.add(env) : !linuxPhoneIme.remove(env)) return;
        prefs.edit().putString(PREF_LINUX_PHONE_IME, String.join("\n", linuxPhoneIme)).apply();
        changed();
    }

    /** XDesk asked xdesk to start this session (the light X for a program, or a whole desktop): its device is "starting". */
    void setLinuxStarting(String session) {
        linuxStarting = session != null ? deviceOfSession(session) : null;
        linuxStartingSince = SystemClock.uptimeMillis();
        changed();
    }

    // --- the Linux environments (DEVICES.md §1, §5) ---

    /**
     * What "xdesk envs" printed, a line per environment: name, kind, "apps" or "-", its sessions joined by "/", tab
     * separated. Nothing (an xdesk without the command): the environments are worked out from the session names.
     * Call before {@link #setLinux}, which tells the views.
     */
    void setEnvironments(List<String> lines) {
        List<Env> parsed = parseEnvs(lines);
        if (!parsed.isEmpty() || !lines.isEmpty() || !reportedEnvs.isEmpty()) {
            ArrayList<String> keep = new ArrayList<>();
            for (String l : lines) if (l.indexOf('\t') > 0) keep.add(l);
            prefs.edit().putString(PREF_ENVS, String.join("\n", keep)).apply();
        }
        reportedEnvs = parsed;
        rebuildEnvs();
    }

    private static List<Env> parseEnvs(List<String> lines) {
        ArrayList<Env> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            if (f.length < 4 || f[0].isEmpty() || f[0].startsWith("#")) continue;
            ArrayList<String> s = new ArrayList<>();
            for (String n : f[3].split("/")) if (!n.isEmpty()) s.add(n);
            out.add(new Env(f[0], f[1], "apps".equals(f[2]), s));
        }
        return out;
    }

    private void rebuildEnvs() {
        if (!reportedEnvs.isEmpty()) {
            envs = reportedEnvs;
            return;
        }
        // An xdesk without "envs": Termux has the termux-* sessions, every other session is an environment of its own
        ArrayList<Env> out = new ArrayList<>();
        ArrayList<String> own = new ArrayList<>();
        for (String s : sessions) if (isTermuxName(s)) own.add(s);
        out.add(new Env(TERMUX, TERMUX, true, own));
        for (String s : sessions)
            if (!isTermuxName(s)) out.add(new Env(s, s.startsWith("proot-") ? "proot" : "other", true, Collections.singletonList(s)));
        envs = out;
    }

    private static boolean isTermuxName(String session) {
        return session.equals(TERMUX) || session.startsWith(TERMUX + "-");
    }

    /** Every environment xdesk knows, Termux first: the ones to pick from in Add device › Linux. */
    List<Env> environments() {
        return envs;
    }

    Env environment(String deviceId) {
        String name = deviceId.startsWith(LINUX) ? deviceId.substring(LINUX.length()) : deviceId;
        for (Env e : envs) if (e.name.equals(name)) return e;
        return null;
    }

    /** Whether this environment is a device (a tab of its own). */
    boolean isLinuxAdded(String env) {
        return env.equals(TERMUX) ? termuxAdded() : addedLinux.contains(env);
    }

    /**
     * Termux is a device like the others, added when wanted: a first start of XDesk has the phone's apps only. It is
     * there by itself once xdesk has answered (it is set up: an update, or Termux was added before), and the user can
     * add or remove it like any other. The other environments live in Termux, so none of them is there without it.
     */
    private boolean termuxAdded() {
        String p = prefs.getString(PREF_TERMUX, "");
        if (p.equals("on")) return true;
        if (p.equals("off")) return false;
        return !reportedEnvs.isEmpty() || !sessions.isEmpty() || xRunning || runningSession != null;
    }

    /** Whether Linux devices are shown at all: Termux is installed and added. Keys of Linux items are gone otherwise. */
    boolean linuxShown() {
        return termuxAdded() && termuxInstalled();
    }

    /** Add device › Linux › an environment, or its removal. Nothing of the environment itself is touched. */
    void setLinuxAdded(String env, boolean add) {
        if (env.equals(TERMUX)) {
            if (add == termuxAdded() && prefs.contains(PREF_TERMUX)) return;
            prefs.edit().putString(PREF_TERMUX, add ? "on" : "off").apply();
            changed();
            return;
        }
        if (add ? !addedLinux.add(env) : !addedLinux.remove(env)) return;
        SharedPreferences.Editor ed = prefs.edit().putString(PREF_LINUX, String.join("\n", addedLinux));
        // A proot or chroot needs Termux, so adding one adds Termux too
        if (add && !termuxAdded()) ed.putString(PREF_TERMUX, "on");
        ed.apply();
        changed();
    }

    /**
     * The session whose apps-only variant ("NAME+apps": X, the window manager, no desktop) is started to open a
     * program of this device when no X runs: the default session if it is this environment's, else its first, else
     * the environment's name alone ("termux+apps" needs no session).
     */
    String appsSession(String deviceId) {
        List<String> own = sessionsOf(deviceId);
        String base = own.contains(defaultSession) ? defaultSession : !own.isEmpty() ? own.get(0)
                : deviceId.startsWith(LINUX) ? deviceId.substring(LINUX.length()) : TERMUX;
        return base + APPS_ONLY;
    }

    /** The session all Linux devices share is only X and a window manager, no desktop. */
    boolean appsOnlyRunning() {
        return runningSession != null && runningSession.endsWith(APPS_ONLY);
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

    String deviceOfSession(String session) {
        if (session == null) return LINUX + TERMUX;
        if (session.endsWith(APPS_ONLY)) session = session.substring(0, session.length() - APPS_ONLY.length());
        for (Env e : envs) if (e.sessions.contains(session)) return LINUX + e.name;
        return isTermuxName(session) ? LINUX + TERMUX : LINUX + session;
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
            // A window no program claims: the environment whose whole desktop runs (started from its panel or a
            // terminal there), else Termux, which runs X
            return deviceOfSession(appsOnlyRunning() ? null : runningSession);
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

    /** The remote devices connected now. A local Linux environment is running or not, never "connected". */
    int connectedCount() {
        int n = 0;
        for (Device d : external()) if (d.type != Type.LINUX && d.state == State.CONNECTED) n++;
        return n;
    }

    /**
     * Termux, then the environments added as devices that xdesk still knows, in its order. None without Termux installed
     * and added (Add device › Linux says how to install it), so someone who only uses Android apps and Macs has no Linux tabs.
     */
    private List<String> linuxIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (!linuxShown()) return new ArrayList<>(ids);
        ids.add(LINUX + TERMUX);
        for (Env e : envs) if (addedLinux.contains(e.name)) ids.add(LINUX + e.name);
        return new ArrayList<>(ids);
    }

    /** A quick question to the package manager, no I/O of our own. Asked on every rebuild: installing Termux shows up. */
    private boolean termuxInstalled() {
        return termuxInstallTime() != 0;
    }

    /** When this Termux was installed (an update keeps it, a new install changes it), 0 when there is none. */
    private long termuxInstallTime() {
        try {
            return context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * Termux removed, or installed anew, since the last look: what XDesk knew of it is forgotten here (its
     * environments, sessions, added devices, hidden entries), and the caller forgets its part too (favourites,
     * recents, the program list). A new Termux need not have any of the old programs. The device goes as well: only
     * Add device brings it back.
     *
     * @return TERMUX_SAME, TERMUX_FORGOTTEN (it was not a device: nothing to tell), or TERMUX_REMOVED /
     *     TERMUX_REINSTALLED, which the user is told
     */
    int forgetGoneTermux() {
        long at = termuxInstallTime(), saved = prefs.getLong(PREF_TERMUX_AT, -1);
        if (at == saved) return TERMUX_SAME;
        prefs.edit().putLong(PREF_TERMUX_AT, at).apply();
        // The first look on this version with Termux there: nothing to say it changed
        if (saved == -1 && at != 0) return TERMUX_SAME;
        boolean was = saved > 0 && termuxAdded();
        addedLinux.clear();
        linuxPhoneIme.clear();
        reportedEnvs = new ArrayList<>();
        sessions = new ArrayList<>();
        defaultSession = runningSession = stoppingSession = termuxHint = null;
        xRunning = false;
        linuxStarting = null;
        boolean anyHidden = hidden.removeIf(k -> deviceOf(k).startsWith(LINUX));
        SharedPreferences.Editor ed = prefs.edit().remove(PREF_LINUX).remove(PREF_ENVS).remove(PREF_LINUX_PHONE_IME).putString(PREF_TERMUX, "off");
        if (anyHidden) ed.putString(PREF_HIDDEN, String.join("\n", hidden));
        String tab = boardTab();
        if (tab != null && tab.startsWith(LINUX)) ed.remove(PREF_BOARD_TAB).remove(PREF_OLD_TAB);
        ed.apply();
        rebuildEnvs();
        changed();
        return !was ? TERMUX_FORGOTTEN : at == 0 ? TERMUX_REMOVED : TERMUX_REINSTALLED;
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
        if (linuxStarting != null && SystemClock.uptimeMillis() - linuxStartingSince > LINUX_START_MS) linuxStarting = null;
        // One X server for all of them (DEVICES.md §2): while it runs every Linux device can stop it
        boolean x = xRunning || runningSession != null || anyLinuxWindow(l);
        if (stopping && (running || termux || windows > 0)) {
            return new Device(id, Type.LINUX, name, State.CONNECTED, context.getString(R.string.xd_chooser_stopping), true);
        }
        // Running: its whole desktop is the session, or programs of it are open. The apps-only session itself is not
        // shown as anything: it is only what the programs run on
        boolean desktop = running && !runningSession.endsWith(APPS_ONLY);
        if (desktop || windows > 0) {
            String note = desktop ? context.getString(R.string.xd_dev_whole_desktop) : null;
            if (windows > 0) {
                String w = context.getResources().getQuantityString(R.plurals.xd_dev_windows, windows, windows);
                note = note != null ? note + " · " + w : w;
            }
            return new Device(id, Type.LINUX, name, State.CONNECTED, note, false, true);
        }
        if (id.equals(linuxStarting)) return new Device(id, Type.LINUX, name, State.CONNECTING, null, false, x);
        if (termux && termuxHint != null && sessions.isEmpty()) return new Device(id, Type.LINUX, name, State.NOT_SET_UP, termuxHint, false, x);
        // X runs with nothing of this environment on it (idle after its last window closed, or busy with another's)
        return new Device(id, Type.LINUX, name, State.DISCONNECTED, x ? context.getString(R.string.xd_dev_x_running) : null, stopping, x && !stopping);
    }

    private static boolean anyLinuxWindow(LinuxSource l) {
        return l != null && !l.windows().isEmpty();
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
        }
        // A Linux environment has no connection: opening a program or its whole desktop starts what is needed
        changed();
    }

    /** A Linux device's "Stop": ends the X server all Linux devices share, with everything on it. XDeskActivity asks first. */
    void stop(String id) {
        if (!id.startsWith(LINUX)) return;
        linuxStarting = null;
        if (actions != null) actions.stopLinux();
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

    /** Up to the switchboard the phone's list and the external Launchpad each kept a tab: the phone's is taken over. */
    private static final String PREF_OLD_TAB = "tab", PREF_OLD_SWITCHER_TAB = "switcher_tab";

    /** The switchboard's tab (Switchboard), one for the phone and the external screen. Null: never chosen. */
    String boardTab() {
        String tab = prefs.getString(PREF_BOARD_TAB, null);
        return tab != null ? tab : prefs.getString(PREF_OLD_TAB, null);
    }

    void setBoardTab(String tab) {
        prefs.edit().putString(PREF_BOARD_TAB, tab).remove(PREF_OLD_TAB).remove(PREF_OLD_SWITCHER_TAB).apply();
    }

    /** A Mac that is connected but locked: its apps come only after the desktop is opened and unlocked. */
    static boolean isLocked(Context c, String deviceId) {
        if (!deviceId.startsWith(MAC)) return false;
        MacClient m = MacClients.get(c, deviceId.substring(MAC.length()));
        return m != null && m.state() == MacClient.State.CONNECTED && m.locked();
    }

    /**
     * Whether a device's windows and apps are listed: a remote one (a Mac) only once it is connected, the list is the
     * real one then (DEVICES.md §3). The phone and the local Linux environments always: their programs are known
     * whether or not X runs.
     */
    boolean listsCatalog(Device d) {
        return !d.external() || d.state == State.CONNECTED || !d.canConnect(this);
    }

    /** A favourite of a remote device that isn't connected: drawn dim, opening it connects. */
    boolean offline(String key) {
        String id = deviceOf(key);
        if (id.equals(PHONE) || id.startsWith(LINUX)) return false;
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

    /** The state in words: a Linux environment is running or not, anything else connected or not. */
    static int stateLabel(Device d) {
        if (d.type != Type.LINUX) return stateLabel(d.state);
        switch (d.state) {
            case NOT_SET_UP: return R.string.xd_dev_state_not_set_up;
            case CONNECTING: return R.string.xd_dev_state_starting;
            case CONNECTED: return R.string.xd_dev_state_running;
            default: return R.string.xd_dev_state_not_running;
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
