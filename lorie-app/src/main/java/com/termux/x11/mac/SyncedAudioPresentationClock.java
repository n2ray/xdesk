/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioPresentationClock.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name;
 * records are written out (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this follows
 * and how to compare later.
 */
package com.termux.x11.mac;

/** Validates presentation timestamps in System.nanoTime's timebase, never playback-head estimates.
 * This is a device-adapter contract, not proof that a device's timestamp measures acoustic output.
 * The owner supplies AudioTimestamp's full long framePosition and nanoTime after each physical
 * output reset. Silence counted here must be enqueued through the same AudioOutputTimeline.
 */
final class SyncedAudioPresentationClock {
    static final int RATE = 48000, STARTUP_FRAMES = 12000, CHUNK_FRAMES = 240;
    // A measured Xiaomi timestamp needed 1 ms beyond the 5 ms startup tolerance.
    private static final int READY_TOLERANCE_FRAMES = 288;
    static final long STARTUP_TIMEOUT_NS = 1_000_000_000L, STALE_NS = 250_000_000L;
    static final long STABLE_SPAN_NS = 100_000_000L;
    enum State { STARTING, READY, FAILED, STALE }

    static final class Reading {
        private final long frame;
        private final long nanos;

        Reading(long frame, long nanos) {
            this.frame = frame;
            this.nanos = nanos;
        }

        long frame() { return frame; }
        long nanos() { return nanos; }
    }

    private long generation, started, priorFrame, priorNanos, lastProgress;
    private long anchorFrame, anchorNanos;
    private int observations;
    private int startupFrames;
    private boolean havePrior;
    private State state = State.STARTING;
    private Reading reading;
    private String failure;

    SyncedAudioPresentationClock(long now) { reset(now); }
    long generation() { return generation; }
    State state() { return state; }
    Reading reading() { return state == State.READY ? reading : null; }
    String failure() { return failure; }
    long reset(long now) {
        generation = Math.incrementExact(generation);
        started = lastProgress = now; startupFrames = 0; havePrior = false;
        priorFrame = priorNanos = 0; reading = null; state = State.STARTING;
        anchorFrame = anchorNanos = 0; observations = 0;
        failure = null;
        return generation;
    }
    /** Returns bounded bootstrap replenishment. No invented mapping without a hardware timestamp.
     * Repeated calls cannot allocate beyond 250 ms. Zero may mean wait, READY, or FAILED.
     */
    int startupSilence(long token, long now) {
        return startupSilence(token, now, 960);
    }
    int startupSilence(long token, long now, int leadFrames) {
        if (token != generation || check(now) != State.STARTING) return 0;
        // Catch up elapsed time, not timer invocation count: a delayed 5 ms task must feed
        // more than 5 ms. Already queued silence counts toward the absolute budget.
        long target = (now - started) * RATE / 1_000_000_000L + Math.max(CHUNK_FRAMES, leadFrames);
        int frames = (int) Math.max(0, Math.min(STARTUP_FRAMES, target) - startupFrames);
        startupFrames += frames;
        return frames;
    }
    State check(long now) {
        if (state == State.FAILED) return state;
        if (now < started || (state == State.STARTING && now - started >= STARTUP_TIMEOUT_NS)
                || (state == State.READY && now - lastProgress > STALE_NS)) {
            failure = now < started ? "monotonic clock moved backwards"
                    : state == State.STARTING ? "startup timeout" : "timestamp stalled";
            state = State.FAILED; reading = null;
        }
        return state;
    }
    State observe(long token, long frame, long stampNanos, long now, long writtenFrames) {
        if (token != generation) return State.STALE;
        if (check(now) == State.FAILED) return state;
        // A newly started track can return an old or temporarily unavailable timestamp.
        if (frame < 0 || stampNanos < started || stampNanos > now
                || now - stampNanos > STALE_NS || frame > writtenFrames) return state;
        if (havePrior) {
            if (frame < priorFrame || stampNanos < priorNanos) return discontinuity(frame, stampNanos);
            if (frame == priorFrame || stampNanos == priorNanos) return state;
            double elapsedFrames = (stampNanos - priorNanos) / 1e9 * RATE;
            double anchorElapsedFrames = (stampNanos - anchorNanos) / 1e9 * RATE;
            int tolerance = state == State.READY ? READY_TOLERANCE_FRAMES : CHUNK_FRAMES;
            if (Math.abs((frame - priorFrame) - elapsedFrames) > tolerance
                    || Math.abs((frame - anchorFrame) - anchorElapsedFrames) > tolerance)
                return discontinuity(frame, stampNanos);
            observations = Math.min(3, observations + 1);
            if (observations >= 3 && stampNanos - anchorNanos >= STABLE_SPAN_NS) {
                state = State.READY;
                anchorFrame = frame; anchorNanos = stampNanos;
            }
        } else {
            anchorFrame = frame; anchorNanos = stampNanos; observations = 1;
        }
        havePrior = true; priorFrame = frame; priorNanos = stampNanos;
        lastProgress = now; reading = new Reading(frame, stampNanos);
        return state;
    }
    private State discontinuity(long frame, long nanos) {
        if (state == State.READY) {
            failure = "frameDelta=" + (frame - priorFrame) + " timeDeltaMs=" + ((nanos - priorNanos) / 1_000_000)
                    + " anchorFrameDelta=" + (frame - anchorFrame) + " anchorTimeDeltaMs=" + ((nanos - anchorNanos) / 1_000_000);
            return fail();
        }
        // An untrusted startup candidate may settle; neither deadline nor silence budget resets.
        anchorFrame = priorFrame = frame; anchorNanos = priorNanos = nanos;
        observations = 1; reading = null;
        return state;
    }
    private State fail() { reading = null; return state = State.FAILED; }
}
