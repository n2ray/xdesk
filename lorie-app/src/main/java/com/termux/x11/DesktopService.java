package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.PowerManager;
import com.termux.x11.app.R;

/** 前台服务：切到别的 App 或界面被回收时，保住进程里的 DesktopSession。 */
public class DesktopService extends Service {
    /** The running notification's channel. The lost-permission reminder has its own (Permissions.CHANNEL). */
    static final String CHANNEL = "desktop";
    private static final int NOTIFICATION = 1;
    /** 通知的标题，不给就是桌面。 */
    static final String EXTRA_TITLE = "title";
    /** PhoneNotify.dc.html's two actions: the Launchpad on the display, and the way to end XDesk. */
    static final String EXTRA_LAUNCHPAD = "launchpad", EXTRA_END = "end";
    private static final int REQUEST_LAUNCHPAD = 10, REQUEST_END = 11;
    private static final Object WAKE_GUARD = new Object();
    private static PowerManager.WakeLock wakeLock;
    private static boolean ending, running;

    static boolean isRunning() {
        return running;
    }

    /** 会话结束了不等于 XDesk 结束了：外接屏上还有仪表盘时，从最近任务划掉仍然要能收到。 */
    static void stopIfIdle(android.content.Context c) {
        android.content.Context app = c.getApplicationContext();
        // Before deciding: whatever ended may be the last thing that needed the CPU, see updateWakeLock
        updateWakeLock(app);
        // The web display keeps it while on: a browser may connect with XDesk in the background, see WebServer.start
        if (DashboardActivity.isAlive() || AppSession.get(app).isActive() || DesktopSession.get(app).isActive()
            || WirelessDisplay.get(app).isActive() || WebServer.get(app).isRunning()) return;
        app.stopService(new Intent(app, DesktopService.class));
    }

    /**
     * The CPU stays awake only while a desktop, an app or a stream is actually running. The service alone (the
     * dashboard alive, the web display merely listening) needs no wake lock, so the device can sleep.
     */
    private static void updateWakeLock(android.content.Context app) {
        synchronized (WAKE_GUARD) {
            if (wakeLock == null) return;
            boolean needed = DesktopSession.get(app).isActive() || AppSession.get(app).isActive()
                || WirelessDisplay.get(app).isActive();
            if (needed && !wakeLock.isHeld()) wakeLock.acquire();
            else if (!needed && wakeLock.isHeld()) wakeLock.release();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.xd_service_channel), NotificationManager.IMPORTANCE_LOW));
        PowerManager pm = getSystemService(PowerManager.class);
        synchronized (WAKE_GUARD) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xdesk:session");
        }
        updateWakeLock(this);
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, XDeskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE);
        // PhoneNotify.dc.html: the two actions go through the activity's existing paths (showSwitcher, End XDesk)
        PendingIntent launchpad = PendingIntent.getActivity(this, REQUEST_LAUNCHPAD,
            new Intent(this, XDeskActivity.class)
                .setAction("com.termux.x11.action.LAUNCHPAD")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_LAUNCHPAD, true),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent end = PendingIntent.getActivity(this, REQUEST_END,
            new Intent(this, XDeskActivity.class)
                .setAction("com.termux.x11.action.END")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_END, true),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Icon actionIcon = Icon.createWithResource(this, R.drawable.xdesk_ic_mono);
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title != null ? title : getString(R.string.xd_service_title))
            .setContentText(getString(R.string.xd_service_text))
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(actionIcon, getString(R.string.xd_switcher_short), launchpad).build())
            .addAction(new Notification.Action.Builder(actionIcon, getString(R.string.xd_end_xdesk), end).build())
            .setOngoing(true)
            .build();
        startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        // A session may have started without the service having been created by it (dashboard, web listener)
        updateWakeLock(this);
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
            android.widget.Toast.makeText(app, app.getString(R.string.xd_service_not_moved), android.widget.Toast.LENGTH_LONG).show();
        }
        for (MainActivity m : new java.util.ArrayList<>(MainActivity.getInstances())) m.finishAndRemoveTask();
        // A Mac's screen is XDesk's own activity on the external display: it would stay there, streaming
        com.termux.x11.mac.MacActivity.finishAll();
        SwitcherOverlay.hide(null);
        // 外接屏上的安卓应用移回手机。车机上的应用在 XDesk 自己的显示器上，AppSession 自己移回；Android Auto 的显示器上的东西不动
        android.view.Display real = TargetDisplay.real(app.getSystemService(android.hardware.display.DisplayManager.class));
        int external = real != null ? real.getDisplayId() : -1;
        AppSession.get(app).endSession(external, () -> {
            DesktopSession.get(app).abandon();
            DashboardActivity.closeIfAlive();
            // 结束 XDesk 也结束无线投屏，接收端回到等待画面
            WirelessDisplay.get(app).stop();
            app.stopService(new Intent(app, DesktopService.class));
            ending = false;
        });
    }

    @Override public void onDestroy() {
        running = false;
        synchronized (WAKE_GUARD) {
            if (wakeLock != null) {
                if (wakeLock.isHeld()) wakeLock.release();
                wakeLock = null;
            }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
