/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioPackets.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name;
 * records and pattern variables are written out (XDesk builds as Java 9). SALL-SYNC.md says which
 * Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Sill kind 43 packet batches, parsed before handing them to an audio decoder. */
final class SyncedAudioPackets {
    static final class Batch {
        private final int epoch;
        private final long sequence;
        private final boolean segmentStart;
        private final List<byte[]> packets;

        Batch(int epoch, long sequence, boolean segmentStart, List<byte[]> packets) {
            this.epoch = epoch;
            this.sequence = sequence;
            this.segmentStart = segmentStart;
            this.packets = packets;
        }

        int epoch() { return epoch; }
        long sequence() { return sequence; }
        boolean segmentStart() { return segmentStart; }
        List<byte[]> packets() { return packets; }
    }

    static boolean needsFlush(boolean submitted, boolean segmentStart, boolean lost, long expected, long sequence) {
        return submitted && (segmentStart || lost || (expected >= 0 && expected != sequence));
    }

    static int primingFrames(boolean segmentStart, int frameCount, int formatPriming) {
        return segmentStart ? formatPriming : frameCount;
    }

    static Batch parse(byte[] bytes) throws IOException {
        if (bytes.length < 9 || bytes.length > 16384 || bytes[0] != 2) throw new IOException("Invalid audio packet header");
        ByteBuffer b = ByteBuffer.wrap(bytes);
        b.get();
        int epoch = Short.toUnsignedInt(b.getShort());
        long sequence = Integer.toUnsignedLong(b.getInt());
        int flags = Byte.toUnsignedInt(b.get());
        int count = Byte.toUnsignedInt(b.get());
        if (count == 0) throw new IOException("Empty audio batch");
        List<byte[]> packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (b.remaining() < 2) throw new IOException("Truncated audio packet length");
            int length = Short.toUnsignedInt(b.getShort());
            if (length > b.remaining()) throw new IOException("Truncated audio packet");
            byte[] packet = new byte[length];
            b.get(packet);
            packets.add(packet);
        }
        if (b.hasRemaining()) throw new IOException("Trailing audio data");
        return new Batch(epoch, sequence, (flags & 1) != 0, packets);
    }
}
