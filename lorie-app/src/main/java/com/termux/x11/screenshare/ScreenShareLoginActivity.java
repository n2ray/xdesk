// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

/**
 * On the phone: the Mac account's password for one Screen Sharing connection. It is handed to the session in memory
 * and cleared there once the authentication used it; never saved, never autofilled, the window kept out of
 * screenshots. Then the picture opens on the display the user chose (EXTRA_DISPLAY).
 */
public final class ScreenShareLoginActivity extends Activity {
    public static final String EXTRA_HOST = "host", EXTRA_DISPLAY = "display";

    /** What ScreenShareSource hears: the login went on to the screen, or was given up. */
    public interface Outcome {
        void started(String hostId);
        void cancelled(String hostId);
    }
    public static volatile Outcome outcome;

    private boolean done;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        final ScreenShareStore.Host host = ScreenShareStore.find(this, getIntent().getStringExtra(EXTRA_HOST));
        final int display = getIntent().getIntExtra(EXTRA_DISPLAY, -1);
        if (host == null) {
            finish();
            return;
        }
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
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.xd_ss_login_title, host.label()))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
            .setPositiveButton(R.string.xd_ss_connect, (d, w) -> {
                char[] pass = new char[secret.length()];
                secret.getText().getChars(0, pass.length, pass, 0);
                secret.setText("");
                ScreenShareActivity.handOver(host.id, pass);
                Intent i = new Intent(this, ScreenShareActivity.class);
                i.putExtra(ScreenShareActivity.EXTRA_HOST, host.id);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions o = ActivityOptions.makeBasic();
                if (display >= 0) o.setLaunchDisplayId(display);
                done = true;
                // The app frees the display first (what was there goes behind), then the picture takes it
                Outcome out = outcome;
                if (out != null) out.started(host.id);
                startActivity(i, o.toBundle());
                finish();
            })
            .setOnCancelListener(d -> finish())
            .create();
        dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dlg.show();
        secret.requestFocus();
    }

    @Override protected void onDestroy() {
        if (!done && !isChangingConfigurations()) {
            Outcome out = outcome;
            String id = getIntent().getStringExtra(EXTRA_HOST);
            if (out != null && id != null) out.cancelled(id);
        }
        super.onDestroy();
    }
}
