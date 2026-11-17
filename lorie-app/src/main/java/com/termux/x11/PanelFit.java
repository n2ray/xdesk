package com.termux.x11;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A wired external display shown on its whole panel, pixel for pixel, when Android would squeeze it (DISPLAY.md §4.2).
 * Runs in the shell-user process (the injector).
 *
 * Android 15's pixel anisotropy correction takes the EDID's millimetres for the truth: a 2560x1440 monitor that says
 * 750x441 mm has "pixels" 89 dpi wide and 85 high, so Android gives it a 2560x1508 size and SurfaceFlinger squeezes
 * that into 1440 rows: blurred. Forcing 2560x1440 alone only letterboxes it (the display service keeps the "corrected"
 * shape when it projects), and the correction itself takes root to turn off. What the shell may do is set the
 * display's projection in SurfaceFlinger itself (ACCESS_SURFACE_FLINGER, as scrcpy does for its displays): the
 * panel's size, forced, projected onto the whole panel. Seen on the AOC Q3279WG5B on 17 November: full and sharp.
 *
 * The display service sets its own projection again whenever it reconfigures the display (a density, a mode, a
 * size), so ours is set again then: every CHECK_MS the display's info (one light binder call) is compared with what
 * it was, and a change sets it again at once and REAPPLY_AGAIN_MS later; after XDesk changes a density too
 * (reapplySoon); and every REAPPLY_MS whatever happened, for a reconfiguration that changes nothing in the info.
 * Measured on 17 November with a projection every second: 0.7% of a core and a composition a second; the reflection
 * is looked up once. The display token comes from services.jar's DisplayControl (Android 14+): that and
 * libandroid_servers stay loaded (about 30 MB more, mostly clean mapped pages), as a library can be opened by one
 * class loader only and a second display needs the lookup again. When the session ends the display gets its own size
 * back, and with it the display service's projection.
 */
final class PanelFit {
    private static final String TAG = "XDeskInjector";
    private static final long WM_TIMEOUT_MS = 5000, CHECK_MS = 1000, REAPPLY_AGAIN_MS = 300, REAPPLY_MS = 10_000;
    static final String SET = "set", CLEARED = "cleared";

    /** Display id: {panel width, panel height}, while fitted. */
    private final Map<Integer, int[]> fitted = new HashMap<>();
    /** Display id: what its info was when last projected (infoKey). */
    private final Map<Integer, String> seen = new HashMap<>();
    private ScheduledExecutorService timer;
    private long lastReapply;
    private static Method getToken;
    // Looked up once
    private static java.lang.reflect.Constructor<?> newTransaction;
    private static Method setProjection, applyTransaction, getDisplayInfo;
    private static Object displayManagerGlobal;

