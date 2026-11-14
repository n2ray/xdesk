package io.github.n2ray.xdesk.input;

/**
 * A physical mouse's buttons from the events Android delivers for it, as X numbered presses and releases with no
 * doubles (INPUT.md §2 pointer.button). Devices differ in how they say a button changed: AOSP sends ACTION_DOWN (with
 * the new buttonState) then ACTION_BUTTON_PRESS, and ACTION_BUTTON_RELEASE then ACTION_UP; some builds give only the
 * DOWN and UP with buttonState, or a BUTTON_PRESS without its action button. This keeps what it has sent and sends
 * only changes, so every way ends up the same.
 *
 * <p>Back and forward are left alone: Android turns them into KEYCODE_BACK and KEYCODE_FORWARD keys from the mouse
 * as well, and that one way is used for them (XDeskActivity), so they are never doubled either.
 *
 * <p>Moves never let go of a button: a device that reports a held button only on some moves must not end a drag.
 */
public final class MouseButtons {
    // android.view.MotionEvent values (public API constants, kept here so this stays plain Java)
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3, ACTION_HOVER_MOVE = 7,
        ACTION_BUTTON_PRESS = 11, ACTION_BUTTON_RELEASE = 12;
    public static final int PRIMARY = 1, SECONDARY = 2, TERTIARY = 4, BACK = 8, FORWARD = 16;
    private static final int TRACKED = PRIMARY | SECONDARY | TERTIARY;

    public interface Out {
        /** X numbered: 1 left, 2 middle, 3 right. */
        void button(int button, boolean down);
    }

    /** Android button bits sent as down. */
    private int held;

    /** One event of the mouse. */
    public void event(int action, int actionButton, int buttonState, Out out) {
        switch (action) {
            case ACTION_BUTTON_PRESS:
                if ((actionButton & TRACKED) != 0) set(actionButton & TRACKED, true, out);
                else if (actionButton == 0) sync(buttonState, true, out);
                break;
            case ACTION_BUTTON_RELEASE:
                if ((actionButton & TRACKED) != 0) set(actionButton & TRACKED, false, out);
                else if (actionButton == 0) sync(buttonState, false, out);
                break;
            case ACTION_DOWN:
                sync(buttonState, true, out);
                sync(buttonState, false, out);
                break;
            case ACTION_UP:
                // The last button is up: nothing is held after this, whatever buttonState says
                sync(0, false, out);
                break;
            case ACTION_MOVE:
            case ACTION_HOVER_MOVE:
                sync(buttonState, true, out);
                break;
            case ACTION_CANCEL:
                releaseAll(out);
                break;
            default:
                break;
        }
    }

    /** Everything sent as down goes up: capture was lost, the device went away. */
    public void releaseAll(Out out) {
        sync(0, false, out);
    }

    /** Forgets what is held without sending anything (the target was let go of by other means). */
    public void clear() {
        held = 0;
    }

    public boolean holding() {
        return held != 0;
    }

    /** Presses (down) or releases (!down) what state says differently from what was sent. */
    private void sync(int state, boolean down, Out out) {
        int want = state & TRACKED;
        int change = down ? want & ~held : held & ~want;
        for (int bit = PRIMARY; bit <= TERTIARY; bit <<= 1) if ((change & bit) != 0) set(bit, down, out);
    }

    private void set(int bits, boolean down, Out out) {
        for (int bit = PRIMARY; bit <= TERTIARY; bit <<= 1) {
            if ((bits & bit) == 0 || ((held & bit) != 0) == down) continue;
            if (down) held |= bit;
            else held &= ~bit;
            out.button(xButton(bit), down);
        }
    }

    /** X numbering of an Android button bit; 0 for one this does not handle. */
    public static int xButton(int bit) {
        switch (bit) {
            case PRIMARY: return Input.LEFT;
            case SECONDARY: return Input.RIGHT;
            case TERTIARY: return Input.MIDDLE;
            case BACK: return Input.BACK_BUTTON;
            case FORWARD: return Input.FORWARD_BUTTON;
            default: return 0;
        }
    }
}
