package com.termux.x11;

import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One session of the injector, as the shell user (uid 2000), which may inject input into other apps and read /dev/input.
 * Normal apps have neither. There are two hosts:
 * - As a Shizuku user service, this is a whole process of this APK's code. It dies with the app (daemon(false) and the
 *   link-to-death below) and every grab ends with the process, so a crash cannot take the keyboard away for good.
 * - In ShellServer, the process stays because starting it again may need Wi-Fi. So everything a session holds is given
 *   back here when the session ends, and only XDesk's uid may call.
 *
 * All input from all threads goes through one worker, in order, because InputInjector sleeps between button changes.
 */
public class InjectorService extends IInjector.Stub {
    private static final String TAG = "XDeskInjector";
    private static final int NO_UID = -1;
    private static final String OVER = ShellText.code(ShellText.SESSION_OVER);
    private static final long AM_TIMEOUT_MS = 10000, DUMPSYS_TIMEOUT_MS = 5000, SHUTDOWN_TIMEOUT_MS = 2000;
    /** Time for a focus change to reach the input system before a key is sent. */
    private static final int FOCUS_SETTLE_MS = 60;
    /** 2 felt right for a physical mouse on a 2560 wide display. */
    private static final float MOUSE_GAIN = 2f, MOUSE_REFERENCE_WIDTH = 2560f;
    /** The touchpad's gain was tuned on a 1920 wide desktop. */
    private static final float PAD_REFERENCE_WIDTH = 1920f;
    private static final float WHEEL_STEP = 1f;
    private static final long KEY_REPEAT_DELAY_MS = 400, KEY_REPEAT_MS = 40;
    private static final long SESSION_POLL_MS = 1000;
    /** How long one drain runs before the worker may take a timer (key repeat, a swipe's end) in between. */
    private static final long DRAIN_MS = 8;
    private static final int BTN_LEFT = 272, BTN_RIGHT = 273, BTN_MIDDLE = 274, BTN_SIDE = 275, BTN_EXTRA = 276,
        BTN_FORWARD = 277, BTN_BACK = 278;

    private interface Task {
        void run() throws Exception;
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    /** Tasks in the order they came, run by one drain on the worker: a burst of moves or wheel ticks is one task. */
    private final ArrayDeque<Task> pending = new ArrayDeque<>();
    private boolean draining;
    private final InputInjector injector;
    private final String injectorError;
    private EvdevReader reader;
    private volatile IInjectorListener listener;
    private volatile float mouseSpeed = 1f;
    /** The phone was touched since the external display last had focus. */
    private volatile boolean focusStale;
    private volatile boolean hotkeyAllowed = true;
    /** Game passthrough (GAMING.md): the keys and mouse are the app's own, nothing grabbed, the external display focused. */
    private volatile boolean gameMode;
    /** Counts key presses, so a timer from an earlier press of the same key stops. */
    private int repeatPress;
    private final AudioRouter audio = new AudioRouter();
    private final DensityOverride density = new DensityOverride();
    private final PanelFit panels = new PanelFit();
    private final ImePlacement imes = new ImePlacement();
    /** All three on the worker: the browser's layout (null without one), whether input is taken over, and the keyboard for that. */
    private String webLayout;
    private boolean takenOver;
    private UhidKeyboard webKeyboard;
    /** The on-screen gamepad (GAMEPAD.md), made on first use and closed with the session. */
    private final GamepadHid gamepad = new GamepadHid();
    private final OutputCapture output = new OutputCapture();
    private volatile boolean enabled = true, watchSession, inSession;
    private volatile String packageName;
    private volatile int displayId = -1;
    /** The pointer over apps, drawn here when XDesk asks (pointerLayer); moved with every place the injector makes. */
    private final PointerLayer pointer = new PointerLayer();
    /** The privacy cover's mute of the external screen's apps; whatever a crash left silenced goes back at once. */
    private final CoverMute coverMute = new CoverMute();
    { new Thread(coverMute::restore, "xdesk-cover-restore").start(); }
    private Thread sessionWatcher;
    private String ownPackage;
    private boolean linked;
    private volatile boolean sessionOver;
    /** Said once per session that the system refuses injection, see refused. */
    private volatile boolean toldInjectionRefused;
    private final int onlyUid;
    /** Displays this session made for apps, by id, see AppDisplay. They end with the session. */
    private final HashMap<Integer, VirtualDisplay> appDisplays = new HashMap<>();

    private static final int BACK_CHECK_MS = 800;
    private final boolean[] sideButtonIsKey = new boolean[2];

    /**
     * RustDesk in a session keeps the mouse's side buttons as they always went, because they belong to the remote machine.
     * Elsewhere a side button is Back or Forward: as a mouse button it would only act as a press at the cursor, because
     * Android turns these into keys for a real mouse but not for an injected one.
     */
    private boolean sideButtonsAreKeys() {
        boolean remote = AppInputProfile.forPackage(packageName).hoverButtons && (inSession || !watchSession);
        return !remote;
    }