    /**
     * @param width the panel's mode, in pixels
     * @param ours XDesk forced this display's size before (it remembers by unique id)
     * @return SET, CLEARED, null when nothing was done, or "error: ..."
     */
    synchronized String fit(int display, int width, int height, boolean ours) {
        if (display == Display.DEFAULT_DISPLAY || width <= 0 || height <= 0) return null;
        Point initial = new Point(), base = new Point();
        Object wm;
        try {
            wm = DensityOverride.windowManager();
            wm.getClass().getMethod("getInitialDisplaySize", int.class, Point.class).invoke(wm, display, initial);
            wm.getClass().getMethod("getBaseDisplaySize", int.class, Point.class).invoke(wm, display, base);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "display sizes not readable: " + e);
            return null;
        }
        if (initial.x <= 0 || initial.y <= 0) return null;
        boolean forced = !base.equals(initial);
        if (sameShape(initial.x, initial.y, width, height)) {
            // Its own shape is right: anything of ours goes
            fitted.remove(display);
            seen.remove(display);
            if (!ours) return null;
            if (forced) {
                String error = clearSize(wm, display);
                if (error != null) return "error: " + error;
            }
            return CLEARED;
        }
        // Someone else's size (a `wm size` of the user's): theirs to keep
        if (forced && !ours && !(base.x == width && base.y == height)) return null;
        if (!(base.x == width && base.y == height)) {
            Log.i(TAG, "display " + display + " is " + initial.x + "x" + initial.y + " in Android, its panel " + width + "x" + height
                + ": its size and projection forced to the panel's");
            String error = null;
            try {
                wm.getClass().getMethod("setForcedDisplaySize", int.class, int.class, int.class).invoke(wm, display, width, height);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.i(TAG, "setForcedDisplaySize failed, trying wm: " + e);
                error = runWm(width + "x" + height, "-d", String.valueOf(display));
            }
            if (error != null) return "error: " + error;
        }
        fitted.put(display, new int[] {width, height});
        String error = project(display, width, height);
        seen.put(display, infoKey(display));
        if (error != null) {
            Log.w(TAG, "display " + display + " projection: " + error);
            // Without the projection the forced size letterboxes: better the system's squeezed picture
            fitted.remove(display);
            clearSize(wm, display);
            return "error: " + error;
        }
        startTimer();
        return SET;
    }

    /** After XDesk forced a density: the display service has just projected the display its way again. */
    void reapplySoon() {
        ScheduledExecutorService t;
        synchronized (this) {
            t = timer;
        }
        if (t == null) return;
        t.schedule(this::reapply, 50, TimeUnit.MILLISECONDS);
        t.schedule(this::reapply, 400, TimeUnit.MILLISECONDS);
    }

    /** Every display fitted gets its own size back, when the session ends. */
    synchronized void clearOurs() {
        if (timer != null) timer.shutdownNow();
        timer = null;
        seen.clear();
        if (fitted.isEmpty()) return;
        try {
            Object wm = DensityOverride.windowManager();
            for (int display : fitted.keySet()) {
                String error = clearSize(wm, display);
                if (error != null) Log.w(TAG, "display " + display + " size not given back: " + error);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "display sizes not given back: " + e);
        }
        fitted.clear();
    }

    private synchronized void reapply() {
        lastReapply = android.os.SystemClock.uptimeMillis();
        for (Map.Entry<Integer, int[]> e : fitted.entrySet()) {
            String error = project(e.getKey(), e.getValue()[0], e.getValue()[1]);
            if (error != null) Log.w(TAG, "display " + e.getKey() + " projection: " + error);
            seen.put(e.getKey(), infoKey(e.getKey()));
        }
    }

    /** Every CHECK_MS: a display whose info changed was reconfigured, and projected the display service's way. */
    private synchronized void check() {
        boolean changed = false;
        for (int display : fitted.keySet()) {
            String now = infoKey(display);
            if (now != null && !now.equals(seen.get(display))) changed = true;
        }
        if (changed) {
            reapply();
            ScheduledExecutorService t = timer;
            if (t != null) t.schedule(this::reapply, REAPPLY_AGAIN_MS, TimeUnit.MILLISECONDS);
        } else if (android.os.SystemClock.uptimeMillis() - lastReapply >= REAPPLY_MS) {
            reapply();
        }
    }

    /** What of a display's info a reconfiguration changes: its size, density, mode, rotation and state. */
    private static String infoKey(int display) {
        try {
            if (getDisplayInfo == null) {
                Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
                displayManagerGlobal = global.getMethod("getInstance").invoke(null);
                getDisplayInfo = global.getMethod("getDisplayInfo", int.class);
            }
            Object info = getDisplayInfo.invoke(displayManagerGlobal, display);
            if (info == null) return null;
            Class<?> c = info.getClass();
            return c.getField("logicalWidth").get(info) + "x" + c.getField("logicalHeight").get(info) + "@"
                + c.getField("logicalDensityDpi").get(info) + " m" + c.getField("modeId").get(info) + " r"
                + c.getField("rotation").get(info) + " s" + c.getField("state").get(info);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private void startTimer() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(this::check, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    /** The panel's size projected onto the whole panel. Null, or what went wrong. */
    private String project(int display, int width, int height) {
        try {
            IBinder token = token(display);
            if (token == null) return "no display token";
            if (newTransaction == null) {
                Class<?> tc = Class.forName("android.view.SurfaceControl$Transaction");
                setProjection = tc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
                applyTransaction = tc.getMethod("apply");
                newTransaction = tc.getConstructor();
            }
            Object t = newTransaction.newInstance();
            Rect r = new Rect(0, 0, width, height);
            setProjection.invoke(t, token, 0, r, new Rect(r));
            applyTransaction.invoke(t);
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return String.valueOf(e);
        }
    }

    /** SurfaceFlinger's token for the physical display behind a display id ("local:<id>" is its unique id). */
    private IBinder token(int display) throws ReflectiveOperationException {
        String unique = DensityOverride.uniqueId(display);
        if (unique == null || !unique.startsWith("local:")) return null;
        long physical;
        try {
            physical = Long.parseLong(unique.substring("local:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
        // Asked each time (a fit, or a reapply every 10 s): SurfaceFlinger makes a new token at each hotplug, and an
        // old one held here stays "alive", so a projection sent to it after a replug did nothing (audit, 17 November)
        return (IBinder) tokenMethod().invoke(null, physical);
    }

    /**
     * SurfaceControl.getPhysicalDisplayToken until Android 13, DisplayControl's (in services.jar) since 14. Lint's
     * private API rule is an app's: this runs as the shell user (app_process), where it is allowed.
     */
    @android.annotation.SuppressLint({"BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi"})
    private static Method tokenMethod() throws ReflectiveOperationException {
        if (getToken != null) return getToken;
        try {
            getToken = Class.forName("android.view.SurfaceControl").getMethod("getPhysicalDisplayToken", long.class);
            return getToken;
        } catch (NoSuchMethodException e) {
            // Android 14+
        }
        ClassLoader cl = (ClassLoader) Class.forName("com.android.internal.os.ClassLoaderFactory")
            .getMethod("createClassLoader", String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class)
            .invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), dc, "android_servers");
        getToken = dc.getMethod("getPhysicalDisplayToken", long.class);
        return getToken;
    }

    /** Within 1%: a 1920x1080 size on a 3840x2160 panel is scaled by Android on purpose, not wrong. */
    static boolean sameShape(int w1, int h1, int w2, int h2) {
        float a = (float) Math.max(w1, h1) / Math.min(w1, h1), b = (float) Math.max(w2, h2) / Math.min(w2, h2);
        return Math.abs(a - b) <= 0.01f * b;
    }

    /**
     * Back to the display's own size. HyperOS gives an external display the phone's size when it is cleared (`wm size
     * reset -d 4` left a 2560x1440 monitor at 1440x3200, 17 November), as it gives it the phone's density
     * (DensityOverride.keepOwnDensity): then its own size is forced, which is what clearing means.
     */
    private static String clearSize(Object wm, int display) {
        String error = null;
        try {
            wm.getClass().getMethod("clearForcedDisplaySize", int.class).invoke(wm, display);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "clearForcedDisplaySize failed, trying wm: " + e);
            error = runWm("reset", "-d", String.valueOf(display));
        }
        if (error != null) return error;
        Point initial = new Point(), base = new Point();
        try {
            wm.getClass().getMethod("getInitialDisplaySize", int.class, Point.class).invoke(wm, display, initial);
            wm.getClass().getMethod("getBaseDisplaySize", int.class, Point.class).invoke(wm, display, base);
            if (initial.x <= 0 || base.equals(initial)) return null;
            Log.i(TAG, "display " + display + " fell back to " + base.x + "x" + base.y + " after clearing, setting its own " + initial.x + "x" + initial.y);
            wm.getClass().getMethod("setForcedDisplaySize", int.class, int.class, int.class).invoke(wm, display, initial.x, initial.y);
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return runWm(initial.x + "x" + initial.y, "-d", String.valueOf(display));
        }
    }

    private static String runWm(String... args) {
        String[] argv = new String[args.length + 2];
        argv[0] = "/system/bin/wm";
        argv[1] = "size";
        System.arraycopy(args, 0, argv, 2, args.length);
        String[] r = InjectorService.exec(WM_TIMEOUT_MS, argv);
        if (r[0] != null) return r[0];
        return r[1].contains("Error") || r[1].contains("Exception") ? r[1].trim() : null;
    }
}
