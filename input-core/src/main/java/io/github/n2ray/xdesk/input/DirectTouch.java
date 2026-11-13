package io.github.n2ray.xdesk.input;

/**
 * Direct touch (INPUT.md §3.2): a surface that shows the picture, where a finger means the place it lands. Positions
 * go out in the touched view's pixels with GestureOutput.position, before any button or scroll.
 *
 * <p>Two kinds of target:
 * <ul>
 * <li>Fingers ({@link #setFingers} true): the target takes the left button as a finger (an Android app in touch
 * mode). A drag drags, a hold holds a finger down so the app makes its own long press.</li>
 * <li>Mouse only (XFCE, a Mac, an Android app in mouse mode): a tap clicks where it lands; one finger dragging
 * scrolls, with momentum; a long press then lifting is a right click, a long press then moving drags with the left
 * button (selects text, moves windows, draws).</li>
 * </ul>
 * In both, two fingers scroll and a two-finger tap is a right click, and a pen draws at once: its tip is the left
 * button, no long press first.
 */
public final class DirectTouch {
    private static final int IDLE = 0, ONE = 1, DRAG = 2, LONG_ARMED = 3, SCROLL = 4, TWO = 5, PEN = 6, DONE = 7;
    private static final long MOMENTUM_FRAME_MS = 16;

    private final Tuning tuning;
    private final Scheduler scheduler;
    private final GestureOutput out;
    private final ScrollMomentum momentum = new ScrollMomentum();
    private final Velocity velocity = new Velocity();
    private boolean fingers;

