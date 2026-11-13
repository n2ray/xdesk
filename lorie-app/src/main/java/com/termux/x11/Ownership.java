package com.termux.x11;

import android.view.KeyEvent;

/**
 * Whose are the phone's physical keyboard and mouse (INPUT.md §7): the phone's own, or the external display's. One
 * switch for both kinds of session, one Ctrl+Alt+M, one state for the indicator.
 *
 * <p>The mechanics differ for good reasons: with an Android app the injector grabs the mice through evdev (and hears
 * Ctrl+Alt+M there, the keyboard being with the app); with the X desktop, its Linux windows or a Mac, the phone's own
 * window passes the keyboard (through the phone's input method, §5.1) and the captured mouse on, or, with that
 * switched off in the settings, the 0.5.1 takeover moves focus and pointer capture to the desktop window. Each is a
 * Side.
 */
final class Ownership {
    interface Side {
        /** This kind of session is the one on the display now. */
        boolean active();
        boolean forwarded();
        void forward(boolean on);
    }

    private final Side app, desktop;
    /** The M of the hotkey went down here: its release is swallowed too, wherever the focus is by then. */
    private boolean swallowM;

    Ownership(Side app, Side desktop) {
        this.app = app;
        this.desktop = desktop;
    }

    private Side side() {
        if (app.active()) return app;
        if (desktop.active()) return desktop;
        return null;
    }

    /** Keyboard and mouse go to the external display now. */
    boolean forwarded() {
        Side s = side();
        return s != null && s.forwarded();
    }

    void set(boolean on) {
        Side s = side();
        if (s != null && s.forwarded() != on) s.forward(on);
    }

    void toggle() {
        Side s = side();
        if (s != null) s.forward(!s.forwarded());
    }

    /**
     * A key in one of XDesk's windows (the phone's, or the desktop window on the external display). True when it was
     * the hotkey, or its M coming up, and must not go on. In app sessions the injector's evdev reader has the hotkey.
     */
    boolean onKey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_M) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && swallowM) {
            swallowM = false;
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || !e.isCtrlPressed() || !e.isAltPressed()) return false;
        if (app.active() || !desktop.active()) return false;
        swallowM = true;
        if (e.getRepeatCount() == 0) toggle();
        return true;
    }
}
