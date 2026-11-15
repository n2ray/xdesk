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

    /** Whether the system's notification access is granted to XDesk (Settings.Secure's enabled listeners). */
    static boolean accessGranted(Context c) {
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

    /** A notification was posted, filtered by NOTIFY.md §2. {@code seed}: what was already there when the service connected. */
    void posted(StatusBarNotification sbn, boolean silent, boolean ongoing, boolean groupSummary, boolean matchesDnd, boolean seed) {
        if (!NotifyFilter.shows(ongoing, silent, groupSummary, context.getPackageName().equals(sbn.getPackageName()))) return;
        String pkg = sbn.getPackageName();
        if (appMode(pkg) == APP_HIDDEN) return;
        Entry e = new Entry(sbn.getKey(), pkg, appLabel(pkg), titleOf(sbn), textOf(sbn), whenOf(sbn), sbn, matchesDnd);
        e.seen = seed;
        Group g = group(pkg);
        // The newest first; a fresh post of an existing key replaces it (an update of the same notification)
        boolean existed = false;
        for (int i = 0; i < g.entries.size(); i++) if (g.entries.get(i).key.equals(e.key)) {
            g.entries.remove(i);
            existed = true;
            break;
        }
        int at = 0;
        while (at < g.entries.size() && g.entries.get(at).when >= e.when) at++;
        g.entries.add(at, e);
        sortGroups();
        // An update neither counts unread again (an unseen one is still one unseen) nor raises a banner
        // (FLAG_ONLY_ALERT_ONCE re-posts are updates, NOTIFY.md §2)
        if (!seed && NotifyFilter.fresh(existed)) {
            unread++;
            lastPosted = e;
        }
        changed();
    }

    /** Cleared on the phone: it leaves the centre too (NOTIFY.md §2). */
    void removed(String key) {
        for (Group g : groups) {
            for (int i = 0; i < g.entries.size(); i++) {
                if (!g.entries.get(i).key.equals(key)) continue;
                Entry gone = g.entries.remove(i);
                if (!gone.seen) unread = Math.max(0, unread - 1);
                if (g.entries.isEmpty()) groups.remove(g);
                lastRemoved = key;
                changed();
                return;
            }
        }
    }

    /** Cleared from the external screen: cancels on the phone (NOTIFY.md §2). */
    void cancel(String key) {
        Entry e = find(key);
        if (e == null) return;
        // FLAG_NO_CLEAR: the system keeps it on the phone, so the centre keeps it too — its × does nothing
        if ((e.sbn.getNotification().flags & android.app.Notification.FLAG_NO_CLEAR) != 0) return;
        NotifyService s = service;
        if (s != null) s.cancelNotification(key);
        removed(key);
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

    /** The apps that posted since the service connected, the newest first: the settings' per-app list. */
    List<String> recentApps() {
        ArrayList<String> out = new ArrayList<>();
        for (Group g : groups) out.add(g.pkg);
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

    private static String titleOf(StatusBarNotification sbn) {
        CharSequence t = sbn.getNotification().extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
        return t != null ? t.toString() : "";
    }

    private static String textOf(StatusBarNotification sbn) {
        CharSequence t = sbn.getNotification().extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
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
        if (mode == APP_HIDDEN) {
            // Not shown any more: its entries leave the centre (the notifications stay on the phone)
            for (Group g : new ArrayList<>(groups)) {
                if (!g.pkg.equals(pkg)) continue;
                for (Entry e : g.entries) if (!e.seen) unread = Math.max(0, unread - 1);
                groups.remove(g);
            }
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
    }

    void detach(NotifyService s) {
        if (service == s) service = null;
    }
}
