package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** XDesk 是显示器：这里只管声音、前台服务和连接状态。桌面由用户或 xdesk 命令启动。放在进程单例里，界面重建不影响它。 */
final class DesktopSession {
    interface Listener {
        /** restarted：X 服务器曾经死掉并被重新拉起，未保存的内容可能已丢失。 */
        void onSessionChanged(boolean connected, boolean restarted);
        void onSessionError(String message);
    }

    private static final String TAG = "XDesk";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String SCRIPT = "/data/data/com.termux/files/usr/bin/xdesk";
    /** 和 xdesk 命令里的 AUDIO_PORT 一致。 */
    private static final int AUDIO_PORT = 47130;
    private static final long POLL_MS = 500;
    private static final long RECOVER_FIRST_MS = 4000;
    private static final long RECOVER_MAX_MS = 60000;
    private static final int MAX_RECOVER_ATTEMPTS = 5;
    private static final String ACTION_STOPPED = "io.github.n2ray.xdesk.STOPPED";
    private static final long FIRST_CONNECT_RETRY_MS = 20000;

    private static DesktopSession instance;

    static synchronized DesktopSession get(Context c) {
        if (instance == null) instance = new DesktopSession(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DesktopInput input = new DesktopInput();
    private Listener listener;
    private AudioPlayer audio;
    private boolean active, connected, serverLost, recovering;
    private long recoverDelay = RECOVER_FIRST_MS;
    private int recoverAttempts;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            boolean now = DesktopInput.view() != null;
            if (now != connected) connectionChanged(now);
            ui.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable recover = new Runnable() {
        @Override public void run() {
            if (!active || connected || !recovering) return;
            Log.i(TAG, "recovering, next try in " + recoverDelay * 2);
            DesktopSession.this.run("recover");
            if (++recoverAttempts >= MAX_RECOVER_ATTEMPTS) {
                // 多半是用户自己停的（xdesk stop），不再重试，回到等待
                recovering = false;
                if (listener != null) listener.onSessionChanged(false, false);
                return;
            }
            recoverDelay = Math.min(recoverDelay * 2, RECOVER_MAX_MS);
            ui.postDelayed(this, recoverDelay);
        }
    };

    /** xdesk stop 广播过来：用户主动结束的，不恢复，立刻回到等待。 */
    private final BroadcastReceiver stopped = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!recovering) return;
            recovering = false;
            serverLost = false;
            ui.removeCallbacks(recover);
            if (listener != null) listener.onSessionChanged(connected, false);
        }
    };

    private DesktopSession(Context c) {
        context = c;
    }

    boolean isActive() { return active; }
    boolean isConnected() { return connected; }
    DesktopInput input() { return input; }
    AudioPlayer audio() { return audio; }

    void setListener(Listener l) {
        listener = l;
    }

    /** 开始当显示器：放窗口由 Activity 负责，这里不启动任何桌面。 */
    void attach(int audioOutput) {
        serverLost = false;
        recovering = false;
        recoverDelay = RECOVER_FIRST_MS;
        active = true;
        audio = new AudioPlayer(context, AUDIO_PORT, audioOutput);
        context.startForegroundService(new Intent(context, DesktopService.class));
        // 只影响是否自动恢复，别的 App 发了也无害
        context.registerReceiver(stopped, new IntentFilter(ACTION_STOPPED), Context.RECEIVER_EXPORTED);
        ui.post(poll);
    }

    /** 运行 xdesk start，需要 RUN_COMMAND 权限。之后意外断开会用 xdesk recover 拉起，用户主动 xdesk stop 的不会。 */
    boolean launch() {
        recovering = true;
        serverLost = false;
        recoverAttempts = 0;
        ui.removeCallbacks(recover);
        ui.postDelayed(recover, FIRST_CONNECT_RETRY_MS);
        return run("start");
    }

    boolean isRecovering() { return recovering; }

    private void connectionChanged(boolean c) {
        connected = c;
        boolean restarted = false;
        if (c) {
            restarted = serverLost;
            recoverAttempts = 0;
            serverLost = false;
            ui.removeCallbacks(recover);
            recoverDelay = RECOVER_FIRST_MS;
        } else {
            // 窗口还在却断了，才是 X 服务器没了；窗口不在只是界面要重放，交给 Activity
            serverLost = recovering && MainActivity.getInstance("") != null;
            ui.removeCallbacks(recover);
            if (recovering) ui.postDelayed(recover, RECOVER_FIRST_MS);
        }
        if (listener != null) listener.onSessionChanged(c, restarted);
    }


    /** 退出显示器，桌面继续运行。 */
    void abandon() {
        if (active) context.unregisterReceiver(stopped);
        active = false;
        connected = false;
        recovering = false;
        ui.removeCallbacks(recover);
        ui.removeCallbacks(poll);
        if (audio != null) audio.close();
        audio = null;
        context.stopService(new Intent(context, DesktopService.class));
    }

    /** 结束桌面（运行 xdesk stop）。 */
    void stop() {
        abandon();
        run("stop");
    }

    private boolean run(String... args) {
        String[] argv = new String[args.length + 1];
        argv[0] = SCRIPT;
        System.arraycopy(args, 0, argv, 1, args.length);
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", argv);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            context.startForegroundService(i);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "RUN_COMMAND failed", e);
            if (listener != null) listener.onSessionError(String.valueOf(e.getMessage()));
            return false;
        }
    }
}
