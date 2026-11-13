package io.github.n2ray.xdesk.input;

/**
 * One touch event, copied out of a MotionEvent into arrays that are reused for every event: no allocation on the
 * touch path. Action and tool numbers are MotionEvent's own, so the copy is a plain assignment.
 */
public final class Touches {
    public static final int MAX = 10;
    public static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;
    public static final int TOOL_FINGER = 1, TOOL_STYLUS = 2, TOOL_MOUSE = 3, TOOL_ERASER = 4;

    /** DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP or CANCEL. */
    public int action;
    /** The pointer that went down or up, for POINTER_DOWN and POINTER_UP; 0 otherwise. */
    public int index;
    /** Pointers in this event, including one going up. */
    public int count;
    public int tool = TOOL_FINGER;
    /** Milliseconds, on the clock of Scheduler.now(). */
    public long time;
    public final int[] id = new int[MAX];
    /** Pixels in the touched view. */
    public final float[] x = new float[MAX], y = new float[MAX];

    /** For tests and adapters: starts an event, pointers follow with pointer(). */
    public Touches set(int action, int index, long time) {
        this.action = action;
        this.index = index;
        this.time = time;
        count = 0;
        return this;
    }

    public Touches pointer(int id, float x, float y) {
        if (count >= MAX) return this;
        this.id[count] = id;
        this.x[count] = x;
        this.y[count] = y;
        count++;
        return this;
    }

    float centroidX() {
        float s = 0;
        for (int i = 0; i < count; i++) s += x[i];
        return count > 0 ? s / count : 0;
    }

    float centroidY() {
        float s = 0;
        for (int i = 0; i < count; i++) s += y[i];
        return count > 0 ? s / count : 0;
    }

    /** Centroid of the pointers that stay: without the one in index for POINTER_UP and UP. */
    float stayingX() {
        if (action != POINTER_UP || count < 2) return centroidX();
        float s = 0;
        for (int i = 0; i < count; i++) if (i != index) s += x[i];
        return s / (count - 1);
    }

    float stayingY() {
        if (action != POINTER_UP || count < 2) return centroidY();
        float s = 0;
        for (int i = 0; i < count; i++) if (i != index) s += y[i];
        return s / (count - 1);
    }
}
