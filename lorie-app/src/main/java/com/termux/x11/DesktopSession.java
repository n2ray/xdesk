package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Random;

/** 一次桌面会话：声音、前台服务和断线恢复。放在进程单例里，界面重建不影响它。 */
final class DesktopSession {
    interface Listener {
        /** restarted：X 服务器曾经死掉并被重新拉起，未保存的内容可能已丢失。 */
        void onSessionChanged(boolean connected, boolean restarted);
        void onSessionError(String message);
    }

    private static final String TAG = "XDesk";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String SCRIPT = "/sdcard/Termux/xdesk/termux/x11-session.sh";
    private static final long POLL_MS = 500;
    private static final long RECOVER_FIRST_MS = 4000;
    private static final long RECOVER_MAX_MS = 60000;
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
    private int audioPort;
    private String size;
    private boolean active, connected, serverLost;
    private long recoverDelay = RECOVER_FIRST_MS;

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
            if (!active || connected) return;
            Log.i(TAG, "recovering, next try in " + recoverDelay * 2);
            launch();
            recoverDelay = Math.min(recoverDelay * 2, RECOVER_MAX_MS);
            ui.postDelayed(this, recoverDelay);
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

    /** 返回 false 表示连 Termux 都调用不了，错误已经报给 listener。 */
    boolean start(String desktopSize, int audioOutput) {
        audioPort = 20000 + new Random().nextInt(30000);
        size = desktopSize;
        serverLost = false;
        recoverDelay = RECOVER_FIRST_MS;
        if (!launch()) return false;
        active = true;
        audio = new AudioPlayer(context, audioPort, audioOutput);
        context.startForegroundService(new Intent(context, DesktopService.class));
        ui.post(poll);
        // 第一次就没连上也要重试（比如上一次的 start 还没跑完）
        ui.postDelayed(recover, FIRST_CONNECT_RETRY_MS);
        return true;
    }

    private boolean launch() {
        return run("start", size, String.valueOf(audioPort));
    }

    private void connectionChanged(boolean c) {
        connected = c;
        boolean restarted = false;
        if (c) {
            restarted = serverLost;
            serverLost = false;
            ui.removeCallbacks(recover);
            recoverDelay = RECOVER_FIRST_MS;
        } else {
            // 窗口还在却断了，才是 X 服务器没了；窗口不在只是界面要重放，交给 Activity
            serverLost = MainActivity.getInstance("") != null;
            ui.removeCallbacks(recover);
            ui.postDelayed(recover, RECOVER_FIRST_MS);
        }
        if (listener != null) listener.onSessionChanged(c, restarted);
    }

    void setSize(String desktopSize) {
        size = desktopSize;
    }

    /** 放弃会话，不碰桌面。用于启动失败时重试。 */
    void abandon() {
        active = false;
        connected = false;
        ui.removeCallbacks(recover);
        ui.removeCallbacks(poll);
        if (audio != null) audio.close();
        audio = null;
        context.stopService(new Intent(context, DesktopService.class));
    }

    /** 结束桌面（XFCE 退出）。 */
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
