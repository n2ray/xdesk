// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;
import com.termux.x11.screenshare.ScreenSharePasswords;
import com.termux.x11.screenshare.ScreenShareStore;

/**
 * Add device › Mac (Screen Sharing): the Mac's address (local network or Tailscale), the account and how big a
 * screen to ask for. The password is asked at each connection (and kept, encrypted, only if the user chooses so at
 * the login, ScreenSharePasswords); here it can be forgotten. The same dialog edits or removes one.
 */
final class ScreenShareSettings {
    private ScreenShareSettings() { }

    static void add(Context c) {
        edit(c, null);
    }

    static void edit(final Context c, final String id) {
        final ScreenShareStore.Host old = id != null ? ScreenShareStore.find(c, id) : null;
        int pad = Style.dp(c, 20);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView note = new TextView(c);
        note.setText(R.string.xd_ss_add_note);
        box.addView(note);
        final EditText host = field(c, box, R.string.xd_ss_host, old != null ? old.host : "", InputType.TYPE_TEXT_VARIATION_URI);
        final EditText account = field(c, box, R.string.xd_ss_account, old != null ? old.account : "", InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        final EditText name = field(c, box, R.string.xd_ss_name, old != null ? old.name : "", 0);
        final CheckBox fixed = new CheckBox(c);
        fixed.setText(R.string.xd_ss_fixed1080);
        fixed.setChecked(old != null && old.fixed1080);
        box.addView(fixed);
        final CheckBox forget = new CheckBox(c);
        forget.setText(R.string.xd_ss_forget);
        if (old != null && ScreenSharePasswords.has(c, old.id)) box.addView(forget);
        final CheckBox measure = new CheckBox(c);
        measure.setText(R.string.xd_ss_measure);
        measure.setChecked(old != null && old.measure);
        box.addView(measure);
        AlertDialog.Builder b = new AlertDialog.Builder(c)
            .setTitle(old != null ? old.label() : c.getString(R.string.xd_ss_add_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String h = host.getText().toString().trim(), a = account.getText().toString().trim();
                if (h.isEmpty() || a.isEmpty()) return;
                if (old != null && (forget.isChecked() || !a.equals(old.account) || !h.equals(old.host)))
                    ScreenSharePasswords.forget(c, old.id);
                ScreenShareStore.save(c, id, name.getText().toString().trim(), h, a, fixed.isChecked(), measure.isChecked());
                changed(c);
            });
        if (old != null) b.setNeutralButton(R.string.xd_ss_remove, (d, w) -> {
            ScreenShareSource src = ScreenShareSource.get();
            if (src != null) src.disconnect(id);
            ScreenSharePasswords.forget(c, id);
            ScreenShareStore.remove(c, id);
            changed(c);
        });
        b.show();
    }

    private static EditText field(Context c, LinearLayout box, int hint, String value, int variation) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setText(value);
        box.addView(e);
        return e;
    }

    private static void changed(Context c) {
        ScreenShareSource src = ScreenShareSource.get();
        if (src != null) src.hostsChanged();
        Devices.get(c).touch();
    }
}