    /** Back on an app's top screen can close it or uncover something else, so look at what is in front afterwards. */
    private void checkTopAfterBack(int keyCode) {
        if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return;
        worker.schedule(() -> post(() -> {
            String own = ownPackage;
            if (own == null) return;
            String top = TaskMover.topPackage(injector.displayId(), own);
            IInjectorListener l = listener;
            if (top != null && l != null) l.onTopApp(top);
        }), BACK_CHECK_MS, TimeUnit.MILLISECONDS);
    }

    /** The web keyboard exists while a browser is here and input is taken over. On the worker. */
    private void updateWebKeyboard() {
        String wanted = takenOver ? webLayout : null;
        if (webKeyboard != null && !webKeyboard.layout().equals(wanted)) {
            webKeyboard.close();
            webKeyboard = null;
        }
        if (wanted != null && webKeyboard == null) webKeyboard = UhidKeyboard.create(wanted);
    }

    private void closeWebKeyboard() {
        if (webKeyboard != null) webKeyboard.close();
        webKeyboard = null;
    }

    private final EvdevReader.Sink physical = new EvdevReader.Sink() {
        @Override public void onForwarding(final boolean on, final int devices) {
            post(() -> {
                takenOver = on;
                updateWebKeyboard();
                if (on) {
                    injector.take();
                    focusStale = false;
                    // Candidates for the physical keyboard next to the text, not on the phone
                    if (webKeyboard != null || ImePlacement.hasPhysicalKeyboard()) imes.place(injector.displayId(), true);
                } else {
                    injector.releaseAll();
                    injector.hoverExit();
                    imes.place(injector.displayId(), false);
                }
                IInjectorListener l = listener;
                if (l != null) l.onForwarding(on, devices);
            });
        }
        @Override public void onDeviceCount(final int devices, final boolean removed) {
            post(() -> {
                // A key or button held on a device that went away would stay down for ever
                if (removed) injector.releaseAll();
                IInjectorListener l = listener;
                if (l != null) l.onForwarding(true, devices);
            });
        }
        @Override public void onModifiersUp() {
            post(injector::clearModifiers);
        }
        @Override public void onMove(final int dx, final int dy) {
            post(new MoveTask(true, dx, dy));
        }
        @Override public void onWheel(final int vertical, final int horizontal) {
            postScroll(vertical * WHEEL_STEP, horizontal * WHEEL_STEP);
        }
        @Override public void onButton(int evdevCode, final boolean down) {
            // Mice name their side buttons either way: BTN_BACK and BTN_FORWARD are BTN_SIDE and BTN_EXTRA here
            final int code = evdevCode == BTN_BACK ? BTN_SIDE : evdevCode == BTN_FORWARD ? BTN_EXTRA : evdevCode;
            if (code == BTN_SIDE || code == BTN_EXTRA) {
                int side = code == BTN_SIDE ? 0 : 1;
                // Decided on the press, so a press and its release always go the same way
                if (down) sideButtonIsKey[side] = sideButtonsAreKeys();
                if (sideButtonIsKey[side]) {
                    if (!down) return;
                    final int keyCode = code == BTN_SIDE ? android.view.KeyEvent.KEYCODE_BACK : android.view.KeyEvent.KEYCODE_FORWARD;
                    post(() -> {
                        injector.focus(injector.displayId());
                        Thread.sleep(FOCUS_SETTLE_MS);
                        focusStale = false;
                        injector.androidKey(keyCode);
                        checkTopAfterBack(keyCode);
                    });
                    return;
                }
            }
            post(() -> {
                int bit = androidButton(code);
                if (bit != 0) injector.button(bit, down);
                if (!down) notifyButtonUp();
            });
        }
        @Override public void onKey(final int code, final int value) {
            post(() -> {
                if (focusStale) {
                    injector.focus(injector.displayId());
                    Thread.sleep(FOCUS_SETTLE_MS);
                    focusStale = false;
                }
                injector.key(code, value);
                if (value == 1) startRepeat(code);
            });
        }
        @Override public void onGameKey() {
            game(!gameMode, true);
        }
        @Override public void onKeyboardUsed() {
            post(() -> {
                if (!focusStale) return;
                injector.focus(injector.displayId());
                focusStale = false;
            });
        }
    };

    /** Started by name by Shizuku, which also decides who gets the Binder. */
    public InjectorService() {
        this(NO_UID);
    }

