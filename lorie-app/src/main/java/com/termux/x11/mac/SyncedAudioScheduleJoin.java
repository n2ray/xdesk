/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioScheduleJoin.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name;
 * records are written out (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this follows
 * and how to compare later.
 */
package com.termux.x11.mac;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Serial-owner rendezvous for asynchronous PCM and model decisions. No Android/device clock here.
 * Register an admitted plan before applying its output; decoder flush alone must not reset this
 * bridge, since the preceding packet's tail can still be scheduled after the decoder resets.
 * A physical output reset invalidates the whole bridge and its timeline together.
 */
final class SyncedAudioScheduleJoin {
    enum State { READY, WAITING, STALE, FAILED }
    private static final class Kept {
        float[] left, right;
    }
    private static final class Waiting {
        final SyncedAudioPlayout.Schedule schedule;
        final double since;

        Waiting(SyncedAudioPlayout.Schedule schedule, double since) {
            this.schedule = schedule;
            this.since = since;
        }
    }
    private final Map<Integer, Kept> kept = new HashMap<>();
    private final ArrayDeque<Waiting> schedules = new ArrayDeque<>();
    private final int capacity;
    private final double timeout;
    private long generation;
    private String failure;

    SyncedAudioScheduleJoin(int capacity, double timeoutSeconds) {
        if (capacity < 1 || capacity > 1000 || !Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0)
            throw new IllegalArgumentException();
        this.capacity = capacity; timeout = timeoutSeconds;
    }
    long generation() { return generation; }
    int retainedPackets() { return kept.size(); }
    int pendingSchedules() { return schedules.size(); }
    String failure() { return failure; }

    State admit(long token, Integer id) {
        if (token != generation) return State.STALE;
        if (failure != null) return State.FAILED;
        if (id == null) return State.READY;
        if (kept.containsKey(id)) return fail("duplicate admitted id");
        if (kept.size() >= capacity) return fail("PCM retention capacity");
        kept.put(id, new Kept());
        return State.READY;
    }
    State decoded(long token, SyncedAudioDecodeMapping.Decoded decoded) {
        if (token != generation) return State.STALE;
        if (failure != null) return State.FAILED;
        Kept pcm = kept.get(decoded.id());
        // A discard may have overtaken a decoder callback; never resurrect it.
        if (pcm == null) return State.READY;
        if (pcm.left != null) return fail("duplicate decoded id");
        if (decoded.left().length != decoded.right().length || decoded.left().length == 0)
            return fail("invalid PCM shape");
        pcm.left = decoded.left().clone(); pcm.right = decoded.right().clone();
        return State.READY;
    }
    State apply(long token, SyncedAudioPlayout.Output output, double now) {
        if (token != generation) return State.STALE;
        if (failure != null) return State.FAILED;
        if (!Double.isFinite(now)) return fail("invalid monotonic time");
        for (int id : output.discards) {
            kept.remove(id);
            schedules.removeIf(w -> w.schedule.id() == id);
        }
        for (SyncedAudioPlayout.Schedule schedule : output.schedules) {
            if (!kept.containsKey(schedule.id())) return fail("schedule without admission");
            if (schedules.size() >= capacity * 2) return fail("schedule capacity");
            schedules.add(new Waiting(schedule, now));
        }
        return schedules.isEmpty() ? State.READY : State.WAITING;
    }
    State pump(long token, SyncedAudioOutputTimeline timeline, double now) {
        if (token != generation) return State.STALE;
        if (failure != null) return State.FAILED;
        if (!Double.isFinite(now)) return fail("invalid monotonic time");
        while (!schedules.isEmpty()) {
            Waiting waiting = schedules.peek();
            SyncedAudioPlayout.Schedule s = waiting.schedule;
            Kept pcm = kept.get(s.id());
            if (pcm == null) return fail("schedule lost PCM ownership");
            if (pcm.left == null) {
                if (now - waiting.since >= timeout) return fail("decoder rendezvous timeout");
                return State.WAITING;
            }
            if (s.offset() < 0 || s.count() <= 0 || s.offset() > pcm.left.length - s.count()
                    || !Double.isFinite(s.start()) || s.start() < 0 || s.start() > Long.MAX_VALUE / 2.0)
                return fail("invalid schedule range");
            float[] left = Arrays.copyOfRange(pcm.left, s.offset(), s.offset() + s.count());
            float[] right = Arrays.copyOfRange(pcm.right, s.offset(), s.offset() + s.count());
            if (s.change() != 0) {
                float[][] changed = SyncedAudioPlayout.change(left, right, s.change());
                left = changed[0]; right = changed[1];
            }
            if (left.length != s.frames()) return fail("rendered frame count mismatch");
            if (s.fadeIn()) { SyncedAudioPlayout.fade(left, true, SyncedAudioPlayout.FADE_FRAMES); SyncedAudioPlayout.fade(right, true, SyncedAudioPlayout.FADE_FRAMES); }
            if (s.fadeOut()) { SyncedAudioPlayout.fade(left, false, SyncedAudioPlayout.FADE_FRAMES); SyncedAudioPlayout.fade(right, false, SyncedAudioPlayout.FADE_FRAMES); }
            if (!timeline.enqueue(timeline.generation(), Math.round(s.start()), left, right))
                return fail("output timeline rejected schedule");
            schedules.remove();
            if (s.part() == SyncedAudioPlayout.Part.TAIL) kept.remove(s.id());
        }
        return State.READY;
    }
    long reset() {
        generation = Math.incrementExact(generation);
        kept.clear(); schedules.clear(); failure = null;
        return generation;
    }
    private State fail(String reason) { failure = reason; return State.FAILED; }
}
