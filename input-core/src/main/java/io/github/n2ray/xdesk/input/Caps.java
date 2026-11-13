package io.github.n2ray.xdesk.input;

/**
 * What a target can do (INPUT.md §6). The differences between targets come from here, the user does not choose them.
 * Immutable; the presets are the targets XDesk knows today, a new kind of target builds its own.
 */
public final class Caps {
    /** Who does what is above a window (§1.1): the target system itself, or XDesk. */
    public static final int DESKTOP = 0, APP = 1, WINDOW = 2;
    public static final int BUTTON_LEFT = 1, BUTTON_RIGHT = 2, BUTTON_MIDDLE = 4, BUTTON_BACK = 8, BUTTON_FORWARD = 16,
        ALL_BUTTONS = 31;

    public final int scope;
    /** Takes several fingers (an Android app), not just a mouse. */
    public final boolean touch;
    public final boolean pen;
    /** BUTTON_* bits. */
    public final int buttons;
    /** Has an input field the phone's own input method can connect to (only Android). */
    public final boolean textField;
    /** Takes fine scrolling with phases (a Mac); otherwise whole or fractional wheel steps. */
    public final boolean scrollPhases;
    /** Uses the ⌘ key family: Back is ⌘[, zoom ⌘+ and ⌘−, app switching holds ⌘. */
    public final boolean macKeys;
    public final boolean clipboardImages;
    /** Can be resized to its cell (DISPLAY.md §1.3). */
    public final boolean resize;

    public Caps(int scope, boolean touch, boolean pen, int buttons, boolean textField, boolean scrollPhases, boolean macKeys,
                boolean clipboardImages, boolean resize) {
        this.scope = scope;
        this.touch = touch;
        this.pen = pen;
        this.buttons = buttons;
        this.textField = textField;
        this.scrollPhases = scrollPhases;
        this.macKeys = macKeys;
        this.clipboardImages = clipboardImages;
        this.resize = resize;
    }

    public static final Caps ANDROID_APP = new Caps(APP, true, true, ALL_BUTTONS, true, false, false, false, true);
    public static final Caps X11_DESKTOP = new Caps(DESKTOP, false, false, ALL_BUTTONS, false, false, false, false, true);
    public static final Caps MAC_WINDOW = new Caps(WINDOW, false, false, BUTTON_LEFT | BUTTON_RIGHT, false, true, true, false, true);
    public static final Caps MAC_DESKTOP = new Caps(DESKTOP, false, false, BUTTON_LEFT | BUTTON_RIGHT, false, true, true, false, false);
    /** A single Linux window shown alone (X11 or Wayland through XDesk's Linux source). */
    public static final Caps LINUX_WINDOW = new Caps(WINDOW, false, false, ALL_BUTTONS, false, false, false, false, true);

    public boolean has(int button) {
        return (buttons & button) != 0;
    }

    /** The BUTTON_* bit of an X button number, 0 for an unknown one. */
    public static int bitOf(int xButton) {
        switch (xButton) {
            case Input.LEFT: return BUTTON_LEFT;
            case Input.RIGHT: return BUTTON_RIGHT;
            case Input.MIDDLE: return BUTTON_MIDDLE;
            case Input.BACK_BUTTON: return BUTTON_BACK;
            case Input.FORWARD_BUTTON: return BUTTON_FORWARD;
            default: return 0;
        }
    }
}
