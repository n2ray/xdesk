package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * XDesk for a computer, over USB: it forwards a port with adb and asks to be connected to. Only the shell user can send
 * this, by the permission in the manifest, and the receiver at that address still has to be paired.
 */
public final class ConnectReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String host = i.getStringExtra("host");
        int port = i.getIntExtra("port", 0);
        if (host == null || port <= 0) return;
        try {
            WirelessDisplay.get(c).connectFromComputer(host, port, i.getStringExtra("name"));
        } catch (RuntimeException e) {
            // Android refuses a foreground service to an app in the background: XDesk has to be open
            Log.w("XDeskWireless", "cannot connect", e);
        }
    }
}
