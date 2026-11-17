package com.termux.x11;

import android.graphics.Point;
import android.util.Log;
import android.view.Display;

/**
 * Corrects an external display whose size in Android has another shape than its panel, what `wm size W x H -d ID`
 * does. HyperOS gave a 2560x1440 monitor (AOC Q3279WG5B, whose EDID says 750x441 mm) the size 2560x1508, so
 * everything on it was laid out 1508 high and squeezed into 1440, blurred (17 November). Runs in the shell-user
 * process. The system keeps a forced size across replugging and reboots, keyed by the physical display, which is
 * right here: it is the panel's own size. XDesk takes it away again only when the display's shape comes right.
 */
final class DisplaySizeFix {
    private static final String TAG = "XDeskInjector";
    private static final long WM_TIMEOUT_MS = 5000;
    static final String SET = "set", CLEARED = "cleared";

    /**
     * @param width the panel's mode, in pixels
     * @param ours XDesk forced this display's size before (it remembers by unique id)
     * @return SET, CLEARED, null when nothing was done, or "error: ..."
     */
    static String fix(int display, int width, int height, boolean ours) {
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
        // Someone else's size (a `wm size` of the user's): theirs to keep
        if (forced && !ours) return null;
        if (sameShape(initial.x, initial.y, width, height)) {
            if (!forced) return null;
            String error = clear(wm, display);
            return error == null ? CLEARED : "error: " + error;
        }
        if (forced && base.x == width && base.y == height) return null;
        Log.i(TAG, "display " + display + " is " + initial.x + "x" + initial.y + " in Android, its panel " + width + "x" + height + ": forcing the panel's size");
        String error = null;
        try {
            wm.getClass().getMethod("setForcedDisplaySize", int.class, int.class, int.class).invoke(wm, display, width, height);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "setForcedDisplaySize failed, trying wm: " + e);
            error = runWm(width + "x" + height, "-d", String.valueOf(display));
        }
        return error == null ? SET : "error: " + error;
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
    private static String clear(Object wm, int display) {
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
