/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: ConnectionDiagnostics.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk. SALL-SYNC.md says which
 * Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import java.util.Arrays;

/**
 * Recent ping round trips (Sall's ConnectionDiagnostics.Rtt): the latest samples, their median, and a median for
 * every whole second, so the settings page can show one number that does not jump with every pong and can tell a
 * link that has been slow for a while from a momentary lag. Times are {@code SystemClock.uptimeMillis}.
 */
final class Rtt {
    private final long[] times = new long[8];
    private final double[] values = new double[8];
    private int next, count;
    private final long[] seconds = new long[5];
    private int secondsNext, secondsCount;
    private long bucket = -1;

    /** One round trip, in seconds, with when it arrived. Out-of-order or implausible samples are dropped. */
    synchronized void add(double seconds, long now) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 60 || now < 0) return;
        long nextBucket = now / 1000;
        if (bucket >= 0 && nextBucket < bucket) return;
        if (bucket >= 0 && nextBucket > bucket) {
            // A whole second is over: its median goes into the per-second ring for slow()
            Long previous = bucketMedian(bucket);
            if (previous != null) {
                this.seconds[secondsNext] = previous;
                secondsNext = (secondsNext + 1) % this.seconds.length;
                secondsCount = Math.min(this.seconds.length, secondsCount + 1);
            }
        }
        bucket = nextBucket;
        times[next] = now;
        values[next] = seconds * 1000;
        next = (next + 1) % times.length;
        count = Math.min(count + 1, times.length);
    }

    /** The median of the samples of the last 1500 ms, in milliseconds; null when there are none. */
    synchronized Long median(long now) {
        double[] recent = new double[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            long age = now - times[i];
            if (age >= 0 && age <= 1500) recent[n++] = values[i];
        }
        if (n == 0) return null;
        Arrays.sort(recent, 0, n);
        return Math.round(n % 2 == 1 ? recent[n / 2] : (recent[n / 2 - 1] + recent[n / 2]) / 2);
    }

    private Long bucketMedian(long second) {
        double[] recent = new double[count];
        int n = 0;
        for (int i = 0; i < count; i++) if (times[i] / 1000 == second) recent[n++] = values[i];
        if (n == 0) return null;
        Arrays.sort(recent, 0, n);
        return Math.round(n % 2 == 1 ? recent[n / 2] : (recent[n / 2 - 1] + recent[n / 2]) / 2);
    }

    /** Whether the round trip has stayed above 250 ms for at least five seconds (the slow-link advice). */
    synchronized boolean slow(long now) {
        if (secondsCount < seconds.length || median(now) == null) return false;
        long[] recent = seconds.clone();
        Arrays.sort(recent);
        return recent[2] > 250;
    }
}
