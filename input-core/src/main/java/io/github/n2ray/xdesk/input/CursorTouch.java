package io.github.n2ray.xdesk.input;

/**
 * "Like a tablet" on a surface the user does not look at (INPUT.md §4: the phone's pad for an app that takes
 * touches). The user watches the big screen, so positions come from the cursor, not from the finger on the phone.
 *
 * <ul>
 * <li>One finger moves the cursor.</li>
 * <li>A tap is a tap at the cursor. A hold puts a finger down at the cursor, so the app sees its own long press;
 * moving then drags it. A press right after a tap does the same at once (double tap and drag).</li>
 * <li>Two or more fingers are real fingers, placed around the cursor: pinch, rotate, two-finger scroll and the app's
 * own multi-finger gestures pass through.</li>
 * <li>Three or four fingers landing together are XDesk's gestures, as on the touchpad (§10 case 5): fingers already
 * passed on are cancelled.</li>
 * </ul>
 */
public final class CursorTouch {
    private static final int MAX_FINGERS = 5;
    private static final int IDLE = 0, ONE = 1, CURSOR = 2, DRAG = 3, MULTI = 4, GESTURE = 5, DONE = 6;
    private static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3, ACTION_POINTER_DOWN = 5,
        ACTION_POINTER_UP = 6, POINTER_INDEX_SHIFT = 8;
    private static final float SWITCH_START_DP = 40, SWITCH_STEP_DP = 80;

    private final Tuning tuning;
    private final Scheduler scheduler;
    private final GestureOutput out;
    private final MultiFinger multi = new MultiFinger();
    private final PointerRest rest = new PointerRest();
    private final float[] travel2 = new float[2];
    private final int[] phoneId = new int[MAX_FINGERS];
    private final float[] fingerX = new float[MAX_FINGERS], fingerY = new float[MAX_FINGERS];
    // What one frame sends: reused
    private final int[] ids = new int[MAX_FINGERS];
    private final float[] offX = new float[MAX_FINGERS], offY = new float[MAX_FINGERS];

