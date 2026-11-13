package io.github.n2ray.xdesk.input;

/**
 * What the gesture engines produce. Everything is optional: each engine only uses part of it, and a user overrides
 * what its target understands. An abstract class rather than default methods, for the receiver's Java 8.
 */
public abstract class GestureOutput {
    /** The cursor moves by pixels, after the pointer gain. */
    public void move(int dx, int dy) { }

    /** Where on the touched view, in its pixels: direct touch, and before buttons of a direct touch. */
    public void position(float x, float y) { }

    /** X numbered, see Input.LEFT. */
    public void button(int button, boolean down) { }

    public void click(int button) {
        button(button, true);
        button(button, false);
    }

    /**
     * Fine scrolling, in wheel steps that may be fractions: dy > 0 is the wheel turning down, dx > 0 right. Sign and
     * speed settings are applied already. Phase is one of Input's scroll phases.
     */
    public void scroll(int phase, float dx, float dy) { }

    /** Input.BACK and the other semantic actions. */
    public void action(int action, int arg) { }

    /** Input.FEEDBACK_*: a short vibration. */
    public void feedback(int kind) { }

    /**
     * Fingers for an app that takes touches, placed around the cursor (phone touch mode). action is a MotionEvent
     * action with the pointer index; ids, offsets in pixels from the cursor: only the first count entries count.
     */
    public void frame(int action, int[] ids, float[] offsetX, float[] offsetY, int count) { }
}
