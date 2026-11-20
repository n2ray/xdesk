package com.termux.x11;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.termux.x11.app.R;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The permissions XDesk asks for, in one place: what each is, how to tell whether it is there (the checks the rest of
 * the app already uses), what opens the right system page, and the level the user decided on. The settings home, the
 * permission centre, the detail pages, the first-start checklist and the lost-permission reminder all read this.
 *
 * <p>Required: the injector service and drawing over other apps. Recommended: notifications. The rest are needed only
 * when their feature is used. Autostart cannot be read: MIUI/HyperOS gives no readable switch, so its state is
 * {@link State#UNKNOWN} and only the system page is offered.
 */
final class Permissions {
    enum Level { REQUIRED, RECOMMENDED, WHEN_NEEDED }
    enum State { ON, OFF, UNKNOWN }

    static final String RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String PREFS = "permissions";
    private static final String WAS_ON = "was_on_";
    /** Items already reminded in this process: once per app start, as the user asked. */
    private static final Set<String> reminded = new HashSet<>();
    /** One reminder notification: a later one replaces it instead of piling up. */
    private static final int REMINDER_ID = 700;

    /** One permission: its words and one way to test it. */
    static final class Item {
        final String id;
        final Level level;
        final int name, use, why, without, steps, action, lost;
        /** A runtime permission to ask the system for, or null when only a page can change it. */
        final String runtime;

        Item(String id, Level level, int name, int use, int why, int without, int steps, int action, int lost, String runtime) {
            this.id = id;
            this.level = level;
            this.name = name;
            this.use = use;
            this.why = why;
            this.without = without;
            this.steps = steps;
            this.action = action;
            this.lost = lost;
            this.runtime = runtime;
        }

        State state(Context c) {
            try {
                switch (id) {
                    case "injector":
                        // The same test the app uses before starting anything: Shizuku permission or the built-in server
                        return AppSession.get(c).injectorReady() ? State.ON : State.OFF;
                    case "overlay":
                        return Settings.canDrawOverlays(c) ? State.ON : State.OFF;
                    case "notifications":
                        if (Build.VERSION.SDK_INT < 33) return State.ON;
                        return Permissions.granted(c, Manifest.permission.POST_NOTIFICATIONS) ? State.ON : State.OFF;
                    case "notify_access":
                        return NotifyCenter.accessGranted(c) ? State.ON : State.OFF;
                    case "termux":
                        return Permissions.granted(c, RUN_COMMAND) ? State.ON : State.OFF;
                    case "camera":
                        return Permissions.granted(c, Manifest.permission.CAMERA) ? State.ON : State.OFF;
                    case "dnd": {
                        NotificationManager nm = c.getSystemService(NotificationManager.class);
                        return nm != null && nm.isNotificationPolicyAccessGranted() ? State.ON : State.OFF;
                    }
                    case "autostart":
                        // MIUI/HyperOS exposes no readable autostart switch: never claim it is off
                        return State.UNKNOWN;
                    case "usb_debug":
                        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1
                            ? State.ON : State.OFF;
                    case "ncm":
                        return WebServer.usbTetheringIsNcm(c) ? State.ON : State.OFF;
                }
            } catch (RuntimeException e) {
                Log.w("XDesk", "permission check " + id, e);
            }
            return State.OFF;
        }

        boolean granted(Context c) {
            return state(c) == State.ON;
        }

        /** Whether the item is worth showing on this phone at all. */
        boolean visible(Context c) {
            switch (id) {
                case "termux":
                    return installed(c, "com.termux");
                case "autostart":
                    return NotifyCenter.hasAutostartSwitch(c);
                case "camera":
                    return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
                default:
                    return true;
            }
        }

        /** The system page that can change it, or null when XDesk routes it itself (injector, autostart, NCM). */
        Intent page(Context c) {
            switch (id) {
                case "overlay":
                    return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
                case "notifications":
                    return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                case "notify_access":
                    return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                case "termux":
                case "camera":
                    // Refused before: the permission only comes back on the app's details page
                    return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + c.getPackageName()));
                case "dnd":
                    return new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                case "usb_debug":
                    return new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
                default:
                    return null;
            }
        }
    }

    static final Item INJECTOR = new Item("injector", Level.REQUIRED,
        R.string.xd_perm_injector_name, R.string.xd_perm_injector_use, R.string.xd_perm_injector_why,
        R.string.xd_perm_injector_without, R.string.xd_perm_injector_steps, R.string.xd_perm_activate,
        R.string.xd_perm_lost_injector, null);
    static final Item OVERLAY = new Item("overlay", Level.REQUIRED,
        R.string.xd_permit_overlay, R.string.xd_perm_overlay_use, R.string.xd_perm_overlay_why,
        R.string.xd_perm_overlay_without, R.string.xd_perm_overlay_steps, R.string.xd_perm_turn_on,
        R.string.xd_perm_lost_overlay, null);
    static final Item NOTIFICATIONS = new Item("notifications", Level.RECOMMENDED,
        R.string.xd_permit_post, R.string.xd_perm_notifications_use, R.string.xd_perm_notifications_why,
        R.string.xd_perm_notifications_without, R.string.xd_perm_notifications_steps, R.string.xd_perm_turn_on,
        0, Manifest.permission.POST_NOTIFICATIONS);
    static final Item NOTIFY_ACCESS = new Item("notify_access", Level.WHEN_NEEDED,
        R.string.xd_permit_notify_access, R.string.xd_perm_notify_access_use, R.string.xd_permit_notify_access_why,
        R.string.xd_perm_notify_access_without, R.string.xd_perm_notify_access_steps, R.string.xd_perm_turn_on,
        0, null);
    static final Item TERMUX = new Item("termux", Level.WHEN_NEEDED,
        R.string.xd_permit_run_command, R.string.xd_perm_termux_use, R.string.xd_permit_run_command_why,
        R.string.xd_perm_termux_without, R.string.xd_perm_termux_steps, R.string.xd_perm_turn_on,
        0, RUN_COMMAND);
    static final Item CAMERA = new Item("camera", Level.WHEN_NEEDED,
        R.string.xd_permit_camera, R.string.xd_perm_camera_use, R.string.xd_permit_camera_why,
        R.string.xd_perm_camera_without, R.string.xd_perm_camera_steps, R.string.xd_perm_turn_on,
        0, Manifest.permission.CAMERA);
    static final Item DND = new Item("dnd", Level.WHEN_NEEDED,
        R.string.xd_permit_dnd, R.string.xd_perm_dnd_use, R.string.xd_permit_dnd_why,
        R.string.xd_perm_dnd_without, R.string.xd_perm_dnd_steps, R.string.xd_perm_turn_on,
        0, null);
    static final Item AUTOSTART = new Item("autostart", Level.WHEN_NEEDED,
        R.string.xd_permit_autostart, R.string.xd_perm_autostart_use, R.string.xd_permit_autostart_why,
        R.string.xd_perm_autostart_without, R.string.xd_perm_autostart_steps, R.string.xd_perm_open,
        0, null);
    static final Item USB_DEBUG = new Item("usb_debug", Level.WHEN_NEEDED,
        R.string.xd_perm_usb_debug_name, R.string.xd_perm_usb_debug_use, R.string.xd_perm_usb_debug_why,
        R.string.xd_perm_usb_debug_without, R.string.xd_perm_usb_debug_steps, R.string.xd_perm_turn_on,
        0, null);
    static final Item NCM = new Item("ncm", Level.WHEN_NEEDED,
        R.string.xd_permit_write_secure, R.string.xd_perm_ncm_use, R.string.xd_perm_ncm_why,
        R.string.xd_perm_ncm_without, R.string.xd_perm_ncm_steps, R.string.xd_permit_copy,
        0, null);

    /** The exact list, in the order the permission centre shows it. */
    static final Item[] ALL = {
        INJECTOR, OVERLAY, NOTIFICATIONS, NOTIFY_ACCESS, TERMUX, CAMERA, DND, AUTOSTART, USB_DEBUG, NCM
    };

    private Permissions() { }

    /** Visible items of one level, in ALL's order. */
    static List<Item> items(Context c, Level level) {
        List<Item> out = new ArrayList<>();
        for (Item i : ALL) if (i.level == level && i.visible(c)) out.add(i);
        return out;
    }

    static List<Item> visible(Context c) {
        List<Item> out = new ArrayList<>();
        for (Item i : ALL) if (i.visible(c)) out.add(i);
        return out;
    }

    /** Required and recommended items that are known to be off: the number the settings home says. */
    static int attention(Context c) {
        int n = 0;
        for (Item i : ALL) {
            if (i.level == Level.WHEN_NEEDED || !i.visible(c)) continue;
            if (i.state(c) == State.OFF) n++;
        }
        return n;
    }

    static int onCount(Context c) {
        int n = 0;
        for (Item i : visible(c)) if (i.state(c) == State.ON) n++;
        return n;
    }

    /** The line a settings row shows instead of silently doing nothing: "Needs “X” · Turn on ›". */
    static String need(Context c, Item item) {
        return c.getString(R.string.xd_perm_inline_need, c.getString(item.name));
    }

    /**
     * Required permissions that were on and are off now: found once per item per process (the user asked not to be
     * told more than once per app start), and every time they are on, the remembered state is refreshed. The injector
     * is not called lost while its server is starting or pairing: that is a wait, not a loss.
     */
    static List<Item> lost(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Item> out = new ArrayList<>();
        for (Item i : ALL) {
            if (i.level != Level.REQUIRED || !i.visible(c)) continue;
            State s = i.state(c);
            if (s == State.ON) {
                p.edit().putBoolean(WAS_ON + i.id, true).apply();
                continue;
            }
            if (!p.getBoolean(WAS_ON + i.id, false) || reminded.contains(i.id)) continue;
            if (i == INJECTOR) {
                ShellActivation shell = AppSession.get(c).shell();
                if (shell.isStarting() || shell.isPairing() || shell.isStoppedByUser()) continue;
            }
            reminded.add(i.id);
            out.add(i);
        }
        return out;
    }

    /** The notification the mockup draws for a lost permission; the existing desktop channel is reused. */
    static void notifyLost(Context c, List<Item> items) {
        if (items.isEmpty()) return;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(DesktopService.CHANNEL,
                c.getString(R.string.xd_service_channel), NotificationManager.IMPORTANCE_DEFAULT));
            StringBuilder text = new StringBuilder();
            for (Item i : items) {
                if (text.length() > 0) text.append(" · ");
                text.append(c.getString(i.lost));
            }
            Intent open = new Intent(c, XDeskActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, DesktopService.CHANNEL)
                .setSmallIcon(R.drawable.xdesk_ic_mono)
                .setContentTitle(c.getString(R.string.xd_perm_lost_notification))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
            nm.notify(REMINDER_ID, n);
        } catch (RuntimeException e) {
            Log.w("XDesk", "lost permission notification", e);
        }
    }

    /** Everything fixed: the reminder goes too. */
    static void cancelLost(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(REMINDER_ID);
        } catch (RuntimeException ignored) { }
    }

    private static boolean granted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
