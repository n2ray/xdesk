package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/** 前台服务：切到别的 App 或界面被回收时，保住进程里的 DesktopSession。 */
public class DesktopService extends Service {
    private static final String CHANNEL = "desktop";
    private static final int NOTIFICATION = 1;
    /** 通知的标题，不给就是桌面。 */
    static final String EXTRA_TITLE = "title";
    private PowerManager.WakeLock wakeLock;
    private static boolean ending, running;

    static boolean isRunning() {
        return running;
    }

    /** 会话结束了不等于 XDesk 结束了：外接屏上还有仪表盘时，从最近任务划掉仍然要能收到。 */
    static void stopIfIdle(android.content.Context c) {
        android.content.Context app = c.getApplicationContext();
        if (DashboardActivity.isAlive() || AppSession.get(app).isActive() || DesktopSession.get(app).isActive()) return;
        app.stopService(new Intent(app, DesktopService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "桌面运行状态", NotificationManager.IMPORTANCE_LOW));
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xdesk:session");
        wakeLock.acquire();
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, XDeskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE);
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title != null ? title : "XDesk 运行中")
            .setContentText("点击回到触摸板")
            .setContentIntent(open)
            .setOngoing(true)
            .build();
        startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        // 进程被杀后会话也没了，不要让系统空跑地重启服务
        return START_NOT_STICKY;
    }

    /**
     * 从最近任务划掉 XDesk：会话结束，手机恢复原样。安卓应用移回手机（不关闭），桌面窗口和仪表盘去掉，外接屏回到系统的镜像，
     * 键鼠和声音路由恢复。Termux 里的 X 和桌面继续运行，下次打开在选择界面上显示为运行中。
     * 任何任务被移除都会走到这里，包括外屏的桌面窗口，所以只认 XDesk 自己的任务。
     * 进程没等到这里就被杀的话，靠注入服务的 link-to-death 兜底（InjectorService.link）。
     */
    @Override public void onTaskRemoved(Intent rootIntent) {
        android.content.ComponentName c = rootIntent != null ? rootIntent.getComponent() : null;
        if (c == null || !XDeskActivity.class.getName().equals(c.getClassName())) return;
        endAll(this);
    }

    /** 从最近任务划掉和“结束 XDesk”是同一回事。 */
    static void endAll(android.content.Context context) {
        // 结束时 XDesk 自己的任务也会被移除，又走到这里一次
        if (ending) return;
        ending = true;
        final android.content.Context app = context.getApplicationContext();
        AppSession apps = AppSession.get(app);
        if (apps.hasAppOnDisplay() && !apps.hasInjector()) {
            android.widget.Toast.makeText(app, "注入服务没有运行（Shizuku 停了？），外接屏上的应用没能移回手机", android.widget.Toast.LENGTH_LONG).show();
        }
        for (MainActivity m : new java.util.ArrayList<>(MainActivity.getInstances())) m.finishAndRemoveTask();
        int external = -1;
        for (android.view.Display d : app.getSystemService(android.hardware.display.DisplayManager.class).getDisplays()) {
            if (d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                external = d.getDisplayId();
                break;
            }
        }
        AppSession.get(app).endSession(external, () -> {
            DesktopSession.get(app).abandon();
            DashboardActivity.closeIfAlive();
            app.stopService(new Intent(app, DesktopService.class));
            ending = false;
        });
    }

    @Override public void onDestroy() {
        running = false;
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
