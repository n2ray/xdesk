package com.termux.x11;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification centre's model (NOTIFY.md §2, §7): what the phone's notifications are now, grouped by app, the
 * unread count, the settings that say what shows where, and the phone's battery for the panel's header. NotifyService
 * feeds it; Notify's windows (the banners, the panel, the bell) draw it, as Switchboard's views draw the Switchboard.
 *
 * Everything here is in memory: a notification's content is never written anywhere or logged — logs say package
 * names and events only (NOTIFY.md §6). Main thread.
 */
final class NotifyCenter {
    private static final String TAG = "XDeskNotify";
    /** Per-app modes, NOTIFY.md §6: banners and the centre, the centre only, or not shown at all. */
    static final int APP_NORMAL = 0, APP_CENTER = 1, APP_HIDDEN = 2;
    /** What a banner may say, NOTIFY.md §6: everything, the app name only, or no banners. */
    static final int CONTENT_ALL = 0, CONTENT_NAME = 1, CONTENT_NONE = 2;
    static final String[] APP_MODES = {"banner_center", "center", "hidden"};

    static final class Entry {
        final String key, pkg, appLabel;
        final String title, text;
        final long when;
        final StatusBarNotification sbn;
        /** Whether it matched the interruption filter when it was posted: what may still banner under DND. */
        final boolean matchesDnd;
        /** Not yet read: the panel's opening has not shown it. Counts on the bell. */
        boolean seen;
        /** A group's summary, shown while none of its group's messages is (NotifyCenter.posted). */
        boolean summary;

        Entry(String key, String pkg, String appLabel, String title, String text, long when,
              StatusBarNotification sbn, boolean matchesDnd) {
            this.key = key;
            this.pkg = pkg;
            this.appLabel = appLabel;
            this.title = title;
            this.text = text;
            this.when = when;
            this.sbn = sbn;
            this.matchesDnd = matchesDnd;
        }
    }

    /** One app's notifications, the newest first. */
    static final class Group {
        final String pkg, appLabel;
        final List<Entry> entries = new ArrayList<>();

        Group(String pkg, String appLabel) {
            this.pkg = pkg;
            this.appLabel = appLabel;
        }
    }

    private static NotifyCenter instance;

