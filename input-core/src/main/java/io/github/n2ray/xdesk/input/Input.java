package io.github.n2ray.xdesk.input;

/**
 * The unified events of INPUT.md §2 that the gesture engines produce: buttons, scroll phases, semantic actions and
 * feedback. Values that also travel elsewhere keep the numbers they already have there (X buttons, the receiver's
 * Android buttons 1 Back and 2 Home).
 */
public final class Input {
    private Input() { }

    // Buttons, X numbered as everywhere in XDesk
    public static final int LEFT = 1, MIDDLE = 2, RIGHT = 3, BACK_BUTTON = 8, FORWARD_BUTTON = 9;

    // Scroll phases (§2 scroll). Deltas only come with CHANGE and MOMENTUM.
    public static final int BEGIN = 1, CHANGE = 2, END = 3, MOMENTUM_BEGIN = 4, MOMENTUM = 5, MOMENTUM_END = 6;
    /** A mouse wheel or anything else without a gesture around it. */
    public static final int WHEEL = 0;

    // Semantic actions (§2 action, §3.4). arg is 0 unless said.
    public static final int BACK = 1, HOME = 2, OVERVIEW = 3, SWITCHER = 4,
    /** Held-modifier app switching: arg +1 next (a swipe to the right), -1 previous, 0 the fingers lifted: done. */
        SWITCH_APP = 5,
    /** arg +1 or -1: the next or previous screen. */
        SWITCH_SCREEN = 6,
    /** arg +1 bigger, -1 smaller, one step. */
        ZOOM = 7,
        FORWARD = 8, CLOSE_WINDOW = 9, FULLSCREEN = 10;

    // Feedback for the hand: what a phone vibrates for (§13)
    public static final int FEEDBACK_CLICK = 1, FEEDBACK_LONG_PRESS = 2, FEEDBACK_GESTURE = 3;

    public static String actionName(int action) {
        switch (action) {
            case BACK: return "back";
            case HOME: return "home";
            case OVERVIEW: return "overview";
            case SWITCHER: return "switcher";
            case SWITCH_APP: return "switchApp";
            case SWITCH_SCREEN: return "switchScreen";
            case ZOOM: return "zoom";
            case FORWARD: return "forward";
            case CLOSE_WINDOW: return "closeWindow";
            case FULLSCREEN: return "fullscreen";
            default: return "action" + action;
        }
    }

    public static String phaseName(int phase) {
        switch (phase) {
            case WHEEL: return "wheel";
            case BEGIN: return "begin";
            case CHANGE: return "change";
            case END: return "end";
            case MOMENTUM_BEGIN: return "momentumBegin";
            case MOMENTUM: return "momentum";
            case MOMENTUM_END: return "momentumEnd";
            default: return "phase" + phase;
        }
    }
}
