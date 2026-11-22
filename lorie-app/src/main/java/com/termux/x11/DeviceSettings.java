package com.termux.x11;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacStore;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Settings › Devices (DEVICES.md §5): the external devices with their state, each one's own page, and Add device ›
 * type: for Linux, picking from the environments the xdesk command reports; for a Mac, pairing by QR code or by its
 * address. A device's page is one level: a Mac has its quick quality, state and connection, picture and sound, its
 * keyboard choices, fit to screen, its saved addresses and its removal; a Linux environment its state and Stop,
 * its desktops, its keyboard and its removal. Mac pairing itself is in MacSettings. UI thread.
 */
final class DeviceSettings {
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/";
    private static final String XDESK = "/data/data/com.termux/files/usr/bin/xdesk";
    /** INPUT.md §5.2: the layouts offered for "Keyboard layout (X side)", as xdesk ime layout names them. */
    private static final String[] X_LAYOUTS = {"auto", "us", "gb", "de", "de-nodeadkeys", "ch-de_nodeadkeys",
            "fr", "es", "it", "pt", "nl", "se"};
    private static final int[] X_LAYOUT_NAMES = {R.string.xd_linux_x_layout_auto, R.string.xd_linux_x_layout_us,
            R.string.xd_linux_x_layout_gb, R.string.xd_linux_x_layout_de, R.string.xd_linux_x_layout_de_nodeadkeys,
            R.string.xd_linux_x_layout_ch_de_nodeadkeys, R.string.xd_linux_x_layout_fr, R.string.xd_linux_x_layout_es,
            R.string.xd_linux_x_layout_it, R.string.xd_linux_x_layout_pt, R.string.xd_linux_x_layout_nl,
            R.string.xd_linux_x_layout_se};

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

    /** One device's page, from that device's things (ItemCard, Switchboard). */
    static void openDevice(Context c, String id) {
        new DeviceSettings(c).device(id);
    }

    /** A device type's own set-up flow (Add device › type, or the launcher's "Set up this device"). */
    static void setUp(Context c, Devices.Type type) {
        DeviceSettings d = new DeviceSettings(c);
        if (type == Devices.Type.MAC) MacSettings.add(c);
        else if (type == Devices.Type.LINUX) d.linuxSetUp();
    }

    /** A page whose content is filled again whenever the devices change, while it shows. False: the device is gone. */
    private interface Filler {
        boolean fill(LinearLayout content);
    }

