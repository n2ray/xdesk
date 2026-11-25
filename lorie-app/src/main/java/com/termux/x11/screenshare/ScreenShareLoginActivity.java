// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
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

    public static final String EXTRA_DISPLAY="external_display";
    private static ScreenShareLoginActivity current;
    public static void closeFor(String id) {
        if(current!=null && current.host!=null && id.equals(current.host.id)) current.finish();
    }
    private android.hardware.display.DisplayManager displayManager;
    private final android.hardware.display.DisplayManager.DisplayListener displayListener=new android.hardware.display.DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id) { }
        public void onDisplayChanged(int id) { }
        public void onDisplayRemoved(int id) {
            if(id==getIntent().getIntExtra(EXTRA_DISPLAY,-1)) finish();
        }
    };
    private boolean done;
    private ScreenShareStore.Host host;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        host = ScreenShareStore.find(this, getIntent().getStringExtra(EXTRA_HOST));
        current=this;
        if(getIntent().hasExtra(EXTRA_DISPLAY) &&
                (getDisplay()==null || getDisplay().getDisplayId()==android.view.Display.DEFAULT_DISPLAY ||
                getDisplay().getDisplayId()!=getIntent().getIntExtra(EXTRA_DISPLAY,-1))) {
            finish(); return;
        }
        if(getIntent().hasExtra(EXTRA_DISPLAY)){
            displayManager=getSystemService(android.hardware.display.DisplayManager.class);
            displayManager.registerDisplayListener(displayListener,null);
        }
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

    /** The password sheet (MacLogin.phone), with 记住密码 when the phone can protect it. */
    private void ask() {
        android.app.Dialog dlg = com.termux.x11.MacLogin.phone(this, host, new com.termux.x11.MacLogin.Typed() {
            @Override public void typed(char[] pass, boolean remember) {
                if (!remember) {
                    go(pass);
                    return;
                }
                // Kept only once the user confirmed it is them; the connection goes on either way
                ScreenSharePasswords.save(ScreenShareLoginActivity.this, host.id,
                    getString(R.string.xd_ss_save_title, host.label()), pass, (unused, error) -> {
                        if (error != null)
                            Toast.makeText(ScreenShareLoginActivity.this, R.string.xd_ss_not_saved, Toast.LENGTH_LONG).show();
                        go(pass);
                    });
            }
            @Override public void cancelled() {
                finish();
            }
        });
        dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void go(char[] pass) {
        if(isFinishing() || isDestroyed()) {Arrays.fill(pass,(char)0);return;}
        done = true;
        Outcome out = outcome;
        if (out != null) out.password(host.id, pass);
        else Arrays.fill(pass, (char) 0);
        finish();
    }

    @Override protected void onDestroy() {
        if(displayManager!=null)displayManager.unregisterDisplayListener(displayListener);
        if(current==this) current=null;
        if (!done && !isChangingConfigurations() && host != null) {
            Outcome out = outcome;
            if (out != null) out.cancelled(host.id);
        }
        super.onDestroy();
    }
}
