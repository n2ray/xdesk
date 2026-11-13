package io.github.n2ray.xdesk.input;

/** Speed of a point over the last 100 ms, from a ring of samples: a VelocityTracker without allocation or Android. */
final class Velocity {
    private static final int SIZE = 16;
    private static final long WINDOW_MS = 100;
    private final long[] t = new long[SIZE];
    private final float[] x = new float[SIZE], y = new float[SIZE];
    private int head, n;
    private float vx, vy;

    void clear() {
        n = 0;
        head = 0;
    }

    void add(long time, float px, float py) {
        head = (head + 1) % SIZE;
        t[head] = time;
        x[head] = px;
        y[head] = py;
        if (n < SIZE) n++;
    }

    /** Pixels per second; afterwards read with x() and y(). Zero when there is too little to go on. */
    void compute() {
        vx = vy = 0;
        if (n < 2) return;
        long now = t[head];
        int oldest = head;
        for (int i = 1; i < n; i++) {
            int k = (head - i + SIZE) % SIZE;
            if (now - t[k] > WINDOW_MS) break;
            oldest = k;
        }
        long dt = now - t[oldest];
        if (dt <= 0) return;
        vx = (x[head] - x[oldest]) * 1000f / dt;
        vy = (y[head] - y[oldest]) * 1000f / dt;
    }

    float x() { return vx; }
    float y() { return vy; }
}
