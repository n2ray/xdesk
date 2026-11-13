/*
 * Ported from sall (io.github.n2ray.sall.MultiFingerGesture), Copyright 2026 Noah Saffer, Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Changed: fixed arrays instead of maps and records, so that no touch
 * event allocates; decisions as int codes; Java 8; travel of the armed fingers readable while they move.
 */
package io.github.n2ray.xdesk.input;

/**
 * Three- and four-finger stroke rules from iOS TrackpadGestures, in dp and milliseconds: fingers landing nearly
 * together, moving the same way, far or fast enough.
 */
final class MultiFinger {
    static final int SWIPE_LEFT = 1, SWIPE_RIGHT = 2, SWIPE_UP = 3, SWIPE_DOWN = 4, PINCH = 5, SPREAD = 6;
    private static final int MAX = Touches.MAX, SAMPLES = 64;

    // Fingers down, by slot
    private final int[] ids = new int[MAX];
    private final boolean[] used = new boolean[MAX];
    private final long[] landedAt = new long[MAX];
    private final float[] landedX = new float[MAX], landedY = new float[MAX], nowX = new float[MAX], nowY = new float[MAX];
    // Armed: the three that started the gesture, where they were then. Others: fingers down beside them.
    private final boolean[] armed = new boolean[MAX], other = new boolean[MAX];
    private final float[] armedX = new float[MAX], armedY = new float[MAX], otherX = new float[MAX], otherY = new float[MAX];
    // Recent centroids of the armed fingers, oldest first from sampleHead
    private final long[] sampleT = new long[SAMPLES];
    private final float[] sampleX = new float[SAMPLES], sampleY = new float[SAMPLES];
    private int sampleHead, sampleCount;
    private final int[] order = new int[MAX];
    private int downCount, armedCount;
    private boolean silent, travelled, done;
    private float cx, cy;

    static int gesture(int decision) { return decision >> 4; }
    static int fingers(int decision) { return decision & 15; }

    boolean isArmed() { return armedCount > 0; }
    boolean isDone() { return done; }
    int downCount() { return downCount; }

    /** No decision for this stroke any more: something else took it (held app switching). */
    void finish() { done = true; }

