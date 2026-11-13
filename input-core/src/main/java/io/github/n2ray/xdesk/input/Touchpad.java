package io.github.n2ray.xdesk.input;

/**
 * The touchpad (INPUT.md §3.1): a touch surface the user does not look at. Runs inline on the thread that delivers
 * the touches and allocates nothing per event.
 *
 * <ul>
 * <li>One finger moves the cursor, faster when it moves faster.</li>
 * <li>A tap clicks: one finger left, two right, three middle, counted by the most fingers down at once, decided when
 * the last one lifts.</li>
 * <li>A long press does not press anything yet: moving then presses the left button and drags, lifting without moving
 * is a right click. A press right after a tap does the same with the left button: moving drags, lifting is the second
 * click of a double click.</li>
 * <li>Two fingers scroll finely, with phases and momentum, or pinch to zoom.</li>
 * <li>Three and four fingers landing together are gestures: three up overview, down home, sideways app switching held
 * as long as the fingers stay; four up the switcher, sideways the screen.</li>
 * </ul>
 */
public final class Touchpad {
    private static final int IDLE = 0, ONE = 1, CURSOR = 2, LONG_ARMED = 3, TAP_ARMED = 4, DRAG = 5, TWO = 6, SCROLL = 7,
        PINCH = 8, MULTI = 9, SWITCHING = 10, DONE = 11;
    /** Sideways travel of three fingers that starts app switching, and each further step. */
    private static final float SWITCH_START_DP = 40, SWITCH_STEP_DP = 80;
    /** Span change that makes two fingers a pinch, and the ratio of one zoom step. */
    private static final float PINCH_DP = 12, ZOOM_STEP = 1.25f;
    private static final long MOMENTUM_FRAME_MS = 16;

    private final Tuning tuning;
    private final Scheduler scheduler;
    private final GestureOutput out;
    private final MultiFinger multi = new MultiFinger();
    private final ScrollMomentum momentum = new ScrollMomentum();
    private final Velocity velocity = new Velocity();
    private final PointerRest rest = new PointerRest();
    private final float[] travel2 = new float[2];

