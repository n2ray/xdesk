// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.app.R;

import java.util.Arrays;

/**
 * On the phone: the Mac account's password for one Screen Sharing connection. A password the user chose to keep
 * is unlocked with their fingerprint or the phone's lock screen (ScreenSharePasswords); otherwise it is typed, and
 * kept only if they tick "Remember". Either way it goes to the connection in memory and is cleared once the
 * authentication used it. The window is kept out of screenshots and autofill.
 */
public final class ScreenShareLoginActivity extends Activity {
    public static final String EXTRA_HOST = "host";

    /** What ScreenShareSource hears: a password to connect with (it clears it), or the login given up. */
    public interface Outcome {
        void password(String hostId, char[] password);
        void cancelled(String hostId);
    }
    public static volatile Outcome outcome;

    private boolean done;
    private ScreenShareStore.Host host;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        host = ScreenShareStore.find(this, getIntent().getStringExtra(EXTRA_HOST));
        if (host == null) {
            finish();
            return;
        }
        if (ScreenSharePasswords.has(this, host.id)) {
            ScreenSharePasswords.load(this, host.id, getString(R.string.xd_ss_unlock_title, host.label()), (pass, error) -> {
                if (pass != null) {
                    go(pass);
                    return;
                }
                if ("invalidated".equals(error))
                    Toast.makeText(this, R.string.xd_ss_password_invalidated, Toast.LENGTH_LONG).show();
                ask();
            });
        } else {
            ask();
        }
    }

    /** The password dialog, with "Remember" when the phone can protect it. */
    private void ask() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView who = new TextView(this);
        who.setText(getString(R.string.xd_ss_login_who, host.account, host.host));
        box.addView(who);
        final EditText secret = new EditText(this);
        secret.setHint(R.string.xd_ss_password);
        secret.setSingleLine();
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setSaveEnabled(false);
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        box.addView(secret);
        final CheckBox remember = new CheckBox(this);
        remember.setText(R.string.xd_ss_remember);
        if (ScreenSharePasswords.canSave(this)) box.addView(remember);
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.xd_ss_login_title, host.label()))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
            .setPositiveButton(R.string.xd_ss_connect, (d, w) -> {
                final char[] pass = new char[secret.length()];
                secret.getText().getChars(0, pass.length, pass, 0);
                secret.setText("");
                if (!remember.isChecked()) {
                    go(pass);
                    return;
                }
                // Kept only once the user confirmed it is them; the connection goes on either way
                ScreenSharePasswords.save(this, host.id, getString(R.string.xd_ss_save_title, host.label()), pass,
                    (unused, error) -> {
                        if (error != null) Toast.makeText(this, R.string.xd_ss_not_saved, Toast.LENGTH_LONG).show();
                        go(pass);
                    });
            })
            .setOnCancelListener(d -> finish())
            .create();
        dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dlg.show();
        secret.requestFocus();
    }

    private void go(char[] pass) {
        done = true;
        Outcome out = outcome;
        if (out != null) out.password(host.id, pass);
        else Arrays.fill(pass, (char) 0);
        finish();
    }

    @Override protected void onDestroy() {
        if (!done && !isChangingConfigurations() && host != null) {
            Outcome out = outcome;
            if (out != null) out.cancelled(host.id);
        }
        super.onDestroy();
    }
}
