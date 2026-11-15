package com.termux.x11;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.Toast;

import com.termux.x11.app.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The switchboard (DEVICES.md §3): the one thing the phone's launcher, the external screen's home, the Launchpad
 * opened over a content and the Docks all are, shown in different places. This is its model, and the views only draw
 * it: ChooserView as a list on the phone, SwitcherOverlay as a grid of icons on the external screen, ExternalDock and
 * DockBar as its short strip. So an item has the same label, the same state words and the same place everywhere.
 *
 * It owns: the tabs (running, favourites, the phone, every device in Devices' order) and which one is chosen, the
 * search text, whether the hidden fold is open, the rows of the chosen tab in order (with the section titles the
 * list shows), every item's presentation, the device bar of the tab (state and the actions there are), the strip,
 * and telling the views when any of it changed. The tab and the search are one for both screens: changed on one, the
 * other follows while it shows.
 *
 * Built like Devices from what is in memory already (Devices, the content sources, AppCatalog, the host's
 * favourites): nothing here connects, reads files or blocks. The rows are worked out once per change, whoever asks.
 * UI thread.
 */
final class Switchboard {
    static final String TAB_RUNNING = "run", TAB_FAVOURITES = "fav";
    /** What marks the item on the external screen, on every surface: this behind it, and its state line in ACCENT_TEXT. */
    static final int CURRENT_TINT = 0x553D7EF5, ACCENT_TEXT = 0xFF8FB6FF;

    /** What only the phone's window knows and can do (XDeskActivity). */
    interface Host {
        /** The favourites in the user's order. */
        List<String> favourites();
        /** The key on the external screen now, or null. */
        String current();
        /** The one before it, or null. */
        String previous();
        /** Keys of what is running, the current one first. */
        List<String> running();
        boolean onCar();
        /** Open an item (or, for {@link ExternalLauncher#SWITCHER}, the Launchpad). */
        void open(String key);
        /** The item's card, a dialog on the phone (ItemCard). False when the key has none. */
        boolean card(String key);
        /** The phone's window, for what a device action shows there (a confirm, a settings page). */
        Context phone();
        /** The hint row of Termux was chosen: ask for its permission, or read it again. */
        void termuxHint();
        /** Only be the display of an X server the user runs himself. */
        void attachX();
    }

    static final class Tab {
        final String id, name;
        /** The dot's state, null for a tab without one (running, favourites, the phone). */
        final Devices.State state;

        Tab(String id, String name, Devices.State state) {
            this.id = id;
            this.name = name;
            this.state = state;
        }
    }

    /** A row is an item (it has a key and can be opened), a section title, the hidden fold's title, or a line of text. */
    static final int ITEM = 0, HEADER = 1, FOLD = 2, NOTE = 3;
    /** An item's place in the strip. */
    static final int STRIP_CURRENT = 1, STRIP_PREVIOUS = 2, STRIP_FAVOURITE = 3, STRIP_ALL = 4;

    static final class Item {
        int kind;
        /** Null for anything that is not an item. */
        String key;
        String label;
        /** The line under the label: what it is, where, and its state in words. May be null. */
        String detail;
        /** The label where there is one line only (the Dock): a whole desktop says whose. */
        String title;
        /** The icon: an Android app's (AppCatalog draws it), a content's picture, or neither (a letter, a glyph). */
        AppCatalog.App app;
        Bitmap bitmap;
        /** An entry of a content source (a Linux program or window, something of a Mac). */
        boolean content;
        boolean running, current, favourite, hidden;
        /** A favourite of a device that isn't connected: dim, opening it connects. */
        boolean dim;
        /** A whole desktop: drawn apart from the apps. */
        boolean desktop;
        /** A Linux desktop session: it has a running dot in the list. */
        boolean session;
        /** Its letter in the A–Z part of a device's list, 0 outside it. */
        char initial;
        /** FOLD: how many are hidden. */
        int count;
        /** STRIP_*, in the strip only. */
        int strip;
        /** What choosing a row without a key does (the fold, a hint); null for none. */
        Runnable action;

        /** What it is and its state, before they are joined into {@link #detail}. */
        private String sub, state, where;
        private String search;
    }

    /** Something a device bar offers. */
    static final class Action {
        final String label;
        final boolean enabled;
        /** It goes on on the phone (a confirm, a page there): the external screen says so. */
        final boolean onPhone;
        private final Runnable run;

        Action(String label, boolean enabled, boolean onPhone, Runnable run) {
            this.label = label;
            this.enabled = enabled;
            this.onPhone = onPhone;
            this.run = run;
        }

        void run() {
            if (enabled && run != null) run.run();
        }
    }

    /** The line under the tabs: a device's state and what can be done with it, or a line about the tab. */
    static final class Bar {
        /** The dot's colour; 0 for none (not a device). */
        final int dot;
        final String text;
        final List<Action> actions;

        Bar(int dot, String text, List<Action> actions) {
            this.dot = dot;
            this.text = text;
            this.actions = actions;
        }
    }

    private static Switchboard instance;

    static synchronized Switchboard get(Context c) {
        if (instance == null) instance = new Switchboard(c.getApplicationContext());
        return instance;
    }

    private final Context c;
    private final Devices devices;
    private final Collator collator = Collator.getInstance();
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private final Runnable changed = this::changed;
    private Host host;
    private String tab, query = "";
    private boolean hiddenOpen;
    /** The rows as last worked out; null after a change. */
    private List<Item> rows;
    /** What the host said when items were last made (look): asked once, not for each of some hundred apps. */
    private HashSet<String> favourites = new HashSet<>();
    private String current;
    private boolean onCar;

    private Switchboard(Context c) {
        this.c = c;
        devices = Devices.get(c);
        String saved = devices.boardTab();
        tab = saved != null ? saved : TAB_FAVOURITES;
        // For good: this lives as long as the process, as Devices does
        devices.addListener(changed);
    }

    void setHost(Host h) {
        host = h;
        changed();
    }

    Host host() {
        return host;
    }

    void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Something the rows are made of changed (the favourites, what is on the screen, the apps): tell the views. */
    void changed() {
        rows = null;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    private String str(int id) {
        return c.getString(id);
    }

    // --- the selection, one for both screens ---

    /**
     * The chosen tab: TAB_RUNNING, TAB_FAVOURITES or a device's id. Always one that exists: while the chosen device
     * is not there (a Mac removed; an environment xdesk has not reported yet) it is the favourites, and the device's
     * again when it is back.
     */
    String tab() {
        return TAB_RUNNING.equals(tab) || TAB_FAVOURITES.equals(tab) || devices.find(tab) != null ? tab : TAB_FAVOURITES;
    }

    void setTab(String id) {
        setTab(id, true);
    }

    /** @param remember false for the overview, which opens on the running tab without that becoming the last tab */
    void setTab(String id, boolean remember) {
        boolean same = id.equals(tab()) && query.isEmpty();
        if (remember) devices.setBoardTab(id);
        if (same) return;
        tab = id;
        query = "";
        hiddenOpen = false;
        changed();
    }

    void stepTab(int by) {
        List<Tab> all = tabs();
        int at = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(tab())) at = i;
        setTab(all.get((at + by + all.size()) % all.size()).id);
    }

    /** The search as typed. It looks through every device, whatever the tab; the tab's own device comes first. */
    String query() {
        return query;
    }

    void setQuery(String q) {
        if (q == null) q = "";
        if (q.equals(query)) return;
        query = q;
        changed();
    }

    boolean searching() {
        return !needle().isEmpty();
    }

    private String needle() {
        return query.trim().toLowerCase();
    }

    boolean hiddenOpen() {
        return hiddenOpen;
    }

    void setHiddenOpen(boolean open) {
        if (open == hiddenOpen) return;
        hiddenOpen = open;
        changed();
    }

    List<Tab> tabs() {
        ArrayList<Tab> out = new ArrayList<>();
        out.add(new Tab(TAB_RUNNING, str(R.string.xd_sw_running), null));
        out.add(new Tab(TAB_FAVOURITES, "★ " + str(R.string.xd_chooser_favourites), null));
        for (Devices.Device d : devices.all()) out.add(new Tab(d.id, d.name, d.external() ? d.state : null));
        return out;
    }

    // --- doing ---

    /** Opening anything ends the search: it was for finding this. */
    void open(String key) {
        if (host == null) {
            Toast.makeText(c, R.string.xd_launcher_need_phone, Toast.LENGTH_LONG).show();
            return;
        }
        if (!ExternalLauncher.SWITCHER.equals(key)) setQuery("");
        host.open(key);
    }

    boolean card(String key) {
        return host != null && key != null && host.card(key);
    }

    // --- the device bar ---

    /** Null when the tab has nothing to say. */
    Bar bar() {
        if (searching()) {
            int n = 0;
            for (Item i : items()) if (i.kind == ITEM) n++;
            return new Bar(0, c.getString(R.string.xd_sw_results, n), Collections.<Action>emptyList());
        }
        if (TAB_RUNNING.equals(tab())) return new Bar(0, str(R.string.xd_sw_running_note), Collections.<Action>emptyList());
        final Devices.Device d = devices.find(tab());
        if (d == null || !d.external()) return null;
        String state = str(Devices.stateLabel(d));
        // A locked Mac: how to get to its apps is what there is to say (opening the desktop is how it is unlocked)
        String text = Devices.isLocked(c, d.id) ? state + " · " + str(R.string.xd_dev_locked_hint)
            : d.note != null && !d.note.isEmpty() ? state + " · " + d.note : state;
        ArrayList<Action> actions = new ArrayList<>();
        if (d.type == Devices.Type.LINUX) {
            // Local, reached through the xdesk command: no connect or disconnect. Running or not, and a Stop for the
            // X server while it runs (DEVICES.md §2); XDeskActivity asks first if anything is open
            if (d.state == Devices.State.NOT_SET_UP) actions.add(setUp(d));
            else if (d.canStop) actions.add(new Action(str(R.string.xd_dev_stop), !d.busy, true, () -> devices.stop(d.id)));
        } else if (d.state == Devices.State.CONNECTED) {
            actions.add(new Action(str(R.string.xd_dev_disconnect), !d.busy, true, () -> {
                if (phone() != null) ItemCard.confirmDisconnect(phone(), d);
            }));
        } else if (d.state == Devices.State.CONNECTING) {
            actions.add(new Action(str(R.string.xd_dev_state_connecting), false, false, null));
        } else if (d.state == Devices.State.NOT_SET_UP) {
            actions.add(setUp(d));
        } else if (d.type == Devices.Type.MAC && d.state == Devices.State.FAILED && d.note != null && needsPairing(d)) {
            actions.add(new Action(str(R.string.xd_dev_pair_again), !d.busy, true, () -> {
                if (phone() != null) MacSettings.add(phone());
            }));
        } else if (d.canConnect(devices)) {
            actions.add(new Action(str(R.string.xd_dev_connect), !d.busy, false, () -> devices.connect(d.id)));
        }
        // The device's own page: a Linux environment's state, Stop and desktops, a Mac's keyboard and fit
        if (d.state != Devices.State.NOT_SET_UP) actions.add(new Action(str(R.string.xd_settings), true, true, () -> {
            if (phone() == null) return;
            if (d.type == Devices.Type.MAC) MacSettings.open(phone());
            else DeviceSettings.openDevice(phone(), d.id);
        }));
        return new Bar(Devices.color(d.state), text, actions);
    }

    private Action setUp(final Devices.Device d) {
        return new Action(str(R.string.xd_dev_set_up), !d.busy, true, () -> {
            if (phone() != null) DeviceSettings.setUp(phone(), d.type);
        });
    }

    private Context phone() {
        return host != null ? host.phone() : null;
    }

    private boolean needsPairing(Devices.Device d) {
        com.termux.x11.mac.MacClient m = com.termux.x11.mac.MacClients.get(c, d.id.substring(Devices.MAC.length()));
        return m != null && m.needsPairing();
    }

    // --- the rows ---

    /** The rows of the chosen tab, or of the search, in order. Not to be changed by the caller. */
    List<Item> items() {
        if (rows == null) rows = build();
        return rows;
    }

    private void look() {
        favourites = new HashSet<>(host != null ? host.favourites() : Collections.<String>emptyList());
        current = host != null ? host.current() : null;
        onCar = host != null && host.onCar();
    }

    private List<Item> build() {
        look();
        ArrayList<Item> out = new ArrayList<>(), hidden = new ArrayList<>();
        String q = needle(), id = tab();
        if (!q.isEmpty()) {
            // Every device, as Launchpad's search: the tab's own device first, then the others in their order
            List<Devices.Device> all = new ArrayList<>(devices.all());
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) all.add(0, all.remove(i));
            for (Devices.Device d : all) {
                ArrayList<Item> found = new ArrayList<>();
                deviceRows(d, q, found, hidden);
                if (found.isEmpty()) continue;
                out.add(header(d.name));
                out.addAll(found);
            }
        } else if (TAB_RUNNING.equals(id)) {
            if (host != null) for (String k : new LinkedHashSet<>(host.running())) {
                // An apps-only session is only what Linux programs run on, never an item
                if (k.startsWith("t:") && k.endsWith(Devices.APPS_ONLY)) continue;
                Item i = forKey(k);
                if (i != null) out.add(across(i, null));
            }
        } else if (TAB_FAVOURITES.equals(id)) {
            if (devices.termuxSuggested())
                out.add(note(str(R.string.xd_dev_termux_suggest), str(R.string.xd_dev_termux_suggest_note), () -> {
                    devices.setLinuxAdded(Devices.TERMUX, true);
                    if (host != null) host.termuxHint();   // the permission, then what Termux has
                }));
            favouriteRows(out);
        } else {
            Devices.Device d = devices.find(id);
            if (d != null) deviceRows(d, q, out, hidden);
        }
        if (!hidden.isEmpty()) {
            boolean open = hiddenOpen || !q.isEmpty();
            Item fold = new Item();
            fold.kind = FOLD;
            fold.label = (open ? "▾ " : "▸ ") + c.getString(R.string.xd_dev_hidden, hidden.size());
            fold.count = hidden.size();
            fold.action = () -> setHiddenOpen(!hiddenOpen);
            out.add(fold);
            if (open) {
                sort(hidden);
                out.addAll(hidden);
            }
        }
        if (out.isEmpty()) {
            out.add(note(str(!q.isEmpty() ? R.string.xd_chooser_no_match : TAB_RUNNING.equals(id) ? R.string.xd_switcher_empty
                : TAB_FAVOURITES.equals(id) ? R.string.xd_dev_no_favourites : R.string.xd_dev_empty), null, null));
        }
        return out;
    }

    private Item header(String title) {
        Item i = new Item();
        i.kind = HEADER;
        i.label = title;
        return i;
    }

    private Item note(String label, String detail, Runnable action) {
        Item i = new Item();
        i.kind = NOTE;
        i.label = label;
        i.detail = detail;
        i.action = action;
        return i;
    }

    /** The one before first (if it is not a favourite anyway), then the favourites in the user's order. */
    private void favouriteRows(List<Item> out) {
        if (host == null) return;
        List<String> favourites = host.favourites();
        String previous = host.previous();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (previous != null) keys.add(previous);
        keys.addAll(favourites);
        for (String k : keys) {
            Item i = forKey(k);
            // Hiding takes an item out of the favourites; one hidden some other way does not show here either
            if (i == null || i.hidden) continue;
            i.dim = devices.offline(k);
            out.add(across(i, k.equals(previous) && !favourites.contains(k) ? str(R.string.xd_dev_previous) : null));
        }
    }

    /**
     * A device's rows: its whole desktops first, always, then its open windows, then its apps by initial. With a
     * search (q not empty) only what matches, without the section titles. Hidden apps go to {@code hidden}.
     */
    private void deviceRows(Devices.Device d, String q, List<Item> out, List<Item> hidden) {
        boolean searching = !q.isEmpty();
        ArrayList<Item> apps = new ArrayList<>();
        if (d.type == Devices.Type.PHONE) {
            List<AppCatalog.App> all = AppCatalog.get(c).apps();
            if (searching) {
                ArrayList<String> names = new ArrayList<>(all.size());
                for (AppCatalog.App a : all) names.add(a.label);
                Initials.prepare(names, changed);
            }
            for (AppCatalog.App a : all) add(appItem(a), q, apps, hidden);
            if (all.isEmpty() && !searching) out.add(note(str(R.string.xd_chooser_loading), null, null));
        } else if (d.type == Devices.Type.LINUX) {
            ArrayList<Item> desktops = new ArrayList<>();
            // The whole desktop is the only entry that starts an environment's desktop; its programs never do
            if (searching) Initials.prepare(devices.sessionsOf(d.id), changed);
            for (String name : devices.sessionsOf(d.id)) {
                Item i = sessionItem(name);
                if (matches(i, q)) desktops.add(join(i));
            }
            if (d.id.equals(Devices.LINUX + Devices.TERMUX) && !searching) {
                String hint = devices.termuxHint();
                if (hint != null) desktops.add(note(hint, null, () -> { if (host != null) host.termuxHint(); }));
                // The environments are all listed. Only for a program started with xdesk run, or when Termux's state
                // can't be read, is there a reason to attach to X directly
                boolean x = devices.xRunning();
                if (devices.stoppingSession() == null && ((x && devices.runningSession() == null) || hint != null))
                    desktops.add(note(str(x ? R.string.xd_chooser_reattach_x : R.string.xd_chooser_attach_x),
                        str(x ? R.string.xd_chooser_reattach_x_note : R.string.xd_chooser_attach_x_note), () -> { if (host != null) host.attachX(); }));
            }
            if (!desktops.isEmpty()) {
                if (!searching) out.add(header(str(R.string.xd_dev_desktop_header)));
                out.addAll(desktops);
            }
            sourceRows(d, LinuxSource.get(), R.string.xd_dev_linux_windows, q, out, apps, hidden);
        } else if (d.type == Devices.Type.MAC) {
            sourceRows(d, Contents.sourceOf(MacSource.PREFIX), R.string.xd_dev_mac_windows, q, out, apps, hidden);
        }
        if (apps.isEmpty()) return;
        sort(apps);
        if (!searching) {
            // The A–Z bar goes by these
            for (Item i : apps) i.initial = Initials.of(i.label);
            if (d.external()) out.add(header(str(R.string.xd_dev_apps)));
        }
        out.addAll(apps);
    }

    /** A source's desktop and windows of this device as sections, its apps into {@code apps}. */
    private void sourceRows(Devices.Device d, ContentSource s, int windowsTitle, String q, List<Item> out, List<Item> apps, List<Item> hidden) {
        if (s == null) return;
        boolean searching = !q.isEmpty();
        // A Mac not connected: only its whole desktop (opening it connects). The windows and apps come when it is,
        // then the list is the real one, not a stale copy (DEVICES.md §3). A Linux environment is local: always listed
        boolean catalog = devices.listsCatalog(d);
        ArrayList<Item> open = new ArrayList<>();
        HashSet<String> shown = new HashSet<>();
        for (ContentSource.Entry e : s.windows()) {
            if (!devices.deviceOf(e.key).equals(d.id)) continue;
            shown.add(e.key);
            Item i = entryItem(e);
            if (i.desktop) {
                // The whole desktop first, always, even locked or still connecting: opening it is how a locked Mac is
                // unlocked, and only then does Sill send the apps (DEVICES.md §3)
                if (!matches(i, q)) continue;
                if (!searching) out.add(header(str(R.string.xd_dev_desktop_header)));
                out.add(join(i));
            } else if (catalog && matches(i, q)) {
                open.add(join(i));
            }
        }
        if (!open.isEmpty()) {
            if (!searching) out.add(header(str(windowsTitle)));
            out.addAll(open);
        }
        if (searching) {
            ArrayList<String> names = new ArrayList<>();
            if (catalog) for (ContentSource.Entry e : s.apps()) if (devices.deviceOf(e.key).equals(d.id)) names.add(e.label);
            for (ContentSource.Entry e : s.windows()) if (devices.deviceOf(e.key).equals(d.id)) names.add(e.label);
            Initials.prepare(names, changed);
        }
        if (!catalog) return;
        for (ContentSource.Entry e : s.apps())
            if (!shown.contains(e.key) && devices.deviceOf(e.key).equals(d.id)) add(entryItem(e), q, apps, hidden);
    }

    private void add(Item i, String q, List<Item> apps, List<Item> hidden) {
        if (matches(i, q)) (i.hidden ? hidden : apps).add(join(i));
    }

    /** By what it says, and by the pinyin initials of a Chinese name ("wx" finds 微信). */
    private boolean matches(Item i, String q) {
        return q.isEmpty() || i.search.contains(q) || Initials.letters(i.label).contains(q);
    }

    /** By initial (A–Z, then '#'), then by name. Chinese names get their initial in the background, then one more change. */
    private void sort(List<Item> list) {
        ArrayList<String> names = new ArrayList<>(list.size());
        for (Item i : list) names.add(i.label);
        Initials.prepare(names, changed);
        Collections.sort(list, (a, b) -> {
            int by = Integer.compare(Initials.rank(Initials.of(a.label)), Initials.rank(Initials.of(b.label)));
            return by != 0 ? by : collator.compare(a.label, b.label);
        });
    }

    // --- one item ---

    private Item item(String key, String label) {
        Item i = new Item();
        i.kind = ITEM;
        i.key = key;
        i.label = label != null ? label : "";
        i.title = i.label;
        i.hidden = devices.isHidden(key);
        i.current = key.equals(current);
        i.favourite = favourites.contains(key);
        if (i.current) i.state = str(onCar ? R.string.xd_on_car : R.string.xd_on_display);
        return i;
    }

    private Item appItem(AppCatalog.App a) {
        Item i = item(ChooserView.recentKey(a), a.label);
        i.app = a;
        i.where = str(R.string.xd_dev_phone);
        i.search = (a.label + " " + a.packageName).toLowerCase();
        return i;
    }

    /** The whole desktop of a Linux environment: the only thing that starts its XFCE session (DEVICES.md §2). */
    private Item sessionItem(String name) {
        Item i = item(ChooserView.recentKey(name), str(R.string.xd_dev_whole_desktop));
        i.desktop = i.session = true;
        i.running = name.equals(devices.runningSession());
        String device = devices.deviceOfSession(name);
        i.where = Devices.linuxName(c, device);
        i.sub = name;
        // Whose desktop: the environment, or the session where an environment has more than one
        i.title = i.label + " · " + (devices.sessionsOf(device).size() > 1 ? name : i.where);
        if (name.equals(devices.stoppingSession())) i.state = str(R.string.xd_chooser_stopping_short);
        else if (!i.current && i.running) i.state = str(R.string.xd_content_running);
        i.search = (i.label + " " + name).toLowerCase();
        return i;
    }

    private Item entryItem(ContentSource.Entry e) {
        Item i = item(e.key, e.label);
        i.content = true;
        i.bitmap = e.icon;
        i.running = e.running;
        i.where = deviceName(e.key);
        boolean detail = e.detail != null && !e.detail.equalsIgnoreCase(e.label);
        if (ChooserView.isDesktopKey(e.key)) {
            i.desktop = true;
            i.label = str(R.string.xd_dev_whole_desktop);
            i.title = i.label + " · " + i.where;
            i.sub = detail ? e.detail : e.label;
        } else if (detail) {
            i.sub = e.detail;
        }
        // A window says its title; "running" is said of what could as well not be
        if (!i.current && e.running && (i.desktop || !detail)) i.state = str(R.string.xd_content_running);
        i.search = (i.label + " " + e.label + " " + (e.detail != null ? e.detail : "")).toLowerCase();
        return i;
    }

    private String deviceName(String key) {
        String id = devices.deviceOf(key);
        if (id.equals(Devices.PHONE)) return str(R.string.xd_dev_phone);
        if (id.startsWith(Devices.LINUX)) return Devices.linuxName(c, id);
        Devices.Device d = devices.find(id);
        return d != null ? d.name : "";
    }

    private static String joined(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(p);
        }
        return out.length() > 0 ? out.toString() : null;
    }

    /** The detail line in its own device's list: what it is and its state. */
    private Item join(Item i) {
        i.detail = joined(i.sub, i.state);
        return i;
    }

    /** The detail line in a list across devices (running, favourites, the strip): the device comes first. */
    private Item across(Item i, String prefix) {
        i.detail = joined(prefix, i.where, i.sub, i.state);
        return i;
    }

    /**
     * An item for any saved key (a favourite, the previous one, what runs), or null when it is gone: an app
     * uninstalled or not listed yet, a program or window that is no more, a desktop xdesk no longer knows.
     */
    private Item forKey(String key) {
        if (key == null) return null;
        // Termux removed (from the phone or as a device): its programs and desktops leave the favourites and
        // Docks too. What is on the display now stays while it is there
        if (devices.deviceOf(key).startsWith(Devices.LINUX) && !devices.linuxShown() && !key.equals(current)) return null;
        if (ChooserView.isAppKey(key)) {
            AppCatalog.App a = AppCatalog.get(c).find(ChooserView.componentOf(key));
            return a != null ? appItem(a) : null;
        }
        if (Contents.sourceOf(key) != null) {
            ContentSource.Entry e = Contents.find(key);
            return e != null ? entryItem(e) : null;
        }
        if (!key.startsWith("t:")) return null;
        String name = ChooserView.sessionOf(key);
        // Also one that is on the screen without being in xdesk's list (attached by hand)
        boolean known = devices.sessions().contains(name) || name.equals(devices.runningSession()) || key.equals(current);
        return known ? sessionItem(name) : null;
    }

    /** For the views that show single keys. Null when the key is gone. */
    Item find(String key) {
        look();
        Item i = forKey(key);
        return i != null ? across(i, null) : null;
    }

    boolean exists(String key) {
        return ExternalLauncher.SWITCHER.equals(key) || find(key) != null;
    }

    // --- the strip (the Docks) ---

    /**
     * The switchboard's short form: what is on the screen, the one before it, the favourites in their order, and with
     * {@code all} a last tile that opens the whole of it. Hidden items are not in it; what is on the screen is, hidden
     * or not, since it is there to be seen.
     */
    List<Item> strip(boolean all) {
        ArrayList<Item> out = new ArrayList<>();
        if (host != null) {
            String previous = host.previous();
            look();
            Item now = find(current);
            if (now != null) out.add(inStrip(now, STRIP_CURRENT));
            Item before = previous != null && !previous.equals(current) ? find(previous) : null;
            if (before != null && !before.hidden) out.add(inStrip(before, STRIP_PREVIOUS));
            for (String k : host.favourites()) {
                Item i = find(k);
                if (i != null && !i.hidden) out.add(inStrip(i, STRIP_FAVOURITE));
            }
        }
        if (all) {
            Item i = new Item();
            i.kind = ITEM;
            i.key = ExternalLauncher.SWITCHER;
            i.label = i.title = str(R.string.xd_switcher_short);
            out.add(inStrip(i, STRIP_ALL));
        }
        return out;
    }

    private static Item inStrip(Item i, int place) {
        i.strip = place;
        return i;
    }
}
