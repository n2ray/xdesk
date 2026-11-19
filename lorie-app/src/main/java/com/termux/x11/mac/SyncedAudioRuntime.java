/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioRuntime.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name;
 * records are written out (XDesk builds as Java 9), and a device-level mute was added so the decoder
 * and the presentation clock keep their step while nothing is audible.
 * SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/** Single serial-owner audio session. Device operations must be nonblocking except open/reset/close.
 * Device timestamps are presentation timestamps, so output latency is not added a second time.
 * This experimental adapter requires codec output PTS to identify its first 48 kHz PCM frame.
 * Any violated contract fails the audio session; callers keep video independent.
 */
final class SyncedAudioRuntime implements AutoCloseable {
    static final class PCM {
        private final long ptsUs;
        private final ByteBuffer bytes;
        private final SyncedAudioDecodeMapping.Encoding encoding;

        PCM(long ptsUs, ByteBuffer bytes, SyncedAudioDecodeMapping.Encoding encoding) {
            this.ptsUs = ptsUs;
            this.bytes = bytes;
            this.encoding = encoding;
        }

        long ptsUs() { return ptsUs; }
        ByteBuffer bytes() { return bytes; }
        SyncedAudioDecodeMapping.Encoding encoding() { return encoding; }
    }

    static final class Stamp {
        private final long frame;
        private final long nanos;

        Stamp(long frame, long nanos) {
            this.frame = frame;
            this.nanos = nanos;
        }

        long frame() { return frame; }
        long nanos() { return nanos; }
    }

    interface Device extends AutoCloseable {
        void open(byte[] cookie) throws Exception;
        void resetDecoder() throws Exception;
        boolean offer(byte[] packet, long ptsUs) throws Exception;
        PCM poll() throws Exception;
        Stamp timestamp();
        int write(float[] pcm, int offset, int count);
        int bufferFrames();
        default int underruns() { return 0; }
        boolean routeChanged();
        /** The local mute changed: keep decoding and the clock running, make nothing audible. */
        default void muted(boolean on) { }
        @Override void close();
    }

    private final Device device;
    private final java.util.function.LongSupplier monotonic;
    private final SyncedAudioPlayout model = new SyncedAudioPlayout();
    private final SyncedAudioDecodeMapping mapping = new SyncedAudioDecodeMapping();
    private final SyncedAudioScheduleJoin join = new SyncedAudioScheduleJoin(100, .5);
    private final SyncedAudioOutputTimeline timeline = new SyncedAudioOutputTimeline(96000);
    private final ArrayDeque<SyncedAudioDecodeMapping.Input> inputs = new ArrayDeque<>();
    private SyncedAudioPresentationClock clock;
    private SyncedAudioStartupSupply startup;
    private boolean handedOff;
    private double outputLeadFrames;
    private byte[] cookie;
    private int epoch = -1, frames;
    private boolean enabled = true, opened, submitted;
    private boolean muted;
    private double lastPacket;
    private double refresh = 1.0 / 60, streamFPS = 60;

