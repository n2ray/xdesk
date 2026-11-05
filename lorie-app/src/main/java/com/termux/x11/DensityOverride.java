package com.termux.x11;

import android.util.Log;
import android.view.Display;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Forces the interface size of the external display, what `wm density N -d ID` does. Runs in the shell-user process.
 * Hidden API first, `wm` when that fails, and failure comes back as text. The system keeps a forced density across
 * reboots, keyed by the physical display, so everything set here is cleared again when the session ends.
 */
final class DensityOverride {
    private static final String TAG = "XDeskInjector";
    /** UserHandle.USER_CURRENT, what `wm density` uses. */
    private static final int USER_CURRENT = -2;
    private static final long WM_TIMEOUT_MS = 5000;

    private final HashSet<Integer> ours = new HashSet<>();

    private static Object windowManager() throws ReflectiveOperationException {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        return Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    private static String runWm(String... args) {
        String[] argv = new String[args.length + 2];
        argv[0] = "/system/bin/wm";
        argv[1] = "density";
        System.arraycopy(args, 0, argv, 2, args.length);
        String[] r = InjectorService.exec(WM_TIMEOUT_MS, argv);
        if (r[0] != null) return r[0];
        return r[1].contains("Error") || r[1].contains("Exception") ? r[1].trim() : null;
    }

    /** Returns null, or what went wrong. Never touches the phone's own screen. */
    synchronized String set(int display, int dpi) {
        if (display == Display.DEFAULT_DISPLAY) return "不能改手机屏";
        String error = null;
        try {
            Object wm = windowManager();
            wm.getClass().getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class).invoke(wm, display, dpi, USER_CURRENT);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "setForcedDisplayDensityForUser failed, trying wm: " + e);
            error = runWm(String.valueOf(dpi), "-d", String.valueOf(display));
        }
        if (error == null) ours.add(display);
        return error;
    }

    /** Returns null, or what went wrong. @param force also when this process did not set it */
    synchronized String clear(int display, boolean force) {
        if (display == Display.DEFAULT_DISPLAY) return null;
        if (!ours.remove(display) && !force) return null;
        try {
            Object wm = windowManager();
            wm.getClass().getMethod("clearForcedDisplayDensityForUser", int.class, int.class).invoke(wm, display, USER_CURRENT);
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "clearForcedDisplayDensityForUser failed, trying wm: " + e);
            return runWm("reset", "-d", String.valueOf(display));
        }
    }

    /** Everything this process set, when the session ends. */
    synchronized void clearOurs() {
        for (int display : new ArrayList<>(ours)) {
            String error = clear(display, false);
            if (error != null) Log.w(TAG, "density not cleared on display " + display + ": " + error);
        }
    }

    /** The display's own density, or 0 when unknown. */
    static int defaultDensity(int display) {
        try {
            Object wm = windowManager();
            return (Integer) wm.getClass().getMethod("getInitialDisplayDensity", int.class).invoke(wm, display);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "getInitialDisplayDensity failed, trying wm: " + e);
        }
        String[] r = InjectorService.exec(WM_TIMEOUT_MS, "/system/bin/wm", "density", "-d", String.valueOf(display));
        return r[0] == null ? parsePhysical(r[1]) : 0;
    }

    /** Reads "Physical density: 213" from `wm density`. */
    static int parsePhysical(String wmOutput) {
        for (String line : wmOutput.split("\n")) {
            if (!line.startsWith("Physical density:")) continue;
            try {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Stays the same for one physical display across replugging, unlike the display id. Null when unknown. */
    static String uniqueId(int display) {
        try {
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object g = global.getMethod("getInstance").invoke(null);
            Object info = global.getMethod("getDisplayInfo", int.class).invoke(g, display);
            return info == null ? null : (String) info.getClass().getField("uniqueId").get(info);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "no unique id for the display: " + e);
            return null;
        }
    }
}