    static synchronized NotifyCenter get(Context c) {
        if (instance == null) instance = new NotifyCenter(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final ArrayList<Group> groups = new ArrayList<>();
    /** Keys of the phone's notifications the centre does not show (silent, ongoing, a hidden app's): the panel's count. */
    private final java.util.HashSet<String> hiddenKeys = new java.util.HashSet<>();
    /** Summaries standing aside while their group has messages here, by key: they come back when it has none. */
    private final Map<String, NotifyService.Posted> suppressed = new HashMap<>();
    /** While a sync runs: whether each key that was here was read. Null otherwise. */
    private Map<String, Boolean> syncing;
    private int listVersion;
    /** When a rebind was last asked for (elapsedRealtime). */
    private static long lastRebind;
    private static final long REBIND_GAP_MS = 5000;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    /** The listener service while the system has it bound: cancelling goes through it. */
    private NotifyService service;
    private int interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL;
    private int batteryLevel = -1;
    private boolean batteryCharging;
    private int unread;
    /** What changed last, for the views: the newest post, the removed key, the app whose mode changed. */
    private Entry lastPosted;
    private String lastRemoved;
    private String lastModeApp;
    /** The phone was unlocked (ACTION_USER_PRESENT): the "unlock the phone" banner goes (NOTIFY.md §5). */
    private boolean lastUnlocked;

    private NotifyCenter(Context c) {
        context = c;
        prefs = c.getSharedPreferences("notify", Context.MODE_PRIVATE);
        refreshBattery();
    }

    void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    /** Whether the system's notification access is granted to XDesk. Granted is not bound: see connected(). */
    static boolean accessGranted(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) return nm.isNotificationListenerAccessGranted(new android.content.ComponentName(c, NotifyService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "access check: " + e);
        }
        return accessGrantedBySetting(c);
    }

    /** The same from Settings.Secure's enabled listeners, where the system's call fails. */
    private static boolean accessGrantedBySetting(Context c) {
        // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is hidden: its value is "enabled_notification_listeners"
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        String pkg = c.getPackageName();
        return flat.contains(pkg + "/" + NotifyService.class.getName())
            || flat.contains(pkg + "/." + NotifyService.class.getSimpleName());
    }

    /** The system settings page for the notification access. */
    static void openAccessSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // --- the list ---

    /**
     * A notification was posted (NOTIFY.md §2). {@code seed}: it was already there, found by a sync, so it neither
     * counts unread nor raises a banner. A post of a key already here replaces it (an update); one that no longer
     * shows (it went silent, its app was hidden) takes the old one out.
     */
    void posted(NotifyService.Posted p, boolean seed) {
        StatusBarNotification sbn = p.sbn;
        String pkg = sbn.getPackageName(), key = sbn.getKey(), groupKey = sbn.getGroupKey();
        Entry old = find(key);
        boolean own = context.getPackageName().equals(pkg);
        boolean shows = NotifyFilter.shows(p.ongoing, p.call, p.silent, own) && appMode(pkg) != APP_HIDDEN;
        if (!shows) {
            // XDesk's own (its service's) is not one the user would miss: not counted as hidden
            if (!own) hiddenKeys.add(key);
            suppressed.remove(key);
            if (old != null) drop(old);
            listChanged();
            return;
        }
        hiddenKeys.remove(key);
        // A group's summary only while none of its messages is here: some apps post nothing but the summary
        if (p.summary && hasMessage(pkg, groupKey)) {
            suppressed.put(key, p);
            if (old != null) drop(old);
            listChanged();
            return;
        }
        suppressed.remove(key);
        Entry e = new Entry(key, pkg, appLabel(pkg), titleOf(sbn), textOf(sbn), whenOf(sbn), sbn, p.matchesDnd);
        e.summary = p.summary;
        e.seen = seed ? syncSeen(key) : old != null && old.seen;
        if (old != null) drop(old);
        // A message came: its group's summary steps aside until the group has none again
        if (!p.summary) for (Entry s : summariesOf(pkg, groupKey)) {
            drop(s);
            suppressed.put(s.key, new NotifyService.Posted(s.sbn, false, false, true, false, s.matchesDnd));
        }
        Group g = group(pkg);
        int at = 0;
        while (at < g.entries.size() && g.entries.get(at).when >= e.when) at++;
        g.entries.add(at, e);
        sortGroups();
        if (!e.seen) unread++;
        // An update neither counts unread again (an unseen one is still one unseen) nor raises a banner
        // (FLAG_ONLY_ALERT_ONCE re-posts are updates, NOTIFY.md §2)
        if (!seed && NotifyFilter.fresh(old != null)) lastPosted = e;
        listChanged();
    }

    /**
     * NOTIFY.md §2.1: the centre made the same as the phone's notifications now. Kept: what was here and still is (read
     * or not); gone: what went meanwhile; new ones enter read, without banners.
     */
    void sync(List<NotifyService.Posted> now) {
        java.util.HashMap<String, Boolean> seen = new java.util.HashMap<>();
        for (Group g : groups) for (Entry e : g.entries) seen.put(e.key, e.seen);
        syncing = seen;
        groups.clear();
        hiddenKeys.clear();
        suppressed.clear();
        unread = 0;
        lastPosted = null;
        // The messages first, so that a summary knows whether its group has any
        for (NotifyService.Posted p : now) if (!p.summary) posted(p, true);
        for (NotifyService.Posted p : now) if (p.summary) posted(p, true);
        syncing = null;
        listChanged();
    }

    /** During a sync, whether a key was read before it; one not here before counts as read. */
    private boolean syncSeen(String key) {
        Boolean s = syncing != null ? syncing.get(key) : null;
        return s == null || s;
    }

    /** Cleared on the phone: it leaves the centre too (NOTIFY.md §2). */
    void removed(String key) {
        hiddenKeys.remove(key);
        suppressed.remove(key);
        Entry gone = find(key);
        if (gone == null) {
            listChanged();
            return;
        }
        drop(gone);
        lastRemoved = key;
        // Its group's last message went: a summary that stood aside for it comes back
        if (!gone.summary && !hasMessage(gone.pkg, gone.sbn.getGroupKey())) {
            for (NotifyService.Posted p : new ArrayList<>(suppressed.values())) {
                if (p.sbn.getPackageName().equals(gone.pkg) && p.sbn.getGroupKey().equals(gone.sbn.getGroupKey())) posted(p, true);
            }
        }
        listChanged();
    }

    /** Out of the list, the unread count and an empty group with it. */
    private void drop(Entry e) {
        for (Group g : groups) {
            if (!g.entries.remove(e)) continue;
            if (!e.seen) unread = Math.max(0, unread - 1);
            if (g.entries.isEmpty()) groups.remove(g);
            return;
        }
    }

    private boolean hasMessage(String pkg, String groupKey) {
        for (Group g : groups) {
            if (!g.pkg.equals(pkg)) continue;
            for (Entry e : g.entries) if (!e.summary && e.sbn.getGroupKey().equals(groupKey)) return true;
        }
        return false;
    }

    private List<Entry> summariesOf(String pkg, String groupKey) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Group g : groups) {
            if (!g.pkg.equals(pkg)) continue;
            for (Entry e : g.entries) if (e.summary && e.sbn.getGroupKey().equals(groupKey)) out.add(e);
        }
        return out;
    }

    /**
     * Cleared from the external screen: cancelled on the phone, and gone from here with it (NOTIFY.md §2). False when
     * that cannot happen — the system keeps it (FLAG_NO_CLEAR), or the service is not bound — and it stays.
     */
    boolean cancel(String key) {
        Entry e = find(key);
        if (e == null) return false;
        if ((e.sbn.getNotification().flags & android.app.Notification.FLAG_NO_CLEAR) != 0) return false;
        NotifyService s = service;
        if (s == null) return false;
        try {
            s.cancelNotification(key);
        } catch (RuntimeException ex) {
            Log.w(TAG, "cancel " + e.pkg + ": " + ex);
            return false;
        }
        removed(key);
        return true;
    }

    private Entry find(String key) {
        for (Group g : groups) for (Entry e : g.entries) if (e.key.equals(key)) return e;
        return null;
    }

    void clearAll() {
        for (Group g : new ArrayList<>(groups)) for (Entry e : new ArrayList<>(g.entries)) cancel(e.key);
        // cancel() → removed() → changed(); make sure one change comes even when nothing was cancelled
        changed();
    }

    /** The list itself changed (not only the battery or a setting): the panel draws it again. One change per sync. */
    private void listChanged() {
        if (syncing != null) return;
        listVersion++;
        changed();
    }

    int listVersion() {
        return listVersion;
    }

    /** Notifications on the phone the centre does not show: silent, ongoing, of hidden apps. The panel says how many. */
    int hiddenCount() {
        return hiddenKeys.size();
    }

    /** The panel opened: everything counts as read. */
    void markSeen() {
        for (Group g : groups) for (Entry e : g.entries) e.seen = true;
        unread = 0;
        changed();
    }

    List<Group> groups() {
        return groups;
    }

    int unread() {
        return unread;
    }

    /**
     * The settings' per-app list: the apps in the centre now, the newest first, then every app with a choice of its own
     * (a hidden app has nothing in the centre, and must still be found to show it again).
     */
    List<String> recentApps() {
        ArrayList<String> out = new ArrayList<>();
        for (Group g : groups) out.add(g.pkg);
        for (String k : prefs.getAll().keySet()) {
            if (!k.startsWith("app_")) continue;
            String pkg = k.substring(4);
            if (!out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    String appLabel(String pkg) {
        String label = labels.get(pkg);
        if (label != null) return label;
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            label = String.valueOf(context.getPackageManager().getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            label = pkg;
        }
        labels.put(pkg, label);
        return label;
    }

    /** The app's icon for banners and the panel's rows; null when it has none. */
    Drawable icon(String pkg) {
        Drawable d = icons.get(pkg);
        if (d == null && !icons.containsKey(pkg)) {
            try {
                d = context.getPackageManager().getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                d = null;
            }
            icons.put(pkg, d);
        }
        return d;
    }

    private Group group(String pkg) {
        for (Group g : groups) if (g.pkg.equals(pkg)) return g;
        Group g = new Group(pkg, appLabel(pkg));
        groups.add(g);
        return g;
    }

    private void sortGroups() {
        // The newest app first; the panel draws the groups in this order
        java.util.Collections.sort(groups, (a, b) -> Long.compare(latest(b), latest(a)));
    }

    private static long latest(Group g) {
        return g.entries.isEmpty() ? 0 : g.entries.get(0).when;
    }

    /** The title, or a conversation's (MessagingStyle puts a group chat's name there). */
    private static String titleOf(StatusBarNotification sbn) {
        android.os.Bundle x = sbn.getNotification().extras;
        CharSequence t = x.getCharSequence(android.app.Notification.EXTRA_TITLE);
        if (t == null || t.length() == 0) t = x.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE);
        return t != null ? t.toString() : "";
    }

    /**
     * The line under the title: the text, else what the style holds — a conversation's newest message (with its
     * sender), the big text, an inbox's last line, the sub text. Chat apps often leave EXTRA_TEXT empty.
     */
    private static String textOf(StatusBarNotification sbn) {
        android.os.Bundle x = sbn.getNotification().extras;
        CharSequence t = x.getCharSequence(android.app.Notification.EXTRA_TEXT);
        if (t != null && t.length() > 0) return t.toString();
        android.os.Parcelable[] messages = x.getParcelableArray(android.app.Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof android.os.Bundle) {
            android.os.Bundle m = (android.os.Bundle) messages[messages.length - 1];
            CharSequence text = m.getCharSequence("text");
            CharSequence sender = m.getCharSequence("sender");
            if (text != null && text.length() > 0) return sender != null && sender.length() > 0 ? sender + ": " + text : text.toString();
        }
        t = x.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
        if (t != null && t.length() > 0) return t.toString();
        CharSequence[] lines = x.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0 && lines[lines.length - 1] != null) return lines[lines.length - 1].toString();
        t = x.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT);
        return t != null ? t.toString() : "";
    }

    private static long whenOf(StatusBarNotification sbn) {
        long when = sbn.getNotification().when;
        return when > 0 ? when : sbn.getPostTime();
    }

    // --- Do not disturb (NOTIFY.md §2) ---

    void interruptionFilter(int filter) {
        if (filter == interruptionFilter) return;
        interruptionFilter = filter;
        changed();
    }

    int interruptionFilter() {
        return interruptionFilter;
    }

    /** NOTIFY.md §2: under Do not disturb nothing banners (what matched the filter when posted still may). */
    boolean bannersAllowed(Entry e) {
        return interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL || e.matchesDnd;
    }

    // --- the battery and the keyguard (NOTIFY.md §4, §5) ---

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) onBattery(intent);
            else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) userPresent();
        }
    };

    /** Live updates while the listener service is connected; the receivers go with it. Registered once. */
    void registerReceivers(NotifyService s) {
        try {
            s.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            s.registerReceiver(battery, new IntentFilter(Intent.ACTION_USER_PRESENT));
        } catch (RuntimeException e) {
            Log.w(TAG, "notify receivers", e);
        }
    }

    void unregisterReceivers(NotifyService s) {
        try {
            s.unregisterReceiver(battery);
        } catch (RuntimeException ignored) { }
    }

    /** The phone was unlocked: the unlock banner's moment is over (NOTIFY.md §5). */
    void userPresent() {
        lastUnlocked = true;
        changed();
    }

    boolean justUnlocked() {
        boolean u = lastUnlocked;
        lastUnlocked = false;
        return u;
    }

    /** One reading now (sticky): the panel's header, the low-battery banners without the service. */
    private void refreshBattery() {
        Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i != null) onBattery(i);
    }

    private void onBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        int percent = level >= 0 && scale > 0 ? Math.round(100f * level / scale) : -1;
        if (percent == batteryLevel && charging == batteryCharging) return;
        batteryLevel = percent;
        batteryCharging = charging;
        changed();
    }

    /** The phone's battery in percent, -1 while unknown. */
    int batteryLevel() {
        return batteryLevel;
    }

    boolean batteryCharging() {
        return batteryCharging;
    }

    // --- settings (NOTIFY.md §6) ---

    /** Show notifications on the external screen. Off: no banners, the bell only counts; the centre still opens. */
    boolean notifyOn() {
        return prefs.getBoolean("on", true);
    }

    void setNotifyOn(boolean on) {
        prefs.edit().putBoolean("on", on).apply();
        changed();
    }

    /**
     * What a banner may say: CONTENT_ALL, CONTENT_NAME or CONTENT_NONE. Never chosen, a wireless receiver shows the
     * app name only (NOTIFY.md §6: a projector or a meeting-room screen); once the user picks, that holds.
     */
    int bannerContent() {
        if (!prefs.contains("content")) return WirelessDisplay.get(context).isActive() ? CONTENT_NAME : CONTENT_ALL;
        int v = prefs.getInt("content", CONTENT_ALL);
        return v >= CONTENT_ALL && v <= CONTENT_NONE ? v : CONTENT_ALL;
    }

    /**
     * Whether the panel's rows, like the banners, say only the app (NOTIFY.md §6): the same choice, so on a projector
     * the centre does not show what the banners hide. A row's ▾ still shows it all, on the user's own click.
     */
    boolean namesOnly() {
        return bannerContent() == CONTENT_NAME;
    }

    boolean bannerContentChosen() {
        return prefs.contains("content");
    }

    void setBannerContent(int content) {
        prefs.edit().putInt("content", content).apply();
        changed();
    }

    int appMode(String pkg) {
        String saved = prefs.getString("app_" + pkg, null);
        for (int i = 0; i < APP_MODES.length; i++) if (APP_MODES[i].equals(saved)) return i;
        return APP_NORMAL;
    }

    void setAppMode(String pkg, int mode) {
        if (mode == APP_NORMAL) prefs.edit().remove("app_" + pkg).apply();
        else prefs.edit().putString("app_" + pkg, APP_MODES[mode]).apply();
        lastModeApp = pkg;
        NotifyService s = service;
        if (s != null) {
            // Hidden: its entries leave the centre (the notifications stay on the phone); shown again: they come back
            s.resync();
            return;
        }
        if (mode == APP_HIDDEN) {
            for (Group g : new ArrayList<>(groups)) {
                if (!g.pkg.equals(pkg)) continue;
                for (Entry e : g.entries) if (!e.seen) unread = Math.max(0, unread - 1);
                groups.remove(g);
            }
            listVersion++;
        }
        changed();
    }

    boolean bellShown() {
        return prefs.getBoolean("bell", true);
    }

    void setBellShown(boolean on) {
        prefs.edit().putBoolean("bell", on).apply();
        changed();
    }

    boolean lowBatteryBanners() {
        return prefs.getBoolean("low_battery", true);
    }

    void setLowBatteryBanners(boolean on) {
        prefs.edit().putBoolean("low_battery", on).apply();
        changed();
    }

    /** Whether the notification-policy access was explained once (NOTIFY.md §4's DND switch). */
    boolean policyAsked() {
        return prefs.getBoolean("policy_asked", false);
    }

    void setPolicyAsked() {
        prefs.edit().putBoolean("policy_asked", true).apply();
    }

    // --- what changed last, for the views ---

    Entry lastPosted() {
        Entry e = lastPosted;
        lastPosted = null;
        return e;
    }

    String lastRemoved() {
        String k = lastRemoved;
        lastRemoved = null;
        return k;
    }

    String lastModeApp() {
        String p = lastModeApp;
        lastModeApp = null;
        return p;
    }

    // --- the listener service ---

    void attach(NotifyService s) {
        service = s;
        Log.i(TAG, "connected");
        changed();
    }

    void detach(NotifyService s) {
        if (service != s) return;
        service = null;
        changed();
    }

    /** Whether the system has the listener bound: only then do notifications come (NOTIFY.md §2.1). */
    boolean connected() {
        return service != null;
    }

    /**
     * Asks the system to bind the listener again (NOTIFY.md §2.1). After XDesk's process went, HyperOS refuses the
     * system's own rebind unless XDesk may start itself; asked while XDesk runs, it may let it through. At most every
     * few seconds.
     */
    static void requestRebind(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastRebind != 0 && now - lastRebind < REBIND_GAP_MS) return;
        lastRebind = now;
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                new android.content.ComponentName(c, NotifyService.class));
            Log.i(TAG, "rebind asked");
        } catch (RuntimeException e) {
            Log.w(TAG, "rebind: " + e);
        }
    }

    /** XDesk came to the front, the external screen came, the panel opened: rebind if granted but not bound. */
    void rebindIfNeeded() {
        if (service == null && accessGranted(context)) requestRebind(context);
    }

    /**
     * Where the user lets XDesk start itself, which HyperOS wants before it binds the listener again: MIUI's own page,
     * else the app's details.
     */
    static void openAutostartSettings(Context c) {
        Intent miui = new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(miui);
            return;
        } catch (RuntimeException ignored) {
            // not MIUI / HyperOS
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "no app details page", e);
        }
    }

    /** Whether this phone has MIUI's autostart switch (HyperOS): then the panel names it. */
    static boolean hasAutostartSwitch(Context c) {
        try {
            c.getPackageManager().getPackageInfo("com.miui.securitycenter", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