    /** @param onlyUid the only caller allowed, and the process outlives this session. See ShellServer. */
    InjectorService(int onlyUid) {
        this.onlyUid = onlyUid;
        // Grabs end with the process by themselves, the sound route does not
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            Log.e(TAG, "injector crashed", e);
            audio.clear(false);
            imes.restoreAll();
            density.clearOurs();
            panels.clearOurs();
            System.exit(1);
        });
        InputInjector i = null;
        String error = null;
        try {
            i = new InputInjector(new InputInjector.CursorListener() {
                @Override public void onCursor(int x, int y) {
                    pointer.moveTo(x, y);
                    IInjectorListener l = listener;
                    try {
                        if (l != null) l.onCursor(x, y);
                    } catch (RemoteException ignored) { }
                }
                @Override public void onFingers(float[] x, float[] y) {
                    IInjectorListener l = listener;
                    try {
                        if (l != null) l.onFingers(x, y);
                    } catch (RemoteException ignored) { }
                }
                @Override public void onEdgePush(int edge, int dx, int dy, boolean held, int x, int y, boolean left) {
                    IInjectorListener l = listener;
                    try {
                        if (l != null) l.onEdgePush(edge, dx, dy, held, x, y, left);
                    } catch (RemoteException ignored) { }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "no input injection", e);
            error = e.toString();
        }
        injector = i;
        injectorError = error;
    }

    /** IInjector.destroy: Shizuku itself calls it when it removes the service, so it is not XDesk's alone. */
    private static final int TRANSACTION_DESTROY = 16777114;
    /** XDesk's app id, looked up once for the Shizuku path, which is handed no uid. 0 unknown, -1 lookup failed. */
    private static volatile int xdeskAppId;

    /**
     * Whether this caller is XDesk. Only shell-user processes run this code, so the package manager must be asked;
     * an unknown answer keeps the Shizuku path working, where Shizuku already gives the Binder to XDesk alone.
     */
    private static boolean isXDesk(int uid) {
        int app = uid % 100000;  // UserHandle.getAppId (hidden): the uid within its user
        if (xdeskAppId == app) return true;
        if (xdeskAppId == -1) return true;
        synchronized (InjectorService.class) {
            if (xdeskAppId == app) return true;
            if (xdeskAppId == -1) return true;
            try {
                Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "package");
                Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
                Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                String[] pkgs = (String[]) ipm.getMethod("getPackagesForUid", int.class).invoke(pm, uid);
                if (pkgs != null) for (String p : pkgs) {
                    if (BuildConfig.APPLICATION_ID.equals(p)) {
                        xdeskAppId = app;
                        return true;
                    }
                }
                return false;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.w(TAG, "no package for the caller", e);
                xdeskAppId = -1;
                return true;
            }
        }
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        int caller = Binder.getCallingUid();
        if (onlyUid != NO_UID) {
            if (caller != onlyUid) throw new SecurityException("not XDesk");
        } else if (code != TRANSACTION_DESTROY && !isXDesk(caller)) {
            // Defence in depth: Shizuku decides who gets the Binder, but nothing else may use it either
            throw new SecurityException("not XDesk");
        }
        return super.onTransact(code, data, reply, flags);
    }

    private static int androidButton(int code) {
        switch (code) {
            case BTN_LEFT: return MotionEvent.BUTTON_PRIMARY;
            case BTN_RIGHT: return MotionEvent.BUTTON_SECONDARY;
            case BTN_MIDDLE: return MotionEvent.BUTTON_TERTIARY;
            case BTN_SIDE: return MotionEvent.BUTTON_BACK;
            case BTN_EXTRA: return MotionEvent.BUTTON_FORWARD;
            default: return 0;
        }
    }

    /** Input and other work of a session that is still on. */
    private void post(final Task t) {
        if (!sessionOver) enqueue(t);
    }

    /** injectInputEvent throws SecurityException when the system does not let the shell user inject, as MIUI does by default. */
    private static boolean refused(Exception e) {
        return e instanceof SecurityException || (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() instanceof SecurityException);
    }

    /** A mouse move not yet injected. The phone's pad and a physical mouse use different gains, so only equal ones merge. */
    private final class MoveTask implements Task {
        final boolean physical;
        float dx, dy;

        MoveTask(boolean physical, float dx, float dy) {
            this.physical = physical;
            this.dx = dx;
            this.dy = dy;
        }

        @Override public void run() throws Exception {
            float gain = physical
                ? MOUSE_GAIN * injector.width() / MOUSE_REFERENCE_WIDTH * mouseSpeed
                : injector.width() / PAD_REFERENCE_WIDTH;
            injector.moveBy(dx * gain, dy * gain);
        }
    }

    /** Wheel steps not yet injected; in touch mode they become a swipe's moves there. */
    private final class ScrollTask implements Task {
        float vertical, horizontal;

        ScrollTask(float vertical, float horizontal) {
            this.vertical = vertical;
            this.horizontal = horizontal;
        }

        @Override public void run() throws Exception {
            injector.scroll(vertical, horizontal);
            if (injector.isSwiping()) worker.schedule(() -> post(injector::endSwipeIfIdle), InputInjector.SWIPE_IDLE_MS + 20, TimeUnit.MILLISECONDS);
        }
    }

    private void enqueue(final Task t) {
        if (worker.isShutdown() || injector == null) return;
        synchronized (pending) {
            // Only with the task not started yet: the order of keys and buttons, and of what came before them, stays exact
            Task tail = pending.peekLast();
            if (t instanceof MoveTask && tail instanceof MoveTask && ((MoveTask) tail).physical == ((MoveTask) t).physical) {
                MoveTask m = (MoveTask) tail;
                m.dx += ((MoveTask) t).dx;
                m.dy += ((MoveTask) t).dy;
                return;
            }
            if (t instanceof ScrollTask && tail instanceof ScrollTask) {
                ScrollTask s = (ScrollTask) tail;
                s.vertical += ((ScrollTask) t).vertical;
                s.horizontal += ((ScrollTask) t).horizontal;
                return;
            }
            pending.addLast(t);
            if (draining) return;
            draining = true;
        }
        scheduleDrain();
    }

