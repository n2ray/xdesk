package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacPairing;
import com.termux.x11.mac.MacStore;

/**
 * XDesk settings › Mac: the paired Macs (remove, "fit to screen", "Mac keyboard") and "Add Mac" by address and the
 * 12-digit code Sill shows on the Mac (menu bar › Pair iPhone or iPad…). Opened with MacSettings.open(activity).
 */
final class MacSettings {
    private final Context c;
    private final SettingsUi s;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Dialog page;

    private MacSettings(Context c) {
        this.c = c;
        s = new SettingsUi(c);
    }

    /** The one call for XDesk's settings list. */
    static Dialog open(Context c) {
        MacSettings m = new MacSettings(c);
        m.show();
        return m.page;
    }

    /** Straight into pairing a new Mac (Settings › Connect device › Add device › Mac, DEVICES.md §5). */
    static void add(Context c) {
        new MacSettings(c).askAddress();
    }

    private void show() {
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        fill(content);
        page = s.page(c.getString(R.string.xd_mac_settings), content);
    }

    private void refresh() {
        if (page == null || !page.isShowing()) {
            Devices.get(c).touch();
            return;
        }
        Devices.get(c).touch();
        page.dismiss();
        show();
    }

    private void fill(LinearLayout content) {
        LinearLayout add = s.section(content, c.getString(R.string.xd_mac_settings_note));
        s.valueRow(add, c.getString(R.string.xd_mac_add), c.getString(R.string.xd_mac_add_note), "", this::askAddress);
        for (final MacStore.Mac m : MacStore.all(c)) {
            LinearLayout card = s.section(content, m.name);
            s.infoRow(card, c.getString(R.string.xd_mac_address), null, m.address());
            s.switchRow(card, c.getString(R.string.xd_mac_fit), c.getString(R.string.xd_mac_fit_note), m.fit, on -> {
                MacStore.setOptions(c, m.id, on, m.macKeyboard);
                MacClients.savedChanged(c);
            });
            s.switchRow(card, c.getString(R.string.xd_mac_keyboard), c.getString(R.string.xd_mac_keyboard_note), m.macKeyboard, on -> {
                MacStore.setOptions(c, m.id, m.fit, on);
                MacClients.savedChanged(c);
            });
            s.dangerRow(content, c.getString(R.string.xd_mac_remove, m.name), c.getString(R.string.xd_mac_remove_note), () ->
                s.confirm(c.getString(R.string.xd_mac_remove, m.name), c.getString(R.string.xd_mac_remove_note), c.getString(R.string.xd_mac_remove_action), () -> {
                    MacStore.remove(c, m.id);
                    MacClients.savedChanged(c);
                    refresh();
                }));
        }
    }

    private void askAddress() {
        s.input(c.getString(R.string.xd_mac_add), c.getString(R.string.xd_mac_address_note), "100.64.0.1:7455", null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, c.getString(R.string.xd_mac_next), address -> {
                    try {
                        MacPairing.parse(address);
                    } catch (IllegalArgumentException e) {
                        toast(c.getString(R.string.xd_mac_bad_address));
                        return;
                    }
                    askCode(address);
                }, null);
    }

    private void askCode(final String address) {
        s.input(c.getString(R.string.xd_mac_code), c.getString(R.string.xd_mac_code_note), "0000 0000 0000", null,
                InputType.TYPE_CLASS_NUMBER, c.getString(R.string.xd_mac_pair), code -> {
                    try {
                        MacPairing.checkCode(code);
                    } catch (IllegalArgumentException e) {
                        toast(c.getString(R.string.xd_mac_bad_code));
                        return;
                    }
                    pair(address, code);
                }, null);
    }

    private void pair(final String address, final String code) {
        toast(c.getString(R.string.xd_mac_pairing));
        new Thread(() -> {
            String message;
            try {
                MacStore.Mac m = MacPairing.pair(c, address, code);
                message = c.getString(R.string.xd_mac_paired, m.name);
                ui.post(() -> {
                    MacClients.savedChanged(c);
                    refresh();
                });
            } catch (Exception e) {
                String reason = MacPairing.refusal(e);
                if ("code".equals(reason)) message = c.getString(R.string.xd_mac_wrong_code);
                else if ("closed".equals(reason) || "expired".equals(reason) || "stopped".equals(reason))
                    message = c.getString(R.string.xd_mac_no_window);
                else if ("busy".equals(reason)) message = c.getString(R.string.xd_mac_busy);
                else message = c.getString(R.string.xd_mac_pair_failed, String.valueOf(e.getMessage()));
            }
            final String m = message;
            ui.post(() -> toast(m));
        }, "mac-pair").start();
    }

    private void toast(String t) {
        Toast.makeText(c, t, Toast.LENGTH_LONG).show();
    }
}
