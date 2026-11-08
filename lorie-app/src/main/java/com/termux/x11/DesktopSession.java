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
        /** xdesk stop has finished, or the wait for it gave up. Main thread. */
        void onDesktopStopFinished();
    }

    private static final String TAG = "XDesk";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String SCRIPT = "/data/data/com.termux/files/usr/bin/xdesk";
    /** 和 xdesk 命令里的 AUDIO_PORT 一致。 */
    private static final int AUDIO_PORT = 47130;
    private static final long POLL_MS = 500;
    /** 窗口重建时也会短暂断开，断开这么久才算 X 服务器没了。 */
    private static final long SERVER_LOST_MS = 3000;
    /** xdesk stop takes up to about 15 s (a logout that hangs, then kills). Past this it is treated as finished. */
    private static final long STOP_TIMEOUT_MS = 40000;
    private static final String ACTION_STOPPED = "io.github.n2ray.xdesk.STOPPED", EXTRA_NONCE = "nonce";
    /**
     * Any app can send ACTION_STOPPED. xdesk saves this in its run dir, which other apps cannot read, when XDesk
     * runs it, and xdesk stop sends it back. New for each XDesk process: every command XDesk runs passes the current one.
     */
    static final String NONCE = newNonce();

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
    /** The session xdesk stop is ending, or "" if unknown; null when none is. */
    private String stopping;
    private final Runnable stopTimeout = this::stopFinished;
    /** Its own receiver: abandon() unregisters the other one before xdesk stop runs. */
    private final BroadcastReceiver stopDone = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (NONCE.equals(i.getStringExtra(EXTRA_NONCE))) stopFinished();
        }
    };

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
            if (NONCE.equals(i.getStringExtra(EXTRA_NONCE))) stoppedByUser = true;
            else Log.w(TAG, "ignored " + ACTION_STOPPED + " without the nonce");
        }
    };

    private static String newNonce() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** For a bash -c script that runs xdesk: the nonce for its STOPPED broadcast. */
    static String nonceEnv() {
        return "export XDESK_NONCE=" + NONCE + "; ";
    }

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
        // 只用来区分是不是用户结束的。别的 App 发的没有 NONCE，不算
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
        DesktopService.stopIfIdle(context);
    }

    /** 结束桌面（运行 xdesk stop）。只由用户明确操作触发，XDesk 打开或退出都不会结束桌面。 */
    void stop(String name) {
        if (stopping != null) return;
        if (active) abandon();
        stopping = name != null ? name : "";
        context.registerReceiver(stopDone, new IntentFilter(ACTION_STOPPED), Context.RECEIVER_EXPORTED);
        ui.postDelayed(stopTimeout, STOP_TIMEOUT_MS);
        if (!run("stop")) stopFinished();
    }

    /** What xdesk stop is ending, "" if not known by name, null when nothing is being stopped. */
    String stopping() {
        return stopping;
    }

    private void stopFinished() {
        if (stopping == null) return;
        stopping = null;
        ui.removeCallbacks(stopTimeout);
        context.unregisterReceiver(stopDone);
        if (listener != null) listener.onDesktopStopFinished();
    }

    private boolean run(String... args) {
        // bash -c, so that xdesk gets the nonce in its environment. Arguments stay separate words: "$@"
        String[] argv = new String[args.length + 3];
        argv[0] = "-c";
        argv[1] = nonceEnv() + "exec " + SCRIPT + " \"$@\"";
        argv[2] = "xdesk";
        System.arraycopy(args, 0, argv, 3, args.length);
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
