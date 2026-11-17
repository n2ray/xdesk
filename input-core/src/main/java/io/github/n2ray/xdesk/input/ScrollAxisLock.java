/*
 * Rules ported from libinput (src/evdev-mt-touchpad-gestures.c, src/libinput-private.h), Copyright © 2015 Red Hat,
 * Inc., MIT License (LICENSES/MIT-libinput.txt). The rest is XDesk's, GPL-3.0.
 */
package io.github.n2ray.xdesk.input;

/**
 * Keeps a two-finger scroll on one axis unless it is meant to go diagonally: libinput's
 * tp_gesture_apply_scroll_constraints (src/evdev-mt-touchpad-gestures.c, MIT), in milliseconds and millimetres.
 * A fading vector of the recent moves says where the fingers are going; time spent mostly along an axis (more than
 * 30 degrees from the other for vertical, less than 60 for horizontal) builds up to ACTIVE_MS and makes that axis
 * active, and time spent squarely on the other one (75 / 15 degrees) takes it away again. One axis active: the
 * other's movement is dropped. A clearly diagonal vector longer than 5 mm frees both for the rest of the scroll.
 * Allocates nothing.
 */
final class ScrollAxisLock {
    private static final long ACTIVE_MS = 100, INACTIVE_MS = 50, EVENT_TIMEOUT_MS = 100;
    private static final double DEGREE_75 = 3.73, DEGREE_60 = 1.73, DEGREE_30 = 0.57, DEGREE_15 = 0.27;
    private static final double MIN_VECTOR = 0.15, FREE_VECTOR = 5;

    private double vx, vy;
    private long prev, durV, durH;
    private boolean activeV, activeH;

    void reset() {
        vx = vy = 0;
        prev = durV = durH = 0;
        activeV = activeH = false;
    }

    /** False when the scroll is held to the other axis. */
    boolean freeX() { return activeH || !activeV; }
    boolean freeY() { return activeV || !activeH; }

    /**
     * One move of the scroll, dxMm and dyMm its travel in mm; d holds the move in any unit, zeroed where it may not go.
     */
    void apply(long time, double dxMm, double dyMm, float[] d) {
        if (activeH && activeV) return;
        long tdelta = 0;
        if (prev != 0 && time - prev <= EVENT_TIMEOUT_MS) tdelta = Math.max(0, time - prev);
        prev = time;
        double decay;
        if (tdelta > 0) {
            double recent = (EVENT_TIMEOUT_MS / 2.0 - tdelta) / (EVENT_TIMEOUT_MS / 2.0);
            double later = (EVENT_TIMEOUT_MS - tdelta) / (EVENT_TIMEOUT_MS * 2.0);
            decay = tdelta <= 0.33 * EVENT_TIMEOUT_MS ? recent : later;
        } else {
            decay = 0;
        }
        vx = vx * decay + dxMm;
        vy = vy * decay + dyMm;
        double length = Math.hypot(vx, vy);
        double slope = vx != 0 ? Math.abs(vy / vx) : Double.POSITIVE_INFINITY;
        if (slope >= DEGREE_30 && length > MIN_VECTOR) {
            durV = Math.min(ACTIVE_MS, durV + tdelta);
            if (slope >= DEGREE_75) durH = Math.max(0, durH - tdelta);
        }
        if (slope < DEGREE_60 && length > MIN_VECTOR) {
            durH = Math.min(ACTIVE_MS, durH + tdelta);
            if (slope < DEGREE_15) durV = Math.max(0, durV - tdelta);
        }
        if (durH == ACTIVE_MS) {
            activeH = true;
            if (durV < INACTIVE_MS) activeV = false;
        }
        if (durV == ACTIVE_MS) {
            activeV = true;
            if (durH < INACTIVE_MS) activeH = false;
        }
        if (length > FREE_VECTOR && slope < DEGREE_60 && slope >= DEGREE_30) activeV = activeH = true;
        if (!activeH && activeV) d[0] = 0;
        if (activeH && !activeV) d[1] = 0;
        if (!activeH && !activeV) {
            if (slope >= DEGREE_60) d[0] = 0;
            if (slope < DEGREE_30) d[1] = 0;
        }
    }
}
