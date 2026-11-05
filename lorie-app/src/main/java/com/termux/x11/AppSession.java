package com.termux.x11;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;

/**
 * An Android app on the external display: the injector process, the cursor and the display it is on. Lives in the
 * process like DesktopSession, so rebuilding the UI does not affect it. Touches nothing of a Termux desktop.
 */
final class AppSession {
    interface Listener {
        /** Shizuku appeared, went away or answered the permission request. */
        void onShizukuChanged();
        void onAppStarted();
        void onAppFailed(String message);
        /** Forwarding, connection or display changed, redraw the status. */
        void onAppStateChanged();
        void onAppMessage(String message);
        /** 外接屏上不再有我们放的应用了（关闭了，或者按 Back 退出了）：该让用户选一个。 */
        void onAppGone();
    }

    static final int SHIZUKU_READY = 0, SHIZUKU_NOT_INSTALLED = 1, SHIZUKU_NOT_RUNNING = 2, SHIZUKU_NO_PERMISSION = 3, SHIZUKU_TOO_OLD = 4;
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static final String TAG = "XDesk";
    private static final int PERMISSION_REQUEST = 47131;
    /** Shizuku's first version with user services. */
    private static final int SHIZUKU_MIN_VERSION = 10;
    private static final long BIND_TIMEOUT_MS = 15000;
    private static final long TOUCH_CURSOR_HIDE_MS = 2000;
    private static final long REBIND_DELAY_MS = 2000;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private static AppSession instance;

