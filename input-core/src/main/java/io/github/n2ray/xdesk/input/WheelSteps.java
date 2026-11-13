package io.github.n2ray.xdesk.input;

/** Fine scrolling turned into whole wheel steps for a target that only knows those, keeping the rest. */
public final class WheelSteps {
    private float restX, restY;
    private int stepX, stepY;

    /** Adds a fine delta; true when there are whole steps, then read with x() and y(). */
    public boolean add(float dx, float dy) {
        restX += dx;
        restY += dy;
        stepX = (int) restX;
        stepY = (int) restY;
        if (stepX == 0 && stepY == 0) return false;
        restX -= stepX;
        restY -= stepY;
        return true;
    }

    public int x() { return stepX; }
    public int y() { return stepY; }

    /** A new gesture starts from nothing, not from the last one's rest. */
    public void clear() {
        restX = restY = 0;
    }
}
