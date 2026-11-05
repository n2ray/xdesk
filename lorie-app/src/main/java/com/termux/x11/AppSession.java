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
    }

    static final int SHIZUKU_READY = 0, SHIZUKU_NOT_INSTALLED = 1, SHIZUKU_NOT_RUNNING = 2, SHIZUKU_NO_PERMISSION = 3, SHIZUKU_TOO_OLD = 4;
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static final String TAG = "XDesk";
    private static final int PERMISSION_REQUEST = 47131;
    /** Shizuku's first version with user services. */
    private static final int SHIZUKU_MIN_VERSION = 10;
    private static final long BIND_TIMEOUT_MS = 15000;
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
    private final AudioRoute audioRoute;
    /** 接管过键鼠又切去了 Termux 桌面：切回来时接着接管。用户没要过就不会自己接管。 */
    private boolean forwardOnReturn;
    private boolean alwaysShowCursor;
    /** Termux 桌面也要靠注入服务选声音输出：只为这个连上，不读键鼠。 */
    private boolean audioBinding;
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
                cursorPolicy.moved(y);
                cursor.moveTo(x, y);
                updateCursor();
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
        @Override public void onButtonUp() {
            ui.post(() -> {
                cursorPolicy.buttonUp(SystemClock.uptimeMillis());
                updateCursor();
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
        audioRoute = AudioRoute.get(c);
        touchModes = c.getSharedPreferences("app_touch_mode", Context.MODE_PRIVATE);
        Shizuku.addBinderReceivedListenerSticky(this::shizukuChanged);
        Shizuku.addBinderDeadListener(this::shizukuChanged);
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code == PERMISSION_REQUEST) shizukuChanged();
        });
    }

    private void updateCursor() {
        long now = SystemClock.uptimeMillis();
        cursor.setSuppressed(!cursorPolicy.visible(now));
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
        app = a;
        display = d;
        mouseSpeed = speed;
        alwaysShowCursor = alwaysShow;
        cursorPolicy.setProfile(AppInputProfile.forPackage(a.packageName));
        cursorPolicy.setAlwaysShow(alwaysShow);
        cursorPolicy.setHeight(logicalSize(d)[1]);
        cursor.attach(d);
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
        if (injector != null || audioBinding || active || shizukuState() != SHIZUKU_READY) return;
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
                error = i.startApp(a.component(), d.getDisplayId());
                if (error != null) {
                    Log.w(TAG, "am start: " + error);
                    error = launchFromApp(a, d);
                }
                if (error == null) {
                    i.setApp(a.packageName);
                    i.setTouchMode(touchModeFor(a));
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
        ui.removeCallbacks(bindTimeout);
        ui.removeCallbacks(cursorTimer);
        setForwarding(false);
        IInjector i = injector;
        try {
            if (i != null) i.setEnabled(false);
        } catch (RemoteException ignored) { }
        active = false;
        started = false;
        forwarding = false;
        display = null;
        cursor.remove();
    }

    /** 记在每个包上，默认鼠标。有自己鼠标方式的应用（RustDesk）不用触摸模式。 */
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
        if (!canUseTouchMode()) return;
        boolean on = !touchModeFor(app);
        touchModes.edit().putBoolean(app.packageName, on).apply();
        IInjector i = injector;
        try {
            if (i != null) i.setTouchMode(on);
        } catch (RemoteException ignored) { }
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
        setForwarding(false);
        cursor.remove();
        if (listener != null) listener.onAppStateChanged();
    }

    void displayAdded(Display d) {
        if (!active || !started || display != null) return;
        display = d;
        cursorPolicy.setHeight(logicalSize(d)[1]);
        cursor.attach(d);
        final IInjector i = injector;
        if (i != null) {
            new Thread(() -> {
                try {
                    int[] size = logicalSize(d);
                    i.setDisplay(d.getDisplayId(), size[0], size[1]);
                    launch(i, d);
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
        try {
            i.setDisplay(id, size[0], size[1]);
        } catch (RemoteException ignored) { }
    }

    /** Stop forwarding, remove the cursor and clear the sound route. The app itself keeps running, and so does any Termux desktop. */
    void stop() {
        stop(-1, null);
    }

    /**
     * The whole session ends (XDesk was swiped away): the same as stop, but apps on the external display first go back to
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
        audioRoute.detachInjector();
        boolean desktopActive = DesktopSession.get(context).isActive();
        active = false;
        started = false;
        forwarding = false;
        display = null;
        cursor.remove();
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
            if (done != null) ui.post(done);
        }, "xdesk-app-stop").start();
        // endSession keeps the foreground service until the moves are done, or the process could be killed before that
        if (done == null && !desktopActive) context.stopService(new Intent(context, DesktopService.class));
    }
}