    static synchronized AppSession get(Context c) {
        if (instance == null) instance = new AppSession(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CursorOverlay cursor;
    private final CursorPolicy cursorPolicy = new CursorPolicy();
    private final TouchDots touchDots;
    private final AudioRoute audioRoute;
    /** 接管过键鼠又切去了 Termux 桌面：切回来时接着接管。用户没要过就不会自己接管。 */
    private boolean forwardOnReturn;
    private boolean alwaysShowCursor;
    /** Termux 桌面也要靠注入服务选声音输出：只为这个连上，不读键鼠。 */
    private boolean audioBinding;
    private int lastCursorX, lastCursorY;
    /** 切去 Termux 桌面时应用的任务留在了外接屏上。 */
    private boolean appBehindDesktop;
    private boolean stopping;
    /** 外接屏的界面大小：按物理显示器记住，显示器的 id 插拔后会变。 */
    private final android.content.SharedPreferences densities;
    private volatile String densityKey;
    private volatile int defaultDensity;
    /** 每个应用自己的界面大小，没有就跟随上面的默认值。 */
    private final android.content.SharedPreferences appDensities;
    private final Object densityLock = new Object();
    /** 我们现在设在外接屏上的值。0 是没设。-1 是不知道：注入服务死了，它设的值可能还留着。 */
    private int appliedDensity;
    private final android.content.SharedPreferences touchModes;
    private Listener listener;
    private boolean active, forwarding, started;
    private int deviceCount;
    private AppCatalog.App app;
    private Display display;
    private float mouseSpeed = 1f;
    private volatile IInjector injector;
    private Shizuku.UserServiceArgs args;

    private final IInjectorListener.Stub fromInjector = new IInjectorListener.Stub() {
        @Override public void onCursor(final int x, final int y) {
            ui.post(() -> {
                if (!active) return;
                lastCursorX = x;
                lastCursorY = y;
                cursorPolicy.moved(y);
                cursor.moveTo(x, y);
                updateCursor();
                scheduleCursorHide();
            });
        }
        @Override public void onForwarding(final boolean on, final int devices) {
            ui.post(() -> {
                forwarding = on;
                deviceCount = devices;
                // Input went back to the phone. The phone's touchpad brings the cursor back when it is used.
                if (!on) cursor.hide();
                if (listener != null) listener.onAppStateChanged();
            });
        }
        @Override public void onMessage(final String message) {
            ui.post(() -> {
                if (listener != null) listener.onAppMessage(message);
            });
        }
        @Override public void onAppInSession(final boolean inSession) {
            ui.post(() -> {
                cursorPolicy.setInSession(inSession);
                updateCursor();
            });
        }
        @Override public void onTopApp(final String pkg) {
            ui.post(() -> topAppChanged(pkg));
        }
        @Override public void onFingers(final float[] x, final float[] y) {
            ui.post(() -> {
                if (!active) return;
                // Overlay windows of one app add up over a point, and together they would block touches
                if (x.length > 0) cursor.hide();
                touchDots.show(x, y);
            });
        }
        @Override public void onButtonUp() {
            ui.post(() -> {
                cursorPolicy.buttonUp(SystemClock.uptimeMillis());
                updateCursor();
                // 点按时让人看到点在哪
                if (active && app != null && touchModeFor(app)) {
                    cursor.moveTo(lastCursorX, lastCursorY);
                    scheduleCursorHide();
                }
            });
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            ui.removeCallbacks(bindTimeout);
            boolean audioOnly = !active && audioBinding;
            audioBinding = false;
            if ((!active && !audioOnly) || binder == null || !binder.pingBinder()) return;
            final IInjector i = IInjector.Stub.asInterface(binder);
            injector = i;
            if (audioOnly) {
                new Thread(() -> {
                    try {
                        i.attach(fromInjector, context.getPackageName());
                        i.setEnabled(false);
                        ui.post(() -> audioRoute.attachInjector(i));
                    } catch (RemoteException e) {
                        Log.w(TAG, "audio only", e);
                    }
                }, "xdesk-audio-connect").start();
            } else {
                new Thread(AppSession.this::prepare, "xdesk-app-start").start();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            lostInjector();
        }
    };

    private final Runnable cursorTimer = this::updateCursor;
    private final Runnable cursorHide = this::hideTouchCursor;

    private final Runnable rebind = this::rebindInjector;

    private final Runnable bindTimeout = () -> fail("注入服务没有启动。到 Shizuku 里确认它还在运行，再试一次。");

    private final InputSink input = new InputSink() {
        @Override public void move(int dx, int dy) {
            IInjector i = injector;
            try {
                if (i != null) i.move(dx, dy);
            } catch (RemoteException ignored) { }
        }
        @Override public void button(int button, boolean down) {
            IInjector i = injector;
            try {
                if (i != null) i.button(button, down);
            } catch (RemoteException ignored) { }
        }
        @Override public void click(int button) {
            button(button, true);
            button(button, false);
        }
        @Override public void scroll(int dy, int dx) {
            IInjector i = injector;
            try {
                if (i != null) i.scroll(dy, dx);
            } catch (RemoteException ignored) { }
        }
        @Override public void key(int scan, boolean down) {
            IInjector i = injector;
            try {
                if (i != null) i.key(scan, down);
            } catch (RemoteException ignored) { }
        }
        @Override public void tapKey(int scan) {
            IInjector i = injector;
            try {
                if (i != null) i.tapKey(scan);
            } catch (RemoteException ignored) { }
        }
        @Override public void text(String s) {
            IInjector i = injector;
            try {
                if (i != null) i.text(s);
            } catch (RemoteException ignored) { }
        }
    };

    private AppSession(Context c) {
        context = c;
        cursor = new CursorOverlay(c);
        touchDots = new TouchDots(c);
        audioRoute = AudioRoute.get(c);
        touchModes = c.getSharedPreferences("app_touch_mode", Context.MODE_PRIVATE);
        densities = c.getSharedPreferences("display_density", Context.MODE_PRIVATE);
        appDensities = c.getSharedPreferences("app_density", Context.MODE_PRIVATE);
        Shizuku.addBinderReceivedListenerSticky(this::shizukuChanged);
        Shizuku.addBinderDeadListener(this::shizukuChanged);
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code == PERMISSION_REQUEST) shizukuChanged();
        });
    }

    private void hideTouchCursor() {
        cursor.hide();
    }

    /** 触控模式下光标不动一会就藏起来，下次移动再出现。 */
    private void scheduleCursorHide() {
        ui.removeCallbacks(cursorHide);
        if (active && app != null && touchModeFor(app)) ui.postDelayed(cursorHide, TOUCH_CURSOR_HIDE_MS);
    }

