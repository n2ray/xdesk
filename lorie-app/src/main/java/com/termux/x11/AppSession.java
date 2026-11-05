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
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private static AppSession instance;

    static synchronized AppSession get(Context c) {
        if (instance == null) instance = new AppSession(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CursorOverlay cursor;
    private Listener listener;
    private boolean active, forwarding, started;
    private int deviceCount;
    private ChooserView.App app;
    private Display display;
    private float mouseSpeed = 1f;
    private volatile IInjector injector;
    private Shizuku.UserServiceArgs args;

    private final IInjectorListener.Stub fromInjector = new IInjectorListener.Stub() {
        @Override public void onCursor(final int x, final int y) {
            ui.post(() -> {
                if (active) cursor.moveTo(x, y);
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
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            ui.removeCallbacks(bindTimeout);
            if (!active || binder == null || !binder.pingBinder()) return;
            injector = IInjector.Stub.asInterface(binder);
            new Thread(AppSession.this::prepare, "xdesk-app-start").start();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            lostInjector();
        }
    };

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
        Shizuku.addBinderReceivedListenerSticky(this::shizukuChanged);
        Shizuku.addBinderDeadListener(this::shizukuChanged);
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code == PERMISSION_REQUEST) shizukuChanged();
        });
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
    void start(ChooserView.App a, Display d, float speed) {
        active = true;
        started = false;
        forwarding = false;
        app = a;
        display = d;
        mouseSpeed = speed;
        cursor.attach(d);
        Intent keepAlive = new Intent(context, DesktopService.class).putExtra(DesktopService.EXTRA_TITLE, "XDesk 正在外接屏上运行 " + a.label);
        context.startForegroundService(keepAlive);
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
            String error = i.configure(fromInjector, d.getDisplayId(), size[0], size[1], app.packageName, mouseSpeed);
            if (error != null) {
                ui.post(() -> fail("无法注入输入：" + error));
                return;
            }
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
            error = launchFromApp(d);
        }
        final String result = error;
        ui.post(() -> {
            if (!active) return;
            if (result != null) {
                fail("无法启动 " + label() + "：" + result);
                return;
            }
            started = true;
            if (listener != null) listener.onAppStarted();
        });
    }

    /**
     * Fallback when the injector cannot start it. setLaunchWindowingMode is hidden and may be refused for a normal
     * app. Without it the system picks the mode, which on a display in desktop mode can be a window.
     */
    private String launchFromApp(Display d) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName.unflattenFromString(app.component()))
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
        forwarding = false;
        cursor.hide();
        if (!active) return;
        if (!started) {
            fail("注入服务断开了");
            return;
        }
        if (listener != null) listener.onAppStateChanged();
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
        try {
            i.setDisplay(id, size[0], size[1]);
        } catch (RemoteException ignored) { }
    }

    /** Stop forwarding and remove the cursor. The app itself keeps running, and so does any Termux desktop. */
    void stop() {
        ui.removeCallbacks(bindTimeout);
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
                // Lifts everything held before the process goes away
                if (i != null) i.shutdown();
            } catch (RemoteException ignored) { }
            try {
                if (a != null && Shizuku.pingBinder()) Shizuku.unbindUserService(a, connection, true);
            } catch (RuntimeException e) {
                Log.w(TAG, "unbind", e);
            }
        }, "xdesk-app-stop").start();
        if (!desktopActive) context.stopService(new Intent(context, DesktopService.class));
    }
}
