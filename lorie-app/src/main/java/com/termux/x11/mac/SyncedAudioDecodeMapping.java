/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioDecodeMapping.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name;
 * records are written out and Java 10's List.copyOf is replaced (XDesk builds as Java 9).
 * SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Packet/PCM identity bridge for a future asynchronous decoder adapter; not an audio player.
 * Output PTS must identify the first PCM frame, in the same 48 kHz sample clock as queued input.
 * A callback must carry the generation captured by its decoder session, never the current one.
 * Every reset/flush must also flush the real decoder before queuing input in the new generation.
 */
final class SyncedAudioDecodeMapping {
    static final int RATE = 48000;
    enum Encoding {
        S16_LE(4), FLOAT32_LE(8);
        final int bytesPerFrame;
        Encoding(int bytesPerFrame) { this.bytesPerFrame = bytesPerFrame; }
    }
    static final class Packet {
        private final int epoch;
        private final long sequence;
        private final double stamp;
        private final boolean segmentStart;
        private final int frames;
        private final byte[] data;

        Packet(int epoch, long sequence, double stamp, boolean segmentStart, int frames, byte[] data) {
            if (epoch < 0 || epoch > 65535 || sequence < 0 || sequence > 0xffffffffL
                    || !Double.isFinite(stamp) || frames < 1 || frames > 8192 || data == null)
                throw new IllegalArgumentException("Invalid packet metadata");
            this.epoch = epoch;
            this.sequence = sequence;
            this.stamp = stamp;
            this.segmentStart = segmentStart;
            this.frames = frames;
            this.data = data.clone();
        }

        int epoch() { return epoch; }
        long sequence() { return sequence; }
        double stamp() { return stamp; }
        boolean segmentStart() { return segmentStart; }
        int frames() { return frames; }
        byte[] data() { return data.clone(); }
    }
    static final class Input {
        private final long generation;
        private final long presentationUs;
        private final Packet packet;

        Input(long generation, long presentationUs, Packet packet) {
            this.generation = generation;
            this.presentationUs = presentationUs;
            this.packet = packet;
        }

        long generation() { return generation; }
        long presentationUs() { return presentationUs; }
        Packet packet() { return packet; }
    }
    /** Exactly one decoded packet, with the model's priming/join frames removed once. */
    static final class Decoded {
        private final Packet packet;
        private final Integer id;
        private final double keptStamp;
        private final float[] left, right;

        Decoded(Packet packet, Integer id, double keptStamp, float[] left, float[] right) {
            this.packet = packet;
            this.id = id;
            this.keptStamp = keptStamp;
            this.left = left;
            this.right = right;
        }

        Packet packet() { return packet; }
        Integer id() { return id; }
        double keptStamp() { return keptStamp; }
        float[] left() { return left; }
        float[] right() { return right; }
    }
    enum Failure { MALFORMED_PCM, UNKNOWN_PTS, OVERLAP, INCOMPLETE }
    static final class Result {
        private final boolean stale;
        private final List<Decoded> decoded;
        private final List<Integer> discardedIds;
        private final Failure failure;

        Result(boolean stale, List<Decoded> decoded, List<Integer> discardedIds, Failure failure) {
            this.stale = stale;
            this.decoded = decoded;
            this.discardedIds = discardedIds;
            this.failure = failure;
        }

        boolean stale() { return stale; }
        List<Decoded> decoded() { return decoded; }
        List<Integer> discardedIds() { return discardedIds; }
        Failure failure() { return failure; }
    }
    private static final class Pending {
        final Packet packet;
        final Integer id;
        final int drop;
        final long start;
        final float[] left, right;
        final BitSet received = new BitSet();
        Pending(Packet packet, SyncedAudioPlayout.Plan plan, long start) {
            this.packet = packet; id = plan.id; drop = plan.dropFront; this.start = start;
            left = new float[packet.frames()]; right = new float[packet.frames()];
        }
        long end() { return start + packet.frames(); }
    }
    private static final class Span {
        final Pending pending;
        final int offset, source, frames;

        Span(Pending pending, int offset, int source, int frames) {
            this.pending = pending;
            this.offset = offset;
            this.source = source;
            this.frames = frames;
        }
    }
    private final TreeMap<Long, Pending> pending = new TreeMap<>();
    private final int limit;
    private long generation = 1, nextFrame;

    SyncedAudioDecodeMapping() { this(100); }
    SyncedAudioDecodeMapping(int maxPendingPackets) {
        if (maxPendingPackets < 1 || maxPendingPackets > 1000) throw new IllegalArgumentException("Invalid queue bound");
        limit = maxPendingPackets;
    }
    static List<Packet> expand(SyncedAudioPackets.Batch batch, double headerStamp, int framesPerPacket) {
        if (!Double.isFinite(headerStamp) || batch == null || batch.packets() == null || batch.packets().isEmpty())
            throw new IllegalArgumentException("Invalid batch");
        List<Packet> packets = new ArrayList<>();
        for (int i = 0; i < batch.packets().size(); i++) {
            packets.add(new Packet(batch.epoch(), (batch.sequence() + i) & 0xffffffffL,
                    headerStamp + (double) i * framesPerPacket / RATE,
                    batch.segmentStart() && i == 0, framesPerPacket, batch.packets().get(i)));
        }
        return Collections.unmodifiableList(packets);
    }
    synchronized long generation() { return generation; }
    synchronized int pendingPackets() { return pending.size(); }

