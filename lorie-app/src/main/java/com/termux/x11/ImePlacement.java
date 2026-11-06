package com.termux.x11;

import android.util.Log;
import android.view.InputDevice;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the input method shows its windows for a display, `IWindowManager.setDisplayImePolicy`. Runs in the shell-user
 * process. LOCAL puts the candidates of a physical keyboard next to the text on the external display; the default
 * puts them, and the soft keyboard, on the phone. The system keeps the policy across reboots, so what is changed here
 * is put back when the session ends.
 *
 * Never HIDE: it disconnects the input method from the whole display, and a saved HIDE would come back.
 */
final class ImePlacement {
    private static final String TAG = "XDeskInjector";
    /** WindowManager.DISPLAY_IME_POLICY_LOCAL and _HIDE. */
    private static final int LOCAL = 0, HIDE = 2;

    /** The policy each changed display had before. */
    private final HashMap<Integer, Integer> before = new HashMap<>();

    /** A keyboard with letters that is not the phone's own: the soft keyboard on the phone stays for touch typing. */
    static boolean hasPhysicalKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.isExternal() && !d.isVirtual() && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
        }
        return false;
    }

    private static int get(Object wm, int display) throws ReflectiveOperationException {
        return (Integer) wm.getClass().getMethod("getDisplayImePolicy", int.class).invoke(wm, display);
    }

    private static void set(Object wm, int display, int policy) throws ReflectiveOperationException {
        wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, display, policy);
    }

    /** @param local on the display itself, or where it was before */
    synchronized void place(int display, boolean local) {
        if (display == android.view.Display.DEFAULT_DISPLAY) return;
        try {
            Object wm = DensityOverride.windowManager();
            if (local) {
                int now = get(wm, display);
                if (!before.containsKey(display)) before.put(display, now == HIDE ? 1 : now);
                if (now != LOCAL) set(wm, display, LOCAL);
            } else {
                Integer old = before.remove(display);
                if (old != null && get(wm, display) != old) set(wm, display, old);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "input method policy not changed on display " + display, e);
        }
    }

    /** The session ended. Displays that went away are skipped by the system. */
    synchronized void restoreAll() {
        for (Map.Entry<Integer, Integer> e : new HashMap<>(before).entrySet()) place(e.getKey(), false);
        before.clear();
    }
}
