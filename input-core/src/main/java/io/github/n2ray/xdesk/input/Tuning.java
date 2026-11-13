package io.github.n2ray.xdesk.input;

/**
 * Timings and speeds of the gestures. Time limits come from the system (ViewConfiguration on Android: they follow the
 * accessibility settings); distances are in dp, so a gesture feels the same on every screen.
 */
public final class Tuning {
    /** The pointer gain of XDesk's touchpad since 0.1: BASE + ACCEL × finger speed in dp per event, at most MAX. */
    public static final float BASE_GAIN = 1.3f, ACCEL_PER_DP = 0.09f, MAX_GAIN = 5f;
    /** Finger travel for one wheel step at scroll speed 1. */
    public static final float SCROLL_STEP_DP = 28;
    public static final float SLOP_DP = 8;

    /** Pixels per dp of the touched screen. */
    public float density = 1;
    /** ViewConfiguration.getLongPressTimeout(). */
    public long longPressMs = 400;
    /** ViewConfiguration.getDoubleTapTimeout(): how soon after a tap a press continues it as a drag. */
    public long doubleTapMs = 300;
    public float slopDp = SLOP_DP;
    /** The pointer speed setting, 1 by default. */
    public float pointerSpeed = 1;
    /** Finger travel per wheel step: shorter is faster scrolling. */
    public float scrollStepDp = SCROLL_STEP_DP;
    /** Content follows the fingers (§3.1). Off is the old wheel direction. */
    public boolean naturalScroll = true;

    /** 1 is the default. The finger travels the same distance, each step is shorter. */
    public void setScrollSpeed(float speed) {
        scrollStepDp = SCROLL_STEP_DP / (speed > 0 ? speed : 1);
    }

    /** Pointer gain for a finger that moved this many dp since the last event. */
    public float gain(float paceDp) {
        return pointerSpeed * Math.min(MAX_GAIN, BASE_GAIN + ACCEL_PER_DP * paceDp);
    }
}
