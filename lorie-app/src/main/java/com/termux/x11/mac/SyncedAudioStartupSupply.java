/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioStartupSupply.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name
 * (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

/** Serial-owner, one-generation silent acquisition allowance. It never grants clock trust. */
final class SyncedAudioStartupSupply {
    static final int MAX_FRAMES = 48000;
    private final int bufferFrames;
    private final long started;
    private long priorFrame = -1, priorStamp;
    private int budget, allocated;
    private boolean measured;

    SyncedAudioStartupSupply(int bufferFrames, long started) {
        if (bufferFrames < 1 || bufferFrames >= MAX_FRAMES) throw new IllegalArgumentException("Unsupported audio buffer size");
        this.bufferFrames = bufferFrames; this.started = started;
        budget = Math.min(MAX_FRAMES, bufferFrames + 12000);
    }
    int budget() { return budget; }
    int allocated() { return allocated; }
    void observe(SyncedAudioRuntime.Stamp stamp, long now, long writtenFrames) {
        if (measured || stamp == null || now < started || now - started >= SyncedAudioPresentationClock.STARTUP_TIMEOUT_NS
                || stamp.frame() < 0 || stamp.frame() > writtenFrames || stamp.nanos() < started
                || stamp.nanos() > now || now - stamp.nanos() > SyncedAudioPresentationClock.STALE_NS) return;
        if (priorFrame >= 0 && stamp.frame() > priorFrame && stamp.nanos() > priorStamp
                && Math.abs((stamp.frame() - priorFrame) - (stamp.nanos() - priorStamp) / 1e9 * 48000)
                <= SyncedAudioPresentationClock.CHUNK_FRAMES) {
            long allowanceNanos = now - started + SyncedAudioPresentationClock.STABLE_SPAN_NS + 40_000_000L;
            long frames = (allowanceNanos * 48000 + 999_999_999L) / 1_000_000_000L;
            budget = (int) Math.min(MAX_FRAMES, Math.max(budget, bufferFrames + frames));
            measured = true;
        }
        priorFrame = stamp.frame(); priorStamp = stamp.nanos();
    }
    int replenish(long now) {
        if (now < started || now - started >= SyncedAudioPresentationClock.STARTUP_TIMEOUT_NS)
            throw new IllegalStateException("Startup supply deadline");
        long target = (now - started) * 48000 / 1_000_000_000L + Math.max(960, bufferFrames);
        int frames = (int) Math.max(0, Math.min(budget, target) - allocated);
        allocated += frames;
        return frames;
    }
}