    private Dialog live(String title, final Filler filler) {
        final LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] page = new Dialog[1];
        final boolean[] alive = {true};
        final Runnable again = () -> {
            content.removeAllViews();
            alive[0] = filler.fill(content);
            // Removed while its page was open: the page goes with it, nothing says "removed" over an empty page
            if (!alive[0] && page[0] != null) page[0].dismiss();
        };
        alive[0] = filler.fill(content);
        devices.addListener(again);
        page[0] = s.page(title, content);
        page[0].setOnDismissListener(x -> devices.removeListener(again));
        if (!alive[0]) page[0].dismiss();
        return page[0];
    }

    private String stateText(Devices.Device d) {
        String state = c.getString(Devices.stateLabel(d));
        return d.note != null && !d.note.isEmpty() ? state + " · " + d.note : state;
    }

    private Dialog list() {
        return live(c.getString(R.string.xd_dev_connect_devices), content -> {
            // SetDevices: the phone first, then the external devices, then Add device in the same card
            LinearLayout phone = s.card(content);
            s.infoRow(phone, c.getString(R.string.xd_dev_phone), c.getString(R.string.xd_dev_phone_note), null);
            LinearLayout known = s.section(content, c.getString(R.string.xd_dev_external));
            for (final Devices.Device d : devices.external())
                s.linkRow(known, d.name, stateText(d), d.state == Devices.State.CONNECTED ? Style.OK : Style.TEXT_DIM,
                    "", () -> device(d.id));
            // A first start has none: say so rather than show an empty card
            if (devices.external().isEmpty()) s.infoRow(known, c.getString(R.string.xd_dev_none), c.getString(R.string.xd_dev_none_note), null);
            // The built-in Linux is not one of the devices xdesk lists: its own row while it is installed
            if (BuiltinLinux.get(c).installed())
                s.linkRow(known, c.getString(R.string.xd_bl_title), c.getString(R.string.xd_bl_row_note), "", () -> BuiltinLinuxPage.manage(c));
            s.accentRow(known, c.getString(R.string.xd_dev_add), null, this::pickType);
            return true;
        });
    }

    private String typeName(Devices.Type t) {
        switch (t) {
            case LINUX: return "Linux";
            case MAC: return "Mac";
            default: return "";
        }
    }

    /**
     * SetDevices' sheet, two levels (DEVICES.md §5): first the kind of computer, Linux or Mac; then how to reach it.
     * Linux: Termux (itself, and the proot / chroot environments its xdesk knows) or XDesk's built-in Linux. Mac:
     * a Sill connection (windows and apps, Sill installed on the Mac) or the Mac's own Screen Sharing (the whole
     * desktop, nothing to install). Each choice opens its own set-up.
     */
    private void pickType() {
        sheet(c.getString(R.string.xd_dev_add_pick),
            new Choice(c.getString(R.string.xd_dev_type_linux), c.getString(R.string.xd_dev_type_linux_note), this::pickLinux),
            new Choice(c.getString(R.string.xd_dev_type_mac), c.getString(R.string.xd_dev_type_mac_note), this::pickMac));
    }

    /** Add device › Linux: Termux (native, proot, chroot) or the built-in Linux. */
    private void pickLinux() {
        sheet(c.getString(R.string.xd_dev_type_linux),
            new Choice(c.getString(R.string.xd_dev_linux_termux), c.getString(R.string.xd_dev_linux_termux_note), this::linuxSetUp),
            new Choice(c.getString(R.string.xd_bl_title), c.getString(R.string.xd_bl_type_note), () -> BuiltinLinuxPage.add(c)));
    }

    /** Add device › Mac: a Sill connection or the Mac's own Screen Sharing. */
    private void pickMac() {
        sheet(c.getString(R.string.xd_dev_type_mac),
            new Choice(c.getString(R.string.xd_dev_mac_sill), c.getString(R.string.xd_dev_mac_sill_note), this::pickMacAdd),
            new Choice(c.getString(R.string.xd_ss_type), c.getString(R.string.xd_ss_type_note), () -> ScreenShareSettings.add(c)));
    }

    private static final class Choice {
        final String title, note;
        final Runnable action;

        Choice(String title, String note, Runnable action) {
            this.title = title;
            this.note = note;
            this.action = action;
        }
    }

    /** A bottom sheet of choices; picking one closes it and runs that choice. */
    private void sheet(String heading, Choice... choices) {
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = s.card(content);
        final Dialog[] sheet = new Dialog[1];
        for (final Choice ch : choices) {
            s.linkRow(card, ch.title, ch.note, "", () -> {
                sheet[0].dismiss();
                ch.action.run();
            });
        }
        android.widget.TextView title = Style.label(c, heading, Style.TEXT, 17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, Style.dp(c, 4));
        sheet[0] = s.bottomSheet(title, content);
    }

    /** Add device › Mac: a QR code from Sill's pairing window, or the Mac's address and the code it shows. */
    private void pickMacAdd() {
        String[] options = {c.getString(R.string.xd_mac_scan), c.getString(R.string.xd_mac_add_address)};
        String note = c.getString(R.string.xd_mac_add_note) + "\n" + c.getString(R.string.xd_mac_scan_note);
        s.choice(c.getString(R.string.xd_mac_add), note, options, -1, which -> {
            if (which == 0) MacSettings.scan(c);
            else MacSettings.add(c);
        });
    }

    /** One device's page. */
    private void device(final String id) {
        Devices.Device first = devices.find(id);
        if (first == null) return;
        if (first.type == Devices.Type.MAC) {
            macDevicePage(first.name, id);
            return;
        }
        if (first.type == Devices.Type.SCREEN) {
            ScreenShareSettings.edit(c, id.substring(Devices.SCREEN.length()));
            return;
        }
        live(first.name, content -> fillLinuxDevice(content, id));
    }

    private boolean fillLinuxDevice(LinearLayout content, final String id) {
        final Devices.Device d = devices.find(id);
        if (d == null) return false;
        final String env = id.substring(Devices.LINUX.length());
        // SetDeviceLinux: the state with the green dot, and what is on the X server, in the first card
        LinearLayout state = s.card(content);
        final boolean running = d.state == Devices.State.CONNECTED || d.state == Devices.State.CONNECTING;
        String stateNote = d.note;
        if (running && devices.runningSession() != null) stateNote = c.getString(R.string.xd_dev_x_session, devices.runningSession());
        else if (d.note == null && !env.equals(Devices.TERMUX)) stateNote = c.getString(R.string.xd_dev_in_termux);
        s.dotRow(state, running, c.getString(Devices.stateLabel(d)), stateNote);
        // Local: nothing to connect. Stop ends the X server all Linux devices share, after asking
        if (d.canStop && !d.busy)
            s.stopRowIn(state, c.getString(R.string.xd_dev_stop), c.getString(R.string.xd_dev_stop_note), () -> devices.stop(id));
        LinearLayout sessions = s.section(content, c.getString(R.string.xd_linux_desktops));
        java.util.List<String> own = devices.sessionsOf(id);
        if (own.isEmpty()) s.infoRow(sessions, c.getString(R.string.xd_dev_linux_no_session), null, null);
        // Each desktop's start script is the user's (CLI.md §5): a tap opens it in an editor in Termux
        for (final String name : own)
            s.linkRow(sessions, name, c.getString(R.string.xd_linux_session_edit_note, name),
                    name.equals(devices.runningSession()) ? c.getString(R.string.xd_dev_state_running)
                    : name.equals(devices.defaultSession()) ? c.getString(R.string.xd_chooser_default) : "",
                    () -> {
                        if (!TermuxCommand.editSession(c, name))
                            android.widget.Toast.makeText(c, R.string.xd_linux_session_edit_failed, android.widget.Toast.LENGTH_LONG).show();
                    });
        // INPUT.md §5.2: with an input method on the X side a keyboard on the phone types as keys and X composes.
        // The switch is this device's; the input method is the one X server's, so its state reads the same on all
        LinearLayout keys = s.section(content, c.getString(R.string.xd_linux_keyboard));
        followGlobalRow(keys, c.getString(R.string.xd_linux_phone_ime),
                c.getString(devices.xIme() ? R.string.xd_linux_x_ime_on : R.string.xd_linux_x_ime_off),
                devices.xIme() ? Style.OK : Style.TEXT_DIM,
                devices.linuxPhoneImeChoice(id), which -> devices.setLinuxPhoneIme(id, choice(which)));
        String layout = devices.xLayout();
        s.linkRow(keys, c.getString(R.string.xd_linux_x_layout), null,
                layout != null ? layoutName(layout) : c.getString(R.string.xd_linux_x_layout_auto),
                () -> pickXLayout(devices));
        // Removing a device only takes its tab. Termux and the environments are separate devices; a proot or chroot
        // runs inside Termux, so the Termux page says that removing Termux leaves them
        String removeNote = c.getString(env.equals(Devices.TERMUX)
                ? R.string.xd_dev_remove_termux_note : R.string.xd_dev_remove_linux_note);
        String removeRowNote = c.getString(env.equals(Devices.TERMUX)
                ? R.string.xd_dev_remove_termux_row_note : R.string.xd_dev_remove_linux_row_note);
        // Its desktop or programs on the screen: they stop first, or they would run on with no device to stop them from
        // (owner, 2026-11-19). X busy with another environment only is left running
        final String confirmNote = running ? c.getString(R.string.xd_dev_remove_running) + "\n\n" + removeNote : removeNote;
        LinearLayout remove = s.card(content);
        ((android.widget.LinearLayout.LayoutParams) remove.getLayoutParams()).topMargin = Style.dp(c, 12);
        s.dangerRowIn(remove, c.getString(R.string.xd_dev_remove), removeRowNote, () ->
            s.confirm(c.getString(R.string.xd_dev_remove_title, d.name), confirmNote,
                c.getString(R.string.xd_dev_remove_action), SettingsUi.LEVEL_DANGER,
                () -> {
                    if (running) devices.stop(id);
                    devices.setLinuxAdded(env, false);
                }));
        return true;
    }

    /**
     * 跟随全局 / 开 / 关：a device's own choice over one of XDesk's global input settings. Null follows the global one,
     * which is the segment shown then, so the row says what is in force.
     */
    private void followGlobalRow(LinearLayout card, String label, String note, Boolean own, final IntConsumer onPick) {
        followGlobalRow(card, label, note, Style.TEXT_DIM, own, onPick);
    }

    private void followGlobalRow(LinearLayout card, String label, String note, int noteColor, Boolean own,
                                 final IntConsumer onPick) {
        String[] options = {c.getString(R.string.xd_follow_global), c.getString(R.string.xd_on), c.getString(R.string.xd_off)};
        int at = own == null ? 0 : own ? 1 : 2;
        s.segmentedRow(card, label, note, noteColor, options, at, onPick);
    }

    private Boolean choice(int which) {
        return which == 0 ? null : which == 1;
    }

    /** The quick quality row shown at the top of a Mac's device page; re-added on every fill of the page. */
    private MacModeRow macMode;

    /**
     * A Mac's page (SALL-SYNC.md "XDesk's own additions"): the quick quality modes at the top, the state with
     * connect, disconnect or pairing again, picture and sound, the keyboard choices, fit to screen, the addresses
     * MacStore keeps for it, and its removal. The mode row follows the Mac's settings while the page shows
     * (MacModeRow's own listener, detached on dismiss).
     */
    private Dialog macDevicePage(String name, final String id) {
        final LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] page = new Dialog[1];
        final boolean[] alive = {true};
        final Runnable[] refill = new Runnable[1];
        refill[0] = () -> {
            content.removeAllViews();
            alive[0] = fillMacDevice(content, id, refill[0]);
            if (!alive[0] && page[0] != null) page[0].dismiss();
        };
        alive[0] = fillMacDevice(content, id, refill[0]);
        devices.addListener(refill[0]);
        page[0] = s.page(name, content);
        page[0].setOnDismissListener(x -> {
            devices.removeListener(refill[0]);
            if (macMode != null) {
                macMode.detach();
                macMode = null;
            }
        });
        if (!alive[0]) page[0].dismiss();
        return page[0];
    }

    private boolean fillMacDevice(LinearLayout content, final String id, final Runnable refill) {
        final Devices.Device d = devices.find(id);
        if (d == null) return false;
        String macId = id.substring(Devices.MAC.length());
        final MacStore.Mac m = MacStore.find(c, macId);
        MacClient client = MacClients.get(c, macId);
        boolean needsPairing = client != null && client.needsPairing();
        if (macMode != null) {
            macMode.detach();
            macMode = null;
        }
        macMode = MacModeRow.add(c, s, macId, content, refill);
        // SetDeviceMac: the state with the green dot and Disconnect beside it, then Picture and sound
        LinearLayout state = s.card(content);
        if (d.state == Devices.State.CONNECTED && !d.busy) {
            s.dotActionRow(state, true, stateText(d), null, c.getString(R.string.xd_dev_disconnect), Style.STOP,
                () -> s.confirm(c.getString(R.string.xd_dev_disconnect_title, d.name),
                    c.getString(R.string.xd_dev_disconnect_mac_note), c.getString(R.string.xd_dev_disconnect),
                    SettingsUi.LEVEL_STOP, () -> devices.disconnect(id)));
        } else {
            s.dotRow(state, false, stateText(d), null);
            if (needsPairing) {
                // It removed XDesk or changed identity: pair with the saved address, or scan again (MacSettings.repair)
                s.linkRow(state, c.getString(R.string.xd_dev_pair_again), c.getString(R.string.xd_mac_repair_note), "",
                        () -> MacSettings.repair(c, macId));
            } else if (d.state != Devices.State.CONNECTING && d.canConnect(devices)) {
                s.linkRow(state, c.getString(R.string.xd_dev_connect), null, "", () -> devices.connect(id));
            }
        }
        s.linkRow(state, c.getString(R.string.xd_mac_stream), c.getString(R.string.xd_mac_stream_note), "",
                () -> MacStreamSettings.open(c, macId));
        if (m != null) {
            LinearLayout screen = s.section(content, c.getString(R.string.xd_mac_screen));
            s.switchRow(screen, c.getString(R.string.xd_mac_fit), c.getString(R.string.xd_mac_fit_note), m.fit,
                    on -> setFit(macId, on));
            LinearLayout keys = s.section(content, c.getString(R.string.xd_linux_keyboard));
            followGlobalRow(keys, c.getString(R.string.xd_translate_modifiers), null,
                    m.translateOverride, which -> setTranslate(macId, choice(which)));
            followGlobalRow(keys, c.getString(R.string.xd_mac_phone_ime), null,
                    m.phoneImeOverride, which -> setPhoneIme(macId, choice(which)));
            macAddresses(s.section(content, c.getString(R.string.xd_mac_addresses)), m);
            LinearLayout remove = s.card(content);
            ((android.widget.LinearLayout.LayoutParams) remove.getLayoutParams()).topMargin = Style.dp(c, 12);
            s.dangerRowIn(remove, c.getString(R.string.xd_dev_remove), c.getString(R.string.xd_mac_remove_row_note), () ->
                s.confirm(c.getString(R.string.xd_dev_remove_title, m.name), c.getString(R.string.xd_mac_remove_note),
                    c.getString(R.string.xd_dev_remove_action), SettingsUi.LEVEL_DANGER, () -> {
                        MacStore.remove(c, m.id);
                        MacClients.savedChanged(c);
                        devices.touch();
                    }));
        }
        return true;
    }

    /**
     * A Mac's own choices, each written over what is saved now: a switch tapped before the page was filled again
     * must not put back the other choice as it was when the page was drawn. Null follows the global setting.
     */
    private void setTranslate(String macId, Boolean translateOverride) {
        MacStore.Mac now = MacStore.find(c, macId);
        if (now == null) return;
        MacStore.setOptions(c, macId, now.fit, translateOverride, now.phoneImeOverride);
        MacClients.savedChanged(c);
        devices.touch();
    }

    private void setPhoneIme(String macId, Boolean phoneImeOverride) {
        MacStore.Mac now = MacStore.find(c, macId);
        if (now == null) return;
        MacStore.setOptions(c, macId, now.fit, now.translateOverride, phoneImeOverride);
        MacClients.savedChanged(c);
        devices.touch();
    }

    private void setFit(String macId, boolean fit) {
        MacStore.Mac now = MacStore.find(c, macId);
        if (now == null) return;
        MacStore.setOptions(c, macId, fit, now.translateOverride, now.phoneImeOverride);
        MacClients.savedChanged(c);
        devices.touch();
    }

    /**
     * What MacStore keeps for this Mac: the address it was paired at, the addresses its last signed list advertised
     * (with their kind), the VPN addresses remembered from older lists, and which one a session last connected at.
     */
    private void macAddresses(LinearLayout card, MacStore.Mac m) {
        MacStore.Address last = m.lastWorked();
        boolean savedLast = same(last, m.host, m.port);
        s.infoRow(card, c.getString(R.string.xd_mac_paired_address),
                savedLast ? c.getString(R.string.xd_mac_addr_last_worked) : null, m.address());
        boolean shown = savedLast;
        for (MacStore.Address a : m.advertised()) {
            shown |= same(last, a.host, a.port);
            addressRow(card, a, false, last);
        }
        for (MacStore.Remembered r : m.remembered()) {
            shown |= same(last, r.address.host, r.address.port);
            addressRow(card, r.address, true, last);
        }
        // The address a session last connected at was neither the saved one nor in either list: say it anyway
        if (last != null && !shown)
            s.infoRow(card, c.getString(R.string.xd_mac_addr_last_worked), addressKind(last.host, last.kind), last.display());
    }

    private static boolean same(MacStore.Address a, String host, int port) {
        return a != null && a.port == port && a.host.equalsIgnoreCase(host);
    }

    private void addressRow(LinearLayout card, MacStore.Address a, boolean remembered, MacStore.Address lastWorked) {
        String note = addressKind(a.host, a.kind);
        if (remembered) note = note + " · " + c.getString(R.string.xd_mac_addr_remembered);
        boolean last = same(lastWorked, a.host, a.port);
        s.infoRow(card, a.display(), note, last ? c.getString(R.string.xd_mac_addr_last_worked) : null);
    }

    /** The address kind as it is shown; a Tailscale address is named so whatever the list called it. */
    private String addressKind(String host, String kind) {
        if (tailscale(host)) return c.getString(R.string.xd_mac_addr_vpn_tailscale);
        if (MacStore.Address.LAN.equals(kind)) return c.getString(R.string.xd_mac_addr_lan);
        if (MacStore.Address.VPN.equals(kind)) return c.getString(R.string.xd_mac_addr_vpn);
        if (MacStore.Address.INTERNET.equals(kind)) return c.getString(R.string.xd_mac_addr_internet);
        return "";
    }

    /** A 100.64.0.0/10 address, a *.ts.net name or fd7a:115c:a1e0:: address is Tailscale's. */
    private static boolean tailscale(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".ts.net") || h.startsWith("fd7a:115c:a1e0:")) return true;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
            return first == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean termuxInstalled() {
        try {
            c.getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String envName(Devices.Env e) {
        return Devices.linuxName(c, Devices.LINUX + e.name);
    }

    private String envNote(Devices.Env e) {
        String kind = e.kind.equals(Devices.TERMUX) ? c.getString(R.string.xd_dev_env_termux) : e.kind.equals("proot") ? "proot"
                : e.kind.equals("chroot") ? "chroot" : e.name;
        String what = c.getString(e.apps ? R.string.xd_dev_env_apps : R.string.xd_dev_env_desktop_only);
        String note = e.sessions.isEmpty() ? kind + " · " + what : kind + " · " + what + " · " + String.join(", ", e.sessions);
        // A proot or chroot runs inside Termux: its own device, but Termux must stay installed
        if (!e.kind.equals(Devices.TERMUX)) note = note + " · " + c.getString(R.string.xd_dev_in_termux);
        return note;
    }

    /**
     * Add device › Linux: the environments the xdesk command reports, each added or removed as a device with a tap
     * (Termux itself always is one); then how to make xdesk know more: Termux, its permission, xdesk setup (proot)
     * or xdesk add (chroot).
     */
    private void linuxSetUp() {
        live(c.getString(R.string.xd_dev_type_linux), content -> {
            LinearLayout found = s.section(content, c.getString(R.string.xd_dev_linux_found));
            // Every environment lives in Termux: without it there is nothing to add yet, only the steps below
            if (!termuxInstalled()) s.infoRow(found, c.getString(R.string.xd_dev_linux_none), c.getString(R.string.xd_dev_linux_none_note), null);
            else for (final Devices.Env e : devices.environments()) {
                final boolean added = devices.isLinuxAdded(e.name);
                final String name = envName(e);
                // Adding only adds (DEVICES.md §5): one already added opens its device page, where it is managed and
                // removed
                s.valueRow(found, name, envNote(e),
                    c.getString(added ? R.string.xd_dev_env_added : R.string.xd_dev_env_add), () -> {
                        if (added) {
                            device(Devices.LINUX + e.name);
                            return;
                        }
                        devices.setLinuxAdded(e.name, true);
                        // Added: ask Termux what it has now (its permission first, the first time)
                        Devices.Actions a = devices.actions();
                        if (a != null) a.setUpLinux();
                    });
            }
            s.infoRow(found, c.getString(R.string.xd_dev_linux_how), c.getString(R.string.xd_dev_linux_how_note), null);
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
                s.valueRow(steps, c.getString(R.string.xd_termux_install_xdesk), c.getString(R.string.xd_termux_install_xdesk_note), "",
                        () -> TermuxCommand.copyInstallAndOpen(c));
            }
            copyRow(steps, c.getString(R.string.xd_dev_linux_setup), c.getString(R.string.xd_dev_linux_setup_note), "xdesk setup");
            copyRow(steps, c.getString(R.string.xd_dev_linux_chroot), c.getString(R.string.xd_dev_linux_chroot_note),
                    "xdesk add NAME 'CMD'");
            String hint = devices.termuxHint();
            // Running the command needs "Run commands in Termux"; without it the row says where to get it (PermInline)
            s.valueRow(steps, c.getString(R.string.xd_dev_linux_reload), hint != null ? hint : c.getString(R.string.xd_dev_linux_reload_note), "",
                Permissions.TERMUX.granted(c) ? null : Permissions.need(c, Permissions.TERMUX), this::openTermuxPermission,
                () -> {
                    Devices.Actions a = devices.actions();
                    if (a != null) a.setUpLinux();
                });
            return true;
        });
    }

    /** The layout's human name, or the layout name itself for one xdesk knows and XDesk does not. */
    private String layoutName(String layout) {
        for (int i = 1; i < X_LAYOUTS.length; i++)
            if (X_LAYOUTS[i].equals(layout)) return c.getString(X_LAYOUT_NAMES[i]);
        return layout;
    }

    /** INPUT.md §5.2: the remembered X side keyboard layout; choosing one runs xdesk ime layout, then reads the state again. */
    private void pickXLayout(final Devices d) {
        final String current = d.xLayout();
        int checked = 0;
        if (current != null)
            for (int i = 1; i < X_LAYOUTS.length; i++)
                if (X_LAYOUTS[i].equals(current)) checked = i;
        String[] names = new String[X_LAYOUTS.length];
        for (int i = 0; i < X_LAYOUTS.length; i++) names[i] = c.getString(X_LAYOUT_NAMES[i]);
        s.choice(c.getString(R.string.xd_linux_x_layout), c.getString(R.string.xd_linux_x_layout_note), names, checked, which -> {
            boolean sent = TermuxCommand.run(c, XDESK + " ime layout " + X_LAYOUTS[which], new TermuxCommand.Callback() {
                @Override public void onOutput(String stdout) {
                    // The command saved it and applied it: read xdesk status again so the row shows it
                    Devices.Actions a = d.actions();
                    if (a != null) a.setUpLinux();
                }
                @Override public void onFailure(String message) {
                    Toast.makeText(c, message, Toast.LENGTH_LONG).show();
                }
            });
            if (!sent) Toast.makeText(c, R.string.xd_termux_not_installed, Toast.LENGTH_LONG).show();
        });
    }

    private void copyRow(LinearLayout card, String label, String note, final String command) {
        s.valueRow(card, label, note + "\n" + command, c.getString(R.string.xd_dev_copy), () -> {
            ClipboardManager cm = c.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("xdesk", command));
            Toast.makeText(c, R.string.xd_dev_copied, Toast.LENGTH_SHORT).show();
        });
    }

    /** The Termux command permission's page: the permission centre asks the system; from elsewhere, the app's details. */
    private void openTermuxPermission() {
        if (c instanceof XDeskActivity) {
            ((XDeskActivity) c).showPermissionDetail(Permissions.TERMUX);
            return;
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }
}