    private void updateCursor() {
        boolean touch = active && app != null && touchModeFor(app);
        cursor.setTouchStyle(touch);
        if (!touch) ui.removeCallbacks(cursorHide);  // 只有触控模式才会自己藏起来
        long now = SystemClock.uptimeMillis();
        cursor.setFaint(!cursorPolicy.fullCursor(now));
        ui.removeCallbacks(cursorTimer);
        long next = cursorPolicy.nextChange(now);
        if (next >= 0) ui.postDelayed(cursorTimer, next + 1);
    }

    private void shizukuChanged() {
        ui.post(() -> {
            if (listener != null) listener.onShizukuChanged();
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isActive() { return active; }
    /** Started and receiving input. */
    boolean isStarted() { return started; }
    /** 桌面窗口没了，外接屏上露出留在后面的应用：接着用它。没有这样的应用返回 false。 */
    boolean resumeApp(Display d, float speed, boolean alwaysShow) {
        if (active || !appBehindDesktop || app == null || injector == null) return false;
        start(app, d, speed, alwaysShow);
        return true;
    }

    /** 外接屏上有 XDesk 放上去的应用。 */
    boolean hasAppOnDisplay() { return (active && started) || appBehindDesktop; }
    /** 上一个注入服务还在结束。 */
    boolean isStopping() { return stopping; }
    boolean isForwarding() { return forwarding; }
    boolean hasInjector() { return injector != null; }
    boolean hasDisplay() { return display != null; }
    int deviceCount() { return deviceCount; }
    String label() { return app != null ? app.label : ""; }
    /** 正在外接屏上的应用。 */
    AppCatalog.App app() { return active ? app : null; }
    InputSink input() { return input; }

    int shizukuState() {
        try {
            if (!Shizuku.pingBinder()) return installed() ? SHIZUKU_NOT_RUNNING : SHIZUKU_NOT_INSTALLED;
            if (Shizuku.isPreV11() || Shizuku.getVersion() < SHIZUKU_MIN_VERSION) return SHIZUKU_TOO_OLD;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? SHIZUKU_READY : SHIZUKU_NO_PERMISSION;
        } catch (RuntimeException e) {
            Log.w(TAG, "Shizuku state", e);
            return SHIZUKU_NOT_RUNNING;
        }
    }

    private boolean installed() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    void requestPermission() {
        Shizuku.requestPermission(PERMISSION_REQUEST);
    }

    /** True when the user chose "deny and don't ask again" and Shizuku will not show a dialog any more. */
    boolean permissionDeniedForGood() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int[] logicalSize(Display d) {
        Point p = new Point();
        d.getRealSize(p);
        return new int[]{p.x, p.y};
    }

    /** Start the injector, then the app. Answers on the listener. */
    void start(AppCatalog.App a, Display d, float speed, boolean alwaysShow) {
        active = true;
        started = false;
        forwarding = false;
        appBehindDesktop = false;
        app = a;
        display = d;
        mouseSpeed = speed;
        alwaysShowCursor = alwaysShow;
        cursorPolicy.setProfile(AppInputProfile.forPackage(a.packageName));
        cursorPolicy.setAlwaysShow(alwaysShow);
        cursorPolicy.setHeight(logicalSize(d)[1]);
        cursor.attach(d);
        touchDots.attach(d);
        updateCursor();
        if (injector == null) audioRoute.setPending(true);
        Intent keepAlive = new Intent(context, DesktopService.class).putExtra(DesktopService.EXTRA_TITLE, "XDesk 正在外接屏上运行 " + a.label);
        context.startForegroundService(keepAlive);
        if (injector != null) {
            // 从 Termux 桌面切回来，注入服务一直留着，不用再等它启动
            new Thread(this::prepare, "xdesk-app-start").start();
            return;
        }
        ui.postDelayed(bindTimeout, BIND_TIMEOUT_MS);
        try {
            Shizuku.bindUserService(serviceArgs(), connection);
        } catch (RuntimeException e) {
            fail("无法启动注入服务：" + e.getMessage());
        }
    }

    private Shizuku.UserServiceArgs serviceArgs() {
        if (args == null) {
            // Tied to the APK's install time, so an update never talks to an old injector.
            int version = 1;
            try {
                version = (int) (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime / 1000);
            } catch (PackageManager.NameNotFoundException ignored) { }
            args = new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(), InjectorService.class.getName()))
                .daemon(false)
                .processNameSuffix("injector")
                .debuggable(false)
                .version(version);
        }
        return args;
    }

    /** Background thread: the calls below can take a moment. */
    private void prepare() {
        IInjector i = injector;
        Display d = display;
        if (i == null || d == null) return;
        try {
            int[] size = logicalSize(d);
            i.attach(fromInjector, context.getPackageName());
            String error = i.configure(fromInjector, d.getDisplayId(), size[0], size[1], app.packageName, mouseSpeed);
            if (error != null) {
                ui.post(() -> fail("无法注入输入：" + error));
                return;
            }
            i.setEnabled(true);
            i.setTouchMode(touchModeFor(app));
            i.setWatchSession(!alwaysShowCursor);
            ui.post(() -> audioRoute.attachInjector(i));
            loadDensity(i, d);
            // 启动之前设，冷启动时应用一开始就是对的大小。桌面窗口这时还在前面，它声明了自己处理 density，不会重建
            applyDensityFor(i, d, app);
            launch(i, d);
        } catch (RemoteException e) {
            ui.post(() -> fail("注入服务断开了：" + e.getMessage()));
        }
    }

    private void launch(IInjector i, Display d) throws RemoteException {
        // Another app's Presentation window would cover what we start. Say so, and leave that app alone.
        String presenting = i.findPresentation(d.getDisplayId());
        if (presenting != null) {
            Log.i(TAG, "presentation on the external display: " + presenting);
            ui.post(() -> {
                if (listener != null) listener.onAppMessage("外接屏上已有别的应用在显示内容，它可能盖住 " + label());
            });
        }
        String error = i.startApp(app.component(), d.getDisplayId());
        if (error != null) {
            Log.w(TAG, "am start: " + error);
            error = launchFromApp(app, d);
        }
        final String result = error;
        ui.post(() -> {
            if (!active) return;
            if (result != null) {
                fail("无法启动 " + label() + "：" + result);
                return;
            }
            started = true;
            if (forwardOnReturn) setForwarding(true);
            forwardOnReturn = false;
            if (listener != null) listener.onAppStarted();
        });
    }

    /**
     * Fallback when the injector cannot start it. setLaunchWindowingMode is hidden and may be refused for a normal
     * app. Without it the system picks the mode, which on a display in desktop mode can be a window.
     */
    private String launchFromApp(AppCatalog.App target, Display d) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName.unflattenFromString(target.component()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(d.getDisplayId());
            try {
                Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, WINDOWING_MODE_FULLSCREEN);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.i(TAG, "windowing mode not settable from the app: " + e);
            }
            context.startActivity(i, o.toBundle());
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    private void fail(String message) {
        if (!active) return;
        stop();
        if (listener != null) listener.onAppFailed(message);
    }

    /** The service died: Shizuku was stopped or the process was killed. Grabs ended with it. */
    private void lostInjector() {
        injector = null;
        audioRoute.detachInjector();
        forgetDensity(-1);
        forwarding = false;
        cursor.hide();
        // The sound route outlives the process, and only a new injector can set it right and later clear it
        ui.postDelayed(rebind, REBIND_DELAY_MS);
        if (!active) return;
        if (!started) {
            fail("注入服务断开了");
            return;
        }
        if (listener != null) listener.onAppStateChanged();
    }

    /** Shizuku 就绪就连上，声音输出才选得准。没有就算了：Termux 桌面不需要 Shizuku，也不弹授权。 */
    void connectForAudio() {
        if (injector != null || audioBinding || active || stopping || shizukuState() != SHIZUKU_READY) return;
        audioBinding = true;
        audioRoute.setPending(true);
        try {
            Shizuku.bindUserService(serviceArgs(), connection);
        } catch (RuntimeException e) {
            audioBinding = false;
            audioRoute.setPending(false);
            Log.w(TAG, "audio only bind", e);
        }
    }

    private void rebindInjector() {
        if (active) reconnect();
        else if (DesktopSession.get(context).isActive()) connectForAudio();
    }

    /** After Shizuku came back. */
    void reconnect() {
        if (!active || injector != null || shizukuState() != SHIZUKU_READY) return;
        try {
            Shizuku.bindUserService(serviceArgs(), connection);
        } catch (RuntimeException e) {
            Log.w(TAG, "rebind", e);
        }
    }

    void setForwarding(boolean on) {
        IInjector i = injector;
        try {
            if (i != null) i.setForwarding(on);
        } catch (RemoteException ignored) { }
    }

    /**
     * 切换外接屏上的应用，不回选择界面。已经在运行的应用只是回到前台：am start 用的是桌面点图标同样的 Intent 和标志，
     * 系统把现有任务拿到前面，不重启。成功后才换输入方式；失败就留在原来的应用上。
     */
    void switchTo(final AppCatalog.App a) {
        final IInjector i = injector;
        final Display d = display;
        if (i == null || d == null) return;
        new Thread(() -> {
            String error;
            try {
                // 启动之前设，这样冷启动的应用一开始就是对的大小。已经在运行的应用切回来也一样
                applyDensityFor(i, d, a);
                error = i.startApp(a.component(), d.getDisplayId());
                if (error != null) {
                    Log.w(TAG, "am start: " + error);
                    error = launchFromApp(a, d);
                }
                if (error == null) {
                    i.setApp(a.packageName);
                    i.setTouchMode(touchModeFor(a));
                } else {
                    applyDensityFor(i, d, app);  // 还留在原来的应用上
                }
            } catch (RemoteException e) {
                error = "注入服务断开了";
            }
            final String result = error;
            ui.post(() -> {
                if (result != null) {
                    if (listener != null) listener.onAppMessage("无法切换到 " + a.label + "：" + result);
                    return;
                }
                if (!active) return;
                app = a;
                cursorPolicy.setProfile(AppInputProfile.forPackage(a.packageName));
                updateCursor();
                context.startForegroundService(new Intent(context, DesktopService.class)
                    .putExtra(DesktopService.EXTRA_TITLE, "XDesk 正在外接屏上运行 " + a.label));
                if (listener != null) listener.onAppStateChanged();
            });
        }, "xdesk-app-switch").start();
    }

    /**
     * 外接屏要换成 Termux 桌面。应用的任务留在后面，注入服务也留着（切回来快）。键鼠交还，热键关掉，光标去掉：
     * 桌面有自己的光标，也通过安卓读同一套键鼠。
     */
    void suspend() {
        if (!active) return;
        forwardOnReturn = forwarding;
        appBehindDesktop = started;
        ui.removeCallbacks(bindTimeout);
        ui.removeCallbacks(cursorTimer);
        setForwarding(false);
        IInjector i = injector;
        try {
            if (i != null) i.setEnabled(false);
            // 桌面有自己的 DPI 设置，外接屏回到默认再放桌面窗口。切回应用时再设
            if (i != null && display != null) {
                i.setDisplayDensity(display.getDisplayId(), -1);
                forgetDensity(0);
            }
        } catch (RemoteException ignored) { }
        active = false;
        started = false;
        forwarding = false;
        display = null;
        cursor.remove();
        touchDots.remove();
    }

    private void loadDensity(IInjector i, Display d) throws RemoteException {
        String unique = i.displayUniqueId(d.getDisplayId());
        // The name is less exact, but better than not remembering
        densityKey = unique != null ? unique : "name:" + d.getName();
        defaultDensity = i.displayDefaultDensity(d.getDisplayId());
    }

    /** 这个应用该用的值：它自己的，否则这台显示器的默认值，都没有是 0。 */
    private int wantedDensity(AppCatalog.App a) {
        int own = appDensity(a);
        return own > 0 ? own : savedDensity();
    }

    /** 值没变就什么都不做：每次改动应用都要重新布局。 */
    private void applyDensityFor(IInjector i, Display d, AppCatalog.App a) throws RemoteException {
        synchronized (densityLock) {
            int want = wantedDensity(a);
            if (want == appliedDensity) return;
            // 不知道留着什么时，清除要不看是谁设的
            String error = i.setDisplayDensity(d.getDisplayId(), want > 0 ? want : appliedDensity < 0 ? 0 : -1);
            if (error != null) densityFailed(error);
            else appliedDensity = want;
        }
    }

    private void forgetDensity(int value) {
        synchronized (densityLock) {
            appliedDensity = value;
        }
    }

    /** 这个应用自己的值，跟随默认时是 0。 */
    int appDensity(AppCatalog.App a) {
        return a != null ? appDensities.getInt(a.packageName, 0) : 0;
    }

    /** @param dpi 0 跟随默认 */
    void setAppDensity(AppCatalog.App a, int dpi) {
        if (dpi > 0) appDensities.edit().putInt(a.packageName, dpi).apply();
        else appDensities.edit().remove(a.packageName).apply();
        if (active && started && app != null && app.packageName.equals(a.packageName)) applyCurrentDensity(false);
    }

    private void applyCurrentDensity(final boolean forceClear) {
        final IInjector i = injector;
        final Display d = display;
        final AppCatalog.App current = app;
        if (i == null || d == null) return;
        new Thread(() -> {
            try {
                synchronized (densityLock) {
                    if (forceClear && wantedDensity(current) == 0) appliedDensity = -1;
                    applyDensityFor(i, d, current);
                }
            } catch (RemoteException e) {
                densityFailed("注入服务断开了");
            }
        }, "xdesk-density").start();
    }

    private void densityFailed(String error) {
        Log.w(TAG, "density: " + error);
        ui.post(() -> {
            if (listener != null) listener.onAppMessage("无法改外接屏界面大小：" + error);
        });
    }

    /** 滑块能不能用：外接屏上是安卓应用，注入服务在。 */
    boolean canSetDensity() {
        return active && started && injector != null && display != null && densityKey != null;
    }

    /** 显示器自己的 dpi，不知道时是 0。 */
    int defaultDensity() {
        return defaultDensity;
    }

    /** 用户设的值，没设过是 0。 */
    int savedDensity() {
        String key = densityKey;
        return key != null ? densities.getInt(key, 0) : 0;
    }

    /**
     * 这台显示器的默认值。外接屏上的应用有自己的值时，它不跟着变。
     *
     * @param dpi 0 回到显示器自己的，这时也清掉不是我们设的（比如手动 wm density）
     */
    void setDensity(int dpi) {
        String key = densityKey;
        if (key == null) return;
        if (dpi > 0) densities.edit().putInt(key, dpi).apply();
        else densities.edit().remove(key).apply();
        applyCurrentDensity(dpi == 0);
    }

    /** 触控模式还是键鼠模式，记在每个包上（键名沿用以前的手指设置，存过的自动算触控），默认键鼠。有自己鼠标方式的应用（RustDesk）不用触摸模式。 */
    private boolean touchModeFor(AppCatalog.App a) {
        return canUseTouchMode(a) && touchModes.getBoolean(a.packageName, false);
    }

    private static boolean canUseTouchMode(AppCatalog.App a) {
        return a != null && !AppInputProfile.forPackage(a.packageName).hoverButtons;
    }

    /** 外接屏上的应用能不能切换。 */
    boolean canUseTouchMode() {
        return active && canUseTouchMode(app);
    }

    boolean isTouchMode() {
        return active && touchModeFor(app);
    }

    void toggleTouchMode() {
        if (canUseTouchMode()) setTouchMode(app, !touchModeFor(app));
    }

    /** 顶部按钮和设置卡片改的是同一个值。 */
    void setTouchMode(AppCatalog.App a, boolean on) {
        touchModes.edit().putBoolean(a.packageName, on).apply();
        updateCursor();
        IInjector i = injector;
        if (i == null || !active || app == null || !app.packageName.equals(a.packageName)) return;
        try {
            i.setTouchMode(on);
        } catch (RemoteException ignored) { }
    }

    boolean isTouchMode(AppCatalog.App a) {
        return touchModeFor(a);
    }

    static boolean touchModeAllowed(AppCatalog.App a) {
        return canUseTouchMode(a);
    }

    /** 触控模式：多指的一帧。外接屏上的点由注入那边告诉我们，它才知道手指实际在哪。 */
    void touchFrame(int action, int[] ids, float[] x, float[] y) {
        IInjector i = injector;
        try {
            if (i != null) i.touchFrame(action, ids, x, y);
        } catch (RemoteException ignored) { }
    }

    /** 外接屏的逻辑尺寸，不知道时是 null。 */
    int[] displaySize() {
        Display d = display;
        return d != null ? logicalSize(d) : null;
    }

    /** 关闭应用要靠注入服务。 */
    boolean canCloseApps() {
        return injector != null;
    }

    /** Back 之后外接屏前面是什么。还是原来的应用就什么都不做。 */
    private void topAppChanged(String pkg) {
        if (!active || !started) return;
        if (app != null && app.packageName.equals(pkg)) return;
        AppCatalog.App now = pkg.isEmpty() ? null : AppCatalog.get(context).findPackage(pkg);
        if (now == null) {
            currentAppGone();
            return;
        }
        // 露出了后面的另一个应用：输入方式和界面大小跟着它
        app = now;
        IInjector i = injector;
        try {
            if (i != null) {
                i.setApp(now.packageName);
                i.setTouchMode(touchModeFor(now));
            }
        } catch (RemoteException ignored) { }
        cursorPolicy.setProfile(AppInputProfile.forPackage(now.packageName));
        updateCursor();
        applyCurrentDensity(false);
        if (listener != null) listener.onAppStateChanged();
    }

    /** 输入方式回到普通，界面值回到默认，让用户选下一个。 */
    private void currentAppGone() {
        if (app == null) return;
        app = null;
        IInjector i = injector;
        try {
            if (i != null) i.setApp("");
        } catch (RemoteException ignored) { }
        cursorPolicy.setProfile(AppInputProfile.forPackage(null));
        updateCursor();
        applyCurrentDensity(false);
        if (listener != null) {
            listener.onAppStateChanged();
            listener.onAppGone();
        }
    }

    void sendBack() {
        IInjector i = injector;
        try {
            if (i != null) i.sendBack();
        } catch (RemoteException ignored) { }
    }

    /**
     * 关闭一个应用（移除它的任务，和从最近任务划掉一样）。如果它正在外接屏上，外接屏上就没有我们放的应用了，
     * 见 currentAppGone。
     */
    void closeApp(final AppCatalog.App a) {
        final IInjector i = injector;
        if (i == null) return;
        new Thread(() -> {
            String error;
            try {
                error = i.closeApp(a.packageName);
            } catch (RemoteException e) {
                error = "注入服务断开了";
            }
            final String result = error;
            ui.post(() -> {
                if (result != null) {
                    if (listener != null) listener.onAppMessage("无法关闭 " + a.label + "：" + result);
                    return;
                }
                if (!active || app == null || !app.packageName.equals(a.packageName)) return;
                currentAppGone();
            });
        }, "xdesk-app-close").start();
    }

    void setAlwaysShowCursor(boolean always) {
        alwaysShowCursor = always;
        cursorPolicy.setAlwaysShow(always);
        updateCursor();
        IInjector i = injector;
        try {
            if (i != null) i.setWatchSession(!always);
        } catch (RemoteException ignored) { }
    }

    void setMouseSpeed(float speed) {
        mouseSpeed = speed;
        IInjector i = injector;
        try {
            if (i != null) i.setMouseSpeed(speed);
        } catch (RemoteException ignored) { }
    }


    void setHotkeyAllowed(boolean allowed) {
        IInjector i = injector;
        try {
            if (i != null) i.setHotkeyAllowed(allowed);
        } catch (RemoteException ignored) { }
    }

    void focusExternal() {
        IInjector i = injector;
        try {
            if (i != null) i.focusExternal();
        } catch (RemoteException ignored) { }
    }

    void phoneTouched() {
        IInjector i = injector;
        try {
            if (i != null) i.phoneTouched();
        } catch (RemoteException ignored) { }
    }

    /** The display id is not stable across replugging, so follow the events and never remember one for longer. */
    void displayRemoved(int id) {
        if (display == null || display.getDisplayId() != id) return;
        display = null;
        forgetDensity(0);
        setForwarding(false);
        cursor.remove();
        touchDots.remove();
        if (listener != null) listener.onAppStateChanged();
    }

    void displayAdded(Display d) {
        if (!active || !started || display != null) return;
        display = d;
        forgetDensity(0);
        cursorPolicy.setHeight(logicalSize(d)[1]);
        cursor.attach(d);
        touchDots.attach(d);
        final IInjector i = injector;
        if (i != null) {
            new Thread(() -> {
                try {
                    int[] size = logicalSize(d);
                    i.setDisplay(d.getDisplayId(), size[0], size[1]);
                    loadDensity(i, d);
                    applyDensityFor(i, d, app);
                    if (app != null) launch(i, d);
                } catch (RemoteException e) {
                    Log.w(TAG, "move to new display", e);
                }
            }, "xdesk-app-move").start();
        }
        if (listener != null) listener.onAppStateChanged();
    }

    void displayChanged(int id) {
        Display d = display;
        IInjector i = injector;
        if (d == null || i == null || d.getDisplayId() != id) return;
        int[] size = logicalSize(d);
        cursorPolicy.setHeight(size[1]);
        cursor.displayChanged(d);
        touchDots.displayChanged(d);
        try {
            i.setDisplay(id, size[0], size[1]);
        } catch (RemoteException ignored) { }
    }

    /** Stop forwarding, remove the cursor and clear the sound route. The app itself keeps running, and so does any Termux desktop. */
    void stop() {
        stop(-1, null);
    }

    /**
     * The whole session ends (XDesk was swiped away, or 结束 XDesk): the same as stop, but apps on the external display first go back to
     * the phone, not closed. Without an injector that cannot happen and they stay where they are.
     *
     * @param externalDisplay the display to empty, or -1
     * @param done runs on the main thread when it is over
     */
    void endSession(int externalDisplay, Runnable done) {
        stop(externalDisplay, done);
    }

    private void stop(final int moveFrom, final Runnable done) {
        ui.removeCallbacks(bindTimeout);
        ui.removeCallbacks(cursorTimer);
        ui.removeCallbacks(rebind);
        forwardOnReturn = false;
        audioBinding = false;
        appBehindDesktop = false;
        stopping = true;
        forgetDensity(0);  // the injector clears what it set before it ends
        audioRoute.detachInjector();
        boolean desktopActive = DesktopSession.get(context).isActive();
        active = false;
        started = false;
        forwarding = false;
        display = null;
        cursor.remove();
        touchDots.remove();
        final IInjector i = injector;
        injector = null;
        final Shizuku.UserServiceArgs a = args;
        new Thread(() -> {
            try {
                if (i != null && moveFrom >= 0) {
                    String error = i.returnToPhone(moveFrom);
                    if (error != null) {
                        Log.w(TAG, "tasks not moved back: " + error);
                        ui.post(() -> android.widget.Toast.makeText(context, "有的应用没能移回手机：" + error, android.widget.Toast.LENGTH_LONG).show());
                    }
                }
                // Lifts everything held before the process goes away
                if (i != null) i.shutdown();
            } catch (RemoteException ignored) { }
            try {
                if (a != null && Shizuku.pingBinder()) Shizuku.unbindUserService(a, connection, true);
            } catch (RuntimeException e) {
                Log.w(TAG, "unbind", e);
            }
            ui.post(() -> {
                stopping = false;
                if (done != null) done.run();
                rebindInjector();
            });
        }, "xdesk-app-stop").start();
        // endSession keeps the foreground service until the moves are done, or the process could be killed before that
        if (done == null && !desktopActive) DesktopService.stopIfIdle(context);
    }
}
