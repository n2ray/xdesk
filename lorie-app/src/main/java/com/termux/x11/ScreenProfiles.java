package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Display;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * What XDesk remembers per screen, DISPLAY.md §11, keyed by the screen's identity (§5.2): a cable-connected screen by
 * its uniqueId, a receiver by its own id, a browser by its id, the car as one (its display id changes every time).
 * Phase 1 keeps the kind (the distance factor of b), the size u and the clarity tier. Main thread.
 */
final class ScreenProfiles {
    private static final String TAG = "XDeskScreens";
    static final String CAR = "car";
    /** §4.2; only remembered for now. */
    static final int CLARITY_NATIVE = 0, CLARITY_BALANCED = 1, CLARITY_SAVER = 2;

    static final class Profile {
        final String key;
        int kind = Sizing.KIND_UNSET;
        int size = Sizing.SIZE_DEFAULT;
        int clarity = CLARITY_BALANCED;

        Profile(String key) {
            this.key = key;
        }
    }

    private static ScreenProfiles instance;
    private final SharedPreferences prefs;
    /** Bumped on every change, so that whoever applies sizes can tell. */
    private int generation;

    static synchronized ScreenProfiles get(Context c) {
        if (instance == null) instance = new ScreenProfiles(c.getApplicationContext());
        return instance;
    }

    private ScreenProfiles(Context c) {
        prefs = c.getSharedPreferences("screens", Context.MODE_PRIVATE);
    }

    static String wiredKey(Display d) {
        String id = null;
        try {
            id = (String) HiddenApiBypass.invoke(Display.class, d, "getUniqueId");
        } catch (Throwable e) {
            Log.i(TAG, "no unique id: " + e);
        }
        // The name and size are less exact, but better than not remembering
        if (id == null || id.isEmpty()) {
            Display.Mode m = d.getMode();
            id = "name:" + d.getName() + ":" + m.getPhysicalWidth() + "x" + m.getPhysicalHeight();
        }
        return "w:" + id;
    }

    static String receiverKey(String id) {
        return "r:" + id;
    }

    static String webKey(String id) {
        return "web:" + id;
    }

    /** The key of display d as XDesk uses it: the car, the wireless display (its receiver) or a wired screen. */
    static String keyOf(Context c, Display d) {
        if (TargetDisplay.isCar(d)) return CAR;
        if (d.getDisplayId() == WirelessDisplay.displayId()) {
            String k = WirelessDisplay.get(c).screenKey();
            if (k != null) return k;
        }
        return wiredKey(d);
    }

    Profile of(Context c, Display d) {
        return of(keyOf(c, d));
    }

    Profile of(String key) {
        Profile p = new Profile(key);
        String v = prefs.getString(key, null);
        if (v == null) return p;
        String[] f = v.split(",");
        try {
            if (f.length > 0) p.kind = Integer.parseInt(f[0]);
            if (f.length > 1) p.size = Integer.parseInt(f[1]);
            if (f.length > 2) p.clarity = Integer.parseInt(f[2]);
        } catch (NumberFormatException ignored) { }
        if (p.size < 0 || p.size >= Sizing.SIZES.length) p.size = Sizing.SIZE_DEFAULT;
        return p;
    }

    /** Whether this screen has a profile at all (first connection asks for its kind). */
    boolean known(String key) {
        return prefs.contains(key);
    }

    void save(Profile p) {
        prefs.edit().putString(p.key, p.kind + "," + p.size + "," + p.clarity).apply();
        generation++;
    }

    int generation() {
        return generation;
    }
}
