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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sill's HEVC stream decoded by the hardware straight into a Surface (the SurfaceView on the external screen):
 * no copy of a picture, no extra composition. Frames come as VideoToolbox writes them, NAL units with a length
 * before each; they are written into the codec's input buffer with start codes instead, one copy from the
 * network buffer. The platform's decoder first; one that fails is dropped and each software HEVC decoder
 * tried once (Sall's VideoDecoderPolicy).
 *
 * Asynchronous: the codec's callbacks run on their own high-priority thread, so a decoded picture goes to the
 * surface as soon as the decoder has it instead of on the next frame or tick. The reader thread takes free input
 * buffers from a queue without waiting; every codec call is serialized with the callbacks, so a released codec is
 * never touched. Called on the connection's reader thread only, except release() (a screen going away).
 */
final class MacVideo {
    private static final String TAG = "XDeskMac";
    private static final byte[] START = {0, 0, 0, 1};
    /** How long a fresh decoder gets to offer its first input buffers; a frame needs one at once. */
    private static final int FIRST_BUFFER_MS = 20;
    /** Render timing: one log line a minute, and how old an unrendered frame is before it is counted as lost. */
    private static final long STATS_NS = 60_000_000_000L, LOST_NS = 2_000_000_000L;

    interface SizeListener {
        void videoSize(int width, int height);
    }

    private final SizeListener sizes;
    private final HashSet<String> failed = new HashSet<>();
    private boolean softwareOnly, unavailable;

    private byte[] config;
    private int lengthSize = 4;
    /** The picture's size from the SPS in the last kind 0; 0 when it could not be read. */
    private int spsWidth, spsHeight;
    private MediaCodec codec;
    private Surface codecSurface;
    private boolean needKeyframe = true;
    /** Room asked of a decoder for one frame: 4 MB, a Retina screen's keyframe, more after a bigger one came. */
    private int inputSize = 4 << 20;
    private static final int MAX_INPUT = 32 << 20;
    private int width, height;

    private HandlerThread thread;
    private Handler handler;
    /** Input buffer indices the decoder offered and the reader has not filled yet. */
    private final ArrayDeque<Integer> freeInput = new ArrayDeque<>();
    /** When each queued frame arrived, by its presentation time, for the render timing. */
    private final HashMap<Long, Long> arrivals = new HashMap<>();
    private final ArrayList<Long> renderSamples = new ArrayList<>();
    private long statsAt;

    MacVideo(SizeListener sizes) {
        this.sizes = sizes;
    }

    boolean unavailable() { return unavailable; }

    /** Kind 0: the stream (re)starts with these VPS/SPS/PPS. */
    synchronized void parameters(byte[] payload, int length, Surface surface) throws IOException {
        if (length < 2) throw new IOException("Truncated HEVC parameters");
        int lengthBytes = payload[0] & 255, count = payload[1] & 255, offset = 2;
        if (lengthBytes < 1 || lengthBytes > 4 || count == 0) throw new IOException("Invalid HEVC parameters");
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
        int[] sps = spsSize(payload, length);
        lengthSize = lengthBytes;
        if (codec != null && Arrays.equals(next, config) && codecSurface == surface) {
            spsWidth = sps != null ? sps[0] : 0;
            spsHeight = sps != null ? sps[1] : 0;
            return;
        }
        config = next;
        // Configure at the picture's own size (H.265 SPS): the decoder is not told 1920×1080 and left to fix it
        spsWidth = sps != null ? sps[0] : 0;
        spsHeight = sps != null ? sps[1] : 0;
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
            // A callback's error let the decoder go: the next keyframe starts the next one
            if (codec == null) return false;
            int needed = annexBSize(payload, length);
            if (needed < 0) {
                // A broken frame is the stream's fault, not the decoder's: drop it, start again from a keyframe
                needKeyframe = true;
                return false;
            }
            if (needed > MAX_INPUT) {
                Log.w(TAG, "video frame of " + needed + " bytes is too big, dropped");
                needKeyframe = true;
                return false;
            }
            if (needed > inputSize) {
                // A frame bigger than the decoder was given room for is not the decoder's fault: start this one again
                // with room for it, from the next keyframe, rather than falling back to software
                inputSize = Math.min(MAX_INPUT, Integer.highestOneBit(needed - 1) << 1);
                Log.i(TAG, "video frame of " + needed + " bytes: decoder input now " + inputSize);
                release();
                needKeyframe = true;
                return false;
            }
            // The callback thread offered these; taking one never waits. None: as the old dequeue timeout, a
            // keyframe starts the picture again
            Integer index = freeInput.poll();
            if (index == null) {
                needKeyframe = true;
                return false;
            }
            ByteBuffer in = codec.getInputBuffer(index);
            if (in == null) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                needKeyframe = true;
                return false;
            }
            in.clear();
            int offset = 0, written = 0;
            while (offset < length) {
                if (lengthSize > length - offset) throw new IllegalArgumentException("Truncated NAL length");
                int n = 0;
                for (int i = 0; i < lengthSize; i++) n = (n << 8) | (payload[offset++] & 255);
                if (n <= 0 || n > length - offset) throw new IllegalArgumentException("Truncated NAL unit");
                if (4 + n > in.remaining()) {
                    // Less room than asked for: this decoder can not take such frames, the next one may
                    Log.w(TAG, "video decoder " + codecName() + " takes " + in.capacity() + " bytes, the frame needs " + needed);
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                    fail(codecName());
                    return false;
                }
                in.put(START).put(payload, offset, n);
                offset += n;
                written += 4 + n;
            }
            long presentation = (long) (timestamp * 1_000_000);
            arrivals.put(presentation, System.nanoTime());
            codec.queueInputBuffer(index, 0, written, presentation, 0);
            needKeyframe = false;
            return true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "video decoder failed: " + codecName(), e);
            fail(codecName());
            return false;
        }
    }

    /** The frame's size as the decoder takes it, a start code before each NAL unit; -1 when its lengths are broken. */
    private int annexBSize(byte[] payload, int length) {
        int offset = 0, total = 0;
        while (offset < length) {
            if (lengthSize > length - offset) return -1;
            int n = 0;
            for (int i = 0; i < lengthSize; i++) n = (n << 8) | (payload[offset++] & 255);
            if (n <= 0 || n > length - offset) return -1;
            offset += n;
            total += 4 + n;
        }
        return total;
    }

    /** Output has no queue to drain any more: the callbacks render each picture as soon as the decoder has it. */
    synchronized void drain() {
        if (codec == null) return;
        // The tick still comes: a stream that renders nothing says so once a minute
        report(System.nanoTime());
    }

    /** The decoder's callbacks: input indices for the reader, pictures straight to the surface. */
    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override public void onInputBufferAvailable(MediaCodec c, int index) {
            synchronized (MacVideo.this) {
                if (c != codec) return;
                freeInput.add(index);
                MacVideo.this.notifyAll();  // a fresh decoder waits for its first buffers
            }
        }

        @Override public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo bufferInfo) {
            synchronized (MacVideo.this) {
                if (c != codec) return;
                try {
                    // The surface now: the stream is live, there is nothing to pace
                    c.releaseOutputBuffer(index, true);
                } catch (RuntimeException e) {
                    Log.w(TAG, "video output", e);
                    fail(codecName());
                }
            }
        }

        @Override public void onError(MediaCodec c, MediaCodec.CodecException e) {
            synchronized (MacVideo.this) {
                if (c != codec) return;
                Log.w(TAG, "video decoder error", e);
                fail(codecName());
            }
        }

        @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
            synchronized (MacVideo.this) {
                if (c != codec) return;
                try {
                    outputFormat(format);
                } catch (RuntimeException e) {
                    // A format with no size in it: the picture still decodes, only the letterbox keeps the last size
                    Log.w(TAG, "video output format", e);
                }
            }
        }
    };

    /** When a picture reached the screen, against when its frame arrived: a minute of these in one line. */
    private final MediaCodec.OnFrameRenderedListener frameRendered = (c, presentationTimeUs, nanoTime) -> {
        synchronized (MacVideo.this) {
            if (c != codec) return;
            // Some decoders (this phone's) give the render time on the wall clock, not System.nanoTime's: brought
            // to the arrival's clock, else the median reads as the date
            long mono = System.nanoTime();
            if (Math.abs(nanoTime - mono) > 3_600_000_000_000L)
                nanoTime = nanoTime - System.currentTimeMillis() * 1_000_000L + mono;
            Long arrival = arrivals.remove(presentationTimeUs);
            if (arrival != null && nanoTime >= arrival) renderSamples.add(nanoTime - arrival);
            report(nanoTime);
        }
    };

    private Handler handler() {
        if (handler == null) {
            thread = new HandlerThread("mac-video", android.os.Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }

    private void outputFormat(MediaFormat f) {
        int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        if (w != width || h != height) {
            width = w;
            height = h;
            sizes.videoSize(w, h);
        }
    }

    /**
     * Once a minute: the median and p95 of the time between a frame's arrival and its rendering, and how many
     * arrived that never rendered. Frames still younger than LOST_NS may render in the next minute.
     */
    private void report(long now) {
        if (statsAt == 0) {
            statsAt = now;
            return;
        }
        if (now - statsAt < STATS_NS) return;
        int n = renderSamples.size();
        String median = "-", p95 = "-";
        if (n > 0) {
            long[] sorted = new long[n];
            for (int i = 0; i < n; i++) sorted[i] = renderSamples.get(i);
            Arrays.sort(sorted);
            double middle = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
            int at = (int) Math.ceil(n * 0.95) - 1;
            if (at < 0) at = 0;
            if (at >= n) at = n - 1;
            median = String.format(Locale.ROOT, "%.1f", middle / 1e6);
            p95 = String.format(Locale.ROOT, "%.1f", sorted[at] / 1e6);
        }
        int lost = 0;
        for (Iterator<Map.Entry<Long, Long>> it = arrivals.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() > LOST_NS) {
                lost++;
                it.remove();
            }
        }
        long seconds = Math.max(1, (now - statsAt) / 1_000_000_000L);
        Log.i(TAG, "video: " + n + " frames in " + seconds + " s, render after arrival median " + median
                + " ms, p95 " + p95 + " ms; " + lost + " arrived but never rendered");
        renderSamples.clear();
        statsAt = now;
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

    /** Called with the lock held (from parameters or frame): the next keyframe starts a fresh decoder. */
    private boolean start(Surface surface) {
        Handler h = handler();
        while (true) {
            String requested = next();
            if (requested == null) {
                unavailable = true;
                Log.w(TAG, "no HEVC decoder works");
                return false;
            }
            MediaCodec made = null;
            try {
                try {
                    made = open(requested, surface, true, h);
                } catch (RuntimeException | IOException first) {
                    // Some decoders refuse an operating rate at configure: the same decoder without it
                    Log.d(TAG, "video decoder " + (requested.isEmpty() ? "of the platform" : requested)
                            + " refused the operating rate, trying without", first);
                    made = open(requested, surface, false, h);
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "video decoder could not start: " + (requested.isEmpty() ? "the platform's" : requested), e);
                softwareOnly = true;
                failed.add(requested);
                continue;
            }
            try {
                codec = made;
                codecSurface = surface;
                freeInput.clear();
                arrivals.clear();
                needKeyframe = true;
                made.start();
                try {
                    made.setOnFrameRenderedListener(frameRendered, h);
                } catch (RuntimeException e) {
                    Log.w(TAG, "video render timing unavailable", e);
                }
            } catch (RuntimeException e) {
                String name = requested.isEmpty() ? "" : requested;
                codec = null;
                codecSurface = null;
                freeInput.clear();
                arrivals.clear();
                try {
                    name = made.getName();
                } catch (RuntimeException ignored) { }
                try {
                    made.release();
                } catch (RuntimeException ignored) { }
                Log.w(TAG, "video decoder could not start: " + name, e);
                softwareOnly = true;
                failed.add(requested);
                if (!name.isEmpty()) failed.add(name);
                continue;
            }
            // The reader takes input buffers without waiting; give a fresh decoder this long to offer one
            long until = SystemClock.uptimeMillis() + FIRST_BUFFER_MS;
            while (freeInput.isEmpty()) {
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // A screen went away while the buffers were awaited: release() took this decoder back
            if (codec != made) return false;
            Log.i(TAG, "video decoder: " + made.getName() + " at "
                    + (spsWidth > 0 && spsHeight > 0 ? spsWidth + "x" + spsHeight : "its own size"));
            return true;
        }
    }

    /** One decoder, asynchronous from the start: the callback is set before configure, which picks the mode. */
    private MediaCodec open(String requested, Surface surface, boolean operatingRate, Handler h) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,
                spsWidth > 0 ? spsWidth : 1920, spsHeight > 0 ? spsHeight : 1080);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // Frames can be a Retina screen's worth: don't let a small default buffer refuse a keyframe
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize);
        if (operatingRate) format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
        MediaCodec made = requested.isEmpty() ? MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                : MediaCodec.createByCodecName(requested);
        boolean ok = false;
        try {
            made.setCallback(callback, h);
            made.configure(format, surface, null, 0);
            made.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            ok = true;
            return made;
        } finally {
            if (!ok) {
                try {
                    made.release();
                } catch (RuntimeException ignored) { }
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
        MediaCodec c = codec;
        codec = null;
        codecSurface = null;
        freeInput.clear();
        arrivals.clear();
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) { }
        try {
            c.release();
        } catch (RuntimeException ignored) { }
        // The next decoder says its size again, also when it is the same: whoever shows the picture now (another
        // MacActivity, after this Mac was off the screen) has not heard it and would stretch the picture over its screen
        width = height = 0;
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
        spsWidth = spsHeight = 0;
        needKeyframe = true;
        width = height = 0;
    }
}