    /** Queue only accepted decode plans. False-decode plans never consume PTS or queue space.
     * The owner must flush on resetFirst before submitting a plan from a new decoder segment.
     * Overflow is explicit: the owner can drain, or flush and tell the playout model of the loss.
     */
    synchronized Input queue(Packet packet, SyncedAudioPlayout.Plan plan) {
        if (!plan.decode) return null;
        if (plan.dropFront < 0 || plan.dropFront > packet.frames()
                || (plan.id == null) != (plan.dropFront == packet.frames()))
            throw new IllegalArgumentException("Plan and PCM frame count disagree");
        if (plan.resetFirst && nextFrame != 0) throw new IllegalStateException("Decoder reset requires flush boundary");
        if (pending.size() >= limit) throw new IllegalStateException("Decoder mapping queue full");
        if (plan.id != null) for (Pending p : pending.values()) {
            if (plan.id.equals(p.id)) throw new IllegalArgumentException("Duplicate kept packet id");
        }
        long end = Math.addExact(nextFrame, packet.frames());
        long pts = frameToUs(nextFrame);
        pending.put(nextFrame, new Pending(packet, plan, nextFrame));
        nextFrame = end;
        return new Input(generation, pts, packet);
    }

    /** Consume a PCM callback without changing the buffer's position/limit. Offsets are absolute,
     * as in BufferInfo. Validate the whole callback before accepting any of its samples.
     * Split, merged and reordered nonoverlapping chunks are all supported.
     */
    synchronized Result output(long callbackGeneration, long presentationUs, ByteBuffer buffer,
                               int offset, int size, Encoding encoding) {
        if (callbackGeneration != generation) return empty(true);
        if (buffer == null || encoding == null || offset < 0 || size < 0
                || offset > buffer.limit() || size > buffer.limit() - offset
                || size % encoding.bytesPerFrame != 0 || presentationUs < 0)
            return fail(Failure.MALFORMED_PCM);
        if (size == 0) return empty(false);
        long first;
        try { first = usToFrame(presentationUs); }
        catch (ArithmeticException e) { return fail(Failure.UNKNOWN_PTS); }
        int frames = size / encoding.bytesPerFrame;
        List<Span> spans = new ArrayList<>();
        long cursor = first;
        int source = 0;
        while (source < frames) {
            Map.Entry<Long, Pending> entry = pending.floorEntry(cursor);
            if (entry == null || cursor >= entry.getValue().end()) return fail(Failure.UNKNOWN_PTS);
            Pending p = entry.getValue();
            int at = (int) (cursor - p.start);
            int take = Math.min(frames - source, p.packet.frames() - at);
            int overlap = p.received.nextSetBit(at);
            if (overlap >= 0 && overlap < at + take) return fail(Failure.OVERLAP);
            spans.add(new Span(p, at, source, take));
            cursor += take; source += take;
        }
        ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        // Reject invalid float output before committing even the first covered packet.
        if (encoding == Encoding.FLOAT32_LE) {
            for (int at = offset; at < offset + size; at += 4) {
                if (!Float.isFinite(pcm.getFloat(at))) return fail(Failure.MALFORMED_PCM);
            }
        }
        List<Decoded> done = new ArrayList<>();
        for (Span span : spans) {
            Pending p = span.pending;
            for (int k = 0; k < span.frames; k++) {
                int at = offset + (span.source + k) * encoding.bytesPerFrame;
                p.left[span.offset + k] = encoding == Encoding.S16_LE ? pcm.getShort(at) / 32768f : pcm.getFloat(at);
                p.right[span.offset + k] = encoding == Encoding.S16_LE ? pcm.getShort(at + 2) / 32768f : pcm.getFloat(at + 4);
            }
            p.received.set(span.offset, span.offset + span.frames);
            if (p.received.cardinality() == p.packet.frames()) {
                pending.remove(p.start);
                done.add(new Decoded(p.packet, p.id, p.packet.stamp() + (double) p.drop / RATE,
                        Arrays.copyOfRange(p.left, p.drop, p.left.length), Arrays.copyOfRange(p.right, p.drop, p.right.length)));
            }
        }
        return new Result(false, Collections.unmodifiableList(done), Collections.<Integer>emptyList(), null);
    }
    /** Call only when the decoder has explicitly drained, not on a transient empty output queue. */
    synchronized Result drained(long callbackGeneration) {
        if (callbackGeneration != generation) return empty(true);
        return pending.isEmpty() ? empty(false) : fail(Failure.INCOMPLETE);
    }
    /** Invalidate queued callbacks and report every kept ID whose PCM is abandoned. */
    synchronized Result flush() {
        List<Integer> discarded = new ArrayList<>();
        for (Pending p : pending.values()) if (p.id != null) discarded.add(p.id);
        long nextGeneration = Math.incrementExact(generation);
        pending.clear(); nextFrame = 0; generation = nextGeneration;
        return new Result(false, Collections.<Decoded>emptyList(), Collections.unmodifiableList(discarded), null);
    }
    private Result fail(Failure cause) {
        Result reset = flush();
        return new Result(false, Collections.<Decoded>emptyList(), reset.discardedIds(), cause);
    }
    private static Result empty(boolean stale) {
        return new Result(stale, Collections.<Decoded>emptyList(), Collections.<Integer>emptyList(), null);
    }
    /** Rational conversion avoids the cumulative truncation of F * 1_000_000 / 48_000 per packet. */
    static long frameToUs(long frame) {
        if (frame < 0) throw new IllegalArgumentException("Negative frame");
        return Math.addExact(Math.multiplyExact(frame / RATE, 1_000_000L),
                (frame % RATE * 1_000_000L + RATE / 2) / RATE);
    }
    static long usToFrame(long us) {
        if (us < 0) throw new IllegalArgumentException("Negative PTS");
        return Math.addExact(Math.multiplyExact(us / 1_000_000, RATE),
                (us % 1_000_000 * RATE + 500_000) / 1_000_000);
    }
}
