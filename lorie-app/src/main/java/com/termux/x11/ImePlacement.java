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
 */
final class ImePlacement {
    private static final String TAG = "XDeskInjector";
    /** WindowManager.DISPLAY_IME_POLICY_LOCAL. */
    private static final int LOCAL = 0;

    /** The policy each changed display had before. */
    private final HashMap<Integer, Integer> before = new HashMap<>();
    private final HashMap<Integer, Integer> applied = new HashMap<>();

    /** A keyboard with letters that is not the phone's own: the soft keyboard on the phone stays for touch typing. */
    static boolean hasPhysicalKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.isExternal() && !d.isVirtual() && !UhidKeyboard.NAME.equals(d.getName())
                    && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
        }
        return false;
    }

    private static int get(Object wm, int display) throws ReflectiveOperationException {
        return (Integer) wm.getClass().getMethod("getDisplayImePolicy", int.class).invoke(wm, display);
    }

    private static void set(Object wm, int display, int policy) throws ReflectiveOperationException {
        wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, display, policy);
    }

    /** Keep the focused editor as Android's IME client. Only move where its IME is drawn. */
    synchronized boolean target(int display, boolean phone) {
        return apply(display, phone ? 1 : LOCAL);
    }

    synchronized boolean windowKeyboard(int display, boolean hardware) {
        return apply(display, hardware ? LOCAL : 2); // DISPLAY_IME_POLICY_HIDE until the phone keyboard is requested
    }

    private boolean apply(int display, int desired) {
        if (display <= android.view.Display.DEFAULT_DISPLAY) return false;
        try {
            Object wm = DensityOverride.windowManager();
            int current = get(wm, display);
            if (current != desired) {
                set(wm, display, desired);
                before.putIfAbsent(display, current);
                applied.put(display, desired);
            }
            return get(wm, display) == desired;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "input method policy not changed on display " + display, e);
            return false;
        }
    }

    synchronized void place(int display, boolean local) {
        if (local) {
            target(display, false);
            return;
        }
        Integer old = before.get(display);
        if (old == null) return;
        try {
            Object wm = DensityOverride.windowManager();
            // Leave a newer system or user change alone.
            if (get(wm, display) == applied.get(display).intValue()) set(wm, display, old);
            before.remove(display);
            applied.remove(display);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "cannot restore input method policy on display " + display, e);
        }
    }

    /** The session ended. Displays that went away are skipped by the system. */
    synchronized void restoreAll() {
        for (Map.Entry<Integer, Integer> e : new HashMap<>(before).entrySet()) place(e.getKey(), false);
        before.clear();
        applied.clear();
    }
}
