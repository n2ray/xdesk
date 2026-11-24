// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.text.InputType;
import android.view.Display;
import android.widget.LinearLayout;

import com.termux.x11.app.R;
import com.termux.x11.screenshare.ScreenSharePasswords;
import com.termux.x11.screenshare.ScreenShareStore;
import com.termux.x11.screenshare.ScreenShareSizing;

/**
 * A Mac over its own Screen Sharing: added with its address and account (XDesk's input sheets, one after the other),
 * then its device page in XDesk's own look, as a Sill Mac's (owner, 24 November: it was a plain Android dialog).
 * Connection (address, account, name), Desktop scaling (native, 1.33×, 2×, and what that gives on the screen attached
 * now), the kept password, testing, removal. Each change is saved at once. The password is asked at each connection
 * (and kept, encrypted, only if the user chooses so at the login, ScreenSharePasswords); here it can be forgotten.
 */
final class ScreenShareSettings {
    private ScreenShareSettings() { }

    private static final int URI = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
    private static final int PERSON = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME;

    /** Add device › Mac › Screen Sharing: the address, then the account; then the new Mac's page. */
    static void add(final Context c) {
        final SettingsUi s = new SettingsUi(c);
        s.input(c.getString(R.string.xd_ss_add_title), c.getString(R.string.xd_ss_add_note), "192.168.1.10", "", URI,
            c.getString(R.string.xd_ss_next), host -> {
                if (host.isEmpty()) return false;
                s.input(c.getString(R.string.xd_ss_account), null, "", "", PERSON, c.getString(R.string.xd_ss_add), account -> {
                    if (account.isEmpty()) return false;
                    String id = ScreenShareStore.save(c, null, "", host, account, false, false, ScreenShareSizing.NATIVE);
                    changed(c);
                    edit(c, id);
                    return true;
                }, null);
                return true;
            }, null);
    }

