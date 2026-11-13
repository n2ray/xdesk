package io.github.n2ray.xdesk.input;

/**
 * Timers for long presses and momentum, on the thread that delivers the touches (a View's handler on Android, a
 * fake clock in tests). Runnables are fields of the engines, created once.
 */
public interface Scheduler {
    void post(Runnable r, long delayMs);
    void cancel(Runnable r);
    /** Milliseconds on the same clock as Touches.time (SystemClock.uptimeMillis on Android). */
    long now();
}