    SyncedAudioRuntime(Device device) { this(device, System::nanoTime); }
    SyncedAudioRuntime(Device device, java.util.function.LongSupplier monotonic) {
        this.device = device; this.monotonic = monotonic; model.away(true);
    }
    static double streamFPS(double reported, int requestedCap) {
        return Double.isFinite(reported) && reported > 0 ? reported : requestedCap > 0 ? requestedCap : 60;
    }
    void format(int epoch, int frames, int prime, byte[] cookie) {
        if (!model.format(epoch, frames, prime, cookie)) return;
        resetPlayback();
        this.epoch = epoch; this.frames = frames; this.cookie = cookie.clone();
    }
    void frame(double stamp, long arrival) { model.frame(stamp, arrival / 1e9); }
    void away(boolean away) { model.away(away); }
    void rtt(Double seconds) { model.rtt(seconds); }
    void timing(double refreshSeconds, double fps) {
        if (!Double.isFinite(refreshSeconds) || refreshSeconds <= 0 || !Double.isFinite(fps) || fps <= 0) return;
        refresh = refreshSeconds; streamFPS = fps;
        updateLatency();
    }
    private void updateLatency() { if (opened) model.latency(0, outputLeadFrames / 48000.0, refresh, streamFPS); }
    String diagnostics() {
        if (!opened) return null;
        return "Audio experimental generation=" + join.generation() + " clock=" + clock.state()
                + " writtenFrames=" + timeline.writtenFrames() + " pendingFloats=" + timeline.pendingFloats()
                + " decoderPending=" + mapping.pendingPackets() + " schedulePending=" + join.pendingSchedules()
                + " startupBudget=" + startup.budget() + " startupAllocated=" + startup.allocated()
                + " handoff=" + handedOff + " writeLeadFrames=" + outputLeadFrames;
    }
    /** The presentation clock's state, STARTING before it can be trusted and FAILED on a broken contract. */
    SyncedAudioPresentationClock.State clockState() { return clock == null ? null : clock.state(); }
    /** Why the presentation clock failed; null before a failure. */
    String clockFailure() { return clock == null ? null : clock.failure(); }
    /** The playout model's last measured placement error, in frames; null before the first placement. */
    Double offsetFrames() { return model.lastError(); }
    void enabled(boolean on) {
        if (enabled == on) return;
        enabled = on; model.mute(!on); resetPlayback();
    }
    /** Local mute: decode and schedule as usual, write nothing audible. */
    void muted(boolean on) {
        if (muted == on) return;
        muted = on;
        if (opened) device.muted(on);
    }
    void packet(SyncedAudioPackets.Batch batch, double stamp, long arrival, long now) throws Exception {
        if (cookie == null || batch.epoch() != epoch) return;
        lastPacket = now / 1e9;
        if (enabled && !opened) {
            device.open(cookie);
            now = monotonic.getAsLong();
            clock = new SyncedAudioPresentationClock(now);
            startup = new SyncedAudioStartupSupply(device.bufferFrames(), now);
            opened = true;
            handedOff = false;
            // Buffer capacity is conservative write-ahead, not a claim of measured hardware IO latency.
            outputLeadFrames = Math.max(240, device.bufferFrames()); updateLatency();
            device.muted(muted);
        }
        List<SyncedAudioDecodeMapping.Packet> expanded = SyncedAudioDecodeMapping.expand(batch, stamp, frames);
        for (SyncedAudioDecodeMapping.Packet packet : expanded) {
            if (opened) decode();
            SyncedAudioPlayout.Plan plan = model.packet(packet.epoch(), packet.sequence(), packet.stamp(), packet.segmentStart(), arrival / 1e9, now / 1e9);
            if (plan.resetFirst) {
                // Resetting an asynchronous codec may abandon the preceding, still scheduled tail.
                // Fail explicitly instead of silently dropping it or mislabelling late output.
                require(inputs.isEmpty() && mapping.pendingPackets() == 0, "codec reset with unresolved PCM");
                if (submitted) device.resetDecoder();
                mapping.flush(); submitted = false;
            }
            if (!plan.output.discards.isEmpty()) {
                SyncedAudioPlayout.Output discards = new SyncedAudioPlayout.Output();
                discards.discards.addAll(plan.output.discards);
                apply(discards, now / 1e9);
                plan.output.discards.clear();
            }
            require(join.admit(join.generation(), plan.id) != SyncedAudioScheduleJoin.State.FAILED, join.failure());
            apply(plan.output, now / 1e9);
            SyncedAudioDecodeMapping.Input input = mapping.queue(packet, plan);
            if (input != null) inputs.add(input);
        }
    }
    void tick(long now) throws Exception {
        if (!opened) return;
        if (device.routeChanged() || now / 1e9 - lastPacket >= 10) { resetPlayback(); return; }
        decode();
        Stamp stamp = device.timestamp();
        now = monotonic.getAsLong();
        require(device.underruns() == 0, "audio underrun during " + clock.state());
        if (stamp != null) clock.observe(clock.generation(), stamp.frame(), stamp.nanos(), now, timeline.writtenFrames());
        require(clock.check(now) != SyncedAudioPresentationClock.State.FAILED,
                "presentation timestamp unavailable or discontinuous: " + clock.failure());
        if (!handedOff) startup.observe(stamp, now, timeline.writtenFrames());
        SyncedAudioPresentationClock.Reading reading = clock.reading();
        if (reading != null && !handedOff) {
            double presentedNow = reading.frame() + (now - reading.nanos()) / 1e9 * 48000;
            // Bootstrap zeros are irrevocably committed. Freeze this generation's writable
            // horizon before the model admits its waiting PCM; never derive it from later music.
            outputLeadFrames = Math.max(outputLeadFrames, timeline.endFrame() - presentedNow);
            handedOff = true; updateLatency();
        }
        if (reading != null) apply(model.played(reading.frame(), reading.nanos() / 1e9, now / 1e9), now / 1e9);
        apply(model.tick(now / 1e9), now / 1e9);
        require(join.pump(join.generation(), timeline, now / 1e9) != SyncedAudioScheduleJoin.State.FAILED, join.failure());
        if (clock.state() == SyncedAudioPresentationClock.State.STARTING) {
            int count = startup.replenish(now);
            if (count > 0) silence(count);
        } else if (!model.holding() && join.pendingSchedules() == 0) {
            // Keep the hardware clock advancing in gaps, but never commit over an editable tail.
            long target = reading.frame() + Math.max(0, Math.round((now - reading.nanos()) / 1e9 * 48000))
                    + Math.max(960, (long) Math.ceil(outputLeadFrames));
            long gap = target - timeline.endFrame();
            // Fill elapsed demand in one bounded allocation, not a fixed number of frames per
            // invocation. A stalled writer must fail rather than accumulate unbounded silence.
            require(gap <= SyncedAudioPresentationClock.STARTUP_FRAMES, "idle audio replenishment exceeds bound");
            if (gap > 0) silence((int) gap);
        }
        SyncedAudioOutputTimeline.Drain result = timeline.drain(timeline.generation(), device::write, () -> false, 8);
        require(result != SyncedAudioOutputTimeline.Drain.ERROR, "AudioTrack write failed");
    }
    private void silence(int frames) {
        require(timeline.enqueue(timeline.generation(), timeline.endFrame(), new float[frames], new float[frames]), "silence capacity");
    }
    private void decode() throws Exception {
        for (int i = 0; i < 16; i++) {
            PCM pcm = device.poll();
            if (pcm == null) break;
            SyncedAudioDecodeMapping.Result result = mapping.output(mapping.generation(), pcm.ptsUs(), pcm.bytes(), 0, pcm.bytes().remaining(), pcm.encoding());
            require(result.failure() == null, "invalid codec PCM/PTS: " + result.failure());
            for (SyncedAudioDecodeMapping.Decoded decoded : result.decoded())
                require(join.decoded(join.generation(), decoded) != SyncedAudioScheduleJoin.State.FAILED, join.failure());
        }
        for (int i = 0; i < 16 && !inputs.isEmpty(); i++) {
            SyncedAudioDecodeMapping.Input input = inputs.peek();
            if (!device.offer(input.packet().data(), input.presentationUs())) break;
            submitted = true; inputs.remove();
        }
    }
    private void apply(SyncedAudioPlayout.Output output, double now) {
        require(join.apply(join.generation(), output, now) != SyncedAudioScheduleJoin.State.FAILED, join.failure());
    }
    private void resetPlayback() {
        device.close(); opened = submitted = false; clock = null; startup = null;
        handedOff = false; outputLeadFrames = 0;
        model.reset(SyncedAudioPlayout.Reset.PLAYBACK); mapping.flush(); join.reset(); timeline.flushed(); inputs.clear();
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    @Override public void close() { resetPlayback(); }
}