    private int state = IDLE;
    private int maxFingers, fingers;
    private long downAt, lastTapUp = Long.MIN_VALUE / 2;
    private float lastX, lastY, travelDp, armedTravelDp, pendingX, pendingY;
    private boolean rebase, gestured, scrolled;
    // Two fingers
    private float span0, spanStep, midStartX, midStartY;
    // App switching
    private float switchFromX;
    // Momentum
    private boolean coasting;
    private long coastAt;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (state != ONE || fingers != 1 || travelDp >= tuning.slopDp) return;
            state = LONG_ARMED;
            armedTravelDp = 0;
            pendingX = pendingY = 0;
            out.feedback(Input.FEEDBACK_LONG_PRESS);
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
            coastScroll(Input.MOMENTUM, momentum.stepX(dt), momentum.stepY(dt));
            scheduler.post(this, MOMENTUM_FRAME_MS);
        }
    };

    public Touchpad(Tuning tuning, Scheduler scheduler, GestureOutput out) {
        this.tuning = tuning;
        this.scheduler = scheduler;
        this.out = out;
    }

    /** One touch event. Returns true while a stroke is on the pad. */
    public boolean onTouch(Touches t) {
        float d = tuning.density;
        switch (t.action) {
            case Touches.DOWN:
                stopMomentum();
                state = ONE;
                fingers = maxFingers = 1;
                downAt = t.time;
                travelDp = 0;
                gestured = scrolled = false;
                rest.clear();
                setLast(t);
                multi.down(t.id[0], t.x[0] / d, t.y[0] / d, t.time, false);
                if (t.time - lastTapUp < tuning.doubleTapMs) {
                    state = TAP_ARMED;
                    pendingX = pendingY = 0;
                    armedTravelDp = 0;
                } else {
                    scheduler.post(longPress, tuning.longPressMs);
                }
                break;
            case Touches.POINTER_DOWN:
                pointerDown(t);
                break;
            case Touches.MOVE:
                onMove(t);
                break;
            case Touches.POINTER_UP:
                feedMulti(t);
                decided(multi.up(t.id[t.index], t.x[t.index] / d, t.y[t.index] / d, t.time));
                fingers = t.count - 1;
                if (state == SWITCHING) endSwitch();
                if (state == SCROLL && fingers < 2) endScroll(true);
                if (state == PINCH && fingers < 2) state = DONE;
                rebase = true;
                break;
            case Touches.UP:
                scheduler.cancel(longPress);
                feedMulti(t);
                decided(multi.up(t.id[0], t.x[0] / d, t.y[0] / d, t.time));
                lifted(t);
                break;
            case Touches.CANCEL:
                cancel();
                break;
            default:
                break;
        }
        return state != IDLE;
    }

    private void pointerDown(Touches t) {
        float d = tuning.density;
        scheduler.cancel(longPress);
        stopMomentum();
        fingers = t.count;
        if (fingers > maxFingers) maxFingers = fingers;
        boolean silenced = multi.down(t.id[t.index], t.x[t.index] / d, t.y[t.index] / d, t.time, state == DRAG);
        // A second finger ends a drag, as it always has
        if (state == DRAG) out.button(Input.LEFT, false);
        int before = state;
        if (multi.isArmed() && state != MULTI && state != SWITCHING) {
            state = MULTI;
        } else if (silenced || fingers > 2) {
            if (state != MULTI && state != SWITCHING) state = DONE;
        } else if (fingers == 2 && (state == ONE || state == CURSOR || state == LONG_ARMED || state == TAP_ARMED || state == DRAG)) {
            state = TWO;
            span0 = spanStep = span(t);
            midStartX = t.centroidX();
            midStartY = t.centroidY();
            velocity.clear();
        }
        if (before == SCROLL && state != SCROLL) emitScroll(Input.END, 0, 0);
        rebase = true;
    }

    private void feedMulti(Touches t) {
        float d = tuning.density;
        for (int i = 0; i < t.count; i++) multi.move(t.id[i], t.x[i] / d, t.y[i] / d, t.time);
    }

    private void setLast(Touches t) {
        lastX = t.centroidX();
        lastY = t.centroidY();
        rebase = false;
    }

    private void onMove(Touches t) {
        feedMulti(t);
        if (rebase) {
            setLast(t);
            return;
        }
        float x = t.centroidX(), y = t.centroidY(), d = tuning.density;
        float dx = (x - lastX) / d, dy = (y - lastY) / d;
        lastX = x;
        lastY = y;
        float step = Math.abs(dx) + Math.abs(dy);
        travelDp += step;
        if (travelDp >= tuning.slopDp) scheduler.cancel(longPress);
        switch (state) {
            case ONE:
                if (travelDp >= tuning.slopDp) state = CURSOR;
                moveCursor(dx, dy);
                break;
            case CURSOR:
            case DRAG:
                moveCursor(dx, dy);
                break;
            case LONG_ARMED:
            case TAP_ARMED:
                // Nothing moves until it is a drag, so that a double click or a right click lands where the cursor was
                armedTravelDp += step;
                pendingX += dx;
                pendingY += dy;
                if (armedTravelDp >= tuning.slopDp) {
                    state = DRAG;
                    out.button(Input.LEFT, true);
                    out.feedback(Input.FEEDBACK_LONG_PRESS);
                    moveCursor(pendingX, pendingY);
                }
                break;
            case TWO:
                decideTwo(t);
                break;
            case SCROLL:
                velocity.add(t.time, x, y);
                scrollFinger(dx, dy, Input.CHANGE);
                break;
            case PINCH:
                pinch(t);
                break;
            case MULTI:
                maybeSwitch();
                break;
            case SWITCHING:
                switchSteps();
                break;
            default:
                break;
        }
    }

    private void moveCursor(float dxDp, float dyDp) {
        float gain = tuning.gain((float) Math.hypot(dxDp, dyDp)), d = tuning.density;
        // Moves are in pixels of the touched screen, as the touchpad has always sent them
        if (rest.add(dxDp * d * gain, dyDp * d * gain)) out.move(rest.dx, rest.dy);
    }

    private static float span(Touches t) {
        if (t.count < 2) return 0;
        return (float) Math.hypot(t.x[0] - t.x[1], t.y[0] - t.y[1]);
    }

    private void decideTwo(Touches t) {
        if (t.count != 2) return;
        float d = tuning.density;
        float spanChange = Math.abs(span(t) - span0) / d;
        float mid = (float) Math.hypot(t.centroidX() - midStartX, t.centroidY() - midStartY) / d;
        if ((spanChange >= PINCH_DP && spanChange > mid) || (mid >= tuning.slopDp && spanChange > mid)) {
            // Zoom steps count from where the fingers landed
            state = PINCH;
            spanStep = span0;
            pinch(t);
        } else if (mid >= tuning.slopDp) {
            state = SCROLL;
            scrolled = true;
            emitScroll(Input.BEGIN, 0, 0);
            velocity.clear();
            velocity.add(t.time, t.centroidX(), t.centroidY());
            // The travel up to here belongs to the scroll
            scrollFinger((t.centroidX() - midStartX) / d, (t.centroidY() - midStartY) / d, Input.CHANGE);
        }
    }

    private void pinch(Touches t) {
        if (t.count != 2) return;
        float s = span(t);
        if (spanStep <= 0) {
            spanStep = s;
            return;
        }
        if (s >= spanStep * ZOOM_STEP) {
            spanStep = s;
            gestured = true;
            out.action(Input.ZOOM, 1);
        } else if (s <= spanStep / ZOOM_STEP) {
            spanStep = s;
            gestured = true;
            out.action(Input.ZOOM, -1);
        }
    }

    /** Finger travel in dp to wheel steps. Natural: fingers up push the content up, which is the wheel turning down. */
    private void scrollFinger(float dxDp, float dyDp, int phase) {
        float sign = tuning.naturalScroll ? -1 : 1;
        emitScroll(phase, sign * dxDp / tuning.scrollStepDp, sign * dyDp / tuning.scrollStepDp);
    }

    private void emitScroll(int phase, float dx, float dy) {
        out.scroll(phase, dx, dy);
    }

    private void endScroll(boolean withMomentum) {
        emitScroll(Input.END, 0, 0);
        state = DONE;
        if (!withMomentum) return;
        velocity.compute();
        float d = tuning.density;
        if (!momentum.start(velocity.x(), velocity.y(), d)) return;
        coasting = true;
        coastAt = scheduler.now();
        emitScroll(Input.MOMENTUM_BEGIN, 0, 0);
        coastScroll(Input.MOMENTUM, momentum.firstX(), momentum.firstY());
        scheduler.post(coast, MOMENTUM_FRAME_MS);
    }

    /** Momentum moves in pixels of finger travel, in the sign of the finger. */
    private void coastScroll(int phase, double pxX, double pxY) {
        float d = tuning.density;
        scrollFinger((float) pxX / d, (float) pxY / d, phase);
    }

    private void stopMomentum() {
        if (!coasting) return;
        coasting = false;
        momentum.clear();
        scheduler.cancel(coast);
        emitScroll(Input.MOMENTUM_END, 0, 0);
    }

    /** Is the one in the coast a scroll that a new touch has to stop: true while it still runs. */
    public boolean coasting() {
        return coasting;
    }

    private void maybeSwitch() {
        if (multi.downCount() != 3 || multi.isDone()) return;
        multi.armedTravel(travel2);
        float dx = travel2[0], dy = travel2[1];
        if (Math.abs(dx) < SWITCH_START_DP || Math.abs(dx) < 1.3f * Math.abs(dy)) return;
        multi.finish();
        state = SWITCHING;
        gestured = true;
        switchFromX = dx;
        out.feedback(Input.FEEDBACK_GESTURE);
        out.action(Input.SWITCH_APP, dx > 0 ? 1 : -1);
    }

    private void switchSteps() {
        multi.armedTravel(travel2);
        float dx = travel2[0] - switchFromX;
        if (Math.abs(dx) < SWITCH_STEP_DP) return;
        switchFromX = travel2[0];
        out.action(Input.SWITCH_APP, dx > 0 ? 1 : -1);
    }

    private void endSwitch() {
        out.action(Input.SWITCH_APP, 0);
        state = DONE;
    }

    /** A three- or four-finger decision from MultiFinger. */
    private void decided(int decision) {
        if (decision == 0 || state != MULTI) return;
        int g = MultiFinger.gesture(decision), n = MultiFinger.fingers(decision);
        int action = 0, arg = 0;
        if (n == 3) {
            if (g == MultiFinger.SWIPE_UP) action = Input.OVERVIEW;
            else if (g == MultiFinger.SWIPE_DOWN) action = Input.HOME;
            else if (g == MultiFinger.SWIPE_LEFT || g == MultiFinger.SWIPE_RIGHT) {
                // A flick too short for held switching: one step
                gestured = true;
                out.feedback(Input.FEEDBACK_GESTURE);
                out.action(Input.SWITCH_APP, g == MultiFinger.SWIPE_RIGHT ? 1 : -1);
                out.action(Input.SWITCH_APP, 0);
                state = DONE;
                return;
            }
        } else if (n == 4) {
            if (g == MultiFinger.SWIPE_UP) action = Input.SWITCHER;
            else if (g == MultiFinger.SWIPE_LEFT) { action = Input.SWITCH_SCREEN; arg = -1; }
            else if (g == MultiFinger.SWIPE_RIGHT) { action = Input.SWITCH_SCREEN; arg = 1; }
        }
        if (action == 0) return;
        gestured = true;
        state = DONE;
        out.feedback(Input.FEEDBACK_GESTURE);
        out.action(action, arg);
    }

    private void lifted(Touches t) {
        switch (state) {
            case DRAG:
                out.button(Input.LEFT, false);
                lastTapUp = Long.MIN_VALUE / 2;
                break;
            case LONG_ARMED:
                out.feedback(Input.FEEDBACK_CLICK);
                out.click(Input.RIGHT);
                lastTapUp = Long.MIN_VALUE / 2;
                break;
            case TAP_ARMED:
                // The second click of a double click; a third tap soon after is a triple click
                out.click(Input.LEFT);
                lastTapUp = t.time;
                break;
            case SWITCHING:
                endSwitch();
                break;
            case SCROLL:
                endScroll(true);
                break;
            default:
                tap(t);
                break;
        }
        state = IDLE;
        fingers = 0;
    }

    private void tap(Touches t) {
        lastTapUp = Long.MIN_VALUE / 2;
        if (gestured || scrolled || travelDp >= tuning.slopDp || t.time - downAt >= tuning.longPressMs) return;
        int button = maxFingers == 1 ? Input.LEFT : maxFingers == 2 ? Input.RIGHT : maxFingers == 3 ? Input.MIDDLE : 0;
        if (button == 0) return;
        out.feedback(Input.FEEDBACK_CLICK);
        out.click(button);
        if (maxFingers == 1) lastTapUp = t.time;
    }

    /** The stroke cannot finish (§2 cancel): whatever it holds goes up, without a click. */
    public void cancel() {
        scheduler.cancel(longPress);
        if (state == DRAG) out.button(Input.LEFT, false);
        else if (state == SCROLL) emitScroll(Input.END, 0, 0);
        else if (state == SWITCHING) out.action(Input.SWITCH_APP, 0);
        stopMomentum();
        multi.cancel();
        state = IDLE;
        fingers = 0;
        lastTapUp = Long.MIN_VALUE / 2;
    }
}
