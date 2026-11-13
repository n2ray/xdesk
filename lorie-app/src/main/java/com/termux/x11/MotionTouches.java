package com.termux.x11;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import io.github.n2ray.xdesk.input.Scheduler;
import io.github.n2ray.xdesk.input.Touches;
import io.github.n2ray.xdesk.input.Tuning;

/** Android's side of input-core: MotionEvents into Touches without allocating, a view's handler as the timer. */
final class MotionTouches {
    private MotionTouches() { }

    /** Copies the event; pointers beyond Touches.MAX are left out, and their own downs and ups become moves. */
    static Touches fill(Touches t, MotionEvent e) {
        int action = e.getActionMasked(), index = e.getActionIndex();
        if ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) && index >= Touches.MAX) {
            action = MotionEvent.ACTION_MOVE;
            index = 0;
        }
        t.action = action;
        t.index = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ? index : 0;
        t.time = e.getEventTime();
        t.tool = e.getToolType(0);
        int n = Math.min(e.getPointerCount(), Touches.MAX);
        t.count = n;
        for (int i = 0; i < n; i++) {
            t.id[i] = e.getPointerId(i);
            t.x[i] = e.getX(i);
            t.y[i] = e.getY(i);
        }
        return t;
    }

    /** The system's timings (they follow the accessibility settings) and the screen's density. */
    static Tuning tuning(View v) {
        Tuning t = new Tuning();
        t.density = v.getResources().getDisplayMetrics().density;
        t.longPressMs = ViewConfiguration.getLongPressTimeout();
        t.doubleTapMs = ViewConfiguration.getDoubleTapTimeout();
        return t;
    }

    static Scheduler scheduler(final View v) {
        return new Scheduler() {
            @Override public void post(Runnable r, long delayMs) { v.postDelayed(r, delayMs); }
            @Override public void cancel(Runnable r) { v.removeCallbacks(r); }
            @Override public long now() { return SystemClock.uptimeMillis(); }
        };
    }
}
