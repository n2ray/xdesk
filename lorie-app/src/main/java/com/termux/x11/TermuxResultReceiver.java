package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Termux 通过 PendingIntent 把命令结果送到这里。 */
public class TermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        TermuxCommand.deliver(c, i);
    }
}
