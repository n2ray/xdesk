package com.termux.x11;

import android.util.Log;
import android.view.Display;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Stops apps raising the on-screen keyboard on the external display: the user calls it from XDesk instead. Sets the
 * display's IME policy to hide, what `wm set-display-ime-policy` is for, and puts the old value back when the session ends.
 * Runs in the shell-user process. It is a hidden call, so failure comes back as text and only costs this convenience.
 *
 * Text from the phone's keyboard still gets there: InputInjector sends it as key events to the focused window, which is
 * not an input method connection. Whether every app takes those keys with the policy set is only known from testing.
 */
final class ImePolicyOverride {
    private static final String TAG = "XDeskInjector";
    private static final int DISPLAY_IME_POLICY_HIDE = 2;

    private final HashMap<Integer, Integer> previous = new HashMap<>();

    /** Returns null, or what went wrong. */
    synchronized String hide(int display) {
        if (display == Display.DEFAULT_DISPLAY) return "不能改手机屏";
        try {
            Object wm = DensityOverride.windowManager();
            if (!previous.containsKey(display)) {
                previous.put(display, (Integer) wm.getClass().getMethod("getDisplayImePolicy", int.class).invoke(wm, display));
            }
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, display, DISPLAY_IME_POLICY_HIDE);
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "IME policy refused on display " + display, e);
            previous.remove(display);
            return String.valueOf(e);
        }
    }

    /** Puts back what was there. Does nothing for a display this process did not change. */
    synchronized String restore(int display) {
        Integer old = previous.remove(display);
        if (old == null) return null;
        try {
            Object wm = DensityOverride.windowManager();
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, display, old);
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "IME policy not restored on display " + display, e);
            return String.valueOf(e);
        }
    }

    /** Everything this process changed, when the session ends. */
    synchronized void restoreAll() {
        for (int display : new ArrayList<>(previous.keySet())) restore(display);
    }
}
