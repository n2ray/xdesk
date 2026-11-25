/*
 * Rules ported from libinput (src/evdev-mt-touchpad-gestures.c, src/libinput-private.h), Copyright © 2015 Red Hat,
 * Inc., MIT License (LICENSES/MIT-libinput.txt). The rest is XDesk's, GPL-3.0.
 */
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
    /**
     * Scroll or pinch, libinput's rules (src/evdev-mt-touchpad-gestures.c, tp_gesture_detect_motion_gestures and
     * tp_gesture_is_pinch, MIT; 17 November: a scroll whose fingers drew a little together or apart zoomed, as only
     * the span change and the middle's travel were compared). Its millimetres are taken at Android's 160 dp an inch.
     * Nothing until a finger has moved UNDECIDED_MM; past SWIPE_TIMEOUT_MS two fingers close together (CLOSE_X_MM
     * across, CLOSE_Y_MM down) scroll; one past MAX_MOVE_MM with the other under MIN_MOVE_MM scrolls (a resting
     * finger); both past MIN_MOVE_MM: the same or neighbouring directions of the eight scroll, others pinch. A scroll
     * still becomes a pinch within PINCH_TIMEOUT_MS if both have moved PINCH_DISAMBIGUATION_MM in directions that are
     * not the same.
     */
    private static final float MM = 160f / 25.4f;
    private static final float UNDECIDED_MM = 1, MIN_MOVE_MM = 1.5f, MAX_MOVE_MM = 4, CLOSE_X_MM = 40, CLOSE_Y_MM = 7,
        PINCH_DISAMBIGUATION_MM = 1.5f;
    private static final long SWIPE_TIMEOUT_MS = 150, PINCH_TIMEOUT_MS = 300;
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
    private long previousMoveAt;
    private float trackingSpeed; // dp/ms, smoothed across one stroke
    private long downAt, lastTapUp = Long.MIN_VALUE / 2;
    private float downX, downY;
    private float lastX, lastY, travelDp, armedTravelDp, pendingX, pendingY;
    private boolean rebase, gestured, scrolled;
    // Two fingers
    private float span0, spanStep, midStartX, midStartY;
    private int twoIdA, twoIdB;
    private long twoAt;
    private float twoAX, twoAY, twoBX, twoBY;
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
                previousMoveAt = downAt;
                trackingSpeed = 0;
                travelDp = 0;
                gestured = scrolled = false;
                rest.clear();
                setLast(t);
                downX = lastX;
                downY = lastY;
                multi.down(t.id[0], t.x[0] / d, t.y[0] / d, t.time, false);
                if (!tuning.desktopPad && t.time - lastTapUp < tuning.doubleTapMs) {
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
            twoIdA = t.id[0];
            twoIdB = t.id[1];
            twoAX = t.x[0];
            twoAY = t.y[0];
            twoBX = t.x[1];
            twoBY = t.y[1];
            twoAt = t.time;
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
        long interval = Math.max(1, t.time - previousMoveAt);
        previousMoveAt = t.time;
        if (tuning.desktopPad && state == ONE)
            travelDp = Math.max(travelDp, (float) Math.hypot(x - downX, y - downY) / d);
        else
            travelDp += step;
        if (travelDp >= tuning.slopDp) scheduler.cancel(longPress);
        switch (state) {
            case ONE:
                if (tuning.desktopPad) {
                    if (travelDp < 2) break;
                    scheduler.cancel(longPress);
                    state = CURSOR;
                } else if (travelDp >= tuning.slopDp) state = CURSOR;
                moveCursor(dx, dy, interval);
                break;
            case CURSOR:
            case DRAG:
                moveCursor(dx, dy, interval);
                break;
            case LONG_ARMED:
            case TAP_ARMED:
                // Nothing moves until it is a drag, so that a double click or a right click lands where the cursor was
                armedTravelDp += step;
                pendingX += dx;
                pendingY += dy;
                if ((tuning.desktopPad ? Math.hypot(pendingX, pendingY) : armedTravelDp) >= tuning.slopDp) {
                    state = DRAG;
                    out.button(Input.LEFT, true);
                    if (!tuning.desktopPad) {
                        out.feedback(Input.FEEDBACK_LONG_PRESS);
                        moveCursor(pendingX, pendingY, interval);
                    }
                }
                break;
            case TWO:
                decideTwo(t);
                break;
            case SCROLL:
                // libinput: a pinch taken for a scroll at first is corrected in its first PINCH_TIMEOUT_MS
                if (t.count == 2 && t.time - twoAt < PINCH_TIMEOUT_MS && isPinch(t)) {
                    emitScroll(Input.END, 0, 0);
                    startPinch(t);
                    break;
                }
                velocity.add(t.time, x, y);
                // libinput's axis lock: a vertical scroll does not wander sideways, nor a horizontal one
                lockMove[0] = dx;
                lockMove[1] = dy;
                axisLock.apply(t.time, dx / MM, dy / MM, lockMove);
                scrollFinger(lockMove[0], lockMove[1], Input.CHANGE);
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

    private void moveCursor(float dxDp, float dyDp, long interval) {
        if (tuning.desktopPad) {
            // Gain in output counts per dp. Slow corrections stay precise on high-density phones.
            float speed = (float) Math.hypot(dxDp, dyDp) / interval;
            // Filter only the gain, so the pointer still responds to each movement immediately.
            float weight = (float) -Math.expm1(-interval / 35.0);
            trackingSpeed += weight * (speed - trackingSpeed);
            // Leave headroom for the HDMI mouse seat's own acceleration.
            float gain = tuning.pointerSpeed * (1f + 1.25f * trackingSpeed / (1f + trackingSpeed));
            if (rest.add(dxDp * gain, dyDp * gain)) out.move(rest.dx, rest.dy);
            return;
        }
        float gain = tuning.gain((float) Math.hypot(dxDp, dyDp)), d = tuning.density;
        // Moves are in pixels of the touched screen, as the touchpad has always sent them
        if (rest.add(dxDp * d * gain, dyDp * d * gain)) out.move(rest.dx, rest.dy);
    }

    private static float span(Touches t) {
        if (t.count < 2) return 0;
        return (float) Math.hypot(t.x[0] - t.x[1], t.y[0] - t.y[1]);
    }

    /** The two fingers' travel since both were down, in mm: out[0..1] the first's, out[2..3] the second's. Null when they are not the same two. */
    private boolean twoTravel(Touches t, float[] out) {
        if (t.count != 2) return false;
        int a = t.id[0] == twoIdA ? 0 : 1, b = 1 - a;
        if (t.id[a] != twoIdA || t.id[b] != twoIdB) return false;
        float k = tuning.density * MM;
        out[0] = (t.x[a] - twoAX) / k;
        out[1] = (t.y[a] - twoAY) / k;
        out[2] = (t.x[b] - twoBX) / k;
        out[3] = (t.y[b] - twoBY) / k;
        return true;
    }

    private final float[] two = new float[4];
    private final ScrollAxisLock axisLock = new ScrollAxisLock();
    private final float[] lockMove = new float[2];

    private void decideTwo(Touches t) {
        if (!twoTravel(t, two)) return;
        float am = (float) Math.hypot(two[0], two[1]), bm = (float) Math.hypot(two[2], two[3]);
        if (am < UNDECIDED_MM && bm < UNDECIDED_MM) return;
        float k = tuning.density * MM;
        float apartX = Math.abs(t.x[0] - t.x[1]) / k, apartY = Math.abs(t.y[0] - t.y[1]) / k;
        if (t.time - twoAt > SWIPE_TIMEOUT_MS && apartX < CLOSE_X_MM && apartY < CLOSE_Y_MM) {
            startScroll(t);
            return;
        }
        // One finger far, the other still: a resting finger, the moving one scrolls
        if ((am >= MAX_MOVE_MM || bm >= MAX_MOVE_MM) && Math.min(am, bm) < MIN_MOVE_MM) {
            startScroll(t);
            return;
        }
        if (am < MIN_MOVE_MM || bm < MIN_MOVE_MM) return;
        if (sameDirections(direction(two[0], two[1]), direction(two[2], two[3]))) startScroll(t);
        else startPinch(t);
    }

    /** tp_gesture_is_pinch: both moved enough, and not the same way. */
    private boolean isPinch(Touches t) {
        if (!twoTravel(t, two)) return false;
        if (Math.hypot(two[0], two[1]) < PINCH_DISAMBIGUATION_MM || Math.hypot(two[2], two[3]) < PINCH_DISAMBIGUATION_MM) return false;
        return !sameDirections(direction(two[0], two[1]), direction(two[2], two[3]));
    }

    private void startScroll(Touches t) {
        float d = tuning.density;
        state = SCROLL;
        scrolled = true;
        axisLock.reset();
        emitScroll(Input.BEGIN, 0, 0);
        velocity.clear();
        velocity.add(t.time, t.centroidX(), t.centroidY());
        // The travel up to here belongs to the scroll, held to its axis as the rest will be
        lockMove[0] = (t.centroidX() - midStartX) / d;
        lockMove[1] = (t.centroidY() - midStartY) / d;
        axisLock.apply(t.time, lockMove[0] / MM, lockMove[1] / MM, lockMove);
        scrollFinger(lockMove[0], lockMove[1], Input.CHANGE);
    }

    private void startPinch(Touches t) {
        // Zoom steps count from where the fingers landed
        state = PINCH;
        spanStep = span0;
        pinch(t);
    }

    /** libinput's xy_get_direction: the one or two of the eight directions (bit 0 north, clockwise) a move in mm is closest to. */
    static int direction(float x, float y) {
        if (Math.abs(x) < 2 && Math.abs(y) < 2) {
            if (x > 0 && y > 0) return S | SE | E;
            if (x > 0 && y < 0) return N | NE | E;
            if (x < 0 && y > 0) return S | SW | W;
            if (x < 0 && y < 0) return N | NW | W;
            if (x > 0) return NE | E | SE;
            if (x < 0) return NW | W | SW;
            if (y > 0) return SE | S | SW;
            if (y < 0) return NE | N | NW;
            return 0xff;
        }
        double r = Math.atan2(y, x);
        r = (r + 2.5 * Math.PI) % (2 * Math.PI);
        r *= 4 / Math.PI;
        int d1 = (int) (r + 0.9) % 8, d2 = (int) (r + 0.1) % 8;
        return 1 << d1 | 1 << d2;
    }

    private static final int N = 1, NE = 2, E = 4, SE = 8, S = 16, SW = 32, W = 64, NW = 128;

    /** libinput's tp_gesture_same_directions: overlapping or neighbouring. */
    static boolean sameDirections(int a, int b) {
        return ((a | (a >> 1)) & b) != 0 || ((b | (b >> 1)) & a) != 0 || ((a & 0x80) != 0 && (b & 0x01) != 0)
            || ((b & 0x80) != 0 && (a & 0x01) != 0);
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
        // The coast keeps to the axis the scroll was held to
        if (!momentum.start(axisLock.freeX() ? velocity.x() : 0, axisLock.freeY() ? velocity.y() : 0, d)) return;
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
        // More across than down is across (17 November: was 1.3 times, too straight for a finger on glass)
        if (Math.abs(dx) < SWITCH_START_DP || Math.abs(dx) <= Math.abs(dy)) return;
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
        if (tuning.desktopPad && state == CURSOR) return;
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
