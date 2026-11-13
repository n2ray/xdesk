/*
 * The decoder handling follows Sall (https://github.com/n2ray/sall: MainActivity.createDecoder,
 * VideoDecoderPolicy.java, Hevc.java), the Android client of Sill (https://github.com/Saffsanity/sill).
 * Copyright 2026 Noah Saffer. Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Sill's HEVC stream decoded by the hardware straight into a Surface (the SurfaceView on the external screen):
 * no copy of a picture, no extra composition. Frames come as VideoToolbox writes them, NAL units with a length
 * before each; they are written into the codec's input buffer with start codes instead, one copy from the
 * network buffer. The platform's decoder first; one that fails is dropped and each software HEVC decoder
 * tried once (Sall's VideoDecoderPolicy). Called on the connection's reader thread only, except release().
 */
final class MacVideo {
    private static final String TAG = "XDeskMac";
    private static final byte[] START = {0, 0, 0, 1};

    interface SizeListener {
        void videoSize(int width, int height);
    }

    private final SizeListener sizes;
    private final HashSet<String> failed = new HashSet<>();
    private boolean softwareOnly, unavailable;

    private byte[] config;
    private int lengthSize = 4;
    private MediaCodec codec;
    private Surface codecSurface;
    private boolean needKeyframe = true;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int width, height;

    MacVideo(SizeListener sizes) {
        this.sizes = sizes;
    }

    boolean unavailable() { return unavailable; }

