package com.termux.x11;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.termux.x11.app.R;

/**
 * Settings › Connect device (DEVICES.md §5): the external devices with their state, each one's page (connect,
 * disconnect, its own settings), and Add device › type › that type's own flow. Mac pairing and Mac options stay in
 * MacSettings; this only hosts their entry points. UI thread.
 */
final class DeviceSettings {
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/";

    private final Context c;
    private final SettingsUi s;
    private final Devices devices;

    private DeviceSettings(Context c) {
        this.c = c;
        s = new SettingsUi(c);
        devices = Devices.get(c);
    }

    /** The one call for XDesk's settings list and the launcher's "+" tab. */
    static Dialog open(Context c) {
        return new DeviceSettings(c).list();
    }

    /** A device type's own set-up flow (Add device › type, or the launcher's "Set up" on a device not set up). */
    static void setUp(Context c, Devices.Type type) {
        DeviceSettings d = new DeviceSettings(c);
        if (type == Devices.Type.MAC) MacSettings.add(c);
        else if (type == Devices.Type.LINUX) d.linuxSetUp();
    }

    /** A page whose content is filled again whenever the devices change, while it shows. */
    private interface Filler {
        void fill(LinearLayout content);
    }

    private Dialog live(String title, final Filler filler) {
        final LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        filler.fill(content);
        final Runnable again = () -> {
            content.removeAllViews();
            filler.fill(content);
        };
        devices.addListener(again);
        Dialog d = s.page(title, content);
        d.setOnDismissListener(x -> devices.removeListener(again));
        return d;
    }

    private String stateText(Devices.Device d) {
        String state = c.getString(Devices.stateLabel(d.state));
        return d.note != null && !d.note.isEmpty() ? state + " · " + d.note : state;
    }

    private Dialog list() {
        return live(c.getString(R.string.xd_dev_connect_devices), content -> {
            LinearLayout known = s.section(content, c.getString(R.string.xd_dev_external));
            for (final Devices.Device d : devices.external())
                s.valueRow(known, d.name, stateText(d), typeName(d.type), () -> device(d.id));
            LinearLayout add = s.section(content, c.getString(R.string.xd_dev_add_note));
            s.valueRow(add, c.getString(R.string.xd_dev_add), null, "", this::pickType);
        });
    }

    private String typeName(Devices.Type t) {
        switch (t) {
            case LINUX: return "Linux";
            case MAC: return "Mac";
            case WINDOWS: return "Windows";
            case ANDROID: return "Android";
            default: return "";
        }
    }

    private void pickType() {
        live(c.getString(R.string.xd_dev_add), content -> {
            LinearLayout types = s.section(content, c.getString(R.string.xd_dev_add_pick));
            s.valueRow(types, c.getString(R.string.xd_dev_type_linux), c.getString(R.string.xd_dev_type_linux_note), "", this::linuxSetUp);
            s.valueRow(types, c.getString(R.string.xd_dev_type_mac), c.getString(R.string.xd_dev_type_mac_note), "", () -> MacSettings.add(c));
            LinearLayout later = s.section(content, c.getString(R.string.xd_dev_soon));
            soon(later, c.getString(R.string.xd_dev_type_windows), c.getString(R.string.xd_dev_type_windows_note));
            soon(later, c.getString(R.string.xd_dev_type_android), c.getString(R.string.xd_dev_type_android_note));
        });
    }

    /** A type that comes later: shown, dim, not choosable. */
    private void soon(LinearLayout card, String label, String note) {
        int before = card.getChildCount();
        s.infoRow(card, label, note, c.getString(R.string.xd_dev_soon_short));
        for (int i = before; i < card.getChildCount(); i++) card.getChildAt(i).setAlpha(0.45f);
    }

