/*
 * Ported from sall (io.github.n2ray.sall.ScrollMomentum), Copyright 2026 Noah Saffer, Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Changed: package and visibility.
 */
package io.github.n2ray.xdesk.input;

/** The same 60 Hz exponential coast used by the iOS trackpad. Velocities in pixels per second. */
final class ScrollMomentum {
    private double x, y;

    boolean start(double velocityX, double velocityY, double density) {
        if (!isFinite(velocityX) || !isFinite(velocityY) || !isFinite(density) || density <= 0
                || Math.hypot(velocityX, velocityY) <= 150 * density) {
            x = y = 0;
            return false;
        }
        x = velocityX;
        y = velocityY;
        return true;
    }

    double firstX() { return x / 60; }
    double firstY() { return y / 60; }

    /** Advance by elapsed seconds, returning whether another delta is visible. */
    boolean advance(double elapsed, double density) {
        if (!isFinite(elapsed) || elapsed <= 0 || !isFinite(density) || density <= 0) return false;
        double decay = Math.pow(.96, Math.min(elapsed, .1) * 60);
        x *= decay;
        y *= decay;
        return Math.hypot(x, y) / 60 >= .3 * density;
    }

    double stepX(double elapsed) { return x * Math.min(elapsed, .1); }
    double stepY(double elapsed) { return y * Math.min(elapsed, .1); }

    void clear() { x = y = 0; }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }
}