    /** Returns true the moment a stroke becomes a multi-finger one, from then on it is not a pointer or scroll. */
    boolean down(int id, float x, float y, long time, boolean holding) {
        if (downCount == 0) reset();
        int s = free();
        if (s < 0) return false;
        used[s] = true;
        ids[s] = id;
        landedAt[s] = time;
        landedX[s] = nowX[s] = x;
        landedY[s] = nowY[s] = y;
        downCount++;
        if (downCount >= 5) done = true;
        if (armedCount > 0) {
            other[s] = true;
            otherX[s] = x;
            otherY[s] = y;
            return false;
        }
        if (downCount < 3 || travelled || holding) return false;
        boolean justSilenced = !silent;
        silent = true;
        // The last three to land, ordered by time and then id
        int n = 0;
        for (int i = 0; i < MAX; i++) if (used[i]) order[n++] = i;
        for (int i = 1; i < n; i++) {
            int v = order[i], j = i - 1;
            while (j >= 0 && later(order[j], v)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        int first = order[n - 3];
        if (!done && time - landedAt[first] <= 150) {
            for (int k = n - 3; k < n; k++) {
                int f = order[k];
                armed[f] = true;
                armedX[f] = nowX[f];
                armedY[f] = nowY[f];
                armedCount++;
            }
            for (int k = 0; k < n - 3; k++) {
                int f = order[k];
                other[f] = true;
                otherX[f] = nowX[f];
                otherY[f] = nowY[f];
            }
            centroidArmed(false);
            addSample(time, cx, cy);
        }
        return justSilenced;
    }

    private boolean later(int a, int b) {
        return landedAt[a] > landedAt[b] || (landedAt[a] == landedAt[b] && ids[a] > ids[b]);
    }

    void move(int id, float x, float y, long time) {
        int s = slot(id);
        if (s < 0) return;
        nowX[s] = x;
        nowY[s] = y;
        if (armedCount == 0) {
            if (Math.hypot(landedX[s] - x, landedY[s] - y) >= 24) travelled = true;
            return;
        }
        if (!done && armed[s]) {
            centroidArmed(true);
            addSample(time, cx, cy);
            // Only the most recent 50 ms and the sample just before it can affect flick speed
            while (sampleCount > 2 && sampleT[(sampleHead + 1) % SAMPLES] <= time - 50) {
                sampleHead = (sampleHead + 1) % SAMPLES;
                sampleCount--;
            }
        }
    }

    /** Returns a decision (gesture << 4 | fingers) when the first armed finger lifts, otherwise 0. */
    int up(int id, float x, float y, long time) {
        int s = slot(id);
        if (s < 0) return 0;
        move(id, x, y, time);
        int decision = 0;
        if (!done && armed[s]) {
            done = true;
            decision = decide(time);
        }
        used[s] = false;
        downCount--;
        return decision;
    }

    void cancel() {
        reset();
    }

    /** How far the armed fingers' centroid went since they were armed, in dp: out[0] across, out[1] down. */
    void armedTravel(float[] out) {
        if (armedCount == 0) {
            out[0] = out[1] = 0;
            return;
        }
        centroidArmed(false);
        float sx = cx, sy = cy;
        centroidArmed(true);
        out[0] = cx - sx;
        out[1] = cy - sy;
    }

    private void reset() {
        for (int i = 0; i < MAX; i++) used[i] = armed[i] = other[i] = false;
        downCount = armedCount = 0;
        sampleHead = sampleCount = 0;
        silent = travelled = done = false;
    }

    private int slot(int id) {
        for (int i = 0; i < MAX; i++) if (used[i] && ids[i] == id) return i;
        return -1;
    }

    private int free() {
        for (int i = 0; i < MAX; i++) if (!used[i]) return i;
        return -1;
    }

    private void addSample(long time, float x, float y) {
        if (sampleCount == SAMPLES) {
            sampleHead = (sampleHead + 1) % SAMPLES;
            sampleCount--;
        }
        int k = (sampleHead + sampleCount) % SAMPLES;
        sampleT[k] = time;
        sampleX[k] = x;
        sampleY[k] = y;
        sampleCount++;
    }

    /** Centroid of the armed fingers into cx, cy: where they were armed, or where they are now (a lifted one stays where it was armed). */
    private void centroidArmed(boolean now) {
        float x = 0, y = 0;
        int n = 0;
        for (int i = 0; i < MAX; i++) {
            if (!armed[i]) continue;
            boolean live = now && used[i];
            x += live ? nowX[i] : armedX[i];
            y += live ? nowY[i] : armedY[i];
            n++;
        }
        cx = n > 0 ? x / n : 0;
        cy = n > 0 ? y / n : 0;
    }

    private boolean mover(int i) {
        return other[i] && Math.hypot(nowX[i] - otherX[i], nowY[i] - otherY[i]) >= 24;
    }

    private int decide(long time) {
        centroidArmed(false);
        float startX = cx, startY = cy;
        centroidArmed(true);
        float endX = cx, endY = cy;
        float dx = endX - startX, dy = endY - startY;
        double travel = Math.hypot(dx, dy);
        int movers = 0;
        for (int i = 0; i < MAX; i++) if (mover(i)) movers++;
        int fingers = Math.min(4, 3 + movers);
        if (travel >= 40 || (travel >= 20 && speed(endX, endY, time) >= 500)) {
            float major = Math.max(Math.abs(dx), Math.abs(dy)), minor = Math.min(Math.abs(dx), Math.abs(dy));
            if (major >= 1.3f * minor) {
                boolean horizontal = Math.abs(dx) > Math.abs(dy);
                float direction = Math.signum(horizontal ? dx : dy), along = horizontal ? Math.abs(dx) : Math.abs(dy);
                boolean together = true;
                for (int i = 0; i < MAX; i++) {
                    if (!armed[i]) continue;
                    float ax = armedX[i], ay = armedY[i], bx = used[i] ? nowX[i] : armedX[i], by = used[i] ? nowY[i] : armedY[i];
                    if ((horizontal ? bx - ax : by - ay) * direction < .5f * along) together = false;
                }
                for (int i = 0; i < MAX; i++) {
                    if (!mover(i)) continue;
                    if ((horizontal ? nowX[i] - otherX[i] : nowY[i] - otherY[i]) * direction <= -.5f * along) together = false;
                }
                if (together) {
                    int g = horizontal ? (dx < 0 ? SWIPE_LEFT : SWIPE_RIGHT) : (dy < 0 ? SWIPE_UP : SWIPE_DOWN);
                    return g << 4 | fingers;
                }
            }
        }
        double r0 = spread(false), r1 = spread(true);
        if (r0 <= 0) return 0;
        if (r1 / r0 <= .75) return PINCH << 4 | fingers;
        if (r1 / r0 >= 1.25) return SPREAD << 4 | fingers;
        return 0;
    }

    private double speed(float endX, float endY, long time) {
        if (sampleCount == 0) return 0;
        int from = sampleHead;
        for (int k = 0; k < sampleCount; k++) {
            int i = (sampleHead + k) % SAMPLES;
            if (sampleT[i] <= time - 50) from = i;
        }
        return time > sampleT[from] ? Math.hypot(endX - sampleX[from], endY - sampleY[from]) * 1000 / (time - sampleT[from]) : 0;
    }

    /** Mean distance from the centroid of the armed fingers and the movers, before (their start points) or after. */
    private double spread(boolean after) {
        float x = 0, y = 0;
        int n = 0;
        for (int i = 0; i < MAX; i++) {
            if (armed[i]) {
                x += after && used[i] ? nowX[i] : armedX[i];
                y += after && used[i] ? nowY[i] : armedY[i];
                n++;
            } else if (mover(i)) {
                x += after ? nowX[i] : otherX[i];
                y += after ? nowY[i] : otherY[i];
                n++;
            }
        }
        if (n == 0) return 0;
        float mx = x / n, my = y / n;
        double sum = 0;
        for (int i = 0; i < MAX; i++) {
            if (armed[i]) {
                sum += Math.hypot((after && used[i] ? nowX[i] : armedX[i]) - mx, (after && used[i] ? nowY[i] : armedY[i]) - my);
            } else if (mover(i)) {
                sum += Math.hypot((after ? nowX[i] : otherX[i]) - mx, (after ? nowY[i] : otherY[i]) - my);
            }
        }
        return sum / n;
    }
}
