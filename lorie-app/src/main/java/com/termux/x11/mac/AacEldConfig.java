/*
 * From Sall (https://github.com/n2ray/sall, AacEldConfig.java), the Android client of Sill. Licensed under the
 * Apache License, Version 2.0. For XDesk: the package, and classes in place of records (XDesk builds as Java 9).
 */
package com.termux.x11.mac;

import java.util.Arrays;

/** Validates Sill's 48 kHz stereo AAC-ELD configuration, accepting bare ASC or an ES descriptor. */
final class AacEldConfig {
    static final class Config {
        private final byte[] asc;
        private final int frames;
        Config(byte[] asc, int frames) { this.asc = asc.clone(); this.frames = frames; }
        byte[] asc() { return asc.clone(); }
        int frames() { return frames; }
    }
    static Config parse(byte[] cookie) {
        if (cookie == null || cookie.length == 0 || cookie.length > 1024)
            throw new IllegalArgumentException("Invalid AAC cookie length");
        // Recognized ES descriptors never fall back to interpreting malformed containers as ASC.
        byte[] asc = (cookie[0] & 255) == 3 ? extract(cookie) : cookie.clone();
        Bits bits = new Bits(asc);
        int object = bits.read(5);
        if (object == 31) object = 32 + bits.read(6);
        if (object != 39) throw new IllegalArgumentException("Not AAC-ELD");
        int frequency = bits.read(4);
        if (frequency == 15) {
            if (bits.read(24) != 48000) throw new IllegalArgumentException("Not 48 kHz AAC");
        } else if (frequency != 3) throw new IllegalArgumentException("Not 48 kHz AAC");
        if (bits.read(4) != 2) throw new IllegalArgumentException("Not stereo AAC");
        int frames = bits.read(1) == 1 ? 480 : 512;
        // Sill's encoder uses no resilience, SBR, ELD extensions or error-protection config.
        // Reject unsupported syntax rather than scanning for a plausible nested ASC signature.
        if (bits.read(4) != 0 || bits.read(4) != 0 || bits.read(2) != 0)
            throw new IllegalArgumentException("Unsupported AAC-ELD features");
        if (bits.remaining() > 7 || bits.read(bits.remaining()) != 0)
            throw new IllegalArgumentException("Trailing AAC configuration data");
        return new Config(asc, frames);
    }
    private static final class Bits {
        final byte[] data;
        int offset;
        Bits(byte[] data) { this.data = data; }
        int remaining() { return data.length * 8 - offset; }
        int read(int count) {
            if (count < 0 || count > 24 || count > remaining()) throw new IllegalArgumentException("Truncated AAC configuration");
            int value = 0;
            for (int i = 0; i < count; i++, offset++) value = (value << 1) | ((data[offset / 8] >> (7 - offset % 8)) & 1);
            return value;
        }
    }
    private static final class Descriptor {
        final int tag, start, end;
        Descriptor(int tag, int start, int end) { this.tag = tag; this.start = start; this.end = end; }
    }

    private static byte[] extract(byte[] esds) {
        if (esds == null || esds.length < 5 || esds.length > 1024) throw new IllegalArgumentException("Invalid ESDS length");
        Descriptor es = descriptor(esds, 0, esds.length);
        if (es.tag != 3 || es.end != esds.length || es.end - es.start < 3) throw new IllegalArgumentException("Invalid ES descriptor");
        int flags = esds[es.start + 2] & 255, offset = es.start + 3;
        if ((flags & 0x80) != 0) offset = advance(offset, 2, es.end);
        if ((flags & 0x40) != 0) {
            int lenAt = offset;
            offset = advance(offset, 1, es.end);
            offset = advance(offset, esds[lenAt] & 255, es.end);
        }
        if ((flags & 0x20) != 0) offset = advance(offset, 2, es.end);

        byte[] result = null;
        boolean foundConfig = false, foundSl = false;
        while (offset < es.end) {
            Descriptor child = descriptor(esds, offset, es.end);
            if (child.tag == 4) {
                if (foundConfig || child.end - child.start < 13) throw new IllegalArgumentException("Invalid decoder config");
                foundConfig = true;
                if ((esds[child.start] & 255) != 0x40 || ((esds[child.start + 1] & 255) >> 2) != 5)
                    throw new IllegalArgumentException("Not MPEG-4 audio");
                int inner = child.start + 13;
                while (inner < child.end) {
                    Descriptor part = descriptor(esds, inner, child.end);
                    if (part.tag == 5) {
                        int length = part.end - part.start;
                        if (result != null || length < 2 || length > 64) throw new IllegalArgumentException("Invalid decoder-specific info");
                        result = Arrays.copyOfRange(esds, part.start, part.end);
                    } else throw new IllegalArgumentException("Unsupported decoder descriptor");
                    inner = part.end;
                }
            } else if (child.tag == 6) {
                if (foundSl || child.end - child.start != 1 || esds[child.start] != 2)
                    throw new IllegalArgumentException("Invalid SL configuration");
                foundSl = true;
            } else throw new IllegalArgumentException("Unsupported ES descriptor");
            offset = child.end;
        }
        if (result == null || !foundSl) throw new IllegalArgumentException("Missing decoder-specific info");
        return result;
    }

    private static int advance(int offset, int count, int end) {
        if (count < 0 || offset > end - count) throw new IllegalArgumentException("Truncated descriptor");
        return offset + count;
    }

    private static Descriptor descriptor(byte[] bytes, int offset, int limit) {
        if (offset >= limit) throw new IllegalArgumentException("Missing descriptor");
        int tag = bytes[offset++] & 255, length = 0;
        boolean complete = false;
        for (int i = 0; i < 4; i++) {
            if (offset >= limit) throw new IllegalArgumentException("Truncated descriptor length");
            int octet = bytes[offset++] & 255;
            length = (length << 7) | (octet & 0x7f);
            if ((octet & 0x80) == 0) { complete = true; break; }
        }
        if (!complete || length > limit - offset) throw new IllegalArgumentException("Invalid descriptor length");
        return new Descriptor(tag, offset, offset + length);
    }
}
