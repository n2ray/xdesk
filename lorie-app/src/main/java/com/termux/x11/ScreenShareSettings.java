// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.view.Display;
import android.hardware.display.DisplayManager;

import com.termux.x11.app.R;
import com.termux.x11.screenshare.ScreenSharePasswords;
import com.termux.x11.screenshare.ScreenShareStore;
import com.termux.x11.screenshare.ScreenShareSizing;

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
        // 桌面缩放: three tiers, and what each gives on the screen attached now, before connecting
        TextView scaleTitle = new TextView(c);
        scaleTitle.setText(R.string.xd_ss_scale);
        scaleTitle.setPadding(0, pad / 2, 0, 0);
        box.addView(scaleTitle);
        final android.widget.RadioGroup scale = new android.widget.RadioGroup(c);
        scale.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        int[] tierNames = {R.string.xd_ss_scale_native, R.string.xd_ss_scale_moderate, R.string.xd_ss_scale_large};
        for (int t = 0; t < 3; t++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(c);
            rb.setId(View.generateViewId());
            rb.setTag(t);
            rb.setText(tierNames[t]);
            scale.addView(rb, new android.widget.RadioGroup.LayoutParams(0, -2, 1));
            if (t == (old != null ? old.scale : ScreenShareSizing.NATIVE)) rb.setChecked(true);
        }
        box.addView(scale);
        final TextView scaleResult = new TextView(c);
        scaleResult.setTextColor(Style.TEXT_DIM);
        box.addView(scaleResult);
        final CheckBox fixed = new CheckBox(c);
        fixed.setText(R.string.xd_ss_fixed1080);
        fixed.setChecked(old != null && old.fixed1080);
        box.addView(fixed);
        final Runnable showScale = () -> scaleResult.setText(scaleLine(c, old, tierOf(scale), fixed.isChecked()));
        scale.setOnCheckedChangeListener((g, idd) -> showScale.run());
        fixed.setOnCheckedChangeListener((v, on) -> showScale.run());
        showScale.run();
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
                ScreenShareStore.save(c, id, name.getText().toString().trim(), h, a, fixed.isChecked(), measure.isChecked(), tierOf(scale));
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

    private static int tierOf(android.widget.RadioGroup g) {
        View v = g.findViewById(g.getCheckedRadioButtonId());
        return v != null && v.getTag() instanceof Integer ? (Integer) v.getTag() : ScreenShareSizing.NATIVE;
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