    private void scheduleDrain() {
        try {
            worker.execute(this::drain);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The executor ended between the check and the submit: run what was accepted, as it would have
            drain();
        }
    }

    private void drain() {
        long until = SystemClock.uptimeMillis() + DRAIN_MS;
        while (true) {
            Task t;
            synchronized (pending) {
                t = pending.pollFirst();
                if (t == null) {
                    draining = false;
                    return;
                }
            }
            try {
                t.run();
            } catch (Exception e) {
                Log.w(TAG, "input failed", e);
                if (!toldInjectionRefused && refused(e)) {
                    toldInjectionRefused = true;
                    message(ShellText.code(ShellText.INJECT_REFUSED));
                }
            }
            if (SystemClock.uptimeMillis() < until) continue;
            synchronized (pending) {
                if (pending.isEmpty()) {
                    draining = false;
                    return;
                }
            }
            if (!worker.isShutdown()) {
                // Let the worker's timers (key repeat, a swipe's end) in between while input keeps coming
                scheduleDrain();
                return;
            }
            until = SystemClock.uptimeMillis() + DRAIN_MS;  // the session ends: finish what was accepted
        }
    }

    /** In touch mode a scroll is a finger on the display, which has to be lifted after a short idle. */
    /**
     * Android turns off the kernel's key repeat and makes its own for real keyboards, which stops at our grab. The newest
     * key held repeats, as on a keyboard. The held state ends it, so releaseAll does too.
     */
    private void startRepeat(final int code) {
        int press = ++repeatPress;
        if (injector.repeats(code)) scheduleRepeat(code, press, KEY_REPEAT_DELAY_MS);
    }

