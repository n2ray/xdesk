package io.github.n2ray.xdesk.input;

/** Sub-pixel rest of pointer movement, so slow fingers still move the cursor. */
final class PointerRest {
    private float restX, restY;
    int dx, dy;

    /** Adds a move in pixels; true when it adds up to whole pixels, read from dx and dy. */
    boolean add(float x, float y) {
        restX += x;
        restY += y;
        dx = (int) restX;
        dy = (int) restY;
        if (dx == 0 && dy == 0) return false;
        restX -= dx;
        restY -= dy;
        return true;
    }

    void clear() {
        restX = restY = 0;
    }
}