    /** Kind 0: the stream (re)starts with these VPS/SPS/PPS. */
    synchronized void parameters(byte[] payload, int length, Surface surface) throws IOException {
        if (length < 2) throw new IOException("Truncated HEVC parameters");
        int size = payload[0] & 255, count = payload[1] & 255, offset = 2;
        if (size < 1 || size > 4 || count == 0) throw new IOException("Invalid HEVC parameters");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            if (offset + 2 > length) throw new IOException("Truncated parameter length");
            int n = (payload[offset] & 255) << 8 | payload[offset + 1] & 255;
            offset += 2;
            if (n == 0 || n > length - offset) throw new IOException("Truncated parameter");
            out.write(START, 0, 4);
            out.write(payload, offset, n);
            offset += n;
        }
        byte[] next = out.toByteArray();
        lengthSize = size;
        if (codec != null && java.util.Arrays.equals(next, config) && codecSurface == surface) return;
        config = next;
        release();
        needKeyframe = true;
    }

    /**
     * Kind 1. Returns false when the picture waits for a keyframe (the caller asks the host for one). surface
     * null: nobody shows this Mac, the frame is dropped.
     */
    synchronized boolean frame(byte[] payload, int length, boolean keyframe, double timestamp, Surface surface) {
        if (surface == null || !surface.isValid()) {
            release();
            needKeyframe = true;
            return true;
        }
        if (codec != null && codecSurface != surface) {
            release();
            needKeyframe = true;
        }
        if (codec == null) {
            if (config == null || !keyframe || unavailable) return false;
            if (!start(surface)) return false;
        }
        if (needKeyframe && !keyframe) return false;
        try {
            drain();
            int index = codec.dequeueInputBuffer(20_000);
            if (index < 0) {
                needKeyframe = true;
                return false;
            }
            ByteBuffer in = codec.getInputBuffer(index);
            in.clear();
            int offset = 0, written = 0;
            while (offset < length) {
                if (lengthSize > length - offset) throw new IllegalArgumentException("Truncated NAL length");
                int n = 0;
                for (int i = 0; i < lengthSize; i++) n = (n << 8) | (payload[offset++] & 255);
                if (n <= 0 || n > length - offset) throw new IllegalArgumentException("Truncated NAL unit");
                if (4 + n > in.remaining()) throw new IllegalArgumentException("Frame larger than the decoder's buffer");
                in.put(START).put(payload, offset, n);
                offset += n;
                written += 4 + n;
            }
            codec.queueInputBuffer(index, 0, written, (long) (timestamp * 1_000_000), 0);
            needKeyframe = false;
            drain();
            return true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "video decoder failed: " + codecName(), e);
            fail(codecName());
            return false;
        }
    }

    /** Pictures the decoder has finished go to the screen at once: the stream is live, there is nothing to pace. */
    synchronized void drain() {
        if (codec == null) return;
        try {
            while (true) {
                int out = codec.dequeueOutputBuffer(info, 0);
                if (out >= 0) {
                    codec.releaseOutputBuffer(out, true);
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                    if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    if (w != width || h != height) {
                        width = w;
                        height = h;
                        sizes.videoSize(w, h);
                    }
                } else {
                    return;
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "video drain", e);
            fail(codecName());
        }
    }

    private String codecName() {
        try {
            return codec != null ? codec.getName() : "";
        } catch (IllegalStateException e) {
            return "";
        }
    }

    private void fail(String name) {
        release();
        needKeyframe = true;
        softwareOnly = true;
        if (name != null && !name.isEmpty()) failed.add(name);
    }

    private boolean start(Surface surface) {
        while (true) {
            String requested = next();
            if (requested == null) {
                unavailable = true;
                Log.w(TAG, "no HEVC decoder works");
                return false;
            }
            MediaCodec made = null;
            try {
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                // Frames can be a Retina screen's worth: don't let a small default buffer refuse a keyframe
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20);
                made = requested.isEmpty() ? MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                        : MediaCodec.createByCodecName(requested);
                made.configure(format, surface, null, 0);
                made.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                made.start();
                codec = made;
                codecSurface = surface;
                needKeyframe = true;
                Log.i(TAG, "video decoder: " + made.getName());
                return true;
            } catch (IOException | IllegalStateException | IllegalArgumentException e) {
                String name = requested;
                if (made != null) {
                    try {
                        name = made.getName();
                    } catch (RuntimeException ignored) { }
                    try {
                        made.release();
                    } catch (RuntimeException ignored) { }
                }
                Log.w(TAG, "video decoder could not start: " + name, e);
                softwareOnly = true;
                failed.add(requested);
                failed.add(name);
            }
        }
    }

    /** "" the platform's choice, a name, or null when none is left. */
    private String next() {
        if (!softwareOnly) return "";
        for (String name : softwareDecoders()) if (!failed.contains(name)) return name;
        return null;
    }

    private static List<String> softwareDecoders() {
        List<String> result = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || info.isAlias()) continue;
                String name = info.getName();
                if (name.endsWith(".secure") || !(info.isSoftwareOnly() || name.startsWith("c2.android.") || name.startsWith("OMX.google."))) continue;
                for (String type : info.getSupportedTypes()) {
                    if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type)) {
                        if (!result.contains(name)) result.add(name);
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot list HEVC decoders", e);
        }
        return result;
    }

    synchronized void release() {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (RuntimeException ignored) { }
        try {
            codec.release();
        } catch (RuntimeException ignored) { }
        codec = null;
        codecSurface = null;
    }

    /** The parameter sets with start codes, as the last kind 0 gave them; null before. */
    synchronized byte[] config() {
        return config;
    }

    /**
     * Kind 1's NAL units with start codes instead of lengths, into out (grown when too small): for a passthrough.
     * Returns the new buffer and its length in len[0], or null when the frame is malformed.
     */
    synchronized byte[] annexB(byte[] payload, int length, byte[] out, int[] len) {
        int need = length + length / 2 + 64;
        if (out == null || out.length < need) out = new byte[need];
        int offset = 0, written = 0;
        while (offset < length) {
            if (lengthSize > length - offset) return null;
            int n = 0;
            for (int i = 0; i < lengthSize; i++) n = (n << 8) | (payload[offset++] & 255);
            if (n <= 0 || n > length - offset) return null;
            if (written + 4 + n > out.length) out = java.util.Arrays.copyOf(out, (written + 4 + n) * 2);
            System.arraycopy(START, 0, out, written, 4);
            System.arraycopy(payload, offset, out, written + 4, n);
            offset += n;
            written += 4 + n;
        }
        len[0] = written;
        return out;
    }

    /**
     * The picture's size from the SPS in a kind 0 payload (pic_width/height_in_luma_samples less the conformance
     * window, H.265 §7.3.2.2), or null. A passthrough has no decoder to say it.
     */
    static int[] spsSize(byte[] payload, int length) {
        if (length < 2) return null;
        int count = payload[1] & 255, offset = 2;
        for (int i = 0; i < count && offset + 2 <= length; i++) {
            int n = (payload[offset] & 255) << 8 | payload[offset + 1] & 255;
            offset += 2;
            if (n < 3 || n > length - offset) return null;
            if (((payload[offset] >> 1) & 0x3f) == 33) {
                try {
                    return parseSps(payload, offset + 2, n - 2);
                } catch (RuntimeException e) {
                    return null;
                }
            }
            offset += n;
        }
        return null;
    }

    private static int[] parseSps(byte[] b, int off, int len) {
        // Emulation prevention bytes (00 00 03) out first
        byte[] r = new byte[len];
        int n = 0, zeros = 0;
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 255;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            r[n++] = (byte) v;
        }
        Bits s = new Bits(r, n);
        s.skip(4);
        int maxSub = s.bits(3);
        s.skip(1);
        // profile_tier_level: general profile (88 bits) and level (8)
        s.skip(96);
        boolean[] profile = new boolean[8], level = new boolean[8];
        for (int i = 0; i < maxSub; i++) {
            profile[i] = s.bits(1) == 1;
            level[i] = s.bits(1) == 1;
        }
        if (maxSub > 0) for (int i = maxSub; i < 8; i++) s.skip(2);
        for (int i = 0; i < maxSub; i++) {
            if (profile[i]) s.skip(88);
            if (level[i]) s.skip(8);
        }
        s.ue();
        int chroma = s.ue();
        if (chroma == 3) s.skip(1);
        int w = s.ue(), h = s.ue();
        if (s.bits(1) == 1) {
            int subW = chroma == 1 || chroma == 2 ? 2 : 1, subH = chroma == 1 ? 2 : 1;
            int left = s.ue(), right = s.ue(), top = s.ue(), bottom = s.ue();
            w -= subW * (left + right);
            h -= subH * (top + bottom);
        }
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null;
        return new int[] {w, h};
    }

    private static final class Bits {
        private final byte[] b;
        private final int size;
        private int pos;

        Bits(byte[] b, int size) {
            this.b = b;
            this.size = size * 8;
        }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= size) throw new IllegalArgumentException("SPS too short");
                v = (v << 1) | ((b[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        void skip(int n) {
            pos += n;
            if (pos > size) throw new IllegalArgumentException("SPS too short");
        }

        int ue() {
            int zeros = 0;
            while (bits(1) == 0) if (++zeros > 31) throw new IllegalArgumentException("bad ue");
            return zeros == 0 ? 0 : (1 << zeros) - 1 + bits(zeros);
        }
    }

    /** A new connection: the stream starts over, but decoders that failed stay dropped. */
    synchronized void reset() {
        release();
        config = null;
        needKeyframe = true;
        width = height = 0;
    }
}
