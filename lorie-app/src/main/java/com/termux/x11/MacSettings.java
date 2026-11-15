package com.termux.x11;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacPairing;
import com.termux.x11.mac.MacStore;
import com.termux.x11.mac.PairLink;
import com.termux.x11.mac.PairScannerActivity;

/**
 * XDesk settings › Mac: the paired Macs (remove, "fit to screen", "Mac keyboard") and "Add Mac" by address and the
 * 12-digit code Sill shows on the Mac (menu bar › Pair iPhone or iPad…), by scanning the pairing window's QR code,
 * or by a tapped sill://pair link (MacSettings.fromLink). Opened with MacSettings.open(activity).
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

    /** A sill://pair link (tapped, or read from its QR code by a camera app): confirm the Mac, then pair. */
    static void fromLink(Context c, String raw) {
        new MacSettings(c).offer(raw);
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
        s.valueRow(add, c.getString(R.string.xd_mac_scan), c.getString(R.string.xd_mac_scan_note), "", this::askScan);
        for (final MacStore.Mac m : MacStore.all(c)) {
            LinearLayout card = s.section(content, m.name);
            s.infoRow(card, c.getString(R.string.xd_mac_address), null, m.address());
            // Each switch writes its own option over what is saved now: the rows keep the Mac as it was when shown
            s.switchRow(card, c.getString(R.string.xd_mac_fit), c.getString(R.string.xd_mac_fit_note), m.fit, on -> {
                MacStore.Mac now = saved(m);
                MacStore.setOptions(c, m.id, on, now.macKeyboard, now.phoneIme);
                MacClients.savedChanged(c);
            });
            s.switchRow(card, c.getString(R.string.xd_mac_keyboard), c.getString(R.string.xd_mac_keyboard_note), m.macKeyboard, on -> {
                MacStore.Mac now = saved(m);
                MacStore.setOptions(c, m.id, now.fit, on, now.phoneIme);
                MacClients.savedChanged(c);
            });
            s.switchRow(card, c.getString(R.string.xd_mac_phone_ime), c.getString(R.string.xd_mac_phone_ime_note), m.phoneIme, on -> {
                MacStore.Mac now = saved(m);
                MacStore.setOptions(c, m.id, now.fit, now.macKeyboard, on);
                MacClients.savedChanged(c);
            });
            // Sill's own settings for this Mac (quality, frame rate, sound, ...) have a page of their own
            s.valueRow(card, c.getString(R.string.xd_mac_stream), c.getString(R.string.xd_mac_stream_note), "", () -> MacStreamSettings.open(c, m.id));
            s.dangerRow(content, c.getString(R.string.xd_mac_remove, m.name), c.getString(R.string.xd_mac_remove_note), () ->
                s.confirm(c.getString(R.string.xd_mac_remove, m.name), c.getString(R.string.xd_mac_remove_note), c.getString(R.string.xd_mac_remove_action), () -> {
                    MacStore.remove(c, m.id);
                    MacClients.savedChanged(c);
                    refresh();
                }));
        }
    }

    private MacStore.Mac saved(MacStore.Mac shown) {
        MacStore.Mac now = MacStore.find(c, shown.id);
        return now != null ? now : shown;
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

    /** The code step, with "Ask the Mac for a code" beside Pair: the ask learns the Mac's key, the pair pins it. */
    private void askCode(final String address) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText field = new EditText(c);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setSingleLine(true);
        field.setTextColor(Style.TEXT);
        field.setHintTextColor(Style.TEXT_DIM);
        field.setTextSize(Style.TEXT_BODY + 2);
        field.setGravity(Gravity.CENTER);
        field.setHint("0000 0000 0000");
        field.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS));
        box.addView(field, new LinearLayout.LayoutParams(-1, Style.dp(c, Style.TARGET + 4)));
        final TextView status = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL);
        status.setPadding(0, Style.dp(c, 8), 0, 0);
        box.addView(status);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        final TextView ask = s.pill(c.getString(R.string.xd_mac_ask), Style.SURFACE_RAISED);
        final TextView go = s.pill(c.getString(R.string.xd_mac_pair), Style.ACCENT);
        row.addView(ask, pillParams());
        row.addView(go, pillParams());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Style.dp(c, 12);
        box.addView(row, rp);
        final Dialog[] d = new Dialog[1];
        final boolean[] submitted = new boolean[1];
        // The Mac's key as the ask saw it (null unless the Mac answered it shows a code): pair against that Mac
        final byte[][] seen = new byte[1][];
        final Runnable submit = () -> {
            String code = field.getText().toString().trim();
            if (code.isEmpty()) return;
            try {
                MacPairing.checkCode(code);
            } catch (IllegalArgumentException e) {
                toast(c.getString(R.string.xd_mac_bad_code));
                return;
            }
            submitted[0] = true;
            d[0].dismiss();
            pair(address, code, seen[0]);
        };
        go.setOnClickListener(v -> {
            Style.tick(v);
            submit.run();
        });
        field.setOnEditorActionListener((v, a, e) -> {
            submit.run();
            return true;
        });
        ask.setOnClickListener(v -> {
            Style.tick(v);
            status.setText(c.getString(R.string.xd_mac_ask_asking));
            new Thread(() -> {
                final MacPairing.Ask answer;
                try {
                    answer = MacPairing.ask(address);
                } catch (Exception e) {
                    ui.post(() -> {
                        if (d[0].isShowing() && !submitted[0]) status.setText(c.getString(R.string.xd_mac_ask_failed));
                    });
                    return;
                }
                final String text;
                if ("shown".equals(answer.reason)) {
                    seen[0] = answer.pin;
                    text = c.getString(R.string.xd_mac_ask_shown);
                } else if ("locked".equals(answer.reason)) {
                    text = c.getString(R.string.xd_mac_ask_locked);
                } else if ("busy".equals(answer.reason)) {
                    text = c.getString(R.string.xd_mac_ask_busy);
                } else {
                    text = c.getString(R.string.xd_mac_ask_denied);
                }
                ui.post(() -> {
                    if (d[0].isShowing() && !submitted[0]) status.setText(text);
                });
            }, "mac-ask").start();
        });
        d[0] = s.sheet(c.getString(R.string.xd_mac_code), c.getString(R.string.xd_mac_code_note), box);
        d[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        field.requestFocus();
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Style.dp(c, Style.TARGET - 4));
        p.leftMargin = Style.dp(c, 8);
        return p;
    }

    /** "Scan QR code": the scanner hands the link back through PairScannerActivity's pending receiver. */
    private void askScan() {
        if (!(c instanceof Activity)) {
            toast(c.getString(R.string.xd_mac_scan_unavailable));
            return;
        }
        try {
            if (!PairScannerActivity.start((Activity) c, this::offer)) toast(c.getString(R.string.xd_mac_scan_busy));
        } catch (RuntimeException e) {
            toast(c.getString(R.string.xd_mac_scan_unavailable));
        }
    }

    /** A pairing link, from the scanner or a tapped sill://pair link: show what it names, then pair. */
    private void offer(String raw) {
        // A scan result can land after the page's window is gone (the scanner ran while the activity finished):
        // never show a dialog on a dead window. Nothing is parsed yet, so there is no secret to clear
        if (c instanceof Activity && (((Activity) c).isFinishing() || ((Activity) c).isDestroyed())) return;
        final PairLink link;
        try {
            link = PairLink.parse(raw);
        } catch (IllegalArgumentException e) {
            toast(c.getString(R.string.xd_mac_bad_link));
            return;
        }
        if (link.addresses.isEmpty()) {
            link.clearSecret();
            toast(c.getString(R.string.xd_mac_link_no_address));
            return;
        }
        StringBuilder message = new StringBuilder(link.name).append("\n");
        for (PairLink.Address a : link.addresses) message.append("\n").append(a.display(link.port));
        message.append("\n\n").append(c.getString(R.string.xd_mac_link_trust));
        // The secret is a credential: clear it when the sheet closes without pairing (pair clears it after pairing)
        final boolean[] confirmed = new boolean[1];
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        final Dialog[] d = new Dialog[1];
        TextView cancel = s.pill(c.getString(R.string.xd_cancel), Style.SURFACE_RAISED);
        cancel.setOnClickListener(v -> d[0].dismiss());
        row.addView(cancel, pillParams());
        TextView go = s.pill(c.getString(R.string.xd_mac_pair), Style.ACCENT);
        go.setOnClickListener(v -> {
            Style.tick(v);
            confirmed[0] = true;   // before dismiss: the dismiss listener must see the pairing already taken over
            d[0].dismiss();
            pairLink(link);
        });
        row.addView(go, pillParams());
        d[0] = s.sheet(c.getString(R.string.xd_mac_link_title), message.toString(), row);
        d[0].setOnDismissListener(x -> {
            if (!confirmed[0]) link.clearSecret();
        });
    }

    private void pair(final String address, final String code) {
        pair(address, code, null);
    }

    private void pair(final String address, final String code, final byte[] seenPin) {
        toast(c.getString(R.string.xd_mac_pairing));
        new Thread(() -> {
            String message;
            try {
                MacStore.Mac m = MacPairing.pair(c, address, code, seenPin);
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

    private void pairLink(final PairLink link) {
        toast(c.getString(R.string.xd_mac_pairing));
        new Thread(() -> {
            String message;
            try {
                MacStore.Mac m = MacPairing.pairLink(c, link);
                message = c.getString(R.string.xd_mac_paired, m.name);
                ui.post(() -> {
                    MacClients.savedChanged(c);
                    refresh();
                });
            } catch (Exception e) {
                String reason = MacPairing.refusal(e);
                if ("busy".equals(reason)) message = c.getString(R.string.xd_mac_busy);
                else message = c.getString(R.string.xd_mac_link_failed, String.valueOf(e.getMessage()));
            }
            final String m = message;
            ui.post(() -> toast(m));
        }, "mac-pair").start();
    }

    private void toast(String t) {
        Toast.makeText(c, t, Toast.LENGTH_LONG).show();
    }
}
