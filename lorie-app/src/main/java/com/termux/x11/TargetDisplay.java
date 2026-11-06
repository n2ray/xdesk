package com.termux.x11;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Which display XDesk uses, decided only here: the wireless display while XDesk streams one (WirelessDisplay), then a
 * real external display, otherwise the car's screen. Every other
 * display is ignored, among them Android Auto's own virtual displays (maps, toolbars, the dashboard), screen recording
 * and casting.
 *
 * The car's display is the one CarActivity is on, as it reports itself: Android Auto makes a virtual display just for it
 * and its id changes on every connection.
 */
final class TargetDisplay {
    /** Display.TYPE_EXTERNAL, TYPE_WIFI and TYPE_OVERLAY, hidden. */
    private static final int TYPE_EXTERNAL = 2, TYPE_WIFI = 3, TYPE_OVERLAY = 4;

    private TargetDisplay() { }

    /** A real external display if there is one, otherwise the car's, otherwise null. */
    static Display find(DisplayManager dm) {
        Display real = real(dm);
        return real != null ? real : car(dm);
    }

    /** The first real external display, or null. */
    static Display real(DisplayManager dm) {
        int wireless = WirelessDisplay.displayId();
        if (wireless != Display.INVALID_DISPLAY) {
            Display d = dm.getDisplay(wireless);
            if (d != null) return d;
        }
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

    /**
     * Where the desktop window may be: the phone, where the system moves it when its display goes, the display XDesk uses,
     * the car's, and the one XDesk made for apps on the car. See MainActivity.displayAllowed.
     */
    static boolean allowsDesktop(Context c, int id) {
        if (id == Display.DEFAULT_DISPLAY || id == CarActivity.displayId() || id == AppSession.get(c).appDisplayId()) return true;
        Display target = find(c.getSystemService(DisplayManager.class));
        return target != null && target.getDisplayId() == id;
    }

    /**
     * HDMI or DisplayPort, also wireless and simulated displays. Not the phone, not the car, not virtual, and not a second
     * built-in screen: a cover screen or the other half of a foldable is TYPE_INTERNAL.
     */
    static boolean isReal(Display d) {
        if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY || isCar(d)) return false;
        // XDesk's wireless display is a virtual one, but XDesk uses it as an external display in every way
        if (d.getDisplayId() == WirelessDisplay.displayId()) return true;
        try {
            int type = (Integer) Display.class.getMethod("getType").invoke(d);
            return type == TYPE_EXTERNAL || type == TYPE_WIFI || type == TYPE_OVERLAY;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Type unknown: the system marks external displays as meant for presentations, built-in ones not
            return (d.getFlags() & Display.FLAG_PRESENTATION) != 0;
        }
    }
}
