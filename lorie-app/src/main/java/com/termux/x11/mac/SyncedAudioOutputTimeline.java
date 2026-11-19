/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioOutputTimeline.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name;
 * records are written out (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this follows
 * and how to compare later.
 */
package com.termux.x11.mac;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/** Serial-queue adapter for already rendered AudioPlayout schedules, not an AudioTrack driver.
 * The model retains its editable 240-frame tail until it emits TAIL; never enqueue that tail early.
 * All positions count stereo frames since the most recent physical output flush.
 */
final class SyncedAudioOutputTimeline {
    interface Sink {
        /** Nonblocking interleaved-float write; returns floats accepted (possibly odd), zero, or error. */
        int write(float[] samples, int offset, int count);
    }
    enum Drain { EMPTY, MORE, BLOCKED, CANCELLED, STALE, ERROR }
    private static final class Chunk {
        final float[] samples;
        int offset;
        Chunk(float[] samples) { this.samples = samples; }
    }
    private final int capacityFrames;
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private long generation, end, writtenFloats, pendingFloats;
    private boolean failed;

    SyncedAudioOutputTimeline(int capacityFrames) {
        if (capacityFrames < 1 || capacityFrames > Integer.MAX_VALUE / 2) throw new IllegalArgumentException();
        this.capacityFrames = capacityFrames;
    }
    long generation() { return generation; }
    long endFrame() { return end; }
    long writtenFrames() { return writtenFloats / 2; }
    long pendingFloats() { return pendingFloats; }

    /** Atomic rejection: never truncate late/overlapping PCM or allocate an unbounded silence gap. */
    boolean enqueue(long token, long start, float[] left, float[] right) {
        if (token != generation || failed || start < end || left.length != right.length || left.length == 0) return false;
        long gap = start - end;
        long room = capacityFrames - (pendingFloats + 1) / 2;
        if (gap > room || left.length > room - gap || start > Long.MAX_VALUE / 2 - left.length) return false;
        float[] pcm = new float[left.length * 2];
        for (int i = 0; i < left.length; i++) { pcm[2 * i] = left[i]; pcm[2 * i + 1] = right[i]; }
        if (gap > 0) queue.add(new Chunk(new float[(int) gap * 2]));
        queue.add(new Chunk(pcm));
        pendingFloats += (gap + left.length) * 2;
        end = start + left.length;
        return true;
    }

    /** Bounded work per tick. Caller serializes writes and physical flush; Sink must not reenter. */
    Drain drain(long token, Sink sink, BooleanSupplier cancelled, int maxCalls) {
        if (maxCalls < 1) throw new IllegalArgumentException("maxCalls must be positive");
        if (token != generation) return Drain.STALE;
        if (failed) return Drain.ERROR;
        for (int call = 0; call < maxCalls && !queue.isEmpty(); call++) {
            if (cancelled.getAsBoolean()) return Drain.CANCELLED;
            Chunk c = queue.peek();
            int remaining = c.samples.length - c.offset;
            int n = sink.write(c.samples, c.offset, remaining);
            if (n < 0 || n > remaining) { failed = true; return Drain.ERROR; }
            if (n == 0) return Drain.BLOCKED;
            c.offset += n; pendingFloats -= n; writtenFloats += n;
            if (c.offset == c.samples.length) queue.remove();
        }
        return queue.isEmpty() ? Drain.EMPTY : Drain.MORE;
    }

    /** Call on the same queue after stopping/flushing the actual output. Invalidates old tasks. */
    long flushed() {
        generation = Math.incrementExact(generation);
        queue.clear(); end = writtenFloats = pendingFloats = 0; failed = false;
        return generation;
    }

    /** Unsigned playback-head unwrapping. This is NOT a presentation timestamp or latency estimate.
     * Stale/ambiguous readings return null; discontinuity stays invalid until a physical flush.
     */
    static final class Head {
        private long generation, raw, frame, nanos;
        private boolean have, invalid;

        static final class Reading {
            private final long frame;
            private final long monotonicNanos;

            Reading(long frame, long monotonicNanos) {
                this.frame = frame;
                this.monotonicNanos = monotonicNanos;
            }

            long frame() { return frame; }
            long monotonicNanos() { return monotonicNanos; }
        }

        void flushed(long token) { generation = token; have = invalid = false; raw = frame = nanos = 0; }
        Reading read(long token, int unsignedHead, long atNanos, long nowNanos, long writtenFrames) {
            if (token != generation || invalid) return null;
            if (atNanos < 0 || nowNanos < atNanos || nowNanos - atNanos > 250_000_000L) return null;
            long next = Integer.toUnsignedLong(unsignedHead);
            if (!have) {
                if (next > writtenFrames) { invalid = true; return null; }
                raw = frame = next; nanos = atNanos; have = true;
            } else {
                long elapsed = atNanos - nanos;
                long delta = (next - raw) & 0xffffffffL;
                // Half a wrap makes an observation ambiguous, even if the process was suspended.
                if (elapsed < 0 || elapsed > 40_000_000_000_000L
                        || delta > elapsed / 1e9 * 48000 + 4800 || frame > writtenFrames - delta) {
                    invalid = true; return null;
                }
                frame += delta; raw = next; nanos = atNanos;
            }
            return new Reading(frame, nanos);
        }
    }
}
