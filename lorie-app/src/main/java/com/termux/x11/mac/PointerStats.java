package com.termux.x11.mac;

import android.os.SystemClock;
import android.util.Log;

/**
 * How evenly pointer moves pass through XDesk on their way to a Mac, to set against the Mac's own count: Sill's log
 * has, per second, in.gap>25ms, in.gap>50ms and in.gap>100ms, the moves that arrived that long after the one before
 * (a pause of a second or more is not counted). The same three counts are kept here at three places, and a line
 * goes to the log every few seconds while moves are sent:
 *
 *   mouse      the captured mouse's reports as the kernel timed them (MotionEvent's event times, each batched sample)
 *   dispatched the events as Android handed them to XDesk's main thread; "late" is how long after the report
 *   sent       the moves as the sender thread wrote them to the connection; "queued" is how long a move waited for
 *              that thread, "write" how long the socket took to accept it
 *
 * Gaps that are already in "mouse" are the mouse's (Bluetooth) or the hand's; ones that first appear in "dispatched"
 * are the main thread's or the frame the events wait for; ones first in "sent", or a long "queued" or "write", are
 * the sender's or a full socket; and what the Mac counts beyond "sent" came on the way there. The buckets are
 * exclusive, as the Mac's are: a gap over 100 ms is not also one over 25.
 */
public final class PointerStats {
    private static final String TAG = "XDeskMac";
    private static final long EVERY_MS = 3000, PAUSE_MS = 1000, STALE_MS = 5000;

    /** One place's gaps. */
    private static final class Gaps {
        long last;
        int count, over25, over50, over100;
        long max;

        void at(long now) {
            count++;
            long gap = now - last;
            if (last != 0 && gap < PAUSE_MS) {
                if (gap > 100) over100++;
                else if (gap > 50) over50++;
                else if (gap > 25) over25++;
                if (gap > max) max = gap;
            }
            last = now;
        }

        void reset() {
            count = over25 = over50 = over100 = 0;
            max = 0;
        }

        @Override public String toString() {
            return count + " gap>25/50/100 " + over25 + "/" + over50 + "/" + over100 + " max " + max + "ms";
        }
    }

    private static final Gaps mouse = new Gaps(), dispatched = new Gaps(), sent = new Gaps();
    private static long late, queued, write, since;

    private PointerStats() { }

    /** One report of the captured mouse, at the time the system read it (uptime). Main thread. */
    public static synchronized void sample(long eventTime) {
        mouse.at(eventTime);
    }

    /** One event with moves handed to XDesk; newest is the time of its last report. Main thread. */
    public static synchronized void dispatched(long newest) {
        long now = SystemClock.uptimeMillis();
        dispatched.at(now);
        if (now - newest > late) late = now - newest;
    }

    /** One move written to the Mac's connection, after waiting waitMs in the queue and writeMs in the socket. */
    static void sent(long waitMs, long writeMs) {
        String line = null;
        synchronized (PointerStats.class) {
            long now = SystemClock.uptimeMillis();
            if (since == 0 || now - sent.last > STALE_MS) {
                // Nothing went to a Mac for a while (the mouse was an X desktop's, or still): what was counted is old
                mouse.reset();
                dispatched.reset();
                sent.reset();
                late = queued = write = 0;
                since = now;
            }
            sent.at(now);
            if (waitMs > queued) queued = waitMs;
            if (writeMs > write) write = writeMs;
            if (now - since >= EVERY_MS) {
                line = "moves in " + (now - since) / 100 / 10f + "s: mouse " + mouse + " · dispatched " + dispatched + ", late max " + late
                    + "ms · sent " + sent + ", queued max " + queued + "ms, write max " + write + "ms";
                mouse.reset();
                dispatched.reset();
                sent.reset();
                late = queued = write = 0;
                since = now;
            }
        }
        if (line != null) Log.i(TAG, line);
    }
}