    /** The Mac's page; it goes when the Mac is removed. */
    static void edit(final Context c, final String id) {
        if (id == null) {
            add(c);
            return;
        }
        final ScreenShareStore.Host first = ScreenShareStore.find(c, id);
        if (first == null) return;
        final SettingsUi s = new SettingsUi(c);
        final LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] page = new Dialog[1];
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            content.removeAllViews();
            ScreenShareStore.Host h = ScreenShareStore.find(c, id);
            if (h == null) {
                if (page[0] != null) page[0].dismiss();
                return;
            }
            fillPage(c, s, content, h, fill[0], () -> { if (page[0] != null) page[0].dismiss(); });
        };
        fill[0].run();
        page[0] = s.page(first.label(), content);
    }

    private static void fillPage(final Context c, final SettingsUi s, LinearLayout content, final ScreenShareStore.Host h,
                                 final Runnable refill, final Runnable close) {
        String unset = c.getString(R.string.xd_ss_not_set);

        // Connection: each value opens XDesk's input sheet. A new address or account forgets a kept password
        LinearLayout conn = s.section(content, c.getString(R.string.xd_ss_section_connection));
        s.valueRow(conn, c.getString(R.string.xd_ss_host_short), null, h.host, () ->
            s.input(c.getString(R.string.xd_ss_host), null, "192.168.1.10", h.host, URI, c.getString(R.string.xd_ss_save), v -> {
                if (v.isEmpty()) return false;
                if (!v.equals(h.host)) ScreenSharePasswords.forget(c, h.id);
                save(c, h, v, h.account, h.name, h.fixed1080, h.measure, h.scale);
                refill.run();
                return true;
            }, null));
        s.valueRow(conn, c.getString(R.string.xd_ss_account_short), null, h.account, () ->
            s.input(c.getString(R.string.xd_ss_account), null, "", h.account, PERSON, c.getString(R.string.xd_ss_save), v -> {
                if (v.isEmpty()) return false;
                if (!v.equals(h.account)) ScreenSharePasswords.forget(c, h.id);
                save(c, h, h.host, v, h.name, h.fixed1080, h.measure, h.scale);
                refill.run();
                return true;
            }, null));
        s.valueRow(conn, c.getString(R.string.xd_ss_name_short), null, h.name != null && !h.name.isEmpty() ? h.name : unset, () ->
            s.input(c.getString(R.string.xd_ss_name), null, h.host, h.name != null ? h.name : "", InputType.TYPE_CLASS_TEXT,
                c.getString(R.string.xd_ss_save), v -> {
                    save(c, h, h.host, h.account, v, h.fixed1080, h.measure, h.scale);
                    refill.run();
                    return true;
                }, null));

        // Desktop scaling: three short choices, and what the chosen one gives on the screen attached now
        LinearLayout scale = s.section(content, c.getString(R.string.xd_ss_scale));
        String[] tiers = {c.getString(R.string.xd_ss_scale_native), c.getString(R.string.xd_ss_scale_moderate),
            c.getString(R.string.xd_ss_scale_large)};
        s.segmentedRow(scale, c.getString(R.string.xd_ss_scale), scaleLine(c, h, h.scale, h.fixed1080), tiers, h.scale, which -> {
            save(c, h, h.host, h.account, h.name, h.fixed1080, h.measure, which);
            refill.run();
        });
        s.switchRow(scale, c.getString(R.string.xd_ss_fixed1080), null, h.fixed1080, on -> {
            save(c, h, h.host, h.account, h.name, on, h.measure, h.scale);
            refill.run();
        });

        // The password: kept or not; a kept one can be forgotten here
        LinearLayout pass = s.section(content, c.getString(R.string.xd_ss_section_password));
        if (ScreenSharePasswords.has(c, h.id)) {
            s.infoRow(pass, c.getString(R.string.xd_ss_password_saved), null, null);
            s.linkRow(pass, c.getString(R.string.xd_ss_forget), null, "", () -> {
                ScreenSharePasswords.forget(c, h.id);
                refill.run();
            });
        } else {
            s.infoRow(pass, c.getString(R.string.xd_ss_password_none), null, null);
        }

        LinearLayout test = s.section(content, c.getString(R.string.xd_ss_section_test));
        s.switchRow(test, c.getString(R.string.xd_ss_measure), null, h.measure, on -> {
            save(c, h, h.host, h.account, h.name, h.fixed1080, on, h.scale);
            refill.run();
        });

        LinearLayout remove = s.card(content);
        ((LinearLayout.LayoutParams) remove.getLayoutParams()).topMargin = Style.dp(c, 12);
        s.dangerRowIn(remove, c.getString(R.string.xd_ss_remove_row), null, () ->
            s.confirm(c.getString(R.string.xd_ss_remove_title, h.label()), c.getString(R.string.xd_ss_remove_message),
                c.getString(R.string.xd_ss_remove), SettingsUi.LEVEL_DANGER, () -> {
                    ScreenShareSource src = ScreenShareSource.get();
                    if (src != null) src.disconnect(h.id);
                    ScreenSharePasswords.forget(c, h.id);
                    ScreenShareStore.remove(c, h.id);
                    changed(c);
                    close.run();
                }));
    }

    private static void save(Context c, ScreenShareStore.Host h, String host, String account, String name,
                             boolean fixed, boolean measure, int scale) {
        ScreenShareStore.save(c, h.id, name != null ? name.trim() : "", host, account, fixed, measure, scale);
        changed(c);
    }

    /**
     * What a tier gives, as numbers: on the external screen attached now ("桌面 1280×800 · 视频 2560×1600 · HiDPI"), or
     * on an assumed 1920×1080 one; and what the last connection really got, when the Mac gave another size than asked.
     */
    static String scaleLine(Context c, ScreenShareStore.Host old, int tier, boolean fixed) {
        Display d = TargetDisplay.real(c.getSystemService(DisplayManager.class));
        ScreenShareSizing z = fixed ? ScreenShareSizing.fixed1080()
            : d != null ? ScreenShareSource.sizing(c, d.getDisplayId(), tier) : ScreenShareSizing.of(tier, 0, 0);
        String how = z.hidpi ? c.getString(R.string.xd_ss_scale_hidpi)
            : z.upscaled() ? c.getString(R.string.xd_ss_scale_upscaled) : null;
        StringBuilder s = new StringBuilder(c.getString(R.string.xd_ss_scale_result, z.desktopW + "×" + z.desktopH,
            z.videoW + "×" + z.videoH));
        if (how != null) s.append(" · ").append(how);
        if (z.limited) s.append(" · ").append(c.getString(R.string.xd_ss_scale_limited));
        if (fixed) return s.toString();  // the test baseline follows no screen
        if (d != null) s.append("\n").append(c.getString(R.string.xd_ss_scale_on, z.screenW + "×" + z.screenH));
        else s.append("\n").append(c.getString(R.string.xd_ss_scale_no_screen));
        int[] r = old != null ? ScreenShareStore.result(c, old.id) : null;
        // The last connection: [tier, desktop w, h, video asked w, h, hidpi, video got w, h]
        if (r != null && (r[6] != r[3] || r[7] != r[4]))
            s.append("\n").append(c.getString(R.string.xd_ss_scale_got, r[3] + "×" + r[4], r[6] + "×" + r[7]));
        return s.toString();
    }

    private static void changed(Context c) {
        ScreenShareSource src = ScreenShareSource.get();
        if (src != null) src.hostsChanged();
        Devices.get(c).touch();
    }
}
