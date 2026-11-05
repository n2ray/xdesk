package com.termux.x11;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

/** 让 Termux 运行命令并把输出传回来。和 DesktopSession.run 一样要 RUN_COMMAND 权限和 allow-external-apps。 */
final class TermuxCommand {
    interface Callback {
        void onOutput(String stdout);
    }

    private static final String TAG = "XDesk";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String EXTRA_ID = "xdesk_command_id";
    private static final String EXTRA_RESULT = "result", RESULT_STDOUT = "stdout";

    private static final SparseArray<Callback> PENDING = new SparseArray<>();
    private static int nextId = 1;

    private TermuxCommand() { }

    /** 在 bash -c 里运行。结果回到主线程；Termux 不理就没有回调，调用方自己算超时。 */
    static boolean run(Context c, String script, Callback callback) {
        int id;
        synchronized (PENDING) {
            id = nextId++;
            PENDING.put(id, callback);
        }
        // Termux 要往里填结果，所以必须可变；用显式 Intent 才允许可变
        Intent result = new Intent(c, TermuxResultReceiver.class).putExtra(EXTRA_ID, id);
        PendingIntent pending = PendingIntent.getBroadcast(c, id, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", script});
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending);
        try {
            c.startForegroundService(i);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "RUN_COMMAND failed", e);
            synchronized (PENDING) {
                PENDING.remove(id);
            }
            return false;
        }
    }

    static void deliver(Intent i) {
        final Callback callback;
        int id = i.getIntExtra(EXTRA_ID, 0);
        synchronized (PENDING) {
            callback = PENDING.get(id);
            PENDING.remove(id);
        }
        Bundle r = i.getBundleExtra(EXTRA_RESULT);
        if (callback == null || r == null) return;
        final String stdout = r.getString(RESULT_STDOUT, "");
        new Handler(Looper.getMainLooper()).post(() -> callback.onOutput(stdout));
    }
}
