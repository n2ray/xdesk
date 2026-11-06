package com.termux.x11;

import android.app.Activity;
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
        /** Termux 没运行命令，或者命令出错了：要告诉用户的话。 */
        void onFailure(String message);
    }

    private static final String TAG = "XDesk";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String EXTRA_ID = "xdesk_command_id";
    /** Termux 结果里的键，见 termux-shared 的 PluginResultsService。err 不是 RESULT_OK 时命令没有运行。 */
    private static final String EXTRA_RESULT = "result", RESULT_STDOUT = "stdout", RESULT_STDERR = "stderr",
        RESULT_EXIT = "exitCode", RESULT_ERR = "err", RESULT_ERRMSG = "errmsg";
    /** bash 找不到命令。 */
    private static final int EXIT_NOT_FOUND = 127;
    private static final String INSTALL = "curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash";

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
        final String failure = failure(r, stdout);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (failure != null) callback.onFailure(failure);
            else callback.onOutput(stdout);
        });
    }

    /** null 是命令正常运行了。装了 xdesk 又有输出时就用输出，退出码不算数。 */
    private static String failure(Bundle r, String stdout) {
        String errmsg = r.getString(RESULT_ERRMSG, ""), stderr = r.getString(RESULT_STDERR, "");
        if (r.getInt(RESULT_ERR, Activity.RESULT_OK) != Activity.RESULT_OK) {
            Log.w(TAG, "Termux refused the command: " + errmsg);
            if (errmsg.contains("allow-external-apps")) return "Termux 不让别的应用运行命令。在 Termux 里运行 xdesk enable-app-start 后点这里";
            return "Termux 没有运行命令：" + firstLine(errmsg);
        }
        // 输出里还会有命令之间的 echo，所以先看是不是没装
        int exit = r.getInt(RESULT_EXIT, 0);
        if (exit == EXIT_NOT_FOUND || stderr.contains("xdesk: No such file")) return "Termux 里还没有装 xdesk。在 Termux 里运行下面这行，装好后点这里：\n" + INSTALL;
        if (!stdout.trim().isEmpty()) return null;
        if (exit != 0 || !stderr.trim().isEmpty()) return "xdesk 出错了（" + exit + "）：" + firstLine(stderr);
        return null;
    }

    private static String firstLine(String s) {
        String t = s.trim();
        int n = t.indexOf('\n');
        return n < 0 ? t : t.substring(0, n);
    }
}
