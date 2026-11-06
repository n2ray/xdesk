package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Finds the pairing port while the user is in Settings, where the pairing dialog closes if they leave. So the guidance
 * and a field for the code are in a notification. The same idea as Shizuku's AdbPairingService, written again here. HyperOS
 * users have seen that field fail, so XDesk's activation page has one too.
 *
 * A short service, which lasts about 3 minutes and must stop in onTimeout or the app gets an ANR. Before Android 14 the
 * type means nothing and there is no onTimeout, so it keeps the same limit itself.
 */
public final class PairingService extends Service {
    private static final String TAG = "XDeskShell";
    private static final String CHANNEL = "xdesk_pairing";
    private static final int NOTIFICATION = 7301, RESULT_NOTIFICATION = 7302;
    private static final String ACTION_START = "start", ACTION_REPLY = "reply", ACTION_STOP = "stop";
    private static final String EXTRA_PORT = "port", REMOTE_INPUT_CODE = "code";
    private static final int REQUEST_REPLY = 1, REQUEST_STOP = 2;
    private static final int ICON = com.termux.x11.app.R.drawable.xdesk_ic_mono;
    private static final long OWN_TIMEOUT_MS = 3 * 60 * 1000;

    private AdbMdns search;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ownTimeout = this::timedOut;

    static Intent startIntent(Context c) {
        return new Intent(c, PairingService.class).setAction(ACTION_START);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        notifications().createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) handler.postDelayed(ownTimeout, OWN_TIMEOUT_MS);
    }

    private NotificationManager notifications() {
        return getSystemService(NotificationManager.class);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification shown = searching();
        if (ACTION_REPLY.equals(action)) {
            Bundle input = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = input != null ? input.getCharSequence(REMOTE_INPUT_CODE) : null;
            int port = intent.getIntExtra(EXTRA_PORT, 0);
            if (port > 0 && code != null && code.length() > 0) {
                shown = message("正在配对", "配对码已经收到。");
                pair(port, code.toString().trim());
            } else {
                shown = port > 0 ? askForCode(port) : searching();
            }
        }
        try {
            startForeground(NOTIFICATION, shown);
        } catch (RuntimeException e) {
            // Not allowed from the background in some states. Pairing goes on, the user just sees it another way
            Log.w(TAG, "startForeground", e);
            notifications().notify(NOTIFICATION, shown);
        }
        startSearch();
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId) {
        timedOut();
    }

    private void timedOut() {
        Log.i(TAG, "pairing service timed out");
        notifications().notify(RESULT_NOTIFICATION, message("配对等待超时", "回到 XDesk，再点一次“打开无线调试设置”。"));
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(ownTimeout);
        if (search != null) search.stop();
        ShellActivation.get(this).setPairingPort(0);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void startSearch() {
        if (search != null) return;
        search = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, port) -> {
            if (port <= 0) return;
            ShellActivation.get(this).setPairingPort(port);
            notifications().notify(NOTIFICATION, askForCode(port));
        });
        search.start();
    }

    private void pair(int port, String code) {
        ShellActivation.get(this).pair(port, code, error -> {
            Notification result = error == null
                ? message("配对成功", "回到 XDesk 就可以继续。")
                : message("配对失败", error + "\n通知里输不了的话，回到 XDesk 在页面里输入。");
            notifications().notify(RESULT_NOTIFICATION, result);
            if (error != null) stopSelf();
        });
    }

    private Notification.Builder base(String title, String text) {
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text));
    }

    private Notification message(String title, String text) {
        return base(title, text).build();
    }

    private Notification searching() {
        PendingIntent stop = PendingIntent.getService(this, REQUEST_STOP, new Intent(this, PairingService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return base("XDesk 正在找配对端口", "打开“无线调试”，点“使用配对码配对设备”。配对框要一直开着。找到后可以在这条通知里输入配对码。输不了的话，用分屏或小窗打开 XDesk，在它的页面里输入。")
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build();
    }

    /** The port goes in the intent, because this service may be gone by the time the user has typed. */
    private Notification askForCode(int port) {
        Intent reply = new Intent(this, PairingService.class).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port);
        PendingIntent pending = PendingIntent.getForegroundService(this, REQUEST_REPLY, reply, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder(REMOTE_INPUT_CODE).setLabel("6 位配对码").build();
        return base("找到配对端口，输入配对码", "点“输入配对码”，填配对框里的 6 位数字。输不了的话，用分屏或小窗打开 XDesk，在它的页面里输入。")
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "输入配对码", pending).addRemoteInput(input).build())
            .build();
    }
}
