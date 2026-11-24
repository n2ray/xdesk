package com.termux.x11;

import com.termux.x11.app.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps XDesk running while the built-in Linux does: its X server and session are XDesk's own child processes, and
 * an app without a foreground service is frozen in the background (cached apps freezer) or killed.
 *
 * Its notification can also keep the CPU awake, as Termux's does (owner, 24 November): with the screen off and only
 * SSH running nothing else holds a wake lock, and the phone sleeps under an open SSH session. Off by default; it goes
 * with the service.
 */
public class LinuxService extends Service {
    private static final String CHANNEL = "builtin_linux";
    private static final String ACTION_STOP = "stop", ACTION_WAKE = "wake";
    private static final int ID = 7140;
    private PowerManager.WakeLock wake;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            // The desktop; BuiltinLinux ends this service itself unless SSH stays (stopping the desktop keeps it)
            BuiltinLinux.get(this).stop();
        } else if (ACTION_WAKE.equals(action)) {
            if (wake == null) {
                wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xdesk:linux");
                wake.setReferenceCounted(false);
            }
            if (wake.isHeld()) wake.release();
            else wake.acquire();
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.xd_bl_title), NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        return START_NOT_STICKY;
    }

    private Notification notification() {
        boolean awake = wake != null && wake.isHeld();
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, XDeskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LinuxService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 2,
                new Intent(this, LinuxService.class).setAction(ACTION_WAKE), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.xd_ic_display)
                .setContentTitle(getString(R.string.xd_bl_running_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.xd_dev_stop), stop).build())
                .addAction(new Notification.Action.Builder(null,
                    getString(awake ? R.string.xd_bl_wake_release : R.string.xd_bl_wake_hold), toggle).build());
        if (awake) b.setContentText(getString(R.string.xd_bl_wake_held));
        return b.build();
    }

    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
