package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * XDesk 是显示器：这里只管声音、前台服务和连接状态。放在进程单例里，界面重建不影响它。
 * 桌面只由用户明确启动和结束：这里不会自己重启桌面，X 服务器没了就告诉界面。
 */
final class DesktopSession {
    interface Listener {
        void onSessionChanged(boolean connected);
        /** X 服务器没了。byUser：用户运行了 xdesk stop。 */
        void onDesktopStopped(boolean byUser);
        void onSessionError(String message);
    }

    private static final String TAG = "XDesk";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String SCRIPT = "/data/data/com.termux/files/usr/bin/xdesk";
    /** 和 xdesk 命令里的 AUDIO_PORT 一致。 */
    private static final int AUDIO_PORT = 47130;
    private static final long POLL_MS = 500;
    /** 窗口重建时也会短暂断开，断开这么久才算 X 服务器没了。 */
    private static final long SERVER_LOST_MS = 3000;
    private static final String ACTION_STOPPED = "io.github.n2ray.xdesk.STOPPED";

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
    private boolean active, connected, stoppedByUser;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            boolean now = DesktopInput.view() != null;
            if (now != connected) connectionChanged(now);
            ui.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable serverLost = new Runnable() {
        @Override public void run() {
            // 窗口不在只是界面要重放（比如显示器拔了），交给 Activity
            if (!active || connected || MainActivity.getInstance("") == null) return;
            Log.i(TAG, "X server gone, by user: " + stoppedByUser);
            if (listener != null) listener.onDesktopStopped(stoppedByUser);
        }
    };

    /** xdesk stop 广播过来：用户主动结束的，界面不用当成意外。 */
    private final BroadcastReceiver stopped = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            stoppedByUser = true;
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
    void attach() {
        stoppedByUser = false;
        active = true;
        audio = new AudioPlayer(AUDIO_PORT, AudioRoute.get(context));
        context.startForegroundService(new Intent(context, DesktopService.class));
        // 只用来区分是不是用户结束的，别的 App 发了也无害
        context.registerReceiver(stopped, new IntentFilter(ACTION_STOPPED), Context.RECEIVER_EXPORTED);
        ui.post(poll);
    }

    /**
     * 运行 xdesk start，需要 RUN_COMMAND 权限。只由用户的选择触发。
     *
     * @param name 环境名，null 是默认环境
     * @param replaceRunning 结束运行中的另一个环境再启动（xdesk switch）
     */
    boolean launch(String name, boolean replaceRunning) {
        stoppedByUser = false;
        if (name == null) return run("start");
        return replaceRunning ? run("switch", name) : run("start", name);
    }

    private void connectionChanged(boolean c) {
        connected = c;
        ui.removeCallbacks(serverLost);
        if (!c) ui.postDelayed(serverLost, SERVER_LOST_MS);
        if (listener != null) listener.onSessionChanged(c);
    }

    /** 退出显示器，桌面继续运行。 */
    void abandon() {
        if (active) context.unregisterReceiver(stopped);
        active = false;
        connected = false;
        ui.removeCallbacks(serverLost);
        ui.removeCallbacks(poll);
        if (audio != null) audio.close();
        audio = null;
        context.stopService(new Intent(context, DesktopService.class));
    }

    /** 结束桌面（运行 xdesk stop）。只由用户明确操作触发，XDesk 打开或退出都不会结束桌面。 */
    void stop() {
        if (active) abandon();
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
