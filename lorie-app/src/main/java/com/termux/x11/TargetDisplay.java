package com.termux.x11;

import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Which display XDesk uses, decided only here: a real external display wins, otherwise the car's screen. Every other
 * display is ignored, among them Android Auto's own virtual displays (maps, toolbars, the dashboard), screen recording
 * and casting.
 *
 * The car's display is the one CarActivity is on, as it reports itself: Android Auto makes a virtual display just for it
 * and its id changes on every connection.
 */
final class TargetDisplay {
    /** Display.TYPE_VIRTUAL, hidden. */
    private static final int TYPE_VIRTUAL = 5;

    private TargetDisplay() { }

    /** A real external display if there is one, otherwise the car's, otherwise null. */
    static Display find(DisplayManager dm) {
        Display real = real(dm);
        return real != null ? real : car(dm);
    }

    /** The first real external display, or null. */
    static Display real(DisplayManager dm) {
        for (Display d : dm.getDisplays()) if (isReal(d)) return d;
        return null;
    }

    /** The display CarActivity is on, while it is there and the display exists. */
    static Display car(DisplayManager dm) {
        int id = CarActivity.displayId();
        return id == Display.INVALID_DISPLAY ? null : dm.getDisplay(id);
    }

    static boolean isCar(Display d) {
        return d != null && d.getDisplayId() == CarActivity.displayId();
    }

    /** Not the phone, not the car and not virtual: HDMI or DisplayPort, also wireless and simulated displays. */
    static boolean isReal(Display d) {
        if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY || isCar(d)) return false;
        try {
            return (Integer) Display.class.getMethod("getType").invoke(d) != TYPE_VIRTUAL;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Type unknown: count it, as XDesk did before it knew about the car
            return true;
        }
    }
}