    /** One device's page. */
    private void device(final String id) {
        Devices.Device first = devices.find(id);
        if (first == null) return;
        live(first.name, content -> {
            final Devices.Device d = devices.find(id);
            if (d == null) {
                // Removed (a Mac): nothing left to show
                s.infoRow(s.card(content), c.getString(R.string.xd_dev_gone), null, null);
                return;
            }
            LinearLayout state = s.section(content, typeName(d.type));
            s.infoRow(state, c.getString(R.string.xd_dev_state), null, stateText(d));
            if (d.state == Devices.State.CONNECTED && !d.busy) {
                s.valueRow(state, c.getString(R.string.xd_dev_disconnect), c.getString(d.type == Devices.Type.LINUX
                        ? R.string.xd_dev_disconnect_linux_note : R.string.xd_dev_disconnect_mac_note), "", () -> devices.disconnect(id));
            } else if (d.state == Devices.State.NOT_SET_UP) {
                s.valueRow(state, c.getString(R.string.xd_dev_set_up), null, "", () -> setUp(c, d.type));
            } else if (d.state != Devices.State.CONNECTING && d.canConnect(devices)) {
                s.valueRow(state, c.getString(R.string.xd_dev_connect), null, "", () -> devices.connect(id));
            }
            if (d.type == Devices.Type.LINUX) {
                LinearLayout sessions = s.section(content, c.getString(R.string.xd_linux_desktops));
                java.util.List<String> own = devices.sessionsOf(id);
                if (own.isEmpty()) s.infoRow(sessions, c.getString(R.string.xd_dev_linux_no_session), null, null);
                for (String name : own)
                    s.infoRow(sessions, name, null, name.equals(devices.runningSession()) ? c.getString(R.string.xd_dev_state_connected)
                            : name.equals(devices.defaultSession()) ? c.getString(R.string.xd_chooser_default) : null);
                LinearLayout more = s.section(content, c.getString(R.string.xd_section_more));
                s.valueRow(more, c.getString(R.string.xd_dev_linux_add_more), null, "", this::linuxSetUp);
            } else if (d.type == Devices.Type.MAC) {
                LinearLayout more = s.section(content, c.getString(R.string.xd_section_more));
                s.valueRow(more, c.getString(R.string.xd_mac_settings), c.getString(R.string.xd_dev_mac_options), "", () -> MacSettings.open(c));
            }
        });
    }

    private boolean termuxInstalled() {
        try {
            c.getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Add device › Linux: Termux, its permission, then xdesk setup (proot) or xdesk add (chroot). */
    private void linuxSetUp() {
        live(c.getString(R.string.xd_dev_type_linux), content -> {
            LinearLayout steps = s.section(content, c.getString(R.string.xd_dev_linux_steps));
            boolean termux = termuxInstalled();
            if (!termux) {
                s.valueRow(steps, c.getString(R.string.xd_dev_linux_install), c.getString(R.string.xd_dev_linux_install_note), "", () -> {
                    try {
                        c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) { }
                });
            } else {
                s.infoRow(steps, c.getString(R.string.xd_dev_linux_install), null, c.getString(R.string.xd_dev_done));
            }
            copyRow(steps, c.getString(R.string.xd_dev_linux_setup), c.getString(R.string.xd_dev_linux_setup_note), "xdesk setup");
            copyRow(steps, c.getString(R.string.xd_dev_linux_chroot), c.getString(R.string.xd_dev_linux_chroot_note),
                    "xdesk add NAME 'CMD'");
            String hint = devices.termuxHint();
            s.valueRow(steps, c.getString(R.string.xd_dev_linux_reload), hint != null ? hint : c.getString(R.string.xd_dev_linux_reload_note), "", () -> {
                Devices.Actions a = devices.actions();
                if (a != null) a.setUpLinux();
            });
            LinearLayout found = s.section(content, c.getString(R.string.xd_dev_linux_found));
            for (Devices.Device d : devices.external())
                if (d.type == Devices.Type.LINUX) s.infoRow(found, d.name, stateText(d), null);
        });
    }

    private void copyRow(LinearLayout card, String label, String note, final String command) {
        s.valueRow(card, label, note + "\n" + command, c.getString(R.string.xd_dev_copy), () -> {
            ClipboardManager cm = c.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("xdesk", command));
            Toast.makeText(c, R.string.xd_dev_copied, Toast.LENGTH_SHORT).show();
        });
    }
}
