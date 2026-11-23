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
 * paired Macs), each with a state. A Mac is remote: it is connected and
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
    static final String SCREEN = "vnc:";
    /** XDesk's own Linux (BuiltinLinux): its device id is LINUX + BUILTIN, its programs' scope is the same word. */
    static final String BUILTIN = BuiltinApps.SCOPE;
    /** Its whole desktop as a session name ("t:@builtin" in the lists): xdesk's names never have an @. */
    static final String BUILTIN_SESSION = "@builtin";
    private static final String TERMUX_PACKAGE = "com.termux";
    /** What forgetGoneTermux did. */
    static final int TERMUX_SAME = 0, TERMUX_FORGOTTEN = 1, TERMUX_REMOVED = 2, TERMUX_REINSTALLED = 3;
    private static final String PREFS = "xdesk_devices", PREF_HIDDEN = "hidden", PREF_OFF = "off", PREF_BOARD_TAB = "board_tab",
        PREF_PHONE_BOARD_TAB = "phone_board_tab",
        PREF_LINUX = "linux", PREF_ENVS = "envs", PREF_LINUX_PHONE_IME = "linux_phone_ime",
        PREF_PHONE_IME_ON = "linux_phone_ime_on", PREF_PHONE_IME_OFF = "linux_phone_ime_off", PREF_TERMUX = "termux",
        PREF_TERMUX_AT = "termux_installed_at", PREF_SHOWN = "shown", PREF_SHOWN_FAVOURITES = "shown_favourites";
    /** An X started for a Linux device that has not shown up by then is no longer "starting". */
    private static final long LINUX_START_MS = 45_000;

    /** SCREEN: a Mac through macOS Screen Sharing (ScreenShareSource), no app on the Mac. */
    enum Type { PHONE, LINUX, MAC, SCREEN }

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
        /** Stop the built-in Linux, after asking. */
        void stopBuiltin();
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
    /** Hidden by default (hiddenByDefault), shown by the user. */
    private final LinkedHashSet<String> shown = new LinkedHashSet<>();
    /** The Linux environments added as devices, by name. Termux itself is always one and is not in here. */
    private final LinkedHashSet<String> addedLinux = new LinkedHashSet<>();
    /** The environments as xdesk envs last reported them; empty with an xdesk that has no such command. */
    private List<Env> reportedEnvs = new ArrayList<>();
    /** reportedEnvs, or the environments worked out from the session names. Termux first. */
    private List<Env> envs = new ArrayList<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private Actions actions;
    /**
     * The Linux environments (by name, Termux too) that chose the phone's input method for a physical keyboard, and
     * those that chose their own. An environment in neither follows the global setting.
     */
    private final LinkedHashSet<String> phoneImeOn = new LinkedHashSet<>(), phoneImeOff = new LinkedHashSet<>();
    /** An input method runs on the X side (xdesk status says so): X can compose what a physical keyboard types. */
    private boolean xIme;
    /** The remembered X side keyboard layout (xdesk status: "Keyboard layout: NAME"), null when X's own is used. */
    private String xLayout;

    private List<String> sessions = new ArrayList<>();
    private String defaultSession, runningSession, stoppingSession, termuxHint, termuxVersionHint;
    private boolean xRunning;
    /** The Linux device X is being started for and since when (uptime), or null. */
    private String linuxStarting;
    private long linuxStartingSince;

    private Devices(Context c) {
        context = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hidden.addAll(lines(prefs.getString(PREF_HIDDEN, "")));
        shown.addAll(lines(prefs.getString(PREF_SHOWN, "")));
        off.addAll(lines(prefs.getString(PREF_OFF, "")));
        addedLinux.addAll(lines(prefs.getString(PREF_LINUX, "")));
        loadPhoneIme();
        reportedEnvs = parseEnvs(lines(prefs.getString(PREF_ENVS, "")));
        rebuildEnvs();
        BuiltinLinux.get(c).addListener(this::changed);
    }

    /**
     * The per-environment keyboard choices. Before each environment could follow the global setting, only the ones
     * that chose the phone's input method were saved: one that equals the global value follows it from now on, one
     * that differs stays an explicit choice. Written once, so a later change of the global setting does not
     * reinterpret the old list.
     */
    private void loadPhoneIme() {
        if (prefs.contains(PREF_PHONE_IME_ON) || prefs.contains(PREF_PHONE_IME_OFF)) {
            phoneImeOn.addAll(lines(prefs.getString(PREF_PHONE_IME_ON, "")));
            phoneImeOff.addAll(lines(prefs.getString(PREF_PHONE_IME_OFF, "")));
            return;
        }
        boolean global = KeyboardSettings.keysViaIme(context);
        // In the old list: this environment chose the phone's input method. Keep that choice only where it differs
        // from the global setting; the others follow the global one from now on.
        for (String env : lines(prefs.getString(PREF_LINUX_PHONE_IME, "")))
            if (!global) phoneImeOn.add(env);
        savePhoneIme();
    }

    private void savePhoneIme() {
        prefs.edit().putString(PREF_PHONE_IME_ON, String.join("\n", phoneImeOn))
            .putString(PREF_PHONE_IME_OFF, String.join("\n", phoneImeOff))
            .remove(PREF_LINUX_PHONE_IME).apply();
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

    void setLinux(List<String> sessionNames, String defaultName, String running, boolean x, String hint,
            String versionHint) {
        sessions = new ArrayList<>(sessionNames);
        // The list is first shown from what was saved, without the default, until Termux answers: the one known stays,
        // or a Linux program opened in that moment would start the whole default desktop instead of apps only
        if (defaultName != null || !sessionNames.contains(defaultSession)) defaultSession = defaultName;
        runningSession = running;
        xRunning = x;
        termuxHint = hint;
        termuxVersionHint = versionHint;
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

    /** The remembered X side keyboard layout (INPUT.md §5.2), or null: X's own ("from X") or an xdesk without the line. */
    String xLayout() {
        return xLayout;
    }

    void setXLayout(String layout) {
        if (Objects.equals(layout, xLayout)) return;
        xLayout = layout;
        changed();
    }

    /**
     * This Linux device uses the phone's input method for a physical keyboard, whatever runs on the X side: its own
     * choice, else the global setting.
     */
    boolean linuxPhoneIme(String deviceId, boolean global) {
        if (deviceId == null || !deviceId.startsWith(LINUX)) return false;
        String env = deviceId.substring(LINUX.length());
        return phoneImeOn.contains(env) || !phoneImeOff.contains(env) && global;
    }

    /** This device's own choice: true the phone's input method, false its own, null follows the global setting. */
    Boolean linuxPhoneImeChoice(String deviceId) {
        if (deviceId == null || !deviceId.startsWith(LINUX)) return null;
        String env = deviceId.substring(LINUX.length());
        if (phoneImeOn.contains(env)) return Boolean.TRUE;
        if (phoneImeOff.contains(env)) return Boolean.FALSE;
        return null;
    }

    /** Set this device's own choice; null makes it follow the global setting again. */
    void setLinuxPhoneIme(String deviceId, Boolean on) {
        if (deviceId == null || !deviceId.startsWith(LINUX)) return;
        String env = deviceId.substring(LINUX.length());
        // Both sets, so the environment is never in both: | evaluates both removals
        boolean moved = on == null ? (phoneImeOn.remove(env) | phoneImeOff.remove(env))
            : on ? (phoneImeOn.add(env) | phoneImeOff.remove(env))
            : (phoneImeOff.add(env) | phoneImeOn.remove(env));
        if (!moved) return;
        savePhoneIme();
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
     * add or remove it like any other. The proot and chroot environments run inside Termux but are devices of their
     * own: removing Termux from XDesk does not remove them.
     */
    private boolean termuxAdded() {
        String p = prefs.getString(PREF_TERMUX, "");
        if (p.equals("on")) return true;
        if (p.equals("off")) return false;
        return !reportedEnvs.isEmpty() || !sessions.isEmpty() || xRunning || runningSession != null;
    }

    /** Whether the Termux device itself is shown: Termux is installed and added as a device. */
    boolean termuxShown() {
        return termuxInstalled() && termuxAdded();
    }

    /**
     * Whether any Linux device is shown: Termux is installed, and Termux itself or a proot/chroot environment was
     * added. Removing Termux hides only its own device: the proot and chroot environments run inside Termux and stay
     * until each is removed on its own page.
     */
    boolean linuxShown() {
        return termuxInstalled() && (termuxAdded() || !addedLinux.isEmpty());
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
    /** The Termux section's line about the xdesk command's version (CLI.md §1), or null when it matches. */
    String termuxVersionHint() { return termuxVersionHint; }
    boolean xRunning() { return xRunning; }

    // --- keys to devices ---

    String deviceOfSession(String session) {
        if (session == null) return LINUX + TERMUX;
        if (session.equals(BUILTIN_SESSION)) return LINUX + BUILTIN;
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
        if (key.startsWith(ScreenShareSource.PREFIX)) return SCREEN + ScreenShareSource.hostOf(key);
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
        // The built-in Linux first: XDesk's own, the one meant to be used most
        if (BuiltinLinux.get(context).installed()) out.add(builtin());
        for (String id : linuxIds()) out.add(linux(id));
        for (MacClient m : MacClients.all(context)) out.add(mac(m));
        for (com.termux.x11.screenshare.ScreenShareStore.Host h : com.termux.x11.screenshare.ScreenShareStore.all(context)) {
            ScreenShareSource src = ScreenShareSource.get();
            boolean on = src != null && src.running(h.id);
            out.add(new Device(SCREEN + h.id, Type.SCREEN, h.label(), on ? State.CONNECTED : State.DISCONNECTED,
                context.getString(R.string.xd_ss_entry_note), false));
        }
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

    /** The devices connected now: a Mac connected, a Linux environment whose desktop or programs run. */
    int connectedCount() {
        int n = 0;
        for (Device d : external()) if (d.state == State.CONNECTED) n++;
        return n;
    }

    /**
     * Termux (when it is a device), then the environments added as devices that xdesk still knows, in its order.
     * Without Termux installed there are none (Add device › Linux says how to install it); removing the Termux
     * device only takes Termux out, the proot and chroot devices stay.
     */
    private List<String> linuxIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (!termuxInstalled()) return new ArrayList<>(ids);
        if (termuxAdded()) ids.add(LINUX + TERMUX);
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
        phoneImeOn.clear();
        phoneImeOff.clear();
        reportedEnvs = new ArrayList<>();
        sessions = new ArrayList<>();
        defaultSession = runningSession = stoppingSession = termuxHint = termuxVersionHint = null;
        xRunning = false;
        linuxStarting = null;
        boolean anyHidden = hidden.removeIf(k -> deviceOf(k).startsWith(LINUX)) | shown.removeIf(k -> deviceOf(k).startsWith(LINUX));
        SharedPreferences.Editor ed = prefs.edit().remove(PREF_LINUX).remove(PREF_ENVS)
            .remove(PREF_LINUX_PHONE_IME).remove(PREF_PHONE_IME_ON).remove(PREF_PHONE_IME_OFF).putString(PREF_TERMUX, "off");
        if (anyHidden) ed.putString(PREF_HIDDEN, String.join("\n", hidden)).putString(PREF_SHOWN, String.join("\n", shown));
        String tab = boardTab(), phoneTab = phoneBoardTab();
        if (tab != null && tab.startsWith(LINUX)) ed.remove(PREF_BOARD_TAB).remove(PREF_OLD_TAB);
        if (phoneTab != null && phoneTab.startsWith(LINUX)) ed.remove(PREF_PHONE_BOARD_TAB);
        ed.apply();
        rebuildEnvs();
        changed();
        return !was ? TERMUX_FORGOTTEN : at == 0 ? TERMUX_REMOVED : TERMUX_REINSTALLED;
    }

    static String linuxName(Context c, String deviceId) {
        String scope = deviceId.substring(LINUX.length());
        if (scope.equals(TERMUX)) return "Termux";
        if (scope.equals(BUILTIN)) return c.getString(R.string.xd_bl_title);
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

    /** Termux's X has windows (the built-in Linux's X, when it is the one shown, is not Termux's). */
    private static boolean anyLinuxWindow(LinuxSource l) {
        return l != null && !l.showsBuiltin() && !l.windows().isEmpty();
    }

    /**
     * The built-in Linux: running or not, from BuiltinLinux. Its X server is its own, beside Termux's (XServers):
     * stopping it stops only it.
     */
    private Device builtin() {
        BuiltinLinux bl = BuiltinLinux.get(context);
        String id = LINUX + BUILTIN, name = context.getString(R.string.xd_bl_title);
        switch (bl.state()) {
            case RUNNING: {
                int windows = 0;
                LinuxSource l = LinuxSource.get();
                if (l != null && l.showsBuiltin()) windows = l.windows().size();
                // "Apps only" is not shown as anything: only what its programs run on
                String note = bl.wholeDesktop() ? context.getString(R.string.xd_dev_whole_desktop) : null;
                if (windows > 0) {
                    String w = context.getResources().getQuantityString(R.plurals.xd_dev_windows, windows, windows);
                    note = note != null ? note + " · " + w : w;
                }
                return new Device(id, Type.LINUX, name, State.CONNECTED, note, false, true);
            }
            case STARTING:
            case INSTALLING:
                return new Device(id, Type.LINUX, name, State.CONNECTING, bl.progress(), true);
            case STOPPING:
                return new Device(id, Type.LINUX, name, State.CONNECTED, context.getString(R.string.xd_chooser_stopping), true);
            default:
                return new Device(id, Type.LINUX, name, State.DISCONNECTED, context.getString(R.string.xd_bl_row_note), false);
        }
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
        if (id.equals(LINUX + BUILTIN)) {
            if (actions != null) actions.stopBuiltin();
            changed();
            return;
        }
        linuxStarting = null;
        if (actions != null) actions.stopLinux();
        changed();
    }

    void disconnect(String id) {
        if (id.startsWith(SCREEN)) {
            ScreenShareSource src = ScreenShareSource.get();
            if (src != null) src.disconnect(id.substring(SCREEN.length()));
            changed();
            return;
        }
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

    /**
     * Linux programs nobody opens on their own (DEVICES.md §1): settings dialogs, the desktop's about, log out, run and
     * app finder, the "preferred application" stand-ins for the real file manager, terminal and browser, and library
     * demos. By the .desktop id (the part of "l:a:SCOPE/ID" after the slash), the same in every environment. They
     * start out hidden; one the user shows stays shown ({@code shown}).
     */
    private static final String[] DEFAULT_HIDDEN_SUFFIXES = {
        "-settings", "-preferences", "-config", "configtool", "settings-manager", "settings-editor",
    };
    private static final java.util.Set<String> DEFAULT_HIDDEN_IDS = new java.util.HashSet<>(Arrays.asList(
        "xfce4-about", "xfce4-session-logout", "xfce4-run", "xfce4-appfinder",
        "xfce4-file-manager", "xfce4-terminal-emulator", "xfce4-web-browser", "xfce4-mail-reader",
        "panel-preferences", "org.xfce.panelprofiles",
        "org.gnome.vte.app.gtk3", "org.gnome.vte.app.gtk4", "yad-icon-browser",
        "thunar-bulk-rename", "gigolo"));

    static boolean hiddenByDefault(String key) {
        if (key == null || !key.startsWith(LinuxSource.APP)) return false;
        String id = key.substring(key.indexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (id.endsWith(".desktop")) id = id.substring(0, id.length() - ".desktop".length());
        if (DEFAULT_HIDDEN_IDS.contains(id)) return true;
        for (String s : DEFAULT_HIDDEN_SUFFIXES) if (id.endsWith(s)) return true;
        return false;
    }

    boolean isHidden(String key) {
        return hidden.contains(key) || hiddenByDefault(key) && !shown.contains(key);
    }

    void setHidden(String key, boolean hide) {
        if (hide == isHidden(key)) return;
        if (hide) {
            shown.remove(key);
            if (!hiddenByDefault(key)) hidden.add(key);
        } else {
            hidden.remove(key);
            if (hiddenByDefault(key)) shown.add(key);
        }
        saveHidden();
        changed();
    }

    /**
     * Once, on the first start with the default hidden programs: the favourites from before stay where they were (a
     * hidden program can't be a favourite, ItemCard), so those of them hidden by default are shown.
     */
    void keepFavouritesShown(List<String> favourites) {
        if (prefs.getBoolean(PREF_SHOWN_FAVOURITES, false)) return;
        for (String k : favourites) if (hiddenByDefault(k) && !hidden.contains(k)) shown.add(k);
        prefs.edit().putBoolean(PREF_SHOWN_FAVOURITES, true).apply();
        saveHidden();
    }

    private void saveHidden() {
        prefs.edit().putString(PREF_HIDDEN, String.join("\n", hidden)).putString(PREF_SHOWN, String.join("\n", shown)).apply();
    }

    /** The hidden entries the user chose (those hidden by default are not in it). */
    List<String> hiddenKeys() {
        return Collections.unmodifiableList(new ArrayList<>(hidden));
    }

    /** Up to the switchboard the phone's list and the external Launchpad each kept a tab: the phone's is taken over. */
    private static final String PREF_OLD_TAB = "tab", PREF_OLD_SWITCHER_TAB = "switcher_tab";

    /**
     * The external screen's switchboard tab (Switchboard.EXTERNAL). This is the key saved before the phone's and the
     * screen's tabs were split, so the screen's last tab survives the update. Null: never chosen.
     */
    String boardTab() {
        String tab = prefs.getString(PREF_BOARD_TAB, null);
        return tab != null ? tab : prefs.getString(PREF_OLD_TAB, null);
    }

    void setBoardTab(String tab) {
        prefs.edit().putString(PREF_BOARD_TAB, tab).remove(PREF_OLD_TAB).remove(PREF_OLD_SWITCHER_TAB).apply();
    }

    /** The phone's switchboard tab (Switchboard.PHONE), its own since 0.7.0. Null: the Switchboard takes the screen's once. */
    String phoneBoardTab() {
        return prefs.getString(PREF_PHONE_BOARD_TAB, null);
    }

    void setPhoneBoardTab(String tab) {
        prefs.edit().putString(PREF_PHONE_BOARD_TAB, tab).apply();
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