    private int state = IDLE, count;
    private float scale = 1f, viewW, viewH, displayW, displayH;
    private float lastX, lastY, travel, centroidX, centroidY;
    private long lastTapUp = Long.MIN_VALUE / 2;
    private boolean switching;
    private float switchFromX;

    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (state != ONE) return;
            state = DRAG;
            out.feedback(Input.FEEDBACK_LONG_PRESS);
            out.button(Input.LEFT, true);
        }
    };

    public CursorTouch(Tuning tuning, Scheduler scheduler, GestureOutput out) {
        this.tuning = tuning;
        this.scheduler = scheduler;
        this.out = out;
        for (int i = 0; i < MAX_FINGERS; i++) phoneId[i] = -1;
    }

    /** The touched view's size: fingers spread by the ratio to the display, so a pinch has its effect there. */
    public void setViewSize(float w, float h) {
        viewW = w;
        viewH = h;
        updateScale();
    }

    public void setDisplaySize(float w, float h) {
        displayW = w;
        displayH = h;
        updateScale();
    }

    private void updateScale() {
        if (viewW > 0 && viewH > 0 && displayW > 0 && displayH > 0) scale = Math.min(displayW / viewW, displayH / viewH);
    }

    private int slotOf(int id) {
        for (int i = 0; i < MAX_FINGERS; i++) if (phoneId[i] == id) return i;
        return -1;
    }

    /** This finger's index in a frame: ordered by slot. */
    private int indexOf(int slot) {
        int index = 0;
        for (int i = 0; i < slot; i++) if (phoneId[i] >= 0) index++;
        return index;
    }

    private void updateFingers(Touches t) {
        for (int p = 0; p < t.count; p++) {
            int slot = slotOf(t.id[p]);
            if (slot < 0) continue;
            fingerX[slot] = t.x[p];
            fingerY[slot] = t.y[p];
        }
    }

    /**
     * Each finger = cursor + (finger − centre when multi-touch began) × scale. The centre stays fixed, so the group
     * moves together and adding or lifting a finger does not make the others jump.
     */
    private void send(int action) {
        int n = 0;
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (phoneId[i] < 0) continue;
            ids[n] = i;
            offX[n] = (fingerX[i] - centroidX) * scale;
            offY[n] = (fingerY[i] - centroidY) * scale;
            n++;
        }
        out.frame(action, ids, offX, offY, n);
    }

    private void beginMulti() {
        scheduler.cancel(hold);
        float sx = 0, sy = 0;
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (phoneId[i] < 0) continue;
            sx += fingerX[i];
            sy += fingerY[i];
        }
        centroidX = sx / count;
        centroidY = sy / count;
        state = MULTI;
        // The injector sees nothing started yet: it puts all fingers down in turn, and cancels a held press
        send(ACTION_POINTER_DOWN);
    }

    private void moveOne(Touches t) {
        float d = tuning.density;
        float dx = (t.x[0] - lastX) / d, dy = (t.y[0] - lastY) / d;
        lastX = t.x[0];
        lastY = t.y[0];
        if (state == ONE) {
            travel += Math.abs(dx) + Math.abs(dy);
            if (travel < tuning.slopDp) return;
            scheduler.cancel(hold);
            state = CURSOR;
        }
        float gain = tuning.gain((float) Math.hypot(dx, dy));
        if (rest.add(dx * gain * d, dy * gain * d)) out.move(rest.dx, rest.dy);
    }

    public boolean onTouch(Touches t) {
        float d = tuning.density;
        int id = t.id[t.index];
        switch (t.action) {
            case Touches.DOWN:
                clear();
                phoneId[0] = id;
                count = 1;
                updateFingers(t);
                lastX = t.x[0];
                lastY = t.y[0];
                travel = 0;
                rest.clear();
                multi.down(id, t.x[0] / d, t.y[0] / d, t.time, false);
                if (t.time - lastTapUp < tuning.doubleTapMs) {
                    state = DRAG;
                    out.button(Input.LEFT, true);
                } else {
                    state = ONE;
                    scheduler.post(hold, tuning.longPressMs);
                }
                break;
            case Touches.POINTER_DOWN: {
                boolean silenced = multi.down(id, t.x[t.index] / d, t.y[t.index] / d, t.time, state == DRAG);
                int slot = slotOf(-1);
                if (slot < 0) break;  // Already the most fingers: this one does not count
                phoneId[slot] = id;
                count++;
                updateFingers(t);
                if (state == GESTURE || state == DONE) break;
                if (multi.isArmed()) {
                    // XDesk's gesture: what the app already has is taken back
                    if (state == MULTI) send(ACTION_CANCEL);
                    else if (state == DRAG) out.button(Input.LEFT, false);
                    scheduler.cancel(hold);
                    state = GESTURE;
                } else if (silenced) {
                    if (state == MULTI) send(ACTION_CANCEL);
                    state = DONE;
                } else if (state == MULTI) {
                    send(ACTION_POINTER_DOWN | indexOf(slot) << POINTER_INDEX_SHIFT);
                } else {
                    beginMulti();
                }
                break;
            }
            case Touches.MOVE:
                for (int i = 0; i < t.count; i++) multi.move(t.id[i], t.x[i] / d, t.y[i] / d, t.time);
                updateFingers(t);
                if (state == MULTI) send(ACTION_MOVE);
                else if (state == GESTURE) gestureMove();
                else if (state == ONE || state == CURSOR || state == DRAG) moveOne(t);
                break;
            case Touches.POINTER_UP: {
                for (int i = 0; i < t.count; i++) multi.move(t.id[i], t.x[i] / d, t.y[i] / d, t.time);
                int decision = multi.up(id, t.x[t.index] / d, t.y[t.index] / d, t.time);
                if (state == GESTURE) {
                    if (switching) endSwitch();
                    else decided(decision);
                }
                int slot = slotOf(id);
                if (slot < 0) break;
                updateFingers(t);
                if (state == MULTI) {
                    if (count > 1) {
                        send(ACTION_POINTER_UP | indexOf(slot) << POINTER_INDEX_SHIFT);
                    } else {
                        // Only fingers beyond the most are still down
                        send(ACTION_UP);
                        state = DONE;
                    }
                }
                phoneId[slot] = -1;
                count--;
                break;
            }
            case Touches.UP:
                scheduler.cancel(hold);
                updateFingers(t);
                int decision = multi.up(id, t.x[0] / d, t.y[0] / d, t.time);
                if (state == MULTI) {
                    if (count > 0) send(ACTION_UP);
                } else if (state == DRAG) {
                    out.button(Input.LEFT, false);
                } else if (state == ONE) {
                    out.click(Input.LEFT);
                    lastTapUp = t.time;
                } else if (state == GESTURE) {
                    if (switching) endSwitch();
                    else decided(decision);
                }
                clear();
                break;
            case Touches.CANCEL:
                cancel();
                break;
            default:
                break;
        }
        return state != IDLE;
    }

    private void gestureMove() {
        if (switching) {
            multi.armedTravel(travel2);
            float dx = travel2[0] - switchFromX;
            if (Math.abs(dx) < SWITCH_STEP_DP) return;
            switchFromX = travel2[0];
            out.action(Input.SWITCH_APP, dx > 0 ? 1 : -1);
            return;
        }
        if (multi.downCount() != 3 || multi.isDone()) return;
        multi.armedTravel(travel2);
        float dx = travel2[0], dy = travel2[1];
        if (Math.abs(dx) < SWITCH_START_DP || Math.abs(dx) < 1.3f * Math.abs(dy)) return;
        multi.finish();
        switching = true;
        switchFromX = dx;
        out.feedback(Input.FEEDBACK_GESTURE);
        out.action(Input.SWITCH_APP, dx > 0 ? 1 : -1);
    }

    private void endSwitch() {
        switching = false;
        out.action(Input.SWITCH_APP, 0);
        state = DONE;
    }

    private void decided(int decision) {
        if (decision == 0) return;
        int g = MultiFinger.gesture(decision), n = MultiFinger.fingers(decision);
        int action = 0, arg = 0;
        if (n == 3) {
            if (g == MultiFinger.SWIPE_UP) action = Input.OVERVIEW;
            else if (g == MultiFinger.SWIPE_DOWN) action = Input.HOME;
            else if (g == MultiFinger.SWIPE_LEFT || g == MultiFinger.SWIPE_RIGHT) {
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
        state = DONE;
        out.feedback(Input.FEEDBACK_GESTURE);
        out.action(action, arg);
    }

    private void clear() {
        for (int i = 0; i < MAX_FINGERS; i++) phoneId[i] = -1;
        count = 0;
        state = IDLE;
        switching = false;
    }

    /** The gesture cannot finish: rotation, another mode, the window went. */
    public void cancel() {
        scheduler.cancel(hold);
        if (state == MULTI) send(ACTION_CANCEL);
        else if (state == DRAG) out.button(Input.LEFT, false);
        else if (switching) out.action(Input.SWITCH_APP, 0);
        multi.cancel();
        clear();
    }
}