    private void scheduleRepeat(final int code, final int press, long delayMs) {
        worker.schedule(() -> post(() -> {
            if (press != repeatPress || !injector.repeats(code)) return;
            injector.key(code, 2);
            scheduleRepeat(code, press, KEY_REPEAT_MS);
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    private void postScroll(final float vertical, final float horizontal) {
        post(new ScrollTask(vertical, horizontal));
    }

    private void notifyButtonUp() {
        IInjectorListener l = listener;
        try {
            if (l != null) l.onButtonUp();
        } catch (RemoteException ignored) { }
    }

    private void message(String s) {
        IInjectorListener l = listener;
        try {
            if (l != null) l.onMessage(s);
        } catch (RemoteException ignored) { }
    }

    @Override public String configure(IInjectorListener l, final int displayId, final int width, final int height, final String pkg, float speed) {
        if (injector == null) return injectorError;
        if (sessionOver) return OVER;
        link(l);
        mouseSpeed = speed;
        enabled = true;
        inSession = false;
        packageName = pkg;
        // Another app or a new session: game passthrough was the old app's
        if (gameMode) game(false, false);
        this.displayId = displayId;
        post(() -> {
            injector.setProfile(AppInputProfile.forPackage(pkg));
            injector.setDisplay(displayId, width, height);
        });
        // shutdown sets sessionOver before it takes the reader under this lock, so a reader made here is always shut down
        synchronized (this) {
            if (reader == null && !sessionOver) {
                try {
                    reader = new EvdevReader(physical);
                    Evdev.close(-1);  // does nothing, but a failed library load shows here and not in the reader thread
                    reader.start();
                } catch (LinkageError e) {
                    Log.e(TAG, "no evdev", e);
                    reader = null;
                    message(ShellText.code(ShellText.NO_PHYSICAL_INPUT, e.getMessage()));
                }
            }
        }
        return null;
    }

    @Override public void attach(IInjectorListener l, String ownPackage) {
        if (sessionOver) return;
        this.ownPackage = ownPackage;
        link(l);
    }

    /**
     * Backup for daemon(false) and for XDesk being killed without onTaskRemoved: whatever happens to the app, nothing
     * stays grabbed, routed or stranded on the external display. Grabs end with this process. Shizuku may kill it first,
     * so this is best effort. In ShellServer the process stays, and shutdown gives the grabs back. A session that is
     * already over has nothing left to do there, and must not touch the sound route of the next one.
     */
    private synchronized void link(IInjectorListener l) {
        listener = l;
        if (linked) return;
        linked = true;
        try {
            l.asBinder().linkToDeath(() -> {
                boolean wasOver = sessionOver;
                if (!wasOver) {
                    String left = audio.clear(false);
                    if (left != null) Log.w(TAG, "audio route not cleared: " + left);
                    // Move first: this way apps do not have to reconfigure on a display they are leaving
                    if (displayId >= 0 && ownPackage != null) TaskMover.moveAllToPhone(displayId, ownPackage);
                    output.stop();
                    imes.restoreAll();
                    density.clearOurs();
                    panels.clearOurs();
                    closeWebKeyboard();
                    gamepad.close();
                }
                if (onlyUid == NO_UID) System.exit(0);
                if (!wasOver) shutdown();
            }, 0);
        } catch (RemoteException e) {
            if (onlyUid == NO_UID) System.exit(0);
            shutdown();
        }
    }

    @Override public String setDisplayDensity(int display, int dpi) {
        if (sessionOver) return OVER;
        String r = dpi > 0 ? density.set(display, dpi) : density.clear(display, dpi == 0);
        // A density makes the display service project the display its own way again
        panels.reapplySoon();
        return r;
    }

    @Override public boolean watchClipboard(boolean on) {
        if (sessionOver) return false;
        try {
            if (!on) {
                clipboard().unwatch();
                return true;
            }
            clipboard().watch(() -> {
                IInjectorListener l = listener;
                if (l == null) return;
                String text;
                try {
                    text = clipboard().text();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return;
                }
                try {
                    l.onClipboard(text);
                } catch (RemoteException ignored) { }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "clipboard watch", e);
            return false;
        }
    }

    @Override public String fitPanel(int display, int width, int height, boolean ours) {
        if (sessionOver) return OVER;
        return panels.fit(display, width, height, ours);
    }


    @Override public String setAppFullscreen(String packageName, boolean on) {
        if (sessionOver) return OVER;
        String[] r = on
            ? exec(AM_TIMEOUT_MS, "/system/bin/am", "compat", "enable", "UNIVERSAL_RESIZABLE_BY_DEFAULT", packageName)
            : exec(AM_TIMEOUT_MS, "/system/bin/am", "compat", "reset", "UNIVERSAL_RESIZABLE_BY_DEFAULT", packageName);
        return r[0];
    }

    @Override public void focusExternal() {
        post(() -> {
            injector.focus(injector.displayId());
            focusStale = false;
        });
    }

    @Override public void gamepadOpen() {
        if (sessionOver) return;
        gamepad.open();
    }

    @Override public void gamepad(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) {
        if (sessionOver) return;
        gamepad.state(buttons, hat, lx, ly, rx, ry, lt, rt);
    }

    @Override public void gamepadEnd() {
        gamepad.end();
    }

    @Override public void focusDisplay(int display) {
        post(() -> injector.focus(display));
    }

    @Override public android.os.ParcelFileDescriptor captureOutput() {
        if (sessionOver) throw new IllegalStateException(OVER);
        return output.start();
    }

    @Override public void setImeOnDisplay(int display, boolean on) {
        if (sessionOver) return;
        imes.place(display, on);
    }

    @Override public void setWebKeyboard(final String layout) {
        if (sessionOver) return;
        post(() -> {
            webLayout = layout;
            updateWebKeyboard();
            if (takenOver) imes.place(injector.displayId(), webKeyboard != null || ImePlacement.hasPhysicalKeyboard());
        });
    }

    @Override public int displayDefaultDensity(int display) {
        return DensityOverride.defaultDensity(display);
    }

    @Override public String displayUniqueId(int display) {
        return DensityOverride.uniqueId(display);
    }

    @Override public String closeApp(String pkg) {
        if (sessionOver) return OVER;
        return TaskMover.closeApp(pkg);
    }

    @Override public String returnToPhone(int display) {
        return ownPackage == null ? ShellText.code(ShellText.NOT_CONNECTED) : TaskMover.moveAllToPhone(display, ownPackage);
    }

    @Override public void setDisplay(final int displayId, final int width, final int height) {
        if (sessionOver) return;
        this.displayId = displayId;
        pointer.display(displayId);
        post(() -> {
            int before = injector.displayId();
            injector.setDisplay(displayId, width, height);
            // The candidates follow a taken-over session to its new display, as after a browser reconnects
            if (takenOver && before != displayId) {
                imes.place(before, false);
                if (webKeyboard != null || ImePlacement.hasPhysicalKeyboard()) imes.place(displayId, true);
            }
        });
    }

    /**
     * Game passthrough on or off (GAMING.md). On: only from "keys on the external screen" (grabbed); the mouse is let go
     * and the external display gets the focus, so Android gives the real keyboard and mouse to the app there. Off: grabbed
     * again when {@code regrab}, else left as it is (setForwarding decides). Any thread.
     */
    private void game(boolean on, boolean regrab) {
        EvdevReader r;
        synchronized (this) {
            r = reader;
            if (r == null || sessionOver || on == gameMode || on && !r.isGrabbed()) return;
            gameMode = on;
        }
        r.setGameMode(on);
        if (on) {
            r.setGrabbed(false);
            post(() -> {
                injector.focus(injector.displayId());
                focusStale = false;
            });
        } else if (regrab) {
            r.setGrabbed(true);
        }
        Log.i(TAG, "game passthrough " + (on ? "on" : "off"));
        // After the forwarding change it caused, which is posted too
        post(() -> {
            IInjectorListener l = listener;
            try {
                if (l != null) l.onGameMode(on);
            } catch (RemoteException ignored) { }
        });
    }

    @Override public void setGameMode(boolean on) {
        game(on, true);
    }

    @Override public void setForwarding(boolean on) {
        if (sessionOver) return;
        // The phone's own switch (Ctrl+Alt+M on the phone, a button) leaves game passthrough and decides by itself
        game(false, false);
        EvdevReader r;
        synchronized (this) {
            r = reader;
        }
        if (r != null) r.setGrabbed(on);
    }

    @Override public void setMouseSpeed(float speed) {
        mouseSpeed = speed;
    }

    @Override public void phoneTouched() {
        if (gameMode) {
            // GAMING.md: touching the phone leaves game passthrough; the keys and mouse are XDesk's again, and the
            // next key brings the focus back to the external display, as after any touch on the phone
            game(false, true);
            post(() -> focusStale = true);
            return;
        }
        focusStale = true;
        // Touching the phone moves focus away. The phone's own mouse buttons press after this, so they still work
        post(injector::cancelTouch);
    }

    @Override public void move(final int dx, final int dy) {
        post(new MoveTask(false, dx, dy));
    }

    @Override public void moveToFraction(final float x, final float y) {
        post(() -> injector.moveTo(x, y));
    }

    @Override public void button(final int button, final boolean down) {
        final int bit = button == 1 ? MotionEvent.BUTTON_PRIMARY : button == 3 ? MotionEvent.BUTTON_SECONDARY : button == 2 ? MotionEvent.BUTTON_TERTIARY : 0;
        if (bit != 0) post(() -> {
            injector.button(bit, down);
            if (!down) notifyButtonUp();
        });
    }

    @Override public void scroll(final int dy, final int dx) {
        // AXIS_VSCROLL is positive for up, AXIS_HSCROLL positive for right
        postScroll(-dy * WHEEL_STEP, dx * WHEEL_STEP);
    }

    /**
     * The phone window does not take focus during an app session, but a dialog or the launcher may have moved it.
     */
    private void fromPhone(final Task t) {
        post(() -> {
            injector.focus(injector.displayId());
            Thread.sleep(FOCUS_SETTLE_MS);
            focusStale = false;
            t.run();
        });
    }

    /** On the web keyboard while there is one, which goes where the focus is like any real keyboard. Otherwise injected. */
    private void typed(final Task onKeyboard, final Task injected) {
        post(() -> {
            // Only a focus that moved away needs settling: two keys of a sentence must not cost two sleeps
            if (focusStale) {
                injector.focus(injector.displayId());
                Thread.sleep(FOCUS_SETTLE_MS);
                focusStale = false;
            }
            if (webKeyboard == null) injected.run();
            else onKeyboard.run();
        });
    }

    @Override public void key(final int scan, final boolean down) {
        // Sources send only presses and releases (INPUT.md §5.4): the repeat is made here, as for grabbed keyboards.
        // The web keyboard is a keyboard of the system's, which repeats by itself.
        typed(() -> webKeyboard.key(scan, down), () -> {
            injector.key(scan, down ? 1 : 0);
            if (down) startRepeat(scan);
        });
    }

    @Override public void touchFrame(final int action, final int[] ids, final float[] x, final float[] y) {
        post(() -> injector.touchFrame(action, ids, x, y));
    }

    @Override public void screenTouch(final int action, final int[] ids, final float[] x, final float[] y) {
        // The touch takes focus to the display, but a key may come before that has settled
        if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) focusStale = true;
        post(() -> injector.screenTouch(action, ids, x, y));
    }

    @Override public int createVirtualDisplay(String name, int width, int height, int dpi, Surface surface, float refreshRate) {
        if (sessionOver) throw new IllegalStateException(OVER);
        VirtualDisplay d = AppDisplay.create(name, width, height, dpi, surface, refreshRate);
        synchronized (appDisplays) {
            if (sessionOver) {
                d.release();
                throw new IllegalStateException(OVER);
            }
            int id = d.getDisplay().getDisplayId();
            appDisplays.put(id, d);
            Log.i(TAG, "display " + id + " made, " + width + "x" + height + " " + dpi + " dpi");
            return id;
        }
    }

    @Override public void setVirtualDisplaySurface(int id, Surface surface) {
        synchronized (appDisplays) {
            VirtualDisplay d = appDisplays.get(id);
            if (d == null) throw new IllegalArgumentException("no display " + id);
            d.setSurface(surface);
        }
    }

    private ShellClipboard clipboard;

    private synchronized ShellClipboard clipboard() throws ReflectiveOperationException {
        if (clipboard == null) clipboard = new ShellClipboard();
        return clipboard;
    }

    @Override public String clipboardText() {
        try {
            return clipboard().text();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "clipboard", e);
            return null;
        }
    }

    @Override public void setClipboardText(String text) {
        try {
            clipboard().setText(text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "clipboard", e);
        }
    }

    @Override public void resizeVirtualDisplay(int id, int width, int height, int dpi, Surface surface) {
        synchronized (appDisplays) {
            VirtualDisplay d = appDisplays.get(id);
            if (d == null) throw new IllegalArgumentException("no display " + id);
            d.resize(width, height, dpi);
            d.setSurface(surface);
        }
    }

    /** Without DESTROY_CONTENT_ON_REMOVAL the system moves what is left on them to the phone. */
    @Override public void releaseXdesxDisplay(int id) {
        synchronized (appDisplays) {
            VirtualDisplay d = appDisplays.remove(id);
            if (d != null) d.release();
        }
    }

    private void releaseDisplays() {
        synchronized (appDisplays) {
            for (VirtualDisplay d : appDisplays.values()) d.release();
            appDisplays.clear();
        }
    }

    @Override public void sendBack() {
        fromPhone(() -> {
            injector.androidKey(android.view.KeyEvent.KEYCODE_BACK);
            checkTopAfterBack(android.view.KeyEvent.KEYCODE_BACK);
        });
    }

    @Override public void tapKey(final int scan) {
        typed(() -> {
            webKeyboard.key(scan, true);
            webKeyboard.key(scan, false);
        }, () -> {
            injector.key(scan, 1);
            injector.key(scan, 0);
        });
    }

    @Override public void text(final String s) {
        typed(() -> {
            if (webKeyboard.text(s, InputInjector.physicalKeyboard()) > 0) message(ShellText.code(ShellText.TEXT_NOT_SENT));
        }, () -> {
            if (injector.text(s) > 0) message(ShellText.code(ShellText.TEXT_NOT_SENT));
        });
    }

    /** am start is what the prototype was tested with. Windowing mode 1 is full screen. */
    @Override public String startApp(String component, int displayId) {
        if (sessionOver) return OVER;
        int at = component.lastIndexOf('@');
        String user = at < 0 ? "current" : component.substring(at + 1);
        if (at >= 0) component = component.substring(0, at);
        // A launcher reset can move a stale browser task here without a first frame.
        // A native desktop window requests a distinct task on its own virtual display.
        String flags = "0x10200000";
        synchronized (appDisplays) {
            VirtualDisplay d = appDisplays.get(displayId);
            if (d != null && d.getDisplay().getName().startsWith("XDesX "))
                flags = "0x18000000";  // NEW_TASK | MULTIPLE_TASK
        }
        String[] r = exec(AM_TIMEOUT_MS, "/system/bin/am", "start", "--user", user, "--display", String.valueOf(displayId), "--windowingMode", "1",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", flags, "-n", component);
        if (r[0] != null) return r[0];
        return r[1].contains("Error") ? r[1].trim() : null;
    }

    /** How long one `dumpsys window windows` answer is shared: callers ask in bursts and each call is a process. */
    private static final long WINDOW_DUMP_MS = 2500;
    private final Object dumpLock = new Object();
    private String dumpText;
    private long dumpTried;
    private boolean dumping;

    /**
     * The text of `dumpsys window windows`, read once at a time and at most every WINDOW_DUMP_MS: a caller in the
     * middle of someone else's read shares it, one within the window gets the last one. Null when it cannot be read.
     */
    private String windowDump() {
        synchronized (dumpLock) {
            boolean waited = false;
            while (dumping) {
                waited = true;
                try {
                    dumpLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            // It waited for the read that was in flight: that answer is the one to share, however long it took
            if (waited) return dumpText;
            long now = SystemClock.elapsedRealtime();
            if (dumpTried > 0 && now - dumpTried < WINDOW_DUMP_MS) return dumpText;
            dumping = true;
            dumpTried = now;
        }
        String[] r = null;
        try {
            r = exec(DUMPSYS_TIMEOUT_MS, "/system/bin/dumpsys", "window", "windows");
        } finally {
            synchronized (dumpLock) {
                dumping = false;
                if (r != null) dumpText = r[0] == null ? r[1] : null;  // a failed read is unknown, not old
                dumpLock.notifyAll();
            }
        }
        return r[0] == null ? r[1] : null;
    }

    @Override public int imeTop(int displayId) {
        String dump = windowDump();
        return dump != null ? WindowDump.imeTop(dump, displayId) : -1;
    }

    @Override public String findPresentation(int displayId) {
        String dump = windowDump();
        return dump != null ? WindowDump.findPresentation(dump, displayId) : null;
    }

    /** Where a notification's activity landed (NOTIFY.md §5): the package in front on the display, best effort. */
    @Override public String topPackage(int displayId) {
        // ownPackage comes with configure/attach; before that there is nothing to look for XDesk among
        return ownPackage != null ? TaskMover.topPackage(displayId, ownPackage) : null;
    }

    /** Returns {error or null, output}. */
    static String[] exec(long timeoutMs, String... argv) {
        try {
            final Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            Thread watchdog = new Thread(() -> {
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly();
                } catch (InterruptedException ignored) { }
            });
            watchdog.setDaemon(true);
            watchdog.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) out.append(line).append('\n');
            }
            int code = p.waitFor();
            return new String[]{code == 0 ? null : argv[0] + " exit " + code + ": " + out, out.toString()};
        } catch (Exception e) {
            return new String[]{e.toString(), ""};
        }
    }

    @Override public void setApp(final String pkg) {
        packageName = pkg;
        inSession = false;
        post(() -> {
            // Everything held belongs to the old app and its input method
            injector.releaseAll();
            injector.hoverExit();
            injector.setTouchMode(false);  // the app side sets what the new app remembers right after this
            injector.setProfile(AppInputProfile.forPackage(pkg));
            EvdevReader r;
            synchronized (this) {
                r = reader;
            }
            if (r != null && r.isGrabbed()) {
                injector.take();
                injector.clearModifiers();
            } else {
                injector.focus(injector.displayId());
            }
            // The new app may still take a moment to get focus, so look again before the next key
            focusStale = true;
        });
    }

    @Override public void setTouchMode(final boolean on) {
        post(() -> injector.setTouchMode(on));
    }

    @Override public void setWatchSession(boolean watch) {
        if (sessionOver) return;
        watchSession = watch;
        startSessionWatcher();
    }

    @Override public void setEnabled(boolean on) {
        if (sessionOver) return;
        enabled = on;
        applyHotkey();
        // A Termux desktop takes the display: nothing the app had held, from the phone or the hotkey, may stay down
        if (!on) post(() -> injector.releaseAll());
    }

    @Override public void setHotkeyAllowed(boolean allowed) {
        if (sessionOver) return;
        hotkeyAllowed = allowed;
        applyHotkey();
    }

    private void applyHotkey() {
        EvdevReader r;
        synchronized (this) {
            r = reader;
        }
        if (r != null) r.setHotkeyEnabled(enabled && hotkeyAllowed);
    }

    @Override public String setAudioOutput(int type, String address) {
        if (sessionOver) return OVER;
        return type < 0 ? audio.clear(true) : audio.set(type, address);
    }

    /** Ask once a second whether the current app is in a session, see AppInputProfile. Only for apps that need it. */
    private synchronized void startSessionWatcher() {
        if (sessionWatcher != null) return;
        sessionWatcher = new Thread(() -> {
            while (!sessionOver) {
                boolean now = false;
                String pkg = packageName;
                if (enabled && watchSession && pkg != null && AppInputProfile.forPackage(pkg).keepScreenOnMeansRemoteCursor) {
                    String dump = windowDump();
                    // When the answer is unknown, show the cursor: too many is better than none
                    now = dump != null && WindowDump.hasKeepScreenOn(dump, pkg, displayId);
                }
                if (now != inSession) {
                    inSession = now;
                    IInjectorListener l = listener;
                    try {
                        if (l != null) l.onAppInSession(now);
                    } catch (RemoteException ignored) { }
                }
                try {
                    Thread.sleep(SESSION_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "xdesk-session-watch");
        sessionWatcher.setDaemon(true);
        sessionWatcher.start();
    }

    private MacKeyboard macKeys;

    @Override public synchronized void macKeyboard(IMacKeyboard target) {
        if (macKeys != null) {
            macKeys.finish();
            macKeys = null;
        }
        if (target != null && !sessionOver && linked) {
            macKeys = new MacKeyboard(target);
            macKeys.start();
        }
    }

    @Override public void shutdown() {
        synchronized (appDisplays) {
            sessionOver = true;
        }
        macKeyboard(null);
        releaseDisplays();
        EvdevReader r;
        synchronized (this) {
            r = reader;
            reader = null;
        }
        if (r != null) {
            r.setGrabbed(false);
            r.shutdown();
        }
        String left = audio.clear(false);
        if (left != null) Log.w(TAG, "audio route not cleared: " + left);
        output.stop();
        imes.restoreAll();
        density.clearOurs();
        panels.clearOurs();
        closeWebKeyboard();
        gamepad.close();
        pointer.release();
        coverMute.restore();
        enabled = false;
        listener = null;
        ShellClipboard c = clipboard;
        if (c != null) c.unwatch();
        stopSessionWatcher();
        if (injector == null) return;
        enqueue(injector::releaseAll);
        worker.shutdown();
        try {
            worker.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) { }
    }

    private synchronized void stopSessionWatcher() {
        if (sessionWatcher != null) sessionWatcher.interrupt();
    }

    @Override public boolean pointerLayer(android.graphics.Bitmap image, int offsetX, int offsetY, boolean visible, int x, int y) {
        if (sessionOver || displayId < 0) return false;
        return pointer.set(displayId, image, offsetX, offsetY, visible, x, y);
    }

    @Override public void muteApps(final boolean on) {
        if (sessionOver) return;
        final int d = displayId;
        final String own = ownPackage;
        // appops is a process per call: off the binder thread, in order
        new Thread(() -> {
            if (on && d >= 0 && own != null) coverMute.mute(TaskMover.packagesOn(d, own));
            else coverMute.restore();
        }, "xdesk-cover-mute").start();
    }

    @Override public void pointerAt(int x, int y) {
        pointer.moveTo(x, y);
    }

    /** From Shizuku when it removes the service. Nothing calls this in ShellServer, but it stays only the end of a session. */
    @Override public void destroy() {
        shutdown();
        if (onlyUid == NO_UID) System.exit(0);
    }
}