    private int state = IDLE;
    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean newMid, scrolled;
    private boolean coasting;
    private long coastAt;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (state != ONE) return;
            out.feedback(Input.FEEDBACK_LONG_PRESS);
            if (fingers) {
                // The app's own long press: a finger held where it landed
                state = DRAG;
                out.position(downX, downY);
                out.button(Input.LEFT, true);
            } else {
                state = LONG_ARMED;
            }
        }
    };

    private final Runnable coast = new Runnable() {
        @Override public void run() {
            if (!coasting) return;
            long now = scheduler.now();
            double dt = (now - coastAt) / 1000d;
            coastAt = now;
            if (!momentum.advance(dt, tuning.density)) {
                stopMomentum();
                return;
            }
            scrollPx((float) momentum.stepX(dt), (float) momentum.stepY(dt), Input.MOMENTUM);
            scheduler.post(this, MOMENTUM_FRAME_MS);
        }
    };

    public DirectTouch(Tuning tuning, Scheduler scheduler, GestureOutput out) {
        this.tuning = tuning;
        this.scheduler = scheduler;
        this.out = out;
    }

    /** True when the target takes the left button as a finger. Ends what is going on. */
    public void setFingers(boolean on) {
        if (on == fingers) return;
        cancel();
        fingers = on;
    }

    public boolean fingers() {
        return fingers;
    }

    private float slopPx() {
        return tuning.slopDp * tuning.density;
    }

    public boolean onTouch(Touches t) {
        switch (t.action) {
            case Touches.DOWN:
                stopMomentum();
                downX = lastX = t.x[0];
                downY = lastY = t.y[0];
                downAt = t.time;
                scrolled = false;
                if (t.tool == Touches.TOOL_STYLUS || t.tool == Touches.TOOL_ERASER) {
                    // A pen draws at once
                    state = PEN;
                    out.position(downX, downY);
                    out.button(Input.LEFT, true);
                    break;
                }
                state = ONE;
                scheduler.post(longPress, tuning.longPressMs);
                break;
            case Touches.POINTER_DOWN:
                if (state == PEN) break;
                scheduler.cancel(longPress);
                stopMomentum();
                if (state == DRAG) out.button(Input.LEFT, false);
                if (state == SCROLL) out.scroll(Input.END, 0, 0);
                state = TWO;
                newMid = true;
                break;
            case Touches.MOVE:
                onMove(t);
                break;
            case Touches.POINTER_UP:
                if ((state == SCROLL || (state == TWO && scrolled)) && t.count - 1 < 2) {
                    endScroll(true);
                    state = DONE;
                }
                // The middle of the fingers that stay is somewhere else
                newMid = true;
                break;
            case Touches.UP:
                scheduler.cancel(longPress);
                lifted(t);
                state = IDLE;
                break;
            case Touches.CANCEL:
                cancel();
                break;
            default:
                break;
        }
        return state != IDLE;
    }

    private void onMove(Touches t) {
        float x = t.x[0], y = t.y[0];
        switch (state) {
            case PEN:
            case DRAG:
                out.position(x, y);
                break;
            case ONE:
                if (Math.hypot(x - downX, y - downY) <= slopPx()) break;
                scheduler.cancel(longPress);
                if (fingers) {
                    state = DRAG;
                    out.position(downX, downY);
                    out.button(Input.LEFT, true);
                    out.position(x, y);
                } else {
                    // Mouse only: one finger scrolls what is under it
                    state = SCROLL;
                    scrolled = true;
                    out.position(downX, downY);
                    out.scroll(Input.BEGIN, 0, 0);
                    velocity.clear();
                    velocity.add(t.time, x, y);
                    scrollPx(x - downX, y - downY, Input.CHANGE);
                    lastX = x;
                    lastY = y;
                }
                break;
            case LONG_ARMED:
                if (Math.hypot(x - downX, y - downY) <= slopPx()) break;
                state = DRAG;
                out.position(downX, downY);
                out.button(Input.LEFT, true);
                out.position(x, y);
                break;
            case SCROLL:
                if (t.count != 1) break;
                velocity.add(t.time, x, y);
                scrollPx(x - lastX, y - lastY, Input.CHANGE);
                lastX = x;
                lastY = y;
                break;
            case TWO:
                if (t.count >= 2) twoFingers(t);
                break;
            default:
                break;
        }
    }

    private void twoFingers(Touches t) {
        float x = (t.x[0] + t.x[1]) / 2, y = (t.y[0] + t.y[1]) / 2;
        if (newMid) {
            newMid = false;
        } else {
            if (!scrolled) {
                if (Math.hypot(x - lastX, y - lastY) == 0) return;
                scrolled = true;
                out.position(downX, downY);
                out.scroll(Input.BEGIN, 0, 0);
                velocity.clear();
            }
            velocity.add(t.time, x, y);
            scrollPx(x - lastX, y - lastY, Input.CHANGE);
        }
        lastX = x;
        lastY = y;
    }

    /** Finger travel in pixels to wheel steps, content following the finger when natural. */
    private void scrollPx(float dx, float dy, int phase) {
        float sign = tuning.naturalScroll ? -1 : 1, step = tuning.scrollStepDp * tuning.density;
        out.scroll(phase, sign * dx / step, sign * dy / step);
    }

    private void lifted(Touches t) {
        switch (state) {
            case PEN:
            case DRAG:
                out.position(t.x[0], t.y[0]);
                out.button(Input.LEFT, false);
                break;
            case ONE:
                out.position(downX, downY);
                out.click(Input.LEFT);
                break;
            case LONG_ARMED:
                out.position(downX, downY);
                out.click(Input.RIGHT);
                break;
            case SCROLL:
                endScroll(true);
                break;
            case TWO:
                if (scrolled) out.scroll(Input.END, 0, 0);
                else if (t.time - downAt < tuning.longPressMs) {
                    out.position(downX, downY);
                    out.click(Input.RIGHT);
                }
                break;
            default:
                break;
        }
    }

    private void endScroll(boolean withMomentum) {
        out.scroll(Input.END, 0, 0);
        if (!withMomentum) return;
        velocity.compute();
        if (!momentum.start(velocity.x(), velocity.y(), tuning.density)) return;
        coasting = true;
        coastAt = scheduler.now();
        out.scroll(Input.MOMENTUM_BEGIN, 0, 0);
        scrollPx((float) momentum.firstX(), (float) momentum.firstY(), Input.MOMENTUM);
        scheduler.post(coast, MOMENTUM_FRAME_MS);
    }

    private void stopMomentum() {
        if (!coasting) return;
        coasting = false;
        momentum.clear();
        scheduler.cancel(coast);
        out.scroll(Input.MOMENTUM_END, 0, 0);
    }

    /** Whatever is held goes up, as when the session ends or the window loses focus. */
    public void cancel() {
        scheduler.cancel(longPress);
        if (state == DRAG || state == PEN) out.button(Input.LEFT, false);
        else if (state == SCROLL || (state == TWO && scrolled)) out.scroll(Input.END, 0, 0);
        stopMomentum();
        state = IDLE;
    }
}
