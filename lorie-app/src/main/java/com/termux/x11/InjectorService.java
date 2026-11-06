package com.termux.x11;

import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private static final int BTN_LEFT = 272, BTN_RIGHT = 273, BTN_MIDDLE = 274, BTN_SIDE = 275, BTN_EXTRA = 276;

    private interface Task {
        void run() throws Exception;
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final InputInjector injector;
    private final String injectorError;
    private EvdevReader reader;
    private volatile IInjectorListener listener;
    private volatile float mouseSpeed = 1f;
    /** The phone was touched since the external display last had focus. */
    private volatile boolean focusStale;
    private volatile boolean hotkeyAllowed = true;
    /** Counts key presses, so a timer from an earlier press of the same key stops. */
    private int repeatPress;
    private final AudioRouter audio = new AudioRouter();
    private final DensityOverride density = new DensityOverride();
    private final ImePlacement imes = new ImePlacement();
    private volatile boolean enabled = true, watchSession, inSession;
    private volatile String packageName;
    private volatile int displayId = -1;
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

    private final EvdevReader.Sink physical = new EvdevReader.Sink() {
        @Override public void onForwarding(final boolean on, final int devices) {
            post(() -> {
                if (on) {
                    injector.take();
                    focusStale = false;
                    // Candidates for the physical keyboard next to the text, not on the phone
                    if (ImePlacement.hasPhysicalKeyboard()) imes.place(injector.displayId(), true);
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
            post(() -> {
                float gain = MOUSE_GAIN * injector.width() / MOUSE_REFERENCE_WIDTH * mouseSpeed;
                injector.moveBy(dx * gain, dy * gain);
            });
        }
        @Override public void onWheel(final int vertical, final int horizontal) {
            postScroll(vertical * WHEEL_STEP, horizontal * WHEEL_STEP);
        }
        @Override public void onButton(final int code, final boolean down) {
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
            System.exit(1);
        });
        InputInjector i = null;
        String error = null;
        try {
            i = new InputInjector(new InputInjector.CursorListener() {
                @Override public void onCursor(int x, int y) {
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
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "no input injection", e);
            error = e.toString();
        }
        injector = i;
        injectorError = error;
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (onlyUid != NO_UID && Binder.getCallingUid() != onlyUid) throw new SecurityException("not XDesk");
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

    private void enqueue(final Task t) {
        if (worker.isShutdown() || injector == null) return;
        worker.execute(() -> {
            try {
                t.run();
            } catch (Exception e) {
                Log.w(TAG, "input failed", e);
                if (!toldInjectionRefused && refused(e)) {
                    toldInjectionRefused = true;
                    message(ShellText.code(ShellText.INJECT_REFUSED));
                }
            }
        });
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
        post(() -> {
            injector.scroll(vertical, horizontal);
            if (injector.isSwiping()) worker.schedule(() -> post(injector::endSwipeIfIdle), InputInjector.SWIPE_IDLE_MS + 20, TimeUnit.MILLISECONDS);
        });
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
                    imes.restoreAll();
                    density.clearOurs();
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
        return dpi > 0 ? density.set(display, dpi) : density.clear(display, dpi == 0);
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

    @Override public void focusDisplay(int display) {
        post(() -> injector.focus(display));
    }

    @Override public void setImeOnDisplay(int display, boolean on) {
        if (sessionOver) return;
        imes.place(display, on);
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
        post(() -> injector.setDisplay(displayId, width, height));
    }

    @Override public void setForwarding(boolean on) {
        if (sessionOver) return;
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
        focusStale = true;
        // Touching the phone moves focus away. The phone's own mouse buttons press after this, so they still work
        post(injector::cancelTouch);
    }

    @Override public void move(final int dx, final int dy) {
        post(() -> {
            float gain = injector.width() / PAD_REFERENCE_WIDTH;
            injector.moveBy(dx * gain, dy * gain);
        });
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

    @Override public void key(final int scan, final boolean down) {
        fromPhone(() -> injector.key(scan, down ? 1 : 0));
    }

    @Override public void touchFrame(final int action, final int[] ids, final float[] x, final float[] y) {
        post(() -> injector.touchFrame(action, ids, x, y));
    }

    @Override public void screenTouch(final int action, final int[] ids, final float[] x, final float[] y) {
        // The touch takes focus to the display, but a key may come before that has settled
        if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) focusStale = true;
        post(() -> injector.screenTouch(action, ids, x, y));
    }

    @Override public int createVirtualDisplay(String name, int width, int height, int dpi, Surface surface) {
        if (sessionOver) throw new IllegalStateException(OVER);
        VirtualDisplay d = AppDisplay.create(name, width, height, dpi, surface);
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

    /** Without DESTROY_CONTENT_ON_REMOVAL the system moves what is left on them to the phone. */
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
        fromPhone(() -> {
            injector.key(scan, 1);
            injector.key(scan, 0);
        });
    }

    @Override public void text(final String s) {
        fromPhone(() -> {
            if (injector.text(s) > 0) message(ShellText.code(ShellText.TEXT_NOT_SENT));
        });
    }

    /** am start is what the prototype was tested with. Windowing mode 1 is full screen. */
    @Override public String startApp(String component, int displayId) {
        if (sessionOver) return OVER;
        String[] r = exec(AM_TIMEOUT_MS, "/system/bin/am", "start", "--display", String.valueOf(displayId), "--windowingMode", "1",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", component);
        if (r[0] != null) return r[0];
        return r[1].contains("Error") ? r[1].trim() : null;
    }

    @Override public String findPresentation(int displayId) {
        String[] r = exec(DUMPSYS_TIMEOUT_MS, "/system/bin/dumpsys", "window", "windows");
        if (r[0] != null) return null;
        return WindowDump.findPresentation(r[1], displayId);
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
                    String[] r = exec(DUMPSYS_TIMEOUT_MS, "/system/bin/dumpsys", "window", "windows");
                    // When the answer is unknown, show the cursor: too many is better than none
                    now = r[0] == null && WindowDump.hasKeepScreenOn(r[1], pkg, displayId);
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

    @Override public void shutdown() {
        synchronized (appDisplays) {
            sessionOver = true;
        }
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
        imes.restoreAll();
        density.clearOurs();
        enabled = false;
        listener = null;
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

    /** From Shizuku when it removes the service. Nothing calls this in ShellServer, but it stays only the end of a session. */
    @Override public void destroy() {
        shutdown();
        if (onlyUid == NO_UID) System.exit(0);
    }
}
